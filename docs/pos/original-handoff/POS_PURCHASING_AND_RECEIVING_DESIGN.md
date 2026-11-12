# POS purchasing, receiving and supplier settlement

Revision 1.1, 21 October 2026. Researched operating design under the user's delegation. Read with the [operational specification](POS_OPERATIONAL_SPECIFICATION.md), [company policies and catalog](POS_COMPANY_POLICIES_AND_CATALOG.md), [architecture 3.2](POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md) and [implementation handoff](POS_IMPLEMENTATION_PLAN.md). Purchasing administration and operating screens are inside CMP. Defines first-release behavior; no endpoint, YAML or running procurement system is implemented here.

## 1. Practical workflows and separate documents

A small shop can use a quick-receive screen without preparing a purchase order. A company can use requisition → purchase order → receiving/inspection → invoice matching → supplier settlement. Both paths use the same guarded stock and financial commands. One convenient screen can create linked documents; it cannot collapse stock, invoice and money into an ambiguous “purchase total.”

| Document | Meaning and effect |
| --- | --- |
| Requisition/reorder proposal | Internal need; no stock, debt or payment posting |
| Purchase order (PO) | Approved supplier commitment, quantity, agreed price and destination; stock does not become available |
| Goods receipt | What physically arrived and its custody/inspection disposition; only accepted released quantities become sellable |
| Inspection/discrepancy | Accepted, quarantined, rejected, missing or excess units and required resolution |
| Supplier invoice | Amount billed, allocated to receipts/services and applicable tax; no second inventory quantity increase |
| Supplier settlement/advance | Actual cash or externally recorded payment with allocations; does not imply new stock or another expense |
| Supplier return | Actual goods leaving branch custody with original receipt/lot references; financial claim follows separately |
| Supplier credit/debit adjustment | Agreed financial correction; does not move physical units by itself |
| Landed-cost adjustment | Allocation of eligible freight/other acquisition cost; affects valuation, not quantity |

Supplier payment terms and operational supplier payables are included in this procurement baseline. They are separate from the explicitly excluded customer credit sales, customer/store-credit balances and loyalty. Full statutory general ledger, payroll, international trade and automatic bank integration are not implied. Start with EGP purchasing/settlement; foreign-currency valuation and exchange gains/losses require their own specified integration before being enabled.

## 2. Authority, company procurement and offline work

The receiving branch master is the sole writer of its physical receipt, warehouse balances, local purchasing records, settlement effects and audit journal. Central administration authors policies and procurement requests/allocations; the master validates and activates them. Central CRUD must not directly adjust branch stock or pretend a purchase order was physically received.

A company purchase spanning branches is divided into identified branch allocations with fixed quantity/approval limits. Each branch consumes only its own remaining allocation; changing another branch's allowance waits for that authority's acknowledged release. A disconnected branch cannot spend a shared unallocated company purchasing budget or consume another branch's outstanding quantity. Company dashboards consolidate acknowledged branch events and show their freshness.

Authorized branch staff can create and receive local purchases offline within company-delegated supplier/category/amount limits, and record a locally available invoice/payment. A company-required central approval that has not arrived remains pending; losing internet does not manufacture that approval. Preapproved POs and locally delegated approval remain usable. Ordinary sales continue independently of a blocked purchasing approval.

Each supplier invoice has one designated posting/settlement branch. Most invoices belong to their receiving branch. An invoice covering several branches uses individually identified receipt allocations confirmed by the originating masters, then one settlement authority; full matching may wait for those confirmations. It must not be posted and paid in full independently by every receiving branch. A received quantity is billed only within its remaining allocation.

Detect duplicate invoices using supplier/company, normalized supplier reference and relevant date/document evidence, retaining the original reference. The branch enforces its own uniqueness and idempotency transactionally. Arbitrary offline entries at two branches cannot guarantee company-wide uniqueness before synchronization. Route shared invoices to their designated posting branch, use known reference reservations where available, and surface discovered conflicts for controlled reconciliation. Do not automatically delete either receipt or issue a compensating payment on a duplicate warning.

## 3. Purchase order and quick receiving

PO header includes company, supplier, branch allocation/destination, currency, order/reference dates, expected delivery, payment terms and approver. Lines include product/variant, supplier item reference, purchase and base units with conversion snapshot, quantity, price, purchase tax/discount, allowed tolerances and requested delivery date. Supplier purchase tax is independent of the product's sale-price tax-included checkbox.

Use a primary workflow Draft → PendingApproval → Approved → Ordered. Track delivery (NotReceived/Partial/Received/ShortClosed), billing (Unbilled/Partial/Billed/Disputed) and settlement independently. Cancellation affects only unfulfilled quantities; a received or billed line cannot be deleted to erase its history. Hold/release or quantity changes require a new approved revision, not overwriting the earlier commitment.

Quick receiving asks for supplier, items, actual quantities, cost and inspection information. If an invoice is available, the flow also creates the linked invoice; if actual money was paid, it records the separate settlement. It supports receipt-without-bill and bill-before-arrival without pretending either is a completed three-step purchase. An invoice entered before delivery remains unmatched and does not unlock stock. Never silently invoke both a receipt stock update and an invoice stock update for the same units.

## 4. Receiving, units and quality

Record physical arrival independently from what was ordered or invoiced. For goods accepted into branch custody:

`physical quantity held = accepted released + inspection quarantine + rejected held`

Goods refused at the door and never held are documented as refused, not added to warehouse stock. Missing quantities remain outstanding/disputed. Rejected-held and quarantined goods have identifiable locations and are unavailable for sale. Record ownership/valuation treatment for rejected supplier-owned custody rather than automatically recognizing it as purchased sellable inventory.

Example: two cartons at 12 units each arrive. Twenty-two units pass inspection and two are held as damaged: physical custody increases by 24 units, sellable quantity by 22, and rejected custody by 2. The supplier invoice may still request 24; that discrepancy requires resolution instead of hiding the rejected units or silently paying as though all passed.

Confirm lot/expiry for tracked goods and exactly one serial per serialized unit. A selected lot's expiry, item and company must match; missing/expired required lot data cannot be released to sellable stock. A partially passed inspection releases only those specific quantities. Required inspection blocks release; its result cannot be inferred from invoice approval. Quarantine release/return/waste references the original custody event and cannot increase stock twice.

Partial delivery leaves the outstanding PO quantities visible. Default over-receipt tolerance is zero unless company policy explicitly permits a bounded quantity/percentage. Extra physical goods still need a custody record: place them in non-sellable excess-hold pending authorized PO amendment/acceptance or return. A receiver cannot quietly increase the approved order. Wrong SKU/substitution requires an explicit approved item mapping/new line and actual lot/unit details, not renaming the original receipt.

Track accepted fulfillment, inspection-pending allocation, rejected quantities and explicitly short-closed quantities separately. Pending inspection reserves its allocated ordered quantity against another concurrent receipt, but does not mark it accepted. Rejection releases that pending fulfillment allocation or creates an explicitly linked replacement allowance; it cannot enable both for the same units. Twenty-four delivered with twenty-two accepted and two rejected leaves a two-unit supplier fulfillment/claim decision, not an automatically complete order. Subsequent supplier returns reopen/reallocate a replacement only through an explicit agreed resolution, not merely because physical stock left the branch.

Store exact product-specific UOM conversions on every line. A later change from a 12-unit carton to a 10-unit carton does not rewrite the original receipt. Reject incompatible dimensions, excessive precision or fractional serial counts. Use one idempotent posting transaction for stock effects, receipt lines, PO-quantity consumption, result/audit and outbox. Two clients cannot receive the same remaining allocation twice.

## 5. Invoice matching and supplier payment

For stocked goods, match the PO agreement, accepted billable receipt quantities and supplier invoice. Quick receipts use their approved purchasing basis if no PO exists. Service invoices use explicit service-delivery/acceptance evidence instead of fictional warehouse receipt units. A line can be partially matched; rejected/unapproved excess quantities and unapproved price/tax variances remain exceptions.

Company settings control price, quantity and rounding tolerances, approval limits and whether uncontested matched amounts can be settled before a dispute is resolved. Default material discrepancies block automatic approval of the affected amount; they do not prevent saving the supplier's actual claim. An approval records why a variance was accepted and preserves the original ordered/received/billed values.

Account for receipt-not-invoiced, invoice-not-received, partial invoicing, advance, payment due, partial settlement, pending external payment, overpayment and supplier credit separately. Explicit allocation ties money/credit to invoices; an advance remains unapplied until allocated, and refunding it resolves that obligation. Record an overpayment truthfully and route it for refund/allocation; do not manufacture another purchase to consume it.

Cash settlement requires permission, available physical cash and a real payment from the assigned drawer/safe. Link it to one cash pay-out/custody movement. Payment from a company bank account is an authorized externally recorded outcome with evidence; an unknown outcome remains pending. Paying for inventory reduces supplier debt/cash; it is not an immediate second operating expense on top of the inventory acquisition cost. A direct consumable/service expense follows its configured accounting category.

Example: invoice 1,000, earlier allocated advance 200, new physical cash payment 500 → paid/allocated 700 and remaining due 300. Today's drawer decreases only by 500. Repeating the submission cannot apply the advance twice, pay another 500 or change stock. A credit adjustment of 100 subsequently reduces the remaining payable to 200 without claiming a physical cash refund occurred.

## 6. Supplier returns and corrections

Supplier returns reference original receipts, affected stock/serial/lot and remaining eligible quantities. Check physical availability excluding other reservations before dispatch; already sold/consumed stock cannot be returned by making it negative. Preserve rejected supplier-owned custody versus purchased inventory so their departures use the correct ledger/value path.

Returning goods creates a dispatch/custody event and a supplier claim. Supplier acceptance, credit/debit note, cash/bank refund and replacement delivery are separate statuses. A credit note alone does not restore or remove units. A replacement arriving later creates its own linked receipt and inspection; do not both reverse the original return and receive the replacement as new stock. Replacements need an explicit free-of-charge/adjustment reference so they do not automatically create a second payable.

Supplier invoice/credit values follow the agreement and accepted claim. Physical inventory reduction uses the branch's pinned valuation method; the difference between inventory book value and supplier credit belongs to a traceable purchase-cost variance, not a forced assertion that those amounts always equal. Rejected supplier-owned goods leave custody without inventing a purchased-stock loss.

A mistake in a posted receipt is corrected by linked, permissioned reversal/adjustment. If downstream sale, kitchen use, transfer or reservation prevents a whole reversal, preserve those effects and resolve the specific quantity/value discrepancy. Do not delete a used receipt, mutate historical serial provenance or rewind a previously closed cash period.

## 7. Valuation and acquisition costs

Keep the already selected moving weighted-average valuation per SKU/warehouse, with exact base-unit quantities. Lot FEFO is a physical selection policy, not an implicit switch to FIFO cost. Store issue cost at sale/preparation time. Example: stock 10 units at cost 20 plus an accepted receipt of 10 at cost 30 gives 20 units valued at 500 and average cost 25, before additional eligible cost.

Freight and other eligible acquisition costs attach to specific receipts/stocked lines. Default allocation is proportional to eligible goods' net purchase value; company may explicitly select quantity or weight where meaningful. Validate the basis and use deterministic rounding. Non-stock service lines are excluded from an inventory-cost allocation unless they are themselves the named acquisition-charge source; do not distribute freight onto unrelated service lines and lose inventory cost.

Example: stocked goods A cost 100, stocked goods B cost 100, unrelated service C costs 100, and eligible freight is 30. Allocate 15 to A and 15 to B; service C remains 100. The supplier obligation/charge source is recorded once, while the allocation supplies inventory valuation rather than another payable.

Late actual cost corrections split explicitly between units still held and units already issued/consumed, using a documented deterministic cost-allocation calculation. Post an inventory-value adjustment and consumed-cost variance as applicable; quantity remains unchanged. Keep original sales/closing/issue snapshots immutable and expose the linked correction separately in cost reports/accounting export. Do not claim that the original sale was made at a cost not known then, or silently rewrite historical published margin. Cost reductions require validation against the affected value; an inconsistent negative inventory value becomes a review exception, not a fabricated balance.

## 8. Planning, reports and documents

Optional min/max and lead-time settings generate reorder proposals. Show sellable quantity, reservations, quarantined units, confirmed outstanding receipts and projected demand separately. Expected receipts may inform planning but never increase today's sellable stock. Suggestions do not automatically place orders or commit spending; authorized users approve/export/share the purchase document through the chosen business workflow.

Provide reports for outstanding PO deliveries, receipt/quality discrepancies, goods received but unbilled, invoices awaiting receipt/matching, supplier balances and due dates, unapplied advances, unsettled claims, purchase-price variance, landed cost, low stock and expiry. The company head controls quantity-only versus cost/settlement visibility and report export. Every company aggregate exposes synchronization freshness.

Documents support supplier references, item/unit/lot detail, received versus accepted quantities, return reason, receiving/approving actors and attachments with scoped access. Retain original supplier documents/evidence under the configured retention policy. Attachments are bounded and retried independently; a nonessential photograph upload must not require internet before a durable local receipt. If policy requires evidence, capture it durably at the branch before posting the affected action.

## 9. New definition and command implications

Candidate records include Supplier, SupplierItem, PurchaseRequisition, PurchaseOrder/BranchAllocation, GoodsReceipt/Line, Inspection, ReceiptDiscrepancy, SupplierInvoice/ReceiptAllocation, SupplierSettlement/Allocation, SupplierAdvance, SupplierReturn, SupplierClaim, SupplierCreditAdjustment and LandedCostAdjustment. Some may be embedded structures. Add catalog UnitConversion, StockSerial and explicit custody/valuation disposition where required. Schema names are provisional; existing YAML remains unchanged.

Guarded command groups:

- `purchase.request/approve/allocate/amend/shortClose`: scope, approval/budget allocation and remaining commitment.
- `receiving.post/inspect/release/reject`: exact units, lot/serial, physical custody, approved limits and one stock effect.
- `supplierInvoice.record/match/dispute`: immutable supplier evidence, remaining receipt allocation, duplicate detection and authorized variance.
- `supplierSettlement.record/allocate/resolve`: actual tender outcome, posting-branch ownership, cash availability and one financial allocation.
- `supplierReturn.dispatch/resolve`: original receipt references, real available quantities and separate claim/credit/replacement outcomes.
- `stockCost.allocate/correct`: eligible sources, exact value conservation, unchanged quantities and traceable historical adjustments.

Every command uses the same branch authorization, version checks, durable operation ID, atomic result/audit/outbox and recovery discipline as checkout. Recovery coverage includes open purchases, inspections, invoice/receipt allocation, supplier payment uncertainty and claims. Generic CRUD must not bypass these transitions.

## 10. Research and design choices

Public documentation read on 21 October 2026:

| Source | Observed behavior | POS design consequence |
| --- | --- | --- |
| [ERPNext purchase order](https://docs.frappe.io/erpnext/purchase-order) | Purchase units, delivery dates, received/billed progress and separate receipt/invoice/payment creation | Preserve units and separate delivery, invoice and settlement status |
| [ERPNext purchase receipt](https://docs.frappe.io/erpnext/purchase-receipt) | Accepted/rejected quantities and locations, serial/batch data, required inspection, partial receipt and short closure | Real custody and quality workflow; only released accepted goods become sellable |
| [Odoo 19 vendor-bill control](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/purchase/manage_deals/control_bills.html) | Billing ordered versus received quantities and a three-way-matching exception indicator when bill details change | Match stocked goods to actual receipts; company-defined approvals explicitly guard exceptions |
| [ERPNext purchase return](https://docs.frappe.io/erpnext/purchase-return) | Returns reference a submitted receipt/invoice; physical and financial effects depend on document/configuration | Separate supplier return from credit/payment, with no duplicate stock updates |
| [ERPNext landed-cost guidance](https://docs.frappe.io/erpnext/landed-cost-voucher) | Additional acquisition costs link to stock receipt/invoice valuation | Allocate costs to eligible stocked receipts and retain traceable value adjustments |

The referenced products expose choices that this POS deliberately constrains. For example, the Odoo page describes an exception indicator rather than a universal hard payment block; our configured approval enforcement is an explicit design decision. ERPNext permits invoice-driven stock updates in some workflows; our receipt/invoice separation prevents both documents from moving the same goods twice. Source examples are not evidence that the planned local RPC engine already supports these behaviors.
