package com.demo.analytics.internal

import com.demo.analytics.AnalyticsEvent
import kotlinx.coroutines.delay

// Returns the scripted results in order, then `fallback`. Records every attempt.
internal class FakeTransport(vararg scripted: SendResult, private val latencyMs: Long = 0) {
    private val scripted = ArrayDeque(scripted.toList())
    var fallback: SendResult = SendResult.Success
    val attempts = mutableListOf<List<AnalyticsEvent>>()
    val deliveredIds = mutableListOf<String>()

    suspend fun send(batch: List<AnalyticsEvent>): SendResult {
        if (latencyMs > 0) delay(latencyMs)
        attempts += batch
        val result = scripted.removeFirstOrNull() ?: fallback
        if (result == SendResult.Success) deliveredIds += batch.map { it.id }
        return result
    }
}
