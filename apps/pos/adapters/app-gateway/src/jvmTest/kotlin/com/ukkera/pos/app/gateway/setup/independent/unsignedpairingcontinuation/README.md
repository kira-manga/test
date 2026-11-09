# Unsigned pairing continuation — focused common tests

Frozen expectations: `qa/pos/designs/unsigned-pairing-continuation/` **UPRC-v1**
and the separately preserved **UPRC-v2** addendum. **All 18 mapped methods have
passing outcomes** on the source committed alongside this record: the 16
continuation methods passed the corrected focused retry; the two unchanged
document methods passed the earlier compatible selection. Zero failures/errors/
skips in those passing selections. These are bounded common-layer subsets,
not whole-case results. The original handoff assertion failure is preserved in
`qa/pos/traceability/unsigned-pairing-continuation-20261103/README.md`.

The real common `LocalSetupCoordinator`, `LocalFreshDevicePairingIntents` and
typed I/C/P/R codecs execute with controlled application ports and synthetic B0
DATA. The data retains the frozen scope/device/key/preparation/acquisition,
capabilities, AppId and deadline correlations. Runtime-assigned request IDs carry
the logical MREQ labels without an additional persisted identifier or envelope.
Signature-sized buffers and root-document carriers provide no verified signature,
native authority, actual SDK correspondence or custody.

The harness reuses controlled in-memory storage, scheduling gates and document
callbacks from the existing first-transfer harness. A recreated coordinator uses
the same retained synthetic world and journal bytes after confirmed controlled
close. This is coordinator restoration, not a fresh OS process or durable native
restart. Document checks are controlled full-data transport and byte-container
bounds; **SAF and other OS document adapters do not run**.

## Method mapping

| Test method | Frozen subset |
| --- | --- |
| `explicitRetirementAllocationAndLinkedSuccessorProofStopAtRetainedHandoff` | UPRC-01/04/05/07/08/10: complete original review, exact R transfer, explicit allocation, separately acknowledged linked C1/P1 proof, exact retained handoff and immutable prior bytes; no implicit head approval or admission |
| `preparedOriginalWithoutPossessionRetiresWithoutAllocationOrResponse` | UPRC-01: PREPARED/no-P Master retirement only; no target-allocation eligibility result |
| `pendingExactIntentsBlockRetirementAllocationAndSuccessorPreparationAndThreeClicks` | UPRC-01/05/07/10: held acknowledgment for each distinct action, exact binding at port entry, three-click suppression and stale/direct duplicate refusal |
| `failedExactRetirementAndAllocationIntentsNeverEnterCorrespondingCalls` | UPRC-03/05/10: controlled rejected acknowledgments produce zero corresponding invocations and preserve prior files |
| `lostThrowingAndCancelledRetirementRepliesRecoverOnlyTheSameRetirement` | UPRC-03/10: one entered attempt for each reply fault, exact original-only R recovery, no retirement/request/allocation repeat |
| `cachedTerminalJournalNeedsActualOriginalAndExactRetirementLookupAfterRecreation` | UPRC-02/03: terminal metadata stays unconfirmed with missing, unavailable or conflicting original lookup; later exact recovery returns the same R |
| `lostThrowingAndCancelledAllocationRepliesRecoverWithoutAnotherAllocation` | UPRC-06/10: one allocation attempt, unknown presentation, exact original-only allocation recovery and unchanged I/preparation/acquisition |
| `recreationPreservesOriginalAndSuccessorCycleAndRequiresItsExactRetirementForRecovery` | UPRC-06/08/10: exact R required after recreation, one successor allocation/response cycle retained, all old files preserved and exact P1 recovered without repeat work |
| `malformedWrongKindAndOtherOriginalRetirementsDoNotReplaceSelectionOrAllocate` | UPRC-02/04: truncated/trailing/replacement/wrong-kind R and other target/root DATA refuse without replacing the original or allocating |
| `wrongPredecessorOriginOrIdentityChallengeAndOldPossessionCannotAdvanceSuccessor` | UPRC-07/08: old C, absent/wrong retirement link, other root/identity C and old P cannot sign/prove the successor |
| `unavailableCapacityAndForeignAllocationResultsRetainAttemptWithoutCreatingSuccessor` | UPRC-05/06/09: controlled final refusal, capacity presentation and foreign reference retain one bound attempt without completion/replacement |
| `lateNativePhasesUnavailableOriginalAndLostCurrentClientCannotInvokeRetirement` | UPRC-02: reported SIGNING_INTENT/APPROVED/retirement-signing phases, unavailable original and loss of the current application client refuse retirement; actual native authority/custody races remain unrun |
| `lockingAnEnteredRetirementOrAllocationHidesReviewAndCannotRepeatTheAttempt` | UPRC-10: lock hides the private projection while a call is held, preserves the attempt and recovers only the same outcome |
| `cancelledAndUnavailableRetirementTransfersDoNotCompleteOrAllocate` | UPRC-04: cancelled/unavailable controlled reads/writes report no successful transfer or allocation; exact R remains re-exportable |
| `knownEmptyInitialAllocationWaitsForIntentAndRecoversWithoutFabricatingOldPossession` | UPRC-V2-01: retained initial slot/no signing intent and exact no-P R, held intent, explicit allocation and lost-reply recovery; no fabricated P0 |
| `missingInitialSlotAndUncertainNativeSigningCannotBeTreatedAsKnownEmpty` | UPRC-V2-02: unavailable native source, missing A0 reference, response-signing uncertainty and uncorrelated allocation-signing intent supply no fresh allocation call |
| `belowAndAtInclusiveRetirementBoundTransportEveryByteWithoutChangingExistingIcpBounds` | UPRC-04: `a5` transport-only specimens at 2,187,263 and 2,187,264 bytes, complete byte preservation, defensive copies and unchanged I/C/P limits; specimens are rejected as canonical R |
| `aboveInclusiveRetirementBoundAndEmptyInputAreRefusedWithoutTruncation` | UPRC-04: 2,187,265-byte and empty full-data containers refuse rather than truncate |

The v1 files and expectations are unchanged. The v1 grouped absent-P target
allocation refusal is not wired as a passing result; the v2 methods separately
cover the corrected known-empty state and missing/uncertain source refusals.

## Execution selectors and limits

- `com.ukkera.pos.app.gateway.setup.independent.unsignedpairingcontinuation.UnsignedPairingContinuationControlledTest` — 16 methods.
- `com.ukkera.pos.app.gateway.setup.independent.unsignedpairingcontinuation.UnsignedPairingRetirementDocumentBoundaryTest` — 2 methods.

The finite-cycle checks cover **one original and one successor allocation**.
They do not execute the full 256-allocation boundary or native 4 MiB retention
capacity. A controlled capacity reply demonstrates refusal routing only.

G0 is checked through absence of unrelated approval/admission/employee/new-Master
port calls and empty admission journals before handoff and in all other paths.
The explicit successor handoff must retain exactly its review-only identity DATA,
with no head/admission attempt, authority, terminal or event; the target admission
journal remains empty. Actual stock/cash/business-event values, authentication
throttles, device revocation, employee sessions and RPC attachment are not
exercised by these ports.

All ten whole v1 cases and both whole v2 cases remain **NOT RUN**. Genuine native
root/lineage/signature and possession verification, official SDK/installed-facts
correspondence, custody and unknown native close, current protected Master
authority/admission races, actual SQLite/force/restart, full count/retention
boundaries and production storage/communication remain #59 work. Native platform
and document/lifecycle variants for Windows/macOS/Android/iPhone/iPad remain #63;
owner execution remains #61. There is no network/WAN or central-service execution
in these tests, and central work remains held. Missing actual sources/adapters do
not gain a successful substitute from the controlled harness.
