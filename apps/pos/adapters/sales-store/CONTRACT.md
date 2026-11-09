# Bounded non-sale cash store adapter

LOCAL ONLY. Implements one child of the cash/store work in the unchanged
`../../../ukkera-pos-plan-review/FINAL-PLAN.md` and original
`../../../ukkera-pos-agent-handoff-2026-10-21`. It is **not** completed T14/T16/T17,
checkout, session provisioning, opening-float source allocation, supplier invoice
settlement, a service rollout or platform/hardware qualification.

## Typed command and protected rows

`CashNonSaleMovementHandler()` implements `MasterCommandHandler` for
`CommandKey("sales.cash.move", 1)`. Its sole request is `CashNonSaleRequestV1`,
encoded by `CashNonSaleCommandCodec`. Only actual non-sale pay-in and operating
expense may apply. The enum still recognizes inventory settlement, but a fresh
request receives `Unsupported(INVENTORY_SETTLEMENT_SOURCE_REQUIRED)` after exact
authorization and before any domain mutation. A cash-side classification and
caller source ID cannot settle a supplier invoice/advance, consume a procurement
entitlement or establish designated settlement authority. Historical cash-only
inventory projections/results still decode and committed retries stay on the
kernel's original-result path; that is not permission for new settlement writes.
A future separate typed/versioned source adapter must validate actual source,
outstanding allocation, designated authority, currency and settlement permissions
and atomically commit source allocation together with actual cash. Customer
receipts/refunds, safe transfers, closing, balance overrides and generic effects
cannot be requested through this route.

Requests are canonical-only JSON, <=4 KiB, with exact core ID/money/revision/time
scalars, no arrays, bounded nesting and a nonblank <=256-character actual reason.
Unknown/duplicate/missing/alternate fields or scalar encodings fail closed. Actor,
scope, operation, digest, permission and snapshots are not request fields. Version
comes from the command key. Invalid UTF-8 is a sanitized malformed-request failure.
Human evidence time is not master ordering or proof of bank verification.

The adapter independently binds the normalized bytes to the kernel-derived
scope/actor/command digest and operation. It reads the exact branch-bound cash row,
source projection and source claim in the borrowed writer transaction. The kernel
has already verified aggregate payload integrity. Cash state is restored only by
`SalesSnapshotCodec` with independent scope/ID/revision metadata; wrong schema,
binding or corrupt body is never default-opened.

| Purpose | Kind/schema or namespace | Revision/key |
| --- | --- | --- |
| Session | `sales.cash.session`, `sales.cash.session/v1` | Domain revision + 1, checked |
| Movement projection | `sales.cash.movement`, `sales.cash.movement/v1` | Insert only, stored revision 1; source ID |
| Cash source claim | `sales.cash.source` | Source ID string, owned by cash session ID |

`SalesStorageRevision` explicitly maps kernel rows (starting at 1) to domain cash
snapshots (starting at 0). Stored zero is invalid; incrementing a max row is an
explicit `REVISION_EXHAUSTED` unsupported result, never wraparound. Domain expected
revision and the authoritative transaction read are both checked; the write CAS
uses the original **storage** revision. All individual row payloads remain <=64
KiB, not the sales snapshot codec's wider 8 MiB storage ceiling.

## Authorization and deliberately narrow admission

The prepared requirement is precisely `CASH_MOVE / PERFORM`, actual branch,
requested session/revision, original operation/digest, exact monetary amount,
fixed category reason code, and **stored actual custodian** as owner. Free-form
reason remains protected evidence, never `ActionBinding.reasonCode`. The kernel
must freshly authorize in this same transaction. Evaluation independently checks
the exact local-employee capability and original actor, even for missing or
unsupported results. An any-owner grant does not bypass actual cash custody.

Recovery resources include the original actor's authorization, the requested cash
session, and its actual drawer when present. Existing committed retries remain
entirely on the kernel's authenticated operation-result path, before this handler
would reinterpret current cash state or check a new source allocation.

**Zero pending work is the initial adapter profile.** Any pending financial
attempt, pending safe transfer, or reserved cash-out causes the explicit typed
`PENDING_FINANCIAL_OR_CUSTODY_WORK` unsupported result with no domain writes. A
complete transactional source-index/closing-work adapter is not implemented.
Empty IDs in isolated bytes do not prove history completeness, recovery readmission
or absence of omitted related work; protected provenance and kernel runtime gates
remain required. This handler is not a recovery verifier or reconciliation tool.

Public `openLocalMasterStore` still defaults to denying authorization and
unqualified runtime admission. Registering this handler does not provision a
branch, protected account, opening balance or usable production service. There is
no accepting runtime, fake protected principal or production seed factory here.

## Exhaustive translation and result meaning

The adapter calls the actual immutable `CashSessions.recordNonSale` proposal. It
requires exactly one cash-session version, one matching unique cash-source check,
no inventory dependency, one matching updated session and one matching movement.
Every sealed sales effect is explicitly handled or rejected; no extra effect,
version, uniqueness requirement or companion plan is silently dropped. Movement
facts and unchanged session custody/evidence are compared to the canonical request
and domain input. Currency/money arithmetic stays checked and exact.

The resulting `ProposedOutcome.Apply` has precisely:
1. One session CAS update using the sales-owned stored-state codec.
2. One movement projection insert (`expectedRevision = null`).
3. One branch-scoped source claim acquire, never a release or owner replacement.
4. A bounded <=2 KiB typed result. No payment, printer, drawer-open or network call.

**The movement row is an insert-only projection of the immutable journal fact,
not a database-enforced append-only table.** This handler never updates it, and
either a pre-existing source claim or projection blocks a fresh operation even if
the other row is absent. The kernel also checks actual uniqueness/CAS constraints.
The trusted composition root must not expose a generic mutation bypass over these
kinds. `CashNonSaleMovementCodec.decode(payload, expectedScope, expectedSourceId,
expectedStorageRevision)` requires an independently supplied stored revision of 1.
Its read-only projection preserves original operation/actor/digest, cash session
and drawer, category and delta, revisions, exact actual evidence/reason, and master
recording time. It cannot be converted into a reusable domain command or proof.

Typed rejection codes are `sales.cash.domain_rejected`,
`sales.cash.session_missing`, and `sales.cash.unsupported`; details are carried by
`CashNonSaleResultCodec`. A domain rejection or unsupported profile has **no cash
writes**. Unexpected corruption/programming/arithmetic errors escape to the
kernel's uncertainty handling rather than becoming success or invented "not
paid" evidence. A posting rejection is not proof that physical cash never moved.

These are only proposals. The kernel must atomically persist state/claims,
original result and actor, journal, audit and default journal-upload outbox, then
return `Recorded` only after the outer commit. Cancellation/lost reply/uncertainty
requires the original persisted operation identity, not a new source or repeated
physical movement. This adapter does not implement another deduplication cache.

## Verification boundaries

Thirty-two authored common tests cover canonical DTO/scalar/bounds failures, real
pure pay-in/expense transitions, the executable inventory-settlement source gate
and historical projection/result compatibility, exact amounts beyond binary64, exact
authorization/owner/amount/context binding, revision mapping and conflicts,
insufficient cash and closing cut, source reuse/insert-only behavior, real pending
retail cash/safe-transfer fixtures, exhaustive translation, movement projection
binding and checked overflow. Fixtures use synthetic identities with the real
security evaluator and real domain proposals; they do not claim durable commits.
One explicitly adversarial large-revision protected row tests exhaustion without
pretending its history was executed.

The public handler copies inaccessible kernel context/row values into **internal**
pure test seams. There are no public fake-kernel constructors and no reflection.
Lead-owned real SQLite integration/fault/retry tests must verify the actual public
bridge, durable atomicity and recovery gates. Module inclusion/build evidence and
independent review are lead-owned; author has not run Gradle/build commands. See
the lead's verification report for actual execution results. No complete checkout,
opening source allocator, installed device/master service, native/OS custody,
hardware or fiscal verification is implied.
