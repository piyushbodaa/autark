package com.piyushbodaa.autark.engine

/** One reply, including time spent saving its chat or waiting for a model/prefill. */
class ReplyRequest {
    @Volatile
    var isStopped: Boolean = false
        private set

    fun stop() {
        isStopped = true
    }
}
