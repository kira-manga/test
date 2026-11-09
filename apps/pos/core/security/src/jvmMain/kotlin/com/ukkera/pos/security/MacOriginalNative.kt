package com.ukkera.pos.security

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Path

/**
 * Narrow libc surface for the macOS original target namespace. This is not a general filesystem
 * API: the caller supplies no arbitrary file name, mode or native flag set. Every observation is
 * DATA for the concrete namespace owner and never grants installation, history or writer
 * authority. Only 64-bit macOS loads the library; unsupported hosts fail closed.
 */
internal object MacOriginalNative {
    const val DIRECTORY_MODE: Int = 448 // 0700
    const val FILE_MODE: Int = 384 // 0600

    private const val EINTR = 4
    private const val ENOENT = 2
    private const val EEXIST = 17
    private const val EAGAIN = 35
    private const val EINVAL = 22
    private const val ACL_TYPE_EXTENDED = 0x00000100
    private const val ACL_FIRST_ENTRY = 0
    private const val O_RDONLY = 0x00000000
    private const val O_RDWR = 0x00000002
    private const val O_NONBLOCK = 0x00000004
    private const val O_NOFOLLOW = 0x00000100
    private const val O_CREAT = 0x00000200
    private const val O_EXCL = 0x00000800
    private const val O_DIRECTORY = 0x00100000
    private const val O_CLOEXEC = 0x01000000
    private const val LOCK_EX = 2
    private const val LOCK_NB = 4
    private const val SEEKSET = 0
    private const val ATTR_BIT_MAP_COUNT = 5
    private const val ATTR_VOL_INFO = 0x00000001
    private const val ATTR_VOL_UUID = 0x00080000
    private const val FSOPT_NOFOLLOW = 0x00000001
    private const val MNT_RDONLY = 0x00000001
    private const val MNT_LOCAL = 0x00001000

    private val library: MacOriginalLibC by lazy {
        Native.load(Platform.C_LIBRARY_NAME, MacOriginalLibC::class.java)
    }

    fun requireSupported() {
        if (!Platform.isMac() || Native.POINTER_SIZE != 8) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }
    }

    fun effectiveUid(): Int {
        requireSupported()
        return library.geteuid()
    }

    fun lstat(path: Path): MacOriginalStamp? {
        requireSupported()
        val stat = MacStat()
        if (library.lstat(path.toString(), stat.pointer) != 0) {
            val error = Native.getLastError()
            if (error == ENOENT) return null
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        stat.read()
        return stat.stamp()
    }

    fun fstat(fd: Int): MacOriginalStamp {
        requireSupported()
        val stat = MacStat()
        if (library.fstat(fd, stat.pointer) != 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        stat.read()
        return stat.stamp()
    }

    fun openDirectory(path: Path): MacOriginalFd {
        requireSupported()
        val fd = library.open(path.toString(),
            O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC, 0)
        if (fd < 0) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return MacOriginalFd(fd)
    }

    fun openReadFile(path: Path): MacOriginalFd {
        requireSupported()
        val fd = library.open(path.toString(), O_RDONLY or O_NONBLOCK or O_NOFOLLOW or O_CLOEXEC, 0)
        if (fd < 0) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return MacOriginalFd(fd)
    }

    fun openReadWriteFile(path: Path): MacOriginalFd {
        requireSupported()
        val fd = library.open(path.toString(), O_RDWR or O_NONBLOCK or O_NOFOLLOW or O_CLOEXEC, 0)
        if (fd < 0) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return MacOriginalFd(fd)
    }

    /** Returns null only for EEXIST; an existing lock/intent is never adopted or replaced here. */
    fun createExclusiveFile(path: Path): MacOriginalFd? {
        requireSupported()
        val fd = library.open(path.toString(),
            O_RDWR or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, FILE_MODE)
        if (fd >= 0) return MacOriginalFd(fd)
        val error = Native.getLastError()
        if (error == EEXIST) return null
        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    /** Returns false only for EEXIST; a missing original directory is never recreated by a race. */
    fun mkdir(path: Path): Boolean {
        requireSupported()
        if (library.mkdir(path.toString(), DIRECTORY_MODE) == 0) return true
        val error = Native.getLastError()
        if (error == EEXIST) return false
        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    fun fchmod(fd: Int, mode: Int) {
        requireSupported()
        if (library.fchmod(fd, mode) != 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    /**
     * A real fsync result is required for files and directories. EINTR is retried; any other
     * failure, including an unsupported directory fsync, remains unresolved and is never success.
     */
    fun fsync(fd: Int) {
        requireSupported()
        while (true) {
            if (library.fsync(fd) == 0) return
            if (Native.getLastError() != EINTR) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
        }
    }

    fun rewind(fd: Int) {
        requireSupported()
        if (library.lseek(fd, NativeLong(0L), SEEKSET) != 0L) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    fun readExact(fd: Int, size: Int): ByteArray {
        requireSupported()
        require(size >= 0)
        val memory = Memory(maxOf(1, size).toLong())
        val tail = Memory(1L)
        try {
            var offset = 0
            while (offset < size) {
                val count = library.read(fd, memory.share(offset.toLong()),
                    NativeLong((size - offset).toLong()))
                if (count < 0) {
                    if (Native.getLastError() == EINTR) continue
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                if (count == 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                if (count > (size - offset).toLong()) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                offset += count.toInt()
            }
            val extra = library.read(fd, tail, NativeLong(1L))
            if (extra < 0 && Native.getLastError() == EINTR) {
                // A retry is safe because the requested count is one byte and nothing was read.
                if (library.read(fd, tail, NativeLong(1L)) != 0L) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
            } else if (extra != 0L) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return memory.getByteArray(0, size)
        } finally {
            memory.clear()
            tail.clear()
        }
    }

    fun writeAll(fd: Int, bytes: ByteArray) {
        requireSupported()
        if (bytes.isEmpty()) return
        val memory = Memory(bytes.size.toLong())
        try {
            memory.write(0, bytes, 0, bytes.size)
            var offset = 0
            while (offset < bytes.size) {
                val count = library.write(fd, memory.share(offset.toLong()),
                    NativeLong((bytes.size - offset).toLong()))
                if (count < 0) {
                    if (Native.getLastError() == EINTR) continue
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                if (count == 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                if (count > (bytes.size - offset).toLong()) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                offset += count.toInt()
            }
        } finally {
            memory.clear()
        }
    }

    fun rename(from: Path, to: Path) {
        requireSupported()
        if (library.rename(from.toString(), to.toString()) != 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    /** True when this descriptor holds the cross-process exclusive lease; false means BUSY. */
    fun flockExclusive(fd: Int): Boolean {
        requireSupported()
        if (library.flock(fd, LOCK_EX or LOCK_NB) == 0) return true
        val error = Native.getLastError()
        if (error == EAGAIN) return false
        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    fun close(fd: Int): Int {
        requireSupported()
        return library.close(fd)
    }

    fun requireNoExtendedAcl(fd: Int) {
        requireSupported()
        val acl = library.acl_get_fd_np(fd, ACL_TYPE_EXTENDED)
        if (acl == null) {
            val error = Native.getLastError()
            // Darwin reports ENOENT for an existing inode with no extended ACL.
            if (error == ENOENT) return
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        try {
            val entry = PointerByReference()
            val result = library.acl_get_entry(acl, ACL_FIRST_ENTRY, entry)
            if (result == 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val error = Native.getLastError()
            // Darwin returns EINVAL at the end of an ACL, including an empty one.
            if (error != EINVAL) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        } finally {
            if (library.acl_free(acl) != 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
        }
    }

    fun requireLocalVolume(path: Path): MacOriginalVolume {
        requireSupported()
        val stat = MacStatFs()
        if (library.statfs(path.toString(), stat.pointer) != 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        stat.read()
        val type = nulString(stat.f_fstypename)
        if (type != "apfs" && type != "hfs") {
            selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }
        if (stat.f_flags and MNT_LOCAL == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }
        if (stat.f_flags and MNT_RDONLY != 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return MacOriginalVolume(type, volumeUuid(path))
    }

    private fun volumeUuid(path: Path): String {
        val attributes = MacAttrList()
        attributes.bitmapcount = ATTR_BIT_MAP_COUNT.toShort()
        attributes.volattr = ATTR_VOL_INFO or ATTR_VOL_UUID
        attributes.write()
        val buffer = Memory(32L)
        try {
            if (library.getattrlist(path.toString(), attributes.pointer, buffer,
                    NativeLong(32L), NativeLong(FSOPT_NOFOLLOW.toLong())) != 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            // Fixed-size volume attributes begin with one u32 length, followed by the uuid_t.
            if (buffer.getInt(0) != 20) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val bytes = buffer.getByteArray(4, 16)
            try {
                val alphabet = "0123456789abcdef"
                val text = buildString(36) {
                    for (index in 0 until 16) {
                        if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
                        val value = bytes[index].toInt() and 255
                        append(alphabet[value ushr 4])
                        append(alphabet[value and 15])
                    }
                }
                if (text.all { it == '0' || it == '-' }) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                return text
            } finally {
                bytes.fill(0)
            }
        } finally {
            buffer.clear()
        }
    }

    private fun nulString(bytes: ByteArray): String {
        val length = bytes.indexOf(0)
        if (length <= 0) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return bytes.copyOfRange(0, length).toString(Charsets.UTF_8)
    }
}

internal data class MacOriginalVolume(val fileSystem: String, val uuid: String) {
    override fun toString(): String = "MacOriginalVolume([REDACTED])"
}

internal data class MacOriginalStamp(
    val device: Int,
    val inode: Long,
    val uid: Int,
    val gid: Int,
    val mode: Int,
    val links: Int,
    val size: Long,
    val modifiedSeconds: Long,
    val modifiedNanos: Long,
    val changedSeconds: Long,
    val changedNanos: Long,
) {
    val isDirectory: Boolean get() = mode and 0xf000 == 0x4000
    val isRegularFile: Boolean get() = mode and 0xf000 == 0x8000

    fun sameIdentity(other: MacOriginalStamp): Boolean =
        device == other.device && inode == other.inode && uid == other.uid && gid == other.gid &&
            mode == other.mode && links == other.links

    fun sameDirectory(other: MacOriginalStamp): Boolean = sameIdentity(other) && isDirectory && other.isDirectory

    fun sameFile(other: MacOriginalStamp): Boolean = sameIdentity(other) && size == other.size &&
        modifiedSeconds == other.modifiedSeconds && modifiedNanos == other.modifiedNanos &&
        changedSeconds == other.changedSeconds && changedNanos == other.changedNanos
}

/** An unconfirmed close poisons this process's namespace custody until process retirement. */
internal object MacOriginalCloseState {
    private var uncertain = false

    @Synchronized
    fun requireConfirmed() {
        if (uncertain) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
    }

    @Synchronized
    fun markUncertain() {
        uncertain = true
    }

    @Synchronized
    fun releaseLease(descriptor: MacOriginalFd) {
        requireConfirmed()
        descriptor.closeConfirmed()
    }
}

/** A failed close is terminal. In particular, EINTR never causes a second close of a reused fd. */
internal class MacOriginalFd internal constructor(descriptor: Int) {
    private var descriptor: Int = descriptor

    val fd: Int
        get() = synchronized(MacOriginalCloseState) {
            check(descriptor >= 0) { "macOS original descriptor is unavailable" }
            descriptor
        }

    fun closeConfirmed(): Unit = synchronized(MacOriginalCloseState) {
        if (descriptor == -1) return@synchronized
        if (descriptor == -2) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val actual = descriptor
        descriptor = -2
        try {
            if (MacOriginalNative.close(actual) != 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            }
            descriptor = -1
        } catch (failure: Throwable) {
            MacOriginalCloseState.markUncertain()
            throw failure
        }
    }
}

@Structure.FieldOrder(
    "st_dev", "st_mode", "st_nlink", "st_ino", "st_uid", "st_gid", "st_rdev",
    "st_atimespec", "st_mtimespec", "st_ctimespec", "st_birthtimespec",
    "st_size", "st_blocks", "st_blksize", "st_flags", "st_gen", "st_lspare", "st_qspare",
)
internal class MacStat : Structure() {
    @JvmField var st_dev: Int = 0
    @JvmField var st_mode: Short = 0
    @JvmField var st_nlink: Short = 0
    @JvmField var st_ino: Long = 0
    @JvmField var st_uid: Int = 0
    @JvmField var st_gid: Int = 0
    @JvmField var st_rdev: Int = 0
    @JvmField var st_atimespec: MacTimespec = MacTimespec()
    @JvmField var st_mtimespec: MacTimespec = MacTimespec()
    @JvmField var st_ctimespec: MacTimespec = MacTimespec()
    @JvmField var st_birthtimespec: MacTimespec = MacTimespec()
    @JvmField var st_size: Long = 0
    @JvmField var st_blocks: Long = 0
    @JvmField var st_blksize: Int = 0
    @JvmField var st_flags: Int = 0
    @JvmField var st_gen: Int = 0
    @JvmField var st_lspare: Int = 0
    @JvmField var st_qspare: LongArray = LongArray(2)

    fun stamp(): MacOriginalStamp = MacOriginalStamp(
        st_dev, st_ino, st_uid, st_gid, st_mode.toInt() and 0xffff, st_nlink.toInt() and 0xffff,
        st_size, st_mtimespec.tv_sec, st_mtimespec.tv_nsec, st_ctimespec.tv_sec, st_ctimespec.tv_nsec,
    )
}

@Structure.FieldOrder("tv_sec", "tv_nsec")
internal class MacTimespec : Structure() {
    @JvmField var tv_sec: Long = 0
    @JvmField var tv_nsec: Long = 0
}

@Structure.FieldOrder(
    "f_bsize", "f_iosize", "f_blocks", "f_bfree", "f_bavail", "f_files", "f_ffree",
    "f_fsid", "f_owner", "f_type", "f_flags", "f_fssubtype", "f_fstypename",
    "f_mntonname", "f_mntfromname", "f_flags_ext", "f_reserved",
)
internal class MacStatFs : Structure() {
    @JvmField var f_bsize: Int = 0
    @JvmField var f_iosize: Int = 0
    @JvmField var f_blocks: Long = 0
    @JvmField var f_bfree: Long = 0
    @JvmField var f_bavail: Long = 0
    @JvmField var f_files: Long = 0
    @JvmField var f_ffree: Long = 0
    @JvmField var f_fsid: MacFsid = MacFsid()
    @JvmField var f_owner: Int = 0
    @JvmField var f_type: Int = 0
    @JvmField var f_flags: Int = 0
    @JvmField var f_fssubtype: Int = 0
    @JvmField var f_fstypename: ByteArray = ByteArray(16)
    @JvmField var f_mntonname: ByteArray = ByteArray(1024)
    @JvmField var f_mntfromname: ByteArray = ByteArray(1024)
    @JvmField var f_flags_ext: Int = 0
    @JvmField var f_reserved: IntArray = IntArray(7)
}

@Structure.FieldOrder("first", "second")
internal class MacFsid : Structure() {
    @JvmField var first: Int = 0
    @JvmField var second: Int = 0
}

@Structure.FieldOrder(
    "bitmapcount", "reserved", "commonattr", "volattr", "dirattr", "fileattr", "forkattr",
)
internal class MacAttrList : Structure() {
    @JvmField var bitmapcount: Short = 0
    @JvmField var reserved: Short = 0
    @JvmField var commonattr: Int = 0
    @JvmField var volattr: Int = 0
    @JvmField var dirattr: Int = 0
    @JvmField var fileattr: Int = 0
    @JvmField var forkattr: Int = 0
}

internal interface MacOriginalLibC : Library {
    fun lstat(path: String, buffer: Pointer): Int
    fun fstat(fd: Int, buffer: Pointer): Int
    fun open(path: String, flags: Int, mode: Int): Int
    fun close(fd: Int): Int
    fun mkdir(path: String, mode: Int): Int
    fun fchmod(fd: Int, mode: Int): Int
    fun fsync(fd: Int): Int
    fun rename(from: String, to: String): Int
    fun flock(fd: Int, operation: Int): Int
    fun read(fd: Int, buffer: Pointer, count: NativeLong): Long
    fun write(fd: Int, buffer: Pointer, count: NativeLong): Long
    fun lseek(fd: Int, offset: NativeLong, whence: Int): Long
    fun getattrlist(path: String, attributes: Pointer, buffer: Pointer, size: NativeLong,
        options: NativeLong): Int
    fun statfs(path: String, buffer: Pointer): Int
    fun geteuid(): Int
    fun acl_get_fd_np(fd: Int, type: Int): Pointer?
    fun acl_get_entry(acl: Pointer, entryId: Int, entry: PointerByReference): Int
    fun acl_free(pointer: Pointer): Int
}
