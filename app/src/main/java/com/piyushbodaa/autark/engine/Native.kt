package com.piyushbodaa.autark.engine

/** JNI operations; the replaceable backend lets local tests exercise the real engine scheduler. */
internal interface NativeBackend {
    fun init(nativeLibDir: String)
    fun load(path: String, contextSize: Int, threads: Int, batchThreads: Int): Int
    fun describe(): ByteArray?
    fun supportsThinking(): Boolean
    fun prompt(roles: Array<ByteArray>, contents: Array<ByteArray>, resume: Boolean,
        thinking: Boolean, temperature: Float, topP: Float, topK: Int, minP: Float,
        presencePenalty: Float, maxTokens: Int): Int
    fun ended(): Int
    fun next(): ByteArray?
    fun prefill(roles: Array<ByteArray>, contents: Array<ByteArray>, thinking: Boolean, maxTokens: Int): Int
    fun stop()
    fun cancelPrefill()
    fun saveSession(path: String): Boolean
    fun loadSession(path: String): Boolean
    fun forget()
    fun unload()
}

/** Thin binding over autark_jni.cpp. Use [LlamaEngine] to serialize model operations. */
internal object Native : NativeBackend {
    init {
        System.loadLibrary("autark")
    }

    override external fun init(nativeLibDir: String)

    /** Returns the context size allocated, or a negative error code. */
    override external fun load(path: String, contextSize: Int, threads: Int, batchThreads: Int): Int

    override external fun describe(): ByteArray?

    override external fun supportsThinking(): Boolean

    /**
     * Returns the prompt length in tokens, or a negative error code. With [resume], the last
     * message is a reply that was cut off, and [next] carries on with it.
     */
    override external fun prompt(
        roles: Array<ByteArray>,
        contents: Array<ByteArray>,
        resume: Boolean,
        thinking: Boolean,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        presencePenalty: Float,
        maxTokens: Int,
    ): Int

    /** How the last reply ended, as LlamaEngine.End's ordinal. */
    override external fun ended(): Int

    /** The next piece of text (possibly empty), or null when the reply is complete. */
    override external fun next(): ByteArray?

    /**
     * Processes a conversation ahead of the next [prompt], so that only the new message is left
     * to do. Returns the number of tokens cached, or a negative error code.
     */
    override external fun prefill(roles: Array<ByteArray>, contents: Array<ByteArray>, thinking: Boolean, maxTokens: Int): Int

    /** Ends the reply being prepared or generated. Safe to call from any thread. */
    override external fun stop()

    /** Ends a [prefill] part-way. Safe to call from any thread. */
    override external fun cancelPrefill()

    /** Writes the conversation in the cache to [path] if it changed. */
    override external fun saveSession(path: String): Boolean

    /** Picks up a conversation written by [saveSession] for the same model. */
    override external fun loadSession(path: String): Boolean

    /** Empties the cache. */
    override external fun forget()

    override external fun unload()
}
