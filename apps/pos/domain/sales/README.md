# Sales domain and protected snapshots

LOCAL ONLY. See [CONTRACT.md](CONTRACT.md) for the domain and mandatory atomic
store requirements, and the preserved original plan at
`../../../ukkera-pos-agent-handoff-2026-10-21/` and
`../../../ukkera-pos-plan-review/FINAL-PLAN.md`.

## Standalone immutable paid-sale fact

`SalesSnapshotCodec.encodePaidSale(PaidSale)` and
`decodePaidSale(encoded, expectedBinding)` store the financial document separately
from its mutable receipt entitlement. The additive `SalesSnapshotKind.PAID_SALE`
uses schema 1 and the existing domain-owned `StoredPaidSale` representation and
`validateStoredSale` checks. Existing kinds and encodings are unchanged. An
entitlement envelope cannot be decoded as a paid sale, or vice versa.

`SalesSnapshotCodec.PAID_SALE_REVISION` is always domain revision **0**. A paid
sale is a created immutable fact, not a version of its order, collection, price
snapshot or return entitlement. Decode rejects any different expected revision,
including a later revision that agrees with the encoded envelope. Encoding never
increments it.

A future insert-only store using domain+1 must independently require storage row
generation **1**, then supply the fixed domain revision 0 along with the protected
row's actual sale ID, complete branch scope and kind. Do not derive the expected
binding from payload fields or hide a later storage generation by always passing
zero. This codec does not implement that mapping, store CAS, insertion, immutable
history enforcement or source indexes. The generic aggregate table is not by
itself an append-only paid-sale table.

The original pricing owner's canonical `StoredPricingSnapshotCodec` string is
preserved without recalculating current prices. The standalone fact retains:

- Sale/order/stock-issue IDs, scope, original creator, actual collector and original
  collecting cash session.
- Original payment attempts/operations, cash received/change, tender policy
  snapshots and actual cashier attestation evidence. External tender evidence is
  not independent bank verification.
- The complete original pricing-component-by-payment matrix. Component keys,
  row totals and original payment column totals must match; positive collections
  cannot be fabricated from an empty payment list. A genuinely zero-payable sale
  remains valid with no invented payment.

Returned objects own nonmutable list copies. Return acceptance can change its
separate entitlement but cannot revise this original sale fact. No command,
authorization, reusable stock proof, inventory plan or mutable remaining-return
cursor is added to the standalone representation.

## Bounds and limits of this slice

The existing canonical JSON, exact scalar, unknown/duplicate/missing-field,
schema-version, 8 MiB UTF-8, 48-level nesting and 1,024 outer-array guards apply
unchanged, including the tighter 64-payment limit and pricing-owned bounds.
Failures expose sanitized `SalesSnapshotFailure` codes rather than private bytes.
The 8 MiB codec ceiling is **not** the kernel's 64 KiB row limit or a usable
checkout capacity guarantee; future adapters must preflight their own complete
effect and row budgets without dropping original facts.

Structural consistency cannot prove the identity/provenance of an original order,
payment, stock issue or pricing source, or detect a coherently replaced whole
fact. Those require an authoritative complete read cut, protected source records,
uniqueness and immutable history, recovery admission and one durable transaction.
`SalePosted` still needs explicit persistence by a separately reviewed adapter;
encoding the entitlement is not a substitute. This addition is not an integrated
finalizer, live checkout, production authorization, payment action, receipt
printing/fiscal acknowledgement, migration or capacity/provenance solution.

`PaidSaleSnapshotCodecTest` uses actual public domain transitions under explicitly
synthetic test identities; its negative encoder fixture is deliberately invalid.
These are pure codec tests, not SQLite/process-loss or real-money evidence. Actual
commands, results and independent review belong in the lead-owned verification
report; authored tests alone are not a passing result.

Lead verification23 October:48 JVM and48 Android-host tests passed (zero
failures/errors/skips), including9 standalone-fact regressions. Both Apple
arm64/simulator-arm64 klibs compiled, not linked/executed. Inventory's independent
read-only source review found no blocker in this bounded codec. Log:
`.workspace/verify-standalone-paid-sale-20261023.log` at repository root.
