package com.demo.analytics.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.demo.analytics.AnalyticsSDK
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme { DemoScreen() }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoScreen() {
    val server by DemoServer.state.collectAsState()
    var mode by remember { mutableStateOf(DemoServer.mode) }
    var tracked by remember { mutableIntStateOf(0) }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Analytics SDK", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Sends every 5s, or sooner at 100 queued events. Going to the background flushes " +
                    "right away and schedules WorkManager. SDK logs are in Logcat under AnalyticsSDK.",
                style = MaterialTheme.typography.bodySmall,
            )

            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Stat("Tracked", tracked)
                    Stat("Received by server", server.received)
                }
            }

            Text("Server", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerMode.entries.forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        onClick = {
                            mode = option
                            DemoServer.mode = option
                            DemoServer.log("server: ${option.label}")
                        },
                        label = { Text(option.label) },
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    AnalyticsSDK.sendAnalyticsEvent("button_tap", mapOf("screen" to "demo"))
                    tracked++
                }) { Text("Track event") }

                Button(onClick = {
                    // Many threads writing at once is the case the lock-free queue is for.
                    repeat(8) { t ->
                        thread(name = "producer-$t") {
                            repeat(125) { i ->
                                AnalyticsSDK.sendAnalyticsEvent("burst", mapOf("thread" to t, "i" to i))
                            }
                        }
                    }
                    tracked += 1_000
                    DemoServer.log("burst of 1000 events from 8 threads")
                }) { Text("Burst 1000") }

                OutlinedButton(onClick = {
                    AnalyticsSDK.flush()
                    DemoServer.log("flush requested")
                }) { Text("Flush now") }
            }

            HorizontalDivider()
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(server.log) { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
