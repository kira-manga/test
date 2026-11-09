# JVM local master host — production-source candidate

LOCAL ONLY. Requirements: [original handoff](../../../../docs/pos/original-handoff/).
Central/backend work remains held. This module supplies host/ledger/child code,
not qualified OS custody, a working key backend or restore acceptance. See the
[current status](../../../../docs/pos/CURRENT-STATUS.md) for actual verification.

## Public composition

Package: `com.ukkera.pos.master.host`, pure Kotlin/JVM (`src/main/kotlin`).

- `JvmLocalMasterHostConfiguration(installationId, localDeviceId, databasePath,
  stateDirectory, authority, backups)` binds a protected installation/device,
  exact normalized paths, branch and the explicit current-v6 backup profile.
  The local device identity is separate from authority copied from a database.
- `JvmMasterHost.enroll(configuration, enrollmentAuthority, supervision)` is an
  explicit authorized creation of the external run ledger. It does **not**
  provision a database/account/key, grant writer authority or migrate live data.
- `JvmMasterHost.open(configuration, compositions, supervision, authorization,
  mode)` acquires ledger/OS custody before any Room builder. `START_SERVICE`
  requires a clean stopped record; `REMAIN_STOPPED` also permits a cleanly held
  restore record, without opening Room.
- `host.status: StateFlow<LocalMasterHostStatus>` publishes a
  `LocalMasterHostActive` only while active. Its real `LocalMasterEndpoint` and
  new `serviceIncarnationId` belong to that one lifetime. `captureActive()` and
  the captured handle's `isCurrent()` prevent treating a stale snapshot as a
  current client connection. An old endpoint is never reset.
- `host.maintenance: MasterMaintenancePort` is the concrete
  `JvmStoppedMaintenance`. `host.quarantineCustody: QuarantineProcessCustody`
  owns helper loans through the same supervisor.
- `host.resumeUnchanged()` is an **explicit** clean-source restart, producing a
  new service/security incarnation. Maintenance never resumes automatically.
  `host.close()` waits for actual normal closure or retains custody on uncertainty.

The root composes the existing `JvmLocalBackupStore` using the exact
`configuration.backups`, a real `ProtectedBackupKeyProvider`, `host.maintenance`,
and `JvmProcessStagedSecurityQuarantine(fixedProcessConfiguration,
host.quarantineCustody)`. No raw Room/security store, principal factory or
mutable transaction escapes this host.

`LocalMasterServiceCompositionSource.prepare(launch)` supplies real handlers,
read handlers, security, clock, dispatcher and `MasterRuntimeVerifier` for each
new launch. The security environment must report the new supplied incarnation.
The host directly calls the existing `openLocalMasterService`; it cannot accept
an arbitrary substitute service. The runtime verifier is passed unchanged:
neither file custody nor an epoch comparison certifies writer/full-history rights.

App wiring must bind the actual authenticated peer, scope labels, installed
handler registry and client credentials to this exact active lifetime. Remove
the binding on stop/retirement/incarnation change. The host does not authenticate
transport or manufacture client device membership. All production startup must
use this host: legacy `openLocalMasterService` and `openLocalMasterStore` remain
public low-level APIs and were **not** changed or globally fenced by this cut.
Host-origin retirement stops endpoint admission but cannot invoke the core's
internal retirement hook. Transports must check the active handle's `isCurrent()`
before accepting late responses; that predicate performs no ledger/disk access.

## Durable stop and cancellation ownership

`DurableMasterRunState` holds an actual `owner.lock` and writes bounded canonical
`run-state.json` via exclusive private temporary file, file force, atomic rename
and directory force. The private state directory is disjoint from the backup
directory and never lies under the replaceable database path. It stores exact
installation/device/scope/authority/path identity, service incarnation,
maintenance authorization coordinates, potentially-starting helper ownership,
and complete `RestoreInstallIntent` including the full previous backup.

The lower recovery module's opaque `JvmPrivateFileLock.acquire(path)` is shared
by host `owner.lock` and backup `store.lock`. One JVM path/native-file-identity
registry refuses competing descriptor opens before acquisition, including hard
links. Unknown acquisition/close retains descriptors and refuses future locks;
`isLocallyHeld` is only local bookkeeping, not OS exclusivity or process-reap proof.

Enrollment is explicit; missing state during normal open is not a fresh-install
signal. Dirty `CUSTODY_ACQUIRING`, `STARTING`, `ACTIVE`, `STOPPING`, maintenance
or helper records refuse startup. There is no automatic dirty-state repair,
orphan sweeper, lock-file deletion or adoption of a partial enrollment. This cut
does not provide the separately supervised resolution tool for those records.
Unknown/partial ledger artifacts are preserved.

Before Room, the host also invokes the narrow recovery-store export
`JvmLocalBackupRestartFence.requireExactRoomIdentity(databasePath, profile)`.
It includes `requireNoPendingRestore(databasePath)` and a real read-only exact
user-version/Room-identity preflight. A missing database, sidecars needing recovery,
different version/identity or existing pending marker refuses startup. This host
requires a separately authorized, already provisioned current database; it never
silently creates one or runs a v5 migration. The preflight is not whole-DDL/row
integrity or native qualification. Marker absence cannot override the supervisor
ledger or grant authority. Its pathname formula is shared with the restore writer.

Custody operations use explicitly owned fresh coroutine contexts: caller Room
loan elements are not inherited. Once accepted, critical ownership work finishes
or is externally retired despite recipient cancellation. Undelivered host/lease
results retain exact ownership and attempt only safe closure. Strong retained
references prevent GC from silently releasing uncertain locks/loans. These are
not hard native deadlines: mandatory OS supervision must cover blocked providers,
process creation, fsync, native close and a stopped JVM.

Maintenance publishes unavailability, stops the real endpoint, durably records
the loan, requires the supervisor to stop all additional storage/transport/outbox
owners, and waits for actual `quiesceAndClose() == CLOSED`. A retirement result,
failed close or uncertain write retains custody; a new pool is never substituted.
`ensureStopped()` checks that exact loan, absence of the old service and actual
supervisor custody. `close()` releases only maintenance bookkeeping; the host
stays stopped until a separate allowed restart.

Before replacement, `persistRestoreIntent` matches the authorized request and
authenticated selected archive, latches a non-resumable state in memory and
forces the full intent. Even an uncertain acknowledgement stays stopped. Held
installation and ordinary host shutdown never clear that intent or activate the
branch. A clean held record can reopen stopped for the exact authorized receipt
replay; unknown/interrupted records require separate supervised resolution.

## Actual quarantine child and remaining OS/key seams

Main class: `com.ukkera.pos.master.host.StagedQuarantineMainKt`.
The executable accepts no application arguments. Its fixed trusted classpath
must install exactly one `StagedQuarantineRuntimeProvider` through Java
`META-INF/services`; **none is installed here**. Missing/multiple providers fail.
No request selects a provider class, key alias, config file, command or environment.

The child decodes the existing bounded request, obtains the supervisor's exact
authorized runtime/key loan, calls real `quarantineStagedLocalSecurity`, checks
continued authorization and closes its normal runtime loan. Only then does it
write the bound receipt. It halts after flushing the receipt, or halts nonzero
without a receipt on failure. It never prints provider failures or tries to close
a poisoned Room pool. Parent-observed actual exit/full containment reap remains
mandatory; calling `halt` is not proof of it.

The host binds helper stage bytes to the already authenticated, named-authorized
backup: exact request/source authority, private stage path, size and SHA-256,
and no hard-link alias to the active database. A durable pending helper loan is
written before the supervisor's containment call and before process launch.
The existing parent wrapper registers the actual child and performs its own
wait/drain handling. The host clears its helper loan only after the mandatory
containment close following confirmed reap (or a genuinely unattempted launch).
Exceptional stages are not removed by this module.

Still-required independent implementations:

1. **`LocalMasterHostSupervision` / lease:** actual protected service identity,
   ancestor-path and process custody, pre-start containment/deadline, additional
   owner closure, descendant/descriptor-safe reap and OS retirement. A PID list,
   boolean or late `childStarted` callback cannot establish pre-start protection.
2. **`LocalMasterMaintenanceAuthorization` / permit:** prepared named local
   recovery authority for the exact incident/target/authenticated archive, with
   current checks that do not reopen the stopped database. Ordinary interactive
   access or a copied v6 financial action-approval row is not this authority.
3. **Actual key backend and child runtime provider:** separate protected
   archive-wrapping and source-record keys, permitted selectors and fresh service/
   boot-time context. No adjacent plaintext key, generated replacement, copied
   device-private identity, synthetic status flag or exported-key wire fallback
   is supplied. The existing backup key API requires a compatible JCA AES handle;
   an operation-only native broker needs a separately approved genuine bridge.

The current host/quarantine profile is exact v6, generated identity
`c35cf1dddfea41417d2bb3d4f95ce791`. V5 archive verification remains in the recovery
adapter's unchanged factory, but this host does not authorize v5 restore/migration.
Trusted canonical DDL pins remain an explicit nonempty configuration input.
The host neither computes nor auto-promotes candidates into accepted pins.
A test composition may supply a clearly labeled candidate; that does not qualify production pins, keys, custody or
restore. Generated schema-JSON file SHA is never the canonical SQLite DDL pin.

Filesystem support is currently POSIX private modes, no symlink components,
atomic rename and directory fsync. Same-identity/privileged hostile processes and
cross-device writer exclusion are not solved by these checks. Windows/mobile
filesystem adapters, native key/time/containment qualification, crash/power-loss
evidence, recovery reconciliation/readmission UI and independent acceptance
remain outstanding. No local-source or synthetic execution result closes those
platform/physical-custody gates.
