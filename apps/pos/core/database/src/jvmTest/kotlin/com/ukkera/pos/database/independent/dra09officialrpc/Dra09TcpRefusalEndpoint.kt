package com.ukkera.pos.database.independent.dra09officialrpc

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.*
import dev.p2pkit.transport.lan.OrganizationLan
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.SerializationException
import kotlin.test.*

/**
 * TCP executor wiring for the existing frozen DR-A09-03–08, including the three
 * currentness and two typed-response variants. Each invocation has its own Host,
 * Client, network namespaces and synthetic operation. Controlled memory custody,
 * association DATA and out-of-band trust pins have the same limits as DR-A09-02.
 * Files coordinate observations; every RPC request/result/Receipt uses SDK sockets.
 */
internal object Dra09TcpRefusalEndpoint {
    private class TestAcceptanceFailure : Exception("DR-A09 controlled acceptance failure")

    @JvmStatic fun main(arguments: Array<String>): Unit = runBlocking {
        val (role, directory, iface, address, scenario) = arguments.toList()
        val files = Observations(Path.of(directory), role)
        files.publish("$role.boot", "booted")
        files.await("network.ready")
        val branch = BranchScope(id(1), id(2), id(3))
        val appId = LocalMasterSdkProfileV1.appId(branch)
        val platform = RpcPlatform.jvm(Dra09MemoryIdentityStore())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val lifetime = SupervisorJob()
        val trust = Dra09MemoryTrustStore()
        val lan = OrganizationLan(listOf("10.203.4.0/30"), iface, address)
        try {
            val initial = RpcClient.create(platform, scope) {
                this.appId = appId; deviceName = "DR-A09 TCP $role"; this.lan = lan
                trustStore = trust; transportReconnect = false
            }
            val fingerprint = initial.localIdentity.fingerprint
            initial.close()
            files.publish("$role.identity", fingerprint.value)
            val remote = PeerFingerprint(files.await(if (role == "host") "client.identity" else "host.identity"))
            if (role == "host") {
                val executes = AtomicInteger()
                val lookups = AtomicInteger()
                trust.seed(appId, RpcTrustPurpose.HostClients, setOf(remote))
                val execute = if (scenario != "08") LocalMasterClientRpcProtocolV1.EXECUTE else RpcProcedure(
                    name = "pos.business.execute", version = 2,
                    request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
                    response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
                    applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
                    retrySafety = RpcRetrySafety.NeverReinvoke,
                    requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
                    responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
                    errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
                )
                val host = RpcHost.create(platform, scope) {
                    this.appId = appId; deviceName = "DR-A09 TCP $role"; this.lan = lan
                    trustStore = trust; advertise = false; requestHistoryCapacity = 16
                    register(execute, authorize = { true }) { _, request ->
                        executes.incrementAndGet()
                        RpcReply.Success(LocalMasterRpcExecuteResponseV1(
                            if (scenario == "07-actor") id(90) else id(4),
                            LocalMasterRpcExecuteValueV1.Recorded(recorded(
                                if (scenario == "07-operation") OperationId.random() else request.request.operationId,
                            )),
                        ))
                    }
                    register(LocalMasterClientRpcProtocolV1.LOOKUP, authorize = { true }) { _, request ->
                        lookups.incrementAndGet()
                        RpcReply.Success(LocalMasterRpcLookupResponseV1(id(4),
                            LocalMasterRpcLookupValueV1.Found(recorded(request.identity.operationId, replayed = true))))
                    }
                }
                try {
                    assertEquals(fingerprint, host.localIdentity.fingerprint)
                    val master = Dra09OfficialRpcFixture.captureControlledMaster(host.localIdentity, branch, ::id, 1)
                    Files.write(files.root.resolve("host.original"), master.copyWireBytes())
                    host.start()
                    withTimeout(15_000) { host.state.first { it == RpcHostState.Running } }
                    files.publish("host.port", host.endpoint().port.toString())
                    observeHost(scenario, files, host, executes, lookups)
                } finally { host.close() }
            } else {
                trust.seed(appId, RpcTrustPurpose.SelectedHosts, setOf(remote))
                val client = RpcClient.create(platform, scope) {
                    this.appId = appId; deviceName = "DR-A09 TCP $role"; this.lan = lan
                    trustStore = trust; transportReconnect = false
                }
                try {
                    assertEquals(fingerprint, client.localIdentity.fingerprint)
                    val port = files.await("host.port").toInt()
                    val master = LocalMasterSdkOriginalV1.decode(Files.readAllBytes(files.root.resolve("host.original")))
                    assertEquals(remote, master.fingerprint)
                    client.connect(RpcSelectedHost(remote, RpcEndpoint("10.203.4.1", port)))
                    withTimeout(15_000) { client.state.first { it == RpcConnectionState.Ready } }
                    val bridge = Dra09OfficialRpcFixture.bindControlledBridge(client, master, branch, lifetime, id(5), ::id)
                    exerciseClient(scenario, files, client, bridge, remote)
                } finally { client.close() }
            }
        } finally { lifetime.cancel(); scope.cancel() }
    }

    private suspend fun observeHost(
        scenario: String, files: Observations, host: RpcHost,
        executes: AtomicInteger, lookups: AtomicInteger,
    ) {
        if (scenario in setOf("06-deny", "06-trust", "08")) {
            files.await("client.returned")
            assertEquals(0, executes.get())
            assertEquals(0, lookups.get())
            assertEquals(0L, host.requests.value.totals.accepted)
            assertEquals(0L, host.diagnostics.value.completedCalls)
            assertFalse(Files.exists(files.marker))
            if (scenario == "08") {
                assertEquals(1L, host.requests.value.totals.refusedAttempts)
                val refused = host.requests.value.entries.single()
                assertEquals("pos.business.execute", refused.procedure)
                assertEquals(1, refused.version)
                assertEquals(RpcHostCallState.Refused, refused.state)
                assertFalse(refused.admitted)
            } else assertTrue(host.requests.value.entries.isEmpty())
        } else {
            files.await("execute.observe")
            withTimeout(10_000) { host.diagnostics.first { it.completedCalls == 1L && it.retainedPayloadBytes > 0 } }
            val retained = host.diagnostics.value.retainedPayloadBytes
            val entry = host.requests.value.entries.single()
            assertEquals("pos.business.execute", entry.procedure)
            assertEquals(1, entry.version)
            assertEquals(RpcHostCallState.Succeeded, entry.state)
            assertEquals(1, executes.get())
            assertEquals(0, lookups.get())
            assertFalse(Files.exists(files.marker))
            files.publish("execute.observed", retained.toString())
            files.await("client.returned")
            // The frozen retention interval starts after the caller has returned.
            delay(1_500)
            if (scenario == "03") assertEquals(retained, host.diagnostics.value.retainedPayloadBytes)
            else assertTrue(host.diagnostics.value.retainedPayloadBytes >= retained,
                "Failed/cancelled/unaccepted calls must withhold Receipt")
            assertFalse(Files.exists(files.marker))
            assertEquals(1, executes.get())
            assertEquals(0, lookups.get())
            files.publish("execute.withheld", "observed")
            if (scenario == "04") {
                files.await("lookup.observe")
                val beforeReceipt = withTimeout(10_000) {
                    host.diagnostics.first { it.completedCalls == 2L && it.retainedPayloadBytes > retained }
                }.retainedPayloadBytes
                val lookup = host.requests.value.entries.single { it.procedure == "pos.business.lookup" }
                assertEquals(1, lookup.version)
                assertEquals(RpcHostCallState.Succeeded, lookup.state)
                assertFalse(Files.exists(files.marker))
                files.publish("lookup.observed", beforeReceipt.toString())
                files.await("durable.marker")
                withTimeout(10_000) { host.diagnostics.first { it.retainedPayloadBytes < beforeReceipt } }
                files.await("client.recovered")
                assertEquals(1, executes.get(), "Recovery must not redispatch the original execute")
                assertEquals(1, lookups.get())
                assertEquals(2L, host.requests.value.totals.accepted)
                assertEquals(2L, host.diagnostics.value.completedCalls)
            }
        }
        files.publish("host.pass", scenario)
        files.await("client.pass")
    }

    private suspend fun exerciseClient(
        scenario: String, files: Observations, client: RpcClient,
        bridge: LocalMasterClientRpcBridgeV1, remote: PeerFingerprint,
    ) {
        val credentials = LocalGatewayCredentials(id(5), OpaqueCredential(ByteArray(52) { 9 }))
        val operationId = OperationId.random()
        val request = MutationRequest(operationId, CommandKey("dr.a09.command", 1),
            StoredBytes(ByteArray(64) { (it + 1).toByte() }))
        suspend fun observeExecute(): Long {
            files.publish("execute.observe", operationId.value)
            return files.await("execute.observed").toLong().also { assertTrue(it > 0) }
        }
        var consumerCalls = 0
        when (scenario) {
            "03" -> {
                val result = assertIs<MutationReply.Recorded>(bridge.execute(credentials, request, id(4)))
                assertEquals(operationId, result.operationId)
                assertEquals(id(4), result.originalActorId)
                assertTrue(result.committed)
                assertFalse(result.replayed)
                assertContentEquals(payload(), result.result.copyBytes())
                observeExecute()
            }
            "04" -> {
                val failure = TestAcceptanceFailure()
                val thrown = runCatching {
                    bridge.execute(credentials, request, id(4)) {
                        consumerCalls++
                        observeExecute()
                        throw failure
                    }
                }.exceptionOrNull()
                assertSame(failure, thrown)
                assertEquals(1, consumerCalls)
            }
            "05" -> coroutineScope {
                val started = CompletableDeferred<Unit>()
                val call = async {
                    bridge.execute(credentials, request, id(4)) {
                        consumerCalls++
                        observeExecute()
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
                withTimeout(10_000) { started.await() }
                call.cancel()
                assertIs<CancellationException>(runCatching { call.await() }.exceptionOrNull())
                assertEquals(1, consumerCalls)
                assertEquals(1L, client.requestTotals.value.cancelled)
            }
            "06-deny", "06-trust", "06-acceptance" -> {
                when (scenario) {
                    "06-deny" -> bridge.deny()
                    "06-trust" -> {
                        client.trust.revoke(remote)
                        withTimeout(10_000) { client.state.first { it != RpcConnectionState.Ready } }
                    }
                }
                if (scenario != "06-acceptance") assertFalse(bridge.isCurrent())
                val failure = runCatching {
                    bridge.execute(credentials, request, id(4)) {
                        consumerCalls++
                        observeExecute()
                        bridge.deny()
                    }
                }.exceptionOrNull()
                assertIs<IllegalStateException>(failure)
                assertFalse(bridge.isCurrent())
                assertEquals(if (scenario == "06-acceptance") 1 else 0, consumerCalls)
            }
            "07-actor", "07-operation" -> {
                val failure = runCatching {
                    bridge.execute(credentials, request, id(4)) { consumerCalls++ }
                }.exceptionOrNull()
                assertIs<SerializationException>(failure)
                assertEquals(0, consumerCalls)
                observeExecute()
            }
            "08" -> {
                val failure = runCatching {
                    bridge.execute(credentials, request, id(4)) { consumerCalls++ }
                }.exceptionOrNull()
                assertEquals(RpcFailureKind.UnknownProcedure, assertIs<RpcFailure>(failure).kind)
                assertEquals(0, consumerCalls)
            }
            else -> error("Unassigned frozen DR-A09 scenario: $scenario")
        }
        assertFalse(Files.exists(files.marker))
        files.publish("client.returned", scenario)
        if (scenario !in setOf("06-deny", "06-trust", "08")) files.await("execute.withheld")
        if (scenario == "04") {
            var recoveredCalls = 0
            var accepted: MutationReply.Recorded? = null
            val identity = OperationIdentity(branchId = id(3), operationId = operationId,
                originalActorId = id(4), semanticDigestVersion = 1, semanticSha256 = "a".repeat(64))
            val recovered = bridge.lookup(credentials, identity, id(4)) { recorded ->
                recoveredCalls++
                accepted = recorded
                files.publish("lookup.observe", recorded.operationId.value)
                assertTrue(files.await("lookup.observed").toLong() > 0)
                assertFalse(Files.exists(files.marker))
                writeDurableMarker(files.marker, recorded.operationId.value)
            }
            val found = assertIs<LookupReply.Found>(recovered)
            assertEquals(operationId, found.result.operationId)
            assertTrue(found.result.replayed)
            assertEquals(operationId, assertNotNull(accepted).operationId)
            assertEquals(1, recoveredCalls)
            assertEquals(operationId.value, Files.readString(files.marker))
            files.publish("client.recovered", operationId.value)
        }
        assertEquals(scenario, files.await("host.pass"))
        files.publish("client.pass", scenario)
    }

    private fun id(number: Int) = DomainId("00000001-0000-4000-8000-${number.toString(16).padStart(12, '0')}")
    private fun payload() = ByteArray(4096) { (it % 251).toByte() }
    private fun recorded(operationId: OperationId, replayed: Boolean = false) = LocalMasterRpcRecordedV1(
        operationId = operationId, originalActorId = id(4), committed = true, code = "committed",
        result = StoredBytes(payload()), authorityEpoch = Revision(1), sequence = JournalSequence(1),
        recordedAt = UtcInstant.parseCanonical("2026-11-09T00:00:00Z"), replayed = replayed,
    )

    private class Observations(val root: Path, private val role: String) {
        val marker: Path = root.resolve("durable.marker")
        fun publish(name: String, value: String) {
            val temporary = root.resolve("$name.$role.tmp")
            Files.writeString(temporary, value)
            Files.move(temporary, root.resolve(name), ATOMIC_MOVE)
        }
        suspend fun await(name: String): String = withTimeout(60_000) {
            val path = root.resolve(name)
            while (!Files.exists(path)) delay(25)
            Files.readString(path)
        }
    }
}
