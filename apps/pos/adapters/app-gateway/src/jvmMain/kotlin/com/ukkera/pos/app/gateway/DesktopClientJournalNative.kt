package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** OS key custody of desktop journal data keys and anti-rollback heads (Windows CNG / macOS Keychain). */
internal interface DesktopJournalKeys {
    fun keyNames(prefix: String): List<String>
    fun keyExists(name: String): Boolean
    fun createKey(name: String, protection: SelfOwnedKeyProtectionV1)
    fun deleteKey(name: String)
    fun inspectKey(name: String, protection: SelfOwnedKeyProtectionV1): Any
    fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray
}

/** Installed-application facts bound into every desktop journal AEAD; re-observed on each base check. */
internal interface DesktopJournalPlatformFacts {
    val targetDigest: String
    val volumeId: String
    fun requireCurrent()
}

/** Desktop process identity and monotonic elapsed clock for the shared journal work. */
internal class JvmClientJournalProcess : ClientJournalProcess {
    private val pid = ProcessHandle.current().pid()
    override fun matchesOriginal(): Boolean = ProcessHandle.current().pid() == pid
    override fun elapsedMillis(): Long = System.nanoTime() / 1_000_000
}

/**
 * Native identity stamp: POSIX device/inode/uid/mode (macOS; Linux for tests) or Windows volume
 * serial/128-bit file ID/owner SID. [mode] is 0 on Windows.
 */
internal data class DesktopFileStamp(val device: Long, val identity: String, val owner: String, val mode: Int,
    val directory: Boolean, val links: Int, val size: Long, val modified: Long, val changed: Long) {
    fun sameIdentityExceptLinks(other: DesktopFileStamp): Boolean = device == other.device && identity == other.identity &&
        owner == other.owner && mode == other.mode && directory == other.directory
    fun sameIdentity(other: DesktopFileStamp): Boolean = sameIdentityExceptLinks(other) && links == other.links
    fun sameDirectory(other: DesktopFileStamp): Boolean = sameIdentityExceptLinks(other)
    fun sameFile(other: DesktopFileStamp): Boolean = sameIdentity(other) && size == other.size &&
        modified == other.modified && changed == other.changed
}

/**
 * Desktop custody seam of the SHARED [ClientJournalFileCore]: owned private directories under the
 * application's fixed private parent, an exclusive lock, write-once files, prefix-completing
 * temporaries, no-replace archive publication and OS-key AEAD/heads. Same protocol as Android.
 */
internal class DesktopClientJournalNative(
    private val parent: Path,
    private val work: ClientJournalWork,
    private val keys: DesktopJournalKeys,
    private val facts: DesktopJournalPlatformFacts,
) : ClientJournalNative {
    private lateinit var root: Path
    private lateinit var directory: Path
    private lateinit var archiveDirectory: Path
    private var archiveHeld = false
    private val held = linkedMapOf<Path, DesktopFileStamp>()
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null
    private var lockStamp: DesktopFileStamp? = null
    private var ownerId: String? = null
    private val posix: Boolean get() = parent.fileSystem.supportedFileAttributeViews().contains("posix")

    override fun openCustody(rootName: String, journalId: DomainId, archive: Boolean, enroll: Boolean,
        enrollChecks: () -> Unit, createDataKey: () -> Unit) {
        facts.requireCurrent()
        val parentStamp = stamp(parent)
        known(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
        ownerId = parentStamp.owner
        held[parent] = parentStamp
        root = parent.resolve(rootName)
        directory = root.resolve(journalId.value)
        archiveDirectory = directory.resolve(ClientJournalFileCore.ARCHIVE)
        if (enroll) {
            enrollChecks()
            known(absent(directory))
            if (!absent(root)) requireDirectory(stamp(root))
            createDataKey()
            if (absent(root)) createDirectory(root, parent)
        }
        hold(root)
        if (enroll) createDirectory(directory, root)
        hold(directory)
        if (archive) {
            if (enroll) createDirectory(archiveDirectory, directory)
            hold(archiveDirectory)
            archiveHeld = true
        }
    }

    override fun acquireLock(enroll: Boolean) {
        val path = directory.resolve(ClientJournalFileCore.LOCK)
        val channel = if (enroll) createPrivateFile(path) else {
            requireFile(stamp(path), 0, allowEmpty = true)
            FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        }
        lockChannel = channel
        lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null } ?: error("client.journal.busy")
        val observed = stamp(path)
        requireFile(observed, 0, allowEmpty = true)
        known(observed.size == 0L)
        lockStamp = observed
        if (enroll) { channel.force(true); syncDirectory(directory) }
    }

    override fun exists(name: String): Boolean = !absent(directory.resolve(name))

    override fun journalNames(): Set<String> = names(directory)

    override fun archiveNames(visit: (String) -> Unit) { names(archiveDirectory).forEach(visit) }

    override fun archiveHasTwoLinks(name: String): Boolean = stamp(archiveDirectory.resolve(name)).links == 2
    override fun archiveLinkedWithTemporary(name: String): Boolean =
        stamp(archiveDirectory.resolve(name)).sameFile(stamp(directory.resolve(ClientJournalFileCore.TEMPORARY)))

    override fun readSnapshot(name: String, archive: Boolean, maximum: Int, allowTwoLinks: Boolean, force: Boolean): ClientJournalSnapshot {
        work.requireActive()
        val path = (if (archive) archiveDirectory else directory).resolve(name)
        val before = stamp(path)
        requireFile(before, maximum, allowEmpty = false, allowTwoLinks = allowTwoLinks)
        val bytes = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            if (force) channel.force(true)
            readExact(channel, before.size.toInt())
        }
        try {
            known(before.sameFile(stamp(path)))
            return ClientJournalSnapshot(ClientJournalMark(before, hex(bytes)), bytes)
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }

    override fun writeNewFile(name: String, bytes: ByteArray, maximum: Int): ClientJournalMark {
        known(bytes.size in 1..maximum)
        val path = directory.resolve(name)
        createPrivateFile(path).use { channel ->
            writeAll(channel, bytes, 0)
            channel.force(true)
        }
        syncDirectory(directory)
        val after = stamp(path)
        requireFile(after, maximum, allowEmpty = false)
        known(after.size == bytes.size.toLong())
        val readback = Files.readAllBytes(path)
        try { known(readback.contentEquals(bytes)) } finally { readback.fill(0) }
        known(after.sameFile(stamp(path)))
        return ClientJournalMark(after, hex(bytes))
    }

    /** Completes the exact fenced bytes as a temporary prefix, then publishes without replacing an archive. */
    override fun publishPrepared(name: String, archive: Boolean, ciphertext: ByteArray, maximum: Int, beforePublish: () -> Unit) {
        val temporary = directory.resolve(ClientJournalFileCore.TEMPORARY)
        val existing = !absent(temporary)
        val channel = if (existing) FileChannel.open(temporary, StandardOpenOption.READ, StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS) else createPrivateFile(temporary, read = true)
        channel.use { owned ->
            val created = stamp(temporary)
            requireFile(created, maximum, allowEmpty = true)
            known(created.size <= ciphertext.size)
            if (!existing) known(created.size == 0L)
            owned.position(0)
            val prefix = readExact(owned, created.size.toInt())
            try { known(prefix.indices.all { prefix[it] == ciphertext[it] }) } finally { prefix.fill(0) }
            writeAll(owned, ciphertext, created.size.toInt())
            owned.force(true)
        }
        syncDirectory(directory)
        val written = stamp(temporary)
        requireFile(written, maximum, allowEmpty = false)
        known(written.size == ciphertext.size.toLong())
        val readback = Files.readAllBytes(temporary)
        try { known(readback.contentEquals(ciphertext)) } finally { readback.fill(0) }
        beforePublish()
        val destination = (if (archive) archiveDirectory else directory).resolve(name)
        if (archive) {
            // A hard link is an atomic no-replace publication; only the known own temporary is unlinked.
            Files.createLink(destination, temporary)
            syncDirectory(archiveDirectory)
            val linked = stamp(destination)
            known(linked.links == 2 && linked.sameFile(stamp(temporary)))
            Files.delete(temporary)
            syncDirectory(directory); syncDirectory(archiveDirectory)
        } else {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            syncDirectory(directory)
        }
        val after = Files.readAllBytes(destination)
        try { known(after.contentEquals(ciphertext)) } finally { after.fill(0) }
        assertBase()
    }

    override fun removePublishedTemporary(name: String, archive: Boolean, ciphertext: ByteArray, maximum: Int, beforeRemove: () -> Unit) {
        val temporary = directory.resolve(ClientJournalFileCore.TEMPORARY)
        if (absent(temporary)) return
        val before = stamp(temporary)
        val linked = before.links == 2
        val destination = (if (archive) archiveDirectory else directory).resolve(name)
        if (linked) known(archive && before.sameFile(stamp(destination)))
        val pending = readSnapshot(ClientJournalFileCore.TEMPORARY, false, maximum, allowTwoLinks = linked, force = false)
        try {
            known(pending.mark.stamp == before && pending.bytes.contentEquals(ciphertext))
            val actual = readSnapshot(name, archive, maximum, allowTwoLinks = linked, force = false)
            try { known(actual.bytes.contentEquals(ciphertext)) } finally { actual.clear() }
            beforeRemove()
            known(before.sameFile(stamp(temporary)))
            work.requireActive()
            Files.delete(temporary)
            syncDirectory(directory)
            if (archive) syncDirectory(archiveDirectory)
        } finally { pending.clear() }
    }

    override fun syncDirectories(archive: Boolean) {
        if (archive) syncDirectory(archiveDirectory)
        syncDirectory(directory)
    }

    override fun removeWriteAhead() {
        work.requireActive()
        Files.delete(directory.resolve(ClientJournalWriteCodecV1.FILE_NAME))
        syncDirectory(directory)
    }

    override fun assertBase() {
        facts.requireCurrent()
        for ((path, expected) in held) known(expected.sameDirectory(stamp(path)))
        val path = directory.resolve(ClientJournalFileCore.LOCK)
        known(lock?.isValid == true && lockChannel?.isOpen == true && requireNotNull(lockStamp).sameFile(stamp(path)))
        val device = requireNotNull(held[directory]).device
        known(requireNotNull(lockStamp).device == device && requireNotNull(held[root]).device == device &&
            (!archiveHeld || requireNotNull(held[archiveDirectory]).device == device))
    }

    override fun writeAadFacts(text: (String) -> Unit, int: (Int) -> Unit, long: (Long) -> Unit) {
        text(facts.targetDigest); text(facts.volumeId)
        text(directory.toString()); text(requireNotNull(ownerId))
        text(requireNotNull(held[root]).identity)
        text(requireNotNull(held[directory]).identity)
        text(requireNotNull(lockStamp).identity)
        text(if (archiveHeld) requireNotNull(held[archiveDirectory]).identity else "")
    }

    override fun requireActive() = work.requireActive()
    override fun keyNames(prefix: String): List<String> = keys.keyNames(prefix)
    override fun keyExists(name: String): Boolean = keys.keyExists(name)
    override fun createKey(name: String, protection: SelfOwnedKeyProtectionV1) = keys.createKey(name, protection)
    override fun deleteKey(name: String) = keys.deleteKey(name)
    override fun inspectKey(name: String, protection: SelfOwnedKeyProtectionV1): Any = keys.inspectKey(name, protection)
    override fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> = keys.encrypt(name, aad, plaintext)
    override fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        keys.decrypt(name, nonce, ciphertext, aad)

    override fun release() {
        lock?.let { held -> if (held.isValid) held.release(); lock = null }
        lockChannel?.close(); lockChannel = null
        held.clear()
    }

    // ---- helpers ----
    private fun hold(path: Path) {
        val observed = stamp(path)
        requireDirectory(observed)
        held[path] = observed
    }

    private fun absent(path: Path): Boolean = !Files.exists(path, LinkOption.NOFOLLOW_LINKS)

    private fun createDirectory(path: Path, parentDirectory: Path) {
        work.requireActive()
        known(absent(path))
        if (posix) Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        else Files.createDirectory(path)
        requireDirectory(stamp(path))
        syncDirectory(parentDirectory)
    }

    private fun createPrivateFile(path: Path, read: Boolean = false): FileChannel {
        val options = buildSet {
            add(StandardOpenOption.CREATE_NEW); add(StandardOpenOption.WRITE); if (read) add(StandardOpenOption.READ)
            add(LinkOption.NOFOLLOW_LINKS)
        }
        return if (posix) FileChannel.open(path, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        else FileChannel.open(path, options)
    }

    private fun names(path: Path): Set<String> {
        work.requireActive()
        val found = mutableSetOf<String>()
        Files.newDirectoryStream(path).use { stream ->
            for (entry in stream) {
                work.requireActive()
                val name = entry.fileName.toString()
                known(name.isNotEmpty() && '/' !in name && '\u0000' !in name && found.size < CLIENT_JOURNAL_MAX_ARCHIVES_V1 + 8 && found.add(name))
            }
        }
        return found
    }

    private fun readExact(channel: FileChannel, size: Int): ByteArray {
        val result = ByteArray(size)
        try {
            val buffer = ByteBuffer.wrap(result)
            while (buffer.hasRemaining()) { work.requireActive(); known(channel.read(buffer) > 0) }
            known(channel.read(ByteBuffer.allocate(1)) == -1)
            return result
        } catch (failure: Throwable) { result.fill(0); throw failure }
    }

    private fun writeAll(channel: FileChannel, bytes: ByteArray, offset: Int) {
        channel.position(offset.toLong())
        val buffer = ByteBuffer.wrap(bytes, offset, bytes.size - offset)
        while (buffer.hasRemaining()) { work.requireActive(); known(channel.write(buffer) > 0) }
    }

    private fun syncDirectory(path: Path) {
        if (!posix) return // NTFS directory metadata is journaled; Java cannot flush a Windows directory handle.
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    private val unix: Boolean get() = parent.fileSystem.supportedFileAttributeViews().contains("unix")

    private fun stamp(path: Path): DesktopFileStamp {
        if (!unix) {
            val o = com.ukkera.pos.security.WindowsDesktopFiles.observe(path)
            return DesktopFileStamp(o.volumeSerial, o.fileId, o.ownerSid, 0, o.directory, o.links, o.size, o.lastWrite, 0L)
        }
        val a = Files.readAttributes(path, "unix:dev,ino,uid,mode,nlink,size,lastModifiedTime,ctime", LinkOption.NOFOLLOW_LINKS)
        val mode = (a["mode"] as Number).toInt()
        return DesktopFileStamp((a["dev"] as Number).toLong(), (a["ino"] as Number).toLong().toString(),
            (a["uid"] as Number).toInt().toString(), mode, mode and 0xF000 == 0x4000, (a["nlink"] as Number).toInt(),
            (a["size"] as Number).toLong(), (a["lastModifiedTime"] as FileTime).to(java.util.concurrent.TimeUnit.NANOSECONDS),
            (a["ctime"] as FileTime).to(java.util.concurrent.TimeUnit.NANOSECONDS))
    }

    private fun requireDirectory(value: DesktopFileStamp) {
        known(value.directory)
        if (unix) known(value.mode and 0xF000 == 0x4000 && value.mode and 2 == 0)
        ownerId?.let { known(value.owner == it) }
    }

    private fun requireFile(value: DesktopFileStamp, maximum: Int, allowEmpty: Boolean, allowTwoLinks: Boolean = false) {
        known(!value.directory && value.owner == ownerId &&
            (!unix || (value.mode and 0xF000 == 0x8000 && value.mode and 0x3F == 0)) &&
            (value.links == 1 || (allowTwoLinks && value.links == 2)) &&
            value.size in (if (allowEmpty) 0L else 1L)..maximum.toLong())
    }

    private fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).useWiped(::encodeHex)

    private fun known(value: Boolean) { check(value) { "client.journal.unavailable" } }
}
