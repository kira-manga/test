package com.ukkera.pos.app.gateway.independent.managerorderreviewv1

import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalManagerOrderReviewCodec
import com.ukkera.pos.sales.store.LocalManagerOrderReviewQueryV1
import com.ukkera.pos.sales.store.LocalOrderReadResultV2
import com.ukkera.pos.sales.store.LocalOrderStockProfileV2
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Typed serializer/decoder semantic slices only. There are no independent canonical bytes,
 * digest vectors, duplicate frames, exact-size vectors or normalized native source fixtures.
 * A decoder result is never reported as a protected handler/authorization/runtime result.
 */
class ManagerOrderReviewFrozenCodecSlicesTest {
    @Test
    fun mrr003NormalTypedSelectorNeedsOnlyFrozenOrderId() {
        val query = LocalManagerOrderReviewQueryV1(FrozenManagerReview.legacyOrder)
        val request = LocalManagerOrderReviewCodec.request(query)
        assertEquals(ReadKey("sales.order.manager-review", 1), request.query)
        val normalized = installedManagerReviewReader().normalize(request.payload)
        val fields = managerReviewWiringJson.parseToJsonElement(normalized.copyBytes().decodeToString()).jsonObject
        assertEquals(setOf("orderId"), fields.keys)
        assertEquals(FrozenManagerReview.legacyOrder.value, fields.getValue("orderId").jsonPrimitive.content)
        // The strict invalid/extra-field/duplicate frames require independent public vectors.
    }

    @Test
    fun mrr006DecoderRetainsFrozenNormalizedOwnerRevisionQuoteAndReferences() {
        val query = LocalManagerOrderReviewQueryV1(FrozenManagerReview.normalizedOrder)
        val decoded = LocalManagerOrderReviewCodec.decode(typedResultBytes(
            LocalOrderReadResultV2(listOf(controlledTypedReviewProjection(normalized = true)), null)), query)
        assertNull(decoded.nextAfter)
        val profiled = decoded.orders.single()
        assertEquals(LocalOrderStockProfileV2.NORMALIZED, profiled.stockProfile)
        val order = profiled.order
        assertEquals(FrozenManagerReview.normalizedOrder, order.orderId)
        assertEquals(FrozenManagerReview.owner, order.ownerId)
        assertEquals(Revision(23), order.revision)
        assertEquals(Money(Currency.EGP, 3750), order.beforeDiscount)
        assertEquals(Money(Currency.EGP, 250), order.discount)
        assertEquals(Money(Currency.EGP, 3500), order.payable)
        val selected = order.selection.lines.single().quantity
        assertEquals(Quantity.parse(selected.unitId, "3", selected.scale), selected)
        val priced = order.lines.single()
        assertEquals(Quantity.parse(priced.quantity.unitId, "3", priced.quantity.scale), priced.quantity)
        assertEquals(Money(Currency.EGP, 3500), priced.payable)
        assertEquals(3500L, order.components.sumOf { it.amount.minor })
        assertEquals(0L, order.components.filter { it.kind == "ITEM_TAX" || it.kind == "CHARGE_TAX" }
            .sumOf { it.amount.minor })
        assertTrue(order.components.all { it.amount.currency == Currency.EGP })
        assertEquals(FrozenManagerReview.collection, order.collectionId)
        assertEquals(FrozenManagerReview.sale, order.saleId)
        assertNull(order.handoffId)
        assertNull(order.handoffRecipientId)
        // No accepting normalized history, actual F_N source read or business-revision authorization.
    }

    @Test
    fun mrr007DecoderKeepsNormalizedLegacyNormalizedProfilesPerTypedPayload() {
        val sequence = listOf(true, false, true)
        val decoded = sequence.map { normalized ->
            val target = if (normalized) FrozenManagerReview.normalizedOrder else FrozenManagerReview.legacyOrder
            LocalManagerOrderReviewCodec.decode(typedResultBytes(LocalOrderReadResultV2(
                listOf(controlledTypedReviewProjection(normalized)), null)), LocalManagerOrderReviewQueryV1(target))
        }
        assertEquals(listOf(LocalOrderStockProfileV2.NORMALIZED, LocalOrderStockProfileV2.LEGACY_BOUNDED,
            LocalOrderStockProfileV2.NORMALIZED), decoded.map { it.orders.single().stockProfile })
        assertEquals(listOf(FrozenManagerReview.normalizedOrder, FrozenManagerReview.legacyOrder,
            FrozenManagerReview.normalizedOrder), decoded.map { it.orders.single().order.orderId })
        assertEquals(listOf(Revision(23), Revision(7), Revision(23)), decoded.map { it.orders.single().order.revision })
        assertTrue(decoded.all { it.nextAfter == null })
        // Decoder sequence only, not one mixed-source local runtime or current-grant sequence.
    }

    @Test
    fun mrr002DecoderAcceptsExplicitEmptySameTypedResultShape() {
        val result = LocalManagerOrderReviewCodec.decode(typedResultBytes(LocalOrderReadResultV2(emptyList(), null)),
            LocalManagerOrderReviewQueryV1(FrozenManagerReview.absentOrder))
        assertTrue(result.orders.isEmpty())
        assertNull(result.nextAfter)
        // Parsing empty bytes confers no revision-zero grant or native absence authority.
    }
}

private fun typedResultBytes(result: LocalOrderReadResultV2): StoredBytes = StoredBytes(
    managerReviewWiringJson.encodeToString(LocalOrderReadResultV2.serializer(), result).encodeToByteArray(),
)
