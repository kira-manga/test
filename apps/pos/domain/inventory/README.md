# Bounded inventory / moving-average planning child

**LOCAL ONLY.** This module is a pure, bounded planning implementation, not a durable
master, database, payment engine, or completed T13/T18/T21/T22/RV-08/RV-09 release gate.
Source authority: the preserved `../../../ukkera-pos-agent-handoff-2026-10-21`
handoff (BLUE stock/custody/valuation, OPS stock/kitchen, PUR corrections), and
`../../../ukkera-pos-plan-review/FINAL-PLAN.md` §§8/10, findings F08/F09.
The preserved handoff directory is `/root/projects/ukkera/ukkera-pos-agent-handoff-2026-10-21`.

## Implemented boundary

- Exact `core:types.Quantity` in pinned SKU base units, scale 0–6. No conversion,
  floating point, manager negative-stock bypass, or implicit precision rounding.
- Separate scope/SKU/warehouse/ownership pools and lot/custody positions. Owned
  quarantine remains valued but unsellable. Supplier-owned rejected custody has no
  purchased inventory carrying value. `24 = 22 owned released + 2 rejected held`
  is covered. A null lot is one explicit untracked identity; expiry requires a lot.
- Atomic plan batches for receipts, concrete allocations, reservation conversion,
  original returns, and captured-cut shortages. All checks finish before any new
  immutable plan is exposed; input snapshots are never mutated.
- Own-reservation conversion excludes only that reservation from competing claims.
  Recall, recorded expiry, lease expiry under paid/partial/unknown collection, and
  count shortages preserve collection/order/actor/quote links and allocation claims
  in a fulfillment hold. Safety revalidation produces no issue proof when held.
  A paid/unknown release requires a matching resolution **and retained obligation
  reference**; inventory never deletes or moves money.
- Multi-warehouse recall within the complete scoped snapshot, including zero-stock
  positions; later receipt/return of that known recalled lot remains unavailable.
  Recorded expiry is not cleared by a backward clock. Supplied time must come from
  authoritative master policy, not an arbitrary client.
- Direct terminal recipe ingredient consumption at send. Paid-unsent safety
  revalidation does not issue. Whole-batch before-start reversal requires every
  required station's explicit confirmation and the issue revision. Any acknowledged
  station start blocks restoration; after-start waste classifies existing cost once
  with no second quantity/value issue. Unsafe original ingredients go to quarantine.
- Restricted in-process `ReservationProof` / `IssueProof`, bound to operation,
  scope, order, quote revision, collection, original actor, exact portion allocation,
  required inventory revision, resulting reservation revision, purpose and time.
  An exclusive `mustCommitBeforeEpochMillis` records the earliest lot/lease expiry;
  `requireCurrent` demands an authoritative commit-time check for such plans even
  when the inventory revision has not changed.
  `StockAllocation.lineId` is the concrete priced **portion ID**, not a parent line
  covering multiple portions. Retail integration must demand `ValueIssuePurpose.RETAIL`.

## Moving-average and late-cost policy

`single-pool-reference-half-up-v1` uses exact rational intermediates. Each ordinary
issue takes `HALF_UP(pool minor value × issued base quantity / pool base quantity)`.
The final pool issue takes the entire residual. One issue's value is distributed
among its immutable allocation IDs using largest remainder, ASCII/UUID-key ties.
Physical lot selection is supplied explicitly and is **not** FIFO valuation. FEFO
is not implemented as an automatic picker; selecting different physical lots in
one valuation pool produces the same total moving-average cost.

Original partial returns use the referenced issue slice's captured cost and a
cumulative quantity cursor: `round(value × cumulative / original quantity)` minus
the previous cumulative allocation, with the final return absorbing its residual.
A changed current average never reprices that original posting.

Late costs name one existing local purchase receipt and one stable charge source.
The requested amount is the **cumulative target delta** for that source; amendment
or cancellation changes the target, not quantity, invoices, payables, or original
cost snapshots. Targets cannot move to another receipt/pool, including after
cancellation; `InventoryPlanner` checks retained bindings across its complete scoped
pool cut (the lower-level `MovingAveragePlanner` has only one pool). Corrections that make any
reference receipt/prefix value negative or orphan value at zero quantity reject.

For a fixed immutable cut, reference-only replay applies all current receipt targets
at the named receipts, then follows recorded issue slices and original-return
quantities. It computes held and still-consumed reference destinations. It does
**not** replay orders, money, stock availability, printer jobs, or other business
commands. For each destination:

```
desired linked correction = reference carrying value − net ORIGINAL posted value
new linked effect          = desired correction − already-applied correction
```

The actual return effect always restores the original captured amount. If a late
cost already exists, the **same atomic plan** adds its separately linked net-zero
held/consumed reclassification. New issues capture today's actual pool cost, while
future amendments still preserve that captured amount. A cancelled source may
leave opposite-signed destination corrections summing to zero: that is necessary
when an intervening original issue captured the then-known higher cost.

Selected EGP examples (tests use minor units):

| History | New linked effects / result |
| --- | --- |
| `10@20 + 10@30`, sell5 at original125, late freight120 on first receipt | held **+90**, consumed **+30**, held value465; not physical-residue60/60 |
| Above, then return2 | original return **50**, separately held **+12**, consumed **−12**; held527 |
| Cancel freight after that return | held **−102**, consumed **−18**; held425; original sale/return unchanged |
| First correction, then another sale5 at actual155, cancel freight | held **−60**, consumed **−60**; both original125/155 snapshots unchanged |
| All stock consumed, late cost | held0; correction stays consumed, no zero-quantity residual |
| Sell10 at250, supplier-return remaining10 with claim300 | departure250, claim/book variance50; no historical purchase-cost reversal |

Every transition conserves source target changes across held and consumed deltas.
Ordinary original returns have zero net correction charge, but can reclassify its
ownership. Effect records retain calculation version, source revision, cumulative
target basis and per-destination deltas. Source facts remain immutable.

## Protected inventory storage — schema 1

`InventorySnapshotCodec.encode(snapshot): String` and
`decode(encoded, expected: InventorySnapshotBinding): InventorySnapshot` provide
the current **bounded whole-cut foundation**, not a production-sized full-branch
document or a pagination scheme. No engine/model constructor was made public.
Private storage DTOs contain current positions, owned valuation pools, retained
reservations/issues, recipe station/disposition state, operation/source indexes,
original valuation facts and every linked correction/target basis.

The independent expected binding is mandatory: kind `BRANCH_INVENTORY`, company
and branch `InventoryScope`, row ID, and revision. **Row ID must equal the expected
scope's branch ID** in this one-whole-cut profile. The envelope must match all four
dimensions; deriving the expected binding from the decoded body defeats this
check. The store must also bind its organization/security/recovery context, which
is not an added organization field in `InventoryScope`.

The encoding is canonical JSON with exact owned scalar serializers. Quantities
retain their original unit and scale; all Long money, revisions and epoch-millis
values are canonical decimal strings. Array order is retained, including historical
event/fact/adjustment order; rows and identities cannot be duplicated. Decode does
not sort away malformed history, normalize scalar representations or silently
migrate versions. Unknown schema/calculation version, fields, ownership/status/fact
variants, unsupported transfer/WIP/output kinds, duplicate fields, alternate JSON
representations, invalid Unicode, overflow and inconsistent rows reject. The byte
adapter must decode UTF-8 strictly before supplying a String.

Admission checks include:

- Scoped owned/supplier custody, unique normalized-null-lot position keys, pinned
  SKU units/scales, consistent lot expiry and retained recall/recorded expiry;
  physical owned custody quantity must equal its pool quantity.
- All retained reservation allocation IDs, concrete portion/position cuts, known
  positions and owned released custody; shortage claims may exceed physical stock
  only under explicit holds. Unsafe positions cannot retain an unheld reservation.
  Hold reasons, paid/partial/unknown bindings and original expiry survive unchanged.
  There is **no clock read or renewed lease** during hydration.
- Exactly linked issued reservation/issue identities, original actor/order/quote/
  collection binding, purpose, exact allocation quantities and captured values.
  Recorded restored quantities must equal the original valuation-return facts.
  Recipe required/started station sets, disposition, full original cancellation
  quantities and issue revision must agree; waste cannot regenerate ingredients.
- The stored valuation event order and then-known receipt-charge target history.
  Each original issue must equal the historical actual pool HALF_UP/slice allocation;
  each original return must equal its captured cumulative original cost. Separate
  reference-only arithmetic verifies every linked delta, target basis, source
  revision, destination balance, current quantity and carrying value. It returns
  stored facts unchanged, rather than repricing them using today's targets. No
  business command, collection, payment or external side effect is replayed.
- Source/event index membership and ordering, and global retained acquisition-charge
  source-to-pool/receipt bindings, including cancelled zero targets.

Resource guards run before recursive parsing: **8 MiB** of UTF-8 bytes (with a
matching preliminary character bound), **16,384** entries per array, **24** container
levels and **65,536** containers. Before DTO/JSON encoding or historical verification,
a separate **16,384** total record/ID budget, conservative byte budget and **262,144**
structural valuation-replay-unit budget apply. The latter sums, across pools,
`events × (1 + facts + 2 × issue/restoration slices + targets)`; it bounds replay
input expansion, not CPU time or individual arithmetic, sorting or hash operations.
Destination membership, position claims and restoration joins use indexes rather
than rescanning whole cuts per row. Existing model bounds still apply. Some valid
planning cuts therefore cannot be stored by this codec. Reject
them; never truncate history, delete cancelled targets, drop competing claims or
reset revisions to fit. These are engineering admission limits, not performance
qualification or permission to compact history without a new reviewed profile.

**The database kernel is intentionally smaller:** `StoredBytes` currently allows
only **65,536 bytes per value**, and a whole proposal is limited to **1 MiB**. A
codec-valid document may exceed either enclosing limit. A typed handler must
reject it before collection/commit; this work does not expand the kernel. A
normalized/paged protected storage scheme and complete-index verifier remain a
separate qualification gate. Domain revision zero also remains zero: the current
database aggregate starts at revision one, and a checked adapter mapping is still
required. Decode never invents that offset.

**Consistency is not provenance or history completeness.** A self-consistent
alternative receipt history with the same IDs/revision can pass arithmetic and
binding checks; only protected storage hashes/journals, authorization, source
indexes and recovery admission establish which history is authoritative. This
snapshot does not contain the complete per-lot movement journal, supplier-custody
receipt/count evidence, related sales/entitlement/resolution rows, or signed kitchen
confirmations. Their absence cannot be inferred from a locally consistent body.
The store must supply complete authoritative indexes and validate those cross-row
links under the same transaction/recovery cut. In particular, preserve paid-release
obligations and original return entitlements independently of stock restoration.

No `InventoryPlan`, `ReservationProof`, `IssueProof`, authorization capability,
kitchen confirmation or reusable safety token is serialized or reconstructed.
Hydrated reservations still need a **fresh** planner safety check against current
authoritative time and related sales evidence, then deadline/CAS enforcement in
the real commit transaction. A successful decode is neither a durable commit nor
permission to sell, cancel work, refund, release a hold or readmit a restored writer.

## Historical purchase/retail/expiry postings — schema 1

`InventoryPosting.fromPlan(authoritativeSource, plan, expectedBinding, nowEpochMillis)`
projects an actual live plan into an immutable `InventoryPostingBatch`.
`InventoryPostingCodec.encode/decode` stores historical facts only. The independent
binding contains scope, operation and required/resulting inventory revisions—not
storage generation, authorization, receipt provenance or a source-history digest.

The factory requires the exact source object and checks both complete cuts, then
crosswalks every ordered Movement, Valuation, ReservationRecorded, SafetyChanged
and IssueRecorded effect to the result. Later same-pool valuations use the actual
preceding `valuation.next` object, not a detached equal state or the initial pool.
Repeated intermediate reservation revisions, collateral expiry and zero-stock
positions are retained. Pool originals/indexes and unmodified fields cannot be
silently replaced or dropped. A new pool starts from a fully canonical empty basis.

This deliberately narrower profile permits company-owned released PURCHASE
receipts, retail issues, reservation records and recorded expiry. It rejects all
unsupported source/result history, including untouched or cancelled-zero costs,
returns/restoration, recipes/kitchen/waste, count/recall, other custody and released
reservations. It does not remove those requirements or the broader planner's
existing behavior. Ordinary zero-valued Consumed correction-balance entries after
issues remain valid and must not be pruned.

The codec caps actual UTF-8 bytes at65,536, effects at1,024, total stored records
at4,096, array entries at4,096, containers at8,192 and nesting at24. Conservative
pre-mapping byte/record budgets can reject otherwise small encodings. These are
independent ceilings, not a promise that every maximum-count batch fits. The
future complete companion wrapper must itself fit64KiB, and its entire enclosing
proposal must fit1MiB; this API expands neither financial-kernel limit. Raw byte
callers must decode UTF-8 strictly before supplying a String.

Two limits are explicit, not hidden behind a successful decode:

1. A receipt Movement carries no expiry/safety payload for a newly created lot.
   Two real receipt plans can have identical posted bytes but different new-lot
   expiries in their complete result cuts. A1 binds that metadata to the result;
   A2 must separately bind full cuts plus the full receipt/lot/expiry/upstream
   request and source claims. Existing-position unreported rewrites still reject.
2. Source-free decoding cannot prove all effects are present or anchor the first
   observed reservation revision. It can detect an interior R2→R4 gap, but an
   omitted first record can leave admissible R3→R4 data. Live source correspondence
   and outer provenance are separate requirements, not a second planner in decode.

No plan, proof, authorization capability or deadline is restored. The factory
checks the supplied current time only; callers still propagate the ORIGINAL live
plan's exclusive deadline into the atomic outer commit. Historical decoding never
renews it. No A2 companion reader, fixture finalizer or accepting gateway is
provided by this domain slice. Its exact source review/execution status is in the
lead's [verification report](../../VERIFICATION_REPORT.md); authored tests alone
are not evidence of execution.

## Required durable/security integration — not supplied here

1. Read a complete authoritative affected-resource cut. Do not omit competing
   reservations, lot safety, cost facts, retained source allocations, or unknown
   collection evidence. The current bounded snapshot is not a paginated partial
   query advertised as a complete warehouse.
2. Authenticate device/human separately, authorize scope/actions/approvals and
   kitchen confirmations; validate actual related collection and resolution
   records. Public reference objects are required dependencies, **not approval
   tokens**. An in-process proof is neither serializable wire authority nor evidence
   that any plan committed. It must be consumed with its own plan under the same
   outer business operation. Protect return entitlement in the same transaction.
3. Calculate late-cost replay outside a long database transaction against a fixed
   source cut. Bind a canonical durable source hash to the exact source facts,
   targets, version and revisions (`ValuationPlan.sourceBasis`). The codec above
   supplies a bounded storage representation/consistency check, not the hash,
   protected journal, trusted import, complete-index proof or recovery readmission.
4. Inside the real master transaction, recheck inventory/pool/reservation/issue
   revisions, lot/time policy, write/recovery admission, approvals and unique
   source consumption. Apply **all** quantity/value/linked correction effects and
   related commerce obligations, persist result/audit/journal/outbox, then commit.
   `InventoryPlanner.requireCurrent` describes a CAS precondition; calling it
   outside a transaction does not provide concurrency safety. Two clients can
   calculate last-unit plans from the same cut; only one CAS may durably commit.
5. Real unique constraints must cover scope/pool/position (normalize the null-lot
   identity deliberately), operation IDs, movement/source IDs, one scope-bound
   acquisition-charge source-to-pool/receipt binding, and consumed original
   allocations. The projection is derived from append-only movements, never a
   direct balance reset. Fact/source identity checks here are not SQL constraints.
6. Recover persisted matching operation results **before** replanning retries.
   Duplicate identity is a domain rejection here, not a fake persisted result
   cache. Preserve unknown outcomes, original actor/money, retained source rights,
   and recovery holds across restart/restore. Use the explicit protected storage
   schema above where its enclosing bounds fit; planning objects/proofs themselves
   are not wire schemas or a result store.
7. Fulfillment holds do not complete customer resolution. No hold-clearing,
   substitution, money refund, write-off, lot release, or recount reconciliation
   has been implicitly authorized by a successful planning calculation.

## Explicit exclusions and hard bounds

- Cross-pool/branch transfers, WIP ownership, production output/yield and finished
  goods cost graphs are **unsupported and reject** (`TRANSFER[_IN]`,
  `WORK_IN_PROGRESS`, `PRODUCTION_OUTPUT`). They cannot be smuggled into late-cost
  replay as an accepted supported kind. Direct recipe terminal consumption is not
  WIP/production support. Full F08 transfer/offline destination/WIP/output proof
  therefore remains open.
- No transfer authentication/dedup protocol, supplier invoice/claim/settlement,
  payables, service costing, landed-charge eligibility distribution among receipts,
  statutory GL/tax/fiscal posting, automatic FEFO, serial registration, recipe
  expansion/UOM conversions/substitutions, partial recipe cancellation, finished
  output, whole commerce/refund entitlement, or money decisions.
- Counts are **shortage-only at the exact captured current revision**. Intervening
  movements reject and require external reconciliation/recount. Surplus valuation,
  safety hold clearing and controlled count resolution are not inferred.
- `MovingAveragePlanner`: 1,024 value events, 256 slices/event, 128 retained cost
  sources. `InventoryPlanner`: 2,048 positions, 256 pools, 1,024 reservations/issues,
  256 allocations/batch, 4,096 operations. Exact checked signed-64-bit quantity and
  posted-money boundaries; unsupported precision/overflow reject. These bounds
  are correctness/DoS limits for this child, not production capacity results or
  permission to delete/rebase retained lineage when exhausted.
- No actual Room transaction, crash/restart test, native packaging, physical-device
  qualification, transport, UI, performance proof or production readiness claim.

## Verification history and current handoff

The pre-posting foundation has90 common test methods and3 JVM oracle methods. The JVM oracle uses only
JDK `BigInteger` for independent rounding, weighted allocation, cumulative return
cursors and full held/consumed replay. It checks 24 deterministic 70-transition
histories with receipts/issues/returns/amended/cancelled costs, 180 wide-integer
cases, opposite correction-source ordering, immutable history and per-effect plus
whole-history conservation. Common tests cover the fixtures and failure boundaries
above, hold races, whole recipe confirmation, ownership/scope separation and atomic
planning failures. These are model/CAS-precondition tests, not database race proof.
The 41 new codec methods cover complete-cut restart, exact original/late-cost/return
preservation, held/expired/recipe state, immutable decoded collections, independent
bindings and smaller enclosing kernel limits. Adversarial cases include balanced
but retroactively repriced original issues, wrong held/consumed correction splits,
cancelled-source cross-pool reuse, malformed/noncanonical/oversized input, missing
or duplicate facts/indexes, inconsistent stock/reservation/issue cuts and recipe
station/disposition conflicts. A deliberate alternate-consistent-history case
documents that arithmetic admission cannot authenticate provenance.
Index regressions exercise 1,024 consumed destinations with 122 cumulative source
amendments near the replay-unit limit, multi-portion reservation claim/hold joins,
and exact per-slice restoration with shared multi-pool return events. The large
fixture uses separate small-integer arithmetic rather than planner/replay calls;
it tests the admitted shape and next-event rejection, not wall-clock performance.

Requested lead-owned local commands (helpers must not start concurrent Gradle):

```
./gradlew :domain:inventory:jvmTest :domain:inventory:testAndroidHostTest
```

The historical pre-codec baseline was52 JVM/49 Android-host at13:08Z. Subsequent
lead execution verified the full93 JVM/90 Android-host foundation, including its
41 codec methods, in the independently audited22:55:05Z combined1,632-case run.
That historical source cut does not include the posting files described above.
After separate backend/security source review, fresh lead-owned targeted execution
completed at23:40:25Z: inventory164 JVM/156 Android-host tests, including66 new
common posting methods and five independent JVM counterexamples. The full target
ran959 cases across ten host profiles,40 harness self-tests and six Apple main-klib
compilations. Its source-frozen archive is
`.workspace/evidence/inventory-posting/20261023T233754.415983Z/verification.json`
at the repository root. Apple compilation is not linking or execution. Independent
archive review and subsequent combined-tree qualifications are tracked in the
lead's verification report; this target alone grants no A2, finalizer or application
acceptance. Older XML or source-review opinions are not execution evidence.

Independent source review by the recovery agent identified two real findings:
cross-pool reuse of a charge source, and recording return expiry only on quarantine
while leaving released stock clear. Both were corrected with regressions and
re-reviewed at the source/model boundary. This is not durable/native or full F08
approval. The lead owns final combined-tree command/revision evidence and remaining
gates in the shared verification logs.

Subsequent codec review found a quadratic prior-destination membership scan inside
the admitted replay budget. It was replaced with existing-map membership; position
claim/hold and restoration joins were also indexed without changing their checks.
The replay budget is explicitly structural, not a raw comparison/time bound. These
earlier corrections and their three focused methods are included in the verified
93/90 foundation; no planner/model implementation was changed by them. Later
posting work remains separately scoped in the shared evidence records.
