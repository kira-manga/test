package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Native target journal bounds, not a schema migration, database receipt or runtime format. */
internal object SelfOwnedNativeInstallationFormatV1 {
    const val MAXIMUM_INTENT_BYTES: Int = 4 * 65_536
    const val MAXIMUM_BINDING_BYTES: Int = 65_536
    const val MAXIMUM_STATE_BYTES: Int = 128 * 1024
    const val MAXIMUM_STATE_PLAINTEXT_BYTES: Int = MAXIMUM_STATE_BYTES - 1024
    const val MAXIMUM_CONTROL_FRAME_BYTES: Int = MAXIMUM_INTENT_BYTES + 4096
    const val NATIVE_ONLY_PURPOSE: String = "ORIGINAL_NATIVE_TARGET_ONLY_NO_ROOM_HISTORY_OR_WRITER_AUTHORITY"
}

internal enum class SelfOwnedTargetKeyRoleV1(val wireId: Int) {
    TARGET_SIGNING(1), STORAGE(2), PEPPER(3),
}

/** Actual original native paths/inodes; only current-lifetime stamps may contain st_dev. */
internal data class SelfOwnedNativeNamespaceV1(
    val canonicalPath: String,
    val volumeUuid: String,
    val applicationUid: Int,
    val directoryInode: Long,
    val lockInode: Long,
    val databaseDirectoryInode: Long,
) {
    init {
        require(canonicalPath.length in 1..1024 && canonicalPath.startsWith('/') &&
            SelfOwnedCompositionNames.isTargetPath(canonicalPath) && '\u0000' !in canonicalPath &&
            canonicalPath.split('/').none { it == "." || it == ".." })
        selfOwnedUtf16(canonicalPath)
        require(volumeUuid.length == 36 && volumeUuid.withIndex().all { (index, character) ->
            if (index in listOf(8, 13, 18, 23)) character == '-'
            else character in '0'..'9' || character in 'a'..'f'
        })
        require(applicationUid >= 0 && directoryInode > 0 && lockInode > 0 && databaseDirectoryInode > 0)
        require(setOf(directoryInode, lockInode, databaseDirectoryInode).size == 3)
    }
    override fun toString(): String = "SelfOwnedNativeNamespaceV1([REDACTED])"
}

internal data class SelfOwnedTargetKeyIntentV1(
    val role: SelfOwnedTargetKeyRoleV1,
    val keyId: DomainId,
    val phase: SelfOwnedKeyIntentPhaseV1,
    val startedBoot: Int?,
    val security: SelfOwnedNativeSecurityV1?,
    val proof: SelfOwnedOctetsV1,
) {
    init {
        require(startedBoot == null || startedBoot >= 0)
        when (phase) {
            SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> require(startedBoot == null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.STARTED -> require(startedBoot != null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.COMPLETE -> {
                require(startedBoot != null && security != null)
                require(proof.size == when (role) {
                    SelfOwnedTargetKeyRoleV1.TARGET_SIGNING -> 65
                    SelfOwnedTargetKeyRoleV1.STORAGE -> 61
                    SelfOwnedTargetKeyRoleV1.PEPPER -> 32
                })
            }
        }
    }
    override fun toString(): String = "SelfOwnedTargetKeyIntentV1([REDACTED])"
}

/**
 * Forced prior intent under the original private namespace. Before original key proof it is
 * OS-private prior state, not self-authenticating authority. The source is always retained
 * verbatim from the original P1 grant; not a new allocation selected by a caller on retry.
 */
internal data class SelfOwnedNativeIntentV1(
    val revision: Long,
    val source: SelfOwnedNativeSourceV1,
    val namespace: SelfOwnedNativeNamespaceV1,
    val nonce: SelfOwnedOctetsV1,
    val keys: List<SelfOwnedTargetKeyIntentV1>,
    val bindingStarted: Boolean,
) {
    init {
        require(revision in 0 until Long.MAX_VALUE && nonce.size == 32)
        require(namespace.applicationUid == source.preparationBody.target.applicationUid)
        require(keys.map { it.role } == SelfOwnedTargetKeyRoleV1.entries.toList())
        val coordinates = source.preparationBody.coordinates
        require(keys.map { it.keyId } == listOf(coordinates.targetSigningKeyId, coordinates.storageKeyId, coordinates.pepperKeyId))
        var unfinished = false
        for (key in keys) {
            when (key.phase) {
                SelfOwnedKeyIntentPhaseV1.COMPLETE -> require(!unfinished)
                SelfOwnedKeyIntentPhaseV1.STARTED -> { require(!unfinished); unfinished = true }
                SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> unfinished = true
            }
        }
        require(!bindingStarted || complete())
    }
    fun complete(): Boolean = keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }
    fun key(role: SelfOwnedTargetKeyRoleV1): SelfOwnedTargetKeyIntentV1 = keys.single { it.role == role }
    fun replaceKey(next: SelfOwnedTargetKeyIntentV1): SelfOwnedNativeIntentV1 {
        require(!bindingStarted && revision < Long.MAX_VALUE - 1 && key(next.role).keyId == next.keyId)
        return copy(revision = revision + 1L, keys = keys.map { if (it.role == next.role) next else it })
    }
    override fun toString(): String = "SelfOwnedNativeIntentV1([REDACTED])"
}

internal data class SelfOwnedNativeJournalStateV1(
    val revision: Long,
    val originalBinding: SelfOwnedNativeBindingV1,
    val newCeremoniesAllowed: Boolean,
) {
    init { require(revision in 0 until Long.MAX_VALUE) }
    fun withdrawNewCeremonies(): SelfOwnedNativeJournalStateV1 {
        if (revision == Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        return copy(revision = revision + 1L, newCeremoniesAllowed = false)
    }
    override fun toString(): String = "SelfOwnedNativeJournalStateV1([REDACTED])"
}

/** Closed native-only framing, separate from the unchanged complete database definition codec. */
internal object SelfOwnedNativeInstallationWireV1 {
    fun encodeIntent(value: SelfOwnedNativeIntentV1): ByteArray =
        selfOwnedEncode("native-target-original-intent/v1", SelfOwnedNativeInstallationFormatV1.MAXIMUM_INTENT_BYTES) {
            long(value.revision); signed(value.source.root); signed(value.source.delegation); signed(value.source.preparation)
            namespace(value.namespace); octets(value.nonce); bool(value.bindingStarted); int(value.keys.size)
            for (key in value.keys) {
                int(key.role.wireId); id(key.keyId); int(key.phase.ordinal)
                bool(key.startedBoot != null); key.startedBoot?.let { int(it) }
                bool(key.security != null); key.security?.let { int(it.ordinal) }
                octets(key.proof)
            }
        }

    fun decodeIntent(bytes: ByteArray): SelfOwnedNativeIntentV1 {
        require(bytes.size in 1..SelfOwnedNativeInstallationFormatV1.MAXIMUM_INTENT_BYTES)
        return selfOwnedDecode(bytes, "native-target-original-intent/v1") {
            val revision = long(); val source = SelfOwnedNativeSourceV1(signed(), signed(), signed())
            val namespace = namespace(); val nonce = octets(32); val started = bool(); require(int() == 3)
            val keys = List(3) {
                val roleId = int(); val role = requireNotNull(SelfOwnedTargetKeyRoleV1.entries.singleOrNull { it.wireId == roleId })
                val keyId = id(); val phase = enumValue(SelfOwnedKeyIntentPhaseV1.entries.toList())
                val boot = if (bool()) int() else null
                val security = if (bool()) enumValue(SelfOwnedNativeSecurityV1.entries.toList()) else null
                SelfOwnedTargetKeyIntentV1(role, keyId, phase, boot, security, octets(65))
            }
            SelfOwnedNativeIntentV1(revision, source, namespace, nonce, keys, started)
        }
    }

    fun keyBinding(value: SelfOwnedNativeIntentV1, role: SelfOwnedTargetKeyRoleV1): ByteArray =
        selfOwnedEncode("native-target-original-key/v1", SelfOwnedNativeInstallationFormatV1.MAXIMUM_CONTROL_FRAME_BYTES) {
            val originalCoordinates = value.source.preparationBody.coordinates
            coordinates(originalCoordinates); target(value.source.preparationBody.target)
            signed(value.source.root); signed(value.source.delegation); signed(value.source.preparation)
            namespace(value.namespace); octets(value.nonce); int(role.wireId); id(value.key(role).keyId)
            int(value.source.preparationBody.protection.ordinal)
        }

    fun encodeBindingBody(value: SelfOwnedNativeBindingBodyV1): ByteArray = selfOwnedEncode("native-target-binding-body/v1") {
        coordinates(value.coordinates); target(value.target)
        string(value.rootDocumentDigest); string(value.delegationDigest); string(value.preparationDigest)
        string(value.completedIntentDigest); octets(value.targetPublicKey); int(value.protection.ordinal)
        int(value.signingSecurity.ordinal); int(value.storageSecurity.ordinal); int(value.pepperSecurity.ordinal)
        string(SelfOwnedNativeInstallationFormatV1.NATIVE_ONLY_PURPOSE)
    }

    fun decodeBindingBody(bytes: ByteArray): SelfOwnedNativeBindingBodyV1 {
        require(bytes.size in 1..SelfOwnedNativeInstallationFormatV1.MAXIMUM_BINDING_BYTES)
        return selfOwnedDecode(bytes, "native-target-binding-body/v1") {
            val coordinates = coordinates(); val target = target()
            val root = string(64); val delegation = string(64); val preparation = string(64); val intent = string(64)
            val point = octets(65); val protection = protection()
            val levels = SelfOwnedNativeSecurityV1.entries.toList()
            val signing = enumValue(levels); val storage = enumValue(levels); val pepper = enumValue(levels)
            require(string(80) == SelfOwnedNativeInstallationFormatV1.NATIVE_ONLY_PURPOSE)
            SelfOwnedNativeBindingBodyV1(coordinates, target, root, delegation, preparation, intent, point,
                protection, signing, storage, pepper)
        }
    }

    fun bindingSigningMessage(body: ByteArray): ByteArray = selfOwnedEncode("native-target-binding-proof/v1") {
        require(body.size <= SelfOwnedNativeInstallationFormatV1.MAXIMUM_BINDING_BYTES - 512)
        string(SelfOwnedNativeInstallationFormatV1.NATIVE_ONLY_PURPOSE); field(body)
    }

    fun encodeBinding(value: SelfOwnedNativeBindingV1): ByteArray = selfOwnedEncode("native-target-binding-signed/v1") {
        val body = value.body(); val signature = value.signature()
        try { field(body); field(signature) } finally { body.fill(0); signature.fill(0) }
    }

    fun decodeBinding(bytes: ByteArray): SelfOwnedNativeBindingV1 {
        require(bytes.size in 1..SelfOwnedNativeInstallationFormatV1.MAXIMUM_BINDING_BYTES)
        return selfOwnedDecode(bytes, "native-target-binding-signed/v1") {
            val body = field(SelfOwnedNativeInstallationFormatV1.MAXIMUM_BINDING_BYTES - 512); val signature = field(64)
            try { SelfOwnedNativeBindingV1(body, signature) } finally { body.fill(0); signature.fill(0) }
        }
    }

    fun encodeState(value: SelfOwnedNativeJournalStateV1): ByteArray =
        selfOwnedEncode("native-target-journal-state/v1", SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_PLAINTEXT_BYTES) {
            long(value.revision); bool(value.newCeremoniesAllowed)
            val binding = value.originalBinding.copyWireBytes()
            try { field(binding) } finally { binding.fill(0) }
        }

    fun decodeState(bytes: ByteArray): SelfOwnedNativeJournalStateV1 {
        require(bytes.size in 1..SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_PLAINTEXT_BYTES)
        return selfOwnedDecode(bytes, "native-target-journal-state/v1") {
            val revision = long(); val allowed = bool(); val binding = field(SelfOwnedNativeInstallationFormatV1.MAXIMUM_BINDING_BYTES)
            val document = try { decodeBinding(binding) } finally { binding.fill(0) }
            SelfOwnedNativeJournalStateV1(revision, document, allowed)
        }
    }

    fun stateAad(value: SelfOwnedNativeIntentV1): ByteArray =
        selfOwnedEncode("native-target-journal-aad/v1", SelfOwnedNativeInstallationFormatV1.MAXIMUM_CONTROL_FRAME_BYTES) {
            require(value.complete() && value.bindingStarted)
            val intent = encodeIntent(value)
            try { field(intent) } finally { intent.fill(0) }
        }

    fun stateSignature(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        selfOwnedEncode("native-target-journal-signature/v1",
            SelfOwnedNativeInstallationFormatV1.MAXIMUM_CONTROL_FRAME_BYTES + SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_BYTES) {
            field(aad); field(nonce); field(ciphertext)
        }

    fun encodeEnvelope(value: SelfOwnedRegistryEnvelopeV1): ByteArray =
        selfOwnedEncode("native-target-journal-envelope/v1", SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_BYTES) {
            require(value.nonce.size == 12 && value.signature.size == 64)
            field(value.nonce); field(value.ciphertext); field(value.signature)
        }

    fun decodeEnvelope(bytes: ByteArray): SelfOwnedRegistryEnvelopeV1 {
        require(bytes.size in 1..SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_BYTES)
        return selfOwnedDecode(bytes, "native-target-journal-envelope/v1") {
            val nonce = field(12); val ciphertext = field(SelfOwnedNativeInstallationFormatV1.MAXIMUM_STATE_PLAINTEXT_BYTES + 16)
            val signature = field(64)
            require(nonce.size == 12 && ciphertext.size >= 16 && signature.size == 64)
            SelfOwnedRegistryEnvelopeV1(nonce, ciphertext, signature)
        }
    }

    private fun SelfOwnedWireWriterV1.namespace(value: SelfOwnedNativeNamespaceV1) {
        string(value.canonicalPath); string(value.volumeUuid); int(value.applicationUid)
        long(value.directoryInode); long(value.lockInode); long(value.databaseDirectoryInode)
    }
    private fun SelfOwnedWireReaderV1.namespace(): SelfOwnedNativeNamespaceV1 =
        SelfOwnedNativeNamespaceV1(string(4096), string(36), int(), long(), long(), long())
}
