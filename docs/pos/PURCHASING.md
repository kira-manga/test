# Local purchasing connection

## Supplier directory reference — additive V2

Shared supplier forms, reads and original-result recovery now use the supplier-only
`local.configuration.write/2` and `local.configuration.read/2` contracts when
explicitly installed. The optional directory reference is separate from the supplier
ID, names and invoice references. Its exact `Code` value preserves zeros, case,
whitespace and valid Unicode; empty editor input explicitly clears it.

V1 requests/results keep their existing bytes. A V1 names/active edit preserves an
existing reference and compatible supplier schema; an original V2 result shows its
own reference, never today's directory value. Current Master permissions, original
identity, CAS and durable effects remain authoritative. V2 pages fit the bounded
RPC payload using complete rows and a last-emitted continuation, without shortening
values or skipping unreturned suppliers. Failure never triggers a V1 fallback write.

Source review includes the corrected long-value pagination boundary. Automated
verification and remaining native/RPC scope are recorded in
[the supplier-reference checkpoint](../../qa/pos/traceability/supplier-reference-v2-20261106/README.md).
This addition does not complete supplier/item terms, reorder planning or purchasing.

## Invoice claim capture — additive V2

The shared capture path records the supplier's **actual claim**, with or without
an internal purchase order or allocation. `PURCHASING_INVOICE_CAPTURE/PERFORM` is
an explicit new permission; existing approval/file-capture grants do not acquire
it automatically. Capture is not receipt, substitution approval, payable
recognition or payment.

- Optional internal PO/revision and allocation links must resolve to the selected
  original context. Supplier order/allocation references are separate unchanged
  text, including UUID-looking text; they never supply internal relationships.
- **Documented goods** retains supplied item/product, purchase/base units, exact
  conversion and billed amounts. A linked documented-goods choice can retain
  different actual goods against an original PO line. **Use PO item/units** is
  explicit and never fills billed amounts from the order. Charges stay separate.
- The Master qualifies actual SKU/product and unit identities against genuine
  activated company publications, including company units not configured as a
  selected SKU's purchase conversion. Claimed rates and amounts remain claims,
  not catalog updates. Unsupported dimensions/precision do not bypass validation.
- V2 has its own command/read/body and original history. Existing V1 stored
  commands/results, permissions and replay paths are retained; identity/reference
  claims are shared across both formats. Legacy invoice reads explicitly report unsupported
  V2 capture rather than manufacturing V1 records. An evidence ID alone does not
  qualify an attached file.
- The invoice directory reads both formats without manufacturing legacy links.
  Capture-only records stay `UNMATCHED_UNAPPROVED`. Current cost access governs
  financial disclosure; selected details show actual terms and original context
  separately. Client-result retention must be durable before display or ACK.
- Capture-only records cannot enter legacy matching, credit or payment flows.
  Unsupported actions defer at admission before business evaluation or new physical
  acquisition. Existing original physical evidence/recovery remains available;
  deferring a new payout does not erase evidence or pretend a claim is absent.

### Restricted invoice-capture connection

The additive `INVOICE_CAPTURE` RPC profile is connected in shared source. Its
version-1 `pos.invoice-capture.read`, `.execute` and `.lookup` routes admit only
the V2 capture command, mixed-format invoice reads, existing PO context and
supplier/SKU reference reads. Export, scans, configuration mutation, receiving,
approval, matching, credit, settlement and physical operations are excluded.
Selecting this profile never grants a permission.

The shared capture-only workspace starts at **Invoices**, with **Existing
purchase-order context** as its second section. Its capture form is version 1;
the resulting invoice command is version 2. Android's explicit LAN selector uses
this same profile and requires separate device and employee custody choices.
No Apple/Desktop native implementation is implied by the shared connection.

Completed Execute/Lookup uses the exact retained original and current capture
and recovery authority, not a fresh preparation against mutable PO/catalog data.
Client retention still precedes presentation and explicit ACK. Incompatible
pending work is unavailable, not filtered into an empty successful scan; retained
physical continuations stay inert for their compatible workflow.

**Verification:** capture and matching supporting results are now recorded in
[the matching checkpoint](../../qa/pos/traceability/captured-invoice-matching-20261105/README.md).
Genuine authority/persistence/admitted-RPC/restart integration remains unfinished;
no refreshed Android artifact or complete purchasing workflow is established.
The [earlier RPC checkpoint](../../qa/pos/traceability/supplier-invoice-capture-rpc-20261105/README.md)
and [preceding capture source record](../../qa/pos/traceability/supplier-invoice-capture-v2-20261105/README.md)
retain their source-specific results and limitations.
The [earlier V1/form-v2 editor record](../../qa/pos/traceability/supplier-invoice-entry-20261105/README.md)
retains its original limits and 16 unrun supporting methods; its old contract gap
is not a claim about the new additive source.

## Captured goods matching and incremental payables — bounded V2

`local.purchasing.invoice.match-recognize` v2 connects an explicitly selected
captured invoice/original, one approved PO/allocation and an accepted valued
receipt. It matches exact eligible goods and records only that operation's new
payable increment in the existing purchasing ledgers. The distinct
`PURCHASING_INVOICE_MATCH_RECOGNIZE/PERFORM` permission uses the **full original
supplier claim**, including unmatched amounts, as its monetary-limit basis.
Explicit company no-ceiling policy remains supported; missing limits are not
unlimited. Matching does not receive stock, change valuation or move money.

The original capture stays immutable and `UNMATCHED_UNAPPROVED`. A separate
overlay shows matching/recognition progress. Full goods matching is not proof that
separate charges or settlement policy are resolved. Zero unmatched money is not
proof that zero-priced goods were matched. Current and historical settlement
gates retain those distinctions; capture alone still grants no financial action.

The shared `INVOICE_MATCHING` connection has invoice/PO reads, an exact source
quote, exact-ID original metadata and original-result recovery. Capture, legacy
matching, directory discovery, document export, configuration and physical payout
are excluded. Its common editor uses scalar source identities and invoice-local
line choices. Android's LAN selection maps to that same shared profile; native
Apple/Desktop integration is not established by this source change.

Finite PO history now requires both the original approval evaluation interval
(`validFrom <= evaluatedAt < validUntil`) and the retained native final decision
(`finalDecisionAt < validUntil`). A qualified approval may be used after expiry
without renewing it or authorizing a new receipt. Absent old evidence preserves
the unsupported-history hold; present corrupt or mismatched evidence still fails
strict validation. No accepting source or terminal purchase refusal is invented.
The higher-level recoverable source-unavailable response remains unfinished when
startup cannot safely admit an owner. This final decision is not the time of disk
sync or response delivery. [Review, tests and native qualification gaps](../../qa/pos/traceability/finite-purchase-history-20261105/README.md).

Exact matching preserves the literal unit/conversion branch and also accepts a
direct-base claim for the same SKU/product/base unit/scale when the rational
price per base unit, discount, tax and calculated amounts agree exactly. Thus
22 EACH can match the unchanged 2-CARTON × 12-EACH PO without inventing a
22/12-CARTON decimal or trusting an arbitrary documentary conversion. Old V2
literal-unit variance refusals retain their original first-refusal gate and exact
native no-effects/result checks; their native replay remains unverified.
[Nineteen focused tests and affected Android compilation passed](../../qa/pos/traceability/finite-purchase-history-20261105/README.md#direct-base-matching-follow-up).
Discrepancy/charge resolution, service acceptance, no-PO approval and broader
settlement remain separate unfinished purchasing work. The
[public matching contract](../../qa/pos/contracts/captured-invoice-matching.md)
defines exact source, authority, status and compatibility boundaries.
This source integration does **not** establish genuine current-authority,
persistence, admitted-RPC or restart acceptance; #59 retains that prerequisite.

## Recovering an acknowledged supplier operation

In the shared Purchasing screen, enter the saved **Original operation ID** and
choose **Look up original result**. This reads the immutable original result
through the current protected gateway; it does not create or resubmit a supplier
command. **Acknowledge result and continue** remains a separate explicit action.

An active form or a selected unresolved/unacknowledged operation must be resolved
first. Other saved originals are not removed by the lookup. Invalid input or an
unavailable read is reported as a lookup problem, not as a newly pending command.
The Master still checks current authorization and original ownership. Supplier
result delivery also requires durable acceptance by the client's original journal;
failed retention must not expose a successful result or silently acknowledge it.

## 29 October — original policy retention prerequisite

The actual commerce registry now includes shared purchasing-policy retention,
activation-pointer and protected-read services. Android acquires exact issued
envelope trust from the same original native company owner before Room/history
opening. Complete signed bytes, current employee authorization, original operation
identity and atomic result/effects/history remain required; absent trust refuses.
The structural original-policy cross-check is shared in `commonMain`.

**This is not positive receiving authority.** All results retain
`UNQUALIFIED_SELECTORS`; no product revision, document, arrival, stock, invoice or
payment is qualified by this profile. Sequential release catch-up, policy-unavailable refusal
reopening, operational policy use, UI/delivery and native JVM/Apple support remain
incomplete. An expired missed predecessor can block catch-up; its required recovery
semantics are unresolved.

Separate review resolved shared-trust retirement on ordinary caller cancellation.
Catalog JVM and Android application compilation executed successfully. Native,
persistence/RPC, concurrency and restart tests **NOT_RUN**; the 28 independent
purchasing candidates remain **UNFROZEN / NOT_RUN**, with explicit binding gaps.
[Exact source, review and limits](../../qa/pos/traceability/local-purchasing-policy-20261029/README.md).

The later shared rejection-history increment admits only original stale-revision
and publication-already-used activation results after exact original authorization,
BEFORE-source, result/resource and five native mutation joins. All policy-unavailable
refusals remain held; no receiving rights are introduced. Separate source review and
catalog JVM/Android compilation passed, with native restart/persistence/RPC and new
behavior tests **NOT_RUN**. [Source and limits](../../qa/pos/traceability/purchasing-rejection-history-20261029/README.md).

## 28 October, 07:07 UTC — actual CASH settlements and advances connected

Purchasing now connects invoice-bound CASH payouts, actual supplier advances and
separate nonphysical advance allocation to the real native journal/atomic master.
Start reserves invoice/cash and recovery capacity before physical payout; Apply
posts exactly one negative drawer movement. UNKNOWN retains reservations and the
original resolution identity. Allocation consumes actual unapplied advance once
without moving cash again. Gross PAYABLE recognition stays immutable; current due
subtracts actual credits/payments/allocations, never rewrites the original expense.

The same installed stock/participant/history owner serves bilingual forms, advance
lists and the native payout panel. Current permissions and expected revisions are
checked. Credit version 2 pins financial sources; original version 1 compatibility
is retained. Foreign/signed/unsupported-precision observations remain literal and
held; no silent currency conversion, rounding or unsupported posting.

Source `b9a94c08…` compiles on gateway/Desktop/Android, passes 526 unchanged
regressions and is packaged. **Actual supplier/native/Room/LAN/restart acceptance
is NOT_RUN.** Independent SCAD12 remains frozen with full fixture/owner-decision
blockers; source reviewers did not design tests. External tender settlement,
supplier refunds/returns, debit/excess/pre-match corrections, attachment/policy
qualification, broader purchasing/cost/tax and full capacity remain required.
[Exact ownership/source/results](../../qa/pos/traceability/supplier-food-financial-20261028/README.md).

Earlier sections retain historical scope/evidence; the current additions above
supersede only their statements that actual CASH advances/settlement do not exist.

---

## Historical 28 October — connected credit and uncontested matching source

Positive agreed EGP credits now have an invoice-bound immutable ledger, permanent
reference/evidence/document claims, one current remaining-due calculation, native
history and canonical bilingual forms. Original invoice/gross recognition, stock,
actual payments and costs are not rewritten. Lost known invoice history remains
unavailable, including when only its normalized credit reference identifies it.
PO-priced uncontested matching is also connected; a higher supplier price remains
an unapproved dispute, never silently accepted uplift.

This is a bounded source implementation, not full purchasing acceptance. Full-
original-invoice PURCHASING_APPROVE is conservative compatibility, not a finalized
credit-specific ceiling. Reference/date/evidence ID/agreement are employee
attestations, not qualified attachment contents or retention. Debit, excess and
pre-match credits, actual advances/payments, approval/evidence policy, supplier
claims/returns and cost/tax corrections remain required. The SCAD12 independent
families are frozen/NOT_RUN; genuine fixture/RPC/restore bindings remain blocked.
[Current source/ownership/evidence](../../qa/pos/traceability/supplier-credit-case-history-20261028/README.md).

## Historical 27 October connection and evidence

---

27 October 2026. **Connected production source; NOT complete local acceptance or
an owner-ready runtime.** Original purchasing requirements remain authoritative.

## Source path

The normal purchasing screen has separate suppliers, receiving allowances,
purchase orders, invoices and payables selectors. Costs require explicit selection
and the production read's current cost permission. A failed/capacity-limited read
does not become an empty list, zero due, matched status or approval. An optional
approval editor failure does not erase a successful authorized document read.

`LocalWorkflowController` → `LocalEndpointWorkflowGateway` →
`LocalPurchasingWorkflow` / `LocalPurchasingForms` / `LocalPurchasingIntent` →
the existing `LocalPurchasingCommands` and read services → the atomic master.
`LocalPurchasingPresentation` reviews the actual canonical command, including
unit snapshots and storage revisions, and distinguishes recorded outcomes.

No command executes during preparation. Original operation/body/action/form
identities are durably staged before submission; retries recover that entry before
new preparation. A match does not reselect newer receipt stock on retry. The
server rechecks authorization, monetary ceilings, source provenance, revisions,
remaining quantities, and atomic claims/result/effects/audit/outbox.

## Connected paths

- Approve an EGP purchase agreement with actual supplier/SKU/warehouse selection,
  exact unit conversion, dates/terms, inspection, explicit net-goods receipt-cost
  basis, prices/discount/tax, goods and distinct original charges.
- Record an invoice's actual supplied quantities/prices/discount/tax/net amounts
  against selected immutable PO allocations. Do not replace a supplier claim's
  values with PO values to force agreement. Invoices do not create stock/payments.
- Match a selected invoice line to an actual accepted receipt line using the
  production match-source read. Bind every returned required/nullable revision;
  an absent payable or receipt-billing row is not revision zero. Different terms,
  stale progress, insufficient quantity or unavailable history is refused.
- Read complete selected document lines/charges, progress and authorized money.
  Successful matching returns the **match ID**, not a made-up invoice result ID.
  Explicit recorded-result acknowledgement can navigate to the actual new order
  or invoice while retaining the chosen cost scope.
- `PURCHASING_APPROVE/PERFORM` protects legacy document operations; additive
  capture V2 instead requires `PURCHASING_INVOICE_CAPTURE/PERFORM`. Neither is
  receiving-allowance `ADMINISTER` or permission to issue supplier payments.
- Supplier/SKU/warehouse pickers browse all authorized pages (25 source rows per
  read) or look up an exact ID. Inactive/excluded rows do not lose the actual next
  cursor. Filtering is explicitly page-local. New preparation rechecks selected
  revisions; saved requests/retries do not reselect their sources. See
  [INVENTORY.md](INVENTORY.md) for the shared picker and lifecycle boundaries.

## Required remaining work — not deleted scope

- Qualify the connected original Android service/protected cohost journals,
  establish genuine company business-publication trust and complete additional
  employee/device enrollment before claiming a usable default installation.
- Complete supplier agreements/requisition/reorder/amendment/short-close journeys.
- Verify additive different-item/unit, unallocated and PO-less capture; then
  implement source-backed matching/dispute resolution of captured V2 claims,
  services and a non-PO approved quick-receipt basis. Capture alone is not any of
  those financial/receiving workflows.
- Complete and qualify exact/uncontested original-price matching (PUR-07), with
  actual accepted receipt sources, approval rules and disputed-balance handling.
  The connected legacy matching modes do not qualify captured V2 claims.
- Actual supplier advances, settlement/refunds, credit/debit notes, supplier returns
  and landed-cost allocation. Displaying a payable or recording a non-sale cash
  movement is not a substitute for these linked obligations.
- Complete configured reference-normalization/settlement-authority variants and
  authentic policy activation. Current fixed local normalization is not proof of
  the frozen configurable SYN-NORM1 fixture or global offline uniqueness.
- Full document/command capacity: documents use 5-row pages; orders/invoices allow
  16 goods plus 8 charges; commands remain 32 KiB and the actual journal remains bounded.
  The old first-25-only approval picker is now replaced by paging, not a hidden
  all-directory load. No truncation or blanket limit increase proves full capacity.
- Real durable/LAN/concurrency/lost-response/restart/backup and platform execution.

## Historical verification

Frozen commerce cases include COM-PUR-02-I/E, COM-PUR-07-I/E, COM-PUR-09-I/E and
COM-X-PROCUREMENT-PLANNING-I/E. Their complete assertions/variants were **not run
or passed** at this checkpoint; references do not imply complete editor coverage.
Missing focused independent cases and genuine integration remain explicit.

Historical fingerprint `1fea3bc9bf41c32093aca346a5462e26ba8eb8ebc9fed6ad5da8b8213bc89e73`:
Android Kotlin/gateway JVM/UI JVM compile PASS;189 inventory and45 UI unchanged
regressions PASS. No new APK, device, native Room, LAN or complete-case pass.
See [traceability](../../qa/pos/traceability/purchasing-20261027/README.md) and
[verification](../../apps/pos/VERIFICATION_REPORT.md). That result does not transfer
to the newer picker; current source-bound checks are in [the handoff](AGENT-HANDOFF.md).
Central remains held.
