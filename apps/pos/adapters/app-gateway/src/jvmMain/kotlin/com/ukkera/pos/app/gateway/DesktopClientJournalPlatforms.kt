package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.SelfOwnedInstalledIdentityWireV2
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2
import com.ukkera.pos.security.WindowsClientJournalKeyStore
import com.ukkera.pos.security.WindowsDesktopFiles
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest

/** Windows CNG custody of desktop client journal keys (per-user, non-exportable, packaged identity). */
internal class WindowsJournalKeys(private val store: WindowsClientJournalKeyStore) : DesktopJournalKeys {
    override fun keyNames(prefix: String): List<String> = store.keyNames(prefix)
    override fun keyExists(name: String): Boolean = store.keyExists(name)
    override fun createKey(name: String, protection: SelfOwnedKeyProtectionV1) {
        check(protection == SelfOwnedKeyProtectionV1.OS_ISOLATED) { "Only the OS-isolated software KSP profile is implemented" }
        store.createKey(name)
    }
    override fun deleteKey(name: String) = store.deleteKey(name)
    override fun inspectKey(name: String, protection: SelfOwnedKeyProtectionV1): Any {
        check(protection == SelfOwnedKeyProtectionV1.OS_ISOLATED)
        return store.inspectKey(name)
    }
    override fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> = store.encrypt(name, aad, plaintext)
    override fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        store.decrypt(name, nonce, ciphertext, aad)
}

/** Windows packaged-app facts bound into desktop journal AEAD. */
internal class WindowsJournalPlatformFacts(private val expected: SelfOwnedWindowsPackagedAppIdentityV2, parent: Path) :
    DesktopJournalPlatformFacts {
    override val targetDigest: String = SelfOwnedInstalledIdentityWireV2.encodeTarget(expected).let { bytes ->
        try { MessageDigest.getInstance("SHA-256").digest(bytes).useWiped(::encodeHex) } finally { bytes.fill(0) }
    }
    override val volumeId: String = WindowsDesktopFiles.volumeUuid(parent)
    override fun requireCurrent() { WindowsDesktopFiles.currentUserSid(expected) }
}

/** Public platform factories for desktop client journal storage. */
public object DesktopClientJournalStorages {
    /** Windows packaged app: the package's own LocalState folder, CNG keys, packaged identity facts. */
    public fun windows(expected: SelfOwnedWindowsPackagedAppIdentityV2): DesktopClientJournalStorage {
        val local = System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA is unavailable")
        val parent = Paths.get(local, "Packages", expected.packageFamilyName, "LocalState")
        check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "Package LocalState is unavailable" }
        return DesktopClientJournalStorage(parent, WindowsJournalKeys(WindowsClientJournalKeyStore.open(expected)),
            WindowsJournalPlatformFacts(expected, parent))
    }
}
