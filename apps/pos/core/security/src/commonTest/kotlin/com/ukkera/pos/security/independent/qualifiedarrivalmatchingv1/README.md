# Qualified arrival matching v1 — shared public unit slices

**NOT_RUN.** The 26 methods wire the five unchanged cases in
`qa/pos/freezes/qualified-arrival-matching-v1/{cases.md,fixtures.json}`. They
exercise `PurchasingArrivalRuleConstraints.compare` with synthetic public values
and authentic outputs of `AuthorizationEvaluator` and `ActionApprovals`.
No signed publication, installed policy, original-byte custody, native history or
protected arrival transaction is established by these fixtures.

## Exact fixture bindings

All IDs use the canonical synthetic UUID prefix `40000000-0000-4000-8000-` and
the listed ordinal zero-padded to twelve digits. These are semantic alias
bindings, never provisioned accounts, suppliers, products or approvals.

| Frozen alias | Ordinal / public binding |
| --- | --- |
| SYN-COMPANY-A / SYN-BRANCH-A | 2 / 3; `BranchScope` company/branch components |
| SYN-COMPANY-B / SYN-BRANCH-B | 4 / 5; changed requirement/documentary scope inputs |
| SYN-REQUESTER-R | 10; employee-policy revision 11 |
| SYN-APPROVER-A | 11; employee-policy revision 12 |
| SYN-REQUESTER-X | 12 |
| SYN-PUR-POLICY | 30; policy ID only; separate policy revision 6 is unbound |
| SYN-PUR-PUBLICATION | 31; sole public signed-publication revision 8 |
| SYN-SUPPLIER-A / SYN-SUPPLIER-B | 32 / 33; supplier revision 3 |
| SYN-CATALOG-RELEASE-7 | 35; release publication ID and revision 7 |
| Changed catalog release ID | 36 |
| SYN-PRODUCT-RICE / SYN-PRODUCT-OIL | 40 / 41; product revisions 4 / 5 |
| SYN-PRODUCT-OTHER | 42 |
| RULE-DIRECT / RULE-SEPARATE / RULE-NAMED-SELF | 50 / 51 / 52 |
| RULE-UNKNOWN / other eligible rule | 53 / 54 |
| RET-PUR-ORIGINAL / RET-OTHER | 60 / 61; selected revisions 2 / 1 |
| DOC-J / DOC-P | 70 / 71; storage revision 1 |
| SYN-ORIGINAL-P2 / changed original ID | 72 / 73 |
| SYN-ARRIVAL-001 / SYN-ARRIVAL-002 | 80 / 81; `OperationId` |
| SYN-ARRIVAL-DRAFT-001 / changed target ID | 82 / 83; target revision 3 |
| Actual separate / actual self / second separate approval IDs | 90 / 91 / 92 |

Support organization ID is 1; device/boot IDs are 20/21. The public SKU model
also requires SKU IDs/revisions: 43/4 and 44/5 explicitly accompany rice/oil;
45 is a changed SKU ID and 46 a category-only selector. These support coordinates
do not infer SKU names or category membership. Delegation grant ID is 55.
Root/predecessor/author/delegation support IDs are 100–105. Their fixed digest
syntax and bodies are untrusted; no signature, enrollment or delegation is
authenticated. The independently supplied validity interval is explicitly
`2026-11-01T00:00:00Z` to `2026-11-02T00:00:00Z`; it is syntactic fixture data,
not a qualified current-time or expiry assertion. Retention minimum 0 and no
obligations are explicit support body values, not a legal duration or default.

DOC-J and DOC-P retain the freeze's exact 155/640 byte lengths, JPEG/PDF media,
`SUPPLIER_DELIVERY` purpose and the unchanged SHA-256 values from its documentary
v2 fixture references. No original file is loaded or newly captured. Selected
and resolved manifest lists are materialized separately and tested in order.
Matched invalid-selector variants materialize their named change separately in
both lists; resolution mismatch variants change only the resolved metadata.
A selected-rule variation updates both required representations of that same
synthetic rule, so tests can exercise its constraints without substituting a body
outside the publication. Separate tests reject a substituted or merged body.

## Actual public proof provisioning

The frozen `UNPRICED_ARRIVAL` alias binds to the current cost-free command's public
`INVENTORY_ADJUST/PERFORM` requirement with `LimitUse.NotApplicable`, unrestricted
ownership and reason code `purchasing.arrival.qualified`. A deterministic unit
normalization includes scope, original requester, explicitly selected rule,
supplier/revision, both exact product/SKU/release coordinates, ordered selected
manifest fields and selected retention class/revision. Target ID/revision and
operation ID bind separately in `ActionBinding`. This normalization is not the
production arrival wire codec. The independently expected requirement is
materialized from those inputs separately from the proof's requirement; it is
never copied from a proof. Negative requirement variants deliberately provide
otherwise public-authorized incompatible requirements, not arrival authority.

A controlled `PrincipalVerifier` supplies explicit `VerifiedLocalIdentity` facts
through `TrustedPrincipalFactory`. For actor ordinal `n`, interactive session and
connection IDs use suffixes `1000+n` and `2000+n`; privileged approving IDs use
`1100+n` and `2100+n`. Generation/admission revision are 1. Explicit snapshots
provide enabled account, active session, admitted device and a matching grant
at requester policy 11 or actual approver policy 12. They are not installed
policies or real authentication/admission. Named equality uses a separate
action-scoped `SelfApprovalPolicy` at support revision 1, never policy revision 6.

Direct proofs come from the real evaluator and retain null approval coordinates.
Separate and NamedSelf proofs use freshly evaluated REQUEST/APPROVE permits,
public `ActionApprovals.issue`, a fresh REQUEST evaluation and public
`ActionApprovals.consume` with independently supplied approver facts. The actual
returned PERFORM proof/issued record/consumed record are retained unchanged.
The monotonic boot-21 inputs are issue 0 and consume 1, with an explicitly supplied
1000 ms lifetime. The late input 1001 exercises that public record's refusal;
it establishes no production clock, deadline or durable approval state.

## Bounded coverage and limits

The source specifies 278 matcher invocations, including identical repeats:
35 expected `NO_MISMATCH` and 243 expected `MISMATCH`. These are planned counts,
not execution results. Each invocation checks the exact semantic result, unchanged
serialized policy and supplied input/proof fields, and unchanged fixture public
proof-provisioning counts. No authority/storage/RPC adapter is supplied to the
matcher. Fixture counts do not measure effects of an unexecuted production action.

| Frozen case | Shared binding |
| --- | --- |
| QAM-01 | Explicit choice/full body; no fallback/merging; exact exposed policy/publication/branch/action, supplier and both products/releases; category-only refusal. |
| QAM-02 | Direct null tuple before/after approvals; actual R/A and R/R; NamedSelf distinct route; explicit equality exception; exact original requester/requester revision and independently expected requirement. |
| QAM-03 | Optional 0/1/2 and required 1/2; required 0 refusal; overflow/repeated ID; every exposed selector and exact resolved metadata/order; incomplete positive length/storage revision. |
| QAM-04 | Exact selected class/revision; absence/change and another rule's class refuse; no inferred duration. |
| QAM-05 | Stale exposed revisions, foreign bound scopes, other operation/revision/content, repeatability, and agreement alongside actual public refusals using later controlled facts. |

Public construction excludes partial approval-coordinate tuples, absent typed
IDs/digests, incomplete required rule fields and an empty NamedSelf named list.
Genuine consumed all-present and evaluator all-absent tuples cover the available
route variations. A Separate route covers absence of an equality exception.
Missing/incomplete selection/body/retention objects, zero storage revision and
zero byte length cover representable omissions. No accepting capability is
constructed directly, modified by reflection or fabricated to reach an impossible
public output. Missing operation ID is bound by a genuine direct evaluator output;
public approval issuance cannot produce an approval for that requirement.

The following remain **unbound / NOT_RUN**, with frozen refusal expectations
preserved: separate policy revision 6; separate `SYN-CATALOG-A` catalog ID;
supplier/SKU/catalog company or branch provenance; document supplier revision at
capture; retention policy ID/revision/company/branch provenance and installation;
whole-company provenance across every source. A bounded requirement/documentary
scope comparison does not close these whole-action gates. Independently current
approver-policy comparison, authenticated/current publication and source loading,
complete normalized operation/source joins, exact bytes/history, expiry/session/
device sources, real transaction/approval consumption, immutable qualification/
retention use, original-result retry/concurrency/restart/RPC/native persistence,
historical readers, and all supported platform/runtime variants remain separate
required lanes. Public supplied-fact refusals do not mark those lanes PASS.

Focused JVM selector, relative to the POS build:

```text
:core:security:jvmTest
--tests com.ukkera.pos.security.independent.qualifiedarrivalmatchingv1.QualifiedArrivalMatchingV1FrozenUnitSlicesTest
```
