# E3-004 native original ACK — supplemental v4-c1 segments

Status: **SOURCE REVIEW + COMPILATION ONLY — NATIVE RUN NOT RUN**. These are bounded supplemental
segments against the inherited production at the selected task revision. A successful run
would be segment evidence only. It would not be a whole `NAC-*` case, a whole SAS/OSR
result, a dependency closure or owner acceptance.

The frozen E3-004 v4-c1 contract/cases/fixtures remain the oracle. No production source,
frozen design byte, test expectation or other owner's lifecycle/training harness is edited
here. The existing `NativeAcknowledgementRepeatTest` remains the already-confirmed repeat
segment; it is not replaced or rerun implicitly.

## Wired segments

One fresh isolated nondebuggable `com.ukkera.pos.local.testing` installation per selected
segment. The genuine setup fixture (`NativeLocalFixtureOwner` with `SasFixturePlan`) settles
one synthetic original through the production native setup path. No fake native data,
synthetic receipt, accepting verifier or second fake engine is supplied.

### `INSTALLATION_ONLY_CONFIRMED_READ_REPEAT`

Bounded linkage: frozen `NAC-002-C` / `NAC-001` action 4 / `NAC-008-A` repeat portion.
The actual owner is opened with `AndroidSelfOwnedMasterService.forOriginalInstallation`.

- Reads the saved original company/definition/receipt through `readOriginalSetup()` four
  times (an initial read, one read in each repeat cycle and a final readback), observes the
  confirmed ACK through the public `readOriginalAcknowledgement()` four times, and observes
  the already-confirmed completion twice through the public
  `resumeOriginalAcknowledgement()` read-only completed branch.
- Requires every public ACK read to be `Observed(CONFIRMED, ...)` and to carry exactly the
  same saved receipt wire bytes; a `PENDING`, `Busy`, `Unresolved` or changed receipt result
  fails the segment.
- Compares actual receipt wire bytes, original request/issued-attempt/sequence/definition/
  genesis/authority epoch and every acquired coordinate (allocation, installation,
  assignment, device, first actor, storage instance, storage key, pepper key, target signing
  key), plus protection/native-binding identity.
- Requires the installation-only mode to stay `INSTALLED_RUNTIME_HELD`; requires a second
  native open to be distinguishably `BUSY` (one owner).
- Takes independent closed-native cuts and native ACK-state observations before and after
  the whole read/repeat episode and a confirmed close. The cut comparison covers every
  current-schema application table row/count and the branch/security identity; the native
  ACK-state comparison covers record digest/generation, pending/role/attempt, receipt,
  first-child and issuer-acknowledgement digests. Any second ACK, second receipt/SQL/key/
  enrollment, owner or history write fails the segment.
- Retains `NA-U1`–`NA-U10`; it does not assert a product status/enum/envelope/owner signal.

### `CONFIRMED_CLOSE_REOPEN_REPEAT`

Bounded linkage: frozen `NAC-006-B` confirmed-close/reopen portion plus the `NAC-002-C` /
`NAC-008-A` repeat portion; it does not execute the whole `NAC-006-B` case.

- Opens the actual operational original through
  `AndroidLocalBranchRuntime.openOriginalMaster`, reads the saved original three times,
  observes the confirmed ACK three times through `readOriginalAcknowledgement()` and
  observes the confirmed completion twice before close.
- Performs one confirmed `quiesceAndClose()` through the production owner, takes an
  independent closed cut and native ACK-state observation, then reopens **existing-only**
  through the same production runtime.
- On reopen, compares the exact original anchor, saved receipt bytes, definition digest,
  generations, ACK digest and every count/row with both the pre-close and closed cuts. A
  fresh service incarnation and one current writer are required; a second same-original
  service incarnation is rejected as well as an admitted second writer.
- Repeats two setup reads, two public ACK reads and two completion observations after
  reopen, then confirms the second close and takes the final independent cut. No second ACK,
  receipt, installation, credential, key, SQL enrollment, database, Room pool, storage
  instance or owner is accepted.
- `NATIVE_CLEANUP_CONFIRMED` is recorded only as the production close's actual drain result;
  the exact independent cleanup/owner-release signal remains `NA-U6`/`AOSF-UB1` unbound.

## Build and run

From `apps/pos` with JDK 21 (source/compilation only in this task):

```sh
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :apps:android-runtime-tests:compileLocalTestingKotlin
```

After installing the matching isolated application/test APK pair on a fresh target, one
segment per installation:

```sh
adb shell am instrument -w \
  -e class com.ukkera.pos.android.independent.nativeoriginalackv4.NativeOriginalAckRecoveryTest \
  -e nativeAckV4Segment INSTALLATION_ONLY_CONFIRMED_READ_REPEAT \
  -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner

adb shell am instrument -w \
  -e class com.ukkera.pos.android.independent.nativeoriginalackv4.NativeOriginalAckRecoveryTest \
  -e nativeAckV4Segment CONFIRMED_CLOSE_REOPEN_REPEAT \
  -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner
```

Evidence is app-private at `noBackupFilesDir/native-ack-v4-selected-segment.json`; it contains
only generated non-secret identities, digests, events and unbound obligations. A second
selected segment must use another fresh installation.

## Delta source review at 8932e6f7 (source-only)

This review read the actual production source at `8932e6f7` (including the actionable fix in
`4a38eaeb` and the follow-up `8932e6f7`), not producer claims. It is a source review, not a
native run, device result or owner acceptance.

Prior-finding disposition:

1. **Post-ACK admission failure hiding the confirmed native ACK: addressed in source.**
   `finishReopenedAcknowledgement()` now captures `installedReceipt` from the
   native-ACK-bound `recovered.record.receipt` immediately after
   `custody.acknowledgeOriginalInstallation()` returns and before
   `AndroidRuntimeHistoryStartup.select` can deny native DATA; the reopened anchor is retained
   until admission completes. On a later failure, `originalInstallationFailure()` returns
   `Installed` from that saved receipt, denies the partial composition, and moves the mode to
   `UNRESOLVED`. `readCompletedOriginalAcknowledgement()` and
   `readOriginalAcknowledgement()` expose that saved completion while `closing == null`.
   `observeConfirmedOriginalReceipt()` independently requires `pending == null`, a retained
   issuer acknowledgement, parent/first-child, `attempt.phase == COMPLETED`, and exact
   receipt digest/genesis equality.
2. **Missing read-only ACK observation: addressed in source.** Public
   `readOriginalAcknowledgement()` returns the saved native `CONFIRMED` or a fresh native
   `PENDING` with the saved receipt, or `Busy`/`Unresolved`. It performs no acknowledgement,
   resume/restart, Room open, owner acquisition or endpoint publication; `Busy` is returned
   for installation/read contention and `Unresolved` for closing or inconclusive native state.

Receipt immutability/exactness review:

- `installedReceipt` is assigned only from a fully scanned `Selection.Opened` receipt, the
  initializer after `owner.acknowledgeInstallation()` returned, a resumed native ACK
  completion, the post-ACK recovered receipt captured before admission, a post-admission
  receipt whose digest/genesis are checked equal to that recovered receipt, or an observed
  native record whose issuer ACK, phase and receipt digest/genesis match the expected saved
  receipt. No path mints a new receipt, re-signs the ACK, or treats `PENDING`/parsed bytes as
  confirmation.
- The public read and completed outcome return the same saved receipt; the adapted confirmed
  segments compare its wire bytes with the independently read saved receipt on every read.
- `installedReceipt` is read under the installation slot. `start()` writes it before the
  service escapes. Reads treat slot contention as `Busy` and closing as `Unresolved`; close
  joins the installation job and takes the same slot before closing custody. No new
  business/readiness/owner authority is published from the saved receipt.

Remaining source-review limits (unexecuted, no fake mechanism):

- No genuine public fault hook forces post-ACK history/Room admission or activation failure.
  The saved-completion/`UNRESOLVED` and `composition.deny()` branches are source-reviewed only;
  the admission-failure cut remains **NOT RUN**.
- No deterministic public checkpoint produces a real pending original. The public read
  `PENDING` branch is **NOT RUN**; this harness does not simulate pending/unknown, create a
  fake native engine or use a mirror service.
- No public boundary withholds/terminates the ACK reply or forces overlapping resume callers.
  Lost-delivery/unknown recovery, the new `INSTALLING` `Busy` branch and contention ordering
  remain **NOT RUN** (`NA-U3`/`NA-U4`).
- Conflict variants remain unpresentable through the public completion action; they are
  **NOT RUN**.
- Exact product ACK/attempt/envelope/status representations remain unbound (`NA-U1`/`NA-U2`).
  The read DTO is an implementation observation, not a product-status oracle.

Verdict: **no actionable source-review blocker found in this delta** for the claimed
saved-native-ACK receipt preservation and read-only observation. This does not qualify the
whole `NAC-*` case, an Android/native run, an OSR/AOSF result or owner acceptance.

## Historical source-review findings at 162c4a3b (before the 4a38eaeb/8932e6f7 fix)

The following records the earlier review findings and remains for history. Findings 1 and 2
were dispositioned at the current revision as described in the delta review above. The line
references describe the older source and are not current dispositions.

The review covered `AndroidSelfOwnedMasterService`, `AndroidRuntimeHistoryStartup`,
`RuntimeHistoryStartup`, `RoomRuntimeHistoryOwner` and the original native owner
(acknowledgement/completion/close paths).

1. **Post-ACK re-admission failure can hide a confirmed ACK as `UNRESOLVED` on the same
   service.** Exact trigger: startup classifies the original as `INSTALLATION_ACK_PENDING`,
   `resumeOriginalAcknowledgement()` enters `finishReopenedAcknowledgement()`, the native
   `custody.acknowledgeOriginalInstallation()` succeeds, then `reopenedAcknowledgement` is
   cleared (`AndroidSelfOwnedMasterService.kt:1310`) and
   `AndroidRuntimeHistoryStartup.select` throws or returns `Held` / an unopenable Room
   (`:1313-1315`). The catch calls `originalInstallationFailure()`, which returns
   `Unresolved` and sets `UNRESOLVED` because both `composition` and the cleared
   `reopenedAcknowledgement` are absent (`:1338-1343`). A later
   `resumeOriginalAcknowledgement()` takes `readCompletedOriginalAcknowledgement()`, which
   rejects `UNRESOLVED` (`:1281-1284`), so the durable confirmed ACK is not observable on
   that service until a confirmed close and a new existing-only reopen. The native result is
   confirmed; the public same-service repeat is therefore not terminal as required. This is
   a bounded pending/reopen recovery defect, not a reason to fabricate an ACK status.

2. **No public read-only ACK-state observation exists for a pending original.**
   `readOriginalSetup()` explicitly admits only `ALLOCATION_ONLY`,
   `INSTALLED_RUNTIME_HELD`, `OPERATIONAL` and `POLICY_HELD`
   (`AndroidSelfOwnedMasterService.kt:1157-1159`), so it throws in
   `INSTALLATION_ACK_PENDING`; `readCompletedOriginalAcknowledgement()` is private and only
   serves already-completed modes (`:1280-1304`). The UI pending projection deliberately
   clears the receipt (`LocalSetupCoordinator.presentPendingAcknowledgement`). Thus a
   pending original cannot be read through a caller-facing read-only surface without first
   invoking the explicit resume action. The exact ACK read/envelope representation remains
   unbound (`NA-U1`/`NA-U2`); this is recorded as a missing deterministic observation
   surface, not as fabricated product status.

3. **The ACK_PENDING checkpoint has no deterministic public interruption hook.** Natural
   pending exists only between the native settlement and
   `RoomSelfOwnedBranchInitializer.initialize`'s `owner.acknowledgeInstallation()`, because
   the initializer acknowledges on every successful settlement. A test cannot stop exactly
   there using public APIs without a process kill at the precise window; there is no
   production fault-injection/checkpoint API. The pending/resume, caller-cancellation and
   lost-delivery branches therefore remain **conditional / NOT RUN**, not forced with fake
   native data. Adding a production hook solely for this harness is outside this test-only
   task.

4. **Lost delivery / unknown is not a distinct reachable durable outcome in this service
   path.** `resumeOriginalAcknowledgement()` converts any ACK work failure to
   `originalInstallationFailure()`, i.e. `AcknowledgementPending` while the owner still
   reports an unacknowledged completion, otherwise `Unresolved`
   (`AndroidSelfOwnedMasterService.kt:1258-1267`, `:1338-1343`). There is no public
   withheld-reply/terminated-attempt boundary that produces the frozen `ACK_UNKNOWN`
   semantic; `Unresolved` is the actual production observation and must not be relabelled.
   Lost-delivery recovery remains unbound/unexecuted.

5. **Conflict variants `NAC-008-B..O` cannot be presented through the public completion
   action.** `resumeOriginalAcknowledgement()` accepts no receipt/identity argument; it
   always uses the custody-bound original. There is no public way to submit a different
   canonical receipt/identity to the same owner without opening a different original. The
   exact conflict representation remains unbound; the fourteen-field rejection cases are
   **NOT RUN**, not silently passed by the internal identity checks.

6. **Concurrent callers during the first ACK attempt observe `Unresolved`, not a supported
   waiting/declined outcome.** While the first caller has set
   `life = INSTALLING`, a second caller fails `life != INSTALLATION_ACK_PENDING`
   (`AndroidSelfOwnedMasterService.kt:1246`) and enters
   `readCompletedOriginalAcknowledgement()`, which rejects `INSTALLING`
   (`:1281-1284`). This may be an honest unknown/contention observation, but it creates no
   separate attempt record and cannot distinguish `ATTEMPT_WAITING_NATIVE_CLEANUP` /
   `ATTEMPT_DECLINED`. Exact contention ordering/status is `NA-U4`; the concurrent variants
   remain unbound.

Positive source observations (not defects): the native claim/close path retains one owner,
the close completion is cached and refuses a second physical retry
(`quiesceAndClose`: `:1362-1404`), the completed read/short-circuit is idempotent
(`readCompletedOriginalAcknowledgement`: `:1280-1304`), and native ACK signing itself is
idempotent for an already-retained claim
(`AndroidLocalCompanyAuthority.kt:200-253`, `AndroidSelfOwnedHistoryAnchor.kt:557-566`).
The confirmed repeat test at `NativeAcknowledgementRepeatTest` already exercises the
already-confirmed completion; it is unchanged.

## Conditional / unbound segments (honest status)

- Pending checkpoint, explicit same-original resume from pending, and cleanup wait: **NOT
  RUN / conditional** — no deterministic public interruption checkpoint.
- Waiting-caller cancellation and late separately owned cleanup: **NOT RUN / conditional** —
  requires a genuine pending wait.
- Lost reply, process-terminated attempt and finite unknown recovery: **NOT RUN /
  conditional** — no public withheld/terminated ACK reply boundary.
- Public ACK read `PENDING` branch: **NOT RUN** — no genuine production pending original is
  available without a deterministic settlement/ACK checkpoint; the confirmed segments do not
  simulate pending or unknown.
- Post-ACK admission/activation failure saved completion: **NOT RUN / source-reviewed only** —
  no genuine public fault hook forces history/Room admission or activation failure.
- Concurrent resume while `INSTALLING` returning `Busy`: **NOT RUN / source-reviewed only** —
  no deterministic overlap forces the branch; exact contention ordering remains `NA-U4`.
- Concurrent resume/read and decline/unknown ordering: **NOT RUN / unbound (`NA-U4`)**.
- Existing-only reopen after unconfirmed close (`NAC-007-B`) and pure close (`NAC-007-C`):
  **NOT RUN** — owned by the separate lifecycle qualification; no unconfirmed close is
  manufactured here.
- Setup-result delivery ACK/go-live separation (`NAC-009`): **NOT RUN** — separate E3-005
  surface; this harness never executes a setup-result ACK or go-live.
- Exact product statuses, attempt records, cleanup/owner signal, closed/reopen mapping,
  history mechanism and admission gate: **UNBOUND** (`NA-U1`–`NA-U10`; `AOSF-UB1..5`).
- Native device execution, emulator qualification, owner acceptance and whole-card closure:
  **NOT RUN**.
