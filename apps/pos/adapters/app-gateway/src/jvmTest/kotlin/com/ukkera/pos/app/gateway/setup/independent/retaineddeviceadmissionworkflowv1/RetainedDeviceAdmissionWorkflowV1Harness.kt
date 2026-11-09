package com.ukkera.pos.app.gateway.setup.independent.retaineddeviceadmissionworkflowv1

import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.gateway.setup.LocalDeviceAdmissionIntents
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchStorage
import com.ukkera.pos.app.gateway.setup.LocalSetupClient
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.app.gateway.setup.LocalSetupEmployeeConnection
import com.ukkera.pos.app.gateway.setup.LocalSetupExecution
import com.ukkera.pos.app.gateway.setup.LocalSetupIntentStorage
import com.ukkera.pos.app.gateway.setup.LocalSetupMaster
import com.ukkera.pos.app.gateway.setup.LocalSetupMasterOpenResult
import com.ukkera.pos.app.gateway.setup.LocalSetupNativeServices
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionStage
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionView
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalBranchInstallationDefinitionV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthorityFailure
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingDefinition
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.NativeDevicePairingWireV1
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Controlled application boundaries for the unchanged RDAW v1 cases. No native factory,
 * database, SDK, session, transport or protected writer is exercised by this harness.
 * Assertions are deliberately outside port bodies: the coordinator catches port failures.
 */
internal class RetainedDeviceAdmissionWorkflowV1Harness(
    val data: RetainedDeviceAdmissionWorkflowV1Data = RetainedDeviceAdmissionWorkflowV1Data(),
    val ledger: RetainedDeviceAdmissionWorkflowV1Ledger = RetainedDeviceAdmissionWorkflowV1Ledger(data),
    val storage: RetainedDeviceAdmissionWorkflowV1Storage = RetainedDeviceAdmissionWorkflowV1Storage(),
    val launchStorage: RetainedDeviceAdmissionWorkflowV1LaunchStorage = RetainedDeviceAdmissionWorkflowV1LaunchStorage(),
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "retained-device-admission-test").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val execution = ControlledExecution(dispatcher)
    private val employeeStorage = ForbiddenEmployeeStorage(ledger)
    private val services = ControlledServices(data, ledger, storage)
    val intents = LocalDeviceAdmissionIntents(storage)
    val context = LocalDeviceAdmissionIntents.Context(data.installationId, data.scope, data.cohostDeviceId)
    val coordinator = LocalSetupCoordinator(services, LocalEmployeeSetupIntents(employeeStorage),
        LocalMasterLaunchRecord(launchStorage), execution,
        beforeRetirement = { _, _ -> ledger.lifecycle("retire") },
        beforeClientHosting = { ledger.lifecycle("host-original-client") },
        deviceAdmissionIntents = intents)
    private var closed = false

    /** Enter through the public original-only setup route, never by injected coordinator state. */
    suspend fun open() {
        coordinator.openOriginalFirstAccount()
        idle()
        assertEquals(LocalSetupPhase.INSTALLATION_RETAINED, coordinator.state.value.phase,
            "the controlled existing original must be opened before workflow assertions")
        coordinator.reconnectOriginalLocalClient()
        idle()
        assertTrue(coordinator.state.value.localClientConnected,
            "only the controlled existing original client is attached")
        coordinator.openDeviceAdmissions()
        idle()
        assertEquals(LocalSetupPhase.DEVICE_ADMISSION, coordinator.state.value.phase)
        assertNoOtherApplicationEffects()
    }

    suspend fun idle() {
        withTimeout(WAIT_MILLIS) { coordinator.state.first { it.work == null } }
    }

    suspend fun inspect(original: RetainedDeviceAdmissionWorkflowV1Original = data.a): LocalSetupDeviceAdmissionView {
        coordinator.inspectDeviceAdmission(original.reference.originalActionId)
        idle()
        return selected()
    }

    fun selected(): LocalSetupDeviceAdmissionView = assertNotNull(coordinator.state.value.deviceAdmission)

    suspend fun refresh() {
        coordinator.openDeviceAdmissions()
        idle()
    }

    suspend fun lookup() {
        coordinator.lookupDeviceAdmission(selected().reviewId)
        idle()
    }

    suspend fun backAndReenter() {
        coordinator.closeDeviceAdmissions()
        idle()
        coordinator.openDeviceAdmissions()
        idle()
    }

    fun seedApproved(original: RetainedDeviceAdmissionWorkflowV1Original = data.a): LocalDeviceAdmissionIntents.Record {
        val before = intents.retainOriginal(context, original.approvedAction)
        return intents.retainApproval(before.identity, original.approval)
    }

    fun seedAttempted(original: RetainedDeviceAdmissionWorkflowV1Original = data.a): LocalDeviceAdmissionIntents.Record {
        val before = seedApproved(original)
        return intents.markAdmissionAttempt(before.identity)
    }

    suspend fun recreate(): RetainedDeviceAdmissionWorkflowV1Harness {
        close()
        val recreated = RetainedDeviceAdmissionWorkflowV1Harness(data, ledger, storage, launchStorage)
        try { recreated.open(); return recreated }
        catch (failure: Throwable) { recreated.close(); throw failure }
    }

    fun assertNoOtherApplicationEffects() {
        assertEquals(emptyList(), ledger.forbiddenCalls(), "no replacement, enrollment, policy or private application call")
        assertEquals(emptyList(), ledger.connectionActions().filter { it != LocalOriginalClientJournalAction.REOPEN_EXISTING },
            "bootstrap and restoration must not enroll or repair an original client")
        assertTrue(coordinator.state.value.employeeEnrollment == null, "no employee enrollment DATA may be published")
        assertTrue(coordinator.state.value.employeePolicy == null, "no employee policy DATA may be published")
        assertTrue(coordinator.state.value.definitionReview == null, "no replacement definition DATA may be published")
    }

    fun assertUnresolvedA() {
        val selected = selected()
        assertEquals(data.a.reference.originalActionId, selected.originalActionId)
        assertEquals(data.a.reference.originalRequestId, selected.originalRequestId)
        assertEquals(data.a.reference.deviceId, selected.deviceId)
        assertEquals(data.a.reference.keyId, selected.keyId)
        assertFalse(selected.stage in TERMINAL_STAGES, "unknown original must not display completion or no effect")
        assertNull(selected.eventId)
        assertNoOtherApplicationEffects()
    }

    fun assertOnlyOriginalALookups(requireLookup: Boolean = true) {
        val calls = ledger.lookups()
        if (requireLookup) assertTrue(calls.isNotEmpty(), "an attempted original must be resolved through the lookup boundary")
        assertTrue(calls.all { it.actionId == data.a.reference.originalActionId }, "recovery must never query same-label B")
    }

    fun assertOriginalReview(original: RetainedDeviceAdmissionWorkflowV1Original = data.a) {
        val expected = original.action
        val selected = selected()
        assertEquals(original.reference.originalActionId, selected.originalActionId)
        assertEquals(original.reference.originalRequestId, selected.originalRequestId)
        assertEquals(original.reference.deviceId, selected.deviceId)
        assertEquals(original.reference.keyId, selected.keyId)
        assertDefinitionBytes(expected.requested, assertNotNull(selected.requested))
        assertTrue(expected.challenge.companyName == selected.companyName, "captured original company name must remain unchanged")
        assertTrue(expected.challenge.headName == selected.headName, "captured original head name must remain unchanged")
        assertTrue(expected.challenge.headLogin == selected.headLogin, "captured original head login must remain unchanged")
        assertTrue(expected.challenge.rootFingerprint == selected.rootFingerprint, "captured original root fingerprint must remain unchanged")
        assertTrue(expected.challenge.challengeDigest.value == selected.challengeDigest, "captured original challenge digest must remain unchanged")
    }

    fun assertAcknowledgedOriginalEntry(entry: RetainedDeviceAdmissionWorkflowV1Entry, kind: String,
        original: RetainedDeviceAdmissionWorkflowV1Original = data.a) {
        val expected = original.action
        assertEquals(expected.originalActionId, entry.actionId)
        assertEquals(expected.requested.request.requestId, entry.requestId)
        assertEquals(expected.requested.admission.deviceId, entry.deviceId)
        assertEquals(expected.requested.target.keyId, entry.keyId)
        val definition = expected.requested.copyWireBytes()
        val challenge = expected.challenge.copyWireBytes()
        val sdk = assertNotNull(expected.requested.sdkIdentity).copyWireBytes()
        try {
            assertTrue(definition.contentEquals(entry.definitionBytes), "producer must receive the complete captured original definition")
            assertTrue(challenge.contentEquals(entry.challengeBytes), "head/context/deadline bytes must remain the captured original")
            val identity = listOf("ukkera.original-device-admission-ui-intent/v1", kind,
                data.installationId.value, data.scope.organizationId.value, data.scope.companyId.value,
                data.scope.branchId.value, data.cohostDeviceId.value, expected.originalActionId.value,
                expected.requested.request.requestId.value, expected.requested.admission.deviceId.value,
                expected.requested.target.keyId.value, assertNotNull(expected.requested.sdkIdentity).originalAcquisitionId.value,
                assertNotNull(expected.requested.sdkIdentity).originalPreparationId.value,
                independentDigest(definition), expected.requested.request.contentDigest.value,
                independentDigest(challenge), independentDigest(sdk), expected.challenge.rootFingerprint)
            assertRetainedFile(entry.retainedFiles, filename(expected.originalActionId, kind), identity)
            assertRetainedFile(entry.retainedFiles, filename(expected.originalActionId, "intent"),
                identity.toMutableList().apply { this[1] = "intent" })
            if (kind == "admit") {
                val certificate = original.approval.copyAuthorityCertificate()
                try {
                    assertTrue(certificate.contentEquals(assertNotNull(entry.authorityBytes)),
                        "authority certificate bytes must remain the captured original")
                    assertRetainedFile(entry.retainedFiles, filename(expected.originalActionId, "approval"),
                        identity.toMutableList().apply { this[1] = "approval" } + independentDigest(certificate))
                } finally { certificate.fill(0) }
            } else {
                assertEquals("head", kind)
                assertFalse(entry.retainedFiles.containsKey(filename(expected.originalActionId, "admit")))
                assertTrue(entry.authorityBytes == null, "head approval entry must not carry authority certificate bytes")
                assertTrue(expected.challenge.headLogin == entry.headLogin, "producer must receive the captured original head login")
                assertTrue(entry.secretWasPresent, "the explicit ceremony must receive its private supplied buffer")
            }
            assertFalse(entry.retainedFiles.containsKey(filename(expected.originalActionId, "applied")))
            assertFalse(entry.retainedFiles.containsKey(filename(expected.originalActionId, "no-effect")))
        } finally { definition.fill(0); challenge.fill(0); sdk.fill(0) }
    }

    fun assertSecretAbsent(witness: RetainedDeviceAdmissionWorkflowV1Secret) {
        assertFalse(witness.presentIn(coordinator.state.value), "secret must be absent from public workflow/route DATA")
        assertTrue(storage.observedWrites().all { !witness.presentIn(it.bytes) }, "secret must be absent from every retained-write payload")
        assertTrue(storage.snapshot().values.all { !witness.presentIn(it) }, "secret must be absent from retained evidence")
        assertTrue(ledger.safeDiagnostics().all { !witness.presentIn(it) }, "controlled diagnostics must never retain the supplied secret")
        assertTrue(ledger.headBuffersAreCleared(), "producer-received buffers must be cleared after ceremony exit")
    }

    suspend fun close() {
        if (closed) return
        closed = true
        storage.releaseGates()
        ledger.releaseGates()
        try { withTimeout(WAIT_MILLIS) { coordinator.requestRetirement().await() } }
        finally { dispatcher.close() }
    }

    private class ControlledExecution(override val dispatcher: CoroutineDispatcher) : LocalSetupExecution {
        override fun elapsedMillis(): Long = 0L
        override fun startWatching(tick: () -> Unit) = Unit
        override fun stopWatching() = Unit
    }

    companion object {
        const val WAIT_MILLIS = 10_000L
        val TERMINAL_STAGES = setOf(LocalSetupDeviceAdmissionStage.APPLIED, LocalSetupDeviceAdmissionStage.KNOWN_NO_EFFECT)
        fun filename(actionId: DomainId, kind: String): String = "${actionId.value}.$kind.v1"
    }
}

internal enum class RetainedDeviceAdmissionWorkflowV1Reply {
    APPROVED, PUBLIC_APPLIED_DATA, DENIED, UNAVAILABLE, UNCERTAIN, BUSY, LOST_REPLY, THROW_AFTER_ENTRY, CANCEL_AFTER_ENTRY,
}

/** Producer fact labels document the controlled prerequisite, not production settlement checks. */
internal enum class RetainedDeviceAdmissionWorkflowV1Outcome(val status: LocalProvisioningStatus) {
    UNAVAILABLE(LocalProvisioningStatus.Unavailable),
    MISSING_WITNESS_OR_HISTORY(LocalProvisioningStatus.Unavailable),
    CONFLICTING(LocalProvisioningStatus.StorageUncertain),
    AMBIGUOUS(LocalProvisioningStatus.StorageUncertain),
    APPARENT_ABSENCE_ONLY(LocalProvisioningStatus.Unavailable),
    CONFIRMED_UNUSED_NO_CHILD_SETTLEMENT_A(LocalProvisioningStatus.NotRecorded),
    CONFIRMED_ROLLBACK_SETTLEMENT_A(LocalProvisioningStatus.NotRecorded),
}

internal class RetainedDeviceAdmissionWorkflowV1Entry(
    val actionId: DomainId, val requestId: DomainId, val deviceId: DomainId, val keyId: DomainId,
    val definitionBytes: ByteArray, val challengeBytes: ByteArray, val authorityBytes: ByteArray?,
    val retainedFiles: Map<String, ByteArray>, val headLogin: String? = null, val secretWasPresent: Boolean = false,
) {
    override fun toString(): String = "ControlledOriginalEntry([REDACTED])"
}

internal data class RetainedDeviceAdmissionWorkflowV1Lookup(val actionId: DomainId, val controlledFact: String)

/** One suspendable boundary. Entry is recorded before holding or failing the answer. */
internal class RetainedDeviceAdmissionWorkflowV1PortGate {
    val entered = CompletableDeferred<Unit>()
    private val release = CompletableDeferred<Unit>()
    suspend fun hold() { entered.complete(Unit); release.await() }
    fun release() { release.complete(Unit) }
    suspend fun awaitEntry() { withTimeout(RetainedDeviceAdmissionWorkflowV1Harness.WAIT_MILLIS) { entered.await() } }
}

/** Shared retained DATA/call ledger survives only controlled coordinator recreation. */
internal class RetainedDeviceAdmissionWorkflowV1Ledger(val data: RetainedDeviceAdmissionWorkflowV1Data) {
    private val monitor = Any()
    private val approvalEntries = mutableListOf<RetainedDeviceAdmissionWorkflowV1Entry>()
    private val admissionEntries = mutableListOf<RetainedDeviceAdmissionWorkflowV1Entry>()
    private val lookupEntries = mutableListOf<RetainedDeviceAdmissionWorkflowV1Lookup>()
    private val reviewedIds = mutableListOf<DomainId>()
    private val approvalRecoveryIds = mutableListOf<DomainId>()
    private val deniedCalls = mutableListOf<String>()
    private val connections = mutableListOf<LocalOriginalClientJournalAction>()
    private val diagnostics = mutableListOf<String>()
    private val receivedHeadBuffers = mutableListOf<CharArray>()
    @Volatile var references: List<LocalDevicePairingReference> = listOf(data.a.reference, data.b.reference)
    @Volatile var actions: Map<DomainId, LocalDevicePairingAction> = mapOf(
        data.a.reference.originalActionId to data.a.action, data.b.reference.originalActionId to data.b.action)
    @Volatile var reviewUnavailable: Set<DomainId> = emptySet()
    @Volatile var listUnavailable: Boolean = false
    @Volatile var recoveryUnavailable: Boolean = false
    @Volatile var approvalReply = RetainedDeviceAdmissionWorkflowV1Reply.APPROVED
    @Volatile var admissionReply = RetainedDeviceAdmissionWorkflowV1Reply.UNAVAILABLE
    @Volatile var lookupStatus: LocalProvisioningStatus = LocalProvisioningStatus.Unavailable
    @Volatile var lookupFact: String = "unavailable"
    @Volatile var admissionRefusalFact: String? = null
    @Volatile var approvalGate: RetainedDeviceAdmissionWorkflowV1PortGate? = null
    @Volatile var admissionGate: RetainedDeviceAdmissionWorkflowV1PortGate? = null
    @Volatile var lookupGate: RetainedDeviceAdmissionWorkflowV1PortGate? = null
    @Volatile var approvalOnEntry: (() -> Unit)? = null
    @Volatile var admissionOnEntry: (() -> Unit)? = null

    fun approvedA() {
        actions = actions + (data.a.reference.originalActionId to data.a.approvedAction)
        references = references.map { if (it.originalActionId == data.a.reference.originalActionId) data.a.approvedReference else it }
    }

    fun approvedBoth() {
        approvedA()
        actions = actions + (data.b.reference.originalActionId to data.b.approvedAction)
        references = references.map { if (it.originalActionId == data.b.reference.originalActionId) data.b.approvedReference else it }
    }

    fun latestB(missingA: Boolean = false) {
        approvedBoth()
        references = listOf(data.b.approvedReference)
        if (missingA) reviewUnavailable = reviewUnavailable + data.a.reference.originalActionId
    }

    fun outcome(value: RetainedDeviceAdmissionWorkflowV1Outcome) {
        lookupFact = value.name
        lookupStatus = value.status
    }

    fun admittedA() {
        lookupFact = "matching_original_admission_confirmed_a"
        lookupStatus = LocalProvisioningStatus.Applied(data.a.reference.originalRequestId)
    }

    fun approval(entry: RetainedDeviceAdmissionWorkflowV1Entry, secret: CharArray) = synchronized(monitor) {
        approvalEntries += entry
        receivedHeadBuffers += secret
        diagnostics += "approval:${entry.actionId.value}"
    }
    fun admission(entry: RetainedDeviceAdmissionWorkflowV1Entry) = synchronized(monitor) {
        admissionEntries += entry; diagnostics += "admission:${entry.actionId.value}"
        admissionRefusalFact?.let { diagnostics += "controlled-refusal:$it" }
    }
    fun lookup(actionId: DomainId) = synchronized(monitor) {
        lookupEntries += RetainedDeviceAdmissionWorkflowV1Lookup(actionId, lookupFact)
        diagnostics += "lookup:${actionId.value}"
    }
    fun lifecycle(kind: String) = synchronized(monitor) { diagnostics += kind }
    fun reviewed(actionId: DomainId) = synchronized(monitor) { reviewedIds += actionId }
    fun recovered(actionId: DomainId) = synchronized(monitor) { approvalRecoveryIds += actionId }
    fun connection(action: LocalOriginalClientJournalAction) = synchronized(monitor) { connections += action }
    fun forbidden(kind: String): Nothing {
        synchronized(monitor) { deniedCalls += kind }
        throw IllegalStateException("No unrelated application boundary is bound")
    }
    fun approvals(): List<RetainedDeviceAdmissionWorkflowV1Entry> = synchronized(monitor) { approvalEntries.toList() }
    fun admissions(): List<RetainedDeviceAdmissionWorkflowV1Entry> = synchronized(monitor) { admissionEntries.toList() }
    fun lookups(): List<RetainedDeviceAdmissionWorkflowV1Lookup> = synchronized(monitor) { lookupEntries.toList() }
    fun reviews(): List<DomainId> = synchronized(monitor) { reviewedIds.toList() }
    fun recoveries(): List<DomainId> = synchronized(monitor) { approvalRecoveryIds.toList() }
    fun forbiddenCalls(): List<String> = synchronized(monitor) { deniedCalls.toList() }
    fun connectionActions(): List<LocalOriginalClientJournalAction> = synchronized(monitor) { connections.toList() }
    fun safeDiagnostics(): List<String> = synchronized(monitor) { diagnostics.toList() }
    fun headBuffersAreCleared(): Boolean = synchronized(monitor) { receivedHeadBuffers.all { chars -> chars.all { it == '\u0000' } } }
    fun releaseGates() { approvalGate?.release(); admissionGate?.release(); lookupGate?.release() }
}

internal enum class RetainedDeviceAdmissionWorkflowV1StorageFailure {
    REFUSED, UNAVAILABLE, EXCEPTION_BEFORE_ACKNOWLEDGEMENT, CANCELLATION_BEFORE_ACKNOWLEDGEMENT,
}

internal class RetainedDeviceAdmissionWorkflowV1Write(val name: String, val bytes: ByteArray) {
    override fun toString(): String = "ControlledRetentionWrite([REDACTED])"
}

/** Synchronous force port held on the owned coordinator thread, never the observing test thread. */
internal class RetainedDeviceAdmissionWorkflowV1StorageGate(val kind: String,
    val failure: RetainedDeviceAdmissionWorkflowV1StorageFailure? = null) {
    val entered = CompletableDeferred<Unit>()
    private val released = CountDownLatch(1)
    @Volatile var acknowledged = false
        private set
    fun beforeAcknowledge() {
        entered.complete(Unit)
        check(released.await(RetainedDeviceAdmissionWorkflowV1Harness.WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
            "Controlled retention release was not delivered"
        }
        when (failure) {
            RetainedDeviceAdmissionWorkflowV1StorageFailure.REFUSED -> throw IOException("Controlled retention refused")
            RetainedDeviceAdmissionWorkflowV1StorageFailure.UNAVAILABLE -> throw IOException("Controlled retention unavailable")
            RetainedDeviceAdmissionWorkflowV1StorageFailure.EXCEPTION_BEFORE_ACKNOWLEDGEMENT ->
                throw IllegalStateException("Controlled retention failure before acknowledgement")
            RetainedDeviceAdmissionWorkflowV1StorageFailure.CANCELLATION_BEFORE_ACKNOWLEDGEMENT ->
                throw CancellationException("Controlled retention cancellation before acknowledgement")
            null -> Unit
        }
    }
    fun acknowledge() { acknowledged = true }
    fun release() { released.countDown() }
    suspend fun awaitEntry() { withTimeout(RetainedDeviceAdmissionWorkflowV1Harness.WAIT_MILLIS) { entered.await() } }
}

/** In-memory controlled journal bytes only; no claim about filesystem or SQLite durability. */
internal class RetainedDeviceAdmissionWorkflowV1Storage : LocalSetupIntentStorage {
    private val monitor = Any()
    private val files = linkedMapOf<String, ByteArray>()
    private val writes = mutableListOf<RetainedDeviceAdmissionWorkflowV1Write>()
    private val acknowledged = mutableListOf<String>()
    @Volatile var gate: RetainedDeviceAdmissionWorkflowV1StorageGate? = null
    @Volatile var failedReadName: String? = null
    override fun names(): List<String> = synchronized(monitor) { files.keys.toList() }
    override fun read(name: String): ByteArray {
        if (name == failedReadName) throw IOException("Controlled retained-intent read unavailable")
        return synchronized(monitor) { checkNotNull(files[name]).copyOf() }
    }
    override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) {
        synchronized(monitor) { writes += RetainedDeviceAdmissionWorkflowV1Write(name, bytes.copyOf()) }
        val held = gate?.takeIf { name.endsWith(".${it.kind}.v1") }
        held?.beforeAcknowledge()
        synchronized(monitor) {
            val existing = files[name]
            if (existing != null) check(allowExactExisting && existing.contentEquals(bytes))
            else files[name] = bytes.copyOf()
            acknowledged += name
        }
        held?.acknowledge()
    }
    override fun verify() = Unit
    fun snapshot(): Map<String, ByteArray> = synchronized(monitor) { files.mapValues { it.value.copyOf() } }
    fun observedWrites(): List<RetainedDeviceAdmissionWorkflowV1Write> = synchronized(monitor) { writes.toList() }
    fun acknowledgedNames(): List<String> = synchronized(monitor) { acknowledged.toList() }
    fun effectWrites(): List<RetainedDeviceAdmissionWorkflowV1Write> = observedWrites().filter {
        it.name.endsWith(".head.v1") || it.name.endsWith(".admit.v1")
    }
    fun replaceFixtureBytes(name: String, bytes: ByteArray) { synchronized(monitor) { files[name] = bytes.copyOf() } }
    fun releaseGates() { gate?.release() }
}

internal class RetainedDeviceAdmissionWorkflowV1LaunchStorage : LocalMasterLaunchStorage {
    private var bytes: ByteArray? = null
    @Synchronized override fun read(): ByteArray? = bytes?.copyOf()
    @Synchronized override fun replaceAndForce(bytes: ByteArray) { this.bytes = bytes.copyOf() }
}

private class ForbiddenEmployeeStorage(private val ledger: RetainedDeviceAdmissionWorkflowV1Ledger) : LocalSetupIntentStorage {
    override fun names(): List<String>? = ledger.forbidden("employee-intent-names")
    override fun read(name: String): ByteArray = ledger.forbidden("employee-intent-read")
    override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean): Unit = ledger.forbidden("employee-intent-write")
    override fun verify(): Unit = ledger.forbidden("employee-intent-verify")
}

private class ControlledServices(private val data: RetainedDeviceAdmissionWorkflowV1Data,
    private val ledger: RetainedDeviceAdmissionWorkflowV1Ledger, private val storage: RetainedDeviceAdmissionWorkflowV1Storage) :
    LocalSetupNativeServices {
    override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult = ledger.forbidden("open-provisioner")
    override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult {
        if (preparation) return ledger.forbidden("prepare-target")
        ledger.lifecycle("read-original-target-data")
        return SelfOwnedNativeOpenResult.Opened(data.freshContext())
    }
    override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult =
        LocalSetupMasterOpenResult.Opened(ControlledMaster(context, data, ledger))
    override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult =
        ledger.forbidden("open-receipt-route")
    override suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient {
        ledger.connection(action)
        return ControlledClient(data, ledger, storage)
    }
    override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean = ledger.forbidden("owns-employee-connection")
    override fun publishClient(owner: Any, client: LocalSetupClient) { ledger.lifecycle("publish-controlled-original-client") }
    override fun withdrawClient(owner: Any) { ledger.lifecycle("withdraw-controlled-original-client") }
}

private class ControlledMaster(private val context: SelfOwnedLocalSecurityContext,
    private val data: RetainedDeviceAdmissionWorkflowV1Data, private val ledger: RetainedDeviceAdmissionWorkflowV1Ledger) : LocalSetupMaster {
    override val mode = MutableStateFlow(LocalMasterLifecycleMode.OPERATIONAL)
    override suspend fun permitsFirstClientEnrollment(): Boolean = false
    override suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning, authorityEpoch: Revision): LocalBranchInstallationSnapshotV2 =
        ledger.forbidden("prepare-installation")
    override suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2, headLogin: String,
        headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome = ledger.forbidden("install-original")
    override suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome = ledger.forbidden("ack-installation")
    override suspend fun readOriginalSetup(): LocalOriginalInstallationSetup = data.originalSetup
    override fun denyNewWork() { ledger.lifecycle("deny-controlled-master") }
    override suspend fun quiesceAndClose(): Boolean { context.close(); ledger.lifecycle("close-controlled-master"); return true }
}

private class ControlledClient(private val data: RetainedDeviceAdmissionWorkflowV1Data,
    private val ledger: RetainedDeviceAdmissionWorkflowV1Ledger, private val storage: RetainedDeviceAdmissionWorkflowV1Storage) : LocalSetupClient {
    @Volatile private var current = true
    override fun isCurrent(): Boolean = current
    override fun denyNewWork() { current = false }
    override suspend fun close() { current = false; ledger.lifecycle("close-controlled-client") }
    override suspend fun retainedDevicePairings(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> =
        if (ledger.listUnavailable) unavailable() else LocalCompanyAuthorityResult.Value(ledger.references)
    override suspend fun reviewDevicePairing(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        ledger.reviewed(originalActionId)
        if (originalActionId in ledger.reviewUnavailable) return unavailable()
        return ledger.actions[originalActionId]?.let { LocalCompanyAuthorityResult.Value(it) } ?: unavailable()
    }
    override suspend fun approveDevicePairing(action: LocalDevicePairingAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
        val requested = action.requested
        ledger.approval(RetainedDeviceAdmissionWorkflowV1Entry(action.originalActionId, requested.request.requestId,
            requested.admission.deviceId, requested.target.keyId, requested.copyWireBytes(), action.challenge.copyWireBytes(),
            null, storage.snapshot(), headLogin, headSecret.any { it != '\u0000' }), headSecret)
        ledger.approvalOnEntry?.invoke()
        ledger.approvalGate?.hold()
        return when (ledger.approvalReply) {
            RetainedDeviceAdmissionWorkflowV1Reply.APPROVED -> {
                ledger.approvedA(); LocalCompanyAuthorityResult.Value(data.a.approval)
            }
            RetainedDeviceAdmissionWorkflowV1Reply.LOST_REPLY -> throw IOException("Controlled approval reply lost after entry")
            RetainedDeviceAdmissionWorkflowV1Reply.THROW_AFTER_ENTRY -> throw IllegalStateException("Controlled approval producer failure after entry")
            RetainedDeviceAdmissionWorkflowV1Reply.CANCEL_AFTER_ENTRY -> throw CancellationException("Controlled approval cancelled after entry")
            else -> unavailable()
        }
    }
    override suspend fun recoverDevicePairingApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
        ledger.recovered(originalActionId)
        if (ledger.recoveryUnavailable) return unavailable()
        val original = when (originalActionId) {
            data.a.reference.originalActionId -> data.a
            data.b.reference.originalActionId -> data.b
            else -> return unavailable()
        }
        return LocalCompanyAuthorityResult.Value(original.approval)
    }
    override suspend fun admitOriginalDevice(approval: LocalDevicePairingApproval): LocalCredentialAdministrationResult {
        val requested = approval.requested
        ledger.admission(RetainedDeviceAdmissionWorkflowV1Entry(approval.originalActionId, requested.request.requestId,
            requested.admission.deviceId, requested.target.keyId, requested.copyWireBytes(),
            NativeDevicePairingWireV1.encodeChallenge(approval.record.body), approval.copyAuthorityCertificate(), storage.snapshot()))
        ledger.admissionOnEntry?.invoke()
        ledger.admissionGate?.hold()
        return when (ledger.admissionReply) {
            RetainedDeviceAdmissionWorkflowV1Reply.PUBLIC_APPLIED_DATA -> LocalCredentialAdministrationResult.Applied(requested.request.requestId)
            RetainedDeviceAdmissionWorkflowV1Reply.DENIED -> LocalCredentialAdministrationResult.Denied
            RetainedDeviceAdmissionWorkflowV1Reply.UNCERTAIN -> LocalCredentialAdministrationResult.StorageUncertain
            RetainedDeviceAdmissionWorkflowV1Reply.BUSY -> LocalCredentialAdministrationResult.Busy
            RetainedDeviceAdmissionWorkflowV1Reply.LOST_REPLY -> throw IOException("Controlled admission reply lost after entry")
            RetainedDeviceAdmissionWorkflowV1Reply.THROW_AFTER_ENTRY -> throw IllegalStateException("Controlled admission producer failure after entry")
            RetainedDeviceAdmissionWorkflowV1Reply.CANCEL_AFTER_ENTRY -> throw CancellationException("Controlled admission cancelled after entry")
            else -> LocalCredentialAdministrationResult.Unavailable
        }
    }
    override suspend fun lookupOriginalDeviceAdmission(originalActionId: DomainId): LocalProvisioningStatus {
        ledger.lookup(originalActionId)
        ledger.lookupGate?.hold()
        return ledger.lookupStatus
    }
    override suspend fun prepareEmployeeEnrollment(requestId: DomainId, definition: LocalAccountProvisioning): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
        ledger.forbidden("prepare-employee")
    override suspend fun approveEmployeeEnrollment(action: LocalEmployeeEnrollmentAction, headLogin: String, headSecret: CharArray): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> =
        ledger.forbidden("approve-employee")
    override suspend fun recoverEmployeeEnrollmentApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> =
        ledger.forbidden("recover-employee-approval")
    override suspend fun recoverEmployeeEnrollmentConnection(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> =
        ledger.forbidden("recover-employee-connection")
    override suspend fun reviewEmployeeEnrollment(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
        ledger.forbidden("review-employee")
    override suspend fun retainedEmployeeEnrollments(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> = ledger.forbidden("list-employees")
    override suspend fun enrollApprovedEmployee(originalRequestId: DomainId, password: CharArray): LocalEnrollmentResult = ledger.forbidden("enroll-employee")
    override suspend fun lookupEmployeeEnrollment(originalRequestId: DomainId): LocalEnrollmentResult = ledger.forbidden("lookup-employee")
    override suspend fun acquireEmployeeIntentJournal(originalRequestId: DomainId, action: NativeEmployeeClientJournalAction): Boolean = ledger.forbidden("employee-journal")
    override suspend fun prepareEmployeePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
        ledger.forbidden("prepare-policy")
    override suspend fun approveEmployeePolicy(action: LocalEmployeePolicyActivationAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = ledger.forbidden("approve-policy")
    override suspend fun reviewEmployeePolicy(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = ledger.forbidden("review-policy")
    override suspend fun recoverEmployeePolicyApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        ledger.forbidden("recover-policy-approval")
    override suspend fun recoverEmployeePolicyConnection(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        ledger.forbidden("recover-policy-connection")
    override suspend fun retainedEmployeePolicies(): LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> = ledger.forbidden("list-policies")
    override suspend fun activateEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt, nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult =
        ledger.forbidden("activate-policy")
    override suspend fun lookupEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt, nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus =
        ledger.forbidden("lookup-policy")
    private fun unavailable(): LocalCompanyAuthorityResult.Unavailable = LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
}

/** Private ephemeral observation only; never convert the complete input to a String or log it. */
internal class RetainedDeviceAdmissionWorkflowV1Secret private constructor(private val pattern: CharArray) {
    fun input(): CharArray = pattern.copyOf()
    fun presentIn(text: CharSequence): Boolean {
        if (text.length < pattern.size) return false
        return (0..text.length - pattern.size).any { start -> pattern.indices.all { text[start + it] == pattern[it] } }
    }
    fun presentIn(bytes: ByteArray): Boolean {
        if (bytes.size < pattern.size) return false
        return (0..bytes.size - pattern.size).any { start -> pattern.indices.all { (bytes[start + it].toInt() and 255) == pattern[it].code } }
    }
    fun presentIn(view: LocalSetupView): Boolean {
        if (presentIn(view.toString())) return true
        val selected = view.deviceAdmission ?: return false
        if (listOfNotNull(selected.companyName, selected.headName, selected.headLogin, selected.rootFingerprint,
                selected.challengeDigest, selected.authorityDigest).any(::presentIn)) return true
        val bytes = selected.requested?.copyWireBytes() ?: return false
        return try { presentIn(bytes) } finally { bytes.fill(0) }
    }
    fun clear() { pattern.fill('\u0000') }
    override fun toString(): String = "EphemeralHeadSecretObservation([REDACTED])"
    companion object {
        fun create(): RetainedDeviceAdmissionWorkflowV1Secret {
            val random = SecureRandom()
            return RetainedDeviceAdmissionWorkflowV1Secret(CharArray(24) { ('a'.code + random.nextInt(26)).toChar() })
        }
    }
}

internal fun assertDefinitionBytes(expected: LocalDevicePairingDefinition, actual: LocalDevicePairingDefinition) {
    val left = expected.copyWireBytes(); val right = actual.copyWireBytes()
    // Failed observed-DATA equality must not format possibly secret-bearing contents.
    try { assertTrue(left.contentEquals(right), "captured complete original bytes must not be reconstructed or replaced") }
    finally { left.fill(0); right.fill(0) }
}

internal fun independentDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

internal fun controlledJournalBytes(fields: List<String>): ByteArray {
    val body = fields.joinToString("\n", postfix = "\n").encodeToByteArray()
    return try { body + "sha256:${independentDigest(body)}\n".encodeToByteArray() } finally { body.fill(0) }
}

internal fun assertRetainedFile(files: Map<String, ByteArray>, name: String, fields: List<String>) {
    val expected = controlledJournalBytes(fields)
    try { assertTrue(expected.contentEquals(assertNotNull(files[name])), "independently observed acknowledged retained action binding") }
    finally { expected.fill(0) }
}

internal fun assertRetainedEvidenceUnchanged(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
    assertTrue(expected.keys == actual.keys, "evidence must not be deleted, relabeled or replaced")
    expected.forEach { (name, bytes) ->
        assertTrue(bytes.contentEquals(assertNotNull(actual[name])), "original evidence must not be overwritten")
    }
}
