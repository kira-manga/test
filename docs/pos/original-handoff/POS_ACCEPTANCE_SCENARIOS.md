# POS acceptance scenario register

Revision 1.2, 21 October 2026. Companion to [architecture 3.2](POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md), the [operational specification](POS_OPERATIONAL_SPECIFICATION.md), [company policies/catalog](POS_COMPANY_POLICIES_AND_CATALOG.md), [purchasing design](POS_PURCHASING_AND_RECEIVING_DESIGN.md) and [implementation quality plan](POS_TEST_AND_QUALITY_PLAN.md).

Status of every scenario: specified, not executed. The register is a coverage plan, not test evidence. Record implementation revision, environment, actual outcome and evidence when each scenario is executed. User-interface demonstrations or generated CRUD alone do not satisfy these checks.

Use isolated data: one organization with separate clothing and restaurant companies; at least two branches under a company; separate branch-local accounts for the same person; two cashiers with distinct drawers; a master, multiple clients and optional standby. Run applicable ordinary operations with central services and standby disconnected. Each numerical fixture starts independently.

## Cash, shifts and custody

| ID | Trigger | Required observable result |
| --- | --- | --- |
| CASH-01 | Open a drawer with counted float 1,000 | One cashier/one active session; opening cash 1,000 and sales zero; retry creates no second opening |
| CASH-02 | Attempt a second cashier session on the occupied drawer | Reject competing custody without changing either session |
| CASH-03 | Opening 1,000; received 5,000; change 400; cash refunds 200; pay-in 100; pay-out 150; safe drop 3,000; external tenders 4,000 | Expected closing cash 2,350; external tenders shown separately; no double subtraction of change |
| CASH-04 | Count 2,360 against expected 2,350 | Overage 10; preserve actual count; no invented sale or forced employee debt |
| CASH-05 | Retain float 500 from actual closing cash 2,360 | Closing safe transfer 1,860; safe acknowledgement shares the transfer ID and is not revenue |
| CASH-06 | Logout, lock screen or move to another admitted device | Human session changes do not close, transfer or duplicate cash custody |
| CASH-07 | Refund a previous day's receipt from today's cashier drawer | Today's actual cash decreases; original sale linked; previous closing report remains immutable |
| CASH-08 | Close during an unknown collection outcome | Recover the original command or preserve explicit controlled exception; no silently lost payment or duplicate next-shift collection |
| CASH-09 | Submit closing while another request attempts a payment | Deterministic closing cut: request belongs to the open session before the cut or is rejected; no uncounted post-cut movement |
| CASH-10 | Manager reviews a submitted variance after the next shift opens | Append review/adjustment evidence; no reopening or overwriting of the original count |
| CASH-11 | Safe transfer receipt is lost/duplicated | Recover one transfer; show unresolved custody until acknowledged; never count it twice |
| CASH-12 | Refund/pay-out exceeds available drawer cash | Do not claim cash paid; retain obligation and require actual replenishment/allowed alternative |

## Identity, order ownership and table/bill operations

| ID | Trigger | Required observable result |
| --- | --- | --- |
| OWN-01 | A user from branch A attempts login/action in branch B | Require a separate enabled B account; no inherited A permissions, drawer or orders |
| OWN-02 | User B logs into a device previously used by A | Clear A's private UI/tokens; retain protected unresolved operations attributed to A |
| OWN-03 | Another cashier lists, opens or edits an owner's active order | Deny ordinary access through list, direct ID, subscription and cached projection |
| OWN-04 | Owner offers a handoff and recipient accepts twice | Exactly one ownership transition; original creator and earlier payment actors preserved |
| OWN-05 | Handoff races with payment/another handoff | Version/lock policy gives one valid outcome; no second owner or relabelled collection |
| OWN-06 | Manager reassigns an absent employee's order | Explicit permission/reason and audit; cash already collected remains with its original session |
| OWN-07 | Kitchen or courier opens its assigned projection | Required task data visible; unrelated prices/customer/financial records remain inaccessible |
| OWN-08 | Split or merge a bill with shared fees and partial payments | Exact quantities, charges and collected amounts preserved once; no repeated ingredient issue |
| OWN-09 | Split/merge across incompatible branch/company or without both ownership grants | Reject without partial mutation |
| OWN-10 | Authorized return operator looks up another cashier's historical receipt | Only necessary receipt data accessible; create a new linked return, preserving original ownership/history |
| OWN-11 | Staff accidentally creates two separate orders for one intended request | Explicitly link/cancel the duplicate; preserve any real preparation/payment effects instead of treating different operation IDs as automatic transport duplicates |

## Kitchen, inventory issue and cancellations

| ID | Trigger | Required observable result |
| --- | --- | --- |
| KIT-01 | Send two burgers; initial bread 10, meat 1,500 g; each requires one bread and 150 g meat | Bread 8 and meat 1,200 g; two durable portion tasks and one set of issue effects |
| KIT-02 | Retry the same kitchen send after a lost reply/restart | Same tasks/issue IDs and quantities; no second deduction or new independent ticket |
| KIT-03 | Cancel one of those two portions before verified start | Bread 9 and meat 1,350 g; reverse original allocation for one portion only |
| KIT-04 | Cancel both after preparation starts | Bread stays 8 and meat 1,200 g; waste/cost disposition recorded without another negative issue |
| KIT-05 | Some portions started and others not | Restore only verified unstarted quantities; preserve started portion cost |
| KIT-06 | Start and confirm-before-start-cancellation arrive concurrently | One transition wins; a cancelled portion cannot subsequently start from a stale screen |
| KIT-07 | Paper ticket printed but preparation state unknown | No automatic restock; cancellation awaits kitchen/authorized confirmation |
| KIT-08 | Printer response lost or printer unavailable after kitchen send | Committed issue remains durable; printing/reprint state explicit; no blind cancellation or duplicate preparation instruction |
| KIT-09 | Recipe, yield, modifier or lot configuration changes after issue | Cancellation uses the original snapshot/allocation; current recipe does not rewrite old consumption |
| KIT-10 | Merchant remakes an incorrectly prepared item | New ingredients checked/issued; original waste recorded; one customer charge and no doubled sold quantity |
| KIT-11 | Replacement ingredients insufficient | Reject new preparation; offer authorized available alternative/refund, without negative stock |
| KIT-12 | Finalize payment for portions already sent to kitchen | Preserve prior ingredient deductions; finalization does not deduct them again |
| KIT-13 | Customer changes an already-started order | Explicit charge/waiver and replacement decisions; old ingredients remain consumed and any paid difference is settled explicitly |
| KIT-14 | One kitchen station starts while another still shows queued | Portion counts as started; no whole-recipe before-start reversal; ready requires every required task |
| KIT-15 | Customer prepays before kitchen send | Ingredients reserved before collection, retained while paid/unsent, then converted into one issue on send; cancellation releases reservation and resolves refund |

## Sellable stock, receiving and counts

| ID | Trigger | Required observable result |
| --- | --- | --- |
| STK-01 | Sell a tracked item with zero available quantity | Reject before requesting collection; no manager negative-stock bypass |
| STK-02 | Two clients compete for the last available quantity | At most the available quantity reserved/sold; loser receives current shortage information |
| STK-03 | One recipe/kit component is short despite others being available | Reject the selected batch atomically; do not partially issue hidden components |
| STK-04 | Stock exists only as expired, blocked, quarantined or reserved units | Those quantities are not advertised or sold as available |
| STK-05 | Scan weighted/pack barcode or edit decimal quantities | Exact configured conversion and authoritative price; leading-zero identifiers preserved; malformed formats rejected |
| STK-06 | Duplicate receiving/transfer receipt after lost acknowledgement | One stock increase with the original source ID; discrepancies remain visible |
| STK-07 | Stock count overlaps sales/receipts | Apply a verified movement cut/deltas or require recount; no silent balance overwrite |
| STK-08 | Collection outcome unknown while reservation would normally expire | Preserve the reservation/evidence until explicit resolution; no double allocation to another sale |
| STK-09 | Return accepted as damaged rather than sellable | Quantity moves to quarantine/waste disposition; cannot be sold merely because money was refunded |

## Payments, unpaid service and delivery

| ID | Trigger | Required observable result |
| --- | --- | --- |
| PAY-01 | Cashier records external terminal success while internet is unavailable | Local validation and completion; no POS provider verification call required |
| PAY-02 | Cashier records not-paid or an insufficient partial tender | No false paid-sale finalization; remaining amount visible |
| PAY-03 | Cash plus external tender with cash change | Correct coverage and one net physical cash effect; no card change invented |
| PAY-04 | External payment occurred, then reply/storage availability is uncertain | Preserve attestation and operation ID; recover outcome without instructing another charge |
| PAY-05 | Served check 300, customer pays 100 then leaves | Actual collection 100; unpaid incident 200; no inventory restoration or automatic cashier shortage 200 |
| PAY-06 | Collect the remaining 200 in a later shift | Current collecting session receives 200; original incident reduced once; no second sale/stock issue |
| PAY-07 | Manager writes off an unpaid fulfilled check | Approved exception and required accounting/fiscal treatment; no fictional cash movement or erasure of supply |
| PAY-08 | COD order delivered but collector has not remitted | Delivery, customer payment and collector custody remain separate; no second sale on remittance |
| PAY-09 | Delivery refused/failed with prepared food or sealed goods | Appropriate inspection/waste and fee/refund decision; no automatic ingredient restock |
| PAY-10 | Correct wrong tender classification after shift close | Linked adjustment explains original custody; no new physical cash entry without money moving |
| PAY-11 | Attempt customer credit, stored balance or loyalty redemption | Feature absent/disabled in release one; cannot use a generic tender to bypass paid coverage |
| PAY-12 | Prepared takeaway is never collected | Abandoned-pickup exception, actual food disposition and explicit prepaid refund/charge decision; not falsely delivered or converted into customer credit |
| PAY-13 | Recorded external payment is later disputed/reversed | Preserve original attestation; append evidence, reconciliation and actual subsequent cash/tender effects; no silent inventory restoration |
| PAY-14 | Authorized exceptional retail release occurs before collection | Availability checked, actual fulfillment/stock issue recorded once and unpaid obligation retained; no fictional payment or later second issue |

## Returns and exchanges

| ID | Trigger | Required observable result |
| --- | --- | --- |
| RET-01 | Same-branch partial return while offline | Original local snapshot and entitlement used; no central lookup prerequisite |
| RET-02 | Return receipt from another branch of the same company | Reject and identify selling branch; no entitlement or stock mutation |
| RET-03 | Simultaneous partial returns exceed remaining quantity/amount | Atomically accept only valid remaining entitlement; duplicates return original result |
| RET-04 | Accept return while external refund is pending/unknown | Entitlement not reusable; refund due/unknown stays visible and recoverable |
| RET-05 | Exchange return value 100 for replacement 130 | Linked return/new sale and restricted offset 100; actual collection 30 only |
| RET-06 | Exchange return value 100 for replacement 80 | Restricted offset 80 and actual refund 20; no spendable store-credit balance |
| RET-07 | Replacement becomes unavailable or collection fails during exchange | Preserve accepted return and refund obligation; no fabricated completed replacement |
| RET-08 | Prepared/served food refunded | Refund handled separately; original ingredients not automatically restored |
| RET-09 | Return after company price/tax/discount changes | Use original permitted refundable snapshots/allocations, not today's price |
| RET-10 | Receiptless return without enabled policy/approval | Reject; exceptional permission cannot bypass evidence/valuation/inspection rules |
| RET-11 | Order was created before actual customer delivery; an eligible goods return/defect claim arrives near its deadline | Use actual receipt/delivery date and applicable category/reason policy; record claim/deadline independently of inspection or actual payout |
| RET-12 | Change-of-mind exception applies to prepared/perishable goods, but customer reports merchant error/defect | Separate remedy path remains available; no blanket denial and no automatic ingredient restock |
| RET-13 | Company shortens its commercial policy after an earlier sale or ordinary operator limit blocks a remedy | Preserve promised/applicable rights, record and escalate the claim; no erased obligation or silent application of a less favorable current rule |

## Pricing, tax, reports and company separation

| ID | Trigger | Required observable result |
| --- | --- | --- |
| TAX-01 | Company 100/revision 5; branch 120/revision 5; company 150/revision 6 | Activating revision 6 makes effective price 150; late old branch update cannot restore 120 |
| TAX-02 | Company changes one SKU while other SKU has a branch price | Apply only the relevant price revisions; unrelated branch changes remain intact |
| TAX-03 | Company update reaches preserved versus reprice-eligible open orders | Apply configured policy; paid/collection-locked/customer-committed snapshots not silently changed |
| TAX-04 | Inclusive 114 and exclusive 100, each at illustrative 14% | Combined net 200, tax 28 and payable 228 before other charges |
| TAX-05 | Fixed discount, mixed tax lines and partial return | Advertised reduction, deterministic allocations and exact receipt totals; refund never exceeds original allocation |
| TAX-06 | Dine-in service, takeaway and delivery charge policies | Correct applicable charge bases/tax; no hidden delivery fee on takeaway or duplicate inclusive tax |
| TAX-07 | Food and clothing companies share one organization | Independent catalogs/profiles/accounts; unauthorized cross-company IDs, sync and caches rejected |
| TAX-08 | Fiscal submission is delayed/retried/rejected | Truthful document status and durable retry/exception under applicable regime; no duplicate fiscal document or rewritten original sale |
| TAX-09 | Compare sales, collections, refunds, safe drops and incidents in reports | Reconcile each category separately to its ledger; cash count is not equated with total sales |

## Runtime, synchronization and recovery

| ID | Trigger | Required observable result |
| --- | --- | --- |
| REL-01 | One-device branch with no standby and central outage | Existing local accounts and healthy local master continue ordinary checkout and same-branch returns |
| REL-02 | Central/standby return after a long backlog | Durable idempotent catch-up while new work continues; no repeated sales, stock issues or cash effects |
| REL-03 | Cashier client disconnected from master | No independent financial finalization; preserve private drafts and unresolved evidence |
| REL-04 | Master restarts after local commit but before response | Original operation/result and actors recovered; outboxes remain durable |
| REL-05 | Planned handover interrupted before/after sealing | Safe abort before seal or controlled continuation after; no two active writers |
| REL-06 | Replaced old master returns | Remains retired/isolated until controlled return; no automatic reclaiming of authority |
| REL-07 | Mobile master activation, lock/background, force-stop and reopening | Notice/idle policy applied; actual availability reported; reopening recovers only its still-active role |
| REL-08 | Software upgrade with pending payment/kitchen/return work | Compatible recovery/migration; stable operation IDs and no lost financial/stock evidence |
| REL-09 | Restore branch from supported central recovery data or optional standby | Verify coherent checkpoint, accounts/policies/orders/locks/custody/entitlements; disclose gaps; no stale bearer-token resurrection |
| REL-10 | Disk full or power interruption during atomic command | No false success; committed result recoverable or no effects; external physical payment evidence retained for reconciliation |

## Company permissions, report scope and approvals

| ID | Scenario | Required outcome |
| --- | --- | --- |
| CFG-01 | Company head sets both amount and percentage discount ceilings | Enforce both ceilings on master command and approval; changing the UI/request cannot bypass either |
| CFG-02 | An employee changes amount/lines after a manager approved the earlier document version | Reject stale approval and request a valid grant; original cashier/approver identities preserved |
| CFG-03 | Branch administrator tries to exceed delegated limits or grant themselves company-wide reports | Reject escalation; explicit deny and company ceiling win; audit the attempt |
| CFG-04 | Named sole operator has company-authorized self-approval for one action | Permit only that bounded action; no implicit self-approval for cash write-off, policies or master recovery |
| CFG-05 | Company revokes report/cost access while branch is offline, then reconnects | Show central update pending; enforce current local policy until activation, then recheck sessions, queries, subscriptions and cached views |
| CFG-06 | Cashier or inventory user requests hidden margin fields/export by direct ID or alternate endpoint | Server filters/denies according to company-head grants; viewing quantities does not grant costs/export |
| CFG-07 | A low-stock/payment alert is retried, opened by a removed recipient or acknowledged | Deduplicate delivery; recheck access; acknowledgement does not create stock, resolve payment or expose sensitive lock-screen totals |
| CFG-08 | Optional numeric approval limit was never configured | Do not interpret omission as unlimited authority; show setup/action resolution without blocking unrelated routine checkout |

## Price rules and promotion allocations

| ID | Scenario | Required outcome |
| --- | --- | --- |
| PRC-01 | Variant, category, channel, quantity and dated price rules overlap | Stable documented precedence and preview; exact same result across retry/devices; protected order snapshot retained |
| PRC-02 | Two automatic offers and a manual order discount target the same units | Default exclusive group selects the eligible best reduction; only explicitly permitted stacking and limits apply |
| PRC-03 | Two equal-value items cost 100 together; one is returned | Original paid allocations are 50 each; one refund of 50, bounded remaining entitlement and no repricing the retained unit |
| PRC-04 | Promotion requires unavailable component/quantity or basket changes during collection | Reject insufficient stock/revalidate before lock; never silently change the locked payable or award nonexistent units |
| PRC-05 | Company requests a globally single-use campaign across disconnected branches | Reject unsupported configuration or require explicit allocated entitlements/coordination; ordinary branch code does not promise global uniqueness |
| PRC-06 | Supplier cost changes while a price campaign is active | Update purchase valuation only; selling price requires its authorized company release and promotion context |

## Product capabilities, identity and production

| ID | Scenario | Required outcome |
| --- | --- | --- |
| CAT-01 | Create size/color variants with only a subset of valid combinations | Create only selected SKUs; correct independent stock/barcodes and inherited defaults; import retry produces no duplicates |
| CAT-02 | Receive two 12-unit cartons, later change future pack definition to 10 | Original receipt remains 24 base units; later document uses its own approved conversion |
| CAT-03 | Weigh an item, scan a malformed/ambiguous weighted code or use an invalid unit conversion | Exact supported quantity; reject ambiguity/incompatible units; embedded price cannot silently bypass current company price |
| CAT-04 | Receive/return serialized product with duplicate serial or fractional quantity | Reject invalid identity/quantity; valid return matches original sold serial and remaining entitlement |
| CAT-05 | An expiry-tracked variant exists in available and quarantined/expired lots | Select only valid released quantities; later inspection release occurs once and retains provenance |
| CAT-06 | Prepare 10 finished units from a versioned recipe, then sell 3 | Ingredient issue occurs on production kitchen send; actual finished output posted once; sale reduces finished stock to 7 without another ingredient issue |
| CAT-07 | Sell virtual kit with mixed kitchen/retail/service components versus previously assembled stocked kit | Virtual components preserve their own issue timing; parent payment never reissues them; assembled kit uses stocked output; no double deduction |
| CAT-08 | Configure recursive kit, fractional serial or incompatible ingredient dimensions | Reject invalid capability combination before activation; existing valid catalog remains usable |
| CAT-09 | Change a tracked physical SKU to non-stock after selling it out | Block casual conversion; require controlled version/migration/new SKU with preserved history, not a zero-stock bypass |
| CAT-10 | Record a refundable container deposit, return once, then retry from another client | Separate container/liability and actual cash effects; receipt entitlement consumed once; no spendable customer balance |

## Purchasing, receiving and supplier settlement

| ID | Scenario | Required outcome |
| --- | --- | --- |
| PUR-01 | Quick-receive without PO, with invoice and cash payment on one screen | Separate linked receipt, invoice and actual pay-out; stock/cash/debt each change exactly once |
| PUR-02 | Supplier invoice arrives before goods | Save actual invoice/unmatched status; no sellable stock increase and no fictional physical receipt |
| PUR-03 | Receive 24 units: accept 22 and hold 2 damaged | Custody 24, sellable 22, rejected-held 2; invoice for 24 exposes mismatch; two units need fulfillment/claim resolution with no double replacement allowance; correct ownership/value treatment |
| PUR-04 | Two clients receive the last PO allocation concurrently or retry after lost reply | At most remaining approved quantity posted; original operation recovered; no duplicate stock or billed allocation |
| PUR-05 | Partial delivery followed by excess goods with zero allowed tolerance | Keep outstanding quantity; record excess physical custody in non-sellable hold until authorized acceptance/return; no hidden PO expansion |
| PUR-06 | Missing required inspection/lot/serial or wrong SKU supplied | Do not release sellable stock; preserve held quantity and explicit inspection/substitution resolution |
| PUR-07 | Invoice quantity/price differs from PO and accepted receipts | Retain supplier claim; classify discrepancy, enforce configured approval and allow only authorized uncontested settlement |
| PUR-08 | Invoice 1,000; prior allocated advance 200; pay cash 500; later credit 100 | Initially due 300, then 200; today's physical pay-out 500 only; no duplicate advance, cash, stock or expense |
| PUR-09 | Same supplier invoice entered twice locally and independently in disconnected branches | Local duplicate guarded; cross-branch conflict detected on sync and routed to one settlement authority; no false claim of prior global uniqueness or auto-deletion of receipts |
| PUR-10 | Company allocates a shared PO to branches, then an offline branch tries another branch's allowance | Enforce its own remaining allocation; no unallocated shared offline budget/quantity spend |
| PUR-11 | Supplier return includes units already reserved/sold or returns only rejected supplier-owned goods | Reject unavailable quantities; preserve correct custody/value basis and separate supplier claim; no negative purchased stock |
| PUR-12 | Supplier credit arrives without physical return, or replacement arrives after dispatch | Credit changes financial claim only; actual replacement creates one inspected receipt with explicit free/charged terms; no doubled stock or payable |
| PUR-13 | Existing 10 units at cost 20; receive 10 at cost 30 | Quantity 20, value 500, average 25; old sales/issue cost remains immutable |
| PUR-14 | Goods A 100, goods B 100, unrelated service 100 and eligible freight 30 | Allocate inventory freight 15 each to A/B, none to unrelated service; payable source and cost allocation not counted twice |
| PUR-15 | Late acquisition-cost change after part of receipt is consumed | Explicit exact adjustment for remaining inventory and consumed-cost variance; unchanged quantities and immutable original sale/closing snapshots |
| PUR-16 | Power loss during receipt/settlement, or restoring a branch with open purchasing disputes | Recover operation/result, quality custody, allocations and obligations; do not replay a physical payment or silently approve a disputed invoice |

## Setup, daily interface and training

| ID | Scenario | Required outcome |
| --- | --- | --- |
| UX-01 | Setup interrupted after catalog/stock import and resumed | Continue saved wizard state; stable import/opening operation IDs prevent duplicate SKU, stock or supplier balances |
| UX-02 | Opening inventory/valuation and drawer float are entered | Correct opening ledgers with sales/revenue zero; no fictional purchases or duplicate cash receipt |
| UX-03 | A training sale/return/close is exercised, then branch goes live | Isolated training data never enters live ledger/synchronization/fiscal submission; live setup is auditable |
| UX-04 | Setup a single mobile master with no standby; later lose central access | Apply notice/idle contract and provision local users/recovery; healthy provisioned branch continues without recurring wizard/cloud lease |
| UX-05 | Use Arabic RTL and English with touch, keyboard and scanner during rapid item entry | Correct labels/layout/price interpretation and focus; shortcuts preserve identical quantity, authorization and confirmation rules |
| UX-06 | External payment/kitchen response is unknown and user taps Back, switches account or retries | Preserve original actor/operation and recovery status; no automatic new charge, stock issue or preparation ticket |
| UX-07 | Manager approves within cashier flow, then cashier logs out on master device | Cashier remains collector for that operation; logout clears private UI but master backend continues |
| UX-08 | Printer fails or a company report includes an offline branch | Distinguish committed local sale from print failure and stale central totals; identified reprint does not create a second sale |

## Completion evidence

For each scenario, record the exact implementation revision, prepared fixture, relevant platform roles, network condition, observed documents/ledger changes and pass/fail evidence. Distinguish automated domain tests, integration tests, installed-device tests and supervised operational rehearsals. Repeat boundary cases with duplicate delivery, concurrent requests and a lost response at the relevant commit transition.

Pilot observations extend this register. A new case is complete only when its owner/permission, customer amount, actual cash/tender outcome, stock/cost disposition, audit history and recovery behavior are specified and verified.
