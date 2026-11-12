# Ukkera POS implementation and agent handoff plan

Revision 1.0, 21 October 2026. Status: execution specification; implementation and production qualification have not been performed by creating this plan. Language: English. Repository working name: `ukkera-pos`. New project namespace: `BranchPos`.

## 1. Objective and authority of this package

Build the production POS described in this package, including its central integration, branch master, native company administration, cashier, inventory, purchasing, restaurant and recovery workflows. The first delivered implementation must progress through a working local system and verified local endpoints before switching an environment to a deployed backend. A UI-only demonstration, generated CRUD or mocked checkout is not completion.

The latest user decisions take precedence over earlier proposals:

- All company administration is inside the Compose Multiplatform application. `module-manager` remains relevant to the existing generation/tooling ecosystem; a customer-facing web administration portal is not a deliverable or prerequisite.
- Use Navigation 3, Koin, Coil and Room with the latest stable **compatible** versions established through current official sources and a real build/runtime spike. P2pKit remains the requested RPC library; its published candidate release needs explicit qualification.
- A main agent owns the whole result. Implementation is divided into bounded tasks with independent review, continuous useful work and integration gates.
- Actual device/peripheral models and branch sizes are unspecified. This package supplies provisional benchmark targets and separate physical qualification gates; it does not claim every model or capacity is already supported.

Read these files as one specification:

| File | Purpose |
| --- | --- |
| [Architecture](POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md) | Accepted authority, account, platform, synchronization and recovery rules |
| [Operational specification](POS_OPERATIONAL_SPECIFICATION.md) | Cash, order, kitchen, stock, money, returns and activity rules |
| [Company policies/catalog](POS_COMPANY_POLICIES_AND_CATALOG.md) | Configurable administration, product capabilities, promotions, remedies and UX |
| [Purchasing design](POS_PURCHASING_AND_RECEIVING_DESIGN.md) | Physical receipt, invoice, settlement, supplier return and valuation |
| [Domain/contract blueprint](POS_DOMAIN_AND_CONTRACT_BLUEPRINT.md) | Entity ownership, storage boundaries, fields and guarded contracts |
| [Technology guide](POS_TECHNOLOGY_AND_LIBRARY_GUIDE.md) | Sources, compatibility decisions and library usage requirements |
| [Agent task catalog](POS_AGENT_TASKS.md) | Dependencies, outputs, ownership and acceptance for execution tasks |
| [Quality plan](POS_TEST_AND_QUALITY_PLAN.md) | Test layers, fixtures, fault/performance/device gates and evidence |
| [Acceptance scenarios](POS_ACCEPTANCE_SCENARIOS.md) | 141 existing business scenarios, all initially unexecuted |
| [Traceability register](POS_TEST_TRACEABILITY.csv) | Scenario-to-implementation/test/evidence mapping |
| [Backend source review](BACKEND_ARCHITECTURE_REVIEW.md) | Historical repository/generator findings; revalidate before implementation |
| [Agent start prompt](POS_AGENT_START_PROMPT.md) | Instructions to paste into the executing main agent |

The portable handoff archive contains local-relative links. Read it without assuming access to this conversation, this machine's absolute paths or personal skills. Where relevant skills exist, read them and repository instructions; the essential requirements are also recorded in this package. Resolve a real contradiction against the latest accepted user decision and record the resolution. Do not silently remove a feature to make an implementation easier.

## 2. Invariants that every workstream must preserve

1. Organization → Company → Branch. Different companies under one organization may have different activities and independent catalogs/policies. All references and reads/writes must obey tenant scope.
2. Exactly one operational master per branch, online or offline, including one-device branches. Clients cannot finalize independently and checkout never switches to central-direct execution.
3. Device admission, local human account and human permission are separate. Employee accounts belong to one branch and can move between admitted devices there; another branch requires another account.
4. The master backend survives cashier logout. Supported same-master restart/reopening restores only its still-valid role. Mobile lifecycle limitations remain explicit; a popup does not override OS suspension.
5. A locally durable commit confirms a sale without internet, bank API or standby acknowledgement. Cashiers attest external tender outcomes; a lost reply is an unknown outcome to recover by operation ID.
6. Business effects, durable operation result, audit and outbox commit atomically. Financial identifiers/entitlements are not made safe solely by P2pKit's temporary transport deduplication.
7. Company price revisions supersede older branch overrides for the relevant context. Protected/paid/collection-locked order and historical receipt snapshots are not silently repriced.
8. No sale beyond available sellable stock. Ingredient issue happens on kitchen send; verified cancellation before start reverses the original issue; after-start cancellation retains consumed stock and classifies cost once.
9. Each cashier has their own cash custody. Cash-session closure preserves actual counts, discrepancies and immutable reports; later corrections do not rewrite history or create fake employee debt.
10. Orders are owner-private with versioned handoff. Scoped kitchen/courier/manager projections are explicit exceptions, not universal order access.
11. Returns/exchanges are same-selling-branch only and work offline. Use original snapshots and remaining entitlement. No customer credit sales, spendable customer balances, gift-card wallets or loyalty in release one.
12. Central synchronization preserves committed facts. It never repeats checkout, bank collection, ingredient issue or physical printing. Central reports disclose branch freshness.
13. Replacement of the master requires a named company-delegated user's approval and effective retirement/isolation of the old writer. Epoch increments alone cannot stop an isolated old device.
14. An optional preprovisioned standby helps recovery; its absence/lag cannot gate ordinary sales. Disclose any missing recovery tail instead of claiming zero loss.
15. Existing YAML definitions remain reference-only. `BranchPos` is new. Generated fields/forms/hooks do not replace real domain commands, tenant guards or migrations.

## 3. Native app areas and separate authority paths

One Compose Multiplatform app contains the following role-aware areas. Adaptive layouts present the same domain state on phone, tablet and desktop.

| Area | Data/command authority | Required screens/workflows |
| --- | --- | --- |
| Company administration | Central authenticated management APIs | Organization/company selection, company profile, branches, accounts/delegations, catalog/variants/recipes, prices/promotions/taxes, policy publication, procurement overview, company reports/alerts, device/recovery oversight |
| Branch administration | Active master with local branch account/grants | Local users/settings within delegation, drawers, stock/counts/receiving, branch reports, active policy status, peripherals, master/recovery operation |
| Cashier/service | Active master through the same guarded contracts | Local login, own orders, scan/search, modifiers, collection, handoff, receipts, returns, shift opening/closing and exceptions |
| Kitchen/delivery | Master-issued narrow authorized projections | Assigned preparation/fulfillment and collection/remittance tasks |
| Provisioning/recovery | Explicit enrollment/recovery trust and named authorization | Setup, pairing, role assignment, restore validation, planned handover and supervised replacement |

Central administration uses a central principal/session; it is not a reusable cashier account across branches. Entering branch operational work requires the correct separate local branch account. Keep credentials, clients, caches, navigation roots and audit actors separated between central administration and branch operation, even on the same physical device.

Company administrators can operate the native management UI away from a branch. Offline management may show permitted cached data and save clearly marked drafts. A global catalog/policy/role change becomes applied only after central acceptance and the branch's activation acknowledgement. Local branch changes work offline only within the previously delegated policy. A queued administrative draft is reauthorized/version-checked at submission; no success toast may imply every disconnected branch has already applied it.

## 4. Repository and module structure

Start by inventorying the actual workspace and preserving existing changes. Use isolated checkouts/worktrees when modifying shared upstream repositories. Record URL, exact source revision, dirty state, purpose and necessary patches for each repository. Do not assume historical review commits are the current compatible set.

| Repository | Intended role |
| --- | --- |
| `ukkera-pos` | New KMP app, branch domain/runtime, clients, native administration, contracts, tests, environment orchestration and handoff documentation |
| `ukkera/modules` | Add new `BranchPos` source definition in a scoped change; existing Pos/Item/Org YAML untouched |
| `ukkera/main-next` | Reuse the actual generator; isolate any required generator fixes with regression coverage |
| `ukkera/viki-go` | Generated central metadata/documents plus protected administration, ingestion, query and fiscal/integration handlers |
| `ukkera/module-manager`, `ukkera/common-main-r` | Read/validate existing tooling, registry and integration conventions; modify only if the generation/integration path actually requires it |
| `p2pKit/P2pKit` | Pin published artifacts and matching reviewed source; contribute isolated fixes if necessary, without pretending an unreviewed fork is the official release |

Recommended KMP boundaries are `core:types`, `core:contracts`, `core:security`, `core:database`, `core:network`, `core:designsystem`, `core:navigation`, `core:testing`; domain modules for catalog/pricing, inventory, orders, cash, kitchen, payments/returns, procurement and reporting; runtime modules for master, RPC, sync and recovery; and feature modules for each user area. Keep platform launchers/adapters in Android, iOS and desktop modules. Final Gradle names are selected in T00/T01 to fit the verified toolchain, not copied as untested commands.

Business rules depend on typed domain interfaces, not UI, Koin lookups, generated central documents or platform APIs. Platform code owns OS lifecycle, secure credential storage, native networking/peripherals and packaging. Feature screens receive state/callbacks; route adapters own navigation/ViewModel wiring. Avoid a module for every individual screen and avoid a single universal module containing the whole product.

## 5. Toolchain and compatibility gate

T01 is a blocking engineering gate for platform foundations, not permission to substitute a simpler product. Its outputs are exact pinned versions/checksums, an official-source compatibility matrix, OS/CPU minima, platform runtime evidence and a recorded decision for every incompatibility.

Verify the latest stable compatible Kotlin/Compose, Android toolchain, Navigation 3 runtime/UI/event/lifecycle, Koin, Coil, Room/SQLite driver/KSP, coroutines/serialization, HTTP engine, Go/Mongo and native toolchain as a coherent set. Artifact metadata saying “release” can still refer to beta/RC; resolve stability explicitly. A newer major, such as Room, requires current coordinates/API/driver review rather than mechanically copying older samples.

No dynamic versions, unbounded dependency upgrades, duplicate navigation engines, unnecessary overlapping HTTP/image/DI frameworks or invented performance guarantees. Use public supported APIs. Revalidate affected builds/tests after upgrades. An unavoidable prerelease is listed with the reason, exact pin, source, known gaps and release gate; the user's explicit P2pKit requirement is handled this way when no suitable stable release exists.

The spike must compile and run a minimal Room transaction, actual P2pKit host/client RPC, same-device adapter, local auth separation, offline process restart and typed Nav3 screen on supported target families. Prove the desktop host/GUI separation and iOS Kotlin/Native path; JVM-only backend code cannot satisfy iOS hosting. Check image loading/cache behavior, database durability/storage protection and actual linked native libraries. Report each unexecuted physical profile separately.

## 6. First integrated slice and milestone exits

This sequencing prioritizes early integration while retaining the full release scope. The first slice is a learning/verification gate, not a reduced final release.

| Milestone | Concrete exit evidence |
| --- | --- |
| M0 — reproducible foundations | Source manifest, instructions, compatibility spike, contracts, CI/test harness and new YAML generation probe |
| M1 — real offline retail sale | Local login, counted drawer, catalog/stock, order, cash/external-tender attestation, durable receipt, lost-reply recovery, second device and user switch; no central/standby dependency |
| M2 — central/native administration | Company management inside CMP, protected local backend endpoints, upload and policy activation, truthful disconnected status and company priority |
| M3 — complete business workflows | Restaurants/cafes/clothing/supermarkets, kitchen, exceptions/delivery, returns/exchanges, purchasing, stock/counts/transfers, reporting and setup implemented against scenarios |
| M4 — operational resilience | Master handover/recovery, optional standby, backup/restore, peripherals, fiscal integration where applicable, observability and controlled upgrades |
| M5 — qualified candidate | Traceability complete, integration/security/fault/performance tests passed, required platform/device/peripheral/fiscal evidence recorded, reproducible candidate artifacts and pilot findings resolved |

T01 spikes execute before committing platform architecture. T06 validates new YAML in isolation and compares generated output. T17 first-slice tests use real master/client and central-service isolation. T23/T24 prove upload and admin activation independently before a combined environment. M3/M4 feature work follows the task DAG; integrating only at the end is prohibited.

## 7. Main agent and continuous multi-agent work

The main agent owns scope, contract decisions, task readiness, repository integrity, shared files, integration, evidence and truthful completion. Delegation transfers a bounded task, not accountability. It keeps `TASK_BOARD.md`, `DECISIONS.md`, `INTEGRATION_STATUS.md` and `VERIFICATION_REPORT.md` in the implementation repository current.

Use the actual available concurrency limit. With four total slots, start one main agent and three workers; do not assume unlimited children. Workers rotate between implementation and independent review. There is no permanently idle reviewer waiting for a large phase to finish.

Each task assignment identifies ID, goal, dependency revisions, allowed file/module ownership, frozen contracts, required tests/scenarios, output/evidence and reviewer. Shared root build files, dependency catalogs/locks, schema registry, route registry and CI integration have a named single owner. A worker requests a small integration change instead of racing another worker over the same file.

Task states: `BACKLOG → READY → IN_PROGRESS → SELF_VERIFIED → REVIEW_QUEUED → IN_REVIEW → CHANGES_REQUESTED/REVIEWED → INTEGRATED → VERIFIED`. `BLOCKED` requires the exact dependency/fact plus safe independent work that remains possible; it never means silently dropping the task.

Scheduling rules:

1. Start independent ready tasks concurrently after their contracts are agreed. Prefer small vertical work units with one clear acceptance boundary.
2. A worker finishing self-checks freezes its patch/revision, records evidence and queues independent review. It then takes another ready independent task or reviews another worker's completed patch; it does not wait for its own review.
3. A reviewer finishes the current bounded work unit or reaches a safe checkpoint, then reviews the assigned frozen patch. Reviews blocking downstream integration take priority over starting another large implementation unit.
4. Reviewers inspect code, tests and sibling paths, run meaningful focused checks where possible, and report concrete findings. The author fixes findings; the reviewer rechecks the actual changed revision.
5. Main integrates only accepted revisions, resolves cross-repository dependencies and reruns affected integration checks on the combined tree. A reviewer-approved isolated patch is not proof the merged system works.
6. Keep work in progress bounded: normally one active implementation plus one pending-review unit per worker. If the review queue grows, allocate capacity to review/fixes/integration before more new code.
7. Do not stack production work on an unreviewed unstable foundation. Independent fixtures, UI against frozen contracts, documentation, adapters and other ready modules can proceed. Necessary waiting when no safe independent work remains is reported honestly, not filled with unrelated changes.

An example rotation is A implementing storage, B implementing isolated central generation, C implementing the UI shell; after their work units complete, B reviews A, C reviews B and A reviews C. Main validates the combined result. The assignment changes as dependencies progress; nobody approves their own change. If only one worker slot is available, execute sequentially with an independent review pass when another reviewer is available and disclose any review still outstanding.

## 8. Task-level definition of done

A task is integrated only when its contract is implemented, relevant normal/negative/boundary/race/retry/failure behavior is covered, checks pass on the named environment, independent findings are resolved, documentation/migrations/schemas are updated and main's integration check passes. There are no placeholder `TODO` endpoints, fake successful payment/sync/print results or screenshots standing in for domain behavior.

For each changed business command, verify: authorized actor/scope; preconditions and expected version; exact money/quantity effects; transactional failure behavior; duplicate/lost-response recovery; audit; outbox; and user-visible result. Tests assert externally meaningful outcomes and ledger conservation, not only mock call counts or copies of the implementation formula.

For each screen, verify loading, empty, success, validation, permission-denied, offline/master-unavailable and recoverable failure states that apply. Test navigation/session cleanup and input methods. Simple reversible text/layout edits need appropriate visual/static verification rather than fabricated low-value unit tests.

Each evidence entry contains task/scenario ID, exact source revision, platform/OS/CPU/device or runner, dataset, command or manual steps, expected/actual result, test totals/failures/skips, log/artifact reference and reviewer. A skipped/unsupported/not-run case never becomes passed through a summary.

## 9. UX contract

The POS must remain simple during real work. Display only the user's granted areas and the activity's relevant controls. Use progressive disclosure for advanced catalog/policy fields; make ordinary scan → verify → pay short. Reuse the same interaction patterns for search, quantity, approval, confirmation, error and recovery across features.

Company administration must be usable on phone/tablet/desktop inside CMP, including bulk catalog editing/import, price publication, permissions, procurement, reports and export. Large tables use bounded paging, search/filter and adaptive detail panes; do not expose raw YAML, synchronization payloads or permission implementation details in customer flows.

Arabic/English, RTL, accessible labels, text scaling, readable amounts, touch targets and keyboard/scanner behavior are release requirements. Navigation has one typed state owner, independent top-level histories and an explicit Back/leave policy. Pending payment, return, kitchen cancellation, import and handover cannot be bypassed through predictive Back, iOS swipe, a tab change or a stale callback. Protected screens must not flash before authentication.

Show independent statuses for local commit, central synchronization, kitchen, printing and external tender attestation. Examples: “Saved at branch,” “Waiting for master,” “Payment outcome needs checking,” and “Refund still due.” A retry of an uncertain operation queries/reuses its identity; a second charge requires a separately established business decision. Login/logout changes UI identity without stopping a valid master or reassigning old commands.

## 10. Local environment, fixtures and endpoint progression

Provide documented repeatable local startup/stop/reset commands using the repository's verified tooling. Isolate ports, database names, credentials and storage from existing applications. A replica-set-capable Mongo environment is required where central transactions/change behavior need it; a generic standalone fake is insufficient evidence for those paths. Never reset a user's live data to make tests pass.

Create synthetic fixtures for one organization, separate clothing/restaurant companies, at least two branches, distinct branch accounts for the same human, two cashiers/drawers, manager/company head, kitchen and courier roles, one master, two clients and optional standby. Include inclusive/exclusive prices, fractional/pack units, last-item stock, recipes, variants, expiry/quarantine, invoices/returns and pending outcomes. Training fixtures are not live financial/fiscal data.

Progress through generator schema validation → actual local central endpoints → local master persistence/auth commands → same-device and LAN clients → native administration → combined offline/reconnect scenarios. Record API contracts and actual endpoint URLs for development only; candidate routes in the blueprint are not claims of existing APIs.

Environment switching configures central base URL, trust/identity and environment-scoped cache/storage; it never changes a branch checkout's authority path. Use explicit development/staging/production identities so local installs and credentials cannot collide with live ones. Retain deployment-compatible migration and API-version checks. Do not embed secrets in source, generated YAML, crash logs, screenshots or the handoff.

## 11. Test hierarchy and capacity assumptions

The quality plan specifies pure-domain/property tests, real database/migration tests, command/API authorization, transport/sync integration, Compose/navigation tests, end-to-end business scenarios, chaos/recovery, performance/soak and installed-device/fiscal/peripheral qualification. Implement all applicable cases from the 141-scenario register plus new cases introduced by implementation and the quality plan's nonfunctional gates.

Provisional profiles are P0: one master/self-client and 5,000 SKUs; P1: one master plus 10 clients, 25,000 SKUs and 5,000 sales/day; P2: one master plus 25 clients, 100,000 SKUs and 20,000 sales/day. P2 is a stress target, not a promise that any phone can host it. Record average/peak line counts, recipe complexity and event rates so daily sales are not confused with instantaneous concurrency.

Initial nominal LAN targets: checkout commit p95 ≤500 ms and p99 ≤1 s, excluding operator/external terminal/physical printing; local lookup p95 ≤150 ms; UI response/navigation p95 ≤100 ms as defined by the quality plan. Measure tail latency, errors, memory, disk growth, frame behavior and queue age. Run the specified no-WAN, 72-hour soak and 250,000-event catch-up profiles; catch-up should sustain more than twice the continuing live event rate under the recorded profile. Faster hardware is not proof for a weaker supported master profile.

Actual supported OS/CPU/device/peripheral combinations, legal fiscal requirements and required third-party connectors remain qualification inputs. Keep these explicit in `EXTERNAL_GATES.md`; implement adapters, tests and available checks while waiting. No agent may mark a physical test passed because its mock or simulator passed, or claim a global production verdict while an applicable mandatory gate remains unresolved.

## 12. Integration, review and release evidence

CI runs appropriate formatting/static analysis, domain and contract tests, DB/generator checks, dependency verification and target builds on each relevant change. Larger scenarios/soaks run in scheduled/candidate lanes with traceable results; infrastructure failures and quarantined flakes are visible. Security/data-integrity failures cannot be waived into a passing release by rerunning until green.

Use source-review and dependency/license checks, bounded secret-free diagnostics, storage-pressure handling and migration/restore tests. Check debug and optimized/release behavior; native linkage, Android shrinking and packaged desktop resources may differ from development execution. Signing/notarization/store and fiscal credentials are external facts; record actual artifact identity and the exact remaining step instead of faking credential-bearing success.

Main's final report distinguishes `IMPLEMENTED`, `AUTOMATED_VERIFIED`, `DEVICE_VERIFIED`, `EXTERNAL_GATE_PENDING` and `RELEASE_READY`. Release readiness requires all applicable mandatory gates, no unresolved release-blocking findings, an immutable reviewed source set, matching reproducible artifacts, deployment/migration/rollback instructions, operator setup/training and pilot evidence. Backups and rollback must preserve committed financial data; a binary downgrade may be unsupported after a schema upgrade and must not be attempted blindly.

Deliver the runnable app/source, new YAML and generated changes, required scoped upstream patches, reproducible local environment, test fixtures and traceability, evidence and defect register, dependency/source manifest, qualified artifact matrix, and operating/recovery runbooks. Publishing to live servers/stores is a separate concrete release action once the applicable environment and authorization are in place. Continue all independently executable implementation and verification work; do not call missing production credentials a reason to stop developing local features.
