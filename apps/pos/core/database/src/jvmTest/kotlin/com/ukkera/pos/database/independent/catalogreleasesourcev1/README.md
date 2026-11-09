# CSR-FROZEN-1 unavailable-source Room wiring

Bounded methods were executed successfully on 1 November 2026; see
[actual results and coverage limits](https://github.com/ukkera/kmp-apps/blob/implement/ukkera-pos-local/qa/pos/traceability/catalog-release-source-20261101/README.md).
Each method covers partial assertions only. Every whole CSR-001–026 case and
native/platform profile remains **UNBOUND / NOT_RUN**; frozen inputs and expected
outcomes are unchanged.

## Bound slices

All methods belong to `CatalogReleaseSourceFrozenUnavailableRoomTest`.

| Method | CSR-011 assertion slice |
| --- | --- |
| `csr011_missingOriginalFrameIsAdmissionHeldWithoutAnyDurableWrite` | A real detached transaction returns null original native observation. Actual activation `prepare` returns SOURCE_UNAVAILABLE and the required CATALOG_ACTIVATE/ADMINISTER binding, never a grant. Connection `total_changes()` and exact all-user-table snapshots remain unchanged. |
| `csr011_realFileReopenDoesNotManufactureOriginalNativeObservation` | Repeat the same held preparation after closing and reopening the actual same SQLite file. Exact tables/columns/quoted values remain unchanged; the original observation is still null. This is file reopen, not process restart or original-operation recovery. |
| `csr011_unavailableReadCannotPrepareKnownEmptyOrProtectedOriginalAfterReopen` | Active and explicit-publication queries through actual public read preparation fail before a PreparedRead exists with the native owner unavailable. No known-empty/protected projection is made; total_changes and exact snapshots remain unchanged before/after file reopen. No remote error/result or current authorization evaluation is exercised. |

The unavailable observation is always the actual production view's null value,
not a supplied empty publication list. Read preparation has a genuinely absent
owner, not a mock owner or hand-made completed snapshot.

## Fixture and limits

The regular supplied-body translator is module-local and equivalent to the
catalog content-test translator. It retains frozen names, versions, order and
actual canonical supplied-content digests. Wire-only carriers are explicitly
non-authoritative. No native original identity is asserted. The frozen allowed
combination matrix (CSR-004) and 65-value bulk axis (CSR-002) are not representable
by the current DTO; both remain implementation/schema limitations, not device
deferrals. Neither fixture nor expected result is rewritten.

A disposable JCA Ed25519 signature only supplies well-formed request-envelope
bytes; no installed trust/native ceremony or signature-membership assertion is
created. Keys/signatures are not persisted. Inert branch metadata has
`writable=false` and no custody/appointment/grant. It is only an FK parent, not a
pre-provisioned qualified Master.

Snapshots include every main user table discovered from SQLite, every column
and exact quoted values/BLOBs. No catalog original/current SKU, operation result,
journal, audit or outbox is manufactured. Tests never call evaluate/project,
submit a command, create AuthorizedAction, claim a known closed refusal, restore
historical evidence or exercise RPC. Missing member/closed-owner variants,
authenticated known-empty, current grants, successful activation/effects,
recovery, races, transport, genuine native custody and full domain-baseline state
remain unbound.

## Focused selector

From the independent POS Gradle root, after recording the actual tested revision,
dependencies and frozen-input hashes:

```text
:core:database:jvmTest --tests 'com.ukkera.pos.database.independent.catalogreleasesourcev1.CatalogReleaseSourceFrozenUnavailableRoomTest'
```

See the linked verification record for actual bounded results; no positive native integration or owner readiness is claimed.
