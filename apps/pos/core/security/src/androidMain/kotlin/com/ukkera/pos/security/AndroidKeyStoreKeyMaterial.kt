package com.ukkera.pos.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * INTERNAL native material only; no accepting DeviceProtectedLocalStorage/DeviceProtectedPepper
 * binding is exported by this cut. KeyInfo establishes key characteristics, not independent
 * process supervision. A later actual custody-bound adapter must satisfy that stronger contract.
 */
internal class AndroidKeyStoreOpenedMaterial internal constructor(
    val enrollment: AndroidKeyStoreEnrollmentRecord,
) {
    val configuration: AndroidKeyStoreConfiguration get() = enrollment.configuration
    val protectionStatus: KeyProtectionStatus get() = KeyProtectionStatus.UNQUALIFIED_CONTEXT
    val storageOperations: AndroidKeyStoreLocalStorageOperations = AndroidKeyStoreLocalStorageOperations(this)
    val pepperOperations: AndroidKeyStorePepperOperations = AndroidKeyStorePepperOperations(this)

    /** Fresh native handle + exact installed profile; never a caller-selected alias or byte key. */
    internal fun storageKey(): SecretKey = existingKey(AndroidKeyStoreKeyKind.STORAGE)

    internal fun pepperKey(): SecretKey = existingKey(AndroidKeyStoreKeyKind.PEPPER)

    private fun existingKey(kind: AndroidKeyStoreKeyKind): SecretKey {
        requireSupportedAndroidKeyProfile(configuration)
        val key = loadExistingAndroidKey(androidKeyStore(), configuration, kind)
        val observed = inspectAndroidKey(configuration, kind, key)
        val expected = when (kind) {
            AndroidKeyStoreKeyKind.STORAGE -> enrollment.storage
            AndroidKeyStoreKeyKind.PEPPER -> enrollment.pepper
        }
        if (observed != expected) androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_MISMATCH)
        return key
    }

    override fun toString(): String = "AndroidKeyStoreOpenedMaterial([REDACTED])"
}

/**
 * Trusted installer/service internals, not application endpoints. The create method's name is
 * not authorization: actual installer approval, a durable incomplete-install fence and exclusive
 * cross-process alias/installation custody must ALREADY exist before calling it.
 */
internal object AndroidKeyStoreMaterialFactory {
    private val lifecycleSlot: Semaphore = Semaphore(1)

    /**
     * Creates both distinct aliases only for an explicitly authorized new installation. Refuses
     * if EITHER alias already exists. No deletion, overwrite, retry under another policy, or repair
     * of partial enrollment is supplied. AndroidKeyStore has no atomic two-key/no-replace create;
     * the process-local gate below is NOT a substitute for the required external native custody.
     *
     * Cancellation/failure can leave one or both keys installed. Retain the install fence and
     * investigate through the separate installer procedure; never call create again as a repair.
     */
    suspend fun createForAuthorizedInstall(configuration: AndroidKeyStoreConfiguration): AndroidKeyStoreAcquisition {
        currentCoroutineContext().ensureActive()
        if (!lifecycleSlot.tryAcquire()) return AndroidKeyStoreAcquisition.Unavailable(AndroidKeyStoreFailure.BUSY)
        var material: AndroidKeyStoreOpenedMaterial? = null
        return try {
            val failure = AndroidKeyStoreNativeWork.run {
                requireSupportedAndroidKeyProfile(configuration)
                val store = androidKeyStore()
                if (store.containsAlias(configuration.storageAlias) || store.containsAlias(configuration.pepperAlias)) {
                    androidKeyStoreFail(AndroidKeyStoreFailure.KEY_ALREADY_PRESENT)
                }
                generateAndroidKey(configuration, AndroidKeyStoreKeyKind.STORAGE)
                val storage = loadExistingAndroidKey(store, configuration, AndroidKeyStoreKeyKind.STORAGE)
                val storageProfile = inspectAndroidKey(configuration, AndroidKeyStoreKeyKind.STORAGE, storage)
                generateAndroidKey(configuration, AndroidKeyStoreKeyKind.PEPPER)
                val pepper = loadExistingAndroidKey(store, configuration, AndroidKeyStoreKeyKind.PEPPER)
                val record = AndroidKeyStoreEnrollmentRecord(configuration,
                    storageProfile,
                    inspectAndroidKey(configuration, AndroidKeyStoreKeyKind.PEPPER, pepper))
                material = AndroidKeyStoreOpenedMaterial(record)
            }
            if (failure != null) AndroidKeyStoreAcquisition.Unavailable(failure)
            else material?.let { AndroidKeyStoreAcquisition.Opened(it) }
                ?: AndroidKeyStoreAcquisition.Unavailable(AndroidKeyStoreFailure.PROVIDER_FAILURE)
        } finally {
            lifecycleSlot.release()
        }
    }

    /**
     * Reads ONLY the exact preauthenticated installer record and its existing aliases. No key
     * generation path is reachable here. A missing/unreadable record is NOT permission to call
     * createForAuthorizedInstall; authenticating/persisting this record belongs to the installer.
     */
    suspend fun openExisting(enrollment: AndroidKeyStoreEnrollmentRecord): AndroidKeyStoreAcquisition {
        currentCoroutineContext().ensureActive()
        if (!lifecycleSlot.tryAcquire()) return AndroidKeyStoreAcquisition.Unavailable(AndroidKeyStoreFailure.BUSY)
        var material: AndroidKeyStoreOpenedMaterial? = null
        return try {
            val failure = AndroidKeyStoreNativeWork.run {
                val configuration = enrollment.configuration
                requireSupportedAndroidKeyProfile(configuration)
                val store = androidKeyStore()
                val storage = loadExistingAndroidKey(store, configuration, AndroidKeyStoreKeyKind.STORAGE)
                val pepper = loadExistingAndroidKey(store, configuration, AndroidKeyStoreKeyKind.PEPPER)
                if (inspectAndroidKey(configuration, AndroidKeyStoreKeyKind.STORAGE, storage) != enrollment.storage ||
                    inspectAndroidKey(configuration, AndroidKeyStoreKeyKind.PEPPER, pepper) != enrollment.pepper) {
                    androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_MISMATCH)
                }
                material = AndroidKeyStoreOpenedMaterial(enrollment)
            }
            if (failure != null) AndroidKeyStoreAcquisition.Unavailable(failure)
            else material?.let { AndroidKeyStoreAcquisition.Opened(it) }
                ?: AndroidKeyStoreAcquisition.Unavailable(AndroidKeyStoreFailure.PROVIDER_FAILURE)
        } finally {
            lifecycleSlot.release()
        }
    }
}

/**
 * At most four admitted native operations in this process, with no waiter queue. Each caller
 * owns its buffers until the dispatched BLOCKING native call really returns, even on cancellation.
 * Dispatch/timeout is not proof that a blocked provider stopped; independent process retirement
 * and installation cleanup remain the future supervisor's responsibility.
 */
internal object AndroidKeyStoreNativeWork {
    private val slots: Semaphore = Semaphore(4)

    suspend fun run(work: () -> Unit): AndroidKeyStoreFailure? {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return AndroidKeyStoreFailure.BUSY
        return try {
            withContext(Dispatchers.IO) { work() }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: AndroidKeyStoreAccessFailure) {
            failure.reason
        } catch (_: UserNotAuthenticatedException) {
            AndroidKeyStoreFailure.KEY_LOCKED
        } catch (_: KeyPermanentlyInvalidatedException) {
            AndroidKeyStoreFailure.KEY_MISSING_OR_INVALIDATED
        } catch (_: UnrecoverableKeyException) {
            AndroidKeyStoreFailure.KEY_MISSING_OR_INVALIDATED
        } catch (_: AEADBadTagException) {
            AndroidKeyStoreFailure.CORRUPT_CIPHERTEXT
        } catch (_: Exception) {
            // Includes unavailable StrongBox/provider failures: NEVER retry with another profile.
            AndroidKeyStoreFailure.PROVIDER_FAILURE
        } finally {
            slots.release()
        }
    }
}

private enum class AndroidKeyStoreKeyKind { STORAGE, PEPPER }

private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null, null) }

private fun requireSupportedAndroidKeyProfile(configuration: AndroidKeyStoreConfiguration) {
    if (!AndroidNativeKeyPolicy.supports(Build.VERSION.SDK_INT, configuration.protectionRequirement.selfOwnedProtection())) {
        androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_UNSUPPORTED)
    }
}

private fun AndroidKeyStoreProtectionRequirement.selfOwnedProtection(): SelfOwnedKeyProtectionV1 = when (this) {
    AndroidKeyStoreProtectionRequirement.ANDROID_KEYSTORE -> SelfOwnedKeyProtectionV1.OS_ISOLATED
    AndroidKeyStoreProtectionRequirement.SECURE_HARDWARE -> SelfOwnedKeyProtectionV1.SECURE_HARDWARE
    AndroidKeyStoreProtectionRequirement.STRONGBOX -> SelfOwnedKeyProtectionV1.STRONGBOX
}

private fun generateAndroidKey(configuration: AndroidKeyStoreConfiguration, kind: AndroidKeyStoreKeyKind) {
    val builder = KeyGenParameterSpec.Builder(alias(configuration, kind), purposes(kind))
        .setKeySize(256)
    AndroidNativeKeyInfo.configureService(builder, configuration.protectionRequirement.selfOwnedProtection())
    when (kind) {
        AndroidKeyStoreKeyKind.STORAGE -> builder
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
        AndroidKeyStoreKeyKind.PEPPER -> builder.setDigests(KeyProperties.DIGEST_SHA256)
    }
    KeyGenerator.getInstance(algorithm(kind), "AndroidKeyStore").apply { init(builder.build()) }.generateKey()
}

private fun loadExistingAndroidKey(store: KeyStore, configuration: AndroidKeyStoreConfiguration, kind: AndroidKeyStoreKeyKind): SecretKey {
    val alias = alias(configuration, kind)
    if (!store.containsAlias(alias)) androidKeyStoreFail(AndroidKeyStoreFailure.KEY_MISSING_OR_INVALIDATED)
    return store.getKey(alias, null) as? SecretKey ?: androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_MISMATCH)
}

@Suppress("DEPRECATION")
private fun inspectAndroidKey(configuration: AndroidKeyStoreConfiguration, kind: AndroidKeyStoreKeyKind, key: SecretKey): AndroidKeyStoreObservedKey {
    requireSupportedAndroidKeyProfile(configuration)
    if (key.algorithm != algorithm(kind)) androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_MISMATCH)
    // This asks for OS characteristics, NEVER key.encoded/SecretKeySpec/exported key material.
    val info = SecretKeyFactory.getInstance(algorithm(kind), "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java) as KeyInfo
    if (info.keystoreAlias != alias(configuration, kind) || info.keySize != 256 ||
        info.purposes != purposes(kind) || info.origin != KeyProperties.ORIGIN_GENERATED ||
        AndroidNativeKeyInfo.hasServiceRestrictions(info) ||
        info.signaturePaddings.isNotEmpty()) {
        androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_MISMATCH)
    }
    when (kind) {
        AndroidKeyStoreKeyKind.STORAGE -> if (info.blockModes.toSet() != setOf(KeyProperties.BLOCK_MODE_GCM) ||
            info.encryptionPaddings.toSet() != setOf(KeyProperties.ENCRYPTION_PADDING_NONE) || info.digests.isNotEmpty()) {
            androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_MISMATCH)
        }
        AndroidKeyStoreKeyKind.PEPPER -> if (info.digests.toSet() != setOf(KeyProperties.DIGEST_SHA256) ||
            info.blockModes.isNotEmpty() || info.encryptionPaddings.isNotEmpty()) {
            androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_MISMATCH)
        }
    }
    val native = try { AndroidNativeKeyInfo.observe(info) }
        catch (_: Exception) { androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_UNSUPPORTED) }
    if (!AndroidNativeKeyPolicy.permits(configuration.protectionRequirement.selfOwnedProtection(), native)) {
        androidKeyStoreFail(AndroidKeyStoreFailure.PROFILE_MISMATCH)
    }
    val security = when (native) {
        AndroidNativeKeySecurity.SOFTWARE -> AndroidKeyStoreObservedSecurity.SOFTWARE
        AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT -> AndroidKeyStoreObservedSecurity.TRUSTED_ENVIRONMENT
        AndroidNativeKeySecurity.STRONGBOX -> AndroidKeyStoreObservedSecurity.STRONGBOX
        AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED -> AndroidKeyStoreObservedSecurity.HARDWARE_KIND_UNSPECIFIED
    }
    return AndroidKeyStoreObservedKey(security)
}

private fun alias(configuration: AndroidKeyStoreConfiguration, kind: AndroidKeyStoreKeyKind): String = when (kind) {
    AndroidKeyStoreKeyKind.STORAGE -> configuration.storageAlias
    AndroidKeyStoreKeyKind.PEPPER -> configuration.pepperAlias
}

private fun algorithm(kind: AndroidKeyStoreKeyKind): String = when (kind) {
    AndroidKeyStoreKeyKind.STORAGE -> KeyProperties.KEY_ALGORITHM_AES
    AndroidKeyStoreKeyKind.PEPPER -> KeyProperties.KEY_ALGORITHM_HMAC_SHA256
}

private fun purposes(kind: AndroidKeyStoreKeyKind): Int = when (kind) {
    AndroidKeyStoreKeyKind.STORAGE -> KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    AndroidKeyStoreKeyKind.PEPPER -> KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
}

private class AndroidKeyStoreAccessFailure(val reason: AndroidKeyStoreFailure) : Exception(reason.name)

internal fun androidKeyStoreFail(reason: AndroidKeyStoreFailure): Nothing = throw AndroidKeyStoreAccessFailure(reason)


/**
 * P2-only concrete per-key preparation under the SAME watched original target owner.
 * This synchronous helper NEVER enters AndroidKeyStoreNativeWork/lifecycleSlot or exports an
 * accepting DeviceProtected adapter. Existing material/V1/V2 behavior above is unchanged.
 */
internal class AndroidSelfOwnedDataKeyMaterial(
    private val environment: AndroidSelfOwnedEnvironment,
    private val journal: AndroidSelfOwnedInstallationJournal,
    private val work: AndroidOwnedInstallationWork,
) {
    private val store = androidKeyStore()

    fun prepareOriginalDataKey(role: SelfOwnedTargetKeyRoleV1) {
        val kind = kind(role)
        var original = journal.intent()
        var intent = original.key(role)
        if (intent.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) { checkedKey(role); return }
        if (intent.phase == SelfOwnedKeyIntentPhaseV1.STARTED && environment.bootCount <= requireNotNull(intent.startedBoot)) {
            // No same-boot replay/adoption of a possibly still completing service generation.
            keyFailure()
        }
        guard()
        val configuration = configuration(original)
        requireSupportedAndroidKeyProfile(configuration)
        val present = store.containsAlias(alias(configuration, kind))
        if (intent.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED && present) keyFailure()
        if (!present) {
            val started = intent.copy(phase = SelfOwnedKeyIntentPhaseV1.STARTED, startedBoot = environment.bootCount,
                security = null, proof = SelfOwnedOctetsV1(ByteArray(0)))
            journal.retainIntent(original.replaceKey(started))
            original = journal.intent(); intent = original.key(role)
            guard()
            if (store.containsAlias(alias(configuration, kind))) keyFailure()
            try { generateAndroidKey(configuration, kind) } catch (_: Exception) { keyFailure() }
        }
        val native = load(configuration, kind)
        val level = inspect(configuration, kind, native)
        val proof = createProof(original, role, native)
        try {
            guard()
            val after = load(configuration, kind)
            if (inspect(configuration, kind, after) != level) keyFailure()
            val complete = intent.copy(phase = SelfOwnedKeyIntentPhaseV1.COMPLETE, security = level, proof = SelfOwnedOctetsV1(proof))
            verifyProof(original, complete, after)
            journal.retainIntent(original.replaceKey(complete))
        } finally { proof.fill(0) }
        checkedKey(role)
    }

    fun assertOriginalDataKeys() {
        checkedKey(SelfOwnedTargetKeyRoleV1.STORAGE)
        checkedKey(SelfOwnedTargetKeyRoleV1.PEPPER)
        guard()
    }

    /** Journal decryption proves STORAGE itself on both sides of its actual use. */
    fun assertOriginalPepperKey() {
        checkedKey(SelfOwnedTargetKeyRoleV1.PEPPER)
        guard()
    }

    /** Native handles remain inside the concrete owner; never encoded/exported or returned to UI. */
    fun storageKey(): SecretKey = checkedKey(SelfOwnedTargetKeyRoleV1.STORAGE)
    fun pepperKey(): SecretKey = checkedKey(SelfOwnedTargetKeyRoleV1.PEPPER)

    fun configuration(): AndroidKeyStoreConfiguration { guard(); return configuration(journal.intent()) }

    fun sealJournal(aad: ByteArray, plaintext: ByteArray): AndroidSelfOwnedCiphertext {
        requireJournalAad(aad)
        if (plaintext.size !in 1..SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_PLAINTEXT_BYTES) keyFailure()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, storageKey())
        cipher.updateAAD(aad)
        val encrypted = cipher.doFinal(plaintext)
        val nonce = cipher.iv
        try {
            if (nonce == null || nonce.size != 12 || encrypted.size != plaintext.size + 16) keyFailure()
            val parameters = cipher.parameters?.getParameterSpec(javax.crypto.spec.GCMParameterSpec::class.java) ?: keyFailure()
            if (parameters.tLen != 128 || !parameters.iv.contentEquals(nonce)) keyFailure()
            storageKey()
            return AndroidSelfOwnedCiphertext(nonce.copyOf(), encrypted.copyOf())
        } finally { nonce?.fill(0); encrypted.fill(0) }
    }

    fun openJournal(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
        requireJournalAad(aad)
        if (nonce.size != 12 || ciphertext.size !in 17..SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_PLAINTEXT_BYTES + 16) keyFailure()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, storageKey(), javax.crypto.spec.GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(ciphertext)
        try {
            if (plaintext.size !in 1..SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_PLAINTEXT_BYTES ||
                plaintext.size + 16 != ciphertext.size) keyFailure()
            storageKey()
            return plaintext
        } catch (failure: Throwable) { plaintext.fill(0); throw failure }
    }

    private fun requireJournalAad(supplied: ByteArray) {
        val expected = SelfOwnedNativeInstallationWireV1.stateAad(journal.intent())
        try { if (!expected.contentEquals(supplied)) keyFailure() } finally { expected.fill(0) }
        guard()
    }

    private fun checkedKey(role: SelfOwnedTargetKeyRoleV1): SecretKey {
        guard()
        val original = journal.intent(); val intent = original.key(role)
        if (intent.phase != SelfOwnedKeyIntentPhaseV1.COMPLETE) keyFailure()
        val configuration = configuration(original); val kind = kind(role)
        val key = load(configuration, kind)
        if (inspect(configuration, kind, key, intent.security) != intent.security) keyFailure()
        verifyProof(original, intent, key)
        guard()
        if (inspect(configuration, kind, load(configuration, kind), intent.security) != intent.security) keyFailure()
        return key
    }

    private fun load(configuration: AndroidKeyStoreConfiguration, kind: AndroidKeyStoreKeyKind): SecretKey =
        try { loadExistingAndroidKey(store, configuration, kind) } catch (_: AndroidKeyStoreAccessFailure) { keyFailure() }

    private fun inspect(configuration: AndroidKeyStoreConfiguration, kind: AndroidKeyStoreKeyKind,
        key: SecretKey, retained: SelfOwnedNativeSecurityV1? = null): SelfOwnedNativeSecurityV1 {
        val observed = try { inspectAndroidKey(configuration, kind, key) }
        catch (_: AndroidKeyStoreAccessFailure) { keyFailure() }
        // Current policy/restrictions are checked above. Only the original generic hardware
        // witness may retain its original granularity; the checked caller still proves the
        // exact original key with its retained AES/HMAC possession proof before returning it.
        val current = when (observed.security) {
            AndroidKeyStoreObservedSecurity.SOFTWARE -> SelfOwnedNativeSecurityV1.SOFTWARE
            AndroidKeyStoreObservedSecurity.TRUSTED_ENVIRONMENT -> SelfOwnedNativeSecurityV1.TRUSTED_ENVIRONMENT
            AndroidKeyStoreObservedSecurity.STRONGBOX -> SelfOwnedNativeSecurityV1.STRONGBOX
            AndroidKeyStoreObservedSecurity.HARDWARE_KIND_UNSPECIFIED -> SelfOwnedNativeSecurityV1.HARDWARE_KIND_UNSPECIFIED
        }
        return current.originalWitness(retained)
    }

    private fun configuration(original: SelfOwnedNativeIntentV1): AndroidKeyStoreConfiguration {
        val coordinates = original.source.preparationBody.coordinates
        val requirement = when (original.source.preparationBody.protection) {
            SelfOwnedKeyProtectionV1.OS_ISOLATED -> AndroidKeyStoreProtectionRequirement.ANDROID_KEYSTORE
            SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> AndroidKeyStoreProtectionRequirement.SECURE_HARDWARE
            SelfOwnedKeyProtectionV1.STRONGBOX -> AndroidKeyStoreProtectionRequirement.STRONGBOX
        }
        return AndroidKeyStoreConfiguration(coordinates.installationId, coordinates.scope,
            ProtectedStorageKeyReference(coordinates.storageKeyId), ProtectedPepperReference(coordinates.pepperKeyId), requirement,
            aliasPrefix = environment.profile.keystoreAliasPrefix)
    }

    private fun createProof(original: SelfOwnedNativeIntentV1, role: SelfOwnedTargetKeyRoleV1, key: SecretKey): ByteArray {
        val binding = SelfOwnedNativeInstallationWireV1.keyBinding(original, role)
        return try {
            when (role) {
                SelfOwnedTargetKeyRoleV1.STORAGE -> {
                    val expected = AndroidP256Primitives.hash(binding)
                    val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                    val encrypted = try {
                        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key); cipher.updateAAD(binding); cipher.doFinal(expected)
                    } finally { expected.fill(0) }
                    val nonce = cipher.iv
                    try {
                        if (nonce == null || nonce.size != 12 || encrypted.size != 48) keyFailure()
                        byteArrayOf(1) + nonce + encrypted
                    } finally { nonce?.fill(0); encrypted.fill(0) }
                }
                SelfOwnedTargetKeyRoleV1.PEPPER -> hmac(key, binding)
                SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> keyFailure()
            }
        } finally { binding.fill(0) }
    }

    private fun verifyProof(original: SelfOwnedNativeIntentV1, intent: SelfOwnedTargetKeyIntentV1, key: SecretKey) {
        val binding = SelfOwnedNativeInstallationWireV1.keyBinding(original, intent.role)
        val proof = intent.proof.copyBytes()
        try {
            when (intent.role) {
                SelfOwnedTargetKeyRoleV1.STORAGE -> {
                    if (proof.size != 61 || proof[0].toInt() != 1) keyFailure()
                    val nonce = proof.copyOfRange(1, 13); val encrypted = proof.copyOfRange(13, 61)
                    val expected = AndroidP256Primitives.hash(binding)
                    try {
                        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
                        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, nonce))
                        cipher.updateAAD(binding)
                        val actual = cipher.doFinal(encrypted)
                        try { if (!AndroidP256Primitives.same(expected, actual)) keyFailure() } finally { actual.fill(0) }
                    } finally { nonce.fill(0); encrypted.fill(0); expected.fill(0) }
                }
                SelfOwnedTargetKeyRoleV1.PEPPER -> {
                    val actual = hmac(key, binding)
                    try { if (!AndroidP256Primitives.same(proof, actual)) keyFailure() } finally { actual.fill(0) }
                }
                SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> keyFailure()
            }
        } finally { binding.fill(0); proof.fill(0) }
    }

    private fun hmac(key: SecretKey, bytes: ByteArray): ByteArray = javax.crypto.Mac.getInstance("HmacSHA256").run {
        init(key); doFinal(bytes).also { if (it.size != 32) { it.fill(0); keyFailure() } }
    }
    private fun kind(role: SelfOwnedTargetKeyRoleV1): AndroidKeyStoreKeyKind = when (role) {
        SelfOwnedTargetKeyRoleV1.STORAGE -> AndroidKeyStoreKeyKind.STORAGE
        SelfOwnedTargetKeyRoleV1.PEPPER -> AndroidKeyStoreKeyKind.PEPPER
        SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> keyFailure()
    }
    private fun guard() { work.requireActive(); environment.requireCurrent(); journal.intent(); work.requireActive() }
    private fun keyFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
}
