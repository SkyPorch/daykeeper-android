# Changelog

## Unreleased

- Keep the draft and loaded history when a token expires mid-send. One recovery
  read decides what happened: a definite 401 or 403 leaves the draft editable and
  sendable, a rejected recovery read or one naming a different customer signs the
  session out, and any other failure is an ordinary error. The write is never
  replayed, and read-marker recoveries are bounded to one per session generation.
- Add `DaykeeperCustomerClient.getIdentityWithFreshToken()`, an identity read that
  always asks the token provider for a new credential first, so a
  `retryable: false` hint cannot suppress the recovery read. **This adds a member
  to `DaykeeperCustomerClient`; a host that implements the interface itself must
  add the method.**
- Read message history with the forward `after` cursor. Refreshing an open thread,
  and reopening one whose history this session still holds, ask only for messages
  newer than the last one held. There is no backward cursor or page-size parameter
  in the customer contract, so a thread whose first page already exceeds the 1 MiB
  response cap still needs a gateway-side page parameter; no "load earlier" control
  is offered, because re-requesting the same window repeats the failing request.
- Accept any non-empty conversation status and expose it as
  `DaykeeperConversationStatus`, with `Unknown(raw)` for a status added after this
  release; one unfamiliar status no longer rejects the whole list.
- Refuse plain HTTP in release builds, including loopback. `DaykeeperClient` now
  has an internal primary constructor carrying that decision and a public
  `@JvmOverloads` secondary constructor with the previous parameters and default
  timeout, so existing Kotlin and Java call sites are unchanged.
- Render the message and conversation lists with a `RecyclerView` and a
  synchronous `DiffUtil` pass instead of rebuilding every row, and detach the
  adapter on stop so lifecycle redaction takes effect immediately.
- Move every user-facing string into `res/values/strings.xml`.
- Project gateway error codes by shape (`^[a-z][a-z0-9_]{2,63}$`) instead of a
  23-entry allowlist, matching the web and React Native SDKs. Codes the gateway
  adds without an SDK release — `widget_token_required`, `conversation_not_found`,
  `support_gateway_request_failed` and the rest — now reach the caller unchanged;
  free-form prose, non-code-shaped tokens and non-string values still collapse to
  `daykeeper_request_failed`, and the envelope's `message` is never read.
- Expose `DaykeeperException.nextAction` as `DaykeeperNextAction`, decoded from
  the envelope through a closed three-value allowlist (`REVIEW_USAGE`,
  `REVIEW_SETUP`, `REFRESH_CONVERSATION`); anything else is dropped.

- Add exact-version Maven Central candidate metadata, source/Javadoc artifacts,
  embedded-license and checksum validation, with registry upload kept outside CI.

- Upgrade to AGP9.2.1/Gradle9.4.1, compile against Android36 for Cordova Android
  compatibility, and pin OkHttp5.4.0 as the latest release with an API36 AAR;
  retain API23 as the declared, still-to-be-device-certified runtime minimum.
- Require successful fresh reads before uncertain-write recovery can be confirmed,
  and verify exact resolved artifact hashes in the independent Maven consumer.

- Add a coroutine customer SDK with eight customer operations, tenant-token
  integration, bounded transport and conservative write outcome handling.
- Add an optional native messenger with conversation history, text composer,
  server-derived unread state, explicit recovery, logout and lifecycle redaction.
- Add offline examples, JVM wire tests, Android-view lifecycle tests, native UI
  instrumentation sources and local Maven packaging for release verification.

This entry is a candidate description, not a published version announcement.
