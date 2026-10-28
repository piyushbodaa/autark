package com.piyushbodaa.autark.engine

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

class EngineException(val code: Int, message: String) : Exception(message) {
    val wasStopped get() = code == ERR_ABORTED
}

private const val ERR_NO_MODEL = -1
private const val ERR_LOAD_FAILED = -2
private const val ERR_CONTEXT = -3
private const val ERR_TOO_LONG = -4
private const val ERR_DECODE = -5
private const val ERR_ABORTED = -6
private const val ERR_TEMPLATE = -7

/**
 * Runs one model at a time. llama.cpp contexts are not thread-safe, so every native call
 * goes through a single dedicated thread, and a mutex keeps loads and replies from overlapping.
 * The conversation in the model's cache is kept in [sessionFile] whenever the model lets go,
 * so that picking the chat up again doesn't mean reading all of it again.
 */
class LlamaEngine internal constructor(
    private val nativeLibDir: String,
    private val sessionFile: File,
    private val backend: NativeBackend?,
) {
    constructor(nativeLibDir: String, sessionFile: File) : this(nativeLibDir, sessionFile, null)

    // Keep JNI loading lazy, on the first native call rather than the app's startup thread.
    private val native: NativeBackend get() = backend ?: Native

    sealed interface State {
        data object Empty : State
        data class Loading(val modelId: String) : State
        data class Ready(
            val modelId: String,
            val description: String,
            val contextSize: Int,
            val canThink: Boolean,
        ) : State
        data class Failed(val modelId: String, val message: String) : State
    }

    sealed interface Event {
        data class Started(val promptTokens: Int) : Event
        data class Text(val text: String) : Event
        data class Finished(val tokens: Int, val seconds: Double, val end: End) : Event
    }

    /** How a reply ended; the same order as autark::End in engine.h. */
    enum class End { None, Done, Length, Stopped, Full, Failed }

    data class Options(
        val thinking: Boolean,
        val temperature: Float,
        val topP: Float,
        val topK: Int,
        val minP: Float,
        /** Counted over the whole reply, the way Qwen's model cards mean it. */
        val presencePenalty: Float,
        val maxTokens: Int,
    )

    /** Threads for generating a reply, and for reading the prompt (which keeps more cores busy). */
    data class Threads(val generation: Int, val batch: Int)

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "llama").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var initialized = false
    private var loadedKey: String? = null

    @Volatile
    private var stopRequested = false

    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Loads the model unless it is already loaded with the same settings. */
    suspend fun load(modelId: String, path: String, contextSize: Int, threads: Threads): Boolean = mutex.withLock {
        withContext(dispatcher) {
            val key = "$modelId|$path|$contextSize|$threads"
            if (key == loadedKey && _state.value is State.Ready) return@withContext true
            if (!initialized) {
                native.init(nativeLibDir)
                initialized = true
            }
            if (loadedKey != null) native.saveSession(sessionFile.path)  // reloading with new settings
            _state.value = State.Loading(modelId)
            val allocated = native.load(path, contextSize, threads.generation, threads.batch)
            if (allocated < 0) {
                loadedKey = null
                _state.value = State.Failed(modelId, messageFor(allocated))
                false
            } else {
                loadedKey = key
                if (sessionFile.exists() && !native.loadSession(sessionFile.path)) sessionFile.delete()
                _state.value = State.Ready(
                    modelId = modelId,
                    description = native.describe()?.toString(Charsets.UTF_8).orEmpty(),
                    contextSize = allocated,
                    canThink = native.supportsThinking(),
                )
                true
            }
        }
    }

    /**
     * Streams a reply to the conversation. [roles] and [contents] are parallel lists of
     * messages ("system", "user" or "assistant"). With [resume], the last message is a reply
     * that was cut off, and the flow carries on with it. Call [stop] to end the reply early;
     * the flow then finishes normally with whatever was generated.
     * Create [request] before loading when Stop must also cover that pending work.
     */
    fun generate(
        roles: List<String>, contents: List<String>, options: Options,
        resume: Boolean = false, request: ReplyRequest = ReplyRequest(),
    ): Flow<Event> = flow {
        stopRequested = false
        mutex.withLock {
            // Stop may be tapped while a prefill still holds the engine.
            if (request.isStopped || stopRequested) {
                emit(Event.Finished(0, 0.0, End.Stopped))
                return@withLock
            }
            val promptTokens = native.prompt(
                bytes(roles),
                bytes(contents),
                resume,
                options.thinking,
                options.temperature,
                options.topP,
                options.topK,
                options.minP,
                options.presencePenalty,
                options.maxTokens,
            )
            if (promptTokens < 0) throw EngineException(promptTokens, messageFor(promptTokens))
            if (request.isStopped || stopRequested) native.stop()
            emit(Event.Started(promptTokens))

            val start = System.nanoTime()
            var tokens = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val piece = native.next() ?: break
                tokens++
                if (piece.isNotEmpty()) emit(Event.Text(piece.toString(Charsets.UTF_8)))
            }
            val end = End.entries.getOrElse(native.ended()) { End.None }
            emit(Event.Finished(tokens, (System.nanoTime() - start) / 1e9, end))
        }
    }.flowOn(dispatcher)

    fun stop() {
        stopRequested = true
        native.stop()
    }

    /**
     * Gets the model through a conversation before the user sends their next message, so that
     * only that message is left to process. A reply waiting to start simply queues behind it.
     */
    suspend fun prefill(roles: List<String>, contents: List<String>, thinking: Boolean, maxTokens: Int) {
        mutex.withLock {
            withContext(dispatcher) {
                if (_state.value is State.Ready) native.prefill(bytes(roles), bytes(contents), thinking, maxTokens)
            }
        }
    }

    /** Ends a running [prefill], for when the user moves to another chat. */
    fun cancelPrefill() = native.cancelPrefill()

    /** Writes the conversation in the cache to disk, once nothing else is running. */
    suspend fun saveSession() = mutex.withLock {
        withContext(dispatcher) {
            if (loadedKey != null) native.saveSession(sessionFile.path)
        }
    }

    /**
     * Frees the model's memory once nothing is running, unless [stillWanted] by then. The
     * conversation is kept on disk; the model reloads on the next message.
     */
    suspend fun release(stillWanted: () -> Boolean) = mutex.withLock {
        withContext(dispatcher) {
            if (loadedKey != null && !stillWanted()) {
                native.saveSession(sessionFile.path)
                native.unload()
                loadedKey = null
                _state.value = State.Empty
            }
        }
    }

    /** Drops the conversation from the cache and from disk, for when chats are deleted. */
    suspend fun forget() = mutex.withLock {
        withContext(dispatcher) {
            if (loadedKey != null) native.forget()
            sessionFile.delete()
        }
    }

    private fun bytes(strings: List<String>) = strings.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray()

    private fun messageFor(code: Int): String = when (code) {
        ERR_NO_MODEL -> "No model is loaded yet."
        ERR_LOAD_FAILED -> "This model file couldn't be opened. It may be damaged, or use a format this version of Autark can't read."
        ERR_CONTEXT -> "Not enough memory for this model at the current context size. Try a smaller context in Settings, or a smaller model."
        ERR_TOO_LONG -> "That message is too long for the model's memory. Try a shorter message or a larger context in Settings."
        ERR_DECODE -> "The model stopped unexpectedly. Try again."
        ERR_ABORTED -> "Stopped."
        ERR_TEMPLATE -> "This model's chat format isn't supported."
        else -> "Something went wrong ($code)."
    }

    companion object {
        /**
         * Big cores do the work; little cores only slow the others down, because every step
         * waits for the slowest thread. The kernel rates each core's capacity (1024 for the
         * fastest), which tells big from little better than clock speed does.
         *
         * Measured on phones in Firebase Test Lab (tools/device-test), with the 2B model:
         * generation is bound by memory bandwidth, so it peaks at one thread fewer than there are
         * big cores, and at four at most (a Galaxy A35 makes 9.2 tokens/s on 2 threads and 8.6 on
         * 4). Reading a prompt is arithmetic and peaks at five. Past those, the threads keep
         * waiting on each other and on the rest of the phone: on all 7 of its cores, a Pixel 11
         * reads prompts 43% slower than on 5, and generates 1.8 tokens/s against 22 on 4. With
         * only two big cores, as in most budget phones, three little ones are worth adding for
         * prompts: a Galaxy A15 reads them 27% faster on 5 threads than on its 2 big cores.
         */
        fun defaultThreads(): Threads {
            val capacities = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                .orEmpty()
                .mapNotNull { runCatching { File(it, "cpu_capacity").readText().trim().toInt() }.getOrNull() }
            val cores = capacities.size.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
            val big = if (capacities.isNotEmpty()) {
                val fastest = capacities.max()
                capacities.count { it * 2 >= fastest }
            } else {
                cores - 2
            }
            return Threads(
                generation = (big - 1).coerceIn(2, 4),
                batch = (if (big <= 2) cores - 3 else big).coerceIn(2, 5),
            )
        }
    }
}
