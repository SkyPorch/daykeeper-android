# Releasing the Daykeeper Android SDK

This is an unreleased candidate. No Maven Central coordinate, immutable tag, or
GitHub Release exists yet. Successful local or hosted verification does not
authorize publication.

## Intended coordinates

- `io.github.skyporch:daykeeper-android`
- `io.github.skyporch:daykeeper-android-ui`

The current project version is `0.1.0-SNAPSHOT`. A release PR must set one exact
non-snapshot SemVer version for both modules and update the changelog and
compatibility evidence.

## Registry bootstrap

1. Approve repository visibility and verify the `io.github.skyporch` namespace
   in the Central Publisher Portal.
2. Create a release-only GPG key, publish its public key, and store its private
   material and passphrase only in the protected release environment.
3. Configure the protected `daykeeper-maven-production` environment with a
   non-author reviewer, Central user token, and signing material. Never expose
   them to pull-request jobs or forks.
4. Review and pin the Central Portal publishing integration. Sonatype does not
   provide an official Gradle Portal plugin; the intended candidate is
   `com.vanniktech.maven.publish` `0.37.0`, which must receive a separate source,
   dependency, and behavior review before adoption.
5. Configure a user-managed Central deployment. CI may upload a signed bundle
   for validation, but an authorized maintainer must inspect the validated
   deployment and publish it separately in the Portal.

## Release gate

1. Run the full hosted Android job from the exact release commit, including unit
   tests, lint, R8 example, instrumentation compilation, local Maven publication,
   and the independent checksum-bound consumer.
2. Run instrumentation and messenger scenarios on authorized physical or managed
   Android devices. The current compile-only result is not runtime evidence.
3. Run `gitleaks git . --no-banner --redact` and review source, POM metadata,
   dependency licenses, AAR contents, sources, notices, privacy claims, and
   generated checksums.
4. Verify the signed `daykeeper-android` and `daykeeper-android-ui` artifacts in a
   clean external Gradle consumer using only the intended Central staging source.
5. Tag the reviewed commit `vMAJOR.MINOR.PATCH` and create matching release notes
   only after the user-managed deployment validates. Publish in the Central
   Portal only after the tag, bundle digests, signatures, POMs, and notes match.

Maven Central releases are immutable. A release workflow must never publish from
a pull request, automatically promote a merely validated deployment, or accept
long-lived credentials outside the protected environment.
