# Contract source

The customer API snapshot is copied verbatim from
[SkyPorch/daykeeper-openapi](https://github.com/SkyPorch/daykeeper-openapi)
commit `4a2b82c9b23503073dc26fdeb5163e8869d007b8`, path `openapi/customer.yaml`
(unreleased; head of branch `codex/daykeeper-agent-credentials`, pull request
SkyPorch/daykeeper-openapi#13).

SHA-256: `ae75711072950c786d69401301292659ece7f37461cf0621ae4f8a58836b82bd`.
Git blob: `9cdf5423e73ad8008fc62adeb8c66e3c018c357d`.
The source's Apache-2.0 license is preserved in `openapi/LICENSE`.

Change from the previous snapshot: `CustomerError` is now an open envelope
(`additionalProperties: true`). The gateway may add fields to an error body, and
`message`, `retryable` and `nextAction` are optional. The Kotlin decoder ignores
unknown keys, so an added field must never be treated as a decode failure.

The Kotlin models are handwritten. This SDK implements only the eight customer
operations. Lifecycle delivery and contact erasure in the full snapshot are
trusted-server operations and intentionally have no Android methods. A contract
update requires model, behavior and test review; do not silently regenerate a
broader client containing server operations.

The message list exposes only a forward `after` cursor. There is no `before` or
page-size parameter, so the client can page forward from a known message but
cannot ask the gateway for an older window.
