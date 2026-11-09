# Fresh-device pairing: bounded JVM workflow selections

Requirements: [approved first-transfer contract](../../../../../../../../../../../../../../../qa/pos/contracts/LOCAL-FRESH-DEVICE-PAIRING-WORKFLOW.md)
and the unchanged frozen `qa/pos/designs/fresh-device-pairing-workflow/`
cases/fixtures, dated 2 November 2026. **The corrected selection compiled and
passed all 30 new methods (23 workflow +7 boundary), zero failures/errors/skips;
30 unchanged coordinator regressions also passed. All twelve whole FDPW cases
remain unverified.** See [results and the preserved initial watch failure](../../../../../../../../../../../../../../../qa/pos/traceability/fresh-device-pairing-workflow-20261102/README.md).

## Selections and evidence boundary

Package: `com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow`.

- `FreshDevicePairingWorkflowControlledTest`: **23 methods, 48 independently
  instantiated controlled scenario setups**, all 23 methods passed in the corrected
  selection. Setups are source-mapped subsets, not whole-case passes.
- `LocalFreshDevicePairingIntentBoundaryTest`: separate **7 methods, 62
  independently reset input/intent subsets**. It uses the real input validator and
  intent codec with controlled storage, not native/platform durability; all seven methods passed.

The controlled workflow runs the common coordinator, real immutable-intent codec
and existing typed I/C/P binary codecs. T and M have separate intent, launch and
admission storage namespaces; they share only explicitly controlled source DATA.
Every entered effect is recorded with the journal snapshot and submitted arguments,
including calls that throw, lose their reply or cancel after entry. Assertions stay
outside port bodies so a caught port exception cannot hide an entered call.

`FreshDevicePairingWorkflowData` supplies complete synthetic typed carriers for
the frozen logical correlations, not genuine native custody, signatures, keys or
authenticated Kit facts. Signature-sized buffers and SEC1-shaped points are DATA
only. M's runtime-assigned request ID carries the logical original A correlation;
its value remains unchanged through that scenario. Canonical C retains the exact
**300,000-ms** M deadline. Public I/C/P never contain the private target-name canary.

## Controlled method mappings

Each row establishes only the named application-port subset if actually executed
successfully. It does not satisfy the whole referenced frozen case.

| Method | Setups | Frozen subsets and observations |
| --- | ---: | --- |
| `twoCoordinatorsTransferExactTypedOriginalsAndStopAtRetainedReview` | 1 | FDPW-001/006/010: explicit I/C/P chain; matching acknowledged intents precede five entered phases; complete bytes and source-owned M review handle stay exact; matching controlled TARGET_PROVED alone permits same-original retained-review handoff, without approval/admission |
| `eachPendingIntentBlocksItsEffectAndDuplicateTapsQueueNothing` | 5 | FDPW-004/005/007: one independently gated acknowledgement/effect per phase; pending acknowledgement permits zero entries; exact acknowledgement permits one entry; duplicate taps queue no further phase |
| `refusedIntentAcknowledgementNeverEntersAnyCorrespondingNativePhase` | 5 | FDPW-004/005: controlled write refusal before acknowledgement causes zero corresponding entries and no completed phase |
| `targetReviewEditsAndMasterAsTargetDoNotCreateNativeWork` | 1 | FDPW-002: input review/edit is read-only; retained input cannot change; original operational M cannot create T or another Master through direct actions |
| `incompleteAndForeignSdkDataCannotBeExportedOrReacquired` | 2 | FDPW-003/008: unavailable acquisition or U identity yields no completed/exported I and no second acquisition; T original reference stays pinned |
| `repeatedExactArtifactsRemainReadOnlyAndCallerMutationCannotChangeI` | 1 | FDPW-005/007/010: exact repeat imports/re-exports create no new preparation/response/proof; exported-buffer mutation cannot alter retained I |
| `corruptWrongKindAndOversizeChallengeDocumentsDoNotSign` | 3 | FDPW-005/007/010: before any valid C import, truncated C and wrong artifact kind refuse with visible invalid-input status; C maximum+1 refuses at the carrier constructor; direct response without C enters zero responses and changes no intent |
| `foreignChallengeAndPossessionDoNotReplacePinnedOriginals` | 1 | FDPW-005/007: U C cannot replace pinned A C; U P cannot populate original M possession or dispatch proof; request/C remain exact |
| `unavailablePreparedOnlyOrForeignMasterResultsCannotConfirmHandoff` | 3 | FDPW-006: unavailable, PREPARED-only and foreign proved results each fail to establish TARGET_PROVED or retained-review eligibility |
| `actualMatchingSourceConfirmationOverridesPendingPresentationOnlyThroughRecovery` | 1 | FDPW-006/008: unavailable original review stays unresolved; later exact controlled source confirmation overrides a pending projection only through original recovery, without another proof |
| `lostPreparationRepliesKeepUnobservedOwnerQuarantined` | 3 | FDPW-008/012: lost/thrown/cancelled preparation entry preserves acknowledged inputs/attempt count; unreturned factory owner gives no I or confirmed close and blocks replacement or even existing-only open in that quarantined service |
| `lostSdkAcquisitionRepliesRecoverIWithoutAnotherAcquisition` | 3 | FDPW-008: lost/thrown/cancelled acquisition retains its attempt and recovers exact source-completed I from the captured owner, without acquisition redispatch |
| `lostMasterPreparationRepliesRecoverCWithoutAnotherRequest` | 3 | FDPW-008: lost/thrown/cancelled preparation recovers exact original M C/request through retained native-review DATA, without a new request/preparation |
| `lostPossessionRepliesRecoverPWithoutAnotherResponse` | 3 | FDPW-008: lost/thrown/cancelled response recovers exact completed original P, without responding again |
| `lostMasterProofRepliesRecoverExactConfirmationWithoutAnotherProof` | 3 | FDPW-006/008: lost/thrown/cancelled proof recovers only the matching original's controlled source-confirmed phase, without another proof |
| `lostMasterPreparationNeverAdoptsTheTemptingLatestAlternateOriginal` | 1 | FDPW-007/008: latest U cannot replace the retained request; only original A later restores C and its unchanged deadline |
| `restorationReexportsExactCompletedOriginalsWithoutAnyNewEffect` | 1 | FDPW-006/008: coordinator recreation after confirmed controlled retirement reads the same retained source originals, re-exports exact I/C/P and hands off the same review without any new effect |
| `lockedPendingResponseClearsPrivateProjectionAndRecoversWithoutResigning` | 1 | FDPW-008: locking hides private projection during pending response; retained inputs survive; original recovery returns completed P without re-signing |
| `staleDocumentCallbackWhileLockedCannotPrepareTheMaster` | 1 | FDPW-007/008: locked document completion and detached old review cause no preparation; old review remains unusable after presentation resumes |
| `targetClockDoesNotAuthorizeMasterAndWatchCannotRenewOriginalChallenge` | 1 | FDPW-009: far-ahead T elapsed time does not reject M C; 30,000-ms watch withdraws presentation without inventing response completion/close; after actual controlled close, new-service recovery reads original P; M unavailable result supplies no timing fallback or deadline renewal |
| `controlledNativeCapacityRefusalIsNotPossessionOrReplacementPermission` | 1 | FDPW-011 bounded refusal: injected CAPACITY_EXHAUSTED remains visible; exact C/attempt binding survives; no P, proof or replacement permission follows duplicates/export/recovery |
| `unknownOwnedCloseKeepsOriginalBindingAndBlocksFreshAcquisition` | 3 | FDPW-012: lost/thrown/cancelled owned close each remains unconfirmed; retained binding survives, failed close is not retried, and fresh/existing-only acquisition stays blocked |
| `cancelledOrUnavailableDocumentsNeverReportCompletedTransfer` | 1 | FDPW-010: controlled import/export cancellation/unavailability never becomes completion; supplied target/M export problems remain visible; transfer alone prepares nothing; a separate explicit preparation does not confer response/proof |

## Separate input/intent boundary selection

| Method | Frozen subset |
| --- | --- |
| `validTextBoundariesAndDistinctUnicodeAreRetainedExactly` | FDPW-002/010: exact valid text boundaries and distinct Unicode spellings |
| `invalidInputIsRejectedBeforeAnyIntentWrite` | FDPW-004/010: invalid text/input cannot create retained intent |
| `capabilityBoundaryRecipesStayExactAndDescriptionsOnly` | FDPW-002/010: exact capability count/code recipes, without grants |
| `missingOrMismatchedAcknowledgementCannotReturnPreparedIntent` | FDPW-004: missing/mismatched acknowledgement refuses |
| `retainedPreparationSurvivesRecreationWithoutReplacementOrReplay` | FDPW-004/008: immutable intent survives storage-wrapper recreation |
| `validAdapterRewriteCannotAcknowledgeDifferentReviewedInputs` | FDPW-004: syntactically valid storage rewrite cannot acknowledge different input |
| `masterActionMustMatchTheRetainedInstallationNotOnlyScopeAndDevice` | FDPW-004/007: M installation remains part of the original action binding |

## Unverified remainder and non-claims

- No actual native factory/key/signing, official Kit identity/custody, native
  original verification, permissions/current M authority, SQLite/protected history,
  OS force, actual process restart or installed document/lifecycle adapter runs.
  Source-confirmed outcomes here are scripted port DATA, not genuine authority.
- The maximum+1 C buffer proves the constructor length refusal only. It is not a
  valid native envelope, does not cover all inclusive I/C/P maximum recipes, and
  is never padded into a purported native challenge.
- The capacity selection uses an ordinary typed C plus an injected native refusal.
  It is **not C-LARGE**, actual 4,194,304-byte retention exhaustion or evidence that
  no signing/rollback occurred. Genuine FDPW-011 capacity behavior remains unverified.
- Factory-loss coverage deliberately stops at same-service quarantine. Recovery
  after a real restart/new service and actual checked native custody of an
  unreturned factory owner remains unverified; cleanup cannot invent closure or
  bypass the owner slot. Failed controlled retirement is explicitly observed;
  disposing scheduling resources is not native-close evidence.
- Full V1/V2 rejection and mismatch matrices, native-valid large C, generic-consent
  rejection, all missing/conflicting-original variants, target reboot/wall-clock
  changes, actual M expiry/final-boundary/boot-counter policy and genuine mismatched
  reconstructed SDK-resource cleanup remain separate required variants.
- Recorded forbidden application ports cover head approval, admission, employee
  enrollment/policy, installation/second-Master effects and unsigned continuation.
  There is no RPC/new-device authentication/business adapter in this controlled
  harness; its absence is not production integration verification. This increment
  offers no known-unsigned continuation and ends at retained-original review.

Timed retirement observation, pending handoff after phase transition and restored
legacy-work timing were source-reviewed but have no timed assertions in this
selection.

Required genuine non-device integration remains #59, installed Windows/macOS/
Android/iPhone/iPad campaigns #63, and owner acceptance #61. Report actual source
revision, selected methods, outcomes and limitations after execution; compilation
or passing these bounded selections cannot establish whole-case PASS, complete
#49, local readiness or owner acceptance. Central work remains held.
