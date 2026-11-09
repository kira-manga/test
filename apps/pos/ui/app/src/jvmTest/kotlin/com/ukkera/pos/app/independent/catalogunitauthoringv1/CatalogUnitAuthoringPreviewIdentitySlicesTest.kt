package com.ukkera.pos.app.independent.catalogunitauthoringv1

import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.types.Code
import com.ukkera.pos.types.CodeNormalization
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Exact preview equality is a necessary input to original matching, not a native
 * mismatch decision. No original Action/Output or accepting gateway is fabricated.
 */
class CatalogUnitAuthoringPreviewIdentitySlicesTest {
    private val f = CatalogUnitAuthoringFrozenInputs

    @Test fun cuae014_allFrozenUnitMutationsAreDifferentPreviewContent_supportingEqualityOnly() {
        for (row in f.rows("retained_unit_mutations")) {
            val message = f.variant("CUAE-014", row.text("variant_id"))
            val original = f.preview()
            val units = requireNotNull(original.unitDefinitions).toMutableList()
            val changed = if (row.text("variant_id") == "definitions-omitted") original.copy(unitDefinitions = null)
            else {
                val index = if (row.text("path").startsWith("unitDefinitions[base]")) 0 else 1
                val unit = units[index]
                units[index] = when (row.text("path").substringAfter("].")) {
                    "id" -> unit.copy(id = DomainId(row.text("value")))
                    "code" -> row.obj("value").let { code -> unit.copy(code = Code(code.text("original"),
                        code.text("normalizedKey"), CodeNormalization.valueOf(code.text("normalization")))) }
                    "dimension" -> unit.copy(dimension = UnitDimension.valueOf(row.text("value")))
                    "maximumScale" -> unit.copy(maximumScale = row.int("value"))
                    else -> error("Unmapped frozen unit substitution")
                }
                original.copy(unitDefinitions = units.toList())
            }
            assertNotEquals(original, changed, message)
            assertEquals(original, changed.copy(unitDefinitions = original.unitDefinitions), message)
            f.assertPackPreview(original, message)
        }
    }

    @Test fun cuae015_allFrozenConversionMutationsAreDifferentPreviewContent_supportingEqualityOnly() {
        for (row in f.rows("retained_conversion_mutations")) {
            val message = f.variant("CUAE-015", row.text("variant_id"))
            val original = f.preview()
            val conversion = original.conversions.single()
            val changed = original.copy(conversions = if (row.text("variant_id") == "conversions-omitted") emptyList()
            else listOf(when (row.text("path").substringAfter("conversion.")) {
                "id" -> conversion.copy(id = DomainId(row.text("value")))
                "revision" -> conversion.copy(revision = Revision(row.text("value").toLong()))
                "sourceUnitId" -> conversion.copy(sourceUnitId = DomainId(row.text("value")))
                "targetUnitId" -> conversion.copy(targetUnitId = DomainId(row.text("value")))
                "reducedRate" -> conversion.copy(rate = UnitConversionRate.parseCanonical(row.text("value")))
                "evidenceId" -> conversion.copy(massVolumeEvidenceId = DomainId(row.text("value")))
                else -> error("Unmapped frozen conversion substitution")
            }))
            assertNotEquals(original, changed, message)
            assertEquals(original, changed.copy(conversions = original.conversions), message)
            f.assertPackPreview(original, message)
        }
    }
}
