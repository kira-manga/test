# POS decisions, operating defaults and remaining deployment facts

Revision 3.2, 21 October 2026. Updated against the user's answers and delegated operational design. Read with the [implementation handoff](POS_IMPLEMENTATION_PLAN.md), [architecture](POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md), [operational specification](POS_OPERATIONAL_SPECIFICATION.md), [company policies and catalog](POS_COMPANY_POLICIES_AND_CATALOG.md), [researched purchasing design](POS_PURCHASING_AND_RECEIVING_DESIGN.md), [acceptance scenario register](POS_ACCEPTANCE_SCENARIOS.md) and [historical backend source review](BACKEND_ARCHITECTURE_REVIEW.md).

The earlier questions about branch accounts, company price priority, individual drawers, private orders, inventory consumption, same-branch returns and first-release exclusions are resolved. The user explicitly asked the assistant to select practical cash/shift/order workflows and activity essentials. Those defaults are now specified rather than left as unanswered questions.

The latest eight answers also resolve company-configurable action limits, delegated pricing/returns/catalog/purchasing design, the branch setup wizard, company-head-controlled reports/alerts and delegated practical UI. The resulting defaults are documented in the two new companions; they are not a new list of questions for the user.

The subsequent execution-plan answers settle the entire company administration UI inside CMP and accept `ukkera-pos`/`BranchPos` as working names. Actual hardware/minimum legacy OS/load requirements are not yet specified; the user authorizes provisional test profiles and separate actual-device launch gates. These choices are incorporated in the detailed handoff and do not require another approval round before agent implementation.

This is document and requirements work. No project YAML, application runtime or business database was modified. Specified acceptance scenarios have not been executed.

## 1. Confirmed decisions

| Topic | Settled rule |
| --- | --- |
| Product/hierarchy | Egypt; Organization → Company → Branch; different company activities within one organization |
| First-release strategy | Common core serving restaurants, cafes, clothing and supermarkets, with activity-specific capabilities |
| Platforms | Windows, Android, iPhone/iPad and macOS; mobile master operation with notice, screen-idle prevention, supported Android foreground service and recovery on reopening |
| Authority | One company-selected local operational master per branch, including single-device branches; no central-direct checkout fallback |
| Recovery | Single-device branch may start without standby and synchronize centrally; optional standby is prepared before use; replacement requires specific company-delegated branch users |
| Checkout | Durable master commit confirms the sale; no internet, bank API or standby acknowledgement requirement |
| External payments | Cashier records the outcome of the separate payment device/service; POS does not independently authorize that bank transaction |
| Catalog/prices | Company-owned base catalog; branches may edit prices; a newer relevant company price revision supersedes older branch price changes |
| Accounts | Account belongs to one branch; another branch requires a separate account; admitted devices do not own the employee identity |
| Cash | Each cashier has their own drawer assignment; practical counted cash-session and review workflow is delegated for design |
| Active orders | Owner-private with explicit handoff; scoped manager/audit and kitchen/delivery projections have separate permissions |
| Stock | No sale beyond available sellable quantities; no negative-stock bypass |
| Kitchen | Deduct ingredients on sending; verified cancellation before start reverses original issues; cancellation after start keeps consumption and records disposition once |
| Returns/exchanges | Selling branch only in release one; works without internet using retained local receipt snapshots and entitlement |
| Exclusions | No customer credit sales, customer/store-credit balances or loyalty in release one |
| Tax/fulfillment | Per-product/price tax-inclusive or tax-exclusive interpretation; service/delivery charges; dine-in, takeaway and delivery |
| Definition files | Existing YAML is reference-only; the product receives a new namespace and project definition |
| Permissions/reporting | Company head defines action/approval limits, named delegation, report/cost/export scope and alert recipients; effective policy is enforced at the master |
| Latest delegated design | Select practical prices/promotions, return rules, broad product capabilities and researched purchasing/receiving defaults |
| Setup and usability | Approved resumable branch setup wizard; delegated practical activity-specific Arabic/English touch/keyboard/scanner experience |
| Administration UI | Complete company administration inside the Compose Multiplatform app, using central management APIs and a separately scoped central session |
| Names and libraries | Working repository ukkera-pos, namespace BranchPos; latest stable compatible Nav3/Koin/Coil/Room/toolchain verified from current official sources; requested P2pKit candidate explicitly qualified |
| Execution and testing | Main-led parallel agent tasks with independent peer review, continuous integration and full scenario/edge/fault/platform verification; provisional load targets accepted |

Do not ask the user to choose these rules again.

## 2. Defaults supplied under the user's delegation

The [operational specification](POS_OPERATIONAL_SPECIFICATION.md) defines the details and numerical examples. These are reviewable operating defaults, not claims that the user personally selected every screen, threshold or implementation constant.

| Area | Selected design baseline |
| --- | --- |
| Sessions | One active interactive session per branch account by default, explicit device move, optional company policy for additional same-branch sessions |
| Sign-in | Branch-local employee identifier and protected credential; optional fast PIN UX with throttling and reset; exact presentation can be finalized with the UI |
| Drawers | One custodian/active session per drawer, counted float, signed cash movements and explicit custody transfers |
| Closing | Open → Closing → SubmittedClosed → Reviewed; resolve unknown collections, count actual cash, preserve variance and review without rewriting history |
| Cash/reporting | Separate sales, collections, cash, external tenders, refunds, tax/charges, safe transfers, incidents and waste; no automatic employee debt for a discrepancy |
| Order handoff | Versioned request/acceptance; owner remains until acceptance; manager reassignment if absent; previous actors and money custody preserved |
| Kitchen | Quantity-level tasks, immutable issue snapshots, authoritative start/cancel competition and explicit paper-kitchen confirmation where start is uncertain |
| Wrong orders/remakes | Merchant error corrected without duplicate customer charge; new preparation checks stock and records new cost; customer changes have an explicit charge/waiver decision |
| Unpaid fulfillment | Preserve supplied goods, actual partial collections and outstanding incident; later recovery/write-off is linked, never a fake cash sale or customer credit wallet |
| Retail availability | Reserve tracked goods before collection; consume once at finalization; unknown payment reservations need explicit resolution |
| Returns/exchanges | Original quantities/prices/tax allocations, inspected stock disposition, pending refunds and restricted exchange offset; no duplicate cash flows or reusable store credit |
| Price updates | Branch change binds to company price revision; later company revision wins; protected order/receipt snapshots follow their separate price policy |
| Taxes/charges | Mixed inclusive/exclusive lines, deterministic discount allocation, explicit service/delivery bases and original-rule adjustments |
| Activity essentials | Tables/kitchen/delivery, variants/exchanges, weights/packs/expiry, receiving/counts/transfers, operational reports and Arabic/English usability as specified |
| Approval configuration | Amount/percentage/context limits, separate perform/approve/administer rights, grants bound to document/version; company-explicit self-approval for a named one-person operator where needed |
| Promotion policy | Deterministic contexts and eligibility, default one promotion per unit, explicit stacking and exact original refund allocations; no unallocated global offline redemption cap |
| Return policy | Egypt CPA-informed category/reason baseline, receipt/delivery dates, inspection, claim deadlines and continuing rights; more favorable commercial periods configurable |
| Product model | Composable stock, variants, units, weights, lots/serials, recipes, batches, kits, services and receipt-linked containers/deposits; no stored-value scope expansion |
| Purchasing | Quick or PO-based receiving, actual custody/quality, invoice matching, operational supplier payables, actual settlement, supplier return/credit and traceable landed cost |
| Company-wide offline limits | Allocate shared procurement budgets/quantities; route invoice settlement; detect cross-branch duplicate references after sync instead of falsely claiming global uniqueness |
| Setup | Resumable scoped wizard, opening quantities/value and cash, isolated training, optional standby and no recurring cloud dependency |

The implementation team can select exact entity names, internal APIs, token timing and retry tuning while preserving these rules. They are not reasons to repeatedly ask the user for permission.

## 3. Remaining external facts

These facts are needed for the affected deployment/integration. They do not reopen the agreed operating architecture or prevent preparing the new domain definitions.

| Fact | Why it is still needed |
| --- | --- |
| Launch companies' fiscal situation | Consumer receipts versus business invoices, legal entities/registrations, tax classifications, credentials and applicable Egyptian integration/outage rules are not determined by a tax-included checkbox |
| Actual peripheral models | Printer/scanner/scale/drawer interfaces and drivers must be qualified on the chosen Windows/macOS/Android/iOS devices |
| Expected operating load | Branch count, concurrent clients, catalog size, daily operations and maximum offline backlog determine measurable storage, latency and recovery targets |
| Specifically required external systems | Name any required ecommerce, delivery marketplace, accounting or legacy-data integration before promising its connector |

Initial deployment may use central onboarding to establish branch identity, catalog, accounts and recovery access; ordinary reboots/login/checkout remain local. An authenticated offline first-install package is an implementation extension if a deployment requires it. There is no hidden cloud renewal requirement for existing branch operation.

Company-configurable values such as commercial return extensions, discount/approval limits, business-day cutoff and receipt layouts need launch configuration. Defaults, permitted bounds and the setup flow are specified; they do not require another round of foundational architecture questions. Actual product/legal classifications can refine the researched Egypt baseline without erasing customer claims or silently shortening applicable rights.

## 4. Engineering specifications and evidence still required

| ID | Required work |
| --- | --- |
| T01 | Map every operational state family to local persistence, optional standby, central stored operations and protected recovery archives. Include branch accounts, order ownership, ingredient issues, cash custody, unpaid incidents and return entitlement. |
| T02 | Define coherent snapshot/journal restoration, schema compatibility, authority history and deduplication without replaying external payments or physical printing. |
| T03 | Implement delegated recovery authentication, device trust and old-master retirement/isolation for each platform. Respect explicit stop and handover states on automatic reopening. |
| T04 | Implement guarded command contracts for cash, ownership, kitchen issue/start/cancel, collection, returns, exchange offsets and monetary corrections; commit durable operation results with the effects. |
| T05 | Specify bounded synchronization/replication, independent acknowledgements/cursors and durable exception handling; preserve all unacknowledged financial work. |
| T06 | Qualify schema migration and mixed-version upgrades with pending operations and recovery copies; never assume an old runtime can open a new schema. |
| T07 | Set return/evidence/financial retention, local archival and backup rotation against actual legal/business windows and storage; unresolved evidence must not disappear on a retry/age timeout. |
| T08 | Verify peripheral custody/mapping through handover and truthful uncertain print outcomes. Test real mobile idle, background, force-stop and reopening behavior. |
| T09 | Use exact money/quantity arithmetic, original tax/price allocations, non-reused identifiers and configured timezone/business-day behavior. |
| T10 | Resolve or isolate the historical generic access/write-guard and generator/SDK compatibility issues before exposing new protected records. |
| T11 | Implement catalog capability validation, deterministic price/promotion/refund allocation, claim deadlines and permissioned report/export/alert projections. |
| T12 | Implement receiving quality/custody, PO/invoice allocations, supplier settlement and cost corrections with offline authority and recovery; include these states in T01–T02. |
| T13 | Implement the resumable setup/training boundary and actual touch/keyboard/scanner/RTL workflows, with recoverable unknown outcomes and no cashier-to-master lifecycle coupling. |

## 5. Next concrete outputs

1. Execute the implementation handoff and task catalog against the consolidated business baseline, starting with source/toolchain/generator compatibility verification.
2. Implement the accompanying domain/contract blueprint as the field/ownership/relationship map, validating generator constraints and distinguishing central models, embedded snapshots and local-only security structures.
3. Generate in isolation, implement native administration, the local command engine and central ingestion, and execute the scenarios against real local services and target devices.
4. Complete fiscal/peripheral qualification for the actual launch businesses and run operational pilots; add discovered cases to the register with explicit owner, payment, cash, stock, audit and recovery behavior.

The product plan now has an operational baseline. Production readiness still depends on implementation and measured evidence, not the number of entities or scenarios in a document.
