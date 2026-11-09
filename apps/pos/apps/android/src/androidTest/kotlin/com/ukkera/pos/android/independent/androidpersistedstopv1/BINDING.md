# APSL v2 Android persisted STOP/lifecycle binding

Status: **SOURCE/COMPILATION ONLY — NATIVE RUN NOT RUN**. The external design input is
`qa/pos/designs/foundation-android-persisted-stop-lifecycle-v2/{cases.md,fixtures.json,coverage.md}`
at commit `5d8d96c4ee066ad9838d72b2af0cc9eb493193a8`. That packet identifies itself as
**CONDITIONAL DESIGN v2 — NOT FROZEN** and is not an ancestor of this integration. It
is not a frozen or accepted whole-case oracle. The separate bounded source/JVM
expectations are frozen in
`qa/pos/traceability/android-lifecycle-resume-20261109/EXPECTATIONS.md`. No APSL case
is reported PASS; the packet's independent whole-design review remains required.

## Build and selection

From `apps/pos` with JDK 21:

```sh
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :adapters:app-gateway:compileKotlinJvm \
  :apps:android-runtime-tests:compileLocalTestingKotlin
```

One selected case per fresh isolated nondebuggable `com.ukkera.pos.local.testing` installation:

```sh
adb shell am instrument -w \
  -e class com.ukkera.pos.android.independent.androidpersistedstopv1.NativePersistedStopServiceTest \
  -e apslCase STOP_RESUME -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner

adb shell am instrument -w \
  -e class com.ukkera.pos.android.independent.androidpersistedstopv1.NativePersistedStopCustodyTest \
  -e apslCase A1_LOGOUT_PENDING -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner
```

Valid service selections: `STOP_RESUME`, `LOCK_BACKGROUND_REOPEN`, `STOP_CLOSE_ACCOUNTING`.
Valid custody selections: `A1_LOGOUT_PENDING`, `OWNER_CLOSE_ACCOUNTING`. Evidence is app-private
at noBackupFilesDir/apsl-v2-selected-case.json and contains no credentials or protected rows.

## Bounded source corrections

- Explicit setup open and explicit local-client attachment no longer clear a retained STOP or
  bypass an UNRESOLVED/read-error launch record. Automatic reopen already refused those records;
  the same launch gate now protects explicit open and refresh. A fresh ABSENT record is unchanged,
  and an explicit RUN record is accepted only when all twelve retained coordinates match.
- Authenticated STOP/RESUME now uses `LocalSetupCoordinator.authenticateAndStopLocalMaster()` /
  `authenticateAndResumeLocalMaster()`. The synthetic test policy grants the explicit
  `MASTER_LIFECYCLE`/`ADMINISTER` permission that the real ingress evaluates inside the protected
  Room writer. The old direct `requestRetirement(intentional = true)` path is not used by the
  tests to create STOP. A wrong password is expected to leave RUN/STOP and custody unchanged.
- The coordinator first retains a canonical intent containing the request ID, action and all
  twelve original coordinates, forces/read-backs it, and only then dispatches authentication or
  the pre-start installation owner. A retained intent is reused exactly after process loss; a
  malformed/foreign/partial/unresolved record or a different action fails closed before dispatch.
- RESUME opens the real installation-only native+Room owner, authenticates through the same
  protected security, commits the lifecycle audit, requires the launch record to remain STOP,
  closes that held owner successfully, forces/read-backs RUN, clears the intent only after the
  readback, and only then reopens the operational endpoint and publishes the original client.
- `AndroidSelfOwnedMasterService.quiesceAndClose()` retains its single cached completion. A second
  API observer joins that same completion instead of issuing or retrying a physical native close;
  `CLOSED` remains observable and the native close is issued once.
- `NativeLocalFixtureOwner` retained one additive observation helper,
  `detachMasterForExplicitClose()`, so the direct owner case can consume the exact Master close
  itself without a wrapper retry.

## Bound segments

- `STOP_RESUME`: actual `AndroidLocalBranchService`/`LocalSetupCoordinator` setup; wrong-password
  STOP denial with RUN/custody unchanged; authenticated `MASTER_LIFECYCLE` STOP; durable STOP
  refusal of automatic recreation; repeat stop refused on the closed controller with the same
  cached retirement; explicit setup-open refusal; wrong-password RESUME denial while STOP remains;
  STOP preserved across another service restart; authenticated same-original RESUME through the
  real pre-start installation owner; same settlement receipt and connected original after resume;
  repeat RESUME refused while running. The native close-before-RUN ordering is source-reviewed,
  not instrumented here.
- `LOCK_BACKGROUND_REOPEN`: actual service `onTaskRemoved`/`onTrimMemory(UI_HIDDEN)`/`onUnbind`
  and production binder lock do not retire or replace the active Master; current refresh succeeds;
  non-intentional service-owner close (no STOP) recovers the same original on existing-only RUN
  reopen; UI close with an acknowledged STOP does not clear STOP or auto-start.
- `STOP_CLOSE_ACCOUNTING`: authenticated lifecycle STOP, acknowledged durable STOP, one confirmed
  service-owner retirement, a second observer joining the same consumed coordinator retirement,
  no replacement writer. Physical native close attempt/confirmation counts are unbound because no
  instrumentation measures them.
- `A1_LOGOUT_PENDING`: real A1 native login/logout, real CASH drawer/session open, real pending
  CASH closing original retaining actor/device/drawer across logout and confirmed close/reopen;
  protected cash read after logout is denied.
- `OWNER_CLOSE_ACCOUNTING`: actual `AndroidSelfOwnedMasterService.quiesceAndClose()` observed from
  `CLOSING` to confirmed `CLOSED`; a second API observer joins the cached completion with no new
  native close. Physical attempt/confirmation counts are recorded as unbound, not fabricated.

## Remaining unbound hooks

Each selected case records its own precise gaps in evidence. In particular: actual OS
force-stop/process-death/device-shutdown/swipe-away, OEM/battery/notification/permission/P1 facts,
pre-existing admitted C11 and ordinary-client surface, ALCR-MCART/UNKNOWN arithmetic and pending
amount, launch-record fault cuts, direct duplicate process/second-writer launch, exact native
close attempt/confirmation counts and the exact public native close taxonomy remain unbound. The
new authenticated STOP/RESUME surface is wired and compiled, but APSL-001.V01/V02/V03 and
APSL-003.V01/V02/V03/V04/V05 remain unexecuted as whole cases. The direct
`OWNER_CLOSE_ACCOUNTING` segment does not create the APS-STOP-PRECLOSE launch record; STOP
durability is bound by the service selections. A real A1 pending CASH closing custody is not the
ALCR-MCART 342.00/342-gross fixture and does not supply that arithmetic.
