# Local serial registration Master bindings

Contract: unchanged frozen
[local-serial-registration-v1](../../../../../../../../../../../../../../qa/pos/designs/local-serial-registration/README.md).
**6 bounded JVM methods passed on 2 November 2026**, with no failures, errors or
skips. The same initial selection passed 21 existing catalog-release-source
regressions. These are retained initial results, not a final all-module rerun;
see the [execution summary](../../../../../../../../../../../../../../qa/pos/traceability/local-serial-master-client-20261102/README.md).

| Class / supporting frozen IDs | Bound observation | Result |
| --- | --- | --- |
| `LocalSerialRegistrationFrozenMasterBoundaryTest`: LSR-01.a, 03.a/b/c/e, 10.h/j | Missing originals restrict requested activation preparation; a fresh current consumer refuses unavailable native originals | 2 PASS — bounded slices |
| Same class: LSR-19.d/h | Current read refuses unavailable native history rather than returning an empty state | 1 PASS — bounded slice |
| Same class: LSR-21.a/b/d | Numeric code, combined `"3.5"` revision/malformed ID, and all three missing permanent/native fields are refused by normalization | 3 PASS — bounded slices |
| `LocalSerialRegistrationSourceCancellationTest`: supplemental LSRC-CANCEL-001 | A deterministically suspended first source throws the identical cancellation; no normal preparation/admission return, other source read or supplied-input change | 1 NOT_RUN — observable subset; mutation evaluation/actions GAP |

The tests call the real Master normalization/preparation/current-read helpers
with unavailable sources and `publications = null`. Exact IDs, raw code, interval
and requested predecessor 3 / successor 4 are frozen request coordinates.
Allocation/authority/digests/key/zero signature and null expected storage revision
are inert syntax carriers, not native membership, authorization, current absence
or a valid CAS. No evaluate/commit, genuine original, successful activation,
durable restart, stock baseline or complete business ZERO vector is supplied.

Source review records that serial query selection refusal occurs in projection
after current protected authorization. The separately recorded database checks
cover current action denial before projection and typed invalid-selection rollback;
they are not this harness's genuine native/Master integration.

All **21 whole cases / 164 specification variants** and journeys remain
**UNBOUND / NOT_RUN**. The serial native publication client remains unavailable,
its candidate uninstalled, and stock/configuration/arrival consumers unbound.
Qualified Android/retained serial time, exact final interval decisions and original
native history remain unfinished. Frozen inputs and expected outcomes are unchanged.

JVM selector: `com.ukkera.pos.catalog.store.independent.localserialregistration.LocalSerialRegistrationFrozenMasterBoundaryTest`.

## Supplemental source cancellation — fixed binding before execution

Frozen specification: [LSRC-CANCEL-001, v1](../../../../../../../../../../../../../../qa/pos/designs/local-serial-source-cancellation/README.md).
This is a separate supplemental lifecycle regression, not a whole LSR-19 or
LSR-13.e binding. The original frozen packet and its request helper are unchanged.
The focused JVM method **FAILED before the correction** (expected the identical
cancellation object, observed no exception), then **PASSED after the correction**
on 2 November 2026. The final selected catalog run passed this method and the six
existing serial Master boundary methods, zero failures/errors/skips. Catalog-store
Android production compilation passed. [Actual results and limits](../../../../../../../../../../../../../../qa/pos/traceability/local-serial-decoder-cancellation-20261102/README.md).
The whole supplementary case still has the observer gaps below; no full-case
acceptance is claimed.

The concrete fixture is `suppliedRequest()` in
`LocalSerialRegistrationSourceCancellationTest.kt`: the unchanged helper's encoded
request with only `publication.definition.serialCode` replaced by `SYNTH-LSRC-001`.
All resulting public fields are fixed below. `D` means the exact string `01`
repeated 32 times; `Z` means exactly 128 zero characters.

| Public request field | Fixed value |
| --- | --- |
| `publication.definition.scope` | `BranchScope(DomainId("11000000-0000-4000-8000-000000000001"), DomainId("31000000-0000-4000-8000-000000000001"), DomainId("41000000-0000-4000-8000-000000000001"))`, in public constructor order |
| `publication.definition.allocationId` | `72000000-0000-4000-8000-000000000001` |
| `publication.definition.allocationDigest` | `D` |
| `publication.definition.serialAuthority.authorityId` | `72000000-0000-4000-8000-000000000002` |
| `publication.definition.serialAuthority.publicationId` | `72000000-0000-4000-8000-000000000003` |
| `publication.definition.serialAuthority.contentDigest`, `.issuerOriginDigest` | `D`, `D` |
| `publication.definition.publicationId` | `b1000000-0000-4000-8000-000000000004` |
| `publication.definition.registrationId` | `a1000000-0000-4000-8000-000000000001` |
| `publication.definition.revision` | `4` |
| `publication.definition.previous.registrationId` | `a1000000-0000-4000-8000-000000000001` |
| `publication.definition.previous.publicationId` | `b1000000-0000-4000-8000-000000000003` |
| `publication.definition.previous.revision`, `.contentDigest` | `3`, `D` |
| `publication.definition.serialId` | `c1000000-0000-4000-8000-000000000001` |
| `publication.definition.serialCode` | `SYNTH-LSRC-001` |
| `publication.definition.skuId` | `d1000000-0000-4000-8000-000000000001` |
| `publication.definition.skuPublicationId` | `e1000000-0000-4000-8000-000000000003` |
| `publication.definition.skuPublicationDigest` | `D` |
| `publication.definition.validFrom`, `.validUntil` | `2031-04-05T12:00:00Z`, `2031-04-05T13:00:00Z` |
| `publication.signingKeyId`, `.signatureHex` | `72000000-0000-4000-8000-000000000004`, `Z` |
| `expectedActiveStorageRevision` | `null` |

The public entry point is `MasterCommandHandler.prepare(StoredBytes,
CommandReadView, CommandContext)`, selected by the serial activation profile.
Context coordinates are the helper's synthetic scope above, actor
`51000000-0000-4000-8000-000000000001`, operation
`f1000000-0000-4000-8000-000000000001`, selected activation key and its semantic
digest. `publications = null` supplies no native authority.

Every `CommandReadView` read is counted: `aggregate`, `claim`,
`originalNativeBusinessPublications`, `qualifiedSerialRegistrationTime`,
`aggregateKeys`, `claimKeys` and `retainedCommand`. The first entered read waits
at a `CompletableDeferred` barrier before throwing the dedicated exception with
message `SYNTH-LSRC-CANCEL-001` and null cause. No source order or timing sleep is
used. Other nullable replies are `null`; pagination replies are synthetic empty
pages with null continuation. These replies must remain unconsumed and establish
neither native membership nor a genuine empty ledger. The caller captures the
exception directly around preparation, not through an `async.await` wrapper.
The byte-for-byte and parsed-field snapshots cover all supplied nested values.

**Verification gap:** `PreparedMutation.evaluate` and mutation actions have no
public observation hook during this preparation call. No authorization,
evaluation or mutation is supplied by the harness, but their required zero counts
are not inferred from cancellation. Normal preparation/admission returns are
observed; any other unexposed business-decision boundary also remains a gap.
A passing method would establish only the listed bounded observations, not full
LSRC-CANCEL-001 acceptance, native/durable effects or any whole frozen LSR variant.

Focused JVM selector:
`com.ukkera.pos.catalog.store.independent.localserialregistration.LocalSerialRegistrationSourceCancellationTest.lsrcCancel001_suspendedSourceCancellationPropagatesWithoutNormalPreparation`.
