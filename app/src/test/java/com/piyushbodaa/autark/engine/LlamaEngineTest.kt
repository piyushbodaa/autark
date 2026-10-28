package com.piyushbodaa.autark.engine

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaEngineTest {
    private val options = LlamaEngine.Options(false, 0.7f, 0.95f, 40, 0.05f, 0f, 1024)
    private val threads = LlamaEngine.Threads(2, 2)

    @Test fun constructingTheEngineDoesNotLoadJniOnTheCallingThread() {
        // Local JVM tests have no libautark: eagerly loading it would fail here.
        LlamaEngine("test", File("unused-test-session"))
    }

    @Test fun stopDuringLoadPreventsPromptAndDoesNotStopTheNextReply() = runBlocking {
        val backend = FakeBackend().apply { blockLoad = true }
        val engine = engine(backend)
        val request = ReplyRequest()
        val loading = async(Dispatchers.Default) { engine.load("test", "none", 4096, threads) }
        try {
            assertTrue(backend.loading.await(5, TimeUnit.SECONDS))
            request.stop()
            engine.stop()
        } finally {
            backend.releaseLoad.countDown()
        }
        assertTrue(loading.await())
        val stopped = engine.generate(listOf("user"), listOf("hello"), options, request = request).toList()
        assertEquals(LlamaEngine.Event.Finished(0, 0.0, LlamaEngine.End.Stopped), stopped.single())
        assertEquals(0, backend.prompts)

        val next = engine.generate(listOf("user"), listOf("next"), options).toList()
        assertEquals(1, backend.prompts)
        assertEquals(LlamaEngine.End.Done, (next.last() as LlamaEngine.Event.Finished).end)
    }

    @Test fun stopWhileWaitingForPrefillPreventsPrompt() = runBlocking {
        val backend = FakeBackend().apply { blockPrefill = true }
        val engine = engine(backend)
        engine.load("test", "none", 4096, threads)
        val prefill = async(Dispatchers.Default) { engine.prefill(listOf("user"), listOf("old"), false, 1024) }
        assertTrue(backend.prefilling.await(5, TimeUnit.SECONDS))
        val request = ReplyRequest()
        val reply = async(Dispatchers.Default) {
            engine.generate(listOf("user"), listOf("new"), options, request = request).toList()
        }
        try {
            request.stop()
            engine.stop()
        } finally {
            backend.releasePrefill.countDown()
        }
        prefill.await()
        assertEquals(LlamaEngine.End.Stopped, (reply.await().last() as LlamaEngine.Event.Finished).end)
        assertEquals(0, backend.prompts)
    }

    @Test fun stopDuringGenerationPreservesTextAndEndsIntentionally() = runBlocking {
        val backend = FakeBackend().apply {
            pieces = ArrayDeque(listOf("partial", "unwanted"))
            blockAfterFirstPiece = true
        }
        val engine = engine(backend)
        engine.load("test", "none", 4096, threads)
        val request = ReplyRequest()
        val events = mutableListOf<LlamaEngine.Event>()
        engine.generate(listOf("user"), listOf("hello"), options, request = request).collect { event ->
            events += event
            if (event is LlamaEngine.Event.Text) {
                request.stop()
                engine.stop()
            }
        }
        assertEquals("partial", events.filterIsInstance<LlamaEngine.Event.Text>().first().text)
        assertEquals(LlamaEngine.End.Stopped, (events.last() as LlamaEngine.Event.Finished).end)
    }

    @Test fun nativeFailureAndContextLimitRemainDistinctEvents() = runBlocking {
        val backend = FakeBackend()
        val engine = engine(backend)
        engine.load("test", "none", 4096, threads)
        for (end in listOf(LlamaEngine.End.Failed, LlamaEngine.End.Full)) {
            backend.end = end
            val events = engine.generate(listOf("user"), listOf("hello"), options).toList()
            assertEquals(end, (events.last() as LlamaEngine.Event.Finished).end)
        }
    }

    private fun engine(backend: NativeBackend) = LlamaEngine("test", File("unused-test-session"), backend)

    private class FakeBackend : NativeBackend {
        val loading = CountDownLatch(1)
        val releaseLoad = CountDownLatch(1)
        val prefilling = CountDownLatch(1)
        val releasePrefill = CountDownLatch(1)
        val stoppedSignal = CountDownLatch(1)
        var blockLoad = false
        var blockPrefill = false
        var blockAfterFirstPiece = false
        var piecesSent = 0
        var prompts = 0
        var pieces = ArrayDeque<String>()
        var end = LlamaEngine.End.Done
        @Volatile var stopped = false
        override fun init(nativeLibDir: String) = Unit
        override fun load(path: String, contextSize: Int, threads: Int, batchThreads: Int): Int {
            loading.countDown()
            if (blockLoad) check(releaseLoad.await(5, TimeUnit.SECONDS))
            return contextSize
        }
        override fun describe() = "test".toByteArray()
        override fun supportsThinking() = true
        override fun prompt(roles: Array<ByteArray>, contents: Array<ByteArray>, resume: Boolean,
            thinking: Boolean, temperature: Float, topP: Float, topK: Int, minP: Float,
            presencePenalty: Float, maxTokens: Int): Int {
            prompts++
            stopped = false // Matches the native prompt's reset.
            return 10
        }
        override fun ended() = (if (stopped) LlamaEngine.End.Stopped else end).ordinal
        override fun next(): ByteArray? {
            if (blockAfterFirstPiece && piecesSent > 0) check(stoppedSignal.await(5, TimeUnit.SECONDS))
            if (stopped) return null
            val piece = pieces.removeFirstOrNull() ?: return null
            piecesSent++
            return piece.toByteArray()
        }
        override fun prefill(roles: Array<ByteArray>, contents: Array<ByteArray>, thinking: Boolean, maxTokens: Int): Int {
            prefilling.countDown()
            if (blockPrefill) check(releasePrefill.await(5, TimeUnit.SECONDS))
            return 0
        }
        override fun stop() {
            stopped = true
            stoppedSignal.countDown()
        }
        override fun cancelPrefill() = Unit
        override fun saveSession(path: String) = true
        override fun loadSession(path: String) = true
        override fun forget() = Unit
        override fun unload() = Unit
    }
}
