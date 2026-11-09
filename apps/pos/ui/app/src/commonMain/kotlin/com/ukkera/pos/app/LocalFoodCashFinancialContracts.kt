package com.ukkera.pos.app

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Response-selected coordinates only. The gateway rereads actual sources before fresh Stage. */
sealed interface LocalFoodCashFinancialReadSource {
    val query: LocalFoodUiQuery

    data class Collection(val collectionId: DomainId, val orderId: DomainId,
        val collectionDomainRevision: Revision, val orderStorageRevision: Revision) : LocalFoodCashFinancialReadSource {
        override val query: LocalFoodUiQuery get() = LocalFoodUiQuery.Collection(collectionId)
        init { require(orderStorageRevision.value >= 1) }
    }

    data class Original(val recordingOperationId: OperationId, val orderId: DomainId, val collectionId: DomainId,
        val originalActorId: DomainId, val recordingActorId: DomainId, val amount: Money,
        val bodyDigest: PayloadDigest, val applicationPending: Boolean) : LocalFoodCashFinancialReadSource {
        override val query: LocalFoodUiQuery get() = LocalFoodUiQuery.PaymentOriginal(recordingOperationId)
        init { require(originalActorId == recordingActorId && amount.minor > 0) }
    }

    data class ReadOnly(override val query: LocalFoodUiQuery) : LocalFoodCashFinancialReadSource {
        init { require(query is LocalFoodUiQuery.Receipt || query is LocalFoodUiQuery.PaymentAttempt ||
            query is LocalFoodUiQuery.OwnPendingPayments) }
    }
}

/** Only canonical command fields, decoded from this actor's immutable journal original. */
sealed interface LocalFoodCashFinancialOriginal {
    val action: LocalAction
    data class BeginCollection(val orderId: DomainId, val expectedOrderStorageRevision: Revision,
        val collectionId: DomainId, val sessionId: DomainId, val expectedSessionDomainRevision: Revision) : LocalFoodCashFinancialOriginal {
        override val action: LocalAction get() = LocalAction.FOOD_BEGIN_COLLECTION
    }
    data class FinalizePaid(val collectionId: DomainId, val expectedCollectionDomainRevision: Revision,
        val saleId: DomainId) : LocalFoodCashFinancialOriginal {
        override val action: LocalAction get() = LocalAction.FOOD_FINALIZE_PAID
    }
    data class ApplyOriginal(val recordingOperationId: OperationId) : LocalFoodCashFinancialOriginal {
        override val action: LocalAction get() = LocalAction.FOOD_PAYMENT_APPLY
    }
}

enum class LocalFoodCashFinancialApplication { CASH_PAID, NOT_PAID, UNKNOWN, PENDING_RECONCILIATION, ALREADY_APPLIED }

/** Typed original-result relationship, never a physical capability or a grant inferred from a label. */
data class LocalFoodCashFinancialOperationDetails(val operationId: OperationId, val originalActorId: DomainId,
    val original: LocalFoodCashFinancialOriginal, val committed: Boolean, val code: String,
    val collectionId: DomainId? = null, val saleId: DomainId? = null,
    val application: LocalFoodCashFinancialApplication? = null, val applicationOperationId: OperationId? = null,
    val pendingReason: String? = null, val amount: Money? = null, val followUp: LocalFoodUiQuery? = null) {
    init {
        require(code.isNotBlank() && code.length <= 160 && (pendingReason == null || pendingReason.length in 1..160))
        require(followUp == null || followUp.isFoodCashFinancialUiQuery())
        require(original !is LocalFoodCashFinancialOriginal.ApplyOriginal || original.recordingOperationId != operationId)
    }
}
