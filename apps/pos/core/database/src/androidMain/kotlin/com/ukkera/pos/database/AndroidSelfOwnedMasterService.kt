package com.ukkera.pos.database

import android.app.Application
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerIdentity
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore

public sealed interface AndroidSelfOwnedMasterOpenResult {
    public class Opened internal constructor(public val service: AndroidSelfOwnedMasterService): AndroidSelfOwnedMasterOpenResult
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1): AndroidSelfOwnedMasterOpenResult
}
/**
 * The actual original cohost connection, not a deserializable enrollment request. Only the
 * owning service can construct it, after complete original installation/history admission.
 * It grants no employee session, device-admission permit by itself or replacement-master authority.
 */
public class AndroidOriginalLocalClient internal constructor(
    private val service: AndroidSelfOwnedMasterService,
    public val peer: LocalAuthenticatedPeer,
    public val serviceIncarnationId: DomainId,
    public val compositionMode: com.ukkera.pos.security.LocalSetupCompositionMode,
    public val material: SelfOwnedNativeMaterialView,
    public val receipt: LocalBranchInstallationReceiptV2,
    public val companyName: String,
    public val client: LocalMasterClient,
    public val administration: LocalMasterAdministrationClient,
    public val commandKeys: Set<CommandKey>,
    public val readKeys: Set<ReadKey>,
) {
    public fun isCurrent(): Boolean = service.ownsOriginalClient(this)
    public fun belongsTo(application: Application): Boolean = service.originalClientApplication(this, application)

    /** Only the fully settled original C1 with no later work may create its first journals. */
    public fun permitsFirstEnrollment(): Boolean = service.permitsFirstClientEnrollment(this)

    /** The actual registered object only; a caller cannot replace this native owner. */
    public fun physicalRecordingGate(): PhysicalRecordingExecutionGate? = service.originalPhysicalGate(this)

    /** Exact owned endpoint/gate association, never a cast of an arbitrary client facade. */
    public fun physicalEndpoint(gate: PhysicalRecordingExecutionGate): LocalMasterEndpoint? =
        service.originalPhysicalEndpoint(this, gate)

    /** Same original native publication owner, not another root or employee authorization. */
    public suspend fun originalBusinessPublications(): LocalCompanyBusinessPublications? =
        service.originalBusinessPublications(this)

    /** Genuine consumed/cold-admitted enrollment witnesses for original native census only.
     * This issues neither current employee permission nor successfully opened client journals. */
    public suspend fun originalConsumedPhysicalEmployees(): LocalCompanyAuthorityResult<List<OriginalPhysicalEmployeeEnrollment>> =
        service.originalConsumedPhysicalEmployees(this)

    /** Explicit COMPLETE policy data only; no inferred role/grants and no employee password. */
    public suspend fun prepareEmployeeEnrollment(requestId: DomainId, definition: LocalAccountProvisioning,
        credentialWasProvided: Boolean): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
        service.prepareEmployeeEnrollment(this, requestId, definition, credentialWasProvided)

    /** Head secret remains caller-owned. This independently authenticates the named company
     * head for the dedicated new purpose; it never signs in the head as a local employee. */
    public suspend fun approveEmployeeEnrollment(action: LocalEmployeeEnrollmentAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> =
        service.approveEmployeeEnrollment(this, action, headLogin, headSecret)

    public suspend fun recoverEmployeeEnrollmentApproval(originalActionId: DomainId):
        LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> =
        service.recoverEmployeeEnrollmentApproval(this, originalActionId)

    /** Exact late-phase original connection recovery; lookup/custody only, never re-dispatch. */
    public suspend fun recoverEmployeeEnrollmentConnection(originalActionId: DomainId):
        LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> =
        service.recoverEmployeeEnrollmentConnection(this, originalActionId)

    /** Read-only exact PREPARED action; never allocates, re-prepares or signs after lost delivery. */
    public suspend fun reviewEmployeeEnrollment(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
        service.reviewEmployeeEnrollment(this, originalActionId)
    public suspend fun retainedEmployeeEnrollments(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> =
        service.retainedEmployeeEnrollments(this)

    /** Selects an already genuinely delivered same-lifetime connection; scalar absence grants nothing. */
    public fun originalEmployeeEnrollment(requestId: DomainId): AndroidLocalEmployeeEnrollmentConnection? =
        service.originalEmployeeEnrollment(this, requestId)
    /** Unavailable before an actual dedicated full-definition approval has been registered. */
    public fun originalEmployeeEnrollmentClient(): AndroidOriginalEmployeeEnrollmentClient? =
        service.originalEmployeeEnrollmentClient(this)
    /** Complete per-target policy preparation on this original native owner, not employee authority. */
    public suspend fun prepareEmployeePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
        service.prepareEmployeePolicy(this, requestId, nextPolicy)

    /** The service owns only a cleaned copy; the caller retains its head-secret buffer. */
    public suspend fun approveEmployeePolicy(action: LocalEmployeePolicyActivationAction, headLogin: String,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        service.approveEmployeePolicy(this, action, headLogin, headSecret)

    public suspend fun reviewEmployeePolicy(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
        service.reviewEmployeePolicy(this, originalActionId)
    public suspend fun recoverEmployeePolicyApproval(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        service.recoverEmployeePolicyApproval(this, originalActionId, late = false)
    /** Late-phase original receipt DATA only; cannot re-sign or renew dispatch. */
    public suspend fun recoverEmployeePolicyConnection(originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
        service.recoverEmployeePolicyApproval(this, originalActionId, late = true)
    public suspend fun retainedEmployeePolicies(): LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> =
        service.retainedEmployeePolicies(this)

    /** Native validates the exact retained approval before the actual sole writer can activate. */
    public suspend fun activateEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult =
        service.activateEmployeePolicy(this, approval, nextPolicy)
    /** Exact original lookup only; no prepare, signing, dispatch or renewed effect authority. */
    public suspend fun lookupEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
        nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus =
        service.lookupEmployeePolicy(this, approval, nextPolicy)

    // BEGIN isolated device-pairing preparation/recovery entry points V1.
    /** Explicit one-new-target DATA preparation on this actual original owner; no admission. */
    public suspend fun prepareDevicePairing(requestId: DomainId, target: LocalDevicePairingTargetDescriptor):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> = service.prepareDevicePairing(this, requestId, target)
    /** Complete SDK-bound DATA captured before this fresh head ceremony. Its target is
     * retained in the descriptor; no caller can append SDK identity to an old approval. */
    public suspend fun prepareSdkBoundDevicePairing(requestId: DomainId, sdkIdentity: LocalDevicePairingSdkIdentityV2):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> =
        service.prepareDevicePairing(this, requestId, sdkIdentity.target, sdkIdentity)
    public suspend fun proveDevicePairingTarget(action: LocalDevicePairingAction, possession: LocalDevicePairingPossession):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> = service.proveDevicePairingTarget(this, action, possession)
    /** Fresh named-head consent to this ONE new purpose/action. The secret array is cleared;
     * no head/employee session, delegated-device-admin grant or later enrollment is created. */
    public suspend fun approveDevicePairing(action: LocalDevicePairingAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<LocalDevicePairingApproval> = service.approveDevicePairing(this, action, headLogin, headSecret)
    public suspend fun reviewDevicePairing(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
        service.reviewDevicePairing(this, originalActionId)
    /** Original certificate DATA only, even when stale. Never reauthenticate/sign/renew. */
    public suspend fun recoverDevicePairingApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingApproval> =
        service.recoverDevicePairingApproval(this, originalActionId)
    public suspend fun retainedDevicePairings(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> =
        service.retainedDevicePairings(this)
    public suspend fun retireKnownUnsignedDevicePairing(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
        service.retireKnownUnsignedDevicePairing(this, originalActionId)
    public suspend fun recoverUnsignedDevicePairingRetirement(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
        service.recoverUnsignedDevicePairingRetirement(this, originalActionId)
    // END isolated device-pairing preparation/recovery entry points V1.

    /** Consume the exact original SDK-bound approval through this Master's sole writer.
     * The approval remains DATA; only its native producer may issue the single-use permit. */
    public suspend fun admitOriginalDevice(approval: LocalDevicePairingApproval): LocalCredentialAdministrationResult =
        service.admitOriginalDevice(this, approval)
    /** Read-only original outcome, joined to retained native and protected history. This
     * neither prepares nor renews an approval, dispatch, device/session or RPC authority. */
    public suspend fun lookupOriginalDeviceAdmission(originalActionId: DomainId): LocalProvisioningStatus =
        service.lookupOriginalDeviceAdmission(this, originalActionId)
    /** Actual same-owner HostClients trust; no caller-supplied pin list or employee grant. */
    public suspend fun acquireRpcHostTrust(): LocalCompanyAuthorityResult<LocalMasterRpcTrust> =
        service.acquireRpcHostTrust(this)

    /** Current authenticated original Master SDK DATA; no capture, repair or serving grant. */
    public suspend fun recoverRpcMasterOriginal(): LocalCompanyAuthorityResult<LocalMasterSdkOriginalV1> =
        service.recoverRpcMasterOriginal(this)

    /** Retain the exact Host resource before its common owner or any SDK work is constructed.
     * This does not transfer or close the authoritative Master/native lifetime. */
    public fun retainRpcHostResource(resource: LocalMasterRpcHostResource): AndroidOriginalRpcHostRegistration =
        service.retainRpcHostResource(this, resource)
    override fun toString(): String = "AndroidOriginalLocalClient([REDACTED])"
}

/** Once-only same-service resource registration. Original DATA is not a transferable writer,
 * transport, device-admission or employee capability; no raw native owner enters this handle. */
public class AndroidOriginalRpcHostRegistration internal constructor(
    internal val service: AndroidSelfOwnedMasterService,
    internal val original: AndroidOriginalLocalClient,
    internal val resource: LocalMasterRpcHostResource,
) {
    public fun isCurrent(): Boolean = service.ownsRpcHostRegistration(this)
    public suspend fun yieldOriginal(): LocalCompanyAuthorityResult<LocalMasterSdkOriginalV1> =
        service.yieldRpcMasterOriginal(this)
    override fun toString(): String = "AndroidOriginalRpcHostRegistration([REDACTED])"
}

/** Actual same-P2 setup -> one Room -> runtime/close lifetime. Only opaque native context is
 * accepted; no public path/driver/key/runtime/clock/healthy callback. Client handles cannot
 * close the master. Later incomplete authorization/stock/pending histories remain held. */
public class AndroidSelfOwnedMasterService private constructor(
    private val application: Application,
    private val custody: SelfOwnedNativeHistoryCustody,
    private val handlers: List<MasterCommandHandler>,
    private val reads: List<MasterReadHandler>,
    private val physical: PhysicalRecordingExecutionGate,
    private val imports: List<NativeOriginalImportInterpreter>,
    private val stockProjection: StockCanonicalProjectionV2?,
    private val historyProducer: RuntimeHistoryBusinessProducer?,
    private val installationOnly: Boolean,
    private val originalMaterial: SelfOwnedNativeMaterialView,
) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
    private val watchdog=Executors.newSingleThreadExecutor { command ->
        Thread(command,"ukkera-owned-master-watchdog").apply { isDaemon=true }
    }.asCoroutineDispatcher()
    private val life=MutableStateFlow(LocalMasterLifecycleMode.ALLOCATION_ONLY)
    public val mode: StateFlow<LocalMasterLifecycleMode> = life.asStateFlow()
    private val lifecycleGate=Any()
    private val installationSlot=Semaphore(1)
    private val installation=AtomicReference<Job?>(null)
    private val closing=AtomicReference<CompletableDeferred<Boolean>?>(null)
    private var roomOwner: RoomRuntimeHistoryOwner?=null
    private var composition: RoomSelfOwnedMasterComposition?=null
    private var peer: LocalAuthenticatedPeer?=null
    // Set only after full native-history admission or a successful native ACK completion.
    // Immutable saved result DATA survives later runtime admission/activation failure.
    private var installedReceipt: LocalBranchInstallationReceiptV2?=null
    private val serviceIncarnationId = DomainId.random()
    @Volatile private var originalClient: AndroidOriginalLocalClient? = null
    // Retained before common construction; never replaced or forgotten on unknown cleanup.
    private var rpcHostRegistration: AndroidOriginalRpcHostRegistration? = null // lifecycleGate
    private var rpcPeerRegistration: LocalMasterRpcPeerRegistration? = null // lifecycleGate
    // Same-lifetime registered capabilities only, never cold custody inferred from account rows.
    private var employeeConnections = emptyList<AndroidLocalEmployeeEnrollmentConnection>() // lifecycleGate
    private var employeeClients = emptyList<AndroidEmployeeClientCustody>() // lifecycleGate
    private var employeeEnrollmentClient: AndroidOriginalEmployeeEnrollmentClient? = null // lifecycleGate
    private var devicePairingBridge: RoomSelfOwnedDevicePairings? = null // same installation slot/job; DATA only
    // Presentation/dispatch state only. The native owner retains and validates the actual
    // completion capability; this snapshot cannot grant an ACK or stand in for the full scan.
    private var reopenedAcknowledgement: OriginalNativeHistorySnapshot?=null
    public val localConnectionId: DomainId? get() = if(life.value==LocalMasterLifecycleMode.OPERATIONAL) peer?.connectionId else null
    public val client: LocalMasterClient? get() = if(life.value==LocalMasterLifecycleMode.OPERATIONAL) composition?.endpoint else null
    public val enrollment: LocalMasterEnrollmentClient? get() = if(life.value==LocalMasterLifecycleMode.OPERATIONAL) composition?.endpoint else null
    public val administration: LocalMasterAdministrationClient? get() = if(life.value==LocalMasterLifecycleMode.OPERATIONAL) composition?.endpoint else null
    public val approvals: LocalMasterApprovalClient? get() = if(life.value==LocalMasterLifecycleMode.OPERATIONAL) composition?.endpoint else null
    public val physicalActions: LocalMasterPhysicalActions? get() = composition?.endpoint?.physicalActions?.takeIf {
        life.value==LocalMasterLifecycleMode.OPERATIONAL
    }

    /** No caller supplies a peer, path, key, clock, admission Boolean or installation receipt. */
    public suspend fun originalLocalClient(): AndroidOriginalLocalClient {
        currentCoroutineContext().ensureActive()
        // The install response may be delivered just before its job clears secrets/releases
        // this slot. Wait for that actual cleanup instead of misreporting a successful install.
        installationSlot.acquire()
        try {
            originalClient?.let { check(it.isCurrent()); return it }
            check(life.value == LocalMasterLifecycleMode.OPERATIONAL)
            val company = custody.readOriginalCompanyAuthority()
            val actualPeer = checkNotNull(peer)
            val endpoint = checkNotNull(composition?.endpoint)
            val receipt = checkNotNull(installedReceipt)
            val coordinates = originalMaterial.coordinates
            val installed = receipt.core.allocation.coordinates
            check(actualPeer.scope == coordinates.scope && actualPeer.deviceId == coordinates.deviceId &&
                company.organizationId == coordinates.scope.organizationId && company.companyId == coordinates.scope.companyId &&
                installed.scope == coordinates.scope && installed.deviceId == coordinates.deviceId &&
                installed.allocationId == coordinates.allocationId && installed.installationId == coordinates.installationId &&
                installed.assignmentId == coordinates.assignmentId && installed.storageInstanceId == coordinates.storageInstanceId &&
                installed.storageKeyId == coordinates.storageKeyId && installed.pepperKeyId == coordinates.pepperKeyId &&
                installed.targetSigningKeyId == coordinates.targetSigningKeyId && installed.firstActorId == coordinates.firstActorId)
            return synchronized(lifecycleGate) {
                check(life.value == LocalMasterLifecycleMode.OPERATIONAL && closing.get() == null && actualPeer.isCurrent())
                AndroidOriginalLocalClient(this, actualPeer, serviceIncarnationId,
                    custody.compositionProfile.mode, originalMaterial, receipt,
                    company.companyName, endpoint, endpoint, handlers.map { it.key }.toSet(), reads.map { it.key }.toSet())
                    .also { originalClient = it }
            }
        } finally { installationSlot.release() }
    }

    internal fun ownsOriginalClient(value: AndroidOriginalLocalClient): Boolean = synchronized(lifecycleGate) {
        originalClient === value && life.value == LocalMasterLifecycleMode.OPERATIONAL && closing.get() == null &&
            roomOwner?.operational == true && peer === value.peer && composition?.endpoint === value.client && value.peer.isCurrent() &&
            value.client.health.value == LocalMasterEndpointHealth.ACTIVE
    }

    internal fun retainRpcHostResource(original: AndroidOriginalLocalClient,
        resource: LocalMasterRpcHostResource): AndroidOriginalRpcHostRegistration = synchronized(lifecycleGate) {
        check(ownsOriginalClient(original) && original.belongsTo(application)) { "Original Master is unavailable" }
        check(rpcHostRegistration == null) { "An original RPC Host resource is already retained" }
        AndroidOriginalRpcHostRegistration(this, original, resource).also { rpcHostRegistration = it }
    }

    internal fun ownsRpcHostRegistration(value: AndroidOriginalRpcHostRegistration): Boolean = synchronized(lifecycleGate) {
        rpcHostRegistration === value && value.service === this && ownsOriginalClient(value.original)
    }

    internal fun originalClientApplication(value: AndroidOriginalLocalClient, actual: Application): Boolean =
        application === actual && ownsOriginalClient(value)

    internal fun permitsFirstClientEnrollment(value: AndroidOriginalLocalClient): Boolean = synchronized(lifecycleGate) {
        if (!ownsOriginalClient(value)) return@synchronized false
        roomOwner?.permitsFirstClientEnrollment(value.receipt) == true
    }

    internal fun originalPhysicalGate(value: AndroidOriginalLocalClient): PhysicalRecordingExecutionGate? =
        synchronized(lifecycleGate) { physical.takeIf { ownsOriginalClient(value) } }

    internal fun originalPhysicalEndpoint(value: AndroidOriginalLocalClient,
        gate: PhysicalRecordingExecutionGate): LocalMasterEndpoint? = synchronized(lifecycleGate) {
        if (physical !== gate || !ownsOriginalClient(value)) null
        else composition?.endpoint?.takeIf { it === value.client && it.physicalActions != null }
    }

    internal suspend fun originalBusinessPublications(value: AndroidOriginalLocalClient): LocalCompanyBusinessPublications? {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(value)) return null
        val retained = custody.readOriginalBusinessPublications()
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(value)) return null
        return (retained as? LocalCompanyAuthorityResult.Value<LocalCompanyBusinessPublications>)?.value
    }

    internal fun ownsEmployeeEnrollment(value: AndroidLocalEmployeeEnrollmentConnection): Boolean = synchronized(lifecycleGate) {
        value.service === this && employeeConnections.any { it === value } && ownsOriginalClient(value.original) &&
            composition?.endpoint === value.original.client
    }
    internal fun ownsEmployeeClient(value: AndroidEmployeeClientCustody): Boolean = synchronized(lifecycleGate) {
        value.service === this && employeeClients.any { it === value } && ownsEmployeeEnrollment(value.enrollment) && value.native.isCurrent()
    }
    internal fun originalEmployeeEnrollment(original: AndroidOriginalLocalClient, requestId: DomainId): AndroidLocalEmployeeEnrollmentConnection? =
        synchronized(lifecycleGate) { employeeConnections.singleOrNull { it.original === original && it.originalRequestId == requestId && ownsEmployeeEnrollment(it) } }
    internal fun ownsEmployeeEnrollmentClient(value: AndroidOriginalEmployeeEnrollmentClient): Boolean = synchronized(lifecycleGate) {
        employeeEnrollmentClient === value && value.service === this && ownsOriginalClient(value.original) && employeeConnections.isNotEmpty()
    }
    internal fun originalEmployeeEnrollmentClient(original: AndroidOriginalLocalClient): AndroidOriginalEmployeeEnrollmentClient? = synchronized(lifecycleGate) {
        employeeEnrollmentClient?.takeIf { it.original === original && ownsEmployeeEnrollmentClient(it) }
    }

    internal suspend fun prepareEmployeeEnrollment(original: AndroidOriginalLocalClient, requestId: DomainId,
        definition: LocalAccountProvisioning, credentialWasProvided: Boolean): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeePrepareWork(original, requestId, definition, credentialWasProvided))
    }
    internal suspend fun approveEmployeeEnrollment(original: AndroidOriginalLocalClient, action: LocalEmployeeEnrollmentAction,
        headLogin: String, headSecret: CharArray): LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original) || headLogin.length !in 1..128 || headSecret.size !in 4..1024) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        var owned: CharArray? = null
        var transferred = false
        try {
            owned = headSecret.copyOf()
            val task = EmployeeApproveWork(original, action, headLogin, checkNotNull(owned))
            transferred = true // Actual retained task owns its copy and every cleanup path.
            return launchEmployeeOwned(task)
        } finally { if (!transferred) { owned?.fill('\u0000'); installationSlot.release() } }
    }
    internal suspend fun recoverEmployeeEnrollmentApproval(original: AndroidOriginalLocalClient, originalActionId: DomainId):
        LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeeApprovalRecoveryWork(original, originalActionId))
    }
    internal suspend fun recoverEmployeeEnrollmentConnection(original: AndroidOriginalLocalClient, originalActionId: DomainId):
        LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeeConnectionRecoveryWork(original, originalActionId))
    }
    internal suspend fun reviewEmployeeEnrollment(original: AndroidOriginalLocalClient, originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeeReviewWork(original, originalActionId))
    }
    internal suspend fun retainedEmployeeEnrollments(original: AndroidOriginalLocalClient):
        LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeeRetainedWork(original))
    }
    internal suspend fun prepareEmployeePolicy(original: AndroidOriginalLocalClient, requestId: DomainId,
        nextPolicy: ActivatedLocalPolicy): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeePolicyPrepareWork(original, requestId, nextPolicy))
    }
    internal suspend fun approveEmployeePolicy(original: AndroidOriginalLocalClient, action: LocalEmployeePolicyActivationAction,
        headLogin: String, headSecret: CharArray): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original) || headLogin.length !in 1..128 || headSecret.size !in 4..1024)
            return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        var owned: CharArray? = null
        var transferred = false
        try {
            owned = headSecret.copyOf()
            val task = EmployeePolicyApproveWork(original, action, headLogin, checkNotNull(owned))
            transferred = true
            return launchEmployeeOwned(task)
        } finally { if (!transferred) { owned?.fill('\u0000'); installationSlot.release() } }
    }
    internal suspend fun reviewEmployeePolicy(original: AndroidOriginalLocalClient, originalActionId: DomainId):
        LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeePolicyReviewWork(original, originalActionId))
    }
    internal suspend fun recoverEmployeePolicyApproval(original: AndroidOriginalLocalClient, originalActionId: DomainId,
        late: Boolean): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeePolicyRecoveryWork(original, originalActionId, late))
    }
    internal suspend fun retainedEmployeePolicies(original: AndroidOriginalLocalClient):
        LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeePolicyRetainedWork(original))
    }
    internal suspend fun activateEmployeePolicy(original: AndroidOriginalLocalClient,
        approval: LocalEmployeePolicyActivationAuthorityReceipt, nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return LocalCredentialAdministrationResult.Unavailable
        if (!installationSlot.tryAcquire()) return LocalCredentialAdministrationResult.Busy
        return launchEmployeeOwned(EmployeePolicyActivateWork(original, approval, nextPolicy))
    }
    internal suspend fun lookupEmployeePolicy(original: AndroidOriginalLocalClient,
        approval: LocalEmployeePolicyActivationAuthorityReceipt, nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return LocalProvisioningStatus.Unavailable
        if (!installationSlot.tryAcquire()) return LocalProvisioningStatus.Busy
        return launchEmployeeOwned(EmployeePolicyLookupWork(original, approval, nextPolicy))
    }

    // BEGIN isolated device-pairing service work V1. Existing worker is scheduling only.
    internal suspend fun prepareDevicePairing(original: AndroidOriginalLocalClient, requestId: DomainId,
        target: LocalDevicePairingTargetDescriptor, sdkIdentity: LocalDevicePairingSdkIdentityV2? = null):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original) || target.admission.scope != original.peer.scope) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(DevicePairingPrepareWork(original, requestId, target, sdkIdentity))
    }
    internal suspend fun proveDevicePairingTarget(original: AndroidOriginalLocalClient, action: LocalDevicePairingAction,
        possession: LocalDevicePairingPossession): LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original) || action.requested.request.scope != original.peer.scope) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(DevicePairingProofWork(original, action, possession))
    }
    internal suspend fun approveDevicePairing(original: AndroidOriginalLocalClient, action: LocalDevicePairingAction,
        headLogin: String, headSecret: CharArray): LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
        try {
            currentCoroutineContext().ensureActive()
            if (!ownsOriginalClient(original) || action.requested.request.scope != original.peer.scope ||
                headLogin.length !in 1..128 || headSecret.size !in 4..1024) return devicePairingUnavailable()
            if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
            var owned: CharArray? = null; var transferred = false
            try {
                owned = headSecret.copyOf(); headSecret.fill('\u0000')
                val task = DevicePairingApproveWork(original, action, headLogin, checkNotNull(owned))
                transferred = true
                return launchEmployeeOwned(task)
            } finally { if (!transferred) { owned?.fill('\u0000'); installationSlot.release() } }
        } finally { headSecret.fill('\u0000') }
    }
    internal suspend fun reviewDevicePairing(original: AndroidOriginalLocalClient, id: DomainId):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(DevicePairingReviewWork(original, id))
    }
    internal suspend fun recoverDevicePairingApproval(original: AndroidOriginalLocalClient, id: DomainId):
        LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(DevicePairingRecoveryWork(original, id))
    }
    internal suspend fun retainedDevicePairings(original: AndroidOriginalLocalClient):
        LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(DevicePairingRetainedWork(original))
    }
    internal suspend fun retireKnownUnsignedDevicePairing(original: AndroidOriginalLocalClient, id: DomainId):
        LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(DevicePairingUnsignedRetirementWork(original, id, recovery = false))
    }
    internal suspend fun recoverUnsignedDevicePairingRetirement(original: AndroidOriginalLocalClient, id: DomainId):
        LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(DevicePairingUnsignedRetirementWork(original, id, recovery = true))
    }
    private fun <T> devicePairingUnavailable(busy: Boolean = false): LocalCompanyAuthorityResult<T> =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE,
            if (busy) SelfOwnedInstallationFailureV1.BUSY else SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    private suspend fun sameDevicePairingBridge(original: AndroidOriginalLocalClient): RoomSelfOwnedDevicePairings {
        check(ownsOriginalClient(original) && original.belongsTo(application))
        val native = LocalCompanyDevicePairings.acquireOriginal(custody)
        check(native is LocalCompanyAuthorityResult.Value && native.value.belongsTo(custody))
        check(ownsOriginalClient(original))
        val owner = checkNotNull(roomOwner)
        check(owner.operational && owner.originalScope == original.peer.scope)
        devicePairingBridge?.let { check(it.native === native.value); return it }
        return RoomSelfOwnedDevicePairings(owner, custody, native.value).also { devicePairingBridge = it }
    }
    private abstract inner class DevicePairingWork<T>(final override val original: AndroidOriginalLocalClient):
        EmployeeWork<LocalCompanyAuthorityResult<T>> {
        override fun unavailable(): LocalCompanyAuthorityResult<T> = devicePairingUnavailable()
        override fun busy(): LocalCompanyAuthorityResult<T> = devicePairingUnavailable(busy = true)
    }
    internal suspend fun acquireRpcHostTrust(original: AndroidOriginalLocalClient): LocalCompanyAuthorityResult<LocalMasterRpcTrust> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(RpcHostTrustWork(original), awaitActualWork = true)
    }
    private inner class RpcHostTrustWork(original: AndroidOriginalLocalClient): DevicePairingWork<LocalMasterRpcTrust>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalMasterRpcTrust> {
            val native = sameDevicePairingBridge(original).native
            checkNotNull(composition).originalRpcTrust(native)
            check(ownsOriginalClient(original))
            return LocalCompanyAuthorityResult.Value(LocalMasterRpcTrust(RpcHostTrustController(original)))
        }
    }
    internal suspend fun recoverRpcMasterOriginal(original: AndroidOriginalLocalClient):
        LocalCompanyAuthorityResult<LocalMasterSdkOriginalV1> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(RpcMasterOriginalWork(original))
    }
    private inner class RpcMasterOriginalWork(original: AndroidOriginalLocalClient):
        DevicePairingWork<LocalMasterSdkOriginalV1>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalMasterSdkOriginalV1> {
            val sdkOriginal = checkNotNull(roomOwner).readOriginalMasterSdk()
            check(ownsOriginalClient(original))
            return LocalCompanyAuthorityResult.Value(sdkOriginal)
        }
    }

    internal suspend fun yieldRpcMasterOriginal(registration: AndroidOriginalRpcHostRegistration):
        LocalCompanyAuthorityResult<LocalMasterSdkOriginalV1> {
        currentCoroutineContext().ensureActive()
        if (!ownsRpcHostRegistration(registration)) return devicePairingUnavailable()
        if (!installationSlot.tryAcquire()) return devicePairingUnavailable(busy = true)
        return launchEmployeeOwned(RpcMasterHostYieldWork(registration), awaitActualWork = true)
    }
    private inner class RpcMasterHostYieldWork(private val registration: AndroidOriginalRpcHostRegistration):
        DevicePairingWork<LocalMasterSdkOriginalV1>(registration.original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalMasterSdkOriginalV1> {
            check(ownsRpcHostRegistration(registration))
            val owner = checkNotNull(roomOwner)
            check(owner.operational && owner.originalCustody === custody && owner.originalScope == original.peer.scope)
            val sdkOriginal = owner.serialPublicationLoan { loan ->
                AndroidSelfOwnedInstallationOwner.yieldExistingMasterSdkForHost(custody, loan.original)
            }
            check(ownsRpcHostRegistration(registration))
            // The actual original loan has returned. Construct from this exact Master's
            // sources, then push only to the service-retained resource; no public getter
            // publishes registration to the original client or retained Android handle.
            val peers = synchronized(lifecycleGate) { rpcPeerRegistration } ?: run {
                val native = sameDevicePairingBridge(original).native
                checkNotNull(composition).originalRpcTrust(native)
                val lifetime = RpcHostPeerRegistrationLifetime(registration)
                val trust = LocalMasterRpcTrust(RpcHostTrustController(original, registration))
                val created = checkNotNull(composition).originalRpcPeerRegistration(lifetime, trust)
                synchronized(lifecycleGate) {
                    check(ownsRpcHostRegistration(registration) && rpcPeerRegistration == null)
                    rpcPeerRegistration = created
                }
                created
            }
            check(ownsRpcHostRegistration(registration))
            // This synchronous private resource callback only retains the token. SDK work
            // and cleanup Job starts belong to later callbacks outside native/Room work.
            registration.resource.bindPeerRegistration(peers)
            check(ownsRpcHostRegistration(registration))
            return LocalCompanyAuthorityResult.Value(sdkOriginal)
        }
    }
    private inner class RpcHostPeerRegistrationLifetime(private val registration: AndroidOriginalRpcHostRegistration):
        LocalMasterRpcPeerRegistrationLifetime {
        override val cleanupScope: CoroutineScope get() = scope
        override fun isCurrent(): Boolean = scope.isActive && ownsRpcHostRegistration(registration)
    }
    private inner class RpcHostTrustController(private val original: AndroidOriginalLocalClient,
        private val hostRegistration: AndroidOriginalRpcHostRegistration? = null): LocalMasterRpcTrustController {
        private fun requireCurrent() {
            check(ownsOriginalClient(original) && (hostRegistration == null || ownsRpcHostRegistration(hostRegistration)))
        }
        override suspend fun readCurrent(exactAppId: AppId, expected: Set<PeerFingerprint>?): Set<PeerFingerprint> {
            currentCoroutineContext().ensureActive()
            requireCurrent()
            check(installationSlot.tryAcquire())
            // The service retains this actual worker and its slot. SDK cancellation only
            // abandons the reply; it cannot release the writer or race native service close.
            val result = launchEmployeeOwned(RpcHostTrustReadWork(original, exactAppId, expected, hostRegistration), awaitActualWork = true)
            currentCoroutineContext().ensureActive()
            requireCurrent()
            return when (result) {
                is LocalCompanyAuthorityResult.Value -> result.value
                is LocalCompanyAuthorityResult.Unavailable -> protectedLocalUnavailable()
            }
        }
        override suspend fun readPeerAssociation(exactAppId: AppId, peer: PeerIdentity): LocalMasterRpcPeerAssociation? {
            currentCoroutineContext().ensureActive()
            requireCurrent()
            check(installationSlot.tryAcquire())
            val result = launchEmployeeOwned(RpcHostPeerAssociationReadWork(original, exactAppId, peer, hostRegistration), awaitActualWork = true)
            currentCoroutineContext().ensureActive()
            requireCurrent()
            return when (result) {
                is LocalCompanyAuthorityResult.Value -> result.value.also { check(it == null || it.isCurrent()) }
                is LocalCompanyAuthorityResult.Unavailable -> protectedLocalUnavailable()
            }
        }
    }
    private inner class RpcHostTrustReadWork(original: AndroidOriginalLocalClient, val exactAppId: AppId,
        val expected: Set<PeerFingerprint>?, val hostRegistration: AndroidOriginalRpcHostRegistration? = null):
        DevicePairingWork<Set<PeerFingerprint>>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<Set<PeerFingerprint>> {
            check(hostRegistration == null || ownsRpcHostRegistration(hostRegistration))
            val native = sameDevicePairingBridge(original).native
            val pins = checkNotNull(composition).originalRpcTrust(native).readCurrent(exactAppId, expected)
            check(ownsOriginalClient(original) && (hostRegistration == null || ownsRpcHostRegistration(hostRegistration)))
            return LocalCompanyAuthorityResult.Value(pins)
        }
    }
    private inner class RpcHostPeerAssociationReadWork(original: AndroidOriginalLocalClient, val exactAppId: AppId,
        val peer: PeerIdentity, val hostRegistration: AndroidOriginalRpcHostRegistration? = null):
        DevicePairingWork<LocalMasterRpcPeerAssociation?>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalMasterRpcPeerAssociation?> {
            check(hostRegistration == null || ownsRpcHostRegistration(hostRegistration))
            val native = sameDevicePairingBridge(original).native
            val association = checkNotNull(composition).originalRpcTrust(native).readPeerAssociation(exactAppId, peer)
            check(ownsOriginalClient(original) && (hostRegistration == null || ownsRpcHostRegistration(hostRegistration)) &&
                (association == null || association.isCurrent()))
            return LocalCompanyAuthorityResult.Value(association)
        }
    }
    private inner class DevicePairingPrepareWork(original: AndroidOriginalLocalClient, val requestId: DomainId,
        val target: LocalDevicePairingTargetDescriptor, val sdkIdentity: LocalDevicePairingSdkIdentityV2?):
        DevicePairingWork<LocalDevicePairingAction>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
            sameDevicePairingBridge(original).prepare(requestId, target, sdkIdentity).also { check(ownsOriginalClient(original)) }
    }
    private inner class DevicePairingProofWork(original: AndroidOriginalLocalClient, val action: LocalDevicePairingAction,
        val possession: LocalDevicePairingPossession): DevicePairingWork<LocalDevicePairingAction>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
            sameDevicePairingBridge(original).proveTarget(action, possession).also { check(ownsOriginalClient(original)) }
    }
    private inner class DevicePairingApproveWork(original: AndroidOriginalLocalClient, val action: LocalDevicePairingAction,
        val headLogin: String, val headSecret: CharArray): DevicePairingWork<LocalDevicePairingApproval>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalDevicePairingApproval> =
            sameDevicePairingBridge(original).approve(action, headLogin, headSecret).also { check(ownsOriginalClient(original)) }
        override fun clearInput() { headSecret.fill('\u0000') }
    }
    private inner class DevicePairingReviewWork(original: AndroidOriginalLocalClient, val id: DomainId): DevicePairingWork<LocalDevicePairingAction>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
            sameDevicePairingBridge(original).native.review(id).also { check(ownsOriginalClient(original)) }
    }
    private inner class DevicePairingRecoveryWork(original: AndroidOriginalLocalClient, val id: DomainId): DevicePairingWork<LocalDevicePairingApproval>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalDevicePairingApproval> =
            sameDevicePairingBridge(original).native.recoverApproval(id).also { check(ownsOriginalClient(original)) }
    }
    private inner class DevicePairingRetainedWork(original: AndroidOriginalLocalClient): DevicePairingWork<List<LocalDevicePairingReference>>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> =
            sameDevicePairingBridge(original).native.retained().also { check(ownsOriginalClient(original)) }
    }
    private inner class DevicePairingUnsignedRetirementWork(original: AndroidOriginalLocalClient, val id: DomainId,
        val recovery: Boolean): DevicePairingWork<LocalDevicePairingUnsignedRetirement>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
            sameDevicePairingBridge(original).let { bridge ->
                if (recovery) bridge.native.recoverUnsignedRetirement(id) else bridge.retireKnownUnsigned(id)
            }.also { check(ownsOriginalClient(original)) }
    }
    // END isolated device-pairing service work V1.

    internal suspend fun admitOriginalDevice(original: AndroidOriginalLocalClient,
        approval: LocalDevicePairingApproval): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return LocalCredentialAdministrationResult.Unavailable
        val requested = approval.requested
        if (requested.request.scope != original.peer.scope || requested.sdkIdentity == null ||
            requested.request.action != LocalProvisioningAction.ADMIT_DEVICE ||
            requested.admission.scope != original.peer.scope || !requested.admission.admitted ||
            requested.admission.revision != Revision(0) || requested.admission.deviceId == original.peer.deviceId)
            return LocalCredentialAdministrationResult.Denied
        if (!installationSlot.tryAcquire()) return LocalCredentialAdministrationResult.Busy
        return launchEmployeeOwned(DeviceAdmissionWork(original, approval))
    }
    internal suspend fun lookupOriginalDeviceAdmission(original: AndroidOriginalLocalClient,
        originalActionId: DomainId): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return LocalProvisioningStatus.Unavailable
        if (!installationSlot.tryAcquire()) return LocalProvisioningStatus.Busy
        return launchEmployeeOwned(DeviceAdmissionLookupWork(original, originalActionId))
    }
    private suspend fun sameDeviceAdmissionBridge(original: AndroidOriginalLocalClient): RoomSelfOwnedDeviceAdmissions {
        // Reuse the actual original pairing producer and composition, never another
        // credential service, protected reader, native owner or generic authority.
        val native = sameDevicePairingBridge(original).native
        check(ownsOriginalClient(original) && native.belongsTo(custody))
        return checkNotNull(composition).originalDeviceAdmissions(native)
    }
    private inner class DeviceAdmissionWork(override val original: AndroidOriginalLocalClient,
        val approval: LocalDevicePairingApproval): EmployeeWork<LocalCredentialAdministrationResult> {
        override fun unavailable(): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Unavailable
        override fun busy(): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Busy
        override suspend fun perform(): LocalCredentialAdministrationResult =
            sameDeviceAdmissionBridge(original).admit(approval).also {
                // Endpoint settlement/cleanup has completed. Later withdrawal may hide
                // delivery; it cannot erase a durable admission or authorize another one.
                check(ownsOriginalClient(original))
            }
    }
    private inner class DeviceAdmissionLookupWork(override val original: AndroidOriginalLocalClient,
        val originalActionId: DomainId): EmployeeWork<LocalProvisioningStatus> {
        override fun unavailable(): LocalProvisioningStatus = LocalProvisioningStatus.Unavailable
        override fun busy(): LocalProvisioningStatus = LocalProvisioningStatus.Busy
        override suspend fun perform(): LocalProvisioningStatus =
            sameDeviceAdmissionBridge(original).lookup(originalActionId).also { check(ownsOriginalClient(original)) }
    }

    internal suspend fun originalEmployeeDefinition(value: AndroidLocalEmployeeEnrollmentConnection, requestId: DomainId,
        login: LocalLoginIdentifier): LocalAccountProvisioning? {
        currentCoroutineContext().ensureActive()
        if (!ownsEmployeeEnrollment(value) || requestId != value.originalRequestId || login != value.requested.definition.login) return null
        if (!installationSlot.tryAcquire()) return null
        return launchEmployeeOwned(EmployeeDefinitionWork(value, requestId, login))
    }
    internal suspend fun provisionOriginalEmployee(value: AndroidLocalEmployeeEnrollmentConnection, requestId: DomainId,
        definition: LocalAccountProvisioning, password: CharArray?): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (!ownsEmployeeEnrollment(value) || requestId != value.originalRequestId || password != null && password.size !in 4..1024)
            return LocalCredentialAdministrationResult.Unavailable
        if (!value.permitsProvisioning) return LocalCredentialAdministrationResult.Denied
        if (!installationSlot.tryAcquire()) return LocalCredentialAdministrationResult.Busy
        var owned: CharArray? = null
        var transferred = false
        try {
            owned = password?.copyOf()
            val task = EmployeeProvisionWork(value, requestId, definition, owned)
            transferred = true
            return launchEmployeeOwned(task)
        } finally { if (!transferred) { owned?.fill('\u0000'); installationSlot.release() } }
    }
    internal suspend fun lookupOriginalEmployee(value: AndroidLocalEmployeeEnrollmentConnection, requestId: DomainId,
        definition: LocalAccountProvisioning, credentialWasProvided: Boolean): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (!ownsEmployeeEnrollment(value) || requestId != value.originalRequestId) return LocalProvisioningStatus.Unavailable
        if (!installationSlot.tryAcquire()) return LocalProvisioningStatus.Busy
        return launchEmployeeOwned(EmployeeLookupWork(value, requestId, definition, credentialWasProvided))
    }
    internal suspend fun originalConsumedPhysicalEmployees(original: AndroidOriginalLocalClient):
        LocalCompanyAuthorityResult<List<OriginalPhysicalEmployeeEnrollment>> {
        currentCoroutineContext().ensureActive()
        if (!ownsOriginalClient(original)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeePhysicalHistoryWork(original))
    }

    internal suspend fun acquireEmployeeClient(value: AndroidLocalEmployeeEnrollmentConnection): LocalCompanyAuthorityResult<AndroidEmployeeClientCustody> {
        currentCoroutineContext().ensureActive()
        if (!ownsEmployeeEnrollment(value)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeeCustodyWork(value))
    }
    internal suspend fun acquireEmployeeJournalAction(value: AndroidEmployeeClientCustody, action: NativeEmployeeClientJournalAction):
        LocalCompanyAuthorityResult<NativeEmployeeJournalAllocation> {
        currentCoroutineContext().ensureActive()
        if (!ownsEmployeeClient(value)) return employeeAuthorityUnavailable()
        if (!installationSlot.tryAcquire()) return employeeAuthorityUnavailable(busy = true)
        return launchEmployeeOwned(EmployeeJournalActionWork(value, action))
    }

    /** Closed service-owned tasks share the ACTUAL installation slot/job joined by close.
     * Caller cancellation only abandons response.await; it cannot cancel accepted work,
     * release the slot/password, infer rollback, or authorize another dispatch. */
    private suspend fun <T> launchEmployeeOwned(task: EmployeeWork<T>, awaitActualWork: Boolean = false): T {
        var launched = false
        try {
            val response = CompletableDeferred<T>()
            val work = synchronized(lifecycleGate) {
                if (!ownsOriginalClient(task.original)) return task.unavailable()
                if (installation.get()?.isCompleted == false) return task.busy()
                scope.launch(start = CoroutineStart.LAZY) {
                    try { response.complete(task.perform()) }
                    catch (_: Throwable) { response.complete(task.unavailable()) }
                    finally { try { task.clearInput() } finally { installationSlot.release() } }
                }.also { installation.set(it); launched = true }
            }
            work.start()
            val result = try { response.await() }
            finally {
                // A cancelled SDK callback must also await this exact retained Job's real
                // finally/slot release before unwinding into SDK cleanup or another callback.
                // This owns no lifecycle gate or native/Room loan and accepts no cleanup flag.
                if (awaitActualWork) withContext(NonCancellable) { work.join() }
            }
            if (awaitActualWork) currentCoroutineContext().ensureActive()
            return result
        } finally { if (!launched) { try { task.clearInput() } finally { installationSlot.release() } } }
    }
    private sealed interface EmployeeWork<T> {
        val original: AndroidOriginalLocalClient
        fun unavailable(): T
        fun busy(): T
        suspend fun perform(): T
        fun clearInput() {}
    }
    private abstract inner class EmployeeAuthorityWork<T>(final override val original: AndroidOriginalLocalClient):
        EmployeeWork<LocalCompanyAuthorityResult<T>> {
        override fun unavailable(): LocalCompanyAuthorityResult<T> = employeeAuthorityUnavailable()
        override fun busy(): LocalCompanyAuthorityResult<T> = employeeAuthorityUnavailable(busy = true)
    }
    private suspend fun sameEmployeeBridge(original: AndroidOriginalLocalClient): RoomSelfOwnedEmployeeEnrollments {
        check(ownsOriginalClient(original))
        val native = custody.readOriginalEmployeeEnrollments()
        check(native is LocalCompanyAuthorityResult.Value)
        check(ownsOriginalClient(original))
        return checkNotNull(composition).originalEmployeeEnrollments(native.value)
    }
    private suspend fun sameEmployeePolicyBridge(original: AndroidOriginalLocalClient): RoomSelfOwnedEmployeePolicyActivations {
        check(ownsOriginalClient(original))
        val native = custody.readOriginalEmployeePolicyActivations()
        check(native is LocalCompanyAuthorityResult.Value)
        check(ownsOriginalClient(original))
        return checkNotNull(composition).originalEmployeePolicyActivations(native.value)
    }
    private inner class EmployeePolicyPrepareWork(original: AndroidOriginalLocalClient, val requestId: DomainId,
        val nextPolicy: ActivatedLocalPolicy): EmployeeAuthorityWork<LocalEmployeePolicyActivationAction>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
            sameEmployeePolicyBridge(original).prepare(requestId, nextPolicy).also { check(ownsOriginalClient(original)) }
    }
    private inner class EmployeePolicyApproveWork(original: AndroidOriginalLocalClient, val action: LocalEmployeePolicyActivationAction,
        val headLogin: String, val headSecret: CharArray): EmployeeAuthorityWork<LocalEmployeePolicyActivationAuthorityReceipt>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
            sameEmployeePolicyBridge(original).approve(action, headLogin, headSecret) { check(ownsOriginalClient(original)) }
        override fun clearInput() { headSecret.fill('\u0000') }
    }
    private inner class EmployeePolicyReviewWork(original: AndroidOriginalLocalClient, val id: DomainId):
        EmployeeAuthorityWork<LocalEmployeePolicyActivationAction>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
            sameEmployeePolicyBridge(original).native.review(id).also { check(ownsOriginalClient(original)) }
    }
    private inner class EmployeePolicyRecoveryWork(original: AndroidOriginalLocalClient, val id: DomainId, val late: Boolean):
        EmployeeAuthorityWork<LocalEmployeePolicyActivationAuthorityReceipt>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
            sameEmployeePolicyBridge(original).recoverApproval(id, late) { check(ownsOriginalClient(original)) }
    }
    private inner class EmployeePolicyRetainedWork(original: AndroidOriginalLocalClient):
        EmployeeAuthorityWork<List<LocalEmployeePolicyActivationReference>>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> =
            sameEmployeePolicyBridge(original).native.retained().also { check(ownsOriginalClient(original)) }
    }
    private inner class EmployeePolicyActivateWork(override val original: AndroidOriginalLocalClient,
        val approval: LocalEmployeePolicyActivationAuthorityReceipt, val nextPolicy: ActivatedLocalPolicy):
        EmployeeWork<LocalCredentialAdministrationResult> {
        override fun unavailable(): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Unavailable
        override fun busy(): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Busy
        override suspend fun perform(): LocalCredentialAdministrationResult =
            sameEmployeePolicyBridge(original).activate(approval, nextPolicy) { check(ownsOriginalClient(original)) }
    }
    private inner class EmployeePolicyLookupWork(override val original: AndroidOriginalLocalClient,
        val approval: LocalEmployeePolicyActivationAuthorityReceipt, val nextPolicy: ActivatedLocalPolicy): EmployeeWork<LocalProvisioningStatus> {
        override fun unavailable(): LocalProvisioningStatus = LocalProvisioningStatus.Unavailable
        override fun busy(): LocalProvisioningStatus = LocalProvisioningStatus.Busy
        override suspend fun perform(): LocalProvisioningStatus = sameEmployeePolicyBridge(original).lookup(approval, nextPolicy)
    }

    private fun publishEmployeeApproval(original: AndroidOriginalLocalClient, bridge: RoomSelfOwnedEmployeeEnrollments,
        approval: LocalEmployeeEnrollmentAuthorityReceipt, permitsProvisioning: Boolean = true):
        LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> = synchronized(lifecycleGate) {
        check(ownsOriginalClient(original) && approval.requested.request.scope == original.peer.scope &&
            original.peer.deviceId in approval.requested.definition.admittedDeviceIds && approval.requested.request.targetId != original.material.coordinates.firstActorId)
        employeeConnections.singleOrNull { it.originalActionId == approval.originalActionId }?.let {
            check(it.original === original && it.bridge === bridge && it.approval.authorityDigest == approval.authorityDigest && ownsEmployeeEnrollment(it))
            val retained = it.requested.copyWireBytes(); val recovered = approval.requested.copyWireBytes()
            try { check(retained.contentEquals(recovered)) } finally { retained.fill(0); recovered.fill(0) }
            // An existing genuinely approved warm handle retains its original authority;
            // recovering data never upgrades a lookup-only connection into dispatch.
            check(!permitsProvisioning || it.permitsProvisioning)
            return@synchronized LocalCompanyAuthorityResult.Value(it)
        }
        check(employeeConnections.size < LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS)
        val value = AndroidLocalEmployeeEnrollmentConnection(this, original, bridge, approval, permitsProvisioning)
        employeeConnections = employeeConnections + value
        if (employeeEnrollmentClient == null) employeeEnrollmentClient = AndroidOriginalEmployeeEnrollmentClient(this, original)
        LocalCompanyAuthorityResult.Value(value)
    }
    private inner class EmployeePrepareWork(original: AndroidOriginalLocalClient, val requestId: DomainId,
        val definition: LocalAccountProvisioning, val credentialWasProvided: Boolean): EmployeeAuthorityWork<LocalEmployeeEnrollmentAction>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
            sameEmployeeBridge(original).prepare(requestId, definition, credentialWasProvided)
    }
    private inner class EmployeeApproveWork(original: AndroidOriginalLocalClient, val action: LocalEmployeeEnrollmentAction,
        val headLogin: String, val headSecret: CharArray): EmployeeAuthorityWork<AndroidLocalEmployeeEnrollmentConnection>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> {
            val bridge = sameEmployeeBridge(original)
            return when (val approved = bridge.native.approve(action, headLogin, headSecret)) {
                is LocalCompanyAuthorityResult.Value -> {
                    check(approved.value.originalActionId == action.originalActionId && approved.value.requested.definitionDigest == action.requested.definitionDigest)
                    publishEmployeeApproval(original, bridge, approved.value)
                }
                is LocalCompanyAuthorityResult.Unavailable -> approved
            }
        }
        override fun clearInput() { headSecret.fill('\u0000') }
    }
    private inner class EmployeeApprovalRecoveryWork(original: AndroidOriginalLocalClient, val originalActionId: DomainId):
        EmployeeAuthorityWork<AndroidLocalEmployeeEnrollmentConnection>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> {
            val bridge = sameEmployeeBridge(original)
            return when (val retained = bridge.native.recoverApproval(originalActionId)) {
                is LocalCompanyAuthorityResult.Value -> publishEmployeeApproval(original, bridge, retained.value)
                is LocalCompanyAuthorityResult.Unavailable -> retained
            }
        }
    }
    private inner class EmployeeConnectionRecoveryWork(original: AndroidOriginalLocalClient, val originalActionId: DomainId):
        EmployeeAuthorityWork<AndroidLocalEmployeeEnrollmentConnection>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection> {
            val bridge = sameEmployeeBridge(original)
            return when (val retained = bridge.native.recoverConnection(originalActionId)) {
                is LocalCompanyAuthorityResult.Value -> {
                    check(retained.value.originalActionId == originalActionId)
                    publishEmployeeApproval(original, bridge, retained.value, permitsProvisioning = false)
                }
                is LocalCompanyAuthorityResult.Unavailable -> retained
            }
        }
    }
    private inner class EmployeeReviewWork(original: AndroidOriginalLocalClient, val originalActionId: DomainId):
        EmployeeAuthorityWork<LocalEmployeeEnrollmentAction>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
            sameEmployeeBridge(original).native.review(originalActionId)
    }
    private inner class EmployeeRetainedWork(original: AndroidOriginalLocalClient):
        EmployeeAuthorityWork<List<LocalEmployeeEnrollmentReference>>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> =
            sameEmployeeBridge(original).native.retained()
    }
    private inner class EmployeeDefinitionWork(val value: AndroidLocalEmployeeEnrollmentConnection, val requestId: DomainId,
        val login: LocalLoginIdentifier): EmployeeWork<LocalAccountProvisioning?> {
        override val original: AndroidOriginalLocalClient get() = value.original
        override fun unavailable(): LocalAccountProvisioning? = null
        override fun busy(): LocalAccountProvisioning? = null
        override suspend fun perform(): LocalAccountProvisioning? {
            check(ownsEmployeeEnrollment(value))
            val retained = value.bridge.native.originalDefinition(requestId, login) ?: return null
            val actual = retained.copyWireBytes(); val approved = value.requested.copyWireBytes()
            try { check(actual.contentEquals(approved)) } finally { actual.fill(0); approved.fill(0) }
            return retained.definition.takeIf { ownsEmployeeEnrollment(value) }
        }
    }
    private inner class EmployeeProvisionWork(val value: AndroidLocalEmployeeEnrollmentConnection, val requestId: DomainId,
        val definition: LocalAccountProvisioning, val password: CharArray?): EmployeeWork<LocalCredentialAdministrationResult> {
        override val original: AndroidOriginalLocalClient get() = value.original
        override fun unavailable(): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Unavailable
        override fun busy(): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Busy
        override suspend fun perform(): LocalCredentialAdministrationResult {
            check(ownsEmployeeEnrollment(value) && value.permitsProvisioning)
            if (!value.bridge.matchesRetained(value.requested, requestId, definition, password != null)) return LocalCredentialAdministrationResult.Denied
            // Irreversible native original dispatch is forced/read back BEFORE endpoint password/hash effects.
            val dispatch = value.bridge.native.beginAccountProvisioning(value.requested.request)
            val endpoint = checkNotNull(composition?.endpoint)
            val result = endpoint.provisionOriginalEmployee(value.bridge, dispatch, definition, password)
            if (result !is LocalCredentialAdministrationResult.Applied) {
                // The exact accepted endpoint work has REALLY finished and cleaned its native
                // Room wait. Native still independently requires no bound frame/unchanged cut.
                // A false/unknown result is retained for original-ID lookup, never redispatched.
                value.bridge.native.finishKnownNoEffect(dispatch)
            }
            return result
        }
        override fun clearInput() { password?.fill('\u0000') }
    }
    private inner class EmployeeLookupWork(val value: AndroidLocalEmployeeEnrollmentConnection, val requestId: DomainId,
        val definition: LocalAccountProvisioning, val credentialWasProvided: Boolean): EmployeeWork<LocalProvisioningStatus> {
        override val original: AndroidOriginalLocalClient get() = value.original
        override fun unavailable(): LocalProvisioningStatus = LocalProvisioningStatus.Unavailable
        override fun busy(): LocalProvisioningStatus = LocalProvisioningStatus.Busy
        override suspend fun perform(): LocalProvisioningStatus {
            check(ownsEmployeeEnrollment(value))
            if (!value.bridge.matchesRetained(value.requested, requestId, definition, credentialWasProvided)) return LocalProvisioningStatus.Denied
            return checkNotNull(composition?.endpoint).lookupOriginalEmployee(value.bridge, requestId, definition, credentialWasProvided)
        }
    }
    private inner class EmployeePhysicalHistoryWork(original: AndroidOriginalLocalClient):
        EmployeeAuthorityWork<List<OriginalPhysicalEmployeeEnrollment>>(original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<List<OriginalPhysicalEmployeeEnrollment>> {
            val bridge = sameEmployeeBridge(original)
            val references = when (val retained = bridge.native.retained()) {
                is LocalCompanyAuthorityResult.Value -> retained.value
                is LocalCompanyAuthorityResult.Unavailable -> return retained
            }
            val consumed = references.filter { it.phase == LocalEmployeeEnrollmentPhase.CONSUMED }
            check(consumed.size <= LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS &&
                consumed.map { it.actorId }.distinct().size == consumed.size &&
                consumed.map { it.originalActionId }.distinct().size == consumed.size)
            val witnesses = consumed.map { reference ->
                // Selection by retained original ID is DATA. ONLY this native acquisition joins
                // actual consumed dispatch/final observation or the cold full admitted sources.
                val native = bridge.native.acquireConsumedEmployee(reference.originalRequestId)
                check(native.originalActionId == reference.originalActionId && native.actorId == reference.actorId)
                originalPhysicalEmployeeEnrollment(original, native)
            }
            check(ownsOriginalClient(original) && witnesses.all { it.belongsTo(original) })
            return LocalCompanyAuthorityResult.Value(witnesses)
        }
    }

    private inner class EmployeeCustodyWork(val value: AndroidLocalEmployeeEnrollmentConnection): EmployeeAuthorityWork<AndroidEmployeeClientCustody>(value.original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<AndroidEmployeeClientCustody> {
            check(ownsEmployeeEnrollment(value))
            val native = value.bridge.native.acquireConsumedEmployee(value.originalRequestId)
            check(native.request == value.requested.request && native.originalActionId == value.originalActionId && native.actorId == value.actorId &&
                native.deviceId == original.peer.deviceId && native.scope == original.peer.scope &&
                native.installationId == original.material.coordinates.installationId && native.enrollmentDefinitionDigest == value.requested.definitionDigest)
            return synchronized(lifecycleGate) {
                check(ownsEmployeeEnrollment(value) && native.isCurrent())
                employeeClients.singleOrNull { it.enrollment === value }?.let { check(it.native === native); return@synchronized LocalCompanyAuthorityResult.Value(it) }
                check(employeeClients.size < LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS)
                val client = AndroidEmployeeClientCustody(this@AndroidSelfOwnedMasterService, value, native)
                employeeClients = employeeClients + client
                LocalCompanyAuthorityResult.Value(client)
            }
        }
    }
    private inner class EmployeeJournalActionWork(val value: AndroidEmployeeClientCustody, val action: NativeEmployeeClientJournalAction):
        EmployeeAuthorityWork<NativeEmployeeJournalAllocation>(value.enrollment.original) {
        override suspend fun perform(): LocalCompanyAuthorityResult<NativeEmployeeJournalAllocation> {
            check(ownsEmployeeClient(value))
            val allocation = value.native.acquireJournalAction(action)
            check(ownsEmployeeClient(value) && allocation.isCurrent())
            return LocalCompanyAuthorityResult.Value(allocation)
        }
    }
    private fun employeeAuthorityUnavailable(busy: Boolean = false): LocalCompanyAuthorityResult.Unavailable =
        LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE,
            if (busy) SelfOwnedInstallationFailureV1.BUSY else SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)

    private suspend fun start() {
        when(val selected=AndroidRuntimeHistoryStartup.select(application,custody,historyProducer,stockProjection)) {
            is AndroidRuntimeHistoryStartup.Selection.Allocation -> life.value=LocalMasterLifecycleMode.ALLOCATION_ONLY
            is AndroidRuntimeHistoryStartup.Selection.Held -> life.value=LocalMasterLifecycleMode.UNRESOLVED
            is AndroidRuntimeHistoryStartup.Selection.AcknowledgementPending -> {
                reopenedAcknowledgement=selected.original
                life.value=LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING
            }
            is AndroidRuntimeHistoryStartup.Selection.Opened -> {
                val composed=compose(selected.owner)
                installedReceipt=checkNotNull(selected.owner.originalRecord.record.receipt)
                // A completely verified installation can be inspected while the desired
                // master role is STOP. Receipt display never grants RUN or business ingress.
                if(installationOnly) life.value=LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD
                else if(selected.owner.operational) {
                    activate(composed); life.value=LocalMasterLifecycleMode.OPERATIONAL
                } else life.value=LocalMasterLifecycleMode.POLICY_HELD
            }
        }
    }
    private suspend fun compose(owner: RoomRuntimeHistoryOwner): RoomSelfOwnedMasterComposition {
        check(composition==null && roomOwner==null)
        // Capture actual Room ownership BEFORE any suspending peer/security construction.
        // Failure here still drains/closes this owner rather than dropping a live Room pool.
        roomOwner=owner
        val actualPeer=custody.originalLocalPeer(); peer=actualPeer
        return RoomSelfOwnedMasterComposition(owner,custody.runtimeSecurity,actualPeer).also { composition=it }
    }
    private fun activate(composed: RoomSelfOwnedMasterComposition) {
        check(!installationOnly)
        composed.openOperational(handlers,reads,object : SerialRegistrationMasterClock {
            override fun now(): UtcInstant = UtcInstant(Clock.System.now())
            // The actual owner provides boot/elapsed observations for session lifetimes.
            // Serial time source qualification and supported precision are still unbound;
            // keep this audit clock without promoting it to qualified serial time.
            override fun qualifiedSerialRegistrationTime(): QualifiedSerialRegistrationTime? = null
        },
            LocalMasterRetirement { retire() },watchdog,Dispatchers.Default,physical,imports,stockProjection,historyProducer)
    }

    /**
     * Narrow current-authority lifecycle ingress. It authenticates through the SAME already
     * constructed protected store/peer/environment as this original composition, then commits
     * STOP/RESUME in that store's serialized revocation transaction. Authentication is a fresh
     * password sign-in with an explicit same-device move, so an already active session cannot
     * silently reuse cached grants; the prior local session is revoked and retained in history.
     * RESUME is admitted only on
     * the installation-only held owner before any operational endpoint exists; STOP only on the
     * live operational owner. No handler, client, RPC bind or business intake is opened here.
     */
    public suspend fun authenticateAndApplyLifecycle(login: LocalLoginIdentifier, password: CharArray,
        request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult =
        authenticateLifecycle(login, password, request, heldStopRecovery = false)

    /** Explicit original STOP recovery on the endpoint-free, installation-only owner. */
    public suspend fun authenticateAndRecoverLifecycleStop(login: LocalLoginIdentifier, password: CharArray,
        request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult {
        if (request.action != LocalMasterLifecycleAction.STOP)
            return LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.WRONG_STATE)
        return authenticateLifecycle(login, password, request, heldStopRecovery = true)
    }

    private suspend fun authenticateLifecycle(login: LocalLoginIdentifier, password: CharArray,
        request: LocalMasterLifecycleRequest, heldStopRecovery: Boolean): LocalMasterLifecycleResult {
        currentCoroutineContext().ensureActive()
        if (password.size !in 4..1024) return LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.AUTHENTICATION_REFUSED)
        val held = request.action == LocalMasterLifecycleAction.RESUME || heldStopRecovery
        val required = if (held) LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD
            else LocalMasterLifecycleMode.OPERATIONAL
        if (held != installationOnly)
            return LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.WRONG_STATE)
        if (life.value != required || closing.get() != null) return LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.WRONG_STATE)
        val composed = composition ?: return LocalMasterLifecycleResult.Unavailable
        val actualPeer = peer ?: return LocalMasterLifecycleResult.Unavailable
        if (!actualPeer.isCurrent()) return LocalMasterLifecycleResult.Unavailable
        if (!installationSlot.tryAcquire()) return LocalMasterLifecycleResult.Busy
        try {
            // Recheck the same native peer/state after admitted work starts; a UI/thread race
            // cannot carry a stale pre-wait observation into the protected transaction.
            if (life.value != required || closing.get() != null || !actualPeer.isCurrent())
                return LocalMasterLifecycleResult.Denied(LocalMasterLifecycleDenial.WRONG_STATE)
            val owner = roomOwner ?: return LocalMasterLifecycleResult.Unavailable
            if (!sameCoordinates(originalMaterial.coordinates, owner.originalRecord.record.allocation.coordinates))
                return LocalMasterLifecycleResult.Unavailable
            // The operational Room owner is shared with the native-intake worker. Authenticate
            // and apply STOP as one typed item on that same worker, so no security loan races
            // an active owned Room invocation. Endpoint-free held recovery stays distinct.
            val result = if (!held) {
                val endpoint = composed.endpoint ?: return LocalMasterLifecycleResult.Unavailable
                endpoint.authenticateAndStopOriginal(actualPeer, login, password, request)
            } else authenticateOriginalLifecycle(composed.authentication, composed.lifecycle,
                actualPeer, login, password, request)
            currentCoroutineContext().ensureActive()
            return if (!actualPeer.isCurrent()) LocalMasterLifecycleResult.Unavailable else result
        } finally {
            installationSlot.release()
        }
    }

    /** Fresh native binding DATA from the actual Master-owned custody, not its inert context. */
    public suspend fun readOwnedOriginalNativeBinding(): SelfOwnedNativeReadResult {
        currentCoroutineContext().ensureActive()
        if (!installationSlot.tryAcquire())
            return SelfOwnedNativeReadResult.Unresolved(SelfOwnedInstallationFailureV1.BUSY)
        return try {
            if (closing.get() != null || life.value != LocalMasterLifecycleMode.OPERATIONAL)
                return SelfOwnedNativeReadResult.Unresolved(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            val result = custody.readOwnedOriginalNativeBinding()
            currentCoroutineContext().ensureActive()
            if (closing.get() != null || life.value != LocalMasterLifecycleMode.OPERATIONAL)
                SelfOwnedNativeReadResult.Unresolved(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            else result
        } finally { installationSlot.release() }
    }

    /** Original-only setup inspection. Missing history never becomes a new installation. */
    public suspend fun readOriginalSetup(): LocalOriginalInstallationSetup {
        currentCoroutineContext().ensureActive()
        // A successful install/ACK response can precede its final secret cleanup.
        // Wait cancellably for the actual slot release, then recheck native state.
        installationSlot.acquire()
        try {
            check(closing.get()==null && life.value in setOf(LocalMasterLifecycleMode.ALLOCATION_ONLY,
                LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING,LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD,
                LocalMasterLifecycleMode.OPERATIONAL,LocalMasterLifecycleMode.POLICY_HELD))
            val original=custody.snapshotOriginal()
            val company=custody.readOriginalCompanyAuthority()
            val c=original.record.allocation.coordinates
            check(company.organizationId==c.scope.organizationId && company.companyId==c.scope.companyId)
            val receipt = if (life.value==LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING) {
                val record=original.record
                check(record.pending==null && record.parent!=null && record.firstChild!=null &&
                    record.attempt?.phase==NativeHistoryAttemptPhaseV2.COMPLETED)
                checkNotNull(record.receipt)
            } else installedReceipt
            check(closing.get()==null)
            return LocalOriginalInstallationSetup(company,original.record.definition,receipt)
        } finally { installationSlot.release() }
    }

    /**
     * Read-only observation of the custody-bound original ACK. Pending and failed runtime
     * admission do not hide a native receipt. This neither continues ACK nor admits history,
     * opens Room, creates an owner or publishes an endpoint.
     */
    public suspend fun readOriginalAcknowledgement(): LocalOriginalAcknowledgementRead {
        currentCoroutineContext().ensureActive()
        if (closing.get()!=null) return LocalOriginalAcknowledgementRead.Unresolved
        if (!installationSlot.tryAcquire()) return LocalOriginalAcknowledgementRead.Busy
        return try { observeOriginalAcknowledgement() } finally { installationSlot.release() }
    }

    /** Wait only for the retained owner's current work to release its slot, then observe ACK
     * data. Cancelling this caller neither cancels native work nor submits an ACK. There is no
     * timeout-to-failure inference, replacement attempt, owner acquisition or SQL replay. */
    public suspend fun awaitOriginalAcknowledgement(): LocalOriginalAcknowledgementRead {
        currentCoroutineContext().ensureActive()
        if (closing.get()!=null) return LocalOriginalAcknowledgementRead.Unresolved
        installationSlot.acquire()
        return try { observeOriginalAcknowledgement() } finally { installationSlot.release() }
    }

    /** The installation slot is held by the caller throughout the native observation. */
    private suspend fun observeOriginalAcknowledgement(): LocalOriginalAcknowledgementRead {
        try {
            if (closing.get()!=null) return LocalOriginalAcknowledgementRead.Unresolved
            installedReceipt?.let { receipt ->
                return LocalOriginalAcknowledgementRead.Observed(
                    LocalOriginalAcknowledgementStatus.CONFIRMED,receipt)
            }
            val record=custody.snapshotOriginal().record
            val receipt=record.receipt ?: return LocalOriginalAcknowledgementRead.Unresolved
            if (record.parent==null || record.firstChild==null ||
                record.attempt?.phase!=NativeHistoryAttemptPhaseV2.COMPLETED || closing.get()!=null)
                return LocalOriginalAcknowledgementRead.Unresolved
            val status=if (record.issuerAcknowledgement!=null) LocalOriginalAcknowledgementStatus.CONFIRMED
                else if (record.pending==null) LocalOriginalAcknowledgementStatus.PENDING
                else return LocalOriginalAcknowledgementRead.Unresolved
            currentCoroutineContext().ensureActive()
            return LocalOriginalAcknowledgementRead.Observed(status,receipt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LocalOriginalAcknowledgementRead.Unresolved
        }
    }

    /**
     * Builds the COMPLETE data review from the actual original allocation and founding human.
     * No password is accepted, no native attempt is issued, and no policy or account is inserted.
     * An already retained definition cannot be replaced. Durable original intent/approval and
     * protected-seed preflight still precede SQL in installOriginal.
     */
    public suspend fun prepareOriginalDefinition(firstAccount: LocalAccountProvisioning,
        authorityEpoch: Revision): LocalBranchInstallationSnapshotV2 {
        currentCoroutineContext().ensureActive()
        check(installationSlot.tryAcquire()) { "Original installation work is busy" }
        try {
            check(life.value==LocalMasterLifecycleMode.ALLOCATION_ONLY)
            val original=custody.snapshotOriginal()
            val record=original.record
            check(record.definition==null && record.database==null && record.parent==null &&
                record.pending==null && record.attempt==null && record.retiredAttempts.isEmpty())
            val c=record.allocation.coordinates
            val human=custody.readOriginalInstallationHumanReference()
            val admission=LocalDeviceAdmission(c.scope,c.deviceId,Revision(0),true,
                c.targetSigningKeyId.value,originalMaterial.targetSigningFingerprint,emptySet())
            val snapshot=LocalBranchInstallationCodecV2.snapshotDefinition(LocalBranchInstallationDefinitionV2(
                record.allocation,DomainId.random(),authorityEpoch,human,firstAccount,admission))
            LocalBranchInstallationCodecV2.preflight(snapshot).clear()
            check(life.value==LocalMasterLifecycleMode.ALLOCATION_ONLY)
            return snapshot
        } finally { installationSlot.release() }
    }

    /** One bounded clean installation work item. Input secrets are copied only after admission.
     * Cancellation abandons this wait, not the retained native attempt/actual SQL outcome. */
    public suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2,headLogin: String,
        headSecret: CharArray,firstEmployeePassword: CharArray): LocalMasterInstallationOutcome =
        submitOriginalInstallation(definition,headLogin,headSecret,firstEmployeePassword,acknowledge=true)

    /** Explicit two-stage installation: settle the genuine original SQL/native transaction,
     * retain its completion on this owner and stop before issuer acknowledgement. No business
     * endpoint is activated. Read the pending result or continue it through
     * [resumeOriginalAcknowledgement]; never resubmit the installation under another identity.
     * Caller cancellation abandons delivery only, as with [installOriginal]. */
    public suspend fun settleOriginal(definition: LocalBranchInstallationDefinitionV2,headLogin: String,
        headSecret: CharArray,firstEmployeePassword: CharArray): LocalMasterInstallationOutcome =
        submitOriginalInstallation(definition,headLogin,headSecret,firstEmployeePassword,acknowledge=false)

    private suspend fun submitOriginalInstallation(definition: LocalBranchInstallationDefinitionV2,headLogin: String,
        headSecret: CharArray,firstEmployeePassword: CharArray,acknowledge: Boolean): LocalMasterInstallationOutcome {
        currentCoroutineContext().ensureActive()
        if(headLogin.length !in 1..128 || headSecret.size !in 4..1024 || firstEmployeePassword.size !in 4..1024 ||
            life.value!=LocalMasterLifecycleMode.ALLOCATION_ONLY) return LocalMasterInstallationOutcome.Unresolved
        if(!installationSlot.tryAcquire()) return LocalMasterInstallationOutcome.Busy
        var head: CharArray?=null; var password: CharArray?=null; var launched=false
        try {
            val snapshot=LocalBranchInstallationCodecV2.snapshotDefinition(definition)
            head=headSecret.copyOf(); password=firstEmployeePassword.copyOf()
            val ownedHead=checkNotNull(head); val ownedPassword=checkNotNull(password)
            val response=CompletableDeferred<LocalMasterInstallationOutcome>()
            val work=synchronized(lifecycleGate) {
                if(closing.get()!=null || life.value!=LocalMasterLifecycleMode.ALLOCATION_ONLY)
                    return LocalMasterInstallationOutcome.Unresolved
                check(installation.get()==null)
                // Publish the admitted job under the same gate as close. A concurrent close
                // cannot observe INSTALLING with no job, close custody, then let work start.
                scope.launch(start=CoroutineStart.LAZY) {
                    var prepared: RoomSelfOwnedInstallationVerifier?=null
                    try {
                        val approved=RoomSelfOwnedInstallationVerifier.prepare(custody,snapshot.definition,headLogin,ownedHead,ownedPassword)
                        prepared=approved
                        val owner=AndroidRuntimeHistoryStartup.openFirst(application,custody)
                        val composed=compose(owner)
                        if (acknowledge) {
                            val receipt=composed.initializer.initialize(approved)
                            response.complete(completeInstallation(composed,receipt))
                        } else {
                            val receipt=composed.initializer.settle(approved)
                            check(composed.owner.hasUnacknowledgedOriginalInstallation &&
                                composed.owner.originalRecord.record.receipt?.digest==receipt.digest)
                            val pending=life.compareAndSet(LocalMasterLifecycleMode.INSTALLING,
                                LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING)
                            response.complete(if (pending && closing.get()==null)
                                LocalMasterInstallationOutcome.AcknowledgementPending
                                else LocalMasterInstallationOutcome.Unresolved)
                        }
                    } catch(failure: Throwable) {
                        // Only bounded static source locations. Throwable messages/arguments can
                        // contain secrets or protected records and must never enter diagnostics.
                        try {
                            val locations=failure.stackTrace.filter {
                                it.className.startsWith("com.ukkera.pos.")
                            }.take(6).joinToString(" <- ") {
                                "${it.className}.${it.methodName}:${it.lineNumber}"
                            }
                            android.util.Log.w("UkkeraNativeOwner",
                                "Original installation refused: ${failure.javaClass.simpleName}; $locations")
                        } catch (_: Throwable) { /* Diagnostics never affect the retained outcome. */ }
                        response.complete(originalInstallationFailure())
                    } finally {
                        ownedHead.fill('\u0000'); ownedPassword.fill('\u0000'); prepared?.clear(); installationSlot.release()
                    }
                }.also {
                    installation.set(it)
                    life.value=LocalMasterLifecycleMode.INSTALLING
                    launched=true
                }
            }
            work.start()
            return response.await()
        } finally { if(!launched) { head?.fill('\u0000'); password?.fill('\u0000'); installationSlot.release() } }
    }

    /** Explicit result completion only: same-writer settlement, or already native-settled C1
     * selected by complete closed-scan startup. No password, new identity or SQL replay.
     * Abandoning the caller does not cancel the native ACK job or release its custody. */
    public suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome {
        currentCoroutineContext().ensureActive()
        if(life.value==LocalMasterLifecycleMode.INSTALLING) return LocalMasterInstallationOutcome.Busy
        if(life.value!=LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING)
            return readCompletedOriginalAcknowledgement()
        if(!installationSlot.tryAcquire()) return LocalMasterInstallationOutcome.Busy
        var launched=false
        try {
            val response=CompletableDeferred<LocalMasterInstallationOutcome>()
            val work=synchronized(lifecycleGate) {
                if(closing.get()!=null || life.value!=LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING)
                    return LocalMasterInstallationOutcome.Unresolved
                // The previous attempt's cleanup/job tail is retained until it really ends.
                if(installation.get()?.isCompleted==false) return LocalMasterInstallationOutcome.Busy
                check(composition!=null || reopenedAcknowledgement!=null)
                scope.launch(start=CoroutineStart.LAZY) {
                    try {
                        val existing=composition
                        if(existing!=null) {
                            val receipt=existing.owner.resumeInstallationAcknowledgement()
                            response.complete(completeInstallation(existing,receipt))
                        } else response.complete(finishReopenedAcknowledgement())
                    } catch(failure: Throwable) {
                        response.complete(originalInstallationFailure())
                    } finally { installationSlot.release() }
                }.also {
                    installation.set(it)
                    life.value=LocalMasterLifecycleMode.INSTALLING
                    launched=true
                }
            }
            work.start()
            return response.await()
        } finally { if(!launched) installationSlot.release() }
    }

    /** Repeated completion observes the saved native original; it never issues another ACK. */
    private suspend fun readCompletedOriginalAcknowledgement(): LocalMasterInstallationOutcome {
        val completedModes=setOf(LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD,
            LocalMasterLifecycleMode.OPERATIONAL,LocalMasterLifecycleMode.POLICY_HELD,
            LocalMasterLifecycleMode.UNRESOLVED)
        if(life.value !in completedModes || closing.get()!=null)
            return LocalMasterInstallationOutcome.Unresolved
        if(!installationSlot.tryAcquire()) return LocalMasterInstallationOutcome.Busy
        try {
            if(life.value !in completedModes || closing.get()!=null)
                return LocalMasterInstallationOutcome.Unresolved
            // Runtime admission may have denied further DATA reads after the ACK succeeded.
            // Return that retained immutable completion, without claiming current admission.
            if(life.value==LocalMasterLifecycleMode.UNRESOLVED) installedReceipt?.let { receipt ->
                if(closing.get()==null) return LocalMasterInstallationOutcome.Installed(receipt)
            }
            val receipt=observeConfirmedOriginalReceipt() ?: return LocalMasterInstallationOutcome.Unresolved
            if(life.value !in completedModes || closing.get()!=null)
                return LocalMasterInstallationOutcome.Unresolved
            currentCoroutineContext().ensureActive()
            return LocalMasterInstallationOutcome.Installed(receipt)
        } catch(cancelled: CancellationException) {
            throw cancelled
        } catch(_: Exception) {
            return LocalMasterInstallationOutcome.Unresolved
        } finally { installationSlot.release() }
    }

    private suspend fun finishReopenedAcknowledgement(): LocalMasterInstallationOutcome {
        val recovered=checkNotNull(reopenedAcknowledgement)
        check(composition==null && roomOwner==null)
        custody.acknowledgeOriginalInstallation() // Native's retained original capability only.
        // This native call returned only after the exact original issuer ACK was retained.
        // Capture its immutable receipt BEFORE history/Room admission can deny native DATA.
        installedReceipt=checkNotNull(recovered.record.receipt)
        // Retain the original anchor until admission has completed. A post-ACK admission
        // failure must still be able to observe the confirmed native result on this owner.
        // ACK changes the native digest. Never open Room using the PRE-ACK scan/admission.
        // Re-traverse the original database, close/reobserve, and only then open one pool.
        val selected=AndroidRuntimeHistoryStartup.select(application,custody,historyProducer,stockProjection)
        check(selected is AndroidRuntimeHistoryStartup.Selection.Opened)
        val composed=compose(selected.owner)
        val receipt=checkNotNull(selected.owner.originalRecord.record.receipt)
        check(receipt.digest==recovered.record.receipt?.digest && receipt.genesis==recovered.record.receipt?.genesis)
        installedReceipt=receipt
        reopenedAcknowledgement=null
        return completeInstallation(composed,receipt)
    }

    private fun completeInstallation(composed: RoomSelfOwnedMasterComposition,
        receipt: LocalBranchInstallationReceiptV2): LocalMasterInstallationOutcome {
        installedReceipt=receipt
        // Actual installation completion is separate from business-service publication.
        if(life.value==LocalMasterLifecycleMode.INSTALLING) {
            if(installationOnly) life.compareAndSet(LocalMasterLifecycleMode.INSTALLING,
                LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD)
            else if(composed.owner.operational) {
                activate(composed)
                if(!life.compareAndSet(LocalMasterLifecycleMode.INSTALLING,LocalMasterLifecycleMode.OPERATIONAL)) composed.deny()
            } else life.compareAndSet(LocalMasterLifecycleMode.INSTALLING,LocalMasterLifecycleMode.POLICY_HELD)
        }
        return if(life.value in setOf(LocalMasterLifecycleMode.OPERATIONAL,
            LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD,LocalMasterLifecycleMode.POLICY_HELD))
            LocalMasterInstallationOutcome.Installed(receipt) else LocalMasterInstallationOutcome.Unresolved
    }

    /** Called while the installation slot is held. Native ACK fact is independent of admission. */
    private suspend fun observeConfirmedOriginalReceipt(): LocalBranchInstallationReceiptV2? {
        val expected=installedReceipt ?: reopenedAcknowledgement?.record?.receipt ?:
            composition?.owner?.originalRecord?.record?.receipt ?: return null
        val record=custody.snapshotOriginal().record
        val receipt=record.receipt ?: return null
        if(record.pending!=null || record.issuerAcknowledgement==null || record.parent==null ||
            record.firstChild==null || record.attempt?.phase!=NativeHistoryAttemptPhaseV2.COMPLETED ||
            receipt.digest!=expected.digest || receipt.genesis!=expected.genesis) return null
        return receipt
    }

    private suspend fun originalInstallationFailure(): LocalMasterInstallationOutcome {
        val confirmed=installedReceipt ?: try { observeConfirmedOriginalReceipt() } catch (_: Exception) { null }
        if(confirmed!=null) {
            installedReceipt=confirmed
            // A partial activation is never available as business ingress. Keep runtime held;
            // only the native saved result is returned, with no admission/reopen/ACK retry.
            try { composition?.deny() } catch (_: Exception) { /* Mode still denies publication. */ }
            life.compareAndSet(LocalMasterLifecycleMode.INSTALLING,LocalMasterLifecycleMode.UNRESOLVED)
            return if(life.value==LocalMasterLifecycleMode.UNRESOLVED && closing.get()==null)
                LocalMasterInstallationOutcome.Installed(confirmed) else LocalMasterInstallationOutcome.Unresolved
        }
        val next=if(composition?.owner?.hasUnacknowledgedOriginalInstallation==true || reopenedAcknowledgement!=null)
            LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING else LocalMasterLifecycleMode.UNRESOLVED
        life.compareAndSet(LocalMasterLifecycleMode.INSTALLING,next)
        return if(life.value==LocalMasterLifecycleMode.INSTALLATION_ACK_PENDING)
            LocalMasterInstallationOutcome.AcknowledgementPending else LocalMasterInstallationOutcome.Unresolved
    }
    private fun sameCoordinates(a: SelfOwnedAllocationCoordinatesV1, b: SelfOwnedAllocationCoordinatesV1): Boolean =
        a.allocationId == b.allocationId && a.scope == b.scope && a.installationId == b.installationId &&
            a.assignmentId == b.assignmentId && a.deviceId == b.deviceId && a.storageInstanceId == b.storageInstanceId &&
            a.storageKeyId == b.storageKeyId && a.pepperKeyId == b.pepperKeyId &&
            a.targetSigningKeyId == b.targetSigningKeyId && a.firstActorId == b.firstActorId
    /** Host retirement only; not UI locking, SQL fate, native close or lease release. */
    public fun denyNewWork() { retire() }
    private fun retire() {
        synchronized(lifecycleGate) {
            if(life.value==LocalMasterLifecycleMode.CLOSED) return
            (physical as? PhysicalRecordingOwnedExecutionGate)?.denyNewWork()
            composition?.deny()
            if(closing.get()==null) life.value=LocalMasterLifecycleMode.UNRESOLVED
        }
        custody.denyDataUse() // Native retains exclusion/actual pending roots, never a new owner.
    }
    /** True only after actual retained Host/worker drain, Room close and original P2 close. */
    public suspend fun quiesceAndClose(): Boolean {
        // Exactly one physical close is issued for this owner. Every later caller joins the
        // same cached completion and observes its result; it is not a retry and must never
        // issue another native close or replace a failed/unconfirmed outcome.
        val (completion,startClose)=synchronized(lifecycleGate) {
            val existing=closing.get()
            if(existing!=null) existing to false
            else CompletableDeferred<Boolean>().also {
                closing.set(it); life.value=LocalMasterLifecycleMode.CLOSING
            } to true
        }
        if(!startClose) return completion.await()
        (physical as? PhysicalRecordingOwnedExecutionGate)?.denyNewWork()
        composition?.deny()
        scope.launch {
            try {
                // CLOSING and registration reservation share the same gate. A registered
                // wrapper remains owned even if its common constructor has not returned.
                // Close outside the service/native/Room slots: SDK trust callbacks use them.
                synchronized(lifecycleGate) { rpcHostRegistration }?.resource?.closeOwned()
                composition?.awaitRpcPeerCleanup()
                installation.get()?.join()
                // Original inspection/definition preparation share this slot too. Their
                // callers may abandon waiting; actual native reads must finish before close.
                installationSlot.acquire()
                try {
                    val actual=composition
                    // Native evidence is a master-service resource. A client disconnect/logout
                    // never reaches this close. Drain real endpoint/native-intake workers before
                    // closing it, and retain the original Room/P2 owner if native custody is held.
                    actual?.endpoint?.awaitQuiescence()
                    val physicalClose=(physical as? PhysicalRecordingOwnedExecutionGate)?.closeOwnedGate()
                    check(physicalClose==null || physicalClose==PhysicalRecordingNativeRelease.RELEASED) {
                        "Original physical evidence ownership is held"
                    }
                    if(actual!=null) actual.close() else roomOwner?.close() ?: custody.close()
                } finally { installationSlot.release() }
                life.value=LocalMasterLifecycleMode.CLOSED; completion.complete(true)
            } catch(failure: Throwable) {
                life.value=LocalMasterLifecycleMode.UNRESOLVED; custody.denyDataUse(); completion.complete(false)
            } finally { watchdog.close(); scope.cancel() }
        }
        return completion.await()
    }

    public companion object {
        /**
         * Real first-account installation without business ingress. The native/Room lifetime
         * stays owned; no operational client is exposed until a genuine configured runtime is
         * opened separately after actual close and a qualified complete-history startup.
         */
        public suspend fun forOriginalInstallation(application: Application,
            context: SelfOwnedLocalSecurityContext): AndroidSelfOwnedMasterOpenResult =
            open(application,context,emptyList(),emptyList(),UnavailablePhysicalRecordingExecutionGate,
                emptyList(),null,null,installationOnly=true)

        /** Called from the same native setup host, not an arbitrary imported installation DTO. */
        public suspend fun fromOriginalContext(application: Application,context: SelfOwnedLocalSecurityContext,
            handlers: List<MasterCommandHandler>,reads: List<MasterReadHandler>,
            physical: PhysicalRecordingExecutionGate = UnavailablePhysicalRecordingExecutionGate,
            nativeOriginalImports: List<NativeOriginalImportInterpreter> = emptyList(),
            stockProjection: StockCanonicalProjectionV2? = null,
            historyProducer: RuntimeHistoryBusinessProducer? = null): AndroidSelfOwnedMasterOpenResult =
            open(application,context,handlers,reads,physical,nativeOriginalImports,stockProjection,historyProducer,installationOnly=false)

        private suspend fun open(application: Application,context: SelfOwnedLocalSecurityContext,
            handlers: List<MasterCommandHandler>,reads: List<MasterReadHandler>,physical: PhysicalRecordingExecutionGate,
            nativeOriginalImports: List<NativeOriginalImportInterpreter>,stockProjection: StockCanonicalProjectionV2?,
            historyProducer: RuntimeHistoryBusinessProducer?,
            installationOnly: Boolean): AndroidSelfOwnedMasterOpenResult {
            val claim=AndroidSelfOwnedInstallationOwner.claimOriginalHistoryCustody(context)
            if(claim !is SelfOwnedNativeHistoryClaimResult.Claimed) return AndroidSelfOwnedMasterOpenResult.Unresolved(
                (claim as SelfOwnedNativeHistoryClaimResult.Unresolved).reason)
            var service: AndroidSelfOwnedMasterService?=null
            var delivered=false
            try {
                val candidate=AndroidSelfOwnedMasterService(application,claim.custody,handlers.toList(),reads.toList(),physical,
                    nativeOriginalImports.toList(),stockProjection,historyProducer,installationOnly,context.materialAtOpen)
                service=candidate
                candidate.start()
                currentCoroutineContext().ensureActive()
                return AndroidSelfOwnedMasterOpenResult.Opened(candidate).also { delivered=true }
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) { return AndroidSelfOwnedMasterOpenResult.Unresolved(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
            finally {
                if(!delivered) withContext(NonCancellable) {
                    val actual=service
                    if(actual==null) {
                        val owned=physical as? PhysicalRecordingOwnedExecutionGate
                        owned?.denyNewWork()
                        val closed=owned?.closeOwnedGate()
                        if(closed==null || closed==PhysicalRecordingNativeRelease.RELEASED) claim.custody.close()
                        else claim.custody.denyDataUse()
                    } else actual.quiesceAndClose()
                }
            }
        }
    }
}
