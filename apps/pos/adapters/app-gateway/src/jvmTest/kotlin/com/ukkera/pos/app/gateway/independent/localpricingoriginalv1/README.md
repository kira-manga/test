# Local pricing original workflow v1 — gateway unit slices

Expectations come from unchanged
`qa/pos/freezes/local-pricing-original-workflow-v1/cases.md` and `fixtures.json`.
The fixture resource is a byte-identical copy. Its retained authoring-status label
is not an execution result; the frozen README records the v1 freeze.

| Frozen cases | Bound unit slice |
| --- | --- |
| LP-01/02, LP-07 authority boundary | Exact unsigned native proposal mapping; generic raw/unsigned receipt input cannot stage or dispatch; absent native publication support remains unavailable. No signatures are fabricated. |
| LP-02/13 | Exact activation/index/set/reset command/action/version/null-approval pairings, including rejection of a mismatched receipt action label; mismatched saved metadata cannot validate or continue. No signed receipt request is constructed. |
| LP-01/09/13, LP-16 bounded-step identity | Production activation/index input mapping captures absent or present storage CAS. Reading newer current state while rejoining the saved original does not rewrite its operation, actor, payload or semantic digest and performs no new write/dispatch. |
| LP-13 | Changed CAS, publication target, local action or current originator cannot replace/adopt the original. |
| LP-13/14 | Stored recorded-refusal representation survives serialization/acknowledgement metadata. Missing authoritative original proof remains unresolved and cannot dispatch new success. |

These are **L1 JVM unit slices**, not L2/L3 production integration. The actual shared
gateway, codecs and journal validation run against scripted current/read/absent
lookup responses. Every custody write, mutation and real authentication call fails
loudly. Constructor-only UUIDs, storage CAS 21/22, quantity/time and tax coordinates
are public-contract bindings, not new authority or arithmetic expectations. Company
price revisions 5/6 use one stable SKU/rule/context identity; they are not storage CAS.

Status: **Six bounded methods passed** in the coordinated pricing run; see
`qa/pos/traceability/pricing-original-workflow-20261101/README.md`. This does not
complete any whole case or establish native custody/production integration.

**UNBOUND:** qualified B-RELEASE source/native head signing and completed receipt;
real durable journal/SQLite, authorized master commit/refusal/replay, LP-09 races,
LP-14 manual-discount limit arithmetic and fresh success, restart/restore, authenticated
RPC/same-device release permissions and all native/device variants. Receipt/activation
effective-price changes and zero downstream business-effect deltas require actual
production reads after execution/reopen, not these controlled port observations.

Selector for the existing POS Gradle build:

```sh
./gradlew :adapters:app-gateway:jvmTest --tests 'com.ukkera.pos.app.gateway.independent.localpricingoriginalv1.*'
```
