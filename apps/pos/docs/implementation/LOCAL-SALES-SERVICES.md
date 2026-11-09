# Local sales services — production implementation boundary

## Status and ownership

- Implementer: `/root/sales`.
- Owned paths: `adapters/sales-store/**`, `domain/sales/**`, and this document.
- Return-CASE companion cut: **STAGING ONLY, AUTHORED, UNCOMPILED, UNVERIFIED**.
  Its mirror/patch is in `.workspace/next-cut/local-return-cases-v1`; the live
  source remains HOLD. Only source inspection and artifact readback are included
  in this handoff, not compilation, execution or independent approval.
- Current observation-v2 / serial / immutable price-source cut: **AUTHORED, UNCOMPILED, UNVERIFIED** as of 24 October
  2026. The implementer has not run builds or tests for these new services and has
  not authored their acceptance tests or expected results. Lead-owned combined
  compilation and independent verification remain separate work.
- No production acceptance, platform qualification or complete activity workflow
  is claimed. Existing historical domain/fixture checks do not verify these new
  service implementations.
- **LOCAL ONLY**: no backend phase, Claude calls, real payments, deployments,
  uploads, commits or live migrations were performed by this implementer.
- Original requirements remain in
  `/root/projects/ukkera/ukkera-pos-agent-handoff-2026-10-21` and
  `../ukkera-pos-plan-review/FINAL-PLAN.md`. Both are preserved. Restaurants,
  cafés, clothing and supermarkets are still required; this bounded retail path
  is not completion of the agreed local scope.

## Actual ingress and composition

`LocalSalesService` delegates mutation, read and original-identity lookup through
the real `LocalMasterEndpoint`. It does not accept a raw employee principal,
cache permissions, create operation IDs, retry physical actions or substitute an
in-memory checkout engine.

`LocalSalesCommands.cash/order/collection/returns` produce the typed canonical
mutation requests. Each accepts an optional `actionApprovalId` selector outside
the business payload. The kernel, not the selector, establishes exact-action
approval. The client must durably preserve the original operation ID, normalized
body and identity before dispatch.

Trusted process composition uses:

- `LocalSalesHandlers.stockCompositeVerifier()`;
- one `LocalStockRepository` configured with that verifier;
- `LocalSalesHandlers.commands(stock, quoteSource, tenderSource, returnPolicy,
  recordingCapacity, physicalRecordingCapacity, returnCaseSource)`;
- `LocalSalesHandlers.reads(stock)`.

The quote source and collection handler must use the same normalized stock
repository/producer registration. Registering these handlers does not create a
database, runtime qualification, catalog, users, grants or opening balances. Do
not also register legacy unrestricted overlapping cash writers as an alternate
production path.

All trusted sources use the borrowed branch-bound transaction. They must not
retain it, use a network price/payment service or accept caller-authored quotes,
stock snapshots, policies or capacity proofs. Defaults remain unavailable.

The trailing `returnCaseSource` parameter defaults to
`UnavailableSalesReturnCaseSourceV1`; registration does not manufacture policy,
fulfillment, customer-receipt, sale-promise or physical-recording authority.

## Authored command families

### `sales.cash.control/v1`

`LocalCashContract.kt` and `LocalCashHandler.kt` provide explicit drawer,
custodian/work-index and safe setup; counted opening; non-sale cash moves;
begin/cancel/submit closing; review; and safe send/receive.

- Missing operational roots are not interpreted as empty live custody.
- Setting up a custodian is not creating an employee or granting permission.
- Safe setup creates an explicitly new empty receipt ledger, not an unexplained
  opening-money import.
- Assignment and source claims, actual logical/storage revisions and current
  closing-work obligations participate in the authoritative transaction.
- Submitted closing remains immutable; receiving a closing safe transfer releases
  custody without rewriting the submitted counted report.
- Inventory settlement is unavailable here without its actual linked source.

### `sales.order.write/v1`

`LocalOrderContract.kt`, `LocalOrderStorage.kt` and `LocalOrderHandler.kt` provide
create, replace-unlocked quote, and offered/cancelled/accepted ownership handoff.

The activated source owns catalog validity and pricing. Normal absent
configuration returns typed `SOURCE_REQUIRED`, not a storage failure. Effective
quote refusal uses the public `LocalSalesQuoteUnavailable` with closed reasons:
no effective price, ambiguous price, insufficient stock, capacity, or unsupported
selector. Corrupt sources are not caught as ordinary quote refusals.

The current `RetailStockRequirements` profile is strict:

- every priced portion is fully covered;
- each inventory allocation's `lineId` is the priced `portionId`;
- allocated quantity has the exact sold quantity's unit and scale, and the
  allocations sum to that quantity;
- quote identity is the order ID and quote revision is the actual pinned revision;
- FEFO allocation uses that exact quote identity/revision.

Pack conversion, non-stock service, recipe and kit timing must not be disguised
by silently changing the sold quantity/unit. Activity/channel selectors do not
implement those workflows.

### `sales.collection.write/v1`

`LocalCollectionContract.kt`, `LocalCollectionStorage.kt` and
`LocalCollectionHandler.kt` provide:

- `Begin`: actual full pinned-stock reservation and collection lock;
- `StartAttempt`: **new/unrecorded v1 starts are retired** with typed
  `OBSERVATION_V2_REQUIRED`; already recorded operations replay unchanged;
- `ReserveRecording`: another recording slot for the same unresolved attempt,
  not another instruction to collect;
- `RecordOutcome`: actual unknown/not-paid/cash/external attestation;
- `Finalize`: exact live issue plan plus paid document and initial entitlement.

The same ownership, current custody, revision, source and money eligibility is
checked before a stock `Held` or successful stock branch. A stock-resolution
result does not mean sale success. Finalization uses the actual issued plan,
creates the standalone immutable `PaidSale` and entitlement at logical revision
zero/storage generation one, and does not create another payment or cash move.

Historical v1 financial outcome recording deliberately does not load inventory
or require a STOCK recovery barrier. Unsafe or expired stock does not erase an actual payment
observation. Unknown preserves the same cash object and unresolved attempt;
external payment remains a cashier attestation, never bank authorization.

### `sales.return.write/v1`

`LocalReturnContract.kt`, `LocalReturnStorage.kt` and `LocalReturnHandler.kt`
provide pending intake, trusted original-policy binding, acceptance, refund
intent, same-attempt recording reservation and actual refund observations. New
unrecorded v1 `StartRefund` is retired with `OBSERVATION_V2_REQUIRED`; recorded
results and existing v1 observation bodies/fences keep their original semantics.

- Intake preserves the original asserted facts, actor and operation, even when
  receipt/policy evidence is unavailable. A genuinely unknown asserted receipt
  leaves the intake pending; a partial/corrupt receipt source still refuses.
- Binding may qualify original facts but may not silently amend received/claim
  dates, reason or evidence to obtain eligibility.
- Acceptance atomically consumes the remaining original quantity/value/tender
  entitlement, records the accepted return and adds the accepting operator's
  outstanding refund obligation.
- Disposition is requested classification only. Acceptance/refund does not
  restore stock or recipe ingredients.
- Refunds refer to original tender allocations and the actual paying session.
  Actual cash payout is recorded once. Settling the refund removes the accepting
  operator's work obligation, not an invented reassignment to the paying cashier.
- `RecordRefundOutcome` includes the original `intentOperationId`, binding the
  observation to the reserved original attempt.

## Current read seams and confidentiality

Read codecs use the kernel's fresh current authorization and publish only bounded
DTOs after authorization. Ordinary missing/direct foreign-owner lookups return
authorized `NotAvailable` or an empty order result. Surviving source claims or
inconsistent companion/index rows still fail closed. These unavailable results
are not verified operation-history absence, zero money or unused refund rights.

- `LocalCashReadCodec`: own cash/configuration status, own session, drawer choices,
  own safe balance, non-private `SafeChoices`/`SafeChoice`, and visible transfers.
  Safe choices contain only ID, label, currency and logical revision. Transfer
  DTOs carry current sender-session and receiving-safe revisions without foreign
  cash balances. No session is fabricated for an unconfigured employee.
- `LocalOrderReadCodec`: own orders and an intended recipient's handoff view.
  Unconfigured own-order work returns an authorized empty list under `ORDER_READ`;
  it does not require `CASH_READ` as a prerequisite.
- `LocalCollectionReadCodec`: `OrderStockCut` supplies the actual pinned primary
  SKU generations before `Begin`; `StockCut` supplies current reservation/stock
  preconditions; `Collection` remains independent of live stock reads; `PaidSale`
  is a narrow immutable financial view, not fiscal or fulfillment acceptance.
- `LocalReturnReadCodec`: original receipt rights, original intake/qualified
  claim facts, pending claims, accepted returns and own unsettled returns.
  Original paying actors alone receive their refund recovery operation IDs and
  paying-session continuation revisions. No stock costs or foreign balances are
  exposed.

Page cursors are keyset cursors, not coherent historical report snapshots. The
outer read reply carries the branch revision. Maximum-sized multi-document read
projection behavior still needs independent volume verification; a request limit
does not by itself prove the combined DTO fits its byte bound.

## Reserved recording and recovery boundary

`LocalSalesRecording.kt` uses a constructor-trusted
`TransactionSalesRecordingCapacity`. `UnavailableSalesRecordingCapacity` is the
default; no accepting physical-capacity adapter was fabricated.

A qualified backing must bind the full requirement to a real protected source
stamp and platform profile. The intent atomically writes a sales recording slot,
the kernel's immutable future-operation fence, permanent operation/allocation
claims and the active-attempt pointer. The kernel separately qualifies absence
of the future operation. A null row or free-space sample is not that proof.

The target binding is domain-separated as
`sales.financial-observation-target/v1`, with ordered fields
`kind, documentId, cashSessionId, attemptId, intentOperationId`. Branch, actor and
command are separately bound by the immutable fence. Unknown consumes only its
observation slot; a subsequent evidence recording still concerns the same money
attempt. Permanent ownership/fence claims are not reclaimed as retry capacity.

Logical observation envelope: request 8 KiB, result 16 KiB, row 64 KiB, at most
eight writes/four claims, apply 512 KiB and journal 768 KiB. Money-intent pending
documents are additionally bounded to 32 KiB, with revision headroom checked.
These figures do **not** qualify SQLite/page/journal/audit overhead, reserve a
durable client outcome spool, or prove arbitrary received/change values can fit
existing numeric cash totals. Actual physical money must remain unavailable
until all required capacity/recovery prerequisites are qualified.

Current kernel semantics confirmed by the lead:

- original recorded-result replay is first;
- an unrecorded reserved ID can receive independently qualified
  `AbsentAtCurrentWriter` from lookup;
- a matching reserved-ID submit rejection/CAS rejection returns
  `RESERVED_OPERATION_NOT_RECORDED` without burning that ID;
- absence permits only protocol-safe continuation, never repeating physical
  collection/refund or changing the immutable client observation.

### Authored closed physical-observation v2

`LocalFinancialContractV2.kt`, `LocalPhysicalRecordingV2.kt`,
`LocalFinancialFactsV2.kt`, `LocalFinancialApplicationV2.kt` and
`LocalFinancialHandlersV2.kt` implement a distinct two-stage protocol under
`sales.collection.write/2` and `sales.return.write/2`:

1. `StartAttempt` / `StartRefund` and same-attempt reserve commands require actual
   protected **master and client** preallocation. `clientAllocationId` is only a
   selector, never proof. A qualified constructor-trusted backing binds exact
   actor/scope/intent/recording/attempt/document/session/tender/amount, original
   refund sources, protected receipt stamps, device/spool and platform profiles.
2. `RecordOutcome` / `RecordRefundOutcome` retain the immutable original physical
   fact plus an initial pending-application row in one commit. Their bodies have
   **no mutable expected revision**. Cash-received/change facts can be nonnegative
   yet financially inconsistent; retaining them must not relabel them `NotPaid`.
3. `ApplyObservation(recordingOperationId)` is a separate accounting operation,
   not another physical action. It loads actual current financial sources,
   validates original attempt/intent/tender/value/actor/custody/claim links,
   authorizes current revisions and atomically CAS-writes the accounting effects
   and application status. Payment/refund records keep the original physical
   recording operation; a cash movement names this accounting operation.

Ingress reads only the immutable slot/fence/protection authority and writes only
fact + pending status. It never reads/writes cash/order/refund balances, stock or
releases the active-attempt claim. Authority recovery resources include the
actor, exact slot/fence/allocation/receipt/device/spool IDs and backing-declared
resources. No writer, incomplete-history, whole-branch or AUTHORIZATION hold is
bypassed. Client protected spooling must survive missing authority/slot, holds,
corruption, cancellation and uncertain recording; a normal intent journal is
not such a spool.

Retention is **not payment coverage, successful refund, `NotPaid`, sale completion
or permission to repeat physical collection/payout**. Financial application may
remain pending for missing/invalid financial sources, original-attempt mismatch,
reconciliation, numeric or bounded-storage capacity. Only named sales-source and
pure domain/capacity failures receive those states; database/transaction/IO
failures remain uncertainty. A failed accounting attempt retains the original
fact and active-attempt claim. Applied facts cannot post twice. Applying Unknown
updates the same unresolved attempt and does not enable a replacement attempt.

Current and original captured financial recovery dependencies are both included.
Conservative kind-wide CASH_CUSTODY + ORDER_CUSTODY barriers, plus
REFUND_ENTITLEMENT for refunds, block direct application when retained recovery
holds might already have accounted for the money, even after generic readmission.
Explicit matching against prior reconciliation adjustments is **not implemented**;
this cut refuses to guess that history.

The v2 target frame is `sales.financial-observation-target/v2` with ordered fields
`kind, documentId, cashSessionId, attemptId, intentOperationId, saleId-or-"-",
claimId-or-"-"`; branch/actor/command are separately fenced. V1 observation bytes,
expected revisions, original fences and replay are never upgraded. A v2 attempt
cannot obtain a v1 slot, nor can a v1 attempt obtain a v2 slot. Retiring new v1
positive starts is a deliberate admission change: historical regression flows
that create fresh v1 attempts are no longer compatible, not acceptance evidence
for the replacement protocol.

SV2-01 source correction: same-attempt reservation now positively classifies only
an actual committed, canonical original StartAttempt/StartRefund producer with
exact scope/actor/operation/document/attempt/session/value and original-payment
links. Unknown/missing/mismatched command history is a source failure, never a
legacy default. The original started result and attempt claim must match; v1
also requires the original retained initial slot/fence/claims (including its
otherwise request-absent payment session ID). V2 retains its additional original
immutable-slot validation. Capacity is not consulted until these checks and
protocol matching succeed. Existing recorded replay and v1 encodings are not
rewritten. This amendment is authored only; independent rereview/compile remain.

V2 logical bounds (`SalesPhysicalRecordingBudgetV2`): request 8 KiB, fact 16 KiB,
status 4 KiB, result 16 KiB, ingress exactly two writes / 64 KiB, financial at most
eight writes/four claims / 64 KiB per row / 512 KiB apply, master journal 768 KiB,
client record 32 KiB and client journal 64 KiB. These are logical envelopes, not
qualified protected page/journal/audit overhead or numerical guarantees.
`UnavailableSalesPhysicalRecordingCapacityV2` remains the safe default. No
accepting platform adapter exists; positive UI starts/refunds remain disabled.

Start/reserve results use `LocalSalesResultCodec`; v2 Record/Apply results use
`LocalObservationResultCodecV2`. `LocalFinancialReadCodecV2` supplies own-only
observation, active recording and bounded keyset pending views under current
CASH_READ without loading financial balances. Empty filtered pages can still
have a continuation cursor. Attempt DTOs append `activeRecordingProtocolVersion`
(2 for v2; absent/null preserves legacy bytes). Unavailable reads do not prove
physical-event or historical absence.

### Immutable company/branch price provenance

Root's activated quote source supplies explicit
`LOCAL_RETAIL_COMPANY_BRANCH_V2` and `LocalSalesPriceSourceV2` per priced portion,
including no-override selections. `PricedLineSnapshot.priceRevision` remains the
**company** revision; the companion independently carries company publication,
SKU/rule/revision/digest/unit price, optional policy publication/entry/revision/
digest, optional branch override event/revision and the effective unit price.

`LocalSalesPriceStorageV2.kt` atomically writes one insert-only
`sales.order.price-sources/v2` companion for each created/replaced v2 quote. Its
ID is the original quote operation ID; its body binds scope/order/quote revision
and canonical quote digest. Every portion must match its line, company revision,
effective price and actual allocated SKU. `SalesPriceSourceReference(id,digest)`
is copied unchanged through `OwnedOrder`, `CollectionLock`, `PaidSale` and the
sale embedded in `ReceiptEntitlement`. Historical companions survive quote edits;
a sourced order cannot silently replace its quote with a source-less profile.

Current loaders require the exact companion and original committed quote
producer, original normalized Create/Replace selection, original order/context,
per-portion SKU allocations, output/claim/result shape and bytes. This is
consistency/provenance validation, not historical authorization/frontier proof.
Original receipt/refund loading never consults today's company or branch prices.
Collection Begin/Start/Finalize include literal `price_sources` read witnesses;
the composite verifier consumes them, requires exact references and includes
these writes in the complete posting manifest. Missing companions fail closed.

Historical source-less/null-serial v1 snapshot bytes are preserved by omitted
nullable defaults. Explicit snapshot envelope v2 is required for ORDER/COLLECTION
with serial or price references, and PAID_SALE/RECEIPT_ENTITLEMENT with a price
reference. V1 carrying a new feature and v2 carrying none are rejected rather
than accepted as version aliases. An absent historical companion never means
company/branch-v2 authority.

## Normalized inventory and retained evidence

The production integration uses per-SKU and individual normalized-row CAS
generations. Inventory planner revision zero describes only a transaction-local
planning cut, never a persisted branch inventory revision or seeded history.
`LocalSalesStockRevisionV1` always denotes an actual stored SKU generation.

The exact live domain inventory dependency is checked by object identity and
composed using `LocalStockRepository.project/compose`. The complete posting
manifest covers sales and inventory writes, claims, jobs and result. Original
live stock deadlines propagate through stock composition; historical timestamps
are not renewed into authority.

`LocalCollectionCompositeVerifier` has finite dispatch for v1 Begin/Start/Finalize
history and v2 StartAttempt only. It checks literal original request/result and all sales before/after
values, owner/custody/quote/attempt links, complete outputs/claims/jobs, permitted
ordered stock effects, reservation/issue/valuation correspondence, immutable
future-operation fence and deterministic original component/tender allocations.
It uses nonrecursive `inspectPosting`; the stock loader separately validates
normalized predecessor/dependency closure and collateral preservation.

The `sales.collection.before` rows are original bounded literal read witnesses,
not independent original-producer references for every sales source. Neither
their hashes nor a complete manifest establishes authenticity, historical
authorization, complete-history absence or the current resource frontier.
Those remain qualified-runtime/recovery obligations. Sales source preimages and
inventory's normalized read references must not be described as interchangeable.

Exact serial coordinates are preserved by `StoredRetailAllocation.serialId`;
serial identity is never substituted with `lotId`. The sales handler explicitly
excludes inventory SERIAL_ORIGIN from writable kinds and never owns serial
registration claims. `LocalCollectionStockReadCodecV2` emits full serial-bearing
hold positions; the old v1 stock read refuses those holds instead of dropping
the serial. Bounded normalized stock closure failures are narrowly translated
to authorized capacity refusals without partial stock effects. This is not
verification of full serial return/registration/platform workflows.

## Staged original-receipt return CASE companion v1

### Separate durable legal work, not a refund/disposition shortcut

`sales.return.case.write/v1` is a new command family, with
`LocalReturnCaseCommandCodecV1` and `LocalSalesCommands.returnCase`. It does not
reinterpret `sales.return.write/v1` or observation/financial v2 payloads.

- `Record(caseId, facts)` records the original asserted coordinates (including
  nulls), received-date assertion, claimed-at, claim timezone, condition, reason,
  requested remedy and evidence. Intake does not require cash configuration or
  original receipt/policy availability. Complete asserted receipt coordinates
  are an initial link, **not proof of actual customer delivery**.
- `AppendReceiptEvidence(caseId, expectedCaseRevision, binding)` appends original
  receipt evidence. It never overwrites original assertions, alters any already
  known sale/portion/quantity coordinate, or rewrites a previous evidence event.
- `Qualify(caseId, expectedCaseRevision, expectedEntitlementRevision)` reads the
  original entitlement and the trusted source in the authoritative transaction.
  Missing policy/customer-receipt/capture data leaves a durable pending
  assessment; a missing receipt link needs an append first. The event records
  both requested revision and the **actually observed** entitlement revision/
  immutable witness. An unknown original receipt has no observed revision.
  Only an eligible original-portion REFUND creates the domain claim, at revision
  zero; qualification consumes no quantity, tender value or cash.
- `Accept(caseId, expectedCaseRevision, decisionOperationId,
  expectedEntitlementRevision, expectedClaimRevision, returnId, disposition,
  inspectionEvidence)` consumes the current original quantity/component/tender
  entitlement under exact CAS and the retained qualified decision. The same
  transaction writes accepted return, changed claim/entitlement, before witnesses,
  case event, case work, financial work when money is due, uniqueness claims,
  result and the kernel-owned audit/journal/outbox. The exact committed kernel
  reply, not a proposed DTO or lost response, establishes success.

The separate owner index persists all three phases:
`PENDING`, `BOUND_UNACCEPTED`, `ACCEPTED_DISPOSITION_PENDING`. A zero-value return,
or a later fully paid refund removed from cash work, remains in case work until
a separately implemented disposition/closure workflow. This version neither
evicts nor closes cases. INELIGIBLE describes only the requested ordinary remedy:
the case stays pending with its route/deadline and history, not an erased defect,
warranty, merchant-fault or other applicable claim.

This first slice supports one original retail portion and refund entitlement
acceptance. EXCHANGE/REPAIR/OTHER facts and assessments can remain pending; their
fulfillment, offsets, fees, new sales and settlement are not implemented here.
The requested disposition is **not** inventory restoration, stock movement,
inspection completion, bank execution, cash payout, credit or a customer wallet.

### Protected bounded reads

`sales.return.case.read/v1` / `LocalReturnCaseReadCodecV1.request` supports:

| Query | Permission / ownership | Bound and continuation |
| --- | --- | --- |
| `Receipt(saleId, afterPortionId, limit)` | current `RETURN_RECEIPT_LOOKUP` | up to 16 original receipt lines; `nextAfterPortionId` |
| `OwnWork(after, limit)` | current `RETURN_CLAIM`, exact original case owner | up to 10 cases; `nextAfter` and `indexRevision` |
| `Case(caseId)` | current `RETURN_CLAIM`, exact original case owner | one complete case; no refund balances |
| `Events(caseId, afterOrdinal, limit)` | current `RETURN_CLAIM`, exact original case owner | up to 8 append-only events; `nextAfterOrdinal` and `caseRevision` |

The response ceiling is 32 KiB. Pages emit the largest fitting nonempty prefix;
continuation names the last row actually emitted. If even the first row cannot
fit, `CapacityRequired` is explicit: no skipped item or truncated evidence is
presented as success. The caller must restart a paginated view if its index/
case/entitlement revision changes; pages are not an historical snapshot token.
Permission is checked in the read transaction before encoding the response.
No hidden inventory cost, producer-employee identity or refund balance is
included in case/history/work views. `acceptedReturnId` routes to separately
protected existing financial reads; NotAvailable is not proof of historical
absence or permission to replay an operation.

Request bodies are bounded at 8 KiB, source/case aggregates at 64 KiB, results
at 4 KiB, histories at 64 events, and owner indexes at 256 retained case IDs.
Pending/evidence events reserve the last two slots for qualification binding
and acceptance. No capacity failure deletes a claim or shortens a deadline;
larger/closed histories require a separately reviewed profile.

### Exact sources, provenance and recovery

`TransactionSalesReturnCaseSourceV1.prepare(view, request)` receives trusted
scope, original sale, immutable intake/binding digests, original intake timestamp
and actor. Its prepared value freezes the exact source references and recovery
resources; after current authorization, its pure `assess(recordedAt)` may use
only those observed references. Source rows require immutable revision 1, exact
schema/payload digest and the original retained committed operation/actor/
command/version/write. Case consumers also validate original record facts,
receipt-binding correspondence, before/after case and index deltas, original
qualified claim, current claim, and exact original acceptance allocation.
History reads check the references of the historical assessment being displayed,
not merely the case's latest decision. The owner-index reader proves its exact
index transition without requalifying unrelated cases; selected cases receive
complete case validation.

The source must preserve both the policy promised at sale and any currently
applicable more favorable/legal rights. It must use actual persisted customer
receipt/quantity/timezone plus the original claimed-at/timezone; asserted
received dates, order-created times and sale-finalized times are not delivery
evidence. Missing configuration stays pending. Reason/route fields are bounded
machine codes. No legal periods, fallback policy, fake receipt or accepting
capacity source are supplied by this slice.

`LocalSalesReturnPolicyCaptureV1` and `SalesReturnPolicySourceReference` define a
distinct proposed promise companion, `sales.return.sale-policy/v1`, profile
`LOCAL_RETAIL_ORIGINAL_PROMISE_V1`. It binds organization/company/branch, original
quote operation, order, quote revision/digest and every portion's original SKU,
category and promise source. **Its protected quote selection/writer and exact
quote→collection→paid-sale/entitlement propagation under a new explicit snapshot
profile are not implemented in this patch.** Root owns the concrete retained
policy/current-rights and actual receipt/fulfillment producers; propagation
requires separately assigned ownership. Legacy receipts lacking that companion
must remain pending; a matching quote digest alone is not propagation proof.

CASE mutations require the original case owner and current direct PERFORM.
`allowsActionApproval=false` until the real approver provenance/resources can be
retained. Valid acceptance uses `RETURN_ACCEPT` with the actual original monetary
slice. An unqualified case can return only a `RETURN_CLAIM`-protected refusal,
never a zero-amount fabricated acceptance authorization.

Conservative REFUND_ENTITLEMENT/ORDER_CUSTODY hold barriers and exact case,
event/index, source/producer, authorization, original receipt/price and relevant
closing-work resources accompany the mutation. Later refund reads verify the
CASE origin explicitly and preserve that footprint through start/reserve/apply.
The source readers accumulate every retained CASE producer they actually consume,
including each selected original/current/binding/decision event's immediate
case/index predecessors and the current owner-index producer's predecessors.
These can belong to other cases: their producer-operation, case and owner/work/
authorization keys remain distinct, not aliases of the selected case or latest
index event. New-case recording also includes its verified existing-index
dependencies. The completed set is copied into the CASE cut, merged through
claim and accepted-origin reads, and includes checked event qualification-source
resources. This is the existing bounded source path's dependency closure, not a
global audit or requalification of all indexed cases; no history is discarded to
shrink the admission footprint.
The narrowly authorized change to `LocalFinancialApplicationV2.kt` is only an
additive verified `caseRecoveryResources` union; it does not change v2 ingress,
wire format, allocation, observation, status transitions or accounting. Actual
protected physical-recording capacity is still required and unavailable by
default. The separate preimage/hash is retained in the staging handoff.
The sealed v2 slot permits only 48 financial resources. Larger CASE closures
receive an authorized `CAPACITY_REQUIRED` on refund start/reserve before any
attempt or reservation is written; this patch neither truncates that closure nor
expands the sealed v2 slot. A larger physical continuation is another delivery
gate, not an accepted money movement.

Existing v1 intake bytes, canonical commands/results and original recorded replay
remain unchanged. New CASE-origin claims have no invented v1 intake. The legacy
RecordClaim rejects a colliding CASE ID, and legacy Accept refuses a CASE claim
instead of bypassing its exact decision and index transaction. No storage/IO
failure is converted into a legal ineligibility or a successful financial fact.
Retained hashes/deltas are consistency evidence, not authenticity, complete
history, current authorization or resource-readmission proof.

### Still held

This is a source-only excluded staging patch, not an integrated application
feature. Root/reviewer integration, independent compile/execution, concrete
policy and actual customer-receipt producers, new-profile sale-promise
propagation, client durable-operation routing and app_shell CASE UI are pending.
Actual refund capacity, exchanges, defect/warranty follow-through, disposition
execution and closure remain separate required work. No build/test/probe or QA
case/expected-result design was performed by this implementer for this cut.

## Remaining delivery gates

- Combined compilation, independently designed real-service/persistence/UI/local
  communication execution, fault/recovery checks and platform qualification.
- Integration/qualification of activated catalog/pricing/tender/original return-policy
  adapters, and actual protected master/client physical-outcome storage.
- Explicit reconciliation-to-original-observation matching and safe resolution of
  already-accounted recovery history; generic readmission is not enough.
- Byte-aware multi-document order/return projections beyond the existing 64 KiB
  response bound; some ordinary direct-ID mutation misses remain source errors.
- Combined verification of serial lineage and compatible snapshot/hold DTOs.
- Restaurant/café incremental portions, recipes/modifiers, kitchen/send/start/
  ready/serve, split/merge, cancellations/remakes, incidents and delivery/courier
  flows; clothing/full variant/serial and supermarket units/pack/weight workflows.
- Display-name/document snapshots, appropriate receipt/fiscal/printing flows,
  operational reconciliation and remaining original local scenario coverage.

No bounded retail success, selector, typed factory or source review replaces
these obligations. Central/backend work remains held for the owner's explicit
post-local-test approval.
