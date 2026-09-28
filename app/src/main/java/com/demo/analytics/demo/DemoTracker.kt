package com.demo.analytics.demo

import com.demo.analytics.AnalyticsSDK
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

// Counts what the demo sends, so "Tracked" can be compared with what the server received.
// Lives outside the Activity so it survives rotation, like the server's count does.
object DemoTracker {
    private val _tracked = MutableStateFlow(0)
    val tracked: StateFlow<Int> = _tracked

    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        AnalyticsSDK.sendAnalyticsEvent(name, properties)
        _tracked.update { it + 1 }
    }
}
