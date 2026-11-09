package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalActionApprovalPhase
import com.ukkera.pos.app.LocalActionApprovalRefusal
import com.ukkera.pos.app.LocalActionApprovalResult
import com.ukkera.pos.app.LocalArrivalApprovalModeV2
import com.ukkera.pos.app.LocalArrivalRuleOptionV2
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.database.LocalActionApprovalDenial
import com.ukkera.pos.database.LocalActionApprovalIssueResult
import com.ukkera.pos.database.LocalActionApprovalPreviewResult
import com.ukkera.pos.database.LocalActionApprovalStatus
import com.ukkera.pos.database.LocalActionApprovalRetireResult
import com.ukkera.pos.database.LocalMasterApprovalClient
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.inventory.store.LocalArrivalActionV1
import com.ukkera.pos.inventory.store.LocalArrivalEvidenceSelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalSourceKindV1
import com.ukkera.pos.inventory.store.LocalArrivalSourceQueryV2
import com.ukkera.pos.inventory.store.LocalArrivalQueryV1
import com.ukkera.pos.inventory.store.LocalArrivalAvailabilityV1
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalCommandsV2
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalProfileV2
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.sync.withLock

/** Shared client ceremony over the separately bound real Master and the existing durable journal. */
internal class LocalActionApprovalWorkflow(
    private val endpoint: LocalMasterApprovalClient?,
    private val journal: LocalClientIntentJournal,
) {
    suspend fun preview(access: GatewayAccess, entry: StoredClientIntentV1): LocalActionApprovalResult {
        val ceremony = requiredCeremony(entry)
        if (ceremony.localPhase !in setOf(LocalActionApprovalPhase.PREVIEW_REQUIRED,
                LocalActionApprovalPhase.READY_TO_CONFIRM, LocalActionApprovalPhase.REFUSED))
            return lookup(access, entry)
        requireUnperformed(access, entry)
        val client = client()
        access.checkCurrent()
        val value = when (val result = client.previewActionApproval(access.lease.credentials, entry.unapprovedRequest(),
            ceremony.retiredApprovals.lastOrNull()?.approvalId)) {
            is LocalActionApprovalPreviewResult.Ready -> result.preview
            LocalActionApprovalPreviewResult.Denied -> throw GatewayFailure(LocalServiceProblem.DENIED)
            LocalActionApprovalPreviewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalActionApprovalPreviewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            LocalActionApprovalPreviewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        access.checkCurrent()
        val captured = StoredClientActionApprovalPreviewV1.capture(entry, value)
        if (ceremony.preview != null && ceremony.preview != captured) throw GatewayFailure(LocalServiceProblem.DENIED)
        val next = entry.copy(actionApproval = ceremony.copy(phase = LocalActionApprovalPhase.READY_TO_CONFIRM.name,
            preview = captured, refusal = null))
        persist(entry, next)
        access.checkCurrent()
        return available(access, next)
    }

    suspend fun issue(access: GatewayAccess, entry: StoredClientIntentV1, confirmation: PayloadDigest,
        login: LocalLoginIdentifier, password: CharArray): LocalActionApprovalResult {
        val ceremony = requiredCeremony(entry)
        // Repeated taps/late callers never issue again while an original attempt may still commit.
        if (ceremony.localPhase != LocalActionApprovalPhase.READY_TO_CONFIRM) {
            if (ceremony.localPhase == LocalActionApprovalPhase.PREVIEW_REQUIRED || ceremony.localPhase == LocalActionApprovalPhase.REFUSED)
                throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            return lookup(access, entry)
        }
        val preview = checkNotNull(ceremony.preview)
        if (preview.confirmationDigest != confirmation) throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
        requireUnperformed(access, entry)
        val client = client()
        // The original ID and full confirmed preview become durable BEFORE handing secrets to
        // the actual Master. Losing this response cannot create a new approval identity.
        val prepared = entry.copy(actionApproval = ceremony.copy(phase = LocalActionApprovalPhase.ISSUE_UNRESOLVED.name,
            approvalId = ceremony.approvalId ?: DomainId.random(), refusal = null))
        persist(entry, prepared)
        access.checkCurrent()
        val result = client.issueActionApproval(checkNotNull(prepared.actionApproval?.approvalId), access.lease.credentials,
            prepared.unapprovedRequest(), confirmation, login, password, ceremony.retiredApprovals.lastOrNull()?.approvalId)
        val next = when (result) {
            is LocalActionApprovalIssueResult.Issued -> attach(prepared, result.receipt)
            is LocalActionApprovalIssueResult.Denied -> prepared.copy(actionApproval = checkNotNull(prepared.actionApproval).copy(
                phase = LocalActionApprovalPhase.REFUSED.name, refusal = when (result.reason) {
                    LocalActionApprovalDenial.AUTHORIZATION_REFUSED -> LocalActionApprovalRefusal.AUTHORIZATION_REFUSED
                    LocalActionApprovalDenial.THROTTLED -> LocalActionApprovalRefusal.THROTTLED
                    LocalActionApprovalDenial.CONFIRMATION_CHANGED -> LocalActionApprovalRefusal.CONFIRMATION_CHANGED
                }.name))
            LocalActionApprovalIssueResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalActionApprovalIssueResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            LocalActionApprovalIssueResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        // A display/session retirement cannot erase an already observed issuance or refusal.
        persist(prepared, next)
        access.checkCurrent()
        return available(access, next)
    }

    suspend fun retire(access: GatewayAccess, entry: StoredClientIntentV1): LocalActionApprovalResult {
        val prepared = journal.requests.withLock {
            // Another explicit caller may have queued against the earlier presentation. Read
            // the exact durable state under the shared handoff lock before allocating anything.
            val current = when (val saved = journal.find(entry.operationId)) {
                is JournalRead.Value -> saved.value ?: throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                JournalRead.Unavailable -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            }
            val ceremony = requiredCeremony(current)
            // A queued tap against an older state is status lookup, not a fresh retry
            // merely because another invocation completed while this caller waited.
            if (current != entry) return lookup(access, current)
            // There is no cancellation of an unknown issuance or an unknown business attempt.
            // An unresolved retirement is lookup-only. Only a received, durably matched
            // no-admission completion may offer another SEPARATE explicit retirement request.
            if (ceremony.localPhase !in setOf(LocalActionApprovalPhase.ATTACHED,
                    LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED)) return lookup(access, current)
            if (ceremony.latestRetirementSequence == Long.MAX_VALUE) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            requireUnperformed(access, current)
            client() // Refuse missing production composition before persisting an invocation.
            val next = current.copy(actionApproval = ceremony.beginRetirement(DomainId.random()))
            // Persist the fresh ID/monotone sequence and exact original binding BEFORE this
            // one dispatch. A lost reply or cancellation never reconstructs a retry disposition.
            persist(current, next)
            next
        }
        val pending = checkNotNull(prepared.actionApproval)
        val attempt = checkNotNull(pending.retirementAttempt).boundAttempt(prepared)
        access.checkCurrent()
        val client = client()
        // Do not hold the interactive handoff lock over the remote response. Protected
        // status lookup may observe this saved unresolved invocation; it cannot redispatch.
        val result = client.retireActionApprovalAttempt(attempt, access.lease.credentials, prepared.unapprovedRequest())
        val next = when (result) {
            is LocalActionApprovalRetireResult.Retired -> retireReceipt(prepared, result.receipt, result.retirement)
            is LocalActionApprovalRetireResult.Consumed -> attach(prepared, result.receipt).also {
                if (it.actionApproval?.localPhase != LocalActionApprovalPhase.CONSUMED)
                    throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            }
            is LocalActionApprovalRetireResult.NotAdmitted -> {
                // Direct returned-response ownership, not a tuple-based replay fence. A
                // copied/reconstructed response cannot release this suspended invocation.
                if (result.attempt !== attempt) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                prepared.copy(actionApproval = pending.withNoAdmission(prepared, result.attempt, result.reason))
            }
            LocalActionApprovalRetireResult.Denied -> throw GatewayFailure(LocalServiceProblem.DENIED)
            LocalActionApprovalRetireResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalActionApprovalRetireResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            LocalActionApprovalRetireResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        // Retain witnessed terminal evidence or this exact received completion even if the
        // presentation retired. A failed write leaves the saved request unresolved, not retryable.
        journal.requests.withLock { persist(prepared, next) }
        access.checkCurrent()
        return available(access, next)
    }

    suspend fun beginReplacement(access: GatewayAccess, entry: StoredClientIntentV1): LocalActionApprovalResult {
        val ceremony = requiredCeremony(entry)
        // An interrupted/repeated start recovers the current ceremony, not another reset.
        if (ceremony.localPhase != LocalActionApprovalPhase.RETIRED) return lookup(access, entry)
        if (ceremony.retiredApprovals.size >= 16) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
        requireUnperformed(access, entry)
        // Reauthenticate the exact retirement at the Master; a saved history row is not authority.
        val current = reconcile(access, entry)
        if (current.actionApproval?.localPhase != LocalActionApprovalPhase.RETIRED)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        requireUnperformed(access, current)
        val next = current.copy(actionApprovalId = null, actionApproval = checkNotNull(current.actionApproval).replacement())
        persist(current, next)
        access.checkCurrent()
        // This only opens a new preview. New authentication, issuance and submission are separate.
        return available(access, next)
    }

    suspend fun lookup(access: GatewayAccess, entry: StoredClientIntentV1): LocalActionApprovalResult {
        val next = reconcile(access, entry)
        return available(access, next)
    }

    /** Historical lookup returns original coordinates; it neither reissues nor renews expiry. */
    suspend fun reconcile(access: GatewayAccess, entry: StoredClientIntentV1): StoredClientIntentV1 {
        val ceremony = requiredCeremony(entry)
        val approvalId = ceremony.approvalId ?: return entry
        val preview = checkNotNull(ceremony.preview)
        access.checkCurrent()
        val result = client().lookupActionApproval(approvalId, access.lease.credentials, preview.confirmationDigest)
        val next = when (result) {
            is LocalActionApprovalStatus.Recorded -> {
                if (ceremony.localPhase == LocalActionApprovalPhase.RETIRED)
                    throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                attach(entry, result.receipt)
            }
            is LocalActionApprovalStatus.Retired -> retireReceipt(entry, result.receipt, result.retirement)
            LocalActionApprovalStatus.NotRecorded -> {
                // The original may still be running. An issued record disappearing is unknown
                // history/storage, never conclusive expiry/revocation or a replacement ceremony.
                if (ceremony.receipt != null) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
                entry
            }
            LocalActionApprovalStatus.Denied -> throw GatewayFailure(LocalServiceProblem.DENIED)
            LocalActionApprovalStatus.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            LocalActionApprovalStatus.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
            LocalActionApprovalStatus.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
        persist(entry, next)
        access.checkCurrent()
        return next
    }

    private fun attach(entry: StoredClientIntentV1, receipt: com.ukkera.pos.database.LocalActionApprovalReceipt): StoredClientIntentV1 {
        val ceremony = checkNotNull(entry.actionApproval).withReceipt(entry, receipt)
        return entry.copy(actionApprovalId = checkNotNull(ceremony.approvalId), actionApproval = ceremony).also { it.validate(journal.owner) }
    }

    private fun retireReceipt(entry: StoredClientIntentV1, receipt: com.ukkera.pos.database.LocalActionApprovalReceipt,
        retirement: com.ukkera.pos.database.LocalActionApprovalRetirementReceipt): StoredClientIntentV1 {
        val ceremony = checkNotNull(entry.actionApproval).withRetirement(entry, receipt, retirement)
        return entry.copy(actionApprovalId = null, actionApproval = ceremony).also { it.validate(journal.owner) }
    }

    private suspend fun requireUnperformed(access: GatewayAccess, entry: StoredClientIntentV1) {
        if (entry.localPhase != LocalPendingPhase.STAGED || entry.recorded != null || entry.acknowledged)
            throw GatewayFailure(LocalServiceProblem.DENIED)
        if (!access.hasHint(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.REQUEST), unpricedArrivalTargetV2(entry.request())))
            throw GatewayFailure(LocalServiceProblem.DENIED)
        when (val lookup = access.lookup(entry)) {
            is LookupReply.AbsentAtCurrentWriter -> if (lookup.operationId != entry.operationId)
                throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            is LookupReply.Deferred -> throw GatewayFailure(problem(lookup.reason))
            is LookupReply.Found, is LookupReply.IdentityConflict -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        }
    }

    private fun requiredCeremony(entry: StoredClientIntentV1): StoredClientActionApprovalV1 {
        entry.validate(journal.owner)
        return entry.actionApproval ?: throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }

    private fun client(): LocalMasterApprovalClient = endpoint ?: throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)

    private suspend fun persist(previous: StoredClientIntentV1, next: StoredClientIntentV1) {
        if (previous == next) return
        if (journal.updateActionApproval(previous, next) != JournalWrite.DURABLE)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    }

    private suspend fun available(access: GatewayAccess, entry: StoredClientIntentV1): LocalActionApprovalResult =
        LocalActionApprovalResult.Available(pendingEntry(entry, access.canContinue(entry)))
}

/** Hints only; the Master rechecks exact REQUEST/APPROVE/PERFORM proofs at the protected boundary. */
internal fun GatewayAccess.arrivalActionHintV2(action: LocalAction, target: DomainId? = null): Boolean =
    action in unpricedArrivalActions && (allow(action, target) || actionApprovalsBound &&
        hasHint(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.REQUEST), target))

internal fun GatewayAccess.arrivalRuleAvailableV2(rule: LocalArrivalRuleOptionV2, target: DomainId? = null): Boolean {
    val route = rule.approvalRoute
    return if (rule.directOnly) allow(rule.action, target) &&
        (route == null || arrivalActorSelectedV2(checkNotNull(route.performers), view.actorId))
    else actionApprovalsBound && route != null && rule.ruleDetails != null && route.mode != LocalArrivalApprovalModeV2.DIRECT &&
        arrivalActorSelectedV2(checkNotNull(route.requesters), view.actorId) &&
        hasHint(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.REQUEST), target)
}

internal fun GatewayAccess.requireArrivalRuleV2(rule: LocalArrivalRuleOptionV2, target: DomainId) {
    if (!arrivalRuleAvailableV2(rule, target)) throw GatewayFailure(LocalServiceProblem.DENIED)
}

/** Unsupported old records are not silently promoted to an approval ceremony. Direct v2 needs
 * its original pinned policy still to select an actual Direct rule, under current performer hints. */
internal suspend fun arrivalContinuationHintV2(access: GatewayAccess, entry: StoredClientIntentV1): Boolean {
    if (!actionMatches(entry.localAction, entry.request())) return false
    val ceremony = entry.actionApproval
    if (ceremony != null) return ceremony.localPhase == LocalActionApprovalPhase.ATTACHED &&
        entry.actionApprovalId == ceremony.approvalId && access.arrivalRuleAvailableV2(ceremony.rule.ui(), unpricedArrivalTargetV2(entry.request()))
    if (entry.actionApprovalId != null || !access.allow(entry.localAction, unpricedArrivalTargetV2(entry.request()))) return false
    val selected = arrivalOriginalSelectionV2(access, entry)
    val cut = arrivalSourceV2(access, LocalArrivalSourceQueryV2(LocalArrivalSourceKindV1.SUPPLIER, selected.supplierId,
        supplierId = selected.supplierId, action = selected.action, skuIds = selected.skuIds,
        documentIds = selected.evidence.documents.map { it.documentId }))
    val purchasing = cut.value.purchasing ?: return false
    if (cut.value.requesterPolicyRevision != selected.requesterPolicyRevision ||
        !arrivalPolicyCurrentV2(purchasing, cut.reply.observedAt) || purchasing.policy != selected.evidence.policy ||
        purchasing.catalog != selected.evidence.catalog || purchasing.documents != selected.evidence.documents) return false
    val rule = purchasing.rules.singleOrNull { it.ruleId == selected.evidence.ruleId } ?: return false
    return rule.directOnly && rule.retention == selected.evidence.retention &&
        access.arrivalRuleAvailableV2(arrivalEvidenceOptionsV2(purchasing, cut.value.requesterPolicyRevision)
            .rules.single { it.ruleId == rule.ruleId }, unpricedArrivalTargetV2(entry.request()))
}

private data class ArrivalOriginalSelectionV2(val supplierId: DomainId, val action: LocalArrivalActionV1,
    val skuIds: List<DomainId>, val requesterPolicyRevision: Revision, val evidence: LocalArrivalEvidenceSelectionV2)

private suspend fun arrivalOriginalSelectionV2(access: GatewayAccess, entry: StoredClientIntentV1): ArrivalOriginalSelectionV2 {
    suspend fun transitionSupplier(arrivalId: DomainId): DomainId {
        // The transition has no supplier override in its canonical body. The protected original
        // target supplies its immutable supplier; missing data never borrows a UI summary.
        val cut = arrivalReadV2(access, LocalArrivalQueryV1(arrivalId))
        if (cut.value.availability != LocalArrivalAvailabilityV1.AVAILABLE) unavailable()
        return cut.value.detail?.header?.supplierId ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
    }
    val request = entry.request()
    return when (request.command) {
        LocalUnpricedArrivalProfileV2.RECORD -> LocalUnpricedArrivalCommandsV2.decodeRecordRequest(request.payload).let {
            ArrivalOriginalSelectionV2(it.supplierId, LocalArrivalActionV1.RECORD, it.items.map { item -> item.skuId },
                it.expectedRequesterPolicyRevision, it.evidence) }
        LocalUnpricedArrivalProfileV2.QUALIFY -> LocalUnpricedArrivalCommandsV2.decodeQualifyRequest(request.payload).let {
            ArrivalOriginalSelectionV2(transitionSupplier(it.arrivalId), LocalArrivalActionV1.QUALIFY, listOf(it.item.skuId),
                it.expectedRequesterPolicyRevision, it.evidence) }
        LocalUnpricedArrivalProfileV2.REJECT -> LocalUnpricedArrivalCommandsV2.decodeRejectRequest(request.payload).let {
            ArrivalOriginalSelectionV2(transitionSupplier(it.arrivalId), LocalArrivalActionV1.REJECT, listOf(it.item.skuId),
                it.expectedRequesterPolicyRevision, it.evidence) }
        LocalUnpricedArrivalProfileV2.DISPATCH -> LocalUnpricedArrivalCommandsV2.decodeDispatchRequest(request.payload).let {
            ArrivalOriginalSelectionV2(transitionSupplier(it.arrivalId), LocalArrivalActionV1.DISPATCH, listOf(it.item.skuId),
                it.expectedRequesterPolicyRevision, it.evidence) }
        else -> error("client.arrival.command.unsupported")
    }
}
