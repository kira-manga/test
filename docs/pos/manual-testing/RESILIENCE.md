# Existing independent resilience and real-world rehearsals

**NOT RUN.** These 70 cases reuse the frozen `scope-v1` actions, fixtures and assertions unchanged. Real-world reruns do not replace their required automated integration variants. Use [the result template](RESULT-TEMPLATE.csv); append separate RSC case/variant rows when executing.

## Fixture definitions

### RF-IDENTITY

Already-provisioned isolated local branch, not central service emulation

- Synthetic O1 has clothing C1 branches B1/B2, food C2 branches B3/B4; unrelated O2. Each local branch has its own actual production database and durable admitted master identity.
- A and B are separate cashier accounts in B1 with separate drawers. Same human A has separately credentialed A2 in B2. M is branch manager without recovery delegation. R is named recovery delegate for B1 only. KDS and courier have only assigned projections. D1/D2 admitted; X unadmitted; OLD retired; T prepared recovery target.
- Qualification bootstrap may establish synthetic already-registered branch trust only. Thereafter execute production local employee create/activate/reset/disable and device admission. Use unique generated protected credentials, never production-default administrator or copied credentials.
- WAN and central are disconnected unless a later owner-approved central run is separately recorded. Initial real central registration and central recovery source are not claimed.

Reset: New isolated fixture identity/database per independent numerical case; never reset live data.

### RF-RETAIL

Exact business commit oracle

- In B1, sellable SKU-A has10units at cost20 EGP each; permitted sale unit price100 EGP, zero-tax synthetic policy (not a launch tax classification). A opens assigned drawer with1000 EGP. No earlier sales/returns/cash movements.
- Order for2units locks payable200. Cash received250/change50 produces one net cash+200, drawer1200, sellable quantity8, inventory value160, sold cost40. Return1 sellable and actually refund100 gives drawer1100, stock9/value180, remaining sale entitlement1unit/100.
- Final counted close1100 has variance0 and one immutable close; later retries do not add money or stock.

Reset: Each run, fault point and ordered platform pair starts independently.

### RF-EXTERNAL

Physical payment before uncertain local commit

- SKU-A10@cost20, price100, drawer1000; reserve1unit before a separately simulated external terminal payment100. Persist original operation/actor/attestation before submission.
- If production transaction did not commit: stock is not issued, sale absent, reservation/evidence stays protected until controlled resolution; physical terminal payment is not reversed by a POS error.
- If it committed: one sale100, stock9/value180, no drawer increase, original cashier attestation100; retry returns same result. Do not initiate any real terminal charge.

Reset: Restore pre-case seed separately for each fault schedule.

### RF-RECOVERY

Cross-domain coherent recovery cut created using real production commands

- Use RF-IDENTITY and initial master epoch4. Actual journal cut is captured after listed commands; never assume a generated implementation sequence number.
- Open A drawer1000. SKU-A10@20, sell2@100 cash250/change50: drawer1200, stock8/value160. Accept1 returned A into quarantine at original cost20 with cash refund100 still due: sellable-on-hand8, quarantine1, return entitlement remaining1unit/100, refund due100.
- Reserve1 of the8 sellable A for an unknown external collection100: available A7, reserved1, no extra issue or drawer movement. Keep protected original actor/operation and physical attestation.
- Send2 burgers from10bread/1500g meat, recipe1bread/150g each: on-hand bread8/meat1200g. One portion Preparing, one Queued. A separate prepaid-unsent burger externally paid50 reserves1bread/150g: available bread7/meat1050g. No early second ingredient issue.
- Create pending safe transfer50 from drawer: drawer1150, unresolved safe custody50. Separate service supplier invoice1000 has historical already-allocated advance200; pay cash500 now and then receive financial credit100: drawer650, remaining service payable200. The historical advance never reduces this drawer again.
- Receive SKU-C24units,22accepted@10 and2damaged supplier-owned rejected held: physical24, sellable22/value220, rejected-held2/value0 in purchased inventory. Supplier claim invoice240 retains mismatch20 and unresolved2unit fulfillment decision; no automatic replacement double allowance.
- Service check300 receives cash100 then unpaid incident200: drawer750. Separate COD service75 delivered/customer cash paid/courier holds75 unremitted; no drawer increase. Formula1000+200-50-500+100=750.
- Separate prior externally paid SKU-E1@100 is returned inspected, reserved into an exchange for SKU-R1@130: return offset100 restricted to that exchange, replacement reserved1, difference30 pending. Preserve accepted return if replacement fails; do not add spendable balance.
- A receipt-linked external deposit10 for one container is outstanding with remaining entitlement1; no cash effect. Original container and liability/tax snapshots retained.
- Include owner-private parked order, pending handoff with A still owner, policy/catalog/recipe revisions, local account verifiers/throttle/recovery grants, clients/admission history, print unknown job, pending local config inbox, central upload outbox, audit, source allocations, numbering and all durable operation results.
- Capture an independent expected identity/state/amount manifest from specified fixture commands and compare production reads at checkpoint. Never populate expected recovery state by copying the implementation snapshot as the oracle.

Reset: Start a fresh real branch seed and execute listed source actions for every independent restore; checkpoints encrypted and synthetic only.

### RF-UI

UI states and locale without implementation-derived expected routes

- Branch sign-in, cashier/order/collection/receipt, kitchen, inventory/purchasing, local management/recovery and separate company-admin/offline-draft security areas from original baseline.
- Clean, dirty unsaved edit, search/dialog handled-in-place, collection outcome unknown, kitchen cancellation awaiting confirmation, refund due and root entries. Arabic RTL/English LTR, large text, long mixed-direction labels, barcode000123 and amounts114.00/100.00.
- Executors bind the frozen required route behaviors to a separately inventoried public route/adapter list. Missing required screen is a failure/gap, not a reason to delete the expectation. No invented deep-link scheme.

Reset: Clear private session/entry state while preserving any deliberate durable original operation fixture.

### RF-SCALE

Original provisional capacity profiles

- P0: one-device master5000SKUs/500sales-day/100open orders/no standby.
- P1: master+10clients/25000SKUs/5000sales-day/500open orders/500000journal events.
- P2: master+25clients/100000SKUs/20000sales-day/2000open orders/1000000journal events/250000queued events.
- Mixed food/clothing/supermarket search/scan/order/kitchen/stock/return/report workload; measured operations use actual production commands and storage. Deterministic schedule and independently reconciled expected ledgers.
- Targets: durable command p95<=500ms/p99<=1000ms; lookup p95<=150ms; input/nav p95<=100ms; smooth60Hz where supported; backlog>=2x measured incoming event rate; same-master P1 ready<=60s; planned P1 transfer<=15min. Provisional, never an excuse for unsafe shortcuts or unsupported-platform exclusion.

Reset: Fresh named synthetic dataset/seed per comparison; record real named hardware and release-like build.

## RSC-001

### Production local enrollment and minimal prelogin access

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- D1 admitted, X unadmitted, R local recovery delegate, M local administrator within bounded account/device grant; no cloud service.

**Steps (unchanged):**
1. Through real local admin UI create and activate a new employee; pair X under permitted local admission and persist trust.
2. Before login call each available bootstrap/read/mutation class as unadmitted and then admitted-only X; login, restart X/master and reconnect.

**Expected results (unchanged):**
- **RSC-001-A01**: Only the declared minimal authentication/bootstrap projection is accessible before employee login; no stock/customer/order/finance mutation or private data.
- **RSC-001-A02**: New employee can authenticate locally using its own established verifier; admission alone never becomes employee authority and wrong-branch/replayed enrollment fails.
- **RSC-001-A03**: Trust and account identity survive restart, no default admin or repeated central enrollment; normal production local create/reset/disable paths are exercised.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-04, REL-01, Q-SEC-01, Q-SEC-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-002

### Core business flow across all25 ordered native role pairs

**Environment:** EACH_OF_25_ORDERED_PHYSICAL_ROLE_PAIRS; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- For EACH of25 ROLE_PAIRS, use two distinct named physical devices/packages; include each same-platform two-device pair. WAN blocked, optional standby absent.

**Steps (unchanged):**
1. Pair/login client to actual master, open counted drawer1000, scan/select2 SKU-A, collect cash250/change50 and issue receipt.
2. Return1 sellable through original receipt, actually refund100 and counted-close1100; restart master/client then query original IDs.

**Expected results (unchanged):**
- **RSC-002-A01**: Sale payable200, one net cash+200, stock8/value160; return100 makes drawer1100, stock9/value180, remaining entitlement1/100 and close variance0.
- **RSC-002-A02**: All actions use production encrypted RPC/auth/Room rather than demo persistence; restarted result/actors/receipt remain identical.
- **RSC-002-A03**: Each pair has separate evidence/status. One platform/simulator result cannot satisfy another pair; no central or standby ACK prerequisite.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_P2PKIT, L4_INSTALLED_BUSINESS_E2E, L6_PHYSICAL.

**Related requirements:** REL-01, UX-05, UX-07, Q-SEC-02, Q-UX-03, Q-UX-05.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-003

### Protected one-device gateway parity on five master platforms

**Environment:** EACH_OF_FIVE_MASTER_PLATFORMS_PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Run separately on Windows/macOS/Android/iPhone/iPad with no other client device and no WAN/standby.

**Steps (unchanged):**
1. Execute RF-RETAIL through installed local cashier and the authenticated same-device service adapter.
2. Attempt equivalent local request without admitted/current employee context; logout while master continues; open second app/service instance.

**Expected results (unchanged):**
- **RSC-003-A01**: Same exact200sale/100refund/1100close and9remaining stock as remote flow; no direct DAO or permissive in-process shortcut.
- **RSC-003-A02**: No-auth request and competing writer cannot post; master keeps its same role and service independently of cashier logout.
- **RSC-003-A03**: Same-device path is supported local adapter/IPC, not unsupported P2pKit loopback or second local database.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-01, UX-04, UX-07, Q-DB-05, Q-DI-01, Q-SEC-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-004

### 24-hour offline existing-branch operation without leases

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-SCALE

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- P1 deterministic24h schedule with local login/refresh, sale, return, kitchen, count and receiving; WAN blocked from startup, standby absent.

**Steps (unchanged):**
1. Run actual production commands and normal employee sessions across expiry/re-login periods and scheduled same-master restarts.
2. Save acknowledged operations and independent expected money/quantity/entitlement totals; observe local upload backlog and health.

**Expected results (unchanged):**
- **RSC-004-A01**: No cloud permission/token/master lease or recurring setup requirement interrupts otherwise healthy operation.
- **RSC-004-A02**: Every committed command survives restart with original result/actor; cash, cost, stock and entitlement reconcile exactly to schedule.
- **RSC-004-A03**: Backlog durable, queues/memory bounded; actual storage failure reported honestly rather than hiding/discarding work.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-01, UX-04, Q-RES-03, Q-PERF-05.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-005

### Partition one client while others use same master

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL, RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- D1 and D2 connected; partition D1 only, master remains healthy for D2; D1 has private draft and original unknown external operation.

**Steps (unchanged):**
1. D2 completes its own allowed transaction; D1 attempts financial finalization/replacement after timeout.
2. Reconnect D1, revalidate identity/session and query original pending operation before any new submission.

**Expected results (unchanged):**
- **RSC-005-A01**: D2 continues normal durable operation; D1 cannot finalize from cache, central CRUD or automatic promotion.
- **RSC-005-A02**: D1 private drafts and original actor/evidence remain protected; no phantom result or second charge.
- **RSC-005-A03**: Reconnection recovers current versions and exact prior result; endpoint timeout never grants authority.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-03, REL-01, Q-RES-04, Q-SEC-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-006

### Discovery is not trust and endpoint change is not replacement

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Trusted master M1 changes LAN address; adversary X uses old address and another discovery name; retired OLD presents previous membership.

**Steps (unchanged):**
1. Reconnect by discovery then approved numeric endpoint; restart client to test durable fingerprint.
2. Attempt wrong branch/certificate/key, pairing-token replay, retired identity and transport-security failure.

**Expected results (unchanged):**
- **RSC-006-A01**: Same verified identity may update endpoint; X/OLD cannot impersonate master or invoke unauthorized procedures.
- **RSC-006-A02**: No plaintext/permissive fallback, transient in-memory trust replacement or user-independent financial authority.
- **RSC-006-A03**: Denied connection cannot expose private bootstrap data or mutate any branch records.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-03, REL-06, Q-SEC-01, Q-SEC-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-007

### Offline discovery topologies and network permission failures

**Environment:** QUALIFIED_LAN_AND_ALL_NATIVE_PLATFORM_PROFILES; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Named supported LANs with multicast off, guest peer isolation, IPv4/IPv6/interface change, denied/revoked OS local-network permission and reconnect storm.

**Steps (unchanged):**
1. Start without WAN, test actual P2pKit discovery/RPC and approved manual endpoint with pinning.
2. Deny connection permission or isolate peers; restore permitted LAN and recover outstanding operations.

**Expected results (unchanged):**
- **RSC-007-A01**: No hidden public relay/signaling prerequisite; manual endpoint works only on reachable supported topology with verified trust.
- **RSC-007-A02**: Blocked topology/permission gives actionable unavailable state, not false checkout or new writer.
- **RSC-007-A03**: Outstanding calls and retries bounded; restoring network does not replay collection.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-01, REL-03, UX-04, Q-SEC-02, Q-SEC-07.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-008

### Commit then lose response and restart

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Original persisted operation ID, order2units/payable200, cash250/change50; deterministic stop after durable commit but before client response.

**Steps (unchanged):**
1. Submit production collection/finalization; suppress reply and terminate master.
2. Restart same master, reauthenticate as original authorized user, query/retry same operation with same semantic payload.

**Expected results (unchanged):**
- **RSC-008-A01**: Exactly one200sale, net cash+200, stock8/value160 and result/audit/journal/print-upload intents for original actor.
- **RSC-008-A02**: Same result/document/issue identities recovered despite new transport request/session; no re-charge, re-issue or duplicate receipt number.
- **RSC-008-A03**: Master epoch unchanged on same-device restart; outboxes durable but physical jobs not blindly replayed.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-04, UX-06, Q-DB-03, Q-RES-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-009

### Failure before commit after external physical payment

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- External terminal event100 is synthetic evidence already received; inject production storage failure at each precommit boundary independently.

**Steps (unchanged):**
1. Try to record original attestation, inspect returned honest outcome and persistent client evidence.
2. Restore storage, query original operation then resubmit only same operation through explicit permitted recovery.

**Expected results (unchanged):**
- **RSC-009-A01**: No false committed sale on failed transaction and no partial stock/cash/result/outbox; physical evidence and protected reservation are not erased or declared bank-failed.
- **RSC-009-A02**: After safe resolution, at most one100sale, stock9/value180, drawer unchanged1000 and original actor/reference.
- **RSC-009-A03**: UI does not ask for another external charge merely due to missing result.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-10, UX-06, Q-DB-03, Q-DB-06, Q-RES-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-010

### Cancellation is not rollback after possible admission

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-RETAIL, RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Separate schedules: cancel before dispatch; cancel after admission before commit; cancel after commit before response; UI Back/close and transport timeout.

**Steps (unchanged):**
1. Submit/cancel via real client and production service with deterministic barriers, then terminate/reopen client.
2. Lookup original operation and inspect independent production ledger before authorizing any next action.

**Expected results (unchanged):**
- **RSC-010-A01**: Before actual admission no business effects; after possible admission outcome is explicitly unresolved until authoritative lookup.
- **RSC-010-A02**: Atomic transaction is all-or-none; cancellation never fabricates success or re-labels a committed action as rolled back.
- **RSC-010-A03**: If committed, exact fixture result once; if not, retained original external evidence/reservation resolves under same identity.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-04, REL-10, UX-06, Q-DB-03, Q-RES-02, Q-NAV-04.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-011

### Durable same-ID content and actor mismatch rejection

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-RETAIL, RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Commit original RF-RETAIL sale as A, then reuse ID with quantity3, tender amount300, different original actor B, or another branch.

**Steps (unchanged):**
1. Send changed variants before/after restart; also repeat original semantic payload with a fresh valid A session and changed transport correlation.
2. Read independent ledger/result and protected audit.

**Expected results (unchanged):**
- **RSC-011-A01**: Changed content/actor/scope rejects without second effect or private result leakage; original result immutable.
- **RSC-011-A02**: Same semantic command by authorized original/recovery context recovers original200/net200/stock8 despite changed transport/session.
- **RSC-011-A03**: Do not generate a new ID to hide a mismatch.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-04, UX-06, Q-RES-02, Q-SEC-09.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-012

### Concurrent duplicate original operation

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Two admitted clients submit same allowed actor/operation/semantic payload concurrently using deterministic barrier; repeat across fresh service processes.

**Steps (unchanged):**
1. Release requests together, lose one response and restart.
2. Recover operation and compare fresh ledger/audit/source IDs.

**Expected results (unchanged):**
- **RSC-012-A01**: One200sale, one stock issue2/cost40, one cash+200 and one source operation result; both authorized retries resolve same outcome.
- **RSC-012-A02**: Uniqueness survives concurrent first submission/restart; no merely in-memory mutex/correlation guarantee.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-04, Q-RES-01, Q-RES-02, Q-DB-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-013

### Not-recorded lookup races in-flight commit

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Original handler paused after admission, before commit; client lookup occurs before durable result exists.

**Steps (unchanged):**
1. Query and receive in-progress/not-recorded/unknown according to actual allowed contract; resume handler while client retries SAME ID.
2. Inspect final outcome and reservation/physical evidence.

**Expected results (unchanged):**
- **RSC-013-A01**: A temporary not-recorded result is not cached as proof of payment failure or permission to issue a new operation/charge.
- **RSC-013-A02**: One valid committed result or explicit unresolved/rejected no-effect state; no duplicate stock/cash/attestation.
- **RSC-013-A03**: Physical external100 remains accounted for even if local operation is rejected.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-04, REL-10, UX-06, Q-RES-02, Q-DB-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-014

### Serializable cross-domain races conserve totals

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Independent fixtures: last unit1/two buyers; receipt remaining entitlement1/two returns; queued burger/start-versus-before-start-cancel; drawer open/pay-versus-close; A-owned order/handoff-versus-payment; compatible split-versus-edit; PO remaining2/two receipts2; approval revision1 versus edited revision2; policy revoke versus newly requested action.

**Steps (unchanged):**
1. Use deterministic barriers to submit each conflicting pair against actual production services and repeat both allowed orderings.
2. Kill after selected commits and reread quantities, money, actors and operation results.

**Expected results (unchanged):**
- **RSC-014-A01**: Last unit sold/reserved at most1; return at most remaining1; PO received at most2; losing command leaves no partial effects.
- **RSC-014-A02**: Start-wins retains original ingredient issue; cancel-wins restores exactly unstarted allocation and stale start fails. Close/pay belongs before cut or rejects, never post-cut uncounted cash.
- **RSC-014-A03**: Handoff/payment/split/edit preserve one owner and all quantity/charge/previous collector identities; stale version approval denies, revoked policy denies newly unauthorized work after activation.
- **RSC-014-A04**: Observed result equals a valid serial ordering of current-state guards, not an arbitrary last-write-wins blend.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-04, REL-10, Q-RES-01, Q-DB-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-015

### Account switch during unknown financial operation

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-EXTERNAL, RF-UI

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- A submitted unknown100 external operation; B next user on same device; manager approves within A flow without taking collection identity.

**Steps (unchanged):**
1. Back/lock/logout offline, authenticate B, deliver delayed A responses/notifications.
2. Reconnect and use permitted A/supervisor recovery; separately move A to another admitted device.

**Expected results (unchanged):**
- **RSC-015-A01**: A tokens/private UI/subscriptions/entry state cleared, no protected flash or late A response in B workspace.
- **RSC-015-A02**: Protected original A operation remains recoverable without B adopting its payment; manager remains approver not collector.
- **RSC-015-A03**: Master remains serving, cash session not transferred/closed and offline revocation pending state honest.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-06, UX-07, REL-04, Q-SEC-04, Q-NAV-07, Q-DI-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-016

### Offline session refresh, disable and persistent rate limits

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Configured local access/refresh expiry, lockout parameters and one-session default are explicit fixture policy, not assumed production constants.

**Steps (unchanged):**
1. Guess credential to configured threshold, restart/rollback wall clock, then valid local recovery/reset.
2. Login/refresh concurrently with dropped reply; reuse old token; disable/revoke locally during active read/subscription and try again without WAN.

**Expected results (unchanged):**
- **RSC-016-A01**: Persistent throttling not reset by restart or clock rollback; secret never appears in logs.
- **RSC-016-A02**: Refresh rotation grants no unauthorized independent session; safely unrecoverable refresh prompts local re-login, not cloud.
- **RSC-016-A03**: Disable/revoke is enforced on local commands/reads/subscriptions/caches after activation even if hint lost; historical actors preserved.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-01, REL-07, UX-04, Q-SEC-03, Q-SEC-06, Q-RES-12.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-017

### Bounded malformed/adversarial local RPC and diagnostics

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Missing scope, cross-company related IDs, oversized/deep/duplicate-field/truncated payloads, invalid enums, excessive decimal precision, integer overflow, decompression bombs, request floods/slow consumer.

**Steps (unchanged):**
1. Send through actual registered protected local RPC/same-device and read/diagnostic routes with explicit bounded harness limits.
2. Follow failures with normal authorized operation and inspect retained queues/resources/redacted errors.

**Expected results (unchanged):**
- **RSC-017-A01**: Reject safely before business effects/expensive unbounded processing; no hidden parsed-field override or unauthorized related data.
- **RSC-017-A02**: No stacktrace/credential/private finance leak; queue/memory stays designed bound and legitimate branch work remains serviceable.
- **RSC-017-A03**: Error is actionable and localized with stable machine meaning, not fabricated committed status.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-03, REL-10, Q-SEC-07, Q-SEC-08.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-018

### Missed change hints, atomic cursor and permission invalidation

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Consistent snapshot/cursor for owner orders/KDS/stock; drop/duplicate/reorder hints and terminate client during page application.

**Steps (unchanged):**
1. Pull changes after saved cut, restart/reconnect and also supply expired cursor.
2. Activate a local permission revocation while old pages/events are delayed.

**Expected results (unchanged):**
- **RSC-018-A01**: Apply page and cursor atomically; no lost change or repeated business operation; expired history requests a new snapshot explicitly.
- **RSC-018-A02**: KDS cancellation/stock/order projections converge to authorized current state even when every notification lost.
- **RSC-018-A03**: Old cursor/page does not retain revoked access; private data/callbacks suppressed.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-03, REL-04, UX-06, Q-RES-04, Q-SEC-06, Q-DB-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-019

### Atomic package activation and active-session revoke

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Requirement-defined authenticated synthetic package fixtures delivered directly to production local validation/activation input, not a fake central server; valid newer, stale, truncated, wrong-scope, invalid-dependency and duplicate packages.

**Steps (unchanged):**
1. Interrupt download/stage/activate and race with collection lock, kitchen send and approval.
2. Activate valid permission revoke, lose client hint and exercise commands/queries/subscriptions/caches.

**Expected results (unchanged):**
- **RSC-019-A01**: Old complete configuration or full validated newer configuration only; malformed package neither partially changes state nor disables unrelated healthy checkout.
- **RSC-019-A02**: Revocation affects current sessions/read/cache after local activation; offline intent not falsely displayed as already applied.
- **RSC-019-A03**: Locked/paid/issued original snapshots preserved; local emergency disable not silently undone by stale data.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-08, UX-06, Q-SEC-06, Q-RES-01.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** HELD — Real central package authoring/authenticated transport/acceptance and branch acknowledgement.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-020

### Desktop installed service survives UI/logout and restarts safely

**Environment:** WINDOWS_PHYSICAL_MASTER, MACOS_PHYSICAL_MASTER; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Windows and macOS actual installed candidate with protected service identity; client D2 connected.

**Steps (unchanged):**
1. Log out and close cashier window; D2 completes production transaction. Crash master service and permit supported bounded supervisor restart.
2. Start duplicate service/process and intentionally stop branch service through authorized UI.

**Expected results (unchanged):**
- **RSC-020-A01**: Window close/logout does not stop healthy master or fabricate cashier session; restart retains epoch/branch and exact original operation.
- **RSC-020-A02**: Duplicate process cannot write; explicit stop persists and supervisor does not undo it.
- **RSC-020-A03**: Actual packaged native library/IPC/keychain/ACL context is tested, not development JVM alone.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-07, UX-07, Q-DI-02, Q-DB-05.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-021

### Android master notice, foreground service and force-stop

**Environment:** ANDROID_PHYSICAL_MASTER_AND_CLIENT; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Physical Android supported OS/OEM, foreground-service permission and local-network permission combinations; separate ordinary client profile.

**Steps (unchanged):**
1. Activate master and observe notice/keep-awake/notification; lock/background/swipe-away/force-stop under documented OS behavior.
2. Reopen with WAN absent, then repeat with persisted intentional-stop/retired/handover markers; end hosting and use client role.

**Expected results (unchanged):**
- **RSC-021-A01**: Hosting availability is observed, not promised beyond OS limits; clients retain unknowns and never promote automatically.
- **RSC-021-A02**: Still-active valid role auto-resumes same identity/epoch after local recovery without cashier/cloud login; stopped/retired/sealed never resumes writes.
- **RSC-021-A03**: Keep-awake applies only active hosting and releases afterward; denied permissions give actionable state without permissive fallback.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-07, UX-04, UX-07, Q-RES-04, Q-RES-13.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-022

### iPhone and iPad active-app hosting and truthful reopen

**Environment:** IPHONE_PHYSICAL_MASTER, IPAD_PHYSICAL_MASTER; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Physical iPhone AND physical iPad separately, supported OS/CPU; active/locked/background/terminated and local-network denied states.

**Steps (unchanged):**
1. Activate and confirm keep-open notice, role-scoped idle prevention; manually lock/background/force-quit during unknown operation.
2. Reopen without WAN and repeat while intentionally stopped/retired/sealed; on iPad also rotate/multitask.

**Expected results (unchanged):**
- **RSC-022-A01**: No claim iOS automatically reopens/serves while suspended; clients show unavailable and preserve original operation.
- **RSC-022-A02**: Reopen resumes only still-active role after recovery, same epoch; no new assignment, lost money, token resurrection or fallback master.
- **RSC-022-A03**: Notice/idle/service state correct on both hardware form factors; iPhone/simulator evidence cannot stand for iPad.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-07, UX-04, UX-07, Q-RES-04, Q-RES-13, Q-NAV-10.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-023

### Role markers, headless startup and secure-key unavailable

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Persisted Active, IntentionallyStopped, Draining, Sealed/Retired and Recovery copies; protected key unavailable and corrupt database variants.

**Steps (unchanged):**
1. Restart/reopen each with no cashier logged in and no WAN; attempt local/remote write.
2. Restore key access via approved local recovery, never new empty bootstrap.

**Expected results (unchanged):**
- **RSC-023-A01**: Only valid still-active recovered authority may write; no automatic reactivation after intentional stop/seal/retirement or copied database.
- **RSC-023-A02**: Missing key/corrupt data leaves explicit recovery state, not new identity/database or weakened protection.
- **RSC-023-A03**: Original actors/operation IDs retained; headless service lifetime independent of human login.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-06, REL-07, REL-09, Q-DB-05, Q-RES-07, Q-RES-10.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-024

### Full coherent local recovery and subsequent business

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Create RF-RECOVERY using real production commands and independent expected manifest, checkpoint complete independent local backup or prepared standby.

**Steps (unchanged):**
1. Restore to admitted but nonauthoritative target; inspect every required state family before delegated activation.
2. Activate through approved fenced recovery, fresh local login, recover prior outcomes, then new sale/same-branch return/close using current permissions.

**Expected results (unchanged):**
- **RSC-024-A01**: At cut: drawer750; A sellable-on-hand8/reserved1/available7/quarantine1; pending refund100; remaining original return1/100; bread8/meat1200g with paid-unsent reserve1/150g; safe50 unresolved; courier75; incident200; service supplier due200; C22sellable/2held and mismatch20.
- **RSC-024-A02**: Exchange offset100/difference30 and deposit10/entitlement1, original actors/recipes/allocations/claims/print unknown/config/inbox/outboxes/results/numbering survive; no paid/prepared side-effect replay.
- **RSC-024-A03**: Restoration alone no writer, fresh sessions/target identity required; preserved original result can be looked up, subsequent work conserves balances.
- **RSC-024-A04**: Central recovery route not claimed from a local fixture.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-09, REL-04, Q-DB-09, Q-RES-08, Q-RES-09, Q-RES-10.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** HELD — Actual central-created recovery package and protected download/key-recovery source.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-025

### Live snapshot has a verified consistent WAL/journal cut

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-RECOVERY, RF-SCALE

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- P1 actual business workload while local backup captures a cut; mutations include sale, return, receipt, kitchen and cash.

**Steps (unchanged):**
1. Take production consistent snapshot and concurrent journal catch-up, stop at completion and inspect manifest.
2. Restore independently; compare each committed source operation before/at/after cut using independently retained command expectations.

**Expected results (unchanged):**
- **RSC-025-A01**: Recovered cut contains all or none of each transaction, matching data/result/audit/outboxes and relationships; never torn main-file/WAL mixture.
- **RSC-025-A02**: Only coherent covered operations counted; later tail explicitly outside snapshot, not silently included in recovery badge.
- **RSC-025-A03**: Snapshot capture/catch-up bounded and does not require central/standby confirmation for new local sale.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-09, REL-10, Q-DB-09, Q-RES-09.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-026

### Tampered, truncated, wrong-key and wrong-branch backup

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Independent variants: altered encrypted chunk, missing last chunk, wrong length/hash, manifest branch B2, wrong recovery key, unsupported schema, stale/divergent cut. Existing target has usable protected state.

**Steps (unchanged):**
1. Try restore through real UI/service and production cryptographic validation, interrupt at final verification.
2. Reopen target and query original usable state and role.

**Expected results (unchanged):**
- **RSC-026-A01**: Invalid copy is rejected or explicit incompatible/reconciliation state without activation or overwriting usable old data.
- **RSC-026-A02**: No key/credential leakage, cloned private identity or silent empty reset.
- **RSC-026-A03**: Valid stale copy cannot claim full current recovery; missing range/age explicitly disclosed.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-09, REL-10, Q-DB-10, Q-RES-08.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-027

### Large protected backup transfer cancellation and resume

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-RECOVERY, RF-SCALE

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- P2-sized encrypted snapshot, authorized target, bounded configured chunk/page sizes; drop/reorder/repeat chunks and cancel mid-transfer.

**Steps (unchanged):**
1. Transfer using actual production authorized application protocol, restart sender/receiver and resume verified cut.
2. Inject disk-full/low space at receive/manifest finalize and retry after safely restoring capacity.

**Expected results (unchanged):**
- **RSC-027-A01**: No unbounded single RPC/file buffer or unsupported raw loopback/file-transfer assumption; memory stays recorded bound.
- **RSC-027-A02**: Incomplete copy never ready/active; original source usable, protected chunks resumable and each verified once.
- **RSC-027-A03**: Low space/cancel cannot silently discard live backlog or mark incomplete backup successful.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-09, REL-10, Q-DB-09, Q-SEC-07.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-028

### Optional standby preparation, privacy and non-authority

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Standby states not-configured/preparing/current/stale/unavailable; D2 may also have ordinary cashier UI; generic M has no recovery grant.

**Steps (unchanged):**
1. Prepare approved device/compatible schema/storage/protected recovery access and verify initial snapshot.
2. Try ordinary employee reading recovery copy or submitting sale from standby; disconnect/lock standby and continue master checkout.

**Expected results (unchanged):**
- **RSC-028-A01**: Ready only after verified identity/credentials/complete checkpoint; standby remains nonwriter and cashier UI cannot read protected copy.
- **RSC-028-A02**: Missing/stale standby never prompts extra cashier approval or delays local commit/printing.
- **RSC-028-A03**: Connected badge alone not recovery freshness; suspended mobile standby truthfully stale/unavailable.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-01, REL-02, REL-09, UX-04, Q-RES-06, Q-RES-09, Q-DB-10.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-029

### 250000-event standby catch-up and independent progress

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY, RF-SCALE

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Prepared standby behind250000 actual committed synthetic journal events; WAN off; new workload continues at measured event rate.

**Steps (unchanged):**
1. Resume production standby replication, duplicate/reorder/lost ACK, crash both sides between copy and ACK then restart.
2. Measure contiguous checkpoint, memory and catch-up throughput while new business continues.

**Expected results (unchanged):**
- **RSC-029-A01**: Stored original operations/effects converge once; no live payment/kitchen/print replay or accepting gap as contiguous cut.
- **RSC-029-A02**: Throughput target>=2x measured incoming event rate, bounded memory, durable restart progress and independent pending central outbox.
- **RSC-029-A03**: Local response excludes standby ACK; report measured elapsed/lag rather than invented zero RPO.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-02, REL-04, Q-PERF-04, Q-RES-11, Q-RES-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** HELD — Equivalent real central upload/ingestion/ACK catch-up and capacity.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-030

### Planned offline handover preserves drained cut

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- R specifically delegated, target T admitted/compatible with own identity, WAN absent, old Active at epoch4.

**Steps (unchanged):**
1. Use actual app prepare/catch-up/drain, resolve handlers preserving unknown evidence; verify final checkpoint on T.
2. Durably seal old, activate T epoch5, reconnect only with approved pinned T identity and fresh sessions; recover original operations then sale/return/close.

**Expected results (unchanged):**
- **RSC-030-A01**: T nonwriter until final cut verified and old durably sealed; one active writer throughout.
- **RSC-030-A02**: All RF-RECOVERY values/actors/results preserved at cut; current cash custody not moved to R and old operation ID same result under fresh authorized session.
- **RSC-030-A03**: Measure drain/copy/activation and target<=15min P1 without sacrificing safety; central history remains pending.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-05, REL-09, Q-RES-05, Q-RES-10, Q-PERF-08.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-031

### Interrupt every handover transition and lost activation response

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Independent cuts before/after each Preparing/Draining/Sealed/Activated/Completed durable transition, plus lost response; one fixed handover/target/checkpoint.

**Steps (unchanged):**
1. Terminate/reopen old and target at chosen cut, read durable states through real recovery UI.
2. Retry same handover; request cancel before and after seal separately.

**Expected results (unchanged):**
- **RSC-031-A01**: Before seal, persisted abort can return old Active and target nonwriter; no lingering second authority.
- **RSC-031-A02**: After seal, only controlled continuation/reassignment, never arbitrary old writer reopen; lost activation reply resolves durable target state.
- **RSC-031-A03**: Idempotent transition identities and checkpoint preserved; no repeated financial or physical outboxes.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-05, REL-06, Q-RES-05, Q-RES-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-032

### Competing targets, stale approval and unsafe promotion denial

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- R approval for handover H/T1/checkpoint C; T2 also ready; M generic manager; wrong branch recovery user R2.

**Steps (unchanged):**
1. Race H/T1 and second target activation; alter target/checkpoint/delegation after approval.
2. Attempt promotion from heartbeat failure, backup possession, manager role or discovery alone.

**Expected results (unchanged):**
- **RSC-032-A01**: At most specifically authorized target/checkpoint activates; altered/stale grants and wrong roles reject with no new writable authority.
- **RSC-032-A02**: Health timeout/epoch bump alone cannot fence/appoint master; original usable authority remains controlled.
- **RSC-032-A03**: Denied requests cannot leak protected recovery credentials/data.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-05, REL-06, REL-09, Q-RES-05, Q-RES-06, Q-SEC-05.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-033

### Delegated offline emergency replacement with real isolation

**Environment:** QUALIFIED_REPLACEMENT_PLATFORM_PAIR_AND_REAL_ISOLATION_CONTROLS; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Old hardware unavailable, WAN off, independently verified local checkpoint C and R protected offline recovery access; old physical/network authority demonstrably isolated under named runbook.

**Steps (unchanged):**
1. Record isolation and observed checkpoint coverage; authenticate R locally and restore nonwriter T.
2. Persist approved new assignment/epoch/own identity, fresh sessions/client pinning; resume safe operation and reconcile unresolved evidence.

**Expected results (unchanged):**
- **RSC-033-A01**: No cloud request required for prepared local-copy recovery; generic M denied.
- **RSC-033-A02**: Verified full checkpoint values restored, possible unreplicated tail explicitly stated rather than invented missing receipts/payment failure.
- **RSC-033-A03**: New role only after actual isolation; original actor/custody/operation identities retained, old bearer tokens invalid.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-06, REL-09, Q-RES-06, Q-RES-08, Q-RES-09, Q-RES-10.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-034

### No usable copy or no safe fencing is not lossless takeover

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Variants: only device destroyed/no independent reachable copy; stale/corrupt copy only; old partitioned master may still serve clients and cannot be retired safely.

**Steps (unchanged):**
1. Attempt normal emergency replacement through actual app with otherwise authorized R.
2. Inspect recovery options, evidence retention and client behavior; do not create fake empty branch success.

**Expected results (unchanged):**
- **RSC-034-A01**: No automatic or claimed immediate lossless takeover; precise missing data/isolation blocker visible.
- **RSC-034-A02**: No manufactured paid receipts, reset stock/cash/entitlement or bank-failure assumption; existing unknown evidence preserved.
- **RSC-034-A03**: Healthy original isolated branch may continue locally, but product cannot claim safe universal single-writer replacement from epoch update alone.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-06, REL-09, Q-RES-06, Q-RES-08.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-035

### Returning stale old master never blindly rejoins

**Environment:** REAL_OLD_AND_REPLACEMENT_MASTER_PAIR; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Two variants: OLD has persisted sealed/retired marker; OLD never received marker during abrupt replacement and still contains stale Active role plus an unreplicated tail.

**Steps (unchanged):**
1. Restart OLD under maintained operational isolation; use read-only controlled return/reconciliation workflow.
2. Attempt ordinary RPC, new writes and blind outbox sync before revoking/re-enrolling OLD; compare legitimate tail identities/cut.

**Expected results (unchanged):**
- **RSC-035-A01**: Marked OLD boots nonwriter; unmarked isolated OLD cannot magically know new epoch, so external isolation remains until actual retirement.
- **RSC-035-A02**: No stale database overwrite/automatic reclaim/blind old job replay. Legitimate recovered evidence retains original operation/actor for explicit reconciliation.
- **RSC-035-A03**: Future ordinary client/standby status needs deliberate re-enrollment; independent old active authority is never certified safe.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-06, REL-09, Q-RES-07, Q-SEC-09.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-036

### Operation identity survives approved epoch change

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- RF-RETAIL sale committed under epoch4 and included in verified transfer checkpoint; replacement epoch5 with fresh A session.

**Steps (unchanged):**
1. Query/retry original branch operation semantic payload on replacement with new routing epoch/session.
2. Then change payload/actor, replay old token and separately attempt recovery of absent unreplicated operation.

**Expected results (unchanged):**
- **RSC-036-A01**: Original authorized recovery returns same200sale/net200/stock8/result without second effect; epoch/session routing alone is not semantic mismatch.
- **RSC-036-A02**: Changed original actor/payload and old credential fail; no scope leakage.
- **RSC-036-A03**: Absent tail is reconciliation, not automatically execute as new or falsely prove prior payment failed.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-05, REL-09, UX-06, Q-SEC-09, Q-RES-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-037

### Restore does not resurrect tokens or clone device keys

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Checkpoint contains protected account verifiers/delegation and prior token/session metadata; target owns unique key; captured old bearer/refresh tokens synthetic.

**Steps (unchanged):**
1. Restore/activate using approved key recovery, login locally with restored enabled account.
2. Replay pre-restore tokens/approvals and compare public identity fingerprints across old/target; ordinary user attempts archive/private-key access.

**Expected results (unchanged):**
- **RSC-037-A01**: Local credential usability restored via protected authorized path without copying active bearer tokens or old private identity.
- **RSC-037-A02**: Old sessions/replayed approval invalid where revoked/replaced; new session cannot rewrite original historical actor.
- **RSC-037-A03**: No plaintext secrets/keys in general YAML/diagnostics/backup views and no ordinary cashier access to recovery material.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-09, REL-06, Q-DB-10, Q-RES-10, Q-SEC-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-038

### Incomplete tail, missing receipt and unknown physical evidence

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Verified checkpoint C predates a recorded client attestation and possibly committed sale; local original storage no longer available. Another known original receipt is absent from restored cut.

**Steps (unchanged):**
1. Restore C and inspect recovery/report freshness; attempt pending payment lookup and return for unavailable receipt.
2. Record authorized evidence/claim and explicit reconciliation without fabricated entitlement; later recover legitimate source tail through controlled path.

**Expected results (unchanged):**
- **RSC-038-A01**: Disclose checkpoint age/coverage and possible missing operations; not-recorded not proof bank failed and no auto second charge.
- **RSC-038-A02**: Missing receipt cannot create unused return entitlement; claim/evidence may be preserved while refund posting waits verified resolution.
- **RSC-038-A03**: Report gaps are not silently deleted/balanced; later same source operation retains identity and effects at most once.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-09, REL-04, UX-06, UX-08, Q-RES-08, Q-RES-14, Q-SEC-09.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-039

### Actual driver durability: process kill versus power loss

**Environment:** EACH_PRODUCTION_DRIVER_ON_NAMED_PHYSICAL_MASTER; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Every production SQLite driver/native profile; effective journal/synchronous/flush settings recorded; named hardware/storage, isolated data only.

**Steps (unchanged):**
1. Independently inject process kill before/after commit and perform authorized physical shutdown/power interruption runs with same workload.
2. Reopen fresh process/connection and query sale/result/stock/cash/audit/outbox and database integrity.

**Expected results (unchanged):**
- **RSC-039-A01**: Committed result recovers exact all-or-none fixture values; failed write never returns false success or torn related records.
- **RSC-039-A02**: Process-kill evidence separate from actual power-loss evidence; filesystem/storage limitations explicitly recorded.
- **RSC-039-A03**: No destructive reset and physical payment evidence preserved if persistence was uncertain.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-04, REL-10, Q-DB-03, Q-DB-04.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-040

### Disk full, readonly, corrupt pages and flush failure

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-EXTERNAL, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Independent safe disposable storage fault points during command, snapshot, import, journal append and migration: full/readonly/I/O/fsync/corrupt page/WAL.

**Steps (unchanged):**
1. Run production operation at fault, record result/evidence and recover healthy storage by supported procedure.
2. Restart and inspect durable result/backlog/old usable state; complete original operation only via recovery.

**Expected results (unchanged):**
- **RSC-040-A01**: No false committed sale or discarded unsent work; transaction all-or-none and externally paid evidence stays explicit.
- **RSC-040-A02**: Failed restore/import/migration does not delete usable previous database or authorize empty branch.
- **RSC-040-A03**: Low-space status actionable and privacy-safe; failures not converted into soft success or infinite unbounded retry.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-10, REL-09, Q-DB-06, Q-DB-10.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-041

### Initial schema, supported migration and corrupted schema refusal

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Initial actual production schema plus separately labelled synthetic migration harness; include each real shipped schema once one exists. No imaginary legacy pass history.

**Steps (unchanged):**
1. Create fresh current database and upgrade each supported prior fixture by allowed direct/chained path; fault at meaningful migration boundaries.
2. Compare schema/constraints/rows/IDs/values/protected fields to independent fixture manifest; try wrong/corrupt schema.

**Expected results (unchanged):**
- **RSC-041-A01**: Fresh/migrated equivalent constraints and all original money/quantity/entitlement/results survive; interrupted migration rolls back or completes atomically.
- **RSC-041-A02**: Unsupported/corrupt schema fails closed without destructive fallback or deletion to satisfy new index.
- **RSC-041-A03**: Evidence states actual versus synthetic history accurately.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-08, REL-09, Q-DB-01, Q-DB-02, Q-DB-08.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-042

### Upgrade with pending work and mixed compatible clients

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Pending collection/kitchen/refund/cash close/purchase/handover and old result readers; supported versus unsupported client protocol manifests.

**Steps (unchanged):**
1. Create verified recovery point and apply real supported local package/schema upgrade; interrupt installation/migration then recover.
2. Reconnect old/new clients; retry original operations under preserved digest and inspect UI of incompatible participant.

**Expected results (unchanged):**
- **RSC-042-A01**: No lost pending evidence, duplicate actor/operation or silently new hash meaning; historical result/snapshot readers preserve original values.
- **RSC-042-A02**: Compatible client can recover; unsupported writes denied BEFORE stock/payment mutation with actionable state.
- **RSC-042-A03**: No old binary opens partially/newer incompatible schema, no second writer or central checkout fallback.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-08, UX-06, Q-RES-13, Q-BE-10, Q-DB-08.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** HELD — Actual generated central/Go/Kotlin cross-service protocol compatibility.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-043

### Unsupported downgrade refuses; supported restore is controlled

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- New schema and pending work; older incompatible app, plus a supported old backup with declared migration path.

**Steps (unchanged):**
1. Attempt installed downgrade over current data; inspect persisted role/data without writes.
2. Use documented compatible restore/migration/recovery procedure and explicitly compare missing-tail coverage.

**Expected results (unchanged):**
- **RSC-043-A01**: Unsupported downgrade refuses safely, never destructive reset or accidental active duplicate.
- **RSC-043-A02**: Supported restore starts nonauthoritative, preserves/reconciles pending evidence and requires controlled role activation.
- **RSC-043-A03**: Known checkpoint gaps remain visible; cannot silently roll away acknowledged sales.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-08, REL-09, Q-DB-08, Q-RES-13.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-044

### Clock jumps, Cairo business cutoff and sequence bounds

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Explicit Cairo cutoff04:00 fixture; independent UTC instants around actual configured date/DST boundary, backward/forward jump, restart and near-max sequence.

**Steps (unchanged):**
1. Record operations immediately before/after cutoff and clock change; exercise session/throttle/unknown-lock scheduling.
2. Attempt sequence increment at supported bound and inspect number/authority lineage.

**Expected results (unchanged):**
- **RSC-044-A01**: Business day computed from configured timezone/cutoff, actual UTC/tax date retained separately. Persistent sequence, not wall time, orders authority.
- **RSC-044-A02**: No reused receipt/operation ID, unsafe lock expiry or reset guessing counter; overflow denied before posting.
- **RSC-044-A03**: No internet time refresh prerequisite for healthy local restart.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-07, REL-08, Q-RES-12, Q-SEC-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-045

### Bounded query plans and reader/write fairness at P2

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-SCALE

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- P2 actual production database, authorized and quantity-only users; indexed barcode/search/last-stock/return entitlement/ledger/report queries with stable pagination.

**Steps (unchanged):**
1. Measure actual query plan/latency/rows/bytes and page cursor consistency while new mutations continue.
2. Flood permitted reads/export/slow consumers within harness limits; revoke sensitive access mid-run.

**Expected results (unchanged):**
- **RSC-045-A01**: Justified indexed predicates and bounded stable pages, no unbounded full history/catalog load or indefinite write starvation.
- **RSC-045-A02**: Local product lookup target p95<=150ms; filtered/stale query and actual render timing reported separately.
- **RSC-045-A03**: Quantity-only paths never fetch hidden costs and old cursors retain no revoked permission.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-02, REL-10, Q-DB-07, Q-PERF-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-046

### Local durable response, rendering, memory and restart benchmarks

**Environment:** NAMED_PHYSICAL_PLATFORM_CAPACITY_PROFILES; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-SCALE

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Named qualified physical platform/storage release-like build, P0/P1/P2, checkpoints enabled, real mixed commands not ping.

**Steps (unchanged):**
1. Measure submit-to-durable-response percentiles excluding human/terminal/paper/remote ACK; separately measure lookup/input/render frames.
2. Repeat navigation/session scope teardown, large import/sync/image work and same-master restart; record managed/native/bitmap/queue/RSS.

**Expected results (unchanged):**
- **RSC-046-A01**: Targets p95 command<=500ms/p99<=1s; lookup<=150ms; input/nav<=100ms; smooth60Hz where hardware supports with p95/missed frames recorded.
- **RSC-046-A02**: No blocked input or monotonically growing retained scope/queue; platform-specific memory budget established, not one JVM heap assumption.
- **RSC-046-A03**: P1 ready-to-serve target<=60s after process launch/healthy storage, excluding human mobile reopen/OS boot; pending outcomes still recoverable.
- **RSC-046-A04**: Missed provisional target reported with bottleneck/capacity review, never silently excluding required platforms.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-01, REL-07, UX-05, Q-PERF-01, Q-PERF-02, Q-PERF-03, Q-PERF-06, Q-PERF-07.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES, RQ-LOAD

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-047

### 72-hour mixed local reconnecting soak

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-SCALE

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- P1,72h deterministic mixed local connected/reconnecting master/client/optional standby schedule, all four activities; WAN off or disabled by phase.

**Steps (unchanged):**
1. Run real search/sale/refund/kitchen/purchase/count/report/print workloads, periodic process restart and client partitions.
2. Reconcile expected effects/checkpoints periodically and after final restart; record memory/resources/queue sizes.

**Expected results (unchanged):**
- **RSC-047-A01**: No lost committed operation, incorrect money/stock/entitlement, crash, monotonic resource leak or unbounded queue.
- **RSC-047-A02**: Service remains independent of client sessions and deferred destinations; exact failure schedules/seeds saved.
- **RSC-047-A03**: Local72h run does not satisfy later central-connected ingestion soak.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-01, REL-02, REL-04, Q-PERF-05, Q-PERF-06, Q-RES-11.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** HELD — Central-connected/ingestion variants of combined72h soak after owner approval.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-048

### Crash recovery, replication and printing under new work

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY, RF-SCALE

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- P1 live master, standby catch-up, print jobs including unknown status and durable central outbox; lose responses/ACKs while new sales continue.

**Steps (unchanged):**
1. Fault production storage/restart at selected boundaries; resume standby/print workers and current cashier transactions.
2. Inspect operation/source effect counts, worker backoff/queues and foreground latency.

**Expected results (unchanged):**
- **RSC-048-A01**: One committed effect per identity; unknown original print never blindly resent as new preparation, finance remains committed.
- **RSC-048-A02**: Bounded fair workers cannot starve foreground or discard unsynced evidence; local/standby/central cursors separate.
- **RSC-048-A03**: No real central catch-up claim from local standby run.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-02, REL-04, REL-10, UX-08, Q-RES-11, Q-PERF-04.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** HELD — Actual central ingestion/catch-up portion of combined experiment.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-049

### No secrets or private state in diagnostics and notifications

**Environment:** ALL_NATIVE_APP_PRIVACY_AND_NOTIFICATION_PROFILES; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-UI, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Synthetic canary tokens/credentials/customer contacts/margins in private scope; low-stock/payment/cash-variance alert recipient loses access.

**Steps (unchanged):**
1. Trigger errors, crash report/diagnostic export, app-switcher/background screenshot and lockscreen notification; logout/switch account then deliver late events.
2. Open old notification and attempt alternate direct export by removed recipient.

**Expected results (unchanged):**
- **RSC-049-A01**: No credential secret or unauthorized contact/margin appears in logs/export/preview/cross-user image/cache or new UI.
- **RSC-049-A02**: Alert delivery deduplicated; access rechecked on open, acknowledgement changes no stock/money/payment obligation.
- **RSC-049-A03**: Authorized diagnostic is bounded/redacted and failures expose safe actions, not raw transcripts.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-07, UX-08, Q-SEC-08, Q-IMG-01.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-050

### Every typed route is bounded, registered and owned

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-UI, RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Executor supplies public route/adapter inventory for every required native screen, without deriving expected behavior from implementation; approved notification/deep-link grammars only if actually product-supported.

**Steps (unchanged):**
1. Round-trip valid route identifiers on all targets; invalid type/scope/length/unknown route must fail boundary validation.
2. Visit independent top-level stacks and reselect active tab; parse duplicate/malformed supported external intents and construct canonical back history.

**Expected results (unchanged):**
- **RSC-050-A01**: Exactly one adapter and documented root/tab for every route; no token/full mutable order/ViewModel in route data and no alternate navigation owner.
- **RSC-050-A02**: Root protected, stacks bounded, tab/reselection semantics same logical state on compact/expanded layout and subject to leave guard.
- **RSC-050-A03**: Supported external intent bounded/deduplicated/revalidated; absent product deep links are not invented for test completion.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L0_PUBLIC_CONTRACT_AND_EXECUTOR_STATIC_INVENTORY, L1_SERIALIZATION, L4_NATIVE_UI.

**Related requirements:** UX-06, UX-07, Q-NAV-01, Q-NAV-02, Q-NAV-08.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-051

### Back and forward leave guards on every route state

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-UI, RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- For every required route: clean, dirty editor, search/dialog local-handler, critical unknown payment/kitchen cancel/refund and root. Sources: toolbar, Android system/predictive, iOS swipe, desktop Escape/Back and programmatic completion.

**Steps (unchanged):**
1. Invoke each back source, also tab/forward/alert navigation during leave guard; duplicate/back cancellation around confirmation.
2. Observe rendered frames and durable operation/entry identities before/after permitted transition.

**Expected results (unchanged):**
- **RSC-051-A01**: All sources produce same single allowed pop/local-handle/discard-confirm/block/root behavior; no double command or lost pending original operation.
- **RSC-051-A02**: Guarded/unauthenticated previous protected screen never flashes during predictive/interactive movement.
- **RSC-051-A03**: Forward/tab/alert cannot evade active guard; cancelled discard preserves draft and submitted financial evidence.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-06, UX-07, Q-NAV-04, Q-NAV-05, Q-NAV-02.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-052

### Cold recreation quarantines route history until authorization

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-UI, RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Saved route-only histories: valid, wrong root/tab, oversized, unauthorized/deleted entity, failed repository validation and interrupted revalidation.

**Steps (unchanged):**
1. Kill/reopen application without current session; authenticate different/same user and validate quarantined history.
2. Capture first frames; interrupt again during validation and later replay an old callback.

**Expected results (unchanged):**
- **RSC-052-A01**: Cold start authentication first; no prior protected screen/ViewModel store visible even for one frame.
- **RSC-052-A02**: Only fully authenticated/authorized/structurally valid minimal routes may restore; failures stay safe and actionable.
- **RSC-052-A03**: Durable pending operation recovered by original ID independently of route object; no stale callback navigation.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-06, UX-07, Q-NAV-06, Q-SEC-04.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-053

### Stale navigation events and bounded command/activity storm

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-UI, RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- 100000 navigator submissions including duplicate taps/stale entries,1000+ pointer/key activity signals, repeated host detach/session/root switches and collectors restart.

**Steps (unchanged):**
1. Run actual navigator/UI with bounded deterministic scheduler, observe entry/action count and scope disposal.
2. Measure host/current-screen recomposition and retained resources during scanner/scrolling workload.

**Expected results (unchanged):**
- **RSC-053-A01**: Stale/inactive entry/session callbacks never execute current-user actions; duplicate financial submissions not replayed.
- **RSC-053-A02**: Bounded history/queue/resources and disposal cannot unregister a newer guard; only designed retained tabs persist.
- **RSC-053-A03**: Host recomposition does not scale with each activity signal; frame/resource metrics recorded without hiding dropped/failing cases.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-06, UX-07, Q-NAV-03, Q-NAV-09, Q-NAV-12, Q-PERF-06.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-054

### Physical iOS LTR/RTL interactive gesture matrix

**Environment:** IPHONE_PHYSICAL_LTR_RTL, IPAD_PHYSICAL_LTR_RTL; N1, N2, N4, N5.

**Fixture:** RF-UI, RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Physical iPhone AND iPad, English left-edge and Arabic right-edge;60/120Hz where shipped hardware supports. Eligible clean route and guarded unknown/dirty/protected route.

**Steps (unchanged):**
1. Slow/full drag, short fast flick, low-velocity release, reversal/cancel, repeated Back and opposite-edge attempts.
2. Switch language then repeat; background/lock/detach midgesture. Measure finger tracking and release settlement separately.

**Expected results (unchanged):**
- **RSC-054-A01**: Correct direction/eligibility, at most one allowed Back; opposite edge or cancelled gesture never navigates unexpectedly.
- **RSC-054-A02**: Guarded/unauthenticated route never reveals prior private screen and retains original financial operation.
- **RSC-054-A03**: Progress/settlement are qualified library-owned behavior; measured frame/latency on each hardware, no fabricated universal speed threshold or copied private recognizer workaround.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-05, UX-06, Q-NAV-10, Q-NAV-11, Q-NAV-05.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-055

### Koin graph, master/session/entry teardown and reentry

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-UI

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Actual production platform/feature bindings, headless master, A/B sessions and entries with required parameters.

**Steps (unchanged):**
1. Resolve graph/start master without UI, create/pop/reset/reenter screen scopes, revoke/logout user and change host/company context.
2. Inspect observable ViewModel/job/listener/registration/resource teardown and database/master instance identity.

**Expected results (unchanged):**
- **RSC-055-A01**: One actual master/database instance, no screen ViewModel singleton or UI dependency required to serve branch.
- **RSC-055-A02**: Master survives human logout; human scope closes on revoke, entry scope closes on pop/reset, old disposal cannot remove new guard.
- **RSC-055-A03**: No escaped old-user state or monotonic resource leak; retained tabs retain only intended authorized state.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L0_GRAPH_CONTRACT, L2_PRODUCTION_RUNTIME, L4_NATIVE_UI, L5_RESOURCE_SOAK.

**Related requirements:** UX-07, REL-07, Q-DI-01, Q-DI-02, Q-NAV-09.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-056

### Scoped Coil caches, decode limits and offline item use

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-UI, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Same URL different company/private authorization, changed catalog revision, corrupt/oversized/unsupported/expired image and offline eviction; platform actual fetchers/decoders.

**Steps (unchanged):**
1. Scroll/resize/reenter catalog, cancel image request, switch account/company while old request pending; revoke private media.
2. Complete actual RF-RETAIL checkout with all images absent/corrupt.

**Expected results (unchanged):**
- **RSC-056-A01**: No cross-company/revision/private media leak or late image completion in next user; tokens not in cache keys/URLs.
- **RSC-056-A02**: Memory/decode/disk bounded and requests cancel, no unbounded retained bitmap/main-thread stall.
- **RSC-056-A03**: Metadata/authoritative price/stock selection and exact checkout work despite image failure; cache is not falsely treated as durable catalog.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-05, UX-07, Q-IMG-01, Q-IMG-02, Q-IMG-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-057

### Arabic/English semantics and assistive rapid-entry accessibility

**Environment:** ALL_NATIVE_ASSISTIVE_AND_INPUT_PROFILES; N1, N2, N4, N5.

**Fixture:** RF-UI, RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Every shared/native prompt/menu/notification plus long plural/placeholder/mixed-direction names; barcode000123, totals114.00/100.00; text scales and contrast; TalkBack/VoiceOver/desktop assistive technology.

**Steps (unchanged):**
1. Perform login/scan/variant/modifier/quantity/approval/payment/return/close using touch, keyboard-only, scanner and assistive paths.
2. Runtime switch language/rotate/resize, generate validation/unknown status and rapid key-repeat/touch overlap.

**Expected results (unchanged):**
- **RSC-057-A01**: Resource/plural/placeholder parity or explicit reviewed allowlist; no missing-release translation concealed by fallback.
- **RSC-057-A02**: RTL/LTR/read order/directional icons/numbers/barcodes/receipts correct, amount meaning unchanged; focus/error announcements/touch targets/contrast usable without color-only dependence.
- **RSC-057-A03**: Same authorization/quantity/confirmation semantics across inputs, no hidden duplicate submit or losing original unknown operation.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-05, UX-06, Q-UX-01, Q-UX-02, Q-UX-03, Q-UX-04, Q-UX-05.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-058

### Resumable setup/openings and honest initial enrollment boundary

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Labelled synthetic already-provisioned branch; setup imports selected sparse variants/barcodes/recipes/stock locations, counted10units@20value200, cash float1000 and opening supplier balance300.

**Steps (unchanged):**
1. Use real wizard/local employee activation/pairing/recovery access/peripheral settings; interrupt after each import/opening commit and resume same stable IDs.
2. Run without standby/WAN and restart master; try duplicate/corrupt/wrong-unit import and unconfigured optional unrelated policy.

**Expected results (unchanged):**
- **RSC-058-A01**: Exactly10opening stock/value200, float1000 and supplier opening300; sales/revenue0, no fictional purchase or duplicate SKU/debt/cash.
- **RSC-058-A02**: Saved setup steps/local accounts/recovery persist; bad input has explicit errors, healthy configured branch does not recur wizard/cloud lease.
- **RSC-058-A03**: No fake initial central registration or central recovery evidence; absent optional standby not a blocker for valid one-device branch.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-01, UX-02, UX-04, Q-UX-06, Q-DB-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** HELD — Actual initial central company/branch/device registration and central recovery qualification.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-059

### Training remains out of production local/live channels

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Separate training database/identity/transport/number sequence and a blank synthetic production qualification branch.

**Steps (unchanged):**
1. Perform training RF-RETAIL sale/return/close; interrupt switch to live then resume responsible acknowledgement.
2. Inspect production local ledgers/numbering/upload/fiscal queues and attempt training identity replay at production local boundary.

**Expected results (unchanged):**
- **RSC-059-A01**: No training cash/stock/receipt/event appears in live production local records or live outgoing jobs; distinct branch/environment identity enforced.
- **RSC-059-A02**: Live setup audit and opening balances correct, no inherited training permissions/private sessions.
- **RSC-059-A03**: Production qualification also runs real production local services; training success alone is not accepted integration proof.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-03, UX-04, Q-UX-06, Q-BE-08.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** HELD — Actual central live-ingestion and fiscal service denial of training events.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-060

### Physical receipt/kitchen printer failure and identified reprint

**Environment:** EACH_SUPPORTED_PRINTER_OS_INTERFACE_PROFILE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Named supported printer/interface/OS profiles, correct assignment/routing; paper/cutter failure, disconnect and physically printed-but-lost-response jobs.

**Steps (unchanged):**
1. Commit actual sale and kitchen send, exercise printer fault, restart dispatch and inspect unknown status.
2. Authorize identified reprint and inspect Arabic/bidi/font/codepage/raster/totals/QR/barcode and routing.

**Expected results (unchanged):**
- **RSC-060-A01**: Committed sale/issue independent of print failure; no second sale/stock issue or blind duplicate kitchen instruction.
- **RSC-060-A02**: Stable job/document identity, explicit uncertain original and marked audited copy; correct printer/station ownership.
- **RSC-060-A03**: Physical output/model/OS evidence separate; mock printer domain pass not hardware qualification.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-08, UX-06, Q-UX-02, Q-UX-05, Q-RES-11.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-061

### Physical barcode scanner and focus/shortcut equivalence

**Environment:** EACH_SUPPORTED_SCANNER_OS_PROFILE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-UI

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Supported actual HID/camera/SDK scanner, leading-zero variant/pack/weighted formats, malformed/ambiguous/unknown code and configured suffixes.

**Steps (unchanged):**
1. Rapid scan with key-repeat/touch input, modal approval, focus leave/restore and runtime RTL/LTR.
2. Scan after current price/context change and attempt unauthorized unknown-product shortcut.

**Expected results (unchanged):**
- **RSC-061-A01**: Identifiers preserve000prefix and correct unit/variant/conversion; malformed/collision rejected, embedded price cannot bypass current company rule.
- **RSC-061-A02**: Exactly intended quantities, no hidden double submit or scanner focus entering amount/approval incorrectly.
- **RSC-061-A03**: Manual/scanner shortcut same permission and confirmation policy, unknown item not negative-stock bypass.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-05, Q-UX-03, Q-UX-05, Q-UX-09.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-062

### Physical scale stable reading, unit/tare and fallback

**Environment:** EACH_SUPPORTED_SCALE_OS_PROTOCOL_PROFILE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Named scale/protocol and product precision fixture; independently known0.750kg, price40/kg ->30EGP; unstable, invalid-unit, stale and disconnected readings.

**Steps (unchanged):**
1. Zero/tare, capture stable weight, sell through production weighted-entry path and compare exact quantity/receipt.
2. Repeat faults and permitted versus forbidden manual entry.

**Expected results (unchanged):**
- **RSC-062-A01**: 0.750kg charged30 with exact stock decrement0.750kg; unit/tare/precision consistent.
- **RSC-062-A02**: Invalid/unstable/disconnected input cannot silently become priced accepted quantity; explicit operator-approved fallback only if configured.
- **RSC-062-A03**: No inferred legal calibration or universal OS/model compatibility from simulated input.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-05, Q-UX-03, Q-UX-09.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES, RQ-FISCAL

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-063

### Physical cash drawer and separately authorized terminal rehearsal

**Environment:** NAMED_DRAWER_AND_AUTHORIZED_TERMINAL_PROFILE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL, RF-EXTERNAL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Correct assigned drawer adapter, unavailable/jammed/repeated-open variants. Terminal automation remains synthetic; any actual external charge/refund requires separate owner authorization.

**Steps (unchanged):**
1. Open drawer with allowed/denied user, wrong assigned drawer and duplicate physical requests; inspect custody ledger.
2. Only when authorized, reconcile actual terminal receipt/cancel/refund with POS manual attestation; otherwise leave that variant unexecuted.

**Expected results (unchanged):**
- **RSC-063-A01**: Hardware open does not itself create money/custody; unauthorized/wrong drawer command rejected and jam not fictional payout.
- **RSC-063-A02**: External POS record is cashier-confirmed, not provider-approved claim; actual tender/cash follows observed event once.
- **RSC-063-A03**: No automated real money movement or fabricated physical terminal evidence.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-05, UX-08, Q-UX-05, Q-SEC-05.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-064

### Native candidate install, launch, upgrade and data policy

**Environment:** ALL_FIVE_NAMED_NATIVE_PACKAGE_PROFILES; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RETAIL

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Exact release-like local candidates for Windows/macOS/Android/iPhone/iPad; declared minimum OS/CPU/native dependency and distribution/signing status.

**Steps (unchanged):**
1. Install and launch on actual OS, exercise protected identity/storage/LAN/firewall prompts/headless or mobile host contract.
2. Upgrade with pending case and inspect rollback/uninstall retention policy; compare artifact bytes/hash/source/config.

**Expected results (unchanged):**
- **RSC-064-A01**: Optimized/native resources/driver/provider actually present and usable; local core RF-RETAIL completes.
- **RSC-064-A02**: No data reset, leaked credential or second writer on upgrade; declared unsupported OS/CPU fails clearly rather than crash/fake success.
- **RSC-064-A03**: Unsigned/local install evidence remains labelled; no store publication/notarization/production signing assumed or performed in this phase.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** REL-07, REL-08, UX-04, Q-SEC-10, Q-RES-13.

**Qualification note:** Not executed. Requires named physical hardware/OS and witnessed observations; simulator/mock evidence cannot satisfy this case.

**Open requirement questions (original IDs):** RQ-DEVICES

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-065

### Restaurant/cafe full shift with remake, split, incident and later collection

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Run once restaurant and once cafe. Bread20/meat3000g; base burger1bread+150g/price100; extra-meat modifier+50g/+10. No tax/charges in this explicit synthetic policy. A/B drawers1000 each.

**Steps (unchanged):**
1. A creates3burgers, sends2 base, cancels1 verified before start; start other and merchant-remake it. Third burger becomes extra-meat and is sent; ready/serve through stations.
2. Split bill into100base and110extra, handoff110 to B. A collects60cash and records40unpaid incident; B collects60cash+50external. Close A at1060 with retained float500/safe560, open next A shift500, collect incident40 then close540; B closes1060.

**Expected results (unchanged):**
- **RSC-065-A01**: Final ingredients bread17/meat2500g: one original wasted base, one remake base and one extra portion; cancellation reversed one issue. Sold quantity2/payable210, no duplicate remake charge.
- **RSC-065-A02**: Collections total210=160cash+50external; original collector/custody preserved. A first close1060, next540, B1060, safe560; no fictional cash or second stock issue on later40.
- **RSC-065-A03**: Split/handoff conserves bills and prior tasks. All source/receipt/waste/incident/count reports reconcile via real native screens/production services.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-05, UX-06, UX-07, UX-08, Q-UX-07.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-066

### Clothing variants, allocated discount and exchange difference

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Red-S2/Blue-M2, cost20 each; sell1each@100 with permitted bill discount20 ->90 each; A drawer1000. Later authorized replacement Blue-M effective price120.

**Steps (unchanged):**
1. Scan variants, collect200cash/change20 for total180; accept Red-S inspected return from original receipt.
2. Exchange into Blue-M120 using restricted offset90 and collect30; retry after lost reply, reprint and close counted1210.

**Expected results (unchanged):**
- **RSC-066-A01**: Original refundable allocation Red90 not current price; extra cash30 only. Final drawer1210, Red-S2/value40, Blue-M0/value0; one original Blue entitlement90 and new replacement entitlement120.
- **RSC-066-A02**: Net sales after return210 and actual collection210; offset not cash or spendable balance. Variant/inspection/stock identities and actors exact.
- **RSC-066-A03**: Unknown/retry/reprint cannot duplicate return, exchange, cash or quantity.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-05, UX-06, UX-08, Q-UX-08.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-067

### Supermarket fast scan, weight, pack, expiry, receipt and count

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Flour10kg@cost20/kg/price40; milk12bottles@cost5,10valid2expired. Three-bottle pack/offer25 allocated8.34 to lowest stable unit ID, then8.33/8.33. A/B drawers1000 each. Separate last-unit SKU-L1/price5.

**Steps (unchanged):**
1. Scan0.750kg flour and pack3valid milk:30+25=55; collect100cash/change45. Receive against PO5milk,3arrive/2accepted/1quarantine.
2. Count flour9.20kg after verified expected9.25kg and post approved-0.05kg adjustment. Race A/B buying last SKU-L; close both at actual ledger counts.

**Expected results (unchanged):**
- **RSC-067-A01**: After main sale flour9.25kg and milk7valid/2expired; after receipt milk9valid/2expired/1quarantine, no held/expired sale.
- **RSC-067-A02**: Count-adjusted flour9.20kg/value184, reason and movement cut retained. Main drawer A1055 before last-unit race.
- **RSC-067-A03**: Only one5sale: final drawer pair(A1060,B1000) or(A1055,B1005), combined2060. Original promotion allocation/refund entitlement exact, no overstock or double receiving.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-05, UX-06, UX-08, Q-UX-09.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-068

### Native purchasing, matching, settlement, cost and report rehearsal

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Approved activated local catalog/policy fixture (global publication not simulated); SKU-C price20, goods PO24@10; drawer1000. Historical supplier advance40, invoice240; actual24received22accepted2supplier-owned-held.

**Steps (unchanged):**
1. Use native import preview/activated policy display, partial inspection and mismatch; settle allowed uncontested100cash plus prior advance40, then supplier credit20 for damaged amount.
2. Allocate separately invoiced eligible freight22 to22accepted units; sell2@20cash, inspect local supplier/stock/cash reports and visibly stale company report.

**Expected results (unchanged):**
- **RSC-068-A01**: Custody before sale24: sellable22/value220, held2/value0 owned stock; invoice discrepancy20 explicit. Invoice due240-40-100-20=80; freight separate due22 ->total102.
- **RSC-068-A02**: Freight makes inventory242/cost11; sale2 consumes22 ->stock20/value220, held2 unchanged. Drawer1000-100+40=940. No double expense/advance/cash/stock.
- **RSC-068-A03**: Company global draft/publication and offline branch totals stay pending/stale; actual local workflows do not require developer console or fake central success.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-01, UX-02, UX-08, Q-UX-10, Q-BE-09.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** None recorded for this case.

**Central portion:** HELD — Actual central catalog/price/policy publication and consolidated multi-branch report.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-069

### Local receipt/fiscal truth and complete accounting export

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY, RF-RECOVERY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Synthetic tax/document/account mappings only, real production immutable receipt/correction and export services; central/fiscal endpoint unavailable.

**Steps (unchanged):**
1. Issue local receipt/correction links, retry submission intent/restart and inspect pending/unknown status without contacting real authority.
2. Export scoped source accounting including sales/returns/custody/unpaid/supplier settlements/cost variance; deny quantity-only user export.

**Expected results (unchanged):**
- **RSC-069-A01**: Stable unique numbers and original totals, no duplicate fiscal document or rewritten sale; cannot claim accepted with no actual verified acceptance.
- **RSC-069-A02**: Export conserves original cash/cost/obligation/source references and separates custody from revenue, supplier cost from second expense; not sales-only completeness.
- **RSC-069-A03**: Unauthorized export no private data; synthetic policy is not launch compliance.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L2_REAL_PRODUCTION_PERSISTENCE, L3_REAL_LOCAL_SERVICES, L4_INSTALLED_BUSINESS_E2E, L5_FAULT_SECURITY.

**Related requirements:** UX-08, REL-04, REL-08, Q-RES-12, Q-SEC-08, Q-DB-03.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** RQ-FISCAL, RQ-INTEGRATIONS, RQ-RETENTION

**Central portion:** HELD — Real central-hosted/external fiscal submission and any requested external accounting connector.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.

## RSC-070

### Owner handoff, independent evidence and dependency/candidate integrity

**Environment:** ALL_MASTER, ALL_CLIENT, PROTECTED_SAME_DEVICE; N1, N2, N4, N5.

**Fixture:** RF-IDENTITY

**Preconditions:**
- Isolated synthetic data only; actual production local services, protected authentication/admission and durable database. No training/demo/in-memory replacement for the boundary being tested.
- Frozen design precedes execution. Bind to actual public/registered interfaces without changing outcomes; unavailable surface/platform is recorded, not bypassed.

**Inputs:**
- Exact candidate source/dependency/schema/protocol/config/artifact manifests, current dependency/license/secret review and local run/setup/rollback/recovery/peripheral instructions; separate designer/implementer/executor.

**Steps (unchanged):**
1. Validate141 original scenario rows and79 gate inventory, per-case evidence slots/IDs, reproducible synthetic seed and all owner manual steps.
2. On actual candidate verify local startup and named platform artifacts/runbooks; trace any changed bytes to invalidated affected evidence, preserve failed/unexecuted cases.

**Expected results (unchanged):**
- **RSC-070-A01**: Design inventory/build totals never become passed business results; every required feature local integration/E2E and hardware variant explicitly evidenced or unverified.
- **RSC-070-A02**: No critical shipped dependency/security/license issue unexamined; no real credentials/live data in fixtures or evidence.
- **RSC-070-A03**: Final local-readiness statement accurately lists remaining gaps; central/backend work waits owner hands-on test and explicit approval, no remote push/publication/deployment.

**Refusal/uncertainty rule:** A denied/cancelled-before-admission operation adds no business, cash, stock, entitlement or dispatch effects (permitted rejection/security audit only). Cancellation/timeout after possible admission never asserts rollback; recover original result and preserve physical evidence.

**Verification layers:** L0_EVIDENCE_CONTRACT, L4_LOCAL_OWNER_HANDOFF.

**Related requirements:** UX-01, UX-04, REL-08, Q-SEC-10.

**Qualification note:** Automatable design only; installed/hardware variants still need their own actual execution evidence.

**Open requirement questions (original IDs):** RQ-DEVICES, RQ-FISCAL, RQ-LOAD, RQ-RETENTION

**Central portion:** None deferred.

**Record:** Exact source/build/artifact/dependency/schema/config identity, fixture seed, platform/roles/driver/network/fault schedule and timestamp.; Independent fresh production read/ledger/operation/checkpoint observations, UI semantic/assertion record and redacted screenshots/logs only as supporting evidence.; Per-assertion actual result and pass/fail/blocked reason; no result is inferred from the design inventory or other platform totals.

**Result:** NOT_RUN.
