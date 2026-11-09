# Supplier reference V2 — bounded Android native binding

**Behavioral execution: NOT RUN.** This source binds two bounded selections under
[#21](https://github.com/ukkera/kmp-apps/issues/21) and
[#59](https://github.com/ukkera/kmp-apps/issues/59). It is not a whole-case result,
admitted-RPC result, installed-UI result or owner acceptance.

## Preserved specification and selection

Select repository-root `qa/pos/designs/supplier-reference-v2/cases.md` and
`fixtures.json` (`SUPPLIER-REFERENCE-V2-CASES-1.0.0`) plus
`clarification-v1.0.1.md` (`SUPPLIER-REFERENCE-V2-CLARIFICATION-1.0.1`). The matching
test assets preserve the base JSON and clarification without transforming their
values or expected results. Original names, operation IDs, spacing, case, zeros,
Arabic and emoji are loaded from that frozen JSON.

Each invocation selects **one** leg and requires its own genuinely fresh synthetic
installation. The same operation IDs cannot be reused or retagged in one history.
The harness never clears application/test data or replaces a previous observation,
native enrollment, credential file, intent journal, history or key. Existing or
uncertain state refuses; an isolated fresh installation is an execution prerequisite.

| Selection | Bound sequence | Still unbound |
| --- | --- | --- |
| `SREF2-002` / `LEGACY` | Genuine current V1 create without reference; V2 set `LITERAL` with initial names/active; fresh protected V2 preload; edited names/false using that exact preload; V2 replace `MIXED`; empty gateway-editor input becomes explicit V2 null clear. | Independent `CURRENT` full-state sequence and the space-only extra step; complete case/path variants. |
| `SREF2-003` / `CURRENT` | Genuine V2 create with `LITERAL`; explicit current V1 names/active update omitting reference; current V2 detail preserves `LITERAL`; explicit V2 clear; earlier original formats and own-cut supplier sources remain exact. | Actual pre-transition retained V1 canonical bytes/originals, pre-transition unchanged grammar comparison, malformed old-body variants and old-binary goldens. Current V1 grammar is not historical evidence. |
| `SREF2-006` support only | Explicit original first-actor reauthentication followed by real protected Current and original lookup after native reopen. | Independent grants, additional/wrong actors, scope/device/expiry/disabled-account cases and all ordered revocation/race cuts. |

`LITERAL` remains exactly `" 0007-Ab  "` (10 UTF-16 units) and `MIXED` remains
exactly `"مورد-0007-A😀 "` (14 units). Present `Code` original/key/`EXACT_V1` are
compared without normalization. No `SPACE`, pagination or large-workload supplement
has been selected. Empty **editor input** is not a present-empty Code value.

The public creation form owns real supplier-ID generation. Its generated ID is
single-assignment bound to the frozen role **before stage**. The original native
setup independently generates its real organization/company/branch/device/first
actor; no fixture identity or revision counter is forced.

## Concrete source and cleanup

`NativeLocalFixtureOwner` directly performs the genuine company/head/branch/account
installation, original Master acquisition and exact first-actor/device native
credential/intent journal enrollment/reopen. Only the three explicitly provisioned
purchasing-read, supplier-administration and original-recovery grants are requested;
the real installation/current Master must authorize them.

`LocalDirectoryClientConnection` uses the acquired `original.client` directly,
the actual peer connection, exact device/scope and `original::isCurrent`, with
`SUPPLIERS` and `supplierReferences=true` selected before any request.
`LocalEndpointBranchGateway.directoryOnly` uses those actual journals. There is
no endpoint observer/wrapper, appointed test principal, accepting history, key,
authority, transaction, reader or journal implementation. Explicit V1 input is a
deliberate supported version choice, never a failure fallback from V2.

Every mutation is genuinely staged and then submitted through its durable original
owner. The harness consumes the protected terminal result DATA before invoking
the public recorded-original ACK; this is not an on-screen display qualification.
Successful logout must actually return `REVOKED`, while the original Master remains
operational. Cleanup visits returned sessions, drains the gateway, closes the exact
journals and actually quiesces/closes the Master even after failures. An unresolved
acquisition/close cannot qualify a later reopen. Runtime-only `CharArray` passwords
and every owned copy are wiped; no credential or password is stringified/persisted.

After **each** accepted step and ACK, actual close precedes a fresh original native
read. Every schema page, finite pass and cell chunk is consumed, validating offsets,
lengths and census completion. Actual read-close/reobservation/delivery are required.
Only nonsecret business rows are retained; protected plaintext/ciphertext cell
copies are wiped and not recorded. This census is supplementary DATA, **not**
production history admission. Every normal Master reopen independently runs the
actual production admission, without an injected verifier or history shortcut.

The census binds existing canonical command/result codecs to actual retained
OPERATION/JOURNAL bytes. Earlier raw results, original journal envelopes, source
supplier records and documentary identities are compared again after later edits.
Per clarification 1.0.1, own-cut names/active are required in genuine supplier/source
records; no names/active fields are invented in V1 or V2 result bodies. Actual
storage CAS and domain revisions, original actor/authentication/time/epoch/sequence
are observed separately. The V1 omission's V2 supplier/source reference is preserved,
even when the current supplier is later cleared.

The final fresh authorized original lookups return the complete originally observed
application result, allowing only its replay flag to differ. A second closed census
requires unchanged supplier originals/effects after those lookups. No resend or
lost-reply simulation is performed.

## No-effects and qualification boundary

The bounded sequence requires exactly one supplier and respectively five or three
accepted supplier mutations, each with its normal single result/journal/audit/upload
obligation. All original effects target only that supplier, with no jobs, claims,
extra business aggregates, stock-position/allocation/reservation/value state or
duplicate documentary identities. Native evidence therefore excludes unrelated
durable invoice/payable/payment/cash business state in this fresh fixture.

Separately authorized protected stock/payable/cash views remain **UNBOUND**; the
native census does not pretend to execute those protected view obligations. The
fixture also does not cover `SREF2-001`, `004`, `005`, capability/failure/no-fallback
and continuation variants of `007`, uncertainty/cancel/ACK variants of `008`,
atomic/source faults in `009`, or full shared UI/language/layout/filtering in `010`.
All admitted physical `SUPPLIERS` RPC/LAN/WAN-absent paths, other native platforms,
minimum-OS/device campaigns `D01/D02`, broader supplier workflow and owner acceptance
remain separately required. Native setup delivery ACK remains contract-unspecified.
No missing obligation is reclassified as a passing or not-applicable case.

## Explicit invocation prerequisites

Use the separately verified matching instrumentation package on a nondebuggable
`com.ukkera.pos.local.testing` target at Android API24+. Select an actually supported
original native key-protection policy; unsupported hardware/owners refuse without
fallback. Supply:

| Argument | Value |
| --- | --- |
| `class` | `com.ukkera.pos.android.independent.nativesupplierreferencev2.NativeSupplierReferenceV2Test` |
| `syntheticNativeSupplierReferenceV2` | `true` |
| `supplierReferenceV2Case` | Exactly `SREF2-002` **or** `SREF2-003` |
| `keyProtection` | Explicit `OS_ISOLATED`, `SECURE_HARDWARE` or `STRONGBOX` |
| `testedRevision` | Actual full 40-character lowercase source revision, independently linked to the installed pair |

The revision argument is a declaration, not proof of installed-artifact provenance.
The private no-backup observation reserves one original invocation before setup,
single-assigns native/input/source bindings and records cleanup/failure classes.
Existing `.bak`/`.new`/base evidence is never overwritten. Even an actually successful
bounded run records `WHOLE_CASE_NOT_QUALIFIED`, not a full frozen case PASS. Source
review, compilation and actual behavioral outcomes must be recorded separately;
all runtime cases currently remain **NOT RUN**. Central work remains **HELD**.
