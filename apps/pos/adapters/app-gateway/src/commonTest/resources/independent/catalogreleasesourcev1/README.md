# Catalog-release frozen gateway and client-journal slices

Bounded methods were executed successfully on 1 November 2026; see
[actual results and coverage limits](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/catalog-release-source-20261101/README.md).
Each method covers partial assertions only. Every whole CSR-001–026 case and
native/platform profile remains **UNBOUND / NOT_RUN**; frozen inputs and expected
outcomes are unchanged.

## Frozen input binding

The source is `qa/pos/freezes/catalog-release-source-v1/`. Its
`source-manifest.json` binds:

| File | SHA-256 |
| --- | --- |
| `README.md` | `4a679bc09bb20bbb32cf09f5a6441c6de48b51532215e479f2e0151d0104a670` |
| `cases.md` | `f29a4ad11da62d0fe8d743c171c02b62dd609aa2bd314b64ffeae691d78802d5` |
| `fixtures.json` | `27e75a5c217f65a2ce0b8c41a92783a3d9fb68df5952438a224fd6d1212391ab` |

`FrozenCatalogReleaseGatewayContentInputs` projects fixed business IDs,
independent revisions, ordered member coordinates and literal names into actual
public content codecs, command intents and `StoredClientIntentV1.staged`.
The semantic fixture is not a production wire schema. The `df120000-...` IDs,
member marker digests and synthetic client-owner/organization/key coordinates
are explicitly **UNBOUND syntax/client-custody inputs**, not genuine native
alias bindings or current grants. The zero-filled 64-byte signature supplies
envelope syntax only; it is not cryptographically valid evidence. Predecessor
digests hash the actual supplied canonical unsigned content, not authenticated
originals. CAS 10/9 are syntax probes, not captured Master storage revisions.
Summary names are supplied UI literals, not assertions about the private
native-stage summary generator.

`SyntheticCatalogReleaseEncryptedJournal` opens the actual production
`JvmEncryptedClientIntentJournal` in a newly allocated synthetic private
directory using a generated AES key retained in memory. Reopen closes the
journal handle and creates a fresh custody context/lifetime; it does not kill
or restart the OS process. Unsupported durable storage is an actual test
failure, not a silent substitute or skip. Cleanup removes only that newly
allocated fixture. Controlled custody is not a native owner, secure-hardware
qualification, authenticated login or Master/RPC fixture. No recorded/committed
result, activated original, native action or completed snapshot is fabricated.

## Selectors

The discovery target is `:adapters:app-gateway:jvmTest`, with these class filters:

```text
com.ukkera.pos.app.gateway.independent.catalogreleasesourcev1.CatalogReleaseFrozenGatewayMappingSlicesTest
com.ukkera.pos.app.gateway.independent.catalogreleasesourcev1.CatalogReleaseFrozenEncryptedIntentSlicesTest
```

The first class is in `commonTest`; the second is in `jvmTest`. They contain six
mapping methods and four encrypted-journal methods. These are discovery
selectors only, not evidence that a method or production communication ran.

### Canonical mapping assertions — bounded methods passed

Methods are in `CatalogReleaseFrozenGatewayMappingSlicesTest`.

| Exact method selector | Bounded mapping if executed successfully |
| --- | --- |
| `csr001And009ReleaseActionIsSeparateAndProjectsCatalogAdministerOnly` | CSR-001/009: the real activation command matches only the catalog-release action among the tested unrelated actions, is in the catalog workflow, rejects an unrelated approval identity, and projects `CATALOG_ACTIVATE/ADMINISTER`. Permission projection and action matching are not current authorization or a branch grant. |
| `csr002OrderedCoordinatesAndLiteralSummaryInputsSurviveActualNormalizationAndStagingMapping` | CSR-002: real normalization, staging mapping and public codec decode retain REL-0/1/2's supplied publication/revision/predecessor/member order and three literal summary names. The comma-containing bag name remains one summary value. Names were supplied, not resolved from authenticated native originals. |
| `csr003EveryMissingMemberCoordinateRefusesAtTheActualCanonicalRequestBoundary` | CSR-003: independently removing publication ID, content digest, SKU ID, SKU revision, product ID or product revision refuses at actual normalization/action-matching boundaries. Wrong original-coordinate equality and handler/domain effects are not checked. |
| `csr006NegativeAndInt64OverflowingReleaseScalarsCannotNormalizeOrWrap` | CSR-006: exact raw numeric JSON tokens `-1` and `9223372036854775808` are injected only at `publication.definition.revision`, replacing canonical string `"0"`; target/token change and every other field's preservation are asserted before normalization refusal. This binds raw-wire refusal, not independent classification of numeric range validation, successor overflow or native history. |
| `csr017And018RealStoredIntentMappingPreservesOriginalAcrossSerializableDeliveryStates` | CSR-017/018: production journal serialization and intent mapping retain original owner/operation/request/digests/summary through STAGED and DISPATCH_UNRESOLVED states. Recorded result remains absent and acknowledgment false. This is not recovery of an actual committed result or closed refusal. |
| `csr018SemanticCasOrderBodyAndActorMutationsDoNotBecomeTheSameSavedIntent` | CSR-018: changing supplied release publication, expected CAS, manifest order or member SKU revision changes the semantic identity; changing actor fails validation against the original client owner. No Master retry or login/session transition is exercised. |

### Actual encrypted client-journal assertions — bounded methods passed

Methods are in `CatalogReleaseFrozenEncryptedIntentSlicesTest`.

| Exact method selector | Bounded mapping if executed successfully |
| --- | --- |
| `csr017CancelledClientWaitCloseAndReopenRetainTheExactDispatchedIntent` | CSR-017: after durable staging/dispatched marking, cancelling only the caller wait and closing/reopening the real encrypted journal retain the exact DISPATCH_UNRESOLVED request/identity; identical restaging leaves one pending operation. No Master commit, lost reply, OS crash or native effect is simulated. |
| `csr018ExactOriginalRestageIsIdempotentButSemanticMutationsConflictAcrossReopen` | CSR-018: same-operation publication/CAS/order/member-revision mutations produce actual client-journal identity conflicts before and after reopen. Exact original restage is durable/idempotent and preserves one pending operation and original content. These are client custody conflicts, not operation results from the Master. |
| `csr018DifferentClientOwnerCannotReopenAndRetagTheOriginalActor` | CSR-018: actual journal opening fails closed for a different client-owner actor, then the original owner reopens unchanged. This is controlled owner-custody enforcement, not account switching, current recovery access or session routing. |
| `csr020And022NewerClientIntentNeverRewritesOriginalBodyOrderOrLiteralSummary` | CSR-020/022: persisting supplied revision-0 and revision-3 intents, then reopening, retains the original body/order/names while the later intent has exactly `[BAG1, BLUE4]`. These are saved syntax intents, not native completions, branch activations, immutable activated originals or recovery of Apply effects. |

## Explicitly unbound

- Native aliases, authentic canonical originals, valid signatures, completed
  membership, known-empty genesis, full authenticated nongapped history, head
  approval, signing ambiguity and independent-native-owner races remain unbound.
- No P-MASTER/P-STORE transaction prerequisite is supplied by a client journal.
  Current grants/scope, commit/CAS races, branch SKU matching, release pointer,
  durable Master result/audit/outbox and ZERO_DOMAIN_DELTA or
  COMMITTED_DOCUMENTARY are not established.
- Original completed cuts, exact branch BEFORE sources, closed refusals, Apply
  actor/result/effect ordering/dependencies, compatible backup/restore and
  historical missing-evidence recovery are not exercised. Journal syntax
  preservation cannot substitute for those original-recovery oracles.
- Actual retained-reference recovery, unrelated signing-intent retry, private
  native-stage summary generation, native preview rendering, current target
  grants and old-SKU compatibility remain unverified.
- Cardinality and the opaque 49,152/49,153-byte probes are not covered by these
  gateway methods; no native 48 KiB bound or genuine maximum-size authoring is
  claimed.
- CSR-005; CSR-009 beyond action/permission projection; CSR-010–016; CSR-019;
  CSR-021; and CSR-023–026 have no executed case binding here. Native/full
  variants of mapped cases remain NOT_RUN. Purchasing selectors/policy/receiving,
  protected current reads, actual same-device service and real LAN P2pKit RPC
  are not supplied by codecs or client-journal IO.
- JVM storage checks are not installed/physical/emulator/simulator qualification
  of Windows, macOS, Android, iPhone or iPad. Required device variants remain
  NOT_RUN. Central implementation and verification remain held.

An eventual execution record must separately identify actual source/dependency
revisions, selectors, platform/driver, prerequisite results and observed
outcomes. This source resource and the frozen requirement hashes are not that
record and assert no passing result.
