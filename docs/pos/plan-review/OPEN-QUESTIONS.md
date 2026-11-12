# Ukkera POS — owner decisions and qualification gates

**Updated24 October2026 · Lead-authored · Actual agent rounds complete; implementation/platform gates remain**

Only genuinely external facts/operational decisions are asked of the owner. The accepted product architecture and delegated workflow design are preserved. Recommended options below are proposals, **not approvals inferred from silence**. No credentials or private source contents should be pasted into this document or notifications.

## 1. Owner/input register

### Q01 — Claude dependency: resolved by the owner's changed collaboration instruction

The owner explicitly authorized continuing without Claude and using the agent team, local-only. Three actual independent-agent rounds have now returned. No additional Claude repair is a current prerequisite, and historical failed delegate calls remain failures. Do not ask again or silently call Claude. Implementation/platform/security gates below remain; no completed-review claim implies release approval.

### Q02 — Who controls emergency recovery, and how is exclusive authority physically enforced?

- **Facts needed:** named company-delegated recovery users; primary/backup custody arrangement for recovery authorization; who can access/isolate the former master and possible replacement devices; how parallel recovery ceremonies are prevented; usable independent copy locations; tolerated data-loss/recovery-time objectives and maximum outage/offline expectations.
- **Recommended:** one controlled recovery incident/issuance ceremony at a time, one target-bound appointment, explicit old-writer/competing-target isolation and protected recovery material. Allow resource-specific degraded recovery after missing-tail loss, keeping uncertain refunds/stock/PO/cash under reconciliation rather than pretending all restored rights are unused.
- **Operational alternatives:** a qualified independent local standby can shorten RTO/reduce observed loss, or a no-standby branch can rely on verified central recovery when reachable. **Both are already allowed** and neither changes normal durable-master sale confirmation. A copy on the same disk is not an independent backup.
- **Unacceptable assumption:** that a cloud epoch, copied “one-use” signature or disconnected heartbeat failure guarantees one writer. If competing appointments cannot be excluded, activation must wait. If guaranteed zero-loss recovery is demanded after total master loss, escalate the conflict with the accepted asynchronous durability boundary; do not quietly require standby ACKs or cloud leases.
- **Blocks:** F01/F02 protocol acceptance and affected T25/T26 deployment claim. It does not reopen the accepted one-master/optional-standby/named-delegation requirements or block healthy local sales.
- **Closure evidence:** approved actual custody/fencing runbook; lost-reply/two-approver/two-target/returning-old-master rehearsal; measured copy-specific RPO and RTO; named approver for reconciliation readmission.

### Q03 — Protected backend runtime qualification remains; source access is resolved

- **Resolved facts:** authorized isolated local checkouts of viki-go/modules/main-next/module-manager/common-main-r are pinned in the implementation source manifest. Real generator and bounded Go route/crypto/scalar tests ran locally; existing YAML is preserved.
- **Remaining decision/facts:** an isolated disposable local Mongo/service fixture and intended service/app scope configuration; no production credentials in reports. Current typed evidence handler is not a mounted transactional production API.
- **Recommended:** finish protected domain↔native hierarchy mapping with per-level namespaced forward/reverse uniqueness, immutable references and parent checks, then qualify the actual authenticated typed runtime/Mongo transaction/ACK failure matrix. Missing, ambiguous or disabled mappings deny even replay; never auto-create mappings during ingestion.
- **Alternative:** an owner-supplied disposable fixture is acceptable if the lead cannot create the required local runtime. Historical generated records or fake in-memory store tests are not persistent-atomicity evidence.
- **Blocks:** backend runtime package acceptance, not pure domain/UI development. All work remains local; no remote deployment is authorized.

### Q04 — What physical deployment profiles must be supported at launch?

- **Facts needed:** actual Windows/macOS CPU/OS, Android phone/tablet models and OS, iPhone/iPad models/OS; installed/store/managed distribution; master/client assignment and mobility; branch/client counts, SKUs, daily/peak work and offline backlog; network/VLAN/AP/firewall/VPN/adapter arrangements; printer/scanner/scale/drawer/terminal models and connection protocols.
- **Recommended:** engineering proposes a modern compatible baseline, the owner identifies launch devices/legacy constraints, and the team qualifies the actual hardware. Retain all five platform roles and all four activities. Use the accepted P0/P1/P2 targets until actual workload is known; label them provisional.
- **Alternative:** specify required older OS/device constraints explicitly before dependency freeze; engineering then demonstrates a coherent supported graph rather than silently lowering or dropping platforms. Unsupported peripheral/OS combinations must be explained at setup, not confused with removal of general app support.
- **Network consequence:** strict P2pKit JVM hosting requires one up non-loopback interface and a safe selected interface; Docker bridges/dual NICs/VPNs can invalidate it. Recommend a separate/qualified central-service host arrangement. Do not disable owner VPNs or modify routing merely to make a spike pass.
- **Blocks:** T01 supported-profile freeze and actual T30/T33/T34 deployment/packaging/capacity claims. Source-only and simulator work cannot fill physical evidence gaps. Mobile-master lifecycle limits already accepted remain unchanged.
- **Closure evidence:** named profile matrix, signed/packaged installation where needed, 25 ordered core master/client pairs, actual iPhone/iPad, lifecycle/AT/peripheral/strict-network evidence and measured workload results.

### Q05 — What fiscal obligations and classifications apply to the launch legal entities?

- **Facts needed:** actual registrations, B2C/B2B document needs, applicable Egyptian invoice/e-receipt regime, product/service/exemption classifications, charge/deposit/tip/return treatment, legal numbering/rounding, receipt/delivery/claim evidence, supplied-but-unpaid recognition, submission/correction deadlines and outage rules. Identify authorized fiscal/accounting contact and securely provision official sandbox/production credentials when appropriate.
- **Recommended:** validate the initial profiles with the business's qualified fiscal/accounting/legal contact and applicable official requirements, then qualify the necessary connector and accounting mappings. Keep draft/test fixtures synthetic until actual authorized environments are available.
- **Alternative while facts are missing:** continue isolated engineering with hypothetical, explicitly labeled tax fixtures, but block the affected company's production fiscal/compliance claim. Do not declare “14% for everything,” infer full compliance from a tax-included checkbox, or remove an applicable connector from scope.
- **Policy baseline preserved:** category-specific CPA-informed 14-day change-of-mind/30-day defect remedies, covered defect resolution within a week, original-method refund and continuing rights; actual classifications/legal review refine this, not erase claims or silently shorten rights.
- **Blocks:** applicable T12/T20/T31 fiscal posting/launch acceptance, not all local domain development. Exact legal rounding must be reconciled with the advertised fixed payable discount in F07.
- **Closure evidence:** validated company profiles/rules, official sandbox behavior, failures/retries/corrections/outage treatment and authorized launch sign-off. The POS still only attests external payment outcomes; no new direct bank authorization product is implied.

### Q06 — What retention, legal holds and recovery-copy custody apply?

- **Facts needed:** longest applicable fiscal/financial, return/warranty/claim and supplier evidence periods; privacy/access and legal-hold requirements; maximum expected disconnected interval; permitted backup locations, who may recover keys and retention/retirement authority.
- **Recommended:** company-approved data-class retention matrix, locally available evidence for promised offline remedies, independent encrypted coherent copies, unresolved-claim/financial holds and minimal protected operation identity/tombstone/archive lookup preserving retry safety.
- **Alternative while facts are pending:** use synthetic long-horizon capacity/restore fixtures and retain necessary evidence conservatively; do not ship arbitrary pruning or silently assume indefinite retention of every secret is acceptable.
- **Blocks:** F11 retention/compaction production approval and associated capacity/recovery claims. Engineering, not the owner, chooses exact indexes, chunk sizes, queue policies and measured warning thresholds within these facts.
- **Closure evidence:** signed/configured windows and legal holds; measured storage/WAL/temporary-copy headroom; archive/old-backup/duplicate-after-compaction tests; authorized key restore and retirement of old copies without losing the sole usable generation.

### Q07 — Are there specifically required external integrations or legacy imports beyond the accepted baseline?

- **Facts needed:** names/versions/interfaces and launch-critical workflows for any accounting target, legacy data source, ecommerce or delivery marketplace the owner actually requires. Include migration counts, opening supplier balances, stock/lot/serial/value and documentary provenance if legacy import is needed.
- **Recommended:** preserve the existing required fiscal/accounting document/export baseline and commission only explicitly named additional connectors/imports with contracts and acceptance criteria. In absence of named systems, do not invent them or claim they are delivered.
- **Alternative:** record additional integrations as later explicitly approved scope when they are not launch-critical. Do not defer any integration the owner has already required or any fiscal connector determined applicable by Q05.
- **Blocks:** only those actual named integration/import launch claims. Not a reason to delay ordinary offline POS or native company administration.
- **Closure evidence:** interface specification, owner-approved mappings/reconciliation/error paths, safe import rehearsal and connector acceptance; no fictional opening purchases/sales to conceal migrated balances.

## 2. Engineering decisions — do not send these back as business questions

The owner has already delegated implementation choices. Main must resolve them with actual evidence and independent review, not ask the owner to design algorithms:

| Engineering item | Recommended decision / closure gate |
| --- | --- |
| F01 missing-history outcomes/readmission | Persist authority coverage and resource holds; test same-ID and new-ID replay, obligations, late-tail versus recovery adjustments. No undefined “safe absence.” |
| F02 appointment mechanism | Concrete serialized incident/issuance state and target-bound chain compatible with Q02's actual custody controls; prove exclusivity or fail closed. |
| F03/F04/F13/F15 transport/storage | Qualify employee-bound RPC disclosure (cache-zero/UI alone insufficient), strict topology, protected same-device service keys and TRUNCATE/FULL actual outer commits. No adjacent-key fallback or cloned recovery authority. |
| F05 backend routes/IDs/indexes | Source access and bounded generator/route probes resolved; finish native/domain mapping, tenant constraints and actual protected persistent runtime transaction/ACK tests. |
| F06 modules/dependencies | Standalone accepted `ukkera-pos`, selective pinned reuse, separate Android launcher. Latest **jointly compatible stable** graph, with requested P2pKit RC explicit. |
| F07/F14 pricing/posting algorithm | V2 constrained-after exact gross-to-net/caps; non-monotone per-request feasibility, canonical historical snapshots; reject unsupported Q05 fiscal coupling. |
| F08 moving-average correction | Recorded value lineage/reference-only replay and the bounded90/30 direction were independently challenged and accepted. Broader transfer/WIP/return/correction propagation and actual storage qualification still require proof. Keep original margin immutable. |
| F09 unsafe reservations | Explicit hold/reallocation/refund transitions; serialized safety/count/finalize/send races, no lost money or negative stock. |
| F10 task sequencing | Named early children without altering full 40-package dependencies or falsely completing 141 scenarios. |
| F11/F12 capacity/accessibility | Record-specific safe compaction and measured growth within Q06; numerical usability/AT gates on Q04 profiles. |
| Catalog/pricing implementation corrections | Priority-first effective selection, retained override high-water history, explicit group-greedy actual-posted selection, canonical per-selection paid spread; never silently promise a global optimizer or rewrite later legitimate discounts. |
| Scoped transport timing/platforms | Implement qualified suspend-inclusive SDK clock providers and error/export boundaries; ordinary resume callbacks are not pre-resume fencing. Bound lifecycle progress separately from logical disclosure safety. This is engineering work, not a request for the owner to choose clock APIs. |
| Scoped SDK lock/API admission | Base host tests passed; four separately preserved public-SDK tests compiled but did not execute under strict locks, and Android ABI checks failed. Perform supported complete lock/API maintenance and independent delta review before overlay admission. Full writer was not attempted. Do not ask the owner to select a bypass or call compilation runtime evidence. |
| Typed cash/stock/sale storage | Invoice/source authority before supplier settlement; complete inventory cut/claims/held effects and recovery footprint; standalone immutable sale fact; no new physical-money admission without reserved durable capacity. |
| Retained history/current origin | Receipt/A1/A2 and the closed B JVM fixture now have independent source/execution/evidence reviews. B stages actual open/create/begin/attempt/outcome/finalize commits with complete two/six-write Held/Issued variants and121 new methods. UNKNOWN/PART_PAID/PAID exposure still needs actual covered-money evidence. These are synthetic identities; production current frontier, protected genesis, procurement/finalizer and pre-money capacity remain unqualified. Coherent historical rollback still passes; no production gateway opts in. |
| F28 current-row SQL integrity | Selected matching-key type/byte preflight plus exact post-hydration binding implemented; kernel CAS/claim validation uses the same view.30 read tests and3 independent-validator controls passed. This does not diagnose retagged keys, authenticate origin or resist disk rollback. |
| T08/F29/F30/F32 protected storage | Original four-table storage/throttle work is separately qualified. D053 adds immutable issuance/fifth-table/current-writer authorization; D055 adds the internal existing-INTERACTIVE ULA1 PrincipalVerifier through the real factory, with separately audited corrected focused and full execution. Preserve256 history, revision0 credentials, terminal jumps, grants and genuine migration provenance. No production provider/default admin/login acceptance. Service-context keys/time, disk rollback policy, atomic successful-attempt/session/issuance, enrollment/password sign-in/reset/refresh/recovery and accepting composition remain required engineering work, not owner choices. |
| D055/F35/F36 composition and liveness | Prove initially unborrowed structured request lineage, no provider re-entry and SAME store/source wiring before accepting use. The contract does not mechanically clean arbitrary inherited Room contexts; dispatcher/Job changes are insufficient. Qualify bounded poisoned-service retirement/restart: child-process fault regressions establish refusal/file preservation, not same-pool recovery or cooperative close. Keep observational peer/time checks distinct from disclosure fencing. |
| F33/F34 replacement/continuity | Security-discovered fifth-rowid and cancellation-erased observation defects actually failed three regressions, then passed reviewed corrections. D054's later18 additive v4 guards and genuine fixtures also passed independent source, corrected59-method focus and full-evidence audits after four real destructive UPDATE regressions. Old definitions/rowids remain, including conservative raw -1 availability; arbitrary DDL/file control/freshness is not guaranteed. Observer-target and stale-floor harness failures remain recorded. No owner SQL/cancellation decision is needed. |
| Verification evidence | Latest independently audited full bounded run at24October08:35:31Z includes D055:2,411 host cases/22 profiles/186 XMLs plus53 harness tests;8,245 unchanged inputs. Exact disjoint union of all2,347 D054 identities and64 focused D055 cases. Pricing audited the208-file archive and full live input/backend state without blocking discrepancy. Android debug package/assembly and SOFTWARE smoke executed; desktop compile UP-TO-DATE; Apple MAIN targets10 executed/12 UP-TO-DATE. Fresh backend9 Node summaries/139 Go records, not fully retained raw test stdout. Both D055 focused FAILs and earlier compiler/observer/stale-floor failures remain preserved. No host run qualifies native runtime, production services, future edits or original acceptance gates; exact references are in VERIFICATION_REPORT.md. |

These are plan/implementation qualification blockers at their affected boundaries, not claims that the application already failed tests.

24 October engineering resolution (D046/D047), not a new owner question: A2 uses
unqualified inventory facts/pure contributions and concrete complete-producer
dispatchers; no unnecessary extension port or reservation birth index. Historical
collection decoding remains distinct from current linked admission. Source-ordered
physical folding avoids lifetime-total overflow and hidden historical overdrafts.
The bounded A2 source is now independently reviewed and qualified by its own
1,198-case target and2,008-case full archive, never by the preceding A1 run.
The later D051/D052 B debate, source review, full execution and independent
evidence audit now resolve only the closed staged JVM fixture (FINAL§4.4).
Protected runtime/frontier and pre-money capacity remain open engineering work.
The D053 source-backed debate resolved that choice as one combined immutable
issuance registry/current-authorization increment; ACTIVE session records alone
are insufficient. Source and targeted corrected regressions are independently
reviewed; complete D053 source-cut execution/audit passed at04:11:14Z, independently
from generated schemas and compile-only compiler repairs. Historical
replacement hardening subsequently passed D054's06:17:07Z full source-cut audit.
Lead/security then completed three D055 verifier-before-sign-in rounds and actual
source/oracle reviews. Corrected focus passed64 host cases/four Apple main klibs
after two preserved failures; pricing accepted that focus and separately accepted
the08:35:31Z full2411-case cut with exact2347+64 identity preservation. These
bounded verdicts do not waive F35/F36 or original platform/security gates.
Password login/refresh/runtime and accepting composition remain required
engineering work, not new owner questions or permission to expose a default login.

## 3. Settled decisions not reopened

Do **not** ask again whether to support all four activities, mobile master roles, one-device branches, native CMP company administration, branch-local accounts, owner-private orders, individual counted drawers, company price priority, no negative stock, kitchen issue timing, same-branch offline returns, external-payment attestation, optional standby or the excluded customer credit/balance/loyalty features. `ukkera-pos` and `BranchPos` are accepted working names.

Launch settings such as discount/approval ceilings, business-day cutoff, receipt layouts, enabled optional deposits/tips, return commercial extensions and peripheral assignment are handled by the already specified wizard and company policy. They are not a new round of foundational architecture approval; missing settings must be visible and never imply unlimited privilege.

## 4. Current readiness

**Ready for gated local implementation; not production-ready.** Q01 is resolved by revised owner instruction and actual rounds. F01/F02/F13/F15 runtime safety stop-lines remain at affected phases. Obtain Q02/Q03/Q04 evidence and Q05/Q06/Q07 facts for launch; documents and host tests cannot mark them passed.

The four review documents remain lead-authored. The owner separately authorized local implementation; actual progress/evidence is in `../ukkera-pos/`. No live migration, push, publication or deployment is authorized.
