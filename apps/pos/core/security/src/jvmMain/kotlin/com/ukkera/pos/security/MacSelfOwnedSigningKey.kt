package com.ukkera.pos.security

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import com.ukkera.pos.types.DomainId

/**
 * Exact composition namespace for one macOS original-context signing key. The profile label is
 * fixed from the shared LIVE/TRAINING_1/TRAINING_2 choice; a caller cannot supply a path, alias,
 * keychain label or arbitrary profile string. The root ID and signing-key ID remain part of the
 * permanent item tag, so a key for one original context can never be reopened as another.
 */
internal data class MacSelfOwnedSigningKeyBinding(
    val mode: LocalSetupCompositionMode,
    val trainingSlot: LocalTrainingCompositionSlot?,
    val rootId: DomainId,
    val signingKeyId: DomainId,
) : MacSigningKeyTag {
    init {
        require(
            (mode == LocalSetupCompositionMode.LIVE && trainingSlot == null) ||
                (mode == LocalSetupCompositionMode.TRAINING && trainingSlot != null),
        ) { "macOS signing-key binding requires exactly LIVE or a training slot" }
    }

    override fun applicationTag(): ByteArray {
        val label = when (mode) {
            LocalSetupCompositionMode.LIVE -> "live"
            LocalSetupCompositionMode.TRAINING -> when (requireNotNull(trainingSlot)) {
                LocalTrainingCompositionSlot.TRAINING_1 -> "training-1"
                LocalTrainingCompositionSlot.TRAINING_2 -> "training-2"
            }
        }
        val text = "$TAG_PREFIX/$label/${rootId.value}/${signingKeyId.value}"
        require(text.length in 1..TAG_MAXIMUM_CHARACTERS && text.all { it.code in 0x20..0x7e }) {
            "macOS signing-key tag is outside its fixed ASCII namespace"
        }
        return text.toByteArray(Charsets.US_ASCII)
    }

    override fun toString(): String = "MacSelfOwnedSigningKeyBinding([REDACTED])"

    private companion object {
        const val TAG_PREFIX = "com.ukkera.pos.macos.self-owned.signing-key.v1"
        const val TAG_MAXIMUM_CHARACTERS = 255
    }
}

/** Permanent Keychain application tag of one signing key. */
internal sealed interface MacSigningKeyTag {
    fun applicationTag(): ByteArray
}

/** Desktop Master custody tag: fixed prefix plus the shared-core derived alias. */
internal class MacDesktopAliasTag(private val alias: String) : MacSigningKeyTag {
    init { require(alias.length in 1..200 && alias.all { it.code in 0x21..0x7e }) }
    override fun applicationTag(): ByteArray = "com.ukkera.pos.macos.desktop-master.p256.v2/$alias".toByteArray(Charsets.US_ASCII)
    override fun toString(): String = "MacDesktopAliasTag([REDACTED])"
}

/** Actual observed custody level, never inferred from the requested policy. */
internal enum class MacSelfOwnedSigningKeySecurity {
    /** A software P-256 key protected by the data-protection Keychain. No hardware claim. */
    DATA_PROTECTION_KEYCHAIN_SOFTWARE,

    /** A P-256 key whose private operations stay in the Secure Enclave. */
    SECURE_ENCLAVE,
}

/**
 * Original-context P-256 signing-key custody in the macOS data-protection Keychain.
 *
 * This is a native prerequisite, not an installed-application, company, employee, session,
 * device, SDK or Room authority. It deliberately exposes no raw key bytes, PKCS#12 import,
 * adjacent file key, caller signing callback, arbitrary message signer or accepting fallback.
 * A generic JVM launch, unsigned host or missing default Keychain access group fails closed;
 * there is no legacy file-keychain or adjacent-file fallback.
 *
 * The native owner remains responsible for durable forced intent, original P1/root-origin
 * verification, journal/binding sequencing and serialized lifetime across processes. This class
 * does not create or attest an intent record: [createAfterForcedIntent] must only be called after
 * the owner has durably recorded its one-way intent, and [reopenExistingOnly] is the recovery
 * path that can never mint a replacement. Neither method proves the item belongs to the original
 * installation by itself; the owner must join it to the retained original binding and native
 * namespace.
 *
 * Keys are permanent P-256 private keys in the data-protection Keychain, selected by the fixed
 * [MacSelfOwnedSigningKeyBinding] tag. `OS_ISOLATED` creates a non-extractable software key with
 * the unlocked-this-device accessibility class and no private-key-usage constraint, which macOS
 * can reject outside the Secure Enclave. `SECURE_HARDWARE` adds the Secure Enclave token and the
 * private-key-usage access control, and fails instead of falling back. `STRONGBOX` is not a macOS
 * facility and is refused. Reopen verifies the exact key class/type/size/token, tag,
 * extractability, public point and actual sign/verify possession. A policy or attribute mismatch
 * fails closed as `KEY_STATE_UNCERTAIN`.
 *
 * Signing methods exist only for the existing shared fixed original-context domains. There is no
 * `sign(message)` surface. The owning native layer must still validate the retained purpose/AAD,
 * origin and one-use intent before invoking them.
 */
internal class MacSelfOwnedSigningKey private constructor(
    val binding: MacSigningKeyTag,
    val protection: SelfOwnedKeyProtectionV1,
    val security: MacSelfOwnedSigningKeySecurity,
    private var privateItem: Item?,
    private var publicKey: Pointer?,
    private val point: ByteArray,
) : AutoCloseable {
    private val lock = Any()
    private var closed = false
    private var closeConfirmed = false

    init {
        require(point.size == 65 && point[0].toInt() == 4)
        require(
            (protection == SelfOwnedKeyProtectionV1.OS_ISOLATED &&
                security == MacSelfOwnedSigningKeySecurity.DATA_PROTECTION_KEYCHAIN_SOFTWARE) ||
                (protection == SelfOwnedKeyProtectionV1.SECURE_HARDWARE &&
                    security == MacSelfOwnedSigningKeySecurity.SECURE_ENCLAVE),
        )
    }

    /** A defensive copy; the retained point is cleared when this custody handle closes. */
    internal fun copyPublicPoint(): ByteArray = synchronized(lock) {
        check(!closed) { "macOS signing key is closed" }
        point.copyOf()
    }

    /** The exact permanent tag for this root/key/profile; DATA only, not an authority claim. */
    internal fun copyApplicationTag(): ByteArray = binding.applicationTag()

    private val original: MacSelfOwnedSigningKeyBinding
        get() = binding as? MacSelfOwnedSigningKeyBinding ?: error("macOS signing key is not an original-context key")

    /** Desktop-alias custody: raw r||s over [message], natively and point verified. */
    internal fun signDesktop(message: ByteArray): ByteArray {
        check(binding is MacDesktopAliasTag)
        return signFixed(message)
    }

    /**
     * Sign one bounded root/issuer/native-preparation document with the shared V1 role frame.
     * The owner remains responsible for choosing the correct original role and retained body.
     */
    internal fun signOriginalDocument(
        role: SelfOwnedDocumentRoleV1,
        body: ByteArray,
    ): SelfOwnedSignedDocumentV1 {
        require(body.size in 1..(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES - 512))
        when (role) {
            SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT -> {
                val root = SelfOwnedInstallationWireV1.decodeRoot(body)
                require(root.rootId == original.rootId && root.rootKeyId == original.signingKeyId && root.rootPublicKey.same(point))
            }
            SelfOwnedDocumentRoleV1.ISSUER_DELEGATION ->
                require(SelfOwnedInstallationWireV1.decodeDelegation(body).rootId == original.rootId)
            SelfOwnedDocumentRoleV1.NATIVE_PREPARATION -> error("macOS preparation requires the V2 installed-target domain")
        }
        val message = SelfOwnedInstallationWireV1.signingMessage(role, body)
        val signature = try { signFixed(message) } finally { message.fill(0) }
        return try { SelfOwnedSignedDocumentV1(role, body, signature) } finally { signature.fill(0) }
    }

    /** The additive native preparation uses its own V2 domain; Android preparation is unchanged. */
    internal fun signOriginalPreparation(
        preparation: SelfOwnedInstalledIdentityPreparationV2,
    ): SelfOwnedInstalledIdentitySignedDocumentV2 {
        require(preparation.target.kind.platform == SelfOwnedInstalledIdentityPlatformV2.MACOS && preparation.rootId == original.rootId)
        val message = SelfOwnedInstalledIdentityWireV2.signingMessage(preparation)
        val signature = try { signFixed(message) } finally { message.fill(0) }
        return try {
            SelfOwnedInstalledIdentitySignedDocumentV2(SelfOwnedInstalledIdentitySignedBodyV2(preparation), signature)
        } finally { signature.fill(0) }
    }

    /** Verify a document against this exact retained public point; no custody or trust inference. */
    internal fun verifyOriginalDocument(document: SelfOwnedSignedDocumentV1): Boolean {
        val body = document.body()
        val signature = document.signature()
        val message = try {
            SelfOwnedInstallationWireV1.signingMessage(document.role, body)
        } finally {
            body.fill(0)
        }
        return try {
            verifyFixed(message, signature)
        } finally {
            message.fill(0)
            signature.fill(0)
        }
    }

    /** Sign the shared native-binding proof frame. The body must be the owner's actual binding body. */
    internal fun signOriginalBinding(body: ByteArray): ByteArray {
        require(body.size in 1..(SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BYTES - 512))
        val decoded = SelfOwnedNativeInstallationWireV2.decodeBindingBody(body)
        require(decoded is SelfOwnedInstalledIdentityResultV2.Data)
        require(decoded.value.namespace.platform == SelfOwnedNativePlatformV2.MACOS &&
            decoded.value.coordinates.targetSigningKeyId == original.signingKeyId &&
            decoded.value.targetPublicKey.same(point))
        val message = SelfOwnedNativeInstallationWireV2.bindingSigningMessage(body)
        return try { signFixed(message) } finally { message.fill(0) }
    }

    /** Verify a native-binding proof against this exact retained public point. */
    internal fun verifyOriginalBinding(body: ByteArray, signature: ByteArray): Boolean {
        require(body.size in 1..(SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BYTES - 512))
        require(signature.size == 64)
        val message = SelfOwnedNativeInstallationWireV2.bindingSigningMessage(body)
        return try { verifyFixed(message, signature) } finally { message.fill(0) }
    }

    /** Sign the shared native-journal state frame; the owner must supply its retained journal AAD. */
    internal fun signOriginalJournalState(
        aad: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
    ): ByteArray {
        require(aad.size in 1..SelfOwnedNativeIdentityProfileV2.MAXIMUM_CONTROL_FRAME_BYTES)
        require(nonce.size == 12)
        require(ciphertext.size in 17..(SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_PLAINTEXT_BYTES + 16))
        val message = SelfOwnedNativeInstallationWireV2.stateSignature(aad, nonce, ciphertext)
        return try { signFixed(message) } finally { message.fill(0) }
    }

    /** Verify a native-journal state frame against this exact retained public point. */
    internal fun verifyOriginalJournalState(
        aad: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        signature: ByteArray,
    ): Boolean {
        require(aad.size in 1..SelfOwnedNativeIdentityProfileV2.MAXIMUM_CONTROL_FRAME_BYTES)
        require(nonce.size == 12)
        require(ciphertext.size in 17..(SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_PLAINTEXT_BYTES + 16))
        require(signature.size == 64)
        val message = SelfOwnedNativeInstallationWireV2.stateSignature(aad, nonce, ciphertext)
        return try { verifyFixed(message, signature) } finally { message.fill(0) }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) {
                check(closeConfirmed) { "macOS signing-key reference close is unresolved" }
                return
            }
            // Deny before native release. A throwing release is never retried or
            // treated as confirmed closure; independent references still get one attempt.
            closed = true
            closeSigningReferences(publicKey, privateItem, point)
            publicKey = null
            privateItem = null
            closeConfirmed = true
        }
    }

    override fun toString(): String = "MacSelfOwnedSigningKey([REDACTED])"

    private fun signFixed(message: ByteArray): ByteArray = synchronized(lock) {
        checkOpen()
        val tag = binding.applicationTag()
        try {
            val signature = withCurrentKey(tag) { item, public ->
                val algorithm = algorithm()
                val messageData = cfData(message)
                try {
                    val signatureData = checkNotNull(
                        keyNative.security.SecKeyCreateSignature(item.privateKey, algorithm, messageData, null),
                    ) { "macOS signing-key operation failed" }
                    try {
                        check(MacSecurityNative.cf.CFGetTypeID(signatureData) ==
                            MacSecurityNative.cf.CFDataGetTypeID()) { "macOS signing-key result is not data" }
                        val length = MacSecurityNative.cf.CFDataGetLength(signatureData).toLong()
                        check(length in 1..72) { "macOS signing-key signature length is invalid" }
                        val der = checkNotNull(MacSecurityNative.cf.CFDataGetBytePtr(signatureData))
                            .getByteArray(0, length.toInt())
                        val raw = try {
                            JvmP256Primitives.fromDer(der).also { check(it.size == 64) }
                        } finally {
                            der.fill(0)
                        }
                        try {
                            check(keyNative.security.SecKeyVerifySignature(
                                public, algorithm, messageData, signatureData, null,
                            ) != 0.toByte()) { "macOS signing-key native verification failed" }
                            check(JvmP256Primitives.verify(point, message, raw)) {
                                "macOS signing-key public-point verification failed"
                            }
                        } catch (failure: Throwable) {
                            raw.fill(0)
                            throw failure
                        }
                        raw
                    } finally {
                        MacSecurityNative.cf.CFRelease(signatureData)
                    }
                } finally {
                    MacSecurityNative.cf.CFRelease(messageData)
                }
            }
            try {
                verifyCurrent(tag)
                signature
            } catch (failure: Throwable) {
                signature.fill(0)
                throw failure
            }
        } finally {
            tag.fill(0)
        }
    }

    private fun verifyFixed(message: ByteArray, signature: ByteArray): Boolean = synchronized(lock) {
        checkOpen()
        JvmP256Primitives.verify(point, message, signature)
    }

    private fun verifyCurrent(tag: ByteArray) {
        withCurrentKey(tag) { _, _ -> Unit }
    }

    /** One fixed native-local possession challenge, never caller-selected signing input. */
    private fun requirePossession() {
        val tag = binding.applicationTag()
        val nonce = JvmP256Primitives.randomBytes(32)
        val message = try {
            selfOwnedEncode("macos-self-owned-signing-key-possession/v1", 1024) {
                field(tag)
                field(nonce)
            }
        } finally {
            nonce.fill(0)
            tag.fill(0)
        }
        val signature = try {
            signFixed(message)
        } finally {
            message.fill(0)
        }
        signature.fill(0)
    }

    /**
     * Reopen the exact permanent item and verify its current attributes and point before use.
     * The retained handle is not trusted as a substitute for current Keychain state.
     */
    private inline fun <T> withCurrentKey(tag: ByteArray, block: (Item, Pointer) -> T): T {
        val loaded = loadVerified(tag, protection, point)
        return try {
            block(loaded.item, loaded.publicKey)
        } finally {
            loaded.close()
        }
    }

    private fun checkOpen() {
        check(!closed && privateItem != null && publicKey != null) { "macOS signing key is closed" }
    }

    internal companion object {
        /**
         * Generate one permanent key only after the native owner has durably recorded its forced
         * one-way intent. A pre-existing tagged item is never adopted as a new creation.
         */
        internal fun createAfterForcedIntent(
            binding: MacSigningKeyTag,
            protection: SelfOwnedKeyProtectionV1,
        ): MacSelfOwnedSigningKey = guarded {
            requireMacPlatform()
            requireSupportedPolicy(protection)
            val tag = binding.applicationTag()
            try {
                requireAbsent(tag)
                val generated = generate(tag, protection)
                val generatedPoint = try {
                    pointOfPrivate(generated)
                } finally {
                    MacSecurityNative.cf.CFRelease(generated)
                }
                try {
                    val key = openExistingInternal(binding, protection, generatedPoint)
                    try {
                        if (!key.point.contentEquals(generatedPoint)) keyFailureState()
                        return@guarded key
                    } catch (failure: Throwable) {
                        key.close()
                        throw failure
                    }
                } finally {
                    generatedPoint.fill(0)
                }
            } finally {
                tag.fill(0)
            }
        }

        /**
         * Existing-only recovery. Missing, foreign, downgraded, replaced or non-possessed items
         * fail closed; this path never generates, replaces or deletes a key.
         */
        internal fun reopenExistingOnly(
            binding: MacSigningKeyTag,
            protection: SelfOwnedKeyProtectionV1,
            originalPublicPoint: ByteArray,
        ): MacSelfOwnedSigningKey = guarded {
            requireMacPlatform()
            requireSupportedPolicy(protection)
            require(originalPublicPoint.size == 65)
            JvmP256Primitives.publicKey(originalPublicPoint)
            openExistingInternal(binding, protection, originalPublicPoint)
        }

        private fun openExistingInternal(
            binding: MacSigningKeyTag,
            protection: SelfOwnedKeyProtectionV1,
            originalPublicPoint: ByteArray?,
        ): MacSelfOwnedSigningKey {
            val tag = binding.applicationTag()
            try {
                var loaded: Loaded? = loadVerified(tag, protection, originalPublicPoint)
                try {
                    val current = checkNotNull(loaded)
                    val observed = securityFor(protection)
                    val key = MacSelfOwnedSigningKey(
                        binding, protection, observed,
                        current.item, current.publicKey, current.point,
                    )
                    loaded = null
                    try {
                        key.requirePossession()
                        return key
                    } catch (failure: Throwable) {
                        key.close()
                        throw failure
                    }
                } finally {
                    loaded?.close()
                }
            } finally {
                tag.fill(0)
            }
        }

        /** Existing-only desktop-alias key; the shared core compares its point with the retained proof. */
        internal fun openDesktop(alias: MacDesktopAliasTag, protection: SelfOwnedKeyProtectionV1): MacSelfOwnedSigningKey = guarded {
            requireMacPlatform()
            requireSupportedPolicy(protection)
            openExistingInternal(alias, protection, null)
        }

        /** Existing-only presence of a desktop-alias key; native errors other than absence fail. */
        internal fun desktopExists(alias: MacDesktopAliasTag): Boolean {
            requireMacPlatform()
            val tag = alias.applicationTag()
            try {
                val item = loadItem(tag) ?: return false
                item.close()
                return true
            } finally { tag.fill(0) }
        }

        private fun requireAbsent(tag: ByteArray) {
            val existing = loadItem(tag)
            if (existing != null) {
                existing.close()
                keyFailureState()
            }
        }

        private fun loadVerified(
            tag: ByteArray,
            protection: SelfOwnedKeyProtectionV1,
            expectedPoint: ByteArray?,
        ): Loaded {
            val opened = loadItem(tag) ?: keyFailureState()
            var item: Item? = opened
            var publicKey: Pointer? = null
            var point: ByteArray? = null
            var transferred = false
            try {
                verifyItemAttributes(opened.dictionary, tag, protection)
                verifyPrivateAttributes(opened.privateKey, protection)
                requirePrivateNotExportable(opened.privateKey)
                val openedPublic = checkNotNull(keyNative.security.SecKeyCopyPublicKey(opened.privateKey)) {
                    "macOS signing-key public point is unavailable"
                }
                publicKey = openedPublic
                verifyPublicAttributes(openedPublic)
                val currentPoint = copyPublicPoint(openedPublic)
                point = currentPoint
                if (expectedPoint != null && !currentPoint.contentEquals(expectedPoint)) {
                    keyFailureState()
                }
                val result = Loaded(opened, openedPublic, currentPoint)
                transferred = true
                return result
            } finally {
                if (!transferred) {
                    closeSigningReferences(publicKey, item, point ?: ByteArray(0))
                }
            }
        }

        private fun generate(tag: ByteArray, protection: SelfOwnedKeyProtectionV1): Pointer {
            Query().use { parameters ->
                parameters.put("kSecAttrKeyType", MacSecurityNative.constant("kSecAttrKeyTypeECSECPrimeRandom"))
                parameters.putNumber("kSecAttrKeySizeInBits", 256)
                parameters.putBoolean("kSecUseDataProtectionKeychain", true)
                if (protection == SelfOwnedKeyProtectionV1.SECURE_HARDWARE) {
                    parameters.put("kSecAttrTokenID",
                        MacSecurityNative.constant("kSecAttrTokenIDSecureEnclave"))
                }
                val privateAttributes = Query()
                try {
                    privateAttributes.putBoolean("kSecAttrIsPermanent", true)
                    privateAttributes.putData("kSecAttrApplicationTag", tag)
                    when (protection) {
                        SelfOwnedKeyProtectionV1.OS_ISOLATED -> {
                            // privateKeyUsage is a Secure Enclave constraint. Applying it to a
                            // software fallback can fail with errSecAuthFailed on macOS hosts
                            // without the Secure Enclave, so OS_ISOLATED uses its protection class.
                            privateAttributes.put("kSecAttrAccessible",
                                MacSecurityNative.constant("kSecAttrAccessibleWhenUnlockedThisDeviceOnly"))
                            privateAttributes.putBoolean("kSecAttrIsExtractable", false)
                        }
                        SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> {
                            val access = checkNotNull(keyNative.security.SecAccessControlCreateWithFlags(
                                null,
                                MacSecurityNative.constant("kSecAttrAccessibleWhenUnlockedThisDeviceOnly"),
                                NativeLong(1L shl 30),
                                null,
                            )) { "macOS signing-key access control is unavailable" }
                            privateAttributes.putOwned("kSecAttrAccessControl", access)
                        }
                        SelfOwnedKeyProtectionV1.STRONGBOX ->
                            selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
                    }
                    parameters.put("kSecPrivateKeyAttrs", privateAttributes.dictionary)
                } finally {
                    privateAttributes.close()
                }
                val generated = keyNative.security.SecKeyCreateRandomKey(parameters.dictionary, null)
                if (generated == null) {
                    if (protection == SelfOwnedKeyProtectionV1.SECURE_HARDWARE) {
                        selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
                    }
                    keyFailureState()
                }
                return generated
            }
        }

        private fun loadItem(tag: ByteArray): Item? = Query().use { query ->
            query.put("kSecClass", MacSecurityNative.constant("kSecClassKey"))
            query.put("kSecAttrKeyType", MacSecurityNative.constant("kSecAttrKeyTypeECSECPrimeRandom"))
            query.put("kSecAttrKeyClass", MacSecurityNative.constant("kSecAttrKeyClassPrivate"))
            query.putData("kSecAttrApplicationTag", tag)
            query.putBoolean("kSecReturnRef", true)
            query.putBoolean("kSecReturnAttributes", true)
            query.put("kSecMatchLimit", MacSecurityNative.constant("kSecMatchLimitOne"))
            query.putBoolean("kSecUseDataProtectionKeychain", true)
            query.putBoolean("kSecAttrSynchronizable", false)
            query.put("kSecUseAuthenticationUI", MacSecurityNative.constant("kSecUseAuthenticationUIFail"))
            val result = PointerByReference()
            val status = MacSecurityNative.security.SecItemCopyMatching(query.dictionary, result)
            val value = result.value
            if (status == NOT_FOUND) {
                if (value != null) MacSecurityNative.cf.CFRelease(value)
                return null
            }
            if (status != SUCCESS) {
                if (value != null) MacSecurityNative.cf.CFRelease(value)
                keyFailureState()
            }
            check(value != null) { "macOS signing-key query returned no attributes" }
            try {
                check(MacSecurityNative.cf.CFGetTypeID(value) ==
                    MacSecurityNative.cf.CFDictionaryGetTypeID()) {
                    "macOS signing-key query result is not a dictionary"
                }
                return Item(value)
            } catch (failure: Throwable) {
                MacSecurityNative.cf.CFRelease(value)
                throw failure
            }
        }

        private fun verifyItemAttributes(
            dictionary: Pointer,
            tag: ByteArray,
            protection: SelfOwnedKeyProtectionV1,
        ) {
            requireData(requireValue(dictionary, "kSecAttrApplicationTag"),
                tag, "macOS signing-key tag differs")
            requireOptionalBoolean(dictionary, "kSecAttrIsPermanent", true)
            requireOptionalBoolean(dictionary, "kSecAttrSynchronizable", false)
            when (protection) {
                SelfOwnedKeyProtectionV1.OS_ISOLATED -> {
                    requireOptionalBoolean(dictionary, "kSecAttrIsExtractable", false)
                    requireToken(dictionary, null)
                    val accessible = requireValue(dictionary, "kSecAttrAccessible")
                    requireString(accessible,
                        MacSecurityNative.constant("kSecAttrAccessibleWhenUnlockedThisDeviceOnly"))
                    check(requireValueOrNull(dictionary, "kSecAttrAccessControl") == null) {
                        "macOS OS_ISOLATED key unexpectedly uses an access-control constraint"
                    }
                }
                SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> {
                    requireOptionalBoolean(dictionary, "kSecAttrIsExtractable", false)
                    requireToken(dictionary, MacSecurityNative.constant("kSecAttrTokenIDSecureEnclave"))
                    val accessControl = requireValue(dictionary, "kSecAttrAccessControl")
                    check(MacSecurityNative.cf.CFGetTypeID(accessControl) ==
                        keyNative.security.SecAccessControlGetTypeID()) {
                        "macOS signing-key access control type is invalid"
                    }
                    requireValueOrNull(dictionary, "kSecAttrAccessible")?.let {
                        requireString(it,
                            MacSecurityNative.constant("kSecAttrAccessibleWhenUnlockedThisDeviceOnly"))
                    }
                }
                SelfOwnedKeyProtectionV1.STRONGBOX -> keyFailureState()
            }
        }

        private fun verifyPrivateAttributes(
            privateKey: Pointer,
            protection: SelfOwnedKeyProtectionV1,
        ) {
            val attributes = checkNotNull(keyNative.security.SecKeyCopyAttributes(privateKey)) {
                "macOS signing-key attributes are unavailable"
            }
            try {
                check(MacSecurityNative.cf.CFGetTypeID(attributes) ==
                    MacSecurityNative.cf.CFDictionaryGetTypeID()) {
                    "macOS signing-key attributes are not a dictionary"
                }
                requireString(requireValue(attributes, "kSecAttrKeyClass"),
                    MacSecurityNative.constant("kSecAttrKeyClassPrivate"))
                requireString(requireValue(attributes, "kSecAttrKeyType"),
                    MacSecurityNative.constant("kSecAttrKeyTypeECSECPrimeRandom"))
                requireNumber(requireValue(attributes, "kSecAttrKeySizeInBits"), 256)
                when (protection) {
                    SelfOwnedKeyProtectionV1.OS_ISOLATED -> requireToken(attributes, null)
                    SelfOwnedKeyProtectionV1.SECURE_HARDWARE -> requireToken(attributes,
                        MacSecurityNative.constant("kSecAttrTokenIDSecureEnclave"))
                    SelfOwnedKeyProtectionV1.STRONGBOX -> keyFailureState()
                }
                requireOptionalBoolean(attributes, "kSecAttrCanSign", true)
                check(keyNative.security.SecKeyIsAlgorithmSupported(
                    privateKey, NativeLong(SEC_KEY_OPERATION_SIGN), algorithm(),
                ) != 0.toByte()) { "macOS signing-key algorithm is unsupported" }
            } finally {
                MacSecurityNative.cf.CFRelease(attributes)
            }
        }

        private fun verifyPublicAttributes(publicKey: Pointer) {
            val attributes = checkNotNull(keyNative.security.SecKeyCopyAttributes(publicKey)) {
                "macOS signing-key public attributes are unavailable"
            }
            try {
                check(MacSecurityNative.cf.CFGetTypeID(attributes) ==
                    MacSecurityNative.cf.CFDictionaryGetTypeID()) {
                    "macOS signing-key public attributes are not a dictionary"
                }
                requireString(requireValue(attributes, "kSecAttrKeyClass"),
                    MacSecurityNative.constant("kSecAttrKeyClassPublic"))
                requireString(requireValue(attributes, "kSecAttrKeyType"),
                    MacSecurityNative.constant("kSecAttrKeyTypeECSECPrimeRandom"))
                requireNumber(requireValue(attributes, "kSecAttrKeySizeInBits"), 256)
                requireOptionalBoolean(attributes, "kSecAttrCanVerify", true)
                check(keyNative.security.SecKeyIsAlgorithmSupported(
                    publicKey, NativeLong(SEC_KEY_OPERATION_VERIFY), algorithm(),
                ) != 0.toByte()) { "macOS signing-key verify algorithm is unsupported" }
            } finally {
                MacSecurityNative.cf.CFRelease(attributes)
            }
        }

        private fun requirePrivateNotExportable(privateKey: Pointer) {
            val exported = keyNative.security.SecKeyCopyExternalRepresentation(privateKey, null)
            if (exported != null) {
                MacSecurityNative.cf.CFRelease(exported)
                keyFailureState()
            }
        }

        private fun pointOfPrivate(privateKey: Pointer): ByteArray {
            val publicKey = checkNotNull(keyNative.security.SecKeyCopyPublicKey(privateKey)) {
                "macOS signing-key generated public point is unavailable"
            }
            try {
                return copyPublicPoint(publicKey)
            } finally {
                MacSecurityNative.cf.CFRelease(publicKey)
            }
        }

        private fun copyPublicPoint(publicKey: Pointer): ByteArray {
            val data = checkNotNull(keyNative.security.SecKeyCopyExternalRepresentation(publicKey, null)) {
                "macOS signing-key public point is unavailable"
            }
            try {
                check(MacSecurityNative.cf.CFGetTypeID(data) ==
                    MacSecurityNative.cf.CFDataGetTypeID()) {
                    "macOS signing-key public point is not data"
                }
                val length = MacSecurityNative.cf.CFDataGetLength(data).toLong()
                check(length == 65L) { "macOS signing-key public point length is invalid" }
                val point = checkNotNull(MacSecurityNative.cf.CFDataGetBytePtr(data))
                    .getByteArray(0, 65)
                try {
                    check(point[0].toInt() == 4) { "macOS signing-key public point is uncompressed" }
                    JvmP256Primitives.publicKey(point)
                } catch (failure: Throwable) {
                    point.fill(0)
                    throw failure
                }
                return point
            } finally {
                MacSecurityNative.cf.CFRelease(data)
            }
        }

        private fun requireToken(dictionary: Pointer, expected: Pointer?) {
            val token = requireValueOrNull(dictionary, "kSecAttrTokenID")
            if (expected == null) {
                check(token == null) { "macOS signing-key token differs from OS_ISOLATED" }
            } else {
                check(token != null && MacSecurityNative.cf.CFEqual(token, expected) != 0.toByte()) {
                    "macOS signing-key is not in the required Secure Enclave"
                }
            }
        }

        private fun requireOptionalBoolean(dictionary: Pointer, key: String, expected: Boolean) {
            val value = requireValueOrNull(dictionary, key) ?: return
            check(MacSecurityNative.cf.CFGetTypeID(value) ==
                keyNative.cf.CFBooleanGetTypeID()) { "macOS signing-key boolean type is invalid" }
            val actual = keyNative.cf.CFBooleanGetValue(value) != 0.toByte()
            check(actual == expected) { "macOS signing-key boolean attribute differs" }
        }

        private fun requireNumber(value: Pointer, expected: Int) {
            check(MacSecurityNative.cf.CFGetTypeID(value) ==
                keyNative.cf.CFNumberGetTypeID()) { "macOS signing-key number type is invalid" }
            Memory(4).use { memory ->
                check(keyNative.cf.CFNumberGetValue(value, NativeLong(CF_NUMBER_SINT32), memory) != 0.toByte()) {
                    "macOS signing-key number is unavailable"
                }
                check(memory.getInt(0) == expected) { "macOS signing-key number attribute differs" }
            }
        }

        private fun requireString(value: Pointer, expected: Pointer) {
            check(MacSecurityNative.cf.CFGetTypeID(value) ==
                MacSecurityNative.cf.CFStringGetTypeID()) { "macOS signing-key string type is invalid" }
            check(MacSecurityNative.cf.CFEqual(value, expected) != 0.toByte()) {
                "macOS signing-key string attribute differs"
            }
        }

        private fun requireData(value: Pointer, expected: ByteArray, message: String) {
            check(MacSecurityNative.cf.CFGetTypeID(value) ==
                MacSecurityNative.cf.CFDataGetTypeID()) { "macOS signing-key data type is invalid" }
            val length = MacSecurityNative.cf.CFDataGetLength(value).toLong()
            check(length == expected.size.toLong()) { message }
            val actual = checkNotNull(MacSecurityNative.cf.CFDataGetBytePtr(value))
                .getByteArray(0, expected.size)
            try {
                check(actual.contentEquals(expected)) { message }
            } finally {
                actual.fill(0)
            }
        }

        private fun requireValue(dictionary: Pointer, key: String): Pointer =
            checkNotNull(requireValueOrNull(dictionary, key)) { "macOS signing-key attribute is absent" }

        private fun requireValueOrNull(dictionary: Pointer, key: String): Pointer? =
            MacSecurityNative.cf.CFDictionaryGetValue(dictionary,
                MacSecurityNative.constant(key))

        private fun cfData(value: ByteArray): Pointer {
            val memory = Memory(maxOf(1, value.size).toLong())
            return try {
                if (value.isNotEmpty()) memory.write(0, value, 0, value.size)
                checkNotNull(MacSecurityNative.cf.CFDataCreate(null, memory,
                    NativeLong(value.size.toLong()))) { "macOS signing-key data creation failed" }
            } finally {
                memory.clear()
            }
        }

        private fun algorithm(): Pointer =
            MacSecurityNative.constant("kSecKeyAlgorithmECDSASignatureMessageX962SHA256")

        private fun requireSupportedPolicy(protection: SelfOwnedKeyProtectionV1) {
            if (protection == SelfOwnedKeyProtectionV1.STRONGBOX) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
            }
        }

        private fun requireMacPlatform() {
            if (!Platform.isMac() || Native.POINTER_SIZE != 8) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
            }
        }

        private fun securityFor(protection: SelfOwnedKeyProtectionV1): MacSelfOwnedSigningKeySecurity =
            when (protection) {
                SelfOwnedKeyProtectionV1.OS_ISOLATED ->
                    MacSelfOwnedSigningKeySecurity.DATA_PROTECTION_KEYCHAIN_SOFTWARE
                SelfOwnedKeyProtectionV1.SECURE_HARDWARE ->
                    MacSelfOwnedSigningKeySecurity.SECURE_ENCLAVE
                SelfOwnedKeyProtectionV1.STRONGBOX -> selfOwnedFail(
                    SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
            }

        private fun keyFailureState(): Nothing =
            selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)

        private inline fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (failure: SelfOwnedFailureV1) {
            throw failure
        } catch (_: LinkageError) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        } catch (_: Exception) {
            keyFailureState()
        }

        private const val SUCCESS = 0
        private const val NOT_FOUND = -25300
        private const val CF_NUMBER_SINT32 = 3L
        private const val SEC_KEY_OPERATION_SIGN = 0L
        private const val SEC_KEY_OPERATION_VERIFY = 1L
    }
}

private class Item(val dictionary: Pointer) : AutoCloseable {
    val privateKey: Pointer = checkNotNull(MacSecurityNative.cf.CFDictionaryGetValue(
        dictionary, MacSecurityNative.constant("kSecValueRef"))) {
        "macOS signing-key item reference is absent"
    }.also {
        check(MacSecurityNative.cf.CFGetTypeID(it) == keyNative.security.SecKeyGetTypeID()) {
            "macOS signing-key item reference type is invalid"
        }
    }

    override fun close() {
        MacSecurityNative.cf.CFRelease(dictionary)
    }
}

private class Loaded(
    val item: Item,
    val publicKey: Pointer,
    val point: ByteArray,
) : AutoCloseable {
    private var closeStarted = false
    private var closeConfirmed = false

    @Synchronized override fun close() {
        if (closeStarted) {
            check(closeConfirmed) { "macOS loaded signing-key reference close is unresolved" }
            return
        }
        closeStarted = true
        closeSigningReferences(publicKey, item, point)
        closeConfirmed = true
    }
}

/** Each owned reference receives one release attempt even if its peer release throws. */
private fun closeSigningReferences(publicKey: Pointer?, item: Item?, point: ByteArray) {
    var failure: Throwable? = null
    try { publicKey?.let { MacSecurityNative.cf.CFRelease(it) } }
    catch (problem: Throwable) { failure = problem }
    try { item?.close() }
    catch (problem: Throwable) {
        val first = failure
        if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
    } finally { point.fill(0) }
    failure?.let { throw it }
}

private object keyNative {
    private val coreLibrary = NativeLibrary.getInstance(
        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    private val securityLibrary = NativeLibrary.getInstance(
        "/System/Library/Frameworks/Security.framework/Security")
    val cf: MacSelfOwnedSigningKeyCoreFoundation =
        Native.load(coreLibrary.name, MacSelfOwnedSigningKeyCoreFoundation::class.java)
    val security: MacSelfOwnedSigningKeySecurityNative =
        Native.load(securityLibrary.name, MacSelfOwnedSigningKeySecurityNative::class.java)
}

private class Query : AutoCloseable {
    val dictionary: Pointer = checkNotNull(MacSecurityNative.cf.CFDictionaryCreateMutable(
        null, NativeLong(0),
        MacSecurityNative.core.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
        MacSecurityNative.core.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")))

    fun put(key: String, value: Pointer) {
        MacSecurityNative.cf.CFDictionarySetValue(dictionary, MacSecurityNative.constant(key), value)
    }

    fun putOwned(key: String, value: Pointer) {
        try {
            put(key, value)
        } finally {
            MacSecurityNative.cf.CFRelease(value)
        }
    }

    fun putBoolean(key: String, value: Boolean) {
        put(key, MacSecurityNative.constant(if (value) "kCFBooleanTrue" else "kCFBooleanFalse",
            core = true))
    }

    fun putNumber(key: String, value: Int) {
        Memory(4).use { memory ->
            memory.setInt(0, value)
            putOwned(key, checkNotNull(keyNative.cf.CFNumberCreate(
                null, NativeLong(CF_NUMBER_SINT32), memory)))
        }
    }

    fun putData(key: String, value: ByteArray) {
        val memory = Memory(maxOf(1, value.size).toLong())
        try {
            if (value.isNotEmpty()) memory.write(0, value, 0, value.size)
            putOwned(key, checkNotNull(MacSecurityNative.cf.CFDataCreate(null, memory,
                NativeLong(value.size.toLong()))))
        } finally {
            memory.clear()
        }
    }

    override fun close() {
        MacSecurityNative.cf.CFRelease(dictionary)
    }

    private companion object {
        const val CF_NUMBER_SINT32 = 3L
    }
}

internal interface MacSelfOwnedSigningKeyCoreFoundation : Library {
    fun CFNumberCreate(allocator: Pointer?, theType: NativeLong, valuePtr: Pointer): Pointer?
    fun CFNumberGetValue(number: Pointer, theType: NativeLong, valuePtr: Pointer): Byte
    fun CFNumberGetTypeID(): NativeLong
    fun CFBooleanGetValue(value: Pointer): Byte
    fun CFBooleanGetTypeID(): NativeLong
}

internal interface MacSelfOwnedSigningKeySecurityNative : Library {
    fun SecKeyCreateRandomKey(parameters: Pointer, error: PointerByReference?): Pointer?
    fun SecKeyCopyPublicKey(key: Pointer): Pointer?
    fun SecKeyCopyAttributes(key: Pointer): Pointer?
    fun SecKeyCopyExternalRepresentation(key: Pointer, error: PointerByReference?): Pointer?
    fun SecKeyCreateSignature(key: Pointer, algorithm: Pointer, dataToSign: Pointer,
        error: PointerByReference?): Pointer?
    fun SecKeyVerifySignature(key: Pointer, algorithm: Pointer, signedData: Pointer,
        signature: Pointer, error: PointerByReference?): Byte
    fun SecKeyIsAlgorithmSupported(key: Pointer, operation: NativeLong, algorithm: Pointer): Byte
    fun SecAccessControlCreateWithFlags(allocator: Pointer?, protection: Pointer,
        flags: NativeLong, error: PointerByReference?): Pointer?
    fun SecAccessControlGetTypeID(): NativeLong
    fun SecKeyGetTypeID(): NativeLong
}
