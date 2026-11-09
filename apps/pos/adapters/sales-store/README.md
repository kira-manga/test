# Bounded typed cash-store adapter

LOCAL ONLY. This is the first typed adapter over the
[durable command kernel](../../core/database/README.md), not a complete checkout,
session-provisioning service or accepting production gateway.

## Implemented contract

- `sales.cash.move` version1 accepts a canonical, bounded DTO for a positive
  non-sale pay-in or operating expense. Actor, branch, authority, digest and
  capability are not request fields. Money remains exact integer minor units.
- The handler reads the actual whole cash session, movement projection and
  source-claim owner inside the kernel transaction. The current local employee
  must be the actual custodian even with an any-owner grant. Recovery admission
  includes that actor and the actual cash session/drawer.
- Every domain version check, unique source claim, effect and companion
  dependency is checked. Unknown effects or inventory dependencies fail closed.
  Success atomically updates the session, inserts the movement projection,
  acquires the source claim and stores result/journal/audit/outbox intent.
  The kernel replies only after its outer commit. A projection in the generic
  aggregate table is not itself a database-enforced immutable fact table.
- Whole-session domain revision0 maps to storage revision1, using checked
  arithmetic. The mapping is specific to this one-transition/one-row profile,
  not a promise for arbitrary normalized domain children.
- An original committed operation is recovered under current result-recovery
  authorization. A new operation cannot reuse its source, even with the same
  owner. Storage uncertainty is not an invented successful payment or a safe
  retry with a new ID.

## Deliberate nonaccepting boundaries

Fresh `INVENTORY_SETTLEMENT` returns
`Unsupported(INVENTORY_SETTLEMENT_SOURCE_REQUIRED)` after exact authorization.
A cash grant and available drawer balance do not prove supplier/payable/source
authority. The category and historical codecs remain recognizable; no retained
old-producer SQLite fixture yet proves replay of a previously Applied settlement.

Pending payment/refund attempts, positive refund reserves and transfer work are
outside this adapter's source-index profile and are refused without changing
money. Closed sessions, insufficient available cash, duplicate sources, revision
exhaustion and values exceeding the64KiB row profile cannot become success.
The8MiB domain codec limit does not widen the kernel profile. A future collection
path must reserve durable outcome-recording capacity **before** physical money
is accepted; refusing later for lack of space is not a recovery policy.

No session genesis/import provenance, inventory settlement, sale finalizer,
return/refund adapter, custody transfer, production identity or UI/RPC binding is
provided here. All broader original requirements remain in scope.

## Executed evidence

The first typed-cash targeted run passed33 JVM and33 Android-host tests, plus13
public-handler/real-file Room integration cases within database61. Later retained
reader/barrier runs added public cash integrations; the current source-cut results
are recorded in the shared verification report, not inferred from this first run.
Tests include
exact amounts above2^53, canonical DTO rejection, actual actor/custodian checks,
source uniqueness, commit/reopen behavior and a genuine domain-created pending
cash refund retaining reserve100 with expected1100/available1000; non-sale
payouts of1000 and1100 are both refused by this bounded profile.

Test identities, writer appointment/custody and seeded companion provenance are
explicitly synthetic. The concurrency fixture measures session-CAS pressure,
not an isolated cross-session unique-source race. See the
[verification report](../../VERIFICATION_REPORT.md) for source cuts and limits;
no original business scenario, physical-money flow or production gate is
certified by these module counts.
