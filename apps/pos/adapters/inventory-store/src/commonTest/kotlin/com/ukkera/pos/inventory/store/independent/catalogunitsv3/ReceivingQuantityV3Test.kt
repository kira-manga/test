package com.ukkera.pos.inventory.store.independent.catalogunitsv3

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.ProductUnitConversion
import com.ukkera.pos.catalog.StockTracking
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.inventory.store.LocalGoodsReceiptLine
import com.ukkera.pos.inventory.store.LocalReceiptDisposition
import com.ukkera.pos.inventory.store.LocalStockItemAuthority
import com.ukkera.pos.inventory.store.LocalStockRejected
import com.ukkera.pos.inventory.store.LocalStockSourceStamp
import com.ukkera.pos.inventory.store.resolveReceiptLine
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CUA-08 frozen quantities through the actual receiving-line resolver. The controlled
 * input is not a current Master grant, verified source stamp or durable receipt fixture.
 */
class ReceivingQuantityV3Test {
    private val organization = DomainId("10000000-aaaa-4000-8000-000000000001")
    private val company = DomainId("10000000-aaaa-4000-8000-000000000002")
    private val product = DomainId("20000000-bbbb-4000-8000-000000000001")
    private val sku = DomainId("20000000-bbbb-4000-8000-000000000002")
    private val piece = DomainId("40000000-dddd-4000-8000-000000000001")
    private val carton = DomainId("40000000-dddd-4000-8000-000000000002")
    private val kilogram = DomainId("40000000-dddd-4000-8000-000000000003")
    private val gram = DomainId("40000000-dddd-4000-8000-000000000004")
    private val portion = DomainId("40000000-dddd-4000-8000-000000000006")
    private val conversionId = DomainId("50000000-eeee-4000-8000-000000000001")
    // Plumbing identities/time are inert for this resolver; no native or storage port accepts them.
    private val branch = DomainId("80000000-aaaa-4000-8000-000000000001")
    private val warehouse = DomainId("80000000-aaaa-4000-8000-000000000002")
    private val lineId = DomainId("80000000-aaaa-4000-8000-000000000003")
    private val allocation = DomainId("80000000-aaaa-4000-8000-000000000004")
    private val source = DomainId("80000000-aaaa-4000-8000-000000000005")
    private val now = UtcInstant.parseCanonical("2026-11-01T00:00:00Z")

    private fun conversion(sourceUnit: DomainId, targetUnit: DomainId, numerator: Long, denominator: Long) =
        ProductUnitConversion(conversionId, Revision(1), product, sourceUnit, targetUnit, UnitConversionRate.ratio(numerator, denominator))

    private fun item(conversion: ProductUnitConversion, sourceScale: Int, targetScale: Int, dimension: UnitDimension): LocalStockItemAuthority =
        LocalStockItemAuthority(BranchScope(organization, company, branch), sku, product, Revision(1), Revision(1),
            conversion.targetUnitId, targetScale, StockTracking.QUANTITY, false, true, true, listOf(conversion),
            listOf(LocalStockSourceStamp(AggregateKey("local.catalog.sku", sku), Revision(1), PayloadDigest("0".repeat(64)))),
            listOf(CatalogUnit(conversion.sourceUnitId, Code.exact(when (conversion.sourceUnitId) {
                gram -> "gram"
                portion -> "massPortion"
                else -> "carton"
            }), dimension, sourceScale), CatalogUnit(conversion.targetUnitId,
                Code.exact(if (conversion.targetUnitId == kilogram) "kilogram" else "piece"), dimension, targetScale)))

    private fun line(quantity: Quantity, conversion: ProductUnitConversion) = LocalGoodsReceiptLine(
        lineId, allocation, source, sku, warehouse, quantity, conversion.reference, null, null,
        LocalReceiptDisposition.RELEASED, Money(Currency.EGP, 0))

    private fun assertExact(quantity: Quantity, conversion: ProductUnitConversion, sourceScale: Int,
        targetScale: Int, dimension: UnitDimension, expectedScaled: Long) {
        val received = resolveReceiptLine(line(quantity, conversion), item(conversion, sourceScale, targetScale, dimension),
            opening = false, inspectionRequired = false, now = now)
        assertEquals(conversion.targetUnitId, received.baseQuantity.unitId)
        assertTrue(received.baseQuantity.scale in 0..targetScale)
        assertEquals(expectedScaled, received.baseQuantity.rescaleExact(targetScale).scaled)
        val retainedConversion = assertNotNull(received.conversion)
        assertEquals(conversionId, retainedConversion.id)
        assertEquals(product, retainedConversion.productId)
        assertEquals(conversion.sourceUnitId, retainedConversion.sourceUnitId)
        assertEquals(conversion.targetUnitId, retainedConversion.targetUnitId)
        assertEquals(conversion.rate, retainedConversion.rate)
        assertEquals(1L, retainedConversion.revision.value)
    }

    @Test
    fun cua08_mass_exact_and_six_digit_boundary_use_literal_arithmetic_oracles() {
        val conversion = conversion(gram, kilogram, 1, 1000)
        assertExact(Quantity(gram, 1125, 0), conversion, 3, 6, UnitDimension.MASS, 1125000)
        assertExact(Quantity(gram, 1, 3), conversion, 3, 6, UnitDimension.MASS, 1)
    }

    @Test
    fun cua08_receiving_refuses_input_above_gram_source_precision() {
        val conversion = conversion(gram, kilogram, 1, 1000)
        assertFailsWith<LocalStockRejected> {
            resolveReceiptLine(line(Quantity(gram, 1, 4), conversion), item(conversion, 3, 6, UnitDimension.MASS),
                opening = false, inspectionRequired = false, now = now)
        }
    }

    @Test
    fun cua08_third_rate_accepts_three_portions_but_never_rounds_one_portion() {
        val conversion = conversion(portion, kilogram, 1, 3)
        assertExact(Quantity(portion, 3, 0), conversion, 0, 6, UnitDimension.MASS, 1000000)
        assertFailsWith<LocalStockRejected> {
            resolveReceiptLine(line(Quantity(portion, 1, 0), conversion), item(conversion, 0, 6, UnitDimension.MASS),
                opening = false, inspectionRequired = false, now = now)
        }
    }

    @Test
    fun cua08_receiving_refuses_fractional_portion_even_when_result_is_exact() {
        val conversion = conversion(portion, kilogram, 1, 3)
        assertFailsWith<LocalStockRejected> {
            resolveReceiptLine(line(Quantity(portion, 3, 1), conversion), item(conversion, 0, 6, UnitDimension.MASS),
                opening = false, inspectionRequired = false, now = now)
        }
    }

    @Test
    fun cua08_count_near_limit_is_exact_and_destination_overflow_is_refused() {
        val conversion = conversion(carton, piece, 2, 1)
        assertExact(Quantity(carton, 4611686018427387903L, 0), conversion, 0, 0, UnitDimension.COUNT, 9223372036854775806L)
        assertFailsWith<LocalStockRejected> {
            resolveReceiptLine(line(Quantity(carton, Long.MAX_VALUE, 0), conversion), item(conversion, 0, 0, UnitDimension.COUNT),
                opening = false, inspectionRequired = false, now = now)
        }
    }

    @Test
    fun cua08_conversion_rejects_a_quantity_with_the_wrong_source_id() {
        val conversion = conversion(gram, kilogram, 1, 1000)
        assertFailsWith<IllegalArgumentException> { conversion.convertExact(Quantity(kilogram, 1, 0), 6) }
        assertFailsWith<LocalStockRejected> {
            resolveReceiptLine(line(Quantity(kilogram, 1, 0), conversion), item(conversion, 3, 6, UnitDimension.MASS),
                opening = false, inspectionRequired = false, now = now)
        }
    }

    @Test
    fun cua08_receiving_rejects_zero_and_negative_business_quantities() {
        val conversion = conversion(gram, kilogram, 1, 1000)
        for (scaled in listOf(0L, -1L)) {
            assertFailsWith<LocalStockRejected> {
                resolveReceiptLine(line(Quantity(gram, scaled, 0), conversion), item(conversion, 3, 6, UnitDimension.MASS),
                    opening = false, inspectionRequired = false, now = now)
            }
        }
    }
}
