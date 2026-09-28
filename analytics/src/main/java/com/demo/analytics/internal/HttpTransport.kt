package com.demo.analytics.internal

import com.demo.analytics.AnalyticsEvent
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

internal sealed interface SendResult {
    data object Success : SendResult
    data class Retryable(val retryAfterMs: Long? = null) : SendResult
    // The server will never accept this batch (e.g. a 400), so it gets dropped.
    data object Fatal : SendResult
}

// The wire format. Kept apart from AnalyticsEvent so renaming a public field can't silently change
// what the server receives.
internal class BatchPayload(
    @SerializedName("sentAt") val sentAt: Long,
    @SerializedName("events") val events: List<EventPayload>,
)

internal class EventPayload(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("ts") val timestampMs: Long,
    @SerializedName("props") val properties: Map<String, Any?>,
)

internal interface EventsApi {
    @POST
    @Headers("Content-Encoding: gzip")
    suspend fun send(@Url url: String, @Body body: RequestBody): Response<Unit>
}

// POSTs a gzipped {"sentAt": ..., "events": [...]}. OkHttp keeps connections alive between flushes,
// so we don't pay for a new TCP + TLS handshake every 5s. IOExceptions are left to the pipeline to retry.
internal class HttpTransport(private val endpoint: String) {

    private val api: EventsApi = Retrofit.Builder()
        // Retrofit needs a base URL. The real one is passed per call with @Url.
        .baseUrl(endpoint.toHttpUrl().resolve("/")!!)
        .client(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build(),
        )
        .build()
        .create(EventsApi::class.java)

    suspend fun send(batch: List<AnalyticsEvent>): SendResult {
        val payload = BatchPayload(
            sentAt = System.currentTimeMillis(),
            events = batch.map { EventPayload(it.id, it.name, it.timestampMs, it.properties) },
        )
        val json = gson.toJson(payload)

        val response = api.send(endpoint, gzip(json).toRequestBody(JSON))
        val code = response.code()
        return when {
            response.isSuccessful -> SendResult.Success
            // Timeout, rate limit or server error: worth trying again. Retry-After is in seconds.
            code == 408 || code == 429 || code >= 500 ->
                SendResult.Retryable(response.headers()["Retry-After"]?.toLongOrNull()?.times(1_000))
            else -> SendResult.Fatal
        }
    }

    // Compressed up front rather than streamed, so the request has a Content-Length instead of
    // being chunked. Batches of repetitive JSON typically shrink to around a fifth of their size.
    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(text.toByteArray()) }
        return out.toByteArray()
    }

    private companion object {
        val gson = Gson()
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
