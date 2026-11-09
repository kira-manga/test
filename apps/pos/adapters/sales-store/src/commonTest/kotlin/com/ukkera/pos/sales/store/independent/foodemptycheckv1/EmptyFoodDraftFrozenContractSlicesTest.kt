package com.ukkera.pos.sales.store.independent.foodemptycheckv1

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodDraftCreateV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalFoodStockCodecV2
import com.ukkera.pos.sales.store.LocalFoodStockRequestV2
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * L0 input/codec slices of frozen EFD-02/03/05 only. Canonical native bytes are
 * captured from the bound codec; no wire layout, durable creation or replay pass is inferred.
 */
class EmptyFoodDraftFrozenContractSlicesTest {
    private val f = FrozenEmptyFoodDraftInputs

    @Test
    fun EFD_02_03_CreateDraftCodecPreservesEveryFrozenServiceLabel() {
        for (activity in f.activities) for (input in f.services) {
            val request = LocalFoodStockRequestV2.CreateDraft(
                LocalFoodDraftCreateV1(DomainId.random(), activity, input.bound()),
            )
            val originalBody = LocalFoodStockCodecV2.encode(request)
            val readback = assertIs<LocalFoodStockRequestV2.CreateDraft>(LocalFoodStockCodecV2.decode(originalBody))
            assertEquals(request, readback)
            assertEquals(input.channel, readback.request.service.channel)
            assertEquals(input.reference, readback.request.service.reference)
            assertEquals(input.seatNote.orEmpty(), readback.request.service.seatNote)
            assertEquals(originalBody, LocalFoodStockCodecV2.normalize(originalBody))
            assertEquals(originalBody, LocalFoodStockCodecV2.encode(readback))
        }
    }

    @Test
    fun EFD_03_BlankAndMissingReferenceRefuseAtTheBoundInput() {
        val service = f.services.first().bound()
        for (reference in listOf("", "   ")) {
            assertFailsWith<IllegalArgumentException> { LocalFoodServiceV1(service.channel, reference, service.seatNote) }
        }
        val originalBody = LocalFoodStockCodecV2.encode(
            LocalFoodStockRequestV2.CreateDraft(LocalFoodDraftCreateV1(DomainId.random(), f.activities.first(), service)),
        )
        val invalidBodies = listOf(
            rewriteService(originalBody) { JsonObject(it - "reference") },
            rewriteService(originalBody) { JsonObject(it + ("reference" to JsonNull)) },
            rewriteService(originalBody) { JsonObject(it + ("reference" to JsonPrimitive(""))) },
            rewriteService(originalBody) { JsonObject(it + ("reference" to JsonPrimitive("   "))) },
        )
        for (body in invalidBodies) {
            assertFailsWith<IllegalArgumentException> { LocalFoodStockCodecV2.normalize(body) }
        }
        assertEquals(service.reference, assertIs<LocalFoodStockRequestV2.CreateDraft>(
            LocalFoodStockCodecV2.decode(originalBody),
        ).request.service.reference)
    }

    @Test
    fun EFD_03_UnsupportedModeAndNonFoodActivityRefuseAtTheBoundInput() {
        val input = f.services.first().bound()
        for (activity in listOf(LocalSalesActivity.CLOTHING, LocalSalesActivity.SUPERMARKET)) {
            assertFailsWith<IllegalArgumentException> { LocalFoodDraftCreateV1(DomainId.random(), activity, input) }
        }
        val originalBody = LocalFoodStockCodecV2.encode(
            LocalFoodStockRequestV2.CreateDraft(LocalFoodDraftCreateV1(DomainId.random(), f.activities.first(), input)),
        )
        val invalidBodies = listOf(
            rewriteService(originalBody) { JsonObject(it + ("channel" to JsonPrimitive("UNSUPPORTED_MODE"))) },
            rewriteRequest(originalBody) { JsonObject(it + ("activity" to JsonPrimitive("CLOTHING"))) },
            rewriteRequest(originalBody) { JsonObject(it + ("activity" to JsonPrimitive("SUPERMARKET"))) },
        )
        for (body in invalidBodies) {
            assertFailsWith<IllegalArgumentException> { LocalFoodStockCodecV2.normalize(body) }
        }
        // This is the bounded draft route, not a company-capability or broader DELIVERY oracle.
    }

    @Test
    fun EFD_05_CanonicalCreateDraftBodyRetainsTheOriginalCommandIdentity() {
        val originalOperation = OperationId.random()
        val request = LocalFoodStockRequestV2.CreateDraft(
            LocalFoodDraftCreateV1(DomainId.random(), f.activities.first(), f.services.last().bound()),
        )
        val originalBody = LocalFoodStockCodecV2.encode(request)
        val original = LocalFoodStockCodecV2.command(originalOperation, request)
        val readback = LocalFoodStockCodecV2.decode(originalBody)
        val identical = LocalFoodStockCodecV2.command(originalOperation, readback)
        assertEquals(originalOperation, original.operationId)
        assertEquals(originalOperation, identical.operationId)
        assertEquals(LocalFoodStockCodecV2.COMMAND, original.command)
        assertEquals(original.command, identical.command)
        assertEquals(originalBody, original.payload)
        assertEquals(originalBody, identical.payload)
        assertEquals(originalBody, LocalFoodStockCodecV2.normalize(identical.payload))
        // No command is submitted: at-most-one commit, mismatch refusal and recovery are unbound.
    }

    private fun rewriteService(body: StoredBytes, change: (JsonObject) -> JsonObject): StoredBytes =
        rewriteRequest(body) { request ->
            JsonObject(request + ("service" to change(request.getValue("service").jsonObject)))
        }

    private fun rewriteRequest(body: StoredBytes, change: (JsonObject) -> JsonObject): StoredBytes {
        val root = Json.parseToJsonElement(body.copyBytes().decodeToString()).jsonObject
        val changed = JsonObject(root + ("request" to change(root.getValue("request").jsonObject)))
        return StoredBytes(changed.toString().encodeToByteArray())
    }
}
