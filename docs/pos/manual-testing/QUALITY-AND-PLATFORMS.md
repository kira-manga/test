# Quality gates and physical platform matrix

**NOT RUN / central portions HELD.** Required check text is copied unchanged from the frozen original register. Code/automated portions stay development gates; physical/owner observations are separate acceptance. QA-assisted fault/load/security tests require genuine production fixtures and controlled tools, not a fake successful app.

## Two-device role pairs

For each row use the original [RSC-002](RESILIENCE.md#rsc-002) inputs, actions and assertions. The environment is two distinct actual devices, an isolated synthetic branch, admitted encrypted LAN and the listed network profiles. Record a separate result per profile/build; one host or simulator cannot pass the other rows.

| Pair | Master | Client | Network | Result |
|---|---|---|---|---|
| PAIR-WINDOWS-WINDOWS | WINDOWS | WINDOWS | N1, N2 | NOT_RUN |
| PAIR-WINDOWS-MACOS | WINDOWS | MACOS | N1, N2 | NOT_RUN |
| PAIR-WINDOWS-ANDROID | WINDOWS | ANDROID | N1, N2 | NOT_RUN |
| PAIR-WINDOWS-IPHONE | WINDOWS | IPHONE | N1, N2 | NOT_RUN |
| PAIR-WINDOWS-IPAD | WINDOWS | IPAD | N1, N2 | NOT_RUN |
| PAIR-MACOS-WINDOWS | MACOS | WINDOWS | N1, N2 | NOT_RUN |
| PAIR-MACOS-MACOS | MACOS | MACOS | N1, N2 | NOT_RUN |
| PAIR-MACOS-ANDROID | MACOS | ANDROID | N1, N2 | NOT_RUN |
| PAIR-MACOS-IPHONE | MACOS | IPHONE | N1, N2 | NOT_RUN |
| PAIR-MACOS-IPAD | MACOS | IPAD | N1, N2 | NOT_RUN |
| PAIR-ANDROID-WINDOWS | ANDROID | WINDOWS | N1, N2 | NOT_RUN |
| PAIR-ANDROID-MACOS | ANDROID | MACOS | N1, N2 | NOT_RUN |
| PAIR-ANDROID-ANDROID | ANDROID | ANDROID | N1, N2 | NOT_RUN |
| PAIR-ANDROID-IPHONE | ANDROID | IPHONE | N1, N2 | NOT_RUN |
| PAIR-ANDROID-IPAD | ANDROID | IPAD | N1, N2 | NOT_RUN |
| PAIR-IPHONE-WINDOWS | IPHONE | WINDOWS | N1, N2 | NOT_RUN |
| PAIR-IPHONE-MACOS | IPHONE | MACOS | N1, N2 | NOT_RUN |
| PAIR-IPHONE-ANDROID | IPHONE | ANDROID | N1, N2 | NOT_RUN |
| PAIR-IPHONE-IPHONE | IPHONE | IPHONE | N1, N2 | NOT_RUN |
| PAIR-IPHONE-IPAD | IPHONE | IPAD | N1, N2 | NOT_RUN |
| PAIR-IPAD-WINDOWS | IPAD | WINDOWS | N1, N2 | NOT_RUN |
| PAIR-IPAD-MACOS | IPAD | MACOS | N1, N2 | NOT_RUN |
| PAIR-IPAD-ANDROID | IPAD | ANDROID | N1, N2 | NOT_RUN |
| PAIR-IPAD-IPHONE | IPAD | IPHONE | N1, N2 | NOT_RUN |
| PAIR-IPAD-IPAD | IPAD | IPAD | N1, N2 | NOT_RUN |

## Protected same-device profiles

For each profile use [RSC-003](RESILIENCE.md#rsc-003), the actual protected adapter, real persistence and the original current-authority assertions. Unsupported loopback or direct UI database writes cannot substitute.

| Platform | Required adapter | Result |
|---|---|---|
| WINDOWS | Protected same-device | NOT_RUN |
| MACOS | Protected same-device | NOT_RUN |
| ANDROID | Protected same-device | NOT_RUN |
| IPHONE | Protected same-device | NOT_RUN |
| IPAD | Protected same-device | NOT_RUN |

## Original engineering gates

For each gate: (1) establish the named original fixture/environment, (2) execute the required check text below, using the linked independent case actions when available, (3) compare every stated invariant/threshold on the actual boundary, and (4) record actual results and missing prerequisites separately. Requirements that are code-only remain automated checks, not a new owner obligation. Physical/OS/fiscal claims require their specified real-world evidence. Central-only checks must not be executed now.

## Q-DB-01

**Required steps and expected observations (unchanged):** Export versioned schemas; fresh install plus every supported production-version upgrade, direct/chained paths as applicable; migrated schema equivalent to fresh schema; no `fallbackToDestructiveMigration` for user databases

**Local obligations:**
- Export versioned schemas; fresh install plus every supported production-version upgrade, direct/chained paths as applicable; migrated schema equivalent to fresh schema; no `fallbackToDestructiveMigration` for user databases

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-041](RESILIENCE.md#rsc-041)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L151) · **Result:** NOT_RUN.

## Q-DB-02

**Required steps and expected observations (unchanged):** Preserve row counts, identities, relationships, encrypted values, null/default rules, posted totals, return entitlement and operation IDs; test corrupted/wrong schema rejection without reset

**Local obligations:**
- Preserve row counts, identities, relationships, encrypted values, null/default rules, posted totals, return entitlement and operation IDs; test corrupted/wrong schema rejection without reset

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-041](RESILIENCE.md#rsc-041)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L152) · **Result:** NOT_RUN.

## Q-DB-03

**Required steps and expected observations (unchanged):** Inject failure before/after each meaningful write in a command; operation result, stock/cash/entitlement/audit/outbox commit all-or-none; retry from a fresh process/connection

**Local obligations:**
- Inject failure before/after each meaningful write in a command; operation result, stock/cash/entitlement/audit/outbox commit all-or-none; retry from a fresh process/connection

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-008](RESILIENCE.md#rsc-008), [RSC-009](RESILIENCE.md#rsc-009), [RSC-010](RESILIENCE.md#rsc-010), [RSC-012](RESILIENCE.md#rsc-012), [RSC-013](RESILIENCE.md#rsc-013), [RSC-014](RESILIENCE.md#rsc-014), [RSC-018](RESILIENCE.md#rsc-018), [RSC-039](RESILIENCE.md#rsc-039), [RSC-058](RESILIENCE.md#rsc-058), [RSC-069](RESILIENCE.md#rsc-069)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L153) · **Result:** NOT_RUN.

## Q-DB-04

**Required steps and expected observations (unchanged):** Use production SQLite driver, configured journal mode and durability/flush policy on each platform; test process kill and actual power/device shutdown separately; record filesystem/device limitations rather than assuming all hardware honors flush identically

**Local obligations:**
- Use production SQLite driver, configured journal mode and durability/flush policy on each platform; test process kill and actual power/device shutdown separately; record filesystem/device limitations rather than assuming all hardware honors flush identically

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-039](RESILIENCE.md#rsc-039)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L154) · **Result:** NOT_RUN.

## Q-DB-05

**Required steps and expected observations (unchanged):** Enforce exclusive writer instance and branch identity; two app/service processes, duplicate launch and wrong database path cannot create competing writers

**Local obligations:**
- Enforce exclusive writer instance and branch identity; two app/service processes, duplicate launch and wrong database path cannot create competing writers

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-003](RESILIENCE.md#rsc-003), [RSC-020](RESILIENCE.md#rsc-020), [RSC-023](RESILIENCE.md#rsc-023)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L155) · **Result:** NOT_RUN.

## Q-DB-06

**Required steps and expected observations (unchanged):** Disk full, read-only/corrupt pages, failed fsync/storage API, low-space during snapshot/import/migration and interrupted transaction produce a recoverable failure, never silent sale success or discarded backlog

**Local obligations:**
- Disk full, read-only/corrupt pages, failed fsync/storage API, low-space during snapshot/import/migration and interrupted transaction produce a recoverable failure, never silent sale success or discarded backlog

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-009](RESILIENCE.md#rsc-009), [RSC-040](RESILIENCE.md#rsc-040)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L156) · **Result:** NOT_RUN.

## Q-DB-07

**Required steps and expected observations (unchanged):** Query plans and measured lookup/last-unit/return-entitlement/ledger queries use justified indexes at P2 scale; pagination is stable and bounded; readers do not indefinitely starve writes

**Local obligations:**
- Query plans and measured lookup/last-unit/return-entitlement/ledger queries use justified indexes at P2 scale; pagination is stable and bounded; readers do not indefinitely starve writes

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-045](RESILIENCE.md#rsc-045)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L157) · **Result:** NOT_RUN.

## Q-DB-08

**Required steps and expected observations (unchanged):** Migration interruption rolls back or completes atomically under the chosen engine; unsupported downgrade refuses safely. Restore a supported old backup through its compatibility path before enabling writes

**Local obligations:**
- Migration interruption rolls back or completes atomically under the chosen engine; unsupported downgrade refuses safely. Restore a supported old backup through its compatibility path before enabling writes

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-041](RESILIENCE.md#rsc-041), [RSC-042](RESILIENCE.md#rsc-042), [RSC-043](RESILIENCE.md#rsc-043)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L158) · **Result:** NOT_RUN.

## Q-DB-09

**Required steps and expected observations (unchanged):** Consistent backup includes WAL/journal cut and all required state; live-file copying without a consistency protocol fails review; large transfer/import/restore is streaming/bounded and cancellation-safe

**Local obligations:**
- Consistent backup includes WAL/journal cut and all required state; live-file copying without a consistency protocol fails review; large transfer/import/restore is streaming/bounded and cancellation-safe

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-024](RESILIENCE.md#rsc-024), [RSC-025](RESILIENCE.md#rsc-025), [RSC-027](RESILIENCE.md#rsc-027)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L159) · **Result:** NOT_RUN.

## Q-DB-10

**Required steps and expected observations (unchanged):** Protected credential/recovery material, device-key non-cloning, token invalidation and cross-branch restore rejection are tested; corrupt/truncated/tampered/wrong-key backup leaves the old usable state intact

**Local obligations:**
- Protected credential/recovery material, device-key non-cloning, token invalidation and cross-branch restore rejection are tested; corrupt/truncated/tampered/wrong-key backup leaves the old usable state intact

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-026](RESILIENCE.md#rsc-026), [RSC-028](RESILIENCE.md#rsc-028), [RSC-037](RESILIENCE.md#rsc-037), [RSC-040](RESILIENCE.md#rsc-040)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L160) · **Result:** NOT_RUN.

## Q-BE-01

**Required steps and expected observations (unchanged):** Pin compatible repository commits; leave existing YAML unchanged; strict parse rejects duplicate keys, bad relations, unsupported properties and cross-scope references in the new BranchPos definition

**Local obligations:**


**Verification needs (unchanged):**


**Detailed existing cases:** Original gate and fixture specification; unresolved detailed tooling/design is BLOCKED, not waived.

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Pin compatible repository commits; leave existing YAML unchanged; strict parse rejects duplicate keys, bad relations, unsupported properties and cross-scope references in the new BranchPos definition

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L168) · **Result:** DEFERRED_CENTRAL.

## Q-BE-02

**Required steps and expected observations (unchanged):** Run the actual source YAML → runtime definitions → Go documents → registry generation path in isolation. Diff generated output, verify expected registration/types/indexes and compile the affected Go service; a config-only GraphQL shortcut is insufficient

**Local obligations:**


**Verification needs (unchanged):**


**Detailed existing cases:** Original gate and fixture specification; unresolved detailed tooling/design is BLOCKED, not waived.

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Run the actual source YAML → runtime definitions → Go documents → registry generation path in isolation. Diff generated output, verify expected registration/types/indexes and compile the affected Go service; a config-only GraphQL shortcut is insufficient

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L169) · **Result:** DEFERRED_CENTRAL.

## Q-BE-03

**Required steps and expected observations (unchanged):** Start a disposable MongoDB replica set plus required auth/app services with explicit local URLs. Seed synthetic application/organization/company/branch/grants; use real login/application headers outside any development authorization bypass

**Local obligations:**


**Verification needs (unchanged):**


**Detailed existing cases:** Original gate and fixture specification; unresolved detailed tooling/design is BLOCKED, not waived.

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Start a disposable MongoDB replica set plus required auth/app services with explicit local URLs. Seed synthetic application/organization/company/branch/grants; use real login/application headers outside any development authorization bypass

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L170) · **Result:** DEFERRED_CENTRAL.

## Q-BE-04

**Required steps and expected observations (unchanged):** Verify generated schema/read behavior and custom authorized management/ingestion commands. Generic model, bulk, cascade, delete and GraphQL paths cannot mutate posted operational ledgers or bypass branch scope

**Local obligations:**


**Verification needs (unchanged):**


**Detailed existing cases:** Original gate and fixture specification; unresolved detailed tooling/design is BLOCKED, not waived.

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Verify generated schema/read behavior and custom authorized management/ingestion commands. Generic model, bulk, cascade, delete and GraphQL paths cannot mutate posted operational ledgers or bypass branch scope

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L171) · **Result:** DEFERRED_CENTRAL.

## Q-BE-05

**Required steps and expected observations (unchanged):** Reproduce and repair the previously reviewed fail-open/overwritten-filter concerns: unknown permissions denied, model enforcement active, multiple conditions composed safely, sync filters cannot override tenant restrictions, direct and related IDs scoped

**Local obligations:**


**Verification needs (unchanged):**


**Detailed existing cases:** Original gate and fixture specification; unresolved detailed tooling/design is BLOCKED, not waived.

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Reproduce and repair the previously reviewed fail-open/overwritten-filter concerns: unknown permissions denied, model enforcement active, multiple conditions composed safely, sync filters cannot override tenant restrictions, direct and related IDs scoped

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L172) · **Result:** DEFERRED_CENTRAL.

## Q-BE-06

**Required steps and expected observations (unchanged):** Central ingestion atomically stores deduplication identity, operation payload consistency, branch sequence, posted records, checkpoint and outbox. Parallel replay and failure before/after commit/ACK cannot create a second financial effect

**Local obligations:**


**Verification needs (unchanged):**


**Detailed existing cases:** Original gate and fixture specification; unresolved detailed tooling/design is BLOCKED, not waived.

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Central ingestion atomically stores deduplication identity, operation payload consistency, branch sequence, posted records, checkpoint and outbox. Parallel replay and failure before/after commit/ACK cannot create a second financial effect

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L173) · **Result:** DEFERRED_CENTRAL.

## Q-BE-07

**Required steps and expected observations (unchanged):** Transaction failure, replica-set primary change, duplicate unique key, timeout, service restart and consumer retry are tested. Redis/cache/queue loss cannot lose the authoritative operation or sole job record

**Local obligations:**


**Verification needs (unchanged):**


**Detailed existing cases:** Original gate and fixture specification; unresolved detailed tooling/design is BLOCKED, not waived.

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Transaction failure, replica-set primary change, duplicate unique key, timeout, service restart and consumer retry are tested. Redis/cache/queue loss cannot lose the authoritative operation or sole job record

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L174) · **Result:** DEFERRED_CENTRAL.

## Q-BE-08

**Required steps and expected observations (unchanged):** Master service credentials cannot impersonate company administrators or another branch; branch bearer tokens are rejected at central admin boundaries and the converse. Development/training data cannot enter live ingestion

**Local obligations:**
- Local branch financial interfaces reject central-admin/service identity as employee authority; retain separate credential stores. Training events cannot be queued into live local upload/fiscal paths.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-059](RESILIENCE.md#rsc-059)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Actual central rejection of branch bearer tokens and wrong-branch/master impersonation, and live central ingestion rejection of training/development data.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L175) · **Result:** NOT_RUN.

## Q-BE-09

**Required steps and expected observations (unchanged):** CMP company administration authors actual catalog/policy/report requests; pending/applied/rejected versions and offline branch freshness are truthful. Report field redaction/export/alert access is enforced server-side

**Local obligations:**
- Native app exposes correct local/delegated workflows and clearly pending global drafts; validate local package activation/version status and local report-field/export/alert access against real services. Do not claim successful central authoring from a fake service.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-068](RESILIENCE.md#rsc-068)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Actual CMP-to-central authenticated catalog/policy/report requests, central field/export/alert filtering and consolidated freshness.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L176) · **Result:** NOT_RUN.

## Q-BE-10

**Required steps and expected observations (unchanged):** SDK/generator stale exports and related compile failures are resolved deliberately, not by inventing empty business models. Schema/client/Go/Kotlin protocol compatibility and unsupported-version rejection have tests

**Local obligations:**
- Local command/read/event/snapshot version readers and writers round-trip exact values and reject incompatible participants before mutation; preserve compatible pending operations.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-042](RESILIENCE.md#rsc-042)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Generator/SDK export repair and real generated Go/central schema/client compatibility.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L177) · **Result:** NOT_RUN.

## Q-SEC-01

**Required steps and expected observations (unchanged):** Pairing token replay, wrong branch/device certificate or key, changed master identity, stolen/retired device, encrypted-transport failure and unauthorized procedure invocation; discovery is not trust

**Local obligations:**
- Pairing token replay, wrong branch/device certificate or key, changed master identity, stolen/retired device, encrypted-transport failure and unauthorized procedure invocation; discovery is not trust

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-001](RESILIENCE.md#rsc-001), [RSC-006](RESILIENCE.md#rsc-006)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L187) · **Result:** NOT_RUN.

## Q-SEC-02

**Required steps and expected observations (unchanged):** Real offline P2pKit discovery/session/RPC on supported platforms/topologies; no hidden public signaling/relay/cloud prerequisite for provisioned LAN checkout; startup without WAN is a separate case

**Local obligations:**
- Real offline P2pKit discovery/session/RPC on supported platforms/topologies; no hidden public signaling/relay/cloud prerequisite for provisioned LAN checkout; startup without WAN is a separate case

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-002](RESILIENCE.md#rsc-002), [RSC-003](RESILIENCE.md#rsc-003), [RSC-005](RESILIENCE.md#rsc-005), [RSC-006](RESILIENCE.md#rsc-006), [RSC-007](RESILIENCE.md#rsc-007)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L188) · **Result:** NOT_RUN.

## Q-SEC-03

**Required steps and expected observations (unchanged):** Login guessing with persistent rate limit, process restart, credential reset/revocation, token replay/refresh rotation/expiry and local account disable; no plaintext passwords/tokens in logs or backups

**Local obligations:**
- Login guessing with persistent rate limit, process restart, credential reset/revocation, token replay/refresh rotation/expiry and local account disable; no plaintext passwords/tokens in logs or backups

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-001](RESILIENCE.md#rsc-001), [RSC-016](RESILIENCE.md#rsc-016), [RSC-037](RESILIENCE.md#rsc-037), [RSC-044](RESILIENCE.md#rsc-044)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L189) · **Result:** NOT_RUN.

## Q-SEC-04

**Required steps and expected observations (unchanged):** Two users sequentially use one device and one account moves between admitted devices; Koin scopes/caches/UI/private drafts stay isolated while original unresolved operation attribution remains protected

**Local obligations:**
- Two users sequentially use one device and one account moves between admitted devices; Koin scopes/caches/UI/private drafts stay isolated while original unresolved operation attribution remains protected

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-015](RESILIENCE.md#rsc-015), [RSC-052](RESILIENCE.md#rsc-052)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L190) · **Result:** NOT_RUN.

## Q-SEC-05

**Required steps and expected observations (unchanged):** Stale/changed amount, document, actor or scope invalidates a manager approval. Named self-approval applies only to expressly granted actions; no self-elevation by editing request roles/limits

**Local obligations:**
- Stale/changed amount, document, actor or scope invalidates a manager approval. Named self-approval applies only to expressly granted actions; no self-elevation by editing request roles/limits

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-032](RESILIENCE.md#rsc-032), [RSC-063](RESILIENCE.md#rsc-063)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L191) · **Result:** NOT_RUN.

## Q-SEC-06

**Required steps and expected observations (unchanged):** Permission revocation delivered during an active session is enforced after atomic local activation on commands, reads, subscriptions, exports and cached sensitive views. Offline pending central intent is reported honestly

**Local obligations:**
- After authentic local activation of a revocation, enforce it on local commands/queries/subscriptions/exports/caches even if a notification was lost; show the currently applied version while offline.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-016](RESILIENCE.md#rsc-016), [RSC-018](RESILIENCE.md#rsc-018), [RSC-019](RESILIENCE.md#rsc-019)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Actual central revocation authoring, pending-intent reporting and authenticated central delivery/acknowledgement.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L192) · **Result:** NOT_RUN.

## Q-SEC-07

**Required steps and expected observations (unchanged):** Malformed/oversized/deep payloads, integer overflow, invalid enum, duplicate fields, partial frames, decompression/decoding limits, request flooding and slow consumer cannot exhaust unbounded memory or bypass validation

**Local obligations:**
- Malformed/oversized/deep payloads, integer overflow, invalid enum, duplicate fields, partial frames, decompression/decoding limits, request flooding and slow consumer cannot exhaust unbounded memory or bypass validation

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-007](RESILIENCE.md#rsc-007), [RSC-017](RESILIENCE.md#rsc-017), [RSC-027](RESILIENCE.md#rsc-027)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L193) · **Result:** NOT_RUN.

## Q-SEC-08

**Required steps and expected observations (unchanged):** Diagnostics, crash reports, analytics, screenshots/app-switcher state, copied data and notifications contain no credential secrets or unauthorized customer/margin data; exports have explicit scope and access

**Local obligations:**
- Diagnostics, crash reports, analytics, screenshots/app-switcher state, copied data and notifications contain no credential secrets or unauthorized customer/margin data; exports have explicit scope and access

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-017](RESILIENCE.md#rsc-017), [RSC-049](RESILIENCE.md#rsc-049), [RSC-069](RESILIENCE.md#rsc-069)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L194) · **Result:** NOT_RUN.

## Q-SEC-09

**Required steps and expected observations (unchanged):** Reject cross-branch/environment/training-live replay and stale-authority writes, old bearer tokens or invalid replayed approval proofs. After an approved epoch change, a freshly authenticated authorized caller can look up/recover the same stable branch operation ID and receive its original committed result without repeating effects; epoch change is not itself a payload mismatch. A missing unreplicated outcome or late old-master write follows explicit reconciliation rather than automatic new execution

**Local obligations:**
- Reject cross-branch/environment/training-live replay and stale-authority writes, old bearer tokens or invalid replayed approval proofs. After an approved epoch change, a freshly authenticated authorized caller can look up/recover the same stable branch operation ID and receive its original committed result without repeating effects; epoch change is not itself a payload mismatch. A missing unreplicated outcome or late old-master write follows explicit reconciliation rather than automatic new execution

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-011](RESILIENCE.md#rsc-011), [RSC-035](RESILIENCE.md#rsc-035), [RSC-036](RESILIENCE.md#rsc-036), [RSC-038](RESILIENCE.md#rsc-038)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L195) · **Result:** NOT_RUN.

## Q-SEC-10

**Required steps and expected observations (unchanged):** Dependency, secret and license checks are reviewed; known critical vulnerabilities affecting shipped paths have no unexamined exception. Crypto uses qualified platform/library primitives, not homemade encryption

**Local obligations:**
- Dependency, secret and license checks are reviewed; known critical vulnerabilities affecting shipped paths have no unexamined exception. Crypto uses qualified platform/library primitives, not homemade encryption

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-064](RESILIENCE.md#rsc-064), [RSC-070](RESILIENCE.md#rsc-070)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L196) · **Result:** NOT_RUN.

## Q-RES-01

**Required steps and expected observations (unchanged):** Last unit; simultaneous return; kitchen start/cancel; drawer close/payment; handoff/payment; split/merge/edit; receipt/PO allowance; approval/policy activation — each race matches one valid state transition and conserved totals

**Local obligations:**
- Last unit; simultaneous return; kitchen start/cancel; drawer close/payment; handoff/payment; split/merge/edit; receipt/PO allowance; approval/policy activation — each race matches one valid state transition and conserved totals

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-012](RESILIENCE.md#rsc-012), [RSC-014](RESILIENCE.md#rsc-014), [RSC-019](RESILIENCE.md#rsc-019)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L206) · **Result:** NOT_RUN.

## Q-RES-02

**Required steps and expected observations (unchanged):** Repeated request after lost response and same ID/different payload across restart preserves original outcome/actor; transport request IDs are not the only durable deduplication identity

**Local obligations:**
- Repeated request after lost response and same ID/different payload across restart preserves original outcome/actor; transport request IDs are not the only durable deduplication identity

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-008](RESILIENCE.md#rsc-008), [RSC-009](RESILIENCE.md#rsc-009), [RSC-010](RESILIENCE.md#rsc-010), [RSC-011](RESILIENCE.md#rsc-011), [RSC-012](RESILIENCE.md#rsc-012), [RSC-013](RESILIENCE.md#rsc-013), [RSC-029](RESILIENCE.md#rsc-029), [RSC-031](RESILIENCE.md#rsc-031), [RSC-036](RESILIENCE.md#rsc-036)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L207) · **Result:** NOT_RUN.

## Q-RES-03

**Required steps and expected observations (unchanged):** 24-hour N2 workload with local login/refresh, sale, return, kitchen, count and receiving; no cloud lease expires ordinary operation; durable backlog survives restart

**Local obligations:**
- 24-hour N2 workload with local login/refresh, sale, return, kitchen, count and receiving; no cloud lease expires ordinary operation; durable backlog survives restart

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-004](RESILIENCE.md#rsc-004)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L208) · **Result:** NOT_RUN.

## Q-RES-04

**Required steps and expected observations (unchanged):** Partition affects one client but master serves others; never promote by health timeout. Reconnecting clients revalidate identity and query pending outcomes before issuing fresh actions

**Local obligations:**
- Partition affects one client but master serves others; never promote by health timeout. Reconnecting clients revalidate identity and query pending outcomes before issuing fresh actions

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-005](RESILIENCE.md#rsc-005), [RSC-018](RESILIENCE.md#rsc-018), [RSC-021](RESILIENCE.md#rsc-021), [RSC-022](RESILIENCE.md#rsc-022)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L209) · **Result:** NOT_RUN.

## Q-RES-05

**Required steps and expected observations (unchanged):** Planned transfer interrupted in Preparing/Draining/Sealed/Activated/Completed; idempotent handover ID/checkpoint; abort only before seal; after seal no arbitrary reopening of old writer

**Local obligations:**
- Planned transfer interrupted in Preparing/Draining/Sealed/Activated/Completed; idempotent handover ID/checkpoint; abort only before seal; after seal no arbitrary reopening of old writer

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-030](RESILIENCE.md#rsc-030), [RSC-031](RESILIENCE.md#rsc-031), [RSC-032](RESILIENCE.md#rsc-032)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L210) · **Result:** NOT_RUN.

## Q-RES-06

**Required steps and expected observations (unchanged):** Emergency replacement requires the specifically delegated branch user and prepared local recovery credentials; generic manager role, a backup alone or an internet heartbeat never appoints a master

**Local obligations:**
- Emergency replacement requires the specifically delegated branch user and prepared local recovery credentials; generic manager role, a backup alone or an internet heartbeat never appoints a master

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-028](RESILIENCE.md#rsc-028), [RSC-032](RESILIENCE.md#rsc-032), [RSC-033](RESILIENCE.md#rsc-033), [RSC-034](RESILIENCE.md#rsc-034)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L211) · **Result:** NOT_RUN.

## Q-RES-07

**Required steps and expected observations (unchanged):** Old master offline during replacement, later restarts with stale role: operational isolation remains until controlled retirement/reconciliation; no automatic acceptance of competing writes or blind old outbox replay

**Local obligations:**
- Old master offline during replacement, later restarts with stale role: operational isolation remains until controlled retirement/reconciliation; no automatic acceptance of competing writes or blind old outbox replay

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-023](RESILIENCE.md#rsc-023), [RSC-035](RESILIENCE.md#rsc-035)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L212) · **Result:** NOT_RUN.

## Q-RES-08

**Required steps and expected observations (unchanged):** Restore from standby, central recovery package, old compatible schema, incomplete/corrupt/stale copy and no independent copy. Verify full checkpoint coverage and show possible missing tail; no fabricated zero-loss claim

**Local obligations:**
- Restore actual local/standby coherent checkpoints, compatible old schema fixtures and reject incomplete/corrupt/stale copies. With no independent copy show the missing-tail/recovery exception rather than a lossless success.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-024](RESILIENCE.md#rsc-024), [RSC-026](RESILIENCE.md#rsc-026), [RSC-033](RESILIENCE.md#rsc-033), [RSC-034](RESILIENCE.md#rsc-034), [RSC-038](RESILIENCE.md#rsc-038)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — End-to-end restoration from a real central-produced recovery package with its actual security and coverage contract.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L213) · **Result:** NOT_RUN.

## Q-RES-09

**Required steps and expected observations (unchanged):** Recovery preserves accounts/delegation, open/parked orders, unknown payments, stock/reservations/kitchen, cash custody/counts, returns/deposits, purchase disputes, receipts, audit, outboxes and operation results

**Local obligations:**
- Recovery preserves accounts/delegation, open/parked orders, unknown payments, stock/reservations/kitchen, cash custody/counts, returns/deposits, purchase disputes, receipts, audit, outboxes and operation results

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-024](RESILIENCE.md#rsc-024), [RSC-025](RESILIENCE.md#rsc-025), [RSC-028](RESILIENCE.md#rsc-028), [RSC-033](RESILIENCE.md#rsc-033)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L214) · **Result:** NOT_RUN.

## Q-RES-10

**Required steps and expected observations (unchanged):** No bearer-token resurrection or old device private-key cloning; new target obtains its own admitted identity and fresh sessions while historical actors remain unchanged

**Local obligations:**
- No bearer-token resurrection or old device private-key cloning; new target obtains its own admitted identity and fresh sessions while historical actors remain unchanged

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-023](RESILIENCE.md#rsc-023), [RSC-024](RESILIENCE.md#rsc-024), [RSC-030](RESILIENCE.md#rsc-030), [RSC-033](RESILIENCE.md#rsc-033), [RSC-037](RESILIENCE.md#rsc-037)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L215) · **Result:** NOT_RUN.

## Q-RES-11

**Required steps and expected observations (unchanged):** Room crash/recovery, sync catch-up and printing run together while new orders proceed; backpressure remains bounded and no retry loop starves foreground commits

**Local obligations:**
- Run real Room crash/recovery, local-standby backlog replication and printing while new local business proceeds; retain unsent central outbox, bound queues and protect foreground commits.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-029](RESILIENCE.md#rsc-029), [RSC-047](RESILIENCE.md#rsc-047), [RSC-048](RESILIENCE.md#rsc-048), [RSC-060](RESILIENCE.md#rsc-060)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Real central ingestion and cloud catch-up during the combined workload.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L216) · **Result:** NOT_RUN.

## Q-RES-12

**Required steps and expected observations (unchanged):** Wall-clock jumps, Cairo timezone/day boundaries, changed timezone, long offline period and counter exhaustion do not duplicate documents, expire locks unsafely or reorder authority by wall-clock alone

**Local obligations:**
- Wall-clock jumps, Cairo timezone/day boundaries, changed timezone, long offline period and counter exhaustion do not duplicate documents, expire locks unsafely or reorder authority by wall-clock alone

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-016](RESILIENCE.md#rsc-016), [RSC-044](RESILIENCE.md#rsc-044), [RSC-069](RESILIENCE.md#rsc-069)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L217) · **Result:** NOT_RUN.

## Q-RES-13

**Required steps and expected observations (unchanged):** Incompatible app/schema/protocol update, interrupted installation and supported rolling client updates preserve pending work; incompatible participants show an actionable state without corrupting records

**Local obligations:**
- Incompatible app/schema/protocol update, interrupted installation and supported rolling client updates preserve pending work; incompatible participants show an actionable state without corrupting records

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-021](RESILIENCE.md#rsc-021), [RSC-022](RESILIENCE.md#rsc-022), [RSC-042](RESILIENCE.md#rsc-042), [RSC-043](RESILIENCE.md#rsc-043), [RSC-064](RESILIENCE.md#rsc-064)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L218) · **Result:** NOT_RUN.

## Q-RES-14

**Required steps and expected observations (unchanged):** Central totals lag or a recovered tail is incomplete: reports expose checkpoint/exception; cash/stock reconciliation compares ledgers and actual custody rather than deleting differences

**Local obligations:**
- Local reports expose their actual checkpoint and recovery gaps and reconcile cash/stock custody against immutable ledgers; a cached central total is visibly stale/incomplete.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-038](RESILIENCE.md#rsc-038)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Actual central report projection lag and completeness under central ingestion/recovery.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L219) · **Result:** NOT_RUN.

## Q-NAV-01

**Required steps and expected observations (unchanged):** Every typed route has construction/boundary validation, stable serialization round-trip, route-kind registry, exactly one adapter and documented root/tab ownership; route data contains only bounded permitted identifiers

**Local obligations:**
- Every typed route has construction/boundary validation, stable serialization round-trip, route-kind registry, exactly one adapter and documented root/tab ownership; route data contains only bounded permitted identifiers

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-050](RESILIENCE.md#rsc-050)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L229) · **Result:** NOT_RUN.

## Q-NAV-02

**Required steps and expected observations (unchanged):** One logical state owner and mutation boundary; independent top-level stacks, named duplicate semantics, root protection, reselection and compact/expanded equivalence. No alternate platform stack or Navigation 2/3 hybrid

**Local obligations:**
- One logical state owner and mutation boundary; independent top-level stacks, named duplicate semantics, root protection, reselection and compact/expanded equivalence. No alternate platform stack or Navigation 2/3 hybrid

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-050](RESILIENCE.md#rsc-050), [RSC-051](RESILIENCE.md#rsc-051)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L230) · **Result:** NOT_RUN.

## Q-NAV-03

**Required steps and expected observations (unchanged):** Stale entry/session callbacks, duplicate taps, inactive host, collectors restarted after navigation and 100,000 command submissions cannot replay actions or grow an unbounded queue/history

**Local obligations:**
- Stale entry/session callbacks, duplicate taps, inactive host, collectors restarted after navigation and 100,000 command submissions cannot replay actions or grow an unbounded queue/history

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-053](RESILIENCE.md#rsc-053)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L231) · **Result:** NOT_RUN.

## Q-NAV-04

**Required steps and expected observations (unchanged):** Every route's clean/dirty/dialog/search/critical-operation/root states map to one Back policy. Android system/predictive Back, iOS swipe, toolbar, Desktop Escape/Back and programmatic completion produce the same permitted result exactly once

**Local obligations:**
- Every route's clean/dirty/dialog/search/critical-operation/root states map to one Back policy. Android system/predictive Back, iOS swipe, toolbar, Desktop Escape/Back and programmatic completion produce the same permitted result exactly once

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-010](RESILIENCE.md#rsc-010), [RSC-051](RESILIENCE.md#rsc-051)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L232) · **Result:** NOT_RUN.

## Q-NAV-05

**Required steps and expected observations (unchanged):** Blocked/handled-in-place Back never visually reveals a previous protected screen. Forward navigation, tab changes and alerts cannot bypass an active leave guard

**Local obligations:**
- Blocked/handled-in-place Back never visually reveals a previous protected screen. Forward navigation, tab changes and alerts cannot bypass an active leave guard

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-051](RESILIENCE.md#rsc-051), [RSC-054](RESILIENCE.md#rsc-054)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L233) · **Result:** NOT_RUN.

## Q-NAV-06

**Required steps and expected observations (unchanged):** Cold/process recreation starts at authentication; route-only quarantine validates after login; wrong root/tab, oversized stack, unauthorized/deleted entity, repository failure or interrupted validation fails closed without even one protected frame

**Local obligations:**
- Cold/process recreation starts at authentication; route-only quarantine validates after login; wrong root/tab, oversized stack, unauthorized/deleted entity, repository failure or interrupted validation fails closed without even one protected frame

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-052](RESILIENCE.md#rsc-052)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L234) · **Result:** NOT_RUN.

## Q-NAV-07

**Required steps and expected observations (unchanged):** Logout/account switch clears sensitive entry/session state while the master service continues. A recovery-required financial operation remains tied to its original actor outside the next user's ordinary UI

**Local obligations:**
- Logout/account switch clears sensitive entry/session state while the master service continues. A recovery-required financial operation remains tied to its original actor outside the next user's ordinary UI

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-015](RESILIENCE.md#rsc-015)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L235) · **Result:** NOT_RUN.

## Q-NAV-08

**Required steps and expected observations (unchanged):** Supported notification/deep-link adapters parse only approved grammars with bounded pending delivery, deduplicate by delivery ID, revalidate session/entity and construct canonical Back history; do not invent public links merely for testing

**Local obligations:**
- Supported notification/deep-link adapters parse only approved grammars with bounded pending delivery, deduplicate by delivery ID, revalidate session/entity and construct canonical Back history; do not invent public links merely for testing

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-050](RESILIENCE.md#rsc-050)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L236) · **Result:** NOT_RUN.

## Q-NAV-09

**Required steps and expected observations (unchanged):** Entry removal/reset releases Koin ViewModels, coroutines, observers and registrations; retained tabs preserve intended state only. Reentry uses correct fresh/restored scope; old registration disposal cannot remove a new guard

**Local obligations:**
- Entry removal/reset releases Koin ViewModels, coroutines, observers and registrations; retained tabs preserve intended state only. Reentry uses correct fresh/restored scope; old registration disposal cannot remove a new guard

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-053](RESILIENCE.md#rsc-053), [RSC-055](RESILIENCE.md#rsc-055)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L237) · **Result:** NOT_RUN.

## Q-NAV-10

**Required steps and expected observations (unchanged):** Physical iPhone/iPad, Arabic RTL/English LTR: slow/full drag, short/fast flick, low-velocity release, reversal/cancel, repeated Back, opposite edge rejection, runtime language switch and background/lock/detach during gesture

**Local obligations:**
- Physical iPhone/iPad, Arabic RTL/English LTR: slow/full drag, short/fast flick, low-velocity release, reversal/cancel, repeated Back, opposite edge rejection, runtime language switch and background/lock/detach during gesture

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-022](RESILIENCE.md#rsc-022), [RSC-054](RESILIENCE.md#rsc-054)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L238) · **Result:** NOT_RUN.

## Q-NAV-11

**Required steps and expected observations (unchanged):** Measure finger tracking and release settlement separately, including 60/120 Hz where shipped hardware provides it; public platform-specific adjustments preserve Android/Desktop behavior and library ownership

**Local obligations:**
- Measure finger tracking and release settlement separately, including 60/120 Hz where shipped hardware provides it; public platform-specific adjustments preserve Android/Desktop behavior and library ownership

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-054](RESILIENCE.md#rsc-054)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L239) · **Result:** NOT_RUN.

## Q-NAV-12

**Required steps and expected observations (unchanged):** Navigation-host recompositions do not scale with 1,000+ pointer/key activity signals; measure frame time, retained entries/ViewModels and current-screen recomposition under scanning/scrolling

**Local obligations:**
- Navigation-host recompositions do not scale with 1,000+ pointer/key activity signals; measure frame time, retained entries/ViewModels and current-screen recomposition under scanning/scrolling

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-053](RESILIENCE.md#rsc-053)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L240) · **Result:** NOT_RUN.

## Q-DI-01

**Required steps and expected observations (unchanged):** Verify all Koin platform/feature graphs with required parameters and scopes; domain/service dependencies resolve without UI; no screen ViewModel singleton or hidden second database/master instance

**Local obligations:**
- Verify all Koin platform/feature graphs with required parameters and scopes; domain/service dependencies resolve without UI; no screen ViewModel singleton or hidden second database/master instance

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-003](RESILIENCE.md#rsc-003), [RSC-055](RESILIENCE.md#rsc-055)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L241) · **Result:** NOT_RUN.

## Q-DI-02

**Required steps and expected observations (unchanged):** Application/master scope outlives cashier login. Human-session scope closes on logout/revocation; entry scope closes on pop/reset; collector/resource teardown is observable and leak-checked

**Local obligations:**
- Application/master scope outlives cashier login. Human-session scope closes on logout/revocation; entry scope closes on pop/reset; collector/resource teardown is observable and leak-checked

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-015](RESILIENCE.md#rsc-015), [RSC-020](RESILIENCE.md#rsc-020), [RSC-055](RESILIENCE.md#rsc-055)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L242) · **Result:** NOT_RUN.

## Q-IMG-01

**Required steps and expected observations (unchanged):** Coil image requests/cache keys include appropriate company/catalog revision and authorization scope; switching accounts/companies cannot expose private media through memory/disk cache

**Local obligations:**
- Coil image requests/cache keys include appropriate company/catalog revision and authorization scope; switching accounts/companies cannot expose private media through memory/disk cache

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-049](RESILIENCE.md#rsc-049), [RSC-056](RESILIENCE.md#rsc-056)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L243) · **Result:** NOT_RUN.

## Q-IMG-02

**Required steps and expected observations (unchanged):** Thumbnail sizing, decode limits, bounded cache and cancellation handle large/corrupt/unsupported images, offline placeholders, permission revocation, scrolling and repeated navigation without main-thread blocking or unbounded retained bitmaps

**Local obligations:**
- Thumbnail sizing, decode limits, bounded cache and cancellation handle large/corrupt/unsupported images, offline placeholders, permission revocation, scrolling and repeated navigation without main-thread blocking or unbounded retained bitmaps

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-056](RESILIENCE.md#rsc-056)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L244) · **Result:** NOT_RUN.

## Q-IMG-03

**Required steps and expected observations (unchanged):** Test configured platform fetchers/decoders/HTTP stack and cache cleanup on all targets; image failures never prevent item selection, authoritative price/stock validation or local checkout

**Local obligations:**
- Test configured platform fetchers/decoders/HTTP stack and cache cleanup on all targets; image failures never prevent item selection, authoritative price/stock validation or local checkout

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-056](RESILIENCE.md#rsc-056)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L245) · **Result:** NOT_RUN.

## Q-UX-01

**Required steps and expected observations (unchanged):** Arabic/English resource, plural and placeholder parity covers shared and native prompts/notifications/menus; reviewed explicit allowlist only; no release fallback concealing missing translation

**Local obligations:**
- Arabic/English resource, plural and placeholder parity covers shared and native prompts/notifications/menus; reviewed explicit allowlist only; no release fallback concealing missing translation

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-057](RESILIENCE.md#rsc-057)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L255) · **Result:** NOT_RUN.

## Q-UX-02

**Required steps and expected observations (unchanged):** RTL layout, directional icons, chart/table reading order, mixed-direction numbers/barcodes/serials and receipts; runtime language changes preserve the order and do not swap amount interpretation

**Local obligations:**
- RTL layout, directional icons, chart/table reading order, mixed-direction numbers/barcodes/serials and receipts; runtime language changes preserve the order and do not swap amount interpretation

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-057](RESILIENCE.md#rsc-057), [RSC-060](RESILIENCE.md#rsc-060)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L256) · **Result:** NOT_RUN.

## Q-UX-03

**Required steps and expected observations (unchanged):** Touch targets, keyboard-only paths, scanner focus, shortcuts, text scaling, contrast, error announcements and TalkBack/VoiceOver/Desktop assistive behavior qualified where available

**Local obligations:**
- Touch targets, keyboard-only paths, scanner focus, shortcuts, text scaling, contrast, error announcements and TalkBack/VoiceOver/Desktop assistive behavior qualified where available

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-002](RESILIENCE.md#rsc-002), [RSC-057](RESILIENCE.md#rsc-057), [RSC-061](RESILIENCE.md#rsc-061), [RSC-062](RESILIENCE.md#rsc-062)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L257) · **Result:** NOT_RUN.

## Q-UX-04

**Required steps and expected observations (unchanged):** Common sale is few clear steps with no routine technical dialogs; variant/modifier/weight selection asks only applicable information. Exceptions show a specific next action, not raw stack traces

**Local obligations:**
- Common sale is few clear steps with no routine technical dialogs; variant/modifier/weight selection asks only applicable information. Exceptions show a specific next action, not raw stack traces

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-057](RESILIENCE.md#rsc-057)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L258) · **Result:** NOT_RUN.

## Q-UX-05

**Required steps and expected observations (unchanged):** Test rapid scan, duplicate key-repeat, scanner-as-keyboard suffix, simultaneous touchscreen input, modal manager approval, payment uncertainty and print failure; no hidden second submit

**Local obligations:**
- Test rapid scan, duplicate key-repeat, scanner-as-keyboard suffix, simultaneous touchscreen input, modal manager approval, payment uncertainty and print failure; no hidden second submit

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-002](RESILIENCE.md#rsc-002), [RSC-057](RESILIENCE.md#rsc-057), [RSC-060](RESILIENCE.md#rsc-060), [RSC-061](RESILIENCE.md#rsc-061), [RSC-063](RESILIENCE.md#rsc-063)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L259) · **Result:** NOT_RUN.

## Q-UX-06

**Required steps and expected observations (unchanged):** Resumable setup/import/training/live transition produces no duplicate opening stock/float and no training fiscal/sync data. Missing optional standby never blocks a valid one-device launch

**Local obligations:**
- Resumable setup/import/training/live transition produces no duplicate opening stock/float and no training fiscal/sync data. Missing optional standby never blocks a valid one-device launch

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-058](RESILIENCE.md#rsc-058), [RSC-059](RESILIENCE.md#rsc-059)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L260) · **Result:** NOT_RUN.

## Q-UX-07

**Required steps and expected observations (unchanged):** Full restaurant rehearsal: table/order/partial send/modifier/start/cancel/remake/split tender/split bill/handoff/served unpaid incident/later collection/shift close

**Local obligations:**
- Full restaurant rehearsal: table/order/partial send/modifier/start/cancel/remake/split tender/split bill/handoff/served unpaid incident/later collection/shift close

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-065](RESILIENCE.md#rsc-065)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L261) · **Result:** NOT_RUN.

## Q-UX-08

**Required steps and expected observations (unchanged):** Full clothing rehearsal: variants/barcodes/discount/partial return/exchange/inspection/tender difference; remaining entitlement and cost reconcile

**Local obligations:**
- Full clothing rehearsal: variants/barcodes/discount/partial return/exchange/inspection/tender difference; remaining entitlement and cost reconcile

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-066](RESILIENCE.md#rsc-066)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L262) · **Result:** NOT_RUN.

## Q-UX-09

**Required steps and expected observations (unchanged):** Full supermarket rehearsal: fast scan/scale/pack/expiry/promotion/partial receipt/count/last-unit race/closing; price and unit conversions remain legible and exact

**Local obligations:**
- Full supermarket rehearsal: fast scan/scale/pack/expiry/promotion/partial receipt/count/last-unit race/closing; price and unit conversions remain legible and exact

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-061](RESILIENCE.md#rsc-061), [RSC-062](RESILIENCE.md#rsc-062), [RSC-067](RESILIENCE.md#rsc-067)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L263) · **Result:** NOT_RUN.

## Q-UX-10

**Required steps and expected observations (unchanged):** Full purchasing/admin rehearsal: catalog import → price/policy release → partial delivery/inspection → invoice mismatch → authorized payment/credit → report with one offline branch

**Local obligations:**
- Rehearse actual native local catalog draft/import preview, activated price/policy display, partial receipt/inspection/invoice mismatch/authorized payment/credit and local reports; global publication remains pending and no fake central authoring is counted.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-068](RESILIENCE.md#rsc-068)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Actual central catalog/price/policy publication and multi-branch consolidated report with an offline branch.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L264) · **Result:** NOT_RUN.

## Q-PERF-01

**Required steps and expected observations (unchanged):** Nominal healthy LAN/qualified device: client submit to durable response p95 ≤ 500 ms, p99 ≤ 1 s; excludes human/terminal time, paper printing and central/standby acknowledgement. Checkpoint writes remain enabled

**Local obligations:**
- Nominal healthy LAN/qualified device: client submit to durable response p95 ≤ 500 ms, p99 ≤ 1 s; excludes human/terminal time, paper printing and central/standby acknowledgement. Checkpoint writes remain enabled

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-046](RESILIENCE.md#rsc-046)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L282) · **Result:** NOT_RUN.

## Q-PERF-02

**Required steps and expected observations (unchanged):** Cached/local indexed product lookup p95 ≤ 150 ms; scan/key acceptance and navigation response p95 ≤ 100 ms. Measure stale/filtered query paths and actual rendering separately

**Local obligations:**
- Cached/local indexed product lookup p95 ≤ 150 ms; scan/key acceptance and navigation response p95 ≤ 100 ms. Measure stale/filtered query paths and actual rendering separately

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-045](RESILIENCE.md#rsc-045), [RSC-046](RESILIENCE.md#rsc-046)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L283) · **Result:** NOT_RUN.

## Q-PERF-03

**Required steps and expected observations (unchanged):** Target smooth 60 Hz interactions on qualified baseline hardware; record p95 frame time, missed frames and startup time. Long import/sync/image work must not block input; lower-refresh devices are measured at their supported refresh

**Local obligations:**
- Target smooth 60 Hz interactions on qualified baseline hardware; record p95 frame time, missed frames and startup time. Long import/sync/image work must not block input; lower-refresh devices are measured at their supported refresh

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-046](RESILIENCE.md#rsc-046)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L284) · **Result:** NOT_RUN.

## Q-PERF-04

**Required steps and expected observations (unchanged):** Drain 250,000 queued events while new business continues; catch-up sustained throughput ≥ 2× the measured incoming event rate and bounded memory. Report elapsed time and restart/resume progress; no fixed internet speed is assumed

**Local obligations:**
- Drain 250000 actual local standby recovery events while new commands continue, with durable resumable cursors and bounded memory; maintain the independently pending central outbox. Use measured incoming event rate and report throughput.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-029](RESILIENCE.md#rsc-029), [RSC-048](RESILIENCE.md#rsc-048)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Equivalent real central upload/ingestion/acknowledgement catch-up capacity.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L285) · **Result:** NOT_RUN.

## Q-PERF-05

**Required steps and expected observations (unchanged):** Execute 24 h N2 operation and 72 h mixed connected/reconnecting soak at P1; ledger/entitlement reconciliation exact, no lost committed operation, crash, monotonic leak or unbounded queue

**Local obligations:**
- Execute the required 24h N2 and 72h local connected/reconnecting P1 workloads using real master/client/optional standby, exact ledger reconciliation and resource bounds.

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-004](RESILIENCE.md#rsc-004), [RSC-047](RESILIENCE.md#rsc-047)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** HELD — Central-connected/ingestion portions of the 72h combined soak after owner approval.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L286) · **Result:** NOT_RUN.

## Q-PERF-06

**Required steps and expected observations (unchanged):** Establish platform budgets in the foundation benchmark before feature growth; record peak/RSS/managed/native/bitmap/queue usage. After warm-up and repeated scope teardown, retained resource counts return to their designed bound; do not use a single JVM heap number for iOS

**Local obligations:**
- Establish platform budgets in the foundation benchmark before feature growth; record peak/RSS/managed/native/bitmap/queue usage. After warm-up and repeated scope teardown, retained resource counts return to their designed bound; do not use a single JVM heap number for iOS

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-046](RESILIENCE.md#rsc-046), [RSC-047](RESILIENCE.md#rsc-047), [RSC-053](RESILIENCE.md#rsc-053)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L287) · **Result:** NOT_RUN.

## Q-PERF-07

**Required steps and expected observations (unchanged):** Provisional same-master ready-to-serve target ≤ 60 s at P1 after process launch and healthy storage; excludes user reopening a terminated mobile app and OS boot time. Pending outcomes remain recoverable

**Local obligations:**
- Provisional same-master ready-to-serve target ≤ 60 s at P1 after process launch and healthy storage; excludes user reopening a terminated mobile app and OS boot time. Pending outcomes remain recoverable

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-046](RESILIENCE.md#rsc-046)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L288) · **Result:** NOT_RUN.

## Q-PERF-08

**Required steps and expected observations (unchanged):** Provisional planned handover target ≤ 15 min at P1 once prepared target/network are available; show drain/copy/activation times. No safety shortcut to meet a stopwatch; emergency recovery has measured checkpoint-specific RTO

**Local obligations:**
- Provisional planned handover target ≤ 15 min at P1 once prepared target/network are available; show drain/copy/activation times. No safety shortcut to meet a stopwatch; emergency recovery has measured checkpoint-specific RTO

**Verification needs (unchanged):**
- Create frozen independent expected outcomes for every listed gate obligation, not only a smoke test.
- Exercise the claimed actual production boundary; static/unit success cannot replace required real storage, services, installed UI or named physical hardware.
- Record per-platform/variant evidence separately; an unexecuted or central-deferred variant never becomes PASS from a count or inventory validation.

**Detailed existing cases:** [RSC-030](RESILIENCE.md#rsc-030)

**Environment:** use the actual boundary named above and [ENVIRONMENTS.md](ENVIRONMENTS.md); unavailable device, legal profile or fault tool is BLOCKED.

**Central portion:** None deferred.

[Original requirement](../original-handoff/POS_TEST_AND_QUALITY_PLAN.md#L289) · **Result:** NOT_RUN.
