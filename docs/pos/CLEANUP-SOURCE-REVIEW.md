# Cleanup union safety review — source only

**Result: no remaining source-audit blocker after the scoped exception below.**
Applied-tree inspection is clean against the approved transformations. This is
not a compilation, behavioral test, independent acceptance or readiness result.
Reviewer: `/root/cleanup_core_review`; implementation remains the lead's work.

## Scope and packet schemas

Lead-supplied baseline: `068fac7` (no Git inspection). Captured 659 current POS
Kotlin sources at 2026-10-26T21:56:56Z: 503 production and 156 regression-source
files. Excluded build/generated caches, `.workspace`, historical external trees
and QA designs. Read the repository/app instructions and current handoff; original
authority remains `docs/pos/original-handoff`.

- Core: `symbol_renames` has 236 declaration rows; `file_renames` has 45 paths.
  Core paths are repository-relative: remove `apps/pos/` when normalizing.
- Workflows: `symbol_mappings` has 852 rows; `path_mappings` has 82 paths.
  These paths are already relative to `apps/pos/`. `reference_files` is only a
  lexical hint, **not** authorization to rename every homonym.
- Union: 1,088 rows / 1,062 distinct old identifiers, with no contradictory
  old-to-new assignments. Shared `stockRelationalAccessV2` mappings agree.

## Exact global-safe subset and scoped exception

The complete global identifier-token map is exactly:

```python
global_safe = {
    r["old_symbol"]: r["new_symbol"]
    for r in core["symbol_renames"] + workflows["symbol_mappings"]
    if r["old_symbol"] != "domainV2"
}
assert len(global_safe) == 1061
```

Its SHA-256, over UTF-8 `json.dumps(global_safe, sort_keys=True,
separators=(',', ':'))`, is:
`f821ad541bb6ae34bcc823541ec6df0a61dc35a73057330a246ed59590c81161`.

`domainV2` **must not** be globally replaced. Apply these pre-rename path scopes:

| Path relative to `apps/pos/` | Replacement | Code occurrences |
| --- | --- | ---: |
| `adapters/app-gateway/src/commonMain/kotlin/com/ukkera/pos/app/gateway/LocalRetailPhysicalGatewayV2.kt` | `retailDomainFact` | 11 |
| `adapters/app-gateway/src/commonMain/kotlin/com/ukkera/pos/app/gateway/LocalRetailPhysicalIntentV2.kt` | `retailDomainFact` | 1 |
| `adapters/sales-store/src/commonMain/kotlin/com/ukkera/pos/sales/store/LocalFinancialApplicationV2.kt` | `historicalOutcome` | 4 |

The last row is the lead's separately reviewed supplement: private payment/refund
outcome converters at original lines 262/269, called at 95/161. They are not the
gateway fact converters. No pre-existing `historicalOutcome` identifier was found;
actual V2 outcome DTOs remain unchanged. This exception was reported before edits.

Use identifier-aware editing, including code inside string interpolation—not
blanket textual replacement. Preserve literal segments, enum entries, stored
fields/tags/SQL/aliases and frozen evidence. The only authorized non-Kotlin runtime
entrypoint exception is the exact private Android manifest service-class reference
specified in the workflow packet. Finite deletions and receipt-helper sharing are
separate explicit operations, not additions to the global map.

## Homonym, serialization and collision findings

- No other mapped old identifier was a differently retained declaration,
  serialized property or enum entry. No mapped type is an `@Serializable` type or
  an enclosing owner of a retained nested serializer. Runtime references within
  wire classes are conversion methods (`restore`/`from`/`binding`), not wire fields.
- `retailPaymentObservation`, `retailRefundObservation` and `retailRejected` each
  receive two old names, but their declarations are in distinct `app.gateway` and
  `sales.store` packages/receiver scopes. They are not destination collisions.
- Existing `claimKeys` and `readOriginalInstallationReceipt` have distinct receiver
  owners. Existing `foodRead`/`foodPortion` belong to a different package;
  `originalCollection` is an unrelated domain property; `originalBytes` and
  `receiptPlan` overlaps are unrelated regression-local variables. No new
  classifier/member collision was found. All 127 path destinations were unique
  and absent before application, including case-folded comparison.

## Applied-tree source comparison

Captured the mapped tree at 2026-10-26T22:07:14Z and rechecked its source contents
before sealing this report:

- Exactly 651 Kotlin files survive; only the eight approved files disappeared.
  All 127 path moves are accounted for; no unexpected Kotlin path appeared.
- After applying the 1,061-name map and 16 scoped occurrences to the preimage,
  every surviving file's code-token stream matches, except the nine explicit
  finite-edit paths. Their differences are the approved dead declarations/import
  cleanup, shared `resolveReceiptLine` call and `private` → `internal` helper.
- No mapped-old code identifier remains. Literal-segment comparison, including
  nested interpolation, found no changed/added literal content; 15 segments were
  removed with approved dead blocks. Comments were not treated as executable code.
- All **1,180 retained `@Serializable` qualified identities, enclosing owners and
  explicit `@SerialName` annotations are unchanged**. Property-name census under
  serializable owners is also unchanged. No serializer identity was added/lost.
- The captured post-edit files did not change during the final audit. No production
  edits, builds/tests, Git/network operations or QA-designer access were performed
  by this reviewer. Existing schema/runtime/central holds remain unchanged.

## Input hashes

- `core-review/rename-candidates.json`:
  `b479e5ec6b806eb28eb09be80f7cf62365d7f35420ca030fbc40c5b912404031`
- `workflows-review/rename-candidates.json`:
  `0fd7f83d848976282a93877106b4d7f3f56a3f8993662f43aa2a9de96467c20e`
