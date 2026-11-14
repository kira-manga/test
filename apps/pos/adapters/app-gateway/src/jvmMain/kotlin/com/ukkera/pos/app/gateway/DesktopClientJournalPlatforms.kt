package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.DesktopMasterClientJournalCustody
import com.ukkera.pos.security.MacClientJournalKeyStore
import com.ukkera.pos.security.MacDesktopFiles
import com.ukkera.pos.security.MacInstalledApplicationIdentity
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityWireV2
import com.ukkera.pos.security.SelfOwnedMacUiIdentityV2
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

/** macOS login-Keychain custody of desktop client journal keys (signed app, no sync, no UI). */
internal class MacJournalKeys(private val store: MacClientJournalKeyStore) : DesktopJournalKeys {
    override fun keyNames(prefix: String): List<String> = store.keyNames(prefix)
    override fun keyExists(name: String): Boolean = store.keyExists(name)
    override fun createKey(name: String, protection: SelfOwnedKeyProtectionV1) {
        check(protection == SelfOwnedKeyProtectionV1.OS_ISOLATED) { "Only the OS-isolated login Keychain profile is implemented" }
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

/** Signed macOS app facts bound into desktop journal AEAD. */
internal class MacJournalPlatformFacts(private val expected: SelfOwnedMacUiIdentityV2, parent: Path) : DesktopJournalPlatformFacts {
    override val targetDigest: String = SelfOwnedInstalledIdentityWireV2.encodeTarget(expected).let { bytes ->
        try { MessageDigest.getInstance("SHA-256").digest(bytes).useWiped(::encodeHex) } finally { bytes.fill(0) }
    }
    override val volumeId: String = MacDesktopFiles.volumeUuid(parent)
    override fun requireCurrent() {
        check(MacInstalledApplicationIdentity.captureUi(expected) is SelfOwnedInstalledIdentityResultV2.Data) { "client.journal.unavailable" }
    }
}

/** Desktop Master SERVICE custody: the service's own OS key custody and secret-store key index. */
internal class MasterServiceJournalKeys(private val custody: DesktopMasterClientJournalCustody) : DesktopJournalKeys {
    override fun keyNames(prefix: String): List<String> = custody.keyNames(prefix)
    override fun keyExists(name: String): Boolean = custody.keyExists(name)
    override fun createKey(name: String, protection: SelfOwnedKeyProtectionV1) {
        check(protection == SelfOwnedKeyProtectionV1.OS_ISOLATED) { "Only the OS-isolated Master service profile is implemented" }
        custody.createKey(name)
    }
    override fun deleteKey(name: String) = custody.deleteKey(name)
    override fun inspectKey(name: String, protection: SelfOwnedKeyProtectionV1): Any {
        check(protection == SelfOwnedKeyProtectionV1.OS_ISOLATED)
        return custody.inspectKey(name)
    }
    override fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> = custody.encrypt(name, aad, plaintext)
    override fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
        custody.decrypt(name, nonce, ciphertext, aad)
}

/** Installed Master service facts bound into its cohost journal AEAD; re-observed on each base check. */
internal class MasterServiceJournalPlatformFacts(private val custody: DesktopMasterClientJournalCustody) : DesktopJournalPlatformFacts {
    override val targetDigest: String get() = custody.targetDigest
    override val volumeId: String get() = custody.volumeId
    override fun requireCurrent() = custody.requireCurrent()
}

/** Public platform factories for desktop client journal storage. */
public object DesktopClientJournalStorages {
    /** Desktop Master SERVICE same-device cohost journals: the service's own owner-only directory and
     * key custody, from the SAME OS custody as its Master (never the UI-process stores). */
    public fun masterService(custody: DesktopMasterClientJournalCustody): DesktopClientJournalStorage =
        DesktopClientJournalStorage(custody.parentDirectory, MasterServiceJournalKeys(custody), MasterServiceJournalPlatformFacts(custody))

    /** Windows packaged app: the package's own LocalState folder, CNG keys, packaged identity facts. */
    public fun windows(expected: SelfOwnedWindowsPackagedAppIdentityV2): DesktopClientJournalStorage {
        val local = System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA is unavailable")
        val parent = Paths.get(local, "Packages", expected.packageFamilyName, "LocalState")
        check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "Package LocalState is unavailable" }
        return DesktopClientJournalStorage(parent, WindowsJournalKeys(WindowsClientJournalKeyStore.open(expected)),
            WindowsJournalPlatformFacts(expected, parent))
    }

    /** Signed macOS app: Application Support/<bundle>, login-Keychain journal keys, Apple-anchored identity facts. */
    public fun mac(expected: SelfOwnedMacUiIdentityV2): DesktopClientJournalStorage {
        val parent = MacDesktopFiles.applicationSupport()
        return DesktopClientJournalStorage(parent, MacJournalKeys(MacClientJournalKeyStore.open()), MacJournalPlatformFacts(expected, parent))
    }
}
