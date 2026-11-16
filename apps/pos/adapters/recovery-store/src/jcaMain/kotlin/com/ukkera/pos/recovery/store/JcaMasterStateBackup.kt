package com.ukkera.pos.recovery.store

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.ukkera.pos.types.DomainId
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AES-256-GCM custody over a platform-held [SecretKey]. On Android the key is a non-exportable
 * Android Keystore key; the cipher generates every IV (randomized encryption is required).
 * Sealed layout: ivLength(1) | iv | ciphertext+tag. Never generates or replaces a key.
 */
public class JcaAesGcmMasterBackupKeyCustody(
    override val keyId: String,
    private val key: () -> SecretKey,
) : MasterBackupKeyCustody {
    override suspend fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plaintext)
        val iv = cipher.iv
        check(iv.size in 12..16)
        return byteArrayOf(iv.size.toByte()) + iv + sealed
    }

    override suspend fun open(aad: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size > 1 + 12 + 16)
        val ivSize = sealed[0].toInt()
        require(ivSize in 12..16 && sealed.size > 1 + ivSize + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 1, ivSize))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed, 1 + ivSize, sealed.size - 1 - ivSize)
    }

    override fun toString(): String = "JcaAesGcmMasterBackupKeyCustody([REDACTED])"
}

/**
 * Private archive directory of the Master installation. Archives are published by atomic rename
 * of a fully forced partial file to a name that does not yet exist; chain heads are append-only
 * numbered files (never rewritten in place). Unknown entries are retained, never deleted.
 */
public class FileMasterBackupVault(private val directory: File) : MasterBackupVault {
    init { require(directory.isAbsolute) }

    private fun ensure(): File {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) error("Backup directory unavailable")
        restrictToOwner(directory)
        return directory
    }

    override suspend fun archiveIds(): List<DomainId> = io {
        (ensure().list() ?: error("Backup directory unreadable")).filter { it.endsWith(SUFFIX) }
            .mapNotNull { name -> runCatching { DomainId(name.removeSuffix(SUFFIX)) }.getOrNull() }.sortedBy { it.value }
    }

    override suspend fun create(backupId: DomainId): MasterBackupArchiveWriter = io {
        val partial = File(ensure(), ".${backupId.value}.partial")
        check(!partial.exists() && !File(directory, backupId.value + SUFFIX).exists())
        val output = FileOutputStream(partial)
        restrictToOwner(partial)
        object : MasterBackupArchiveWriter {
            private var done = false
            override suspend fun append(bytes: ByteArray) = io { check(!done); output.write(bytes) }
            override suspend fun publish() = io {
                check(!done); done = true
                output.flush(); output.fd.sync(); output.close()
                val target = File(directory, backupId.value + SUFFIX)
                check(!target.exists() && partial.renameTo(target))
                syncDirectory(directory)
            }
            override suspend fun abort() = io {
                if (!done) { done = true; runCatching { output.close() } }
                if (partial.exists()) partial.delete()
                Unit
            }
        }
    }

    override suspend fun open(backupId: DomainId): MasterBackupArchiveReader = io {
        val file = RandomAccessFile(File(ensure(), backupId.value + SUFFIX), "r")
        object : MasterBackupArchiveReader {
            override suspend fun readExactly(count: Int): ByteArray = io {
                require(count >= 0)
                check(file.length() - file.filePointer >= count) { "Truncated archive" }
                ByteArray(count).also { file.readFully(it) }
            }
            override suspend fun atEnd(): Boolean = io { file.filePointer == file.length() }
            override suspend fun close() = io { file.close() }
        }
    }

    override suspend fun delete(backupId: DomainId): Unit = io {
        val file = File(ensure(), backupId.value + SUFFIX)
        check(file.isFile && file.delete())
        syncDirectory(directory)
    }

    override suspend fun readChainHead(): ByteArray? = io {
        val heads = headFiles()
        heads.lastOrNull()?.let { (_, file) ->
            check(file.length() in 1..MAX_HEAD)
            file.readBytes()
        }
    }

    override suspend fun writeChainHead(bytes: ByteArray): Unit = io {
        require(bytes.size in 1..MAX_HEAD)
        val heads = headFiles()
        val next = (heads.lastOrNull()?.first ?: 0L) + 1
        val partial = File(directory, ".head.partial")
        if (partial.exists()) check(partial.delete())
        FileOutputStream(partial).use { it.write(bytes); it.flush(); it.fd.sync() }
        val target = File(directory, HEAD_PREFIX + next.toString().padStart(19, '0'))
        check(!target.exists() && partial.renameTo(target))
        syncDirectory(directory)
        // Older heads are superseded only after the new head is durable; keep one predecessor.
        heads.dropLast(1).forEach { (_, file) -> file.delete() }
    }

    private fun headFiles(): List<Pair<Long, File>> = (ensure().listFiles() ?: error("Backup directory unreadable"))
        .filter { it.isFile && it.name.startsWith(HEAD_PREFIX) }
        .mapNotNull { file -> file.name.removePrefix(HEAD_PREFIX).toLongOrNull()?.let { it to file } }
        .sortedBy { it.first }

    override fun toString(): String = "FileMasterBackupVault([REDACTED])"

    private companion object {
        const val SUFFIX = ".ukmsb"
        const val HEAD_PREFIX = "chain-head."
        const val MAX_HEAD = 4096
    }
}

/** A private consistent snapshot file produced by the Master's own serialized capture. */
public class FileMasterStateSnapshot(
    private val file: File,
    override val facts: MasterStateCaptureFacts,
) : MasterStateSnapshot {
    override suspend fun read(offset: Long, max: Int): ByteArray = io {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            val size = minOf(max.toLong(), raf.length() - offset).toInt().coerceAtLeast(0)
            ByteArray(size).also { raf.readFully(it) }
        }
    }
    override suspend fun discard(): Unit = io { if (file.exists()) check(file.delete()); Unit }
    override fun toString(): String = "FileMasterStateSnapshot([REDACTED])"

    public companion object {
        /** Native observations of a just-captured snapshot file: size and SHA-256. */
        public suspend fun measure(file: File): Pair<Long, String> = io {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read); total += read
                }
            }
            total to digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        /** Room identity hash and user_version read from the captured snapshot itself. */
        public suspend fun readSchema(file: File): Pair<Int, String> = io {
            BundledSQLiteDriver().open(file.absolutePath, SQLITE_OPEN_READONLY).use { connection ->
                connection.scalarLong("PRAGMA main.user_version").toInt() to
                    connection.scalarText("SELECT identity_hash FROM room_master_table WHERE id=42")
            }
        }
    }
}

/**
 * Fresh/quarantined restore directory. The live Master database is never a target: this
 * directory must be separate from it. Layout: `candidate/master.db` + `candidate/held.json`
 * (HELD, never admitted), `quarantine/<id>-<n>/` for refused bytes, `.stage-<id>` while staging.
 */
public class FileMasterRestoreTarget(private val directory: File) : MasterRestoreTarget {
    init { require(directory.isAbsolute) }

    override suspend fun condition(): MasterRestoreTargetCondition = io {
        if (!directory.exists()) return@io MasterRestoreTargetCondition.FRESH
        val names = (directory.list() ?: return@io MasterRestoreTargetCondition.OCCUPIED).toSet()
        when {
            names.isEmpty() -> MasterRestoreTargetCondition.FRESH
            names == setOf(QUARANTINE) -> MasterRestoreTargetCondition.QUARANTINED
            else -> MasterRestoreTargetCondition.OCCUPIED
        }
    }

    override suspend fun beginStage(backupId: DomainId): MasterRestoreStage = io {
        if (!directory.isDirectory) check(directory.mkdirs() || directory.isDirectory)
        restrictToOwner(directory)
        val stage = File(directory, ".stage-" + backupId.value)
        check(!stage.exists() && stage.mkdir())
        restrictToOwner(stage)
        val database = File(stage, DATABASE)
        val output = FileOutputStream(database)
        object : MasterRestoreStage {
            private var closed = false
            private fun close() { if (!closed) { closed = true; output.flush(); output.fd.sync(); output.close() } }
            override suspend fun append(bytes: ByteArray) = io { check(!closed); output.write(bytes) }
            override suspend fun observe(): MasterRestoredObservation {
                io { close() }
                val (bytes, sha) = FileMasterStateSnapshot.measure(database)
                return io { inspect(bytes, sha) }
            }
            private fun inspect(bytes: Long, sha: String): MasterRestoredObservation {
                var integrity = false; var version = -1; var identity = ""
                runCatching {
                    BundledSQLiteDriver().open(database.absolutePath, SQLITE_OPEN_READONLY).use { c ->
                        integrity = c.scalarText("PRAGMA integrity_check") == "ok"
                        version = c.scalarLong("PRAGMA main.user_version").toInt()
                        identity = c.scalarText("SELECT identity_hash FROM room_master_table WHERE id=42")
                    }
                }
                return MasterRestoredObservation(bytes, sha, integrity, version, identity)
            }
            override suspend fun installHeld(heldRecord: ByteArray) = io {
                close()
                FileOutputStream(File(stage, HELD)).use { it.write(heldRecord); it.flush(); it.fd.sync() }
                syncDirectory(stage)
                val candidate = File(directory, CANDIDATE)
                check(!candidate.exists() && stage.renameTo(candidate))
                syncDirectory(directory)
            }
            override suspend fun quarantine(reason: MasterRestoreRefusal) = io {
                runCatching { close() }
                FileOutputStream(File(stage, "refusal.txt")).use {
                    it.write((reason.name + "\n").encodeToByteArray()); it.flush(); it.fd.sync()
                }
                val root = File(directory, QUARANTINE)
                if (!root.isDirectory) check(root.mkdir())
                var n = 0
                var target: File
                do { target = File(root, backupId.value + "-" + n++) } while (target.exists())
                check(stage.renameTo(target))
                syncDirectory(root); syncDirectory(directory)
            }
            override suspend fun abort() = io {
                runCatching { close() }
                if (database.exists() && database.length() == 0L) database.delete()
                if ((stage.list() ?: emptyArray()).isEmpty()) stage.delete()
                Unit
            }
        }
    }

    override fun toString(): String = "FileMasterRestoreTarget([REDACTED])"

    public companion object {
        public const val CANDIDATE: String = "candidate"
        public const val QUARANTINE: String = "quarantine"
        public const val DATABASE: String = "master.db"
        public const val HELD: String = "held.json"
    }
}

private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

private fun SQLiteConnection.scalarLong(sql: String): Long = prepare(sql).use { check(it.step()); it.getLong(0) }
private fun SQLiteConnection.scalarText(sql: String): String = prepare(sql).use { check(it.step()); it.getText(0) }

/** Best-effort owner-only permission on POSIX; the platform base directory already is owner-only. */
private fun restrictToOwner(file: File) {
    file.setReadable(false, false); file.setReadable(true, true)
    file.setWritable(false, false); file.setWritable(true, true)
    if (file.isDirectory) { file.setExecutable(false, false); file.setExecutable(true, true) }
}

/** Directory durability where the platform allows opening a directory; Windows cannot. */
private fun syncDirectory(directory: File) {
    runCatching { RandomAccessFile(directory, "r").use { it.fd.sync() } }
}
