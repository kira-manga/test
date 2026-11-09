package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId

internal data class FoodRefundObligationRead(val reply: ReadReply.Data, val result: LocalFoodRefundReadResult.Obligation)

/** One genuine accepted liability response, not the former accepting employee's private collection read. */
internal suspend fun foodRefundObligationRead(access: GatewayAccess, id: DomainId): FoodRefundObligationRead {
    if (!access.installed.has(LocalFoodRefundReadCodec.QUERY)) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val reply = access.read(LocalFoodRefundReadCodec.request(LocalFoodRefundQuery.Obligation(id)))
    val result = foodRefundReadResult(reply) as? LocalFoodRefundReadResult.Obligation ?: unavailable()
    val value = result.value
    require(value.refundId == id && value.status in setOf("DUE", "PART_REFUNDED", "OUTCOME_UNKNOWN", "SETTLED") &&
        value.obligations.size <= 64 && value.attempts.size <= 64 && result.ownRecoveries.size <= 64 &&
        value.obligations.map { it.originalPaymentId }.distinct().size == value.obligations.size &&
        value.attempts.map { it.attemptId }.distinct().size == value.attempts.size &&
        result.ownRecoveries.map { it.target.attemptId }.distinct().size == result.ownRecoveries.size)
    val money = listOf(value.originalQuotedTotal, value.actualTenderLiability, value.refunded, value.due) +
        value.obligations.map { it.amount } + value.attempts.map { it.amount }
    require(money.all { it.currency == Currency.EGP && it.minor >= 0L } &&
        value.actualTenderLiability.minor <= value.originalQuotedTotal.minor &&
        value.refunded + value.due == value.actualTenderLiability &&
        value.obligations.fold(Money(value.due.currency, 0L)) { total, item -> total + item.amount } == value.actualTenderLiability &&
        value.attempts.filter { it.status == "ATTESTED_PAID" }.fold(Money(value.due.currency, 0L)) { total, item -> total + item.amount } == value.refunded)
    value.attempts.forEach { attempt ->
        require(attempt.status in setOf("AWAITING_ATTESTATION", "OUTCOME_UNKNOWN", "NOT_PAID", "ATTESTED_PAID") && attempt.amount.minor > 0L &&
            value.obligations.singleOrNull { it.originalPaymentId == attempt.originalPaymentId }?.tender == attempt.tender &&
            (attempt.ownAttempt || attempt.intentOperationId == null && attempt.appliedRefundObservationId == null))
    }
    result.ownRecoveries.forEach { recovery ->
        val target = recovery.target
        val attempt = value.attempts.singleOrNull { it.attemptId == target.attemptId } ?: unavailable()
        require(attempt.ownAttempt && target.refundId == id && target.orderId == result.orderId &&
            target.collectionId == value.collectionId && target.saleId == value.saleId &&
            target.originalPaymentId == attempt.originalPaymentId && target.intentOperationId == attempt.intentOperationId &&
            recovery.amount == attempt.amount && recovery.status == attempt.status)
    }
    return FoodRefundObligationRead(reply, result)
}

internal fun foodRefundUiContext(read: LocalFoodRefundReadResult.Obligation): LocalFoodRefundContext {
    val value = read.value
    val unresolved = value.attempts.any { foodRefundUnresolved(it.status) }
    val dues = value.obligations.mapNotNull { obligation ->
        val paid = value.attempts.filter { it.originalPaymentId == obligation.originalPaymentId && it.status == "ATTESTED_PAID" }
            .fold(Money(obligation.amount.currency, 0L)) { total, attempt -> total + attempt.amount }
        val due = obligation.amount - paid
        require(due.minor >= 0L)
        if (due.minor == 0L) null else LocalFoodRefundDue(value.refundId, obligation.originalPaymentId, due, obligation.tender.kind, unresolved)
    }
    require(dues.fold(Money(value.due.currency, 0L)) { total, item -> total + item.amount } == value.due)
    val recoveries = read.ownRecoveries.filter { foodRefundUnresolved(it.status) }.map { recovery ->
        val target = recovery.target
        LocalFoodRefundRecovery(value.refundId, target.attemptId, target.originalPaymentId, recovery.amount, recovery.status,
            target.intentOperationId, target.sessionId, target.recordingOperationId, target.resolutionOperationId)
    }
    return LocalFoodRefundContext(value.refundId, read.orderId, value.originalQuotedTotal, value.actualTenderLiability,
        value.refunded, value.due, dues, recoveries)
}

internal fun foodRefundUnresolved(status: String): Boolean = status in setOf("AWAITING_ATTESTATION", "OUTCOME_UNKNOWN")

internal fun foodRefundReadResult(reply: ReadReply.Data): LocalFoodRefundReadResult =
    LocalFoodRefundReadCodec.decode(reply.payload).also {
        if (it == LocalFoodRefundReadResult.CapacityRequired) throw GatewayFailure(LocalServiceProblem.FOOD_CAPACITY_REQUIRED)
    }

internal suspend fun gatewayFoodRefundOriginal(access: GatewayAccess, id: OperationId): LocalFoodRefundReadResult.Original? =
    when (val result = foodRefundReadResult(access.read(LocalFoodRefundReadCodec.request(LocalFoodRefundQuery.Original(id))))) {
        LocalFoodRefundReadResult.NotAvailable -> null
        is LocalFoodRefundReadResult.Original -> result.also {
            requireFoodRefundOriginal(it)
            require(it.summary.target.recordingOperationId == id && it.summary.originalActorId == access.view.actorId &&
                it.summary.recordingActorId == access.view.actorId)
        }
        else -> unavailable()
    }

internal suspend fun gatewayFoodRefundResolution(access: GatewayAccess, id: OperationId): LocalFoodRefundReadResult.Resolution? =
    when (val result = foodRefundReadResult(access.read(LocalFoodRefundReadCodec.request(LocalFoodRefundQuery.Resolution(id))))) {
        LocalFoodRefundReadResult.NotAvailable -> null
        is LocalFoodRefundReadResult.Resolution -> result.also {
            val summary = it.summary
            require(summary.target.resolutionOperationId == id && summary.originalActorId == access.view.actorId &&
                summary.recordingActorId == access.view.actorId)
            val original = gatewayFoodRefundOriginal(access, summary.target.recordingOperationId) ?: unavailable()
            require(original.outcome is LocalFoodRefundOutcome.Unknown)
            requireFoodRefundResolution(it, original.summary)
        }
        else -> unavailable()
    }

/** Fresh private immutable original selection, explicitly not the supervisor-review command. */
internal suspend fun gatewayFoodRefundSelection(access: GatewayAccess, id: OperationId): LocalFoodRefundOriginalSelection {
    val original = gatewayFoodRefundOriginal(access, id) ?: unavailable()
    val resolution = gatewayFoodRefundResolution(access, original.summary.target.resolutionOperationId)
    resolution?.let {
        require(original.outcome is LocalFoodRefundOutcome.Unknown)
        requireFoodRefundResolution(it, original.summary)
    }
    return LocalFoodRefundOriginalSelection(foodRefundDisplay(original.summary.target, original.summary.amount),
        foodRefundFactProgress(original.summary, outcome = original.outcome), resolution?.let { foodRefundResolutionFactProgress(it) })
}
