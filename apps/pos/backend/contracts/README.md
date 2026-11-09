# Evidence-v1 candidate contract

Candidate typed path: `POST /api/v1/branch-pos/ingestion/v1`.
**Not mounted in the actual Fiber service.** `HTTPHandler` is optional and
fails unavailable without both a production authenticator and atomic store.

* Request schema: `evidence-v1.schema.json#/$defs/Batch`.
* Confirmed-commit ACK schema: `evidence-v1.schema.json#/$defs/Acknowledgement`.
* Request/signed bytes: `golden/evidence-v1.json` (`batch`, `canonicalPayload`).
* ACK: `golden/ack-v1.json`, compared to actual Go service output in tests.
* Payload hash: `sha256:070da7cf4fd7fed76953b38330bc689e3e75a1bc8aa4720f88a37488001e471c`.

The fixture key is public deterministic **test-only** material, never an
enrolled device credential. `variants` are independent signed fixtures, not a
batch: their reused operation IDs with different contents would be conflicts.
The old incompatible candidate is retained only under `superseded/`.

## Exact scalars and bytes

`schemaVersion`, revisions, epochs, sequences and money minor units are
canonical decimal strings checked against int64 bounds. Positive counters do
not admit zero; result/effect revisions admit zero. The current bounded effect
union is `cashPosted` (EGP nonzero signed minor string) or `stockPosted`
(`{unitId: UUID, scaled: nonzero signed int64 string, scale: integer 0..6}`).
Zero balances remain valid in generic core Quantity; this *posted-effect*
contract requires a nonzero movement. No binary float/rounding/coercion is used.

All wire identifiers, including the three scope fields, use the actual shared
lowercase UUID grammar. Native legacy tenant strings are **not** cast into
UUIDs; see the required protected mapping contract below.

`masterCommittedAt` uses the explicitly agreed **evidence-v1 adapter subset**:
UTC `Z`, years `0000..9999`, Kotlin Instant fraction groups of 0/3/6/9 digits.
Examples: no fraction, `.100Z`, `.123400Z`, `.123456700Z`. Reject `.1Z`, redundant
zero groups, numeric offsets, invalid dates and expanded/negative years.
Generic core `UtcInstant` intentionally retains its wider range. A production
master must reject an unsupported evidence-v1 time **before local effects**;
there is no permission to rewrite a successful signed operation afterward.

The generated BSON datetime is only a millisecond-precision query projection.
The canonical payload string is authoritative for exact signed time/bytes;
never reconstruct them from `master_committed_at` after a Mongo round trip.

Canonical encoding is fixed-schema `branch-pos-json-v1`, **not generic RFC
8785**. Serialize compact UTF-8 JSON (values constrained to ASCII here), with
no whitespace, preserving effect order and these fields in order:

```text
schemaVersion, domainId, scope, operationId, authorityEpoch, journalSequence,
originMasterDeviceId, signingKeyId, authorityProofId, originalActorId,
catalogRevision, policyRevision, masterCommittedAt, effects, result
```

Nested order: scope = organizationId/companyId/branchId; effect =
effectId/kind/resourceId/revision/[money or quantity]; money = currency/minor;
quantity = unitId/scaled/scale; result = documentId/revision. Omit the unused
effect member. Input may contain that unused member as null, but it is omitted
from canonical bytes. Do not sort/reorder effects or normalize signed values.

```text
contentHash = "sha256:" + lowercase_hex(SHA256(canonical_payload))
signature = padded_standard_base64(Ed25519_sign(
  "ukkera-branch-pos:evidence:v1\n" || canonical_payload))
```

The actual decoder rejects duplicate/case-variant/unknown fields, missing
required members, invalid UTF-8, excessive nesting, trailing JSON, and numeric
amounts. Scale must be one unquoted digit: not `"6"`, `6.0`, `6e0` or `-0`.
Limits: 256 KiB request, 1..64 envelopes, 1..256 effects per envelope. JSON
Schema alone cannot prove lexical-number forms, duplicate keys, calendar/date
semantics, hash/signature, effect-ID uniqueness or cross-field checkpoint
invariants. Those are actual Go checks/adapter obligations, not schema-engine
claims. No third-party JSON Schema engine was installed for this probe.

## Admission and durability

The verified authenticator supplies `Principal.NativeScope` (source namespace
plus unchanged native Org/Company/Branch strings) and a branch-service device
UUID. Request JSON and generic app/server keys do not supply this principal.

Within the same mandatory transaction, `AuthorizeUploader` rechecks current
enrollment; `ResolveOrigin` resolves the **provisioned** domain UUID scope and
all historic actor/master/key/authority/resource/unit/policy relationships.
The returned native scope must equal the authenticated principal's scope.
Missing, ambiguous, disabled, reparented or wrong-source mappings fail; even a
receipt replay takes this admission path. No create-on-ingest mapping exists.

The store serializes/constrains branch+operation across epochs and reserves
branch+epoch+sequence for accepted **and quarantined** receipts. Same identity
and content returns the original receipt, never a second projection job.
Identity/position conflicts are durably recorded without overwriting original
history. Quarantine is retained but not projected; later retry does not promote
it. Evidence/receipt/job/quarantine/conflict/checkpoint writes are atomic.

Only confirmed durable commit returns an ACK. Any ambiguous commit returns no
ACK; retry the same operation IDs/content. Driver callback retries must not
accumulate duplicate response items. The test-only store exercises these
requirements; it is not Mongo transaction evidence or a product adapter.

ACK `coverage` is **`central-accepted-evidence-only`**. Checkpoints are per epoch,
numerically ordered, with the highest contiguous accepted sequence, highest
accepted sequence and sorted disjoint missing ranges. Quarantined/conflicting
items never advance accepted coverage. This does not prove standby, projection,
durable local backup, authority fencing, or complete recovery history.

The optional HTTP adapter returns JSON/no-store: 200 ACK, 400 invalid evidence,
401 failed authentication, 403 forbidden/signature, 415 non-JSON, 503 missing
adapters or unacknowledged commit; wrong path/method are 404/405. It never
serializes driver errors, credentials, raw payloads or stack traces.
