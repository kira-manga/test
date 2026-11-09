package com.ukkera.pos.android

import android.app.Application
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import com.ukkera.pos.android.files.AndroidNativeFiles
import android.system.OsConstants
import android.util.AtomicFile
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchStorage
import java.io.File

/** Android private-file/atomic-write adapter. No record codec, role or restart decisions. */
internal class AndroidLocalMasterLaunchStorage(private val application: Application) : LocalMasterLaunchStorage {
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
        require(names.all { it in FILES })
        if (names.isEmpty()) return null
        // Do not use AtomicFile.openRead: it may roll an interrupted stop back to an older value.
        check(names == setOf(FILE)) { "Local launch record write remains unresolved" }
        val file = File(root, FILE)
        regular(file)
        val descriptor = openDescriptor(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        var retainedBytes: ByteArray? = null
        try {
            val before = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(before.st_mode) && before.st_uid == Process.myUid() &&
                before.st_nlink == 1L && (before.st_mode and 511) == 384 && before.st_size in 1..MAXIMUM_BYTES.toLong())
            val bytes = ByteArray(before.st_size.toInt()).also { retainedBytes = it }
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val count = Os.read(descriptor, bytes, offset, bytes.size - offset)
                    check(count > 0); offset += count
                }
                check(Os.read(descriptor, ByteArray(1), 0, 1) == 0)
                val after = Os.lstat(file.path)
                check(after.st_dev == before.st_dev && after.st_ino == before.st_ino && after.st_size == before.st_size)
                return bytes
            } catch (failure: Throwable) { bytes.fill(0); throw failure }
        } finally {
            try { closeDescriptor(descriptor) }
            catch (failure: Throwable) { retainedBytes?.fill(0); throw failure }
        }
    }

    @Synchronized
    override fun replaceAndForce(bytes: ByteArray) {
        require(bytes.size in 1..MAXIMUM_BYTES)
        val root = checkNotNull(directory(create = true))
        val names = names(root)
        require(names.all { it in FILES })
        names.forEach { regular(File(root, it)) }
        val atomic = AtomicFile(File(root, FILE))
        val output = atomic.startWrite()
        try {
            Os.fchmod(output.fd, 384)
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
            forceDirectory(root)
        } catch (failure: Throwable) {
            // Keep the partial marker. Neither rollback nor silence is durable stop completion.
            try { output.close() } catch (_: Exception) { /* original failure wins */ }
            throw failure
        }
    }

    private fun openDescriptor(path: String, flags: Int, mode: Int): java.io.FileDescriptor =
        protectedIo { AndroidNativeFiles.open(path, flags, mode) }
    private fun closeDescriptor(descriptor: java.io.FileDescriptor) =
        protectedIo { AndroidNativeFiles.close(descriptor) }

    private fun directory(create: Boolean): File? {
        check(!closureUncertain) { "Native storage closure remains unresolved" }
        val root = File(application.noBackupFilesDir, "ukkera.local-master-launch.v1")
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

    private fun regular(file: File) {
        val stat = Os.lstat(file.path)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
            stat.st_nlink == 1L && (stat.st_mode and 511) == 384 && stat.st_size <= MAXIMUM_BYTES)
    }

    private fun forceDirectory(file: File) {
        val descriptor = openDescriptor(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
            OsConstants.O_NONBLOCK, 0)
        try {
            val stat = Os.fstat(descriptor)
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() &&
                (stat.st_mode and OsConstants.S_IWOTH) == 0)
            Os.fsync(descriptor)
        } finally { closeDescriptor(descriptor) }
    }

    private companion object {
        const val FILE = "launch"
        const val MAXIMUM_BYTES = LocalMasterLaunchRecord.MAXIMUM_BYTES
        val FILES = setOf(FILE, "$FILE.new", "$FILE.bak")
    }
}
