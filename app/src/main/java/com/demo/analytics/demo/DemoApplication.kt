package com.demo.analytics.demo

import android.app.Application
import com.demo.analytics.AnalyticsConfig
import com.demo.analytics.AnalyticsSDK

class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DemoServer.start()
        AnalyticsSDK.init(this, DemoServer.URL, AnalyticsConfig(maxAttempts = 4))
        DemoTracker.track("app_open")
    }
}
