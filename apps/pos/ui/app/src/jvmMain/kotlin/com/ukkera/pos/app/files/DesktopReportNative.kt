package com.ukkera.pos.app.files

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path

/**
 * Narrow native observations for the desktop report custody profile. Every result is DATA;
 * neither an observation nor a successful call grants publication, owner, storage or employee
 * authority. Unsupported platforms and unavailable native facilities fail closed.
 */
internal object DesktopReportNative {
    /** Actual effective UID of this process, not a probe file's owner or a user-name lookup. */
    fun macEffectiveUid(): Long {
        check(Platform.isMac()) { "macOS native observation requested on another platform" }
        return Integer.toUnsignedLong(macLibC.geteuid())
    }

    /**
     * Reads the Darwin extended ACL through `acl_get_file`/`acl_get_entry`. An existing object
     * with no extended ACL yields an empty ACL (Darwin reports ENOENT from `acl_get_file` or
     * EINVAL from `acl_get_entry`); every other outcome is unavailable inspection, not "no ACL".
     * Any extended entry, allow or deny, is outside this POSIX-mode custody model.
     */
    fun macHasExtendedAcl(path: Path): Boolean {
        check(Platform.isMac()) { "macOS native observation requested on another platform" }
        val acl = macLibC.acl_get_file(path.toString(), ACL_TYPE_EXTENDED)
        if (acl == null) {
            val error = Native.getLastError()
            // Darwin reports ENOENT for an existing inode that has no extended ACL.
            if (error == ENOENT) return false
            error("macOS extended ACL read unavailable (errno $error)")
        }
        try {
            val entry = PointerByReference()
            if (macLibC.acl_get_entry(acl, ACL_FIRST_ENTRY, entry) == 0) {
                check(entry.value != null) { "macOS extended ACL entry unavailable" }
                return true
            }
            val error = Native.getLastError()
            // Darwin returns EINVAL at the end of an ACL, including an empty one.
            check(error == EINVAL) { "macOS extended ACL entry read unavailable (errno $error)" }
            return false
        } finally {
            check(macLibC.acl_free(acl) == 0) { "macOS extended ACL release failed" }
        }
    }

    /**
     * Observes the actual Win32 volume and final path of a directory. GetDriveTypeW is applied
     * to the volume path returned by GetVolumePathNameW, and the directory handle must resolve
     * to the same NTFS volume serial reported by GetVolumeInformationByHandleW. A mapped SMB
     * drive, UNC/device namespace, reparse point, removable media or non-NTFS filesystem is
     * rejected even where the JDK FileStore describes it as NTFS.
     */
    fun windowsObserveVolume(path: Path): WindowsVolumeObservation {
        check(Platform.isWindows()) { "Windows native observation requested on another platform" }
        val absolute = path.toAbsolutePath().normalize()
        val mountRoot = windowsVolumePath(absolute)
        check(Regex("^[A-Za-z]:\\\\").matches(mountRoot)) {
            "Windows report custody requires a drive-letter volume root"
        }
        val driveType = windowsKernel32.GetDriveTypeW(WString(mountRoot))
        val volumeGuid = windowsVolumeGuid(mountRoot)
        val volumeInfo = windowsVolumeInformation(mountRoot)
        rejectMappedDrive(mountRoot)
        return withWindowsHandle(absolute, directory = true, access = FILE_READ_ATTRIBUTES) { handle ->
            val byHandle = windowsFileInformation(handle)
            check(byHandle.dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0 &&
                byHandle.dwFileAttributes and FILE_ATTRIBUTE_REPARSE_POINT == 0) {
                "Windows report custody requires a non-reparse directory"
            }
            val finalPath = windowsFinalPath(handle)
            check(!finalPath.startsWith("\\\\?\\UNC\\", ignoreCase = true) &&
                !finalPath.startsWith("\\\\?\\GLOBALROOT", ignoreCase = true) &&
                !finalPath.startsWith("\\\\.\\", ignoreCase = true)) {
                "Windows report custody rejects network/device final paths"
            }
            val handleInfo = windowsVolumeInformationByHandle(handle)
            val handleSerial = byHandle.dwVolumeSerialNumber.toLong() and 0xffffffffL
            check(handleInfo.serial == handleSerial && volumeInfo.serial == handleSerial) {
                "Windows report custody observed an inconsistent volume identity"
            }
            check(handleInfo.fileSystem.equals("NTFS", ignoreCase = true) &&
                volumeInfo.fileSystem.equals("NTFS", ignoreCase = true)) {
                "Windows report custody requires NTFS"
            }
            WindowsVolumeObservation(
                driveType = driveType,
                fileSystem = handleInfo.fileSystem,
                volumeSerialNumber = handleSerial,
                readOnly = volumeInfo.readOnly,
                mountRoot = mountRoot,
                volumeGuid = volumeGuid,
                finalPath = finalPath,
            )
        }
    }

    /**
     * Kernel file identity from GetFileInformationByHandle: volume serial plus NTFS file index.
     * Hard links share both values, so the spool and its published link retain one identity.
     * No alternate-stream token or random custody value is a substitute for the kernel ID.
     */
    fun windowsFileIdentity(path: Path, directory: Boolean): String {
        check(Platform.isWindows()) { "Windows native observation requested on another platform" }
        return withWindowsHandle(path, directory, FILE_READ_ATTRIBUTES) { handle ->
            val info = windowsFileInformation(handle)
            val isDirectory = info.dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0
            check(isDirectory == directory) { "Windows report custody observed an unexpected file type" }
            check(info.dwFileAttributes and FILE_ATTRIBUTE_REPARSE_POINT == 0) {
                "Windows report custody rejects reparse-point identities"
            }
            val serial = info.dwVolumeSerialNumber.toLong() and 0xffffffffL
            val index = ((info.nFileIndexHigh.toLong() and 0xffffffffL) shl 32) or
                (info.nFileIndexLow.toLong() and 0xffffffffL)
            check(index != 0L) { "Windows report custody observed an empty kernel file index" }
            "windows:${serial.toString(16)}:${index.toString(16)}".also { check(it.length <= 256) }
        }
    }

    private val macLibC: MacLibC by lazy {
        Native.load(Platform.C_LIBRARY_NAME, MacLibC::class.java)
    }
    private val windowsKernel32: WindowsKernel32 by lazy {
        Native.load("kernel32", WindowsKernel32::class.java)
    }
    private val windowsMpr: WindowsMpr by lazy {
        Native.load("mpr", WindowsMpr::class.java)
    }

    private fun windowsVolumePath(path: Path): String {
        val buffer = CharArray(32_768)
        check(windowsKernel32.GetVolumePathNameW(WString(path.toString()), buffer, buffer.size) != 0) {
            "Windows report custody could not observe the volume root (${Native.getLastError()})"
        }
        return nulString(buffer)
    }

    private fun windowsVolumeGuid(mountRoot: String): String {
        val buffer = CharArray(1_024)
        check(windowsKernel32.GetVolumeNameForVolumeMountPointW(WString(mountRoot), buffer, buffer.size) != 0) {
            "Windows report custody could not observe a local volume name (${Native.getLastError()})"
        }
        return nulString(buffer).also {
            check(it.startsWith("\\\\?\\Volume{", ignoreCase = true)) {
                "Windows report custody requires a volume-GUID mount"
            }
        }
    }

    private fun windowsVolumeInformation(root: String): WindowsVolumeInfo {
        val volumeName = CharArray(1_024)
        val fileSystemName = CharArray(256)
        val serial = IntByReference()
        val maximumComponent = IntByReference()
        val flags = IntByReference()
        check(windowsKernel32.GetVolumeInformationW(WString(root), volumeName, volumeName.size, serial,
            maximumComponent, flags, fileSystemName, fileSystemName.size) != 0) {
            "Windows report custody could not observe volume information (${Native.getLastError()})"
        }
        return WindowsVolumeInfo(
            fileSystem = nulString(fileSystemName),
            serial = serial.value.toLong() and 0xffffffffL,
            readOnly = flags.value and FILE_READ_ONLY_VOLUME != 0,
        )
    }

    private fun windowsVolumeInformationByHandle(handle: Pointer): WindowsVolumeInfo {
        val volumeName = CharArray(1_024)
        val fileSystemName = CharArray(256)
        val serial = IntByReference()
        val maximumComponent = IntByReference()
        val flags = IntByReference()
        check(windowsKernel32.GetVolumeInformationByHandleW(handle, volumeName, volumeName.size, serial,
            maximumComponent, flags, fileSystemName, fileSystemName.size) != 0) {
            "Windows report custody could not observe handle volume information (${Native.getLastError()})"
        }
        return WindowsVolumeInfo(
            fileSystem = nulString(fileSystemName),
            serial = serial.value.toLong() and 0xffffffffL,
            readOnly = flags.value and FILE_READ_ONLY_VOLUME != 0,
        )
    }

    private fun windowsFinalPath(handle: Pointer): String {
        val buffer = CharArray(32_768)
        val length = windowsKernel32.GetFinalPathNameByHandleW(handle, buffer, buffer.size, 0)
        check(length in 1 until buffer.size) {
            "Windows report custody could not observe the final path (${Native.getLastError()})"
        }
        return buffer.concatToString(0, length)
    }

    private fun rejectMappedDrive(mountRoot: String) {
        val buffer = CharArray(32_768)
        val length = IntByReference(buffer.size)
        val result = windowsMpr.WNetGetConnectionW(WString(mountRoot.take(2)), buffer, length)
        // Success or ERROR_MORE_DATA proves a network connection behind the drive letter.
        check(result != 0 && result != ERROR_MORE_DATA) {
            "Windows report custody rejects mapped/network drives"
        }
    }

    private fun windowsFileInformation(handle: Pointer): WindowsByHandleFileInformation {
        val info = WindowsByHandleFileInformation()
        check(windowsKernel32.GetFileInformationByHandle(handle, info) != 0) {
            "Windows report custody could not observe the kernel file identity (${Native.getLastError()})"
        }
        info.read()
        return info
    }

    private inline fun <T> withWindowsHandle(path: Path, directory: Boolean, access: Int,
        block: (Pointer) -> T): T {
        val flags = FILE_FLAG_OPEN_REPARSE_POINT or if (directory) FILE_FLAG_BACKUP_SEMANTICS else 0
        val handle = windowsKernel32.CreateFileW(WString(windowsNativePath(path)), access, FILE_SHARE_ALL,
            null, OPEN_EXISTING, flags, null)
        if (Pointer.nativeValue(handle) == INVALID_HANDLE_VALUE) {
            error("Windows report custody could not open the object (${Native.getLastError()})")
        }
        try { return block(handle) } finally {
            check(windowsKernel32.CloseHandle(handle) != 0) { "Windows report custody handle close failed" }
        }
    }

    private fun windowsNativePath(path: Path): String {
        val text = path.toAbsolutePath().normalize().toString()
        check(!text.startsWith("\\\\") || text.startsWith("\\\\?\\")) {
            "Windows report custody rejects UNC/device paths"
        }
        if (text.startsWith("\\\\?\\")) return text
        check(Regex("^[A-Za-z]:\\\\").containsMatchIn(text)) {
            "Windows report custody requires a drive-letter path"
        }
        return "\\\\?\\$text"
    }

    private fun nulString(buffer: CharArray): String {
        val end = buffer.indexOf('\u0000')
        check(end >= 1) { "Windows report custody observed an invalid native string" }
        return buffer.concatToString(0, end)
    }

    private const val ACL_TYPE_EXTENDED = 0x00000100
    private const val ACL_FIRST_ENTRY = 0
    private const val ENOENT = 2
    private const val EINVAL = 22
    private const val FILE_READ_ATTRIBUTES = 0x00000080
    private const val FILE_SHARE_ALL = 0x00000007
    private const val OPEN_EXISTING = 3
    private const val FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000
    private const val FILE_FLAG_BACKUP_SEMANTICS = 0x02000000
    private const val FILE_ATTRIBUTE_DIRECTORY = 0x00000010
    private const val FILE_ATTRIBUTE_REPARSE_POINT = 0x00000400
    private const val FILE_READ_ONLY_VOLUME = 0x00080000
    private const val ERROR_MORE_DATA = 234
    private const val INVALID_HANDLE_VALUE = -1L
}

internal data class WindowsVolumeObservation(
    val driveType: Int,
    val fileSystem: String,
    val volumeSerialNumber: Long,
    val readOnly: Boolean,
    val mountRoot: String,
    val volumeGuid: String,
    val finalPath: String,
)

internal class WindowsVolumeInfo(val fileSystem: String, val serial: Long, val readOnly: Boolean)

@Structure.FieldOrder(
    "dwFileAttributes", "ftCreationTime", "ftLastAccessTime", "ftLastWriteTime",
    "dwVolumeSerialNumber", "nFileSizeHigh", "nFileSizeLow", "nNumberOfLinks",
    "nFileIndexHigh", "nFileIndexLow",
)
internal class WindowsByHandleFileInformation : Structure() {
    @JvmField var dwFileAttributes: Int = 0
    @JvmField var ftCreationTime: WindowsFileTime = WindowsFileTime()
    @JvmField var ftLastAccessTime: WindowsFileTime = WindowsFileTime()
    @JvmField var ftLastWriteTime: WindowsFileTime = WindowsFileTime()
    @JvmField var dwVolumeSerialNumber: Int = 0
    @JvmField var nFileSizeHigh: Int = 0
    @JvmField var nFileSizeLow: Int = 0
    @JvmField var nNumberOfLinks: Int = 0
    @JvmField var nFileIndexHigh: Int = 0
    @JvmField var nFileIndexLow: Int = 0
}

@Structure.FieldOrder("dwLowDateTime", "dwHighDateTime")
internal class WindowsFileTime : Structure() {
    @JvmField var dwLowDateTime: Int = 0
    @JvmField var dwHighDateTime: Int = 0
}

private interface MacLibC : Library {
    fun geteuid(): Int
    fun acl_get_file(path: String, type: Int): Pointer?
    fun acl_get_entry(acl: Pointer, entryId: Int, entry: PointerByReference): Int
    fun acl_free(obj: Pointer): Int
}

private interface WindowsKernel32 : StdCallLibrary {
    fun GetDriveTypeW(rootPathName: WString): Int
    fun GetVolumePathNameW(fileName: WString, volumePathName: CharArray, bufferLength: Int): Int
    fun GetVolumeNameForVolumeMountPointW(volumeMountPoint: WString, volumeName: CharArray,
        bufferLength: Int): Int
    fun GetVolumeInformationW(rootPathName: WString, volumeNameBuffer: CharArray?,
        volumeNameSize: Int, volumeSerialNumber: IntByReference?, maximumComponentLength: IntByReference?,
        fileSystemFlags: IntByReference?, fileSystemNameBuffer: CharArray?, fileSystemNameSize: Int): Int
    fun GetVolumeInformationByHandleW(hFile: Pointer, volumeNameBuffer: CharArray?,
        volumeNameSize: Int, volumeSerialNumber: IntByReference?, maximumComponentLength: IntByReference?,
        fileSystemFlags: IntByReference?, fileSystemNameBuffer: CharArray?, fileSystemNameSize: Int): Int
    fun GetFinalPathNameByHandleW(handle: Pointer, buffer: CharArray, length: Int, flags: Int): Int
    fun GetFileInformationByHandle(handle: Pointer, information: WindowsByHandleFileInformation): Int
    fun CreateFileW(fileName: WString, desiredAccess: Int, shareMode: Int, securityAttributes: Pointer?,
        creationDisposition: Int, flagsAndAttributes: Int, templateFile: Pointer?): Pointer
    fun CloseHandle(handle: Pointer): Int
}

private interface WindowsMpr : StdCallLibrary {
    fun WNetGetConnectionW(localName: WString, remoteName: CharArray, length: IntByReference): Int
}
