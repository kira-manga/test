package com.ukkera.pos.android

import android.app.Application
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import com.ukkera.pos.android.files.AndroidNativeFiles
import android.system.OsConstants
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalDeviceAdmissionIntents
import com.ukkera.pos.app.gateway.setup.LocalFreshDevicePairingIntents
import com.ukkera.pos.app.gateway.setup.LocalSetupIntentStorage
import java.io.File

/** Fixed application-owned namespaces only, never a caller-selected storage path. */
internal enum class AndroidSetupIntentRole(val directoryName: String, val maximumBytes: Int, val maximumFiles: Int) {
    EMPLOYEE("ukkera.employee-enrollment-setup-ui.v1", LocalEmployeeSetupIntents.MAXIMUM_BYTES, LocalEmployeeSetupIntents.MAXIMUM_FILES),
    DEVICE_ADMISSION("ukkera.original-device-admission-setup-ui.v1", LocalDeviceAdmissionIntents.MAXIMUM_BYTES, LocalDeviceAdmissionIntents.MAXIMUM_FILES),
    FRESH_DEVICE_TARGET("ukkera.fresh-device-pairing-target-setup-ui.v1", LocalFreshDevicePairingIntents.MAXIMUM_BYTES, LocalFreshDevicePairingIntents.MAXIMUM_FILES),
    FRESH_DEVICE_MASTER("ukkera.fresh-device-pairing-master-setup-ui.v1", LocalFreshDevicePairingIntents.MAXIMUM_BYTES, LocalFreshDevicePairingIntents.MAXIMUM_FILES),
}

/** Native private-file adapter only; intent interpretation/order/encoding live in commonMain. */
internal class AndroidSetupIntentStorage(
    private val application: Application,
    private val role: AndroidSetupIntentRole = AndroidSetupIntentRole.EMPLOYEE,
    private val namespaceSuffix: String = "",
) : LocalSetupIntentStorage {
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
    override fun names(): List<String>? = directory(create = false)?.let { root ->
        val names = mutableListOf<String>()
        protectedIo {
            AndroidNativeFiles.openDirectory(root.path).use { cursor ->
                while (true) {
                    val name = cursor.nextName() ?: break
                    require(names.size < role.maximumFiles)
                    names.add(name)
                }
            }
        }
        checkDirectory(root)
        names
    }

    @Synchronized
    override fun read(name: String): ByteArray {
        requireName(name)
        return readBytes(checkNotNull(directory(create = false)), name)
    }

    @Synchronized
    override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) {
        requireName(name)
        require(bytes.size in 1..role.maximumBytes)
        writeBytesExclusive(checkNotNull(directory(create = true)), name, bytes, allowExactExisting)
    }

    @Synchronized
    override fun verify() { checkDirectory(checkNotNull(directory(create = false))) }

    private fun requireName(name: String) {
        require(name.length in 1..96 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' } &&
            name != "." && name != "..")
    }

    private fun openDescriptor(path: String, flags: Int, mode: Int): java.io.FileDescriptor =
        protectedIo { AndroidNativeFiles.open(path, flags, mode) }
    private fun closeDescriptor(descriptor: java.io.FileDescriptor) =
        protectedIo { AndroidNativeFiles.close(descriptor) }

    private fun directory(create: Boolean): File? {
        check(!closureUncertain) { "Native storage closure remains unresolved" }
        val root = File(application.noBackupFilesDir, role.directoryName + namespaceSuffix)
        try { checkDirectory(root) }
        catch (missing: ErrnoException) {
            if (missing.errno != OsConstants.ENOENT) throw missing
            if (!create) return null
            Os.mkdir(root.path, 448) // 0700, private application directory.
            forceDirectory(application.noBackupFilesDir)
            checkDirectory(root)
        }
        return root
    }

    private fun checkDirectory(root: File) {
        val stat = Os.lstat(root.path)
        require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() && (stat.st_mode and 511) == 448)
    }

    private fun writeBytesExclusive(root: File, name: String, bytes: ByteArray, allowExactExisting: Boolean) {
        checkDirectory(root)
        try {
            val path = File(root, name).path
            val descriptor = try {
                openDescriptor(path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                    OsConstants.O_NOFOLLOW, 384) // 0600.
            } catch (existing: ErrnoException) {
                if (existing.errno != OsConstants.EEXIST || !allowExactExisting) throw existing
                val existingBytes = readBytes(root, name)
                try { require(existingBytes.contentEquals(bytes)) } finally { existingBytes.fill(0) }
                return
            }
            try {
                val stat = Os.fstat(descriptor)
                require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                    (stat.st_mode and 511) == 384 && stat.st_nlink == 1L && stat.st_size == 0L)
                var offset = 0
                while (offset < bytes.size) {
                    val written = Os.write(descriptor, bytes, offset, bytes.size - offset)
                    require(written > 0); offset += written
                }
                Os.fsync(descriptor)
            } finally { closeDescriptor(descriptor) }
            forceDirectory(root)
            val retained = readBytes(root, name)
            try { require(retained.contentEquals(bytes)) } finally { retained.fill(0) }
            checkDirectory(root)
        } finally { checkDirectory(root) }
    }

    private fun readBytes(root: File, name: String): ByteArray {
        val path = File(root, name).path
        val descriptor = openDescriptor(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        var bytes: ByteArray? = null
        try {
            val stat = Os.fstat(descriptor)
            require(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == Process.myUid() &&
                (stat.st_mode and 511) == 384 && stat.st_nlink == 1L && stat.st_size in 1L..role.maximumBytes.toLong())
            val value = ByteArray(stat.st_size.toInt()); bytes = value
            var offset = 0
            while (offset < value.size) {
                val count = Os.read(descriptor, value, offset, value.size - offset)
                require(count > 0); offset += count
            }
            require(Os.read(descriptor, ByteArray(1), 0, 1) == 0)
            val current = Os.lstat(path)
            require(current.st_ino == stat.st_ino && current.st_dev == stat.st_dev && current.st_size == stat.st_size)
            return value
        } catch (failure: Throwable) {
            bytes?.fill(0)
            throw failure
        } finally {
            // The common decoder owns the buffer only after native close also succeeds.
            try { closeDescriptor(descriptor) }
            catch (failure: Throwable) { bytes?.fill(0); throw failure }
        }
    }

    private fun forceDirectory(root: File) {
        val expected = Os.lstat(root.path)
        require(OsConstants.S_ISDIR(expected.st_mode) && expected.st_uid == Process.myUid() &&
            (expected.st_mode and OsConstants.S_IWOTH) == 0)
        // O_DIRECTORY is not public in Android's OsConstants. A nonblocking/no-follow
        // open and exact descriptor identity/mode checks reject a substituted non-directory.
        val descriptor = openDescriptor(root.path, OsConstants.O_RDONLY or OsConstants.O_NONBLOCK or
            OsConstants.O_NOFOLLOW, 0)
        try {
            val stat = Os.fstat(descriptor)
            require(OsConstants.S_ISDIR(stat.st_mode) && stat.st_uid == Process.myUid() &&
                stat.st_dev == expected.st_dev && stat.st_ino == expected.st_ino &&
                stat.st_gid == expected.st_gid && stat.st_mode == expected.st_mode)
            Os.fsync(descriptor)
            val current = Os.lstat(root.path)
            require(current.st_dev == stat.st_dev && current.st_ino == stat.st_ino &&
                current.st_uid == stat.st_uid && current.st_gid == stat.st_gid && current.st_mode == stat.st_mode)
        } finally { closeDescriptor(descriptor) }
    }

}
