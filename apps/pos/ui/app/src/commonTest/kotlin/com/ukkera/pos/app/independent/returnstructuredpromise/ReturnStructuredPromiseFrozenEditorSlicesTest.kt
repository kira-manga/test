package com.ukkera.pos.app.independent.returnstructuredpromise

import com.ukkera.pos.app.LocalCompanyPriceTaxInclusion
import com.ukkera.pos.app.LocalCompanyReturnPromiseDraft
import com.ukkera.pos.app.ui.CompanySkuEditor
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Frozen v1 public-draft and actual shared editor conversion slices of D01-D10,
 * D15-D18 and U01. Editor text is user entry, not a quoted numeric wire scalar.
 * This does not execute native signing, gateway staging, Master authority or L4
 * rendering/RTL/accessibility/device behavior. All 35 whole cases remain unclaimed.
 */
class ReturnStructuredPromiseFrozenEditorSlicesTest {
    private val f = FrozenReturnStructuredPromiseInputs

    @Test
    fun rspD01To06_allEightValidPublicDraftsRetainExactOriginalAndOptionalValues() {
        for (vector in f.rows("validInputs")) {
            assertDraft(draft(vector.getValue("input").jsonObject), vector.getValue("expectedExtension").jsonObject,
                f.base, f.text(vector, "id"))
        }
    }

    @Test
    fun rspD07_allFifteenPresenceVectorsBindRealPublicDraftValidation() {
        for (vector in f.rows("presenceVectors")) {
            val id = "RSP-V1-D07/" + f.text(vector, "id")
            val input = vector.getValue("input").jsonObject
            if (f.text(vector, "outcome") == "VALID") {
                assertDraft(draft(input), vector.getValue("expectedExtension").jsonObject, f.base, id)
            } else {
                val retained = draft(f.input("V-ORIGINAL"))
                assertFails(id) { draft(input) }
                assertDraft(retained, f.expected("V-ORIGINAL"), f.base, id + " retained")
            }
        }
    }

    @Test
    fun rspD08To10_representableInvalidRangesRefusePublicDraftWithoutPartialValues() {
        for (vector in f.rows("invalidRangeVectors")) {
            val input = vector.getValue("input").jsonObject
            if (input.values.any { it.jsonPrimitive.intOrNull == null }) continue
            val retained = draft(f.input("V-ORIGINAL"))
            assertFails(f.text(vector, "id")) { draft(input) }
            assertDraft(retained, f.expected("V-ORIGINAL"), f.base, f.text(vector, "id") + " retained")
        }
        // Both Int-overflow vectors bind the real editor below; no harness parse is a production rejection.
    }

    @Test
    fun rspD15_literalNumbersAndJsonLookingTextDoNotFillDraftPeriods() {
        val text = f.other("F-TEXT-NUMBERS")
        val base = JsonObject(f.base + mapOf("literalEnglish" to text.getValue("literalEnglish"),
            "literalArabic" to text.getValue("literalArabic")))
        for (id in listOf("V-ABSENT", "V-ORIGINAL")) {
            assertDraft(draft(f.input(id), base), f.expected(id), base, "RSP-V1-D15/$id")
            assertDraft(assertNotNull(assertNotNull(editor(f.input(id), base).toDraft()).returnPromise),
                f.expected(id), base, "RSP-V1-D15 editor/$id")
        }
    }

    @Test
    fun rspD16_originalCategoryStaysExactWithoutInference() {
        val base = JsonObject(f.base + ("originalCategory" to f.other("F-CATEGORY-OTHER").getValue("originalCategory")))
        for (id in listOf("V-ABSENT", "V-ORIGINAL")) {
            assertDraft(draft(f.input(id), base), f.expected(id), base, "RSP-V1-D16/$id")
        }
    }

    @Test
    fun rspD17_mutatingCallerMapCannotRewriteProducedPublicDraft() {
        val caller = f.input("V-ORIGINAL").toMutableMap()
        val baseCaller = f.base.toMutableMap()
        val retained = draft(JsonObject(caller), JsonObject(baseCaller))
        caller.clear()
        caller.putAll(f.input("V-LATER-SHORTER"))
        baseCaller["literalEnglish"] = f.other("F-TEXT-NUMBERS").getValue("literalEnglish")
        baseCaller["originalCategory"] = f.other("F-CATEGORY-OTHER").getValue("originalCategory")
        assertDraft(retained, f.expected("V-ORIGINAL"), f.base, "RSP-V1-D17")
    }

    @Test
    fun rspD18_clearedPublicDraftIsSeparateAndDefectPairCannotBePartlyCleared() {
        val retained = draft(f.input("V-ORIGINAL"))
        val cleared = retained.copy(defectiveReceiptWindowDays = null, defectiveResolutionDays = null)
        val changeOnly = JsonObject(f.expected("V-ORIGINAL").filterKeys { it == "changeOfMindReceiptWindowDays" })
        assertDraft(cleared, changeOnly, f.base, "RSP-V1-D18 pair cleared")
        assertDraft(cleared.copy(changeOfMindReceiptWindowDays = null), f.expected("V-ABSENT"), f.base,
            "RSP-V1-D18 all cleared")
        assertFails("RSP-V1-D18 receipt-only clear") { retained.copy(defectiveReceiptWindowDays = null) }
        assertFails("RSP-V1-D18 resolution-only clear") { retained.copy(defectiveResolutionDays = null) }
        assertDraft(retained, f.expected("V-ORIGINAL"), f.base, "RSP-V1-D18 retained")
    }

    @Test
    fun rspU01_allEightValidEditorInputsProduceTheFrozenExplicitOrUnspecifiedDraft() {
        for (vector in f.rows("validInputs")) {
            val value = editor(vector.getValue("input").jsonObject)
            val result = assertNotNull(value.toDraft(), f.text(vector, "id"))
            assertDraft(assertNotNull(result.returnPromise), vector.getValue("expectedExtension").jsonObject,
                f.base, "RSP-V1-U01/" + f.text(vector, "id"))
        }
    }

    @Test
    fun rspU01_allPresenceVectorsExposeValidationInsteadOfACompletedDraft() {
        for (vector in f.rows("presenceVectors")) {
            val value = editor(vector.getValue("input").jsonObject)
            val id = "RSP-V1-U01/" + f.text(vector, "id")
            if (f.text(vector, "outcome") == "VALID") {
                assertDraft(assertNotNull(assertNotNull(value.toDraft(), id).returnPromise),
                    vector.getValue("expectedExtension").jsonObject, f.base, id)
            } else {
                assertFalse(value.complete, id + " incomplete")
                assertNull(value.toDraft(), id + " no produced draft")
            }
        }
    }

    @Test
    fun rspU01_allTwelveInvalidRangesIncludingOverflowCannotProduceEditorDraft() {
        for (vector in f.rows("invalidRangeVectors")) {
            val retained = assertNotNull(assertNotNull(editor(f.input("V-ORIGINAL")).toDraft()).returnPromise)
            val rejected = editor(vector.getValue("input").jsonObject)
            val id = "RSP-V1-U01/" + f.text(vector, "id")
            assertFalse(rejected.complete, id + " incomplete")
            assertNull(rejected.toDraft(), id + " no produced draft")
            assertDraft(retained, f.expected("V-ORIGINAL"), f.base, id + " retained")
        }
    }

    @Test
    fun rspU01_clearingBothDefectFieldsAndThenChangeOfMindLeavesUnspecifiedValues() {
        val original = editor(f.input("V-ORIGINAL"))
        val retained = assertNotNull(assertNotNull(original.toDraft()).returnPromise)
        val pairCleared = original.copy(defectiveReceiptWindowDays = "", defectiveResolutionDays = "")
        val changeOnly = JsonObject(f.expected("V-ORIGINAL").filterKeys { it == "changeOfMindReceiptWindowDays" })
        assertDraft(assertNotNull(assertNotNull(pairCleared.toDraft()).returnPromise), changeOnly, f.base,
            "RSP-V1-U01 pair cleared")
        val allCleared = pairCleared.copy(changeOfMindReceiptWindowDays = "")
        assertDraft(assertNotNull(assertNotNull(allCleared.toDraft()).returnPromise), f.expected("V-ABSENT"), f.base,
            "RSP-V1-U01 all unspecified")
        for (partial in listOf(original.copy(defectiveReceiptWindowDays = ""), original.copy(defectiveResolutionDays = ""))) {
            assertFalse(partial.complete)
            assertNull(partial.toDraft())
        }
        assertDraft(retained, f.expected("V-ORIGINAL"), f.base, "RSP-V1-U01 retained")
    }

    private fun draft(input: JsonObject, base: JsonObject = f.base): LocalCompanyReturnPromiseDraft =
        LocalCompanyReturnPromiseDraft(DomainId(f.text(base, "originalPromiseId")),
            Revision(f.text(base, "originalRevision").toLong()), f.text(base, "originalCategory"),
            f.text(base, "literalEnglish"), f.text(base, "literalArabic"),
            f.day(input, "changeOfMindReceiptWindowDays"), f.day(input, "defectiveReceiptWindowDays"),
            f.day(input, "defectiveResolutionDays"))

    private fun editor(input: JsonObject, base: JsonObject = f.base): CompanySkuEditor {
        val operation = f.other("F-OPERATION")
        // Reused frozen synthetic IDs and minimal SKU fields are conversion prerequisites,
        // not evidence of a native publication, retained source, authorized product reuse or sale.
        return CompanySkuEditor(publicationId = DomainId(f.text(f.base, "organizationId")),
            skuId = DomainId(f.text(f.base, "companyId")), productId = DomainId(f.text(f.base, "branchId")),
            baseUnitId = DomainId(f.text(f.base, "originalPromiseId")),
            priceRuleId = DomainId(f.text(operation, "operationId")), taxPolicyId = DomainId(f.text(operation, "actorId")),
            englishName = f.text(f.base, "literalEnglish"), code = f.text(f.base, "originalCategory"),
            price = "1", taxCategory = f.text(f.base, "originalCategory"), taxPercent = "0",
            inclusion = LocalCompanyPriceTaxInclusion.EXCLUDED, taxConfirmed = true,
            baseUnitCode = f.text(f.base, "originalCategory"), unitDimension = UnitDimension.COUNT, baseMaximumScale = 0,
            returnPromiseEnabled = true, returnPolicyId = f.text(base, "originalPromiseId"),
            returnPolicyRevision = f.text(base, "originalRevision"), returnCategory = f.text(base, "originalCategory"),
            returnEnglishTerms = f.text(base, "literalEnglish"), returnArabicTerms = f.text(base, "literalArabic"),
            changeOfMindReceiptWindowDays = entry(input, "changeOfMindReceiptWindowDays"),
            defectiveReceiptWindowDays = entry(input, "defectiveReceiptWindowDays"),
            defectiveResolutionDays = entry(input, "defectiveResolutionDays"))
    }

    private fun entry(input: JsonObject, name: String): String =
        input[name]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content ?: ""

    private fun assertDraft(value: LocalCompanyReturnPromiseDraft, expected: JsonObject, base: JsonObject, label: String) {
        val actual = JsonObject(buildMap {
            value.changeOfMindReceiptWindowDays?.let { put("changeOfMindReceiptWindowDays", JsonPrimitive(it)) }
            value.defectiveReceiptWindowDays?.let { put("defectiveReceiptWindowDays", JsonPrimitive(it)) }
            value.defectiveResolutionDays?.let { put("defectiveResolutionDays", JsonPrimitive(it)) }
        })
        assertEquals(expected, actual, label)
        assertEquals(f.text(base, "originalPromiseId"), value.policyId.value, label)
        assertEquals(f.text(base, "originalRevision"), value.policyRevision.value.toString(), label)
        assertEquals(f.text(base, "originalCategory"), value.categoryCode, label)
        assertEquals(f.text(base, "literalEnglish"), value.englishTerms, label)
        assertEquals(f.text(base, "literalArabic"), value.arabicTerms, label)
    }
}
