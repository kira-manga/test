package com.ukkera.pos.sales.store.independent.returnstructuredpromise

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalReturnCaseSourceReferenceV1
import com.ukkera.pos.sales.store.LocalSalesReturnPolicyCaptureCodecV2
import com.ukkera.pos.sales.store.LocalSalesReturnPolicyCaptureInputV2
import com.ukkera.pos.sales.store.LocalSalesReturnPolicyCaptureV2
import com.ukkera.pos.sales.store.LocalSalesReturnPortionCaptureV2
import com.ukkera.pos.sales.store.LocalSalesReturnPromiseDescriptorV2
import com.ukkera.pos.sales.store.LocalSalesReturnPromiseStateV2
import com.ukkera.pos.sales.store.LocalSalesReturnPublicationV2
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * Frozen v1 fields bound to the actual quote companion codec, not a quote transaction.
 * The codec supplies a valid syntax carrier; only frozen field values/presence are
 * oracles. Its source rows/digests are not genuine native/retained authority.
 * Whole host goldens, signing, persistence, quote/source joins, later rights,
 * protected gateways, legal qualification, refunds and whole cases remain UNBOUND.
 */
class ReturnStructuredPromiseFrozenCaptureCodecSlicesTest {
    private val f = FrozenReturnStructuredPromiseInputs
    private val codec = LocalSalesReturnPolicyCaptureCodecV2

    @Test
    fun rspD01To06_allEightValidDescriptorsSurviveActualCaptureCodecRoundTrip() {
        for (vector in f.rows("validInputs")) {
            val id = f.text(vector, "id")
            val input = vector.getValue("input").jsonObject
            val value = carrier(descriptor(input))
            val bytes = codec.encode(value)
            val read = codec.decode(bytes)
            assertCapture(read, vector.getValue("expectedExtension").jsonObject, id)
            assertEquals(bytes, codec.encode(read), id + " round trip")
        }
    }

    @Test
    fun rspD07_allFifteenPresenceVectorsBindDescriptorAndCaptureValidation() {
        for (vector in f.rows("presenceVectors")) {
            val id = "RSP-V1-D07/" + f.text(vector, "id")
            val input = vector.getValue("input").jsonObject
            if (f.text(vector, "outcome") == "VALID") {
                assertCapture(codec.decode(codec.encode(carrier(descriptor(input)))),
                    vector.getValue("expectedExtension").jsonObject, id)
            } else {
                assertFails(id + " actual descriptor") { descriptor(input) }
                assertRawRefused(rawCarrier(input), id)
            }
        }
        // Valid authoring nulls use typed nulls/omission; raw signed-null admission is not asserted.
    }

    @Test
    fun rspD08To10_allTwelveInvalidRangeVectorsRefuseActualCaptureDecode() {
        for (vector in f.rows("invalidRangeVectors")) {
            val input = vector.getValue("input").jsonObject
            val id = f.text(vector, "id")
            assertRawRefused(rawCarrier(input), id)
            if (input.values.all { it.jsonPrimitive.intOrNull != null }) {
                assertFails(id + " actual descriptor") { descriptor(input) }
            }
        }
        // Int overflow is rejected by the real codec, never counted from a harness parser.
    }

    @Test
    fun rspD11_allFifteenWrongTypedVectorsRefuseWithoutCanonicalCoercion() {
        for (vector in f.rows("invalidTypeVectors")) {
            assertRawRefused(rawCarrier(vector.getValue("input").jsonObject),
                "RSP-V1-D11/" + f.text(vector, "id"))
        }
    }

    @Test
    fun rspD12_noncanonicalDayScalarsAndDuplicateMemberNeverBecomeEquivalentCapture() {
        val supported = setOf("M-C-QUOTED", "M-C-FRACTION-SPELLING", "M-C-EXPONENT",
            "M-C-PLUS", "M-C-LEADING-ZERO", "M-C-DUPLICATE")
        for (vector in f.memberRows("noncanonicalOrUnsupported").filter { f.text(it, "id") in supported }) {
            val value = codec.encode(carrier(descriptor(f.input("V-ORIGINAL"))))
            val original = promiseFields(value)
            val fragment = f.text(vector, "fragment")
            val edited = original.entries.joinToString(",", "{", "}") { entry ->
                if (entry.key == "changeOfMindReceiptWindowDays") fragment
                else JsonObject(mapOf(entry.key to entry.value)).toString().removePrefix("{").removeSuffix("}")
            }
            assertRawRefused(replacePromise(value, edited), "RSP-V1-D12/" + f.text(vector, "id"))
        }
        // Alias/case full-host handling and valid raw signed nulls remain unbound.
    }

    @Test
    fun rspD17_mutatingCallerMapAndProducingOtherValuesCannotRewriteEarlierCaptureValue() {
        val caller = f.input("V-ORIGINAL").toMutableMap()
        val retained = carrier(descriptor(JsonObject(caller)))
        val before = codec.encode(retained)
        caller.clear()
        caller.putAll(f.input("V-LATER-SHORTER"))
        codec.encode(carrier(descriptor(JsonObject(caller))))
        codec.encode(carrier(descriptor(f.input("V-LATER-LONGER"))))
        assertCapture(retained, f.expected("V-ORIGINAL"), "RSP-V1-D17 retained")
        assertEquals(before, codec.encode(retained))
        assertCapture(codec.decode(before), f.expected("V-ORIGINAL"), "RSP-V1-D17 reread")
        // These are separate codec values, not later-policy activation or two durable results.
    }

    @Test
    fun rspD18_clearingSeparateDescriptorValuesPreservesEarlierCaptureAndRefusesPartialPair() {
        val original = descriptor(f.input("V-ORIGINAL"))
        val retained = carrier(original)
        val before = codec.encode(retained)
        val cleared = original.copy(defectiveReceiptWindowDays = null, defectiveResolutionDays = null)
        val changeOnly = JsonObject(f.expected("V-ORIGINAL").filterKeys { it == "changeOfMindReceiptWindowDays" })
        assertCapture(codec.decode(codec.encode(carrier(cleared))), changeOnly, "RSP-V1-D18 pair cleared")
        assertCapture(codec.decode(codec.encode(carrier(cleared.copy(changeOfMindReceiptWindowDays = null)))),
            f.expected("V-ABSENT"), "RSP-V1-D18 all cleared")
        assertFails("RSP-V1-D18 receipt-only clear") { original.copy(defectiveReceiptWindowDays = null) }
        assertFails("RSP-V1-D18 resolution-only clear") { original.copy(defectiveResolutionDays = null) }
        assertEquals(before, codec.encode(retained))
        assertCapture(codec.decode(before), f.expected("V-ORIGINAL"), "RSP-V1-D18 retained")
    }

    private fun descriptor(input: JsonObject): LocalSalesReturnPromiseDescriptorV2 =
        LocalSalesReturnPromiseDescriptorV2(DomainId(f.text(f.base, "originalPromiseId")),
            Revision(f.text(f.base, "originalRevision").toLong()), f.text(f.base, "originalCategory"),
            sha256(f.base.toString().encodeToByteArray()), f.day(input, "changeOfMindReceiptWindowDays"),
            f.day(input, "defectiveReceiptWindowDays"), f.day(input, "defectiveResolutionDays"))

    private fun carrier(promise: LocalSalesReturnPromiseDescriptorV2): LocalSalesReturnPolicyCaptureV2 {
        val operation = f.other("F-OPERATION")
        val publicationId = DomainId(f.text(f.base, "organizationId"))
        val operationId = OperationId(f.text(operation, "operationId"))
        val actorId = DomainId(f.text(operation, "actorId"))
        val source = LocalReturnCaseSourceReferenceV1("local.catalog.publication", publicationId,
            "local.catalog.publication/v1", Revision(1), sha256(f.base.toString().encodeToByteArray()),
            operationId, actorId, "local.configuration.write", 1)
        // Frozen synthetic IDs are reused for opaque carrier slots. These mandatory
        // source/quote fields merely reach the codec; no source or quote golden is invented.
        val publication = LocalSalesReturnPublicationV2(DomainId(f.text(f.base, "originalPromiseId")),
            Revision(0), actorId, source, LocalSalesReturnPromiseStateV2.SIGNED_PRESENT, promise)
        val portion = LocalSalesReturnPortionCaptureV2(DomainId(f.text(f.base, "branchId")),
            DomainId(f.text(f.base, "companyId")), publicationId)
        return LocalSalesReturnPolicyCaptureV2(BranchScope(DomainId(f.text(f.base, "organizationId")),
            DomainId(f.text(f.base, "companyId")), DomainId(f.text(f.base, "branchId"))),
            DomainId(operationId.value), DomainId(f.text(f.base, "branchId")), Revision(0),
            sha256(f.base.toString().encodeToByteArray()), operationId,
            LocalSalesReturnPolicyCaptureInputV2(listOf(portion), listOf(publication)))
    }

    private fun rawCarrier(input: JsonObject): StoredBytes {
        val valid = codec.encode(carrier(descriptor(f.input("V-ORIGINAL"))))
        val original = promiseFields(valid)
        val changed = linkedMapOf<String, JsonElement>()
        for ((key, element) in original) {
            if (key !in f.extensionNames) changed[key] = element
            else input[key]?.let { changed[key] = it }
        }
        return replacePromise(valid, JsonObject(changed).toString())
    }

    private fun replacePromise(value: StoredBytes, newPromise: String): StoredBytes {
        val text = value.copyBytes().decodeToString()
        val prior = promiseFields(value).toString()
        val index = text.indexOf(prior)
        check(index >= 0 && text.indexOf(prior, index + prior.length) < 0) { "Carrier promise must bind exactly once" }
        return StoredBytes(text.replaceRange(index, index + prior.length, newPromise).encodeToByteArray())
    }

    private fun assertRawRefused(input: StoredBytes, label: String) {
        val retained = carrier(descriptor(f.input("V-ORIGINAL")))
        val before = codec.encode(retained)
        assertCapture(codec.decode(before), f.expected("V-ORIGINAL"), label + " valid carrier")
        assertFails(label + " actual capture decoder") { codec.decode(input) }
        assertEquals(before, codec.encode(retained), label + " prior bytes")
        assertCapture(retained, f.expected("V-ORIGINAL"), label + " prior descriptor")
        // No durable store/business-effect observer is supplied by this pure codec slice.
    }

    private fun assertCapture(value: LocalSalesReturnPolicyCaptureV2, expected: JsonObject, label: String) {
        val promise = assertNotNull(value.input.publications.single().promise, label)
        val fields = promiseFields(codec.encode(value))
        assertEquals(expected, JsonObject(fields.filterKeys { it in f.extensionNames }), label + " exact optional members")
        for (key in expected.keys) assertFalse(fields.getValue(key).jsonPrimitive.isString, label + " integer " + key)
        assertEquals(f.text(f.base, "originalPromiseId"), promise.policyId.value, label)
        assertEquals(f.text(f.base, "originalRevision"), promise.policyRevision.value.toString(), label)
        assertEquals(f.text(f.base, "originalCategory"), promise.categoryCode, label)
        assertEquals(f.text(f.base, "organizationId"), value.scope.organizationId.value, label)
        assertEquals(f.text(f.base, "companyId"), value.scope.companyId.value, label)
        assertEquals(f.text(f.base, "branchId"), value.scope.branchId.value, label)
    }

    private fun promiseFields(value: StoredBytes): JsonObject = Json.parseToJsonElement(value.copyBytes().decodeToString())
        .jsonObject.getValue("input").jsonObject.getValue("publications").jsonArray.single()
        .jsonObject.getValue("promise").jsonObject
}
