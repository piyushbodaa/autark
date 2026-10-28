package com.piyushbodaa.autark.data

/** Saved with a partial answer so reopening its chat still explains why it ended. */
enum class ReplyProblem(val message: String) {
    Failed("This reply stopped unexpectedly. Tap Retry to try again."),
    ContextFull("This reply reached the conversation limit. Try a shorter reply, a new chat, or a larger Memory setting."),
}
