# Changelog

## Unreleased

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
