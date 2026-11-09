# Approval provenance v1 — shared public unit slices

**Bounded execution: 17 methods passed on 1 November 2026**, zero
failures/errors/skips. The methods wire unchanged frozen v1.0 expectations from
`qa/pos/freezes/approval-provenance-v1/{README.md,cases.md}` and exercise the real
public `AuthorizationEvaluator` and `ActionApprovals` with controlled U-layer
identity/current-policy inputs. Complete APV cases remain NOT_RUN.
[Executed checks and limits](../../../../../../../../../../../../../qa/pos/traceability/approval-documentary-retry-20261101/README.md)
are recorded separately.

## Exact U fixture bindings

The freeze's tuples are business aliases, not a wire format. Their canonical UUID
bindings below are synthetic test inputs, never real provisioned accounts, grants,
devices, approval records or goods receipts. Scope always retains organization,
company and branch; RB/RC retain their specified different account scopes.

| Frozen alias | Exact canonical UUID |
| --- | --- |
| ORG-PROV-001 | `30000000-0000-4000-8000-000000000001` |
| COMP-PROV-001 | `30000000-0000-4000-8000-000000000002` |
| BR-PROV-001 | `30000000-0000-4000-8000-000000000003` |
| COMP-PROV-002 | `30000000-0000-4000-8000-000000000004` |
| BR-PROV-002 | `30000000-0000-4000-8000-000000000005` |
| BR-PROV-003 | `30000000-0000-4000-8000-000000000006` |
| R / EMP-PROV-REQ-001 | `30000000-0000-4000-8000-000000000010` |
| M / EMP-PROV-APPROVER-001 | `30000000-0000-4000-8000-000000000011` |
| N / EMP-PROV-APPROVER-002 | `30000000-0000-4000-8000-000000000012` |
| D / EMP-PROV-DIRECT-001 | `30000000-0000-4000-8000-000000000013` |
| S / EMP-PROV-SOLE-001 | `30000000-0000-4000-8000-000000000014` |
| RB / EMP-PROV-REQ-B2-001 | `30000000-0000-4000-8000-000000000015` |
| RC / EMP-PROV-REQ-C2-001 | `30000000-0000-4000-8000-000000000016` |
| DEVICE-PROV-001 | `30000000-0000-4000-8000-000000000020` |
| DEVICE-PROV-002 | `30000000-0000-4000-8000-000000000021` |
| GR-PROV-001 | `30000000-0000-4000-8000-000000000040` |
| GR-PROV-002 | `30000000-0000-4000-8000-000000000041` |
| GR-PROV-DIRECT-001 | `30000000-0000-4000-8000-000000000042` |
| GR-PROV-SELF-001 | `30000000-0000-4000-8000-000000000043` |
| GR-LINE-PROV-001 | `30000000-0000-4000-8000-000000000044` |
| GR-LINE-PROV-002 | `30000000-0000-4000-8000-000000000045` |
| APP-PROV-001 | `30000000-0000-4000-8000-000000000060` |
| APP-PROV-002 | `30000000-0000-4000-8000-000000000061` |
| APP-PROV-SELF-001 | `30000000-0000-4000-8000-000000000062` |
| OP-PROV-001 | `30000000-0000-4000-8000-000000000100` |
| OP-PROV-002 | `30000000-0000-4000-8000-000000000101` |
| OP-PROV-003 | `30000000-0000-4000-8000-000000000102` |
| OP-PROV-DIRECT-001 | `30000000-0000-4000-8000-000000000103` |
| OP-PROV-SELF-001 | `30000000-0000-4000-8000-000000000104` |

Support boot UUID is `30000000-0000-4000-8000-000000000022`. For an account's table
suffix `n` (10–16), synthetic interactive session/connection suffixes are
`1000+n` / `2000+n`; privileged-approval suffixes are `1100+n` / `2100+n`, each
zero-padded to twelve digits under the same `30000000-0000-4000-8000-` prefix.
All supplied generations/admission revisions are 1. A controlled verifier feeds
these inputs through `TrustedPrincipalFactory`; it does not authenticate a real
session or transport. Device/status fields are explicit snapshot inputs for
identity equality, not evidence of actual device-to-branch admission. DEVICE-002
is bound but no real moved-device schedule is performed.

Baseline activated policy is 10. R/M request, M/N approve, D performs, and S
requests/approves with the explicit active S/action exception. Foreign account
request grants are scoped to their own fixture branches. These controlled grants
use an explicit EGP monetary-ceiling absence, so changed 201.00 input is not
incidentally rejected by a new amount ceiling. They are not installed policies.
The frozen **post a local goods receipt** behavior alias maps to public
`PURCHASING_APPROVE` for this harness; it does not implement or execute a goods
receipt. The changed-action binding is `SUPPLIER_CASH_SETTLE` and grants no supplier
cash-settlement execution. Absent and explicitly inactive S exceptions both map
to absence from the supplied active exception set; no stored inactive-policy
history is manufactured.

200.00/201.00 EGP bind to exact 20000/20100 minor units. Document revision 7/8,
line identities, quantity 2/3 and the exact free-form reasons remain represented.
The U-only digest bytes are UTF-8 lines, in this fixed order, without a final LF:

```text
approval-provenance-v1-unit
receipt=<canonical document UUID>
version=<decimal revision>
line=<canonical line UUID>
quantity=<decimal quantity>
currency=EGP
amountMinor=<decimal minor units>
reason=<exact frozen free-form reason>
```

`sha256` binds those controlled bytes to `ActionBinding.payloadDigest`; document,
revision, operation, permission and scope also remain explicit public requirement
fields. `goods_receipt.accepted` is the bounded public reason-code binding; it
does not replace, trim or normalize the full reason in the digest. This encoding
is not production serialization, native-origin evidence or a codec/digest oracle.

Chosen synthetic issue origin `2026-11-01T00:00:00Z` maps to same-boot elapsed 0;
frozen current `00:05:00Z` maps to 300000; validity end `00:10:00Z` maps to 600000;
frozen late `00:10:01Z` maps to 601000 milliseconds. The chosen 600000-ms lifetime
preserves the stated interval but prescribes no production lifetime or qualified
OS clock. No wall-clock, reboot, persistence or platform-clock behavior is tested.

## Slice map — bounded assertions passed; complete cases NOT_RUN

Each row is one method in `ApprovalProvenanceV1FrozenUnitSlicesTest`. Each stated
negative variant starts independently. Counts are passive observations of public
final PERFORM proofs, consumer callback observations and returned successful
consumptions; they are not inspected business/database effects. Fresh REQUEST and
APPROVE setup permits are not counted as accepting consuming-action proofs.

| Frozen case/slice | Assertion wired |
| --- | --- |
| APV-01 | Independent direct D/null before and after both separate invocations in one context; exact prescribed direct operation/policy, no direct consumption, no prior approver carried over. |
| APV-02 | R/M then R/N under their exact different approvals/operations/receipts; consumer sees each pair/policy 10, each consumes once, first R/M proof remains exact after the second call. Identical fixture display labels do not substitute for canonical IDs. |
| APV-03 | Explicit active NamedSelf S/S, nonnull approver, exact operation and one returned consumption. |
| APV-04-a | M/M Separate issuance refuses despite M's own request/approval permits; no direct permission fallback. |
| APV-04-b | M's request is valid but R's approving permission is denied; no fabricated approve permit or issue/consume. |
| APV-05-a–d | Missing/inactive/wrong-account/wrong-action exception: both issue and consume refuse, zero final observations and original S/S issued input unchanged. |
| APV-06-a | Controlled current requester-session revocation denies the public fresh REQUEST evaluation; no consume is dispatched. |
| APV-06-b | Controlled approver account disable denies current APPROVE evaluation and public consumption; original R/M input retained. |
| APV-06-c | Controlled qualifying approver-session revocation denies current APPROVE evaluation. Current-session-source/consumption integration is not bound. |
| APV-06-d | Supplied activated policy 11 removes approval permission; current evaluation and old-record consumption refuse, retained input stays R/M at policy 10. |
| APV-06-e | Activated-10 positive control accepts R/M once. The pending-11 description is not passed as activated authority; real pending/activated source selection is not exercised. |
| APV-07-A/B/E | Changed requester M or current approving identity N cannot consume the R/M record; N's otherwise valid approving permit cannot rewrite M. RB/RC current request evaluations refuse the bound foreign scope. |
| APV-07-C/D/F/G | Exactly the stated company, branch, operation or action field changes; the public record/requirement binding refuses the unchanged original permit, no new proof/consumption. No changed-action business flow is claimed. |
| APV-07-H | Current policy 11 still authorizes both setup permits, but cannot consume the policy-10 approval. |
| APV-08-a–g | Changed receipt/version/line/quantity/amount/full reason or exact late validity input refuses; original issued identity/body remains unchanged. |
| APV-09-c | Actual returned consumed record refuses a fresh authorization attempt; cumulative observed proof/consumer/consumption counts stay 1 and first R/M remains unchanged. |
| APV-09-e | Same operation with quantity 3 cannot consume that returned record again; original proof stays R/M and cumulative counts stay 1. This is not a durable operation-body conflict/recovery test. |

## Named gaps retained from full frozen cases

- APV-09-a/b: the public pure record has no explicit denied/revoked approval-status
  representation. No synthetic status field or pretend persisted record is added.
- APV-09-d and the recovery/conflict portion of APV-09-e: no original-result store,
  lookup/submission, committed-result recovery or altered-payload collision check
  is substituted. Pure refusal to consume again does not establish recovery.
- APV-06-a/c/e and APV-07-E: supplied snapshot decisions do not prove the production
  current-session/grant/pending-policy source or correct transaction composition.
  `CurrentApproverState` has no qualifying-session/grant-source field. Later
  revocation/recovery of durable historical provenance remains L2/L3/L4 evidence.
- Full consuming business action, persistent all-or-none effects, approval CAS,
  SQLite connections/restart/restore, action/result/audit/outbox consistency,
  current real authentication, local protected adapters/RPC, replay/concurrency,
  lost responses/partition and actual business zero-effects remain unbound here.
- Shared native-target execution, all platform Master/client roles, own-auth UI,
  account/device changes, rendered private state, device/emulator/simulator and
  owner acceptance remain separately unverified. Central services remain held.

Executed JVM selector, relative to the POS build:

```text
:core:security:jvmTest
--tests com.ukkera.pos.security.independent.approvalprovenancev1.ApprovalProvenanceV1FrozenUnitSlicesTest
```
