# Security

Do not put real tokens, customer transcripts, infrastructure details or private
security reports in public issues. Use the repository's private vulnerability
reporting channel when enabled. If it is unavailable, contact a SkyPorch
maintainer privately first; this candidate does not promise a monitored public
security mailbox or response SLA.

Apps must obtain short-lived customer tokens from an authenticated backend. That
backend binds one stable customer subject to an authorized tenant and scopes the
token narrowly. No Android code can replace server-side authorization, quota
enforcement, erasure or abuse controls. Do not expose management, signing or
provider credentials to a client app, including build configs or sample files.

Create new clients/sessions on account changes. Capture immutable identity in the
token provider; never let an old request retrieve the next customer's token.
Cancel headless calls and reset the messenger on sign-out. Providers must honor
cancellation and must not log credentials. HTTP redirects are rejected rather
than forwarded. Only system TLS trust is used; no trust-all or certificate bypass
hook is exposed. Exact loopback HTTP is for isolated tests, subject to the host's
Android cleartext policy; production configuration must use HTTPS.

No request interceptor or shared OkHttp client can be injected into the public
SDK. It uses no cookie jar, authenticator or persistent cache. Every SDK attempt
has a network guard against automatic follow-up dispatch and writes have one-shot
bodies. The SDK still cannot guarantee exactly-once delivery after an ambiguous
failure. Never automatically resend a write just because an error is retryable
elsewhere in your application.

`DaykeeperException` contains safe metadata only. Model values and messenger
state contain customer data and are not safe diagnostic objects. A host that
replaces `DaykeeperCustomerClient` owns that implementation's trust boundary.
Do not persist tokens, transcripts or drafts without an explicit product/privacy
decision. See [PRIVACY.md](PRIVACY.md) for host integration requirements.

## Coordinated disclosure

Please give maintainers a chance to ship a fix before publishing details,
and tell us when you intend to publish so a fix and an advisory can be
prepared alongside it. Reporters who want credit in the advisory should say
so in the report. No response time is promised while this candidate is
unreleased; see the statement above.
