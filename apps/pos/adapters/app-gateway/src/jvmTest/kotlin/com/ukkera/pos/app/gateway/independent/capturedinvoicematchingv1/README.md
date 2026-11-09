# Captured invoice matching: supporting gateway harness

**Execution: 38 selected JVM methods passed on 5 November 2026**, with no failures,
errors or skips: four actual encrypted-client-journal checks, the 23 supporting
methods below and 11 existing supplier-match display checks. These are not
completed CCM cases or genuine Master/RPC integration evidence.

## Unchanged expectations

- `qa/pos/designs/supplier-captured-claim-matching-v1`, version **1.0.1**.
- `qa/pos/designs/supplier-captured-claim-matching-binding-v1`, version **1.0.0**.
- F-QUALITY keeps the immutable claim of two original cartons at historical
  12 each/carton: 24 each / EGP240 (`24000` minor). The exact selected accepted
  slice is 22 each / EGP220 (`22000`); 2 each / EGP20 (`2000`) stays unresolved.
  Purchase tax, discounts, allocated payment and credit remain zero. Matching
  does not change the original, receive stock, approve a variance or pay money.
- The command is `local.purchasing.invoice.match-recognize` v2, with the distinct
  combined PERFORM permission. Form version1 is not command version2.

The deterministic UUIDs, revisions and source-present callbacks are inert wiring;
they are not records obtained from actual authoritative producers. Optional first
progress, receipt-billing and payable heads stay explicitly absent rather than0.

## Actual harness boundary

`CapturedInvoiceMatchingGatewayFixture.kt` uses the production shared gateway,
public purchasing codecs and projection validators over a bounded scripted
`LocalMasterClient`. It reuses the existing memory-only
`ControlledInvoiceCaptureJournal`; its DURABLE outcomes and serialized roundtrips
do not establish real durable storage, native reopen, restart or restore.

The endpoint does not enforce Master permissions, qualify original bytes, create
actual accepted/rejected receipts, serialize allocation/recognition commits or
perform admitted RPC. Scripted cost redaction is not the required protected
three-grant read boundary. The registry deliberately omits the original-metadata
editor read, so projection checks do not qualify the matching form, CMP controller,
screen interaction or native file flow. The additional matching-profile methods
test real profile validators; their PO and exact-ID metadata checks do not execute
those reads or establish admitted RPC.

## Method-to-case mapping

All 23 methods passed. Their boundaries remain supporting, not whole-case qualification.

### `CapturedInvoiceMatchingGatewaySupportingTest` — 8 methods

| Method | Frozen case contribution | Bounded assertion |
| --- | --- | --- |
| `ccm03And09StagingPinsTheExactQualitySourcesAndExplicitFirstHeadAbsence` | CCM-03/09 | Actual gateway stages exact claim/original/PO/allocation/receipt IDs and observed heads, preserves absent optional heads, shows raw240/selected220 and dispatches nothing. |
| `ccm09JournalCodecRoundTripKeepsTheExactCombinedCommandAndOriginalActor` | CCM-09/10 | Exact serialized command/form/owner/digests survive a memory-journal codec roundtrip. |
| `ccm08CombinedGrantAbsenceBlocksFreshPreparationWithoutSourceReads` | CCM-08 | Conservative current gateway hint refuses fresh preparation without the combined grant. |
| `ccm08And09CurrentCombinedGrantLossBlocksNewDispatchButKeepsTheOriginal` | CCM-08/09 | Current hint loss denies dispatch after original-identity lookup without replacing the staged body. |
| `ccm08InvoiceCaptureOnlyProfileRejectsFreshMatchingWithoutJournalOrSourceWork` | CCM-08 | Capture-only installed profile cannot stage this combined command despite scripted grants. |
| `ccm03And11QualityProjectionSeparatesRawRecognizedAndUnresolvedMoneyWithCostsRedacted` | CCM-03/11 | Actual projection preserves raw24/240, matched22/220, unmatched2/20, unpaid220 and the unchanged capture; redacted costs do not erase quantities/progress. |
| `ccm11DirectoryProjectionDoesNotInventClaimOrRecognitionDetailLines` | CCM-11 | Bounded summary omits both original and recognition detail lines while retaining original/current statuses. |
| `ccm10And11OverlayValidationRejectsMissingDetailAndFalsePaidOrAllocationBalances` | CCM-10/11 | Missing recognition detail, erased unresolved amount, false payment, inconsistent allocation balance or partial-as-settleable overlay fails closed. |

### `CapturedInvoiceMatchingRecoverySupportingTest` — 8 methods

| Method | Frozen case contribution | Bounded assertion |
| --- | --- | --- |
| `ccm07And10RepeatedSubmissionKeepsOneControlledResultAndNoNewPreparation` | CCM-07/10 | Repeated submit/lookup retains one exact original callback result without new reads or execution. |
| `ccm10LostReplyAndEntryCodecRoundTripRecoverTheExactOriginalQualityResult` | CCM-10 | Controlled lost reply plus recreated gateway/memory journal recovers original22/220, raw240, actor/body/heads without rebuilding. |
| `ccm10ChangedSameOperationQuantityCannotReplaceTheUnresolvedOriginal` | CCM-10 | Deliberately changed21 under the same operation ID cannot replace original22. |
| `ccm08And10DifferentActorCannotAdoptOrRecoverThisClientsOriginal` | CCM-08/10 | Denied session or other actor cannot adopt the original journal owner or read through this original-client route. |
| `ccm10And11PresentationWaitsForResultRetention` | CCM-10/11 | A controlled retention barrier withholds the completed presentation while the exact result is unretained. |
| `ccm10ResultRetentionFailureLeavesTheOriginalUnpresentedAndUnacknowledged` | CCM-10 | NO_CAPACITY/IDENTITY_CONFLICT/UNAVAILABLE receipt outcomes leave the original outstanding; a later original lookup retains the same result before ACK. |
| `ccm10MismatchedOrCorruptMatchResultsCannotBeRetainedPresentedOrAcknowledged` | CCM-10 | Wrong match/invoice IDs, invoice/event/progress/payable revisions, quantity or truncated bytes are not accepted as the original result. |
| `ccm08And10InvoiceCaptureOnlyProfileCannotReactivateRetainedMatching` | CCM-08/10 | Capture-only stage/submit/lookup/pending/ACK cannot reactivate, replace or hide retained matching work. |

### `CapturedInvoiceMatchingProfileSupportingTest` — 7 methods

| Method | Frozen case contribution | Bounded assertion |
| --- | --- | --- |
| `ccm08MatchingProfileFixesOneCombinedMutationAndFourReadKeys` | CCM-08 | Exact matching command/read membership; capture, legacy matching, configuration and physical procedures remain excluded. |
| `ccm08And11TypedReadsStayWithinInvoiceOrderSourceAndExactDocumentMetadata` | CCM-08/11 | Canonical invoice, PO, source-quote and exact metadata query selection; not execution of metadata/PO reads. |
| `ccm08And11ReadModesDoNotAdmitLegacyPayableScanBytesExportOrReferenceDirectories` | CCM-08/11 | Wrong shared-key sections, scans, bytes/export and reference discovery remain excluded. |
| `ccm08And10OnlyScalarVersionOneAndCanonicalCombinedBodiesAreCompatible` | CCM-08/10 | Exact form/body/original identity; malformed fields, attached approval and incompatible input are refused. |
| `ccm08And10CurrentCombinedHintAndOriginalActorStillBoundMatchingProfileWork` | CCM-08/10 | Conservative current UI hints and original actor binding; not Master revocation/commit ordering. |
| `ccm10And11MatchingProfileRecoversTheExactQualityOriginalWithoutNewPreparation` | CCM-10/11 | Lost controlled reply retains/retrieves the original 22/220 result and raw240 before explicit ACK. |
| `ccm08And10UnsupportedRetainedProfilesNeverBecomeEmptyPendingOrReplacementWork` | CCM-08/10 | Other saved command profiles remain retained, unavailable and undispatched, including before cursor filtering. |

## Encrypted client storage follow-up

`CapturedInvoiceMatchingEncryptedJournalTest` injects the production
`JvmEncryptedClientIntentJournal` into the same gateway. All **four methods
passed** using actual private POSIX files, AES-GCM, close/reopen and explicit
acknowledgement/archive. The earlier 23 memory-port methods also passed unchanged;
the full package selection additionally ran 11 existing display methods.

| Method | Frozen contribution | Verified client boundary |
|---|---|---|
| `ccm07And10LostReplyReopensExactOriginalAndArchivesOnlyAfterExplicitAcknowledgement` | CCM-07/10 | Lost scripted reply retains the exact original; reopen/lookup retains22/220 and raw240; unacknowledged work stays pending, explicit ACK archives it, and a second reopen retains the same result without another source read or dispatch. |
| `ccm08And10ReopenedOriginalRejectsChangedBodyAndOtherActor` | CCM-08/10 | Changed same-ID quantity or denied/different-actor session observation cannot replace/adopt the retained original; original recovery remains possible. |
| `ccm10MissingOrTruncatedActiveHistoryCannotReopenAsAnEmptyQueue` | CCM-10 | Each missing/truncated active-file variant refuses reopen, never initializes accepting empty history. |
| `ccm10MissingOrTruncatedAcknowledgedArchiveCannotReopenAsAnEmptyQueue` | CCM-10 | Each missing/truncated acknowledged-archive variant refuses reopen. |

Source review corrected call instrumentation to record rejected read/execute
attempts too, preventing a caught fixture exception from hiding unwanted work.
No frozen oracle changed. The Master endpoint, key custody and session observations
remain controlled. This is client storage integration, **not** a Master commit,
admitted RPC, actual process crash, native key proof or whole CCM case.
[Revision, command and remaining scope](../../../../../../../../../../../../../../qa/pos/traceability/captured-invoice-matching-20261105/README.md#encrypted-client-journal-recovery).

## Still required

Actual producer-backed original/claim/approved-PO/accepted-receipt setup; finite
full-original authorization and independently gated protected reads; serialized
current-state/revocation/business commits; allocation/payable conservation and
invoice-identity duplicate guard; real transaction failure and original-result
recovery; admitted RPC and native durable storage/reopen; genuine CMP interaction
and platform profiles all remain required. F-FULL, arrival, capacity and mixed-price
production outcomes are not exercised by this quality-only gateway fixture.

No statutory GL, future variance/tolerance, pre-dispute settlement, company-global
offline uniqueness or central verification result is introduced. Frozen full-case,
native and owner outcomes remain NOT RUN; central work remains held.
