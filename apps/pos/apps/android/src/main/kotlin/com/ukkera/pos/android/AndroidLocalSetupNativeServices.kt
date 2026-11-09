package com.ukkera.pos.android

import android.app.Application
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.gateway.AndroidPairedDeviceClientCredentials
import com.ukkera.pos.app.gateway.AndroidPairedDeviceClientCredentialsResult
import com.ukkera.pos.app.gateway.LocalAuthenticationOnlyConnection
import com.ukkera.pos.app.gateway.LocalDirectoryClientConnection
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.app.gateway.LocalBranchDisplayLabels
import com.ukkera.pos.app.gateway.LocalEndpointBranchGateway
import com.ukkera.pos.app.gateway.setup.LocalSetupLanAuthentication
import com.ukkera.pos.security.AndroidPairedDeviceCredentialJournalCustody
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import com.ukkera.pos.app.LocalEnrollmentResult
import com.ukkera.pos.app.gateway.AndroidLocalBranchRuntime
import com.ukkera.pos.app.gateway.LocalClientRole
import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.gateway.setup.LocalSetupClient
import com.ukkera.pos.app.gateway.setup.LocalSetupEmployeeConnection
import com.ukkera.pos.app.gateway.setup.LocalSetupFreshDeviceTarget
import com.ukkera.pos.app.gateway.setup.LocalSetupFreshDeviceTargetOpenResult
import com.ukkera.pos.app.gateway.setup.LocalSetupFreshDeviceTargetResult
import com.ukkera.pos.app.gateway.setup.LocalSetupLanClient
import com.ukkera.pos.app.gateway.setup.LocalSetupLanHost
import com.ukkera.pos.app.gateway.setup.LocalSetupLanSettings
import com.ukkera.pos.app.gateway.setup.LocalSetupMaster
import com.ukkera.pos.app.gateway.setup.LocalSetupMasterOpenResult
import com.ukkera.pos.app.gateway.setup.LocalSetupLifecycleMaster
import com.ukkera.pos.app.gateway.setup.LocalSetupLifecycleOpenResult
import com.ukkera.pos.app.gateway.setup.LocalSetupNativeServices
import com.ukkera.pos.app.gateway.setup.validateEndpoint
import com.ukkera.pos.app.setup.LocalSetupLanEndpoint
import com.ukkera.pos.app.setup.LocalSetupLanWorkflow
import com.ukkera.pos.database.AndroidLocalEmployeeEnrollmentConnection
import com.ukkera.pos.database.AndroidOriginalInstallationReceiptOwner
import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.rpc.AndroidOriginalRpcClientOwner
import com.ukkera.pos.rpc.AndroidOriginalRpcHostOwner
import com.ukkera.pos.security.AndroidSelfOwnedEnrollmentSource
import com.ukkera.pos.security.AndroidSelfOwnedInstallationOwner
import com.ukkera.pos.security.AndroidDevicePairingTarget
import com.ukkera.pos.security.AndroidDevicePairingTargetOpenResult
import com.ukkera.pos.security.AndroidDevicePairingTargetResult
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalBranchInstallationDefinitionV2
import com.ukkera.pos.security.LocalBranchInstallationSnapshotV2
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalEmployeeEnrollmentAction
import com.ukkera.pos.security.LocalEmployeeEnrollmentAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeeEnrollmentDefinition
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApproval
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingMasterTransfer
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import com.ukkera.pos.app.gateway.LocalClientPublication
import com.ukkera.pos.security.LocalDevicePairingTargetDescriptor
import com.ukkera.pos.security.LocalDevicePairingTargetReference
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalMasterLifecycleRequest
import com.ukkera.pos.security.LocalMasterLifecycleResult
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.NativeEmployeeClientJournalAction
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Concrete native composition, never caller-supplied authority. Every wrapper retains its exact
 * native owner and delegates to the existing checked factories. Preallocated transfer holders
 * are retained before acquisition; positive returns are captured before checks or allocations.
 * Unconfirmed acquisition/close therefore cannot release the coordinator's process slot.
 */
internal class AndroidLocalSetupNativeServices(
    private val application: Application,
    private val trainingSlot: LocalTrainingCompositionSlot? = null,
) : LocalSetupNativeServices {
    override val compositionProfile: com.ukkera.pos.security.LocalCompositionSdkProfileV1 =
        trainingSlot?.let { com.ukkera.pos.security.LocalTrainingSdkProfileV1(it) }
            ?: com.ukkera.pos.security.LocalMasterSdkProfileV1
    private val publication: LocalClientPublication
        get() = if (trainingSlot == null) (application as PosApplication).localClient
        else (application as PosApplication).trainingLocalClient
    private var masterTransfer: Master? = null
    private var lifecycleTransfer: Lifecycle? = null
    private var clientTransfer: Client? = null
    private var freshTargetTransfer: FreshDeviceTarget? = null

    override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult {
        val slot = trainingSlot
        return if (slot == null) {
            if (originalOnly) AndroidSelfOwnedEnrollmentSource.openOriginal(application)
            else AndroidSelfOwnedEnrollmentSource.open(application)
        } else if (originalOnly) AndroidSelfOwnedEnrollmentSource.openTrainingOriginal(application, slot)
        else AndroidSelfOwnedEnrollmentSource.openTraining(application, slot)
    }

    override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult {
        val slot = trainingSlot
        return if (slot == null) {
            if (preparation) AndroidSelfOwnedInstallationOwner.prepareOriginal(application)
            else AndroidSelfOwnedInstallationOwner.openOriginal(application)
        } else if (preparation) AndroidSelfOwnedInstallationOwner.prepareTraining(application, slot)
        else AndroidSelfOwnedInstallationOwner.openTraining(application, slot)
    }

    override suspend fun prepareFreshDeviceTarget(scope: BranchScope, capabilities: Set<String>):
        LocalSetupFreshDeviceTargetOpenResult = acquireFreshDeviceTarget {
        val slot = trainingSlot
        if (slot == null) AndroidDevicePairingTarget.prepareNew(application, scope, capabilities)
        else AndroidDevicePairingTarget.prepareNewForTraining(application, scope, slot, capabilities)
    }

    override suspend fun openOriginalFreshDeviceTarget(): LocalSetupFreshDeviceTargetOpenResult =
        acquireFreshDeviceTarget {
            val slot = trainingSlot
            if (slot == null) AndroidDevicePairingTarget.openExisting(application)
            else AndroidDevicePairingTarget.openExistingForTraining(application, slot)
        }

    private suspend fun acquireFreshDeviceTarget(factory: suspend () -> AndroidDevicePairingTargetOpenResult):
        LocalSetupFreshDeviceTargetOpenResult {
        check(freshTargetTransfer == null)
        val holder = FreshDeviceTarget()
        val opened = LocalSetupFreshDeviceTargetOpenResult.Opened(holder)
        freshTargetTransfer = holder // Partial acquisition remains retained before native IO.
        return when (val native = factory()) {
            is AndroidDevicePairingTargetOpenResult.Opened -> {
                holder.capture(native.target) // Exact owner retained before mapping/publication.
                opened
            }
            is AndroidDevicePairingTargetOpenResult.Unresolved ->
                LocalSetupFreshDeviceTargetOpenResult.Unresolved(native.reason, native.original)
        }
    }

    override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult {
        check(masterTransfer == null)
        val holder = Master()
        val opened = LocalSetupMasterOpenResult.Opened(holder)
        masterTransfer = holder // Retain BEFORE the actual factory can transfer native custody.
        return when (val native = AndroidLocalBranchRuntime.openOriginalMaster(
            application, context, trainingComposition = trainingSlot != null,
        )) {
            is AndroidSelfOwnedMasterOpenResult.Opened -> {
                holder.capture(native.service)
                opened // No new wrapper or publication between capture and common retention.
            }
            is AndroidSelfOwnedMasterOpenResult.Unresolved -> LocalSetupMasterOpenResult.Unresolved(native.reason)
        }
    }

    override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult =
        AndroidOriginalInstallationReceiptOwner.attachOriginal(context)

    override suspend fun openInstalledLifecycle(context: SelfOwnedLocalSecurityContext): LocalSetupLifecycleOpenResult {
        check(lifecycleTransfer == null)
        val holder = Lifecycle()
        val opened = LocalSetupLifecycleOpenResult.Opened(holder)
        lifecycleTransfer = holder // Retain the transfer slot before native custody can move.
        return try {
            when (val native = AndroidSelfOwnedMasterService.forOriginalInstallation(application, context)) {
                is AndroidSelfOwnedMasterOpenResult.Opened -> {
                    holder.capture(native.service)
                    opened
                }
                is AndroidSelfOwnedMasterOpenResult.Unresolved -> LocalSetupLifecycleOpenResult.Unresolved(native.reason)
            }
        } catch (failure: Throwable) {
            // The factory may have transferred custody before throwing. Never release the
            // process slot or infer a target-only close; the exact holder remains retained.
            throw failure
        }
    }

    override suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient? {
        val actual = master as? Master
        check(actual != null && masterTransfer === actual && actual.owner === this && clientTransfer == null)
        val holder = Client()
        clientTransfer = holder
        val acquired = AndroidLocalBranchRuntime.connectOriginal(
            application, actual.value, action, trainingComposition = trainingSlot != null,
        )
        if (acquired == null) {
            clientTransfer = null // The real factory's null path already awaited its journal closes.
            return null
        }
        holder.capture(acquired)
        return holder
    }

    override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean =
        connection is EmployeeConnection && connection.owner === this && connection.value.belongsTo(application)

    override fun publishClient(owner: Any, client: LocalSetupClient) {
        val actual = client as? Client
        check(actual != null && actual.owner === this && clientTransfer === actual)
        val runtime = actual.value
        publication.publish(owner, runtime.branchGateway, LocalClientRole.MASTER,
            runtime::isCurrent, runtime::denyNewWork)
    }

    override fun withdrawClient(owner: Any) { publication.withdraw(owner) }

    override fun publishLanAuthentication(owner: Any, client: LocalSetupLanClient,
        authentication: LocalSetupLanAuthentication) {
        val actual = client as? LanClient
        check(actual != null && freshTargetTransfer?.owns(actual) == true && actual.owns(authentication))
        publication.publish(owner, authentication.gateway, LocalClientRole.CLIENT,
            authentication::isCurrent, authentication::denyNewWork)
    }

    override fun retainLanHost(master: LocalSetupMaster, scope: CoroutineScope,
        settings: LocalSetupLanSettings): LocalSetupLanHost {
        AndroidLocalNetworkPermission.requireGranted(application)
        val actual = master as? Master
        check(actual != null && actual.owner === this && masterTransfer === actual)
        return actual.retainLanHost(scope, settings)
    }

    override fun retainLanClient(target: LocalSetupFreshDeviceTarget, scope: CoroutineScope,
        originalPairingActionId: DomainId, settings: LocalSetupLanSettings): LocalSetupLanClient {
        AndroidLocalNetworkPermission.requireGranted(application)
        val actual = target as? FreshDeviceTarget
        check(actual != null && actual.owner === this && freshTargetTransfer === actual)
        return actual.retainLanClient(scope, originalPairingActionId, settings)
    }

    private inner class FreshDeviceTarget : LocalSetupFreshDeviceTarget {
        override val compositionProfile: com.ukkera.pos.security.LocalCompositionSdkProfileV1?
            get() = acquired?.compositionProfile
        val owner: AndroidLocalSetupNativeServices get() = this@AndroidLocalSetupNativeServices
        private val attachmentGate = Any()
        @Volatile private var acquired: AndroidDevicePairingTarget? = null
        private var denied = false
        private var lanClient: LanClient? = null
        val value: AndroidDevicePairingTarget get() = checkNotNull(acquired)
        fun capture(value: AndroidDevicePairingTarget) { acquired = value }
        fun owns(client: LanClient): Boolean = synchronized(attachmentGate) { !denied && lanClient === client }

        fun retainLanClient(scope: CoroutineScope, originalPairingActionId: DomainId,
            settings: LocalSetupLanSettings): LanClient = synchronized(attachmentGate) {
            check(freshTargetTransfer === this)
            lanClient?.let {
                it.requireSelection(scope, originalPairingActionId, settings)
                return@synchronized it
            }
            check(!denied) { "Fresh target is retired" }
            val holder = LanClient(scope, originalPairingActionId, settings, this, value)
            lanClient = holder // Transfer and ceremony fence precede the native constructor.
            try {
                holder.capture(AndroidOriginalRpcClientOwner.retainExisting(application, scope,
                    value, originalPairingActionId, settings.lan))
                holder
            } catch (failure: Throwable) {
                holder.retainFailure(failure)
                throw failure // Never restore native-only custody after attempted transfer.
            }
        }

        private suspend fun <T> run(action: suspend (AndroidDevicePairingTarget) -> AndroidDevicePairingTargetResult<T>):
            LocalSetupFreshDeviceTargetResult<T> {
            val current = synchronized(attachmentGate) {
                if (denied || lanClient != null) null else acquired
            } ?: return LocalSetupFreshDeviceTargetResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            return wrapTarget(action(current))
        }

        override suspend fun original(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> =
            run { it.original() }
        override suspend fun descriptor(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetDescriptor> =
            run { it.descriptor() }
        override suspend fun acquireSdkIdentity(appId: AppId, deviceName: String):
            LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> =
            run { it.acquireSdkIdentity(appId, deviceName) }
        override suspend fun recoverSdkIdentity(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> =
            run { it.recoverSdkIdentity() }
        override suspend fun respond(challenge: LocalDevicePairingChallenge): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> =
            run { it.respond(challenge) }
        override suspend fun recoverPossession(originalPairingActionId: DomainId): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> =
            run { it.recoverPossession(originalPairingActionId) }
        override suspend fun importApprovedMaster(transfer: LocalDevicePairingMasterTransfer):
            LocalSetupFreshDeviceTargetResult<LocalDevicePairingApprovedMaster> = run { target ->
            val certificate = transfer.copyAuthorityCertificate()
            try {
                val root = transfer.copyRootAnnouncement()
                try { target.importApprovedMaster(certificate, root) } finally { root.fill(0) }
            } finally { certificate.fill(0) }
        }
        override suspend fun recoverApprovedMaster(originalPairingActionId: DomainId):
            LocalSetupFreshDeviceTargetResult<LocalDevicePairingApprovedMaster> = run { it.recoverApprovedMaster(originalPairingActionId) }
        override suspend fun beginAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement):
            LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> =
            run { it.beginAfterKnownUnsigned(retirement) }
        override suspend fun recoverAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement):
            LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> =
            run { it.recoverAfterKnownUnsigned(retirement) }
        override fun denyNewWork() {
            synchronized(attachmentGate) {
                denied = true
                // The RPC source alone fences its native target after both original readbacks.
                if (lanClient == null) acquired?.denyNewWork()
            }
        }
        override suspend fun close() {
            denyNewWork()
            val attachment = synchronized(attachmentGate) { lanClient }
            if (attachment == null) value.close() else attachment.close()
            // Both source and RPC factory cleanup must return before setup custody is released.
            check(freshTargetTransfer === this)
            freshTargetTransfer = null
        }
    }

    private fun <T> wrapTarget(result: AndroidDevicePairingTargetResult<T>): LocalSetupFreshDeviceTargetResult<T> =
        when (result) {
            is AndroidDevicePairingTargetResult.Value -> LocalSetupFreshDeviceTargetResult.Value(result.value)
            is AndroidDevicePairingTargetResult.Unavailable -> LocalSetupFreshDeviceTargetResult.Unavailable(result.reason)
        }

    private inner class Master : LocalSetupMaster {
        val owner: AndroidLocalSetupNativeServices get() = this@AndroidLocalSetupNativeServices
        private val attachmentGate = Any()
        @Volatile private var acquired: AndroidSelfOwnedMasterService? = null
        private var denied = false
        private var lanHost: LanHost? = null
        val value: AndroidSelfOwnedMasterService get() = checkNotNull(acquired)
        fun capture(value: AndroidSelfOwnedMasterService) { acquired = value }

        fun retainLanHost(scope: CoroutineScope, settings: LocalSetupLanSettings): LanHost = synchronized(attachmentGate) {
            check(masterTransfer === this)
            lanHost?.let {
                it.requireSelection(scope, settings)
                return@synchronized it
            }
            check(!denied) { "Original Master is retired" }
            val holder = LanHost(application, value, scope, settings)
            lanHost = holder // Exact holder precedes suspended original reads and RPC construction.
            holder
        }

        override val mode: StateFlow<LocalMasterLifecycleMode> get() = value.mode
        override suspend fun permitsFirstClientEnrollment(): Boolean = value.originalLocalClient().permitsFirstEnrollment()
        override suspend fun prepareOriginalDefinition(account: LocalAccountProvisioning, authorityEpoch: Revision): LocalBranchInstallationSnapshotV2 =
            value.prepareOriginalDefinition(account, authorityEpoch)
        override suspend fun installOriginal(definition: LocalBranchInstallationDefinitionV2, headLogin: String,
            headSecret: CharArray, employeePassword: CharArray): LocalMasterInstallationOutcome =
            value.installOriginal(definition, headLogin, headSecret, employeePassword)
        override suspend fun resumeOriginalAcknowledgement(): LocalMasterInstallationOutcome = value.resumeOriginalAcknowledgement()
        override suspend fun authenticateAndApplyLifecycle(login: LocalLoginIdentifier, password: CharArray,
            request: LocalMasterLifecycleRequest): LocalMasterLifecycleResult =
            value.authenticateAndApplyLifecycle(login, password, request)
        override suspend fun readOriginalSetup(): LocalOriginalInstallationSetup = value.readOriginalSetup()
        override fun denyNewWork() {
            synchronized(attachmentGate) {
                denied = true
                value.denyNewWork()
            }
        }
        override suspend fun quiesceAndClose(): Boolean {
            denyNewWork()
            // Service teardown also owns this Host. Its app transfer must be known closed
            // before a native-only close can release custody after a lost constructor.
            synchronized(attachmentGate) { lanHost }?.close()
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
            return if (request.action == com.ukkera.pos.security.LocalMasterLifecycleAction.STOP)
                value.authenticateAndRecoverLifecycleStop(login, password, request)
            else value.authenticateAndApplyLifecycle(login, password, request)
        }

        override fun denyNewWork() {
            denied = true
            acquired?.denyNewWork()
        }

        override suspend fun quiesceAndClose(): Boolean {
            denyNewWork()
            val closed = value.quiesceAndClose()
            if (closed) { check(lifecycleTransfer === this); lifecycleTransfer = null }
            return closed
        }
    }

    private class LanHost(
        private val application: Application,
        private val retainedMaster: AndroidSelfOwnedMasterService,
        private val retainedScope: CoroutineScope,
        private val settings: LocalSetupLanSettings,
    ) : LocalSetupLanHost {
        private val gate = Mutex()
        private val captured = CompletableDeferred<AndroidOriginalRpcHostOwner>()
        private var acquisition: Deferred<Unit>? = null
        private var constructorEntered = false
        private var closing: CompletableDeferred<Unit>? = null

        fun requireSelection(scope: CoroutineScope, selection: LocalSetupLanSettings) {
            check(retainedScope === scope && settings.sameSelection(selection)) { "Original Host attachment differs" }
        }

        private fun requireAvailable() {
            check(closing == null && retainedScope.coroutineContext[Job]?.isActive == true) {
                "Original Host attachment is retired"
            }
        }

        private suspend fun acquireOwner(): AndroidOriginalRpcHostOwner {
            currentCoroutineContext().ensureActive()
            val pending = gate.withLock {
                requireAvailable()
                acquisition ?: retainedScope.async(start = CoroutineStart.LAZY) {
                    gate.withLock { requireAvailable() }
                    AndroidLocalNetworkPermission.requireGranted(application)
                    val original = retainedMaster.originalLocalClient()
                    gate.withLock {
                        requireAvailable()
                        constructorEntered = true
                        try {
                            val actual = AndroidOriginalRpcHostOwner.retainExisting(application, retainedScope,
                                original, settings.lan)
                            check(captured.complete(actual)) // Direct retention before any suspension.
                        } catch (failure: Throwable) {
                            captured.completeExceptionally(failure)
                            throw failure
                        }
                    }
                }.also { acquisition = it }
            }
            pending.start() // Never launch while the synchronous Master attachment monitor is held.
            pending.await()
            val owner = captured.await()
            currentCoroutineContext().ensureActive()
            gate.withLock { requireAvailable() }
            return owner
        }

        override suspend fun start(): LocalSetupLanEndpoint {
            AndroidLocalNetworkPermission.requireGranted(application)
            val owner = acquireOwner()
            AndroidLocalNetworkPermission.requireGranted(application)
            owner.start()
            val endpoint = owner.endpoint()
            currentCoroutineContext().ensureActive()
            gate.withLock { requireAvailable() }
            return LocalSetupLanEndpoint(endpoint.address, endpoint.port)
        }

        override suspend fun endpoint(): LocalSetupLanEndpoint {
            currentCoroutineContext().ensureActive()
            val pending = gate.withLock {
                requireAvailable()
                checkNotNull(acquisition) { "Original Host attachment has not been started" }
            }
            pending.await()
            val owner = captured.await()
            currentCoroutineContext().ensureActive()
            gate.withLock { requireAvailable() }
            val endpoint = owner.endpoint()
            currentCoroutineContext().ensureActive()
            gate.withLock { requireAvailable() }
            return LocalSetupLanEndpoint(endpoint.address, endpoint.port)
        }

        override suspend fun close(): Unit = withContext(NonCancellable) {
            val selected = gate.withLock {
                closing?.let { it to false } ?: CompletableDeferred<Unit>().let {
                    closing = it // Terminal even if Start has never admitted an acquisition.
                    it to true
                }
            }
            val outcome = selected.first
            if (!selected.second) {
                outcome.await()
                return@withContext
            }
            try {
                gate.withLock { acquisition }?.join()
                // A cancelled acquisition can still have returned/captured an actual owner.
                // Lost construction remains unknown, never a native-only Master close.
                if (gate.withLock { constructorEntered }) captured.await().close()
                outcome.complete(Unit)
            } catch (failure: Throwable) {
                outcome.completeExceptionally(failure)
                throw failure
            }
        }
    }

    private inner class LanClient(
        private val retainedScope: CoroutineScope,
        private val originalPairingActionId: DomainId,
        private val settings: LocalSetupLanSettings,
        private val source: FreshDeviceTarget,
        private val target: AndroidDevicePairingTarget,
    ) : LocalSetupLanClient {
        private val captured = CompletableDeferred<AndroidOriginalRpcClientOwner>()
        private val gate = Mutex()
        private val denied = AtomicBoolean(false)
        private val ownedJob = SupervisorJob()
        private val owned = CoroutineScope(retainedScope.coroutineContext.minusKey(Job) + ownedJob)
        private var connecting: Deferred<Unit>? = null
        private var selectedEndpoint: LocalSetupLanEndpoint? = null
        private var closing: Deferred<Unit>? = null
        private var custody: AndroidPairedDeviceCredentialJournalCustody? = null
        private var credentials: AndroidPairedDeviceClientCredentials? = null
        private val authentication = AtomicReference<LanAuthentication?>(null)
        private val retired = CompletableDeferred<Unit>()
        private val denialFailure = AtomicReference<Throwable?>(null)

        init {
            retainedScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { retired.await() }
                finally { withContext(NonCancellable) { try { close() } catch (_: Exception) { /* Owner remains retained. */ } } }
            }
        }

        fun capture(value: AndroidOriginalRpcClientOwner) { check(captured.complete(value)) }
        fun retainFailure(failure: Throwable) { captured.completeExceptionally(failure) }
        fun requireSelection(scope: CoroutineScope, actionId: DomainId, selection: LocalSetupLanSettings) {
            check(retainedScope === scope && originalPairingActionId == actionId && settings.sameSelection(selection)) {
                "Original Client attachment differs"
            }
        }
        fun owns(value: LocalSetupLanAuthentication): Boolean = authentication.get() === value && value.isCurrent()
        private fun current(): Boolean = !denied.get() && source.owns(this) &&
            retainedScope.coroutineContext[Job]?.isActive == true

        override suspend fun connect(endpoint: LocalSetupLanEndpoint) {
            AndroidLocalNetworkPermission.requireGranted(application)
            currentCoroutineContext().ensureActive()
            val selected = settings.validateEndpoint(endpoint)
            val pending = gate.withLock {
                check(current()) { "Original Client attachment is retired" }
                selectedEndpoint?.let { check(it == endpoint) { "Selected endpoint differs" } }
                connecting ?: owned.async(start = CoroutineStart.LAZY) {
                    val rpc = captured.await()
                    settings.credentialAction?.let { action ->
                        check(current())
                        val native = target.acquireCredentialJournalCustody(originalPairingActionId, action)
                        check(native is AndroidDevicePairingTargetResult.Value) { "Native credential custody is unavailable" }
                        custody = native.value // Retain actual custody before further IO or checking currentness.
                        check(current() && native.value.isCurrent())
                        val acquired = if (settings.workflow != LocalSetupLanWorkflow.AUTHENTICATION_ONLY)
                            AndroidPairedDeviceClientCredentials.acquireForActorIntents(application, native.value,
                                checkNotNull(settings.actorIntentAction))
                        else AndroidPairedDeviceClientCredentials.acquire(application, native.value)
                        check(acquired is AndroidPairedDeviceClientCredentialsResult.Opened) { "Device credential journal is unavailable" }
                        credentials = acquired.credentials // Retain before the raw target can transfer/close.
                        check(current() && acquired.credentials.isCurrent())
                    }
                    AndroidLocalNetworkPermission.requireGranted(application)
                    rpc.connect(selected) // Native readbacks, exact target close, actual SDK negotiation.
                    check(current())
                    val journal = credentials
                    if (journal != null) {
                        val approved = checkNotNull(rpc.approvedMaster.value)
                        check(journal.credentials.owner.scope == approved.scope &&
                            journal.credentials.owner.deviceId == approved.targetDeviceId && journal.isCurrent())
                        val labels = LocalBranchDisplayLabels(approved.scope, "Company " + approved.scope.companyId.value,
                            "Branch " + approved.scope.branchId.value)
                        val attachment = if (settings.workflow != LocalSetupLanWorkflow.AUTHENTICATION_ONLY) {
                            val profile = when (settings.workflow) {
                                LocalSetupLanWorkflow.SUPPLIERS -> LocalMasterRpcWorkflowProfile.SUPPLIERS
                                LocalSetupLanWorkflow.WAREHOUSES -> LocalMasterRpcWorkflowProfile.WAREHOUSES
                                LocalSetupLanWorkflow.REPORTS -> LocalMasterRpcWorkflowProfile.REPORTS
                                LocalSetupLanWorkflow.STOCK_TRANSFERS -> LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS
                                LocalSetupLanWorkflow.KITCHEN -> LocalMasterRpcWorkflowProfile.KITCHEN
                                LocalSetupLanWorkflow.RETAIL_ORDERS -> LocalMasterRpcWorkflowProfile.RETAIL_ORDERS
                                LocalSetupLanWorkflow.PAYMENT_CORRECTIONS -> LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS
                                LocalSetupLanWorkflow.CUSTOMER_RECEIPTS -> LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS
                                LocalSetupLanWorkflow.FOOD_CHECKS -> LocalMasterRpcWorkflowProfile.FOOD_CHECKS
                                LocalSetupLanWorkflow.FOOD_CASH_FINANCIAL -> LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL
                                LocalSetupLanWorkflow.FOOD_TABLE_ASSIGNMENTS -> LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS
                                LocalSetupLanWorkflow.INVOICE_CAPTURE -> LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE
                                LocalSetupLanWorkflow.INVOICE_MATCHING -> LocalMasterRpcWorkflowProfile.INVOICE_MATCHING
                                LocalSetupLanWorkflow.AUTHENTICATION_ONLY -> error("Business workflow selection is absent")
                            }
                            val endpoint = rpc.directoryClient(profile)
                            val intents = checkNotNull(journal.actorIntentSource(rpc)) { "Actor intent custody is unavailable" }
                            check(endpoint.isCurrent() && journal.isCurrent() && current())
                            val connection = LocalDirectoryClientConnection(endpoint, endpoint.connectionId,
                                journal.credentials.owner, labels, profile,
                                supplierReferences = profile == LocalMasterRpcWorkflowProfile.SUPPLIERS
                            ) { current() && journal.isCurrent() && endpoint.isCurrent() }
                            val gateway = LocalEndpointBranchGateway.directoryOnly(MutableStateFlow(connection),
                                journal.credentials, intents, profile)
                            LanAuthentication(gateway, journal, endpoint::isCurrent)
                        } else {
                            val service = rpc.authenticationService()
                            check(service.isCurrent())
                            val connection = LocalAuthenticationOnlyConnection(service, service.connectionId,
                                journal.credentials.owner, labels) { current() && journal.isCurrent() && service.isCurrent() }
                            val gateway = LocalEndpointBranchGateway.authenticationOnly(MutableStateFlow(connection), journal.credentials)
                            LanAuthentication(gateway, journal, service::isCurrent)
                        }
                        authentication.set(attachment) // Retain before checks/delivery; close owns the gateway.
                        check(attachment.isCurrent())
                    }
                }.also { selectedEndpoint = endpoint; connecting = it; it.start() }
            }
            pending.await()
            currentCoroutineContext().ensureActive()
            check(current())
            // Reobserve the same retained connection; never create another journal or owner.
            captured.await().connect(selected)
            authentication.get()?.let { check(it.isCurrent()) }
        }

        override suspend fun authentication(): LocalSetupLanAuthentication? {
            currentCoroutineContext().ensureActive()
            check(current())
            return authentication.get()?.takeIf { it.isCurrent() }
        }

        private inner class LanAuthentication(
            val actualGateway: LocalEndpointBranchGateway,
            private val journal: AndroidPairedDeviceClientCredentials,
            private val rpcCurrent: () -> Boolean,
        ) : LocalSetupLanAuthentication {
            private val locallyDenied = AtomicBoolean(false)
            override val gateway: BranchUiGateway get() = actualGateway
            override fun isCurrent(): Boolean = !locallyDenied.get() && current() &&
                authentication.get() === this && journal.isCurrent() && rpcCurrent() && actualGateway.configured
            override fun denyNewWork() { locallyDenied.set(true); actualGateway.close() }
        }

        override suspend fun close(): Unit = withContext(NonCancellable) {
            denied.set(true) // Synchronous denial precedes owned work drain and all actual close.
            try { authentication.get()?.denyNewWork() }
            catch (failure: Throwable) { denialFailure.compareAndSet(null, failure) }
            val pending = gate.withLock {
                closing ?: owned.async(start = CoroutineStart.LAZY) {
                    var failure: Throwable? = null
                    suspend fun attempt(action: suspend () -> Unit) {
                        try { action() } catch (caught: Throwable) {
                            if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
                        }
                    }
                    // Retain all acquired owners even if denial or connection preparation failed.
                    attempt { gate.withLock { connecting }?.join() }
                    // Drain accepted credential/application work before closing the actual file owner.
                    attempt { authentication.get()?.actualGateway?.closeAndAwait() }
                    attempt { captured.await().close() }
                    attempt { credentials?.close() }
                    attempt { custody?.close() }
                    denialFailure.get()?.let { caught ->
                        if (failure == null) failure = caught
                        else if (failure !== caught) failure!!.addSuppressed(caught)
                    }
                    failure?.let { throw it }
                    retired.complete(Unit)
                    Unit
                }.also { closing = it; it.start() }
            }
            pending.await()
            ownedJob.cancelAndJoin()
        }
    }

    private inner class Client : LocalSetupClient {
        val owner: AndroidLocalSetupNativeServices get() = this@AndroidLocalSetupNativeServices
        @Volatile private var acquired: AndroidLocalBranchRuntime? = null
        val value: AndroidLocalBranchRuntime get() = checkNotNull(acquired)
        fun capture(value: AndroidLocalBranchRuntime) { acquired = value }
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
        val owner: AndroidLocalSetupNativeServices get() = this@AndroidLocalSetupNativeServices
        override val requested: LocalEmployeeEnrollmentDefinition get() = value.requested
        override val approval: LocalEmployeeEnrollmentAuthorityReceipt get() = value.approval
        override val originalRequestId: DomainId get() = value.originalRequestId
        override val originalActionId: DomainId get() = value.originalActionId
        override val actorId: DomainId get() = value.actorId
    }
}
