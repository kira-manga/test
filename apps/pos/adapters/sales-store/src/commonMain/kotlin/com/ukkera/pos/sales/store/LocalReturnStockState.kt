package com.ukkera.pos.sales.store

import com.ukkera.pos.sales.AcceptedReturn
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** Scalar independent stock frontier. Immutable posting records retain the actual selections. */
@Serializable
internal data class LocalReturnStockStateV1(
    val scope: BranchScope,
    val returnId: DomainId,
    val saleId: DomainId,
    val caseId: DomainId,
    val portionId: DomainId,
    val issueId: DomainId,
    val acceptanceOperationId: OperationId,
    val acceptanceDigest: PayloadDigest,
    val acceptedQuantity: Quantity,
    val revision: Revision,
    val postedQuantity: Quantity,
    val postingCount: Long,
    val lastRestorationId: DomainId,
    val lastOperationId: OperationId,
)

@Serializable
internal data class LocalReturnStockPostingV1(
    val scope: BranchScope,
    val operationId: OperationId,
    val semanticDigest: PayloadDigest,
    val actorId: DomainId,
    val recordedAt: UtcInstant,
    val request: LocalReturnStockRequestV1,
    val beforeStateDigest: PayloadDigest?,
    val quantity: Quantity,
    val after: LocalReturnStockStateV1,
    val result: LocalReturnStockResultV1.Applied,
)

internal class LocalReturnStockRefused(val reason: String) : IllegalArgumentException(reason)
internal fun requireReturnStock(value: Boolean, reason: String) {
    if (!value) throw LocalReturnStockRefused(reason)
}

internal fun returnStockStatus(posted: Quantity, accepted: Quantity): LocalReturnStockStatusV1 {
    require(posted.unitId == accepted.unitId && posted.scale == accepted.scale && posted.scaled >= 0 && posted <= accepted)
    return when {
        posted.scaled == 0L -> LocalReturnStockStatusV1.PENDING
        posted == accepted -> LocalReturnStockStatusV1.POSTED
        else -> LocalReturnStockStatusV1.PARTIALLY_POSTED
    }
}

/** Quantity conservation only; actual current authority and stock sources belong to the
 * owning Master transaction. This never changes entitlement, refunds, cash or CASE origin. */
internal fun returnStockPostingValue(
    scope: BranchScope,
    operationId: OperationId,
    semanticDigest: PayloadDigest,
    actorId: DomainId,
    recordedAt: UtcInstant,
    request: LocalReturnStockRequestV1,
    accepted: AcceptedReturn,
    acceptanceOperationId: OperationId,
    acceptanceDigest: PayloadDigest,
    before: LocalReturnStockStateV1?,
    beforeDigest: PayloadDigest?,
): LocalReturnStockPostingV1 {
    require(accepted.scope == scope && accepted.id == request.returnId && accepted.saleId == request.saleId &&
        accepted.claimId == request.caseId && accepted.quantity.scaled > 0)
    require(before == null && beforeDigest == null || before != null && beforeDigest != null)
    val original = accepted.quantity
    requireReturnStock(request.acceptedQuantity == original, "ACCEPTED_SOURCE_CHANGED")
    requireReturnStock((before?.revision ?: Revision(0)) == request.expectedDispositionRevision, "DISPOSITION_REVISION_CHANGED")
    if (before != null) require(before.scope == scope && before.returnId == accepted.id && before.saleId == accepted.saleId &&
        before.caseId == accepted.claimId && before.portionId == accepted.portionId && before.issueId == request.issueId &&
        before.acceptanceOperationId == acceptanceOperationId && before.acceptanceDigest == acceptanceDigest &&
        before.acceptedQuantity == original && before.revision.value >= 1 && before.postingCount == before.revision.value &&
        before.postedQuantity.scaled > 0 && before.postedQuantity <= original &&
        before.postedQuantity.unitId == original.unitId && before.postedQuantity.scale == original.scale)
    requireReturnStock(accepted.requestedDisposition in setOf(ReturnDisposition.INSPECTED_SELLABLE, ReturnDisposition.QUARANTINE),
        "DISPOSITION_NOT_SUPPORTED")
    requireReturnStock(request.selections.size in 1..LocalReturnStockProfileV1.MAX_SELECTIONS &&
        request.selections.map { it.allocationId }.distinct().size == request.selections.size, "INVALID_SELECTION")
    var selected = Quantity(original.unitId, 0, original.scale)
    for (selection in request.selections) {
        requireReturnStock(selection.quantity.unitId == original.unitId && selection.quantity.scale == original.scale &&
            selection.quantity.scaled > 0, "ORIGINAL_UNIT_REQUIRED")
        requireReturnStock(accepted.requestedDisposition != ReturnDisposition.QUARANTINE ||
            selection.custody == LocalReturnStockCustodyV1.QUARANTINE, "INSPECTION_CUSTODY_REQUIRED")
        // Guard the sum before addition; overflow cannot become a smaller allowed return.
        requireReturnStock(selected.scaled <= original.scaled - selection.quantity.scaled, "ACCEPTED_QUANTITY_EXHAUSTED")
        selected += selection.quantity
    }
    val consumed = before?.postedQuantity ?: Quantity(original.unitId, 0, original.scale)
    requireReturnStock(request.expectedPostedQuantity == consumed, "DISPOSITION_QUANTITY_CHANGED")
    requireReturnStock(selected <= original - consumed, "ACCEPTED_QUANTITY_EXHAUSTED")
    val total = consumed + selected
    val next = (before?.revision ?: Revision(0)).next()
    val after = LocalReturnStockStateV1(scope, accepted.id, accepted.saleId, accepted.claimId, accepted.portionId,
        request.issueId, acceptanceOperationId, acceptanceDigest, original, next, total, next.value,
        request.restorationId, operationId)
    return LocalReturnStockPostingV1(scope, operationId, semanticDigest, actorId, recordedAt, request, beforeDigest,
        selected, after, LocalReturnStockResultV1.Applied(accepted.id, request.restorationId, next, total, original - total,
            returnStockStatus(total, original)))
}
