# POS executable task catalog

Revision 1.0, 21 October 2026. All tasks start `BACKLOG`; no implementation/test pass is implied. Use with the [implementation plan](POS_IMPLEMENTATION_PLAN.md), [domain blueprint](POS_DOMAIN_AND_CONTRACT_BLUEPRINT.md), [quality plan](POS_TEST_AND_QUALITY_PLAN.md) and [141-scenario traceability register](POS_TEST_TRACEABILITY.csv).

## Assignment and completion contract

Task IDs below identify reviewable work packages. A main agent assigns a current worker, independent reviewer, owned modules/files, exact dependency revisions and a bounded subtask before work begins. Family names are skills/responsibilities, not permanently reserved agents. Split large packages into explicit child work units; never assign an entire domain as one indefinite task whose review waits until the end.

Every package delivers source, meaningful tests, fixture changes, API/schema/migration documentation where applicable, self-verification evidence and an independently reviewed patch. Completion also requires integration verification on main's combined tree. Required scenario IDs are anchors, not a cap on tests; cover new implementation edges and applicable quality gates. Mock-only integration, empty tests and unexecuted physical claims cannot satisfy an exit.

Scenario references in an early package require that package's owned contract assertions and real boundary tests, not an impossible end-to-end dependency on later features. Keep the overall scenario `NOT RUN`/incomplete until all required layers are exercised; record partial evidence separately. T19 completes payment/delivery/incident cases, T22 completes purchase-to-settlement/return cases, T27 completes report/export/alert cases, and T33 validates their combined-platform rollups. Never introduce a circular dependency merely to mark an early scenario fully passed.

Root dependency/build/CI registries have one integration owner. Workers can work concurrently in distinct modules or isolated branches/worktrees. Do not modify another worker's files to make your tests compile without coordinating the contract change.

## Dependency and ownership map

Dependencies must be integrated contracts/implementations as appropriate before completing the dependent work. UI/fixtures can be prototyped earlier against an explicitly frozen contract, but the package cannot be marked integrated using unimplemented fake behavior. T28 is split into five independently schedulable native administration packages.

| ID | Work package | Owner family | Independent reviewer family | Dependencies |
| --- | --- | --- | --- | --- |
| T00 | Workspace/source preflight | FOUNDATION | QA-RELEASE | none |
| T01 | Dependency and platform spike | FOUNDATION | RECOVERY | T00 |
| T02 | Domain/contract/schema freeze | FOUNDATION | PAYMENTS | T00, T01 |
| T03 | CI, fixtures and evidence harness | QA-RELEASE | FOUNDATION | T00, T01 |
| T04 | Money, quantity, time and identifiers | FOUNDATION | CASH | T02, T03 |
| T05 | Room persistence and atomic command store | FOUNDATION | RECOVERY | T01, T02, T03, T04 |
| T06 | New YAML and protected central runtime | CENTRAL | AUTH | T02, T03 |
| T07 | Central management identity/permissions | AUTH | CENTRAL | T04, T06 |
| T08 | Device admission and local human auth | AUTH | RECOVERY | T04, T05 |
| T09 | Master hosting and RPC adapters | FOUNDATION | AUTH | T01, T05, T08 |
| T10 | CMP shell, Nav3, Koin and design system | UI-NAV | AUTH | T01, T02, T03, T04 |
| T11 | Catalog and policy definitions | CATALOG | INVENTORY | T05, T06, T08 |
| T12 | Prices, promotions, tax and charges | CATALOG | RETURNS | T04, T11 |
| T13 | Inventory/reservations/counts/transfers | INVENTORY | KITCHEN | T04, T05, T08, T11 |
| T14 | Cash custody and shifts | CASH | PAYMENTS | T04, T05, T08, T10 |
| T15 | Orders, ownership and bill structure | SALES | CASH | T08, T10, T11, T12, T13 |
| T16 | Tender recording and sale commit | PAYMENTS | RETURNS | T09, T12, T13, T14, T15 |
| T17 | Cashier UI and first real offline slice | UI-CASHIER | QA-RELEASE | T10, T16 |
| T18 | Recipes, kitchen and production | KITCHEN | INVENTORY | T09, T10, T11, T13, T15 |
| T19 | Fulfillment/delivery and exceptions | SALES | CASH | T10, T14, T16, T18 |
| T20 | Returns/exchanges/deposit entitlement | RETURNS | PAYMENTS | T10, T12, T13, T14, T16, T18 |
| T21 | Purchase orders, receiving and inspection | PROCUREMENT | INVENTORY | T08, T11, T13 |
| T22 | Supplier settlement and cost corrections | PROCUREMENT | CASH | T14, T21 |
| T23 | Branch upload and central ingestion | SYNC | RECOVERY | T05, T06, T07, T09 |
| T24 | Administrative distribution/activation | SYNC | CATALOG | T07, T11, T12, T15, T16, T18, T23 |
| T25 | Backup, restore and optional standby | RECOVERY | SYNC | T05, T08, T09, T19, T20, T22, T23, T24 |
| T26 | Master transfer/replacement and fencing | RECOVERY | AUTH | T25 |
| T27 | Reports, alerts and scoped export | CENTRAL | CASH | T14, T16, T18, T19, T20, T22, T23, T24 |
| T28A | Native company/branch/people administration | UI-ADMIN | AUTH | T07, T10 |
| T28B | Native catalog/pricing/policy administration | UI-ADMIN | CATALOG | T11, T12, T24, T28A |
| T28C | Native inventory/purchasing administration | UI-ADMIN | PROCUREMENT | T09, T13, T21, T22, T28A |
| T28D | Native reports/alerts/export administration | UI-ADMIN | CASH | T27, T28A |
| T28E | Native device/recovery administration | UI-ADMIN | RECOVERY | T26, T28A |
| T29 | Setup, import and isolated training | UI-ADMIN | INVENTORY | T10, T11, T13, T14, T24, T26, T28A, T30 |
| T30 | Peripheral adapters and routing | PERIPHERALS | KITCHEN | T09, T10, T16, T18 |
| T31 | Fiscal/accounting documents and integration | FISCAL | PAYMENTS | T06, T07, T10, T12, T16, T19, T20, T22, T23 |
| T32 | Security, observability and upgrade hardening | FOUNDATION | QA-RELEASE | T05, T09, T10, T23, T24, T25, T26 |
| T33 | System/chaos/load/device qualification | QA-RELEASE | RECOVERY | T17, T19, T20, T22, T24, T26, T27, T28A, T28B, T28C, T28D, T28E, T29, T30, T31, T32, T34 |
| T34 | Reproducible native candidate packaging | QA-RELEASE | FOUNDATION | T01, T03, T10, T32 |
| T35 | Pilot, final evidence and operating handoff | QA-RELEASE | CASH | T33, T34 |

T34 can build candidate packages before T33 physical qualification; T33 tests those exact packages. Any subsequent change produces a new candidate and reruns affected gates. T35 requires applicable external checks, not merely a green development build. Ordinary feature tests run with their packages; T33 is the combined-system gate, not the first time testing starts.

These are package completion dependencies. Main may assign an early storage/backup/activation protocol or screen child against frozen interfaces before downstream domain completion, then explicitly schedule its complete integration gate after all listed dependencies. Missing physical runners/fiscal credentials remain separate external gates while safe source, fixtures, available target builds and independent feature work continue. Do not mark unverified platform compatibility as proven or leave all workers idle waiting for a physical-only gate.

## T00 — workspace/source preflight

Inventory current files/dirty trees and applicable AGENTS/skills. Prepare isolated source checkouts for `ukkera-pos`, modules, viki-go and actual generator dependencies. Record exact commits, supported tools, existing local configuration and historical review differences. Read the real YAML generation workflow and preserve existing YAML hashes. Create the task/evidence/decision boards and a portable local startup outline. Do not touch live databases or repurpose existing credentials. Exit: source manifest, cleanly scoped changes and a documented executable discovery path; unresolved facts named without blocking independent work.

## T01 — dependency and platform spike

Use the technology guide and recheck current official artifacts. Lock a compatible stable set, distinguishing P2pKit's requested release candidate and Room's current major/API. Build minimal actual Room, RPC host/client, secure-storage, Koin scope and Nav3/Coil integration on the required families. Establish OS/CPU/runtime minima and packaging-native dependency requirements. Exercise local restart and same-device service access. Exit: recorded commands/results and dependency graph, not “should compile”; physical-only gaps are explicit. No permanent production architecture is based on an unsupported platform shortcut.

## T02 — domain/contract/schema freeze

Turn the blueprint into versioned command/query/event definitions and a complete field/relationship/index registry. Freeze money/quantity types, global domain IDs versus central storage IDs, tenant scopes, operation identity, source-quantity allocation and journal/epoch lineage. Probe generator ID/field/index behavior before freezing incompatible shapes. Publish OpenAPI/RPC/schema documentation with candidate versus actual endpoints clearly separated. Identify local-only secrets and protected financial writes. Exit: domain, central, UI and QA reviewers can implement against one coherent contract; breaking changes require a recorded compatibility decision.

## T03 — CI, fixtures and evidence harness

Create reproducible synthetic multi-company/multi-branch fixtures, fake clocks/ID generators, controlled failure injection, local central-service orchestration and master/client launch helpers. Add appropriate unit/integration/UI/target build lanes and machine-readable test results. Seed data must be isolated and reset only in explicit test environments. Preserve scenario IDs in test names/tags or mapping. Track failures/errors/skips, flaky-test ownership and missing runners. Exit: a deliberate failing assertion fails CI, artifacts identify the tested tree, and the first nontrivial real local integration test runs.

## T04 — money, quantity, time and identifiers

Implement exact scaled/decimal monetary and quantity operations, rounding/remainder allocation, UOM dimensions, overflow bounds, UTC plus Africa/Cairo/business-day handling and durable identifier generation. Preserve identifiers' leading zeros and distinguish server storage IDs from domain identities. Tests use independent examples/properties for conservation, mixed tax inclusion, fractional units, deadlines, day/DST boundaries and maximum/minimum values. Exit: CASH-03/05, TAX-04/05 and catalog/unit fixtures have deterministic cross-platform results; no binary floating point enters posted financial values.

## T05 — Room persistence and atomic command store

Build the verified current Room/SQLite layer with explicit transaction boundaries, uniqueness/foreign-key/index contracts, single-writer enforcement and durable operation/result/outbox persistence. Validate actual driver/platform durability settings and storage protection; a transaction API alone is not power-loss evidence. Export schemas and implement non-destructive upgrade/failure/restore paths from supported versions, including fresh versus migrated equivalence. Test races, cancellation, full disk, crash points and query plans. Exit: REL-04/08/10 and stock/payment atomicity primitives work in real persistence, with no destructive fallback.

## T06 — new YAML and protected central runtime

Create `BranchPos` definitions through the real generator in isolation. Generate required metadata, Go documents/registry and any unavoidable tool artifacts. Review generated diffs and verify existing YAML/definitions are preserved. Add protected central command/ingestion paths and guards for tenant, relation, list/direct-ID/bulk actions; eliminate or isolate the historical generic authorization weaknesses. Compile/start Go with the required Mongo topology and test actual local endpoints. Exit: generated schema matches the contract, commands enforce rules server-side, and no general CRUD path rewrites posted branch finance/stock.

## T07 — central management identity and permission

Implement central company-head/delegated identities, organization/company scope, management permissions, branch registration and intended policy versions. Native administration must authenticate directly to these central management APIs; it does not impersonate a branch cashier. Provision branch enrollment/account/recovery material through protected contracts. Enforce grants on queries, commands and exported fields. Exit: cross-company and self-elevation attempts fail across endpoint forms; central session cannot execute branch financial commands merely by having an admin title. Cover TAX-07 and CFG-03/05/06.

## T08 — device admission and local human authentication

Implement trusted device enrollment, branch-local credentials/session refresh/logout/revocation, login throttling, scoped roles and bounded approval grants. Keep device trust, master service identity and cashier identity independent. Support named offline recovery authority and company-explicit self-approval only where configured. Tokens reference current enforced policy; switching users clears private UI/session data but preserves unresolved operations with their original actors. Exit: OWN-01/02/03, CFG-01..08 and offline login/refresh tests pass without central access; service identity survives cashier logout.

For CFG-06/07 here, prove permission/query/recipient guard contracts; T27 supplies the real report/export/alert integration before those full scenarios can pass.

## T09 — master hosting and RPC adapters

Implement the portable master command/query runtime, P2pKit host/client contracts, secure discovery/pairing and bounded reconnect/cancellation behavior. Same-device access uses a supported authenticated adapter/IPC rather than bypassing authorization or relying on unsupported loopback RPC. Desktop hosting is supervised separately from the cashier window; Android foreground service and iOS active-app hosting obey verified OS APIs. Preserve intentional-stop/retired/handover markers during reopening. Exit: REL-01/03/04/07 and cross-client auth tests run over real transport; no client becomes an independent financial writer.

## T10 — CMP shell, Navigation 3, Koin and design system

Build typed minimal routes, central navigator, route registry, independent area stacks, entry-scoped ViewModels and explicit Back/forward-leave policy. Separate central-management and local-branch security roots and quarantine process-restored routes until authenticated/validated. Use Koin with app/master/session/entry scopes and Coil with bounded correctly scoped caches. Provide Arabic/English, RTL, accessibility, typography, amount controls, validation, empty/loading/error/permission states and adaptive shells. Exit: route/back/restoration/scope/RTL tests plus relevant platform builds pass; no duplicate navigation engine, global screen ViewModel or stale-session navigation.

## T11 — catalog and policy definitions

Implement company product/variant/attribute, UOM/pack/barcode, lot/serial, recipe/kit/service/deposit capabilities and branch assortment with validated combinations. Version price/policy/recipe definitions and preserve historic snapshots. Reject cyclic kits, ambiguous codes, illegal conversions and casual tracking changes. Support preallocated offline code namespaces or pending company publication where uniqueness needs central authority. Exit: CAT-01..05/08/09 and import/unit fixtures pass; sold-out physical SKUs cannot be relabelled non-stock to bypass inventory.

## T12 — prices, promotions, tax and charges

Implement deterministic company-context selection and branch override revision binding, promotions/stacking/limits, exact discount/refund allocations and inclusive/exclusive tax. Separate service/delivery/packaging/tips/deposit treatments. Preserve protected open-order snapshots and display any authorized repricing before collection. Do not change selling prices on supplier-cost updates or claim globally single-use offline campaign limits without allocated entitlement. Exit: TAX-01..06, PRC-01..06, original-price returns and rounding properties pass against independently calculated fixtures.

## T13 — inventory, reservations, counts and transfers

Implement quantity/custody/value ledgers, available stock calculation, reservations with unresolved-payment protection, lot FEFO, quarantine/expiry/serial tracking, opening quantities, counted adjustments and source/destination transfer evidence. Enforce no negative stock without manager bypass. Capture movement cuts for counts and deduplicate partial transfer receipts. Model moving-average cost independently of lot picking. Exit: STK-01..09, concurrent last-item races and allocation conservation pass; every movement traces to a guarded source command.

## T14 — cash custody and shifts

Implement business day, drawer assignment, counted opening, physical receipt/change/refund/pay-in/pay-out/drop/transfer, exclusive custody and closing cut/count/review. Separate actual cash from card totals, sales, expense categories and transfers. Unknown payments/custody remain explicit; later corrections link to an immutable close. Exit: CASH-01..12 including expected 2,350, actual 2,360, variance +10 and retained-float transfer 1,860 pass; no invented employee debt or duplicated money.

Own bounded CMP screen children for opening/closing, blind/visible count per policy, pay-in/pay-out, safe drop/receipt, custody transfer, pending items and variance review. Include permission, validation, interruption, Back and reconciliation UI checks. T20 later supplies complete refund-to-current-drawer integration.

## T15 — orders, ownership and bill structure

Implement drafts/parked/open orders, owner-restricted queries/subscriptions, versioned edit and handoff acceptance, scoped manager reassignment, line/portion snapshots and independent preparation/fulfillment/settlement states. Split/merge only compatible same-branch bills while preserving exact amounts and original stock/payment identities. Add collection/version locks without coupling human login to order or cash custody. Exit: OWN-03..11 plus split/merge concurrency and unauthorized direct-ID cases pass, including unchanged actors after handoff.

Own CMP floor/table layout and operational status, table/order selection/move, guest-count/seat allocation where enabled, owned-order handoff and split/merge screens. Table movement is not an ownership transfer or a stock/payment replay; actual kitchen/collection projections are integrated with T18/T19. Validate permissions and busy/dirty/unknown-operation leave guards in every flow.

## T16 — tender recording and durable sale commit

Implement split tender, actual cash/change, externally attested payment, remaining payable, uncertain outcomes, idempotent result lookup and atomic sale finalization. Validate stock reservations before collection and preserve them while payment is unresolved. Finalization consumes retail stock once and does not reissue kitchen ingredients. Separate printed receipt jobs from finance. Exit: PAY-01..14 and REL-04/10 as applicable, including failure after physical payment and before/after local commit; no fake bank authorization or central/standby dependency.

Use the owned tender/commit assertions for cross-feature PAY cases here; full courier/incident flows are completed in T19 and fully rolled up in T33, not mocked as already complete in T16.

## T17 — cashier UI and first integrated offline slice

Build real scan/search/variant/quantity/cart/park/handoff/payment/receipt/reprint flows, owned-order lists and drawer opening/closing with relevant exceptions. Connect to implemented domain/transport contracts, not a permanent fake repository. Run one-device checkout with no WAN/standby, then two clients competing for stock and switching users; restart after lost responses. Exit: the first end-to-end sale and same-session reconciliation works on named real environments, with UX-05..08 and full evidence. The slice does not mark unimplemented restaurant/procurement/recovery features complete.

## T18 — recipes, kitchen and finished production

Implement versioned recipes/modifiers, stations, per-portion task states, kitchen send/issue/start/cancel/ready/serve and incremental tickets. Reserve ingredients for prepayment, issue on send, reverse only verified unstarted portions and classify already consumed waste once. Handle multi-station start/cancel races and uncertain paper preparation. Finished batches create actual released output; virtual combos preserve each component's timing. Exit: KIT-01..15 and CAT-06/07 pass, including power failure/retry, mixed started quantities, remakes and insufficient replacement stock.

Own CMP KDS station/queue/ticket views, start/cancel-confirm/ready/served controls, partial-quantity actions, modifier/note presentation, remake/waste entry and finished-production/yield screens. Route narrow kitchen accounts to these views and test stale tickets, duplicate taps, print uncertainty, RTL and access boundaries. An API-only kitchen package is incomplete.

## T19 — fulfillment, delivery and exceptional orders

Implement dine-in/takeaway/delivery states, wrong-order linkage, merchant remakes versus customer changes, prepared no-show/failed delivery, partial collection and unpaid-fulfillment incidents. Courier collection, change and remittance use explicit custody; remittance is not another sale. Preserve the actual outstanding amount and later incident collection/write-off without fake payment or stock reversal. Exit: all exception rows in the operational specification and PAY-01..14 are mapped and verified; a 300 bill with 100 collected retains a 200 incident and later recovery does not duplicate revenue.

Own CMP dispatch/pickup/delivery assignment and status, courier COD/change/remittance, failed/no-show fulfillment, wrong-item/remake resolution, unpaid-incident collection/write-off and manager review screens. Expose only the assigned courier/kitchen projection; desktop and handheld use the same business states. Test partial/unknown settlement and ownership transitions through these real screens.

## T20 — returns, exchanges and packaging deposits

Implement same-branch original-receipt lookup, quantity/amount/serial entitlement, inspected return disposition, reason/category/deadline policies and pending refund. Exchange links a return/new sale with a restricted offset; preserve accepted return if replacement fails. Use original discount/tax allocations, original allowable tender and actual payout evidence; preserve prepared-food consumption. Optional container deposits have their own receipt-bound liability/return entitlement. Exit: RET-01..13, PRC-03 and CAT-10 pass, including concurrent partial returns, repeat refund attempts and claim versus payout timing.

Own CMP receipt lookup, return selection/inspection, claim/evidence/deadline, replacement reservation/difference, original-tender confirmation, refund-due/unknown-resolution and container-return screens. Every step uses actual entitlement and stock contracts. Test restricted manager approvals, Back/account switch and interruption without discarding accepted goods or refund obligations.

## T21 — purchasing, receiving and inspection

Implement supplier/item references, requisition/PO/revision, branch allocations, quick receipt, exact UOM snapshots, partial/excess/wrong-item receiving, accepted/quarantined/rejected custody and inspection. PO pending-inspection quantities cannot be received twice; rejection/replacement allowance cannot double-count fulfillment. No supplier invoice alone increases sellable quantity. Handle serialized/lot/expiry validation and discrepancy resolution. Exit: PUR-01..07/10/11 plus STK-06 pass; the 24-arrived/22-accepted/2-rejected case has correct custody, remaining claim and invoice mismatch.

PUR-01 payment/invoice settlement and PUR-11 supplier-return resolution receive full integration in T22. T21 verifies receiving/availability/claim contracts and leaves the whole scenario unpassed until those dependent effects exist.

## T22 — supplier settlement and valuation corrections

Implement invoice/receipt allocation and matching, terms, advances, partial settlement, duplicate detection/routing, supplier claims/return/credit/replacement and eligible landed cost. A global invoice has one settlement authority; offline cross-branch duplicates are honestly reconciled. Separate physical pay-out, supplier balance and inventory cost. Supplier return uses carrying value; credit amount differences are explicit variance. Exit: PUR-08/09/12..16 and carrying-value regression fixtures pass; no double expense, payment, stock or negative residual value from an incorrectly valued return.

## T23 — upload and protected central ingestion

Implement durable bounded branch outbox, per-journal lineage/cursors, authenticated upload, central durable ingestion/idempotency and acknowledged cursors. Central ACK follows the required durable commit; retry cannot rerun business commands. Preserve original actors, snapshots and source event identities across restarts/recovery. Quarantine malformed/conflicting events with diagnostic recovery, rather than dropping them or advancing past an unaccounted gap. Exit: REL-02/04/09, central outage/reconnect, duplicate/reordered/lost ACK and 250,000-event backlog profiles reconcile exactly.

## T24 — administrative package distribution and activation

Implement central native-admin submissions, pending/version/conflict handling, distributed packages, master staging/validation/atomic activation and application status. Apply company-priority price updates, delegated branch overrides, roles/account revocation, recipes and configurable open-order repricing without corrupting active operations. An offline global draft is not applied authority. Exit: TAX-01..03, CFG-05 and active-login/collection/kitchen revision races pass; user-facing status distinguishes central acceptance from each branch's activation.

## T25 — backup, restore and optional standby

Implement coherent snapshot/journal recovery, integrity/authentication/encryption under the verified storage design, bounded streaming and optional asynchronous standby replication. Include accounts/protected recovery access, policy/order/stock/kitchen/cash/return/purchasing states, operation results, locks/outboxes and journal ancestry. Never clone private device identities or reactivate old bearer tokens. Test corrupt/truncated/old-schema restores and interrupted snapshot creation. Exit: REL-02/08/09/10 and recovery fixtures conserve all acknowledged effects; missing tail is disclosed and absence of standby never delays a sale.

## T26 — master transfer, replacement and fencing

Implement Preparing/Draining/Sealed/Activated/Completed handover, persistent stop/retirement, preprovisioned named delegation and authenticated target identity. Safe abort is allowed only before seal; subsequent continuation cannot reactivate the old writer. Emergency replacement requires real operational fencing/isolation plus a verified recovery state. Handle concurrent approvals/targets, returning old device and client reconnection/session reauthentication. Exit: REL-05/06/07/09 and independent partition drills prove at most one writable authority; bumping an epoch alone is not accepted as fencing.

## T27 — reports, alerts and scoped exports

Build reconcilable read models for sales/collections/tenders, shifts/cash/custody, tax/charges/discounts, stock/cost/expiry, kitchen/waste/remakes, incidents/returns, supplier balances and synchronization health. Company head controls company/branch/own scope and sensitive fields/export separately. Show stale/incomplete company totals. Alerts deduplicate and enforce recipient access; acknowledgement does not resolve the business obligation. Export uses bounded memory and protection appropriate to contents. Exit: TAX-09, CFG-05..07, report-to-ledger reconciliation and unauthorized projection/cache/export tests pass.

## T28A — native company, branch and people administration

Build the actual CMP company-head login/workspace, organization/company/branch selection, profiles, branch onboarding initiation, users/roles/delegations and pending policy views. Central access works from an unpaired remote administrative device where authorized. Entering branch operations requires a separate local account; switching company clears inappropriate caches and protected navigation. Exit: native flows exercise real central endpoints with tenant/permission tests; no company web portal or hidden branch-master dependency is required for remote administration.

## T28B — native catalog, pricing and policy administration

Build adaptive product templates, valid variants, recipe/modifier/kit editing, barcode/UOM/tax-inclusion settings, bulk import preview, price contexts/promotions and return/approval policies. Provide safe dry-run/overlap/rounding previews and publication/branch-activation status. Offline work is a draft unless a delegated local action is explicitly used. Exit: CAT/PRC/TAX/CFG scenarios apply through the UI and real APIs; numeric settings are validated and advanced controls do not overwhelm ordinary item entry.

## T28C — native inventory and purchasing administration

Build stock/lot/serial/custody views, counts/transfers, quick receiving, PO/inspection, supplier invoices/matching/settlements/returns/claims and cost correction screens. Separate ordered, physically held, sellable, billed and paid quantities/amounts clearly. Respect source branch authority and designated settlement branch for cross-branch views. Exit: representative full purchase-to-settlement, partial receipt, rejection/replacement and count/transfer cases work through native screens; no UI action directly overwrites ledger balances.

## T28D — native reports, alerts and export administration

Build role-aware company/branch reports, filters, data-freshness indicators, financial drilldown, export and alert rule/recipient settings. Optimize large result views with bounded paging and adaptive detail, without fetching prohibited cost/customer fields. Exit: report numbers reconcile to implemented ledger sources, access changes invalidate applicable views, and Arabic/English export/formatting is verified with large datasets and failure recovery.

## T28E — native device and recovery administration

Build device admission, master/standby health, last safe recovery checkpoint, pending replication, named delegation, planned transfer, approved emergency recovery and returning-device workflows. Explain consequences with business-readable text and show missing tail/fencing steps honestly. Device role controls never bypass local permission/recovery contracts. Exit: administrators complete a rehearsed transfer and interrupted recovery through the actual app; a second target, stale approval or retired device cannot become an independent writer.

## T29 — setup, import and isolated training

Build the resumable company/branch wizard covering activity/currency/timezone/fiscal identity, policies, local accounts/recovery, master/pairing, catalog/opening stock/value, drawers/opening float and applicable peripherals. Implement stable import identities, validation/dry run and resumable checkpoints. Training fixtures/environment are physically/logically separated from live finance and fiscal submission. Exit: UX-01..04, duplicate import/opening retry and failed setup resume tests pass; absence of an optional feature/standby does not block a healthy configured branch.

## T30 — peripherals and routing

Define explicit capability adapters for receipt/kitchen printers, scanners, scales and drawers per OS/interface/model. Implement routing, custody, queued jobs, persisted print identity/status and marked reprints. Unknown physical print outcome is not silently converted into duplicate kitchen production. Support manual product/weight workflows only where policy permits and without claiming an unavailable hardware integration works. Exit: KIT-07/08, STK-05, UX-05/08 and actual supported-model checks pass; mocks validate logic while missing physical model checks remain external gates.

Own CMP peripheral discovery/assignment, printer routing, test output, job status/reprint and missing-device recovery screens used by branch setup and daily operation.

## T31 — fiscal and accounting integration

Implement immutable receipt/tax/correction numbering and allocation, accounting export and applicable Egyptian fiscal document/submission adapters. Determine actual launch registrations/classification/credentials and legal outage rules before declaring that integration qualified; do not assume an inclusive-price checkbox settles compliance. Durable asynchronous submission records pending/rejected/accepted status and idempotent corrections. Exit: TAX-04..09 and real available sandbox/contract fixtures pass; unavailable credentials/classification are named gates, with other development continuing and no fabricated fiscal acceptance.

Own CMP fiscal configuration, submission/correction status and authorized recovery/retry views, plus accounting export status. Include supplier cost/settlement and supplied-unpaid mappings from T19/T22; a sales-only export cannot claim complete operational accounting coverage.

## T32 — security, observability and upgrades

Complete role/tenant/direct-ID/relationship/bulk guards, secure storage, redaction, attachment bounds, application/session privacy, storage-pressure and bounded-backlog behavior. Add master latency/error/queue/backup metrics and diagnostic exports excluding secrets/private records. Implement controlled schema/protocol upgrades, compatible clients and pending-work recovery; incompatible downgrade is explicit. Review native lifecycle/cancellation/resource cleanup. Exit: negative security suites, REL-08/10, upgrades with pending financial/kitchen/refund/receiving work and memory/resource soaks pass on available profiles.

## T33 — combined-system qualification

Run the 141 scenario mappings and applicable additional quality gates against the integrated source and actual candidate packages. Exercise all required master/client pairings, central/standby partitions, concurrency/fault injection, crash/recovery, cross-company authorization, price/policy races, long offline periods, backlog catch-up, performance and 72-hour soak. Test actual hardware/lifecycle/RTL gestures and fiscal/peripheral integrations when supplied. Exit: exact outcomes/evidence and a complete defect register; missing mandatory external cases stay pending and prevent a blanket release-ready verdict. Do not hide skips or replace hardware with mocks.

## T34 — reproducible native candidate packaging

Create development identities and reproducible Android, iOS, macOS and Windows candidate builds with required resources/native dependencies, legal notices and source/version/dependency manifests. Verify optimized builds, platform-specific signing/package configuration where credentials exist and installed launch/update behavior. Unsigned validation remains labelled unsigned; actual signing/store promotion is a separately evidenced action. Exit: artifact hashes identify the exact tested tree/toolchain and package inspection/launch checks; no rebuild of different bytes silently inherits old test approval.

## T35 — pilot, final audit and operating handoff

Rehearse realistic clothing/supermarket and restaurant/cafe shifts with ordinary operators: setup/opening, rush sales, mistakes/returns, kitchen/delivery, purchasing, count/close, outage and master recovery. Resolve discovered state/money/custody/stock issues and extend scenarios. Deliver local/deployment/update/rollback/recovery/peripheral runbooks, supported model/capacity matrix, new YAML/source patches, candidate artifacts, source/test evidence and outstanding external facts. Exit: main agent's reviewed verdict accurately distinguishes implemented/automated/device verified/release ready; no remaining mandatory task is hidden behind a progress percentage.

## Mandatory handoff between workers

Use this record whenever a task reaches review:

```text
Task ID / bounded child:
Source repositories and exact revisions:
Owned changed files/modules:
Behavior implemented and contract version:
Dependencies used; any deviations/ADR:
Tests/scenario IDs; command, environment, totals and outcome:
Failure/race/retry/permission cases exercised:
Schema/migration/API/fixture/document changes:
Known limitations and external gates:
Reviewer and frozen patch revision:
Next independent READY task / queued peer review:
```

Reviewer response records findings with file/behavior/evidence, resolved status and the actual re-reviewed revision. Main records the integrated revision and combined checks. If the task changes after approval, re-review the affected diff; never rubber-stamp an amended implementation using the earlier approval.
