# Contributing

Use small ready-for-review pull requests, exact dependency versions and Daykeeper
product names. SkyPorch is the publisher. Keep examples synthetic and reusable
across applications. Preserve license notices and the recorded contract source.

Run the commands in the README. Include the test counts and platform versions
that actually ran; distinguish mocks, JVM tests, native instrumentation and live
gateway proof. Include screenshots for rendered changes when an authorized
capture is available, or explain the missing coverage. Do not run device commands
against personal/employer devices without authorization.

Review cancellation, customer switches, draft preservation, response binding,
redirects, cookies, retries, unknown write outcomes, API compatibility and privacy
on every relevant change. Releases, namespace verification, public visibility,
production deployment and billing changes require separate maintainer approval.
The local verification repository is not a remote release. Build an exact Maven
candidate with `-PdaykeeperVersion=MAJOR.MINOR.PATCH`, then run
`bash Scripts/check-maven-candidate.sh MAJOR.MINOR.PATCH`. Never provide registry
or signing credentials to a pull-request job.

Keep the repository publishable: examples, fixtures, and documentation must not
name downstream products, consuming applications, or their hostnames.
