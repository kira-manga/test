# BranchPos backend — bounded local evidence, not T06 acceptance

**LOCAL ONLY. No service was started, endpoint enabled, database migrated, or
artifact published.** The generated models and typed ingestion core are two
separate probes; no production adapter joins them yet.

## Delivered surfaces

| Surface | Evidence and limitation |
|---|---|
| Real generator | `spec/BranchPos.yaml` → seven real Go models, runtime YAML and namespace registry. Two byte-identical passes; all 54 existing source YAML files unchanged against their pinned Git versions. |
| Full Go registry | Overlay-compiles the real existing registry plus seven models. Every original registration is retained; exact JSON/BSON string ID/revision round trips are tested. No generated source was installed into `internal/document`. |
| Generic boundary | Unconditional `branch_pos__*` denial, including future names, reads and config models. Checked before nil/no-app/audit/server-key/super-admin bypasses and before mixed-write/upload/sync side effects. Actual target keys/configured relations/aggregation destinations are inspected, not ordinary business strings. |
| Typed ingestion core | Mandatory authenticator + atomic-store ports, Ed25519 verification, exact canonical payload, receipt/job/checkpoint orchestration, durable conflict/quarantine protocol. Private copy-on-write **test oracle only**; no memory product fallback. |
| Wire candidate | `contracts/evidence-v1.schema.json`, request/ACK goldens and six separately signed UTC variants. Actual Go decoding/signature/ACK tests plus Node producer/regressions. Kotlin verification is separate lead-owned evidence, not assumed by this directory. |
| Reviewable source | `patches/{main-next,viki-go}.patch` and file/pin/hash manifest, forward-apply checked against each exact pinned tree in temporary Git indexes. No checkout resets or commits. |

## Reproduce the bounded checks

From the project root, with the pinned isolated source checkouts, locked Node
dependencies and already-verified local Go/cache available:

```sh
tools/backend/verify.sh
```

This performs source/YAML integrity, owned-file formatting, real generation,
Node tests, Go core/permission race tests, targeted actual HTTP/gRPC/GraphQL/
DB/sync/import boundaries, overlay compile, and scoped patch export. It uses
`GOTOOLCHAIN=local`, `GOPROXY=off`, `GOVCS=*:off`, two Go build workers and no
automatic dependency retrieval. Missing prerequisites fail rather than enable
a service or download silently. Result: `evidence/local-verification.json`.

Go's vet subprocess cannot chdir into an overlay-only package directory. Only
the generated overlay compile uses `-vet=off`; ordinary Go source tests retain
vet. The first overlay attempt failed for that tooling limitation; it was not
counted as a pass. The corrected command and JSON/BSON test pass are separately
recorded in `evidence/generated-go-compile.json`.

Pinned revisions are in `tools/backend/pins.json`. The official isolated Go
archive SHA/size verification is in `evidence/go-toolchain.json`. Generator
source/output/YAML/gitlink hashes are in `generated/manifest.json`; patch hashes
and exact owned source bytes are in `patches/manifest.json`.

## Deliberate generation boundary

The seven models are `CompanyProfile`, `BranchBinding`, `OperationEvidence`,
`IngestionReceipt`, `IngestionCheckpoint`, `QuarantineEnvelope`, and
`ProjectionJob`. This is **not a frozen final product schema**. In particular,
it does not implement the protected domain/native identity registry, historic
authority/key store, full signed-envelope persistence mapping, or full product
effects/reporting taxonomy.

Existing `Org\Org`, `Org\Company` and `Org\Branch` references retain their
actual native **string** IDs. New domain IDs are UUID strings. Financial
amounts/versions/sequences use decimal strings, never generated `*int` or
generic JSON floating-point fields. The typed Quantity's bounded precision
`scale` is a small integer; it is not an amount.

Generated BSON datetime fields have **millisecond precision**. In particular,
`master_committed_at` is only a coarse query projection; `canonical_payload`
retains exact signed UTC bytes. Never rebuild a hash/signature, ordering or an
exact nanosecond timestamp from that BSON field. No Mongo adapter/nanosecond
persistence test has been supplied.

The actual `props.compound_indexes` dialect emits **non-unique** indexes.
`spec/index-requirements.json` is a requirement, not an installed migration.
Receipts need uniqueness across epochs by operation, and by epoch/sequence for
both accepted and quarantined evidence. Generic YAML/UI metadata is never
treated as authorization or authoritative validation.

Only real pure TypeScript transformations are imported. Legacy wrappers and
their pull/fanout/hard-coded-path behavior are never executed. The main-next
patch only narrows imports and extracts deterministic helper-aware registry
rendering; the isolated harness stages one new namespace without touching the
existing Modules checkout.

## Generic route coverage

`evidence/route-inventory.json` distinguishes:

* Actual in-process app handlers, including 21 cases in no-app and privileged
  contexts; mixed and configured-relation cascades preflight before writes or
  upload helpers.
* Actual five generic gRPC methods, GraphQL list/single/forward/inverse/raw
  relation resolver paths, generic primitives and sync pull/manifest.
* Actual direct-export/import-upload handlers and import flush/service guards.
* Source-traced aliases, background reprocessing, metadata-backed downloads
  and global search, which are **not** claimed as end-to-end service tests.

Handlers are mounted only in in-process test apps. Real middleware/gateway
authentication, live routing, Mongo views, search-index contents, stale files
and deployed configuration are not proven. GraphQL schema metadata may still
list configured model types; data resolvers deny BranchPos. There are no
generic GraphQL mutations/subscriptions in the inspected schema to claim tested.

This patch is **not a general tenant-isolation fix** for other legacy models.
Existing permissive generic modes/filter behavior remain outside this boundary.
An unrelated existing wallet helper can return nil after writing a 403 and let
its caller continue; this was reported to the lead, not silently refactored.
New BranchPos handler guards return directly at the call site.

## Required before enabling typed runtime ingestion

1. Protected per-entity domain/native mapping registry and installed
   bidirectional indexes; authoritative parent/disabled/tombstone checks in
   one transaction (`contracts/domain-native-mapping-requirements.json`).
2. Production authenticated native-scope branch-service principal, separated
   from central-human/local-employee identities; revocation and source-namespace
   checks repeated inside admission. No bearer material lives in this probe.
3. Real Mongo replica-set transaction adapter with exact generated persistence
   mapping and unique constraints; crash, retry, ambiguous commit, concurrency,
   tenant, relationship and index tests against that adapter.
4. Historic signing-key/authority proof/cutoff validation. Epoch changes alone
   do not fence an isolated writer. Unknown/stale evidence remains quarantined
   and never silently promoted or projected.
5. Actual mounted typed endpoint and product DTO/producer integration,
   including **pre-effect** evidence-v1 time admission and shared quantity/unit
   checks; scalar/golden tests alone do not establish this.
6. Idempotent durable projection worker, complete business-effect vocabulary,
   full recovery/standby/epoch coverage, independent engineering review and
   combined integration evidence. The ACK only covers accepted central evidence.

None of these missing runtime/recovery gates is marked passed by local green
tests, generated code, a notification, or this README.
