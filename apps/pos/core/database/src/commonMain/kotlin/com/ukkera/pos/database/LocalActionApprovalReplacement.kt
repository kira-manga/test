package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Canonical DATA joined to the actual native issuance final decision and current row census. */
internal class RuntimeApprovalIssuanceOrigin(
    val historySequence: Long,
    val evidence: RuntimeActionApprovalIssuanceEvidenceV1,
) {
    val issued: ProtectedLocalActionApproval = evidence.value()
    val approvalId: DomainId get() = issued.record.approvalId
    val submissionDigest: PayloadDigest get() = checkNotNull(issued.issueRequestDigest)
    val command: CommandKey get() = CommandKey(evidence.commandName, evidence.commandVersion)
}

/** This binding is constructed only after the actual native origin/retirement census.
 * The references are consistency witnesses, never a replacement for that census. */
internal class QualifiedActionApprovalIssueBinding(
    val originalRequestor: ActionApprovalRequestorAuthority,
    val replacementOrigin: ActionApprovalReplacementOrigin?,
    private val rootSequence: Long?,
    private val predecessorSequence: Long?,
    private val retirementSequence: Long?,
    private val priorApprovals: List<ProtectedLocalActionApproval> = emptyList(),
    private val priorRetirements: List<RecordedActionApprovalRetirement> = emptyList(),
) {
    init { require(priorApprovals.size == priorRetirements.size) }
    fun sameBinding(other: QualifiedActionApprovalIssueBinding): Boolean = originalRequestor == other.originalRequestor &&
        replacementOrigin == other.replacementOrigin && rootSequence == other.rootSequence &&
        predecessorSequence == other.predecessorSequence && retirementSequence == other.retirementSequence &&
        priorApprovals.map(::runtimeApprovalText) == other.priorApprovals.map(::runtimeApprovalText) &&
        priorRetirements.size == other.priorRetirements.size && priorRetirements.zip(other.priorRetirements).all { (a, b) ->
            a.historySequence == b.historySequence && sameActionApprovalRetirementEvent(a.event, b.event)
        }

    /** Revalidate the immutable selected ancestors after tentative issuance writes, without
     * pretending the new not-yet-native row belongs to an earlier native issuance census. */
    suspend fun requireUnchanged(transaction: RoomLocalSecurityTransaction) {
        priorApprovals.zip(priorRetirements).forEach { (expected, retired) ->
            val scope = expected.record.requirement.scope as BranchScope
            val current = readVerifiedActionApproval(transaction, scope, expected.record.approvalId) ?: transaction.refuse()
            if (runtimeApprovalText(current) != runtimeApprovalText(expected)) transaction.refuse()
            val currentRetirement = readRecordedActionApprovalRetirement(transaction, current) ?: transaction.refuse()
            if (currentRetirement.historySequence != retired.historySequence ||
                !sameActionApprovalRetirementEvent(currentRetirement.event, retired.event)) transaction.refuse()
        }
    }
}

/** ALL earlier OP issuances must be exact, unused and permanently retired. Current REQUEST
 * is independent of original business attribution; a supplied predecessor selects only the
 * actual latest native issuance. Kernel preparation separately proves complete OP absence. */
internal suspend fun qualifyActionApprovalIssue(
    transaction: RoomLocalSecurityTransaction,
    principal: LocalEmployeePrincipal,
    command: CommandKey,
    prepared: PreparedLocalApprovalCommand,
    requestorPolicyRevision: Revision,
    currentRequestor: ActionApprovalRequestorAuthority,
    submitted: PayloadDigest,
    retiredApprovalId: DomainId?,
): QualifiedActionApprovalIssueBinding? {
    val requirement = prepared.requirement
    val operation = checkNotNull(requirement.binding?.operationId)
    check(currentRequestor.sessionId == principal.sessionId && currentRequestor.generation == principal.sessionGeneration &&
        currentRequestor.deviceId == principal.deviceId && currentRequestor.admissionRevision == principal.admissionRevision)
    val previous = joinedOperationApprovals(transaction, principal.scope, operation)
    if (previous.isEmpty()) return if (retiredApprovalId == null)
        QualifiedActionApprovalIssueBinding(currentRequestor, null, null, null, null) else null
    val root = previous.first()
    val last = previous.last()
    if (last.value.record.approvalId != retiredApprovalId || previous.any { prior ->
            prior.origin.command != command || prior.value.record.requestorId != principal.actorId ||
                prior.value.record.requirement != requirement || prior.value.issueRequestDigest != submitted ||
                prior.value.record.requestorPolicyRevision != requestorPolicyRevision || prior.value.record.consumedByOperationId != null
        }) return null
    val retirement = readRecordedActionApprovalRetirement(transaction, last.value) ?: return null
    if (retirement.historySequence <= last.origin.historySequence) transaction.refuse()
    last.retirement = retirement
    val priorValues = previous.map { it.value }
    val priorRetirements = previous.map { checkNotNull(it.retirement) }
    val original = originalActionApprovalRequestor(root.value)
    if (prepared.actionApprovalBusinessAttribution != ActionApprovalBusinessAttributionSupport.PRESERVE_ORIGINAL_REQUESTER) {
        // Legacy unsupported producers may continue only the original same-authority v2 path.
        if (previous.any { it.value.hasReplacementOrigin } || original != currentRequestor) return null
        return QualifiedActionApprovalIssueBinding(original, null, root.origin.historySequence,
            last.origin.historySequence, retirement.historySequence, priorValues, priorRetirements)
    }
    return QualifiedActionApprovalIssueBinding(original, actionApprovalReplacementOrigin(root.value, last.value, retirement.event),
        root.origin.historySequence, last.origin.historySequence, retirement.historySequence, priorValues, priorRetirements)
}

/** Consuming attribution comes from actual native-issued images, not an ancestor selector,
 * digest, DTO or revived original principal. Current authority is checked independently by
 * the transaction binding; a v3 grant must itself be the current latest unretired issuance. */
internal suspend fun qualifyActionApprovalBusinessAttribution(transaction: RoomLocalSecurityTransaction,
    value: ProtectedLocalActionApproval): CommandBusinessAttribution? {
    val scope = value.record.requirement.scope as BranchScope
    val previous = joinedOperationApprovals(transaction, scope, checkNotNull(value.record.requirement.binding?.operationId))
    val last = previous.lastOrNull() ?: transaction.refuse()
    if (last.origin.approvalId != value.record.approvalId || runtimeApprovalText(last.value) != runtimeApprovalText(value) ||
        value.record.consumedByOperationId != null || readVerifiedActionApprovalRetirement(transaction, value) != null) return null
    val original = originalActionApprovalRequestor(previous.first().value)
    return CommandBusinessAttribution(original.sessionId, original.generation)
}

private class NativeBoundApproval(val origin: RuntimeApprovalIssuanceOrigin, val value: ProtectedLocalActionApproval) {
    var retirement: RecordedActionApprovalRetirement? = null
}

/** Complete native issuance/row and chronological predecessor join for the selected OP. */
private suspend fun joinedOperationApprovals(transaction: RoomLocalSecurityTransaction, scope: BranchScope,
    operation: com.ukkera.pos.types.OperationId): List<NativeBoundApproval> {
    val previous = mutableListOf<NativeBoundApproval>()
    for (origin in transaction.actionApprovalIssuanceOrigins()) {
        val value = readVerifiedActionApproval(transaction, scope, origin.approvalId) ?: transaction.refuse()
        if (!value.hasApproverIncarnation || value.issueRequestDigest != origin.submissionDigest ||
            runtimeApprovalText(unconsumedActionApproval(value)) != origin.evidence.approval) transaction.refuse()
        if (value.record.requirement.binding?.operationId == operation) previous += NativeBoundApproval(origin, value)
    }
    val root = previous.firstOrNull() ?: return previous
    if (root.value.hasReplacementOrigin) transaction.refuse()
    previous.forEachIndexed { index, entry ->
        if (!sameActionApprovalBusiness(root.value, entry.value) || entry.origin.command != root.origin.command) transaction.refuse()
        if (index == 0) return@forEachIndexed
        val predecessor = previous[index - 1]
        if (predecessor.value.record.consumedByOperationId != null) transaction.refuse()
        val retired = readRecordedActionApprovalRetirement(transaction, predecessor.value) ?: transaction.refuse()
        predecessor.retirement = retired
        if (predecessor.origin.historySequence >= retired.historySequence || retired.historySequence >= entry.origin.historySequence)
            transaction.refuse()
        if (entry.value.hasReplacementOrigin) {
            if (entry.value.replacementOrigin != actionApprovalReplacementOrigin(root.value, predecessor.value, retired.event)) transaction.refuse()
        } else if (predecessor.value.hasReplacementOrigin) transaction.refuse() // No v3-to-v2 downgrade.
    }
    return previous
}

internal fun originalActionApprovalRequestor(value: ProtectedLocalActionApproval): ActionApprovalRequestorAuthority =
    ActionApprovalRequestorAuthority(value.requestorSessionId, value.requestorGeneration, value.requestorDeviceId,
        value.requestorCredentialRevision, value.requestorAdmissionRevision)

internal fun sameActionApprovalBusiness(first: ProtectedLocalActionApproval, second: ProtectedLocalActionApproval): Boolean =
    first.record.requestorId == second.record.requestorId && first.record.requirement == second.record.requirement &&
        first.record.requestorPolicyRevision == second.record.requestorPolicyRevision && first.issueRequestDigest == second.issueRequestDigest &&
        first.confirmationDigest == second.confirmationDigest && originalActionApprovalRequestor(first) == originalActionApprovalRequestor(second)

internal fun unconsumedActionApproval(value: ProtectedLocalActionApproval): ProtectedLocalActionApproval =
    value.consumed(value.record.copy(revision = Revision(0), consumedByOperationId = null))

/** Pure consistency projection. It supplies no native origin or retirement authority. */
internal fun actionApprovalReplacementOrigin(root: ProtectedLocalActionApproval, predecessor: ProtectedLocalActionApproval,
    retirement: LocalAuthEvent): ActionApprovalReplacementOrigin {
    requireActionApprovalRetirementEvent(predecessor, retirement)
    val bytes = LocalAuthenticationCodec.encodeEvent(retirement)
    return try { ActionApprovalReplacementOrigin(root.record.approvalId, runtimeApprovalDigest(unconsumedActionApproval(root)),
        predecessor.record.approvalId, runtimeApprovalDigest(predecessor), retirement.eventId, sha256(bytes)) }
    finally { bytes.fill(0) }
}
