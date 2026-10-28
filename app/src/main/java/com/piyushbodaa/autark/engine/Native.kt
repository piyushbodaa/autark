package com.piyushbodaa.autark.engine

/** Thin binding over autark_jni.cpp. Use [LlamaEngine]; it keeps every call on one thread. */
internal object Native {
    init {
        System.loadLibrary("autark")
    }

    external fun init(nativeLibDir: String)

    /** Returns the context size allocated, or a negative error code. */
    external fun load(path: String, contextSize: Int, threads: Int, batchThreads: Int): Int

    external fun describe(): ByteArray?

    external fun supportsThinking(): Boolean

    /** Clears the stop flag once, before a user request starts loading. Safe from any thread. */
    external fun beginReply()

    /** Returns a fitting UTF-8 prefix length, or a negative error code. */
    external fun fitShared(
        roles: Array<ByteArray>, contents: Array<ByteArray>, instruction: ByteArray, text: ByteArray,
        thinking: Boolean, maxTokens: Int,
    ): Int

    /**
     * Returns the prompt length in tokens, or a negative error code. With [resume], the last
     * message is a reply that was cut off, and [next] carries on with it.
     */
    external fun prompt(
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
        chatId: ByteArray,
    ): Int

    /** How the last reply ended, as LlamaEngine.End's ordinal. */
    external fun ended(): Int

    /** The next piece of text (possibly empty), or null when the reply is complete. */
    external fun next(): ByteArray?

    /**
     * Processes a conversation ahead of the next [prompt], so that only the new message is left
     * to do. Returns the number of tokens cached, or a negative error code.
     */
    external fun prefill(
        roles: Array<ByteArray>, contents: Array<ByteArray>, thinking: Boolean, maxTokens: Int, chatId: ByteArray,
    ): Int

    /** Ends the reply being prepared or generated. Safe to call from any thread. */
    external fun stop()

    /** Ends a [prefill] part-way. Safe to call from any thread. */
    external fun cancelPrefill()

    /** Writes the conversation in the cache to [path] if it changed. */
    external fun saveSession(path: String): Boolean

    /** Picks up a conversation written by [saveSession] for the same model. */
    external fun loadSession(path: String): Boolean

    /** The owners of the live and saved caches, which may be different chats. */
    external fun chatId(): ByteArray?
    external fun sessionChatId(path: String): ByteArray?

    /** Empties the cache. */
    external fun forget()

    external fun unload()
}
