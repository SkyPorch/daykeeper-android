# Daykeeper for Android

Native Android customer support SDKs by SkyPorch: a coroutine customer client and
an optional native messenger. This is an **unreleased candidate**. No Maven
Central release or production compatibility certification is available yet.

## Modules

| Module | Intended Maven coordinate | Purpose |
| --- | --- | --- |
| `daykeeper` | `io.github.skyporch:daykeeper-android` | Customer identity, conversations, messages, unread state and anonymous claims |
| `daykeeper-ui` | `io.github.skyporch:daykeeper-android-ui` | Native conversation list, history, composer and lifecycle handling |

The namespace still needs ownership verification before publication. The build
only publishes snapshots to an isolated local verification repository; it has no
remote publishing credentials or release automation. Install an exact approved
version after release, never `latest.release` or a dynamic range.

Android API 23 is the declared minimum. Build verification uses Android 36,
JDK 17, Gradle 8.13 and Kotlin 2.2.0. See [compatibility](COMPATIBILITY.md) for what
has actually been tested, dependency choices and remaining device gates.

## Customer client

Your backend supplies a short-lived, tenant-bound customer token. Never embed a
management key, provider credential or token-signing secret in an Android app.
The token provider must represent one immutable customer identity for the entire
client lifetime. Refresh credentials for that same identity only.

```kotlin
val client = withContext(Dispatchers.IO) {
    DaykeeperClient(
        baseUrl = "https://support.example.com/customer-gateway",
        tokenProvider = DaykeeperTokenProvider { forceRefresh ->
            // Implement this in your app using its authenticated backend.
            // Capture the stable signed-in customer; never read a mutable
            // global 'current user' that can change underneath this client.
            customerBackend.supportToken(customerId, forceRefresh)
        },
    )
}
val conversations = client.listConversations()
val thread = client.createConversation().conversation
client.sendMessage(thread.id, "I need help with my order.")
```

`customerBackend`, `customerId` and backend authentication are host-app code, not
SDK-provided account signup. The SDK does not create Daykeeper accounts or grant
tenant access. Tenant isolation and quotas must be enforced by the gateway.

Use coroutine cancellation when leaving a screen or signing out. A GET may
refresh its token once after a 401 unless the response explicitly says not to
retry. Writes never refresh-and-replay or retry automatically. A dispatched write
can have `DaykeeperException.outcomeUnknown == true` after a transport failure,
timeout or invalid success response: read the latest server state before deciding
what to do next. This is not an exactly-once delivery guarantee.

## Native messenger

Create a `DaykeeperMessengerSession(client)` on the main thread and retain it in
your host's `ViewModel`. Create a `DaykeeperMessengerView(context)` and call
`bind(session, viewLifecycleOwner)` in a Fragment, or bind to the Activity's
lifecycle. Use only one bound view per session. See the runnable [example](example).

The view pauses and redacts visible content when its lifecycle stops. Drafts
remain in that same session's memory across navigation and rotation. On host-app
logout or customer change, call `session.reset()` before creating and binding a
new client/session. Reset cancels work, drops the old token provider and clears
messages and drafts. The in-messenger sign-out button resets only support, not
your app's authentication. A reset session cannot be signed back in.

Use `session.reset()` from `ViewModel.onCleared()` as well. Configure keyboard
resize/system-bar/IME insets in your host Activity, as the example does. The
messenger uses proportional containers and scrollable content, density-aware
touch targets, and scale-independent text. It inherits the host theme.

New conversations, sends, refreshes and read markers are explicit actions. After
an uncertain send, the messenger preserves but disables the draft. Refresh and
review history, then explicitly discard it. After uncertain creation, review the
list and acknowledge it. Neither recovery action repeats the write. A confirmed
read-marker write refreshes server summaries so new unread arrivals are retained.

## Security and privacy

HTTPS is required except exact loopback hosts for isolated local testing.
The SDK rejects redirects, does not use cookies or persistent HTTP caching,
and never exposes arbitrary server bodies or underlying exceptions as errors.
The total request deadline includes token acquisition and response reading
(default 30 seconds, configurable from 1 to 60). Decoded responses are capped at
1 MiB. Token providers must still cancel their own underlying work cooperatively;
the SDK cannot forcibly terminate a blocking host implementation.

The SDK has no analytics, push registration, background jobs or transcript
persistence. Models contain customer data: do not log them. See [security](SECURITY.md)
and [privacy integration](PRIVACY.md) before shipping. Screenshots, host analytics,
keyboards, OS backups and your backend remain the host app's responsibility.

## What is not included yet

This is not full support-platform feature parity. Attachment models are present,
but the messenger does not upload, download or preview attachments. Push,
realtime updates, automatic reconnect/polling, help-center/search, rich messages,
campaign UI, localization beyond English and an idiomatic Java callback API are
not implemented. See [release gates](COMPATIBILITY.md).

## Develop and verify

```sh
./gradlew :daykeeper:testDebugUnitTest :daykeeper-ui:testDebugUnitTest lint \
  :example:assembleRelease :example:assembleDebugAndroidTest \
  publishAllPublicationsToVerificationRepository
./gradlew -p verification/consumer \
  -PdaykeeperRepository="$PWD/build/repository" testDebugUnitTest assembleRelease
```

Set `ANDROID_HOME` to your installed Android SDK. Import this repository in
Android Studio to run `example`: it is clearly labeled offline synthetic data,
uses no production credentials and sends no network requests. On an explicitly
authorized, isolated test device, run `:example:connectedDebugAndroidTest`.
Never point tests at a user's device or production gateway by default.

The [contract snapshot](openapi/SOURCE.md) is Apache-2.0; SDK source is [MIT](LICENSE).
Dependency licenses remain applicable. See [contributing](CONTRIBUTING.md).
