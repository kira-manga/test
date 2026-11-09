package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalRetailRefundDue
import com.ukkera.pos.app.LocalRetailRefundRecovery
import com.ukkera.pos.sales.AttemptStatus
import com.ukkera.pos.sales.RefundStatus
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.Money

internal fun refundUnresolved(status: String): Boolean =
    status == AttemptStatus.AWAITING_ATTESTATION.name || status == AttemptStatus.OUTCOME_UNKNOWN.name

/** Actual bounded accepted-return projection invariants, not new acceptance or refund authority. */
internal fun requireAcceptedRefund(value: LocalAcceptedReturnViewV1) {
    require(value.quantity.scaled > 0L && value.requestedDisposition in ReturnDisposition.entries.map { it.name })
    require(value.obligations.size <= 64 && value.attempts.size <= 64 &&
        value.obligations.map { it.originalPaymentId }.distinct().size == value.obligations.size &&
        value.attempts.map { it.attemptId }.distinct().size == value.attempts.size)
    val currency = value.originalPayable.currency
    require(value.originalPayable.minor >= 0L && value.refunded.currency == currency && value.refunded.minor >= 0L &&
        value.due.currency == currency && value.due.minor >= 0L && value.due == value.originalPayable - value.refunded)
    fun tender(value: LocalTenderViewV1) {
        require(value.kind in setOf("CASH", "CARD", "EXTERNAL_WALLET", "OTHER"))
        require(if (value.kind == "CASH") value.tenderId == null && value.policyRevision == null &&
            !value.requiresTerminal && !value.requiresReference else value.tenderId != null && value.policyRevision != null)
    }
    val obligations = value.obligations.associateBy { it.originalPaymentId }
    value.obligations.forEach {
        tender(it.tender)
        require(it.amount.currency == currency && it.amount.minor > 0L)
    }
    require(value.obligations.fold(Money(currency, 0L)) { sum, it -> sum + it.amount } == value.originalPayable)
    value.attempts.forEach { attempt ->
        val obligation = obligations[attempt.originalPaymentId] ?: unavailable()
        require(attempt.status in AttemptStatus.entries.map { it.name } && attempt.tender == obligation.tender &&
            attempt.amount.currency == currency && attempt.amount.minor > 0L && attempt.amount.minor <= obligation.amount.minor)
        val unresolved = refundUnresolved(attempt.status)
        require(attempt.activeRecordingOperationId == null || attempt.ownAttempt && unresolved)
        val protocol = attempt.activeRecordingProtocolVersion
        require(protocol == null || attempt.activeRecordingOperationId != null && (protocol == 2 || protocol == 3))
        require(attempt.completedRecordingOperationId == null || attempt.ownAttempt && attempt.status == AttemptStatus.ATTESTED_PAID.name)
        if (attempt.ownAttempt) {
            require(attempt.intentOperationId != null && attempt.cashSessionId != null && attempt.cashSessionRevision != null &&
                (attempt.status != AttemptStatus.ATTESTED_PAID.name || attempt.completedRecordingOperationId != null))
        } else {
            // Another payer's exact private recovery identities are never projected to this actor.
            require(attempt.intentOperationId == null && attempt.activeRecordingOperationId == null &&
                attempt.completedRecordingOperationId == null && attempt.cashSessionId == null &&
                attempt.cashSessionRevision == null && attempt.activeRecordingProtocolVersion == null)
        }
    }
    val paid = value.attempts.filter { it.status == AttemptStatus.ATTESTED_PAID.name }
    require(paid.fold(Money(currency, 0L)) { sum, it -> sum + it.amount } == value.refunded)
    require(value.attempts.count { refundUnresolved(it.status) } <= 1)
    value.obligations.forEach { obligation ->
        val remaining = obligation.amount - paid.filter { it.originalPaymentId == obligation.originalPaymentId }
            .fold(Money(currency, 0L)) { sum, it -> sum + it.amount }
        require(remaining.minor >= 0L && value.attempts.filter {
            it.originalPaymentId == obligation.originalPaymentId && refundUnresolved(it.status)
        }.all { it.amount.minor <= remaining.minor })
    }
    val status = when {
        value.attempts.any { refundUnresolved(it.status) } -> RefundStatus.OUTCOME_UNKNOWN
        value.due.minor == 0L -> RefundStatus.SETTLED
        value.refunded.minor > 0L -> RefundStatus.PART_REFUNDED
        else -> RefundStatus.DUE
    }
    require(value.status == status.name)
}

internal fun retailRefundDues(value: LocalAcceptedReturnViewV1): List<LocalRetailRefundDue> {
    requireAcceptedRefund(value)
    val paid = value.attempts.filter { it.status == AttemptStatus.ATTESTED_PAID.name }
    val unresolved = value.attempts.any { refundUnresolved(it.status) }
    return value.obligations.mapNotNull { obligation ->
        val amount = obligation.amount - paid.filter { it.originalPaymentId == obligation.originalPaymentId }
            .fold(Money(obligation.amount.currency, 0L)) { sum, it -> sum + it.amount }
        if (amount.minor == 0L) null else LocalRetailRefundDue(value.saleId, value.claimId, value.returnId,
            obligation.originalPaymentId, amount, obligation.tender.kind, unresolved)
    }
}

internal fun retailRefundRecoveries(value: LocalAcceptedReturnViewV1): List<LocalRetailRefundRecovery> {
    requireAcceptedRefund(value)
    return value.attempts.filter { it.ownAttempt && refundUnresolved(it.status) }.map { attempt ->
        LocalRetailRefundRecovery(value.saleId, value.claimId, value.returnId, attempt.attemptId,
            attempt.originalPaymentId, attempt.amount, attempt.tender.kind, attempt.status,
            attempt.intentOperationId ?: unavailable(), attempt.cashSessionId ?: unavailable(),
            attempt.activeRecordingOperationId, attempt.activeRecordingProtocolVersion)
    }
}

/** Only a real exposed V3 selector and matching authenticated V3 slot can supply this recovery. */
internal fun requireRefundAttemptRecording(value: LocalAcceptedReturnViewV1, attempt: LocalRefundAttemptViewV1,
    read: LocalFinancialReadResultV3.AttemptRecording, owner: LocalClientIdentity) {
    requireAcceptedRefund(value)
    require(value.attempts.singleOrNull { it.attemptId == attempt.attemptId } == attempt && attempt.ownAttempt &&
        refundUnresolved(attempt.status) && attempt.activeRecordingProtocolVersion == 3 &&
        attempt.activeRecordingOperationId != null)
    val target = read.target
    require(target.kind == SalesRecordingKind.REFUND_ATTESTATION && target.documentId == value.returnId &&
        target.originalSaleId == value.saleId && target.originalClaimId == value.claimId &&
        target.originalPaymentId == attempt.originalPaymentId && target.attemptId == attempt.attemptId &&
        target.sessionId == attempt.cashSessionId && target.intentOperationId == attempt.intentOperationId &&
        target.recordingOperationId == attempt.activeRecordingOperationId && read.clientDeviceId == owner.deviceId)
    PayloadDigest(read.requirementDigest)
    fun summary(value: LocalJournalFactSummaryV3, phase: LocalJournalFactPhaseV3) {
        requireRetailSummary(value)
        require(value.target == target && value.phase == phase && value.originalActorId == owner.actorId &&
            value.amount == attempt.amount && value.tender == attempt.tender)
    }
    read.original?.let { summary(it, LocalJournalFactPhaseV3.ORIGINAL) }
    read.resolution?.let { resolution ->
        val original = read.original ?: unavailable()
        summary(resolution, LocalJournalFactPhaseV3.RESOLUTION)
        require(resolution.originalBodyDigest == original.bodyDigest && resolution.amount == original.amount &&
            resolution.tender == original.tender)
    }
}
