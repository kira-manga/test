package com.ukkera.pos.database.independent.dra09officialrpc

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.*
import dev.p2pkit.transport.lan.OrganizationLan
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*

/**
 * Child executor for the existing frozen DR-A09-02. Both endpoints use the
 * ordinary official JVM factory in distinct network namespaces. The original
 * controlled identity, approval, response and trust inputs remain test DATA.
 */
internal object Dra09TcpBridgeEndpoint {
    @JvmStatic fun main(arguments: Array<String>): Unit = runBlocking {
        val (role, directory, iface, address) = arguments.toList()
        val root = Path.of(directory)
        fun publish(name: String, value: String) {
            val temporary = root.resolve("$name.$role.tmp")
            Files.writeString(temporary, value)
            Files.move(temporary, root.resolve(name), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
        publish("$role.boot", "booted")
        suspend fun await(name: String): String = withTimeout(60_000) {
            val path = root.resolve(name)
            while (!Files.exists(path)) delay(25)
            Files.readString(path)
        }
        await("network.ready")
        fun id(number: Int) = DomainId("00000001-0000-4000-8000-${number.toString(16).padStart(12, '0')}")
        val branch = BranchScope(id(1), id(2), id(3))
        val actor = id(4)
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
            publish("$role.identity", fingerprint.value)
            val remote = PeerFingerprint(await(if (role == "host") "client.identity" else "host.identity"))
            if (role == "host") {
                val executes = AtomicInteger()
                val lookups = AtomicInteger()
                trust.seed(appId, RpcTrustPurpose.HostClients, setOf(remote))
                val host = RpcHost.create(platform, scope) {
                    this.appId = appId; deviceName = "DR-A09 TCP $role"; this.lan = lan
                    trustStore = trust; advertise = false; requestHistoryCapacity = 16
                    register(LocalMasterClientRpcProtocolV1.EXECUTE, authorize = { true }) { _, request ->
                        executes.incrementAndGet()
                        RpcReply.Success(LocalMasterRpcExecuteResponseV1(actor,
                            LocalMasterRpcExecuteValueV1.Recorded(LocalMasterRpcRecordedV1(
                                operationId = request.request.operationId, originalActorId = actor,
                                committed = true, code = "committed",
                                result = StoredBytes(ByteArray(4096) { (it % 251).toByte() }),
                                authorityEpoch = Revision(1), sequence = JournalSequence(1),
                                recordedAt = UtcInstant.parseCanonical("2026-11-09T00:00:00Z"), replayed = false,
                            ))))
                    }
                    register(LocalMasterClientRpcProtocolV1.LOOKUP, authorize = { true }) { _, request ->
                        lookups.incrementAndGet()
                        RpcReply.Success(LocalMasterRpcLookupResponseV1(actor,
                            LocalMasterRpcLookupValueV1.Found(LocalMasterRpcRecordedV1(
                                operationId = request.identity.operationId, originalActorId = actor,
                                committed = true, code = "committed",
                                result = StoredBytes(ByteArray(4096) { (it % 251).toByte() }),
                                authorityEpoch = Revision(1), sequence = JournalSequence(1),
                                recordedAt = UtcInstant.parseCanonical("2026-11-09T00:00:00Z"), replayed = true,
                            ))))
                    }
                }
                try {
                    assertEquals(fingerprint, host.localIdentity.fingerprint)
                    val master = Dra09OfficialRpcFixture.captureControlledMaster(host.localIdentity, branch, ::id, 1)
                    Files.write(root.resolve("host.original"), master.copyWireBytes())
                    host.start()
                    host.state.first { it == RpcHostState.Running }
                    publish("host.port", host.endpoint().port.toString())
                    await("accepting")
                    val retained = withTimeout(5_000) {
                        host.diagnostics.first { it.retainedPayloadBytes > 0 }
                    }.retainedPayloadBytes
                    val entry = host.requests.value.entries.single()
                    assertEquals("pos.business.execute", entry.procedure)
                    assertEquals(1, entry.version)
                    assertEquals(RpcHostCallState.Succeeded, entry.state)
                    assertTrue(entry.requestId.isNotBlank())
                    assertTrue(host.requests.value.hostIncarnation.isNotBlank())
                    assertFalse(Files.exists(root.resolve("durable.marker")))
                    assertEquals(1, executes.get())
                    assertEquals(0, lookups.get())
                    publish("host.observed", retained.toString())
                    await("durable.marker")
                    withTimeout(5_000) { host.diagnostics.first { it.retainedPayloadBytes < retained } }
                    assertEquals(1L, host.requests.value.totals.accepted)
                    assertEquals(1L, host.diagnostics.value.completedCalls)
                    assertEquals(1, executes.get())
                    assertEquals(0, lookups.get())
                    publish("host.pass", "receipt-after-durable")
                    await("client.pass")
                } finally { host.close() }
            } else {
                trust.seed(appId, RpcTrustPurpose.SelectedHosts, setOf(remote))
                val client = RpcClient.create(platform, scope) {
                    this.appId = appId; deviceName = "DR-A09 TCP $role"; this.lan = lan
                    trustStore = trust; transportReconnect = false
                }
                try {
                    assertEquals(fingerprint, client.localIdentity.fingerprint)
                    val port = await("host.port").toInt()
                    val master = LocalMasterSdkOriginalV1.decode(Files.readAllBytes(root.resolve("host.original")))
                    assertEquals(remote, master.fingerprint)
                    client.connect(RpcSelectedHost(remote, RpcEndpoint("10.203.4.1", port)))
                    withTimeout(15_000) { client.state.first { it == RpcConnectionState.Ready } }
                    val bridge = Dra09OfficialRpcFixture.bindControlledBridge(
                        client, master, branch, lifetime, id(5), ::id,
                    )
                    val credentials = LocalGatewayCredentials(id(5), OpaqueCredential(ByteArray(52) { 9 }))
                    val operationId = OperationId.random()
                    val request = MutationRequest(operationId, CommandKey("dr.a09.command", 1),
                        StoredBytes(ByteArray(64) { (it + 1).toByte() }))
                    var accepted: MutationReply.Recorded? = null
                    var retainedAtAcceptance = 0L
                    val result = bridge.execute(credentials, request, actor) { recorded ->
                        accepted = recorded
                        publish("accepting", recorded.operationId.value)
                        retainedAtAcceptance = await("host.observed").toLong()
                        assertFalse(Files.exists(root.resolve("durable.marker")))
                        writeDurableMarker(root.resolve("durable.marker"), recorded.operationId.value)
                    }
                    val recorded = assertIs<MutationReply.Recorded>(result)
                    assertEquals(operationId, recorded.operationId)
                    assertEquals(actor, recorded.originalActorId)
                    assertTrue(recorded.committed)
                    assertEquals("committed", recorded.code)
                    assertFalse(recorded.replayed)
                    assertContentEquals(ByteArray(4096) { (it % 251).toByte() }, recorded.result.copyBytes())
                    assertEquals(operationId, assertNotNull(accepted).operationId)
                    assertTrue(retainedAtAcceptance > 0)
                    assertEquals(operationId.value, Files.readString(root.resolve("durable.marker")))
                    assertTrue(bridge.isCurrent())
                    await("host.pass")
                    assertEquals(1L, client.requestTotals.value.succeeded)
                    publish("client.pass", "exact-association")
                } finally { client.close() }
            }
        } finally { lifetime.cancel(); scope.cancel() }
    }
}
