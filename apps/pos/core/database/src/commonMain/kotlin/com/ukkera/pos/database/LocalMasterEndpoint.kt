package com.ukkera.pos.database

import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalMasterLifecycleAction
import com.ukkera.pos.security.LocalMasterLifecycleDenial
import com.ukkera.pos.security.LocalMasterLifecycleRequest
import com.ukkera.pos.security.LocalMasterLifecycleResult
import com.ukkera.pos.security.LocalMasterLifecycleService
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalAdministrationCommand
import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalEmployeeCreationReviewV1
import com.ukkera.pos.security.LocalEmployeeCreationExecutionV1
import com.ukkera.pos.security.LocalEmployeeCreationCancellationV1
import com.ukkera.pos.security.LocalEmployeeCreationTargetV1
import com.ukkera.pos.security.LocalEmployeeCreationDraftV1
import com.ukkera.pos.security.LocalEmployeeCreationContextResultV1
import com.ukkera.pos.security.LocalEmployeeCreationDefinitionResultV1
import com.ukkera.pos.security.LocalEmployeeCreationStatusV1
import com.ukkera.pos.security.LocalAdministrationQuery
import com.ukkera.pos.security.LocalAdministrationReadResult
import com.ukkera.pos.security.LocalAdministrationService
import com.ukkera.pos.security.LocalAdministrationStatus
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.security.LocalAuthenticationDenial
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalBranchInitializer
import com.ukkera.pos.security.LocalBranchInstallationDefinition
import com.ukkera.pos.security.LocalBranchInstallationResult
import com.ukkera.pos.security.LocalCredentialAdministration
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.NativeLocalEmployeeEnrollmentDispatch
import com.ukkera.pos.security.NativeLocalEmployeePolicyActivationDispatch
import com.ukkera.pos.security.NativeLocalDeviceAdmissionDispatch
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalAccountReactivation
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.CompanyBusinessPublicationAction
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.CompanyBusinessPublicationProposal
import com.ukkera.pos.security.CompanyBusinessPublicationReference
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompanyAuthorityFailure
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.discardUndeliveredSerialContent
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore

public enum class LocalMasterEndpointHealth { ACTIVE, STOPPING, RETIRED }

public enum class LocalMasterRetirementReason { REQUEST_DEADLINE, STORAGE_UNCERTAIN, UNEXPECTED_SERVICE_FAILURE }

/**
 * Platform supervision hook. Must synchronously mark the service unavailable and arrange a
 * bounded OS-level retirement/restart. It MUST NOT synchronously call Room.close(), release
 * writer custody, promote another writer, or start a replacement pool while this process could
 * still own a poisoned connection. An exception here cannot re-enable the endpoint.
 */
public fun interface LocalMasterRetirement {
    public fun retire(reason: LocalMasterRetirementReason)
}

/**
 * Typed, bounded, supervised ingress for the REAL auth/read/command kernel. The service owns a
 * fresh CoroutineScope made only of a new SupervisorJob and an explicitly supplied dispatcher.
 * Caller contexts (including Room borrowed-connection elements) are never propagated. Workers
 * authenticate first, after that full call returns enter the separate command/read kernel.
 * No caller callback, raw principal, SQL, DAO or transaction view is accepted by this endpoint.
 *
 * A cancelled caller stops waiting, NOT an already accepted financial intent. That request may
 * have committed; its durable operation ID is the recovery key. Results never become evidence
 * of cancellation/absence. Password/bearer snapshots are cleared when work ends or is rejected.
 * A service deadline or uncertain storage permanently retires this endpoint. A separate
 * watchdog does not await poisoned Room cleanup before withholding responses/retiring ingress.
 * This is bounded refusal, not proof of OS process termination or another writer's fencing.
 *
 * Only the trusted composition creates this with the SAME security source as the store. No
 * defaults provision identity, grant writer authority, or qualify keys/transport/platforms.
 * An actual grouped offline initializer selects installation-only admission. ALL other work
 * is then refused both at submission and by the worker, even through a leaked full endpoint.
 */
public class LocalMasterEndpoint internal constructor(
    private val store: AtomicMasterStore,
    private val principals: TrustedPrincipalFactory,
    private val authentication: LocalAuthenticationService,
    private val credentials: LocalCredentialAdministration,
    private val approvals: LocalActionApprovalService,
    private val retirement: LocalMasterRetirement,
    watchdogDispatcher: CoroutineDispatcher,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    workers: Int = 4,
    maximumQueuedRequests: Int = 32,
    private val maximumRequestMillis: Long = 30_000,
    private val installationInitializer: LocalBranchInitializer? = null,
    private val administration: LocalAdministrationService? = null,
    private val lifecycle: LocalMasterLifecycleService? = null,
) : LocalMasterClient, LocalMasterEnrollmentClient, LocalMasterApprovalClient, LocalMasterAdministrationClient, LocalMasterSerialPublications {
    init {
        require(workers in 1..16 && maximumQueuedRequests in 1..256)
        require(maximumRequestMillis in 1_000..300_000)
    }

    /** Only the actual native composition asks about a kernel-issued binding; no IO or grant. */
    internal fun ownsPhysicalInvocation(gate: PhysicalRecordingExecutionGate,
        binding: PhysicalRecordingInvocationBinding): Boolean =
        mutableHealth.value == LocalMasterEndpointHealth.ACTIVE && store.ownsPhysicalInvocation(this, gate, binding)

    internal fun ownsPhysicalInstruction(gate: PhysicalRecordingExecutionGate,
        binding: PhysicalInstructionNativeBinding): Boolean =
        mutableHealth.value == LocalMasterEndpointHealth.ACTIVE && store.ownsPhysicalInstruction(this, gate, binding)

    /** Actual owner calls this BEFORE retiring/closing state or ANY external callback. */
    internal fun denyPhysicalFromOwner() { store.denyPhysicalRecording() }

    private val serviceJob = SupervisorJob()
    private val physicalRegistration = store.registerPhysicalEndpoint(this)
    /** Genuine optional companion only; an arbitrary LocalMasterClient cannot attach one. */
    public val physicalActions: LocalMasterPhysicalActions? =
        if (installationInitializer == null) store.physicalActionsFor(this) else null
    private val serviceScope = CoroutineScope(serviceJob + dispatcher)
    private val watchdogJob = SupervisorJob()
    // Composition supplies an independently scheduled watchdog. A fresh coroutine alone is not
    // scheduling isolation from blocking storage/provider code; OS retirement is still required.
    private val watchdogScope = CoroutineScope(watchdogJob + watchdogDispatcher)
    private val mutableHealth = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    override public val health: StateFlow<LocalMasterEndpointHealth> = mutableHealth.asStateFlow()
    private enum class WorkKind { OPERATIONAL, INSTALLATION }
    private val admittedWork = if (installationInitializer == null) WorkKind.OPERATIONAL else WorkKind.INSTALLATION
    /** Locator-only custody companion. Null until the genuine original owner can be composed. */
    public val nativeOriginalIntake: NativeOriginalIntake? =
        if (installationInitializer == null) store.nativeOriginalIntakeFor(this) else null
    private val maximumNativeOriginalRequests = minOf(128, maximumQueuedRequests)
    // Bounded QUEUED/EXECUTING selectors only, never a lifetime/durable deduplication cache.
    // A completed SQL operation is recognized from its whole retained pair, not from this set.
    private val nativeOriginalRequests = MutableStateFlow<Set<DomainId>>(emptySet())
    private val nativeOriginalScanQueued = MutableStateFlow(false)
    // One full definition/password preparation OR queued/executing ceremony, without waiters.
    // Releasing this on caller cancellation would permit unbounded detached preparation/work.
    private val installationSlot = Semaphore(1)
    private val originalEmployeeEnrollments = MutableStateFlow<RoomSelfOwnedEmployeeEnrollments?>(null)
    private val originalEmployeePolicyActivations = MutableStateFlow<RoomSelfOwnedEmployeePolicyActivations?>(null)
    private val originalDeviceAdmissions = MutableStateFlow<RoomSelfOwnedDeviceAdmissions?>(null)
    private val originalSerialPublications = MutableStateFlow<RoomSelfOwnedSerialPublications?>(null)
    /** Null on an unqualified/non-original composition; never enabled by a client flag. */
    public val serialPublications: LocalMasterSerialPublications? get() =
        if (installationInitializer == null && originalSerialPublications.value != null) this else null
    internal fun bindOriginalSerialPublications(value: RoomSelfOwnedSerialPublications) {
        check(installationInitializer == null &&
            (originalSerialPublications.value == null || originalSerialPublications.value === value))
        originalSerialPublications.value = value
    }
    /** Same original security service and its EXISTING password capacity; no second owner. */
    internal fun bindOriginalEmployeeEnrollments(value: RoomSelfOwnedEmployeeEnrollments) {
        check(installationInitializer == null && credentials === value.administration &&
            (originalEmployeeEnrollments.value == null || originalEmployeeEnrollments.value === value))
        originalEmployeeEnrollments.value = value
    }
    internal fun bindOriginalEmployeePolicyActivations(value: RoomSelfOwnedEmployeePolicyActivations) {
        check(installationInitializer == null && credentials === value.administration &&
            (originalEmployeePolicyActivations.value == null || originalEmployeePolicyActivations.value === value))
        originalEmployeePolicyActivations.value = value
    }
    internal fun bindOriginalDeviceAdmissions(value: RoomSelfOwnedDeviceAdmissions) {
        check(installationInitializer == null && credentials === value.administration &&
            (originalDeviceAdmissions.value == null || originalDeviceAdmissions.value === value))
        originalDeviceAdmissions.value = value
    }
    internal val installationClient: LocalMasterInstallationClient? = installationInitializer?.let {
        object : LocalMasterInstallationClient {
            override val health: StateFlow<LocalMasterEndpointHealth> get() = this@LocalMasterEndpoint.health
            override suspend fun initialize(definition: LocalBranchInstallationDefinition,
                password: CharArray): LocalBranchInstallationResult = submitInstallation(definition, password)
            override suspend fun lookupInstallation(definition: LocalBranchInstallationDefinition): LocalBranchInstallationResult =
                submitInstallation(definition, null)
        }
    }
    private val requests = Channel<Work<*>>(maximumQueuedRequests, onUndeliveredElement = { it.abandon() })
    private val workerJobs: List<Job> = List(workers) {
        serviceScope.launch {
            try {
                val physicalIngress = store.capturePhysicalWorker(this@LocalMasterEndpoint,
                    physicalRegistration, currentCoroutineContext())
                for (work in requests) {
                    if (mutableHealth.value != LocalMasterEndpointHealth.ACTIVE || work.kind != admittedWork ||
                        (work.kind==WorkKind.OPERATIONAL && !store.allowsOperationalIngress())) {
                        work.abandon()
                    } else {
                        work.run(physicalIngress)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                retire(LocalMasterRetirementReason.UNEXPECTED_SERVICE_FAILURE)
            }
        }
    }
    /**
     * Scheduling only: no native/provider/Room call in this coroutine. Fixed scan work uses the
     * SAME queue, clean service workers and deadline watchdog as every other actual endpoint
     * operation. The one-second BACKGROUND retry is not B4's publication lag or a human TTL.
     * Restart/repeated wake enumerates authenticated existing catalogue entries only. No new
     * allocation, employee session, physical action or new recording operation is generated.
     */
    private val nativeOriginalScanScheduler: Job? = nativeOriginalIntake?.let { intake ->
        serviceScope.launch {
            while (mutableHealth.value == LocalMasterEndpointHealth.ACTIVE) {
                if (nativeOriginalScanQueued.compareAndSet(false, true)) {
                    val work = NativeOriginalScanWork(intake)
                    if (!requests.trySend(work).isSuccess) work.abandon()
                }
                delay(1_000)
            }
        }
    }

    /** Immediate admission/coalescing only; no operation result or existence is disclosed. */
    internal fun enqueueNativeOriginal(intake: NativeOriginalIntake,
        locator: NativeOriginalIntakeLocator): NativeOriginalIntakeSubmission {
        if (mutableHealth.value != LocalMasterEndpointHealth.ACTIVE || installationInitializer != null ||
            nativeOriginalIntake !== intake || !store.acceptsNativeOriginal(this, intake))
            return NativeOriginalIntakeSubmission.UNAVAILABLE
        while (true) {
            val current = nativeOriginalRequests.value
            if (locator.clientAllocationId in current) return NativeOriginalIntakeSubmission.QUEUED
            if (current.size >= maximumNativeOriginalRequests) return NativeOriginalIntakeSubmission.BUSY
            if (nativeOriginalRequests.compareAndSet(current, current + locator.clientAllocationId)) break
        }
        val work = NativeOriginalWork(intake, locator)
        if (!requests.trySend(work).isSuccess) {
            work.abandon()
            return if (mutableHealth.value == LocalMasterEndpointHealth.ACTIVE) NativeOriginalIntakeSubmission.BUSY
                else NativeOriginalIntakeSubmission.UNAVAILABLE
        }
        return NativeOriginalIntakeSubmission.QUEUED
    }

    /** Actual original owner only. STOP shares the same single worker as Room/native intake. */
    internal suspend fun authenticateAndStopOriginal(peer: LocalAuthenticatedPeer,
        login: LocalLoginIdentifier, password: CharArray,
        request: LocalMasterLifecycleRequest, diagnostics: OriginalLifecycleDiagnostics): LocalMasterLifecycleResult {
        currentCoroutineContext().ensureActive()
        if (request.action != LocalMasterLifecycleAction.STOP || lifecycle == null ||
            installationInitializer != null || !peer.isCurrent())
            return LocalMasterLifecycleResult.Unavailable
        if (password.size !in 4..1024)
            return LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.AUTHENTICATION_REFUSED)
        diagnostics.mark(OriginalLifecyclePhase.ENDPOINT_SUBMITTED)
        return submit(LifecycleWork(peer, login, password.copyOf(), request, diagnostics))
    }

    override public suspend fun signIn(
        connectionId: DomainId,
        login: LocalLoginIdentifier,
        password: CharArray,
        explicitDeviceMove: Boolean,
    ): LocalAuthenticationResult {
        currentCoroutineContext().ensureActive()
        if (password.size !in 4..1024) return LocalAuthenticationResult.Denied(
            com.ukkera.pos.security.LocalAuthenticationDenial.AUTHENTICATION_REFUSED)
        return submit(LoginWork(connectionId, login, password.copyOf(), explicitDeviceMove))
    }

    override public suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult =
        submit(RefreshWork(credentials.copyOwned()))

    override public suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        submit(CurrentWork(credentials.copyOwned()))

    /** A reply timeout is not native completion. Renewable RPC reads retain this exact
     * accepted work until its input cleanup, even if the caller stops awaiting its reply. */
    internal suspend fun currentAfterExecutionSettles(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        val work = CurrentWork(credentials.copyOwned())
        return try { submit(work) }
        finally { withContext(NonCancellable) { work.actualCleanup.await() } }
    }

    override public suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult =
        submit(LogoutWork(credentials.copyOwned()))

    override public suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply =
        submit(MutationWork(credentials.copyOwned(), request))

    internal suspend fun startPhysicalInstruction(credentials: LocalGatewayCredentials, request: MutationRequest,
        lifetime: PhysicalInteractiveRequestLifetime): PhysicalFreshStartReply = try {
        submit(PhysicalStartWork(credentials.copyOwned(), request, lifetime))
    } catch (failure: Throwable) { lifetime.deny(); throw failure }

    internal suspend fun publishPhysicalInstruction(credentials: LocalGatewayCredentials, offer: PhysicalInstructionOffer,
        lifetime: PhysicalInteractiveRequestLifetime): PhysicalInstructionReply = try {
        reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.PHYSICAL_INSTRUCTION_SUBMIT_ENTERED)
        val result = submit(PhysicalPublicationWork(credentials.copyOwned(), offer, lifetime))
        reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.PHYSICAL_INSTRUCTION_SUBMIT_RETURNED)
        result
    } catch (failure: Throwable) { offer.deny(); lifetime.deny(); throw failure }

    override public suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply =
        submit(LookupWork(credentials.copyOwned(), identity))

    override public suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply =
        submit(ReadWork(credentials.copyOwned(), request))

    /** Private retained-Host resource check uses the same supervised kernel and protected writer. */
    internal suspend fun authorizeRpcBusiness(credentials: LocalGatewayCredentials,
        access: LocalMasterRpcBusinessAccess, requireRecovery: Boolean,
        audience: RoomLocalDisplayRpcAudience? = null): LocalMasterRpcAccessResult =
        submit(RpcBusinessAuthorizationWork(credentials.copyOwned(), access, requireRecovery, audience))

    internal suspend fun readRpcBusiness(credentials: LocalGatewayCredentials, request: ReadRequest,
        audience: RoomLocalDisplayRpcAudience): ReadReply = submit(ReadWork(credentials.copyOwned(), request, audience))

    internal suspend fun executeRpcBusiness(credentials: LocalGatewayCredentials, request: MutationRequest,
        audience: RoomLocalDisplayRpcAudience): MutationReply = submit(MutationWork(credentials.copyOwned(), request, audience))

    internal suspend fun lookupRpcBusiness(credentials: LocalGatewayCredentials, identity: OperationIdentity,
        audience: RoomLocalDisplayRpcAudience): LookupReply = submit(LookupWork(credentials.copyOwned(), identity, audience))

    /** Server-computed confirmation for the original command without an approval selector. */
    override public suspend fun previewActionApproval(
        requestor: LocalGatewayCredentials, request: MutationRequest, retiredApprovalId: DomainId?,
    ): LocalActionApprovalPreviewResult =
        submit(ActionApprovalPreviewWork(requestor.copyOwned(), request, retiredApprovalId))

    /**
     * Retain the original approval ID, command and confirmation digest before submission.
     * Cancelling this wait does not cancel an accepted issuance; recover through the lookup.
     * Only this work's copied password/bearer are cleared, never the caller's originals.
     */
    override public suspend fun issueActionApproval(
        approvalId: DomainId,
        requestor: LocalGatewayCredentials,
        request: MutationRequest,
        confirmationDigest: PayloadDigest,
        approverLogin: LocalLoginIdentifier,
        approverPassword: CharArray,
        retiredApprovalId: DomainId?,
    ): LocalActionApprovalIssueResult {
        currentCoroutineContext().ensureActive()
        if (approverPassword.size !in 4..1024) return LocalActionApprovalIssueResult.Denied(
            LocalActionApprovalDenial.AUTHORIZATION_REFUSED)
        val password = approverPassword.copyOf()
        val work = try {
            val captured = requestor.copyOwned()
            try { ActionApprovalIssueWork(approvalId, captured, request, confirmationDigest, approverLogin, password, retiredApprovalId) }
            catch (failure: Throwable) { captured.bearer.destroy(); throw failure }
        } catch (failure: Throwable) { password.fill('\u0000'); throw failure }
        // Construction transferred ownership to Work; submit handles queue refusal/cancellation,
        // and accepted work retains its copies until the independent service worker finishes.
        return submit(work)
    }

    /** Historical receipt only; current requestor authorization remains mandatory. */
    override public suspend fun lookupActionApproval(
        approvalId: DomainId, requestor: LocalGatewayCredentials, confirmationDigest: PayloadDigest,
    ): LocalActionApprovalStatus =
        submit(ActionApprovalLookupWork(approvalId, requestor.copyOwned(), confirmationDigest))

    /** Explicit permanent retirement; cancellation of the wait retains the original lookup identity. */
    override public suspend fun retireActionApproval(
        approvalId: DomainId, requestor: LocalGatewayCredentials, request: MutationRequest,
        confirmationDigest: PayloadDigest,
    ): LocalActionApprovalRetireResult =
        submit(ActionApprovalRetireWork(approvalId, requestor.copyOwned(), request, confirmationDigest))

    override public suspend fun retireActionApprovalAttempt(
        attempt: LocalActionApprovalRetirementAttempt, requestor: LocalGatewayCredentials,
        request: MutationRequest,
    ): LocalActionApprovalRetireResult {
        currentCoroutineContext().ensureActive()
        if (!attempt.matches(request)) return LocalActionApprovalRetireResult.Denied
        return submit(ActionApprovalRetireWork(attempt.approvalId, requestor.copyOwned(), request,
            attempt.confirmationDigest, attempt))
    }

    /** The installed provisioning authority must authorize the complete request digest. */
    override public suspend fun provisionAccount(
        requestId: DomainId,
        definition: LocalAccountProvisioning,
        password: CharArray?,
    ): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (password != null && password.size !in 4..1024) return LocalCredentialAdministrationResult.Denied
        return submit(ProvisionAccountWork(requestId, definition, password?.copyOf()))
    }

    /** Closed original-service work, never the ordinary request-ID provisioning endpoint.
     * A watchdog response is NOT actual completion. Retain this exact worker's cleanup wait,
     * including its non-cancellable actual Room/native completion, before returning to service. */
    internal suspend fun provisionOriginalEmployee(bridge: RoomSelfOwnedEmployeeEnrollments,
        dispatch: NativeLocalEmployeeEnrollmentDispatch, definition: LocalAccountProvisioning,
        password: CharArray?): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (originalEmployeeEnrollments.value !== bridge || credentials !== bridge.administration ||
            password != null && password.size !in 4..1024) return LocalCredentialAdministrationResult.Unavailable
        val work = OriginalEmployeeProvisionWork(bridge, dispatch, definition, password?.copyOf())
        return try { submit(work) } finally { withContext(NonCancellable) { work.actualCleanup.await() } }
    }

    internal suspend fun lookupOriginalEmployee(bridge: RoomSelfOwnedEmployeeEnrollments, requestId: DomainId,
        definition: LocalAccountProvisioning, credentialWasProvided: Boolean): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (originalEmployeeEnrollments.value !== bridge || credentials !== bridge.administration) return LocalProvisioningStatus.Unavailable
        val work = OriginalEmployeeLookupWork(bridge, requestId, definition, credentialWasProvided)
        return try { submit(work) } finally { withContext(NonCancellable) { work.actualCleanup.await() } }
    }
    internal suspend fun activateOriginalEmployeePolicy(bridge: RoomSelfOwnedEmployeePolicyActivations,
        dispatch: NativeLocalEmployeePolicyActivationDispatch, nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (originalEmployeePolicyActivations.value !== bridge || credentials !== bridge.administration)
            return LocalCredentialAdministrationResult.Unavailable
        val work = OriginalEmployeePolicyWork(bridge, dispatch, nextPolicy)
        return try { submit(work) } finally { withContext(NonCancellable) { work.actualCleanup.await() } }
    }
    internal suspend fun lookupOriginalEmployeePolicy(bridge: RoomSelfOwnedEmployeePolicyActivations, requestId: DomainId,
        nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (originalEmployeePolicyActivations.value !== bridge || credentials !== bridge.administration)
            return LocalProvisioningStatus.Unavailable
        val work = OriginalEmployeePolicyLookupWork(bridge, requestId, nextPolicy)
        return try { submit(work) } finally { withContext(NonCancellable) { work.actualCleanup.await() } }
    }
    internal suspend fun admitOriginalDevice(bridge: RoomSelfOwnedDeviceAdmissions,
        dispatch: NativeLocalDeviceAdmissionDispatch, admission: LocalDeviceAdmission): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (originalDeviceAdmissions.value !== bridge || credentials !== bridge.administration)
            return LocalCredentialAdministrationResult.Unavailable
        val work = OriginalDeviceAdmissionWork(bridge, dispatch, admission)
        return try { submit(work) } finally { withContext(NonCancellable) { work.actualCleanup.await() } }
    }
    internal suspend fun lookupOriginalDeviceAdmission(bridge: RoomSelfOwnedDeviceAdmissions,
        originalActionId: DomainId): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (originalDeviceAdmissions.value !== bridge || credentials !== bridge.administration)
            return LocalProvisioningStatus.Unavailable
        val work = OriginalDeviceAdmissionLookupWork(bridge, originalActionId)
        return try { submit(work) } finally { withContext(NonCancellable) { work.actualCleanup.await() } }
    }

    public suspend fun admitDevice(requestId: DomainId, admission: LocalDeviceAdmission): LocalCredentialAdministrationResult =
        submit(AdmitDeviceWork(requestId, admission))

    public suspend fun revokeDevice(
        operator: LocalGatewayCredentials, requestId: DomainId, deviceId: DomainId, expectedRevision: Revision,
    ): LocalCredentialAdministrationResult =
        submit(RevokeDeviceWork(operator.copyOwned(), requestId, deviceId, expectedRevision))

    override public suspend fun lookupAccountProvisioning(requestId: DomainId, definition: LocalAccountProvisioning,
        credentialWasProvided: Boolean): LocalProvisioningStatus =
        submit(AccountProvisioningLookupWork(requestId, definition, credentialWasProvided))

    public suspend fun lookupDeviceAdmission(requestId: DomainId, admission: LocalDeviceAdmission): LocalProvisioningStatus =
        submit(DeviceAdmissionLookupWork(requestId, admission))

    public suspend fun activatePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult =
        submit(ActivatePolicyWork(requestId, nextPolicy))

    public suspend fun lookupPolicyActivation(requestId: DomainId, nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus =
        submit(PolicyActivationLookupWork(requestId, nextPolicy))

    public suspend fun reactivateAccount(requestId: DomainId, target: LocalAccountReactivation): LocalCredentialAdministrationResult =
        submit(ReactivateAccountWork(requestId, target))

    public suspend fun lookupAccountReactivation(requestId: DomainId, target: LocalAccountReactivation): LocalProvisioningStatus =
        submit(AccountReactivationLookupWork(requestId, target))

    public suspend fun resetCredential(
        operator: LocalGatewayCredentials, actorId: DomainId, expectedRevision: Revision, newPassword: CharArray,
    ): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (newPassword.size !in 4..1024) return LocalCredentialAdministrationResult.Denied
        return submit(ResetCredentialWork(operator.copyOwned(), actorId, expectedRevision, newPassword.copyOf()))
    }

    public suspend fun revokeSession(
        operator: LocalGatewayCredentials, actorId: DomainId, sessionId: DomainId, expectedRevision: Revision,
    ): LocalCredentialAdministrationResult =
        submit(RevokeSessionWork(operator.copyOwned(), actorId, sessionId, expectedRevision))

    public suspend fun disableAccount(
        operator: LocalGatewayCredentials, actorId: DomainId, expectedRevision: Revision,
    ): LocalCredentialAdministrationResult =
        submit(DisableAccountWork(operator.copyOwned(), actorId, expectedRevision))

    override public suspend fun employeeCreationContext(operator: LocalGatewayCredentials,
        target: LocalEmployeeCreationTargetV1): LocalEmployeeCreationContextResultV1 =
        submit(EmployeeCreationContextWork(operator.copyOwned(), target))

    override public suspend fun captureEmployeeCreationDefinition(operator: LocalGatewayCredentials,
        draft: LocalEmployeeCreationDraftV1): LocalEmployeeCreationDefinitionResultV1 =
        submit(EmployeeCreationDefinitionWork(operator.copyOwned(), draft))

    /** Queue only this exact original. The actual service still owns protected writer/native
     * admission; a missing/incomplete provider retains the interface's unavailable default. */
    override public suspend fun executeEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1, newPassword: CharArray?): LocalEmployeeCreationExecutionV1 {
        currentCoroutineContext().ensureActive()
        if (original.copyRequestedDefinition().credentialWasProvided != (newPassword != null) ||
            newPassword != null && newPassword.size !in 4..1024) return LocalEmployeeCreationExecutionV1.Denied
        val owned = operator.copyOwned()
        var password: CharArray? = null
        var submitted = false
        try {
            password = newPassword?.copyOf()
            val request = EmployeeCreationExecutionWork(owned, original, password)
            submitted = true // submit disposes inputs on rejection as well as actual completion.
            return submit(request)
        } finally { if (!submitted) { owned.bearer.destroy(); password?.fill('\u0000') } }
    }

    override public suspend fun cancelEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationCancellationV1 =
        submit(EmployeeCreationCancellationWork(operator.copyOwned(), original))

    override public suspend fun reviewEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationReviewV1 =
        submit(EmployeeCreationReviewWork(operator.copyOwned(), original))

    /** Queue the exact original read through the operational service owner. The service
     * still authenticates the original creator and current target-bound authority; no
     * lookup result can dispatch creation or settle a pending native child. */
    override public suspend fun lookupEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationStatusV1 =
        submit(EmployeeCreationLookupWork(operator.copyOwned(), original))

    override public suspend fun readAdministration(operator: LocalGatewayCredentials, query: LocalAdministrationQuery): LocalAdministrationReadResult =
        submit(AdministrationReadWork(operator.copyOwned(), query))

    override public suspend fun lookupAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        submit(AdministrationLookupWork(operator.copyOwned(), command))

    override public suspend fun cancelAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        submit(AdministrationLookupWork(operator.copyOwned(), command, cancel = true))

    override public suspend fun administer(operator: LocalGatewayCredentials, command: LocalAdministrationCommand,
        newPassword: CharArray?): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (newPassword != null && newPassword.size !in 4..1024) return LocalCredentialAdministrationResult.Denied
        val owned = operator.copyOwned()
        var password: CharArray? = null
        var submitted = false
        try {
            password = newPassword?.copyOf()
            val request = AdministrationWork(owned, command, password)
            submitted = true // submit owns/disposes every admitted and refused work item.
            return submit(request)
        } finally { if (!submitted) { owned.bearer.destroy(); password?.fill('\u0000') } }
    }

    override public suspend fun retainedSerialPublications(credentials: LocalGatewayCredentials):
        LocalCompanyAuthorityResult<List<LocalSerialPrivatePublicationReference>> = submit(SerialRetainedWork(credentials.copyOwned()))

    override public suspend fun prepareSerialAuthority(credentials: LocalGatewayCredentials, requestId: DomainId):
        LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> = submit(SerialAuthorityWork(credentials.copyOwned(), requestId))

    override public suspend fun prepareSerialPublication(credentials: LocalGatewayCredentials, requestId: DomainId,
        proposal: CompanyBusinessPublicationProposal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> {
        currentCoroutineContext().ensureActive()
        if (proposal.kind !in setOf(CompanyBusinessPublicationKind.SERIAL_AUTHORITY_V1, CompanyBusinessPublicationKind.SERIAL_REGISTRATION_V1))
            return serialUnavailable(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        val owned = credentials.copyOwned()
        var bytes: ByteArray? = null
        var submitted = false
        try {
            val content = proposal.copyCanonicalContent(); bytes = content
            val work = SerialPrepareWork(owned, requestId, proposal.company, proposal.kind, proposal.publicationId, proposal.entityId, content)
            submitted = true
            return submit(work)
        } finally { if (!submitted) { owned.bearer.destroy(); bytes?.fill(0) } }
    }

    override public suspend fun reviewSerialPublication(credentials: LocalGatewayCredentials, requestId: DomainId,
        originRequestId: DomainId, originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
        submit(SerialReviewWork(credentials.copyOwned(), requestId, originRequestId, originalActionId))

    override public suspend fun recoverSerialPublication(credentials: LocalGatewayCredentials, requestId: DomainId,
        originRequestId: DomainId, originalActionId: DomainId): LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> =
        submit(SerialRecoverWork(credentials.copyOwned(), requestId, originRequestId, originalActionId))

    private suspend fun submitInstallation(
        definition: LocalBranchInstallationDefinition, password: CharArray?,
    ): LocalBranchInstallationResult {
        currentCoroutineContext().ensureActive()
        if (installationInitializer == null || mutableHealth.value != LocalMasterEndpointHealth.ACTIVE) {
            return LocalBranchInstallationResult.Unavailable
        }
        if (password != null && password.size !in 4..1024) return LocalBranchInstallationResult.Denied
        if (!installationSlot.tryAcquire()) return if (mutableHealth.value == LocalMasterEndpointHealth.ACTIVE) {
            LocalBranchInstallationResult.Busy
        } else LocalBranchInstallationResult.Unavailable
        var ownedPassword: CharArray? = null
        var work: InstallationWork? = null
        try {
            ownedPassword = password?.copyOf()
            // Pure bounded capture only, BEFORE enqueue and outside every service/provider
            // call. The worker receives no caller-owned definition, collection or password.
            val captured = try { LocalBranchInstallationCodec.snapshotDefinition(definition) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                return LocalBranchInstallationResult.Denied
            }
            currentCoroutineContext().ensureActive()
            work = InstallationWork(captured, ownedPassword)
        } finally {
            if (work == null) {
                ownedPassword?.fill('\u0000')
                installationSlot.release()
            }
        }
        // Ownership transferred to Work. From here only rejection/actual work cleanup releases
        // the permit, never abandonment of this caller's wait on accepted work.
        return submit(checkNotNull(work))
    }

    /**
     * Stop accepting work immediately; queued inputs are cleared. In-flight requests are allowed
     * to finish/recover; an external maintenance owner must await actual quiescence BEFORE closing
     * the DB or releasing custody. This method never claims that the database is already closed.
     */
    public fun stopAccepting() {
        // Deny fresh financial continuations before any close/cancellation/owner side effects.
        // Incurred native facts/obligations remain retained; this does not erase or reclaim them.
        store.denyPhysicalRecording()
        if (mutableHealth.compareAndSet(LocalMasterEndpointHealth.ACTIVE, LocalMasterEndpointHealth.STOPPING)) {
            nativeOriginalScanScheduler?.cancel() // scheduling only; do not cancel accepted native custody work
            requests.close()
        }
    }

    /** Trusted grouped owner only; a close watchdog must also suppress late worker replies. */
    internal fun retireFromOwner(reason: LocalMasterRetirementReason, afterMarked: () -> Unit): Unit = retire(reason, afterMarked)

    public suspend fun awaitQuiescence() {
        check(mutableHealth.value != LocalMasterEndpointHealth.ACTIVE) { "Stop ingress before awaiting quiescence" }
        for (job in workerJobs) job.join()
        nativeOriginalScanScheduler?.join()
        check(mutableHealth.value != LocalMasterEndpointHealth.RETIRED) { "Retired service requires OS-level retirement" }
        watchdogJob.cancel()
        serviceJob.cancel()
    }

    private suspend fun <T> submit(work: Work<T>): T {
        var dispatched = false
        try {
            currentCoroutineContext().ensureActive()
            if (mutableHealth.value != LocalMasterEndpointHealth.ACTIVE || work.kind != admittedWork ||
                        (work.kind==WorkKind.OPERATIONAL && !store.allowsOperationalIngress())) {
                work.abandon()
                return work.notAdmitted(LocalActionApprovalNoAdmissionReason.ENDPOINT_UNAVAILABLE)
            }
            if (!requests.trySend(work).isSuccess) {
                work.abandon()
                return work.notAdmitted(if (mutableHealth.value == LocalMasterEndpointHealth.ACTIVE)
                    LocalActionApprovalNoAdmissionReason.QUEUE_FULL else LocalActionApprovalNoAdmissionReason.ENDPOINT_UNAVAILABLE)
            }
            dispatched = true
            return work.response.receive()
        } catch (cancelled: CancellationException) {
            // Never cancel a dispatched command merely because its reply recipient disappeared.
            // Channel's undelivered hook also covers prompt cancellation after a response was
            // offered but before receive resumed. An abandoned access/refresh pair is destroyed.
            // For the fixed physical work kinds, withdraw original ownership synchronously
            // BEFORE response cancellation/undelivered hooks or any asynchronous UI cleanup.
            // Ordinary accepted command execution remains independent of its caller's wait.
            work.onWaitAbandoned()
            work.response.cancel(cancelled)
            if (!dispatched) work.abandon()
            throw cancelled
        }
    }

    private fun retire(reason: LocalMasterRetirementReason, afterMarked: (() -> Unit)? = null) {
        store.denyPhysicalRecording()
        if (mutableHealth.getAndSetRetired()) {
            // Arrange physical supervision before cancellation hooks or response receivers can
            // run synchronously. Neither Job.cancel nor a continuation is an OS-retirement fence.
            try { retirement.retire(reason) } catch (_: Throwable) { /* remain retired */ }
            try { afterMarked?.invoke() } finally {
                requests.cancel(CancellationException("Local master retired"))
                serviceJob.cancel(CancellationException("Local master retired"))
            }
        } else afterMarked?.invoke()
    }

    private abstract inner class Work<T> {
        open val kind: WorkKind get() = WorkKind.OPERATIONAL
        val response = Channel<T>(1, onUndeliveredElement = { discard(it) })
        abstract fun unavailable(): T
        open fun busy(): T = unavailable()
        /** Called only after submit proves this Work was never accepted by the request queue. */
        open fun notAdmitted(reason: LocalActionApprovalNoAdmissionReason): T =
            if (reason == LocalActionApprovalNoAdmissionReason.QUEUE_FULL) busy() else unavailable()
        open fun denied(): T = unavailable()
        abstract suspend fun perform(): T
        open suspend fun performOwned(physicalIngress: PhysicalRecordingOwnedIngress): T = perform()
        open fun clearInput() {}
        open fun discard(value: T) {}
        open fun uncertain(value: T): Boolean = false
        open fun onWaitAbandoned() {}

        fun abandon() {
            onWaitAbandoned()
            clearInput()
            respond(unavailable())
        }

        private fun respond(value: T) {
            // trySend failure does not call onUndeliveredElement: retain explicit ownership.
            if (!response.trySend(value).isSuccess) discard(value)
            response.close()
        }

        suspend fun run(physicalIngress: PhysicalRecordingOwnedIngress) {
            // Separate completion state: cancellation of a reply recipient is NOT completion of
            // an in-flight financial request. Conversely, a completed request cannot later have
            // its cancelled watchdog retire an otherwise healthy service.
            val executionFinished = MutableStateFlow(false)
            val watchdog = watchdogScope.launch {
                delay(maximumRequestMillis)
                if (executionFinished.compareAndSet(false, true)) {
                    retire(LocalMasterRetirementReason.REQUEST_DEADLINE)
                    respond(unavailable())
                }
            }
            try {
                val value = performOwned(physicalIngress)
                if (!executionFinished.compareAndSet(false, true)) {
                    discard(value)
                } else if (uncertain(value)) {
                    retire(LocalMasterRetirementReason.STORAGE_UNCERTAIN)
                    discard(value)
                    respond(unavailable())
                } else if (mutableHealth.value == LocalMasterEndpointHealth.RETIRED) {
                    discard(value)
                    respond(unavailable())
                } else respond(value)
            } catch (cancelled: CancellationException) {
                // The caller's job was never inherited. Cancellation here originated within
                // the service/provider or retirement, not from a disconnected reply recipient.
                // Do not silently lose a worker while leaving a possibly poisoned pool ACTIVE.
                retire(LocalMasterRetirementReason.STORAGE_UNCERTAIN)
                respond(unavailable())
                throw cancelled
            } catch (_: RoomLocalAccessVerificationStorageUncertain) {
                retire(LocalMasterRetirementReason.STORAGE_UNCERTAIN)
                respond(unavailable())
            } catch (_: RoomLocalAccessVerificationBusy) {
                // Saturation is not an invalid password, a COMMIT failure or a poisoned pool.
                if (executionFinished.compareAndSet(false, true)) respond(busy())
            } catch (_: RoomLocalAccessVerificationUnavailable) {
                // Normal peer/eligibility refusal after a known-clean authentication boundary
                // must not take down the branch writer. No principal or command was dispatched.
                if (executionFinished.compareAndSet(false, true)) respond(denied())
            } catch (_: Throwable) {
                retire(LocalMasterRetirementReason.UNEXPECTED_SERVICE_FAILURE)
                respond(unavailable())
            } finally {
                executionFinished.compareAndSet(false, true)
                watchdog.cancel()
                clearInput()
            }
        }
    }

    private inner class InstallationWork(
        val definition: LocalBranchInstallationDefinition, val password: CharArray?,
    ) : Work<LocalBranchInstallationResult>() {
        override val kind: WorkKind get() = WorkKind.INSTALLATION
        private val cleared = MutableStateFlow(false)
        override fun unavailable(): LocalBranchInstallationResult = LocalBranchInstallationResult.Unavailable
        override fun busy(): LocalBranchInstallationResult = LocalBranchInstallationResult.Busy
        override fun denied(): LocalBranchInstallationResult = LocalBranchInstallationResult.Denied
        override suspend fun perform(): LocalBranchInstallationResult {
            val initializer = checkNotNull(installationInitializer)
            return if (password == null) initializer.lookup(definition) else initializer.initialize(definition, password)
        }
        override fun clearInput() {
            if (cleared.compareAndSet(false, true)) {
                password?.fill('\u0000')
                installationSlot.release()
            }
        }
        override fun uncertain(value: LocalBranchInstallationResult): Boolean = value is LocalBranchInstallationResult.StorageUncertain
    }

    private inner class NativeOriginalScanWork(val intake: NativeOriginalIntake) : Work<NativeOriginalWorkerResult>() {
        private val cleared = MutableStateFlow(false)
        override fun unavailable(): NativeOriginalWorkerResult = NativeOriginalWorkerResult.HELD
        override suspend fun perform(): NativeOriginalWorkerResult = NativeOriginalWorkerResult.HELD
        override suspend fun performOwned(physicalIngress: PhysicalRecordingOwnedIngress): NativeOriginalWorkerResult =
            store.enqueueRetainedNativeOriginals(intake, physicalIngress)
        override fun clearInput() { if (cleared.compareAndSet(false, true)) nativeOriginalScanQueued.value = false }
        override fun uncertain(value: NativeOriginalWorkerResult): Boolean = value == NativeOriginalWorkerResult.STORAGE_UNCERTAIN
    }

    private inner class NativeOriginalWork(val intake: NativeOriginalIntake,
        val locator: NativeOriginalIntakeLocator) : Work<NativeOriginalWorkerResult>() {
        private val cleared = MutableStateFlow(false)
        override fun unavailable(): NativeOriginalWorkerResult = NativeOriginalWorkerResult.HELD
        override suspend fun perform(): NativeOriginalWorkerResult = NativeOriginalWorkerResult.HELD
        override suspend fun performOwned(physicalIngress: PhysicalRecordingOwnedIngress): NativeOriginalWorkerResult =
            store.importNativeOriginalFromOwnedIngress(intake, locator, physicalIngress)
        override fun clearInput() {
            if (!cleared.compareAndSet(false, true)) return
            // Not caller cancellation: clear only after queue refusal/abandonment or actual
            // independent Work cleanup. Retry follows the still-retained native catalogue.
            while (true) {
                val current = nativeOriginalRequests.value
                if (nativeOriginalRequests.compareAndSet(current, current - locator.clientAllocationId)) return
            }
        }
        override fun uncertain(value: NativeOriginalWorkerResult): Boolean = value == NativeOriginalWorkerResult.STORAGE_UNCERTAIN
    }

    private inner class LifecycleWork(
        val peer: LocalAuthenticatedPeer, val login: LocalLoginIdentifier,
        val password: CharArray, val request: LocalMasterLifecycleRequest,
        val diagnostics: OriginalLifecycleDiagnostics,
    ) : Work<LocalMasterLifecycleResult>() {
        override fun unavailable(): LocalMasterLifecycleResult = LocalMasterLifecycleResult.Unavailable
        override fun busy(): LocalMasterLifecycleResult = LocalMasterLifecycleResult.Busy
        override suspend fun perform(): LocalMasterLifecycleResult = authenticateOriginalLifecycle(
            authentication, checkNotNull(lifecycle), peer, login, password, request, diagnostics)
        override fun clearInput() { password.fill('\u0000') }
        override fun uncertain(value: LocalMasterLifecycleResult): Boolean =
            value is LocalMasterLifecycleResult.StorageUncertain
    }

    private inner class LoginWork(
        val connectionId: DomainId, val login: LocalLoginIdentifier,
        val password: CharArray, val move: Boolean,
    ) : Work<LocalAuthenticationResult>() {
        override fun unavailable(): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
        override fun busy(): LocalAuthenticationResult = LocalAuthenticationResult.Denied(LocalAuthenticationDenial.SERVICE_BUSY)
        override suspend fun perform(): LocalAuthenticationResult = authentication.signIn(connectionId, login, password, move)
        override fun clearInput() { password.fill('\u0000') }
        override fun discard(value: LocalAuthenticationResult) { value.destroyTokensIfPresent() }
        override fun uncertain(value: LocalAuthenticationResult): Boolean = value is LocalAuthenticationResult.StorageUncertain
    }

    private inner class RefreshWork(val credentials: LocalGatewayCredentials) : Work<LocalAuthenticationResult>() {
        override fun unavailable(): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
        override fun busy(): LocalAuthenticationResult = LocalAuthenticationResult.Denied(LocalAuthenticationDenial.SERVICE_BUSY)
        override suspend fun perform(): LocalAuthenticationResult = authentication.refresh(credentials)
        override fun clearInput() { credentials.bearer.destroy() }
        override fun discard(value: LocalAuthenticationResult) { value.destroyTokensIfPresent() }
        override fun uncertain(value: LocalAuthenticationResult): Boolean = value is LocalAuthenticationResult.StorageUncertain
    }

    private inner class CurrentWork(val credentials: LocalGatewayCredentials) : Work<LocalSessionViewResult>() {
        val actualCleanup = CompletableDeferred<Unit>()
        override fun unavailable(): LocalSessionViewResult = LocalSessionViewResult.Unavailable
        override fun busy(): LocalSessionViewResult = LocalSessionViewResult.Busy
        override suspend fun perform(): LocalSessionViewResult = authentication.current(credentials)
        override fun clearInput() { credentials.bearer.destroy(); actualCleanup.complete(Unit) }
        override fun uncertain(value: LocalSessionViewResult): Boolean = value is LocalSessionViewResult.StorageUncertain
    }

    private inner class LogoutWork(val credentials: LocalGatewayCredentials) : Work<LocalSignOutResult>() {
        override fun unavailable(): LocalSignOutResult = LocalSignOutResult.Unavailable
        override fun busy(): LocalSignOutResult = LocalSignOutResult.Busy
        override suspend fun perform(): LocalSignOutResult = authentication.logout(credentials)
        override fun clearInput() { credentials.bearer.destroy() }
        override fun uncertain(value: LocalSignOutResult): Boolean = value is LocalSignOutResult.StorageUncertain
    }

    private inner class MutationWork(val credentials: LocalGatewayCredentials, val request: MutationRequest,
        val audience: RoomLocalDisplayRpcAudience? = null) : Work<MutationReply>() {
        override fun unavailable(): MutationReply = MutationReply.Deferred(request.operationId, DeferredReason.STORAGE_UNCERTAIN)
        override fun busy(): MutationReply = MutationReply.Deferred(request.operationId, DeferredReason.SERVICE_BUSY)
        override fun denied(): MutationReply = MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)
        override suspend fun perform(): MutationReply = if (audience == null)
            store.execute(principals.authenticateLocal(credentials), request)
            else store.executeRpcBusiness(principals.authenticateLocal(credentials), request, audience)
        override suspend fun performOwned(physicalIngress: PhysicalRecordingOwnedIngress): MutationReply =
            if (audience == null) store.executeFromOwnedIngress(principals.authenticateLocal(credentials), request, physicalIngress)
            else store.executeRpcBusiness(principals.authenticateLocal(credentials), request, audience, physicalIngress)
        override fun clearInput() { credentials.bearer.destroy() }
        override fun discard(value: MutationReply) {
            (value as? MutationReply.Recorded)?.physicalStart?.denyDelivery()
        }
        override fun uncertain(value: MutationReply): Boolean = value is MutationReply.Deferred && value.reason == DeferredReason.STORAGE_UNCERTAIN
    }

    /** Reuses the ACTUAL bounded clean worker, not a client-side planner or a new scope. */
    private inner class PhysicalStartWork(val credentials: LocalGatewayCredentials, val request: MutationRequest,
        val lifetime: PhysicalInteractiveRequestLifetime) : Work<PhysicalFreshStartReply>() {
        private fun refused(reason: DeferredReason): PhysicalFreshStartReply {
            lifetime.deny()
            return PhysicalFreshStartReply(MutationReply.Deferred(request.operationId, reason), null)
        }
        override fun unavailable(): PhysicalFreshStartReply = refused(DeferredReason.STORAGE_UNCERTAIN)
        override fun busy(): PhysicalFreshStartReply = refused(DeferredReason.SERVICE_BUSY)
        override fun denied(): PhysicalFreshStartReply = refused(DeferredReason.AUTHORIZATION_DENIED)
        override suspend fun perform(): PhysicalFreshStartReply = refused(DeferredReason.PHYSICAL_RECORDING_HELD)
        override suspend fun performOwned(physicalIngress: PhysicalRecordingOwnedIngress): PhysicalFreshStartReply {
            reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.PHYSICAL_START_AUTHENTICATION_ENTERED)
            val principal = principals.authenticateLocal(credentials)
            reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.PHYSICAL_START_AUTHENTICATION_RETURNED)
            return store.startPhysicalFromOwnedIngress(principal, request, lifetime, physicalIngress)
        }
        override fun onWaitAbandoned() { lifetime.deny() }
        override fun clearInput() { credentials.bearer.destroy() }
        override fun discard(value: PhysicalFreshStartReply) {
            lifetime.deny()
            value.offer?.deny()
            (value.recordedOrDeferred as? MutationReply.Recorded)?.physicalStart?.denyDelivery()
        }
        override fun uncertain(value: PhysicalFreshStartReply): Boolean =
            (value.recordedOrDeferred as? MutationReply.Deferred)?.reason == DeferredReason.STORAGE_UNCERTAIN
    }

    private inner class PhysicalPublicationWork(val credentials: LocalGatewayCredentials, val offer: PhysicalInstructionOffer,
        val lifetime: PhysicalInteractiveRequestLifetime) : Work<PhysicalInstructionReply>() {
        override fun unavailable(): PhysicalInstructionReply {
            onWaitAbandoned()
            return PhysicalInstructionReply.StorageUncertain
        }
        override fun busy(): PhysicalInstructionReply = denied()
        override fun denied(): PhysicalInstructionReply {
            onWaitAbandoned()
            return PhysicalInstructionReply.Held
        }
        override suspend fun perform(): PhysicalInstructionReply = denied()
        override suspend fun performOwned(physicalIngress: PhysicalRecordingOwnedIngress): PhysicalInstructionReply {
            reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.PHYSICAL_INSTRUCTION_AUTHENTICATION_ENTERED)
            val principal = principals.authenticateLocal(credentials)
            reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.PHYSICAL_INSTRUCTION_AUTHENTICATION_RETURNED)
            return store.publishPhysicalFromOwnedIngress(principal, offer, lifetime, physicalIngress)
        }
        override fun onWaitAbandoned() { offer.deny(); lifetime.deny() }
        override fun clearInput() { credentials.bearer.destroy() }
        override fun discard(value: PhysicalInstructionReply) {
            onWaitAbandoned()
            (value as? PhysicalInstructionReply.Published)?.instruction?.deny()
        }
        override fun uncertain(value: PhysicalInstructionReply): Boolean = value is PhysicalInstructionReply.StorageUncertain
    }

    private inner class LookupWork(val credentials: LocalGatewayCredentials, val identity: OperationIdentity,
        val audience: RoomLocalDisplayRpcAudience? = null) : Work<LookupReply>() {
        override fun unavailable(): LookupReply = LookupReply.Deferred(identity.operationId, DeferredReason.STORAGE_UNCERTAIN)
        override fun busy(): LookupReply = LookupReply.Deferred(identity.operationId, DeferredReason.SERVICE_BUSY)
        override fun denied(): LookupReply = LookupReply.Deferred(identity.operationId, DeferredReason.AUTHORIZATION_DENIED)
        override suspend fun perform(): LookupReply = if (audience == null)
            store.lookup(principals.authenticateLocal(credentials), identity)
            else store.lookupRpcBusiness(principals.authenticateLocal(credentials), identity, audience)
        override fun clearInput() { credentials.bearer.destroy() }
        override fun uncertain(value: LookupReply): Boolean = value is LookupReply.Deferred && value.reason == DeferredReason.STORAGE_UNCERTAIN
    }

    private inner class RpcBusinessAuthorizationWork(val credentials: LocalGatewayCredentials,
        val access: LocalMasterRpcBusinessAccess, val requireRecovery: Boolean,
        val audience: RoomLocalDisplayRpcAudience?) : Work<LocalMasterRpcAccessResult>() {
        override fun unavailable(): LocalMasterRpcAccessResult = LocalMasterRpcAccessResult.UNAVAILABLE
        override fun denied(): LocalMasterRpcAccessResult = LocalMasterRpcAccessResult.DENIED
        override suspend fun perform(): LocalMasterRpcAccessResult =
            store.authorizeRpcBusiness(principals.authenticateLocal(credentials), access, requireRecovery, audience)
        override fun clearInput() { credentials.bearer.destroy() }
        override fun uncertain(value: LocalMasterRpcAccessResult): Boolean = value == LocalMasterRpcAccessResult.UNAVAILABLE
    }

    private inner class ReadWork(val credentials: LocalGatewayCredentials, val request: ReadRequest,
        val audience: RoomLocalDisplayRpcAudience? = null) : Work<ReadReply>() {
        override fun unavailable(): ReadReply = ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN)
        override fun busy(): ReadReply = ReadReply.Unavailable(ReadFailure.SERVICE_BUSY)
        override fun denied(): ReadReply = ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        override suspend fun perform(): ReadReply = if (audience == null)
            store.read(principals.authenticateLocal(credentials), request)
            else store.readRpcBusiness(principals.authenticateLocal(credentials), request, audience)
        override fun clearInput() { credentials.bearer.destroy() }
        override fun uncertain(value: ReadReply): Boolean = value is ReadReply.Unavailable && value.reason == ReadFailure.STORAGE_UNCERTAIN
    }

    private inner class ActionApprovalPreviewWork(
        val requestor: LocalGatewayCredentials, val request: MutationRequest, val retiredApprovalId: DomainId?,
    ) : Work<LocalActionApprovalPreviewResult>() {
        override fun unavailable(): LocalActionApprovalPreviewResult = LocalActionApprovalPreviewResult.Unavailable
        override fun busy(): LocalActionApprovalPreviewResult = LocalActionApprovalPreviewResult.Busy
        override fun denied(): LocalActionApprovalPreviewResult = LocalActionApprovalPreviewResult.Denied
        override suspend fun perform(): LocalActionApprovalPreviewResult = approvals.preview(requestor, request, retiredApprovalId)
        override fun clearInput() { requestor.bearer.destroy() }
        override fun uncertain(value: LocalActionApprovalPreviewResult): Boolean = value is LocalActionApprovalPreviewResult.StorageUncertain
    }

    private inner class ActionApprovalIssueWork(
        val approvalId: DomainId, val requestor: LocalGatewayCredentials, val request: MutationRequest,
        val confirmationDigest: PayloadDigest, val approverLogin: LocalLoginIdentifier, val password: CharArray,
        val retiredApprovalId: DomainId?,
    ) : Work<LocalActionApprovalIssueResult>() {
        override fun unavailable(): LocalActionApprovalIssueResult = LocalActionApprovalIssueResult.Unavailable
        override fun busy(): LocalActionApprovalIssueResult = LocalActionApprovalIssueResult.Busy
        override fun denied(): LocalActionApprovalIssueResult = LocalActionApprovalIssueResult.Denied(LocalActionApprovalDenial.AUTHORIZATION_REFUSED)
        override suspend fun perform(): LocalActionApprovalIssueResult =
            approvals.issue(approvalId, requestor, request, confirmationDigest, approverLogin, password, retiredApprovalId)
        override fun clearInput() { requestor.bearer.destroy(); password.fill('\u0000') }
        override fun uncertain(value: LocalActionApprovalIssueResult): Boolean = value is LocalActionApprovalIssueResult.StorageUncertain
    }

    private inner class ActionApprovalLookupWork(
        val approvalId: DomainId, val requestor: LocalGatewayCredentials, val confirmationDigest: PayloadDigest,
    ) : Work<LocalActionApprovalStatus>() {
        override fun unavailable(): LocalActionApprovalStatus = LocalActionApprovalStatus.Unavailable
        override fun busy(): LocalActionApprovalStatus = LocalActionApprovalStatus.Busy
        override fun denied(): LocalActionApprovalStatus = LocalActionApprovalStatus.Denied
        override suspend fun perform(): LocalActionApprovalStatus = approvals.lookup(approvalId, requestor, confirmationDigest)
        override fun clearInput() { requestor.bearer.destroy() }
        override fun uncertain(value: LocalActionApprovalStatus): Boolean = value is LocalActionApprovalStatus.StorageUncertain
    }

    private inner class ActionApprovalRetireWork(
        val approvalId: DomainId, val requestor: LocalGatewayCredentials, val request: MutationRequest,
        val confirmationDigest: PayloadDigest,
        val attempt: LocalActionApprovalRetirementAttempt? = null,
    ) : Work<LocalActionApprovalRetireResult>() {
        override fun unavailable(): LocalActionApprovalRetireResult = LocalActionApprovalRetireResult.Unavailable
        override fun busy(): LocalActionApprovalRetireResult = LocalActionApprovalRetireResult.Busy
        override fun denied(): LocalActionApprovalRetireResult = LocalActionApprovalRetireResult.Denied
        override fun notAdmitted(reason: LocalActionApprovalNoAdmissionReason): LocalActionApprovalRetireResult =
            attempt?.let { LocalActionApprovalRetireResult.NotAdmitted(it, reason) } ?: super.notAdmitted(reason)
        override suspend fun perform(): LocalActionApprovalRetireResult =
            approvals.retire(approvalId, requestor, request, confirmationDigest)
        override fun clearInput() { requestor.bearer.destroy() }
        override fun uncertain(value: LocalActionApprovalRetireResult): Boolean = value is LocalActionApprovalRetireResult.StorageUncertain
    }

    private abstract inner class CredentialWork : Work<LocalCredentialAdministrationResult>() {
        override fun unavailable(): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Unavailable
        override fun busy(): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Busy
        override fun uncertain(value: LocalCredentialAdministrationResult): Boolean = value is LocalCredentialAdministrationResult.StorageUncertain
    }

    private inner class ProvisionAccountWork(
        val requestId: DomainId, val definition: LocalAccountProvisioning, val password: CharArray?,
    ) : CredentialWork() {
        override suspend fun perform(): LocalCredentialAdministrationResult = credentials.provisionAccount(requestId, definition, password)
        override fun clearInput() { password?.fill('\u0000') }
    }

    private inner class OriginalEmployeeProvisionWork(
        val bridge: RoomSelfOwnedEmployeeEnrollments, val dispatch: NativeLocalEmployeeEnrollmentDispatch,
        val definition: LocalAccountProvisioning, val password: CharArray?,
    ) : CredentialWork() {
        val actualCleanup = CompletableDeferred<Unit>()
        override suspend fun perform(): LocalCredentialAdministrationResult = withContext(NonCancellable) {
            if (originalEmployeeEnrollments.value !== bridge || credentials !== bridge.administration) unavailable()
            else bridge.administration.provisionOriginalEmployee(dispatch, definition, password)
        }
        override fun clearInput() { password?.fill('\u0000'); actualCleanup.complete(Unit) }
    }
    private inner class OriginalDeviceAdmissionWork(val bridge: RoomSelfOwnedDeviceAdmissions,
        val dispatch: NativeLocalDeviceAdmissionDispatch, val admission: LocalDeviceAdmission) : CredentialWork() {
        val actualCleanup = CompletableDeferred<Unit>()
        override suspend fun perform(): LocalCredentialAdministrationResult = withContext(NonCancellable) {
            if (originalDeviceAdmissions.value !== bridge || credentials !== bridge.administration) unavailable()
            else bridge.administration.admitOriginalDevice(dispatch, admission)
        }
        override fun clearInput() { actualCleanup.complete(Unit) }
    }

    private inner class AdmitDeviceWork(val requestId: DomainId, val admission: LocalDeviceAdmission) : CredentialWork() {
        override suspend fun perform(): LocalCredentialAdministrationResult = credentials.admitDevice(requestId, admission)
    }

    private inner class RevokeDeviceWork(
        val operator: LocalGatewayCredentials, val requestId: DomainId, val deviceId: DomainId, val expectedRevision: Revision,
    ) : CredentialWork() {
        override suspend fun perform(): LocalCredentialAdministrationResult = credentials.revokeDevice(operator, requestId, deviceId, expectedRevision)
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class ActivatePolicyWork(val requestId: DomainId, val nextPolicy: ActivatedLocalPolicy) : CredentialWork() {
        override suspend fun perform(): LocalCredentialAdministrationResult = credentials.activatePolicy(requestId, nextPolicy)
    }
    private inner class OriginalEmployeePolicyWork(val bridge: RoomSelfOwnedEmployeePolicyActivations,
        val dispatch: NativeLocalEmployeePolicyActivationDispatch, val nextPolicy: ActivatedLocalPolicy) : CredentialWork() {
        val actualCleanup = CompletableDeferred<Unit>()
        override suspend fun perform(): LocalCredentialAdministrationResult = withContext(NonCancellable) {
            if (originalEmployeePolicyActivations.value !== bridge || credentials !== bridge.administration) unavailable()
            else bridge.administration.activateOriginalEmployeePolicy(dispatch, nextPolicy)
        }
        override fun clearInput() { actualCleanup.complete(Unit) }
    }

    private inner class ReactivateAccountWork(val requestId: DomainId, val target: LocalAccountReactivation) : CredentialWork() {
        override suspend fun perform(): LocalCredentialAdministrationResult = credentials.reactivateAccount(requestId, target)
    }

    private abstract inner class ProvisioningLookupWork : Work<LocalProvisioningStatus>() {
        override fun unavailable(): LocalProvisioningStatus = LocalProvisioningStatus.Unavailable
        override fun busy(): LocalProvisioningStatus = LocalProvisioningStatus.Busy
        override fun uncertain(value: LocalProvisioningStatus): Boolean = value is LocalProvisioningStatus.StorageUncertain
    }

    private inner class AccountProvisioningLookupWork(val requestId: DomainId, val definition: LocalAccountProvisioning,
        val credentialWasProvided: Boolean) : ProvisioningLookupWork() {
        override suspend fun perform(): LocalProvisioningStatus = credentials.lookupAccountProvisioning(requestId, definition, credentialWasProvided)
    }

    private inner class OriginalEmployeeLookupWork(val bridge: RoomSelfOwnedEmployeeEnrollments, val requestId: DomainId,
        val definition: LocalAccountProvisioning, val credentialWasProvided: Boolean) : ProvisioningLookupWork() {
        val actualCleanup = CompletableDeferred<Unit>()
        override suspend fun perform(): LocalProvisioningStatus = withContext(NonCancellable) {
            if (originalEmployeeEnrollments.value !== bridge || credentials !== bridge.administration) unavailable()
            else bridge.administration.lookupOriginalEmployee(bridge.native.provisioning, requestId, definition, credentialWasProvided)
        }
        override fun clearInput() { actualCleanup.complete(Unit) }
    }
    private inner class OriginalDeviceAdmissionLookupWork(val bridge: RoomSelfOwnedDeviceAdmissions,
        val originalActionId: DomainId) : ProvisioningLookupWork() {
        val actualCleanup = CompletableDeferred<Unit>()
        override suspend fun perform(): LocalProvisioningStatus = withContext(NonCancellable) {
            if (originalDeviceAdmissions.value !== bridge || credentials !== bridge.administration) unavailable()
            else bridge.administration.lookupOriginalDeviceAdmission(bridge.native, originalActionId)
        }
        override fun clearInput() { actualCleanup.complete(Unit) }
    }

    private inner class DeviceAdmissionLookupWork(val requestId: DomainId, val admission: LocalDeviceAdmission) : ProvisioningLookupWork() {
        override suspend fun perform(): LocalProvisioningStatus = credentials.lookupDeviceAdmission(requestId, admission)
    }

    private inner class PolicyActivationLookupWork(val requestId: DomainId, val nextPolicy: ActivatedLocalPolicy) : ProvisioningLookupWork() {
        override suspend fun perform(): LocalProvisioningStatus = credentials.lookupPolicyActivation(requestId, nextPolicy)
    }
    private inner class OriginalEmployeePolicyLookupWork(val bridge: RoomSelfOwnedEmployeePolicyActivations,
        val requestId: DomainId, val nextPolicy: ActivatedLocalPolicy) : ProvisioningLookupWork() {
        val actualCleanup = CompletableDeferred<Unit>()
        override suspend fun perform(): LocalProvisioningStatus = withContext(NonCancellable) {
            if (originalEmployeePolicyActivations.value !== bridge || credentials !== bridge.administration) unavailable()
            else bridge.administration.lookupOriginalEmployeePolicy(bridge.native.provisioning, requestId, nextPolicy)
        }
        override fun clearInput() { actualCleanup.complete(Unit) }
    }

    private inner class AccountReactivationLookupWork(val requestId: DomainId, val target: LocalAccountReactivation) : ProvisioningLookupWork() {
        override suspend fun perform(): LocalProvisioningStatus = credentials.lookupAccountReactivation(requestId, target)
    }

    private inner class ResetCredentialWork(
        val operator: LocalGatewayCredentials, val actorId: DomainId, val expectedRevision: Revision, val password: CharArray,
    ) : CredentialWork() {
        override suspend fun perform(): LocalCredentialAdministrationResult = credentials.resetCredential(operator, actorId, expectedRevision, password)
        override fun clearInput() { operator.bearer.destroy(); password.fill('\u0000') }
    }

    private inner class RevokeSessionWork(
        val operator: LocalGatewayCredentials, val actorId: DomainId, val sessionId: DomainId, val expectedRevision: Revision,
    ) : CredentialWork() {
        override suspend fun perform(): LocalCredentialAdministrationResult = credentials.revokeSession(operator, actorId, sessionId, expectedRevision)
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class DisableAccountWork(
        val operator: LocalGatewayCredentials, val actorId: DomainId, val expectedRevision: Revision,
    ) : CredentialWork() {
        override suspend fun perform(): LocalCredentialAdministrationResult = credentials.disableAccount(operator, actorId, expectedRevision)
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class AdministrationWork(val operator: LocalGatewayCredentials,
        val command: LocalAdministrationCommand, val password: CharArray?) : CredentialWork() {
        override suspend fun perform(): LocalCredentialAdministrationResult =
            administration?.administer(operator, command, password) ?: LocalCredentialAdministrationResult.Unavailable
        override fun clearInput() { operator.bearer.destroy(); password?.fill('\u0000') }
    }

    private inner class EmployeeCreationExecutionWork(val operator: LocalGatewayCredentials,
        val original: LocalDelegatedEmployeeCreationV1, val password: CharArray?) : Work<LocalEmployeeCreationExecutionV1>() {
        override fun unavailable(): LocalEmployeeCreationExecutionV1 = LocalEmployeeCreationExecutionV1.Unavailable
        override fun busy(): LocalEmployeeCreationExecutionV1 = LocalEmployeeCreationExecutionV1.Busy
        override fun denied(): LocalEmployeeCreationExecutionV1 = LocalEmployeeCreationExecutionV1.Denied
        override fun uncertain(value: LocalEmployeeCreationExecutionV1): Boolean = value is LocalEmployeeCreationExecutionV1.StorageUncertain
        override suspend fun perform(): LocalEmployeeCreationExecutionV1 =
            administration?.executeEmployeeCreation(operator, original, password) ?: LocalEmployeeCreationExecutionV1.Unavailable
        override fun clearInput() { operator.bearer.destroy(); password?.fill('\u0000') }
    }

    private inner class EmployeeCreationCancellationWork(val operator: LocalGatewayCredentials,
        val original: LocalDelegatedEmployeeCreationV1) : Work<LocalEmployeeCreationCancellationV1>() {
        override fun unavailable(): LocalEmployeeCreationCancellationV1 = LocalEmployeeCreationCancellationV1.Unavailable
        override fun busy(): LocalEmployeeCreationCancellationV1 = LocalEmployeeCreationCancellationV1.Busy
        override fun denied(): LocalEmployeeCreationCancellationV1 = LocalEmployeeCreationCancellationV1.Denied
        override fun uncertain(value: LocalEmployeeCreationCancellationV1): Boolean = value is LocalEmployeeCreationCancellationV1.StorageUncertain
        override suspend fun perform(): LocalEmployeeCreationCancellationV1 =
            administration?.cancelEmployeeCreation(operator, original) ?: LocalEmployeeCreationCancellationV1.Unavailable
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class EmployeeCreationContextWork(val operator: LocalGatewayCredentials,
        val target: LocalEmployeeCreationTargetV1) : Work<LocalEmployeeCreationContextResultV1>() {
        override fun unavailable(): LocalEmployeeCreationContextResultV1 = LocalEmployeeCreationContextResultV1.Unavailable
        override fun busy(): LocalEmployeeCreationContextResultV1 = LocalEmployeeCreationContextResultV1.Busy
        override fun denied(): LocalEmployeeCreationContextResultV1 = LocalEmployeeCreationContextResultV1.Denied
        override fun uncertain(value: LocalEmployeeCreationContextResultV1): Boolean = value is LocalEmployeeCreationContextResultV1.StorageUncertain
        override suspend fun perform(): LocalEmployeeCreationContextResultV1 =
            administration?.employeeCreationContext(operator, target) ?: LocalEmployeeCreationContextResultV1.Unavailable
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class EmployeeCreationDefinitionWork(val operator: LocalGatewayCredentials,
        val draft: LocalEmployeeCreationDraftV1) : Work<LocalEmployeeCreationDefinitionResultV1>() {
        override fun unavailable(): LocalEmployeeCreationDefinitionResultV1 = LocalEmployeeCreationDefinitionResultV1.Unavailable
        override fun busy(): LocalEmployeeCreationDefinitionResultV1 = LocalEmployeeCreationDefinitionResultV1.Busy
        override fun denied(): LocalEmployeeCreationDefinitionResultV1 = LocalEmployeeCreationDefinitionResultV1.Denied
        override fun uncertain(value: LocalEmployeeCreationDefinitionResultV1): Boolean = value is LocalEmployeeCreationDefinitionResultV1.StorageUncertain
        override suspend fun perform(): LocalEmployeeCreationDefinitionResultV1 =
            administration?.captureEmployeeCreationDefinition(operator, draft) ?: LocalEmployeeCreationDefinitionResultV1.Unavailable
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class EmployeeCreationReviewWork(val operator: LocalGatewayCredentials,
        val original: LocalDelegatedEmployeeCreationV1) : Work<LocalEmployeeCreationReviewV1>() {
        override fun unavailable(): LocalEmployeeCreationReviewV1 = LocalEmployeeCreationReviewV1.Unavailable
        override fun busy(): LocalEmployeeCreationReviewV1 = LocalEmployeeCreationReviewV1.Busy
        override fun uncertain(value: LocalEmployeeCreationReviewV1): Boolean = value is LocalEmployeeCreationReviewV1.StorageUncertain
        override suspend fun perform(): LocalEmployeeCreationReviewV1 =
            administration?.reviewEmployeeCreation(operator, original) ?: LocalEmployeeCreationReviewV1.Unavailable
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class EmployeeCreationLookupWork(val operator: LocalGatewayCredentials,
        val original: LocalDelegatedEmployeeCreationV1) : Work<LocalEmployeeCreationStatusV1>() {
        override fun unavailable(): LocalEmployeeCreationStatusV1 = LocalEmployeeCreationStatusV1.Unavailable
        override fun busy(): LocalEmployeeCreationStatusV1 = LocalEmployeeCreationStatusV1.Busy
        override fun uncertain(value: LocalEmployeeCreationStatusV1): Boolean = value is LocalEmployeeCreationStatusV1.StorageUncertain
        override suspend fun perform(): LocalEmployeeCreationStatusV1 =
            administration?.lookupEmployeeCreation(operator, original) ?: LocalEmployeeCreationStatusV1.Unavailable
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class AdministrationReadWork(val operator: LocalGatewayCredentials,
        val query: LocalAdministrationQuery) : Work<LocalAdministrationReadResult>() {
        override fun unavailable(): LocalAdministrationReadResult = LocalAdministrationReadResult.Unavailable
        override fun busy(): LocalAdministrationReadResult = LocalAdministrationReadResult.Busy
        override fun uncertain(value: LocalAdministrationReadResult): Boolean = value is LocalAdministrationReadResult.StorageUncertain
        override suspend fun perform(): LocalAdministrationReadResult =
            administration?.readAdministration(operator, query) ?: LocalAdministrationReadResult.Unavailable
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class AdministrationLookupWork(val operator: LocalGatewayCredentials,
        val command: LocalAdministrationCommand, val cancel: Boolean = false) : Work<LocalAdministrationStatus>() {
        override fun unavailable(): LocalAdministrationStatus = LocalAdministrationStatus.Unavailable
        override fun busy(): LocalAdministrationStatus = LocalAdministrationStatus.Busy
        override fun uncertain(value: LocalAdministrationStatus): Boolean = value is LocalAdministrationStatus.StorageUncertain
        override suspend fun perform(): LocalAdministrationStatus =
            if (cancel) administration?.cancelAdministration(operator, command) ?: LocalAdministrationStatus.Unavailable
            else administration?.lookupAdministration(operator, command) ?: LocalAdministrationStatus.Unavailable
        override fun clearInput() { operator.bearer.destroy() }
    }

    private fun serialUnavailable(reason: SelfOwnedInstallationFailureV1): LocalCompanyAuthorityResult.Unavailable =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE, reason)

    /** Authentication runs on the existing clean worker BEFORE the original loans mutex. */
    private abstract inner class SerialWork<T>(val operator: LocalGatewayCredentials) : Work<LocalCompanyAuthorityResult<T>>() {
        override fun unavailable(): LocalCompanyAuthorityResult<T> = serialUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        override fun denied(): LocalCompanyAuthorityResult<T> = serialUnavailable(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        override fun uncertain(value: LocalCompanyAuthorityResult<T>): Boolean =
            value is LocalCompanyAuthorityResult.Unavailable && value.nativeReason == SelfOwnedInstallationFailureV1.STATE_UNCERTAIN
        final override suspend fun perform(): LocalCompanyAuthorityResult<T> {
            val service = originalSerialPublications.value ?: return unavailable()
            val principal = principals.authenticateLocal(operator) ?: return denied()
            return performAuthenticated(service, principal)
        }
        abstract suspend fun performAuthenticated(service: RoomSelfOwnedSerialPublications,
            principal: LocalEmployeePrincipal): LocalCompanyAuthorityResult<T>
        override fun clearInput() { operator.bearer.destroy() }
    }

    private inner class SerialRetainedWork(operator: LocalGatewayCredentials) : SerialWork<List<LocalSerialPrivatePublicationReference>>(operator) {
        override suspend fun performAuthenticated(service: RoomSelfOwnedSerialPublications,
            principal: LocalEmployeePrincipal): LocalCompanyAuthorityResult<List<LocalSerialPrivatePublicationReference>> = service.retained(principal)
    }

    private inner class SerialAuthorityWork(operator: LocalGatewayCredentials, val requestId: DomainId) : SerialWork<CompanyBusinessPublicationAction>(operator) {
        override suspend fun performAuthenticated(service: RoomSelfOwnedSerialPublications,
            principal: LocalEmployeePrincipal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> = service.prepareAuthority(principal, requestId)
        override fun discard(value: LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>) {
            if (value is LocalCompanyAuthorityResult.Value) value.value.discardUndeliveredSerialContent()
        }
    }

    private inner class SerialPrepareWork(operator: LocalGatewayCredentials, val requestId: DomainId, val company: CompanyScope,
        val publicationKind: CompanyBusinessPublicationKind, val publicationId: DomainId, val entityId: DomainId, val content: ByteArray) :
        SerialWork<CompanyBusinessPublicationAction>(operator) {
        override suspend fun performAuthenticated(service: RoomSelfOwnedSerialPublications,
            principal: LocalEmployeePrincipal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
            service.prepare(principal, requestId, CompanyBusinessPublicationProposal(company, publicationKind, publicationId, entityId, content))
        override fun clearInput() { super.clearInput(); content.fill(0) }
        override fun discard(value: LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>) {
            if (value is LocalCompanyAuthorityResult.Value) value.value.discardUndeliveredSerialContent()
        }
    }

    private inner class SerialReviewWork(operator: LocalGatewayCredentials, val requestId: DomainId,
        val originRequestId: DomainId, val actionId: DomainId) : SerialWork<CompanyBusinessPublicationAction>(operator) {
        override suspend fun performAuthenticated(service: RoomSelfOwnedSerialPublications,
            principal: LocalEmployeePrincipal): LocalCompanyAuthorityResult<CompanyBusinessPublicationAction> =
            service.review(principal, requestId, originRequestId, actionId)
        override fun discard(value: LocalCompanyAuthorityResult<CompanyBusinessPublicationAction>) {
            if (value is LocalCompanyAuthorityResult.Value) value.value.discardUndeliveredSerialContent()
        }
    }

    private inner class SerialRecoverWork(operator: LocalGatewayCredentials, val requestId: DomainId,
        val originRequestId: DomainId, val actionId: DomainId) : SerialWork<CompanyBusinessPublicationOutput>(operator) {
        override suspend fun performAuthenticated(service: RoomSelfOwnedSerialPublications,
            principal: LocalEmployeePrincipal): LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput> =
            service.recover(principal, requestId, originRequestId, actionId)
        override fun discard(value: LocalCompanyAuthorityResult<CompanyBusinessPublicationOutput>) {
            if (value is LocalCompanyAuthorityResult.Value) value.value.discardUndeliveredSerialContent()
        }
    }
}

private fun LocalGatewayCredentials.copyOwned(): LocalGatewayCredentials =
    bearer.useBytes { LocalGatewayCredentials(connectionId, OpaqueCredential(it)) }

private fun LocalAuthenticationResult.destroyTokensIfPresent() {
    if (this is LocalAuthenticationResult.Authenticated) tokens.destroy()
}

private fun MutableStateFlow<LocalMasterEndpointHealth>.getAndSetRetired(): Boolean {
    while (true) {
        val current = value
        if (current == LocalMasterEndpointHealth.RETIRED) return false
        if (compareAndSet(current, LocalMasterEndpointHealth.RETIRED)) return true
    }
}
