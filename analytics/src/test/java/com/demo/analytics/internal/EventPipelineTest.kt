package com.demo.analytics.internal

import com.demo.analytics.AnalyticsConfig
import com.demo.analytics.AnalyticsEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventPipelineTest {

    private val queue = BoundedEventQueue(capacity = 10_000)

    // Keeps the 5s timer out of the way in tests that only care about retries.
    private val noTimer = AnalyticsConfig(flushIntervalMs = 3_600_000)

    // Most tests are about foreground behaviour, so that's the default here.
    private fun TestScope.startPipeline(
        transport: FakeTransport,
        config: AnalyticsConfig = AnalyticsConfig(),
        foreground: Boolean = true,
        onPendingInBackground: () -> Unit = {},
    ): EventPipeline = EventPipeline(
        queue, config, transport::send, backgroundScope, onPendingInBackground = onPendingInBackground,
    ).also {
        it.foreground = foreground
        it.start()
        runCurrent() // let the startup flush run
    }

    private fun EventPipeline.track(name: String) = onEnqueued(queue.offer(AnalyticsEvent(name)))

    @Test
    fun flushesEveryInterval() = runTest {
        val transport = FakeTransport()
        val pipeline = startPipeline(transport)

        repeat(3) { pipeline.track("e$it") }
        advanceTimeBy(4_999)
        assertEquals(0, transport.attempts.size)

        advanceTimeBy(2)
        assertEquals(1, transport.attempts.size)
        assertEquals(listOf("e0", "e1", "e2"), transport.attempts.single().map { it.name })
        assertEquals(0, pipeline.pendingCount)
    }

    @Test
    fun fullBatchFlushesEarly() = runTest {
        val transport = FakeTransport()
        val pipeline = startPipeline(transport, AnalyticsConfig(maxBatchSize = 5))

        repeat(5) { pipeline.track("e$it") }
        runCurrent()

        assertEquals(1, transport.attempts.size)
        assertEquals(5, transport.attempts.single().size)
    }

    @Test
    fun backlogIsSentInBatchesInOrder() = runTest {
        val transport = FakeTransport()
        val pipeline = startPipeline(transport, AnalyticsConfig(maxBatchSize = 10, flushIntervalMs = 3_600_000))

        repeat(25) { queue.offer(AnalyticsEvent("e$it")) }
        pipeline.requestFlush()
        runCurrent()

        assertEquals(listOf(10, 10, 5), transport.attempts.map { it.size })
        assertEquals((0 until 25).map { "e$it" }, transport.attempts.flatten().map { it.name })
    }

    @Test
    fun retriesWithBackoffThenSucceeds() = runTest {
        val transport = FakeTransport(SendResult.Retryable(), SendResult.Retryable(), SendResult.Success)
        val pipeline = startPipeline(transport, noTimer)

        pipeline.track("e")
        pipeline.requestFlush()
        runCurrent()
        assertEquals(1, transport.attempts.size)

        advanceTimeBy(1_000 + 2_000 + 1) // max possible jitter for attempts 1 and 2
        assertEquals(3, transport.attempts.size)
        assertEquals(0, pipeline.pendingCount)
    }

    @Test
    fun honoursRetryAfter() = runTest {
        val transport = FakeTransport(SendResult.Retryable(retryAfterMs = 30_000), SendResult.Success)
        val pipeline = startPipeline(transport, noTimer)

        pipeline.track("e")
        pipeline.requestFlush()
        runCurrent()
        advanceTimeBy(29_999)
        assertEquals(1, transport.attempts.size)

        advanceTimeBy(2)
        assertEquals(2, transport.attempts.size)
    }

    @Test
    fun givesUpButKeepsEventsForNextFlush() = runTest {
        val transport = FakeTransport().apply { fallback = SendResult.Retryable() }
        val pipeline = startPipeline(transport, noTimer.copy(maxAttempts = 3))

        pipeline.track("e")
        pipeline.requestFlush()
        runCurrent()
        advanceTimeBy(60_000)

        assertEquals(3, transport.attempts.size)
        assertEquals(1, pipeline.pendingCount)

        transport.fallback = SendResult.Success
        pipeline.requestFlush()
        runCurrent()
        assertEquals(0, pipeline.pendingCount)
    }

    @Test
    fun exceptionFromTransportIsRetried() = runTest {
        var calls = 0
        val send: suspend (List<AnalyticsEvent>) -> SendResult = {
            if (calls++ == 0) throw java.io.IOException("offline") else SendResult.Success
        }
        val pipeline = EventPipeline(queue, noTimer, send, backgroundScope).also { it.start() }

        pipeline.track("e")
        pipeline.requestFlush()
        runCurrent()
        advanceTimeBy(1_001)

        assertEquals(2, calls)
        assertEquals(0, pipeline.pendingCount)
    }

    @Test
    fun fatalBatchIsDroppedAndDoesNotBlockTheRest() = runTest {
        val transport = FakeTransport(SendResult.Fatal)
        val pipeline = startPipeline(transport, AnalyticsConfig(maxBatchSize = 2, flushIntervalMs = 3_600_000))

        repeat(4) { queue.offer(AnalyticsEvent("e$it")) }
        pipeline.requestFlush()
        runCurrent()

        assertEquals(2, transport.attempts.size)
        assertEquals(2, transport.deliveredIds.size) // only the second batch
        assertEquals(listOf("e2", "e3"), transport.attempts.last().map { it.name })
        assertEquals(0, pipeline.pendingCount)
    }

    @Test
    fun startsInBackgroundWithTheTimerOff() = runTest {
        val transport = FakeTransport(SendResult.Retryable(), SendResult.Success)
        val pipeline = EventPipeline(queue, AnalyticsConfig(maxAttempts = 1), transport::send, backgroundScope)
        pipeline.start()

        pipeline.track("e")
        runCurrent()
        assertEquals(1, transport.attempts.size) // sent right away, and failed

        advanceTimeBy(60_000)
        assertEquals(1, transport.attempts.size) // no 5s timer retrying it

        pipeline.foreground = true // ON_START
        runCurrent()
        assertEquals(2, transport.attempts.size)
        assertEquals(0, pipeline.pendingCount)
    }

    @Test
    fun backgroundEventIsSentRightAway() = runTest {
        val transport = FakeTransport()
        val pipeline = startPipeline(transport, foreground = false)

        pipeline.track("push_received")
        runCurrent()

        assertEquals(listOf("push_received"), transport.attempts.single().map { it.name })
    }

    @Test
    fun failedBackgroundFlushHandsOffToWorkManager() = runTest {
        val transport = FakeTransport().apply { fallback = SendResult.Retryable() }
        var handOffs = 0
        val pipeline = startPipeline(
            transport, noTimer.copy(maxAttempts = 1), foreground = false, onPendingInBackground = { handOffs++ },
        )

        pipeline.track("e")
        runCurrent()

        assertEquals(1, handOffs)
        assertEquals(1, pipeline.pendingCount)
    }

    @Test
    fun failedForegroundFlushDoesNotHandOff() = runTest {
        val transport = FakeTransport().apply { fallback = SendResult.Retryable() }
        var handOffs = 0
        val pipeline = startPipeline(transport, noTimer.copy(maxAttempts = 1), onPendingInBackground = { handOffs++ })

        pipeline.track("e")
        pipeline.requestFlush()
        runCurrent()

        assertEquals(1, transport.attempts.size)
        assertEquals(0, handOffs)
    }

    @Test
    fun concurrentFlushesNeverSendTheSameEventTwice() = runTest {
        val transport = FakeTransport(latencyMs = 100)
        val pipeline = startPipeline(transport, noTimer)

        repeat(3) { pipeline.track("e$it") }
        pipeline.requestFlush() // the loop flushes...
        val results = List(3) { async { pipeline.flushNow() } } // ...while WorkManager does too
        advanceTimeBy(1_000)

        assertTrue(results.all { it.await() })
        assertEquals(3, transport.deliveredIds.size)
        assertEquals(3, transport.deliveredIds.toSet().size)
    }

    @Test
    fun flushNowReportsPendingWork() = runTest {
        val transport = FakeTransport().apply { fallback = SendResult.Retryable() }
        val pipeline = startPipeline(transport, noTimer.copy(maxAttempts = 1))

        pipeline.track("e")
        val job = async { pipeline.flushNow() }
        runCurrent()
        assertFalse(job.await())
    }
}
