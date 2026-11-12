# POS cleanup and refactoring

**Applied; production compilation and selected JVM regressions pass. Behavioral/native qualification remains BLOCKED; NOT complete or owner-ready.**
Owner priority, 26 October 2026. Baseline: `068fac7d5504072f63e78b6e7ef1975088fe1aaa`.
Saved candidate: `640e0e636d9c6a33ca66b580ffed48a4a03b515f`, confirmed pushed
only to `implement/ukkera-pos-local` with `[skip ci]`.
Relevant portable compile/regression checks are now resolved, but native/runtime
prerequisites still block complete behavioral preservation. They must be completed
and tested rather than bypassed or marked as cleanup passes. All unfinished local
features remain required; central work and Queue are outside this refactor.

## KMP consolidation — 28 October

The owner now requests continued feature work alongside gradual architectural
cleanup. Shared setup/employee/recovery orchestration, compatible launch/intent
codecs, publication and lifecycle declarations replace the Android policy layer.
Android keeps native storage, keys, scheduling, foreground/service ownership and
actual acquisition/close. Ordinary shared/native names are unversioned; persisted
identity/header/schema bytes and original recovery/permission checks are unchanged.

Source `7154d99064fb3ddaca4dc083b31a31c9a82da90996c1ba7d18bdbcb8607e97bb`
compiles Android/Desktop/JVM and passes526 selected old regressions plus one
independently frozen STOP-policy slice on JVM/Android host. This is **not** native
or complete cleanup/platform qualification. The audit also identifies duplicate
password policy, JVM lifecycle/backup orchestration and native-owner policy for
incremental consolidation. Differing historical journal codecs remain compatible,
not blindly merged. [Architecture/boundaries](ARCHITECTURE.md),
[exact ownership and evidence](../../qa/pos/traceability/shared-setup-kmp-20261028/README.md).

## Current verification update — 27 October

- Paged-selection/inventory source `10ca821cba81df954264d443406e2405e294a043e31e3ff68f40a62ed38f25f9`
  (787 inputs) compiles/assembles147.708s and passes338 unchanged inventory/store/UI
  regressions68.645s. Four new ordinary files are unversioned; existing stock
  initialization bytes/schema remain unchanged. Shared query construction removes
  duplicate refresh/ACK mapping. No new tests/oracles, independent acceptance or
  transferred cleanup parity. Owner now prioritizes useful Android end-to-end
  testing before further cosmetic work; full scope and central hold remain.

- Native prepared-journal recovery source `fa9a1503bd808abf512b9f063dbf7694f5fb23083ff22649ba39f6a85f50729e`
  (783 inputs) compiles/assembles147.696s and passes45 unchanged UI regressions
  54.299s. Ordinary owner/write names stay unversioned; the new private write-ahead
  codec keeps V1 because its bytes persist. Shared exclusive-create/force/readback
  code replaces duplicate initial/write-ahead creators. No existing journal/schema
  format changes, transferred cleanup parity, native/device or independent acceptance.
  Earlier security/database blockers remain; all original/frozen evidence is preserved.

- Latest original-client continuation source `92ad4400e32f9dbdaa9c04fa786d04f65862e6745bfe2ca71e5f4a68c194facf`
  (782 inputs) compiles/assembles300.815s and passes45 unchanged UI regressions
  55.544s. Seven source changes use ordinary unversioned names and do not change
  journal/schema formats. Atomic Room-mode visibility is corrected; no parity,
  native/device or independent new-path acceptance is inferred. Earlier security
  failures/database test-compilation blocker remain. Exact sources in dated logs.

- Original Android-cohost source `df95b401e06aebbff7e9e0e55295cc05199f2311de36c3b8342f60dc6553708a`
  (782 inputs) compiles for gateway/UI/Android/desktop and assembles a debug APK
  (402.062s);45 unchanged UI regressions pass42.514s. Security still fails2 of139
  existing expectations. Native/device/cleanup parity remains unverified. Four new
  ordinary runtime/key/publication files use unversioned names; fixed journal/key
  identity domains and old persisted formats keep genuine versions. No schema or
  oracle change. [Exact boundaries](ANDROID-LOCAL-CLIENT.md) and dated app logs.

- Current cash-close source `b8a1ead60b813683077361c18419ece4a797d1f46c2fff145dc30c7819c2f4ac`
  (778 inputs) compiles for Android/desktop/gateway/UI and passes153 selected
  existing regressions. It adds ordinary, unversioned note/workflow files, with
  V1 retained only on new persisted/wire DTOs. Count cursor parsing distinguishes
  ordinary foreign/missing input from broken stored source, without weakening
  the original session/claim checks. Cash form reason limits now match the domain.
  No independent note/native acceptance or full cleanup parity is claimed.
- The latest database attempt on preceding authentication source failed test
  **compilation** at the removed raw throttle callback: zero DB cases ran. The
  older623/461 entry below remains historical, not new execution evidence. No
  permissive production callback, legacy schema7 opener or changed oracle was
  introduced. Disk shortage is now resolved; transient RAM pauses retain reserves.

- Later original-recovery source `4511e7d9c15c8fa7f74512e6258fb9702d7a6d4b58043ef05ffa200a5ef9339c`
  compiles for Android/gateway/UI and passes45 unchanged UI regressions. The
  real-close/Room-owner correction and bounded original-C1 recovery do not pass
  blocked native behavioral checks. No schema, frozen oracle or baseline change.
  See the02:20 verification entry; ordinary names remain unversioned.
- Gateway/UI JVM, desktop compilation and Android debug assembly succeeded on
  fingerprint `72b59ea6f4dc866417ba7aa008956eec0bc67adb2e78c6947654d7f0d67053bc`.
- 722 selected JVM regressions (including45 UI) pass on fingerprint
  `2a5a1ead2f8b46912e71b7305bcd831d7cb0a21ac8809e80ba4c6eaed0c1a044`.
- 623 real database regression methods executed on that same fingerprint;461
  fail at the existing native-startup/owned-schema guard. The phase is FAIL, not
  partial accepted integration. All461 share that concrete fixture blocker.
- Two existing security assertions still disagree with the unchanged pre-cleanup
 10-table profile. Expectations/production tables have not been weakened.
- The actual KSP-generated7.json now exists (29 entities). No migration or native
  qualification is inferred. The old resource-stopped attempts below remain
  historical failures, not the current compilation status.
- The real Swing Main-dispatcher dependency is now explicit. Existing fixture-only
  capability/phase wiring was corrected without new cases or expected outcomes.
  Five unchanged owned-process/lock checks also pass after the foreground Gradle
  ownership correction. Resource reserves remain6GiB memory/8GiB disk.

[Current verification log](../../apps/pos/VERIFICATION_REPORT.md) records exact
packets, hashes, owner/executor roles and limits. CP001/002 structural-only results
and CP003–014 runtime gaps remain unchanged; no full cleanup acceptance is claimed.

## What changed

The portable, exact before/after record is [CLEANUP-MAP.json](CLEANUP-MAP.json).
It covers 127 source-file renames, 1,061 globally unambiguous identifier names,
one additional identifier with three explicit file-local scopes, all removals
and all 135 retained version-named paths from the 270-path baseline census.
The census includes compatibility fixtures and immutable QA records, not just
application implementations. No blanket `V1`/`V2` replacement was performed.

- Ordinary UI/controller, gateway, runtime-owner, planner and adapter names now
  describe their purpose rather than the development iteration that created them.
- Historical and journal financial handlers are named by role, not confused or
  merged. Historical payment/refund readers remain registered.
- The private Android setup service and its binder/callers/manifest are renamed
  together. It remains nonexported; action strings, lifetime flags and all other
  application/activity/provider identities are unchanged.
- Shared receiving validation now has one implementation, used by both routes.
  Its ordered checks, arithmetic and the normalized route's serial-registration
  copy/authority checks are preserved.
- [ARCHITECTURE.md](ARCHITECTURE.md), the READMEs and the continuation handoff give
  newcomers a primary navigation path. Large existing dated records remain
  historical evidence rather than competing current status pages.

### Real removals, not cosmetic renaming

| Removed source | Evidence and retained behavior |
| --- | --- |
| Seven-file `RuntimeHistory*V1` / `SelfOwnedNativeHistoryWireV1` D1 island | Explicitly unregistered, unadmitted draft; no installer, live writer, registered DAO, supported schema or migration. Closed reference graph. Actual P1/P2 V1 records and history V2 remain. |
| `LocalFoodHandoffWritesV2.kt` | Old writer entry had no caller; active paid-handoff writer is separate. All old handoff storage/producer readers remain. |
| Two food endpoint forwarding facades | Declaration-only wrappers; actual gateway already stages durable intent and calls the production endpoint. Endpoint permissions and reads are retained. |
| Retired fresh legacy recording-reservation creation subgraph | Closed unused creators, not compatibility readers. Stored slots, target/budget checks, historical RecordOutcome/ApplyObservation and refusal of fresh legacy starts remain. |
| Unused receipt-key/refund-footprint/scalar helpers and stock-demand interface | Exact declaration-level removals; actual producers, financial bounds, list-demand services and original stock/money checks remain. |

Eight complete source files were removed. Nine other source files received bounded
removals/consolidation/import cleanup. Full paths and boundaries are in the map.
No required unfinished capability was deleted simply because it lacks a caller.

### What deliberately keeps a version

Persisted and serialized DTOs/descriptors/fields, canonical codecs and wire
profiles; SQL entities/tables/queries and migrations; operation/command/enum
identities; fixed native-journal and key/signature domains; old financial/history
interpreters; and independently frozen test-suite/evidence versions remain explicit.
Mixed files can keep a genuine format suffix while their runtime declarations
inside become unversioned. Public Kotlin visibility alone is not a versioned
compatibility promise for this unpublished, jointly rebuilt app.

All surviving emitted literal text, numeric limits, ownership/cancellation rules,
permission checks and safety holds are preserved. In particular cleanup does not
invent schema7 identity, permit a6→7 migration, qualify native startup, enroll an
account/journal or turn an unavailable workflow into a passing demo.

## Review, independent design and actual verification

| Role | Owner / result |
| --- | --- |
| Cleanup implementer and integrator | `/root` |
| Core source reviewer | `/root/cleanup_core_review`;188 production files inspected |
| Workflow source reviewer | `/root/cleanup_workflows_review`;179 affected files plus runtime/caller boundaries |
| Cross-map/post-apply source audit | [Core reviewer report](CLEANUP-SOURCE-REVIEW.md):651 surviving Kotlin files match approved edits;1,180 retained serializer identities unchanged; scoped `domainV2` homonym handled before application |
| Independent test designer | `/root/cleanup_test_design`, fresh requirements-only context; no implementation/tests/diffs/logs inspected |
| Test executor/wiring | `/root/cleanup_executor`; finished exact saved-archive structural execution; business/runtime execution blocked |
| Continuation | `/root`, solo after existing helpers finished; no new helpers or delegation |

Frozen independent packet: `qa/pos/freezes/cleanup-preservation-v1/`.
14 cases:3 structural and11 business/integration cases. CASES SHA-256:
`69dea7cda0f1eabc13a5c641331949eff096a6c454399974a0b0502bb79e5495`.
Freeze receipt: `fea27429e5fbb77e072e23eef2491463ab67fcd998ccef4946f3170dc2951340`.
Inputs/assertions/expected outcomes cannot change during wiring. All original141
statuses and historical freezes are preserved. Build/source inspection is not
business acceptance; missing original product/platform prerequisites are explicit
BLOCKED/NOT_RUN, not preservation passes.

### Completed executor handoff

[Results and exact blockers](../../qa/pos/wiring/cleanup-preservation/RESULTS.md)
bind baseline `068fac7` to candidate `640e0e6`, not to newer dirty documentation
or tooling. CP001/002 passed **structural-only** inspection. CP003 remains blocked
on the complete approved durable-contract corpus; CP004–014 remain blocked on
genuine operational fixtures, artifacts, transport, recovery and platform paths.
**Zero runtime executions and zero behavioral acceptance passes.**

Each revision retains 14 cases, 24 variants and all 86 frozen assertions; the
1,630 required runtime matrix rows remain blocked. All original141 receive
additive preservation observations, not new business outcomes. The correction
to the first executor inventory was separately bound and rerun; neither earlier
evidence nor any frozen expectation was overwritten.

- Final binding: `dd04ea303d0a1e118c3678e5b7be67af0b4fc617805b7cb075cee8f9d503cc48`.
- Final run manifest: `830d9f9b527f4db3301d40334b6653cdb2768922ae515796665f223b6e8e8046`.
- The lead checked every member hash in both bindings/runs and exact parity of
  all 86 assertions per revision against the unchanged independent freeze.
  This is **lead packet-integrity review**, not independent approval of the
  lead's implementation. Independent result review remains outstanding.
- Concise evidence/141 observations are portable; raw generated inventories,
  review copies and detailed execution matrices remain local, unchanged. See
  the executor README for the publication boundary and fresh-clone limitations.

### Historical build attempts before the successful compile

Before any cleanup source change, the guarded core/database baseline attempts
stopped at `HOST_MEMORY_RESERVE` after52.046s and28.031s. Neither reached a database
compile verdict. Packets are under `apps/pos/.workspace/evidence/local-compile/`:

- `20261026T213033.789531Z-fa33d0c05b0e4768a0a3ffeb0dd36519`, fingerprint
  `063eaa6c687373f888f71cc02ddb21dd1669d6d62c0ffd3d14b0d09d65dd5e07`.
- `20261026T214257.919335Z-066d055725a2422c855dc8ccaad53517`, fingerprint
  `02c4d701e7b341ff778560c5724f9f79b8b99fbc1e03bba5e9c6c705f07e5a2c`.

First post-refactor request: gateway JVM, UI JVM and desktop compilation through
`tools/compile_local.py --offline --configure-on-demand`. It stopped on the same
reserve after7.417s, before compiler diagnostics; **FAIL, not verified**.
Packet `20261026T220621.445072Z-fe0f6a3f8ffe49ec9a73fe1bbb4596fa`, unchanged source
fingerprint `698147fdce6e74f982a019debeb62543d3893653c52ef5113fb2f796380c93ae`.
The focused core/database attempt also stopped after4.165s on the same reserve,
before compiler diagnostics, with the same source fingerprint: packet
`20261026T220922.164466Z-a6033fc1ec09488ba4873971ceac0e1b`. Sanitized resource
observations are retained; attribution is still under investigation.
No OOM diagnosis, successful new build or independent acceptance pass is implied.

The third post-refactor core/database attempt stopped after32.823s on the same
reserve and fingerprint: packet
`20261026T221856.117486Z-1a14fad009924beab54ff858207057a8`.
It reached cached prerequisite tasks and security compilation preparation but
did not complete the requested target. No completed database build is inferred.

The finished resource review found correct guard arithmetic and memory pressure
even while no owned build ran. A virtio balloon device exists, but causation is
**unproven**. No safe multi-GiB disposable cache was established; synthetic `/tmp`
databases/evidence and unrelated workloads remain untouched. Review SHA-256:
`4ffcebf107851a5aa13e189a62c31c18ec9b9dd59d5cf1c25cb51ae4dded6b79`.

Solo follow-up at22:25–22:27 performed120s of **passive observation only**:
available RAM ranged1.47–21.77GiB and fell below the6GiB reserve in2 of41 samples.
No new Gradle retry was launched. Record:
`.workspace/cleanup-20261026/solo-readiness-20261026T222521Z.json`.
Verification needs stable reserved local build capacity; an isolated high idle
sample is not sufficient. Do not lower reserves, disable guest memory controls,
kill unrelated processes or repeatedly relaunch an already blocked build.

### Build-tool correction after the saved application refactor

Solo review found that `compile_local.py --configure-on-demand` conflicts with
`verify_local.py`'s required all-project/source-set observations. The22:18 packet
contains missing observations for unevaluated projects; not all are merely a
consequence of interruption. Removed that optional mode and explicitly pass
`--no-configure-on-demand`. The existing observer, central hold, task restrictions,
JVM bounds and resource reserves are unchanged. A narrower **compile target**
still works with full project configuration; no permissive observer fallback was
added. The old failure packets remain unchanged.

Python syntax, CLI help and local-only plan display succeeded after this tooling
correction. These are lightweight tooling checks, **not a Gradle build or
independent application test**. The tool correction follows the `640e0e6`
archive inspected by the executor and must not inherit its result as new evidence.

Keep one heavy build,6GiB memory and8GiB disk reserve. Only six duplicate owned
Gradle distributions were removed after checking an identical installed copy;
source, staging, evidence, logs, configuration and dependency caches remain intact.
Machine-local review/edit/resource records are in
`apps/pos/.workspace/cleanup-20261026/`, not shipped as duplicate source trees.

### 27 October continuation — preserved cleanup evidence, newer source

Ordinary setup/runtime names remain unversioned; genuine installation/native/SQL
contract versions are retained. First-account definition/submission and real-owner
close/ACK wiring are now compiled in the production source. Fingerprint
`0f25bc17d8f46f7d84ff549cacfd68c6dcd5b0c947e386bd837b22b703afa477` passes gateway/UI
JVM and Android Kotlin compilation and45 unchanged UI regressions. Security
regression phase FAIL:139 executed,2 unchanged baseline table-count expectations.
Native/device acceptance and cleanup CP003–014 remain unverified/blocked; no
newer source inherits CP001/002 or old build outcomes. See the01:38 entry in
[VERIFICATION_REPORT.md](../../apps/pos/VERIFICATION_REPORT.md) for exact packets.

Resource handling keeps6GiB memory/8GiB disk reserves. Further POS-only reproducible
task caches were pruned using exact task/package/invocation evidence; compile runs
can use `--no-build-cache`. Read-only archived source copies were content-deduplicated
with archive-to-archive hardlinks. Every referenced hash/size in all24 affected
manifests matched before and after; original handoffs, results, logs, databases and
logical artifacts are untouched. Archived file mtimes and physical inode independence
are not preserved. Records and saved-space figures remain in the verification entry
and ignored `.workspace/cleanup-20261026/` records, not a rewritten prior packet.
The owner was notified that sustained builds need at least2GiB additional headroom;
no RAM upgrade, reserve reduction or unrelated data deletion is justified.

## Preserved work and current continuation state

| Work | Preserved handoff | Actual state |
| --- | --- | --- |
| Normalized retail entry/handoff | `apps/pos/.workspace/next-cut/local-retail-normalized-entry-v2/source-001/PAUSED-HANDOFF.md` | Five finite changes and bounded order/collection gateway joins now integrated; gateway JVM compile and446 regressions pass on aa4f748. Original stage unchanged. Full256-line capacity, native physical Start and independent workflow acceptance remain open. |
| Independent restoration design | `qa/pos/designs/food-before-start-restoration-v1/PAUSED-HANDOFF.md` |35 draft cases; consistency review,141-row applicability and hashes unfinished. Not frozen/executed. Designer is stopped; do not reactivate under the solo instruction or fill gaps from implementation. |
| Lead local runtime | [AGENT-HANDOFF.md](AGENT-HANDOFF.md) | First-account definition/submission and same-lifetime ACK source connected/compiled, native acceptance unverified. Genuine business publication, client-journal enrollment, cross-process reconciliation and the remaining full local scope still open. |

Retail handoff SHA-256:
`07057b5a617be2b74577ef6f9591727dbad9767a34db5b1b5a2526d3acfeb079`;
paused manifest: `3fa00989a02ff7d4d239ad2891f6910569ba7ef1432c9f44d879fdf7feed3fd6`.
These ignored stages exist only on this machine. Rebase finite changes using the
map; do not recopy complete pre-cleanup files or fabricate absent stage contents.
Restaurants, cafés, clothing and supermarkets remain in scope. No central work
starts before owner hands-on local testing and explicit approval.
