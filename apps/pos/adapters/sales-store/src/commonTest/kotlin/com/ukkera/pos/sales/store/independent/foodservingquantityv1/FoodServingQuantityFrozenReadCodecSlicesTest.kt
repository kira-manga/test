package com.ukkera.pos.sales.store.independent.foodservingquantityv1

import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.FoodStationStatus
import com.ukkera.pos.sales.store.FoodDeliveryV1
import com.ukkera.pos.sales.store.LocalFoodOrderDetailV1
import com.ukkera.pos.sales.store.LocalFoodOrderSummaryV1
import com.ukkera.pos.sales.store.LocalFoodPortionViewV1
import com.ukkera.pos.sales.store.LocalFoodReadCodecV1
import com.ukkera.pos.sales.store.LocalFoodReadResultV1
import com.ukkera.pos.sales.store.LocalFoodStationTaskViewV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.localSalesDecode
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.sales.store.localSalesJson
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Synthetic public read and delivery DTO codec inputs from the frozen quantity
 * fixture. No handler read, saved line, durable task, printer acknowledgment,
 * original-producer proof, native storage or RPC is established by these slices.
 * FQ-V1-01 through FQ-V1-10 remain NOT_RUN as whole cases.
 */
class FoodServingQuantityFrozenReadCodecSlicesTest {
    private val f = FrozenFoodServingQuantityInputs

    @Test
    fun FQ_V1_01_02_ReadCodecKeepsQuantityTwoAndBothExactNotesOnTheLine() {
        for (payableMinor in listOf(22_000L, 28_000L)) {
            val input = orderRead(payableMinor)
            val bytes = localSalesEncode(input, LocalFoodReadResultV1.serializer())
            val decoded = LocalFoodReadCodecV1.result(bytes)
            val detail = assertNotNull(decoded.detail)
            assertEquals(f.orderId, detail.summary.orderId)
            assertEquals(f.ownerA, detail.summary.ownerId)
            assertEquals(f.existingOrderRevision, detail.summary.revision)
            assertEquals("", detail.summary.service.seatNote)
            assertEquals(Money(Currency.EGP, payableMinor), detail.originalQuotedTotal)
            assertEquals(listOf(f.lineId), detail.portions.map { it.commercialLineId }.distinct())
            assertEquals(f.two, detail.portions.fold(f.one.copy(scaled = 0)) { total, portion -> total + portion.quantity })
            assertTrue(detail.portions.all {
                it.lineId == f.lineId && it.itemNote == f.ITEM_NOTE && it.seatNote == f.SEAT_NOTE &&
                    it.modifierIds == listOf(f.cheeseOptionId) &&
                    it.preparation.status == FoodPreparationStatus.NOT_SENT && it.preparation.tasks.isEmpty()
            })
            assertEquals(bytes, localSalesEncode(decoded, LocalFoodReadResultV1.serializer()))
        }
    }

    @Test
    fun FQ_V1_01_StationAndDeliveryCodecsKeepTheExactLineNotes() {
        val task = LocalFoodStationTaskViewV1(
            f.orderId, f.existingOrderRevision, f.lineId, Revision(1), f.taskId, f.stationId,
            f.ORIGINAL_DESCRIPTION, f.one, f.ITEM_NOTE, f.service.reference,
            FoodStationStatus.QUEUED, FoodPreparationStatus.QUEUED, FoodStationDeliveryMode.DISPLAY,
            seatNote = f.SEAT_NOTE,
        )
        val readBytes = localSalesEncode(LocalFoodReadResultV1(tasks = listOf(task)), LocalFoodReadResultV1.serializer())
        val readTask = LocalFoodReadCodecV1.result(readBytes).tasks.single()
        assertEquals(f.ITEM_NOTE, readTask.itemNote)
        assertEquals(f.SEAT_NOTE, readTask.seatNote)
        assertEquals(f.one, readTask.quantity)

        val delivery = delivery(f.SEAT_NOTE)
        val bytes = localSalesEncode(delivery, FoodDeliveryV1.serializer())
        val decoded = localSalesDecode(bytes, FoodDeliveryV1.serializer())
        assertEquals(f.ITEM_NOTE, decoded.itemNote)
        assertEquals(f.SEAT_NOTE, decoded.seatNote)
        assertEquals(f.service.reference, decoded.serviceReference)
        assertEquals(f.one, decoded.quantity)
        assertEquals(bytes, localSalesEncode(decoded, FoodDeliveryV1.serializer()))
    }

    @Test
    fun FQ_V1_04_LegacyPortionReadAndDeliveryOmitAdditiveLineDefaults() {
        val legacyPortion = LocalFoodPortionViewV1(f.lineId, f.ORIGINAL_DESCRIPTION, f.one,
            listOf(f.cheeseOptionId), f.ITEM_NOTE,
            FoodPreparationState(f.lineId, Revision(0), FoodPreparationStatus.NOT_SENT))
        val bytes = localSalesEncode(legacyPortion, LocalFoodPortionViewV1.serializer())
        val decoded = localSalesDecode(bytes, LocalFoodPortionViewV1.serializer())
        val portionJson = localSalesJson.parseToJsonElement(bytes.copyBytes().decodeToString()).jsonObject
        assertFalse("lineId" in portionJson)
        assertFalse("seatNote" in portionJson)
        assertEquals(legacyPortion.portionId, legacyPortion.commercialLineId)
        assertEquals("", legacyPortion.seatNote)
        assertEquals(bytes, localSalesEncode(decoded, LocalFoodPortionViewV1.serializer()))

        val deliveryBytes = localSalesEncode(delivery(""), FoodDeliveryV1.serializer())
        val deliveryJson = localSalesJson.parseToJsonElement(
            deliveryBytes.copyBytes().decodeToString()
        ).jsonObject
        assertFalse("seatNote" in deliveryJson)
        val decodedDelivery = localSalesDecode(deliveryBytes, FoodDeliveryV1.serializer())
        assertEquals("", decodedDelivery.seatNote)
        assertEquals(f.ITEM_NOTE, decodedDelivery.itemNote)
        assertEquals(deliveryBytes, localSalesEncode(decodedDelivery, FoodDeliveryV1.serializer()))
    }

    private fun orderRead(payableMinor: Long): LocalFoodReadResultV1 {
        val portions = f.twoServings().map { selected ->
            LocalFoodPortionViewV1(selected.portionId, f.ORIGINAL_DESCRIPTION, selected.quantity,
                selected.modifierIds, selected.itemNote,
                FoodPreparationState(selected.portionId, Revision(0), FoodPreparationStatus.NOT_SENT),
                selected.lineId, selected.seatNote)
        }
        val summary = LocalFoodOrderSummaryV1(f.orderId, f.ownerA, f.existingOrderRevision,
            LocalSalesActivity.RESTAURANT, f.service, portions.size, null, null)
        return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(summary, portions,
            Money(Currency.EGP, payableMinor), false))
    }

    private fun delivery(seatNote: String): FoodDeliveryV1 = FoodDeliveryV1(
        f.orderId, f.lineId, f.taskId, f.stationId, Revision(1), FoodStationDeliveryMode.DISPLAY,
        "queue", f.ORIGINAL_DESCRIPTION, f.one, f.ITEM_NOTE, f.service.reference, seatNote)
}
