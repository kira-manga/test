package com.ukkera.pos.recovery.store

import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Master full-state backup service over the real JCA AES-GCM custody and file vault, with
 * synthetic snapshot bytes. Self-authored, NOT independent; a fake restore target stands in for
 * the SQLite-observing target. Expectations were fixed before running.
 */
class MasterStateBackupServiceTest {
    private val root = Files.createTempDirectory("ukmsb-test").toFile()
    private val installation = DomainId.random()
    private val approvalId = DomainId.random()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private var now = 1_000_000L
    private var generation = 1L

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private fun snapshot(bytes: ByteArray) = object : MasterStateSnapshot {
        override val facts = MasterStateCaptureFacts(DomainId.random(), DomainId.random(), DomainId.random(), installation,
            7, "0123456789abcdef0123456789abcdef", generation, sha(byteArrayOf(1)), bytes.size.toLong(), sha(bytes))
        override suspend fun read(offset: Long, max: Int): ByteArray =
            bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + max))
        override suspend fun discard() = Unit
    }

    private val content = ByteArray(10_000) { (it * 31).toByte() }
    private val approved = mutableSetOf<DomainId>()

    private fun service(keys: MasterBackupKeyCustody = JcaAesGcmMasterBackupKeyCustody("test:key") { key },
        source: MasterStateSnapshotSource? = MasterStateSnapshotSource { snapshot(content) }) = MasterStateBackupService(
        MasterStateBackupConfiguration(retainLatest = 2, scheduleIntervalMillis = 60_000L, chunkBytes = 4096),
        source, keys, FileMasterBackupVault(File(root, "archives")),
        { approval, _, id -> approval.approvalId in approved && approval.installationId == id }, { now })

    private class FakeTarget : MasterRestoreTarget {
        val staged = java.io.ByteArrayOutputStream()
        var held: ByteArray? = null
        var quarantined: MasterRestoreRefusal? = null
        override suspend fun condition() = if (held != null) MasterRestoreTargetCondition.OCCUPIED else MasterRestoreTargetCondition.FRESH
        override suspend fun beginStage(backupId: DomainId) = object : MasterRestoreStage {
            override suspend fun append(bytes: ByteArray) { staged.write(bytes) }
            override suspend fun observe(): MasterRestoredObservation {
                val bytes = staged.toByteArray()
                return MasterRestoredObservation(bytes.size.toLong(), sha(bytes), true, 7, "0123456789abcdef0123456789abcdef")
            }
            override suspend fun installHeld(heldRecord: ByteArray) { held = heldRecord }
            override suspend fun quarantine(reason: MasterRestoreRefusal) { quarantined = reason }
            override suspend fun abort() { staged.reset() }
        }
    }

    @Test fun createsVerifiesAndChainsArchives(): Unit = runBlocking {
        val backups = service()
        val first = assertIs<MasterBackupOutcome.Created>(backups.createBackup())
        assertEquals(1L, first.entry.sequence)
        now += 1_000; generation++
        val second = assertIs<MasterBackupOutcome.Created>(backups.createBackup())
        assertEquals(2L, second.entry.sequence)
        val report = backups.verifyChain()
        assertTrue(report.intact)
        assertEquals(2, report.entries.size)
        assertIs<MasterBackupVerification.Verified>(backups.verifyBackup(first.entry.backupId))
        // Archive bytes are encrypted: the synthetic plaintext pattern never appears.
        val archive = File(root, "archives/${first.entry.backupId.value}.ukmsb").readBytes()
        assertFalse(String(archive, Charsets.ISO_8859_1).contains(String(content.copyOfRange(0, 64), Charsets.ISO_8859_1)))
    }

    @Test fun retentionKeepsNewestAndScheduleRespectsInterval(): Unit = runBlocking {
        val backups = service()
        repeat(3) { now += 1_000; generation++; assertIs<MasterBackupOutcome.Created>(backups.createBackup()) }
        assertEquals(listOf(2L, 3L), backups.verifyChain().entries.map { it.sequence })
        now += 30_000
        assertEquals(MasterBackupOutcome.NotDue, backups.runScheduledIfDue())
        now += 60_000
        assertIs<MasterBackupOutcome.Created>(backups.runScheduledIfDue())
    }

    @Test fun tamperedArchiveIsRefusedAndBreaksChain(): Unit = runBlocking {
        val backups = service()
        val created = assertIs<MasterBackupOutcome.Created>(backups.createBackup())
        RandomAccessFile(File(root, "archives/${created.entry.backupId.value}.ukmsb"), "rw").use { file ->
            file.seek(file.length() - 20); val b = file.read(); file.seek(file.length() - 20); file.write(b xor 0x01)
        }
        assertIs<MasterBackupVerification.Refused>(backups.verifyBackup(created.entry.backupId))
    }

    @Test fun wrongKeyCannotReadArchives(): Unit = runBlocking {
        val created = assertIs<MasterBackupOutcome.Created>(service().createBackup())
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val stranger = service(keys = JcaAesGcmMasterBackupKeyCustody("test:other") { other })
        assertFalse(stranger.verifyChain().intact)
        assertIs<MasterBackupVerification.Refused>(stranger.verifyBackup(created.entry.backupId))
    }

    @Test fun captureUnavailableWithoutOperationalMaster(): Unit = runBlocking {
        assertEquals(MasterBackupOutcome.Failed(MasterBackupFailure.CAPTURE_UNAVAILABLE), service(source = null).createBackup())
    }

    @Test fun restoreRequiresApprovalAndYieldsOnlyHeldCandidate(): Unit = runBlocking {
        val backups = service()
        val created = assertIs<MasterBackupOutcome.Created>(backups.createBackup())
        val target = FakeTarget()
        val approval = MasterRestoreApproval(approvalId, installation)
        assertEquals(MasterRestoreOutcome.Refused(MasterRestoreRefusal.APPROVAL_REQUIRED),
            backups.restore(created.entry.backupId, approval, target))
        assertEquals(null, target.held)
        approved += approvalId
        assertEquals(MasterRestoreOutcome.Refused(MasterRestoreRefusal.APPROVAL_REQUIRED),
            backups.restore(created.entry.backupId, MasterRestoreApproval(approvalId, DomainId.random()), target))
        val held = assertIs<MasterRestoreOutcome.Held>(backups.restore(created.entry.backupId, approval, target))
        assertEquals(sha(content), held.restoredSha256)
        assertTrue(target.staged.toByteArray().contentEquals(content))
        assertTrue(String(target.held!!).contains("HELD_NOT_ADMITTED"))
        // An occupied target is never overwritten.
        assertEquals(MasterRestoreOutcome.Refused(MasterRestoreRefusal.TARGET_OCCUPIED),
            backups.restore(created.entry.backupId, approval, target))
    }

    private companion object {
        fun sha(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
