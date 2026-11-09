@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.sales

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface StoredTender {
    @Serializable @SerialName("CASH") data object Cash : StoredTender
    @Serializable @SerialName("EXTERNAL") data class External(
        val tenderId: DomainId, val policyRevision: Revision, val kind: String,
        val requiresTerminal: Boolean, val requiresReference: Boolean,
    ) : StoredTender

    fun restore(): Tender = when (this) {
        Cash -> Tender.Cash
        is External -> Tender.External(tenderId, policyRevision, enumValueOf<ExternalTenderKind>(kind), requiresTerminal, requiresReference)
    }

    companion object {
        fun from(value: Tender): StoredTender = when (value) {
            Tender.Cash -> Cash
            is Tender.External -> External(value.tenderId, value.policyRevision, value.kind.name, value.requiresTerminal, value.requiresReference)
        }
    }
}

@Serializable
internal data class StoredExternalEvidence(val attestation: StoredCashierEvidence, val terminal: String?, val reference: String?) {
    fun restore(): ExternalTenderEvidence = ExternalTenderEvidence(attestation.restore(), terminal, reference)

    companion object {
        fun from(value: ExternalTenderEvidence): StoredExternalEvidence = StoredExternalEvidence(
            StoredCashierEvidence.from(value.attestation), value.terminal, value.reference)
    }
}

@Serializable
internal sealed interface StoredCollectionOutcome {
    @Serializable @SerialName("UNKNOWN") data class Unknown(val evidence: StoredCashierEvidence) : StoredCollectionOutcome
    @Serializable @SerialName("NOT_PAID") data class NotPaid(val evidence: StoredCashierEvidence, val reason: String) : StoredCollectionOutcome
    @Serializable @SerialName("CASH_RECEIVED") data class CashReceived(
        val received: Money, val change: Money, val evidence: StoredCashierEvidence,
    ) : StoredCollectionOutcome
    @Serializable @SerialName("EXTERNAL_ATTESTED") data class ExternalAttested(val evidence: StoredExternalEvidence) : StoredCollectionOutcome

    fun restore(): CollectionOutcome = when (this) {
        is Unknown -> CollectionOutcome.Unknown(evidence.restore())
        is NotPaid -> CollectionOutcome.NotPaid(evidence.restore(), reason)
        is CashReceived -> CollectionOutcome.CashReceived(received, change, evidence.restore())
        is ExternalAttested -> CollectionOutcome.ExternalAttested(evidence.restore())
    }

    companion object {
        fun from(value: CollectionOutcome): StoredCollectionOutcome = when (value) {
            is CollectionOutcome.Unknown -> Unknown(StoredCashierEvidence.from(value.evidence))
            is CollectionOutcome.NotPaid -> NotPaid(StoredCashierEvidence.from(value.evidence), value.reason)
            is CollectionOutcome.CashReceived -> CashReceived(value.received, value.change, StoredCashierEvidence.from(value.evidence))
            is CollectionOutcome.ExternalAttested -> ExternalAttested(StoredExternalEvidence.from(value.evidence))
        }
    }
}

/** Historical value only. No decoder returns employee authority or an executable application. */
@Serializable
internal data class StoredRetailResolutionLinkV1(val originalUnknownOperationId: OperationId,
    val originalUnknownBodyDigest: PayloadDigest, val resolutionOperationId: OperationId,
    val resolutionRecorderId: DomainId, val accountingOperationId: OperationId, val accountingActorId: DomainId) {
    fun restore(): RetailResolutionObservationLink = RetailResolutionObservationLink(originalUnknownOperationId,
        originalUnknownBodyDigest, resolutionOperationId, resolutionRecorderId, accountingOperationId, accountingActorId)
    companion object {
        fun from(value: RetailResolutionObservationLink): StoredRetailResolutionLinkV1 = with(value) {
            StoredRetailResolutionLinkV1(originalUnknownOperationId, originalUnknownBodyDigest, resolutionOperationId,
                resolutionRecorderId, accountingOperationId, accountingActorId)
        }
    }
}

/** FOOD linkage is a separate stored shape, never the retail link/profile. */
@Serializable
internal data class StoredFoodResolutionLinkV1(val originalUnknownOperationId: OperationId,
    val originalUnknownBodyDigest: PayloadDigest, val resolutionOperationId: OperationId,
    val resolutionRecorderId: DomainId, val accountingOperationId: OperationId, val accountingActorId: DomainId) {
    fun restore(): FoodResolutionObservationLink = FoodResolutionObservationLink(originalUnknownOperationId,
        originalUnknownBodyDigest, resolutionOperationId, resolutionRecorderId, accountingOperationId, accountingActorId)
    companion object {
        fun from(value: FoodResolutionObservationLink): StoredFoodResolutionLinkV1 = with(value) {
            StoredFoodResolutionLinkV1(originalUnknownOperationId, originalUnknownBodyDigest, resolutionOperationId,
                resolutionRecorderId, accountingOperationId, accountingActorId)
        }
    }
}

@Serializable
internal data class StoredPaymentRecord(
    val attemptId: DomainId, val originalOperationId: OperationId, val recordingOperationId: OperationId,
    val collectionId: DomainId, val collectorId: DomainId, val cashSessionId: DomainId, val payerReference: String?,
    val tender: StoredTender, val amount: Money, val cashReceived: Money?, val changeGiven: Money?,
    val attestation: StoredCashierEvidence, val externalEvidence: StoredExternalEvidence?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resolutionObservation: StoredRetailResolutionLinkV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val foodResolutionObservation: StoredFoodResolutionLinkV1? = null,
) {
    fun restore(): PaymentRecord = PaymentRecord(attemptId, originalOperationId, recordingOperationId, collectionId,
        collectorId, cashSessionId, payerReference, tender.restore(), amount, cashReceived, changeGiven,
        attestation.restore(), externalEvidence?.restore(), resolutionObservation?.restore(), foodResolutionObservation?.restore()).also(::validateStoredPayment)

    companion object {
        fun from(value: PaymentRecord): StoredPaymentRecord = StoredPaymentRecord(value.attemptId, value.originalOperationId,
            value.recordingOperationId, value.collectionId, value.collectorId, value.cashSessionId, value.payerReference,
            StoredTender.from(value.tender), value.amount, value.cashReceived, value.changeGiven,
            StoredCashierEvidence.from(value.attestation), value.externalEvidence?.let(StoredExternalEvidence::from),
            value.resolutionObservation?.let(StoredRetailResolutionLinkV1::from),
            value.foodResolutionObservation?.let(StoredFoodResolutionLinkV1::from))
    }
}

@Serializable
internal data class StoredPaymentAttempt(
    val id: DomainId, val originalOperationId: OperationId, val originalActorId: DomainId, val originalCashSessionId: DomainId,
    val payerReference: String?, val tender: StoredTender, val amount: Money, val status: String,
    val latestObservation: StoredCollectionOutcome?, val payment: StoredPaymentRecord?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resolutionObservation: StoredRetailResolutionLinkV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val foodResolutionObservation: StoredFoodResolutionLinkV1? = null,
) {
    fun restore(): PaymentAttempt = PaymentAttempt(id, originalOperationId, originalActorId, originalCashSessionId,
        payerReference, tender.restore(), amount, enumValueOf<AttemptStatus>(status), latestObservation?.restore(), payment?.restore(), resolutionObservation?.restore(), foodResolutionObservation?.restore())
        .also(::validateStoredPaymentAttempt)

    companion object {
        fun from(value: PaymentAttempt): StoredPaymentAttempt = StoredPaymentAttempt(value.id, value.originalOperationId,
            value.originalActorId, value.originalCashSessionId, value.payerReference, StoredTender.from(value.tender),
            value.amount, value.status.name, value.latestObservation?.let(StoredCollectionOutcome::from),
            value.payment?.let(StoredPaymentRecord::from), value.resolutionObservation?.let(StoredRetailResolutionLinkV1::from),
            value.foodResolutionObservation?.let(StoredFoodResolutionLinkV1::from))
    }
}

internal fun validateStoredPayment(payment: PaymentRecord) {
    payment.amount.requirePositive()
    validatePayerReference(payment.payerReference)
    snapshotCheck(payment.resolutionObservation == null || payment.foodResolutionObservation == null)
    when {
        payment.resolutionObservation != null -> requireStoredResolutionLinkV1(payment.resolutionObservation,
            payment.originalOperationId, payment.recordingOperationId, payment.attestation.actorId)
        payment.foodResolutionObservation != null -> requireStoredFoodResolutionLinkV1(payment.foodResolutionObservation,
            payment.originalOperationId, payment.recordingOperationId, payment.attestation.actorId)
        else -> snapshotCheck(payment.attestation.actorId == payment.collectorId)
    }
    when (payment.tender) {
        Tender.Cash -> {
            val received = payment.cashReceived
            val change = payment.changeGiven
            snapshotCheck(received != null && change != null && payment.externalEvidence == null)
            checkNotNull(received).requirePositive()
            checkNotNull(change).requireNonnegative()
            sameCurrency(payment.amount, received, change)
            snapshotCheck(received - change == payment.amount)
        }
        is Tender.External -> {
            snapshotCheck(payment.cashReceived == null && payment.changeGiven == null && payment.externalEvidence != null)
            val external = checkNotNull(payment.externalEvidence)
            snapshotCheck(external.attestation == payment.attestation)
            validateExternal(payment.tender, external)
        }
    }
}

internal fun validateStoredPaymentAttempt(attempt: PaymentAttempt) {
    attempt.amount.requirePositive()
    validatePayerReference(attempt.payerReference)
    val observation = attempt.latestObservation
    val payment = attempt.payment
    when (attempt.status) {
        AttemptStatus.AWAITING_ATTESTATION -> snapshotCheck(observation == null && payment == null)
        AttemptStatus.OUTCOME_UNKNOWN -> snapshotCheck(observation is CollectionOutcome.Unknown && payment == null)
        AttemptStatus.NOT_PAID -> snapshotCheck(observation is CollectionOutcome.NotPaid && payment == null)
        AttemptStatus.ATTESTED_PAID -> snapshotCheck(payment != null &&
            (observation is CollectionOutcome.CashReceived || observation is CollectionOutcome.ExternalAttested))
    }
    val evidence = when (observation) {
        null -> null
        is CollectionOutcome.Unknown -> observation.evidence
        is CollectionOutcome.NotPaid -> { requireReason(observation.reason); observation.evidence }
        is CollectionOutcome.CashReceived -> observation.evidence
        is CollectionOutcome.ExternalAttested -> observation.evidence.attestation
    }
    val link = attempt.resolutionObservation
    val foodLink = attempt.foodResolutionObservation
    snapshotCheck(link == null || foodLink == null)
    if (link == null && foodLink == null) snapshotCheck(evidence == null || evidence.actorId == attempt.originalActorId)
    else if (foodLink != null) {
        snapshotCheck(evidence != null && attempt.status in setOf(AttemptStatus.NOT_PAID, AttemptStatus.ATTESTED_PAID))
        requireStoredFoodResolutionLinkV1(foodLink, attempt.originalOperationId, foodLink.resolutionOperationId,
            checkNotNull(evidence).actorId)
    } else {
        snapshotCheck(evidence != null && attempt.status in setOf(AttemptStatus.NOT_PAID, AttemptStatus.ATTESTED_PAID))
        val retailLink = checkNotNull(link)
        requireStoredResolutionLinkV1(retailLink, attempt.originalOperationId, retailLink.resolutionOperationId, checkNotNull(evidence).actorId)
    }
    payment?.let {
        validateStoredPayment(it)
        snapshotCheck(it.attemptId == attempt.id && it.originalOperationId == attempt.originalOperationId &&
            it.collectorId == attempt.originalActorId && it.cashSessionId == attempt.originalCashSessionId &&
            it.payerReference == attempt.payerReference && it.tender == attempt.tender && it.amount == attempt.amount && it.attestation == evidence &&
            it.resolutionObservation == link && it.foodResolutionObservation == foodLink)
        when (observation) {
            is CollectionOutcome.CashReceived -> snapshotCheck(it.tender == Tender.Cash && it.cashReceived == observation.received &&
                it.changeGiven == observation.change && it.externalEvidence == null)
            is CollectionOutcome.ExternalAttested -> snapshotCheck(it.tender is Tender.External && it.externalEvidence == observation.evidence)
            else -> snapshotCheck(false)
        }
    }
}

internal fun validatePayerReference(value: String?) {
    snapshotCheck(value == null || value.isNotBlank() && value.length <= 128)
}

internal fun AttemptStatus.isUnresolved(): Boolean = this == AttemptStatus.AWAITING_ATTESTATION || this == AttemptStatus.OUTCOME_UNKNOWN

internal fun requireStoredResolutionLinkV1(link: RetailResolutionObservationLink, intent: OperationId,
    observation: OperationId, recorder: DomainId) {
    snapshotCheck(setOf(intent, link.originalUnknownOperationId, link.resolutionOperationId, link.accountingOperationId).size == 4 &&
        observation == link.resolutionOperationId && recorder == link.resolutionRecorderId)
    // Digest format is checked by its closed value type; source existence/audit is adapter-owned.
    PayloadDigest(link.originalUnknownBodyDigest.value)
}

/** Intrinsic immutable linkage only; actual UNKNOWN/native/audit originals are adapter-owned. */
internal fun requireStoredFoodResolutionLinkV1(link: FoodResolutionObservationLink, intent: OperationId,
    observation: OperationId, recorder: DomainId) {
    snapshotCheck(setOf(intent, link.originalUnknownOperationId, link.resolutionOperationId, link.accountingOperationId).size == 4 &&
        observation == link.resolutionOperationId && recorder == link.resolutionRecorderId)
    PayloadDigest(link.originalUnknownBodyDigest.value)
}
