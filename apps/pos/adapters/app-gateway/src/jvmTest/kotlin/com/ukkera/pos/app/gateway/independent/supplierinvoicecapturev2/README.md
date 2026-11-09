# Supplier invoice capture V2 gateway support

**Execution: all 23 JVM methods passed on 5 November 2026**, with no remaining
failures, errors or skips. **Profile S only.** There are 15 gateway,
form, input and controlled-read methods, plus 8 original-result/recovery methods.
They consume the unchanged `supplier-invoice-discrepancy-capture-v1` specification
1.0 and `supplier-invoice-referenced-discrepancy-v1` specification 1.0 with its
context-identity clarification 1.1. The loader is wired to check both frozen
artifact sets and the clarification's preserved manifest digests before loading
their numeric and literal expectations. No frozen expectation is rewritten. Three initial display checks used the old
original-claim label. Four selector/classifier bindings were corrected without
changing any assertion or amount, and the affected 15-method class passed on
rerun; the eight unchanged recovery methods retain their initial passing result.

The production shared gateway, command/read codecs and client-entry codec operate
over controlled session/read/result ports and a memory-only journal. A controlled
`Recorded` reply is not evidence of a Master business commit. Likewise, the
journal's `DURABLE` reply tests handling of that outcome, not genuine persistence.
Synthetic DTO identities and revisions are not scoped authoritative fixture
bindings.

## Public bindings

- `SUPPLIER_INVOICE_CAPTURE`, form version 1, maps to
  `local.purchasing.invoice.record` command version 2. It requires the separate
  `PURCHASING_INVOICE_CAPTURE` / `PERFORM` permission with no monetary approval
  limit; it does not reuse purchase-approval authority.
- The optional internal PO ID/revision pair is distinct from raw bounded supplier
  order text. Raw supplier allocation text is distinct from an internal line link,
  including UUID-looking strings, leading zeroes, Unicode and permitted spaces.
- `DOCUMENTARY_GOODS` records explicit actual terms without allocation.
  `DOCUMENTARY_ORDER_LINE:<id>` retains selected original context while recording
  all actual documentary item/unit/conversion terms. `ORDER_LINE:<id>` selects the
  original item/unit snapshot, but billed quantities and amounts remain explicit.
- `local.purchasing.invoice.read` version 2 distinguishes original-format invoices
  from capture-only claims. Exact-ID details carry actual lines and optional
  original comparison snapshots. Directory summaries intentionally omit lines.
  Cost-hidden projections omit monetary fields, not operational documentary text.
- The controlled success carrier has outer code `committed`, body code
  `purchasing.invoice_captured`, the entered invoice ID and revision 1, with no
  matching-progress revision. Returned/read records mark `CAPTURE_ONLY_V2` and
  `UNMATCHED_UNAPPROVED`, not approved or payable.

## Frozen contributions wired

| Frozen requirement | Supporting boundary |
| --- | --- |
| SIDC-01 | OTHER_UNIT keeps 5 TRAY × 6 = 30 EA / EGP 251.25; CHANGED_CONVERSION keeps 3 CARTON × 10 = 30 EA / EGP 270.75. Actual snapshots are not replaced with original PO terms. |
| SIDC-02 and clarification 1.1 | No-PO and explicit-PO-context mixed claims retain unallocated B and C lines: 24 EA / EGP 240.00 and 2500 G / EGP 100.00, total EGP 340.00. Optional links/inputs stay absent unless entered; unknown/raw supplier references never become internal links. Controlled detail and summary reads bind returned IDs, raw text and costs on/off. |
| SIDC-03, numeric shape only; SIRD-02(c) | Zero quantity, zero conversion denominator and unsupported entered quantity precision reject before client staging/dispatch, without PO fallback or staging the other mixed line alone. |
| SIDC-04, client hint only | Capture-only policy is distinct from purchase approval. A previously opened form cannot stage after the controlled current view removes capture permission. |
| SIDC-05 / SIRD-03, client original only | Lost controlled replies and client-entry codec round trips preserve exact original request bytes, actor, context, actual terms and result identities without new reads or redispatch. Changed content cannot replace the unresolved client original. |
| SIDC-06 | Mixed-claim result presentation waits for client retention; failed submit/lookup retention preserves the original and blocks acknowledgement. Recovery does not redispatch it. |
| SIDC-08 | Explicit conforming V2 selection retains 24 EA / EGP 240.00. An unchanged V1 fixture checks its command shape/codec round trip and original result semantics; unsupported capture form versions reject. |
| SIRD-01 | Documentary linked context retains actual B / 4 TRAY / 5 EA per TRAY / EGP 229.40 or actual A / 3 CARTON / 10 EA per CARTON / EGP 273.30. Controlled reads keep the original 12/1, 24 EA and EGP 240.00 comparison separate; hidden costs leave both operational snapshots intact. |
| SIRD-02(a), DTO absence only | A line absent from the controlled selected-PO DTO rejects rather than being silently dropped into unallocated capture. |
| Common original-actor/result oracle | A refused current session or different actor cannot read/adopt the client original. Wrong returned invoice ID, wrong initial revision and a legacy progress-result shape cannot be retained or acknowledged as the original capture result. |

## Limits and required remaining qualification

All 23 methods remain **NOT RUN**; no whole frozen case or original purchasing
scenario is claimed passing. The existing `supplierinvoicediscrepancyv1` package
and its original 16 methods remain unchanged. Its reused V1 fixture does not
constitute a historical database migration or archived-byte comparison.

The controlled read endpoint projects its decoded request and supplies empty
discrepancy lists. These probes qualify neither production discrepancy calculation
nor an independent persisted invoice/history read. Original PO amounts are frozen
controlled comparison terms, not observed persisted state. SIDC-07's authorized
later catalog/PO transition and historical snapshots remain unbound.

The changed-reference retry uses another DTO ID, not an independently resolved
valid REF-B. Gateway `Uncertain` demonstrates client nonreplacement, not
authoritative semantic conflict. Missing selected-line coverage proves absence
only in the supplied DTO, not genuine target/scope ownership.

Still unverified: real Master/Room/admitted-RPC integration; current activated
permission and revocation/commit races; dimension, product, supplier and
foreign-reference qualification; authoritative duplicates, atomic effects and
the frozen unchanged stock/cash/ledger cut; durable invoice/journal reopen,
backup/restore and migration; separately authorized recovery readers; all native
lifecycle/device variants and owner acceptance. The public V2 invoice marker and
capture-detail form absence are bound here, not controller/native fresh-payout
enforcement. No stock, receipt, variance approval, payable or payment acceptance
is established by these controlled ports.

From the POS Gradle root, the focused command for separate execution is:

    ./gradlew :adapters:app-gateway:jvmTest --tests 'com.ukkera.pos.app.gateway.independent.supplierinvoicecapturev2.*' --no-daemon --max-workers=1

Actual revision, execution outcomes and evidence belong in separate records;
the frozen designs remain unchanged.
