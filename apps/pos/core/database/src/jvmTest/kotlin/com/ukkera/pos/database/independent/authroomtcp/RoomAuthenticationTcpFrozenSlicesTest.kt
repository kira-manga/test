package com.ukkera.pos.database

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Executor wiring of auth-v1 I005/I009 and STC-S01/S09/S10 observables, not new
 * independent cases. Production authentication codecs, official strict LAN TCP,
 * and the actual grouped Room service run together. A TEST-ONLY handler maps two
 * pinned SDK peers to the existing fixture's synthetic admissions. This does not
 * exercise LocalRpcMasterAuthenticationIngress, the native peer registry, the
 * remote authentication owner, installed UI retirement, or full frozen cases. */
class RoomAuthenticationTcpFrozenSlicesTest {
    @Test fun stcS01_loginCurrentLogoutOverTcpAndRoom() = run("logout")
    @Test fun stcS09_refreshRotationOverTcpAndRoom() = run("refresh")
    @Test fun authV1I009_stcS10_explicitMoveOverTcpAndRoom() = run("move")
    @Test fun authV1_refreshWrongDeviceOverTcpAndRoom() = run("wrong-device")
    @Test fun rempR04_frozenPasswordDelayScheduleOverTcpAndRoom() = run("throttle")
    @Test fun rempR03_unavailablePepperCannotMatchOrResetProtectionOverTcpAndRoom() = run("verifier-unavailable")

    @Test fun rempR05_samePhysicalSuccessorBindingAndRetainedWorkLineage() = run("renewal")

    private fun run(scenario: String): Unit = runBlocking {
        assumeTrue("Explicit disposable Linux namespace executor required",
            System.getenv("POS_DISPOSABLE_TCP_NAMESPACE") == "true")
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val root = Files.createTempDirectory("pos-auth-room-tcp-", permissions)
        val suffix = UUID.randomUUID().toString().replace("-", "").take(9)
        val hostInterface = "arh$suffix"
        val clientInterface = "arc$suffix"
        Files.writeString(root.resolve("network-inputs.txt"), "$hostInterface\n$clientInterface\n")
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val classpath = checkNotNull(System.getProperty("ukkera.test.runtime.classpath"))
        val children = mutableListOf<Process>()
        var createdInterfaceIndex: Int? = null
        var hostInterfaceMoved = false
        fun command(vararg args: String): String {
            val process = ProcessBuilder(*args).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Namespace command timed out")
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(0, process.exitValue(), output)
                return output
            } finally {
                if (process.isAlive) process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
        fun interfaceIndex(name: String): Int? = Json.parseToJsonElement(command("ip", "-j", "link", "show"))
            .jsonArray.map { it.jsonObject }.singleOrNull { it.getValue("ifname").jsonPrimitive.content == name }
            ?.getValue("ifindex")?.jsonPrimitive?.int
        fun start(role: String, iface: String, address: String): Process = ProcessBuilder(
            "unshare", "--net", "--", java, "-Xmx128m", "-XX:ActiveProcessorCount=2",
            "-cp", classpath, RoomAuthenticationTcpEndpoint::class.java.name,
            role, root.toString(), iface, address, scenario,
        ).apply {
            // Launcher banners can pollute retained diagnostics; these test children
            // already carry their explicit bounds. No unrelated process is changed.
            environment().remove("JAVA_TOOL_OPTIONS")
            environment().remove("JDK_JAVA_OPTIONS")
            environment().remove("_JAVA_OPTIONS")
        }.redirectErrorStream(true).redirectOutput(root.resolve("$role.log").toFile()).start()
        try {
            val host = start("host", hostInterface, "10.203.5.1").also(children::add)
            val client = start("client", clientInterface, "10.203.5.2").also(children::add)
            withTimeout(30_000) {
                while (!Files.exists(root.resolve("host.boot")) || !Files.exists(root.resolve("client.boot"))) {
                    check(children.all(Process::isAlive)) { "Endpoint failed before network setup" }
                    delay(25)
                }
            }
            command("ip", "link", "add", hostInterface, "type", "veth", "peer", "name", clientInterface)
            // Read the actual namespace's native link census. Java's address-oriented
            // interface lookup can omit a newly created, unconfigured link.
            createdInterfaceIndex = checkNotNull(interfaceIndex(hostInterface))
            Files.writeString(root.resolve("network-host-ifindex.txt"), createdInterfaceIndex.toString())
            command("ip", "link", "set", hostInterface, "netns", host.pid().toString())
            hostInterfaceMoved = true
            command("ip", "link", "set", clientInterface, "netns", client.pid().toString())
            for ((child, iface, address) in listOf(Triple(host, hostInterface, "10.203.5.1/30"),
                Triple(client, clientInterface, "10.203.5.2/30"))) {
                command("nsenter", "-t", child.pid().toString(), "-n", "ip", "addr", "add", address, "dev", iface)
                command("nsenter", "-t", child.pid().toString(), "-n", "ip", "link", "set", iface, "up")
                command("nsenter", "-t", child.pid().toString(), "-n", "ip", "link", "set", "lo", "up")
            }
            Files.writeString(root.resolve("network.ready"), "ready")
            for ((index, child) in children.withIndex()) {
                assertTrue(child.waitFor(120, TimeUnit.SECONDS), "Authentication endpoint timed out")
                val role = if (index == 0) "host" else "client"
                assertEquals(0, child.exitValue(), Files.readString(root.resolve("$role.log")))
                assertEquals(scenario, Files.readString(root.resolve("$role.pass")))
            }
        } finally {
            // Only these exact disposable child processes belong to this executor.
            children.forEach { if (it.isAlive) it.destroyForcibly() }
            children.forEach { it.waitFor(5, TimeUnit.SECONDS) }
            // If setup failed before moving the created pair, dispose only the
            // exact interface index created by this invocation in the parent namespace.
            val originalIndex = createdInterfaceIndex
            if (!hostInterfaceMoved && originalIndex != null &&
                interfaceIndex(hostInterface) == originalIndex) {
                command("ip", "link", "delete", hostInterface)
            }
            println("Controlled Room/TCP authentication diagnostics: $root")
            for (role in listOf("host", "client")) {
                val log = root.resolve("$role.log")
                if (Files.exists(log)) println("$role: ${Files.readString(log)}")
            }
        }
    }
}
