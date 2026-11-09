# FTA-RPC-v1 sales handler-membership supporting subset

Frozen source: `qa/pos/designs/food-table-assignment-rpc/{cases.md,fixtures.json,runtime-bindings.md}`.
Case mapping: **FTA-RPC-v1-S01**, fixed boundary grammar, supporting L0/controlled membership only.
Status: **4 bounded JVM methods PASS**, zero failures/errors/skips. See
[the dated verification record](../../../../../../../../../../../../../../qa/pos/traceability/food-table-assignment-rpc-20261104/README.md).
No complete supporting family or whole-case result is claimed.

| Method | Frozen subset and actual API |
| --- | --- |
| `ftaRpcS01AssignmentAndBindingReadAdmitFrozenAOnlyForTheAssignmentProfile` | Actual assignment/read normalizers and `supportsRpcWorkflow`; exact A/O1 at 7/absent/F1 2/T01 3, accepted only for the retained assignment profile. Other profile identities do not admit these bodies. |
| `ftaRpcS01FoodReadAdmitsOwnAndExactDetailButNotStationTaskOrHandoffSelectors` | Actual FOOD read support admits Own and exact O1/O2 selectors in the new profile, refuses Station/Task/Handoff selector families without recipe evaluation. |
| `ftaRpcS01NewProfileRefusesKitchenReadAndFoodCommandFamiliesBeforeRecipeEvaluation` | Actual version-1/version-2 check-kitchen handlers and FOOD Send command handler refuse the assignment profile before source evaluation. Send uses frozen O1 revision 7 and its two portion IDs as inert prohibited-family input. |
| `ftaRpcS01AssignmentAndBindingReadRefuseBodySwapsAndMalformedInjectedBodies` | Actual assignment/read support refuses swapped body kinds, Own bodies, truncation, injected grant/profile fields and duplicate order fields. Typed input failure or unsupported membership is refusal; no undocumented error-code oracle is added. |

Existing public codecs adapt the independent semantic inputs to existing API bytes.
Normalization equality checks preservation of that supplied input; it is not an
independently preserved golden-byte/digest result. The old FTA helper file
`independent/foodtableassignment/FrozenFoodTableAssignmentSalesInputs.kt` is reused
only for actual handler constructors and its counted directory port with
unavailable retained originals.
Its older 12/3/7 command, labels, source coordinates and directory DATA are not used
as the new fixture. The directory must have zero calls. Recipe/history ports fail
if called and supply no authority or accepting native proof. Frozen UUIDs in
negative Station/Task selectors are syntactically valid inert coordinates; no
station/task existence or preparation source is asserted.

These checks do not call prepare/evaluate/project, commit, disclose protected rows,
look up an original or provide current authentication/native grants. They cover
only the stated S01 handler-membership subset, not all S01 malformed/prohibited/
permission/procedure variants. RB01 exact golden bytes and actual retained
procedure boundary, RB03 current Master authority and all required native/durable
bindings remain separate. Zero business effects/disclosure at a committed cut
and whole FTA-RPC-v1-W01–W12 or existing FTA-v1 cases are not qualified here.

Suggested selector: `:adapters:sales-store:jvmTest --tests '*FoodTableAssignmentRpcFrozenSalesSupportTest'`.
No frozen files, original expectations, production APIs or dependency configuration
were changed. Genuine non-device integration remains #59; installed/platform runs
remain #63 and owner acceptance #61. Central work remains held.
