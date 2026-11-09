@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.sales

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface StoredRefundOutcome {
    @Serializable @SerialName("UNKNOWN") data class Unknown(val evidence: StoredCashierEvidence) : StoredRefundOutcome
    @Serializable @SerialName("NOT_PAID") data class NotPaid(val evidence: StoredCashierEvidence, val reason: String) : StoredRefundOutcome
    @Serializable @SerialName("CASH_PAID") data class CashPaid(val evidence: StoredCashierEvidence) : StoredRefundOutcome
    @Serializable @SerialName("EXTERNAL_ATTESTED") data class ExternalAttested(val evidence: StoredExternalEvidence) : StoredRefundOutcome

    fun restore(): RefundOutcome = when (this) {
        is Unknown -> RefundOutcome.Unknown(evidence.restore())
        is NotPaid -> RefundOutcome.NotPaid(evidence.restore(), reason)
        is CashPaid -> RefundOutcome.CashPaid(evidence.restore())
        is ExternalAttested -> RefundOutcome.ExternalAttested(evidence.restore())
    }

    companion object {
        fun from(value: RefundOutcome): StoredRefundOutcome = when (value) {
            is RefundOutcome.Unknown -> Unknown(StoredCashierEvidence.from(value.evidence))
            is RefundOutcome.NotPaid -> NotPaid(StoredCashierEvidence.from(value.evidence), value.reason)
            is RefundOutcome.CashPaid -> CashPaid(StoredCashierEvidence.from(value.evidence))
            is RefundOutcome.ExternalAttested -> ExternalAttested(StoredExternalEvidence.from(value.evidence))
        }
    }
}

@Serializable
internal data class StoredRefundRecord(
    val attemptId: DomainId, val returnId: DomainId, val originalPaymentId: DomainId,
    val originalOperationId: OperationId, val recordingOperationId: OperationId,
    val payingActorId: DomainId, val payingCashSessionId: DomainId, val tender: StoredTender,
    val amount: Money, val attestation: StoredCashierEvidence, val externalEvidence: StoredExternalEvidence?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resolutionObservation: StoredRetailResolutionLinkV1? = null,
) {
    fun restore(): RefundRecord = RefundRecord(attemptId, returnId, originalPaymentId, originalOperationId, recordingOperationId,
        payingActorId, payingCashSessionId, tender.restore(), amount, attestation.restore(), externalEvidence?.restore(), resolutionObservation?.restore())
        .also(::validateStoredRefund)

    companion object {
        fun from(value: RefundRecord): StoredRefundRecord = StoredRefundRecord(value.attemptId, value.returnId,
            value.originalPaymentId, value.originalOperationId, value.recordingOperationId, value.payingActorId,
            value.payingCashSessionId, StoredTender.from(value.tender), value.amount, StoredCashierEvidence.from(value.attestation),
            value.externalEvidence?.let(StoredExternalEvidence::from), value.resolutionObservation?.let(StoredRetailResolutionLinkV1::from))
    }
}

@Serializable
internal data class StoredRefundAttempt(
    val id: DomainId, val originalOperationId: OperationId, val originalActorId: DomainId, val originalCashSessionId: DomainId,
    val originalPaymentId: DomainId, val tender: StoredTender, val amount: Money, val status: String,
    val latestObservation: StoredRefundOutcome?, val refund: StoredRefundRecord?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resolutionObservation: StoredRetailResolutionLinkV1? = null,
) {
    fun restore(): RefundAttempt = RefundAttempt(id, originalOperationId, originalActorId, originalCashSessionId,
        originalPaymentId, tender.restore(), amount, enumValueOf<AttemptStatus>(status), latestObservation?.restore(), refund?.restore(), resolutionObservation?.restore())
        .also(::validateStoredRefundAttempt)

    companion object {
        fun from(value: RefundAttempt): StoredRefundAttempt = StoredRefundAttempt(value.id, value.originalOperationId,
            value.originalActorId, value.originalCashSessionId, value.originalPaymentId, StoredTender.from(value.tender),
            value.amount, value.status.name, value.latestObservation?.let(StoredRefundOutcome::from), value.refund?.let(StoredRefundRecord::from),
            value.resolutionObservation?.let(StoredRetailResolutionLinkV1::from))
    }
}

internal fun validateStoredRefund(refund: RefundRecord) {
    refund.amount.requirePositive()
    if (refund.resolutionObservation == null) snapshotCheck(refund.attestation.actorId == refund.payingActorId)
    else requireStoredResolutionLinkV1(refund.resolutionObservation, refund.originalOperationId,
        refund.recordingOperationId, refund.attestation.actorId)
    when (refund.tender) {
        Tender.Cash -> snapshotCheck(refund.externalEvidence == null)
        is Tender.External -> {
            val evidence = refund.externalEvidence
            snapshotCheck(evidence != null && evidence.attestation == refund.attestation)
            validateExternal(refund.tender, checkNotNull(evidence))
        }
    }
}

internal fun validateStoredRefundAttempt(attempt: RefundAttempt) {
    attempt.amount.requirePositive()
    val observation = attempt.latestObservation
    val refund = attempt.refund
    when (attempt.status) {
        AttemptStatus.AWAITING_ATTESTATION -> snapshotCheck(observation == null && refund == null)
        AttemptStatus.OUTCOME_UNKNOWN -> snapshotCheck(observation is RefundOutcome.Unknown && refund == null)
        AttemptStatus.NOT_PAID -> snapshotCheck(observation is RefundOutcome.NotPaid && refund == null)
        AttemptStatus.ATTESTED_PAID -> snapshotCheck(refund != null &&
            (observation is RefundOutcome.CashPaid || observation is RefundOutcome.ExternalAttested))
    }
    val evidence = when (observation) {
        null -> null
        is RefundOutcome.Unknown -> observation.evidence
        is RefundOutcome.NotPaid -> { requireReason(observation.reason); observation.evidence }
        is RefundOutcome.CashPaid -> observation.evidence
        is RefundOutcome.ExternalAttested -> observation.evidence.attestation
    }
    val link = attempt.resolutionObservation
    if (link == null) snapshotCheck(evidence == null || evidence.actorId == attempt.originalActorId)
    else {
        snapshotCheck(evidence != null && attempt.status in setOf(AttemptStatus.NOT_PAID, AttemptStatus.ATTESTED_PAID))
        requireStoredResolutionLinkV1(link, attempt.originalOperationId, link.resolutionOperationId, checkNotNull(evidence).actorId)
    }
    refund?.let {
        validateStoredRefund(it)
        snapshotCheck(it.attemptId == attempt.id && it.originalOperationId == attempt.originalOperationId &&
            it.payingActorId == attempt.originalActorId && it.payingCashSessionId == attempt.originalCashSessionId &&
            it.originalPaymentId == attempt.originalPaymentId && it.tender == attempt.tender && it.amount == attempt.amount && it.attestation == evidence &&
            it.resolutionObservation == link)
        when (observation) {
            is RefundOutcome.CashPaid -> snapshotCheck(it.tender == Tender.Cash && it.externalEvidence == null)
            is RefundOutcome.ExternalAttested -> snapshotCheck(it.tender is Tender.External && it.externalEvidence == observation.evidence)
            else -> snapshotCheck(false)
        }
    }
}
