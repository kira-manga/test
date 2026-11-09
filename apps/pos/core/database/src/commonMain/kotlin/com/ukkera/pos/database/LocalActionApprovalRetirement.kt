package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** One deterministic locator in a separate digest domain. Colliding audit identities fail closed. */
internal fun actionApprovalRetirementId(scope: BranchScope, approvalId: DomainId): DomainId {
    val bytes = ("ukkera.local-action-approval-retirement-key/v1\u0000${scope.storageKey()}\u0000${approvalId.value}").encodeToByteArray()
    val digest = try { sha256(bytes).value } finally { bytes.fill(0) }
    val hex = digest.take(32).toCharArray()
    hex[12] = '8'
    hex[16] = "89ab"[hex[16].digitToInt(16) and 3]
    return DomainId(buildString(36) {
        hex.forEachIndexed { index, char ->
            if (index in setOf(8, 12, 16, 20)) append('-')
            append(char)
        }
    })
}

internal fun actionApprovalRetirementEvent(value: ProtectedLocalActionApproval,
    requestor: RuntimeCommandAuthorizationSnapshotV1): LocalAuthEvent {
    val scope = value.record.requirement.scope as BranchScope
    require(value.hasApproverIncarnation && value.record.revision == Revision(0) && value.record.consumedByOperationId == null &&
        requestor.scope == scope && requestor.actorId == value.record.requestorId)
    return LocalAuthEvent(scope, actionApprovalRetirementId(scope, value.record.approvalId),
        LocalAuthEventKind.ACTION_APPROVAL_RETIRED, value.record.approvalId, requestor.actorId,
        requestor.sessionId, requestor.accountRevision, requestor.access.observedAt, runtimeApprovalDigest(value))
}

/** An unrelated event at the deterministic key is a conflict, never evidence of retirement. */
internal fun requireActionApprovalRetirementEvent(value: ProtectedLocalActionApproval, event: LocalAuthEvent) {
    val scope = value.record.requirement.scope as BranchScope
    require(value.hasApproverIncarnation && value.record.revision == Revision(0) && value.record.consumedByOperationId == null &&
        event.scope == scope && event.eventId == actionApprovalRetirementId(scope, value.record.approvalId) &&
        event.eventId != value.record.approvalId && event.kind == LocalAuthEventKind.ACTION_APPROVAL_RETIRED &&
        event.targetId == value.record.approvalId && event.operatorId == value.record.requestorId &&
        event.sessionId != null && event.accountRevision != null && event.contentDigest == runtimeApprovalDigest(value))
}

internal fun sameActionApprovalRetirementEvent(left: LocalAuthEvent, right: LocalAuthEvent): Boolean {
    val a = LocalAuthenticationCodec.encodeEvent(left)
    return try {
        val b = LocalAuthenticationCodec.encodeEvent(right)
        try { a.contentEquals(b) } finally { b.fill(0) }
    } finally { a.fill(0) }
}

internal fun actionApprovalRetirementReceipt(value: ProtectedLocalActionApproval,
    event: LocalAuthEvent): LocalActionApprovalRetirementReceipt {
    requireActionApprovalRetirementEvent(value, event)
    return LocalActionApprovalRetirementReceipt(event.eventId, value.record.approvalId,
        checkNotNull(value.record.requirement.binding?.operationId), value.record.requestorId, value.confirmationDigest,
        checkNotNull(event.contentDigest), event.at)
}

/** The existing actual native owner admitted the entire before/history cut of this same loan. */
internal suspend fun readVerifiedActionApprovalRetirement(transaction: RoomLocalSecurityTransaction,
    value: ProtectedLocalActionApproval): LocalAuthEvent? {
    val scope = value.record.requirement.scope as BranchScope
    transaction.requireActionApprovalRetirementHistory()
    val event = transaction.readAuthEvent(scope, actionApprovalRetirementId(scope, value.record.approvalId))
    transaction.verifyActionApprovalRetirementHistory(value, event)
    if (event == null) return null
    try { requireActionApprovalRetirementEvent(value, event) }
    catch (_: IllegalArgumentException) { transaction.refuse() }
    return event
}

/** Only a recorded native retirement can be an ancestor of a later issuance. The existing
 * reader also validates this frame's tentative one-audit capture, but that is not reusable. */
internal class RecordedActionApprovalRetirement(val event: LocalAuthEvent, val historySequence: Long)

internal suspend fun readRecordedActionApprovalRetirement(transaction: RoomLocalSecurityTransaction,
    value: ProtectedLocalActionApproval): RecordedActionApprovalRetirement? {
    val scope = value.record.requirement.scope as BranchScope
    transaction.requireActionApprovalRetirementHistory()
    val event = transaction.readAuthEvent(scope, actionApprovalRetirementId(scope, value.record.approvalId))
    val sequence = transaction.verifyActionApprovalRetirementHistory(value, event)
    if (event == null) return null
    if (sequence == null) transaction.refuse()
    try { requireActionApprovalRetirementEvent(value, event) }
    catch (_: IllegalArgumentException) { transaction.refuse() }
    return RecordedActionApprovalRetirement(event, sequence)
}
