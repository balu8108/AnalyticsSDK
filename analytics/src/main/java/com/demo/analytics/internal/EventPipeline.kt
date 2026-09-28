package com.demo.analytics.internal

import com.demo.analytics.AnalyticsConfig
import com.demo.analytics.AnalyticsEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.random.Random

// The consumer side. Callers only touch the queue. Everything here runs one flush at a time.
internal class EventPipeline(
    private val queue: BoundedEventQueue,
    private val config: AnalyticsConfig,
    private val send: suspend (List<AnalyticsEvent>) -> SendResult,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    // Called when a background flush couldn't send everything, so WorkManager can take over.
    private val onPendingInBackground: () -> Unit = {},
    private val random: Random = Random.Default,
) {
    // Conflated, so any number of flush requests during a flush become one follow-up flush.
    private val flushSignal = Channel<Unit>(Channel.CONFLATED)

    // Both the loop below and FlushWorker call flush(), from different threads. Without this lock
    // they could send the same batch twice.
    private val flushMutex = Mutex()

    // A batch that failed to send. It goes out again before anything newer, so order holds and
    // it isn't lost. Only touched under flushMutex.
    private var retryBatch: List<AnalyticsEvent> = emptyList()

    // For tests.
    internal val pendingCount: Int get() = queue.size + retryBatch.size

    // In the background there's no point waking every 5s: events are rare, so each one is sent
    // straight away instead. Starts false because the process can be started with no UI (by
    // WorkManager, a push, a broadcast). ON_START switches it on when something is actually shown.
    @Volatile var foreground: Boolean = false
        set(value) {
            field = value
            requestFlush()
        }

    fun start(): Job = scope.launch {
        requestFlush() // send anything tracked before init()
        while (isActive) {
            if (foreground) {
                withTimeoutOrNull(config.flushIntervalMs) { flushSignal.receive() }
            } else {
                flushSignal.receive()
            }
            val sentEverything = flush()
            // In the foreground the next tick retries. In the background nothing will, so hand it off.
            if (!sentEverything && !foreground) onPendingInBackground()
        }
    }

    fun requestFlush() {
        flushSignal.trySend(Unit)
    }

    fun onEnqueued(queueSize: Int) {
        if (!foreground || queueSize >= config.maxBatchSize) requestFlush()
    }

    // Returns true if nothing is left to send.
    suspend fun flushNow(): Boolean = flush()

    private suspend fun flush(): Boolean = flushMutex.withLock {
        try {
            var batch = retryBatch.ifEmpty { queue.poll(config.maxBatchSize) }
            while (batch.isNotEmpty()) {
                retryBatch = batch
                if (!deliver(batch)) {
                    log("Giving up for now, ${batch.size} events kept for the next flush")
                    return@withLock false
                }
                retryBatch = emptyList()
                batch = queue.poll(config.maxBatchSize)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Flush failed: $e")
            false
        }
    }

    // Returns true once the batch is done with: sent, or rejected for good.
    private suspend fun deliver(batch: List<AnalyticsEvent>): Boolean {
        for (attempt in 1..config.maxAttempts) {
            val result = try {
                send(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Send failed: $e")
                SendResult.Retryable()
            }
            when (result) {
                SendResult.Success -> return true
                // Retrying won't help, and keeping the batch would block everything behind it.
                SendResult.Fatal -> {
                    log("Server rejected ${batch.size} events, dropping them")
                    return true
                }
                is SendResult.Retryable -> if (attempt < config.maxAttempts) {
                    val delayMs = result.retryAfterMs ?: backoffMs(attempt)
                    log("Attempt $attempt failed, retrying in ${delayMs}ms")
                    delay(delayMs)
                }
            }
        }
        return false
    }

    // Exponential backoff with full jitter: a random wait between 0 and the cap. This stops devices
    // that failed during the same outage from all retrying at the same moment.
    private fun backoffMs(attempt: Int): Long {
        val exponential = config.baseBackoffMs shl (attempt - 1).coerceAtMost(20)
        return random.nextLong(0, min(config.maxBackoffMs, exponential) + 1)
    }
}
