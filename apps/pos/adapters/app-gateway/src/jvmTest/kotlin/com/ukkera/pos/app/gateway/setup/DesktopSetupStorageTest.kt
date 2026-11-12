package com.ukkera.pos.app.gateway.setup

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Self-authored (NOT independent) checks of the desktop setup record primitives. */
class DesktopSetupStorageTest {
    private fun storages() = DesktopSetupStorages(Files.createTempDirectory("ukkera-setup-test"))

    @Test fun intentsAreExclusiveAndExactExistingOnly() {
        val intents = storages().freshTarget
        assertNull(intents.names())
        intents.writeExclusive("a.intent", byteArrayOf(1, 2), allowExactExisting = false)
        intents.writeExclusive("a.intent", byteArrayOf(1, 2), allowExactExisting = true)
        assertFailsWith<Throwable> { intents.writeExclusive("a.intent", byteArrayOf(1, 3), allowExactExisting = true) }
        assertFailsWith<Throwable> { intents.writeExclusive("a.intent", byteArrayOf(1, 2), allowExactExisting = false) }
        assertEquals(listOf("a.intent"), intents.names())
        assertContentEquals(byteArrayOf(1, 2), intents.read("a.intent"))
        assertFailsWith<IllegalArgumentException> { intents.writeExclusive("../x", byteArrayOf(1), false) }
    }

    @Test fun launchRecordReplacesAndRefusesInterruptedWrite() {
        val parent = Files.createTempDirectory("ukkera-setup-test")
        val launch = DesktopSetupStorages(parent).launch
        assertNull(launch.read())
        launch.replaceAndForce(byteArrayOf(7)); launch.replaceAndForce(byteArrayOf(8))
        assertContentEquals(byteArrayOf(8), launch.read())
        Files.write(parent.resolve("ukkera.local-master-launch.v1").resolve("launch.new"), byteArrayOf(9))
        assertFailsWith<IllegalStateException> { launch.read() }
    }

    @Test fun lifecycleRecoversOnlyExactInterruptedBytes() {
        val parent = Files.createTempDirectory("ukkera-setup-test")
        val lifecycle = DesktopSetupStorages(parent).lifecycle
        lifecycle.replaceAndForce(byteArrayOf(1))
        val dir = parent.resolve("ukkera.local-master-lifecycle-intent.v1")
        Files.write(dir.resolve("intent.new"), byteArrayOf(2))
        assertContentEquals(byteArrayOf(2), lifecycle.readInterruptedWrite())
        assertFailsWith<IllegalArgumentException> { lifecycle.commitRecovered(byteArrayOf(3)) }
        lifecycle.commitRecovered(byteArrayOf(2))
        assertContentEquals(byteArrayOf(2), lifecycle.read())
        lifecycle.clearAndForce()
        assertNull(lifecycle.read())
    }

    @Test fun foreignPermissionsAreRefused() {
        val parent = Files.createTempDirectory("ukkera-setup-test")
        val intents = DesktopSetupStorages(parent).employee
        intents.writeExclusive("a", byteArrayOf(1), false)
        Files.setPosixFilePermissions(parent.resolve("ukkera.employee-enrollment-setup-ui.v1"), PosixFilePermissions.fromString("rwxr-x---"))
        assertFailsWith<IllegalStateException> { intents.names() }
    }
}
