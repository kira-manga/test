# FTA-RPC-v1 floor handler-membership supporting subset

Frozen source: `qa/pos/designs/food-table-assignment-rpc/{cases.md,fixtures.json,runtime-bindings.md}`.
Case mapping: **FTA-RPC-v1-S01**, fixed boundary grammar, supporting L0 only.
Status: **2 bounded JVM methods PASS**, zero failures/errors/skips. See
[the dated verification record](../../../../../../../../../../../../../../qa/pos/traceability/food-table-assignment-rpc-20261104/README.md).
No complete supporting family or whole-case result is claimed.

| Method | Frozen subset and actual API |
| --- | --- |
| `ftaRpcS01FloorTablesAndExactTableSelectorsAdmitOnlyTheAssignmentProfile` | Actual floor-read normalizer and `supportsRpcWorkflow` admit Floors, exact F1 table selectors (initial/after T20/after T21) and exact T01 only for the assignment profile. |
| `ftaRpcS01FloorHandlerRefusesFoodAndAssignmentBodiesAndMalformedDirectoryInput` | Actual support refuses Own FOOD and exact frozen A assignment bodies, truncation, injected grant, duplicate limit and invalid limit 0. No error-code expectation is invented. |

All IDs and A's 7/absent/F1 2/T01 3 come from the frozen semantic fixture.
Existing public codecs are input adapters; normalization equality is input
preservation, not independent golden-byte qualification. The existing floor
handler is invoked directly, without directory prepare/project, read view,
authentication, native source records, committed effects or protected read-back.
These tests therefore do not prove real directory contents, pagination results,
current authorization, admission/RPC or native history. They cover only this S01
handler-membership subset, not complete S01 or any whole FTA-RPC-v1/FTA-v1 case.
All required runtime bindings remain unqualified by these pure checks.

Suggested selector: `:adapters:catalog-store:jvmTest --tests '*FoodTableAssignmentRpcFrozenFloorSupportTest'`.
No dependency/API widening or frozen expectation changes are made. Genuine
non-device integration remains #59, platform runs #63 and owner acceptance #61;
central implementation and verification remain held.
