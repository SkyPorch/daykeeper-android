# Contract source

The customer API snapshot is copied verbatim from
[SkyPorch/daykeeper-openapi](https://github.com/SkyPorch/daykeeper-openapi)
commit `a4f123969e3e0e005c0a4858fee7bff17cd5a180`, path `openapi/customer.yaml`
(unmerged candidate at the time of this snapshot).

SHA-256: `322158cd5fa5c54a054d701ff64a9c8b07cad477414d7df83ba5a3aa7ee06cc3`.
Git blob: `bf566c97a541ac5e4e1f04670fb3b65475b635a6`.
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
page-size parameter. The client can therefore page forward from a message it
already holds, but it cannot ask the gateway for an older window, and it has no
way to bound the size of the default window. A conversation whose default
response exceeds the transport's 1 MiB ceiling stays unreadable until the
gateway gains a page-size or backward-cursor parameter; no client-side change
can fix it. Do not add a "load earlier" affordance that simply re-requests the
same default window — it repeats the request that already failed.
