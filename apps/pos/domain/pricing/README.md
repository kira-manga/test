# Exact operational pricing — bounded T12 child

Authority: original `../ukkera-pos-agent-handoff-2026-10-21` (relative to the
repository root), BLUE §§2/4, OPS §§3/10, POL §§3/4/5, QA §6 and
`../ukkera-pos-plan-review/FINAL-PLAN.md` §7 / finding F07. Original files are
unchanged. This package is local-only implementation, not full T12 completion,
independent approval, fiscal certification, a durable sale or permission to collect.

## Frozen supported profile

`linear-protected-components-half-up-lr/v2`, EGP (two minor-unit digits):

1. The caller supplies **resolved immutable** unit/price revision, concrete
   line/portion quantity, independent inclusion/category/rate snapshots and
   explicitly selected charge eligibility. Quantity has its original unit and
   scale (0–6); unit price has at most eight fractional digits. Zero price and
   zero tax are permitted; quantity must be positive. No binary floating point.
2. Multiply quantity by unit price exactly. For inclusive prices, divide by
   `1 + itemTaxRate`; exclusive prices are already item net. A rate is an explicit
   typed percentage, never inferred from a category or an inclusion checkbox.
3. Apply the **explicit ordered** resolved item-promotion adjustments, then
   order/manual adjustments. Reusing an adjusted portion requires explicit
   stacking. Selection must have already frozen concrete consumed portions.
4. Each percentage charge is a nonnegative linear percentage of its eligible
   **discounted item net**, with a separately pinned simple tax percentage.
   Multiple charges can overlap. Fixed charges may be inclusive or exclusive;
   they are independent of item discount and are not discountable here. There is
   no arbitrary 100% tax/service limit and no invented statutory rate; scalar
   resource and signed-64-bit posted bounds remain enforced.
5. Establish the undiscounted posting by rounding complete nonnegative document
   payable **once, half-up**, then floor its exact component quotas and assign
   remaining cents by largest exact remainders. Ties use canonical ASCII
   portion/charge/component keys, not input order or localized labels.
6. For each adjustment, derive the exact discounted document and its half-up
   target. **Freeze** every exact-unaffected posting (including excluded portions
   and fixed charges). Bound each changed posting between its exact floor and
   `min(exact ceil, previous posted amount)`. First retain cents required by each
   portion's cumulative original posted amount cap / current scope amount cap,
   using largest remainders and stable ties within that group. Then allocate the
   remaining target cents by largest remainder among candidates with room.
   Disjoint portion groups make this a bounded greedy allocation, not a global
   campaign optimizer. If the target cannot satisfy every bound, reject it before
   collection. Never move an excluded cent or silently change the requested amount.
   All item/charge net/tax postings sum to the document and none increases or goes
   negative. Fixed charges keep independent identities, not artificial quantities.

The previous experimental `linear-document-half-up-lr/v1` is **rejected**. Its
unconstrained before/after Hamilton allocation could transfer a posted discount
to an ineligible component; a separate signed explanation did not fix eligibility.

Input bounds: at most 256 portions, 32 charge rules, 32 adjustments and 10,000
component postings. `core/types` limits exact rational numerator and denominator
to 1,024 decimal digits each and rejects greater reduced intermediates. Price,
quantity and rates retain their scalar bounds. Overflow is an error before any
result is returned, including a pre-discount total that would overflow even when
the final discounted total would fit. Empty baskets yield a zero snapshot.

### Gross discounts, caps and the posting boundary

For eligible portion `i`, the marginal payable factor is

```
f_i = 1 + itemTax_i + Σ(applicableChargeRate * (1 + chargeTaxRate))
```

Allocate the exact gross reduction proportionally to current eligible marginal
gross (`net_i * f_i`), saturating explicit capacities by exact water filling.
Divide each allocated gross reduction by `f_i` to reduce item net, then derive
its dependent tax/charges. Never subtract the gross customer reduction from net
and never subtract it again from already-discounted final components.

Both amount and percentage limits are required at the line, cumulative basket
and adjustment levels. Default line/basket permission is **zero**, not unlimited.
Line/basket percentage caps use their original marginal gross; their remaining
exact capacity is consumed across every adjustment. **Actual posted** amount caps
also bind, cumulatively from original posted allocations rather than refreshed
rounded bases. Per-adjustment percentage caps and
percentage discounts use the **current** scoped marginal gross. Optional scope
caps can further restrict portions; they cannot enlarge a line/basket allowance.
The exact advertised amount is rejected above its necessary capacity upper bound
or when its particular posting is infeasible, not silently changed. A percentage request exceeding a cap is also rejected rather than
quietly converted to a lower percentage. These are calculation limits, not proof
of authorization or of a branch campaign-right reservation.

Let `B` be current exact document payable, `C` current eligible exact capacity,
and `post` nonnegative half-up to integer cents. A **necessary capacity upper
bound** is `post(B) - post(B-C)`, **not** `floor(C)` or `ceil(C)` in cents. Intersect
it with actual remaining posted line/basket/adjustment/scope amount capacities.
For a requested integer-cent reduction `D` within this bound, choose exact
reduction `R = min(D/100, C)`, and verify `post(B)-post(B-R) == D`. Then independently
check the constrained component allocation. Capacity is not posting feasibility.

The document target is exact: translation by integer cents preserves nonnegative half-up. If
`D/100 > C`, the maximum is at most `ceil(100*C) <= D`, hence it must equal `D`;
depleting the fractional cap achieves the requested document target. For example `.006`
EGP alone posts one cent and can be reduced to zero; `.006` eligible plus `.006`
unaffected fixed charge posts one cent both before/after depletion and permits
**zero** cents of advertised discount. Snapshots preserve exact capacity, bases,
exact reductions and the distinct posted customer reduction.

Feasibility is **not monotone**: before minor-unit exact values `(1.6,1.6,.7,.7,.7)`
post `(1,1,1,1,1)`. Discounting only the first two by one cent makes their exact
amounts `(1.1,1.1)`; their floors plus three frozen cents exceed the target, so one
cent rejects. A two-cent discount makes them `(.6,.6)`, allowing `(0,0,1,1,1)`.
No binary search, threshold assumption or unchecked "Max discount" button is safe.
`capacityPayableReductionUpperBound` is explicitly **unqualified**;
`maximumPayableReduction` is null unless the trivial zero maximum is proved.
Every requested amount must pass its own feasibility check. No alternative net
discount optimizer silently replaces this profile's proportional exact allocation.

`advertisedReduction` and `postingDelta` are the **same actual nonnegative**
before-minus-after posted component amount; they are zero on unaffected/excluded
components and sum to the requested document reduction. `roundingDifference`
records posted reduction minus exact reduction; it is not another posting.
Both exact caps and actual posted amount caps apply. Do not subtract allocations
again from already-discounted final components. Original **final posted** amounts
are the refund entitlement source. This constrained operational profile does not
claim independently rounded line tax or statutory tax coupling; those need a
separately qualified compatible profile before the affected company's launch.

`validateSupportedProfile(profile, taxPostingPolicy)` is the publication/collection
guard. Only `OPERATIONAL_COMPONENT_ALLOCATION` is supported;
`INDEPENDENT_LINE_HALF_UP` and `COUPLED_FISCAL_ADAPTER` reject rather than quietly
falling back. Hypothetical coupling regression, in **minor units**: item net
`3.6`, item tax `.504`, and two excluded fixed charges `10.3` each initially post
`(4,1,10,10)` = `25`. A one-cent gross reduction makes exact net `776/285`
and exact tax `2716/7125`, for document target `24`. Protected operational posting
is `(3,1,10,10)`. Independent line half-up would demand tax `0`, which cannot
satisfy that target with the frozen charges and permitted item-net quota. This
test proves rejection of that unsupported policy, not a qualified fiscal adapter.

Hypothetical regression (not a statutory tax determination): inclusive 114 plus
exclusive 100 at 14%, with 10% net service taxed 14%, gives
`200 + 28 + 20 + 2.80 = 250.80`. Fixed payable reduction 25.08 gives
`180 + 25.20 + 18 + 2.52 = 225.72`.

## Bounded promotion selection — explicit opt-in, not full T12

`PromotionSelector.quote(PromotionSelectionRequest): PromotionQuote` implements
`group-ordered-greedy-actual-posted/v1`. The profile and its rule interpretation
tags are required explicit configuration, **not inferred business defaults**.
The existing V2 calculator, snapshot codec and return algorithm are unchanged.

Inputs are resolved catalog-priced portions, separate SKU/category facts, explicit
group order, revision-pinned rules, UTC/channel/exact-code context, one caller-
generated retry-stable application UUID per rule, and ordered manual adjustments.
No portion splitting, UUID generation, catalog lookup, UOM conversion, clock read
or stock creation occurs. Bundle/buy-get applicants must already be distinct
**one price-UOM unit** portions with their original IDs. A frozen available
quantity must cover each supplied sold portion, in its original unit and scale;
this is a calculation check, **not** a reservation or a concurrency guarantee.
The stock adapter must also aggregate portions sharing a SKU/location/lot pool;
repeating that pool's availability on every portion does not prove aggregate stock.
Catalog company/context/rule/override/conversion provenance must remain in the
trusted adapter's committed catalog snapshot, not collapse into one price revision.

The deterministic selection order is:

1. Process the caller's explicit item-group order, then bill-group order.
2. For each unused rule in the group, match currently usable portions, resolve its
   complete concrete cohort and thresholds, and append that candidate alone to
   the already selected adjustment prefix. Every trial uses V2, including its
   caps, dependent charges, eligibility protection and posting feasibility.
   Bundle/buy-get trials additionally check their promised per-portion paid spread,
   not only the cohort total; an incompatible V2 result rejects the quote.
3. Choose the greatest **actual posted** customer reduction among current single
   candidates, then higher configured rule priority, then canonical ASCII rule ID.
4. Consume all qualifier **and** reward portions, even when reduction is zero.
   Reconsider unused candidates on the remaining portions. Partial overlaps are
   supported: scoped discounts/tiers use their remaining matching cohort;
   bundle/buy-get candidates need complete remaining counts. Each rule can apply
   at most once in this explicitly single-application profile.

This is deliberately **not** a globally best set selection: A consuming `{1,2}`
for 10 beats either B consuming `{1}` for 6 or C consuming `{2}` for 6, so greedy
chooses 10 rather than the combined alternative 12. A regression test and the
returned `selectionExplanation` make this tradeoff explicit. Do not market its
result as the greatest possible combined discount. Group order is semantic;
reordering rules, facts, requirements or price portions is not.

Same-group consumption is exclusive. Cross-group reuse needs mutual configured
compatibility **and** explicit `PromotionCalculationGrants` for every affected
pair. Following manual discounts need configured permission, a supplied group
calculation grant and `StackingPermission.EXPLICITLY_ALLOWED`. These values are
**not authorization evidence**. A transactional adapter must bind real permissions,
approvals, actor/scope and complete configuration/quantity/quote digests, revalidate
stock and revisions before locking/collection, and atomically commit its effects.
Actual posted and exact V2 line/basket/adjustment caps still apply to every trial.

Supported opt-in rule interpretations:

| Rule | Bounded meaning |
| --- | --- |
| Fixed/percentage item or bill scope | Apply once over the matching concrete cohort; fixed value is a gross reduction, not silently multiplied by quantity. Bill groups follow item groups. |
| Quantity tiers | Same-UOM exact quantities, distinct increasing thresholds; highest qualified tier applies to all matching remaining units. A fixed tier benefit is one aggregate reduction, not a fixed unit price or per-unit multiplication. No progressive/marginal-tier interpretation. |
| Fixed-price bundle | One complete explicit SKU/unit/count list; choose existing units by stable portion ID. Target is the cohort's posted gross **including dependent percentage charges**, excluding independent fixed charges. |
| Buy-X-get-Y | One same-SKU, equal-current-marginal-gross cohort; pay-for-X exact gross is half-up posted to the declared cohort target. The target is spread over all X+Y units by original gross weights, never by designating a hidden zero-refundable reward. Mixed-price/cheapest-item pairing is unsupported. |

At **each bundle/buy-get selection**, the required paid allocation is the posted
target multiplied by each portion's **exact original marginal gross** divided by
the cohort's original gross, with largest-remainder cents and canonical portion-ID
ties. All-zero original cohorts admit only a zero target. V2 must produce exactly
that per-portion posting while retaining its own caps and component protections;
the selector never overwrites or renormalizes V2 output. For equal 100/100 portions
and target 100, a zero first-line discount cap would let generic V2 water filling
produce 100/0. The promotion instead rejects with `PAID_ALLOCATION_NOT_SUPPORTED`.
A first-line cap of 50 and an unlimited second cap permits the promised 50/50.
Prior unequal discounts or component rounding can likewise make this bounded
target-spread profile unsupported even though V2 can post the cohort total.

`SelectedPromotion.targetPaidAllocations` pins these **selection-time** weights
and paid values. Separately permitted later automatic/manual discounts retain their
actual V2 deltas: an explicitly authorized later 10 discount only on the first unit
can change 50/50 to **40/50**. `paidPortions` and stored return cursors use the **final**
historical paid allocation, not the earlier target or a new 45/45 redistribution.
Independent/conditional charge-refund decisions remain separate. Zero allocations
caused by an original zero price or deterministic minor-unit rounding are not an
invented free-item refund policy and must still be displayed honestly.

SKU/category inclusion is an explicit union; SKU exclusions win. Optional minimum
spend uses **exact original cohort marginal gross**, not a rounded/current-catalog
amount or independent fixed fees. Minimum/tier quantities cannot add unlike UOMs;
exactly comparable scales of the same UOM are supported. Channels and campaign
codes use core `Code` with its explicit exact normalization. Time eligibility is
an explicitly opted-in UTC half-open interval `[from, until)`; recurring local/DST
campaign schedules are not silently inferred. Bundle/buy-get rules pin a receipt
disclosure and original-paid-across-all-consumed-portions treatment. Receipt/legal
adapters still must disclose actual paid allocations and preserve applicable rights.

Unsupported configuration rejects even if it would currently be ineligible.
An otherwise eligible candidate whose promised discount is over cap or cannot be
posted by V2 **blocks the quote**, rather than silently disappearing in favor of
another offer or an undiscounted collection. Negative-benefit bundle targets also
reject. No maximum-discount binary search or feasibility-threshold assumption is used.

Bounds: 16 rules/groups, 64 priced portions, 136 candidate trials, and at most 32
configured automatic rules plus manual adjustments. Filter sets, category facts,
requirements and tiers have explicit bounded counts. All traces and retained
collections are defensively copied and mutation-resistant. Trace entries record
each round's candidate cohort, original basis, selected tier, actual posted
comparison, winner/tie reason or explicit ineligibility; final paid allocations
link to the V2 snapshot and distinguish automatic refund entitlement from charges
requiring a separate performance decision.

`PromotionQuote` is an immutable calculated preview, not a durable record. Its
full rules, context, calculation inputs and selection trace need a future reviewed
storage/transaction adapter; **the pricing-only codec does not preserve this new
selection evidence**. It does preserve final paid/tax/charge allocations for
original-value return arithmetic. Discarding selection/disclosure provenance and
storing only that pricing string is not a complete promotion-aware sale integration.

## Protected output and partial returns

`OperationalPricingEngine.calculate(PricingRequest): PricingSnapshot` returns a
factory-only value with defensively copied, mutation-resistant lists. The output
pins the algorithm, price/tax/charge snapshots, eligibility/limits, exact before
and after components, posting allocations, each adjustment's reconciliation and
final quantity/portion allocations. There is no mutable catalog lookup in it.

### Canonical stored snapshot — schema 1

`StoredPricingSnapshotCodec.encode(snapshot): String` and `decode(encoded):
PricingSnapshot` preserve the complete snapshot, including the original before
posting, ordered discount chain, final allocations and their pinned inputs.
The envelope uses canonical scalar strings and canonical JSON field/array order;
unknown schema/profile/fiscal policy, extra or duplicate fields, alternate number
representations and noncanonical JSON reject. There is no public unchecked whole
snapshot restore constructor. The surrounding sale codec must retain this string
unchanged, not convert its money to JSON numbers or rebuild a quote from a catalog.

Decode verifies historical quantity/price/net equations, pinned tax and charge
relationships, exact/posted conservation, original largest-remainder ties,
discount order/stacking/caps, proportional water filling and protected after
postings. It returns the **stored values unchanged**; it never calls `calculate`,
selects current promotions, or reads today's catalog/rules. Version support is
explicitly pinned. Future changes need reviewed compatibility/migration handling;
an unknown historical algorithm cannot be relabeled as the current one.

Storage has its own fail-closed resource boundary: at most 8 MiB of characters
**and** UTF-8 bytes, 16,384 total line/charge/component/adjustment/scope/allocation
records, 10,000 entries in any JSON array, and at most 24 container levels. A
conservative character budget runs before encoding, so some arithmetically valid
large snapshots can reject storage rather than allocate unbounded JSON. Callers
must validate/encode before collection and abort the surrounding transaction on
failure. An enclosing sale envelope may impose an additional stricter limit.

Canonical validation is **not** a signature, caller authorization, durable commit,
fiscal approval or proof that all pinned inputs were legitimately selected.
The caller still owns the atomic sale/result/effects/audit/outbox commit; storing
only document totals or catalog IDs is insufficient. No Room transaction is
implemented by this pure module.

### Stored partial-return arithmetic

`snapshot.openReturnCursor(portionId, explicitlyRefundableChargeIds)` constructs
an immutable cursor from original **final posted** component amounts. For
`stored-component-cumulative-floor/v1`, each component's cumulative consumption is

```
floor(originalComponentMinor * cumulativeAcceptedQuantity / originalQuantity)
```

The difference from previously consumed quantity is the current slice. It is
monotone, partition-independent, never exceeds the original component, and the
final portion consumes all remaining cents. It intentionally does not run a new
largest-remainder allocation for each changing return quantity (which can have
the Alabama paradox/non-monotone component consumption). Intermediate partial
return totals can differ from half-up of a proportional document total; the
stored return profile is explicit and must be disclosed/legally qualified where
applicable. No present-day price, campaign or tax enters this calculation.

Item net/tax and `PROPORTIONAL_WITH_ITEMS` percentage charges are included.
`EXPLICIT_ONLY` percentage charges require an explicit caller-authorized
performance/reason decision; `NEVER` charges cannot be selected. Independent fixed
delivery/packaging/service charges are **not** automatically reversed on item
return: sales must keep a separate original one-time charge entitlement and
authorized unperformed-service decision. An omitted charge is not erased.

Sales owns selling-branch validation, original tender allocation, inspection,
concurrency/revision checks, durable entitlement consumption **at acceptance**
and later actual refund evidence. Reusing an old pure cursor is not prevented
by arithmetic alone. A returned slice is not a payment, refund confirmation,
wallet or authorization token.

## Explicit remaining gates — not silently removed requirements

- This engine consumes already resolved effective prices. Company-priority price
  selection, override revision invalidation, quote protection/repricing policy,
  stock reservations and permission/approval evidence belong to upstream trusted
  commands and remain required. It never publishes a changed selling price.
- Promotion selection is only the explicitly bounded profile above, **not full
  campaign support**. Repeated applications, per-unit fixed multiplication,
  progressive tiers, mixed-price/cheapest-reward buy-get, alternative charge-target
  interpretations, recurring local-time windows and arbitrary combinations remain
  unimplemented. Branch redemption caps/global preallocated rights have no accepting
  fake adapter: those scope modes reject. One declared application within this
  basket is not a stock/campaign ledger or cross-command redemption guarantee.
  `UNRESOLVED_CAMPAIGNS` rejects; never drop unsupported fields and claim `NONE`/resolved.
- Unknown profile IDs, unresolved campaigns, gross/compounding/tiered/minimum
  percentage-charge bases and unsupported fixed-charge item-return allocation
  reject before publication/collection. Current support does not imply arbitrary
  stacking/combinatorial optimization or nonlinear promotions. Positive supported
  percentage taxes are simple, noncompound; specialized tax/deposit/excise regimes
  must have explicit new profile support rather than be approximated as this one.
- Fiscal line/document rounding, company registrations/classifications, legal
  discount/tax treatment, zero-payable rules, required Egyptian documents and
  connectors remain Q05/T20/T31 gates. The inclusion checkbox is not compliance.
- Durable promotion-trace storage and sale/result/effects/audit/outbox integration,
  historical schema migration, all native/physical targets and independent review
  remain separate evidence.

## Local verification commands (lead schedules heavy builds)

```
./gradlew :domain:pricing:jvmTest --max-workers=1 --console=plain
./gradlew :domain:pricing:testAndroidHostTest --max-workers=1 --console=plain
```

The lead schedules these commands and records combined-tree evidence separately.
Common tests cover the F07 fixture, mixed/zero rates, precise quantities,
rates over 100%, caps, sequential explicit stacking, protected posting eligibility,
the ineligible-cent adversarial fixtures and non-monotone feasibility,
stable ties, resolved equal bundle allocations, immutable snapshots, limits and
partial-return residuals. Stored-codec cases cover restart from original postings,
canonical bounded parsing, unsupported versions/fiscal policies, and balanced but
invalid remainder or proportional-allocation tampering. JVM-only JDK `BigInteger`/`BigDecimal` oracles add 160
seeded mixed pricing cases (with bounded deterministic failure shrinking), 160
fractional-cap cases, 100 return-partition cases and direct algebra for mixed-rate
cap saturation. Seed is `0xF0702026`; tests print case/context on failure. Expected
amounts do not call production arithmetic/allocation to compute themselves. The
constrained JDK oracle selects retained cents by independent dynamic programming,
not the production sorting implementation, and expects explicit infeasibility
rather than dropping cases that cannot be posted.

New selection tests add partial-overlap rescoring, the 10-versus-6+6 non-optimizer
counterexample, posted/priority/ID comparisons, explicit compatibility/manual
stacking, original minimum bases, exact UOM tiers, equal-paid bundle/buy-get
returns, absent stock/atomic portions, unsupported/infeasible policies, immutable
traces and the 136-trial bound. A separate JDK integer-cent greedy oracle checks
120 seeded overlapping campaign cases with deterministic shrinking and permutation
checks (`0xF0706EE0`). Its independent scope is integer-EGP prices and integer
percentage benefits in one exclusive group, without charges, caps or bundle/buy-get
targets; it does not independently qualify all campaign arithmetic. Fixed-number
regressions additionally distinguish cap-induced 100/0 rejection from feasible
50/50, original-versus-current weights, zero-weight targets, and permitted later
50/50 to 40/50 manual stacking with final stored returns. Fresh lead-generated XMLs
from 2026-10-23 at 14:02 UTC record **80 JVM and 75 Android-host tests**, zero failures,
errors or skips, including the six target-spread guard regressions. Independent
source review found its cap-allocation concern addressed; that reviewer did not run
tests. These are host results, not native-device, fiscal or transaction-adapter proof.
The 136-trial case uses 16 simple disjoint offers, not a max-64-portion/32-charge
runtime benchmark. Replaying prefixes can execute up to 816 V2 adjustment steps
before manual processing; count bounds are not physical latency qualification.
