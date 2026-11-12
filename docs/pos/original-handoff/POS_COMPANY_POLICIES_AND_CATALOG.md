# POS company policies, catalog and everyday experience

Revision 1.1, 21 October 2026. Incorporates the user's company-controlled permissions/report access, delegated pricing/returns/product/purchasing/usability design and approved setup wizard. All company administration is now explicitly inside the CMP app, with central management and branch-local authority separated. Companion to [architecture 3.2](POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md), the [implementation handoff](POS_IMPLEMENTATION_PLAN.md), the [operational specification](POS_OPERATIONAL_SPECIFICATION.md) and the [purchasing design](POS_PURCHASING_AND_RECEIVING_DESIGN.md). These are specified behaviors, not implemented features.

## 1. Company head, delegation and configurable limits

The company head controls that company's permission templates, named delegations, action limits, approval rules, report visibility and exports. Organization membership does not automatically grant access to every company's operating data. A central administration identity does not become a shared multi-branch cashier account: anyone performing branch work uses a separate enabled account for that branch.

Company configuration defines, per role/action and permitted branch scope:

- Whether the user may perform the action, request approval, approve another person's request or administer the setting.
- Amount and percentage ceilings, eligible product/category/channel, reasons, and whether supporting evidence is required. If both amount and percentage ceilings exist, enforce both.
- Ordinary versus exceptional cases, such as a policy-compliant return versus a receiptless claim, or an agreed promotion versus a manual price reduction.
- Whether a branch administrator may set stricter limits or receive a specific exception bounded by company policy.
- Whether separate requestor and approver are required. Where a one-person branch needs self-approval, the company head explicitly grants it to a named branch account for specific actions; no implicit universal bypass.

Use editable role templates. Cashiers can sell at effective prices/promotions and operate their own drawer by default. Manual discounts, cost visibility, exceptional refunds, cash write-offs, stock corrections and policy administration require explicit grants. Managers receive operational approval capabilities only within the company's configured scope. Master replacement still requires the separate named recovery delegation already agreed.

Numeric limits are company settings, not constants embedded in the application. The setup wizard makes any missing limit visible; it never interprets an omitted limit as unlimited authority. A company head can explicitly select no monetary ceiling for an authorized action. Routine checkout does not depend on completing unrelated optional approval settings.

Approvals bind the requestor, approver, company/branch, action, document version, relevant lines/amount and reason. Changes beyond the approved values invalidate the grant. Capture the approval within the cashier's flow without replacing the cashier's identity or cash custody. Prevent self-elevation and policy changes that grant the author more authority than they were delegated.

The master enforces the currently activated version on every command and protected query. Tokens carry identity and policy-version references, not permanent authority. Updated policy affects ongoing sessions when activated; changing a role does not relabel earlier sales. Central changes to a disconnected branch remain visibly pending until that master receives them. Offline approvals require an already provisioned, authorized local approver.

Configuration cannot authorize negative sellable stock, cross-company references, cross-branch employee accounts, release-one cross-branch returns, rewriting posted records, independent client finalization or excluded customer credit/balance/loyalty workflows.

## 2. Reports, exports and operational alerts

The company head grants each report by data scope and sensitivity. Separate own drawer/orders, one branch, selected branches and consolidated company access; separate revenue/tender data from purchase costs, margins, supplier balances, employee discrepancies and customer contact data. Reading a report does not automatically grant exporting, bulk extraction, editing or receiving its sensitive alerts.

Default cashier visibility covers their assigned drawer, current session, own permitted sales and actionable order exceptions. Managers see the operational branch reports delegated to them. Inventory staff see quantity/expiry/receiving reports; purchasing costs require another grant. Accountants/auditors receive company-selected financial scope. Reports and subscriptions are filtered on the master/central server, not only hidden in menus. Historical receipt lookup for an authorized return exposes only the necessary receipt projection.

Essential report families include sales versus collections; cash sessions and custody; external tender confirmations; tax/charges/discounts; returns/refund obligations; unpaid fulfillment; kitchen timing/waste/remakes; stock availability/valuation/expiry; receiving/purchasing/supplier settlement; and master/synchronization/recovery health. Show branch synchronization freshness and incomplete company totals explicitly.

Alerts have configured recipients, severity, thresholds, quiet hours where appropriate, deduplication, acknowledgement and resolution. Include low stock, approaching expiry, overdue preparation, uncollected delivery money, unresolved payment/refund, cash variance, purchasing mismatch and unavailable master/low storage. Ordinary local alerts work without internet; remote company notifications wait for delivery. Lock-screen previews omit sensitive totals by default. An acknowledgement does not resolve the underlying obligation or move money. Recheck access when generating, opening or exporting a report/alert; revoke cached sensitive views when updated permissions activate. An already delivered export cannot be remotely made unreadable by merely changing a role.

## 3. Price selection and company priority

Use EGP as the initial operating currency. Each company defines its price contexts, including a standard price and optional dine-in, takeaway, delivery or quantity-tier rules. Defaults use the same price across channels; enabling a channel price must be explicit. Prices retain their tax-inclusion setting and tax category independently.

The master selects an eligible context by channel, quantity, effective period, product/variant specificity and explicit rule priority, with a stable rule-ID tie-breaker. Administrative preview explains the resulting customer price and competing rules before activation. Incompatible overlapping rules are flagged rather than relying on database retrieval order.

A branch override references the exact relevant company product/variant/price-context revision. A new company revision invalidates an older branch override for that context. The already accepted example remains: company 100 at revision 5 → branch 120 based on revision 5 → company 150 at revision 6 produces an effective price of 150 when revision 6 activates. A late offline revision-5 edit cannot restore 120. Unrelated product changes do not erase valid overrides elsewhere. A new authorized branch change may bind to revision 6 if company policy allows it.

Changing a supplier purchase cost never changes the selling price automatically. Administration may preview a suggested selling-price change, then publish it through the same company price-release process.

Existing open-order policy remains independent: preserve protected/collected/collection-locked/customer-committed snapshots; optionally reprice eligible uncommitted lines when administration enables it. New lines use the current effective rules. Freeze the exact quoted amount when entering collection; show any authorized repricing before the customer is asked to pay.

## 4. Promotions and manual discounts

Support fixed/percentage item or bill discounts, quantity tiers, fixed-price bundles, buy-X-get-Y offers, category/product rules, channel/date/time conditions and simple campaign codes. Each rule specifies included/excluded SKUs, minimum quantity or spend, cap, eligibility, priority and compatibility group. No loyalty balance or customer-credit capability is implied.

Default: one automatic promotion can consume a given unit; overlapping alternatives in the same exclusive group choose the greatest customer reduction under the declared rules, with priority and rule ID resolving ties. Do not promise a global optimizer across arbitrary combinations. Applying an additional order discount or manual override to already promoted units requires explicit stacking configuration and the appropriate permission. The configuration preview shows the exact calculation order: effective base price, permitted item/bundle adjustments, permitted order discount, then the configured charge/tax calculations. Manual entry is not an automatic way to evade a company promotion cap.

Never silently award more discounted units than stock availability or the basket permits. A campaign may have per-basket or per-branch redemption limits enforced by its master. A globally single-use code or company-wide hard redemption cap across disconnected branches requires preallocated branch entitlements or online coordination; it is not promised by an ordinary offline campaign code. Release one defaults to campaigns without such a global redemption promise.

Allocate every discount to concrete sold quantities using exact decimal calculations and deterministic minor-unit remainder allocation. All allocations sum to the advertised reduction and cannot make an eligible line negative. For mixed inclusive/exclusive taxes, calculate the customer's advertised payable reduction consistently with the operational tax rules.

For a two-item promotion with equal original values and a combined payable price of 100, allocate 50 to each unit for refund purposes. Display the allocated paid value and return terms on the receipt; do not display an item as having no refundable value while internally promising 50. A partial return normally refunds that unit's original allocated paid amount without recalculating the retained item at today's price. A legitimate all-or-nothing bundle return condition must be disclosed and cannot remove applicable defective-goods rights. Historical campaign changes never recalculate original entitlements.

## 5. Returns, complaints and customer remedies

Release-one posting remains restricted to the selling branch and works entirely from its local receipt snapshots and remaining entitlement. Company support can record/route a complaint without creating a second branch's return entitlement. This technical redemption scope is not presented as a limitation on the customer's legal rights.

Use separate reasons and policies for change of mind, wrong supplied item, defective/damaged item, merchant preparation error, quality complaint, failed delivery and unperformed service. Record receipt, actual customer receipt/delivery date, claimed date, quantity, condition, inspection, requested remedy, evidence, responsibility and resolution. Do not measure a delivered product's return period from a substantially earlier order-creation date. Preserve timezone and policy/version used in the eligibility decision.

The Egyptian Consumer Protection Agency's published FAQ describes a 14-day change-of-mind period from receipt, with listed exceptions, and a 30-day defective-goods return/exchange period. It also describes resolving the covered defective-goods request within a week and returning payment by the purchase method. Use this as the initial Egypt policy baseline for applicable goods, with category-specific exceptions and case deadlines; do not encode every product or service as having identical eligibility. Source and access date are recorded below.

Examples of required policy distinctions:

| Product/case | Default handling |
| --- | --- |
| Ordinary clothing or suitable packaged retail goods, unchanged condition | Change-of-mind flow under the applicable period and inspection rules; company may offer a longer commercial period |
| Perishable/prepared food | No blanket ordinary change-of-mind restock; quality, wrong-item and merchant-error remedies remain available and do not restore consumed ingredients |
| Customer-specified goods correctly supplied | Apply the applicable change-of-mind exception; a defect or failure to match agreed specifications uses the separate remedy path |
| Opened hygiene-sensitive goods | Apply the verified category/packaging rule, not a blanket ban on all clothing or every opened package |
| Defect or merchant fault | Record the claim immediately, inspect and provide the applicable refund/replacement/remedy; an ordinary discount/return limit cannot erase the claim |
| Complaint after the ordinary return window | Route to warranty/service/other applicable rights or an authorized commercial exception; do not assume all rights end on day 30 |

Company settings may extend periods, specify inspection and grant commercial exceptions. They may not silently shorten the applicable legal baseline. Preserve the policy promised at sale and applicable later customer rights. Record a claim even when approval or a physical refund must be completed later; track its deadline and unresolved obligation.

Receipt/serial/evidence retention covers the longest applicable supported return, warranty and financial-record requirement, and retains unresolved claims until resolved under the retention policy. Expiry of an ordinary commercial return window is not a reason to delete evidence needed by an open defect claim. Keep the local data needed for the promised offline remedies available or in a locally accessible protected archive.

Receipt lookup is the default. If proof is missing, provide the separately permitted evidence/receipt-recovery workflow already specified; an invoice photograph, order reference or other accepted evidence does not by itself establish unused refund entitlement. Validate the underlying transaction. Protect against repeated claims and returns while a refund is pending.

Use original price, tax and discount allocations. Default refund uses the original available tender allocation; another method needs an allowed, documented resolution consistent with applicable rules, not merely a manager override. Actual cash/external refund evidence remains distinct from accepting the return. Returned stock moves to sellable stock only after required inspection; otherwise quarantine, supplier return or waste. Refunds create no spendable customer balance.

## 6. Composable product catalog

Provide simple business-oriented templates backed by independent product capabilities. A product is not limited to one exclusive label such as “clothes” or “food.” A yogurt may be stocked, sold by pack, tracked by lot/expiry and offered in a bundle simultaneously.

| Template/capability | Example | Required behavior |
| --- | --- | --- |
| Simple stocked SKU | Bottle, packaged snack | Unit stock, price/tax, one or more unambiguous barcodes |
| Variants | Shirt size/color, coffee size | Explicit valid combinations, independent SKU/barcode/stock where stocked; shared product defaults |
| Measured quantity | Cheese by kg, fabric by meter | Exact decimal quantity, allowed precision, base unit and compatible conversions; configured scale/manual entry |
| Pack/carton/unit | Box of 12 bottles | Product-specific conversion snapshot, pack barcode and selling/purchase UOM; exactly 12 base units per configured box |
| Lot and expiry | Dairy or perishable ingredients | Receipt lot, expiry, quarantine/recall and FEFO selection; expired/blocked quantities unavailable |
| Serial tracking | Individually numbered equipment | Whole-unit quantity, unique identity, purchase/sale/return trace; verify the returned serial |
| Ingredients/materials | Flour or cooking oil | Purchasable, optionally not directly sellable; recipes use exact stock units |
| Made-to-order recipe | Burger with modifiers | Versioned recipe/options, allowed substitutions and kitchen routes; ingredients issue on send |
| Prepared batch/finished goods | Morning pastry batch | Production request issues ingredients on kitchen send; actual completed yield creates finished stock; sale consumes finished SKU once |
| Virtual kit/configurable combo | Meal with choice of drink | Component availability/reservations, priced parent with exact component allocations; no fictional parent inventory |
| Assembled stocked kit | Prepacked hamper | Assembly consumes components and creates the kit SKU; sale consumes the kit, not its components a second time |
| Non-stock service | Alteration or gift wrapping | Fixed, quantity or entered-duration charge; service completion and refunds distinct from physical stock |
| Reusable packaging and optional deposit | Returnable bottle/container | Container movement and receipt-linked deposit liability/return entitlement; no customer wallet; explicit tax/accounting mapping |
| Company-defined attributes | Brand, material, flavor, allergens | Validated typed fields and searchable labels; attributes cannot execute arbitrary stock/payment behavior |

Capabilities include can-purchase, can-sell, can-produce, inventory tracking, lot/serial tracking, measurement, pricing and fulfillment. Validate combinations: serial-tracked units cannot be fractional; ingredient units must convert dimensionally; recipes/kits cannot contain cyclic components; assembled and virtual kits use different stock paths. Where a measured item's mass-to-volume conversion is valid, store an explicit product-specific conversion and evidence, never a universal kg-to-liter rule.

Kitchen substitutions/modifiers select the actual recipe before reservation/send. An unavailable required component blocks the item; optional removal changes the versioned recipe and price according to configuration. Preparation batches capture actual yield, remaining work in progress and waste; cancelling after start retains the original consumption, just as for customer orders. A container deposit is refundable only within its receipt-linked remaining entitlement and applicable condition, with actual money movement recorded once.

A virtual combo with mixed fulfillment preserves each component's existing timing: made-to-order ingredients issue on kitchen send, retail components follow their reservation/finalization flow, and services do not issue fictional stock. Paying or completing the priced parent does not issue the children's inventory again. Estimated batch yield is unavailable for sale until the actual finished quantity has been posted and released.

Base stock unit, tracking method and identifiers cannot be casually changed after transactions. Use a controlled conversion/versioning workflow with reconciled balances or a new SKU. Disabling stock tracking is not an authorized way to sell an exhausted physical SKU. Archive products instead of deleting records needed by receipts, recipes, returns or recovery.

Maintain company uniqueness for SKU and scannable barcode within the applicable barcode namespace; preserve leading zeros. Ambiguous barcode formats and collisions are rejected during setup/import. Offline local requests for new globally unique codes remain pending company publication or use preallocated branch namespaces; independent branches cannot safely promise company-wide uniqueness for arbitrary new codes. Weighted barcode rules explicitly identify product, weight/price fields and checksum; a price-encoded barcode does not silently supersede a newly activated company price. Where its embedded price is used, it follows an explicit company-approved context and is checked against the decoded quantity and current rule; otherwise require reweigh/relabel rather than guessing.

The initial creation wizard asks for a template, name, category, sale/purchase flags, tax-included checkbox, unit, price and relevant stock settings. Reveal recipe/variants/lots/serials only when selected. Generate only valid variant combinations, preview counts before bulk generation and support bulk prices/barcodes. CSV import offers sample templates, dry-run errors, duplicate detection and explicit mappings. Updating a name is separate from changing identity; import retries do not create duplicate SKUs.

This catalog covers the four agreed activities and common adjacent retail/service products. A non-stock service record does not implement bookings, recurring subscriptions, rental custody or license delivery. Gift cards and stored-value products remain outside release one because they require the excluded balance/credit functionality. Specialized regulated-industry workflows are not claimed merely because custom attributes exist.

## 7. Branch setup wizard and go-live

Provide a resumable, role-aware wizard with an activity-specific checklist:

1. Establish the organization/company and authorized company head, then choose company activities and the branch. Confirm EGP, Africa/Cairo business timezone, business-day cutoff and applicable legal/tax identity.
2. Select enabled sales channels, tax/charge/return policies, price contexts, permission templates and limits. Show a readable policy summary; advanced settings can be completed by the correct administrator.
3. Register the selected master and branch identity, initialize local storage, provision branch-local users/credentials and named recovery access, then pair clients. Apply the mobile-master notice and lifecycle contract where relevant.
4. Import/create catalog, variants, recipes, prices and stock locations. Preview invalid units, barcode collisions, missing recipe components and incompatible tax/stock settings before publication.
5. Record counted opening stock by location/lot/serial and opening valuation. Record opening drawer float separately from inventory and sales. Opening supplier obligations, if migrated, are explicit opening records rather than fictional new purchases.
6. Assign each cashier's drawer, configure printers/kitchen routes/scanners/scales and perform the applicable peripheral checks. A branch without a kitchen does not need kitchen configuration.
7. Verify local login, roles, a sample order/refund/closing flow and master connectivity during a central outage in an isolated training dataset. Training documents and stock cannot be synchronized into live financial records or presented as live fiscal receipts.
8. Confirm a recoverable initial state and central synchronization/recovery status. An independent standby remains optional. Finalize the configured branch as live with the responsible user's acknowledgement and an auditable setup record.

Initial trusted registration may use central access as already specified; an authenticated offline first-install package is a separate deployment option. The wizard is not rerun on every login or master restart. A central outage/absent optional standby does not lock an already provisioned healthy branch out of ordinary operation. Go-live checks distinguish an actual missing required capability from an optional unconfigured feature; show the exact corrective action.

## 8. Everyday interface and operational recovery

Provide Arabic and English, correct RTL/LTR layout, readable numerals and prices, accessible labels and practical touch targets. Preserve barcode scanner focus and offer configurable keyboard shortcuts on desktop. Every dangerous shortcut still uses the same permission/confirmation rules as touch. Do not hide required information behind color alone.

Use activity-specific cashier layouts on the shared model: fast barcode/quantity entry for retail; categories, modifiers, tables and kitchen status for restaurants; variant selection for clothing. Products show available quantities where permitted, clear price/tax interpretation and required choices before adding. Local indexed search and bounded lists work without central connectivity. An unknown barcode offers an authorized lookup/create-request flow, not an untracked generic stock bypass.

Keep sale, payment and preparation states distinct. Show “Saved at branch,” “Waiting for master,” “Payment outcome needs checking,” “Kitchen cancellation awaiting confirmation,” and “Refund still due” as applicable. A cloud icon reports synchronization, not whether the local sale exists. Lost replies offer recovery of the original operation, not an immediately available new “charge again” action. Disconnecting from the master preserves drafts but cannot complete an independent financial transaction.

Payment shows payable, each recorded tender, remaining amount and change. Require confirmation for the external amount/method and configurable terminal/reference fields; never display an unverified bank approval claim. Manager approval appears within the current operation, and handing off an order makes acceptance/ownership visible. Printing failures allow an identified reprint without issuing a second sale; kitchen copies clearly identify repeats and uncertain originals.

Closing presents opening float, physical cash movements, expected count, actual count, variance and pending items separately from card totals. Company policy can select blind counting before showing the expected amount. Reports show whether totals are current or waiting for branch synchronization. Persist drafts at safe local checkpoints; Back/close warns about actual pending work and provides a recovery path. A master host's cashier logout never stops the branch backend.

## 9. References and status

Public sources read on 21 October 2026:

- [Odoo 19: product types](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/inventory/product_management/configure/type.html): separate sale/purchase capability, goods/services/combos and inventory tracking. The POS adds its own strict stock, recipe and branch-authority rules.
- [Odoo 19: pricelists](https://www.odoo.com/documentation/19.0/applications/sales/sales/products_prices/prices/pricing.html): conditional pricing by products, quantities and periods. Our company revision priority and offline guarantees are separate requirements.
- [Egyptian Consumer Protection Agency: frequently asked questions](https://cpa.gov.eg/ar-EG/اسئلة-متكررة): published return/exchange periods, exceptions and defective-goods remedies. The launch configuration must apply the actual product/service rules; this planning reference is not a blanket legal classification of every catalog item.
- [ERPNext purchase receipt](https://docs.frappe.io/erpnext/purchase-receipt) and [Odoo vendor-bill control](https://www.odoo.com/documentation/19.0/applications/inventory_and_mrp/purchase/manage_deals/control_bills.html): the companion purchasing specification records the detailed workflow and source-to-design mapping.

The source systems are design references, not dependencies selected for the POS. No project YAML, application or database was changed by this specification.
