package com.ukkera.pos.catalog.store.independent.foodservingquantityv1

import com.ukkera.pos.catalog.store.LocalCatalogFoodSource
import com.ukkera.pos.catalog.store.LocalCatalogFoodStockSource
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.validFoodDefinition
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.store.LocalFoodPortionSelectionV1
import com.ukkera.pos.sales.store.LocalFoodSelectionGroups
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.TransactionFoodRecipeSourceV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L0 current-source rejection guards and L1 real price-resolver/engine/codec
 * slices of food-serving-quantity-v1. Captured price inputs are synthetic, not
 * signed-source activation or durable readback. Fail-on-read views supply no
 * database state or evidence. Positive current catalog preparation, literal
 * original catalog verification, protected persistence/RPC, installed actions,
 * and all ten whole FQ cases remain NOT_RUN.
 */
class FoodServingQuantityFrozenCatalogSlicesTest {
    private val f = FrozenFoodServingQuantityCatalogInputs

    @Test
    fun FQ_V1_01_OriginalPreparedPriceAndEngineProduceOneLineQuantityTwoAt22000() {
        assertQuote(f.original, calculate(f.original))
    }

    @Test
    fun FQ_V1_02_CurrentCompatiblePreparedPriceAndEngineProduce28000() {
        assertQuote(f.current, calculate(f.current))
        assertTrue(validFoodDefinition(f.recipe(f.current), f.definitions(f.current)))
        assertEquals(f.variantId, f.recipe(f.current).outputSkuId)
        assertEquals(listOf(f.cheeseOptionId), f.recipe(f.current).choices.single().modifierIds)
        assertEquals(160L, f.recipe(f.current).choices.single().ingredients.single { it.skuId == f.meatId }.quantityPerYield.scaled)
    }

    @Test
    fun FQ_V1_04_StoredOriginalPriceSnapshotKeeps22000AfterCurrentPriceCalculation() {
        val originalBytes = StoredPricingSnapshotCodec.encode(calculate(f.original))
        assertQuote(f.current, calculate(f.current))
        val original = StoredPricingSnapshotCodec.decode(originalBytes)
        assertQuote(f.original, original)
        assertEquals(originalBytes, StoredPricingSnapshotCodec.encode(original))
        // This checks an immutable stored-format value, not Master recovery or a lost reply.
    }

    @Test
    fun FQ_V1_03_SelectedDefinitionRejectsMissingCheeseAndEqualLabelOtherProduct() {
        val recipe = f.recipe(f.original)
        val definitions = f.definitions(f.original)
        assertTrue(validFoodDefinition(recipe, definitions))
        assertFalse(validFoodDefinition(recipe, definitions - f.cheeseId))
        assertFalse(validFoodDefinition(recipe, definitions + (f.cheeseId to definitions.getValue(f.cheeseId).copy(active = false))))
        assertFalse(validFoodDefinition(recipe, definitions + (f.variantId to definitions.getValue(f.variantId).copy(active = false))))
        val lookalike = definitions.getValue(f.combinationSkuId).copy(productId = f.lookalikeProductId)
        assertEquals(definitions.getValue(f.combinationSkuId).names, lookalike.names)
        assertFalse(validFoodDefinition(recipe, definitions + (f.combinationSkuId to lookalike)))
    }

    @Test
    fun FQ_V1_03_CurrentCatalogAdaptersRefuseMixedLineIdentityOrExactNotesBeforeReads() = runTest {
        val original = f.twoServings()
        val second = original[1]
        val incompatible = listOf(
            second.copy(itemId = f.lookalikeVariantId),
            second.copy(choiceId = f.lookalikeVariantId),
            second.copy(modifierIds = listOf(f.lookalikeCheeseOptionId)),
            second.copy(expectedRecipeRevision = f.current.recipeRevision),
            second.copy(quantity = f.two),
            second.copy(itemNote = ""),
            second.copy(seatNote = ""),
        )
        for (changed in incompatible) assertRefusedBeforeRead(listOf(original[0], changed))
    }

    @Test
    fun FQ_V1_X_BOUNDARY_CurrentCatalogAdaptersRefuseDuplicateServingIdsBeforeReads() = runTest {
        val original = f.twoServings()
        assertRefusedBeforeRead(listOf(original[0], original[1].copy(portionId = original[0].portionId)))
    }

    private fun calculate(source: FrozenQuantityPriceSource): PricingSnapshot {
        val selections = f.twoServings(source)
        val group = LocalFoodSelectionGroups.group(selections).single()
        val pricing = f.preparedPrice(source)
        val selected = pricing.resolve(f.service.channel.name, group.totalQuantity, f.at)
        assertEquals(UnitPrice.parse(Currency.EGP, source.combinationPrice), selected.effectiveUnitPrice)
        val resolved = selections.map { portion ->
            val provenance = selected.provenance(portion.commercialLineId, portion.portionId)
            assertEquals(f.lineId, provenance.lineId)
            assertEquals(portion.portionId, provenance.portionId)
            assertEquals(f.combinationSkuId, provenance.skuId)
            assertEquals(source.baseRevision, provenance.companyPriceRevision)
            ResolvedPriceLine(portion.commercialLineId, portion.portionId, selected.company.revision,
                portion.quantity, selected.effectiveUnitPrice, selected.company.inclusion, selected.company.tax)
        }
        return OperationalPricingEngine.calculate(PricingRequest(f.quoteId, Revision(0), Currency.EGP, resolved))
    }

    private fun assertQuote(source: FrozenQuantityPriceSource, quote: PricingSnapshot) {
        assertEquals(Money(Currency.EGP, source.payableMinor), quote.payable)
        assertEquals(Money(Currency.EGP, source.payableMinor), quote.beforeDiscountPayable)
        assertEquals(Money(Currency.EGP, 0), quote.advertisedDiscount)
        assertEquals(listOf(f.lineId), quote.lines.map { it.lineId }.distinct())
        assertEquals(listOf(f.lineId, f.secondPortionId), quote.lines.map { it.portionId })
        assertEquals(f.two, quote.lines.fold(f.one.copy(scaled = 0)) { total, portion -> total + portion.quantity })
        assertTrue(quote.lines.all {
            it.quantity == f.one && it.unitPrice == UnitPrice.parse(Currency.EGP, source.combinationPrice) &&
                it.payable == Money(Currency.EGP, source.unitMinor) && it.priceRevision == source.baseRevision
        })
        assertEquals(0L, quote.components.filter { it.kind == PriceComponentKind.ITEM_TAX }.sumOf { it.posted.minor })
        assertTrue(quote.charges.isEmpty())
        assertTrue(quote.discounts.isEmpty())
        val selections = f.twoServings(source)
        assertTrue(selections.all { it.itemNote == f.ITEM_NOTE && it.seatNote == f.SEAT_NOTE })
    }

    private suspend fun assertRefusedBeforeRead(portions: List<LocalFoodPortionSelectionV1>) {
        val adapters = listOf<TransactionFoodRecipeSourceV1>(
            LocalCatalogFoodSource(UnavailableCompanyPublicationSignatureVerifier),
            LocalCatalogFoodStockSource(UnavailableCompanyPublicationSignatureVerifier),
        )
        for (source in adapters) {
            val view = FailOnReadView(f.scope)
            assertNull(source.prepare(view, LocalSalesActivity.RESTAURANT, f.service,
                f.catalogCheckpointRevision, portions))
            assertEquals(0, view.reads)
        }
    }
}

/** A trap, not a supplied current/historical database projection. */
private class FailOnReadView(override val scope: BranchScope) : CommandReadView {
    var reads: Int = 0
        private set

    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = unexpectedRead()
    override suspend fun claim(key: ClaimKey): DomainId? = unexpectedRead()
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = unexpectedRead()

    private fun unexpectedRead(): Nothing {
        reads++
        error("Invalid grouped selection reached a catalog read")
    }
}
