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
 */
class LlamaEngine(private val nativeLibDir: String) {

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
        data class Finished(val tokens: Int, val seconds: Double) : Event
    }

    data class Options(
        val thinking: Boolean,
        val temperature: Float,
        val topP: Float,
        val maxTokens: Int,
    )

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "llama").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val mutex = Mutex()
    private var initialized = false
    private var loadedKey: String? = null

    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Loads the model unless it is already loaded with the same settings. */
    suspend fun load(modelId: String, path: String, contextSize: Int, threads: Int): Boolean = mutex.withLock {
        withContext(dispatcher) {
            val key = "$modelId|$path|$contextSize|$threads"
            if (key == loadedKey && _state.value is State.Ready) return@withContext true
            if (!initialized) {
                Native.init(nativeLibDir)
                initialized = true
            }
            _state.value = State.Loading(modelId)
            val allocated = Native.load(path, contextSize, threads)
            if (allocated < 0) {
                loadedKey = null
                _state.value = State.Failed(modelId, messageFor(allocated))
                false
            } else {
                loadedKey = key
                _state.value = State.Ready(
                    modelId = modelId,
                    description = Native.describe()?.toString(Charsets.UTF_8).orEmpty(),
                    contextSize = allocated,
                    canThink = Native.supportsThinking(),
                )
                true
            }
        }
    }

    /**
     * Streams a reply to the conversation. [roles] and [contents] are parallel lists of
     * messages ("system", "user" or "assistant"). Call [stop] to end the reply early;
     * the flow then finishes normally with whatever was generated.
     */
    fun generate(roles: List<String>, contents: List<String>, options: Options): Flow<Event> = flow {
        mutex.withLock {
            val promptTokens = Native.prompt(
                roles.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                contents.map { it.toByteArray(Charsets.UTF_8) }.toTypedArray(),
                options.thinking,
                options.temperature,
                options.topP,
                options.maxTokens,
            )
            if (promptTokens < 0) throw EngineException(promptTokens, messageFor(promptTokens))
            emit(Event.Started(promptTokens))

            val start = System.nanoTime()
            var tokens = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val piece = Native.next() ?: break
                tokens++
                if (piece.isNotEmpty()) emit(Event.Text(piece.toString(Charsets.UTF_8)))
            }
            emit(Event.Finished(tokens, (System.nanoTime() - start) / 1e9))
        }
    }.flowOn(dispatcher)

    fun stop() = Native.abort()

    /** Frees the model's memory if nothing is running; it reloads on the next message. */
    suspend fun releaseIfIdle() {
        if (!mutex.tryLock()) return
        try {
            withContext(dispatcher) {
                if (loadedKey != null) {
                    Native.unload()
                    loadedKey = null
                    _state.value = State.Empty
                }
            }
        } finally {
            mutex.unlock()
        }
    }

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
        /** Big cores do the work; little cores only slow the others down. */
        fun defaultThreads(): Int {
            val cores = Runtime.getRuntime().availableProcessors()
            return (cores - 2).coerceIn(2, 4)
        }
    }
}
