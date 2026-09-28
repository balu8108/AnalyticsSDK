package com.demo.analytics

import java.util.UUID

data class AnalyticsEvent(
    val name: String,
    val properties: Map<String, Any?> = emptyMap(),
    // Created on the device so the backend can drop duplicates. A batch can be sent twice if the
    // server accepts it but the response never reaches us (e.g. a timeout), so we retry.
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
)
