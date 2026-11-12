# Android original local client

**Original cohost only; not a complete owner-ready POS.** See the current
[implementation and verification dashboard](CURRENT-STATUS.md). Other employee,
device and platform workflows remain required.

## Retained original device admission

The shared setup workflow now has **Review / admit a retained original device**
after the original local client is connected to its operational Master:

1. Read the actual retained native originals and inspect one exact SDK-bound V2
   target, branch/root, native key and complete SDK association.
2. For an eligible target-proved original, use the existing named company-head
   password and explicitly approve that original. Approval does not admit it.
3. Confirm admission separately. The application forces its exact original
   attempt intent before calling the same Master/native admission service.
4. After submission, use the original-only lookup. Lost, denied or uncertain
   replies and reopening cannot submit that admission again. Completion requires
   the actual original native/protected lookup, not a saved terminal label.

Locking hides the review and clears the password form; it does not cancel native
work or discard an unresolved original. Admission does not sign in an employee,
create permissions or establish a remote RPC connection.

Fresh target-role acquisition/proof transfer is not application-integrated yet,
so an empty list cannot be populated by this page. Complete new-device
authentication/history and remote serving remain in [#49](https://github.com/ukkera/kmp-apps/issues/49)
and [#50](https://github.com/ukkera/kmp-apps/issues/50). Source review and controlled
application tests are separate from genuine native/SQLite/RPC integration
([#59](https://github.com/ukkera/kmp-apps/issues/59)), installed tests
([#63](https://github.com/ukkera/kmp-apps/issues/63)) and owner acceptance
([#61](https://github.com/ukkera/kmp-apps/issues/61)); no runtime result is implied.

<details>
<summary>27 October 2026 cohost and journal implementation checkpoint</summary>

The following description and results belong to that earlier source checkpoint.
Later integration and verification status is in the current dashboard above.

## Runtime path

`PosApplication.localClient` is the stable branch gateway. The retained,
nonexported `AndroidLocalBranchService` owns `AndroidLocalSetupOwner`, which opens
`AndroidLocalBranchRuntime.openOriginalMaster` using the actual commerce command/
read registry and `AndroidSelfOwnedMasterService.fromOriginalContext`. Installation
still uses real native custody and protected Room; a receipt is not sign-in.

`AndroidSelfOwnedMasterService.originalLocalClient()` captures the actual admitted
peer/endpoint, complete original installation coordinates/receipt, registered keys
and one service incarnation. Only that service constructs the binding. It checks
the exact original application object and its real lifetime, not caller paths,
scalar IDs, keys or an accepting callback. Acquisition waits for installation's
actual cleanup/slot release, not merely the installation reply.

The first account starts with no selected grants. Its complete definition and
device policy are reviewed before independent head/employee secrets are submitted.
Neither company-head identity nor installation completion becomes an employee
session. Sign-in uses the account/password actually created and the real local
endpoint, protected credential journal and actor-owned intent journal.

## Explicit journal actions

- **Enroll this original client once:** only a fully settled/acknowledged original
  C1, command sequence zero, desired RUN, no pending history/Room loan. Generate
  each nonexportable AndroidKeyStore key before exclusive private file creation.
  Force/read back the encrypted initial manifest and observe its native head.
- **Reopen existing client journals:** validate the same fixed keys, private paths,
  complete encrypted manifest/archive census and native heads. Never creates,
  repairs or replaces a key, directory, lock, manifest or missing pending history.
- **Continue initial client enrollment:** only the completed-first/untouched-second
  boundary. Reopen the actual credential journal, require native head0 and its
  byte-exact initial manifest while original acknowledged C1/RUN is still current.
  Keep that owner private/locked; enroll the second journal only through its strict
  no-prior-key/head/path checks; verify the first again before publication. An
  absent first journal, failed read or a later/current-empty revision cannot qualify.
  If both journals already exist, use Reopen rather than continuation.
- **Recover an interrupted client journal write:** complete only exact saved
  ciphertext already fenced by its existing native successor head. This is a
  separate visible action, not an automatic repair in Reopen or fresh enrollment.
  It never submits a master command, issues a session or recreates a native key.
- **Employee sign-in:** separate, interactive local authentication after both
  journals have opened. No default account, token resurrection or auto-login.

Journal/key IDs are domain-separated derivatives of the already durable original
receipt/coordinates. They are not randomly replaced after missing data. Credential
and first-actor intent journals must both validate before publication. Further
employee/device enrollment requires its own controlled admission and journals;
this source does not claim to implement it.

## Durable native heads and failure boundary

`AndroidClientJournalKeys` shares strict generated/nonexportable AES-GCM metadata
validation. Each head is a separate nonexportable key alias containing its sequence
and the SHA-256 of the complete encrypted manifest/archive set. The alias namespace
refuses foreign key IDs. New writes force/read back a private write-ahead record
before creating their successor head, and create that head **before** publishing
the new file. The successor fences the old cut immediately; it is never permission
to fall back to the older head. Remove an old head only after forcing/reading back
the complete successor file cut. At most two consecutive heads coexist; ordinary
reopening still requires the highest to match every encrypted file.

The new `write-ahead.v1.pending` record stores the exact already-encrypted target,
previous native sequence, complete before/after digests and a closed manifest/
archive selector. It contains no plaintext bearer credentials. Its format alone
is untrusted: recovery requires the actual nonexportable successor key, complete
file census and correct existing data-key/AAD authentication.

- If the complete current cut is the original before-cut, the intended one-file
  change must compute exactly the native successor digest. A partial temporary
  can only be extended from a byte-exact prefix of that saved ciphertext; it is
  never truncated, re-encrypted, replaced or interpreted as another operation.
- If the complete cut already equals the successor, force/read back the actual
  target rather than replaying it. An interrupted archive link may have exactly
  two links only when they are the same proved archive/temporary inode. Remove
  only that duplicate temporary after proving its full target bytes.
- Every unaffected manifest/archive retains its same-owner file marks. All names,
  archive counts, digests, bounds, private modes, descriptors and current native
  head are rechecked. A subset, foreign cursor/path or merely readable file cannot
  establish a complete cut.
- After actual file/directory force, full readback and native agreement, remove
  only the exact duplicate write-ahead record. Both journals still undergo their
  full semantic decode/archive census before gateway publication. A recovered
  credential record remains recovery material, never an automatic sign-in.

**Still held:** incomplete/unreadable write-ahead bytes; no actual successor key;
legacy write/file interruptions without the new record; a cut matching neither
the original nor successor; a missing/corrupt live manifest, directory, lock, key
or archive; initial journal key/file/head interruptions. The earlier enrollment
continuation still covers only completed-unused credentials **before second-key
creation**. This is not general initialization, database, backup or disaster recovery.
No live journal/data key is reset, no ID/revision is replaced and no pending
business evidence is discarded. Native heads supplement, not replace, actual
descriptors/locks, encrypted formats and original service lifetime admission.
This profile does not protect against compromised application UID/OS/keystore and
has not been qualified under physical power loss, reinstall, upgrade or transfer.

No existing manifest/credential/intent serialization identity or Room schema changed.
The new private write-ahead codec is explicitly versioned because it is persisted;
ordinary owner/operation files remain unversioned. Older binaries refuse the new
pending file rather than pretending it is a completed old cut. No downgrade,
device or power-loss compatibility qualification is inferred.
No invented6→7 migration, copied bearer login or arbitrary data-reset fallback.

## Ownership and service lifetime

The explicit visible-user connection publishes a generic localized special-use
foreground notification, not company/employee/private data. Manifest permissions
and service type are declared; the service remains same-process, nonexported and
START_NOT_STICKY. Foreground/OEM/background/platform qualification is **unexecuted**.

Application gateway publication rechecks lifetime after asynchronous sign-in and
closes/refuses a stale returned session. Mode changes withdraw publication and stale connection/enrollment controls. Room
owner mode/uncertainty is atomically published to client workers; enrollment also
rechecks the current mode and loan state. Closing
unpublishes first, denies/drains work, cancels/joins the mode observer, closes both
actual journal owners, then closes the actual master/native owner. UI detach/lock
is not master retirement. Uncertain native close retains process exclusion.

## Deliberately unresolved production joins

The registered commerce services still use unavailable signature, return-policy,
recording-capacity and physical-recording authorities. The native company root is
P-256; existing business publications are Ed25519. No algorithm reinterpretation,
accept-all verifier, fake stock or attested physical-money success is supplied.
A same-process cohost is not real LAN transport. General business/pending/changed-
identity history recovery, additional accounts/devices, backup restoration and the
complete four-activity workflows remain required.

## Evidence and next work

At the earlier checkpoint, gateway/UI/Android/Desktop Kotlin compilation, debug
APK assembly and 45 unchanged UI regressions passed. Security results belonged
to an earlier source: 139 executions with two existing table-profile failures,
not a current pass. Database integration had failed test compilation at a
removed raw throttle callback. No native-device or independent complete business
acceptance was established. Debuggable Android did not qualify native custody.

The [dated journal recovery record](../../qa/pos/traceability/android-journal-recovery-20261027/README.md)
and [verification report](../../apps/pos/VERIFICATION_REPORT.md) preserve the exact
earlier evidence and original scenarios. That checkpoint did not establish
focused write-ahead/native-head/prefix/link/cleanup failure coverage; it is not
evidence of current verification status. Central implementation and verification
remain held pending owner testing and separate explicit authorization.

</details>
