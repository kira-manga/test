# Private serial request representation unit slices

Frozen behavior: `qa/pos/designs/local-serial-private-request/v2/`, version
`local-serial-private-request-v2`; v1 remains historical and unchanged.
The assertions below bind only observable portions of that freeze. The
supplementary grammar and buffer checks do not add native acceptance cases.

Status: **7 bounded JVM methods PASS**, zero failures/errors/skips, on 2 November
2026. See `qa/pos/traceability/local-serial-private-request-20261102/README.md` for
source revision, exact results, initial failure/correction and verification limits.
No complete native/Room/RPC case is passed by these unit observations.

Selector: `com.ukkera.pos.security.independent.localserialprivate.NativeSerialPrivateRequestRepresentationSlicesTest`
under `:core:security:jvmTest`.

| Method suffix / unit slice | Frozen assertion reference | Observable bound |
| --- | --- | --- |
| `lspr001ExactOriginKeepsPinnedProposalAfterCallerBufferMutation` | LSPR-001-OK, C1/C5 | Exact company/kind/publication/entity/content survives caller-buffer disposal and request decode. This is request data, not authenticated binding or preparation ordering |
| `lspr001MissingOriginAndOriginIdentityReuseCannotBecomeAccess` | C1/C4, supplementary exact-origin grammar | An access request cannot reuse the origin's request identity; a truncated origin reference refuses decoding. This does not exercise a wildcard protected binding |
| `lspr003ChangedCompanyKindOrPublicationCannotUsePinnedOriginAnchor` | LSPR-003-COMPANY/KIND/PUBLICATION, C4 | Single represented target changes refuse against a pinned origin lookup at the closed grammar boundary. The alternate kind is valid syntax, not a genuine native decoy |
| `lspr003ChangedEntityOrContentDoesNotCompareAsPinnedOriginal` | LSPR-003-ENTITY/CONTENT, C4 | Exact-target comparator distinguishes each single changed field; untouched origin data remains unchanged |
| `lspr008FreshAccessIdsRetainExactOriginInsteadOfNewPublicationIdentity` | LSPR-008 shared original-identity requirement, C4 | PREPARE/REVIEW/RECOVER/STATUS retain the pinned origin ID/digest and original tuple while access IDs change. No lost reply, restart or concurrent delivery is simulated |
| `lspr003CapsuleCopiesSuppliedAndReturnedRequestDependencyAndFactBuffers` | C1/C4, supplementary immutable-input boundary | All three buffers are copied on ingress and egress; caller mutation cannot rewrite represented request data. Dependency/fact fillers grant no authority |
| `lspr008ClearingNativeOwnedDecodedCopyPreservesSuppliedOriginalAndCapsule` | LSPR-008-CANCEL shared preservation requirement, C4 | Disposal of an exclusive decoded copy leaves supplied origin/capsule data intact. No native cancellation or durable preservation is established |

All observations are **L1 unit**. The fixture uses ephemeral UUID-shaped carrier
IDs, reference digests and dates. Exact frozen `content-A`/`content-B` strings are
embedded in a bounded printable field to bind comparisons through the supported
canonical representation. This is not an alias-to-native fixture mapping.

Every complete LSPR case and its native original/requester/approval/activation/
business count vector remain **NOT RUN**. These checks acquire no native owner,
Master authentication, protected storage, Room transaction, RPC peer or signing
authority. Authentic original membership, private disclosure, durable ordering,
fresh protected reads and all installed-platform variants require the genuine
production fixture described by the v2 freeze.
