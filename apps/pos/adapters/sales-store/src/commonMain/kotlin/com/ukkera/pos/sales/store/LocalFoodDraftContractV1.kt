package com.ukkera.pos.sales.store

import com.ukkera.pos.types.DomainId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** An explicit unpaid draft has no selected recipe, commercial quote or preparation work. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class LocalFoodDraftCreateV1(
    public val orderId: DomainId,
    public val activity: LocalSalesActivity,
    public val service: LocalFoodServiceV1,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val deliveryDetails: LocalFoodDeliveryDetailsV1? = null,
) {
    init {
        require(activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE))
        require((service.channel == LocalSalesChannel.DELIVERY) == (deliveryDetails != null))
    }
}

/** This schema alone admits an empty ORDER; legacy and quoted-order profiles remain strict. */
public object LocalFoodDraftProfileV1 {
    public const val ORDER_SCHEMA: String = "sales.food.order/empty-draft-v1"
    public const val CREATED: String = "food.order.draft_created"
}
