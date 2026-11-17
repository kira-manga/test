package com.ukkera.pos.database

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.setup.DesktopSetupStorages
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.setup.*
import com.ukkera.pos.rpc.DesktopOriginalRpcClientOwner
import com.ukkera.pos.rpc.DesktopOriginalRpcHostOwner
import com.ukkera.pos.rpc.LocalOriginalRpcClientOwner
import com.ukkera.pos.rpc.LocalRpcRemoteDirectoryClient
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcExecutionEvidence
import dev.p2pkit.rpc.RpcFailure
import dev.p2pkit.rpc.RpcFailureKind
import dev.p2pkit.rpc.RpcFailurePhase
import dev.p2pkit.transport.lan.OrganizationLan
import java.nio.file.Path
import java.net.NetworkInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Self-authored, NOT independent. Production native admission, encrypted journals, current
 * authentication, TCP, UI observation and lookup supporting LAN-R01/R03/R04/R05 and LAN2-U09.
 * See LanRecoverySupporting.md for the deliberately smaller inputs and missing frozen actions.
 * No financial transfer, manufactured permission, cached login or native-custody substitute.
 */
internal object LanRecoverySupportingEndpoint {
    private const val HEAD_LOGIN = "head.one"
    private const val HEAD_PASSWORD = "synthetic-head-pass-2026"
    private const val OWNER_PASSWORD = "synthetic-owner-pass-2026"
    private const val CLIENT_PASSWORD = "synthetic-recovery-client-2026"
    private val profile = LocalMasterRpcWorkflowProfile.LAN_BUSINESS

    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 5)
        val (role, stage, directory, iface, address) = args
        val root = Path.of(directory)
        val exchange = LanFixtureExchange(root.resolve("exchange"))
        if (stage == "keeper") {
            // Network-namespace custody only. No application/native owner is opened here.
            exchange.putText("$role.namespace", ProcessHandle.current().pid().toString())
            while (exchange.read("namespaces.release") == null) delay(100)
            return@runBlocking
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(600_000L) {
                val network = checkNotNull(NetworkInterface.getByName(iface)) { "Assigned isolated interface is missing" }
                check(network.isUp && network.supportsMulticast() && !network.isLoopback &&
                    network.inetAddresses.toList().any { it.hostAddress == address }) {
                    "Assigned isolated interface/address is not ready"
                }
                check(NetworkInterface.getNetworkInterfaces().toList().none {
                    it.isUp && !it.isLoopback && it.name != iface
                }) { "Isolated application has competing active interfaces" }
                exchange.putText("$role.$stage.network", "single-active-interface-up-multicast-exact-private-address")
                val lan = OrganizationLan(listOf("10.203.17.0/24"), iface, address)
                if (role == "master") master(root, exchange, lan, scope, stage)
                else client(role, root, exchange, lan, scope, stage)
                exchange.putText("$role.$stage.closed", "confirmed-native-close")
            }
        } catch (failure: Throwable) {
            val at = failure.stackTrace.filter { it.className.startsWith("com.ukkera.pos.") }
                .take(5).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            System.err.println("Recovery supporting failure: $role/$stage ${failure.javaClass.simpleName}; $at")
            throw failure
        } finally { scope.cancel() }
    }

    private suspend fun master(root: Path, exchange: LanFixtureExchange, lan: OrganizationLan,
        lifetime: CoroutineScope, stage: String) {
        val original = LanGenuineProcessEndpoint.LanGenuineMaster(root.resolve("master"))
        var service: AndroidSelfOwnedMasterService? = null
        var host: DesktopOriginalRpcHostOwner? = null
        try {
            val coordinates: SelfOwnedAllocationCoordinatesV1
            if (stage == "initial") {
                original.installAndOpen()
                service = original.service
                coordinates = original.coordinates
                LocalMasterLaunchRecord(DesktopSetupStorages(root.resolve("master")).launch).retainRunning(coordinates)
            } else {
                check(stage == "master-restart")
                val opened = DesktopSelfOwnedInstallationOwner.open(original.os, SelfOwnedNativeProfileV1.LIVE)
                check(opened is SelfOwnedNativeOpenResult.Opened) { "Existing original Master unavailable" }
                coordinates = opened.context.materialAtOpen.coordinates
                check((LocalMasterLaunchRecord(DesktopSetupStorages(root.resolve("master")).launch).read()
                    as? LocalMasterLaunchRecord.Read.Running)?.matches(coordinates) == true)
                val publications = opened.context.readOriginalBusinessPublications().value("existing publications")
                val registry = LocalBranchCommerceRegistry(NativeCompanyPublicationSignatureVerifier(publications.trust),
                    UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
                    nativePublications = publications)
                val composed = original.factory.fromOriginalContext(opened.context, registry.commands, registry.reads,
                    UnavailablePhysicalRecordingExecutionGate, registry.nativeOriginalImports, registry.stockProjection, registry.historyProducer)
                check(composed is AndroidSelfOwnedMasterOpenResult.Opened) { "Existing operational Master unavailable" }
                service = composed.service
            }
            val held = checkNotNull(service)
            check(held.mode.value == LocalMasterLifecycleMode.OPERATIONAL) { "Existing Master mode: ${held.mode.value.name}" }
            val client = held.originalLocalClient()
            val sdk = client.recoverRpcMasterOriginal().value("original Master SDK")
            if (stage == "initial") {
                exchange.put("recovery.master.sdk", sdk.copyWireBytes())
                exchange.putText("recovery.scope", coordinates.scope.let {
                    "${it.organizationId.value}/${it.companyId.value}/${it.branchId.value}" })
                val devices = linkedSetOf(coordinates.deviceId)
                for (role in listOf("client1", "client2")) {
                    val identity = LocalDevicePairingSdkIdentityV2.decode(exchange.await("recovery.$role.sdk"))
                    val action = client.prepareSdkBoundDevicePairing(DomainId.random(), identity).value("pairing preparation")
                    exchange.put("recovery.$role.challenge", action.challenge.copyWireBytes())
                    val proven = client.proveDevicePairingTarget(action,
                        LocalDevicePairingPossession.decode(exchange.await("recovery.$role.possession"))).value("possession")
                    val approval = client.approveDevicePairing(proven, HEAD_LOGIN, HEAD_PASSWORD.toCharArray()).value("approval")
                    check(client.admitOriginalDevice(approval) is LocalCredentialAdministrationResult.Applied)
                    exchange.put("recovery.$role.transfer", LocalDevicePairingMasterTransfer.capture(approval).copyWireBytes())
                    check(devices.add(DomainId(exchange.awaitText("recovery.$role.imported"))))
                }
                for ((role, login) in listOf("client1" to "cashier.a", "client2" to "cashier.b")) {
                    val actor = DomainId.random()
                    val definition = original.account(actor, login, setOf(SecurityAction.CASH_READ,
                        SecurityAction.CASH_CONFIGURE, SecurityAction.CATALOG_READ, SecurityAction.INVENTORY_QUANTITY_READ,
                        SecurityAction.OPERATION_RESULT_RECOVERY), devices)
                    val request = DomainId.random()
                    val prepared = client.prepareEmployeeEnrollment(request, definition, true).value("employee preparation")
                    val enrollment = client.approveEmployeeEnrollment(prepared, HEAD_LOGIN, HEAD_PASSWORD.toCharArray()).value("employee approval")
                    check(enrollment.provisionAccount(request, definition, CLIENT_PASSWORD.toCharArray()) is LocalCredentialAdministrationResult.Applied)
                    exchange.putText("recovery.$role.actor", actor.value)
                }
            } else check(sdk.copyWireBytes().contentEquals(exchange.await("recovery.master.sdk"))) {
                "Master restart changed the original SDK identity"
            }
            host = DesktopOriginalRpcHostOwner.retainExisting(original.factory.owner, lifetime, client, lan, true) {
                original.os.openSdkIdentityStore(SelfOwnedNativeProfileV1.LIVE)
            }
            host.acquire(); host.start()
            exchange.putText("master.$stage.port", host.endpoint().port.toString())
            if (stage == "initial") {
                // Establish the original Host and both current physical Clients before
                // admitting local business/native-history writes, as in the original fixture.
                exchange.await("client1.initial.authenticated"); exchange.await("client2.initial.authenticated")
                for (role in listOf("client1", "client2")) withOwner(held) { endpoint, credentials ->
                    original.cash(endpoint, credentials, LocalCashRequestV1.ConfigureCustodian(
                        DomainId(exchange.awaitText("recovery.$role.actor"))))
                }
                exchange.putText("clients.configuration-ready", "current-host-clients-before-owner-cash-configuration")
                exchange.await("client1.initial.ready"); exchange.await("client2.initial.ready")
                exchange.await("client1.partitioned")
                configureDrawer(held, original, exchange, "partition")
                exchange.await("client2.partition.observed")
                exchange.putText("partition.restore-permitted", "other-client-converged")
                exchange.await("client1.reconnected")
                exchange.await("client1.initial.closed")
                configureDrawer(held, original, exchange, "client-restart")
                exchange.putText("client1.restart.start", "master-and-client2-remain-active")
                exchange.await("client1.client-restart.closed")
                exchange.putText("client2.initial.release", "close-before-master-restart")
                exchange.await("client2.initial.closed")
            } else {
                configureDrawer(held, original, exchange, "master-restart")
                exchange.putText("master.restart.changed", "committed")
                exchange.await("client1.master-restart.closed"); exchange.await("client2.master-restart.closed")
                LocalMasterLaunchRecord(DesktopSetupStorages(root.resolve("master")).launch).retainStop()
                check(LocalMasterLaunchRecord(DesktopSetupStorages(root.resolve("master")).launch).read() ==
                    LocalMasterLaunchRecord.Read.Stopped)
            }
        } finally {
            withContext(NonCancellable) {
                try { host?.close() } finally {
                    if (stage == "initial") original.close() else check(service?.quiesceAndClose() != false)
                }
            }
        }
    }

    private suspend fun configureDrawer(service: AndroidSelfOwnedMasterService,
        original: LanGenuineProcessEndpoint.LanGenuineMaster, exchange: LanFixtureExchange, stage: String) {
        val drawer = DomainId.random()
        exchange.putText("drawer.$stage", drawer.value)
        withOwner(service) { endpoint, credentials -> original.cash(endpoint, credentials,
            LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic recovery $stage", Currency.EGP)) }
        exchange.putText("drawer.$stage.committed", "durable")
    }

    private suspend fun <T> withOwner(service: AndroidSelfOwnedMasterService,
        action: suspend (LocalMasterClient, LocalGatewayCredentials) -> T): T {
        val endpoint = checkNotNull(service.client)
        val connection = checkNotNull(service.localConnectionId)
        val auth = endpoint.signIn(connection, LocalLoginIdentifier("owner.one"), OWNER_PASSWORD.toCharArray())
        check(auth is LocalAuthenticationResult.Authenticated)
        try { return action(endpoint, LocalGatewayCredentials(connection, auth.tokens.access)) }
        finally { try { check(endpoint.logout(LocalGatewayCredentials(connection, auth.tokens.access)) == LocalSignOutResult.Revoked) }
            finally { auth.tokens.destroy() } }
    }

    private suspend fun client(role: String, root: Path, exchange: LanFixtureExchange, lan: OrganizationLan,
        lifetime: CoroutineScope, stage: String) {
        val scope = exchange.awaitText("recovery.scope").split('/').let {
            require(it.size == 3); BranchScope(DomainId(it[0]), DomainId(it[1]), DomainId(it[2])) }
        val platform = LanLinuxClientTargetPlatform(root.resolve(role))
        var target: DesktopClientTargetHandle? = DesktopClientTargetHandle(if (stage == "initial")
            DesktopDevicePairingTarget.prepareNew(platform, scope, setOf("POS_CLIENT")) else DesktopDevicePairingTarget.openExisting(platform))
        var rpc: LocalOriginalRpcClientOwner? = null
        var journals: DesktopPairedDeviceClientCredentials? = null
        var gateway: LocalEndpointBranchGateway? = null
        try {
            val active = checkNotNull(target)
            val approved: LocalDevicePairingApprovedMaster
            if (stage == "initial") {
                val sdk = LocalMasterSdkOriginal.decode(exchange.await("recovery.master.sdk"))
                val identity = active.acquireSdkIdentity(sdk.appId, "Synthetic recovery $role")
                exchange.put("recovery.$role.sdk", identity.copyWireBytes())
                exchange.put("recovery.$role.possession", active.respond(
                    LocalDevicePairingChallenge.decode(exchange.await("recovery.$role.challenge"))).copyWireBytes())
                approved = active.importApprovedMaster(LocalDevicePairingMasterTransfer.decode(exchange.await("recovery.$role.transfer")))
                check(LocalMasterSdkOriginal.same(approved.masterSdkOriginal, sdk))
                exchange.putText("recovery.$role.action", approved.originalPairingActionId.value)
                exchange.putText("recovery.$role.imported", approved.targetDeviceId.value)
            } else {
                val recovered = active.recoverApprovedMasterData(DomainId(exchange.awaitText("recovery.$role.action")))
                check(recovered is LocalOriginalPairingTargetResult.Value)
                approved = recovered.value
                check(approved.targetDeviceId.value == exchange.awaitText("recovery.$role.imported"))
                check(active.recoverSdkIdentity().copyWireBytes().contentEquals(exchange.await("recovery.$role.sdk")))
                check(LocalMasterSdkOriginal.same(approved.masterSdkOriginal,
                    LocalMasterSdkOriginal.decode(exchange.await("recovery.master.sdk"))))
            }
            val owner = Any()
            val custody = active.acquireCredentialJournalCustody(owner, approved.originalPairingActionId,
                if (stage == "initial") LocalPairedDeviceCredentialJournalAction.ENROLL else LocalPairedDeviceCredentialJournalAction.REOPEN)
            val acquired = DesktopPairedDeviceClientCredentials.acquireForActorIntents(owner, custody,
                LanProcessJournalSupport.storage(root.resolve(role)))
            check(acquired is DesktopPairedDeviceClientCredentialsResult.Opened) { "Original protected Client journals unavailable" }
            val heldJournals = acquired.credentials.also { journals = it }
            val portStage = if (stage == "master-restart") stage else "initial"
            val port = exchange.awaitText("master.$portStage.port").toInt()
            val heldRpc = DesktopOriginalRpcClientOwner.retainRecoverableExisting(lifetime, active, approved.originalPairingActionId, lan) {
                platform.identityStore() }.also { rpc = it }
            target = null
            heldRpc.acquire(); heldRpc.connect(RpcEndpoint("10.203.17.1", port))
            val actor = DomainId(exchange.awaitText("recovery.$role.actor"))
            val identity = LocalClientIdentity(scope, actor, approved.targetDeviceId)
            val source = checkNotNull(heldJournals.actorIntentSource(heldRpc))
            check(source.current(identity) == null) { "Cold native reopen restored actor access before current authentication" }
            if (stage != "initial") {
                val retained = credentialSnapshot(heldJournals)
                try { check(retained.sessions.isNotEmpty()) { "Original retained revocation material was lost" } }
                finally { retained.destroy() }
                exchange.putText("$role.$stage.locked", "retained-tokens-and-native-identity-no-actor-access")
            }
            var directory = heldRpc.directoryClient(profile)
            fun newGateway(): LocalEndpointBranchGateway {
                val connection = LocalRemoteBusinessClientConnection(directory, directory.connectionId,
                    LocalClientDeviceIdentity(scope, approved.targetDeviceId), LocalBranchDisplayLabels(scope, "Synthetic Company", "Synthetic Branch")) {
                    directory.isCurrent() && heldJournals.isCurrent() }
                return LocalEndpointBranchGateway.remoteBusiness(MutableStateFlow(connection), heldJournals.credentials, source)
            }
            var heldGateway = newGateway().also { gateway = it }
            var session = signIn(role, heldGateway, replaceActive = stage != "initial")
            val journal = checkNotNull(source.current(identity))
            if (stage == "initial") {
                exchange.putText("$role.initial.authenticated", "fresh-ui-sign-in-and-current")
                exchange.await("clients.configuration-ready")
                observe(session)
                val batch = viewBatch(journal)
                exchange.putText("$role.initial.page", journalJson.encodeToString(StoredClientReadViewV1.serializer(), batch.pages.first()))
                if (role == "client1") stageUnknown(session, journal, directory, heldJournals, exchange)
                exchange.putText("$role.initial.ready", "current-protected-batch-and-journal")
                if (role == "client1") {
                    exchange.await("partition.restored")
                    check(findOriginal(journal, exchange).recorded == null)
                    heldGateway.closeAndAwait(); gateway = null
                    recordLanConnectExceptions(root, "client1-reconnect.jfr") {
                        recoverAndConnect(heldRpc, RpcEndpoint("10.203.17.1", port), exchange)
                    }
                    directory = heldRpc.directoryClient(profile)
                    heldGateway = newGateway().also { gateway = it }
                    session = signIn(role, heldGateway, replaceActive = true)
                    observe(session, DomainId(exchange.awaitText("drawer.partition")))
                    recoverUnknown(session, journal, exchange)
                    exchange.putText("client1.reconnected", "same-identity-confirmed-transport-close-fresh-auth-coherent-missed-update-original-lookup")
                } else {
                    exchange.await("client1.partitioned")
                    observe(session, DomainId(exchange.awaitText("drawer.partition")))
                    exchange.putText("client2.partition.observed", "automatic-ui-update-while-client1-link-down")
                    exchange.await("client2.initial.release")
                }
            } else {
                val missed = if (stage == "master-restart") {
                    exchange.await("master.restart.changed"); "master-restart"
                } else "client-restart"
                observe(session, DomainId(exchange.awaitText("drawer.$missed")))
                val batch = viewBatch(journal)
                val oldPage = journalJson.decodeFromString(StoredClientReadViewV1.serializer(), exchange.awaitText("$role.initial.page"))
                val old = oldPage.snapshot()
                check(batch.pages.all { it.snapshot().cursor.sessionId != old.cursor.sessionId }) {
                    "Cold restart reused cached session authority" }
                val credentials = gatewayCredentials(heldJournals, directory.connectionId)
                try {
                    val expired = directory.pull(credentials, profile, oldPage.selection(), old.cursor)
                    check(expired is LocalMasterChangesReplyV1.Unavailable && expired.reason == LocalMasterChangesFailureV1.CURSOR_EXPIRED)
                } finally { credentials.bearer.destroy() }
                if (role == "client1") {
                    val retained = findOriginal(journal, exchange)
                    check(retained.recorded == journalJson.decodeFromString(StoredKernelResultV1.serializer(),
                        exchange.awaitText("unknown.expected-result")) && !retained.acknowledged)
                    recoverUnknown(session, journal, exchange)
                }
                exchange.putText("$role.$stage.observed", "fresh-auth-original-identity-current-coherent-batch-no-mutation-replay")
            }
        } finally { withContext(NonCancellable) {
            try { gateway?.closeAndAwait() } finally { try { journals?.close() } finally { try { rpc?.close() } finally { target?.close() } } }
        } }
    }

    /**
     * Mirrors the production LAN Client monitor (LocalSetupCoordinator.startLanClientMonitorLocked):
     * confirmed whole-role recoverTransport, then the selected connect; after a refused connect wait
     * 1 s, doubling to at most 30 s, and repeat. Only the official SDK same-peer refusal shape
     * (NotConnected; Negotiation; NotSent) is retried. A healthy same-identity inbound session
     * can produce this generic refusal; that mechanism has not been established as the cause
     * of the historical POS partition failures. Every other failure is final, the identity
     * must stay exact, and no RPC, session or keepalive deadline is changed.
     */
    private suspend fun recoverAndConnect(rpc: LocalOriginalRpcClientOwner, endpoint: RpcEndpoint,
        exchange: LanFixtureExchange) {
        val started = System.nanoTime()
        val attempts = mutableListOf<String>()
        var wait = 1_000L
        while (true) {
            val before = checkNotNull(rpc.original.value)
            rpc.recoverTransport()
            val recovered = checkNotNull(rpc.original.value)
            check(recovered.identity.copyWireBytes().contentEquals(before.identity.copyWireBytes()) &&
                recovered.exactDeviceName == before.exactDeviceName) { "Recovered Client identity differs" }
            val refused = try { rpc.connect(endpoint); false } catch (failure: RpcFailure) {
                if (failure.kind != RpcFailureKind.NotConnected || failure.phase != RpcFailurePhase.Negotiation ||
                    failure.executionEvidence != RpcExecutionEvidence.NotSent) throw failure
                true
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000
            attempts += (if (refused) "NotConnected/Negotiation/NotSent" else "Ready") + "@${elapsed}ms"
            println("Recovery supporting reconnect: attempt=${attempts.size}; ${attempts.last()}")
            if (!refused) break
            check(attempts.size < 7) { "Selected reconnect still refused after the production schedule: $attempts" }
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000L)
        }
        exchange.putText("client1.reconnect.attempts", attempts.joinToString(";"))
    }

    private suspend fun signIn(role: String, gateway: LocalEndpointBranchGateway,
        replaceActive: Boolean = false): BranchUiSession {
        // Closing a UI locks its protected journal; it does not revoke the retained
        // Master session. Recovery explicitly requests the existing device-move policy.
        val result = gateway.signIn(if (role == "client1") "cashier.a" else "cashier.b", CLIENT_PASSWORD.toCharArray(), replaceActive)
        check(result is SignInResult.Authenticated) { "Explicit current UI sign-in failed: $result" }
        check(result.session.current() is LocalSessionCheckResult.Current)
        return result.session
    }

    private suspend fun observe(session: BranchUiSession, drawer: DomainId? = null) {
        val live = checkNotNull(session.workflows as? LocalWorkflowLiveViews)
        withTimeout(180_000L) {
            live.readUpdates(LocalWorkflowQuery(LocalWorkflow.CASH)).first { update ->
                when (update) {
                    is LocalWorkflowReadUpdate.Available -> drawer == null || update.page.records.any {
                        it.reference.kind == LocalRecordKind.DRAWER && it.reference.id == drawer }
                    is LocalWorkflowReadUpdate.Unavailable -> error("Current UI observation unavailable: ${update.problem}")
                    LocalWorkflowReadUpdate.RestartRequired -> error("Root query requested restart")
                }
            }
        }
    }

    private suspend fun stageUnknown(session: BranchUiSession, journal: LocalClientIntentJournal,
        directory: LocalRpcRemoteDirectoryClient, journals: DesktopPairedDeviceClientCredentials, exchange: LanFixtureExchange) {
        val operation = OperationId.random()
        val drawer = DomainId.random()
        val intent = LocalCommandIntent(operation, LocalAction.DRAWER_CONFIGURE, 1, null, listOf(
            LocalScalarInput(LocalField.DRAWER_ID, LocalScalarValue.Identifier(drawer)),
            LocalScalarInput(LocalField.LABEL, LocalScalarValue.Text("Synthetic unresolved metadata"))))
        check(session.workflows.stage(intent) is LocalStageResult.Staged)
        check(journal.markDispatched(operation) == JournalWrite.DURABLE)
        val saved = (journal.find(operation) as? JournalRead.Value)?.value ?: error("Staged original missing")
        exchange.putText("unknown.original", journalJson.encodeToString(StoredClientIntentV1.serializer(), saved))
        val credentials = gatewayCredentials(journals, directory.connectionId)
        try {
            val result = directory.execute(credentials, saved.request())
            check(result is MutationReply.Recorded && result.committed)
            exchange.putText("unknown.expected-result", journalJson.encodeToString(StoredKernelResultV1.serializer(), StoredKernelResultV1.from(result)))
            // Deliberately lose application delivery: no remember/ACK. The actual committed
            // Master result and exact encrypted DISPATCHED original must survive every cut.
            check((journal.find(operation) as JournalRead.Value).value == saved)
        } finally { credentials.bearer.destroy() }
    }

    private suspend fun recoverUnknown(session: BranchUiSession, journal: LocalClientIntentJournal, exchange: LanFixtureExchange) {
        val before = findOriginal(journal, exchange)
        check(session.workflows.lookup(before.operationId) is LocalOperationResult.Recorded)
        val after = findOriginal(journal, exchange)
        check(after.sameIntent(before) && !after.acknowledged && after.recorded == journalJson.decodeFromString(
            StoredKernelResultV1.serializer(), exchange.awaitText("unknown.expected-result")))
    }

    private suspend fun findOriginal(journal: LocalClientIntentJournal, exchange: LanFixtureExchange): StoredClientIntentV1 {
        val original = journalJson.decodeFromString(StoredClientIntentV1.serializer(), exchange.awaitText("unknown.original"))
        val saved = (journal.find(original.operationId) as? JournalRead.Value)?.value ?: error("Retained original missing")
        check(saved.sameIntent(original) && !saved.acknowledged)
        return saved
    }

    private suspend fun viewBatch(journal: LocalClientIntentJournal): StoredClientReadViewBatchV1 =
        ((checkNotNull(journal.readViews).load() as? JournalRead.Value)?.value ?: error("Protected coherent batch missing"))
            .also { it.validate(journal.owner) }

    private suspend fun credentialSnapshot(journals: DesktopPairedDeviceClientCredentials): StoredClientCredentials =
        (journals.credentials.read() as? JournalRead.Value)?.value ?: error("Protected credential journal unavailable")

    private suspend fun gatewayCredentials(journals: DesktopPairedDeviceClientCredentials, connection: DomainId): LocalGatewayCredentials {
        val snapshot = credentialSnapshot(journals)
        try { return LocalGatewayCredentials(connection, snapshot.sessions.last().access.useBytes(::OpaqueCredential)) }
        finally { snapshot.destroy() }
    }

    private fun <T> LocalCompanyAuthorityResult<T>.value(stage: String): T = when (this) {
        is LocalCompanyAuthorityResult.Value -> value
        is LocalCompanyAuthorityResult.Unavailable -> error("$stage unavailable: $reason")
    }
}
