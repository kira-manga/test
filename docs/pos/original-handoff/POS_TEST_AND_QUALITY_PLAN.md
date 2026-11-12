# POS implementation verification and release gates

Revision 1.0 — 21 October 2026. Project: `ukkera-pos`; new central namespace: `BranchPos`.

This is an implementation and verification contract for the main agent, feature agents and independent reviewers. **No test in this document has been executed against an implemented POS. All 141 acceptance scenarios and all additional engineering gates start at `NOT RUN`.** Generating models, showing a UI demo, compiling one platform, or writing test names is not a passing result.

Read with the [architecture](POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md), [operations](POS_OPERATIONAL_SPECIFICATION.md), [company policies and catalog](POS_COMPANY_POLICIES_AND_CATALOG.md), [purchasing](POS_PURCHASING_AND_RECEIVING_DESIGN.md), [141 acceptance scenarios](POS_ACCEPTANCE_SCENARIOS.md), and [machine-readable traceability](POS_TEST_TRACEABILITY.csv). Company administration is part of the Compose Multiplatform application; generated module-manager forms are developer/schema tooling, not a substitute product interface.

## 1. Ownership and the meaning of done

The main agent owns the integrated result, task dependencies, contract consistency, evidence index, unresolved risks and final verdict. Every feature agent implements its business behavior and meaningful tests together. Another agent reviews the change, assertions and failure handling; an author cannot self-approve their own task. Review is a separate scheduled work item. While a review is pending, the author takes another unblocked task within an agreed contract; reviewers complete their current bounded task before reviewing. Do not leave agents waiting unnecessarily or let parallel work bypass an unresolved contract dependency.

Task families below are stable traceability labels. The execution backlog maps concrete task IDs to them: `FOUNDATION`, `CENTRAL`, `AUTH`, `CATALOG`, `INVENTORY`, `SALES`, `PAYMENTS`, `CASH`, `KITCHEN`, `RETURNS`, `PROCUREMENT`, `SYNC`, `RECOVERY`, `UI-NAV`, `UI-ADMIN`, `UI-CASHIER`, `PERIPHERALS`, `FISCAL`, `QA-RELEASE`.

A task is complete only when its required behavior is implemented, applicable tests pass, independent findings are resolved or explicitly classified, and evidence identifies the tested tree. A main-agent integration run must prove that individually green components work together. A simulated adapter or disabled path cannot satisfy a production capability it replaces.

| Evidence state | Meaning |
| --- | --- |
| `NOT RUN` | Planned only; no execution evidence exists |
| `PASS` | Expected outcome and required invariants observed on the identified implementation/environment |
| `FAIL` | Required behavior or invariant failed; retain failing seed/log/fixture |
| `BLOCKED` | Exact missing hardware, credentials, service or decision prevents execution; does not count as passed |
| `NOT APPLICABLE` | Reviewer accepted a precise scope reason, such as no scale on a specific peripheral profile; never used to drop a required platform or role |
| `INVALIDATED` | A subsequent source, dependency, schema, toolchain or configuration change invalidated earlier evidence |

`PASS` at a unit layer does not imply platform, peripheral, legal or end-to-end acceptance. CSV rows are scenario rollups: a row becomes `PASS` only after its required run variants pass. Store run records separately; never overwrite one device's failure with another device's success.

## 2. Non-negotiable invariants

1. The active branch master is the only operational writer, online and offline, including a one-device branch. A disconnected client cannot finalize against its cache or central CRUD.
2. Device admission, branch-local employee identity, current session and operation approval are separate checks. Company administration sessions and master-service credentials are separate from branch cashier sessions.
3. Financial/stock command effects, durable result, audit and outboxes commit atomically. Retry uses the original operation ID and payload digest; same ID with a different payload is rejected.
4. No negative sellable stock, over-return entitlement, duplicate cash movement, duplicate kitchen issue or unapproved competing master is accepted.
5. Original actor, counted cash, posted price/tax/cost snapshots and historical documents survive logout, price changes, migration, retries and recovery.
6. Cashier-recorded external payment is an attestation. There is no required bank/provider/cloud call before local completion. An unknown outcome never becomes permission to charge again.
7. Ordinary completion never waits for standby or central acknowledgement. Losing the only storage device can lose its unreplicated tail; report measured recovery coverage truthfully.
8. Company policy applies locally only after validated activation. Central reports display freshness and pending changes; central intent is not proof of branch application.
9. Returns/exchanges are same-selling-branch only. Customer credit, spendable customer balances and loyalty remain excluded.
10. Windows, macOS, Android, iPhone and iPad all ship as clients and support the agreed master role. Mobile hosting remains subject to the explicit active-app/OS lifecycle contract.
11. No destructive database fallback, blind historical balance rewrite, fake success, cross-tenant cache reuse or protected screen flash may be accepted as recovery.

Assertions must inspect outcomes from independent read/ledger views, not merely the command return value or mocked calls. For each money-changing case inspect payable, collected amount, physical cash, refunds due, entitlements, quantity/cost, audit actor and sync identity as applicable.

## 3. Test pyramid and harness contracts

| Layer | Required scope | Real dependencies and oracle | Typical cadence |
| --- | --- | --- | --- |
| L0: contracts/static | YAML strictness, namespace/ownership, route registry, dependency rules, serializer compatibility, localized resource parity | Source and generated-output inspection; no business acceptance claim | Every affected change |
| L1: pure domain | Money/quantity arithmetic, permissions, state transitions, allocation, policy and navigation | Deterministic clocks/IDs and independent expected values; property/reference-model tests | Every feature change; all shared tests on integration |
| L2: local persistence/runtime | Real Room/SQLite schema, transactions, command idempotency, session/role checks, migrations, outboxes | Actual production DAO/runtime path and selected driver; read committed ledger/results from a fresh connection | Every persistence/domain change |
| L3: contracts/services | Real P2pKit RPC, protected same-device adapter, viki-go/auth, generated BranchPos definitions, MongoDB replica set, sync | Isolated local services and real registration/permissions; no in-memory repository substituted for the boundary under test | Every boundary change and integrated candidate |
| L4: application end-to-end | CMP cashier, kitchen, inventory and company administration; complete user workflows | Installed apps connected to the real master and isolated central stack; screenshots are supplemental | Each feature slice; smoke on each integration |
| L5: resilience/security/load | Races, partitions, process/OS failure, malformed input, tenancy, catch-up, long runs | Fault-injection harness and independent invariants; saved schedules/seeds | Nightly/contract changes and candidate |
| L6: physical/operational | Real OS lifecycle, iOS gestures, printers/scanners/scales/drawers, restore rehearsal and fiscal qualification | Actual named hardware/environment and witnessed observations | Before rollout on each supported profile |

Use the latest compatible stable test libraries supported by the chosen toolchain, verified against official documentation at implementation time. Expected building blocks include `kotlin.test`, Kotlin coroutines test facilities, platform-supported Room testing, the chosen Kotlin test runner/property library, Compose UI semantics testing, Go testing/race detection, MongoDB integration fixtures and a reproducible local-service harness. Select tools for their actual platform support; a JVM-only test library does not establish native iOS behavior.

Write explicit test-environment abstractions for clock, scheduler, IDs, lifecycle, storage failure and network fault injection. Production code may depend on narrow interfaces; tests of the real transport/storage/auth path must still use those implementations. Fake printers and external-payment events exercise domain behavior without moving real money; qualify physical adapters separately.

Avoid placeholder tests, assertions that only restate a mocked implementation, sleeps as race coordination, broad snapshots without semantic assertions and blanket retry-until-green. Use latches/barriers or deterministic schedulers, bounded timeouts and saved reproducible failures. A flaky required test is a defect to resolve, not evidence to hide.

## 4. Fixtures, platform roles and execution profiles

### 4.1 Synthetic fixture catalog

Each fixture starts from a versioned seed manifest with stable IDs, independently reset documents and a known ledger cut. Never run against live business data, copied credentials or inherited remote environment defaults.

| Fixture | Contents and purpose |
| --- | --- |
| F-BASE | Organization O1 containing clothing C1 and restaurant C2; two branches per company; unrelated organization O2; same human with separate branch accounts; owner, other cashier, branch manager, company head, inventory, KDS and recovery identities; two exclusive drawers |
| F-AUTH | Admitted/unadmitted/retired devices, valid/revoked/expired sessions, explicit denies, absent/configured numeric limits, stale policy and version-bound approvals |
| F-CASH | Independent opening/count/transfer fixtures; 1,000 + 5,000 - 400 - 200 + 100 - 150 - 3,000 = 2,350; counted 2,360; retained float 500; card total 4,000 |
| F-ORDER | Owner-private parked/open orders, handoff candidates, tables, compatible/incompatible bills, collection locks and earlier actors |
| F-KITCHEN | Ten bread units and 1,500 g meat; burger consumes one bread and 150 g; per-portion task states, two stations, paper/KDS paths, modifier and historical recipe versions |
| F-STOCK | Last-unit competition, reservations, unavailable/expired/quarantined lots, exact unit conversions, serials, ledger/count cuts, dispatch/in-transit/receipt identities, last-unit carrying-value residual and original-cost customer return into a changed average-cost pool |
| F-PAY | Cash/external/split tenders, 300 bill with 100 collected, 200 incident, paid-unsent preparation, unknown attestation, COD collector custody and refund obligations |
| F-RETURN | Locally retained immutable receipt and remaining entitlement; partial-return races; 100 return versus replacement 130 or 80; pending refund; actual customer delivery timestamp |
| F-PRICE | Company revision 5 at 100, branch override 120 against revision 5, company revision 6 at 150; mixed tax 114 inclusive + 100 exclusive at illustrative 14%; tied/overlapping promotions |
| F-CATALOG | Selected size/color combinations, multiple barcodes, 12-unit then 10-unit pack versions, valid/invalid dimensions, serials/lots, virtual/assembled kits, output batches and packaging-deposit entitlement |
| F-PURCHASE | PO branch allowances, 24 delivered/22 accepted/2 held, invoice/advance/payment/credit allocations, 10 units at 20 + 10 at 30, supplier return carrying value 250 versus credit claim 300 with explicit variance 50, stock A/B values 100 each plus service 100 and freight 30 |
| F-RECOVERY | Checkpoint/journal/outbox/inbox, open shifts/orders, reservations/unknown payments, return entitlements, purchasing disputes, protected local credentials, delegated replacement and all handover states |
| F-SETUP | Incomplete/resumable setup, duplicate/corrupt import rows, training/live separation, local credential provisioning, opening stock/float and optional absent standby |
| F-UI | Arabic/English labels, long/mixed-direction values, large text, compact/expanded layouts, scanner/key/touch focus, clean/dirty/blocked navigation states |
| F-SCALE | Synthetic P0/P1/P2 datasets and deterministic workload seeds; no production customer data or externally sent notifications |

### 4.2 Network and failure profiles

| Profile | Required behavior under test |
| --- | --- |
| N0 | Healthy LAN, central reachable, optional standby healthy; baseline |
| N1 | WAN and central unavailable while LAN/master healthy; local login/refresh/sales/returns/receiving continue within delegated permissions |
| N2 | WAN unavailable and no standby configured or reachable; same ordinary completion boundary as N1 |
| N3 | Client-master partition, master change of LAN address, packet loss/latency/reconnect; no alternative writer and pending operations retained |
| N4 | Duplicated/reordered/truncated RPC or sync delivery, lost request/response/ACK, concurrent clients and stale document versions |
| N5 | Kill/restart, low storage, I/O failure, migration interruption, OS lifecycle suspension/termination; no false success |
| N6 | Planned handover/emergency recovery, stale old master, divergent checkpoint, unavailable central and stale optional standby |
| N7 | Delayed/invalid/revoked company configuration, permission and catalog packages; atomic activation and truthful version status |
| N8 | Peripheral failure, paper out, disconnected scale/scanner, uncertain print response and physical cash/refund evidence |

`BASE-OFFLINE` in traceability means N0, N1 and N2, plus scenario-specific profiles. Financial mutators also require N4 retry/concurrency and N5 commit-boundary checks even when a row lists a shorter business-specific profile. Negative scope cases must exercise direct request paths, not only disabled UI controls.

### 4.3 Platform roles

`ALL-MASTER` means Windows, macOS, Android, physical iPhone and physical iPad hosting the master, respecting each platform's operating contract. `ALL-CLIENT` means those platforms acting as admitted clients. `CMP-ADMIN` is the company administration UI on those same app platforms. `CENTRAL` means the actual isolated Go/auth/MongoDB service stack. `PHYSICAL-PERIPHERAL` requires the selected compatible adapter and named device.

Run shared domain tests on the available JVM/native targets, every applicable L2 suite against each production SQLite driver, and critical workflows with every required master platform. At candidate time run at least the core login → stock check → sale → same-branch return → drawer close flow across all 25 ordered master/client platform pairs. Do not claim native iPhone/iPad qualification from simulator-only evidence. Wider scenarios may use risk-based pairwise combinations, but payment uncertainty, recovery, role changes and platform lifecycle need their explicit critical variants.

One-device tests must use the protected local adapter; they cannot route through an unsupported P2pKit loopback workaround. Desktop service/UI separation and mobile in-process/service arrangements must preserve identical authorization and command invariants.

## 5. Required command expansion for every business scenario

The 141-row CSV is the minimum business inventory, not one trivial test per feature. Each applicable command receives the following variants, linked back to its scenario IDs:

| Variant | Required assertion |
| --- | --- |
| Valid transition | Exact expected business documents, money/stock/cost/entitlement deltas and actors |
| Wrong actor/scope | Unauthenticated, wrong role, other branch/company/organization, wrong owner and unauthorized related ID fail without mutation |
| Boundary input | Zero/negative/excess quantity, missing/invalid unit, amount overflow, invalid precision, expired stock, malformed identifiers and empty/oversized payload fail safely |
| Stale state | Changed version, approval, price/recipe/permission revision or closed drawer yields the documented conflict; no partial effect |
| Duplicate delivery | Same operation/payload returns one original outcome; different payload under same ID is rejected |
| Concurrent delivery | Simultaneous last-stock, closing, ownership, return, PO allocation or policy activation has one permitted serializable outcome |
| Failure before commit | No committed business effects or success response; any physical external evidence remains explicitly unresolved |
| Failure after commit/before response | Query/retry recovers the committed result; no repeated tender, stock, ticket or settlement |
| Restart/restore | Operation identity, original actor, pending obligation and downstream outbox survive the supported recovery path |
| UI/session transition | Back, logout, new user, rotation/resize and background cannot retag the operation or repeat it |

Not every read-only UI field needs every fault variant. The reviewer documents applicability using the actual effect boundary; they cannot omit a mutating financial boundary because another feature tests a generic repository.

## 6. Exact arithmetic, properties and independent reference models

Use integer minor units for posted money and the agreed exact scaled/decimal representations for unit prices, tax rates and quantities. Never compare financial results using floating-point tolerances. Bound inputs before multiplication/allocation and test overflow, division, sign and round-trip serialization across Kotlin/Go/JSON/MongoDB/SQLite.

Required properties, with reproducible generator seeds and shrinking:

- Rounded line/tax/charge allocations sum exactly to their document amount; allocation order is deterministic; identical snapshots yield identical results on all platforms.
- Paid allocation = collected coverage plus permitted restricted exchange offsets; physical cash = receipts minus change/refunds/payouts, with internal custody transfers distinguished from revenue.
- Refund quantities/amounts plus remaining entitlement never exceed original eligible quantities/paid allocations. Multiple partial refunds and bundle returns cannot create value.
- Stock conservation holds across available/reserved/quarantine/in-transit/issued/waste states. A started-kitchen cancellation does not produce a second negative issue; before-start reversal references the original allocation.
- Recipe/kit graph validation rejects cycles and incompatible units; parent and component effects preserve issue timing and exact quantity. Historical unit/recipe revisions do not alter original receipts.
- Permuting retries or independent operations cannot add extra effects. Noncommuting commands such as start/cancel and close/pay must match one allowed serial order.
- Sync replay converges to the same acknowledged branch operations without re-executing checkout. Company revision priority remains true despite package and branch-edit reorderings.
- Supplier invoices/credits affect obligations, not physical stock; receipt/return changes stock once; landed-cost adjustments conserve allocated value across eligible remaining inventory and consumed-cost variance.
- Supplier-return carrying value is distinct from the supplier's financial credit claim. Regression fixture: receive 10 at 20 and 10 at 30, sell 10 at moving average 25, then return the remaining 10 from the 30-cost receipt. Remaining inventory value 250 must not be reduced by a supplier claim of 300 into zero quantity/value -50; post the verified carrying-value reduction and explicit value/claim variance, preserving the earlier sale cost. Include last-unit rounding residuals and transfer-in valuation.

Build small independent reference models for cash reconciliation, quantity/entitlement accounting, price allocation and command states. Avoid using the production calculation function to generate expected values. Check representative mutation testing of critical predicates where tool support exists; surviving meaningful mutations require improved assertions or a documented equivalence argument. A coverage percentage alone cannot prove these properties, and 100% line coverage is not the release definition.

## 7. Room, storage and crash consistency gates

| Gate | Required tests and evidence |
| --- | --- |
| Q-DB-01 | Export versioned schemas; fresh install plus every supported production-version upgrade, direct/chained paths as applicable; migrated schema equivalent to fresh schema; no `fallbackToDestructiveMigration` for user databases |
| Q-DB-02 | Preserve row counts, identities, relationships, encrypted values, null/default rules, posted totals, return entitlement and operation IDs; test corrupted/wrong schema rejection without reset |
| Q-DB-03 | Inject failure before/after each meaningful write in a command; operation result, stock/cash/entitlement/audit/outbox commit all-or-none; retry from a fresh process/connection |
| Q-DB-04 | Use production SQLite driver, configured journal mode and durability/flush policy on each platform; test process kill and actual power/device shutdown separately; record filesystem/device limitations rather than assuming all hardware honors flush identically |
| Q-DB-05 | Enforce exclusive writer instance and branch identity; two app/service processes, duplicate launch and wrong database path cannot create competing writers |
| Q-DB-06 | Disk full, read-only/corrupt pages, failed fsync/storage API, low-space during snapshot/import/migration and interrupted transaction produce a recoverable failure, never silent sale success or discarded backlog |
| Q-DB-07 | Query plans and measured lookup/last-unit/return-entitlement/ledger queries use justified indexes at P2 scale; pagination is stable and bounded; readers do not indefinitely starve writes |
| Q-DB-08 | Migration interruption rolls back or completes atomically under the chosen engine; unsupported downgrade refuses safely. Restore a supported old backup through its compatibility path before enabling writes |
| Q-DB-09 | Consistent backup includes WAL/journal cut and all required state; live-file copying without a consistency protocol fails review; large transfer/import/restore is streaming/bounded and cancellation-safe |
| Q-DB-10 | Protected credential/recovery material, device-key non-cloning, token invalidation and cross-branch restore rejection are tested; corrupt/truncated/tampered/wrong-key backup leaves the old usable state intact |

The first release has no historical production database yet. Test the initial schema and synthetic migration harness, record that fact, and keep every subsequently shipped schema as a permanent fixture. Do not invent a fictitious passed legacy migration history.

## 8. Real central backend and new YAML gates

| Gate | Required integration proof |
| --- | --- |
| Q-BE-01 | Pin compatible repository commits; leave existing YAML unchanged; strict parse rejects duplicate keys, bad relations, unsupported properties and cross-scope references in the new BranchPos definition |
| Q-BE-02 | Run the actual source YAML → runtime definitions → Go documents → registry generation path in isolation. Diff generated output, verify expected registration/types/indexes and compile the affected Go service; a config-only GraphQL shortcut is insufficient |
| Q-BE-03 | Start a disposable MongoDB replica set plus required auth/app services with explicit local URLs. Seed synthetic application/organization/company/branch/grants; use real login/application headers outside any development authorization bypass |
| Q-BE-04 | Verify generated schema/read behavior and custom authorized management/ingestion commands. Generic model, bulk, cascade, delete and GraphQL paths cannot mutate posted operational ledgers or bypass branch scope |
| Q-BE-05 | Reproduce and repair the previously reviewed fail-open/overwritten-filter concerns: unknown permissions denied, model enforcement active, multiple conditions composed safely, sync filters cannot override tenant restrictions, direct and related IDs scoped |
| Q-BE-06 | Central ingestion atomically stores deduplication identity, operation payload consistency, branch sequence, posted records, checkpoint and outbox. Parallel replay and failure before/after commit/ACK cannot create a second financial effect |
| Q-BE-07 | Transaction failure, replica-set primary change, duplicate unique key, timeout, service restart and consumer retry are tested. Redis/cache/queue loss cannot lose the authoritative operation or sole job record |
| Q-BE-08 | Master service credentials cannot impersonate company administrators or another branch; branch bearer tokens are rejected at central admin boundaries and the converse. Development/training data cannot enter live ingestion |
| Q-BE-09 | CMP company administration authors actual catalog/policy/report requests; pending/applied/rejected versions and offline branch freshness are truthful. Report field redaction/export/alert access is enforced server-side |
| Q-BE-10 | SDK/generator stale exports and related compile failures are resolved deliberately, not by inventing empty business models. Schema/client/Go/Kotlin protocol compatibility and unsupported-version rejection have tests |

The old generator investigation verified 66 source transformations only. It did not exercise endpoints, business transactions or production authorization; keep that historic evidence separate from the new suite.

## 9. Authentication, tenancy and transport security

Test each exposed local RPC, same-device adapter, central API, query, subscription, export and diagnostic route through a generated authorization inventory. Cover unauthenticated/admitted-only/user/service/recovery identities and organization/company/branch/owner scopes. Omitted scope and guessed IDs must fail closed. Related-record writes and projection caches are part of the attack surface.

| Gate | Required adversarial cases |
| --- | --- |
| Q-SEC-01 | Pairing token replay, wrong branch/device certificate or key, changed master identity, stolen/retired device, encrypted-transport failure and unauthorized procedure invocation; discovery is not trust |
| Q-SEC-02 | Real offline P2pKit discovery/session/RPC on supported platforms/topologies; no hidden public signaling/relay/cloud prerequisite for provisioned LAN checkout; startup without WAN is a separate case |
| Q-SEC-03 | Login guessing with persistent rate limit, process restart, credential reset/revocation, token replay/refresh rotation/expiry and local account disable; no plaintext passwords/tokens in logs or backups |
| Q-SEC-04 | Two users sequentially use one device and one account moves between admitted devices; Koin scopes/caches/UI/private drafts stay isolated while original unresolved operation attribution remains protected |
| Q-SEC-05 | Stale/changed amount, document, actor or scope invalidates a manager approval. Named self-approval applies only to expressly granted actions; no self-elevation by editing request roles/limits |
| Q-SEC-06 | Permission revocation delivered during an active session is enforced after atomic local activation on commands, reads, subscriptions, exports and cached sensitive views. Offline pending central intent is reported honestly |
| Q-SEC-07 | Malformed/oversized/deep payloads, integer overflow, invalid enum, duplicate fields, partial frames, decompression/decoding limits, request flooding and slow consumer cannot exhaust unbounded memory or bypass validation |
| Q-SEC-08 | Diagnostics, crash reports, analytics, screenshots/app-switcher state, copied data and notifications contain no credential secrets or unauthorized customer/margin data; exports have explicit scope and access |
| Q-SEC-09 | Reject cross-branch/environment/training-live replay and stale-authority writes, old bearer tokens or invalid replayed approval proofs. After an approved epoch change, a freshly authenticated authorized caller can look up/recover the same stable branch operation ID and receive its original committed result without repeating effects; epoch change is not itself a payload mismatch. A missing unreplicated outcome or late old-master write follows explicit reconciliation rather than automatic new execution |
| Q-SEC-10 | Dependency, secret and license checks are reviewed; known critical vulnerabilities affecting shipped paths have no unexamined exception. Crypto uses qualified platform/library primitives, not homemade encryption |

Record approved threat-model assumptions, especially the inability to stop an isolated stale device without real retirement/isolation controls. A new epoch on central or client discovery alone is not physical fencing.

## 10. Fault, race, recovery and reconciliation matrix

For command processing inject faults at receive/authenticate, validate, reserve/lock, write, commit, prepare response, client receive, outbox dispatch, central/standby commit and acknowledgement. For external terminal cashiers, also model actual payment occurring before an unavailable POS commit. The system must distinguish not submitted, pending, committed, rejected and physically paid but unreconciled.

| Gate | Required experiment and oracle |
| --- | --- |
| Q-RES-01 | Last unit; simultaneous return; kitchen start/cancel; drawer close/payment; handoff/payment; split/merge/edit; receipt/PO allowance; approval/policy activation — each race matches one valid state transition and conserved totals |
| Q-RES-02 | Repeated request after lost response and same ID/different payload across restart preserves original outcome/actor; transport request IDs are not the only durable deduplication identity |
| Q-RES-03 | 24-hour N2 workload with local login/refresh, sale, return, kitchen, count and receiving; no cloud lease expires ordinary operation; durable backlog survives restart |
| Q-RES-04 | Partition affects one client but master serves others; never promote by health timeout. Reconnecting clients revalidate identity and query pending outcomes before issuing fresh actions |
| Q-RES-05 | Planned transfer interrupted in Preparing/Draining/Sealed/Activated/Completed; idempotent handover ID/checkpoint; abort only before seal; after seal no arbitrary reopening of old writer |
| Q-RES-06 | Emergency replacement requires the specifically delegated branch user and prepared local recovery credentials; generic manager role, a backup alone or an internet heartbeat never appoints a master |
| Q-RES-07 | Old master offline during replacement, later restarts with stale role: operational isolation remains until controlled retirement/reconciliation; no automatic acceptance of competing writes or blind old outbox replay |
| Q-RES-08 | Restore from standby, central recovery package, old compatible schema, incomplete/corrupt/stale copy and no independent copy. Verify full checkpoint coverage and show possible missing tail; no fabricated zero-loss claim |
| Q-RES-09 | Recovery preserves accounts/delegation, open/parked orders, unknown payments, stock/reservations/kitchen, cash custody/counts, returns/deposits, purchase disputes, receipts, audit, outboxes and operation results |
| Q-RES-10 | No bearer-token resurrection or old device private-key cloning; new target obtains its own admitted identity and fresh sessions while historical actors remain unchanged |
| Q-RES-11 | Room crash/recovery, sync catch-up and printing run together while new orders proceed; backpressure remains bounded and no retry loop starves foreground commits |
| Q-RES-12 | Wall-clock jumps, Cairo timezone/day boundaries, changed timezone, long offline period and counter exhaustion do not duplicate documents, expire locks unsafely or reorder authority by wall-clock alone |
| Q-RES-13 | Incompatible app/schema/protocol update, interrupted installation and supported rolling client updates preserve pending work; incompatible participants show an actionable state without corrupting records |
| Q-RES-14 | Central totals lag or a recovered tail is incomplete: reports expose checkpoint/exception; cash/stock reconciliation compares ledgers and actual custody rather than deleting differences |

Healthcheck status and a completed backup job are not restore evidence. Execute at least one full replacement rehearsal with real client reconnection and subsequent sale/return/close. Test recovery without central connectivity whenever its documented prerequisite is a prepared local copy and delegated credentials.

## 11. Navigation 3, Koin, Coil and application behavior

Use current supported public APIs from the pinned compatible dependencies. Skill reference versions are prior verified examples, not a claim of today's latest versions. After relevant dependency/toolchain updates rerun the affected matrices; never copy private recognizer hacks or arbitrary transition constants from another version without inspecting current public behavior.

| Gate | Required checks |
| --- | --- |
| Q-NAV-01 | Every typed route has construction/boundary validation, stable serialization round-trip, route-kind registry, exactly one adapter and documented root/tab ownership; route data contains only bounded permitted identifiers |
| Q-NAV-02 | One logical state owner and mutation boundary; independent top-level stacks, named duplicate semantics, root protection, reselection and compact/expanded equivalence. No alternate platform stack or Navigation 2/3 hybrid |
| Q-NAV-03 | Stale entry/session callbacks, duplicate taps, inactive host, collectors restarted after navigation and 100,000 command submissions cannot replay actions or grow an unbounded queue/history |
| Q-NAV-04 | Every route's clean/dirty/dialog/search/critical-operation/root states map to one Back policy. Android system/predictive Back, iOS swipe, toolbar, Desktop Escape/Back and programmatic completion produce the same permitted result exactly once |
| Q-NAV-05 | Blocked/handled-in-place Back never visually reveals a previous protected screen. Forward navigation, tab changes and alerts cannot bypass an active leave guard |
| Q-NAV-06 | Cold/process recreation starts at authentication; route-only quarantine validates after login; wrong root/tab, oversized stack, unauthorized/deleted entity, repository failure or interrupted validation fails closed without even one protected frame |
| Q-NAV-07 | Logout/account switch clears sensitive entry/session state while the master service continues. A recovery-required financial operation remains tied to its original actor outside the next user's ordinary UI |
| Q-NAV-08 | Supported notification/deep-link adapters parse only approved grammars with bounded pending delivery, deduplicate by delivery ID, revalidate session/entity and construct canonical Back history; do not invent public links merely for testing |
| Q-NAV-09 | Entry removal/reset releases Koin ViewModels, coroutines, observers and registrations; retained tabs preserve intended state only. Reentry uses correct fresh/restored scope; old registration disposal cannot remove a new guard |
| Q-NAV-10 | Physical iPhone/iPad, Arabic RTL/English LTR: slow/full drag, short/fast flick, low-velocity release, reversal/cancel, repeated Back, opposite edge rejection, runtime language switch and background/lock/detach during gesture |
| Q-NAV-11 | Measure finger tracking and release settlement separately, including 60/120 Hz where shipped hardware provides it; public platform-specific adjustments preserve Android/Desktop behavior and library ownership |
| Q-NAV-12 | Navigation-host recompositions do not scale with 1,000+ pointer/key activity signals; measure frame time, retained entries/ViewModels and current-screen recomposition under scanning/scrolling |
| Q-DI-01 | Verify all Koin platform/feature graphs with required parameters and scopes; domain/service dependencies resolve without UI; no screen ViewModel singleton or hidden second database/master instance |
| Q-DI-02 | Application/master scope outlives cashier login. Human-session scope closes on logout/revocation; entry scope closes on pop/reset; collector/resource teardown is observable and leak-checked |
| Q-IMG-01 | Coil image requests/cache keys include appropriate company/catalog revision and authorization scope; switching accounts/companies cannot expose private media through memory/disk cache |
| Q-IMG-02 | Thumbnail sizing, decode limits, bounded cache and cancellation handle large/corrupt/unsupported images, offline placeholders, permission revocation, scrolling and repeated navigation without main-thread blocking or unbounded retained bitmaps |
| Q-IMG-03 | Test configured platform fetchers/decoders/HTTP stack and cache cleanup on all targets; image failures never prevent item selection, authoritative price/stock validation or local checkout |

Keep snapshot/layout tests for visual regressions, with semantic assertions for totals, focus and recovery actions. Screenshot approval alone cannot verify security, dispatch identity, data binding or a successful business command.

## 12. Localized simple UX and business rehearsals

All company administration, catalog, permissions, reports and configuration flows must work in the CMP application. Verify users do not have to open module-manager or a developer console for ordinary administration. Online company administration and locally delegated offline actions must show their actual authority/version boundary clearly.

| Gate | Required result |
| --- | --- |
| Q-UX-01 | Arabic/English resource, plural and placeholder parity covers shared and native prompts/notifications/menus; reviewed explicit allowlist only; no release fallback concealing missing translation |
| Q-UX-02 | RTL layout, directional icons, chart/table reading order, mixed-direction numbers/barcodes/serials and receipts; runtime language changes preserve the order and do not swap amount interpretation |
| Q-UX-03 | Touch targets, keyboard-only paths, scanner focus, shortcuts, text scaling, contrast, error announcements and TalkBack/VoiceOver/Desktop assistive behavior qualified where available |
| Q-UX-04 | Common sale is few clear steps with no routine technical dialogs; variant/modifier/weight selection asks only applicable information. Exceptions show a specific next action, not raw stack traces |
| Q-UX-05 | Test rapid scan, duplicate key-repeat, scanner-as-keyboard suffix, simultaneous touchscreen input, modal manager approval, payment uncertainty and print failure; no hidden second submit |
| Q-UX-06 | Resumable setup/import/training/live transition produces no duplicate opening stock/float and no training fiscal/sync data. Missing optional standby never blocks a valid one-device launch |
| Q-UX-07 | Full restaurant rehearsal: table/order/partial send/modifier/start/cancel/remake/split tender/split bill/handoff/served unpaid incident/later collection/shift close |
| Q-UX-08 | Full clothing rehearsal: variants/barcodes/discount/partial return/exchange/inspection/tender difference; remaining entitlement and cost reconcile |
| Q-UX-09 | Full supermarket rehearsal: fast scan/scale/pack/expiry/promotion/partial receipt/count/last-unit race/closing; price and unit conversions remain legible and exact |
| Q-UX-10 | Full purchasing/admin rehearsal: catalog import → price/policy release → partial delivery/inspection → invoice mismatch → authorized payment/credit → report with one offline branch |

Use synthetic pilot shifts with people representative of cashier, kitchen, inventory and company-head roles. Record task completion, mistaken actions, ambiguous recovery choices and assistive barriers. Pilot discoveries become explicit new scenarios and regression tests; do not promise that a finite document covers every future business.

## 13. Provisional performance and capacity qualification

These are starting engineering targets selected because real launch workloads and peripheral models are unspecified. They are **not measured capacity claims** and cannot silently become limits that remove a requested platform. Record device/OS, storage, build mode, database size, exact workload and network before comparing. If a profile misses a target, measure the bottleneck and either fix it or document a reviewed supported-capacity profile before deployment.

| Profile | Initial dataset/workload target |
| --- | --- |
| P0: single device | One master with its local cashier UI; 5,000 active SKUs; 500 sales/day; 100 concurrent open/parked orders; no standby |
| P1: ordinary branch | One master + 10 clients; 25,000 active SKUs; 5,000 sales/day; 500 open orders; at least 500,000 retained journal events |
| P2: stress/capacity | One master + 25 clients; 100,000 active SKUs; 20,000 sales/day; 2,000 open orders; one million retained journal events; 250,000 queued sync events |

Maintain independent food/clothing/supermarket mixes. Nominal load includes searches/scans, quantities, kitchen work, price queries, returns, reports and synchronization—not only an empty `ping`. Initial peak target is five committed sale/order transactions per second with at least ten mixed read/stock/kitchen operations per second at P2; record the actual number of journal events per transaction. Generate data fast during setup; measured operation runs use the real commands and storage.

| Gate/metric | Initial acceptance target and measurement rule |
| --- | --- |
| Q-PERF-01: local command | Nominal healthy LAN/qualified device: client submit to durable response p95 ≤ 500 ms, p99 ≤ 1 s; excludes human/terminal time, paper printing and central/standby acknowledgement. Checkpoint writes remain enabled |
| Q-PERF-02: lookup/input | Cached/local indexed product lookup p95 ≤ 150 ms; scan/key acceptance and navigation response p95 ≤ 100 ms. Measure stale/filtered query paths and actual rendering separately |
| Q-PERF-03: UI | Target smooth 60 Hz interactions on qualified baseline hardware; record p95 frame time, missed frames and startup time. Long import/sync/image work must not block input; lower-refresh devices are measured at their supported refresh |
| Q-PERF-04: backlog | Drain 250,000 queued events while new business continues; catch-up sustained throughput ≥ 2× the measured incoming event rate and bounded memory. Report elapsed time and restart/resume progress; no fixed internet speed is assumed |
| Q-PERF-05: offline/soak | Execute 24 h N2 operation and 72 h mixed connected/reconnecting soak at P1; ledger/entitlement reconciliation exact, no lost committed operation, crash, monotonic leak or unbounded queue |
| Q-PERF-06: memory | Establish platform budgets in the foundation benchmark before feature growth; record peak/RSS/managed/native/bitmap/queue usage. After warm-up and repeated scope teardown, retained resource counts return to their designed bound; do not use a single JVM heap number for iOS |
| Q-PERF-07: restart | Provisional same-master ready-to-serve target ≤ 60 s at P1 after process launch and healthy storage; excludes user reopening a terminated mobile app and OS boot time. Pending outcomes remain recoverable |
| Q-PERF-08: handover | Provisional planned handover target ≤ 15 min at P1 once prepared target/network are available; show drain/copy/activation times. No safety shortcut to meet a stopwatch; emergency recovery has measured checkpoint-specific RTO |

For total master loss, observed recovery point is the newest coherent independent durable checkpoint, not the latest UI success. Report local/standby/central sequence lag and possible missing operations. Planned handover can preserve the drained committed cut when verified; asynchronous disaster recovery cannot promise universally zero RPO.

Benchmark clean release-like builds on named physical hardware. A development simulator benchmark is a useful early signal but not deployment capacity evidence. Use identical datasets/conditions for before/after comparisons and retain raw results, rather than optimizing only an aggregate mean.

## 14. Required OS, packaging and physical peripheral matrix

Determine minimum supported OS versions, CPU architectures and actual device models during the foundation compatibility spike; record them before freezing dependencies and packaging. The unspecified facts do not authorize dropping an agreed platform. Desktop Windows/macOS launchers, native libraries and service lifecycle need their own runners; a macOS development build does not prove a Windows package.

| Surface | Required gate before its deployment claim |
| --- | --- |
| Windows | Debug/release compilation, packaged runtime installation/launch, single writer/service supervision, logout/UI-close independence, restart/upgrade/uninstall data policy, firewall/LAN permissions, real scanner/printer/drawer adapter |
| macOS | Same desktop requirements plus supported architectures, app privacy/network behavior, packaged native library loading, sandbox/entitlement/signing/notarization checks as applicable to distribution |
| Android | Release/R8/lint/resource/ABI checks, phone/tablet UI, foreground-service declaration/lifecycle and notification, permission denial, battery/OEM restrictions, screen-idle policy, manual lock, swipe-away/force-stop/reopen and local-network reconnection |
| iPhone | Simulator compile/integration plus physical app/master tests, local-network permissions, active-app screen-idle prevention, manual lock/background/termination truthfulness, reopen recovery, LTR/RTL gesture matrix, packaged framework/linkage |
| iPad | Physical compact/expanded/rotation/multitasking behavior, same hosting limits and recovery, touch/keyboard/RTL gestures, supported refresh rates; iPhone evidence does not replace tablet qualification |
| Printer/KDS | Selected LAN/USB/Bluetooth mechanisms per supported OS; Arabic/bidi/font/codepage/raster output, totals/QR/barcodes, paper out, cutter, connection loss/unknown response, explicit duplicate-copy marking and no duplicate preparation instruction |
| Cash drawer | Correct assigned physical drawer, open command permissions, unavailable/jammed/repeat request behavior and no fictional cash movement; opening hardware is separate from recorded custody |
| Barcode scanner | Real HID/camera/SDK paths chosen for each profile; leading zeroes, variants/pack/weighted formats, fast scans, focus restoration and malformed code handling |
| Scale | Named supported model/protocol; tare/zero/stability/unit/precision, disconnect/invalid readings, operator-approved manual fallback if permitted; no inferred legal calibration approval |
| External payment terminal | Synthetic manual attestation flows in automation; supervised real terminal receipt/cancel/refund reconciliation when the launch business authorizes it. Do not initiate real charges merely to run automated tests |
| Fiscal/accounting | Determine applicable Egyptian invoice/e-receipt regime and required fields/taxes/legal rounding, credentials and submission deadlines; validate official sandbox, retries/rejections/corrections and actual launch requirements before claiming compliant readiness |

Hardware-specific capabilities can be supported only on qualified adapter/OS combinations; explain unsupported combinations during setup. General app/platform support is not a promise that every printer or scale connects to every OS.

Unsigned compile/packaging can proceed locally. Production signing, store distribution, notarization and real fiscal/terminal operations need their actual authorized credentials and execution environment; do not fabricate evidence or put secrets into fixtures. Capture these gates as `BLOCKED` until satisfied, not as green source checks.

## 15. Continuous verification and independent review workflow

| Pipeline stage | Required execution and artifact |
| --- | --- |
| Before feature implementation | Read contract/scenario rows; create failing meaningful tests for new critical behavior; identify layer/platform gaps and deterministic fixtures |
| Each feature commit | Format/static checks and targeted L0–L2 suite; include new IDs/fixtures and assertions; author reports actual commands/results and known limits |
| Independent task review | Peer inspects code and tests, searches sibling paths for the same bug, verifies boundaries/negative cases and reruns high-risk tests; findings carry severity and exact locations |
| Main-agent integration | Merge compatible work without overwriting other agents; full common tests, affected real services/RPC/UI flows and cross-feature reconciliation; no assumption that unit-green implies integrated-green |
| Scheduled broad run | Native/JVM suites, representative cross-platform L3/L4, fault/property/recovery/load suites and dependency/security checks; exact runs selected by risk and changed boundaries |
| Release candidate | Freeze exact commits, schema/protocol/config/dependency lock and artifact hashes; run full applicable 141-scenario variants plus Q-gates, platform/physical/fiscal qualification and supervised rehearsal |
| After candidate changes | Mark affected evidence invalidated; rerun impacted and required integrated gates. Do not claim old evidence for a rebuilt or changed artifact |

Do not invent universal Gradle task names before the repository exists. The foundation task must create and document repository-supported commands for common/native tests, Room migrations, Compose UI, Android lint/release, iOS build/tests, Windows/macOS package tests, Go unit/race/integration, generation verification and traceability validation. CI invokes those real commands with explicit configurations and reports test counts, failures, skips and exit codes.

Library upgrades require a dated official-version/compatibility record, dependency locks/checksums where supported, release-note review and targeted regression tests. Pin an exact compatible stable set for reproducibility. Do not use floating `+`/`latest` selectors or automatically adopt an incompatible preview to satisfy “latest.” Recheck Kotlin/Compose/compiler/Gradle/AGP/JDK/Xcode, Nav3/lifecycle, Koin, Coil, Room/SQLite and P2pKit together.

## 16. Evidence format, traceability validation and release verdict

Each execution record includes:

- Scenario/Q-gate ID, concrete test name and source path, author and independent reviewer, fixture version/seed, scenario variation and expected invariant.
- Exact repository commits and dirty-state/diff identity, dependency lock, schema/protocol/config versions, build/artifact hash and timestamp.
- OS/device/architecture/master-client role, driver/peripheral model and network/failure profile; local service revisions and non-secret configuration identity.
- Real command/test runner result, test totals/failures/errors/skips, actual observable outcome, ledger/checkpoint assertions, relevant redacted logs/screens/video and performance samples.
- Verdict, bug link if failed, exact missing input if blocked, and evidence invalidation trigger. Synthetic fixtures never contain real service cookies, credentials or customer data.

Traceability validation must assert exactly one row for every current acceptance ID, no unknown/duplicate/missing IDs, nonempty owner/reviewer/layer/platform/profile/fixture/expected fields and allowed status values. Preserve the original trigger/outcome verbatim unless the source requirement itself is deliberately revised. New requirements add source scenarios and mappings; they do not silently reuse another case ID. Cross-feature tests may reference several rows; no critical row can rely exclusively on mocked/unit-only evidence.

The CSV starts with all statuses `NOT RUN`; implementation agents populate actual test references and separate execution records as work proceeds. A source audit or this document's structural validation must not change those statuses.

| Candidate verdict | Rule |
| --- | --- |
| READY | Required business variants and applicable Q-gates pass on the exact candidate; all agreed platforms/roles and deployment peripherals/fiscal obligations qualified; no unresolved critical integrity/security/data-loss defect; recoverability and operator rehearsal proven |
| CONDITIONALLY READY FOR A NAMED TEST/PILOT ONLY | Source/integration subset is proven, while explicitly listed physical/external facts remain blocked; state the allowed environment and restrictions. This is not production readiness |
| NOT READY | Failed/unexecuted critical behavior, unsupported required role/platform, missing transaction/auth/recovery guarantees, or no evidence for the proposed deployment |

Do not mark this project READY because tests compile, all CSV rows exist, a percentage is high, or the agent ran out of time. Final reporting states what changed, what actually passed, what remains untested/blocked and the concrete next action. The main agent owns the final assessment; independent reviewers supply evidence, not a substitute for integration responsibility.
