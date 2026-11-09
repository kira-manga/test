# Bounded sales transition contract

LOCAL ONLY. No bank integration, real payments, remote publication, deployment,
in-memory production repository, or claim of T14/T15/T16/T20 completion.

Authoritative requirements: preserved `../../../ukkera-pos-agent-handoff-2026-10-21/`
BLUE §§4.4/4.6/4.7, OPS §§2/4/5/7/8/9, QA fixtures/invariants, and
`../../../ukkera-pos-plan-review/FINAL-PLAN.md` §§3/6/9. Source paths here are
relative to this module. The original handoff is unchanged.

## Public boundary

`:domain:sales`, `com.ukkera.pos.sales`:

- `CashSessions`: counted exclusive opening, categorized actual movements, safe
  send/receipt, close cut, cancel-before-submit, actual counted disposition,
  immutable close and a distinct non-self review.
- `Orders`: owner-private direct read, immutable priced retail portions, quote
  replacement only before collection/handoff, explicit offer/accept/cancel.
- `Collections`: complete allocation reservation dependency before collection,
  protected quote, partial/split cash and externally attested tender, durable-intent
  proposal, unknown/not-paid/attested outcomes, paid retail finalization.
- `Returns`: narrow original receipt projection, claim recording independently of
  money permission, same-branch original entitlement acceptance, then distinct
  pending/unknown/actual partial refunds to today's paying session.

All mutations return `SalesTransition<T>`. `value` and `effects` are immutable
**proposals**, not a successful operation or committed money. Inputs are trusted
master read cuts and typed commands, never caller-supplied snapshots. Domain
snapshots are intentionally not request wire DTOs. The bounded protected-storage
codec below is separate from command admission. List inputs are copied into
nonmutable views.

`SalesCommand` holds an operation ID, current `AuthorizedAction`, authoritative
recording instant, master-computed full canonical payload digest and reason code.
`salesAuthorizationRequirement` constructs the exact action/target/revision/
operation/digest/reason/amount binding for `:core:security`. The gateway MUST compute
the digest from the complete typed command and referenced authoritative inputs;
passing through a client digest is not verification. This domain verifies that
the supplied security capability matches that binding and the concrete owner.
This is not a second policy evaluator. A missing numeric ceiling remains a denial
in the security module. A central human, device, or service principal cannot be
used as a local cashier.

### Auth targets

| Transition | Security action | Binding target | Amount |
| --- | --- | --- | --- |
| open | CASH_OPEN | drawer assignment/current revision | counted opening |
| non-sale move / safe send | CASH_MOVE / CASH_TRANSFER | cash session/current revision | actual amount |
| safe receipt | CASH_RECEIVE | transfer/current revision | transferred amount |
| begin/cancel close | CASH_CLOSE | cash session/current revision | not applicable |
| submit close | CASH_CLOSE | cash session/current revision | actual count |
| review | CASH_REVIEW | cash session/current revision | not applicable |
| create/read/edit/handoff | corresponding ORDER action | order/current revision (zero on create) | not applicable |
| begin collection | COLLECTION_BEGIN | order/current revision | protected payable |
| start/record attempt | COLLECTION_ATTEST | collection/current revision | attempt amount |
| resolve unknown | COLLECTION_RESOLVE | collection/current revision | original attempt amount |
| finalize | COLLECTION_FINALIZE or distinct COLLECTION_FINALIZE_ZERO | order/current revision | protected payable |
| record claim | RETURN_CLAIM | new claim ID/revision zero | not applicable |
| receipt lookup / accept | RETURN_RECEIPT_LOOKUP / RETURN_ACCEPT | sale entitlement/current revision | acceptance only: original slice payable |
| start/record refund | REFUND_RECORD | accepted return/current revision | refund attempt amount |
| resolve unknown refund | REFUND_RESOLVE | accepted return/current revision | original refund attempt amount |

Safe receipt also requires the current destination custody assignment and a
different receiving custodian. Handoff acceptance binds the intended recipient;
it cannot provide general access to an unrelated owner's order.

## Mandatory atomic adapter work

Before showing success, one authoritative local transaction must:

1. Resolve `(branch, operationId)` from durable results using the canonical payload
   and original actor. Return an existing verified result; reject payload/actor
   reuse. Never reinterpret a timeout as “not paid” or generate a fresh charge ID.
2. Read/revalidate current employee session/admission/grants and recovery resource
   admission. Check **every** `versions` entry against current durable rows.
   Check closing work-index changes transactionally with affected orders; an
   out-of-transaction empty query is not closure readiness.
3. Enforce real branch-scoped `unique` constraints. Active drawer/custodian
   uniqueness must distinguish active assignments from immutable history. Claim
   acceptance, original cash/payment/refund sources and one sale per order also
   need real constraints. An opening source ID is a one-time allocated float/
   transfer/retained-float reconciliation source, not a reusable safe/account ID;
   verify its actual custody and allocated amount and consume it once. Handoff
   offer IDs are one-time even if later cancelled. This module does not implement
   those database indexes or opening source allocation records.
4. Apply the exact `inventoryDependencies.plan`, checking its source cut,
   operation and authoritative commit time/deadline with `InventoryPlanner.requireCurrent`.
   Inputs are constructor-restricted paired `ReservationPlan`,
   `ReservationSafetyResult.Safe` and `ReservationIssueResult.Issued`, not detached
   proofs. The dependency retains the actual immutable plan and distinguishes
   RESERVE / REVALIDATE / RETAIL_ISSUE; a same-revision/operation/reservation tuple
   from a different plan does not satisfy it. Never commit
   a stock issue before validating the sales transition. Hold/recall/expiry does
   not erase captured money or authorize another payment.
5. Persist the new aggregates, append-only observations/movements and immutable
   document snapshots with operation result, audit and outboxes all-or-none.
   Preserve the original actor/session/quote even after logout/re-login.
6. Only then return success. Printer, WAN, bank or optional standby responses are
   not part of this pure contract and are not invented as acknowledgements.

Inspect the combined tree's lead-owned verification report for actual build/test
evidence. This module's tests are L1 pure/dependency-integration tests, NOT Room
crash consistency, real RPC, physical money or OS acceptance evidence.

## Conservative boundaries

- Retail allocations use the pricing **portion ID** in inventory's `lineId`, and
  exact pinned stock base unit/scale. Every portion's full quantity must be covered.
  Empty/partial allocation, a positive total, a service flag or manager permission
  cannot bypass stock. Only `ValueIssuePurpose.RETAIL` finalization is admitted.
  Genuine non-stock services, packs needing conversions, mixed virtual kits and
  paid-unsent recipe financial finalization need additional capability/timing
  integration; this package does not prematurely issue ingredients.
- A collection reservation starts protected as `UNKNOWN` in inventory. This
  conservative exposure need not be downgraded merely because one attempt was
  not paid. No unlock/reprice/release or ordinary ownership transfer of a locked,
  partial or uncertain collection is provided. Supervised resolution is remaining
  work, not an unsafe implicit fallback.
- Each new payment attempt needs a fresh, current same-operation safety plan,
  including a commit deadline for expiring stock/leases. A recall/expiry/count
  hold blocks NEW physical collection intent, but cannot block recording money
  actually received for an already-admitted attempt or erase an unknown outcome.
- Session closing reconciles admitted unknown work **before** the immutable cash
  cut. New money is rejected while Closing. Actual count minus expected is the
  preserved variance. Closing safe delivery equals actual count minus actual
  retained float and requires actual delivery evidence; pending safe receipt
  blocks drawer reuse. It does not retroactively alter the frozen expected ledger.
- Cash receipt posts `received - change` once. External attestation has no cash
  delta and no change capability. Unknown refund attempts reserve available cash;
  other payouts cannot spend that reservation. No cash movement is posted until
  actual outcome attestation. Reference strings are evidence/duplicate hints, not
  bank verification.
- Sale finalization freezes a component-by-original-payment allocation matrix.
  Both row and tender totals conserve. Return acceptance uses pricing's stored
  cumulative-floor cursor and final residual, consumes remaining quantity and
  original tender allowances immediately, and creates a nonspendable refund
  obligation. Repeated partial claims cannot regenerate consumed rights.
- Return disposition is a **requested inspected disposition**, not a stock-posting
  proof. Acceptance/refund never restocks goods or reconstructs recipes. Current
  pricing is irrelevant; fixed and explicit-only charge exceptions are not
  automatically refunded. Other-method refunds, receiptless exceptions, exchanges
  and container deposit allocation are not implemented.
- Maximums: 256 retail allocations/portions, 1,024 price components, 64 payment or
  refund attempts per collection/return, 128 pending attempts/transfers per cash
  snapshot. These are domain limits, not permission to send a large RPC body.
  Gateways must enforce negotiated byte bounds and normalized/paged child storage.

## Protected local snapshot codec (schema 1)

`SalesSnapshotCodec` provides explicit encode/decode pairs for cash sessions, safe
transfers, orders, collections, original receipt entitlements (including their
immutable paid sale), recorded claims and accepted-return/refund state. These are
**protected local stored snapshots**, never authenticated commands, public receipt
projections or reusable capabilities. No `AuthorizedAction`, command/digest,
credentials, reservation proof, issue proof or inventory plan is serialized.
Stock requirement identities are preserved, not claimed to be current stock safety.

Decode requires an independent `SalesSnapshotBinding(kind, scope, id, revision)`
from the protected row/envelope. Do not derive that expected binding from the
payload. The decoder rejects unknown schema versions/fields/statuses, inconsistent
amounts and links, unknown/malformed scalar forms, duplicate keys and noncanonical
JSON rather than applying defaults or silently migrating. Money/quantity/revision/
IDs use `:core:types` exact serializers; `BusinessDate` and `UtcInstant` distinguish
operational dates from canonical UTC recording evidence. Immutable state owns
defensive list copies after hydration. Errors expose stable codes, not payloads.

The envelope has an 8 MiB UTF-8 bound (also bounded before UTF-8 allocation) and
48-level JSON nesting bound. A universal outer-array bound of 1,024 is checked
before deserialization/canonical re-encoding; the tighter existing domain child
bounds also remain in force. Quoted pricing has its own independent bounds. A
maximum-size valid pricing snapshot plus a large tender matrix may exceed this
sales envelope and is rejected; no automatic truncation/alternate format is used.
This is a **storage** bound, not an RPC or UI performance qualification.

The protected quote is embedded unchanged as the pricing owner's canonical
`StoredPricingSnapshotCodec` string. It restores the original snapshot and stored
historical calculation profile; sales never reruns current pricing or bypasses
pricing constructors. Return cursors restore their original cumulative quantity
against this stored snapshot. `AcceptedReturn.consumedQuantityBefore` preserves
the exact original starting cursor so 33/33/34 and zero-valued slices can be
validated and resumed without regenerating entitlement.

Required cross-row work remains explicit:

- Collection decode requires its current protected order and cash session; their
  owner/creator, scope, quote, revision, paid state and attempt links must agree.
- Claim and accepted-return decode require the original receipt; accepted-return
  decode also requires its accepted claim. Original portion/component/tender,
  exact cumulative slice, original actors/operations and refund coverage are
  checked. A recorded disputed/exhausted claim remains a valid nonmoney record.
- `SalesSnapshotLinks.requireCashSessionPending` reconstructs the pending attempt
  IDs and reserved cash from the **complete current source index** provided by the
  authoritative transaction; incomplete/extra sources or reservation mismatches
  reject. Its pending safe-transfer sum must not exceed cumulative safe-drop
  debits (checked money arithmetic); individual transfer bounds are insufficient.
  `requireSafeTransfer` links actual custody and closing dispositions.
- `requireReceiptHistory` can verify a **complete bounded history** of at most
  1,024 accepted returns and matching claims. It rejects missing/duplicate or
  overlapping quantity ranges, replays only original posted tender allocations,
  and compares the exact remaining matrix and entitlement revision. Larger
  histories need a separately qualified normalized/paged verifier, not truncation.

An isolated structurally valid snapshot cannot prove that another row exists,
that history is complete, or that its body was not coherently replaced. For example,
two original 33-cent thirds can only be distinguished by their cumulative history.
The codec is not a signature, DB foreign key, unique index, permission grant,
operation-result journal or recovery readmission. Actual protected provenance,
complete transactional indexes, immutable history and missing-history resource
reconciliation remain mandatory. No Room handler/index integration or process-loss
qualification is implied by these pure codec tests. See the lead-owned combined
verification report for commands/results; authored tests alone are not evidence.

## Still required for full task acceptance

- T14: production rows/indexes/business-day lifecycle, denomination/recount UX,
  actual custody integrations, drawer/courier transfers, linked post-close
  correction workflows, explicit configured self-review and all CASH variants.
- T15: server indexed lists/subscriptions/private cache invalidation, parked and
  full commercial lifecycle, scoped manager reassignment/supervised collection
  handoff, compatible atomic bill split/merge, floor/table UI and all OWN variants.
- T16: production command DTOs and registered handlers, protected-codec admission
  into real rows/indexes and migrations, atomic
  Room/result/audit/outbox integration, process-loss/physical-payment recovery,
  mixed stock timing and paid-unsent fulfillment, receipt jobs/UI and full PAY/REL
  variants. There is no local fake checkout standing in for those proofs.
- T20: verified statutory/category/deadline configuration and inspection policies,
  serial/stock-disposition integration, fixed/performance-dependent charge remedies,
  separately authorized alternative tender, exchanges/restricted offsets,
  receiptless/deposit workflows, production offline retention/lookup and UI.
- Windows/macOS/Android/iPhone/iPad client/master qualification, fiscal/legal,
  hardware, recovery, independent review and complete original scenario rollups.

The module is not a customer balance, credit-sale, loyalty or unrestricted approval
system. There are no synthetic bank successes or real charge/refund calls.
