# Ukkera POS — consolidated implementation plan

**Updated:** 24 October 2026. **Status: actual independent-agent review and focused implementation debates completed; gated implementation plan, not a release approval.**

The owner subsequently authorized **local implementation**, then explicitly waived Claude and requested the available agent team. No further Claude call is authorized. Six plan-review Claude calls and one later implementation call failed; that history remains in [DEBATE-SUMMARY.md](DEBATE-SUMMARY.md). The independent reviewer `/root/independent_review` has now returned real independent, challenge/rebuttal and resolution rounds via the collaboration tools. It worked read-only; the lead alone maintains these review deliverables. This is not retroactive successful Opus delegation.

All 19 original handoff files remain preserved. This plan permits bounded local implementation with the stop-lines below; it does **not** certify a complete POS, actual platform/physical/fiscal qualification or unimplemented adapters. Work must stay local: no pushes, PRs, publication, uploads or deployment; no live migrations or real financial transactions. Actual builds/tests are recorded separately in `../ukkera-pos/VERIFICATION_REPORT.md`.

### Reviewed corrections and stop-lines

- Missing history blocks unsafe retries **and new IDs spending unreconciled rights**; positive stored results remain recoverable with current authorization. One controlled replacement appointment does not cryptographically fence copied issuers/targets.
- Use the constrained TRUNCATE/FULL Room candidate in §4.1, not unproved WAL tuning. Atomic commit and qualified runtime/authentication adapters are mandatory before live commands.
- Pricing uses an explicitly versioned constrained-after operational profile in §7.1. Ordinary independent Hamilton allocations can violate exclusion/cap semantics; signed explanations do not fix that. Feasibility is non-monotone and fiscal coupling is separately validated.
- **Sensitive P2pKit rc6 RPC stays unbound/fail-closed** until a request/session-bound disclosure correction is qualified (§2.3). UI generations/cache-zero alone are not wire confidentiality.
- All four business activities, native company administration, five client/master platform families, 141 scenarios, 79 original gates and 25 ordered platform pairs remain required. A foundation or retail integration slice does not reduce release scope.

## 1. Authority, scope and preserved baseline

Read this together with the preserved handoff. Detailed entity catalogs, operational exceptions, scenario triggers/results and task exits are incorporated by reference, not replaced by abbreviated summaries:

- [Architecture][ARCH], [operations][OPS], [company policies/catalog][POL], [purchasing][PUR].
- [Domain/contract blueprint][BLUE], [technology guide][TECH], [implementation plan][IMPL] and [task catalog][TASK].
- All **141 business scenarios** in [acceptance][ACCEPT] and their exact [traceability register][TRACE]; all **79 engineering gates** in [quality][QA].
- [Settled decisions and remaining facts][DEC]. Historical [backend findings][BE] are evidence to revalidate, not proof of today's deployed behavior.

Original files remain unchanged. Latest accepted user decisions take precedence. The review deltas and subsequent implementation-boundary findings are in [FINDINGS.md](FINDINGS.md). They clarify or strengthen the implementation contract; they do not delete original requirements. The dated three-round decisions are recorded explicitly; the later owner instruction, not the original agent start prompt, authorizes current local implementation.

### Release-one product contract

| Area | Preserved requirement |
| --- | --- |
| Market and hierarchy | Egypt; initial operating/purchasing currency EGP; Organization → Company → Branch. Independent activities, catalogs, policies and permissions per company. |
| Activities | Restaurants, cafes, clothing **and** supermarkets in release one. An early retail slice is an integration milestone, not a reduced release. |
| Platforms and roles | Windows, macOS, Android, physical iPhone and physical iPad, each as client **and** master. Determine actual OS/CPU/device profiles through qualification; do not remove a platform to avoid engineering work. |
| Branch authority | Exactly one explicitly appointed local master, including a one-device branch. No autonomous client finalization, cloud checkout fallback, periodic cloud lease or central login-renewal dependency for provisioned local work. |
| Confirmation | A sale is confirmed by a **durable master commit**. Internet, bank, printer and optional standby acknowledgements are not prerequisites. Total loss of unreplicated storage can nevertheless lose recovery history. |
| Payments | Cash and cashier-attested external tenders, split/partial collection, refunds and reconciliation. No direct bank authorization or claim of independently verified external payment. |
| Identity | Branch-local employee accounts; another branch requires another account. Device admission, service identity and human authorization are separate. Central administration uses separate identities, tokens, caches and navigation roots. |
| Prices | A newer relevant company price revision supersedes older branch overrides. Protected quotes, collection-locked/part-paid orders and posted documents preserve their snapshots. |
| Custody and ownership | Exclusive counted drawer custody; owner-private orders with explicit accepted handoff; narrow manager, kitchen, courier and returns projections. No automatic employee debt or unexplained balancing entries. |
| Stock and kitchen | No negative-stock bypass. Retail reserves before collection. Made-to-order ingredients issue on kitchen send; verified before-start cancellation reverses the original allocation; after-start cancellation classifies consumed cost once. Paid-unsent ingredients remain reserved. |
| Returns | Same-selling-branch offline returns/exchanges from original snapshots and remaining entitlement. Accepting a return reserves/consumes entitlement before refund payment. Restricted exchange offsets, not wallets. |
| Procurement | Quick and PO receiving, inspection/custody, supplier invoices, advances/payables/actual settlement, claims/returns/credits, landed cost, branch allocations and designated invoice settlement authority. |
| Administration | Complete company administration in CMP. No required customer-facing web portal. Central acceptance and each branch's activation are distinct. |
| Technology and names | New working repository `ukkera-pos`, backend namespace `BranchPos`, original YAML untouched. Latest jointly compatible stable Nav3/Koin/Coil/Room/toolchain; explicitly qualify requested P2pKit RC. |
| Recovery | Optional standby never gates healthy sales. Named delegated replacement approval, verified copy, genuine old-writer fencing, no automatic promotion and no promise of universally zero-loss asynchronous recovery. |
| Experience | Arabic/English, RTL, touch/keyboard/scanner operation, accessibility, resumable setup, isolated training, physical qualification and operational pilots. |

**Exclusions remain:** customer credit sales, customer/store-credit balances, loyalty, cross-branch return redemption, full statutory GL/payroll, direct bank authorization and unspecified unrestricted connectors. Supplier payables and unresolved customer refund obligations are included and are not stored-value products. Browser cashier support is unconfirmed and not a prerequisite. Bookings/subscriptions/rentals/regulated-industry workflows are not implied by a generic service or custom-attribute record. Optional features remain optional: standby, tips, receipt-linked deposits, enabled campaign/channel options, min/max reorder suggestions and explicitly enabled receiptless-return exceptions.

## 2. Source adoption and foundation gates — F03–F06

### 2.1 Repository decisions

Adopt the already accepted **standalone `ukkera-pos` repository**, with selective, pinned reuse rather than a wholesale Queue migration or unrelated ecosystem rewrite.

| Source | Decision and prerequisite |
| --- | --- |
| `kmp-apps`, inspected `95d3574a1f15ed8f1fcf5d2b97fa2955ad7bacc3` | Reuse suitable shell/design/navigation and build knowledge after compatibility review. Do not copy its combined KMP/Android-application convention as the new foundation. Its schema-v2 app-identity YAML and lowercase dotted package are not `BranchPos` backend YAML. |
| `kmp-features`, inspected `ed3c0b63a8b31f627ee9c230648fb83dee121bd3` | Inventory reusable public contracts/UI and qualified adapters. Do not reuse the online Viki identity path as offline branch authentication, or the convenience session store as master security/recovery/financial storage. |
| `ukkera/modules`, `main-next`, `viki-go`, `module-manager`, `common-main-r` | Now available in isolated local pinned checkouts. Revisions and exported patches are recorded in the implementation source manifest/backend evidence. Real isolated generation and bounded Go route tests do not establish a mounted, transactional Mongo service. Preserve every existing YAML and reject generic BranchPos access; finish typed runtime/mapping/DB qualification before acceptance. |
| P2pKit | Match artifacts to reviewed source. This review inspected rc6 source at `3ef2c6155abd66292011c3775b30fb71d280e9c7`, not the unrelated dirty working-tree changes. No unreviewed fork is treated as the official release. |

The current KMP checkout uses Kotlin 2.4.10 / CMP 1.11.1 / AGP 9.3.1 / Gradle 9.6.1 and legacy Android compatibility switches. These are **observed inputs, not the new lockfile**. Official metadata checks during review confirmed publication of Kotlin 2.4.21, CMP 1.12.2, Room3 3.0.3 and P2pKit 0.8.0-rc6; publication is not compilation or runtime compatibility. Recheck official coordinates/stability at T01 and pin a coherent graph, including Navigation 3 runtime/UI/event/lifecycle, Koin, Coil, KSP, SQLite, HTTP engines, crypto/native providers, Go/Mongo and packaging tools. No dynamic versions or blanket “latest everything” upgrade.

Use shared KMP library modules with the qualified `com.android.kotlin.multiplatform.library` layout and separate Android launcher. Keep domain types/contracts/security/database/testing, domain services, master/RPC/sync/recovery runtime, native adapters and UI areas separate without creating a module per screen. Domain rules do not depend on UI, Koin lookups or generated central documents.

### 2.2 Required T01 evidence

Before broad feature integration, qualify a real transaction/result/outbox, actual paired P2pKit RPC, protected same-device access, offline login/restart, secure storage, native library packaging and Nav3/Koin/Coil lifecycle behavior. Prove desktop service/window independence and Kotlin/Native iOS hosting; a JVM engine or simulator-only proof is insufficient. Pin hashes, OS/CPU/toolchains, commands, results, skips and environmental gaps.

**Network preflight (F03):** pinned rc6 strict JVM `OrganizationLan` rejects another up non-loopback interface, including competing adapters/bridges; its selected interface must not be virtual/point-to-point/unsafe. Explicit approved private CIDRs, interface and numeric address are required. Qualify the branch network, firewall, multicast-denied/manual pinned-endpoint path, address changes and loss of permissions. Recreate the runtime after approved interface/address changes; recover uncertain operations by business ID.

Do not assume Docker bridges, VPNs, dual Ethernet/Wi-Fi or a virtualized adapter can coexist with strict mode. Isolate central development containers from the master host, or select another **measured** supported network arrangement. A VM is not automatically a valid workaround. Never disable security checks, use permissive trust or make network changes on an owner's machine without a separate authorized implementation task. Same-device operation uses the same authorized handlers through protected in-process access or IPC; it must not depend on prohibited loopback RPC.

P2pKit transport deduplication is volatile and incarnation-bounded, not financial idempotency. Respect the reviewed 64 KiB default/1 MiB ceiling and bounded request deadlines (default 10 s, ceiling 30 s, subject to pinned-version qualification). Long work uses durable jobs and resumable bounded transfers; no unbounded backup or catalog RPC.

### 2.3 Employee-bound RPC disclosure — F13

Pinned P2pKit rc6 device trust is not employee-session result authorization. `RpcConfiguration.authorize` receives only `PeerIdentity`; retained/status replies bypass the business handler, original terminal bodies survive cache trimming, and queued sends/reconnects bind to the device rather than the originating employee. `resultCacheBytes=0` removes cached bodies but does not solve first-response, bodyless-status or queued-message disclosure. `tryNotify` is not an escape hatch.

Keep sensitive RPC unavailable until an independently reviewed library change or demonstrably equivalent supported architecture provides: verified immutable request/session/generation scope; scope-aware duplicate/status/cancel/receipt/terminal authorization including failures; revocable outbound tickets with an explicit disclosure/revocation ordering; no automatic result rebinding to the latest same-device session. A new employee uses a new authenticated application result lookup. UI/session fences remain defense in depth, never the confidentiality proof. Already transmitted information cannot be recalled.

Prefer a bounded library correction over a new result-encryption subsystem or restarting the entire shared host on every employee authorization change. Any isolated local patch has a distinct version/provenance and its own regression evidence; never call it official rc6. Test malicious same-device raw ID/digest replay, original delayed success/error, queued send revocation, expiry/disable, reconnect and bodyless cancellation/status. Device-only tests are insufficient.

**Selected bounded correction (23 October implementation review):** SDK-owned, single-use initial physical lifetime, exclusive application-output claim, immutable original target and one-shot outbound/publication tickets. Final SDK `belongsTo(session)` must compare the captured object referentially before RPC reads facade identity/incoming properties; an interface wrapper can otherwise substitute both. Authenticate original credentials on every original/duplicate invoke before record lookup, and freshly authorize every success/error disclosure. Seal before activating policy replacement. Capture bounded deadlines before asynchronous work and never renew their original start.

The exact cut is **logical whole-message commitment**, not native completion: a fully encoded message committed before seal may begin or finish transmission afterward only on its OLD context, even if no bytes had yet left. It is not secret from someone retaining that endpoint's old keys. A new employee/application context therefore requires a fresh physical context. Client delivery is an opaque retained handle whose one-shot `take` freshly authorizes and commits local publication immediately before returning to the immutable old presentation context; never enqueue that result into a new employee's shared state.

The first isolated RPC profile is strict **invoke-only**, with distinct binding/wire capability, bounded lifetime tombstones and refusal at capacity instead of identity eviction. Status/cancel/receipt/notify are ignored before lookup/mutation; legacy downgrade, bootstrap, reconnect/reinvoke and transport recovery are unavailable in this stage. Application operation recovery must later use a fresh authenticated invocation. This staged profile does not delete required realtime, notifications, background service or recovery features. Local coordinates are `local.ukkera.pos.p2pkit:<module>:0.8.0-SNAPSHOT`, label `rc6+ukkera-scope1`, pinned to rc6 source; nothing is published.

Callback/serializer exceptions are not transport evidence. Only an exact private per-call remote failure created after current authorized publication may preserve remote status. Normalize local failures to the actual original request ID, SDK ticket commitment/ambiguity and trusted encoding/decoding phase; a decoder's arbitrary `SafeBeforeExecution` must not authorize retry after an Invoke was sent. Financial recovery still uses the original durable application operation ID.

**Source admission and evidence:** the [local candidate](../ukkera-pos/integrations/p2pkit/README.md) has two separately manifested layers: tested26-file base and an **unapplied three-file public-SDK integration overlay**. Base core927/126 and RPC125/118 host tests passed with unchanged locks; the four overlay tests compiled on JVM/Android but never executed because strict runtime memberships were not locked. Actual Android ABI comparisons failed against unchanged baselines. Complete supported whole-project lock/API maintenance, independent dependency/signature review and strict overlay execution are required before integration admission. The complete lock writer was not attempted; a partial-writer guard rejection is not proof it cannot succeed. Do not substitute shadow sources, partial locks, exclusions or current compiler output as an approved ABI baseline. Main POS verification fingerprints exported patches/manifests; it does not run or qualify the ignored P2p checkout.

**Required remaining transport gates:** real suspend-inclusive elapsed clocks (Android elapsedRealtime; Apple mach_continuous_time; qualified JVM OS bridge), native failure/regression behavior, actual sleep/hibernation tests, Swift exported expected-refusal handling, application policy ordering and platform/service qualification. nanoTime/systemUptime plus a best-effort resume observer is insufficient. An Unconfined continuation may delay lifecycle return while an outer connection lock remains held after authority has already sealed; do not claim unconditional cleanup/return progress. Sensitive POS routes remain unbound until these affected guarantees are qualified.

## 3. Authorities, roles and core journeys

### 3.1 Enforced authority matrix

| Actor/context | May do | Must not inherit |
| --- | --- | --- |
| Central company head/delegate | Scoped structure, people/delegation, catalog/prices/policies, procurement allocations, reports/alerts/export and recovery oversight in CMP | Other companies through organization membership; local cashier power; direct mutation of branch financial/stock facts |
| Branch cashier/service employee | Local login, own order lifecycle, effective prices, own cash custody and permitted collection/returns | Another cashier's orders/drawer, cost reports, exceptional discounts/refunds or recovery approval without grants |
| Branch operational manager | Specifically delegated approvals, reassignment, counts, exceptions, review and local administration | Unbounded company powers or automatic master replacement authority |
| Inventory/purchasing staff | Scoped quantity/inspection/receipt workflows; costs/settlement only with additional rights | Unrestricted cash, financial reports or shared unallocated company procurement allowances |
| Kitchen/courier | Station tasks or assigned delivery/collection/remittance projection | General order browsing, policy edits or another cashier's drawer |
| Master/central-ingestion service | Branch-bound typed operations/evidence under service identity | Human approvals or a central management identity |
| Recovery participant/approver | Restricted copies; specific authenticated named delegation to a controlled replacement | Automatic promotion from discovery, backup possession, heartbeat loss or a generic manager title |

Maintain separate perform/request/approve/administer/export rights, scope and field sensitivity. Enforce both amount and percentage limits where both apply; a missing limit is not unlimited permission. Explicit named self-approval is allowed only where the company configured it for the action. Approvals bind actor, document/version, lines/amount/reason and scope; edits invalidate stale approval. Server-side queries, direct IDs, subscriptions, exports and cached views obey the same effective policy.

Default one interactive session per branch account, with explicit device move; additional sessions are a company option. Moving/logging out never relabels pending money or transfers drawer custody. A branch-local disabled account or activated revocation is enforced immediately locally; a remote central change remains pending while disconnected.

### 3.2 Journeys and observable completion

- **Provision → train → live:** resumable scoped setup, validated policies/catalog, counted stock/value/float, pairing and recovery access, isolated training rehearsal, explicit live acknowledgement. Retried steps cannot duplicate opening balances.
- **Open → sell → close:** local identity and counted drawer; scan/search/variants; authoritative quote/reservation; actual tender attestation; durable receipt and recoverable unknown outcome; immutable counted closing. Works with WAN disconnected.
- **Restaurant/cafe:** table/channel, incremental portions/modifiers, send/start/ready/served, cancellations/remakes, ownership handoff, partial/split tender, split/merge bill, incidents and later recovery without second revenue/stock issue.
- **Clothing:** valid size/color selection, per-variant barcode/stock, original-allocation partial return, inspection and linked exchange difference.
- **Supermarket:** rapid scan, weight/pack precision, configured scale/encoded barcode validation, lot/expiry, promotions, last-unit race and bounded lookup.
- **Procurement:** quick/PO receipt, actual custody/inspection, partial/over/rejected goods, independent invoice match, actual settlement/credit/return, valuation correction and fresh/pending company views.
- **Management:** all company actions inside CMP; offline cached views/drafts are marked as such, reauthorized/version-checked on submission, and never presented as already applied at disconnected branches.
- **Recovery:** reconnect to same master first; if replacement is necessary, verify authority/copy, disclose missing history, reconnect with new trust and resolve pending work before affected new mutations.

All normal and exceptional variants in [OPS][OPS], [PUR][PUR], [POL][POL] and [ACCEPT][ACCEPT] remain required; these journeys are not a new cap on scope.

## 4. Data, APIs and the durable command contract

Use the complete record/field/relationship catalog in [BLUE §2–4][BLUE] as the minimum registry. Freeze each record's owning authority, tenant keys, revision/immutability, scalar types, relationships/deletion rules, actual unique indexes, local/central/security location, recovery coverage and migration version in T02. Candidate procedure names are **not existing backend endpoints**.

### 4.1 Scalars and storage

- UUID domain IDs are independent of receipt numbers and clocks. Stable operation IDs are durably saved **before first submission**, unique by `(branchId, operationId)` across epochs; never reused for a new actor or payload. Map native backend storage IDs separately.
- EGP posted money uses bounded signed 64-bit minor units, string-encoded on JSON. Exact quantities initially allow up to six fractional digits and unit price/cost up to eight; units impose narrower valid precision. Use checked exact intermediate arithmetic, reject overflow/unsupported precision and never use floating point for financial/stock decisions.
- Revisions/sequences use checked nonnegative 64-bit values encoded as strings. UTC/master commit time, client observation time, Africa/Cairo local business date/cutoff and fiscal date are distinct. Master sequence orders facts; clock rollback cannot recycle IDs or reset persistent lockout safety.
- Preserve original codes/barcodes and leading zeros; normalized uniqueness keys have a version. Enforce dimensional conversions and whole serialized units.
- Non-null tenant scope and composite tenant references are enforced at the trusted side. Unique constraints cover operation ID, epoch/sequence, company SKU/barcode, branch login, active drawer assignment, ownership acceptance and consumed source allocations. Qualify partial-index/null behavior; pre-checks and a Kotlin mutex are not the constraints.
- Keep master authoritative Room data, protected master security records, non-authoritative client caches/pending evidence, central immutable evidence, central derived projections and bounded attachments separate. Product/account archival never cascades away receipt, entitlement or ledger history.

#### Initial durable Room profile — F15

Use a **file-backed**, explicitly selected `TRUNCATE` journal with Room3 3.0.3 and bundled SQLite candidate; request `setSingleConnectionPool()` and reject a driver advertising its own pool. Room sets non-WAL `synchronous=FULL` before creation/migration and on subsequent connections. WAL is configured NORMAL; a user `onOpen` callback is too late for initialization and is not called for every later connection.

Acquire the actual writer with `useWriterConnection`; require no enclosing transaction, then verify that same connection's `journal_mode=truncate`, `synchronous=2`, persistent main file and foreign keys before `withTransaction(IMMEDIATE)`. Commit effects/result/journal/audit/outbox and actual uniqueness/CAS constraints together. Return success only after the **outer** commit completes. Do not expose alternate DAO writes, repair bad PRAGMAs silently, use private APIs/SQL interception, or enable destructive migration fallback.

Use actual schema uniqueness/tenant references plus append-only guards. An UPDATE/DELETE trigger alone does not protect against SQLite `INSERT OR REPLACE` when recursive triggers are off: reject existing primary **and alternate unique** identities before insert, including active claims. Verify installed guards and migration behavior. A hash detects corruption but does not authenticate an untrusted restored database.

Test fresh creation, migration-time PRAGMAs, reopen, enclosing transaction rejection, accidental replace, every commit cut, disk/driver errors, two writers against one file and killed-process/lost-reply recovery. A process kill does not establish physical power-loss durability. The single-connection profile trades throughput for a straightforward public-API contract; qualify original P0/P1/P2 load and all target VFS/filesystems rather than claiming 128-client or WAL performance. WAL/FULL remains a future qualified alternative, not required complexity now.

#### Historical replacement hardening — D054 / F33

Resolved engineering contract, separately implemented/verified after D053:

- Preserve all33 historical guard bodies, entities/indexes and exports1/2/3.
  Add18 guards in trigger-only version4: global implicit-rowid collision refusal
  before INSERT on the original11 tables; rowid immutability before every UPDATE
  on their seven mutable tables. Also reject branch scope and claim scope/
  namespace/key changes. Existing five aggregate/protected logical-key/revision
  guards and four immutable financial UPDATE guards remain unchanged. Supported
  payload/revision updates, account terminalization and claim release/reinsert
  remain available; unsupported raw identity relocation is intentionally narrowed.
- Fresh master creation installs only the new callback's18; all51 are verified
  by their owning callbacks on open, with immutable-facts callback still last.
  Generic marker/legacy builders do not change. Exact guard name/table/SQL checks
  detect required-guard drift, not arbitrary added UNIQUE constraints or hostile
  files. Schema4's identity intentionally equals v3; do not add dummy DDL.
- Direct1→4/2→4 first reject all18 reserved names using ASCII-insensitive matching
  across main-schema object types, then invoke unchanged1→3/2→3 and install/
  verify18 with a reference check. Direct3→4 instead verifies its genuine v3
  persistent TRUNCATE/FULL profile, exact version/identity and all33 old guards,
  including corrected issuance protection. Never invoke strict3 after an older
  path while Room still exposes1/2 metadata. No IF NOT EXISTS, adoption, silent
  repair, re-encryption, row renumbering or rewriting historical guard bodies.
- Bad source stamp/required guards or new reserved names must fail before any
  CREATE preparation/execution. Arbitrary old-object/data/FK failures may occur
  later and must roll back. RAISE(ABORT) undoes the failed statement, not earlier
  successful statements if the caller catches it and commits. Test uncaught
  Room transaction rollback separately. All rowid aliases and negative/-1 values
  remain covered; an existing raw -1 may conservatively block auto-rowid insert.
- Keep independent generated LegacyV3 and actual1→3/2→3/reopen observations at3.
  Preserve exact typed cells, rowids, old guard bytes and absent issuance; then
  upgrade the SAME file3→4 and invoke the real current protected store. This is
  not adapter execution at v3. New populated3→4 tests must include fifth-table
  issuance. Verify all18 guards, identity-narrowing positives/negatives, aliases/
  recursive modes, multirow statement atomicity, genuine migrations, wrong-name/
  wrong-type/case aliases, partial-DDL rollback/original reopen/clean retry and
  unchanged corrupt-source refusal. No historical damage repair is inferred.

Four actual pre-fix UPDATE regressions reproduced destructive SQL before source
correction. Security independently reviewed the lead's correction, genuine legacy
fixtures and nonvacuous statement-rollback oracles. The same four test bytes passed,
followed by all59 focused methods and the24October06:17:07Z full cut:2,347 cases/
22 profiles/179 XMLs,53 harness tests,8,234 unchanged inputs, every2,312 D053
identity plus35 JVM methods. Pricing independently accepted both corrected focus
and full evidence. A prior15-failure raw-observer target was corrected only by
test-side sole-writer coordination outside a transaction; a stale542-vs577 harness
assertion was raised, not removed. Both failed packets remain failures. Exact
commands/hashes are in `../ukkera-pos/VERIFICATION_REPORT.md`.

This resolves the bounded v4 guard/migration source cut, not an automatic arbitrary-
DDL validator or hostile-file defense. These guards neither authenticate a user
nor complete local login; actual protected runtime and native qualification remain.

#### Current-row SQL validation — F28

Before hydrating a selected aggregate/claim, perform a key-only scalar preflight for actual SQLite `typeof`, canonical-width text bytes (including embedded-NUL tails), INTEGER revision and bounded BLOB length. Invalid storage remains an error, not an absent row. Validate hydrated exact scope/key/schema/owner/hash and hash the same bounded payload copy returned to the handler. Route kernel CAS/claim validation through these strict transaction-local reads too: `getLong` truncating REAL2.25 to2 is not a valid revision check. Preserve cancellation and view lifetime; sanitize private ordinary failure causes. These checks cover selected matching keys, not all database corruption or authenticated freshness. The implementation and33 substantive selected-row/validator regressions have passed bounded host verification; OS storage/custody remain separate gates.

### 4.2 Mutation path

1. Admit authenticated device plus the correct human/service session; bound/decode input; derive tenant/actor, check active writer and recovery admission.
2. Compute a versioned canonical semantic digest from command/version, effective tenant/branch, **original actor**, target revisions and normalized payload. Exclude bearer tokens, transport IDs/incarnation and the routing epoch hint.
3. Under authorized result-recovery rules, return a matching persisted result without repeating effects. Mismatched actor/payload rejects. A supervisor's resolution is separately permissioned/audited and preserves the original actor/custody.
4. In one controlled database transaction, establish operation identity and recheck role, approval, aggregate revisions, stock/entitlements/cash/allocations and writer/recovery state. Concurrent initial submissions serialize on real constraints.
5. Compute authoritative allocations; atomically persist domain effects, result, audit, ordered journal envelope and durable sync/print/kitchen/fiscal job intents. No network IO or manager dialog inside this transaction.
6. Return `COMMITTED` **only after durable commit**. Dispatch bounded workers afterward. A printer, projection, cloud or standby failure changes its own status, not the committed business outcome.

Define typed commands and purpose-limited query projections for all families in [BLUE §6][BLUE]; no generic all-fields query. Specify pagination/cursor bytes/rows, JSON/enum evolution, safe localization codes and authorized error details. Keep Kotlin/Go golden fixtures. Development, training and production identities/namespaces must never cross ingest boundaries.

### 4.3 Typed integration and evidence constraints

- Store journal positions by `(scope, authorityEpoch, sequence)`, but operation identity by `(scope, operationId)` across epochs. Replays expose their original epoch. Validate exact immutable-trigger DDL and actual actor AUTHORIZATION recovery membership, not just trigger names or any arbitrary employee key; out-of-profile supervisor audit time cannot disclose results.
- First cash adapter accepts pay-in/operating-expense only with current authorization, original operation binding and actual drawer custody. INVENTORY_SETTLEMENT needs the missing invoice/source/designated-settlement authority and complete companion obligations; do not turn a cash-move grant into that authority. Existing stored-result recovery stays before transition preparation. Test genuine pending refunds/closing/transfers and all outer-store failure boundaries. Historical producer compatibility needs an actual historical fixture, not a newly recorded Unsupported result labeled legacy success.
- A whole-branch inventory cut must be complete and fit the existing64KiB row/1MiB proposal limits; never filter away collateral expiry or truncate codec data. Whole-branch domain revision advances once; normalized child revisions may not. Use independent storage generations plus protected complete manifests or a reviewed range reader for normalized storage. Preserve every source/effect/allocation claim, valuation fact and current recovery key. A branch-ID STOCK sentinel is not a wildcard for held lots/pools.
- Prefer the existing immutable command journal to a second per-operation inventory-history store. Add a bounded transaction-bound retained-command accessor that validates the original result/journal pair, exact v1 framing/scalars/hashes/digest and historical after-writes/claims/jobs without current-handler replay. Both missing is only no retained pair, never authoritative absence or genesis; broken/oversized/unsupported pairs fail closed. Historical writes are immutable facts, not reusable proposals or current projections. Match the current cut/companion CAS pair to their selected producer's exact writes; do not require that operation to be the global journal head. Qualified origin/frontier and exclusive mutation composition/import remain necessary—coherent stale hashes do not prevent rollback. Full historical folds belong producer/import/recovery/audit qualification, not every healthy sale's hot path.
- The first implemented inventory adapter is **receipt-only**, not that finalizer: two current cut/companion rows with exact retained producer/result/writes/claims, complete supported receipt/position/value/expiry crosswalk, all older source/upstream claim owners and source-object identity. Protected production genesis remains absent; the concrete test initializer requires successful fresh-file installation and closed/frozen producer declarations for both kinds and claim namespaces. Reject unsupported reservation/issue/cost/custody states instead of filtering them. Enforce both32-count and actual byte ceilings with no truncation. Coherent rollback of the entire historical pair can still pass; selected retained producer is not current-frontier proof.48/48 adapter and23 Room integration tests do not authorize procurement or accepting money.
- Sequence the next inventory integration as **A1 domain posted facts/crosswalk → independent review and targeted/full combined execution → A2 adapter companion/provenance → B closed fixture finalizer**. A1 reuses internal inventory storage DTOs, retains the complete ordered Movement/Valuation/ReservationRecorded/SafetyChanged/IssueRecorded batch and rejects unsupported whole-cut histories rather than filtering them. Later same-pool valuation sources must be the actual preceding `valuation.next` object, not merely equal values or the initial pool. Preserve repeated intermediate reservation revisions and collateral/zero-stock expiry. All source/result pools must be purchase/retail/expiry-profile compatible, including untouched or cancelled-zero cost history; ordinary zero-valued Consumed balances are legitimate. Decoding is historical data only, never a recreated plan/proof, source provenance or current-frontier grant. A2 must separately bind cumulative receipt/lot/upstream/source/claim evidence and the complete companion within the unchanged64KiB/1MiB limits; do not broaden receipt-v1 or create a parallel duplicate DTO layer. A1 passed independent source review, the959-case target at23:40:25Z (inventory164/156), then the1,769-case/22-profile full combined run at23:49:40Z; pricing independently audited both exact packets without discrepancy. The subsequent 24 October A2 debate resolved the bounded design and explicitly authorized the new work below; source review/execution and production acceptance remain separate.
- A receipt Movement contains no new-lot expiry/safety payload: genuine receive plans can have identical posted bytes and different complete result cuts. A2 therefore binds **both full cut bytes and the full receipt/lot/expiry/upstream request**, never inferring provenance from posted facts. Source-free decoding can accept omitted first reservation records or whole receipt pairs; live source correspondence and outer producer completeness must reject omissions. Explicit passing limitation tests document these boundaries rather than weakening the live checks or inventing a second planner in decode.
- **24 October A2 resolution (D046):** the subsequent real three-round debate authorizes bounded implementation, not acceptance. Use `inventory.companion/retail-v1`, the same two current CAS kinds and unchanged cut codec. Retain complete receipt provenance, issue-ID→producer ownership, previous pair refs and only the latest canonical A1 batch string; genesis has none. `observe` supplies explicitly unqualified row/retained facts; `projectContribution` supplies only two inventory writes, source/origin claims and the original live deadline. Concrete genesis/receive validators must verify the entire normalized request/result/writes/claims/jobs, rejecting unknown/composite versions. Later B's private sales-aware coordinator owns complete composite acceptance. A constructor-trusted port can be sound but is unnecessary now; no per-call caller-selected expectations or inventory-subset approval. Only inventory current rows equal the selected producer's after-writes, not later mutable sales/cash rows.
- **A2 correspondence and bounds:** partition every source into exactly one receipt or issue producer and fold receipts/issues in source order per position, with checked units, operation ordering and nonnegative intermediate balances. Do not separately sum lifetime receipts into Long: `receive MAX → issue MAX → receive MAX` is valid. Bind every receipt's lot/expiry/original value/upstream identity, including exhausted positions; preserve zero Consumed balances. Reuse existing whole-cut domain validation through a factual helper, not a second planner. No reservation birth index/claim is added without an actual owner invariant; B checks collection/reservation correspondence and its explicit sales uniqueness contract. Fixture-only ceilings are 32 receipts/issues/reservations, 64 physical sources and 128 inventory operations, not achievable business capacity. No pruning on exhaustion; actual complete cut/escaped companion remain ≤64KiB. Batch request/result limits are 32KiB/4KiB; the complete outer proposal still owes ≤1MiB accounting including input/framing/claims/jobs. Receipt-v1 scalar-wire extraction must preserve byte-exact encoding and rejection behavior with explicit legacy tests.
- **A2 execution boundary, 24 October:** independently reviewed retail-v1 and both domain helpers passed the1,198-case target and the subsequent2,008-case/22-profile/160-XML full local run at01:29:14Z. Security audited the target; pricing audited the full packet without discrepancy. All8,198 inputs stayed unchanged;53 harness tests, build targets/SOFTWARE smoke, Apple main-klib targets and fresh backend probes passed. This is not native/runtime/business acceptance. The inventory observations remain unqualified and contributions partial; B still requires the complete staged composite contract and fresh tests. Read-only B design/challenge is now permitted, not an accepting production finalizer.

- **Historical sales prerequisite (D047):** add a narrowly named intrinsic collection decoder that reuses existing stored/canonical/binding checks, leaving the current order/session-linked API unchanged. An old retained collection cannot borrow today's advanced cash session as historical dependency evidence. Historical decoding grants neither current custody nor permission to finalize; B validates available historical composite links and fresh current dependencies separately. No seventh cash write, planner replay or recreated authorization/proof. These additions require new independent source review and actual targeted/combined execution before B use.
- First fixture-only finalizer may start from already durably COVERED money with explicitly synthetic custody/identity/provenance. Produce that fixture through actual staged proposals from domain0/storage1: receive, reserve+begin, revalidate+attempt, cashier-attested outcome, commit and reopen. Never import an already-advanced snapshot at generation1, downstamp revisions or weaken insertion guards. Successful finalization must atomically persist exactly six aggregate writes: the complete inventory pair plus order, collection, **standalone PaidSale**, and receipt entitlement, with no second cash movement. Held finalization commits exactly the inventory pair and complete safety/collateral effects, preserves paid obligation, creates no issue/sale claims and replays its original held result. Validate the producer's entire writes/claims/jobs, not merely an inventory subset. Missing state never supplies invented zero amounts/owners. This fixture does not solve the capacity reservation required BEFORE future physical collection, protected genesis, complete history or production scale.
- Before Held **or** Issued planning, validate the same paid target, exact actor/order-owner/collector/drawer-custodian, payable, revisions and reservation/source links. The domain's Issued-only `finalizePaid` checks do not automatically protect a Held store path. For the first bounded profile, any WholeBranch, STOCK-kind or exact STOCK hold denies finalization even if an old alias was readmitted; this denial-only barrier is not a wildcard grant or permanent removal of restored-branch support. Broader readmission needs qualified canonical stock-resource mapping. Preserve distinct cost-source natural identities, pool/receipt/upstream links and every linked adjustment/collateral effect.
- Actual COVERED collection evidence may legitimately coexist with inventory exposure UNKNOWN, PART_PAID or PAID: `recordOutcome` does not update inventory, and all three labels require protected resolution rather than automatic release. Accept those three with the full checks above; reject UNCOLLECTED in this finalizer, without narrowing A1's historical enum support. Add partial payment → PART_PAID exposure → remaining payment → COVERED fixtures for both Held and Issued with unchanged collection evidence. `issueReservation` already performs safety revalidation; do not prepend another same-operation revalidate plan. Propagate the original live plan's exclusive deadline to the existing outer-commit checks, not a decoded historical batch or renewed timestamp.
- Verification itself is gated: immutable same-byte XML capture/parse/hash/archive, captured suite timestamps within both completed host-phase bounds, actual non-skipped suites, current-invocation backend counts/pins, complete compiler/build input fingerprint before/after, symlink refusal, one run lock and bounded cleanup of owned subprocess groups. Terminal publication must stage a nonaccepting state, then sample cancellation at an explicit completion cutoff; signals after that cutoff are post-verification, not a promise of cancellation through process exit. Diagnostic/stubbed harness results never grant application acceptance. Targeted old reports cannot qualify newer source, missing platform tests or the141/79 acceptance register.

### 4.4 Closed staged-checkout fixture — D051/D052, not a production gateway

The A1→A2→B sequence above now has a source-reviewed, executed and independently
audited **JVM real-Room fixture**. This resolves the fixture milestone only.
Production identity/runtime, protected current frontier/initialization,
procurement authority and durable capacity reserved before actual collection
remain prerequisites to accepting application commands. Keep existing public
domain APIs, kernel schema and unavailable UI gateways unchanged by this stage.

Use six closed version1 commands: `fixture.checkout.open`, `.create`, `.begin`,
`.attempt`, `.outcome`, `.finalize`, alongside unchanged genesis/receive. Seed
only synthetic authority/policy and empty drawer/custodian assignment roots at
domain0/storage1. Real open/create establish actual sessions/orders and claims;
named synthetic baskets run through the actual pricing engine. Commit and reopen
between stages; never install advanced snapshots or invent missing-state defaults.

| Command/outcome | Aggregate writes | New uniqueness claims |
| --- | ---: | --- |
| Open cash session | 3 | 4, including opening source and active assignments |
| Create order | 1 | ORDER |
| Reserve + begin collection | 4 | COLLECTION |
| Revalidate + begin attempt | 4 | PAYMENT_ATTEMPT |
| Unknown outcome, PRESERVE / DERIVE exposure | 1 / 3 | none |
| NotPaid outcome, PRESERVE / DERIVE | 2 / 4 | none |
| CashReceived outcome, PRESERVE / DERIVE | 2 / 4 | PAYMENT_RECORD + CASH_SOURCE |
| ExternalAttested outcome, PRESERVE / DERIVE | 2 / 4 | PAYMENT_RECORD |
| Final Held | 2: full inventory pair | none |
| Final Issued | 6: inventory pair, order, collection, standalone PaidSale, entitlement | issue source + SALE + SALE_ORDER + RETURN_ENTITLEMENT |

- Before **both** final branches, check exact current local-employee authorization
  and action/operation/digest/reason/revision/amount; owner=collector=custodian=
  actor; active assignment claims and OPEN cash; every requested revision,
  including read-only dependencies; canonical quote/allocation correspondence
  and `SalesSnapshotLinks.requireCollection`; COVERED/resolved money, no prior
  sale/handoff, and the target's retained reservation identity. Zero payable
  uses `COLLECTION_FINALIZE_ZERO`. UNKNOWN/PART_PAID/PAID exposure can coexist
  with covered money; UNCOLLECTED cannot. A different genuine pending collection
  in the same cash session is allowed; this is not global pending reconciliation.
- Outcomes record actual observations even after stock becomes unsafe or an
  earlier reservation deadline passes. PRESERVE neither reads inventory nor
  takes a STOCK barrier. Unknown retains the **same cash object**, not a no-op
  rewrite. DERIVE invokes the actual exposure plan from the after-collection:
  unresolved or zero-collected NotPaid→UNKNOWN, partial→PART_PAID, COVERED→PAID.
  Never accept a requested exposure enum; commit legitimate same-enum updates.
  Preserve every financial effect in the immutable normalized request and
  actual after-collection/cash, without another receipt/event row or cash repost.
- Claim acquisition requires absence even if the existing owner is equal.
  Retries recover the original operation. Open/cash-source claims belong to
  session, ORDER to order, COLLECTION/SALE to order, attempt/payment-record to
  collection, SALE_ORDER/entitlement to sale, inventory source to producer and
  receipt origin to receipt. These are uniqueness owners, not human permissions.
- Call the issue planner once. Held keeps all collateral expiry/hold effects;
  Issued never rewrites/reposts cash. Preserve exact command, source and plan
  identity plus the original exclusive live deadline. Latest inventory producer
  need not belong to this target. Freeze complete kind/claim declarations and
  reject outsider overlap, including protected AUTH/policy declarations.
- `requireCheckoutAfterFacts` and `requireCheckoutInventoryProducer` use finite
  dispatch and exact canonical request/result/after-write/schema/generation/
  claim/job correspondence. No recursive history callbacks, expected-write
  approval port, current repricing or recreated plans. Only the selected current
  inventory pair must equal its retained after-writes. Historical collateral
  may advance; absent preimages, old arithmetic/auth/deadlines, full history,
  authenticity and current frontier are **not** established by these validators.
  Keep explicit passing limitation regressions for those boundaries.
- Retain64KiB rows/1MiB full framed envelope. The closed fixture's conservative
  bound is432,141 bytes, not an application-capacity claim or reserved recording
  space before money. No history pruning or extra global pending restriction.

Verification: the corrected full run at24October02:51:11Z passed2,129 host
cases/22 profiles/167 XMLs and53 harness tests with8,211 unchanged inputs. All
2,008 A2 identities remain plus121 Checkout JVM methods. Security source-reviewed
all13 files and the minimal compile-fix deltas; pricing independently accepted
the exact evidence packet. The first compilation failure remains preserved.
Android/desktop and all22 Apple main-klib targets were UP-TO-DATE; SOFTWARE smoke
executed. No Apple runtime or native release evidence is inferred. See
`../ukkera-pos/VERIFICATION_REPORT.md` for exact packet, hashes and limitations.

## 5. Corrected retry and incomplete-history recovery — F01–F02

### 5.1 Outcome contract (replaces the unsafe unqualified absence interpretation)

| Outcome | Meaning and next action |
| --- | --- |
| `COMMITTED` | Matching original durable result is available; recover it without effects, including after an approved epoch change. |
| `REJECTED` | This known command conclusively produced no domain effects; an external physical payment may still need resolution. Do not use this status to infer what happened in missing history. |
| `IN_PROGRESS` | Known command still resolving; bounded lookup/backoff, no new charge or operation ID. |
| `NOT_RECORDED` | No record **and verified history covers every possible prior execution on the admitted authority chain**. May resubmit the same original command through the transaction protocol. Not a cached permanent negative or proof of bank failure. |
| `HISTORY_INCOMPLETE` | An absent result may be in a gap/unreplicated tail or an unavailable archive. Preserve the original operation/evidence and require reconciliation; do not automatically re-execute it. |
| Transport `OUTCOME_UNKNOWN` | Client lacks a conclusive reply. Keep command, actor, lock and attestation, then query the authority. Transport timeout/cancellation is not rollback. |
| `RESOURCE_RECONCILIATION_REQUIRED` | Proposed new mutation touches rights/balances/policies whose recovered state is not trustworthy. Do not generate a fresh ID to bypass this hold. |

Persist coverage by authority chain/epoch and coherent checkpoint, known gaps, possible unknown tail, available archives and reconciliation decisions. A highest restored sequence is not proof of a terminal cut. Positive verified results remain usable even during a recovery hold. Negative result lookup and command execution share race-safe identity coordination so an earlier handler cannot commit after a supposedly final “not found.”

### 5.2 Write admission after a stale restore

Restoring a stale snapshot can resurrect not only a missing operation but also **stock, drawer cash, return/deposit rights, exchange offsets, supplier settlement/receipt/PO allowances and local authorization state**. Therefore operation-ID deduplication alone is insufficient: a new operation ID could spend the same restored rights again.

1. Restore to a **non-authoritative, quarantined recovery state**. Authenticate the prepared recovery authority, verify manifests/keys/schema/cuts, preserve gaps and original actors. Never invent missing paid receipts or unused entitlements.
2. Determine a conservatively bounded affected scope from verified evidence. If the missing tail cannot be bounded, treat potentially affected branch resources as untrusted rather than assuming “only pending client requests” were lost.
3. Permit safe scoped reads and separately authorized reconciliation commands. Withhold ordinary mutations touching affected stock/custody/entitlements/allocations or uncertain grants. Unaffected work is allowed only when independence is demonstrated.
4. Reconcile physical stock/cash and kitchen/courier/safe custody against retained receipts, client attestations, independent copies, supplier evidence and source allocations. A stock count can establish current stock; it cannot establish an absent sale's unused refund entitlement or prove an invoice was never paid. Preserve claims, legal deadlines, pending refunds and customer obligations while evidence is incomplete.
5. Persist resource-specific opening/reconciliation adjustments, links to evidence, responsible approver, remaining uncertainty and the readmission decision. Re-establish authorized local accounts/sessions; do not revive copied bearer tokens or unknown revoked grants.
6. If old tail data later becomes available, ingest it through **read-only evidence reconciliation**. Match its effects against recovery adjustments already incorporated in counts/balances. Do not both replay the old stock/cash effect and keep an adjustment representing that same effect. Preserve original document facts and expose any unresolved accounting variance separately.

This is a disaster-recovery restriction, **not** a standby ACK or cloud requirement for healthy master checkout. If no independent usable copy or sufficient evidence exists, state the loss/reconciliation limit; no plan can recover absent data by changing an epoch.

### 5.3 Authority serialization and fencing

Keep the original planned flow: Preparing → Draining → Sealed → Activated → Completed, one target-bound handover ID; the old writer drains and durably seals before target activation. Abort only before seal. Lost replies resolve from durable states, not a second handover.

For emergency recovery, add an explicit **single recovery incident and exclusive issuance/custody procedure**. Bind appointment to branch, predecessor authority, incident, target identity, checkpoint, approving delegation and durable issuance state. Serialize different approvers and target selections as well as retries of the same handover.

A signature, one-use token or epoch number is **not alone proof of exclusivity** when disconnected issuance state can be copied. The deployment must qualify company-controlled recovery custody and physical/operational exclusion of the old writer **and competing successor ceremonies**. Changing target requires cancellation/fencing of any prior appointment/target. Losing the issuer does not authorize uncoordinated reissuance. If exclusion cannot be established, do not activate another writer. No cloud lease, automatic election or unrequested consensus subsystem is added. Owner acceptance of the actual custody/fencing arrangement remains Q02 in [OPEN-QUESTIONS.md](OPEN-QUESTIONS.md).

## 6. Sales, cash, food service and fulfillment

### 6.1 Orders and collection

Track independent ownership, commercial, preparation-by-quantity, fulfillment, collection, refund and incident states; do not infer paid from delivered/ready/closed. Handoff request/acceptance uses expected revision; owner remains until accepted. Manager reassignment is explicit. Resolve or supervise transfer of an in-progress collection without changing original cash custody. Bill split/merge is same-branch, compatible context and authorized ownership only; conserve preparation IDs, charge/tender allocations and collected amounts atomically.

`payment.begin` freezes the payable version and reserves required retail/virtual-kit/prepaid-unsent ingredient allocations before asking for money. Validate all components, not merely a positive total. A part-paid or unknown collection stays locked. Keep payer/collector/session, attempt ID, tender, amount, optional required-by-policy terminal/reference, time and cashier attestation. External references are duplicate hints, not proof of bank approval.

Record cash actually received, change actually given and external outcomes separately. No card change; no false coverage, synthetic collection or credit sale. Ordinary sale finalization requires paid coverage or an authorized zero-payable document with its tax/stock treatment. Finance and preparation may complete at different times. Recover physical-payment-before-POS-commit cases without charging again.

### 6.2 Cash custody and reconciliation

Drawer assignment is exclusive with counted opening float and one active custodian/session. Payment records, including external tenders, reference the collecting session. Break/lock and user logout are not drawer close. Transfers have one ID, distinct sending/receiving custody and acknowledgement; unacknowledged custody remains visible.

Expected closing cash = opening + cash received − change − actual cash refunds + non-sale pay-ins − pay-outs − safe transfers. If stored customer cash is already net of change, do not subtract change twice. Classify inventory settlement, expense and internal custody distinctly.

Use Open → Closing → SubmittedClosed → Reviewed. Closing establishes a mutation cut, resolves unknown financial work, handles owned unpaid orders, counts actual cash and records actual retained float/safe delivery. Cancel Closing safely before submission only. Review/linked later corrections never rewrite a submitted close. A variance does not create employee debt or a fictional expense; bank reconciliation is separate and cannot require internet to close locally. Refund/pay-out needs real available cash; otherwise keep the obligation pending.

**Golden fixture:** `1000 + 5000 − 400 − 200 + 100 − 150 − 3000 = 2350`; count `2360` gives `+10`; retain `500` and transfer actual `1860`. External tenders `4000` do not affect drawer cash.

### 6.3 Kitchen, production and exceptions

Kitchen send atomically checks selected quantities/recipe/modifier/lot versions, issues ingredients once, creates quantity/station tasks and durable delivery intents. Already-issued ingredients are not deducted at checkout. Paid-unsent portions retain reservations and pending-preparation state; send converts their own reservation once.

Start and before-start cancellation compete on the same quantities/revision. Any required station starting makes the portion started. Restore only verified unstarted original allocations; unsafe returned ingredients go to quarantine, not automatic sellable stock. Once started, retain consumption and reclassify WIP to sold/waste once. Paper printing does not prove preparation is unstarted; require kitchen confirmation. Unknown start/print outcome stays pending, not a speculative reversal.

New/remade work checks new stock and has its own cost. Merchant error does not double customer quantity/revenue; different items/customer changes require linked charge/waiver/refund decisions. Preserve partial-start cancellations, wrong supplied item, free meal, failed delivery, abandoned pickup, duplicate independent order and subsequent payment-dispute workflows in OPS §7.

**Fixture:** bread `10`, meat `1500 g`; two burgers at `1 bread + 150 g` leave `8 / 1200 g`. One verified before-start cancellation restores `9 / 1350 g`; after-start cancellation restores nothing and must not issue another negative movement.

Finished production/assembled kits create stock only on actual completed yield; selling finished output consumes that SKU once. Virtual kits consume/reserve components according to each component's fulfillment timing. No estimated yield becomes sellable early.

### 6.4 Delivery and unpaid fulfillment

Retail/takeaway ordinarily collects before release; dine-in service and configured COD preserve an outstanding obligation. Exceptional release records an authorized stock/fulfillment event, not a fake paid sale. Delivery status, customer collection and courier remittance are separate; remittance is a custody transfer, not another sale. Courier access is limited to assignments.

Record supplied quantities, actual partial payments and remaining incident. Later collection goes into today's collecting session without second sale/stock issue; approved write-off has no cash movement. Preserve prepared-food loss, returned goods inspection and performed/unperformed delivery charge decisions. Tips, if enabled, remain distinct custody/accounting categories.

**Fixture:** served `300`, collected `100`, incident `200`; later collect `200` against the incident, not a new `200` sale.

## 7. Catalog, prices, tax and receipts — F07

Keep all composable capabilities in POL §6: stocked SKUs, variants, multiple barcodes, exact measured units/packs, lots/expiry/recall, serials, ingredients, recipes, finished batches, virtual/assembled kits, non-stock services, typed attributes and optional receipt-linked containers/deposits. Reject cyclic components, incompatible conversions, fractional serials, ambiguous identifiers and casual post-transaction changes to stock/tracking identity. Use archive/version/conversion workflows instead of deleting provenance or disabling stock to evade shortages.

Eligible price selection is explicitly **priority → target specificity → channel specificity → highest qualified quantity threshold → stable rule ID**. Effective time is eligibility, not a hidden latest-start priority. Company price changes supersede only older overrides for that relevant product/context. Retain immutable append-only `(overrideId, revision)` history even when an override is removed; reject below-high-water rollback or rewritten versions. An identical latest retired revision may explicitly reactivate after current company/policy/authorization checks. Protected adapters must supply complete authoritative prior history, never a client-omitted registry. Preserve existing quotes by default; optional recalculation affects eligible unlocked/uncommitted orders only. New lines use current effective prices unless an explicitly added policy says otherwise. Purchase cost changes never publish selling prices automatically.

### 7.1 Versioned operational calculation profile — F07/F14

Freeze calculation order before T12: effective base price → permitted item/quantity/bundle promotions → permitted order/manual discount → configured charges and tax. The default one-promotion-per-unit/exclusive-group choice selects the greatest declared customer reduction **within each declared group**, with stable ties; this is not a general arbitrary-combination optimizer. Additional stacking is explicit. Enforce basket/branch caps locally; global hard limits need preallocated rights or coordination, not ordinary offline coupon strings.

The bounded implemented selector is explicitly opt-in `group-ordered-greedy-actual-posted/v1`: compare greatest **actual posted** candidate reduction inside each declared group, then priority/stable ID. A{1,2}=10 versus B{1}=6 and C{2}=6 can choose10, not optimal12; disclose that behavior in preview, never advertise global optimization. Bundle/buy-get selection separately freezes original-gross-weighted canonical paid targets; equal original100/100 at bundle target100 must allocate50/50. Reject cap-constrained V2 results such as100/0 rather than silently changing the promised refund basis. Later explicitly permitted manual10 on the first unit may yield40/50; do not invent a universal final45/45 rebalance. Persist selection evidence and later adjustments distinctly; returns use the actual final paid snapshot. Broad campaign variants and maximum/native latency qualification remain required beyond this profile.

For the initial operational profile:

1. Interpret inclusive/exclusive prices into exact net/tax contributions. Rates, inclusion and category are separate. Select eligible units/charges and freeze campaign choices before allocating the manual order discount.
2. A **fixed payable reduction** is a gross customer reduction, not blindly the same numerical reduction in item net. For fixed percentage service charges on discounted net, use each eligible unit's marginal payable factor: `1 + itemTaxRate + sum(serviceRate × (1 + serviceTaxRate))` for the applicable percentage charges. Fixed unaffected charges are not in that marginal factor. Solve the supported linear gross-to-net reduction exactly and allocate it proportionally with caps/stable ties.
3. Preserve exact intermediates until the named posting boundary. The initial before-discount document uses HALF_UP payable and stable largest-remainder component postings. The selected **`linear-protected-components-half-up-lr/v2`** after-profile freezes every exact-unaffected component, including excluded/fixed charges. Changed eligible components remain nonnegative, cannot increase from the prior posted step, and remain within their exact floor/ceiling bounds. Enforce cumulative original posted amount caps and exact percentage limits; distribute feasible remaining cents deterministically by exact remainders/stable keys, with bounded per-portion constraints. Reject infeasible configurations before publication/collection; do not silently use unconstrained Hamilton or an arbitrary optimizer.
4. The advertised discount is the actual before/after posted payable reduction. Positive mathematical eligible capacity is an **upper bound, not an attainable maximum**. Feasibility is not monotone: assess the requested amount; no binary search or assumption that all amounts below a maximum work. Parent/child, tax buckets and tender/refund totals conserve exactly. Signed zero-sum redistribution can explain an alternative profile but cannot restore this profile's eligibility/approval guarantees.
5. Persist algorithm version, eligibility, exact bases and posted price/discount/tax/charge allocations. Include the reduction of dependent service charges in the advertised payable-reduction reconciliation; distinguish item reduction from dependent-charge reduction and never subtract the advertised total again from already discounted components. Partial returns use the stored original allocation and final-portion remainder, not recomputation at today's rules.
6. A fiscal adapter may require another legally validated posting boundary. Qualify an allocation that satisfies both its rule and the advertised payable reduction; reject an unsupported configuration **before publication/collection**, not with an incorrect receipt. Do not claim the proposed document allocation is already legally approved for every launch company.

**New review fixture:** inclusive `114` plus exclusive `100` at a hypothetical `14%` gives net `200`, item tax `28`. A `10%` service charge on eligible net, itself taxed `14%`, gives total `250.80`. Fixed advertised discount `25.08` must yield **`225.72`**: item net `180`, item tax `25.20`, service `18`, service tax `2.52`. Subtracting `25.08` from item net first instead produces `219.34968`, not the promised result. Also cover mixed/zero rates, tiny amounts, caps, bundles, all permitted stacking, fractional quantities and repeated partial returns with independent fixtures.

**Adversarial posting fixtures (all numbers in minor units):**

- Before exact `(1.8,1.7,1.6)`, C excluded, posts `(2,2,1)`. Discount A+B by 1: ordinary independent Hamilton gives `(1,1,2)` and increases C. The constrained profile gives `(2,1,1)`. Also test `(1.1,1.2,1.55)` where ordinary Hamilton puts the entire discount on excluded C.
- Before `(1.6,1.6,.7,.7,.7)`, first two eligible, posts `(1,1,1,1,1)`. Discount 1 is infeasible under frozen exclusions/floor bounds; discount 2 is feasible `(0,0,1,1,1)`; discount 3 is infeasible. No monotone maximum search.
- Hypothetical14%: net3.6/tax.504 plus excluded charges10.3+10.3 posts `(4,1,10,10)=25`. Gross discount1 gives exact net776/285 and tax2716/7125. Operational component bounds permit `(3,1,10,10)=24`, but a per-line HALF_UP exact-tax profile requires tax0 and cannot reach24 within those bounds. Reject that fiscal/profile combination, not change the discount, silently keep tax1, or invent a rounding line.

Historical snapshot codecs must validate bounded canonical versions/economics/caps/component sums without querying current catalog or recalculating at current rules. Unknown/tampered profiles fail closed; old stored paid allocations remain the sole return basis. A stored pricing object is not an approval, authenticated receipt or database-integrity proof.

### 7.2 Documents and fiscal boundary

Show subtotal, discounts, net, tax, explicit service/delivery/packaging charges, final payable, tender/change, original operator/current collector and fulfillment. Distinguish unpaid check, paid receipt, return/refund, fiscal document and labeled reprint. Preserve branch identity, unique non-reused numbering, line/variant/modifier and adjustment links. Performed delivery/service fees are not automatically refunded on every partial item return; unperformed eligible charges use original treatment.

Fiscal submission is a separately durable, idempotent connector/job with pending/accepted/rejected/correction states. No false acceptance or new sale on retry. Applicable document/registration/classification/numbering/tax rounding and outage deadlines must be validated for the actual Egyptian legal entities before their launch (Q05). The hypothetical 14% examples select no universal statutory rate. Operational supply/incident recognition and accounting export must include supplied-but-unpaid goods under validated rules.

## 8. Inventory and reservation safety — F09

Use ledger movements and source allocation IDs, not direct balance resets. Sellable availability excludes reservations, expired/quarantined/blocked stock, supplier-owned rejected custody and unavailable kit ingredients. Services are genuinely non-stock, not an escape hatch. Count against a captured movement cut with subsequent movements reconciled or recounted. Transfer is dispatch → in-transit → receipt/shortage with authenticated source evidence and destination deduplication, not a two-branch distributed transaction.

**Add safety invalidation:** a collection reservation does not make expired/recalled/count-short stock consumable indefinitely. Model a fulfillment hold when reserved stock becomes unsafe/unavailable. Revalidate safety and allocation at finalization/send using an authoritative transition that races correctly with recall/expiry/count adjustments. Do not silently expire a paid/unknown reservation, erase money or resell rights.

If permitted, atomically replace with equivalent valid allocation without changing the protected commercial quote or falsely changing a serial/recipe. Otherwise preserve paid/unknown collection, original actor and refund/fulfillment obligation, and route an authorized customer resolution. Recall of already supplied goods is a claim/incident, not retroactive inventory recreation. Test retail lots and paid-unsent recipe ingredients, multiple reservations, count shortages and finalization/send races.

## 9. Returns, claims, exchanges and deposits

Same-selling-branch receipt lookup must work offline from retained local evidence/entitlement. Enforce scope in every write path, not merely a UI message. An authorized returns operator sees the narrow immutable receipt projection, not ownership of the original order.

Record customer receipt/delivery date, claim date, reason, quantity/serial, inspection, evidence, policy version, deadline and remedy. Preserve the researched Egypt CPA-informed baseline: applicable 14-day change-of-mind, 30-day defective-goods remedies, covered defect resolution within a week, category exceptions, original-method refund and continuing warranty/other rights. Validate actual classifications/legal rules at launch; do not treat all products identically or erase a claim because manager approval/payment is pending. Commercial policies may be more favorable, not silently shorter than applicable rights.

Accepting a return atomically reserves/consumes remaining quantity/value entitlement even while refund is due/unknown. Disposition is independent: inspected saleable restock, quarantine, waste or supplier return. Prepared/served food never becomes restored recipe ingredients. Cash refund posts to today's paying custody only when actually paid; external refund is an authorized attestation. Original available tender allocations remain the default; another method must be permitted and legally consistent with documented reason. Receiptless exceptions remain disabled until explicitly configured and never prove an unknown bank transaction or unused entitlement by themselves.

An exchange preserves separate original return and replacement sale, original allocations, replacement stock reservation and a restricted linked offset. Return `100` / new `130` → offset `100`, actual collection `30`; new `80` → offset `80`, actual refund `20`. No synthetic full cash flows or spendable balance. If replacement/payment fails, keep truthful accepted-return/refund-due/pending state. Optional packaging deposits have receipt-linked remaining liability/entitlement and actual refund, not a wallet.

## 10. Purchasing, settlement and valuation — F08

Preserve every workflow in [PUR][PUR], including requisitions, approved PO revisions, branch allocations, quick receiving, custody/inspection, receipt-only and invoice-before-arrival cases, partial/over/wrong/rejected goods, invoice tolerances, supplier advance/payment/credit/claim/replacement and cost reports.

- The receiving master owns physical stock/custody. Central procurement distributes identified branch rights; another branch's allowance cannot be reallocated before acknowledged release. Offline users act only within activated local delegation/preapproved allocations.
- One invoice has one designated posting/settlement authority. Multi-branch receipt allocations require originating-master confirmation; incomplete matching is visible. Local reference checks are transactional; arbitrary offline company-wide duplicates are detected/reconciled after sync, not falsely guaranteed absent.
- Receipt, invoice, settlement and supplier return are separate effects even in one screen. Inspection-pending quantities reserve PO fulfillment rights but are not accepted stock; rejection/replacement allowance cannot release two rights for one unit. Default over-receipt tolerance is zero unless explicitly bounded; extra goods still get non-sellable custody.
- Physical custody = accepted released + quarantined + rejected held. Refused-at-door goods are documented but not held stock. Preserve supplier-owned rejected goods' ownership/value separately.
- Invoice matching uses remaining billable receipt allocations, approved prices/taxes/tolerances and service-delivery evidence. Disputed amounts remain visible. Actual cash/bank settlement, advance allocation, overpayment and supplier credit are distinct; no invoice or credit moves stock again.
- Supplier return checks actual available source-linked units; dispatch, supplier claim, credit, money refund and replacement receipt/inspection are distinct. Used receipts are corrected, not deleted. Original sales/close snapshots are immutable.

**Preserved fixtures:** 24 delivered/22 accepted/2 rejected-held → custody `24`, sellable `22`. Invoice `1000 − advance 200 − new cash 500 = due 300`; credit `100` → due `200`; today's cash outflow remains `500`. Freight `30` over eligible goods `100 + 100` and unrelated service `100` allocates `15 + 15 + 0`, with one charge source/obligation.

### 10.1 Deterministic moving-average correction policy

Moving weighted average is per SKU/warehouse; FEFO picks physical lots and is not FIFO valuation. Store original issue/sale/WIP value at each posting. A supplier credit can differ from the inventory departure's book cost; expose a purchase-cost variance rather than force them equal.

**Preserved fixture:** buy `10 @ 20 + 10 @ 30` → quantity `20`, value `500`, average `25`; sell `10` at captured cost `250`; return the remaining purchased units to supplier with claim `300` → inventory departure `250`, difference `50`, remaining value `0`. Do not rewrite the original sale cost.

**Independently accepted bounded direction; broader lineage/integration proof still required:** calculate late-cost deltas from **recorded value lineage**, using deterministic reference valuation replay (or a proven equivalent contribution ledger). The single-pool90/30 fixture below has been independently challenged; it does not qualify the complete propagation engine. This is calculation over immutable history, **not replay of business commands**, repricing sales or rewriting original postings. Carry a correction's value contribution through moving-average pools, issues, WIP/finished output, transfers, returns and consumption; post present linked deltas to remaining inventory value and consumed-cost variance, quantities unchanged. Do not allocate solely by remaining physical receipt/FEFO units.

**New fixture:** buy `10 @ 20 + 10 @ 30`, sell `5 @ 25` → held `15`, value `375`, original issue `125`. Late freight `120` on the first receipt gives reference pool `620 / 20 = 31`: held reference value `465` (**+90**) and consumed-cost correction **+30**. Physical-receipt residue could suggest `60/60`; that is a different policy, not the selected moving-average value-lineage result. Show original margin and linked later correction distinctly.

Calculate against a fixed ledger cut outside long write transactions; recheck revisions/source hash and post one idempotent correction atomically. Persist eligible source, basis, version, lineage, per-destination deltas and remainder. Cross-branch transferred value changes are authenticated pending adjustments applied by the destination master, never central overwrites or a false all-branches transaction. Preserve supplier obligation once. Negative corrections/zero quantity must not fabricate negative value; unresolved inconsistencies require review. T22 must prove repeated/cancelled corrections, WIP/yield, returns, offline destinations and quantity/value conservation before claiming this engine complete.

## 11. Synchronization, recovery copies and retention — F01, F11

### 11.1 Independent asynchronous streams

Journal cursor is `{branchId, authorityEpoch, sequence}` with stable transaction/child IDs, original actor and immutable versioned effects/result/content hashes. Same-master restart continues its epoch; replacement records a parent checkpoint/authority edge. Consumers replay **recorded effects**, never checkout, availability tests, bank payment or physical printing.

Maintain separate positions for local durable commit, optional standby durable copy, central immutable accepted evidence, central report projection and complete recovery coverage. ACK only contiguous durably stored ranges with chain/gap information. Quarantined evidence may be stored yet excluded from accepted reports/recovery; report both states and retain necessary source copies. Same identity/content is an idempotent ACK; changed content is a durable conflict. Historical authorized late-epoch evidence is distinguished from a retired writer's forbidden new effects and from recovered effects already represented by reconciliation.

Central ingestion authenticates the branch service and validates origin/scope/relations. Use a tested Mongo replica-set transaction for multi-document evidence/result/checkpoint/outbox writes, or an explicitly reviewed equivalent atomic commit design. Generic sequential cascades are not that design. Derived-report/cache failure retries from durable work, never repeats physical operations.

Admin download is independent of branch upload/standby. Persist inbox IDs, validate origin/dependencies/schema/scope/revisions, stage a complete release, activate atomically and ACK applied/rejected state. Interrupted download retains last valid configuration. A local emergency disable is not undone by stale central data; a newer explicit authorized resolution is necessary. Company price publication is not magic activation at an offline branch.

Read projections use consistent authorized snapshot plus cursor, bounded pull pages, atomic page/cursor application and tombstones/invalidation. Notifications are hints. Session/role change invalidates private cursors and caches; expired cursors require a fresh snapshot, not a silently incomplete delta stream.

### 11.2 Complete, protected recovery

A coherent copy includes all BLUE §8 state: effective config/catalog/recipes/prices, local profiles and protected credential recovery, grants/authority, open orders/handoffs, locks/attestations, stock/reservations/kitchen/WIP/waste/cost, receipts/returns/deposits/exchanges, cash/counts/safe/courier custody, incidents, purchasing/inspection/invoice/settlement/claims, operation results, numbering, audit, inbox/outbox and uncertain side-effect jobs. A sales export is not a recovery package.

Use consistent Room/SQLite backup including WAL/cut, authenticated encryption, bounded resumable chunks/hashes, compatible schema and explicit recovery authorization. New target has its own device key and new sessions; never clone the former private identity or revive bearer tokens. Protected recovery records survive human logout without exposure to the next user.

Single-device branches remain valid without standby, with prepared recovery authorization and qualified central recovery route. A copy on the same disk is not independent. Show actual age/gaps/RPO; no usable reachable copy means no guaranteed immediate lossless replacement. Healthy master commits continue despite replication lag until an actual durable-storage failure occurs.

### 11.3 Retention/capacity contract

Complete this matrix in T02/T25/T32 using legal windows and measured P0/P1/P2 storage, not arbitrary TTLs:

| Data | Retention and safe-compaction condition |
| --- | --- |
| Operation identity/digest/result and authority history | Preserve retry safety across epochs, restore and archival. An evicted result requires protected archive lookup or reconciliation, never a retry-safe `NOT_RECORDED` caused merely by expiry. Retain the required identity/tombstone/cut evidence. |
| Unacknowledged journal/outbox and financial evidence | Do not drop on age/retry count. Compact only after verified relevant durable consumer coverage or a verified replacement snapshot preserving complete required recovery state. |
| Receipts, serials, allocations, return/deposit rights and claims | Longest applicable return/warranty/financial/legal requirement; unresolved claims and promised offline remedies remain accessible locally or in a protected local archive. |
| Snapshots, key envelopes and migration fixtures | Preserve coherent independently usable generations, compatible restore path and authorized key recovery; test restore before retiring a sole usable generation. |
| Private caches, sessions and attachments | Minimal permitted lifetime and scoped cleanup; clear private views at logout/revocation but not unresolved financial evidence. Expiring authentication material is not permanent authority history. |

Specify byte/event budgets, measured daily growth/offline horizon, archive lookup latency, disk/WAL/temporary-copy headroom, warning thresholds and runbooks before release. Compaction must pass duplicate-after-archive, old-backup, interrupted compaction and legal-hold tests. Do not retain secrets forever merely to preserve deduplication, and do not delete necessary deduplication merely to reduce storage.

## 12. Security, UI, platforms and operating controls — F04, F12

### 12.1 Security and reuse exclusions

Provide separate central-session, local-employee-session, master-service identity/key and durable pending-operation stores. The inspected Viki gateway's HTTP login/current-identity behavior cannot be the branch's offline authority. `DefaultAuthRepository` restore/revocation behavior and `SessionManager` clear-on-read/decode-error are not approved for persistent master/financial material.

The current JVM non-Windows session storage keeps an AES key beside the session and tolerates permission-setting failures. Do not import it as the protected master/recovery mechanism. Qualify OS key facilities in the actual supervised service context (not only interactive desktop login), protected field/database mechanism and an authorized encrypted key-recovery path. Room/bundled SQLite alone does not encrypt the ledger. Token-cache loss can require re-login; key/evidence failure must preserve data and enter recoverable failure, not erase it.

Local logout must immediately hide private routes/views and deny that interactive context even when remote revocation is unavailable; separately handle pending revocation/financial resolution. Master runtime remains independent. Use reviewed password hashing/calibration, salts and persistent throttling; scope transport tokens and current-policy references. TLS origin/redirect/image clients never leak admin tokens to untrusted media endpoints. Bound input, nesting, queues, concurrency, archives and decompression. Redact logs, app-switcher previews, notifications, crash reports and support exports; audit sensitive actions without passwords/tokens.

#### Bounded protected-storage sequence — T08/F04

Start with four inert protected tables for account/credential/existing session state, activated effective/session policy, device admission and paired account/device throttle buckets. The selected internal profile is AES-256-GCM with12-byte fresh nonce/16-byte tag, canonical AAD binding format/algorithm/configured key ID/table/schema/full scope/key/dimension/revision and strict canonical payloads. A stored key ID cannot select arbitrary keys. Preflight actual SQL types and encoded byte bounds before ciphertext hydration; then verify the tag and exact expected payload identity. Missing is distinct from corrupt/locked storage, but none grants an empty identity, a fresh throttle bucket or implicit bootstrap. Keep production protection unavailable until actual service-context keys, nonce/rotation/recovery and rollback policy are qualified; synthetic AEAD test keys are not that evidence.

Reuse the existing session/throttle reducers and transaction interfaces. An internal attempt-bound Room adapter captures the exact database instance, scope/actor/device/attempt and account/session/credential/admission/policy incarnations, then rechecks them in the same IMMEDIATE transaction before the existing throttle block. Restrict exactly the two relevant bucket keys; use activated policy and retain legitimately unequal counters/failures. Stale successful verification cannot clear a newer incarnation's failed attempts. Serialize and consume each captured reserve/completion capability once, including uncertain commit/cancellation, and recheck original attempt-start continuity inside completion; attempt IDs and even monotonic timestamps can be reused. No reusable generic authorization proof follows from throttle completion.

Keep separate plaintext caps of account256KiB/policy1MiB/admission16KiB/throttle4KiB, not a financial-kernel budget expansion. Enforce counts and actual bytes without truncating256-session history or complete grants. Preserve exact-branch and same-company grants without retagging, credentialRevision0 and null credentials retaining revision. An exhausted generation may terminalize account revision4→MAX: require both countersMAX, disabled/all revoked and the complete immutable session history. Do not impose a universal+1 rule that prevents lockdown or accept stale/same-revision reactivation. Guard SQL identity/revision/no-replace/no-delete behavior, transaction lifetime, cancellation and ignored failures/CAS conflicts; return only after outer COMMIT.

Use a master-specific builder and a genuine separately generated seven-entity legacy-v1 fixture for the additive v2 migration, not a down-versioned v2 or the unrelated marker-v2 qualification DB. Pinned Room3 migrates/restamps identity before its ordinary identity check: explicitly verify user_version1, the single id42 original identity using exact bytes immune to stored collation, and every old guard body before protected DDL. Unknown identity/extra table/missing guard refuses without repair. Keep both schema exports, test every old cell unchanged, healthy versus zero-CREATE controls, injected rollback/reopen and no-effect replay. This stage creates no accepting principal, default administrator, provisioning/reset endpoint or token/login gateway. Authentic historical ciphertext restored with old revisions can still pass; AEAD is neither disk rollback resistance nor whole-ledger encryption.

Later sign-in rechecks original boot/deadline and captured incarnations; durable throttle completion alone is not atomic session issuance. Tokens are opaque hashed secrets, one immutable issuance per session/generation in the later initial profile; re-admission cannot restamp original credential/admission revision. Lost login replies require fresh password verification and explicit move, not plaintext bearer retrieval. Preserve the original required refresh/rotation/recovery lifecycle as a separate later implementation and qualification gate, not silently removed scope. Current command authorization must borrow the same live Room transaction.

The inert stage is now implemented and independently source-reviewed, with a737-case target and a separate1,632-case full Linux host run at22:55:05Z. Pricing independently audited both exact archives without discrepancy; see the implementation evidence report. These results include genuine disposable v1→v2 migrations and synthetic JCA keys, not live migration approval, accepting login, protected service keys or any original release-gate waiver. Subsequent A1 and A2 sources have their own separately reviewed target/full executions in §4.3; the storage archive alone never qualified later edits.

#### Immutable access issuance and current writer authorization — D053 / F32–F34

The subsequent bounded T08 increment combines the missing immutable access facts
with the actual writer-bound reader; do not ship a bridge that treats ACTIVE
session state alone as bearer validity. Keep the required later login/refresh
lifecycle, service providers and platform qualification separate and visible.

1. **Inert issuance:** one INTERACTIVE-access record per `(scope, sessionId)`,
   protected revision0,4KiB plaintext cap, no overwrite/delete/extension/session
   reuse. Bind actor, device, individual session generation1..MAX-1, original
   credential/admission revisions (including0), separate issuerContextId, boot and
   issued monotonic moment. Require explicit positive representable duration and
   exclusive deadline. No default TTL, duplicate policy/status or read-time backfill.
2. **Token prerequisite, not token verification:** canonical lowercase64-hex
   SHA-256 over UTF-8(`ukkera.local-access-token/sha256/v1`) || 0x00 || exactly32
   secret bytes. Hash parsing/equality proves neither entropy nor authentication.
   Future issuance must use a qualified CSPRNG. This D053 hash profile alone
   defines no transport locator and accepts no bearer; D055 below adds the
   separately scoped existing-access parser and fixed-size digest comparison.
3. **Current facts:** compare the already-trusted principal to the protected
   account, retained individual session, original credential/admission bindings,
   current policy and current service/time context. Another legitimately allocated
   account generation must not invalidate an unchanged concurrent session. Disabled,
   revoked, terminal, missing, expired or mismatching facts deny; old sessions with
   no issuance are not grandfathered. Current policy is read live, not copied into
   issuance. An old/expired issuance must not disable unrelated healthy sessions.
4. **Same-writer capability:** use one internal concrete reader bound to the exact
   live Room database/writer/view, source and original principal object. A public,
   wrapped, fabricated or expired business view provides no protected authority.
   Complete authenticated account/policy/admission/issuance fingerprints remain
   private; no password, DAO or mutation capability escapes. Caught protected
   failures remain sticky. Ordinary business-read behavior is unchanged.
5. **Finalization:** every normal execute/lookup return, including positive replay,
   conflict, early refusal, stored rejection and supervisor audit, rechecks current
   protected dependencies. Finish suspendable reads, connection/key-health queries
   and any business wall-clock capture before the last synchronous monotonic sample.
   Afterwards permit only bounded captured-fact/state/cancellation comparisons.
   A final denial after staged effects throws out of the transaction and is mapped
   outside it; finally only invalidates lifetime. Cancellation/original failures
   are not masked. Pure result construction is allowed inside; publication follows
   outer COMMIT. This deadline cut does not promise fsync/delivery before expiry.
6. **Source continuity:** one source belongs to one supervised service activation.
   Record normally returned trusted-current samples or discontinuity latches before
   propagating cancellation, even when SQL rolls back. Thrown cancellation without
   a returned sample invents no clock fact. Actual elapsed regression, current boot/
   issuer-context change or unavailable continuity permanently disables that source.
   A fresh, non-reused context is required after service activation/recovery/restart;
   reconstructing a source under the old context is not continuity/rollback proof.
7. **Failure contract:** missing/ineligible facts or unavailable continuity give
   sanitized AUTHORIZATION_DENIED after rollback. Malformed/AEAD/read/SQL/foreign-
   owner/lifetime failures remain STORAGE_UNCERTAIN even if policy code catches them.
   Neither condition initializes rows or authorizes collecting money.
8. **Genuine development migrations:** schema3 adds only the fifth table; preserve
   exports1/2 and old guard bodies. Register direct1→3 and strict2→3 routes because
   Room restamps metadata only after the whole path. The former invokes unchanged
   gated1→2 then fifth DDL, not strict2→3 under still-v1 metadata. Use separate KSP-
   generated v1/v2 fixtures, exact original identity/guard checks before DDL and
   all-cell/rollback/reopen controls. No live migration is authorized.
9. **Replacement guard correction:** the fifth immutable table rejects logical-key
   **or global implicit-rowid** INSERT conflicts, all aliases/negative values included.
   Raw existing rowid=-1 may conservatively block omitted-rowid insertion; do not
   exempt it or renumber evidence. Original11-table INSERT/UPDATE replacement gaps
   required the separately reviewed D054 additive hardening in §4.1; preserve historical DDL.
   Normal DAOs use ABORT/checked writes. Do not claim bearer forgery, defense against
   arbitrary DDL/file control or every possible replacement path already covered.

Required tests include original-revision0 and concurrent-generation positives,
every current dependency/issuance mismatch, no-grandfather controls, every staged
rollback cut across all12 tables, replay/conflict/audit expiry, exact cancellation,
source/view/connection confinement, rollback-spanning high-water, final callback
ordering, actual pre/post SQLite COMMIT failures and genuine migration provenance.
Security independently found two source bugs and lead reproduced them in three
failing regressions before their corrected pass. Generated-schema/source review,
focused execution, full invocation and independent evidence audit remain distinct;
see the dated implementation report rather than treating method counts as success.

The D053 increment supplied no password sign-in/provisioning/reset, PrincipalVerifier,
refresh/rotation/lost-refresh-reply recovery, production AEAD/clock/service adapter,
transport-bound disclosure or accepting application gateway. Those are required
engineering work, not owner questions, optional features or completed T08 exits.
The bounded D055 verifier below supplies only its explicitly described part.

#### Existing INTERACTIVE access verification — D055 / F32, F35–F36

Three substantive lead/security review rounds selected **existing-issuance
verification before password sign-in**, not instead of it. Existing throttle
completion owns its own transaction; it cannot yet atomically commit successful
attempt completion, session transition and issuance. Credential/login resolution,
activated lifetime and original refresh/reset/recovery requirements remain later
required work. Do not connect an accepting application merely because this
internal verifier and the real principal factory now exist.

1. **Strict wire:** exactly52 bytes: ASCII `ULA1` at0–3, session UUID in canonical
   network byte order at4–19, secret32 bytes at20–51. No JSON/text/Base64 fallback,
   truncation, padding or trailing bytes. Scope is service configuration; actor
   and device derive from protected issuance and authenticated physical evidence,
   never unverified claims. Keep the established D053 hash domain unchanged.
2. **Owned comparison:** compare distinct owned32-byte digests using
   `MessageDigest.isEqual` on JVM/Android and pinned `timingsafe_bcmp` on Apple.
   Reject unsupported sizes/aliasing before native access; clear owned buffers
   best-effort. Neither total memory erasure nor end-to-end constant response
   time is promised. Native source/klib availability is not runtime qualification.
3. **Protected facts before identity:** read issuance first, derive the actor,
   then authenticated account/credential-presence/revision, current policy and
   device admission in the same protected transaction. Preserve the existing
   account/policy/admission/issuance fingerprint ordering and narrow projection;
   no password object escapes. Use a pure candidate-identity eligibility overload,
   not a manufactured trusted principal to validate itself.
4. **Physical binding:** capture one genuinely authenticated physical lifetime.
   Every non-null protected admission key identifier/fingerprint must match
   evidence from that SAME key, conjunctively. A device ID, descriptor string,
   capability list or the newest connection reusing an ID is not authentication.
   After actual inner COMMIT, synchronously check the captured lifetime itself.
5. **Publication and continuity:** hold an outer bare writer across the existing
   root protected transaction's COMMIT and final peer/current-time/eligibility
   checks. Publish a candidate only after the entire outer loan and cleanup return
   normally. The verifier and command kernel MUST share the same D053 continuity
   source instance and exact store owner. Constructor store identity alone cannot
   prove separate kernel wiring. Account normally returned time evidence before
   cancellation. Real commands/results separately reauthorize current grants.
6. **Supported entry, not an enforced guarantee:** call from an ordinary
   structured request coroutine outside ALL Room loans/transactions, with no
   inherited/shared/retained/re-entered borrowed context. Authenticate first,
   then call the kernel separately. Pinned Room reuses bare loans; the SQL
   transaction guard does not reject every inherited loan. Tests demonstrate
   shared wrapper reuse, not an experimentally reproduced rollback escape.
   Accepting composition must establish context lineage and non-re-entry, or
   supply a genuinely clean, bounded execution design with cancellation/shutdown
   and caller-held-loan deadlock handling. Dispatcher/Job changes, caller flags,
   private Room-key inspection or removing the outer loan are not accepted fixes.
7. **Bounded admission and failure:** require explicit positive service-lifetime
   capacity; nonblocking admission precedes credential copies, providers and
   Room. Release exactly once on every exit. Saturation, storage/hash/ordinary
   peer failures give a fixed sanitized unavailable result without private causes.
   Credential/ineligibility and unavailable/latched continuity give generic null
   refusal; never classify every null as a wrong password or advance password
   throttle for infrastructure failure. Preserve cancellation. This is not a
   timeout, fairness, hung-provider or financial recording-capacity guarantee.
8. **Limits that remain required:** peer/time checks are separate observations,
   not simultaneous freshness or transport disclosure fencing. Revocation may
   commit after loan release but before the caller receives the principal.
   Another pool/process is not fenced. Injected outer recycle failure can retain
   Room's writer and make cooperative close spin (F36); do not confuse limiter
   recovery with pool recovery. Qualify bounded service retirement/restart before
   accepting composition; do not open a competing pool as an implicit repair.

Required regressions use the actual verifier → TrustedPrincipalFactory → separate
kernel, real SQLite/JCA AEAD, literal UUID/hash vectors and original immutable
issuance. Cover every missing/corrupt dependency and descriptor, grant removal/
restoration without bearer changes, device move/re-admission, revoked retained
results, expiry/context/high-water, two independent unborrowed callers, both
revocation orders, capacity>1 with a second held wave, cancellation, real COMMIT
and cleanup faults. Faults that poison Room close run in deadline-bounded child
JVMs: exact post-assertion witnesses/exit0 and parent all12-table reopen equality
are mandatory; timeout/kill is never success or graceful-shutdown evidence.

The corrected focus completed08:14:25Z with64 actual host cases and four executed
security/database Apple main klibs, after two preserved failed attempts and
independent test-oracle corrections. Pricing independently accepted that focus,
then separately accepted the08:35:31Z full combined run:2,411 cases/22 profiles/
186 XMLs,53 harness tests and8,245 unchanged inputs. The full identities are the
disjoint D0542347+D05564 union. Full Apple MAIN targets were10 executed/12
UP-TO-DATE; the security/database pairs were UP-TO-DATE after focus, not freshly
recompiled in the full phase. No linking/runtime qualification follows. Exact
archive/commands/audits and both failed packets remain in the verification report.
No schema, issuance, refresh, provisioning or accepting UI composition changes
belong to D055. F35/F36 remain accepting-composition gates.

### 12.2 Navigation and accessibility

Implement the existing Nav3 typed minimal routes, central navigator/registry, independent area stacks, entry-scoped ViewModels and explicit Back/forward-leave policy. Restored routes await authenticated scope/resource validation. Koin owns application/master/session/entry lifetimes; no global screen ViewModels, duplicate navigation engine or hidden teardown of the master. Coil caches are bounded and correctly tenant/session-scoped; missing media never blocks checkout.

Add a measurable engineering baseline to Q-UX-03: text contrast at least `4.5:1` for normal text and `3:1` for large text; essential controls/focus indicators at least `3:1`; common touch actions at least `48 × 48 dp` on Android/common layouts and `44 × 44 pt` on iOS; qualify enlarged text up to `200%` and selected platform accessibility sizes without hiding totals/actions. Apply native logical units and test actual rendering, not device-pixel comparisons. Keyboard-only operation, stable scanner focus, visible focus, labels, announcements and non-color-only status are required. Record and repair exceptions rather than silently waiving them.

Qualify Arabic/English parity, plurals/placeholders, RTL/back gestures, bidi numbers/barcodes/serials, decimal input, receipts/QR/layouts and runtime language change preserving the sale. Use actual TalkBack, VoiceOver (iPhone/iPad/macOS) and selected Windows assistive technology such as Narrator on the qualified profiles. These are engineering targets, not a claim of legal accessibility certification or every AT implementation's support.

### 12.3 Platform and peripheral contract

- **Windows/macOS:** supervised master separate from cashier window; packaged native libraries, service-context keys, exclusive writer, restart, upgrades, firewall, signing/notarization where applicable.
- **Android:** qualified foreground service/notification/permissions, screen-idle behavior, battery/OEM restrictions, manual lock/force-stop/reopen and LAN reconnection. Do not promise the OS will never stop hosting.
- **iPhone/iPad:** active-app hosting, explicit limitations and idle-prevention notice; manual lock/background/suspension/termination truthfully makes the master unavailable. Same-device reopen may restore its valid role, never undo intentional stop/retirement. Physical tablet multitasking/rotation and phone results are distinct.
- **Printers/KDS:** selected LAN/USB/Bluetooth profiles, Arabic fonts/bidi/raster as required, paper-out/cutter/disconnect and uncertain physical output. Durable job state is not proof paper printed; authorized reprints are labeled and do not trigger a second sale/preparation.
- **Drawers/scanners/scales:** actual assigned drawer and permissions; hardware open is not a cash movement. Qualify HID/camera/SDK barcode routes, suffix/repeat/focus, leading zeros and malformed/weighted formats. Scale protocol, tare/zero/stability/unit/precision and permitted manual fallback are explicit; do not claim legal calibration from a software test.

General platform support does not promise every peripheral/OS pair. Setup must explain qualified and unsupported adapter combinations without dropping the accepted general app platform.

### 12.4 Observability, migrations, deployment and rollback

Expose branch/master/lifecycle/epoch, effective config, local commit, separate copy/central/projection checkpoints and gap/age, pending unknown payments/refunds, job age/failures, unacknowledged custody, history/resource holds, storage/WAL pressure and migration/recovery state. Use safe operation/branch correlation IDs; company totals disclose freshness. Alerts have permissioned recipients, deduplication, acknowledgement and resolution; acknowledging never settles money. Logs/metrics are bounded and local-safe; remote delivery waits for connectivity without blocking sales.

Preserve schemas and synthetic initial migration fixtures, then every shipped version. Verify fresh versus migrated equivalence, exact values/relations/entitlements/IDs, failure injection and old-backup migration. No destructive fallback. Reject unsupported schema/binary downgrades. Quiesce/drain when required, capture a coherent backup, respect mixed-version client contracts and revalidate role before writes.

**Rollback already exists in the handoff; strengthen its runbook:** after post-upgrade commits, restoring a pre-upgrade snapshot is not a routine rollback because it discards financial work. Prefer a qualified compatible binary rollback or forward repair; an unavoidable older-data restore enters §5 missing-history reconciliation with explicit loss/cut disclosure. Never label an old snapshot restore lossless.

Future delivery sequence: actual isolated local generator → compiled registered Go runtime → disposable local auth/app/Mongo replica-set integration → real local endpoints and branch/CMP integration → release-like native candidates → authorized staging/fiscal sandbox → physical/operational pilot → controlled production switch. Record environment/base URL separately from authority logic; central switching cannot become cloud checkout. Candidate provenance, signatures/SBOM/dependency and secret checks, backups/restore, support/runbooks and qualified rollback precede rollout. No production credentials, terminal charges or migrations are authorized by this document-review task.

## 13. Phases, dependencies and measurable package exits — F10

Retain M0–M5 and all 40 original package completion dependencies. The graph is acyclic; early child work is already allowed in TASK lines 11, 17 and 62–64. Make the risky children named and scheduled rather than defer all resilience to late T25/T26/T32.

| Early milestone | Timing and concrete proof; not completion of its parent package |
| --- | --- |
| E0 — source/adoption/topology plan | T00/T01: exact revisions, safe reuse exclusions, generator discovery, network/device/credential gaps and supported candidate profiles. |
| E1 — native compatibility/package probe | T01 with early T34/peripheral inputs: real storage/RPC/crypto/IPC and minimal packaged launcher on available targets; physical gaps explicit. No full T34 or platform-pass claim yet. |
| E2 — recovery/rounding/value contract review | Within T02 before freeze: F01 outcome/write-admission model, F02 serialized custody/fencing, F07 calculation profile and F08 valuation oracle fixtures. Design/protocol proof here does not depend on completing T25/T26. |
| E3 — primitive recovery integration | Early T25/T26 children after integrated T05/T08/T09: one real atomic operation, lost reply, coherent restore, stale-tail quarantine, target-bound activation and competing-target rejection. Gate M1 integration; extend coverage with each later domain. Full T25/T26 still wait for their complete dependencies. |
| E4 — resumable setup/security-root skeleton | Early T29 child alongside T10/T08: scoped wizard state, training/live isolation, safe interrupted steps and master-versus-cashier lifecycle. Full domain setup waits for T29's listed dependencies. |
| E5 — ongoing package and upgrade probes | T34/T32 children as foundations/features arrive: actual packaged native linking and supported schema fixtures, not first attempted only before pilot. Final security/package exits remain unchanged. |

| Milestone | Completion evidence |
| --- | --- |
| M0 — foundations | T00–T05 applicable foundations/CI, exact contracts, compatible graph, isolated generator discovery/probe and E0–E2 evidence; unavailable backend/device facts clearly separate. |
| M1 — real offline retail slice | T17 path with local login/count/stock/quote/cash and external attestation/receipt, second client, user switch, lost-response recovery and E3. WAN/standby absent; real storage/transport. Not full activity release. |
| M2 — central/native administration | Actual protected local backend/new YAML, company CMP flows, typed ingestion and configuration activation. Tenant/direct-ID/bulk negative tests, no mocked success. |
| M3 — full business workflows | All four activities plus cash, returns, inventory, purchasing, exceptions, reports and full administration/setup; package tests and cross-domain conservation, no reduced release. |
| M4 — resilience/operations | Complete T25/T26/T30/T31/T32 integration: all-state recovery and fencing, optional standby, qualified peripherals/applicable fiscal connector, retention/observability and upgrades. |
| M5 — candidate and pilot | Exact T34 candidates tested by T33, then T35 pilots; all required traceability and actual platform/external evidence, no unresolved critical/high correctness/security defect or undisclosed qualification gap. |

Milestones coordinate integration; they are not an alternative serial DAG. Early contracts/fixtures can proceed on frozen interfaces, but a partially proven scenario remains incomplete. Physical/fiscal/source access gates block the affected claims, not every independent safe work package. No calendar duration or staffing throughput has been proven by this review.

### Original 40-package completion map (preserved)

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

The original detailed task descriptions and exits in [TASK][TASK] remain normative. T28 is five packages, not one missing task. The historical T01–T13 engineering list in DEC §4 is a different list, not renumbering of this catalog.

For every authorized implementation increment, main records task/child, scope/files, dependency revisions, frozen interfaces, test/fixture/quality obligations, independent reviewer and integration owner. Use the original task states and bounded work/review queues. Shared build/schema/route/CI registries have one owner. A package is not complete without substantive self-tests, independent review, documented migrations/contracts and verification on the combined tree; nobody approves their own patch. Missing independent review is a reported gap, not simulated consensus.

## 14. Acceptance, qualification and release verdict

### 14.1 Required full coverage retained, not yet qualified

| Business family | Original IDs | Count |
| --- | --- | ---: |
| Cash/custody | CASH-01–CASH-12 | 12 |
| Identity/ownership | OWN-01–OWN-11 | 11 |
| Kitchen | KIT-01–KIT-15 | 15 |
| Stock | STK-01–STK-09 | 9 |
| Payment/fulfillment | PAY-01–PAY-14 | 14 |
| Returns/exchanges | RET-01–RET-13 | 13 |
| Tax/report/scope | TAX-01–TAX-09 | 9 |
| Reliability | REL-01–REL-10 | 10 |
| Configuration/permissions | CFG-01–CFG-08 | 8 |
| Pricing/promotions | PRC-01–PRC-06 | 6 |
| Catalog | CAT-01–CAT-10 | 10 |
| Purchasing | PUR-01–PUR-16 | 16 |
| Setup/daily UX | UX-01–UX-08 | 8 |
| **Total** | Exact triggers/results remain in ACCEPT/TRACE | **141** |

Retain **Q-DB-01–10, Q-BE-01–10, Q-SEC-01–10, Q-RES-01–14, Q-NAV-01–12, Q-DI-01–02, Q-IMG-01–03, Q-UX-01–10 and Q-PERF-01–08**: **79** distinct original gates. All original CSV execution statuses are `NOT RUN`, and implementation/test/revision/evidence fields are unfilled. This review does not change them.

Add the following review-derived test groups without replacing or renumbering any original ID. These are **acceptance groups, not wholesale passed by bounded implementation unit tests**:

| ID | Required additional proof | Original anchors |
| --- | --- | --- |
| RV-01 | Stale snapshot after sale/refund/PO/payment; present original result recovered; absent result returns history hold; new IDs cannot spend resurrected rights; resource readmission; late-tail/count reconciliation does not double effects | REL, PAY, RET, PUR; Q-SEC-09, Q-RES-08/09/14 |
| RV-02 | Two disconnected delegated approvers/targets, copied issuer state, lost activation reply, abandoned first target and old writer return; prove only one safely appointed writer or no activation | Q-RES-05/06/07/10 |
| RV-03 | Strict topology with competing bridge/VPN/NIC, address change, manual pinned endpoint, same-device guarded IPC and offline runtime startup | Q-SEC-01/02, Q-DB-05 |
| RV-04 | Offline restore/logout, key unavailable/corrupt store/permissions failure, user switch with pending payment, service startup without human login, authorized non-cloning recovery | OWN, CFG; Q-SEC-03/04, Q-DB-10 |
| RV-05 | Every discovered generic/model/relationship/bulk/GraphQL/sync/import/export route denies cross-scope or immutable-ledger bypass; actual central atomic evidence ingest and ACK failures | Q-BE-01–10 |
| RV-06 | Separate Android launcher and pinned reusable libraries compile/package across all required families; no BranchPos/app-identity schema confusion or accidental Queue-wide refactor | T00/T01/T34; Q-BE-10 |
| RV-07 | Advertised fixed gross discount including taxable service, mixed rates/inclusion, minor-unit caps/ties, partial refunds and legal posting profiles | TAX, PRC, RET; QA §6 |
| RV-08 | Moving-average late-cost 90/30 fixture; physical FEFO does not choose value split; transfer/WIP/output/return/cost-decrease/repeated-adjustment conservation and immutable historical margin | PUR, STK, KIT; QA §6 |
| RV-09 | Reservation expires/recalls/count-short after partial/unknown payment; hold/substitution/refund path, no unsafe consumption or silent monetary release | STK, KIT, PAY, CAT; Q-RES-01 |
| RV-10 | Early native/restore/setup children integrated before broad milestone; every added domain extends all-state recovery; no partial child marked full task/scenario pass | T01/T17/T25/T26/T29/T34 |
| RV-11 | Compaction/archive/old-backup/unknown-tail result lookup, legal holds, failed snapshot replacement and disk exhaustion preserve evidence/retry safety | Q-DB-06/08/09; Q-RES-08/09/11 |
| RV-12 | Numerical accessibility baseline plus actual AT, Arabic bidi receipt/input, large text, scanner/modal approval/keyboard and protected unknown-outcome recovery | Q-UX-01–10 |
| RV-13 | Request/session-bound original/retained/status/error/queued RPC disclosure and malicious same-device replay; UI fence alone must not pass | OWN; Q-SEC-03/04/09 |
| RV-14 | Excluded-cent and non-monotone discount feasibility; explicit fiscal coupling rejection; validated immutable snapshot/partial-return reconstruction | TAX, PRC, RET |
| RV-15 | Actual file-backed TRUNCATE/FULL create/migrate/reopen, immutable REPLACE rejection, commit/driver/disk/process-loss boundaries | Q-DB-01–10 |

For every business command, test normal/negative/boundary, actor/scope, stale revision/approval, duplicate/different-payload, concurrency, pre/post-commit failure, lost response, same-master restart, approved replacement and side-effect recovery at the required real boundary. Independent money/quantity/value oracles and conservation properties complement examples. Mock-only integration, a skipped physical test, a backup “success” badge or document lint cannot satisfy runtime acceptance.

### 14.2 Performance and physical matrix

Preserve all **25 ordered master/client platform pairs** (Windows, macOS, Android, iPhone, iPad) for the core local login → sale → return → close path, plus lifecycle/peripheral/security evidence specific to each family. Include same-device operation and actual iPhone/iPad hardware, not simulators alone.

| Profile | Dataset/workload retained |
| --- | --- |
| P0 | One master/local UI, 5,000 SKUs, 500 sales/day, 100 open orders, no standby |
| P1 | Master + 10 clients, 25,000 SKUs, 5,000 sales/day, 500 open orders, 500,000 retained events |
| P2 | Master + 25 clients, 100,000 SKUs, 20,000 sales/day, 2,000 open orders, 1,000,000 events, 250,000 queued sync events |

Initial peak: five sale/order commits/s plus ten mixed read/stock/kitchen operations/s. Healthy qualified LAN durable command response p95 ≤500 ms/p99 ≤1 s; lookup p95 ≤150 ms, input/navigation p95 ≤100 ms. Measure release-like physical builds with durable settings on, exact hardware/dataset and raw results. Establish platform memory/frame/storage budgets early. Backlog catch-up ≥2× measured incoming rate with bounded memory. Same-master ready ≤60 s at P1 after process launch; prepared planned transfer ≤15 min at P1 without safety shortcuts. Execute 24 h offline N2 and 72 h mixed connected/reconnecting soak at P1.

These remain **provisional engineering targets, not measured capacity** or permission to drop a required platform. Measure emergency RTO and copy-specific RPO separately. Actual hardware/load/peripherals and launch credentials are external gates, not implicit test passes.

### 14.3 Completion and current readiness

A future release needs exact tested commits/artifact hashes, per-scenario/gate evidence, independent reviews and combined-tree results, verified full-state recovery/fencing, security negatives, completed native administration and all four activities, actual physical/fiscal/peripheral qualification, rehearsed backup/upgrade/rollback and pilot resolution. Every missing gate has an owner and explicit status; no placeholder endpoints or fabricated passes.

**Latest bounded evidence:** D055 completed24October08:35:31Z with2,411 passing host cases/22 profiles/186 XMLs and53 harness tests, independently audited with8,245 unchanged inputs. Every2,347 D054 identity and64 focused D055 identities remain as an exact disjoint union. Android debug packaging/assembly and SOFTWARE smoke executed; desktop compile was UP-TO-DATE; Apple MAIN targets10 executed/12 UP-TO-DATE, not linking/runtime. Fresh backend9 Node summaries/139 Go pass records are invocation-bound, without complete raw test stdout. Pricing accepted the exact208-file archive and full live input/Git/provenance checks; packet SHA256 `4fea9cf8c8c60d71e1f92b08f5af240d4f0cb8eda8d2774096733c7541832236`. Both D055 focused FAILs and earlier compiler/observer/stale-floor failures remain recorded. No original scenario or package is passed by these counts; password sign-in/refresh, F35/F36 composition/liveness, real providers and accepting gateways remain required work.

**Current verdict:** **READY for bounded, local implementation under explicit gates; NOT production-ready and NOT a complete application.** Three actual independent-agent rounds settled the design directions; they did not approve code or satisfy the original failed Opus request retroactively. Sensitive RPC, unqualified recovery activation, unsupported fiscal constraints and unguarded/nonatomic writes remain stop-lines. The implementation evidence report, owner Q02/Q04–Q07 facts, actual protected backend/native/OS storage, full feature coverage and physical qualification determine later package/release acceptance. Do not declare a foundation, Android debug build or Linux tests to be the completed five-platform POS.

[ARCH]: ../ukkera-pos-agent-handoff-2026-10-21/POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md
[OPS]: ../ukkera-pos-agent-handoff-2026-10-21/POS_OPERATIONAL_SPECIFICATION.md
[POL]: ../ukkera-pos-agent-handoff-2026-10-21/POS_COMPANY_POLICIES_AND_CATALOG.md
[PUR]: ../ukkera-pos-agent-handoff-2026-10-21/POS_PURCHASING_AND_RECEIVING_DESIGN.md
[BLUE]: ../ukkera-pos-agent-handoff-2026-10-21/POS_DOMAIN_AND_CONTRACT_BLUEPRINT.md
[TECH]: ../ukkera-pos-agent-handoff-2026-10-21/POS_TECHNOLOGY_AND_LIBRARY_GUIDE.md
[IMPL]: ../ukkera-pos-agent-handoff-2026-10-21/POS_IMPLEMENTATION_PLAN.md
[TASK]: ../ukkera-pos-agent-handoff-2026-10-21/POS_AGENT_TASKS.md
[ACCEPT]: ../ukkera-pos-agent-handoff-2026-10-21/POS_ACCEPTANCE_SCENARIOS.md
[TRACE]: ../ukkera-pos-agent-handoff-2026-10-21/POS_TEST_TRACEABILITY.csv
[QA]: ../ukkera-pos-agent-handoff-2026-10-21/POS_TEST_AND_QUALITY_PLAN.md
[DEC]: ../ukkera-pos-agent-handoff-2026-10-21/POS_OPEN_DECISIONS_REVIEW.md
[BE]: ../ukkera-pos-agent-handoff-2026-10-21/BACKEND_ARCHITECTURE_REVIEW.md
