# POS development continuation guide

GitHub [tracker #2](https://github.com/ukkera/kmp-apps/issues/2) is the live backlog.
Read the owning Feature and its Tasks before changing code. This guide identifies
integration boundaries; it is not a second backlog or a completion certificate.

## Start with the current evidence

## Creation-original cancellation boundary — 12 November 2026

Internal source `e0529db5` adds a distinct same-request cancellation audit and
native capsule, a zero-write prior-result path, and an exact one-audit fresh
writer. Fresh cancellation requires the actual COMMITTED native child plus
matching original lifetime/allocation/intent and protected-final declaration;
it changes no target account/policy/credential/session. Creation's original read
and internal client recovery distinguish Cancelled from Applied/conflict. The
v4 journal records cancellation only from an exact protected response for the
queried canonical original, never from a local stop or NotObserved.

**Compilation/tests NOT_RUN** under the still-unavailable shared resource gate.
No old results are attributed to this source, and focused independent cases/review
remain absent. Cold scanning explicitly holds this new family; complete replay
and image/lineage/current/count closure, public dispatch and UI/RPC composition
remain unfinished. Existing creation/history holds, prior TCP failure and owner
downloads remain unchanged. [Exact sources and verification limits](../../qa/pos/traceability/creation-original-cancellation-20261112/README.md).

## Creation-original client recovery — 12 November 2026

Internal source `32183e09` connects the retained creation journal to the actual
protected exact-original lookup under the original actor's current lease. Session
observations surround lookup, journal recording and delivery. Applied/conflict
recording requires the queried original and compares its full canonical bytes
under the journal mutex, preventing a late response from terminalizing a replacement
entry. NotObserved, denials and uncertainty retain the original and stop flag;
no redispatch or server cancellation is inferred. This internal method has no
public recovery UI/RPC consumer, and public creation/native history remain held.

**Compilation and tests NOT_RUN:** the shared resource gate is still unavailable;
no repeated request or gate bypass. Focused independent cases/review remain absent.
Server cancellation/history, dispatch/composition and full verification remain
unfinished; the earlier TCP failure is unresolved. Existing inspection downloads
are unchanged. [Exact source and verification limits](../../qa/pos/traceability/creation-original-client-recovery-20261112/README.md).

## Creation-original client journal — 12 November 2026

Internal source `e47b9b8c`/`7861f0bc`/`8997bd85` adds the complete immutable creation original
and creator/device identity to additive encrypted-journal profile v4. A durable
first-dispatch transition is never recreated by reopen/read; cancellation after
the durable write withholds its handoff while retaining the phase. Stopping prepared
work removes it locally; stopping dispatched work retains a lookup/server-cancel
obligation, with no invented tombstone or rollback. Exact Applied/conflict results
remain distinct, and passwords never enter this component. Android/JVM adapters
retain their existing native custody; v1-v3 layouts stay in source and v4 cannot
silently downgrade. Historical binary compatibility and v4 lifecycle behavior
remain unqualified.

**Compilation and tests NOT_RUN:** the shared disk gate timed out before Gradle
started (exit 75). No prior results are attributed to this source. Focused
independent cases/review remain absent. Public creation remains unavailable; the
native history hold stays. Service dispatch, actual server cancellation/history,
RPC/UI composition and complete native/cold-reopen qualification remain unfinished.
The earlier coordinator's one TCP failure remains unresolved.
[Exact sources, framing, static observations and unstarted verification](../../qa/pos/traceability/creation-original-client-journal-20261112/README.md).

## Internal creation original coordinator — 12 November 2026

Source `979fa62a` connects protected preflight, optional password hashing outside
Room, final-writer reauthorization and exact actual native settlement. A prior
original is explicitly lookup-only; a new unused password is not reported as
installed. Fresh receipts require the captured original, actual COMMITTED native
settlement, exact prepared child and matching protected-final declaration.
**Public employee creation remains unavailable**, and the native history hold stays.
Original journal/service/cancel/restart, complete history closure and client
RPC/journal/UI remain unfinished. Focused coordinator/native execution is NOT_RUN;
independent review is not claimed.

The unchanged regression selection **FAILED**: **2,303 passed, one TCP failure,
zero errors, 24 platform skips** (2,328 methods). Security/UI reused prior task
results; database/local-RPC executed. Of 15 actual TCP methods, 14 passed and one
failed. Android/both iOS database compilation tasks completed. Gateway tests were
not reached; a separate request remained queued at the shared resource gate and
was cancelled before any build started. The retained-byte equality failure
(359,583 expected, 250,033 observed) remains unresolved. Official rc12 source shows
the counter includes shared transport allocations; this does not itself establish
body loss or a passing retention result. No assertion change or passing retry.
[Exact source, failed results, SDK observations and limits](../../qa/pos/traceability/creation-original-coordinator-20261112/README.md).

## Closed creation writer component — 12 November 2026

The internal creation staging frame now consumes the actual scoped input census,
seals the canonical D+5 INSERTs, verifies complete protected read-back/account and
admission census, and connects exact native mutation/capsule/protected-final
preparation. It preserves original IDs, current creator/peer checks and the unsplit
native deadline. Refused protected reviews retain the zero-write finalizer and
post-return check; capture hooks require the exact attached frame, and close drops
the expected image manifest too. **Public employee creation remains unavailable**;
the native history hold remains. No accepting default or service/RPC/UI dispatch was added.

The initial Long-counter compile error is retained and corrected. At clean
`25377d34`, existing checks passed **3,621 methods / 24 native platform skips / zero
failures/errors**, including all 15 actual TCP methods. Android and both iOS database
compilations passed. These checks do not execute the new staging path. Focused
independent/native creation qualification and independent review remain absent.
Full history closure, original-owning service/cancel/restart, settlement/confirmed
close and client journal/RPC/UI remain unfinished.
[Source, attempts, XML hashes and limits](../../qa/pos/traceability/creation-closed-writer-20261112/README.md).

## macOS, Windows and Android inspection downloads — 12 November 2026

The manual owner-package workflow provides macOS Apple Silicon/Intel DMGs,
a Windows x64 MSI and the retained-signer Android application/runtime-test pair.
All four artifacts were downloaded and verified against file manifests/checksums.
Desktop packaging and bounded packaged startup passed; Android input/source checks,
matching source build and fatal lint passed. The first Windows publication and
Android memory failures remain recorded alongside the successful retries.

**Inspection packages; full POS operation is incomplete.** Desktop launchers remain
unconfigured for trusted setup/Master/Client services. Android employee creation
remains unavailable. Packages contain application tree `a00d8303`; the later
creation lookup/writer/coordinator changes are not included. The auxiliary repository stays **PUBLIC**.
[Downloads, expiry dates, exact source/hashes and readiness limits](../../qa/pos/traceability/owner-test-packages-20261112/README.md).

## Exact employee-creation original lookup — 12 November 2026

Source `2225926b`, verified at clean `d107526a`, adds current-authorized read-back
of the exact original creator/request/recipient/digest and immutable creation event.
It uses the existing target-bound ADMINISTER read frame with zero writes and
confirmed close; a replacement login does not restore an old session. `NotObserved`
grants no safe absence, rollback or redispatch. **Employee creation remains
unavailable**, and the native history family remains held.

The unchanged five-module selection passed **3,621 methods**, with **24 platform
skips and zero failures/errors**. All **15 actual TCP methods passed**, with no
database skips; Android and both iOS database compilations passed. Focused
independent lookup/native creation qualification remains absent. Guarded creation
writer/native settlement/confirmed close, complete history closure, original
cancel/restart and client journal/RPC/UI remain unfinished.
[Exact source, task execution, XML hashes and limitations](../../qa/pos/traceability/creation-original-lookup-20261112/README.md).

## Current creation input preflight — 12 November 2026

Clean source `a00d8303` privately scopes the protected creation input comparison
to its actual zero-write Room transaction. Capture consumes it once with exact
request/recipient/creator/session/device/revision, original access-window and four
actual protected dependency hashes; callback exit/cancellation
retires it. Public review remains DATA. **Creation remains unavailable**; no writer
or accepting history family is enabled. Existing checks: **3,621 passed / 24 platform
skips / zero failures/errors**; all 15 actual TCP methods passed; Android/both iOS
Kotlin compilation passed. Matching retained-signer Android packaging and lint
passed; **not installed/uploaded**. Focused independent/native creation qualification
and source review are not claimed; tests and frozen expectations stayed unchanged.
[Source, execution/reuse, hashes and remaining work](../../qa/pos/traceability/creation-preflight-transaction-20261112/README.md).

Writer/native settlement/confirmed close, complete created-history qualification,
original recovery, client journal/RPC/UI, remaining roles and native platform scope
stay unfinished. Keep #13/#59/#3/#2 open, central held, auxiliary PUBLIC and financial/
renewal originals and unknowns intact.

The earlier source `634a9986` retains its completed-origin/replay/lineage/recipient
connections and [exact checks/package evidence](../../qa/pos/traceability/created-employee-history-origin-20261112/README.md).
Continue complete created-history closure and guarded all-INSERT writer/native
settlement/original lookup/cancel/restart/journal/RPC/UI without removing the hold
before full recovery is supported. Independent focused expectations/execution
remain absent.

## Earlier creation seed event join — 12 November 2026

Source `f92bf8fa` invokes the creation seed comparison inside the actual
chronological scanner. It retains ordered native commitments and the initial
historical ACCOUNT across the image-before-capsule grammar, then compares the
canonical profile with the real creator/census/admission prefix, preceding event
and native final. The result is initial DATA; **the unjoined hold remains** and no
created-actor origin/replay or writer is enabled. Creation remains unavailable.

Existing checks: **3,621 passed, 24 platform skips, zero failures or
errors**; Android and both iOS Kotlin compilation passed. Matching signed Android
packages passed assembly/lint/signature/alignment/package checks, not installed
or uploaded. [Source, commands, reuse, hashes and limits](../../qa/pos/traceability/creation-event-seed-join-20261112/README.md).
Independent focused/native execution remains **NOT_RUN**; no independent review
is claimed. No tests or frozen expectations changed.

Continue with a distinct creation origin and subsequent per-actor replay,
complete cold closure, then the guarded writer/settlement/original recovery/
journal/RPC/UI. Never fabricate a head enrollment or replace native membership
with a caller Boolean. Preserve all unresolved originals and the unsplit native
budget. Central stays held; all owning issues stay open; auxiliary stays PUBLIC.

## Foundation and delegated creation groundwork — 12 November 2026

Source `dfbcdadd` adds a distinct employee-created audit seed and joins the exact
initial protected images to the original creator and native final. Source
`c0369357` adds chronological Android traversal of every initial protected INSERT
and shared scanner checks for the exact image/plaintext/group boundaries. Extra
seeds stream within the existing cold-memory ceilings. Neither increment enables
employee creation or registers an accepting creation history family.

At clean `c0369357`, all five selected regression tasks executed: **3,621 passed,
24 platform skips, zero failures/errors**. Android and both iOS Kotlin compilation
passed. The matching retained signed Android pair passed assembly, fatal lint,
signer/alignment/package checks; it has not been installed, launched, instrumented
or uploaded. [Source, regression and package evidence](../../qa/pos/traceability/protected-seed-traversal-20261112/README.md).
The preceding [seed increment](../../qa/pos/traceability/delegated-employee-creation-seeds-20261112/README.md)
retains its separate source/results. Independent focused seed/native grammar
qualification remains **NOT_RUN**; no independent source review is claimed.

Continue with the chronological creator/census and initial-image semantic join,
a distinct native creation origin, subsequent per-actor replay and complete cold
closure. The guarded writer, prepared-child settlement, original lookup/cancel/
restart, client journal, RPC and shared creation UI remain unfinished. Preserve
original authority, ciphertext/history lineage and actual close; decoded seed DATA
does not supply any of them. Complete administration/role/delegation/defaults and
native STORAGE/PEPPER/Desktop/Apple composition remain development work.

Retained financial application is RECORDED, finalization DISPATCH_PENDING then
signal 9; renewal is REFRESH_PENDING with `original_revoked=false`. No rollback
or redispatch is inferred. Keep the unsplit 30,000-ms budget. #13/#59/#3/#2 remain
open, central stays held, and the auxiliary repository remains **PUBLIC**.


## Setup closure private presentation — 11 November 2026

Source `c09ca783` cancels setup-screen work and clears private results/checkpoints
and drafts on retirement. Suspended gateway replies and user edits require the
actual current activation, attachment and navigation token. A delayed cancelled
reply cannot republish old data or clear successor work. Original operation IDs
remain available for same-controller recovery; the durable actor journal remains
unchanged and still fences replacement requests. Runtime retirement permanently
closes the old controller. **14 controlled JVM methods passed**, zero failures,
errors/skips; Android and both iOS UI klib compilations passed. Existing frozen
expectations and original draft assertions remain unchanged. This is implementer
harness wiring and source inspection, not independent review or complete native
qualification. Current downloadable packages predate this UI change; refresh is
pending. [Exact source/checks/limits](../../qa/pos/traceability/setup-closure-retirement-20261111/README.md).

## Closed ancestor inspections — 11 November 2026

Sources `01130e99` / follow-up `e3203739` retain eligible literal ancestor inspections alongside their
already retained immutable originals only after the entire typed/resource stock
graph closes normally. Every requested original still reopens and exactly
matches on the active core loan; direct sources and selected writes remain actual.
The existing128-original/8-MiB envelope, full-proof fallback and scope clearance
before the next financial owner remain. The follow-up releases the graph root reference after
queue publication and on every close/failure, preventing unretained roots from
being indirectly retained through closed ancestor inspections. Compiler field
inspection confirms no hidden root field; it is not independent qualification.
Closed traversal lookup/queue APIs stay unavailable. **1,835 existing JVM methods passed**, two namespace skips, zero
failures/errors; Android and both iOS sales compilations passed with POS Gradle9.7.0.
The follow-up also passed1,835 existing JVM methods/two namespace skips and
Android/both iOS sales compilation. Revised signed application packaging/fatal lint
passed at source2f960d6b. Exact app f4130188 and unchanged retained source51
executor3213c7a0 are bound to auxiliary snapshot30dbfcb7. One
[Android42976531281](https://github.com/kira-manga/test/actions/runs/42976531281)
and one [Apple42976535981](https://github.com/kira-manga/test/actions/runs/42976535981)
runs are collected. Apple passed562 existing stock simulator methods, zero
failures/errors/skips. Android failed, one started/zero completed: durable
original/import and application RECORDED; finalization DISPATCH_PENDING then
signal9, with close/cold reopens unobserved and no cleanup. An actual native
exclusion request WATCHED_ROOM_GUARD_REFUSED was logged at the shared deadline;
this establishes neither finalization outcome nor rollback. No same-condition
redispatch is planned. The preceding source3c package passed but was not staged/dispatched. No measured speedup/native fix or
independent source qualification is established. The original unsplit shared
30,000-ms native budget and frozen executor/assertions remain unchanged.
[Source/checks/limits](../../qa/pos/traceability/closed-stock-ancestor-inspections-20261111/README.md).
The preceding source659 Android run failed with durable original/application and
unresolved finalization; Apple passed562 existing stock regressions. Preserve all
known originals and unresolved identities. Whole financial/platform/Epic and
owner acceptance remain open. Auxiliary remains **PUBLIC**.

## Current stock proof within one financial node — 11 November 2026

Source `b5e52af5` shares normally closed immutable stock root inspections within
one financial `requireOne(owner)`. Each requested original is actually reopened,
its exact identity/hash/body compared, and its direct sources and selected writes
read/compared again. The actual repository/core-access references and core owner
context remain required; the original-only facade exposes no current-stock reader
or authority. Existing128-entry/8-MiB optimization limits retain full-proof fallback.
Scope clears between financial owners so queued dependency edges cannot be hidden.
**1,835 existing JVM tests passed**, two namespace skips, zero failures/errors;
Android and both iOS sales compilations passed using POS Gradle9.7.0. Signed
application packaging/fatal lint passed at pristine source6593e72f (same production
bytes); application6f9401ac, retained source51 executor3213c7a0 and signer54f974f8
are bound to auxiliary snapshot707773f. Apple42969680909 passed **562 existing inventory/sales simulator methods**,
zero failures/errors/skips. Android42969676284 **FAILED**, one started/zero
completed: durable original, same-ID import recovery and matching economics were
observed; application `RECORDED`, then signal9 with finalization/close/cold reopens
unobserved and no observed cleanup. No exit cause or rollback is established. No measured speedup/native fix is established; frozen
executor/assertions and original
unsplit shared30,000-ms native budget remain. [Source/checks and boundaries](../../qa/pos/traceability/financial-node-stock-proof-20261111/README.md).
The source99 diagnostic failed, preserving durable original/application and
unresolved finalization; its bounded managed sample is linked below. Independent
review/qualification, platform composition, whole financial/Epic scope and owner
acceptance remain open. Auxiliary repository stays public.

## Collected native-payment original verification — 11 November 2026

Source `99e8ba9e` shares the existing finite immutable financial-original proof
within one existing-native-payment verification on the same writer loan. Complete
actual readers, current reads and identity/hash/write/provenance comparisons
remain; each reader drains ancestry, and `finally` clears the scope. The existing
bounds and original unsplit shared 30,000-ms native work budget remain.
**1,652 existing JVM tests passed**, two namespace skips, zero failures/errors;
Android and both iOS sales klibs compiled. The local check record discloses the
root-wrapper 9.6.1 invocation; subsequent DEX/signed packaging/fatal lint use the
POS-pinned 9.7.0 wrapper. No test/oracle change or measured speedup is claimed.
[Collected source/checks/pair and outcomes](../../qa/pos/traceability/native-payment-proof-20261111/README.md).
Apple passed **562 existing inventory/sales simulator methods**, zero failures,
errors or skips. Android42962802720 **failed**: one started/zero completed methods;
genuine cash capture/import and same-ID original recovery were observed, with
matching protected economics and financial application `RECORDED`. Finalization
remained pending before signal9; its outcome, final close and cold reopens are
unobserved, with no established exit cause. Preserve the original/application and
unresolved finalization. One diagnostic-only run42965755534 is bound to this exact
APK pair and snapshot991; it also **failed**, one started/zero completed. Its
[10,326-record managed sample](../../qa/pos/traceability/payment-finalization-sample-20261111/README.md)
again observes existing-original verification; zero stack mismatches. Finalization
remains unobserved; all frozen assertions and the original budget remain.

The collected source355 finalization sample failed: durable original/application
were recorded, finalization remained pending and the process exited signal9.
Its managed stack sampled repeated existing-original verification before any
observed finalization-handler result. Finalization, final close and cold reopens
remain unknown. [Actual diagnostic and limits](../../qa/pos/traceability/original-finalization-sample-20261111/README.md).
Preserve all known original/application and unresolved finalization/original
identities. Independent source/semantic qualification, native workflow/platform
composition and owner acceptance remain open; auxiliary repository stays public.

Preceding checkpoint, **11 November 2026**: source `355ea18a` shares exact immutable
original identities only after a whole stock producer graph closes normally,
within the existing private one-read scope. Fresh roots retain their full typed
ancestry/cycle/resource checks and cumulative envelope. Actual source lookups,
full comparisons, selected writes, current rows/claims/ranges and all authority
checks remain. `finally` clears entries before returning; optimization limits
fall back to full proof. No format, custody, frozen executor/assertion or original
unsplit shared 30,000-ms native budget change. **1,835 JVM methods passed**, two
namespace skips, zero failures/errors; Android/both iOS inventory compilation and
signed pair/fatal lint passed. [Exact source/checks/pair](../../qa/pos/traceability/stock-closed-ancestry-20261111/README.md).

Android42954657696 is collected: durable capture/import, same-ID recovery and
application `RECORDED`, then signal9 after finalization dispatch. One method
started, zero completed. Finalization outcome, final close and cold reopens remain
unknown. Preserve its known original/application and unresolved finalization
identity, plus every earlier unknown original. Apple42954664502 passed562 stock
simulator methods, zero failures/errors/skips; all74 manifest/75 checksums match.
One [pending-finalization diagnostic run42957042109](../../qa/pos/traceability/original-finalization-sample-20261111/README.md)
uses the identical signed pair/assertions and unchanged original native budget.
It is collected: the case failed with recorded original/application and unresolved
finalization. Its managed sample shows existing-original verification work, not
a native financial outcome or process-exit cause. [Predecessor results remain preserved](../../qa/pos/traceability/stock-producer-inspection-20261111/README.md).
No cause, rollback, safe recovery or controlled speedup is established. Independent
source/semantic review, platform composition and owner acceptance remain open.
Use no subagents and keep the auxiliary repository public. Desktop/iOS packages
remain unconfigured bootstraps. Follow [current status](CURRENT-STATUS.md) and
[installation instructions](INSTALL-AND-TEST.md).

- [Grouped streamed payment sources](../../qa/pos/traceability/payment-streamed-source-20261111/README.md) at `dbd89440`:
  every actual original lookup/full identity comparison remains. One finite
  financial-original proof qualifies each selected streamed producer once and
  matches all selected writes during one complete traversal; conflicts/duplicates/
  missing/incomplete sources refuse. No current/native/grant cache, stored-format,
  executor/frozen-assertion or original unsplit shared 30,000-ms budget change.
  Exact-source **1,652 JVM methods passed /two explicit skips**, zero failures/errors;
  Android/both iOS sales klibs and clean signed pair/fatal lint passed.
  Android42935013259 failed one method: Start returned `STORAGE_UNCERTAIN`,
  instruction authorization was not reached, and final native close was unconfirmed.
  No process-crash result or signal9 was reported. The original remains unresolved.
  Apple42935021614 passed all 562 stock simulator methods, zero failures/errors/skips;
  all 74 manifest and 75 checksum entries matched. A short diagnostic-only ART
  method sample at first stock preparation is prepared to identify actual work;
  exact APKs/assertions and the original unsplit native budget remain unchanged.
  Independent source review/qualification and complete native workflow remain open.

- [One-authorization original proof](../../qa/pos/traceability/instruction-original-proof-20261111/README.md)
  at `9087e117` passed1,652 JVM methods/two explicit namespace skips, Android/both
  iOS sales klibs and clean signed pair/fatal lint. Android42931770736 started one
  method, completed zero: recorded Start/native instruction preparation/retained
  Start read returned, then signal9 during domain authorization preparation.
  No instruction adoption, financial outcome or final cleanup observed. Exact
  pair/provenance verified; no cause/rollback/safe recovery established. Apple
  42931800602 passed 562 existing stock simulator methods, zero failures/errors/skips;
  all 74 manifest/75 checksum entries matched. Prior unknown originals remain held.

- [Current Android ownership/lifecycle correction](../../qa/pos/traceability/android-lifecycle-supporting-20261110/README.md):
  production `f01bbb41` binds the security-only lifecycle frame to current
  authorization and retains completed evidence through preparation; executor
  `5c25107e` waits for enrollment settlement. **18 focused lifecycle methods**,
  Android compilation, clean signed packaging/fatal lint and full database
  regression passed (1,067 passed, one skipped, zero failures/errors).
  Native STOP/close `42816765743` passed one API35 x86_64 supporting method
  without AOT: committed STOP, coordinator CLOSED/cached retirement, durable
  STOP preventing automatic reopen. Physical native close counts remain unbound.
  Background/reopen `42817587823` passed one supporting method without AOT.
  STOP/RESUME `42817587411` ended with signal 9 during pre-start history traversal;
  AOT cold publication diagnostic `42816992704` ended with signal 9 after all
  signing/root-endorsement markers returned. Neither completed a method; no SKU
  output or financial command returned. Recordings include exact APK/hash checks.
  Investigate bounded traversal cost and remaining publication settlement without
  changing the original budget or rerunning unchanged conditions.
  Prior failures remain retained. Independent review/full lifecycle acceptance
  remain pending. The original shared 30,000-ms native work budget is unchanged.
  The [installation handoff](INSTALL-AND-TEST.md) names the current Android APK
  separately from older desktop/Apple packages. Keep the auxiliary repository
  public per the owner's latest instruction; upload no private/signing material.

- [Bounded row DATA batching](../../qa/pos/traceability/android-history-row-batching-20261110/README.md)
  `b4e029e2`: 16 row entries /128 chunks /1 MiB per guarded native task, with
  existing scanner validation, earlier-row continuation refusal and original
  watchdog. Security 423 passed /24 skipped; final database 1,067 passed /one
  skipped, zero failures/errors; Android compilation and clean signed packaging
  passed. APK `c5b080d4` adds separate fixed completion-settlement markers.
  STOP/resume `42824104645`: one completed/one failed, scanner attempted to decode
  the already-cleared lifecycle lineage capsule. Cold AOT `42824111400`: zero
  completed, signal 9 after all five completion markers returned. Exact APK pairs
  verified and outcomes retained; shared iOS device/simulator klib compilation passed.
  Correct the lifetime defect and investigate post-body cold delivery;
  no unchanged rerun or budget extension/split/renewal. Independent review remains.

- [Lifecycle lineage intent lifetime](../../qa/pos/traceability/android-lifecycle-lineage-intent-20261110/README.md)
  fixed at `860ce540`; Android diagnostic forwarding corrected at `c9507d18`.
  Full database **1,067 passed /one skipped**, zero failures/errors; affected
  Android database and both iOS database klib targets passed. Clean signed
  packaging/fatal lint passed at `c9507d18`. Exact APK pair staged at auxiliary
  `d993eab`; STOP/resume `42829791429` completed one method with one failure:
  full scan/actual read close/re-observation returned, then admission refused
  RESUME with `WRONG_STATE`. Cold AOT `42829799371` completed no method, signal9
  after final custody/task return/result posting and claim entry. Exact APK pairs
  verified; both failures retained. No changed tests/assertions/inputs/timeouts/
  watchdog; no native pass or independent review claim.

- [Lifecycle audit closure accounting](../../qa/pos/traceability/android-lifecycle-closure-accounting-20261110/README.md)
  at `a476d536` counts only exact fully validated lifecycle event/image joins in
  closure totals; business counts and authority checks remain unchanged.
  Full database **1,067 passed/one skipped**, zero failures/errors; Android
  compilation and iOS device/simulator klib compilation passed. Async claim-result
  and watched terminal-cleanup diagnostics distinguish the remaining cold gap.
  STOP/resume `42834923867` passed one unchanged supporting method, including
  unauthorized RESUME refusal/STOP preservation and authenticated same-original
  RESUME. Unbound assertions remain unqualified. Cold `42834931436` completed no
  method; claim accepted, then `NATIVE_CLOSURE_UNKNOWN` before the Room deadline.
  Continue [closure-origin diagnosis](../../qa/pos/traceability/android-cold-closure-origin-20261110/README.md):
  bounded originating static frames and pre-cleanup executor diagnostics; preserve
  all assertions/fixtures and original 30,000-ms budget. Clean production package/
  fatal lint and changed executor build/signature verification passed at `735d4e8c`;
  exact pair staged at auxiliary `730ff955`. Once-dispatched cold AOT `42839072566`
  failed with zero completed methods/OS signal9; publication decode/match/signature
  returned. No originating error/exclusion diagnostic returned. Host-only `42841675151`
  retained genuine publication/five recorded setup replies, then unresolved
  `OPENING_STOCK` dispatch and zero completed methods/OS signal9. Preserve the
  unknown original. [Next static writer-failure observation](../../qa/pos/traceability/android-opening-stock-native-boundary-20261110/README.md)
  precedes mandatory exclusion; database regression passed at `5625ad49`
  (1,067 passed/one skipped). Revised fixed-phase/stock-loan observation source
  `5a47c628` compiled JVM/Android/iOS klibs and clean signed packaging/fatal lint
  passed; exact pair in auxiliary `780023a8`. Cold AOT `42848054092` dispatched
  once, pending. No lifecycle rerun. Review remains open.

- Latest follow-up: [ordinary FOOD AlreadyApplied recovery](../../qa/pos/traceability/food-payment-already-applied-20261106/README.md).
  Genuine native BEFORE selection and complete earlier Apply/Start/native-raw
  source proof are integrated and reviewed. Cold replay qualifies and retires
  earlier refusals before a later successful writer; it never lends a refusal as
  that writer. **132 selected JVM methods passed**, including 18 new supporting
  checks. Affected Android production compilation passed. The selected EGP 300 /
  bread-meat native fixture is now source-reviewed and its standalone Android
  harness compilation passed. It binds pre-staged R, first Apply, ordinary refusal,
  exact lookup, later FOOD writer and actual close/reopen/fresh authentication.
  Native execution is NOT RUN; no positive native/whole FAA result is established.
  Follow its [binding and invocation](../../apps/pos/apps/android/src/androidTest/kotlin/com/ukkera/pos/android/independent/nativefoodpaymentalreadyappliedv1/BINDING.md)
  in #63 after matching APK packaging; finish unbound FAA-Q01–Q04/current-authority,
  failure variants and required non-device/admitted-RPC integration in #59.
  Pending/scalar financial refusals and additional RETURN-stock source census
  remain unsupported. Current CASH_READ/original-recovery gates are unchanged.

- Previous follow-up: [FOOD financial/read/original-recovery RPC](../../qa/pos/traceability/food-financial-rpc-20261106/README.md).
  The explicitly selected `FOOD_CASH_FINANCIAL` profile admits normalized
  Begin/Finalize and ApplyOriginal, not physical Start or Draft/Add/Send.
  Shared Master/source checks, restricted gateway/UI and Android setup selection
  are integrated and reviewed. **111 distinct supporting/regression JVM methods
  passed**; affected shared and Android production compilation passed. Actual
  encrypted client-journal reopen is covered with controlled Master replies,
  not genuine Master settlement or admitted-RPC qualification. Existing form1
  journals and FOOD_CHECKS attachments are unchanged. The retained APK pair
  predates this increment; no new packaging/native run occurred.
  Next: source-qualified scalar refusals and pending Apply outcomes,
  genuine source/financial/restart/admitted-RPC integration, and the separate
  distributed physical companion. Missing sources remain unavailable, not
  reconstructed from current rows or falsely acknowledged. See #26/#51/#59.

- Previous follow-up: [FOOD CASH original custody and journal binding](../../qa/pos/traceability/cold-history-20261106/food-cash-original.md).
  Production `1946ebde` supports ordinary issued ingredients without reserving or
  issuing them again. Its 45 selected JVM passes are supplemented by two actual
  source-custody DATA checks in `44f5a0d0`; genuine Start/financial/restart behavior
  is not established by those checks. The original separate database selection did
  not execute; the latest AlreadyApplied batch passed all 14 FOOD CASH selector
  DATA methods, still not a native financial/restart result.
  Four Café SQL operations now use the exact native employee intent journal through
  Stage/Pending/Submit/acknowledgement and original-only lookup after reopen.
  Reviewed cleanup drains the native view before logout. Final Android compilation
  and refreshed signed app/test packaging passed; no native test ran.
  The pair also includes the [supplier-reference fixture](../../qa/pos/traceability/supplier-reference-v2-20261106/README.md#bounded-native-reference-fixture).
  Next: complete original-source proof and the remaining Branch/physical bindings,
  plus genuine admitted FOOD CASH RPC qualification. The separate financial
  profile above does not add financial/physical actions to FOOD_CHECKS.
  Whole FCC acceptance, genuine non-device composition and required RPC
  integration remain #26/#51/#59; device execution is separate in #63.

- [CURRENT-STATUS.md](CURRENT-STATUS.md) records revision-specific implementation
  and verification. Development completion and owner acceptance are both pending.
- [Completion policy](planning/WORKFLOW.md): close implementation only after its
  full defined scope, review and required unit/non-device integration pass.
  Pending device tests belong in [#63](https://github.com/ukkera/kmp-apps/issues/63);
  owner acceptance belongs in [#61](https://github.com/ukkera/kmp-apps/issues/61).
  Missing implementation and failed required automation are not deferred testing.
- [Current unpaid FOOD V3](../../qa/pos/traceability/food-current-payable-v3-20261106/README.md)
  and [supplier directory references V2](../../qa/pos/traceability/supplier-reference-v2-20261106/README.md)
  are source-integrated and reviewed, with selected supporting tests recorded
  separately from genuine Master/native/RPC qualification. V3 preserves original
  saved prices and earlier reads; supplier V2 retains V1 formats/references and
  never falls back after a failed V2 request. Finish pending verification from
  those records rather than reimplementing either contract.
  The selected FOOD checks passed 57 JVM methods (39 new V3); supplier checks
  passed 34 new methods plus all 57 selected existing supplier/warehouse methods.
  Android production and native-harness compilation passed. The later signed
  `0df3e184` app/test pair includes this source; no native test was executed.
  Existing `LocalDirectoryClientConnection` constructor forms retain V1 supplier
  composition. Current supplier-reference integrations must explicitly select
  `supplierReferences = true` with the `SUPPLIERS` profile before attachment;
  Android does this at its native composition root. The flag is an immutable
  supported-version selection, not peer negotiation or permission. Other profiles
  cannot enable it, and a failed V2 call must never retry as a V1 write.
- [Bounded native history #51/#53](../../qa/pos/traceability/cold-history-20261106/README.md)
  includes native-owned receipt DATA and reviewed exact retail-payment/refund V3
  original/Start cold selection. Start is the original physical instruction, not
  app startup. Original authorization, source pairs, writer, reservation and exact
  effects remain required; prefix DATA cannot grant authority or publish proofs.
  **Eight selected JVM DATA methods and affected Android production/runtime-harness
  compilation passed** for the refund follow-up; earlier 50-method payment results
  remain source-specific. The Android cash-original fixture binds real setup,
  capture, separate Apply/Finalize and two confirmed owner reopens; its corrected
  original-line assertions also compiled and final source review is complete.
  The latest fixture binds exact original issue/cost/timestamp through separately
  authorized stock history, with equality across both reopens; review and packaging
  passed. **No native run or whole COS/CRF/G513 pass.** Original displayed
  instruction, payment receipt, UI/LAN/failure bindings and non-device native
  composition remain open. Cost assertions are compiled, not runtime-verified.
  The supplier CASH selector follow-up now recognizes only its exact existing
  command/profile and retains full native/Start/purchasing/cash ancestry. Source
  review, three supporting DATA methods and affected Android compilation passed.
  [Supplier result and remaining scope](../../qa/pos/traceability/cold-history-20261106/supplier-cash-original.md).
  The refreshed Android pair below includes that supplier work.
- [Ordinary FOOD current-state closure](../../qa/pos/traceability/cold-history-20261106/ordinary-food-current.md)
  is now source-integrated: complete current census, genuine last-writer owners,
  literal original AFTER correspondence and current stock relationships, with
  unchanged security/retention bounds. Source review, **50 existing supporting JVM
  methods and affected Android compilation passed**. These do not directly exercise
  the new non-empty native join; no APK was rebuilt. Frozen ordinary-check cases
  remain NOT RUN. The newer V3 increment above supplies current mixed-state
  amounts and a reviewed bounded OFC-COLD-101 Android fixture using separately
  selected line/portion and catalog-revision bindings. Its independent closed and
  stronger pre-close FOOD source proofs, staff intent-journal and full UI/LAN/crash
  bindings remain unbound. Release-activation publication evidence and journal DATA
  must not be presented as the missing FOOD original-event/source proof;
  the 257-supplier workload is separate and unexecuted. Complete these bindings
  and qualify actual close/reopen and supplier originals. FOOD payment now has the reviewed
  selector/custody continuation above; its native qualification remains open. FOOD
  refund and unsupported approvals/refusals remain held; selectors still require
  their complete typed ancestry and effects.
- Shared permission-denial cleanup now attempts independent private holders even
  after one cleanup fails, and blocks local reactivation until failed ownership
  is resolved. [Source review and 25 focused JVM passes](../../qa/pos/traceability/session-denial-cleanup-20261105/README.md)
  cover bounded controller/lifetime behavior, not genuine Master/RPC/native
  closure. The refreshed Android pair includes this correction.
- The bounded current-session credential custody defect #64 is closed at
  [7c6bf7e1](https://github.com/ukkera/kmp-apps/commit/7c6bf7e122a7c62d088e57ae9390e48b560aeae2).
  Stock-count safety #65 is corrected with passing supporting tests, but genuine
  retained-history integration remains unverified. See the current status for scope.
- Shared acknowledged-original supplier lookup and durable-retention checks are
  integrated with [95 passing focused JVM methods](../../qa/pos/traceability/supplier-original-lookup-20261105/README.md).
  The later finite-approval work compiled the Android application and, before its
  last source correction, the native supplier harness. Native execution is still
  pending; preserve the whole-case gaps when continuing #59/#63. A later
  standard packaging run produced the earlier signed app/test pair at `75308e99`;
  packaging does not qualify the native workflow.
- [Invoice capture and matching #21/#22](../../qa/pos/traceability/captured-invoice-matching-20261105/README.md)
  connects immutable capture, exact accepted-goods matching and incremental payable
  recognition to shared restricted RPC/workspaces and Android LAN selection. The
  combined matching permission evaluates the entire original claim. Quantity-based
  goods status and unresolved-charge settlement guards remain distinct from money.
  **128 distinct supporting JVM methods passed** across security, planner, gateway,
  UI and RPC request-lifetime checks, including the corrected display-label rerun.
  Android application compilation passed on `6ae30726`. The later finite-approval
  increment also compiled the runtime harness after resolving the official Compose
  dependency and correcting its original-installation API call. No dependency
  version or production security API changed; no installed execution is implied.
  Final-source production Android compilation and **16 focused purchasing JVM
  methods passed**. Earlier DEX/lint-preparation failures remain historical;
  subsequent separate fatal-lint, app-package and runtime-test-package tasks
  succeeded at `75308e99`. Signatures, matching signer, minSdk24 and ZIP alignment
  were verified. Installed behavior remains unverified.
  The newer packaging result below supersedes that pair, without rerunning these
  source-specific unit tests. Genuine native/current-authority/persistence/admitted-RPC/
  restart qualification and variance/charge/service/no-PO resolution remain open.
  [Finite PO history](../../qa/pos/traceability/finite-purchase-history-20261105/README.md)
  now checks the original evaluation interval and retained native final decision;
  no accepting deadline or native-custody bypass was introduced. Missing/corrupt
  evidence stays held or uncertain; the higher-level unavailable-source response
  still needs implementation. Legacy V1 originals stay intact. The C22
  direct-base matcher is now source-reviewed: the unchanged CARTON12 PO supports
  22-EACH and staged 12+10 claims with exact pricing. **19 focused JVM methods and
  affected catalog Android compilation passed**; native source/authority, original
  refusal replay and whole workflow qualification remain open. FPH-010 needs an
  actual held-runtime/current-authority capability, not an unauthenticated response
  or a relaxed startup guard.
- The [native SIDC-02(b) fixture](../../qa/pos/traceability/supplier-invoice-capture-rpc-20261105/README.md#native-sidc-02b-fixture--source-reviewed-not-run)
  now wires the full independent business baseline, shared capture gateway,
  protected reads and original recovery across native close/reopen. It reuses the
  supplier fixture's shared native owner. The matching checkpoint records the latest
  compilation result; **native execution is pending**;
  it does not verify the editor, LAN RPC or a whole SIDC/ICR case. Use the existing
  Android runtime-test module rather than creating another fixture/module.
- The latest table-assignment checkpoint is
  [e2825ccf](https://github.com/ukkera/kmp-apps/commit/e2825ccf1f4a70daeb3fcf5255b843b0ed50a4e5).
  Its [345 selected JVM methods](../../qa/pos/traceability/food-table-assignment-rpc-20261104/README.md)
  are supporting/regression evidence, not complete business or native acceptance.
  The combined application compilation did not complete; no Desktop pass is implied.
- The current signed Android pair contains production `1946ebde` and the reviewed
  Café SQL-journal continuation. Fatal lint, application/test packaging, matching
  signatures, actual minSdk24/targetSdk37 and 16-KiB ZIP alignment passed.
  [Artifact identities and exact source/build limits](../../qa/pos/traceability/cold-history-20261106/food-cash-original.md#android-packaging-checkpoint--6-november-2026)
  are separate from startup, payment/restart and owner acceptance. Prior pairs are
  retained. No installation or native execution was attempted for this pair.
  [Delivery #43](https://github.com/ukkera/kmp-apps/issues/43) owns current artifacts.

The captured-matching gateway now also has [actual encrypted client-journal
close/reopen/archive coverage](../../qa/pos/traceability/captured-invoice-matching-20261105/README.md#encrypted-client-journal-recovery):
**38 selected JVM methods passed, four new and 34 existing**. Master/session/key
custody remain controlled; this does not remove the genuine native/current-schema
Master/RPC prerequisites below.

## Shared foundations and remaining integration

Paths in this table are relative to `apps/pos`.

| Area / owner | Existing production entry points | Remaining development boundary |
|---|---|---|
| Runtime and history: #12 / #51 | `core/database`: `AtomicMasterStore`, `LocalMasterEndpoint`, `RuntimeHistoryStartup`, `RuntimeHistoryRoomStartup` | Genuine original authority, current-schema native ownership and complete command/history/resource integration. Never remove a schema guard to substitute for a missing native owner. |
| Setup and authentication: #13 / #14 / #15 | `core/security`, database authentication services; `adapters/app-gateway/.../setup/LocalSetupCoordinator` | Complete real native acquisition, current-grant/session transitions and original recovery; preserve private-state cleanup and confirmed close. |
| Admitted LAN and sessions: #16 / #48 / #49 / #50 | `adapters/local-rpc`; shared gateway/session composition | Official P2pKit **0.8.0-rc12 is consumed**. Durable acceptance-before-Receipt is connected and supporting-tested through the official client/Host and POS bridge; a separate two-process/network-namespace TCP slice passed. See [current exact results](../../qa/pos/traceability/epic3-native-qualification-20261109/README.md). Genuine installed pairing/current-authority/application workflows remain unfinished; the old rc11/#543 dependency is historical. No POS-maintained SDK fork. |
| Catalog, inventory and purchasing: #4 | `domain/catalog`, `domain/inventory`, `adapters/catalog-store`, `adapters/inventory-store` | Finish complete workflows and original recovery, including positive source authority; bounded codecs or registered handlers alone do not finish a Feature. |
| Sales, cash and returns: #5 | `domain/pricing`, `domain/sales`, `adapters/sales-store`, workflow gateways | Integrate remaining financial/physical authorization, entitlement and recovery paths with actual persistence. Keep unknown money outcomes and original operation identities. |
| Restaurant/café: #6 | Shared FOOD checks, table assignment and kitchen profiles | Assignment-only RPC does not implement occupancy, seats or split/merge. Draft/Add does not implement Send/payment. Use each Feature's explicit remaining checklist. |
| Backup and recovery: #8 / #54 | `adapters/recovery-store`, shared backup policy | Real compatible native key/history custody and safe restore/readmission remain required. Archive encryption or copied SQL rows cannot establish authority. |
| Native platforms: #9 / #55 / #56 / #57 | Android setup composition; shared `AppDependencies`; owned Room startup | Android runtime qualification remains incomplete. Desktop/iOS defaults do not compose an authenticated Master/Client. Supply real native adapters; do not replace defaults with accepting stubs. |
| Production verification: #42 / #58 / #59 | Independent frozen cases and existing production harnesses | Bind actual original native custody, current schema, production services and admitted RPC. A fake fixture or a subset of supporting assertions is not a whole-case pass. |

The critical native prerequisite is **real current-schema ownership**, not another
copy of shared business logic. Same-device native work can proceed independently
of the upstream transport Receipt limitation. See [Apple integration](APPLE-INTEGRATION.md)
for framework, driver, storage/key and lifecycle boundaries; preserve Android's
compatible signed formats rather than impersonating Android identity on another OS.

## Working on a task

1. Read its scope, requirement IDs, dependencies, current commit/results and next
   unchecked step in GitHub. Reuse the [requirement map](planning/REQUIREMENT-ISSUE-MAP.csv).
2. Make the smallest correct integrated change. Put reusable rules in
   `commonMain`; platforms supply actual OS/native adapters.
3. Use independently defined expected behavior and relevant existing tests.
   Follow `qa/pos/AGENTS.md` for new design and harness wiring. Preserve frozen
   inputs, expected results and historical failures.
4. Run proportional checks with the ordinary Gradle wrapper in `apps/pos`.
   For example, `./gradlew :adapters:local-rpc:jvmTest` checks that module;
   application compilation uses `:apps:android:compileLocalTestingKotlin` and
   `:apps:desktop:compileKotlin`. These commands are examples, not passing results.
5. Record review, actual results and remaining scope in the Issue and parent Epic.
   Close only satisfied scope; continue with the next unblocked task.

## Invariants and preserved work

- The Master independently validates current employee, session, device and policy
  state for protected operations and result disclosure. Client grants, notifications
  and acknowledgement are not authority. Serialize revocation with business commit.
- Preserve original request/result identity, uncertain physical outcomes, durable
  effects/audit/outbox, native close and required format/schema compatibility.
- Preserve original requirements, frozen expectations, protected drafts, historical
  evidence and SDK records. Archived source is not permission to apply a patch.
- Central implementation **and verification remain held** pending actual local
  owner testing and separate explicit authorization. Queue remains a separate product.
- Authorized delivery remains ordinary `[skip ci]` commits/pushes on
  `implement/ukkera-pos-local`; no Actions, PRs, merges, releases or deployments.

[Architecture](ARCHITECTURE.md) · [Manual test catalog](manual-testing/README.md) ·
[Preserved pause checkpoint](paused-work/2026-10-28/README.md) ·
[Historical continuation record](https://github.com/ukkera/kmp-apps/blob/e2825ccf1f4a70daeb3fcf5255b843b0ed50a4e5/docs/pos/AGENT-HANDOFF.md)
