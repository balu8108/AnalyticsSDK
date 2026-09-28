# Analytics SDK

A small Android analytics SDK. Events are batched and sent every 5 seconds, with retries and app lifecycle handling.

- `:analytics` is the SDK (min SDK 24).
- `:app` is a demo that runs a small HTTP server on the device, so you can watch batching, retries and failures without a backend.

## Integration

**1. Add the module**

```kotlin
// settings.gradle.kts
include(":analytics")

// app/build.gradle.kts
dependencies {
    implementation(project(":analytics"))
}
```

**2. Initialize once in `Application.onCreate`**

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AnalyticsSDK.init(this, "https://api.example.com/v1/events")
    }
}
```

Optionally pass an `AnalyticsConfig`:

| Field | Default | Meaning |
|---|---|---|
| `flushIntervalMs` | 5 000 | How often to send while the app is in the foreground |
| `maxBatchSize` | 100 | Events per request. Reaching it also triggers a send straight away |
| `maxQueueSize` | 10 000 | Events held in memory. The oldest are dropped beyond this |
| `maxAttempts` | 5 | Attempts per batch per flush |
| `baseBackoffMs` / `maxBackoffMs` | 1 000 / 60 000 | Bounds for the delay between retries |

**3. Track events from any thread**

```kotlin
AnalyticsSDK.sendAnalyticsEvent("checkout_started", mapOf("items" to 3, "total" to 49.99))
AnalyticsSDK.flush() // optional: send now instead of waiting for the next tick
```

`sendAnalyticsEvent` never blocks, so it's safe on the main thread. Events tracked before `init()` are kept and sent once it runs. Property values should be strings, numbers, booleans or null.

Nothing else is needed. The `INTERNET` permission and R8 keep rules come with the module.

## Server contract

The SDK sends `POST <endpoint>` with `Content-Type: application/json` and `Content-Encoding: gzip`:

```json
{
  "sentAt": 1759070000000,
  "events": [
    { "id": "9f1c…", "name": "checkout_started", "ts": 1759069998000, "props": { "items": 3 } }
  ]
}
```

| Response | What the SDK does |
|---|---|
| 2xx | Done |
| 408, 429, 5xx, or a network error | Retries, honouring `Retry-After` (in seconds) |
| Any other 4xx | Drops the batch. Retrying can't help, and keeping it would block everything behind it |

Delivery is **at-least-once**. If a response is lost (for example, a timeout after the server accepted the batch), the batch is sent again. **The server should ignore events whose `id` it has already seen.**

## How it works

```
any thread ──offer──▶ BoundedEventQueue ──poll(100)──▶ EventPipeline ──▶ HttpTransport (Retrofit + OkHttp)
               lock-free, capped            one flush at a time         gzip, pooled connections
```

- **No locks for callers.** `sendAnalyticsEvent` only adds to a `ConcurrentLinkedQueue`, so callers never wait on a lock. One consumer coroutine does all the sending. `ConcurrentLinkedQueue.size()` walks the whole list, so the queue keeps its own count for the cap.
- **One loop for every flush trigger.** The loop waits for the 5-second timeout or a flush request, whichever comes first. The request channel is `CONFLATED`, so any number of requests during a flush become a single follow-up flush. Requests come from a full batch, `flush()`, lifecycle changes, and events tracked in the background.
- **Retries.** Exponential backoff with full jitter: a random delay between 0 and the current cap, so devices that failed during the same outage don't all retry at once. A batch that still fails after `maxAttempts` is kept aside and sent before anything newer, so order holds.
- **The flush lock.** The loop and `FlushWorker` can both flush, from different threads. A coroutine `Mutex` stops them from sending the same batch twice. Only the sending side uses it, never callers.
- **Lifecycle.** The SDK uses `ProcessLifecycleOwner`, which works at app level rather than per Activity, so rotating the screen or switching Activities doesn't count as going to the background.

  | State | Behaviour |
  |---|---|
  | Foreground (ON_START) | Timer runs every `flushIntervalMs` |
  | Going to the background (ON_STOP) | Flushes immediately and schedules `FlushWorker` (unique, waits for a network connection) |
  | Background | No timer. Each event is sent straight away. A failed flush schedules `FlushWorker` again |
  | Started without UI (WorkManager, push, broadcast) | Treated as background until ON_START |

## Limitations

- **In memory only.** Pending events are lost if the process dies, and Android often kills backgrounded apps. `FlushWorker` only helps while the original process is still alive, for example when it's waiting for the network to come back.

## Running

```bash
./gradlew :analytics:testDebugUnitTest   # unit tests
./gradlew :app:installDebug              # demo app
```

In the demo, switch the server between healthy, flaky (503), dropping connections and rejecting (400), then watch the log. Press Home to see the background flush. SDK logs are in Logcat under the `AnalyticsSDK` tag.
