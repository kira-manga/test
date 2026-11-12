# POS operational specification

Revision 1.2, 21 October 2026. Companion to [architecture 3.2](POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md) and the [implementation handoff](POS_IMPLEMENTATION_PLAN.md). This document defines the practical first-release baseline requested by the user. It records their decisions and supplies defaults for the cash, shift and order workflows they explicitly delegated for design. The [company policies and catalog](POS_COMPANY_POLICIES_AND_CATALOG.md) and [purchasing specification](POS_PURCHASING_AND_RECEIVING_DESIGN.md) supply the latest permission, pricing, returns, product, receiving, onboarding and usability details. All company administration is inside the CMP app with separate central-management and local-branch authority paths. It is a specification, not an implemented or production-qualified system.

Existing YAML remains unchanged. The entity groups below prepare the new project YAML; they do not replace the handwritten local domain engine or central ingestion work.

## 1. Confirmed rules and design defaults

| Concern | Rule |
| --- | --- |
| Small branch | May start with one master and central synchronization, without an independent local standby. An optional standby is prepared before it is relied upon for recovery. |
| Price ownership | Company owns the base catalog and its price updates take priority over branch price changes. Branch changes remain scoped, versioned and permission-controlled. |
| Employee account | Belongs to exactly one branch. The same person needs another account to work in another branch. Changing admitted devices within the same branch does not change that account. |
| Cash custody | Each cashier has their own assigned drawer; no pooled drawer with several simultaneous custodians. Cash sessions and login sessions are separate. |
| Order access | The owner accesses and operates their order; another employee takes over through an explicit handoff. Specific manager/audit and kitchen/delivery projections remain permission-scoped. |
| Availability | No sale/fulfillment of stock-tracked goods beyond available sellable stock; no permission that simply bypasses this rule. |
| Food ingredients | Deduct when the selected order quantities are sent to the kitchen. Verified cancellation before preparation starts restores the original issued ingredients. Cancellation after preparation starts does not restore them. |
| Excluded from release one | Customer credit sales, customer monetary/store-credit balances and loyalty. Split tender and temporary unpaid dine-in checks are distinct operational concepts and are supported. |
| Activity coverage | Common core plus restaurant/cafe, clothing and supermarket workflows in the first release. Company configuration selects applicable capabilities. |
| Tax/fulfillment | Product price can be tax-inclusive or tax-exclusive. Support service and delivery charges, dine-in, takeaway and delivery. |

Operational defaults below are selected under the user's delegation, remain visible in company policy, and can be reviewed without changing the core accounting and integrity rules. The user has also selected returns/exchanges only at the selling branch in release one, fully usable without internet.

## 2. Accounts, ownership and everyday access

Persist branch-local account identity and credentials independently of device identity. Enforce branch scope from the authenticated account, not a branch ID supplied by the client. An optional staff directory link does not grant another branch's login or permissions.

Default interactive session policy: one active interactive session per account, with an explicit move-to-another-device flow. Preserve pending financial commands during the move. Separate narrow service accounts serve kitchen displays or unattended screens. Company policy may permit additional sessions within the same branch, but every operation still has one actor and an authoritative order/resource version.

Recommended local sign-in is an employee identifier and protected local credential, with an optional fast PIN workflow, persistent throttling and controlled reset. Exact sign-in presentation can be finalized with the UI; it does not change branch-local identity. Privileged approval requires the approving user's own local authentication and a grant bound to the action.

An order records original creator, current owner, every handoff and actual actors for edits, preparation, payment, cancellation and refund. Owner access is enforced in queries, direct-ID operations, subscriptions and caches. A kitchen account sees only the station's preparation projection; a delivery account sees only its assignment. These projections do not grant ordinary cashier access to another owner's order.

Handoff uses a durable request ID and expected order version. The current owner remains authoritative until the recipient accepts; acceptance changes ownership atomically. A manager can perform an audited reassignment if the owner is absent. Do not allow two accepted owners or change the actor on previously collected payments. Payment in progress must be resolved or transferred through an explicit supervised recovery step before ordinary ownership transfer.

Practical restaurant flow: a waiter owns the order, then hands it to a cashier for collection. Handing over an order never hands over cash already collected or the previous cashier's drawer.

Owner-private active orders do not prevent an authorized returns operator from looking up the limited immutable receipt details needed for a same-branch return. The return is a new document owned by the handling operator and linked to the original sale; it does not transfer or relabel the original order. General browsing of another owner's orders remains restricted.

## 3. Company prices, branch changes and open orders

Bind every branch price change to the company's current price revision for that product/variant and price context. A company update to that same price context supersedes branch changes based on older revisions. It does not reset unrelated products merely because another company configuration field changed.

Example: company price 100; branch changes it to 120 against revision 5; company publishes 150 at revision 6. Once the master activates revision 6, the effective price becomes 150. A disconnected branch's late revision-5 price change cannot overwrite revision 6 during synchronization. Keep the superseded change for audit.

After activating revision 6, a newly authorized branch change may reference revision 6 unless company policy disables branch changes. This allows the accepted branch-edit capability while ensuring a later company price release wins. Central price intent takes effect at the branch when the master receives and activates it, not magically while the branch is disconnected.

Company priority determines the effective catalog. The separately accepted open-order policy determines whether eligible existing lines keep their snapshots or are repriced. Default: preserve existing committed/quoted lines; new lines use the current effective price. Administration may enable recalculation for eligible unpaid and unlocked drafts/parked orders. Payment-locked amounts, posted documents and already committed customer quotes are not silently changed.

Record quantity/unit, recipe or variant, tax mode, tax rules, discount allocation, charge allocation, price revision and payable amount in the finalized snapshot. Never price a return from today's catalog.

## 4. Cash drawers, cashier sessions and business days

Use separate records for physical drawer/cash point, cashier account, cashier cash session, branch business day, cash movement and review. A drawer can be reused by a different cashier after a counted closure and new opening, but it has one custodian and one writable cash session at a time. A cashier has one active drawer assignment by default. A device change cannot move a physical drawer balance.

Each cash session has opening float, movement sequence/cut, tender summaries, actual closing count, expected closing cash, variance, closing disposition and review status. A branch business day aggregates sessions without collapsing their accountability. Use the branch timezone and a configurable operational cutoff for after-midnight restaurants; preserve actual timestamps and tax-document dates separately.

### 4.1 Opening and active work

1. Cashier signs in locally and selects their permitted physical cash point.
2. Verify that it has no competing active session or unresolved closing custody.
3. Count opening cash by denomination or explicit total, record its source and confirm custody. Opening float is not sales revenue.
4. Open the session with a unique ID and immutable opening record. A reused drawer's retained float is counted again by the incoming cashier.
5. Record every actual cash receipt, change, refund, authorized non-sale pay-in, paid-out and safe drop against this session.
6. Break/lock hides the UI and locks access; it does not close the cash session. Re-login resumes the same permitted assignment.

Payment recording, including external tenders, references the collecting cashier's session. Cashier and terminal summaries remain separate so external-terminal settlement can be reconciled without changing drawer cash.

### 4.2 Correct totals

```text
Expected closing cash
  = counted opening float
  + physical cash received from customers
  - change actually given
  - cash refunds actually paid
  + authorized non-sale cash pay-ins
  - authorized non-sale cash pay-outs
  - cash transferred to the safe

Cash variance = actual closing count - expected closing cash
```

If a cash movement stores the net amount after change, do not subtract change again. Keep received amount and change available for receipt/audit while posting one consistent signed cash effect. Card/wallet attestations never increase drawer cash. A refund paid today for yesterday's sale reduces today's paying drawer and references the original sale; it does not reopen yesterday's session.

Example in EGP: opening 1,000 + cash received 5,000 − change 400 − cash refunds 200 + pay-in 100 − paid-out 150 − safe drop 3,000 = expected 2,350. If counted cash is 2,360, overage is 10. Separately recorded card payments of 4,000 do not alter that expected cash.

Show these distinct report totals: item sales before discounts, discounts, net items, tax, service/delivery charges, paid sales, returns/credit adjustments, actual collections by tender, refunds by tender, outstanding operational checks/incidents, inventory cost/waste, opening float, cash transfers and variance. Collections can differ from sales because of partial payments, refunds from prior sessions and later recovery of an unpaid incident.

Safe drops and floats are internal custody transfers, not expenses/revenue. Each transfer has one ID, sending and receiving custody records, amount, actors and acknowledgement. Unacknowledged safe receipts remain a custody exception rather than disappearing from the reports.

Classify non-sale pay-outs by their actual purpose: an inventory purchase, operating expense and custody transfer have different accounting meanings despite all reducing drawer cash. Cash refunds/pay-outs require sufficient recorded cash and actual cash availability; otherwise keep the obligation pending and replenish through a recorded transfer or use a permitted alternative tender. Never fabricate a negative drawer or mark an unpaid refund paid.

### 4.3 Closing and review

Use Open → Closing → SubmittedClosed → Reviewed, with an explicit safe cancellation of Closing before submission. Do not reopen a submitted session for ordinary editing.

1. Establish a local closing cut and stop new tender/cash mutations for this drawer. Other drawers and permitted order work continue.
2. Resolve in-flight collection and unknown command outcomes. Hand off owned unpaid orders explicitly; they must not be silently marked paid or copied into the next cashier's sales.
3. Count cash, preferably with the expected total hidden until the count is submitted. Record recounts and reasons before final submission.
4. Compute expected cash and variance from the immutable ledger cut. Require a reason/review for differences; a difference is not automatically a debt owed by the employee.
5. Record the actual disposition: money delivered to the safe and float retained under custody. In the example, retaining 500 from an actual count of 2,360 means a closing safe transfer of 1,860; do not use expected cash to invent the transfer amount.
6. Submit an immutable closing report. Manager review can follow without blocking a new correctly counted session once custody and unresolved financial work are handled. Exception thresholds control review, not false balancing entries.
7. Record later explanations/corrections as linked adjustments and review events. Preserve the original close/count; never create a fictitious expense to hide a shortage.

Closing does not require internet or a bank settlement response. External-payment reconciliation has a separate status. End-of-business-day reporting records any deliberately carried open check or unresolved incident with owner and amount; it cannot silently erase it.

## 5. Order, preparation, fulfillment and settlement are separate

Do not squeeze every case into one status or infer payment from preparation/delivery.

| Dimension | Representative states |
| --- | --- |
| Ownership | Owned, HandoffPending, HandedOff; manager exception with audit |
| Commercial order | Draft, Confirmed, Open, Closing, Closed, Cancelled |
| Preparation per quantity/task | NotSent, Queued, Preparing, Ready, CancelRequested, CancelledBeforeStart, DiscardedAfterStart, RemakeRequired |
| Fulfillment per quantity | NotHandedOver, Served/PickedUp, OutForDelivery, Delivered, DeliveryFailed, Returned |
| Collection | Unpaid, CollectionLocked, PartPaid, Paid, OutcomeUnknown |
| Refund | None, RefundDue, PartRefunded, Refunded, RefundOutcomeUnknown |
| Exception | None, UnpaidFulfillmentIncident, QualityIssue, WrongItem, CustodyDispute, ApprovedWriteOff |

Track quantities, not just whole-order flags. If 2 of 5 items started preparation, cancellation can restore only the 3 verified unstarted portions. Keep stock disposition and customer charge disposition separate: food can be wasted while its charge is waived, or replaced while the original customer charge remains valid.

Every persistent transition validates current owner/role, expected version, quantity and prior state on the master. Use a stable application operation ID and transactional effects/outboxes. Repeated requests return the same result. The client never resolves a timeout by creating another payment or another kitchen issue.

## 6. Restaurant/cafe workflow and kitchen inventory

Support dine-in with tables/seat notes, takeaway with pickup references, and delivery with recipient/contact/address and assignment. Allow incremental ordering, modifiers, exclusions, required choices, item notes, course/hold-and-fire where enabled, kitchen-station routing, kitchen display/printing, ready/served transitions and explicit order handoff.

Default retail collection precedes release of goods; dine-in allows normal service before collection. Takeaway supports pay-before-pickup; delivery supports prepaid or controlled cash-on-delivery. These are fulfillment policies, not permission to falsely mark an unpaid order paid.

### 6.1 Sending to the kitchen

In one master transaction, validate selected quantities and modifier/recipe versions, check available sellable ingredients, deduct/issue the required ingredients, persist the kitchen tasks and issue identities, and enqueue delivery/printing. If any required component is unavailable, reject that send batch with the shortages; a smaller explicitly selected batch can be submitted separately.

Pin the recipe, yield, units, substitutions and ingredient lot/warehouse allocation used for each issued portion. Sending the same quantity twice cannot issue ingredients twice. New additions create new portion/task identities. Final checkout does not deduct ingredients again for portions already issued.

For a counter/prepaid order collected before kitchen send, reserve the required ingredients before collection. A financially paid but unsent portion keeps its reservation and an explicit pending-preparation state. Kitchen send converts that reservation into the ingredient issue once, counting its own reserved allocation correctly. Payment finalization does not deduct those ingredients early. Cancelling before send releases the reservation and resolves any refund; paid reservations do not expire silently while a kitchen/printing task is delayed.

### 6.2 Starting and cancelling

Kitchen start is an acknowledged master transition. Starting and confirming a before-start cancellation compete on the same version/quantities; only one succeeds. A stale kitchen screen cannot start a successfully cancelled portion.

For multi-station work, a portion is ready only after its required station tasks are ready. Starting any required task marks that portion as started for the cancellation rule. A before-start cancellation requires all required tasks to remain unstarted/confirmed cancelled; one station's stale status cannot restore the whole recipe while another station is preparing it.

If cancellation is verified before preparation starts, reverse the original ingredient issue for exactly that quantity using the original allocations and recipe snapshot. If physical stock has become unsafe/damaged in the meantime, route its return to quarantine/disposition instead of making it sellable automatically.

If preparation has started, cancellation keeps the ingredient deduction and records waste/loss or another approved disposition. Do not issue another negative stock movement merely to label the already issued ingredients as waste. Where costing tracks kitchen work-in-progress, reclassify its cost once into sold cost or waste according to the outcome.

For paper-only kitchens, a printed ticket does not prove that preparation has not begun. Before restoring ingredients, require kitchen confirmation or an authorized staff confirmation based on kitchen feedback. Unknown start/print outcome stays cancellation-pending with stock unavailable until resolved. Printer failure cannot roll back an already committed issue behind the kitchen's back.

### 6.3 Edits, wrong items and remakes

Edits to quantities/modifiers after sending create an explicit cancellation/replacement or additional task; do not mutate the issued recipe history. New work checks stock again.

Default billing: merchant preparation/picking error is corrected without charging the customer twice. A same-product remake links to the original paid/charged line and has its own production issue and cost, but does not double the sold quantity/revenue. A different-product substitution or customer-requested change creates linked adjustment/new-line records and any payable/refundable difference.

For a customer-requested change before start, reverse eligible original ingredients and charge the agreed new item amount. After start, manager policy determines whether the original charge is retained or waived; record the reason and the wasted cost separately. Paid amounts require a refund/adjustment workflow rather than silently reducing a paid total.

## 7. Required exception handling

| Case | Operational action | Money and stock outcome |
| --- | --- | --- |
| Wrong order entered, not sent | Owner corrects draft with audit where required | No ingredient issue; recompute the draft |
| Sent but verified not started | Request/confirm cancellation for specific quantities | Reverse the original issue; release/refund charges as applicable |
| Some quantities started | Split the cancellation by verified quantity | Restore unstarted portions only; started portions retain cost |
| Customer cancels after preparation starts | Approved cancellation/charge decision | Keep ingredients deducted; record waste; handle any refund separately |
| Correctly ordered item prepared incorrectly | Record quality/preparation error and remake | New ingredients must be available; original waste recorded; no duplicate customer charge |
| Wrong product served | Record delivered error, stop further wrong work and create correction/substitution | Served food never automatically returns to ingredients; charge/refund only the approved difference |
| Customer changes their mind after ready | Record request, charge decision and new work | Original cost retained; replacement checks stock; waiver requires permission |
| Customer consumed/collected and left unpaid | Create unpaid-fulfillment incident with owner, quantities and outstanding amount | No fictional cash receipt, no inventory restoration and no automatic cashier shortage |
| Customer paid part and left | Preserve all payment records; incident covers only the remaining amount | Actual cash stays in its collecting session; no customer credit wallet is created |
| Customer later pays an incident | Authorized linked collection against the original obligation | Receipt in today's collecting session; no second sale or second stock deduction |
| Management writes off an unpaid incident | Separate approved write-off with reason and fiscal/accounting treatment | No cash movement; retain original history and fulfilled cost |
| Free meal/complimentary replacement | Authorized discount/compensation or linked remake | Inventory and tax treatment still apply; zero payable is not zero cost |
| Stock is unavailable for a replacement | Offer an available authorized substitute, wait or refund | Do not create negative stock to complete the replacement |
| Delivery fails or is refused | Record failed fulfillment, goods condition, fee decision and collector custody | Packaged goods require inspection; prepared food is not automatically restocked; refunds remain explicit |
| Prepared takeaway is never collected | Record abandoned pickup, contact/expiry decision and actual food disposition | Do not claim the customer received it; keep preparation cost and resolve any prepaid refund/charge under policy |
| Staff accidentally creates a second independent order | Link the duplicate to the intended order and explicitly cancel its work/charges | Actual preparation and payments are reconciled separately; do not erase already consumed stock or a second external payment |
| Cash handed over but POS reply lost | Lookup/recover the original operation and collection evidence | Never request another charge merely because the reply timed out |
| Cashier recorded the wrong tender | Manager posts linked classification/custody correction | No invented second collection; original record and closing report stay auditable |
| A previously recorded external payment is later disputed/reversed | Record evidence and a linked payment-dispute/reconciliation incident | Preserve the original cashier attestation and fulfilled stock; record actual subsequent money movement rather than undoing the sale silently |

An unpaid incident is an exception to resolve, not an enabled credit-sale product or a spendable customer account. Preserve bill/line snapshots, actual collections and the remaining amount. Separate paid-sale finalization from exception closure and from any required fiscal document issuance. Statutory revenue/tax recognition for supplied-but-unpaid goods must be mapped to the applicable business rules; do not assume that absence of payment makes the supply disappear.

Actual goods released before collection must have a corresponding stock/fulfillment event even though no payment exists. If kitchen/retail issue was already recorded, do not deduct it again. An intentional exceptional retail release still checks available stock and records its authorized issue; a subsequently discovered physical loss uses an audited incident/count adjustment rather than inventing a paid sale or enabling future negative-stock sales.

## 8. Retail, stock availability, returns and exchanges

Stock-tracked retail items reserve their selected warehouse/lot quantities atomically when collection begins, before asking the cashier to take payment. Finalization consumes the reservation once. Draft/parked carts do not promise stock by default. A reservation covering an unknown/part-completed external collection is not silently expired and resold; resolve the collection first.

Available sellable quantity excludes existing reservations, blocked/quarantined and expired stock. Validate full requested quantities and all recipe/kit components, not merely whether the total is greater than zero. Services and charges have an explicit non-stock type; they are not a workaround for selling unavailable goods. No negative-stock override is provided.

Support variants, size/color, units/packs, multiple barcodes, weighted quantities, lot/expiry where relevant, warehouse allocation and exact unit conversion. Keep barcode values as identifiers with leading zeros. Scale-encoded weight/price must follow a configured validated format and the authoritative pricing rule; arbitrary scanned amounts cannot override the master price.

### 8.1 Returns

Apply the category/reason policies in the company policy specification. The researched Egypt baseline distinguishes eligible 14-day change-of-mind returns from 30-day defective-goods remedies, measured from customer receipt, with applicable exceptions and other continuing rights. Record claims/deadlines even if inspection, approval or actual payment remains pending. A company may offer more favorable commercial terms; a configured action limit cannot erase a statutory remedy. Original sale, proof and unused entitlement still need verification.

Reference original sale lines, original tax/discount/charge allocation and remaining returnable quantity/amount. Accept partial returns and repeated partial returns only within the remaining entitlement. The originating branch serializes the checks with the return posting.

Keep the original receipt snapshots and cumulative return/refund entitlement locally for the full configured return window. Ordinary same-branch returns must not require a central lookup. A receipt unavailable after incomplete disaster recovery is an explicit recovery/evidence exception, not permission to invent its remaining entitlement. Accepting a return consumes/reserves its entitlement even while the resulting refund is pending, so a second operator cannot return the same quantity again.

Inspect disposition separately: sellable restock, quarantine, damaged/waste or supplier return. Returned clothing or sealed goods can become sellable only after the configured inspection; prepared/served food does not restore recipe ingredients. A refunded item is not automatically a restocked item.

Cash refund changes the paying cashier's current drawer when actually paid. External refunds are performed outside POS and recorded by authorized staff. Refund due is a liability to resolve, not a customer store-credit balance; do not turn an unavailable refund into spendable credit.

Default refund allocation follows the original available tender allocations, including earlier partial refunds. A different permitted method requires explicit authorization and an audit reason. A return amount is not proof that an external refund succeeded; record its actual cashier-confirmed outcome.

Require receipt lookup by default. Exceptional receiptless returns need a separate limited manager workflow with product, evidence, reason, valuation and fraud limits; keep disabled until company policy explicitly enables it. This setting does not bypass stock inspection or permit refunding an unknown card charge through the POS.

### 8.2 Exchanges

Link a return to a replacement sale, preserving their own prices, taxes, stock effects and the difference to collect/refund. Reserve the replacement stock before promising the exchange. If a replacement cannot proceed, retain a truthful accepted-return/refund-due state rather than losing the returned goods or manufacturing a completed exchange.

Same-price exchanges still produce linked documents and stock movements. More expensive replacements collect the difference; cheaper replacements refund the difference according to actual tender outcomes. A failed/unknown external refund or collection leaves an explicit pending settlement with stable operation IDs.

Use a restricted exchange-offset allocation between the linked return and replacement documents. A returned value of 100 and new price of 130 require 30 of actual collection; a new price of 80 requires 20 of actual refund. The offset is not a separate cash receipt/payment or a spendable customer balance. It cannot be reused outside that exchange, paid out twice or silently abandoned without resolving the accepted return's refund obligation.

### 8.3 Cross-branch return boundary

Release one accepts returns and exchanges only in the selling branch, including offline. Another branch of the same company cannot consume that receipt's return entitlement, and another company cannot do so merely because it shares the organization. Show the selling branch to the operator and direct the customer there. Enforce this rule on the master and central write paths, not only in the UI.

Cross-branch return redemption is excluded from release one. Adding it later requires a coherent entitlement-allocation/coordination design across all redeeming branches, including the origin; a central check at a receiving branch alone is insufficient while the origin independently redeems the same entitlement offline.

## 9. Payments, delivery collection and monetary corrections

Support cash, externally performed card/wallet/other configured tenders, split tender and partial collection of an active check. Require paid coverage for ordinary paid-sale finalization. Change is permitted only from actual cash under the configured rule; never invent card change. Do not provide new credit sales, customer balances or loyalty in release one.

Lock the payable version before collection. Preserve payer/collector, amount, tender, terminal/reference where supplied, cashier attestation and time. An external reference may help find a duplicate but is not proof of bank approval. Reference requirements can be configured per tender; do not require a bank API response.

For dine-in service before collection and delivery COD, retain the open obligation until actual collection or an approved exception resolution. Default takeaway/retail requires collection before handover; a supervised exceptional release records the unpaid condition explicitly.

Delivery collection uses an identified collector custody record. A courier has a limited assignment account, not permission to operate another cashier's drawer. Record cash received, change and later remittance. Receiving courier cash into a drawer/safe is a custody transfer, not a second sale. Mark delivery status, customer collection and collector remittance separately. If courier collection is not enabled, require prepaid delivery or explicit cashier collection; never mark COD paid solely because it was delivered.

Service charges, delivery charges and voluntarily recorded tips are separate lines/categories. Tips, where enabled, have separate custody/accounting treatment and are not silently added to sales revenue or employee entitlement. No direct payment-provider integration is required by this scope.

Corrections after a closed shift append linked records. A correction of a tender originally misclassified as card may explain an old cash overage; it must not create a new physical cash receipt today when no new money moved. Show original close, subsequent adjustments and reconciled explanations distinctly.

## 10. Tax-inclusive/exclusive prices, discounts and charges

Provide the requested tax-included checkbox in product/price administration, with a product default and an explicit effective value on the sold variant/price. Persist tax inclusion separately from tax category/rate. A checkbox changes price interpretation; it does not determine the legally applicable rate or exemption.

Support mixed inclusive/exclusive lines in one order. Use exact decimal/scaled calculations, bounded integer minor units for posted money and deterministic rounding/allocation. Store the calculated net, tax and gross snapshot so every device and central report agrees.

Illustrative arithmetic using a hypothetical 14% rate: an inclusive price of 114 means net 100 plus tax 14; an exclusive price of 100 means payable 114. Together, those lines total 228, not 242. This example does not select a tax rate for all Egyptian products.

Support permission-limited fixed/percentage line and order discounts, and basic company promotions with deterministic eligibility/stacking. Allocate order discounts to affected lines once and preserve allocations for partial returns. A fixed discount presented as a reduction of the customer's payable amount must actually reduce that payable amount by the advertised value, subject to a nonnegative total.

The company policy specification defines quantity/bundle/buy-X-get-Y campaigns, explicit exclusive/compatible groups, default no-stacking per unit, exact refund allocations and offline redemption limits. Supplier cost changes never directly publish new selling prices. Global single-use campaigns require allocation/coordination rather than an unsupported offline uniqueness promise.

Service-charge rules define eligible channel/items, fixed or percentage amount, calculation base and tax treatment. Default percentage base is eligible item net after discounts, excluding tax, delivery, tips and the service charge itself. Delivery uses an explicit amount/zone rule and tax category. Takeaway has no hidden delivery charge. Packaging charges, if enabled, are explicit charge lines.

Show item subtotal, discounts, applicable charges, tax and final payable without counting tax-inclusive tax a second time. Refunding service/delivery charges follows recorded performance and reason: cancelled unperformed delivery is eligible for reversal; a performed delivery fee is not automatically reversed on every partial item return. Tax adjustments use the original applicable snapshots and required fiscal rules.

Receipt content includes company/branch identity, unique document number, original operator/current collector as applicable, items/variants/modifiers, quantities, discounts, net/tax/charges, tenders/change, fulfillment type and links for returns/exchanges. Distinguish an unpaid check, paid receipt, refund and reprint. Fiscal submission/acceptance status is separate and visible.

Egypt integration scope still needs the actual companies' registration, document types, product/tax classifications and required invoice/e-receipt credentials/workflows. Implement the applicable connector and outage/submission policy before approving an affected company's production launch; the inclusive-price checkbox is not the complete fiscal integration.

## 11. First-release feature baseline

Features are enabled by company/activity policy; the shared core and access enforcement apply to all four activities.

| Area | First-release baseline |
| --- | --- |
| Organization/company/branch | Different company activities, scoped administration, catalog and price releases, branch status and authorized consolidated reports |
| People/devices | Branch-local users, default/custom roles, action approvals, device admission, master operation/recovery and audit |
| Cashier | Search/scan, variant/modifier selection, quantity editing, owner-private parked orders, explicit handoff, discounts, split tender, receipts/reprints and recovery of unknown outcomes |
| Restaurant/cafe | Table layout/status, dine-in/takeaway/delivery, incremental ordering, modifiers/notes, kitchen stations and queued/start/ready/served quantities, cancellations/remakes/waste, partial collection and split/merge bills |
| Clothing | Size/color variants, per-variant stock/barcodes, labels, partial returns, inspected disposition and linked exchanges with price differences |
| Supermarket | Fast scanning, multiple barcodes, exact weighted/unit/pack quantities, scale integration, batches/expiry and sellable availability, basic promotions, item/bill discounts and partial returns |
| Inventory | Branch warehouses/locations, opening balances, supplier/item references, receiving and purchase/return documents, transfers, counts, adjustments with reasons, recipe versions/yields, waste, low-stock and expiry reports |
| Purchasing | Quick receiving or requisition/PO, partial/excess/rejected goods, inspection, invoice matching, supplier payables/advances, actual settlement, supplier return/credit, landed cost and permitted company reports |
| Broad catalog | Composable variants, exact measures/packs, lot/serial tracking, ingredients, made-to-order recipes, finished batches, virtual/assembled kits, services, attributes and optional receipt-linked packaging deposits |
| Cash operations | Individual drawers, opening floats, breaks, pay-ins/pay-outs, safe drops, counted closing, variance review and business-day summaries |
| Customer/fulfillment | Optional receipt/contact details, required delivery details, pickup/delivery references, limited collector custody, failed deliveries and exception resolution |
| Tax/pricing | Company-priority prices, branch edits, inclusive/exclusive interpretation, effective tax rules, service/delivery/packaging charges, recorded discounts and fiscal integration for applicable launch businesses |
| Reporting | Sales and collections separately, tender/terminal totals, cashier/shift/day closing, taxes/charges/discounts, returns/exchanges, kitchen waste/remakes, stock/valuation/expiry, incidents and synchronization freshness |
| Everyday usability | Arabic/English, RTL, touch/keyboard/barcode flows, clear permission failures, large operational controls, bounded search/pagination, visible unsent/unknown/printing states and accessible labels |
| Administration/support | Import preview/validation, export by permission, activity settings, peripheral assignment, backup/recovery status, diagnostics with secrets excluded, version compatibility and controlled software updates |
| Setup and policy control | Resumable company/branch wizard, isolated training, opening stock/float, company-head-controlled limits and report/export/alert scope, explicit defaults and offline activation status |
| Native company administration | Complete CMP company-head screens for structure/people/catalog/prices/policies/purchasing/reports/devices; central principal isolated from branch-local cashier sessions; no required web portal |

Bill splitting/merging is restricted to the same branch and compatible price/tax/fulfillment context. Validate both owners or an explicit handoff/manager grant, preserve preparation/stock identities and allocate charges/payments exactly. Already collected amounts cannot be duplicated across children; a failed split leaves the original consistent.

Inventory receiving/counting never directly overwrites a ledger balance. Record movements with origin IDs and approval where required. A count uses a captured movement cut and accounts for subsequent movements or requires recount; do not ignore reservations or double-apply receipts. Inter-branch transfers use dispatch → in-transit → receipt/shortage records bound to source/destination, with destination deduplication and verified transfer evidence. They do not pretend both branch databases share one transaction.

Default operational valuation uses a versioned moving weighted-average cost per stocked product/variant and warehouse, with exact base-unit quantities. Lot/expiry picking remains explicit and can follow first-expiry-first-out independently of valuation. Capture issue/sale cost at the operation; a later purchase must not rewrite historical margin. Before-start issue reversal and inspected saleable returns reference their original quantity/value, while after-start waste reclassifies the already issued cost once. Statutory accounting export uses the company's validated account/tax mappings.

Receiving, invoicing and paying are distinct documents/effects even when entered in one convenient screen. Supplier invoice/credit posting does not move the same stock again. Supplier returns use actual available quantities and record the supplier claim separately. Late acquisition costs create explicit value adjustments for remaining/consumed goods while preserving original historical snapshots; the purchasing specification defines the examples and approval boundaries.

Finished batches/assembled kits consume components during production and create their own stocked output; selling the output consumes that SKU once. Virtual kits check/consume their actual components. Optional refundable packaging deposits use receipt-linked entitlements, separate liability/tax treatment and actual refund records; they cannot become an unrestricted customer balance.

Excluded first-release capabilities: customer credit sales, customer/store-credit balances and loyalty, as explicitly requested. Full statutory general ledger, payroll, unrestricted ecommerce/marketplace connectors and direct bank authorization are separate integrations/products rather than implied guarantees of the operational POS. Core source documents, cost/cash subledgers and accounting export must remain complete and traceable.

## 12. Domain records and command boundaries for the new YAML

Names are working names. Keep central public definitions separate from local credentials, sessions and protected recovery storage. Every local financial/stock command commits its records, operation result, audit and outboxes together.

| Group | Candidate records |
| --- | --- |
| Company/branch policy | CompanyPosProfile, CompanyCatalog, ActivityPolicy, BranchProfile, BusinessDay, ConfigurationRelease |
| Branch accounts and ownership | BranchUser, local credential/session structures, Role/Permission, ActionApproval, OrderOwnershipTransfer |
| Catalog/pricing | Product, Variant, Barcode, Unit/UnitConversion, BranchAssortment, Price, CompanyPriceRevision, BranchPriceOverride, TaxPolicy, ChargeRule, Promotion |
| Catalog/policy extensions | ProductCapability, StockSerial, KitDefinition, ProductionBatch, PromotionAllocation, ReturnPolicy, Deposit/ReturnEntitlement, ReportGrant, AlertRule, BranchSetupRecord |
| Orders | Order, OrderLine/Portion, PriceSnapshot, CollectionLock, Fulfillment, Table, DeliveryAssignment |
| Kitchen/cost | RecipeVersion, RecipeComponent, KitchenTask, PreparationEvent, IngredientIssue, IssueReversal, WasteRecord, RemakeLink |
| Stock | Warehouse, StockLot, StockMovement, StockReservation, GoodsReceipt, PurchaseDocument, SupplierReturn, StockCount, StockAdjustment, StockTransfer/Receipt |
| Purchasing | Supplier/ItemReference, PurchaseRequisition, PurchaseOrder/BranchAllocation, Inspection, ReceiptDiscrepancy, SupplierInvoice/ReceiptAllocation, SupplierSettlement/Allocation, SupplierAdvance, SupplierClaim, SupplierCreditAdjustment, LandedCostAdjustment |
| Sales/refunds | Sale/LineSnapshot, PaymentRecord, ExternalPaymentAttestation, Return/ReturnLine, Refund/Allocation, Exchange, MonetaryAdjustment |
| Cash custody | CashDrawer, CashSession, CashMovement, CashCount, CashTransfer, SafeReceipt, CashCloseReport, CashReview, CollectorCustody |
| Exceptions | UnpaidFulfillmentIncident, IncidentCollection, ApprovedWriteOff, QualityIncident, FulfillmentException, PaymentDispute, CorrectionRecord |
| Documents/integration | ReceiptDocument, FiscalDocument/Submission, PrintJob, AccountingExportBatch |
| Reliability | OperationRecord, BranchJournalEntry, SyncInbox/Outbox, RecoverySnapshot, ReplicationCheckpoint, MasterAssignment/Handover and RecoveryDelegation |

| Command family | Essential guards/effects |
| --- | --- |
| cash.open / close / review | Exclusive cashier/drawer assignment; opening count; closing cut; immutable cash ledger and submitted report |
| cash.move / transfer / receive | Actual custody event; signed amount; reason/approval; paired identity without double-counting |
| order.create / edit / park / handoff | Branch/account scope; owner/version checks; exact acceptance of handoff |
| kitchen.send / start / cancel / confirm | Ingredient availability; issued recipe/quantity snapshot; start-cancel serialization; reversal versus waste |
| order.split / merge / replace | Compatible scopes; authorized owners; preserved preparation/payment/stock identity and totals |
| payment.begin / record / resolve | Payable version lock; preserved attestation; correct collecting session; recover unknown outcome |
| sale.finalize | Required paid coverage or authorized zero-payable document; correct stock consumption without double-deducting kitchen issues |
| order.reportUnpaid / incident.collect / writeOff | Actual fulfillment and paid amounts; remaining obligation; permission/reason; no fictional cash or second sale |
| return.accept / refund.record / exchange | Remaining entitlement; original snapshots; inspected stock disposition; actual monetary settlement |
| stock.receive / count / adjust / transfer | Quantity/unit/lot validity; reservations and ledger cut; source/destination evidence; no negative stock bypass |
| purchase / receiving / supplierInvoice / supplierSettlement / supplierReturn | Branch allocations/approvals; actual inspected receipt; remaining billable quantities; one actual payment and separate credit/replacement; see purchasing command specification |
| catalog.configure / production.post / deposit.return | Valid capability/unit combinations; immutable tracking history; components versus stocked output; receipt-linked remaining deposit entitlement |
| policy.publish / report.query / export / alert.subscribe | Company-head delegation; server-side action/field/branch scope; bounded approval; activated version and protected caches |
| price.applyCompany / setBranch | Company revision priority; branch scope; effective-date/order-price policy; immutable history |
| document.issue / submit / correct | Document type/numbering and original references; idempotent integration; truthful submission state |

Protect these records from unrestricted generic CRUD, including bulk deletion, balance resets and cross-company references. Generated forms do not substitute for command guards.

## 13. Acceptance and remaining deployment facts

The [acceptance scenarios](POS_ACCEPTANCE_SCENARIOS.md) map ordinary work, business exceptions, concurrency and recovery to observable outcomes. Implement and execute them; this document does not mark them passed.

Remaining external facts are the launch companies' fiscal requirements, hardware/peripheral models, realistic workloads and any explicitly required third-party integration. Sign-in presentation and routine policy defaults can be tuned without reopening branch account ownership, same-branch returns or the delegated cash/shift design.

Release readiness requires the implemented domain rules, real local/central integration, recovery rehearsals and actual Windows/macOS/Android/iPhone/iPad device/lifecycle verification. New real-world cases found in a pilot extend the scenario register and receive explicit state/money/stock handling; no document alone proves that every possible future case is covered.
