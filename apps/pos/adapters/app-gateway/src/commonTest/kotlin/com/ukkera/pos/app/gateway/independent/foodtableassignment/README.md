# FTA-v1 gateway supporting bindings

Specification: `qa/pos/designs/food-table-assignment/{README.md,cases.md,fixtures.json}`.
The frozen expectations are unchanged. The twelve supporting JVM methods **passed**;
[revision-bound outcomes](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/food-table-assignment-20261103/README.md).
All whole FTA-001–FTA-040 cases remain **NOT RUN**; supporting passes do not change
that status.

## Actual boundary and inputs

`FoodTableAssignmentFrozenGatewaySlicesTest` invokes the actual
`LocalEndpointWorkflowGateway`, table-assignment/directory mapping, saved-intent
parser/validation and UI input-continuation/rebind code. Session views, lookup/read/
execute replies and client journal custody are controlled **DATA** ports. These are
L0/L1 supporting observations, not real L2/L3/L5 persistence, Master authorization,
admitted P2pKit, process restart, protected native custody or installed-app evidence.

`FrozenFoodTableAssignmentGatewayInputs` binds the exact frozen IDs, command
coordinates, directory rows and result semantics. Its primary move is order 12 /
binding 1 / upper 5 / B 11; the original result is B / binding 2 / order 12.
Request mapping uses `LocalFoodTableAssignmentRequestV1`,
`LocalFoodTableAssignmentCodecV1`, `LocalAction.FOOD_ASSIGN_TABLE`, form version 1
and the seven explicit ID/revision fields. The observation uses
`LocalFoodTableAssignmentObservationV1`; table detail supplies its current parent
and produces the actual form descriptor. This maps supplied API shapes, not an
independent wire format oracle. Serialized DTOs and digests are input/custody
material only; exact code spelling, error precedence, wording and golden bytes
are not asserted.

`LookupReply.AbsentAtCurrentWriter` appears only as explicitly scripted
**GATEWAY_ONLY_BRANCH_DATA** to reach the execute-reply branch. It establishes no
native absence, current writer, admission, grant, source history or Master commit.
Failed/deferred/mismatched real observations are never converted into that sentinel.
The journal has no assignment-specific result validator: typed validation must run
in the actual gateway before any controlled result write or acknowledgement.
`DURABLE` port answers, unique-retention counters and idempotent acknowledgement
counters do not certify native storage or a network ACK protocol.

## Per-method frozen mapping

| Method | Frozen case / exact supporting variant | Actual layer and remaining assertions |
| --- | --- | --- |
| `fta002003004006StageRetainsSelectedIdsAllFourExpectationsAndDoesNotDispatch` | FTA-002 restaurant first request 12/null/main 3/A 7; FTA-003 12/1/main 3/C 13; FTA-004 primary request 12/1/upper 5/B 11; FTA-006 12/1/main 3/A 7, each with its frozen operation ID | L1 actual staging/codec/custody boundary. No execute or result write; exact selected input survives. Existing FOOD bodies, café scope, actual bindings/producers, atomic success and numeric C0 are not bound. |
| `fta010038UnsupportedFormsNonScalarShapesAndLabelInsteadOfTableIdDoNotStage` | FTA-010 literal `Table 07` cannot fill the typed table-ID scalar; FTA-038 version-1 restriction, with unsupported 2/unknown integer and scalar-only API-shape probes | L1 actual pre-stage mapping. No staged input or dispatch. The API probes are supporting shape checks, not extra frozen cases. Wrong-kind/missing native destination proof, exact ingress errors and persisted form bytes remain unbound. |
| `fta001ReadMappingDistinguishesSuppliedAbsentAndAssignedBindingAndKeepsCurrentParent` | FTA-001 supplied absent versus supplied A/1 observations, order 12; main 3/upper 5/retired 2 directory, upper 5/B 11 detail; optional captured binding revision | L1 actual read/page/form mapping. No operation/custody write or dispatch; no bill records in these configuration pages. Genuine protected absence, real existing A/1, native current directory/history, browsing/navigation and C0 remain unbound. |
| `fta024FailedTruncatedMismatchedAndRetiredSessionReadsNeverPublishAnAbsentPage` | FTA-024 denied/unavailable/truncated read, mismatched order and credential lifetime retired while awaiting supplied response | L1 actual gateway failure/lifetime boundary. No available unassigned page and no dispatch. Controller stale-callback/page retirement, actual F-ASSIGNED re-read and native session revocation remain unbound. |
| `fta025031SubmitUsesRetainedOriginalDespiteEditorReplacementAndExactRetryDoesNotRedispatch` | FTA-025 and FTA-031, each original operation ID; retained B input 12/1/5/11 versus C editor replacement; original B/2 reply and duplicate found result | L1 actual stage/submit/recovery with synthetic execute-branch DATA. Dispatched input is the original, duplicate recovery does not redispatch. Native exactly-once commit, UI Back/leave/recomposition/touch schedules and all C0 remain unbound. |
| `fta026AlteredSemanticInputsAndOtherAccountCannotReplaceRetainedOriginal` | FTA-026 C/main destination, changed floor/table/order/binding expectations, absent binding expectation, other order and account B; original B/2 retained | L1 actual input identity/recovery and journal-owner check. Altered input cannot replace original custody. Actual Master identity conflicts/authorization, persistence reopen and financial/stock/preparation state remain unbound. |
| `fta027028DeferredLookupKeepsOriginalUntilFoundRecoveryAfterGatewayReconstruction` | FTA-027 and FTA-028, each original ID and 12/1/5/11 request; deferred then original B/2 found result | L1 actual unknown/recovery path and new gateway object over the same controlled custody. No resubmission from deferred lookup. Lost LAN delivery, in-flight native races, real Master restart and uncertain collection conservation remain unbound. |
| `fta037ConflictingSameOperationResultsFailBeforeRetentionAcrossGatewayEntryPoints` | FTA-037 six directly representable variants: other order; main/C IDs; observed order 13; floor 6; table 12; result binding 3. Each runs through stage recovery, submit-found, lookup, pending and execute-reply | L1 actual typed result correlation before write/publication, plus ACK refusal without accepted result and reconstructed gateway retry. Original request stays exact; result-write/accepted/ACK counters stay zero. The seventh predecessor variant, real original-operation/native result retention and authoritative B/2/C0 remain unbound. |
| `fta038SavedFormVersionOneRestoresExactInputAndAllUnsupportedVersionsRefuseWithoutDispatch` | FTA-038 OP-FTA-038 and original 12/1/5/11; saved form version 1; unsupported 0, 2, unknown integer, string `unknown`, null and missing version | L0 actual journal JSON parser and `StoredClientIntentV1.validate`. Original survives and no lookup/read/execute occurs. Exact saved bytes, native storage/parser ingress, installed editor restoration and preservation across physical process failure remain unbound. |
| `fta038039UnchangedFormRebindRetainsSelectedIdentityAndPinsButClearsConfirmation` | FTA-038 unchanged form version 1 versus 2; FTA-039 captured 12/1/5/11 and selected order/floor/table identities | L1 actual input-continuation/rebind code on the actual mapped form. Pins/selected identities survive; confirmation does not. Fresh protected source acquisition, durable editor/navigation storage and pending-operation identity are separate unbound obligations. |
| `fta039EveryUnequalRevisionAndBothAbsentAssignedDirectionsRefuseRebindWithoutSubstitution` | FTA-039 current order 13, floor 6, table 12, binding 2 or absent against captured 12/1/5/11; captured absent versus current binding 1 | L1 actual mapped form and equality-only rebind. Every mismatch refuses without replacing captured values or dispatch. Current revisions are supplied DATA, not legitimately executed edit/configuration/binding history; native C0 and controller schedules remain unbound. |
| `fta040ConflictSurvivesAckAttemptsReconstructionAndAccountSwitchThenOriginalRetiresOnce` | FTA-040 result-binding-3 conflict, unresolved original 12/1/5/11, account B, exact original B/2, conflicting ACK lookup, then logical ACK/retirement and late conflicting/duplicate replies | L1 actual validation/retention/ACK/recovery boundary. Before valid result: zero accepted writes/ACKs. Afterwards: one unique original retention and one logical retirement; no reopen or execute. Native durable journal, authorized cross-session recovery, transport ACKs, installed private-state clearing and authoritative B/2/C0 remain unbound. |

The result DTO repeats observed order revision in both top-level result and
binding. The `different_observed_order_revision` input therefore changes both
representations to 13, preserving that single frozen semantic conflict.
FTA-037's `different_previous_assignment_revision` cannot be represented
distinctly: this result DTO has no predecessor-expectation member. Result revision
3 is tested once, as its own frozen variant; it is not counted as predecessor
coverage.

## Remaining qualification

U-ERROR/U-WIRE remain unbound. U-API is mapped only at the listed supplied API
seams; predecessor result data and exact native saved-form ingress remain gaps.
Primary null service-reference/seat-note representation is not consumed by these
gateway request/result slices and is not repaired or qualified by them.
U-HISTORY/U-RUNTIME/U-OS, positive production association/history/BEFORE traversal,
native compare-and-swap/revocation/fault/restore schedules, full numeric C0 and
FTA-029/030/032–036 real boundaries remain unbound here. The exact core refusal
candidate shape has no independently frozen oracle; no candidate-shape test is
invented.

Required genuine non-device integration remains #56/#59. Installed/device checks
remain #63; owner acceptance remains #61. None of those requirements is waived
or moved to a passing supporting-method status.
