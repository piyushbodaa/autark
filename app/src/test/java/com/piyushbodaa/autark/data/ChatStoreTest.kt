package com.piyushbodaa.autark.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun failedWriteKeepsSavedChatAndCanBeRetried() = runBlocking {
        val dir = temporary.newFolder()
        val store = ChatStore(dir)
        val original = Conversation(id = "chat", messages = listOf(Message("user", "Hello")))
        assertTrue(store.save(original))
        val file = File(dir, "chat.json")
        val saved = file.readText()
        val updated = original.copy(messages = original.messages + Message("assistant", "Reply"))

        // Opening a directory for writing throws IOException, just like a failed disk write.
        val blockedTemp = File(dir, "chat.json.tmp").apply { mkdir() }
        assertFalse(store.save(updated))
        assertEquals(saved, file.readText())
        assertEquals(listOf(original), store.conversations.value)

        assertTrue(blockedTemp.delete())
        assertTrue(store.save(updated))
        val reloaded = ChatStore(dir)
        reloaded.load()
        assertEquals(listOf(updated), reloaded.conversations.value)
    }

    @Test
    fun failedRenameDoesNotClaimChatWasSaved() = runBlocking {
        val dir = temporary.newFolder()
        val store = ChatStore(dir)
        File(dir, "chat.json").mkdir()
        val chat = Conversation(id = "chat", messages = listOf(Message("user", "Hello")))

        assertFalse(store.save(chat))
        assertTrue(store.conversations.value.isEmpty())
    }
}
