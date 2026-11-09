package com.ukkera.pos.sales.store.independent.returncommercialintentv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalReturnCaseActionV1
import com.ukkera.pos.sales.store.LocalReturnCaseAssessmentV1
import com.ukkera.pos.sales.store.LocalReturnCaseCommandCodecV1
import com.ukkera.pos.sales.store.LocalReturnCaseDecisionViewV1
import com.ukkera.pos.sales.store.LocalReturnCaseEventViewV1
import com.ukkera.pos.sales.store.LocalReturnCaseProfileV1
import com.ukkera.pos.sales.store.LocalReturnCaseReadCodecV1
import com.ukkera.pos.sales.store.LocalReturnCaseReadResultV1
import com.ukkera.pos.sales.store.LocalReturnCaseRequestV1
import com.ukkera.pos.sales.store.LocalReturnOrdinaryAssessmentV1
import com.ukkera.pos.sales.store.localSalesDecode
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Four bounded serialization slices of frozen return-commercial-intent-v1 U01/U02.
 * Required legacy coordinates and absent-observation container fields are inert,
 * unasserted shape fillers. Generated current bytes are comparison inputs, not
 * independently frozen full legacy goldens or genuine historical source evidence.
 * No trusted source record, non-PENDING acceptance or protected grant is created.
 *
 * U01 binds typed omitted/false equality and canonical-output roundtrips only.
 * Raw explicit-default false normalization conflicts with the established strict
 * canonical-admission contract and remains contradictory/unbound under retained v1;
 * these tests neither accept it nor replace the frozen expectation with rejection.
 * U02 similarly binds typed unset/null output omission, not raw-null admission.
 * Unsupported non-PENDING fixtures/constructor outcomes, complete case results,
 * genuine history/staging/recovery/authorization, all I01–I06 and business effects
 * remain unbound. No wire golden, field order, digest, UI text or downgrade rule
 * is inferred from these limited comparisons.
 */
class ReturnCommercialIntentFrozenSerializationSlicesTest {
    private val frozen: FrozenReturnCommercialIntentInputs = FrozenReturnCommercialIntentInputs

    @Test
    fun rciU01_typedOmittedAndFalse_sameCanonicalOutputOnly() {
        val absentVariant = frozen.request("RQ-OMITTED")
        val falseVariant = frozen.request("RQ-FALSE")
        val absent = legacyRequest()
        val explicitFalse = absent.copy(recordOriginalCommercialTiming =
            falseVariant.getValue("literal_value").jsonPrimitive.boolean)
        val absentBytes = LocalReturnCaseCommandCodecV1.encode(absent)
        val falseBytes = LocalReturnCaseCommandCodecV1.encode(explicitFalse)
        assertContentEquals(absentBytes.copyBytes(), falseBytes.copyBytes(), "RCI-U01 typed omitted/false equality")
        assertFalse(absentVariant.text("assigned_field") in jsonObject(absentBytes), "RCI-U01 default absent")
        assertFalse(falseVariant.text("assigned_field") in jsonObject(falseBytes), "RCI-U01 typed false omitted")

        val decodedAbsent = assertCanonicalRequest(absentBytes)
        val decodedFalse = assertCanonicalRequest(falseBytes)
        assertEquals(absentVariant.getValue("semantic_value").jsonPrimitive.boolean,
            decodedAbsent.recordOriginalCommercialTiming)
        assertEquals(falseVariant.getValue("literal_value").jsonPrimitive.boolean,
            decodedFalse.recordOriginalCommercialTiming)
        assertEquals(decodedAbsent, decodedFalse)
        // No raw JSON with an explicit false field is normalized or called canonical here.
    }

    @Test
    fun rciU01_trueIntent_distinctLiteralAndCanonicalRoundtripOnly() {
        val variant = frozen.request("RQ-TRUE")
        val field = variant.text("assigned_field")
        val absent = legacyRequest()
        val requested = absent.copy(recordOriginalCommercialTiming =
            variant.getValue("literal_value").jsonPrimitive.boolean)
        val absentBytes = LocalReturnCaseCommandCodecV1.encode(absent)
        val trueBytes = LocalReturnCaseCommandCodecV1.encode(requested)
        assertNotEquals(absentBytes, trueBytes, "RCI-U01 true is distinct original intent")
        val trueJson = jsonObject(trueBytes)
        val literal = trueJson.getValue(field).jsonPrimitive
        assertFalse(literal.isString, "RCI-U01 actual boolean token, not a string")
        assertTrue(literal.boolean, "RCI-U01 literal true retained")
        assertEquals(jsonObject(absentBytes), JsonObject(trueJson - field),
            "RCI-U01 legacy semantic members held fixed")
        assertTrue(assertCanonicalRequest(trueBytes).recordOriginalCommercialTiming)

        val command = LocalReturnCaseCommandCodecV1.command(SHAPE_ONLY_OPERATION_ID, requested)
        assertContentEquals(trueBytes.copyBytes(), command.payload.copyBytes(), "RCI-U01 dispatch-value intent")
        // This generated request value does not establish staging, a committed
        // original operation, duplicate conflict, older-peer behavior or recovery.
    }

    @Test
    fun rciU02_typedUnsetAndNullAssessment_defaultOutputOmittedOnly() {
        val absent = LocalReturnCaseAssessmentV1(LocalReturnOrdinaryAssessmentV1.PENDING,
            SHAPE_ONLY_REASON, SHAPE_ONLY_ROUTE)
        val explicitNull = absent.copy(originalCommercialTiming = null)
        val absentBytes = localSalesEncode(absent, LocalReturnCaseAssessmentV1.serializer())
        val nullBytes = localSalesEncode(explicitNull, LocalReturnCaseAssessmentV1.serializer())
        assertDefaultOmission(absentBytes, nullBytes)

        for (bytes in listOf(absentBytes, nullBytes)) {
            val decoded = localSalesDecode(bytes, LocalReturnCaseAssessmentV1.serializer())
            assertNull(decoded.originalCommercialTiming)
            assertContentEquals(bytes.copyBytes(), localSalesEncode(decoded,
                LocalReturnCaseAssessmentV1.serializer()).copyBytes(), "RCI-U02 assessment canonical roundtrip")
        }
        // The PENDING value is an inert serialization container, not an observed qualification.
    }

    @Test
    fun rciU02_typedUnsetAndNullProtectedProjection_defaultOutputOmittedOnly() {
        val absent = LocalReturnCaseDecisionViewV1(SHAPE_ONLY_OPERATION_ID,
            LocalReturnOrdinaryAssessmentV1.PENDING, SHAPE_ONLY_REASON, SHAPE_ONLY_ROUTE,
            policyId = null, policyRevision = null, salePromiseCaptureId = null,
            applicableRightsIds = emptyList(), customerReceiptIds = emptyList(),
            customerReceivedAt = null, receiptTimeZoneId = null, remedyDueAt = null)
        val explicitNull = absent.copy(originalCommercialTiming = null)
        val absentResult = projectionContainer(absent)
        val nullResult = projectionContainer(explicitNull)
        val absentBytes = encodeProjection(absentResult)
        val nullBytes = encodeProjection(nullResult)
        assertDefaultOmission(absentBytes, nullBytes)

        for (bytes in listOf(absentBytes, nullBytes)) {
            val decoded = assertIs<LocalReturnCaseReadResultV1.Events>(LocalReturnCaseReadCodecV1.decode(bytes))
            val decision = assertNotNull(decoded.events.single().decision)
            assertNull(decision.originalCommercialTiming)
            assertContentEquals(bytes.copyBytes(), encodeProjection(decoded).copyBytes(),
                "RCI-U02 public protected-projection codec roundtrip")
        }
        // This typed public read-result envelope is not an authorized read, retained
        // historical event, authenticated producer or actual durable PENDING history.
    }

    private fun legacyRequest(): LocalReturnCaseRequestV1.Qualify =
        LocalReturnCaseRequestV1.Qualify(SHAPE_ONLY_CASE_ID, Revision(0))

    private fun assertCanonicalRequest(bytes: StoredBytes): LocalReturnCaseRequestV1.Qualify {
        val decoded = assertIs<LocalReturnCaseRequestV1.Qualify>(LocalReturnCaseCommandCodecV1.decode(bytes))
        assertContentEquals(bytes.copyBytes(), LocalReturnCaseCommandCodecV1.encode(decoded).copyBytes())
        assertContentEquals(bytes.copyBytes(), LocalReturnCaseCommandCodecV1.normalize(bytes).copyBytes())
        return decoded
    }

    private fun assertDefaultOmission(absent: StoredBytes, explicitNull: StoredBytes) {
        assertContentEquals(absent.copyBytes(), explicitNull.copyBytes(), "RCI-U02 typed unset/null equality")
        assertFalse(hasField(Json.parseToJsonElement(absent.copyBytes().decodeToString()),
            frozen.projection("RS-ABSENT").text("assigned_field")), "RCI-U02 unset field omitted")
        assertFalse(hasField(Json.parseToJsonElement(explicitNull.copyBytes().decodeToString()),
            frozen.projection("RS-NULL").text("assigned_field")), "RCI-U02 typed null field omitted")
    }

    /** Look for only the assigned field; no nested wire layout or member order is an oracle. */
    private fun hasField(value: JsonElement, name: String): Boolean = when (value) {
        is JsonObject -> name in value || value.values.any { hasField(it, name) }
        is JsonArray -> value.any { hasField(it, name) }
        else -> false
    }

    private fun projectionContainer(decision: LocalReturnCaseDecisionViewV1): LocalReturnCaseReadResultV1.Events =
        LocalReturnCaseReadResultV1.Events(SHAPE_ONLY_CASE_ID, Revision(0), listOf(
            LocalReturnCaseEventViewV1(0, SHAPE_ONLY_OPERATION_ID, UtcInstant(Instant.fromEpochSeconds(0)),
                LocalReturnCaseActionV1.ASSESSMENT_PENDING, binding = null, decision = decision)), nextAfterOrdinal = null)

    private fun encodeProjection(result: LocalReturnCaseReadResultV1): StoredBytes =
        localSalesEncode(result, LocalReturnCaseReadResultV1.serializer(), LocalReturnCaseProfileV1.MAX_READ_BYTES)

    private fun jsonObject(bytes: StoredBytes): JsonObject =
        Json.parseToJsonElement(bytes.copyBytes().decodeToString()).jsonObject

    private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

    private companion object {
        // Required transport/container shapes only; no original source, operation or business code is asserted.
        val SHAPE_ONLY_CASE_ID: DomainId = DomainId("00000000-0000-4000-8000-000000000001")
        val SHAPE_ONLY_OPERATION_ID: OperationId = OperationId("00000000-0000-4000-8000-000000000002")
        const val SHAPE_ONLY_REASON: String = "SHAPE_ONLY_REASON"
        const val SHAPE_ONLY_ROUTE: String = "SHAPE_ONLY_ROUTE"
    }
}
