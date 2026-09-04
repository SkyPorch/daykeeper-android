# Releasing the Daykeeper Android SDK

This is an unreleased candidate. No Maven Central coordinate, immutable tag, or
GitHub Release exists yet. Successful local or hosted verification does not
authorize publication.

## Intended coordinates

- `io.github.skyporch:daykeeper-android`
- `io.github.skyporch:daykeeper-android-ui`

The default project version is `0.1.0-SNAPSHOT`. Release-candidate jobs pass one
exact non-snapshot SemVer through `-PdaykeeperVersion`; both modules and the UI's
core dependency receive that same value. A release PR must update the CI candidate
version, changelog and compatibility evidence together.

## Registry bootstrap

1. Approve repository visibility and verify the `io.github.skyporch` namespace
   in the Central Publisher Portal.
2. Create a release-only GPG key, publish its public key, and store its private
   material and passphrase only in the protected release environment.
3. Configure the protected `daykeeper-maven-production` environment with a
   non-author reviewer, Central user token, and signing material. Never expose
   them to pull-request jobs or forks.
4. Review the pinned `com.vanniktech.maven.publish` `0.37.0` dependency when
   updating it. Sonatype does not provide an official Gradle Portal plugin. The
   default build does not add a Central target; it does so only when an operator
   passes `-PdaykeeperCentralRelease=true`.
5. Keep Central user tokens and in-memory signing material out of this repository
   and ordinary CI. There is deliberately no credential-bearing release workflow.
6. Configure a user-managed Central deployment only after explicit release
   approval. Upload for validation with `publishToMavenCentral`; never invoke
   `publishAndReleaseToMavenCentral`. An authorized maintainer must inspect the
   validated deployment and approve the irreversible Portal publish separately.

## Release gate

1. Run the full hosted Android job from the exact release commit, including unit
   tests, lint, R8 example, instrumentation compilation, exact-version local Maven
   publication, candidate metadata/archive checks and the checksum-bound consumer.
2. Run instrumentation and messenger scenarios on authorized physical or managed
   Android devices. The current compile-only result is not runtime evidence.
3. Run `gitleaks git . --no-banner --redact` and review source, POM metadata,
   dependency licenses, AAR contents, sources, notices, privacy claims, and
   generated checksums.
4. Before credentials are supplied, build the exact candidate locally and run
   `bash Scripts/check-maven-candidate.sh MAJOR.MINOR.PATCH`. Confirm both POMs,
   AARs, source/Javadoc jars, Gradle metadata, embedded licenses and SHA-256
   sidecars, then verify the independent consumer against both fresh AAR hashes.
5. Verify the signed `daykeeper-android` and `daykeeper-android-ui` artifacts in a
   clean external Gradle consumer using only the intended Central staging source.
6. Tag the reviewed commit `vMAJOR.MINOR.PATCH` and create matching release notes
   only after the user-managed deployment validates. Publish in the Central
   Portal only after the tag, commit, bundle digests, signatures, POMs and notes
   match the reviewed evidence.

Maven Central releases are immutable. No process may publish from a pull request,
automatically promote a merely validated deployment, or accept long-lived
credentials outside the approved operator environment. Adding a persistent
credential-bearing release workflow requires separate security review and
explicit approval.
