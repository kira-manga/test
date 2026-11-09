package com.ukkera.pos.catalog

import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.Barcode
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import com.ukkera.pos.types.UnitPrice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class CatalogCompositionHistoryTest {
    @Test fun assembledKitCyclesAndSelfCyclesAreRejected() {
        val aRef = CatalogVersionRef(cid(650), Revision(1))
        val bRef = CatalogVersionRef(cid(651), Revision(1))
        val a = CF.sku(capabilities = CF.retail.copy(canProduce = true, fulfillment = FulfillmentKind.ASSEMBLED_KIT, composition = aRef))
        val b = CF.sku(cid(201), cid(101), CF.retail.copy(canProduce = true, fulfillment = FulfillmentKind.ASSEMBLED_KIT, composition = bRef))
        val aRecipe = CatalogComposition(aRef.id, aRef.revision, a.id, CompositionKind.ASSEMBLED_KIT, Quantity(CF.each.id, 1, 0),
            listOf(CatalogComponent(cid(660), b.id, Quantity(CF.each.id, 1, 0))))
        val bRecipe = CatalogComposition(bRef.id, bRef.revision, b.id, CompositionKind.ASSEMBLED_KIT, Quantity(CF.each.id, 1, 0),
            listOf(CatalogComponent(cid(661), a.id, Quantity(CF.each.id, 1, 0))))
        val cyclic = CF.invalid(CF.definition(products = listOf(CF.product(), CF.product(cid(101))), skus = listOf(a, b),
            prices = listOf(CF.price(target = CatalogPriceTarget.Standard)), compositions = listOf(aRecipe, bRecipe)))
        assertTrue(cyclic.issues.any { it.code == CatalogIssueCode.COMPONENT_CYCLE })
        val self = CatalogComposition(aRef.id, aRef.revision, a.id, CompositionKind.ASSEMBLED_KIT, Quantity(CF.each.id, 1, 0),
            listOf(CatalogComponent(cid(660), a.id, Quantity(CF.each.id, 1, 0))))
        assertTrue(CF.invalid(CF.definition(skus = listOf(a), compositions = listOf(self))).issues.any { it.code == CatalogIssueCode.COMPONENT_CYCLE })
    }

    @Test fun deepCompositionGraphIsValidatedIterativelyWithinExplicitWorkBounds() {
        val count = 750
        val products = (0 until count).map { CF.product(cid(1_000 + it)) }
        val skus = (0 until count).map { n ->
            val cap = if (n == count - 1) CF.retail else CF.retail.copy(canProduce = true,
                fulfillment = FulfillmentKind.ASSEMBLED_KIT, composition = CatalogVersionRef(cid(3_000 + n), Revision(1)))
            CF.sku(cid(2_000 + n), products[n].id, cap)
        }
        val compositions = (0 until count - 1).map { n -> CatalogComposition(cid(3_000 + n), Revision(1), skus[n].id,
            CompositionKind.ASSEMBLED_KIT, Quantity(CF.each.id, 1, 0), listOf(CatalogComponent(cid(4_000 + n), skus[n + 1].id, Quantity(CF.each.id, 1, 0)))) }
        val catalog = CF.valid(CF.definition(products = products, skus = skus, compositions = compositions,
            prices = listOf(CF.price(target = CatalogPriceTarget.Standard))))
        assertEquals(count - 1, catalog.definition.compositions.size)
    }

    @Test fun virtualComboKeepsRetailKitchenAndServiceComponentsOnDistinctStockPaths() {
        val virtualRef = CatalogVersionRef(cid(650), Revision(1))
        val recipeRef = CatalogVersionRef(cid(651), Revision(1))
        val parent = CF.sku(capabilities = CF.retail.copy(canPurchase = false, tracking = StockTracking.NONE,
            fulfillment = FulfillmentKind.VIRTUAL_KIT, composition = virtualRef))
        val cooked = CF.sku(cid(201), cid(101), CF.retail.copy(canPurchase = false, canProduce = true, tracking = StockTracking.NONE,
            fulfillment = FulfillmentKind.MADE_TO_ORDER, composition = recipeRef))
        val retail = CF.sku(cid(202), cid(102))
        val service = CF.sku(cid(203), cid(103), CF.retail.copy(canPurchase = false, tracking = StockTracking.NONE,
            fulfillment = FulfillmentKind.SERVICE, serviceCharging = ServiceCharging.QUANTITY))
        val ingredient = CF.sku(cid(204), cid(104), CF.retail.copy(canSell = false, ingredient = true))
        val combo = CatalogComposition(virtualRef.id, virtualRef.revision, parent.id, CompositionKind.VIRTUAL_KIT, Quantity(CF.each.id, 1, 0),
            listOf(cooked, retail, service).mapIndexed { n, sku -> CatalogComponent(cid(660 + n), sku.id, Quantity(CF.each.id, 1, 0)) })
        val recipe = CatalogComposition(recipeRef.id, recipeRef.revision, cooked.id, CompositionKind.MADE_TO_ORDER, Quantity(CF.each.id, 1, 0),
            listOf(CatalogComponent(cid(663), ingredient.id, Quantity(CF.each.id, 1, 0))))
        val catalog = CF.valid(CF.definition(products = (100..104).map { CF.product(cid(it)) }, skus = listOf(parent, cooked, retail, service, ingredient),
            compositions = listOf(combo, recipe), prices = listOf(CF.price(target = CatalogPriceTarget.Standard))))
        val plan = CF.plan(catalog, CF.branchDefinition(catalog, capabilities = CatalogExecutionCapability.entries))
        assertEquals(StockTracking.NONE, parent.capabilities.tracking)
        assertEquals(CatalogStockEffectContract.VIRTUAL_COMPONENTS_KEEP_THEIR_OWN_TIMING_NO_PARENT_STOCK, parent.capabilities.stockEffectContract())
        assertEquals(CatalogStockEffectContract.MADE_TO_ORDER_COMPONENT_ISSUE_ON_KITCHEN_SEND, cooked.capabilities.stockEffectContract())
        assertEquals(CatalogStockEffectContract.RETAIL_SKU_RESERVATION_AND_FULFILLMENT, retail.capabilities.stockEffectContract())
        assertEquals(CatalogStockEffectContract.SERVICE_COMPLETION_WITHOUT_STOCK, service.capabilities.stockEffectContract())
        assertTrue(plan.requiredCapabilities.containsAll(listOf(CatalogExecutionCapability.VIRTUAL_KIT_COMPONENT_FULFILLMENT,
            CatalogExecutionCapability.MADE_TO_ORDER_ISSUE_ON_SEND, CatalogExecutionCapability.NON_STOCK_SERVICE_COMPLETION,
            CatalogExecutionCapability.INGREDIENTS)))
    }

    @Test fun preparedAndAssembledPathsDescribeDifferentProductionButNeverDoubleIssueOnSale() {
        assertEquals(CatalogStockEffectContract.PRODUCTION_SEND_THEN_ACTUAL_YIELD_AND_FINISHED_SKU_SALE_ONCE,
            CF.retail.copy(fulfillment = FulfillmentKind.PREPARED_BATCH).stockEffectContract())
        assertEquals(CatalogStockEffectContract.ASSEMBLY_CONSUMES_COMPONENTS_SALE_CONSUMES_PARENT_ONCE,
            CF.retail.copy(fulfillment = FulfillmentKind.ASSEMBLED_KIT).stockEffectContract())
        // These are required contract descriptions, not execution/production acceptance evidence.
    }

    @Test fun recipeIngredientConversionIsBoundToTheIngredientProductAndItsStockDimension() {
        val ref = CatalogVersionRef(cid(650), Revision(1))
        val parent = CF.sku(capabilities = CF.retail.copy(canPurchase = false, tracking = StockTracking.NONE, canProduce = true,
            fulfillment = FulfillmentKind.MADE_TO_ORDER, composition = ref))
        val ingredient = revisedSku(CF.sku(cid(201), cid(101)), baseUnit = CF.kg.id, scale = 3,
            capabilities = CF.retail.copy(canSell = false, ingredient = true, measured = true))
        val density = ProductUnitConversion(cid(600), Revision(1), ingredient.productId, CF.litre.id, CF.kg.id, UnitConversionRate.ratio(4, 5), cid(601))
        val recipe = CatalogComposition(ref.id, ref.revision, parent.id, CompositionKind.MADE_TO_ORDER, Quantity(CF.each.id, 1, 0),
            listOf(CatalogComponent(cid(660), ingredient.id, Quantity(CF.litre.id, 2, 0), density.reference)))
        val definition = CF.definition(units = listOf(CF.each, CF.kg, CF.litre), products = listOf(CF.product(), CF.product(cid(101))),
            skus = listOf(parent, ingredient), conversions = listOf(density), compositions = listOf(recipe))
        CF.valid(definition)
        val failure = CF.invalid(revised(definition, conversions = listOf(density.copy(productId = parent.productId))))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.INVALID_COMPOSITION })
    }

    @Test fun physicalStockCannotBeAddedToServiceOrVirtualOrMadeToOrderParents() {
        val service = revisedSku(CF.sku(), capabilities = CF.retail.copy(fulfillment = FulfillmentKind.SERVICE, serviceCharging = ServiceCharging.FIXED))
        assertTrue(CF.invalid(CF.definition(skus = listOf(service))).issues.any { it.code == CatalogIssueCode.INVALID_CAPABILITIES })
        val untrackedRetail = revisedSku(CF.sku(), capabilities = CF.retail.copy(canPurchase = false, tracking = StockTracking.NONE))
        assertTrue(CF.invalid(CF.definition(skus = listOf(untrackedRetail))).issues.any { it.code == CatalogIssueCode.INVALID_CAPABILITIES })
    }

    @Test fun usedRecordsCanBeArchivedButNotDeletedAndBarcodeLookupStopsSellingThem() {
        val ns = CatalogBarcodeNamespace(cid(710), Code.exact("literal"), BarcodeFormat.EXACT_LITERAL)
        val barcode = CatalogBarcodeBinding(cid(711), Revision(1), ns.id, Barcode.exact("00123"), cid(200), CF.each.id)
        val old = CF.valid(CF.definition(namespaces = listOf(ns), barcodes = listOf(barcode)))
        val product = CatalogProduct(cid(100), Revision(2), old.definition.products.single().name, listOf(CF.category.id), status = CatalogStatus.ARCHIVED)
        val archived = CF.valid(revised(old.definition, products = listOf(product), skus = listOf(revisedSku(old.definition.skus.single(), status = CatalogStatus.ARCHIVED)),
            barcodes = listOf(barcode.copy(revision = Revision(2), status = CatalogStatus.ARCHIVED)), activePrices = emptyList()), old, listOf(cid(200)))
        val lookup = CatalogBarcodes.resolve(archived, CompanyBarcodeIdentity(CF.company, ns.id, barcode.value))
        assertEquals(CatalogBarcodeFailure.ARCHIVED_OR_UNSELLABLE, assertIs<CatalogBarcodeResult.Unavailable>(lookup).reason)
        val removed = CF.invalid(revised(old.definition, skus = emptyList(), barcodes = emptyList(), activePrices = emptyList()), old, listOf(cid(200)))
        assertTrue(removed.issues.any { it.code == CatalogIssueCode.HISTORY_RECORD_REMOVED })
    }

    @Test fun usedStockCannotBecomeANonStockServiceToBypassExhaustion() {
        val old = CF.valid(CF.definition())
        val service = revisedSku(old.definition.skus.single(), capabilities = CF.retail.copy(canPurchase = false,
            tracking = StockTracking.NONE, fulfillment = FulfillmentKind.SERVICE, serviceCharging = ServiceCharging.QUANTITY))
        val failure = CF.invalid(revised(old.definition, skus = listOf(service)), old, listOf(cid(200)))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.STOCK_IDENTITY_CHANGED })
    }

    @Test fun usedBaseUnitAndPrecisionAreProtectedEvenWithNewPriceAndCatalogRevisions() {
        val old = CF.valid(CF.definition())
        val newUnit = CF.each.copy(id = cid(15), code = Code.exact("new-each"))
        val sku = revisedSku(old.definition.skus.single(), baseUnit = newUnit.id)
        val price = CF.price(cid(301), quantity = CatalogPriceQuantityRange(Quantity(newUnit.id, 0, 0)))
        val failure = CF.invalid(revised(old.definition, units = listOf(CF.each, newUnit), skus = listOf(sku),
            prices = old.definition.priceRules + price, activePrices = listOf(price.reference)), old, listOf(cid(200)))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.STOCK_IDENTITY_CHANGED })
        val precision = revisedSku(old.definition.skus.single(), scale = 3, capabilities = CF.retail.copy(measured = true))
        assertTrue(CF.invalid(revised(old.definition, skus = listOf(precision)), old, listOf(cid(200))).issues.any { it.code == CatalogIssueCode.STOCK_IDENTITY_CHANGED })
    }

    @Test fun usedSkuKeyAndBarcodeIdentityCannotBeRepointedButAliasesMayBeAdded() {
        val ns = CatalogBarcodeNamespace(cid(710), Code.exact("literal"), BarcodeFormat.EXACT_LITERAL)
        val barcode = CatalogBarcodeBinding(cid(711), Revision(1), ns.id, Barcode.exact("00123"), cid(200), CF.each.id)
        val old = CF.valid(CF.definition(namespaces = listOf(ns), barcodes = listOf(barcode)))
        val renamed = revisedSku(old.definition.skus.single(), key = Code.exact("new-key"))
        assertTrue(CF.invalid(revised(old.definition, skus = listOf(renamed)), old, listOf(cid(200))).issues.any { it.code == CatalogIssueCode.IDENTIFIER_CHANGED })
        val changedBarcode = barcode.copy(revision = Revision(2), value = Barcode.exact("123"))
        assertTrue(CF.invalid(revised(old.definition, barcodes = listOf(changedBarcode)), old, listOf(cid(200))).issues.any { it.code == CatalogIssueCode.IDENTIFIER_CHANGED })
        val alias = barcode.copy(id = cid(712), value = Barcode.exact("alias-00123"))
        val aliases = CF.valid(revised(old.definition, barcodes = listOf(barcode, alias)), old, listOf(cid(200)))
        assertEquals(2, aliases.definition.barcodes.size)
    }

    @Test fun usedVariantIdentityRemainsDeclaredEvenAfterArchive() {
        val axis = VariantAxis(cid(700), Code.exact("size"), listOf(Code.exact("S"), Code.exact("L")))
        val product = CatalogProduct(cid(100), Revision(1), Code.exact("shirt"), variantAxes = listOf(axis))
        val sku = revisedSku(CF.sku(), variants = listOf(VariantValue(axis.id, Code.exact("S"))))
        val old = CF.valid(CF.definition(products = listOf(product), skus = listOf(sku)))
        val changed = revisedSku(sku, variants = listOf(VariantValue(axis.id, Code.exact("L"))))
        assertTrue(CF.invalid(revised(old.definition, skus = listOf(changed)), old, listOf(sku.id)).issues.any { it.code == CatalogIssueCode.IDENTIFIER_CHANGED })
        val missingAxis = CatalogProduct(product.id, Revision(2), product.name, status = CatalogStatus.ARCHIVED)
        val failure = CF.invalid(revised(old.definition, products = listOf(missingAxis), skus = listOf(revisedSku(sku, status = CatalogStatus.ARCHIVED)), activePrices = emptyList()), old, listOf(sku.id))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.IDENTIFIER_CHANGED })
    }

    @Test fun oldPriceAndConversionVersionsCannotBeRewrittenOrDeleted() {
        val conversion = ProductUnitConversion(cid(600), Revision(1), cid(100), CF.box.id, CF.each.id, UnitConversionRate.ratio(12, 1))
        val old = CF.valid(CF.definition(units = listOf(CF.each, CF.box), conversions = listOf(conversion)))
        val changedPrice = CF.price(amount = "150")
        val rewritten = CF.invalid(revised(old.definition, prices = listOf(changedPrice), conversions = listOf(conversion.copy(rate = UnitConversionRate.ratio(10, 1)))), old)
        assertTrue(rewritten.issues.count { it.code == CatalogIssueCode.VERSION_REWRITTEN } >= 2)
        val removed = CF.invalid(revised(old.definition, prices = emptyList(), activePrices = emptyList(), conversions = emptyList()), old)
        assertTrue(removed.issues.count { it.code == CatalogIssueCode.HISTORY_RECORD_REMOVED } >= 2)
    }

    @Test fun companyCatalogAndPerPriceRevisionCannotRollBackOrEraseTheirHighWaterHistory() {
        val r5 = CF.price()
        val first = CF.valid(CF.definition())
        val r6 = r5.copy(revision = Revision(6), terms = r5.terms.copy(unitPrice = UnitPrice.parse(Currency.EGP, "150")))
        val second = CF.valid(revised(first.definition, prices = listOf(r5, r6), activePrices = listOf(r6.reference)), first)
        val rollback = CF.invalid(revised(second.definition, activePrices = listOf(r5.reference)), second)
        assertTrue(rollback.issues.any { it.code == CatalogIssueCode.REVISION_REGRESSION })
        val erase = CF.invalid(revised(second.definition, prices = listOf(r5), activePrices = listOf(r5.reference)), second)
        assertTrue(erase.issues.any { it.code == CatalogIssueCode.HISTORY_RECORD_REMOVED })
        val catalogRollback = CF.invalid(revised(second.definition, revision = Revision(1)), second)
        assertTrue(catalogRollback.issues.any { it.code == CatalogIssueCode.REVISION_REGRESSION })
    }

    @Test fun unchangedVersionAllowsIdempotentValidationButChangedContentDoesNot() {
        val original = CF.valid(CF.definition())
        CF.valid(original.definition, original)
        val altered = revisedSku(original.definition.skus.single(), revision = Revision(1), key = Code.exact("different"))
        val failure = CF.invalid(revised(original.definition, revision = Revision(1), skus = listOf(altered)), original)
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.VERSION_REWRITTEN })
    }

    @Test fun unavailableStaleOrCrossCompanyHistoryCannotAuthorizeDefinitionChanges() {
        val old = CF.valid(CF.definition())
        val next = revised(old.definition)
        val cases = listOf(
            CatalogHistorySnapshot(CF.company, Revision(1), false),
            CatalogHistorySnapshot(CF.company, Revision(0), true),
            CatalogHistorySnapshot(CompanyScope(cid(1), cid(99)), Revision(1), true),
        )
        val expected = listOf(CatalogIssueCode.HISTORY_UNAVAILABLE, CatalogIssueCode.STALE_HISTORY, CatalogIssueCode.WRONG_SCOPE)
        for (n in cases.indices) {
            val rejected = assertIs<CompanyCatalogValidation.Rejected>(CompanyCatalogValidator.validate(next, cases[n], old))
            assertTrue(rejected.issues.any { it.code == expected[n] })
        }
    }

    @Test fun namesMayChangeByRevisionWithoutChangingStockOrBarcodeIdentity() {
        val old = CF.valid(CF.definition())
        val product = old.definition.products.single()
        val renamed = CatalogProduct(product.id, product.revision.next(), Code.exact("New display name"), product.categoryIds, product.variantAxes, product.attributes)
        val next = CF.valid(revised(old.definition, products = listOf(renamed)), old, listOf(cid(200)))
        assertEquals("New display name", next.definition.products.single().name.original)
        assertEquals(old.definition.priceRules, next.definition.priceRules)
    }
}
