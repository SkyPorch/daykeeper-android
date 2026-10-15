# Contract source

The customer API snapshot is copied verbatim from
[SkyPorch/daykeeper-openapi](https://github.com/SkyPorch/daykeeper-openapi)
tag `v1.11.0`, commit `4683bfa2eef41c0948a4ea1507ceb4c8e95f1bff`, path
`openapi/customer.yaml`.

SHA-256: `bc77cb02b877856d2b52ce948fcda4b3b357a7885cd5f5405c3b6394d3b96251`.
Git blob: `dc1ac7a6d3191e28f296996729c9744c6b92efbb`.
- Tag status: v1.11.0 (immutable release tag).
The source's Apache-2.0 license is preserved in `openapi/LICENSE`.

Snapshot `v1.11.0` (customer contract 0.2.0) adds only the optional
`Daykeeper-Client` request header on every customer-session operation, which
this client already sends by default.

Earlier snapshot change: `CustomerError` is now an open envelope
(`additionalProperties: true`). The gateway may add fields to an error body, and
`message`, `retryable` and `nextAction` are optional. The Kotlin decoder ignores
unknown keys, so an added field must never be treated as a decode failure.

The Kotlin models are handwritten. This SDK implements only the eight customer
operations. Lifecycle delivery and contact erasure in the full snapshot are
trusted-server operations and intentionally have no Android methods. A contract
update requires model, behavior and test review; do not silently regenerate a
broader client containing server operations.

The message list exposes only a forward `after` cursor. There is no `before` or
page-size parameter. The client can therefore page forward from a message it
already holds, but it cannot ask the gateway for an older window, and it has no
way to bound the size of the default window. A conversation whose default
response exceeds the transport's 1 MiB ceiling stays unreadable until the
gateway gains a page-size or backward-cursor parameter; no client-side change
can fix it. Do not add a "load earlier" affordance that simply re-requests the
same default window — it repeats the request that already failed.
