package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalActionApprovalPhase
import com.ukkera.pos.app.LocalActionApprovalRefusal
import com.ukkera.pos.app.LocalActionApprovalReview
import com.ukkera.pos.app.LocalActionApprovalState
import com.ukkera.pos.app.LocalActionApprovalRetirement
import com.ukkera.pos.app.LocalActionApprovalRetirementAttempt
import com.ukkera.pos.app.LocalActionApprovalRetirementNoAdmissionReason
import com.ukkera.pos.app.LocalRetiredActionApproval
import com.ukkera.pos.app.LocalArrivalActorSelectionV2
import com.ukkera.pos.app.LocalArrivalActorSelectionModeV2
import com.ukkera.pos.app.LocalArrivalApprovalModeV2
import com.ukkera.pos.app.LocalArrivalApprovalRouteV2
import com.ukkera.pos.app.LocalArrivalRetentionCoordinateV2
import com.ukkera.pos.app.LocalArrivalRuleOptionV2
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.database.LocalActionApprovalPreview
import com.ukkera.pos.database.LocalActionApprovalReceipt
import com.ukkera.pos.database.LocalActionApprovalRetirementReceipt
import com.ukkera.pos.database.LocalActionApprovalNoAdmissionReason
import com.ukkera.pos.database.LocalActionApprovalRetirementAttempt as MasterActionApprovalRetirementAttempt
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalCommandsV2
import com.ukkera.pos.inventory.store.LocalArrivalEvidenceSelectionV2
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalProfileV2
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

/** Documentary metadata only. The actual Master still loads the signed rule and current grants. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredClientArrivalApprovalRuleV1(
    val ruleId: DomainId,
    val action: String,
    val minimumDocuments: Int,
    val maximumDocuments: Int,
    val media: List<PurchasingEvidenceMediaV1>,
    val purposes: List<PurchasingEvidencePurposeV1>,
    val retention: LocalArrivalRetentionCoordinateV2,
    val approvalRoute: LocalArrivalApprovalRouteV2,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val ruleDetails: String? = null,
) {
    fun ui(): LocalArrivalRuleOptionV2 = LocalArrivalRuleOptionV2(ruleId, LocalAction.valueOf(action), false,
        minimumDocuments, maximumDocuments, media, purposes, retention, approvalRoute, ruleDetails)

    companion object {
        fun capture(rule: LocalArrivalRuleOptionV2): StoredClientArrivalApprovalRuleV1 {
            require(!rule.directOnly)
            val route = checkNotNull(rule.approvalRoute)
            require(route.mode != LocalArrivalApprovalModeV2.DIRECT)
            require(rule.ruleDetails != null)
            return StoredClientArrivalApprovalRuleV1(rule.ruleId, rule.action.name, rule.minimumDocuments,
                rule.maximumDocuments, rule.media, rule.purposes, rule.retention, route, rule.ruleDetails).also { it.ui() }
        }
    }
}

/** Exact display requirement/digest from the real preview; no grant or reusable proof is stored. */
@Serializable
internal data class StoredClientActionApprovalPreviewV1(
    val requestorId: DomainId,
    val requestorPolicyRevision: Revision,
    val scope: BranchScope,
    val documentId: DomainId,
    val documentRevision: Revision,
    val payloadDigest: PayloadDigest,
    val operationId: OperationId,
    val reasonCode: String,
    val confirmationDigest: PayloadDigest,
) {
    fun requirement(): AuthorizationRequirement = AuthorizationRequirement(
        Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), scope,
        ActionBinding(documentId, documentRevision, payloadDigest, operationId, reasonCode))

    fun validate(entry: StoredClientIntentV1) {
        require(requestorId == entry.owner.actorId && scope == entry.owner.scope && operationId == entry.operationId)
        val original = arrivalApprovalOriginal(entry)
        require(requestorPolicyRevision == original.requesterPolicyRevision && requirement() == original.requirement)
    }

    companion object {
        fun capture(entry: StoredClientIntentV1, value: LocalActionApprovalPreview): StoredClientActionApprovalPreviewV1 {
            val requirement = value.requirement
            require(requirement == arrivalApprovalOriginal(entry).requirement)
            val binding = checkNotNull(requirement.binding)
            return StoredClientActionApprovalPreviewV1(value.requestorId, value.requestorPolicyRevision,
                requirement.scope as BranchScope, binding.documentId, binding.revision, binding.payloadDigest,
                checkNotNull(binding.operationId), binding.reasonCode, value.confirmationDigest).also { it.validate(entry) }
        }
    }
}

/** Immutable issuance coordinates and a monotone observed one-use status, never bearer authority. */
@Serializable
internal data class StoredClientActionApprovalReceiptV1(
    val approverId: DomainId,
    val issuedAt: MonotonicMoment,
    val expiresAt: MonotonicMoment,
    val consumedByOperationId: OperationId?,
) {
    fun validate(entry: StoredClientIntentV1, rule: StoredClientArrivalApprovalRuleV1) {
        require(issuedAt.bootId == expiresAt.bootId && issuedAt.elapsedMillis < expiresAt.elapsedMillis)
        require(consumedByOperationId == null || consumedByOperationId == entry.operationId)
        val route = rule.approvalRoute
        require(arrivalActorSelectedV2(checkNotNull(route.requesters), entry.owner.actorId))
        require(arrivalActorSelectedV2(checkNotNull(route.approvers), approverId))
        require(approverId != entry.owner.actorId || route.mode == LocalArrivalApprovalModeV2.NAMED_SELF &&
            approverId in route.namedEmployeeIds)
    }

    companion object {
        fun capture(entry: StoredClientIntentV1, value: LocalActionApprovalReceipt): StoredClientActionApprovalReceiptV1 {
            val ceremony = checkNotNull(entry.actionApproval)
            val preview = checkNotNull(ceremony.preview)
            require(value.approvalId == ceremony.approvalId && value.requestorId == preview.requestorId &&
                value.requirement == preview.requirement() && value.confirmationDigest == preview.confirmationDigest)
            return StoredClientActionApprovalReceiptV1(value.approverId, value.issuedAt, value.expiresAt,
                value.consumedByOperationId).also { it.validate(entry, ceremony.rule) }
        }
    }
}

/** A display-safe copy of the conclusive Master tombstone, not native authority. */
@Serializable
internal data class StoredClientActionApprovalRetirementV1(
    val retirementId: DomainId,
    val originalApprovalDigest: PayloadDigest,
    val retiredAt: MonotonicMoment,
) {
    fun ui(): LocalActionApprovalRetirement = LocalActionApprovalRetirement(retirementId, originalApprovalDigest, retiredAt)

    companion object {
        fun capture(entry: StoredClientIntentV1, value: LocalActionApprovalRetirementReceipt): StoredClientActionApprovalRetirementV1 {
            val ceremony = checkNotNull(entry.actionApproval)
            val preview = checkNotNull(ceremony.preview)
            require(value.approvalId == ceremony.approvalId && value.operationId == entry.operationId &&
                value.requestorId == entry.owner.actorId && value.confirmationDigest == preview.confirmationDigest)
            return StoredClientActionApprovalRetirementV1(value.retirementId, value.originalApprovalDigest, value.retiredAt)
        }
    }
}

/** A durable invocation identity only. Full binding is derived from the unchanged original;
 * an absent old field never supplies a pending identity or a completed no-admission result. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredClientActionApprovalRetirementAttemptV1(
    val version: Int = 1,
    val attemptId: DomainId,
    val sequence: Long,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val noAdmissionReason: String? = null,
) {
    fun validate() {
        require(version == 1 && sequence > 0)
        noAdmissionReason?.let(LocalActionApprovalRetirementNoAdmissionReason::valueOf)
    }

    fun boundAttempt(entry: StoredClientIntentV1): MasterActionApprovalRetirementAttempt {
        validate()
        val ceremony = checkNotNull(entry.actionApproval)
        require(ceremony.retirementAttempt == this)
        return MasterActionApprovalRetirementAttempt.capture(attemptId, sequence, checkNotNull(ceremony.approvalId),
            entry.unapprovedRequest(), checkNotNull(ceremony.preview).confirmationDigest)
    }

    fun ui(): LocalActionApprovalRetirementAttempt {
        validate()
        return LocalActionApprovalRetirementAttempt(attemptId, sequence,
            noAdmissionReason?.let(LocalActionApprovalRetirementNoAdmissionReason::valueOf))
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredClientRetiredActionApprovalV1(
    val approvalId: DomainId,
    val preview: StoredClientActionApprovalPreviewV1,
    val receipt: StoredClientActionApprovalReceiptV1,
    val retirement: StoredClientActionApprovalRetirementV1,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val retirementAttempt: StoredClientActionApprovalRetirementAttemptV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val completedRetirementAttempts: List<StoredClientActionApprovalRetirementAttemptV1> = emptyList(),
) {
    fun validate(entry: StoredClientIntentV1, rule: StoredClientArrivalApprovalRuleV1) {
        preview.validate(entry)
        receipt.validate(entry, rule)
        require(receipt.consumedByOperationId == null)
        retirementAttempt?.validate()
        completedRetirementAttempts.forEach { it.validate(); require(it.noAdmissionReason != null) }
        require(completedRetirementAttempts.isEmpty() || retirementAttempt != null)
    }

    fun ui(): LocalRetiredActionApproval = LocalRetiredActionApproval(approvalId, receipt.approverId,
        receipt.issuedAt, receipt.expiresAt, preview.confirmationDigest, retirement.ui(), retirementAttempt?.ui(),
        completedRetirementAttempts.map { it.ui() })
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredClientActionApprovalV1(
    val version: Int = 1,
    val rule: StoredClientArrivalApprovalRuleV1,
    val phase: String = LocalActionApprovalPhase.PREVIEW_REQUIRED.name,
    val preview: StoredClientActionApprovalPreviewV1? = null,
    val approvalId: DomainId? = null,
    val receipt: StoredClientActionApprovalReceiptV1? = null,
    val refusal: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val retirement: StoredClientActionApprovalRetirementV1? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val retiredApprovals: List<StoredClientRetiredActionApprovalV1> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val retirementAttempt: StoredClientActionApprovalRetirementAttemptV1? = null,
    /** Append-only received completions; an explicit retry never erases its predecessor. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val completedRetirementAttempts: List<StoredClientActionApprovalRetirementAttemptV1> = emptyList(),
) {
    val localPhase: LocalActionApprovalPhase get() = LocalActionApprovalPhase.valueOf(phase)

    fun original(): StoredClientActionApprovalV1 = StoredClientActionApprovalV1(rule = rule)

    fun validate(entry: StoredClientIntentV1) {
        require(version == 1 && entry.formVersion == 2 && entry.localAction in unpricedArrivalActions &&
            entry.request().command in unpricedArrivalKeysV2 && rule.action == entry.action)
        val uiRule = rule.ui()
        require(uiRule.approvalRoute != null && uiRule.approvalRoute?.mode != LocalArrivalApprovalModeV2.DIRECT &&
            uiRule.ruleDetails != null)
        val original = arrivalApprovalOriginal(entry)
        require(original.ruleId == rule.ruleId && original.retention == rule.retention &&
            original.documents.size in rule.minimumDocuments..rule.maximumDocuments &&
            original.documents.all { it.first in rule.media && it.second in rule.purposes })
        require(arrivalActorSelectedV2(checkNotNull(rule.approvalRoute.requesters), entry.owner.actorId))
        preview?.validate(entry)
        require((localPhase == LocalActionApprovalPhase.PREVIEW_REQUIRED) == (preview == null))
        require(localPhase != LocalActionApprovalPhase.PREVIEW_REQUIRED || approvalId == null)
        require(localPhase in setOf(LocalActionApprovalPhase.PREVIEW_REQUIRED, LocalActionApprovalPhase.READY_TO_CONFIRM) ||
            approvalId != null)
        val attached = localPhase in setOf(LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.CONSUMED,
            LocalActionApprovalPhase.RETIRE_UNRESOLVED, LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED,
            LocalActionApprovalPhase.RETIRED)
        require(attached == (receipt != null))
        require((localPhase == LocalActionApprovalPhase.REFUSED) == (refusal != null))
        refusal?.let { LocalActionApprovalRefusal.valueOf(it) }
        require(entry.actionApprovalId == if (attached && localPhase != LocalActionApprovalPhase.RETIRED) approvalId else null)
        require((localPhase == LocalActionApprovalPhase.RETIRED) == (retirement != null))
        require(retiredApprovals.size <= 16 && retiredApprovals.map { it.approvalId }.distinct().size == retiredApprovals.size)
        require(retiredApprovals.none { it.approvalId == approvalId })
        retiredApprovals.forEach { it.validate(entry, rule) }
        retirementAttempt?.validate()
        require(retirementAttempt == null || localPhase in setOf(LocalActionApprovalPhase.RETIRE_UNRESOLVED,
            LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, LocalActionApprovalPhase.CONSUMED, LocalActionApprovalPhase.RETIRED))
        require(localPhase != LocalActionApprovalPhase.RETIRE_UNRESOLVED || retirementAttempt?.noAdmissionReason == null)
        require(localPhase != LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED || retirementAttempt?.noAdmissionReason != null)
        completedRetirementAttempts.forEach { it.validate(); require(it.noAdmissionReason != null) }
        require(completedRetirementAttempts.isEmpty() || retirementAttempt != null)
        val attempts = knownRetirementAttempts
        require(attempts.zipWithNext().all { (before, after) -> before.sequence < after.sequence })
        require(attempts.map { it.attemptId }.distinct().size == attempts.size)
        receipt?.let {
            it.validate(entry, rule)
            require((localPhase == LocalActionApprovalPhase.CONSUMED) == (it.consumedByOperationId != null))
        }
        require(localPhase != LocalActionApprovalPhase.PREVIEW_REQUIRED || entry.localPhase == LocalPendingPhase.STAGED)
        require(localPhase != LocalActionApprovalPhase.READY_TO_CONFIRM || entry.localPhase == LocalPendingPhase.STAGED)
        require(entry.recorded == null || attached)
    }

    fun ui(entry: StoredClientIntentV1): LocalActionApprovalState {
        validate(entry)
        val selected = rule.ui()
        return LocalActionApprovalState(localPhase, selected, approvalId, preview?.let {
            LocalActionApprovalReview(entry.operationId, entry.localAction, it.requestorId, it.requestorPolicyRevision,
                it.requirement(), it.confirmationDigest, selected, entry.summary.map { value -> value.ui() })
        }, receipt?.approverId, receipt?.issuedAt, receipt?.expiresAt,
            refusal?.let(LocalActionApprovalRefusal::valueOf), retirement?.ui(), retiredApprovals.map { it.ui() },
            retirementAttempt?.ui(), completedRetirementAttempts.map { it.ui() })
    }

    /** A recorded original may add its receipt, never change requester, target, expiry or approver. */
    fun withReceipt(entry: StoredClientIntentV1, value: LocalActionApprovalReceipt): StoredClientActionApprovalV1 {
        require(localPhase in setOf(LocalActionApprovalPhase.READY_TO_CONFIRM, LocalActionApprovalPhase.ISSUE_UNRESOLVED, LocalActionApprovalPhase.REFUSED,
            LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.CONSUMED, LocalActionApprovalPhase.RETIRE_UNRESOLVED,
            LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED))
        val captured = StoredClientActionApprovalReceiptV1.capture(entry, value)
        receipt?.let { old ->
            require(old.copy(consumedByOperationId = null) == captured.copy(consumedByOperationId = null))
            require(old.consumedByOperationId == null || old.consumedByOperationId == captured.consumedByOperationId)
        }
        return copy(phase = if (captured.consumedByOperationId != null) LocalActionApprovalPhase.CONSUMED.name
            else if (localPhase in setOf(LocalActionApprovalPhase.RETIRE_UNRESOLVED,
                    LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED)) localPhase.name
            else LocalActionApprovalPhase.ATTACHED.name, receipt = captured, refusal = null)
    }

    fun withRetirement(entry: StoredClientIntentV1, original: LocalActionApprovalReceipt,
        value: LocalActionApprovalRetirementReceipt): StoredClientActionApprovalV1 {
        require(localPhase in setOf(LocalActionApprovalPhase.READY_TO_CONFIRM, LocalActionApprovalPhase.ISSUE_UNRESOLVED,
            LocalActionApprovalPhase.REFUSED, LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.RETIRE_UNRESOLVED,
            LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED, LocalActionApprovalPhase.RETIRED))
        val captured = StoredClientActionApprovalReceiptV1.capture(entry, original)
        require(captured.consumedByOperationId == null && (receipt == null || captured == receipt))
        val retired = StoredClientActionApprovalRetirementV1.capture(entry, value)
        require(retirement == null || retirement == retired)
        return copy(phase = LocalActionApprovalPhase.RETIRED.name, receipt = captured, retirement = retired, refusal = null)
    }

    private val knownRetirementAttempts: List<StoredClientActionApprovalRetirementAttemptV1> get() =
        retiredApprovals.flatMap { it.completedRetirementAttempts + listOfNotNull(it.retirementAttempt) } +
            completedRetirementAttempts + listOfNotNull(retirementAttempt)

    val latestRetirementSequence: Long get() = knownRetirementAttempts.lastOrNull()?.sequence ?: 0L

    /** Only an explicit initial request or a durably completed no-admission cycle may dispatch. */
    fun beginRetirement(attemptId: DomainId): StoredClientActionApprovalV1 {
        require(localPhase in setOf(LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED))
        require(localPhase != LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED || retirementAttempt?.noAdmissionReason != null)
        require(latestRetirementSequence < Long.MAX_VALUE)
        require(knownRetirementAttempts.none { it.attemptId == attemptId })
        return copy(phase = LocalActionApprovalPhase.RETIRE_UNRESOLVED.name,
            retirementAttempt = StoredClientActionApprovalRetirementAttemptV1(attemptId = attemptId,
                sequence = latestRetirementSequence + 1L),
            completedRetirementAttempts = completedRetirementAttempts + listOfNotNull(retirementAttempt))
    }

    /** Receipt ownership is checked by the live caller; this separately checks every durable
     * invocation/original coordinate. No generic error or lookup can call this completion. */
    fun withNoAdmission(entry: StoredClientIntentV1, attempt: MasterActionApprovalRetirementAttempt,
        reason: LocalActionApprovalNoAdmissionReason): StoredClientActionApprovalV1 {
        require(localPhase == LocalActionApprovalPhase.RETIRE_UNRESOLVED)
        val pending = checkNotNull(retirementAttempt)
        require(pending.noAdmissionReason == null && pending.boundAttempt(entry) == attempt)
        return copy(phase = LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED.name,
            retirementAttempt = pending.copy(noAdmissionReason = reason.name))
    }

    /** Preserve the complete old ceremony; never modify the original business request. */
    fun replacement(): StoredClientActionApprovalV1 {
        require(localPhase == LocalActionApprovalPhase.RETIRED && retiredApprovals.size < 16)
        val archived = StoredClientRetiredActionApprovalV1(checkNotNull(approvalId), checkNotNull(preview),
            checkNotNull(receipt), checkNotNull(retirement), retirementAttempt, completedRetirementAttempts)
        return StoredClientActionApprovalV1(rule = rule, retiredApprovals = retiredApprovals + archived)
    }

    companion object {
        fun staged(intent: LocalCommandIntent): StoredClientActionApprovalV1? {
            if (intent.formVersion != 2 || intent.action !in unpricedArrivalActions) return null
            val rule = intent.arrivalEvidence?.rule ?: return null
            return if (rule.directOnly) null else StoredClientActionApprovalV1(rule = StoredClientArrivalApprovalRuleV1.capture(rule))
        }
    }
}

internal fun arrivalActorSelectedV2(selection: LocalArrivalActorSelectionV2, actor: DomainId): Boolean =
    selection.mode == LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES || actor in selection.employeeIds

internal data class ArrivalApprovalOriginalV2(
    val requirement: AuthorizationRequirement,
    val requesterPolicyRevision: Revision,
    val ruleId: DomainId,
    val retention: LocalArrivalRetentionCoordinateV2,
    val documents: List<Pair<PurchasingEvidenceMediaV1, PurchasingEvidencePurposeV1>>,
)

/** Local equality validation only; this public requirement object never grants PERFORM authority. */
internal fun arrivalApprovalOriginal(entry: StoredClientIntentV1): ArrivalApprovalOriginalV2 {
    val request = entry.request()
    fun original(target: DomainId, revision: Revision, requesterRevision: Revision,
        evidence: LocalArrivalEvidenceSelectionV2, reason: String): ArrivalApprovalOriginalV2 {
        val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM),
            entry.owner.scope, ActionBinding(target, revision, entry.semanticSha256, entry.operationId, reason),
            LimitUse.NotApplicable, OwnerConstraint.Unrestricted)
        return ArrivalApprovalOriginalV2(requirement, requesterRevision, evidence.ruleId,
            LocalArrivalRetentionCoordinateV2(evidence.retention.classId, evidence.retention.revision),
            evidence.documents.map { PurchasingEvidenceMediaV1.valueOf(it.media) to PurchasingEvidencePurposeV1.valueOf(it.purpose) })
    }
    return when (request.command) {
        LocalUnpricedArrivalProfileV2.RECORD -> LocalUnpricedArrivalCommandsV2.decodeRecordRequest(request.payload).let {
            original(it.arrivalId, Revision(0), it.expectedRequesterPolicyRevision, it.evidence, it.reasonCode)
        }
        LocalUnpricedArrivalProfileV2.QUALIFY -> LocalUnpricedArrivalCommandsV2.decodeQualifyRequest(request.payload).let {
            original(it.arrivalId, it.expectedArrivalRevision, it.expectedRequesterPolicyRevision, it.evidence, it.reasonCode)
        }
        LocalUnpricedArrivalProfileV2.REJECT -> LocalUnpricedArrivalCommandsV2.decodeRejectRequest(request.payload).let {
            original(it.arrivalId, it.expectedArrivalRevision, it.expectedRequesterPolicyRevision, it.evidence, it.reasonCode)
        }
        LocalUnpricedArrivalProfileV2.DISPATCH -> LocalUnpricedArrivalCommandsV2.decodeDispatchRequest(request.payload).let {
            original(it.arrivalId, it.expectedArrivalRevision, it.expectedRequesterPolicyRevision, it.evidence, it.reasonCode)
        }
        else -> error("client.approval.command.unsupported")
    }
}

internal fun StoredClientIntentV1.unapprovedRequest(): MutationRequest = request().let {
    MutationRequest(it.operationId, it.command, it.payload)
}

/** Exactly one allowed metadata advancement; all original/business fields must remain unchanged. */
internal fun validClientActionApprovalTransition(previous: StoredClientIntentV1, next: StoredClientIntentV1): Boolean {
    previous.validate(previous.owner); next.validate(previous.owner)
    if (!previous.sameIntent(next) || previous.localPhase != next.localPhase || previous.recorded != next.recorded ||
        previous.acknowledged != next.acknowledged || previous.acknowledged) return false
    val before = previous.actionApproval ?: return false
    val after = next.actionApproval ?: return false
    if (before == after) return previous.actionApprovalId == next.actionApprovalId
    if (before.rule != after.rule) return false
    if (before.localPhase == LocalActionApprovalPhase.RETIRED &&
        after.localPhase == LocalActionApprovalPhase.PREVIEW_REQUIRED) {
        // Only the exact append-only retired original can make room for another ceremony.
        return previous.localPhase == LocalPendingPhase.STAGED && previous.recorded == null &&
            before.retiredApprovals.size < 16 && after == before.replacement() && next.actionApprovalId == null
    }
    if (before.retiredApprovals != after.retiredApprovals ||
        before.preview != null && before.preview != after.preview ||
        before.approvalId != null && before.approvalId != after.approvalId) return false
    if (before.retirement != null && before.retirement != after.retirement) return false
    if (before.localPhase == LocalActionApprovalPhase.ATTACHED &&
        after.localPhase == LocalActionApprovalPhase.RETIRE_UNRESOLVED &&
        before.retirementAttempt == null && after.retirementAttempt == null) {
        // Legacy callers may add an unresolved hold, never infer an invocation or release it.
        return after == before.copy(phase = LocalActionApprovalPhase.RETIRE_UNRESOLVED.name)
    }
    if (before.localPhase in setOf(LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED) &&
        after.localPhase == LocalActionApprovalPhase.RETIRE_UNRESOLVED) {
        val pending = after.retirementAttempt ?: return false
        if (previous.localPhase != LocalPendingPhase.STAGED || previous.recorded != null ||
            before.latestRetirementSequence == Long.MAX_VALUE || pending.sequence != before.latestRetirementSequence + 1L ||
            pending.noAdmissionReason != null || pending.attemptId == before.retirementAttempt?.attemptId ||
            before.retiredApprovals.any { it.retirementAttempt?.attemptId == pending.attemptId }) return false
        return after == before.beginRetirement(pending.attemptId)
    }
    if (before.localPhase == LocalActionApprovalPhase.RETIRE_UNRESOLVED &&
        after.localPhase == LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED) {
        val pending = before.retirementAttempt ?: return false // Legacy unknown requests are lookup-only.
        val completed = after.retirementAttempt ?: return false
        if (pending.noAdmissionReason != null || completed.noAdmissionReason == null ||
            completed.copy(noAdmissionReason = null) != pending) return false
        return after == before.copy(phase = LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED.name, retirementAttempt = completed)
    }
    // Terminal reads preserve the last invocation, including its received disposition.
    // No same-phase metadata rewrite can replace an unresolved ID/sequence (ABA).
    if (before.retirementAttempt != after.retirementAttempt ||
        before.completedRetirementAttempts != after.completedRetirementAttempts) return false
    return when (before.localPhase) {
        LocalActionApprovalPhase.PREVIEW_REQUIRED -> after.localPhase == LocalActionApprovalPhase.READY_TO_CONFIRM
        LocalActionApprovalPhase.READY_TO_CONFIRM -> after.localPhase == LocalActionApprovalPhase.ISSUE_UNRESOLVED ||
            before.approvalId != null && after.localPhase in setOf(LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.CONSUMED,
                LocalActionApprovalPhase.RETIRED)
        LocalActionApprovalPhase.ISSUE_UNRESOLVED -> after.localPhase in setOf(LocalActionApprovalPhase.ATTACHED,
            LocalActionApprovalPhase.CONSUMED, LocalActionApprovalPhase.REFUSED, LocalActionApprovalPhase.RETIRED)
        LocalActionApprovalPhase.REFUSED -> after.localPhase == LocalActionApprovalPhase.READY_TO_CONFIRM ||
            after.localPhase == LocalActionApprovalPhase.ATTACHED || after.localPhase == LocalActionApprovalPhase.CONSUMED ||
            after.localPhase == LocalActionApprovalPhase.RETIRED
        LocalActionApprovalPhase.ATTACHED, LocalActionApprovalPhase.RETIRE_UNRESOLVED,
        LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED -> after.localPhase in setOf(LocalActionApprovalPhase.CONSUMED,
            LocalActionApprovalPhase.RETIRED) &&
            before.receipt?.copy(consumedByOperationId = null) == after.receipt?.copy(consumedByOperationId = null)
        LocalActionApprovalPhase.CONSUMED, LocalActionApprovalPhase.RETIRED -> false
    }
}
