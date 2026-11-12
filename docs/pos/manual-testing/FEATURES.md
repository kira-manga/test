# Feature-by-feature owner checklist

**NOT RUN.** These are the 105 frozen feature definitions. Follow the linked original business scenarios and gate definitions; an unimplemented workflow is BLOCKED, not removed. Local obligations/test needs are unchanged.

## LF-CORE-01

### Four activity profiles and a shared local operating core

**Applicability:** Required baseline

**Required local behavior:**
- Restaurant, cafe, clothing and supermarket each expose their applicable complete native workflows on a shared authority/ledger model; one organization can contain independently configured companies.

**Steps / rehearsal needs from the independent requirements:**
1. Rehearse a full shift separately for all four profiles, not only a retail checkout; verify disabled inapplicable controls do not disable mandatory shared behavior.

**Environment:** real application surfaces: Activity/profile setup and all branch workspaces; actual boundaries: CMP application, Actual local domain services and durable database.

**Original scenario checks:** [TAX-07](SCENARIOS.md#tax-07), [UX-05](SCENARIOS.md#ux-05)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08), [Q-UX-09](QUALITY-AND-PLATFORMS.md#q-ux-09), [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CORE-02

### One authoritative local production runtime

**Applicability:** Required baseline

**Required local behavior:**
- One active branch master, including one-device operation; protected same-device adapter and real P2pKit clients invoke identical permissioned production handlers. No direct UI DAO writes, client financial finalization, in-memory demo replacement or central-direct fallback.

**Steps / rehearsal needs from the independent requirements:**
1. Compare one-device and remote-client sale/return/receiving/close records using fresh production reads; deny a disconnected client and unauthenticated local adapter without effects.

**Environment:** real application surfaces: Branch connection status and cashier workspace; actual boundaries: Headless master runtime, Protected local adapter, P2pKit, Room/SQLite.

**Original scenario checks:** [REL-01](SCENARIOS.md#rel-01), [REL-03](SCENARIOS.md#rel-03), [REL-04](SCENARIOS.md#rel-04), [UX-07](SCENARIOS.md#ux-07)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-DB-05](QUALITY-AND-PLATFORMS.md#q-db-05), [Q-DI-01](QUALITY-AND-PLATFORMS.md#q-di-01), [Q-RES-04](QUALITY-AND-PLATFORMS.md#q-res-04)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CORE-03

### Exact money, quantity, rounding and serialization

**Applicability:** Required baseline

**Required local behavior:**
- EGP two-decimal minor-unit postings; exact quantities up to six fractional digits and unit price/cost up to eight initially; half-up at declared monetary boundaries, stable largest-remainder allocation; checked bounds and versioned rounding. Preserve string identifiers/leading zeros and exact JSON integer strings.

**Steps / rehearsal needs from the independent requirements:**
1. Independent rational arithmetic verifies totals, fractional quantities, refunds and last-unit value residuals; reject overflow, excess precision, NaN/infinity, incompatible currencies/dimensions without any posting on every runtime.

**Environment:** real application surfaces: Amount, quantity and tax controls; receipts; actual boundaries: Production scalar/serializer and pricing/stock persistence.

**Original scenario checks:** [CASH-03](SCENARIOS.md#cash-03), [TAX-04](SCENARIOS.md#tax-04), [TAX-05](SCENARIOS.md#tax-05), [CAT-02](SCENARIOS.md#cat-02), [CAT-03](SCENARIOS.md#cat-03), [PUR-13](SCENARIOS.md#pur-13), [PUR-14](SCENARIOS.md#pur-14), [PUR-15](SCENARIOS.md#pur-15), [PRC-03](SCENARIOS.md#prc-03)

**Original gate checks:** [Q-SEC-07](QUALITY-AND-PLATFORMS.md#q-sec-07)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CORE-04

### Atomic commands, durable identity and honest result recovery

**Applicability:** Required baseline

**Required local behavior:**
- Persist initiating operation ID before submit; bind branch/original actor/semantic payload independently of transport request and authority epoch. Commit business facts, result, audit, journal and jobs together. Expose committed/rejected/in-progress/not-recorded/unknown distinctly; only retry the original operation under recovery rules.

**Steps / rehearsal needs from the independent requirements:**
1. Inject cancellation and process failure at admission/write/commit/response; same ID/content recovers once after restart or approved replacement, changed payload/actor rejects, and NOT_RECORDED never means a bank payment failed.

**Environment:** real application surfaces: Pending operation and recovery views; actual boundaries: Client protected journal, Command handler, Production transaction/result/outbox store.

**Original scenario checks:** [REL-04](SCENARIOS.md#rel-04), [REL-10](SCENARIOS.md#rel-10), [PAY-04](SCENARIOS.md#pay-04), [KIT-02](SCENARIOS.md#kit-02), [STK-06](SCENARIOS.md#stk-06), [PUR-16](SCENARIOS.md#pur-16), [UX-06](SCENARIOS.md#ux-06)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02), [Q-SEC-09](QUALITY-AND-PLATFORMS.md#q-sec-09)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CORE-05

### Immutable history, audit and scoped evidence retention

**Applicability:** Required baseline

**Required local behavior:**
- Keep original actors, snapshots, source allocations and posted facts immutable; corrections append links. Account/product archive cannot cascade-delete history. Retain locally accessible protected receipt/claim evidence for the longest applicable configured remedy/record window, including unresolved obligations.

**Steps / rehearsal needs from the independent requirements:**
1. Rename/disable/archive referenced records and change current policy; historical receipts/audit remain readable only to authorized roles and still support offline returns. Test archive retrieval and retention boundaries with unresolved claims.

**Environment:** real application surfaces: History/audit, receipt lookup, document attachments; actual boundaries: Production history/retention/attachment storage.

**Original scenario checks:** [CASH-10](SCENARIOS.md#cash-10), [RET-09](SCENARIOS.md#ret-09), [RET-13](SCENARIOS.md#ret-13), [KIT-09](SCENARIOS.md#kit-09), [PAY-10](SCENARIOS.md#pay-10), [PUR-15](SCENARIOS.md#pur-15)

**Original gate checks:** [Q-DB-02](QUALITY-AND-PLATFORMS.md#q-db-02), [Q-SEC-08](QUALITY-AND-PLATFORMS.md#q-sec-08), [Q-RES-09](QUALITY-AND-PLATFORMS.md#q-res-09)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CORE-06

### Time, business day and collision-free numbering

**Applicability:** Required baseline

**Required local behavior:**
- Separate UTC event time, Africa/Cairo date, configurable business cutoff and legal document date. Durable sequences/UUIDs and receipt numbering never reuse identity across clock jumps, restart, restore or handover.

**Steps / rehearsal needs from the independent requirements:**
1. Cross midnight/cutoff and DST using deterministic clocks; roll clock backward and exhaust counters. Verify order, session age/locks/throttling and document identity do not reset or collide.

**Environment:** real application surfaces: Timezone/day configuration and dated documents; actual boundaries: Production clock/ID/numbering and ledger paths.

**Original scenario checks:** [CASH-07](SCENARIOS.md#cash-07), [CASH-09](SCENARIOS.md#cash-09), [RET-11](SCENARIOS.md#ret-11), [REL-08](SCENARIOS.md#rel-08)

**Original gate checks:** [Q-RES-12](QUALITY-AND-PLATFORMS.md#q-res-12)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CORE-07

### Typed validation and actionable no-side-effect errors

**Applicability:** Required baseline

**Required local behavior:**
- Bound and validate input before costly work; return stable safe codes/message arguments with permitted resolution details. Reject wrong identity, stock, entitlement, approval, stale version, incompatible schema and storage faults without pretending success or erasing physical evidence.

**Steps / rehearsal needs from the independent requirements:**
1. Exercise every registered mutating boundary with invalid values and direct hostile requests; assert business ledgers/jobs unchanged except allowed rejection audit and verify localized specific next actions.

**Environment:** real application surfaces: Validation, conflict, permission and recovery dialogs; actual boundaries: Every production command/read adapter.

**Original scenario checks:** [STK-01](SCENARIOS.md#stk-01), [STK-03](SCENARIOS.md#stk-03), [OWN-09](SCENARIOS.md#own-09), [CFG-08](SCENARIOS.md#cfg-08), [REL-03](SCENARIOS.md#rel-03)

**Original gate checks:** [Q-SEC-07](QUALITY-AND-PLATFORMS.md#q-sec-07), [Q-UX-04](QUALITY-AND-PLATFORMS.md#q-ux-04)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CORE-08

### Scoped snapshots, durable change cursors and bounded content

**Applicability:** Required baseline

**Required local behavior:**
- Snapshot at a consistent authorized cut; apply page and cursor atomically; missed notifications recover by pull and expired cursor requires fresh snapshot. Enforce byte/row/page/attachment limits and local identity scopes.

**Steps / rehearsal needs from the independent requirements:**
1. Drop/reorder notifications and crash during page apply; KDS/order/stock views converge without duplicate work. Revoke access and replay old cursors; protected records remain unavailable. Test bounded large catalog/attachment transfers and cancellation.

**Environment:** real application surfaces: Search, lists, KDS and realtime status; actual boundaries: Production queries/change stream, Client persistent projections, Content transfer.

**Original scenario checks:** [OWN-03](SCENARIOS.md#own-03), [CFG-05](SCENARIOS.md#cfg-05), [REL-03](SCENARIOS.md#rel-03), [REL-04](SCENARIOS.md#rel-04)

**Original gate checks:** [Q-SEC-07](QUALITY-AND-PLATFORMS.md#q-sec-07), [Q-RES-04](QUALITY-AND-PLATFORMS.md#q-res-04), [Q-DB-07](QUALITY-AND-PLATFORMS.md#q-db-07)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-01

### Separate company, branch, employee and service authority

**Applicability:** Required baseline

**Required local behavior:**
- Branch accounts belong to one branch even for the same human; device admission is not employee login. Company admin, master service, employee and narrow KDS/courier principals have separate protected stores and navigation roots.

**Steps / rehearsal needs from the independent requirements:**
1. Attempt cross-branch/company login and supplied actor spoofing through actual local adapters; a central admin/service credential cannot record a cashier operation and old-user callbacks cannot appear in a new user session.

**Environment:** real application surfaces: Branch sign-in, company/branch selector, security roots; actual boundaries: Local session/admission registry, Protected credential and cache stores.

**Original scenario checks:** [OWN-01](SCENARIOS.md#own-01), [OWN-02](SCENARIOS.md#own-02), [TAX-07](SCENARIOS.md#tax-07), [UX-07](SCENARIOS.md#ux-07)

**Original gate checks:** [Q-SEC-04](QUALITY-AND-PLATFORMS.md#q-sec-04), [Q-BE-08](QUALITY-AND-PLATFORMS.md#q-be-08), [Q-NAV-07](QUALITY-AND-PLATFORMS.md#q-nav-07)

**Central portion:** HELD — Actual central login/company management sessions and their API boundary qualification.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-02

### Trusted local device enrollment and trust persistence

**Applicability:** Required baseline

**Required local behavior:**
- Local delegated administration admits clients to the provisioned branch with protected unique identity and pinned master trust; prelogin access is a minimal allowlist. Discovery/address changes never grant trust, and expired/reused/wrong-branch admission must fail.

**Steps / rehearsal needs from the independent requirements:**
1. Use real enrollment screens and real encrypted P2pKit, restart both sides, move endpoint with same identity and present changed identity at same address. Verify peer-only access cannot read financial data.

**Environment:** real application surfaces: Pair/admit/revoke device and connection recovery; actual boundaries: Production admission and trust store, P2pKit secure sessions.

**Original scenario checks:** [REL-01](SCENARIOS.md#rel-01), [REL-03](SCENARIOS.md#rel-03), [UX-04](SCENARIOS.md#ux-04)

**Original gate checks:** [Q-SEC-01](QUALITY-AND-PLATFORMS.md#q-sec-01), [Q-SEC-02](QUALITY-AND-PLATFORMS.md#q-sec-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-03

### Local employee provisioning, activation, reset and disable

**Applicability:** Required baseline

**Required local behavior:**
- Authorized delegated local administrator can create/activate/reset/disable a branch account offline within company grants. A synced directory profile alone is not an established credential; no insecure default administrator or plaintext password distribution.

**Steps / rehearsal needs from the independent requirements:**
1. Create a synthetic local employee through the real UI/service, establish own protected credential, login, reset and disable across restart; unauthorized actor cannot create/reset/reenable accounts and stale package cannot resurrect a local disable.

**Environment:** real application surfaces: People, activation/reset and account status; actual boundaries: Production local account lifecycle, Secure verifier store, Persistent throttling.

**Original scenario checks:** [OWN-01](SCENARIOS.md#own-01), [CFG-03](SCENARIOS.md#cfg-03), [CFG-05](SCENARIOS.md#cfg-05), [UX-04](SCENARIOS.md#ux-04)

**Original gate checks:** [Q-SEC-03](QUALITY-AND-PLATFORMS.md#q-sec-03), [Q-SEC-06](QUALITY-AND-PLATFORMS.md#q-sec-06)

**Central portion:** HELD — Central directory creation/reset intent and protected delivery remain central-phase work.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-04

### Offline login, refresh rotation and persistent throttling

**Applicability:** Required baseline

**Required local behavior:**
- Authenticate locally with protected employee credential; optional PIN uses qualified protection/throttling. Local access/refresh lifecycle works without WAN; persistent guessing controls survive restart and clock rollback. Refresh rotation is atomic; lost replies do not mint unrelated sessions.

**Steps / rehearsal needs from the independent requirements:**
1. Exercise real login/refresh/expiry/revocation repeatedly with WAN blocked, failed logins and restart. Concurrent refresh/old-token replay must obey the configured policy or require safe local re-login without cloud dependency.

**Environment:** real application surfaces: Sign-in/lock/credential-change; actual boundaries: Production local auth and secure session registry.

**Original scenario checks:** [REL-01](SCENARIOS.md#rel-01), [REL-07](SCENARIOS.md#rel-07), [UX-04](SCENARIOS.md#ux-04)

**Original gate checks:** [Q-SEC-03](QUALITY-AND-PLATFORMS.md#q-sec-03), [Q-RES-03](QUALITY-AND-PLATFORMS.md#q-res-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-05

### Logout, session revocation and explicit device move

**Applicability:** Required baseline

**Required local behavior:**
- One interactive session per account by default; explicit authorized device move, with configurable bounded same-branch additional sessions. Logout/revocation clears private UI/tokens/subscriptions immediately without stopping master or transferring drawer; offline revocation intent stays truthful and durable. Unresolved operations remain protected and attributed to their originator.

**Steps / rehearsal needs from the independent requirements:**
1. A then B on same device, A move to another admitted device, disconnected logout and late response tests; verify old tokens/personal data excluded and financial recovery never becomes B collection.

**Environment:** real application surfaces: User switch, lock, logout, session management; actual boundaries: Production session and client private-state lifecycle.

**Original scenario checks:** [OWN-02](SCENARIOS.md#own-02), [CASH-06](SCENARIOS.md#cash-06), [UX-06](SCENARIOS.md#ux-06), [UX-07](SCENARIOS.md#ux-07)

**Original gate checks:** [Q-SEC-04](QUALITY-AND-PLATFORMS.md#q-sec-04), [Q-NAV-07](QUALITY-AND-PLATFORMS.md#q-nav-07), [Q-DI-02](QUALITY-AND-PLATFORMS.md#q-di-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-06

### Company ceilings, granular grants and least-privilege enforcement

**Applicability:** Required baseline

**Required local behavior:**
- Editable defaults for cashier/waiter/kitchen/inventory/manager/auditor/service roles; current local grants enforce explicit deny, bounded branch delegation, both amount and percentage ceilings, separate perform/approve/administer permissions. Omission is not unlimited authority; unknown grants deny.

**Steps / rehearsal needs from the independent requirements:**
1. Exercise command, direct read, related ID, subscription and export under allow/deny/missing-limit matrices. Routine checkout remains available when unrelated optional approvals are unconfigured.

**Environment:** real application surfaces: Roles, limits, permission failures; actual boundaries: Production policy evaluator on every local boundary.

**Original scenario checks:** [CFG-01](SCENARIOS.md#cfg-01), [CFG-02](SCENARIOS.md#cfg-02), [CFG-03](SCENARIOS.md#cfg-03), [CFG-04](SCENARIOS.md#cfg-04), [CFG-05](SCENARIOS.md#cfg-05), [CFG-06](SCENARIOS.md#cfg-06), [CFG-08](SCENARIOS.md#cfg-08), [OWN-03](SCENARIOS.md#own-03), [OWN-07](SCENARIOS.md#own-07)

**Original gate checks:** [Q-SEC-05](QUALITY-AND-PLATFORMS.md#q-sec-05), [Q-SEC-06](QUALITY-AND-PLATFORMS.md#q-sec-06)

**Central portion:** HELD — Company-head central grant publication and online global role administration.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-07

### Inline version-bound approvals and bounded self-approval

**Applicability:** Required baseline

**Required local behavior:**
- Approver authenticates personally inside cashier flow; grant binds requester, branch, action, document/version, lines, amount and reason. Changed request invalidates approval. Named company-explicit self-approval applies only to authorized actions, never an implicit recovery/write-off bypass.

**Steps / rehearsal needs from the independent requirements:**
1. Approve then change quantity/amount/actor/action; deny stale grant without mutation. Replay approval and test bounds. Cashier remains collector before/after manager dismissal and logout.

**Environment:** real application surfaces: Approval prompt and audit; actual boundaries: Production approval/session/policy and guarded commands.

**Original scenario checks:** [CFG-01](SCENARIOS.md#cfg-01), [CFG-02](SCENARIOS.md#cfg-02), [CFG-04](SCENARIOS.md#cfg-04), [UX-07](SCENARIOS.md#ux-07)

**Original gate checks:** [Q-SEC-05](QUALITY-AND-PLATFORMS.md#q-sec-05), [Q-UX-05](QUALITY-AND-PLATFORMS.md#q-ux-05)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-08

### Owner-private orders and narrow task/receipt projections

**Applicability:** Required baseline

**Required local behavior:**
- Ordinary coworkers cannot list/open/edit/subscribe/cache another owner order. Kitchen/courier see only assigned work; authorized return staff get only necessary historical receipt fields. Quantity access does not grant cost/margin/customer/export access.

**Steps / rehearsal needs from the independent requirements:**
1. Test UI, direct ID, related ID, alternate endpoints, subscriptions and cached projection after role change; assert field absence before serialization rather than merely hidden controls.

**Environment:** real application surfaces: Own-order lists, KDS, courier, historical receipt lookup; actual boundaries: Production authorized query/projection layer.

**Original scenario checks:** [OWN-03](SCENARIOS.md#own-03), [OWN-07](SCENARIOS.md#own-07), [OWN-10](SCENARIOS.md#own-10), [CFG-06](SCENARIOS.md#cfg-06)

**Original gate checks:** [Q-SEC-04](QUALITY-AND-PLATFORMS.md#q-sec-04), [Q-SEC-06](QUALITY-AND-PLATFORMS.md#q-sec-06)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-09

### Named offline recovery delegation and protected service secrets

**Applicability:** Required baseline

**Required local behavior:**
- Locally prepared company-named recovery delegate authenticates separately and approves a particular target/checkpoint/handover. Generic manager, paired device or backup possession is insufficient. Device keys and service material are independent of cashier credentials and never cloned/exported as bearer secrets.

**Steps / rehearsal needs from the independent requirements:**
1. With old master and WAN unavailable, verify correct/incorrect delegate and target approval; deny key unavailable, wrong-branch and stolen backup without creating an authority. Verify local recovery access remains usable after safe restart.

**Environment:** real application surfaces: Recovery access setup and delegated recovery approval; actual boundaries: Production protected recovery auth, Platform keystore, Authority records.

**Original scenario checks:** [REL-05](SCENARIOS.md#rel-05), [REL-06](SCENARIOS.md#rel-06), [REL-09](SCENARIOS.md#rel-09), [UX-04](SCENARIOS.md#ux-04)

**Original gate checks:** [Q-RES-06](QUALITY-AND-PLATFORMS.md#q-res-06), [Q-RES-10](QUALITY-AND-PLATFORMS.md#q-res-10), [Q-DB-10](QUALITY-AND-PLATFORMS.md#q-db-10)

**Central portion:** HELD — Actual central issuance/distribution of named delegation and central key recovery.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-AUTH-10

### Privacy, secure storage and bounded diagnostics

**Applicability:** Required baseline

**Required local behavior:**
- Qualified verifier/crypto and platform storage protect credentials, tokens, keys and backups. Logs, crash/diagnostic exports, notification previews, screenshots/app-switcher views and image caches must not disclose secrets or unauthorized contact/cost data.

**Steps / rehearsal needs from the independent requirements:**
1. Use synthetic canary secrets/private fields; exercise errors, logout, backup/export and notifications and verify absence on all exposed paths. Check OS permission/keystore failures fail closed without new permissive identity.

**Environment:** real application surfaces: Diagnostics, privacy previews and secure-storage failures; actual boundaries: Production logger/export/notification, Platform protected storage and app privacy.

**Original scenario checks:** [OWN-02](SCENARIOS.md#own-02), [CFG-07](SCENARIOS.md#cfg-07), [UX-07](SCENARIOS.md#ux-07)

**Original gate checks:** [Q-SEC-03](QUALITY-AND-PLATFORMS.md#q-sec-03), [Q-SEC-08](QUALITY-AND-PLATFORMS.md#q-sec-08), [Q-SEC-10](QUALITY-AND-PLATFORMS.md#q-sec-10)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-SETUP-01

### Resumable role-aware native setup and live acknowledgement

**Applicability:** Required baseline

**Required local behavior:**
- Wizard persists activity/branch identity, EGP/Cairo/cutoff/legal identity, channels/policies, accounts/recovery, master/client setup, catalog/locations, openings and peripherals. Resume safe checkpoints; responsible go-live acknowledgement is auditable. No recurring wizard/cloud lease on restart.

**Steps / rehearsal needs from the independent requirements:**
1. Interrupt each local setup step and resume through the actual app; repeat stable import/opening IDs and verify no duplicated ledgers. Required versus optional readiness actions are distinguished.

**Environment:** real application surfaces: Setup wizard and go-live checklist; actual boundaries: Production setup state, local services and persistence.

**Original scenario checks:** [UX-01](SCENARIOS.md#ux-01), [UX-02](SCENARIOS.md#ux-02), [UX-03](SCENARIOS.md#ux-03), [UX-04](SCENARIOS.md#ux-04)

**Original gate checks:** [Q-UX-06](QUALITY-AND-PLATFORMS.md#q-ux-06)

**Central portion:** HELD — Initial trusted organization/company/branch registration and actual central recovery verification; already-provisioned qualification fixture must be clearly labelled.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-SETUP-02

### Catalog import preview, validation and retry-safe application

**Applicability:** Required baseline

**Required local behavior:**
- Offer sample CSV, explicit mappings/dry-run errors and duplicate detection for variants/barcodes/UOM/recipes/tax/tracking. Preview counts, persist import identity/checkpoints and preserve unaffected valid records on rejection. Global new identifiers remain unpublished unless preallocated/authorized.

**Steps / rehearsal needs from the independent requirements:**
1. Import valid/duplicate/corrupt rows with interruption/resume; verify stable SKU/opening IDs, exact quantities and explicit partial-import policy instead of unnoticed duplicates or scope leakage.

**Environment:** real application surfaces: Catalog import and validation preview; actual boundaries: Production local staging/validation/activation and bounded file parsing.

**Original scenario checks:** [UX-01](SCENARIOS.md#ux-01), [CAT-01](SCENARIOS.md#cat-01), [CAT-08](SCENARIOS.md#cat-08), [CAT-09](SCENARIOS.md#cat-09)

**Original gate checks:** [Q-UX-06](QUALITY-AND-PLATFORMS.md#q-ux-06), [Q-DB-09](QUALITY-AND-PLATFORMS.md#q-db-09)

**Central portion:** HELD — Global catalog publication and cross-branch uniqueness at central authority.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-SETUP-03

### Opening stock, valuation, supplier obligations and cash

**Applicability:** Required baseline

**Required local behavior:**
- Count opening stock by location/lot/serial with value; opening supplier debt is explicit migration/opening evidence, not a fictional purchase. Opening drawer float is a separate custody record, with sales/revenue zero.

**Steps / rehearsal needs from the independent requirements:**
1. Enter, retry and resume opening quantities/value, liabilities and float; independently reconcile quantity/value/cash with zero sales and no duplicate receipt/invoice.

**Environment:** real application surfaces: Opening inventory, supplier balances and drawer count; actual boundaries: Production opening/stock/cost/cash/obligation ledgers.

**Original scenario checks:** [UX-01](SCENARIOS.md#ux-01), [UX-02](SCENARIOS.md#ux-02), [CASH-01](SCENARIOS.md#cash-01)

**Original gate checks:** [Q-UX-06](QUALITY-AND-PLATFORMS.md#q-ux-06), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-SETUP-04

### Isolated training and synthetic owner qualification

**Applicability:** Required baseline

**Required local behavior:**
- Training uses distinct database, transport identity and receipt namespace. Real production local handlers/persistence must also be exercised using a labelled synthetic already-provisioned qualification fixture; training/demo stores cannot stand in for that integration. No production-default admin is introduced.

**Steps / rehearsal needs from the independent requirements:**
1. Run sample sale/refund/close in training then switch live; verify no training money/stock/numbering/outbox appears. Separately run the production local path against isolated synthetic accounts and preserve fixture manifest.

**Environment:** real application surfaces: Training banner, live switch, qualification setup instructions; actual boundaries: Actual production local services, Separate training/live storage and identity.

**Original scenario checks:** [UX-03](SCENARIOS.md#ux-03), [UX-04](SCENARIOS.md#ux-04)

**Original gate checks:** [Q-UX-06](QUALITY-AND-PLATFORMS.md#q-ux-06), [Q-BE-08](QUALITY-AND-PLATFORMS.md#q-be-08)

**Central portion:** HELD — Live central ingestion/fiscal rejection of training after owner authorization.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-SETUP-05

### Native administration, offline drafts and actual local delegation

**Applicability:** Required baseline

**Required local behavior:**
- Complete CMP screens cover structure/people/catalog/price/policy/inventory/purchasing/reports/devices/recovery. Local delegated actions call actual master services; unavailable global administration is a clearly pending draft, never fake success or a developer-console dependency.

**Steps / rehearsal needs from the independent requirements:**
1. Exercise local parts of all five administration areas, reauthorize saved drafts after permissions/revisions change, and check context/cache isolation. Inventory, purchasing and recovery cannot be reduced to static screens.

**Environment:** real application surfaces: T28A–T28E native administration workspaces; actual boundaries: Production local administrative services and durable drafts.

**Original scenario checks:** [CFG-03](SCENARIOS.md#cfg-03), [CFG-05](SCENARIOS.md#cfg-05), [TAX-01](SCENARIOS.md#tax-01), [UX-01](SCENARIOS.md#ux-01), [UX-08](SCENARIOS.md#ux-08)

**Original gate checks:** [Q-BE-09](QUALITY-AND-PLATFORMS.md#q-be-09), [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10), [Q-NAV-07](QUALITY-AND-PLATFORMS.md#q-nav-07)

**Central portion:** HELD — Actual remote unpaired-device company login and global management/report publication endpoints, without requiring a branch master for those central operations.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-01

### Simple stocked SKUs, capabilities, assortment and attributes

**Applicability:** Required baseline

**Required local behavior:**
- Company templates compose can-purchase/sell/produce, tracked stock and fulfillment; branch assortment is distinct from company base. Typed searchable attributes (brand/material/flavor/allergens) cannot run arbitrary stock/payment behavior. Archive instead of deleting historical identity.

**Steps / rehearsal needs from the independent requirements:**
1. Create/preview representative products for all activities; authorize local changes and validate activated catalog. Invalid capability edits fail without disabling existing valid sales.

**Environment:** real application surfaces: Product template/editor, category and branch assortment; actual boundaries: Production catalog validation/activation, Stock/pricing services.

**Original scenario checks:** [CAT-01](SCENARIOS.md#cat-01), [CAT-08](SCENARIOS.md#cat-08), [CAT-09](SCENARIOS.md#cat-09), [TAX-07](SCENARIOS.md#tax-07)

**Original gate checks:** [Q-UX-04](QUALITY-AND-PLATFORMS.md#q-ux-04)

**Central portion:** HELD — Company-global catalog publication/uniqueness.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-02

### Selected size/color variants and labels

**Applicability:** Required baseline

**Required local behavior:**
- Generate only explicitly selected valid combinations; each stocked variant has independent SKU, stock and barcode with inherited defaults. Preview counts and support bulk prices/barcodes and clothing labels.

**Steps / rehearsal needs from the independent requirements:**
1. Choose a sparse size/color matrix, import twice and scan/return individual variants; assert unselected combinations absent and stock/receipt identity preserved. Qualify physical label printing separately.

**Environment:** real application surfaces: Variant creation/selection, bulk editor and label output; actual boundaries: Production catalog/import, Variant stock and scanner paths.

**Original scenario checks:** [CAT-01](SCENARIOS.md#cat-01), [UX-05](SCENARIOS.md#ux-05)

**Original gate checks:** [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08)

**Central portion:** HELD — Actual company publication of global variant/barcode identifiers.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-03

### Units, packs and exact measured quantities

**Applicability:** Required baseline

**Required local behavior:**
- Product-specific base/purchase/sale units and precise dimension-compatible conversions; historical documents pin pack definition. Weighted/manual entry obeys SKU precision; mass-volume requires explicit evidenced product conversion.

**Steps / rehearsal needs from the independent requirements:**
1. Receive two 12-unit packs then set future definition to 10: old receipt remains 24; new one uses 20. Reject fractional serials/incompatible dimensions/excess precision without changing stock.

**Environment:** real application surfaces: UOM/pack settings, weight/quantity entry and receiving; actual boundaries: Production conversions, document snapshots and stock.

**Original scenario checks:** [CAT-02](SCENARIOS.md#cat-02), [CAT-03](SCENARIOS.md#cat-03), [STK-05](SCENARIOS.md#stk-05)

**Original gate checks:** [Q-UX-09](QUALITY-AND-PLATFORMS.md#q-ux-09)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-04

### Multiple barcodes, weighted formats and collision guards

**Applicability:** Required baseline

**Required local behavior:**
- Preserve leading zeros and configured namespace/checksum/product/weight or price fields; reject ambiguous/colliding/malformed codes. Embedded price cannot bypass current company context. Unknown code supports authorized lookup/create-request, never untracked stock.

**Steps / rehearsal needs from the independent requirements:**
1. Scan leading-zero, variant, pack, weight and price codes; change company price and verify approved interpretation/reweigh path. Rapid scanner suffixes cannot double-enter or bypass manual-entry rules.

**Environment:** real application surfaces: Barcode rule editor, scan field and unknown-code resolution; actual boundaries: Production scanner parser/catalog lookup/price authority.

**Original scenario checks:** [STK-05](SCENARIOS.md#stk-05), [CAT-03](SCENARIOS.md#cat-03), [CAT-01](SCENARIOS.md#cat-01), [UX-05](SCENARIOS.md#ux-05)

**Original gate checks:** [Q-UX-05](QUALITY-AND-PLATFORMS.md#q-ux-05), [Q-UX-09](QUALITY-AND-PLATFORMS.md#q-ux-09)

**Central portion:** HELD — Company-wide arbitrary barcode publication or allocation of branch namespaces.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-05

### Lot/expiry/quarantine and FEFO

**Applicability:** Required baseline

**Required local behavior:**
- Lot provenance, expiry, recalls/blocks and quarantine are enforced; only released unexpired unreserved units are sellable. FEFO physical selection is independent of moving-average valuation; inspection release is exactly once.

**Steps / rehearsal needs from the independent requirements:**
1. Mix valid/expired/quarantined/reserved lots; request more than sellable quantity and race release/retry. Inspect chosen allocations, exact released amount and original provenance.

**Environment:** real application surfaces: Lot/expiry, inspection and availability views; actual boundaries: Production lot allocation/quality/custody ledgers.

**Original scenario checks:** [CAT-05](SCENARIOS.md#cat-05), [STK-04](SCENARIOS.md#stk-04), [STK-09](SCENARIOS.md#stk-09), [PUR-06](SCENARIOS.md#pur-06)

**Original gate checks:** [Q-UX-09](QUALITY-AND-PLATFORMS.md#q-ux-09), [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-06

### Serial identity through purchase, sale and return

**Applicability:** Required baseline

**Required local behavior:**
- Whole serialized units with unique registered identity and immutable receipt/sale/return provenance. Return must match the original sold serial and unused entitlement; local checks do not promise arbitrary global offline uniqueness.

**Steps / rehearsal needs from the independent requirements:**
1. Receive duplicate serial and fractional quantity, sell wrong/held serial, return another serial and race duplicate return; reject without physical or financial mutation.

**Environment:** real application surfaces: Serial capture and receipt/return verification; actual boundaries: Production serial registry/stock/entitlement.

**Original scenario checks:** [CAT-04](SCENARIOS.md#cat-04), [CAT-08](SCENARIOS.md#cat-08), [PUR-06](SCENARIOS.md#pur-06)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-SEC-07](QUALITY-AND-PLATFORMS.md#q-sec-07)

**Central portion:** HELD — Cross-branch global serial conflict handling/registration where applicable.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-07

### Ingredients, recipes, choices and graph validation

**Applicability:** Required baseline

**Required local behavior:**
- Purchase-only ingredients, exact dimensional recipes/yields, versioned modifiers/exclusions/required choices/substitutions and station routes. Cyclic kits/recipes, incompatible units and missing required components reject before activation.

**Steps / rehearsal needs from the independent requirements:**
1. Configure a versioned burger with ingredient substitutions/modifiers and invalid graph variants; verify full atomic availability and historical cancellation uses original allocation, not current recipe.

**Environment:** real application surfaces: Recipe/modifier/ingredient editors and required choices; actual boundaries: Production catalog/recipe validation and kitchen allocation.

**Original scenario checks:** [KIT-01](SCENARIOS.md#kit-01), [KIT-09](SCENARIOS.md#kit-09), [CAT-08](SCENARIOS.md#cat-08), [STK-03](SCENARIOS.md#stk-03)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-SEC-07](QUALITY-AND-PLATFORMS.md#q-sec-07)

**Central portion:** HELD — Company recipe/catalog publication; local activated behavior remains required now.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-08

### Finished production and assembled stock

**Applicability:** Required baseline

**Required local behavior:**
- Ingredients issue on production send; actual finished yield posts/release once, with work-in-progress/waste. Estimated yield is unavailable for sale. Assembled kit becomes a stocked output whose sale does not consume components again.

**Steps / rehearsal needs from the independent requirements:**
1. Produce 10 actual units, retry completion and sell 3: output 7 with one ingredient issue. Partial yield/start cancellation and blocked output cannot create sellable units.

**Environment:** real application surfaces: Production request, KDS/yield/waste and assembly; actual boundaries: Production issue/output/cost ledger and retail finalization.

**Original scenario checks:** [CAT-06](SCENARIOS.md#cat-06), [CAT-07](SCENARIOS.md#cat-07), [KIT-04](SCENARIOS.md#kit-04), [KIT-12](SCENARIOS.md#kit-12)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-09

### Virtual/configurable mixed-fulfillment kits

**Applicability:** Required baseline

**Required local behavior:**
- No fictional virtual parent stock; check components and allocate parent charge exactly. Kitchen, retail and service children retain their own reservation/issue/completion timing; parent payment never reissues them.

**Steps / rehearsal needs from the independent requirements:**
1. Sell a combo containing recipe, retail bottle and service; independently reconcile each effect and return allocation. Missing any required component rejects selected batch atomically.

**Environment:** real application surfaces: Combo choices, cart/receipt and task views; actual boundaries: Production kit expansion, pricing, reservations and fulfillment.

**Original scenario checks:** [CAT-07](SCENARIOS.md#cat-07), [STK-03](SCENARIOS.md#stk-03), [KIT-12](SCENARIOS.md#kit-12), [PRC-04](SCENARIOS.md#prc-04)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-10

### Non-stock services without stock bypass

**Applicability:** Required baseline

**Required local behavior:**
- Explicit fixed/quantity/duration service charges, completion and refunds without fictional stock. Sold-out tracked physical SKU cannot be casually retyped non-stock; controlled version/migration/new SKU preserves history. No implied booking/subscription/rental/gift-card system.

**Steps / rehearsal needs from the independent requirements:**
1. Sell an alteration/wrapping service and reconcile no stock movement; attempt changing exhausted physical item tracking and verify denial with original receipts/history intact.

**Environment:** real application surfaces: Service product, completion and catalog tracking controls; actual boundaries: Production catalog capability guards and service fulfillment.

**Original scenario checks:** [CAT-09](SCENARIOS.md#cat-09), [STK-01](SCENARIOS.md#stk-01), [CAT-07](SCENARIOS.md#cat-07)

**Original gate checks:** [Q-UX-04](QUALITY-AND-PLATFORMS.md#q-ux-04)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CAT-11

### Receipt-linked containers and refundable deposits

**Applicability:** Configurable first-release capability: qualify enabled and disabled policies

**Required local behavior:**
- When enabled, separate container custody, deposit liability/tax mapping and receipt-bound remaining entitlement. Return once with actual refund; no spendable customer balance. Disabled policy cannot be bypassed by generic tender.

**Steps / rehearsal needs from the independent requirements:**
1. Record deposit/container, return then retry concurrently from another client; inspect one entitlement consumption, one actual refund and correct container condition.

**Environment:** real application surfaces: Deposit receipt and container return; actual boundaries: Production deposit/entitlement/cash/custody services.

**Original scenario checks:** [CAT-10](SCENARIOS.md#cat-10), [RET-03](SCENARIOS.md#ret-03)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PRICE-01

### Company price priority and branch overrides

**Applicability:** Required baseline

**Required local behavior:**
- Branch override references exact company product/variant/context revision; newer relevant company release supersedes older override, not unrelated SKU. Authorized subsequent override can bind new revision. Supplier cost cannot auto-publish selling price.

**Steps / rehearsal needs from the independent requirements:**
1. Verify 100/r5 -> 120/r5 -> 150/r6 locally including stale arrival, unrelated SKU and denied branch edits; preserve audit and original sold prices.

**Environment:** real application surfaces: Price editor/preview and effective-version display; actual boundaries: Production local policy activation and price selection.

**Original scenario checks:** [TAX-01](SCENARIOS.md#tax-01), [TAX-02](SCENARIOS.md#tax-02), [PRC-06](SCENARIOS.md#prc-06)

**Original gate checks:** [Q-SEC-06](QUALITY-AND-PLATFORMS.md#q-sec-06), [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01)

**Central portion:** HELD — Actual company release publication and synchronization.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PRICE-02

### Deterministic contexts, overlap preview and open-order policy

**Applicability:** Required baseline

**Required local behavior:**
- Channel/quantity/date/time/product/variant priority and stable rule-ID tie-break determine price; incompatible overlap flagged. Default same channel price unless explicit. Preserve protected/quoted/part-paid/locked snapshots; optionally reprice eligible open/parked lines and increment version visibly.

**Steps / rehearsal needs from the independent requirements:**
1. Preview overlapping rules then evaluate on different clients/retries. Race collection with activation; stale total cannot silently finalize and new lines follow current effective context.

**Environment:** real application surfaces: Context/overlap preview, open-order policy and cashier totals; actual boundaries: Production price engine/version locks.

**Original scenario checks:** [PRC-01](SCENARIOS.md#prc-01), [TAX-03](SCENARIOS.md#tax-03)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-UX-04](QUALITY-AND-PLATFORMS.md#q-ux-04)

**Central portion:** HELD — Global authoring/publication of contexts and policy.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PRICE-03

### Promotions, quantity tiers, bundles and codes

**Applicability:** Required baseline

**Required local behavior:**
- Fixed/percentage, quantity-tier, fixed-bundle and buy-X-get-Y campaigns with product/category/channel/date/code conditions, caps and exclusions. Default exclusive group chooses best eligible reduction with declared priority/ID ties, not an arbitrary global optimizer. Per-basket/branch limits enforced; unsupported global uniqueness rejected unless truly allocated.

**Steps / rehearsal needs from the independent requirements:**
1. Competing equal/unequal campaigns, unavailable free unit and collection-lock basket changes; calculate exact eligible discounted units and demonstrate same result across order/retry/platform.

**Environment:** real application surfaces: Promotion editor, calculation preview, scan/cart/receipt; actual boundaries: Production promotion and stock/collection validation.

**Original scenario checks:** [PRC-01](SCENARIOS.md#prc-01), [PRC-02](SCENARIOS.md#prc-02), [PRC-03](SCENARIOS.md#prc-03), [PRC-04](SCENARIOS.md#prc-04), [PRC-05](SCENARIOS.md#prc-05)

**Original gate checks:** [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08), [Q-UX-09](QUALITY-AND-PLATFORMS.md#q-ux-09), [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01)

**Central portion:** HELD — Actual global publication or explicitly supported cross-branch entitlement coordination, not required as an invented offline promise.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PRICE-04

### Manual discount grants and deterministic refundable allocation

**Applicability:** Required baseline

**Required local behavior:**
- Permissioned fixed/percentage line/order reduction honors both ceilings, caps and stacking. Advertised fixed payable reduction is exact, nonnegative, allocated once to concrete quantities with stable minor-unit remainders. Retained item is not repriced on partial return.

**Steps / rehearsal needs from the independent requirements:**
1. Equal-value two-item price100 allocates50 each; return one for50. Exercise mixed-tax order discount and remainder ties using independent arithmetic, stale approval and denied stacking.

**Environment:** real application surfaces: Discount/approval controls, allocation receipt/return; actual boundaries: Production discount allocation/approval/entitlement.

**Original scenario checks:** [CFG-01](SCENARIOS.md#cfg-01), [CFG-02](SCENARIOS.md#cfg-02), [TAX-05](SCENARIOS.md#tax-05), [PRC-02](SCENARIOS.md#prc-02), [PRC-03](SCENARIOS.md#prc-03)

**Original gate checks:** [Q-SEC-05](QUALITY-AND-PLATFORMS.md#q-sec-05), [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PRICE-05

### Inclusive/exclusive tax and explicit charges

**Applicability:** Required baseline

**Required local behavior:**
- Product/price tax-included checkbox separate from tax category/rate; mixed modes preserve net/tax/gross. Service base defaults to eligible item net after discount excluding tax/delivery/tips/self; delivery zones/amounts and optional packaging are explicit. No hidden takeaway delivery fee or double inclusive tax.

**Steps / rehearsal needs from the independent requirements:**
1. Illustrative114 inclusive plus100 exclusive at14% yields net200 tax28 payable228. Independently test charge bases/rounding and original performed-versus-unperformed charge refund rules.

**Environment:** real application surfaces: Tax/charge settings, price display, receipts/refunds; actual boundaries: Production tax/charge/snapshot arithmetic.

**Original scenario checks:** [TAX-04](SCENARIOS.md#tax-04), [TAX-05](SCENARIOS.md#tax-05), [TAX-06](SCENARIOS.md#tax-06), [RET-09](SCENARIOS.md#ret-09)

**Original gate checks:** [Q-UX-02](QUALITY-AND-PLATFORMS.md#q-ux-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PRICE-06

### Optional tips with separate custody/accounting

**Applicability:** Configurable baseline: test enabled and disabled tip policy

**Required local behavior:**
- When configured, voluntarily recorded tips have explicit separate custody/accounting and cannot be silently treated as item revenue or automatic employee entitlement.

**Steps / rehearsal needs from the independent requirements:**
1. Record split cash/external collection with tip; independently reconcile payable/tender/cash/tip category and reject unauthorized or disabled tip path without effect.

**Environment:** real application surfaces: Tender and tip controls, tender/custody reports; actual boundaries: Production payment/cash/report mappings.

**Original scenario checks:** [TAX-09](SCENARIOS.md#tax-09), [PAY-03](SCENARIOS.md#pay-03)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CASH-01

### Exclusive drawer custody and counted opening

**Applicability:** Required baseline

**Required local behavior:**
- One custodian/active cash session per drawer, one default drawer per cashier. Count denomination or total and source; float is not revenue. Device move/lock/logout does not change custody; incoming cashier recounts retained float.

**Steps / rehearsal needs from the independent requirements:**
1. Open1000, retry and compete with another cashier: one opening, zero sales, no competing custody or session mutation.

**Environment:** real application surfaces: Drawer assignment/open/count/lock/resume; actual boundaries: Production cash/session/custody ledger and uniqueness.

**Original scenario checks:** [CASH-01](SCENARIOS.md#cash-01), [CASH-02](SCENARIOS.md#cash-02), [CASH-06](SCENARIOS.md#cash-06), [UX-02](SCENARIOS.md#ux-02)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CASH-02

### Physical cash movements and available cash

**Applicability:** Required baseline

**Required local behavior:**
- Actual receipts/change/refunds and authorized categorized pay-ins/pay-outs post once in current collecting/paying session. External tenders never add drawer cash; insufficient actual/recorded cash leaves obligation due.

**Steps / rehearsal needs from the independent requirements:**
1. Independently reconcile1000+5000-400-200+100-150-3000=2350; external4000 separate. Prior-day refund affects current drawer, no invented negative drawer or repeated change.

**Environment:** real application surfaces: Cash movement/refund/pay-out/tender totals; actual boundaries: Production cash/tender/expense ledgers.

**Original scenario checks:** [CASH-03](SCENARIOS.md#cash-03), [CASH-07](SCENARIOS.md#cash-07), [CASH-12](SCENARIOS.md#cash-12), [PAY-03](SCENARIOS.md#pay-03), [PAY-10](SCENARIOS.md#pay-10)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CASH-03

### Safe drops, transfers and acknowledgement

**Applicability:** Required baseline

**Required local behavior:**
- One transfer identity with sending/receiving custody, actors and acknowledgement; safe drop/float is not expense/revenue. Unacknowledged receipt remains custody exception.

**Steps / rehearsal needs from the independent requirements:**
1. Count2360 retain500 yields transfer1860. Lose/duplicate ACK and retry from another client: one transfer, explicit unresolved custody until received.

**Environment:** real application surfaces: Safe drop/receipt/transfer and exceptions; actual boundaries: Production transfer/custody records.

**Original scenario checks:** [CASH-05](SCENARIOS.md#cash-05), [CASH-11](SCENARIOS.md#cash-11)

**Original gate checks:** [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-CASH-04

### Closing cut, count, variance and append-only review

**Applicability:** Required baseline

**Required local behavior:**
- Open/Closing/SubmittedClosed/Reviewed; cancel Closing safely only before submission. Establish cut, resolve unknowns and explicitly hand off unpaid orders. Preserve actual count/recounts/reasons; later review appends even after next shift.

**Steps / rehearsal needs from the independent requirements:**
1. Race close/payment and restart unknown collection. Expected2350/count2360 gives+10, no invented sale/debt. Test blind/visible count policy and forbidden ordinary reopen.

**Environment:** real application surfaces: Closing/pending work/count/disposition/review; actual boundaries: Production close-cut/count/review and result recovery.

**Original scenario checks:** [CASH-04](SCENARIOS.md#cash-04), [CASH-08](SCENARIOS.md#cash-08), [CASH-09](SCENARIOS.md#cash-09), [CASH-10](SCENARIOS.md#cash-10)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02), [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-ORDER-01

### Activity-specific cart, rapid scan/search and parked orders

**Applicability:** Required baseline

**Required local behavior:**
- Local indexed search, scan/quantity, clothing variant and food modifier/required choices. Show permitted availability and authoritative price interpretation. Owner-private durable draft/parked orders do not promise stock by default.

**Steps / rehearsal needs from the independent requirements:**
1. Actual touch/key/scanner rapid entry, park/restart/resume offline; unknown barcode cannot create generic untracked stock and repeated input cannot double-submit.

**Environment:** real application surfaces: Cashier catalog/cart/owned parked lists; actual boundaries: Production catalog/search/order services and protected draft store.

**Original scenario checks:** [OWN-03](SCENARIOS.md#own-03), [OWN-11](SCENARIOS.md#own-11), [UX-05](SCENARIOS.md#ux-05), [UX-06](SCENARIOS.md#ux-06), [STK-05](SCENARIOS.md#stk-05)

**Original gate checks:** [Q-UX-04](QUALITY-AND-PLATFORMS.md#q-ux-04), [Q-UX-05](QUALITY-AND-PLATFORMS.md#q-ux-05)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-ORDER-02

### Explicit handoff and manager reassignment

**Applicability:** Required baseline

**Required local behavior:**
- Owner persists until recipient accepts one version-bound durable request. Manager reassignment needs permission/reason. Creator/prior collectors/drawers never relabel; unknown payment requires resolution or supervised transfer.

**Steps / rehearsal needs from the independent requirements:**
1. Duplicate accept and race payment/handoff/reassignment; exactly one current owner, unchanged historical actors/custody and no partial transition on denial.

**Environment:** real application surfaces: Offer/accept handoff and reassignment; actual boundaries: Production ownership/version/collection-lock transactions.

**Original scenario checks:** [OWN-04](SCENARIOS.md#own-04), [OWN-05](SCENARIOS.md#own-05), [OWN-06](SCENARIOS.md#own-06), [CASH-06](SCENARIOS.md#cash-06)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-ORDER-03

### Tables, seats and compatible split/merge

**Applicability:** Required baseline

**Required local behavior:**
- Floor/table layout/status/select/move, optional guest/seat allocation. Table move is not ownership/payment/stock replay. Split/merge requires compatible same-branch price/tax/fulfillment and both owners/grant; exact quantities/fees/partial payments conserved.

**Steps / rehearsal needs from the independent requirements:**
1. Split partially paid bill with charges, merge compatible children and move table; compare totals and issue IDs. Incompatible scope or race rejects without partial mutation.

**Environment:** real application surfaces: Floor/table/seat/move/split/merge; actual boundaries: Production order/ownership/allocation services.

**Original scenario checks:** [OWN-08](SCENARIOS.md#own-08), [OWN-09](SCENARIOS.md#own-09), [OWN-05](SCENARIOS.md#own-05)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-ORDER-04

### Separate state dimensions and independent duplicate-order correction

**Applicability:** Required baseline

**Required local behavior:**
- Ownership/order/preparation/fulfillment/collection/refund/exception remain separate per quantity. Separate operation IDs are not automatically transport duplicates; link and explicitly reconcile actual preparation/payment.

**Steps / rehearsal needs from the independent requirements:**
1. Create duplicate orders with one or both prepared/paid; link/cancel selected work while preserving consumed stock and real refund obligations.

**Environment:** real application surfaces: Order status and duplicate/wrong-order resolution; actual boundaries: Production order/incident/kitchen/payment links.

**Original scenario checks:** [OWN-11](SCENARIOS.md#own-11), [KIT-13](SCENARIOS.md#kit-13), [PAY-12](SCENARIOS.md#pay-12)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-KITCHEN-01

### Incremental send and atomic ingredient issue

**Applicability:** Required baseline

**Required local behavior:**
- Check all selected components, pin recipe/yield/UOM/substitution/lot allocation, and persist portion tasks/issue/jobs atomically. Additions get new identities; retry or checkout does not reissue.

**Steps / rehearsal needs from the independent requirements:**
1. Two burgers from10bread/1500g meat yield8/1200g and two tasks. Lost response/restart returns same IDs; insufficient component rejects full selected batch.

**Environment:** real application surfaces: Incremental send and kitchen tickets; actual boundaries: Production recipe/stock/task/outbox transaction.

**Original scenario checks:** [KIT-01](SCENARIOS.md#kit-01), [KIT-02](SCENARIOS.md#kit-02), [KIT-09](SCENARIOS.md#kit-09), [KIT-12](SCENARIOS.md#kit-12), [STK-03](SCENARIOS.md#stk-03)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02), [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-KITCHEN-02

### Prepaid unsent reservations

**Applicability:** Required baseline

**Required local behavior:**
- Reserve before prepayment; retain paid-unsent or unknown-payment reservation during delay. Send converts to one issue; cancellation releases reservation and separately resolves actual refund.

**Steps / rehearsal needs from the independent requirements:**
1. Prepay, cross ordinary expiry, restart then send/cancel; no early issue, no resell of reserved stock, one eventual issue or explicit refund-due/release.

**Environment:** real application surfaces: Paid-unsent and preparation/payment recovery; actual boundaries: Production reservation/payment/kitchen services.

**Original scenario checks:** [KIT-15](SCENARIOS.md#kit-15), [STK-08](SCENARIOS.md#stk-08)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-KITCHEN-03

### Station tasks, courses, start and quantity readiness

**Applicability:** Required baseline

**Required local behavior:**
- Narrow station projections with notes/modifiers, optional course/hold/fire. Per-portion start/ready/served; any required station start marks portion started, and all required tasks must be ready.

**Steps / rehearsal needs from the independent requirements:**
1. Two stations, one started one stale queued; cannot restore whole recipe or mark fully ready early. KDS cannot fetch unrelated prices/contact/finance.

**Environment:** real application surfaces: KDS queues/tickets/course/fire/ready/served; actual boundaries: Production station/portion/version/projection services.

**Original scenario checks:** [KIT-06](SCENARIOS.md#kit-06), [KIT-14](SCENARIOS.md#kit-14), [OWN-07](SCENARIOS.md#own-07)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-KITCHEN-04

### Verified cancellation versus consumed waste

**Applicability:** Required baseline

**Required local behavior:**
- Reverse only verified unstarted original allocations; unsafe returned ingredients quarantine. Started portion retains consumption with one waste/cost reclassification. Paper/start/print uncertainty awaits confirmation.

**Steps / rehearsal needs from the independent requirements:**
1. Cancel one of two before start ->9bread/1350g; cancel both after start ->8/1200g. Mix states and race start/cancel after recipe change; stale start denies and original allocation used.

**Environment:** real application surfaces: Cancel request/confirm and waste/paper resolution; actual boundaries: Production kitchen/stock/cost transitions.

**Original scenario checks:** [KIT-03](SCENARIOS.md#kit-03), [KIT-04](SCENARIOS.md#kit-04), [KIT-05](SCENARIOS.md#kit-05), [KIT-06](SCENARIOS.md#kit-06), [KIT-07](SCENARIOS.md#kit-07), [KIT-08](SCENARIOS.md#kit-08), [KIT-09](SCENARIOS.md#kit-09)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-KITCHEN-05

### Remakes, merchant error and customer changes

**Applicability:** Required baseline

**Required local behavior:**
- Merchant remake checks fresh ingredients and records old waste, one customer charge/sold quantity. Substitute/customer change uses linked price/waiver/new work and explicit paid difference; shortage cannot bypass availability.

**Steps / rehearsal needs from the independent requirements:**
1. Remake/retry then unavailable substitute; inspect new issue, original cost, no duplicate revenue and explicit adjustment/refund.

**Environment:** real application surfaces: Quality/remake/substitution/charge waiver; actual boundaries: Production kitchen/price/payment/incident services.

**Original scenario checks:** [KIT-10](SCENARIOS.md#kit-10), [KIT-11](SCENARIOS.md#kit-11), [KIT-13](SCENARIOS.md#kit-13), [RET-08](SCENARIOS.md#ret-08), [RET-12](SCENARIOS.md#ret-12)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-FULFIL-01

### Dine-in, takeaway and delivery

**Applicability:** Required baseline

**Required local behavior:**
- Independent served/picked-up/out-for-delivery/delivered/failed quantities; table/seat/pickup references and required delivery recipient/contact/address. Default retail/takeaway collect before handover; dine-in service before collection allowed.

**Steps / rehearsal needs from the independent requirements:**
1. Actual dispatch/pickup/courier screens with partial fulfillment; delivery does not imply payment/remittance and courier sees only assignment.

**Environment:** real application surfaces: Fulfillment/pickup/dispatch/courier; actual boundaries: Production fulfillment and scoped delivery services.

**Original scenario checks:** [PAY-08](SCENARIOS.md#pay-08), [PAY-09](SCENARIOS.md#pay-09), [PAY-12](SCENARIOS.md#pay-12), [OWN-07](SCENARIOS.md#own-07)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-FULFIL-02

### COD collection, change and remittance custody

**Applicability:** Required baseline

**Required local behavior:**
- Identified courier collects/change/remits separately; remittance not second sale. Courier cannot use cashier drawer. If courier collection disabled, require prepaid or actual cashier collection.

**Steps / rehearsal needs from the independent requirements:**
1. Deliver and collect, leave unremitted then remit/retry; reconcile customer payment/collector/drawer-safe custody with one sale.

**Environment:** real application surfaces: COD/change/remittance/uncollected funds; actual boundaries: Production tender/collector/cash transfer services.

**Original scenario checks:** [PAY-08](SCENARIOS.md#pay-08), [PAY-03](SCENARIOS.md#pay-03), [CASH-11](SCENARIOS.md#cash-11)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-FULFIL-03

### Failed delivery and abandoned pickup

**Applicability:** Required baseline

**Required local behavior:**
- Record failure/no-show/contact-expiry/fee-refund decision and actual disposition. Packaged goods inspect; prepared food never auto-restocks. Prepaid amount remains charge/refund decision, not customer credit.

**Steps / rehearsal needs from the independent requirements:**
1. Prepaid food uncollected and COD refused: not falsely delivered/paid, original consumption retained, explicit inspection/custody/refund.

**Environment:** real application surfaces: Failure/no-show/disposition resolution; actual boundaries: Production fulfillment/inspection/waste/refund.

**Original scenario checks:** [PAY-09](SCENARIOS.md#pay-09), [PAY-12](SCENARIOS.md#pay-12), [KIT-04](SCENARIOS.md#kit-04)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-FULFIL-04

### Unpaid incidents, later collection and write-off

**Applicability:** Required baseline

**Required local behavior:**
- 300 supplied/100 collected retains200 incident, no employee shortage/credit. Later collection belongs to current session without resale/reissue. Approved write-off has reason/accounting/fiscal treatment without cash. Exceptional retail release checks stock and records unpaid physical issue once.

**Steps / rehearsal needs from the independent requirements:**
1. Collect100, close, later collect200/retry; total300 collection with one supply. Authorized write-off and denied shortage release retain honest ledgers.

**Environment:** real application surfaces: Unpaid incident/collection/write-off/exceptional release; actual boundaries: Production incident/stock/cash/fiscal intents.

**Original scenario checks:** [PAY-05](SCENARIOS.md#pay-05), [PAY-06](SCENARIOS.md#pay-06), [PAY-07](SCENARIOS.md#pay-07), [PAY-14](SCENARIOS.md#pay-14)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** HELD — Actual central/external fiscal submission arising from supplied-unpaid/write-off; local mappings remain required.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PAY-01

### Locked payable, split tender and cashier attestation

**Applicability:** Required baseline

**Required local behavior:**
- Check/reserve before collection, lock payable; record collector/session/method/amount/terminal/ref/outcome, cash/change, split/partial tender. External success is cashier attestation, no bank/WAN/standby dependency. Insufficient/not-paid cannot finalize; no card change.

**Steps / rehearsal needs from the independent requirements:**
1. Offline mixed tender/change and partial/not-paid flows; inspect remaining payable, one net cash effect, stock reservation and immutable locked total.

**Environment:** real application surfaces: Tender/confirmation/change/remaining amount; actual boundaries: Production reservation/lock/payment/finalization.

**Original scenario checks:** [PAY-01](SCENARIOS.md#pay-01), [PAY-02](SCENARIOS.md#pay-02), [PAY-03](SCENARIOS.md#pay-03), [PAY-04](SCENARIOS.md#pay-04), [STK-01](SCENARIOS.md#stk-01), [STK-08](SCENARIOS.md#stk-08)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02), [Q-UX-05](QUALITY-AND-PLATFORMS.md#q-ux-05)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PAY-02

### Unknown collection/refund and protected evidence

**Applicability:** Required baseline

**Required local behavior:**
- Keep original actor/operation/attestation durably through Back/logout/restart. Unknown cannot trigger another charge, release reservation or reassign payment; authorized recovery returns original result or controlled unresolved obligation.

**Steps / rehearsal needs from the independent requirements:**
1. Physical external event followed by storage/response failure on both sides of commit; original-ID recovery only, supervisor never relabels collector and next cashier cannot view prior private evidence.

**Environment:** real application surfaces: Outcome-needs-checking and reconciliation; actual boundaries: Production client journal/result/refund/permission services.

**Original scenario checks:** [PAY-04](SCENARIOS.md#pay-04), [CASH-08](SCENARIOS.md#cash-08), [RET-04](SCENARIOS.md#ret-04), [UX-06](SCENARIOS.md#ux-06), [REL-10](SCENARIOS.md#rel-10)

**Original gate checks:** [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-NAV-07](QUALITY-AND-PLATFORMS.md#q-nav-07)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PAY-03

### Post-close classification correction and dispute

**Applicability:** Required baseline

**Required local behavior:**
- Wrong tender and dispute/reversal append evidence; original attestation/close immutable. Only actual subsequent movement affects cash/tender; no silent stock restoration.

**Steps / rehearsal needs from the independent requirements:**
1. Correct tender after close without new money, then record a distinct actual refund; immutable close plus linked adjustment, no invented receipt.

**Environment:** real application surfaces: Correction/dispute review; actual boundaries: Production adjustment/dispute/cash/report.

**Original scenario checks:** [PAY-10](SCENARIOS.md#pay-10), [PAY-13](SCENARIOS.md#pay-13), [CASH-10](SCENARIOS.md#cash-10)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PAY-04

### Excluded customer balances/credit/loyalty stay disabled

**Applicability:** Required baseline

**Required local behavior:**
- No customer credit/store balance/loyalty or implied gift/stored-value product. Incidents, supplier payables, exchange offset and deposit entitlement are not spendable generic tenders.

**Steps / rehearsal needs from the independent requirements:**
1. Craft disabled tender/product/UI/direct requests: no paid sale, wallet or stock effect without supported coverage.

**Environment:** real application surfaces: Tender and capability controls; actual boundaries: Production capability/tender guards.

**Original scenario checks:** [PAY-11](SCENARIOS.md#pay-11), [RET-05](SCENARIOS.md#ret-05), [RET-06](SCENARIOS.md#ret-06), [CAT-10](SCENARIOS.md#cat-10)

**Original gate checks:** [Q-SEC-07](QUALITY-AND-PLATFORMS.md#q-sec-07)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RETURN-01

### Offline origin-branch receipt and entitlement

**Applicability:** Required baseline

**Required local behavior:**
- Local immutable receipt and cumulative entitlement; only selling branch redeems. Accept/reserve entitlement even while refund pending. Narrow authorized lookup, missing recovered evidence is not invented entitlement.

**Steps / rehearsal needs from the independent requirements:**
1. Offline repeat/parallel partial returns exceed remainder: at most valid entitlement once; wrong branch directs to seller without mutation.

**Environment:** real application surfaces: Receipt lookup/return quantity/refund due; actual boundaries: Production receipt/return/entitlement.

**Original scenario checks:** [RET-01](SCENARIOS.md#ret-01), [RET-02](SCENARIOS.md#ret-02), [RET-03](SCENARIOS.md#ret-03), [RET-04](SCENARIOS.md#ret-04), [OWN-10](SCENARIOS.md#own-10)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RETURN-02

### Customer remedies, evidence and deadline rights

**Applicability:** Required baseline

**Required local behavior:**
- Initial Egypt applicable14-day change-of-mind/30-day defect and covered one-week resolution measured from receipt/delivery. Preserve promised/more favorable/continuing rights; operator limit cannot erase claim. Receiptless exception disabled absent policy and validated evidence/valuation.

**Steps / rehearsal needs from the independent requirements:**
1. Test delivery/claim boundaries, policy shortening, prepared-food defect versus change-of-mind, missing receipt. Keep claim/deadline independent of inspection/approval/physical refund.

**Environment:** real application surfaces: Claim/evidence/deadline/receipt recovery; actual boundaries: Production policy/claim/evidence/entitlement.

**Original scenario checks:** [RET-10](SCENARIOS.md#ret-10), [RET-11](SCENARIOS.md#ret-11), [RET-12](SCENARIOS.md#ret-12), [RET-13](SCENARIOS.md#ret-13), [RET-09](SCENARIOS.md#ret-09)

**Original gate checks:** [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08), [Q-RES-12](QUALITY-AND-PLATFORMS.md#q-res-12)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RETURN-03

### Inspected stock and original snapshot/tender refund

**Applicability:** Required baseline

**Required local behavior:**
- Money and disposition separate; restock only inspected eligible goods, quarantine/waste never ingredient restoration. Original tax/discount/charge/remaining tender allocation; permitted alternate tender needs reason. Actual payout affects today drawer; pending refund not balance.

**Steps / rehearsal needs from the independent requirements:**
1. Change price/tax/recipe then return; original permitted amount/cost. Insufficient cash or unknown external refund leaves due/unknown with entitlement unavailable.

**Environment:** real application surfaces: Inspection/original tender/refund resolution; actual boundaries: Production stock/cost/refund/cash.

**Original scenario checks:** [RET-08](SCENARIOS.md#ret-08), [RET-09](SCENARIOS.md#ret-09), [RET-04](SCENARIOS.md#ret-04), [STK-09](SCENARIOS.md#stk-09), [CASH-07](SCENARIOS.md#cash-07), [CASH-12](SCENARIOS.md#cash-12)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RETURN-04

### Linked exchange with restricted offset

**Applicability:** Required baseline

**Required local behavior:**
- Return/new sale separate, exclusive noncash offset and replacement reservation. Same-price still documents/stock. Accepted return survives unavailable replacement or failed/unknown difference; no reusable credit.

**Steps / rehearsal needs from the independent requirements:**
1. Return100/new130 ->collect30; new80 ->offset80/refund20. Race shortage/restart pending difference without losing accepted goods or refund obligation.

**Environment:** real application surfaces: Exchange/reservation/difference/recovery; actual boundaries: Production return/exchange/stock/payment.

**Original scenario checks:** [RET-05](SCENARIOS.md#ret-05), [RET-06](SCENARIOS.md#ret-06), [RET-07](SCENARIOS.md#ret-07), [PRC-03](SCENARIOS.md#prc-03)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-STOCK-01

### Warehouses, sellable stock and reservations

**Applicability:** Required baseline

**Required local behavior:**
- Track location/available/reserved/held/expired/in-transit/issued/waste; validate full quantity/components without negative-stock bypass. Reserve before collection; unknown/part-completed collection cannot expire casually.

**Steps / rehearsal needs from the independent requirements:**
1. Two clients compete last unit among mixed lots: one permitted winner, current shortage loser, exact conservation and no partial hidden issue/payment prompt.

**Environment:** real application surfaces: Stock/location/availability and checkout shortage; actual boundaries: Production stock/reservation/custody.

**Original scenario checks:** [STK-01](SCENARIOS.md#stk-01), [STK-02](SCENARIOS.md#stk-02), [STK-03](SCENARIOS.md#stk-03), [STK-04](SCENARIOS.md#stk-04), [STK-08](SCENARIOS.md#stk-08), [CAT-05](SCENARIOS.md#cat-05)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-STOCK-02

### Counts, movement cuts and reasoned adjustments

**Applicability:** Required baseline

**Required local behavior:**
- Count uses movement cut and verified subsequent deltas or recount. Permission/reason adjustment appends, never overwrites; reservations and losses remain explicit.

**Steps / rehearsal needs from the independent requirements:**
1. Count while selling/receiving then apply/retry/stale cut: exact delta or recount, no balance overwrite; unauthorized correction no effect.

**Environment:** real application surfaces: Count/recount/variance/adjustment; actual boundaries: Production movement/count/cost.

**Original scenario checks:** [STK-07](SCENARIOS.md#stk-07), [UX-02](SCENARIOS.md#ux-02)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-STOCK-03

### Dispatch/in-transit/receipt transfers

**Applicability:** Required baseline

**Required local behavior:**
- Source/destination evidence and origin quantities; destination deduplicates partial receipt/shortage. Preserve custody/value, no assumed cross-branch atomic database transaction or unavailable-stock dispatch.

**Steps / rehearsal needs from the independent requirements:**
1. Two provisioned real local masters, partial receipt/lost ACK/restart: one source decrease and accepted destination increase, discrepancy stays visible.

**Environment:** real application surfaces: Transfer/dispatch/in-transit/receive; actual boundaries: Production transfer/custody/value ledgers.

**Original scenario checks:** [STK-06](SCENARIOS.md#stk-06), [STK-07](SCENARIOS.md#stk-07)

**Original gate checks:** [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** HELD — Actual central transfer-intent/evidence routing if selected; local transitions remain required.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-STOCK-04

### Moving average, original cost and last-unit remainder

**Applicability:** Required baseline

**Required local behavior:**
- Versioned weighted-average per SKU/warehouse independent of FEFO; immutable issue cost. Original-value reversals/inspected return, exact final residual. No rewritten historical margin or unexplained zero-quantity negative value.

**Steps / rehearsal needs from the independent requirements:**
1. 10@20+10@30 ->20/value500/average25. Sell10 then return remaining carrying250 versus supplier claim300 ->variance50. Test changed-pool customer return and last unit remainder.

**Environment:** real application surfaces: Valuation and cost history; actual boundaries: Production quantity/value/issue-cost.

**Original scenario checks:** [PUR-13](SCENARIOS.md#pur-13), [PUR-15](SCENARIOS.md#pur-15), [KIT-09](SCENARIOS.md#kit-09), [RET-09](SCENARIOS.md#ret-09)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PUR-01

### Suppliers, item references, terms and reorder planning

**Applicability:** Required baseline

**Required local behavior:**
- Supplier/item refs, EGP terms, optional min/max/lead-time proposals; separate available/reserved/held/expected. Proposals create no stock/debt/payment; actual supplier evidence scoped.

**Steps / rehearsal needs from the independent requirements:**
1. Create delegated supplier and shortage proposal; inspect no stock/spend before authorized purchase/receipt.

**Environment:** real application surfaces: Suppliers/items/terms/reorder; actual boundaries: Production supplier/procurement persistence.

**Original scenario checks:** [PUR-01](SCENARIOS.md#pur-01), [PUR-02](SCENARIOS.md#pur-02), [PUR-10](SCENARIOS.md#pur-10)

**Original gate checks:** [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10)

**Central portion:** HELD — Company-wide supplier/procurement publication.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PUR-02

### Requisitions, PO approval/revision and branch allowances

**Applicability:** Required baseline

**Required local behavior:**
- Draft/pending/approved/ordered with independent delivery/billing/settlement. Pin product/unit/price/tax/tolerance/destination; amend/hold/cancel/short-close only allowed unfulfilled quantities. Own fixed branch allowance, no unallocated offline shared budget.

**Steps / rehearsal needs from the independent requirements:**
1. Create/approve delegated PO, revise/short-close; received/billed history cannot delete. Concurrent allowance/wrong branch denied; pending central approval does not stop sales.

**Environment:** real application surfaces: Requisition/PO/approval/revision/short-close; actual boundaries: Production purchasing/approval/allocation.

**Original scenario checks:** [PUR-04](SCENARIOS.md#pur-04), [PUR-05](SCENARIOS.md#pur-05), [PUR-10](SCENARIOS.md#pur-10)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-SEC-05](QUALITY-AND-PLATFORMS.md#q-sec-05)

**Central portion:** HELD — Actual central shared allocation/reallocation and acknowledged release.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PUR-03

### Quick receipt/invoice/payment remain distinct

**Applicability:** Required baseline

**Required local behavior:**
- One screen links separate physical receipt, invoice and actual settlement. Receipt without bill/bill before arrival supported; invoice alone never stock, payment not second expense.

**Steps / rehearsal needs from the independent requirements:**
1. Quick receive/invoice/pay then lost reply retry: stock/cash/debt each once. Bill-first unmatched has zero stock until receipt.

**Environment:** real application surfaces: Quick receiving and linked finance; actual boundaries: Production receipt/invoice/settlement/cash.

**Original scenario checks:** [PUR-01](SCENARIOS.md#pur-01), [PUR-02](SCENARIOS.md#pur-02), [PUR-08](SCENARIOS.md#pur-08)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PUR-04

### Partial/excess receiving and quality custody

**Applicability:** Required baseline

**Required local behavior:**
- Held=accepted+quarantine+rejected held; refused at door not stock. Missing stays outstanding. Zero default overreceipt tolerance; excess/wrong SKU held until approved resolution. Lot/serial/expiry/inspection gates release, pending allocation cannot double-receive.

**Steps / rehearsal needs from the independent requirements:**
1. 24arrive/22accepted/2damaged ->24custody/22sellable/2rejected and2 fulfillment decision. Race allowance and repeat release; no double replacement allowance or PO expansion.

**Environment:** real application surfaces: Receive/inspect/hold/reject/substitute; actual boundaries: Production custody/quality/PO/stock.

**Original scenario checks:** [PUR-03](SCENARIOS.md#pur-03), [PUR-04](SCENARIOS.md#pur-04), [PUR-05](SCENARIOS.md#pur-05), [PUR-06](SCENARIOS.md#pur-06), [STK-06](SCENARIOS.md#stk-06), [CAT-02](SCENARIOS.md#cat-02), [CAT-04](SCENARIOS.md#cat-04)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PUR-05

### Invoice matching, disputes and settlement authority

**Applicability:** Required baseline

**Required local behavior:**
- Match agreement/remaining accepted billable quantity/claim; service acceptance uses evidence not warehouse units. Save unmatched/mismatch, approval guards disputed amount; uncontested settlement only by configured permission. One posting branch; local normalized duplicate guard.

**Steps / rehearsal needs from the independent requirements:**
1. Price/quantity/tax mismatch and double allocation retain original claim, deny unapproved payout. Offline separate branches never falsely promise global uniqueness.

**Environment:** real application surfaces: Invoice/match/dispute/approval; actual boundaries: Production invoice/allocation/uniqueness.

**Original scenario checks:** [PUR-02](SCENARIOS.md#pur-02), [PUR-03](SCENARIOS.md#pur-03), [PUR-07](SCENARIOS.md#pur-07), [PUR-09](SCENARIOS.md#pur-09)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10)

**Central portion:** HELD — Cross-branch conflict and confirmed receipt matching with one central designated settlement authority.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PUR-06

### Supplier advance, payment, overpayment and credit

**Applicability:** Required baseline

**Required local behavior:**
- Separate due/paid/unapplied advance/overpayment/unknown/credit. Explicit allocation once; actual cash from permitted available custody, external bank outcome attested rather than initiated.

**Steps / rehearsal needs from the independent requirements:**
1. 1000invoice-200advance-500cash ->300due; credit100 ->200due, today cash-500 only. Retry, unknown or insufficient cash never invent payment/purchase.

**Environment:** real application surfaces: Supplier balance/advance/payment/allocation; actual boundaries: Production payable/cash/evidence.

**Original scenario checks:** [PUR-08](SCENARIOS.md#pur-08), [PUR-12](SCENARIOS.md#pur-12), [CASH-12](SCENARIOS.md#cash-12)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PUR-07

### Supplier return, claim and replacement

**Applicability:** Required baseline

**Required local behavior:**
- Only available original receipt/lot/serial quantities; distinguish rejected supplier-owned custody from purchased value. Dispatch/claim/credit/refund/replacement separate. Replacement one inspected receipt with free/charged terms. Carrying value distinct from credit claim.

**Steps / rehearsal needs from the independent requirements:**
1. Sold/reserved return denied; supplier-owned rejected goods leave without purchased loss. Credit alone no quantity; replacement adds once with explicit payable/variance.

**Environment:** real application surfaces: Supplier return/claim/credit/replacement; actual boundaries: Production return/custody/valuation/obligation.

**Original scenario checks:** [PUR-11](SCENARIOS.md#pur-11), [PUR-12](SCENARIOS.md#pur-12), [PUR-13](SCENARIOS.md#pur-13)

**Original gate checks:** [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PUR-08

### Landed cost and late/posted corrections

**Applicability:** Required baseline

**Required local behavior:**
- Eligible freight allocation by goods net value by default or approved quantity/weight, deterministic remainder. Source payable once. Late cost separates held adjustment/consumed variance with quantity/history unchanged; used receipts correct through linked guarded adjustments.

**Steps / rehearsal needs from the independent requirements:**
1. A100/B100/service100/freight30 ->goods15each, service0allocation. Consume part then correct; conserve value, preserve original sale, reject impossible whole reversal/negative residual.

**Environment:** real application surfaces: Landed cost and value/receipt correction; actual boundaries: Production cost/variance/correction ledgers.

**Original scenario checks:** [PUR-14](SCENARIOS.md#pur-14), [PUR-15](SCENARIOS.md#pur-15), [PUR-16](SCENARIOS.md#pur-16)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PUR-09

### Purchasing evidence and complete recovery

**Applicability:** Required baseline

**Required local behavior:**
- Retain supplier originals/unit/lot/received/accepted/actors and required evidence locally. Bounded attachments; optional upload cannot gate receipt. Backup includes inspection, allocations, disputed invoices, unknown payments and claims.

**Steps / rehearsal needs from the independent requirements:**
1. Offline evidence capture and interruption/restore: no physical settlement replay, no autoapproval or lost quality custody.

**Environment:** real application surfaces: Attachments/audit/open disputes; actual boundaries: Production protected content/outboxes/recovery.

**Original scenario checks:** [PUR-16](SCENARIOS.md#pur-16), [PUR-07](SCENARIOS.md#pur-07)

**Original gate checks:** [Q-RES-09](QUALITY-AND-PLATFORMS.md#q-res-09), [Q-DB-09](QUALITY-AND-PLATFORMS.md#q-db-09)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-REPORT-01

### Sales, collections, cash and tax reconcile separately

**Applicability:** Required baseline

**Required local behavior:**
- Separate gross/discount/net/tax/charges/tips/sales, actual collections/refunds by tender-terminal, opening/cash-in/out/safe/count/variance/incidents. Sales cannot equal cash count by assumption.

**Steps / rehearsal needs from the independent requirements:**
1. Independently sum production source ledgers with prior-day refund/later incident payment/safe drop; trace drilldowns and no fictitious balancing.

**Environment:** real application surfaces: Own/shift/day/sales/tender/terminal/tax reports; actual boundaries: Production local scoped report/ledger reads.

**Original scenario checks:** [TAX-09](SCENARIOS.md#tax-09), [CASH-03](SCENARIOS.md#cash-03), [CASH-07](SCENARIOS.md#cash-07), [CASH-10](SCENARIOS.md#cash-10), [PAY-06](SCENARIOS.md#pay-06), [PAY-10](SCENARIOS.md#pay-10)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10)

**Central portion:** HELD — Actual consolidated company reports.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-REPORT-02

### Stock, kitchen, returns and supplier reports

**Applicability:** Required baseline

**Required local behavior:**
- Report sellable/reserved/held/value/expiry, timing/waste/remake, return/refund/incident, PO/quality/unbilled/unmatched/due/advance/claim/price and landed-cost variance; originals distinct from linked adjustments.

**Steps / rehearsal needs from the independent requirements:**
1. Full real business fixtures reconcile each category to immutable source IDs; held/disputed values not counted as sellable/approved.

**Environment:** real application surfaces: Operational/kitchen/return/stock/supplier reports; actual boundaries: Production report projections and ledger sources.

**Original scenario checks:** [TAX-09](SCENARIOS.md#tax-09), [PUR-03](SCENARIOS.md#pur-03), [PUR-07](SCENARIOS.md#pur-07), [PUR-15](SCENARIOS.md#pur-15), [RET-04](SCENARIOS.md#ret-04), [KIT-10](SCENARIOS.md#kit-10)

**Original gate checks:** [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08), [Q-UX-09](QUALITY-AND-PLATFORMS.md#q-ux-09), [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10)

**Central portion:** HELD — Actual cross-branch aggregation/freshness.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-REPORT-03

### Sensitive report fields, scoped drilldown and export

**Applicability:** Required baseline

**Required local behavior:**
- Separate own/branch/selected/company scope and revenue/cost/margin/supplier/employee variance/contact/export grants. Redact before serialization, recheck every endpoint/subscription/cache, bounded pages/exports; activated revoke clears sensitive views.

**Steps / rehearsal needs from the independent requirements:**
1. Quantity-only user probes fields/direct IDs/alternate export, then revoke while active. No forbidden fetched data; already delivered exports not falsely claimed remotely erased.

**Environment:** real application surfaces: Report filters/drilldown/export/status; actual boundaries: Production local query/field/export enforcement.

**Original scenario checks:** [CFG-03](SCENARIOS.md#cfg-03), [CFG-05](SCENARIOS.md#cfg-05), [CFG-06](SCENARIOS.md#cfg-06), [OWN-10](SCENARIOS.md#own-10)

**Original gate checks:** [Q-SEC-06](QUALITY-AND-PLATFORMS.md#q-sec-06), [Q-SEC-08](QUALITY-AND-PLATFORMS.md#q-sec-08), [Q-DB-07](QUALITY-AND-PLATFORMS.md#q-db-07)

**Central portion:** HELD — Central grant management and central report/export filtering.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-REPORT-04

### Operational alerts and honest acknowledgement

**Applicability:** Required baseline

**Required local behavior:**
- Recipient/severity/threshold/quiet hours/dedup/ack/resolution for stock/expiry/prep/payment/refund/collector/variance/purchase/master/storage. Local alerts offline, sensitive previews off by default. Ack changes no underlying money/stock/obligation.

**Steps / rehearsal needs from the independent requirements:**
1. Retry alert, remove recipient then open old notification and acknowledge: current access, one identity, unchanged business obligation.

**Environment:** real application surfaces: Rules/inbox/ack/notifications; actual boundaries: Production local alert/outbox/recipient and native adapter.

**Original scenario checks:** [CFG-07](SCENARIOS.md#cfg-07), [UX-08](SCENARIOS.md#ux-08)

**Original gate checks:** [Q-SEC-08](QUALITY-AND-PLATFORMS.md#q-sec-08), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02)

**Central portion:** HELD — Actual remote company notification delivery.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-REPORT-05

### Truthful local/replication/report freshness

**Applicability:** Required baseline

**Required local behavior:**
- Distinct local commit/standby copy/central storage/report projection/full recovery cursors. Show active policy/pending intent, unknown operation, disk/latency/queue/checkpoint gap; no misleading green cloud icon.

**Steps / rehearsal needs from the independent requirements:**
1. Disconnect and create real work, restore stale checkpoint: local complete/remote pending, actual lag and missing tail, never invented company currentness/zero loss.

**Environment:** real application surfaces: Master/backup/sync health and report freshness; actual boundaries: Production local health/metrics/cursors.

**Original scenario checks:** [REL-02](SCENARIOS.md#rel-02), [REL-09](SCENARIOS.md#rel-09), [UX-08](SCENARIOS.md#ux-08), [CFG-05](SCENARIOS.md#cfg-05)

**Original gate checks:** [Q-RES-14](QUALITY-AND-PLATFORMS.md#q-res-14), [Q-BE-09](QUALITY-AND-PLATFORMS.md#q-be-09)

**Central portion:** HELD — Live central report/ingestion/recovery statuses.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-01

### Five native master and client platforms

**Applicability:** Required baseline

**Required local behavior:**
- Windows, macOS, Android, physical iPhone and physical iPad each required as master/client. All25 ordered pairs plus separate protected one-device path. Actual OS/CPU/package/service/native providers qualified; missing hardware remains unverified, not dropped.

**Steps / rehearsal needs from the independent requirements:**
1. Installed login->stock->sale->same-branch return->close on every ordered pair with N1/N2; real mobile lifecycle and desktop headless service, not simulator/JVM substitute.

**Environment:** real application surfaces: Native launch/setup and master/client selection; actual boundaries: Actual platform host/storage/security/transport adapters.

**Original scenario checks:** [REL-01](SCENARIOS.md#rel-01), [REL-07](SCENARIOS.md#rel-07), [UX-04](SCENARIOS.md#ux-04), [UX-05](SCENARIOS.md#ux-05)

**Original gate checks:** [Q-SEC-02](QUALITY-AND-PLATFORMS.md#q-sec-02), [Q-NAV-10](QUALITY-AND-PLATFORMS.md#q-nav-10), [Q-NAV-11](QUALITY-AND-PLATFORMS.md#q-nav-11)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-02

### Real encrypted offline LAN communication

**Applicability:** Required baseline

**Required local behavior:**
- Required production P2pKit typed RPC, protected local gateway, durable pinned identity, no public relay/signaling. Numeric endpoint fallback when discovery blocked still requires trust. Bound frames/concurrency/timeouts/reconnect; notification is hint, not durable truth.

**Steps / rehearsal needs from the independent requirements:**
1. WAN blocked from startup, denied LAN permission, guest peer isolation, multicast off, interface/IPv4/IPv6/address changes and reconnect storm. Unknown/retired peer and malformed payload fail closed, one isolated client cannot promote.

**Environment:** real application surfaces: Pair/discovery/manual endpoint/connection state; actual boundaries: Production P2pKit host/client and secure local adapter.

**Original scenario checks:** [REL-01](SCENARIOS.md#rel-01), [REL-03](SCENARIOS.md#rel-03), [REL-04](SCENARIOS.md#rel-04)

**Original gate checks:** [Q-SEC-01](QUALITY-AND-PLATFORMS.md#q-sec-01), [Q-SEC-02](QUALITY-AND-PLATFORMS.md#q-sec-02), [Q-SEC-07](QUALITY-AND-PLATFORMS.md#q-sec-07), [Q-RES-04](QUALITY-AND-PLATFORMS.md#q-res-04)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-03

### Desktop supervision and mobile hosting lifecycle

**Applicability:** Required baseline

**Required local behavior:**
- Cashier logout/navigation/desktop UI close does not stop master. Desktop bounded supervised restart. Mobile notice, role-scoped keep-awake and Android foreground service; manual lock/background/force-stop honest availability. Reopen automatically resumes only still-active role/epoch after storage checks; explicit stop/retired/sealed markers persist.

**Steps / rehearsal needs from the independent requirements:**
1. Physical mobile idle/manual lock/background/kill/reopen, service permission/OEM denial and desktop UI/service separation. No need cashier/cloud login for backend, no supervisor undo of intentional stop.

**Environment:** real application surfaces: Host notice/health/stop/resume/transfer; actual boundaries: Platform service/process lifecycle and durable role store.

**Original scenario checks:** [REL-07](SCENARIOS.md#rel-07), [UX-04](SCENARIOS.md#ux-04), [UX-07](SCENARIOS.md#ux-07), [CASH-06](SCENARIOS.md#cash-06)

**Original gate checks:** [Q-DI-02](QUALITY-AND-PLATFORMS.md#q-di-02), [Q-RES-04](QUALITY-AND-PLATFORMS.md#q-res-04), [Q-RES-13](QUALITY-AND-PLATFORMS.md#q-res-13)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-04

### Durable outboxes and independent asynchronous destinations

**Applicability:** Required baseline

**Required local behavior:**
- Persist result/journal/print/sync/recovery jobs with command; confirm after local durable commit only. Separate destination cursors/contiguous ACKs, bounded retry/backpressure and independent progress. No short retry/age TTL discards financial work; actual storage exhaustion remains failure.

**Steps / rehearsal needs from the independent requirements:**
1. Lose response/ACK and restart, delay one destination while another/foreground operates; identical source IDs and no repeated physical payment/print/preparation. At low storage retain backlog and report capacity.

**Environment:** real application surfaces: Local saved/pending print/sync/checkpoint status; actual boundaries: Production transaction/outboxes/standby replication.

**Original scenario checks:** [REL-02](SCENARIOS.md#rel-02), [REL-04](SCENARIOS.md#rel-04), [REL-10](SCENARIOS.md#rel-10), [UX-08](SCENARIOS.md#ux-08)

**Original gate checks:** [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02), [Q-RES-11](QUALITY-AND-PLATFORMS.md#q-res-11), [Q-PERF-04](QUALITY-AND-PLATFORMS.md#q-perf-04)

**Central portion:** HELD — Actual central authenticated ingestion/ACK/report pipeline and remote fiscal jobs.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-05

### Validated atomic policy/catalog activation

**Applicability:** Required baseline

**Required local behavior:**
- Persist unique inbox, verify origin/scope/version/dependencies/schema, stage complete package and activate atomically. Incomplete/invalid/reordered package leaves previous valid state. Activate current permissions and eligible price policy without cashier approval or overwriting local emergency disable/posted facts.

**Steps / rehearsal needs from the independent requirements:**
1. Qualification inputs exercise production validation/activation with truncated/tampered/wrong-branch/duplicate/stale packages and concurrent payment/kitchen/approval. Old config or full new config only, never partial.

**Environment:** real application surfaces: Pending/applied/rejected versions and local configuration; actual boundaries: Production inbox/package verification/activation/session invalidation.

**Original scenario checks:** [TAX-01](SCENARIOS.md#tax-01), [TAX-02](SCENARIOS.md#tax-02), [TAX-03](SCENARIOS.md#tax-03), [CFG-05](SCENARIOS.md#cfg-05), [KIT-09](SCENARIOS.md#kit-09), [REL-08](SCENARIOS.md#rel-08)

**Original gate checks:** [Q-SEC-06](QUALITY-AND-PLATFORMS.md#q-sec-06), [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-RES-13](QUALITY-AND-PLATFORMS.md#q-res-13)

**Central portion:** HELD — Real central authoring/distribution/application acknowledgement.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-06

### Consistent encrypted backup and optional prepared standby

**Applicability:** Required baseline

**Required local behavior:**
- Backup covers coherent WAL/journal cut and all policies/accounts/recovery/material/order/payment/stock/kitchen/cash/return/purchase/audit/result/outbox/numbering state. Authenticated encryption, bounded resumable chunks and manifest. Optional standby prepared with identity/storage/delegation/initial verified snapshot; same-disk copy not independent.

**Steps / rehearsal needs from the independent requirements:**
1. Take snapshot while real workload runs; restore and verify entire inventory including unknowns/disputes. Interrupt/corrupt/truncate/wrong-key/wrong-branch cannot replace usable state. Standby absent/stale never gates sale.

**Environment:** real application surfaces: Backup/create/verify/restore/standby health; actual boundaries: Production backup/crypto/chunk/standby and Room persistence.

**Original scenario checks:** [REL-02](SCENARIOS.md#rel-02), [REL-09](SCENARIOS.md#rel-09), [REL-10](SCENARIOS.md#rel-10), [UX-04](SCENARIOS.md#ux-04)

**Original gate checks:** [Q-DB-09](QUALITY-AND-PLATFORMS.md#q-db-09), [Q-DB-10](QUALITY-AND-PLATFORMS.md#q-db-10), [Q-RES-08](QUALITY-AND-PLATFORMS.md#q-res-08), [Q-RES-09](QUALITY-AND-PLATFORMS.md#q-res-09)

**Central portion:** HELD — Actual independent central recovery archive and protected key-download path.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-07

### Planned handover with seal-before-activation

**Applicability:** Required baseline

**Required local behavior:**
- One durable target/checkpoint-bound handover: Preparing/Draining/Sealed/Activated/Completed. Target nonwriter until complete cut and old durable seal; abort only before seal. Clients trust approved new identity, reauthenticate and recover old operation IDs without actor/cash reassignment.

**Steps / rehearsal needs from the independent requirements:**
1. Fault/retry each transition and compete two targets/approvals; at most one operational writer. After seal crash cannot reopen old role; transferred pending results remain original across new epoch.

**Environment:** real application surfaces: Prepare/drain/seal/continue/complete/cancel transfer; actual boundaries: Production authority/checkpoint/identity/operation recovery.

**Original scenario checks:** [REL-05](SCENARIOS.md#rel-05), [REL-06](SCENARIOS.md#rel-06), [REL-09](SCENARIOS.md#rel-09)

**Original gate checks:** [Q-RES-05](QUALITY-AND-PLATFORMS.md#q-res-05), [Q-RES-10](QUALITY-AND-PLATFORMS.md#q-res-10), [Q-PERF-08](QUALITY-AND-PLATFORMS.md#q-perf-08)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-08

### Offline emergency recovery, fencing and controlled old-device return

**Applicability:** Required baseline

**Required local behavior:**
- Named local delegate plus verified independent copy and actual old-authority isolation/retirement, not heartbeat/epoch/backup alone. Restore nonauthoritative, disclose missing tail and reconcile original evidence. New identity/sessions; old tokens/keys not cloned. Returning old device read-only until controlled retirement/re-enrollment.

**Steps / rehearsal needs from the independent requirements:**
1. WAN/old master unavailable recovery, stale old master reboot, no-copy and non-fenceable conditions; no automatic promotion, invented outcome, blind outbox replay or zero-loss claim. Reconnect clients and perform sale/return/close afterward.

**Environment:** real application surfaces: Emergency recovery/checkpoint/fencing/missing data/old-device return; actual boundaries: Production recovery/delegation/authority and real operational isolation.

**Original scenario checks:** [REL-06](SCENARIOS.md#rel-06), [REL-09](SCENARIOS.md#rel-09), [REL-07](SCENARIOS.md#rel-07)

**Original gate checks:** [Q-RES-06](QUALITY-AND-PLATFORMS.md#q-res-06), [Q-RES-07](QUALITY-AND-PLATFORMS.md#q-res-07), [Q-RES-08](QUALITY-AND-PLATFORMS.md#q-res-08), [Q-RES-09](QUALITY-AND-PLATFORMS.md#q-res-09), [Q-RES-10](QUALITY-AND-PLATFORMS.md#q-res-10), [Q-SEC-09](QUALITY-AND-PLATFORMS.md#q-sec-09)

**Central portion:** HELD — Actual central recovery source option when approved; local-copy recovery must not require it.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-09

### Non-destructive migrations and controlled upgrades/rollback

**Applicability:** Required baseline

**Required local behavior:**
- Version local schema/protocol/event/snapshot; preserve compatible readers, operation digest, exact historical snapshots and role markers. Fresh/supported upgrades equivalent, high-risk recovery point, interrupted migration rollback/complete. Unsupported downgrade refuses, no delete/reset fallback.

**Steps / rehearsal needs from the independent requirements:**
1. Initial schema plus labelled synthetic migration fixture; every actually shipped prior version thereafter. Upgrade with pending payment/kitchen/refund/cash close/purchase/handover and restore old supported copy; old incompatible client refuses before mutation.

**Environment:** real application surfaces: Update/version/recovery/rollback guidance; actual boundaries: Production Room migrations/version negotiation/installed package.

**Original scenario checks:** [REL-08](SCENARIOS.md#rel-08), [REL-09](SCENARIOS.md#rel-09), [REL-10](SCENARIOS.md#rel-10)

**Original gate checks:** [Q-DB-01](QUALITY-AND-PLATFORMS.md#q-db-01), [Q-DB-02](QUALITY-AND-PLATFORMS.md#q-db-02), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-DB-04](QUALITY-AND-PLATFORMS.md#q-db-04), [Q-DB-08](QUALITY-AND-PLATFORMS.md#q-db-08), [Q-RES-13](QUALITY-AND-PLATFORMS.md#q-res-13)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RES-10

### Storage failure, exclusivity, load and observability

**Applicability:** Required baseline

**Required local behavior:**
- Exclusive branch/runtime instance; actual driver/flush policy. Disk-full/read-only/corruption/fsync/snapshot/migration failures never silent success/discarded backlog. Bound memory/read pagination/export/jobs, measure latency/queue/storage/recovery. P0/P1/P2 are provisional targets, not permission to drop platforms.

**Steps / rehearsal needs from the independent requirements:**
1. Real failure injection and independent fresh reads, separate actual power-loss hardware test. Benchmark P0/P1/P2,24h N2 and72h soak with ledger reconciliation and saved raw percentiles/resources/checkpoints.

**Environment:** real application surfaces: Storage pressure, recovery, diagnostics and health; actual boundaries: Production driver/filesystem/runtime/load/metric paths.

**Original scenario checks:** [REL-10](SCENARIOS.md#rel-10), [REL-04](SCENARIOS.md#rel-04)

**Original gate checks:** [Q-DB-04](QUALITY-AND-PLATFORMS.md#q-db-04), [Q-DB-05](QUALITY-AND-PLATFORMS.md#q-db-05), [Q-DB-06](QUALITY-AND-PLATFORMS.md#q-db-06), [Q-DB-07](QUALITY-AND-PLATFORMS.md#q-db-07), [Q-RES-03](QUALITY-AND-PLATFORMS.md#q-res-03), [Q-RES-11](QUALITY-AND-PLATFORMS.md#q-res-11), [Q-PERF-01](QUALITY-AND-PLATFORMS.md#q-perf-01), [Q-PERF-02](QUALITY-AND-PLATFORMS.md#q-perf-02), [Q-PERF-03](QUALITY-AND-PLATFORMS.md#q-perf-03), [Q-PERF-04](QUALITY-AND-PLATFORMS.md#q-perf-04), [Q-PERF-05](QUALITY-AND-PLATFORMS.md#q-perf-05), [Q-PERF-06](QUALITY-AND-PLATFORMS.md#q-perf-06), [Q-PERF-07](QUALITY-AND-PLATFORMS.md#q-perf-07), [Q-PERF-08](QUALITY-AND-PLATFORMS.md#q-perf-08)

**Central portion:** HELD — Central catch-up/connected-soak throughput portions after approval.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-UI-01

### Typed navigation, guarded Back and private restoration

**Applicability:** Required baseline

**Required local behavior:**
- One Nav3-owned logical model/mutation boundary; typed minimal bounded routes, independent bounded tabs, entry/session ownership and canonical histories. Unified Back/forward/alert/tab leave guards; no protected frame during blocked gesture. Cold restore authenticates and revalidates quarantined identifiers before any protected rendering.

**Steps / rehearsal needs from the independent requirements:**
1. Enumerate route registry and clean/dirty/search/dialog/critical/root policies, stale callbacks/duplicate taps/100000 submissions,1000activity events, runtime language switch and physical iOS gesture matrix. No invented public links solely to test.

**Environment:** real application surfaces: Every route/tab/dialog/Back/notification entry; actual boundaries: Production Nav3 navigator/guards/route serializers.

**Original scenario checks:** [UX-06](SCENARIOS.md#ux-06), [UX-07](SCENARIOS.md#ux-07), [OWN-02](SCENARIOS.md#own-02)

**Original gate checks:** [Q-NAV-01](QUALITY-AND-PLATFORMS.md#q-nav-01), [Q-NAV-02](QUALITY-AND-PLATFORMS.md#q-nav-02), [Q-NAV-03](QUALITY-AND-PLATFORMS.md#q-nav-03), [Q-NAV-04](QUALITY-AND-PLATFORMS.md#q-nav-04), [Q-NAV-05](QUALITY-AND-PLATFORMS.md#q-nav-05), [Q-NAV-06](QUALITY-AND-PLATFORMS.md#q-nav-06), [Q-NAV-07](QUALITY-AND-PLATFORMS.md#q-nav-07), [Q-NAV-08](QUALITY-AND-PLATFORMS.md#q-nav-08), [Q-NAV-09](QUALITY-AND-PLATFORMS.md#q-nav-09), [Q-NAV-10](QUALITY-AND-PLATFORMS.md#q-nav-10), [Q-NAV-11](QUALITY-AND-PLATFORMS.md#q-nav-11), [Q-NAV-12](QUALITY-AND-PLATFORMS.md#q-nav-12)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-UI-02

### Koin and coroutine lifecycle ownership

**Applicability:** Required baseline

**Required local behavior:**
- One qualified graph with platform/master/connection/human/company/entry/command lifetimes. Master outlives cashier, captured operation actor not mutable global current user; bounded structured jobs and observable teardown, no second database/runtime.

**Steps / rehearsal needs from the independent requirements:**
1. Start headless, switch sessions, pop/reset/reenter and retire service; assert correct resources persist/close and stale callbacks cannot affect new scope.

**Environment:** real application surfaces: Workspace/logout/entry lifecycle; actual boundaries: Production Koin scopes/coroutine owners.

**Original scenario checks:** [UX-07](SCENARIOS.md#ux-07), [OWN-02](SCENARIOS.md#own-02), [REL-07](SCENARIOS.md#rel-07)

**Original gate checks:** [Q-DI-01](QUALITY-AND-PLATFORMS.md#q-di-01), [Q-DI-02](QUALITY-AND-PLATFORMS.md#q-di-02), [Q-NAV-09](QUALITY-AND-PLATFORMS.md#q-nav-09)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-UI-03

### Coil and durable catalog-media boundaries

**Applicability:** Required baseline

**Required local behavior:**
- Scoped company/revision/private media caches, bounded thumbnail decode/disk/memory and cleanup. Durable offline required assets need manifest separate from evictable image cache. Image failure never blocks price/stock-authoritative checkout; no bearer token in keys/URLs.

**Steps / rehearsal needs from the independent requirements:**
1. Same URL different tenant, revoked/expired/corrupt/oversized image and offline navigation; old image completion cannot render after user switch, memory returns to bound.

**Environment:** real application surfaces: Catalog cards/media/attachment preview; actual boundaries: Production Coil/configured platform decoders/scoped cache.

**Original scenario checks:** [OWN-02](SCENARIOS.md#own-02), [TAX-07](SCENARIOS.md#tax-07), [UX-05](SCENARIOS.md#ux-05)

**Original gate checks:** [Q-IMG-01](QUALITY-AND-PLATFORMS.md#q-img-01), [Q-IMG-02](QUALITY-AND-PLATFORMS.md#q-img-02), [Q-IMG-03](QUALITY-AND-PLATFORMS.md#q-img-03)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-UI-04

### Arabic/English, RTL, accessible input and readable recovery

**Applicability:** Required baseline

**Required local behavior:**
- Resource/plural/placeholder parity shared/native, correct RTL/LTR mixed numbers/barcodes/receipts and direction; text scale/contrast/labels/error announcements, touch/keyboard/scanner and assistive access. No color-only state or routine raw technical dialogs. Shortcut same permission/confirmation as touch.

**Steps / rehearsal needs from the independent requirements:**
1. Rapid scan/key repeat/touch approval plus TalkBack/VoiceOver/desktop assistive on named hardware; language switch/resize retains data/amount meaning/focus. Recovery next actions distinct for local saved/waiting/unknown/cancel-pending/refund-due.

**Environment:** real application surfaces: All cashier/kitchen/admin/receipt/recovery surfaces; actual boundaries: Production native resources/semantics/input and UI-service bindings.

**Original scenario checks:** [UX-05](SCENARIOS.md#ux-05), [UX-06](SCENARIOS.md#ux-06), [UX-07](SCENARIOS.md#ux-07)

**Original gate checks:** [Q-UX-01](QUALITY-AND-PLATFORMS.md#q-ux-01), [Q-UX-02](QUALITY-AND-PLATFORMS.md#q-ux-02), [Q-UX-03](QUALITY-AND-PLATFORMS.md#q-ux-03), [Q-UX-04](QUALITY-AND-PLATFORMS.md#q-ux-04), [Q-UX-05](QUALITY-AND-PLATFORMS.md#q-ux-05), [Q-NAV-10](QUALITY-AND-PLATFORMS.md#q-nav-10), [Q-NAV-11](QUALITY-AND-PLATFORMS.md#q-nav-11)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-UI-05

### Receipts, document identity and marked reprints

**Applicability:** Required baseline

**Required local behavior:**
- Immutable unique company/branch/operator/collector/item/variant/modifier/quantity/discount/net/tax/charge/tender/change/fulfillment/return-exchange links. Show allocated promoted paid value and terms. Distinguish unpaid check, paid receipt, refund and reprint, fiscal status separate.

**Steps / rehearsal needs from the independent requirements:**
1. Read/print/reprint original receipt after price/user/shift change; no changed numbers/totals or second sale. Verify Arabic/bidi output and all totals independently.

**Environment:** real application surfaces: Receipt/refund/check/reprint preview and history; actual boundaries: Production immutable document/numbering/print jobs.

**Original scenario checks:** [UX-08](SCENARIOS.md#ux-08), [TAX-05](SCENARIOS.md#tax-05), [RET-05](SCENARIOS.md#ret-05), [PRC-03](SCENARIOS.md#prc-03)

**Original gate checks:** [Q-UX-02](QUALITY-AND-PLATFORMS.md#q-ux-02), [Q-RES-12](QUALITY-AND-PLATFORMS.md#q-res-12)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PER-01

### Printer/KDS routing, durable jobs and uncertain output

**Applicability:** Required baseline

**Required local behavior:**
- Explicit LAN/USB/Bluetooth model/OS capability routing and ownership; persistent print ID/status, dispatch only after required commit. Paper-out/cutter/disconnect/lost-response cannot undo sale or blindly duplicate kitchen instruction; marked audited reprints.

**Steps / rehearsal needs from the independent requirements:**
1. Physical assigned receipt/kitchen printer Arabic fonts/codepage/raster/QR/barcodes, paper failure, unknown reply and reprint. Emulation tests state logic only; physical status remains unverified until observed.

**Environment:** real application surfaces: Peripheral discovery/assign/test/queue/reprint/recovery; actual boundaries: Production printer/KDS adapters and durable job store.

**Original scenario checks:** [KIT-07](SCENARIOS.md#kit-07), [KIT-08](SCENARIOS.md#kit-08), [UX-08](SCENARIOS.md#ux-08)

**Original gate checks:** [Q-RES-11](QUALITY-AND-PLATFORMS.md#q-res-11), [Q-UX-05](QUALITY-AND-PLATFORMS.md#q-ux-05)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PER-02

### Real barcode scanner and scale integration

**Applicability:** Required baseline

**Required local behavior:**
- Qualify actual HID/camera/SDK scanner and named scale protocol by OS; leading-zero/pack/weighted validity/focus, scale tare/zero/stability/unit/precision and disconnect. Manual fallback only if permitted, never a claim physical adapter passed or legal calibration proved.

**Steps / rehearsal needs from the independent requirements:**
1. Physical rapid scans/malformed suffixes/scale unstable-invalid-disconnected readings; no duplicate quantity, inaccurate weight or payment from unavailable reading.

**Environment:** real application surfaces: Scanner/scale setup/test/entry/fallback; actual boundaries: Production platform scanner/scale adapters and authoritative conversion.

**Original scenario checks:** [STK-05](SCENARIOS.md#stk-05), [CAT-03](SCENARIOS.md#cat-03), [UX-05](SCENARIOS.md#ux-05)

**Original gate checks:** [Q-UX-03](QUALITY-AND-PLATFORMS.md#q-ux-03), [Q-UX-05](QUALITY-AND-PLATFORMS.md#q-ux-05), [Q-UX-09](QUALITY-AND-PLATFORMS.md#q-ux-09)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-PER-03

### Drawer hardware and external terminal qualification

**Applicability:** Required baseline

**Required local behavior:**
- Correct assigned drawer permission/open/repeat/jam handling separate from recorded cash custody. POS never initiates terminal payment in this scope. Automation uses synthetic external attestation; supervised actual terminal reconciliation needs owner authorization.

**Steps / rehearsal needs from the independent requirements:**
1. Physical drawer denied/duplicate/unavailable opening creates no money; authorized real terminal receipt/cancel/refund rehearsal only when explicitly approved, otherwise NOT_EXECUTED.

**Environment:** real application surfaces: Drawer assignment/open and external tender evidence; actual boundaries: Production drawer adapter/payment-attestation path.

**Original scenario checks:** [CASH-01](SCENARIOS.md#cash-01), [CASH-12](SCENARIOS.md#cash-12), [PAY-01](SCENARIOS.md#pay-01), [PAY-03](SCENARIOS.md#pay-03)

**Original gate checks:** [Q-UX-05](QUALITY-AND-PLATFORMS.md#q-ux-05), [Q-SEC-05](QUALITY-AND-PLATFORMS.md#q-sec-05)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-FISCAL-01

### Local fiscal documents, corrections and asynchronous truth

**Applicability:** Required baseline

**Required local behavior:**
- Immutable receipt/tax/correction numbering, original allocations/references and durable local submission intent. Pending/rejected/unknown/accepted-as-confirmed distinct; no invented legal acceptance or original sale rewrite. Launch entity classification/rounding/deadlines must be supplied before compliance claim.

**Steps / rehearsal needs from the independent requirements:**
1. Offline create/correct/retry intent and restart; one document identity and correct original snapshots. Real government connector/credentials unavailable is not passed by synthetic status transition.

**Environment:** real application surfaces: Fiscal configuration/submission/correction/retry status; actual boundaries: Production document/numbering/outbox and local connector boundary.

**Original scenario checks:** [TAX-04](SCENARIOS.md#tax-04), [TAX-05](SCENARIOS.md#tax-05), [TAX-06](SCENARIOS.md#tax-06), [TAX-07](SCENARIOS.md#tax-07), [TAX-08](SCENARIOS.md#tax-08), [TAX-09](SCENARIOS.md#tax-09), [PAY-07](SCENARIOS.md#pay-07)

**Original gate checks:** [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-RES-12](QUALITY-AND-PLATFORMS.md#q-res-12)

**Central portion:** HELD — Actual central-hosted and external fiscal sandbox/production transport/acceptance, only after authorization; legal qualification separate.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-FISCAL-02

### Traceable operational accounting export

**Applicability:** Required baseline

**Required local behavior:**
- Export complete source/cash/cost/obligation mappings for sales/refunds/unpaid supply/write-off/supplier settlement/landed costs/transfers, not sales-only or full statutory general-ledger claim. Company-validated account/tax mapping, bounded authorized export and independent status.

**Steps / rehearsal needs from the independent requirements:**
1. Reconcile synthetic full shift/purchase sources to generated local export totals and references; denied cost/export role cannot access; restart/cancel export does not change business ledgers.

**Environment:** real application surfaces: Accounting mapping/export/recovery status; actual boundaries: Production report/export/source mapping.

**Original scenario checks:** [TAX-09](SCENARIOS.md#tax-09), [PAY-07](SCENARIOS.md#pay-07), [PUR-08](SCENARIOS.md#pur-08), [PUR-14](SCENARIOS.md#pur-14), [PUR-15](SCENARIOS.md#pur-15), [CASH-05](SCENARIOS.md#cash-05)

**Original gate checks:** [Q-SEC-08](QUALITY-AND-PLATFORMS.md#q-sec-08), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03)

**Central portion:** HELD — Actual central/external accounting delivery connectors if launch requires them.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RELEASE-01

### Reproducible installed candidates and operating runbooks

**Applicability:** Required baseline

**Required local behavior:**
- Native candidate artifacts with exact source/toolchain/dependency/schema/config hashes, resources/native libraries/legal notices. Local startup/setup, synthetic accounts/data, feature manual checklist, update/rollback/restore/peripheral runbooks and supported hardware/capacity matrix. No remote publication/signing claim without actual authorized evidence.

**Steps / rehearsal needs from the independent requirements:**
1. Install/launch/update/uninstall-data-policy on actual OS, run full synthetic owner rehearsals and record results separately. Rebuilt or changed candidate invalidates affected evidence.

**Environment:** real application surfaces: Install/setup/rehearsal and owner manual checklist; actual boundaries: Actual candidate packages, Production runtime and operational runbooks.

**Original scenario checks:** [REL-08](SCENARIOS.md#rel-08), [UX-01](SCENARIOS.md#ux-01), [UX-02](SCENARIOS.md#ux-02), [UX-03](SCENARIOS.md#ux-03), [UX-04](SCENARIOS.md#ux-04), [UX-05](SCENARIOS.md#ux-05), [UX-06](SCENARIOS.md#ux-06), [UX-07](SCENARIOS.md#ux-07), [UX-08](SCENARIOS.md#ux-08)

**Original gate checks:** [Q-SEC-10](QUALITY-AND-PLATFORMS.md#q-sec-10), [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08), [Q-UX-09](QUALITY-AND-PLATFORMS.md#q-ux-09), [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10)

**Central portion:** None deferred.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.

## LF-RELEASE-02

### Independent frozen business and integration evidence

**Applicability:** Required baseline

**Required local behavior:**
- Separate designer/implementer/executor; freeze expected assertions before execution, preserve versions. All141 scenarios and79 gates retain applicable variants; exact tested revision/environment/observed ledgers/evidence/blockers. Domain-only/build/test-total success cannot prove local completion; hardware unexecuted and central deferrals not passes.

**Steps / rehearsal needs from the independent requirements:**
1. Validate inventory then execute separate production integration and business E2E/run records; record failing/blocked/unexecuted variants without rollup overclaim. Owner tests/explicit approval precede central phase.

**Environment:** real application surfaces: Owner manual checklist and evidence index; actual boundaries: Independent test execution and immutable evidence registry.

**Original scenario checks:** [CASH-01](SCENARIOS.md#cash-01), [CASH-02](SCENARIOS.md#cash-02), [CASH-03](SCENARIOS.md#cash-03), [CASH-04](SCENARIOS.md#cash-04), [CASH-05](SCENARIOS.md#cash-05), [CASH-06](SCENARIOS.md#cash-06), [CASH-07](SCENARIOS.md#cash-07), [CASH-08](SCENARIOS.md#cash-08), [CASH-09](SCENARIOS.md#cash-09), [CASH-10](SCENARIOS.md#cash-10), [CASH-11](SCENARIOS.md#cash-11), [CASH-12](SCENARIOS.md#cash-12), [OWN-01](SCENARIOS.md#own-01), [OWN-02](SCENARIOS.md#own-02), [OWN-03](SCENARIOS.md#own-03), [OWN-04](SCENARIOS.md#own-04), [OWN-05](SCENARIOS.md#own-05), [OWN-06](SCENARIOS.md#own-06), [OWN-07](SCENARIOS.md#own-07), [OWN-08](SCENARIOS.md#own-08), [OWN-09](SCENARIOS.md#own-09), [OWN-10](SCENARIOS.md#own-10), [OWN-11](SCENARIOS.md#own-11), [KIT-01](SCENARIOS.md#kit-01), [KIT-02](SCENARIOS.md#kit-02), [KIT-03](SCENARIOS.md#kit-03), [KIT-04](SCENARIOS.md#kit-04), [KIT-05](SCENARIOS.md#kit-05), [KIT-06](SCENARIOS.md#kit-06), [KIT-07](SCENARIOS.md#kit-07), [KIT-08](SCENARIOS.md#kit-08), [KIT-09](SCENARIOS.md#kit-09), [KIT-10](SCENARIOS.md#kit-10), [KIT-11](SCENARIOS.md#kit-11), [KIT-12](SCENARIOS.md#kit-12), [KIT-13](SCENARIOS.md#kit-13), [KIT-14](SCENARIOS.md#kit-14), [KIT-15](SCENARIOS.md#kit-15), [STK-01](SCENARIOS.md#stk-01), [STK-02](SCENARIOS.md#stk-02), [STK-03](SCENARIOS.md#stk-03), [STK-04](SCENARIOS.md#stk-04), [STK-05](SCENARIOS.md#stk-05), [STK-06](SCENARIOS.md#stk-06), [STK-07](SCENARIOS.md#stk-07), [STK-08](SCENARIOS.md#stk-08), [STK-09](SCENARIOS.md#stk-09), [PAY-01](SCENARIOS.md#pay-01), [PAY-02](SCENARIOS.md#pay-02), [PAY-03](SCENARIOS.md#pay-03), [PAY-04](SCENARIOS.md#pay-04), [PAY-05](SCENARIOS.md#pay-05), [PAY-06](SCENARIOS.md#pay-06), [PAY-07](SCENARIOS.md#pay-07), [PAY-08](SCENARIOS.md#pay-08), [PAY-09](SCENARIOS.md#pay-09), [PAY-10](SCENARIOS.md#pay-10), [PAY-11](SCENARIOS.md#pay-11), [PAY-12](SCENARIOS.md#pay-12), [PAY-13](SCENARIOS.md#pay-13), [PAY-14](SCENARIOS.md#pay-14), [RET-01](SCENARIOS.md#ret-01), [RET-02](SCENARIOS.md#ret-02), [RET-03](SCENARIOS.md#ret-03), [RET-04](SCENARIOS.md#ret-04), [RET-05](SCENARIOS.md#ret-05), [RET-06](SCENARIOS.md#ret-06), [RET-07](SCENARIOS.md#ret-07), [RET-08](SCENARIOS.md#ret-08), [RET-09](SCENARIOS.md#ret-09), [RET-10](SCENARIOS.md#ret-10), [RET-11](SCENARIOS.md#ret-11), [RET-12](SCENARIOS.md#ret-12), [RET-13](SCENARIOS.md#ret-13), [TAX-01](SCENARIOS.md#tax-01), [TAX-02](SCENARIOS.md#tax-02), [TAX-03](SCENARIOS.md#tax-03), [TAX-04](SCENARIOS.md#tax-04), [TAX-05](SCENARIOS.md#tax-05), [TAX-06](SCENARIOS.md#tax-06), [TAX-07](SCENARIOS.md#tax-07), [TAX-08](SCENARIOS.md#tax-08), [TAX-09](SCENARIOS.md#tax-09), [REL-01](SCENARIOS.md#rel-01), [REL-02](SCENARIOS.md#rel-02), [REL-03](SCENARIOS.md#rel-03), [REL-04](SCENARIOS.md#rel-04), [REL-05](SCENARIOS.md#rel-05), [REL-06](SCENARIOS.md#rel-06), [REL-07](SCENARIOS.md#rel-07), [REL-08](SCENARIOS.md#rel-08), [REL-09](SCENARIOS.md#rel-09), [REL-10](SCENARIOS.md#rel-10), [CFG-01](SCENARIOS.md#cfg-01), [CFG-02](SCENARIOS.md#cfg-02), [CFG-03](SCENARIOS.md#cfg-03), [CFG-04](SCENARIOS.md#cfg-04), [CFG-05](SCENARIOS.md#cfg-05), [CFG-06](SCENARIOS.md#cfg-06), [CFG-07](SCENARIOS.md#cfg-07), [CFG-08](SCENARIOS.md#cfg-08), [PRC-01](SCENARIOS.md#prc-01), [PRC-02](SCENARIOS.md#prc-02), [PRC-03](SCENARIOS.md#prc-03), [PRC-04](SCENARIOS.md#prc-04), [PRC-05](SCENARIOS.md#prc-05), [PRC-06](SCENARIOS.md#prc-06), [CAT-01](SCENARIOS.md#cat-01), [CAT-02](SCENARIOS.md#cat-02), [CAT-03](SCENARIOS.md#cat-03), [CAT-04](SCENARIOS.md#cat-04), [CAT-05](SCENARIOS.md#cat-05), [CAT-06](SCENARIOS.md#cat-06), [CAT-07](SCENARIOS.md#cat-07), [CAT-08](SCENARIOS.md#cat-08), [CAT-09](SCENARIOS.md#cat-09), [CAT-10](SCENARIOS.md#cat-10), [PUR-01](SCENARIOS.md#pur-01), [PUR-02](SCENARIOS.md#pur-02), [PUR-03](SCENARIOS.md#pur-03), [PUR-04](SCENARIOS.md#pur-04), [PUR-05](SCENARIOS.md#pur-05), [PUR-06](SCENARIOS.md#pur-06), [PUR-07](SCENARIOS.md#pur-07), [PUR-08](SCENARIOS.md#pur-08), [PUR-09](SCENARIOS.md#pur-09), [PUR-10](SCENARIOS.md#pur-10), [PUR-11](SCENARIOS.md#pur-11), [PUR-12](SCENARIOS.md#pur-12), [PUR-13](SCENARIOS.md#pur-13), [PUR-14](SCENARIOS.md#pur-14), [PUR-15](SCENARIOS.md#pur-15), [PUR-16](SCENARIOS.md#pur-16), [UX-01](SCENARIOS.md#ux-01), [UX-02](SCENARIOS.md#ux-02), [UX-03](SCENARIOS.md#ux-03), [UX-04](SCENARIOS.md#ux-04), [UX-05](SCENARIOS.md#ux-05), [UX-06](SCENARIOS.md#ux-06), [UX-07](SCENARIOS.md#ux-07), [UX-08](SCENARIOS.md#ux-08)

**Original gate checks:** [Q-DB-01](QUALITY-AND-PLATFORMS.md#q-db-01), [Q-DB-02](QUALITY-AND-PLATFORMS.md#q-db-02), [Q-DB-03](QUALITY-AND-PLATFORMS.md#q-db-03), [Q-DB-04](QUALITY-AND-PLATFORMS.md#q-db-04), [Q-DB-05](QUALITY-AND-PLATFORMS.md#q-db-05), [Q-DB-06](QUALITY-AND-PLATFORMS.md#q-db-06), [Q-DB-07](QUALITY-AND-PLATFORMS.md#q-db-07), [Q-DB-08](QUALITY-AND-PLATFORMS.md#q-db-08), [Q-DB-09](QUALITY-AND-PLATFORMS.md#q-db-09), [Q-DB-10](QUALITY-AND-PLATFORMS.md#q-db-10), [Q-BE-01](QUALITY-AND-PLATFORMS.md#q-be-01), [Q-BE-02](QUALITY-AND-PLATFORMS.md#q-be-02), [Q-BE-03](QUALITY-AND-PLATFORMS.md#q-be-03), [Q-BE-04](QUALITY-AND-PLATFORMS.md#q-be-04), [Q-BE-05](QUALITY-AND-PLATFORMS.md#q-be-05), [Q-BE-06](QUALITY-AND-PLATFORMS.md#q-be-06), [Q-BE-07](QUALITY-AND-PLATFORMS.md#q-be-07), [Q-BE-08](QUALITY-AND-PLATFORMS.md#q-be-08), [Q-BE-09](QUALITY-AND-PLATFORMS.md#q-be-09), [Q-BE-10](QUALITY-AND-PLATFORMS.md#q-be-10), [Q-SEC-01](QUALITY-AND-PLATFORMS.md#q-sec-01), [Q-SEC-02](QUALITY-AND-PLATFORMS.md#q-sec-02), [Q-SEC-03](QUALITY-AND-PLATFORMS.md#q-sec-03), [Q-SEC-04](QUALITY-AND-PLATFORMS.md#q-sec-04), [Q-SEC-05](QUALITY-AND-PLATFORMS.md#q-sec-05), [Q-SEC-06](QUALITY-AND-PLATFORMS.md#q-sec-06), [Q-SEC-07](QUALITY-AND-PLATFORMS.md#q-sec-07), [Q-SEC-08](QUALITY-AND-PLATFORMS.md#q-sec-08), [Q-SEC-09](QUALITY-AND-PLATFORMS.md#q-sec-09), [Q-SEC-10](QUALITY-AND-PLATFORMS.md#q-sec-10), [Q-RES-01](QUALITY-AND-PLATFORMS.md#q-res-01), [Q-RES-02](QUALITY-AND-PLATFORMS.md#q-res-02), [Q-RES-03](QUALITY-AND-PLATFORMS.md#q-res-03), [Q-RES-04](QUALITY-AND-PLATFORMS.md#q-res-04), [Q-RES-05](QUALITY-AND-PLATFORMS.md#q-res-05), [Q-RES-06](QUALITY-AND-PLATFORMS.md#q-res-06), [Q-RES-07](QUALITY-AND-PLATFORMS.md#q-res-07), [Q-RES-08](QUALITY-AND-PLATFORMS.md#q-res-08), [Q-RES-09](QUALITY-AND-PLATFORMS.md#q-res-09), [Q-RES-10](QUALITY-AND-PLATFORMS.md#q-res-10), [Q-RES-11](QUALITY-AND-PLATFORMS.md#q-res-11), [Q-RES-12](QUALITY-AND-PLATFORMS.md#q-res-12), [Q-RES-13](QUALITY-AND-PLATFORMS.md#q-res-13), [Q-RES-14](QUALITY-AND-PLATFORMS.md#q-res-14), [Q-NAV-01](QUALITY-AND-PLATFORMS.md#q-nav-01), [Q-NAV-02](QUALITY-AND-PLATFORMS.md#q-nav-02), [Q-NAV-03](QUALITY-AND-PLATFORMS.md#q-nav-03), [Q-NAV-04](QUALITY-AND-PLATFORMS.md#q-nav-04), [Q-NAV-05](QUALITY-AND-PLATFORMS.md#q-nav-05), [Q-NAV-06](QUALITY-AND-PLATFORMS.md#q-nav-06), [Q-NAV-07](QUALITY-AND-PLATFORMS.md#q-nav-07), [Q-NAV-08](QUALITY-AND-PLATFORMS.md#q-nav-08), [Q-NAV-09](QUALITY-AND-PLATFORMS.md#q-nav-09), [Q-NAV-10](QUALITY-AND-PLATFORMS.md#q-nav-10), [Q-NAV-11](QUALITY-AND-PLATFORMS.md#q-nav-11), [Q-NAV-12](QUALITY-AND-PLATFORMS.md#q-nav-12), [Q-DI-01](QUALITY-AND-PLATFORMS.md#q-di-01), [Q-DI-02](QUALITY-AND-PLATFORMS.md#q-di-02), [Q-IMG-01](QUALITY-AND-PLATFORMS.md#q-img-01), [Q-IMG-02](QUALITY-AND-PLATFORMS.md#q-img-02), [Q-IMG-03](QUALITY-AND-PLATFORMS.md#q-img-03), [Q-UX-01](QUALITY-AND-PLATFORMS.md#q-ux-01), [Q-UX-02](QUALITY-AND-PLATFORMS.md#q-ux-02), [Q-UX-03](QUALITY-AND-PLATFORMS.md#q-ux-03), [Q-UX-04](QUALITY-AND-PLATFORMS.md#q-ux-04), [Q-UX-05](QUALITY-AND-PLATFORMS.md#q-ux-05), [Q-UX-06](QUALITY-AND-PLATFORMS.md#q-ux-06), [Q-UX-07](QUALITY-AND-PLATFORMS.md#q-ux-07), [Q-UX-08](QUALITY-AND-PLATFORMS.md#q-ux-08), [Q-UX-09](QUALITY-AND-PLATFORMS.md#q-ux-09), [Q-UX-10](QUALITY-AND-PLATFORMS.md#q-ux-10), [Q-PERF-01](QUALITY-AND-PLATFORMS.md#q-perf-01), [Q-PERF-02](QUALITY-AND-PLATFORMS.md#q-perf-02), [Q-PERF-03](QUALITY-AND-PLATFORMS.md#q-perf-03), [Q-PERF-04](QUALITY-AND-PLATFORMS.md#q-perf-04), [Q-PERF-05](QUALITY-AND-PLATFORMS.md#q-perf-05), [Q-PERF-06](QUALITY-AND-PLATFORMS.md#q-perf-06), [Q-PERF-07](QUALITY-AND-PLATFORMS.md#q-perf-07), [Q-PERF-08](QUALITY-AND-PLATFORMS.md#q-perf-08)

**Central portion:** HELD — Central-phase tests stay explicitly deferred pending owner approval.

**Result:** NOT_RUN. Record each actual rehearsal/variant in [the result template](RESULT-TEMPLATE.csv); full behavior above remains the expected outcome.
