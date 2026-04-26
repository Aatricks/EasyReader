package io.aatricks.easyreader.data.repository.summary

import android.content.Context
import io.aatricks.llmedge.text.TextClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.concurrent.atomic.AtomicInteger

class LlmEdgeSummaryEngineTest {

    private fun createEngine(): LlmEdgeSummaryEngine {
        return LlmEdgeSummaryEngine(mock<Context>())
    }

    @Test
    fun `initialize is single flight across concurrent callers`() = runBlocking {
        val engine = createEngine()
        val client = mock<TextClient>()
        val createCalls = AtomicInteger(0)
        val prepareCalls = AtomicInteger(0)
        val prepareStarted = CompletableDeferred<Unit>()
        val allowPrepare = CompletableDeferred<Unit>()

        engine.createTextClient = { _, _ ->
            createCalls.incrementAndGet()
            client
        }
        engine.prepareTextClient = {
            prepareCalls.incrementAndGet()
            prepareStarted.complete(Unit)
            allowPrepare.await()
        }

        val results = List(10) { async(Dispatchers.Default) { engine.initialize() } }

        yield()

        prepareStarted.await()
        allowPrepare.complete(Unit)

        val initializedResults = withTimeout(5_000) { results.awaitAll() }

        assertTrue(initializedResults.all { it.isSuccess })
        assertEquals(1, createCalls.get())
        assertEquals(1, prepareCalls.get())
        assertTrue(engine.isAvailable())
    }

    @Test
    fun `initialize returns success without reinitializing after success`() = runBlocking {
        val engine = createEngine()
        val client = mock<TextClient>()
        val createCalls = AtomicInteger(0)
        val prepareCalls = AtomicInteger(0)

        engine.createTextClient = { _, _ ->
            createCalls.incrementAndGet()
            client
        }
        engine.prepareTextClient = {
            prepareCalls.incrementAndGet()
        }

        val first = engine.initialize()
        val second = engine.initialize()

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(1, createCalls.get())
        assertEquals(1, prepareCalls.get())
        assertTrue(engine.isAvailable())
    }

    @Test
    fun `failure propagates to all concurrent waiters`() = runBlocking {
        val engine = createEngine()
        val client = mock<TextClient>()
        val createCalls = AtomicInteger(0)
        val prepareCalls = AtomicInteger(0)
        val prepareEntered = CompletableDeferred<Unit>()
        val failNow = CompletableDeferred<Unit>()

        engine.createTextClient = { _, _ ->
            createCalls.incrementAndGet()
            client
        }
        engine.prepareTextClient = {
            prepareCalls.incrementAndGet()
            prepareEntered.complete(Unit)
            failNow.await()
            throw IllegalStateException("boom")
        }

        val results = withTimeout(5_000) {
            val jobs = List(10) { async(Dispatchers.Default) { engine.initialize() } }
            yield()
            prepareEntered.await()
            failNow.complete(Unit)
            jobs.awaitAll()
        }

        val resultSummary = results.joinToString { result ->
            if (result.isSuccess) {
                "success"
            } else {
                "failure:${result.exceptionOrNull()?.javaClass?.simpleName}"
            }
        }

        assertTrue("Expected every waiter to fail, but got [$resultSummary]", results.all { it.isFailure })
        assertEquals(1, createCalls.get())
        assertEquals(1, prepareCalls.get())
        assertFalse(engine.isAvailable())
    }

    @Test
    fun `initialize can retry after failure`() = runBlocking {
        val engine = createEngine()
        val createCalls = AtomicInteger(0)
        val prepareCalls = AtomicInteger(0)
        val firstClient = mock<TextClient>()
        val secondClient = mock<TextClient>()

        engine.createTextClient = { _, _ ->
            when (createCalls.incrementAndGet()) {
                1 -> firstClient
                else -> secondClient
            }
        }
        engine.prepareTextClient = {
            if (prepareCalls.incrementAndGet() == 1) {
                throw IllegalStateException("boom")
            }
        }

        val first = engine.initialize()
        val second = engine.initialize()

        assertTrue(first.isFailure)
        assertTrue(second.isSuccess)
        assertEquals(2, createCalls.get())
        assertEquals(2, prepareCalls.get())
        assertTrue(engine.isAvailable())
    }

    @Test
    fun `owner cancellation does not leave waiters hanging`() = runBlocking {
        val engine = createEngine()
        val client = mock<TextClient>()
        val createCalls = AtomicInteger(0)
        val prepareEntered = CompletableDeferred<Unit>()
        val keepPreparing = CompletableDeferred<Unit>()

        engine.createTextClient = { _, _ ->
            createCalls.incrementAndGet()
            client
        }
        engine.prepareTextClient = {
            prepareEntered.complete(Unit)
            keepPreparing.await()
        }

        val owner = async(Dispatchers.Default) { engine.initialize() }
        val waiter = async(Dispatchers.Default) { engine.initialize() }

        yield()

        prepareEntered.await()
        owner.cancel()
        owner.cancelAndJoin()

        val waiterResult = withTimeout(5_000) { waiter.await() }

        assertTrue(waiterResult.isFailure)
        assertEquals(1, createCalls.get())
        assertFalse(engine.isAvailable())

        engine.prepareTextClient = {
            // succeed on retry
        }

        val retry = engine.initialize()

        assertTrue(retry.isSuccess)
        assertTrue(engine.isAvailable())
    }
}