package com.ukkera.pos.database

import com.ukkera.pos.database.independent.dra09officialrpc.Dra09MemoryIdentityStore
import com.ukkera.pos.database.independent.dra09officialrpc.Dra09MemoryTrustStore
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalMasterSdkProfileV1
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.*
import dev.p2pkit.transport.lan.OrganizationLan
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration.Companion.seconds

/**
 * Self-authored, NOT independent. LAN-SIR v1.0.0, frozen in LanSameIdentityReconnectSupporting.md
 * before first execution. Official RpcHost/RpcClient over real TCP in disposable namespaces.
 * Diagnoses same-identity reconnect after a silent partition; no POS production state is used.
 */
internal object LanSameIdentityReconnectSupportingExecutor {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        check(System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true")
        require(args.isEmpty())
        val root = Files.createTempDirectory(Path.of("/var/tmp"), "pos-lan-sir-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        println("LAN-SIR evidence retained at $root")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("ukkera.test.runtime.classpath") ?: System.getProperty("java.class.path")
        val suffix = ProcessHandle.current().pid().toString().takeLast(5)
        val roles = listOf("host", "client")
        val hostEnds = roles.indices.map { "ss$it$suffix" }
        val appEnds = roles.indices.map { "sa$it$suffix" }
        val processes = linkedMapOf<String, Process>()
        val created = mutableListOf<String>()
        fun command(vararg a: String) {
            val child = ProcessBuilder(*a).redirectErrorStream(true).start()
            if (!child.waitFor(15, TimeUnit.SECONDS)) { child.destroyForcibly(); error("Network command timed out") }
            check(child.exitValue() == 0) { "Network command failed: ${child.inputStream.bufferedReader().readText()}" }
        }
        fun ns(role: String, vararg a: String) =
            command("nsenter", "-t", checkNotNull(processes[role]).pid().toString(), "-n", "ip", *a)
        fun mark(name: String, value: String) {
            val temp = root.resolve("$name.executor.tmp"); Files.writeString(temp, value)
            Files.move(temp, root.resolve(name), StandardCopyOption.ATOMIC_MOVE)
        }
        suspend fun await(name: String, millis: Long = 180_000) = withTimeout(millis) {
            while (!Files.exists(root.resolve(name))) {
                for ((role, p) in processes) if (!p.isAlive) check(p.exitValue() == 0) { "$role failed; see $root" }
                delay(50)
            }
        }
        try {
            processes["switch"] = ProcessBuilder("unshare", "--net", "--", "sleep", "900")
                .redirectErrorStream(true).redirectOutput(root.resolve("switch.log").toFile()).start()
            for ((i, role) in roles.withIndex()) processes[role] = ProcessBuilder("unshare", "--net", "--", java,
                "-Xmx256m", "-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=/var/tmp", "-cp", classpath,
                LanSameIdentityReconnectSupportingEndpoint::class.java.name, role, root.toString(), appEnds[i],
                "10.203.18.${i + 1}").redirectErrorStream(true).redirectOutput(root.resolve("$role.log").toFile()).start()
            await("host.boot"); await("client.boot")
            ns("switch", "link", "add", "br0", "type", "bridge")
            for ((i, role) in roles.withIndex()) {
                command("ip", "link", "add", hostEnds[i], "type", "veth", "peer", "name", appEnds[i])
                created += hostEnds[i]
                command("ip", "link", "set", hostEnds[i], "netns", processes.getValue("switch").pid().toString())
                command("ip", "link", "set", appEnds[i], "netns", processes.getValue(role).pid().toString())
                ns("switch", "link", "set", hostEnds[i], "master", "br0")
                ns("switch", "link", "set", hostEnds[i], "up")
                ns(role, "addr", "add", "10.203.18.${i + 1}/24", "dev", appEnds[i])
                ns(role, "link", "set", appEnds[i], "up")
                ns(role, "link", "set", "lo", "up")
            }
            ns("switch", "link", "set", "br0", "up")
            mark("network.ready", "ready")
            await("client.partition.request")
            ns("switch", "link", "set", hostEnds[1], "down")
            mark("partitioned", System.currentTimeMillis().toString())
            await("client.restore.request")
            ns("switch", "link", "set", hostEnds[1], "up")
            mark("restored", System.currentTimeMillis().toString())
            await("client.pass", 240_000); await("host.pass", 30_000)
            for (role in roles) {
                val p = processes.getValue(role)
                check(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0) { "$role did not exit cleanly; see $root" }
            }
            println("LAN-SIR client: ${Files.readString(root.resolve("client.pass"))}")
            println("LAN-SIR host: ${Files.readString(root.resolve("host.pass"))}")
        } finally {
            processes.values.forEach { if (it.isAlive) it.destroyForcibly() }
            processes.values.forEach { it.waitFor(5, TimeUnit.SECONDS) }
            for (name in created) runCatching { ProcessBuilder("ip", "link", "del", name).start().waitFor(5, TimeUnit.SECONDS) }
            for (role in roles) root.resolve("$role.log").takeIf(Files::exists)?.let { println("--- $role.log\n${Files.readString(it)}") }
            println("LAN-SIR evidence retained at $root")
        }
    }
}

internal object LanSameIdentityReconnectSupportingEndpoint {
    private val probe = RpcProcedure("pos.sir.probe", 1, String.serializer(), String.serializer(), String.serializer())

    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        val (role, directory, iface, address) = args.toList()
        val root = Path.of(directory)
        val started = System.currentTimeMillis()
        fun log(text: String) = println("SIR $role +${System.currentTimeMillis() - started}ms $text")
        fun mark(name: String, value: String) {
            val temp = root.resolve("$name.$role.tmp"); Files.writeString(temp, value)
            Files.move(temp, root.resolve(name), StandardCopyOption.ATOMIC_MOVE)
        }
        suspend fun await(name: String): String = withTimeout(180_000) {
            val path = root.resolve(name)
            while (!Files.exists(path)) delay(25)
            Files.readString(path)
        }
        mark("$role.boot", "booted")
        await("network.ready")
        fun id(n: Int) = DomainId("00000001-0000-4000-8000-${n.toString().padStart(12, '0')}")
        val appId = LocalMasterSdkProfileV1.appId(BranchScope(id(1), id(2), id(3)))
        val identities = Dra09MemoryIdentityStore()
        val platform = RpcPlatform.jvm(identities)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val trust = Dra09MemoryTrustStore()
        val lan = OrganizationLan(listOf("10.203.18.0/24"), iface, address)
        suspend fun newClient(): RpcClient = RpcClient.create(platform, scope) {
            this.appId = appId; deviceName = "SIR client"; this.lan = lan
            trustStore = trust; transportReconnect = false
        }
        try {
            val initial = newClient()
            val fingerprint = initial.localIdentity.fingerprint
            initial.close()
            mark("$role.identity", fingerprint.value)
            val remote = PeerFingerprint(await(if (role == "host") "client.identity" else "host.identity"))
            if (role == "host") {
                trust.seed(appId, RpcTrustPurpose.HostClients, setOf(remote))
                val host = RpcHost.create(platform, scope) {
                    this.appId = appId; deviceName = "SIR host"; this.lan = lan
                    trustStore = trust; advertise = false; requestHistoryCapacity = 16
                    register(probe, authorize = { true }) { _, value -> RpcReply.Success(value) }
                }
                try {
                    host.start()
                    host.state.first { it == RpcHostState.Running }
                    // Observation only: fixed state names and generations, never identities.
                    val watcher = scope.launch {
                        host.connections.collect { list ->
                            log("HOST connections=" + list.joinToString(",") { "${it.state.name}#${it.generation}" })
                        }
                    }
                    mark("host.port", host.endpoint().port.toString())
                    await("client.pass")
                    watcher.cancelAndJoin()
                    mark("host.pass", "observed")
                } finally { host.close() }
            } else {
                trust.seed(appId, RpcTrustPurpose.SelectedHosts, setOf(remote))
                val selected = RpcSelectedHost(remote, RpcEndpoint("10.203.18.1", await("host.port").toInt()))
                suspend fun call(client: RpcClient, timeoutSeconds: Int): String = try {
                    val reply = client.call(probe, "probe", timeout = timeoutSeconds.seconds, retry = RpcRetry.RecoverOnly(maxAttempts = 1))
                    if (reply is RpcReply.Success<*>) "Success" else "Reply:${reply::class.simpleName}"
                } catch (f: RpcFailure) { "${f.kind.name}/${f.phase.name}/${f.executionEvidence.name}" }
                var connectSequence = 0
                suspend fun connect(client: RpcClient): String = recordLanConnectExceptions(root,
                    "client-connect-${++connectSequence}.jfr") { try {
                    client.connect(selected)
                    withTimeout(15_000) { client.state.first { it == RpcConnectionState.Ready } }
                    "Ready"
                } catch (f: RpcFailure) { "${f.kind.name}/${f.phase.name}/${f.executionEvidence.name}" }
                catch (f: TimeoutCancellationException) { "ReadyTimeout" } }
                val results = linkedMapOf<String, String>()
                val a = newClient()
                results["A.connect"] = connect(a)
                results["A.probe"] = call(a, 10)
                log("A ${results["A.connect"]} ${results["A.probe"]}")
                val b = newClient()
                results["E1.B.connect"] = connect(b)
                b.close()
                results["E1.A.probeAfter"] = call(a, 10)
                log("E1 ${results["E1.B.connect"]} A=${results["E1.A.probeAfter"]}")
                mark("client.partition.request", "1")
                await("partitioned")
                val partitionStarted = System.nanoTime()
                results["partition.A.probe"] = call(a, 3)
                // The failed probe need not consume its full 3s timeout. Preserve the
                // frozen 14s link-down input instead of assuming 3s + a fixed 11s wait.
                delay((14_000L - (System.nanoTime() - partitionStarted) / 1_000_000).coerceAtLeast(0L))
                mark("client.restore.request", "1")
                val restored = await("restored").toLong()
                a.close()
                var current = newClient()
                results["E2.immediate"] = connect(current)
                results["E2.elapsedMs"] = (System.currentTimeMillis() - restored).toString()
                log("E2 ${results["E2.immediate"]} after ${results["E2.elapsedMs"]}ms")
                var attempt = 0
                var ready = results["E2.immediate"] == "Ready"
                for (wait in listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)) {
                    if (ready) break
                    current.close()
                    delay(wait)
                    attempt++
                    current = newClient()
                    val outcome = connect(current)
                    val elapsed = System.currentTimeMillis() - restored
                    results["E3.attempt$attempt"] = "$outcome@${elapsed}ms"
                    log("E3 attempt$attempt $outcome after ${elapsed}ms")
                    ready = outcome == "Ready"
                }
                results["final.probe"] = call(current, 10)
                current.close()
                val summary = results.entries.joinToString("; ") { "${it.key}=${it.value}" }
                log("SUMMARY $summary")
                mark("client.pass", summary)
            }
        } finally { scope.cancel() }
    }
}
