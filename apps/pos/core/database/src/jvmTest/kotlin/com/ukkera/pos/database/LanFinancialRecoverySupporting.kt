package com.ukkera.pos.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.setup.DesktopSetupStorages
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.setup.*
import com.ukkera.pos.rpc.DesktopOriginalRpcClientOwner
import com.ukkera.pos.rpc.DesktopOriginalRpcHostOwner
import com.ukkera.pos.rpc.LocalOriginalRpcClientOwner
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcExecutionEvidence
import dev.p2pkit.rpc.RpcFailure
import dev.p2pkit.rpc.RpcFailureKind
import dev.p2pkit.rpc.RpcFailurePhase
import dev.p2pkit.transport.lan.OrganizationLan
import java.net.NetworkInterface
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*

/**
 * Self-authored, NOT independent. LAN-FPR v1.0.0, frozen in LanFinancialRecoverySupporting.md
 * before first execution. Disposable Linux namespaces; every application stage is a fresh JVM
 * on the SAME original role root. Client1 is SIGKILLed at a durable unresolved boundary; the
 * Master restarts existing-only. Supporting #113 evidence only, not a frozen-case pass.
 */
internal object LanFinancialRecoverySupportingExecutor {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        check(System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true")
        require(args.isEmpty())
        // The physical spool rejects group/other-writable ancestors: native root under user.home.
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.home")), "pos-lan-fpr-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val roles = listOf("master", "client1")
        roles.forEach { LanProcessNativeSupport.privateDirectory(root.resolve(it)) }
        val exchange = LanFixtureExchange(root.resolve("exchange"))
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("ukkera.test.runtime.classpath") ?: System.getProperty("java.class.path")
        val keepers = linkedMapOf<String, Process>()
        val workers = linkedMapOf<String, Process>()
        val suffix = ProcessHandle.current().pid().toString().takeLast(5)
        val namespaceRoles = roles + "switch"
        val hostEnds = roles.indices.map { "fs$it$suffix" }
        val applicationEnds = roles.indices.map { "fa$it$suffix" }
        val created = mutableListOf<String>()
        println("LAN-FPR originals/logs retained at $root; Linux software OS/key stand-ins")

        fun command(vararg args: String) {
            val child = ProcessBuilder(*args).redirectErrorStream(true).start()
            if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); error("Network command timed out") }
            check(child.exitValue() == 0) { "Disposable network command failed: ${child.inputStream.bufferedReader().readText()}" }
        }
        fun network(role: String, vararg args: String) =
            command("nsenter", "-t", checkNotNull(keepers[role]).pid().toString(), "-n", "ip", *args)
        fun start(role: String, stage: String): Process {
            val index = roles.indexOf(role)
            val prefix = if (stage == "keeper") listOf("unshare", "--net", "--") else
                listOf("nsenter", "-t", checkNotNull(keepers[role]).pid().toString(), "-n", "--")
            val iface = if (stage == "keeper") "unused" else applicationEnds[index]
            return ProcessBuilder(prefix + listOf(java, "-Xmx384m", "-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=/var/tmp",
                "-Dukkera.lan.verifierDiagnostics=true", "-D${LanGenuinePaymentSupport.ENABLE_PROPERTY}=true", "-cp", classpath,
                LanFinancialRecoverySupportingEndpoint::class.java.name, role, stage, root.toString(), iface, "10.203.19.${index + 1}"))
                .redirectErrorStream(true).redirectOutput(root.resolve("$role.$stage.log").toFile()).start()
        }
        val killed = mutableSetOf<String>()
        fun healthy() {
            for ((name, child) in workers) if (name !in killed && !child.isAlive) check(child.exitValue() == 0) {
                "$name failed with ${child.exitValue()}; originals/logs retained at $root" }
            check(keepers.values.all(Process::isAlive)) { "Namespace custody failed; see $root" }
        }
        suspend fun await(label: String) = withTimeout(600_000L) {
            while (exchange.read(label) == null) { healthy(); delay(100) }
        }
        suspend fun closed(role: String, stage: String) {
            await("$role.$stage.closed")
            val child = checkNotNull(workers["$role.$stage"])
            withTimeout(30_000L) { while (child.isAlive) delay(100) }
            check(child.exitValue() == 0)
        }
        try {
            namespaceRoles.forEach { keepers[it] = start(it, "keeper") }
            withTimeout(30_000L) {
                while (namespaceRoles.any { exchange.read("$it.namespace") == null }) { healthy(); delay(50) }
            }
            network("switch", "link", "add", "br0", "type", "bridge")
            for ((i, role) in roles.withIndex()) {
                command("ip", "link", "add", hostEnds[i], "type", "veth", "peer", "name", applicationEnds[i])
                created += hostEnds[i]
                command("ip", "link", "set", hostEnds[i], "netns", keepers.getValue("switch").pid().toString())
                command("ip", "link", "set", applicationEnds[i], "netns", keepers.getValue(role).pid().toString())
                network("switch", "link", "set", hostEnds[i], "master", "br0")
                network("switch", "link", "set", hostEnds[i], "up")
                network(role, "addr", "add", "10.203.19.${i + 1}/24", "dev", applicationEnds[i])
                network(role, "link", "set", applicationEnds[i], "up")
                network(role, "link", "set", "lo", "up")
            }
            network("switch", "link", "set", "br0", "up")
            network("switch", "link", "set", "lo", "up")
            withTimeout(1_500_000L) {
                roles.forEach { workers["$it.initial"] = start(it, "initial") }
                await("client1.initial.killable")
                // Process interruption 1: SIGKILL (Process.destroyForcibly on Linux). No graceful
                // close, native close or delivery acknowledgement runs in the Client JVM.
                val victim = workers.getValue("client1.initial")
                killed += "client1.initial"
                victim.destroyForcibly()
                check(victim.waitFor(30, TimeUnit.SECONDS)) { "SIGKILLed Client1 did not exit; see $root" }
                check(victim.exitValue() == 137) { "Client1 interruption exit ${victim.exitValue()}" }
                exchange.putText("client1.initial.killed", "sigkill-exit-${victim.exitValue()}")
                workers["client1.client-restart"] = start("client1", "client-restart")
                closed("client1", "client-restart")
                // Process interruption 2: graceful Master exit after the Client closed.
                exchange.putText("master.initial.release", "close-before-existing-only-restart")
                closed("master", "initial")
                roles.forEach { workers["$it.master-restart"] = start(it, "master-restart") }
                closed("client1", "master-restart"); closed("master", "master-restart")
            }
            exchange.putText("executor.completed", "LAN-FPR-v1.0.0-supporting-only;whole-frozen-cases-NOT-RUN")
            println("LAN-FPR: unresolved supplier original survived Client SIGKILL and Master restart; one resolved payout verified")
        } finally {
            workers.values.forEach { if (it.isAlive) it.destroyForcibly() }
            workers.values.forEach { it.waitFor(5, TimeUnit.SECONDS) }
            if (keepers.values.all(Process::isAlive)) runCatching { exchange.putText("namespaces.release", "close") }
            keepers.values.forEach { if (!it.waitFor(5, TimeUnit.SECONDS)) it.destroyForcibly() }
            keepers.values.forEach { it.waitFor(5, TimeUnit.SECONDS) }
            for (name in created) {
                val child = ProcessBuilder("ip", "link", "del", name).redirectErrorStream(true).start()
                if (!child.waitFor(5, TimeUnit.SECONDS)) child.destroyForcibly()
            }
            for (log in Files.list(root).use { files -> files.filter { it.fileName.toString().endsWith(".log") }.sorted().toList() })
                println("--- ${log.fileName}\n${Files.readString(log).takeLast(12_000)}")
            println("LAN-FPR native files, generated test keys and all evidence remain at $root")
        }
    }
}

internal object LanFinancialRecoverySupportingEndpoint {
    private const val HEAD_LOGIN = "head.one"
    private const val HEAD_PASSWORD = "synthetic-head-pass-2026"
    private const val OWNER_PASSWORD = "synthetic-owner-pass-2026"
    private const val CLIENT_PASSWORD = "synthetic-fpr-client-2026"
    private const val LOGIN = "cashier.a"
    private const val SUBNET = "10.203.19"
    private val profile = LocalMasterRpcWorkflowProfile.LAN_BUSINESS
    private val opening = Money(Currency.EGP, 100_000)
    private val payout = Money(Currency.EGP, 22_800)
    private val finalCash = Money(Currency.EGP, 77_200)

    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        require(args.size == 5)
        val (role, stage, directory, iface, address) = args
        val root = Path.of(directory)
        val exchange = LanFixtureExchange(root.resolve("exchange"), 600_000L)
        if (stage == "keeper") {
            exchange.putText("$role.namespace", ProcessHandle.current().pid().toString())
            while (exchange.read("namespaces.release") == null) delay(100)
            return@runBlocking
        }
        check(java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(900_000L) {
                val network = checkNotNull(NetworkInterface.getByName(iface)) { "Assigned isolated interface is missing" }
                check(network.isUp && network.supportsMulticast() && !network.isLoopback &&
                    network.inetAddresses.toList().any { it.hostAddress == address }) { "Assigned isolated interface/address is not ready" }
                check(NetworkInterface.getNetworkInterfaces().toList().none { it.isUp && !it.isLoopback && it.name != iface }) {
                    "Isolated application has competing active interfaces" }
                val lan = OrganizationLan(listOf("$SUBNET.0/24"), iface, address)
                if (role == "master") master(root, exchange, lan, scope, stage) else client(root, exchange, lan, scope, stage)
                exchange.putText("$role.$stage.closed", "confirmed-native-close")
            }
        } catch (failure: Throwable) {
            val at = failure.stackTrace.filter { it.className.startsWith("com.ukkera.pos.") }
                .take(6).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
            System.err.println("LAN-FPR failure: $role/$stage ${failure.javaClass.simpleName}: ${failure.message}; $at")
            throw failure
        } finally { scope.cancel() }
    }

    private fun mark(exchange: LanFixtureExchange, label: String, value: String) {
        exchange.putText(label, value)
        println("LAN-FPR marker: $label")
    }

    // ---------------------------------------------------------------- Master

    private suspend fun master(root: Path, exchange: LanFixtureExchange, lan: OrganizationLan,
        lifetime: CoroutineScope, stage: String) {
        val original = LanGenuineProcessEndpoint.LanGenuineMaster(root.resolve("master"))
        var service: AndroidSelfOwnedMasterService? = null
        var host: DesktopOriginalRpcHostOwner? = null
        var finalLedgerScope: BranchScope? = null
        try {
            val coordinates: SelfOwnedAllocationCoordinatesV1
            if (stage == "initial") {
                original.installAndOpen() // Payment property: actual physical gate, ENROLL_ORIGINAL.
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
                // Same production custody as the original install; existing-only physical reopen.
                val physical = checkNotNull(DesktopOriginalPhysicalJournalExecutionGate.create(original.factory.owner,
                    DesktopClientJournalStorages.masterService(DesktopMasterClientJournalCustody.of(original.os,
                        SelfOwnedNativeProfileV1.LIVE)))) { "Existing Master physical gate unavailable" }
                val registry = LocalBranchCommerceRegistry(NativeCompanyPublicationSignatureVerifier(publications.trust),
                    UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
                    retailParticipants = physical, nativePublications = publications)
                val composed = original.factory.fromOriginalContext(opened.context, registry.commands, registry.reads,
                    physical, registry.nativeOriginalImports, registry.stockProjection, registry.historyProducer)
                check(composed is AndroidSelfOwnedMasterOpenResult.Opened) { "Existing operational Master unavailable" }
                service = composed.service
                check(physical.acquireRemoteOriginalOwner(composed.service.originalLocalClient(),
                    DistributedPhysicalNativeAction.REOPEN_EXISTING)) { "Existing Master physical catalogue reopen unavailable" }
            }
            val held = checkNotNull(service)
            check(held.mode.value == LocalMasterLifecycleMode.OPERATIONAL) { "Master mode: ${held.mode.value.name}" }
            val client = held.originalLocalClient()
            val sdk = client.recoverRpcMasterOriginal().value("original Master SDK")
            if (stage == "initial") {
                exchange.put("fpr.master.sdk", sdk.copyWireBytes())
                exchange.putText("fpr.scope", coordinates.scope.let {
                    "${it.organizationId.value}/${it.companyId.value}/${it.branchId.value}" })
                val devices = linkedSetOf(coordinates.deviceId)
                val identity = LocalDevicePairingSdkIdentityV2.decode(exchange.await("fpr.client1.sdk"))
                val action = client.prepareSdkBoundDevicePairing(DomainId.random(), identity).value("pairing preparation")
                exchange.put("fpr.client1.challenge", action.challenge.copyWireBytes())
                val proven = client.proveDevicePairingTarget(action,
                    LocalDevicePairingPossession.decode(exchange.await("fpr.client1.possession"))).value("possession")
                val approval = client.approveDevicePairing(proven, HEAD_LOGIN, HEAD_PASSWORD.toCharArray()).value("approval")
                check(client.admitOriginalDevice(approval) is LocalCredentialAdministrationResult.Applied)
                exchange.put("fpr.client1.transfer", LocalDevicePairingMasterTransfer.capture(approval).copyWireBytes())
                check(devices.add(DomainId(exchange.awaitText("fpr.client1.imported"))))
                val actor = DomainId.random()
                val definition = original.account(actor, LOGIN, setOf(SecurityAction.CASH_READ, SecurityAction.CATALOG_READ,
                    SecurityAction.INVENTORY_QUANTITY_READ) + LanGenuinePaymentSupport.clientActions, devices)
                val request = DomainId.random()
                val prepared = client.prepareEmployeeEnrollment(request, definition, true).value("employee preparation")
                val enrollment = client.approveEmployeeEnrollment(prepared, HEAD_LOGIN, HEAD_PASSWORD.toCharArray()).value("employee approval")
                check(enrollment.provisionAccount(request, definition, CLIENT_PASSWORD.toCharArray()) is LocalCredentialAdministrationResult.Applied)
                exchange.putText("fpr.client1.actor", actor.value)
            } else check(sdk.copyWireBytes().contentEquals(exchange.await("fpr.master.sdk"))) {
                "Master restart changed the original SDK identity"
            }
            host = DesktopOriginalRpcHostOwner.retainExisting(original.factory.owner, lifetime, client, lan, true) {
                original.os.openSdkIdentityStore(SelfOwnedNativeProfileV1.LIVE)
            }
            host.acquire(); host.start()
            mark(exchange, "master.$stage.port", host.endpoint().port.toString())
            if (stage == "initial") {
                exchange.await("client1.initial.authenticated")
                val actor = DomainId(exchange.awaitText("fpr.client1.actor"))
                val drawer = DomainId.random(); val cash = DomainId.random(); val supplier = DomainId.random()
                exchange.putText("fpr.drawer", drawer.value); exchange.putText("fpr.session", cash.value)
                exchange.putText("fpr.supplier", supplier.value)
                withOwner(held) { endpoint, credentials ->
                    original.cash(endpoint, credentials, LocalCashRequestV1.ConfigureCustodian(actor))
                    original.cash(endpoint, credentials, LocalCashRequestV1.ConfigureDrawer(drawer,
                        "Synthetic FPR supplier cash", Currency.EGP))
                    val created = endpoint.execute(credentials, MutationRequest(OperationId.random(), LocalConfigurationProfile.COMMAND,
                        LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ConfigureSupplier(null,
                            LocalSupplierV1(supplier, LocalNamesV1("Synthetic FPR supplier", "مورد اختبار الاسترداد"), true)))))
                    check(created is MutationReply.Recorded && created.committed) { "Real supplier administration failed" }
                }
                mark(exchange, "fpr.provisioned", "custodian-drawer-supplier-after-host-and-client-auth")
                exchange.await("client1.client-restart.closed")
                exchange.await("master.initial.release")
            } else {
                exchange.await("client1.master-restart.finished")
                exchange.await("client1.master-restart.closed")
                finalLedgerScope = coordinates.scope
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
        finalLedgerScope?.let { scope ->
            // Observation revision 1.0.3: the actual native writer owns SQLite exclusion.
            // Inspect only after confirmed native close; keep every ledger assertion intact.
            verifyLedger(exchange, root.resolve("master"), scope, DomainId(exchange.awaitText("fpr.client1.actor")))
            mark(exchange, "fpr.ledger.verified", "one-unknown-original-one-linked-cashpaid-one-movement-cash772")
        }
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

    // ---------------------------------------------------------------- Client1

    private suspend fun client(root: Path, exchange: LanFixtureExchange, lan: OrganizationLan,
        lifetime: CoroutineScope, stage: String) {
        val role = "client1"
        val scope = exchange.awaitText("fpr.scope").split('/').let {
            require(it.size == 3); BranchScope(DomainId(it[0]), DomainId(it[1]), DomainId(it[2])) }
        val platform = LanLinuxClientTargetPlatform(root.resolve(role))
        val physicalPlatform = LanPaymentPhysicalNativeSupport.platform(platform, root.resolve(role))
        var target: DesktopClientTargetHandle? = DesktopClientTargetHandle(if (stage == "initial")
            DesktopDevicePairingTarget.prepareNew(physicalPlatform, scope, setOf("POS_CLIENT"))
            else DesktopDevicePairingTarget.openExisting(physicalPlatform))
        var rpc: LocalOriginalRpcClientOwner? = null
        var journals: DesktopPairedDeviceClientCredentials? = null
        var gateway: LocalEndpointBranchGateway? = null
        try {
            val active = checkNotNull(target)
            val approved: LocalDevicePairingApprovedMaster
            if (stage == "initial") {
                val sdk = LocalMasterSdkOriginal.decode(exchange.await("fpr.master.sdk"))
                val identity = active.acquireSdkIdentity(sdk.appId, "Synthetic FPR $role")
                exchange.put("fpr.$role.sdk", identity.copyWireBytes())
                exchange.put("fpr.$role.possession", active.respond(
                    LocalDevicePairingChallenge.decode(exchange.await("fpr.$role.challenge"))).copyWireBytes())
                approved = active.importApprovedMaster(LocalDevicePairingMasterTransfer.decode(exchange.await("fpr.$role.transfer")))
                check(LocalMasterSdkOriginal.same(approved.masterSdkOriginal, sdk))
                exchange.putText("fpr.$role.action", approved.originalPairingActionId.value)
                exchange.putText("fpr.$role.imported", approved.targetDeviceId.value)
            } else {
                val recovered = active.recoverApprovedMasterData(DomainId(exchange.awaitText("fpr.$role.action")))
                check(recovered is LocalOriginalPairingTargetResult.Value)
                approved = recovered.value
                check(approved.targetDeviceId.value == exchange.awaitText("fpr.$role.imported"))
                check(active.recoverSdkIdentity().copyWireBytes().contentEquals(exchange.await("fpr.$role.sdk"))) {
                    "Client restart changed the original SDK identity" }
                check(LocalMasterSdkOriginal.same(approved.masterSdkOriginal, LocalMasterSdkOriginal.decode(exchange.await("fpr.master.sdk"))))
            }
            val owner = Any()
            val custody = active.acquireCredentialJournalCustody(owner, approved.originalPairingActionId,
                if (stage == "initial") LocalPairedDeviceCredentialJournalAction.ENROLL else LocalPairedDeviceCredentialJournalAction.REOPEN)
            val acquired = DesktopPairedDeviceClientCredentials.acquireForActorIntents(owner, custody,
                LanProcessJournalSupport.storage(root.resolve(role)))
            check(acquired is DesktopPairedDeviceClientCredentialsResult.Opened) { "Original protected Client journals unavailable" }
            val heldJournals = acquired.credentials.also { journals = it }
            val port = exchange.awaitText("master.${if (stage == "master-restart") stage else "initial"}.port").toInt()
            val heldRpc = DesktopOriginalRpcClientOwner.retainRecoverableExisting(lifetime, active, approved.originalPairingActionId, lan) {
                platform.identityStore() }.also { rpc = it }
            target = null
            heldRpc.acquire()
            if (stage == "initial") heldRpc.connect(RpcEndpoint("$SUBNET.1", port))
            else connectWithProductionRetry(heldRpc, RpcEndpoint("$SUBNET.1", port), exchange, stage)
            val actor = DomainId(exchange.awaitText("fpr.$role.actor"))
            val identity = LocalClientIdentity(scope, actor, approved.targetDeviceId)
            val source = checkNotNull(heldJournals.actorIntentSource(heldRpc))
            check(source.current(identity) == null) { "Native reopen restored actor access before current authentication" }
            val auth = heldRpc.authenticationService()
            val previous = if (stage == "initial") null else {
                val retained = credentialSnapshot(heldJournals)
                try {
                    check(retained.sessions.isNotEmpty()) { "Original retained revocation material was lost" }
                    LocalGatewayCredentials(auth.connectionId, retained.sessions.last().access.useBytes(::OpaqueCredential))
                } finally { retained.destroy() }
            }
            val directory = heldRpc.directoryClient(profile)
            val connection = LocalRemoteBusinessClientConnection(directory, directory.connectionId,
                LocalClientDeviceIdentity(scope, approved.targetDeviceId),
                LocalBranchDisplayLabels(scope, "Synthetic Company", "Synthetic Branch"),
                retailPhysicalBinding = checkNotNull(heldJournals.remotePhysicalBinding(heldRpc, directory)) {
                    "Actual paired remote physical binding unavailable" }) { directory.isCurrent() && heldJournals.isCurrent() }
            val heldGateway = LocalEndpointBranchGateway.remoteBusiness(
                MutableStateFlow<LocalRemoteBusinessClientConnection?>(connection), heldJournals.credentials, source).also { gateway = it }
            suspend fun signIn(replaceActive: Boolean): BranchUiSession {
                val result = heldGateway.signIn(LOGIN, CLIENT_PASSWORD.toCharArray(), replaceActive)
                check(result is SignInResult.Authenticated) { "Explicit current UI sign-in failed: $result" }
                check(result.session.isCurrent() && result.session.current() is LocalSessionCheckResult.Current)
                return result.session
            }
            val session = signIn(replaceActive = stage != "initial")
            if (previous != null) try {
                val stale = auth.current(previous)
                check(stale !is LocalSessionViewResult.Current) { "Previous process session remained current after replacement" }
                println("LAN-FPR previous-process session after replacement: ${stale::class.simpleName}")
            } finally { previous.bearer.destroy() }
            mark(exchange, "$role.$stage.authenticated", "fresh-ui-sign-in-and-current")
            when (stage) {
                "initial" -> initialStage(exchange, session, ::signIn)
                "client-restart" -> clientRestartStage(exchange, session)
                else -> masterRestartStage(exchange, session)
            }
        } finally { withContext(NonCancellable) {
            // As LanRecoverySupporting: closing the gateway locks the UI and its protected journal;
            // it does not revoke the retained Master session (the next stage proves replacement).
            try { gateway?.closeAndAwait() } finally {
                try { journals?.close() } finally { try { rpc?.close() } finally { target?.close() } } }
        } }
    }

    private fun fingerprint(p: LocalRetailPhysicalProgress): String = listOf(p.operationId.value, p.state.name,
        p.recordingOperationId?.value, p.originalRecordingOperationId?.value, p.resolutionOperationId?.value,
        p.originalUnknown, p.reviewDisplay?.amount, p.subject.name, p.originalActorId?.value).joinToString("|")

    private suspend fun awaitState(session: BranchUiSession, operation: OperationId,
        state: LocalRetailDurableState): LocalRetailPhysicalProgress = withTimeout(90_000L) {
        var found: LocalRetailPhysicalProgress? = null
        var last: String? = null
        val began = System.nanoTime()
        while (found == null) {
            val read = session.retailPhysical.recoverSupplierCash(operation)
            check(read !is LocalRetailPhysicalReply.Instruction) { "Recovery issued a replacement instruction" }
            // Diagnostic observation only (1.0.1): reply kind and fixed enum, never IDs/payloads.
            val shape = when (read) {
                is LocalRetailPhysicalReply.Progress -> "Progress:${read.value.state.name}"
                is LocalRetailPhysicalReply.Unavailable -> "Unavailable:${read.problem.name}"
                else -> read::class.simpleName ?: "Other"
            }
            if (shape != last) {
                println("LAN-FPR recovery observation: expected=${state.name}; observed=$shape; " +
                    "+${(System.nanoTime() - began) / 1_000_000}ms")
                last = shape
            }
            if (read is LocalRetailPhysicalReply.Progress && read.value.state == state) found = read.value else delay(100)
        }
        checkNotNull(found)
    }

    private suspend fun staged(session: BranchUiSession, request: LocalRetailPhysicalRequest): OperationId {
        val prepared = session.retailPhysical.prepare(request)
        check(prepared is LocalRetailPhysicalReply.Prepared) { "Payment preparation failed; original retained: $prepared" }
        val staged = session.retailPhysical.stage(prepared.value)
        check(staged is LocalRetailPhysicalReply.Staged) { "Native intent staging failed: $staged" }
        return staged.value.operationId
    }

    private suspend fun initialStage(exchange: LanFixtureExchange, first: BranchUiSession,
        signIn: suspend (Boolean) -> BranchUiSession) {
        check(exchange.awaitText("fpr.provisioned").isNotEmpty())
        var session = first
        val drawer = DomainId(exchange.awaitText("fpr.drawer"))
        val cash = DomainId(exchange.awaitText("fpr.session"))
        val supplier = DomainId(exchange.awaitText("fpr.supplier"))
        fun value(field: LocalField, data: LocalScalarValue) = LocalScalarInput(field, data)
        fun id(field: LocalField, data: DomainId) = value(field, LocalScalarValue.Identifier(data))
        val openingOperation = OperationId.random()
        exchange.putText("fpr.opening", openingOperation.value)
        val intent = LocalCommandIntent(openingOperation, LocalAction.CASH_OPEN, 1, null, listOf(
            id(LocalField.SESSION_ID, cash),
            value(LocalField.DRAWER_ID, LocalScalarValue.Reference(LocalRecordReference(LocalRecordKind.DRAWER, drawer, Revision(0)))),
            value(LocalField.EXPECTED_CUSTODIAN_REVISION, LocalScalarValue.Integer(0)),
            value(LocalField.BUSINESS_DATE, LocalScalarValue.Date("2026-11-15")), id(LocalField.SOURCE_ID, DomainId.random()),
            value(LocalField.OPENING_FLOAT, LocalScalarValue.Decimal("1000")), id(LocalField.EVIDENCE_ID, DomainId.random()),
            value(LocalField.OCCURRED_AT, LocalScalarValue.Text(UtcInstant(Clock.System.now()).canonicalString()))))
        check(session.workflows.stage(intent) is LocalStageResult.Staged)
        val opened = session.workflows.submit(openingOperation)
        check(opened is LocalOperationResult.Recorded && opened.decision == LocalRecordedDecision.APPLIED) { "CASH_OPEN: $opened" }
        check(session.workflows.acknowledgeRecorded(openingOperation) is LocalAcknowledgementResult.Acknowledged)
        val start = staged(session, LocalRetailPhysicalRequest.SupplierCash(supplier, null, payout, "synthetic.fpr.advance"))
        exchange.putText("fpr.start", start.value)
        val uiLifetime = LocalRetailPhysicalLifetime()
        val instruction = session.retailPhysical.start(start, uiLifetime)
        check(instruction is LocalRetailPhysicalReply.Instruction) { "Actual remote native Start unavailable: $instruction" }
        check(instruction.value.display.amount == payout && instruction.value.display.subject == LocalPaymentSubject.SUPPLIER)
        val original = instruction.value.display.recordingOperationId
        exchange.putText("fpr.original", original.value)
        check(instruction.value.capture(LocalRetailObservation.Unknown(LocalRetailEvidence(DomainId.random(),
            UtcInstant(Clock.System.now())))) is LocalRetailPhysicalCapture.CapturedInMemory)
        // Loss of UI delivery; the native worker independently owns the accepted observation.
        session.close(); uiLifetime.deny()
        session = signIn(true)
        val pending = awaitState(session, original, LocalRetailDurableState.SQL_ORIGINAL_PENDING)
        val recoveredStart = session.retailPhysical.recoverSupplierCash(start)
        check(recoveredStart is LocalRetailPhysicalReply.Progress &&
            recoveredStart.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING) { "Start recovery: $recoveredStart" }
        exchange.putText("fpr.pending.original", fingerprint(pending))
        exchange.putText("fpr.pending.start", fingerprint(recoveredStart.value))
        println("LAN-FPR pre-kill original: ${fingerprint(pending)}")
        mark(exchange, "client1.initial.killable", "sql-original-pending-durable")
        // The executor SIGKILLs this JVM here. Nothing below is expected to run.
        while (true) delay(1_000)
    }

    private suspend fun clientRestartStage(exchange: LanFixtureExchange, session: BranchUiSession) {
        check(exchange.awaitText("client1.initial.killed").startsWith("sigkill"))
        val original = OperationId(exchange.awaitText("fpr.original"))
        val start = OperationId(exchange.awaitText("fpr.start"))
        val pending = awaitState(session, original, LocalRetailDurableState.SQL_ORIGINAL_PENDING)
        check(fingerprint(pending) == exchange.awaitText("fpr.pending.original")) {
            "Recovered original differs after SIGKILL: ${fingerprint(pending)}" }
        val recoveredStart = session.retailPhysical.recoverSupplierCash(start)
        check(recoveredStart is LocalRetailPhysicalReply.Progress &&
            fingerprint(recoveredStart.value) == exchange.awaitText("fpr.pending.start")) { "Start recovery after SIGKILL: $recoveredStart" }
        mark(exchange, "client1.client-restart.recovered", "exact-original-and-start-no-instruction")
        val apply = staged(session, LocalRetailPhysicalRequest.SupplierCashApply(original, LocalRetailObservationPhase.ORIGINAL))
        exchange.putText("fpr.unknown-apply", apply.value)
        val unknown = session.retailPhysical.continueSql(apply)
        check(unknown is LocalRetailPhysicalReply.Progress &&
            unknown.value.state == LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED) { "Unknown apply: $unknown" }
        repeat(3) { check((session.retailPhysical.continueSql(apply) as? LocalRetailPhysicalReply.Progress)?.value == unknown.value) }
        val unresolved = session.retailPhysical.recoverSupplierCash(original)
        check(unresolved is LocalRetailPhysicalReply.Progress &&
            unresolved.value.state == LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED) { "Unresolved original: $unresolved" }
        exchange.putText("fpr.unresolved.original", fingerprint(unresolved.value))
        exchange.putText("fpr.unresolved.apply", fingerprint(unknown.value))
        println("LAN-FPR unresolved before Master restart: ${fingerprint(unresolved.value)}")
        mark(exchange, "client1.client-restart.unresolved", "observation-applied-unresolved")
    }

    private suspend fun masterRestartStage(exchange: LanFixtureExchange, session: BranchUiSession) {
        val original = OperationId(exchange.awaitText("fpr.original"))
        val apply = OperationId(exchange.awaitText("fpr.unknown-apply"))
        val unresolved = session.retailPhysical.recoverSupplierCash(original)
        check(unresolved is LocalRetailPhysicalReply.Progress &&
            fingerprint(unresolved.value) == exchange.awaitText("fpr.unresolved.original")) {
            "Unresolved original differs after Master restart: $unresolved" }
        val replay = session.retailPhysical.continueSql(apply)
        check(replay is LocalRetailPhysicalReply.Progress && fingerprint(replay.value) == exchange.awaitText("fpr.unresolved.apply")) {
            "Retained ORIGINAL apply replay after Master restart: $replay" }
        mark(exchange, "client1.master-restart.recovered", "exact-unresolved-original-and-apply")
        when (val listed = session.retailPhysical.pendingSupplierCash()) {
            is LocalRetailPhysicalReply.Saved -> println("LAN-FPR observation pendingSupplierCash: saved=${listed.values.size}; " +
                "facts=${listed.facts.joinToString(",") { "${it.state.name}:${it.originalRecordingOperationId == original}" }}")
            else -> println("LAN-FPR observation pendingSupplierCash: ${listed::class.simpleName}")
        }
        val resolution = staged(session, LocalRetailPhysicalRequest.SupplierCashResolve(original,
            LocalRetailObservation.CashPaid(LocalRetailEvidence(DomainId.random(), UtcInstant(Clock.System.now())),
                literalSupplierAmount = LocalSupplierObservedAmount("EGP", "228"))))
        exchange.putText("fpr.resolution", resolution.value)
        val linked = session.retailPhysical.continueSql(resolution)
        check(linked is LocalRetailPhysicalReply.Progress && linked.value.state == LocalRetailDurableState.SQL_RESOLUTION_PENDING) {
            "Linked resolution after restarts: $linked" }
        val recoveredResolution = session.retailPhysical.recoverSupplierCash(resolution)
        check(recoveredResolution is LocalRetailPhysicalReply.Progress && recoveredResolution.value.operationId == resolution &&
            recoveredResolution.value.state == LocalRetailDurableState.SQL_RESOLUTION_PENDING &&
            recoveredResolution.value.originalRecordingOperationId == original &&
            recoveredResolution.value.resolutionOperationId == resolution && recoveredResolution.value.resolutionRecorded &&
            recoveredResolution.value.reviewDisplay?.amount == payout) { "Resolution recovery: $recoveredResolution" }
        val applyResolution = staged(session, LocalRetailPhysicalRequest.SupplierCashApply(original, LocalRetailObservationPhase.RESOLUTION))
        exchange.putText("fpr.resolution-apply", applyResolution.value)
        val paid = session.retailPhysical.continueSql(applyResolution)
        check(paid is LocalRetailPhysicalReply.Progress && paid.value.state == LocalRetailDurableState.FINANCIALLY_APPLIED) { "Paid: $paid" }
        repeat(3) { check((session.retailPhysical.continueSql(applyResolution) as? LocalRetailPhysicalReply.Progress)?.value == paid.value) }
        val visible = session.workflows.read(LocalWorkflowQuery(LocalWorkflow.CASH))
        check(visible is LocalReadResult.Available)
        val cash = DomainId(exchange.awaitText("fpr.session"))
        val record = visible.page.records.single { it.reference.kind == LocalRecordKind.CASH_SESSION && it.reference.id == cash }
        val expected = record.values.single { it.label.english == "Expected cash" }.value.english
        check(Regex("-?[0-9]+\\.[0-9]{2}").find(expected)?.value == "772.00" && "EGP" in expected) { "Expected cash: $expected" }
        mark(exchange, "client1.master-restart.finished", "financially-applied-once-cash772")
    }

    /** Adapted with attribution from LanGenuinePaymentSupport.verifyNativeLedger (Agent2-owned,
     * unmodified): fresh read-only native SQLite connection; no row injection or force proof. */
    private suspend fun verifyLedger(exchange: LanFixtureExchange, root: Path, scope: BranchScope, actor: DomainId) {
        val databases = Files.walk(root).use { files -> files.filter { it.fileName.toString() == "master.db" }.toList() }
        check(databases.size == 1)
        val path = databases.single().toRealPath(); check(path.startsWith(root.toRealPath()))
        val original = OperationId(exchange.awaitText("fpr.original"))
        val resolution = OperationId(exchange.awaitText("fpr.resolution"))
        val apply = OperationId(exchange.awaitText("fpr.unknown-apply"))
        val applyResolution = OperationId(exchange.awaitText("fpr.resolution-apply"))
        val cash = DomainId(exchange.awaitText("fpr.session"))
        BundledSQLiteDriver().open(path.toString(), SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { db ->
            fun row(key: AggregateKey): Pair<Revision, StoredBytes> = db.prepare(
                "SELECT revision,payload,payloadHash FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use { cursor ->
                cursor.bindText(1, scope.storageKey()); cursor.bindText(2, key.kind); cursor.bindText(3, key.id.value)
                check(cursor.step()); val revision = Revision(cursor.getLong(0)); val bytes = cursor.getBlob(1)
                check(sha256(bytes).value == cursor.getText(2) && !cursor.step())
                revision to StoredBytes(bytes)
            }
            val raw = LocalSupplierCashFacts.decodeRaw(row(LocalSupplierCashJournal.rawKey(original)).second)
            val linked = LocalSupplierCashFacts.decodeRaw(row(LocalSupplierCashJournal.resolutionKey(resolution)).second)
            check(raw.target == linked.target && raw.originalActorId == actor && raw.recordingActorId == actor &&
                linked.recordingActorId == actor && raw.phase == LocalSupplierCashPhase.ORIGINAL && linked.phase == LocalSupplierCashPhase.RESOLUTION)
            check((LocalSupplierCashCodec.decode(raw.body()) as LocalSupplierCashRequest.RecordOriginal).outcome is LocalSupplierCashOutcome.Unknown)
            val resolved = LocalSupplierCashCodec.decode(linked.body()) as LocalSupplierCashRequest.RecordResolution
            check(resolved.originalBodyDigest == raw.bodyDigest && resolved.resolverActorId == actor &&
                (resolved.outcome as LocalSupplierCashOutcome.CashPaid).supportedAmountOrNull() == payout)
            for ((operation, appliedOperation, action) in listOf(Triple(original, apply, LocalSupplierCashAction.UNKNOWN),
                Triple(resolution, applyResolution, LocalSupplierCashAction.CASH_PAID))) {
                val application = LocalSupplierCashFacts.decodeApplication(row(LocalSupplierCashJournal.applicationKey(operation)).second)
                check(application.applicationOperationId == appliedOperation && application.originalActorId == actor &&
                    (application.result as? LocalSupplierCashResult.Applied)?.action == action)
            }
            val sessionRow = row(AggregateKey(CashNonSaleStoreProfile.SESSION_KIND, cash))
            val session = SalesSnapshotCodec.decodeCashSession(sessionRow.second.copyBytes().decodeToString(), SalesSnapshotBinding(
                SalesSnapshotKind.CASH_SESSION, scope, cash, SalesStorageRevision.toDomain(sessionRow.first)))
            check(session.custodianId == actor && session.opening.amount == opening && session.expectedCash == finalCash &&
                session.totals.payOuts == payout && session.totals.payIns.minor == 0L && session.totals.received.minor == 0L)
            val movement = row(LocalSupplierCashStorage.movementKey(raw.target.attemptId))
            check(movement.first == Revision(1))
            val literal = Json.parseToJsonElement(movement.second.copyBytes().decodeToString()).jsonObject
            check(literal.getValue("operationId").jsonPrimitive.content == applyResolution.value &&
                literal.getValue("originalActorId").jsonPrimitive.content == actor.value &&
                literal.getValue("sessionId").jsonPrimitive.content == cash.value &&
                literal.getValue("delta").jsonObject.getValue("minor").jsonPrimitive.long == -22_800L)
            val operations = listOf("fpr.opening", "fpr.start", "fpr.original", "fpr.unknown-apply",
                "fpr.resolution", "fpr.resolution-apply").map { exchange.awaitText(it) }
            for (operation in operations) for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
                db.prepare("SELECT COUNT(*) FROM $table WHERE scope=? AND operationId=?").use { cursor ->
                    cursor.bindText(1, scope.storageKey()); cursor.bindText(2, operation)
                    check(cursor.step() && cursor.getLong(0) == 1L && !cursor.step()) { "$table rows for one operation != 1" }
                }
            }
        }
    }

    /** Same production-shaped schedule as LanRecoverySupportingEndpoint.recoverAndConnect (1.0.2). */
    private suspend fun connectWithProductionRetry(rpc: LocalOriginalRpcClientOwner, endpoint: RpcEndpoint,
        exchange: LanFixtureExchange, stage: String) {
        val started = System.nanoTime()
        val attempts = mutableListOf<String>()
        var wait = 1_000L
        while (true) {
            if (attempts.isNotEmpty()) {
                val before = checkNotNull(rpc.original.value)
                rpc.recoverTransport()
                val recovered = checkNotNull(rpc.original.value)
                check(recovered.identity.copyWireBytes().contentEquals(before.identity.copyWireBytes()) &&
                    recovered.exactDeviceName == before.exactDeviceName) { "Recovered Client identity differs" }
            }
            val refused = try { rpc.connect(endpoint); false } catch (failure: RpcFailure) {
                if (failure.kind != RpcFailureKind.NotConnected || failure.phase != RpcFailurePhase.Negotiation ||
                    failure.executionEvidence != RpcExecutionEvidence.NotSent) throw failure
                true
            }
            attempts += (if (refused) "NotConnected/Negotiation/NotSent" else "Ready") + "@${(System.nanoTime() - started) / 1_000_000}ms"
            println("LAN-FPR connect: stage=$stage attempt=${attempts.size}; ${attempts.last()}")
            if (!refused) break
            check(attempts.size < 7) { "Selected connect still refused after the production schedule: $attempts" }
            delay(wait)
            wait = (wait * 2).coerceAtMost(30_000L)
        }
        exchange.putText("client1.$stage.connect.attempts", attempts.joinToString(";"))
    }

    private suspend fun credentialSnapshot(journals: DesktopPairedDeviceClientCredentials): StoredClientCredentials =
        (journals.credentials.read() as? JournalRead.Value)?.value ?: error("Protected credential journal unavailable")

    private fun <T> LocalCompanyAuthorityResult<T>.value(stage: String): T = when (this) {
        is LocalCompanyAuthorityResult.Value -> value
        is LocalCompanyAuthorityResult.Unavailable -> error("$stage unavailable: $reason")
    }
}
