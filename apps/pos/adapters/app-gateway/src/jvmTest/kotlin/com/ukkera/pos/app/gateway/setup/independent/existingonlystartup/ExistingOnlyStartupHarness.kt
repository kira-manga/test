package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

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
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalBranchInstallationDefinitionV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingMasterTransfer
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingTargetDescriptor
import com.ukkera.pos.security.LocalDevicePairingTargetReference
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalEmployeeEnrollmentAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeeEnrollmentDefinition
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeBindingV1
import com.ukkera.pos.security.SelfOwnedNativeContextController
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.SelfOwnedNativeReadResult
import com.ukkera.pos.security.SelfOwnedNativeWithdrawal
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Controlled-port harness for the effective E3-002 v3 + errata scenes. The real
 * LocalSetupCoordinator and LocalMasterLaunchRecord execute; every native/service boundary is a
 * transparent scripted port that records the actual calls, object identities and outcomes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ExistingOnlyStartupHarness(
    private val test: TestScope,
    val variant: FrozenVariant,
    private val fixtures: ExistingOnlyStartupFixtures = ExistingOnlyStartupFixtures,
) {
    val scene: Scene = Scene.from(variant, fixtures)
    val scheduler: TestCoroutineScheduler = test.testScheduler
    val log = StartupObservationLog()
    val ledger = OwnershipLedger()
    val launchStorage = ControlledLaunchStorage(scene.recordId, fixtures, log)
    private val recording = RecordingDispatcher(StandardTestDispatcher(scheduler))
    val execution = ControlledExecution(recording, scheduler)

    val targetOpens = mutableListOf<TargetOpenObservation>()
    val masterOpens = mutableListOf<MasterOpenObservation>()
    val bindingReads = mutableListOf<BindingObservation>()
    val setupReads = mutableListOf<SetupObservation>()
    val clientConnects = mutableListOf<ClientConnectObservation>()
    val publishes = mutableListOf<PublishObservation>()
    val withdraws = mutableListOf<WithdrawObservation>()
    val targetCloseEntries = mutableListOf<CloseObservation>()
    val masterCloseEntries = mutableListOf<CloseObservation>()
    val clientCloseEntries = mutableListOf<CloseObservation>()
    val denialCalls = mutableListOf<DenialObservation>()
    val forbiddenCalls = mutableListOf<String>()
    val hostNotices = mutableListOf<String>()
    val startupCallJobs = mutableListOf<Job>()
    val transferObservations = mutableListOf<TransferObservation>()
    private val uncertainWithdrawTokens = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
    private val withdrawAttempts = java.util.IdentityHashMap<Any, Int>()

    var retirementRequests = 0
    var repeatReopenRefusals = 0
    var repeatRetirementRefusals = 0
    var retirementDeferred: Deferred<Unit>? = null
    var startupJob: Job? = null
    var startupJobCompletedNormally = false
    var startupJobFailure: Throwable? = null
    var startupJobCompletedAtSequence: Int? = null
    var beforeRetirementCalls = 0
    var targetMaterialAtOpen: SelfOwnedNativeMaterialView? = null
    var targetContext: SelfOwnedLocalSecurityContext? = null
    var targetContextAlias: String = "CTX-UNRETURNED"
    var masterObject: LocalSetupMaster? = null
    var masterAlias: String = "MST-UNRETURNED"
    var clientObject: LocalSetupClient? = null
    var clientAlias: String = "CLIENT-UNRETURNED"
    var publishedOwner: Any? = null
    var publishedClient: LocalSetupClient? = null
    var controlledContext: ControlledContext? = null
    var staleTargetCloseAttempts: Int = 0
    var firstCloseOwner: String? = null
    var firstCloseClaimed = false
    var firstCloseGateEntered = false
    var firstClosePending = false
    private var defensiveToken: Any? = null
    private var defensiveTokenAlias: String? = null
    private val outcomeSignal = CompletableDeferred<Unit>()
    private var outcomeSignalRecorded = false
    private val gateEntered = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val gateReleased = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var closeOwnerSequence = 0
    private var publishedAtSequence = -1
    private var retirementRequestedAtSequence = -1

    private val masterProfile = scene.master?.let { MasterProfile.from(fixtures, it) }
    private val setupProfile = scene.setup?.let { SetupProfile.from(fixtures, it) }
    private val clientProfile = scene.client?.let { ClientProfile.from(fixtures, it) }
    private val targetProfile = scene.target?.let { TargetProfile.from(fixtures, it) }
    private val bindingProfile = scene.binding?.let { BindingProfile.from(fixtures, it) }
    private val withdrawProfile = WithdrawProfile.from(fixtures, scene.withdraw)
    private val closeTargetProfile = CloseProfile.from(fixtures, scene.closeTarget)
    private val closeMasterProfile = CloseProfile.from(fixtures, scene.closeMaster)
    private val closeClientProfile = CloseProfile.from(fixtures, scene.closeClient)
    private val firstCloseProfile = scene.firstClose.takeIf { it != "NONE" }?.let { CloseProfile.from(fixtures, it) }
    private val presentationProfile = fixtures.profile("presentations", scene.presentation)

    private val masterModeState: MutableStateFlow<LocalMasterLifecycleMode> =
        MutableStateFlow(masterProfile?.initialMode ?: LocalMasterLifecycleMode.OPERATIONAL)
    private val observedMasterMode: StateFlow<LocalMasterLifecycleMode> =
        object : StateFlow<LocalMasterLifecycleMode> by masterModeState {
            override val value: LocalMasterLifecycleMode
                get() {
                    val current = masterModeState.value
                    log.record("PRODUCTION", "master.mode", "observed", detail = current.name)
                    return current
                }
        }
    private var clientCurrent = clientProfile?.isCurrent ?: true
    private var teardownRequested = false
    private var retirementsJobCaptureArmed = false
    private var jobsBeforeRetirement: Set<Job> = emptySet()
    val retirementJobs = mutableListOf<Job>()
    private val targetCoordinates: SelfOwnedAllocationCoordinatesV1 = targetProfile?.let { profile ->
        ExistingOnlyStartupData.coordinatesWith(profile.coordinateSource, profile.foreignAt)
    } ?: ExistingOnlyStartupData.canonicalCoordinates
    private val bindingMaterial: SelfOwnedNativeMaterialView?
    private val bindingObject: SelfOwnedNativeBindingV1?

    init {
        fun coordinatesFor(source: String, foreignAt: Int?): SelfOwnedAllocationCoordinatesV1 = when (source) {
            "canonical" -> ExistingOnlyStartupData.canonicalCoordinates
            "foreign" -> ExistingOnlyStartupData.coordinatesWith("foreign", null)
            "target" -> targetCoordinates
            "canonical_with_foreign_at" -> ExistingOnlyStartupData.coordinatesWith("canonical", foreignAt)
            else -> error("${variant.variantId} has unknown coordinate source $source")
        }
        bindingMaterial = bindingProfile?.let { profile ->
            ExistingOnlyStartupData.material(coordinatesFor(profile.materialSource, profile.foreignIndex))
        }
        bindingObject = bindingProfile?.let { profile ->
            ExistingOnlyStartupData.binding(coordinatesFor(profile.bindingSource, profile.foreignIndex))
        }
    }

    val coordinator: LocalSetupCoordinator = LocalSetupCoordinator(
        services = ControlledServices(),
        employeeSetupIntents = LocalEmployeeSetupIntents(ForbiddenIntentStorage()),
        launchRecord = LocalMasterLaunchRecord(launchStorage),
        execution = execution,
        beforeRetirement = { _, result ->
            beforeRetirementCalls++
            retirementRequests++
            jobsBeforeRetirement = recording.jobs.toSet()
            retirementsJobCaptureArmed = true
            retirementDeferred = result
            log.record("PRODUCTION", "beforeRetirement", "enter", detail = "requests=$retirementRequests")
            result.invokeOnCompletion { failure ->
                log.record("PRODUCTION", "retirement", if (failure == null) "normal" else "exceptional",
                    detail = failure?.message ?: "")
            }
        },
        beforeClientHosting = { hostNotices += "beforeClientHosting"; log.record("PRODUCTION", "beforeClientHosting", "enter") },
        deviceAdmissionIntents = null,
        freshDevicePairingIntents = null,
        pairingDocuments = null,
    )

    val view: LocalSetupView get() = coordinator.state.value
    val masterFlowValue: LocalMasterLifecycleMode get() = masterModeState.value
    val masterInitialMode: LocalMasterLifecycleMode get() = masterProfile?.initialMode ?: LocalMasterLifecycleMode.OPERATIONAL
    val withdrawOutcome: String get() = withdrawProfile.outcome

    fun entry(): Job {
        coordinator.reopenRetainedMaster()
        val job = recording.jobs.firstOrNull()
            ?: throw AssertionError("${variant.variantId}: reopenRetainedMaster dispatched no startup job")
        startupJob = job
        job.invokeOnCompletion { failure ->
            startupJobCompletedNormally = failure == null
            startupJobFailure = failure
            startupJobCompletedAtSequence = log.size
            log.record("PRODUCTION", "startupJob", if (failure == null) "completed" else "completed-exceptionally",
                detail = failure?.message ?: "")
        }
        log.record("TEST", "reopenRetainedMaster", "issued", detail = "startupJob=${job.alias()}")
        return job
    }

    fun runUntilIdle() {
        scheduler.advanceUntilIdle()
        captureRetirementJobsIfArmed()
    }

    private fun captureRetirementJobsIfArmed() {
        if (!retirementsJobCaptureArmed) return
        recording.jobs.filter { job -> jobsBeforeRetirement.none { it === job } && job !== startupJob }
            .forEach { job -> if (retirementJobs.none { it === job }) retirementJobs += job }
        retirementsJobCaptureArmed = false
    }

    suspend fun settleStartup() {
        val job = assertNotNull(startupJob, "startup job was not captured")
        job.join()
        assertTrue(job.isCompleted, "${variant.variantId}: startup job did not complete")
        assertFalse(job.isCancelled, "${variant.variantId}: startup job was cancelled")
        log.record("TEST", "startupJob", "joined", detail = "job=${job.alias()}")
        if (startupJobFailure != null) {
            log.record("TEST", "startupJob", "completed-exceptionally", detail = startupJobFailure?.message ?: "")
        }
    }

    fun requestRetirement(): Deferred<Unit> {
        jobsBeforeRetirement = recording.jobs.toSet()
        val deferred = coordinator.requestRetirement(false)
        retirementDeferred = deferred
        retirementRequestedAtSequence = log.size
        retirementsJobCaptureArmed = true
        log.record("TEST", "requestRetirement", "issued", detail = "deferred=${deferred.alias()}")
        return deferred
    }

    fun repeatReopen() {
        val jobsBefore = recording.jobs.size
        val opensBefore = targetOpens.size
        val retirementsBefore = retirementRequests
        val withdrawsBefore = withdraws.size
        val closesBefore = closeEntryCount()
        coordinator.reopenRetainedMaster()
        runUntilIdle()
        assertEquals(jobsBefore, recording.jobs.size, "${variant.variantId}: repeat entry dispatched a new startup job")
        assertEquals(opensBefore, targetOpens.size, "${variant.variantId}: repeat entry acquired another target")
        assertEquals(retirementsBefore, retirementRequests, "${variant.variantId}: repeat entry requested retirement")
        assertEquals(withdrawsBefore, withdraws.size, "${variant.variantId}: repeat entry performed a new withdrawal")
        if (cleanupSettledOrClosed()) {
            assertEquals(closesBefore, closeEntryCount(), "${variant.variantId}: repeat entry performed new cleanup")
        }
        repeatReopenRefusals++
        log.record("TEST", "reopenRetainedMaster", "refused", detail = "repeat")
    }

    fun repeatRetirement() {
        val expected = assertNotNull(retirementDeferred, "${variant.variantId}: no retirement Deferred to repeat")
        val retirementsBefore = retirementRequests
        val withdrawsBefore = withdraws.size
        val closesBefore = closeEntryCount()
        val settledBefore = expected.isCompleted
        val actual = coordinator.requestRetirement(false)
        runUntilIdle()
        assertSame(expected, actual, "${variant.variantId}: repeat retirement returned another Deferred")
        assertEquals(retirementsBefore, retirementRequests, "${variant.variantId}: repeat retirement re-entered cleanup")
        assertEquals(withdrawsBefore, withdraws.size, "${variant.variantId}: repeat retirement performed a new withdrawal")
        if (settledBefore && expected.isCompleted) {
            assertEquals(closesBefore, closeEntryCount(), "${variant.variantId}: repeat retirement duplicated cleanup")
        }
        repeatRetirementRefusals++
        log.record("TEST", "requestRetirement", "refused", detail = "repeat")
    }

    private fun cleanupSettledOrClosed(): Boolean =
        view.phase == LocalSetupPhase.CLOSED || retirementDeferred?.isCompleted == true

    fun closeEntryCount(): Int = targetCloseEntries.size + masterCloseEntries.size + clientCloseEntries.size

    fun setHint(event: String) {
        val recordId = when (event) {
            "EV-SET-HINT-STOP" -> "REC-STOP"
            "EV-SET-HINT-UNRESOLVED-BOM" -> "REC-BOM"
            "EV-SET-HINT-FOREIGN-RUN" -> "REC-RUN-FOREIGN-ALL"
            else -> error("${variant.variantId}: unknown hint mutation $event")
        }
        val record = fixtures.record(recordId)
        launchStorage.testMutate(record.bytes, event)
    }

    fun invalidateClient(event: String) {
        assertEquals("EV-INVALIDATE-CLIENT", event, "${variant.variantId}: unexpected currentness event")
        clientCurrent = false
        log.record("TEST", "client.isCurrent", "mutated-false", alias = clientProfile?.alias ?: "CLIENT")
    }

    fun setMode(event: String) {
        val mode = when (event) {
            "EV-SET-MODE-ALLOCATION_ONLY" -> LocalMasterLifecycleMode.ALLOCATION_ONLY
            "EV-SET-MODE-INSTALLING" -> LocalMasterLifecycleMode.INSTALLING
            "EV-SET-MODE-INSTALLATION_ACK_PENDING" -> LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING
            "EV-SET-MODE-INSTALLED_RUNTIME_HELD" -> LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD
            "EV-SET-MODE-POLICY_HELD" -> LocalMasterLifecycleMode.POLICY_HELD
            "EV-SET-MODE-UNRESOLVED" -> LocalMasterLifecycleMode.UNRESOLVED
            "EV-SET-MODE-CLOSING" -> LocalMasterLifecycleMode.CLOSING
            "EV-SET-MODE-CLOSED" -> LocalMasterLifecycleMode.CLOSED
            else -> error("${variant.variantId}: unknown mode event $event")
        }
        masterModeState.value = mode
        log.record("TEST", "master.mode", "mutated", detail = mode.name)
    }

    fun performEvent(event: String) {
        when {
            event == "EV-REQUEST-RETIREMENT" -> requestRetirement()
            event == "EV-REPEAT-REOPEN" -> repeatReopen()
            event == "EV-REPEAT-RETIRE" -> repeatRetirement()
            event.startsWith("EV-SET-HINT-") -> setHint(event)
            event == "EV-INVALIDATE-CLIENT" -> invalidateClient(event)
            event.startsWith("EV-SET-MODE-") -> setMode(event)
            event == "EV-CANCEL-WAITER" -> error("${variant.variantId}: waiter cancellation is handled by the D8-01 driver")
            else -> error("${variant.variantId}: unknown event $event")
        }
    }

    fun enterGate(gate: String) {
        log.record("PRODUCTION", "gate", "enter", detail = gate)
        gateEntered.getOrPut(gate) { CompletableDeferred() }.complete(Unit)
    }

    suspend fun awaitGate(gate: String) {
        enterGate(gate)
        val released = gateReleased.getOrPut(gate) { CompletableDeferred() }
        if (teardownRequested) {
            released.complete(Unit)
            log.record("TEST", "gate", "teardown-release", detail = gate)
            return
        }
        released.await()
        log.record("PRODUCTION", "gate", "release", detail = gate)
    }

    fun releaseGate(gate: String) {
        gateReleased.getOrPut(gate) { CompletableDeferred() }.complete(Unit)
        log.record("TEST", "gate", "released", detail = gate)
    }

    fun gateWasEntered(gate: String): Boolean = gateEntered[gate]?.isCompleted == true

    fun gateEnterSequence(gate: String): Int? = log.all().firstOrNull {
        it.method == "gate" && it.phase == "enter" && it.detail == gate
    }?.sequence

    fun gateReleaseSequence(gate: String): Int? = log.all().firstOrNull {
        it.method == "gate" && it.phase in setOf("release", "released", "teardown-release") && it.detail == gate
    }?.sequence

    fun beginTeardown() {
        teardownRequested = true
    }

    fun releaseAllGates() {
        gateReleased.values.forEach { it.complete(Unit) }
    }

    fun jobs(): List<Job> = recording.jobs.toList()

    fun jobCensus(): List<String> = jobs().map { job ->
        val role = when {
            job === startupJob -> "startup"
            retirementJobs.any { it === job } -> "retirement-cleanup"
            publishes.isNotEmpty() -> "client-observer"
            else -> "unclassified"
        }
        "$role ${job.alias()} state=${jobState(job)}"
    }

    fun observerJobs(): List<Job> = jobs().filter { job ->
        job !== startupJob && retirementJobs.none { it === job }
    }

    fun jobState(job: Job): String = when {
        job.isCancelled -> "cancelled"
        job.isCompleted -> "completed"
        job.isActive -> "active"
        else -> "unknown"
    }

    fun unreleasedGates(): Set<String> = gateEntered.keys.filter { gate ->
        gateReleased[gate]?.isCompleted != true
    }.toSet()

    fun markOutcome(alias: String, outcome: String) {
        if (!outcomeSignalRecorded) {
            outcomeSignalRecorded = true
            outcomeSignal.complete(Unit)
        }
        log.record("PRODUCTION", alias, "outcome", detail = outcome)
    }

    suspend fun awaitOutcomeOrCompletion() {
        if (outcomeSignalRecorded) return
        val deferred = retirementDeferred
        if (deferred?.isCompleted == true) return
        // The scheduler has no timer source here: run all immediately runnable work, then
        // observe whether the outcome signal or the retirement Deferred completed.
        runUntilIdle()
        if (outcomeSignalRecorded || deferred?.isCompleted == true) return
        outcomeSignal.await()
    }

    suspend fun captureStartupJob(method: String) {
        val current = currentCoroutineContext()[Job] ?: return
        startupCallJobs += current
        log.record("PRODUCTION", method, "startup-job", detail = "job=${current.alias()}")
        val expected = startupJob
        if (expected != null) {
            assertSame(expected, current, "${variant.variantId}: $method ran on a non-startup job")
        }
    }

    fun firstCloseClaim(owner: String): CloseProfile? {
        if (firstCloseProfile == null) return null
        if (firstCloseClaimed) return null
        firstCloseClaimed = true
        firstCloseOwner = owner
        log.record("PRODUCTION", "firstClose", "entered", detail = owner)
        return firstCloseProfile
    }

    fun markFirstCloseEntered(profile: CloseProfile) {
        if (profile === firstCloseProfile) {
            firstCloseGateEntered = true
            firstClosePending = true
        }
    }

    fun markFirstCloseReturned(profile: CloseProfile) {
        if (profile === firstCloseProfile) firstClosePending = false
    }

    fun nextCloseSequence(): Int = ++closeOwnerSequence

    fun targetCoordinatesForAlias(): SelfOwnedAllocationCoordinatesV1 =
        targetMaterialAtOpen?.coordinates ?: ExistingOnlyStartupData.canonicalCoordinates

    fun withdrawalToken(owner: Any): String {
        val alias = when {
            publishedOwner != null && owner === publishedOwner -> "OWNER-PUBLISHED"
            owner === defensiveToken -> defensiveTokenAlias ?: "TOKEN-ACQUIRED"
            defensiveToken == null -> {
                defensiveToken = owner
                defensiveTokenAlias = "TOKEN-ACQUIRED"
                "TOKEN-ACQUIRED"
            }
            else -> "TOKEN-OTHER"
        }
        return alias
    }

    fun publishSequence() = publishedAtSequence
    fun retirementSequence() = retirementRequestedAtSequence

    inner class ControlledServices : LocalSetupNativeServices {
        override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult {
            forbiddenCalls += "openProvisioner(originalOnly=$originalOnly)"
            log.record("PRODUCTION", "openProvisioner", "forbidden", detail = "originalOnly=$originalOnly")
            throw ControlledFailure("EX-PROVISIONER")
        }

        override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult {
            captureStartupJob("openTarget")
            val observation = TargetOpenObservation(preparation, log.size)
            targetOpens += observation
            log.record("PRODUCTION", "openTarget", "enter", detail = "preparation=$preparation")
            if (preparation) {
                forbiddenCalls += "openTarget(preparation=true)"
                observation.outcome = "forbidden-preparation"
                throw ControlledFailure("EX-PREPARATION-TARGET")
            }
            val profile = targetProfile ?: error("${variant.variantId}: openTarget has no script")
            ledger.callInFlight(profile.contextAlias, "target-context")
            profile.holdGate?.let { awaitGate(it) }
            val result = when (profile.result) {
                "opened" -> {
                    val coordinates = ExistingOnlyStartupData.coordinatesWith(profile.coordinateSource, profile.foreignAt)
                    val material = ExistingOnlyStartupData.material(coordinates)
                    val controller = ControlledContext(material)
                    val context = SelfOwnedLocalSecurityContext(controller)
                    controlledContext = controller
                    targetMaterialAtOpen = material
                    targetContext = context
                    targetContextAlias = profile.contextAlias
                    ledger.held(profile.contextAlias, "target-context")
                    observation.outcome = "opened"
                    observation.contextAlias = profile.contextAlias
                    observation.coordinates = coordinates
                    log.record("PRODUCTION", "openTarget", "opened", alias = profile.contextAlias)
                    SelfOwnedNativeOpenResult.Opened(context)
                }
                "unresolved" -> {
                    ledger.failedAcquisition(profile.contextAlias)
                    observation.outcome = "unresolved"
                    observation.reason = profile.reason
                    log.record("PRODUCTION", "openTarget", "unresolved", detail = profile.reasonAlias ?: "")
                    SelfOwnedNativeOpenResult.Unresolved(profile.reason)
                }
                "throw" -> {
                    ledger.failedAcquisition(profile.contextAlias)
                    observation.outcome = "throw"
                    log.record("PRODUCTION", "openTarget", "throw", detail = profile.exceptionAlias ?: "")
                    throw ControlledFailure(profile.exceptionAlias ?: "EX-TARGET")
                }
                else -> error("${variant.variantId}: unknown target result ${profile.result}")
            }
            return result
        }

        override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult {
            captureStartupJob("openMaster")
            val observation = MasterOpenObservation(context, log.size)
            masterOpens += observation
            log.record("PRODUCTION", "openMaster", "enter", alias = context.alias())
            assertSame(targetContext, context, "${variant.variantId}: openMaster did not receive the exact opened context")
            val profile = masterProfile ?: error("${variant.variantId}: openMaster has no script")
            profile.holdGate?.let { awaitGate(it) }
            return when (profile.result) {
                "opened" -> {
                    val master = ControlledMaster(profile, context, targetContextAlias)
                    masterObject = master
                    masterAlias = profile.alias
                    ledger.held(profile.alias, "master")
                    observation.outcome = "opened"
                    observation.masterAlias = profile.alias
                    // The real Android original-master open claims native custody at its sole
                    // handoff linearization point and only then returns Opened. Record that
                    // public-contract fact here; no assertion infers transfer from object presence.
                    val transfer = TransferObservation(context, targetContextAlias, master, profile.alias, log.size)
                    transferObservations += transfer
                    controlledContext?.transferredMaster = master
                    ledger.transfer(targetContextAlias, profile.alias)
                    log.record("PRODUCTION", "openMaster", "custody-transferred", alias = targetContextAlias,
                        detail = "to ${profile.alias}")
                    log.record("PRODUCTION", "openMaster", "opened", alias = profile.alias)
                    LocalSetupMasterOpenResult.Opened(master)
                }
                "unresolved" -> {
                    observation.outcome = "unresolved"
                    ledger.heldTransferUnknown(targetContextAlias)
                    log.record("PRODUCTION", "openMaster", "unresolved", detail = profile.reasonAlias ?: "")
                    log.record("PRODUCTION", "custody", "held-transfer-unknown", alias = targetContextAlias)
                    LocalSetupMasterOpenResult.Unresolved(profile.reason)
                }
                "throw" -> {
                    observation.outcome = "throw"
                    ledger.heldTransferUnknown(targetContextAlias)
                    log.record("PRODUCTION", "openMaster", "throw", detail = profile.exceptionAlias ?: "")
                    log.record("PRODUCTION", "custody", "held-transfer-unknown", alias = targetContextAlias)
                    throw ControlledFailure(profile.exceptionAlias ?: "EX-MASTER")
                }
                else -> error("${variant.variantId}: unknown master result ${profile.result}")
            }
        }

        override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult {
            forbiddenCalls += "openOriginalReceipt"
            log.record("PRODUCTION", "openOriginalReceipt", "forbidden")
            throw ControlledFailure("EX-RECEIPT")
        }

        override suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient? {
            captureStartupJob("connectClient")
            val observation = ClientConnectObservation(master, action, log.size)
            clientConnects += observation
            log.record("PRODUCTION", "connectClient", "enter", alias = master.alias(), detail = action.name)
            assertSame(masterObject, master, "${variant.variantId}: connectClient did not receive the exact opened Master")
            if (action != LocalOriginalClientJournalAction.REOPEN_EXISTING) {
                forbiddenCalls += "connectClient(${action.name})"
            }
            val profile = clientProfile ?: error("${variant.variantId}: connectClient has no script")
            profile.holdGate?.let { awaitGate(it) }
            return when (profile.result) {
                "returned" -> {
                    val client = ControlledClient(profile)
                    clientObject = client
                    clientAlias = profile.alias
                    ledger.held(profile.alias, "client")
                    observation.outcome = "returned"
                    observation.clientAlias = profile.alias
                    log.record("PRODUCTION", "connectClient", "returned", alias = profile.alias)
                    client
                }
                "null" -> {
                    observation.outcome = "null"
                    log.record("PRODUCTION", "connectClient", "null")
                    null
                }
                "throw" -> {
                    observation.outcome = "throw"
                    log.record("PRODUCTION", "connectClient", "throw", detail = profile.exceptionAlias ?: "")
                    throw ControlledFailure(profile.exceptionAlias ?: "EX-CLIENT")
                }
                else -> error("${variant.variantId}: unknown client result ${profile.result}")
            }
        }

        override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean {
            forbiddenCalls += "ownsEmployeeConnection"
            log.record("PRODUCTION", "ownsEmployeeConnection", "forbidden")
            return false
        }

        override fun publishClient(owner: Any, client: LocalSetupClient) {
            val observation = PublishObservation(owner, client, log.size)
            publishes += observation
            if (publishedOwner == null) {
                publishedOwner = owner
                publishedClient = client
                publishedAtSequence = log.size
                log.record("PRODUCTION", "publishClient", "enter", alias = "OWNER-PUBLISHED", detail = "client=${client.alias()}")
            } else {
                log.record("PRODUCTION", "publishClient", "duplicate", alias = "OWNER-PUBLISHED")
            }
            if (client !== clientObject) {
                forbiddenCalls += "publishClient(other-client)"
            }
        }

        override fun withdrawClient(owner: Any) {
            val alias = withdrawalToken(owner)
            val attempts = (withdrawAttempts[owner] ?: 0) + 1
            withdrawAttempts[owner] = attempts
            if (uncertainWithdrawTokens.contains(owner) || attempts > 1 && withdrawProfile.outcome == "THROW") {
                forbiddenCalls += "withdrawClient(uncertain-token-retry)"
            }
            val observation = WithdrawObservation(owner, alias, withdrawProfile.outcome, log.size)
            withdraws += observation
            log.record("PRODUCTION", "withdrawClient", "enter", alias = alias,
                detail = "${withdrawProfile.outcome}; attempt=$attempts")
            when (withdrawProfile.outcome) {
                "FORBIDDEN" -> {
                    forbiddenCalls += "withdrawClient($alias)"
                    observation.outcome = "forbidden"
                    log.record("PRODUCTION", "withdrawClient", "forbidden", alias = alias)
                }
                "CONDITIONAL" -> {
                    observation.outcome = "unit"
                    log.record("PRODUCTION", "withdrawClient", "unit", alias = alias)
                }
                "THROW", "THROW_IF_ENTERED" -> {
                    uncertainWithdrawTokens += owner
                    observation.outcome = "throw"
                    markOutcome("withdrawClient", "throw")
                    log.record("PRODUCTION", "withdrawClient", "throw", alias = alias)
                    throw ControlledFailure("EX-WITHDRAW")
                }
                else -> error("${variant.variantId}: unknown withdraw outcome ${withdrawProfile.outcome}")
            }
        }
    }

    inner class ControlledContext(
        override val materialAtOpen: SelfOwnedNativeMaterialView,
    ) : SelfOwnedNativeContextController {
        override val compositionProfile: com.ukkera.pos.security.LocalCompositionSdkProfileV1
            get() = com.ukkera.pos.security.LocalMasterSdkProfileV1
        var transferredMaster: LocalSetupMaster? = null
            set(value) {
                field = value
                log.record("PRODUCTION", "target.context", "transferred", alias = targetContextAlias,
                    detail = (value as? ControlledMaster)?.alias ?: "master")
            }
        override suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult {
            captureStartupJob("readOriginalNativeBinding")
            val profile = bindingProfile ?: error("${variant.variantId}: binding read has no script")
            val observation = BindingObservation(log.size, profile.result)
            bindingReads += observation
            log.record("PRODUCTION", "readOriginalNativeBinding", "enter")
            profile.holdGate?.let { awaitGate(it) }
            return when (profile.result) {
                "retained" -> {
                    val material = assertNotNull(bindingMaterial, "${variant.variantId}: binding material is missing")
                    val binding = assertNotNull(bindingObject, "${variant.variantId}: binding object is missing")
                    observation.outcome = "retained"
                    observation.material = material
                    log.record("PRODUCTION", "readOriginalNativeBinding", "retained")
                    SelfOwnedNativeReadResult.Retained(material, binding)
                }
                "unresolved" -> {
                    observation.outcome = "unresolved"
                    log.record("PRODUCTION", "readOriginalNativeBinding", "unresolved", detail = profile.reasonAlias ?: "")
                    SelfOwnedNativeReadResult.Unresolved(profile.reason)
                }
                "throw" -> {
                    observation.outcome = "throw"
                    log.record("PRODUCTION", "readOriginalNativeBinding", "throw", detail = profile.exceptionAlias ?: "")
                    throw ControlledFailure(profile.exceptionAlias ?: "EX-BINDING")
                }
                else -> error("${variant.variantId}: unknown binding result ${profile.result}")
            }
        }

        override suspend fun withdrawNewCeremonies(): SelfOwnedNativeWithdrawal {
            forbiddenCalls += "withdrawNewCeremonies"
            log.record("PRODUCTION", "withdrawNewCeremonies", "forbidden")
            return SelfOwnedNativeWithdrawal.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        }

        override fun denyContextNewCeremonies() {
            denialCalls += DenialObservation("target.denyNewCeremonies", targetContextAlias, log.size)
            log.record("PRODUCTION", "target.denyNewCeremonies", "enter", alias = targetContextAlias)
        }

        override fun denyContextDataUse() {
            denialCalls += DenialObservation("target.denyDataUse", targetContextAlias, log.size)
            log.record("PRODUCTION", "target.denyDataUse", "enter", alias = targetContextAlias)
        }

        override suspend fun closeContext() {
            val transferred = transferredMaster
            if (transferred != null) {
                staleTargetCloseAttempts++
                log.record("PRODUCTION", "target.close", "stale-after-transfer", alias = targetContextAlias)
                return // The public original-context contract is inert after the sole handoff.
            }
            targetCloseEntries += CloseObservation("target", log.size)
            val entry = targetCloseEntries.last()
            log.record("PRODUCTION", "target.close", "enter", alias = targetContextAlias)
            val profile = if (closeTargetProfile.optional) closeTargetProfile else firstCloseClaim("target") ?: closeTargetProfile
            entry.profile = profile
            entry.owner = "target"
            if (profile.outcome == "NONE") {
                forbiddenCalls += "target.close(unscripted)"
                log.record("PRODUCTION", "target.close", "unscripted")
                return
            }
            if (profile.optional) {
                ledger.optionalCloseObserved(targetContextAlias)
                when (profile.outcome) {
                    "CONFIRM" -> {
                        entry.outcome = "optional-unit"
                        log.record("PRODUCTION", "target.close", "optional-diagnostic-unit",
                            alias = targetContextAlias)
                        return
                    }
                    "THROW" -> {
                        entry.outcome = "optional-throw"
                        markOutcome("target.close", "optional-throw")
                        log.record("PRODUCTION", "target.close", "optional-diagnostic-throw",
                            alias = targetContextAlias)
                        throw ControlledFailure("EX-TARGET-CLOSE-OPTIONAL")
                    }
                    else -> error("${variant.variantId}: unsupported optional target close ${profile.outcome}")
                }
            }
            ledger.closeEntered(targetContextAlias)
            if (profile.gate != null) {
                entry.held = true
                markFirstCloseEntered(profile)
                awaitGate(profile.gate)
            }
            when (profile.outcome) {
                "CONFIRM", "HELD_THEN_CONFIRM" -> {
                    entry.outcome = "confirm"
                    ledger.confirm(targetContextAlias)
                    markFirstCloseReturned(profile)
                    log.record("PRODUCTION", "target.close", "confirm")
                }
                "THROW", "HELD_THEN_THROW" -> {
                    entry.outcome = "throw"
                    ledger.unconfirm(targetContextAlias)
                    markFirstCloseReturned(profile)
                    markOutcome("target.close", "throw")
                    log.record("PRODUCTION", "target.close", "throw")
                    throw ControlledFailure("EX-TARGET-CLOSE")
                }
                else -> error("${variant.variantId}: target close outcome ${profile.outcome} is not supported")
            }
        }

        override suspend fun claimOriginalHistoryCustody(): SelfOwnedNativeHistoryClaimResult {
            forbiddenCalls += "claimOriginalHistoryCustody"
            log.record("PRODUCTION", "claimOriginalHistoryCustody", "forbidden")
            return SelfOwnedNativeHistoryClaimResult.Unresolved(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        }
    }

    private inner class ControlledMaster(
        private val profile: MasterProfile,
        private val custodyContext: SelfOwnedLocalSecurityContext,
        private val custodyAlias: String,
    ) : LocalSetupMaster {
        val alias: String get() = profile.alias
        override suspend fun readOwnedOriginalNativeBinding(): SelfOwnedNativeReadResult =
            custodyContext.readOriginalNativeBinding()
        override val mode = observedMasterMode
        override suspend fun permitsFirstClientEnrollment(): Boolean {
            log.record("PRODUCTION", "master.permitsFirstClientEnrollment", "enter")
            return false
        }
        override suspend fun readOriginalSetup(): LocalOriginalInstallationSetup {
            captureStartupJob("readOriginalSetup")
            val script = setupProfile ?: error("${variant.variantId}: setup read has no script")
            setupReads += SetupObservation(script.definition, script.receipt, script.throwRead, log.size)
            log.record("PRODUCTION", "master.readOriginalSetup", "enter", detail = script.alias)
            if (script.throwRead) {
                log.record("PRODUCTION", "master.readOriginalSetup", "throw")
                throw ControlledFailure("EX-SETUP")
            }
            log.record("PRODUCTION", "master.readOriginalSetup", "returned", detail = script.alias)
            return ExistingOnlyStartupData.setup(script.definitionPresent, script.receiptPresent)
        }
        override suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning, authorityEpoch: Revision):
            LocalBranchInstallationSnapshotV2 {
            forbiddenCalls += "prepareOriginalDefinition"
            log.record("PRODUCTION", "master.prepareOriginalDefinition", "forbidden")
            throw ControlledFailure("EX-PREPARE-DEFINITION")
        }
        override suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2, headLogin: String,
            headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome {
            headSecret.fill('\u0000'); employeePassword.fill('\u0000')
            forbiddenCalls += "installOriginal"
            log.record("PRODUCTION", "master.installOriginal", "forbidden")
            throw ControlledFailure("EX-INSTALL")
        }
        override suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome {
            forbiddenCalls += "resumeOriginalAcknowledgement"
            log.record("PRODUCTION", "master.resumeOriginalAcknowledgement", "forbidden")
            throw ControlledFailure("EX-ACKNOWLEDGE")
        }
        override fun denyNewWork() {
            denialCalls += DenialObservation("master.denyNewWork", masterAlias, log.size)
            log.record("PRODUCTION", "master.denyNewWork", "enter", alias = masterAlias)
        }
        override suspend fun quiesceAndClose(): Boolean {
            assertSame(targetContext, custodyContext,
                "${variant.variantId}: Master close does not own the captured target custody")
            masterCloseEntries += CloseObservation("master", log.size)
            val entry = masterCloseEntries.last()
            ledger.closeEntered(masterAlias)
            log.record("PRODUCTION", "master.quiesceAndClose", "enter", alias = masterAlias,
                detail = "custody=$custodyAlias")
            val script = firstCloseClaim("master") ?: closeMasterProfile
            entry.profile = script
            entry.owner = "master"
            if (script.outcome == "NONE") {
                forbiddenCalls += "master.close(unscripted)"
                log.record("PRODUCTION", "master.quiesceAndClose", "unscripted")
                return true
            }
            if (script.gate != null) {
                entry.held = true
                markFirstCloseEntered(script)
                awaitGate(script.gate)
            }
            return when (script.outcome) {
                "CONFIRM", "HELD_THEN_CONFIRM" -> {
                    entry.outcome = "true"
                    ledger.confirm(masterAlias)
                    markFirstCloseReturned(script)
                    log.record("PRODUCTION", "master.quiesceAndClose", "true")
                    true
                }
                "FALSE", "HELD_THEN_FALSE" -> {
                    entry.outcome = "false"
                    ledger.unconfirm(masterAlias)
                    markFirstCloseReturned(script)
                    markOutcome("master.quiesceAndClose", "false")
                    log.record("PRODUCTION", "master.quiesceAndClose", "false")
                    false
                }
                "THROW", "HELD_THEN_THROW" -> {
                    entry.outcome = "throw"
                    ledger.unconfirm(masterAlias)
                    markFirstCloseReturned(script)
                    markOutcome("master.quiesceAndClose", "throw")
                    log.record("PRODUCTION", "master.quiesceAndClose", "throw")
                    throw ControlledFailure("EX-MASTER-CLOSE")
                }
                else -> error("${variant.variantId}: master close outcome ${script.outcome} is not supported")
            }
        }
    }

    private inner class ControlledClient(private val profile: ClientProfile) : LocalSetupClient {
        override fun isCurrent(): Boolean {
            log.record("PRODUCTION", "client.isCurrent", "observed", alias = profile.alias, detail = clientCurrent.toString())
            return clientCurrent
        }
        override fun denyNewWork() {
            denialCalls += DenialObservation("client.denyNewWork", clientAlias, log.size)
            log.record("PRODUCTION", "client.denyNewWork", "enter", alias = clientAlias)
        }
        override suspend fun close() {
            clientCloseEntries += CloseObservation("client", log.size)
            val entry = clientCloseEntries.last()
            ledger.closeEntered(clientAlias)
            log.record("PRODUCTION", "client.close", "enter", alias = profile.alias)
            val script = firstCloseClaim("client") ?: closeClientProfile
            entry.profile = script
            entry.owner = "client"
            if (script.outcome == "NONE") {
                forbiddenCalls += "client.close(unscripted)"
                log.record("PRODUCTION", "client.close", "unscripted", alias = profile.alias)
                return
            }
            if (script.gate != null) {
                entry.held = true
                markFirstCloseEntered(script)
                awaitGate(script.gate)
            }
            when (script.outcome) {
                "CONFIRM", "HELD_THEN_CONFIRM" -> {
                    entry.outcome = "confirm"
                    ledger.confirm(clientAlias)
                    markFirstCloseReturned(script)
                    log.record("PRODUCTION", "client.close", "confirm", alias = profile.alias)
                }
                "THROW", "HELD_THEN_THROW" -> {
                    entry.outcome = "throw"
                    ledger.unconfirm(clientAlias)
                    markFirstCloseReturned(script)
                    markOutcome("client.close", "throw")
                    log.record("PRODUCTION", "client.close", "throw", alias = profile.alias)
                    throw ControlledFailure("EX-CLIENT-CLOSE")
                }
                else -> error("${variant.variantId}: client close outcome ${script.outcome} is not supported")
            }
        }

        override suspend fun prepareEmployeeEnrollment(requestId: DomainId, definition: LocalAccountProvisioning):
            LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> = forbiddenEmployee("prepareEmployeeEnrollment")
        override suspend fun approveEmployeeEnrollment(action: LocalEmployeeEnrollmentAction, headLogin: String,
            headSecret: CharArray): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> {
            headSecret.fill('\u0000'); return forbiddenEmployee("approveEmployeeEnrollment")
        }
        override suspend fun recoverEmployeeEnrollmentApproval(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> = forbiddenEmployee("recoverEmployeeEnrollmentApproval")
        override suspend fun recoverEmployeeEnrollmentConnection(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> = forbiddenEmployee("recoverEmployeeEnrollmentConnection")
        override suspend fun reviewEmployeeEnrollment(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> = forbiddenEmployee("reviewEmployeeEnrollment")
        override suspend fun retainedEmployeeEnrollments():
            LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> = forbiddenEmployee("retainedEmployeeEnrollments")
        override suspend fun enrollApprovedEmployee(originalRequestId: DomainId, password: CharArray): LocalEnrollmentResult {
            password.fill('\u0000'); return forbiddenEmployee("enrollApprovedEmployee")
        }
        override suspend fun lookupEmployeeEnrollment(originalRequestId: DomainId): LocalEnrollmentResult =
            forbiddenEmployee("lookupEmployeeEnrollment")
        override suspend fun acquireEmployeeIntentJournal(originalRequestId: DomainId,
            action: NativeEmployeeClientJournalAction): Boolean = forbiddenEmployee("acquireEmployeeIntentJournal")
        override suspend fun prepareEmployeePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy):
            LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = forbiddenEmployee("prepareEmployeePolicy")
        override suspend fun approveEmployeePolicy(action: LocalEmployeePolicyActivationAction, headLogin: String,
            headSecret: CharArray): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
            headSecret.fill('\u0000'); return forbiddenEmployee("approveEmployeePolicy")
        }
        override suspend fun reviewEmployeePolicy(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = forbiddenEmployee("reviewEmployeePolicy")
        override suspend fun recoverEmployeePolicyApproval(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = forbiddenEmployee("recoverEmployeePolicyApproval")
        override suspend fun recoverEmployeePolicyConnection(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = forbiddenEmployee("recoverEmployeePolicyConnection")
        override suspend fun retainedEmployeePolicies():
            LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> = forbiddenEmployee("retainedEmployeePolicies")
        override suspend fun activateEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
            nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult = forbiddenEmployee("activateEmployeePolicy")
        override suspend fun lookupEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
            nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus = forbiddenEmployee("lookupEmployeePolicy")
        override suspend fun prepareSdkBoundDevicePairing(requestId: DomainId,
            identity: LocalDevicePairingSdkIdentityV2): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
            forbiddenEmployee("prepareSdkBoundDevicePairing")
        override suspend fun proveDevicePairingTarget(action: LocalDevicePairingAction,
            possession: LocalDevicePairingPossession): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
            forbiddenEmployee("proveDevicePairingTarget")
        override suspend fun retainedDevicePairings():
            LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> = forbiddenEmployee("retainedDevicePairings")
        override suspend fun reviewDevicePairing(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalDevicePairingAction> = forbiddenEmployee("reviewDevicePairing")
        override suspend fun approveDevicePairing(action: LocalDevicePairingAction, headLogin: String,
            headSecret: CharArray): LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
            headSecret.fill('\u0000'); return forbiddenEmployee("approveDevicePairing")
        }
        override suspend fun recoverDevicePairingApproval(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalDevicePairingApproval> = forbiddenEmployee("recoverDevicePairingApproval")
        override suspend fun retireKnownUnsignedDevicePairing(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> = forbiddenEmployee("retireKnownUnsignedDevicePairing")
        override suspend fun recoverUnsignedDevicePairingRetirement(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> = forbiddenEmployee("recoverUnsignedDevicePairingRetirement")
        override suspend fun admitOriginalDevice(approval: LocalDevicePairingApproval):
            LocalCredentialAdministrationResult = forbiddenEmployee("admitOriginalDevice")
        override suspend fun lookupOriginalDeviceAdmission(originalActionId: DomainId): LocalProvisioningStatus =
            forbiddenEmployee("lookupOriginalDeviceAdmission")

        private fun <T> forbiddenEmployee(method: String): T {
            forbiddenCalls += "client.$method"
            log.record("PRODUCTION", "client.$method", "forbidden")
            throw ControlledFailure("EX-EMPLOYEE-EFFECT")
        }
    }

    private inner class ForbiddenIntentStorage : LocalSetupIntentStorage {
        override fun names(): List<String> {
            forbiddenCalls += "employeeIntent.names"
            log.record("PRODUCTION", "employeeIntent.names", "forbidden")
            throw ControlledFailure("EX-EMPLOYEE-INTENT")
        }
        override fun read(name: String): ByteArray {
            forbiddenCalls += "employeeIntent.read"
            log.record("PRODUCTION", "employeeIntent.read", "forbidden")
            throw ControlledFailure("EX-EMPLOYEE-INTENT")
        }
        override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) {
            forbiddenCalls += "employeeIntent.write"
            log.record("PRODUCTION", "employeeIntent.write", "forbidden")
            throw ControlledFailure("EX-EMPLOYEE-INTENT")
        }
        override fun verify() {
            forbiddenCalls += "employeeIntent.verify"
            log.record("PRODUCTION", "employeeIntent.verify", "forbidden")
        }
    }

    fun positiveTransfer(): TransferObservation? = transferObservations.singleOrNull()

    fun optionalTargetClose(): Boolean = closeTargetProfile.optional

    fun closeTargetProfileForAssertion(): CloseProfile = closeTargetProfile

    fun bindingObjectForAssertion(): SelfOwnedNativeBindingV1? = bindingObject

    fun fingerprintBinding(binding: SelfOwnedNativeBindingV1): String {
        val bytes = binding.copyWireBytes()
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }

    fun fingerprintCoordinates(source: String, foreignAt: Int? = null): String {
        val binding = ExistingOnlyStartupData.binding(ExistingOnlyStartupData.coordinatesWith(source, foreignAt))
        return fingerprintBinding(binding)
    }

    fun clientProfileResult(): String = clientProfile?.result ?: "none"

    fun setupProfileDefinition(): String = setupProfile?.definition ?: "absent"

    fun setupProfileReceipt(): String = setupProfile?.receipt ?: "absent"

    fun setupProfileThrow(): Boolean = setupProfile?.throwRead ?: false

    fun targetContextCloseEntered(): Boolean = targetCloseEntries.isNotEmpty()

    fun observerWatching(): Boolean = execution.tick != null

    fun firstRetirementCloseSequence(): Int? =
        (targetCloseEntries + masterCloseEntries + clientCloseEntries).minOfOrNull { it.sequence }
}

internal class ControlledLaunchStorage(
    recordId: String,
    fixtures: ExistingOnlyStartupFixtures,
    private val log: StartupObservationLog,
) : LocalMasterLaunchStorage {
    private val initial = fixtures.record(recordId)
    var bytes: ByteArray? = initial.bytes?.copyOf()
        private set
    var readThrows: Boolean = initial.readThrows
        private set
    var reads: Int = 0
        private set
    var writes: Int = 0
        private set

    override fun read(): ByteArray? {
        reads++
        if (readThrows) {
            log.record("PRODUCTION", "launchStorage.read", "throw", detail = "read=$reads")
            throw ControlledFailure("EX-LAUNCH-READ")
        }
        val current = bytes?.copyOf()
        log.record("PRODUCTION", "launchStorage.read", "returned",
            detail = "read=$reads bytes=${current?.size ?: "absent"} digest=${current?.let { sha256(it) } ?: "absent"}")
        return current
    }

    override fun replaceAndForce(bytes: ByteArray) {
        writes++
        this.bytes = bytes.copyOf()
        log.record("PRODUCTION", "launchStorage.write", "applied", detail = "write=$writes")
    }

    fun testMutate(bytes: ByteArray?, event: String) {
        this.bytes = bytes?.copyOf()
        readThrows = false
        log.record("TEST", "launchStorage.mutate", "applied",
            detail = "$event bytes=${bytes?.size ?: "absent"}")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ControlledExecution(
    private val recording: RecordingDispatcher,
    private val scheduler: TestCoroutineScheduler,
) : LocalSetupExecution {
    override val dispatcher: CoroutineDispatcher get() = recording
    var tick: (() -> Unit)? = null
        private set
    override fun elapsedMillis(): Long = scheduler.currentTime
    override fun startWatching(tick: () -> Unit) { this.tick = tick }
    override fun stopWatching() { tick = null }
}

internal class RecordingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
    val jobs = mutableListOf<Job>()
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        context[Job]?.let { job -> if (jobs.none { it === job }) jobs += job }
        delegate.dispatch(context, block)
    }
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = delegate.isDispatchNeeded(context)
}

internal class ControlledFailure(message: String) : RuntimeException(message)

internal fun Job.alias(): String = "JOB-${hashCode().toUInt().toString(16)}"
internal fun Any.alias(): String = when (this) {
    is LocalSetupMaster -> "MST-OBJECT"
    is LocalSetupClient -> "CLIENT-OBJECT"
    is SelfOwnedLocalSecurityContext -> "CTX-OBJECT"
    is Deferred<*> -> "DEFERRED-${hashCode().toUInt().toString(16)}"
    else -> "OWNER-${hashCode().toUInt().toString(16)}"
}
