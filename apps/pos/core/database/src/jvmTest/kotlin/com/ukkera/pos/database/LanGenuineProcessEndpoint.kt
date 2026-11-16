package com.ukkera.pos.database

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.setup.*
import com.ukkera.pos.rpc.DesktopOriginalRpcClientOwner
import com.ukkera.pos.rpc.DesktopOriginalRpcHostOwner
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.transport.lan.OrganizationLan
import java.nio.file.Path
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import jdk.jfr.Configuration
import jdk.jfr.Recording
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect

/**
 * Supporting executor, NOT an independent oracle. Every authority-bearing value below comes
 * from the actual production provisioner, native target, original Master, or protected RPC.
 * Fixture control files transport public ceremony DATA only; they are never admission/trust.
 * This Linux SOFTWARE-OS stand-in does not qualify a Windows/macOS installation or owner device.
 * Full frozen cases remain NOT RUN until their complete mapped actions have been executed.
 */
internal object LanGenuineProcessEndpoint {
    @JvmStatic fun main(arguments: Array<String>): Unit = runBlocking {
        require(arguments.size in 4..6)
        val (role, directory, iface, address) = arguments.take(4)
        val processWatchdog = arguments.getOrNull(4)?.toLong() ?: 240_000L
        require(processWatchdog in 240_000L..600_000L)
        require(role in setOf("master", "client1", "client2"))
        val root = Path.of(directory)
        val barrierWatchdog = arguments.getOrNull(5)?.toLong() ?: 180_000L
        require(barrierWatchdog in 180_000L..processWatchdog &&
            (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY) || barrierWatchdog == 180_000L))
        val exchange = LanFixtureExchange(root.resolve("exchange"), barrierWatchdog)
        exchange.putText("$role.boot", ProcessHandle.current().pid().toString())
        exchange.await("network.ready")
        if (java.lang.Boolean.getBoolean("ukkera.lan.singleNic")) {
            val up = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            check(up.size == 1 && up.single().name == iface && up.single().supportsMulticast() &&
                up.single().inetAddresses.toList().any { it.hostAddress == address }) {
                "Supporting single-NIC topology does not meet the actual SDK network prerequisite"
            }
            exchange.putText("$role.network-profile", "one-up-nonloopback-multicast-interface-with-exact-address")
        }
        val lan = OrganizationLan(listOf("10.203.16.0/24"), iface, address)
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(processWatchdog) {
                if (role == "master") master(root, exchange, lan, lifetime)
                else client(role, root, exchange, lan, lifetime)
            }
            exchange.putText("$role.closed", "confirmed")
        } finally { lifetime.cancel() }
    }

    private suspend fun master(root: Path, exchange: LanFixtureExchange, lan: OrganizationLan,
        lifetime: CoroutineScope) {
        val master = LanGenuineMaster(root.resolve("master"))
        var host: DesktopOriginalRpcHostOwner? = null
        try {
            master.installAndOpen()
            master.observeAndAcknowledgeSavedSetup(exchange)
            val original = master.service.originalLocalClient()
            val sdk = original.recoverRpcMasterOriginal().valueOrFail("Master SDK original")
            exchange.put("master.sdk", sdk.copyWireBytes())
            exchange.putText("master.scope", master.scopeIds())
            val devices = linkedSetOf(master.coordinates.deviceId)
            val sdkPeers = linkedSetOf(sdk.peerId)
            val sdkFingerprints = linkedSetOf(sdk.fingerprint)
            val actors = linkedMapOf<String, DomainId>()
            for (role in listOf("client1", "client2")) {
                val identity = LocalDevicePairingSdkIdentityV2.decode(exchange.await("$role.sdk"))
                check(identity.appId == sdk.appId && sdkPeers.add(identity.peerId) && sdkFingerprints.add(identity.fingerprint)) {
                    "Each genuine target requires its own SDK identity within the actual original Master namespace"
                }
                val action = original.prepareSdkBoundDevicePairing(DomainId.random(), identity)
                    .valueOrFail("$role pairing preparation")
                exchange.put("$role.challenge", action.challenge.copyWireBytes())
                val possession = LocalDevicePairingPossession.decode(exchange.await("$role.possession"))
                val proven = original.proveDevicePairingTarget(action, possession).valueOrFail("$role target possession")
                val approval = original.approveDevicePairing(proven, HEAD_LOGIN, HEAD_PASSWORD.toCharArray())
                    .valueOrFail("$role fresh named-head approval")
                check(original.admitOriginalDevice(approval) is LocalCredentialAdministrationResult.Applied) {
                    "$role genuine original admission did not apply"
                }
                exchange.put("$role.master-transfer", LocalDevicePairingMasterTransfer.capture(approval).copyWireBytes())
                val importedDevice = DomainId(exchange.awaitText("$role.imported"))
                check(importedDevice == identity.target.admission.deviceId && devices.add(importedDevice)) {
                    "Distinct actual approved/imported target device required"
                }
            }
            // A and B are separate genuine head-approved employee enrollments. The complete
            // actual admitted-device set is part of each reviewed definition; no grant seeding.
            for ((role, login) in listOf("client1" to "cashier.a", "client2" to "cashier.b")) {
                val actor = DomainId.random()
                val definition = master.account(actor, login, CLIENT_ACTIONS, devices)
                val request = DomainId.random()
                val prepared = original.prepareEmployeeEnrollment(request, definition, true)
                    .valueOrFail("$role full employee preparation")
                val enrollment = original.approveEmployeeEnrollment(prepared, HEAD_LOGIN, HEAD_PASSWORD.toCharArray())
                    .valueOrFail("$role fresh named-head employee approval")
                check(enrollment.provisionAccount(request, definition, CLIENT_PASSWORD.toCharArray())
                    is LocalCredentialAdministrationResult.Applied) { "$role original employee enrollment did not apply" }
                exchange.putText("$role.actor", actor.value)
                actors[role] = actor
            }
            host = DesktopOriginalRpcHostOwner.retainExisting(master.factory.owner, lifetime, original, lan, true) {
                master.os.openSdkIdentityStore(SelfOwnedNativeProfileV1.LIVE)
            }
            host.acquire()
            host.start()
            val endpoint = host.endpoint()
            if (java.lang.Boolean.getBoolean("ukkera.lan.profile")) {
                // Private diagnostic artifact only. This observes the existing original
                // requests; it changes no deadline, authority, fixture or expected result.
                val recording = Recording(Configuration.getConfiguration("profile")).apply {
                    maxSize = 64L * 1024 * 1024
                    start()
                }
                lifetime.launch(Dispatchers.IO) {
                    try {
                        repeat(12) {
                            delay(5_000)
                            recording.dump(root.resolve("master-authentication-profile.jfr"))
                        }
                    } finally { recording.close() }
                }
            }
            exchange.putText("master.endpoint", endpoint.port.toString())
            exchange.await("client1.authenticated")
            exchange.await("client2.authenticated")
            exchange.putText("simultaneous.reads", "begin")
            exchange.await("client1.observed")
            exchange.await("client2.observed")
            exchange.putText("master.observed", "genuine-admission-two-current-distinct-actors-typed-read")
            if (java.lang.Boolean.getBoolean(LanGenuineStockSupport.ENABLE_PROPERTY)) {
                master.withOwner { endpoint, credentials ->
                    LanGenuineStockSupport.prepareMaster(exchange, endpoint, credentials,
                        checkNotNull(original.originalBusinessPublications()), master.coordinates.scope, actors,
                        root.resolve("master"), HEAD_LOGIN) { HEAD_PASSWORD.toCharArray() }
                    val profile = if (java.lang.Boolean.getBoolean("ukkera.lan.stockProfile"))
                        Recording(Configuration.getConfiguration("profile")).apply { maxSize = 32L * 1024 * 1024; start() }
                        else null
                    val sample = profile?.let { recording ->
                        val path = Files.createTempFile(Path.of("/var/tmp"), "pos-lan-stock-profile-", ".jfr")
                        println("Optional stock CPU observation retained at $path")
                        lifetime.launch(Dispatchers.IO) {
                            repeat(48) { delay(5_000); recording.dump(path) }
                        }
                    }
                    try {
                        LanGenuineStockSupport.runMaster(exchange, endpoint, credentials, root.resolve("master"), master.coordinates.scope, actors)
                    } finally {
                        sample?.cancelAndJoin()
                        profile?.close()
                    }
                }
                exchange.putText("clients.release", "close")
                exchange.await("client1.closed"); exchange.await("client2.closed")
                return
            }
            if (java.lang.Boolean.getBoolean(LanGenuineBusinessSupport.ENABLE_PROPERTY)) {
                master.withOwner { endpoint, credentials ->
                    LanGenuineBusinessSupport.prepareMaster(exchange, endpoint, credentials, actors, root.resolve("master"))
                }
                LanGenuineBusinessSupport.runMaster(exchange, root.resolve("master"), master.coordinates.scope, actors)
                exchange.putText("clients.release", "close")
                exchange.await("client1.closed")
                exchange.await("client2.closed")
                return
            }
            master.withOwner { endpoint, credentials ->
                for (actor in actors.values) master.cash(endpoint, credentials, LocalCashRequestV1.ConfigureCustodian(actor))
            }
            if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
                master.withOwner { endpoint, credentials -> LanGenuinePaymentSupport.prepareMaster(exchange, endpoint, credentials, actors) }
            exchange.putText("clients.gateway-phase", "fresh-sign-in-native-encrypted-actor-journals")
            if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
                LanGenuinePaymentSupport.runMaster(exchange, root.resolve("master"), master.coordinates.scope, actors)
            exchange.await("client1.gateway-ready")
            exchange.await("client2.gateway-ready")
            // Self-authored, NOT independent: optional three-commit stability observation
            // for frozen LAN-A02/C05/R08 continuity. Both subscriptions stay mounted; the
            // next commit waits for BOTH automatic observations. Default is unchanged.
            repeat(stabilityRounds()) { round ->
                val suffix = roundSuffix(round)
                val drawer = DomainId.random()
                exchange.putText("master.new-drawer$suffix", drawer.value)
                master.withOwner { endpoint, credentials -> master.cash(endpoint, credentials,
                    LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic LAN shared metadata", Currency.EGP)) }
                exchange.await("client1.live-update$suffix")
                exchange.await("client2.live-update$suffix")
            }
            exchange.putText("clients.release", "close")
            exchange.await("client1.closed")
            exchange.await("client2.closed")
        } catch (failure: Throwable) {
            // Failure-only observation before mandatory cleanup can terminate the JVM.
            // No exception message, credential, native input, path or identity is logged.
            try {
                val locations = failure.stackTrace.filter {
                    it.className.startsWith("com.ukkera.pos.")
                }.take(6).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                System.err.println("Supporting Master failure: ${failure.javaClass.simpleName}; $locations")
            } catch (_: Throwable) { /* Observation cannot replace the original failure. */ }
            throw failure
        } finally {
            withContext(NonCancellable) {
                try { host?.close() } finally { master.close() }
            }
        }
    }

    private suspend fun client(role: String, root: Path, exchange: LanFixtureExchange, lan: OrganizationLan,
        lifetime: CoroutineScope) {
        val scope = exchange.awaitText("master.scope").split('/').let {
            require(it.size == 3); BranchScope(DomainId(it[0]), DomainId(it[1]), DomainId(it[2]))
        }
        val masterSdk = LocalMasterSdkOriginal.decode(exchange.await("master.sdk"))
        val platform = LanLinuxClientTargetPlatform(root.resolve(role))
        val physicalPlatform: DesktopClientTargetPlatform = if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
            LanPaymentPhysicalNativeSupport.platform(platform, root.resolve(role)) else platform
        var target: DesktopClientTargetHandle? = DesktopClientTargetHandle(DesktopDevicePairingTarget.prepareNew(
            physicalPlatform, scope, setOf("POS_CLIENT")))
        var rpc: com.ukkera.pos.rpc.LocalOriginalRpcClientOwner? = null
        var tokens: LocalSessionTokens? = null
        var journals: DesktopPairedDeviceClientCredentials? = null
        var gateway: LocalEndpointBranchGateway? = null
        var phase = "PAIRING"
        try {
            val active = checkNotNull(target)
            val identity = active.acquireSdkIdentity(masterSdk.appId, "Synthetic LAN $role")
            exchange.put("$role.sdk", identity.copyWireBytes())
            val challenge = LocalDevicePairingChallenge.decode(exchange.await("$role.challenge"))
            exchange.put("$role.possession", active.respond(challenge).copyWireBytes())
            val approved = active.importApprovedMaster(LocalDevicePairingMasterTransfer.decode(exchange.await("$role.master-transfer")))
            check(approved.scope == scope && LocalMasterSdkOriginal.same(masterSdk, approved.masterSdkOriginal))
            exchange.putText("$role.imported", approved.targetDeviceId.value)
            val rootOwner = Any()
            val custody = active.acquireCredentialJournalCustody(rootOwner, approved.originalPairingActionId,
                LocalPairedDeviceCredentialJournalAction.ENROLL)
            val acquired = DesktopPairedDeviceClientCredentials.acquireForActorIntents(rootOwner, custody,
                LanProcessJournalSupport.storage(root.resolve(role)))
            check(acquired is DesktopPairedDeviceClientCredentialsResult.Opened) {
                "$role genuine paired native encrypted credentials/allocation journal acquisition unavailable"
            }
            val heldJournals = acquired.credentials
            journals = heldJournals
            val actor = DomainId(exchange.awaitText("$role.actor"))
            val port = exchange.awaitText("master.endpoint").toInt()
            val heldRpc = DesktopOriginalRpcClientOwner.retainRecoverableExisting(lifetime, active, approved.originalPairingActionId, lan) {
                platform.identityStore()
            }
            rpc = heldRpc
            target = null // The retained production RPC owner now owns the actual target close.
            heldRpc.acquire()
            heldRpc.connect(RpcEndpoint("10.203.16.1", port))
            val auth = heldRpc.authenticationService()
            val login = if (role == "client1") "cashier.a" else "cashier.b"
            phase = "SIGN_IN"
            val result = auth.signIn(auth.connectionId, LocalLoginIdentifier(login), CLIENT_PASSWORD.toCharArray(), false)
            check(result is LocalAuthenticationResult.Authenticated) { "$role protected RPC authentication: $result" }
            tokens = result.tokens
            val credentials = LocalGatewayCredentials(auth.connectionId, result.tokens.access)
            phase = "CURRENT"
            val current = auth.current(credentials)
            check(current is LocalSessionViewResult.Current && current.view.actorId == actor) {
                "$role current authenticated actor did not match its original employee enrollment"
            }
            val directory = heldRpc.directoryClient(LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
            exchange.putText("$role.authenticated", "current-distinct-actor")
            phase = "WAIT_READS"
            exchange.await("simultaneous.reads")
            val selection = LocalCashReadCodec.request(LocalCashQueryV1.OwnCash)
            // Same public typed selection on two independently authenticated connections.
            // This supporting slice has no manufactured opening float or cashier assignment.
            // Preserve its actual NotConfigured result; it cannot pass the full F-BASE cases.
            repeat(4) {
                phase = "TYPED_READ"
                val reply = directory.read(credentials, selection)
                check(reply is ReadReply.Data) { "$role protected typed read unavailable: ${readReason(reply)}" }
                val cash = LocalCashReadCodec.decode(reply.payload)
                check(cash is LocalCashReadResultV1.NotConfigured && cash.custodianId == actor) {
                    "$role typed OwnCash did not bind its actual current actor"
                }
                phase = "SNAPSHOT"
                val snapshot = directory.snapshot(credentials, LocalMasterRpcWorkflowProfile.LAN_BUSINESS, selection)
                check(snapshot is LocalMasterChangesReplyV1.Snapshot && snapshot.cursor.actorId == actor &&
                    snapshot.cursor.deviceId == approved.targetDeviceId && snapshot.cursor.scope == scope) {
                    "$role authorized snapshot unavailable: ${changesReason(snapshot)}"
                }
            }
            exchange.putText("$role.observed", "four-protected-reads-and-snapshots-own-native-actor-device")
            if (java.lang.Boolean.getBoolean(LanGenuineStockSupport.ENABLE_PROPERTY)) {
                phase = "STOCK_SUPPORTING"
                LanGenuineStockSupport.runClient(role, root, exchange, directory, credentials, scope, actor)
                check(auth.logout(credentials) == LocalSignOutResult.Revoked)
                tokens?.destroy(); tokens = null
                exchange.await("clients.release")
                return
            }
            if (java.lang.Boolean.getBoolean(LanGenuineBusinessSupport.ENABLE_PROPERTY)) {
                phase = "BUSINESS_SUPPORTING"
                LanGenuineBusinessSupport.runClient(role, root, exchange, directory, credentials, scope, actor)
                check(auth.logout(credentials) == LocalSignOutResult.Revoked)
                tokens?.destroy(); tokens = null
                exchange.await("clients.release")
                return
            }
            phase = "WAIT_GATEWAY"
            exchange.await("clients.gateway-phase")
            phase = "LOGOUT"
            check(auth.logout(credentials) == LocalSignOutResult.Revoked) { "$role logout did not revoke" }
            tokens?.destroy(); tokens = null
            val intents = checkNotNull(heldJournals.actorIntentSource(heldRpc)) { "$role paired actor journal source unavailable" }
            val connection = LocalRemoteBusinessClientConnection(directory, directory.connectionId,
                LocalClientDeviceIdentity(scope, approved.targetDeviceId),
                LocalBranchDisplayLabels(scope, "Synthetic LAN Company", "Synthetic LAN Branch"),
                retailPhysicalBinding = if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
                    checkNotNull(heldJournals.remotePhysicalBinding(heldRpc, directory)) { "Actual paired remote physical binding unavailable" }
                else null) {
                val directoryCurrent = directory.isCurrent()
                val journalsCurrent = heldJournals.isCurrent()
                if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY) &&
                    (!directoryCurrent || !journalsCurrent))
                    println("Payment connection observation: directory=$directoryCurrent; journals=$journalsCurrent")
                directoryCurrent && journalsCurrent
            }
            val heldGateway = LocalEndpointBranchGateway.remoteBusiness(
                MutableStateFlow<LocalRemoteBusinessClientConnection?>(connection), heldJournals.credentials, intents)
            gateway = heldGateway
            phase = "GATEWAY_SIGN_IN"
            val signedIn = heldGateway.signIn(login, CLIENT_PASSWORD.toCharArray(), false)
            check(signedIn is SignInResult.Authenticated) { "$role actual remote UI sign-in: $signedIn" }
            var session = signedIn.session
            phase = "GATEWAY_CURRENT"
            check(session.isCurrent() && session.current() is LocalSessionCheckResult.Current) {
                "$role native-journal remote UI session is not current"
            }
            if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
                session = LanGenuinePaymentSupport.runClient(role, root, exchange, session) {
                    val reopened = heldGateway.signIn(login, CLIENT_PASSWORD.toCharArray(), true)
                    check(reopened is SignInResult.Authenticated) { "Fresh protected payment recovery sign-in unavailable" }
                    reopened.session
                }
            val live = session.workflows as? LocalWorkflowLiveViews
                ?: error("$role production remote workflow has no protected live-view capability")
            // One subscription remains open while the actual Master commits. The fixture never
            // presses Refresh or writes a Client projection/cursor. Production owns each poll,
            // coherent snapshot, protected native replacement and UI delivery.
            phase = "LIVE_VIEW"
            coroutineScope {
                val observation = async {
                    var ready = false
                    var observedRounds = 0
                    val rounds = stabilityRounds()
                    live.readUpdates(LocalWorkflowQuery(LocalWorkflow.CASH)).collect { update ->
                        when (update) {
                            is LocalWorkflowReadUpdate.Available -> {
                                if (!ready) { exchange.putText("$role.gateway-ready", "encrypted-coherent-initial-page"); ready = true }
                                val suffix = roundSuffix(observedRounds)
                                val expected = exchange.read("master.new-drawer$suffix")?.toString(Charsets.UTF_8)
                                if (expected != null && update.page.records.any {
                                    it.reference.kind == LocalRecordKind.DRAWER && it.reference.id.value == expected
                                }) {
                                    exchange.putText("$role.live-update$suffix", "actual-master-drawer-visible-through-production-ui-subscription")
                                    observedRounds++
                                    if (observedRounds == rounds) throw LanObservationComplete()
                                }
                            }
                            is LocalWorkflowReadUpdate.Unavailable -> error("$role protected live view unavailable: ${update.problem}")
                            LocalWorkflowReadUpdate.RestartRequired -> error("$role root live query unexpectedly needs restart")
                        }
                    }
                }
                try { observation.await() } catch (_: LanObservationComplete) { }
            }
            phase = "WAIT_RELEASE"
            exchange.await("clients.release")
            phase = "GATEWAY_LOGOUT"
            check(session.logout() == LocalSessionEndResult.REVOKED) { "$role UI logout did not revoke" }
        } catch (failure: Throwable) {
            // Static phase/class/source observation before mandatory cleanup can fail.
            try {
                val locations = failure.stackTrace.filter { it.className.startsWith("com.ukkera.pos.") }
                    .take(6).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                System.err.println("Supporting Client failure: $phase; ${failure.javaClass.simpleName}; $locations")
            } catch (_: Throwable) { /* Observation cannot replace the original failure. */ }
            throw failure
        } finally {
            tokens?.destroy()
            withContext(NonCancellable) {
                try { gateway?.closeAndAwait() } finally {
                    try { journals?.close() } finally { try { rpc?.close() } finally { target?.close() } }
                }
            }
        }
    }

    private fun readReason(reply: ReadReply): String = when (reply) {
        is ReadReply.Data -> "DATA"
        is ReadReply.Unavailable -> reply.reason.name
    }
    private fun changesReason(reply: LocalMasterChangesReplyV1): String = when (reply) {
        is LocalMasterChangesReplyV1.Snapshot -> "SNAPSHOT"
        is LocalMasterChangesReplyV1.Unchanged -> "UNCHANGED"
        is LocalMasterChangesReplyV1.Unavailable -> reply.reason.name
    }
    private fun mutationReason(reply: MutationReply): String = when (reply) {
        is MutationReply.Recorded -> "RECORDED:${reply.committed}:${reply.code}"
        is MutationReply.Deferred -> "DEFERRED:${reply.reason}"
        is MutationReply.IdentityConflict -> "IDENTITY_CONFLICT"
    }

    private const val HEAD_LOGIN = "head.one"
    private const val HEAD_PASSWORD = "synthetic-head-pass-2026"
    private const val INSTALLER_PASSWORD = "synthetic-installer-pass-2026"
    private const val OWNER_PASSWORD = "synthetic-owner-pass-2026"
    private const val CLIENT_PASSWORD = "synthetic-cashier-pass-2026"
    private fun stabilityRounds(): Int = System.getProperty("ukkera.lan.stabilityRounds", "1").toInt().also {
        require(it in 1..3)
    }
    private fun roundSuffix(round: Int): String = if (round == 0) "" else ".round${round + 1}"
    private class LanObservationComplete : java.util.concurrent.CancellationException()
    private val CLIENT_ACTIONS = setOf(SecurityAction.CASH_READ, SecurityAction.CATALOG_READ,
        SecurityAction.INVENTORY_QUANTITY_READ) +
        (if (java.lang.Boolean.getBoolean(LanGenuineBusinessSupport.ENABLE_PROPERTY)) LanGenuineBusinessSupport.clientActions else emptySet()) +
        (if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY)) LanGenuinePaymentSupport.clientActions else emptySet()) +
        (if (java.lang.Boolean.getBoolean(LanGenuineStockSupport.ENABLE_PROPERTY)) LanGenuineStockSupport.clientActions else emptySet())
    private val MASTER_ACTIONS = CLIENT_ACTIONS + setOf(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION,
        SecurityAction.DEVICE_ADMINISTRATION, SecurityAction.OPERATION_RESULT_RECOVERY,
        SecurityAction.SETUP_RESULT_READ, SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE,
        SecurityAction.SETUP_BUSINESS_GO_LIVE, SecurityAction.SETUP_CONFIGURATION_SETTLE,
        SecurityAction.SETUP_CATALOG_IMPORT_SETTLE, SecurityAction.SETUP_OPENING_SETTLE,
        SecurityAction.SETUP_CHECKPOINTS_READ, SecurityAction.CATALOG_ACTIVATE,
        SecurityAction.WAREHOUSE_CONFIGURE, SecurityAction.OPENING_INVENTORY_APPROVE,
        SecurityAction.INVENTORY_ADJUST, SecurityAction.CASH_CONFIGURE, SecurityAction.CASH_OPEN) +
        (if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY)) LanGenuinePaymentSupport.masterActions else emptySet())

    internal class LanGenuineMaster(private val root: Path) {
        val os = LanLinuxOriginalOs(root)
        private val fixtureOriginals = LanPersistentTestFiles(root.resolve("fixture-original-inputs"))
        val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
        lateinit var coordinates: SelfOwnedAllocationCoordinatesV1
            private set
        lateinit var service: AndroidSelfOwnedMasterService
            private set
        private var opened: AndroidSelfOwnedMasterService? = null

        fun scopeIds(): String = coordinates.scope.let {
            "${it.organizationId.value}/${it.companyId.value}/${it.branchId.value}"
        }

        fun account(actor: DomainId, login: String, actions: Set<SecurityAction>, devices: Set<DomainId>): LocalAccountProvisioning {
            val grants = actions.map { action -> PermissionGrant(Permission(action,
                if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY) && action == SecurityAction.SUPPLIER_ADMINISTRATION ||
                    java.lang.Boolean.getBoolean(LanGenuineStockSupport.ENABLE_PROPERTY) && action in LanGenuineStockSupport.masterAdminActions)
                    PermissionVerb.ADMINISTER else PermissionVerb.PERFORM),
                coordinates.scope, GrantEffect.ALLOW, PermissionLimits(Money(Currency.EGP, 1_000_000), 10_000),
                OwnerAccess.OWN_ONLY, actor) }
            return LocalAccountProvisioning(actor, LocalLoginIdentifier(login), ActivatedLocalPolicy(coordinates.scope,
                actor, Revision(0), grants, LocalSessionPolicy(1), ThrottlePolicy(), approvalPolicy = null),
                LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), devices)
        }

        suspend fun installAndOpen() {
            val founding = LocalCompanyFoundingFactsV1(SelfOwnedCompanyFactsV1("Synthetic LAN Company", "Synthetic Installer",
                "installer.one", SelfOwnedKeyProtectionV1.OS_ISOLATED), "Synthetic Head", HEAD_LOGIN,
                LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, LocalCompanyHeadPurposeV1.entries.toSet())
            val company = provisioner { p -> p.completeNewCompanyAndHead(p.prepareNewCompanyAndHead(founding),
                INSTALLER_PASSWORD.toCharArray(), HEAD_PASSWORD.toCharArray()) }
            provisioner { p -> p.issueOriginalPreparation(p.prepareOriginalBranch("Synthetic LAN Branch", company.rootFingerprint),
                "installer.one", INSTALLER_PASSWORD.toCharArray()) }
            val prepared = DesktopSelfOwnedInstallationOwner.prepare(os, SelfOwnedNativeProfileV1.LIVE)
            check(prepared is SelfOwnedNativeOpenResult.Opened) { "Native original preparation: $prepared" }
            coordinates = prepared.context.materialAtOpen.coordinates
            prepared.context.close()
            val original = DesktopSelfOwnedInstallationOwner.open(os, SelfOwnedNativeProfileV1.LIVE)
            check(original is SelfOwnedNativeOpenResult.Opened) { "Native prepared original open: $original" }
            val installing = factory.forOriginalInstallation(original.context)
            check(installing is AndroidSelfOwnedMasterOpenResult.Opened) { "Original installation composition: $installing" }
            opened = installing.service
            val definition = installing.service.prepareOriginalDefinition(account(coordinates.firstActorId, "owner.one",
                MASTER_ACTIONS, setOf(coordinates.deviceId)), Revision(1))
            var outcome = installing.service.installOriginal(definition.definition, HEAD_LOGIN, HEAD_PASSWORD.toCharArray(),
                OWNER_PASSWORD.toCharArray())
            if (outcome == LocalMasterInstallationOutcome.AcknowledgementPending)
                outcome = installing.service.resumeOriginalAcknowledgement()
            check(outcome is LocalMasterInstallationOutcome.Installed) { "Genuine installation/native ACK: $outcome" }
            check(installing.service.quiesceAndClose()) { "Installation owner did not confirm close" }
            opened = null
            val operational = DesktopSelfOwnedInstallationOwner.open(os, SelfOwnedNativeProfileV1.LIVE)
            check(operational is SelfOwnedNativeOpenResult.Opened) { "Operational original reopen: $operational" }
            val publications = operational.context.readOriginalBusinessPublications().valueOrFail("Original native publications")
            val physical = if (java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
                checkNotNull(DesktopOriginalPhysicalJournalExecutionGate.create(factory.owner,
                    DesktopClientJournalStorages.masterService(DesktopMasterClientJournalCustody.of(os, SelfOwnedNativeProfileV1.LIVE))))
                else null
            val registry = LocalBranchCommerceRegistry(NativeCompanyPublicationSignatureVerifier(publications.trust),
                UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
                retailParticipants = physical ?: UnavailableRetailJournalParticipantsSource,
                nativePublications = publications)
            val composed = factory.fromOriginalContext(operational.context, registry.commands, registry.reads,
                physical ?: UnavailablePhysicalRecordingExecutionGate, registry.nativeOriginalImports, registry.stockProjection, registry.historyProducer)
            check(composed is AndroidSelfOwnedMasterOpenResult.Opened) { "Operational original composition: $composed" }
            service = composed.service
            opened = service
            if (physical != null) check(physical.acquireRemoteOriginalOwner(service.originalLocalClient(),
                DistributedPhysicalNativeAction.ENROLL_ORIGINAL)) { "Actual original Master physical catalogue enrollment unavailable" }
        }

        suspend fun <T> withOwner(block: suspend (LocalMasterClient, LocalGatewayCredentials) -> T): T {
            val endpoint = checkNotNull(service.client)
            val connection = checkNotNull(service.localConnectionId)
            val auth = endpoint.signIn(connection, LocalLoginIdentifier("owner.one"), OWNER_PASSWORD.toCharArray())
            check(auth is LocalAuthenticationResult.Authenticated) { "Original owner authentication: $auth" }
            val credentials = LocalGatewayCredentials(connection, auth.tokens.access)
            try { return block(endpoint, credentials) }
            finally { try { check(endpoint.logout(credentials) == LocalSignOutResult.Revoked) }
                finally { auth.tokens.destroy() } }
        }

        suspend fun cash(endpoint: LocalMasterClient, credentials: LocalGatewayCredentials, request: LocalCashRequestV1) {
            val original = MutationRequest(OperationId.random(), LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(request))
            retainFixtureOriginal(original)
            val reply = endpoint.execute(credentials, original)
            check(reply is MutationReply.Recorded && reply.committed) {
                "Original synthetic cash-configuration command: ${mutationReason(reply)}"
            }
        }

        private fun retainFixtureOriginal(request: MutationRequest) {
            // Executor input retention only, never a fabricated production Client journal.
            // No retry allocates another ID; preserve the exact first input before dispatch.
            val payload = request.payload.copyBytes()
            val canonical = try { ByteArrayOutputStream().also { out -> DataOutputStream(out).use {
                it.writeUTF(request.operationId.value); it.writeUTF(request.command.name); it.writeInt(request.command.version)
                it.writeInt(payload.size); it.write(payload)
            } }.toByteArray() } finally { payload.fill(0) }
            try { fixtureOriginals.locked { fixtureOriginals.create(fixtureOriginals.name(request.operationId.value), canonical) } }
            finally { canonical.fill(0) }
        }

        suspend fun observeAndAcknowledgeSavedSetup(exchange: LanFixtureExchange) = withOwner { endpoint, credentials ->
            val body = LocalSetupResultCodecV1.encodeRequest(coordinates.installationId)
            val before = endpoint.read(credentials, ReadRequest(LocalSetupResultProfileV1.READ, body))
            check(before is ReadReply.Data) { "Actual saved setup protected read unavailable" }
            val initial = LocalSetupResultCodecV1.decodeProjection(before.payload)
            check(initial.acknowledgement == null && initial.goLive == null)
            val original = MutationRequest(OperationId(DomainId.random().value), LocalSetupResultProfileV1.ACKNOWLEDGE, body)
            retainFixtureOriginal(original)
            val acknowledged = endpoint.execute(credentials, original)
            exchange.putText("master.setup-ack-dispatch", mutationReason(acknowledged))
            check(acknowledged is MutationReply.Recorded && acknowledged.committed) {
                "Explicit saved setup delivery ACK: ${mutationReason(acknowledged)}"
            }
            val acknowledgement = LocalSetupResultCodecV1.decodeOutcome(acknowledged.result)
            check(acknowledgement.status == LocalSetupResultStatusV1.ACKNOWLEDGED &&
                acknowledgement.code == LocalSetupResultProfileV1.ACKNOWLEDGED_CODE &&
                acknowledgement.acknowledgement?.operationId == original.operationId) {
                "Explicit saved setup delivery ACK typed result did not match its original"
            }
            val replay = endpoint.execute(credentials, original)
            check(replay is MutationReply.Recorded && replay.replayed && replay.operationId == acknowledged.operationId &&
                replay.result == acknowledged.result) { "Original setup delivery ACK did not recover the same retained result" }
            val after = endpoint.read(credentials, ReadRequest(LocalSetupResultProfileV1.READ, body))
            check(after is ReadReply.Data)
            val observed = LocalSetupResultCodecV1.decodeProjection(after.payload)
            check(observed.acknowledgement?.operationId == original.operationId && observed.goLive == null)
            exchange.putText("master.setup-ack", "protected-read-explicit-ack-original-replay;no-implicit-go-live")
            exchange.putText("master.setup-prerequisites", observed.prerequisites.joinToString("\n") {
                "${it.code}:${it.settled}:${it.binding}"
            })
        }

        private fun <T> provisioner(block: (SelfOwnedDesktopProvisionerV2) -> T): T {
            val platform = DesktopSelfOwnedOriginalPlatform(os)
            val work = AndroidOwnedInstallationWork(checkNotNull(AndroidProvisionerProcessLease.acquire(SelfOwnedNativeProfileV1.LIVE)),
                platform.process)
            try {
                val environment = platform.captureEnvironment(work, SelfOwnedNativeProfileV1.LIVE, preparation = true)
                val registry = platform.provisionerRegistry(environment, work)
                registry.openNative()
                try { return block(SelfOwnedDesktopProvisionerV2(environment, registry as SelfOwnedProvisionerRegistryStoreV2,
                    platform.provisionerKeys(environment, registry, work), work)) }
                finally { registry.closeNative() }
            } finally { runBlocking { withTimeout(30_000L) { work.requestClose { }.await() } } }
        }

        suspend fun close() { check(opened?.quiesceAndClose() != false); opened = null }
    }
}

/** Private immutable process mailbox. Atomicity uses the same real file lock in all JVMs. */
internal class LanFixtureExchange(root: Path, private val waitMillis: Long = 180_000L) {
    init { require(waitMillis in 180_000L..600_000L) }
    private val files = LanPersistentTestFiles(root)
    fun put(label: String, bytes: ByteArray) = files.locked { files.create(files.name(label), bytes) }
    fun putText(label: String, text: String) = put(label, text.toByteArray(Charsets.UTF_8))
    fun read(label: String): ByteArray? = files.locked { files.read(files.name(label), 65_536) }
    suspend fun await(label: String): ByteArray = withTimeout(waitMillis) {
        while (true) {
            read(label)?.let { return@withTimeout it }
            delay(50)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }
    suspend fun awaitText(label: String): String = await(label).toString(Charsets.UTF_8)
}

private fun <T> LocalCompanyAuthorityResult<T>.valueOrFail(stage: String): T = when (this) {
    is LocalCompanyAuthorityResult.Value -> value
    is LocalCompanyAuthorityResult.Unavailable -> error("$stage unavailable: $reason")
}
