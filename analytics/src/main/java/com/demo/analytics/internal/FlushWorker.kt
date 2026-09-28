package com.demo.analytics.internal

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.demo.analytics.AnalyticsSDK
import java.util.concurrent.TimeUnit

// Sends whatever is still pending after the app goes to the background, once there's a network.
internal class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        if (AnalyticsSDK.flushNow()) Result.success() else Result.retry()

    companion object {
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<FlushWorker>()
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            // KEEP: going to the background again while a job is pending shouldn't queue a second one.
            WorkManager.getInstance(context)
                .enqueueUniqueWork("analytics-flush", ExistingWorkPolicy.KEEP, request)
        }
    }
}
