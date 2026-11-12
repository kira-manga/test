# Owner pause checkpoint — 28 October 2026

**DEVELOPMENT PAUSED / LOCAL SYSTEM INCOMPLETE / NOT OWNER-TESTING READY.**

The owner requested the nearest safe checkpoint before reorganizing the remaining
work into GitHub Epics, Features and Tasks. All helpers have stopped. No new feature,
SDK retry, issue creation or central work is authorized by this checkpoint.
The Git commit containing this record is the saved revision; its parent is
`57e12ee`. Source identity and evidence references are in [CHECKPOINT.json](CHECKPOINT.json).

## What is actually integrated

- Earlier commits `27baf44` and `57e12ee` moved setup/recovery/publication and
  password workflows into `commonMain`, retaining real native storage, service,
  key and crypto adapters. Opening-stock review and pairing-preparation recovery
  were also integrated. These are bounded code deliveries, not complete workflows.
- This checkpoint finishes the already-reviewed backup-policy extraction:
  `LocalBackupPolicy`, `LocalBackupSnapshotPolicy`, `LocalBackupFormats` and
  `LocalBackupPorts` now own reusable create/verify/retention/restore/replay policy.
  JVM code retains files, SQLite, AEAD, quarantine, leases and actual close/locks.
- Persisted formats, immutable-table/audit checks, quarantine retention and
  pin → stop-intent → marker → atomic-install ordering are preserved. Restore
  never grants writer admission. No business feature or migration is added here.

Backup implementation: `/root/android_installation_troubleshooter`.
Separate source review, integration, compile-tool allowlist and execution: `/root`.
No new test cases or expected results were authored. The executed cases are
unchanged historical regressions, not new independent acceptance design.

## Checkpoint verification (already executed)

Exact application fingerprint (1,005 local inputs):
`b76121e281dddff86e913aaf78e1a493d792543c4c42dad2cca1dee39825a9a0`.
Before/after snapshots matched for both runs; the final source-identity check
also matches. Root handoff documents and these inert archives are not app inputs.

Commands were run from `apps/pos`:

```sh
python3 -B tools/compile_local.py --offline --no-build-cache \
  :adapters:recovery-store:compileKotlinJvm \
  :adapters:local-master-host:compileKotlin
# PASS: 52.575 seconds; both requested tasks executed.

python3 -B tools/verify_local.py --offline --jvm-regression-modules core/recovery
# PASS: 55 existing JVM regressions, zero failures/errors/skips; 37.446 seconds.
```

Evidence remains machine-local under `apps/pos/.workspace/evidence/`:

- `local-compile/20261028T182300.415563Z-9bd480afdbca4bf2b8dc5d8a41ca8b0b`
- `local-only/20261028T182541.519259Z`

**Limits:** core recovery tests do not exercise full backup orchestration, native
restore, real local RPC or application end-to-end behavior. No recovery-store
adapter tests exist. Existing backup profiles support schema 5/6 and the JVM host
pins 6; current schema 7/native/mobile composition remains incomplete. No new APK,
device run, iOS run or complete local acceptance result is claimed.

## Unfinished source saved, NOT applied

The three patches below are inert UTF-8 source archives outside application source
sets. They are not alternative implementations in the live app, build outputs,
mocked features or integration-ready deliveries. They preserve unfinished work in
Git without breaking the active source tree. Exact hashes/preimages/postimages,
ownership, missing targets and dependency snapshots are recorded in their JSON
sidecars. Do not apply them blindly or rewrite the sealed bytes.

| Archive / author | Saved work | Blocking gaps |
| --- | --- | --- |
| [session-attachment.patch](session-attachment.patch), `/root`; source reviewer `/root/android_installation_troubleshooter` | Ten common UI targets: attachment identity, controller retirement, data-only continuation and draft/request-ID retention; corrected controller keys and immutable ACK dispatch gateway. | Native-card employee DENIED cleanup must be distinct from company-head refusal; rapid inactive→return cleanup is not established; actual gateway dependency is unfinished. No compile/tests. |
| [device-admission.patch](device-admission.patch), `/root/opening_stock_delivery` | Shared/native admission, policy codec and target custody candidate; 45-target roster, 43 present postimages (four unchanged), two absent. | **NOT BUILDABLE.** Missing `OriginalLocalSdkRpcSource.kt`, `RuntimeDeviceAdmissionHistoryJoin.kt`, registry/key methods, original scoped dispatch, pre-write fencing, own-auth settlement/cleanup and app/restart joins. |
| [sdk-runtime.patch](sdk-runtime.patch), `/root/sdk_rpc_continuation` | Fifteen common SDK owner/peer/identity, typed prelude, scoped-channel, target-driver and rotating-gateway targets. | **NOT COMPILED / NOT OPERATIONAL.** Close-result/concurrency and cleanup races, raw-kit reference identity, acquisition-error custody, API dependencies, original serving/admission/readback and UI integration remain unresolved. |

The exported [session review](session-source-review.md) and
[SDK review](sdk-source-review.md) are source reviews, not independent test designs.
The fresh independent UI designer call failed at the thread limit; no designer,
test cases or expected results were fabricated.

The SDK dependency snapshot observed the **original** UI packet, not the corrected
UI archive here. Reconcile those hashes and unresolved APIs before any integration.
Its separately retained final native-dependency sidecar reports all 43 native
pins match; its path/hash are in `CHECKPOINT.json`. That observation is not a build.
SDK first-frame APIs still cannot safely replay a consumed HELLO. Explicit
prelude/scoped serving remains unwired; do not fake sessions or first-frame demux.

Original stages, reviews, evidence and earlier paused work remain unchanged in
`.workspace`. This export does not commit local keys, data, caches or raw logs.
Protected unfinished QA drafts and the four untracked opening-inventory freeze
copies remain local and untouched; they are not newly frozen/executed acceptance.

## Remaining work and truthful scope

- Complete genuine device enrollment/admission, SDK/RPC master/client serving,
  session refresh/revocation and UI attachment integration against actual services.
- Complete remaining positive business and restart/reconciliation paths across
  sales/payments/drawers/returns, inventory/opening stock/purchasing, food workflows,
  reporting and current-schema backup/recovery. Existing modules/screens are not
  proof of full application delivery; restaurants, cafés, clothing and supermarkets
  all remain required.
- Resolve the retained two security expectation failures and obsolete database
  raw-throttle fixture without weakening expectations or restoring an unsafe API.
- Design missing tests independently, freeze them before execution, run real
  persistence/RPC/failure-recovery acceptance, and qualify actual platform builds.
  iOS master/LAN/Keychain composition and native/device checks remain incomplete.
- Produce a current signed Android testing build, synthetic setup/accounts and
  hands-on checklist after the relevant integrations pass. The old 12:40 APK was
  packaged only, not installed/run, and excludes this checkpoint's changes.

SDK recipe `d2c978a2df5c9a32ccee037a0abf92fd8af30837e93f48c1257839b166849760`
was consumed by an actual producer stopped at `HOST_MEMORY_RESERVE` during Apple
compilation. Preserve source, outputs and failure. Do not rerun materializers,
reuse that source as fresh, fabricate receipts or automatically retry it.

The authoritative [requirement register](../../../../qa/pos/traceability/shared-password-kmp-20261028/REQUIREMENT-STATUS.csv)
retains **105 features, 141 original scenarios and 79 gates; zero complete
requirement acceptance passes**. This extraction changes no requirement status
and does not inherit previous-source passes. The
[original requirements](../../original-handoff/) and
[agreed plan](../../plan-review/FINAL-PLAN.md) remain authoritative and unchanged.
Central implementation **and verification remain HELD** until owner local testing
and explicit approval.

## What happens after the owner resumes

1. Audit existing GitHub Issues and reconcile them against the complete requirement
   register and saved blockers before proposing any new Epics/Features/Tasks.
   Reuse existing Issues; no Issues or Epics were created for this checkpoint.
2. Assign dependency-ordered, bounded tasks with clear implementation ownership,
   independent test-design ownership and observable completion criteria.
3. Resume the genuine SDK/admission/session integration first, then remaining
   business/recovery and platform work with its required verification.

For now: **pause development**. Ordinary commits/pushes are allowed only on
`implement/ukkera-pos-local`, with `[skip ci]`; no Actions/PRs/deployment. Retain
4 GiB free disk, 6 GiB available RAM and one owned heavy operation when work resumes.
