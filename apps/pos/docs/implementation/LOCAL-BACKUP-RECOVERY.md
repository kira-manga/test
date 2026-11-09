# Local backup and held restore — JVM adapter

Authored 2026-10-24 by the recovery implementer. **LOCAL ONLY.** Authority remains
the original `../ukkera-pos-agent-handoff-2026-10-21` and agreed recovery design;
this document does not replace them or authorize the held central/backend phase.

This is a production-source candidate, not an accepted recovery installation.
The implementer has not run builds/tests, authored acceptance cases, or accessed
independent QA drafts. The lead records combined compilation, independent review
and execution evidence separately. Native custody, real process supervision,
protected keys and complete application recovery remain integration gates.

## Implemented boundary

Module `:adapters:recovery-store` is JVM-only, targeting JVM 17 with toolchain 21.
It uses real files, bundled SQLite and JCA authenticated encryption. Package:
`com.ukkera.pos.recovery.store`.

`JvmLocalBackupStore` provides:

- `createBackup()` — capture, encrypt, authenticate and publish a local snapshot;
  report retention separately through `CreatedLocalBackup.retentionCompleted`.
- `verifyBackup(backupId)` — authenticate the complete archive, decrypt into a
  private stage and validate the actual SQLite database against trusted pins.
- `applyRetention()` — remove only excess, fully verified, unpinned archives.
- `restoreBackup(ControlledLocalRestore)` — install a quarantined, whole-branch-
  held database under mandatory stopped maintenance; **never activate it**.

There is no public network route, UI, backup scheduler, OS key provisioner,
maintenance implementation, production child entrypoint or master activator here.
The real parent-side helper launcher below is authored, but requires actual
supervisor/custody and child-runtime integration.
There are no accepting/no-op defaults for the three required ports below.

## Mandatory runtime and key integration

1. **`ProtectedBackupKeyProvider`:** supply the authorized AES-256 wrapping-key
   handle in a genuinely qualified supervised service context. The status enum
   is checked but is not qualification evidence. Only explicitly configured key
   IDs can be selected; missing/locked keys fail, never generate replacements.
   No adjacent plaintext key, password-derived fallback or copied device-private
   identity is supplied. Key lifetime, rotation and recoverable custody remain
   the provider's responsibility.
2. **`MasterMaintenancePort`:** authorize the exact scoped operation, stop ingress
   and dispatch, drain requests, actually close **all** Room/auth/outbox handles,
   and retain exclusive local process/filesystem custody. A requested close,
   cancelled job, expired timeout or stopped marker is not proof of quiescence.
   Cancellation during lease handoff must not lose ownership of that custody.
   `approvalId` on restore must resolve to protected, named-user local recovery
   authorization; possession of the archive/request IDs is not authorization.
3. **`StagedSecurityQuarantine`:** this is the **parent-side supervised-process
   boundary**, not a direct in-process call to the core Room helper. Supply the
   original protected-security recovery keys, authenticate every protected table,
   quarantine the exact stage, and obtain a bound receipt only after actual
   helper termination/reaping and closure of all handles. A child retirement
   callback only requests termination. On uncertain helper death, the shared
   supervisor must retain the maintenance stop/custody and stage; never resume
   the writer or install/delete files based on an expired join.

The core-owned child operation is `quarantineStagedLocalSecurity` in
`core/database/.../StagedLocalSecurityQuarantine.kt`. It alone cannot prove its
own process has been reaped. `JvmProcessStagedSecurityQuarantine` supplies the
actual parent-side process wrapper; its required OS-backed custody implementation
and the child entrypoint remain lead-owned integration obligations.

The maintenance lease persists a deny-on-restart `RestoreInstallIntent` before
replacement. It binds the complete request, authenticated selected backup,
installed database hash and optional **full authenticated previous backup**.
After even uncertain acknowledgement of that intent, `close()` must keep the
runtime stopped. Without an intent, only an unchanged source whose entire
process/helper custody is confirmed may resume. No local lock or epoch excludes
another isolated physical writer; that fencing decision remains external.

## Trusted schema profiles

`LocalMasterBackupProfiles.localAuthV5(permittedSchemaSha256)` fixes version **5**
and Room identity **`917ea675dbaed518193bc43cfcd19ffc`**. The required application
tables are exactly:

| Financial/base | Protected local security |
| --- | --- |
| `branch_authority` | `protected_local_account` |
| `operation_result` | `protected_effective_policy` |
| `aggregate_state` | `protected_device_admission` |
| `unique_claim` | `protected_login_throttle` |
| `journal_envelope` | `protected_local_access_issuance` |
| `audit_event` | `protected_local_auth_configuration` |
| `outbox_intent` | `protected_local_refresh_issuance` |
| | `protected_local_auth_event` |
| | `protected_local_session_archive` |

`LocalMasterBackupProfiles.localAuthV6(permittedSchemaSha256)` fixes version **6**
and the genuinely generated Room identity **`c35cf1dddfea41417d2bb3d4f95ce791`**.
It includes all tables above plus `protected_local_action_approval`: exactly
**17 application tables**, including **10 protected security tables**. The lead's
archived KSP6 `6.json` has file SHA-256
`c975b390da9b995a7d0a60e2d5a8bf52fc91b1e65ac66867859b33bdae238d1e`.
This records generated-metadata provenance only, not an executed canonical DDL
fingerprint, native qualification or a successful backup/restore.

Room identity does **not** cover trigger bodies. The caller must additionally
provide a nonempty explicit set of trusted whole-schema SHA-256 values. There
is deliberately no permissive default or learn-from-archive path. Qualify each
genuine fresh/direct-migration profile against the core's exact table, index
and guard definitions before selecting its hash. Neither generated Room JSON's
file hash nor an arbitrary database's observed hash is that qualification.

`JvmBackupSchemaFingerprint.observeStopped(lease)` exposes the same bounded,
read-only fingerprint calculation for a stopped database. Its result is an
**observation only**, not validation or permission to add a pin. Canonical bytes
for `ALGORITHM_ID = ukkera.sqlite-main-schema/v1` are:

1. Begin SHA-256 with ASCII `ukkera.sqlite-main-schema/v1` followed by byte zero.
2. Read `type,name,tbl_name,sql` from `main.sqlite_master`, ordered by
   `type COLLATE BINARY,name COLLATE BINARY`. Include all rows, including internal
   Room/SQLite tables, indexes and triggers; do not include `rootpage`.
3. For every row and its four columns in that order, append the one-byte SQLite
   storage type, a four-byte big-endian byte length, and the value bytes. TEXT
   and BLOB use SQLite's byte projection without normalization; NULL is empty;
   INTEGER is signed eight-byte big-endian; FLOAT is its raw IEEE-754 bits as
   eight-byte big-endian. Thus NULL differs from empty text and exact DDL
   spelling matters. There is no additional row delimiter.
4. Encode the digest as 64 lowercase hexadecimal characters.

Actual schema and authenticated manifest hashes must both be in the configured
set. Unsupported versions, DDL drift or missing/additional application tables
are refused; the adapter does not migrate, restamp or repair them.

Both factories still require nonempty, independently qualified canonical DDL
pins; no such pin is supplied or auto-learned here. Existing v5 backup verification
and originals remain supported by the unchanged explicit v5 profile. The current
core helper accepts exact v6 only, checking version and identity before opening
Room. **V5 staged quarantine/restore is blocked**: preserve the original and failed
stage until an explicitly qualified staged compatibility/migration path exists.
Never silently run a v6 migration because a v5 archive was selected. The v6
approval rows must authenticate and remain unchanged across quarantine; copied
approval issuance cannot acquire a new service/session's authority merely by
surviving in the snapshot. Adding the factory is not restore qualification.

## Actual parent-side helper supervision

`JvmProcessStagedSecurityQuarantine(configuration, custody)` implements the
quarantine port using `ProcessBuilder`, not an in-memory stand-in:

- The trusted configuration supplies fixed argv, an absolute executable, private
  working directory and explicit environment. No request selects a command,
  shell interpolation or inherited ambient environment. The composition must
  qualify the executable, classpath, environment and ancestor-path custody.
- A bounded private `.quarantine-request-*` file in the invocation stage is
  redirected as stdin. There is no potentially blocked parent pipe write. The
  request contains only the exact stage path and existing scoped request IDs,
  never exported keys, passwords or tokens.
- Two dedicated daemon readers drain stdout and stderr independently. Stdout is
  capped at a 64-KiB framed receipt plus its header; stderr is discarded with a
  64-KiB cap, never logged, forwarded or persisted. Excess/invalid output and
  nonzero exit are failures, not accepting fallback receipts.
- The parent observes a monotonic run deadline (default 120 seconds), polls real
  process exit, and on cancellation/failure attempts graceful termination then
  forcible termination, with actual timed `waitFor` observations. Default stop
  waits are 2 and 10 seconds; drain/join bound is 3 seconds. These are Java-level
  supervision bounds, not proof that native process creation, OS calls, a JVM
  pause or a mandatory provider callback can never block. The external OS
  supervisor must independently cover those failure paths.
- Success is withheld until direct-child exit, valid bound receipt, full
  containment/reap confirmation from the required custody lease, and completion
  of pipe readers. Java `waitFor` **alone** does not prove there were no escaped
  descendants or inherited staged-file descriptors.
- Unknown startup, unconfirmed death/containment or unfinished pipe cleanup
  permanently retires the wrapper, requests OS retirement and **does not call
  the helper lease's close/release method**. An exception in the retirement
  callback cannot release that outstanding durable loan. The enclosing restore
  keeps the stage on any exception/cancellation; no files are removed by this
  wrapper. Successful enclosing restore cleanup also removes the request file.

`QuarantineProcessCustody` is mandatory, without a default. It is a bridge to the
**same** actual stopped-maintenance supervisor, not a callback pretending to
establish custody. Its lease registers the real `ProcessHandle`, confirms the
whole containment was reaped, retains a durable stop on uncertainty, and releases
only the helper loan on a confirmed safe path. It never resumes the master.

`StagedQuarantineProcessProtocol.readRequest` and `writeReceipt` are the public
child-side codec entrypoints. Each frame is fixed ASCII request/receipt magic,
one big-endian 32-bit byte length, and bounded canonical JSON containing protocol
version 1. Extra bytes, unknown fields and noncanonical data are refused; receipt
row-count keys use sorted order. The child must run real quarantine, close the
normally completed database, emit one receipt and exit. The parent does not
release that receipt merely because it appeared on stdout.

### Stopped-custody integration proposal (not an implemented maintenance port)

1. A protected supervisor resolves the named recovery approval and owns the
   branch runtime's actual local filesystem/process lock and launch containment.
   Its restart gate must honor durable pending restore and outstanding helper
   loans before any writable/auth/outbox pool opens. A file lock/marker remains
   local custody only, never physical fencing of another device.
2. Stop every ingress, background dispatcher and outbox/storage owner. For the
   grouped JVM service, use its real `quiesceAndClose()` path and require actual
   CLOSED; PROCESS_RETIREMENT_REQUIRED needs OS termination/reaping, not another
   Room close or a replacement pool. Account for every additional handle outside
   that service. Only then supply `MasterMaintenanceLease.databasePath`.
3. Snapshot/restore uses that lease throughout the stages described here. Before
   starting a child, `QuarantineProcessCustody.acquire` records an outstanding
   helper launch against the same incident/stage. External containment/deadline
   coverage must exist **before** `.start()`, including a parent crash before
   `childStarted`. A late PID-registration callback alone is insufficient.
4. The root-owned child entrypoint loads the independently qualified OS security
   key references/environment from trusted runtime configuration, not arbitrary
   archive-selected key material. It maps the bounded request to the exact-
   current core quarantine function and emits the bound receipt only after its
   successful real close. Its failure path terminates under supervision rather
   than invoking Room.close on a poisoned pool. Separate protected archive-
   wrapping keys stay with the parent key provider.
5. The supervisor confirms full helper containment/reap before closing that loan.
   Any uncertain start, callback acknowledgement, native close or process death
   retains the durable stop. Do not discard the stage or start a replacement
   master based on a timer, disconnected caller or signed-looking status flag.
6. Before installation persist the authenticated `RestoreInstallIntent`. After
   it exists, source resumption is forbidden even if the local rename outcome
   is uncertain. Without any intent, resumption of the unchanged source needs
   confirmed total custody and a properly renewed service incarnation; it is
   not permission to revive old tokens or enroll a copied authority.

This proposal does not supply those protected supervisor records/locks, OS
process containment, child entrypoint, key-context qualification, approval
resolver or restart/readmission implementation. No synthetic provider is used
to mark them implemented or verified.

## Consistent capture and archive format

Capture holds stopped maintenance while a bundled SQLite READONLY connection
establishes a real SHARED read transaction, validates the source, and streams
an exact main-file copy. Source file identity/size and absence of sidecars are
checked again before releasing the transaction/lease. This is not `VACUUM` or
logical export: hidden ROWIDs, previous incidents and all table contents are
preserved. Encryption and retention run on the copy after source maintenance
is released, rather than prolonging checkout downtime.

Only rollback-journal mode is supported. WAL/SHM files and nonempty rollback
journals are refused, not omitted. A new read-only connection normally reports
DELETE after a Room TRUNCATE connection has closed: TRUNCATE is per-connection,
and both use the rollback format. DELETE/TRUNCATE/PERSIST are accepted only
under the same stopped, sidecar-free snapshot protocol.

Validation includes schema pins, `integrity_check`, foreign-key checks, the
single expected branch and exact scope of every application-table row,
authority/head/recovery context, result/journal partner
presence and stored payload SHA-256 values for results, journals, aggregates
and outbox bodies. These checks are not business-handler replay or proof that
all external effects/history have been reconciled.

Archive format version 1 uses a fresh random 256-bit data key per backup, wrapped
with AES-GCM under the protected key and a random 96-bit wrapping nonce. The
fixed header binds backup/key IDs. Bounded AES-GCM records bind the header hash,
counter, record type and plaintext length as AAD. Per-data-key nonces are a
four-byte zero prefix followed by an eight-byte big-endian record counter.
The encrypted canonical manifest precedes 256-KiB database records and an
authenticated final record. Length, final-record, EOF and database-hash checks
reject incomplete/reordered/appended content. The archive checksum is computed
from the **same authenticated input stream**, not a subsequently reopened path.

Each record authenticates before its plaintext is written to private staging;
no stage is passed to the security helper until the whole archive validates.
The manifest carries distinct conservative `capturedAt` and later `createdAt`,
branch scope/full authority, checkpoint/head hash, revisions, schema pins and
database size/hash. Its only history-knowledge value is
`UNKNOWN_AFTER_CAPTURED_CHECKPOINT`. A copied maximum sequence or wall clock
does not prove a terminal tail, an absent operation or a master appointment.

Archive verification does not prove availability of the separate keys protecting
the inner security records, native process custody or complete restore readiness.

## Controlled install and preserved evidence

Restore decrypts a verified stage on the target database's filesystem. If a
target already exists, it first captures and verifies an encrypted backup of
that **complete current database**, before replacing anything. A corrupt or
unsupported existing target is not overwritten: it must be preserved and an
operator must select a separately stopped empty target through the trusted
maintenance workflow. There is no force-overwrite switch.

For the current v6 profile, the security helper must authenticate all ten protected
tables, preserve credentials/policies/issuance/approvals/archives/configuration/history,
revoke copied sessions, disable admissions, and append exactly one protected recovery auth
event. The adapter binds its receipt IDs and row counts to the request and
checks table/ROWID fingerprints: only account/admission content may change,
their row counts remain equal, and the old audit prefix must be unchanged with
exactly one appended event. All other application tables remain unchanged at
this stage. Missing source keys cannot trigger automatic rekey or identity copy.

The adapter then creates a new recovery incident with a **WholeBranch hold**,
sets `writable=false`, and preserves the original authority/epoch as inactive
history. Recorded preserved obligations from the selected backup and current
safety copy are unioned; conflicting entries sharing an obligation ID refuse
installation. This is not exhaustive discovery of unknown grants/resources.
Prior incident state and any newer financial tail remain in the full encrypted
archives; no tail is silently merged, replayed or classified as absent.

Both archives are pinned before the runtime intent and durable local pending
marker are written. The staged database is forced, atomically renamed over the
target and its parent directory forced. Rename/directory-force form a
noncancellable window, **not** a guaranteed wall-clock deadline or hardware
power-loss qualification. Success returns `InstalledHeldRestore`, not permission
to open for business. No receipt dispatch, financial effect, token revival,
promotion or activation occurs.

When a pending marker exists, only the exact same authorized request can replay
the held receipt, and only with matching authenticated backup, installed file
hash and held incident. A mismatch refuses further automatic restore. There is
no automatic rollback, source resumption or pending-marker clearing workflow.
The local marker relies on protected filesystem/runtime custody; it is not an
independent physical-exclusion certificate.

## Storage, retention and limitations

- POSIX host only: owner-only directory modes and newly created file modes
  (`0700`/`0600`), no symlink path components, atomic rename and directory fsync
  support are required. Trusted runtime custody must protect ancestor directories
  and paths against replacement; these checks do not defeat a privileged or
  same-identity hostile process. Windows and mobile adapters are not supplied.
- A coroutine mutex and OS `store.lock` serialize archive operations across
  cooperating local instances. Neither grants branch-master authority.
- Default retention is five verified snapshots, configured range 2–256. Missing
  keys, corrupt/unknown entries and unverified archives never count as valid
  deletion candidates. `.pin` evidence is retained indefinitely. There is no
  automatic unpin, journal/result compaction or financial-history deletion API.
- Default database limit is 8 GiB, configurable up to 64 GiB; manifest limit is
  64 KiB, a directory scan is capped at 4,096 entries, and SQLite hydration is
  bounded by 4-MiB cells, 8-MiB rows, 64 columns and 1,024 schema objects. These
  are memory/capacity limits, not hard execution-time guarantees.
- Scratch directories are created exclusively, never adopted from an existing
  random-looking name. Invocation-owned scratch is normally removed. After **any quarantine error
  or cancellation**, the stage is intentionally left untouched because caller
  failure is not proof of helper death. Process crashes can also leave private
  plaintext scratch. No automatic orphan sweeper exists: cleanup requires the
  supervisor to establish no live handles and preserve any incident evidence.
- Public failures expose typed sanitized reasons, not provider exceptions,
  filesystem paths, SQL, credentials or plaintext. Normal byte buffers are
  cleared best-effort; JVM/JCA/native memory erasure is not claimed.

Actual key/service custody, trusted fresh/migration schema pins, helper-process
reaping, crash/power-loss behavior, owner-facing restore/reconciliation workflows,
independent execution, and the other required platforms remain **UNVERIFIED**
until the lead records their real evidence. An authored port or observed hash
does not close any of those gates.
