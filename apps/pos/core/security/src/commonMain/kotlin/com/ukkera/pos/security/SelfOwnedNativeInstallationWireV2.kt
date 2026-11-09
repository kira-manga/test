package com.ukkera.pos.security

/**
 * Closed Windows/macOS native-target framing. Every domain is new and disjoint from the Android
 * V1 intent/binding/journal domains and from the frozen installed-identity V2 domains. Decoding is
 * bounded, canonical and fail-closed: an unknown profile/domain/version/platform/role, truncation,
 * oversize field or trailing byte is refused. No decode path verifies a signature, observes OS
 * custody or grants authority.
 */
internal object SelfOwnedNativeInstallationWireV2 {
    fun encodeIntent(value: SelfOwnedNativeIntentV2): ByteArray =
        frame(SelfOwnedNativeIdentityProfileV2.INTENT_DOMAIN,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES) {
            int(SelfOwnedNativeIdentityProfileV2.FORMAT_VERSION)
            long(value.revision)
            source(value.source)
            namespace(value.namespace)
            octets(value.nonce)
            flag(value.bindingStarted)
            int(value.keys.size)
            value.keys.forEach { keyIntent(it) }
        }

    fun decodeIntent(bytes: ByteArray):
        SelfOwnedInstalledIdentityResultV2<SelfOwnedNativeIntentV2> =
        selfOwnedIdentityGuarded {
            val value = read(bytes, SelfOwnedNativeIdentityProfileV2.INTENT_DOMAIN,
                SelfOwnedNativeIdentityProfileV2.MAXIMUM_INTENT_BYTES) {
                requireVersion()
                val revision = long()
                val source = source()
                val namespace = namespace()
                val nonce = octets(SelfOwnedNativeIdentityProfileV2.NONCE_BYTES)
                if (nonce.size != SelfOwnedNativeIdentityProfileV2.NONCE_BYTES) {
                    refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                }
                val bindingStarted = flag()
                if (int() != 3) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
                val keys = List(3) { keyIntent() }
                SelfOwnedNativeIntentV2(revision, source, namespace, nonce, keys, bindingStarted)
            }
            canonical(bytes, SelfOwnedNativeInstallationWireV2::encodeIntent, value)
            value
        }

    fun keyBinding(value: SelfOwnedNativeIntentV2, role: SelfOwnedTargetKeyRoleV1): ByteArray =
        frame(SelfOwnedNativeIdentityProfileV2.KEY_DOMAIN,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_CONTROL_FRAME_BYTES) {
            coordinates(value.source.coordinates)
            installedTarget(value.source.installedTarget)
            signed(value.source.root); signed(value.source.delegation)
            preparationDocument(value.source.preparation)
            namespace(value.namespace); octets(value.nonce)
            int(role.wireId); id(value.key(role).keyId)
            bootSession(requireNotNull(value.key(role).startedBoot) {
                "A started or complete key intent requires its actual boot session"
            })
            int(value.source.protection.ordinal)
            string(SelfOwnedNativeIdentityProfileV2.NATIVE_ONLY_PURPOSE)
        }

    fun encodeBindingBody(value: SelfOwnedNativeBindingBodyV2): ByteArray =
        frame(SelfOwnedNativeIdentityProfileV2.BINDING_BODY_DOMAIN,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BODY_BYTES) {
            int(SelfOwnedNativeIdentityProfileV2.FORMAT_VERSION)
            coordinates(value.coordinates)
            installedTarget(value.installedTarget)
            namespace(value.namespace); bootSession(value.bootSession)
            string(value.rootDocumentDigest); string(value.delegationDigest)
            string(value.preparationDigest); string(value.completedIntentDigest)
            octets(value.targetPublicKey); int(value.protection.ordinal)
            int(value.keys.size); value.keys.forEach { keyIntent(it) }
            string(value.purpose)
        }

    fun decodeBindingBody(bytes: ByteArray):
        SelfOwnedInstalledIdentityResultV2<SelfOwnedNativeBindingBodyV2> =
        selfOwnedIdentityGuarded {
            val value = read(bytes, SelfOwnedNativeIdentityProfileV2.BINDING_BODY_DOMAIN,
                SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BODY_BYTES) {
                requireVersion()
                val coordinates = coordinates()
                val installedTarget = installedTarget()
                val namespace = namespace()
                val bootSession = bootSession()
                val root = digest()
                val delegation = digest()
                val preparation = digest()
                val completedIntent = digest()
                val point = octets(65)
                if (point.size != 65) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                val protection = protection()
                if (int() != 3) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
                val keys = List(3) { keyIntent() }
                val purpose = string()
                if (purpose != SelfOwnedNativeIdentityProfileV2.NATIVE_ONLY_PURPOSE) {
                    refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                }
                SelfOwnedNativeBindingBodyV2(coordinates, installedTarget, namespace, bootSession,
                    root, delegation, preparation, completedIntent, point, protection, keys)
            }
            canonical(bytes, SelfOwnedNativeInstallationWireV2::encodeBindingBody, value)
            value
        }

    fun bindingSigningMessage(body: ByteArray): ByteArray =
        frame(SelfOwnedNativeIdentityProfileV2.BINDING_PROOF_DOMAIN,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_CONTROL_FRAME_BYTES) {
            require(body.size <= SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BYTES - 512)
            string(SelfOwnedNativeIdentityProfileV2.NATIVE_ONLY_PURPOSE); field(body)
        }

    fun encodeBinding(value: SelfOwnedNativeBindingV2): ByteArray =
        frame(SelfOwnedNativeIdentityProfileV2.BINDING_DOMAIN,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BYTES) {
            int(SelfOwnedNativeIdentityProfileV2.FORMAT_VERSION)
            val body = value.body(); val signature = value.signature()
            try { field(body); field(signature) } finally { body.fill(0); signature.fill(0) }
        }

    fun decodeBinding(bytes: ByteArray):
        SelfOwnedInstalledIdentityResultV2<SelfOwnedNativeBindingV2> =
        selfOwnedIdentityGuarded {
            val value = read(bytes, SelfOwnedNativeIdentityProfileV2.BINDING_DOMAIN,
                SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BYTES) {
                requireVersion()
                val body = field(SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BODY_BYTES)
                val signature = field(SelfOwnedNativeIdentityProfileV2.SIGNATURE_BYTES)
                if (signature.size != SelfOwnedNativeIdentityProfileV2.SIGNATURE_BYTES) {
                    signature.fill(0); body.fill(0)
                    refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_SIGNATURE_SHAPE)
                }
                try {
                    SelfOwnedNativeBindingV2(body, signature)
                } finally { body.fill(0); signature.fill(0) }
            }
            canonical(bytes, SelfOwnedNativeInstallationWireV2::encodeBinding, value)
            value
        }

    fun stateAad(value: SelfOwnedNativeIntentV2): ByteArray =
        frame(SelfOwnedNativeIdentityProfileV2.AAD_DOMAIN,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_CONTROL_FRAME_BYTES) {
            require(value.complete() && value.bindingStarted)
            val intent = encodeIntent(value)
            try { field(intent) } finally { intent.fill(0) }
        }

    fun stateSignature(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        frame(SelfOwnedNativeIdentityProfileV2.SIGNATURE_DOMAIN,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_CONTROL_FRAME_BYTES +
                SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_BYTES) {
            field(aad); field(nonce); field(ciphertext)
        }

    fun encodeEnvelope(value: SelfOwnedNativeJournalEnvelopeV2): ByteArray =
        frame(SelfOwnedNativeIdentityProfileV2.ENVELOPE_DOMAIN,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_BYTES) {
            int(SelfOwnedNativeIdentityProfileV2.FORMAT_VERSION)
            require(value.nonce.size == 12 && value.signature.size ==
                SelfOwnedNativeIdentityProfileV2.SIGNATURE_BYTES)
            field(value.nonce); field(value.ciphertext); field(value.signature)
        }

    fun decodeEnvelope(bytes: ByteArray):
        SelfOwnedInstalledIdentityResultV2<SelfOwnedNativeJournalEnvelopeV2> =
        selfOwnedIdentityGuarded {
            val value = read(bytes, SelfOwnedNativeIdentityProfileV2.ENVELOPE_DOMAIN,
                SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_BYTES) {
                requireVersion()
                val nonce = field(12); val ciphertext = field(
                    SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_PLAINTEXT_BYTES + 16,
                ); val signature = field(SelfOwnedNativeIdentityProfileV2.SIGNATURE_BYTES)
                if (nonce.size != 12 || ciphertext.size < 16 || signature.size !=
                    SelfOwnedNativeIdentityProfileV2.SIGNATURE_BYTES) {
                    nonce.fill(0); ciphertext.fill(0); signature.fill(0)
                    refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                }
                SelfOwnedNativeJournalEnvelopeV2(nonce, ciphertext, signature)
            }
            canonical(bytes, SelfOwnedNativeInstallationWireV2::encodeEnvelope, value)
            value
        }

    fun encodeState(value: SelfOwnedNativeJournalStateV2): ByteArray =
        frame(SelfOwnedNativeIdentityProfileV2.STATE_DOMAIN,
            SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_PLAINTEXT_BYTES) {
            int(SelfOwnedNativeIdentityProfileV2.FORMAT_VERSION)
            long(value.revision); flag(value.newCeremoniesAllowed)
            val binding = value.originalBinding.copyWireBytes()
            try { field(binding) } finally { binding.fill(0) }
        }

    fun decodeState(bytes: ByteArray):
        SelfOwnedInstalledIdentityResultV2<SelfOwnedNativeJournalStateV2> =
        selfOwnedIdentityGuarded {
            val value = read(bytes, SelfOwnedNativeIdentityProfileV2.STATE_DOMAIN,
                SelfOwnedNativeIdentityProfileV2.MAXIMUM_STATE_PLAINTEXT_BYTES) {
                requireVersion()
                val revision = long(); val allowed = flag()
                val bindingBytes = field(SelfOwnedNativeIdentityProfileV2.MAXIMUM_BINDING_BYTES)
                val binding = try {
                    when (val result = decodeBinding(bindingBytes)) {
                        is SelfOwnedInstalledIdentityResultV2.Data -> result.value
                        is SelfOwnedInstalledIdentityResultV2.Refused -> refuse(result.code)
                    }
                } finally { bindingBytes.fill(0) }
                SelfOwnedNativeJournalStateV2(revision, binding, allowed)
            }
            canonical(bytes, SelfOwnedNativeInstallationWireV2::encodeState, value)
            value
        }

    /** SHA-256 of the complete, binding-started intent; syntax only, never custody evidence. */
    fun completedIntentDigest(value: SelfOwnedNativeIntentV2): String {
        require(value.complete() && value.bindingStarted)
        val bytes = encodeIntent(value)
        return try { sha256(bytes).value } finally { bytes.fill(0) }
    }

    private fun frame(
        domain: String,
        maximum: Int,
        body: SelfOwnedWireWriterV1.() -> Unit,
    ): ByteArray = SelfOwnedInstalledIdentityWireV2.frame(
        SelfOwnedNativeIdentityProfileV2.ID, domain, maximum, body,
    )

    private inline fun <T> read(
        bytes: ByteArray,
        domain: String,
        maximum: Int,
        body: SelfOwnedIdentityReader.() -> T,
    ): T {
        if (bytes.size > maximum) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        val reader = SelfOwnedIdentityReader(bytes)
        if (reader.string() != SelfOwnedNativeIdentityProfileV2.ID) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        }
        if (reader.string() != domain) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
        val result = reader.body()
        reader.finish()
        return result
    }

    private fun <T> canonical(bytes: ByteArray, encode: (T) -> ByteArray, value: T) {
        val canonical = encode(value)
        try {
            if (!canonical.contentEquals(bytes)) {
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
            }
        } finally { canonical.fill(0) }
    }

    private fun SelfOwnedIdentityReader.requireVersion() {
        if (int() != SelfOwnedNativeIdentityProfileV2.FORMAT_VERSION) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_VERSION)
        }
    }

    private fun SelfOwnedWireWriterV1.flag(value: Boolean) { int(if (value) 1 else 0) }

    private fun SelfOwnedIdentityReader.flag(): Boolean = when (int()) {
        0 -> false
        1 -> true
        else -> refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
    }

    private fun SelfOwnedIdentityReader.octets(maximum: Int): SelfOwnedOctetsV1 {
        val copy = field(maximum)
        return try { SelfOwnedOctetsV1(copy, maximum) } finally { copy.fill(0) }
    }

    private fun SelfOwnedIdentityReader.enumValue(values: List<SelfOwnedNativeSecurityV1>):
        SelfOwnedNativeSecurityV1 = values.getOrNull(int())
        ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)

    private fun SelfOwnedIdentityReader.protection(): SelfOwnedKeyProtectionV1 =
        SelfOwnedKeyProtectionV1.entries.getOrNull(int())
            ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)

    private fun SelfOwnedIdentityReader.coordinates(): SelfOwnedAllocationCoordinatesV1 {
        val ids = List(12) { uuid() }
        if (ids.toSet().size != 12) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DUPLICATE_COORDINATE)
        }
        return SelfOwnedAllocationCoordinatesV1(
            ids[0], BranchScope(ids[1], ids[2], ids[3]),
            ids[4], ids[5], ids[6], ids[7], ids[8], ids[9], ids[10], ids[11],
        )
    }

    private fun SelfOwnedIdentityReader.signed(): SelfOwnedSignedDocumentV1 {
        val copy = field(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
        val decoded = try { SelfOwnedInstallationWireV1.decodeSigned(copy) } finally { copy.fill(0) }
        return decoded ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
    }

    private fun SelfOwnedIdentityReader.installedTarget(): SelfOwnedNativeInstalledTargetBindingV1 {
        val copy = field(SelfOwnedNativeIdentityProfileV2.MAXIMUM_INSTALLED_TARGET_BYTES)
        val decoded = try { SelfOwnedInstalledTargetBindingV1.decode(copy) }
        finally { copy.fill(0) }
        val binding = when (decoded) {
            is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
            is SelfOwnedInstalledIdentityResultV2.Refused -> refuse(decoded.code)
        }
        return binding as? SelfOwnedNativeInstalledTargetBindingV1
            ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH)
    }

    private fun SelfOwnedIdentityReader.source(): SelfOwnedNativeSourceV2 {
        val root = signed(); val delegation = signed()
        val preparationBytes = field(
            SelfOwnedInstalledIdentityProfileV2.MAXIMUM_DOCUMENT_BYTES,
        )
        val preparation = try {
            when (val decoded =
                SelfOwnedInstalledIdentityWireV2.decodeSignedDocument(preparationBytes)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
                is SelfOwnedInstalledIdentityResultV2.Refused -> refuse(decoded.code)
            }
        } finally { preparationBytes.fill(0) }
        when (preparation.preparation.target.kind.platform) {
            SelfOwnedInstalledIdentityPlatformV2.WINDOWS,
            SelfOwnedInstalledIdentityPlatformV2.MACOS -> Unit
            SelfOwnedInstalledIdentityPlatformV2.IOS ->
                refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_POLICY_PENDING)
        }
        val target = SelfOwnedNativeInstalledTargetBindingV1(preparation.preparation.target)
        return SelfOwnedNativeSourceV2(
            SelfOwnedPreparationPacketV2(
                preparation.preparation.coordinates, target, root, delegation, preparation,
            ),
        )
    }

    private fun SelfOwnedIdentityReader.platform(): SelfOwnedNativePlatformV2 {
        val id = int()
        return SelfOwnedNativePlatformV2.fromWireId(id)
            ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH)
    }

    private fun SelfOwnedIdentityReader.namespace(): SelfOwnedNativeNamespaceV2 {
        return when (val platform = platform()) {
            SelfOwnedNativePlatformV2.WINDOWS -> SelfOwnedNativeNamespaceV2.Windows(
                string(), string(), string(), string(), string(), string(),
            )
            SelfOwnedNativePlatformV2.MACOS -> SelfOwnedNativeNamespaceV2.Mac(
                string(), int(), string(), long(), long(), long(),
            )
        }
    }

    private fun SelfOwnedIdentityReader.bootSession(): SelfOwnedNativeBootSessionV2 {
        val platform = platform()
        val id = string()
        if (!SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(id)) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        }
        return when (platform) {
            SelfOwnedNativePlatformV2.WINDOWS -> SelfOwnedNativeBootSessionV2.Windows(id)
            SelfOwnedNativePlatformV2.MACOS -> SelfOwnedNativeBootSessionV2.Mac(id)
        }
    }

    private fun SelfOwnedIdentityReader.keyIntent(): SelfOwnedTargetKeyIntentV2 {
        val roleId = int()
        val role = SelfOwnedTargetKeyRoleV1.entries.singleOrNull { it.wireId == roleId }
            ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
        val keyId = uuid()
        val phase = SelfOwnedKeyIntentPhaseV1.entries.getOrNull(int())
            ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        val started = if (flag()) bootSession() else null
        val security = if (flag()) enumValue(SelfOwnedNativeSecurityV1.entries.toList()) else null
        val proof = octets(SelfOwnedNativeIdentityProfileV2.MAXIMUM_KEY_PROOF_BYTES)
        return SelfOwnedTargetKeyIntentV2(role, keyId, phase, started, security, proof)
    }

    private fun SelfOwnedWireWriterV1.source(value: SelfOwnedNativeSourceV2) {
        signed(value.root); signed(value.delegation)
        preparationDocument(value.preparation)
    }

    private fun SelfOwnedWireWriterV1.preparationDocument(
        value: SelfOwnedInstalledIdentitySignedDocumentV2,
    ) {
        val bytes = value.copyWireBytes()
        try { field(bytes) } finally { bytes.fill(0) }
    }

    private fun SelfOwnedWireWriterV1.installedTarget(
        value: SelfOwnedNativeInstalledTargetBindingV1,
    ) {
        val bytes = value.copyWireBytes()
        try { field(bytes) } finally { bytes.fill(0) }
    }

    private fun SelfOwnedWireWriterV1.namespace(value: SelfOwnedNativeNamespaceV2) {
        int(value.platform.wireId)
        when (value) {
            is SelfOwnedNativeNamespaceV2.Windows -> {
                string(value.canonicalPath); string(value.ownerSid); string(value.volumeId)
                string(value.directoryFileId); string(value.lockFileId)
                string(value.databaseDirectoryFileId)
            }
            is SelfOwnedNativeNamespaceV2.Mac -> {
                string(value.canonicalPath); int(value.uid); string(value.volumeUuid)
                long(value.directoryInode); long(value.lockInode); long(value.databaseDirectoryInode)
            }
        }
    }

    private fun SelfOwnedWireWriterV1.bootSession(value: SelfOwnedNativeBootSessionV2) {
        int(value.platform.wireId); string(value.bootSessionId)
    }

    private fun SelfOwnedWireWriterV1.keyIntent(value: SelfOwnedTargetKeyIntentV2) {
        int(value.role.wireId); id(value.keyId); int(value.phase.ordinal)
        flag(value.startedBoot != null); value.startedBoot?.let { bootSession(it) }
        flag(value.security != null); value.security?.let { int(it.ordinal) }
        octets(value.proof)
    }
}
