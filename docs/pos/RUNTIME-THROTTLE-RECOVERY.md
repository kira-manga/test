# Original-account throttle history and restart

## Current extension

[Authentication recovery](RUNTIME-AUTHENTICATION-RECOVERY.md) describes the latest
source: original-account sign-in/refresh/logout, bounded session archival and
complete chronological continuity for one-sided throttle changes. It is
**uncompiled and unexecuted**, held for disk headroom; no historical pass transfers.
Default operational publication and protected client-journal enrollment are still
missing. Unsupported/general pending and business histories remain held.

**The sections below describe the historical compiled paired-throttle checkpoint,
not the newer source's complete boundary or verification result.**

Source `872877330bf90e79f4ee0726a61bae634fb4d023a414630e238c785aa77ed35a` (768 inputs), 27 October 2026.
**Bounded production-source progress, not complete authentication or native acceptance.**
No default operational runtime, enrolled client journal or admission bypass was added.

## Authority and exact supported boundary

Original `POS_OPERATIONAL_SPECIFICATION.md` §2 requires protected local credentials
and persistent throttling. Production architecture §6 and original OWN-01/REL-01
require local authentication and recovery without a central dependency.

The complete startup scanner now supports original C1 followed **only** by newly
captured throttle events for the original first account and original cohost device,
with unchanged original account/policy/admission/configuration digests. **Both
buckets must actually change** in each supported event: both prior/next states
then have genuine historical image joins. The complete scan, original native
settlement, issuer ACK, actual read close and current-tip checks still apply.

One-sided changes remain held: the unchanged peer needs its own historical
relation, not a signed capsule treated as proof of a state never joined. Generic
legacy events, sessions/refresh/logout, other employees/devices, changed policy
or incarnations, mixed writes and business/stock/food/recovery histories remain
held until their own semantic joins exist. No truncated or installation-only
prefix is accepted in place of an observed unsupported suffix.

## Follow the production path

1. `RoomPersistentThrottleStore` authenticates the original dependency records on
   the same Room writer loan. It captures their digests plus actual before/after
   buckets, original attempt, monotonic observation and completion outcome.
2. `ThrottlePairReducer` is shared by actual persistence and history verification.
   Existing individual reducer rules, timing defaults, account-denial precedence
   and the rule against reserving one bucket when its peer denies are unchanged.
3. `RoomRuntimeHistoryFrame` emits typed evidence only when actual native-trigger
   mutation count, table, ordered keys and UPDATE kinds agree. A later session or
   approval finalizer supersedes this throttle-only purpose, not the reverse.
4. The event scanner checks the signed full event/capsule and exact mutation keys.
   Native-selected throttle lineage rereads its own original event chunks, then
   joins decrypted previous/new states and current tips. Original seed digests
   bind unchanged dependencies; today's policy is not substituted at restart.
5. `RuntimeHistoryCapsule` retains only bounded intent/dependency/fact sections
   while all chunks stream past. Extra throttle-key collection is capped at two
   changes; larger/mixed histories still stream without accumulating their keys.

Mutable owned buffers are cleared; immutable decoded values are not claimed to
be securely erased. No password, verifier or bearer is added to the fact capsule.

## Compatibility

`ukkera.runtime-throttle-evidence/v1` is a genuine new retained profile. Its DTO
V1 names are serialization identities, not ordinary implementation iterations.
The native `LINEAGE_EVENT_CHUNKS` read pass is appended after existing enum values,
so prior ordinals remain unchanged. No Room schema, protected-record format,
existing command, individual throttle rule or frozen expectation changes.
Schema7 remains29 entities, identity `a06a6ac54e0e94955d5e8ccb3236db07`.
Older startup readers keep unsupported later histories held; a rollback must not
clear the new/original history to make an older binary open. No migration or
operational compatibility qualification is inferred from compilation.

## Verification and independence

- Final Android/desktop/gateway/UI Kotlin compilation: **PASS compilation only**,
  101.363s. Packet `local-compile/20261027T065350.943027Z-71fb45525b23481c83b53e83439d4a32` under
  `apps/pos/.workspace/evidence/`; verification SHA256 `6a254c475a720191247ad4d3727734fe9e7859a3ac267d80e52d4c8fc56cc762`.
- Preceding source `ad1363b90e0d325ce674fab3efb2f258247955292b50704d65bb589e361c4762` passed the same production compilation (284.339s).
  Its security regression execution **FAIL**:139 tests,2 failures,0 errors/skips,
  39.799s. Packet `local-only/20261027T064644.783203Z`; verification SHA256 `1e6c4fd657058489cbc16967d38d49ddc10349aba22432b06c6d67718abc9af4`.
  The two failures are unchanged pre-cleanup five-versus-ten protected-table
  expectations. The only later input change caps scanner key collection at two;
  that final source was compiled, not rerun through the security suite. No test
  source/expected result was changed. Earlier461 database fixture failures remain.
- `/root` implemented, integrated, source-reviewed and executed existing
  regressions solo. This is not independent source approval or business acceptance.
- Existing independent designer `/root/qa_auth`: frozen QA-AUTH-E006/I006 anchor
  complete persistent throttling, restart/device/concurrency/clock behavior. Their
  synthetic three-failure/60-second policy remains unchanged. Neither complete
  case was executed on this source. Focused new capsule/native-relation coverage
  is missing; the implementer did not invent that oracle or reactivate a designer.
- No new native/Room/LAN/process-kill/device execution, APK assembly or complete
  independent acceptance. All141 original scenarios remain required; only the
  seven central-only gates are deferred, not passed.

See [traceability](../../qa/pos/traceability/runtime-throttle-20261027/README.md),
[verification](../../apps/pos/VERIFICATION_REPORT.md) and
[remaining local work](CURRENT-STATUS.md). Full local POS is NOT owner-ready;
central implementation/testing remains HELD pending owner testing and approval.
