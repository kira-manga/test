package com.ukkera.pos.app.independent.catalogproductversionv1

import com.ukkera.pos.app.LocalCompanyProductDraft
import com.ukkera.pos.app.ui.CompanyAttributeType
import com.ukkera.pos.app.ui.CompanyProductAttributeEditor
import com.ukkera.pos.catalog.CatalogAttributeValue
import com.ukkera.pos.catalog.VariantAxis
import com.ukkera.pos.types.Code
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CPR-FROZEN-1 L1 actual shared editor and public draft/snapshot conversions.
 * No native review/signing, durable save, product-version assignment, Master grant,
 * activation or business execution is supplied. Whole L2/L3 cases stay UNBOUND / NOT_RUN.
 */
class CatalogProductVersionFrozenEditorTest {
    private val f = FrozenCatalogProductEditorInputs

    @Test fun cpr009_eachExplicitCombinationProducesOneExactEditorDraft() {
        val blueMedium = assertNotNull(f.editor().toDraft())
        val blackSmall = assertNotNull(f.editor(true).toDraft())
        assertEquals(f.blueMediumSku, blueMedium.skuId)
        assertEquals(f.blackSmallSku, blackSmall.skuId)
        assertEquals(f.product, blueMedium.productId)
        assertEquals(f.product, blackSmall.productId)
        assertEquals(f.selection(), blueMedium.variantValues)
        assertEquals(f.selection(true), blackSmall.variantValues)
        assertEquals("SHIRT-BLUE-M-01", blueMedium.code)
        assertEquals(listOf("0000000000002"), blueMedium.barcodes)
        assertEquals("SHIRT-BLACK-S-01", blackSmall.code)
        assertEquals(listOf("0000000000003"), blackSmall.barcodes)
        f.assertDefinition(assertNotNull(blueMedium.productDefinition), "CPR-009 blue/m")
        f.assertDefinition(assertNotNull(blackSmall.productDefinition), "CPR-009 black/s")
        // Each real conversion returns one selected draft, not a Cartesian batch.
        // Save counts, signed revision 7 and stock conservation require native execution.
    }

    @Test fun cpr003_explicitReuseInputKeepsProductIdentityWithoutInventingVersion() {
        val draft = assertNotNull(f.editor().copy(defineProduct = false).toDraft())
        assertEquals(f.product, draft.productId)
        assertEquals(f.blueMediumSku, draft.skuId)
        assertEquals(f.selection(), draft.variantValues)
        assertNull(draft.productDefinition)
        // Null requests exact native reuse; it is not a completed product body or revision.
    }

    @Test fun cpr007_editorPreservesIndependentEnglishOnlyAndArabicOnlyNames() {
        val english = assertNotNull(assertNotNull(f.editor().toDraft()).productDefinition)
        assertEquals("Cotton shirt", english.englishName)
        assertEquals("", english.arabicName)
        val arabic = assertNotNull(assertNotNull(f.editor().copy(productEnglishName = "", productArabicName = "قميص قطني").toDraft()).productDefinition)
        assertEquals("", arabic.englishName)
        assertEquals("قميص قطني", arabic.arabicName)
        assertEquals(listOf(f.clothing, f.tops), arabic.categoryIds)
        assertEquals(f.definition().variantAxes, arabic.variantAxes)
        assertEquals(f.definition().attributes, arabic.attributes)
    }

    @Test fun cpr007_publicProductNameBoundariesAccept160AndRefuse161OrBothEmpty() {
        for ((language, character) in listOf("english" to "A", "arabic" to "ق")) {
            val input = f.definition()
            val accepted = if (language == "english") input.copy(englishName = character.repeat(160), arabicName = "")
                else input.copy(englishName = "", arabicName = character.repeat(160))
            val snapshot = accepted.snapshot()
            assertEquals(character.repeat(160), if (language == "english") snapshot.englishName else snapshot.arabicName, language)
            assertEquals("", if (language == "english") snapshot.arabicName else snapshot.englishName, language)
            assertFailsWith<IllegalArgumentException>(language) {
                if (language == "english") input.copy(englishName = character.repeat(161), arabicName = "")
                else input.copy(englishName = "", arabicName = character.repeat(161))
            }
        }
        assertFailsWith<IllegalArgumentException> { LocalCompanyProductDraft("", "") }
    }

    @Test fun cpr010_undeclaredOrMissingEditorSelectionRefusesAndCorrectionIsExact() {
        val valid = f.editor()
        val green = valid.copy(variantAxes = listOf(valid.variantAxes[0].copy(selected = "green"), valid.variantAxes[1]))
        val missingSize = valid.copy(variantAxes = listOf(valid.variantAxes[0], valid.variantAxes[1].copy(selected = "")))
        assertNull(green.toDraft(), "CPR-010.A")
        assertNull(missingSize.toDraft(), "CPR-010.B")
        val corrected = assertNotNull(valid.toDraft())
        assertEquals(f.selection(), corrected.variantValues)
        f.assertDefinition(assertNotNull(corrected.productDefinition), "CPR-010 correction")
    }

    @Test fun cpr010_publicDraftCannotAttachMissingForeignOrConflictingAxisSelection() {
        val original = assertNotNull(f.editor().toDraft())
        val values = original.variantValues
        val variants = listOf(
            "B missing size" to values.take(1),
            "C foreign axis" to listOf(values[0].copy(axisId = f.foreignAxis), values[1]),
            "D conflicting duplicate" to (values + values[0].copy(value = Code.exact("black"))))
        for ((variant, choices) in variants) {
            assertFailsWith<IllegalArgumentException>("CPR-010.$variant") { original.copy(variantValues = choices) }
        }
        assertEquals(f.selection(), original.variantValues)
        f.assertDefinition(assertNotNull(original.productDefinition), "CPR-010 original")
        // Typed draft inputs cannot represent the scalar/null wire variant; codecs cover it.
        // This draft has no authenticated parent ID, so native reassignment is unbound here.
    }

    @Test fun cpr011_exactDecimalAndBusinessLikeNoteAreDescriptiveEditorInput() {
        val original = assertNotNull(f.editor().toDraft())
        val attributes = f.editor().productAttributes.toMutableList()
        attributes[2] = CompanyProductAttributeEditor("note", CompanyAttributeType.TEXT, "stock.receive(100)")
        val edited = assertNotNull(f.editor().copy(productCategories = "${f.clothing.value}\n${f.tops.value}\n${f.seasonal.value}",
            productAttributes = attributes).toDraft())
        val product = assertNotNull(edited.productDefinition)
        assertEquals(listOf(f.clothing, f.tops, f.seasonal), product.categoryIds)
        assertEquals("30", assertIs<CatalogAttributeValue.Decimal>(product.attributes[1].value).value.canonicalString())
        assertEquals("stock.receive(100)", assertIs<CatalogAttributeValue.Text>(product.attributes[2].value).value.original)
        val invalid = f.editor().copy(productAttributes = listOf(
            CompanyProductAttributeEditor("wash_temperature", CompanyAttributeType.DECIMAL, "stock.receive(100)")))
        assertFails("CPR-011 declared Decimal rejects text") { invalid.toDraft() }
        f.assertDefinition(assertNotNull(original.productDefinition), "CPR-011 original")
        // No stock, price, purchasing or payment service is invoked by this conversion.
    }

    @Test fun cpr012_realProductSnapshotOwnsNestedListsAgainstLaterMutations() {
        val original = f.definition()
        val categories = original.categoryIds.toMutableList()
        val colorValues = original.variantAxes[0].values.toMutableList()
        val axes = mutableListOf(VariantAxis(f.color, Code.exact("color"), colorValues), original.variantAxes[1])
        val attributes = original.attributes.toMutableList()
        val input = original.copy(categoryIds = categories, variantAxes = axes, attributes = attributes)
        val captured = input.snapshot()
        categories += f.seasonal
        categories.reverse()
        colorValues += Code.exact("green")
        axes.reverse()
        attributes[0] = attributes[0].copy(value = CatalogAttributeValue.Text(Code.exact("linen")))
        attributes.reverse()
        f.assertDefinition(captured, "CPR-012 public snapshot")
    }

    @Test fun cpr012_realEditorConversionPinsBodyAndBlueMediumWhileLaterDraftChanges() {
        val baseline = f.editor()
        val originalAxes = baseline.variantAxes.toMutableList()
        val originalAttributes = baseline.productAttributes.toMutableList()
        val input = baseline.copy(variantAxes = originalAxes, productAttributes = originalAttributes)
        val captured = assertNotNull(input.toDraft())
        originalAxes.reverse()
        originalAttributes.reverse()
        val later = baseline.copy(productEnglishName = "Mutable later draft", productArabicName = "draft second name",
            productCategories = "${f.clothing.value}\n${f.tops.value}\n${f.seasonal.value}", variantAxes = listOf(
                baseline.variantAxes[0].copy(values = "blue\nblack\ngreen", selected = "black"),
                baseline.variantAxes[1].copy(selected = "s")), productAttributes = listOf(
                baseline.productAttributes[0].copy(value = "linen"), baseline.productAttributes[1], baseline.productAttributes[2]))
        val laterDraft = assertNotNull(later.toDraft())
        f.assertDefinition(assertNotNull(captured.productDefinition), "CPR-012 captured editor body")
        assertEquals(f.selection(), captured.variantValues)
        assertEquals(baseline.publicationId, captured.publicationId)
        assertEquals(f.product, captured.productId)
        val laterBody = assertNotNull(laterDraft.productDefinition)
        assertEquals("Mutable later draft", laterBody.englishName)
        assertEquals("draft second name", laterBody.arabicName)
        assertEquals(listOf(f.clothing, f.tops, f.seasonal), laterBody.categoryIds)
        assertEquals(listOf("blue", "black", "green"), laterBody.variantAxes[0].values.map { it.original })
        assertEquals("linen", assertIs<CatalogAttributeValue.Text>(laterBody.attributes[0].value).value.original)
        assertEquals(f.selection(true), laterDraft.variantValues)
        // This verifies capture at toDraft(), not a native operation/digest or save count.
    }
}
