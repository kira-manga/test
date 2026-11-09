# Local stock services — production source boundary

Status: **implementation source, not verified application capability**. This inventory cut
has not been compiled or executed by its implementer. Independent design, execution,
integration review, recovery qualification and owner hands-on acceptance remain separate
gates. Historical fixture results do not verify these services. No tests, test cases or
expected results were authored as part of this implementation.

Authority: `/root/projects/ukkera/ukkera-pos-agent-handoff-2026-10-21` and
`../ukkera-pos-plan-review/FINAL-PLAN.md`. Work is LOCAL ONLY. Central/backend work remains
held for owner approval. This document does not change the agreed full local scope.

## Composition and public entry points

`LocalStockHandlers(repository, authority)` supplies real `MasterCommandHandler` and
`MasterReadHandler` registrations. The composition owner installs them in the real
`AtomicMasterStore`, with the **same** `LocalStockRepository` instance used by sales.
The adapter does not own a Room connection, bootstrap a branch, invent catalog/policy
authority, provide accepting security/runtime hooks, or publish success before commit.

The app constructs and durably stages immutable requests through `LocalStockCommands`.
An uncertain result is recovered using the **original operation ID and original normalized
request**, never a replacement operation or a guessed business rejection.

Current command registrations:

| Command | Implemented source behavior |
| --- | --- |
| `stock.opening.post/v1`, `/v2` | Explicit approved first balances, quantities and opening value; v2 carries serial inputs. No inferred missing-row genesis. |
| `purchasing.receipt.post/v1`, `/v2` | Identified physical receipt dispositions, exact original UOM conversion, cumulative quantity/value allowance consumption; v2 includes distinct serialized units. |
| `purchasing.inspection.release/v1`, `/v2` | Employee inspection attestation against remaining original quarantine; v2 may atomically qualify a pending serial before release. No second acquisition/value entry. |
| `stock.count.capture/v1`, `/v2` | Identified physical position and current item storage generation captured durably; v2 preserves serial identity. |
| `stock.count.post/v1`, `/v2` | Unchanged captured generation required. Nonserial shortage/equal or separately approved surplus; serial v2 accepts only zero/one and never a gain. |
| `stock.lot.recall/v1`, `/v2` | Complete retained same-SKU lot coverage across local warehouses and active reservation dependencies. `recallSerials` explicitly selects v2; v1 refuses serial cuts. |
| `stock.warehouse.transfer/v1`, `/v2` | Same-branch **supplier-owned nonreleased custody** relocation, preserving a serial in v2. Owned-value transfer is not implemented in this cut. |

`LocalStockRepository` provides:

- `observe(view, skuIds, reservationIds, issueIds)`: one complete bounded live dependency cut.
- `allocate(cut, allocationSetId, allocationSetRevision, requests, now)`: deterministic
  whole-basket FEFO selection from the selected real warehouses. Earlier lines consume the
  same cut's availability. Allocation IDs include the persisted allocation-set identity and
  revision, stable line identity, and normalized position. Repricing must advance that revision.
- `project(cut, context, plan, now)`: exact-source-object inventory contribution; not a permission,
  sales proof, independent transaction, or success reply.
- `compose(...)`: complete proposal manifest, other owner's typed changes/claims/jobs, and the
  earliest mandatory commit deadline. The kernel remains the only committer.
- `resources(cut)`: concrete stock, order-custody and original-actor aliases for the complete cut.
- `countApprovalSnapshot(view, countId)`: claim-checked count plus complete current selected-stock
  closure for the configuration owner's separate approval producer. This is not a public read
  reply, an approval or a reusable stock/permission proof.
- `countSurplusConsumed(view, basis)`: producer-validated document/claim correspondence for the
  supplied immutable approval, without requiring its historical stock generations to remain current.
- `inspectPosting(evidence)`: literal complete ordered effects and whole manifest correspondence.
  It deliberately does not call composite callbacks, authorize an operation, or establish source
  completeness/current-frontier authenticity by itself.

`LocalStockReadCut.snapshot` is the actual planner source object. Its initial inventory revision
is a transaction-local planning counter, **not** the branch revision or a row CAS generation.
`LocalStockReadCut.items` exposes the real independent item storage generations.

## Authority and workflow details

The trusted composition port `LocalStockAuthorityReader` rereads activated items, warehouses,
receiving/opening bases, count-surplus bases, and optional serial registrations through the borrowed transaction. Every
nonempty source-stamp list is reread and checked by key, storage generation and digest under
that same transaction. It is not obtained from a cashier payload or cached sign-in claim.
Null means unavailable/pending; source/parser/storage failures remain uncertainty.

Receiving records the actual product-specific conversion and original line/source/allocation
identities. Changed conversion publications do not rewrite historical receipts. Released and
quarantined owned quantities consume the same stable approval allocation cumulatively across
approval revisions. The allowance records:

`acceptedQuantity + pendingInspectionQuantity = committedQuantity`

Rejected-held goods remain supplier-owned custody without owned book value. Refused-at-door
lines record a receipt line identity but create no physical stock movement. Required inspection
prevents initial release. Released receipts reject expired, recalled or unsafe destinations;
conflicting expiry for the same SKU/lot within a batch also rejects. Approval validity and
released-lot expiry tighten the outer commit deadline.

Inspection consumes the remaining quantity of the **original** receipt line, moves quarantine
to released custody, and moves allowance pending to accepted without increasing total
commitment or value. `evidenceId` is the employee's retained reference: this implementation
does **not** assert that it is a verified durable external certificate/attachment. A required
artifact-verification workflow still needs its real artifact authority and persistence path.

Count capture pins the actual item generation and identified physical quantity. A subsequent
item change requires recount; this is not a direct balance overwrite. Shortage uses the actual
owned pool's issue value and retains all collateral paid/partial/unknown reservation holds.
An equal count still has a durable source/count record and advances the item generation.

### Exact approved count surplus

`LocalStockCountApprovalSnapshot` exposes count identity/generation, captured position/item
generation/quantity/actor/time, current item generation/quantity, and explicit `OPEN`, `POSTED`
or `RECOUNT_REQUIRED` status. Null means claim-checked document absence; loader/producer
inconsistency remains uncertainty. An unchanged item generation must still have the captured
quantity. Its three immutable source stamps identify the count, item and selected position at
this observation; after approval creation these are historical approval inputs, **not** the
approval's later current authority frontier. Its separately copied `recoveryResources` contains
the **entire** `resources(cut)` closure, including collateral holds/order custody/original actors,
plus the count and captured-actor aliases. It is not reduced to those three stamps or a branch
sentinel. The approval producer must retain historical witnesses separately from current
approval/policy sources so a later stock change can yield an authorized stale/posted rejection
rather than a misleading authority-storage failure.

`LocalCountPostRequest` optionally names `surplusBasisId` and
`expectedSurplusBasisRevision`; it does not carry a cashier-selected value.
`LocalCountSurplusBasis` contains the exact branch, basis identity/revision, count identity and
count generation, captured item generation, position, captured quantity, higher counted
quantity, value of the **additional units only**, approving actor, policy, validity and source
stamps. It is a dedicated approval, not a receiving allowance or current-moving-average guess.

The configuration owner must establish the approving actor's current, explicitly count/value-
bound `INVENTORY_ADJUST` authority. The reader's default is unavailable until that real source
is wired. Posting checks every binding, consumes a permanent basis claim once, stores the
original approval/value evidence, appends `COUNT_SURPLUS` value and movement facts, and
preserves existing safety/custody/holds. It creates neither a purchase nor a payable. Ordinary
count request bytes omit the new default-null fields; surplus-bearing normalized value/effect
records use version 2. The original whole-cut/retail-v1 profiles do not silently admit this
extended history.

### Bounded serialized-unit lifecycle

This source cut gives a serial its own `StockPositionKey.serialId` and
`LocalStockPositionRef.serialId`; it is **not** an alias for `lotId`. One serial is exactly one
physical base unit, even when the quantity scale is nonzero. One receipt line names one serial;
the 16-line bound has not been raised. `LocalGoodsReceiptLine` appends `serialId` and the exact
`serialCode`. Codes are nonempty, bounded, already trimmed, and contain no ASCII controls/DEL;
there is no silent trimming, case folding or identity normalization. Duplicate IDs/codes within
a request refuse. A SKU cannot silently migrate between retained serial and nonserial positions.

Actual receipt custody creates `local.stock.serial-origin` and permanent local serial-ID and
exact-code claims. Original receipt/line/source/SKU/position/base quantity/actor/time are retained.
All current custody positions must agree with this origin, and at most one retained position
may hold the unit. Exhausted positions and origin claims are never deleted or reused for a new
receipt. `REFUSED` lines acquire neither the serial nor its code, because no physical custody
was accepted.

Local claims alone **do not prove company-global uniqueness**. Without a qualified registration,
quarantine or supplier-owned rejected-held custody records
`StockSerialRegistrationStatus.LOCAL_PENDING_REGISTRATION`; it cannot be released or available
for reservation. `LocalStockAuthorityReader.serialRegistration(view, serialId)` defaults to null.
A trusted composition implementation must return an exact `LocalStockSerialRegistration`
binding scope, serial ID/code, SKU, registration ID/revision, issuer/provenance IDs, validity and
source stamps, derived from authenticated registration or allocated namespace authority. This
inventory cut implements neither the signed activation producer nor central issuance/delivery.
It accepts no employee registration flag. Qualification validity tightens the final atomic
commit deadline; current read stamps and every registration/issuer/provenance alias enter recovery.

`LocalInspectionReleaseRequest.serialId` must match the original line. If still pending, the
same command requires the trusted qualification, updates only the origin's registration/time,
and emits `InventoryEffect.SerialRegistrationConfirmed` for every retained position before
the two custody movements. Receipt provenance, value, expiry and safety cannot be rewritten.
The existing command is **inspection plus release**: standalone inspection attestation while
remaining pending is not implemented. Already qualified serial quarantine can release under
the original receipt's remaining entitlement without another registration.

`allocate` requires a whole-number base-unit basket quantity for serial SKUs and allocates
distinct qualified available serials. Reservation claims retain each exact serial through
exposure, holds, release and issue. `InventoryPlanner.issueReservation` accepts serial retail
issue, not direct-recipe consumption. `restoreOriginalRetail` preserves the original issued
serial, pool, lot and allocation, restores exactly one unit only against its remaining original
return entitlement, refuses already-held custody and preserves qualification. It restores the
original value lineage rather than creating another receipt. This domain/repository support is
**not** a verified sales-return application or serial selection UI.

`LocalCountPostRequest.serialId` binds the captured serial; equal/shortage counting is supported.
Serial surplus/rediscovery is explicitly unsupported, including a zero position after sale or
loss; a separate reconciliation protocol is needed. `LocalCountSurplusBasis` is nonserial only.
Supplier-owned rejected custody can relocate warehouses without changing identity or value.
Owned serial transfer remains unsupported pending paired value lineage/late-cost propagation.

Serial-bearing commands and normalized position/reservation/issue/effect/document rows use v2.
`InventoryPostedEffectKind.SERIAL_REGISTRATION` and `InventoryPostedRecords.serialRegistrations`
preserve qualification effects. Nullable extensions use `@EncodeDefault(NEVER)` to retain the
original no-serial encoding shape; original whole-cut, fixture-receipt and retail-v1 profiles
refuse serial data instead of dropping it. Byte compatibility has not been executed in this cut.
Serial origins belong to `inventoryKinds` for complete observations, but sales writers must
exclude that kind; serial ID/code claim namespaces are deliberately absent from the generic
inventory-claim set and are exact receipt-only extras. Only serial receipt/opening/inspection
producers may write the origin registry.

## Normalized persistence and complete provenance

Kinds: `local.stock.item`, `position`, `pool`, `value-page`, `reservation`, `issue`, `posting`,
`receipt`, `allowance`, `inspection`, `count`, `transfer`, and `serial-origin` (each with the full prefix).
There is no whole-branch inventory row or branch SKU-count cap.

- An item root indexes **all retained positions**, owned pools and active reservation IDs for
  that SKU. Zero/exhausted positions are retained. Lists must be distinct and canonically ordered.
- Loading expands the active-reservation closure into the other participating SKUs. Requested
  original issues load their original reservation and required current pools. No truncated cut
  is labeled complete. Missing item rows with permanent item claims are inconsistent, not new.
- Pool histories are complete immutable domain histories encoded into fixed 48 KiB pages.
  All but the final page have exactly that size. Headers bind order, bytes, digests, domain
  generation, quantity and value. New page identity includes its creator **pool storage
  generation**, ordinal and digest. Only identical pages at the same ordinal in the current
  predecessor are reused. An identical older noncurrent page cannot cause an insert collision
  or be overwritten. No history pruning, balance-only restart or resetting source IDs occurs.
- Every mutable stock row names the exact predecessor operation, storage generation and digest.
  The retained per-operation manifest includes the complete normalized observed inventory-row
  references, whole other-write hashes/generations/schemas, exact claims/jobs and result hash.
- The loan-bound loader checks each current row against its literal retained producer after row,
  the complete stock-specific producer (or the trusted sales composite callback), original
  observations and direct predecessors. It validates complete item/position/pool/reservation/
  original-issue closure, physical/value totals, original/restored value slices, exact movements,
  immutable value-history prefixes and appended records, reservation bindings/holds, and exact
  document counters/successors. Intermediate reservation and safety effects are retained.

Permanent claims separately protect movement sources, item IDs, reservation IDs, allocation
IDs, document IDs, allowance IDs, every original receipt line including refused lines,
one-time count-surplus bases, and acquired serial IDs/exact codes. Composite sales producers must validate their **whole** request,
result, writes, claims and jobs, not only the inventory portion.

This is **not a recursive whole-history ancestry proof**. Direct original producers receive
complete pure shape validation; the stock loader does not recursively crosswalk every producer
back to genesis. The actual protected runtime must independently establish writer identity,
current frontier, anti-rollback and recovery admission. Hash agreement alone does not establish
any of those properties. Required producer absence remains unavailable/uncertain.

## Authorization, recovery and reads

Commands use exact operation/request/document/revision/reason bindings with live local employee
`INVENTORY_ADJUST` permission. They do not turn a generic adjustment permission into approval
source authority. Typed business rejection happens only after authorization. Current-row,
parser and producer failures are not stored as ordinary missing-stock rejections.

Recovery footprints include actual existing dependencies and newly computed receipt/inspection/
transfer position/pool/source aliases, item/lot/warehouse identities, original actors, approval
sources and custody/allowance/document identities. A branch-ID sentinel is not a wildcard.
The outer kernel must recheck writer/recovery state, authority and deadlines through the actual
durable commit boundary.

Read factories and decoders are public (`LocalStockCommands`, `LocalStockQueries`):

- `items(after, limit)`: current keyset-paginated item roots and storage generations.
- `quantities(skuId, after, limit)`: quantities, reservations, availability, safety and expiry.
- `quantityPosition(skuId, positionId)`: exact current item-index membership and position; same
  `decodeQuantities` result shape with zero/one row and no cursor. No guessed coordinate or
  unbounded page search is necessary for a selected UI row.
- `costs(skuId, after, limit)`: owned quantity/value with separate cost permission.
- `receipt(receiptId)`: quantity-only original receipt/conversion/disposition projection.
- `receiptCosts(receiptId)`: same projection with explicitly authorized original values.
- `count(countId)`: captured generation/quantity, count disposition and surplus-basis reference;
  it does not leak the surplus approval's cost through the quantity-only route.

Both nonserial quantity routes emit **read-body version 2** with `heldQuantity` and
`heldShortageQuantity`; read keys/request bytes are unchanged. Held quantity is the selected
position's quantity in retained `HELD` reservations. Held shortage is exactly
`max(reserved - onHand, 0)`. A positive shortage requires every claimant to be `HELD` with an
exact-position `COUNT_SHORTAGE` or `UNAVAILABLE` hold; an unrelated allocation's hold does not
justify it. Recorded recall/expiry/count-review safety also requires its exact-position hold.
Original producer validation is retained, not replaced by these current aggregate checks.
Genuine protected shortages remain explicit with full reserved totals and zero availability;
claims are never clipped or turned into negative stock. Unexplained overage remains uncertain.
`decodeQuantities` continues to accept original v1 bodies, exposing null for both added fields
to mean **unprojected**, not zero. V2 requires both fields, matching units/nonnegative quantities,
held no greater than reserved and the exact shortage formula. New responses include zero values
explicitly when there is no held quantity/shortage.

Serial-bearing quantity rows use **read-body v3** and append `serialCode` and
`serialRegistrationStatus`; the identity is `row.position.serialId`. A serial item read loads
the complete current custody/origin/reservation closure even for an exact selected position,
not only the displayed page. No-serial/empty pages remain v2. Bodies v1/v2 refuse serial fields;
v3 requires serial identity, code, status and the existing hold detail. Pending is an explicit
status, never an inferred registered state or null permission.

Serial receipt reads use **body v2**; `LocalStockReceivedLine` appends `serialId`, `serialCode`
and `initialSerialRegistrationStatus`. The latter is historical at receipt, not current
qualification after inspection; it is null for refused/nonserial lines. Serial count reads also
use **body v2**, preserving the captured serial in `position`. Original receipt/count body v1
remains no-serial; all read request keys are unchanged. These DTOs remain display projections.

`INVENTORY_QUANTITY_READ`, `INVENTORY_COST_READ` and `PURCHASING_READ` remain distinct.
Missing documents decode nullable. A missing item and an absent selected position are distinct
through the returned current item generation. Retain `ReadReply.Data.branchRevision` and
`observedAt`: pages are current observations, not a single immutable historical report.
No raw aggregate rows, password/security payloads or reusable domain proofs go to the UI.

## Finite profile bounds and unresolved basket capacity

Current source bounds per complete mutation/allocation cut: 16 requested/closure items,
256 positions, 32 owned pools, 64 reservations, 16 request lines; 50 read rows/page; 32 KiB
stock request bodies; 64 KiB complete aggregate rows; at most 128 pool pages and 8 MiB live
pool bytes/cut. Historical pool crosswalk bytes are additionally bounded. A loader limits total
protected reads/witness framing to 64 MiB, 16,384 row/page reads and 4,096 producer witnesses.
These are refusal limits, not measured memory/latency or production-capacity evidence.

The underlying moving-average history remains bounded at 1,024 events/pool and has an additional
conservative replay-work/storage limit. That extra limit can bind earlier. Repeated retention
and required original-producer availability have not been workload-qualified. Separately created
read loaders do not claim a global HTTP/request memory budget. No compaction or complete
retained-history archival qualification exists here.

The **16-line/16-SKU allocation ceiling is still a full-scope supermarket limitation**. Raising
it alone would not implement a practical 128-line basket:

- A reservation touching `K` items writes `K` item roots plus a reservation and posting, before
  sales' own rows. Thus 128 distinct stocked SKUs already exceed the current kernel's 128-write cap.
- An issue touching `K` one-position/one-pool items normally writes at least `K` items, `K`
  positions, `K` pool headers and `K` changed value pages, plus reservation/issue/posting and
  sales records. Even this minimal shape is over 512 writes at 128 distinct SKUs.
- Complete ordered effects, observations and write manifests currently share one 64 KiB posting
  row, with JSON framing overhead. Reservation/issue/order snapshots also have independent row
  limits. Increasing the numeric item count cannot remove any of these constraints.

A next profile needs coordinated, versioned, **bounded paged posting/effect/observation
manifests**, preservation of exact whole-envelope correspondence, and the kernel owner's
explicit review of a higher finite write/claim-count limit **without increasing the complete
1 MiB atomic proposal ceiling**. Larger reservation/issue and sales/quote rows need equivalent
paging where necessary. Any actual encoded proposal still must fit, and cannot be split into
partially committed stock/sales successes. This alone is not proof that all allowed 128-line
shapes fit; payload amplification/history page costs must also be assessed before selecting
the profile. Full-scope capacity remains unresolved, not waived by safe refusal.

## Remaining implementation/qualification gates

- This serial source cut is uncompiled/unexecuted. Signed registration activation/namespace
  delivery, global uniqueness/conflict handling, serial UI entry/selection and the complete
  original-return application service remain integration/implementation gates. Pending custody
  is not permission to sell. Standalone pending inspection and serial rediscovery are missing.
- Owned warehouse transfer needs genuine paired outgoing/incoming value lineage and downstream
  late-cost propagation. It is not implemented as count loss plus purchase. Supplier-custody
  relocation is not evidence of the owned workflow.
- Cross-branch dispatch/in-transit/authenticated receipt/shortage, WIP/prepared-output lineage,
  supplier-return/inspection-rejection/reversal workflows and required external-artifact
  verification remain separate missing local obligations.
- Stock receiving does not implement supplier invoices, payables, settlements, advances or
  company allocation dispatch. No cash/inventory-settlement permission is inferred here.
- Real runtime/bootstrap/authority composition, all required local application UI/service/
  durable communication/recovery paths, full basket capacity and the independent frozen-case
  design/execution/review gates remain outstanding. Platform/hardware/fiscal checks remain
  unverified; nothing here certifies production readiness.
