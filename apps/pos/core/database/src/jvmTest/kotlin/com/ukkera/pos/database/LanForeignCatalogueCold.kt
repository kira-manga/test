package com.ukkera.pos.database

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.rpc.DesktopOriginalRpcClientOwner
import com.ukkera.pos.rpc.DesktopOriginalRpcHostOwner
import com.ukkera.pos.rpc.LocalOriginalRpcClientOwner
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.transport.lan.OrganizationLan
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

/** SELF-AUTHORED, NOT independent. FRC04 existing-only follow-up using the SAME real
 * three native roots. Actual production Client acquisition must validate B's complete
 * native catalogue/critical resolution cells; logical SQL recovery alone cannot pass. */
internal object LanForeignCatalogueColdEndpoint {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        check(System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true" && LanForeignCatalogueSupport.enabled())
        require(args.size == 6)
        val (role, name, iface, address) = args.take(4)
        val root = Path.of(name).toRealPath()
        check(root == LanForeignCatalogueSupport.existingRoot())
        val exchange = LanFixtureExchange(LanForeignCatalogueSupport.coldExchange(root), args[5].toLong())
        val base = LanFixtureExchange(root.resolve("exchange"))
        for (label in if (LanForeignCatalogueSupport.interrupted()) listOf("master.closed", "client1.closed", "client2.killed", "foreign.interrupted.verified", "executor.completed")
            else listOf("master.closed", "client1.closed", "client2.closed", "foreign.live.verified", "executor.completed"))
            check(base.read(label) != null)
        exchange.putText("$role.boot", ProcessHandle.current().pid().toString())
        exchange.await("network.ready")
        val up = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        check(up.size == 1 && up.single().name == iface && up.single().supportsMulticast() &&
            up.single().inetAddresses.toList().any { it.hostAddress == address })
        val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            withTimeout(args[4].toLong()) {
                val lan = OrganizationLan(listOf("10.203.16.0/24"), iface, address)
                if (role == "master") master(root, exchange, base, lan, lifetime)
                else client(role, root, exchange, base, lan, lifetime)
            }
            exchange.putText("$role.closed", "confirmed")
        } finally { lifetime.cancel() }
    }

    private suspend fun master(root: Path, exchange: LanFixtureExchange, base: LanFixtureExchange,
        lan: OrganizationLan, lifetime: CoroutineScope) {
        val held = LanFoundingSupervisorEndpoint.openExisting(root)
        var host: DesktopOriginalRpcHostOwner? = null
        try {
            val original = held.service.originalLocalClient()
            val sdk = when (val value = original.recoverRpcMasterOriginal()) {
                is LocalCompanyAuthorityResult.Value -> value.value
                is LocalCompanyAuthorityResult.Unavailable -> error("Existing native Master SDK unavailable: ${value.reason}")
            }
            check(sdk.copyWireBytes().contentEquals(base.await("master.sdk")))
            exchange.putText("master.operational", "existing-only-native-history-operational-same-sdk")
            host = DesktopOriginalRpcHostOwner.retainExisting(held.original.factory.owner, lifetime, original, lan, true) {
                held.original.os.openSdkIdentityStore(SelfOwnedNativeProfileV1.LIVE)
            }
            host.acquire(); host.start()
            exchange.putText("master.endpoint", host.endpoint().port.toString())
            exchange.await("client1.finished"); exchange.await("client2.finished")
            exchange.putText("clients.release", "confirmed-client-checks-complete")
            // Keep the actual Host alive for final Current/logout and protected native
            // retirement. The previous support closed it immediately after release.
            exchange.await("client1.closed"); exchange.await("client2.closed")
        } finally {
            withContext(NonCancellable) { try { host?.close() } finally { check(held.service.quiesceAndClose()) } }
        }
    }

    private suspend fun client(role: String, root: Path, exchange: LanFixtureExchange, base: LanFixtureExchange,
        lan: OrganizationLan, lifetime: CoroutineScope) {
        check(role in setOf("client1", "client2"))
        val scope = LanFoundingSupervisorSupport.scope(base)
        val platform = LanLinuxClientTargetPlatform(root.resolve(role))
        val actual = LanPaymentPhysicalNativeSupport.platform(platform, root.resolve(role))
        var target: DesktopClientTargetHandle? = DesktopClientTargetHandle(DesktopDevicePairingTarget.openExisting(actual))
        var rpc: LocalOriginalRpcClientOwner? = null
        var journals: DesktopPairedDeviceClientCredentials? = null
        var gateway: LocalEndpointBranchGateway? = null
        try {
            val active = checkNotNull(target)
            val recovered = active.recoverApprovedMasterData(checkNotNull(active.original().originalPairingActionId))
            check(recovered is LocalOriginalPairingTargetResult.Value)
            val approved = recovered.value
            check(approved.targetDeviceId.value == base.awaitText("$role.imported") &&
                active.recoverSdkIdentity().copyWireBytes().contentEquals(base.await("$role.sdk")))
            val rootOwner = Any()
            val custody = active.acquireCredentialJournalCustody(rootOwner, approved.originalPairingActionId,
                LocalPairedDeviceCredentialJournalAction.REOPEN)
            val acquired = DesktopPairedDeviceClientCredentials.acquireForActorIntents(rootOwner, custody,
                LanProcessJournalSupport.storage(root.resolve(role)))
            check(acquired is DesktopPairedDeviceClientCredentialsResult.Opened)
            val heldJournals = acquired.credentials.also { journals = it }
            val heldRpc = DesktopOriginalRpcClientOwner.retainRecoverableExisting(lifetime, active,
                approved.originalPairingActionId, lan) { platform.identityStore() }.also { rpc = it }
            target = null
            heldRpc.acquire()
            heldRpc.connect(RpcEndpoint("10.203.16.1", exchange.awaitText("master.endpoint").toInt()))
            val source = checkNotNull(heldJournals.actorIntentSource(heldRpc))
            val actor = DomainId(base.awaitText("$role.actor"))
            val identity = LocalClientIdentity(scope, actor, approved.targetDeviceId)
            check(source.current(identity) == null) { "Native recovery restored employee access before fresh sign-in" }
            val directory = heldRpc.directoryClient(LocalMasterRpcWorkflowProfile.LAN_BUSINESS)
            val binding = checkNotNull(heldJournals.remotePhysicalBinding(heldRpc, directory))
            val connection = LocalRemoteBusinessClientConnection(directory, directory.connectionId,
                LocalClientDeviceIdentity(scope, approved.targetDeviceId),
                LocalBranchDisplayLabels(scope, "Synthetic LAN Company", "Synthetic LAN Branch"), binding) {
                directory.isCurrent() && heldJournals.isCurrent()
            }
            val heldGateway = LocalEndpointBranchGateway.remoteBusiness(
                MutableStateFlow<LocalRemoteBusinessClientConnection?>(connection), heldJournals.credentials, source).also { gateway = it }
            // Establish C1's protected view before B's explicit sign-in changes its own account.
            if (role == "client2") exchange.await("client1.current")
            val signed = heldGateway.signIn(if (role == "client1") "cashier.a" else "cashier.b",
                "synthetic-cashier-pass-2026".toCharArray(), true)
            check(signed is SignInResult.Authenticated) { "Existing Client current sign-in unavailable: $signed" }
            val session = signed.session
            check(session.display.actorId == actor && session.current() is LocalSessionCheckResult.Current)
            val journal = checkNotNull(source.current(identity))
            if (role == "client1") {
                suspend fun cash(expected: String) {
                    val read = session.workflows.read(LocalWorkflowQuery(LocalWorkflow.CASH))
                    check(read is LocalReadResult.Available)
                    val row = read.page.records.single { it.reference.kind == LocalRecordKind.CASH_SESSION &&
                        it.reference.id.value == base.awaitText("payment.session") }
                    val amount = row.values.single { it.label.english == "Expected cash" }.value.english
                    check(Regex("-?[0-9]+\\.[0-9]{2}").find(amount)?.value == expected && "EGP" in amount)
                }
                cash(if (LanForeignCatalogueSupport.interrupted()) "1000.00" else "772.00")
                if (LanForeignCatalogueSupport.interrupted()) {
                    exchange.putText("client1.current", "fresh-current-same-native-actor-device")
                    exchange.await("client2.resolution-complete")
                    cash("772.00")
                }
            } else {
                val native = checkNotNull(binding.attach(journal)?.native) {
                    "Logical result recovery cannot mask failure to reopen B's real native counterpart catalogue"
                }
                check(native.originalActorId == actor && native.binding.deviceId == approved.targetDeviceId)
                exchange.putText("client2.native-reopened", "actual-existing-own-catalogue-critical-record-census")
                if (LanForeignCatalogueSupport.interrupted()) {
                    LanForeignCatalogueSupport.completeResolution(base, session, OperationId(base.awaitText("payment.resolution")))
                    exchange.putText("client2.resolution-complete", "same-durable-native-intent-resumed-one-application")
                }
                for (label in listOf("payment.resolution", "payment.resolution-apply")) {
                    val operation = OperationId(base.awaitText(label))
                    val reply = session.retailPhysical.recoverSupplierCash(operation)
                    // Exact original resolution retention remains SQL_RESOLUTION_PENDING;
                    // current application is a separate original, not a rewritten result.
                    val expected = if (label == "payment.resolution") LocalRetailDurableState.SQL_RESOLUTION_PENDING
                        else LocalRetailDurableState.FINANCIALLY_APPLIED
                    check(reply is LocalRetailPhysicalReply.Progress && reply.value.operationId == operation &&
                        reply.value.state == expected) {
                        "Exact existing foreign resolution result recovery failed: $reply"
                    }
                }
                val application = OperationId(base.awaitText("payment.resolution-apply"))
                val first = session.retailPhysical.continueSql(application)
                check(first is LocalRetailPhysicalReply.Progress && first.value.state == LocalRetailDurableState.FINANCIALLY_APPLIED)
                repeat(3) { check((session.retailPhysical.continueSql(application) as? LocalRetailPhysicalReply.Progress)?.value == first.value) }
                LanForeignCatalogueClientRoot(heldJournals, source, binding, identity).verifyOriginals(base)
                check(session.retailPhysical.recoverSupplierCash(OperationId(base.awaitText("payment.original"))) is LocalRetailPhysicalReply.Unavailable)
                exchange.putText("client2.originals-recovered", "same-resolution-application-IDs-three-exact-replays-no-instruction")
            }
            if (role != "client1" || !LanForeignCatalogueSupport.interrupted())
                exchange.putText("$role.current", "fresh-current-same-native-actor-device")
            exchange.putText("$role.finished", "existing-native-and-exact-original-checks-complete")
            exchange.await("clients.release")
            check(session.current() is LocalSessionCheckResult.Current)
            check(session.logout() == LocalSessionEndResult.REVOKED)
            check(session.retailPhysical.continueSql(OperationId(base.awaitText("payment.resolution-apply"))) is LocalRetailPhysicalReply.Unavailable)
        } finally { withContext(NonCancellable) {
            try { gateway?.closeAndAwait() } finally {
                try { journals?.close() } finally { try { rpc?.close() } finally { target?.close() } }
            }
        } }
    }
}
