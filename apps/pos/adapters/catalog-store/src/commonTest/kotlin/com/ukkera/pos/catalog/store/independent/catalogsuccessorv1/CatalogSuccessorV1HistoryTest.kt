package com.ukkera.pos.catalog.store.independent.catalogsuccessorv1

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.localCatalogConversionRevisionRejection
import com.ukkera.pos.catalog.store.localCatalogUnitHistoryRejection
import com.ukkera.pos.catalog.store.localCatalogUnitShapeRejection
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Pure history validation slices only; no complete CSR case or runtime join is executed. */
class CatalogSuccessorV1HistoryTest {
    private val fixture = CatalogSuccessorV1Fixture

    @Test
    fun higherSuccessorRetainsExactUnitAndConversionHistory() {
        // CSR-001: unchanged definitions plus a new version/rate are the control.
        val p1 = fixture.p1
        val p2 = fixture.p2
        assertNull(localCatalogUnitShapeRejection(p1))
        assertNull(localCatalogUnitShapeRejection(p2))
        assertNull(localCatalogUnitHistoryRejection(listOf(
            fixture.inertEnvelope(p1), fixture.inertEnvelope(p2))))
        assertNull(localCatalogConversionRevisionRejection(p2, listOf(p1)))
        assertEquals(listOf(fixture.eachDefinition, fixture.cartonDefinition), p2.unitDefinitions)
        assertEquals(p1.skuId, p2.skuId)
        assertEquals(p1.productId, p2.productId)
        assertEquals(p1.baseUnitId, p2.baseUnitId)
        assertEquals(p1.quantityScale, p2.quantityScale)
        assertEquals(p1.tracking, p2.tracking)
        assertEquals(p1.fulfillment, p2.fulfillment)
        assertEquals("000012", p2.code)
        assertEquals(listOf("000000000012"), p2.barcodes)
        assertEquals("12/1", p1.conversions.single().rate.canonicalString())
        assertEquals(Revision(1), p1.conversions.single().revision)
        assertEquals("10/1", p2.conversions.single().rate.canonicalString())
        assertEquals(Revision(2), p2.conversions.single().revision)
    }

    @Test
    fun olderPublicationFailsFreshConversionHighWaterCheck() {
        // CSR-015's fresh rollback is distinct from CSR-005's original-result recovery.
        // Keeping P1 in exact history is allowed; selecting its old version anew is not.
        val retained = listOf(fixture.p1, fixture.p2)
        assertNull(localCatalogUnitHistoryRejection(retained.map(fixture::inertEnvelope)))
        assertNotNull(localCatalogConversionRevisionRejection(fixture.p1, retained))
        assertNull(localCatalogConversionRevisionRejection(fixture.p2, listOf(fixture.p1)))
        // No command retry, stored result recovery or catalog rollback is modeled here.
    }

    @Test
    fun oldConversionVersionRejectsFrozenRateAndBindingMutations() {
        // CSR-013: the exact V-CONVERSION-* inputs, not missing native prerequisites.
        val p1 = fixture.p1
        val p2 = fixture.p2
        val oldRate = UnitConversionRate.parseCanonical("12/1")
        val newRate = UnitConversionRate.parseCanonical("10/1")
        val bag = CatalogUnit(fixture.newCount, Code.exact("BAG"), UnitDimension.COUNT, 0)
        val variants = listOf(
            Mutation("V-CONVERSION-RATE", p2.copy(conversions = listOf(
                p2.conversions.single().copy(revision = Revision(1), rate = newRate)))),
            Mutation("V-CONVERSION-PRODUCT", p2.copy(
                skuId = fixture.conflictSku, productId = fixture.otherProduct,
                skuRevision = Revision(1), code = "000077", barcodes = listOf("000000000077"),
                conversions = listOf(p2.conversions.single().copy(revision = Revision(1), rate = oldRate)))),
            Mutation("V-CONVERSION-SOURCE", p2.copy(
                unitDefinitions = p2.unitDefinitions.orEmpty() + bag,
                conversions = listOf(p2.conversions.single().copy(
                    revision = Revision(1), rate = oldRate, sourceUnitId = fixture.newCount)))),
        )
        assertNull(localCatalogUnitHistoryRejection(listOf(
            fixture.inertEnvelope(p1), fixture.inertEnvelope(p2))))
        for (variant in variants) {
            assertNull(localCatalogUnitShapeRejection(variant.candidate), variant.id)
            assertNotNull(localCatalogUnitHistoryRejection(listOf(
                fixture.inertEnvelope(p1), fixture.inertEnvelope(variant.candidate))), variant.id)
        }
        // V-CONVERSION-TARGET ceases to target the base. The public value refuses
        // that exact input before history validation; this is only a shape slice.
        assertFailsWith<IllegalArgumentException>("V-CONVERSION-TARGET") {
            p2.copy(conversions = listOf(p2.conversions.single().copy(
                revision = Revision(1), rate = oldRate, targetUnitId = fixture.carton)))
        }
        assertEquals(Revision(1), p1.conversions.single().revision)
        assertEquals("12/1", p1.conversions.single().rate.canonicalString())
        assertEquals(fixture.product, p1.productId)
        assertEquals(fixture.carton, p1.conversions.single().sourceUnitId)
        assertEquals(fixture.each, p1.conversions.single().targetUnitId)
    }

    private data class Mutation(val id: String, val candidate: LocalPublishedSkuV1)
}
