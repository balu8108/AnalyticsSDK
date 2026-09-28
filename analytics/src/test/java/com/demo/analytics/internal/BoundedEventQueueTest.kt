package com.demo.analytics.internal

import com.demo.analytics.AnalyticsEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class BoundedEventQueueTest {

    @Test
    fun concurrentProducersLoseNothing() {
        val queue = BoundedEventQueue(capacity = 1_000_000)
        val threads = 8
        val perThread = 10_000
        val start = CountDownLatch(1)

        (0 until threads).map { t ->
            thread {
                start.await()
                repeat(perThread) { queue.offer(AnalyticsEvent("t$t-$it")) }
            }
        }.also { start.countDown() }.forEach { it.join() }

        val drained = queue.poll(Int.MAX_VALUE)
        assertEquals(threads * perThread, drained.size)
        assertEquals(threads * perThread, drained.map { it.id }.toSet().size)
        assertEquals(0, queue.size)
    }

    @Test
    fun overflowDropsOldest() {
        val queue = BoundedEventQueue(capacity = 100)

        repeat(150) { queue.offer(AnalyticsEvent("e$it")) }

        val drained = queue.poll(Int.MAX_VALUE)
        assertEquals(100, drained.size)
        assertEquals("e50", drained.first().name)
        assertEquals("e149", drained.last().name)
    }

    @Test
    fun pollTakesOldestUpToMax() {
        val queue = BoundedEventQueue(capacity = 100)
        repeat(5) { queue.offer(AnalyticsEvent("e$it")) }

        assertEquals(listOf("e0", "e1", "e2"), queue.poll(3).map { it.name })
        assertEquals(2, queue.size)
        assertEquals(listOf("e3", "e4"), queue.poll(3).map { it.name })
    }
}
