# Contract source

The customer API snapshot is copied verbatim from
[SkyPorch/daykeeper-openapi](https://github.com/SkyPorch/daykeeper-openapi)
commit `f2ae208de7c2c0422482d3f8b16c8c6f7542c347`, path
`openapi/customer.yaml` (unmerged candidate at the time of this snapshot).

SHA-256: `b62dd386a87380f3fe94f968ff8fedf703ca6079199ea74057d32e31f91e1fec`.
The source's Apache-2.0 license is preserved in `openapi/LICENSE`.

The Kotlin models are handwritten. This SDK implements only the eight customer
operations. Lifecycle delivery and contact erasure in the full snapshot are
trusted-server operations and intentionally have no Android methods. A contract
update requires model, behavior and test review; do not silently regenerate a
broader client containing server operations.
