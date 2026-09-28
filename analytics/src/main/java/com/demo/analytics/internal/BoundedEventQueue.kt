package com.demo.analytics.internal

import com.demo.analytics.AnalyticsEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

// Many threads offer, one consumer drains. No locks on the caller's path.
internal class BoundedEventQueue(@Volatile var capacity: Int) {
    private val queue = ConcurrentLinkedQueue<AnalyticsEvent>()

    // ConcurrentLinkedQueue.size() walks the whole list, so keep our own count. It can be off by
    // a few under contention, which is fine for a cap.
    private val count = AtomicInteger()

    val size: Int get() = count.get()

    // Returns the size after adding, so the caller can decide whether to flush early.
    // When full, the oldest event is dropped.
    fun offer(event: AnalyticsEvent): Int {
        queue.offer(event)
        val size = count.incrementAndGet()
        if (size > capacity && queue.poll() != null) return count.decrementAndGet()
        return size
    }

    // Takes up to [max] of the oldest events.
    fun poll(max: Int): List<AnalyticsEvent> {
        val batch = ArrayList<AnalyticsEvent>(minOf(max, size.coerceAtLeast(0)))
        while (batch.size < max) {
            batch += queue.poll() ?: break
            count.decrementAndGet()
        }
        return batch
    }
}
