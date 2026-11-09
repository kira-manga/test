package com.ukkera.pos.sales.store.independent.foodemptycheckv1

import com.ukkera.pos.sales.store.FoodBodyV1
import com.ukkera.pos.sales.store.FoodEnvelopeV1
import com.ukkera.pos.sales.store.LocalFoodProfileV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Semantic inputs from food-empty-check-v1/fixtures.json; no durable Master fixture. */
internal object FrozenEmptyFoodDraftInputs {
    val ownerA = DomainId("b0000000-0000-4000-8000-000000000001")
    val ordinaryB = DomainId("b0000000-0000-4000-8000-000000000002")
    val scope = BranchScope(
        DomainId("a0000000-0000-4000-8000-000000000001"),
        DomainId("a0000000-0000-4000-8000-000000000002"),
        DomainId("a0000000-0000-4000-8000-000000000004"),
    )
    val activities = listOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE)
    val services = listOf(
        ServiceInput(LocalSalesChannel.DINE_IN, "T-02", "Seat 02 / المقعد 02"),
        ServiceInput(LocalSalesChannel.COUNTER, "Counter 01 / الكاونتر 01", null),
        ServiceInput(LocalSalesChannel.TAKEAWAY, "  Pickup 0007 / استلام 0007  ", "For pickup / للاستلام"),
    )

    data class ServiceInput(val channel: LocalSalesChannel, val reference: String, val seatNote: String?) {
        // This codec represents a semantically absent optional note with the empty string.
        fun bound(): LocalFoodServiceV1 = LocalFoodServiceV1(channel, reference, seatNote.orEmpty())
    }

    fun emptyEnvelope(input: ServiceInput, activity: LocalSalesActivity = LocalSalesActivity.RESTAURANT): FoodEnvelopeV1 =
        FoodEnvelopeV1(
            scope, LocalFoodProfileV1.ORDER, DomainId.random(),
            // An arbitrary positive structural input, not an expected creation revision.
            Revision(23), OperationId.random(),
            FoodBodyV1.Order(ownerA, ownerA, activity, input.bound(), emptyList(), emptyList(), Money(Currency.EGP, 0)),
        )
}
