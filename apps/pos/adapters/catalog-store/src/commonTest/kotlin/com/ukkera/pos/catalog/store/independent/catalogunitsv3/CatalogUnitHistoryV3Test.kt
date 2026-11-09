package com.ukkera.pos.catalog.store.independent.catalogunitsv3

import com.ukkera.pos.catalog.store.localCatalogConversionRevisionRejection
import com.ukkera.pos.catalog.store.localCatalogUnitHistoryRejection
import com.ukkera.pos.catalog.store.localCatalogUnitShapeRejection
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.UnitConversionRate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** CUA-06/07/09 L1-A only. Controlled content is never authenticated native history. */
class CatalogUnitHistoryV3Test {
    private val f = CatalogUnitsV3Fixture

    @Test
    fun cua07_equal_meaning_and_later_rate_have_no_isolated_identity_conflict() {
        val first = f.firstHistory()
        val second = f.secondHistory()
        assertNull(localCatalogUnitShapeRejection(first))
        assertNull(localCatalogUnitShapeRejection(second))
        assertNull(localCatalogUnitHistoryRejection(listOf(f.controlled(first), f.controlled(second))))
        assertNull(localCatalogConversionRevisionRejection(second, listOf(first)))
        assertEquals(1L, first.conversions.single().revision.value)
        assertEquals("12/1", first.conversions.single().rate.canonicalString())
    }

    @Test
    fun cua07_same_unit_id_cannot_change_code_dimension_or_precision() {
        for (changed in listOf(f.pieceDefinition.copy(code = Code.exact("g")),
            f.pieceDefinition.copy(dimension = UnitDimension.MASS), f.pieceDefinition.copy(maximumScale = 1))) {
            val successor = f.secondHistory().copy(unitDefinitions = listOf(changed, f.cartonDefinition))
            assertNotNull(localCatalogUnitHistoryRejection(listOf(f.controlled(f.firstHistory()), f.controlled(successor))))
        }
    }

    @Test
    fun cua07_used_normalized_code_cannot_name_a_different_id() {
        val prior = listOf(f.controlled(f.firstHistory()))
        val baseline = f.secondHistory().copy(baseUnitId = f.newPieceId, conversions = emptyList(),
            unitDefinitions = listOf(f.pieceDefinition.copy(id = f.newPieceId, code = Code.exact("g"))))
        assertNull(localCatalogUnitShapeRejection(baseline))
        assertNull(localCatalogUnitHistoryRejection(prior + f.controlled(baseline)))

        // The proposed base remains resolved; only its exact code changes to the used code.
        val duplicateCode = baseline.copy(unitDefinitions = listOf(f.pieceDefinition.copy(id = f.newPieceId)))
        assertNull(localCatalogUnitShapeRejection(duplicateCode))
        assertNotNull(localCatalogUnitHistoryRejection(prior + f.controlled(duplicateCode)))
    }

    @Test
    fun cua07_conversion_version_cannot_change_its_rate_or_product() {
        val first = f.firstHistory()
        val changedRate = f.secondHistory().copy(conversions = listOf(first.conversions.single().copy(rate = UnitConversionRate.ratio(10, 1))))
        // Frozen F-CUA-MEANING explicitly retains the revision-1/productB refusal.
        val changedProduct = f.secondHistory().copy(productId = f.productB, conversions = first.conversions)
        // A later revision also retains its product identity under the frozen public contract.
        val changedFutureProduct = f.secondHistory().copy(productId = f.productB)
        assertNull(localCatalogUnitShapeRejection(changedFutureProduct))
        for (successor in listOf(changedRate, changedProduct, changedFutureProduct)) {
            assertNotNull(localCatalogUnitHistoryRejection(listOf(f.controlled(first), f.controlled(successor))))
        }
    }

    @Test
    fun cua07_conversion_identity_cannot_swap_endpoints() {
        val successor = f.secondHistory().copy(baseUnitId = f.carton, conversions = listOf(
            f.firstHistory().conversions.single().copy(sourceUnitId = f.piece, targetUnitId = f.carton)))
        assertNotNull(localCatalogUnitHistoryRejection(listOf(f.controlled(f.firstHistory()), f.controlled(successor))))
    }

    @Test
    fun cua07_shape_refuses_unresolved_base_source_and_excess_sku_precision() {
        val first = f.firstHistory()
        for (invalid in listOf(first.copy(unitDefinitions = emptyList()),
            first.copy(unitDefinitions = listOf(f.pieceDefinition)), first.copy(quantityScale = 1))) {
            assertNotNull(localCatalogUnitShapeRejection(invalid))
        }
        assertFailsWith<IllegalArgumentException> {
            first.copy(conversions = listOf(first.conversions.single().copy(targetUnitId = f.carton)))
        }
        assertFailsWith<IllegalArgumentException> {
            first.copy(conversions = listOf(first.conversions.single().copy(sourceUnitId = f.piece)))
        }
    }

    @Test
    fun cua07_stale_conversion_revision_is_refused_against_later_history() {
        assertNotNull(localCatalogConversionRevisionRejection(f.firstHistory(), listOf(f.secondHistory())))
    }

    @Test
    fun cua06_legacy_raw_id_cannot_receive_an_invented_count_definition() {
        val legacy = f.legacy().copy(baseUnitId = f.legacyRaw)
        val declaration = f.secondHistory().copy(baseUnitId = f.legacyRaw, conversions = emptyList(),
            unitDefinitions = listOf(f.pieceDefinition.copy(id = f.legacyRaw)))
        assertNotNull(localCatalogUnitHistoryRejection(listOf(f.controlled(legacy), f.controlled(declaration))))
        assertNull(legacy.unitDefinitions)
        assertEquals(f.legacyRaw, legacy.baseUnitId)
    }

    @Test
    fun cua09_cross_dimension_shape_refuses_absent_evidence_and_evidence_id_alone() {
        val kilogram = f.pieceDefinition.copy(id = f.kilogram, code = Code.exact("kilogram"), dimension = UnitDimension.MASS, maximumScale = 6)
        val litre = f.pieceDefinition.copy(id = f.litre, code = Code.exact("litre"), dimension = UnitDimension.VOLUME, maximumScale = 6)
        for (evidence in listOf(null, f.evidence)) {
            val proposed = f.legacy().copy(baseUnitId = f.kilogram, quantityScale = 6, unitDefinitions = listOf(kilogram, litre),
                conversions = listOf(f.firstHistory().conversions.single().copy(sourceUnitId = f.litre, targetUnitId = f.kilogram,
                    rate = UnitConversionRate.ratio(4, 5), dimensionalEvidenceId = evidence)))
            assertNotNull(localCatalogUnitShapeRejection(proposed))
        }
    }
}
