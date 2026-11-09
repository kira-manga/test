# Captured invoice matching shared UI support

Fourteen supporting common UI methods are wired for #21/#22. They preserve the
expectations in [CCM v1.0.1](../../../../../../../../../../../../../qa/pos/designs/supplier-captured-claim-matching-v1/README.md),
its [cases](../../../../../../../../../../../../../qa/pos/designs/supplier-captured-claim-matching-v1/cases.md),
the separate [binding v1.0.0](../../../../../../../../../../../../../qa/pos/designs/supplier-captured-claim-matching-binding-v1/README.md)
and its [cases](../../../../../../../../../../../../../qa/pos/designs/supplier-captured-claim-matching-binding-v1/cases.md).
The [public captured-invoice contract](../../../../../../../../../../../../../qa/pos/contracts/captured-invoice-matching.md)
binds the restricted combined-action workspace. No frozen case, fixture or outcome
is modified.

**Execution: all 14 methods passed on JVM on 5 November 2026**, with no failures,
errors or skipped methods, on the matching source accompanying this record.
The focused selection is
`:ui:app:jvmTest --tests 'com.ukkera.pos.app.independent.capturedinvoicematchingv1.*'`.
These are supporting observations, not complete CCM cases or native acceptance.

The harness uses the actual common `LocalWorkflowController`, `UkkeraRuntime`,
`AppNavigator`, workflow selectors and invoice UI validators. Its
`LocalWorkflowGateway` and `BranchUiSession` callbacks are explicitly controlled
test ports. Scalar form version1 is the UI representation for
`SUPPLIER_INVOICE_MATCH_RECOGNIZE`; the business command remains the separately
bound version2 action. Fixture IDs and revision inputs are synthetic UI data,
not claims of authoritative source provisioning or observed storage revisions.

[CapturedInvoiceMatchingUiValidatorsSupportingTest.kt](CapturedInvoiceMatchingUiValidatorsSupportingTest.kt)
binds these structural portions:

| Frozen case portions | Method | Bounded assertion |
| --- | --- | --- |
| CCM-05/11; public restricted profile | `ccm05Ccm11InvoiceAndPoSelectionsRemainExactRoots` | Root invoice and PO selectors stay distinct from nested/revised coordinates and excluded sections. |
| CCM-05/08/11; binding combined action and source selection | `ccm05Ccm08Ccm11FormRejectsForeignChoicesDiscoveryAndOtherActions` | Only the matching action's version1 scalar form and invoice-local line choices are accepted; foreign references, discovery, line groups, capture and legacy/configuration actions are refused. |
| CCM-05/10/11; public scalar input and retained-original boundary | `ccm05Ccm10Ccm11DraftAndRetainedInputRejectForeignLineAndLineGroups` | Editable and retained UI inputs cannot add a foreign invoice line or line-group shape. |
| CCM-05/10/11; public exact selections and original-input boundary | `ccm05Ccm10Ccm11FrozenIntentRejectsForeignReferencesAndLineGroups` | Frozen UI intent retains a root invoice and invoice-local scalar line selections; foreign/absent selectors, incompatible versions/actions and line groups stay unavailable. |

[CapturedInvoiceMatchingUiProfileSupportingTest.kt](CapturedInvoiceMatchingUiProfileSupportingTest.kt)
binds these controller and navigation portions:

| Frozen case portions | Method | Bounded assertion |
| --- | --- | --- |
| CCM-08/11; public restricted profile | `ccm08Ccm11MatchingRoutesAndWorkflowRemainRestricted` | Actual navigation and workflow selection retain purchasing/status presentation; capture and matching remain distinct modes. |
| CCM-08/11; public invoice/PO read scope | `ccm08Ccm11ControllerReadsOnlyInvoiceAndPoSections` | Only invoice and read-only PO selectors generate reads; other workflows/sections cannot stage or submit. |
| CCM-08/11; public excluded controls | `ccm08Ccm11ExcludedPagesNeverOpenOrStage` | Controlled responses containing capture, legacy matching, invoice creation or supplier configuration cannot become usable pages/forms; documentary/physical/discovery controls remain inactive. |
| CCM-03/11; F-QUALITY and binding presentation | `ccm03Ccm11ReadOnlyOverlayPreservesRawClaimAndRecognizedProgress` | The supplied raw24/EGP240 capture remains `UNMATCHED_UNAPPROVED`; a separate nonselectable overlay retains recognized22/EGP220, unresolved2/EGP20, payments0, due220 and settlement-ineligible presentation. |
| CCM-11; public invoice-local form boundary | `ccm11ChangingAndClearingInvoiceRetiresPageBeforeReread` | Changing or clearing the invoice retires its old page/form before a delayed read; no stale form can stage during that read. |
| CCM-10/11; incompatible retained-original boundary | `ccm10Ccm11MixedPendingOriginalsRemainUnavailable` | A mixed matching/capture pending response stays explicitly unavailable; it cannot become a successful empty scan or dispatch/acknowledge either original. |
| CCM-08/10/11; incompatible retained-original boundary | `ccm08Ccm10Ccm11IncompatibleOriginalSurvivesDeactivationAndRetirement` | Other actions/workflows, unsupported original input and retained physical data stay inert with their original identity/data across UI deactivation and retirement. |
| CCM-10/11; F-QUALITY and binding exact recovery | `ccm10Ccm11RapidStageAndLostReplyKeepSingleOriginalForLookup` | Duplicate UI input stages/submits once; lost reply preserves the same original and frozen22-unit selection; explicit lookup presents the controlled original matching result without automatic acknowledgement. |
| CCM-10/11; unchanged original input | `ccm10Ccm11UncertainStageRetriesExactFrozenInput` | A mismatched uncertain staging reply cannot redirect recovery; explicit staging retry uses the identical original UI input/ID and submits nothing. |
| CCM-08/10/11; known access-loss cleanup | `ccm08Ccm10Ccm11KnownDenialRetiresDetailsAndKeepsOriginal` | A controlled denial clears private read/form/reference state while retaining the original operation/input; no replacement or automatic recovery is dispatched. |

[CapturedInvoiceMatchingUiFixtures.kt](CapturedInvoiceMatchingUiFixtures.kt)
supplies the controlled ports and the unchanged F-QUALITY numeric values. The
overlay assertions check acceptance, preservation and selection behavior of
supplied UI data. They do not independently produce or decode the recognized
amounts, enforce cost redaction, inspect a ledger or prove a financial effect.
No frozen PDF is captured, reopened or compared by this harness.

These are supporting L1/controller-state observations. Required real non-device
L2/L3/L5 and applicable L4 execution remains outstanding. There is no Master
permission/ceiling enforcement, production gateway, SQLite/Room, durable client
journal, admitted RPC, process restart, native file/lifecycle, rendered
Arabic/English layout or owner acceptance result here. The exact original-source
refusal codes, units/conversion history, allocation races, financial atomicity,
duplicate invoice guard and settlement policy remain with their separately
required checks. No whole CCM case or purchasing workflow is passed by this
package; the variance/policy and native prerequisites in the frozen designs remain
unchanged.
