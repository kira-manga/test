# Supplier-invoice capture RPC gateway support

**Execution: all seven JVM methods passed on 5 November 2026**, with no failures,
errors or skips. These methods consume unchanged design version 1 at
`qa/pos/designs/supplier-invoice-capture-rpc-v1/`. Every mapping below is partial
supporting coverage, not completion of an ICR case.

The production gateway and codecs operate over scripted Current/read/result
callbacks and the existing memory-only journal. `DURABLE`, `Recorded` and initial
lookup absence are controlled responses, not genuine storage, Master commit,
authentication or qualified absence. Invoice and SKU read probes return explicit
unavailability while observing outgoing selectors; no complete empty F0 page is
invented. Supplier and PO DTOs permit bounded preparation only.

## Per-method bindings

| Method | Partial frozen binding | Supporting assertion | Status |
| --- | --- | --- | --- |
| `icr01Icr03Icr09ProfileAdmitsOnlyCaptureAndItsReadFamilies` | ICR-01/03/09 | Installation selection admits only capture and invoice/PO/supplier/SKU read families; excludes export, scan and other configuration families. Not actual profile admission or authority. | PASSED (supporting) |
| `icr02Icr03GatewaySelectsInvoicePoAndSupplierSkuReads` | ICR-02/03 | Outgoing invoice page/direct-ID, PO and supplier/SKU selectors preserve the selected ID and costs flag; no mutation/staging. No returned F0, catalog or cost-release proof. | PASSED (supporting) |
| `icr03Icr09ExcludedWorkflowsAndActionsDoNotDispatch` | ICR-03/09 | Excluded workflows, purchasing sections, warehouse references and selected business actions reach neither read nor mutation dispatch/staging. | PASSED (supporting) |
| `icr07Icr10C0AndC1KeepFrozenActualClaimBeforeV2Dispatch` | ICR-07/10 | C0/C1 preserve exact literal references, dimensioned quantities, amounts and optional PO/allocation IDs in the staged V2 body; only explicit submit dispatches that body. No native staging or N1 proof. | PASSED (supporting) |
| `icr13Icr14Icr15LostResultLookupKeepsTheExactStagedOriginal` | ICR-13/14/15 | Lost scripted reply leaves the original unresolved; changed text cannot replace it; explicit lookup preserves original owner/body/digest without new PO/catalog reads or redispatch. No authoritative conflict or qualified status proof. | PASSED (supporting) |
| `icr16ClientResultWaitsForRetentionBeforeReturnAndAck` | ICR-16 | Caller return waits at the controlled retention barrier; ACK follows retained result. No physical durability, authenticated result qualification or UI-display eligibility proof. | PASSED (supporting) |
| `icr14Icr16Icr17FailedRetentionDoesNotLoseOriginalOrPermitAck` | ICR-14/16/17 | Failed retention keeps the original unresolved and blocks ACK; explicit lookup can retain the same original without redispatch. No current protected-control or native recovery proof. | PASSED (supporting) |

## Fixture facts and limits

C0/C1 keep **36 each / 37500 minor** and **2.500000 kg / 10030 minor**,
total **47530 minor**. C1 links only SKU-A to PO-A/allocation A. PO-A retains
revision **8**, **20 each**, and **20000 minor**; those old terms never replace
the claim. The older capture fixture supplies connection mechanics only, not an
ICR numeric oracle; its default installation is unchanged.

Supplied IDs remain unchanged. Unspecified carrier UUIDs use `IcrInputs.mappedId`
in one synthetic namespace: units 1–3, products 10–11, conversions 20–21, lines
30–31/40, warehouse 41 and connection/clock/source/tax 90–93. The invoice ID is
assigned once per reset. Additional supplier/tax/session/due-date DTO coordinates
are synthetic construction data, not new authoritative frozen facts. SKU-B has
identical purchase/base units: its frozen unit revision 4 is not bound by the
gateway's conversion-only revision carrier.

No full case is satisfied. Still unbound: actual admission/authentication and
Master permissions; F0 historical/capture reads; current source qualification;
Room, atomic N0/N1 effects and deduplication; cancellation/decoding/revocation
races; genuine retained native custody, restart and lifecycle; shared application
integration and all platforms. D1 numeric/outer limits, D2 normalization, D3
cross-lifetime recovery and D5 historical readers remain unresolved here. These
probes do not import supplier-only limits or weaken those requirements.
