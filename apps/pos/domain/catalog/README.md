# Catalog definition and price-selection foundation

**LOCAL ONLY.** Source of requirements: `../../../ukkera-pos-agent-handoff-2026-10-21/`
(`POS_COMPANY_POLICIES_AND_CATALOG.md` §§1,3,6; domain blueprint; CAT/PRC acceptance cases)
and `../../../ukkera-pos-plan-review/FINAL-PLAN.md` §7.

This module is a pure, bounded **definition/validation/new-line selection** package. A
`ValidatedCompanyCatalog` is not a published company catalog; a `CatalogActivationPlan`
is not durable activation, an authorization, stock availability, or a reservation.
No catalog operation writes storage, reads a clock, sends notifications, posts money,
or executes a fulfillment/inventory workflow.

## Boundaries and immutable inputs

Dependencies are `core:types` and the existing scope/action/limit types in `core:security`.
There is no pricing, inventory, sales, or UI dependency. No second permission evaluator,
tax calculator, or promotion engine is introduced.

* `CompanyCatalogDefinition` holds company-scoped products, explicit SKU combinations,
  typed non-executable attributes, units, product-specific conversion/composition versions,
  barcode namespaces, tax-category identities, price versions and explicit active pointers.
* `CompanyCatalogPolicy` explicitly selects a price profile, one standard context, sales
  channels, optional enabled contexts/channel prices, and optional action declarations.
* `BranchCatalogDefinition`/`BranchCatalogPolicy` bind the exact received company catalog
  and policy, assortment, explicitly enabled capabilities, optional declarations, current
  overrides and inactive immutable `historicalOverrides`.
* All collections are bounded **before** copying. Private read-only collection wrappers
  prevent mutation through retained input lists, iterators, or mutable-list casts. Child
  definitions are immutable as well. `CatalogBounds` are configurable trusted engineering
  capacities, not business limits or client-controlled ways to enlarge server budgets.

`CompanyCatalogValidator.validate(definition, history, previous)` validates local consistency,
version lineage, identities, references, current component cycles, dimensions and price
ambiguities. The authoritative, complete history and previous version must come from the
same serialized activation transaction. A caller-supplied `complete=true` is not evidence.
Unknown, stale, or differently scoped history fails rather than permitting identity changes.

`BranchCatalogValidator.planActivation(...)` additionally requires an exact company
publication reference, an explicit branch assortment/capability declaration and a supplied
execution-support manifest. The trusted integration owner must establish publication and
manifest evidence; IDs/enums alone cannot do so. Arbitrary offline company-global code
creation stays pending company publication. A preallocated-namespace publication adapter
is not implemented. Every enabled sale UOM needs a standard fallback **at the supplied
activation instant**. This is not qualification of gap-free future schedules.

## Named price profile (explicit delegated design choice)

`PRIORITY_FIRST_V1` is an opt-in technical profile approved for this bounded implementation,
not an assertion that the original prose universally fixed all precedence relationships.
The owner may revise the profile before a real activation.

1. Determine eligibility: explicit active version/context, SKU/product/flat-category target,
   admitted channel, half-open UTC period, and exact UOM/quantity interval.
2. Rank eligible rules by explicit priority descending.
3. Then SKU > product > flat category > standard target specificity.
4. Then channel-specific over channel fallback.
5. Then highest qualifying minimum quantity.
6. Finally canonical lowercase rule UUID ascending.

**Period start is only eligibility, never precedence/revision.** There is no category
hierarchy, floating point, implicit price conversion, latest-row choice, or database-order
tie-break. Standard context is channel-independent; merely adding a channel rule does not
enable channel-specific pricing.

Equal pre-ID rank, overlapping targets/UOM/channel/quantity/time, and incompatible exact
unit-price/tax-inclusion/tax-category terms reject company validation. Diagnostics include
both competing immutable rules and a concrete SKU/channel/quantity/UTC witness. Identical
economics may tie by stable ID. Conflict checking is conservative even if another rule
would dominate the conflict; operators must resolve the incompatible overlap before activation.
All structural and overlap work must fit explicit engineering budgets; partial validation
is never accepted.

### Company/branch revision fence

`CompanyPriceReference(company, skuId, context: CatalogVersionRef, rule: CatalogVersionRef)`
is the exact relevant base. Company publication revision is diagnostic, **not** this fence.
The company rule is selected first. An enabled, in-period branch override applies only if
its entire base reference equals that selected company reference. Tax inclusion/category
remain the company's terms; this override type changes only the unit amount.

The required fixture is encoded in tests: company100/r5 → branch120 based on r5 →
company150/r6 produces150, explaining the superseded override. An unrelated product or
context publication preserves a still-matching override. Old/offline override records may
remain inert in a loaded snapshot; they cannot win by a larger branch revision or timestamp.
Overlapping current overrides are rejected, not sorted into a winner.

Current and historical branch overrides form an append-only version registry across branch
revisions, unique by `(override ID, revision)`. Replacing/retiring a current record retains its
immutable scope/base/price/period in history. A current override cannot fall below the highest
retained revision of that ID; removing O/r2 and reintroducing O/r1 cannot reset the high-water
mark or rewrite an old snapshot reference. Historical records are not candidates for selection.
Explicit reactivation of the **identical latest retained version** remains possible when the
company base/policy/revision checks pass and the real gateway authorizes it; retirement is not
an invented irrevocable ban. Older-than-high-water activation still constitutes rollback.
The combined current/history size is bounded before copying, and history checking consumes the
explicit validation-work budget. The protected adapter must load the actual complete prior
branch registry in the same transaction: clients cannot omit it, replace it with a partial
history, or supply `previousBranch=null` to pretend that an existing branch is new.

`BranchPriceOverrideAdmission.checkEdit(plan, expectedBranchRevision, proposal)` explicitly
rejects stale company bases, stale branch edits, in-place revision rewrites, overlap, disabled
override policy and missing required action/grant/limit fields. `ReadyForAuthorization` means
only the definition is ready for the **existing** security gateway. The gateway still resolves
real grants and delegated ceilings, determines the authoritative `LimitUse` (no invented
per-unit/basket ceiling interpretation here), checks current scope/actor/approval/revocation,
and admits the edit atomically. Snapshot validation alone is not edit admission or authorization.

Company price/context/conversion/composition version registries are also append-only in this foundation.
Changing a current pointer archives a version; it does not delete evidence. Current price
and context pointers cannot select an older retained revision. Staged newer price/context
versions belong in an external draft until activation, not alongside an older active version
in this active-catalog representation. A protected archive/compaction workflow is not implemented.

## Exact public selection/adapter boundary

```kotlin
CatalogPriceSelector.preview(
    plan: CatalogActivationPlan,
    query: CatalogPriceQuery, // skuId, Code channel, Quantity, caller-supplied UtcInstant
): CatalogPricePreview
```

Preview has a typed `Selected`/`Unavailable` outcome, ranked company candidates with
ineligibility reasons, and applied/superseded/ineligible branch-override explanations.
`SelectedCatalogPrice` contains:

* exact company and branch scope, company catalog/policy revision;
* product/SKU/category IDs, versioned context and full `CompanyPriceReference`;
* separate optional branch override version (never collapsed into the company's revision);
* exact price-UOM `quantity`, stock `baseQuantity`, and full optional conversion snapshot;
* `UnitPrice`, `CatalogTaxInclusion.INCLUSIVE/EXCLUSIVE`, and tax-category ID.

The pricing adapter supplies/pins the tax policy/rate and maps this to its existing resolved
line and per-portion facts. Promotion selection consumes already-resolved prices; it does
not perform another catalog lookup. Stock uses the preserved base conversion, not a guessed
price-UOM quantity. Price and tax/promotions/authorization snapshots remain distinct domains.
Protected admin previews need protected-query authorization/projection by their caller.

Selection concerns **new lines only**. It never rewrites protected, customer-committed,
quoted, collection-locked, partially paid, posted, or returned lines. Previously returned
snapshot objects remain immutable. There is no purchase-cost field/path that can publish
a selling price, and no automatic repricing API.

## Catalog capabilities and honest execution limits

* Variants enumerate exact allowed axis/value combinations; no Cartesian SKU generation.
  SKU keys are company-unique, including archived identities. Typed attributes cannot execute
  arbitrary stock, payment, pricing, or regulatory behavior.
* Barcode identity is `(company, explicit namespace, exact key)`, preserving leading zeros.
  Namespace is mandatory. Supported literal formats are exact text, EAN-8, EAN-13, UPC-A;
  named GTIN formats validate digits/length/checksum. Literal control/whitespace terminators
  are rejected, not trimmed. No UPC/EAN numerical equivalence, inferred namespace, weighted
  barcode decoding, embedded-price override or measurement guess exists.
* Exact conversion is product-bound and directly source→base (no inferred graph/inverse).
  Measured precision is explicit; impossible precision/overflow fails without rounding.
  Cross-dimension conversion supports only explicitly evidenced, product-specific mass↔volume.
  Evidence references require real upstream verification. Universal kg↔litre and other
  mixed-dimension conversions are not supported. Sale increments must convert exactly.
* Serial tracking uses whole count units even with nonzero quantity snapshot precision.
  Fractional serial inputs/tier boundaries/pack increments fail. Expiry requires lots.
* Fixed selected recipe/kit components are bounded, dimensionally valid, acyclic on the current
  graph, and use retained exact conversion versions. Modifiers, substitutions and configurable
  choice execution are not implemented. Historical composition versions are not spliced into
  today's graph. Recipe coefficients are validated, not arbitrary future production quantities.
* Made-to-order describes component issue on kitchen send. Prepared batches describe issue on
  production send, **actual** yield posting and sale of finished stock once. Virtual kits have
  no parent stock and preserve retail/kitchen/service component timing. Assembled kits consume
  components at assembly and parent stock at sale, never components a second time.
* Services declare fixed/quantity/entered-duration charging, separate completion, and no stock.
  Reusable containers optionally declare an explicit tax/accounting mapping and receipt-linked
  deposit liability. No balance, credit, wallet, loyalty, gift-card, appointment, subscription,
  rental, or regulated-industry execution is implied.
* A selected capability requires explicit branch enablement and a supplied qualified execution
  manifest. Merely declaring metadata cannot activate unsupported workflows. Purchasability
  alone does not force an unrelated purchasing setup for routine sale selection; actually
  enabling branch purchasing does require its explicit execution support.

After use, product/SKU/key/variant, base unit/precision, stock tracking, expiry, measurement and
fulfillment identity cannot casually change. Used barcodes cannot be deleted/repointed/reused;
new aliases are allowed. Referenced products/SKUs/units/versions remain available to history.
Archive rather than delete. Controlled identity/balance migration is not implemented: use a
new identity and an independently authorized workflow. New pack/recipe versions can affect
future snapshots while a prior 2×12 receipt still means24, not20 after a pack becomes10.

Optional action declarations reuse `Permission`, `SecurityAction`, `RequiredLimits` and
`PermissionLimits`. Missing mandatory fields fail when that action is requested; unrelated
optional gaps do not block ordinary selection. Explicit no-monetary-ceiling is representable
as a definition, but existing security limit execution cannot represent it: mapping/edit
admission fail explicitly instead of passing null as unlimited authority.

## Verification status

**Current lead verification:** 75 JVM tests and75 Android-host tests passed with zero
failures/errors/skips, including8 branch-history regressions; both iOS target Klibs compiled.
Log: `.workspace/verify-db-catalog-cash-current-20261023.log` (repository root).
Independent read-only review checked the history correction; the helper ran no builds.
The earlier67-case reports remain a historical snapshot. Synthetic manifest/publication
inputs test pure validation only, not real workflow execution.

Requested lead commands: `:domain:catalog:jvmTest`, `:domain:catalog:testAndroidHostTest`,
`:domain:catalog:compileKotlinIosArm64`, `:domain:catalog:compileKotlinIosSimulatorArm64`.
Klib compilation is not iOS runtime qualification. No serializers, storage adapters, publication
transport/registry, real branch activation, live gateway, platform/hardware/fiscal integration,
or production acceptance are provided by this module. The current product has one bounded
`Code` name: localized-name maps, shared-default inheritance, bulk/import/wizard UX and the
remaining BLUE §4.3/CAT-01 product-creation behavior are residual scope, not completed acceptance.
