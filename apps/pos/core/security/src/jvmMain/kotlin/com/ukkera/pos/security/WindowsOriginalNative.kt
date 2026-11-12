package com.ukkera.pos.security

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.ptr.ShortByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Narrow native surface for the concrete Windows original-namespace owner. It exposes no
 * arbitrary public file or registry API: callers select only a base directory and the fixed
 * profile target name. Every observation is DATA for [WindowsSelfOwnedOriginalNamespace], not
 * installation approval, a target key, a boot session, history ownership or Master authority.
 * Only 64-bit Windows loads the libraries; unsupported hosts fail closed.
 */
internal object WindowsOriginalNative {
    const val DIRECTORY_ACL_FLAGS: Int = 0x3 // OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE

    private const val GENERIC_READ: Int = Int.MIN_VALUE
    private const val GENERIC_WRITE: Int = 0x40000000
    private const val READ_CONTROL: Int = 0x00020000
    private const val SHARE_READ: Int = 0x00000001
    private const val SHARE_WRITE: Int = 0x00000002
    private const val OPEN_EXISTING: Int = 3
    private const val CREATE_NEW: Int = 1
    private const val FILE_ATTRIBUTE_NORMAL: Int = 0x00000080
    private const val FILE_ATTRIBUTE_DIRECTORY: Int = 0x00000010
    private const val FILE_ATTRIBUTE_REPARSE_POINT: Int = 0x00000400
    private const val FILE_FLAG_BACKUP_SEMANTICS: Int = 0x02000000
    private const val FILE_FLAG_OPEN_REPARSE_POINT: Int = 0x00200000
    private const val FILE_TYPE_DISK: Int = 1
    private const val FILE_ATTRIBUTE_TAG_INFO_CLASS: Int = 9
    private const val FILE_ID_INFO_CLASS: Int = 18
    private const val ERROR_FILE_NOT_FOUND: Int = 2
    private const val ERROR_PATH_NOT_FOUND: Int = 3
    private const val ERROR_SHARING_VIOLATION: Int = 32
    private const val ERROR_LOCK_VIOLATION: Int = 33
    private const val ERROR_FILE_EXISTS: Int = 80
    private const val ERROR_INSUFFICIENT_BUFFER: Int = 122
    private const val ERROR_ALREADY_EXISTS: Int = 183
    private const val ERROR_NO_MORE_FILES: Int = 18
    private const val ERROR_NO_MORE_ITEMS: Int = 259
    private const val ERROR_NO_TOKEN: Int = 1008
    private const val TOKEN_QUERY: Int = 0x0008
    private const val SE_FILE_OBJECT: Int = 1
    private const val OWNER_SECURITY_INFORMATION: Int = 0x00000001
    private const val DACL_SECURITY_INFORMATION: Int = 0x00000004
    private const val FILE_ALL_ACCESS: Int = 0x001F01FF
    private const val KEY_ALL_ACCESS: Int = 0x000F003F
    private const val KEY_QUERY_VALUE: Int = 0x00000001
    private const val DRIVE_FIXED: Int = 3
    private const val REG_OPTION_NON_VOLATILE: Int = 0
    private const val REG_OPTION_OPEN_LINK: Int = 0x00000008
    private const val REG_CREATED_NEW_KEY: Int = 1
    private const val REG_OPENED_EXISTING_KEY: Int = 2
    private const val REG_BINARY: Int = 3
    private const val LOCKFILE_EXCLUSIVE_LOCK: Int = 0x00000002
    private const val LOCKFILE_FAIL_IMMEDIATELY: Int = 0x00000001
    private const val MAX_REGISTRY_VALUE_NAME_CHARS: Int = 256
    private const val MAX_REGISTRY_KEY_CLASS_CHARS: Int = 256
    private const val MAX_SECURITY_DESCRIPTOR_BYTES: Int = 64 * 1024
    private const val MAX_DIRECTORY_ENTRIES: Int = 64
    private const val MAX_FINAL_PATH_CHARS: Int = 32_768
    private const val REGISTRY_BASE: String = "Software\\Ukkera\\POS\\OriginalNativeNamespaceV2"
    private const val REGISTRY_LINK_VALUE: String = "SymbolicLinkValue"

    private val kernel get() = WindowsOriginalNativeAccess.kernel
    private val advapi get() = WindowsOriginalNativeAccess.advapi

    private val HKEY_CURRENT_USER: Pointer = Pointer(-2147483647L)
    private val REGISTRY_LINK_PREFIXES: List<String> = listOf(
        "Software",
        "Software\\Ukkera",
        "Software\\Ukkera\\POS",
        REGISTRY_BASE,
    )

    fun requireSupported() {
        if (!Platform.isWindows() || Native.POINTER_SIZE != 8) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        }
    }

    /** A process-token observation is not authority for an impersonating thread. */
    fun requireNoImpersonation() {
        val token = PointerByReference()
        val opened = advapi.OpenThreadToken(kernel.GetCurrentThread(), TOKEN_QUERY, 1, token)
        if (opened != 0) {
            val actual = token.value ?: run {
                WindowsOriginalCloseState.markUncertain()
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            }
            WindowsOriginalHandle(actual).closeConfirmed()
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
        }
        if (Native.getLastError() != ERROR_NO_TOKEN) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
        }
    }

    /** Existing file/directory facts for desktop client journals; no creation, no reparse following. */
    fun observeEntryFacts(path: String): WindowsEntryObservation {
        requireSupported()
        val handle = openDirectoryHandle(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        try {
            val info = fileInformation(handle.pointer)
            requireNoReparsePoint(handle.pointer, info)
            val identity = fileIdentity(handle.pointer)
            val owner = PointerByReference(); val descriptor = PointerByReference()
            if (advapi.GetSecurityInfo(handle.pointer, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION,
                    owner, null, null, null, descriptor) != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val security = checkNotNull(descriptor.value)
            val sid = try {
                val text = PointerByReference()
                check(advapi.ConvertSidToStringSidW(checkNotNull(owner.value), text) != 0)
                val value = checkNotNull(text.value)
                try { value.getWideString(0) } finally { kernel.LocalFree(value) }
            } finally { kernel.LocalFree(security) }
            val size = (info.nFileSizeHigh.toLong() shl 32) or (info.nFileSizeLow.toLong() and 0xffffffffL)
            val written = (info.ftLastWriteTime.high.toLong() shl 32) or (info.ftLastWriteTime.low.toLong() and 0xffffffffL)
            return WindowsEntryObservation(identity.volumeSerialNumber, identity.fileId, info.nNumberOfLinks, size, written,
                info.dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0, sid)
        } finally { handle.closeConfirmed() }
    }

    /** Existing file/directory identity only; nothing is created. A missing entry is unexplained state. */
    fun observeExistingEntry(path: String): WindowsOriginalFileIdentity {
        requireSupported()
        val handle = openDirectoryHandle(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        try { return fileIdentity(handle.pointer) } finally { handle.closeConfirmed() }
    }

    fun openSupportDirectory(path: Path): WindowsOriginalSupport {
        requireSupported()
        val normalized = path.toAbsolutePath().normalize()
        if (!path.isAbsolute || normalized.nameCount == 0 ||
            normalized.toString().contains('/')) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
        val expected = normalized.toString().replace('/', '\\')
        val handle = openDirectoryHandle(expected)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        try {
            val observation = observeSupportDirectory(handle.pointer, expected)
            val volumeUuid = requireLocalNtfsVolume(handle.pointer, expected)
            return WindowsOriginalSupport(handle, observation, volumeUuid)
        } catch (failure: Throwable) {
            try { handle.closeConfirmed() } catch (close: Throwable) { failure.addSuppressed(close) }
            throw failure
        }
    }

    fun createServiceDirectory(
        path: String,
        accountSid: String,
        serviceSid: String,
    ): WindowsOriginalHandle {
        withPrivateAttributes(accountSid, serviceSid, inheritable = true, rights = "FA") { attributes ->
            if (kernel.CreateDirectoryW(WString(nativePath(path)), attributes) == 0) {
                val error = Native.getLastError()
                if (error == ERROR_ALREADY_EXISTS) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
                }
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
        }
        return openDirectoryHandle(path)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    fun openServiceDirectory(path: String): WindowsOriginalHandle? = openDirectoryHandle(path)

    fun createLockFile(
        path: String,
        accountSid: String,
        serviceSid: String,
    ): WindowsOriginalHandle {
        val handle = withPrivateAttributes(accountSid, serviceSid, inheritable = false, rights = "FA") { attributes ->
            kernel.CreateFileW(
                WString(nativePath(path)),
                GENERIC_READ or GENERIC_WRITE or READ_CONTROL,
                SHARE_READ or SHARE_WRITE,
                attributes,
                CREATE_NEW,
                FILE_ATTRIBUTE_NORMAL or FILE_FLAG_OPEN_REPARSE_POINT,
                null,
            )
        }
        if (invalidHandle(handle)) {
            val error = Native.getLastError()
            if (error == ERROR_FILE_EXISTS || error == ERROR_ALREADY_EXISTS) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return WindowsOriginalHandle(handle)
    }

    fun openLockFile(path: String): WindowsOriginalHandle? {
        val handle = kernel.CreateFileW(
            WString(nativePath(path)),
            GENERIC_READ or GENERIC_WRITE or READ_CONTROL,
            SHARE_READ or SHARE_WRITE,
            null,
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL or FILE_FLAG_OPEN_REPARSE_POINT,
            null,
        )
        if (!invalidHandle(handle)) return WindowsOriginalHandle(handle)
        return when (val error = Native.getLastError()) {
            ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND -> null
            ERROR_SHARING_VIOLATION, ERROR_LOCK_VIOLATION ->
                selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    /** The retained handle owns the cross-process lease until its checked close. */
    fun acquireExclusiveLock(handle: Pointer) {
        Memory(32).use { overlapped ->
            overlapped.clear()
            if (kernel.LockFileEx(handle, LOCKFILE_EXCLUSIVE_LOCK or LOCKFILE_FAIL_IMMEDIATELY,
                    0, -1, -1, overlapped) == 0) {
                if (Native.getLastError() == ERROR_LOCK_VIOLATION) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
                }
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
        }
    }

    fun observeSupportDirectory(handle: Pointer, expectedPath: String): WindowsOriginalObservation {
        val information = fileInformation(handle)
        requireDirectoryInformation(information)
        requireNoReparsePoint(handle, information)
        val identity = fileIdentity(handle)
        return WindowsOriginalObservation(canonicalPath(handle, expectedPath), identity)
    }

    fun observeServiceDirectory(
        handle: Pointer,
        expectedPath: String,
        accountSid: String,
        serviceSid: String,
    ): WindowsOriginalObservation {
        val observation = observeSupportDirectory(handle, expectedPath)
        requirePrivateFileSecurity(handle, accountSid, serviceSid, FILE_ALL_ACCESS,
            DIRECTORY_ACL_FLAGS)
        return observation
    }

    fun observeLockFile(
        handle: Pointer,
        expectedPath: String,
        accountSid: String,
        serviceSid: String,
    ): WindowsOriginalObservation {
        val information = fileInformation(handle)
        if (information.dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0 ||
            information.nFileSizeHigh != 0 || information.nFileSizeLow != 0 ||
            information.nNumberOfLinks != 1) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        requireNoReparsePoint(handle, information)
        val identity = fileIdentity(handle)
        requirePrivateFileSecurity(handle, accountSid, serviceSid, FILE_ALL_ACCESS, 0)
        return WindowsOriginalObservation(canonicalPath(handle, expectedPath), identity)
    }

    /** Requires an existing local fixed NTFS volume; never substitutes a serial-number guess. */
    fun requireVolume(handle: Pointer, path: String): String = requireLocalNtfsVolume(handle, path)

    fun directoryNames(path: String): List<String> {
        val data = WindowsOriginalFindData()
        val search = kernel.FindFirstFileW(WString(nativePath(path) + "\\*"), data)
        if (invalidHandle(search)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val names = ArrayList<String>()
        var failure: Throwable? = null
        try {
            while (true) {
                data.read()
                val name = data.fileName()
                if (name != "." && name != "..") {
                    if (names.size >= MAX_DIRECTORY_ENTRIES || name.contains('\\') ||
                        name.contains('/') || name.contains('\u0000')) {
                        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                    }
                    names += name
                }
                if (kernel.FindNextFileW(search, data) == 0) {
                    val error = Native.getLastError()
                    if (error != ERROR_NO_MORE_FILES) {
                        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                    }
                    break
                }
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (kernel.FindClose(search) == 0 && failure == null) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
        }
        return names
    }

    fun registryProfilePath(profile: String): String = "$REGISTRY_BASE\\$profile"

    fun registryLeafPath(profile: String, targetDigest: String): String =
        "${registryProfilePath(profile)}\\$targetDigest"

    fun requireNoRegistryLinks(path: String, allowMissing: Boolean) {
        val prefixes = buildList {
            addAll(REGISTRY_LINK_PREFIXES)
            val relative = path.removePrefix(REGISTRY_BASE + "\\").split('\\')
            if (relative.isNotEmpty() && relative.first().isNotEmpty()) {
                var current = REGISTRY_BASE
                for (part in relative) {
                    current = "$current\\$part"
                    add(current)
                }
            }
        }
        for (prefix in prefixes.distinct()) {
            val key = openRegistryKey(prefix, KEY_QUERY_VALUE or READ_CONTROL)
            if (key == null) {
                if (allowMissing) continue
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            useRegistryKey(WindowsOriginalRegistryKey(key)) { requireNoLinkValue(it) }
        }
    }

    fun ensureRegistryComponent(
        path: String,
        accountSid: String,
        serviceSid: String,
    ): WindowsOriginalRegistryKey {
        val existing = openRegistryKey(path, KEY_ALL_ACCESS)
        if (existing != null) {
            try {
                return validateRegistryComponent(existing, accountSid, serviceSid)
            } catch (failure: Throwable) {
                closeRegistryAfterFailure(existing, failure)
                throw failure
            }
        }
        return createRegistryComponent(path, accountSid, serviceSid, createOnly = false)
    }

    fun createRegistryComponent(
        path: String,
        accountSid: String,
        serviceSid: String,
        createOnly: Boolean,
    ): WindowsOriginalRegistryKey {
        val result = PointerByReference()
        val disposition = IntByReference()
        withPrivateAttributes(accountSid, serviceSid, inheritable = false, rights = "KA") { attributes ->
            val status = advapi.RegCreateKeyExW(
                HKEY_CURRENT_USER, WString(path), 0, null, REG_OPTION_NON_VOLATILE,
                KEY_ALL_ACCESS, attributes, result, disposition,
            )
            if (status != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val handle = checkNotNull(result.value) {
            "Windows original registry key handle is absent"
        }
        try {
            if (disposition.value != REG_CREATED_NEW_KEY &&
                disposition.value != REG_OPENED_EXISTING_KEY) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            if (createOnly && disposition.value != REG_CREATED_NEW_KEY) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
            if (disposition.value == REG_CREATED_NEW_KEY) flushRegistryKey(handle)
            return validateRegistryComponent(handle, accountSid, serviceSid)
        } catch (failure: Throwable) {
            closeRegistryAfterFailure(handle, failure)
            throw failure
        }
    }

    fun openExistingRegistryComponent(
        path: String,
        accountSid: String,
        serviceSid: String,
    ): WindowsOriginalRegistryKey? {
        val handle = openRegistryKey(path, KEY_ALL_ACCESS) ?: return null
        try {
            return validateRegistryComponent(handle, accountSid, serviceSid)
        } catch (failure: Throwable) {
            closeRegistryAfterFailure(handle, failure)
            throw failure
        }
    }

    fun requireRegistryBase(key: Pointer) {
        val info = registryInfo(key)
        if (info.classNameChars != 0 || info.values != 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    fun requireRegistryProfile(key: Pointer, expectedLeaf: String, allowEmpty: Boolean) {
        val info = registryInfo(key)
        if (info.classNameChars != 0 || info.values != 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val names = registrySubkeyNames(key)
        if (allowEmpty) {
            if (names.isNotEmpty()) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        } else if (names != listOf(expectedLeaf)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    fun requireRegistryLeaf(key: Pointer, allowedValues: Set<String>) {
        val info = registryInfo(key)
        if (info.classNameChars != 0 || info.subkeys != 0 || info.values !in 0..allowedValues.size) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val names = registryValueNames(key)
        if (names.size != info.values || names.toSet().size != names.size ||
            names.any { it !in allowedValues }) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    fun readRegistryValue(key: Pointer, valueName: String, maximum: Int): ByteArray? {
        val type = IntByReference()
        val size = IntByReference()
        val probe = advapi.RegQueryValueExW(key, WString(valueName), null, type, null, size)
        if (probe == ERROR_FILE_NOT_FOUND) return null
        if (probe != 0 || type.value != REG_BINARY || size.value !in 1..maximum) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val memory = Memory(size.value.toLong())
        try {
            val readSize = IntByReference(size.value)
            val readType = IntByReference()
            val status = advapi.RegQueryValueExW(key, WString(valueName), null, readType,
                memory, readSize)
            if (status != 0 || readType.value != REG_BINARY || readSize.value != size.value) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return ByteArray(readSize.value).also { memory.read(0, it, 0, it.size) }
        } finally {
            memory.clear()
            memory.close()
        }
    }

    fun writeRegistryValue(
        key: Pointer,
        valueName: String,
        value: ByteArray,
        maximum: Int,
    ): ByteArray {
        if (value.size !in 1..maximum) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val memory = Memory(value.size.toLong())
        try {
            memory.write(0, value, 0, value.size)
            val status = advapi.RegSetValueExW(key, WString(valueName), 0, REG_BINARY,
                memory, value.size)
            if (status != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            flushRegistryKey(key)
            val readback = readRegistryValue(key, valueName, maximum)
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            if (!MessageDigest.isEqual(readback, value)) {
                readback.fill(0)
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return readback
        } finally {
            memory.clear()
            memory.close()
        }
    }

    fun <T> useRegistryKey(handle: WindowsOriginalRegistryKey, block: (Pointer) -> T): T {
        var primary: Throwable? = null
        try {
            return block(handle.pointer)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                handle.closeConfirmed()
            } catch (close: Throwable) {
                if (primary != null) primary.addSuppressed(close) else throw close
            }
        }
    }

    private fun validateRegistryComponent(
        handle: Pointer,
        accountSid: String,
        serviceSid: String,
    ): WindowsOriginalRegistryKey {
        requireNoLinkValue(handle)
        requirePrivateRegistrySecurity(handle, accountSid, serviceSid)
        return WindowsOriginalRegistryKey(handle)
    }

    private fun closeRegistryAfterFailure(handle: Pointer, failure: Throwable) {
        try { WindowsOriginalRegistryKey(handle).closeConfirmed() }
        catch (close: Throwable) { failure.addSuppressed(close) }
    }

    private fun openRegistryKey(path: String, desiredAccess: Int): Pointer? {
        val result = PointerByReference()
        val status = advapi.RegOpenKeyExW(HKEY_CURRENT_USER, WString(path), REG_OPTION_OPEN_LINK,
            desiredAccess, result)
        if (status == 0) return checkNotNull(result.value)
        if (status == ERROR_FILE_NOT_FOUND || status == ERROR_PATH_NOT_FOUND) return null
        selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    private fun requireNoLinkValue(key: Pointer) {
        val type = IntByReference()
        val size = IntByReference()
        when (val status = advapi.RegQueryValueExW(key, WString(REGISTRY_LINK_VALUE), null,
            type, null, size)) {
            0 -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            ERROR_FILE_NOT_FOUND -> Unit
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun flushRegistryKey(key: Pointer) {
        if (advapi.RegFlushKey(key) != 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun registryInfo(key: Pointer): WindowsOriginalRegistryInfo {
        val className = Memory(MAX_REGISTRY_KEY_CLASS_CHARS * 2L)
        val classLength = IntByReference(MAX_REGISTRY_KEY_CLASS_CHARS)
        val subkeys = IntByReference()
        val values = IntByReference()
        try {
            val status = advapi.RegQueryInfoKeyW(key, className, classLength, null,
                subkeys, null, null, values, null, null, null, null)
            if (status != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            if (subkeys.value < 0 || values.value < 0 || classLength.value !in 0..MAX_REGISTRY_KEY_CLASS_CHARS) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return WindowsOriginalRegistryInfo(subkeys.value, values.value, classLength.value)
        } finally {
            className.clear()
            className.close()
        }
    }

    private fun registrySubkeyNames(key: Pointer): List<String> {
        val names = ArrayList<String>()
        val buffer = Memory(MAX_REGISTRY_VALUE_NAME_CHARS * 2L)
        try {
            var index = 0
            while (true) {
                if (index >= 64) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                val length = IntByReference(MAX_REGISTRY_VALUE_NAME_CHARS)
                val status = advapi.RegEnumKeyExW(key, index, buffer, length, null,
                    null, null, null)
                if (status == ERROR_NO_MORE_ITEMS) break
                if (status != 0 || length.value !in 1..MAX_REGISTRY_VALUE_NAME_CHARS) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                val name = buffer.getWideString(0)
                if (name.length != length.value) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                names += name
                index++
            }
            return names
        } finally {
            buffer.clear()
            buffer.close()
        }
    }

    private fun registryValueNames(key: Pointer): List<String> {
        val names = ArrayList<String>()
        val buffer = Memory(MAX_REGISTRY_VALUE_NAME_CHARS * 2L)
        try {
            var index = 0
            while (true) {
                if (index >= 64) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                val length = IntByReference(MAX_REGISTRY_VALUE_NAME_CHARS)
                val status = advapi.RegEnumValueW(key, index, buffer, length, null,
                    null, null, null)
                if (status == ERROR_NO_MORE_ITEMS) break
                if (status != 0 || length.value !in 1..MAX_REGISTRY_VALUE_NAME_CHARS) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                val name = buffer.getWideString(0)
                if (name.length != length.value) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                names += name
                index++
            }
            return names
        } finally {
            buffer.clear()
            buffer.close()
        }
    }

    private fun openDirectoryHandle(path: String): WindowsOriginalHandle? {
        val handle = kernel.CreateFileW(
            WString(nativePath(path)),
            GENERIC_READ or READ_CONTROL,
            SHARE_READ or SHARE_WRITE,
            null,
            OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OPEN_REPARSE_POINT,
            null,
        )
        if (!invalidHandle(handle)) return WindowsOriginalHandle(handle)
        return when (val error = Native.getLastError()) {
            ERROR_FILE_NOT_FOUND, ERROR_PATH_NOT_FOUND -> null
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun fileInformation(handle: Pointer): WindowsOriginalByHandleFileInformation {
        val information = WindowsOriginalByHandleFileInformation()
        if (kernel.GetFileInformationByHandle(handle, information) == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        information.read()
        return information
    }

    private fun requireDirectoryInformation(information: WindowsOriginalByHandleFileInformation) {
        if (information.dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun requireNoReparsePoint(
        handle: Pointer,
        information: WindowsOriginalByHandleFileInformation,
    ) {
        if (information.dwFileAttributes and FILE_ATTRIBUTE_REPARSE_POINT != 0 ||
            kernel.GetFileType(handle) != FILE_TYPE_DISK) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val tag = attributeTag(handle)
        if (tag.first and FILE_ATTRIBUTE_REPARSE_POINT != 0 || tag.second != 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun fileIdentity(handle: Pointer): WindowsOriginalFileIdentity {
        val buffer = Memory(24)
        try {
            if (kernel.GetFileInformationByHandleEx(handle, FILE_ID_INFO_CLASS, buffer, 24) == 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val volumeSerial = buffer.getLong(0)
            val fileId = buffer.getByteArray(8, 16)
            try {
                if (fileId.all { it == 0.toByte() }) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                val hex = buildString(32) {
                    for (byte in fileId) {
                        val value = byte.toInt() and 255
                        append("0123456789abcdef"[value ushr 4])
                        append("0123456789abcdef"[value and 15])
                    }
                }
                return WindowsOriginalFileIdentity(volumeSerial, hex)
            } finally {
                fileId.fill(0)
            }
        } finally {
            buffer.clear()
            buffer.close()
        }
    }

    private fun canonicalPath(handle: Pointer, expectedPath: String): String {
        val buffer = Memory(MAX_FINAL_PATH_CHARS.toLong() * 2)
        try {
            val length = kernel.GetFinalPathNameByHandleW(handle, buffer, MAX_FINAL_PATH_CHARS, 0)
            if (length !in 1 until MAX_FINAL_PATH_CHARS) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val raw = buffer.getWideString(0)
            if (raw.length != length || raw.startsWith("\\\\?\\UNC\\")) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val text = if (raw.startsWith("\\\\?\\")) raw.substring(4) else raw
            if (!text.equals(expectedPath, ignoreCase = true)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return text
        } finally {
            buffer.clear()
            buffer.close()
        }
    }

    private fun attributeTag(handle: Pointer): Pair<Int, Int> {
        val buffer = Memory(8)
        try {
            if (kernel.GetFileInformationByHandleEx(handle, FILE_ATTRIBUTE_TAG_INFO_CLASS,
                    buffer, 8) == 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return buffer.getInt(0) to buffer.getInt(4)
        } finally {
            buffer.clear()
            buffer.close()
        }
    }

    private fun requireLocalNtfsVolume(handle: Pointer, path: String): String {
        val mountPoint = Memory(MAX_FINAL_PATH_CHARS.toLong() * 2)
        val volumeName = Memory(MAX_FINAL_PATH_CHARS.toLong() * 2)
        try {
            if (kernel.GetVolumePathNameW(WString(path), mountPoint,
                    MAX_FINAL_PATH_CHARS) == 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val mount = mountPoint.getWideString(0)
            if (mount.isEmpty() || kernel.GetDriveTypeW(WString(mount)) != DRIVE_FIXED) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
            }
            val fileSystem = Memory(256 * 2)
            try {
                if (kernel.GetVolumeInformationByHandleW(handle, null, 0, null, null,
                        null, fileSystem, 256) == 0) {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                if (fileSystem.getWideString(0) != "NTFS") {
                    selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
                }
            } finally {
                fileSystem.clear()
                fileSystem.close()
            }
            val mountWithSlash = if (mount.endsWith("\\")) mount else "$mount\\"
            if (kernel.GetVolumeNameForVolumeMountPointW(WString(mountWithSlash), volumeName,
                    MAX_FINAL_PATH_CHARS) == 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val volume = volumeName.getWideString(0)
            val open = volume.indexOf('{')
            val close = volume.indexOf('}')
            if (open < 0 || close != open + 37) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val uuid = volume.substring(open + 1, close).lowercase(java.util.Locale.ROOT)
            if (uuid.length != 36 || uuid.count { it == '-' } != 4 ||
                uuid.withIndex().any { (index, character) ->
                    if (index == 8 || index == 13 || index == 18 || index == 23) {
                        character != '-'
                    } else {
                        character !in '0'..'9' && character !in 'a'..'f'
                    }
                } || uuid.all { it == '0' || it == '-' }) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            return uuid
        } finally {
            mountPoint.clear()
            volumeName.clear()
            mountPoint.close()
            volumeName.close()
        }
    }

    private fun requirePrivateFileSecurity(
        handle: Pointer,
        accountSid: String,
        serviceSid: String,
        expectedMask: Int,
        expectedAceFlags: Int,
    ) {
        val owner = PointerByReference()
        val dacl = PointerByReference()
        val descriptor = PointerByReference()
        val result = advapi.GetSecurityInfo(handle, SE_FILE_OBJECT,
            OWNER_SECURITY_INFORMATION or DACL_SECURITY_INFORMATION,
            owner, null, dacl, null, descriptor)
        if (result != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val security = checkNotNull(descriptor.value) {
            "Windows original security descriptor is absent"
        }
        try {
            requireSecurityDescriptor(security, owner.value, dacl.value, accountSid, serviceSid,
                expectedMask, expectedAceFlags)
        } finally {
            kernel.LocalFree(security)
        }
    }

    private fun requirePrivateRegistrySecurity(
        handle: Pointer,
        accountSid: String,
        serviceSid: String,
    ) {
        val required = IntByReference(0)
        val information = OWNER_SECURITY_INFORMATION or DACL_SECURITY_INFORMATION
        val probe = advapi.RegGetKeySecurity(handle, information, null, required)
        if ((probe != ERROR_INSUFFICIENT_BUFFER && probe != 0) ||
            required.value !in 1..MAX_SECURITY_DESCRIPTOR_BYTES) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val descriptor = Memory(required.value.toLong())
        try {
            val actualSize = IntByReference(required.value)
            val actual = advapi.RegGetKeySecurity(handle, information, descriptor, actualSize)
            if (actual != 0 || actualSize.value !in 1..required.value) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val owner = PointerByReference()
            val ownerDefaulted = IntByReference()
            if (advapi.GetSecurityDescriptorOwner(descriptor, owner, ownerDefaulted) == 0 ||
                ownerDefaulted.value != 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val present = IntByReference()
            val dacl = PointerByReference()
            val daclDefaulted = IntByReference()
            if (advapi.GetSecurityDescriptorDacl(descriptor, present, dacl, daclDefaulted) == 0 ||
                present.value == 0 || daclDefaulted.value != 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            requireSecurityDescriptor(descriptor, owner.value, dacl.value, accountSid, serviceSid,
                KEY_ALL_ACCESS, 0)
        } finally {
            descriptor.clear()
            descriptor.close()
        }
    }

    private fun requireSecurityDescriptor(
        descriptor: Pointer,
        owner: Pointer?,
        dacl: Pointer?,
        accountSid: String,
        serviceSid: String,
        expectedMask: Int,
        expectedAceFlags: Int,
    ) {
        if (advapi.IsValidSecurityDescriptor(descriptor) == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val control = ShortByReference()
        val revision = IntByReference()
        if (advapi.GetSecurityDescriptorControl(descriptor, control, revision) == 0 ||
            revision.value != 1) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val bits = control.value.toInt() and 0xffff
        if (bits and 0x1004 != 0x1004 || bits and 0x0009 != 0 || bits and 0x8000 == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        if (owner == null || sidText(owner) != accountSid) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val acl = checkNotNull(dacl) {
            "Windows original security descriptor has a null DACL"
        }
        if (advapi.IsValidAcl(acl) == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val count = acl.getShort(4).toInt() and 0xffff
        if (count != 1) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val output = PointerByReference()
        if (advapi.GetAce(acl, 0, output) == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val ace = checkNotNull(output.value) {
            "Windows original access entry is absent"
        }
        val flags = ace.getByte(1).toInt() and 0xff
        val size = ace.getShort(2).toInt() and 0xffff
        val sid = ace.share(8)
        if (ace.getByte(0).toInt() != 0 || flags != expectedAceFlags ||
            ace.getInt(4) != expectedMask || advapi.IsValidSid(sid) == 0 ||
            size != 8 + advapi.GetLengthSid(sid) || sidText(sid) != serviceSid) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun sidText(sid: Pointer): String {
        if (advapi.IsValidSid(sid) == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val text = PointerByReference()
        if (advapi.ConvertSidToStringSidW(sid, text) == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val value = checkNotNull(text.value) {
            "Windows original SID text is absent"
        }
        return try { value.getWideString(0) } finally { kernel.LocalFree(value) }
    }

    private fun <T> withPrivateAttributes(
        accountSid: String,
        serviceSid: String,
        inheritable: Boolean,
        rights: String,
        work: (Pointer) -> T,
    ): T {
        if (!SelfOwnedInstalledIdentityGrammarV2.accountSid(accountSid) ||
            !SelfOwnedInstalledIdentityGrammarV2.serviceSid(serviceSid)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
        }
        val descriptor = PointerByReference()
        val flags = if (inheritable) "OICI" else ""
        val text = "O:$accountSid" + "D:P(A;" + flags + ";$rights;;;$serviceSid)"
        if (advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(WString(text), 1,
                descriptor, null) == 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        try {
            val attributes = WindowsOriginalSecurityAttributes().also {
                it.length = it.size()
                it.descriptor = checkNotNull(descriptor.value)
                it.inheritHandle = 0
                it.write()
            }
            return work(attributes.pointer)
        } finally {
            descriptor.value?.let { kernel.LocalFree(it) }
        }
    }

    private fun invalidHandle(handle: Pointer): Boolean = Pointer.nativeValue(handle) == -1L

    private fun nativePath(path: String): String {
        if (path.length !in 3..32600 || path[0] !in 'A'..'Z' && path[0] !in 'a'..'z' ||
            path[1] != ':' || path[2] != '\\' || path.endsWith("\\") ||
            path.contains('/') || path.contains('\u0000') || "\\\\" in path ||
            path.indexOf(':', 2) >= 0) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return "\\\\?\\$path"
    }
}

internal data class WindowsOriginalFileIdentity(
    val volumeSerialNumber: Long,
    val fileId: String,
) {
    override fun toString(): String = "WindowsOriginalFileIdentity([REDACTED])"
}

internal data class WindowsOriginalObservation(
    val canonicalPath: String,
    val identity: WindowsOriginalFileIdentity,
) {
    override fun toString(): String = "WindowsOriginalObservation([REDACTED])"
}

internal class WindowsOriginalSupport internal constructor(
    val handle: WindowsOriginalHandle,
    val observation: WindowsOriginalObservation,
    val volumeUuid: String,
) {
    override fun toString(): String = "WindowsOriginalSupport([REDACTED])"
}

/** Unknown native or registry close status prevents new custody and lease release in this process. */
internal object WindowsOriginalCloseState {
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
    fun releaseLease(handle: WindowsOriginalHandle) {
        requireConfirmed()
        handle.closeConfirmed()
    }
}

/** A failed close is terminal and never retried, including during acquisition cleanup. */
internal class WindowsOriginalHandle internal constructor(handle: Pointer) {
    private var value: Pointer? = handle
    private var closeUncertain = false

    val pointer: Pointer
        get() = synchronized(WindowsOriginalCloseState) {
            value ?: selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }

    fun closeConfirmed(): Unit = synchronized(WindowsOriginalCloseState) {
        if (closeUncertain) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val actual = value ?: return@synchronized
        value = null
        closeUncertain = true
        try {
            if (WindowsOriginalNativeAccess.kernel.CloseHandle(actual) == 0)
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            closeUncertain = false
        } catch (failure: Throwable) {
            WindowsOriginalCloseState.markUncertain()
            throw failure
        }
    }

    override fun toString(): String = "WindowsOriginalHandle([REDACTED])"
}

/** Temporary registry handles participate in the same terminal close-uncertainty boundary. */
internal class WindowsOriginalRegistryKey internal constructor(key: Pointer) {
    private var value: Pointer? = key
    private var closeUncertain = false

    val pointer: Pointer
        get() = synchronized(WindowsOriginalCloseState) {
            value ?: selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        }

    fun closeConfirmed(): Unit = synchronized(WindowsOriginalCloseState) {
        if (closeUncertain) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        val actual = value ?: return@synchronized
        value = null
        closeUncertain = true
        try {
            if (WindowsOriginalNativeAccess.advapi.RegCloseKey(actual) != 0)
                selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
            closeUncertain = false
        } catch (failure: Throwable) {
            WindowsOriginalCloseState.markUncertain()
            throw failure
        }
    }

    override fun toString(): String = "WindowsOriginalRegistryKey([REDACTED])"
}

private data class WindowsOriginalRegistryInfo(
    val subkeys: Int,
    val values: Int,
    val classNameChars: Int,
)

internal object WindowsOriginalNativeAccess {
    val kernel: WindowsOriginalKernel32 = Native.load("kernel32", WindowsOriginalKernel32::class.java)
    val advapi: WindowsOriginalAdvapi32 = Native.load("advapi32", WindowsOriginalAdvapi32::class.java)
}

internal interface WindowsOriginalKernel32 : StdCallLibrary {
    fun GetCurrentThread(): Pointer
    fun CloseHandle(handle: Pointer): Int
    fun LocalFree(memory: Pointer): Pointer?
    fun CreateDirectoryW(path: WString, attributes: Pointer?): Int
    fun CreateFileW(path: WString, access: Int, share: Int, attributes: Pointer?,
        disposition: Int, flags: Int, template: Pointer?): Pointer
    fun GetFileType(handle: Pointer): Int
    fun GetFileInformationByHandle(handle: Pointer, information: WindowsOriginalByHandleFileInformation): Int
    fun GetFileInformationByHandleEx(handle: Pointer, informationClass: Int, information: Pointer,
        size: Int): Int
    fun GetFinalPathNameByHandleW(handle: Pointer, path: Pointer, length: Int, flags: Int): Int
    fun GetVolumePathNameW(path: WString, volumePathName: Pointer, length: Int): Int
    fun GetVolumeNameForVolumeMountPointW(mountPoint: WString, volumeName: Pointer, length: Int): Int
    fun GetVolumeInformationByHandleW(handle: Pointer, volumeName: Pointer?, volumeNameSize: Int,
        volumeSerial: IntByReference?, maxComponentLength: IntByReference?,
        fileSystemFlags: IntByReference?, fileSystemName: Pointer?, fileSystemNameSize: Int): Int
    fun GetDriveTypeW(root: WString): Int
    fun LockFileEx(handle: Pointer, flags: Int, reserved: Int, bytesLow: Int, bytesHigh: Int,
        overlapped: Pointer): Int
    fun FindFirstFileW(pattern: WString, data: WindowsOriginalFindData): Pointer
    fun FindNextFileW(handle: Pointer, data: WindowsOriginalFindData): Int
    fun FindClose(handle: Pointer): Int
}

internal interface WindowsOriginalAdvapi32 : StdCallLibrary {
    fun OpenThreadToken(thread: Pointer, access: Int, openAsSelf: Int, token: PointerByReference): Int
    fun GetSecurityInfo(handle: Pointer, objectType: Int, securityInfo: Int, owner: PointerByReference?,
        group: PointerByReference?, dacl: PointerByReference?, sacl: PointerByReference?,
        descriptor: PointerByReference): Int
    fun GetSecurityDescriptorControl(descriptor: Pointer, control: ShortByReference,
        revision: IntByReference): Int
    fun GetSecurityDescriptorOwner(descriptor: Pointer, owner: PointerByReference,
        defaulted: IntByReference): Int
    fun GetSecurityDescriptorDacl(descriptor: Pointer, present: IntByReference,
        dacl: PointerByReference, defaulted: IntByReference): Int
    fun IsValidSecurityDescriptor(descriptor: Pointer): Int
    fun IsValidAcl(acl: Pointer): Int
    fun GetAce(acl: Pointer, index: Int, ace: PointerByReference): Int
    fun IsValidSid(sid: Pointer): Int
    fun GetLengthSid(sid: Pointer): Int
    fun ConvertSidToStringSidW(sid: Pointer, text: PointerByReference): Int
    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(text: WString, revision: Int,
        descriptor: PointerByReference, size: IntByReference?): Int
    fun RegOpenKeyExW(root: Pointer, subKey: WString, options: Int, desired: Int,
        result: PointerByReference): Int
    fun RegCreateKeyExW(root: Pointer, subKey: WString, reserved: Int, className: WString?,
        options: Int, desired: Int, attributes: Pointer?, result: PointerByReference,
        disposition: IntByReference): Int
    fun RegCloseKey(key: Pointer): Int
    fun RegQueryInfoKeyW(key: Pointer, className: Pointer?, classNameLength: IntByReference?,
        reserved: Pointer?, subkeys: IntByReference?, maxSubkeyLength: IntByReference?,
        maxClassLength: IntByReference?, values: IntByReference?, maxValueNameLength: IntByReference?,
        maxValueLength: IntByReference?, securityDescriptor: IntByReference?,
        lastWriteTime: Pointer?): Int
    fun RegEnumKeyExW(key: Pointer, index: Int, name: Pointer?, nameLength: IntByReference,
        reserved: IntByReference?, className: Pointer?, classLength: IntByReference?,
        lastWriteTime: Pointer?): Int
    fun RegEnumValueW(key: Pointer, index: Int, valueName: Pointer?, valueNameLength: IntByReference,
        reserved: IntByReference?, type: IntByReference?, data: Pointer?,
        dataLength: IntByReference?): Int
    fun RegQueryValueExW(key: Pointer, valueName: WString?, reserved: Pointer?,
        type: IntByReference?, data: Pointer?, dataLength: IntByReference?): Int
    fun RegSetValueExW(key: Pointer, valueName: WString?, reserved: Int, type: Int,
        data: Pointer?, dataLength: Int): Int
    fun RegFlushKey(key: Pointer): Int
    fun RegGetKeySecurity(key: Pointer, information: Int, descriptor: Pointer?,
        descriptorLength: IntByReference): Int
}

@Structure.FieldOrder("length", "descriptor", "inheritHandle")
internal class WindowsOriginalSecurityAttributes : Structure() {
    @JvmField var length: Int = 0
    @JvmField var descriptor: Pointer? = null
    @JvmField var inheritHandle: Int = 0
}

@Structure.FieldOrder("low", "high")
internal class WindowsOriginalFileTime : Structure() {
    @JvmField var low: Int = 0
    @JvmField var high: Int = 0
}

@Structure.FieldOrder("dwFileAttributes", "ftCreationTime", "ftLastAccessTime", "ftLastWriteTime",
    "nFileSizeHigh", "nFileSizeLow", "dwReserved0", "dwReserved1", "cFileName", "cAlternateFileName")
internal class WindowsOriginalFindData : Structure() {
    @JvmField var dwFileAttributes: Int = 0
    @JvmField var ftCreationTime: WindowsOriginalFileTime = WindowsOriginalFileTime()
    @JvmField var ftLastAccessTime: WindowsOriginalFileTime = WindowsOriginalFileTime()
    @JvmField var ftLastWriteTime: WindowsOriginalFileTime = WindowsOriginalFileTime()
    @JvmField var nFileSizeHigh: Int = 0
    @JvmField var nFileSizeLow: Int = 0
    @JvmField var dwReserved0: Int = 0
    @JvmField var dwReserved1: Int = 0
    @JvmField var cFileName: ShortArray = ShortArray(260)
    @JvmField var cAlternateFileName: ShortArray = ShortArray(14)

    fun fileName(): String {
        var length = 0
        while (length < cFileName.size && cFileName[length].toInt() != 0) length++
        return CharArray(length) { cFileName[it].toInt().toChar() }.concatToString()
    }
}

@Structure.FieldOrder("dwFileAttributes", "ftCreationTime", "ftLastAccessTime", "ftLastWriteTime",
    "dwVolumeSerialNumber", "nFileSizeHigh", "nFileSizeLow", "nNumberOfLinks",
    "nFileIndexHigh", "nFileIndexLow")
internal class WindowsOriginalByHandleFileInformation : Structure() {
    @JvmField var dwFileAttributes: Int = 0
    @JvmField var ftCreationTime: WindowsOriginalFileTime = WindowsOriginalFileTime()
    @JvmField var ftLastAccessTime: WindowsOriginalFileTime = WindowsOriginalFileTime()
    @JvmField var ftLastWriteTime: WindowsOriginalFileTime = WindowsOriginalFileTime()
    @JvmField var dwVolumeSerialNumber: Int = 0
    @JvmField var nFileSizeHigh: Int = 0
    @JvmField var nFileSizeLow: Int = 0
    @JvmField var nNumberOfLinks: Int = 0
    @JvmField var nFileIndexHigh: Int = 0
    @JvmField var nFileIndexLow: Int = 0
}
