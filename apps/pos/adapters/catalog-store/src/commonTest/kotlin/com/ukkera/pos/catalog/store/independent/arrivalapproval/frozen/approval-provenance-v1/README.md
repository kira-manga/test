# Approval provenance — frozen specification v1.0

Status: **FROZEN, NOT RUN**. Freeze date: 1 November 2026. The fixtures,
cases, assertions and expected results in [cases.md](cases.md) are fixed before
harness execution. This is a small authorization specification, not a purchasing
or local-readiness verdict.

The assigned observable contract is: protected business authorization uses the
Master's current local authority in each transaction. Direct authorization has
no approver. An accepting proof for a consuming approval identifies the original
requester and the actual authorized approver. Separate approval forbids
unauthorized self approval; NamedSelf requires an explicit active exception for
the named account and action. Actor, scope, operation and policy bindings must
match. Denied, stale, revoked and consumed approvals cannot supply a new accepting
proof. These are behavior descriptions, not implementation or API names.

## Original requirement mapping

| Reference | Original source and applicable requirement |
| --- | --- |
| R1 | [Operational specification](../../requirements/original/POS_OPERATIONAL_SPECIFICATION.md), §1 "Employee account" and §2 "Accounts, ownership and everyday access": branch-account identity is independent of devices; privileged approval uses the approver's own local authentication and an action-bound grant; original and actual actors remain recorded. |
| R2 | [Company policies](../../requirements/original/POS_COMPANY_POLICIES_AND_CATALOG.md), §1 "Company head, delegation and configurable limits": separate requester/approver rules; explicit named action-specific self approval; binding to requester, approver, company/branch, action, document version, lines/amount and reason; preserve cashier identity; enforce the currently activated policy on every command. |
| R3 | [Acceptance scenarios](../../requirements/original/POS_ACCEPTANCE_SCENARIOS.md), "Company permissions, report scope and approvals", CFG-02, CFG-03, CFG-04 and CFG-05; "Setup, daily interface and training", UX-06 and UX-07: reject stale grants and escalation, retain requester/approver identities, bound self approval, activation-aware revocation and original attribution through retry/logout. |
| R4 | [Test and quality plan](../../requirements/original/POS_TEST_AND_QUALITY_PLAN.md), §2 invariants 2, 3, 5 and 8; §5 "Required command expansion"; §9 Q-SEC-03, Q-SEC-04, Q-SEC-05, Q-SEC-06 and Q-SEC-09: separate identity/session/approval checks, immutable historical actor, original-operation recovery, invalid approval rejection and current activated permission enforcement. |
| R5 | [Purchasing specification](../../requirements/original/POS_PURCHASING_AND_RECEIVING_DESIGN.md), §2 "Authority, company procurement and offline work", §8 "Planning, reports and documents" and §9 final paragraph: the branch Master authorizes local purchasing, documents retain receiving/approving actors, and commands use authorization, versions, durable operation IDs and atomic result/audit/outbox discipline. PUR-04 and PUR-16 in the [acceptance scenarios](../../requirements/original/POS_ACCEPTANCE_SCENARIOS.md) supply retry/recovery obligations. |

## Coverage boundaries and required execution

The L1 cases observe authorization decisions and the provenance supplied to a
consuming action. An independent transaction in these cases is a separate
authorization invocation with explicitly supplied current authority. Pure tests
cannot establish database transaction isolation, real session authentication,
transport protection or persistent audit correctness.

| Scope | Required verification; currently NOT RUN |
| --- | --- |
| L1 unit | Execute the frozen cases on the available JVM/native shared-test targets with deterministic fixtures. Preserve every expected identity, refusal and binding. JVM execution alone does not qualify native platforms. |
| L2 production persistence/runtime | Repeat positive, rejection, revocation, duplicate and failure-before/after-commit cases through the real consuming action and production SQLite drivers for each supported platform. A fresh independent connection must observe original requester and actual approver in the committed action/result/audit and related outbox, with all-or-none approval consumption and business effects. On refusal, observe zero additional successful actions, consumption and stock/cash/document effects; a truthful denied-attempt audit is allowed. Include restart and supported restore. Gates Q-DB-02, Q-DB-03, Q-DB-04 and Q-SEC-09; R4/R5. |
| L3 local services/communication | Exercise the protected same-device adapter and real admitted client-to-Master local RPC/authentication paths. Recheck current requester and approver sessions, scope and activated policy; test pending versus activated revocation, replay, lost response, concurrent consumption and client-Master partition. Cover N0–N5 and N7 where applicable. No cache, device identity, training service or in-memory replacement establishes this boundary. Q-SEC-01 through Q-SEC-06 and Q-SEC-09; quality plan §§3, 4.2, 4.3 and 9. |
| L4 application behavior | Exercise own-authentication approval inside the cashier flow, followed by logout/account/device changes and recovery. The original requester remains the operator/collector as applicable, and the actual approver remains recorded. No private-state transfer or actor relabeling. CFG-02, UX-06, UX-07 and Q-SEC-04. |
| Platform/operational acceptance | Required Master/client roles cover Windows, macOS, Android, iPhone and iPad under their operating contracts. Device, emulator/simulator and physical-device campaigns and owner hands-on acceptance are separate, unverified scopes; unit or host integration success cannot satisfy them. Quality plan §§2 and 4.3; qa/pos/AGENTS.md. |
| Central services | Real central administration/provisioning/synchronization remains deferred pending owner testing and separate explicit authorization. Already provisioned synthetic local fixtures may exercise local behavior without claiming central registration, central verification or a passed deferral. OWNER-DIRECTIVE.md and R5. |

No owner decision is unresolved for the assigned identity-preservation contract.
An unavailable production or platform boundary is recorded as unrun/blocked,
never inferred from a unit pass. This specification does not claim complete
coverage of the cited original scenarios or gates.

Any expectation correction requires an independently derived new version with
its rationale; retain v1.0 and its results. Execution records must identify the
tested revision, fixture/case IDs, layer, platform/role, network/failure variant,
observed outcome and verification gaps without changing this freeze.
