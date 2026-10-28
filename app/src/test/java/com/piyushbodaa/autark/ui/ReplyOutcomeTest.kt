package com.piyushbodaa.autark.ui

import com.piyushbodaa.autark.data.ReplyProblem
import com.piyushbodaa.autark.engine.LlamaEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyOutcomeTest {
    @Test fun successfulAndIntentionalStopsAreNotErrors() {
        assertEquals(ReplyOutcome(), replyOutcome(LlamaEngine.End.Done))
        assertEquals(ReplyOutcome(), replyOutcome(LlamaEngine.End.Stopped))
    }

    @Test fun onlyLengthLimitedRepliesOfferContinue() {
        assertEquals(ReplyOutcome(cut = true), replyOutcome(LlamaEngine.End.Length))
        assertEquals(ReplyOutcome(problem = ReplyProblem.ContextFull), replyOutcome(LlamaEngine.End.Full))
        assertEquals(ReplyOutcome(problem = ReplyProblem.Failed), replyOutcome(LlamaEngine.End.Failed))
        assertEquals(ReplyOutcome(problem = ReplyProblem.Failed), replyOutcome(LlamaEngine.End.None))
    }
}
