package com.demo.analytics

data class AnalyticsConfig(
    val flushIntervalMs: Long = 5_000,
    // Also the early-flush threshold: once this many events are queued we don't wait for the timer.
    val maxBatchSize: Int = 100,
    // Cap on queued events. The oldest are dropped beyond this (e.g. a long offline stretch).
    val maxQueueSize: Int = 10_000,
    // Per flush. After that the batch waits for the next flush or WorkManager.
    val maxAttempts: Int = 5,
    val baseBackoffMs: Long = 1_000,
    val maxBackoffMs: Long = 60_000,
) {
    init {
        require(maxQueueSize >= maxBatchSize) { "maxQueueSize must be >= maxBatchSize" }
    }
}
