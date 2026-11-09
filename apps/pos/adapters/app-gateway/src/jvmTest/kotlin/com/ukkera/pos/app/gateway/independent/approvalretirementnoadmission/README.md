# Unpriced arrival retirement — bounded client slices

This record describes the `dad9bf0` suite. Its held-tap gap is superseded by
`ArrivalRetirementHeldLookupSlicesTest`: six new methods passed on the newer
shared implementation. [Current held-lookup results and limits](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/approval-held-lookup-20261102/README.md).
The earlier tests and frozen expectations below are unchanged.

The unchanged [version 2 cases](../../../../../../../../../../../../../../qa/pos/designs/arrival-retirement-no-admission-v2/cases.md),
[fixtures](../../../../../../../../../../../../../../qa/pos/designs/arrival-retirement-no-admission-v2/fixtures.json)
and [freeze](../../../../../../../../../../../../../../qa/pos/designs/arrival-retirement-no-admission-v2/freeze.json)
are the expectations. The ten gateway methods and four controller methods passed, with zero failures,
errors or skips; see the [scoped results](../../../../../../../../../../../../../../qa/pos/traceability/approval-no-admission-20261101/README.md).
These results do not establish whole-case or native acceptance.

## Mapped gateway variants

| Method suffix | Frozen case and bounded evidence |
| --- | --- |
| `FreshI1IsDurableBeforeHandoffAndRetainsSameApprovalI0` | ARRNA-01: real encrypted journal write/reopen/read before the sole I1 handoff; exact Record A, selected Separate rule, confirmation and same-approval completed I0 retained. Separate post-return generic-unresolved repeat performs lookup without redispatch. |
| `ExactLiveNegativeCompletesOnlyI1AndSurvivesFreshReopen` | ARRNA-02: both exact controlled negative reasons finish only I1; prior I0 and original issuance/input/PDF survive fresh real storage and requester recovery-view change. Pending/submit remain conservative. |
| `EveryGenericRetirementReplyLeavesI1LookupOnlyAfterReopen` | ARRNA-03: Denied, Busy, Unavailable and StorageUncertain remain unresolved after reopen; subsequent explicit retirement request performs lookup only. |
| `TransportErrorAndCancelledWaitCannotReconstructNoAdmission` | ARRNA-03: controlled transport exception and actual cancelled gateway wait retain saved unresolved I1. Neither delivers an exact negative. |
| `EqualCopiesAndOtherOriginBindingsCannotCompleteLiveI1` | ARRNA-04: exact-coordinate copy, other invocation/sequence/approval/operation/command/payload/confirmation reply carriers fail live-reference ownership and cannot release I1. |
| `ChangedRecordPayloadOrPreviewFailsDurableBindingWithoutRewritingA` | ARRNA-04: durable binding validator rejects 12/20-unit payloads, changed source/custody/supplier, distinct full B PDF manifest, rule/publication and confirmation. A remains byte-equal. This is a validator boundary, not a physical dispatch-origin fence. |
| `StaleI1CompletionCasCannotReleaseHeldI2AcrossReopen` | ARRNA-05: actual I1 completion retained; an explicit identical-input I2 gets a fresh ID/sequence. Actual whole-record CAS rejects the previously saved I1 completion while I2 is held; reopen preserves unresolved I2 and I0/I1 history; exact live I2 reply finishes I2 only. |
| `SupportedLegacyUnresolvedRecordHasNoAcceptingInvocationDefault` | ARRNA-06: current supported omitted-field unresolved representation and real reopen remain lookup-only; no invented invocation can complete it. This is not an identified shipped migration or old physical dispatch delivery. |
| `InitialKnownNoCommitSaveFailurePreservesI0AndPreventsHandoff` | ARRNA-07: wrapper refuses before delegating the fresh write; zero I1 handoffs, unchanged durable I0/input/original source after reopen. |
| `CompletionKnownNoCommitSaveFailureLeavesI1LookupOnly` | ARRNA-07: exact live negative received, but wrapper refuses before delegating completion write; real saved I1 stays unresolved; subsequent request performs lookup without redispatch. |

## Fixture and evidence boundary

Logical aliases map consistently to UUID-valid existing public coordinates. A is
one 24-base-unit Record, 2 cartons × 12; B is a distinct one-carton/12-unit Record.
Each selects one required complete frozen PDF, zero optional originals, the same
Separate rule, purchasing publication revision 5 and retention class revision 2.
The original typed body is unapproved and supplier-owned/uninspected held, without
price, invoice, settlement, PO allocation or an inspection-pass default. The UI
input/summary snapshots are controlled retained client metadata, not a full form
gesture qualification or an authenticated quantity-conversion source.

The exact full checked-in PDF bytes are saved in actual encrypted documentary
source records, with their complete unsubmitted transfer bodies. They are compared
after reopen, not replaced by extracted text or merely a digest. Actual JCA AES-GCM,
atomic replacement/fsync and fresh production-store connections are used under
controlled key/custody inputs. One stable journal wrapper dynamically delegates to
the reopened store; it never opens a second competing file lock.

Original issuance is an explicitly supplied historical client receipt observation
used only by actual gateway lookup/reconciliation. Saved preview metadata does not
construct a Ready response; no Issued result, accepting principal, native grant,
positive Master business or original-history authority is created. I0 is completed
through the actual gateway using an exact controlled no-admission reply for approval
A before campaign counters are sampled. I1 and I2 retain the same approval/input;
earlier completed invocations are preserved separately from the latest attempt.

Counters independently check zero Record/Qualify/Reject/Dispatch endpoint calls,
preview and issuance calls. They do not claim Master ledger/custody effects or the
frozen EGP 100000 drawer baseline. No retirement completion permits Direct fallback,
approval consumption, replacement or business submission.

## Explicitly UNBOUND

- The frozen repeated tap **while held** must start lookup. The current controller
  disables work during its wait and the gateway holds its requests mutex. A no-op
  or the separately bound post-return lookup is not a passing held-tap oracle.
- D2-only refusal/earlier D1 late-execution fencing, unavailable origin association,
  remote serialization, duplicate transport callbacks and lost-negative recovery.
  The stale completion test proves actual durable CAS, not transport replay fencing.
- Physical source generation 0 and original-history generation/head authenticity:
  no physical generation field or supported genuine original-history qualification
  is manufactured. Manifest equality is not authority. Retention-publication and
  original session/drawer authority are not expressed by the journal body.
- A shipped old-journal migration and its old pending/history/origin continuity;
  staged native rollback, crash/power-loss/fsync fault campaigns, postcommit release,
  accepted-work lifecycle and separately authentic terminal receipts.
- Genuine current-Master REQUEST/APPROVE/PERFORM, account/session/device authority,
  native non-Direct Record acceptance, money/stock/custody/PO business effects and RPC.
- The [unchanged version 1 text original](../../../../../../../../../../../../../../qa/pos/designs/arrival-retirement-no-admission/README.md)
  remains UNBOUND for supported public arrival media. Version 2 does not replace
  the earlier manual-discount workflow or qualify any whole original scenario.

All eight whole frozen arrival cases remain unpassed. Only individual mapped
methods may receive actual execution outcomes.

Gateway selector:

```text
com.ukkera.pos.app.gateway.independent.approvalretirementnoadmission.ArrivalRetirementNoAdmissionGatewayTest
```

## Controller-over-gateway slices

Four methods additionally exercise explicit separate retry and I0/I1 preservation,
stale submit hints after generic or completion-save failure, fresh current-client
refusal, and obsolete post-durable UI delivery against lifecycle/Record B. Actual
I/O and request completion are awaited; advancing a test scheduler alone is not
persistence evidence. The held-tap lookup and native/transport boundaries above
remain unchanged.

Controller selector:

```text
com.ukkera.pos.app.gateway.independent.approvalretirementnoadmission.ArrivalRetirementNoAdmissionControllerSlicesTest
```
