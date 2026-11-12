# Original-account authentication history and restart

27 October 2026. Source fingerprint
`ed90e11c350ef1f3a37df25acacdb709f1def574d366c604bff2b0350bfc7a86` (776 inputs).
**Production compilation now passes; authentication/native acceptance has not passed.**
Default operational publication and protected client-journal enrollment remain
missing. No native admission hold, schema or original requirement was bypassed.

## Requirements and supported boundary

Original `POS_OPERATIONAL_SPECIFICATION.md` §2 and production architecture §§6–7
require branch-local credentials, persistent throttling, local session renewal
and current authorization without a central login dependency. OWN-01/02,
REL-01/04/10 and UX-04/07 retain their complete business and platform obligations.

The complete startup verifier now has source support for original installation
C1 followed by typed authentication/throttle history for **the original employee
and original cohost only**, with the original credential, policy, admission and
authentication configuration unchanged:

- Password-attempt reservation/completion, including a one-sided bucket change.
- Sign-in, explicit permitted replacement and policy-denied sign-in. Denial does
  not fabricate a session; actual throttle and maintenance effects remain visible.
- Refresh rotation: one successor, the same original work start/deadline and no
  new scope. An expired or prior-service credential is not revived at startup.
- Logout and subsequent local sign-in. Logout does not change the branch role,
  cash custody, business records or policy.
- Retirement of unusable interactive sessions and immutable archival when the
  retained account reaches its existing 256-session bound.

This is a bounded extension, **not general disaster recovery**. Pending native
authentication commits, unsupported/mixed/business/stock/food histories, other
employees/devices, changed credentials/policy/admission/configuration and missing
evidence remain held. The existing separate pending-original-C1 recovery is not
extended to later transactions. Complete scanning, native settlement, issuer ACK,
actual read close and current-tip reconciliation still precede Room admission.

## Production path and trust boundaries

1. `RoomLocalAuthenticationService` begins an internal capture before maintenance
   and completes it after the actual protected account/issuance/audit writes. A
   successful sign-in capture must match the real MATCH throttle completion on
   that same transaction. Refresh/logout do not create a password attempt.
2. `RoomLocalSecurityStore` rereads and hashes the actual authenticated account,
   policy, admission and configuration on its writer loan. The existing native
   owner, current-peer/authorization checks, commit and publication barriers stay
   authoritative. Legacy unowned fixtures receive no artificial native capability.
3. `LocalSessionMaintenance` shares the existing retirement/archive rules between
   the protected writer and the historical verifier. Access expiry alone does not
   retire a still-renewable work session. Archival appends revoked identities before
   removing them from the bounded account; it never deletes durable history.
4. `RoomRuntimeHistoryFrame` hashes participating canonical plaintext at the actual
   sealing boundary. Native-trigger count, ordered key/table, INSERT/UPDATE kind
   and before/after existence must match before signing. Partial captures cannot
   finalize; zero writes cannot invent an event. Issued access/refresh facts must
   share generation, credential/admission revision and a bounded work deadline.
5. `RuntimeOriginalAuthenticationReplay` is a **startup proof**, not an in-memory
   application authentication service. It reconstructs the original empty account
   using a candidate credential and requires its full canonical hash to equal the
   original native seed. Original policy/admission/configuration seeds must also
   match; actual historical C1 images are joined independently later in the scan.
6. Chronological replay predicts every account/throttle/access/refresh/audit/archive
   plaintext digest. It retains at most 256 issuance identities, removes only its
   own verification entries after predicting immutable archive insertions, and
   joins final values to the independently decrypted current census. An unchanged
   throttle peer is proved by complete continuity from original seeds, not by
   trusting an unjoined capsule or today's mutable value.
7. Each relevant native-selected lineage rereads its own signed capsule and joins
   the actual decrypted image, binding, revision and mutation key. Event, lineage,
   image, current-row and relationship counts must all close. An unsupported later
   event invalidates the bounded proof; a valid installation prefix cannot admit it.

## Bounded reads and privacy

Authentication facts are limited to 264 image descriptors and 192 KiB. The
additional image descriptors do not accumulate an entire database or business
event. The full event pass still verifies **every mutation and payload chunk**.

For each subsequent authentication lineage, native storage derives the capsule's
starting chunk from that event's recorded mutable-image ends. The scanner derives
the same boundary independently from the verified header, then requires all
remaining ordinals, section bytes and digests. The scalar query checks storage
type/range and actual statement close. Callers supply neither SQL nor offsets.
This avoids rereading complete 256-session account images for every archive row;
it is not a prefix/shortcut admission rule.

Facts retain canonical access/refresh issuance metadata and **hashes of random
token secrets**, never bearer secrets, passwords or password-verifier bytes.
These hashes are not usable access tokens, but their account/device/session/time
metadata remains sensitive internal history. Do not log/export capsules, include
them in notifications or treat this as a privacy/retention qualification. Evidence
`toString()` is redacted. Mutable byte buffers are cleared; immutable decoded
strings/objects are not claimed to be securely erased. No history-pruning policy
is introduced by the verifier's bounded working set.

## Compatibility

`ukkera.runtime-authentication-evidence/v1` is a new retained format; its DTO V1
identities are necessary compatibility names. Ordinary helper filenames remain
unversioned. Existing protected-record encodings, Room entities/schema7, migrations,
table tags and native pass ordinals are unchanged. No migration was run or added.

The existing `LINEAGE_EVENT_CHUNKS` pass now also covers account, access, refresh,
authentication event and archive images. Reader and native traversal must be
upgraded together. Older binaries may hold the new history or read sequence.
**Never delete history, credentials or client journals to force a downgrade.**
Neither compilation nor this description qualifies rollback/device compatibility.

## Verification and independent ownership

- Implementer, integrator and source reviewer: `/root`, solo. This is **not** an
  independent source review. No helpers were spawned, delegated to or reactivated.
- Existing independent designer: `/root/qa_auth`, frozen `qa/pos/freezes/auth-v1`.
  Relevant anchors: E006/E007 and I005/I006/I008/I009/I010/I011/I012/I033/I040.
  These anchors are not exhaustive focused capsule/archival/corruption coverage.
  No cases, inputs, assertions or expected results were authored or changed.
- After the owner freed disk, this exact776-input source compiled for Android,
  desktop, gateway and UI in340.455s and passed153 unchanged sales/sales-store/UI
  regressions in70.553s. These are not authentication acceptance tests.
  Packets: `local-compile/20261027T094835.513989Z-489001d1ffd143ceb7af7d2483cafdbe/`
  and `local-only/20261027T095438.045265Z/`, under `apps/pos/.workspace/evidence/`.
- Security `local-only/20261027T095716.308816Z/` ran139 methods and failed2
  unchanged protected-table expectations. Database
  `local-only/20261027T095805.619314Z/` failed **compileTestKotlinJvm**:
  RoomPersistentThrottleStoreTest invokes the removed raw `transaction` callback.
  Zero database tests executed in that attempt. The historical623/461 native-
  startup fixture XML must not be counted as this attempt's execution.
- Do not restore an arbitrary production throttle-write callback, weaken expected
  table sets or use a legacy schema7 opener to manufacture green tests. Safe
  fixture binding and genuine native runtime integration remain required.
- At09:01 UTC the earlier launch was withheld below the8GiB disk reserve; that
  unchanged hold packet remains in
  `apps/pos/.workspace/evidence/local-authentication-source/20261027T090102.973721Z/`.
  Its SHA256 is `dc3d6702846ca492a726c708562422c8113fa96f51d3328fa0cd0c4f9ae0db1e`.
  **The disk shortage is now resolved.** Keep6GiB available-memory/8GiB disk reserves.
- No native/Room/LAN/device authentication acceptance or APK assembly occurred.
  Focused independent design/review and complete application/runtime execution
  remain open. Later cash-only source has its own compilation/regression packets;
  neither earlier nor later unrelated passes close these authentication gates.

[Current handoff](AGENT-HANDOFF.md), [verification log](../../apps/pos/VERIFICATION_REPORT.md)
and [current325 requirement/ownership rows](../../qa/pos/traceability/cash-close-notes-20261027/README.md)
retain the full local scope and all 141 original scenarios. Only Q-BE-01..07 are
central-only deferrals, not passes. Full local POS is **incomplete and NOT ready
for owner testing**. Central implementation/testing remains held until approval.
