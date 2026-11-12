# Local restaurant/café configuration

27 October 2026. **Connected, compiled source; not independently qualified food
operation, physical payment or device acceptance.**

## Actual application path

The Catalog workspace uses `LocalEndpointCompanyCatalogGateway.foodConfiguration`
and the same original native company owner, employee session and durable client
journal. It does not create unsigned recipes or use a second in-memory catalog.

1. Propose the ingredient/output SKUs through the existing company catalog form.
   Raw ingredients are purchasable; made-to-order output is quantity-tracked,
   non-purchasable and genuinely signed at SKU revision1. Existing stocked retail
   publications retain their exact revision0 representation.
2. Select actual paged SKU records, quantities and one complete recipe choice.
   Review the exact retained recipe, then authenticate the separate company head.
   The genuine `FOOD_RECIPE_V1` publication is signed by the native owner.
3. Stage branch activation separately under the current employee permission.
   Signing alone neither activates the recipe nor receives ingredients or money.
4. Create/rename stations and select the active warehouse and every route target.
   These ordinary branch commands use protected, revision-bound selections and
   the original client intent → submit/lookup → explicit ACK lifecycle.

The read handler supplies protected bounded SKU/recipe/station/route pages and
exact-ID details. UI labels are not authority. Preparation rechecks selected
sources and complete route coverage; an immutable staged request is never
rewritten from a later selection. Lock/navigation discards private presentation,
not native publications or uncertain journal work. Failed initial reads remain
visible until explicit refresh; sibling busy changes cannot trigger retry loops.

## Compatibility and limits

- Existing retail publication, activation and saved-summary bytes remain intact.
  Old binaries fail closed on the new recipe publication kind; no downgrade
  qualification is claimed.
- Current recipe editor: one choice, no modifiers,1–16 ingredients and one route
  key. This is a finite entry template, not removal of the full recipe/menu scope.
- Station mode changes/retirement require outstanding-output reconciliation and
  remain refused here. PAPER routing is not a printer/receipt implementation.
- Ingredient stocking, actual order/send/kitchen/fulfillment/payment, multi-actor
  history and independent failure recovery remain separate required workflows.

## Ownership and checks

Food implementation: `/root/local_purchasing_runtime_delivery`; integration,
cursor compile correction and sibling refresh fix: `/root`. No tests or expected
outcomes were authored/changed by these implementers. Source3521bb4b… compiles for
gateway JVM, Android and Desktop and passes601 existing regressions; these do not
qualify the new food workflow. [Exact source/evidence and original requirement
coverage](../../qa/pos/traceability/food-cash-runtime-20261027/README.md).
