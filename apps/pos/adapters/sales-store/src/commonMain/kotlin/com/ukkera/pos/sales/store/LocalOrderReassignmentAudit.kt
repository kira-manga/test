package com.ukkera.pos.sales.store

import com.ukkera.pos.sales.OrderReassigned
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/**
 * One create-only audited manager event, addressed by the original operation ID. Receiving
 * eligibility is deliberately absent: these business facts cannot certify protected authority.
 */
@Serializable
internal data class LocalOrderReassignmentAuditV1(
    val scope: LocalSalesScope,
    val id: DomainId,
    val revision: Revision,
    val operationId: OperationId,
    val orderId: DomainId,
    val beforeOrderRevision: Revision,
    val afterOrderRevision: Revision,
    val managerId: DomainId,
    val previousOwnerId: DomainId,
    val recipientId: DomainId,
    val beforePreviousWorkRevision: Revision,
    val afterPreviousWorkRevision: Revision,
    val beforeRecipientWorkRevision: Revision,
    val afterRecipientWorkRevision: Revision,
    val reasonCode: String,
    val evidence: LocalCashEvidenceV1,
    val recordedAt: UtcInstant,
)

/** Constructs literal audit bytes only; never an eligibility or historical authorization. */
internal fun orderReassignmentAudit(event: OrderReassigned, previousWork: LocalWorkState,
    recipientWork: LocalWorkState): LocalOrderReassignmentAuditV1 {
    check(event.evidence.actorId == event.managerId && event.afterRevision == event.beforeRevision.next() &&
        event.previousOwnerId != event.recipientId && previousWork.id == event.previousOwnerId &&
        recipientWork.id == event.recipientId && previousWork.scope.value() == event.scope && recipientWork.scope.value() == event.scope)
    return LocalOrderReassignmentAuditV1(LocalSalesScope.from(event.scope), DomainId(event.operationId.value), Revision(0),
        event.operationId, event.orderId, event.beforeRevision, event.afterRevision, event.managerId, event.previousOwnerId,
        event.recipientId, previousWork.revision, previousWork.revision.next(), recipientWork.revision, recipientWork.revision.next(),
        event.reasonCode, LocalCashEvidenceV1(event.evidence.evidenceId, UtcInstant(event.evidence.occurredAt)), UtcInstant(event.recordedAt))
}
