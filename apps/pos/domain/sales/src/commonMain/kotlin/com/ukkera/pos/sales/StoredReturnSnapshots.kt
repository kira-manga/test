package com.ukkera.pos.sales

import com.ukkera.pos.pricing.ReturnedPriceComponent
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

@Serializable
internal data class StoredClaimEvidence(
    val policyId: DomainId, val policyRevision: Revision, val customerReceivedAt: UtcInstant,
    val claimedAt: UtcInstant, val remedyDueAt: UtcInstant?, val reason: String, val evidence: StoredCashierEvidence,
) {
    fun restore(): ReturnClaimEvidence = ReturnClaimEvidence(policyId, policyRevision, customerReceivedAt.value,
        claimedAt.value, remedyDueAt?.value, reason, evidence.restore())

    companion object {
        fun from(value: ReturnClaimEvidence): StoredClaimEvidence = StoredClaimEvidence(value.policyId, value.policyRevision,
            UtcInstant(value.customerReceivedAt), UtcInstant(value.claimedAt), value.remedyDueAt?.let(::UtcInstant),
            value.reason, StoredCashierEvidence.from(value.evidence))
    }
}

@Serializable
internal data class StoredReturnClaim(
    val saleId: DomainId, val portionId: DomainId, val quantity: Quantity, val claimantOperatorId: DomainId,
    val evidence: StoredClaimEvidence, val acceptedReturnId: DomainId?,
) {
    fun restore(binding: SalesSnapshotBinding): ReturnClaim = ReturnClaim(binding.id, binding.scope, saleId, portionId,
        quantity, claimantOperatorId, evidence.restore(), binding.revision, acceptedReturnId).also(::validateStoredClaim)

    companion object {
        fun from(value: ReturnClaim): StoredReturnClaim = StoredReturnClaim(value.saleId, value.portionId, value.quantity,
            value.claimantOperatorId, StoredClaimEvidence.from(value.evidence), value.acceptedReturnId)
    }
}

@Serializable
internal data class StoredReturnedComponent(val key: String, val kind: String, val chargeId: DomainId?, val amount: Money) {
    fun matches(value: ReturnedPriceComponent): Boolean = key == value.key && kind == value.kind.name && chargeId == value.chargeId && amount == value.amount

    companion object {
        fun from(value: ReturnedPriceComponent): StoredReturnedComponent = StoredReturnedComponent(value.key, value.kind.name, value.chargeId, value.amount)
    }
}

@Serializable
internal data class StoredReturnTenderObligation(val originalPaymentId: DomainId, val tender: StoredTender, val amount: Money) {
    fun restore(): ReturnTenderObligation = ReturnTenderObligation(originalPaymentId, tender.restore(), amount)

    companion object {
        fun from(value: ReturnTenderObligation): StoredReturnTenderObligation = StoredReturnTenderObligation(value.originalPaymentId, StoredTender.from(value.tender), value.amount)
    }
}

@Serializable
internal data class StoredAcceptedReturn(
    val saleId: DomainId, val claimId: DomainId, val portionId: DomainId, val quantity: Quantity,
    val consumedQuantityBefore: Quantity, val acceptingOperatorId: DomainId, val evidence: StoredClaimEvidence,
    val requestedDisposition: String, val inspectionEvidence: StoredCashierEvidence, val originalPayable: Money,
    val components: List<StoredReturnedComponent>, val tenderObligations: List<StoredReturnTenderObligation>,
    val attempts: List<StoredRefundAttempt>,
) {
    fun restore(binding: SalesSnapshotBinding, entitlement: ReceiptEntitlement): AcceptedReturn {
        snapshotCheck(components.size in 1..1_024 && tenderObligations.size <= 64 && attempts.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        snapshotCheck(binding.scope == entitlement.scope && saleId == entitlement.saleId)
        val cursor = restoreOriginalCursor(entitlement.sale.quote, portionId, consumedQuantityBefore)
        val slice = cursor.consume(quantity)
        snapshotCheck(originalPayable == slice.payable && components.size == slice.components.size &&
            components.zip(slice.components).all { (stored, original) -> stored.matches(original) })
        // Pricing owns the component constructors. The validated original slice is
        // restored using its stored profile, not caller-made objects or today's price.
        return AcceptedReturn(binding.id, binding.scope, saleId, claimId, portionId, quantity, consumedQuantityBefore,
            acceptingOperatorId, evidence.restore(), enumValueOf<ReturnDisposition>(requestedDisposition), inspectionEvidence.restore(),
            originalPayable, slice.components, tenderObligations.map { it.restore() }, binding.revision, attempts.map { it.restore() })
            .also(::validateStoredReturn)
    }

    companion object {
        fun from(value: AcceptedReturn): StoredAcceptedReturn = StoredAcceptedReturn(value.saleId, value.claimId, value.portionId,
            value.quantity, value.consumedQuantityBefore, value.acceptingOperatorId, StoredClaimEvidence.from(value.evidence),
            value.requestedDisposition.name, StoredCashierEvidence.from(value.inspectionEvidence), value.originalPayable,
            value.components.map(StoredReturnedComponent::from), value.tenderObligations.map(StoredReturnTenderObligation::from),
            value.attempts.map(StoredRefundAttempt::from))
    }
}

internal fun validateStoredClaim(claim: ReturnClaim) {
    claim.quantity.requirePositive()
    snapshotCheck(claim.evidence.evidence.actorId == claim.claimantOperatorId)
    snapshotCheck(if (claim.acceptedReturnId == null) claim.revision == Revision(0) else claim.revision == Revision(1))
}

internal fun validateStoredReturn(accepted: AcceptedReturn) {
    accepted.quantity.requirePositive()
    accepted.originalPayable.requireNonnegative()
    snapshotCheck(accepted.consumedQuantityBefore.unitId == accepted.quantity.unitId &&
        accepted.consumedQuantityBefore.scale == accepted.quantity.scale && accepted.consumedQuantityBefore.scaled >= 0L)
    snapshotCheck(accepted.inspectionEvidence.actorId == accepted.acceptingOperatorId)
    snapshotCheck(accepted.components.isNotEmpty() && accepted.components.map { it.key }.distinct().size == accepted.components.size)
    snapshotCheck(accepted.components.fold(accepted.originalPayable.zero()) { total, component ->
        sameCurrency(accepted.originalPayable, component.amount)
        total + component.amount.requireNonnegative()
    } == accepted.originalPayable)
    snapshotCheck(accepted.tenderObligations.map { it.originalPaymentId }.distinct().size == accepted.tenderObligations.size)
    snapshotCheck(accepted.tenderObligations.fold(accepted.originalPayable.zero()) { total, obligation ->
        sameCurrency(accepted.originalPayable, obligation.amount)
        total + obligation.amount.requirePositive()
    } == accepted.originalPayable)
    snapshotCheck(accepted.attempts.map { it.id }.distinct().size == accepted.attempts.size)
    snapshotCheck(accepted.attempts.map { it.originalOperationId }.distinct().size == accepted.attempts.size)
    snapshotCheck(accepted.revision.value >= accepted.attempts.size.toLong())
    val remaining = accepted.tenderObligations.associate { it.originalPaymentId to it.amount }.toMutableMap()
    for ((index, attempt) in accepted.attempts.withIndex()) {
        validateStoredRefundAttempt(attempt)
        val obligation = accepted.tenderObligations.singleOrNull { it.originalPaymentId == attempt.originalPaymentId }
            ?: throw SalesSnapshotRejectedException(SalesSnapshotFailure.INCONSISTENT_STATE)
        sameCurrency(accepted.originalPayable, attempt.amount)
        snapshotCheck(attempt.tender == obligation.tender && attempt.amount.minor <= remaining.getValue(attempt.originalPaymentId).minor)
        snapshotCheck(!attempt.status.isUnresolved() || index == accepted.attempts.lastIndex)
        attempt.refund?.let { refund ->
            snapshotCheck(refund.returnId == accepted.id)
            remaining[attempt.originalPaymentId] = remaining.getValue(attempt.originalPaymentId) - refund.amount
        }
    }
    snapshotCheck(accepted.due.minor >= 0L)
}
