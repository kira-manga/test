# POS domain, persistence and contract blueprint

Revision 1.0, 21 October 2026. Implementation handoff for the temporary repository name `ukkera-pos` and namespace `BranchPos`. This is the field/ownership/transaction design for the agreed product, not a claim that the models, procedures or endpoints already exist. Author a new YAML; do not modify existing Pos, Item, Org or other source YAML as part of defining this module.

Read with the [architecture](POS_PRODUCTION_ARCHITECTURE_PROPOSAL.md), [operational specification](POS_OPERATIONAL_SPECIFICATION.md), [company policies and catalog](POS_COMPANY_POLICIES_AND_CATALOG.md), [purchasing design](POS_PURCHASING_AND_RECEIVING_DESIGN.md), [141 acceptance scenarios](POS_ACCEPTANCE_SCENARIOS.md) and [historical backend investigation](BACKEND_ARCHITECTURE_REVIEW.md). Those documents define accepted product behavior; this blueprint makes implementation boundaries concrete. Model names below are candidate implementation names, with required semantics that must survive any deliberate renaming.

The latest UI decision is authoritative: the complete company administration experience lives inside the Compose Multiplatform application. `module-manager`, `main-next` and `modules` remain generation/development infrastructure where required by the existing backend workflow; a generated web administration screen is not a delivered company-management feature for this product.

## 1. Authorities, identities and modes in one application

| Context | Authentication and scope | Authoritative actions | Forbidden shortcut |
| --- | --- | --- | --- |
| Company administration in CMP | Central human identity and explicit organization/company/branch grants | Publish catalog, policy, company procurement allocations; inspect acknowledged reports; request branch changes | Using organization membership as unrestricted access to all companies, or editing branch stock/cash/sales directly |
| Branch operation in CMP | Admitted device plus a separate branch-local employee/service session issued by its master | Operate that branch through the master's domain commands, including delegated local administration | Treating a paired device or central admin login as a cashier identity |
| Embedded master service | Device/service identity and a persisted active master assignment | Authoritative local authentication, reads, commands, transactional storage and asynchronous synchronization | Stopping the branch when its cashier signs out; calling cloud checkout when a master cannot be reached |
| Central ingestion | Authenticated branch-service identity, authority history and typed immutable bundles | Store branch evidence, update central projections, acknowledge durable checkpoints | Rerunning checkout or rejecting a past sale because current catalog prices differ |
| Standby/recovery participant | Explicit device admission and recovery delegation | Receive protected recovery data; activate only through approved transfer/replacement | Becoming master on heartbeat failure or by opening a copied database |

Central admin and local employee sessions use separate token stores, repositories, navigation roots and permission scopes even though their screens share one application. Switching context invalidates the previous private subscriptions and cached screen state. A human may possess both identities; an optional directory link associates them for administration, never for inherited operating permission. A central company head who works a till signs into a separate account for that branch.

Company-wide catalog publication, arbitrary company-wide unique identifiers, shared procurement allocation changes and central user grants require the central authority. When it is unavailable, show appropriately permissioned cached administration data with freshness and allow local drafts. A draft is not a published company update. Reconnection revalidates its original revision and current permission; conflicts require an explicit resolution instead of blind last-write-wins. Delegated branch-local changes continue through the master under its activated policy. A centrally requested branch operational change is a command intent with applied/rejected/pending state; its request record is not proof of a stock or money effect.

An already provisioned branch needs neither central token renewal nor a cloud lease to run. Initial enrollment establishes trustworthy company/branch identity, local users and recovery access. Initial installation without central connectivity requires a separately qualified, authenticated provisioning package. Keep training data in a different database and identity/transport namespace, with no route into live ingestion, fiscal submission or live receipt numbering.

## 2. Universal identifiers, scalars and tenant boundaries

### 2.1 Required scalar contracts

| Type | Storage and wire rule | Validation |
| --- | --- | --- |
| `DomainId` | New domain records use cryptographically generated UUID identifiers, encoded as canonical lower-case strings; identity never depends on device clock or receipt number | Parse before access; bounded length; no reuse after cancellation, restoration or migration |
| `OperationId` | Generated and durably saved by the initiating application before its first state-changing request; stable across retries, reconnects and authority transfer | Unique within the branch independently of authority epoch; cannot be reused with another semantic payload or actor |
| `StorageId` | Backend-specific generated document identity, if required | Do not assume the generator accepts UUIDs as Mongo `_id`; qualify an explicit `domainId` mapping and unique index when native IDs differ |
| `Revision` / `Sequence` | Bounded nonnegative 64-bit integer internally; serialize as decimal strings across JSON boundaries to avoid JavaScript precision loss | Explicit increments and overflow rejection; never derive ordering from wall time |
| `Money` | Currency plus signed integer minor units; EGP uses two decimal places. Wire minor units as strings, such as `{ "currency": "EGP", "minor": "11400" }` | Bounded 64-bit postings; reject mixed currency in a release-one transaction and overflow before writing |
| `ExactDecimal` | Canonical decimal string plus a declared scale contract; use exact decimal/rational arithmetic for unit prices, quantities, rates and allocations | No `Float`/`Double` for posted money, quantity comparison, tax or cost. Reject NaN, infinity, exponent abuse and excessive precision |
| `Quantity` | Product base-unit quantity, stored as scaled integer with up to six fractional digits initially; each unit/SKU declares its allowed precision | Positive command quantities; signed ledger deltas derived by the command. Serialized units must be whole numbers |
| `UnitPrice` / `UnitCost` | Exact precision up to eight fractional digits initially, with bounded aggregate values | Preserve calculation precision until the declared posting boundary; never truncate a small ingredient cost on every component |
| `Rate` | Exact bounded ratio with explicit meaning, such as a percentage or unit-conversion numerator/denominator | Reject a zero denominator and unsupported rate combinations; do not infer a percentage from an untyped number |
| Time | UTC instant, branch IANA timezone and relevant local date; record optional client observation time separately from master commit time | Master sequence orders events; business-day cutoff and legal document date are explicit. Clock rollback cannot reuse IDs or reset lockout counters |
| `Code` / `Barcode` / external reference | Original string plus separately normalized searchable/unique key and a normalization-version marker | Preserve leading zeros; do not cast codes to numbers; reject ambiguity and normalize before uniqueness checks |
| `ContentHash` | Algorithm identifier plus hash of a defined canonical representation or exact transferred bytes | Never hash platform-dependent object stringification; include hash-format version |

The precision values are initial engineering contracts, not a reason to approximate unsupported input. Use checked exact intermediate arithmetic; multiplying two valid 64-bit operands may overflow a 64-bit intermediate. The dependency qualification task must choose and verify the multiplatform implementation, including Kotlin/Go/JSON golden fixtures. Increasing precision later is a versioned migration and contract change. APIs return a specific validation error for unsupported precision instead of silently rounding stock quantities.

Choose and document one versioned rounding algorithm. Default arithmetic is half-up at the explicitly defined monetary boundary; fiscal adapters may require a different validated rule and preserve that rule's identifier. Allocate fixed discounts/charges/tax remainders deterministically by exact proportional share, largest remainder and stable line/portion ID. Sum posted allocations to their declared document amount exactly; the last consumed quantity/value allocation absorbs the recorded remainder where necessary. Refund the original stored allocation, not a fresh division at today's price.

### 2.2 Mandatory field groups

Every company-scoped record has non-null `organizationId`, `companyId`, `domainId`, `schemaVersion` and its lifecycle/version metadata. Every branch-owned record adds non-null `branchId`. Resolve organization/company/branch relationships on the trusted side; a client-supplied scope is only a requested target. For branch commands, the effective scope comes from the admitted connection and local session.

Mutable aggregates carry `revision`, `createdAt`, `createdBy`, `updatedAt` and `lastOperationId`. Posted facts carry `originOperationId`, `originAuthorityEpoch`, `originJournalSequence`, captured actor/approver context, business time and immutable content/version. Historical actor references retain display-safe snapshots when a user later changes name or is disabled.

Use composite tenant foreign keys or equivalent enforced constraints: `(companyId, productId)` and `(branchId, orderId)` cannot point into another company's product or another branch's order. A globally unique random ID does not establish authorization. Central lookup, direct-ID query, relationships, export, subscription, batch write and ingestion each enforce scope. Avoid nullable scope filters whose `OR` composition could enlarge access. Do not create one nullable “company-or-branch” record model whose semantics depend on which ID happened to be omitted.

Relations to existing shared Org/Auth records are explicit adapter mappings to verified identifiers. The first schema probe must establish actual ID types, required fields and deletion behavior. Do not silently clone a second independent organization/company directory or modify existing Org YAML to work around an unverified relation. If a stable mapping is needed, own it in `BranchPos` and enforce one active mapping per external identity and tenant.

### 2.3 Index rules that must become real constraints

At minimum qualify and create unique constraints for `(branchId, operationId)`, `(branchId, authorityEpoch, journalSequence)`, `(companyId, skuKey)`, `(companyId, barcodeNamespace, barcodeKey)`, `(branchId, loginKey)`, each active drawer assignment, active order ownership-transfer acceptance, and each source allocation consumed by stock/payment/return posting. Include `(companyId, domainId)` or `(branchId, domainId)` where needed for tenant foreign keys.

Active-only uniqueness may need a separate active-assignment table or a verified partial index; do not assume the YAML dialect expresses it. Inactive historical cash sessions must not conflict with a unique active drawer constraint. Nullable lot/serial keys need a deliberate normalized representation or separate indexes so SQL null semantics cannot permit duplicate stock positions. Application pre-checks improve messages but do not replace constraints in concurrent transactions.

Company-wide SKU/barcode publication is centralized, or uses an explicitly allocated namespace. Serial registration and supplier reference detection have the same offline limitation: local checks cannot guarantee that two disconnected branches did not enter the same arbitrary value. Route or reserve globally constrained identities where required, then keep conflicts durably visible on ingestion; never invent an earlier global guarantee or erase already recorded custody.

## 3. Persistence layout and data movement

| Persistence category | Contents | Owner and export rule |
| --- | --- | --- |
| Central directory/configuration | Company/branch profiles, public employee profiles, roles/delegations, versioned catalog/prices/policies, company allocations | Central authored; master activates a complete validated release. Public account profile is not a usable offline credential |
| Master Room operational tables | Orders, stock/custody, kitchen, collection, returns, cash, purchases, incidents, operation results, local journal/outboxes | One branch domain writer; Room transactions enforce multi-aggregate effects; synchronized evidence is not centrally mutable |
| Master protected security persistence | Credential verifiers, token hashes, throttling, local recovery credentials, protected key references | No generic YAML synchronization. Qualified encrypted recovery path only where required; raw private device keys are not copied |
| Client Room/private storage | Scoped catalog/projection cache, per-user drafts, stable pending operations/attestations, cursors | Not an authoritative ledger. Private material is session/account scoped; protected recovery records survive UI logout without exposure to the next user |
| Central immutable evidence | Committed branch operation bundles, immutable document/ledger mirrors, authority history and ingestion status | Only typed branch ingestion/correction interfaces can write; no generic CRUD mutation of accepted facts |
| Central derived projections | Company reports, search/read models, branch freshness, alert/report delivery metadata | Rebuildable from accepted evidence/configuration; distinguish a projection checkpoint from recovery completeness |
| Embedded immutable snapshots | Sold descriptions/options, quantity/UOM conversion, price/tax/discount, recipe/component/cost, policy decision, actor/approver, supplier terms | Stored with their source document, versioned and bounded; references supplement snapshots, never replace them |
| Attachment/content storage | Receipt evidence, supplier documents, product media, protected recovery chunks | Scoped references and integrity metadata; bounded streaming and resumable transfer; no raw tokens or credential exports |

Local Room entities and central YAML models need not be one-to-one. A receipt can be a bounded central document with embedded line snapshots while the master normalizes allocations for constraints and queries. Large orders, bulk imports and recovery packages must avoid unbounded arrays: use explicit child tables/collections and bounded export chunks. Do not keep a customer's lifetime of sales in one document.

Deleting or archiving a product/account must not cascade-delete financial, recipe or entitlement history. Privacy redaction is a separate permitted process preserving required accounting identity/provenance and configured evidence retention. Active local tokens are never embedded in operations, receipt JSON, analytics, audit payloads or diagnostics.

## 4. Candidate records and aggregate boundaries

The `C` marker means central-authoritative configuration/directory, `B` branch-authoritative state with central evidence/projection, `L` local-only protected/runtime state, and `E` embedded immutable structure. These markers are ownership rules, not invented YAML syntax. Repeated tenant/audit fields from section 2 are omitted from the tables for readability.

### 4.1 Directory, enrollment, administration and grants

| Candidate record | Ownership and essential fields | Constraints and guarded operations |
| --- | --- | --- |
| `CompanyPosProfile` | C: shared company reference, activity capabilities, EGP currency, locale defaults, tax identity reference, active policy release | One active profile per company; different companies under an organization do not share a catalog implicitly |
| `CompanyCatalog` | C: company, catalog identity, current publication revision | Root for company-owned products, context prices and capabilities; publication is versioned |
| `BranchProfile` | C configuration/B application: shared branch reference, timezone, business cutoff, channels, warehouse/drawer defaults | Only its master activates operational changes; applied revision returned centrally |
| `BranchUser` | C profile/B activated state: branch, login key, display name, active status, role references, optional central person reference | Exactly one branch; locally delegated creation/disable is an audited branch event; stale central state cannot reactivate an emergency-disabled account |
| `Role`, `RolePermission`, `UserRole`, `UserPolicyOverride` | C policy/B activation: actions, scopes, allow/deny, amount and percentage limits, reasons/evidence, self-approval exceptions | Explicit deny and delegation ceiling enforced; a missing ceiling is not unlimited permission |
| `ReportGrant`, `AlertRule` | C/B activation: report fields/sensitivity, branch scope, read/export/subscribe permissions, recipients/thresholds/quiet hours | Queries and delivery recheck grants; quantity access does not reveal cost/margin |
| `Device`, `DeviceEnrollment` | C enrollment/B trust state: device ID, branch, public fingerprint/key identifier, admission status, capabilities, pairing evidence | Device identity separate from user; no secret private key field in public model; pre-login procedures narrowly scoped |
| `RecoveryDelegation` | C grant/B protected activation: named branch user, permitted targets/actions, delegation revision, protected verification reference | Manager role alone insufficient; offline recovery needs previously usable local authorization |
| `ConfigurationRelease` | C: release ID, company/target branches, ordered revision/dependencies, manifest hashes, schema/protocol requirement, effective intent, authenticated origin | Staged then atomically activated; reject partial/invalid/out-of-order packages without corrupting active configuration |
| `AppliedConfiguration`, `ConfigurationConflict` | B: release ID/hash, local activated revisions, status/reason, conflicting local change | Upload actual application result; “published” and “applied at branch” are distinct |
| `AdministrativeCommand` | C intent/B result: target branch/resource, expected revision, typed action, author/grant, durable command ID | Company admin cannot forge applied branch effects; retries preserve identity and do not override conflicts |
| `BranchSetupRecord`, `ImportBatch` | C/B: environment, setup step checkpoints, validated import manifest, per-row stable source IDs, go-live actor | Repeated/resumed import does not duplicate opening stock/float; live and training roots cannot mix |

Central human IAM is reused through a qualified adapter to the existing platform. Do not invent a second cloud login/password store inside BranchPos. Local branch authentication is explicit handwritten behavior and remains available without cloud IAM. Account profiles, local credential lifecycle and central reset intent are three distinct concerns.

### 4.2 Local authentication and approvals

| Record | Required fields and rules |
| --- | --- |
| `LocalCredential` (L) | Branch/user, algorithm/version/parameters, salt/verifier or protected key reference, activation/reset state. Persistent throttling uses account/device context and survives restart. Never store plaintext PIN/password or ordinary synchronized verifiers |
| `AuthSession`, `RefreshTokenRecord`, `SessionRevocation` (L) | Session ID, branch/user, origin device, token hashes, issue/expiry/revocation state, current policy reference and rotation lineage. Default one interactive account session; service sessions separately scoped |
| `ActionApproval` (B with restricted authentication material L) | Approval ID, requestor, approver, branch, action, document/revision, canonical amount/line digest, bounds, reason, created/expiry/consumed state. Approval proof is single-use or explicitly bounded; approval does not switch collector identity |
| `PendingClientOperation` (L client) | Operation ID, originating account, canonical semantic request, pinned order/payable revision, captured external attestation, send/unknown/resolved state, result reference. Recovery access requires the original account or an explicit scoped supervisor grant |

Local access/refresh tokens are opaque random secrets with only hashes in the master registry. Protect active client secrets with the qualified platform secret store. Rotation must recover a lost reply without generating unrelated sessions; avoid dependence on a live central request. A new master re-establishes sessions, preserving users and historical actors without reviving copied bearer tokens.

### 4.3 Catalog, prices, policies and production definitions

| Candidate record | Ownership and essential fields | Integrity/command boundary |
| --- | --- | --- |
| `Product`, `ProductVariant` | C: SKU key, names/translations, category, typed attributes, sale/purchase/produce flags, variant combination, capability definition | Only valid variant combinations; archive used products; tracking/base-unit changes after use require controlled migration/new SKU |
| `ProductCapability` | C/E: stock/serial/lot/expiry mode, measurement precision, fulfillment mode, kit/recipe/service/deposit flags | Validate compatible combinations; no fractional serials, cyclic recipes/kits or exhaustion bypass by disabling tracking |
| `UnitOfMeasure`, `UnitConversion` | C: dimension, code, precision; product-specific from/to ratio and revision | Exact compatible conversion; product-specific evidenced mass/volume conversion where applicable; copy into receipt/issue snapshots |
| `Barcode`, `WeightedBarcodeRule` | C: variant, namespace/value/key, pack conversion, encoding/checksum/product/weight/price field rules | Preserve leading zeros; deterministic matching; embedded price cannot silently bypass effective company price |
| `BranchAssortment` | B under company policy: variant, local availability/status, branch restriction | Does not own another catalog or permit selling unavailable physical stock |
| `PriceContext`, `CompanyPriceRevision`, `BranchPriceOverride` | C price context/revision, B override: product/variant, channel/quantity/date conditions, exact unit amount, tax inclusion, base revision | Relevant newer company revision supersedes old branch override; unrelated product releases do not reset all overrides |
| `TaxPolicy`, `ChargeRule` | C/E: category/rates, included/excluded mode independent of rate, calculation/rounding version; service/delivery/packaging/tip bases and treatment | Pin applied snapshot; mixed inclusion supported; regulated applicability validated separately |
| `Promotion` | C: eligibility, quantity/basket bounds, fixed/percentage/bundle/BOGO rule, cap, exclusive/stacking group, priority, date/channel | Stable declared evaluation order; no unsupported global single-use promise offline |
| `PromotionAllocation` | B/E: campaign/version, concrete line/portion, original amount, allocated reduction, refundable allocation | Allocations conserve discount and payable; no re-awarding consumed units or repricing returns |
| `ReturnPolicy` | C/E: reason/category, receipt/delivery-based period, inspection, evidence, remedy deadline, legal/commercial baseline/version | Record claims and applicable rights; policy/action limit cannot erase a valid claim |
| `ModifierGroup`, `ModifierOption` | C: selection cardinality, required/exclusive groups, price and recipe substitutions | Validate actual selected recipe before reservation/send; edits to issued work create new transitions |
| `RecipeVersion`, `RecipeComponent`, `KitDefinition` | C/E: output SKU/yield, component/unit quantities, substitutes, required kitchen routes, virtual versus assembled behavior | Acyclic, exact quantity conversion; issued snapshot survives later definition changes |

Non-stock services support fixed, quantity or entered-duration charging and explicit fulfillment/refund treatment. They do not imply rental, appointment scheduling, subscriptions, digital-license fulfillment or regulated-industry modules. Product templates present these composable capabilities simply; they do not create four disconnected vertical data models.

### 4.4 Orders, ownership, preparation and fulfillment

| Aggregate/records | Required fields | Guards and effects |
| --- | --- | --- |
| `Order`, `OrderLine` | Branch, creator/current owner, revision, channel/table/pickup/delivery details, commercial state, line IDs, exact quantities, quoted snapshot and active charge allocations | Owner-filtered direct/list/subscription access; versioned mutation; paid/preparation state never inferred from one order status |
| `OrderPortion`, `PriceSnapshot` | Logical quantity slice, original line identity, fulfillment component identities, pinned UOM/price/tax/discount/recipe, preparation and fulfillment counters | Slices support partial cancellation/return/split without duplicate quantity; retail weighted slices need not create millions of per-unit rows |
| `OrderOwnershipTransfer` | Transfer ID, order/revision, previous owner, intended recipient, offer/accept/cancel state, manager exception/reason | Previous owner remains until one atomic acceptance; collection lock blocks ordinary reassignment |
| `CollectionLock` | Order/payable revision, locked total/allocations, collecting account/session or collector custody, reservation IDs, outstanding attempts | No silent repricing or release while actual collection is unresolved; allowed edits require explicit settlement/relock workflow |
| `Table`, `KitchenStation` | Branch layout/station identity, availability/assignment, routes and revisions | Table moves preserve order/preparation identity; display availability does not grant access to another owner's order |
| `KitchenTask`, `PreparationEvent` | Portion/station, sent quantity, state/revision, acknowledged start/ready/cancel actors, print/evidence reference | Any required task start means portion started; all required tasks ready means ready. Start/cancel races serialize on shared state |
| `IngredientIssue`, `IssueReversal`, `WasteRecord` | Original portion/production ID, recipe snapshot, component lot/warehouse quantities and pinned value, reason/disposition | Send issues once; verified unstarted reversal references original issue; after-start waste reclassifies cost without a second issue |
| `RemakeLink`, `QualityIncident` | Original charged portion, replacement preparation identity, cause/responsibility, approved charge/waiver and cost disposition | Merchant error does not double sold quantity/revenue; new preparation still requires actual available ingredients |
| `Fulfillment`, `DeliveryAssignment`, `FulfillmentException` | Portion quantities, handover/delivery facts, assigned collector, necessary recipient data, failed/refused/no-show reason | Delivery, customer collection and courier remittance independent; limited assignment projection |
| `ProductionBatch` | Recipe/assembly revision, sent quantities, actual started/completed yield, work in progress, waste, finished lot/expiry | Issue components on production kitchen send; release actual completed output once; selling finished SKU consumes output, never ingredients again |

Order split/merge is a transaction over the affected aggregates. Validate both owners or explicit approval, compatible company/branch/channel/tax contexts and expected revisions. Reassign exact line/portion, charge and collection allocations; retain original preparation/stock identities. Partial payment is allocated once, not copied into both children. A failed split changes neither source nor destination.

For prepaid unsent recipes, beginning collection reserves ingredients; payment completion does not issue them early. The paid-unsent reservation persists until kitchen send converts it into one issue or an explicit cancellation releases it and resolves the refund. A virtual combo retains its children's timing: recipe ingredients on send, retail component on retail fulfillment/finalization, service without fictional stock. A stocked assembled kit consumes its own output SKU once.

### 4.5 Stock, custody, counts and transfers

| Aggregate/records | Required fields | Guards and effects |
| --- | --- | --- |
| `Warehouse`, `StockLocation` | Branch, location code, sellable/quarantine/rejected/transit role | Branch-local location; receiving custody and sellable ownership are explicit |
| `StockLot`, `StockSerial` | Company SKU, lot/serial identity, receipt provenance, expiry, quality/recall state, current custody/location | Serial unique under qualified company registration; local return verifies sold serial; held/expired/blocked quantity unavailable |
| `StockPosition`, `StockCustodyPosition` | SKU/location/lot/serial/owner basis, physical quantity, reserved quantity, valuation-pool reference where owned, revision | Rebuildable checked projection; purchased inventory and supplier-owned rejected custody cannot be conflated; lot provenance is not an independent FIFO cost rule |
| `StockValuationPool` | SKU/warehouse/ownership basis, exact owned on-hand quantity and carrying value, revision; moving average derived from those totals | One valuation owner for the declared pool, including owned non-sellable custody as applicable. Supplier-owned rejected units are outside purchased inventory value |
| `StockMovement` | Stable movement ID, origin operation/source allocation, exact base delta, value delta, from/to custody, lot/serial, reason | Append-only; movement/source uniqueness; quantity and valuation effects distinguished |
| `StockReservation` | Source order/portion/transfer, exact allocations, reason/state, collection linkage, expiry policy | Reserve against actual sellable quantity; own reservation recognized when converting to issue; unknown/paid reservations cannot silently expire |
| `StockCount`, `StockAdjustment` | Captured journal cut, counted quantity, subsequent movements/recount requirement, reason/approver, derived adjustment | No direct balance overwrite; reconcile movements and reservations before posting adjustment |
| `StockTransfer`, `TransferDispatch`, `TransferReceipt` | Source/destination branch, item/unit/lot/serial snapshots, dispatched/received/short quantities, authenticated evidence and child IDs | Dispatch and receipt are separate local transactions; destination cannot receive same evidence twice; no shared cross-branch DB transaction |

Available sellable quantity equals released, unexpired, unblocked owned stock minus active reservations. Check the entire selected quantity and every component atomically. There is no manager permission to bypass insufficient stock. When a physical loss or bad count is discovered, record an incident and controlled adjustment; do not manufacture a paid sale to explain it.

The stock ledger has one quantity/cost owner per SKU/warehouse. Moving weighted-average cost is independent of lot FEFO picking. Preserve each historical issued value and the original return/reversal allocation. Finished production and assembly transfer component cost to output/work-in-progress/waste exactly once. Disassembly, where later enabled, needs a separately specified guarded workflow; generic negative production is not an implicit capability.

For ordinary stock issues, supplier-return dispatch and transfer dispatch, remove the quantity's share of the current carrying value in that SKU/warehouse pool, using the declared exact allocation and rounding rule. When issuing the pool's final on-hand units, allocate the remaining carrying value so zero on-hand quantity leaves zero inventory value. Reservations do not remove physical quantity/value until issue; pending quarantine/ownership pools remain separate. A pre-existing inconsistent residual is a controlled cost-reconciliation exception, not silently erased or pushed into a negative inventory value. Original saleable customer returns and genuine before-start issue reversals restore their referenced original issued quantity/value as specified, then update the current pool; a supplier return is not a reversal of its old purchase receipt value.

For same-company inter-branch transfers, the destination incorporates accepted quantity and its allocated dispatched carrying-value snapshot into its own weighted-average pool, plus separately approved acquisition costs if applicable. A destination does not use its selling price or an unrelated current supplier cost. Shortages remain assigned to the in-transit custody/value obligation until resolved; neither destination receipt nor acknowledgement duplicates quantity/value. Cross-company transfers require a separately defined intercompany buying/selling flow and are not ordinary stock transfers merely because the companies share an organization.

### 4.6 Sales, tenders, returns, exchanges and deposits

| Aggregate/records | Essential fields | Guards and effects |
| --- | --- | --- |
| `Sale`, `SaleLineSnapshot` | Source order/portions, original actors, immutable net/tax/gross/discount/charges, business date, configuration/rounding version | Paid sale requires coverage or authorized zero payable; supplied-but-unpaid exception is explicitly represented without a fake payment |
| `PaymentRecord`, `ExternalPaymentAttestation` | Collection intent/attempt, tender, amount, cash received/change if applicable, confirmed/not-paid/unknown state, actor, cash session/collector custody, terminal/reference/evidence | “Confirmed by cashier” is not bank authorization; no automatic external charge/retry; record actual outcome once |
| `PaymentAllocation`, `MonetaryAdjustment` | Payment/source document/portion, applied amount, reversal/correction reason, prior record reference | Allocations cannot exceed actual usable collection or target obligation; tender reclassification creates no new physical money |
| `ReturnClaim`, `Return`, `ReturnLine` | Selling branch/sale/line, actual customer receipt date, claimed date, reason/policy snapshot/deadline, accepted quantity/value, inspection/disposition | Transactional remaining entitlement; original allocation; same selling branch only; accepted return consumes entitlement before refund completion |
| `ReturnEntitlement`, `Refund`, `RefundAllocation` | Original refundable quantity/money/tender allocation, previously accepted/refunded amounts, refund due/unknown/settled and actual evidence | No second return while refund pending; method constraints and original values preserved; cash only changes on actual refund |
| `Exchange`, `ExchangeOffset` | Linked return and replacement sale, restricted allocated return value, residual collect/refund, lifecycle | Offset usable only within its exchange; never a wallet/cash movement; unavailable replacement leaves explicit refund obligation |
| `DepositEntitlement`, `DepositReturn` | Receipt/container identity, original liability/tax amount, condition, remaining refundable quantity/value, actual return/refund | Receipt-linked one-time allocation; container stock and money distinct; no spendable customer balance |
| `PaymentDispute`, `CorrectionRecord` | Original attestation/document, evidence, reconciliation decision, actual subsequent financial effect | Preserve original event and closed-shift report; no blind inventory restoration or rerun of original payment |

Cash payments store received and change for receipt evidence and one defined net cash ledger effect. Payable coverage uses net applicable collections plus an allowed restricted exchange offset. Excess external tender cannot produce fictional card change. Cash change follows configured actual cash rules. A refund obligation and its actual payout are separate even when a one-screen command records both atomically.

Return acceptance and refund recording may be separate commands, or one composite transaction when every required physical/payment fact is available. Both must consume one entitlement and create one set of effects. Use original portion-level paid allocations for partial returns; bundle allocations, taxes, service/delivery eligibility and rounding residuals must stay exact. Food refunds never rebuild ingredients from today's recipe. An inspection may create quarantined stock while a financial refund remains due.

### 4.7 Cash sessions, safe custody and incidents

| Aggregate/records | Essential fields | Guards and effects |
| --- | --- | --- |
| `BusinessDay` | Branch timezone/cutoff, operational date, open/closed status, carried obligations | Independent of login, cash sessions and fiscal document date |
| `CashDrawer`, `CashierAssignment` | Physical cash point, active session, custodian, assignment revision | One active custodian/session per drawer; default one active drawer per cashier; device move does not transfer money |
| `CashSession` | Cashier, drawer/day, opening count/source, Open/Closing/SubmittedClosed/Reviewed, closing cut and summary | Closing establishes cut; no later payment inserted below it; submitted report immutable |
| `CashMovement` | Signed net physical delta, received/change breakdown where applicable, source operation/document, custody, reason | Card confirmation creates no drawer movement; no duplicate payout from supplier settlement plus generic expense |
| `CashCount`, `CashCloseReport`, `CashReview` | Denominations/actual count, expected ledger cut, variance, disposition, reviewer and linked later explanations | Actual variance preserved, not converted into fake sales/expense or automatic employee debt |
| `CashTransfer`, `SafeReceipt`, `CollectorCustody` | Sender/receiver custody, amount, send/ack IDs, actual actors/times, unresolved state | Internal transfer not revenue; acknowledgement not a second receipt; courier remittance not a second sale |
| `UnpaidFulfillmentIncident`, `IncidentCollection`, `ApprovedWriteOff` | Supplied quantity/document, real collected amount, remaining obligation, owner, reason, later collection/write-off allocations | No restored stock or invented payment; later actual collection belongs to current custody/session and reduces incident once |

`Expected cash = opening + actual customer cash received − actual change − cash refunds + non-sale pay-ins − pay-outs − safe drops`. A net-cash posting must not subtract change twice. Opening 1,000 plus 5,000 received, less 400 change, 200 refund, plus 100 pay-in, less 150 payout and 3,000 safe drop gives 2,350. Actual 2,360 means overage 10; retaining float 500 leaves actual transfer 1,860. External card 4,000 changes none of these physical amounts.

A fulfilled check 300 with 100 collected has an incident of 200. Later collection 200 changes today's custody and settles that incident without new sold quantity, revenue or stock issue. Full statutory recognition/export must use the validated accounting/fiscal mapping for supply and subsequent settlement; absence of payment is not deletion of the supplied document.

### 4.8 Purchasing, receipts, supplier obligations and cost

| Aggregate/records | Essential fields | Guards and effects |
| --- | --- | --- |
| `Supplier`, `SupplierItem` | C directory/B activated local details: company supplier identity, references, tax/contact data, product/UOM mappings | Scoped supplier directory; local additions use delegated/preallocated identity or pending company publication |
| `PurchaseRequisition`, `ReorderProposal` | Need, SKU/quantity/date, requester, review outcome | No stock/debt/payment effect or automatic supplier commitment |
| `PurchaseOrder`, `PurchaseOrderLine` | Supplier/branch, approved revision, order/delivery/billing states, exact quantity/unit/cost/tax, terms/tolerances | Approve/amend/short-close via command; received/billed history cannot be erased by editing a line |
| `PurchaseBranchAllocation` | Central allocation ID, branch, fixed quantity/spend ceiling, revision, reserved/consumed/released progress | Master consumes only its allocation; reassigning shared capacity requires acknowledged release from prior owner |
| `GoodsReceipt`, `GoodsReceiptLine` | PO/quick-purchase basis, physical arrival, accepted/quarantined/rejected/refused/missing/excess quantities, UOM snapshot, lot/serial, ownership/value | One posting transaction; only accepted released goods become sellable; invoice does not move quantity again |
| `Inspection`, `ReceiptDiscrepancy` | Original custody/line, inspected/released quantity, result/evidence, reason and authorized resolution | Quality/pending fulfillment allocations consumed once; rejected replacement allowance cannot be issued twice |
| `SupplierInvoice`, `SupplierInvoiceLine`, `ReceiptInvoiceAllocation` | Designated posting branch, supplier original/normalized reference, claim date/value/tax, matched receipt/service allocations, disputed/unapproved amount | Local duplicate/index guards, remaining billable allocations, central duplicate conflict handling; no independent full settlement by every branch |
| `SupplierSettlement`, `SupplierSettlementAllocation`, `SupplierAdvance` | Actual payment/unknown state, posting authority, tender/evidence, invoice/advance allocation, remaining unapplied amount | Only actual cash payout or external attestation; allocation cannot exceed source funds or payable; no customer credit capability |
| `SupplierReturn`, `SupplierClaim` | Receipt/lot/serial, dispatched actual eligible quantity, book value, supplier acceptance/replacement/refund/credit state | Cannot return sold/reserved unavailable stock; rejected supplier-owned custody has different value treatment |
| `SupplierCreditAdjustment` | Invoice/claim, agreed credit/debit value, allocation/evidence | Financial effect only; replacement arrives through a new linked receipt, not both return reversal and receipt |
| `LandedCostAdjustment`, `CostAllocation` | Source charge, eligible stocked receipts, basis/version, exact allocated value, remaining/consumed value split | No quantity effect or second payable; preserve historical cost snapshot and expose adjustment separately |
| `OpeningSupplierObligation` | Migration/source reference, supplier, posting branch, amount/date/terms/evidence | Explicit opening record; do not fabricate a new purchase/receipt/payment to import an old balance |

Quick receiving is a composite UI over receipt, invoice and optional actual settlement commands with linked IDs. If executed as one local unit, define one transaction envelope and result; if staged, expose every durable stage and resume it rather than replaying the entire screen as a new purchase. Receipt-without-invoice and invoice-before-arrival are supported truthfully.

For a company invoice covering several receiving branches, reserve and acknowledge each source receipt's billing allocation with its authoritative master, then post/settle through one designated branch. This is a durable multi-step allocation workflow, not a cross-branch transaction. An unacknowledged remote allocation remains unconfirmed; it cannot be silently billed twice by two posting branches. Ordinary branch sales continue while shared purchasing coordination is pending.

Supplier credit terms, advances and payables are included operational subledgers. Customer credit sales, customer/store balances and loyalty remain excluded. An invoice 1,000 with allocated advance 200 and new cash 500 has 300 due; later credit 100 leaves 200, while today's drawer decreases only 500. Supplier purchase cost changes do not publish a sale-price change.

Supplier-return valuation example, using net amounts with no tax: receive 10 units at 20 and 10 at 30, yielding quantity 20/value 500; sell 10 at the current cost 25, leaving quantity 10/value 250. Returning those remaining 10 units, even when linked to the 30-per-unit receipt, removes carrying value 250 and leaves quantity/value zero. The supplier may owe an agreed credit of 300. Keep that claim/credit distinct from the 250 inventory departure; the 50 difference is an explicitly linked purchase-cost variance at the applicable claim/credit recognition step, with pending claim status until agreed where required. It is not a negative 50 inventory balance or a rewrite of the earlier sale's 250 cost. Persist original receipt price, dispatched carrying value and agreed supplier credit independently, with tax handled by its own original invoice/credit allocation.

### 4.9 Documents, reporting, integration and durable jobs

| Candidate record | Essential fields and restrictions |
| --- | --- |
| `ReceiptDocument`, `DocumentNumberAllocation` | Document type, stable internal ID, business/fiscal number components, branch/authority namespace, exact source snapshot, original/reprint links; no number reuse after restart/recovery |
| `FiscalDocument`, `FiscalSubmission` | Immutable source payload version, applicable company regime, legal external identity, attempt/result/status, rejection/correction references; retry identity durable; tax adapter cannot modify original sale |
| `AccountingExportBatch` | Source journal/document range, mapping/version, exact totals, export identity/acknowledgement; no duplicate postings on retry; not an implied full general ledger product |
| `PrintJob`, `PeripheralAssignment` | Committed source, job ID, printer owner/destination, payload/template version, pending/sent/uncertain/completed, explicit reprint identity; physical print is not exactly-once guaranteed |
| `AuditEntry` | Actor/action/resource and revisions, reason/approval, outcome, operation/journal references, redacted evidence; immutable append-only audit without token/credential logging |
| `ReportProjection`, `AlertOccurrence`, `DeliveryAttempt` | Source checkpoint, scope/field grants, aggregation/version, alert dedupe identity and ack/resolution; cached company totals expose stale branches |
| `AttachmentReference` | Tenant/branch/owner, purpose, hash, byte size/media type, local capture status, upload state, retention/access classification; required evidence durable before affected action |

Logical print-job deduplication cannot prove that paper did or did not emerge after a lost device reply. Require explicit operator-visible uncertainty/reprint controls, especially for kitchen tickets. Fiscal, accounting and notification jobs progress asynchronously from durable records; their failure cannot retroactively uncommit a completed local sale. Actual fiscal obligations and supported outage policy remain launch-company qualification gates.

## 5. Command envelope, transaction result and error contracts

### 5.1 Candidate transport-neutral envelope

The same command handler serves the protected in-process master adapter and P2pKit RPC. HTTPS central administration/ingestion is a separate adapter with its own identities. Names are proposed procedures, not existing viki-go routes.

```json
{
  "contractVersion": "1",
  "operationId": "018b60d4-b2aa-4db6-9810-a45fba88d825",
  "command": "payment.record",
  "resource": { "type": "Order", "id": "b6a90e6a-c9bc-4251-b664-809f749be4da", "expectedRevision": "18" },
  "authorityContext": { "branchId": "admitted-branch-id", "epoch": "4" },
  "payload": {
    "collectionLockId": "d0b92f11-c371-4fd0-9c7b-41dc8075dd56",
    "attemptId": "d8d92b54-d387-4e6e-af11-b67b78bc09823",
    "tender": "EXTERNAL_CARD",
    "amount": { "currency": "EGP", "minor": "11400" },
    "outcome": "CONFIRMED_BY_CASHIER",
    "terminalReference": "optional-external-reference"
  }
}
```

Transport authentication supplies session/device credentials outside the logged business payload. The trusted session supplies actor and effective branch; a supplied authority context is a routing/staleness assertion and must match admission. Approval ID and required related-resource revisions appear in typed payloads where relevant. Bulk/composite commands enumerate every affected expected revision and bounded item count.

The canonical semantic digest includes command/version, effective tenant/branch, stable originating actor, target revisions and normalized typed payload. Exclude transient transport request IDs, bearer tokens, current connection incarnation and the authority-routing hint: those legitimately change on reconnect or approved replacement. Preserve the original actor and original semantic content. A different person cannot adopt a pending payment merely by reusing its operation ID.

### 5.2 Execution sequence

1. Authenticate admission/session, reject incompatible contracts and bound bytes/collections before expensive work. Resolve trusted tenant/actor and require an active, non-retired writer for mutations.
2. Parse/normalize typed input and compute the versioned semantic digest. Look up `(branchId, operationId)` under authorized result-recovery rules. An existing matching command returns the stored result without repeating effects; changed payload/actor is a conflict or access denial.
3. In the controlled local writer transaction, establish the operation identity, recheck active assignment, current permission/approval, aggregate revisions and business prerequisites. This transaction resolves concurrent first submissions; a separate pre-check is insufficient.
4. Compute authoritative prices, stock/quantity/tender/cash allocations using activated configuration and required frozen snapshots. Persist all involved domain facts/projections, operation result, immutable audit, ordered journal event and durable external/print/sync work.
5. Commit durably; only then return `COMMITTED` with the original operation ID, result schema version, affected IDs/revisions, authoritative totals and journal cursor. Do not wait for the internet, optional standby, printer or fiscal provider.
6. Dispatch jobs after commit. Retry outbox work idempotently and report its independent state. A job failure does not convert committed business effects into a rejected operation.

Record deterministic business rejection results where appropriate; submitting changed business input uses a new operation ID after refreshing state. Transient infrastructure failures before a conclusive result are recoverable with the same ID. Never permanently cache “not found” as a financial outcome while an earlier handler can still commit. Query/retry must race safely through the same operation identity/transaction protocol.

### 5.3 Result/recovery statuses

| Status | Meaning and UI behavior |
| --- | --- |
| `COMMITTED` | Original durable result available; show the stored totals/document, even if the reply was recovered after a restart |
| `REJECTED` | No effects from this command; stable business error and resolution details. Do not imply an external physical payment was reversed |
| `IN_PROGRESS` | A known command is still being resolved; continue lookup with bounded backoff; do not create another charge/issue |
| `NOT_RECORDED` | Authoritative lookup currently found no committed/known record; only resubmit the same original operation safely, never infer that a bank payment failed |
| Transport `OUTCOME_UNKNOWN` | Client cannot determine the committed result; preserve original command/attestation and offer recovery |

`operation.get` returns only a caller-authorized projection. Recovery by a supervisor requires an explicit permission and audit, and never changes original actor/cash custody. Losing permission may prevent fresh work while still requiring a permitted route to resolve an earlier monetary obligation; implement that route explicitly rather than granting full cashier rights.

### 5.4 Typed error taxonomy

| Family | Representative stable codes | Required treatment |
| --- | --- | --- |
| Identity/scope | `DEVICE_NOT_ADMITTED`, `SESSION_EXPIRED`, `ACCOUNT_DISABLED`, `WRONG_BRANCH`, `PERMISSION_DENIED`, `OWNER_REQUIRED` | Fail closed; safe local reauthentication or scoped approval; no data leakage in error details |
| Concurrency/authority | `REVISION_CONFLICT`, `PAYABLE_LOCKED`, `HANDOFF_CONFLICT`, `MASTER_DRAINING`, `MASTER_RETIRED`, `STALE_AUTHORITY` | Refresh/recover the original command; do not automatically fork it under a new ID |
| Business availability | `INSUFFICIENT_STOCK`, `LOT_NOT_SELLABLE`, `INSUFFICIENT_DRAWER_CASH`, `RETURN_ENTITLEMENT_EXHAUSTED`, `PO_ALLOCATION_EXHAUSTED` | Structured permitted shortage/balance detail; no bypass flag |
| Payment/approval | `PAYMENT_UNRESOLVED`, `INSUFFICIENT_COVERAGE`, `APPROVAL_REQUIRED`, `APPROVAL_STALE`, `LIMIT_EXCEEDED` | Preserve evidence/locks; request appropriate local resolution without cloud requirement |
| Input/configuration | `INVALID_QUANTITY`, `PRECISION_UNSUPPORTED`, `INVALID_CAPABILITY`, `BARCODE_AMBIGUOUS`, `CONFIG_DEPENDENCY_MISSING`, `CONFIG_CONFLICT` | Correct precise fields or stage a valid release; healthy previous configuration remains active |
| Identity consistency | `OPERATION_PAYLOAD_MISMATCH`, `DUPLICATE_SOURCE_ALLOCATION`, `CROSS_SCOPE_REFERENCE`, `IMMUTABLE_RECORD` | Reject and audit; do not hide by generating another operation ID |
| Storage/protocol | `STORAGE_UNAVAILABLE`, `RECOVERY_REQUIRED`, `CONTRACT_UNSUPPORTED`, `SCHEMA_UNSUPPORTED`, `PAYLOAD_TOO_LARGE`, `CURSOR_EXPIRED` | Honest unknown/rejected boundary, compatible recovery or bounded re-fetch; no fabricated success |

Transport HTTP status or RPC exception class is not the business outcome. Return stable machine code, safe localized message key/arguments, relevant authorized current revision, retry/recovery guidance and correlation ID. Never return stack traces, passwords, token values or unrestricted records.

## 6. Procedure groups and read contracts

| Family | Candidate procedures and minimum proof |
| --- | --- |
| Bootstrap/auth | `system.capabilities`, `auth.login/refresh/logout/me/activate/resetCredential/revokeSession/approveAction`; encrypted pre-login allowlist, local throttling, current policy and user-switch isolation |
| Orders/ownership | `order.create/edit/park/confirm/handoff.offer/handoff.accept/reassign/split/merge`; owner checks, revisions and preserved quantity/payment history |
| Kitchen/fulfillment | `kitchen.send/start/ready/cancel.request/cancel.confirm`, `fulfillment.record`, `delivery.assign/collect/remit/resolve`, `production.send/complete`; per-quantity issue/cost/custody invariants |
| Collection/sales | `payment.begin/record/resolve`, `sale.finalize`, `order.reportUnpaid`, `incident.collect/writeOff`; immutable payable, actual attestation and exact effect count |
| Returns/deposits | `return.claim/accept`, `refund.record/resolve`, `exchange.prepare/complete/cancel`, `deposit.return`; remaining entitlement and actual residual settlement |
| Cash | `cash.open/move/transfer/receive/close.begin/close.submit/close.cancel/review`; exclusive custody, counted cut and append-only correction |
| Stock/procurement | `stock.receive/count/adjust/transfer.dispatch/transfer.receive`, `purchase.request/approve/allocate/amend/shortClose`, `receiving.inspect/release/reject`, `supplierInvoice.record/match/dispute`, `supplierSettlement.record/allocate/resolve`, `supplierReturn.dispatch/resolve`, `stockCost.allocate/correct`; separate custody, quantity, value and settlement |
| Catalog/admin | `catalog.validate/publish`, `price.setBranch`, `policy.publish`, `admin.requestBranchChange`, `config.apply`, `report.query/export`, `alert.acknowledge`; central publication versus actual branch application |
| Reliability | `operation.get`, `changes.snapshot/pull`, `sync.upload/ack`, `recovery.manifest/chunk/checkpoint`, `master.prepareTransfer/drain/seal/activate/complete/abort/stop/resume`; identity, sequence, checkpoint and lifecycle invariants |

Do not expose all table fields through one generic `query`. Define explicit read projections: own active orders; limited historical receipt lookup; kitchen station tasks; delivery assignment; quantity-only catalog/stock; cost-authorized inventory; own cash-session summary; branch management; and consolidated company reports. Apply field redaction before serialization and authorize each related record. Pagination uses stable indexed sort plus scoped cursor, count and byte limits. Search respects Unicode/localized input and preserves barcode identifiers.

`changes.snapshot` returns a consistent authorized snapshot and cut. `changes.pull` returns changes after the saved cursor, including permitted tombstones/invalidation, up to a byte/row budget. Client application of a page and its cursor is one local transaction. Notifications merely prompt pulling; missed events are repaired on reconnect/poll. A role/session change invalidates private cursors/caches; a previous cursor is not continuing authorization. Expired journal retention returns `CURSOR_EXPIRED` and a fresh-snapshot route, never an incomplete silent history.

P2pKit body constraints must be measured against the pinned library version. Historical source review found a 64 KiB default and 1 MiB ceiling; this is a design constraint to verify, not permission to configure arbitrarily huge messages. Catalog images, attachment uploads and backup data require bounded separately qualified transfer; do not assume the RPC-owned session exposes a core file-transfer API. Encrypted device admission, actual RPC availability/discovery offline, fingerprint persistence and mobile hosting behavior are explicit integration proofs.

## 7. Journal, sync, idempotency and central ingestion

Use `JournalCursor = { branchId, authorityEpoch, sequence }`. Within an epoch, increment a persistent sequence per committed branch transaction; multiple row effects belong to that transaction envelope with stable child IDs. Same-master restart resumes the same epoch/sequence. An approved new master uses a new epoch with a parent checkpoint/authority edge. Emergency recovery can expose a missing tail; it must not reuse a restored flat counter as though old/new events shared one unbroken sequence.

Keep `(branchId, operationId)` deduplication across epochs. A command committed on the old master and present in the verified recovery copy must return its original result on the replacement. An absent unreplicated operation is an explicit reconciliation case, especially if payment or physical fulfillment occurred. Epoch advancement cannot recreate unavailable data.

The branch journal stores transaction IDs, original actor, configuration/contract versions, typed immutable effects, authoritative result and content hashes. It is a recovery/replication log, not a request to re-execute business behavior. Standby/cloud replay must not rerun payment, printer, kitchen-start or stock availability logic against a different current state. Restore/rebuild derived projections from committed effects with stable IDs; live side-effect jobs retain their existing uncertain/completed state.

Central ingestion receives a bounded batch of operation envelopes with authority evidence. It authenticates branch service, validates all tenant/relationship/origin references, deduplicates by operation identity/content and durably stores the result plus central outbox/projection work. Use a tested Mongo transaction deployment where effects span documents, or another explicitly reviewed atomic evidence/commit design; current generic sequential cascade is not sufficient. A central reporting failure after evidence storage must be retried from its durable job.

Track separate cursors/statuses for branch local commit, optional standby verified durable copy, cloud immutable storage, cloud reporting projection and complete central recovery coverage. An ACK identifies the highest contiguous durably stored position per epoch/chain and any missing ranges. Do not acknowledge an in-memory receipt or merely the last numerical item in an out-of-order batch. A quarantined bundle may be durably held for review while excluded from accepted reporting/recovery; expose both states, and retain the branch copy until the relevant recovery/retention conditions are satisfied.

Same identity and content returns the existing acknowledgement; different content under the same identity is a durable conflict. Late legitimate old-epoch operations are checked against historical appointment/cutoff evidence. Events beyond a retired authority's permitted cut remain reconciliation exceptions rather than accepted competing writes. No server repricing of original sales, bank reauthorization, retroactive cashier reassignment or negative-stock re-execution occurs during ingestion.

Outbox rows include destination, event/operation identity, content reference/hash, attempt count, next-attempt time, last safe error and durable acknowledgement. Retries use bounded exponential backoff/jitter and bytes/rows backpressure; pending financial evidence is not deleted after a fixed retry count or short receipt TTL. Upload, management download, optional standby replication and projections make independent progress. Central outage, long backlog or absent standby does not gate a healthy local commit. Actual storage exhaustion is a durable-write failure and must be reported honestly.

## 8. Authority and complete recovery records

| Record | Required content |
| --- | --- |
| `MasterAssignment` | Branch, selected device identity, epoch, predecessor/authority evidence, assigned state, approving delegation and activated checkpoint |
| `MasterLifecycleState` | Active, intentionally stopped, draining, sealed/retired or recovery state; exclusive local database/runtime ownership information |
| `MasterHandover` | Stable handover ID, old/new identity and epochs, Preparing/Draining/Sealed/Activated/Completed state, target capabilities, approval, final cut and verified target acknowledgement |
| `RecoverySnapshot` | Branch/schema/protocol/authority manifests, complete snapshot cut, encrypted chunk hashes/lengths, required key envelope references, component coverage and completion verification |
| `ReplicationCheckpoint`, `StandbyReplica` | Target identity, verified durable epoch/sequence, last confirmation, gaps/integrity state, protected recovery readiness |
| `RecoveryReconciliation` | Source checkpoint, disclosed unknown/unreplicated range, affected pending operations/custody/stock/payment, authorized resolution and preserved original attribution |

Planned transfer keeps the target non-authoritative during copying, drains mutations, verifies the final cut, durably seals the old role, and only then activates the bound new identity/epoch. Abort before sealing can reactivate the old role; interruption after sealing requires handover recovery, not an automatic reversal to the old writer. Activation and lost-reply resolution are idempotent under the same handover ID.

Emergency replacement requires a named company-delegated branch user with prepared local recovery authentication and a verified independent copy. Operationally isolate/retire the old authority before enabling the replacement. A cloud epoch update cannot fence an isolated old device that never receives it; preserve isolation until controlled return installs its retirement. Discovery, heartbeat failure, IP address change and supervisor restart never confer replacement authority. Every runtime startup checks persisted role, handover state and exclusive storage ownership before accepting writes.

A complete recovery cut includes current configuration/catalog/price dependencies, local user profiles and protected credential-recovery material, roles/delegations, open/parked orders, ownership transfers, payment locks/attestations, paid-unsent reservations, preparation/issues/waste, all stock/custody/cost state, sales/return/deposit entitlements, exchange offsets, cash sessions/counts/transfers/collector money, unpaid incidents, open purchasing/inspection/invoice allocations/supplier obligations/claims, operation IDs/results, inbox/outboxes, authority/numbering state, audit and unresolved side-effect jobs. A total-sales report is not this package.

Restore into a non-authoritative target with consistent database backup/journal procedures, integrity verification and compatible schema. Never copy a live database file without its correct WAL/snapshot treatment. Use bounded resumable authenticated encryption through a qualified library; preserve protected credential usability via approved key recovery, not plaintext exports. Re-establish sessions and target identity; do not clone the previous device's private key or resurrect old bearer tokens. Retain original actor on pending work. A recovered print/refund may remain uncertain and need operator resolution; replaying all outboxes blindly is forbidden.

A branch can operate without standby. Provision recovery access and verify its central recovery route, while showing actual copy freshness/coverage. Total loss of the only device while no independent usable copy is reachable has no guaranteed immediate lossless recovery. The plan must not claim zero RPO from asynchronous uploads. This limitation does not justify changing the accepted local sale-confirmation boundary.

## 9. New YAML mapping and generator qualification

The new source file is `BranchPos.yaml` under the actual verified source directory, with namespace `BranchPos`. The generated naming/route convention must be inspected from output; do not assume a camel-case namespace mechanically produces a particular URL. Keep the old YAML hash/inventory unchanged during the new-module generation step.

Candidate central YAML families are directory/configuration, catalog/pricing/policies, public branch users/grants, enrolled public devices/authority history, administrative intent/application status, immutable operational mirrors, supplier procurement/obligations, documents/jobs, redacted audit and central ingestion/recovery manifests. Local credential/session/key tables do not belong in ordinary generic central models. A candidate mirror is not an editable form: generic create/update/delete/reset/bulk paths must be denied or unavailable for protected financial, stock, security, authority and operation records.

Generator acceptance requires a deliberately small isolated probe before authoring the full model graph:

1. Verify actual namespace casing, source path, environment/worktree output paths and all generator consumers. Avoid wrapper scripts that pull/overwrite unrelated repositories or write to developer-specific absolute paths.
2. Define representative scalars, decimal-as-string fields, bounded embedded structures, one company relation, one branch relation, inverse relation, unique/composite indexes and read-only/write-guard intent using the repository's supported dialect. Unsupported guards/indexes become explicit handwritten migrations/handlers, not guessed YAML keywords.
3. Generate runtime model configuration, typed Go documents, registry aggregation and applicable frontend artifacts. A YAML runtime file alone is not a registered writable Go model.
4. Confirm domain/storage ID mapping, nullability, wire serialization, exact numeric round trip, tenant foreign keys, index installation and migration behavior in actual local Mongo/services. Test direct IDs and alternate generic APIs, not only form validation.
5. Confirm command/ingestion write authorization. Historical source review found sequential generic cascades, tenant-filter merge hazards, audit-only/unknown-permission behavior and sync-receipt crash windows. Reproduce and fix or isolate these paths in the authorized implementation; merely using a new namespace does not make them safe.
6. Record the exact repository revisions, generated file inventory, clean-regeneration diff and runtime evidence. Only then expand model groups and map the CMP data adapters to verified contracts.

YAML does not generate Room entities/migrations, Kotlin domain transactions, local auth, P2pKit procedures, exact monetary algorithms, reliable synchronization or master recovery. Implement those explicitly. Compiled Go hooks/handlers must be registered and exercised where used; an arbitrary `hooks` field is not proof that any runtime invokes it. Candidate procedure names in this document must be bound to real registered endpoints/adapters and published contract fixtures before frontend integration.

## 10. Versioning, migrations and compatibility

Version four independent surfaces: local Room schema; command/read/RPC protocol; immutable event/snapshot format; and central YAML/Go document/configuration model set. Publish a capability manifest with supported reader/writer ranges, enabled business capabilities and body/page limits. Reject an incompatible write before beginning payment/stock mutation. Read-only degradation must still protect private data and avoid presenting an unsupported mutable workflow as usable.

Preserve old operation-result and snapshot readers for retained history and pending commands. Do not change canonical request hashing so an old retry becomes a new financial command. Keep original decimal/rounding/UOM/recipe/policy versions with historic facts. Upcast for reading/rebuilding under explicit deterministic adapters; never rewrite original signed/hashed evidence to a newer shape without retaining its original representation.

Every shipped Room version needs a tested non-destructive migration path from every supported earlier version, including pending collection, kitchen, return, cash-close, purchase and handover states. Validate fresh install separately. No destructive migration fallback for live branch or recovery storage. Unique/index changes must first discover and resolve old duplicates under a documented procedure, not discard rows to make the migration pass. Preserve required foreign keys, exact value checks and stable source IDs.

Create a verified recovery point before high-risk upgrades, drain mutations as needed and maintain persisted intentional-stop/authority states. A failed upgrade cannot automatically start two database owners or open a partially migrated store. An older binary may be unable to read a newer schema; downgrade requires a compatible restoration/migration decision, not reinstalling the old package over live data. Mixed client/master versions negotiate compatibility and block unsupported commands without changing the local authority or introducing a central checkout fallback.

## 11. Required cross-domain proof and coverage mapping

The implementation/testing plan supplies executable test layers, real-device/load targets and per-agent ownership. This blueprint supplies the semantic assertions to preserve across those layers. Every acceptance scenario remains unexecuted until it has revision/environment/result evidence.

| Scenario family | Principal aggregates/contracts | Required invariant beyond UI success |
| --- | --- | --- |
| CASH-01–12 | Cash session/custody/closing | Exact physical balance, unique custodian, atomic cut, immutable report and no invented payout |
| OWN-01–11 | Branch identity, own-order reads, handoff, split/merge | No cross-user/company/branch leakage; quantities, ownership and past collectors preserved |
| KIT-01–15 | Portions/tasks, recipe issue/reversal, prepaid reservations | No double issue, no restock after start, no expired paid reservation, no duplicate merchant-error charge |
| STK-01–09 | Positions/reservations/receipts/counts | No negative sellable stock or source duplication; exact quantity and cut reconciliation |
| PAY-01–14 | Collection locks, attestations, incidents/delivery | Local offline completion, real tender evidence, correct current custody and no repeated external action |
| RET-01–13 | Entitlements, claims, refunds, exchange offsets | Same branch, original amount/tax allocation, one remaining entitlement and truthful refund obligation |
| TAX-01–09 | Company revisions, pricing/tax/charges/fiscal/reporting | Company priority, protected snapshots, exact mixed-tax totals and reconciled source ledgers |
| REL-01–10 | Operation journal/outbox, master lifecycle/recovery | Atomic result recovery, single writer, preserved unresolved work and honest copy gaps |
| CFG-01–08 | Grants/approvals/report scope/alerts | Current policy enforcement on server/stream/cache; bound approval and no self-elevation |
| PRC-01–06 | Context prices/promotions/allocations | Deterministic rule choice, declared stacking and exact original paid/refundable amounts |
| CAT-01–10 | Capability/UOM/variant/production/deposit | Valid combinations, no component/output double count and no wallet shortcut |
| PUR-01–16 | Receipt quality/custody, invoice/payment/cost | Distinct quantity/value/debt effects, remaining allocations and no fictitious global offline uniqueness |
| UX-01–08 | Setup/import/private recovery/read projections | Training isolation, stable resumed operations and truthful state across input/navigation methods |

Add explicit contract tests for central-administration login versus local employee login in the same CMP application, wrong company shared-directory relations, actor-preserving operation recovery after switching accounts, policy revocation during a stream, composite-index race failure, command schema/hash compatibility across upgrades, and generated-ID round trips. These are requirements for the handoff in addition to the business register, not claims of new passed tests.

Valuation contract tests must include the supplier-return 250 carrying-value/300 credit/50 variance example, fractional quantity issue followed by final-unit residual allocation, zero-on-hand/value consistency, saleable customer return at original issued value into a changed average-cost pool, and transfer-in using the source's dispatched cost with a partial-receipt shortage. Assert quantity, pool value, original sale snapshot and claim/variance allocations separately before and after retries/restart.

The main agent must publish a record-to-owner matrix, checked schema/endpoint registry and exact fixture corpus before dependent agents implement independent wire models. Any later change to a shared field, command envelope, ordering rule or snapshot format requires an updated versioned contract and consumer review. No agent may bypass a protected command with generic CRUD to make a screen appear functional. Completion means the implemented state, ledgers, authorization, durable recovery and evidence all agree.
