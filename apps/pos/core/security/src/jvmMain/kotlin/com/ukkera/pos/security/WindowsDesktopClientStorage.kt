package com.ukkera.pos.security

import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import java.nio.file.Path

/** Native facts of one existing Windows file or directory (DATA; observed through a non-following handle). */
public class WindowsEntryObservation internal constructor(
    public val volumeSerial: Long,
    public val fileId: String,
    public val links: Int,
    public val size: Long,
    public val lastWrite: Long,
    public val directory: Boolean,
    public val ownerSid: String,
)

/** Packaged-app file observations for desktop client journals. */
public object WindowsDesktopFiles {
    public fun observe(path: Path): WindowsEntryObservation = WindowsOriginalNative.observeEntryFacts(path.toAbsolutePath().toString())
    /** Process-token user SID of the exact packaged app (no impersonation). */
    public fun currentUserSid(expected: SelfOwnedWindowsPackagedAppIdentityV2): String = WindowsClientTargetKey.requirePackagedUser(expected)
    /** NTFS volume UUID of an existing directory, observed natively. */
    public fun volumeUuid(directory: Path): String {
        val support = WindowsOriginalNative.openSupportDirectory(directory)
        try { return support.volumeUuid } finally { support.handle.closeConfirmed() }
    }
}

/**
 * Per-user CNG custody of desktop client journal keys inside the packaged app (owner decision: no
 * Client service). Data keys are persisted non-exportable AES-256 keys used for AES-GCM; anti-rollback
 * heads are zero-use persisted keys created/deleted by exact name. Every operation re-observes the
 * exact packaged identity and refuses impersonation. Key bytes never leave CNG.
 */
public class WindowsClientJournalKeyStore private constructor(private val expected: SelfOwnedWindowsPackagedAppIdentityV2) {
    public fun keyNames(prefix: String): List<String> = withProvider { provider ->
        val found = ArrayList<String>()
        val state = PointerByReference()
        try {
            var count = 0
            while (true) {
                val name = PointerByReference()
                val status = WindowsOriginalCng.api.NCryptEnumKeys(provider, null, name, state, WindowsOriginalCng.SILENT)
                if (status == NTE_NO_MORE_ITEMS) break
                status.checkCng()
                val buffer = checkNotNull(name.value)
                try {
                    check(++count <= 65_536)
                    val text = checkNotNull(buffer.getPointer(0)).getWideString(0)
                    if (text.startsWith(NAMESPACE)) text.removePrefix(NAMESPACE).takeIf { it.startsWith(prefix) }?.let(found::add)
                } finally { WindowsOriginalCng.api.NCryptFreeBuffer(buffer) }
            }
        } finally { state.value?.let { WindowsOriginalCng.api.NCryptFreeBuffer(it) } }
        found
    }

    public fun keyExists(name: String): Boolean = withProvider { provider ->
        val output = PointerByReference()
        when (val status = WindowsOriginalCng.api.NCryptOpenKey(provider, output, WString(NAMESPACE + name), 0, WindowsOriginalCng.SILENT)) {
            0 -> { WindowsOriginalCng.api.NCryptFreeObject(checkNotNull(output.value)).checkCng(); true }
            NTE_BAD_KEYSET -> false
            else -> { status.checkCng(); false }
        }
    }

    /** Creates a new persisted key; an existing name is never overwritten. */
    public fun createKey(name: String): Unit = withProvider { provider ->
        val user = WindowsClientTargetKey.requirePackagedUser(expected)
        val output = PointerByReference()
        WindowsOriginalCng.api.NCryptCreatePersistedKey(provider, output, WString("AES"), WString(NAMESPACE + name), 0, 0).checkCng()
        val key = checkNotNull(output.value)
        try {
            WindowsOriginalCng.setInt(key, "Length", 256)
            WindowsOriginalCng.setInt(key, "Export Policy", 0)
            WindowsOriginalCng.setInt(key, "Key Usage", 1)
            WindowsOriginalCng.setPrivateSecurity(key, user, user)
            WindowsOriginalCng.api.NCryptFinalizeKey(key, WindowsOriginalCng.SILENT).checkCng()
        } finally { WindowsOriginalCng.api.NCryptFreeObject(key).checkCng() }
    }

    public fun deleteKey(name: String): Unit = withProvider { provider ->
        val output = PointerByReference()
        WindowsOriginalCng.api.NCryptOpenKey(provider, output, WString(NAMESPACE + name), 0, WindowsOriginalCng.SILENT).checkCng()
        val key = checkNotNull(output.value)
        // NCryptDeleteKey frees the handle on success; on failure the handle is still ours.
        val status = WindowsOriginalCng.api.NCryptDeleteKey(key, WindowsOriginalCng.SILENT)
        if (status != 0) { WindowsOriginalCng.api.NCryptFreeObject(key); status.checkCng() }
    }

    /** Fixed policy observation; anything else is refused. */
    public fun inspectKey(name: String): String = withKey(name) { key -> requirePolicy(key); POLICY }

    public fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val nonce = JvmP256Primitives.randomBytes(12)
        return withKey(name) { key ->
            requirePolicy(key); setGcm(key)
            nonce.copyOf() to WindowsSelfOwnedStorageKey.crypt(key, false, nonce, aad, plaintext)
        }.also { nonce.fill(0) }
    }

    public fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray = withKey(name) { key ->
        requirePolicy(key); setGcm(key)
        WindowsSelfOwnedStorageKey.crypt(key, true, nonce, aad, ciphertext)
    }

    private fun requirePolicy(key: Pointer) {
        val user = WindowsClientTargetKey.requirePackagedUser(expected)
        check(WindowsOriginalCng.stringProperty(key, "Algorithm Name") == "AES" &&
            WindowsOriginalCng.intProperty(key, "Length") == 256 &&
            WindowsOriginalCng.intProperty(key, "Export Policy") == 0 &&
            WindowsOriginalCng.intProperty(key, "Key Usage") == 1) { "Client journal key policy differs" }
        WindowsOriginalCng.requirePrivateSecurity(key, user, user)
    }

    private fun setGcm(key: Pointer) {
        val bytes = "ChainingModeGCM\u0000".toByteArray(Charsets.UTF_16LE)
        com.sun.jna.Memory(bytes.size.toLong()).use { memory ->
            memory.write(0, bytes, 0, bytes.size)
            WindowsOriginalCng.api.NCryptSetProperty(key, WString("Chaining Mode"), memory, bytes.size, 0).checkCng()
        }
    }

    private fun <T> withKey(name: String, body: (Pointer) -> T): T = withProvider { provider ->
        val output = PointerByReference()
        WindowsOriginalCng.api.NCryptOpenKey(provider, output, WString(NAMESPACE + name), 0, WindowsOriginalCng.SILENT).checkCng()
        val key = checkNotNull(output.value)
        try { body(key) } finally { WindowsOriginalCng.api.NCryptFreeObject(key).checkCng() }
    }

    private fun <T> withProvider(body: (Pointer) -> T): T {
        check(Platform.isWindows()) { "Windows is required" }
        WindowsClientTargetKey.requirePackagedUser(expected)
        val output = PointerByReference()
        WindowsOriginalCng.api.NCryptOpenStorageProvider(output, WString("Microsoft Software Key Storage Provider"), 0).checkCng()
        val provider = checkNotNull(output.value)
        try {
            WindowsOriginalCng.requireProvider(provider, SelfOwnedKeyProtectionV1.OS_ISOLATED)
            return body(provider)
        } finally { WindowsOriginalCng.api.NCryptFreeObject(provider).checkCng() }
    }

    public companion object {
        private const val NAMESPACE = "ukkera.pos.client-journal.v1:"
        private const val POLICY = "AES256-GCM-NONEXPORTABLE-USER-KSP"
        private const val NTE_BAD_KEYSET = 0x80090016.toInt()
        private const val NTE_NO_MORE_ITEMS = 0x8009002A.toInt()
        public fun open(expected: SelfOwnedWindowsPackagedAppIdentityV2): WindowsClientJournalKeyStore {
            WindowsClientTargetKey.requirePackagedUser(expected)
            return WindowsClientJournalKeyStore(expected)
        }
    }
}
