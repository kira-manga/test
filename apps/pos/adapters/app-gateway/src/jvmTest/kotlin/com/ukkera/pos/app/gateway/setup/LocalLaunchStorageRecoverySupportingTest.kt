package com.ukkera.pos.app.gateway.setup

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Self-authored, NOT independent. Bounded supporting checks for frozen LAN-R03/R04/R05
 * (qa/pos/designs/lan-master-client-v1/cases.json) and OWNER-LAN-MASTER-CLIENT §7.
 * Real desktop files/atomic replace; shared gate covers Android's pre-AtomicFile decision.
 * No Android execution, genuine Master lifecycle, process crash or whole LAN case pass.
 */
class LocalLaunchStorageRecoverySupportingTest {
    @Test fun sharedGateAcceptsOnlyAbsenceOrOneSettledRecord() {
        assertFalse(LocalLaunchStorageNamespace.requireSettled(null))
        assertFalse(LocalLaunchStorageNamespace.requireSettled(emptyList()))
        assertTrue(LocalLaunchStorageNamespace.requireSettled(listOf("launch")))
        for (mask in 1..7) {
            val names = listOf("launch", "launch.new", "launch.bak").filterIndexed { index, _ -> mask and (1 shl index) != 0 }
            if (names != listOf("launch")) assertFails { LocalLaunchStorageNamespace.requireSettled(names) }
        }
        for (names in listOf(listOf("foreign"), listOf("launch", "foreign"), listOf("launch", "launch")))
            assertFails { LocalLaunchStorageNamespace.requireSettled(names) }
    }

    @Test fun bothNativeLaunchNamespacesPreserveEveryInterruptedCombinationBeforeReadOrWrite() {
        for (lan in listOf(false, true)) for (mask in 1..7) {
            if (mask == 1) continue
            val parent = Files.createTempDirectory("pos-launch-recovery-")
            try {
                val storages = DesktopSetupStorages(parent)
                val storage = if (lan) storages.lanLaunch else storages.launch
                val directory = privateDirectory(parent.resolve(if (lan) "ukkera.local-lan-launch.v1" else "ukkera.local-master-launch.v1"))
                val before = linkedMapOf<String, ByteArray>()
                listOf("launch", "launch.new", "launch.bak").forEachIndexed { index, name ->
                    if (mask and (1 shl index) != 0) {
                        val bytes = "retained-$name-$mask".encodeToByteArray()
                        Files.write(directory.resolve(name), bytes)
                        before[name] = bytes
                    }
                }
                assertFails { storage.read() }
                assertFails { storage.replaceAndForce("replacement-STOP".encodeToByteArray()) }
                assertFails { storage.replaceAndForce("replacement-RUN".encodeToByteArray()) }
                assertPreserved(directory, before)
                if (lan) {
                    val record = LocalLanLaunchRecord(storage)
                    assertEquals(LocalLanLaunchRecord.Read.Unresolved, record.read())
                    assertFails { record.retainStop() }
                } else {
                    val record = LocalMasterLaunchRecord(storage)
                    assertEquals(LocalMasterLaunchRecord.Read.Unresolved, record.read())
                    assertFails { record.retainStop() }
                }
                assertPreserved(directory, before)
            } finally { parent.toFile().deleteRecursively() }
        }
    }

    @Test fun foreignEntriesBlockReplacementWithoutChangingAnyBytes() {
        for (lan in listOf(false, true)) {
            val parent = Files.createTempDirectory("pos-launch-foreign-")
            try {
                val storages = DesktopSetupStorages(parent)
                val storage = if (lan) storages.lanLaunch else storages.launch
                storage.replaceAndForce("old-launch".encodeToByteArray())
                val directory = parent.resolve(if (lan) "ukkera.local-lan-launch.v1" else "ukkera.local-master-launch.v1")
                Files.write(directory.resolve("foreign"), "foreign-evidence".encodeToByteArray())
                val before = mapOf("launch" to "old-launch".encodeToByteArray(), "foreign" to "foreign-evidence".encodeToByteArray())
                assertFails { storage.read() }
                assertFails { storage.replaceAndForce("new-launch".encodeToByteArray()) }
                assertPreserved(directory, before)
            } finally { parent.toFile().deleteRecursively() }
        }
    }

    @Test fun settledStopSurvivesNewStorageOwnersWithoutChangingRoleFormat() {
        val parent = Files.createTempDirectory("pos-launch-stop-")
        try {
            val storages = DesktopSetupStorages(parent)
            assertNull(storages.launch.read())
            assertNull(storages.lanLaunch.read())
            LocalMasterLaunchRecord(storages.launch).retainStop()
            LocalLanLaunchRecord(storages.lanLaunch).retainStop()
            val master = storages.launch.read()
            val lan = storages.lanLaunch.read()
            val reopened = DesktopSetupStorages(parent)
            assertEquals(LocalMasterLaunchRecord.Read.Stopped, LocalMasterLaunchRecord(reopened.launch).read())
            assertEquals(LocalLanLaunchRecord.Read.Stopped, LocalLanLaunchRecord(reopened.lanLaunch).read())
            assertContentEquals(master, reopened.launch.read())
            assertContentEquals(lan, reopened.lanLaunch.read())
        } finally { parent.toFile().deleteRecursively() }
    }

    private fun privateDirectory(path: Path): Path = Files.createDirectory(path,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

    private fun assertPreserved(directory: Path, expected: Map<String, ByteArray>) {
        assertEquals(expected.keys, Files.newDirectoryStream(directory).use { entries -> entries.map { it.fileName.toString() }.toSet() })
        expected.forEach { (name, bytes) -> assertContentEquals(bytes, Files.readAllBytes(directory.resolve(name))) }
    }
}
