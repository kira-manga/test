package com.ukkera.pos.app.gateway.setup.independent.unsignedpairingcontinuation

import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.gateway.setup.*
import com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow.FreshWorkflowDocuments
import com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow.FreshWorkflowExecution
import com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow.FreshWorkflowLaunchStorage
import com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow.FreshWorkflowPortGate
import com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow.FreshWorkflowStorage
import com.ukkera.pos.app.setup.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import java.io.IOException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Real common coordinator, immutable UI-intent journal and canonical DATA codecs
 * with controlled application ports. No native factory, real signature, Kit,
 * SQLite, OS document adapter, protected authority or physical restart executes.
 * Port observations survive exceptions caught by the coordinator.
 */
internal class UnsignedPairingContinuationHarness(
    val world: ContinuationWorld,
    val role: LocalSetupFreshDevicePairingRole,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "unsigned-pairing-continuation-test").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val execution = FreshWorkflowExecution(dispatcher)
    val targetStorage = if (isMaster) world.unusedMasterTargetStorage else world.targetStorage
    val masterStorage = if (isMaster) world.masterStorage else world.unusedTargetMasterStorage
    val intents = LocalFreshDevicePairingIntents(targetStorage, masterStorage)
    val documents = FreshWorkflowDocuments()
    val coordinator = LocalSetupCoordinator(ContinuationServices(this),
        LocalEmployeeSetupIntents(ContinuationForbiddenStorage(world)),
        LocalMasterLaunchRecord(if (isMaster) world.masterLaunchStorage else world.targetLaunchStorage), execution,
        beforeRetirement = { _, _ -> world.read("retirement") },
        beforeClientHosting = { world.read("original-client-host") },
        deviceAdmissionIntents = LocalDeviceAdmissionIntents(if (isMaster) world.masterAdmissionStorage else world.targetAdmissionStorage),
        freshDevicePairingIntents = intents, pairingDocuments = documents)
    private var closed = false
    val isMaster get() = role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER
    val context get() = LocalDeviceAdmissionIntents.Context(world.data.installationId, world.data.scope, world.data.masterDeviceId)
    fun view() = checkNotNull(coordinator.state.value.freshDevicePairing)
    fun targetSnapshot() = runCatching { intents.target() }.getOrNull()
    fun masterSnapshot(requestId: DomainId) = runCatching { intents.master(context, requestId) }.getOrNull()
    fun cyclesSnapshot() = targetSnapshot()?.let { intents.responseCycles(it.identity.intentId) }.orEmpty()
    suspend fun idle() { withTimeout(WAIT_MILLIS) { coordinator.state.first { it.work == null } } }
    suspend fun readyTarget() {
        coordinator.chooseFreshDeviceTarget(); idle()
        coordinator.reviewFreshDeviceTarget(world.data.targetInput); idle()
        coordinator.prepareFreshDeviceTarget(view().reviewId); idle()
        coordinator.acquireFreshDeviceSdkIdentity(view().reviewId); idle()
    }
    suspend fun openMaster() {
        coordinator.openOriginalFirstAccount(); idle()
        coordinator.reconnectOriginalLocalClient(); idle()
        coordinator.openFreshDevicePairing(); idle()
        coordinator.beginFreshDevicePairing(); idle()
    }
    suspend fun beginNextMaster() { coordinator.beginFreshDevicePairing(); idle() }
    suspend fun importIdentity(bytes: ByteArray) {
        documents.offer(LocalSetupPairingDocumentKind.SDK_IDENTITY, bytes)
        coordinator.importFreshDeviceIdentity(view().reviewId); idle()
    }
    suspend fun prepareChallenge() { coordinator.prepareFreshDeviceChallenge(view().reviewId); idle() }
    suspend fun importChallenge(bytes: ByteArray) {
        documents.offer(LocalSetupPairingDocumentKind.CHALLENGE, bytes)
        coordinator.importFreshDeviceChallenge(view().reviewId); idle()
    }
    suspend fun respond() { coordinator.respondFreshDeviceChallenge(view().reviewId); idle() }
    suspend fun importPossession(bytes: ByteArray) {
        documents.offer(LocalSetupPairingDocumentKind.POSSESSION, bytes)
        coordinator.importFreshDevicePossession(view().reviewId); idle()
    }
    suspend fun prove() { coordinator.proveFreshDeviceTarget(view().reviewId); idle() }
    suspend fun reviewRetirement() { coordinator.reviewUnsignedDevicePairingRetirement(view().reviewId); idle() }
    suspend fun retire() { coordinator.retireUnsignedDevicePairing(view().reviewId); idle() }
    suspend fun importRetirement(bytes: ByteArray) {
        documents.offer(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT, bytes)
        coordinator.importUnsignedDevicePairingRetirement(view().reviewId); idle()
    }
    suspend fun continueAllocation() { coordinator.continueUnsignedDevicePairing(view().reviewId); idle() }
    suspend fun recover() { coordinator.recoverFreshDevicePairing(view().reviewId); idle() }
    suspend fun export(kind: LocalSetupPairingDocumentKind): ByteArray {
        coordinator.exportFreshDeviceDocument(view().reviewId, kind); idle()
        check(view().document?.outcome == LocalSetupPairingDocumentOutcome.EXPORTED)
        return documents.exports().last { it.kind == kind }.bytes.copyOf()
    }
    suspend fun recreate(requestId: DomainId? = null): UnsignedPairingContinuationHarness {
        cleanup()
        val restored = UnsignedPairingContinuationHarness(world, role)
        if (isMaster) {
            restored.coordinator.openOriginalFirstAccount(); restored.idle()
            restored.coordinator.reconnectOriginalLocalClient(); restored.idle()
            restored.coordinator.openFreshDevicePairing(); restored.idle()
            restored.coordinator.inspectFreshDevicePairing(checkNotNull(requestId)); restored.idle()
        } else {
            restored.coordinator.inspectOriginalFreshDeviceTarget(); restored.idle()
        }
        return restored
    }
    suspend fun cleanup() {
        if (closed) return
        closed = true
        world.releaseGates(); targetStorage.releaseGates(); masterStorage.releaseGates(); documents.releaseGates()
        try {
            withTimeout(WAIT_MILLIS) { coordinator.requestRetirement().await() }
            check(coordinator.state.value.phase == LocalSetupPhase.CLOSED)
        } finally { execution.stopWatching(); dispatcher.close() }
    }
    companion object { const val WAIT_MILLIS = 10_000L }
}

internal enum class ContinuationEffect { TARGET_PREPARE, TARGET_SDK_ACQUIRE, MASTER_PREPARE, TARGET_RESPOND, MASTER_PROVE, MASTER_RETIRE, TARGET_CONTINUE }
internal enum class ContinuationReply { COMPLETE, UNAVAILABLE, CAPACITY, LOST_REPLY, THROW_AFTER_ENTRY, CANCEL_AFTER_ENTRY, FOREIGN }

internal class ContinuationEntry(val kind: ContinuationEffect,
    val target: LocalFreshDevicePairingIntents.TargetRecord? = null,
    val master: LocalFreshDevicePairingIntents.MasterRecord? = null,
    val cycles: List<LocalFreshDevicePairingIntents.ResponseCycle> = emptyList(),
    val actionId: DomainId? = null, val requestId: DomainId? = null,
    val identityBytes: ByteArray? = null, val challengeBytes: ByteArray? = null,
    val possessionBytes: ByteArray? = null, val retirementBytes: ByteArray? = null,
    val actualAction: LocalDevicePairingAction? = null) {
    override fun toString() = "ContinuationEntry([REDACTED])"
}

internal class ContinuationWorld {
    val data = UnsignedPairingContinuationData()
    val targetStorage = FreshWorkflowStorage()
    val masterStorage = FreshWorkflowStorage()
    val unusedTargetMasterStorage = FreshWorkflowStorage()
    val unusedMasterTargetStorage = FreshWorkflowStorage()
    val targetAdmissionStorage = FreshWorkflowStorage()
    val masterAdmissionStorage = FreshWorkflowStorage()
    val targetLaunchStorage = FreshWorkflowLaunchStorage()
    val masterLaunchStorage = FreshWorkflowLaunchStorage()
    val masterMode = MutableStateFlow(LocalMasterLifecycleMode.OPERATIONAL)
    private val monitor = Any()
    private val observations = mutableListOf<ContinuationEntry>()
    private val readObservations = mutableListOf<String>()
    private val forbiddenObservations = mutableListOf<String>()
    private val originals = mutableListOf<ContinuationOriginal>()
    private val possessions = linkedMapOf<DomainId, LocalDevicePairingPossession>()
    private val allocations = linkedMapOf<String, LocalDevicePairingTargetReference>()
    @Volatile var clientCurrent = true
    @Volatile var preparationCompleted = false
    @Volatile var sdkCompleted = false
    @Volatile var allocationIndex = 0
    @Volatile var responseOriginal: ContinuationOriginal? = null
    @Volatile var preparationReply = ContinuationReply.COMPLETE
    @Volatile var masterPreparationReply = ContinuationReply.COMPLETE
    @Volatile var responseReply = ContinuationReply.COMPLETE
    @Volatile var proofReply = ContinuationReply.COMPLETE
    @Volatile var retirementReply = ContinuationReply.COMPLETE
    @Volatile var allocationReply = ContinuationReply.COMPLETE
    @Volatile var masterPreparationGate: FreshWorkflowPortGate? = null
    @Volatile var responseGate: FreshWorkflowPortGate? = null
    @Volatile var proofGate: FreshWorkflowPortGate? = null
    @Volatile var retirementGate: FreshWorkflowPortGate? = null
    @Volatile var allocationGate: FreshWorkflowPortGate? = null
    @Volatile var originalReviewUnavailable = false
    @Volatile var originalListUnavailable = false
    @Volatile var retirementRecoveryUnavailable = false
    @Volatile var allocationRecoveryUnavailable = false
    @Volatile var sdkRecoveryUnavailable = false
    @Volatile var possessionRecoveryUnavailable = false
    @Volatile var targetOriginalUnavailable = false
    @Volatile var recoveredIdentity: LocalDevicePairingSdkIdentityV2? = null
    @Volatile var recoveredPossession: LocalDevicePairingPossession? = null
    @Volatile var recoveredRetirement: LocalDevicePairingUnsignedRetirement? = null
    @Volatile var recoveredAllocation: LocalDevicePairingTargetReference? = null
    @Volatile var referenceOverride: LocalDevicePairingTargetReference? = null
    @Volatile var reviewOverride: LocalDevicePairingAction? = null
    @Volatile var referencesOverride: List<LocalDevicePairingReference>? = null

    fun record(entry: ContinuationEntry) = synchronized(monitor) { observations += entry }
    fun entries(kind: ContinuationEffect? = null) = synchronized(monitor) { observations.filter { kind == null || it.kind == kind }.toList() }
    fun read(kind: String) = synchronized(monitor) { readObservations += kind }
    fun reads() = synchronized(monitor) { readObservations.toList() }
    fun forbidden(kind: String): Nothing {
        synchronized(monitor) { forbiddenObservations += kind }
        throw IllegalStateException("Unrelated controlled application boundary")
    }
    fun forbiddenCalls() = synchronized(monitor) { forbiddenObservations.toList() }
    fun retain(original: ContinuationOriginal) = synchronized(monitor) { originals += original }
    fun originals() = synchronized(monitor) { originals.toList() }
    fun original(index: Int = 0) = originals()[index]
    fun original(actionId: DomainId) = originals().singleOrNull { it.prepared.originalActionId == actionId }
    fun retainPossession(original: ContinuationOriginal) {
        synchronized(monitor) { possessions[original.prepared.originalActionId] = original.possession }
        responseOriginal = original
    }
    fun possession(actionId: DomainId) = synchronized(monitor) { possessions[actionId] }
    fun retainAllocation(retirement: LocalDevicePairingUnsignedRetirement) {
        allocationIndex += 1; responseOriginal = null
        synchronized(monitor) { allocations[retirement.dispositionDigest.value] = data.sdkReference(allocationIndex) }
    }
    fun allocation(retirement: LocalDevicePairingUnsignedRetirement) = synchronized(monitor) { allocations[retirement.dispositionDigest.value] }
    fun allocationCount() = synchronized(monitor) { 1 + allocations.size }
    fun sourceReference(): LocalDevicePairingTargetReference = referenceOverride ?: responseOriginal?.let {
        data.responseReference(it, allocationIndex)
    } ?: if (sdkCompleted) data.sdkReference(allocationIndex) else data.keyReference()
    fun releaseGates() {
        masterPreparationGate?.release(); responseGate?.release(); proofGate?.release()
        retirementGate?.release(); allocationGate?.release()
    }
}

private class ContinuationForbiddenStorage(private val world: ContinuationWorld) : LocalSetupIntentStorage {
    override fun names(): List<String>? = world.forbidden("employee-intent-names")
    override fun read(name: String): ByteArray = world.forbidden("employee-intent-read")
    override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) = world.forbidden("employee-intent-write")
    override fun verify() = world.forbidden("employee-intent-verify")
}

private class ContinuationServices(private val harness: UnsignedPairingContinuationHarness) : LocalSetupNativeServices {
    private val world get() = harness.world
    override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult = world.forbidden("open-provisioner")
    override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult {
        if (!harness.isMaster || preparation) return world.forbidden("installation-target-or-second-master")
        return SelfOwnedNativeOpenResult.Opened(world.data.freshContext())
    }
    override suspend fun prepareFreshDeviceTarget(scope: BranchScope, capabilities: Set<String>): LocalSetupFreshDeviceTargetOpenResult {
        world.record(ContinuationEntry(ContinuationEffect.TARGET_PREPARE, target = harness.targetSnapshot()))
        world.preparationCompleted = true
        return LocalSetupFreshDeviceTargetOpenResult.Opened(ContinuationTarget(harness))
    }
    override suspend fun openOriginalFreshDeviceTarget(): LocalSetupFreshDeviceTargetOpenResult {
        world.read("target-open-existing")
        return if (world.preparationCompleted) LocalSetupFreshDeviceTargetOpenResult.Opened(ContinuationTarget(harness))
        else LocalSetupFreshDeviceTargetOpenResult.Unresolved(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }
    override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult {
        if (!harness.isMaster) return world.forbidden("second-master-open")
        return LocalSetupMasterOpenResult.Opened(ContinuationMaster(context, world))
    }
    override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult = world.forbidden("receipt-route")
    override suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient {
        if (!harness.isMaster || action != LocalOriginalClientJournalAction.REOPEN_EXISTING) return world.forbidden("client-enrollment-or-repair")
        return ContinuationClient(harness)
    }
    override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean = world.forbidden("employee-owner")
    override fun publishClient(owner: Any, client: LocalSetupClient) { world.read("publish-original-client") }
    override fun withdrawClient(owner: Any) { world.read("withdraw-original-client") }
}

private class ContinuationMaster(private val context: SelfOwnedLocalSecurityContext,
    private val world: ContinuationWorld) : LocalSetupMaster {
    override val mode get() = world.masterMode
    override suspend fun permitsFirstClientEnrollment() = false
    override suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning, authorityEpoch: Revision): LocalBranchInstallationSnapshotV2 = world.forbidden("prepare-installation")
    override suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2, headLogin: String,
        headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome = world.forbidden("install-original")
    override suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome = world.forbidden("ack-installation")
    override suspend fun readOriginalSetup() = world.data.originalSetup
    override fun denyNewWork() = Unit
    override suspend fun quiesceAndClose(): Boolean { context.close(); return true }
}

private class ContinuationTarget(private val harness: UnsignedPairingContinuationHarness) : LocalSetupFreshDeviceTarget {
    private val world get() = harness.world
    override suspend fun original(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> {
        world.read("target-original")
        return if (world.targetOriginalUnavailable) unavailable() else LocalSetupFreshDeviceTargetResult.Value(world.sourceReference())
    }
    override suspend fun descriptor(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetDescriptor> {
        world.read("target-descriptor")
        return LocalSetupFreshDeviceTargetResult.Value(world.data.identity.target)
    }
    override suspend fun acquireSdkIdentity(appId: AppId, deviceName: String): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> {
        world.record(ContinuationEntry(ContinuationEffect.TARGET_SDK_ACQUIRE, target = harness.targetSnapshot()))
        world.sdkCompleted = true
        return LocalSetupFreshDeviceTargetResult.Value(world.data.identity)
    }
    override suspend fun recoverSdkIdentity(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> {
        world.read("sdk-recover")
        return if (world.sdkRecoveryUnavailable || !world.sdkCompleted) unavailable()
        else LocalSetupFreshDeviceTargetResult.Value(world.recoveredIdentity ?: world.data.identity)
    }
    override suspend fun respond(challenge: LocalDevicePairingChallenge): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> {
        world.record(ContinuationEntry(ContinuationEffect.TARGET_RESPOND, target = harness.targetSnapshot(),
            cycles = harness.cyclesSnapshot(), actionId = challenge.originalActionId, challengeBytes = challenge.copyWireBytes()))
        world.responseGate?.hold()
        val original = checkNotNull(world.original(challenge.originalActionId))
        if (completes(world.responseReply)) world.retainPossession(original)
        replyFailure(world.responseReply)
        return when (world.responseReply) {
            ContinuationReply.UNAVAILABLE -> unavailable()
            ContinuationReply.CAPACITY -> unavailable(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            ContinuationReply.FOREIGN -> LocalSetupFreshDeviceTargetResult.Value(world.data.original(index = 100, identity = world.data.otherIdentity).possession)
            else -> LocalSetupFreshDeviceTargetResult.Value(original.possession)
        }
    }
    override suspend fun recoverPossession(originalPairingActionId: DomainId): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> {
        world.read("possession-recover:${originalPairingActionId.value}")
        val original = world.possession(originalPairingActionId)
        return if (world.possessionRecoveryUnavailable || original == null) unavailable()
        else LocalSetupFreshDeviceTargetResult.Value(world.recoveredPossession ?: original)
    }
    override suspend fun beginAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> {
        world.record(ContinuationEntry(ContinuationEffect.TARGET_CONTINUE, target = harness.targetSnapshot(),
            cycles = harness.cyclesSnapshot(), actionId = retirement.originalActionId, retirementBytes = retirement.copyWireBytes()))
        world.allocationGate?.hold()
        if (completes(world.allocationReply)) world.retainAllocation(retirement)
        replyFailure(world.allocationReply)
        return when (world.allocationReply) {
            ContinuationReply.UNAVAILABLE -> unavailable()
            ContinuationReply.CAPACITY -> unavailable(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            ContinuationReply.FOREIGN -> LocalSetupFreshDeviceTargetResult.Value(world.data.sdkReference(1).copy(keyId = world.data.otherIdentity.target.keyId))
            else -> LocalSetupFreshDeviceTargetResult.Value(world.sourceReference())
        }
    }
    override suspend fun recoverAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> {
        world.read("allocation-recover:${retirement.dispositionDigest.value}")
        val allocation = world.allocation(retirement)
        return if (world.allocationRecoveryUnavailable || allocation == null || allocation.originalResponseId != world.sourceReference().originalResponseId) unavailable()
        else LocalSetupFreshDeviceTargetResult.Value(world.recoveredAllocation ?: world.sourceReference())
    }
    override fun denyNewWork() = Unit
    override suspend fun close() { world.read("target-owned-close-confirmed") }
    private fun unavailable(reason: SelfOwnedInstallationFailureV1 = SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) = LocalSetupFreshDeviceTargetResult.Unavailable(reason)
}

private class ContinuationClient(private val harness: UnsignedPairingContinuationHarness) : LocalSetupClient {
    private val world get() = harness.world
    private var current = true
    override fun isCurrent() = current && world.clientCurrent
    override fun denyNewWork() { current = false }
    override suspend fun close() { current = false }
    override suspend fun prepareSdkBoundDevicePairing(requestId: DomainId,
        identity: LocalDevicePairingSdkIdentityV2): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        world.record(ContinuationEntry(ContinuationEffect.MASTER_PREPARE, master = harness.masterSnapshot(requestId),
            requestId = requestId, identityBytes = identity.copyWireBytes()))
        world.masterPreparationGate?.hold()
        val existing = world.originals()
        val previous = existing.lastOrNull { it.identity.descriptorDigest == identity.descriptorDigest }?.retirement
        val original = world.data.original(requestId, identity, index = existing.size, previous = previous)
        if (completes(world.masterPreparationReply)) world.retain(original)
        replyFailure(world.masterPreparationReply)
        return if (world.masterPreparationReply == ContinuationReply.UNAVAILABLE || world.masterPreparationReply == ContinuationReply.CAPACITY) unavailable()
        else LocalCompanyAuthorityResult.Value(original.prepared)
    }
    override suspend fun proveDevicePairingTarget(action: LocalDevicePairingAction,
        possession: LocalDevicePairingPossession): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        world.record(ContinuationEntry(ContinuationEffect.MASTER_PROVE, master = harness.masterSnapshot(action.requested.request.requestId),
            actionId = action.originalActionId, requestId = action.requested.request.requestId,
            challengeBytes = action.challenge.copyWireBytes(), possessionBytes = possession.copyWireBytes(), actualAction = action))
        world.proofGate?.hold()
        val original = checkNotNull(world.original(action.originalActionId))
        if (completes(world.proofReply)) original.current = original.proved
        replyFailure(world.proofReply)
        return if (world.proofReply == ContinuationReply.UNAVAILABLE || world.proofReply == ContinuationReply.CAPACITY) unavailable()
        else LocalCompanyAuthorityResult.Value(original.current)
    }
    override suspend fun retainedDevicePairings(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> {
        world.read("master-retained-list")
        return if (world.originalListUnavailable) unavailable()
        else LocalCompanyAuthorityResult.Value(world.referencesOverride ?: world.originals().map { it.current.record.reference() })
    }
    override suspend fun reviewDevicePairing(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        world.read("master-review:${originalActionId.value}")
        if (world.originalReviewUnavailable) return unavailable()
        val original = world.original(originalActionId) ?: return unavailable()
        return LocalCompanyAuthorityResult.Value(world.reviewOverride ?: original.current)
    }
    override suspend fun retireKnownUnsignedDevicePairing(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> {
        val original = checkNotNull(world.original(originalActionId))
        world.record(ContinuationEntry(ContinuationEffect.MASTER_RETIRE, master = harness.masterSnapshot(original.requestId),
            actionId = originalActionId, requestId = original.requestId, actualAction = original.current,
            challengeBytes = original.challenge.copyWireBytes()))
        world.retirementGate?.hold()
        val (terminal, retirement) = world.data.retirement(original, withPossession = original.current.phase != LocalDevicePairingPhase.PREPARED)
        if (completes(world.retirementReply)) { original.current = terminal; original.retirement = retirement }
        replyFailure(world.retirementReply)
        return when (world.retirementReply) {
            ContinuationReply.UNAVAILABLE, ContinuationReply.CAPACITY -> unavailable()
            ContinuationReply.FOREIGN -> LocalCompanyAuthorityResult.Value(world.data.retirement(world.data.original(index = 100, identity = world.data.otherIdentity)).second)
            else -> LocalCompanyAuthorityResult.Value(retirement)
        }
    }
    override suspend fun recoverUnsignedDevicePairingRetirement(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> {
        world.read("retirement-recover:${originalActionId.value}")
        val retirement = world.original(originalActionId)?.retirement
        return if (world.retirementRecoveryUnavailable || retirement == null) unavailable()
        else LocalCompanyAuthorityResult.Value(world.recoveredRetirement ?: retirement)
    }
    override suspend fun approveDevicePairing(action: LocalDevicePairingAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalDevicePairingApproval> = world.forbidden("head-approval")
    override suspend fun recoverDevicePairingApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingApproval> = world.forbidden("head-approval-recovery")
    override suspend fun admitOriginalDevice(approval: LocalDevicePairingApproval): LocalCredentialAdministrationResult = world.forbidden("device-admission")
    override suspend fun lookupOriginalDeviceAdmission(originalActionId: DomainId): LocalProvisioningStatus = world.forbidden("admission-lookup")
    override suspend fun prepareEmployeeEnrollment(requestId: DomainId, definition: LocalAccountProvisioning): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> = world.forbidden("employee-prepare")
    override suspend fun approveEmployeeEnrollment(action: LocalEmployeeEnrollmentAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> = world.forbidden("employee-approve")
    override suspend fun recoverEmployeeEnrollmentApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> = world.forbidden("employee-approval-recover")
    override suspend fun recoverEmployeeEnrollmentConnection(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> = world.forbidden("employee-connection-recover")
    override suspend fun reviewEmployeeEnrollment(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> = world.forbidden("employee-review")
    override suspend fun retainedEmployeeEnrollments(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> = world.forbidden("employee-list")
    override suspend fun enrollApprovedEmployee(originalRequestId: DomainId, password: CharArray): LocalEnrollmentResult = world.forbidden("employee-enroll")
    override suspend fun lookupEmployeeEnrollment(originalRequestId: DomainId): LocalEnrollmentResult = world.forbidden("employee-lookup")
    override suspend fun acquireEmployeeIntentJournal(originalRequestId: DomainId, action: NativeEmployeeClientJournalAction): Boolean = world.forbidden("employee-journal")
    override suspend fun prepareEmployeePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = world.forbidden("employee-policy-prepare")
    override suspend fun approveEmployeePolicy(action: LocalEmployeePolicyActivationAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = world.forbidden("employee-policy-approve")
    override suspend fun reviewEmployeePolicy(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = world.forbidden("employee-policy-review")
    override suspend fun recoverEmployeePolicyApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = world.forbidden("employee-policy-approval-recover")
    override suspend fun recoverEmployeePolicyConnection(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = world.forbidden("employee-policy-connection-recover")
    override suspend fun retainedEmployeePolicies(): LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> = world.forbidden("employee-policy-list")
    override suspend fun activateEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult = world.forbidden("employee-policy-activate")
    override suspend fun lookupEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus = world.forbidden("employee-policy-lookup")
    private fun unavailable() = LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
}

private fun completes(reply: ContinuationReply) = reply in setOf(ContinuationReply.COMPLETE,
    ContinuationReply.LOST_REPLY, ContinuationReply.THROW_AFTER_ENTRY, ContinuationReply.CANCEL_AFTER_ENTRY)
private fun replyFailure(reply: ContinuationReply) {
    when (reply) {
        ContinuationReply.LOST_REPLY -> throw IOException("Controlled reply lost after entry")
        ContinuationReply.THROW_AFTER_ENTRY -> throw IllegalStateException("Controlled failure after entry")
        ContinuationReply.CANCEL_AFTER_ENTRY -> throw CancellationException("Controlled cancellation after entry")
        else -> Unit
    }
}
