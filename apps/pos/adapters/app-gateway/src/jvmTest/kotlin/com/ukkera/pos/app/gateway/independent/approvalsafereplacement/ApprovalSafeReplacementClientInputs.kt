package com.ukkera.pos.app.gateway.independent.approvalsafereplacement

import com.ukkera.pos.app.LocalActionApprovalPhase
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.arrivalapprovalclient.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalProfileV2
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ASR v1 semantic input projection over its unchanged F1 dependency. All approval,
 * retirement and clock coordinates below are CLIENT metadata carriers, not native
 * receipts, expiry facts, authenticated history or permanent-fence authority.
 * No restricted Master approval preview/receipt/retirement receipt is constructed.
 */
internal fun asrApprovalCarrier(index: Int): DomainId =
    DomainId("b1000000-0000-4000-8000-${(index + 1).toString(16).padStart(12, '0')}")

internal fun asrRetirementCarrier(index: Int): DomainId =
    DomainId("b1700000-0000-4000-8000-${(index + 1).toString(16).padStart(12, '0')}")

internal fun asrRetirementUnresolvedData(entry: StoredClientIntentV1): StoredClientIntentV1 =
    entry.copy(actionApproval = checkNotNull(entry.actionApproval).copy(
        phase = LocalActionApprovalPhase.RETIRE_UNRESOLVED.name))

internal fun asrRetiredData(inputs: AawClientJournalInputs, entry: StoredClientIntentV1,
    index: Int): StoredClientIntentV1 = entry.copy(actionApprovalId = null,
    actionApproval = checkNotNull(entry.actionApproval).copy(phase = LocalActionApprovalPhase.RETIRED.name,
        retirement = StoredClientActionApprovalRetirementV1(asrRetirementCarrier(index),
            PayloadDigest("a".repeat(64)), MonotonicMoment(inputs.clockCarrier, index * 3L + 3))))

internal suspend fun asrSaveReady(host: SyntheticArrivalApprovalEncryptedJournal,
    inputs: AawClientJournalInputs, existing: StoredClientIntentV1? = null): StoredClientIntentV1 {
    val staged = existing ?: inputs.stagedData().also {
        assertEquals(JournalWrite.DURABLE, host.journal.stage(it))
    }
    val ready = inputs.readyData(staged)
    assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(staged, ready))
    return ready
}

internal suspend fun asrSaveAttached(host: SyntheticArrivalApprovalEncryptedJournal,
    inputs: AawClientJournalInputs, index: Int = 0,
    existing: StoredClientIntentV1? = null): StoredClientIntentV1 {
    val ready = asrSaveReady(host, inputs, existing)
    val unresolved = inputs.unresolvedData(ready, asrApprovalCarrier(index))
    assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(ready, unresolved))
    val attached = inputs.attachedData(unresolved).let { value -> value.copy(actionApproval =
        checkNotNull(value.actionApproval).copy(receipt = StoredClientActionApprovalReceiptV1(inputs.approver,
            MonotonicMoment(inputs.clockCarrier, index * 3L + 1),
            MonotonicMoment(inputs.clockCarrier, index * 3L + 2), null))) }
    assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(unresolved, attached))
    return attached
}

internal suspend fun asrSaveRetired(host: SyntheticArrivalApprovalEncryptedJournal,
    inputs: AawClientJournalInputs, attached: StoredClientIntentV1, index: Int = 0): StoredClientIntentV1 {
    val unresolved = if (attached.actionApproval?.localPhase == LocalActionApprovalPhase.RETIRE_UNRESOLVED) attached
        else asrRetirementUnresolvedData(attached).also {
            assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(attached, it))
        }
    val retired = asrRetiredData(inputs, unresolved, index)
    assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(unresolved, retired))
    return retired
}

/** Journal boundary only: this is not the gateway's authoritative replacement gate. */
internal suspend fun asrArchiveClientData(host: SyntheticArrivalApprovalEncryptedJournal,
    retired: StoredClientIntentV1): StoredClientIntentV1 {
    val next = retired.copy(actionApprovalId = null,
        actionApproval = checkNotNull(retired.actionApproval).replacement())
    assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(retired, next))
    return next
}

internal data class AsrRetirementCall(val approvalId: DomainId, val request: MutationRequest,
    val confirmation: PayloadDigest, val requestor: LocalGatewayCredentials)

/**
 * Supplied public observations/control data. ACTIVE/current/absence/Found here do
 * not qualify a writer or authentication. Mutations always refuse or stay unknown;
 * no Master/business tables, approval proofs or physical instructions are created.
 */
internal class ControlledAsrClientEndpoint(val base: ControlledAawClientEndpoint) :
    LocalMasterClient by base, LocalMasterApprovalClient by base {
    val retirements = mutableListOf<AsrRetirementCall>()
    val previewPredecessors = mutableListOf<DomainId?>()
    val issuePredecessors = mutableListOf<DomainId?>()
    val events = mutableListOf<String>()
    var retirementReply: LocalActionApprovalRetireResult = LocalActionApprovalRetireResult.StorageUncertain
    var onRetirement: suspend (AsrRetirementCall) -> Unit = {}
    var businessReply: LookupReply? = null

    override suspend fun previewActionApproval(requestor: LocalGatewayCredentials, request: MutationRequest,
        retiredApprovalId: DomainId?): LocalActionApprovalPreviewResult {
        previewPredecessors += retiredApprovalId
        return base.previewActionApproval(requestor, request, retiredApprovalId)
    }

    override suspend fun issueActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest, approverLogin: LocalLoginIdentifier,
        approverPassword: CharArray, retiredApprovalId: DomainId?): LocalActionApprovalIssueResult {
        issuePredecessors += retiredApprovalId
        return base.issueActionApproval(approvalId, requestor, request, confirmationDigest,
            approverLogin, approverPassword, retiredApprovalId)
    }

    override suspend fun retireActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest): LocalActionApprovalRetireResult {
        val call = AsrRetirementCall(approvalId, request, confirmationDigest, requestor)
        events += "retire"
        retirements += call
        onRetirement(call)
        return retirementReply
    }

    override suspend fun retireActionApprovalAttempt(attempt: LocalActionApprovalRetirementAttempt,
        requestor: LocalGatewayCredentials, request: MutationRequest): LocalActionApprovalRetireResult =
        super<LocalMasterApprovalClient>.retireActionApprovalAttempt(attempt, requestor, request)

    override suspend fun lookupActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        confirmationDigest: PayloadDigest): LocalActionApprovalStatus {
        events += "approval-lookup"
        return base.lookupActionApproval(approvalId, requestor, confirmationDigest)
    }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        events += "business-lookup"
        base.businessLookups += identity
        return businessReply ?: base.businessDenial?.let { LookupReply.Deferred(identity.operationId, it) }
            ?: LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }

    fun assertNoBusinessMutationOrAuthentication() {
        assertTrue(base.executions.isEmpty())
        assertEquals(0, base.signIns)
        assertEquals(0, base.refreshes)
        assertEquals(0, base.signOuts)
    }
}

internal fun asrClientGateway(journal: LocalClientIntentJournal, endpoint: ControlledAsrClientEndpoint,
    credentials: AawClientCredentialInputs = AawClientCredentialInputs()): LocalEndpointWorkflowGateway =
    LocalEndpointWorkflowGateway(endpoint, journal, credentials, LocalWorkflowInstallation(
        setOf(LocalUnpricedArrivalProfileV2.RECORD, LocalUnpricedArrivalProfileV2.QUALIFY,
            LocalUnpricedArrivalProfileV2.REJECT, LocalUnpricedArrivalProfileV2.DISPATCH),
        setOf(LocalUnpricedArrivalProfileV2.SOURCE)))

/** Source-level Found response carrier only, never a genuine published business commit. */
internal fun asrFoundBusinessData(inputs: AawClientJournalInputs): LookupReply.Found {
    val data = inputs.terminalData()
    return LookupReply.Found(MutationReply.Recorded(data.operationId, data.originalActorId, data.committed,
        data.code, decodeHex(data.payloadHex, 65_536).useWiped { StoredBytes(it) }, data.authorityEpoch,
        data.sequence, data.recordedAt, replayed = true))
}

internal fun asrSessionView(inputs: AawClientJournalInputs, actor: DomainId = inputs.requester,
    scope: BranchScope = inputs.scope, device: DomainId = inputs.owner.deviceId,
    verbs: List<PermissionVerb> = listOf(PermissionVerb.REQUEST, PermissionVerb.PERFORM),
    denyRequest: Boolean = false): LocalSessionView {
    val original = inputs.sessionView(actor)
    val grants = verbs.map { PermissionGrant(Permission(SecurityAction.INVENTORY_ADJUST, it), scope, GrantEffect.ALLOW) } +
        if (denyRequest) listOf(PermissionGrant(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.REQUEST), scope, GrantEffect.DENY))
        else emptyList()
    return LocalSessionView(actor, scope, device, original.sessionId, original.generation, original.login,
        ActivatedLocalPolicy(scope, actor, original.policy.revision, grants,
            original.policy.sessionPolicy, original.policy.throttlePolicy), original.accessExpiresAt, original.workExpiresAt)
}

/** Controlled write failure before/after a real client metadata write; not a native IO fault claim. */
internal class AsrApprovalWriteFailureJournal(private val delegate: LocalClientIntentJournal,
    private val unknownAfterDurableWrite: Boolean) : LocalClientIntentJournal(delegate.owner) {
    override suspend fun find(operationId: OperationId) = delegate.find(operationId)
    override suspend fun pending() = delegate.pending()
    override suspend fun stage(intent: StoredClientIntentV1) = delegate.stage(intent)
    override suspend fun markDispatched(operationId: OperationId) = delegate.markDispatched(operationId)
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1) = delegate.remember(operationId, result)
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1) = delegate.acknowledge(operationId, result)
    override suspend fun updateActionApproval(previous: StoredClientIntentV1, next: StoredClientIntentV1): JournalWrite {
        if (unknownAfterDurableWrite) assertEquals(JournalWrite.DURABLE, delegate.updateActionApproval(previous, next))
        return JournalWrite.UNAVAILABLE
    }
    override suspend fun close() = delegate.close()
}

internal fun asrAssertOriginalUnchanged(original: StoredClientIntentV1, current: StoredClientIntentV1) {
    assertEquals(original.owner, current.owner)
    assertEquals(original.operationId, current.operationId)
    assertEquals(original.action, current.action)
    assertEquals(original.formVersion, current.formVersion)
    assertEquals(original.inputDigest, current.inputDigest)
    assertEquals(original.commandName, current.commandName)
    assertEquals(original.commandVersion, current.commandVersion)
    assertEquals(original.payloadHex, current.payloadHex)
    assertEquals(original.semanticSha256, current.semanticSha256)
    assertEquals(original.label, current.label)
    assertEquals(original.summary, current.summary)
    assertEquals(assertNotNull(original.actionApproval).rule, assertNotNull(current.actionApproval).rule)
}
