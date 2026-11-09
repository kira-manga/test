# Local serial registration client bindings

Frozen contract: [local-serial-registration-v1](../../../../../../../../../../../../../../qa/pos/designs/local-serial-registration/README.md),
unchanged `cases.md` and `fixtures.json`. The packet is stored under `designs`,
declares itself frozen and includes `FREEZE.json`. No frozen payload is modified.

**40 bounded JVM methods passed on 2 November 2026**, with no failures, errors
or skips: 19 gateway slices, 15 client-codec slices and 6 Master boundary slices.
The focused post-fix selection also compiled catalog-store Android production.
See the [correction summary](../../../../../../../../../../../../../../qa/pos/traceability/local-serial-decoder-cancellation-20261102/README.md).

Before the correction, both new direct activation/query duplicate-member refusal
methods **FAILED** because duplicates were accepted. That failure is preserved;
the earlier 27 passing methods did not verify the later additions. The post-fix
selection passed all seven added methods, including escaped-name and object-scope
controls. They are bounded codec checks, not additional whole frozen variants.

All 21 whole LSR cases, 164 specification variants, LSR-J1–J3, actual durable/
native/Master/RPC integration and platform pairs remain **UNBOUND / NOT_RUN**.
Passing bounded methods do not establish the complete business ZERO vector,
native authority, exact completed membership, qualified actual time, custody/
history, races, durable restart or positive activation.

| Test class | Frozen IDs | Bound observation | Result |
| --- | --- | --- | --- |
| `LocalSerialRegistrationFrozenGatewaySlicesTest` | LSR-01, 05–06, 10–11, 13–14, 16–17, 19, 21 | Shared workflow gateway and saved-journal format: exact identity; unsigned-stage/fresh-ID refusal; current access and late-lease refusal; dispatch-checkpoint failure; same-original retry with controlled absence and nonrecording refusal | 19 PASS — bounded slices |
| `LocalSerialRegistrationFrozenClientCodecSlicesTest` | LSR-04, 06–07, 12, 14, 21 | Serial codec/client normalization and saved-entry validation: typed refusal, exact raw code, changed-original refusal, unsupported version and revision bounds | 8 PASS — existing bounded slices |
| `LocalSerialRegistrationFrozenClientCodecSlicesTest` — initial direct-decoder methods | LSR-21.e | Direct activation/query duplicate-member refusal using existing synthetic identities; ordinary reordered/whitespace query syntax control | 2 FAIL before correction; 2 PASS after correction |
| `LocalSerialRegistrationFrozenClientCodecSlicesTest` — additional lexical/control methods | LSR-21.e | Plain/escaped-equivalent duplicate names in activation/query/result/read shapes; single-escape and separate-object controls; ordinary reordered/whitespace JSON | 5 PASS — derived codec subchecks |

Requests carry the freeze's exact synthetic IDs, raw code, interval and desired
revision/predecessor. Authority/allocation/key/digest/device/session coordinates
and the zero signature are inert syntax carriers. No accepting native verifier,
successful original or business result is supplied. Controlled `Current`, grants,
`AbsentAtCurrentWriter` and journal `DURABLE` inputs are adapter stimuli, not real
authority, authoritative absence or actual persistence. Retried execute calls
return `Deferred(HISTORY_INCOMPLETE)`; they never claim activation or completion.

The serial reference/publication range is `1 <= revision < Long.MAX_VALUE`.
Duplicate-field input maps to frozen **LSR-21.e**; LSR-21.d is the missing
native/permanent-field variant. The existing saved-entry refusal retains exactly
the same conflicting `serialCode` members. Its malformed-action assertion now
requires `actionMatches(...)` inside an `IllegalArgumentException` refusal
assertion so strict decoding may reject before saved-entry validation. This is
execution wiring only: the frozen refusal oracle, saved-entry validation and
historical passing outcome are unchanged. The added direct activation decoder
check reuses that payload; the direct query decoder check repeats the existing
`registrationId` member without changing any synthetic ID. Added lexical
subchecks use plain names, one-character Unicode escapes, fully escaped names
with either hex case, and equivalent escaped names on either side of the
duplicate. Each spelling alone must decode to the unchanged typed shape.
Result/read reply carriers supply neither a real committed outcome nor an
authentic currently authorized native read; the read shape is explicitly
`TIME_UNAVAILABLE`. Equal names in separate nested/sibling objects and ordinary
member reordering/whitespace remain valid syntax controls. No numeric/depth
bound is added. Full LSR-21.e and actual transport framing remain UNBOUND.
Unsupported-version validation preserves the original
input; it is not an actual upgrade/recovery run. Byte/collection/depth
limits, exact-size acceptance and actual transport framing remain UNBOUND.
Unstated positive byte-limit assertions were excluded from these frozen bindings.
Individual malformed-field checks are narrower than complete LSR-21.b/d variants;
exact exception/problem mappings are adapter observations, not frozen subtype
oracles. Frozen inputs and expected outcomes were not changed.

The newer private serial bridge is installed when the genuine Master companion
exists; see `qa/pos/traceability/local-serial-private-request-20261102/README.md`.
Its employee/native ownership, private recovery and independent head ceremony are
source-integrated but not qualified by a positive native/Room/RPC run. These tests do not construct the candidate native publication gateway,
prepare/review/approve originals or stage a genuinely completed publication.
Head signing without a branch activation grant, real secret cleanup during native
approval, unsigned-error edit permission and permission loss after a real durable
activation stage remain UNBOUND. Controlled journal data is not L2 storage proof.
Stock/configuration/arrival consumer installation and qualified Android/retained
serial time also remain unavailable; these checks do not qualify those paths.

JVM selectors: `com.ukkera.pos.app.gateway.independent.localserialregistration.LocalSerialRegistrationFrozenGatewaySlicesTest`
and `com.ukkera.pos.app.gateway.independent.localserialregistration.LocalSerialRegistrationFrozenClientCodecSlicesTest`.
The new method selectors are
`lsr21e_directActivationDecoderRejectsExistingDuplicateSemanticFieldInput` and
`lsr21e_directQueryDecoderRejectsDuplicateRegistrationIdentityField`.
The additional lexical/control selectors are
`lsr21e_directActivationDecoderRejectsEscapedEquivalentDuplicateMemberNames`,
`lsr21e_directQueryDecoderRejectsEscapedEquivalentDuplicateMemberNames`,
`lsr21e_directResultDecoderRejectsDuplicateMemberNames`,
`lsr21e_directReadDecoderRejectsDuplicateMemberNamesAtEachObjectScope` and
`lsr21e_separateObjectsMayRepeatMemberNamesWithoutRejectingOrdinaryJsonSyntax`.
The JVM test configuration permits database-owned reply constructors. Recorded
Android production/application compilation is compilation only; Android, iOS and
other-platform runtime test execution is not claimed.
