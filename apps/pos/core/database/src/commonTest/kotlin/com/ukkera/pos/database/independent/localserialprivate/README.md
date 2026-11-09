# Private serial origin and policy unit slices

Frozen behavior: `qa/pos/designs/local-serial-private-request/v2/`, version
`local-serial-private-request-v2`. The v2 catalog-preparation versus original-result
recovery distinction is preserved; v1 outcomes are not reinterpreted.

Status: **10 bounded JVM methods PASS**, zero failures/errors/skips, on 2 November
2026. See `qa/pos/traceability/local-serial-private-request-20261102/README.md` for
source revision, exact results, initial failure/correction and verification limits.
No complete native/Room/RPC case is passed by these unit observations.

Selector: `com.ukkera.pos.database.LocalSerialPrivateOriginAndPolicySlicesTest`
under `:core:database:jvmTest`.

| Method / unit slice | Frozen assertion reference | Observable bound |
| --- | --- | --- |
| `lspr002AbsentOriginCannotAuthorizeAnyPrivateAccess` | LSPR-002-UNBOUND/MISSING | Origin-affinity comparison refuses missing origin for PREPARE/REVIEW/RECOVER/STATUS; immutable control evidence remains unchanged |
| `lspr002ChangedRequesterEvidenceCannotRepairOrAdoptOriginal` | LSPR-002-CHANGED/ADOPT, LSPR-003-REQUESTER | Changed represented requester and a different reading actor fail origin comparison; original data remains A. Plausible syntax is not authenticated origin evidence |
| `lspr003ChangedEntityAndContentRefuseAgainstPinnedOrigin` | LSPR-003-ENTITY/CONTENT | Each encoded scalar changes independently, with access/origin lookup and original digest fixed; comparison refuses against untouched control origin |
| `lspr005OtherEmployeeCatalogAndSupervisorGrantsCannotAdoptPrivateOrigin` | LSPR-005-OTHER-PREPARE/OTHER-READ/SUPERVISOR | Even a supplied current catalog allow and an allowed separate audited result projection cannot enter A's private origin capture. No actual supervisor projection content is inspected |
| `lspr006CatalogRemovalDeniesPreparationAndAllowsOriginalRecoveryCapture` | LSPR-006-POLICY-REVOKED, C6 | Removing only catalog permission denies preparation policy; original-account recovery remains allowed without recovery/catalog ALLOW, and REVIEW/RECOVER/STATUS retain A's exact origin at capture |
| `lspr006MatchingRecoveryDenyPrevailsDespiteCatalogAndRecoveryAllows` | LSPR-006-RECOVERY-DENIED | Matching explicit recovery deny refuses original recovery despite catalog/recovery allows and grant order. Review/status requirement mappings use the same original identity; no private release boundary is executed |
| `lspr006RecoveryDenyForDifferentPublicationCannotDenyPinnedOriginal` | LSPR-006-UNMATCHED-DENY | Deny bound to distinct represented E2/P1 does not refuse A/P0 recovery; it does refuse recovery for its own target |
| `lspr006CurrentEmployeeSessionAndDeviceDenialsOverrideOldAllowedSnapshot` | LSPR-006-SESSION-REVOKED/EMPLOYEE-DISABLED/DEVICE-RETIRED | Each current snapshot denial defeats earlier preparation/recovery allows. Master expiry-time policy and withheld notification are not exercised |
| `lspr005FreshSessionAndMovedDeviceKeepOriginalAccountBinding` | LSPR-005-FRESH-SESSION/MOVED-DEVICE | New substituted session/device identities for A authorize original preparation/recovery capture without rewriting the original actor/target |
| `lspr005OtherBranchAccountCannotRecoverOriginalAccountResult` | LSPR-005-OTHER-BRANCH | A different branch/account principal cannot recover the original branch/account result |

All observations are **L1 unit**. Identity verification, current authorization
snapshots and the audit callback are substituted. Evidence structs, dependency
fingerprints and event-image digests are ephemeral DATA; they do not establish
protected-writer provenance. Codec/evidence helpers bind source representation
without constructing native requests, accepted native histories or final decisions.
The exact frozen content strings are carried in a printable canonical field for
comparison only; there is no genuine alias-to-native corpus mapping.

Every complete LSPR case remains **NOT RUN**, including all zero-disclosure and
durable original/binding/approval/activation/business count assertions. In
particular, LSPR-001 authentication/before-preparation ordering, LSPR-002-UNAUTHENTIC,
all LSPR-004 retained native history variants, LSPR-006 race/release schedules,
all LSPR-007 native head ceremonies and LSPR-008 fault/restart/concurrent-retry
variants are not discharged by this harness.

Genuine non-device integration requires an actual `SelfOwnedNativeHistoryCustody`
implementing the native private-publication controller, a provisioned retained
original corpus and authentic protected-final decisions. `RoomSelfOwnedSerialPublications`
requires the same original `RoomRuntimeHistoryOwner`, `RoomLocalSecurityStore`,
bound `RoomCurrentLocalAuthorizationSource` and custody; fabricated substitutes
cannot satisfy its protected/native comparison. `RuntimeSerialPublicationHistoryJoin`
requires an actual `SelfOwnedNativeHistoryReadSession` with chronological actor
sources, final decisions and complete immutable image/lineage/current/tip reads.
No authentic JVM fixture for these paths is supplied here. Real P2pKit peer,
native head authentication, process reopen and platform/provider prerequisites
remain those specified in the freeze.
