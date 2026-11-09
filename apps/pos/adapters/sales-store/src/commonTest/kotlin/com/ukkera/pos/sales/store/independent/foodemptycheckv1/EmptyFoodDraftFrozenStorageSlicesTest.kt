package com.ukkera.pos.sales.store.independent.foodemptycheckv1

import com.ukkera.pos.sales.store.FoodBodyV1
import com.ukkera.pos.sales.store.FoodEnvelopeV1
import com.ukkera.pos.sales.store.FoodHandoffV1
import com.ukkera.pos.sales.store.LocalFoodDraftProfileV1
import com.ukkera.pos.sales.store.LocalFoodProfileV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.foodEnvelope
import com.ukkera.pos.sales.store.foodEnvelopeBytes
import com.ukkera.pos.sales.store.foodStockSchema
import com.ukkera.pos.sales.store.foodStoredEnvelope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L0 structural storage slices of frozen EFD-02/04 only. Literal envelopes are
 * synthetic decoder inputs; they do not establish current authority, durable creation,
 * stock/cash cuts, complete original-producer proof, native persistence or RPC.
 */
class EmptyFoodDraftFrozenStorageSlicesTest {
    private val f = FrozenEmptyFoodDraftInputs

    @Test
    fun EFD_02_ExplicitEmptySchemaPreservesExactLabelsAndIntrinsicZeroFacts() {
        for (activity in f.activities) for (input in f.services) {
            val original = f.emptyEnvelope(input, activity)
            val originalBody = foodEnvelopeBytes(original)
            val readback = foodStoredEnvelope(LocalFoodDraftProfileV1.ORDER_SCHEMA, originalBody)
            val order = assertIs<FoodBodyV1.Order>(readback.body)
            assertEquals(original, readback)
            assertEquals(originalBody, foodEnvelopeBytes(readback))
            assertEquals(f.ownerA, order.creatorId)
            assertEquals(f.ownerA, order.ownerId)
            assertEquals(activity, order.activity)
            assertEquals(input.channel, order.service.channel)
            assertEquals(input.reference, order.service.reference)
            assertEquals(input.seatNote.orEmpty(), order.service.seatNote)
            assertTrue(order.portionIds.isEmpty())
            assertTrue(order.batchIds.isEmpty())
            assertEquals(Money(Currency.EGP, 0), order.originalQuotedTotal)
            assertNull(order.handoff)
            assertNull(order.financialCollectionId)
            assertNull(order.closedByOperationId)
        }
    }

    @Test
    fun EFD_02_EmptySchemaRejectsPortionsQuotesAndNonzeroPayable() {
        val original = f.emptyEnvelope(f.services.first())
        val order = assertIs<FoodBodyV1.Order>(original.body)
        val portionId = DomainId.random()
        val batchId = DomainId.random()
        val invalidOrders = listOf(
            order.copy(portionIds = listOf(portionId)),
            order.copy(batchIds = listOf(batchId)),
            order.copy(portionIds = listOf(portionId), batchIds = listOf(batchId)),
            order.copy(originalQuotedTotal = Money(Currency.EGP, 1)),
            order.copy(originalQuotedTotal = Money(Currency.EGP, -1)),
        )
        for (invalid in invalidOrders) assertInvalidDraft(original.copy(body = invalid))
    }

    @Test
    fun EFD_02_EmptySchemaRejectsHandoffCollectionAndClosure() {
        val original = f.emptyEnvelope(f.services.first())
        val order = assertIs<FoodBodyV1.Order>(original.body)
        val invalidOrders = listOf(
            order.copy(handoff = FoodHandoffV1(DomainId.random(), f.ownerA, f.ordinaryB,
                UtcInstant.parseCanonical("2026-10-30T10:00:00Z"))),
            order.copy(financialCollectionId = DomainId.random()),
            order.copy(closedByOperationId = OperationId.random()),
            order.copy(financialCollectionId = DomainId.random(), closedByOperationId = OperationId.random()),
        )
        for (invalid in invalidOrders) assertInvalidDraft(original.copy(body = invalid))
    }

    @Test
    fun EFD_02_04_EmptySchemaRejectsReassignedOwnershipAndNonFoodState() {
        val original = f.emptyEnvelope(f.services.first())
        val order = assertIs<FoodBodyV1.Order>(original.body)
        val invalidOrders = listOf(
            order.copy(ownerId = f.ordinaryB),
            order.copy(creatorId = f.ordinaryB),
            order.copy(activity = LocalSalesActivity.CLOTHING),
            order.copy(activity = LocalSalesActivity.SUPERMARKET),
        )
        for (invalid in invalidOrders) assertInvalidDraft(original.copy(body = invalid))
        // Equality guards do not prove that either identity came from current trusted Master state.
    }

    @Test
    fun EFD_02_EmptyOrderRequiresExplicitSchemaAndRejectsLegacyQuotedProfiles() {
        val original = f.emptyEnvelope(f.services.first())
        val originalBody = foodEnvelopeBytes(original)
        assertEquals(original, foodStoredEnvelope(LocalFoodDraftProfileV1.ORDER_SCHEMA, originalBody))
        assertFailsWith<IllegalStateException> { foodEnvelope(originalBody) }
        for (schema in listOf(LocalFoodProfileV1.schema(LocalFoodProfileV1.ORDER), foodStockSchema(LocalFoodProfileV1.ORDER))) {
            assertFailsWith<IllegalStateException> { foodStoredEnvelope(schema, originalBody) }
        }
    }

    @Test
    fun EFD_02_EmptySchemaRejectsTheWrongAggregateKindOrBody() {
        val original = f.emptyEnvelope(f.services.first())
        assertInvalidDraft(original.copy(kind = LocalFoodProfileV1.PORTION))
        assertInvalidDraft(original.copy(kind = LocalFoodProfileV1.OWNER_INDEX))
        assertInvalidDraft(original.copy(body = FoodBodyV1.OwnerIndex(emptyList())))
    }

    private fun assertInvalidDraft(envelope: FoodEnvelopeV1) {
        assertFailsWith<IllegalStateException> {
            foodStoredEnvelope(LocalFoodDraftProfileV1.ORDER_SCHEMA, foodEnvelopeBytes(envelope))
        }
    }
}
