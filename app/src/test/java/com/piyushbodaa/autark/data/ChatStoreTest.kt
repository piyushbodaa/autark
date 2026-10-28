package com.piyushbodaa.autark.data

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun partialReplyProblemsSurviveReopeningTheirChat() = runBlocking {
        val dir = folder.newFolder("chats")
        val conversation = Conversation(messages = listOf(
            Message("user", "question"),
            Message("assistant", "partial failed", problem = ReplyProblem.Failed),
            Message("assistant", "partial full", problem = ReplyProblem.ContextFull),
            Message("assistant", "length limited", cut = true),
            Message("assistant", "completed"),
        ))
        ChatStore(dir).save(conversation)
        val reopened = ChatStore(dir)
        reopened.load()
        assertEquals(conversation, reopened.conversations.value.single())
    }

    @Test fun olderChatsKeepTheirContentAndContinueFlag() = runBlocking {
        val dir = folder.newFolder("legacy")
        File(dir, "old.json").writeText("""
            {"id":"old","title":"Earlier chat","updatedAt":1,"messages":[
                {"role":"user","text":"hello"},
                {"role":"assistant","text":"partial","cut":true}
            ]}
        """.trimIndent())
        val reopened = ChatStore(dir)
        reopened.load()
        val last = reopened.conversations.value.single().messages.last()
        assertEquals("partial", last.text)
        assertEquals(true, last.cut)
        assertNull(last.problem)
    }
}
