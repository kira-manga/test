package com.ukkera.pos.security

import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Symmetric desktop custody over an OS-protected secret store (macOS data-protection Keychain or
 * Windows DPAPI for the service account). A 32-byte secret per alias is created once (atomic
 * put-if-absent, existing aliases are never replaced) and is used only transiently inside this
 * process; it never crosses the [DesktopKeyCustody] seam. Security class: SOFTWARE.
 */
internal abstract class DesktopSecretStoreCustody(private val store: JvmSecureIdentityStore) : DesktopKeyCustody {
    private val random = SecureRandom()

    override fun createAes(alias: String) = create(AES, alias)
    override fun createHmac(alias: String) = create(HMAC, alias)

    override fun sealAes(alias: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> = use(AES, alias) { secret ->
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        nonce to cipher.doFinal(plaintext)
    }

    override fun openAes(alias: String, aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray = use(AES, alias) { secret ->
        desktopKnown(nonce.size == 12 && ciphertext.size >= 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        try { cipher.doFinal(ciphertext) } catch (_: java.security.GeneralSecurityException) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    override fun hmac(alias: String, message: ByteArray): ByteArray = use(HMAC, alias) { secret -> mac(secret, message) }

    override fun symmetricCheck(alias: String): ByteArray? {
        val label = DesktopKeyCustody.CHECK_LABEL.toByteArray(Charsets.US_ASCII)
        read(AES, alias)?.let { secret ->
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, ByteArray(12)))
                cipher.updateAAD(label)
                val tag = cipher.doFinal()
                return try { MessageDigest.getInstance("SHA-256").digest(tag) } finally { tag.fill(0) }
            } finally { secret.fill(0) }
        }
        read(HMAC, alias)?.let { secret -> try { return mac(secret, label) } finally { secret.fill(0) } }
        return null
    }

    private fun mac(secret: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }.doFinal(message)

    private fun namespace(kind: String, alias: String): String {
        desktopKnown(alias.length in 1..200 && alias.all { it.code in 0x21..0x7e })
        return "ukkera.desktop-master.$kind.v2/$alias"
    }

    private fun read(kind: String, alias: String): ByteArray? = store.read(namespace(kind, alias))?.also {
        if (it.size != 32) { it.fill(0); selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN) }
    }

    private fun create(kind: String, alias: String) {
        // Each alias names exactly one key kind; an existing alias of either kind is never replaced.
        if (store.read(namespace(AES, alias))?.also { it.fill(0) } != null ||
            store.read(namespace(HMAC, alias))?.also { it.fill(0) } != null) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        }
        val secret = ByteArray(32).also(random::nextBytes)
        try {
            val kept = store.putIfAbsent(namespace(kind, alias), secret)
            try { if (!MessageDigest.isEqual(kept, secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN) }
            finally { kept.fill(0) }
        } finally { secret.fill(0) }
    }

    private inline fun <T> use(kind: String, alias: String, block: (ByteArray) -> T): T {
        val secret = read(kind, alias) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        try { return block(secret) } finally { secret.fill(0) }
    }

    private companion object {
        const val AES = "aes"
        const val HMAC = "hmac"
    }
}

/** macOS: P-256 in the data-protection Keychain (non-extractable), symmetric secrets in Keychain items. */
internal class MacDesktopKeyCustody(store: JvmSecureIdentityStore, private val protection: SelfOwnedKeyProtectionV1) :
    DesktopSecretStoreCustody(store) {
    override fun p256Point(alias: String): ByteArray? {
        val tag = MacDesktopAliasTag(alias)
        if (!MacSelfOwnedSigningKey.desktopExists(tag)) return null
        return MacSelfOwnedSigningKey.openDesktop(tag, protection).use { it.copyPublicPoint() }
    }
    override fun createP256(alias: String): ByteArray =
        MacSelfOwnedSigningKey.createAfterForcedIntent(MacDesktopAliasTag(alias), protection).use { it.copyPublicPoint() }
    override fun signP256(alias: String, message: ByteArray): ByteArray =
        MacSelfOwnedSigningKey.openDesktop(MacDesktopAliasTag(alias), protection).use { it.signDesktop(message) }
    override fun toString(): String = "MacDesktopKeyCustody([REDACTED])"
}

/** Windows: P-256 in a service-ACL'd CNG KSP key, symmetric secrets DPAPI-protected for the service. */
internal class WindowsDesktopKeyCustody(store: JvmSecureIdentityStore, private val target: SelfOwnedInstalledIdentityTargetV2,
    private val protection: SelfOwnedKeyProtectionV1) : DesktopSecretStoreCustody(store) {
    override fun p256Point(alias: String): ByteArray? {
        if (!WindowsSelfOwnedSigningKey.desktopExists(target, alias, protection)) return null
        return WindowsSelfOwnedSigningKey.openDesktop(target, alias, protection, null).use { it.publicPoint() }
    }
    override fun createP256(alias: String): ByteArray =
        WindowsSelfOwnedSigningKey.createDesktop(target, alias, protection).use { it.publicPoint() }
    override fun signP256(alias: String, message: ByteArray): ByteArray =
        WindowsSelfOwnedSigningKey.openDesktop(target, alias, protection, null).use { it.sign(message) }
    override fun toString(): String = "WindowsDesktopKeyCustody([REDACTED])"
}
