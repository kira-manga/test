package com.ukkera.pos.database.independent.dra09officialrpc

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.*
import dev.p2pkit.transport.lan.OrganizationLan
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.serializer
import org.junit.Assume.assumeTrue
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Wires the already frozen DR-A09-01 association/retention/Receipt assertions to
 * genuine official JVM LAN sockets in two isolated network namespaces. Synthetic
 * memory identity stores and out-of-band trust pins remain test-only inputs.
 * Explicit disposable-host opt-in; no native custody or POS enrollment claim.
 */
class Dra09TcpNamespaceSupportingTest {
    @Test fun dra0901OverTwoGenuineTcpEndpoints() = runBlocking {
        assumeTrue("Explicit disposable Linux namespace executor required",
            System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true")
        val root = Files.createTempDirectory("dra09-tcp-")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = checkNotNull(System.getProperty("ukkera.test.runtime.classpath"))
        val children = mutableListOf<Process>()
        val suffix = ProcessHandle.current().pid().toString().takeLast(6)
        val hostInterface = "drh$suffix"
        val clientInterface = "drc$suffix"
        fun command(vararg arguments: String) {
            val process = ProcessBuilder(*arguments).redirectErrorStream(true).start()
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Namespace command timed out" }
            check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
        }
        fun start(role: String, iface: String, address: String): Process = ProcessBuilder(
            "unshare", "--net", "--", java, "-Xmx128m", "-XX:ActiveProcessorCount=2",
            "-cp", classpath, Dra09TcpEndpoint::class.java.name,
            role, root.toString(), iface, address,
        ).redirectErrorStream(true).redirectOutput(root.resolve("$role.log").toFile()).start()
        try {
            val host = start("host", hostInterface, "10.203.4.1").also(children::add)
            val client = start("client", clientInterface, "10.203.4.2").also(children::add)
            withTimeout(30_000) {
                while (!Files.exists(root.resolve("host.boot")) || !Files.exists(root.resolve("client.boot"))) {
                    check(children.all(Process::isAlive)) { "Endpoint failed before network setup" }
                    delay(50)
                }
            }
            command("ip", "link", "add", hostInterface, "type", "veth", "peer", "name", clientInterface)
            command("ip", "link", "set", hostInterface, "netns", host.pid().toString())
            command("ip", "link", "set", clientInterface, "netns", client.pid().toString())
            for ((process, iface, address) in listOf(Triple(host, hostInterface, "10.203.4.1/30"),
                Triple(client, clientInterface, "10.203.4.2/30"))) {
                command("nsenter", "-t", process.pid().toString(), "-n", "ip", "addr", "add", address, "dev", iface)
                command("nsenter", "-t", process.pid().toString(), "-n", "ip", "link", "set", iface, "up")
                command("nsenter", "-t", process.pid().toString(), "-n", "ip", "link", "set", "lo", "up")
            }
            Files.writeString(root.resolve("network.ready"), "ready")
            for ((index, process) in children.withIndex()) {
                assertTrue(process.waitFor(90, TimeUnit.SECONDS), "TCP endpoint timed out")
                val role = if (index == 0) "host" else "client"
                assertEquals(0, process.exitValue(), Files.readString(root.resolve("$role.log")))
            }
            assertEquals("receipt-after-durable", Files.readString(root.resolve("host.pass")))
            assertEquals("exact-association", Files.readString(root.resolve("client.pass")))
        } finally {
            children.forEach { if (it.isAlive) it.destroyForcibly() }
            children.forEach { it.waitFor(5, TimeUnit.SECONDS) }
            // Each endpoint's namespace and veth disappear with its final process.
            // Retain logs on failure; never remove unrelated host interfaces.
            println("DR-A09 disposable TCP diagnostics: $root")
            for (role in listOf("host", "client")) {
                val log = root.resolve("$role.log")
                if (Files.exists(log)) println("$role: ${Files.readString(log)}")
            }
        }
    }
}

/** Child entry point only. No production factory, provider or transport SPI override. */
internal object Dra09TcpEndpoint {
    @JvmStatic fun main(arguments: Array<String>): Unit = runBlocking {
        val (role, directory, iface, address) = arguments.toList()
        val root = Path.of(directory)
        Files.writeString(root.resolve("$role.boot"), "booted")
        suspend fun await(name: String): String = withTimeout(60_000) {
            val path = root.resolve(name)
            while (!Files.exists(path)) delay(25)
            Files.readString(path)
        }
        await("network.ready")
        fun id(number: Int) = DomainId("00000001-0000-4000-8000-${number.toString().padStart(12, '0')}")
        val appId = LocalMasterSdkProfileV1.appId(BranchScope(id(1), id(2), id(3)))
        val platform = RpcPlatform.jvm(Dra09MemoryIdentityStore())
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val trust = Dra09MemoryTrustStore()
        val lan = OrganizationLan(listOf("10.203.4.0/30"), iface, address)
        val probe = RpcProcedure("dr.a09.probe", 1, String.serializer(), String.serializer(), String.serializer())
        try {
            val initial = RpcClient.create(platform, scope) {
                this.appId = appId; deviceName = "DR-A09 TCP $role"; this.lan = lan
                trustStore = trust; transportReconnect = false
            }
            val fingerprint = initial.localIdentity.fingerprint
            initial.close()
            Files.writeString(root.resolve("$role.identity"), fingerprint.value)
            val remote = PeerFingerprint(await(if (role == "host") "client.identity" else "host.identity"))
            if (role == "host") {
                trust.seed(appId, RpcTrustPurpose.HostClients, setOf(remote))
                val host = RpcHost.create(platform, scope) {
                    this.appId = appId; deviceName = "DR-A09 TCP $role"; this.lan = lan
                    trustStore = trust; advertise = false; requestHistoryCapacity = 16
                    register(probe, authorize = { true }) { _, value -> RpcReply.Success(value) }
                }
                try {
                    assertEquals(fingerprint, host.localIdentity.fingerprint)
                    host.start()
                    host.state.first { it == RpcHostState.Running }
                    Files.writeString(root.resolve("host.port"), host.endpoint().port.toString())
                    await("accepting")
                    val retained = withTimeout(10_000) { host.diagnostics.first { it.retainedPayloadBytes > 0 } }.retainedPayloadBytes
                    val entry = host.requests.value.entries.single()
                    assertEquals(probe.name, entry.procedure); assertEquals(probe.version, entry.version)
                    assertEquals(RpcHostCallState.Succeeded, entry.state)
                    assertEquals(await("accepting"), entry.requestId)
                    assertFalse(Files.exists(root.resolve("durable.marker")))
                    Files.writeString(root.resolve("host.observed"), host.requests.value.hostIncarnation)
                    await("durable.marker")
                    withTimeout(10_000) { host.diagnostics.first { it.retainedPayloadBytes < retained } }
                    assertEquals(1L, host.requests.value.totals.accepted)
                    assertEquals(1L, host.requests.value.totals.succeeded)
                    Files.writeString(root.resolve("host.pass"), "receipt-after-durable")
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
                    client.connect(RpcSelectedHost(remote, RpcEndpoint("10.203.4.1", await("host.port").toInt())))
                    withTimeout(15_000) { client.state.first { it == RpcConnectionState.Ready } }
                    val details = client.callWithAcceptance(probe, "probe-request", timeout = 15.seconds,
                        retry = RpcRetry.RecoverOnly(maxAttempts = 1)) { acceptance ->
                        assertEquals(remote, acceptance.hostFingerprint)
                        assertTrue(acceptance.hostIncarnation.isNotBlank())
                        assertEquals(RpcExecutionEvidence.HandlerFinished, acceptance.details.executionEvidence)
                        Files.writeString(root.resolve("accepting"), acceptance.details.requestId.value)
                        assertEquals(await("host.observed"), acceptance.hostIncarnation)
                        writeDurableMarker(root.resolve("durable.marker"), acceptance.details.requestId.value)
                    }
                    assertEquals("probe-request", assertIs<RpcReply.Success<String>>(details.reply).value)
                    await("host.pass")
                    Files.writeString(root.resolve("client.pass"), "exact-association")
                } finally { client.close() }
            }
        } finally { scope.cancel() }
    }
}
