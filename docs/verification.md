# Initial Android candidate verification

Recorded September 1, 2026, before publication. These results apply to the
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

Local environment: macOS arm64, OpenJDK 17.0.14, Android compile SDK 37.0,
Gradle 9.4.1, AGP 9.2.1 and AGP built-in Kotlin 2.2.10. The
Robolectric tests are host-JVM simulations, not a substitute for an emulator or
physical device.

The separate consumer uses Maven coordinates and does not include source files
or project dependencies from the SDK build. The UI publication correctly depends
on `io.github.skyporch:daykeeper-android:0.1.0-SNAPSHOT`. Binary and source JARs
include the MIT notice. Local AAR SHA-256 values:

- Core: `b8f718fcfc02d7978782ef7230cca880e89ed907947b5ac1f6d9800403ec2cd9`
- UI: `cbc4adbafbeaadcd8c649a7ff62da17009774406e50bd2f4c38c47d74778098b`

The consumer refreshes snapshot resolution and rejects a mismatch before it
compiles. A negative control with an intentionally incorrect core hash failed
at `verifyDaykeeperArtifacts` as expected; it is not counted as a passing test.

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
