# AOSF v2 Android original-setup fixture

Status: **SOURCE/COMPILATION ONLY — NATIVE RUN NOT RUN**. The actual
`AndroidLocalBranchService`/`LocalSetupCoordinator` installation path remains the
baseline. Each case then exercises the actual current-action-granted protected
saved-setup read, delivery ACK and responsible business go-live where the frozen
fixture permits it. AOSF-C/AOSF-D are separate fresh installations selected
explicitly; remaining envelope/key-version observations stay explicit unbound
obligations. No case is reported PASS.

Android runtime-test compilation of this binding passed with JDK 21. That is a
source-integration result only; no selected case was executed.

## Build

From `apps/pos` with JDK 21:

```sh
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :apps:android-runtime-tests:compileLocalTestingKotlin
```

## Run one selected case

Use a fresh isolated, nondebuggable `com.ukkera.pos.local.testing` installation
(matching application/test APK pair). Do not reuse or reset an earlier fixture
installation. One case per fresh install:

```sh
adb shell am instrument -w \
  -e aosfCase AOSF-001 -e aosfFixture AOSF-A -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner
```

Valid selections: `AOSF-001`/`AOSF-002`/`AOSF-005` with `aosfFixture=AOSF-A`;
`AOSF-003` with `aosfFixture=AOSF-A`, `AOSF-C` or `AOSF-D`; `AOSF-004` with
`aosfFixture=AOSF-A` (the AOSF-B result) or `AOSF-C`; `AOSF-006` with
`aosfFixture=AOSF-U`. Each selection needs its own fresh isolated installation.
Evidence is app-private
at `noBackupFilesDir/aosf-v2-selected-installation.json`; it contains no credentials,
tokens or passwords.

## Wired setup-result bindings

- `AOSF-001`: real setup-only settlement, exact anchor, explicit signed-head policy
  activation of only `SETUP_RESULT_READ`, actual employee login, two protected reads
  bound to the original result/actor, ACK absent and go-live absent.
- `AOSF-002`: protected read twice before any ACK with a lost-reply second read; the
  pre-read cut starts after the explicit action-grant/session setup and the post-read
  cut excludes that explicit setup. A post-reopen session rejection is recorded as a
  specific read-cut limitation instead of being called a read delta.
- `AOSF-003` (`aosfFixture=AOSF-A`): settlement is read before ACK; one real delivery
  ACK is committed once; AOSF-B is read twice and re-read after confirmed close/reopen
  with the same original result, ACK actor/operation/audit identity and no go-live
  fact. The ACK cut allows only setup-result bookkeeping; stock/claims/security/authority
  are unchanged.
- `AOSF-003` (`aosfFixture=AOSF-C`): the production checkpoint commands settle the
  original-bound configuration, explicit no-import catalog and explicit-zero opening
  prerequisites; one real ACK and one real responsible-actor go-live are committed.
  Both facts, their exact operation/actor/audit identity, the zero business ledger and
  the after-close persisted cut are bound. `SETUP_CHECKPOINTS_READ` is used for the
  checkpoint read and `SETUP_RESULT_READ` for the saved-result read; the signed policy
  grants both explicitly.
- `AOSF-003` (`aosfFixture=AOSF-D`): the same original-bound checkpoints plus one real
  responsible-actor go-live are established without ACK; the live-without-ACK baseline
  is read and cut independently. One real ACK is then committed; the go-live operation,
  responsible actor, audit time and prerequisites remain exactly unchanged and the
  ACK fact is added without stock/claim movement.
- `AOSF-004` (`aosfFixture=AOSF-A`): this fresh installation is advanced to frozen
  AOSF-B by one real ACK, then confirmed-close/existing-only reopen/login/protected
  read/readback are bound.
- `AOSF-004` (`aosfFixture=AOSF-C`): the acknowledged-live AOSF-C baseline is
  established by the same production checkpoint/ACK/go-live path; confirmed native-owner
  close, existing-only reopen, explicit original-current-employee login and the
  protected current-authority read preserve the exact ACK/go-live actor/audit identity,
  original readback and zero business ledger.
- `AOSF-005`: signed read grant, pre-close protected read, confirmed close, reopened
  pre-close session and protected-read/refresh probes, explicit retirement/fresh S3
  login, fresh protected read and rejected stale/foreign/tampered reads.
- `AOSF-006`: distinct AOSF-U actor receives its own explicit read grant; protected
  ACK-state reads disclose no committed delivery ACK while the separate uncertain
  logical operation remains unknown/unresolved, with no retry, re-anchor, new identity
  or operation row.

## Remaining blockers

- AOSF-C and AOSF-D are separate installations. Selecting one cannot exercise the other;
  the other remains an explicit unbound obligation for that fresh installation.
- Exact delivery-ACK/envelope representation and native envelope retention/consumption
  remain unbound (AOSF-UB5 / X-1; OSR-U1).
- Setup-definition key/version is not exposed by the selected production setup definition
  path. The C/D configuration checkpoint carries the requested EGP/Africa-Cairo fixed
  inputs as real original-bound production data; no separate setup-definition key/version
  observation is claimed.
- Exact closed native readback status and native-close-to-intentional-stop mapping remain
  undecided (OSR-U4). The AOSF-004-C cut compares setup/business/authority/ACK facts and
  excludes only the frozen allowed post-reopen session delta.
- Native execution requires an installed target and is NOT RUN in this workspace.
  Other-platform/P1 segments remain unbound.

## Confirmed native ACK repeat segment

After installing the matching isolated application/test APK pair on a fresh target:

```sh
adb shell am instrument -w \
  -e class com.ukkera.pos.android.independent.aosfsetupv2.NativeAcknowledgementRepeatTest \
  -e nativeAckCase REPEAT_CONFIRMED -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner
```

This segment checks two repeated native completions against the saved original, with
unchanged receipt bytes, owner/incarnation, sign-in count and closed native cut.
It does not qualify a whole NAC case or the pending/uncertain close branches.
Native execution remains NOT RUN until this command actually executes successfully.
