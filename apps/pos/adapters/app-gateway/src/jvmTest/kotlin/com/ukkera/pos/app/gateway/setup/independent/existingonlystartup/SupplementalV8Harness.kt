package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
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
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalBranchInstallationDefinitionV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthority
import com.ukkera.pos.security.LocalCompanyAuthorityFailure
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompanyAuthorityView
import com.ukkera.pos.security.LocalCompanyFoundingAction
import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyPolicyAction
import com.ukkera.pos.security.LocalCompanyPolicyProposal
import com.ukkera.pos.security.LocalCompanyPolicyPublication
import com.ukkera.pos.security.LocalCompanyInstallationAction
import com.ukkera.pos.security.LocalCompanyInstallationApproval
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingMasterTransfer
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.LocalInstallationHumanReferenceV2
import com.ukkera.pos.security.LocalProvisioner
import com.ukkera.pos.security.LocalProvisionerCompanyView
import com.ukkera.pos.security.LocalProvisionerEnrollment
import com.ukkera.pos.security.LocalProvisionerGrant
import com.ukkera.pos.security.LocalProvisionerInspection
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalProvisionerPendingAction
import com.ukkera.pos.security.LocalProvisionerPreparation
import com.ukkera.pos.security.LocalProvisionerWithdrawal
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.OriginalAttemptRetirement
import com.ukkera.pos.security.OriginalInstallationCompletion
import com.ukkera.pos.security.OriginalInstallationProposal
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedBranchFactsV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.SelfOwnedNativeReadResult
import com.ukkera.pos.security.SelfOwnedPreparationPacketV1
import com.ukkera.pos.security.OriginalAllocationCommitmentV2
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Input script derived only from the frozen supplemental-v8 inventory row. */
internal class SupplementalScript(val variant: SupplementalVariant) {
    val family: String = variant.caseId.substringAfter("E3RA-").take(1)
    val isRefreshFamily: Boolean get() = family == "R"

    var startupMaster: String = "opened"
    var startupMasterMode: LocalMasterLifecycleMode = LocalMasterLifecycleMode.OPERATIONAL
    var startupClient: String = "returned"
    var startupSetup: SetupScript = SetupScript(true, true, false)
    var refreshSetup: SetupScript = SetupScript(true, true, false)
    var refreshBinding: BindingScript = BindingScript("agree", 0, null)
    var refreshRecordThrow: Boolean = false
    var currentness: String = "true"
    var refreshTarget: String = "opened"
    var refreshMaster: String = "opened"
    var refreshMasterMode: LocalMasterLifecycleMode = LocalMasterLifecycleMode.OPERATIONAL
    var refreshClient: String = "returned"
    var closeClient: String = "confirm"
    var closeMaster: String = "confirm"
    var closeTarget: String = "none"
    var bindingGate: String? = null
    var masterGate: String? = null
    var clientGate: String? = null
    var closeGate: String? = null
    var provisionerOpenGate: String? = null
    var provisionerCloseGate: String? = null
    var denyIssuance: String = "unit"
    var denyHead: String = "unit"
    var provisionerClose: String = "unit"
    var provisionerInspection: String = "enrolled"

    // Late-refresh-v3 additive controls. Defaults keep every accepted v8 row byte/behaviour
    // identical; the late driver alone enables the optional setup gate and the controlled
    // publication-withdrawal semantics.
    var lateSetupGate: String? = null
    var lateRefreshSetup: SetupScript? = null
    var latePublicationSemantics: Boolean = false
    var lateWithdrawalThrow: Boolean = false

    init {
        startupSetup = setupFor(variant.setupProfile, startup = true)
        refreshSetup = setupFor(variant.setupProfile, startup = false)
        when (val profile = variant.bindingProfile) {
            "BND-AGREE" -> refreshBinding = BindingScript("agree", 0, null)
            "BND-UNAVAILABLE" -> refreshBinding = BindingScript("unresolved", 0,
                SelfOwnedInstallationFailureV1.UNAVAILABLE)
            "BND-DENIED" -> refreshBinding = BindingScript("unresolved", 0,
                SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
            "BND-THROW" -> refreshBinding = BindingScript("throw", 0, null)
            else -> {
                val match = Regex("BND-CHANGED-(\\d{2})").matchEntire(profile)
                    ?: error("${variant.variantId}: unknown binding profile $profile")
                val index = match.groupValues[1].toInt() - 1
                require(index in 0..11) { "${variant.variantId}: binding index out of range" }
                refreshBinding = BindingScript("changed", index, null)
            }
        }
        refreshRecordThrow = variant.recordProfile == "REC-READ-THROW"
        when (val native = variant.nativeProfile) {
            "NAT-POSITIVE", "NAT-HELD-BINDING" -> Unit
            "R-NAT-TARGET-UNRESOLVED" -> refreshTarget = "unresolved"
            "R-NAT-TARGET-THROW" -> refreshTarget = "throw"
            "R-NAT-MASTER-UNRESOLVED" -> refreshMaster = "unresolved"
            "R-NAT-MASTER-THROW" -> refreshMaster = "throw"
            "R-NAT-MASTER-POLICY-HELD" -> {
                refreshMaster = "opened"
                refreshMasterMode = LocalMasterLifecycleMode.POLICY_HELD
            }
            "R-NAT-CLIENT-STALE" -> currentness = "false"
            "R-NAT-CLIENT-NULL" -> refreshClient = "null"
            "R-NAT-CLIENT-THROW" -> refreshClient = "throw"
            "R-NAT-CLIENT-CURRENTNESS-THROW" -> currentness = "throw"
            "B-NAT-MASTER-THROW-IMMEDIATE" -> startupMaster = "throw"
            "B-NAT-MASTER-THROW-HELD-RETIRE" -> {
                startupMaster = "throw"
                masterGate = "G-MASTER-OPEN"
            }
            "B-NAT-MASTER-THROW-OPTIONAL-TARGET-UNIT" -> {
                startupMaster = "throw"
                closeTarget = "confirm"
            }
            "B-NAT-CLIENT-THROW-IMMEDIATE" -> startupClient = "throw"
            "B-NAT-CLIENT-THROW-HELD-RETIRE" -> {
                startupClient = "throw"
                clientGate = "G-CLIENT-CONNECT"
            }
            "B-NAT-CLIENT-THROW-MASTER-CLOSE-THROW" -> {
                startupClient = "throw"
                closeMaster = "throw"
            }
            "P-NAT-NORMAL" -> Unit
            "P-NAT-DENY-ISSUANCE-THROW" -> denyIssuance = "throw"
            "P-NAT-DENY-HEAD-ISSUANCE-THROW" -> denyHead = "throw"
            "P-NAT-CLOSE-HELD-UNIT" -> {
                provisionerClose = "unit"
                provisionerCloseGate = "G-PROVISIONER-CLOSE"
            }
            "P-NAT-CLOSE-THROW" -> provisionerClose = "throw"
            "P-NAT-CLOSE-HELD-THROW" -> {
                provisionerClose = "throw"
                provisionerCloseGate = "G-PROVISIONER-CLOSE"
            }
            "P-NAT-LATE-NORMAL" -> provisionerOpenGate = "G-PROVISIONER-OPEN"
            "P-NAT-LATE-DENY-THROW" -> {
                provisionerOpenGate = "G-PROVISIONER-OPEN"
                denyIssuance = "throw"
            }
            "P-NAT-LATE-CLOSE-THROW" -> {
                provisionerOpenGate = "G-PROVISIONER-OPEN"
                provisionerClose = "throw"
            }
            else -> error("${variant.variantId}: unknown native profile $native")
        }
        if ("G-REFRESH-BINDING" in variant.gates) bindingGate = "G-REFRESH-BINDING"
        if ("G-CLOSE-OWNER" in variant.gates) closeGate = "G-CLOSE-OWNER"
        when (variant.nativeProfile) {
            "B-NAT-CLIENT-THROW-MASTER-CLOSE-THROW" -> closeMaster = "throw"
        }
        if (variant.caseId == "E3RA-P1" || variant.caseId == "E3RA-P3" || variant.caseId == "E3RA-P4") {
            if (variant.caseId != "E3RA-P4") {
                when (variant.nativeProfile) {
                    "P-NAT-CLOSE-HELD-UNIT" -> provisionerClose = "unit"
                    "P-NAT-CLOSE-THROW" -> provisionerClose = "throw"
                    "P-NAT-CLOSE-HELD-THROW" -> provisionerClose = "throw"
                }
            }
        }
    }

    private fun setupFor(profile: String, startup: Boolean): SetupScript = when (profile) {
        "SET-PRESENT-PRESENT" -> SetupScript(true, true, false)
        "SET-DEF-NULL-RECEIPT-PRESENT" -> SetupScript(false, true, false)
        "SET-DEF-PRESENT-RECEIPT-NULL" -> SetupScript(true, false, false)
        "SET-BOTH-NULL" -> SetupScript(false, false, false)
        "SET-TRANSITION-TO-BOTH-NULL" -> if (startup) SetupScript(true, true, false)
            else SetupScript(false, false, false)
        "R-NAT-SETUP-THROW" -> if (startup) SetupScript(true, true, false) else SetupScript(true, true, true)
        else -> error("${variant.variantId}: unknown setup profile $profile")
    }
}

internal data class SetupScript(val definition: Boolean, val receipt: Boolean, val throws: Boolean)
internal data class BindingScript(
    val kind: String,
    val index: Int,
    val reason: SelfOwnedInstallationFailureV1?,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class SupplementalV8Harness(
    private val test: TestScope,
    val variant: SupplementalVariant,
    private val fixtures: SupplementalV8Fixtures = SupplementalV8Fixtures,
) {
    enum class Phase { STARTUP, REFRESH, PROVISIONER }

    val script = SupplementalScript(variant)
    val scheduler: TestCoroutineScheduler = test.testScheduler
    val log = StartupObservationLog()
    val ledger = OwnershipLedger()
    val launchStorage = SupplementalLaunchStorage(
        ExistingOnlyStartupFixtures.record("REC-RUN-CANONICAL").bytes, log)
    private val recording = RecordingDispatcher(StandardTestDispatcher(scheduler))
    val execution = SupplementalExecution(recording, scheduler)

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
    val provisionerOpens = mutableListOf<ProvisionerOpenObservation>()
    val provisionerInspects = mutableListOf<ProvisionerInspectObservation>()
    val authorityInspects = mutableListOf<ProvisionerAuthorityObservation>()
    val provisionerDenials = mutableListOf<ProvisionerDenialObservation>()
    val provisionerCloses = mutableListOf<ProvisionerCloseObservation>()
    val forbiddenCalls = mutableListOf<String>()
    val transferObservations = mutableListOf<TransferObservation>()
    val retirementJobs = mutableListOf<Job>()
    var phase: Phase = Phase.STARTUP
    var startupJob: Job? = null
    var retirementDeferred: Deferred<Unit>? = null
    var retirementJobsBefore: Set<Job> = emptySet()
    var retirementRequests = 0
    var repeatRefusals = 0
    var targetMaterialAtOpen: SelfOwnedNativeMaterialView? = null
    var targetContext: SelfOwnedLocalSecurityContext? = null
    var masterObject: LocalSetupMaster? = null
    var clientObject: LocalSetupClient? = null
    var publishedOwner: Any? = null
    var publishedClient: LocalSetupClient? = null
    var provisionerObject: LocalProvisioner? = null
    var companyAuthorityObject: LocalCompanyAuthority? = null
    var companyAuthority: LocalCompanyAuthorityView = ExistingOnlyStartupData.company
    var livePublications: Int = 0
    var clientCustodyUnknown: Boolean = false
    var targetCustodyUnknown: Boolean = false
    var targetTransferred: Boolean = false
    var closedDeferredNormal: Boolean = false
    var closedDeferredExceptional: Throwable? = null
    var teardownRequested: Boolean = false
    var coreSnapshotTaken: Boolean = false
    private var currentnessState: String = "true"
    private var testActorWithdrawal: Boolean = false
    private var testPublishingForeign: Boolean = false
    var publicationWithdrawnByExactOwner: Boolean = false
    var publicationWithdrawalUncertain: Boolean = false
    var publicationUncertain: Boolean = false
    var foreignOwner: Any? = null
    var foreignClient: LocalSetupClient? = null
    var foreignLive: Boolean = false

    private val gateEntered = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val gateReleased = mutableMapOf<String, CompletableDeferred<Unit>>()
    private val masterModeValue = MutableStateFlow(LocalMasterLifecycleMode.OPERATIONAL)
    private val observedMasterMode: StateFlow<LocalMasterLifecycleMode> =
        object : StateFlow<LocalMasterLifecycleMode> by masterModeValue {
            override val value: LocalMasterLifecycleMode
                get() {
                    val current = masterModeValue.value
                    log.record("PRODUCTION", "master.mode", "observed", detail = current.name)
                    return current
                }
        }

    val services = ControlledServices()
    val coordinator: LocalSetupCoordinator = LocalSetupCoordinator(
        services = services,
        employeeSetupIntents = LocalEmployeeSetupIntents(ForbiddenIntentStorage()),
        launchRecord = LocalMasterLaunchRecord(launchStorage),
        execution = execution,
        beforeRetirement = { _, result ->
            retirementRequests++
            retirementJobsBefore = recording.jobs.toSet()
            retirementDeferred = result
            log.record("PRODUCTION", "beforeRetirement", "enter", detail = "requests=$retirementRequests")
            result.invokeOnCompletion { failure ->
                if (failure == null) {
                    closedDeferredNormal = true
                    log.record("PRODUCTION", "retirement", "normal")
                } else {
                    closedDeferredExceptional = failure
                    log.record("PRODUCTION", "retirement", "exceptional", detail = failure.message ?: "")
                }
            }
        },
        beforeClientHosting = { log.record("PRODUCTION", "beforeClientHosting", "enter") },
        deviceAdmissionIntents = null,
        freshDevicePairingIntents = null,
        pairingDocuments = null,
    )

    val view: com.ukkera.pos.app.setup.LocalSetupView get() = coordinator.state.value

    fun usePhase(value: Phase) {
        phase = value
    }

    fun setCurrentness(value: String) {
        currentnessState = value
        if (value != "true") {
            log.record("TEST", "client.isCurrent", "mutated", detail = value)
        }
    }

    fun mutateHint(bytes: ByteArray?, event: String) {
        launchStorage.testMutate(bytes, event)
    }

    fun armReadThrow() {
        launchStorage.armReadThrow()
    }

    fun runUntilIdle() {
        scheduler.advanceUntilIdle()
    }

    fun jobs(): List<Job> = recording.jobs.toList()

    fun newJobsSince(before: Set<Job>): List<Job> = recording.jobs.filter { job -> before.none { it === job } }

    fun captureStartup(action: () -> Unit): Job {
        val before = recording.jobs.toSet()
        action()
        val created = newJobsSince(before)
        val job = created.firstOrNull()
            ?: throw AssertionError("${variant.variantId}: public action dispatched no job")
        if (created.size > 1) {
            throw AssertionError("${variant.variantId}: public action dispatched ${created.size} jobs")
        }
        startupJob = job
        job.invokeOnCompletion { failure ->
            log.record("PRODUCTION", "workJob", if (failure == null) "completed" else "exceptional",
                detail = failure?.message ?: "")
        }
        log.record("TEST", "publicAction", "issued", alias = job.alias())
        return job
    }

    fun invokeRefresh(): List<Job> {
        val before = recording.jobs.toSet()
        coordinator.refreshOriginalStatus()
        return newJobsSince(before)
    }

    /**
     * The late test acts as the independent lifecycle watchdog through the very same
     * publish/withdraw composition port used by production. The observation is attributed to
     * the test actor; production never calls it on behalf of the test.
     */
    fun independentWithdraw(owner: Any): WithdrawObservation {
        testActorWithdrawal = true
        try { services.withdrawClient(owner) } finally { testActorWithdrawal = false }
        return withdraws.last()
    }

    /** The test owns and publishes a known foreign Client under a distinct exact owner. */
    fun independentForeignPublish(owner: Any, client: LocalSetupClient) {
        testPublishingForeign = true
        try { services.publishClient(owner, client) } finally { testPublishingForeign = false }
    }

    fun setMasterMode(value: LocalMasterLifecycleMode) {
        masterModeValue.value = value
        log.record("TEST", "master.mode", "mutated", detail = value.name)
    }

    fun masterMode(): LocalMasterLifecycleMode = masterModeValue.value

    fun productionPublishCount(): Int = publishes.count { it.actor == "PRODUCTION" }
    fun productionWithdraws(): List<WithdrawObservation> = withdraws.filter { it.actor == "PRODUCTION" }
    fun testWithdrawals(): List<WithdrawObservation> =
        withdraws.filter { it.actor == "INDEPENDENT_LIFECYCLE_WATCHDOG" }

    fun invokeProvisionerInspection(): Job {
        val before = recording.jobs.toSet()
        coordinator.inspectOriginalCompany()
        val created = newJobsSince(before)
        val job = created.firstOrNull()
            ?: throw AssertionError("${variant.variantId}: inspectOriginalCompany dispatched no job")
        if (created.size > 1) {
            throw AssertionError("${variant.variantId}: inspectOriginalCompany dispatched ${created.size} jobs")
        }
        startupJob = job
        return job
    }

    fun requestRetirement(): Deferred<Unit> {
        retirementJobsBefore = recording.jobs.toSet()
        val deferred = coordinator.requestRetirement(false)
        retirementDeferred = deferred
        log.record("TEST", "requestRetirement", "issued", detail = "requests=${retirementRequests + 1}")
        return deferred
    }

    fun captureRetirementJobs() {
        recording.jobs.filter { job -> retirementJobsBefore.none { it === job } && job !== startupJob }
            .forEach { job -> if (retirementJobs.none { it === job }) retirementJobs += job }
    }

    suspend fun settle(job: Job) {
        runUntilIdle()
        job.join()
        runUntilIdle()
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
            return
        }
        released.await()
        log.record("PRODUCTION", "gate", "release", detail = gate)
    }

    fun releaseGate(gate: String) {
        gateReleased.getOrPut(gate) { CompletableDeferred() }.complete(Unit)
        log.record("TEST", "gate", "released", detail = gate)
    }

    fun gateWasEntered(gate: String?): Boolean = gate != null && gateEntered[gate]?.isCompleted == true

    fun gateEnterSequence(gate: String): Int? = log.all().firstOrNull {
        it.method == "gate" && it.phase == "enter" && it.detail == gate
    }?.sequence

    fun beginTeardown() { teardownRequested = true }

    fun releaseAllGates() { gateReleased.values.forEach { it.complete(Unit) } }

    fun unreleasedGates(): Set<String> = gateEntered.keys.filter { gate ->
        gateReleased[gate]?.isCompleted != true
    }.toSet()

    fun coreSnapshot() {
        coreSnapshotTaken = true
        log.record("TEST", "core-snapshot", "taken")
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

    fun isRetiring(): Boolean = view.phase == com.ukkera.pos.app.setup.LocalSetupPhase.CLOSING ||
        retirementDeferred != null

    fun isClosed(): Boolean = view.phase == com.ukkera.pos.app.setup.LocalSetupPhase.CLOSED

    fun targetAlias(): String = targetContext?.let { "CTX-STARTUP" } ?: "CTX-NOT-OPENED"
    fun masterAlias(): String = masterObject?.let { "MST-OBJECT" } ?: "MST-NOT-OPENED"
    fun clientAlias(): String = clientObject?.let { "CLIENT-OBJECT" } ?: "CLIENT-NOT-RETURNED"
    fun provisionerAlias(): String = provisionerObject?.let { "PROVISIONER-OBJECT" } ?: "PROVISIONER-NONE"

    fun mutateStoredRecord(bytes: ByteArray?) = launchStorage.testMutate(bytes, "TEST-MUTATION")

    inner class ControlledServices : LocalSetupNativeServices {
        override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult {
            if (phase != Phase.PROVISIONER) {
                forbiddenCalls += "openProvisioner(outside-provisioner-family)"
                log.record("PRODUCTION", "openProvisioner", "forbidden")
                throw ControlledFailure("EX-PROVISIONER-OUTSIDE")
            }
            val gate = script.provisionerOpenGate
            if (gate != null) {
                awaitGate(gate)
            }
            val observation = ProvisionerOpenObservation(originalOnly, log.size)
            provisionerOpens += observation
            log.record("PRODUCTION", "openProvisioner", "enter", detail = "originalOnly=$originalOnly")
            if (!originalOnly) forbiddenCalls += "openProvisioner(originalOnly=false)"
            val provisioner = ControlledProvisioner()
            provisionerObject = provisioner
            companyAuthorityObject = provisioner.companyAuthority
            observation.outcome = "opened"
            ledger.held(provisionerAlias(), "provisioner")
            log.record("PRODUCTION", "openProvisioner", "opened", alias = provisionerAlias())
            return LocalProvisionerOpenResult.Opened(provisioner)
        }

        override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult {
            if (phase == Phase.PROVISIONER) {
                forbiddenCalls += "openTarget(provisioner-family)"
                throw ControlledFailure("EX-TARGET-OUTSIDE")
            }
            val observation = TargetOpenObservation(preparation, log.size)
            targetOpens += observation
            log.record("PRODUCTION", "openTarget", "enter", detail = "preparation=$preparation")
            if (preparation) forbiddenCalls += "openTarget(preparation=true)"
            val failure = if (phase == Phase.REFRESH) script.refreshTarget else "opened"
            return when (failure) {
                "unresolved" -> {
                    observation.outcome = "unresolved"
                    log.record("PRODUCTION", "openTarget", "unresolved")
                    SelfOwnedNativeOpenResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
                }
                "throw" -> {
                    observation.outcome = "throw"
                    log.record("PRODUCTION", "openTarget", "throw")
                    throw ControlledFailure("EX-REFRESH-TARGET")
                }
                else -> {
                    val material = ExistingOnlyStartupData.material(ExistingOnlyStartupData.canonicalCoordinates)
                    val context = SelfOwnedLocalSecurityContext(ControlledContext(material))
                    targetContext = context
                    targetMaterialAtOpen = material
                    observation.outcome = "opened"
                    observation.contextAlias = targetAlias()
                    observation.coordinates = material.coordinates
                    ledger.held(targetAlias(), "target-context")
                    log.record("PRODUCTION", "openTarget", "opened", alias = targetAlias())
                    SelfOwnedNativeOpenResult.Opened(context)
                }
            }
        }

        override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult {
            if (phase == Phase.PROVISIONER) {
                forbiddenCalls += "openMaster(provisioner-family)"
                throw ControlledFailure("EX-MASTER-OUTSIDE")
            }
            val observation = MasterOpenObservation(context, log.size)
            masterOpens += observation
            assertSameContext(context)
            script.masterGate?.let { awaitGate(it) }
            val result = if (phase == Phase.REFRESH) script.refreshMaster else script.startupMaster
            log.record("PRODUCTION", "openMaster", "enter", alias = targetAlias())
            return when (result) {
                "unresolved" -> {
                    observation.outcome = "unresolved"
                    ledger.heldTransferUnknown(targetAlias())
                    targetCustodyUnknown = true
                    log.record("PRODUCTION", "openMaster", "unresolved")
                    SelfOwnedMasterOpenResultUnresolved()
                }
                "throw" -> {
                    observation.outcome = "throw"
                    ledger.heldTransferUnknown(targetAlias())
                    targetCustodyUnknown = true
                    log.record("PRODUCTION", "openMaster", "throw")
                    throw ControlledFailure("EX-REFRESH-MASTER")
                }
                else -> {
                    val master = ControlledMaster(if (phase == Phase.REFRESH) script.refreshMasterMode
                        else script.startupMasterMode)
                    masterObject = master
                    masterModeValue.value = master.initialMode
                    observation.outcome = "opened"
                    observation.masterAlias = masterAlias()
                    ledger.held(masterAlias(), "master")
                    ledger.transfer(targetAlias(), masterAlias())
                    targetTransferred = true
                    transferObservations += TransferObservation(context, targetAlias(), master,
                        masterAlias(), log.size)
                    log.record("PRODUCTION", "openMaster", "opened", alias = masterAlias())
                    LocalSetupMasterOpenResult.Opened(master)
                }
            }
        }

        override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext):
            LocalMasterInstallationReceiptOpenResult {
            forbiddenCalls += "openOriginalReceipt"
            throw ControlledFailure("EX-RECEIPT")
        }

        override suspend fun connectClient(master: LocalSetupMaster,
            action: LocalOriginalClientJournalAction): LocalSetupClient? {
            if (phase == Phase.PROVISIONER) {
                forbiddenCalls += "connectClient(provisioner-family)"
                throw ControlledFailure("EX-CLIENT-OUTSIDE")
            }
            val observation = ClientConnectObservation(master, action, log.size)
            clientConnects += observation
            log.record("PRODUCTION", "connectClient", "enter", alias = masterAlias(), detail = action.name)
            if (master !== masterObject) {
                forbiddenCalls += "connectClient(other-master)"
                throw AssertionError("${variant.variantId}: connectClient did not receive the exact Master")
            }
            if (action != LocalOriginalClientJournalAction.REOPEN_EXISTING) {
                forbiddenCalls += "connectClient(${action.name})"
            }
            script.clientGate?.let { awaitGate(it) }
            val result = if (phase == Phase.REFRESH) script.refreshClient else script.startupClient
            return when (result) {
                "null" -> {
                    observation.outcome = "null"
                    log.record("PRODUCTION", "connectClient", "null")
                    null
                }
                "returned" -> {
                    val client = ControlledClient()
                    clientObject = client
                    observation.outcome = "returned"
                    observation.clientAlias = clientAlias()
                    ledger.held(clientAlias(), "client")
                    log.record("PRODUCTION", "connectClient", "returned", alias = clientAlias())
                    client
                }
                "throw" -> {
                    observation.outcome = "throw"
                    clientCustodyUnknown = true
                    log.record("PRODUCTION", "connectClient", "throw")
                    throw ControlledFailure("EX-CLIENT")
                }
                else -> error("${variant.variantId}: unknown client script $result")
            }
        }

        override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean {
            forbiddenCalls += "ownsEmployeeConnection"
            return false
        }

        override fun publishClient(owner: Any, client: LocalSetupClient) {
            if (phase == Phase.PROVISIONER) {
                forbiddenCalls += "publishClient(provisioner-family)"
                throw ControlledFailure("EX-PUBLISH-OUTSIDE")
            }
            if (testPublishingForeign) {
                publishes += PublishObservation(owner, client, log.size, "INDEPENDENT_LIFECYCLE_WATCHDOG")
                foreignOwner = owner
                foreignClient = client
                foreignLive = true
                livePublications++
                log.record("TEST", "publishClient.foreign", "published", alias = "OWNER-FOREIGN")
                return
            }
            val observation = PublishObservation(owner, client, log.size)
            publishes += observation
            if (publishedOwner == null) {
                publishedOwner = owner
                publishedClient = client
                livePublications++
                log.record("PRODUCTION", "publishClient", "enter", alias = "OWNER-PUBLISHED")
            } else if (owner === publishedOwner && client === publishedClient) {
                log.record("PRODUCTION", "publishClient", "republish-same-owner", alias = "OWNER-PUBLISHED")
            } else {
                forbiddenCalls += "publishClient(second-live-publication)"
                livePublications++
                log.record("PRODUCTION", "publishClient", "duplicate", alias = "OWNER-DUPLICATE")
            }
            if (client !== clientObject) forbiddenCalls += "publishClient(other-client)"
        }

        override fun withdrawClient(owner: Any) {
            val actor = if (testActorWithdrawal) "INDEPENDENT_LIFECYCLE_WATCHDOG" else "PRODUCTION"
            val observation = WithdrawObservation(owner,
                if (owner === publishedOwner) "OWNER-PUBLISHED" else "TOKEN-UNPUBLISHED",
                if (script.lateWithdrawalThrow && testActorWithdrawal) "throw" else "unit",
                log.size, actor)
            withdraws += observation
            if (script.lateWithdrawalThrow && testActorWithdrawal) {
                observation.outcome = "throw"
                publicationWithdrawalUncertain = true
                publicationUncertain = true
                log.record(actor, "withdrawClient", "throw", alias = observation.alias)
                throw ControlledFailure("EX-WITHDRAW")
            }
            observation.outcome = "unit"
            if (publishedOwner != null && owner === publishedOwner) {
                livePublications = 0
                if (script.latePublicationSemantics) publicationWithdrawnByExactOwner = true
                log.record(actor, "withdrawClient", "published-owner", alias = "OWNER-PUBLISHED")
            } else {
                log.record(actor, "withdrawClient", "unpublished-token", alias = observation.alias)
            }
        }
    }

    private fun assertSameContext(context: SelfOwnedLocalSecurityContext) {
        if (context !== targetContext) {
            throw AssertionError("${variant.variantId}: openMaster did not receive the exact context")
        }
    }

    private fun SelfOwnedMasterOpenResultUnresolved(): LocalSetupMasterOpenResult =
        LocalSetupMasterOpenResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)

    private inner class ControlledContext(
        override val materialAtOpen: SelfOwnedNativeMaterialView,
    ) : com.ukkera.pos.security.SelfOwnedNativeContextController {
        override val compositionProfile: com.ukkera.pos.security.LocalCompositionSdkProfileV1
            get() = com.ukkera.pos.security.LocalMasterSdkProfileV1
        override suspend fun readOriginalNativeBinding(): SelfOwnedNativeReadResult {
            val binding = if (phase == Phase.REFRESH) script.refreshBinding
                else BindingScript("agree", 0, null)
            val observation = BindingObservation(log.size, binding.kind)
            bindingReads += observation
            log.record("PRODUCTION", "readOriginalNativeBinding", "enter", detail = binding.kind)
            script.bindingGate?.takeIf { phase == Phase.REFRESH }?.let { awaitGate(it) }
            return when (binding.kind) {
                "agree" -> {
                    observation.outcome = "retained"
                    observation.material = ExistingOnlyStartupData.material(ExistingOnlyStartupData.canonicalCoordinates)
                    log.record("PRODUCTION", "readOriginalNativeBinding", "retained", detail = "canonical")
                    SelfOwnedNativeReadResult.Retained(observation.material!!,
                        ExistingOnlyStartupData.binding(ExistingOnlyStartupData.canonicalCoordinates))
                }
                "changed" -> {
                    val coordinates = ExistingOnlyStartupData.coordinatesWith("canonical", binding.index)
                    observation.outcome = "retained"
                    observation.material = ExistingOnlyStartupData.material(ExistingOnlyStartupData.canonicalCoordinates)
                    log.record("PRODUCTION", "readOriginalNativeBinding", "retained",
                        detail = "changed-index=${binding.index}")
                    SelfOwnedNativeReadResult.Retained(observation.material!!,
                        ExistingOnlyStartupData.binding(coordinates))
                }
                "unresolved" -> {
                    observation.outcome = "unresolved"
                    log.record("PRODUCTION", "readOriginalNativeBinding", "unresolved",
                        detail = binding.reason?.name ?: "UNAVAILABLE")
                    SelfOwnedNativeReadResult.Unresolved(binding.reason
                        ?: SelfOwnedInstallationFailureV1.UNAVAILABLE)
                }
                "throw" -> {
                    observation.outcome = "throw"
                    log.record("PRODUCTION", "readOriginalNativeBinding", "throw")
                    throw ControlledFailure("EX-BINDING")
                }
                else -> error("${variant.variantId}: unknown binding script ${binding.kind}")
            }
        }

        override suspend fun withdrawNewCeremonies(): com.ukkera.pos.security.SelfOwnedNativeWithdrawal {
            forbiddenCalls += "withdrawNewCeremonies"
            return com.ukkera.pos.security.SelfOwnedNativeWithdrawal.Unavailable(
                SelfOwnedInstallationFailureV1.UNAVAILABLE)
        }

        override fun denyContextNewCeremonies() {
            ledger.optionalCloseObservedIfPresent(targetAlias())
            log.record("PRODUCTION", "target.denyNewCeremonies", "enter", alias = targetAlias())
        }

        override fun denyContextDataUse() {
            log.record("PRODUCTION", "target.denyDataUse", "enter", alias = targetAlias())
        }

        override suspend fun closeContext() {
            if (targetTransferred) {
                log.record("PRODUCTION", "target.close", "stale-after-transfer", alias = targetAlias())
                return
            }
            targetCloseEntries += CloseObservation("target", log.size)
            val entry = targetCloseEntries.last()
            val outcome = script.closeTarget
            log.record("PRODUCTION", "target.close", "enter", alias = targetAlias(), detail = outcome)
            when (outcome) {
                "confirm" -> {
                    entry.outcome = "unit"
                    ledger.optionalCloseObserved(targetAlias())
                    log.record("PRODUCTION", "target.close", "optional-diagnostic-unit")
                }
                "throw" -> {
                    entry.outcome = "throw"
                    log.record("PRODUCTION", "target.close", "optional-diagnostic-throw")
                    throw ControlledFailure("EX-TARGET-CLOSE")
                }
                else -> {
                    entry.outcome = "none"
                    log.record("PRODUCTION", "target.close", "unscripted")
                }
            }
        }

        override suspend fun claimOriginalHistoryCustody(): com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult {
            forbiddenCalls += "claimOriginalHistoryCustody"
            return com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult.Unresolved(
                SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        }
    }

    private fun OwnershipLedger.optionalCloseObservedIfPresent(alias: String) {
        entry(alias)?.let { if (it.state == OwnerState.HELD) Unit }
    }

    private inner class ControlledMaster(val initialMode: LocalMasterLifecycleMode) : LocalSetupMaster {
        override suspend fun readOwnedOriginalNativeBinding(): SelfOwnedNativeReadResult =
            checkNotNull(targetContext).readOriginalNativeBinding()
        override val mode: StateFlow<LocalMasterLifecycleMode> = observedMasterMode
        override suspend fun permitsFirstClientEnrollment(): Boolean = false
        override suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning,
            authorityEpoch: Revision): LocalBranchInstallationSnapshotV2 {
            forbiddenCalls += "prepareOriginalDefinition"
            throw ControlledFailure("EX-PREPARE")
        }
        override suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2,
            headLogin: String, headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome {
            headSecret.fill('\u0000'); employeePassword.fill('\u0000')
            forbiddenCalls += "installOriginal"
            throw ControlledFailure("EX-INSTALL")
        }
        override suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome {
            forbiddenCalls += "resumeOriginalAcknowledgement"
            throw ControlledFailure("EX-ACK")
        }
        override suspend fun readOriginalSetup(): LocalOriginalInstallationSetup {
            val setup = if (phase == Phase.REFRESH) (script.lateRefreshSetup ?: script.refreshSetup)
                else script.startupSetup
            val observation = SetupObservation(if (setup.definition) "present" else "null",
                if (setup.receipt) "present" else "null", setup.throws, log.size)
            setupReads += observation
            log.record("PRODUCTION", "master.readOriginalSetup", "enter",
                detail = "${observation.definition}/${observation.receipt} throw=${setup.throws}")
            script.lateSetupGate?.takeIf { phase == Phase.REFRESH }?.let { awaitGate(it) }
            if (setup.throws) {
                log.record("PRODUCTION", "master.readOriginalSetup", "throw")
                throw ControlledFailure("EX-SETUP")
            }
            log.record("PRODUCTION", "master.readOriginalSetup", "returned")
            return ExistingOnlyStartupData.setup(setup.definition, setup.receipt)
        }
        override fun denyNewWork() {
            denialCalls += DenialObservation("master.denyNewWork", masterAlias(), log.size)
            log.record("PRODUCTION", "master.denyNewWork", "enter", alias = masterAlias())
        }
        override suspend fun quiesceAndClose(): Boolean {
            masterCloseEntries += CloseObservation("master", log.size)
            val entry = masterCloseEntries.last()
            ledger.closeEntered(masterAlias())
            log.record("PRODUCTION", "master.quiesceAndClose", "enter", alias = masterAlias(),
                detail = script.closeMaster)
            script.closeGate?.let { awaitGate(it) }
            return when (script.closeMaster) {
                "confirm" -> {
                    entry.outcome = "true"
                    ledger.confirm(masterAlias())
                    log.record("PRODUCTION", "master.quiesceAndClose", "true")
                    true
                }
                "false" -> {
                    entry.outcome = "false"
                    ledger.unconfirm(masterAlias())
                    log.record("PRODUCTION", "master.quiesceAndClose", "false")
                    false
                }
                "throw" -> {
                    entry.outcome = "throw"
                    ledger.unconfirm(masterAlias())
                    log.record("PRODUCTION", "master.quiesceAndClose", "throw")
                    throw ControlledFailure("EX-MASTER-CLOSE")
                }
                else -> error("${variant.variantId}: unknown Master close script ${script.closeMaster}")
            }
        }
    }

    private inner class ControlledClient : LocalSetupClient {
        override fun isCurrent(): Boolean {
            // The real publication port withdraws by denying the exact published runtime
            // (`LocalClientPublication.withdraw`), which clears its connection state and makes
            // that Client non-current. The late lifecycle fixture reproduces that public effect
            // without adding any production observation. A thrown withdrawal is uncertainty:
            // the Client's currentness is not affirmatively observable.
            if (script.latePublicationSemantics && publicationWithdrawalUncertain) {
                log.record("PRODUCTION", "client.isCurrent", "observed", alias = clientAlias(),
                    detail = "throw-withdrawal-uncertain")
                throw ControlledFailure("EX-CURRENTNESS-WITHDRAW-UNCERTAIN")
            }
            if (script.latePublicationSemantics && publicationWithdrawnByExactOwner) {
                log.record("PRODUCTION", "client.isCurrent", "observed", alias = clientAlias(),
                    detail = "false-by-exact-owner-withdrawal")
                return false
            }
            log.record("PRODUCTION", "client.isCurrent", "observed", alias = clientAlias(),
                detail = currentnessState)
            return when (currentnessState) {
                "true" -> true
                "false" -> false
                else -> throw ControlledFailure("EX-CURRENTNESS")
            }
        }
        override fun denyNewWork() {
            denialCalls += DenialObservation("client.denyNewWork", clientAlias(), log.size)
            log.record("PRODUCTION", "client.denyNewWork", "enter", alias = clientAlias())
        }
        override suspend fun close() {
            clientCloseEntries += CloseObservation("client", log.size)
            val entry = clientCloseEntries.last()
            ledger.closeEntered(clientAlias())
            log.record("PRODUCTION", "client.close", "enter", alias = clientAlias(), detail = script.closeClient)
            script.closeGate?.let { awaitGate(it) }
            when (script.closeClient) {
                "confirm" -> {
                    entry.outcome = "unit"
                    ledger.confirm(clientAlias())
                    log.record("PRODUCTION", "client.close", "unit")
                }
                "throw" -> {
                    entry.outcome = "throw"
                    ledger.unconfirm(clientAlias())
                    log.record("PRODUCTION", "client.close", "throw")
                    throw ControlledFailure("EX-CLIENT-CLOSE")
                }
                else -> error("${variant.variantId}: unknown Client close script ${script.closeClient}")
            }
        }
        override suspend fun prepareEmployeeEnrollment(requestId: DomainId,
            definition: LocalAccountProvisioning): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
            forbiddenEmployee()
        override suspend fun approveEmployeeEnrollment(action: LocalEmployeeEnrollmentAction, headLogin: String,
            headSecret: CharArray): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> =
            forbiddenEmployee()
        override suspend fun recoverEmployeeEnrollmentApproval(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> = forbiddenEmployee()
        override suspend fun recoverEmployeeEnrollmentConnection(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> = forbiddenEmployee()
        override suspend fun reviewEmployeeEnrollment(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> = forbiddenEmployee()
        override suspend fun retainedEmployeeEnrollments():
            LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> = forbiddenEmployee()
        override suspend fun enrollApprovedEmployee(originalRequestId: DomainId,
            password: CharArray): com.ukkera.pos.app.LocalEnrollmentResult {
            password.fill('\u0000'); return forbiddenEmployee()
        }
        override suspend fun lookupEmployeeEnrollment(originalRequestId: DomainId):
            com.ukkera.pos.app.LocalEnrollmentResult = forbiddenEmployee()
        override suspend fun acquireEmployeeIntentJournal(originalRequestId: DomainId,
            action: NativeEmployeeClientJournalAction): Boolean = forbiddenEmployee()
        override suspend fun prepareEmployeePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy):
            LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = forbiddenEmployee()
        override suspend fun approveEmployeePolicy(action: LocalEmployeePolicyActivationAction, headLogin: String,
            headSecret: CharArray): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
            headSecret.fill('\u0000'); return forbiddenEmployee()
        }
        override suspend fun reviewEmployeePolicy(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = forbiddenEmployee()
        override suspend fun recoverEmployeePolicyApproval(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = forbiddenEmployee()
        override suspend fun recoverEmployeePolicyConnection(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> = forbiddenEmployee()
        override suspend fun retainedEmployeePolicies():
            LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> = forbiddenEmployee()
        override suspend fun activateEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
            nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult = forbiddenEmployee()
        override suspend fun lookupEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
            nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus = forbiddenEmployee()

        private fun <T> forbiddenEmployee(): T {
            forbiddenCalls += "employee-effect"
            log.record("PRODUCTION", "employee", "forbidden")
            throw ControlledFailure("EX-EMPLOYEE")
        }
    }

    private inner class ControlledProvisioner : LocalProvisioner {
        private val companyView = LocalProvisionerCompanyView(
            ExistingOnlyStartupData.company.rootId,
            ExistingOnlyStartupData.company.organizationId,
            ExistingOnlyStartupData.company.companyId,
            ExistingOnlyStartupData.company.companyName,
            "Synthetic responsible person",
            ExistingOnlyStartupData.company.headLogin,
            ExistingOnlyStartupData.company.rootFingerprint,
            ExistingOnlyStartupData.company.protection,
            true,
        )
        override val companyAuthority: LocalCompanyAuthority = ControlledCompanyAuthority(this)
        override suspend fun inspect(): LocalProvisionerInspection {
            val observation = ProvisionerInspectObservation(log.size)
            provisionerInspects += observation
            log.record("PRODUCTION", "provisioner.inspect", "enter")
            if (script.provisionerInspection != "enrolled") {
                error("${variant.variantId}: unsupported provisioner inspection")
            }
            observation.outcome = "enrolled"
            log.record("PRODUCTION", "provisioner.inspect", "enrolled")
            return LocalProvisionerInspection.Enrolled(companyView, null, false)
        }
        override suspend fun prepareNewCompany(facts: SelfOwnedCompanyFactsV1): LocalProvisionerPreparation =
            forbiddenProvisioner("prepareNewCompany")
        override suspend fun completeNewCompany(action: LocalProvisionerPendingAction,
            secret: CharArray): LocalProvisionerEnrollment {
            secret.fill('\u0000'); return forbiddenProvisioner("completeNewCompany")
        }
        override suspend fun prepareBranchWithEnrolledCompany(facts: SelfOwnedBranchFactsV1):
            LocalProvisionerPreparation = forbiddenProvisioner("prepareBranch")
        override suspend fun issueOriginalPreparation(action: LocalProvisionerPendingAction,
            installerLogin: String, secret: CharArray): LocalProvisionerGrant {
            secret.fill('\u0000'); return forbiddenProvisioner("issueOriginalPreparation")
        }
        override suspend fun recoverOriginalPreparation(): LocalProvisionerGrant =
            forbiddenProvisioner("recoverOriginalPreparation")
        override suspend fun withdrawNewIssuance(): LocalProvisionerWithdrawal =
            LocalProvisionerWithdrawal.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        override fun denyNewIssuance() {
            provisionerDenials += ProvisionerDenialObservation("denyNewIssuance",
                provisionerAlias(), script.denyIssuance, log.size)
            log.record("PRODUCTION", "provisioner.denyNewIssuance", "enter",
                detail = script.denyIssuance)
            if (script.denyIssuance == "throw") throw ControlledFailure("EX-DENY-ISSUANCE")
        }
        override suspend fun close() {
            provisionerCloses += ProvisionerCloseObservation(provisionerAlias(), script.provisionerClose, log.size)
            val entry = provisionerCloses.last()
            ledger.closeEntered(provisionerAlias())
            log.record("PRODUCTION", "provisioner.close", "enter",
                detail = script.provisionerClose)
            script.provisionerCloseGate?.let { awaitGate(it) }
            when (script.provisionerClose) {
                "unit" -> {
                    entry.outcome = "unit"
                    ledger.confirm(provisionerAlias())
                    log.record("PRODUCTION", "provisioner.close", "unit")
                }
                "throw" -> {
                    entry.outcome = "throw"
                    ledger.unconfirm(provisionerAlias())
                    log.record("PRODUCTION", "provisioner.close", "throw")
                    throw ControlledFailure("EX-PROVISIONER-CLOSE")
                }
                else -> error("${variant.variantId}: unknown provisioner close ${script.provisionerClose}")
            }
        }
        private fun <T> forbiddenProvisioner(method: String): T {
            forbiddenCalls += "provisioner.$method"
            throw ControlledFailure("EX-PROVISIONER-EFFECT")
        }
    }

    private inner class ControlledCompanyAuthority(private val owner: ControlledProvisioner) : LocalCompanyAuthority {
        private val harness: SupplementalV8Harness get() = this@SupplementalV8Harness
        override suspend fun inspectCompanyAuthority(): LocalCompanyAuthorityResult<LocalCompanyAuthorityView> {
            harness.authorityInspects += ProvisionerAuthorityObservation("inspectCompanyAuthority",
                harness.provisionerAlias(), "value", harness.log.size)
            harness.log.record("PRODUCTION", "companyAuthority.inspectCompanyAuthority", "value")
            return LocalCompanyAuthorityResult.Value(harness.companyAuthority)
        }
        override suspend fun readOriginalCompanyAndHeadAction():
            LocalCompanyAuthorityResult<LocalCompanyFoundingAction> {
            harness.authorityInspects += ProvisionerAuthorityObservation("readOriginalCompanyAndHeadAction",
                harness.provisionerAlias(), "unavailable", harness.log.size)
            harness.log.record("PRODUCTION", "companyAuthority.readOriginalCompanyAndHeadAction", "unavailable")
            return LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
        }
        override fun denyHeadIssuance() {
            harness.provisionerDenials += ProvisionerDenialObservation("denyHeadIssuance",
                harness.provisionerAlias(), harness.script.denyHead, harness.log.size)
            harness.log.record("PRODUCTION", "companyAuthority.denyHeadIssuance", "enter",
                detail = harness.script.denyHead)
            if (harness.script.denyHead == "throw") throw ControlledFailure("EX-DENY-HEAD")
        }
        override suspend fun resumeOriginalCompanyAndHead():
            LocalCompanyAuthorityResult<LocalCompanyFoundingAction> = unavailable()
        override suspend fun readOriginalInstallationHumanReference():
            LocalCompanyAuthorityResult<LocalInstallationHumanReferenceV2> = unavailable()
        override suspend fun prepareNewCompanyAndHead(facts: LocalCompanyFoundingFactsV1):
            LocalCompanyAuthorityResult<LocalCompanyFoundingAction> = unavailable()
        override suspend fun completeNewCompanyAndHead(action: LocalCompanyFoundingAction,
            installerSecret: CharArray, headSecret: CharArray):
            LocalCompanyAuthorityResult<LocalCompanyAuthorityView> {
            installerSecret.fill('\u0000'); headSecret.fill('\u0000'); return unavailable()
        }
        override suspend fun preparePolicyPublication(proposal: LocalCompanyPolicyProposal):
            LocalCompanyAuthorityResult<LocalCompanyPolicyAction> = unavailable()
        override suspend fun approvePolicyPublication(action: LocalCompanyPolicyAction, headLogin: String,
            secret: CharArray): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> {
            secret.fill('\u0000'); return unavailable()
        }
        override suspend fun recoverPolicyPublication(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> = unavailable()
        override suspend fun prepareOriginalInstallationDefinition(proposal: OriginalInstallationProposal):
            LocalCompanyAuthorityResult<LocalCompanyInstallationAction> = unavailable()
        override suspend fun approveOriginalInstallationDefinition(action: LocalCompanyInstallationAction,
            headLogin: String, secret: CharArray): LocalCompanyAuthorityResult<LocalCompanyInstallationApproval> {
            secret.fill('\u0000'); return unavailable()
        }
        override suspend fun readOriginalInstallationAttempt():
            LocalCompanyAuthorityResult<LocalCompanyInstallationApproval> = unavailable()
        override suspend fun acknowledgeOriginalInstallationReceipt(completion: OriginalInstallationCompletion):
            LocalCompanyAuthorityResult<com.ukkera.pos.security.LocalInstallationSignedAcknowledgementV2> = unavailable()
        override suspend fun retireOriginalInstallationAttempt(retirement: OriginalAttemptRetirement):
            LocalCompanyAuthorityResult<Unit> = unavailable()
        override suspend fun withdrawHeadIssuance(purpose: com.ukkera.pos.security.LocalCompanyHeadPurposeV1?):
            LocalCompanyAuthorityResult<Unit> = unavailable()
        private fun <T> unavailable(): LocalCompanyAuthorityResult<T> =
            LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE)
    }

    private inner class ForbiddenIntentStorage : LocalSetupIntentStorage {
        override fun names(): List<String> {
            forbiddenCalls += "employeeIntent.names"; throw ControlledFailure("EX-EMPLOYEE-INTENT")
        }
        override fun read(name: String): ByteArray {
            forbiddenCalls += "employeeIntent.read"; throw ControlledFailure("EX-EMPLOYEE-INTENT")
        }
        override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) {
            forbiddenCalls += "employeeIntent.write"; throw ControlledFailure("EX-EMPLOYEE-INTENT")
        }
        override fun verify() { forbiddenCalls += "employeeIntent.verify" }
    }

    fun positiveTransfer(): TransferObservation? = transferObservations.singleOrNull()

    fun currentnessObservationCount(value: String): Int =
        log.all().count { it.method == "client.isCurrent" && it.detail == value }
}

internal data class ProvisionerOpenObservation(val originalOnly: Boolean, val sequence: Int) {
    var outcome: String = ""
}
internal data class ProvisionerInspectObservation(val sequence: Int) {
    var outcome: String = ""
}
internal data class ProvisionerAuthorityObservation(
    val method: String,
    val alias: String,
    val outcome: String,
    val sequence: Int,
)
internal data class ProvisionerDenialObservation(
    val method: String,
    val alias: String,
    val scripted: String,
    val sequence: Int,
)
internal data class ProvisionerCloseObservation(
    val alias: String,
    val scripted: String,
    val sequence: Int,
) {
    var outcome: String = ""
}

internal class SupplementalLaunchStorage(
    initialBytes: ByteArray?,
    private val log: StartupObservationLog,
) : LocalMasterLaunchStorage {
    var bytes: ByteArray? = initialBytes?.copyOf()
        private set
    private var readThrows = false
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
            detail = "read=$reads bytes=${current?.size ?: "absent"} " +
                "digest=${current?.let { sha256(it) } ?: "absent"}")
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

    fun armReadThrow() {
        readThrows = true
        log.record("TEST", "launchStorage.read", "armed-throw")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class SupplementalExecution(
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
