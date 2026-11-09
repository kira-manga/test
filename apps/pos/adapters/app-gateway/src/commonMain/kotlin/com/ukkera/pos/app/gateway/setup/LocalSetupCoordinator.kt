package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.setup.LocalSetupEmployeeEnrollmentStage
import com.ukkera.pos.app.setup.LocalSetupEmployeeEnrollmentView
import com.ukkera.pos.app.setup.LocalSetupEmployeeEnrollments
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicies
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyOutcome
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyStage
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissions
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionOutcome
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionStage
import com.ukkera.pos.app.setup.LocalSetupFreshDevicePairingRole
import com.ukkera.pos.app.setup.LocalSetupFreshDevicePairingStage
import com.ukkera.pos.app.setup.LocalSetupFreshDeviceTargetInput
import com.ukkera.pos.app.setup.LocalSetupLanEndpoint
import com.ukkera.pos.app.setup.LocalSetupLanInput
import com.ukkera.pos.app.setup.LocalSetupLanRole
import com.ukkera.pos.app.setup.LocalSetupLanStatus
import com.ukkera.pos.app.setup.LocalSetupLanView
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentKind
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentOutcome
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentView
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.sha256
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalEmployeeEnrollmentPhase
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationPhase
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingMasterTransfer
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPhase
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingProfileV1
import com.ukkera.pos.security.LocalDevicePairingProfileV2
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalDevicePairingTargetPhase
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.app.setup.LocalSetupBranchReview
import com.ukkera.pos.app.setup.LocalSetupCompanyReview
import com.ukkera.pos.app.setup.LocalSetupCompanyView
import com.ukkera.pos.app.setup.LocalSetupController
import com.ukkera.pos.app.setup.LocalSetupDefinitionReview
import com.ukkera.pos.app.setup.LocalSetupFirstAccountInput
import com.ukkera.pos.app.setup.LocalSetupNativeView
import com.ukkera.pos.app.setup.LocalSetupNewCompanyInput
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.app.setup.LocalSetupReceiptView
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.app.setup.LocalSetupWork
import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.database.OriginalInstallationReceiptResult
import com.ukkera.pos.security.LocalCompanyAuthorityFailure
import com.ukkera.pos.security.LocalBranchInstallationReceiptV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompanyAuthorityView
import com.ukkera.pos.security.LocalCompanyFoundingAction
import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.LocalProvisionerActionKind
import com.ukkera.pos.security.LocalProvisionerCompanyView
import com.ukkera.pos.security.LocalProvisionerGrant
import com.ukkera.pos.security.LocalProvisionerInspection
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalProvisionerPendingAction
import com.ukkera.pos.security.LocalProvisionerPreparation
import com.ukkera.pos.security.LocalProvisioner
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedBranchFactsV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.SelfOwnedNativeReadResult
import com.ukkera.pos.security.SelfOwnedPreparationPacketV1
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Shared setup/enrollment/recovery state machine. The platform supplies concrete native owners
 * and OS adapters; no platform implements its own ceremony, original-ID or restart rules.
 * Ports are composition, not authority: only the actual native services may grant installation,
 * sessions or master access. UI review IDs remain stale-presentation discriminators only.
 */
public class LocalSetupCoordinator(
    private val services: LocalSetupNativeServices,
    private val employeeSetupIntents: LocalEmployeeSetupIntents,
    private val launchRecord: LocalMasterLaunchRecord,
    private val execution: LocalSetupExecution,
    /** Immediate same-process unpublication; never a native/admission provider. */
    private val beforeRetirement: (LocalSetupCoordinator, Deferred<Unit>) -> Unit,
    /** Native hosting notice/lifecycle plumbing, not master authority. */
    private val beforeClientHosting: () -> Unit,
    /** Separate original-intent namespace. Absence cannot enable device admission. */
    private val deviceAdmissionIntents: LocalDeviceAdmissionIntents? = null,
    private val freshDevicePairingIntents: LocalFreshDevicePairingIntents? = null,
    private val pairingDocuments: LocalSetupPairingDocuments? = null,
) : LocalSetupController {
    private val gate = LocalSetupMonitor()
    private val scope = CoroutineScope(SupervisorJob() + execution.dispatcher)
    private val published = MutableStateFlow(LocalSetupView())
    override val state: StateFlow<LocalSetupView> = published.asStateFlow()
    private var view = LocalSetupView()
    private var native: OwnedNative? = null
    private var automaticReopenAttempted = false
    private var foundingAction: LocalCompanyFoundingAction? = null
    private var branchAction: LocalProvisionerPendingAction? = null
    private var retainedPreparation: SelfOwnedPreparationPacketV1? = null
    private var freshTargetOriginal: LocalSetupFreshDeviceTargetOriginal? = null
    // A terminal attachment remains bound to this exact native owner/configuration. Neither
    // failed construction nor LAN-only close re-arms another factory in this setup lifetime.
    private var lanAttachment: LanAttachment? = null
    private var active: Work? = null
    private var retiring = false
    private var retirement: CompletableDeferred<Unit>? = null
    private var retiringAt: Long? = null
    private var retirementWatchMillis = OPERATION_MILLIS
    // Set before acquisition. A partial holder retains any returned actual owner even if the
    // handoff fails. With no returned handle, the factory's cleanup remains unacknowledged;
    // neither an empty holder nor a refusal proves that its native lease has been released.
    private var acquisitionCloseUnobserved = false
    private val clockGate = LocalSetupMonitor()
    private var lastElapsed = execution.elapsedMillis()

    init {
        try {
            check(lastElapsed >= 0)
            execution.startWatching(::observeDrain)
        } catch (failure: Throwable) {
            execution.stopWatching(); scope.cancel(); throw failure
        }
    }

    override fun chooseNewLocalCompany(): Unit = withSetupLock(gate) {
        if (!canAdmit()) return@withSetupLock
        if (view.phase != LocalSetupPhase.CHOOSE_ROUTE || native != null) {
            problemLocked(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED); return@withSetupLock
        }
        view = view.copy(phase = LocalSetupPhase.NEW_COMPANY_DETAILS, problem = null)
        publishLocked()
    }

    override fun inspectOriginalCompany() {
        start(LocalSetupWork.READING_ORIGINAL_COMPANY, setOf(LocalSetupPhase.CHOOSE_ROUTE)) {
            operation {
                val owner = acquireProvisioner(originalOnly = true) ?: return@operation
                if (continueWork()) inspectCompany(owner)
            }
        }
    }

    override fun inspectOriginalInstallation() {
        start(LocalSetupWork.READING_ORIGINAL_RECEIPT, setOf(LocalSetupPhase.CHOOSE_ROUTE)) {
            operation { readOriginalTarget() }
        }
    }

    override fun prepareNewLocalCompany(input: LocalSetupNewCompanyInput) {
        start(LocalSetupWork.PREPARING_COMPANY, setOf(LocalSetupPhase.NEW_COMPANY_DETAILS)) {
            // Immutable bounded facts only, constructed after admission. No password is queued.
            val facts = LocalCompanyFoundingFactsV1(
                SelfOwnedCompanyFactsV1(input.companyName, input.installerName, input.installerLogin, input.protection),
                input.headName, input.headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
                LocalCompanyHeadPurposeV1.entries.toSet(),
            )
            operation {
                val owner = acquireProvisioner(originalOnly = false) ?: return@operation
                if (!continueWork()) return@operation
                // Selecting NEW does not overwrite a found original. Only the concrete native
                // Unenrolled observation on this explicitly selected route reaches prepare-new.
                when (val existing = owner.value.inspect()) {
                    LocalProvisionerInspection.Unenrolled -> {
                        if (continueWork()) when (val result = owner.value.companyAuthority.prepareNewCompanyAndHead(facts)) {
                            is LocalCompanyAuthorityResult.Value -> presentFounding(owner, result.value)
                            is LocalCompanyAuthorityResult.Unavailable -> authorityFailure(result)
                        }
                    }
                    else -> if (continueWork()) inspectCompany(owner, existing)
                }
            }
        }
    }

    override fun resumeOriginalCompany() {
        start(LocalSetupWork.RESUMING_ORIGINAL_COMPANY, setOf(LocalSetupPhase.ORIGINAL_COMPANY_PENDING)) {
            val owner = requireProvisioner()
            operation {
                // No caller facts, new IDs, password or missing-file flag. Native alone can
                // resume its retained exact key intents; uncertain STARTED intents stay held.
                when (val result = owner.value.companyAuthority.resumeOriginalCompanyAndHead()) {
                    is LocalCompanyAuthorityResult.Value -> presentFounding(owner, result.value)
                    is LocalCompanyAuthorityResult.Unavailable -> authorityFailure(result)
                }
            }
        }
    }

    override fun completeNewLocalCompany(reviewId: DomainId, installerSecret: CharArray, headSecret: CharArray) {
        try {
            start(LocalSetupWork.RETAINING_COMPANY, setOf(LocalSetupPhase.COMPANY_REVIEW)) {
                val owner = requireProvisioner()
                val action = requireNotNull(foundingAction)
                requireOriginal(view.companyReview?.reviewId == reviewId && !action.enrollmentRetained)
                require(installerSecret !== headSecret && installerSecret.size in 12..256 && headSecret.size in 12..256)
                val installer = installerSecret.copyOf()
                val head = try { headSecret.copyOf() } catch (failure: Throwable) { installer.fill('\u0000'); throw failure }
                try {
                    operation(clear = { installer.fill('\u0000'); head.fill('\u0000') }) {
                        // An unavailable/lost answer cannot leave a replacement-password button.
                        change { it.copy(phase = LocalSetupPhase.ORIGINAL_UNRESOLVED, companyReview = null) }
                        when (val result = owner.value.companyAuthority.completeNewCompanyAndHead(action, installer, head)) {
                            is LocalCompanyAuthorityResult.Value -> if (continueWork()) inspectCompany(owner)
                            is LocalCompanyAuthorityResult.Unavailable -> authorityFailure(result)
                        }
                    }
                } catch (failure: Throwable) { installer.fill('\u0000'); head.fill('\u0000'); throw failure }
            }
        } finally { installerSecret.fill('\u0000'); headSecret.fill('\u0000') }
    }

    override fun prepareOriginalBranch(branchName: String) {
        start(LocalSetupWork.PREPARING_BRANCH, setOf(LocalSetupPhase.COMPANY_RETAINED)) {
            val owner = requireProvisioner()
            val company = requireNotNull(view.company)
            val facts = SelfOwnedBranchFactsV1(company.rootFingerprint, branchName, company.protection)
            operation {
                when (val result = owner.value.prepareBranchWithEnrolledCompany(facts)) {
                    is LocalProvisionerPreparation.Prepared -> presentBranch(result.action, company)
                    is LocalProvisionerPreparation.Unavailable -> nativeFailure(result.reason)
                }
            }
        }
    }

    override fun issueOriginalBranch(reviewId: DomainId, installerSecret: CharArray) {
        try {
            start(LocalSetupWork.RETAINING_PREPARATION, setOf(LocalSetupPhase.BRANCH_REVIEW)) {
                val owner = requireProvisioner()
                val action = requireNotNull(branchAction)
                requireOriginal(view.branchReview?.reviewId == reviewId)
                require(installerSecret.size in 12..256)
                val secret = installerSecret.copyOf()
                try {
                    operation(clear = { secret.fill('\u0000') }) {
                        change { it.copy(phase = LocalSetupPhase.ORIGINAL_UNRESOLVED) }
                        when (val result = owner.value.issueOriginalPreparation(action, action.installerLogin, secret)) {
                            is LocalProvisionerGrant.Retained -> presentPreparation(action, result.packet)
                            is LocalProvisionerGrant.Unresolved -> nativeFailure(result.reason)
                        }
                    }
                } catch (failure: Throwable) { secret.fill('\u0000'); throw failure }
            }
        } finally { installerSecret.fill('\u0000') }
    }

    override fun prepareOriginalNativeTarget() {
        start(LocalSetupWork.PREPARING_ORIGINAL_TARGET, setOf(LocalSetupPhase.ORIGINAL_PREPARATION_RETAINED)) {
            val owner = requireProvisioner()
            val action = requireNotNull(branchAction)
            val previouslyRetained = requireNotNull(retainedPreparation)
            operation {
                // Re-read only the positive original packet before handing ownership to P2.
                val packet = when (val result = owner.value.recoverOriginalPreparation()) {
                    is LocalProvisionerGrant.Retained -> result.packet
                    is LocalProvisionerGrant.Unresolved -> { nativeFailure(result.reason); return@operation }
                }
                requireOriginal(sameCoordinates(packet.coordinates, previouslyRetained.coordinates) &&
                    sameCoordinates(packet.coordinates, requireNotNull(action.allocation)))
                if (!continueWork()) return@operation
                closeNativeOwner(owner) // ACTUAL awaited P1 close; not deny/cancel/health.
                if (!continueWork()) return@operation
                val target = acquireTarget(preparation = true) ?: return@operation
                if (!continueWork()) return@operation
                readNative(target, packet.coordinates, action.branchName)
            }
        }
    }

    override fun openOriginalFirstAccount() {
        start(LocalSetupWork.OPENING_ORIGINAL_INSTALLATION, FIRST_ACCOUNT_OPEN_PHASES) {
            operation { openOriginalInstallation() }
        }
    }

    override fun prepareFirstAccount(input: LocalSetupFirstAccountInput) {
        start(LocalSetupWork.REVIEWING_FIRST_ACCOUNT, setOf(LocalSetupPhase.FIRST_ACCOUNT_DETAILS)) {
            val target = requireTarget()
            val master = requireNotNull(target.master)
            val headLogin = requireNotNull(view.firstAccountHeadLogin)
            operation {
                val snapshot = master.prepareOriginalDefinition(input.account, input.authorityEpoch)
                target.definition = snapshot
                if (!continueWork()) return@operation
                change { it.copy(phase = LocalSetupPhase.FIRST_ACCOUNT_REVIEW,
                    definitionReview = LocalSetupDefinitionReview(DomainId.random(), snapshot, headLogin), problem = null) }
            }
        }
    }

    override fun installFirstAccount(reviewId: DomainId, headSecret: CharArray, employeePassword: CharArray) {
        try {
            start(LocalSetupWork.INSTALLING_FIRST_ACCOUNT, setOf(LocalSetupPhase.FIRST_ACCOUNT_REVIEW)) {
                val target = requireTarget()
                val master = requireNotNull(target.master)
                val review = requireNotNull(view.definitionReview)
                requireOriginal(review.reviewId == reviewId && review.snapshot === target.definition)
                require(headSecret.size in 12..256 && employeePassword.size in 4..256)
                val head = headSecret.copyOf()
                var employee: CharArray? = null
                try {
                    val password = employeePassword.copyOf().also { employee = it }
                    operation(clear = { head.fill('\u0000'); password.fill('\u0000') }) {
                        // Detach the secret form immediately; the actual admitted job owns its
                        // private buffers through native approval, SQL settlement and ACK.
                        change { it.copy(phase = LocalSetupPhase.ORIGINAL_UNRESOLVED, definitionReview = null) }
                        presentInstallationResult(master.installOriginal(review.snapshot.definition, review.headLogin, head, password))
                    }
                } catch (failure: Throwable) { head.fill('\u0000'); employee?.fill('\u0000'); throw failure }
            }
        } finally { headSecret.fill('\u0000'); employeePassword.fill('\u0000') }
    }

    override fun resumeOriginalInstallationAcknowledgement() {
        start(LocalSetupWork.ACKNOWLEDGING_ORIGINAL_INSTALLATION, setOf(LocalSetupPhase.INSTALLATION_ACK_PENDING)) {
            val master = requireNotNull(requireTarget().master)
            operation { presentInstallationResult(master.resumeOriginalAcknowledgement()) }
        }
    }

    override fun enrollOriginalLocalClient(): Unit = connectOriginalClient(LocalOriginalClientJournalAction.ENROLL_FIRST_CLIENT)
    override fun continueOriginalLocalClientEnrollment(): Unit = connectOriginalClient(LocalOriginalClientJournalAction.CONTINUE_FIRST_CLIENT)
    override fun reconnectOriginalLocalClient(): Unit = connectOriginalClient(LocalOriginalClientJournalAction.REOPEN_EXISTING)
    override fun recoverOriginalLocalClientWrites(): Unit = connectOriginalClient(LocalOriginalClientJournalAction.RECOVER_PENDING_WRITES)

    /** App reopening is a trigger, never permission to install, promote, unlock or sign in. */
    public fun reopenRetainedMaster(): Unit {
        withSetupLock(gate) {
            if (automaticReopenAttempted || retiring || active != null || native != null ||
                view.phase != LocalSetupPhase.CHOOSE_ROUTE) return
            automaticReopenAttempted = true // One attempt per owner; no automatic retry loop.
        }
        start(LocalSetupWork.OPENING_ORIGINAL_INSTALLATION, setOf(LocalSetupPhase.CHOOSE_ROUTE), allowLocked = true) {
            operation {
                when (val retained = launchRecord.read()) {
                    LocalMasterLaunchRecord.Read.Absent -> Unit // Fresh installation: no native open.
                    LocalMasterLaunchRecord.Read.Stopped -> change {
                        it.copy(problem = LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED)
                    }
                    LocalMasterLaunchRecord.Read.Unresolved -> change {
                        it.copy(problem = LocalSetupProblem.AUTOMATIC_REOPEN_UNAVAILABLE)
                    }
                    is LocalMasterLaunchRecord.Read.Running -> {
                        lockPresentation() // Backend startup does not unlock private setup or employee UI.
                        openOriginalInstallation(expectedLaunch = retained)
                        if (!continueWork()) return@operation
                        val target = withSetupLock(gate) { native as? Target } ?: return@operation
                        val master = target.master ?: return@operation
                        if (master.mode.value != LocalMasterLifecycleMode.OPERATIONAL ||
                            target.localClient != null || !retained.matches(target.context.materialAtOpen.coordinates)) return@operation
                        beforeClientHosting()
                        attachOriginalClient(target, master, LocalOriginalClientJournalAction.REOPEN_EXISTING,
                            automatic = true)
                    }
                }
            }
        }
    }

    private fun connectOriginalClient(action: LocalOriginalClientJournalAction) {
        start(LocalSetupWork.CONNECTING_ORIGINAL_LOCAL_CLIENT, setOf(LocalSetupPhase.INSTALLATION_RETAINED)) {
            val target = requireTarget()
            val master = requireNotNull(target.master)
            requireOriginal(target.localClient == null && master.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
            beforeClientHosting() // While the explicit visible UI request is being admitted.
            operation { attachOriginalClient(target, master, action, automatic = false) }
        }
    }

    private suspend fun attachOriginalClient(target: Target, master: LocalSetupMaster,
        action: LocalOriginalClientJournalAction, automatic: Boolean) {
        withSetupLock(gate) {
            requireOriginal(!retiring && native === target && !acquisitionCloseUnobserved)
            target.beginClientAttachment()
            acquisitionCloseUnobserved = true
        }
        // A throw settles this call but not native custody: a partial native Client may exist
        // while no public handle was returned. Record the settled-unknown state; never retry,
        // never fabricate a Client and never infer absence from the throw.
        val client = try {
            services.connectClient(master, action)
        } catch (failure: Throwable) {
            withSetupLock(gate) { target.settleFailedClientAttachment() }
            throw failure
        }
        target.attachClient(client) // Capture the actual owner BEFORE checks/publication.
        retainNative(target)
        if (!continueWork()) return
        if (client == null) {
            val mayContinueEnrollment = master.mode.value == LocalMasterLifecycleMode.OPERATIONAL &&
                master.permitsFirstClientEnrollment()
            change { it.copy(localClientConnected = false, firstClientEnrollmentAvailable = false,
                firstClientEnrollmentContinuationAvailable = mayContinueEnrollment,
                problem = LocalSetupProblem.CLIENT_JOURNAL_UNAVAILABLE) }
            return
        }
        withSetupLock(gate) {
            requireOriginal(!retiring && native === target && client.isCurrent())
            val coordinates = target.context.materialAtOpen.coordinates
            if (automatic) requireOriginal((launchRecord.read() as? LocalMasterLaunchRecord.Read.Running)
                ?.matches(coordinates) == true)
            else launchRecord.retainRunning(coordinates)
            // Saving a launch hint cannot make an expired/native-held runtime usable.
            requireOriginal(!retiring && client.isCurrent() && master.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
            services.publishClient(target, client)
            view = view.copy(localClientConnected = true, firstClientEnrollmentAvailable = false,
                firstClientEnrollmentContinuationAvailable = false, problem = null)
            publishLocked()
        }
        watchOriginalClient(target, master)
    }

    private fun watchOriginalClient(target: Target, master: LocalSetupMaster) {
        target.watchClient(scope.launch {
            master.mode.collect { mode ->
                if (mode != LocalMasterLifecycleMode.OPERATIONAL) {
                    services.withdrawClient(target)
                    withSetupLock(gate) {
                        if (native === target && !retiring) {
                            view = view.copy(localClientConnected = false, firstClientEnrollmentAvailable = false,
                                firstClientEnrollmentContinuationAvailable = false,
                                localClientConnectionAvailable = false, problem = LocalSetupProblem.RUNTIME_PUBLICATION_HELD,
                                locked = view.locked || view.phase == LocalSetupPhase.EMPLOYEE_POLICY || view.phase == LocalSetupPhase.DEVICE_ADMISSION,
                                employeePolicy = view.employeePolicy?.copy(stage = LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED,
                                    outcome = null, eventId = null),
                                deviceAdmission = view.deviceAdmission?.copy(reviewId = DomainId.random(),
                                    stage = deviceAdmissionUnresolvedStage(target.deviceAdmissionOriginal), outcome = null, eventId = null))
                            publishLocked()
                        }
                    }
                }
            }
        })
    }

    override fun startLanHost(input: LocalSetupLanInput) {
        start(LocalSetupWork.STARTING_LAN_HOST, LAN_HOST_PHASES) {
            val settings = LocalSetupLanSettings.from(input)
            val target = requireTarget()
            val master = target.master ?: throw OriginalRequired()
            requireOriginal(master.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
            val retained = lanAttachment
            requireOriginal(retained == null || retained is LanHost && retained.owner === target &&
                retained.master === master && retained.settings.sameSelection(settings) && retained.canObserve)
            beforeClientHosting() // The actual service notice precedes explicit Host activation.
            operation {
                val attachment = (retained as? LanHost) ?: withSetupLock(gate) {
                    if (retiring) return@withSetupLock null
                    requireOriginal(native === target && lanAttachment == null && !acquisitionCloseUnobserved &&
                        master.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
                    LanHost(target, master, settings, newLanScope()).also {
                        lanAttachment = it // BEFORE the native constructor can retain or claim anything.
                        publishLocked()
                    }
                } ?: return@operation
                try {
                    if (!attachment.captured) {
                        val actual = services.retainLanHost(master, attachment.retainedScope, settings)
                        attachment.capture(actual) // Direct retention before checks or publication.
                    }
                    if (!beginLanObservation(attachment)) return@operation
                    val endpoint = attachment.value.start()
                    publishLanEndpoint(attachment, endpoint)
                } catch (_: Throwable) { lanObservationFailed(attachment) }
            }
        }
    }

    override fun connectLanClient(input: LocalSetupLanInput, endpoint: LocalSetupLanEndpoint) {
        start(LocalSetupWork.CONNECTING_LAN_CLIENT, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val settings = LocalSetupLanSettings.from(input)
            settings.validateEndpoint(endpoint) // Numeric private address/subnet and nonzero port.
            val target = (native as? FreshDeviceTarget) ?: throw OriginalRequired()
            val original = target.original
            val actionId = original.responseActionId ?: throw OriginalRequired()
            val verified = original.verifiedMaster ?: throw OriginalRequired()
            requireOriginal(freshTargetOriginal === original && verified.originalPairingActionId == actionId &&
                original.identity != null && original.possession != null && !original.continuationAttempted)
            requireFreshRpcNamespace(requireNotNull(original.identity))
            val retained = lanAttachment
            requireOriginal(retained == null && freshTargetCurrent(target, original) || retained is LanClient &&
                retained.owner === target && retained.original === original && retained.actionId == actionId &&
                retained.selectedEndpoint == endpoint && retained.settings.sameSelection(settings) && retained.canObserve)
            operation {
                val attachment = (retained as? LanClient) ?: withSetupLock(gate) {
                    if (retiring) return@withSetupLock null
                    requireOriginal(native === target && lanAttachment == null && !acquisitionCloseUnobserved &&
                        freshTargetCurrent(target, original) && original.responseActionId == actionId &&
                        original.verifiedMaster === verified)
                    LanClient(target, original, actionId, settings, endpoint, newLanScope()).also {
                        lanAttachment = it
                        // Fence common ceremonies only. Native original readbacks must happen
                        // before raw-target denial/close inside the selected-original adapter.
                        target.lanTransferEntered = true
                        view = view.copy(freshDevicePairing = null)
                        publishLocked()
                    }
                } ?: return@operation
                try {
                    if (!attachment.captured) {
                        val actual = services.retainLanClient(target.value, attachment.retainedScope, actionId, settings)
                        attachment.capture(actual) // Unknown constructor results never remove this holder.
                    }
                    if (!beginLanObservation(attachment)) return@operation
                    attachment.value.connect(attachment.selectedEndpoint)
                    publishLanAuthentication(attachment)
                    publishLanEndpoint(attachment, attachment.selectedEndpoint)
                } catch (_: Throwable) { lanObservationFailed(attachment) }
            }
        }
    }

    private suspend fun publishLanAuthentication(attachment: LanClient) {
        if (attachment.settings.credentialAction == null) return
        // Producer retains the actual gateway/journals before returning; LAN close owns them.
        val authentication = attachment.value.authentication() ?: error("LAN authentication is unavailable")
        withSetupLock(gate) {
            if (!lanOwnerCurrentLocked(attachment)) { authentication.denyNewWork(); return@withSetupLock }
            check(attachment.authentication == null || attachment.authentication === authentication)
            attachment.authentication = authentication // Retain before currentness or publication.
            check(authentication.isCurrent()) { "Original authentication attachment is retired" }
            if (!attachment.authenticationPublished) {
                services.publishLanAuthentication(attachment, attachment.value, authentication)
                attachment.authenticationPublished = true
            }
        }
    }

    private fun withdrawLanAuthenticationLocked(attachment: LanClient) {
        attachment.authenticationPublished = false // No positive view survives callback failure.
        try { services.withdrawClient(attachment) } // Exact publication, never another Client owner.
        catch (failure: Throwable) { attachment.authenticationDenialFailure = attachment.authenticationDenialFailure ?: failure }
        try { attachment.authentication?.denyNewWork() }
        catch (failure: Throwable) { attachment.authenticationDenialFailure = attachment.authenticationDenialFailure ?: failure }
        // Denial failure remains retained, but must not skip independent drain/native cleanup.
    }

    override fun refreshLanAttachment() {
        start(LocalSetupWork.READING_LAN_ATTACHMENT, LAN_ACTION_PHASES) {
            val attachment = lanAttachment ?: throw OriginalRequired()
            requireOriginal(attachment.captured && attachment.canObserve && attachment.observedEndpoint != null)
            operation {
                try {
                    if (!beginLanObservation(attachment)) return@operation
                    when (attachment) {
                        is LanHost -> publishLanEndpoint(attachment, attachment.value.endpoint())
                        is LanClient -> {
                            // The retained native operation reobserves the same Ready connection;
                            // it never creates another connection attempt or selected factory.
                            attachment.value.connect(attachment.selectedEndpoint)
                            publishLanEndpoint(attachment, attachment.selectedEndpoint)
                        }
                    }
                } catch (_: Throwable) { lanObservationFailed(attachment) }
            }
        }
    }

    override fun closeLanAttachment() {
        start(LocalSetupWork.CLOSING_LAN_ATTACHMENT, LAN_ACTION_PHASES) {
            val attachment = lanAttachment ?: throw OriginalRequired()
            operation {
                try { closeLanOwned(attachment) }
                catch (_: Throwable) { /* Unknown close stays retained; it is never Master stop. */ }
            }
        }
    }

    private fun newLanScope(): CoroutineScope = CoroutineScope(
        scope.coroutineContext.minusKey(Job) + SupervisorJob(checkNotNull(scope.coroutineContext[Job])),
    )

    private fun lanOwnerCurrentLocked(attachment: LanAttachment): Boolean {
        if (retiring || lanAttachment !== attachment || native !== attachment.owner ||
            !checkNotNull(attachment.retainedScope.coroutineContext[Job]).isActive || !attachment.canObserve) return false
        return when (attachment) {
            is LanHost -> attachment.target.master === attachment.master &&
                attachment.master.mode.value == LocalMasterLifecycleMode.OPERATIONAL
            is LanClient -> attachment.target.original === attachment.original &&
                freshTargetOriginal === attachment.original && attachment.original.responseActionId == attachment.actionId &&
                attachment.original.verifiedMaster?.originalPairingActionId == attachment.actionId
        }
    }

    private fun beginLanObservation(attachment: LanAttachment): Boolean = withSetupLock(gate) {
        if (!lanOwnerCurrentLocked(attachment)) {
            if (!retiring && lanAttachment === attachment) {
                attachment.status = LocalSetupLanStatus.UNAVAILABLE
                attachment.endpoint = null
                problemLocked(LocalSetupProblem.NATIVE_UNAVAILABLE)
            }
            return@withSetupLock false
        }
        attachment.status = if (attachment is LanHost) LocalSetupLanStatus.STARTING else LocalSetupLanStatus.CONNECTING
        attachment.endpoint = null // A pending read never republishes a stale actual endpoint.
        publishLocked()
        true
    }

    private fun publishLanEndpoint(attachment: LanAttachment, endpoint: LocalSetupLanEndpoint) {
        attachment.settings.validateEndpoint(endpoint)
        when (attachment) {
            is LanHost -> require(endpoint.address == attachment.settings.lan.localAddress &&
                (attachment.settings.lan.listenPort == 0 || endpoint.port == attachment.settings.lan.listenPort))
            is LanClient -> require(endpoint == attachment.selectedEndpoint)
        }
        withSetupLock(gate) {
            if (!lanOwnerCurrentLocked(attachment)) {
                if (!retiring && lanAttachment === attachment) {
                    attachment.status = LocalSetupLanStatus.UNAVAILABLE
                    attachment.endpoint = null
                    problemLocked(LocalSetupProblem.NATIVE_UNAVAILABLE)
                }
                return@withSetupLock
            }
            attachment.observedEndpoint?.let { require(it == endpoint) } // No changed listener/endpoint retarget.
            attachment.observedEndpoint = endpoint
            attachment.endpoint = endpoint
            attachment.status = if (attachment is LanHost) LocalSetupLanStatus.LISTENING else LocalSetupLanStatus.CONNECTED
            view = view.copy(problem = null, nativeProblem = null)
            publishLocked() // Endpoint observation supplies no employee/business authority.
        }
    }

    private fun lanObservationFailed(attachment: LanAttachment) = withSetupLock(gate) {
        if (retiring || lanAttachment !== attachment) return@withSetupLock
        if (attachment is LanClient) withdrawLanAuthenticationLocked(attachment)
        attachment.endpoint = null
        val denialUnconfirmed = attachment is LanClient && attachment.authenticationDenialFailure != null
        attachment.status = if (attachment.captured && !denialUnconfirmed) LocalSetupLanStatus.UNAVAILABLE else LocalSetupLanStatus.UNKNOWN
        problemLocked(if (attachment.captured && !denialUnconfirmed) LocalSetupProblem.NATIVE_UNAVAILABLE else LocalSetupProblem.CLOSE_UNCONFIRMED)
    }

    private suspend fun closeLanOwned(attachment: LanAttachment) {
        val selected = withSetupLock(gate) {
            check(lanAttachment === attachment)
            attachment.closeResult?.let { return@withSetupLock it to false }
            val outcome = CompletableDeferred<Unit>()
            attachment.closeResult = outcome // A failed/unknown exact close is never rearmed.
            if (attachment is LanClient) withdrawLanAuthenticationLocked(attachment)
            attachment.status = LocalSetupLanStatus.CLOSING
            attachment.endpoint = null
            publishLocked()
            outcome to true
        }
        val outcome = selected.first
        if (!selected.second) { outcome.await(); return }
        try {
            // Callers reach this only after admitted LAN work has drained. Cancelling this
            // child fences SDK publication while the coordinator's retirement worker lives.
            attachment.retainedScope.cancel()
            attachment.closeActual()
            checkNotNull(attachment.retainedScope.coroutineContext[Job]).cancelAndJoin()
            if (attachment is LanClient) withSetupLock(gate) { attachment.authenticationDenialFailure }?.let { throw it }
            withSetupLock(gate) {
                check(lanAttachment === attachment)
                attachment.status = LocalSetupLanStatus.CLOSED
                if (!retiring) view = view.copy(problem = null, nativeProblem = null)
                publishLocked()
            }
            outcome.complete(Unit)
        } catch (failure: Throwable) {
            withSetupLock(gate) {
                attachment.status = LocalSetupLanStatus.UNKNOWN
                attachment.endpoint = null
                problemLocked(LocalSetupProblem.CLOSE_UNCONFIRMED)
            }
            outcome.completeExceptionally(failure)
            throw failure
        }
    }

    override fun openEmployeeEnrollment() {
        start(LocalSetupWork.READING_EMPLOYEE_ENROLLMENT, EMPLOYEE_OPEN_PHASES) {
            val target = requireEmployeeTarget()
            val client = requireNotNull(target.localClient)
            operation {
                val originals = employeeOriginals(client) ?: return@operation
                val records = employeeRecords(target)
                val pending = records.singleOrNull { !it.complete }
                val mayBegin = pending == null && originals.all { original ->
                    original.phase in setOf(LocalEmployeeEnrollmentPhase.CONSUMED, LocalEmployeeEnrollmentPhase.REJECTED) &&
                        records.any { it.identity.requestId == original.originalRequestId && it.complete }
                }
                change { it.copy(phase = LocalSetupPhase.EMPLOYEE_ENROLLMENT,
                    employeeEnrollments = LocalSetupEmployeeEnrollments(originals, mayBegin),
                    employeeEnrollment = null, problem = null) }
                if (pending != null) inspectEmployeeOriginal(target, client, pending.identity.requestId, originals)
            }
        }
    }

    override fun beginEmployeeEnrollment() {
        start(LocalSetupWork.RETAINING_EMPLOYEE_ENROLLMENT, setOf(LocalSetupPhase.EMPLOYEE_ENROLLMENT)) {
            val target = requireEmployeeTarget()
            val client = requireNotNull(target.localClient)
            requireOriginal(view.employeeEnrollments?.newDefinitionAvailable == true && view.employeeEnrollment == null)
            operation {
                val originals = employeeOriginals(client) ?: return@operation
                val records = employeeRecords(target)
                requireOriginal(records.none { !it.complete } && originals.all { original ->
                    original.phase in setOf(LocalEmployeeEnrollmentPhase.CONSUMED, LocalEmployeeEnrollmentPhase.REJECTED) &&
                        records.any { it.identity.requestId == original.originalRequestId && it.complete }
                })
                val material = target.context.materialAtOpen.coordinates
                val identity = LocalEmployeeSetupIntents.Identity(material.installationId, material.scope,
                    material.deviceId, DomainId.random(), DomainId.random())
                requireOriginal(identity.actorId != material.firstActorId)
                target.employeeIdentity = identity
                target.employeeAction = null; target.employeeConnection = null; target.employeeJournalOpenedRequestId = null
                // Publish the original IDs before the first exclusive write. Unknown writes keep
                // those same IDs visible for inspection, never another fresh allocation.
                change { it.copy(employeeEnrollments = LocalSetupEmployeeEnrollments(originals, false),
                    employeeEnrollment = employeeView(identity, LocalSetupEmployeeEnrollmentStage.ORIGINAL_UNRESOLVED)) }
                employeeSetupIntents.begin(identity)
                change { it.copy(employeeEnrollment = employeeView(identity, LocalSetupEmployeeEnrollmentStage.DEFINITION), problem = null) }
            }
        }
    }

    override fun inspectEmployeeEnrollment(originalRequestId: DomainId) {
        start(LocalSetupWork.READING_EMPLOYEE_ENROLLMENT, setOf(LocalSetupPhase.EMPLOYEE_ENROLLMENT)) {
            val target = requireEmployeeTarget()
            val client = requireNotNull(target.localClient)
            operation { inspectEmployeeOriginal(target, client, originalRequestId) }
        }
    }

    override fun prepareEmployeeEnrollment(account: LocalAccountProvisioning) {
        start(LocalSetupWork.REVIEWING_EMPLOYEE_ENROLLMENT, setOf(LocalSetupPhase.EMPLOYEE_ENROLLMENT)) {
            val target = requireEmployeeTarget()
            val client = requireNotNull(target.localClient)
            val identity = requireNotNull(target.employeeIdentity)
            requireOriginal(view.employeeEnrollment?.stage == LocalSetupEmployeeEnrollmentStage.DEFINITION &&
                account.actorId == identity.actorId && account.policy.scope == identity.scope &&
                account.admittedDeviceIds == setOf(identity.deviceId))
            operation {
                val original = employeeSetupIntents.readOriginal(identity)
                requireOriginal(!original.preparing && original.actionId == null && !original.complete)
                employeeSetupIntents.markPreparing(identity) // BEFORE native prepare can allocate/admit anything.
                change { it.copy(employeeEnrollment = employeeView(identity, LocalSetupEmployeeEnrollmentStage.ORIGINAL_UNRESOLVED)) }
                when (val result = client.prepareEmployeeEnrollment(identity.requestId, account)) {
                    is LocalCompanyAuthorityResult.Value -> {
                        val action = result.value
                        employeeSetupIntents.retainNativeOriginal(identity, action)
                        target.employeeAction = action
                        presentEmployeeReview(identity, action)
                    }
                    is LocalCompanyAuthorityResult.Unavailable -> employeeAuthorityFailure(result)
                }
            }
        }
    }

    override fun approveEmployeeEnrollment(reviewId: DomainId, headSecret: CharArray) {
        try {
            start(LocalSetupWork.APPROVING_EMPLOYEE_ENROLLMENT, setOf(LocalSetupPhase.EMPLOYEE_ENROLLMENT)) {
                val target = requireEmployeeTarget()
                val client = requireNotNull(target.localClient)
                val identity = requireNotNull(target.employeeIdentity)
                val review = requireNotNull(view.employeeEnrollment)
                val action = requireNotNull(target.employeeAction)
                requireOriginal(review.reviewId == reviewId && review.stage == LocalSetupEmployeeEnrollmentStage.REVIEW &&
                    review.requested === action.requested && review.originalActionId == action.originalActionId)
                require(headSecret.size in 12..256)
                val head = headSecret.copyOf()
                try {
                    operation(clear = { head.fill('\u0000') }) {
                        val record = employeeSetupIntents.readOriginal(identity)
                        requireOriginal(record.actionId == action.originalActionId &&
                            record.definitionDigest == action.requested.definitionDigest.value && !record.passwordAttempted && !record.complete)
                        employeeSetupIntents.markHeadAttempt(identity)
                        change { it.copy(employeeEnrollment = review.copy(stage = LocalSetupEmployeeEnrollmentStage.APPROVAL_UNRESOLVED)) }
                        when (val result = client.approveEmployeeEnrollment(action, action.headLogin, head)) {
                            is LocalCompanyAuthorityResult.Value -> presentEmployeeConnection(target, result.value, LocalEmployeeEnrollmentPhase.APPROVED)
                            is LocalCompanyAuthorityResult.Unavailable -> employeeAuthorityFailure(result)
                        }
                    }
                } catch (failure: Throwable) { head.fill('\u0000'); throw failure }
            }
        } finally { headSecret.fill('\u0000') }
    }

    override fun enrollApprovedEmployee(reviewId: DomainId, employeePassword: CharArray) {
        try {
            start(LocalSetupWork.ENROLLING_EMPLOYEE_ACCOUNT, setOf(LocalSetupPhase.EMPLOYEE_ENROLLMENT)) {
                val target = requireEmployeeTarget()
                val client = requireNotNull(target.localClient)
                val identity = requireNotNull(target.employeeIdentity)
                val review = requireNotNull(view.employeeEnrollment)
                val connection = requireNotNull(target.employeeConnection)
                requireOriginal(review.reviewId == reviewId && review.stage == LocalSetupEmployeeEnrollmentStage.APPROVED &&
                    services.ownsEmployeeConnection(connection) && connection.originalRequestId == identity.requestId &&
                    connection.requested === review.requested)
                require(employeePassword.size in 4..256)
                val password = employeePassword.copyOf()
                try {
                    operation(clear = { password.fill('\u0000') }) {
                        val record = employeeSetupIntents.readOriginal(identity)
                        requireOriginal(!record.passwordAttempted && !record.complete)
                        employeeSetupIntents.markPasswordAttempt(identity) // BEFORE the real gateway's encrypted original dispatch.
                        change { it.copy(employeeEnrollment = review.copy(stage = LocalSetupEmployeeEnrollmentStage.ENROLLMENT_UNRESOLVED)) }
                        presentEmployeeAccountResult(target, client.enrollApprovedEmployee(identity.requestId, password))
                    }
                } catch (failure: Throwable) { password.fill('\u0000'); throw failure }
            }
        } finally { employeePassword.fill('\u0000') }
    }

    override fun enrollEmployeeIntentJournal(reviewId: DomainId): Unit = connectEmployeeJournal(reviewId, NativeEmployeeClientJournalAction.ENROLL_EMPLOYEE)
    override fun reopenEmployeeIntentJournal(reviewId: DomainId): Unit = connectEmployeeJournal(reviewId, NativeEmployeeClientJournalAction.REOPEN_EXISTING)
    override fun recoverEmployeeIntentJournalWrites(reviewId: DomainId): Unit = connectEmployeeJournal(reviewId, NativeEmployeeClientJournalAction.RECOVER_PENDING_WRITES)

    private fun connectEmployeeJournal(reviewId: DomainId, action: NativeEmployeeClientJournalAction) {
        start(LocalSetupWork.CONNECTING_EMPLOYEE_INTENT_JOURNAL, setOf(LocalSetupPhase.EMPLOYEE_ENROLLMENT)) {
            val target = requireEmployeeTarget()
            val client = requireNotNull(target.localClient)
            val identity = requireNotNull(target.employeeIdentity)
            val review = requireNotNull(view.employeeEnrollment)
            requireOriginal(review.reviewId == reviewId && review.stage in setOf(
                LocalSetupEmployeeEnrollmentStage.ACCOUNT_CREATED, LocalSetupEmployeeEnrollmentStage.JOURNAL_UNRESOLVED))
            requireOriginal(services.ownsEmployeeConnection(requireNotNull(target.employeeConnection)))
            operation {
                val record = employeeSetupIntents.readOriginal(identity)
                if (action == NativeEmployeeClientJournalAction.ENROLL_EMPLOYEE)
                    requireOriginal(!record.journalAttempted && !record.complete && review.firstJournalEnrollmentAvailable)
                employeeSetupIntents.markJournalAttempt(identity)
                change { it.copy(employeeEnrollment = review.copy(stage = LocalSetupEmployeeEnrollmentStage.JOURNAL_UNRESOLVED,
                    firstJournalEnrollmentAvailable = false)) }
                // Native consumed-child custody and its exact journal allocation are acquired
                // here. A UI UUID, success flag or account directory cannot construct them.
                if (client.acquireEmployeeIntentJournal(identity.requestId, action) && continueWork() && client.isCurrent()) {
                    target.employeeJournalOpenedRequestId = identity.requestId
                    change { it.copy(employeeEnrollment = review.copy(stage = LocalSetupEmployeeEnrollmentStage.JOURNAL_OPENED,
                        firstJournalEnrollmentAvailable = false), problem = null) }
                } else change { it.copy(problem = LocalSetupProblem.CLIENT_JOURNAL_UNAVAILABLE) }
            }
        }
    }

    override fun finishEmployeeEnrollment(reviewId: DomainId) {
        start(LocalSetupWork.RETAINING_EMPLOYEE_ENROLLMENT, setOf(LocalSetupPhase.EMPLOYEE_ENROLLMENT)) {
            val target = requireEmployeeTarget()
            val identity = requireNotNull(target.employeeIdentity)
            val review = requireNotNull(view.employeeEnrollment)
            requireOriginal(review.reviewId == reviewId && review.stage in setOf(
                LocalSetupEmployeeEnrollmentStage.JOURNAL_OPENED, LocalSetupEmployeeEnrollmentStage.REJECTED))
            val client = requireNotNull(target.localClient)
            operation {
                if (review.stage == LocalSetupEmployeeEnrollmentStage.REJECTED) {
                    val originals = employeeOriginals(client) ?: return@operation
                    requireOriginal(originals.singleOrNull { it.originalRequestId == identity.requestId }?.let { original ->
                        original.originalActionId == review.originalActionId && original.actorId == identity.actorId &&
                            original.phase == LocalEmployeeEnrollmentPhase.REJECTED
                    } == true)
                    employeeSetupIntents.markRejectedComplete(identity)
                } else {
                    requireOriginal(target.employeeJournalOpenedRequestId == identity.requestId && client.isCurrent())
                    employeeSetupIntents.markComplete(identity) // Actual positive journal acquisition, never receipt-as-login.
                }
                target.employeeIdentity = null; target.employeeAction = null; target.employeeConnection = null
                target.employeeJournalOpenedRequestId = null
                change { it.copy(phase = LocalSetupPhase.INSTALLATION_RETAINED, employeeEnrollment = null,
                    employeeEnrollments = null, problem = null) }
            }
        }
    }

    override fun openEmployeePolicies() {
        start(LocalSetupWork.READING_EMPLOYEE_POLICY, EMPLOYEE_POLICY_OPEN_PHASES) {
            val target = requirePolicyTarget()
            val client = requireNotNull(target.localClient)
            operation {
                val originals = policyOriginals(target, client) ?: return@operation
                val retained = target.employeePolicyOriginal
                change { it.copy(phase = LocalSetupPhase.EMPLOYEE_POLICY,
                    employeePolicies = LocalSetupEmployeePolicies(originals,
                        retained == null && originals.all { original -> original.phase in TERMINAL_EMPLOYEE_POLICY_PHASES }),
                    employeePolicy = retained?.let { original ->
                        view.employeePolicy?.takeIf { selected -> selected.originalRequestId == original.requestId }
                            ?: original.projection(LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED)
                    }, problem = null) }
                if (retained != null) inspectPolicyOriginal(target, client, retained.requestId, originals)
            }
        }
    }

    override fun closeEmployeePolicies() {
        start(LocalSetupWork.READING_EMPLOYEE_POLICY, setOf(LocalSetupPhase.EMPLOYEE_POLICY)) {
            requirePolicyTarget().also { requireOriginal(it.employeePolicyOriginal == null && view.employeePolicy == null) }
            operation { change { it.copy(phase = LocalSetupPhase.INSTALLATION_RETAINED, employeePolicies = null, problem = null) } }
        }
    }

    override fun beginEmployeePolicy() {
        start(LocalSetupWork.READING_EMPLOYEE_POLICY, setOf(LocalSetupPhase.EMPLOYEE_POLICY)) {
            val target = requirePolicyTarget()
            val client = requireNotNull(target.localClient)
            requireOriginal(view.employeePolicies?.newDefinitionAvailable == true && view.employeePolicy == null &&
                target.employeePolicyOriginal == null)
            operation {
                val originals = policyOriginals(target, client) ?: return@operation
                requireOriginal(originals.all { it.phase in TERMINAL_EMPLOYEE_POLICY_PHASES })
                if (!policyOwnerCurrent(target, client)) return@operation
                val original = LocalSetupEmployeePolicyOriginal(DomainId.random(), target.context.materialAtOpen.coordinates.scope)
                target.employeePolicyOriginal = original
                change { it.copy(employeePolicies = LocalSetupEmployeePolicies(originals, false),
                    employeePolicy = original.projection(LocalSetupEmployeePolicyStage.DEFINITION), problem = null) }
            }
        }
    }

    override fun inspectEmployeePolicy(originalRequestId: DomainId) {
        start(LocalSetupWork.READING_EMPLOYEE_POLICY, setOf(LocalSetupPhase.EMPLOYEE_POLICY)) {
            val target = requirePolicyTarget()
            val client = requireNotNull(target.localClient)
            requireOriginal(target.employeePolicyOriginal?.let { it.requestId == originalRequestId } != false)
            operation { inspectPolicyOriginal(target, client, originalRequestId) }
        }
    }

    override fun prepareEmployeePolicy(reviewId: DomainId, nextPolicy: ActivatedLocalPolicy) {
        start(LocalSetupWork.PREPARING_EMPLOYEE_POLICY, setOf(LocalSetupPhase.EMPLOYEE_POLICY)) {
            val target = requirePolicyTarget()
            val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.employeePolicyOriginal)
            val selected = requireNotNull(view.employeePolicy)
            requireOriginal(selected.reviewId == reviewId && selected.originalRequestId == original.requestId &&
                selected.stage == LocalSetupEmployeePolicyStage.DEFINITION && !original.preparationAttempted &&
                nextPolicy.scope == original.scope && nextPolicy.revision.value > 0L)
            operation {
                val originals = policyOriginals(target, client) ?: return@operation
                requireOriginal(originals.none { it.originalRequestId == original.requestId } &&
                    originals.all { it.phase in TERMINAL_EMPLOYEE_POLICY_PHASES })
                if (!policyOwnerCurrent(target, client)) return@operation
                original.actorId = nextPolicy.actorId
                original.submittedPolicy = nextPolicy
                original.preparationAttempted = true // BEFORE native preparation; no unknown-result re-prepare.
                change { it.copy(employeePolicy = original.projection(LocalSetupEmployeePolicyStage.PREPARATION_UNRESOLVED)) }
                when (val result = client.prepareEmployeePolicy(original.requestId, nextPolicy)) {
                    is LocalCompanyAuthorityResult.Value -> {
                        retainPolicyAction(original, result.value)
                        if (policyOwnerCurrent(target, client)) presentPolicy(original, LocalSetupEmployeePolicyStage.REVIEW)
                    }
                    is LocalCompanyAuthorityResult.Unavailable -> if (policyOwnerCurrent(target, client)) policyAuthorityFailure(result)
                }
            }
        }
    }

    override fun approveEmployeePolicy(reviewId: DomainId, headSecret: CharArray) {
        try {
            start(LocalSetupWork.APPROVING_EMPLOYEE_POLICY, setOf(LocalSetupPhase.EMPLOYEE_POLICY)) {
                val target = requirePolicyTarget()
                val client = requireNotNull(target.localClient)
                val original = requireNotNull(target.employeePolicyOriginal)
                val selected = requireNotNull(view.employeePolicy)
                val action = requireNotNull(original.action)
                requireOriginal(selected.reviewId == reviewId && selected.stage == LocalSetupEmployeePolicyStage.REVIEW &&
                    selected.originalRequestId == original.requestId && selected.originalActionId == action.originalActionId &&
                    selected.requested === original.requested && !original.activationAttempted)
                require(headSecret.size in 12..256)
                val head = headSecret.copyOf()
                try {
                    operation(clear = { head.fill('\u0000') }) {
                        val originals = policyOriginals(target, client) ?: return@operation
                        val reference = policyReference(original, originals)
                        requireOriginal(reference?.phase == LocalEmployeePolicyActivationPhase.PREPARED)
                        // A stale review cannot sign. The native owner rechecks the complete original
                        // and its head lifetime; this fresh read does not allocate or approve anything.
                        val current = when (val read = client.reviewEmployeePolicy(action.originalActionId)) {
                            is LocalCompanyAuthorityResult.Value -> read.value
                            is LocalCompanyAuthorityResult.Unavailable -> {
                                if (policyOwnerCurrent(target, client)) policyAuthorityFailure(read)
                                return@operation
                            }
                        }
                        requireOriginal(original.actionId == current.originalActionId && original.matches(current.requested) &&
                            current.headLogin == action.headLogin && current.headName == action.headName &&
                            current.companyName == action.companyName && current.rootFingerprint == action.rootFingerprint)
                        retainPolicyAction(original, current)
                        if (!policyOwnerCurrent(target, client)) return@operation
                        change { it.copy(employeePolicy = original.projection(LocalSetupEmployeePolicyStage.APPROVAL_UNRESOLVED)) }
                        val result = try { client.approveEmployeePolicy(current, current.headLogin, head) }
                        finally { head.fill('\u0000') }
                        when (result) {
                            is LocalCompanyAuthorityResult.Value -> {
                                retainPolicyApproval(original, result.value)
                                original.phase = LocalEmployeePolicyActivationPhase.APPROVED
                                if (policyOwnerCurrent(target, client)) presentPolicy(original, LocalSetupEmployeePolicyStage.APPROVED)
                            }
                            is LocalCompanyAuthorityResult.Unavailable -> if (policyOwnerCurrent(target, client)) policyAuthorityFailure(result)
                        }
                    }
                } catch (failure: Throwable) { head.fill('\u0000'); throw failure }
            }
        } finally { headSecret.fill('\u0000') }
    }

    override fun activateApprovedEmployeePolicy(reviewId: DomainId) {
        start(LocalSetupWork.ACTIVATING_EMPLOYEE_POLICY, setOf(LocalSetupPhase.EMPLOYEE_POLICY)) {
            val target = requirePolicyTarget()
            val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.employeePolicyOriginal)
            val selected = requireNotNull(view.employeePolicy)
            val approval = requireNotNull(original.approval)
            requireOriginal(selected.reviewId == reviewId && selected.stage == LocalSetupEmployeePolicyStage.APPROVED &&
                selected.originalRequestId == original.requestId && selected.originalActionId == original.actionId &&
                selected.requested === original.requested && !original.activationAttempted)
            operation {
                val originals = policyOriginals(target, client) ?: return@operation
                requireOriginal(policyReference(original, originals)?.phase == LocalEmployeePolicyActivationPhase.APPROVED)
                val current = when (val read = client.recoverEmployeePolicyApproval(requireNotNull(original.actionId))) {
                    is LocalCompanyAuthorityResult.Value -> read.value
                    is LocalCompanyAuthorityResult.Unavailable -> {
                        if (policyOwnerCurrent(target, client)) policyAuthorityFailure(read)
                        return@operation
                    }
                }
                requireOriginal(current.authorityDigest == approval.authorityDigest)
                retainPolicyApproval(original, current)
                if (!policyOwnerCurrent(target, client)) return@operation
                original.activationAttempted = true // BEFORE dispatch; only genuine APPROVED recovery can re-arm this original.
                change { it.copy(employeePolicy = original.projection(LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED)) }
                val result = client.activateEmployeePolicy(current, requireNotNull(original.requested).nextPolicy)
                if (!policyOwnerCurrent(target, client)) return@operation
                when (result) {
                    is LocalCredentialAdministrationResult.Applied -> {
                        original.phase = LocalEmployeePolicyActivationPhase.CONSUMED
                        presentPolicy(original, LocalSetupEmployeePolicyStage.APPLIED,
                            LocalSetupEmployeePolicyOutcome.APPLIED, result.eventId)
                    }
                    else -> presentPolicy(original, LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED, when (result) {
                        LocalCredentialAdministrationResult.Denied -> LocalSetupEmployeePolicyOutcome.DENIED
                        LocalCredentialAdministrationResult.Unavailable -> LocalSetupEmployeePolicyOutcome.UNAVAILABLE
                        LocalCredentialAdministrationResult.StorageUncertain -> LocalSetupEmployeePolicyOutcome.STORAGE_UNCERTAIN
                        LocalCredentialAdministrationResult.Busy -> LocalSetupEmployeePolicyOutcome.BUSY
                        is LocalCredentialAdministrationResult.Applied -> error("setup.policy-original-result-required")
                    })
                }
            }
        }
    }

    override fun lookupEmployeePolicy(reviewId: DomainId) {
        start(LocalSetupWork.LOOKING_UP_EMPLOYEE_POLICY, setOf(LocalSetupPhase.EMPLOYEE_POLICY)) {
            val target = requirePolicyTarget()
            val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.employeePolicyOriginal)
            requireOriginal(view.employeePolicy?.let { it.reviewId == reviewId && it.originalRequestId == original.requestId &&
                it.stage != LocalSetupEmployeePolicyStage.DEFINITION } == true)
            operation { inspectPolicyOriginal(target, client, original.requestId) }
        }
    }

    override fun finishEmployeePolicy(reviewId: DomainId) {
        start(LocalSetupWork.READING_EMPLOYEE_POLICY, setOf(LocalSetupPhase.EMPLOYEE_POLICY)) {
            val target = requirePolicyTarget()
            val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.employeePolicyOriginal)
            val selected = requireNotNull(view.employeePolicy)
            requireOriginal(selected.reviewId == reviewId && selected.originalRequestId == original.requestId &&
                ((!original.preparationAttempted && selected.stage == LocalSetupEmployeePolicyStage.DEFINITION) ||
                    selected.stage in setOf(LocalSetupEmployeePolicyStage.APPLIED, LocalSetupEmployeePolicyStage.NOT_RECORDED)))
            operation {
                if (original.preparationAttempted) {
                    val originals = policyOriginals(target, client) ?: return@operation
                    requireOriginal(policyReference(original, originals)?.phase in TERMINAL_EMPLOYEE_POLICY_PHASES)
                    inspectPolicyOriginal(target, client, original.requestId, originals)
                    requireOriginal(view.employeePolicy?.stage in setOf(
                        LocalSetupEmployeePolicyStage.APPLIED, LocalSetupEmployeePolicyStage.NOT_RECORDED))
                }
                if (!policyOwnerCurrent(target, client)) return@operation
                target.employeePolicyOriginal = null
                change { it.copy(phase = LocalSetupPhase.INSTALLATION_RETAINED,
                    employeePolicy = null, employeePolicies = null, problem = null) }
            }
        }
    }

    override fun chooseFreshDeviceTarget() {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.CHOOSE_ROUTE)) {
            requireOriginal(native == null)
            operation {
                val saved = requireFreshPairingJournal().target()
                val original = LocalSetupFreshDeviceTargetOriginal()
                saved?.let { original.retainRecord(it) }
                freshTargetOriginal = original
                presentFreshTarget(original, if (saved == null) LocalSetupFreshDevicePairingStage.TARGET_INPUT
                    else LocalSetupFreshDevicePairingStage.ORIGINAL_UNAVAILABLE)
            }
        }
    }

    override fun reviewFreshDeviceTarget(input: LocalSetupFreshDeviceTargetInput) {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val original = requireNotNull(freshTargetOriginal)
            requireOriginal(native == null && view.freshDevicePairing?.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET &&
                view.freshDevicePairing?.stage in setOf(LocalSetupFreshDevicePairingStage.TARGET_INPUT,
                    LocalSetupFreshDevicePairingStage.TARGET_INPUT_REVIEW) && !original.preparationEntered && original.record == null)
            val copied = LocalSetupFreshDeviceTargetInput(input.scope, input.capabilities, input.appId, input.deviceName)
            requireOriginal(copied.matchesBranchRpcNamespace)
            operation {
                requireOriginal(requireFreshPairingJournal().target() == null)
                original.intentId = DomainId.random(); original.input = copied
                presentFreshTarget(original, LocalSetupFreshDevicePairingStage.TARGET_INPUT_REVIEW)
            }
        }
    }

    override fun prepareFreshDeviceTarget(reviewId: DomainId) {
        start(LocalSetupWork.PREPARING_FRESH_DEVICE_TARGET, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val original = requireNotNull(freshTargetOriginal)
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.FRESH_TARGET)
            requireOriginal(native == null && view.freshDevicePairing?.stage == LocalSetupFreshDevicePairingStage.TARGET_INPUT_REVIEW &&
                !original.preparationEntered && original.record == null)
            val input = requireNotNull(original.input); val intentId = requireNotNull(original.intentId)
            requireOriginal(input.matchesBranchRpcNamespace)
            operation {
                val journal = requireFreshPairingJournal()
                requireOriginal(journal.target() == null)
                original.preparationEntered = true // A thrown force cannot re-arm this lifetime.
                original.retainRecord(journal.beginTarget(intentId, input.scope, input.capabilities, input.appId, input.deviceName))
                original.retainRecord(journal.markTargetPreparation(intentId))
                presentFreshTarget(original, LocalSetupFreshDevicePairingStage.TARGET_PREPARATION_UNRESOLVED)
                if (!freshPresentationOpen()) return@operation
                val owner = acquireFreshTarget(original, input) ?: return@operation
                if (freshTargetCurrent(owner, original)) readFreshTarget(owner, original)
            }
        }
    }

    override fun inspectOriginalFreshDeviceTarget() {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.CHOOSE_ROUTE, LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            requireOriginal(native == null || native is FreshDeviceTarget)
            operation {
                val saved = requireFreshPairingJournal().target()
                val original = freshTargetOriginal ?: LocalSetupFreshDeviceTargetOriginal().also { freshTargetOriginal = it }
                saved?.let { original.retainRecord(it) }
                presentFreshTarget(original, LocalSetupFreshDevicePairingStage.ORIGINAL_UNAVAILABLE)
                val owner = (native as? FreshDeviceTarget) ?: acquireFreshTarget(original, null) ?: return@operation
                if (freshTargetCurrent(owner, original)) readFreshTarget(owner, original)
            }
        }
    }

    override fun acquireFreshDeviceSdkIdentity(reviewId: DomainId) {
        start(LocalSetupWork.ACQUIRING_FRESH_DEVICE_SDK, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val owner = requireFreshTarget(); val original = owner.original
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.FRESH_TARGET)
            requireOriginal(view.freshDevicePairing?.stage == LocalSetupFreshDevicePairingStage.TARGET_KEY_READY &&
                !original.sdkEntered && original.record != null)
            val input = requireNotNull(original.input); val intentId = requireNotNull(original.intentId)
            requireOriginal(input.matchesBranchRpcNamespace)
            operation {
                if (!readFreshTarget(owner, original)) return@operation
                requireOriginal(original.reference?.phase == LocalDevicePairingTargetPhase.KEY_READY && !original.sdkEntered)
                if (!freshTargetEffectCurrent(owner, original)) return@operation
                original.sdkEntered = true
                original.retainRecord(requireFreshPairingJournal().markTargetSdkAttempt(intentId))
                presentFreshTarget(original, LocalSetupFreshDevicePairingStage.SDK_ACQUISITION_UNRESOLVED)
                if (!freshTargetEffectCurrent(owner, original)) return@operation
                when (val result = owner.value.acquireSdkIdentity(AppId(input.appId), input.deviceName)) {
                    is LocalSetupFreshDeviceTargetResult.Value -> if (freshTargetCurrent(owner, original)) {
                        original.retainIdentity(result.value)
                        original.retainRecord(requireFreshPairingJournal().retainTargetSdkIdentity(intentId, result.value))
                        readFreshTarget(owner, original)
                    }
                    is LocalSetupFreshDeviceTargetResult.Unavailable -> if (freshTargetCurrent(owner, original)) freshTargetFailure(original, result.reason)
                }
            }
        }
    }

    override fun openFreshDevicePairing() {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.INSTALLATION_RETAINED,
            LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
            operation {
                val inventory = freshMasterInventory(target, client) ?: return@operation
                val selected = target.freshPairingOriginal
                val requestId = selected?.takeIf { it.preparationEntered || it.record != null }?.requestId
                    ?: inventory.blockedRequestId ?: inventory.records.singleOrNull()?.identity?.requestId
                if (requestId != null) inspectFreshMaster(target, client, requestId, inventory)
                else {
                    val original = selected ?: LocalSetupFreshDeviceMasterOriginal(deviceAdmissionContext(target), DomainId.random())
                        .also { target.freshPairingOriginal = it }
                    presentFreshMaster(original, LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_IMPORT, inventory)
                }
            }
        }
    }

    override fun beginFreshDevicePairing() {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
            operation {
                val inventory = freshMasterInventory(target, client) ?: return@operation
                requireFreshMasterCanBegin(target, client, inventory)
                target.freshPairingOriginal?.let { requireOriginal(!it.preparationEntered && it.record == null ||
                    it.requestId in target.confirmedFreshPairingRequests) }
                val original = LocalSetupFreshDeviceMasterOriginal(deviceAdmissionContext(target), DomainId.random())
                target.freshPairingOriginal = original
                presentFreshMaster(original, LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_IMPORT, inventory)
            }
        }
    }

    override fun inspectFreshDevicePairing(originalRequestId: DomainId) {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
            operation { inspectFreshMaster(target, client, originalRequestId) }
        }
    }

    override fun prepareFreshDeviceChallenge(reviewId: DomainId) {
        start(LocalSetupWork.PREPARING_FRESH_DEVICE_CHALLENGE, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.freshPairingOriginal)
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER)
            requireOriginal(view.freshDevicePairing?.stage == LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_REVIEW &&
                !original.preparationEntered && original.record == null)
            val identity = requireNotNull(original.identity)
            requireOriginal(identity.appId == LocalMasterSdkProfileV1.appId(original.context.scope))
            operation {
                val inventory = freshMasterInventory(target, client) ?: return@operation
                requireFreshMasterCanBegin(target, client, inventory)
                if (!freshMasterEffectCurrent(target, client, original)) return@operation
                original.preparationEntered = true
                original.retainRecord(requireFreshPairingJournal().beginMaster(original.context, original.requestId, identity))
                original.retainRecord(requireFreshPairingJournal().markMasterPreparation(original.context, original.requestId))
                presentFreshMaster(original, LocalSetupFreshDevicePairingStage.MASTER_PREPARATION_UNRESOLVED, inventory)
                if (!freshMasterEffectCurrent(target, client, original)) return@operation
                when (val result = client.prepareSdkBoundDevicePairing(original.requestId, identity)) {
                    is LocalCompanyAuthorityResult.Value -> if (freshMasterCurrent(target, client, original)) {
                        original.retainAction(result.value)
                        original.retainRecord(requireFreshPairingJournal().retainMasterAction(original.context, original.requestId, result.value))
                        inspectFreshMaster(target, client, original.requestId)
                    }
                    is LocalCompanyAuthorityResult.Unavailable -> if (freshMasterCurrent(target, client, original))
                        freshMasterFailure(original, result)
                }
            }
        }
    }

    override fun importFreshDeviceIdentity(reviewId: DomainId): Unit = importFreshDocument(reviewId, LocalSetupPairingDocumentKind.SDK_IDENTITY)
    override fun importFreshDeviceChallenge(reviewId: DomainId): Unit = importFreshDocument(reviewId, LocalSetupPairingDocumentKind.CHALLENGE)
    override fun importFreshDevicePossession(reviewId: DomainId): Unit = importFreshDocument(reviewId, LocalSetupPairingDocumentKind.POSSESSION)
    override fun importApprovedPairingMaster(reviewId: DomainId): Unit = importFreshDocument(reviewId, LocalSetupPairingDocumentKind.APPROVED_MASTER)
    override fun importUnsignedDevicePairingRetirement(reviewId: DomainId): Unit = importFreshDocument(reviewId, LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT)

    private fun importFreshDocument(reviewId: DomainId, kind: LocalSetupPairingDocumentKind) {
        start(LocalSetupWork.IMPORTING_FRESH_DEVICE_DOCUMENT, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val selected = requireNotNull(view.freshDevicePairing)
            requireOriginal(selected.reviewId == reviewId)
            val documents = requireNotNull(pairingDocuments)
            if (kind == LocalSetupPairingDocumentKind.CHALLENGE || kind == LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT ||
                kind == LocalSetupPairingDocumentKind.APPROVED_MASTER) {
                val owner = requireFreshTarget(); val original = owner.original
                requireOriginal(selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET && original.identity != null)
                operation {
                    if (kind == LocalSetupPairingDocumentKind.APPROVED_MASTER) {
                        requireOriginal(!original.continuationAttempted)
                        if (!readFreshTarget(owner, original)) return@operation
                        requireOriginal(original.possession != null)
                    }
                    val result = documents.importDocument(kind) { freshTargetCurrent(owner, original) }
                    if (!freshTargetCurrent(owner, original)) return@operation
                    when (result) {
                        is LocalSetupPairingDocumentImportResult.Completed -> {
                            if (!retainImportedFreshDocument(result, kind) { bytes ->
                                if (kind == LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT) original.retainRetirement(LocalDevicePairingUnsignedRetirement.decode(bytes))
                                else if (kind == LocalSetupPairingDocumentKind.APPROVED_MASTER)
                                    original.retainMasterTransfer(LocalDevicePairingMasterTransfer.decode(bytes))
                                else {
                                    requireOriginal(!original.continuationAttempted && (original.responseCycle == 0 || original.continuationConfirmed))
                                    original.retainChallenge(LocalDevicePairingChallenge.decode(bytes))
                                }
                            }) return@operation
                            original.document = LocalSetupPairingDocumentView(kind, LocalSetupPairingDocumentOutcome.IMPORTED, result.byteCount)
                            presentFreshTarget(original, freshTargetStage(original))
                        }
                        LocalSetupPairingDocumentImportResult.Cancelled -> {
                            original.document = LocalSetupPairingDocumentView(kind, LocalSetupPairingDocumentOutcome.CANCELLED)
                            presentFreshTarget(original, freshTargetStage(original))
                        }
                        is LocalSetupPairingDocumentImportResult.Unavailable -> freshDocumentFailure(result.problem)
                    }
                }
            } else {
                val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
                val original = requireNotNull(target.freshPairingOriginal)
                requireOriginal(selected.role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER &&
                    (kind != LocalSetupPairingDocumentKind.POSSESSION || original.action != null))
                operation {
                    val result = documents.importDocument(kind) { freshMasterCurrent(target, client, original) }
                    if (!freshMasterCurrent(target, client, original)) return@operation
                    when (result) {
                        is LocalSetupPairingDocumentImportResult.Completed -> {
                            if (!retainImportedFreshDocument(result, kind) { bytes ->
                                when (kind) {
                                    LocalSetupPairingDocumentKind.SDK_IDENTITY -> original.retainIdentity(LocalDevicePairingSdkIdentityV2.decode(bytes))
                                    LocalSetupPairingDocumentKind.POSSESSION -> original.retainPossession(LocalDevicePairingPossession.decode(bytes))
                                    LocalSetupPairingDocumentKind.CHALLENGE, LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT,
                                    LocalSetupPairingDocumentKind.APPROVED_MASTER -> throw OriginalRequired()
                                }
                            }) return@operation
                            original.document = LocalSetupPairingDocumentView(kind, LocalSetupPairingDocumentOutcome.IMPORTED, result.byteCount)
                            presentFreshMaster(original, freshMasterStage(original))
                        }
                        LocalSetupPairingDocumentImportResult.Cancelled -> {
                            original.document = LocalSetupPairingDocumentView(kind, LocalSetupPairingDocumentOutcome.CANCELLED)
                            presentFreshMaster(original, freshMasterStage(original))
                        }
                        is LocalSetupPairingDocumentImportResult.Unavailable -> freshDocumentFailure(result.problem)
                    }
                }
            }
        }
    }

    private fun retainImportedFreshDocument(result: LocalSetupPairingDocumentImportResult.Completed,
        expected: LocalSetupPairingDocumentKind, retain: (ByteArray) -> Unit): Boolean {
        val bytes = result.copyBytes()
        return try {
            requireOriginal(result.kind == expected && bytes.size in 1..expected.maximumBytes)
            retain(bytes); true
        } catch (_: Exception) { freshDocumentFailure(LocalSetupProblem.INVALID_INPUT); false }
        finally { bytes.fill(0) }
    }

    override fun verifyApprovedPairingMaster(reviewId: DomainId) {
        start(LocalSetupWork.VERIFYING_APPROVED_PAIRING_MASTER, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val owner = requireFreshTarget(); val original = owner.original
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.FRESH_TARGET)
            val transfer = requireNotNull(original.masterTransfer)
            requireOriginal(!original.masterImportEntered && original.verifiedMaster == null)
            operation {
                if (!readFreshTarget(owner, original)) return@operation
                requireOriginal(original.possession != null && transfer.originalActionId == original.responseActionId)
                if (!freshTargetEffectCurrent(owner, original)) return@operation
                original.masterImportEntered = true
                presentFreshTarget(original, LocalSetupFreshDevicePairingStage.TARGET_MASTER_UNRESOLVED)
                when (val result = owner.value.importApprovedMaster(transfer)) {
                    is LocalSetupFreshDeviceTargetResult.Value -> {
                        if (!freshTargetCurrent(owner, original)) return@operation
                        original.retainVerifiedMaster(result.value)
                        presentFreshTarget(original, freshTargetStage(original))
                    }
                    is LocalSetupFreshDeviceTargetResult.Unavailable -> {
                        if (freshTargetCurrent(owner, original)) {
                            presentFreshTarget(original, LocalSetupFreshDevicePairingStage.TARGET_MASTER_UNRESOLVED)
                            freshDocumentFailure(result.reason.toSetupProblem())
                        }
                    }
                }
            }
        }
    }

    override fun recoverApprovedPairingMaster(reviewId: DomainId) {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val owner = requireFreshTarget(); val original = owner.original
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.FRESH_TARGET)
            operation {
                if (!readFreshTarget(owner, original)) return@operation
                val actionId = requireNotNull(original.responseActionId)
                when (val result = owner.value.recoverApprovedMaster(actionId)) {
                    is LocalSetupFreshDeviceTargetResult.Value -> {
                        if (!freshTargetCurrent(owner, original)) return@operation
                        original.retainVerifiedMaster(result.value)
                        presentFreshTarget(original, freshTargetStage(original))
                    }
                    is LocalSetupFreshDeviceTargetResult.Unavailable -> {
                        if (freshTargetCurrent(owner, original)) freshDocumentFailure(result.reason.toSetupProblem())
                    }
                }
            }
        }
    }

    override fun respondFreshDeviceChallenge(reviewId: DomainId) {
        start(LocalSetupWork.RESPONDING_FRESH_DEVICE_CHALLENGE, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val owner = requireFreshTarget(); val original = owner.original
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.FRESH_TARGET)
            requireOriginal(view.freshDevicePairing?.stage == LocalSetupFreshDevicePairingStage.TARGET_CHALLENGE_REVIEW &&
                !original.responseEntered && original.record != null)
            val challenge = requireNotNull(original.challenge); val intentId = requireNotNull(original.intentId)
            requireFreshRpcNamespace(requireNotNull(original.identity))
            operation {
                if (!readFreshTarget(owner, original)) return@operation
                requireFreshChallengeIdentity(challenge, requireNotNull(original.identity))
                requireOriginal(!original.responseEntered)
                if (!freshTargetEffectCurrent(owner, original)) return@operation
                original.responseEntered = true
                if (original.responseCycle == 0) original.retainRecord(requireFreshPairingJournal().markTargetResponse(intentId, challenge))
                else {
                    requireOriginal(original.continuationConfirmed)
                    requireFreshPairingJournal().markSuccessorTargetResponse(intentId, challenge)
                    original.retainCycles(requireFreshPairingJournal().responseCycles(intentId))
                }
                presentFreshTarget(original, LocalSetupFreshDevicePairingStage.TARGET_RESPONSE_UNRESOLVED)
                if (!freshTargetEffectCurrent(owner, original)) return@operation
                when (val result = owner.value.respond(challenge)) {
                    is LocalSetupFreshDeviceTargetResult.Value -> if (freshTargetCurrent(owner, original)) {
                        original.retainPossession(result.value)
                        if (original.responseCycle == 0) original.retainRecord(requireFreshPairingJournal().retainTargetPossession(intentId, result.value))
                        else {
                            requireFreshPairingJournal().retainSuccessorTargetPossession(intentId, result.value)
                            original.retainCycles(requireFreshPairingJournal().responseCycles(intentId))
                        }
                        readFreshTarget(owner, original)
                    }
                    is LocalSetupFreshDeviceTargetResult.Unavailable -> if (freshTargetCurrent(owner, original)) freshTargetFailure(original, result.reason)
                }
            }
        }
    }

    override fun proveFreshDeviceTarget(reviewId: DomainId) {
        start(LocalSetupWork.PROVING_FRESH_DEVICE_TARGET, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.freshPairingOriginal)
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER)
            requireOriginal(view.freshDevicePairing?.stage == LocalSetupFreshDevicePairingStage.MASTER_POSSESSION_REVIEW &&
                !original.proofEntered && original.record != null)
            val possession = requireNotNull(original.possession)
            requireFreshRpcNamespace(requireNotNull(original.identity))
            operation {
                if (!readFreshMasterAction(target, client, original)) return@operation
                requireOriginal(original.action?.phase == LocalDevicePairingPhase.PREPARED && !original.proofEntered)
                requireFreshPossession(requireNotNull(original.action).challenge, possession)
                if (!freshMasterEffectCurrent(target, client, original)) return@operation
                original.proofEntered = true
                original.nativeConfirmed = false
                original.retainRecord(requireFreshPairingJournal().markMasterProof(original.context, original.requestId, possession))
                presentFreshMaster(original, LocalSetupFreshDevicePairingStage.MASTER_PROOF_UNRESOLVED)
                if (!freshMasterEffectCurrent(target, client, original)) return@operation
                when (val result = client.proveDevicePairingTarget(requireNotNull(original.action), possession)) {
                    is LocalCompanyAuthorityResult.Value -> if (freshMasterCurrent(target, client, original)) {
                        original.retainAction(result.value)
                        requireOriginal(result.value.phase == LocalDevicePairingPhase.TARGET_PROVED &&
                            result.value.possession?.let { sameFreshPossession(it, possession) } == true)
                        // Only another actual retained-original review publishes handoff eligibility.
                        inspectFreshMaster(target, client, original.requestId)
                    }
                    is LocalCompanyAuthorityResult.Unavailable -> if (freshMasterCurrent(target, client, original))
                        freshMasterFailure(original, result)
                }
            }
        }
    }

    override fun reviewUnsignedDevicePairingRetirement(reviewId: DomainId) {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.freshPairingOriginal)
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER)
            operation {
                if (!readFreshMasterAction(target, client, original)) return@operation
                requireUnsignedRetirementCandidate(target, client, original)
                original.retirementReview = true
                presentFreshMaster(original, LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_REVIEW)
            }
        }
    }

    private suspend fun requireUnsignedRetirementCandidate(target: Target, client: LocalSetupClient,
        original: LocalSetupFreshDeviceMasterOriginal) {
        requireOriginal(original.nativeConfirmed && !original.retirementEntered && original.action?.phase in setOf(
            LocalDevicePairingPhase.PREPARED, LocalDevicePairingPhase.TARGET_PROVED))
        val inventory = deviceAdmissionInventory(target, client) ?: throw OriginalRequired()
        requireOriginal(freshMasterEffectCurrent(target, client, original))
        // Do not reinterpret head/admission uncertainty as pairing retirement or no effect.
        requireOriginal(inventory.records.none { it.identity.actionId == original.action?.originalActionId &&
            (it.headAttempted || it.admissionAttempted) } && target.deviceAdmissionOriginal?.let {
            it.actionId != original.action?.originalActionId || !it.unresolvedAttempt } != false)
        requireOriginal(blockedDeviceAdmission(target, inventory) == null)
    }

    override fun retireUnsignedDevicePairing(reviewId: DomainId) {
        start(LocalSetupWork.RETIRING_UNSIGNED_DEVICE_PAIRING, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.freshPairingOriginal)
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER)
            requireOriginal(view.freshDevicePairing?.stage == LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_REVIEW && original.retirementReview)
            operation {
                if (!readFreshMasterAction(target, client, original)) return@operation
                requireUnsignedRetirementCandidate(target, client, original)
                original.retainRecord(requireFreshPairingJournal().markMasterRetirement(original.context, original.requestId))
                original.retirementReview = false; original.nativeConfirmed = false
                target.confirmedFreshPairingRequests.remove(original.requestId)
                presentFreshMaster(original, LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_UNRESOLVED)
                if (!freshMasterEffectCurrent(target, client, original)) return@operation
                when (val retired = client.retireKnownUnsignedDevicePairing(requireNotNull(original.action).originalActionId)) {
                    is LocalCompanyAuthorityResult.Value -> if (freshMasterCurrent(target, client, original)) {
                        original.retainRetirement(retired.value)
                        original.retainRecord(requireFreshPairingJournal().retainMasterRetirement(original.context, original.requestId, retired.value))
                        if (readFreshMasterAction(target, client, original)) presentFreshMaster(original, freshMasterStage(original))
                    }
                    is LocalCompanyAuthorityResult.Unavailable -> if (freshMasterCurrent(target, client, original)) freshMasterFailure(original, retired)
                }
            }
        }
    }

    override fun continueUnsignedDevicePairing(reviewId: DomainId) {
        start(LocalSetupWork.CONTINUING_UNSIGNED_DEVICE_PAIRING, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val owner = requireFreshTarget(); val original = owner.original
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.FRESH_TARGET)
            requireOriginal(view.freshDevicePairing?.stage == LocalSetupFreshDevicePairingStage.TARGET_RETIREMENT_REVIEW && !original.continuationAttempted)
            val certificate = requireNotNull(original.retirement); val intentId = requireNotNull(original.intentId)
            requireFreshRpcNamespace(requireNotNull(original.identity))
            operation {
                if (!readFreshTarget(owner, original)) return@operation
                requireOriginal(original.retirement === certificate && (!original.responseEntered || original.possession != null))
                val responseId = requireNotNull(original.reference?.originalResponseId)
                if (!freshTargetEffectCurrent(owner, original)) return@operation
                val journal = requireFreshPairingJournal()
                journal.markTargetContinuation(intentId, certificate, responseId)
                original.retainCycles(journal.responseCycles(intentId))
                presentFreshTarget(original, LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED)
                if (!freshTargetEffectCurrent(owner, original)) return@operation
                when (val continued = owner.value.beginAfterKnownUnsigned(certificate)) {
                    is LocalSetupFreshDeviceTargetResult.Value -> if (freshTargetCurrent(owner, original)) {
                        journal.retainTargetContinuation(intentId, certificate, continued.value)
                        original.retainCycles(journal.responseCycles(intentId)); original.retainReference(continued.value)
                        readFreshTarget(owner, original)
                    }
                    is LocalSetupFreshDeviceTargetResult.Unavailable -> if (freshTargetCurrent(owner, original)) freshTargetFailure(original, continued.reason)
                }
            }
        }
    }

    override fun recoverFreshDevicePairing(reviewId: DomainId) {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val selected = requireNotNull(view.freshDevicePairing)
            requireOriginal(selected.reviewId == reviewId)
            when (selected.role) {
                LocalSetupFreshDevicePairingRole.FRESH_TARGET -> {
                    val original = requireNotNull(freshTargetOriginal)
                    operation {
                        requireFreshPairingJournal().target()?.let { original.retainRecord(it) }
                        val owner = (native as? FreshDeviceTarget) ?: acquireFreshTarget(original, null) ?: return@operation
                        readFreshTarget(owner, original)
                    }
                }
                LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER -> {
                    val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
                    val original = requireNotNull(target.freshPairingOriginal)
                    operation {
                        if (!original.preparationEntered && original.record == null) presentFreshMaster(original, freshMasterStage(original))
                        else inspectFreshMaster(target, client, original.requestId)
                    }
                }
            }
        }
    }

    override fun exportFreshDeviceDocument(reviewId: DomainId, kind: LocalSetupPairingDocumentKind) {
        start(LocalSetupWork.EXPORTING_FRESH_DEVICE_DOCUMENT, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val selected = requireNotNull(view.freshDevicePairing)
            requireOriginal(selected.reviewId == reviewId)
            val documents = requireNotNull(pairingDocuments)
            if (selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET) {
                val owner = requireFreshTarget(); val original = owner.original
                requireOriginal(kind == LocalSetupPairingDocumentKind.SDK_IDENTITY || kind == LocalSetupPairingDocumentKind.POSSESSION)
                operation {
                    if (!readFreshTarget(owner, original, readPossession = kind == LocalSetupPairingDocumentKind.POSSESSION)) return@operation
                    val bytes = when (kind) {
                        LocalSetupPairingDocumentKind.SDK_IDENTITY -> requireNotNull(original.identity).copyWireBytes()
                        LocalSetupPairingDocumentKind.POSSESSION -> requireNotNull(original.possession).copyWireBytes()
                        LocalSetupPairingDocumentKind.CHALLENGE, LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT,
                        LocalSetupPairingDocumentKind.APPROVED_MASTER -> throw OriginalRequired()
                    }
                    try {
                        requireOriginal(bytes.size in 1..kind.maximumBytes)
                        if (!freshTargetCurrent(owner, original)) return@operation
                        val result = documents.exportDocument(kind, bytes) { freshTargetCurrent(owner, original) }
                        if (!freshTargetCurrent(owner, original)) return@operation
                        original.document = freshExportResult(kind, bytes.size, result)
                        presentFreshTarget(original, freshTargetStage(original))
                        if (result is LocalSetupPairingDocumentExportResult.Unavailable) freshDocumentFailure(result.problem)
                    } finally { bytes.fill(0) }
                }
            } else {
                val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
                val original = requireNotNull(target.freshPairingOriginal)
                requireOriginal(kind == LocalSetupPairingDocumentKind.CHALLENGE || kind == LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT ||
                    kind == LocalSetupPairingDocumentKind.APPROVED_MASTER)
                operation {
                    if (!readFreshMasterAction(target, client, original)) return@operation
                    val bytes = if (kind == LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT) {
                        requireOriginal(original.nativeConfirmed && original.action?.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED)
                        requireNotNull(original.retirement).copyWireBytes()
                    } else if (kind == LocalSetupPairingDocumentKind.APPROVED_MASTER) {
                        requireOriginal(original.nativeConfirmed && original.action?.phase == LocalDevicePairingPhase.APPROVED)
                        val approval = when (val read = client.recoverDevicePairingApproval(requireNotNull(original.action).originalActionId)) {
                            is LocalCompanyAuthorityResult.Value -> read.value
                            is LocalCompanyAuthorityResult.Unavailable -> {
                                if (freshMasterCurrent(target, client, original)) freshDocumentFailure(authorityProblem(read))
                                return@operation
                            }
                        }
                        if (!freshMasterCurrent(target, client, original)) return@operation
                        requireOriginal(approval.originalActionId == original.action?.originalActionId &&
                            approval.challengeDigest == original.action?.challenge?.challengeDigest)
                        LocalDevicePairingMasterTransfer.capture(approval).copyWireBytes()
                    } else requireNotNull(original.action).challenge.copyWireBytes()
                    try {
                        requireOriginal(bytes.size in 1..kind.maximumBytes)
                        if (!freshMasterCurrent(target, client, original)) return@operation
                        val result = documents.exportDocument(kind, bytes) { freshMasterCurrent(target, client, original) }
                        if (!freshMasterCurrent(target, client, original)) return@operation
                        original.document = freshExportResult(kind, bytes.size, result)
                        presentFreshMaster(original, freshMasterStage(original))
                        if (result is LocalSetupPairingDocumentExportResult.Unavailable) freshDocumentFailure(result.problem)
                    } finally { bytes.fill(0) }
                }
            }
        }
    }

    private fun freshExportResult(kind: LocalSetupPairingDocumentKind, count: Int,
        result: LocalSetupPairingDocumentExportResult): LocalSetupPairingDocumentView = when (result) {
        LocalSetupPairingDocumentExportResult.Completed -> LocalSetupPairingDocumentView(kind, LocalSetupPairingDocumentOutcome.EXPORTED, count)
        LocalSetupPairingDocumentExportResult.Cancelled -> LocalSetupPairingDocumentView(kind, LocalSetupPairingDocumentOutcome.CANCELLED)
        is LocalSetupPairingDocumentExportResult.Unavailable -> {
            LocalSetupPairingDocumentView(kind, LocalSetupPairingDocumentOutcome.UNAVAILABLE)
        }
    }

    override fun handoffFreshDeviceAdmission(reviewId: DomainId) {
        start(LocalSetupWork.READING_DEVICE_ADMISSION, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.freshPairingOriginal)
            requireFreshReview(reviewId, LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER)
            requireOriginal(view.freshDevicePairing?.stage in setOf(LocalSetupFreshDevicePairingStage.TARGET_PROVED,
                LocalSetupFreshDevicePairingStage.RETAINED_ADMISSION_HANDOFF))
            operation {
                if (!readFreshMasterAction(target, client, original)) return@operation
                requireOriginal(original.action?.phase in FRESH_MASTER_HANDOFF_PHASES &&
                    original.action?.possession?.let { sameFreshPossession(it, requireNotNull(original.possession)) } == true)
                if (!freshMasterEffectCurrent(target, client, original)) return@operation
                val actionId = requireNotNull(original.action).originalActionId
                change { it.copy(phase = LocalSetupPhase.DEVICE_ADMISSION, freshDevicePairing = null,
                    deviceAdmissions = null, deviceAdmission = null, problem = null) }
                inspectDeviceAdmissionOriginal(target, client, actionId) // Review only: no head or admission dispatch.
            }
        }
    }

    override fun closeFreshDevicePairing() {
        start(LocalSetupWork.READING_FRESH_DEVICE_PAIRING, setOf(LocalSetupPhase.FRESH_DEVICE_PAIRING)) {
            val selected = requireNotNull(view.freshDevicePairing)
            if (selected.role == LocalSetupFreshDevicePairingRole.FRESH_TARGET) {
                operation {
                    val owner = native as? FreshDeviceTarget
                    if (owner != null) closeNativeOwner(owner)
                    if (continueWork()) change { it.copy(phase = LocalSetupPhase.CHOOSE_ROUTE, freshDevicePairing = null, problem = null) }
                }
            } else {
                val target = requireDeviceAdmissionTarget(); val client = requireNotNull(target.localClient)
                val original = requireNotNull(target.freshPairingOriginal)
                operation {
                    if (original.preparationEntered || original.record != null) {
                        if (!readFreshMasterAction(target, client, original)) return@operation
                        requireOriginal(original.action?.phase in FRESH_MASTER_HANDOFF_PHASES ||
                            original.action?.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED && original.retirement != null)
                    }
                    if (!freshMasterCurrent(target, client, original)) return@operation
                    // Keep the privately retained exact original, including uncertain force outcomes.
                    change { it.copy(phase = LocalSetupPhase.INSTALLATION_RETAINED, freshDevicePairing = null, problem = null) }
                }
            }
        }
    }

    override fun openDeviceAdmissions() {
        start(LocalSetupWork.READING_DEVICE_ADMISSION, DEVICE_ADMISSION_OPEN_PHASES) {
            val target = requireDeviceAdmissionTarget()
            val client = requireNotNull(target.localClient)
            operation {
                change { it.copy(phase = LocalSetupPhase.DEVICE_ADMISSION, problem = null) }
                if (deviceAdmissionIntents == null) {
                    change { it.copy(deviceAdmissions = null, deviceAdmission = null,
                        problem = LocalSetupProblem.NATIVE_UNAVAILABLE) }
                    return@operation
                }
                val inventory = deviceAdmissionInventory(target, client) ?: return@operation
                val blockedId = blockedDeviceAdmission(target, inventory)
                val selectedId = blockedId ?: target.deviceAdmissionOriginal?.actionId
                change { it.copy(deviceAdmissions = LocalSetupDeviceAdmissions(inventory.originals, blockedId),
                    deviceAdmission = target.deviceAdmissionOriginal?.projection(deviceAdmissionUnresolvedStage(target.deviceAdmissionOriginal)),
                    problem = null) }
                if (selectedId != null) inspectDeviceAdmissionOriginal(target, client, selectedId, inventory)
            }
        }
    }

    override fun inspectDeviceAdmission(originalActionId: DomainId) {
        start(LocalSetupWork.READING_DEVICE_ADMISSION, setOf(LocalSetupPhase.DEVICE_ADMISSION)) {
            val target = requireDeviceAdmissionTarget()
            val client = requireNotNull(target.localClient)
            operation { inspectDeviceAdmissionOriginal(target, client, originalActionId) }
        }
    }

    override fun approveDeviceAdmission(reviewId: DomainId, headSecret: CharArray) {
        try {
            start(LocalSetupWork.APPROVING_DEVICE_ADMISSION, setOf(LocalSetupPhase.DEVICE_ADMISSION)) {
                val target = requireDeviceAdmissionTarget()
                val client = requireNotNull(target.localClient)
                val original = requireNotNull(target.deviceAdmissionOriginal)
                val selected = requireNotNull(view.deviceAdmission)
                requireOriginal(selected.reviewId == reviewId && selected.originalActionId == original.actionId &&
                    selected.stage == LocalSetupDeviceAdmissionStage.HEAD_REVIEW && selected.requested === original.requested &&
                    !original.headAttemptEntered && !original.admissionAttemptEntered)
                require(headSecret.size in 12..256)
                val head = headSecret.copyOf()
                try {
                    operation(clear = { head.fill('\u0000') }) {
                        val inventory = deviceAdmissionInventory(target, client) ?: return@operation
                        requireDeviceAdmissionEffectInventory(target, original, inventory)
                        val reference = inventory.originals.single { it.originalActionId == original.actionId }
                        requireOriginal(reference.phase == LocalDevicePairingPhase.TARGET_PROVED)
                        if (!readDeviceAdmissionAction(target, client, original, reference)) return@operation
                        val record = requireNotNull(original.record)
                        requireOriginal(!record.headAttempted && record.authorityDigest == null &&
                            !record.admissionAttempted && record.terminal == null)
                        if (!deviceAdmissionOwnerCurrent(target, client)) return@operation
                        original.headAttemptEntered = true
                        original.retainRecord(requireDeviceAdmissionJournal().markHeadAttempt(record.identity))
                        // Both force and exact native review precede the uncertain ceremony.
                        presentDeviceAdmission(original, LocalSetupDeviceAdmissionStage.APPROVAL_UNRESOLVED)
                        val current = requireNotNull(original.action)
                        val result = try { client.approveDevicePairing(current, current.challenge.headLogin, head) }
                        finally { head.fill('\u0000') }
                        when (result) {
                            is LocalCompanyAuthorityResult.Value -> {
                                original.retainApproval(result.value)
                                original.phase = LocalDevicePairingPhase.APPROVED
                                original.retainRecord(requireDeviceAdmissionJournal().retainApproval(record.identity, result.value))
                                if (deviceAdmissionOwnerCurrent(target, client))
                                    presentDeviceAdmission(original, LocalSetupDeviceAdmissionStage.APPROVED)
                            }
                            is LocalCompanyAuthorityResult.Unavailable -> if (deviceAdmissionOwnerCurrent(target, client))
                                deviceAdmissionAuthorityFailure(result, original)
                        }
                    }
                } catch (failure: Throwable) { head.fill('\u0000'); throw failure }
            }
        } finally { headSecret.fill('\u0000') }
    }

    override fun admitApprovedDevice(reviewId: DomainId) {
        start(LocalSetupWork.ADMITTING_DEVICE, setOf(LocalSetupPhase.DEVICE_ADMISSION)) {
            val target = requireDeviceAdmissionTarget()
            val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.deviceAdmissionOriginal)
            val selected = requireNotNull(view.deviceAdmission)
            val approval = requireNotNull(original.approval)
            requireOriginal(selected.reviewId == reviewId && selected.originalActionId == original.actionId &&
                selected.stage == LocalSetupDeviceAdmissionStage.APPROVED && selected.requested === original.requested &&
                !original.admissionAttemptEntered && original.record?.authorityDigest == approval.authorityDigest.value &&
                original.record?.terminal == null)
            operation {
                val inventory = deviceAdmissionInventory(target, client) ?: return@operation
                requireDeviceAdmissionEffectInventory(target, original, inventory)
                val reference = inventory.originals.single { it.originalActionId == original.actionId }
                requireOriginal(reference.phase == LocalDevicePairingPhase.APPROVED)
                if (!readDeviceAdmissionAction(target, client, original, reference)) return@operation
                val record = requireNotNull(original.record)
                requireOriginal(!record.admissionAttempted && record.terminal == null && record.authorityDigest == approval.authorityDigest.value)
                val current = when (val read = client.recoverDevicePairingApproval(original.actionId)) {
                    is LocalCompanyAuthorityResult.Value -> read.value
                    is LocalCompanyAuthorityResult.Unavailable -> {
                        if (deviceAdmissionOwnerCurrent(target, client)) deviceAdmissionAuthorityFailure(read, original)
                        return@operation
                    }
                }
                original.retainApproval(current)
                requireOriginal(current.authorityDigest == approval.authorityDigest)
                original.retainRecord(requireDeviceAdmissionJournal().retainApproval(record.identity, current))
                if (!deviceAdmissionOwnerCurrent(target, client)) return@operation
                original.admissionAttemptEntered = true
                original.retainRecord(requireDeviceAdmissionJournal().markAdmissionAttempt(record.identity))
                // Never reset this marker after refusal, Busy, timeout, lost delivery or restart.
                presentDeviceAdmission(original, LocalSetupDeviceAdmissionStage.ADMISSION_UNRESOLVED)
                when (val result = client.admitOriginalDevice(current)) {
                    is LocalCredentialAdministrationResult.Applied -> {
                        requireOriginal(result.eventId == original.requestId)
                        // The dispatch reply cannot substitute for original native/protected
                        // outcome lookup. Keep the attempt unresolved until that read settles it.
                        if (deviceAdmissionOwnerCurrent(target, client))
                            lookupDeviceAdmissionOutcome(target, client, original)
                    }
                    else -> if (deviceAdmissionOwnerCurrent(target, client)) presentDeviceAdmission(original,
                        LocalSetupDeviceAdmissionStage.ADMISSION_UNRESOLVED, when (result) {
                            LocalCredentialAdministrationResult.Denied -> LocalSetupDeviceAdmissionOutcome.DENIED
                            LocalCredentialAdministrationResult.Unavailable -> LocalSetupDeviceAdmissionOutcome.UNAVAILABLE
                            LocalCredentialAdministrationResult.StorageUncertain -> LocalSetupDeviceAdmissionOutcome.STORAGE_UNCERTAIN
                            LocalCredentialAdministrationResult.Busy -> LocalSetupDeviceAdmissionOutcome.BUSY
                            is LocalCredentialAdministrationResult.Applied -> error("setup.device-original-result-required")
                        })
                }
            }
        }
    }

    override fun lookupDeviceAdmission(reviewId: DomainId) {
        start(LocalSetupWork.LOOKING_UP_DEVICE_ADMISSION, setOf(LocalSetupPhase.DEVICE_ADMISSION)) {
            val target = requireDeviceAdmissionTarget()
            val client = requireNotNull(target.localClient)
            val original = requireNotNull(target.deviceAdmissionOriginal)
            requireOriginal(view.deviceAdmission?.let { it.reviewId == reviewId && it.originalActionId == original.actionId } == true)
            operation {
                val retained = original.record
                if (retained != null && (original.admissionAttemptEntered || retained.terminal != null)) {
                    original.retainRecord(requireDeviceAdmissionJournal().original(retained.identity))
                    // Original outcome lookup is independent from a currently unavailable
                    // pairing-list/review delivery. Native alone joins its complete history.
                    lookupDeviceAdmissionOutcome(target, client, original)
                } else inspectDeviceAdmissionOriginal(target, client, original.actionId, inspectOutcome = true)
            }
        }
    }

    override fun closeDeviceAdmissions() {
        start(LocalSetupWork.READING_DEVICE_ADMISSION, setOf(LocalSetupPhase.DEVICE_ADMISSION)) {
            val target = requireDeviceAdmissionTarget()
            val client = requireNotNull(target.localClient)
            operation {
                if (deviceAdmissionIntents != null) {
                    val inventory = deviceAdmissionInventory(target, client) ?: return@operation
                    requireOriginal(blockedDeviceAdmission(target, inventory) == null)
                    val original = target.deviceAdmissionOriginal
                    if (original != null && (original.admissionAttemptEntered || original.record?.terminal != null)) {
                        inspectDeviceAdmissionOriginal(target, client, original.actionId, inventory)
                        requireOriginal(view.deviceAdmission?.stage in setOf(LocalSetupDeviceAdmissionStage.APPLIED,
                            LocalSetupDeviceAdmissionStage.KNOWN_NO_EFFECT))
                    }
                }
                if (!deviceAdmissionOwnerCurrent(target, client)) return@operation
                target.deviceAdmissionOriginal = null
                change { it.copy(phase = LocalSetupPhase.INSTALLATION_RETAINED,
                    deviceAdmissions = null, deviceAdmission = null, problem = null) }
            }
        }
    }

    private fun requireFreshPairingJournal(): LocalFreshDevicePairingIntents = checkNotNull(freshDevicePairingIntents)
    private fun requireFreshTarget(): FreshDeviceTarget = (native as? FreshDeviceTarget)?.also {
        requireOriginal(freshTargetCurrent(it, it.original))
    } ?: throw OriginalRequired()

    private fun requireFreshReview(reviewId: DomainId, role: LocalSetupFreshDevicePairingRole) {
        requireOriginal(view.freshDevicePairing?.let { it.reviewId == reviewId && it.role == role } == true)
    }

    private fun freshPresentationOpen(): Boolean = withSetupLock(gate) {
        !retiring && !view.locked && view.phase == LocalSetupPhase.FRESH_DEVICE_PAIRING
    }

    private fun freshTargetCurrent(owner: FreshDeviceTarget, original: LocalSetupFreshDeviceTargetOriginal): Boolean =
        withSetupLock(gate) { !retiring && !owner.lanTransferEntered && native === owner &&
            owner.original === original && freshTargetOriginal === original }

    private fun freshTargetEffectCurrent(owner: FreshDeviceTarget, original: LocalSetupFreshDeviceTargetOriginal): Boolean =
        freshTargetCurrent(owner, original) && freshPresentationOpen()

    private fun freshMasterCurrent(target: Target, client: LocalSetupClient,
        original: LocalSetupFreshDeviceMasterOriginal): Boolean = withSetupLock(gate) {
        val current = !retiring && native === target && target.localClient === client && target.freshPairingOriginal === original &&
            client.isCurrent() && view.localClientConnected && target.master?.mode?.value == LocalMasterLifecycleMode.OPERATIONAL
        if (!current && !retiring && native === target) {
            original.nativeConfirmed = false
            target.confirmedFreshPairingRequests.remove(original.requestId)
            view = view.copy(locked = true, localClientConnected = false, localClientConnectionAvailable = false,
                problem = LocalSetupProblem.RUNTIME_PUBLICATION_HELD,
                freshDevicePairing = view.freshDevicePairing?.copy(reviewId = DomainId.random(),
                    stage = freshMasterUnresolvedStage(original), nativePhase = null))
            publishLocked()
        }
        current
    }

    private fun freshMasterEffectCurrent(target: Target, client: LocalSetupClient,
        original: LocalSetupFreshDeviceMasterOriginal): Boolean = freshMasterCurrent(target, client, original) && freshPresentationOpen()

    private fun requireFreshRpcNamespace(identity: LocalDevicePairingSdkIdentityV2) {
        requireOriginal(identity.appId == LocalMasterSdkProfileV1.appId(identity.target.admission.scope))
    }

    private suspend fun acquireFreshTarget(original: LocalSetupFreshDeviceTargetOriginal,
        preparation: LocalSetupFreshDeviceTargetInput?): FreshDeviceTarget? {
        val owner = FreshDeviceTarget(original)
        if (!reserveAcquisition(owner)) return null
        try {
            val opened = if (preparation == null) services.openOriginalFreshDeviceTarget()
                else services.prepareFreshDeviceTarget(preparation.scope, preparation.capabilities)
            return when (opened) {
                is LocalSetupFreshDeviceTargetOpenResult.Opened -> {
                    owner.capture(opened.target) // Actual returned owner first, before projection/validation.
                    retainNative(owner)
                    owner
                }
                is LocalSetupFreshDeviceTargetOpenResult.Unresolved -> {
                    opened.original?.let { original.retainReference(it) }
                    change { it.copy(phase = LocalSetupPhase.FRESH_DEVICE_PAIRING,
                        freshDevicePairing = original.projection(LocalSetupFreshDevicePairingStage.ORIGINAL_UNAVAILABLE),
                        problem = LocalSetupProblem.CLOSE_UNCONFIRMED, nativeProblem = opened.reason.toSetupProblem()) }
                    null // Unresolved factory cleanup keeps this exact partial holder and service slot.
                }
            }
        } catch (failure: Throwable) {
            try { requestRetirement() } finally { throw failure }
        }
    }

    private suspend fun readFreshTarget(owner: FreshDeviceTarget, original: LocalSetupFreshDeviceTargetOriginal,
        readPossession: Boolean = true): Boolean {
        if (!freshTargetCurrent(owner, original)) return false
        val journal = requireFreshPairingJournal()
        journal.target()?.let { original.retainRecord(it); original.retainCycles(journal.responseCycles(it.identity.intentId)) }
        if (original.responseCycle > 0 && !original.continuationConfirmed) {
            // Detached original identity DATA may be re-read even when allocation is unresolved.
            when (val descriptor = owner.value.descriptor()) {
                is LocalSetupFreshDeviceTargetResult.Value -> { if (!freshTargetCurrent(owner, original)) return false; original.retainDescriptor(descriptor.value) }
                is LocalSetupFreshDeviceTargetResult.Unavailable -> { freshTargetFailure(original, descriptor.reason); return false }
            }
            when (val sdk = owner.value.recoverSdkIdentity()) {
                is LocalSetupFreshDeviceTargetResult.Value -> { if (!freshTargetCurrent(owner, original)) return false; original.retainIdentity(sdk.value) }
                is LocalSetupFreshDeviceTargetResult.Unavailable -> { freshTargetFailure(original, sdk.reason); return false }
            }
            val certificate = original.retainedContinuationCertificate
            if (certificate == null) { presentFreshTarget(original, LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED); return false }
            when (val recovery = owner.value.recoverAfterKnownUnsigned(certificate)) {
                is LocalSetupFreshDeviceTargetResult.Value -> {
                    if (!freshTargetCurrent(owner, original)) return false
                    val intentId = requireNotNull(original.intentId)
                    journal.retainTargetContinuation(intentId, certificate, recovery.value)
                    original.retainCycles(journal.responseCycles(intentId)); original.retainReference(recovery.value)
                }
                is LocalSetupFreshDeviceTargetResult.Unavailable -> { if (freshTargetCurrent(owner, original)) freshTargetFailure(original, recovery.reason); return false }
            }
        }
        when (val read = owner.value.original()) {
            is LocalSetupFreshDeviceTargetResult.Value -> {
                if (!freshTargetCurrent(owner, original)) return false
                requireOriginal(original.responseCycle > 0 || read.value.phase !in setOf(
                    LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_SIGNING_INTENT,
                    LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY))
                original.retainReference(read.value)
                original.record?.takeIf { it.preparationAttempted }?.let {
                    original.retainRecord(requireFreshPairingJournal().retainTargetReference(it.identity.intentId, read.value))
                }
            }
            is LocalSetupFreshDeviceTargetResult.Unavailable -> {
                if (freshTargetCurrent(owner, original)) freshTargetFailure(original, read.reason)
                return false
            }
        }
        when (val read = owner.value.descriptor()) {
            is LocalSetupFreshDeviceTargetResult.Value -> {
                if (!freshTargetCurrent(owner, original)) return false
                original.retainDescriptor(read.value)
            }
            is LocalSetupFreshDeviceTargetResult.Unavailable -> {
                if (freshTargetCurrent(owner, original)) freshTargetFailure(original, read.reason)
                return false
            }
        }
        if (!original.sdkEntered) {
            presentFreshTarget(original, if (original.reference?.phase == LocalDevicePairingTargetPhase.KEY_READY)
                LocalSetupFreshDevicePairingStage.TARGET_KEY_READY else LocalSetupFreshDevicePairingStage.TARGET_PREPARATION_UNRESOLVED)
            return original.reference?.phase == LocalDevicePairingTargetPhase.KEY_READY
        }
        when (val read = owner.value.recoverSdkIdentity()) {
            is LocalSetupFreshDeviceTargetResult.Value -> {
                if (!freshTargetCurrent(owner, original)) return false
                original.retainIdentity(read.value)
                original.record?.let {
                    requireOriginal(it.sdkAttempted)
                    original.retainRecord(requireFreshPairingJournal().retainTargetSdkIdentity(it.identity.intentId, read.value))
                }
            }
            is LocalSetupFreshDeviceTargetResult.Unavailable -> {
                if (freshTargetCurrent(owner, original)) freshTargetFailure(original, read.reason)
                return false
            }
        }
        if (original.responseEntered) {
            if (!readPossession) {
                presentFreshTarget(original, LocalSetupFreshDevicePairingStage.TARGET_RESPONSE_UNRESOLVED)
                return true
            }
            val actionId = original.responseActionId
            if (actionId == null) {
                presentFreshTarget(original, LocalSetupFreshDevicePairingStage.TARGET_RESPONSE_UNRESOLVED)
                return false
            }
            when (val read = owner.value.recoverPossession(actionId)) {
                is LocalSetupFreshDeviceTargetResult.Value -> {
                    if (!freshTargetCurrent(owner, original)) return false
                    original.retainPossession(read.value)
                    original.record?.let {
                        if (original.responseCycle == 0 && it.responseActionId != null)
                            original.retainRecord(journal.retainTargetPossession(it.identity.intentId, read.value))
                        else if (original.responseCycle > 0) {
                            journal.retainSuccessorTargetPossession(it.identity.intentId, read.value)
                            original.retainCycles(journal.responseCycles(it.identity.intentId))
                        }
                    }
                    presentFreshTarget(original, freshTargetStage(original))
                }
                is LocalSetupFreshDeviceTargetResult.Unavailable -> {
                    if (freshTargetCurrent(owner, original)) freshTargetFailure(original, read.reason)
                    return false
                }
            }
        } else presentFreshTarget(original, freshTargetStage(original))
        return freshTargetCurrent(owner, original)
    }

    private fun freshTargetStage(original: LocalSetupFreshDeviceTargetOriginal): LocalSetupFreshDevicePairingStage = when {
        original.continuationAttempted || original.responseCycle > 0 && !original.continuationConfirmed -> LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED
        original.retirement != null -> LocalSetupFreshDevicePairingStage.TARGET_RETIREMENT_REVIEW
        original.verifiedMaster != null -> LocalSetupFreshDevicePairingStage.TARGET_MASTER_VERIFIED
        original.masterImportEntered -> LocalSetupFreshDevicePairingStage.TARGET_MASTER_UNRESOLVED
        original.masterTransfer != null -> LocalSetupFreshDevicePairingStage.TARGET_MASTER_REVIEW
        original.responseEntered -> if (original.possession != null) LocalSetupFreshDevicePairingStage.TARGET_POSSESSION_READY
            else LocalSetupFreshDevicePairingStage.TARGET_RESPONSE_UNRESOLVED
        original.identity != null -> if (original.challenge != null) LocalSetupFreshDevicePairingStage.TARGET_CHALLENGE_REVIEW
            else if (original.continuationConfirmed) LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_READY
            else LocalSetupFreshDevicePairingStage.SDK_IDENTITY_READY
        original.sdkEntered -> LocalSetupFreshDevicePairingStage.SDK_ACQUISITION_UNRESOLVED
        original.descriptor != null && original.reference?.phase == LocalDevicePairingTargetPhase.KEY_READY -> LocalSetupFreshDevicePairingStage.TARGET_KEY_READY
        original.preparationEntered -> LocalSetupFreshDevicePairingStage.TARGET_PREPARATION_UNRESOLVED
        original.input != null -> LocalSetupFreshDevicePairingStage.TARGET_INPUT_REVIEW
        else -> LocalSetupFreshDevicePairingStage.TARGET_INPUT
    }

    private class FreshMasterInventory(val records: List<LocalFreshDevicePairingIntents.MasterRecord>,
        val originals: List<LocalDevicePairingReference>, val blockedRequestId: DomainId?)

    private suspend fun freshMasterInventory(target: Target, client: LocalSetupClient): FreshMasterInventory? {
        if (!deviceAdmissionOwnerCurrent(target, client)) return null
        val records = requireFreshPairingJournal().masters(deviceAdmissionContext(target))
        val read = client.retainedDevicePairings()
        if (!deviceAdmissionOwnerCurrent(target, client)) return null
        return when (read) {
            is LocalCompanyAuthorityResult.Value -> {
                val originals = read.value
                requireOriginal(originals.size <= LocalDevicePairingProfileV1.MAXIMUM_ACTIONS &&
                    originals.map { it.originalActionId }.distinct().size == originals.size &&
                    originals.map { it.originalRequestId }.distinct().size == originals.size)
                records.forEach { record -> originals.singleOrNull { it.originalRequestId == record.identity.requestId }?.let { native ->
                    requireOriginal((record.actionId == null || record.actionId == native.originalActionId) &&
                        record.identity.deviceId == native.deviceId && record.identity.keyId == native.keyId)
                } }
                val retained = target.freshPairingOriginal
                val blocked = retained?.takeIf { it.preparationEntered && records.none { record -> record.identity.requestId == it.requestId } }?.requestId
                    ?: records.firstOrNull { record ->
                        val reference = originals.singleOrNull { it.originalRequestId == record.identity.requestId }
                        reference == null || (reference.phase !in FRESH_MASTER_HANDOFF_PHASES && reference.phase != LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED) ||
                            record.identity.requestId !in target.confirmedFreshPairingRequests
                    }?.identity?.requestId
                FreshMasterInventory(records, originals, blocked)
            }
            is LocalCompanyAuthorityResult.Unavailable -> {
                target.freshPairingOriginal?.let { freshMasterFailure(it, read) }
                    ?: change { it.copy(phase = LocalSetupPhase.FRESH_DEVICE_PAIRING, problem = authorityProblem(read),
                        freshDevicePairing = null) }
                null
            }
        }
    }

    private suspend fun requireFreshMasterCanBegin(target: Target, client: LocalSetupClient, inventory: FreshMasterInventory) {
        requireOriginal(inventory.blockedRequestId == null && inventory.originals.none {
            it.phase == LocalDevicePairingPhase.PREPARED || it.phase in UNRESOLVED_DEVICE_PAIRING_PHASES })
        val admissions = deviceAdmissionInventory(target, client) ?: throw OriginalRequired()
        requireOriginal(blockedDeviceAdmission(target, admissions) == null)
    }

    private suspend fun inspectFreshMaster(target: Target, client: LocalSetupClient, requestId: DomainId,
        supplied: FreshMasterInventory? = null) {
        val inventory = supplied ?: freshMasterInventory(target, client) ?: return
        requireOriginal(inventory.blockedRequestId == null || inventory.blockedRequestId == requestId ||
            requestId in target.confirmedFreshPairingRequests)
        val retained = target.freshPairingOriginal
        requireOriginal(retained == null || !retained.preparationEntered || retained.requestId == requestId ||
            retained.requestId in target.confirmedFreshPairingRequests)
        val record = inventory.records.singleOrNull { it.identity.requestId == requestId }
        val original = retained?.takeIf { it.requestId == requestId }
            ?: record?.let { LocalSetupFreshDeviceMasterOriginal(it.identity.context, requestId) }
            ?: throw OriginalRequired()
        target.freshPairingOriginal = original
        record?.let { original.retainRecord(it) }
        original.nativeConfirmed = false
        presentFreshMaster(original, freshMasterUnresolvedStage(original), inventory)
        if (readFreshMasterAction(target, client, original, inventory)) {
            val refreshed = freshMasterInventory(target, client) ?: return
            presentFreshMaster(original, freshMasterStage(original), refreshed)
        }
    }

    private suspend fun readFreshMasterAction(target: Target, client: LocalSetupClient,
        original: LocalSetupFreshDeviceMasterOriginal, supplied: FreshMasterInventory? = null): Boolean {
        original.nativeConfirmed = false
        target.confirmedFreshPairingRequests.remove(original.requestId)
        val inventory = supplied ?: freshMasterInventory(target, client) ?: return false
        if (!freshMasterCurrent(target, client, original)) return false
        val record = inventory.records.singleOrNull { it.identity.requestId == original.requestId }
        record?.let { original.retainRecord(it) }
        val reference = inventory.originals.singleOrNull { it.originalRequestId == original.requestId }
        if (reference == null || record == null) {
            presentFreshMaster(original, freshMasterUnresolvedStage(original), inventory)
            change { it.copy(problem = LocalSetupProblem.STATE_UNCERTAIN) }
            return false // Missing request/ref is never no-effect or permission for preparation.
        }
        when (val read = client.reviewDevicePairing(reference.originalActionId)) {
            is LocalCompanyAuthorityResult.Value -> {
                if (!freshMasterCurrent(target, client, original)) return false
                requireOriginal(read.value.originalActionId == reference.originalActionId && read.value.phase == reference.phase)
                original.retainAction(read.value)
                requireOriginal(isSdkBoundDeviceAdmissionOriginal(target, read.value))
                original.retainRecord(requireFreshPairingJournal().retainMasterAction(original.context, original.requestId, read.value))
                if (read.value.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED) {
                    val retirement = client.recoverUnsignedDevicePairingRetirement(read.value.originalActionId)
                    if (!freshMasterCurrent(target, client, original)) return false
                    when (retirement) {
                        is LocalCompanyAuthorityResult.Value -> {
                            original.retainRetirement(retirement.value)
                            if (original.retirementEntered) original.retainRecord(requireFreshPairingJournal().retainMasterRetirement(
                                original.context, original.requestId, retirement.value))
                        }
                        is LocalCompanyAuthorityResult.Unavailable -> { freshMasterFailure(original, retirement); return false }
                    }
                }
                original.nativeConfirmed = true
                if (read.value.phase in FRESH_MASTER_HANDOFF_PHASES) {
                    requireOriginal(read.value.possession != null)
                    target.confirmedFreshPairingRequests.add(original.requestId)
                } else if (read.value.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED) {
                    requireOriginal(original.retirement != null)
                    target.confirmedFreshPairingRequests.add(original.requestId)
                }
                return true
            }
            is LocalCompanyAuthorityResult.Unavailable -> {
                if (freshMasterCurrent(target, client, original)) freshMasterFailure(original, read)
                return false
            }
        }
    }

    private fun freshMasterUnresolvedStage(original: LocalSetupFreshDeviceMasterOriginal): LocalSetupFreshDevicePairingStage =
        if (original.retirementEntered || original.action?.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED) LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_UNRESOLVED
        else if (original.proofEntered) LocalSetupFreshDevicePairingStage.MASTER_PROOF_UNRESOLVED
        else if (original.preparationEntered || original.record != null) LocalSetupFreshDevicePairingStage.MASTER_PREPARATION_UNRESOLVED
        else LocalSetupFreshDevicePairingStage.ORIGINAL_UNAVAILABLE

    private fun freshMasterStage(original: LocalSetupFreshDeviceMasterOriginal): LocalSetupFreshDevicePairingStage = when {
        original.preparationEntered || original.record != null -> {
            if (!original.nativeConfirmed) freshMasterUnresolvedStage(original)
            else if (original.action?.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED && original.retirement != null)
                LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED
            else if (original.retirementEntered) LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_UNRESOLVED
            else if (original.retirementReview) LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_REVIEW
            else when (original.action?.phase) {
                LocalDevicePairingPhase.TARGET_PROVED -> LocalSetupFreshDevicePairingStage.TARGET_PROVED
                LocalDevicePairingPhase.APPROVED -> LocalSetupFreshDevicePairingStage.RETAINED_ADMISSION_HANDOFF
                LocalDevicePairingPhase.PREPARED -> if (original.retirementEntered || original.action?.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED) LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_UNRESOLVED
        else if (original.proofEntered) LocalSetupFreshDevicePairingStage.MASTER_PROOF_UNRESOLVED
                    else if (original.possession != null) LocalSetupFreshDevicePairingStage.MASTER_POSSESSION_REVIEW
                    else LocalSetupFreshDevicePairingStage.MASTER_CHALLENGE_READY
                else -> freshMasterUnresolvedStage(original)
            }
        }
        original.identity != null -> LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_REVIEW
        else -> LocalSetupFreshDevicePairingStage.MASTER_IDENTITY_IMPORT
    }

    private fun presentFreshTarget(original: LocalSetupFreshDeviceTargetOriginal,
        stage: LocalSetupFreshDevicePairingStage) = change {
        it.copy(phase = LocalSetupPhase.FRESH_DEVICE_PAIRING, freshDevicePairing = original.projection(stage), problem = null)
    }

    private fun presentFreshMaster(original: LocalSetupFreshDeviceMasterOriginal,
        stage: LocalSetupFreshDevicePairingStage, inventory: FreshMasterInventory? = null) = change {
        val previous = it.freshDevicePairing
        val projection = original.projection(stage, inventory?.records.orEmpty(), inventory?.blockedRequestId)
        it.copy(phase = LocalSetupPhase.FRESH_DEVICE_PAIRING, freshDevicePairing = if (inventory == null)
            projection.copy(masterOriginals = previous?.masterOriginals.orEmpty(), blockedRequestId = previous?.blockedRequestId)
            else projection, problem = null)
    }

    private fun freshTargetFailure(original: LocalSetupFreshDeviceTargetOriginal, reason: SelfOwnedInstallationFailureV1) = change {
        val stage = when {
            original.continuationAttempted || original.responseCycle > 0 && !original.continuationConfirmed -> LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED
            original.responseEntered -> LocalSetupFreshDevicePairingStage.TARGET_RESPONSE_UNRESOLVED
            original.sdkEntered -> LocalSetupFreshDevicePairingStage.SDK_ACQUISITION_UNRESOLVED
            original.preparationEntered -> LocalSetupFreshDevicePairingStage.TARGET_PREPARATION_UNRESOLVED
            else -> LocalSetupFreshDevicePairingStage.ORIGINAL_UNAVAILABLE
        }
        it.copy(phase = LocalSetupPhase.FRESH_DEVICE_PAIRING, freshDevicePairing = original.projection(stage), problem = reason.toSetupProblem())
    }

    private fun freshMasterFailure(original: LocalSetupFreshDeviceMasterOriginal, reason: LocalCompanyAuthorityResult.Unavailable) {
        original.nativeConfirmed = false
        (native as? Target)?.confirmedFreshPairingRequests?.remove(original.requestId)
        change { current -> current.copy(phase = LocalSetupPhase.FRESH_DEVICE_PAIRING, problem = authorityProblem(reason),
            freshDevicePairing = original.projection(freshMasterUnresolvedStage(original)).copy(
                masterOriginals = current.freshDevicePairing?.masterOriginals.orEmpty(),
                blockedRequestId = original.requestId)) }
    }

    private fun freshDocumentFailure(problem: LocalSetupProblem) = change {
        it.copy(problem = problem, freshDevicePairing = it.freshDevicePairing?.copy(reviewId = DomainId.random()))
    }

    private fun requireDeviceAdmissionTarget(): Target = requireTarget().also { target ->
        requireOriginal(deviceAdmissionOwnerCurrent(target, target.localClient))
    }

    private fun deviceAdmissionOwnerCurrent(target: Target, client: LocalSetupClient?): Boolean = withSetupLock(gate) {
        val current = !retiring && native === target && target.localClient === client && client?.isCurrent() == true &&
            view.localClientConnected && target.master?.mode?.value == LocalMasterLifecycleMode.OPERATIONAL
        if (!current && !retiring && native === target) {
            view = view.copy(locked = true, localClientConnected = false,
                localClientConnectionAvailable = false, problem = LocalSetupProblem.RUNTIME_PUBLICATION_HELD,
                deviceAdmission = view.deviceAdmission?.copy(reviewId = DomainId.random(),
                    stage = deviceAdmissionUnresolvedStage(target.deviceAdmissionOriginal), outcome = null, eventId = null))
            publishLocked()
        }
        current
    }

    private fun requireDeviceAdmissionJournal(): LocalDeviceAdmissionIntents = checkNotNull(deviceAdmissionIntents)

    private fun deviceAdmissionContext(target: Target): LocalDeviceAdmissionIntents.Context {
        val coordinates = target.context.materialAtOpen.coordinates
        return LocalDeviceAdmissionIntents.Context(coordinates.installationId, coordinates.scope, coordinates.deviceId)
    }

    private class DeviceAdmissionInventory(val records: List<LocalDeviceAdmissionIntents.Record>,
        val originals: List<LocalDevicePairingReference>)

    private suspend fun deviceAdmissionInventory(target: Target, client: LocalSetupClient): DeviceAdmissionInventory? {
        if (!deviceAdmissionOwnerCurrent(target, client)) return null
        val records = requireDeviceAdmissionJournal().records(deviceAdmissionContext(target))
        val read = client.retainedDevicePairings()
        if (!deviceAdmissionOwnerCurrent(target, client)) return null
        return when (read) {
            is LocalCompanyAuthorityResult.Value -> {
                val originals = read.value
                requireOriginal(originals.size <= LocalDevicePairingProfileV1.MAXIMUM_ACTIONS &&
                    originals.map { it.originalActionId }.distinct().size == originals.size &&
                    originals.map { it.originalRequestId }.distinct().size == originals.size)
                records.forEach { record -> originals.singleOrNull { it.originalActionId == record.identity.actionId }?.let { reference ->
                    requireOriginal(reference.originalRequestId == record.identity.requestId &&
                        reference.deviceId == record.identity.deviceId && reference.keyId == record.identity.keyId)
                } }
                DeviceAdmissionInventory(records, originals)
            }
            is LocalCompanyAuthorityResult.Unavailable -> {
                val pending = records.singleOrNull { it.unresolvedAttempt }
                    ?: records.firstOrNull { it.terminal != null && it.identity.actionId !in target.confirmedDeviceAdmissionTerminals }
                val original = target.deviceAdmissionOriginal ?: pending?.let { LocalSetupDeviceAdmissionOriginal(it) }
                if (original != null) {
                    records.singleOrNull { it.identity.actionId == original.actionId }?.let { original.retainRecord(it) }
                    target.deviceAdmissionOriginal = original
                    change { it.copy(deviceAdmissions = LocalSetupDeviceAdmissions(unresolvedActionId = original.actionId)) }
                }
                deviceAdmissionAuthorityFailure(read, original)
                null
            }
        }
    }

    /** Unknown originals and missing retained history cannot be replaced by another selection. */
    private fun blockedDeviceAdmission(target: Target, inventory: DeviceAdmissionInventory): DomainId? {
        val blocked = inventory.records.filter { it.unresolvedAttempt }.map { it.identity.actionId } +
            listOfNotNull(target.deviceAdmissionOriginal?.takeIf { it.unresolvedAttempt }?.actionId) +
            inventory.originals.filter { it.phase in UNRESOLVED_DEVICE_PAIRING_PHASES }.map { it.originalActionId } +
            inventory.records.filter { record -> inventory.originals.none { it.originalActionId == record.identity.actionId } }
                .map { it.identity.actionId }
        requireOriginal(blocked.distinct().size <= 1)
        // Several previously settled originals may be retained after a restart.
        // Revalidate them one at a time; saved terminal labels alone cannot
        // unblock selection or effectful work on a different original.
        return blocked.firstOrNull() ?: inventory.records.firstOrNull {
            it.terminal != null && it.identity.actionId !in target.confirmedDeviceAdmissionTerminals
        }?.identity?.actionId
    }

    private fun requireDeviceAdmissionEffectInventory(target: Target, original: LocalSetupDeviceAdmissionOriginal,
        inventory: DeviceAdmissionInventory) {
        requireOriginal(blockedDeviceAdmission(target, inventory)?.let { it == original.actionId } != false &&
            inventory.records.all { record -> inventory.originals.any { it.originalActionId == record.identity.actionId } } &&
            inventory.originals.none { it.phase in UNRESOLVED_DEVICE_PAIRING_PHASES })
        inventory.records.singleOrNull { it.identity.actionId == original.actionId }?.let { original.retainRecord(it) }
    }

    private suspend fun inspectDeviceAdmissionOriginal(target: Target, client: LocalSetupClient, actionId: DomainId,
        suppliedInventory: DeviceAdmissionInventory? = null, inspectOutcome: Boolean = false) {
        val inventory = suppliedInventory ?: deviceAdmissionInventory(target, client) ?: return
        if (!deviceAdmissionOwnerCurrent(target, client)) return
        val blockedId = blockedDeviceAdmission(target, inventory)
        requireOriginal(blockedId == null || blockedId == actionId)
        val reference = inventory.originals.singleOrNull { it.originalActionId == actionId }
        val record = inventory.records.singleOrNull { it.identity.actionId == actionId }
        val retained = target.deviceAdmissionOriginal
        requireOriginal(retained?.let { !it.unresolvedAttempt || it.actionId == actionId } != false)
        val original = retained?.takeIf { it.actionId == actionId } ?: reference?.let { LocalSetupDeviceAdmissionOriginal(it) }
            ?: record?.let { LocalSetupDeviceAdmissionOriginal(it) } ?: throw OriginalRequired()
        target.deviceAdmissionOriginal = original
        record?.let { original.retainRecord(it) }
        change { it.copy(deviceAdmissions = LocalSetupDeviceAdmissions(inventory.originals, blockedId),
            deviceAdmission = original.projection(deviceAdmissionUnresolvedStage(original)), problem = null) }
        if (reference == null) {
            original.phase = null
            presentDeviceAdmission(original, LocalSetupDeviceAdmissionStage.ORIGINAL_UNAVAILABLE, LocalSetupDeviceAdmissionOutcome.UNAVAILABLE)
            if (original.admissionAttemptEntered || original.record?.terminal != null) lookupDeviceAdmissionOutcome(target, client, original)
            return // Intent identity does not reconstruct a missing native original.
        }
        val reviewed = readDeviceAdmissionAction(target, client, original, reference)
        if (!deviceAdmissionOwnerCurrent(target, client)) return
        if (original.admissionAttemptEntered || original.record?.terminal != null) {
            // Even failed review/recovery cannot re-arm a submitted original or replace lookup.
            if (original.record != null) lookupDeviceAdmissionOutcome(target, client, original)
            return
        }
        if (!reviewed) return
        when (original.phase) {
            LocalDevicePairingPhase.TARGET_PROVED -> presentDeviceAdmission(original,
                if (original.headAttemptEntered) LocalSetupDeviceAdmissionStage.APPROVAL_UNRESOLVED else LocalSetupDeviceAdmissionStage.HEAD_REVIEW)
            LocalDevicePairingPhase.APPROVED -> {
                val read = client.recoverDevicePairingApproval(actionId)
                when (read) {
                    is LocalCompanyAuthorityResult.Value -> {
                        original.retainApproval(read.value)
                        original.retainRecord(requireDeviceAdmissionJournal().retainApproval(requireNotNull(original.record).identity, read.value))
                        if (deviceAdmissionOwnerCurrent(target, client)) {
                            presentDeviceAdmission(original, LocalSetupDeviceAdmissionStage.APPROVED)
                            if (inspectOutcome) lookupDeviceAdmissionOutcome(target, client, original)
                        }
                    }
                    is LocalCompanyAuthorityResult.Unavailable -> if (deviceAdmissionOwnerCurrent(target, client))
                        deviceAdmissionAuthorityFailure(read, original)
                }
            }
            LocalDevicePairingPhase.HEAD_CONSENT_INTENT, LocalDevicePairingPhase.SIGNING_INTENT ->
                presentDeviceAdmission(original, LocalSetupDeviceAdmissionStage.APPROVAL_UNRESOLVED, LocalSetupDeviceAdmissionOutcome.STORAGE_UNCERTAIN)
            else -> presentDeviceAdmission(original, if (original.headAttemptEntered) LocalSetupDeviceAdmissionStage.APPROVAL_UNRESOLVED
                else LocalSetupDeviceAdmissionStage.TARGET_NOT_READY)
        }
    }

    private suspend fun readDeviceAdmissionAction(target: Target, client: LocalSetupClient,
        original: LocalSetupDeviceAdmissionOriginal, reference: LocalDevicePairingReference): Boolean {
        val read = client.reviewDevicePairing(reference.originalActionId)
        if (!deviceAdmissionOwnerCurrent(target, client)) return false
        return when (read) {
            is LocalCompanyAuthorityResult.Value -> {
                original.retainReference(reference)
                original.retainAction(read.value)
                requireOriginal(read.value.phase == reference.phase)
                if (!isSdkBoundDeviceAdmissionOriginal(target, read.value)) {
                    presentDeviceAdmission(original, LocalSetupDeviceAdmissionStage.TARGET_NOT_READY)
                    false // A legacy/missing association never gains V2 effect eligibility.
                } else {
                    original.retainRecord(requireDeviceAdmissionJournal().retainOriginal(deviceAdmissionContext(target), read.value))
                    deviceAdmissionOwnerCurrent(target, client)
                }
            }
            is LocalCompanyAuthorityResult.Unavailable -> { deviceAdmissionAuthorityFailure(read, original); false }
        }
    }

    private fun isSdkBoundDeviceAdmissionOriginal(target: Target, action: LocalDevicePairingAction): Boolean {
        val requested = action.requested; val coordinates = target.context.materialAtOpen.coordinates
        return requested.sdkIdentity != null && requested.purpose == LocalDevicePairingProfileV2.PURPOSE &&
            requested.request.scope == coordinates.scope && requested.admission.scope == coordinates.scope &&
            requested.admission.admitted && requested.admission.revision.value == 0L &&
            requested.admission.deviceId != coordinates.deviceId && action.challenge.cohostDeviceId == coordinates.deviceId
    }

    private suspend fun lookupDeviceAdmissionOutcome(target: Target, client: LocalSetupClient,
        original: LocalSetupDeviceAdmissionOriginal) {
        val record = requireNotNull(original.record)
        if (!deviceAdmissionOwnerCurrent(target, client)) return
        original.confirmedTerminal = null
        target.confirmedDeviceAdmissionTerminals.remove(original.actionId)
        val unresolvedStage = if (!original.admissionAttemptEntered && record.terminal == null &&
            original.phase == LocalDevicePairingPhase.APPROVED && original.approval != null)
            LocalSetupDeviceAdmissionStage.APPROVED else LocalSetupDeviceAdmissionStage.ADMISSION_UNRESOLVED
        presentDeviceAdmission(original, unresolvedStage)
        val result = client.lookupOriginalDeviceAdmission(original.actionId)
        if (!deviceAdmissionOwnerCurrent(target, client)) return
        when (result) {
            is LocalProvisioningStatus.Applied -> {
                requireOriginal(result.eventId == original.requestId)
                original.retainRecord(requireDeviceAdmissionJournal().retainApplied(record.identity, result.eventId))
                original.confirmedTerminal = LocalDeviceAdmissionIntents.Terminal.APPLIED
                target.confirmedDeviceAdmissionTerminals.add(original.actionId)
                presentDeviceAdmission(original, LocalSetupDeviceAdmissionStage.APPLIED, LocalSetupDeviceAdmissionOutcome.APPLIED, result.eventId)
            }
            LocalProvisioningStatus.NotRecorded -> {
                // Only this actual native/protected settled result proves known no effect.
                original.retainRecord(requireDeviceAdmissionJournal().retainKnownNoEffect(record.identity))
                original.confirmedTerminal = LocalDeviceAdmissionIntents.Terminal.KNOWN_NO_EFFECT
                target.confirmedDeviceAdmissionTerminals.add(original.actionId)
                presentDeviceAdmission(original, LocalSetupDeviceAdmissionStage.KNOWN_NO_EFFECT, LocalSetupDeviceAdmissionOutcome.KNOWN_NO_EFFECT)
            }
            else -> presentDeviceAdmission(original, unresolvedStage, when (result) {
                LocalProvisioningStatus.Denied -> LocalSetupDeviceAdmissionOutcome.DENIED
                LocalProvisioningStatus.Unavailable -> LocalSetupDeviceAdmissionOutcome.UNAVAILABLE
                LocalProvisioningStatus.StorageUncertain -> LocalSetupDeviceAdmissionOutcome.STORAGE_UNCERTAIN
                LocalProvisioningStatus.Busy -> LocalSetupDeviceAdmissionOutcome.BUSY
                is LocalProvisioningStatus.Applied, LocalProvisioningStatus.NotRecorded -> error("setup.device-original-lookup-required")
            })
        }
    }

    private fun deviceAdmissionUnresolvedStage(original: LocalSetupDeviceAdmissionOriginal?): LocalSetupDeviceAdmissionStage = when {
        original?.admissionAttemptEntered == true || original?.record?.terminal != null -> LocalSetupDeviceAdmissionStage.ADMISSION_UNRESOLVED
        original?.headAttemptEntered == true || original?.phase in UNRESOLVED_DEVICE_PAIRING_PHASES -> LocalSetupDeviceAdmissionStage.APPROVAL_UNRESOLVED
        else -> LocalSetupDeviceAdmissionStage.ORIGINAL_UNAVAILABLE
    }

    private fun presentDeviceAdmission(original: LocalSetupDeviceAdmissionOriginal, stage: LocalSetupDeviceAdmissionStage,
        outcome: LocalSetupDeviceAdmissionOutcome? = null, eventId: DomainId? = null) = change {
        it.copy(phase = LocalSetupPhase.DEVICE_ADMISSION,
            deviceAdmissions = deviceAdmissionList(it.deviceAdmissions, original),
            deviceAdmission = original.projection(stage, outcome, eventId), problem = null)
    }

    private fun deviceAdmissionList(list: LocalSetupDeviceAdmissions?, original: LocalSetupDeviceAdmissionOriginal): LocalSetupDeviceAdmissions? =
        list?.copy(unresolvedActionId = if (original.unresolvedAttempt || original.phase in UNRESOLVED_DEVICE_PAIRING_PHASES ||
                list.originals.none { it.originalActionId == original.actionId }) original.actionId
            else list.unresolvedActionId?.takeIf { it != original.actionId })

    private fun deviceAdmissionAuthorityFailure(result: LocalCompanyAuthorityResult.Unavailable,
        original: LocalSetupDeviceAdmissionOriginal?) = change {
        it.copy(phase = LocalSetupPhase.DEVICE_ADMISSION, problem = authorityProblem(result),
            deviceAdmissions = original?.let { selected -> deviceAdmissionList(it.deviceAdmissions, selected) } ?: it.deviceAdmissions,
            deviceAdmission = original?.projection(deviceAdmissionUnresolvedStage(original), LocalSetupDeviceAdmissionOutcome.UNAVAILABLE))
    }

    private fun requirePolicyTarget(): Target = requireTarget().also { target ->
        requireOriginal(policyOwnerCurrent(target, target.localClient))
    }

    private fun policyOwnerCurrent(target: Target, client: LocalSetupClient?): Boolean = withSetupLock(gate) {
        val current = !retiring && native === target && target.localClient === client && client?.isCurrent() == true &&
            view.localClientConnected && target.master?.mode?.value == LocalMasterLifecycleMode.OPERATIONAL
        if (!current && !retiring && native === target) {
            // Retain native work and original data privately. A stale owner's late answer
            // cannot publish another review, an absence claim or successful activation.
            view = view.copy(locked = true, localClientConnected = false,
                localClientConnectionAvailable = false, problem = LocalSetupProblem.RUNTIME_PUBLICATION_HELD,
                employeePolicy = view.employeePolicy?.copy(stage = LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED,
                    outcome = null, eventId = null))
            publishLocked()
        }
        current
    }

    private suspend fun policyOriginals(target: Target, client: LocalSetupClient): List<LocalEmployeePolicyActivationReference>? {
        if (!policyOwnerCurrent(target, client)) return null
        val read = client.retainedEmployeePolicies()
        if (!policyOwnerCurrent(target, client)) return null
        return when (read) {
            is LocalCompanyAuthorityResult.Value -> read.value.also { originals ->
                requireOriginal(originals.map { it.originalActionId }.distinct().size == originals.size &&
                    originals.map { it.originalRequestId }.distinct().size == originals.size)
            }
            is LocalCompanyAuthorityResult.Unavailable -> { policyAuthorityFailure(read); null }
        }
    }

    private fun policyReference(original: LocalSetupEmployeePolicyOriginal,
        originals: List<LocalEmployeePolicyActivationReference>): LocalEmployeePolicyActivationReference? =
        originals.singleOrNull { it.originalRequestId == original.requestId }?.also { reference ->
            requireOriginal((original.actionId == null || original.actionId == reference.originalActionId) &&
                (original.actorId == null || original.actorId == reference.actorId))
        }

    private suspend fun inspectPolicyOriginal(target: Target, client: LocalSetupClient, requestId: DomainId,
        suppliedOriginals: List<LocalEmployeePolicyActivationReference>? = null) {
        val originals = suppliedOriginals ?: policyOriginals(target, client) ?: return
        if (!policyOwnerCurrent(target, client)) return
        val reference = originals.singleOrNull { it.originalRequestId == requestId }
        val retained = target.employeePolicyOriginal
        requireOriginal(retained == null || retained.requestId == requestId)
        val original = retained ?: reference?.let {
            LocalSetupEmployeePolicyOriginal(it.originalRequestId, target.context.materialAtOpen.coordinates.scope, it.actorId)
                .also { selected -> selected.actionId = it.originalActionId; selected.preparationAttempted = true }
        } ?: throw OriginalRequired()
        target.employeePolicyOriginal = original
        if (reference == null) {
            presentPolicy(original, if (!original.preparationAttempted) LocalSetupEmployeePolicyStage.DEFINITION
                else if (original.actionId == null) LocalSetupEmployeePolicyStage.PREPARATION_UNRESOLVED
                else LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED)
            if (original.preparationAttempted) change { it.copy(problem = LocalSetupProblem.STATE_UNCERTAIN) }
            return // Missing reference never re-prepares or allocates another request.
        }
        policyReference(original, originals)
        original.phase = reference.phase
        change { it.copy(employeePolicies = LocalSetupEmployeePolicies(originals, false),
            employeePolicy = original.projection(LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED), problem = null) }
        when (reference.phase) {
            LocalEmployeePolicyActivationPhase.PREPARED -> {
                val read = client.reviewEmployeePolicy(reference.originalActionId)
                if (read is LocalCompanyAuthorityResult.Value) retainPolicyAction(original, read.value)
                if (!policyOwnerCurrent(target, client)) return
                when (read) {
                    is LocalCompanyAuthorityResult.Value -> presentPolicy(original,
                        if (original.activationAttempted) LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED
                        else LocalSetupEmployeePolicyStage.REVIEW)
                    is LocalCompanyAuthorityResult.Unavailable -> policyAuthorityFailure(read)
                }
            }
            LocalEmployeePolicyActivationPhase.SIGNING_INTENT -> presentPolicy(original,
                LocalSetupEmployeePolicyStage.APPROVAL_UNRESOLVED, LocalSetupEmployeePolicyOutcome.STORAGE_UNCERTAIN)
            LocalEmployeePolicyActivationPhase.APPROVED -> {
                val read = client.recoverEmployeePolicyApproval(reference.originalActionId)
                if (read is LocalCompanyAuthorityResult.Value) retainPolicyApproval(original, read.value)
                if (!policyOwnerCurrent(target, client)) return
                when (read) {
                    is LocalCompanyAuthorityResult.Value -> {
                        // Successful native APPROVED recovery excludes pending dispatch/frame work
                        // and rechecks the original head lifetime and preparation cut. Admission
                        // Busy alone proves none of that. Re-arm only this fully joined original;
                        // the rotated review still needs a separate explicit activation decision.
                        original.activationAttempted = false
                        presentPolicy(original, LocalSetupEmployeePolicyStage.APPROVED)
                    }
                    is LocalCompanyAuthorityResult.Unavailable -> policyAuthorityFailure(read)
                }
            }
            LocalEmployeePolicyActivationPhase.DISPATCH_INTENT, LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT,
            LocalEmployeePolicyActivationPhase.CONSUMED, LocalEmployeePolicyActivationPhase.REJECTED -> {
                original.activationAttempted = true
                val read = client.recoverEmployeePolicyConnection(reference.originalActionId)
                if (read is LocalCompanyAuthorityResult.Value) retainPolicyApproval(original, read.value)
                if (!policyOwnerCurrent(target, client)) return
                when (read) {
                    is LocalCompanyAuthorityResult.Value -> {
                        presentPolicy(original, LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED)
                        lookupPolicyOutcome(target, client, original)
                    }
                    is LocalCompanyAuthorityResult.Unavailable -> policyAuthorityFailure(read)
                }
            }
        }
    }

    private fun retainPolicyAction(original: LocalSetupEmployeePolicyOriginal, action: LocalEmployeePolicyActivationAction) {
        requireOriginal((original.actionId == null || original.actionId == action.originalActionId) &&
            original.matches(action.requested) && original.approval == null)
        original.actionId = action.originalActionId
        original.requested = action.requested
        original.action = action
        original.phase = LocalEmployeePolicyActivationPhase.PREPARED
        original.companyName = action.companyName
        original.headName = action.headName
        original.headLogin = action.headLogin
        original.rootFingerprint = action.rootFingerprint
    }

    private fun retainPolicyApproval(original: LocalSetupEmployeePolicyOriginal,
        approval: LocalEmployeePolicyActivationAuthorityReceipt) {
        requireOriginal(original.actionId == approval.originalActionId && original.matches(approval.requested) &&
            (original.rootFingerprint == null || original.rootFingerprint == approval.rootFingerprint) &&
            (original.approval == null || original.approval?.authorityDigest == approval.authorityDigest))
        original.requested = approval.requested
        original.approval = approval
        original.rootFingerprint = approval.rootFingerprint
    }

    private suspend fun lookupPolicyOutcome(target: Target, client: LocalSetupClient, original: LocalSetupEmployeePolicyOriginal) {
        val approval = requireNotNull(original.approval)
        requireOriginal(original.matches(approval.requested) && original.actionId == approval.originalActionId)
        if (!policyOwnerCurrent(target, client)) return
        val result = client.lookupEmployeePolicy(approval, requireNotNull(original.requested).nextPolicy)
        if (!policyOwnerCurrent(target, client)) return
        when (result) {
            is LocalProvisioningStatus.Applied -> presentPolicy(original, LocalSetupEmployeePolicyStage.APPLIED,
                LocalSetupEmployeePolicyOutcome.APPLIED, result.eventId)
            LocalProvisioningStatus.NotRecorded -> presentPolicy(original,
                if (original.phase == LocalEmployeePolicyActivationPhase.REJECTED) LocalSetupEmployeePolicyStage.NOT_RECORDED
                else LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED, LocalSetupEmployeePolicyOutcome.NOT_RECORDED)
            LocalProvisioningStatus.Denied -> presentPolicy(original, LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED,
                LocalSetupEmployeePolicyOutcome.DENIED)
            LocalProvisioningStatus.Unavailable -> presentPolicy(original, LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED,
                LocalSetupEmployeePolicyOutcome.UNAVAILABLE)
            LocalProvisioningStatus.StorageUncertain -> presentPolicy(original, LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED,
                LocalSetupEmployeePolicyOutcome.STORAGE_UNCERTAIN)
            LocalProvisioningStatus.Busy -> presentPolicy(original, LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED,
                LocalSetupEmployeePolicyOutcome.BUSY)
        }
    }

    private fun presentPolicy(original: LocalSetupEmployeePolicyOriginal, stage: LocalSetupEmployeePolicyStage,
        outcome: LocalSetupEmployeePolicyOutcome? = null, eventId: DomainId? = null) = change {
        it.copy(phase = LocalSetupPhase.EMPLOYEE_POLICY,
            employeePolicy = original.projection(stage, outcome, eventId), problem = null)
    }

    private fun policyAuthorityFailure(result: LocalCompanyAuthorityResult.Unavailable) = change {
        it.copy(phase = LocalSetupPhase.EMPLOYEE_POLICY, problem = authorityProblem(result),
            employeePolicy = it.employeePolicy?.let { original -> original.copy(reviewId = DomainId.random(), stage = when (original.stage) {
                LocalSetupEmployeePolicyStage.PREPARATION_UNRESOLVED -> LocalSetupEmployeePolicyStage.PREPARATION_UNRESOLVED
                LocalSetupEmployeePolicyStage.APPROVAL_UNRESOLVED -> LocalSetupEmployeePolicyStage.APPROVAL_UNRESOLVED
                LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED -> LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED
                else -> LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED
            }, outcome = null, eventId = null) })
    }

    private fun requireEmployeeTarget(): Target = requireTarget().also { target ->
        requireOriginal(target.localClient?.isCurrent() == true && view.localClientConnected &&
            target.master?.mode?.value == LocalMasterLifecycleMode.OPERATIONAL)
    }

    private fun employeeRecords(target: Target): List<LocalEmployeeSetupIntents.Record> {
        val coordinates = target.context.materialAtOpen.coordinates
        return employeeSetupIntents.records(coordinates.installationId, coordinates.scope, coordinates.deviceId)
    }

    private suspend fun employeeOriginals(client: LocalSetupClient): List<LocalEmployeeEnrollmentReference>? =
        when (val read = client.retainedEmployeeEnrollments()) {
            is LocalCompanyAuthorityResult.Value -> read.value
            is LocalCompanyAuthorityResult.Unavailable -> { employeeAuthorityFailure(read); null }
        }

    private suspend fun inspectEmployeeOriginal(target: Target, client: LocalSetupClient, requestId: DomainId,
        suppliedOriginals: List<LocalEmployeeEnrollmentReference>? = null) {
        target.employeeJournalOpenedRequestId = null
        val originals = suppliedOriginals ?: employeeOriginals(client) ?: return
        val reference = originals.singleOrNull { it.originalRequestId == requestId }
        var record = employeeRecords(target).singleOrNull { it.identity.requestId == requestId }
        if (reference == null) {
            val existing = requireNotNull(record)
            target.employeeIdentity = existing.identity
            target.employeeAction = null; target.employeeConnection = null
            change { it.copy(employeeEnrollment = employeeView(existing.identity, if (!existing.preparing && existing.actionId == null)
                LocalSetupEmployeeEnrollmentStage.DEFINITION else LocalSetupEmployeeEnrollmentStage.ORIGINAL_UNRESOLVED,
                existing.actionId), problem = if (existing.preparing) LocalSetupProblem.STATE_UNCERTAIN else null) }
            return // Absence never permits automatic re-prepare of a previously attempted original.
        }
        record?.let { requireOriginal(it.identity.actorId == reference.actorId &&
            (it.actionId == null || it.actionId == reference.originalActionId)) }
        when (reference.phase) {
            LocalEmployeeEnrollmentPhase.PREPARED -> when (val reviewed = client.reviewEmployeeEnrollment(reference.originalActionId)) {
                is LocalCompanyAuthorityResult.Value -> {
                    val action = reviewed.value
                    val coordinates = target.context.materialAtOpen.coordinates
                    val identity = record?.identity ?: LocalEmployeeSetupIntents.Identity(coordinates.installationId,
                        coordinates.scope, coordinates.deviceId, reference.originalRequestId, reference.actorId)
                    requireOriginal(action.originalActionId == reference.originalActionId && action.requested.request.requestId == requestId)
                    employeeSetupIntents.retainNativeOriginal(identity, action)
                    target.employeeIdentity = identity; target.employeeAction = action; target.employeeConnection = null
                    presentEmployeeReview(identity, action)
                }
                is LocalCompanyAuthorityResult.Unavailable -> employeeAuthorityFailure(reviewed)
            }
            LocalEmployeeEnrollmentPhase.SIGNING_INTENT -> {
                val identity = requireNotNull(record).identity
                target.employeeIdentity = identity; target.employeeAction = null; target.employeeConnection = null
                change { it.copy(employeeEnrollment = employeeView(identity, LocalSetupEmployeeEnrollmentStage.APPROVAL_UNRESOLVED,
                    reference.originalActionId, reference.phase), problem = LocalSetupProblem.STATE_UNCERTAIN) }
            }
            LocalEmployeeEnrollmentPhase.APPROVED -> when (val recovered = client.recoverEmployeeEnrollmentApproval(reference.originalActionId)) {
                is LocalCompanyAuthorityResult.Value -> {
                    val existing = requireNotNull(record) // UI state alone never recreates the native original.
                    target.employeeIdentity = existing.identity
                    presentEmployeeConnection(target, recovered.value, reference.phase)
                    if (existing.passwordAttempted) presentEmployeeAccountResult(target, client.lookupEmployeeEnrollment(requestId))
                }
                is LocalCompanyAuthorityResult.Unavailable -> employeeAuthorityFailure(recovered)
            }
            LocalEmployeeEnrollmentPhase.DISPATCH_INTENT, LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT,
            LocalEmployeeEnrollmentPhase.CONSUMED, LocalEmployeeEnrollmentPhase.REJECTED -> {
                val existing = requireNotNull(record)
                target.employeeIdentity = existing.identity
                target.employeeAction = null; target.employeeConnection = null
                // The runtime restores the exact original lookup publication, using only the
                // genuine warm handle or fully admitted native cold history. Late-phase
                // recovery never reapproves the head or dispatches an employee password.
                val connection = when (val recovered = client.recoverEmployeeEnrollmentConnection(reference.originalActionId)) {
                    is LocalCompanyAuthorityResult.Value -> recovered.value
                    is LocalCompanyAuthorityResult.Unavailable -> {
                        change { it.copy(employeeEnrollment = employeeView(existing.identity,
                            LocalSetupEmployeeEnrollmentStage.ORIGINAL_UNRESOLVED, reference.originalActionId, reference.phase),
                            problem = authorityProblem(recovered)) }
                        return
                    }
                }
                if (!services.ownsEmployeeConnection(connection) || connection.originalRequestId != requestId) {
                    change { it.copy(employeeEnrollment = employeeView(existing.identity,
                        LocalSetupEmployeeEnrollmentStage.ORIGINAL_UNRESOLVED, reference.originalActionId, reference.phase),
                        problem = LocalSetupProblem.STATE_UNCERTAIN) }
                    return
                }
                presentEmployeeConnection(target, connection, reference.phase)
                presentEmployeeAccountResult(target, client.lookupEmployeeEnrollment(requestId))
            }
        }
    }

    private fun presentEmployeeReview(identity: LocalEmployeeSetupIntents.Identity, action: LocalEmployeeEnrollmentAction) {
        requireOriginal(action.requested.request.requestId == identity.requestId && action.requested.request.targetId == identity.actorId &&
            action.requested.request.scope == identity.scope && action.requested.definition.admittedDeviceIds == setOf(identity.deviceId))
        change { it.copy(employeeEnrollment = employeeView(identity, LocalSetupEmployeeEnrollmentStage.REVIEW,
            action.originalActionId, LocalEmployeeEnrollmentPhase.PREPARED).copy(requested = action.requested,
            companyName = action.companyName, headName = action.headName, headLogin = action.headLogin,
            rootFingerprint = action.rootFingerprint), problem = null) }
    }

    private fun presentEmployeeConnection(target: Target, connection: LocalSetupEmployeeConnection,
        phase: LocalEmployeeEnrollmentPhase) {
        val identity = requireNotNull(target.employeeIdentity)
        val record = employeeSetupIntents.readOriginal(identity)
        requireOriginal(services.ownsEmployeeConnection(connection) && connection.originalRequestId == identity.requestId &&
            connection.actorId == identity.actorId && connection.originalActionId == record.actionId &&
            connection.requested.definitionDigest.value == record.definitionDigest && connection.requested.request.scope == identity.scope &&
            connection.requested.definition.admittedDeviceIds == setOf(identity.deviceId))
        target.employeeConnection = connection
        val previous = view.employeeEnrollment?.takeIf { it.originalRequestId == identity.requestId }
        change { it.copy(employeeEnrollment = (previous ?: employeeView(identity, LocalSetupEmployeeEnrollmentStage.APPROVED))
            .copy(reviewId = DomainId.random(), originalActionId = connection.originalActionId, nativePhase = phase,
                requested = connection.requested, rootFingerprint = connection.approval.rootFingerprint,
                stage = if (record.passwordAttempted || phase != LocalEmployeeEnrollmentPhase.APPROVED)
                    LocalSetupEmployeeEnrollmentStage.ENROLLMENT_UNRESOLVED else LocalSetupEmployeeEnrollmentStage.APPROVED,
                serviceProblem = null), problem = null) }
    }

    private fun presentEmployeeAccountResult(target: Target, result: LocalEnrollmentResult) {
        val identity = requireNotNull(target.employeeIdentity)
        val record = employeeSetupIntents.readOriginal(identity)
        val review = requireNotNull(view.employeeEnrollment)
        change { it.copy(employeeEnrollment = when (result) {
            LocalEnrollmentResult.Created -> review.copy(stage = LocalSetupEmployeeEnrollmentStage.ACCOUNT_CREATED,
                nativePhase = LocalEmployeeEnrollmentPhase.CONSUMED, firstJournalEnrollmentAvailable = !record.journalAttempted && !record.complete,
                serviceProblem = null)
            LocalEnrollmentResult.Unresolved -> review.copy(stage = LocalSetupEmployeeEnrollmentStage.ENROLLMENT_UNRESOLVED)
            is LocalEnrollmentResult.Rejected -> review.copy(stage = if (review.nativePhase == LocalEmployeeEnrollmentPhase.REJECTED)
                LocalSetupEmployeeEnrollmentStage.REJECTED else LocalSetupEmployeeEnrollmentStage.ENROLLMENT_UNRESOLVED,
                serviceProblem = result.problem)
        }, problem = null) }
    }

    private fun employeeView(identity: LocalEmployeeSetupIntents.Identity, stage: LocalSetupEmployeeEnrollmentStage,
        actionId: DomainId? = null, phase: LocalEmployeeEnrollmentPhase? = null): LocalSetupEmployeeEnrollmentView =
        LocalSetupEmployeeEnrollmentView(DomainId.random(), identity.requestId, identity.actorId, identity.scope,
            setOf(identity.deviceId), stage, originalActionId = actionId, nativePhase = phase)

    private fun employeeAuthorityFailure(result: LocalCompanyAuthorityResult.Unavailable) {
        change { it.copy(phase = LocalSetupPhase.EMPLOYEE_ENROLLMENT, problem = authorityProblem(result)) }
    }

    private suspend fun presentInstallationResult(result: LocalMasterInstallationOutcome) {
        when (result) {
            is LocalMasterInstallationOutcome.Installed -> {
                val master = requireNotNull(requireTarget().master)
                val mayEnroll = master.mode.value == LocalMasterLifecycleMode.OPERATIONAL &&
                    master.permitsFirstClientEnrollment()
                change { it.copy(
                phase = LocalSetupPhase.INSTALLATION_RETAINED, receipt = receiptView(result.receipt),
                definitionReview = null, firstAccountHeadLogin = null, localClientConnected = false,
                firstClientEnrollmentAvailable = mayEnroll,
                firstClientEnrollmentContinuationAvailable = mayEnroll,
                localClientConnectionAvailable = master.mode.value == LocalMasterLifecycleMode.OPERATIONAL,
                problem = LocalSetupProblem.RUNTIME_PUBLICATION_HELD) }
            }
            LocalMasterInstallationOutcome.AcknowledgementPending -> presentPendingAcknowledgement()
            LocalMasterInstallationOutcome.Busy -> change { it.copy(problem = LocalSetupProblem.BUSY) }
            LocalMasterInstallationOutcome.Unresolved -> fail(LocalSetupProblem.STATE_UNCERTAIN)
        }
    }

    private fun presentPendingAcknowledgement() = change { it.copy(
        phase = LocalSetupPhase.INSTALLATION_ACK_PENDING, receipt = null, definitionReview = null,
        firstAccountHeadLogin = null, problem = null) }

    private suspend fun openOriginalInstallation(keepPendingOwner: Boolean = true,
        expectedLaunch: LocalMasterLaunchRecord.Read.Running? = null) {
        withSetupLock(gate) { requireOriginal(lanAttachment == null) }
        val previous = withSetupLock(gate) { native }
        if (keepPendingOwner && (previous as? Target)?.master?.mode?.value == LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING) {
            // Ordinary entry preserves the genuine completion. Only the explicit close/rescan
            // action discards this owner, and only after its actual close is confirmed.
            presentPendingAcknowledgement()
            return
        }
        previous?.let { closeNativeOwner(it) }
        if (!continueWork()) return
        // Always close the preparation/P3 owner, then acquire the original existing-only
        // context. A creating preparation context cannot be relabeled as D7 custody.
        val target = acquireTarget(preparation = false) ?: return
        requireOriginal(expectedLaunch == null || expectedLaunch.matches(target.context.materialAtOpen.coordinates))
        if (!continueWork() || !readNative(target, verifyOriginalBinding = expectedLaunch != null)) return
        beginAttachment(target)
        // A throw or Unresolved result settles the invocation but exposes no transfer-status
        // fact. The returned target is HELD_TRANSFER_UNKNOWN: it may still own the native
        // lifetime or may have transferred to an unreturned Master. Never infer absence and
        // never fabricate a target-only close or a Master handle.
        val opened = try {
            services.openMaster(target.context)
        } catch (failure: Throwable) {
            withSetupLock(gate) { target.settleFailedMasterAttachment() }
            throw failure
        }
        when (opened) {
            is LocalSetupMasterOpenResult.Opened -> {
                target.attachMaster(opened.master)
                retainNative(target)
            }
            is LocalSetupMasterOpenResult.Unresolved -> {
                withSetupLock(gate) { target.settleFailedMasterAttachment() }
                acquisitionFailure(opened.reason)
                return
            }
        }
        if (!continueWork()) return
        val master = requireNotNull(target.master)
        if (expectedLaunch != null && master.mode.value != LocalMasterLifecycleMode.OPERATIONAL) {
            fail(LocalSetupProblem.RUNTIME_PUBLICATION_HELD)
            return // No first-account creation, acknowledgement, journal repair or role change on reopening.
        }
        when (master.mode.value) {
            LocalMasterLifecycleMode.ALLOCATION_ONLY -> {
                val original = master.readOriginalSetup()
                val definition = original.definition
                target.definition = definition
                change { it.copy(phase = if (definition == null) LocalSetupPhase.FIRST_ACCOUNT_DETAILS
                    else LocalSetupPhase.FIRST_ACCOUNT_REVIEW,
                    firstAccountHeadLogin = original.company.headLogin,
                    definitionReview = definition?.let { saved ->
                        LocalSetupDefinitionReview(DomainId.random(), saved, original.company.headLogin) },
                    receipt = null, problem = null) }
            }
            LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD, LocalMasterLifecycleMode.OPERATIONAL,
            LocalMasterLifecycleMode.POLICY_HELD -> {
                val original = master.readOriginalSetup()
                // C9: the automatic existing-only reconnect accepts either/both nullable
                // definition and receipt after native validation. Explicit setup entry keeps
                // its original complete-receipt requirement.
                val receipt = original.receipt
                if (expectedLaunch == null) requireNotNull(receipt)
                val mayEnroll = master.mode.value == LocalMasterLifecycleMode.OPERATIONAL &&
                    master.permitsFirstClientEnrollment()
                change { it.copy(phase = LocalSetupPhase.INSTALLATION_RETAINED,
                    receipt = receipt?.let(::receiptView),
                    firstAccountHeadLogin = null, definitionReview = null, localClientConnected = false,
                    firstClientEnrollmentAvailable = mayEnroll,
                    firstClientEnrollmentContinuationAvailable = mayEnroll,
                    localClientConnectionAvailable = master.mode.value == LocalMasterLifecycleMode.OPERATIONAL,
                    problem = LocalSetupProblem.RUNTIME_PUBLICATION_HELD) }
            }
            LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING -> presentPendingAcknowledgement()
            else -> fail(LocalSetupProblem.STATE_UNCERTAIN)
        }
    }

    override fun refreshOriginalStatus() {
        start(LocalSetupWork.READING_ORIGINAL_RECEIPT, REFRESH_PHASES) {
            val original = native
            requireOriginal(original != null)
            operation {
                when (original) {
                    is Provisioner -> inspectCompany(original)
                    is Target -> if (lanAttachment != null) readRetainedLanMaster(original)
                        else if (original.master != null && original.localClient != null &&
                            view.localClientConnected) refreshConnectedOriginal(original)
                        else openOriginalInstallation(keepPendingOwner = false)
                    is FreshDeviceTarget -> readFreshTarget(original, original.original)
                    null -> error("setup.original-owner-required")
                }
            }
        }
    }

    /**
     * Explicit manual refresh of an already connected existing-only original. The action is
     * admitted only after the genuine resumePresentation() user action (a locked automatic
     * reconnect is refused by [start]); it never unlocks presentation itself.
     *
     * The positive verification core is a fresh canonical RUN read, a fresh retained-binding
     * read agreeing on all twelve coordinates, true currentness on the Client actually presented
     * as connected, and a fresh record recheck. The production mode qualified here is
     * REUSE_CURRENT_PUBLICATION: the exact published Client/Master/publication is retained with
     * no acquisition, close, withdrawal or second publication. A failed core/target/lifecycle
     * observation refuses without destroying the working connection. The separately permitted
     * confirmed close/reopen mode is not selected by this implementation. Neither path writes a
     * launch hint, creates setup/authority or retires the branch.
     */
    private suspend fun refreshConnectedOriginal(target: Target) {
        try {
            refreshConnectedOriginalCore(target)
        } catch (failure: Throwable) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            if (!retiring) refreshRefusal(LocalSetupProblem.STATE_UNCERTAIN)
        }
    }

    private suspend fun refreshConnectedOriginalCore(target: Target) {
        val master = target.master ?: return
        if (!refreshRecord(target)) return
        val verified = try {
            readNative(target, expected = target.context.materialAtOpen.coordinates,
                verifyOriginalBinding = true)
        } catch (_: OriginalRequired) {
            // The actual readback disagreed with the acquired material/binding. This is a
            // real refusal, not a guessed taxonomy or a retry.
            refreshRefusal(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED)
            return
        }
        if (!verified) {
            // Preserve the actual mapped native reason from readNative; do not overwrite it
            // with a generic refresh outcome.
            refreshRefusal(view.problem ?: LocalSetupProblem.RUNTIME_PUBLICATION_HELD)
            return
        }
        if (!continueWork()) return
        // Recheck the canonical retained record after the (possibly held) native verification,
        // immediately before any positive claim or replacement publication.
        if (!refreshRecord(target)) return
        if (!continueWork()) return
        val presented = target.localClient
        if (presented == null || !view.localClientConnected) {
            refreshRefusal(LocalSetupProblem.RUNTIME_PUBLICATION_HELD)
            return
        }
        val current = try { presented.isCurrent() } catch (_: Throwable) { false }
        if (!current) {
            // Keep the old lifecycle/publication accounting but make no positive connected
            // claim for a stale or currentness-unknown Client.
            refreshRefusal(LocalSetupProblem.RUNTIME_PUBLICATION_HELD)
            return
        }
        if (master.mode.value != LocalMasterLifecycleMode.OPERATIONAL) {
            refreshRefusal(LocalSetupProblem.RUNTIME_PUBLICATION_HELD)
            return
        }
        // Optional fresh setup read. Nullable definition/receipt are valid C9 inputs after
        // native identity validation and are never repaired or fabricated.
        master.readOriginalSetup()
        if (!continueWork()) return
        finalizeRefreshPositive(target, master, presented)
    }

    /**
     * The final positive state transition is atomic under the setup gate. The optional setup
     * read is a suspension point; a concurrent lifecycle withdrawal, mode change, owner
     * replacement, currentness change or canonical-record change must win over a stale
     * positive claim. The refresh never resurrects a withdrawn publication or recreates one.
     */
    private fun finalizeRefreshPositive(target: Target, master: LocalSetupMaster,
        presented: LocalSetupClient): Boolean = withSetupLock(gate) {
        if (retiring) return@withSetupLock false
        val recorded = (launchRecord.read() as? LocalMasterLaunchRecord.Read.Running)
            ?.matches(target.context.materialAtOpen.coordinates) == true
        val current = try { presented.isCurrent() } catch (_: Throwable) { false }
        val admitted = native === target && target.localClient === presented &&
            view.localClientConnected && current && recorded &&
            master.mode.value == LocalMasterLifecycleMode.OPERATIONAL
        if (!admitted) {
            view = view.copy(
                phase = LocalSetupPhase.ORIGINAL_UNRESOLVED,
                problem = if (!recorded) LocalSetupProblem.STATE_UNCERTAIN
                    else LocalSetupProblem.RUNTIME_PUBLICATION_HELD,
                native = null, receipt = null, firstAccountHeadLogin = null,
                definitionReview = null, localClientConnected = false,
                localClientConnectionAvailable = false,
                firstClientEnrollmentAvailable = false,
                firstClientEnrollmentContinuationAvailable = false)
            publishLocked()
            false
        } else {
            view = view.copy(phase = LocalSetupPhase.INSTALLATION_RETAINED,
                receipt = null, firstAccountHeadLogin = null, native = null,
                definitionReview = null, localClientConnected = true,
                firstClientEnrollmentAvailable = false,
                firstClientEnrollmentContinuationAvailable = false,
                localClientConnectionAvailable = true, problem = null)
            publishLocked()
            true
        }
    }

    private fun refreshRecord(target: Target): Boolean {
        return when (val read = launchRecord.read()) {
            is LocalMasterLaunchRecord.Read.Running -> {
                if (read.matches(target.context.materialAtOpen.coordinates)) true
                else {
                    refreshRefusal(LocalSetupProblem.STATE_UNCERTAIN)
                    false
                }
            }
            LocalMasterLaunchRecord.Read.Stopped -> {
                refreshRefusal(LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED)
                false
            }
            LocalMasterLaunchRecord.Read.Absent, LocalMasterLaunchRecord.Read.Unresolved -> {
                refreshRefusal(LocalSetupProblem.STATE_UNCERTAIN)
                false
            }
        }
    }

    private fun refreshRefusal(problem: LocalSetupProblem, connected: Boolean = false) = change {
        it.copy(phase = if (it.phase == LocalSetupPhase.CLOSED) it.phase else LocalSetupPhase.ORIGINAL_UNRESOLVED,
            problem = problem, native = null, receipt = null, firstAccountHeadLogin = null,
            definitionReview = null,
            localClientConnected = connected,
            localClientConnectionAvailable = false,
            firstClientEnrollmentAvailable = false,
            firstClientEnrollmentContinuationAvailable = false)
    }

    /** A status button cannot retire/reopen the Master bound to a terminal LAN selection. */
    private suspend fun readRetainedLanMaster(target: Target) {
        val master = target.master ?: throw OriginalRequired()
        val attachment = withSetupLock(gate) { lanAttachment as? LanHost } ?: throw OriginalRequired()
        requireOriginal(attachment.owner === target && attachment.master === master)
        val original = master.readOriginalSetup()
        withSetupLock(gate) {
            if (retiring || native !== target || target.master !== master || lanAttachment !== attachment) return@withSetupLock
            val operational = master.mode.value == LocalMasterLifecycleMode.OPERATIONAL
            view = view.copy(receipt = original.receipt?.let(::receiptView),
                localClientConnected = view.localClientConnected && operational,
                localClientConnectionAvailable = view.localClientConnectionAvailable && operational,
                firstClientEnrollmentAvailable = view.firstClientEnrollmentAvailable && operational,
                firstClientEnrollmentContinuationAvailable = view.firstClientEnrollmentContinuationAvailable && operational,
                problem = when {
                    attachment.status == LocalSetupLanStatus.UNKNOWN -> LocalSetupProblem.CLOSE_UNCONFIRMED
                    !operational -> LocalSetupProblem.RUNTIME_PUBLICATION_HELD
                    attachment.status == LocalSetupLanStatus.UNAVAILABLE -> LocalSetupProblem.NATIVE_UNAVAILABLE
                    else -> null
                })
            if (!operational && attachment.status in setOf(LocalSetupLanStatus.STARTING, LocalSetupLanStatus.LISTENING)) {
                attachment.status = LocalSetupLanStatus.UNAVAILABLE
                attachment.endpoint = null
            }
            publishLocked()
        }
    }

    override fun lockPresentation(): Unit = withSetupLock(gate) {
        view = view.copy(locked = true)
        publishLocked()
    }

    override fun resumePresentation(): Unit = withSetupLock(gate) {
        if (retiring || active != null || view.phase == LocalSetupPhase.CLOSED) return@withSetupLock
        if (view.phase == LocalSetupPhase.EMPLOYEE_POLICY) {
            val target = native as? Target ?: return@withSetupLock
            if (!policyOwnerCurrent(target, target.localClient)) return@withSetupLock
        }
        if (view.phase == LocalSetupPhase.DEVICE_ADMISSION) {
            val target = native as? Target ?: return@withSetupLock
            if (!deviceAdmissionOwnerCurrent(target, target.localClient)) return@withSetupLock
        }
        if (view.phase == LocalSetupPhase.FRESH_DEVICE_PAIRING && view.freshDevicePairing?.role == LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER) {
            val target = native as? Target ?: return@withSetupLock
            val client = target.localClient ?: return@withSetupLock
            val original = target.freshPairingOriginal ?: return@withSetupLock
            if (!freshMasterCurrent(target, client, original)) return@withSetupLock
        }
        // Native actions remain the originals. New presentation discriminators invalidate old
        // detached callbacks; native checks still own boot/deadline, credentials and continuity.
        view = view.copy(locked = false,
            companyReview = view.companyReview?.copy(reviewId = DomainId.random()),
            branchReview = view.branchReview?.copy(reviewId = DomainId.random()),
            definitionReview = view.definitionReview?.let { LocalSetupDefinitionReview(DomainId.random(), it.snapshot, it.headLogin) },
            employeeEnrollment = view.employeeEnrollment?.copy(reviewId = DomainId.random()),
            employeePolicy = view.employeePolicy?.copy(reviewId = DomainId.random()),
            deviceAdmission = view.deviceAdmission?.copy(reviewId = DomainId.random()),
            freshDevicePairing = view.freshDevicePairing?.copy(reviewId = DomainId.random()))
        publishLocked()
    }

    /**
     * Immediate unpublication/denial, then independent drain and actual native close. Cancelling
     * an Activity or a waiter cannot cancel this job. Failure retains this owner and native
     * references; the service must keep its process slot and must not publish a replacement.
     */
    public fun requestRetirement(intentional: Boolean = false): Deferred<Unit> {
        val result: CompletableDeferred<Unit>
        val admitted: Work?
        val captured: OwnedNative?
        withSetupLock(gate) {
            if (intentional) {
                try { launchRecord.retainStop() }
                catch (_: Exception) {
                    problemLocked(LocalSetupProblem.AUTOMATIC_REOPEN_UNAVAILABLE)
                    return CompletableDeferred<Unit>().also {
                        it.completeExceptionally(IllegalStateException("Local stop intent was not durably retained"))
                    }
                }
            }
            retirement?.let { return it }
            result = CompletableDeferred()
            retirement = result
            retiring = true
            retiringAt = try { elapsed() } catch (_: Exception) { null }
            admitted = active
            captured = native
            lanAttachment?.let {
                if (it is LanClient) try { withdrawLanAuthenticationLocked(it) }
                catch (_: Throwable) { view = view.copy(problem = LocalSetupProblem.CLOSE_UNCONFIRMED) }
                if (it.status != LocalSetupLanStatus.CLOSED && it.status != LocalSetupLanStatus.UNKNOWN)
                    it.status = LocalSetupLanStatus.CLOSING
                it.endpoint = null
            }
            // Keep the admitted work's watch through phase handoff and drain. An idle
            // fresh owner has the same bounded close observation, not a renewed challenge.
            retirementWatchMillis = admitted?.watchMillis ?: if (
                view.phase == LocalSetupPhase.FRESH_DEVICE_PAIRING || captured is FreshDeviceTarget
            ) FRESH_PAIRING_OPERATION_MILLIS else OPERATION_MILLIS
            view = view.copy(phase = LocalSetupPhase.CLOSING, work = LocalSetupWork.CLOSING_NATIVE_OWNER, locked = true)
            publishLocked() // No private projection remains before denial/close begins.
        }
        // The actual service unpublishes even when this path was entered by the watchdog or
        // by a failed phase transfer, not only by an Activity/service stop. Do not close or
        // release anything if that local lifecycle notification could not be completed.
        val unpublished = try { beforeRetirement(this, result); true }
        catch (_: Throwable) {
            withSetupLock(gate) { problemLocked(LocalSetupProblem.CLOSE_UNCONFIRMED) }
            result.completeExceptionally(IllegalStateException("Setup service unpublication remains unconfirmed"))
            false
        }
        try { captured?.denyTokenOnce() }
        catch (_: Throwable) { withSetupLock(gate) { problemLocked(LocalSetupProblem.CLOSE_UNCONFIRMED) } }
        // Deny already returned owners immediately, before waiting for admitted work. The
        // same holder may capture another exact owner during drain; cleanup denies that
        // newly returned handle separately without retrying any uncertain earlier handle.
        try { captured?.denyOwnedHandlesOnce() }
        catch (_: Throwable) { withSetupLock(gate) { problemLocked(LocalSetupProblem.CLOSE_UNCONFIRMED) } }
        if (!unpublished) return result
        scope.launch {
            try {
                admitted?.finished?.await()
                withSetupLock(gate) { lanAttachment }?.let { closeLanOwned(it) }
                val actual = withSetupLock(gate) { native }
                if (actual != null) closeNativeOwner(actual)
                withSetupLock(gate) {
                    check(!acquisitionCloseUnobserved && native == null &&
                        (lanAttachment == null || lanAttachment?.status == LocalSetupLanStatus.CLOSED))
                    foundingAction = null; branchAction = null; retainedPreparation = null; freshTargetOriginal = null
                    view = LocalSetupView(phase = LocalSetupPhase.CLOSED, locked = true)
                    publishLocked()
                }
                // Only this actual completion lets the service remove its retained process slot.
                result.complete(Unit)
                execution.stopWatching()
                scope.cancel()
            } catch (_: Throwable) {
                withSetupLock(gate) { problemLocked(LocalSetupProblem.CLOSE_UNCONFIRMED) }
                result.completeExceptionally(IllegalStateException("Setup native close remains unconfirmed"))
                // Never retry a possibly reused descriptor, cancel native work or kill cohost.
            }
        }
        return result
    }

    private suspend fun acquireProvisioner(originalOnly: Boolean): Provisioner? {
        // Construct and retain the cleanup holder BEFORE the factory can transfer ownership.
        // No wrapper/lambda/registry construction may intervene after its positive return.
        val owner = Provisioner()
        if (!reserveAcquisition(owner)) return null
        try {
            val result = services.openProvisioner(originalOnly)
            return when (result) {
                is LocalProvisionerOpenResult.Opened -> {
                    owner.capture(result.provisioner) // First: direct reference retention only.
                    retainNative(owner)
                    owner
                }
                is LocalProvisionerOpenResult.Unavailable -> { acquisitionFailure(result.reason); null }
            }
        } catch (failure: Throwable) {
            // The registered holder, not the unwinding stack, owns the exact returned handle.
            // Service null-publication precedes denial, active-work drain and watched close.
            try { requestRetirement() } finally { throw failure }
        }
    }

    private suspend fun acquireTarget(preparation: Boolean): Target? {
        val owner = Target()
        if (!reserveAcquisition(owner)) return null
        try {
            val result = services.openTarget(preparation)
            return when (result) {
                is SelfOwnedNativeOpenResult.Opened -> {
                    owner.capture(result.context) // Before checks, callbacks or result delivery.
                    retainNative(owner)
                    owner
                }
                is SelfOwnedNativeOpenResult.Unresolved -> { acquisitionFailure(result.reason); null }
            }
        } catch (failure: Throwable) {
            try { requestRetirement() } finally { throw failure }
        }
    }

    private fun reserveAcquisition(owner: OwnedNative): Boolean = withSetupLock(gate) {
        if (retiring) return@withSetupLock false
        requireOriginal(lanAttachment == null)
        check(native == null && !acquisitionCloseUnobserved)
        native = owner // Partial ownership is retained before entering either actual factory.
        acquisitionCloseUnobserved = true
        true
    }

    private fun retainNative(owner: OwnedNative) = withSetupLock(gate) {
        check(native === owner) // Already retained; never replace another generation.
        acquisitionCloseUnobserved = false
        // No denial callback here: a concurrent retirement may still be unpublishing the
        // service. That path waits for this admitted Work, then denies/closes this holder.
        // Every acquisition caller checks continueWork() before using the returned owner.
    }

    private fun beginAttachment(target: Target) = withSetupLock(gate) {
        check(native === target && !acquisitionCloseUnobserved)
        target.beginAttachment()
        acquisitionCloseUnobserved = true
    }

    private suspend fun closeNativeOwner(owner: OwnedNative) {
        withSetupLock(gate) {
            check(native === owner)
            view = view.copy(work = LocalSetupWork.CLOSING_NATIVE_OWNER)
            publishLocked()
        }
        try {
            withSetupLock(gate) { lanAttachment?.takeIf { it.owner === owner } }?.let { closeLanOwned(it) }
            // Token withdrawal and actual-owner denial are deliberately separate: the token
            // call may have run before a late target/Master/Client existed, while the newly
            // returned exact owners still require denial. A concurrent attempt is awaited
            // before close can be treated as confirmed.
            var denialFailure: Throwable? = null
            try { owner.denyTokenOnce() }
            catch (failure: Throwable) { denialFailure = failure }
            try { owner.denyOwnedHandlesOnce() }
            catch (failure: Throwable) { if (denialFailure == null) denialFailure = failure }
            owner.awaitDenialSettled()
            owner.closeOnce()
            // A denial/withdrawal that threw is never confirmation and is never retried.
            // Even when the genuine child owners close, unresolved withdrawal custody keeps
            // the lifecycle from reporting CLOSED.
            (denialFailure ?: owner.denialFailure)?.let { throw it }
        }
        catch (failure: Throwable) {
            withSetupLock(gate) { problemLocked(LocalSetupProblem.CLOSE_UNCONFIRMED) }
            requestRetirement()
            throw failure
        }
        withSetupLock(gate) {
            check(native === owner)
            native = null
            // Also acknowledges a captured owner whose post-capture handoff failed. An empty
            // holder cannot reach here: closeActual requires the genuine returned handle.
            acquisitionCloseUnobserved = false
            foundingAction = null
            if (!retiring) view = view.copy(work = active?.kind)
            publishLocked()
        }
    }

    private suspend fun inspectCompany(owner: Provisioner,
        priorInspection: LocalProvisionerInspection? = null) {
        val inspection = priorInspection ?: owner.value.inspect()
        if (!continueWork()) return
        when (inspection) {
            LocalProvisionerInspection.Unenrolled -> fail(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED)
            is LocalProvisionerInspection.Unavailable -> nativeFailure(inspection.reason)
            is LocalProvisionerInspection.FoundingPending -> {
                // The legacy P1 action is never converted into a head founding action.
                when (val result = owner.value.companyAuthority.readOriginalCompanyAndHeadAction()) {
                    is LocalCompanyAuthorityResult.Value -> {
                        requireOriginal(result.value.organizationId == inspection.action.organizationId &&
                            result.value.companyId == inspection.action.companyId && !result.value.enrollmentRetained)
                        presentFounding(owner, result.value)
                    }
                    is LocalCompanyAuthorityResult.Unavailable -> authorityFailure(result)
                }
            }
            is LocalProvisionerInspection.Enrolled -> {
                val head = when (val result = owner.value.companyAuthority.inspectCompanyAuthority()) {
                    is LocalCompanyAuthorityResult.Value -> result.value
                    is LocalCompanyAuthorityResult.Unavailable -> { authorityFailure(result); return }
                }
                if (!continueWork()) return
                val company = companyView(inspection.company, head)
                withSetupLock(gate) { foundingAction = null }
                change { it.copy(companyReview = null, company = company, branchReview = null, native = null, receipt = null) }
                val original = inspection.originalAction
                if (original == null) {
                    requireOriginal(!inspection.originalGrantRetained)
                    withSetupLock(gate) { branchAction = null; retainedPreparation = null }
                    change { it.copy(phase = LocalSetupPhase.COMPANY_RETAINED, problem = null) }
                } else {
                    presentBranch(original, company)
                    if (inspection.originalGrantRetained && continueWork()) {
                        when (val grant = owner.value.recoverOriginalPreparation()) {
                            is LocalProvisionerGrant.Retained -> presentPreparation(original, grant.packet)
                            is LocalProvisionerGrant.Unresolved -> nativeFailure(grant.reason)
                        }
                    }
                }
            }
        }
    }

    private suspend fun presentFounding(owner: Provisioner, action: LocalCompanyFoundingAction) {
        if (!continueWork()) return
        if (action.enrollmentRetained) {
            // Positive enrolled read is never shown with a new/replacement password form.
            inspectCompany(owner)
            return
        }
        val review = LocalSetupCompanyReview(DomainId.random(), action.companyName, action.installerName,
            action.installerLogin, action.headName, action.headLogin, action.rootFingerprint, action.organizationId,
            action.companyId, action.headAuthorityId, action.headEnrollmentId, action.protection, action.purposes)
        withSetupLock(gate) { foundingAction = action; branchAction = null; retainedPreparation = null }
        change { it.copy(phase = LocalSetupPhase.COMPANY_REVIEW, companyReview = review, company = null,
            branchReview = null, native = null, receipt = null, problem = null) }
    }

    private fun presentBranch(action: LocalProvisionerPendingAction, company: LocalSetupCompanyView) {
        requireOriginal(action.kind == LocalProvisionerActionKind.PREPARE_ORIGINAL_BRANCH &&
            action.organizationId == company.organizationId && action.companyId == company.companyId &&
            action.rootFingerprint == company.rootFingerprint && action.protection == company.protection &&
            action.installerLogin == company.installerLogin)
        val coordinates = requireNotNull(action.allocation)
        requireOriginal(coordinates.scope.organizationId == company.organizationId && coordinates.scope.companyId == company.companyId)
        val review = LocalSetupBranchReview(DomainId.random(), requireNotNull(action.branchName),
            company.rootFingerprint, company.organizationId, company.companyId, coordinates.scope.branchId,
            coordinates.allocationId, coordinates.installationId, coordinates.deviceId, coordinates.firstActorId, action.protection)
        withSetupLock(gate) { branchAction = action; retainedPreparation = null }
        change { it.copy(phase = LocalSetupPhase.BRANCH_REVIEW, branchReview = review,
            companyReview = null, company = company, native = null, receipt = null, problem = null) }
    }

    private fun presentPreparation(action: LocalProvisionerPendingAction, packet: SelfOwnedPreparationPacketV1) {
        requireOriginal(sameCoordinates(requireNotNull(action.allocation), packet.coordinates))
        withSetupLock(gate) { retainedPreparation = packet }
        change { it.copy(phase = LocalSetupPhase.ORIGINAL_PREPARATION_RETAINED, problem = null) }
    }

    private suspend fun readOriginalTarget() {
        val previous = withSetupLock(gate) { native }
        if (previous != null) closeNativeOwner(previous)
        if (!continueWork()) return
        // P3 requires the P2 existing-only observation profile. Even a successfully PREPARED
        // P2 owner is actually closed before reopening here; it cannot be relabeled read-only.
        val owner = acquireTarget(preparation = false) ?: return
        if (!continueWork() || !readNative(owner)) return
        if (!continueWork()) return
        beginAttachment(owner)
        when (val opened = services.openOriginalReceipt(owner.context)) {
            is LocalMasterInstallationReceiptOpenResult.Opened -> {
                owner.attachReceipt(opened)
                retainNative(owner)
                if (!continueWork()) return
                when (val result = opened.client.recoverOriginalReceipt()) {
                    is OriginalInstallationReceiptResult.Found -> {
                        val receipt = result.receipt
                        val coordinates = receipt.core.allocation.coordinates
                        requireOriginal(sameCoordinates(coordinates, owner.context.materialAtOpen.coordinates))
                        val projection = receiptView(receipt)
                        change { it.copy(phase = LocalSetupPhase.ORIGINAL_RECEIPT_FOUND, receipt = projection,
                            problem = LocalSetupProblem.FULL_INSTALLATION_HELD) }
                    }
                    OriginalInstallationReceiptResult.Busy -> fail(LocalSetupProblem.BUSY)
                    OriginalInstallationReceiptResult.Unresolved -> fail(LocalSetupProblem.STATE_UNCERTAIN)
                }
            }
            LocalMasterInstallationReceiptOpenResult.Busy -> acquisitionFailure(SelfOwnedInstallationFailureV1.BUSY)
            LocalMasterInstallationReceiptOpenResult.Unresolved -> acquisitionFailure(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private suspend fun readNative(owner: Target, expected: SelfOwnedAllocationCoordinatesV1? = null,
        expectedBranch: String? = null, verifyOriginalBinding: Boolean = false): Boolean =
        when (val read = owner.context.readOriginalNativeBinding()) {
        is SelfOwnedNativeReadResult.Retained -> {
            val material = read.material
            requireOriginal(sameCoordinates(material.coordinates, owner.context.materialAtOpen.coordinates))
            if (verifyOriginalBinding) {
                // Native readback exposes the digest of the complete signed binding. Check
                // consistency with the acquired original without decoding or granting authority.
                val bindingBytes = read.binding.copyWireBytes()
                val bindingDigest = try { sha256(bindingBytes).value } finally { bindingBytes.fill(0) }
                requireOriginal(bindingDigest == material.originalNativeBindingDigest &&
                    bindingDigest == owner.context.materialAtOpen.originalNativeBindingDigest)
            }
            if (expected != null) requireOriginal(sameCoordinates(expected, material.coordinates))
            if (expectedBranch != null) requireOriginal(expectedBranch == material.branchName)
            change { it.copy(phase = LocalSetupPhase.ORIGINAL_TARGET_RETAINED, native = nativeView(material),
                companyReview = null, receipt = null, firstAccountHeadLogin = null, definitionReview = null,
                problem = LocalSetupProblem.FULL_INSTALLATION_HELD) }
            true
        }
        is SelfOwnedNativeReadResult.Unresolved -> { nativeFailure(read.reason); false }
    }

    private fun requireProvisioner(): Provisioner = (native as? Provisioner)
        ?: throw OriginalRequired()
    private fun requireTarget(): Target = (native as? Target) ?: throw OriginalRequired()

    private fun start(kind: LocalSetupWork, phases: Set<LocalSetupPhase>, allowLocked: Boolean = false,
        prepare: () -> Operation) {
        val work: Work
        val operation: Operation
        withSetupLock(gate) {
            if (!canAdmit(allowLocked)) return
            if ((native as? FreshDeviceTarget)?.lanTransferEntered == true && kind !in LAN_WORK) {
                problemLocked(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED); return
            }
            if (view.phase !in phases) { problemLocked(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED); return }
            val now = try { elapsed() } catch (_: Exception) { problemLocked(LocalSetupProblem.STATE_UNCERTAIN); return }
            // This application watch withdraws publication; only the native Master
            // judges its separate challenge expiry. Retained-admission handoff keeps
            // this watch even after the operation changes the presentation phase.
            val watchMillis = if (kind in FRESH_PAIRING_WORK || view.phase == LocalSetupPhase.FRESH_DEVICE_PAIRING)
                FRESH_PAIRING_OPERATION_MILLIS else OPERATION_MILLIS
            if (now > Long.MAX_VALUE - watchMillis) { problemLocked(LocalSetupProblem.STATE_UNCERTAIN); return }
            work = Work(kind, now + watchMillis, watchMillis)
            active = work // No second waiter can queue/copy a secret past this point.
            operation = try { prepare() }
            catch (_: OriginalRequired) {
                active = null; problemLocked(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED); return
            } catch (_: Exception) {
                active = null; problemLocked(LocalSetupProblem.INVALID_INPUT); return
            } catch (_: Throwable) {
                active = null; problemLocked(LocalSetupProblem.STATE_UNCERTAIN); return
            }
            view = view.copy(work = kind, problem = null, nativeProblem = null)
            publishLocked()
        }
        scope.launch {
            try {
                if (continueWork()) operation.execute()
            } catch (_: OriginalRequired) {
                fail(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED)
            } catch (_: Throwable) {
                if (withSetupLock(gate) { acquisitionCloseUnobserved })
                    acquisitionFailure(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                else fail(LocalSetupProblem.STATE_UNCERTAIN)
            } finally {
                try { operation.clear() }
                finally {
                    withSetupLock(gate) {
                        if (active === work) active = null
                        if (!retiring) view = view.copy(work = null)
                        publishLocked()
                    }
                    work.finished.complete(Unit)
                }
            }
        }
    }

    private fun canAdmit(allowLocked: Boolean = false): Boolean {
        if (retiring || view.phase == LocalSetupPhase.CLOSED) return false
        if (view.locked && !allowLocked) return false
        if (active != null) { problemLocked(LocalSetupProblem.BUSY); return false }
        if (acquisitionCloseUnobserved) { problemLocked(LocalSetupProblem.CLOSE_UNCONFIRMED); return false }
        return true
    }

    private fun continueWork(): Boolean = withSetupLock(gate) { !retiring }
    private fun change(update: (LocalSetupView) -> LocalSetupView) = withSetupLock(gate) {
        if (!retiring) view = update(view)
        publishLocked()
    }
    private fun fail(problem: LocalSetupProblem) = change {
        if (it.phase == LocalSetupPhase.EMPLOYEE_ENROLLMENT) it.copy(problem = problem,
            employeeEnrollment = it.employeeEnrollment?.let { original -> original.copy(stage = when (original.stage) {
                LocalSetupEmployeeEnrollmentStage.APPROVAL_UNRESOLVED -> LocalSetupEmployeeEnrollmentStage.APPROVAL_UNRESOLVED
                LocalSetupEmployeeEnrollmentStage.ENROLLMENT_UNRESOLVED -> LocalSetupEmployeeEnrollmentStage.ENROLLMENT_UNRESOLVED
                LocalSetupEmployeeEnrollmentStage.JOURNAL_UNRESOLVED -> LocalSetupEmployeeEnrollmentStage.JOURNAL_UNRESOLVED
                else -> LocalSetupEmployeeEnrollmentStage.ORIGINAL_UNRESOLVED
            }) })
        else if (it.phase == LocalSetupPhase.EMPLOYEE_POLICY) it.copy(problem = problem,
            employeePolicy = it.employeePolicy?.let { original -> original.copy(reviewId = DomainId.random(),
                stage = when (original.stage) {
                    LocalSetupEmployeePolicyStage.PREPARATION_UNRESOLVED -> LocalSetupEmployeePolicyStage.PREPARATION_UNRESOLVED
                    LocalSetupEmployeePolicyStage.APPROVAL_UNRESOLVED -> LocalSetupEmployeePolicyStage.APPROVAL_UNRESOLVED
                    LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED -> LocalSetupEmployeePolicyStage.ACTIVATION_UNRESOLVED
                    else -> LocalSetupEmployeePolicyStage.ORIGINAL_UNRESOLVED
                }, outcome = null, eventId = null) })
        else if (it.phase == LocalSetupPhase.DEVICE_ADMISSION) {
            val original = (native as? Target)?.deviceAdmissionOriginal
            it.copy(problem = problem,
                deviceAdmissions = original?.let { selected -> deviceAdmissionList(it.deviceAdmissions, selected) } ?: it.deviceAdmissions,
                deviceAdmission = original?.projection(deviceAdmissionUnresolvedStage(original), LocalSetupDeviceAdmissionOutcome.STORAGE_UNCERTAIN)
                    ?: it.deviceAdmission?.copy(reviewId = DomainId.random(), stage = LocalSetupDeviceAdmissionStage.ORIGINAL_UNAVAILABLE,
                        outcome = LocalSetupDeviceAdmissionOutcome.STORAGE_UNCERTAIN, eventId = null))
        }
        else if (it.phase == LocalSetupPhase.FRESH_DEVICE_PAIRING) {
            val target = native as? Target
            val original = target?.freshPairingOriginal
            original?.nativeConfirmed = false
            original?.let { selected -> target?.confirmedFreshPairingRequests?.remove(selected.requestId) }
            it.copy(problem = problem, freshDevicePairing = if (original != null)
                original.projection(freshMasterUnresolvedStage(original)).copy(
                    masterOriginals = it.freshDevicePairing?.masterOriginals.orEmpty(), blockedRequestId = original.requestId)
                else freshTargetOriginal?.let { selected -> selected.projection(when {
                    selected.continuationAttempted || selected.responseCycle > 0 && !selected.continuationConfirmed ->
                        LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED
                    selected.responseEntered -> LocalSetupFreshDevicePairingStage.TARGET_RESPONSE_UNRESOLVED
                    selected.sdkEntered -> LocalSetupFreshDevicePairingStage.SDK_ACQUISITION_UNRESOLVED
                    selected.preparationEntered -> LocalSetupFreshDevicePairingStage.TARGET_PREPARATION_UNRESOLVED
                    else -> freshTargetStage(selected)
                }) } ?: it.freshDevicePairing?.copy(reviewId = DomainId.random(),
                    stage = LocalSetupFreshDevicePairingStage.ORIGINAL_UNAVAILABLE, nativePhase = null))
        }
        else it.copy(phase = LocalSetupPhase.ORIGINAL_UNRESOLVED, companyReview = null, definitionReview = null,
            firstAccountHeadLogin = null, problem = problem, nativeProblem = null)
    }
    private fun acquisitionFailure(reason: SelfOwnedInstallationFailureV1) = change {
        it.copy(phase = LocalSetupPhase.ORIGINAL_UNRESOLVED, companyReview = null,
            problem = LocalSetupProblem.CLOSE_UNCONFIRMED, nativeProblem = reason.toSetupProblem())
    }
    private fun nativeFailure(reason: SelfOwnedInstallationFailureV1) { fail(reason.toSetupProblem()) }
    private fun authorityProblem(result: LocalCompanyAuthorityResult.Unavailable): LocalSetupProblem = result.nativeReason?.toSetupProblem() ?: when (result.reason) {
            LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED -> LocalSetupProblem.HEAD_ENROLLMENT_REQUIRED
            LocalCompanyAuthorityFailure.ORIGINAL_KEY_PREPARATION_REQUIRED -> LocalSetupProblem.ORIGINAL_KEY_PREPARATION_REQUIRED
            LocalCompanyAuthorityFailure.ORIGINAL_TARGET_OWNER_REQUIRED -> LocalSetupProblem.ORIGINAL_TARGET_OWNER_REQUIRED
            LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED -> LocalSetupProblem.ORIGINAL_ACTION_REQUIRED
            LocalCompanyAuthorityFailure.ORIGINAL_ATTEMPT_SETTLEMENT_REQUIRED -> LocalSetupProblem.FULL_INSTALLATION_HELD
            LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE -> LocalSetupProblem.NATIVE_UNAVAILABLE
        }
    private fun authorityFailure(result: LocalCompanyAuthorityResult.Unavailable) {
        val problem = authorityProblem(result)
        change { it.copy(phase = if (problem == LocalSetupProblem.ORIGINAL_KEY_PREPARATION_REQUIRED)
            LocalSetupPhase.ORIGINAL_COMPANY_PENDING else LocalSetupPhase.ORIGINAL_UNRESOLVED,
            companyReview = null, problem = problem) }
    }
    private fun problemLocked(problem: LocalSetupProblem) {
        view = view.copy(problem = problem)
        publishLocked()
    }
    private fun publishLocked() {
        view = view.copy(lan = lanViewLocked())
        published.value = if (view.locked) view.copy(companyReview = null, company = null,
            branchReview = null, native = null, receipt = null, firstAccountHeadLogin = null, definitionReview = null,
            employeeEnrollment = null, employeeEnrollments = null, employeePolicy = null, employeePolicies = null,
            deviceAdmission = null, deviceAdmissions = null, freshDevicePairing = null) else view
    }

    private fun lanViewLocked(): LocalSetupLanView {
        val attachment = lanAttachment
        if (attachment is LanHost && attachment.status in setOf(LocalSetupLanStatus.STARTING, LocalSetupLanStatus.LISTENING) &&
            (attachment.target.master !== attachment.master || attachment.master.mode.value != LocalMasterLifecycleMode.OPERATIONAL)) {
            attachment.status = LocalSetupLanStatus.UNAVAILABLE
            attachment.endpoint = null
        }
        val available = attachment == null && !retiring && !view.locked && active == null && !acquisitionCloseUnobserved
        val target = native as? Target
        val fresh = native as? FreshDeviceTarget
        val original = fresh?.original
        return LocalSetupLanView(
            authenticationAvailable = attachment is LanClient && attachment.authenticationPublished &&
                attachment.authentication?.isCurrent() == true,
            role = attachment?.role,
            status = attachment?.status ?: LocalSetupLanStatus.NOT_ATTACHED,
            endpoint = if (retiring || view.locked) null else attachment?.endpoint,
            hostAvailable = available && view.phase in LAN_HOST_PHASES &&
                target?.master?.mode?.value == LocalMasterLifecycleMode.OPERATIONAL,
            clientAvailable = available && view.phase == LocalSetupPhase.FRESH_DEVICE_PAIRING &&
                fresh != null && !fresh.lanTransferEntered && original != null && freshTargetOriginal === original &&
                original.responseActionId != null && original.verifiedMaster?.originalPairingActionId == original.responseActionId &&
                original.identity != null && original.possession != null && !original.continuationAttempted,
        )
    }

    /** Shared rollback detection; OS sampling is not native security-clock qualification. */
    private fun elapsed(): Long = withSetupLock(clockGate) {
        val current = execution.elapsedMillis()
        check(current >= 0 && current >= lastElapsed)
        lastElapsed = current
        current
    }

    private fun observeDrain() {
        try {
            val now = elapsed()
            val overrun = withSetupLock(gate) {
                if (retiring) {
                    if (retirement?.isCompleted == false && retiringAt?.let { now - it >= retirementWatchMillis } != false)
                        problemLocked(LocalSetupProblem.CLOSE_UNCONFIRMED)
                    false
                } else active?.let { now >= it.deadline } == true
            }
            if (overrun) requestRetirement()
        } catch (_: Throwable) { requestRetirement() }
    }

    private sealed class OwnedNative {
        private var closeResult: CompletableDeferred<Unit>? = null
        private val denialGate = LocalSetupMonitor()
        private var tokenDenialOutcome: CompletableDeferred<Unit>? = null
        private val handleDenials = mutableListOf<Pair<Any, CompletableDeferred<Unit>>>()
        private var denialFailureValue: Throwable? = null
        val denialFailure: Throwable? get() = withSetupLock(denialGate) { denialFailureValue }

        /** Withdraws/denies the exact lifecycle token. Never retried after an uncertain outcome. */
        protected abstract fun denyToken()
        /** Denies the actual returned child owners retained by this holder. */
        protected open fun denyOwnedHandles() {}
        protected abstract suspend fun closeActual()

        private fun retainDenialFailure(failure: Throwable) = withSetupLock(denialGate) {
            if (denialFailureValue == null) denialFailureValue = failure
        }

        /** A second caller observes this same attempt; cleanup awaits its actual outcome. */
        fun denyTokenOnce() {
            val outcome: CompletableDeferred<Unit>
            withSetupLock(denialGate) {
                if (tokenDenialOutcome != null) return
                outcome = CompletableDeferred()
                tokenDenialOutcome = outcome
            }
            try { denyToken(); outcome.complete(Unit) }
            catch (failure: Throwable) {
                retainDenialFailure(failure)
                outcome.completeExceptionally(failure)
                throw failure
            }
        }

        /**
         * The attempt belongs to the exact returned handle, not its reusable acquisition
         * holder. A later Master/Client gets its own denial after startup drains; an earlier
         * uncertain handle is never retried. Callbacks run outside the native monitor.
         */
        protected fun denyHandleOnce(handle: Any, deny: () -> Unit) {
            val outcome: CompletableDeferred<Unit>
            withSetupLock(denialGate) {
                if (handleDenials.any { it.first === handle }) return
                outcome = CompletableDeferred()
                handleDenials += handle to outcome
            }
            try { deny(); outcome.complete(Unit) }
            catch (failure: Throwable) {
                retainDenialFailure(failure)
                outcome.completeExceptionally(failure)
                // Keep denying other exact owners even when this one remains uncertain.
            }
        }

        fun denyOwnedHandlesOnce() {
            denyOwnedHandles()
            denialFailure?.let { throw it }
        }

        /** Waits for any concurrent denial attempt; an uncertain failure stays retained. */
        suspend fun awaitDenialSettled() {
            val outcomes = withSetupLock(denialGate) {
                listOfNotNull(tokenDenialOutcome) + handleDenials.map { it.second }
            }
            outcomes.forEach { runCatching { it.await() } }
        }

        suspend fun closeOnce() {
            closeResult?.let { it.await(); return }
            val outcome = CompletableDeferred<Unit>()
            closeResult = outcome
            try { closeActual(); outcome.complete(Unit) }
            catch (failure: Throwable) { outcome.completeExceptionally(failure); throw failure }
        }
    }
    private sealed class LanAttachment(
        val owner: OwnedNative,
        val settings: LocalSetupLanSettings,
        val retainedScope: CoroutineScope,
        val role: LocalSetupLanRole,
    ) {
        abstract val captured: Boolean
        var status: LocalSetupLanStatus = if (role == LocalSetupLanRole.HOST)
            LocalSetupLanStatus.STARTING else LocalSetupLanStatus.CONNECTING
        var endpoint: LocalSetupLanEndpoint? = null
        var observedEndpoint: LocalSetupLanEndpoint? = null
        var closeResult: CompletableDeferred<Unit>? = null
        val canObserve: Boolean get() = closeResult == null && status in setOf(
            LocalSetupLanStatus.STARTING, LocalSetupLanStatus.CONNECTING,
            LocalSetupLanStatus.LISTENING, LocalSetupLanStatus.CONNECTED,
        )
        suspend fun closeActual() {
            check(captured) { "Original LAN attachment construction cleanup remains unconfirmed" }
            closeReturned()
        }
        protected abstract suspend fun closeReturned()
    }
    private class LanHost(
        val target: Target,
        val master: LocalSetupMaster,
        settings: LocalSetupLanSettings,
        retainedScope: CoroutineScope,
    ) : LanAttachment(target, settings, retainedScope, LocalSetupLanRole.HOST) {
        @Volatile private var acquired: LocalSetupLanHost? = null
        override val captured: Boolean get() = acquired != null
        val value: LocalSetupLanHost get() = checkNotNull(acquired)
        fun capture(actual: LocalSetupLanHost) { acquired = actual }
        override suspend fun closeReturned() { value.close() }
    }
    private class LanClient(
        val target: FreshDeviceTarget,
        val original: LocalSetupFreshDeviceTargetOriginal,
        val actionId: DomainId,
        settings: LocalSetupLanSettings,
        val selectedEndpoint: LocalSetupLanEndpoint,
        retainedScope: CoroutineScope,
    ) : LanAttachment(target, settings, retainedScope, LocalSetupLanRole.CLIENT) {
        @Volatile private var acquired: LocalSetupLanClient? = null
        var authentication: LocalSetupLanAuthentication? = null
        var authenticationPublished = false
        var authenticationDenialFailure: Throwable? = null
        override val captured: Boolean get() = acquired != null
        val value: LocalSetupLanClient get() = checkNotNull(acquired)
        fun capture(actual: LocalSetupLanClient) { acquired = actual }
        override suspend fun closeReturned() { value.close() }
    }
    private class Provisioner : OwnedNative() {
        @Volatile private var acquired: LocalProvisioner? = null
        // Separate lifetime attempt keys: the two declared ingress domains are independent,
        // and a throw in the first must not skip the second exact-owner attempt.
        private val issuanceDenialKey = Any()
        private val headIssuanceDenialKey = Any()
        val value: LocalProvisioner get() = checkNotNull(acquired)
        // Called once by the admitted acquisition. No checks/allocations before this write.
        fun capture(value: LocalProvisioner) { acquired = value }
        override fun denyToken() = Unit
        override fun denyOwnedHandles() {
            val actual = acquired ?: return // In-flight acquisition is drained, not denied early.
            denyHandleOnce(issuanceDenialKey) { actual.denyNewIssuance() }
            denyHandleOnce(headIssuanceDenialKey) { actual.companyAuthority.denyHeadIssuance() }
        }
        override suspend fun closeActual() { value.close() }
    }
    private class FreshDeviceTarget(val original: LocalSetupFreshDeviceTargetOriginal) : OwnedNative() {
        @Volatile private var acquired: LocalSetupFreshDeviceTarget? = null
        val value: LocalSetupFreshDeviceTarget get() = checkNotNull(acquired)
        @Volatile var lanTransferEntered = false
        fun capture(target: LocalSetupFreshDeviceTarget) { acquired = target }
        override fun denyToken() = Unit
        override fun denyOwnedHandles() {
            // The exact selected Client owns target readback/fence/close after transfer.
            // Retirement may race native retention before its wrapper has captured a Client.
            if (!lanTransferEntered) acquired?.let { actual ->
                denyHandleOnce(actual) { actual.denyNewWork() }
            }
        }
        override suspend fun closeActual() { value.close() }
    }
    private inner class Target : OwnedNative() {
        @Volatile private var acquired: SelfOwnedLocalSecurityContext? = null
        val context: SelfOwnedLocalSecurityContext get() = checkNotNull(acquired)
        fun capture(context: SelfOwnedLocalSecurityContext) { acquired = context }
        @Volatile var master: LocalSetupMaster? = null
            private set
        @Volatile private var receiptOwner: LocalMasterInstallationReceiptOpenResult.Opened? = null
        private var attachmentPending = false
        private var clientAttachmentPending = false
        // A settled-but-unconfirmed native custody fact. Clearing the in-flight marker does not
        // prove absence or transfer; these flags keep the holder unresolved until a documented
        // public observation exists.
        private var masterCustodyUnknown = false
        private var clientCustodyUnknown = false
        @Volatile var localClient: LocalSetupClient? = null
            private set
        private var clientObserver: Job? = null
        var employeeIdentity: LocalEmployeeSetupIntents.Identity? = null
        var employeeAction: LocalEmployeeEnrollmentAction? = null
        var employeeConnection: LocalSetupEmployeeConnection? = null
        var employeeJournalOpenedRequestId: DomainId? = null
        var employeePolicyOriginal: LocalSetupEmployeePolicyOriginal? = null
        var deviceAdmissionOriginal: LocalSetupDeviceAdmissionOriginal? = null
        var freshPairingOriginal: LocalSetupFreshDeviceMasterOriginal? = null
        val confirmedFreshPairingRequests = mutableSetOf<DomainId>()
        val confirmedDeviceAdmissionTerminals = mutableSetOf<DomainId>()
        var definition: LocalBranchInstallationSnapshotV2? = null
        fun beginAttachment() {
            check(!attachmentPending && master == null && receiptOwner == null)
            attachmentPending = true
        }
        fun attachMaster(value: LocalSetupMaster) {
            master = value // Capture before checks/continuation; retirement drains this actual owner.
            attachmentPending = false
            masterCustodyUnknown = false
        }
        fun attachReceipt(value: LocalMasterInstallationReceiptOpenResult.Opened) {
            receiptOwner = value
            attachmentPending = false
        }
        /**
         * The openMaster call settled with no returned Master. Its invocation is no longer in
         * flight, but native custody may have transferred to an unreturned Master. Keep the
         * target HELD_TRANSFER_UNKNOWN; no target-only close is fabricated or treated as CLOSED.
         */
        fun settleFailedMasterAttachment() {
            attachmentPending = false
            masterCustodyUnknown = true
        }
        fun beginClientAttachment() {
            check(localClient == null && !clientAttachmentPending)
            clientAttachmentPending = true
        }
        fun attachClient(value: LocalSetupClient?) {
            localClient = value
            clientAttachmentPending = false // A null return still waited for actual journal closes.
            if (value != null) clientCustodyUnknown = false
        }
        /**
         * connectClient threw. The call settled but an unreturned partial native Client may
         * exist; the known Master remains the exact closeable native owner. No Client handle is
         * fabricated, none is closed, and no safe absence is inferred.
         */
        fun settleFailedClientAttachment() {
            clientAttachmentPending = false
            clientCustodyUnknown = true
        }
        fun watchClient(job: Job) { check(clientObserver == null); clientObserver = job }
        override fun denyToken() {
            // The holder is the exact opaque lifecycle token passed to publication and
            // withdrawal. Withdraw it once; owner denial is a separate exact-handle step.
            services.withdrawClient(this)
        }
        override fun denyOwnedHandles() {
            localClient?.let { actual -> denyHandleOnce(actual) { actual.denyNewWork() } }
            master?.let { actual -> denyHandleOnce(actual) { actual.denyNewWork() }; return }
            receiptOwner?.let { actual -> denyHandleOnce(actual) { actual.denyNewReads() }; return }
            val actual = acquired ?: return
            denyHandleOnce(actual) { actual.denyNewCeremonies(); actual.denyDataUse() }
        }
        override suspend fun closeActual() {
            // A failed transfer can already have consumed the context. Its now-inert close
            // must never be mistaken for actual child-owner closure or free the process slot.
            check(!attachmentPending && !clientAttachmentPending) { "Original child-owner close remains unconfirmed" }
            clientObserver?.cancelAndJoin()
            clientObserver = null
            localClient?.close()
            localClient = null
            val service = master
            val reader = receiptOwner
            when {
                service != null -> {
                    check(service.quiesceAndClose()) { "Original master close remains unconfirmed" }
                    master = null
                }
                reader != null -> { reader.close(); receiptOwner = null }
                masterCustodyUnknown -> Unit // No public retained-custody observation exists.
                else -> context.close()
            }
            definition = null
            employeeIdentity = null; employeeAction = null; employeeConnection = null; employeeJournalOpenedRequestId = null
            employeePolicyOriginal = null; deviceAdmissionOriginal = null; freshPairingOriginal = null
            confirmedDeviceAdmissionTerminals.clear()
            confirmedFreshPairingRequests.clear()
            // A settled call never proves native absence/transfer. Keep the unresolved fact
            // explicit so retirement cannot report CLOSED from a normal Deferred alone.
            check(!clientCustodyUnknown) { "Original unreturned Client custody remains unconfirmed" }
            check(!masterCustodyUnknown) { "Original target transfer remains unconfirmed" }
        }
    }
    private class Work(val kind: LocalSetupWork, val deadline: Long, val watchMillis: Long) {
        val finished = CompletableDeferred<Unit>()
    }
    private class Operation(val execute: suspend () -> Unit, val clear: () -> Unit)
    private fun operation(clear: () -> Unit = {}, body: suspend () -> Unit): Operation = Operation(body, clear)
    private class OriginalRequired : RuntimeException("Original setup action required")
    private fun requireOriginal(value: Boolean) { if (!value) throw OriginalRequired() }

    override fun toString(): String = "LocalSetupCoordinator([REDACTED])"

    private companion object {
        const val OPERATION_MILLIS = 300_000L
        const val FRESH_PAIRING_OPERATION_MILLIS = 30_000L
        val LAN_WORK = setOf(LocalSetupWork.STARTING_LAN_HOST, LocalSetupWork.CONNECTING_LAN_CLIENT,
            LocalSetupWork.READING_LAN_ATTACHMENT, LocalSetupWork.CLOSING_LAN_ATTACHMENT)
        val LAN_HOST_PHASES = setOf(LocalSetupPhase.INSTALLATION_RETAINED, LocalSetupPhase.EMPLOYEE_ENROLLMENT,
            LocalSetupPhase.EMPLOYEE_POLICY, LocalSetupPhase.DEVICE_ADMISSION, LocalSetupPhase.FRESH_DEVICE_PAIRING,
            LocalSetupPhase.ORIGINAL_UNRESOLVED)
        val LAN_ACTION_PHASES = LocalSetupPhase.entries.toSet() - setOf(LocalSetupPhase.CLOSING, LocalSetupPhase.CLOSED)
        val FRESH_PAIRING_WORK = setOf(LocalSetupWork.READING_FRESH_DEVICE_PAIRING,
            LocalSetupWork.PREPARING_FRESH_DEVICE_TARGET, LocalSetupWork.ACQUIRING_FRESH_DEVICE_SDK,
            LocalSetupWork.PREPARING_FRESH_DEVICE_CHALLENGE, LocalSetupWork.RESPONDING_FRESH_DEVICE_CHALLENGE,
            LocalSetupWork.PROVING_FRESH_DEVICE_TARGET, LocalSetupWork.IMPORTING_FRESH_DEVICE_DOCUMENT,
            LocalSetupWork.EXPORTING_FRESH_DEVICE_DOCUMENT, LocalSetupWork.RETIRING_UNSIGNED_DEVICE_PAIRING,
            LocalSetupWork.CONTINUING_UNSIGNED_DEVICE_PAIRING)
        val EMPLOYEE_OPEN_PHASES = setOf(LocalSetupPhase.INSTALLATION_RETAINED, LocalSetupPhase.EMPLOYEE_ENROLLMENT)
        val EMPLOYEE_POLICY_OPEN_PHASES = setOf(LocalSetupPhase.INSTALLATION_RETAINED, LocalSetupPhase.EMPLOYEE_POLICY)
        val DEVICE_ADMISSION_OPEN_PHASES = setOf(LocalSetupPhase.INSTALLATION_RETAINED, LocalSetupPhase.DEVICE_ADMISSION)
        val FRESH_MASTER_HANDOFF_PHASES = setOf(LocalDevicePairingPhase.TARGET_PROVED, LocalDevicePairingPhase.APPROVED)
        val UNRESOLVED_DEVICE_PAIRING_PHASES = setOf(LocalDevicePairingPhase.HEAD_CONSENT_INTENT,
            LocalDevicePairingPhase.SIGNING_INTENT, LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT)
        val TERMINAL_EMPLOYEE_POLICY_PHASES = setOf(LocalEmployeePolicyActivationPhase.CONSUMED, LocalEmployeePolicyActivationPhase.REJECTED)
        val FIRST_ACCOUNT_OPEN_PHASES = setOf(LocalSetupPhase.CHOOSE_ROUTE, LocalSetupPhase.ORIGINAL_TARGET_RETAINED,
            LocalSetupPhase.ORIGINAL_RECEIPT_FOUND, LocalSetupPhase.ORIGINAL_UNRESOLVED,
            LocalSetupPhase.FIRST_ACCOUNT_DETAILS, LocalSetupPhase.FIRST_ACCOUNT_REVIEW, LocalSetupPhase.INSTALLATION_RETAINED)
        val REFRESH_PHASES = setOf(LocalSetupPhase.COMPANY_REVIEW, LocalSetupPhase.ORIGINAL_COMPANY_PENDING,
            LocalSetupPhase.COMPANY_RETAINED, LocalSetupPhase.BRANCH_REVIEW,
            LocalSetupPhase.ORIGINAL_PREPARATION_RETAINED, LocalSetupPhase.ORIGINAL_TARGET_RETAINED,
            LocalSetupPhase.ORIGINAL_RECEIPT_FOUND, LocalSetupPhase.ORIGINAL_UNRESOLVED,
            LocalSetupPhase.FIRST_ACCOUNT_DETAILS, LocalSetupPhase.FIRST_ACCOUNT_REVIEW, LocalSetupPhase.INSTALLATION_RETAINED,
            LocalSetupPhase.INSTALLATION_ACK_PENDING)

        fun receiptView(receipt: LocalBranchInstallationReceiptV2): LocalSetupReceiptView {
            val coordinates = receipt.core.allocation.coordinates
            return LocalSetupReceiptView(receipt.core.originalRequestId, receipt.core.issuedAttemptId,
                coordinates.installationId, coordinates.scope.branchId, receipt.core.definitionDigest.value,
                receipt.digest.value, receipt.genesis.value)
        }

        fun sameCoordinates(a: SelfOwnedAllocationCoordinatesV1, b: SelfOwnedAllocationCoordinatesV1): Boolean =
            a.allocationId == b.allocationId && a.scope == b.scope && a.installationId == b.installationId &&
                a.assignmentId == b.assignmentId && a.deviceId == b.deviceId && a.storageInstanceId == b.storageInstanceId &&
                a.storageKeyId == b.storageKeyId && a.pepperKeyId == b.pepperKeyId &&
                a.targetSigningKeyId == b.targetSigningKeyId && a.firstActorId == b.firstActorId

        fun companyView(company: LocalProvisionerCompanyView, head: LocalCompanyAuthorityView): LocalSetupCompanyView {
            check(company.rootId == head.rootId && company.organizationId == head.organizationId &&
                company.companyId == head.companyId && company.companyName == head.companyName &&
                company.rootFingerprint == head.rootFingerprint && company.protection == head.protection)
            return LocalSetupCompanyView(company.companyName, company.responsiblePersonName, company.installerLogin,
                head.headName, head.headLogin, company.rootFingerprint, company.organizationId, company.companyId,
                company.protection, head.headAuthorityId, head.headEnrollmentId, head.headEnabled,
                buildSet {
                    if (head.headEnabled && head.policyIssuanceEnabled) add(LocalCompanyHeadPurposeV1.POLICY_PUBLISH)
                    if (head.headEnabled && head.installationIssuanceEnabled) add(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE)
                })
        }

        fun nativeView(material: SelfOwnedNativeMaterialView): LocalSetupNativeView {
            val ids = material.coordinates
            return LocalSetupNativeView(material.branchName, ids.scope.organizationId, ids.scope.companyId,
                ids.scope.branchId, ids.allocationId, ids.installationId, ids.deviceId, ids.firstActorId,
                material.targetSigningFingerprint, material.originalNativeBindingDigest, material.protection)
        }

        fun SelfOwnedInstallationFailureV1.toSetupProblem(): LocalSetupProblem = when (this) {
            SelfOwnedInstallationFailureV1.BUSY -> LocalSetupProblem.BUSY
            SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM -> LocalSetupProblem.UNSUPPORTED_PLATFORM
            SelfOwnedInstallationFailureV1.INVALID_APPLICATION -> LocalSetupProblem.INVALID_APPLICATION
            SelfOwnedInstallationFailureV1.USER_LOCKED -> LocalSetupProblem.USER_LOCKED
            SelfOwnedInstallationFailureV1.INVALID_INPUT -> LocalSetupProblem.INVALID_INPUT
            SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED -> LocalSetupProblem.AUTHENTICATION_REFUSED
            SelfOwnedInstallationFailureV1.THROTTLED -> LocalSetupProblem.THROTTLED
            SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN -> LocalSetupProblem.ISSUANCE_WITHDRAWN
            SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED -> LocalSetupProblem.CAPACITY_EXHAUSTED
            SelfOwnedInstallationFailureV1.ALREADY_ENROLLED, SelfOwnedInstallationFailureV1.EXISTING_AUTHORITY_REQUIRED,
            SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED -> LocalSetupProblem.ORIGINAL_ACTION_REQUIRED
            SelfOwnedInstallationFailureV1.CONFLICT, SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
            SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN -> LocalSetupProblem.STATE_UNCERTAIN
            SelfOwnedInstallationFailureV1.UNAVAILABLE, SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE,
            SelfOwnedInstallationFailureV1.NATIVE_RETIRED -> LocalSetupProblem.NATIVE_UNAVAILABLE
        }
    }
}
