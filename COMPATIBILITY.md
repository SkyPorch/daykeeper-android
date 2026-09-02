# Compatibility and release gates

## Candidate boundary

This repository is a standalone Android SDK, not an Android wrapper over a web
widget. Its public API matches the eight customer methods in the recorded
[contract snapshot](openapi/SOURCE.md). No management, billing, lifecycle campaign
or customer erasure endpoint is exposed. Models are handwritten and validate IDs,
conversation binding, counts and basic timestamp shapes; they are not a general
JSON Schema validator. Unknown response fields are ignored.

Build baseline: Android 36, JDK 17, Gradle 9.4.1, AGP 9.2.1, built-in Kotlin 2.2.10,
coroutines 1.10.2 and serialization 1.9.0. Runtime minimum is declared API 23;
declaring a minimum is not device certification.

OkHttp is pinned to 5.4.0, whose Android artifact supports consumer compile SDK 36.
OkHttp 5.5.0 raises its AAR minimum to API 37, so it is intentionally deferred until
the supported Cordova Android toolchain advances. See the
[official changelog](https://square.github.io/okhttp/changelogs/changelog/). Daykeeper's AARs
require consumer compile SDK 36; this does not raise the declared runtime minimum
or silently change a host app's target SDK. The example targets 36. The build uses
the [supported AGP 9.2 toolchain](https://developer.android.com/build/releases/agp-9-2-0-release-notes)
and [built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin),
with no AAR metadata override or unsupported-compile suppression. Other build
toolchain combinations and all native runtime claims still need verification. API 36 also matches
the supported/default compile SDK in Cordova Android 15.1.0, avoiding a preview-SDK requirement for
hybrid consumers.
Dependency currency is not a completed security audit.

## Verification levels

- Transport tests use real local HTTP sockets through the production OkHttp
  implementation, including redirect destinations, cookies with a positive
  control, single-shot writes, errors, cancellation, gzip limits and deadlines.
  TLS rejection uses an untrusted local certificate. These run on a JVM, not ART.
- Robolectric tests exercise Android Views and session/lifecycle behavior at
  simulated API 35. They are not device screenshots or emulator runtime proof.
- The example is also built with R8 shrinking. A successful build does not prove
  the minified app's runtime behavior.
- Instrumented UI tests are supplied for a real Android runtime. Until an
  authorized device/CI run is recorded, their compilation is the only claim.
- Local Maven verification publishes AARs, sources, POMs and Gradle metadata into
  the repository's build directory at exact version `0.1.0`. The candidate gate
  checks required POM fields, module coordinates, source/Javadoc jars, embedded
  licenses and SHA-256 sidecars. It is not a Maven Central publication.
  The separate consumer refreshes dependency resolution and checks that both
  resolved AAR hashes equal the freshly built artifacts before accepting results.

## Required before production release

- Approve and merge the canonical contract and SDK PRs; verify the SkyPorch Maven
  namespace, signing/provenance process and release ownership. Audit full history,
  public metadata and package contents before making the repository public.
- Run instrumented transport and messenger tests on the minimum supported API
  and a current API, including an R8 release build. Verify certificate validation,
  redirects, cookies and deadline behavior on ART, not just the host JVM.
- Capture and review before/after or new-screen screenshots in light/dark mode,
  narrow and large screens, large fonts, keyboard open/closed and error/recovery
  states. Complete TalkBack, focus, rotation, process-death, multi-window and
  actual background/foreground testing. No predecessor Android UI exists here.
- Prove a real backend first conversation, reply, unread/read, multiple threads,
  logout/customer switch and isolation against the approved deployed gateway,
  with old/new consumer regression evidence. Offline fixtures do not prove live
  provider or production-user parity.
- Finish attachment handling, push registration and notification open/logout
  cleanup, reconnect/realtime strategy, required rich content, localization and
  documented accessibility behavior. Never claim all-platform parity from this
  initial messenger alone.
- Verify a separate consumer installing the approved immutable release from
  Maven Central, including dependency resolution, minification and upgrade.
- Review dependency advisories, host privacy disclosures, retention/erasure and
  production rollout/rollback gates. No repository approval authorizes a live
  traffic cutover or a server migration by itself.

## Design references

[Intercom's Android installation](https://developers.intercom.com/installing-intercom/android/installation)
informs native messenger packaging and installation conventions. Separate a base
SDK from optional channel capabilities, and provide native examples. Daykeeper's
API 23 declaration is not a promise to match every historical device supported by
another SDK. [Android's publishing guide](https://developer.android.com/build/publish-library/upload-library)
informs AAR/POM/module metadata distribution through Maven rather than loose
binary copies. Resend-style agent/account onboarding belongs in the server APIs,
CLI and MCP; it does not justify management keys in customer applications.
