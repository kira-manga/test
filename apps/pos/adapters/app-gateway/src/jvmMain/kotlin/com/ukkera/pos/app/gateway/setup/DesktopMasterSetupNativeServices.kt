package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.security.readOriginalBusinessPublications
import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.gateway.DesktopClientJournalStorage
import com.ukkera.pos.app.gateway.DesktopClientJournalStorages
import com.ukkera.pos.app.gateway.DesktopLocalBranchRuntime
import com.ukkera.pos.app.gateway.LocalClientPublication
import com.ukkera.pos.app.gateway.LocalClientRole
import com.ukkera.pos.database.AndroidLocalEmployeeEnrollmentConnection
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalEmployeeEnrollmentAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeeEnrollmentDefinition
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.readOriginalPurchasingPolicies
import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.setup.LocalSetupLanEndpoint
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.database.DesktopSelfOwnedMasterServiceFactory
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.database.UnavailablePhysicalRecordingExecutionGate
import com.ukkera.pos.rpc.DesktopOriginalRpcHostOwner
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalBranchInstallationDefinitionV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompositionSdkProfileV1
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalMasterLifecycleAction
import com.ukkera.pos.security.LocalMasterLifecycleRequest
import com.ukkera.pos.security.LocalMasterLifecycleResult
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import com.ukkera.pos.security.LocalTrainingSdkProfileV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.SelfOwnedNativeReadResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.security.JvmSecureIdentityStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Genuine Windows/macOS Master-service natives. [openProvisioner] and [openTarget] are the desktop
 * original owners observed from the running service process; [factory] is the one desktop host
 * identity; [identityStore] is the service's SDK identity store for the LAN host.
 */
public class DesktopMasterSetupPlatform(
    internal val openProvisioner: suspend (originalOnly: Boolean) -> LocalProvisionerOpenResult,
    internal val openTarget: suspend (preparation: Boolean) -> SelfOwnedNativeOpenResult,
    internal val factory: DesktopSelfOwnedMasterServiceFactory,
    internal val identityStore: () -> JvmSecureIdentityStore,
    /** The service's own same-device cohost journal custody (see [DesktopClientJournalStorages.masterService]).
     * Absent custody keeps the same-device Client and every admin ceremony unavailable. */
    internal val clientJournals: (() -> DesktopClientJournalStorage)? = null,
)

/**
 * Desktop Master SERVICE composition: provisioner, original target, Master installation and
 * operation, receipt, lifecycle, the original LAN host and the same-device original (cohost) Client
 * that the shared coordinator's admin ceremonies use, over the same shared owners as Android. The
 * cohost Client's journals use the service's own custody ([DesktopMasterSetupPlatform.clientJournals]);
 * its gateway is published only on this service's [localClient]. The desktop UI process remains a
 * paired LAN Client of this Master. Every wrapper retains its exact native owner before checks.
 * Physical recording is unavailable on desktop (no native physical journal gate).
 */
public class DesktopMasterSetupNativeServices(
    private val platform: DesktopMasterSetupPlatform,
    private val trainingSlot: LocalTrainingCompositionSlot? = null,
) : LocalSetupNativeServices {
    override val compositionProfile: LocalCompositionSdkProfileV1 =
        trainingSlot?.let { LocalTrainingSdkProfileV1(it) } ?: LocalMasterSdkProfileV1
    private var masterTransfer: Master? = null
    private var lifecycleTransfer: Lifecycle? = null
    private var clientTransfer: Client? = null
    /** Service-local publication of the cohost gateway; presentation only, never authority. */
    public val localClient: LocalClientPublication = LocalClientPublication()

    override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult = platform.openProvisioner(originalOnly)
    override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult = platform.openTarget(preparation)

    override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult {
        check(masterTransfer == null)
        val holder = Master()
        val opened = LocalSetupMasterOpenResult.Opened(holder)
        masterTransfer = holder // Retain BEFORE the actual factory can transfer native custody.
        val publications = when (val read = context.readOriginalBusinessPublications()) {
            is LocalCompanyAuthorityResult.Value -> read.value
            is LocalCompanyAuthorityResult.Unavailable -> null
        }
        val signatures = publications?.let { NativeCompanyPublicationSignatureVerifier(it.trust) }
            ?: UnavailableCompanyPublicationSignatureVerifier
        val purchasingPolicyTrust = when (val read = context.readOriginalPurchasingPolicies()) {
            is LocalCompanyAuthorityResult.Value -> read.value.trust
            is LocalCompanyAuthorityResult.Unavailable -> null
        }
        val commerce = LocalBranchCommerceRegistry(signatures, UnavailableSalesReturnPolicy,
            UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
            purchasingPolicyTrust = purchasingPolicyTrust, nativePublications = publications)
        return when (val native = platform.factory.fromOriginalContext(context, commerce.commands, commerce.reads,
            UnavailablePhysicalRecordingExecutionGate, commerce.nativeOriginalImports, commerce.stockProjection,
            commerce.historyProducer)) {
            is AndroidSelfOwnedMasterOpenResult.Opened -> { holder.capture(native.service); opened }
            is AndroidSelfOwnedMasterOpenResult.Unresolved -> LocalSetupMasterOpenResult.Unresolved(native.reason)
        }
    }

    override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult =
        platform.factory.attachOriginalReceipt(context)

    override suspend fun openInstalledLifecycle(context: SelfOwnedLocalSecurityContext): LocalSetupLifecycleOpenResult {
        check(lifecycleTransfer == null)
        val holder = Lifecycle()
        val opened = LocalSetupLifecycleOpenResult.Opened(holder)
        lifecycleTransfer = holder // The factory may transfer custody before throwing; the holder stays retained.
        return when (val native = platform.factory.forOriginalInstallation(context)) {
            is AndroidSelfOwnedMasterOpenResult.Opened -> { holder.capture(native.service); opened }
            is AndroidSelfOwnedMasterOpenResult.Unresolved -> LocalSetupLifecycleOpenResult.Unresolved(native.reason)
        }
    }

    override suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient? {
        val actual = master as? Master
        check(actual != null && masterTransfer === actual && actual.owner === this && clientTransfer == null)
        val storage = platform.clientJournals?.invoke() ?: return null // Explicitly unavailable custody.
        val holder = Client()
        clientTransfer = holder
        val acquired = DesktopLocalBranchRuntime.connectOriginal(platform.factory.owner, actual.value, action, storage)
        if (acquired == null) {
            clientTransfer = null // The real factory's null path already awaited its journal closes.
            return null
        }
        holder.capture(acquired)
        return holder
    }

    override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean =
        connection is EmployeeConnection && connection.owner === this && connection.value.belongsTo(platform.factory.owner)

    override fun publishClient(owner: Any, client: LocalSetupClient) {
        val actual = client as? Client
        check(actual != null && actual.owner === this && clientTransfer === actual)
        val runtime = actual.value
        localClient.publish(owner, runtime.branchGateway, LocalClientRole.MASTER, runtime::isCurrent, runtime::denyNewWork)
    }

    override fun withdrawClient(owner: Any) { localClient.withdraw(owner) }
    override fun publishLanAuthentication(owner: Any, client: LocalSetupLanClient, authentication: LocalSetupLanAuthentication): Unit =
        error("The desktop Master service is never a LAN Client")
    override fun retainLanClient(target: LocalSetupFreshDeviceTarget, scope: CoroutineScope, originalPairingActionId: DomainId,
        settings: LocalSetupLanSettings): LocalSetupLanClient = error("The desktop Master service is never a LAN Client")

    override fun retainLanHost(master: LocalSetupMaster, scope: CoroutineScope, settings: LocalSetupLanSettings): LocalSetupLanHost {
        val actual = master as? Master
        check(actual != null && actual.owner === this && masterTransfer === actual)
        return actual.retainLanHost(scope, settings)
    }

    private inner class Master : LocalSetupMaster {
        val owner: DesktopMasterSetupNativeServices get() = this@DesktopMasterSetupNativeServices
        private val attachmentGate = Any()
        @Volatile private var acquired: AndroidSelfOwnedMasterService? = null
        private var denied = false
        private var lanHost: LanHost? = null
        val value: AndroidSelfOwnedMasterService get() = checkNotNull(acquired)
        fun capture(value: AndroidSelfOwnedMasterService) { acquired = value }

        fun retainLanHost(scope: CoroutineScope, settings: LocalSetupLanSettings): LanHost = synchronized(attachmentGate) {
            check(masterTransfer === this)
            lanHost?.let { it.requireSelection(scope, settings); return@synchronized it }
            check(!denied) { "Original Master is retired" }
            LanHost(platform, value, scope, settings).also { lanHost = it }
        }

        override suspend fun readOwnedOriginalNativeBinding(): SelfOwnedNativeReadResult = value.readOwnedOriginalNativeBinding()
        override suspend fun readOwnedOriginalNativeBindingV2(): com.ukkera.pos.security.SelfOwnedNativeReadResultV2 =
            value.readOwnedOriginalNativeBindingV2()
        override val mode: StateFlow<LocalMasterLifecycleMode> get() = value.mode
        override suspend fun permitsFirstClientEnrollment(): Boolean = value.originalLocalClient().permitsFirstEnrollment()
        override suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning, authorityEpoch: Revision): LocalBranchInstallationSnapshotV2 =
            value.prepareOriginalDefinition(account, authorityEpoch)
        override suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2, headLogin: String,
            headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome =
            value.installOriginal(definition, headLogin, headSecret, employeePassword)
        override suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome = value.resumeOriginalAcknowledgement()
        override suspend fun authenticateAndApplyLifecycle(login: LocalLoginIdentifier, password: CharArray,
            request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult = value.authenticateAndApplyLifecycle(login, password, request)
        override suspend fun readOriginalSetup(): LocalOriginalInstallationSetup = value.readOriginalSetup()
        override fun denyNewWork() { synchronized(attachmentGate) { denied = true; value.denyNewWork() } }
        override suspend fun quiesceAndClose(): Boolean {
            denyNewWork()
            synchronized(attachmentGate) { lanHost }?.close() // Host transfer is known closed before Master close.
            val closed = value.quiesceAndClose()
            if (closed) { check(masterTransfer === this); masterTransfer = null }
            return closed
        }
    }

    private inner class Lifecycle : LocalSetupLifecycleMaster {
        @Volatile private var acquired: AndroidSelfOwnedMasterService? = null
        private var denied = false
        private val value: AndroidSelfOwnedMasterService get() = checkNotNull(acquired)
        fun capture(value: AndroidSelfOwnedMasterService) { acquired = value }
        override val mode: StateFlow<LocalMasterLifecycleMode> get() = value.mode
        override suspend fun authenticateAndApplyLifecycle(login: LocalLoginIdentifier, password: CharArray,
            request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult {
            check(!denied && lifecycleTransfer === this) { "Installed lifecycle owner is retired" }
            return if (request.action == LocalMasterLifecycleAction.STOP) value.authenticateAndRecoverLifecycleStop(login, password, request)
            else value.authenticateAndApplyLifecycle(login, password, request)
        }
        override fun denyNewWork() { denied = true; acquired?.denyNewWork() }
        override suspend fun quiesceAndClose(): Boolean {
            denyNewWork()
            val closed = value.quiesceAndClose()
            if (closed) { check(lifecycleTransfer === this); lifecycleTransfer = null }
            return closed
        }
    }

    private class LanHost(
        private val platform: DesktopMasterSetupPlatform,
        private val retainedMaster: AndroidSelfOwnedMasterService,
        private val retainedScope: CoroutineScope,
        private val settings: LocalSetupLanSettings,
    ) : LocalSetupLanHost {
        private val gate = Mutex()
        private val captured = CompletableDeferred<DesktopOriginalRpcHostOwner>()
        private var acquisition: Deferred<Unit>? = null
        private var constructorEntered = false
        private var closing: CompletableDeferred<Unit>? = null

        fun requireSelection(scope: CoroutineScope, selection: LocalSetupLanSettings) {
            check(retainedScope === scope && settings.sameSelection(selection)) { "Original Host attachment differs" }
        }
        private fun requireAvailable() {
            check(closing == null && retainedScope.coroutineContext[Job]?.isActive == true) { "Original Host attachment is retired" }
        }

        private suspend fun acquireOwner(): DesktopOriginalRpcHostOwner {
            currentCoroutineContext().ensureActive()
            val pending = gate.withLock {
                requireAvailable()
                acquisition ?: retainedScope.async(start = CoroutineStart.LAZY) {
                    gate.withLock { requireAvailable() }
                    val original = retainedMaster.originalLocalClient()
                    gate.withLock {
                        requireAvailable()
                        constructorEntered = true
                        try {
                            check(captured.complete(DesktopOriginalRpcHostOwner.retainExisting(platform.factory.owner,
                                retainedScope, original, settings.lan, platform.identityStore)))
                        } catch (failure: Throwable) { captured.completeExceptionally(failure); throw failure }
                    }
                }.also { acquisition = it }
            }
            pending.start()
            pending.await()
            val owner = captured.await()
            currentCoroutineContext().ensureActive()
            gate.withLock { requireAvailable() }
            return owner
        }

        override suspend fun start(): LocalSetupLanEndpoint {
            val owner = acquireOwner()
            owner.start()
            val endpoint = owner.endpoint()
            currentCoroutineContext().ensureActive()
            gate.withLock { requireAvailable() }
            return LocalSetupLanEndpoint(endpoint.address, endpoint.port)
        }

        override suspend fun endpoint(): LocalSetupLanEndpoint {
            currentCoroutineContext().ensureActive()
            val pending = gate.withLock { requireAvailable(); checkNotNull(acquisition) { "Original Host attachment has not been started" } }
            pending.await()
            val endpoint = captured.await().endpoint()
            currentCoroutineContext().ensureActive()
            gate.withLock { requireAvailable() }
            return LocalSetupLanEndpoint(endpoint.address, endpoint.port)
        }

        override suspend fun close(): Unit = withContext(NonCancellable) {
            val selected = gate.withLock {
                closing?.let { it to false } ?: CompletableDeferred<Unit>().let { closing = it; it to true }
            }
            if (!selected.second) { selected.first.await(); return@withContext }
            try {
                gate.withLock { acquisition }?.join()
                if (gate.withLock { constructorEntered }) captured.await().close()
                selected.first.complete(Unit)
            } catch (failure: Throwable) { selected.first.completeExceptionally(failure); throw failure }
        }
    }

    private inner class Client : LocalSetupClient {
        val owner: DesktopMasterSetupNativeServices get() = this@DesktopMasterSetupNativeServices
        @Volatile private var acquired: DesktopLocalBranchRuntime? = null
        val value: DesktopLocalBranchRuntime get() = checkNotNull(acquired)
        fun capture(value: DesktopLocalBranchRuntime) { acquired = value }
        override fun isCurrent(): Boolean = value.isCurrent()
        override fun denyNewWork(): Unit = value.denyNewWork()
        override suspend fun close() {
            value.close()
            check(clientTransfer === this)
            clientTransfer = null
        }
        override suspend fun prepareEmployeeEnrollment(requestId: DomainId, definition: LocalAccountProvisioning): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
            value.prepareEmployeeEnrollment(requestId, definition)
        override suspend fun approveEmployeeEnrollment(action: LocalEmployeeEnrollmentAction, headLogin: String, headSecret: CharArray):
            LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> = wrap(value.approveEmployeeEnrollment(action, headLogin, headSecret))
        override suspend fun recoverEmployeeEnrollmentApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> =
            wrap(value.recoverEmployeeEnrollmentApproval(originalActionId))
        override suspend fun recoverEmployeeEnrollmentConnection(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> =
            wrap(value.recoverEmployeeEnrollmentConnection(originalActionId))
        override suspend fun reviewEmployeeEnrollment(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeeEnrollmentAction> =
            value.reviewEmployeeEnrollment(originalActionId)
        override suspend fun retainedEmployeeEnrollments(): LocalCompanyAuthorityResult<List<LocalEmployeeEnrollmentReference>> =
            value.retainedEmployeeEnrollments()
        override suspend fun prepareEmployeePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy):
            LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> = value.prepareEmployeePolicy(requestId, nextPolicy)
        override suspend fun approveEmployeePolicy(action: LocalEmployeePolicyActivationAction, headLogin: String,
            headSecret: CharArray): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> {
            try { return value.approveEmployeePolicy(action, headLogin, headSecret) }
            finally { headSecret.fill('\u0000') }
        }
        override suspend fun reviewEmployeePolicy(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAction> =
            value.reviewEmployeePolicy(originalActionId)
        override suspend fun recoverEmployeePolicyApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
            value.recoverEmployeePolicyApproval(originalActionId)
        override suspend fun recoverEmployeePolicyConnection(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalEmployeePolicyActivationAuthorityReceipt> =
            value.recoverEmployeePolicyConnection(originalActionId)
        override suspend fun retainedEmployeePolicies(): LocalCompanyAuthorityResult<List<LocalEmployeePolicyActivationReference>> =
            value.retainedEmployeePolicies()
        override suspend fun activateEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
            nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult = value.activateEmployeePolicy(approval, nextPolicy)
        override suspend fun lookupEmployeePolicy(approval: LocalEmployeePolicyActivationAuthorityReceipt,
            nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus = value.lookupEmployeePolicy(approval, nextPolicy)
        override suspend fun retainedDevicePairings(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> =
            value.retainedDevicePairings()
        override suspend fun prepareSdkBoundDevicePairing(requestId: DomainId, sdkIdentity: LocalDevicePairingSdkIdentityV2):
            LocalCompanyAuthorityResult<LocalDevicePairingAction> = value.prepareSdkBoundDevicePairing(requestId, sdkIdentity)
        override suspend fun proveDevicePairingTarget(action: LocalDevicePairingAction,
            possession: LocalDevicePairingPossession): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
            value.proveDevicePairingTarget(action, possession)
        override suspend fun reviewDevicePairing(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
            value.reviewDevicePairing(originalActionId)
        override suspend fun approveDevicePairing(action: LocalDevicePairingAction, headLogin: String,
            headSecret: CharArray): LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
            try { return value.approveDevicePairing(action, headLogin, headSecret) }
            finally { headSecret.fill('\u0000') }
        }
        override suspend fun recoverDevicePairingApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingApproval> =
            value.recoverDevicePairingApproval(originalActionId)
        override suspend fun retireKnownUnsignedDevicePairing(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
            value.retireKnownUnsignedDevicePairing(originalActionId)
        override suspend fun recoverUnsignedDevicePairingRetirement(originalActionId: DomainId):
            LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
            value.recoverUnsignedDevicePairingRetirement(originalActionId)
        override suspend fun admitOriginalDevice(approval: LocalDevicePairingApproval): LocalCredentialAdministrationResult =
            value.admitOriginalDevice(approval)
        override suspend fun lookupOriginalDeviceAdmission(originalActionId: DomainId): LocalProvisioningStatus =
            value.lookupOriginalDeviceAdmission(originalActionId)
        override suspend fun enrollApprovedEmployee(originalRequestId: DomainId, password: CharArray): LocalEnrollmentResult =
            value.enrollApprovedEmployee(originalRequestId, password)
        override suspend fun lookupEmployeeEnrollment(originalRequestId: DomainId): LocalEnrollmentResult = value.lookupEmployeeEnrollment(originalRequestId)
        override suspend fun acquireEmployeeIntentJournal(originalRequestId: DomainId, action: NativeEmployeeClientJournalAction): Boolean =
            value.acquireEmployeeIntentJournal(originalRequestId, action)
        private fun wrap(result: LocalCompanyAuthorityResult<AndroidLocalEmployeeEnrollmentConnection>): LocalCompanyAuthorityResult<LocalSetupEmployeeConnection> =
            when (result) {
                is LocalCompanyAuthorityResult.Value -> LocalCompanyAuthorityResult.Value(EmployeeConnection(result.value))
                is LocalCompanyAuthorityResult.Unavailable -> result
            }
    }

    private inner class EmployeeConnection(val value: AndroidLocalEmployeeEnrollmentConnection) : LocalSetupEmployeeConnection {
        val owner: DesktopMasterSetupNativeServices get() = this@DesktopMasterSetupNativeServices
        override val requested: LocalEmployeeEnrollmentDefinition get() = value.requested
        override val approval: LocalEmployeeEnrollmentAuthorityReceipt get() = value.approval
        override val originalRequestId: DomainId get() = value.originalRequestId
        override val originalActionId: DomainId get() = value.originalActionId
        override val actorId: DomainId get() = value.actorId
    }
}
