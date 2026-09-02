# Initial Android candidate verification

Recorded September 2, 2026, before publication. These results apply to the
candidate source and local artifacts, not to production traffic.

| Check | Result |
| --- | --- |
| JVM transport tests using real loopback HTTP sockets | 16 passed |
| Robolectric messenger/view/lifecycle tests | 18 passed; API 35 scenarios and construction checks at API 23/26/29/30 |
| Independent Maven consumer | 2 tests passed; exact freshly built AAR hashes verified before release-library compilation |
| Example with R8 enabled | Release APK built; runtime not exercised |
| Native instrumentation source | 2 UI tests compiled into a test APK; not executed |
| Android lint | No errors; newer-dependency and offline-example polish warnings remain |
| Kotlin formatter and whitespace checks | Passed |
| Contract, Gradle wrapper and distribution checksums | Matched the recorded/official checksums |
| Source scan | No detected secrets or private consumer/provider product mentions |

Local environment: macOS arm64, OpenJDK 17.0.14, Android compile SDK 36,
Gradle 9.4.1, AGP 9.2.1 and AGP built-in Kotlin 2.2.10. The
Robolectric tests are host-JVM simulations, not a substitute for an emulator or
physical device.

The separate consumer uses Maven coordinates and does not include source files
or project dependencies from the SDK build. The UI publication correctly depends
on `io.github.skyporch:daykeeper-android:0.1.0-SNAPSHOT`. Binary and source JARs
include the MIT notice. Local AAR SHA-256 values:

- Core: `f40683238ff61ebdeacbf0c13995f1d4f9358c1be5c8107341f0ed03feaeffa3`
- UI: `29cfa9ffb25c0fd8fb0934c02b3dc6a9e7de581f384cc65dd1f77939f0fe136b`

The consumer refreshes snapshot resolution and rejects a mismatch before it
compiles. A negative control with an intentionally incorrect core hash failed
at `verifyDaykeeperArtifacts` as expected; it is not counted as a passing test.

Both AARs declare `minCompileSdk=36`. A separate Cordova consumer assembled the
native bridge against Cordova Android 15.1.0 with its Gradle 8.14.2/AGP 8.10.1
toolchain and the exact local artifacts above. This is compile evidence, not a
Cordova device or live-service run.

Manual review added explicit uncertain outcomes for write redirects, including
303 responses that may follow an accepted POST. Lint caught and prompted the
correct API 30 guard for content capture. The cookie-isolation positive control
was corrected to use a real path-matching cookie before the final successful run.
Recovery confirmation is disabled until a fresh history/list read succeeds;
failed reads and lifecycle suspension invalidate prior review readiness.

No authorized Android device run or screenshots were available for this pass.
No deployed gateway, production account, customer data, management credential,
published Maven release or live traffic was used. Hosted CI and the remaining
[release gates](../COMPATIBILITY.md) must be satisfied before shipping. External
source-export AI review CLIs were not run; local manual review and tests are not
an independent security audit.
