package com.ukkera.pos.sales.store.independent.foodcurrentpayablev3

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.FoodPreparation
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.FoodStationStatus
import com.ukkera.pos.sales.FoodStationTask
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenObservationV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV3
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenStatusV1
import com.ukkera.pos.sales.store.LocalFoodDeliveryDetailsV1
import com.ukkera.pos.sales.store.LocalFoodDeliveryObservationV1
import com.ukkera.pos.sales.store.LocalFoodOrderDetailV1
import com.ukkera.pos.sales.store.LocalFoodOrderSummaryV1
import com.ukkera.pos.sales.store.LocalFoodPortionViewV1
import com.ukkera.pos.sales.store.LocalFoodReadResultV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Controlled public DTO bindings of frozen values; no successful protected production reply is asserted. */
internal object FoodCurrentPayableV3CodecInputs {
    val order = DomainId("20000000-0000-4000-8000-000000000001")
    val emptyOrder = DomainId("20000000-0000-4000-8000-000000000003")
    val owner = DomainId("10000000-0000-4000-8000-000000000004")
    val revision = Revision(41)
    fun portion(number: Int) = DomainId("40000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun source(number: Int) = DomainId("74000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    val at = UtcInstant.parseCanonical("2026-11-06T09:00:00Z")
    fun egp(minor: Long) = Money(Currency.EGP, minor)
    fun bytes(text: String) = StoredBytes(text.encodeToByteArray())
    fun text(bytes: StoredBytes) = bytes.copyBytes().decodeToString()

    fun state(number: Int, prepared: Boolean = true): FoodPreparationState {
        val id = portion(number)
        val unsent = FoodPreparationState(id, Revision(0), FoodPreparationStatus.NOT_SENT)
        if (number == 6) return FoodPreparation.requestCancellation(unsent, unsent.revision, source(1), at)
        if (!prepared || number == 1) return unsent
        var current = FoodPreparation.sent(unsent, unsent.revision, source(100 + number), source(200 + number),
            listOf(FoodStationTask(source(300 + number), source(400), FoodStationDeliveryMode.DISPLAY, FoodStationStatus.QUEUED)), at)
        if (number == 2) return current
        current = FoodPreparation.start(current, current.revision, current.tasks.single().stationId, at)
        if (number == 3) return current
        current = FoodPreparation.ready(current, current.revision, current.tasks.single().stationId, at)
        return if (number == 5) FoodPreparation.serve(current, current.revision, at) else current
    }

    fun food(visible: List<Int> = listOf(1, 2), prepared: Boolean = true,
        delivery: Boolean = false, next: DomainId? = portion(2)): LocalFoodReadResultV1 {
        val summary = LocalFoodOrderSummaryV1(order, owner, revision, LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(if (delivery) LocalSalesChannel.DELIVERY else LocalSalesChannel.DINE_IN,
                "Controlled frozen check", ""), 6, null, null)
        return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(summary, visible.map { number ->
            val id = portion(number)
            LocalFoodPortionViewV1(id, "Frozen serving ${('A'.code + number - 1).toChar()}", Quantity(source(2), 1, 0),
                emptyList(), "", state(number, prepared))
        }, egp(30_803), false), nextAfter = next)
    }

    fun empty(): LocalFoodReadResultV1 = LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(
        LocalFoodOrderSummaryV1(emptyOrder, owner, Revision(1), LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Controlled empty check", ""), 0, null, null), emptyList(), egp(0), false))

    fun v3(kitchen: Boolean, delivery: Boolean, amount: Money?): LocalFoodCheckKitchenResultV3 =
        LocalFoodCheckKitchenResultV3(food(delivery = delivery),
            if (kitchen) LocalFoodCheckKitchenObservationV1(order, revision, 1, LocalFoodCheckKitchenStatusV1.UNAVAILABLE) else null,
            if (delivery) LocalFoodDeliveryObservationV1(order, revision,
                LocalFoodDeliveryDetailsV1("Synthetic recipient", "synthetic@example.invalid", "Synthetic address")) else null,
            amount)
}
