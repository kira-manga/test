# Local execution plan

**Local development authorized; incomplete; central implementation and verification held.**
[Tracker #2](https://github.com/ukkera/kmp-apps/issues/2) is the live backlog.
Use [the two-milestone completion policy](WORKFLOW.md); this page is an execution
plan, not a second set of Issue statuses.

Deferred emulator/simulator/physical execution is tracked in
[#63](https://github.com/ukkera/kmp-apps/issues/63), separately from implementation
closure. Finish full workflows with required non-device integration and review;
do not suspend independent coding for runtime-tool failures. Known application
defects and unfinished native/platform composition remain implementation blockers.

## Dependency-led order

| Boundary / existing Issues | Next deliverable | Development evidence required |
|---|---|---|
| Official RPC #48 / admission #49 / sessions #50 | Official P2pKit 0.8.0-rc11 is consumed; finish admitted serving, current-policy continuity and original-result recovery. Durable application acceptance before transport Receipt depends on upstream #543. | Production admission/RPC, current-authority, refresh/revocation and confirmed-close checks. The resolved release prerequisite is not a dependency on closing all of #48; a build alone does not finish qualification. |
| Native history #51 → persistence/RPC fixtures #59 | Finish command, original authorization, resource and native history joins across installed workflows. | Frozen cases through real production services/storage/RPC, including restart, duplicate requests and uncertain results. No accepting history callback or fake session. |
| Receiving #52 and returns #53 | Bind genuine documentary policy/retained-product authority and immutable original-sale/customer-delivery/refund rights. | Positive and rejection, concurrent, partial and restart workflows using real persistence; supplier/customer physical outcomes remain distinct from documentary decisions. |
| Backup #54 → #36 | Bind full native history and recoverable key/anchor custody; implement recorded current-schema restore and platform/UI composition. | Encrypted full-state create/verify/restore, corruption/wrong-key/cancellation/restart/retention and post-recovery business checks. Restore must not admit a writer automatically. |
| Android #55 → runnable prototype #43 | Compose real local setup, authentication, backend/RPC, journals and business services; package a signed local candidate with synthetic setup. | Required automated startup, persistence, backend/RPC and restart checks; identify tested revision and unverified physical checks separately. |
| Regression repair #58 and automation #42 | Reconcile preserved failures/ownership and complete test fixture bindings without changing frozen expectations. | Meaningful unit and production integration outcomes on the integrated source; unrun/blocked tests explicitly distinguished from passes. |
| All remaining local Features and #56/#57 | Complete business breadth, shared UI and Windows/macOS/iPhone/iPad native composition in dependency order. | Required per-feature automated tests and actual native build/integration results; unavailable platforms remain blocked, not passed by JVM compilation. |

SDK/platform blockers affect dependent integration, not all independent source
work. Complete master-authoritative route coverage alongside these boundaries:
#12/#13/#14/#16/#35 and #49/#50. Reuse the existing current-authorization engine;
verify stale clients, missed notifications, in-flight revoke/commit ordering and
reconnection/restart rather than relying on client acknowledgement.

## Historical evidence baseline — 29–30 October 2026

The following results apply only to their linked revisions. For later integrated
work and remaining scope, use [CURRENT-STATUS.md](../CURRENT-STATUS.md) and the
[live tracker](https://github.com/ukkera/kmp-apps/issues/2).

- Closed-rejection dispatch now keeps the aggregate read-trace parser Apply-only,
  preserving native dependency and original authorization checks. Separate source
  review accepted the correction; database/catalog JVM and Android compilation
  executed successfully. **28 unchanged retained-command codec regressions passed**
  on Android host, not native refusal-history acceptance. Native/restart/RPC and
  whole cases remain NOT_RUN; all policy-unavailable histories stay held.
  [Source/results](../../../qa/pos/traceability/rejection-read-trace-20261029/README.md).
- The recorded shared unstaged FOOD menu restoration preserves original selection/notes
  and adopts only compatible fresh source versions. Accepted denial survives
  cleanup without weakening source retirement. Separate source/harness review;
  three menu slices and two late-reply methods/four schedules plus 68 regressions
  passed (73 methods), and Android/Desktop compilation executed. The initial
  expected-DENIED/observed-UNAVAILABLE failure is preserved and fixed in source.
  All seven whole SFC cases, complete economics/capacity/staged-original and
  genuine Master/native/persistence/RPC/platform requirements remain unverified.
  [Recorded source/results](../../../qa/pos/traceability/food-menu-source-continuity-20261030/README.md).
  Earlier [recipient refresh](../../../qa/pos/traceability/food-recipient-refresh-20261030/README.md)
  and [exact-source restoration](../../../qa/pos/traceability/food-session-source-restoration-20261029/README.md)
  retain their source-specific evidence; the newer run does not relabel it.
- Closed purchasing stale/reused-publication rejection history is integrated and
  separately source-reviewed; catalog JVM/Android compilation passed. All
  policy-unavailable refusals remain held, and native restart/persistence/RPC
  behavior is NOT_RUN. [Source/results](../../../qa/pos/traceability/purchasing-rejection-history-20261029/README.md).
- Latest recovery/history/profile checkpoint: shared mobile recovery targets and
  retained Start joins reviewed; affected compilation passed. The same 29
  protected-storage regressions passed on JVM and Android host. Native history,
  full backup/restore, Apple execution and the separate database throttle fixture
  remain unverified. [Source/results](../../../qa/pos/traceability/shared-foundation-20261029/README.md).
- Shared policy adoption: eight gateway/journal slices, two runtime/navigation
  slices and 45 unchanged UI regressions passed; Android/Desktop compiled. Actual
  master/RPC/native races and complete session cases remain unverified.
- Historical SDK observer/recipe results remain in their original traceability
  records, but their custom build gates are retired. The `df01b98` SDK Gradle
  build succeeded while custom final validation failed; neither fact proves
  current official-library integration. F13 belongs upstream; suitable official
  behavior plus real POS admission/RPC tests remain required.
- `cbd848f`: shared session presentation; affected compilation and 45 unchanged UI
  JVM regressions passed, real attachment/RPC continuity unfinished.
- `69f59cb`: report-history producer; compilation and 108 unchanged sales/store
  regressions passed, native export/restart and full reports not verified.
- `79ae408`: shared backup/schema inspection compiled; no full backup/restore
  execution, current Room 7 restore still refuses before maintenance.
- `2786140`: 33 complete session cases/70 variants frozen; whole cases remain
  NOT_RUN despite the separately reported bounded slices.

[Evidence and limits](GITHUB-STATUS-EVIDENCE.md). None of these counts establishes
whole-Feature development completion or owner acceptance.

## Working cycle

1. Review the current Issue, requirement IDs, public contract and actual dependency
   status; assign a bounded change without creating duplicate backlog items.
2. Preserve source/compatibility and review incomplete archives before integrating.
   Normal builds may be rerun; do not alter historical evidence or invent authority
   for unavailable adapters.
3. Freeze independent cases before execution. Implementers do not author their own
   expected outcomes; wire the real production boundary without weakening assertions.
4. Run appropriate local unit/integration checks using isolated synthetic data.
   Keep source review, compilation, regressions, production integration and physical
   acceptance distinct. Required automation unavailable in an environment is blocked.
5. Integrate reviewed changes, resolve known in-scope defects, save the authorized
   revision and update the Task/Feature plus its Epic with meaningful evidence.
6. Continue the next highest-value unblocked local scope. Close only satisfied
   development criteria; link outstanding manual acceptance to #61.

## Delivery and owner acceptance

#43 delivers runnable local builds, setup/test accounts, synthetic sample data,
exact tested revisions and limitations. #61 owns the separate [manual test catalog](../manual-testing/README.md),
with external deployment/legal/recovery facts under #60. A manual-only delay does
not block an otherwise development-complete Feature. Missing code or required
automated integration cannot be transferred into the manual lane.

The first usable prototype is a milestone, not a checkout-only scope reduction.
All four business activities and five platform families remain required. Central
work under #11/#44–#47 requires actual local owner testing and **separate explicit
authorization**; neither milestone closure grants it.

The [original package dependencies](DEPENDENCIES.md) and
[sealed pause record](../paused-work/2026-10-28/README.md) remain preserved history.
Private execution instructions are local only; no workflow, deployment, release or
central task is started by this plan.
