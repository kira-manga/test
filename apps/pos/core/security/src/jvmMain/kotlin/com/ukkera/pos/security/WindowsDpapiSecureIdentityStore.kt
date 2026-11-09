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
import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.nio.ByteBuffer
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Official SDK opaque identity storage protected by Windows DPAPI in the current user's
 * nonvolatile registry key `HKCU\Software\Ukkera\POS\SecureIdentityV1`. That key is created
 * atomically with an explicit owner-only protected DACL and is reopened and validated for
 * every operation. A namespace is hashed for its value name and bound into DPAPI optional
 * entropy. Values retain the strict magic/version/namespace/length envelope and are stored
 * as `REG_BINARY` ciphertext; inputs larger than 768 KiB are refused so the complete value
 * stays inside the standard-format registry value limit.
 *
 * Values are copied defensively and are never truncated. Every registry open rejects foreign
 * owners or DACLs, unreadable keys, key classes, subkeys, unexpected value names and symbolic
 * links; namespace reads reject invalid types, oversize values and changed data. Mutations call
 * `RegFlushKey` and verify exact readback before reporting success; flush failures fail closed.
 * A private LocalAppData lock root only holds cross-process namespace lock files. File-backed
 * identity values from the earlier uncomposed adapter are detected there and refused, never
 * silently adopted or ignored. Explicit deletion can still remove a corrupt target value so an
 * SDK reset is not blocked by the corruption it is meant to clear.
 * This store is not installed-application identity, hardware-backed key custody or OS
 * runtime validation.
 */
public class WindowsDpapiSecureIdentityStore private constructor(
    private val directory: Path,
    private val directoryNative: String,
    private val accountSid: ByteArray,
    private val directoryIdentity: WindowsDirectoryIdentity,
) : JvmSecureIdentityStore {
    override fun read(namespace: String): ByteArray? {
        val target = WindowsIdentityNamespace.create(namespace)
        return withNamespace(target) {
            withRegistryKey { key -> readCommitted(key, target) }
        }
    }

    override fun putIfAbsent(namespace: String, value: ByteArray): ByteArray {
        require(value.size <= MAX_VALUE_BYTES) { "Windows identity value exceeds supported capacity" }
        val snapshot = value.copyOf()
        try {
            val target = WindowsIdentityNamespace.create(namespace)
            return withNamespace(target) {
                withRegistryKey { key ->
                    readCommitted(key, target)?.let { return@withRegistryKey it }
                    writeNewValue(key, target, snapshot)
                }
            }
        } finally {
            snapshot.fill(0)
        }
    }

    override fun delete(namespace: String): Boolean {
        val target = WindowsIdentityNamespace.create(namespace)
        return withNamespace(target) {
            withRegistryKey { key ->
                WindowsSecureIdentityNative.deleteRegistryValue(key, target.valueName)
            }
        }
    }

    override fun toString(): String = "WindowsDpapiSecureIdentityStore([REDACTED])"

    /** Local callers share bounded monitors, including independently opened stores.
     * The exclusive native handle still excludes other processes; a foreign/uncertain
     * handle is refused, never retried or treated as ownership. Lock files are permanent. */
    private fun <T> withNamespace(target: WindowsIdentityNamespace, work: () -> T): T =
        synchronized(namespaceMonitors[(target.valueName.hashCode() and Int.MAX_VALUE) % namespaceMonitors.size]) {
            validateStoreDirectory()
            ensureNoLegacyFileValues()
            val handle = WindowsSecureIdentityNative.openNamespaceLock(
                targetPath(target.lockName), accountSid)
            WindowsSecureIdentityNative.useHandle(handle) { opened ->
                validateRegularFile(opened, singleLink = true)
                validateStoreDirectory()
                ensureNoLegacyFileValues()
                work().also { validateStoreDirectory() }
            }
        }

    private fun <T> withRegistryKey(work: (Pointer) -> T): T {
        val handle = WindowsSecureIdentityNative.openRegistryKey(accountSid)
        return WindowsSecureIdentityNative.useRegistryKey(handle) { opened ->
            WindowsSecureIdentityNative.validateRegistryKey(opened, accountSid)
            work(opened).also {
                WindowsSecureIdentityNative.validateRegistryKey(opened, accountSid)
                WindowsSecureIdentityNative.requireRegistryPathNotLinked()
            }
        }
    }

    private fun validateStoreDirectory() {
        val handle = WindowsSecureIdentityNative.openDirectory(directory,
            WindowsSecureIdentityNative.FILE_READ_ATTRIBUTES or WindowsSecureIdentityNative.READ_CONTROL)
        WindowsSecureIdentityNative.useHandle(handle) { opened ->
            val actual = WindowsSecureIdentityNative.validateDirectoryHandle(opened, accountSid)
            WindowsSecureIdentityNative.requirePrivateAcl(opened, accountSid)
            check(actual == directoryIdentity) { "Windows identity store directory was replaced" }
        }
    }

    private fun ensureNoLegacyFileValues() {
        val found = WindowsSecureIdentityNative.legacyFileValues(directoryNative)
        check(found == 0) { "Windows identity store contains an unadopted legacy file value" }
    }

    private fun readCommitted(key: Pointer, target: WindowsIdentityNamespace): ByteArray? {
        val stored = WindowsSecureIdentityNative.readRegistryValue(key, target.valueName)
        return try {
            // A previous writer may have changed the value and then failed its flush. Reading
            // or returning that winner must settle persistence before exposing SDK identity.
            WindowsSecureIdentityNative.flushRegistryKey(key)
            stored?.let { decodeStored(it, target) }
        } finally {
            stored?.fill(0)
        }
    }

    private fun writeNewValue(
        key: Pointer,
        target: WindowsIdentityNamespace,
        snapshot: ByteArray,
    ): ByteArray {
        val plain = target.encode(snapshot)
        val cipher = try {
            WindowsDpapi.protect(plain, target.entropy)
        } finally {
            plain.fill(0)
        }
        try {
            check(cipher.size in 1..MAX_CIPHERTEXT_BYTES) { "Windows identity ciphertext length is invalid" }
            // Registry values retain the same namespace-bound outer format read by decodeStored.
            // DPAPI authenticates the inner namespace and payload with the namespace entropy.
            val stored = ByteBuffer.allocate(OUTER_HEADER_SIZE + cipher.size)
                .put(OUTER_MAGIC)
                .putInt(FORMAT_VERSION)
                .put(target.digest)
                .putInt(cipher.size)
                .put(cipher)
                .array()
            try {
                val readback = WindowsSecureIdentityNative.writeRegistryValue(key, target.valueName, stored)
                try {
                    val winner = decodeStored(readback, target)
                    if (!MessageDigest.isEqual(snapshot, winner)) {
                        winner.fill(0)
                        error("Windows identity creation readback differs")
                    }
                    return winner
                } finally {
                    readback.fill(0)
                }
            } finally {
                stored.fill(0)
            }
        } finally {
            cipher.fill(0)
        }
    }

    private fun validateRegularFile(handle: Pointer, singleLink: Boolean) {
        WindowsSecureIdentityNative.validateRegularHandle(handle, singleLink)
        WindowsSecureIdentityNative.requireOwner(handle, accountSid)
        WindowsSecureIdentityNative.requirePrivateAcl(handle, accountSid)
    }

    private fun decodeStored(stored: ByteArray, target: WindowsIdentityNamespace): ByteArray {
        check(stored.size >= OUTER_HEADER_SIZE) { "Windows identity value header is truncated" }
        val buffer = ByteBuffer.wrap(stored)
        val magic = ByteArray(OUTER_MAGIC.size)
        buffer.get(magic)
        check(MessageDigest.isEqual(magic, OUTER_MAGIC)) { "Windows identity value magic differs" }
        val version = buffer.int
        check(version == FORMAT_VERSION) { "Windows identity value format is unsupported" }
        val digest = ByteArray(32)
        buffer.get(digest)
        check(MessageDigest.isEqual(digest, target.digest)) { "Windows identity value namespace differs" }
        val cipherLength = buffer.int
        check(cipherLength in 1..MAX_CIPHERTEXT_BYTES && cipherLength == buffer.remaining()) {
            "Windows identity value length is invalid"
        }
        val cipher = ByteArray(cipherLength)
        buffer.get(cipher)
        try {
            val plain = WindowsDpapi.unprotect(cipher, target.entropy)
            try {
                return decodePlain(plain, target)
            } finally {
                plain.fill(0)
            }
        } finally {
            cipher.fill(0)
            digest.fill(0)
            magic.fill(0)
        }
    }

    private fun decodePlain(plain: ByteArray, target: WindowsIdentityNamespace): ByteArray {
        check(plain.size >= INNER_HEADER_SIZE) { "Windows identity payload header is truncated" }
        val buffer = ByteBuffer.wrap(plain)
        val magic = ByteArray(INNER_MAGIC.size)
        buffer.get(magic)
        check(MessageDigest.isEqual(magic, INNER_MAGIC)) { "Windows identity payload magic differs" }
        val digest = ByteArray(32)
        buffer.get(digest)
        check(MessageDigest.isEqual(digest, target.digest)) { "Windows identity payload namespace differs" }
        val valueLength = buffer.int
        check(valueLength in 0..MAX_VALUE_BYTES && valueLength == buffer.remaining()) {
            "Windows identity payload length is invalid"
        }
        val value = ByteArray(valueLength)
        buffer.get(value)
        return value
    }

    private fun targetPath(fileName: String): String = "$directoryNative\\$fileName"

    public companion object {
        /** Opens real Windows DPAPI, the fixed private lock root and the per-user registry key. */
        public fun open(): WindowsDpapiSecureIdentityStore {
            check(Platform.isWindows()) { "Windows DPAPI is required" }
            val accountSid = WindowsSecureIdentityNative.currentUserSid()
            val localAppData = WindowsSecureIdentityNative.localAppData()
            check(localAppData.isAbsolute && localAppData == localAppData.normalize()) {
                "Windows known-folder path is not a fixed absolute directory"
            }
            WindowsSecureIdentityNative.requireFixedVolume(localAppData)
            WindowsSecureIdentityNative.validateExistingDirectory(localAppData, accountSid)
            WindowsDpapi.verify()
            var directory = localAppData
            for (part in STORE_PATH) {
                directory = directory.resolve(part)
                WindowsSecureIdentityNative.ensurePrivateDirectory(directory, accountSid)
            }
            check(directory.startsWith(localAppData)) { "Windows identity store path escaped its known folder" }
            val identity = WindowsSecureIdentityNative.directoryIdentity(directory, accountSid)
            val store = WindowsDpapiSecureIdentityStore(directory, windowsNativePath(directory),
                accountSid, identity)
            store.validateStoreDirectory()
            store.ensureNoLegacyFileValues()
            store.withRegistryKey { }
            return store
        }

        // Fixed stripes avoid retaining attacker-controlled namespace strings. Hash
        // collisions only serialize extra local work; all native checks still run.
        private val namespaceMonitors = Array(64) { Any() }
        private val STORE_PATH = listOf("Ukkera", "POS", "secure-identity-v1")
    }
}

private class WindowsIdentityNamespace private constructor(
    val digest: ByteArray,
    val entropy: ByteArray,
    val valueName: String,
    val lockName: String,
) {
    fun encode(value: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(INNER_HEADER_SIZE + value.size)
        buffer.put(INNER_MAGIC).put(digest).putInt(value.size).put(value)
        return buffer.array()
    }

    companion object {
        fun create(namespace: String): WindowsIdentityNamespace {
            val encoded = namespace.encodeToByteArray(throwOnInvalidSequence = true)
            val digest = try {
                MessageDigest.getInstance("SHA-256").digest(encoded)
            } finally {
                encoded.fill(0)
            }
            val entropyDigest = MessageDigest.getInstance("SHA-256")
            entropyDigest.update(WINDOWS_IDENTITY_ENTROPY_LABEL)
            entropyDigest.update(digest)
            val entropy = entropyDigest.digest()
            val hex = windowsHex(digest)
            return WindowsIdentityNamespace(digest, entropy, hex, "$hex.blob.lock")
        }
    }
}

private object WindowsDpapi {
    private val random = SecureRandom()

    fun verify() {
        val sample = ByteArray(32).also(random::nextBytes)
        try {
            val cipher = protect(sample, WINDOWS_DPAPI_SELF_TEST_ENTROPY)
            try {
                val plain = unprotect(cipher, WINDOWS_DPAPI_SELF_TEST_ENTROPY)
                try {
                    check(MessageDigest.isEqual(sample, plain)) { "Windows DPAPI self-test differs" }
                } finally {
                    plain.fill(0)
                }
            } finally {
                cipher.fill(0)
            }
        } finally {
            sample.fill(0)
        }
    }

    fun protect(value: ByteArray, entropy: ByteArray): ByteArray {
        check(value.size <= MAX_PLAINTEXT_BYTES && entropy.size in 1..1024)
        val valueMemory = Memory(maxOf(1, value.size).toLong())
        val entropyMemory = Memory(entropy.size.toLong())
        val output = WindowsDataBlob()
        try {
            if (value.isNotEmpty()) valueMemory.write(0, value, 0, value.size)
            entropyMemory.write(0, entropy, 0, entropy.size)
            val inputBlob = WindowsDataBlob().also {
                it.cbData = value.size
                it.pbData = valueMemory
            }
            val entropyBlob = WindowsDataBlob().also {
                it.cbData = entropy.size
                it.pbData = entropyMemory
            }
            val succeeded = WindowsSecureIdentityNative.crypt32.CryptProtectData(inputBlob, null,
                entropyBlob, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
            if (succeeded == 0) error("Windows DPAPI protection failed (${Native.getLastError()})")
            output.read()
            val size = output.cbData
            check(size in 1..MAX_CIPHERTEXT_BYTES) { "Windows DPAPI returned an unsupported output size" }
            return checkNotNull(output.pbData).getByteArray(0, size)
        } finally {
            val pointer = output.pbData
            if (pointer != null) {
                try {
                    pointer.clear(output.cbData.toLong().coerceIn(0L, MAX_CIPHERTEXT_BYTES.toLong()))
                } finally {
                    WindowsSecureIdentityNative.kernel.LocalFree(pointer)
                }
            }
            entropyMemory.clear()
            valueMemory.clear()
        }
    }

    fun unprotect(value: ByteArray, entropy: ByteArray): ByteArray {
        check(value.size in 1..MAX_CIPHERTEXT_BYTES && entropy.size in 1..1024)
        val valueMemory = Memory(value.size.toLong())
        val entropyMemory = Memory(entropy.size.toLong())
        val output = WindowsDataBlob()
        try {
            valueMemory.write(0, value, 0, value.size)
            entropyMemory.write(0, entropy, 0, entropy.size)
            val inputBlob = WindowsDataBlob().also {
                it.cbData = value.size
                it.pbData = valueMemory
            }
            val entropyBlob = WindowsDataBlob().also {
                it.cbData = entropy.size
                it.pbData = entropyMemory
            }
            val succeeded = WindowsSecureIdentityNative.crypt32.CryptUnprotectData(inputBlob, null,
                entropyBlob, null, null, CRYPTPROTECT_UI_FORBIDDEN, output)
            if (succeeded == 0) error("Windows DPAPI unprotection failed (${Native.getLastError()})")
            output.read()
            val size = output.cbData
            check(size in 1..MAX_PLAINTEXT_BYTES) { "Windows DPAPI returned an unsupported plaintext size" }
            return checkNotNull(output.pbData).getByteArray(0, size)
        } finally {
            val pointer = output.pbData
            if (pointer != null) {
                try {
                    pointer.clear(output.cbData.toLong().coerceIn(0L, MAX_PLAINTEXT_BYTES.toLong()))
                } finally {
                    WindowsSecureIdentityNative.kernel.LocalFree(pointer)
                }
            }
            entropyMemory.clear()
            valueMemory.clear()
        }
    }
}

private object WindowsSecureIdentityNative {
    val kernel: WindowsSecureIdentityKernel32 = Native.load("kernel32", WindowsSecureIdentityKernel32::class.java)
    val advapi: WindowsSecureIdentityAdvapi32 = Native.load("advapi32", WindowsSecureIdentityAdvapi32::class.java)
    val crypt32: WindowsSecureIdentityCrypt32 = Native.load("crypt32", WindowsSecureIdentityCrypt32::class.java)
    private val shell: WindowsSecureIdentityShell32 = Native.load("shell32", WindowsSecureIdentityShell32::class.java)
    private val ole: WindowsSecureIdentityOle32 = Native.load("ole32", WindowsSecureIdentityOle32::class.java)

    const val GENERIC_READ: Int = Int.MIN_VALUE
    const val GENERIC_WRITE: Int = 0x40000000
    const val FILE_READ_ATTRIBUTES: Int = 0x00000080
    const val KEY_QUERY_VALUE: Int = 0x00000001
    const val READ_CONTROL: Int = 0x00020000
    const val SHARE_READ: Int = 0x00000001
    const val SHARE_WRITE: Int = 0x00000002
    const val SHARE_DELETE: Int = 0x00000004
    const val OPEN_EXISTING: Int = 3
    const val FILE_ATTRIBUTE_DIRECTORY: Int = 0x00000010
    const val FILE_ATTRIBUTE_REPARSE_POINT: Int = 0x00000400
    const val FILE_FLAG_BACKUP_SEMANTICS: Int = 0x02000000
    const val FILE_FLAG_OPEN_REPARSE_POINT: Int = 0x00200000
    const val FILE_TYPE_DISK: Int = 1
    const val FILE_ATTRIBUTE_TAG_INFO_CLASS: Int = 9
    const val ERROR_FILE_NOT_FOUND: Int = 2
    const val ERROR_PATH_NOT_FOUND: Int = 3
    const val ERROR_INSUFFICIENT_BUFFER: Int = 122
    const val ERROR_ALREADY_EXISTS: Int = 183
    const val ERROR_NO_MORE_FILES: Int = 18
    const val ERROR_NO_MORE_ITEMS: Int = 259
    const val TOKEN_QUERY: Int = 0x0008
    const val TOKEN_USER_CLASS: Int = 1
    const val SE_FILE_OBJECT: Int = 1
    const val OWNER_SECURITY_INFORMATION: Int = 0x00000001
    const val DACL_SECURITY_INFORMATION: Int = 0x00000004
    const val INHERIT_ONLY_ACE: Int = 0x8
    const val FILE_ALL_ACCESS: Int = 0x001F01FF
    const val KEY_ALL_ACCESS: Int = 0x000F003F
    const val DRIVE_FIXED: Int = 3
    const val REG_OPTION_NON_VOLATILE: Int = 0
    const val REG_OPTION_OPEN_LINK: Int = 0x00000008
    const val REG_CREATED_NEW_KEY: Int = 1
    const val REG_OPENED_EXISTING_KEY: Int = 2
    const val REG_BINARY: Int = 3

    private const val REGISTRY_PATH = "Software\\Ukkera\\POS\\SecureIdentityV1"
    private const val REGISTRY_LINK_VALUE = "SymbolicLinkValue"
    private const val VALUE_NAME_HEX_LENGTH = 64
    private const val MAX_REGISTRY_VALUE_NAME_CHARS = 256
    private const val MAX_REGISTRY_KEY_CLASS_CHARS = 256
    private const val MAX_SECURITY_DESCRIPTOR_BYTES = 64 * 1024
    private val HKEY_CURRENT_USER: Pointer = Pointer(-2147483647L)
    private val REGISTRY_PATH_PREFIXES = listOf(
        "Software",
        "Software\\Ukkera",
        "Software\\Ukkera\\POS",
        REGISTRY_PATH,
    )

    private val LOCAL_APP_DATA = byteArrayOf(
        0x85.toByte(), 0x27, 0xB3.toByte(), 0xF1.toByte(),
        0xBA.toByte(), 0x6F,
        0xCF.toByte(), 0x4F,
        0x9D.toByte(), 0x55, 0x7B, 0x8E.toByte(), 0x7F, 0x15, 0x70, 0x91.toByte(),
    )

    fun localAppData(): Path {
        val folderId = Memory(LOCAL_APP_DATA.size.toLong())
        val output = PointerByReference()
        return try {
            folderId.write(0, LOCAL_APP_DATA, 0, LOCAL_APP_DATA.size)
            val result = shell.SHGetKnownFolderPath(folderId, 0, null, output)
            val pointer = checkNotNull(output.value) {
                "Windows LocalAppData known folder is unavailable ($result)"
            }
            try {
                check(result >= 0) { "Windows LocalAppData known folder is unavailable ($result)" }
                val text = pointer.getWideString(0)
                check(text.length in 1..32767 && '\u0000' !in text) {
                    "Windows LocalAppData path has an invalid length"
                }
                Path.of(text)
            } finally {
                ole.CoTaskMemFree(pointer)
            }
        } finally {
            folderId.clear()
        }
    }

    fun requireFixedVolume(path: Path) {
        val root = checkNotNull(path.root) { "Windows known folder has no volume root" }
        val type = kernel.GetDriveTypeW(WString(root.toString()))
        check(type == DRIVE_FIXED) { "Windows identity storage requires a fixed local volume" }
    }

    fun currentUserSid(): ByteArray {
        val token = PointerByReference()
        check(advapi.OpenProcessToken(kernel.GetCurrentProcess(), TOKEN_QUERY, token) != 0) {
            "Current process token is unavailable (${Native.getLastError()})"
        }
        val handle = checkNotNull(token.value)
        return useHandle(handle) { opened ->
            val required = IntByReference()
            if (advapi.GetTokenInformation(opened, TOKEN_USER_CLASS, null, 0, required) == 0) {
                check(Native.getLastError() == ERROR_INSUFFICIENT_BUFFER && required.value in 8..65536) {
                    "Current process account query failed (${Native.getLastError()})"
                }
            }
            check(required.value in 8..65536) { "Current process account size is invalid" }
            val buffer = Memory(required.value.toLong())
            try {
                check(advapi.GetTokenInformation(opened, TOKEN_USER_CLASS, buffer, required.value,
                    required) != 0) {
                    "Current process account query failed (${Native.getLastError()})"
                }
                val sid = checkNotNull(buffer.getPointer(0)) { "Current process account SID is absent" }
                check(advapi.IsValidSid(sid) != 0) { "Current process account SID is invalid" }
                val length = advapi.GetLengthSid(sid)
                check(length in 8..1024) { "Current process account SID size is invalid" }
                sid.getByteArray(0, length)
            } finally {
                buffer.clear()
            }
        }
    }

    fun validateExistingDirectory(path: Path, accountSid: ByteArray) {
        val handle = openDirectory(path, FILE_READ_ATTRIBUTES or READ_CONTROL)
        useHandle(handle) { opened -> validateDirectoryHandle(opened, accountSid) }
    }

    fun ensurePrivateDirectory(path: Path, accountSid: ByteArray) {
        val native = windowsNativePath(path)
        withPrivateAttributes(accountSid, inheritable = true, rights = "FA") { attributes ->
            if (kernel.CreateDirectoryW(WString(native), attributes) == 0) {
                val error = Native.getLastError()
                check(error == ERROR_ALREADY_EXISTS) {
                    "Windows identity directory creation failed ($error)"
                }
            }
        }
        val handle = openDirectory(path, FILE_READ_ATTRIBUTES or READ_CONTROL)
        useHandle(handle) { opened ->
            validateDirectoryHandle(opened, accountSid)
            requirePrivateAcl(opened, accountSid)
        }
    }

    fun directoryIdentity(path: Path, accountSid: ByteArray): WindowsDirectoryIdentity {
        val handle = openDirectory(path, FILE_READ_ATTRIBUTES or READ_CONTROL)
        return useHandle(handle) { opened -> validateDirectoryHandle(opened, accountSid) }
    }

    fun openDirectory(path: Path, access: Int): Pointer {
        val handle = kernel.CreateFileW(WString(windowsNativePath(path)), access,
            SHARE_READ or SHARE_WRITE or SHARE_DELETE, null, OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OPEN_REPARSE_POINT, null)
        check(!invalidHandle(handle)) {
            "Windows identity directory open failed (${Native.getLastError()})"
        }
        return handle
    }

    fun validateDirectoryHandle(handle: Pointer, accountSid: ByteArray): WindowsDirectoryIdentity {
        val information = WindowsByHandleFileInformation()
        check(kernel.GetFileInformationByHandle(handle, information) != 0) {
            "Windows identity directory information failed (${Native.getLastError()})"
        }
        information.read()
        check(information.dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY != 0) {
            "Windows identity storage path is not a directory"
        }
        check(information.dwFileAttributes and FILE_ATTRIBUTE_REPARSE_POINT == 0) {
            "Windows identity storage path is a reparse point"
        }
        check(kernel.GetFileType(handle) == FILE_TYPE_DISK) {
            "Windows identity storage path is not a disk directory"
        }
        val tag = attributeTag(handle)
        check(tag.first and FILE_ATTRIBUTE_REPARSE_POINT == 0 && tag.second == 0) {
            "Windows identity storage path is a reparse point"
        }
        check(information.nFileIndexHigh != 0 || information.nFileIndexLow != 0) {
            "Windows identity storage directory identity is unavailable"
        }
        requireOwner(handle, accountSid)
        return WindowsDirectoryIdentity(information.dwVolumeSerialNumber,
            information.nFileIndexHigh, information.nFileIndexLow)
    }

    fun validateRegularHandle(handle: Pointer, singleLink: Boolean) {
        val information = WindowsByHandleFileInformation()
        check(kernel.GetFileInformationByHandle(handle, information) != 0) {
            "Windows identity file information failed (${Native.getLastError()})"
        }
        information.read()
        check(information.dwFileAttributes and FILE_ATTRIBUTE_DIRECTORY == 0) {
            "Windows identity storage entry is a directory"
        }
        check(information.dwFileAttributes and FILE_ATTRIBUTE_REPARSE_POINT == 0) {
            "Windows identity storage entry is a reparse point"
        }
        check(kernel.GetFileType(handle) == FILE_TYPE_DISK) {
            "Windows identity storage entry is not a disk file"
        }
        if (singleLink) check(information.nNumberOfLinks == 1) {
            "Windows identity storage entry has unexpected hard links"
        }
        val tag = attributeTag(handle)
        check(tag.first and FILE_ATTRIBUTE_REPARSE_POINT == 0 && tag.second == 0) {
            "Windows identity storage entry is a reparse point"
        }
    }

    fun requireOwner(handle: Pointer, accountSid: ByteArray) {
        val owner = PointerByReference()
        val descriptor = PointerByReference()
        val result = advapi.GetSecurityInfo(handle, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION,
            owner, null, null, null, descriptor)
        check(result == 0) { "Windows identity owner query failed ($result)" }
        val security = checkNotNull(descriptor.value) { "Windows identity owner descriptor is absent" }
        try {
            val actual = checkNotNull(owner.value) { "Windows identity owner SID is absent" }
            Memory(accountSid.size.toLong()).use { expected ->
                expected.write(0, accountSid, 0, accountSid.size)
                check(advapi.EqualSid(actual, expected) != 0) {
                    "Windows identity storage is owned by another account"
                }
            }
        } finally {
            kernel.LocalFree(security)
        }
    }

    fun requirePrivateAcl(handle: Pointer, accountSid: ByteArray) {
        val dacl = PointerByReference()
        val descriptor = PointerByReference()
        val result = advapi.GetSecurityInfo(handle, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
            null, null, dacl, null, descriptor)
        check(result == 0) { "Windows identity ACL query failed ($result)" }
        val security = checkNotNull(descriptor.value)
        try {
            val control = ShortByReference()
            val revision = IntByReference()
            check(advapi.GetSecurityDescriptorControl(security, control, revision) != 0 &&
                control.value.toInt() and 0x1004 == 0x1004) {
                "Windows identity requires a protected, present DACL"
            }
            val acl = checkNotNull(dacl.value) { "Windows identity has a null DACL" }
            check(advapi.IsValidAcl(acl) != 0) { "Windows identity ACL is invalid" }
            val count = acl.getShort(4).toInt() and 0xffff
            check(count in 1..2) { "Windows identity ACL contains unexpected entries" }
            Memory(accountSid.size.toLong()).use { expected ->
                expected.write(0, accountSid, 0, accountSid.size)
                var appliesToObject = false
                repeat(count) { index ->
                    val output = PointerByReference()
                    check(advapi.GetAce(acl, index, output) != 0)
                    val ace = checkNotNull(output.value)
                    val flags = ace.getByte(1).toInt() and 0xff
                    val size = ace.getShort(2).toInt() and 0xffff
                    check(ace.getByte(0).toInt() == 0 && flags and 0xe4 == 0 &&
                        size == 8 + accountSid.size && ace.getInt(4) == FILE_ALL_ACCESS) {
                        "Windows identity ACL contains an unsupported grant"
                    }
                    val sid = ace.share(8)
                    check(advapi.IsValidSid(sid) != 0 && advapi.GetLengthSid(sid) == accountSid.size &&
                        advapi.EqualSid(sid, expected) != 0) {
                        "Windows identity ACL grants another account"
                    }
                    if (flags and INHERIT_ONLY_ACE == 0) appliesToObject = true
                }
                check(appliesToObject) { "Windows identity owner has no direct grant" }
            }
        } finally {
            kernel.LocalFree(security)
        }
    }

    fun requirePrivateRegistryAcl(handle: Pointer, accountSid: ByteArray) {
        val required = IntByReference(0)
        val information = OWNER_SECURITY_INFORMATION or DACL_SECURITY_INFORMATION
        val probe = advapi.RegGetKeySecurity(handle, information, null, required)
        check((probe == ERROR_INSUFFICIENT_BUFFER || probe == 0) &&
            required.value in 1..MAX_SECURITY_DESCRIPTOR_BYTES) {
            "Windows identity registry security query failed ($probe)"
        }
        val descriptor = Memory(required.value.toLong())
        try {
            val actualSize = IntByReference(required.value)
            val actual = advapi.RegGetKeySecurity(handle, information, descriptor, actualSize)
            check(actual == 0) { "Windows identity registry security read failed ($actual)" }
            check(actualSize.value in 1..required.value) {
                "Windows identity registry security descriptor changed"
            }
            val control = ShortByReference()
            val revision = IntByReference()
            check(advapi.GetSecurityDescriptorControl(descriptor, control, revision) != 0) {
                "Windows identity registry security control is unavailable"
            }
            check(revision.value == 1) { "Windows identity registry security revision is unsupported" }
            val bits = control.value.toInt() and 0xffff
            check(bits and 0x1004 == 0x1004) {
                "Windows identity registry requires a protected, present DACL"
            }
            check(bits and (0x0001 or 0x0008 or 0x0100 or 0x0400) == 0) {
                "Windows identity registry security is defaulted or inherited"
            }
            check(bits and 0x8000 != 0) {
                "Windows identity registry security descriptor is not self-relative"
            }
            val owner = PointerByReference()
            val ownerDefaulted = IntByReference()
            check(advapi.GetSecurityDescriptorOwner(descriptor, owner, ownerDefaulted) != 0) {
                "Windows identity registry owner is unavailable"
            }
            check(ownerDefaulted.value == 0) { "Windows identity registry owner is defaulted" }
            val actualOwner = checkNotNull(owner.value) { "Windows identity registry owner is absent" }
            Memory(accountSid.size.toLong()).use { expected ->
                expected.write(0, accountSid, 0, accountSid.size)
                check(advapi.IsValidSid(actualOwner) != 0 &&
                    advapi.GetLengthSid(actualOwner) == accountSid.size &&
                    advapi.EqualSid(actualOwner, expected) != 0) {
                    "Windows identity registry is owned by another account"
                }
            }
            val present = IntByReference()
            val dacl = PointerByReference()
            val daclDefaulted = IntByReference()
            check(advapi.GetSecurityDescriptorDacl(descriptor, present, dacl, daclDefaulted) != 0) {
                "Windows identity registry ACL is unavailable"
            }
            check(present.value != 0 && daclDefaulted.value == 0) {
                "Windows identity registry requires an explicit present DACL"
            }
            val acl = checkNotNull(dacl.value) { "Windows identity registry has a null DACL" }
            check(advapi.IsValidAcl(acl) != 0) { "Windows identity registry ACL is invalid" }
            val count = acl.getShort(4).toInt() and 0xffff
            check(count == 1) { "Windows identity registry ACL contains unexpected entries" }
            val output = PointerByReference()
            check(advapi.GetAce(acl, 0, output) != 0) {
                "Windows identity registry ACL entry is unavailable"
            }
            val ace = checkNotNull(output.value)
            val flags = ace.getByte(1).toInt() and 0xff
            val size = ace.getShort(2).toInt() and 0xffff
            check(ace.getByte(0).toInt() == 0 && flags == 0 && size == 8 + accountSid.size &&
                ace.getInt(4) == KEY_ALL_ACCESS) {
                "Windows identity registry ACL contains an unsupported grant"
            }
            val sid = ace.share(8)
            Memory(accountSid.size.toLong()).use { expected ->
                expected.write(0, accountSid, 0, accountSid.size)
                check(advapi.IsValidSid(sid) != 0 && advapi.GetLengthSid(sid) == accountSid.size &&
                    advapi.EqualSid(sid, expected) != 0) {
                    "Windows identity registry ACL grants another account"
                }
            }
        } finally {
            descriptor.clear()
            descriptor.close()
        }
    }

    fun openRegistryKey(accountSid: ByteArray): Pointer {
        val existing = openExistingRegistryKey()
        if (existing != null) {
            return try {
                requireRegistryPathNotLinked()
                existing
            } catch (failure: Throwable) {
                advapi.RegCloseKey(existing)
                throw failure
            }
        }

        requireExistingRegistryPathNotLinked()
        val result = PointerByReference()
        val disposition = IntByReference()
        withPrivateAttributes(accountSid, inheritable = false, rights = "KA") { attributes ->
            val status = advapi.RegCreateKeyExW(HKEY_CURRENT_USER, WString(REGISTRY_PATH), 0, null,
                REG_OPTION_NON_VOLATILE, KEY_ALL_ACCESS, attributes, result, disposition)
            check(status == 0) { "Windows identity registry key creation failed ($status)" }
        }
        val handle = checkNotNull(result.value) { "Windows identity registry key handle is absent" }
        try {
            check(disposition.value == REG_CREATED_NEW_KEY ||
                disposition.value == REG_OPENED_EXISTING_KEY) {
                "Windows identity registry key disposition is invalid"
            }
            if (disposition.value == REG_CREATED_NEW_KEY) flushRegistryKey(handle)
            requireRegistryPathNotLinked()
            return handle
        } catch (failure: Throwable) {
            advapi.RegCloseKey(handle)
            throw failure
        }
    }

    private fun openExistingRegistryKey(): Pointer? {
        val result = PointerByReference()
        val status = advapi.RegOpenKeyExW(HKEY_CURRENT_USER, WString(REGISTRY_PATH),
            REG_OPTION_OPEN_LINK, KEY_ALL_ACCESS, result)
        if (status == 0) return checkNotNull(result.value)
        check(status == ERROR_FILE_NOT_FOUND || status == ERROR_PATH_NOT_FOUND) {
            "Windows identity registry key open failed ($status)"
        }
        return null
    }

    fun requireRegistryPathNotLinked() {
        for (path in REGISTRY_PATH_PREFIXES) {
            val result = PointerByReference()
            val status = advapi.RegOpenKeyExW(HKEY_CURRENT_USER, WString(path),
                REG_OPTION_OPEN_LINK, KEY_QUERY_VALUE or READ_CONTROL, result)
            check(status == 0) { "Windows identity registry path open failed ($status)" }
            val handle = checkNotNull(result.value)
            useRegistryKey(handle) { opened -> requireNoLinkValue(opened) }
        }
    }

    private fun requireExistingRegistryPathNotLinked() {
        for (path in REGISTRY_PATH_PREFIXES) {
            val result = PointerByReference()
            val status = advapi.RegOpenKeyExW(HKEY_CURRENT_USER, WString(path),
                REG_OPTION_OPEN_LINK, KEY_QUERY_VALUE or READ_CONTROL, result)
            if (status == ERROR_FILE_NOT_FOUND || status == ERROR_PATH_NOT_FOUND) return
            check(status == 0) { "Windows identity registry path open failed ($status)" }
            val handle = checkNotNull(result.value)
            useRegistryKey(handle) { opened -> requireNoLinkValue(opened) }
        }
    }

    private fun requireNoLinkValue(handle: Pointer) {
        val type = IntByReference()
        val size = IntByReference()
        when (val status = advapi.RegQueryValueExW(handle, WString(REGISTRY_LINK_VALUE),
            null, type, null, size)) {
            0 -> error("Windows identity registry path contains a symbolic link")
            ERROR_FILE_NOT_FOUND -> Unit
            else -> error("Windows identity registry link check failed ($status)")
        }
    }

    fun validateRegistryKey(handle: Pointer, accountSid: ByteArray) {
        requirePrivateRegistryAcl(handle, accountSid)
        val className = Memory(MAX_REGISTRY_KEY_CLASS_CHARS * 2L)
        val classLength = IntByReference(MAX_REGISTRY_KEY_CLASS_CHARS)
        val subkeys = IntByReference()
        val values = IntByReference()
        val maxValueName = IntByReference()
        try {
            val status = advapi.RegQueryInfoKeyW(handle, className, classLength, null,
                subkeys, null, null, values, maxValueName, null, null, null)
            check(status == 0) { "Windows identity registry key information failed ($status)" }
            check(subkeys.value == 0) { "Windows identity registry key contains subkeys" }
            check(classLength.value == 0) { "Windows identity registry key has an unexpected class" }
            check(values.value in 0..MAX_REGISTRY_VALUES) {
                "Windows identity registry key contains too many values"
            }
            check(maxValueName.value in 0..VALUE_NAME_HEX_LENGTH) {
                "Windows identity registry key contains an unexpected value name"
            }
        } finally {
            className.clear()
            className.close()
        }

        val name = Memory(MAX_REGISTRY_VALUE_NAME_CHARS * 2L)
        try {
            val seen = HashSet<String>()
            var index = 0
            while (true) {
                check(index < MAX_REGISTRY_VALUES) {
                    "Windows identity registry key contains too many values"
                }
                val nameLength = IntByReference(MAX_REGISTRY_VALUE_NAME_CHARS)
                val status = advapi.RegEnumValueW(handle, index, name, nameLength, null,
                    null, null, null)
                if (status == ERROR_NO_MORE_ITEMS) break
                check(status == 0) { "Windows identity registry value enumeration failed ($status)" }
                check(nameLength.value == VALUE_NAME_HEX_LENGTH) {
                    "Windows identity registry contains an unexpected value name"
                }
                val text = CharArray(VALUE_NAME_HEX_LENGTH) { name.getChar(it * 2L) }
                check(text.all { it in '0'..'9' || it in 'a'..'f' }) {
                    "Windows identity registry contains an unexpected value name"
                }
                check(seen.add(text.concatToString())) {
                    "Windows identity registry contains ambiguous value names"
                }
                index++
            }
            check(index == values.value) {
                "Windows identity registry values changed during enumeration"
            }
        } finally {
            name.clear()
            name.close()
        }
    }

    fun readRegistryValue(handle: Pointer, valueName: String): ByteArray? {
        val type = IntByReference()
        val size = IntByReference()
        val probe = advapi.RegQueryValueExW(handle, WString(valueName), null, type, null, size)
        if (probe == ERROR_FILE_NOT_FOUND) return null
        check(probe == 0) { "Windows identity registry value query failed ($probe)" }
        check(type.value == REG_BINARY) {
            "Windows identity registry value has an unsupported type"
        }
        check(size.value in 1..MAX_STORED_BYTES) {
            "Windows identity registry value has an unsupported size"
        }
        val memory = Memory(size.value.toLong())
        try {
            val readSize = IntByReference(size.value)
            val readType = IntByReference()
            val status = advapi.RegQueryValueExW(handle, WString(valueName), null,
                readType, memory, readSize)
            check(status == 0) { "Windows identity registry value read failed ($status)" }
            check(readType.value == REG_BINARY && readSize.value == size.value) {
                "Windows identity registry value changed during read"
            }
            return ByteArray(readSize.value).also { memory.read(0, it, 0, it.size) }
        } finally {
            memory.clear()
            memory.close()
        }
    }

    fun writeRegistryValue(handle: Pointer, valueName: String, value: ByteArray): ByteArray {
        check(value.size in 1..MAX_STORED_BYTES)
        val memory = Memory(value.size.toLong())
        try {
            memory.write(0, value, 0, value.size)
            val status = advapi.RegSetValueExW(handle, WString(valueName), 0, REG_BINARY,
                memory, value.size)
            check(status == 0) { "Windows identity registry value write failed ($status)" }
            flushRegistryKey(handle)
            val readback = readRegistryValue(handle, valueName)
                ?: error("Windows identity registry value disappeared after write")
            var completed = false
            try {
                check(MessageDigest.isEqual(readback, value)) {
                    "Windows identity registry value changed after write"
                }
                completed = true
                return readback
            } finally {
                if (!completed) readback.fill(0)
            }
        } finally {
            memory.clear()
            memory.close()
        }
    }

    fun deleteRegistryValue(handle: Pointer, valueName: String): Boolean {
        val status = advapi.RegDeleteValueW(handle, WString(valueName))
        if (status == ERROR_FILE_NOT_FOUND) {
            // Also settle an earlier deletion whose flush failed before this retry.
            flushRegistryKey(handle)
            val unexpected = readRegistryValue(handle, valueName)
            if (unexpected != null) {
                unexpected.fill(0)
                error("Windows identity registry value is present despite deletion absence")
            }
            return false
        }
        check(status == 0) { "Windows identity registry value deletion failed ($status)" }
        flushRegistryKey(handle)
        val remaining = readRegistryValue(handle, valueName)
        if (remaining != null) {
            remaining.fill(0)
            error("Windows identity registry value remained after deletion")
        }
        return true
    }

    fun flushRegistryKey(handle: Pointer) {
        val status = advapi.RegFlushKey(handle)
        check(status == 0) { "Windows identity registry flush failed ($status)" }
    }

    fun legacyFileValues(directoryNative: String): Int {
        val found = WindowsFindDataW()
        val search = kernel.FindFirstFileW(WString("$directoryNative\\*"), found)
        check(!invalidHandle(search)) {
            "Windows identity lock-root enumeration failed (${Native.getLastError()})"
        }
        var count = 0
        var failure: Throwable? = null
        try {
            while (true) {
                found.read()
                val name = found.fileName()
                if (name != "." && name != ".." && isLegacyFileValue(name)) count++
                if (kernel.FindNextFileW(search, found) == 0) {
                    val error = Native.getLastError()
                    check(error == ERROR_NO_MORE_FILES) {
                        "Windows identity lock-root enumeration failed ($error)"
                    }
                    break
                }
            }
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (kernel.FindClose(search) == 0 && failure == null) {
                error("Windows identity lock-root enumeration close failed (${Native.getLastError()})")
            }
        }
        return count
    }

    private fun isLegacyFileValue(name: String): Boolean {
        if (name.length == VALUE_NAME_HEX_LENGTH + ".blob".length &&
            name.endsWith(".blob") && isLowerHex(name, 0, VALUE_NAME_HEX_LENGTH)) {
            return true
        }
        val prefix = ".pending-"
        if (!name.startsWith(prefix) || !name.endsWith(".tmp")) return false
        val middle = name.substring(prefix.length, name.length - ".tmp".length)
        return middle.length == VALUE_NAME_HEX_LENGTH + 1 + 32 &&
            middle[VALUE_NAME_HEX_LENGTH] == '-' &&
            isLowerHex(middle, 0, VALUE_NAME_HEX_LENGTH) &&
            isLowerHex(middle, VALUE_NAME_HEX_LENGTH + 1, 32)
    }

    private fun isLowerHex(value: String, start: Int, length: Int): Boolean {
        for (index in start until start + length) {
            val character = value[index]
            if (character !in '0'..'9' && character !in 'a'..'f') return false
        }
        return true
    }

    private fun <T> withPrivateAttributes(
        accountSid: ByteArray,
        inheritable: Boolean,
        rights: String,
        work: (Pointer) -> T,
    ): T {
        Memory(accountSid.size.toLong()).use { sid ->
            sid.write(0, accountSid, 0, accountSid.size)
            val stringSid = PointerByReference()
            check(advapi.ConvertSidToStringSidW(sid, stringSid) != 0)
            val sidText = checkNotNull(stringSid.value)
            val descriptor = PointerByReference()
            try {
                val value = sidText.getWideString(0)
                val flags = if (inheritable) "OICI" else ""
                check(advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    WString("O:" + value + "D:P(A;" + flags + ";" + rights + ";;;" + value + ")"),
                    1, descriptor, null) != 0) { "Windows identity private ACL creation failed" }
                val attributes = WindowsIdentitySecurityAttributes().also {
                    it.length = it.size()
                    it.descriptor = checkNotNull(descriptor.value)
                    it.inheritHandle = 0
                    it.write()
                }
                return work(attributes.pointer)
            } finally {
                descriptor.value?.let { kernel.LocalFree(it) }
                kernel.LocalFree(sidText)
            }
        }
    }

    fun openNamespaceLock(path: String, accountSid: ByteArray): Pointer =
        withPrivateAttributes(accountSid, inheritable = false, rights = "FA") { attributes ->
            kernel.CreateFileW(WString(path), GENERIC_READ or GENERIC_WRITE or READ_CONTROL,
                0, attributes, 4 /* OPEN_ALWAYS */, FILE_FLAG_OPEN_REPARSE_POINT, null).also {
                check(!invalidHandle(it)) { "Windows identity namespace is busy or unavailable (${Native.getLastError()})" }
            }
        }

    fun attributeTag(handle: Pointer): Pair<Int, Int> {
        val buffer = Memory(8)
        return try {
            check(kernel.GetFileInformationByHandleEx(handle, FILE_ATTRIBUTE_TAG_INFO_CLASS,
                buffer, 8) != 0) {
                "Windows identity attribute query failed (${Native.getLastError()})"
            }
            buffer.getInt(0) to buffer.getInt(4)
        } finally {
            buffer.clear()
            buffer.close()
        }
    }

    fun invalidHandle(handle: Pointer): Boolean = Pointer.nativeValue(handle) == -1L

    fun <T> useHandle(handle: Pointer, block: (Pointer) -> T): T {
        var primary: Throwable? = null
        try {
            return block(handle)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            if (kernel.CloseHandle(handle) == 0 && primary == null) {
                error("Windows identity handle close failed (${Native.getLastError()})")
            }
        }
    }

    fun <T> useRegistryKey(handle: Pointer, block: (Pointer) -> T): T {
        var primary: Throwable? = null
        try {
            return block(handle)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            val status = advapi.RegCloseKey(handle)
            if (status != 0 && primary == null) {
                error("Windows identity registry handle close failed ($status)")
            }
        }
    }
}

private data class WindowsDirectoryIdentity(
    val volumeSerialNumber: Int,
    val fileIndexHigh: Int,
    val fileIndexLow: Int,
)

private fun windowsNativePath(path: Path): String {
    val text = path.toAbsolutePath().normalize().toString().replace('/', '\\')
    check('\u0000' !in text && text.length in 2..32600) { "Windows identity path has an invalid length" }
    check(!text.startsWith("\\\\.\\")) { "Windows identity path cannot be a device path" }
    if (text.startsWith("\\\\?\\")) return text
    return if (text.startsWith("\\\\")) "\\\\?\\UNC\\" + text.substring(2) else "\\\\?\\$text"
}

private fun windowsHex(value: ByteArray): String {
    val digits = "0123456789abcdef"
    val result = CharArray(value.size * 2)
    value.forEachIndexed { index, byte ->
        val unsigned = byte.toInt() and 0xff
        result[index * 2] = digits[unsigned ushr 4]
        result[index * 2 + 1] = digits[unsigned and 0x0f]
    }
    return result.concatToString()
}

private fun WindowsFindDataW.fileName(): String {
    var length = 0
    while (length < cFileName.size && cFileName[length].toInt() != 0) length++
    val characters = CharArray(length) { cFileName[it].toInt().toChar() }
    return characters.concatToString()
}

private val WINDOWS_IDENTITY_ENTROPY_LABEL =
    "ukkera.pos.windows-dpapi-secure-identity.v1\u0000".encodeToByteArray()
private val WINDOWS_DPAPI_SELF_TEST_ENTROPY =
    "ukkera.pos.windows-dpapi-self-test.v1".encodeToByteArray()

private const val FORMAT_VERSION = 1
/** Registry standard-format values are limited to 1 MiB including envelope and DPAPI overhead. */
private const val MAX_VALUE_BYTES = 768 * 1024
private const val MAX_CIPHERTEXT_BYTES = MAX_VALUE_BYTES + 64 * 1024
private const val INNER_HEADER_SIZE = 8 + 32 + 4
private const val OUTER_HEADER_SIZE = 8 + 4 + 32 + 4
private const val MAX_PLAINTEXT_BYTES = INNER_HEADER_SIZE + MAX_VALUE_BYTES
private const val MAX_STORED_BYTES = OUTER_HEADER_SIZE + MAX_CIPHERTEXT_BYTES
private const val MAX_REGISTRY_VALUES = 4096
private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1
private val OUTER_MAGIC = "UKPID001".encodeToByteArray()
private val INNER_MAGIC = "UKPIV001".encodeToByteArray()

@Structure.FieldOrder("cbData", "pbData")
internal class WindowsDataBlob : Structure() {
    @JvmField var cbData: Int = 0
    @JvmField var pbData: Pointer? = null
}

@Structure.FieldOrder("low", "high")
internal class WindowsFileTime : Structure() {
    @JvmField var low: Int = 0
    @JvmField var high: Int = 0
}

@Structure.FieldOrder("dwFileAttributes", "ftCreationTime", "ftLastAccessTime", "ftLastWriteTime",
    "nFileSizeHigh", "nFileSizeLow", "dwReserved0", "dwReserved1", "cFileName", "cAlternateFileName")
internal class WindowsFindDataW : Structure() {
    @JvmField var dwFileAttributes: Int = 0
    @JvmField var ftCreationTime = WindowsFileTime()
    @JvmField var ftLastAccessTime = WindowsFileTime()
    @JvmField var ftLastWriteTime = WindowsFileTime()
    @JvmField var nFileSizeHigh: Int = 0
    @JvmField var nFileSizeLow: Int = 0
    @JvmField var dwReserved0: Int = 0
    @JvmField var dwReserved1: Int = 0
    @JvmField var cFileName = ShortArray(260)
    @JvmField var cAlternateFileName = ShortArray(14)
}

@Structure.FieldOrder("dwFileAttributes", "ftCreationTime", "ftLastAccessTime", "ftLastWriteTime",
    "dwVolumeSerialNumber", "nFileSizeHigh", "nFileSizeLow", "nNumberOfLinks",
    "nFileIndexHigh", "nFileIndexLow")
internal class WindowsByHandleFileInformation : Structure() {
    @JvmField var dwFileAttributes: Int = 0
    @JvmField var ftCreationTime = WindowsFileTime()
    @JvmField var ftLastAccessTime = WindowsFileTime()
    @JvmField var ftLastWriteTime = WindowsFileTime()
    @JvmField var dwVolumeSerialNumber: Int = 0
    @JvmField var nFileSizeHigh: Int = 0
    @JvmField var nFileSizeLow: Int = 0
    @JvmField var nNumberOfLinks: Int = 0
    @JvmField var nFileIndexHigh: Int = 0
    @JvmField var nFileIndexLow: Int = 0
}

@Structure.FieldOrder("length", "descriptor", "inheritHandle")
internal class WindowsIdentitySecurityAttributes : Structure() {
    @JvmField var length: Int = 0
    @JvmField var descriptor: Pointer? = null
    @JvmField var inheritHandle: Int = 0
}

internal interface WindowsSecureIdentityKernel32 : StdCallLibrary {
    fun GetCurrentProcess(): Pointer
    fun CloseHandle(handle: Pointer): Int
    fun LocalFree(memory: Pointer): Pointer?
    fun GetDriveTypeW(root: WString): Int
    fun CreateDirectoryW(path: WString, attributes: Pointer?): Int
    fun CreateFileW(path: WString, access: Int, share: Int, attributes: Pointer?,
        disposition: Int, flags: Int, template: Pointer?): Pointer
    fun GetFileType(handle: Pointer): Int
    fun GetFileInformationByHandle(handle: Pointer, information: WindowsByHandleFileInformation): Int
    fun GetFileInformationByHandleEx(handle: Pointer, kind: Int, information: Pointer, size: Int): Int
    fun FindFirstFileW(pattern: WString, data: WindowsFindDataW): Pointer
    fun FindNextFileW(handle: Pointer, data: WindowsFindDataW): Int
    fun FindClose(handle: Pointer): Int
}

internal interface WindowsSecureIdentityAdvapi32 : StdCallLibrary {
    fun OpenProcessToken(process: Pointer, access: Int, token: PointerByReference): Int
    fun GetTokenInformation(token: Pointer, kind: Int, buffer: Pointer?, length: Int,
        needed: IntByReference): Int
    fun GetSecurityDescriptorControl(descriptor: Pointer, control: ShortByReference, revision: IntByReference): Int
    fun GetSecurityDescriptorOwner(descriptor: Pointer, owner: PointerByReference, defaulted: IntByReference): Int
    fun GetSecurityDescriptorDacl(descriptor: Pointer, present: IntByReference, dacl: PointerByReference,
        defaulted: IntByReference): Int
    fun IsValidAcl(acl: Pointer): Int
    fun GetAce(acl: Pointer, index: Int, ace: PointerByReference): Int
    fun ConvertSidToStringSidW(sid: Pointer, text: PointerByReference): Int
    fun ConvertStringSecurityDescriptorToSecurityDescriptorW(text: WString, revision: Int,
        descriptor: PointerByReference, size: IntByReference?): Int
    fun IsValidSid(sid: Pointer): Int
    fun GetLengthSid(sid: Pointer): Int
    fun EqualSid(first: Pointer, second: Pointer): Int
    fun GetSecurityInfo(handle: Pointer, kind: Int, information: Int, owner: PointerByReference?,
        group: PointerByReference?, dacl: PointerByReference?, sacl: PointerByReference?,
        descriptor: PointerByReference): Int
    fun RegCreateKeyExW(root: Pointer, subKey: WString, reserved: Int, className: WString?,
        options: Int, desired: Int, attributes: Pointer?, result: PointerByReference,
        disposition: IntByReference): Int
    fun RegOpenKeyExW(root: Pointer, subKey: WString, options: Int, desired: Int,
        result: PointerByReference): Int
    fun RegCloseKey(key: Pointer): Int
    fun RegQueryInfoKeyW(key: Pointer, className: Pointer?, classNameLength: IntByReference?,
        reserved: Pointer?, subkeys: IntByReference?, maxSubkeyLength: IntByReference?,
        maxClassLength: IntByReference?, values: IntByReference?, maxValueNameLength: IntByReference?,
        maxValueLength: IntByReference?, securityDescriptor: IntByReference?,
        lastWriteTime: Pointer?): Int
    fun RegEnumValueW(key: Pointer, index: Int, valueName: Pointer?, valueNameLength: IntByReference,
        reserved: IntByReference?, type: IntByReference?, data: Pointer?, dataLength: IntByReference?): Int
    fun RegQueryValueExW(key: Pointer, valueName: WString?, reserved: Pointer?, type: IntByReference?,
        data: Pointer?, dataLength: IntByReference?): Int
    fun RegSetValueExW(key: Pointer, valueName: WString?, reserved: Int, type: Int,
        data: Pointer?, dataLength: Int): Int
    fun RegDeleteValueW(key: Pointer, valueName: WString?): Int
    fun RegFlushKey(key: Pointer): Int
    fun RegGetKeySecurity(key: Pointer, information: Int, descriptor: Pointer?,
        descriptorLength: IntByReference): Int
}

internal interface WindowsSecureIdentityCrypt32 : StdCallLibrary {
    fun CryptProtectData(input: WindowsDataBlob, description: Pointer?, entropy: WindowsDataBlob?,
        reserved: Pointer?, prompt: Pointer?, flags: Int, output: WindowsDataBlob): Int
    fun CryptUnprotectData(input: WindowsDataBlob, description: PointerByReference?,
        entropy: WindowsDataBlob?, reserved: Pointer?, prompt: Pointer?, flags: Int,
        output: WindowsDataBlob): Int
}

internal interface WindowsSecureIdentityShell32 : StdCallLibrary {
    fun SHGetKnownFolderPath(folder: Pointer, flags: Int, token: Pointer?,
        path: PointerByReference): Int
}

internal interface WindowsSecureIdentityOle32 : StdCallLibrary {
    fun CoTaskMemFree(pointer: Pointer?)
}
