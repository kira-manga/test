package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.gateway.DesktopClientJournalStorage
import com.ukkera.pos.app.gateway.DesktopPairedDeviceClientCredentials
import com.ukkera.pos.app.gateway.DesktopPairedDeviceClientCredentialsResult
import com.ukkera.pos.app.gateway.LocalAuthenticationOnlyConnection
import com.ukkera.pos.app.gateway.LocalBranchDisplayLabels
import com.ukkera.pos.app.gateway.LocalClientPublication
import com.ukkera.pos.app.gateway.LocalClientRole
import com.ukkera.pos.app.gateway.LocalRemoteBusinessClientConnection
import com.ukkera.pos.app.gateway.LocalDirectoryClientConnection
import com.ukkera.pos.app.gateway.localPairedClientConnection
import com.ukkera.pos.app.gateway.LocalEndpointBranchGateway
import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.setup.LocalSetupLanEndpoint
import com.ukkera.pos.app.setup.LocalSetupLanWorkflow
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.rpc.DesktopOriginalRpcClientOwner
import com.ukkera.pos.rpc.LocalOriginalRpcClientOwner
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.DesktopClientTargetHandle
import com.ukkera.pos.security.LocalCompositionSdkProfileV1
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingMasterTransfer
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingTargetDescriptor
import com.ukkera.pos.security.LocalDevicePairingTargetReference
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.security.LocalOriginalPairingTargetResult
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import com.ukkera.pos.security.LocalTrainingSdkProfileV1
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Desktop OS natives for the in-app Client: target custody, journal storage and the SDK identity store. */
public class DesktopClientSetupPlatform(
    internal val prepare: (LocalTrainingCompositionSlot?, BranchScope, Set<String>) -> DesktopClientTargetHandle,
    internal val openExisting: (LocalTrainingCompositionSlot?) -> DesktopClientTargetHandle,
    internal val journalStorage: () -> DesktopClientJournalStorage,
    internal val identityStore: () -> JvmSecureIdentityStore,
)

/**
 * Desktop UI-process composition: the additional-device (Client) role only. The desktop Master is
 * the OS-supervised background service, never this UI process, so every Master/provisioner entry is
 * fail-closed UNAVAILABLE here. Pairing ceremony, setup intents, LAN policy and paired actor-intent
 * custody (allocation ledger, fresh Master Current, actor journals) are shared with Android.
 */
public class DesktopLocalSetupNativeServices(
    private val platform: DesktopClientSetupPlatform,
    private val publication: LocalClientPublication,
    private val trainingSlot: LocalTrainingCompositionSlot? = null,
) : LocalSetupNativeServices {
    override suspend fun observeLanNetworks(): List<com.ukkera.pos.app.setup.LocalSetupLanNetwork> =
        com.ukkera.pos.app.gateway.setup.LocalSetupNativeLanNetworks.observe()

    override val compositionProfile: LocalCompositionSdkProfileV1 =
        trainingSlot?.let { LocalTrainingSdkProfileV1(it) } ?: LocalMasterSdkProfileV1
    private var freshTargetTransfer: FreshDeviceTarget? = null

    override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult =
        LocalProvisionerOpenResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult =
        SelfOwnedNativeOpenResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult =
        LocalSetupMasterOpenResult.Unresolved(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult =
        error("The desktop UI process never owns Master installation records")
    override suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient? = null
    override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean = false
    override fun publishClient(owner: Any, client: LocalSetupClient): Unit = error("No Master client in the desktop UI process")
    override fun withdrawClient(owner: Any) { publication.withdraw(owner) }

    override suspend fun prepareFreshDeviceTarget(scope: BranchScope, capabilities: Set<String>): LocalSetupFreshDeviceTargetOpenResult =
        acquire { platform.prepare(trainingSlot, scope, capabilities) }

    override suspend fun openOriginalFreshDeviceTarget(): LocalSetupFreshDeviceTargetOpenResult =
        acquire { platform.openExisting(trainingSlot) }

    private suspend fun acquire(factory: () -> DesktopClientTargetHandle): LocalSetupFreshDeviceTargetOpenResult {
        check(freshTargetTransfer == null)
        val holder = FreshDeviceTarget()
        freshTargetTransfer = holder // Retained before native IO; an exception leaves custody unresolved.
        return try {
            holder.capture(withContext(Dispatchers.IO) { factory() })
            LocalSetupFreshDeviceTargetOpenResult.Opened(holder)
        } catch (failure: Throwable) {
            val reason = DesktopClientTargetHandle.refusalReason(failure) ?: throw failure // Unknown outcome stays retained.
            freshTargetTransfer = null // The native factory refused before creating an owner.
            LocalSetupFreshDeviceTargetOpenResult.Unresolved(reason, null)
        }
    }

    override fun publishLanAuthentication(owner: Any, client: LocalSetupLanClient, authentication: LocalSetupLanAuthentication) {
        val actual = client as? LanClient
        check(actual != null && freshTargetTransfer?.owns(actual) == true && actual.owns(authentication))
        publication.publish(owner, authentication.gateway, LocalClientRole.CLIENT, authentication::isCurrent, authentication::denyNewWork)
    }

    override fun retainLanClient(target: LocalSetupFreshDeviceTarget, scope: CoroutineScope,
        originalPairingActionId: DomainId, settings: LocalSetupLanSettings): LocalSetupLanClient {
        val actual = target as? FreshDeviceTarget
        check(actual != null && actual.owner === this && freshTargetTransfer === actual)
        return actual.retainLanClient(scope, originalPairingActionId, settings)
    }

    private inner class FreshDeviceTarget : LocalSetupFreshDeviceTarget {
        override val compositionProfile: LocalCompositionSdkProfileV1? get() = acquired?.compositionProfile
        val owner: DesktopLocalSetupNativeServices get() = this@DesktopLocalSetupNativeServices
        private val attachmentGate = Any()
        @Volatile private var acquired: DesktopClientTargetHandle? = null
        private var denied = false
        private var lanClient: LanClient? = null
        val value: DesktopClientTargetHandle get() = checkNotNull(acquired)
        fun capture(value: DesktopClientTargetHandle) { acquired = value }
        fun owns(client: LanClient): Boolean = synchronized(attachmentGate) { !denied && lanClient === client }

        fun retainLanClient(scope: CoroutineScope, originalPairingActionId: DomainId, settings: LocalSetupLanSettings): LanClient =
            synchronized(attachmentGate) {
                check(freshTargetTransfer === this)
                lanClient?.let { it.requireSelection(scope, originalPairingActionId, settings); return@synchronized it }
                check(!denied) { "Fresh target is retired" }
                val holder = LanClient(scope, originalPairingActionId, settings, this, value)
                lanClient = holder // Transfer and ceremony fence precede the native constructor.
                try {
                    holder.capture(DesktopOriginalRpcClientOwner.retainRecoverableExisting(scope, value, originalPairingActionId,
                        settings.lan, platform.identityStore))
                    holder
                } catch (failure: Throwable) { holder.retainFailure(failure); throw failure }
            }

        private suspend fun <T> run(action: (DesktopClientTargetHandle) -> T): LocalSetupFreshDeviceTargetResult<T> {
            val current = synchronized(attachmentGate) { if (denied || lanClient != null) null else acquired }
                ?: return LocalSetupFreshDeviceTargetResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            return try { LocalSetupFreshDeviceTargetResult.Value(withContext(Dispatchers.IO) { action(current) }) }
            catch (failure: Throwable) {
                LocalSetupFreshDeviceTargetResult.Unavailable(DesktopClientTargetHandle.refusalReason(failure) ?: throw failure)
            }
        }
        private fun <T> data(result: LocalOriginalPairingTargetResult<T>): LocalSetupFreshDeviceTargetResult<T> = when (result) {
            is LocalOriginalPairingTargetResult.Value -> LocalSetupFreshDeviceTargetResult.Value(result.value)
            is LocalOriginalPairingTargetResult.Unavailable -> LocalSetupFreshDeviceTargetResult.Unavailable(result.reason)
        }

        override suspend fun original(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> = run { it.original() }
        override suspend fun descriptor(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetDescriptor> = run { it.descriptor() }
        override suspend fun acquireSdkIdentity(appId: AppId, deviceName: String): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> =
            run { it.acquireSdkIdentity(appId, deviceName) }
        override suspend fun recoverSdkIdentity(): LocalSetupFreshDeviceTargetResult<LocalDevicePairingSdkIdentityV2> = run { it.recoverSdkIdentity() }
        override suspend fun respond(challenge: LocalDevicePairingChallenge): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> =
            run { it.respond(challenge) }
        override suspend fun recoverPossession(originalPairingActionId: DomainId): LocalSetupFreshDeviceTargetResult<LocalDevicePairingPossession> =
            run { it.recoverPossession(originalPairingActionId) }
        override suspend fun importApprovedMaster(transfer: LocalDevicePairingMasterTransfer): LocalSetupFreshDeviceTargetResult<LocalDevicePairingApprovedMaster> =
            run { it.importApprovedMaster(transfer) }
        override suspend fun recoverApprovedMaster(originalPairingActionId: DomainId): LocalSetupFreshDeviceTargetResult<LocalDevicePairingApprovedMaster> {
            val current = synchronized(attachmentGate) { if (denied || lanClient != null) null else acquired }
                ?: return LocalSetupFreshDeviceTargetResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            return data(withContext(Dispatchers.IO) { current.recoverApprovedMasterData(originalPairingActionId) })
        }
        override suspend fun beginAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> =
            run { it.beginAfterKnownUnsigned(retirement) }
        override suspend fun recoverAfterKnownUnsigned(retirement: LocalDevicePairingUnsignedRetirement): LocalSetupFreshDeviceTargetResult<LocalDevicePairingTargetReference> =
            run { it.recoverAfterKnownUnsigned(retirement) }
        override fun denyNewWork() {
            synchronized(attachmentGate) { denied = true; if (lanClient == null) acquired?.denyNewWork() }
        }
        override suspend fun close() {
            denyNewWork()
            val attachment = synchronized(attachmentGate) { lanClient }
            if (attachment == null) acquired?.close() else attachment.close()
            check(freshTargetTransfer === this)
            freshTargetTransfer = null
        }
    }

    private inner class LanClient(
        private val retainedScope: CoroutineScope,
        private val originalPairingActionId: DomainId,
        private val settings: LocalSetupLanSettings,
        private val source: FreshDeviceTarget,
        private val target: DesktopClientTargetHandle,
    ) : LocalSetupLanClient {
        private val captured = CompletableDeferred<LocalOriginalRpcClientOwner>()
        private val gate = Mutex()
        private val denied = AtomicBoolean(false)
        private val ownedJob = SupervisorJob()
        private val owned = CoroutineScope(retainedScope.coroutineContext.minusKey(Job) + ownedJob)
        private var preparing: Deferred<LocalOriginalRpcClientOwner>? = null
        private var discovering: Deferred<Unit>? = null
        private var selectedCandidateId: DomainId? = null
        private var connecting: Deferred<Unit>? = null
        private var selectedEndpoint: LocalSetupLanEndpoint? = null
        private var closing: Deferred<Unit>? = null
        private var recovering: Deferred<Unit>? = null
        private val transportChanging = AtomicBoolean(false)
        private var custody: PairedDeviceCredentialJournalCustody? = null
        private var credentials: DesktopPairedDeviceClientCredentials? = null
        private val authentication = AtomicReference<LanAuthentication?>(null)
        private val retired = CompletableDeferred<Unit>()
        private val denialFailure = AtomicReference<Throwable?>(null)

        init {
            retainedScope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { retired.await() }
                finally { withContext(NonCancellable) { try { close() } catch (_: Exception) { /* Owner remains retained. */ } } }
            }
        }

        fun capture(value: LocalOriginalRpcClientOwner) { check(captured.complete(value)) }
        fun retainFailure(failure: Throwable) { captured.completeExceptionally(failure) }
        fun requireSelection(scope: CoroutineScope, actionId: DomainId, selection: LocalSetupLanSettings) {
            check(retainedScope === scope && originalPairingActionId == actionId && settings.sameSelection(selection)) {
                "Original Client attachment differs"
            }
        }
        fun owns(value: LocalSetupLanAuthentication): Boolean = authentication.get() === value && value.isCurrent()
        private fun current(): Boolean = !denied.get() && source.owns(this) && retainedScope.coroutineContext[Job]?.isActive == true
        private fun workCurrent(): Boolean = current() && !transportChanging.get()

        override val discoveredMasters: kotlinx.coroutines.flow.Flow<List<com.ukkera.pos.app.setup.LocalSetupLanDiscoveredMaster>> =
            kotlinx.coroutines.flow.flow {
                captured.await().discoveredMasters.collect { entries ->
                    emit(if (current()) entries.map { entry ->
                        com.ukkera.pos.app.setup.LocalSetupLanDiscoveredMaster(entry.candidateId, entry.displayName,
                            entry.fingerprint, entry.matchesOriginalMaster)
                    } else emptyList())
                }
            }

        private suspend fun prepareOwned(): LocalOriginalRpcClientOwner {
            currentCoroutineContext().ensureActive()
            val pending = gate.withLock {
                check(current()) { "Original Client attachment is retired" }
                preparing ?: owned.async(start = CoroutineStart.LAZY) {
                    val rpc = captured.await()
                    settings.credentialAction?.let { action ->
                        check(current())
                        val native = withContext(Dispatchers.IO) {
                            target.acquireCredentialJournalCustody(this@LanClient, originalPairingActionId, action)
                        }
                        custody = native // Retain actual custody before further IO or currentness checks.
                        check(current() && native.isCurrent())
                        val storage = withContext(Dispatchers.IO) { platform.journalStorage() }
                        val acquired = if (settings.workflow != LocalSetupLanWorkflow.AUTHENTICATION_ONLY)
                            DesktopPairedDeviceClientCredentials.acquireForActorIntents(this@LanClient, native, storage,
                                checkNotNull(settings.actorIntentAction))
                        else DesktopPairedDeviceClientCredentials.acquire(this@LanClient, native, storage)
                        check(acquired is DesktopPairedDeviceClientCredentialsResult.Opened) { "Device credential journal is unavailable" }
                        credentials = acquired.credentials
                        check(current() && acquired.credentials.isCurrent())
                    }
                    check(current())
                    rpc
                }.also { preparing = it; it.start() }
            }
            val rpc = pending.await()
            currentCoroutineContext().ensureActive()
            check(current())
            return rpc
        }

        override suspend fun startDiscovery() {
            currentCoroutineContext().ensureActive()
            val pending = gate.withLock {
                check(workCurrent() && recovering?.isCompleted != false && selectedEndpoint == null && selectedCandidateId == null) {
                    "Original Client discovery is unavailable"
                }
                discovering ?: owned.async(start = CoroutineStart.LAZY) {
                    val rpc = prepareOwned()
                    rpc.startDiscovery()
                    check(current())
                }.also { recovering = null; discovering = it; it.start() }
            }
            pending.await()
            currentCoroutineContext().ensureActive()
            gate.withLock { check(workCurrent() && discovering === pending) { "Original discovery generation is retired" } }
        }

        override suspend fun connect(endpoint: LocalSetupLanEndpoint) {
            settings.validateEndpoint(endpoint)
            connectSelected(endpoint, null)
        }

        override suspend fun connectDiscovered(candidateId: DomainId) {
            connectSelected(null, candidateId)
        }

        private suspend fun connectSelected(endpoint: LocalSetupLanEndpoint?, candidateId: DomainId?) {
            currentCoroutineContext().ensureActive()
            check((endpoint == null) != (candidateId == null))
            val pending = gate.withLock {
                check(workCurrent() && recovering?.isCompleted != false) { "Original Client attachment is retired" }
                if (connecting != null) check(selectedEndpoint == endpoint && selectedCandidateId == candidateId) {
                    "Selected Master differs"
                }
                connecting ?: owned.async(start = CoroutineStart.LAZY) {
                    val rpc = prepareOwned()
                    if (endpoint != null) rpc.connect(settings.validateEndpoint(endpoint))
                    else rpc.connectDiscovered(checkNotNull(candidateId))
                    publishAuthentication(rpc)
                }.also {
                    selectedEndpoint = endpoint
                    selectedCandidateId = candidateId
                    recovering = null
                    connecting = it
                    it.start()
                }
            }
            pending.await()
            currentCoroutineContext().ensureActive()
            gate.withLock { check(workCurrent() && connecting === pending) { "Original Client generation is retired" } }
            val rpc = captured.await()
            // Reobserve the same physical session; never acquire another owner or journal.
            if (endpoint != null) rpc.connect(settings.validateEndpoint(endpoint))
            else rpc.connectDiscovered(checkNotNull(candidateId))
            authentication.get()?.let { check(it.isCurrent()) }
        }

        private suspend fun publishAuthentication(rpc: LocalOriginalRpcClientOwner) {
            check(workCurrent())
            val journal = credentials
            if (journal != null) {
                val approved = checkNotNull(rpc.approvedMaster.value)
                check(journal.credentials.owner.scope == approved.scope &&
                    journal.credentials.owner.deviceId == approved.targetDeviceId && journal.isCurrent())
                val labels = LocalBranchDisplayLabels(approved.scope, "Company " + approved.scope.companyId.value,
                    "Branch " + approved.scope.branchId.value)
                val profile = settings.workflow.masterRpcProfile()
                val attachment = if (profile != null) {
                    val endpoint = rpc.directoryClient(profile)
                    val intents = checkNotNull(journal.actorIntentSource(rpc)) { "Actor intent custody is unavailable" }
                    check(endpoint.isCurrent() && journal.isCurrent() && current())
                    // Same retained original Client; the Master authorizes every business call.
                    val creation = rpc.employeeCreationClient(endpoint)
                    check(creation.isCurrent() && endpoint.isCurrent() && current())
                    val connection = localPairedClientConnection(endpoint, endpoint.connectionId,
                        journal.credentials.owner, labels, profile,
                        acquirePhysicalBinding = { journal.remotePhysicalBinding(rpc, endpoint) }
                    ) { workCurrent() && journal.isCurrent() && endpoint.isCurrent() }
                    val gateway = when (connection) {
                        is LocalRemoteBusinessClientConnection -> LocalEndpointBranchGateway.remoteBusiness(
                            MutableStateFlow(connection), journal.credentials, intents, creation)
                        is LocalDirectoryClientConnection -> LocalEndpointBranchGateway.directoryWithEmployeeCreation(
                            MutableStateFlow(connection), journal.credentials, intents, profile, creation)
                        else -> error("Business workflow attachment is absent")
                    }
                    LanAuthentication(gateway, journal, endpoint::isCurrent)
                } else {
                    val service = rpc.authenticationService()
                    check(service.isCurrent())
                    val connection = localPairedClientConnection(service, service.connectionId,
                        journal.credentials.owner, labels, profile = null
                    ) { workCurrent() && journal.isCurrent() && service.isCurrent() } as LocalAuthenticationOnlyConnection
                    val gateway = LocalEndpointBranchGateway.authenticationOnly(MutableStateFlow(connection), journal.credentials)
                    LanAuthentication(gateway, journal, service::isCurrent)
                }
                authentication.set(attachment) // Retain before checks/delivery; close owns the gateway.
                check(attachment.isCurrent())
            }
        }

        override suspend fun recoverTransport() {
            currentCoroutineContext().ensureActive()
            val pending = gate.withLock {
                check(current() && closing == null) { "Original Client attachment is retired" }
                recovering ?: run {
                    check(preparing?.isCompleted == true && preparing?.isCancelled == false &&
                        (discovering != null || connecting != null)) { "Original native preparation is unconfirmed" }
                    val oldDiscovery = discovering
                    val oldConnection = connecting
                    transportChanging.set(true) // Deny every old connection closure before draining it.
                    try { authentication.get()?.denyNewWork() }
                    catch (failure: Throwable) { denialFailure.compareAndSet(null, failure) }
                    owned.async(start = CoroutineStart.LAZY) {
                        oldDiscovery?.join()
                        oldConnection?.join()
                        // A just-finishing connect may have retained its gateway after initial denial.
                        authentication.get()?.let { old -> old.denyNewWork(); old.actualGateway.closeAndAwait() }
                        denialFailure.get()?.let { throw it }
                        val rpc = checkNotNull(preparing).await()
                        rpc.recoverTransport()
                        gate.withLock {
                            check(current() && closing == null && credentials?.isCurrent() != false) {
                                "Recovered native Client attachment is retired"
                            }
                            authentication.set(null)
                            discovering = null
                            connecting = null
                            selectedEndpoint = null
                            selectedCandidateId = null
                            transportChanging.set(false)
                        }
                    }.also { recovering = it; it.start() }
                }
            }
            pending.await()
            currentCoroutineContext().ensureActive()
            gate.withLock { check(workCurrent() && recovering === pending) { "Native Client recovery is retired" } }
        }

        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        override fun transportConnectedNow(): Boolean {
            if (!workCurrent() || !captured.isCompleted || captured.isCancelled) return false
            return try { captured.getCompleted().transportConnectedNow() } catch (_: Exception) { false }
        }

        override suspend fun authentication(): LocalSetupLanAuthentication? {
            currentCoroutineContext().ensureActive()
            check(workCurrent())
            return authentication.get()?.takeIf { it.isCurrent() }
        }

        private inner class LanAuthentication(
            val actualGateway: LocalEndpointBranchGateway,
            private val journal: DesktopPairedDeviceClientCredentials,
            private val rpcCurrent: () -> Boolean,
        ) : LocalSetupLanAuthentication {
            private val locallyDenied = AtomicBoolean(false)
            override val gateway: BranchUiGateway get() = actualGateway
            override fun isCurrent(): Boolean = !locallyDenied.get() && workCurrent() &&
                authentication.get() === this && journal.isCurrent() && rpcCurrent() && actualGateway.configured
            override fun denyNewWork() { locallyDenied.set(true); actualGateway.close() }
        }

        override suspend fun close(): Unit = withContext(NonCancellable) {
            denied.set(true)
            try { authentication.get()?.denyNewWork() } catch (failure: Throwable) { denialFailure.compareAndSet(null, failure) }
            val pending = gate.withLock {
                closing ?: owned.async(start = CoroutineStart.LAZY) {
                    var failure: Throwable? = null
                    suspend fun attempt(action: suspend () -> Unit) {
                        try { action() } catch (caught: Throwable) {
                            if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
                        }
                    }
                    attempt { gate.withLock { recovering }?.join() }
                    attempt { gate.withLock { preparing }?.join() }
                    attempt { gate.withLock { discovering }?.join() }
                    attempt { gate.withLock { connecting }?.join() }
                    attempt { authentication.get()?.actualGateway?.closeAndAwait() }
                    attempt { captured.await().close() }
                    attempt { credentials?.close() }
                    attempt { custody?.close() }
                    denialFailure.get()?.let { caught ->
                        if (failure == null) failure = caught else if (failure !== caught) failure!!.addSuppressed(caught)
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
}
