# Supplier reference V2 — original-result clarification

**Clarification 1.0.1 · 6 November 2026 · FROZEN before execution · NOT RUN.**
Identity: `SUPPLIER-REFERENCE-V2-CLARIFICATION-1.0.1`.

## Selected specification

Select `SUPPLIER-REFERENCE-V2-CASES-1.0.0` ([cases.md](cases.md),
[fixtures.json](fixtures.json)) **plus this clarification 1.0.1** for execution.
The base files remain preserved. All original case IDs, vectors, literal values,
limits, actions and other expectations remain unchanged; this is not an additional
case or a different result schema.

## SREF2-002 — original format versus original source facts

The precise original sentence is:

> Earlier original results retain the reference/names/active from their own cut.

Replace that sentence, for the selected specification, with:

> Earlier original results retain their exact original format and, for V2, their
> own reference snapshot. Preserve own-cut names/active in the genuine original
> supplier/source records used for reconstruction; this does not require adding
> names/active fields to a result body. Any fields already required by the
> qualified original result format remain required and unchanged.

Apply the same clarification to fixture
`cases[id=SREF2-002].expected.previous_results`, whose original value is
`Exact own-cut reference/names/active retained`: it means exact original format
and own-cut V2 reference, with own-cut names/active retained in genuine supplier/
source evidence, not a requirement to add result-body fields.

**Reason:** [V2 “Write and compatibility behavior”](../../contracts/LOCAL-SUPPLIER-REFERENCE-V2.md)
explicitly preserves the original reference snapshot, unchanged V1 formats and
cold reconstruction from original inputs/genuine prior records.
[V1 “Data and persistence”/“Compatibility and verification”](../../contracts/local-supplier-rpc-workflow-v1.md)
requires immutable original result/actor/operation facts and preserved formats.
Neither assigned contract specifies new names/active result-body fields. This
clarification separates preserved source facts from result-body grammar without
weakening exact original results or adding fields to V1.

No application, native, RPC or device execution has run. Every base case remains
**NOT RUN**, tested revision/evidence remain unassigned, and central implementation/
verification remains **HELD**. Required genuine V1 pre-transition bytes/grammar,
production/native/RPC bindings and separate device/owner campaign remain unchanged.
