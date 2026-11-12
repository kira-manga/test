package com.ukkera.pos.app.gateway

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.ukkera.pos.security.AndroidNativeKeyInfo
import com.ukkera.pos.security.AndroidNativeKeyPolicy
import com.ukkera.pos.security.AndroidNativeKeySecurity
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** App-owned AndroidKeyStore keys only. No imported/exportable key or replacement on failure. */
internal fun inspectClientJournalKey(
    store: KeyStore, alias: String, protection: SelfOwnedKeyProtectionV1,
): Pair<SecretKey, AndroidNativeKeySecurity> {
    check(AndroidNativeKeyPolicy.supports(Build.VERSION.SDK_INT, protection))
    check(store.containsAlias(alias))
    val key = store.getKey(alias, null) as? SecretKey ?: error("client.journal.key")
    val exported = key.encoded
    if (exported != null) { exported.fill(0); error("client.journal.exportable-key") }
    check(key.algorithm == KeyProperties.KEY_ALGORITHM_AES && key.format == null)
    val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .getKeySpec(key, KeyInfo::class.java) as KeyInfo
    check(info.keystoreAlias == alias && info.keySize == 256 &&
        info.purposes == (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) &&
        info.origin == KeyProperties.ORIGIN_GENERATED && !AndroidNativeKeyInfo.hasServiceRestrictions(info) &&
        info.signaturePaddings.isEmpty() && info.digests.isEmpty() &&
        info.blockModes.toSet() == setOf(KeyProperties.BLOCK_MODE_GCM) &&
        info.encryptionPaddings.toSet() == setOf(KeyProperties.ENCRYPTION_PADDING_NONE))
    val security = AndroidNativeKeyInfo.observe(info)
    check(AndroidNativeKeyPolicy.permits(protection, security))
    return key to security
}

/** A failed/unknown generation leaves its original alias for inspection, never delete-and-retry. */
internal fun createClientJournalKey(store: KeyStore, alias: String, protection: SelfOwnedKeyProtectionV1) {
    check(AndroidNativeKeyPolicy.supports(Build.VERSION.SDK_INT, protection))
    check(!store.containsAlias(alias))
    val builder = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true)
    val spec = AndroidNativeKeyInfo.configureService(builder, protection).build()
    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
        init(spec); generateKey()
    }
    inspectClientJournalKey(store, alias, protection)
}
