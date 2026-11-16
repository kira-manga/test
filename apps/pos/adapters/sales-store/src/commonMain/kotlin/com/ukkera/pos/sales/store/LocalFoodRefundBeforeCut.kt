package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.types.*

/** Actual native BEFORE economic cut, with the installed financial read/claim order.
 * Source/native/immutable producer qualification precedes every returned literal image. */
internal suspend fun foodRefundBeforeCut(view: FoodFinancialBeforeReadBudget, id: DomainId): FoodRefundCut {
    val refund = view.financial(LocalFoodFinancialProfileV1.REFUND, id)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val value = foodFinancialRefund(refund.envelope)
    val collection = view.financial(LocalFoodFinancialProfileV1.COLLECTION, value.originalCollection.id)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val index = view.financial(LocalFoodFinancialProfileV1.REFUND_INDEX, value.orderId)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val sale = value.saleId?.let { view.financial(LocalFoodFinancialProfileV1.SALE, it)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING) }
    val rights = value.saleId?.let { view.financial(LocalFoodFinancialProfileV1.ENTITLEMENT, it)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING) }
    requireFoodRefundBasis(value, foodFinancialCollection(collection.envelope),
        (index.envelope.body as FoodFinancialBodyV1.RefundIndex), sale?.let { foodFinancialSale(it.envelope) },
        rights?.let { foodFinancialEntitlement(it.envelope) })
    val batches = value.originalCollection.quote.batches.map { batch ->
        view.batch(batch.id)
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    }
    val cut = FoodRefundCut(refund, collection, index, sale, rights, batches)
    requireFoodRefundPrices(cut.inputs, view.scope, value)
    for (attempt in value.originalCollection.attempts) {
        check(view.claim(LocalSalesStoreProfile.unique("payment_attempt", attempt.id)) == value.originalCollection.id)
        attempt.payment?.let {
            check(view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) == value.originalCollection.id)
            if (attempt.tender == Tender.Cash)
                check(view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) == attempt.originalCashSessionId)
        }
    }
    for (attempt in value.attempts) {
        check(view.claim(LocalSalesStoreProfile.unique("refund_attempt", attempt.id)) == value.id)
        attempt.refund?.let {
            check(view.claim(LocalSalesStoreProfile.unique("refund_record", attempt.id)) == value.id)
            if (attempt.tender == Tender.Cash)
                check(view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) == attempt.originalCashSessionId)
        }
    }
    return cut
}
