package com.demo.analytics.demo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread
import kotlin.random.Random

enum class ServerMode(val label: String) {
    Healthy("200 OK"),
    Flaky("50% 503"),
    Down("Drops connection"),
    Rejecting("400"),
}

data class ServerState(val received: Int = 0, val log: List<String> = emptyList())

// A tiny HTTP server on the device, so the demo goes through the SDK's real network code.
// Handles one request at a time, which is all the SDK ever sends.
object DemoServer {
    const val URL = "http://127.0.0.1:8080/events"

    @Volatile var mode = ServerMode.Healthy

    private val _state = MutableStateFlow(ServerState())
    val state: StateFlow<ServerState> = _state

    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun start() {
        thread(name = "demo-server", isDaemon = true) {
            ServerSocket(8080, 50, InetAddress.getByName("127.0.0.1")).use { server ->
                while (true) {
                    runCatching { server.accept().use(::handle) }
                }
            }
        }
    }

    fun log(message: String) = _state.update { it.withLine(message) }

    private fun handle(socket: Socket) {
        if (mode == ServerMode.Down) {
            log("dropped connection")
            return // closing without a response looks like a network failure to the client
        }

        val input = BufferedInputStream(socket.getInputStream())
        val headers = generateSequence { input.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        fun header(name: String) = headers.firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()
        val raw = ByteArray(header("Content-Length")?.toInt() ?: 0).also { DataInputStream(input).readFully(it) }
        val body = if (header("Content-Encoding") == "gzip") GZIPInputStream(raw.inputStream()).readBytes() else raw
        val events = JSONObject(String(body)).getJSONArray("events").length()

        val status = when (mode) {
            ServerMode.Flaky -> if (Random.nextBoolean()) "200 OK" else "503 Service Unavailable"
            ServerMode.Rejecting -> "400 Bad Request"
            else -> "200 OK"
        }
        socket.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())

        _state.update {
            val received = if (status.startsWith("200")) it.received + events else it.received
            it.copy(received = received).withLine("$events events -> ${status.substringBefore(' ')}")
        }
    }

    private fun InputStream.readLine(): String {
        val line = StringBuilder()
        while (true) {
            val b = read()
            if (b == -1 || b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
        }
        return line.toString()
    }

    private fun ServerState.withLine(message: String) =
        copy(log = (listOf("${time.format(Date())}  $message") + log).take(200))
}
