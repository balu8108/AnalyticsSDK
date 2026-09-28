package com.demo.analytics

import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.demo.analytics.internal.BoundedEventQueue
import com.demo.analytics.internal.EventPipeline
import com.demo.analytics.internal.FlushWorker
import com.demo.analytics.internal.HttpTransport
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.atomic.AtomicBoolean

object AnalyticsSDK {
    private const val TAG = "AnalyticsSDK"

    private val initialized = AtomicBoolean(false)
    @Volatile private var pipeline: EventPipeline? = null

    // Created up front so events sent before init() are kept, not lost.
    private val queue = BoundedEventQueue(AnalyticsConfig().maxQueueSize)

    // Call from Application.onCreate. Later calls do nothing.
    @MainThread
    fun init(context: Context, endpoint: String, config: AnalyticsConfig = AnalyticsConfig()) {
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        queue.capacity = config.maxQueueSize

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName(TAG))
        val pipeline = EventPipeline(
            queue = queue,
            config = config,
            send = HttpTransport(endpoint)::send,
            scope = scope,
            log = { Log.d(TAG, it) },
            onPendingInBackground = { FlushWorker.enqueue(appContext) },
        )
        this.pipeline = pipeline
        pipeline.start()

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                pipeline.foreground = true
            }

            // Flush now while we still can. The worker is scheduled up front too, because Android may
            // freeze the process mid-retry, before the pipeline gets the chance to schedule it.
            override fun onStop(owner: LifecycleOwner) {
                pipeline.foreground = false
                FlushWorker.enqueue(appContext)
            }
        })
    }

    // Safe from any thread. Only adds to a lock-free queue, so it never blocks the caller.
    fun sendAnalyticsEvent(event: AnalyticsEvent) {
        val size = queue.offer(event)
        pipeline?.onEnqueued(size)
    }

    fun sendAnalyticsEvent(name: String, properties: Map<String, Any?> = emptyMap()) =
        sendAnalyticsEvent(AnalyticsEvent(name, properties))

    fun flush() {
        pipeline?.requestFlush()
    }

    internal suspend fun flushNow(): Boolean = pipeline?.flushNow() ?: false
}
