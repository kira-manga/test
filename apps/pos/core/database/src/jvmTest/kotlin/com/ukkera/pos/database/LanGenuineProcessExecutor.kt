package com.ukkera.pos.database

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * Explicit disposable-host executor. There is no skipped/pass variant: missing Linux namespace
 * capability is a failed executor prerequisite, and child failures retain all native originals.
 * This wires a bounded supporting subset of frozen LAN-S01/S02/A01 and records its limitations;
 * it does not author or replace their expectations, or publish full-case acceptance.
 */
internal object LanGenuineProcessExecutor {
    @JvmStatic fun main(arguments: Array<String>): Unit = runBlocking {
        check(System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true") {
            "Requires explicit disposable Linux network-namespace executor opt-in"
        }
        require(arguments.size == 1) { "Pass the frozen supporting input-mapping file" }
        val mapping = Path.of(arguments.single()).toRealPath()
        require(Files.size(mapping) in 1..65_536)
        val repository = mapping.parent.parent.parent
        val frozen = Json.parseToJsonElement(Files.readString(mapping)).jsonObject
        check(frozen.getValue("status").jsonPrimitive.content == "FROZEN_BEFORE_FIRST_EXECUTION")
        val paymentSupporting = java.lang.Boolean.getBoolean(LanGenuinePaymentSupport.ENABLE_PROPERTY)
        val paymentMapping = frozen["mapping_version"]?.jsonPrimitive?.content == LanGenuinePaymentSupport.MAPPING_VERSION
        check(paymentSupporting == paymentMapping) { "Payment support requires its exact separately versioned input mapping and explicit opt-in" }
        val extension = frozen["supporting_extension"]?.jsonPrimitive?.content
        require(extension == null || extension == LanGenuineBusinessSupport.MAPPING_VERSION || extension == LanGenuineStockSupport.MAPPING_VERSION)
        val stockSupporting = java.lang.Boolean.getBoolean(LanGenuineStockSupport.ENABLE_PROPERTY)
        check(stockSupporting == (extension == LanGenuineStockSupport.MAPPING_VERSION)) {
            "Stock support requires its exact separately versioned input mapping and explicit opt-in"
        }
        val businessSupporting = java.lang.Boolean.getBoolean(LanGenuineBusinessSupport.ENABLE_PROPERTY)
        check(businessSupporting == (extension == LanGenuineBusinessSupport.MAPPING_VERSION)) {
            "Business support requires its exact separately versioned input mapping and explicit opt-in"
        }
        check(listOf(paymentSupporting, businessSupporting, stockSupporting).count { it } <= 1) {
            "Select one separately mapped supporting extension"
        }
        val watchdog = frozen["watchdog_policy"]?.jsonObject
        val processWatchdog = watchdog?.getValue("process_millis")?.jsonPrimitive?.long ?: 240_000L
        val executorWatchdog = watchdog?.getValue("executor_millis")?.jsonPrimitive?.long ?: 300_000L
        val barrierWatchdog = watchdog?.get("barrier_millis")?.jsonPrimitive?.long ?: 180_000L
        require(barrierWatchdog in 180_000L..processWatchdog && (paymentSupporting || barrierWatchdog == 180_000L))
        require(processWatchdog in 240_000L..600_000L && executorWatchdog in (processWatchdog + 60_000L)..660_000L)
        val expectedInputs = setOf("qa/pos/designs/lan-master-client-v1/cases.json",
            "qa/pos/designs/lan-master-client-v1/fixtures.json", "qa/pos/designs/lan-master-client-v1/freeze.json")
        val inputs = frozen.getValue("independent_design_inputs").jsonObject
        check(inputs.keys == expectedInputs)
        for ((relative, digest) in inputs) check(com.ukkera.pos.security.sha256(
            Files.readAllBytes(repository.resolve(relative))).value == digest.jsonPrimitive.content) {
            "Frozen original input changed: $relative; preserve and investigate, never silently replace expectations"
        }
        val root = Files.createTempDirectory(if (paymentSupporting) Path.of(System.getProperty("user.home")) else Path.of("/var/tmp"), "pos-lan-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val observations = if (paymentSupporting) Files.createTempDirectory(Path.of("/var/tmp"), "pos-lan-payment-evidence-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) else root
        if (paymentSupporting) Files.writeString(observations.resolve("native-root.txt"), root.toString())
        val roles = listOf("master", "client1", "client2")
        roles.forEach { LanProcessNativeSupport.privateDirectory(root.resolve(it)) }
        if (paymentSupporting) roles.forEach { LanProcessNativeSupport.privateDirectory(observations.resolve(it)) }
        val exchange = LanFixtureExchange(root.resolve("exchange"))
        exchange.put("executor.frozen-input-mapping", Files.readAllBytes(mapping))
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = System.getProperty("ukkera.test.runtime.classpath") ?: System.getProperty("java.class.path")
        val suffix = ProcessHandle.current().pid().toString().takeLast(5)
        val hostEnds = listOf("lm1$suffix", "lm2$suffix")
        val clientEnds = listOf("lc1$suffix", "lc2$suffix")
        // Self-authored, NOT independent: optional finite continuity extension of the
        // unchanged supporting fixture. Default remains its original single commit.
        val stabilityRounds = System.getProperty("ukkera.lan.stabilityRounds", "1").toInt()
        require(stabilityRounds in 1..3)
        val singleNic = System.getProperty("ukkera.lan.singleNic", "false").toBoolean()
        var switchProcess: Process? = null
        val children = linkedMapOf<String, Process>()
        val createdHostLinks = mutableListOf<String>()
        println("Genuine LAN native root retained at $root; logs/provenance at $observations; Linux SOFTWARE OS stand-ins only")

        fun command(vararg args: String) {
            val child = ProcessBuilder(*args).redirectErrorStream(true).start()
            if (!child.waitFor(15, TimeUnit.SECONDS)) {
                child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS)
                error("Disposable network command timed out: ${args.firstOrNull()}")
            }
            check(child.exitValue() == 0) { "Disposable network command failed: ${child.inputStream.bufferedReader().readText()}" }
        }

        fun network(process: Process, vararg args: String) =
            command("nsenter", "-t", process.pid().toString(), "-n", "ip", *args)

        fun start(role: String, iface: String, address: String): Process = ProcessBuilder("unshare", "--net", "--",
            java, "-Xmx384m", "-XX:ActiveProcessorCount=2", "-Djava.io.tmpdir=/var/tmp",
            "-Dukkera.lan.verifierDiagnostics=true",
            "-Dukkera.lan.stabilityRounds=$stabilityRounds",
            "-Dukkera.lan.singleNic=$singleNic",
            "-D" + LanGenuineBusinessSupport.ENABLE_PROPERTY + "=" + businessSupporting,
            "-D" + LanGenuinePaymentSupport.ENABLE_PROPERTY + "=" + paymentSupporting,
            "-D" + LanGenuineStockSupport.ENABLE_PROPERTY + "=" + stockSupporting,
            "-Dukkera.lan.stockProfile=" + System.getProperty("ukkera.lan.stockProfile", "false"),
            "-Dukkera.lan.paymentEvidence=$observations",
            "-XX:FlightRecorderOptions=stackdepth=256",
            "-Dukkera.lan.profile=" + System.getProperty("ukkera.lan.profile", "false"), "-cp", classpath,
            LanGenuineProcessEndpoint::class.java.name, role, root.toString(), iface, address, processWatchdog.toString(), barrierWatchdog.toString())
            .redirectErrorStream(true).redirectOutput(observations.resolve("$role.log").toFile()).start()

        try {
            children["master"] = start("master", if (singleNic) "lm0$suffix" else "br0", "10.203.16.1")
            children["client1"] = start("client1", clientEnds[0], "10.203.16.2")
            children["client2"] = start("client2", clientEnds[1], "10.203.16.3")
            withTimeout(30_000L) {
                while (roles.any { exchange.read("$it.boot") == null }) {
                    check(children.values.all(Process::isAlive)) { "An independent JVM failed before network setup; see $root" }
                    delay(50)
                }
            }
            val host = checkNotNull(children["master"])
            if (singleNic) {
                // Self-authored supporting topology: only the isolated switch has bridge
                // slaves. Each genuine application namespace has exactly one LAN NIC,
                // satisfying the official SDK selector without disabling its strict checks.
                val switch = ProcessBuilder("unshare", "--net", "--", "sleep", "660")
                    .redirectErrorStream(true).redirectOutput(root.resolve("switch.log").toFile()).start()
                switchProcess = switch
                val ownNamespace = Files.readSymbolicLink(Path.of("/proc/self/ns/net"))
                withTimeout(5_000L) {
                    while (Files.readSymbolicLink(Path.of("/proc/${switch.pid()}/ns/net")) == ownNamespace) {
                        check(switch.isAlive)
                        delay(10)
                    }
                }
                network(switch, "link", "add", "br0", "type", "bridge")
                for (i in 0..2) {
                    val application = checkNotNull(children[roles[i]])
                    val applicationEnd = if (i == 0) "lm0$suffix" else clientEnds[i - 1]
                    val switchEnd = "ls$i$suffix"
                    command("ip", "link", "add", switchEnd, "type", "veth", "peer", "name", applicationEnd)
                    createdHostLinks += switchEnd
                    command("ip", "link", "set", switchEnd, "netns", switch.pid().toString())
                    command("ip", "link", "set", applicationEnd, "netns", application.pid().toString())
                    network(switch, "link", "set", switchEnd, "master", "br0")
                    network(switch, "link", "set", switchEnd, "up")
                    network(application, "addr", "add", "10.203.16.${i + 1}/24", "dev", applicationEnd)
                    network(application, "link", "set", applicationEnd, "up")
                    network(application, "link", "set", "lo", "up")
                }
                network(switch, "link", "set", "br0", "up")
                network(switch, "link", "set", "lo", "up")
            } else {
                network(host, "link", "add", "br0", "type", "bridge")
                for (i in 0..1) {
                    val client = checkNotNull(children["client${i + 1}"])
                    command("ip", "link", "add", hostEnds[i], "type", "veth", "peer", "name", clientEnds[i])
                    createdHostLinks += hostEnds[i]
                    // Both ends enter their final owned namespace; none connects to the host/WAN.
                    command("ip", "link", "set", hostEnds[i], "netns", host.pid().toString())
                    command("ip", "link", "set", clientEnds[i], "netns", client.pid().toString())
                    network(host, "link", "set", hostEnds[i], "master", "br0")
                    network(host, "link", "set", hostEnds[i], "up")
                    network(client, "addr", "add", "10.203.16.${i + 2}/24", "dev", clientEnds[i])
                    network(client, "link", "set", clientEnds[i], "up")
                    network(client, "link", "set", "lo", "up")
                }
                network(host, "addr", "add", "10.203.16.1/24", "dev", "br0")
                network(host, "link", "set", "br0", "up")
                network(host, "link", "set", "lo", "up")
            }
            exchange.putText("network.ready", "isolated-three-process-lan-no-wan-route")
            withTimeout(executorWatchdog) {
                while (children.values.any(Process::isAlive)) {
                    for ((role, child) in children) if (!child.isAlive)
                        check(child.exitValue() == 0) { "$role failed with exit ${child.exitValue()}; exact original/log retained at $root" }
                    delay(100)
                }
            }
            for ((role, child) in children) {
                check(child.exitValue() == 0 && exchange.read("$role.closed") != null) {
                    "$role did not confirm native/resource close; original/log retained at $root"
                }
            }
            exchange.putText("executor.completed", "supporting-slice-only;full-frozen-cases-not-run;owner-devices-not-run")
            println("Supporting genuine admission/auth/typed-read process executor completed. Full frozen LAN cases and physical-device acceptance remain NOT RUN.")
        } catch (failure: Throwable) {
            // Observational diagnostics only AFTER an already failed supporting run.
            // HotSpot SIGQUIT prints stacks, without changing the frozen inputs, original
            // request identity, RPC deadline, native ownership or the failed outcome.
            for (child in children.values.filter(Process::isAlive)) runCatching {
                val signal = ProcessBuilder("kill", "-QUIT", child.pid().toString()).start()
                if (!signal.waitFor(2, TimeUnit.SECONDS)) signal.destroyForcibly()
            }
            withContext(NonCancellable) { delay(500) }
            throw failure
        } finally {
            children.values.forEach { if (it.isAlive) it.destroyForcibly() }
            children.values.forEach { it.waitFor(5, TimeUnit.SECONDS) }
            switchProcess?.let { if (it.isAlive) it.destroyForcibly(); it.waitFor(5, TimeUnit.SECONDS) }
            // If setup failed before either end moved, remove only a veth this invocation
            // successfully created. Normal child namespace teardown has already removed it.
            for (name in createdHostLinks) {
                val cleanup = ProcessBuilder("ip", "link", "del", name).redirectErrorStream(true).start()
                if (!cleanup.waitFor(5, TimeUnit.SECONDS)) cleanup.destroyForcibly()
            }
            // Child-owned namespaces/veth disappear at final process exit. Preserve all role
            // roots and logs on both success/failure; an unknown outcome is not clean rollback.
            println("Original native files, generated test keys, logs and process observations retained at $root")
        }
    }
}
