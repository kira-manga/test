package com.ukkera.pos.catalog

import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal class CatalogPriceSelectionTest {
    @Test fun company100r5Branch120ThenCompany150r6Selects150AndKeepsTheOldSnapshot() {
        val oldRule = CF.price()
        val old = CF.valid(CF.definition(policy = CF.policy(overrides = true)))
        val branch = CF.branchDefinition(old, overrides = listOf(CF.override(oldRule)))
        val quoted = CF.selected(CF.plan(old, branch))
        assertEquals("120", quoted.unitPrice.amount.canonicalString())
        assertEquals(Revision(5), quoted.companyPrice.rule.revision)
        assertEquals(Revision(1), quoted.branchOverride?.revision)

        val freshRule = oldRule.copy(revision = Revision(6), terms = oldRule.terms.copy(unitPrice = UnitPrice.parse(Currency.EGP, "150")))
        val fresh = CF.valid(revised(old.definition, prices = listOf(oldRule, freshRule), activePrices = listOf(freshRule.reference)), old)
        val freshBranch = CF.branchDefinition(fresh, revision = Revision(2), overrides = branch.overrides)
        val preview = CatalogPriceSelector.preview(CF.plan(fresh, freshBranch, branch), CF.query())
        val selected = assertIs<CatalogPriceOutcome.Selected>(preview.outcome).price
        assertEquals("150", selected.unitPrice.amount.canonicalString())
        assertEquals(freshRule.reference, selected.companyPrice.rule)
        assertNull(selected.branchOverride)
        assertEquals(BranchOverrideDisposition.SUPERSEDED_COMPANY_REVISION, preview.overrides.single().disposition)
        assertEquals("120", quoted.unitPrice.amount.canonicalString(), "A new-line selector never mutates a quoted/collected historical snapshot")
    }

    @Test fun lateOfflineRevision5EditCannotRestore120AndNewRevision6BindingStillNeedsAuthorization() {
        val oldRule = CF.price()
        val company = CF.valid(CF.definition(policy = CF.policy(actions = listOf(CF.action()), overrides = true)))
        val branch = CF.branchDefinition(company, actions = listOf(CF.action(cid(510))), overrides = listOf(CF.override()))
        val rule6 = oldRule.copy(revision = Revision(6), terms = oldRule.terms.copy(unitPrice = UnitPrice.parse(Currency.EGP, "150")))
        val newer = CF.valid(revised(company.definition, prices = listOf(oldRule, rule6), activePrices = listOf(rule6.reference)), company)
        val newBranch = CF.branchDefinition(newer, revision = Revision(2), actions = branch.policy.optionalActions, overrides = branch.overrides)
        val plan = CF.plan(newer, newBranch, branch)
        val stale = CF.override().copy(revision = Revision(2))
        val denied = assertIs<BranchOverrideEditAdmission.Rejected>(BranchPriceOverrideAdmission.checkEdit(plan, Revision(2), stale))
        assertTrue(BranchOverrideEditFailure.STALE_OR_WRONG_COMPANY_PRICE in denied.reasons)
        assertEquals("150", CF.selected(plan).unitPrice.amount.canonicalString())
        val rebound = stale.copy(basedOn = stale.basedOn.copy(rule = rule6.reference), unitPrice = UnitPrice.parse(Currency.EGP, "160"))
        val preflight = assertIs<BranchOverrideEditAdmission.ReadyForAuthorization>(BranchPriceOverrideAdmission.checkEdit(plan, Revision(2), rebound))
        assertEquals(CF.overridePermission, preflight.permission)
        assertEquals(rebound, preflight.proposal)
        // No authorization/save is performed by preflight, so the same plan still selects 150.
        assertEquals("150", CF.selected(plan).unitPrice.amount.canonicalString())
    }

    @Test fun unrelatedProductPricePublicationDoesNotInvalidateThisOverridesRelevantRevision() {
        val other = CF.price(id = cid(301), revision = Revision(8), amount = "50", target = CatalogPriceTarget.Sku(cid(201)))
        val catalog = CF.valid(CF.definition(
            policy = CF.policy(overrides = true), products = listOf(CF.product(), CF.product(cid(101))),
            skus = listOf(CF.sku(), CF.sku(cid(201), cid(101))), prices = listOf(CF.price(), other),
        ))
        val branch = CF.branchDefinition(catalog, overrides = listOf(CF.override()))
        val nextOther = other.copy(revision = Revision(9), terms = other.terms.copy(unitPrice = UnitPrice.parse(Currency.EGP, "80")))
        val next = CF.valid(revised(catalog.definition, prices = catalog.definition.priceRules + nextOther,
            activePrices = listOf(CF.price().reference, nextOther.reference)), catalog)
        val price = CF.selected(CF.plan(next, CF.branchDefinition(next, revision = Revision(2), overrides = branch.overrides), branch))
        assertEquals("120", price.unitPrice.amount.canonicalString())
        assertEquals(Revision(2), price.companyCatalogRevision)
        assertEquals(Revision(5), price.companyPrice.rule.revision)
    }

    @Test fun unrelatedPriceContextRevisionDoesNotInvalidateStandardOverride() {
        val delivery = CF.price(cid(302), Revision(1), "130", context = CF.channelContext.reference, channel = CF.delivery)
        val catalog = CF.valid(CF.definition(policy = CF.policy(contexts = listOf(CF.standard.reference, CF.channelContext.reference),
            channelPrices = listOf(CF.delivery), overrides = true), contexts = listOf(CF.standard, CF.channelContext), prices = listOf(CF.price(), delivery)))
        val context2 = CF.channelContext.copy(revision = Revision(2))
        val delivery2 = delivery.copy(revision = Revision(2), context = context2.reference, terms = delivery.terms.copy(unitPrice = UnitPrice.parse(Currency.EGP, "145")))
        val policy2 = CF.policy(Revision(2), listOf(CF.standard.reference, context2.reference), listOf(CF.delivery), overrides = true)
        val next = CF.valid(revised(catalog.definition, policy = policy2, contexts = catalog.definition.priceContexts + context2,
            prices = catalog.definition.priceRules + delivery2, activePrices = listOf(CF.price().reference, delivery2.reference)), catalog)
        val plan = CF.plan(next, CF.branchDefinition(next, overrides = listOf(CF.override())))
        assertEquals("120", CF.selected(plan, CF.query(channel = CF.takeaway)).unitPrice.amount.canonicalString())
        assertEquals("145", CF.selected(plan, CF.query(channel = CF.delivery)).unitPrice.amount.canonicalString())
    }

    @Test fun defaultStandardPriceIsIdenticalAcrossExplicitSalesChannels() {
        val plan = CF.plan(CF.valid(CF.definition()))
        for (channel in listOf(CF.takeaway, CF.dineIn, CF.delivery)) assertEquals("100", CF.selected(plan, CF.query(channel = channel)).unitPrice.amount.canonicalString())
        val unavailable = assertIs<CatalogPriceOutcome.Unavailable>(CatalogPriceSelector.preview(plan, CF.query(channel = Code.exact("unconfigured"))).outcome)
        assertEquals(CatalogPriceFailure.CHANNEL_NOT_ENABLED, unavailable.reason)
    }

    @Test fun channelSpecificPricingCannotBeEnabledMerelyByAddingARule() {
        val delivery = CF.price(cid(301), amount = "130", context = CF.channelContext.reference, channel = CF.delivery)
        val invalid = CF.invalid(CF.definition(policy = CF.policy(contexts = listOf(CF.standard.reference, CF.channelContext.reference)),
            contexts = listOf(CF.standard, CF.channelContext), prices = listOf(CF.price(), delivery)))
        assertTrue(invalid.issues.any { it.code == CatalogIssueCode.INVALID_POLICY })
    }

    @Test fun optInPriorityFirstProfileDoesNotSecretlyPreferMoreSpecificRules() {
        val global = CF.price(cid(303), amount = "77", target = CatalogPriceTarget.Standard, priority = 10)
        val product = CF.price(cid(302), amount = "88", target = CatalogPriceTarget.Product(cid(100)), priority = 1)
        val plan = CF.plan(CF.valid(CF.definition(prices = listOf(CF.price(), global, product))))
        val preview = CatalogPriceSelector.preview(plan, CF.query())
        assertEquals(PriceSelectionProfile.PRIORITY_FIRST_V1, preview.profile)
        assertEquals(global.reference, assertIs<CatalogPriceOutcome.Selected>(preview.outcome).price.companyPrice.rule)
        assertEquals(listOf(global.id, product.id, CF.price().id), preview.candidates.map { it.rule.id })
    }

    @Test fun equalPriorityRanksSkuThenProductThenFlatCategoryThenStandard() {
        val sku = CF.price()
        val product = CF.price(cid(301), amount = "90", target = CatalogPriceTarget.Product(cid(100)))
        val category = CF.price(cid(302), amount = "80", target = CatalogPriceTarget.Category(CF.category.id))
        val standard = CF.price(cid(303), amount = "70", target = CatalogPriceTarget.Standard)
        val rules = listOf(sku, product, category, standard)
        for (start in rules.indices) {
            val remaining = rules.drop(start)
            val plan = CF.plan(CF.valid(CF.definition(prices = rules, activePrices = remaining.reversed().map { it.reference })))
            assertEquals(remaining.first().reference, CF.selected(plan).companyPrice.rule)
        }
    }

    @Test fun channelSpecificityComesBeforeHighestQualifyingMinimumTier() {
        val delivery = CF.price(cid(301), amount = "95", context = CF.channelContext.reference, channel = CF.delivery)
        val tier = CF.price(cid(302), amount = "80", context = CF.tierContext.reference,
            quantity = CatalogPriceQuantityRange(Quantity(CF.each.id, 5, 0)))
        val policy = CF.policy(contexts = listOf(CF.standard.reference, CF.channelContext.reference, CF.tierContext.reference), channelPrices = listOf(CF.delivery))
        val plan = CF.plan(CF.valid(CF.definition(policy = policy, contexts = listOf(CF.standard, CF.channelContext, CF.tierContext), prices = listOf(CF.price(), tier, delivery))))
        assertEquals(delivery.reference, CF.selected(plan, CF.query("10", channel = CF.delivery)).companyPrice.rule)
        assertEquals(tier.reference, CF.selected(plan, CF.query("10", channel = CF.takeaway)).companyPrice.rule)
    }

    @Test fun highestQualifyingTierUsesExactFractionalQuantityAndHalfOpenMaximum() {
        val measured = revisedSku(CF.sku(), scale = 3, capabilities = CF.retail.copy(measured = true))
        val quarter = CF.price(cid(301), amount = "90", context = CF.tierContext.reference,
            quantity = CatalogPriceQuantityRange(Quantity.parse(CF.each.id, "0.250", 3)))
        val half = CF.price(cid(302), amount = "80", context = CF.tierContext.reference,
            quantity = CatalogPriceQuantityRange(Quantity.parse(CF.each.id, "0.500", 3), Quantity.parse(CF.each.id, "1.000", 3)))
        val catalog = CF.valid(CF.definition(policy = CF.policy(contexts = listOf(CF.standard.reference, CF.tierContext.reference)),
            skus = listOf(measured), contexts = listOf(CF.standard, CF.tierContext), prices = listOf(CF.price(), quarter, half)))
        val plan = CF.plan(catalog, CF.branchDefinition(catalog, capabilities = CF.simpleCapabilities + CatalogExecutionCapability.MEASURED_QUANTITY))
        assertEquals(quarter.reference, CF.selected(plan, CF.query("0.499", 3)).companyPrice.rule)
        assertEquals(half.reference, CF.selected(plan, CF.query("0.500", 3)).companyPrice.rule)
        assertEquals(quarter.reference, CF.selected(plan, CF.query("1.000", 3)).companyPrice.rule)
    }

    @Test fun periodStartIsNotASilentPrecedenceAndCompatibleTiesUseCanonicalRuleId() {
        val early = CF.price(cid(300), amount = "100.00")
        val late = CF.price(cid(301), amount = "100", period = CatalogEffectivePeriod(UtcInstant.parseCanonical("2026-09-01T00:00:00Z")))
        for (rules in listOf(listOf(early, late), listOf(late, early))) {
            val plan = CF.plan(CF.valid(CF.definition(prices = rules)))
            assertEquals(early.reference, CF.selected(plan).companyPrice.rule)
            assertEquals("100", CF.selected(plan).unitPrice.amount.canonicalString())
        }
    }

    @Test fun incompatibleEqualPreIdOverlapRejectsActivationWithAConcreteDeterministicPreview() {
        val first = CF.price()
        val second = CF.price(cid(301), amount = "150", period = CatalogEffectivePeriod(UtcInstant.parseCanonical("2026-09-01T00:00:00Z")))
        val a = CF.invalid(CF.definition(prices = listOf(first, second)))
        val b = CF.invalid(CF.definition(prices = listOf(second, first)))
        assertTrue(a.issues.any { it.code == CatalogIssueCode.PRICE_AMBIGUITY })
        assertEquals(a.priceConflicts, b.priceConflicts)
        val conflict = a.priceConflicts.single()
        assertEquals(cid(200), conflict.witness.skuId)
        assertEquals(second.period.from, conflict.witness.at)
        assertTrue(first.quantityRange.contains(conflict.witness.quantity))
        assertTrue(second.quantityRange.contains(conflict.witness.quantity))
        assertNotEquals(conflict.first.terms, conflict.second.terms)
    }

    @Test fun taxInclusionOrCategoryDisagreementIsIncompatibleEvenWhenUnitPriceIsEqual() {
        val first = CF.price()
        val category2 = CatalogTaxCategory(cid(21), Code.exact("other"))
        val otherInclusion = first.copy(id = cid(301), terms = first.terms.copy(taxInclusion = CatalogTaxInclusion.EXCLUSIVE))
        val otherCategory = first.copy(id = cid(301), terms = first.terms.copy(taxCategoryId = category2.id))
        for (second in listOf(otherInclusion, otherCategory)) {
            val failure = CF.invalid(CF.definition(prices = listOf(first, second), taxCategories = listOf(CF.tax, category2)))
            assertEquals(1, failure.priceConflicts.size)
        }
    }

    @Test fun touchingUtcPeriodsAreNotOverlapsAndEndIsExclusive() {
        val boundary = UtcInstant.parseCanonical("2026-06-01T00:00:00Z")
        val old = CF.price(period = CatalogEffectivePeriod(CF.start, boundary))
        val next = CF.price(cid(301), amount = "150", period = CatalogEffectivePeriod(boundary))
        val plan = CF.plan(CF.valid(CF.definition(prices = listOf(old, next))))
        assertEquals(old.reference, CF.selected(plan, CF.query(at = UtcInstant.parseCanonical("2026-05-31T23:59:59.999999999Z"))).companyPrice.rule)
        assertEquals(next.reference, CF.selected(plan, CF.query(at = boundary)).companyPrice.rule)
        val unavailable = assertIs<CatalogPriceOutcome.Unavailable>(CatalogPriceSelector.preview(plan, CF.query(at = UtcInstant.parseCanonical("2025-12-31T23:59:59Z"))).outcome)
        assertEquals(CatalogPriceFailure.NO_ELIGIBLE_PRICE, unavailable.reason)
    }

    @Test fun flatCategoryOverlapsAreBasedOnActualSkuMembershipNotAnInventedHierarchy() {
        val category2 = CatalogCategory(cid(41), Code.exact("second"))
        val first = CF.price(target = CatalogPriceTarget.Category(CF.category.id))
        val second = CF.price(cid(301), amount = "150", target = CatalogPriceTarget.Category(category2.id))
        val p2 = CatalogProduct(cid(101), Revision(1), Code.exact("second product"), listOf(category2.id))
        CF.valid(CF.definition(products = listOf(CF.product(), p2), skus = listOf(CF.sku(), CF.sku(cid(201), cid(101))),
            categories = listOf(CF.category, category2), prices = listOf(first, second)))
        val both = CatalogProduct(cid(100), Revision(1), Code.exact("both"), listOf(CF.category.id, category2.id))
        val invalid = CF.invalid(CF.definition(products = listOf(both), categories = listOf(CF.category, category2), prices = listOf(first, second)))
        assertEquals(cid(200), invalid.priceConflicts.single().witness.skuId)
    }

    @Test fun priceUnitAndStockBaseUnitAreBothPinnedWithoutInventingAPackPrice() {
        val conversion = ProductUnitConversion(cid(600), Revision(1), cid(100), CF.box.id, CF.each.id, UnitConversionRate.ratio(12, 1))
        val sku = revisedSku(CF.sku(), saleUnits = listOf(CatalogSaleUnit(CF.box.id, 0, conversion.reference)))
        val base = CF.price(amount = "10")
        val box = CF.price(cid(301), amount = "110", quantity = CatalogPriceQuantityRange(Quantity(CF.box.id, 0, 0)))
        val catalog = CF.valid(CF.definition(units = listOf(CF.each, CF.box), skus = listOf(sku), conversions = listOf(conversion), prices = listOf(base, box)))
        val plan = CF.plan(catalog, CF.branchDefinition(catalog, capabilities = CF.simpleCapabilities + CatalogExecutionCapability.PACK_CONVERSION))
        val price = CF.selected(plan, CF.query("2", unit = CF.box.id))
        assertEquals("110", price.unitPrice.amount.canonicalString())
        assertEquals(Quantity(CF.box.id, 2, 0), price.quantity)
        assertEquals(Quantity(CF.each.id, 24, 0), price.baseQuantity)
        assertEquals(conversion, price.conversionSnapshot)
        val noGuess = assertIs<CatalogPriceOutcome.Unavailable>(CatalogPriceSelector.preview(plan, CF.query(unit = CF.kg.id)).outcome)
        assertEquals(CatalogQuantityFailure.UNIT_NOT_ENABLED, noGuess.quantityFailure)
    }

    @Test fun extremeExplicitPrioritiesDoNotOverflowComparatorArithmetic() {
        val low = CF.price(priority = Int.MIN_VALUE)
        val high = CF.price(cid(301), amount = "70", priority = Int.MAX_VALUE, target = CatalogPriceTarget.Standard)
        val plan = CF.plan(CF.valid(CF.definition(prices = listOf(low, high))))
        assertEquals(high.reference, CF.selected(plan).companyPrice.rule)
    }
}
