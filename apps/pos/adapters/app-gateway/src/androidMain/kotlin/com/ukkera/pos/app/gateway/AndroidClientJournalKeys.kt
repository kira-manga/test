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

/**
 * Filesystem snapshots do not roll back AndroidKeyStore. Retain a nonexportable native key
 * naming the complete encrypted manifest/archive digest. New writes first force their exact
 * write-ahead record, then create the successor BEFORE publishing the file change. A successor
 * fences the old cut even before publication; it never licenses falling back to the old head.
 * At most an old and a new head coexist. Remove the old key only after the complete successor
 * file cut is forced/read back; never remove the latest key or recreate a missing head.
 *
 * Ordinary opening is nonmutating. Explicit recovery requires a retained native successor
 * and the exact complete before/after file join. Unproved/legacy interruptions remain held.
 * This is not protection against compromise of the application UID/OS/keystore itself and
 * still requires physical platform/power-loss qualification. No key material enters files.
 */
internal class AndroidClientJournalHead(
    private val store: KeyStore,
    private val prefix: String,
    private val protection: SelfOwnedKeyProtectionV1,
    private val work: AndroidClientJournalWork,
) {
    private data class Head(val sequence: Long, val digest: String, val alias: String)
    private var retained: List<Head>? = null

    fun requireAbsent() { check(readHeads().isEmpty()) }

    fun enroll(digest: String) {
        check(retained == null)
        requireAbsent()
        val initial = head(0, digest)
        work.requireActive()
        createClientJournalKey(store, initial.alias, protection)
        check(readHeads() == listOf(initial))
        retained = listOf(initial)
    }

    fun open(digest: String) {
        check(retained == null)
        val actual = readHeads()
        check(actual.isNotEmpty() && actual.last().digest == digest)
        retained = actual
    }

    fun requireCurrent(digest: String) {
        val expected = checkNotNull(retained)
        check(readHeads() == expected && expected.last().digest == digest)
    }

    /** A completed, never-written enrollment, not merely an empty current manifest. */
    fun requireInitial(digest: String) {
        requireCurrent(digest)
        check(retained == listOf(head(0, digest)))
    }

    /** Settle an already durable current cut before writing a new write-ahead record. */
    fun settledSequence(digest: String): Long {
        requireCurrent(digest)
        pruneOld()
        return checkNotNull(retained).single().sequence
    }

    /** The owner has forced/read back its entire write-ahead record, not the new live file. */
    fun prepare(previousSequence: Long, previousDigest: String, nextDigest: String) {
        requireCurrent(previousDigest)
        val old = head(previousSequence, previousDigest)
        check(retained == listOf(old) && previousDigest != nextDigest)
        val next = head(Math.addExact(old.sequence, 1), nextDigest)
        work.requireActive()
        createClientJournalKey(store, next.alias, protection)
        val expected = listOf(old, next)
        check(readHeads() == expected)
        retained = expected
    }

    /** Positive existing-key evidence only. Never create a missing successor during recovery. */
    fun recoverPrepared(previousSequence: Long, previousDigest: String, nextDigest: String) {
        check(retained == null && previousDigest != nextDigest)
        val old = head(previousSequence, previousDigest)
        val next = head(Math.addExact(previousSequence, 1), nextDigest)
        val actual = readHeads()
        check(actual == listOf(next) || actual == listOf(old, next))
        retained = actual
    }

    /** Called only after forced/read-back complete successor files, including every archive. */
    fun finishPrepared(nextDigest: String) {
        requireCurrent(nextDigest)
        pruneOld()
        requireCurrent(nextDigest)
    }

    private fun pruneOld() {
        val actual = checkNotNull(retained)
        check(readHeads() == actual)
        if (actual.size == 1) return
        check(actual.size == 2)
        work.requireActive()
        store.deleteEntry(actual.first().alias)
        check(readHeads() == listOf(actual.last()))
        retained = listOf(actual.last())
    }

    private fun readHeads(): List<Head> {
        work.requireActive()
        val found = ArrayList<Head>(2)
        val names = store.aliases()
        val journalPrefix = prefix.removeSuffix("/").substringBeforeLast("/") + "/"
        var count = 0
        while (names.hasMoreElements()) {
            check(++count <= 65_536)
            val name = names.nextElement()
            if (name.startsWith(journalPrefix)) check(name.startsWith(prefix))
            if (!name.startsWith(prefix)) continue
            check(found.size < 2)
            val suffix = name.removePrefix(prefix)
            check(suffix.length == 81 && suffix[16] == '/' &&
                suffix.take(16).all { it in '0'..'9' || it in 'a'..'f' })
            val sequence = suffix.take(16).toLong(16)
            val value = head(sequence, suffix.drop(17))
            check(value.alias == name && found.none { it.sequence == sequence })
            inspectClientJournalKey(store, name, protection)
            found.add(value)
        }
        work.requireActive()
        val result = found.sortedBy { it.sequence }
        if (result.size == 2) check(result[0].sequence < Long.MAX_VALUE && result[1].sequence == result[0].sequence + 1)
        return result
    }

    private fun head(sequence: Long, digest: String): Head {
        check(sequence >= 0 && digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' })
        return Head(sequence, digest, prefix + sequence.toString(16).padStart(16, '0') + "/" + digest)
    }
}
