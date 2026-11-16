package com.ukkera.pos.database

import com.ukkera.pos.app.gateway.DesktopClientJournalStorage
import com.ukkera.pos.app.gateway.DesktopJournalKeys
import com.ukkera.pos.app.gateway.DesktopJournalPlatformFacts
import com.ukkera.pos.security.SelfOwnedInstalledIdentityWireV2
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest

/**
 * TEST-ONLY Linux custody for the unchanged production encrypted Client journal. This requires
 * test-source module friendship; production exposes only its genuine OS-specific factories.
 * The key material is actual generated, durable JCA SOFTWARE material, explicitly exportable.
 * Installed-app identity is a stand-in. Neither fact is native Keychain/CNG qualification.
 */
internal object LanProcessJournalSupport {
    fun storage(root: Path): DesktopClientJournalStorage {
        val parent = LanProcessNativeSupport.privateDirectory(root.resolve("client-journals"))
        val custody = LanPersistentSoftwareCustody(root.resolve("journal-software-keys"))
        val keys = object : DesktopJournalKeys {
            override fun keyNames(prefix: String): List<String> = custody.keyNames(prefix)
            override fun keyExists(name: String): Boolean = custody.keyExists(name)
            override fun createKey(name: String, protection: SelfOwnedKeyProtectionV1) {
                check(protection == SelfOwnedKeyProtectionV1.OS_ISOLATED)
                custody.createAes(name)
            }
            override fun deleteKey(name: String) = custody.deleteKey(name)
            override fun inspectKey(name: String, protection: SelfOwnedKeyProtectionV1): Any {
                check(protection == SelfOwnedKeyProtectionV1.OS_ISOLATED && custody.keyExists(name))
                val check = checkNotNull(custody.symmetricCheck(name))
                try { return "TEST_ONLY_SOFTWARE:" + check.joinToString("") { "%02x".format(it.toInt() and 255) } }
                finally { check.fill(0) }
            }
            override fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> =
                custody.sealAes(name, aad, plaintext)
            override fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
                custody.openAes(name, aad, nonce, ciphertext)
        }
        val original = Files.readAttributes(parent, "unix:*", NOFOLLOW_LINKS)
        val originalIdentity = listOf(original["dev"], original["ino"], original["uid"], original["mode"])
        val facts = object : DesktopJournalPlatformFacts {
            override val targetDigest: String = SelfOwnedInstalledIdentityWireV2.encodeTarget(LanProcessNativeSupport.clientIdentity)
                .let { bytes -> try { MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it.toInt() and 255) } } finally { bytes.fill(0) } }
            override val volumeId: String = LanProcessNativeSupport.volume(parent)
            override fun requireCurrent() {
                check(parent.toRealPath(NOFOLLOW_LINKS) == parent)
                val current = Files.readAttributes(parent, "unix:*", NOFOLLOW_LINKS)
                check(listOf(current["dev"], current["ino"], current["uid"], current["mode"]) == originalIdentity)
                check(LanProcessNativeSupport.volume(parent) == volumeId)
            }
        }
        return DesktopClientJournalStorage(parent, keys, facts)
    }
}
