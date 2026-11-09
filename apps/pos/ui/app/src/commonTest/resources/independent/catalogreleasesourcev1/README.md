# Catalog-release frozen UI slices

Bounded methods were executed successfully on 1 November 2026; see
[actual results and coverage limits](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/catalog-release-source-20261101/README.md).
Each method covers partial assertions only. Every whole CSR-001–026 case and
native/platform profile remains **UNBOUND / NOT_RUN**; frozen inputs and expected
outcomes are unchanged.

## Frozen input binding

The source is `qa/pos/freezes/catalog-release-source-v1/`. Its
`source-manifest.json` binds these inputs:

| File | SHA-256 |
| --- | --- |
| `README.md` | `4a679bc09bb20bbb32cf09f5a6441c6de48b51532215e479f2e0151d0104a670` |
| `cases.md` | `f29a4ad11da62d0fe8d743c171c02b62dd609aa2bd314b64ffeae691d78802d5` |
| `fixtures.json` | `27e75a5c217f65a2ce0b8c41a92783a3d9fb68df5952438a224fd6d1212391ab` |

`FrozenCatalogReleaseUiContentInputs` projects the fixed business IDs,
independent revisions, literal names and declared member order into actual
public UI draft/editor DTOs. The semantic fixture is not a production wire
schema. The `df110000-...` publication IDs and SHA-256 marker digests are
explicitly **UNBOUND syntax/content-equality tokens**, not bindings of native
aliases such as REL-0 or SKU-BLUE-3. The typed predecessor references likewise
provide content inputs only, never genuine completion evidence. Required SKU
codes are explicit `UNBOUND-...` display scaffolding, not fixture barcodes
reinterpreted as SKU codes.

The rendered fixture uses the actual `LocalCatalogReleasePublicationCard`,
editor and runtime display attachment with a controlled UI protocol. Its
display-session/current flags and available source DTOs are not authenticated
native sessions, admitted devices or Master grants. `status(null)` supplies
only an empty controlled reference list, no source, and `mayPrepare=false`;
this is not authenticated known-empty native history. Preparation either
returns unavailable or waits on a cancellable gate. Review, head approval,
completed-output and activation calls fail the test if attempted. No native
action, approval, completion or committed result is manufactured.

## Selectors

The discovery target is `:ui:app:jvmTest`, with these class filters:

```text
com.ukkera.pos.app.independent.catalogreleasesourcev1.CatalogReleaseFrozenUiContentSlicesTest
com.ukkera.pos.app.independent.catalogreleasesourcev1.CatalogReleaseFrozenRenderedSlicesTest
```

The first class is in `commonTest`; the second is in `jvmTest`. There are eight
content methods and four rendered methods. Discovery names and existing English
control labels/metadata regexes are wiring selectors, not additional frozen
exact-copy requirements or proof that a selector has run.

### Content/editor assertions — bounded methods passed

Methods are in `CatalogReleaseFrozenUiContentSlicesTest`.

| Exact method selector | Bounded mapping if executed successfully |
| --- | --- |
| `csr001ReleaseSkuProductAndContentOnlyCursorAreNotInterchangeable` | CSR-001: release revision 0, BLUE SKU revision 3 and SHIRT product revision 2 remain distinct in the draft. A member-publication collision and observed release values 3/72 cannot substitute for this genesis draft. No branch cursor or existing SKU bytes are read. |
| `csr002ZeroOneSixtyFourAndSixtyFiveExerciseActualDraftBoundsAndOrder` | CSR-002: actual public draft construction refuses 0/65 members, accepts 1/64, and retains REL-0/1/2's supplied member order through editor/draft snapshots. This is cardinality/order only. |
| `csr003DuplicateSkuAndPublicationIdentitiesCannotBecomeDistinctMembers` | CSR-003: BLUE3/BLUE4 cannot become distinct members of one draft, and duplicate publication identity is refused. Other original-coordinate mutations are not authenticated here. |
| `csr004SharedProductRevisionIsIndependentAndConflictingRevisionsRefuse` | CSR-004: BLUE3 and BLACK2 retain shared SHIRT product revision 2 independently of SKU revisions 3/2. The frozen conflict changes BLACK2 to product revision 1 while BLUE3 remains at product revision 2; the draft refuses. Complete product bodies and P0/P1/P2 provenance are unbound. |
| `csr006DraftCannotInferMissingPredecessorSkipVersionChangeCatalogOrWrapSuccessor` | CSR-006: the editor/draft boundary refuses a missing nonzero predecessor, skipped observed successor, changed catalog, wrong predecessor revision and successor overflow at `Long.MAX_VALUE`. These are supplied structural relations, not a qualified completed history. |
| `csr007FrozenEditorAndPreviewRetainExactOriginalAcrossNewerChoices` | CSR-007: a frozen editor returns its captured revision-1 draft despite copied newer editor/member choices; publication, predecessor and old BLUE3 member remain exact. The actual preview DTO retains that definition and company. No newer native completion or head-approval decision is supplied. |
| `csr008RetiringOnlyTheUiAttachmentPreservesItsExactRetainedDraft` | CSR-008: retiring the UI attachment invalidates its lifetime while preserving the original unsigned draft and leaving native action identity absent. This is not uncertain head signing. |
| `csr020And022OwnedDraftAndPreviewCannotBeRelabeledThroughMutableInputsOrReads` | CSR-020/022: mutation of caller-owned member input and detached list reads cannot relabel the owned draft, snapshot or preview definition. This proves UI content isolation only, not durable original recovery. |

### Actual-card assertions — bounded methods passed

Methods are in `CatalogReleaseFrozenRenderedSlicesTest`.

| Exact method selector | Bounded mapping if executed successfully |
| --- | --- |
| `csr002CardOffersLiteralNamesAndAppendsTheExactOrderWithoutCommaExpansion` | CSR-002: controlled REL-1 choices display the three literal names and append `[BAG1, BLACK2, BLUE3]` in that order. `Cotton bag, natural` remains one member. No native original-name resolution occurs. |
| `csr002CardDisplaysEverySelectedPublicationDigestSkuAndProductCoordinateInDeclaredOrder` | CSR-002: the selected-editor renderer exposes every supplied publication ID, digest, SKU ID/revision and product ID/revision, in numbered member order. This is not the private native review/completed preview renderer. |
| `csr007UnavailablePrepareAndReferencesOnlyRefreshRetryTheSameFrozenDraft` | CSR-007: after unavailable preparation and a references-only refresh, both preparation calls receive the same frozen draft; all delivered status calls are `status(null)`. No newer source choices or source/mayPrepare authority are delivered, and no native action identity is created. |
| `csr008CancelledPreparationAndCardReopenKeepOriginalDraftWithoutManufacturingCompletion` | CSR-008: disposing the card cancels its suspended preparation; reopening retries the same unsigned draft without inventing an action/completion. Caller preparation cancellation is not a native signing attempt or proof of native noncompletion. |

## Explicitly unbound

- Genuine native aliases, original canonical member/release bytes, ownership,
  valid signatures, completed membership, authenticated history and head approval
  remain unbound. No positive P-NATIVE prerequisite is supplied.
- The exact opaque 49,152/49,153-byte probes and genuine schema-valid maximum-size
  authoring are not covered. Typed cardinality is not the 48 KiB native bound.
- Native preview rendering, recovery from actual retained references, retry while
  another signing intent exists, current target grants, known-empty genesis,
  old-SKU compatibility and native/root races remain unverified.
- Current Master authorization and protected scope, production transactions,
  immutable activated originals/pointer, ZERO_DOMAIN_DELTA,
  COMMITTED_DOCUMENTARY, original result/actor/effects/BEFORE dependencies,
  restore/backup, protected current reads, purchasing boundaries, same-device
  production adapter and real LAN RPC are not established by these UI slices.
- CSR-005; CSR-009 authorization; CSR-010–016; CSR-019; CSR-021; and CSR-023–026
  have no executed case binding here. The native/full variants of every mapped
  case also remain NOT_RUN. Passing cross-cutting UI assertions cannot substitute
  for a case whose frozen prerequisites require L2/L3/L5.
- Shared JVM rendering is not installed/physical/emulator/simulator verification
  of Windows, macOS, Android, iPhone or iPad. Required device variants remain
  NOT_RUN. Central implementation and verification remain held.

An eventual execution record must separately identify the actual source and
dependency revisions, exact selectors, platform/driver, prerequisite outcomes
and observed results. Neither these source files nor their frozen input hashes
are that record, and no passing outcome is asserted here.
