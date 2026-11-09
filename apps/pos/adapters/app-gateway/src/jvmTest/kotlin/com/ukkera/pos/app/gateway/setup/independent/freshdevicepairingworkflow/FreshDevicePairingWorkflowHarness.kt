package com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow

import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.gateway.setup.*
import com.ukkera.pos.app.setup.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Controlled application ports only. The real common coordinator, immutable intent
 * journal and native binary DATA codecs run; no native factory/key, Kit, SQLite,
 * physical restart, permissions, signature verification or OS document adapter runs.
 * Port bodies record observations rather than assertions because the coordinator
 * deliberately catches failed native calls.
 */
internal class FreshDevicePairingWorkflowHarness(
    val world: FreshDevicePairingWorkflowWorld = FreshDevicePairingWorkflowWorld(),
    val role: LocalSetupFreshDevicePairingRole = LocalSetupFreshDevicePairingRole.FRESH_TARGET,
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "fresh-device-pairing-test").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    val execution = FreshWorkflowExecution(dispatcher)
    val targetStorage = if (role == LocalSetupFreshDevicePairingRole.FRESH_TARGET) world.targetStorage else world.unusedMasterTargetStorage
    val masterStorage = if (role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER) world.masterStorage else world.unusedTargetMasterStorage
    private val launchStorage = if (role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER) world.masterLaunchStorage else world.targetLaunchStorage
    private val admissionStorage = if (role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER) world.masterAdmissionStorage else world.targetAdmissionStorage
    val intents = LocalFreshDevicePairingIntents(targetStorage, masterStorage)
    val documents = FreshWorkflowDocuments()
    private val services = FreshWorkflowServices(this)
    val coordinator = LocalSetupCoordinator(services, LocalEmployeeSetupIntents(FreshWorkflowForbiddenStorage(world)),
        LocalMasterLaunchRecord(launchStorage), execution,
        beforeRetirement = { _, _ -> world.recordRead("retirement") },
        beforeClientHosting = { world.recordRead("original-client-host") },
        deviceAdmissionIntents = LocalDeviceAdmissionIntents(admissionStorage),
        freshDevicePairingIntents = intents, pairingDocuments = documents)
    private var closed = false
    private var expectedUnconfirmedRetirement = false
    private var confirmedRetirement = false

    val context get() = LocalDeviceAdmissionIntents.Context(world.data.installationId, world.data.scope, world.data.masterDeviceId)
    fun targetSnapshot(): LocalFreshDevicePairingIntents.TargetRecord? = runCatching { intents.target() }.getOrNull()
    fun masterSnapshot(requestId: DomainId): LocalFreshDevicePairingIntents.MasterRecord? =
        runCatching { intents.master(context, requestId) }.getOrNull()
    fun view(): LocalSetupFreshDevicePairingView = assertNotNull(coordinator.state.value.freshDevicePairing)
    suspend fun idle() { withTimeout(WAIT_MILLIS) { coordinator.state.first { it.work == null } } }
    suspend fun reviewTarget() {
        coordinator.chooseFreshDeviceTarget(); idle()
        coordinator.reviewFreshDeviceTarget(world.data.targetInput); idle()
    }
    suspend fun prepareTarget() { coordinator.prepareFreshDeviceTarget(view().reviewId); idle() }
    suspend fun acquireIdentity() { coordinator.acquireFreshDeviceSdkIdentity(view().reviewId); idle() }
    suspend fun readyTarget() { reviewTarget(); prepareTarget(); acquireIdentity() }
    suspend fun openMaster() {
        coordinator.openOriginalFirstAccount(); idle()
        coordinator.reconnectOriginalLocalClient(); idle()
        coordinator.openFreshDevicePairing(); idle()
        coordinator.beginFreshDevicePairing(); idle()
    }
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
    suspend fun recover() { coordinator.recoverFreshDevicePairing(view().reviewId); idle() }
    suspend fun export(kind: LocalSetupPairingDocumentKind): ByteArray {
        coordinator.exportFreshDeviceDocument(view().reviewId, kind); idle()
        assertEquals(LocalSetupPairingDocumentOutcome.EXPORTED, view().document?.outcome,
            "recorded outgoing DATA alone is not completed export")
        return documents.exports().last { it.kind == kind }.bytes.copyOf()
    }
    suspend fun awaitUnconfirmedRetirement() {
        withTimeout(WAIT_MILLIS) { coordinator.state.first { it.phase == LocalSetupPhase.CLOSING } }
        val failure = runCatching { withTimeout(WAIT_MILLIS) { coordinator.requestRetirement().await() } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException && failure !is CancellationException,
            "actual failed retirement must be observed, not a timeout/cancelled test waiter")
        val retained = coordinator.state.value
        assertEquals(LocalSetupProblem.CLOSE_UNCONFIRMED, retained.problem)
        assertTrue(retained.locked)
        assertFalse(retained.phase == LocalSetupPhase.CLOSED)
        expectedUnconfirmedRetirement = true
    }
    suspend fun recreate(): FreshDevicePairingWorkflowHarness {
        check(!expectedUnconfirmedRetirement) { "An unconfirmed service slot cannot be replaced by synthetic cleanup" }
        cleanup()
        check(confirmedRetirement) { "A replacement service requires actually confirmed controlled retirement" }
        val restored = FreshDevicePairingWorkflowHarness(world, role)
        if (role == LocalSetupFreshDevicePairingRole.FRESH_TARGET) {
            restored.coordinator.inspectOriginalFreshDeviceTarget(); restored.idle()
        } else {
            restored.coordinator.openOriginalFirstAccount(); restored.idle()
            restored.coordinator.reconnectOriginalLocalClient(); restored.idle()
            restored.coordinator.openFreshDevicePairing(); restored.idle()
            val request = checkNotNull(world.original).prepared.requested.request.requestId
            restored.coordinator.inspectFreshDevicePairing(request); restored.idle()
        }
        return restored
    }
    suspend fun cleanup() {
        if (closed) return
        closed = true
        // Release controlled scheduling resources, never change the scripted native outcome
        // or retry a failed close. An explicitly observed failed retirement stays failed.
        world.releaseGates(); targetStorage.releaseGates(); masterStorage.releaseGates(); documents.releaseGates()
        try {
            val failure = runCatching { withTimeout(WAIT_MILLIS) { coordinator.requestRetirement().await() } }.exceptionOrNull()
            if (expectedUnconfirmedRetirement) {
                assertTrue(failure is IllegalStateException && failure !is CancellationException,
                    "expected unresolved synthetic teardown must not become a closure claim")
                assertFalse(coordinator.state.value.phase == LocalSetupPhase.CLOSED)
            } else {
                if (failure != null) throw failure
                assertEquals(LocalSetupPhase.CLOSED, coordinator.state.value.phase)
                confirmedRetirement = true
            }
        } finally { execution.stopWatching(); dispatcher.close() }
    }
    companion object { const val WAIT_MILLIS = 10_000L }
}

internal enum class FreshWorkflowReply { COMPLETE, UNAVAILABLE, CAPACITY, LOST_REPLY, THROW_AFTER_ENTRY, CANCEL_AFTER_ENTRY, FOREIGN, PREPARED_ONLY }
internal enum class FreshWorkflowEffect { TARGET_PREPARE, TARGET_SDK_ACQUIRE, MASTER_PREPARE, TARGET_RESPOND, MASTER_PROVE }

internal class FreshWorkflowEffectEntry(val kind: FreshWorkflowEffect,
    val targetRecord: LocalFreshDevicePairingIntents.TargetRecord?,
    val masterRecord: LocalFreshDevicePairingIntents.MasterRecord?,
    val identityBytes: ByteArray? = null, val challengeBytes: ByteArray? = null,
    val possessionBytes: ByteArray? = null, val actualAction: LocalDevicePairingAction? = null,
    val appId: String? = null, val privateName: String? = null, val requestId: DomainId? = null,
    val requestedScope: BranchScope? = null, val requestedCapabilities: Set<String>? = null) {
    override fun toString(): String = "FreshWorkflowEffectEntry([REDACTED])"
}

internal class FreshDevicePairingWorkflowWorld {
    val data = FreshDevicePairingWorkflowData()
    val targetStorage = FreshWorkflowStorage()
    val masterStorage = FreshWorkflowStorage()
    val unusedTargetMasterStorage = FreshWorkflowStorage()
    val unusedMasterTargetStorage = FreshWorkflowStorage()
    val targetAdmissionStorage = FreshWorkflowStorage()
    val masterAdmissionStorage = FreshWorkflowStorage()
    val targetLaunchStorage = FreshWorkflowLaunchStorage()
    val masterLaunchStorage = FreshWorkflowLaunchStorage()
    private val monitor = Any()
    private val effects = mutableListOf<FreshWorkflowEffectEntry>()
    private val forbidden = mutableListOf<String>()
    private val reads = mutableListOf<String>()
    @Volatile var preparationReply = FreshWorkflowReply.COMPLETE
    @Volatile var acquisitionReply = FreshWorkflowReply.COMPLETE
    @Volatile var masterPreparationReply = FreshWorkflowReply.COMPLETE
    @Volatile var responseReply = FreshWorkflowReply.COMPLETE
    @Volatile var proofReply = FreshWorkflowReply.COMPLETE
    @Volatile var closeReply = FreshWorkflowReply.COMPLETE
    @Volatile var preparationGate: FreshWorkflowPortGate? = null
    @Volatile var acquisitionGate: FreshWorkflowPortGate? = null
    @Volatile var masterPreparationGate: FreshWorkflowPortGate? = null
    @Volatile var responseGate: FreshWorkflowPortGate? = null
    @Volatile var proofGate: FreshWorkflowPortGate? = null
    @Volatile var closeGate: FreshWorkflowPortGate? = null
    @Volatile var preparationCompleted = false
    @Volatile var sdkCompleted = false
    @Volatile var responseCompleted = false
    @Volatile var proofCompleted = false
    @Volatile var ownerCloseUnresolved = false
    @Volatile var original: FreshDevicePairingWorkflowOriginal? = null
    @Volatile var recoveredIdentity: LocalDevicePairingSdkIdentityV2? = null
    @Volatile var recoveredPossession: LocalDevicePairingPossession? = null
    @Volatile var originalReferenceOverride: LocalDevicePairingTargetReference? = null
    @Volatile var originalReviewOverride: LocalDevicePairingAction? = null
    @Volatile var referencesOverride: List<LocalDevicePairingReference>? = null
    @Volatile var originalReviewUnavailable = false
    @Volatile var originalListUnavailable = false
    @Volatile var sdkRecoveryUnavailable = false
    @Volatile var possessionRecoveryUnavailable = false

    fun record(entry: FreshWorkflowEffectEntry) = synchronized(monitor) { effects += entry }
    fun entries(kind: FreshWorkflowEffect? = null): List<FreshWorkflowEffectEntry> = synchronized(monitor) {
        effects.filter { kind == null || it.kind == kind }.toList() }
    fun forbidden(kind: String): Nothing {
        synchronized(monitor) { forbidden += kind }
        throw IllegalStateException("Unrelated controlled application boundary")
    }
    fun forbiddenCalls(): List<String> = synchronized(monitor) { forbidden.toList() }
    fun recordRead(kind: String) = synchronized(monitor) { reads += kind }
    fun reads(): List<String> = synchronized(monitor) { reads.toList() }
    fun releaseGates() {
        preparationGate?.release(); acquisitionGate?.release(); masterPreparationGate?.release()
        responseGate?.release(); proofGate?.release(); closeGate?.release()
    }
    fun sourceReference(): LocalDevicePairingTargetReference = originalReferenceOverride ?: when {
        responseCompleted -> data.responseReference(checkNotNull(original))
        sdkCompleted -> data.sdkReference()
        else -> data.keyReference()
    }
}

internal class FreshWorkflowPortGate {
    val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    suspend fun hold() { entered.complete(Unit); released.await() }
    suspend fun awaitEntry() { withTimeout(FreshDevicePairingWorkflowHarness.WAIT_MILLIS) { entered.await() } }
    fun release() { released.complete(Unit) }
}

internal class FreshWorkflowExecution(override val dispatcher: CoroutineDispatcher) : LocalSetupExecution {
    @Volatile var elapsed = 0L
    @Volatile private var tick: (() -> Unit)? = null
    override fun elapsedMillis(): Long = elapsed
    override fun startWatching(tick: () -> Unit) { this.tick = tick }
    override fun stopWatching() { tick = null }
    fun observeAt(millis: Long) { elapsed = millis; tick?.invoke() }
}

internal class FreshWorkflowStorageGate(val kind: String, val fail: Boolean = false) {
    val entered = CompletableDeferred<Unit>()
    private val released = CountDownLatch(1)
    fun beforeAcknowledgement() {
        entered.complete(Unit)
        check(released.await(FreshDevicePairingWorkflowHarness.WAIT_MILLIS, TimeUnit.MILLISECONDS))
        if (fail) throw IOException("Controlled intent acknowledgement unavailable")
    }
    suspend fun awaitEntry() { withTimeout(FreshDevicePairingWorkflowHarness.WAIT_MILLIS) { entered.await() } }
    fun release() { released.countDown() }
}

/** Byte retention is controlled in-memory storage; it proves no OS force or SQLite commit. */
internal class FreshWorkflowStorage : LocalSetupIntentStorage {
    private val monitor = Any()
    private val files = linkedMapOf<String, ByteArray>()
    @Volatile var gate: FreshWorkflowStorageGate? = null
    @Volatile var readUnavailable = false
    override fun names(): List<String> = synchronized(monitor) { files.keys.toList() }
    override fun read(name: String): ByteArray {
        if (readUnavailable) throw IOException("Controlled intent read unavailable")
        return synchronized(monitor) { checkNotNull(files[name]).copyOf() }
    }
    override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) {
        gate?.takeIf { name.endsWith(".${it.kind}.v1") }?.beforeAcknowledgement()
        synchronized(monitor) {
            val existing = files[name]
            if (existing != null) check(allowExactExisting && existing.contentEquals(bytes))
            else files[name] = bytes.copyOf()
        }
    }
    override fun verify() { if (readUnavailable) throw IOException("Controlled intent verification unavailable") }
    fun snapshot(): Map<String, ByteArray> = synchronized(monitor) { files.mapValues { it.value.copyOf() } }
    fun replace(name: String, bytes: ByteArray) = synchronized(monitor) { files[name] = bytes.copyOf() }
    fun releaseGates() { gate?.release() }
}

internal class FreshWorkflowLaunchStorage : LocalMasterLaunchStorage {
    private var bytes: ByteArray? = null
    @Synchronized override fun read(): ByteArray? = bytes?.copyOf()
    @Synchronized override fun replaceAndForce(bytes: ByteArray) { this.bytes = bytes.copyOf() }
}

internal class FreshWorkflowDocumentEntry(val kind: LocalSetupPairingDocumentKind, val bytes: ByteArray,
    val wasCurrent: Boolean) { override fun toString(): String = "FreshWorkflowDocumentEntry([REDACTED])" }

internal class FreshWorkflowDocuments : LocalSetupPairingDocuments {
    private val monitor = Any()
    private val offered = ArrayDeque<LocalSetupPairingDocumentImportResult>()
    private val imports = mutableListOf<LocalSetupPairingDocumentKind>()
    private val exports = mutableListOf<FreshWorkflowDocumentEntry>()
    @Volatile var importGate: FreshWorkflowPortGate? = null
    @Volatile var exportGate: FreshWorkflowPortGate? = null
    @Volatile var exportReply: LocalSetupPairingDocumentExportResult = LocalSetupPairingDocumentExportResult.Completed
    @Volatile var mutateExportBuffer = false
    fun offer(kind: LocalSetupPairingDocumentKind, bytes: ByteArray) = offer(LocalSetupPairingDocumentImportResult.Completed(kind, bytes))
    fun offer(result: LocalSetupPairingDocumentImportResult) = synchronized(monitor) { offered.addLast(result) }
    override suspend fun importDocument(kind: LocalSetupPairingDocumentKind, isCurrent: () -> Boolean): LocalSetupPairingDocumentImportResult {
        synchronized(monitor) { imports += kind }
        importGate?.hold()
        // Return even stale DATA to verify the coordinator's own callback boundary.
        return synchronized(monitor) { offered.removeFirst() }
    }
    override suspend fun exportDocument(kind: LocalSetupPairingDocumentKind, bytes: ByteArray,
        isCurrent: () -> Boolean): LocalSetupPairingDocumentExportResult {
        synchronized(monitor) { exports += FreshWorkflowDocumentEntry(kind, bytes.copyOf(), isCurrent()) }
        if (mutateExportBuffer) bytes.fill(0)
        exportGate?.hold()
        return exportReply
    }
    fun imports(): List<LocalSetupPairingDocumentKind> = synchronized(monitor) { imports.toList() }
    fun exports(): List<FreshWorkflowDocumentEntry> = synchronized(monitor) { exports.toList() }
    fun releaseGates() { importGate?.release(); exportGate?.release() }
}

private class FreshWorkflowForbiddenStorage(private val world: FreshDevicePairingWorkflowWorld) : LocalSetupIntentStorage {
    override fun names(): List<String>? = world.forbidden("employee-intent-names")
    override fun read(name: String): ByteArray = world.forbidden("employee-intent-read")
    override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean): Unit = world.forbidden("employee-intent-write")
    override fun verify(): Unit = world.forbidden("employee-intent-verify")
}

private class FreshWorkflowServices(private val harness: FreshDevicePairingWorkflowHarness) : LocalSetupNativeServices {
    private val world get() = harness.world
    private val isMaster get() = harness.role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER
    override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult = world.forbidden("open-provisioner")
    override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult {
        if (!isMaster || preparation) return world.forbidden("installation-target-preparation-or-second-master")
        return SelfOwnedNativeOpenResult.Opened(world.data.freshContext())
    }
    override suspend fun prepareFreshDeviceTarget(scope: BranchScope, capabilities: Set<String>): LocalSetupFreshDeviceTargetOpenResult {
        world.record(FreshWorkflowEffectEntry(FreshWorkflowEffect.TARGET_PREPARE, harness.targetSnapshot(), null,
            requestedScope = scope, requestedCapabilities = capabilities.toSet()))
        world.preparationGate?.hold()
        if (world.preparationReply !in setOf(FreshWorkflowReply.UNAVAILABLE, FreshWorkflowReply.CAPACITY)) world.preparationCompleted = true
        effectFailure(world.preparationReply)
        return when (world.preparationReply) {
            FreshWorkflowReply.UNAVAILABLE -> LocalSetupFreshDeviceTargetOpenResult.Unresolved(
                SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, world.data.keyReference())
            FreshWorkflowReply.CAPACITY -> LocalSetupFreshDeviceTargetOpenResult.Unresolved(
                SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            else -> LocalSetupFreshDeviceTargetOpenResult.Opened(FreshWorkflowTarget(harness))
        }
    }
    override suspend fun openOriginalFreshDeviceTarget(): LocalSetupFreshDeviceTargetOpenResult {
        world.recordRead("target-open-existing")
        if (!world.preparationCompleted || world.ownerCloseUnresolved)
            return LocalSetupFreshDeviceTargetOpenResult.Unresolved(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
                if (world.preparationCompleted) world.sourceReference() else null)
        return LocalSetupFreshDeviceTargetOpenResult.Opened(FreshWorkflowTarget(harness))
    }
    override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult {
        if (!isMaster) return world.forbidden("second-master-open")
        return LocalSetupMasterOpenResult.Opened(FreshWorkflowMaster(context, world))
    }
    override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult = world.forbidden("receipt-route")
    override suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient {
        if (!isMaster || action != LocalOriginalClientJournalAction.REOPEN_EXISTING) return world.forbidden("client-enroll-or-repair")
        return FreshWorkflowClient(harness)
    }
    override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean = world.forbidden("employee-owner")
    override fun publishClient(owner: Any, client: LocalSetupClient) { world.recordRead("publish-original-client") }
    override fun withdrawClient(owner: Any) { world.recordRead("withdraw-original-client") }
}

private class FreshWorkflowMaster(private val context: SelfOwnedLocalSecurityContext,
    private val world: FreshDevicePairingWorkflowWorld) : LocalSetupMaster {
    override val mode = MutableStateFlow(LocalMasterLifecycleMode.OPERATIONAL)
    override suspend fun permitsFirstClientEnrollment(): Boolean = false
    override suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning, authorityEpoch: Revision): LocalBranchInstallationSnapshotV2 = world.forbidden("prepare-installation")
    override suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2, headLogin: String,
        headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome = world.forbidden("install-original")
    override suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome = world.forbidden("ack-installation")
    override suspend fun readOriginalSetup(): LocalOriginalInstallationSetup = world.data.originalSetup
    override fun denyNewWork() = Unit
    override suspend fun quiesceAndClose(): Boolean { context.close(); return true }
}

private class FreshWorkflowTarget(private val harness: FreshDevicePairingWorkflowHarness) : LocalSetupFreshDeviceTarget {
    private val world get() = harness.world
    override suspend fun original(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> {
        world.recordRead("target-original")
        return LocalSetupFreshDeviceTargetResult.Value(world.sourceReference())
    }
    override suspend fun descriptor(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetDescriptor> {
        world.recordRead("target-descriptor")
        return LocalSetupFreshDeviceTargetResult.Value(world.data.identityT.target)
    }
    override suspend fun acquireSdkIdentity(appId: AppId, deviceName: String): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> {
        world.record(FreshWorkflowEffectEntry(FreshWorkflowEffect.TARGET_SDK_ACQUIRE, harness.targetSnapshot(), null,
            appId = appId.value, privateName = deviceName))
        world.acquisitionGate?.hold()
        if (world.acquisitionReply !in setOf(FreshWorkflowReply.UNAVAILABLE, FreshWorkflowReply.CAPACITY)) world.sdkCompleted = true
        effectFailure(world.acquisitionReply)
        return when (world.acquisitionReply) {
            FreshWorkflowReply.CAPACITY -> unavailable(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            FreshWorkflowReply.UNAVAILABLE -> unavailable(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            FreshWorkflowReply.FOREIGN -> LocalSetupFreshDeviceTargetResult.Value(world.data.identityU)
            else -> LocalSetupFreshDeviceTargetResult.Value(world.data.identityT)
        }
    }
    override suspend fun recoverSdkIdentity(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> {
        world.recordRead("sdk-recover")
        return if (world.sdkRecoveryUnavailable || !world.sdkCompleted) unavailable(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        else LocalSetupFreshDeviceTargetResult.Value(world.recoveredIdentity ?: world.data.identityT)
    }
    override suspend fun respond(challenge: LocalDevicePairingChallenge): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> {
        world.record(FreshWorkflowEffectEntry(FreshWorkflowEffect.TARGET_RESPOND, harness.targetSnapshot(), null,
            challengeBytes = challenge.copyWireBytes()))
        world.responseGate?.hold()
        if (world.responseReply !in setOf(FreshWorkflowReply.UNAVAILABLE, FreshWorkflowReply.CAPACITY)) world.responseCompleted = true
        effectFailure(world.responseReply)
        return when (world.responseReply) {
            FreshWorkflowReply.CAPACITY -> unavailable(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            FreshWorkflowReply.UNAVAILABLE -> unavailable(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            FreshWorkflowReply.FOREIGN -> LocalSetupFreshDeviceTargetResult.Value(world.data.foreignOriginal().possession)
            else -> LocalSetupFreshDeviceTargetResult.Value(checkNotNull(world.original).possession)
        }
    }
    override suspend fun recoverPossession(originalPairingActionId: DomainId): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> {
        world.recordRead("possession-recover:${originalPairingActionId.value}")
        if (world.possessionRecoveryUnavailable || !world.responseCompleted) return unavailable(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return LocalSetupFreshDeviceTargetResult.Value(world.recoveredPossession ?: checkNotNull(world.original).possession)
    }
    override suspend fun beginAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> = world.forbidden("unsigned-continuation")
    override fun denyNewWork() = Unit
    override suspend fun close() {
        world.recordRead("target-owned-close")
        world.closeGate?.hold()
        if (world.closeReply != FreshWorkflowReply.COMPLETE) {
            world.ownerCloseUnresolved = true
            effectFailure(world.closeReply)
            throw IOException("Controlled owned close remains unavailable")
        }
        world.ownerCloseUnresolved = false
        world.recordRead("target-owned-close-confirmed")
    }
    private fun unavailable(reason: SelfOwnedInstallationFailureV1) = LocalSetupFreshDeviceTargetResult.Unavailable(reason)
}

private class FreshWorkflowClient(private val harness: FreshDevicePairingWorkflowHarness) : LocalSetupClient {
    private val world get() = harness.world
    @Volatile private var current = true
    override fun isCurrent(): Boolean = current
    override fun denyNewWork() { current = false }
    override suspend fun close() { current = false }
    override suspend fun prepareSdkBoundDevicePairing(requestId: DomainId,
        identity: LocalDevicePairingSdkIdentityV2): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        world.record(FreshWorkflowEffectEntry(FreshWorkflowEffect.MASTER_PREPARE, null,
            harness.masterSnapshot(requestId), identityBytes = identity.copyWireBytes(), requestId = requestId))
        world.masterPreparationGate?.hold()
        val original = world.data.original(requestId, identity)
        if (world.masterPreparationReply != FreshWorkflowReply.UNAVAILABLE) world.original = original
        effectFailure(world.masterPreparationReply)
        return when (world.masterPreparationReply) {
            FreshWorkflowReply.UNAVAILABLE -> unavailable()
            FreshWorkflowReply.FOREIGN -> LocalCompanyAuthorityResult.Value(world.data.foreignOriginal().prepared)
            else -> LocalCompanyAuthorityResult.Value(original.prepared)
        }
    }
    override suspend fun proveDevicePairingTarget(action: LocalDevicePairingAction,
        possession: LocalDevicePairingPossession): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        world.record(FreshWorkflowEffectEntry(FreshWorkflowEffect.MASTER_PROVE, null,
            harness.masterSnapshot(action.requested.request.requestId),
            challengeBytes = action.challenge.copyWireBytes(), possessionBytes = possession.copyWireBytes(), actualAction = action))
        world.proofGate?.hold()
        if (world.proofReply !in setOf(FreshWorkflowReply.UNAVAILABLE, FreshWorkflowReply.PREPARED_ONLY, FreshWorkflowReply.FOREIGN)) world.proofCompleted = true
        effectFailure(world.proofReply)
        return when (world.proofReply) {
            FreshWorkflowReply.UNAVAILABLE -> unavailable()
            FreshWorkflowReply.FOREIGN -> LocalCompanyAuthorityResult.Value(world.data.foreignOriginal().proved)
            FreshWorkflowReply.PREPARED_ONLY -> LocalCompanyAuthorityResult.Value(checkNotNull(world.original).prepared)
            else -> LocalCompanyAuthorityResult.Value(checkNotNull(world.original).proved)
        }
    }
    override suspend fun retainedDevicePairings(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> {
        world.recordRead("master-retained-list")
        if (world.originalListUnavailable) return unavailable()
        val original = world.original
        return LocalCompanyAuthorityResult.Value(world.referencesOverride ?: if (original == null) emptyList()
            else listOf(if (world.proofCompleted) original.provedReference else original.preparedReference))
    }
    override suspend fun reviewDevicePairing(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        world.recordRead("master-review:${originalActionId.value}")
        if (world.originalReviewUnavailable) return unavailable()
        world.originalReviewOverride?.let { return LocalCompanyAuthorityResult.Value(it) }
        val original = world.original ?: return unavailable()
        if (originalActionId != original.prepared.originalActionId) return unavailable()
        return LocalCompanyAuthorityResult.Value(if (world.proofCompleted) original.proved else original.prepared)
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
    override suspend fun prepareEmployeePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = world.forbidden("employee-policy-prepare")
    override suspend fun approveEmployeePolicy(action: LocalEmployeePolicyActivationAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = world.forbidden("employee-policy-approve")
    override suspend fun reviewEmployeePolicy(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = world.forbidden("employee-policy-review")
    override suspend fun recoverEmployeePolicyApproval(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = world.forbidden("employee-policy-approval-recover")
    override suspend fun recoverEmployeePolicyConnection(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = world.forbidden("employee-policy-connection-recover")
    override suspend fun retainedEmployeePolicies():
        LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> = world.forbidden("employee-policy-list")
    override suspend fun activateEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult = world.forbidden("employee-policy-activate")
    override suspend fun lookupEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus = world.forbidden("employee-policy-lookup")
    private fun unavailable() = LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
}

private fun effectFailure(reply: FreshWorkflowReply) {
    when (reply) {
        FreshWorkflowReply.LOST_REPLY -> throw IOException("Controlled reply lost after entry")
        FreshWorkflowReply.THROW_AFTER_ENTRY -> throw IllegalStateException("Controlled failure after entry")
        FreshWorkflowReply.CANCEL_AFTER_ENTRY -> throw CancellationException("Controlled cancellation after entry")
        else -> Unit
    }
}
