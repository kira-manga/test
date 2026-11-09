package com.ukkera.pos.sales

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/**
 * Distinct inert FOOD UNKNOWN linkage. It is not a grant, raw fact, retail profile or
 * physical retry. Whole immutable originals and current scoped audit are adapter-owned.
 */
public data class FoodResolutionObservationLink internal constructor(
    public val originalUnknownOperationId: OperationId,
    public val originalUnknownBodyDigest: PayloadDigest,
    public val resolutionOperationId: OperationId,
    public val resolutionRecorderId: DomainId,
    public val accountingOperationId: OperationId,
    public val accountingActorId: DomainId,
)

/** Shared exact pure value for the real supervised reducer and retained history comparison. */
internal fun foodRecordedResolutionLink(attempt: PaymentAttempt, observationOperationId: OperationId,
    recordingActorId: DomainId, accountingOperationId: OperationId, accountingActorId: DomainId,
    originalUnknownOperationId: OperationId?, originalUnknownBodyDigest: PayloadDigest?,
    unknown: Boolean): FoodResolutionObservationLink? {
    requireSales(observationOperationId != attempt.originalOperationId && observationOperationId != accountingOperationId,
        SalesRule.INVALID_EVIDENCE)
    if (attempt.status == AttemptStatus.AWAITING_ATTESTATION) {
        requireSales(recordingActorId == attempt.originalActorId && originalUnknownOperationId == null &&
            originalUnknownBodyDigest == null, SalesRule.INVALID_EVIDENCE)
        return null
    }
    requireSales(attempt.status == AttemptStatus.OUTCOME_UNKNOWN && !unknown &&
        originalUnknownOperationId != null && originalUnknownBodyDigest != null &&
        (attempt.latestObservation as? CollectionOutcome.Unknown)?.evidence?.actorId == attempt.originalActorId,
        SalesRule.INVALID_EVIDENCE)
    val original = checkNotNull(originalUnknownOperationId)
    requireSales(setOf(attempt.originalOperationId, original, observationOperationId, accountingOperationId).size == 4,
        SalesRule.INVALID_EVIDENCE)
    return FoodResolutionObservationLink(original, checkNotNull(originalUnknownBodyDigest), observationOperationId,
        recordingActorId, accountingOperationId, accountingActorId)
}
