package com.ukkera.pos.database

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.recovery.OperationIdentity
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*

/** SELF-AUTHORED, NOT independent. lan-founding-supervisor-supporting-v1.0.1.
 * Direct genuine TCP/native source-purpose coverage, NOT Client UI journal/device acceptance.
 * The original paid supplier case runs first unchanged. Separate existing-only processes
 * exercise founding-account Review/refusal on its actually admitted additional device.
 * No native owner, authority callback, historical command view or new physical action. */
internal object LanFoundingSupervisorSupport {
    const val ENABLE_PROPERTY = "ukkera.lan.foundingSupervisorSupporting"
    const val EXISTING_PROPERTY = "ukkera.lan.foundingSupervisorExistingRoot"
    const val MAPPING_VERSION = "lan-founding-supervisor-supporting-v1.0.1"
    val labels = listOf("founding.review", "founding.refusal")
    fun existingRoot(): Path? = System.getProperty(EXISTING_PROPERTY)?.takeIf { it.isNotBlank() }?.let { Path.of(it).toRealPath() }

    suspend fun scope(exchange: LanFixtureExchange): BranchScope {
        val ids = exchange.awaitText("master.scope").split('/').map(::DomainId)
        check(ids.size == 3); return BranchScope(ids[0], ids[1], ids[2])
    }

    fun requestBytes(request: MutationRequest): ByteArray = ByteArrayOutputStream().also { output ->
        DataOutputStream(output).use { data ->
            data.writeUTF(request.operationId.value); data.writeUTF(request.command.name); data.writeInt(request.command.version)
            val bytes = request.payload.copyBytes()
            try { data.writeInt(bytes.size); data.write(bytes) } finally { bytes.fill(0) }
        }
    }.toByteArray()

    fun identity(bytes: ByteArray, scope: BranchScope, actor: DomainId): OperationIdentity =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val operation = OperationId(data.readUTF()); val command = CommandKey(data.readUTF(), data.readInt())
            val size = data.readInt(); check(size in 1..65_536)
            val body = StoredBytes(ByteArray(size).also(data::readFully)); check(data.read() == -1)
            OperationIdentity(scope.branchId, operation, actor, 1, semanticDigest(scope, actor, command, body).value)
        }

    fun receipt(result: MutationReply.Recorded): ByteArray = ByteArrayOutputStream().also { output ->
        check(result.physicalStart == null)
        DataOutputStream(output).use { data ->
            data.writeUTF(result.operationId.value); data.writeUTF(result.originalActorId.value)
            data.writeBoolean(result.committed); data.writeUTF(result.code)
            data.writeLong(result.authorityEpoch.value); data.writeLong(result.sequence.value)
            data.writeUTF(result.recordedAt.canonicalString())
            val bytes = result.result.copyBytes()
            try { data.writeInt(bytes.size); data.write(bytes) } finally { bytes.fill(0) }
        }
    }.toByteArray()

    fun exact(result: MutationReply.Recorded, stored: ByteArray) {
        check(result.replayed && result.physicalStart == null && receipt(result).contentEquals(stored)) {
            "Exact original identity/result changed"
        }
    }

    suspend fun verifyAfterClosed(exchange: LanFixtureExchange, root: Path) {
        for (role in listOf("master", "client1", "client2")) check(exchange.awaitText("$role.closed") == "confirmed")
        val base = LanFixtureExchange(root.resolve("exchange"))
        val scope = scope(base)
        // SAME original independent financial ledger checks; no marker is rewritten.
        LanGenuinePaymentSupport.verifyNativeLedger(base, root.resolve("master"), scope, DomainId(base.awaitText("client1.actor")))
        val paths = Files.walk(root.resolve("master")).use { files -> files.filter { it.fileName.toString() == "master.db" }.toList() }
        check(paths.size == 1 && paths.single().toRealPath().startsWith(root.resolve("master").toRealPath()))
        BundledSQLiteDriver().open(paths.single().toString(), SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { db ->
            for (label in labels) {
                val operation = exchange.awaitText("$label.operation")
                for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent"))
                    db.prepare("SELECT COUNT(*) FROM $table WHERE scope=? AND operationId=?").use { cursor ->
                        cursor.bindText(1, scope.storageKey()); cursor.bindText(2, operation)
                        check(cursor.step() && cursor.getLong(0) == 1L && !cursor.step())
                    }
            }
        }
        exchange.putText("founding.live.verified", "exact-review-refusal-replays-revocation-cash77200-no-new-payout")
    }
}

/** Existing-only second phase. All role files and SDK originals must already exist. */
internal object LanFoundingSupervisorEndpoint {
    private const val OWNER_PASSWORD = "synthetic-owner-pass-2026"
    private const val CASHIER_PASSWORD = "synthetic-cashier-pass-2026"

    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        check(System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true")
        check(java.lang.Boolean.getBoolean(LanFoundingSupervisorSupport.ENABLE_PROPERTY))
        check(java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY))
        require(args.size == 6)
        val (role, rootName, iface, address) = args.take(4)
        val root = Path.of(rootName).toRealPath()
        check(root == LanFoundingSupervisorSupport.existingRoot())
        val exchange = LanFixtureExchange(root.resolve("founding-exchange"), args[5].toLong())
        val base = LanFixtureExchange(root.resolve("exchange"))
        for (label in listOf("master.closed", "client1.closed", "client2.closed", "payment.verified", "executor.completed"))
            check(base.read(label) != null)
        exchange.putText("$role.boot", ProcessHandle.current().pid().toString())
        exchange.await("network.ready")
        val up = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        check(up.size == 1 && up.single().name == iface && up.single().supportsMulticast() &&
            up.single().inetAddresses.toList().any { it.hostAddress == address })
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val lan = OrganizationLan(listOf("10.203.16.0/24"), iface, address)
        try {
            withTimeout(args[4].toLong()) {
                if (role == "master") master(root, exchange, base, lan, lifetime)
                else client(role, root, exchange, base, lan, lifetime)
            }
            exchange.putText("$role.closed", "confirmed")
        } finally { lifetime.cancel() }
    }

    internal data class ExistingMaster(val original: LanGenuineProcessEndpoint.LanGenuineMaster,
        val service: AndroidSelfOwnedMasterService, val coordinates: SelfOwnedAllocationCoordinatesV1)

    internal suspend fun openExisting(root: Path): ExistingMaster {
        val original = LanGenuineProcessEndpoint.LanGenuineMaster(root.resolve("master"))
        val opened = DesktopSelfOwnedInstallationOwner.open(original.os, SelfOwnedNativeProfileV1.LIVE)
        check(opened is SelfOwnedNativeOpenResult.Opened) { "Actual existing Master refused" }
        val coordinates = opened.context.materialAtOpen.coordinates
        val publications = when (val result = opened.context.readOriginalBusinessPublications()) {
            is LocalCompanyAuthorityResult.Value -> result.value
            is LocalCompanyAuthorityResult.Unavailable -> error("Existing publications unavailable: ${result.reason}")
        }
        val physical = checkNotNull(DesktopOriginalPhysicalJournalExecutionGate.create(original.factory.owner,
            DesktopClientJournalStorages.masterService(DesktopMasterClientJournalCustody.of(original.os, SelfOwnedNativeProfileV1.LIVE))))
        val registry = LocalBranchCommerceRegistry(NativeCompanyPublicationSignatureVerifier(publications.trust),
            UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
            retailParticipants = physical, nativePublications = publications)
        val composed = original.factory.fromOriginalContext(opened.context, registry.commands, registry.reads, physical,
            registry.nativeOriginalImports, registry.stockProjection, registry.historyProducer)
        check(composed is AndroidSelfOwnedMasterOpenResult.Opened)
        val service = composed.service
        try {
            check(service.mode.value == LocalMasterLifecycleMode.OPERATIONAL) { "Existing Master mode: ${service.mode.value}" }
            check(physical.acquireRemoteOriginalOwner(service.originalLocalClient(), DistributedPhysicalNativeAction.REOPEN_EXISTING))
            return ExistingMaster(original, service, coordinates)
        } catch (failure: Throwable) {
            withContext(NonCancellable) { check(service.quiesceAndClose()) }
            throw failure
        }
    }

    internal suspend fun <T> owner(held: ExistingMaster, move: Boolean,
        action: suspend (LocalMasterClient, LocalGatewayCredentials) -> T): T {
        val client = checkNotNull(held.service.client); val connection = checkNotNull(held.service.localConnectionId)
        val auth = client.signIn(connection, LocalLoginIdentifier("owner.one"), OWNER_PASSWORD.toCharArray(), move)
        check(auth is LocalAuthenticationResult.Authenticated)
        val credentials = LocalGatewayCredentials(connection, auth.tokens.access)
        try { return action(client, credentials) }
        finally { try { check(client.logout(credentials) == LocalSignOutResult.Revoked) } finally { auth.tokens.destroy() } }
    }

    private suspend fun master(root: Path, exchange: LanFixtureExchange, base: LanFixtureExchange,
        lan: OrganizationLan, lifetime: CoroutineScope) {
        val held = openExisting(root)
        var host: DesktopOriginalRpcHostOwner? = null
        try {
            val client = held.service.originalLocalClient()
            val sdk = when (val read = client.recoverRpcMasterOriginal()) {
                is LocalCompanyAuthorityResult.Value -> read.value
                is LocalCompanyAuthorityResult.Unavailable -> error("Original Master SDK unavailable: ${read.reason}")
            }
            check(sdk.copyWireBytes().contentEquals(base.await("master.sdk")))
            exchange.put("master.sdk", sdk.copyWireBytes())
            exchange.putText("master.scope", held.coordinates.scope.let { "${it.organizationId.value}/${it.companyId.value}/${it.branchId.value}" })
            exchange.putText("founding.actor", held.coordinates.firstActorId.value)
            host = DesktopOriginalRpcHostOwner.retainExisting(held.original.factory.owner, lifetime, client, lan, true) {
                held.original.os.openSdkIdentityStore(SelfOwnedNativeProfileV1.LIVE)
            }
            host.acquire(); host.start(); exchange.putText("master.endpoint", host.endpoint().port.toString())
            exchange.await("client1.current")
            exchange.await("founding.operations-complete")
            owner(held, true) { clientNow, credentials ->
                check(clientNow.current(credentials) is LocalSessionViewResult.Current)
                exchange.putText("founding.revoked", "actual-password-explicit-device-move-policy1")
                exchange.await("founding.denied-on-fresh-transport")
            }
            exchange.putText("clients.release", "close")
            exchange.await("client1.finished"); exchange.await("client2.finished")
        } finally {
            withContext(NonCancellable) { try { host?.close() } finally { check(held.service.quiesceAndClose()) } }
        }
    }

    private suspend fun client(role: String, root: Path, exchange: LanFixtureExchange, base: LanFixtureExchange,
        lan: OrganizationLan, lifetime: CoroutineScope) {
        check(role in setOf("client1", "client2"))
        val platform = LanLinuxClientTargetPlatform(root.resolve(role))
        val actual = LanPaymentPhysicalNativeSupport.platform(platform, root.resolve(role))
        var target: DesktopClientTargetHandle? = DesktopClientTargetHandle(DesktopDevicePairingTarget.openExisting(actual))
        var rpc: LocalOriginalRpcClientOwner? = null
        val issued = mutableListOf<LocalSessionTokens>()
        try {
            val active = checkNotNull(target)
            val approved = when (val result = active.recoverApprovedMasterData(checkNotNull(active.original().originalPairingActionId))) {
                is LocalOriginalPairingTargetResult.Value -> result.value
                is LocalOriginalPairingTargetResult.Unavailable -> error("Actual retained approval unavailable: ${result.reason}")
            }
            check(active.recoverSdkIdentity().copyWireBytes().contentEquals(base.await("$role.sdk")))
            check(approved.targetDeviceId.value == base.awaitText("$role.imported"))
            val held = DesktopOriginalRpcClientOwner.retainRecoverableExisting(lifetime, active, approved.originalPairingActionId, lan) {
                platform.identityStore()
            }.also { rpc = it }
            target = null
            held.acquire()
            val endpoint = RpcEndpoint("10.203.16.1", exchange.awaitText("master.endpoint").toInt())
            held.connect(endpoint)
            var auth = held.authenticationService()
            if (role == "client2") exchange.await("client1.current")
            val login = if (role == "client1") "cashier.a" else "owner.one"
            val actor = DomainId(if (role == "client1") base.awaitText("client1.actor") else exchange.awaitText("founding.actor"))
            val signed = auth.signIn(auth.connectionId, LocalLoginIdentifier(login),
                (if (role == "client1") CASHIER_PASSWORD else OWNER_PASSWORD).toCharArray(), true)
            check(signed is LocalAuthenticationResult.Authenticated) { "Existing-role genuine password sign-in refused: $signed" }
            issued += signed.tokens
            val credentials = LocalGatewayCredentials(auth.connectionId, signed.tokens.access)
            val current = auth.current(credentials)
            check(current is LocalSessionViewResult.Current && current.view.actorId == actor &&
                current.view.deviceId == approved.targetDeviceId && current.view.scope == LanFoundingSupervisorSupport.scope(base))
            if (role == "client1") {
                val directory = held.directoryClient(LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                val read = directory.read(credentials, LocalCashReadCodec.request(LocalCashQueryV1.Session(DomainId(base.awaitText("payment.session")))))
                check(read is ReadReply.Data)
                val cash = (LocalCashReadCodec.decode(read.payload) as? LocalCashReadResultV1.Session)?.session
                    ?: error("Actual Client1 protected cash view missing")
                check(cash.custodianId == actor && cash.expectedCash == Money(Currency.EGP, 77_200) &&
                    cash.payOuts == Money(Currency.EGP, 22_800) && cash.cashLedgerRevision == Revision(1) &&
                    cash.reservedCashOut.minor == 0L && cash.pendingAttemptIds.isEmpty())
            }
            exchange.putText("$role.current", "actual-protected-native-actor-device-current")
            if (role == "client1") {
                exchange.await("founding.operations-complete")
                check(auth.current(credentials) is LocalSessionViewResult.Current)
                exchange.await("clients.release")
                check(auth.current(credentials) is LocalSessionViewResult.Current)
            } else {
                val directory = held.directoryClient(LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
                val original = OperationId(base.awaitText("payment.original"))
                val requests = listOf(
                    LocalSupplierCashSupervisorCodec.command(OperationId.random(), LocalSupplierCashSupervisorRequest.Review(original)),
                    LocalSupplierCashSupervisorCodec.command(OperationId.random(), LocalSupplierCashSupervisorRequest.Apply(OperationId(base.awaitText("payment.resolution")), LocalSupplierCashPhase.RESOLUTION)),
                )
                for ((label, request) in LanFoundingSupervisorSupport.labels.zip(requests)) {
                    val encoded = LanFoundingSupervisorSupport.requestBytes(request)
                    exchange.put("$label.request", encoded); exchange.putText("$label.operation", request.operationId.value)
                    val reply = directory.executeAccepting(credentials, request) { exchange.put("$label.receipt", LanFoundingSupervisorSupport.receipt(it)) }
                    check(reply is MutationReply.Recorded && !reply.committed && reply.physicalStart == null) { "Audited founding request failed: $reply" }
                    if (label == "founding.review") {
                        check(reply.code == "purchasing.supplier_cash_supervisor_reviewed")
                        val review = LocalSupplierCashSupervisorCodec.decodeReview(reply.result)
                        val resolved = checkNotNull(review.resolution)
                        check(review.original.target.recordingOperationId == original && review.original.originalActorId.value == base.awaitText("client1.actor") &&
                            review.original.recordingActorId == review.original.originalActorId && resolved.target == review.original.target &&
                            review.original.applicationOperationId?.value == base.awaitText("payment.unknown-apply") &&
                            resolved.originalActorId == review.original.originalActorId && resolved.recordingActorId == review.original.originalActorId &&
                            review.original.amount == Money(Currency.EGP, 22_800) && resolved.amount == Money(Currency.EGP, 22_800) &&
                            resolved.target.resolutionOperationId.value == base.awaitText("payment.resolution") &&
                            review.originalOutcome is LocalSupplierCashOutcome.Unknown &&
                            (review.resolutionOutcome as? LocalSupplierCashOutcome.CashPaid)?.supportedAmountOrNull() == Money(Currency.EGP, 22_800) &&
                            resolved.applicationOperationId?.value == base.awaitText("payment.resolution-apply"))
                    } else check(reply.code == "purchasing.supplier_cash_already_applied")
                    val saved = exchange.await("$label.receipt")
                    repeat(3) {
                        val replay = directory.execute(credentials, request)
                        check(replay is MutationReply.Recorded); LanFoundingSupervisorSupport.exact(replay, saved)
                    }
                    val lookup = directory.lookup(credentials, LanFoundingSupervisorSupport.identity(encoded, current.view.scope, actor))
                    check(lookup is LookupReply.Found); LanFoundingSupervisorSupport.exact(lookup.result, saved)
                }
                exchange.putText("founding.operations-complete", "actual-additional-device-review-and-already-applied-no-money")
                exchange.await("founding.revoked")
                // The actual current account cut can retire its captured SDK scope. Recover
                // the SAME native target before testing the old bearer on a fresh real peer.
                connectRecovered(held, endpoint, exchange)
                auth = held.authenticationService()
                val oldOnCurrentPeer = LocalGatewayCredentials(auth.connectionId, signed.tokens.access)
                val denied = try { auth.current(oldOnCurrentPeer) is LocalSessionViewResult.Denied }
                    catch (failure: RpcFailure) { if (failure.kind != RpcFailureKind.Unauthorized) throw failure; true }
                check(denied) { "Revoked founding bearer accepted on fresh actual original transport" }
                val restored = auth.signIn(auth.connectionId, LocalLoginIdentifier("cashier.b"), CASHIER_PASSWORD.toCharArray(), true)
                check(restored is LocalAuthenticationResult.Authenticated); issued += restored.tokens
                val restoredCurrent = auth.current(LocalGatewayCredentials(auth.connectionId, restored.tokens.access))
                check(restoredCurrent is LocalSessionViewResult.Current && restoredCurrent.view.actorId.value == base.awaitText("client2.actor") &&
                    restoredCurrent.view.deviceId == approved.targetDeviceId)
                exchange.putText("founding.denied-on-fresh-transport", "old-bearer-denied-new-cashier-current-same-native-device")
                exchange.await("clients.release")
            }
            exchange.putText("$role.finished", "current-proof-and-resource-phase-finished")
        } finally {
            withContext(NonCancellable) { try { rpc?.close() } finally { target?.close(); issued.forEach { it.destroy() } } }
        }
    }

    private suspend fun connectRecovered(rpc: LocalOriginalRpcClientOwner, endpoint: RpcEndpoint, exchange: LanFixtureExchange) {
        var attempt = 0
        var wait = 1_000L
        while (true) {
            val original = checkNotNull(rpc.original.value)
            rpc.recoverTransport()
            val recovered = checkNotNull(rpc.original.value)
            check(recovered.identity.copyWireBytes().contentEquals(original.identity.copyWireBytes()) &&
                recovered.exactDeviceName == original.exactDeviceName)
            try { rpc.connect(endpoint); exchange.putText("founding.reconnect.attempts", (++attempt).toString()); return }
            catch (failure: RpcFailure) {
                if (failure.kind != RpcFailureKind.NotConnected || failure.phase != RpcFailurePhase.Negotiation ||
                    failure.executionEvidence != RpcExecutionEvidence.NotSent || ++attempt >= 7) throw failure
                delay(wait); wait = minOf(30_000L, wait * 2L)
            }
        }
    }
}

/** Third fresh JVM: actual native whole replay, fresh cohost auth and exact originals. */
internal object LanFoundingSupervisorCold {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        check(System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true")
        require(args.size == 1)
        val root = Path.of(args.single()).toRealPath()
        val exchange = LanFixtureExchange(root.resolve("founding-exchange"))
        check(exchange.read("founding.live.verified") != null && exchange.read("executor.completed") != null)
        val held = LanFoundingSupervisorEndpoint.openExisting(root)
        try {
            val sdk = when (val result = held.service.originalLocalClient().recoverRpcMasterOriginal()) {
                is LocalCompanyAuthorityResult.Value -> result.value
                is LocalCompanyAuthorityResult.Unavailable -> error("Cold original SDK unavailable: ${result.reason}")
            }
            check(sdk.copyWireBytes().contentEquals(LanFixtureExchange(root.resolve("exchange")).await("master.sdk")))
            LanFoundingSupervisorEndpoint.owner(held, false) { client, credentials ->
                val current = client.current(credentials)
                check(current is LocalSessionViewResult.Current && current.view.actorId == held.coordinates.firstActorId &&
                    current.view.deviceId == held.coordinates.deviceId)
                for (label in LanFoundingSupervisorSupport.labels) {
                    val identity = LanFoundingSupervisorSupport.identity(exchange.await("$label.request"), held.coordinates.scope, held.coordinates.firstActorId)
                    val reply = client.lookup(credentials, identity)
                    check(reply is LookupReply.Found); LanFoundingSupervisorSupport.exact(reply.result, exchange.await("$label.receipt"))
                }
            }
        } finally { withContext(NonCancellable) { check(held.service.quiesceAndClose()) } }
        // Read-only ledger recheck AFTER the actual cold owner certainly closed.
        val base = LanFixtureExchange(root.resolve("exchange"))
        LanGenuinePaymentSupport.verifyNativeLedger(base, root.resolve("master"), held.coordinates.scope, DomainId(base.awaitText("client1.actor")))
        exchange.putText("founding.cold.verified", "existing-only-operational-same-originals-current-auth-cash77200-native-close")
    }
}

/** SELF-AUTHORED native admission probe; not a whole TCP/confirmed-close pass. */
internal object LanFoundingSupervisorReplayProbe {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        check(System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true")
        require(args.size == 1)
        val held = LanFoundingSupervisorEndpoint.openExisting(Path.of(args.single()).toRealPath())
        try { check(held.service.mode.value == LocalMasterLifecycleMode.OPERATIONAL) }
        finally { withContext(NonCancellable) { check(held.service.quiesceAndClose()) } }
        println("Founding additional-device native replay probe OPERATIONAL and closed")
    }
}
