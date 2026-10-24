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

    /** Throws away the last answer and asks again. */
    fun regenerate() {
        if (_generating.value) return
        val conversation = _current.value
        if (conversation.messages.lastOrNull()?.isUser == false) {
            reply(conversation.copy(messages = conversation.messages.dropLast(1)))
        }
    }

    fun stop() = c.engine.stop()

    private fun reply(conversation: Conversation) {
        if (modelStatus.value != ModelManager.Status.Ready) {
            _current.value = conversation
            _notice.value = "Autark is still setting up."
            return
        }
        val s = settings.value
        _generating.value = true
        _current.value = conversation.copy(messages = conversation.messages + Message("assistant", "", model = model.name))

        replyJob = viewModelScope.launch {
            try {
                c.chats.save(conversation)
                if (!c.engine.load(model.id, c.models.file.path, s.contextSize, threadsFor(s))) {
                    _notice.value = (engineState.value as? LlamaEngine.State.Failed)?.message ?: "Couldn't start the model."
                    return@launch
                }
                val (roles, contents) = transcript(conversation, s)
                val options = LlamaEngine.Options(
                    thinking = s.thinking,
                    temperature = s.temperature,
                    topP = 0.95f,
                    maxTokens = s.maxTokens,
                )
                val text = StringBuilder()
                c.engine.generate(roles, contents, options).collect { event ->
                    when (event) {
                        is LlamaEngine.Event.Started -> Unit
                        is LlamaEngine.Event.Text -> {
                            text.append(event.text)
                            updateReply { it.copy(text = text.toString()) }
                        }
                        is LlamaEngine.Event.Finished -> updateReply { it.copy(tokens = event.tokens, seconds = event.seconds) }
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
                    c.chats.save(final)
                    _generating.value = false
                }
            }
        }
    }

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

    fun newChat() = afterStopping { _current.value = Conversation() }

    fun openChat(id: String) = afterStopping {
        conversations.value.firstOrNull { it.id == id }?.let { _current.value = it }
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
}
