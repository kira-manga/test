package com.ukkera.pos.catalog

import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.types.Barcode
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

internal class CatalogDefinitionsTest {
    @Test fun definitionCollectionsAndNestedVariantValuesAreDefensiveAndNotMutableThroughCasts() {
        val values = mutableListOf(Code.exact("S"), Code.exact("L"))
        val axes = mutableListOf(VariantAxis(cid(700), Code.exact("size"), values))
        val products = mutableListOf(CatalogProduct(cid(100), Revision(1), Code.exact("shirt"), listOf(CF.category.id), axes))
        val skuValues = mutableListOf(VariantValue(cid(700), Code.exact("S")))
        val skus = mutableListOf(revisedSku(CF.sku(), variants = skuValues))
        val contexts = mutableListOf(CF.standard.reference)
        val channels = mutableListOf(CF.takeaway)
        val policy = CompanyCatalogPolicy(CF.company, cid(4), Revision(1), PriceSelectionProfile.PRIORITY_FIRST_V1,
            CF.standard.reference, contexts, channels)
        val definition = CF.definition(policy = policy, products = products, skus = skus)
        values.clear(); axes.clear(); products.clear(); skuValues.clear(); skus.clear(); contexts.clear(); channels.clear()
        assertEquals(2, definition.products.single().variantAxes.single().values.size)
        assertEquals(1, definition.skus.single().variantValues.size)
        assertEquals(listOf(CF.takeaway), definition.policy.salesChannels)
        assertEquals(listOf(CF.standard.reference), definition.policy.enabledContexts)
        assertTrue(runCatching { (definition.skus as MutableList<CatalogSku>).clear() }.isFailure)
        assertTrue(runCatching { (definition.products.single().variantAxes.single().values as MutableList<Code>).clear() }.isFailure)
        CF.valid(definition)
    }

    @Test fun collectionBoundsAreCheckedBeforeAttemptingToReadOrCopyElements() {
        val oversized = object : AbstractList<CatalogSku>() {
            override val size: Int = 3
            override fun get(index: Int): CatalogSku = error("Must not copy an oversized collection")
        }
        assertFailsWith<IllegalArgumentException> {
            CF.definition(skus = oversized, bounds = CatalogBounds(maxEntities = 2))
        }
    }

    @Test fun tighterPublicationBoundsAlsoCheckPreviouslyConstructedNestedObjects() {
        val axes = listOf(VariantAxis(cid(700), Code.exact("size"), listOf(Code.exact("S"), Code.exact("L"))))
        val product = CatalogProduct(cid(100), Revision(1), Code.exact("shirt"), variantAxes = axes)
        val failure = CF.invalid(CF.definition(products = listOf(product), bounds = CatalogBounds(maxChildren = 1)))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.ENGINEERING_BOUND_EXCEEDED })
    }

    @Test fun workAndDiagnosticBoundsFailClosedRatherThanAcceptingPartialValidation() {
        val exhausted = CF.invalid(CF.definition(bounds = CatalogBounds(maxValidationWork = 1)))
        assertTrue(exhausted.issues.any { it.code == CatalogIssueCode.ENGINEERING_BOUND_EXCEEDED })
        val duplicate = CF.invalid(CF.definition(skus = listOf(CF.sku(), CF.sku()), bounds = CatalogBounds(maxIssues = 1)))
        assertEquals(1, duplicate.issues.size)
        assertTrue(duplicate.diagnosticsTruncated)
    }

    @Test fun variantsEnumerateOnlyDeclaredCombinationsNotACartesianSkuProduct() {
        val size = VariantAxis(cid(700), Code.exact("size"), listOf(Code.exact("S"), Code.exact("L")))
        val color = VariantAxis(cid(701), Code.exact("color"), listOf(Code.exact("red"), Code.exact("blue")))
        val product = CatalogProduct(cid(100), Revision(1), Code.exact("shirt"), listOf(CF.category.id), listOf(size, color))
        val smallRed = revisedSku(CF.sku(), variants = listOf(VariantValue(size.id, Code.exact("S")), VariantValue(color.id, Code.exact("red"))))
        val largeBlue = revisedSku(CF.sku(cid(201)), variants = listOf(VariantValue(size.id, Code.exact("L")), VariantValue(color.id, Code.exact("blue"))))
        val catalog = CF.valid(CF.definition(products = listOf(product), skus = listOf(smallRed, largeBlue)))
        assertEquals(2, catalog.definition.skus.size)
        assertNotEquals(smallRed.key, largeBlue.key)
        val duplicated = revisedSku(largeBlue, variants = smallRed.variantValues.reversed())
        assertTrue(CF.invalid(CF.definition(products = listOf(product), skus = listOf(smallRed, duplicated))).issues.any { it.code == CatalogIssueCode.DUPLICATE_IDENTITY })
    }

    @Test fun variantsRejectMissingUnknownOrDuplicatedAxesAndUndeclaredValues() {
        val axis = VariantAxis(cid(700), Code.exact("size"), listOf(Code.exact("S")))
        val product = CatalogProduct(cid(100), Revision(1), Code.exact("shirt"), variantAxes = listOf(axis))
        val invalidChoices = listOf(emptyList(), listOf(VariantValue(cid(701), Code.exact("S"))), listOf(VariantValue(axis.id, Code.exact("XL"))))
        for (choices in invalidChoices) {
            val failure = CF.invalid(CF.definition(products = listOf(product), skus = listOf(revisedSku(CF.sku(), variants = choices))))
            assertTrue(failure.issues.any { it.code == CatalogIssueCode.INVALID_VARIANT })
        }
        val duplicate = listOf(VariantValue(axis.id, Code.exact("S")), VariantValue(axis.id, Code.exact("S")))
        assertTrue(CF.invalid(CF.definition(products = listOf(product), skus = listOf(revisedSku(CF.sku(), variants = duplicate)))).issues.any { it.code == CatalogIssueCode.DUPLICATE_IDENTITY })
    }

    @Test fun skuKeysAreCompanyUniqueAndAreNotNumericOrCaseFolded() {
        val a = revisedSku(CF.sku(), key = Code.exact("00123"))
        val b = revisedSku(CF.sku(cid(201), cid(101)), key = Code.exact("123"))
        CF.valid(CF.definition(products = listOf(CF.product(), CF.product(cid(101))), skus = listOf(a, b)))
        val failure = CF.invalid(CF.definition(products = listOf(CF.product(), CF.product(cid(101))), skus = listOf(a, revisedSku(b, key = a.key))))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.DUPLICATE_IDENTITY })
    }

    @Test fun literalBarcodesPreserveLeadingZerosAndRequireExplicitCompanyNamespace() {
        val namespace = CatalogBarcodeNamespace(cid(710), Code.exact("literal"), BarcodeFormat.EXACT_LITERAL)
        val zero = CatalogBarcodeBinding(cid(711), Revision(1), namespace.id, Barcode.exact("00123"), cid(200), CF.each.id)
        val plain = zero.copy(id = cid(712), value = Barcode.exact("123"))
        val catalog = CF.valid(CF.definition(namespaces = listOf(namespace), barcodes = listOf(zero, plain)))
        val a = assertIs<CatalogBarcodeResult.Found>(CatalogBarcodes.resolve(catalog, CompanyBarcodeIdentity(CF.company, namespace.id, Barcode.exact("00123")))).identity
        val b = assertIs<CatalogBarcodeResult.Found>(CatalogBarcodes.resolve(catalog, CompanyBarcodeIdentity(CF.company, namespace.id, Barcode.exact("123")))).identity
        assertEquals("00123", a.identity.barcode.original)
        assertEquals("00123", a.identity.barcode.normalizedKey)
        assertNotEquals(a.binding, b.binding)
        val otherCompany = CompanyScope(CF.company.organizationId, cid(99))
        val wrong = assertIs<CatalogBarcodeResult.Unavailable>(CatalogBarcodes.resolve(catalog, CompanyBarcodeIdentity(otherCompany, namespace.id, zero.value)))
        assertEquals(CatalogBarcodeFailure.WRONG_COMPANY, wrong.reason)
    }

    @Test fun duplicateBarcodeWithinNamespaceIsRejectedButExplicitDifferentNamespacesAreIndependent() {
        val first = CatalogBarcodeNamespace(cid(710), Code.exact("first"), BarcodeFormat.EXACT_LITERAL)
        val second = first.copy(id = cid(720), code = Code.exact("second"))
        val a = CatalogBarcodeBinding(cid(711), Revision(1), first.id, Barcode.exact("00123"), cid(200), CF.each.id)
        val b = a.copy(id = cid(721), namespaceId = second.id)
        CF.valid(CF.definition(namespaces = listOf(first, second), barcodes = listOf(a, b)))
        val collision = CF.invalid(CF.definition(namespaces = listOf(first), barcodes = listOf(a, b.copy(namespaceId = first.id))))
        assertTrue(collision.issues.any { it.code == CatalogIssueCode.DUPLICATE_IDENTITY })
    }

    @Test fun namedEanAndUpcFormatsValidateDigitsLengthAndChecksumWithoutLosingZeros() {
        val vectors = listOf(BarcodeFormat.EAN_8 to "96385074", BarcodeFormat.EAN_13 to "4006381333931", BarcodeFormat.UPC_A to "036000291452")
        for ((format, text) in vectors) {
            val ns = CatalogBarcodeNamespace(cid(710), Code.exact(format.name), format)
            val binding = CatalogBarcodeBinding(cid(711), Revision(1), ns.id, Barcode.exact(text), cid(200), CF.each.id)
            val catalog = CF.valid(CF.definition(namespaces = listOf(ns), barcodes = listOf(binding)))
            assertIs<CatalogBarcodeResult.Found>(CatalogBarcodes.resolve(catalog, CompanyBarcodeIdentity(CF.company, ns.id, binding.value)))
            val wrongDigit = text.dropLast(1) + if (text.last() == '0') "1" else "0"
            val failure = CF.invalid(CF.definition(namespaces = listOf(ns), barcodes = listOf(binding.copy(value = Barcode.exact(wrongDigit)))))
            assertTrue(failure.issues.any { it.code == CatalogIssueCode.INVALID_BARCODE_FORMAT })
        }
    }

    @Test fun scannerTerminatorsWhitespaceAndEmbeddedPayloadsAreNotGuessedOrTrimmed() {
        val namespace = CatalogBarcodeNamespace(cid(710), Code.exact("literal"), BarcodeFormat.EXACT_LITERAL)
        val binding = CatalogBarcodeBinding(cid(711), Revision(1), namespace.id, Barcode.exact("00123"), cid(200), CF.each.id)
        for (value in listOf("00123\n", " 00123", "00123 ", "00123\u001d")) {
            val failure = CF.invalid(CF.definition(namespaces = listOf(namespace), barcodes = listOf(binding.copy(value = Barcode.exact(value)))))
            assertTrue(failure.issues.any { it.code == CatalogIssueCode.INVALID_BARCODE_FORMAT })
        }
        val catalog = CF.valid(CF.definition(namespaces = listOf(namespace), barcodes = listOf(binding)))
        val payload = CompanyBarcodeIdentity(CF.company, namespace.id, Barcode.exact("00123:weight=2.5:price=1"))
        assertEquals(CatalogBarcodeFailure.NOT_FOUND, assertIs<CatalogBarcodeResult.Unavailable>(CatalogBarcodes.resolve(catalog, payload)).reason)
    }

    @Test fun expiryRequiresLotsWhileMeasuredAndLotCapabilitiesCanCompose() {
        val invalid = revisedSku(CF.sku(), capabilities = CF.retail.copy(requiresExpiry = true))
        assertTrue(CF.invalid(CF.definition(skus = listOf(invalid))).issues.any { it.code == CatalogIssueCode.INVALID_CAPABILITIES })
        val yogurt = revisedSku(CF.sku(), scale = 3, capabilities = CF.retail.copy(tracking = StockTracking.LOT, requiresExpiry = true, measured = true))
        CF.valid(CF.definition(skus = listOf(yogurt)))
    }

    @Test fun serialQuantitiesMustBeWholeEvenAtANonzeroStoredPrecision() {
        val serial = revisedSku(CF.sku(), scale = 3, capabilities = CF.retail.copy(tracking = StockTracking.SERIAL))
        val catalog = CF.valid(CF.definition(skus = listOf(serial)))
        val whole = assertIs<CatalogQuantityResult.Resolved>(CatalogQuantities.resolveForSale(catalog, serial.id, Quantity.parse(CF.each.id, "2.000", 3)))
        assertEquals(Quantity(CF.each.id, 2_000, 3), whole.snapshot.baseQuantity)
        val fraction = assertIs<CatalogQuantityResult.Rejected>(CatalogQuantities.resolveForSale(catalog, serial.id, Quantity.parse(CF.each.id, "0.500", 3)))
        assertEquals(CatalogQuantityFailure.SERIAL_REQUIRES_WHOLE_BASE_UNITS, fraction.reason)
        val badDefinition = revisedSku(serial, capabilities = serial.capabilities.copy(measured = true))
        assertTrue(CF.invalid(CF.definition(skus = listOf(badDefinition))).issues.any { it.code == CatalogIssueCode.INVALID_CAPABILITIES })
    }

    @Test fun fractionalSerialTierCannotHideAnAmbiguousOverlapFromTheValidator() {
        val serial = revisedSku(CF.sku(), scale = 3, capabilities = CF.retail.copy(tracking = StockTracking.SERIAL))
        val tier = CF.price(cid(301), context = CF.tierContext.reference,
            quantity = CatalogPriceQuantityRange(Quantity.parse(CF.each.id, "1.500", 3)))
        val failure = CF.invalid(CF.definition(policy = CF.policy(contexts = listOf(CF.standard.reference, CF.tierContext.reference)),
            skus = listOf(serial), contexts = listOf(CF.standard, CF.tierContext), prices = listOf(CF.price(), tier)))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.INVALID_PRICE_RULE })
    }

    @Test fun unsupportedQuantityPrecisionIsRejectedAndNeverRounded() {
        val measured = revisedSku(CF.sku(), scale = 3, capabilities = CF.retail.copy(measured = true))
        val catalog = CF.valid(CF.definition(skus = listOf(measured)))
        val invalid = assertIs<CatalogQuantityResult.Rejected>(CatalogQuantities.resolveForSale(catalog, measured.id, Quantity.parse(CF.each.id, "0.0001", 4)))
        assertEquals(CatalogQuantityFailure.UNSUPPORTED_PRECISION, invalid.reason)
        for (amount in listOf(0L, -1L)) assertEquals(CatalogQuantityFailure.QUANTITY_NOT_POSITIVE,
            assertIs<CatalogQuantityResult.Rejected>(CatalogQuantities.resolveForSale(catalog, measured.id, Quantity(CF.each.id, amount, 3))).reason)
    }

    @Test fun packConversionSnapshotRemainsTwoTimesTwelveAfterFuturePackBecomesTen() {
        val twelve = ProductUnitConversion(cid(600), Revision(1), cid(100), CF.box.id, CF.each.id, UnitConversionRate.ratio(12, 1))
        val sku = revisedSku(CF.sku(), saleUnits = listOf(CatalogSaleUnit(CF.box.id, 0, twelve.reference)))
        val original = CF.valid(CF.definition(units = listOf(CF.each, CF.box), skus = listOf(sku), conversions = listOf(twelve)))
        val receipt = assertIs<CatalogQuantityResult.Resolved>(CatalogQuantities.resolveForSale(original, sku.id, Quantity(CF.box.id, 2, 0))).snapshot
        val ten = twelve.copy(revision = Revision(2), rate = UnitConversionRate.ratio(10, 1))
        val nextSku = revisedSku(sku, saleUnits = listOf(CatalogSaleUnit(CF.box.id, 0, ten.reference)))
        val next = CF.valid(revised(original.definition, skus = listOf(nextSku), conversions = listOf(twelve, ten)), original, listOf(sku.id))
        val newLine = assertIs<CatalogQuantityResult.Resolved>(CatalogQuantities.resolveForSale(next, sku.id, Quantity(CF.box.id, 2, 0))).snapshot
        assertEquals(Quantity(CF.each.id, 24, 0), receipt.baseQuantity)
        assertEquals(Quantity(CF.each.id, 20, 0), newLine.baseQuantity)
        assertEquals(Quantity(CF.each.id, 24, 0), receipt.conversionSnapshot?.convertExact(Quantity(CF.box.id, 2, 0), 0))
        assertEquals(twelve.reference, receipt.conversionSnapshot?.reference)
        assertEquals(ten.reference, newLine.conversionSnapshot?.reference)
    }

    @Test fun massVolumeNeedsExplicitProductEvidenceAndNeverEnablesOtherCrossDimensions() {
        val massSku = revisedSku(CF.sku(), baseUnit = CF.kg.id, scale = 3, capabilities = CF.retail.copy(measured = true))
        val conversion = ProductUnitConversion(cid(600), Revision(1), cid(100), CF.litre.id, CF.kg.id, UnitConversionRate.ratio(4, 5))
        val base = CF.definition(units = listOf(CF.kg, CF.litre, CF.metre), skus = listOf(massSku),
            prices = listOf(CF.price(quantity = CatalogPriceQuantityRange(Quantity(CF.kg.id, 0, 0)))), conversions = listOf(conversion))
        assertTrue(CF.invalid(base).issues.any { it.code == CatalogIssueCode.INVALID_CONVERSION })
        val evidenced = conversion.copy(massVolumeEvidenceId = cid(601))
        val valid = CF.valid(revised(base, conversions = listOf(evidenced), skus = listOf(revisedSku(massSku,
            saleUnits = listOf(CatalogSaleUnit(CF.litre.id, 2, evidenced.reference))))))
        val quantity = assertIs<CatalogQuantityResult.Resolved>(CatalogQuantities.resolveForSale(valid, massSku.id, Quantity(CF.litre.id, 2, 0)))
        assertEquals(Quantity(CF.kg.id, 1_600, 3), quantity.snapshot.baseQuantity)
        val nonsense = evidenced.copy(sourceUnitId = CF.metre.id)
        assertTrue(CF.invalid(revised(base, conversions = listOf(nonsense))).issues.any { it.code == CatalogIssueCode.INVALID_CONVERSION })
    }

    @Test fun conversionCannotBeBorrowedFromAnotherProductOrRoundedToFit() {
        val third = ProductUnitConversion(cid(600), Revision(1), cid(100), CF.box.id, CF.each.id, UnitConversionRate.ratio(1, 3))
        val sku = revisedSku(CF.sku(), scale = 3, capabilities = CF.retail.copy(measured = true), saleUnits = listOf(CatalogSaleUnit(CF.box.id, 0, third.reference)))
        assertTrue(CF.invalid(CF.definition(units = listOf(CF.each, CF.box), skus = listOf(sku), conversions = listOf(third))).issues.any { it.code == CatalogIssueCode.INVALID_CONVERSION })
        val another = third.copy(productId = cid(101), rate = UnitConversionRate.ratio(12, 1))
        assertTrue(CF.invalid(CF.definition(units = listOf(CF.each, CF.box), products = listOf(CF.product(), CF.product(cid(101))),
            skus = listOf(sku), conversions = listOf(another))).issues.any { it.code == CatalogIssueCode.INVALID_CONVERSION })
    }

    @Test fun serialPackIncrementCannotRepresentHalfASerialItem() {
        val conversion = ProductUnitConversion(cid(600), Revision(1), cid(100), CF.box.id, CF.each.id, UnitConversionRate.ratio(5, 2))
        val serial = revisedSku(CF.sku(), capabilities = CF.retail.copy(tracking = StockTracking.SERIAL), saleUnits = listOf(CatalogSaleUnit(CF.box.id, 0, conversion.reference)))
        val failure = CF.invalid(CF.definition(units = listOf(CF.each, CF.box), skus = listOf(serial), conversions = listOf(conversion)))
        assertTrue(failure.issues.any { it.code == CatalogIssueCode.INVALID_CONVERSION })
    }
}
