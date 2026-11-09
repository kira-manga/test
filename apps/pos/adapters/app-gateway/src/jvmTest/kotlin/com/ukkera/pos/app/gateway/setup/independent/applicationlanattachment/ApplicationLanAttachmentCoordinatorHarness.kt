package com.ukkera.pos.app.gateway.setup.independent.applicationlanattachment

import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.gateway.setup.*
import com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow.FreshDevicePairingWorkflowData
import com.ukkera.pos.app.setup.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The production coordinator and official mapper run with bounded controlled contract ports.
 * Host script read/ready/open labels are composition observations, not real SDK factories,
 * native originals, physical interfaces, custody, signatures, storage or LAN evidence.
 * No approved-Master capability is constructed: Client-positive cases cannot run here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ApplicationLanAttachmentCoordinatorHarness(test: TestScope) {
    val scheduler: TestCoroutineScheduler = test.testScheduler
    val events = ApplicationLanEvents()
    val data = FreshDevicePairingWorkflowData()
    private val context = data.freshContext()
    val execution = ApplicationLanExecution(scheduler)
    val master = ApplicationLanMaster(data, events)
    val host = ApplicationLanHost(events)
    val fresh = ApplicationLanFreshTarget(data, events)
    val launchStorage = ApplicationLanLaunchStorage(events)
    val employeeStorage = ApplicationLanForbiddenStorage(events, "employee")
    val admissionStorage = ApplicationLanForbiddenStorage(events, "admission")
    val targetStorage = ApplicationLanMemoryIntents(events, "pairing.target")
    val masterStorage = ApplicationLanMemoryIntents(events, "pairing.master")
    val pairingIntents = LocalFreshDevicePairingIntents(targetStorage, masterStorage)
    val hostRetentions = mutableListOf<HostRetention>()
    var retentionFailure: Throwable? = null
    private var retirementResult: Deferred<Unit>? = null

    private val services: LocalSetupNativeServices = object : LocalSetupNativeServices {
        override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult = forbidden("provisioner.open")
        override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult {
            events.record(if (preparation) "forbidden.target.create" else "setup.target.open-existing")
            if (preparation) throw IOException("No creating target is available in this harness")
            return SelfOwnedNativeOpenResult.Opened(context)
        }
        override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult {
            events.record("setup.master.open-existing")
            return LocalSetupMasterOpenResult.Opened(master)
        }
        override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult =
            forbidden("receipt.open")
        override suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient? =
            forbidden("employee-client.connect")
        override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean {
            events.record("forbidden.employee-connection.check")
            return false
        }
        override fun publishClient(owner: Any, client: LocalSetupClient): Unit = forbidden("client.publish")
        override fun withdrawClient(owner: Any) { events.record("setup.client.withdraw") }
        override suspend fun prepareFreshDeviceTarget(scope: BranchScope, capabilities: Set<String>): LocalSetupFreshDeviceTargetOpenResult =
            forbidden("fresh-target.create")
        override suspend fun openOriginalFreshDeviceTarget(): LocalSetupFreshDeviceTargetOpenResult {
            events.record("setup.fresh-target.open-existing")
            return LocalSetupFreshDeviceTargetOpenResult.Opened(fresh)
        }
        override fun retainLanHost(master: LocalSetupMaster, scope: CoroutineScope,
            settings: LocalSetupLanSettings): LocalSetupLanHost {
            events.record("Host.retain")
            hostRetentions += HostRetention(master, scope, settings, coordinator.state.value.lan)
            retentionFailure?.let { throw it }
            host.bind(scope)
            return host
        }
        override fun retainLanClient(target: LocalSetupFreshDeviceTarget, scope: CoroutineScope,
            originalPairingActionId: DomainId, settings: LocalSetupLanSettings): LocalSetupLanClient =
            forbidden("Client.retain")
    }

    val coordinator: LocalSetupCoordinator = LocalSetupCoordinator(services, LocalEmployeeSetupIntents(employeeStorage),
        LocalMasterLaunchRecord(launchStorage), execution,
        beforeRetirement = { _, result ->
            events.record("setup.retirement.unpublish")
            retirementResult = result
            result.invokeOnCompletion { failure -> if (failure == null) events.record("setup.retirement.confirmed") }
        },
        beforeClientHosting = { events.record("Host.notice") },
        deviceAdmissionIntents = LocalDeviceAdmissionIntents(admissionStorage),
        freshDevicePairingIntents = pairingIntents)

    val view get() = coordinator.state.value
    fun pump() { scheduler.runCurrent() }
    fun idle() { pump(); assertEquals(null, view.work, "The bounded operation has not completed") }
    fun openExistingMaster() {
        coordinator.openOriginalFirstAccount(); idle()
        assertEquals(LocalSetupPhase.INSTALLATION_RETAINED, view.phase)
        assertTrue(view.lan.hostAvailable)
        assertEquals(1, events.count("setup.master.open-existing"))
    }
    fun startHost() { coordinator.startLanHost(ApplicationLanAttachmentFrozenInputs.hostZero); pump() }
    fun activeHost() {
        startHost(); idle()
        assertEquals(LocalSetupLanStatus.LISTENING, view.lan.status)
        assertEquals(ApplicationLanAttachmentFrozenInputs.actualHostEndpoint, view.lan.endpoint)
    }

    /** Reuse existing controlled DATA, not a newly signed proof or an approved-Master token. */
    fun seedResponseData() {
        val original = data.original()
        val input = data.targetInput
        val intentId = FreshDevicePairingWorkflowData.id("target_input_intent_A")
        pairingIntents.beginTarget(intentId, input.scope, input.capabilities, input.appId, input.deviceName)
        pairingIntents.markTargetPreparation(intentId)
        pairingIntents.retainTargetReference(intentId, data.keyReference())
        pairingIntents.markTargetSdkAttempt(intentId)
        pairingIntents.retainTargetSdkIdentity(intentId, data.identityT)
        pairingIntents.markTargetResponse(intentId, original.challenge)
        pairingIntents.retainTargetPossession(intentId, original.possession)
        fresh.reference = data.responseReference(original)
        fresh.possession = original.possession
    }
    fun recoverFreshTarget() { coordinator.inspectOriginalFreshDeviceTarget(); idle() }
    fun chooseFreshTargetWithoutNativeOwner() { coordinator.chooseFreshDeviceTarget(); idle() }

    fun snapshot(): List<String> = events.all.toList()
    fun assertNoNewEffects(before: List<String>) {
        assertEquals(before, events.all, "A refused direct request touched a contract port or effect sink")
    }
    fun assertNoAuthorityEffects() {
        assertTrue(events.all.none { it.startsWith("forbidden.") }, "An unrelated native/authority operation was attempted")
        assertEquals(0, events.count("launch.write"))
        assertEquals(0, events.count("pairing.master.write"))
    }
    fun assertMasterPreserved() {
        assertEquals(LocalMasterLifecycleMode.OPERATIONAL, master.mode.value)
        assertEquals(0, events.count("Master.deny"))
        assertEquals(0, events.count("Master.close.enter"))
        assertEquals(0, events.count("Master.close.confirmed"))
        assertEquals(1, events.count("setup.master.open-existing"))
        assertNoAuthorityEffects()
    }
    fun assertSingleHostScript() {
        assertEquals(1, events.count("Host.retain"))
        assertEquals(1, events.count("script.HostOriginal.read.enter"))
        assertEquals(1, events.count("script.HostFactory.create.enter"))
        assertEquals(1, events.count("script.LAN.open.enter"))
    }

    /** Teardown awaits real coordinator retirement; unknown outcomes are not retried into PASS. */
    suspend fun finish(expectUnconfirmed: Boolean = false) {
        host.releaseAll()
        fresh.closeGate.release()
        master.closeGate.release()
        pump()
        val result = retirementResult ?: coordinator.requestRetirement()
        pump()
        assertTrue(result.isCompleted, "Teardown must not leave an admitted script or close barrier waiting")
        val outcome = runCatching { result.await() }
        if (expectUnconfirmed) {
            assertTrue(outcome.isFailure, "Unknown construction/close cannot become confirmed cleanup")
            assertFalse(view.phase == LocalSetupPhase.CLOSED)
            assertEquals(0, events.count("setup.retirement.confirmed"))
        } else {
            outcome.getOrThrow()
            assertEquals(LocalSetupPhase.CLOSED, view.phase)
            assertEquals(1, events.count("setup.retirement.confirmed"))
        }
        // These fixture bytes have no native custody. Close their test-owned read DATA only.
        context.close()
    }

    private fun forbidden(name: String): Nothing {
        events.record("forbidden.$name")
        throw IOException("This unrelated native operation is unavailable")
    }
    data class HostRetention(val master: LocalSetupMaster, val scope: CoroutineScope,
        val settings: LocalSetupLanSettings, val view: LocalSetupLanView)
}

internal class ApplicationLanEvents {
    val all = mutableListOf<String>()
    fun record(event: String) { all += event }
    fun count(event: String): Int = all.count { it == event }
    fun index(event: String): Int = all.indexOf(event).also { assertTrue(it >= 0, "Missing event $event") }
}

internal class ApplicationLanGate {
    private var result = CompletableDeferred<Unit>().also { it.complete(Unit) }
    fun hold() { check(result.isCompleted); result = CompletableDeferred() }
    fun release() { result.complete(Unit) }
    suspend fun await() { result.await() }
}

/** A controlled idempotent native contract port; its internals are not SDK coverage. */
internal class ApplicationLanHost(private val events: ApplicationLanEvents) : LocalSetupLanHost {
    val readGate = ApplicationLanGate()
    val factoryGate = ApplicationLanGate()
    val openGate = ApplicationLanGate()
    val observationGate = ApplicationLanGate()
    val closeGate = ApplicationLanGate()
    var failedStage: String? = null
    var observationFailure: Throwable? = null
    var closeFailure: Throwable? = null
    var actualEndpoint = ApplicationLanAttachmentFrozenInputs.actualHostEndpoint
    var nextObservation = actualEndpoint
    private var startup: Deferred<LocalSetupLanEndpoint>? = null

    fun bind(scope: CoroutineScope) {
        check(startup == null)
        startup = scope.async(start = CoroutineStart.LAZY) {
            events.record("script.HostOriginal.read.enter")
            readGate.await(); failAt("original_read_admitted")
            events.record("script.HostOriginal.read.confirmed")
            events.record("script.HostFactory.create.enter")
            factoryGate.await(); failAt("factory_construction_admitted")
            events.record("script.HostFactory.ready")
            events.record("script.LAN.open.enter")
            openGate.await(); failAt("lan_open_admitted")
            events.record("script.LAN.listening")
            actualEndpoint
        }
    }
    fun hold(stage: String) { gate(stage).hold() }
    fun release(stage: String) { gate(stage).release() }
    fun releaseAll() { readGate.release(); factoryGate.release(); openGate.release(); observationGate.release(); closeGate.release() }
    private fun gate(stage: String): ApplicationLanGate = when (stage) {
        "original_read_admitted" -> readGate
        "factory_construction_admitted" -> factoryGate
        "lan_open_admitted" -> openGate
        else -> error("Unknown frozen Host stage $stage")
    }
    private fun failAt(stage: String) {
        if (failedStage == stage) throw IOException("Controlled definitive stage failure")
    }
    override suspend fun start(): LocalSetupLanEndpoint {
        events.record("Host.start")
        return checkNotNull(startup).await()
    }
    override suspend fun endpoint(): LocalSetupLanEndpoint {
        events.record("Host.observe.enter")
        observationGate.await()
        observationFailure?.let { throw it }
        events.record("Host.observe.confirmed")
        return nextObservation
    }
    override suspend fun close() {
        events.record("Host.close.enter")
        closeGate.await()
        closeFailure?.let { throw it }
        events.record("Host.close.confirmed")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ApplicationLanExecution(private val scheduler: TestCoroutineScheduler) : LocalSetupExecution {
    override val dispatcher = StandardTestDispatcher(scheduler)
    private var tick: (() -> Unit)? = null
    override fun elapsedMillis(): Long = scheduler.currentTime
    override fun startWatching(tick: () -> Unit) { this.tick = tick }
    override fun stopWatching() { tick = null }
}

internal class ApplicationLanMaster(private val data: FreshDevicePairingWorkflowData,
    private val events: ApplicationLanEvents) : LocalSetupMaster {
    override val mode = MutableStateFlow(LocalMasterLifecycleMode.OPERATIONAL)
    val closeGate = ApplicationLanGate()
    override suspend fun permitsFirstClientEnrollment(): Boolean { events.record("Master.enrollment.read"); return false }
    override suspend fun readOriginalSetup(): LocalOriginalInstallationSetup {
        events.record("Master.setup.read")
        return data.originalSetup
    }
    override suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning,
        authorityEpoch: Revision): LocalBranchInstallationSnapshotV2 = refuse("master.prepare")
    override suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2,
        headLogin: String, headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome {
        headSecret.fill('\u0000'); employeePassword.fill('\u0000')
        return refuse("master.install")
    }
    override suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome = refuse("master.acknowledgement")
    override fun denyNewWork() { events.record("Master.deny") }
    override suspend fun quiesceAndClose(): Boolean {
        events.record("Master.close.enter")
        closeGate.await()
        mode.value = LocalMasterLifecycleMode.CLOSED
        events.record("Master.close.confirmed")
        return true
    }
    private fun refuse(name: String): Nothing { events.record("forbidden.$name"); throw IOException("No installation effects are available") }
}

internal class ApplicationLanFreshTarget(private val data: FreshDevicePairingWorkflowData,
    private val events: ApplicationLanEvents) : LocalSetupFreshDeviceTarget {
    var reference = data.keyReference()
    var possession: LocalDevicePairingPossession? = null
    var originalFailure = false
    val closeGate = ApplicationLanGate()
    override suspend fun original(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> {
        events.record("FreshTarget.original.read")
        return if (originalFailure) LocalSetupFreshDeviceTargetResult.Unavailable(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        else LocalSetupFreshDeviceTargetResult.Value(reference)
    }
    override suspend fun descriptor(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetDescriptor> {
        events.record("FreshTarget.descriptor.read")
        return LocalSetupFreshDeviceTargetResult.Value(data.identityT.target)
    }
    override suspend fun recoverSdkIdentity(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> {
        events.record("FreshTarget.sdk.read")
        return LocalSetupFreshDeviceTargetResult.Value(data.identityT)
    }
    override suspend fun recoverPossession(originalPairingActionId: DomainId): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> {
        events.record("FreshTarget.possession.read")
        val retained = possession
        return if (retained != null && retained.originalActionId == originalPairingActionId)
            LocalSetupFreshDeviceTargetResult.Value(retained)
        else LocalSetupFreshDeviceTargetResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    }
    override suspend fun acquireSdkIdentity(appId: AppId, deviceName: String): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> = refuse("fresh.sdk.acquire")
    override suspend fun respond(challenge: LocalDevicePairingChallenge): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> = refuse("fresh.sign")
    override suspend fun importApprovedMaster(transfer: LocalDevicePairingMasterTransfer): LocalSetupFreshDeviceTargetResult<LocalDevicePairingApprovedMaster> = refuse("fresh.trust.import")
    override suspend fun recoverApprovedMaster(originalPairingActionId: DomainId): LocalSetupFreshDeviceTargetResult<LocalDevicePairingApprovedMaster> {
        events.record("FreshTarget.approved-master.read")
        return LocalSetupFreshDeviceTargetResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    }
    override suspend fun beginAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> = refuse("fresh.replace")
    override fun denyNewWork() { events.record("FreshTarget.deny") }
    override suspend fun close() {
        events.record("FreshTarget.close.enter")
        closeGate.await()
        events.record("FreshTarget.close.confirmed")
    }
    private fun refuse(name: String): Nothing { events.record("forbidden.$name"); throw IOException("No target ceremony or authority effect is available") }
}

internal class ApplicationLanLaunchStorage(private val events: ApplicationLanEvents) : LocalMasterLaunchStorage {
    var bytes: ByteArray? = null
        private set
    override fun read(): ByteArray? = bytes?.copyOf()
    override fun replaceAndForce(bytes: ByteArray) { events.record("launch.write"); this.bytes = bytes.copyOf() }
}

internal class ApplicationLanForbiddenStorage(private val events: ApplicationLanEvents,
    private val name: String) : LocalSetupIntentStorage {
    override fun names(): List<String> = emptyList()
    override fun read(name: String): ByteArray = refuse("read")
    override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean): Unit = refuse("write")
    override fun verify() = Unit
    private fun refuse(operation: String): Nothing { events.record("forbidden.$name.$operation"); throw IOException("No unrelated intent effect is available") }
}

internal class ApplicationLanMemoryIntents(private val events: ApplicationLanEvents,
    private val label: String) : LocalSetupIntentStorage {
    private val files = linkedMapOf<String, ByteArray>()
    override fun names(): List<String> = files.keys.toList()
    override fun read(name: String): ByteArray = files.getValue(name).copyOf()
    override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) {
        events.record("$label.write")
        val retained = files[name]
        if (retained != null) require(allowExactExisting && retained.contentEquals(bytes))
        else files[name] = bytes.copyOf()
    }
    override fun verify() = Unit
}
