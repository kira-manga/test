package com.ukkera.pos.sales.store.independent.foodcurrentpayablev2

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.FoodPreparationState
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenObservationV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenResultV2
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

/** Public DTO bindings for bounded wire checks; these are not protected production replies. */
internal object FoodCurrentPayableFrozenCodecInputs {
    fun id(number: Int): DomainId = DomainId("c2810000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    val order = id(1)
    val owner = id(2)
    val p1 = id(11)
    val unit = id(20)
    val revision = Revision(3)

    fun bytes(text: String): StoredBytes = StoredBytes(text.encodeToByteArray())
    fun text(bytes: StoredBytes): String = bytes.copyBytes().decodeToString()

    fun f2(deliveryPresent: Boolean = false): LocalFoodReadResultV1 {
        val summary = LocalFoodOrderSummaryV1(order, owner, revision, LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(if (deliveryPresent) LocalSalesChannel.DELIVERY else LocalSalesChannel.DINE_IN,
                "Synthetic F2", ""), 4, null, null)
        val portion = LocalFoodPortionViewV1(p1, "Frozen serving P1", Quantity(unit, 1, 0), emptyList(), "",
            FoodPreparationState(p1, Revision(0), FoodPreparationStatus.NOT_SENT))
        return LocalFoodReadResultV1(detail = LocalFoodOrderDetailV1(summary, listOf(portion),
            Money(Currency.EGP, 50_000), false), nextAfter = p1)
    }

    fun v2(kitchenPresent: Boolean, deliveryPresent: Boolean, current: Money?): LocalFoodCheckKitchenResultV2 =
        LocalFoodCheckKitchenResultV2(f2(deliveryPresent),
            if (kitchenPresent) LocalFoodCheckKitchenObservationV1(order, revision, 3,
                LocalFoodCheckKitchenStatusV1.UNAVAILABLE) else null,
            if (deliveryPresent) LocalFoodDeliveryObservationV1(order, revision,
                LocalFoodDeliveryDetailsV1("Synthetic recipient", "synthetic@example.invalid", "Synthetic address")) else null,
            current)
}
