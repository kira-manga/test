package com.ukkera.pos.recovery.store

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Android Master recovery key custody: a non-exportable AES-256-GCM Android Keystore key under a
 * fixed alias. The key never leaves the Keystore; archives sealed under it are unreadable by any
 * other device or after the key is lost (an independent copy needs the held central counterpart).
 */
public object AndroidKeystoreMasterBackupKeys {
    private const val PROVIDER = "AndroidKeyStore"

    /**
     * Existing key under [alias], or a newly generated one only when [createIfAbsent] is true.
     * Archives sealed by a different (lost) key fail authentication; nothing is replaced.
     */
    public fun custody(alias: String, createIfAbsent: Boolean): MasterBackupKeyCustody? {
        require(alias.length in 8..96 && alias.all { it.isLetterOrDigit() || it in "._-" })
        val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
        if (!store.containsAlias(alias)) {
            if (!createIfAbsent) return null
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build())
            generator.generateKey()
        }
        return JcaAesGcmMasterBackupKeyCustody("android-keystore:$alias") {
            val loaded = KeyStore.getInstance(PROVIDER).apply { load(null) }
            loaded.getKey(alias, null) as? SecretKey ?: error("Recovery key unavailable")
        }
    }
}
