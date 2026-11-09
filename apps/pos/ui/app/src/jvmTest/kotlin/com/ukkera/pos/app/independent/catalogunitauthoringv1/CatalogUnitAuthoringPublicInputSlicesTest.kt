package com.ukkera.pos.app.independent.catalogunitauthoringv1

import com.ukkera.pos.app.LocalCompanySkuDraft
import com.ukkera.pos.app.ui.CompanySkuEditor
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Shared public input and real editor conversion only; no native, journal, Master or business-state pass. */
class CatalogUnitAuthoringPublicInputSlicesTest {
    private val f = CatalogUnitAuthoringFrozenInputs

    @Test fun cuae001_omittedAndEmptyDraftExtensionsRemainAbsent_supportingFieldsOnly() {
        for ((variant, empty) in listOf("definitions-omitted-conversions-omitted" to false,
            "definitions-omitted-conversions-empty" to true)) {
            val message = f.variant("CUAE-001", variant)
            val draft = f.legacyDraft(empty)
            draft.validatePublicationInput()
            f.assertLegacyDraftFields(draft, message)
            assertNull(draft.unitDefinitions, message)
            assertEquals(emptyList(), draft.conversions, message)
            assertEquals(f.legacy.int("quantityScale"), draft.quantityScale, message)
        }
        // Mandatory price and missing canSell/revision inputs prevent the exact F-LEGACY
        // no-price/revision-1 golden-through-draft variant. That obligation stays unbound.
    }

    @Test fun cuae002_allFrozenDimensionsAtBothPrecisionEndpoints_surviveDraftAndEditor() {
        for (row in f.rows("base_dimension_precision_variants")) {
            val message = f.variant("CUAE-002", row.text("variant_id"))
            val dimension = UnitDimension.valueOf(row.text("dimension"))
            val base = f.units(false).single().copy(dimension = dimension, maximumScale = row.int("maximumScale"))
            val direct = f.draft().copy(quantityScale = row.int("skuQuantityScale"), unitDefinitions = listOf(base))
            direct.validatePublicationInput()
            val editor = f.editor().copy(unitDimension = dimension, baseMaximumScale = row.int("maximumScale"),
                quantityScale = row.int("skuQuantityScale"))
            val entered = assertNotNull(editor.toDraft(), message)
            assertEquals(direct, entered, message)
            assertEquals(row.text("dimension"), entered.unitDefinitions?.single()?.dimension?.name, message)
            assertEquals(row.int("maximumScale"), entered.unitDefinitions?.single()?.maximumScale, message)
            assertEquals(row.int("skuQuantityScale"), entered.quantityScale, message)
            assertEquals(emptyList(), entered.conversions, message)
            f.assertLegacyDraftFields(entered, message)
        }
        // The base-only generic canonical golden cannot be acquired from the new authoring
        // template. Domain/codec v3 regressions remain separate, unchanged coverage.
    }

    @Test fun cuae003_allLiteralCodesStayExactInTheSelectedCountEditor() {
        for (row in f.rows("literal_code_variants")) {
            val message = f.variant("CUAE-003", row.text("variant_id"))
            val identity = f.bindingId("literal-unit:${row.text("variant_id")}")
            val draft = assertNotNull(f.editor().copy(baseUnitIdentity = identity.value,
                baseUnitCode = row.text("input_text"), unitDimension = UnitDimension.COUNT).toDraft(), message)
            val unit = assertNotNull(draft.unitDefinitions, message).single()
            assertEquals(identity, unit.id, message)
            assertEquals(row.obj("expected_code").text("original"), unit.code.original, message)
            assertEquals(row.obj("expected_code").text("normalizedKey"), unit.code.normalizedKey, message)
            assertEquals(row.obj("expected_code").text("normalization"), unit.code.normalization.name, message)
            assertEquals(row.text("selected_dimension"), unit.dimension.name, message)
            assertEquals(row.text("expected_sku_code"), draft.code, message)
        }
    }

    @Test fun cuae004_representableMissingAndNoncanonicalBaseFieldsRejectAndCorrectionRestoresExactInput() {
        for (row in f.rows("invalid_base_variants").filter { it.text("variant_id") != "dimension-unknown" }) {
            val message = f.variant("CUAE-004", row.text("variant_id"))
            val valid = f.editor()
            val invalid = when (row.text("variant_id")) {
                "id-omitted", "id-empty" -> valid.copy(baseUnitIdentity = "")
                "id-malformed", "id-uppercase" -> valid.copy(baseUnitIdentity = row.text("value"))
                "code-empty" -> valid.copy(baseUnitCode = row.text("value"))
                "dimension-omitted" -> valid.copy(unitDimension = null)
                "precision-omitted" -> valid.copy(baseMaximumScale = null)
                else -> error("Unmapped frozen base input")
            }
            rejects(invalid, message)
            assertEquals(f.draft(), assertNotNull(valid.toDraft(), message), message)
        }
    }

    @Test fun cuae004_unknownDimensionCannotConstructTheSharedEditorEnum_constructorSliceOnly() {
        val row = f.rows("invalid_base_variants").single { it.text("variant_id") == "dimension-unknown" }
        val message = f.variant("CUAE-004", row.text("variant_id"))
        assertFailsWith<IllegalArgumentException>(message) { UnitDimension.valueOf(row.text("value")) }
        // There is no free-text dimension parser in this editor. No unknown-dimension
        // native publication or UI injection result is manufactured by this assertion.
    }

    @Test fun cuae005_frozenBaseAndSkuPrecisionRelationshipsAreNotClamped() {
        for (row in f.rows("precision_variants")) {
            val message = f.variant("CUAE-005", row.text("variant_id"))
            val editor = f.editor().copy(baseMaximumScale = row.int("baseMaximumScale"), quantityScale = row.int("skuQuantityScale"))
            if (row.text("expected") == "REJECT") rejects(editor, message)
            else {
                val draft = assertNotNull(editor.toDraft(), message)
                assertEquals(row.int("baseMaximumScale"), draft.unitDefinitions?.single()?.maximumScale, message)
                assertEquals(row.int("skuQuantityScale"), draft.quantityScale, message)
            }
        }
    }

    @Test fun cuae007_exactPackEntryPreservesIndependentDefinitionsAndBasePerSourceRate() {
        val message = f.variant("CUAE-007", "exact-count-pack-24-over-2")
        val direct = f.draft(true)
        direct.validatePublicationInput()
        val entered = assertNotNull(f.editor(true).toDraft(), message)
        assertEquals(direct, entered, message)
        f.assertUnits(entered.unitDefinitions, f.pack.array("expected_unit_definitions"), message)
        assertEquals(1, entered.conversions.size, message)
        f.assertPackConversion(entered.conversions.single(), message)
        assertEquals(f.price.text("decimal"), entered.unitPriceEgp, message)
        f.assertLegacyDraftFields(entered, message)
    }

    @Test fun cuae008_sourcePrecisionIsIndependentOfBaseAndSkuPrecision() {
        for (row in f.rows("source_precision_variants")) {
            val message = f.variant("CUAE-008", row.text("variant_id"))
            val editor = f.editor(true).copy(baseMaximumScale = row.int("baseMaximumScale"),
                quantityScale = row.int("skuQuantityScale"), packMaximumScale = row.int("sourceMaximumScale"))
            if (row.text("expected") == "REJECT") rejects(editor, message)
            else {
                val draft = assertNotNull(editor.toDraft(), message)
                val units = assertNotNull(draft.unitDefinitions, message)
                assertEquals(row.int("baseMaximumScale"), units.single { it.id == f.id("base_unit") }.maximumScale, message)
                assertEquals(row.int("sourceMaximumScale"), units.single { it.id == f.id("source_unit") }.maximumScale, message)
                assertEquals(row.int("skuQuantityScale"), draft.quantityScale, message)
                f.assertPackConversion(draft.conversions.single(), message)
            }
        }
    }

    @Test fun cuae009_allValidRatioTextBoundariesProduceTheFrozenReducedRate() {
        for (row in f.rows("valid_ratio_variants")) {
            val message = f.variant("CUAE-009", row.text("variant_id"))
            val draft = assertNotNull(f.editor(true).copy(conversionNumerator = row.text("numerator_text"),
                conversionDenominator = row.text("denominator_text")).toDraft(), message)
            f.assertPackConversion(draft.conversions.single(), message, row.text("expected_reduced_rate"))
            f.assertUnits(draft.unitDefinitions, f.pack.array("expected_unit_definitions"), message)
            assertEquals(0, draft.quantityScale, message)
        }
    }

    @Test fun cuae010_allFrozenInvalidRatioTextsRejectWithoutSubstitutionAndCorrectionIsExact() {
        for (row in f.rows("invalid_ratio_variants")) {
            val message = f.variant("CUAE-010", row.text("variant_id"))
            rejects(f.editor(true).copy(conversionNumerator = row.optionalText("numerator_text").orEmpty(),
                conversionDenominator = row.optionalText("denominator_text").orEmpty()), message)
            f.assertPackConversion(assertNotNull(f.editor(true).toDraft(), message).conversions.single(), message)
        }
    }

    @Test fun cuae011_representableEnabledPackOmissionsRejectRatherThanDisappear() {
        for (row in f.rows("incomplete_pack_variants").filter { it.text("variant_id") != "source-dimension-omitted" }) {
            val message = f.variant("CUAE-011", row.text("variant_id"))
            val valid = f.editor(true)
            val invalid = when (row.text("variant_id")) {
                "source-id-omitted" -> valid.copy(packUnitIdentity = "")
                "source-code-empty" -> valid.copy(packUnitCode = "")
                "source-precision-omitted" -> valid.copy(packMaximumScale = null)
                "conversion-id-omitted" -> valid.copy(conversionIdentity = "")
                "conversion-id-malformed", "conversion-id-uppercase" -> valid.copy(conversionIdentity = row.text("value"))
                "numerator-omitted" -> valid.copy(conversionNumerator = "")
                "denominator-omitted" -> valid.copy(conversionDenominator = "")
                else -> error("Unmapped frozen pack input")
            }
            assertTrue(invalid.packEnabled, message)
            rejects(invalid, message)
            assertEquals(f.draft(true), assertNotNull(valid.toDraft(), message), message)
        }
        // Source dimension is the same explicitly selected shared dimension, not an
        // independently optional field. Its source-only omission variant stays unbound.
    }

    @Test fun cuae011_whollyAbsentPackIsAnExactBaseOnlyDraft() {
        val message = f.variant("CUAE-011", "pack-wholly-absent")
        val draft = assertNotNull(f.editor(false).toDraft(), message)
        f.assertUnits(draft.unitDefinitions, f.definition("F-BASE-ONLY").array("expected_unit_definitions"), message)
        assertEquals(emptyList(), draft.conversions, message)
    }

    @Test fun cuae012_frozenInvalidEndpointsDefinitionsAndBareEvidenceRefusePublicDraftConstruction() {
        for (row in f.rows("invalid_endpoint_variants")) {
            val message = f.variant("CUAE-012", row.text("variant_id"))
            assertFailsWith<IllegalArgumentException>(message) {
                val input = f.draft(true)
                val original = input.conversions.single()
                val changed = when (row.text("variant_id")) {
                    "same-source-target" -> input.copy(conversions = listOf(original.copy(sourceUnitId = DomainId(row.text("value")))))
                    "target-not-base" -> input.copy(conversions = listOf(original.copy(targetUnitId = DomainId(row.text("value")))))
                    "fresh-source-unresolved" -> input.copy(unitDefinitions = row.array("definitions").map { f.unit(it.jsonObject) },
                        conversions = listOf(original.copy(sourceUnitId = DomainId(row.text("value")))))
                    "cross-dimension-without-evidence", "cross-dimension-bare-evidence-id" -> input.copy(
                        unitDefinitions = listOf(f.units(false).single(), f.unit(row.obj("source_definition"))),
                        conversions = listOf(original.copy(sourceUnitId = DomainId(row.text("conversion_source")),
                            targetUnitId = DomainId(row.text("conversion_target")),
                            massVolumeEvidenceId = row.optionalText("evidenceId")?.let(::DomainId))))
                    else -> error("Unmapped frozen endpoint input")
                }
                changed.validatePublicationInput()
            }
        }
        // No rejected authoritative command or business-state read is inferred from a constructor refusal.
    }

    private fun rejects(editor: CompanySkuEditor, message: String) {
        val result: LocalCompanySkuDraft? = try { editor.toDraft() }
        catch (_: IllegalArgumentException) { null }
        assertNull(result, message)
    }
}
