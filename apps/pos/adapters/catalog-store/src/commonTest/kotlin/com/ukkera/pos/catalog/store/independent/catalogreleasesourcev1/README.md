# CSR-FROZEN-1 bounded content wiring

Earlier bounded execution is recorded in
[actual results and coverage limits](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/catalog-release-source-20261101/README.md).
That evidence applies only to its recorded source. The separate explicit-matrix
and full-BULK extension below is **NOT_RUN** pending current-source execution.
Each method covers partial assertions only. Every whole CSR-001–026 case and
native/platform profile remains **UNBOUND / NOT_RUN**; frozen inputs and expected
outcomes are unchanged.

## Input binding

Source: `qa/pos/freezes/catalog-release-source-v1`.

| Frozen file | SHA-256 |
| --- | --- |
| `cases.md` | `f29a4ad11da62d0fe8d743c171c02b62dd609aa2bd314b64ffeae691d78802d5` |
| `fixtures.json` | `27e75a5c217f65a2ce0b8c41a92783a3d9fb68df5952438a224fd6d1212391ab` |
| `source-manifest.json` | `238670b8152212c62e17f137a9a4249c258b7255507857ab64ae46f35ca30b67` |

The unchanged `CatalogReleaseSourceFrozenFixture.kt` translates four regular product
bodies, ten SKU bodies and five releases. Frozen UUIDs, names in both languages,
prices, selected axis/value mappings, independent counters, exact predecessor
relations and manifest order are preserved. Its previous null-matrix projection,
the original content/proposal methods and their helpers remain unchanged.
Database unavailable-source tests retain their separate module-local regular
projection; they do not supply explicit-matrix or bulk-member qualification.
No production fixture API is added.

`CatalogReleaseSourceFrozenVariantFixture.kt` is a separate extension. It carries
the exact SHIRT-P0/P1/P2 axes `[M,L]` / `[BLUE,BLACK]` and ordered allowed rows
`[M/BLUE,L/BLACK]`, BAG-P0's single STANDARD row, and the full BULK-P0 one-axis
65-value/65-row matrix. All 65 bulk SKU descriptors retain their frozen UUIDs,
English names, barcodes, selected code values and revision 0. The 64-member
manifest is the exact first 64 descriptors; the 65-member input retains all 65.
No S/RED substitutions, axis splitting, Cartesian expansion or fixture-side
accepting validator is used.

Publication aliases are instantiated as disposable supplied-content identities,
not authenticated native publication IDs. Each regular and bulk member digest hashes the
exact instantiated canonical SKU bytes. Organization, axis/category/unit IDs,
SKU code, old SKU `catalogRevision=0`, ACTIVE status, price rule/policy IDs,
effective epoch and zero tax rate are explicit wire-only carriers for otherwise
unspecified fields. They are not assertions about native originals, dimensional
or category authority, branch cursor, current stock, fiscal tax or grants. The
regular frozen stock/retail/sell/purchase/expiry/precision and tax-inclusion traits
are mapped without deriving product revision from SKU revision.

Bulk descriptors do not specify SKU prices or Arabic SKU names. Their sellability
remains true. A required zero-EGP price row and empty Arabic SKU name are explicit
non-oracle DTO carriers; neither asserts a frozen price, fiscal tax or translation.
The product's frozen English/Arabic names and empty typed attributes are preserved.
Digest and codec assertions concern the exact supplied bodies, not native originals.

## Exact assertion mapping

All content methods below belong to `CatalogReleaseSourceFrozenContentTest`.
Any executed coverage is **PARTIAL**, not a
complete-case PASS. Constructor refusal is distinguished from content-gate
refusal; no new public error-code oracle is invented.

| Method | Frozen case / bounded assertion |
| --- | --- |
| `csr001_releaseCoordinateAndCodecKeepIndependentCounters` | CSR-001; CSR-024 own-coordinate fragment: exact codec/reference coordinate, release 0 / SKU 3 / product 2 separation; SKU-publication and cursor72 substitutions cannot form this release. No purchasing qualification. |
| `csr002_zeroAndOneMemberConstructorSlicesPreserveTheMember` | CSR-002: zero refused; one retained by codec and mapped-member gate; max 64 profile value only. Full 64/65 content fixtures remain unbound. |
| `csr002_declaredOrderAndCommaNamesSurviveCanonicalRoundTrip` | CSR-002: REL-0/1/2 exact member order and frozen English labels after codec/supplied-body lookup; comma stays within one label. Not a protected current read. |
| `csr002_suppliedMutableManifestCannotRewriteItsOwnedOrder` | CSR-002: changing the supplied constructor list cannot alter the owned manifest order or canonical bytes. Not durable/native original retention. |
| `csr002_opaque49152And49153ByteProbesAreNotValidReleaseBodies` | CSR-002: exact x-byte probes, 48KiB profile relation and actual canonical-decoder refusal. At-bound bytes satisfy size only; neither is valid content. Native ownership-work ordering is not executed. |
| `csr003_eachMissingMemberCoordinateFailsStrictCanonicalDecode` | CSR-003 MISSING-1–6: independently omit each of the six required member coordinates. |
| `csr003_eachWrongMemberCoordinateIsIndependentlyRefused` | CSR-003 WRONG-PUBLICATION / DIGEST / SKU-ID / SKU-REVISION / PRODUCT-ID / PRODUCT-REVISION: exact frozen single-field substitutions; constructor or actual member gate refuses. |
| `csr003_duplicateSkuAndPublicationRemainDuplicateDespiteDifferentCounters` | CSR-003 DUPLICATE-SKU / DUPLICATE-PUBLICATION: BLUE3/4 remain one SKU; repeated publication identity refused. |
| `csr004_regularMappedProductBodiesPinTwoAcrossDifferentSkuRevisions` | CSR-004 supported-field slice: BLUE3 and BLACK2 pin equal product 2 bodies, including both names; full supplied mapped provenance qualifies the content join. |
| `csr004_missingProductLegacyProductAndMissingVariantCannotQualify` | CSR-004 MISSING-PRODUCT / LEGACY-PRODUCT / MISSING-VARIANT: actual member gate refuses. Digests describe each altered body, preventing a trivial old-digest failure. |
| `csr004_exactFrozenMBlackMutationCannotQualifyTheFullSuppliedHistory` | CSR-004 INVALID-COMBINATION: exact M/BLACK is absent from the frozen allowed matrix and the actual full-history member join refuses it. This is an identity/history slice, not standalone matrix validation. |
| `csr004_conflictingSameBodyVersionAndDifferentPinnedVersionsCannotQualify` | CSR-004 CONFLICTING-PRODUCT-BODY / REVISION: exact `Conflicting shirt` change under P2, and BLACK2 pinning P1 alongside BLUE3/P2; adjusted digests/declared revision retain the frozen mutation meaning. |
| `csr004_p0ToP2WithoutEitherP1ProvenanceSourceCannotQualify` | CSR-004 GAPPED-PRODUCT-PROVENANCE: remove both supplied P1-bearing originals, leaving P0/P2; selected member content still exists but cannot qualify. |
| `csr006_completeReleaseSequenceKeepsEveryExactPredecessorAndFullMemberSlice` | CSR-006 full supplied 0→4 history and exact predecessor references; each release's mapped members checked. CSR-002 REL-3/4 order fragment; no branch catch-up activation. |
| `csr006_everyFrozenSequenceMutationIsRefusedByTheSuppliedHistoryGate` | CSR-006 all 11 history mutations, independently. Rewritten REL-1 uses the frozen REL-2 member order under REL-1's identity while successors keep their original predecessor coordinate. No completed-output authenticity is asserted. |
| `csr006_negativeAndInt64OverflowScalarsCannotWrap` | CSR-006: exact -1 and 9223372036854775808 probes in both the original canonical JSON-string representation and unquoted raw numeric representation, with type/exact-token guards and decoder refusal. Typed maximum-successor assertion is unchanged. No decoder range-specific error classification or native-sequence coverage is claimed. |

`CatalogReleaseSourceFrozenVariantContentTest` adds four separate methods, all
currently **NOT_RUN**. The original methods above do not use this extension.

| Method | Frozen case / bounded assertion |
| --- | --- |
| `csr004_exactFrozenAllowedMatricesSurviveCanonicalRoundTrip` | CSR-004: exact ordered SHIRT-P0/P1/P2 `[M/BLUE,L/BLACK]` and BAG-P0 STANDARD matrices through public product/SKU serialization and canonical re-encoding; regular supplied SKU shapes qualify. No native provenance. |
| `csr004_exactFrozenMBlackSelectionIsRefusedWithoutNeighboringHistory` | CSR-004 INVALID-COMBINATION: exact M/BLACK uses individually declared values but no allowed row. Public shape gate refuses without neighboring SKU history; singleton and full supplied member joins also refuse with recomputed exact digests. No error-code oracle or native completion is invented. |
| `csr002_exactFrozenBulkAxisAndSixtyFourMembersQualifyAsSuppliedContent` | CSR-002: full frozen BULK-P0 code axis and 65 ordered rows; all 65 exact SKU descriptors and actual supplied-byte digests; exact first-64 manifest retained by canonical roundtrip, sequence check and full supplied member content join. No native ownership, protected read or grant. |
| `csr002_exactSixtyFiveFrozenBulkMembersAreConstructorRefused` | CSR-002: all 65 distinct exact bulk members, individually shaped supplied bodies and matching digests, cannot replace the 64-member manifest through its public constructor. No fabricated member digests or native refusal. |

`CatalogReleaseSourceFrozenProposalTest` has one additional method:

| Method | Frozen case / bounded assertion |
| --- | --- |
| `csr001_exactInertReleaseProposalUsesItsOwnKindIdentityAndCanonicalBytes` | CSR-001: distinct CATALOG_RELEASE_V1 proposal kind, company, own publication, catalog entity, digest and exact copied canonical bytes; supplied SKU bytes unchanged. No owner/review/signing/completion, branch activation or other-company-state check. |

## Projection and execution limits

- The original fixture's former-schema comments describe its retained regular
  projection, not the current DTO's capabilities. Its full-history M/BLACK slice
  is unchanged; the separate extension now wires standalone matrix refusal and
  full 65-value product/64-member content qualification, still **NOT_RUN**.
- These supplied-content slices do not bind authenticated product provenance or
  genuine native 1/64-member authoring. Neither a wired 64-member method nor the
  old max-64 constant is an actual executed/native result.
- The frozen bulk input exercises 65 values and 65 matrix rows only. It does not
  specify new 128-axis-value or 256-matrix-row capacity boundary probes. No such
  boundary testing or separately frozen null-matrix legacy byte oracle is claimed.
- Missing native integration and required non-device checks remain development
  work under #17, not deferred device-only testing or whole-case passes.
- No helper asserting "highest published release" is used for activation. CSR-015
  active-relative advance/CAS/concurrency remains unbound, as do every positive
  native completion, grant, Apply/closed-refusal, historical cut/BEFORE recovery,
  protected current membership, purchasing/receiving and LAN workflow.
- Byte/content tests never establish actual native known-empty observation.
  Separate real file-backed Room unavailable-source tests are under
  `apps/pos/core/database/src/jvmTest/kotlin/com/ukkera/pos/database/independent/catalogreleasesourcev1`;
  those also cover only partial preparation/read slices.

## Focused execution selectors

From the independent POS Gradle root, after recording the actual source/dependency
revision and frozen-input hashes:

```text
:adapters:catalog-store:jvmTest --tests 'com.ukkera.pos.catalog.store.independent.catalogreleasesourcev1.CatalogReleaseSourceFrozenContentTest' --tests 'com.ukkera.pos.catalog.store.independent.catalogreleasesourcev1.CatalogReleaseSourceFrozenProposalTest' --tests 'com.ukkera.pos.catalog.store.independent.catalogreleasesourcev1.CatalogReleaseSourceFrozenVariantContentTest'
```

See the linked verification record for actual bounded results; no platform/native pass is claimed.
