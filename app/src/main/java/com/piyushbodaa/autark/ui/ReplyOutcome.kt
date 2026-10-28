package com.piyushbodaa.autark.ui

import com.piyushbodaa.autark.data.ReplyProblem
import com.piyushbodaa.autark.engine.LlamaEngine

internal data class ReplyOutcome(val cut: Boolean = false, val problem: ReplyProblem? = null)

internal fun replyOutcome(end: LlamaEngine.End): ReplyOutcome = when (end) {
    LlamaEngine.End.Done, LlamaEngine.End.Stopped -> ReplyOutcome()
    LlamaEngine.End.Length -> ReplyOutcome(cut = true)
    LlamaEngine.End.Full -> ReplyOutcome(problem = ReplyProblem.ContextFull)
    LlamaEngine.End.Failed, LlamaEngine.End.None -> ReplyOutcome(problem = ReplyProblem.Failed)
}
