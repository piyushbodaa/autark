package com.piyushbodaa.autark.engine

/** Thin binding over autark_jni.cpp. Use [LlamaEngine]; it keeps every call on one thread. */
internal object Native {
    init {
        System.loadLibrary("autark")
    }

    external fun init(nativeLibDir: String)

    /** Returns the context size allocated, or a negative error code. */
    external fun load(path: String, contextSize: Int, threads: Int): Int

    external fun describe(): ByteArray?

    external fun supportsThinking(): Boolean

    /** Returns the prompt length in tokens, or a negative error code. */
    external fun prompt(
        roles: Array<ByteArray>,
        contents: Array<ByteArray>,
        thinking: Boolean,
        temperature: Float,
        topP: Float,
        maxTokens: Int,
    ): Int

    /** The next piece of text (possibly empty), or null when the reply is complete. */
    external fun next(): ByteArray?

    /** Safe to call from any thread. */
    external fun abort()

    external fun unload()
}
