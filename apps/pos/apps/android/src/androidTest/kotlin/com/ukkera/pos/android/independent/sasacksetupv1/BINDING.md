# SAS v1 Android selected-case harness

Status: **SOURCE/COMPILATION ONLY — NATIVE EXECUTION NOT RUN**. The harness is wired to the
actual native original setup, signed head policy ceremonies, current master, durable Room and
ordinary production commands/reads. It now settles the frozen BASE-CUT through real signed
branch-activity/SKU publications, `stock.opening.post`, cash opening and the original-bound
setup checkpoint commands (sentinel `SETUP-SYNTH-001`, 3 units, 7500 minor stock value,
50000 minor float, no sales/supplier obligation). Every selected case ends with its explicit
unbound remainder; no case is reported PASS.

## Build

From `apps/pos` with JDK 21:

```sh
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :apps:android-runtime-tests:compileLocalTestingKotlin
```

## Run one selected case

Use a fresh isolated, nondebuggable `com.ukkera.pos.local.testing` installation (matching
application/test APK pair). One case per fresh install; the retained evidence file refuses
reuse:

```sh
adb shell am instrument -w \
  -e sasCase SAS-001 -e keyProtection OS_ISOLATED \
  com.ukkera.pos.local.testing.test/androidx.test.runner.AndroidJUnitRunner
```

Valid selections: `SAS-001`..`SAS-022`. Evidence is app-private at
`noBackupFilesDir/sas-v1-selected-case.json`; it contains no credentials or tokens.

## Case disposition

| Case | Wired Android segment | Explicit unbound remainder |
| --- | --- | --- |
| SAS-001 | protected read twice, restart, no ACK/go-live | U1 projection, U3 envelope, other master platforms |
| SAS-002 | one durable ACK, same-ID retry before/after restart, no go-live | U1 status, U3 envelope, other platforms |
| SAS-003 | responsible go-live without ACK; no implicit ACK | U1 status, U9, other platforms |
| SAS-004 | go-live then ACK on already-live; actor/audit retained | U1 status, U3 envelope, other platforms |
| SAS-005 | US/UX/approval ACK/go-live denials; UR read-only | UC central session, UI flag, U1/U4, other platforms |
| SAS-006 | P0 denials, real signed activation, exact post-grant actions and go-live | U1/U4, other platforms |
| SAS-007 | local revocation before action; cached session denied | deterministic race barrier, central intent, U1/U4 |
| SAS-008 | disabled account and revoked session denied | D3/D4 device variants, S2/T1 cross-scope, U1 |
| SAS-009 | conflicting installation name held; exact original ACK | S1C/S2/T1, M2/M1-old, U5, other platforms |
| SAS-010 | ACK and go-live same-ID concurrent/restart replay | U1/U7 exact retry status, other platforms |
| SAS-011 | changed ACK and go-live content/actor conflict; original actors retained | U1/U5 |
| SAS-012 | new ACK/go-live IDs cannot duplicate; actor/audit retained | U7/U8 exact repeated-ID result |
| SAS-013 | lost ACK/go-live replies, restart, retained results/projection | U1/U3 recovery surface/envelope |
| SAS-014 | no effect baseline only | no production fault-injection boundary; U1/U6 |
| SAS-015 | no effect baseline only | no commit/flush/fsync/power fault; U6 |
| SAS-016 | UR read, UA/UV denial, audited supervisor recovery | U1/U10/U11, other platforms |
| SAS-017 | BASE-CUT exposes four settled production prerequisites; no go-live attempted | missing S1P variants and optional standby remain blocked, U2 |
| SAS-018 | forged readiness/actor/permission/time fields refused; signed settled path commits as UG | mock readiness, exact U1/U2 |
| SAS-019 | live ACK + responsible go-live; no stock/financial aggregate movement | T1 training store, U9 central/fiscal |
| SAS-020 | installed S1 baseline only | D2 client, S2/B2/D5, T1, cached cross-branch projection |
| SAS-021 | US/unrelated-approval denials | successful UG go-live actor/substitution, UC, U1/U4 |
| SAS-022 | same-build compatible reopen and unknown field refusal | pre-increment S1/S1A/S1L data, stored unknown profile, U12 |

## Current exact blockers

- The frozen S1P missing/in-progress prerequisite variants still cannot be produced from the
  settled BASE-CUT without an unapproved injection or substitute readiness; SAS-017 remains
  BLOCKED. `SETUP_CHECKPOINTS_READ` is the new explicit checkpoint-detail action and is not
  replaced by `SETUP_RESULT_READ`.
- Native envelope lifetime/consumption remains U3 (contract X-1) and is never asserted.
- Full native execution requires an installed target and is NOT_RUN in this workspace.
  Windows/macOS/physical iPhone/iPad real-provider segments are unbound.
- Training/live, second-branch, second-client, retired-device and pre-increment-upgrade
  fixtures are not present; those case portions remain BLOCKED, not PASS.

## Linux/JVM supplemental scope

`LocalSetupResultGrammarSupportingTest` (core:database `jvmTest`) exercises the production
additive codecs/fail-closed decoding and frozen prerequisites only.
`SetupAcknowledgementsRegistrySupportingTest` (catalog-store `jvmTest`) exercises actual
registry registration/normalization and the restart-producer registration only.
`LocalSetupCheckpointKernelSupportingTest` (core:database `jvmTest`) drives the real checkpoint
handlers through a durable non-native SQLite kernel and proves fail-closed no-effect writes;
`LocalSetupCheckpointHandlerSupportingTest` (catalog-store `jvmTest`) covers exact
original/effect binding, duplicate catalog-operation source rejection, recovery barrier
declarations, `SETUP_CHECKPOINTS_READ`, immutable receipt/attempt binding and historical
restart-producer ownership. None is a native original, protected SAS read, ACK/go-live commit
or SAS case PASS.
