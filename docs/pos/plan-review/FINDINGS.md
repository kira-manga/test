# Ukkera POS — lead review findings

**Updated24 October2026 · Lead-authored review; actual independent-agent rounds completed under the owner's revised instruction.**

Both lead and independent reviewer read all19 handoff files. Historical Claude failures remain failures. The owner then authorized local implementation without Claude; three actual rounds with `/root/independent_review` supplied findings/rebuttals/counterexamples. Current design resolutions below supersede dated pending-peer wording in the retained original finding history. They are **not full implementation or release approvals**.

## 1. Verdict and priorities

**READY for gated local implementation; NOT ready for unconditional contract/release sign-off.** The scope is unusually thorough; the review does not recommend reducing activities, platforms, offline guarantees or business coverage. The most important design correction is the meaning of absent operations and restored resource rights after an incomplete recovery. The next is serializing competing replacement approvals without pretending an epoch alone fences disconnected devices.

Severity means: **Critical** — can break a foundational financial/authority guarantee and must be resolved before freezing that contract; **High** — material money, security or supported-platform risk, with the affected implementation gate blocked; **Medium** — necessary precision/sequencing/acceptance improvement before the related package is accepted. Environmental prerequisites are labeled as such, not alleged application defects.

| ID | Severity | Class | Required correction / gate |
| --- | --- | --- | --- |
| P00 | Historical process gap, superseded scope | Original Opus calls failed; owner waived Claude | Three actual agent rounds completed; historical failed delegation is not reclassified as successful |
| F01 | Critical | Contradictory recovery/retry contract | History-aware absence and resource write admission after stale restore |
| F02 | High | Authority protocol completion | Serialize independent successor appointments as well as same-handover retries |
| F03 | High | Concrete platform prerequisite | Explicit strict P2pKit topology preflight and supported deployment arrangement |
| F04 | High | Conditional code-reuse/security risk | Exclude online/convenience auth storage from offline service and financial persistence |
| F05 | High | Protected backend runtime gate; source access resolved | Preserve tested route isolation; qualify mounted authentication, persistent mapping and transactional Mongo behavior |
| F06 | Medium | Repository/adoption ambiguity | Pin selective KMP reuse and separate new Android launcher/backend YAML identities |
| F07 | High | Financial contract completion | Exact advertised payable discount including dependent service/tax effects |
| F08 | High | Valuation contract completion | Select deterministic moving-average late-cost lineage policy and oracle |
| F09 | High | Missing cross-state transition | Unsafe/unavailable stock under an existing paid/unknown reservation |
| F10 | Medium | Sequencing clarification | Explicit early native/recovery/setup children, preserving the existing acyclic DAG |
| F11 | Medium | Strengthening existing retention rules | Measured retention/capacity/compaction matrix preserving retry and remedy safety |
| F12 | Medium | Measurable acceptance | Numeric accessibility targets and named actual assistive-technology profiles |
| F13 | Critical | Verified transport disclosure gap | Employee-bound original/retained/status/queued reply authorization, not only device trust/cache-zero |
| F14 | High | Discount eligibility/fiscal contract | Constrained-after postings, non-monotone feasibility and explicit coupled-tax rejection |
| F15 | High | SQLite durability/integrity qualification | Supported pre-migration/per-connection FULL profile, outer commit, immutable replacement guards |
| F16 | High | Bundle paid-allocation implementation | Preserve promised per-selection refund spread under caps and later permitted discounts |
| F17 | High | Retired catalog override history | Retain immutable revision high-water across remove/re-add |
| F18 | High | Typed supplier-settlement authority | Cash-move rights cannot substitute for invoice/source authority |
| F19 | High | Epoch/schema/recovery disclosure guards | Epoch-local positions, exact trigger DDL, actual actor key and valid audit time |
| F20 | High | SDK facade substitution | Referential lifetime ownership before reading wrapper identity/incoming |
| F21 | Critical at sensitive binding | Device-sleep credential expiry | Qualified suspend-inclusive clock or proven execution barrier, not a timer/resume observer |
| F22 | High | Swift expected-refusal boundary | Explicit throwable exports and native qualification |
| F23 | High | Verification evidence integrity | Same-byte reports, full inputs/fresh counts, one-run lock and owned-group cleanup |
| F24 | High | Typed stock/sale integration completeness | Complete cut/companions/recovery footprint, immutable sale fact and pre-money capacity |
| F25 | Medium | Lifecycle return-progress qualification | Distinguish authority seal from Unconfined outer-lock liveness |
| F26 | High | Local exception execution-evidence provenance | A custom serializer must not forge sent-call identity/phase/retry safety |
| F27 | High integration gate | Locked SDK test/API admission | Preserve exact pending integration; complete supported lock/API maintenance before qualification |
| F28 | High | Current-row SQLite coercion/bounded hydration | Validate actual SQL types/byte bounds before hydration and route kernel CAS/claim checks through the strict transaction view |
| F29 | High | Unknown legacy Room identity restamping | Validate original version/exact identity bytes/guards before protected DDL; prove no repair and genuine v1 preservation |
| F30 | High | Stale local credential-attempt capability | Exact captured incarnations, one-shot completion and original attempt-start continuity; no ID/timestamp-only reuse |
| F31 | Medium | Strict UTF error-contract leakage | Translate malformed Unicode to the typed sanitized sales rejection; retain current linked validation unchanged |
| F32 | High | Local access/current-authorization binding | Immutable original issuance/expiry and same-writer current facts; ACTIVE account state alone is not bearer validity |
| F33 | High | Hidden-rowid replacement integrity | Fifth-table fix and D054 historical INSERT/UPDATE hardening implemented and independently audited for the reviewed topology; arbitrary-file/DDL and production qualification remain outside this cut |
| F34 | High | Cancellation-erased continuity observations | Account normally returned current samples/latches before cancellation; never invent a sample from a thrown cancellation |
| F35 | High integration gate | Inherited Room loans and separate continuity owners | Qualify unborrowed request lineage/non-re-entry and SAME source wiring; SQL transaction checks alone are insufficient |
| F36 | High availability gate | Noncooperative Room close after recycle fault | Preserve no-publication checks, isolate destructive tests, and qualify bounded service retirement/restart without claiming pool recovery |

P00 is separate from the technical findings. F16–F36 arose from actual subsequent implementation reviews, not alleged omissions invented in the original handoff. Required fixes are below; optional refinements remain separate. Pure source/tests settle bounded behavior only, never all original scenarios or platform qualification.

### Current resolution register (24October2026)

| Findings | Resolution and remaining gate |
| --- | --- |
| F01/F02 | Independently accepted history/resource holds and evidence-backed single appointment issuer, with cloned-state countermodel. Actual custody/exclusion and durable recovery adapters remain owner/platform gates. |
| F03/F04/F06 | Selective pinned reuse/separate launchers accepted. Current scalar/JVM/Android builds are evidence, not installed native/service/key/network qualification. |
| F05 | Five real source clones and bounded real generator/Go route tests are now available; mounted protected API, persistent hierarchy mapping and transactional Mongo evidence remain unqualified. |
| F07/F14 | Exact gross-to-net250.80→225.72 accepted. Independent Hamilton eligibility correction and non-monotone/coupled-tax counterexamples require explicit V2 profile and per-request feasibility. No generic fiscal approval. |
| F08/F09 | Single-pool value-reference90/30 model and explicit unsafe-reservation holds accepted direction. Cross-review additionally fixed charge-source reuse and clock-rollback expired-return escape; full transfer/WIP/output and transaction integration remain. |
| F10–F12 | Original DAG/scope retained; early children and measurable retention/accessibility gates remain required, not discarded after foundation tests. |
| F13 | Cache-zero mitigation rejected as sufficient. Sensitive RPC is fail-closed until a distinct qualified library disclosure-scope correction or proven supported alternative. |
| F15 | Public TRUNCATE/FULL single-connection candidate selected; WAL onOpen workaround rejected. Actual outer-transaction/fault/schema/driver tests and each physical VFS remain separate. |
| F24/F28 | Receipt/A1/A2 prerequisites and selected-row SQL validation have separately reviewed execution evidence. D051/D052 adds the closed B staged-checkout JVM fixture:2,129-case full run, all prior identities retained, independent source review and pricing evidence audit. Production finalization/identity/frontier/initialization/procurement/capacity remain unqualified; coherent historical rollback is still an explicit limitation. |
| F29/F30 | Inert protected storage corrections independently source-reviewed and executed in the737-case target and separate1,632-case combined run. Pricing independently audited both exact archives without discrepancy; subsequent A1 source is outside that baseline. Full identity/key/login/custody/rollback-resistance and live migration gates remain. |
| F32–F34 | D053 immutable issuance/current-writer authorization and its two reproduced corrections are independently qualified. D054 subsequently fixes the historical11-table replacement paths with18 additive v4 guards; four additional destructive regressions went green, corrected59-method focus and2,347-case full cut passed independent audits. Old schemas/guards and all D053 test identities remain. Actual production bearer/login/runtime/refresh gates remain open; neither source cut is application acceptance. |
| F35/F36 | D055 internal supported-entry and shared-source contract resolved through actual challenge; no arbitrary-context support or simultaneous freshness claimed. Corrected64-case focus and the2,411-case full cut passed separate independent audits after preserved fixture/oracle failures. Accepting-context wiring and bounded poisoned-service retirement remain required engineering qualification, not completed by child-process tests. |

## 2. Evidence and review boundaries

Evidence keys below link to the unchanged originals: [ARCH][ARCH], [OPS][OPS], [POL][POL], [PUR][PUR], [BLUE][BLUE], [TECH][TECH], [IMPL][IMPL], [TASK][TASK], [QA][QA], [DEC][DEC] and [BE][BE]. Line numbers are from the preserved 21 October package. A historical source observation is not a new exploit result.

### 2.1 Complete handoff reading register

| File | Coverage / use in review |
| --- | --- |
| [README.md](../ukkera-pos-agent-handoff-2026-10-21/README.md) | Package entry, status and navigation; same contents as handoff README |
| [POS_HANDOFF_README.md](../ukkera-pos-agent-handoff-2026-10-21/POS_HANDOFF_README.md) | Full handoff entry and completion limits |
| [POS_AGENT_START_PROMPT.md](../ukkera-pos-agent-handoff-2026-10-21/POS_AGENT_START_PROMPT.md) | Future execution obligations; not authority to implement in this task |
| [POS_IMPLEMENTATION_PLAN.md][IMPL] | Scope, repository design, milestones, agent workflow and definition of done |
| [POS_AGENT_TASKS.md][TASK] | All 40 packages, ownership, dependencies and exits |
| [POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md][ARCH] | Authority, identity, mobile operation, command/sync/recovery boundaries |
| [POS_OPERATIONAL_SPECIFICATION.md][OPS] | Cash/order/kitchen/stock/payment/tax/returns and full activity baseline |
| [POS_COMPANY_POLICIES_AND_CATALOG.md][POL] | Configurable rights, catalog, remedies, promotions, setup and UX |
| [POS_PURCHASING_AND_RECEIVING_DESIGN.md][PUR] | Procurement, custody, inspection, invoices, supplier settlement and cost |
| [POS_DOMAIN_AND_CONTRACT_BLUEPRINT.md][BLUE] | All record families, scalars, constraints, APIs, storage and recovery fields |
| [POS_TECHNOLOGY_AND_LIBRARY_GUIDE.md][TECH] | Libraries, native/platform qualification, data protection and integration |
| [POS_TEST_AND_QUALITY_PLAN.md][QA] | All 79 gates, fixtures, physical/load/failure profiles and evidence rules |
| [POS_ACCEPTANCE_SCENARIOS.md](../ukkera-pos-agent-handoff-2026-10-21/POS_ACCEPTANCE_SCENARIOS.md) | All 141 business triggers and observable results |
| [POS_TEST_TRACEABILITY.csv](../ukkera-pos-agent-handoff-2026-10-21/POS_TEST_TRACEABILITY.csv) | All rows/columns, exact source mapping, required layers/profiles and unexecuted status |
| [POS_OPEN_DECISIONS_REVIEW.md][DEC] | Settled owner decisions versus external facts; no reopening settled scope |
| [BACKEND_ARCHITECTURE_REVIEW.md][BE] | Full historical source/generator assessment and explicit runtime limits |
| [POS_HANDOFF_REVIEW.md](../ukkera-pos-agent-handoff-2026-10-21/POS_HANDOFF_REVIEW.md) | Prior handoff document checks; not the independent debate requested now |
| [HANDOFF_MANIFEST.json](../ukkera-pos-agent-handoff-2026-10-21/HANDOFF_MANIFEST.json) | 18 payload hashes/sizes plus manifest itself = 19 files; status/count assertions checked |
| [backend-review/generator-verification.json](../ukkera-pos-agent-handoff-2026-10-21/backend-review/generator-verification.json) | Exact scope: 66 old-model transformations, no service/registry build/API/DB qualification |

The 19 files total **609,398 bytes**. All 18 manifest-listed payload hashes/sizes matched. The lead checked 141 unique scenarios, exact CSV trigger/result/source mappings, 79 distinct Q gates and an acyclic 40-package completion graph. All scenario execution statuses are `NOT RUN`; implementation/test, revision and execution-evidence fields are empty. Document checks do not make them passed.

### 2.2 Existing code inspected — historical22 October plan-only snapshot

This table records the original review boundary, not current source availability.
The later local implementation obtained the five pinned backend checkouts and
executed bounded generator/Node/Go probes, as recorded in the current resolution
register and implementation verification report. Protected runtime/Mongo
qualification remains open.

| Source | Revision and evidence limit |
| --- | --- |
| `kmp-apps` | `95d3574a1f15ed8f1fcf5d2b97fa2955ad7bacc3`; build conventions, app-definition parser, version catalog and Queue backend write guards inspected read-only |
| `kmp-features` | `ed3c0b63a8b31f627ee9c230648fb83dee121bd3`; authentication gateway/repository/session manager and JVM secure-session implementation inspected read-only |
| `/root/projects/p2pkit/P2pKit` | Pinned rc6 source inspected with `git show 3ef2c6155abd66292011c3775b30fb71d280e9c7`; existing unrelated working-tree changes were not altered or claimed clean |
| Backend/generator repositories named in BE §2 | At the original22 October review, not locally present in bounded searches of `/root/projects` and `/tmp`; unauthenticated retrieval of pinned viki source returned404. This historical access failure did **not prove nonexistence or vulnerability**. Source access was subsequently resolved. |
| Published dependency metadata | Read-only official metadata checks confirmed Kotlin 2.4.21, CMP 1.12.2, Room3 3.0.3 and P2pKit 0.8.0-rc6 were published as of this review. No compatibility build/runtime qualification was performed. |

During that original22 October plan-only review, no application build, generator
run, migration, deployment or business database operation was performed. Only
source/document inspection and document/structural/arithmetic/integrity checks
were performed. Subsequent local implementation tests are separately recorded;
no live migration or deployment has been authorized or performed.

## 3. Required corrections and qualified resolutions

### F01 — Critical: absent operation does not prove safe retry after missing history

**Evidence:** BLUE §5.3, line 294 says `NOT_RECORDED` permits safely resubmitting the same original operation. BLUE §7, lines 335–345, especially 337, correctly says an absent unreplicated operation requires reconciliation. QA Q-SEC-09, line 195 explicitly rejects automatic new execution for a missing unreplicated outcome. ARCH §18.6, lines 534–544, discloses missing tail then allows replacement activation/resumption, but does not make resource write admission sufficiently explicit.

**Impact:** a stale snapshot may omit an already accepted sale/refund/receipt/settlement and restore stock, cash, return entitlement or PO allowance that was already consumed. Repeating the old operation can duplicate effects. Merely blocking old operation IDs is insufficient: a **new ID** can spend the same resurrected rights. Restricting only known pending client requests overlooks an unknown tail that was never returned to the client.

**Concrete correction:** FINAL-PLAN §5 defines persisted history coverage by authority chain/epoch/checkpoint, known gaps and possible unknown tail. `NOT_RECORDED` is retry-safe only under complete relevant execution coverage; otherwise use `HISTORY_INCOMPLETE`. Positive restored results still return normally. A separate `RESOURCE_RECONCILIATION_REQUIRED` admission check applies to fresh mutations touching uncertain balances/rights/grants. If affected scope cannot be bounded, quarantine conservatively rather than guess. Reconcile custody, stock, kitchen, money, entitlements and purchasing evidence separately; a stock count does not prove unused refund entitlement. Preserve obligations/claims/deadlines and original actor. Late recovered tail must be matched against adjustments already included in recovery counts, not blindly replayed on top.

**Trade-off:** degraded service after actual data loss versus duplicate financial/stock effects. This does not change ordinary durable-master confirmation or add standby/cloud ACKs. A stale disaster copy cannot be made complete through retry logic.

**Acceptance/owner:** T02/T05/T25/T26; RV-01 and Q-SEC-09/Q-RES-08/09/14. Recover a present result once; withhold absent results/new mutations until coverage/resource readmission; reconcile late tail with no double effects. **Status:** design correction incorporated by lead; protocol/oracle and peer challenge still required before contract freeze. Owner supplies practical recovery controls/RPO facts, not the mathematical meaning of absence.

### F02 — High: independently approved successors still need serialization

**Evidence:** ARCH §18.3, lines 495–508 already binds a planned handover to its target/checkpoint and durably seals the old master. ARCH §18.6, lines 534–542 permits named offline delegated recovery; BLUE §8, lines 353–362 supplies assignment/approval records and old-writer isolation. These protections are substantial. The remaining gap is **two different valid recovery ceremonies**, not retries of one handover or missing old-master fencing.

**Impact:** two disconnected delegates can select different restored targets after the old device is isolated. Each target may hold a valid-looking signature/next epoch. A one-use proof checked against two copied local databases is not globally one-use. Both writers could issue independent sales, stock or refunds.

**Concrete correction:** FINAL-PLAN §5.3 adds single-incident issuance/custody, durable target/predecessor/checkpoint binding, explicit target-change cancellation and a qualified operational procedure excluding competing successor appointments. Named delegates may remain multiple, but appointment issuance must be serialized. No activation if old writer or a prior/competing target cannot be excluded. A lost/cloned issuer requires controlled recovery of issuance custody, not a fresh unconditional token. State the limits of software guarantees against copied offline state.

**Trade-off:** stronger custody/runbook obligations and possible recovery delay. A cloud lease or consensus system would change accepted offline authority; neither is silently added. Cryptographic signing authenticates an approval but does not establish global exclusivity by itself.

**Acceptance/owner:** T02/T08/T26; RV-02, Q-RES-05/06/07/10. Test two disconnected approvers, competing targets, lost activation reply and returning old writer. Q02 requires owner acceptance of actual custody/isolation controls. **Status:** lead-selected operationally qualified approach, not proven or peer-approved. If those controls cannot be provided, this remains an activation blocker.

### F03 — High: strict transport topology can invalidate the local deployment plan

**Evidence:** TECH §3/§8 and IMPL §10 require actual master/client/local-backend integration. Pinned rc6 `library/p2p-transport-lan/src/jvmMain/kotlin/dev/p2pkit/transport/lan/JvmOrganizationLan.kt`, lines 50–62, counts **any other up non-loopback interface** as competing and refuses it; the selected interface must not be virtual/point-to-point/unsafe. Pinned `docs/rpc/security-and-deployment.md`, lines 98–127, requires explicit private CIDRs/interface/address, documents JVM single-interface restrictions, forbids loopback/VPN fallback and requires runtime recreation after address/interface changes.

**Impact:** a development or production desktop with Docker bridges, dual active Wi-Fi/Ethernet or a VPN may fail strict RPC startup. A same-machine loopback demo or an assumed VM solution is not supported topology evidence. Disabling the guard to obtain a demo conceals a deployment/security blocker.

**Concrete correction:** inventory enumerated interfaces/topology in T00/T01; define qualified master-host and separate central-service arrangements, approved LAN routing/firewall/manual pinned endpoint, permission failure and runtime-recreation behavior. Keep same-device protected IPC/in-process adapter distinct from LAN. Qualify an isolated container/host arrangement rather than prescribe unverified NIC exemptions or network reconfiguration. Preserve durable application operation IDs through uncertain path failure.

**Trade-off:** constrained deployment profiles versus weakening the requested library's security model. Long transfers also remain bounded by the reviewed RPC size/deadline constraints; use durable jobs/chunks, not giant requests.

**Acceptance/owner:** T01/T09/T30/T34; RV-03 and Q-SEC-01/02. Actual master/client startup and negative topology tests on supported OSes; owner supplies real network/device facts Q04. **Status:** concrete engineering prerequisite added; no runtime compatibility claim and no claim the published dependencies are invented.

### F04 — High: available authentication/storage components are not drop-in master persistence

**Evidence:**

- [VikiIdentityGateway][S-GATEWAY], lines 31–58: login and current identity use HTTP.
- [DefaultAuthRepository][S-AUTH], lines 66–89: restore calls the identity gateway; lines 140–147: logout awaits revocation before clearing local session.
- [SessionManager][S-SESSION], lines 28–36: any storage read/decode failure invokes `storage.clear()`.
- [JvmSecureSessionStorage][S-STORE], lines 43–52 and 127–146: the non-Windows JVM protector writes its AES key beside the session; lines 168–187 tolerate permission failures; lines 190–200 provide a rename fallback, not a demonstrated fsync durability contract.
- TECH §7.2, lines 149–153 already requires protected per-platform storage, no adjacent database keys, independent service secrets and non-cloning recovery.

**Impact:** blindly adopting these components could make branch login/restore depend on central service, delay user isolation during logout, expose copied key/data pairs or erase irreplaceable authority/pending financial evidence on a recoverable read/key error. This is a **conditional reuse risk**, not proof all existing auth abstractions are unusable or every current session cache is a ledger vulnerability.

**Concrete correction:** create a reuse/exclusion matrix. Retain suitable contracts/UI with separate local-master identity/session adapters. Separate central sessions, local sessions, service/security store and durable original pending-operation evidence. Immediately isolate a logged-out interactive user independently of remote revocation; do not stop master. Use OS-protected keys qualified in the actual service context, recoverable encrypted key envelopes and failure states that preserve evidence. Never clone device private keys or store master history in a clear-on-error convenience token cache.

**Trade-off:** small purpose-specific adapters/storage implementations versus a blanket ecosystem rewrite. Existing central auth may remain appropriate after its own scope/security qualification.

**Acceptance/owner:** T01/T05/T07/T08/T25/T32; RV-04, Q-SEC-03/04 and Q-DB-10. Offline restart/user switch, key lock/unavailability, corrupt storage, permission failure and replacement with original actor preserved. **Status:** explicit exclusions incorporated; adapters/key mechanisms remain an engineering gate, not owner business design.

### F05 — High gate: backend isolation must be verified across inherited routes

**Evidence:** BE §6.2, lines 140–148 describes historical audit-only/unknown-permission behavior, nullable scope/filter composition and sync overwrite concerns, explicitly not live exploit findings. BE §8, lines 194–205 and generator JSON prove only 66 old-model transformations. Current [VikiQueueUpdateOne][S-QUEUE], lines 27–40 records historical deployed cross-role/branch/account write failures and defaults all write capabilities false; lines 51–53 gate submission; lines 76–79 identify generic `/api/v1/update_one/`. QA Q-BE-01–10 already requires runtime negative checks. Current backend repositories were unavailable for reinspection.

**Impact:** protecting only new named BranchPos commands leaves a possible inherited generic/model/bulk/relationship/GraphQL/sync path around those protections. Conversely, treating the historical report as proof of today's live behavior would be unjustified. Generation and frontend hiding cannot certify tenant isolation or atomic central ingestion.

**Concrete correction:** obtain authorized exact sources/runtime fixture in T00. Enumerate every reachable API form and explicitly deny/isolate BranchPos immutable ledger writes except typed authenticated ingestion/corrections. Revalidate unknown permission deny, mandatory scope, direct/related IDs, filters, export/alerts and training/live isolation. Prove real Mongo multi-document atomicity or a separately reviewed atomic evidence design, including identity/result/checkpoint/outbox commit and failed ACK. Preserve existing YAML; repair only necessary protected integration paths.

**Trade-off:** targeted fail-closed namespace protection and verification, not an unrelated full backend rewrite. An inaccessible backend blocks its evidence gate, not pure local domain design.

**Acceptance/owner:** T00/T06/T07/T23/T31; RV-05 and Q-BE-01–10; Q03 for authorized repository/runtime availability. **Status:** existing important gate made concrete, **not a newly demonstrated current server exploit**. No endpoint was exercised by this review.

### F06 — Medium: explicitly choose selective KMP reuse and build ownership

**Evidence:** IMPL §4, lines 69–84 lists the backend/generator/P2pKit ecosystem but omits locally available `kmp-apps`/`kmp-features`. [UkkeraKmpApplicationPlugin][S-BUILD], lines 49–52 combines KMP and Android application plugins; lines 88–99 uses `androidTarget`. [gradle.properties][S-PROPS], lines 11–12 disables built-in Kotlin/new DSL. TECH §3, line 63 requires a separate launcher/new KMP library layout. [AppDefinitionParser][S-PARSER], lines 53–68 requires schema-v2 identity mapping; lines 104–112 requires lowercase dotted JVM package. `BranchPos` names the backend domain, not that app identity field.

**Impact:** different workers could scaffold incompatible build layouts, duplicate reusable work, rename unrelated Queue modules or send backend model YAML through an app-identity parser. Current differing versions are not evidence that either existing repository fails to build.

**Concrete correction:** retain accepted standalone `ukkera-pos`; pin and inventory selective contracts/UI/design reuse, with local adapters/security exclusions from F04. Use a qualified separate Android launcher and new library convention. Record backend namespace, working repo and platform/package identities separately. Any shared build/root registry change has one owner and independent review; do not refactor Queue as a side effect.

**Acceptance/owner:** T00/T01/T10/T34; RV-06 and relevant target build/packaging gates. **Status:** adoption recommendation incorporated; coherent graph still to qualify. No owner reapproval of the already accepted repository/namespace is required.

### F07 — High: a gross fixed discount needs an exact service/tax-aware algorithm

**Evidence:** OPS §10, lines 248–254 requires an advertised fixed discount to reduce payable by that amount and computes percentage service on discounted eligible net. POL §4, lines 53–59 requires deterministic sequence and original refund allocations. BLUE §2.1, lines 27–46 supplies exact types, half-up/largest-remainder defaults but leaves the explicit algorithm/posting boundary to freeze.

**Impact:** independently implementing discount, tax and service can under/overcharge while each component appears locally plausible. Partial returns then fail to reconcile with the receipt. This is completion of an acknowledged contract boundary, not an observed application calculation defect.

**Concrete correction:** FINAL-PLAN §7.1 selects a versioned calculation order and proposes exact gross-to-net discount allocation including marginal percentage service and service tax. Freeze eligibility/stacking first, use deterministic caps/remainders and persist exact/posted per-quantity allocations. Explicitly distinguish advertised total reduction from item-base reduction and consequent charge reduction in the receipt; never subtract them twice. Choose the actual fiscal posting profile before that company's launch; do not assert a single operational rounding rule is universally legal.

**Independent review fixture:** inclusive `114` + exclusive `100` at hypothetical `14%`, plus `10%` net service taxed `14%`, totals `250.80`. Fixed `25.08` advertised reduction gives **`225.72`** (`180 + 25.20 + 18 + 2.52`). Naive `25.08` net reduction gives `219.34968`. The fixture illustrates the contract, not an Egyptian product classification.

**Trade-off:** deterministic arithmetic for the bounded supported rule set, not an arbitrary nonlinear promotion optimizer. Legal line/document rounding must coexist with the promised customer reduction; an incompatible configuration cannot silently produce a different discount.

**Acceptance/owner:** T02/T04/T12/T20/T31; RV-07, TAX/PRC/RET and QA §6 fixtures, including mixed/zero rates, tiny amounts, caps and repeated partial returns. Q05 supplies actual fiscal facts. **Status:** lead calculation profile proposed; independent oracle/fiscal compatibility and peer challenge remain before acceptance.

### F08 — High: late acquisition cost must define which moving-average value remains

**Evidence:** PUR §7, lines 85–91 selects moving weighted average per SKU/warehouse, explicitly separates FEFO physical picking and requires a documented deterministic held/consumed correction. OPS §11, lines 288–290 preserves original issue/sale costs and separate late adjustments. No concrete lineage algorithm fixes the ambiguity between physical receipt residue and pooled moving-average value.

**Impact:** both a `60/60` and `90/30` held/consumed split can appear to match a loose reading, but create different current inventory value, later sale cost and margin corrections. Transfers, recipes/WIP and returns amplify the difference. Supplier credit is not automatically the same as book inventory departure.

**Concrete correction:** use a versioned value-lineage calculation with a reference-only moving-average valuation replay or equivalent proven contribution ledger. Carry the cost delta through recorded pool/issue/transfer/production lineage; append current inventory-value and consumed-cost correction records without changing quantities, original cost snapshots or supplier obligations. Compute against a fixed cut and recheck before atomic idempotent posting. Destination branches receive guarded pending adjustments, not central overwrites.

**Independent fixture:** buy `10 @ 20 + 10 @ 30`; sell `5 @ 25` → held `15`, value `375`, original issue `125`. Add late freight `120` to first receipt: reference average `31`; held value `465`, delta **`90`**; consumed correction **`30`**. A physical first-receipt-residue allocation may instead yield `60/60`, which is a different policy. The lead recommends value-lineage because the accepted valuation is pooled moving average, while keeping original reported margin immutable.

**Trade-off:** lineage/cut calculation complexity versus a simpler receipt-residue policy with different accounting meaning. The reference oracle must not re-execute real sales, printing, stock commands or external payments. No fiscal/accounting or peer agreement is asserted.

**Acceptance/owner:** T02/T13/T18/T21/T22/T27/T31; RV-08 and PUR/STK/KIT fixtures. Include WIP/output, transfers to offline branches, returns, repeated/negative corrections and zero balance; conservation and historical immutability required. **Status:** explicit lead-selected policy pending independent review/engineering proof. This engineering choice was delegated; do not ask the owner to implement its algorithm.

### F09 — High: reserved stock can become unsafe while money remains unresolved

**Evidence:** OPS §8, lines 190–192 forbids silent expiry of unknown/part-completed collection reservations and excludes expired/quarantined stock from sellable availability. OPS §6.1, line 138 preserves paid-unsent ingredient reservations; §11, line 286 requires counts to account for reservations. POL §6, lines 98 and 111–113 includes recall/expiry and mixed recipe/kit timing. The normal rules are clear; the collision needs a transition.

**Impact:** an implementation may indefinitely honor an expired/recalled reservation and sell unsafe stock, or release it while payment is unknown and lose the customer's paid fulfillment/refund obligation. A count shortage can invalidate already allocated rights even with no timer.

**Concrete correction:** add explicit reservation safety invalidation/fulfillment hold. Validate at finalization/send and serialize recall/expiry/count/substitution races. If allowed, reallocate equivalent valid stock atomically while preserving quote and original actor; otherwise keep money/locks/obligation and resolve substitution/wait/refund explicitly. Do not sell unsafe goods, grant negative stock or silently expire monetary evidence. Handle paid-unsent recipes and retail lots alike.

**Acceptance/owner:** T13/T16/T18/T19/T20; RV-09, STK/PAY/KIT/CAT and Q-RES-01. Test multi-reservation shortage, unsafe lot, partial/unknown payment and kitchen-send race. **Status:** transition incorporated; implementation proof outstanding. No new business right to unsafe or negative stock is introduced.

### F10 — Medium: name early recovery/native/setup milestones rather than rely on permission to split

**Evidence:** TASK dependency table, lines 19–60: full T25 follows T19/T20/T22/T23/T24; T26 follows T25; T29 is late; T34 follows T32. TASK lines 11, 17 and 62–64 explicitly permit early child work/partial scenario evidence. IMPL §6 and TECH §3 already demand early spikes.

**Impact:** a literal package scheduler can postpone the highest-risk backup/key/native provider/authority tests until substantial feature investment, or incorrectly introduce a cycle to make an early full scenario pass. The existing graph itself is **not cyclic**.

**Concrete correction:** FINAL-PLAN §13 names E0–E5 children: topology/adoption, native package probe, protocol/arithmetic contract review, primitive actual restore/activation, setup/security-root skeleton and continuous package/upgrade probes. Design E2 within T02; integrate primitive E3 after T05/T08/T09 before M1, while leaving full T25/T26 dependencies untouched. Extend recovery coverage as each domain lands. No partial child or mocked flow completes its parent/scenario.

**Acceptance/owner:** main integration owner; RV-10 and original task exits. Scheduling/evidence board distinguishes contract, child and full package statuses; still 40 packages and acyclic completion dependencies. **Status:** clarification incorporated; no staffing or duration claim and no new foundational owner decision.

### F11 — Medium: turn existing retention safeguards into an executable compaction contract

**Evidence:** ARCH §19, line 584 already forbids dropping unacknowledged history on retry/age limits. BLUE lines 329, 337, 343 and 347 specify cursor expiry, cross-epoch deduplication, contiguous coverage ACK and financial evidence retention. POL §5, line 82 covers the longest applicable return/warranty/financial requirement and unresolved claims. DEC §4, line 98 explicitly requires retention/local archival/backup rotation work.

**Impact:** without a record-specific matrix, storage can grow unbounded or be compacted so aggressively that a retry appears new, a promised offline return needs cloud access, or the only usable snapshot/key is retired. A numeric last ACK alone does not prove complete recovery coverage.

**Concrete correction:** FINAL-PLAN §11.3 requires measured byte/event/offline-horizon budgets, legal windows and compaction proof by record/consumer. Retain operation identity/digest/tombstone or protected archive lookup sufficient to prevent retry-safe absence after eviction. Preserve unresolved claims and money; keep local evidence for promised offline remedies. Replacing a journal requires a verified coherent covering snapshot and required consumer/recovery coverage, not just a reporting projection. Test interruption/old backup/unknown tail/archive lookup/disk full.

**Trade-off:** explicit storage overhead versus correctness; not infinite retention of all secrets and not an arbitrary universal TTL. Low disk alerts are distinct from actual inability to commit; standby lag alone still does not stop sales.

**Acceptance/owner:** T02/T05/T23/T25/T32; RV-11 and Q-DB-06/08/09. Q06 supplies applicable retention/legal holds; engineering measures capacity. **Status:** strengthening an already specified gate, not a finding that retention or backups were absent.

### F12 — Medium: accessibility needs measurable pass criteria

**Evidence:** QA §12, Q-UX-01–10 is substantial. Q-UX-03, line 257 lists target size, keyboard/scanner, scaling, contrast, announcements and AT but provides no numerical target; POL §8, line 140 likewise asks for practical targets/readability.

**Impact:** two implementers can both claim accessibility from different unchecked interpretations. Arabic/large-text/operator controls can regress while a screenshot or resource-parity test passes.

**Concrete correction:** FINAL-PLAN §12.2 proposes contrast `4.5:1` normal text / `3:1` large text and essential controls; common touch minimum `48 dp` / iOS `44 pt`; enlarged text through `200%` and selected native accessibility sizes. Test keyboard-only/visible focus/scanner modal recovery, labels and announced errors on actual TalkBack/VoiceOver and selected Windows AT. Test Arabic/RTL mixed identifiers and printed receipts. Record profile-specific exceptions explicitly, not as silent passes.

**Trade-off:** larger controls/adaptive layouts versus operational density; numeric engineering targets do not establish legal certification or support for every AT product.

**Acceptance/owner:** T10/T17/T28A–E/T29/T30/T33; RV-12/Q-UX-01–10. **Status:** measurable baseline proposed; real physical/user evidence outstanding. Actual device/profile inputs are Q04, not a reason to drop accessibility.

### F13 — Critical: device-bound RPC replies can cross employee-session boundaries

**Evidence:** P2pKit pinned3ef2c6155abd66292011c3775b30fb71d280e9c7, `RpcConfiguration.kt:44–47`; `RpcHostEngine.kt:168–219,287–323,390–452,481–518`; `RpcLink.kt:237–247`; `RpcHost.kt:168–189,218–241`. Independent reviewer inspected the pinned source, not dirty unrelated work. Device-only authorization cannot recover the original employee scope. First terminal bodies remain after cache trimming and are sent to the latest device link; bodyless cancellations can bypass the callback; link queues outlive cache eviction.

**Impact:** employeeB on A's still-trusted device can receive A's private result/status after logout/switch, including unsolicited original replies. Fresh authorized operation.get and UI generation fences do not prevent raw same-device disclosure.

**Required correction:** FINAL-PLAN§2.3; keep sensitive transport unavailable. Add verified immutable employee/request scope, scope-aware all-phase authorization and revocable outbound tickets with explicit revocation ordering. Preserve conservative execution evidence; use fresh authenticated lookup after employee change. Do not add an unreviewed result-encryption protocol or claim host restart/cache-zero is a demonstrated fix.

**Resolution:** lead withdrew cache-zero-as-solution after peer counterexample. Both select a bounded library correction; actual patch/publication/source/runtime tests remain unresolved. Tests RV-13 must include success/error/status, in-flight/queued sends, raw replay/reconnect and token expiry. Any local fork must have distinct provenance; no official-rc6 claim.

### F14 — High: ordinary Hamilton after-rounding can violate discount exclusions/caps

**Evidence:** POL§pricing, preserved lines57–59; FINAL-PLAN§7.1. Peer independently calculated before(1.8,1.7,1.6)→posts(2,2,1), excludedC, discountA+B1→ordinary posts(1,1,2); reverse example puts all reduction on excludedC. Round3 proved non-monotone feasibility and14%-tax coupling fixtures now in FINAL-PLAN§7.1.

**Impact:** aggregate payable conservation can mask an ineligible line increase/decrease or cap/approval bypass. A signed rounding-explanation field is truthful accounting but does not restore eligibility. A maximum-discount binary search can wrongly accept/reject requests. Component conservation alone does not satisfy per-line fiscal tax rules.

**Required correction:** versioned constrained-after profile freezes exact-unaffected posted components; bounds changed postings by floor/ceil and previous posting, enforces cumulative original posted and exact caps, distributes feasible cents deterministically. Validate each request; mark mathematical capacity as an unqualified upper bound, reject unsupported coupled fiscal rules before publication/collection. Refunds read actual stored allocations.

**Resolution:** lead/price owner accepted independent counterexamples and selected V2, not an arbitrary optimizer. Tests RV-14 include exclusion, stable ties/permutations, repeated capped discounts, non-monotone D1/D2/D3, fiscal coupling rejection and stored partial returns. Actual legal profile remains Q05.

### F15 — High: WAL naming/onOpen tuning does not establish durable Room commits

**Evidence:** Room3.0.3 `RoomConnectionManager.kt:117–172,285–289,352–354`; public `RoomDatabase.kt:408,448,634–664` and `Transactor.kt:42,68–74`. Both source inspections confirm WAL setsNORMAL before migration; user onOpen is later and not repeated for all pooled connections. Independent kernel review also reproduced SQLite REPLACE bypassing DELETE triggers with recursive triggers off.

**Impact:** a replied financial success can lack the claimed crash/power-loss guarantees; nested transactions may return apparent success before the real commit; accidental replace can rewrite immutable evidence despite update/delete triggers.

**Required correction:** file-backed explicitTRUNCATE/FULL and no-driver-pool single connection, same borrowed writer verification outside IMMEDIATE transaction, all five families atomically, success only outside outer commit, no destructive fallback/unguarded DAO. Add preinsert duplicate-identity guards covering alternate unique keys. Qualify migration, actual driver/disk/commit errors/reopen/fresh-process behavior and physical VFS separately.

**Resolution:** lead/peer selected the simplest supported candidate, accepting serialized-reader throughput tradeoff. Current foundation tests are not power-loss/platform proof; WAL/FULL remains optional future work once supported.

### F16 — High: cap-based allocation can break promised bundle refunds

**Evidence:** POL§4/line59 equal-value bundle fixture; `ukkera-pos/domain/pricing/PromotionSelector.kt` and its paid-allocation regression tests. Independent review gave original100+100, target100, caps0/100: ordinary V2 water filling could yield paid100/0 instead of promised50/50.

**Impact:** receipt/refund terms become false despite a conserved total. Rebalancing all later discounts would also invent a different business rule.

**Required correction/resolution:** freeze original-gross-weighted canonical LR paid targets at EACH bundle/buy-get selection; reject candidate V2 postings that cannot match, with `PAID_ALLOCATION_NOT_SUPPORTED`. Keep later legitimately stacked manual reductions separate. Six discriminators passed in80 JVM/75 Android-host pricing tests; reviewed V2/codec was not rewritten. Selection-evidence persistence, wider campaign forms, actual fiscal and native/max-latency qualification remain. The explicit group-greedy selector is not a global optimizer.

### F17 — High: deleting an override must not erase revision history

**Evidence:** POL§3 company/branch price precedence; `domain/catalog/CatalogDefinitions.kt`, `CatalogActivation.kt`, `CatalogOverrideHistoryTest.kt`. Current-only state allowed removingO/r2 then reintroducingO/r1 or rewriting known terms.

**Impact:** stale/replayed branch edits can regain authority over new lines and invalidate immutable version assumptions.

**Required correction/resolution:** bounded append-only `(id, revision)` registry; immutable terms, high-water validation over current+historical union, no implicit selection from history. Permit explicit identical-latest reactivation only after current base/policy/authorization checks. Eight regressions and independent read-only review;75/75 host tests plus both Apple klib compilations passed. Protected complete-history loading/activation remains unimplemented; a client-supplied partial registry is not proof.

### F18 — High: a cash-move grant is not supplier-settlement authority

**Evidence:** PUR§invoice matching/settlement and OPS cash classification; `adapters/sales-store/CashNonSaleMovementHandler.kt`, `CashNonSaleTranslation.kt`, `core/database/.../CashStoreIntegrationTest.kt`.

**Impact:** accepting INVENTORY_SETTLEMENT from a session/amount/source ID alone could drain custody while bypassing invoice balance, designated branch authority, advance/credit allocation and unique procurement source obligations.

**Required correction/resolution:** fresh inventory-settlement requests return Unsupported AFTER exact current actor/custodian authorization, before domain transition, until complete procurement authority/companion translation exists. Preserve old decoding and positive-result lookup. Pay-in/operating-expense33/33 adapter tests and13 real-Room typed integrations passed within database61. Genuine pending refund reserve, closing and transfer cases reject safely. **Evidence gap:** no retained old-producer SQLite fixture; replaying a newly stored Unsupported does not prove historical Applied replay. Do not add a production legacy-bypass switch to manufacture that test.

### F19 — High: guard names and current epoch are insufficient durable identities

**Evidence:** BLUE operation/recovery/journal contract; `core/database/MasterRoomDatabase.kt`, `AtomicMasterStore.kt`, `DurableRoomProfile.kt`, `StoreBoundaryRegressionTest.kt`, `StoreMigrationProfileTest.kt` and `StoreCommitFailureTest.kt`.

**Impact:** sequence reuse across authorized epochs can collide; replay may present the wrong authority. Same-name ineffective triggers can permit immutable-fact rewriting. An unrelated employee footprint or invalid supervisor audit timestamp can bypass intended recovery/disclosure safeguards.

**Required correction/resolution:** journal key `(scope,epoch,sequence)` versus cross-epoch operation key `(scope,operationId)`; replay original epoch; compare exact normalized expected trigger DDL; require original actor AUTHORIZATION key; reject out-of-profile audit time before disclosure.61 JVM tests passed, including actual COMMIT errors and test-only DDL migration/rollback with boundary-reached assertions. Schema1 hash `19f2b78d97f758f47845a449bbcf02d5a80e4b06e334b05b3fa09af574d98a28` is unreleased development state, not a production migration. Physical VFS/power-loss and protected runtime remain open.

### F20 — High: virtual facade claims do not attest identity and incoming streams

**Evidence:** isolated rc6 candidate `library/p2p-core/P2pDisclosure.kt`, `SessionScopedDisclosureTest.kt`, and scoped RPC construction in `ScopedRpcSession.kt`. Backend challenged an interface wrapper forwarding real claim/send but overriding peer identity/incoming; a genuine lifetime alone does not certify the wrapper.

**Impact:** RPC could authenticate fabricated views while using a real SDK send authority.

**Required correction/resolution:** final SDK-owned `belongsTo(session)` compares actual captured object with `===`, no equality/hash/property callbacks; verify immediately after claim, before HELLO/identity/incoming. Mismatch seals captured lifetime, never invokes untrusted wrapper cleanup. Independent core reviewer matched source hashes and reviewed substituted-view regression. Current compilation/runtime evidence is recorded separately; no full adapter/platform approval.

### F21 — Critical before sensitive use: ordinary monotonic clocks can stop during sleep

**Evidence:** core Android/JVM `Platform.*.kt` uses nanoTime; iOS uses systemUptime. `P2pDisclosure.kt` checks deadline at commitment. Recovery reviewer verified platform API documentation for Android SystemClock, Apple mach_continuous_time, Windows interrupt time and Linux CLOCK_BOOTTIME.

**Impact:** a prepared credential-bound message/publication can remain apparently fresh after a device sleeps past its expiry. A delayed timer or resume observer is not an authoritative first-resumed-commit barrier.

**Required correction:** separate SDK-owned suspend-inclusive disclosure clock, initialized outside authority locks, failed/regressing provider permanently retiring authority. Recommended native APIs: Android elapsedRealtime; iOS/macOS mach_continuous_time; Windows QueryInterruptTimePrecise; Linux CLOCK_BOOTTIME. JVM needs reviewed packaged bridge; no unsupported fallback. Alternatively prove synchronous pre-suspend retirement AND a universal pre-resume execution barrier, including abrupt OS sleep—not merely UI callbacks.

**Resolution:** **OPEN required gate**, explicitly not cleared by documentation/common/JVM synthetic tests. On each supported platform test actual sleep/deep-sleep/hibernation beyond TTL, suppress test-controlled timers and assert Expired at the gate, not a coincidental network-loss failure. No sensitive POS binding until qualified.

### F22 — High: expected Kotlin refusals can abort Swift callers

**Evidence:** new public lifetime prepare/preparePublication and deadline allowance APIs throw expected P2pError/argument refusals; core is framework-exported. Existing P2pSession uses throwable annotations, but the candidate initially omitted them. Default suspend export otherwise propagates only cancellation.

**Impact:** ordinary seal/expiry/capacity or invalid-allowance handling can terminate a Swift client instead of returning an expected error.

**Required correction/resolution:** additive `@Throws(Exception::class)` on affected public APIs, reviewed in common source; no native implementation rewrite. Add signature checks where meaningful and qualify actual generated Swift headers/calls. Source correction is authored; Swift linking/execution remains unavailable on this host and is not PASS.

### F23 — High: a verifier can falsely certify changed or incomplete inputs

**Evidence:** `tools/verify_local.py` initial runner and8 self-tests; independent app-shell reviewer reproduced root/tree symlink reads, missed `src/.../build` and buildSrc inputs, XML parse/hash/copy TOCTOU, leader-exits/ignoring-child leak, missing runner signal cleanup, no run lock and backend PASS-only freshness. Five backend checkout files outside the owned patch list were not fingerprinted.

**Impact:** passing counts can identify different/failing XML or unmeasured source; stale/missing backend suites can appear verified; abandoned test processes interfere with later work. `--offline` also overpromised wrapper-bootstrap behavior.

**Required correction:** precise generated-output roots, build-logic/full backend input+pin inventory and symlink refusal; capture XML once and parse/hash/archive those bytes with metadata checks; one-run flock; retain owned process-group leader until all termination signals finish, handle repeated INT/TERM/HUP; current backend invocation/fresh timestamps, required package/Node counts and zero skips; honest Gradle-only offline wording or real wrapper cache preflight. **Resolution:** scalars corrected the runner; app-shell independently reran40 self-tests and additional adversarial probes without a remaining concrete blocker. Lead's actual `tools/verify_local.sh --apple-klibs` passed at16:00:54Z:1,132 host tests, builds/software smoke/Apple klibs and current-invocation backend checks;8,136 before/after input records matched exactly. Evidence: `../ukkera-pos/.workspace/evidence/runs/20261023T155542.038180Z/verification.json`. This closes the reproduced bounded-runner defects for that source cut, not all conceivable faults or application/native acceptance. New inputs require new verification.

**24 October additional source findings and resolution:** security found that accepted XML had no completed-phase upper timestamp bound and that the targeted runner could publish PASS after a late signal. The full runner's pre-save signum check narrowed but did not close that publication window. Both active callers now enforce inclusive lower/upper bounds against the captured XML metadata and retain `mtimeNs`; shared finalization stages nonaccepting FINALIZING before one explicit signal-snapshot cutoff. A pre-cutoff signal forces FAIL; post-cutoff signals are explicitly post-verification. Independent rereview also caught and removed a cache-dependent platform-probe subprocess from the mocked harness. Lead's53-case harness preflight passed and security audited its exact packet; these tests stub work phases and do not qualify A2 or the application. No prior archive corruption or actual cancellation was demonstrated; all143 previous live XMLs separately matched their archived hashes and original phase bounds. Original packets are preserved, not retroactively described as enforcing these new guards. Both active runners subsequently executed the53-case harness and real A2 target/full phases; their exact accepted XML/evidence were independently audited under the new bounds/cutoff contract.

### F24 — High: small typed snapshots cannot silently stand in for complete transactions

**Evidence:** BLUE§2/4/6 complete domain facts; kernel `CommandContract.kt` point reads/64KiB rows/1MiB proposals; inventory codec up to8MiB and planner collateral expiry; `Collections.finalizePaid`, `SalePosted`, embedded `StoredPaidSale`, recovery exact resource selectors. Independent sales/inventory design review found normalized reservation/pool revisions can advance multiple times per plan.

**Impact:** filtering a cut can lose unrelated safety holds; assuming every child revision+1 breaks CAS. Dropping SalePosted because entitlement embeds it omits immutable sale facts. A branch-ID STOCK sentinel misses held lot/pool keys. Accepting money before reserving finalization/outcome capacity can strand a real obligation.

**Required correction/resolution:** bounded whole-branch fixture first, complete inventory companions/source claims and recovery footprint; immutable standalone PaidSale codec/fact; held path retains COVERED evidence and emits no issue/sale claims. Independent storage generations and protected complete manifests or reviewed enumeration before normalization. Explicit protected genesis, real imported provenance and pre-collection capacity admission. Inventory codec/index correction93/90 passed, but **finalizer/production capacity/provenance remain OPEN**. No silent row-limit increase, evidence truncation, history pruning or missing-state default0. Whole-branch bounded planner is not automatically production-sized after normalization.

**Simplification after actual challenge:** reuse existing immutable journal after-payloads/new generations and claims/jobs through a strict transaction-bound retained-command reader; do not duplicate full cut history per operation or replay all history on every healthy sale. Keep two current cut/companion rows and compare them to their selected retained producer's exact writes. Original metadata stays historical; a retained rejection is not a commit witness, and missing/valid hashed records never prove absence, current frontier or exclusive producer ownership. The kernel currently permits overlapping handler write namespaces, so protected composition/import/frontier qualification remains essential. STOCK-hold denial and Held paid-target eligibility are separate required safeguards. The reader is implemented, independently source-reviewed and actually tested in database104/28 plus cash33/33 and both database Apple klibs; no finalizer/capacity or production acceptance is implied.

**Denial-only barrier resolution:** additive default-empty handler restriction and explicit four-argument recovery policy scan every retained selector by kind, independent of ID, footprint intersection and readmission. The original three-argument policy remains; current actor/ordinary resource checks and authorized original-result replay are preserved. Security found that a single-hold fixture would miss first-hold/reason-filter defects; inventory added genuine AUTH-only restore/readmit then late-tail STOCK-alias hold/readmit controls. All21 new tests executed in recovery55/54, database112/28 and cash33/33; four recovery/database Apple main klibs compiled. Evidence: `../ukkera-pos/.workspace/evidence/recovery-barrier/20261023T205448.304792Z/verification.json`. No production handler opts in; canonical alias/readmission, full producer/finalizer and pre-collection capacity remain open.

**Receipt-only profile resolution:** `adapters/inventory-store` now checks the complete supported receipt/position/quantity/value/expiry crosswalk, exact latest retained producer/result/two after-writes/two source claims/no jobs, all older source/upstream claim owners and identical planner source object. Both current rows CAS together; missing is not genesis. The test-only composition freezes kinds and claim namespaces independently, gates initialization on successful fresh installation and uses STOCK denial. Backend challenged initializer/older-origin/declaration evidence; lead corrected it. Pricing independently caught and rereviewed an unreachable oversized-row test setup.48/48 adapter tests and23 real-Room integrations passed; database168/28 includes all current regressions. The32-receipt count cap is additional to actual64KiB/1MiB byte limits, not a32-arbitrary-receipt promise. Unsupported reservation/issue/cost/custody states reject. A real-Room regression explicitly shows coherent historical pair rollback can still pass even with newer retained facts/claims; no frontier authenticity is claimed. Evidence: `../ukkera-pos/.workspace/evidence/runs/20261023T215245.398756Z/verification.json`. **F24 remains OPEN for complete inventory/finalizer/procurement/protected genesis and pre-collection capacity.**

**Next-stage correction at the A1 boundary (D044/D045):** Place immutable posted-fact projection and the exact live source/effect/result crosswalk in domain/inventory before the adapter companion, reusing existing internal DTOs. Preserve sequential same-pool source identity, every intermediate reservation record, collateral/zero-stock expiry and ordinary zero Consumed balances. Reject unsupported histories across the entire source/result cut, not only the changed pool or latest adjustment. Backend/security source-reviewed A1; lead executed959 cases/10 profiles (inventory164/156), six Apple main-klib targets and40 harness tests with8,179 unchanged inputs. Pricing independently audited that exact target and the subsequent1,769-case/22-profile/143-XML combined run at23:49:40Z without discrepancy;8,179 inputs remained unchanged in each. A2 must bind complete receipt/lot/upstream/source/claim provenance; B must stage synthetic covered money through actual committed proposals/reopen and validate the entire two-write Held or six-write Issued producer. Actual COVERED evidence can retain UNKNOWN, PART_PAID or PAID inventory exposure; a one-attempt fixture cannot justify silently excluding legitimate partial-payment history. Keep identical actor/custody/coverage/quote/revision/allocation checks for both outcomes, retain the original exclusive deadline, and do not create another cash movement. These source-backed corrections preserve F24's original requirements and leave its production/capacity gates open.

**High-priority A2 provenance boundary, now demonstrated:** `InventoryEffect.Movement` and `InventoryPosting.fromPlan` do not supply a new lot's expiry from the posted movement; two real receive plans can encode identical batches yet different complete cuts. `InventoryPostingCodecTest` also demonstrates source-free acceptance of an omitted first reservation record or entire receipt pair. Those are intentionally bounded historical-data APIs, not new proof of an accepting-path exploit. Using them alone as producer/current authority would admit missing metadata/effects. Required correction: bind full cut bytes and full original receipt/lot/expiry/upstream request, retain cumulative source/claim ownership, and validate the entire actual retained producer/composite. The live crosswalk separately rejects omissions; decoding must not manufacture a plan/proof/deadline or duplicate the planner. Evidence: `../ukkera-pos/.workspace/evidence/inventory-posting/20261023T233754.415983Z/verification.json`. F24 remains OPEN.

Additional F24 corrections resolved on24 October (D046/D047); the bounded A2
source has now passed independent review and target/full execution:

| Severity / evidence | Practical impact | Required correction / resolution |
| --- | --- | --- |
| High — `InventoryReceiptRepository.validateReceiptCut`, receipt companion shape and A2 source join; `Quantity.plus` uses checked Long | Receipt-only revision/count/producer equations reject source-free operations and multi-receipt batches; separate lifetime receipt sums reject valid stock after issues, while unordered net sums hide historical lot overdraft | Distinct retail-v1 profile; exact source/producer/fact partition and source-ordered per-position deltas. Genuine MAX-cancellation and historical-overdraw twins required. Do not widen receipt-v1 or reimplement valuation. |
| High — existing receipt producer verifies **exactly** two writes and no jobs; B requires two/six complete variants | Inventory-subset validation could silently admit missing/extra sale facts, money movement, claims or jobs | Separate unqualified facts/pure contributions from concrete full-producer dispatch. Closed fixture owns the complete accepting path; reject unknown versions. Constructor-trusted ports are not inherently unsafe, but unused generic acceptance abstraction is omitted. |
| High before B — `SalesSnapshotCodec.decodeCollection:84–91`, `SalesSnapshotLinks.requireCollection`, `Collections.recordOutcome` | Today's advanced/closed session is not the old producer's cash evidence; requiring it rejects valid history or misrepresents provenance | Add only historical intrinsic decoding through `StoredCollection.restore`; preserve current linked checks. B supplies historical composite links and fresh admission checks. No seventh cash write. |
| Medium — A1's private whole-cut profile and receipt private wire helpers | A2 could drift by cloning domain serializers/guards or broadening legacy parsing limits | Add factual public supported-snapshot validation and narrowly share scalar/provenance wire helpers; unchanged old codec semantics, literal legacy goldens and refusal-boundary tests required. |

No reservation-birth owner requirement was found in the existing contract; an
extra index/namespace is deliberately not added. Receipt/source ownership stays
mandatory. All added count ceilings are fixture bounds, never stock-capacity
promises. These resolutions do not close F24's production, frontier or capacity
gates.

**A2 bounded execution resolution:** the1,198-case target passed at01:15:28Z and
security independently audited its82 XMLs/239 additions/no removals. The full
01:29:14Z run passed2,008 cases/22 profiles/160 XMLs,53 harness tests and8,198
unchanged inputs; pricing independently accepted that exact packet without
discrepancy. These executions cover the source-ordered fold, whole provenance,
concrete fixture validators, historical decoder and literal legacy regressions.
The extra tests distinguish an existing-lot prefix overdraw from a missing balance,
inner A1 noncanonical data from outer formatting, and actual41-allocation decode
from encode-only coverage. No acceptance shortcut or guard was weakened. F24
remains OPEN for complete B composites, protected frontier/genesis/procurement,
production identity and pre-money capacity; coherent old-pair rollback still passes
as an explicit limitation, not a freshness proof.

**B bounded resolution, D051/D052:** four independent proposals, challenge and
resolution selected six private JVM fixture commands, not a production gateway.
The source-backed refinements below are required fixture corrections, not new
business requirements:

| Severity / evidence | Impact | Concrete correction / resolution |
| --- | --- | --- |
| High — `Collections.finalizePaid` protects Issued, while inventory planning can return Held; `CheckoutStoreFixture` final preflight | A Held path could retain another reservation or accept stale/unauthorized paid state without reaching the sale-domain guard | One common preflight before either branch checks exact actor/custody/assignments, every requested revision, quote/allocation links, actual resolved coverage and target reservation. Explicit zero permission; genuine wrong-reservation and Held duplicate-source twins now execute. |
| High — `Collections.recordOutcome`, `CheckoutLiveTranslation`, immutable command journal | Unknown has unchanged cash; historical overwritten rows alone lose prior observations. Extra cash writes or gating actual observations on stock safety can misstate money | Preserve exact immutable request and real after-writes; Unknown uses1/3 writes versus2/4 for the other outcomes. Derive exposure from actual after-collection, never caller enum. No extra cash event ledger or late-outcome stock gate; partial/mixed/zero literal oracles execute. |
| High — `CheckoutRetainedProducer` and A2 observe/project boundaries | Inventory-only or recursive/current-state reconstruction can mistake partial facts for complete historical/financial authority | Finite whole-envelope after-fact crosswalk, exact live source/plan identity, two-write Held/six-write Issued, complete explicit claims/jobs. Historical preimage arithmetic/auth/expiry/frontier remain unproved and tested as limitations. |
| Medium — fixture setup/claim declarations and domain uniqueness callbacks | Direct advanced seeding or owner-equal claim reuse masks actual transitions/duplicate admissions | Real open/create plus staged committed begin/attempt/outcome; freeze both writable kinds and claim namespaces. Existing equal-owner claims still block new operations; original-ID replay remains separate. |

Unnecessary proposals were withdrawn after concrete challenges: another public
preflight API, a customer-receipt row, recursive predecessor/producer selectors,
one-order/global-pending restriction and separate custody/exposure commands.
Existing domains and immutable journal already provide the bounded facts.

Security independently reviewed all13 Checkout JVM files and the exact minimal
compile corrections. The first B run failed before B execution on two prohibited
value-class varargs and one missing `suspend`; diagnostics are preserved, not
reported as passing tests. Fresh full02:51:11Z run passed2,129 cases/22 profiles/
167 XMLs,53 harness tests and8,211 unchanged inputs. Pricing independently
accepted all187 evidence files, live input membership and exact121 new JVM
method identities, with all2,008 A2 identities retained. Evidence details are in
`../ukkera-pos/VERIFICATION_REPORT.md`; specification is FINAL§4.4.

**Current F24 status: fixture milestone resolved; required production fixes
remain OPEN.** No accepting application checkout, protected current frontier,
production initialization/procurement or pre-money durable capacity exists.
The fixture's432,141-byte envelope bound is not a cumulative capacity guarantee.

### F25 — Medium: sealing authority does not guarantee lifecycle return progress

**Evidence:** core `sealWithoutReleasing` obtains lifetime Mutex while lifecycle owns `connectionLock`; an Unconfined queued waiter may execute inline when that inner mutex opens. Recovery/security agreed this is an outer-lock liveness issue, not a logical disclosure bypass.

**Impact:** blocking application continuation can delay close/rearm/another connection-lock user after uncommitted rights were safely revoked. An existing budget-observer test does not prove queued-waiter behavior.

**Required correction/resolution:** narrow documentation/claims and a deterministic bounded waiter regression separating already-Sealed/no-output authority from delayed lifecycle completion. Do not promise callback-free overall lifecycle or physical native completion. Broader lock/lifecycle redesign requires independent review if hard return-progress is required for a target service profile; do not widen this patch on an unproved assertion. The independently reviewed bounded regression executed in the current base host suite; platform/service timing and unconditional outer lifecycle progress remain open.

### F26 — High: callback exception fields cannot prove a request was not executed

**Evidence:** independent pricing review of isolated `ScopedRpcClientEngine.kt` catch at original lines297–301 and `internal/RpcBodyCodec.kt` custom-deserializer behavior. The candidate preserved ANY RpcFailure with nonnull requestId. Concrete case: actual Invoke committed, Success body arrives, custom decoder throws a different-ID RejectedBeforeExecution/SafeBeforeExecution failure, while Publication is denied.

**Impact:** a local codec fault can masquerade as authorized remote execution evidence and suggest retrying a possibly executed command. This is provenance hardening against faulty/custom serializers, not a demonstrated network-only privilege exploit.

**Required correction/resolution:** preserve only exact private per-call exception reference constructed after Response+Publication authorization, successful SDK publication and pending-context validation. Sanitize all local serializer failures to original request ID, actual send-ticket evidence/retry advice and trusted Encoding/Decoding phase. Do not trust field equality, a matching-ID object or a broad prior-publication flag. Backend authored four discriminators covering before-send encode, after-send success/error decode, spoofed phases/IDs and real authorized rejection versus denied publication. Pricing independently rereviewed the fix;45 scoped tests executed in each host profile within RPC125/118 passing tests. Financial retry safety still depends on application operation IDs; native/SDK integration gates remain separate.

### F27 — High integration gate: compiled SDK tests are not admitted runtime/API evidence

**Evidence:** isolated P2pKit core test dependency on RPC introduced strict-lock memberships for `slf4j-api:2.0.7` and `kotlinx-serialization-json-io`/`-jvm:1.11.0`. Four public-SDK/RPC tests compiled on JVM and Android but both runtime classpaths failed before execution. A partial `--write-locks` command was guard-rejected; a separate normal diagnostic failed core and RPC Android ABI comparisons against unchanged admitted baselines. Logs are retained in `../ukkera-pos/.workspace/evidence/p2p/`. **The complete supported `resolveAndLockAll` writer was not attempted.**

**Impact:** claiming these tests passed or treating JVM source compilation as SDK/API/native compatibility would conceal a required integration gap. Ad hoc test classpaths, shadow sources, partial locks or overwritten signatures could produce a green but nonreproducible, unreviewed candidate.

**Required correction/resolution:** preserve the exact reviewed work as an explicit **unapplied three-file overlay**, independently manifested/apply-checked after the tested26-file base. That preservation is complete; independent backend reconstruction verified both resulting trees, byte lengths/hashes/modes, all14 unchanged locks+verification metadata and154 archived XML files. Base core927/126 and RPC125/118 passed without the four overlay tests. Before admitting the overlay, perform supported complete lock/API maintenance on suitably provisioned local hosts, independently review dependency/signature deltas, and run actual strict SDK integration suites. No native/sleep/physical or sensitive POS approval follows even then. See [source-layer contract](../ukkera-pos/integrations/p2pkit/README.md). **OPEN gate**, not a new business requirement or an alleged original-plan omission.

### F28 — High: SQLite affinity can defeat current-row domain validation

**Evidence:** `core/database/.../MasterRoomDatabase.kt` original `SELECT *` current reads and `AtomicMasterStore.kt` raw DAO proposal validation; actual `CurrentPointReadTest.kt` replaces a valid revision2 with REAL2.25 and observes Room's `getLong` yield2. Same-byte TEXT payload can hydrate as BLOB bytes and BLOB claim owner as expected UUID text. Prior hydration admitted data before any byte bound. BLUE§2/6 and QA Q-DB require exact CAS/integrity, not coercion.

**Impact:** malformed storage may look like a valid expected revision/owner, hydrate excessive private data, silently overwrite corruption, or write an operation before only later detecting a mismatch. Handler-level reads do not protect a kernel validator that separately bypasses them.

**Required correction:** key-only scalar metadata queries validate SQL storage type, positive INTEGER revision and all text/blob byte bounds before any row hydration. Preserve missing versus invalid distinction; validate exact requested key/scope, canonical schema/UUID/hash and payload size/hash afterward. Hash and return the same bounded copy. Preserve transaction lifetime/cancellation, sanitize ordinary exceptions, and use this same strict view in both kernel CAS and claim validation, not raw DAO reads.

**Resolution/evidence:** lead/scalars implemented without changing schema1, entities or migration. Security independently source-reviewed and required three no-read-prepare kernel tests in addition to30 selected-row tests. Healthy twins and exact one evaluation establish reachability; zero AFTER_RESULT invocations and unchanged same-connection `total_changes` distinguish early refusal from persistence followed by rollback. All33 regressions executed in database168/28 during full1,389-case verification at21:56:25Z; exact test SHA3f415ba05e5f63afbf97365051b5116c7752c9052e7f9c4bb05df1d5b230a83e independently reviewed. **Resolved for selected matching-key reads/validation only**: not retagged-key detection, database-wide integrity, origin authenticity, disk rollback resistance or current frontier. App-shell's later review failed with provider rate limit; no final approval attributed.

### F29 — High: Room migration can restamp an unknown legacy identity

**Evidence:** pinned Room3.0.3 `RoomConnectionManager.kt` migration/validation/identity update ordering (lines219–235, before ordinary identity checking), original QA Q-DB upgrade/integrity requirements, and the new `core/database/.../ProtectedLocalSchema.kt`/`ProtectedLocalMigrationTest.kt`. Pricing's actual independent review found that compatible tables and guards with a missing/changed Room identity would otherwise be blessed as v2. A second counterexample replaced the identity column with NOCASE collation: uppercase32-byte old hash would satisfy an apparently exact text comparison that Room's original comparison rejects.

**Impact:** a migration could conceal unsupported/corrupted schema provenance, create new protected tables before refusal, or repair a tampered file instead of preserving it for diagnosis. A test checking only final empty tables cannot prove validation happened before DDL because rollback hides earlier writes.

**Required correction:** before any protected CREATE, verify actual user_version1, exactly one integer id42 identity row, canonical original hash as exact BLOB bytes, and all old immutable guard bodies. Reject missing/extra/malformed metadata and unexpected tables; never restamp unknown state. Preserve the original seven entity schemas/cells/guards and both schema exports. Use a genuinely generated v1 fixture; instrument attempted CREATE preparation/execution with healthy4/corrupt0 controls, compare full original tables and schema/identity after refusal, and compare all seven tables around each replay.

**Resolution:** lead implemented; pricing independently accepted nine exact frozen files after the collation correction. Ten new migration tests ran successfully in the first preflight, but that overall run failed three unrelated cancellation-identity assertions. Corrected combined verification is recorded separately in `../ukkera-pos/VERIFICATION_REPORT.md`; no approval of live migrations or schema authenticity follows. **This is a bounded metadata-drift gate, not a signature, a freshness proof or permission to restore an old writable backup.**

### F30 — High: stale local login-attempt permits can clear newer failures

**Evidence:** original T08 persistent local credential/session/throttle requirements; `core/security/.../Throttle.kt` intentionally models a generic paired reducer, with permits carrying keys/attempt ID but no incarnation/start capability; new `core/database/.../RoomPersistentThrottleStore.kt`. Backend/scalars independently demonstrated both changed account/credential/admission/policy races and later reservations reusing the same attempt ID. Start equality alone is insufficient when completed/new reservations share a monotonic tick.

**Impact:** delayed MATCH from an old verifier could clear newer lockout failures or complete a different attempt if the adapter trusts only an ID, a re-captured current incarnation or equal bucket revisions. Treating throttle completion as login would additionally miss the later session-issuance authorization/deadline cut.

**Required correction:** privately bind an attempt to the exact database/scope/actor/device, captured account revision/generation/credential revision and activated admission/policy revisions. Recheck inside every IMMEDIATE transaction before either callback; restrict two exact bucket keys and configured activated policy. Preserve unequal legitimate bucket counters. Serialize one-shot reserve and completion ownership, retire the owned completion before work even on cancellation/uncertain commit, and check original attempt-start continuity for both buckets. Ignore/deny foreign permits without consuming a legitimate one. Later sign-in still rechecks original boot/deadline/incarnations and atomically persists its own acceptance.

**Resolution:** backend implemented without changing existing reducers/interfaces; scalars independently accepted four source files and32 substantive JVM tests, including timely still-matching nine-way incarnation races, new-start/same-tick reuse, healthy twins, CAS interposition and actual outer-COMMIT cuts. The initial run exposed test-fixture exception-copying assumptions, not evidence of safe rollback at the assertions it never reached. Exact-reference assertions were retained using an explicit test-only noncopyable cancellation sentinel; independent re-review verified only the two construction changes and unchanged production bytes. Final rerun results belong in the verification report. **Not production login, OS-key, rollback-resistance or T08 completion.**

### F31 — Medium: malformed Unicode escapes a promised typed sales error

**Evidence:** `domain/sales/.../SalesSnapshotCodec.kt`, `boundSnapshot` strict `encodeToByteArray(throwOnInvalidSequence = true)` and the original `checkedSnapshot` catch list. Pricing inspected the local Kotlin stdlib source and found that an under-cap lone surrogate can throw CharacterCodingException outside the promised `SalesSnapshotRejectedException` contract. This is inherited code exposed by the new historical API, not a weakened collection-restoration path.

**Impact:** malformed local snapshot input follows an unexpected, platform-dependent exception path rather than the stable sanitized failure contract. The input still rejects; this is not demonstrated acceptance, authorization bypass or disclosure of a financial payload.

**Required correction:** explicitly translate CharacterCodingException to MALFORMED, preserving existing typed rejections and current linked validation. Test lone high/low surrogates with exact safe message/null cause and a valid supplementary-character roundtrip. Do not replace strict encoding with lossy substitution or catch arbitrary fatal errors.

**Resolution:** scalars implemented the narrow guard/test delta; pricing independently reversed it to the prior hashes and accepted revised source. The stronger `decodeCollection` block remains byte-identical. The high/low surrogate failures and valid supplementary-character twin executed in sales75/75 in the1,198-case A2 target and2,008-case full run; both packets were independently audited. This closes the bounded typed-error defect, not production/identity or native-platform gates.

### F32 — High: ACTIVE local-session state does not bind access-token validity

**Evidence:** original [BLUE] local identity/session contracts and [TASK] T08;
`core/security/.../Session.kt` (`LocalSessions`), `Principal.kt` and the pre-D053 four protected
tables contain account/session generation/status but no immutable token hash,
original credential/admission binding, issuer service context or access deadline.
`AtomicMasterStore` requires a trusted current source, but test authorization rows
and the unavailable production default do not implement that adapter. Backend's
initial borrowed-reader-only proposal would leave the required validity facts absent.

**Impact:** wiring ACTIVE flags directly as current authentication could grandfather
old sessions, restamp original bindings, miss expiry/revocation or confuse individual
concurrent-session generation with the account's allocation counter. This was a
missing production prerequisite, not evidence that the unavailable gateway admitted
an actual unauthorized customer request.

**Required correction / resolution:** D053's combined inert issuance/fifth-table/
same-writer source in FINAL§12.1 binds immutable original facts and live policy,
confines protected reads to the exact writer/source/principal object and performs
normal-return final checks before outer publication. Keep token hashing distinct
from entropy, constant-time bearer verification and current authorization. Preserve
all original login, move, throttling, refresh and recovery requirements; do not add
a default TTL or discard them to simplify this stage.

**Acceptance / status:**132 added source methods span core facts, canonical codecs,
real migrations and real-Room execute/lookup/expiry/rollback/concurrency/lifetime
behavior. D053's04:11:14Z full run passed2,312 cases, including all183 additions;
pricing independently accepted the exact archive and preserved B identities. The bounded
prerequisite does not complete T08. D055 subsequently supplies the bounded existing-
issuance PrincipalVerifier through the actual factory and separate kernel (FINAL§12.1),
not provisioning/password sign-in, refresh/rotation/recovery, qualified service
key/clock/transport or accepting composition. Those gates remain required.

### F33 — High: implicit rowid conflicts bypass logical-key REPLACE guards

**Evidence:** pre-fix `LocalAccessIssuanceSchema.kt` fifth-table `no_replace` checks
only `(scope,sessionId)` while the ordinary SQLite table has a globally unique
implicit rowid. With recursive triggers OFF, REPLACE can delete its conflicting
victim without invoking its DELETE guard. Security independently identified this;
lead's real bundled-SQLite regression actually succeeded when refusal was required:
rowid1/alias-rowid/OFF/same-scope in
`../ukkera-pos/.workspace/evidence/t08-focused/20261024T034139.418119Z/verification.json`.
The archived pre-fix production SHA is
`e29e52b412977c216bd346768e49a113b86509a3270ac47937f54999a5a748b2`.

**Impact:** internal/raw SQL could remove an immutable issuance using different
logical coordinates. Copied ciphertext is not a valid new bearer or authenticated
record; changed AAD still rejects. This is a registry-integrity/availability gap,
not demonstrated public-RPC injection. Normal DAOs use ABORT inserts/checked updates.

**Required correction / bounded resolution:** fifth `no_replace` now rejects the
logical tuple **OR global rowid**, outside any scope predicate. Keep all aliases and
negative/-1 values covered. An existing raw -1 may conservatively deny automatic
append because SQLite uses -1 as the BEFORE INSERT sentinel; do not silently skip
that case. Security reviewed the exact source; the same test passed its complete
rowid/alias/OFF-ON/scope matrix in the later03:50:43Z focused packet.

**Historical-table extension / resolved bounded correction:** the original seven
financial/four protected guard bodies in `DurableRoomProfile.kt`/
`ProtectedLocalSchema.kt` omit hidden rowid too; v4 now adds separate guards rather
than rewriting those preserved definitions.
Independent read-only follow-up also identifies `UPDATE OR REPLACE` rowid collisions
on seven mutable tables despite valid revision changes, plus branch/claim logical-
key collisions; these additional paths were initially static counterexamples. Four actual
regressions then failed at04:42:57Z against unchanged corrected v3: aggregate and
protected-policy rowid replacement, branch scope collision and claim key collision.
Each recorded sqlCompleted=true, changes=1, destructiveReplacementObserved=true,
and two target rows becoming one, with valid-revision/noncollision controls and
FKs enabled. Exact packet: `../ukkera-pos/.workspace/evidence/d054-focused/20261024T044206.139139Z/verification.json`,
SHA256 `37ee0ce771b5e6aaaa39c0d1ed09618b7024a135e42bc5e274b670b1f5bd9dd1`.
FKs can mask some parent replacements, not every case. Lead implemented D054's18
additive guards in `MasterReplacementGuards.kt`:11 global-rowid INSERT refusals
and7 scalar UPDATE rowid-immutability guards, plus branch/claim logical identity
immutability. Version4/registration are the only old production-file changes;
all33 old bodies,12 entities/indexes and exports1/2/3 remain unchanged. Existing
immutable UPDATE guards already cover four financial tables. FINAL§4.1 specifies
strict provenance/name preflight, genuine direct/historical routes and preserved
rowids/ciphertext/DDL, without repair or arbitrary-topology inspection.

**Acceptance:** security independently reviewed the exact source and nonvacuous
multirow INSERT/UPDATE oracles. The four frozen regression bytes passed; corrected
59-method focus and the06:17:07Z full cut passed, independently audited by pricing.
Full packet `../ukkera-pos/.workspace/evidence/runs/20261024T061200.914277Z/verification.json`,
SHA256 `e0794a09f111ce41d3f82f216b882ac00e3dc74db1ac11bc714c131d016db559`:
2,347 cases, every2,312 D053 identity+35,8,234 unchanged inputs. Migration tests
cover populated issuance, unusual rowids, all18 reserved names and actual DDL
faults1/9/18 with rollback/genuine-old reopen/retry; corrupt-FK sources stay refused
and preserved. The bounded v4 integrity fix is **RESOLVED** for this topology and
intact guards. Arbitrary extra UNIQUE constraints, DDL/file control, historical-
damage repair and authentication of raw writes are not claimed; earlier B/kernel
evidence is not retroactively reclassified as failed.

**Verification-only corrections (resolved, not public application vulnerabilities):**
- **Medium:** new migration-test raw observers could contend with a live Room
  connection;15/59 methods failed SQLite code5 in packet85266d83…. Pinned Room3
  source establishes asynchronous refresh/IMMEDIATE work as a possible cause,
  not the actual lock owner. `MasterReplacementTestSupport.observeReplacementRoom`
  now holds the correct sole writer outside a transaction while closing the
  unchanged raw observer. Only three test files changed; no retries, weakened
  assertions or production tuning. Security source-reviewed and pricing audited
  corrected a0d7cc2b… with59 passes.
- **Low:** `tools/tests/test_verification_evidence.py:305` still expected542 after
  both runners raised the database floor to577. Early full1362d7cc… stopped at
  52/53 harness passes, before Gradle/backend and without a source-after manifest.
  Lead changed only542→577; all53 identities remain. Pricing verified this fifth
  post-focus input delta, then independently accepted the new full invocation.
  Both failed packets remain failures, never retrospectively relabeled green.

### F34 — High: caller cancellation discarded a normally returned time observation

**Evidence:** pre-fix `RoomCurrentLocalAuthorizationSource.observeCurrent` ran its
post-callback `ensureActive()` before retaining high-water/null/discontinuity.
Security found the ordering gap. Two real-Room tests in the same03:42:51Z red packet
cancelled only the command child while the environment callback returned normally.
Exact cancellation and SQL rollback passed, but later fresh-job denial returned
`Recorded`. Pre-fix source SHA:
`583c9e0f5ad018e35621c74534414fac7dca8d58862367b58f24adcae5c5c3d2`.

**Impact:** the source could forget a genuinely observed later time or lost
continuity across a cancelled/rolled-back request, then admit a lower-time request
or revive an unavailable environment. Correctly propagating cancellation alone
did not preserve the independent service-lifetime safety evidence.

**Required correction / resolution:** retain the pre-observe cancellation check.
If the callback returns, synchronously record the sample/high-water or unavailable/
discontinuity latch first, then preserve caller cancellation, then enforce typed
denial. Provider errors must not mask cancellation. A callback that throws
CancellationException without returning a sample does not establish a fabricated
time observation or poison unrelated sessions. No further provider callback or
suspension follows the final sample. The bookkeeping intentionally survives SQL
rollback, and a stale/expired issuance alone never globally disables the source.

**Acceptance / status:** security reviewed the exact correction and unchanged
regression sources. Both methods passed in the03:50:43Z rerun, including all null/
boot/context variants and healthy-session controls. Together with F33 these were
three failed→passed identities, not an identical whole-source red/green tree:
two production fixes and separately reviewed README/floor changes intervened.
Full combined D053 execution and independent audit subsequently passed at
04:11:14Z; the two preceding internal compiler failures remain recorded. Qualified
suspend-inclusive platform time, fresh service contexts and disk rollback/fencing
remain open.

### F35 — High integration gate: SQL transaction checks do not prove unborrowed request entry

**Evidence:** D055 `RoomLocalAccessPrincipalVerifier.kt:65–88,119–155` and
`RoomCurrentLocalAuthorizationSource.kt:28–50`; pinned Room3.0.3 source
`ConnectionPoolImpl.kt:114–179` in the locally archived runtime sources jar.
Room reuses its thread-local/inherited coroutine connection wrapper before pool
acquisition. `RoomLocalAccessConcurrencyTest.kt:16–41` actually demonstrates two
children sharing an inherited bare wrapper. The source constructor checks exact
store reference but cannot establish which source a separately created kernel uses.

**Impact:** accepting arbitrary borrowed contexts can defeat the intended root-
transaction boundary or race shared continuity state. Separate source instances
can forget each other's high-water/latches even over the same database. The
characterization is not an experimentally demonstrated rollback escape, and no
accepting application is currently wired; this is an integration precondition,
not a claim that deployed requests were compromised.

**Required correction / resolution:** preserve the outer writer and enforce the
explicit supported composition contract: ordinary structured requests outside
all Room loans/transactions; no inherited/shared/retained/re-entered borrowed
contexts; factory call then kernel call separately; exact SAME store and D053
source instance. Qualify that lineage and provider non-re-entry before binding.
Arbitrary-context support needs a genuinely clean bounded executor design with
cancellation/shutdown and caller-held-loan deadlock handling, not dispatcher/Job
changes, caller-provided flags or private Room introspection. Another pool/process
remains unfenced. Peer/time observations do not become simultaneous/disclosure
fencing by holding a SQLite writer.

**Acceptance / status:** the internal unbound source contract was accepted after
actual peer challenge. Supported independent callers, both revocation orders and
cross-verifier/kernel high-water have passing real focused tests, all retained
in the independently audited08:35:31Z full cut. Mechanical
arbitrary-context enforcement, qualified transport/service composition and full
application acceptance remain OPEN; do not present comments as enforcement.

### F36 — High availability gate: real outer-recycle failure can make Room close spin

**Evidence:** failed D055 focus `20261024T074350.658457Z`, SHA256
`081c4782bb0ce8e1117eb718286b9e15749c37e86a198f9970c3e302a53b784d`;
archived07:55:14 JVM dump at
`../ukkera-pos/.workspace/evidence/d055-diagnostics/20261024T075600Z/worker-thread-dump.txt:14–21`.
The worker is in cleanup test finally → `RoomDatabase.close` → `CloseBarrier`.
Pinned `ConnectionPoolImpl.kt:171–176` can skip writer release after
`markRecycled` throws; invalidation can retain a close blocker while waiting for
that writer (`InvalidationTracker.kt:402–412`, `CloseBarrier.kt:83–96`).

**Impact:** an ordinary test finally can hide assertion outcomes and stall the
whole suite; a future service shutdown cannot assume this dependency failure is
cooperatively cancellable. The dump proves a close spin, not that preceding
assertions passed or that a principal escaped. Lead deliberately interrupted the
run rather than falsely record the configured timeout as observed.

**Required correction / resolution:** keep both real ordinary/cancellation
cleanup-fault variants in bounded isolated child JVMs, retaining exact refusal,
cancellation identity, COMMIT/final-check/fault counts and SAME capacity1 limiter
recovery. Only after assertions emit/flush an exact variant witness and halt0;
errors halt nonzero, never from a success-finally. Parent must seed/snapshot/close
the exact file first, require deadline/exit0/exact fresh output, bound output
reading, reap on failure, then compare all12 tables with a fresh parent Room
owner. Production composition still needs qualified bounded retirement/restart;
timeouts/IO dispatch cannot make spinning close safe, and opening another pool
does not fence the poisoned owner.

**Acceptance / status:** both child witnesses and all lifetime assertions passed
in the corrected focus and the separately audited full cut; first interruption
and the later unrelated grants-oracle
FAIL are preserved. This resolves the test-suite hang without weakening the
publication oracle. It does NOT claim same-pool recovery, graceful shutdown,
physical crash recovery or a completed production-service gate.

## 4. Safeguards deliberately retained; non-findings

The plan already handles many problems a shallow review might wrongly report as omissions:

- Device admission is not human authorization; central and branch sessions are separate, including native company administration.
- Exactly one local master, durable operation/result/outbox transaction, immutable snapshots and local offline return entitlement are explicit.
- Old-master fencing, post-seal recovery, optional standby, missing-tail disclosure and non-zero asynchronous RPO are already required. F01/F02 sharpen admission/issuance, not replace these with cloud lease/election.
- Payment initiation/verification is deliberately outside POS; cashier attestation is an accepted requirement, not an integration defect. Declining bank access cannot turn the product into false “bank approved” UI.
- Receiving, supplier invoice, cash settlement, claims and inventory valuation are separate, including branch PO allowances and designated settlement authority.
- Paid-unsent reservations, before-start issue reversal, after-start waste and unknown physical-print outcomes already exist.
- Retention, Arabic/RTL, accessibility, testing, backup and rollback are not missing wholesale. IMPL §12, line 179; TECH §7.3, line 157; QA Q-DB-08 already require safe upgrade/rollback and downgrade refusal. The final plan only clarifies that post-upgrade financial commits forbid a casual pre-upgrade snapshot rollback.
- The 40-task graph is acyclic and explicitly permits early children. All 141 scenarios and 79 gates remain unexecuted, not failed tests.
- New versions were found in publication metadata. This review does not label Kotlin 2.4.21, CMP 1.12.2 or Room3 3.0.3 fictitious. Nor does publication prove compatible builds.

## 5. Optional improvements — not release requirements added by this review

| ID | Optional improvement | Benefit / constraint |
| --- | --- | --- |
| O01 | Lightweight model checking of the authority/coverage state machine | Useful additional counterexample search for F01/F02; ordinary state-machine, crash/race and operational fencing proof remain required regardless. Do not build a generic distributed-consensus framework. |
| O02 | Authenticated offline first-install package | Useful only if a launch deployment needs first enrollment with no central access; DEC §3, line 84 already labels it an extension. Existing provisioned offline login/checkout is mandatory regardless. |
| O03 | More advanced diagnostic visualization of cost lineage/recovery coverage | Can aid support after the required safe logs, scoped status and reconciliation views exist. Must not expose private data or defer core correctness behind a dashboard project. |

Do not remove release-one activities/platforms/catalog/purchasing to shorten the schedule. Conversely, do not add payment-provider integration, full GL, customer credit, web admin or an arbitrary promotion optimizer as prerequisites.

## 6. Review verification and integrity record

The preserved source baselines use sorted workspace-relative paths; for each included file aggregate `relativePath + NUL + SHA256(fileBytes) + newline`. Exclude path components `.git`, `node_modules`, `.gradle`, `build`, `.kotlin`. This measures the handoff and identified KMP source sets, not the entire filesystem or unrelated P2pKit work.

| Set | Files | Baseline aggregate SHA-256 |
| --- | ---: | --- |
| Original handoff | 19 | `4dad42a51224f0fe664fe99626c8da914638802c34850ae93105dcf70ee3a520` |
| `kmp-apps` | 457 | `822dfed2b64664b47ef7a9f26a0c44a854470c33165866a8f489a5280991f4d6` |
| `kmp-features` | 1959 | `ce2260b19170af62f735862d4b848ffbcdcb081701dd18b5fd982fd1a3855e95` |

These baselines were rechecked unchanged after timed-out delegation. Both KMP repositories remained Git-clean. P2pKit had unrelated existing dirty work; this review used pinned `git show` and does not assert that whole checkout was clean. Only the lead writes the four requested review documents.

Final document verification on 22 October 2026 passed: exactly the four requested output files; 102 local link/reference resolutions; consistent Markdown table shapes; all 18 payload hashes/sizes and all three source aggregates above unchanged; both KMP Git trees clean; exact 141/141 CSV source-line/trigger/result mapping with all statuses still `NOT RUN`; 79 distinct original Q gates; the exact original 40-task table and acyclic dependencies; all 12 review-derived test groups; and independent document arithmetic for cash, tax/discount, stock/kitchen, supplier settlement, valuation and exchanges. No placeholder completion claims were found.

The preceding22October record certifies only that dated review. On23October, three actual revised-scope agent rounds completed and the owner authorized local implementation. Current application build/test evidence lives in `../ukkera-pos/VERIFICATION_REPORT.md`; no full scenario, deployment, native or production approval follows from these document checks. Remaining decisions/gates are in [OPEN-QUESTIONS.md](OPEN-QUESTIONS.md).

**24 October current source-cut evidence:** pricing separately accepted D055's
08:14:25Z focused and08:35:31Z full packets. The latter has2,411 cases/22 profiles/
186 XMLs,53 harness tests and8,245 unchanged inputs; every2,347 D054 identity plus
64 disjoint D055 cases remains. Exact208-file archive and full live source/backend
state were independently checked; packet SHA256
`4fea9cf8c8c60d71e1f92b08f5af240d4f0cb8eda8d2774096733c7541832236`.
Both real focused FAILs and prior failures remain preserved. This evidence
qualifies bounded source behavior, not F35/F36 accepting composition, production
sign-in/refresh, any original package or the five-platform POS. Current document
consistency audit is separate; a source-cut audit does not approve later wording.

[ARCH]: ../ukkera-pos-agent-handoff-2026-10-21/POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md
[OPS]: ../ukkera-pos-agent-handoff-2026-10-21/POS_OPERATIONAL_SPECIFICATION.md
[POL]: ../ukkera-pos-agent-handoff-2026-10-21/POS_COMPANY_POLICIES_AND_CATALOG.md
[PUR]: ../ukkera-pos-agent-handoff-2026-10-21/POS_PURCHASING_AND_RECEIVING_DESIGN.md
[BLUE]: ../ukkera-pos-agent-handoff-2026-10-21/POS_DOMAIN_AND_CONTRACT_BLUEPRINT.md
[TECH]: ../ukkera-pos-agent-handoff-2026-10-21/POS_TECHNOLOGY_AND_LIBRARY_GUIDE.md
[IMPL]: ../ukkera-pos-agent-handoff-2026-10-21/POS_IMPLEMENTATION_PLAN.md
[TASK]: ../ukkera-pos-agent-handoff-2026-10-21/POS_AGENT_TASKS.md
[QA]: ../ukkera-pos-agent-handoff-2026-10-21/POS_TEST_AND_QUALITY_PLAN.md
[DEC]: ../ukkera-pos-agent-handoff-2026-10-21/POS_OPEN_DECISIONS_REVIEW.md
[BE]: ../ukkera-pos-agent-handoff-2026-10-21/BACKEND_ARCHITECTURE_REVIEW.md
[S-GATEWAY]: ../kmp-features/features/auth/backend-viki/src/commonMain/kotlin/com/ukkera/features/auth/backend/viki/VikiIdentityGateway.kt
[S-AUTH]: ../kmp-features/features/auth/core/src/commonMain/kotlin/com/ukkera/features/auth/core/DefaultAuthRepository.kt
[S-SESSION]: ../kmp-features/features/auth/core/src/commonMain/kotlin/com/ukkera/features/auth/core/SessionManager.kt
[S-STORE]: ../kmp-features/features/auth/storage-default/src/jvmMain/kotlin/com/ukkera/features/auth/storage/JvmSecureSessionStorage.kt
[S-QUEUE]: ../kmp-apps/apps/queue/shared/backend-viki/src/commonMain/kotlin/com/ukkera/queue/backend/viki/VikiQueueUpdateOne.kt
[S-BUILD]: ../kmp-apps/build-logic/src/main/kotlin/com/ukkera/apps/buildlogic/application/UkkeraKmpApplicationPlugin.kt
[S-PARSER]: ../kmp-apps/build-logic/src/main/kotlin/com/ukkera/apps/buildlogic/definition/AppDefinitionParser.kt
[S-PROPS]: ../kmp-apps/gradle.properties
