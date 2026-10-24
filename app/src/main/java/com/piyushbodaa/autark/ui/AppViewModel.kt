package com.piyushbodaa.autark.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.piyushbodaa.autark.AutarkApplication
import com.piyushbodaa.autark.data.Conversation
import com.piyushbodaa.autark.data.Message
import com.piyushbodaa.autark.data.Settings
import com.piyushbodaa.autark.engine.EngineException
import com.piyushbodaa.autark.engine.LlamaEngine
import com.piyushbodaa.autark.models.CatalogModel
import com.piyushbodaa.autark.models.ModelManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as AutarkApplication).container

    val settings: StateFlow<Settings> = c.settings.settings
    val engineState: StateFlow<LlamaEngine.State> = c.engine.state
    val conversations: StateFlow<List<Conversation>> = c.chats.conversations
    val model: CatalogModel = c.models.model
    val modelStatus: StateFlow<ModelManager.Status> = c.models.status

    private val _current = MutableStateFlow(Conversation())
    val current: StateFlow<Conversation> = _current.asStateFlow()

    private val _generating = MutableStateFlow(false)
    val generating: StateFlow<Boolean> = _generating.asStateFlow()

    /** A reply is waiting for the model to load or to read the chat, before its first word. */
    private val _reading = MutableStateFlow(false)
    val reading: StateFlow<Boolean> = _reading.asStateFlow()

    /** Text shared from another app, waiting for the user to say what to do with it. */
    private val _shared = MutableStateFlow<Shared?>(null)
    val shared: StateFlow<Shared?> = _shared.asStateFlow()

    data class Shared(val text: String, val shortened: Boolean)

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var replyJob: Job? = null
    private var prefillJob: Job? = null
    private var prefilledFor: Pair<String, Int>? = null  // conversation id and message count

    init {
        viewModelScope.launch { c.chats.load() }

        // Keep the model loaded so the first reply starts right away.
        viewModelScope.launch {
            combine(settings, modelStatus) { s, status ->
                if (status == ModelManager.Status.Ready) s.contextSize to threadsFor(s) else null
            }.distinctUntilChanged().collectLatest { request ->
                if (request != null && !_generating.value) {
                    c.engine.load(model.id, c.models.file.path, request.first, request.second)
                }
            }
        }
    }

    // ---- setup ----

    fun startDownload() = c.models.download()

    fun cancelDownload() = c.models.cancel()

    // ---- the app on screen ----

    /**
     * Back on screen. If Android took the model's memory while the app was away, load it again now
     * instead of at the first keystroke, so a quick message doesn't wait for all of the load.
     */
    fun onAppVisible() {
        if (modelStatus.value != ModelManager.Status.Ready || engineState.value != LlamaEngine.State.Empty) return
        viewModelScope.launch {
            val s = settings.value
            c.engine.load(model.id, c.models.file.path, s.contextSize, threadsFor(s))
        }
    }

    /** Off screen, where Android may stop the app at any moment: keep a reply that's still coming in. */
    fun onAppHidden() {
        if (_generating.value) viewModelScope.launch { c.chats.save(_current.value) }
    }

    // ---- chat ----

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _generating.value) return
        val conversation = _current.value
        reply(
            conversation.copy(
                title = conversation.title.ifEmpty { titleFrom(trimmed) },
                updatedAt = System.currentTimeMillis(),
                messages = conversation.messages + Message("user", trimmed),
            ),
        )
    }

    /** Replaces the user's last message, and the reply to it, and asks again. */
    fun edit(text: String) {
        val trimmed = text.trim()
        val conversation = _current.value
        val last = conversation.messages.indexOfLast { it.isUser }
        if (trimmed.isEmpty() || _generating.value || last < 0) return
        reply(
            conversation.copy(
                title = if (last == 0) titleFrom(trimmed) else conversation.title,
                updatedAt = System.currentTimeMillis(),
                messages = conversation.messages.take(last) + Message("user", trimmed),
            ),
        )
    }

    /** Throws away the last answer and asks again, or answers a question that never got its answer. */
    fun regenerate() {
        if (_generating.value) return
        val conversation = _current.value
        val last = conversation.messages.lastOrNull() ?: return
        reply(if (last.isUser) conversation else conversation.copy(messages = conversation.messages.dropLast(1)))
    }

    /** Carries on with a reply that stopped short. */
    fun continueReply() {
        if (_generating.value) return
        val conversation = _current.value
        if (conversation.messages.lastOrNull()?.let { !it.isUser && it.cut } == true) reply(conversation, resume = true)
    }

    fun stop() = c.engine.stop()

    /** With [resume], the conversation ends with a reply that stopped short, and this carries on with it. */
    private fun reply(conversation: Conversation, resume: Boolean = false) {
        if (modelStatus.value != ModelManager.Status.Ready) {
            _current.value = conversation
            _notice.value = "Autark is still setting up."
            return
        }
        val s = settings.value
        val earlier = if (resume) conversation.messages.last() else Message("assistant", "", model = model.name)
        _generating.value = true
        _reading.value = true
        _current.value = if (resume) conversation else conversation.copy(messages = conversation.messages + earlier)

        replyJob = viewModelScope.launch {
            try {
                c.chats.save(conversation)
                if (!c.engine.load(model.id, c.models.file.path, s.contextSize, threadsFor(s))) {
                    _notice.value = (engineState.value as? LlamaEngine.State.Failed)?.message ?: "Couldn't start the model."
                    return@launch
                }
                val (roles, contents) = transcript(conversation, s)
                val text = StringBuilder(earlier.text)
                var savedAt = System.nanoTime()
                c.engine.generate(roles, contents, options(s), resume).collect { event ->
                    when (event) {
                        is LlamaEngine.Event.Started -> _reading.value = false
                        is LlamaEngine.Event.Text -> {
                            text.append(event.text)
                            // Marked cut until it finishes, in case Android stops the app first.
                            updateReply { it.copy(text = text.toString(), cut = true) }
                            if (System.nanoTime() - savedAt > SAVE_EVERY_NANOS) {
                                savedAt = System.nanoTime()
                                c.chats.save(_current.value)
                            }
                        }
                        is LlamaEngine.Event.Finished -> updateReply {
                            it.copy(
                                tokens = earlier.tokens + event.tokens,
                                seconds = earlier.seconds + event.seconds,
                                cut = event.end == LlamaEngine.End.Length,
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EngineException) {
                if (!e.wasStopped) _notice.value = e.message
            } finally {
                withContext(NonCancellable) {
                    val now = _current.value
                    val last = now.messages.lastOrNull()
                    val final = if (last != null && !last.isUser && last.text.isBlank()) {
                        now.copy(messages = now.messages.dropLast(1))
                    } else {
                        now
                    }.copy(updatedAt = System.currentTimeMillis())
                    _current.value = final
                    _reading.value = false
                    c.chats.save(final)
                    _generating.value = false
                }
            }
        }
    }

    /**
     * The temperature is the Creativity setting. Qwen's model card recommends a presence penalty of
     * 1.5–2 for Qwen 3.5 2B, but side by side (tools/engine-test/engine-ask, 24 Oct) it made the
     * 2B worse: with it, sums came out wrong (working them out repeats digits, which it penalizes),
     * and one reply said to share a UPI PIN with delivery partners. So chats keep llama.cpp's usual
     * sampling, and thinking uses the card's setting for precise work, which has no penalty.
     */
    private fun options(s: Settings) = LlamaEngine.Options(
        thinking = s.thinking,
        temperature = s.temperature,
        topP = 0.95f,
        topK = if (s.thinking) 20 else 40,
        minP = if (s.thinking) 0f else 0.05f,
        presencePenalty = 0f,
        maxTokens = s.maxTokens,
    )

    /** What the model sees: the system prompt, then each message, with earlier reasoning left out. */
    private fun transcript(conversation: Conversation, s: Settings): Pair<List<String>, List<String>> {
        val roles = mutableListOf<String>()
        val contents = mutableListOf<String>()
        if (s.systemPrompt.isNotBlank()) {
            roles += "system"
            contents += s.systemPrompt
        }
        conversation.messages.forEach {
            roles += it.role
            contents += if (it.isUser) it.text else Thinking.split(it.text).answer
        }
        return roles to contents
    }

    /**
     * The user started typing, so a message is on its way: get the model through the chat so far
     * (and loaded, if Android made it let go) in the meantime, leaving only that message to process.
     */
    fun onTyping() {
        val conversation = _current.value
        val key = conversation.id to conversation.messages.size
        if (_generating.value || prefilledFor == key || modelStatus.value != ModelManager.Status.Ready) return
        prefilledFor = key
        prefillJob?.cancel()
        prefillJob = viewModelScope.launch {
            val s = settings.value
            if (c.engine.load(model.id, c.models.file.path, s.contextSize, threadsFor(s))) {
                val (roles, contents) = transcript(conversation, s)
                c.engine.prefill(roles, contents, s.thinking, s.maxTokens)
            }
        }
    }

    private fun cancelPrefill() {
        prefillJob?.cancel()
        prefillJob = null
        prefilledFor = null
        c.engine.cancelPrefill()
    }

    private fun updateReply(change: (Message) -> Message) = _current.update { conversation ->
        val last = conversation.messages.lastOrNull() ?: return@update conversation
        if (last.isUser) conversation else conversation.copy(messages = conversation.messages.dropLast(1) + change(last))
    }

    fun newChat() = afterStopping {
        _shared.value = null
        _current.value = Conversation()
    }

    fun openChat(id: String) = afterStopping {
        conversations.value.firstOrNull { it.id == id }?.let {
            _shared.value = null
            _current.value = it
        }
    }

    fun deleteChat(id: String) = afterStopping {
        c.chats.delete(id)
        c.engine.forget()  // the model's cache may hold this chat
        if (_current.value.id == id) _current.value = Conversation()
    }

    fun deleteAllChats() = afterStopping {
        c.chats.deleteAll()
        c.engine.forget()
        _current.value = Conversation()
    }

    // ---- text from other apps ----

    /** Text shared from another app, or selected in one, starts a new chat about it. */
    fun share(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        afterStopping {
            _current.value = Conversation()
            _shared.value = Shared(trimmed.take(SHARED_MAX_CHARS).trimEnd(), shortened = trimmed.length > SHARED_MAX_CHARS)
            onTyping()  // a question about it is on its way
        }
    }

    /** Asks about the shared text: [instruction] goes first, then the text. */
    fun sendShared(instruction: String) {
        val shared = _shared.value ?: return
        if (instruction.isBlank() || _generating.value) return
        _shared.value = null
        send("${instruction.trim()}\n\n${shared.text}")
    }

    fun dropShared() {
        _shared.value = null
    }

    private fun afterStopping(action: suspend () -> Unit) {
        cancelPrefill()
        viewModelScope.launch {
            if (_generating.value) {
                c.engine.stop()
                replyJob?.join()
            }
            action()
        }
    }

    fun dismissNotice() {
        _notice.value = null
    }

    // ---- settings ----

    fun updateSettings(change: (Settings) -> Settings) = c.settings.update(change)

    private fun threadsFor(s: Settings) =
        if (s.threads > 0) LlamaEngine.Threads(s.threads, s.threads) else LlamaEngine.defaultThreads()

    private fun titleFrom(text: String): String {
        val line = text.lineSequence().first().trim()
        return if (line.length <= 48) line else line.take(47).trimEnd() + "…"
    }

    private companion object {
        const val SAVE_EVERY_NANOS = 2_000_000_000L

        /** About 1,500 tokens of English, which leaves room for a reply at the default memory setting. */
        const val SHARED_MAX_CHARS = 6000
    }
}
