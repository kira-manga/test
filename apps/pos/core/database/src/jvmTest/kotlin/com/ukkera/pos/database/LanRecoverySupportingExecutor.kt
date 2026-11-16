package com.ukkera.pos.database

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Self-authored, NOT independent. Explicit disposable Linux process/TCP recovery support.
 * Namespace keepers own networking only; every application stage is a separate fresh JVM.
 * All actual production originals persist in the SAME role roots; failure never resets them.
 * See LanRecoverySupporting.md. This is not a complete frozen-case executor or a native OS pass.
 */
internal object LanRecoverySupportingExecutor {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        check(System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true")
        require(args.isEmpty())
        val root = Files.createTempDirectory(Path.of("/var/tmp"), "pos-lan-recovery-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val roles = listOf("master", "client1", "client2")
        roles.forEach { LanProcessNativeSupport.privateDirectory(root.resolve(it)) }
        val exchange = LanFixtureExchange(root.resolve("exchange"))
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("ukkera.test.runtime.classpath") ?: System.getProperty("java.class.path")
        val keepers = linkedMapOf<String, Process>()
        val workers = linkedMapOf<String, Process>()
        val suffix = ProcessHandle.current().pid().toString().takeLast(5)
        // The SDK requires exactly one active non-loopback interface. Keep bridge/slave
        // interfaces in a NETWORK-ONLY namespace, never an application namespace. A JVM
        // started after IPv6 link-local assignment observes those slaves as extra egress.
        val namespaceRoles = roles + "switch"
        val hostEnds = roles.indices.map { "rs$it$suffix" }
        val applicationEnds = roles.indices.map { "ra$it$suffix" }
        val created = mutableListOf<String>()
        println("Recovery supporting originals/logs retained at $root; Linux software OS/key stand-ins")

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
                "-Dukkera.lan.verifierDiagnostics=true", "-cp", classpath,
                LanRecoverySupportingEndpoint::class.java.name, role, stage, root.toString(), iface, "10.203.17.${index + 1}"))
                .redirectErrorStream(true).redirectOutput(root.resolve("$role.$stage.log").toFile()).start()
        }
        fun healthy() {
            for ((name, child) in workers) if (!child.isAlive) check(child.exitValue() == 0) {
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
                network(role, "addr", "add", "10.203.17.${i + 1}/24", "dev", applicationEnds[i])
                network(role, "link", "set", applicationEnds[i], "up")
                network(role, "link", "set", "lo", "up")
            }
            network("switch", "link", "set", "br0", "up")
            network("switch", "link", "set", "lo", "up")
            withTimeout(1_200_000L) {
                roles.forEach { workers["$it.initial"] = start(it, "initial") }
                await("client1.initial.ready"); await("client2.initial.ready")
                network("switch", "link", "set", hostEnds[1], "down")
                exchange.putText("client1.partitioned", "one-client-veth-down-master-and-client2-stay-up")
                await("partition.restore-permitted")
                network("switch", "link", "set", hostEnds[1], "up")
                exchange.putText("partition.restored", "same-physical-LAN")
                closed("client1", "initial")
                await("client1.restart.start")
                workers["client1.client-restart"] = start("client1", "client-restart")
                closed("client1", "client-restart")
                closed("client2", "initial"); closed("master", "initial")
                roles.forEach { workers["$it.master-restart"] = start(it, "master-restart") }
                roles.forEach { closed(it, "master-restart") }
            }
            exchange.putText("executor.completed", "supporting-recovery-only;whole-frozen-cases-and-native-platforms-NOT-RUN")
            println("Supporting partition/reconnect, Client restart, same-Master restart, fresh authentication, coherent catch-up and original lookup completed")
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
            println("Original native files, generated test keys and all success/failure evidence remain at $root")
        }
    }
}
