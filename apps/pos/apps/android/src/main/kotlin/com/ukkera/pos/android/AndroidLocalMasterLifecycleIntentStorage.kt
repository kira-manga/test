package com.ukkera.pos.android

import android.app.Application
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.AtomicFile
import com.ukkera.pos.android.files.AndroidNativeFiles
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStorage
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.security.AndroidTrainingSetupNamespaces
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import java.io.File

/** Fixed additive namespaces; no caller-selected path. */
internal class AndroidLifecyclePrivateNamespace private constructor(val directoryName: String) {
    companion object {
        val INTENT = AndroidLifecyclePrivateNamespace("ukkera.local-master-lifecycle-intent.v1")
        val STOP_BINDING = AndroidLifecyclePrivateNamespace("ukkera.local-master-stop-binding.v1")

        /** Fixed bounded slot namespaces; never shared with LIVE, the other slot or launch bytes. */
        fun training(slot: LocalTrainingCompositionSlot,
            stopBinding: Boolean): AndroidLifecyclePrivateNamespace {
            val prefix = if (stopBinding) "ukkera.local-master-stop-binding"
            else "ukkera.local-master-lifecycle-intent"
            return AndroidLifecyclePrivateNamespace(
                prefix + AndroidTrainingSetupNamespaces.intentStorageSuffix(slot))
        }
    }
}

/**
 * Additive private lifecycle-intent namespace, separate from the launch record. It never reads
 * or rewrites old launch bytes. Partial/foreign files remain unresolved rather than Absent.
 */
internal class AndroidLocalMasterLifecycleIntentStorage(
    private val application: Application,
    private val namespace: AndroidLifecyclePrivateNamespace = AndroidLifecyclePrivateNamespace.INTENT,
) : LocalMasterLifecycleIntentStorage {
    private var closureUncertain = false

    private inline fun <T> protectedIo(block: () -> T): T {
        check(!closureUncertain) { "Native storage closure remains unresolved" }
        return try { block() }
        catch (failure: Throwable) {
            if (failure is AndroidNativeFiles.ClosureUncertainException ||
                failure.suppressed.any { it is AndroidNativeFiles.ClosureUncertainException }) closureUncertain = true
            throw failure
        }
    }

    @Synchronized
    override fun read(): ByteArray? {
        val root = directory(create = false) ?: return null
        val names = names(root)
        if (names.isEmpty()) return null
        check(names == setOf(FILE)) { "Lifecycle intent write remains unresolved" }
        return readBytes(root, FILE)
    }

    @Synchronized
    override fun readInterruptedWrite(): ByteArray? {
        val root = directory(create = false) ?: return null
        val names = names(root)
        return when {
            names.isEmpty() || names == setOf(FILE) -> null
            names == setOf(NEW) -> readBytes(root, NEW)
            else -> error("Lifecycle intent interrupted write is not recoverable")
        }
    }

    @Synchronized
    override fun commitRecovered(bytes: ByteArray) {
        require(bytes.size in 1..MAXIMUM_BYTES)
        val root = checkNotNull(directory(create = false))
        check(names(root) == setOf(NEW)) { "Lifecycle intent interrupted write is not recoverable" }
        val interrupted = readBytes(root, NEW)
        try { check(interrupted.contentEquals(bytes)) { "Lifecycle intent interrupted bytes differ" } }
        finally { interrupted.fill(0) }
        val source = File(root, NEW)
        val target = File(root, FILE)
        check(!target.exists()) { "Lifecycle intent target already exists" }
        // Force the complete .new bytes before the directory rename becomes visible.
        val descriptor = openDescriptor(source.path, OsConstants.O_RDWR or OsConstants.O_NOFOLLOW, 0)
        try {
            val stat = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                stat.st_nlink == 1L && (stat.st_mode and 511) == 384 && stat.st_size == bytes.size.toLong())
            Os.fsync(descriptor)
        } finally { closeDescriptor(descriptor) }
        check(source.renameTo(target)) { "Lifecycle intent recovery rename failed" }
        forceDirectory(root)
        check(names(root) == setOf(FILE)) { "Lifecycle intent recovery remains unresolved" }
        val retained = readBytes(root, FILE)
        try { check(retained.contentEquals(bytes)) { "Lifecycle intent recovery readback differs" } }
        finally { retained.fill(0) }
    }

    @Synchronized
    override fun replaceAndForce(bytes: ByteArray) {
        require(bytes.size in 1..MAXIMUM_BYTES)
        val root = checkNotNull(directory(create = true))
        val names = names(root)
        check(names.all { it in FILES }) { "Lifecycle intent write remains unresolved" }
        check(names.isEmpty() || names == setOf(FILE)) { "Lifecycle intent write remains unresolved" }
        if (names == setOf(FILE)) {
            val existing = readBytes(root, FILE)
            try { check(existing.contentEquals(bytes)) { "A different lifecycle intent is already retained" } }
            finally { existing.fill(0) }
            forceDirectory(root)
            return
        }
        val atomic = AtomicFile(File(root, FILE))
        val output = atomic.startWrite()
        try {
            Os.fchmod(output.fd, 384)
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            forceDirectory(root)
        } catch (failure: Throwable) {
            try { output.close() } catch (_: Exception) { /* original failure wins */ }
            throw failure
        }
        val retained = readBytes(root, FILE)
        try { check(retained.contentEquals(bytes)) { "Lifecycle intent readback differs" } }
        finally { retained.fill(0) }
    }

    @Synchronized
    override fun clearAndForce() {
        val root = directory(create = false) ?: return
        val names = names(root)
        check(names.all { it in FILES }) { "Lifecycle intent retirement remains unresolved" }
        check(names.none { it != FILE }) { "Lifecycle intent write remains unresolved" }
        if (FILE in names) {
            val file = File(root, FILE)
            if (file.exists() && !file.delete()) error("Lifecycle intent retirement remains unresolved")
        }
        forceDirectory(root)
        check(names(root).isEmpty()) { "Lifecycle intent retirement remains unresolved" }
    }

    private fun openDescriptor(path: String, flags: Int, mode: Int): java.io.FileDescriptor =
        protectedIo { AndroidNativeFiles.open(path, flags, mode) }
    private fun closeDescriptor(descriptor: java.io.FileDescriptor) =
        protectedIo { AndroidNativeFiles.close(descriptor) }

    private fun directory(create: Boolean): File? {
        check(!closureUncertain) { "Native storage closure remains unresolved" }
        val root = File(application.noBackupFilesDir, namespace.directoryName)
        val stat = try { Os.lstat(root.path) } catch (missing: ErrnoException) {
            if (missing.errno != OsConstants.ENOENT) throw missing
            if (!create) return null
            Os.mkdir(root.path, 448)
            forceDirectory(application.noBackupFilesDir)
            Os.lstat(root.path)
        }
        require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() && (stat.st_mode and 511) == 448)
        return root
    }

    private fun names(root: File): Set<String> {
        val names = mutableSetOf<String>()
        protectedIo {
            AndroidNativeFiles.openDirectory(root.path).use { cursor ->
                while (true) {
                    val name = cursor.nextName() ?: break
                    require(names.size < FILES.size && name in FILES && names.add(name))
                }
            }
        }
        return names
    }

    private fun readBytes(root: File, name: String): ByteArray {
        val path = File(root, name).path
        regular(File(path))
        val descriptor = openDescriptor(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        var bytes: ByteArray? = null
        try {
            val stat = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                stat.st_nlink == 1L && (stat.st_mode and 511) == 384 &&
                stat.st_size in 1L..MAXIMUM_BYTES.toLong())
            val value = ByteArray(stat.st_size.toInt()); bytes = value
            var offset = 0
            while (offset < value.size) {
                val count = Os.read(descriptor, value, offset, value.size - offset)
                require(count > 0); offset += count
            }
            require(Os.read(descriptor, ByteArray(1), 0, 1) == 0)
            val current = Os.lstat(path)
            require(current.st_dev == stat.st_dev && current.st_ino == stat.st_ino && current.st_size == stat.st_size)
            return value
        } catch (failure: Throwable) {
            bytes?.fill(0); throw failure
        } finally {
            try { closeDescriptor(descriptor) }
            catch (failure: Throwable) { bytes?.fill(0); throw failure }
        }
    }

    private fun regular(file: File) {
        val stat = Os.lstat(file.path)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
            stat.st_nlink == 1L && (stat.st_mode and 511) == 384 && stat.st_size <= MAXIMUM_BYTES.toLong())
    }

    private fun forceDirectory(file: File) {
        val expected = Os.lstat(file.path)
        require(OsConstants.S_ISDIR(expected.st_mode) && expected.st_uid == Process.myUid() &&
            (expected.st_mode and OsConstants.S_IWOTH) == 0)
        val descriptor = openDescriptor(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
            OsConstants.O_NONBLOCK, 0)
        try {
            val stat = Os.fstat(descriptor)
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() &&
                stat.st_dev == expected.st_dev && stat.st_ino == expected.st_ino &&
                stat.st_gid == expected.st_gid && stat.st_mode == expected.st_mode)
            Os.fsync(descriptor)
            val current = Os.lstat(file.path)
            require(current.st_dev == stat.st_dev && current.st_ino == stat.st_ino &&
                current.st_uid == stat.st_uid && current.st_gid == stat.st_gid && current.st_mode == stat.st_mode)
        } finally { closeDescriptor(descriptor) }
    }

    private companion object {
        const val FILE = "intent"
        const val NEW = "$FILE.new"
        const val MAXIMUM_BYTES = LocalMasterLifecycleIntentStore.MAXIMUM_BYTES
        val FILES = setOf(FILE, NEW, "$FILE.bak")
    }
}
