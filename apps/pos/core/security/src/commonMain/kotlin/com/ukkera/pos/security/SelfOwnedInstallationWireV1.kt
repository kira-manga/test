package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Fixed bounded canonical binary framing. Decoding signed bytes is NEVER authority verification. */
public object SelfOwnedInstallationWireV1 {
    public fun decodeSigned(bytes: ByteArray): SelfOwnedSignedDocumentV1? {
        if (bytes.size !in 1..SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES) return null
        val owned = bytes.copyOf()
        return try {
            selfOwnedDecode(owned, "signed-document/v1") {
                val roleId = int()
                val role = SelfOwnedDocumentRoleV1.entries.singleOrNull { it.wireId == roleId }
                requireNotNull(role)
                val body = field(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES - 512)
                val signature = field(64)
                try { SelfOwnedSignedDocumentV1(role, body, signature) }
                finally { body.fill(0); signature.fill(0) }
            }
        } catch (_: Exception) { null } // Includes strict UTF-8 decoding failure; bounded pure parsing only.
        finally { owned.fill(0) }
    }

    internal fun encodeSigned(value: SelfOwnedSignedDocumentV1): ByteArray =
        selfOwnedEncode("signed-document/v1") {
            int(value.role.wireId)
            val body = value.body(); val signature = value.signature()
            try { field(body); field(signature) } finally { body.fill(0); signature.fill(0) }
        }

    internal fun signingMessage(role: SelfOwnedDocumentRoleV1, body: ByteArray): ByteArray =
        selfOwnedEncode("signed-body/v1") { int(role.wireId); field(body) }

    internal fun encodeRoot(value: SelfOwnedRootBodyV1): ByteArray = selfOwnedEncode("root-announcement/v1") {
        id(value.rootId); id(value.rootKeyId); id(value.organizationId); id(value.companyId); id(value.installerId)
        string(value.companyName); string(value.responsiblePersonName); string(value.installerLogin)
        octets(value.rootPublicKey); int(value.protection.ordinal); id(value.bootstrapActionId)
    }

    internal fun decodeRoot(bytes: ByteArray): SelfOwnedRootBodyV1 = selfOwnedDecode(bytes, "root-announcement/v1") {
        SelfOwnedRootBodyV1(id(), id(), id(), id(), id(), string(640), string(640), string(64), octets(65), protection(), id())
    }

    internal fun encodeDelegation(value: SelfOwnedDelegationBodyV1): ByteArray = selfOwnedEncode("issuer-delegation/v1") {
        id(value.rootId); string(value.rootDocumentDigest); id(value.organizationId); id(value.companyId)
        id(value.issuerId); id(value.issuerKeyId); octets(value.issuerPublicKey); id(value.installerId)
        long(value.installerRevision); long(value.delegationRevision); int(value.protection.ordinal)
        string("LOCAL_REGISTRY_AND_ORIGINAL_NATIVE_PREPARATION_ONLY")
    }

    internal fun decodeDelegation(bytes: ByteArray): SelfOwnedDelegationBodyV1 = selfOwnedDecode(bytes, "issuer-delegation/v1") {
        val result = SelfOwnedDelegationBodyV1(id(), string(64), id(), id(), id(), id(), octets(65), id(), long(), long(), protection())
        require(string(80) == "LOCAL_REGISTRY_AND_ORIGINAL_NATIVE_PREPARATION_ONLY")
        result
    }

    internal fun encodePreparation(value: SelfOwnedPreparationBodyV1): ByteArray = selfOwnedEncode("native-preparation/v1") {
        preparationBodyFields(value)
    }

    /** Unframed legacy preparation body (the frozen `legacy.preparation` body vector). */
    internal fun encodePreparationBody(value: SelfOwnedPreparationBodyV1): ByteArray {
        val writer = SelfOwnedWireWriterV1(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
        return try { writer.preparationBodyFields(value); writer.finish() } finally { writer.clear() }
    }

    /** Unframed legacy four-field target; no tag, version or platform discriminator is added. */
    internal fun encodeTarget(value: SelfOwnedTargetDescriptorV1): ByteArray {
        val writer = SelfOwnedWireWriterV1(1024)
        return try { writer.target(value); writer.finish() } finally { writer.clear() }
    }

    private fun SelfOwnedWireWriterV1.preparationBodyFields(value: SelfOwnedPreparationBodyV1) {
        id(value.rootId); string(value.rootDocumentDigest); id(value.issuerId); string(value.delegationDigest)
        id(value.installerId); long(value.installerRevision); long(value.delegationRevision)
        coordinates(value.coordinates); long(value.serial); octets(value.nonce); target(value.target)
        string(value.branchName); int(value.protection.ordinal)
        string("PREPARE_ORIGINAL_NATIVE_KEYS_ONLY_NO_ROOM_AUTHORITY")
    }

    /**
     * The actual shared legacy preparation consumer. The result-returning compatibility parser
     * is the single source of the legacy field/refusal rules; this throwing wrapper preserves
     * the existing internal `require`-style contract used by the native owner and registry.
     */
    internal fun decodePreparation(bytes: ByteArray): SelfOwnedPreparationBodyV1 =
        SelfOwnedLegacyInstallationCompatibilityV1.requirePreparation(bytes)

    internal fun encodeBootstrap(value: SelfOwnedBootstrapV1): ByteArray = selfOwnedEncode("native-bootstrap-intent/v1") {
        long(value.revision); id(value.actionId); id(value.rootId); id(value.organizationId); id(value.companyId)
        id(value.issuerId); id(value.installerId)
        string(value.facts.companyName); string(value.facts.responsiblePersonName); string(value.facts.installerLogin)
        int(value.facts.protection.ordinal); target(value.target); registryNamespace(value.namespace)
        bool(value.enrollmentStarted); octets(value.nonce); int(value.keys.size)
        for (key in value.keys) {
            int(key.role.wireId); id(key.keyId); int(key.phase.ordinal)
            bool(key.startedBoot != null); key.startedBoot?.let { int(it) }
            bool(key.security != null); key.security?.let { int(it.ordinal) }
            octets(key.proof)
        }
    }

    internal fun decodeBootstrap(bytes: ByteArray): SelfOwnedBootstrapV1 = selfOwnedDecode(bytes, "native-bootstrap-intent/v1") {
        val revision = long(); val action = id(); val root = id(); val organization = id(); val company = id()
        val issuer = id(); val installer = id()
        val facts = SelfOwnedCompanyFactsV1(string(640), string(640), string(64), protection())
        val target = target(); val namespace = registryNamespace(); val enrollmentStarted = bool()
        val nonce = octets(32); require(int() == 4)
        val keys = List(4) {
            val roleId = int()
            val role = requireNotNull(SelfOwnedNativeKeyRoleV1.entries.singleOrNull { it.wireId == roleId })
            val keyId = id(); val phase = enumValue(SelfOwnedKeyIntentPhaseV1.entries.toList())
            val boot = if (bool()) int() else null
            val security = if (bool()) enumValue(SelfOwnedNativeSecurityV1.entries.toList()) else null
            SelfOwnedNativeKeyIntentV1(role, keyId, phase, boot, security, octets(512))
        }
        SelfOwnedBootstrapV1(revision, action, root, organization, company, issuer, installer, facts, target,
            namespace, nonce, keys, enrollmentStarted)
    }

    internal fun encodeRegistry(value: LocalProvisionerRegistryStateV1): ByteArray =
        selfOwnedEncode("local-provisioner-registry/v1", SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES) {
            long(value.revision); signed(value.root); signed(value.delegation); bool(value.issuanceEnabled); long(value.nextSerial)
            int(value.installers.size)
            for (record in value.installers) {
                id(record.installerId); string(record.name); string(record.login)
                long(record.credentialRevision); long(record.delegationRevision); bool(record.enabled)
                int(SelfOwnedInstallationProfileV1.PASSWORD_ITERATIONS); octets(record.salt); octets(record.verifier)
                int(record.failedAttempts); bool(record.reservationId != null); record.reservationId?.let { id(it) }
                int(record.throttleBoot); long(record.nextAllowedElapsedMillis); long(record.cooldownMillis)
            }
            int(value.allocations.size)
            for (record in value.allocations) {
                val body = encodePreparation(record.body)
                try { field(body) } finally { body.fill(0) }
                int(record.phase.ordinal); bool(record.grant != null); record.grant?.let { signed(it) }
            }
        }

    internal fun decodeRegistry(bytes: ByteArray): LocalProvisionerRegistryStateV1 {
        require(bytes.size <= SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES)
        return selfOwnedDecode(bytes, "local-provisioner-registry/v1") {
            val revision = long(); val root = signed(); val delegation = signed(); val enabled = bool(); val serial = long()
            val count = int(); require(count in 1..SelfOwnedInstallationProfileV1.MAXIMUM_INSTALLERS)
            val installers = List(count) {
                val installer = id(); val name = string(640); val login = string(64)
                val credential = long(); val delegated = long(); val active = bool()
                require(int() == SelfOwnedInstallationProfileV1.PASSWORD_ITERATIONS)
                val salt = octets(32); val verifier = octets(32); val failed = int()
                val reservation = if (bool()) id() else null
                LocalProvisionerInstallerV1(installer, name, login, credential, delegated, active, salt, verifier,
                    failed, reservation, int(), long(), long())
            }
            val allocationCount = int(); require(allocationCount <= SelfOwnedInstallationProfileV1.MAXIMUM_ALLOCATIONS)
            val allocations = List(allocationCount) {
                val body = field(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
                val decoded = try { decodePreparation(body) } finally { body.fill(0) }
                LocalProvisionerAllocationV1(decoded, enumValue(LocalProvisionerAllocationPhaseV1.entries.toList()),
                    if (bool()) signed() else null)
            }
            LocalProvisionerRegistryStateV1(revision, root, delegation, enabled, serial, installers, allocations)
        }
    }

    internal fun registryAad(bootstrap: SelfOwnedBootstrapV1): ByteArray = selfOwnedEncode("registry-aad/v1") {
        id(bootstrap.rootId); id(bootstrap.key(SelfOwnedNativeKeyRoleV1.REGISTRY).keyId); id(bootstrap.issuerId)
        val body = encodeBootstrap(bootstrap)
        try { field(body) } finally { body.fill(0) }
    }

    internal fun registrySignature(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        selfOwnedEncode("registry-signature/v1", SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES + 65_536) {
            field(aad); field(nonce); field(ciphertext)
        }

    internal fun encodeEnvelope(nonce: ByteArray, ciphertext: ByteArray, signature: ByteArray): ByteArray =
        selfOwnedEncode("encrypted-registry/v1", SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES) {
            require(nonce.size == 12 && signature.size == 64); field(nonce); field(ciphertext); field(signature)
        }

    internal fun decodeEnvelope(bytes: ByteArray): SelfOwnedRegistryEnvelopeV1 {
        require(bytes.size <= SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES)
        return selfOwnedDecode(bytes, "encrypted-registry/v1") {
            val nonce = field(12); val ciphertext = field(SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES + 16)
            val signature = field(64)
            require(nonce.size == 12 && ciphertext.size >= 16 && signature.size == 64)
            SelfOwnedRegistryEnvelopeV1(nonce, ciphertext, signature)
        }
    }
}

internal class SelfOwnedRegistryEnvelopeV1(val nonce: ByteArray, val ciphertext: ByteArray, val signature: ByteArray) {
    fun clear() { nonce.fill(0); ciphertext.fill(0); signature.fill(0) }
}

internal fun selfOwnedEncode(domain: String,
    maximum: Int = SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES,
    body: SelfOwnedWireWriterV1.() -> Unit,
): ByteArray {
    val writer = SelfOwnedWireWriterV1(maximum)
    return try {
        writer.string(SelfOwnedInstallationProfileV1.ID); writer.string(domain)
        writer.body(); writer.finish()
    } finally { writer.clear() }
}

internal fun <T> selfOwnedDecode(bytes: ByteArray, domain: String, body: SelfOwnedWireReaderV1.() -> T): T {
    val reader = SelfOwnedWireReaderV1(bytes)
    require(reader.string(128) == SelfOwnedInstallationProfileV1.ID && reader.string(128) == domain)
    val result = reader.body(); reader.finish(); return result
}

internal class SelfOwnedWireWriterV1(private val maximum: Int) {
    private var bytes = ByteArray(minOf(maximum, 256))
    private var used = 0
    private fun room(size: Int) {
        require(size >= 0 && size <= maximum - used)
        if (used + size > bytes.size) {
            val next = ByteArray(minOf(maximum, maxOf(used + size, bytes.size * 2)))
            bytes.copyInto(next, endIndex = used); bytes.fill(0); bytes = next
        }
    }
    fun int(value: Int) {
        require(value >= 0); room(4)
        for (shift in intArrayOf(24, 16, 8, 0)) bytes[used++] = (value ushr shift).toByte()
    }
    fun long(value: Long) {
        require(value >= 0); room(8)
        for (shift in 56 downTo 0 step 8) bytes[used++] = (value ushr shift).toByte()
    }
    fun bool(value: Boolean) { room(1); bytes[used++] = if (value) 1 else 0 }
    fun field(value: ByteArray) { int(value.size); room(value.size); value.copyInto(bytes, used); used += value.size }
    fun string(value: String) {
        selfOwnedUtf16(value)
        val encoded = value.encodeToByteArray()
        try { field(encoded) } finally { encoded.fill(0) }
    }
    fun id(value: DomainId) { string(value.value) }
    fun octets(value: SelfOwnedOctetsV1) { val copy = value.copyBytes(); try { field(copy) } finally { copy.fill(0) } }
    fun signed(value: SelfOwnedSignedDocumentV1) { val copy = value.copyWireBytes(); try { field(copy) } finally { copy.fill(0) } }
    fun target(value: SelfOwnedTargetDescriptorV1) {
        string(value.applicationId); int(value.applicationUid); string(value.apkSignerSha256); string(value.androidIdCommitment)
    }
    fun registryNamespace(value: SelfOwnedRegistryNamespaceV1) {
        string(value.canonicalPath); int(value.applicationUid); string(value.volumeUuid)
        long(value.directoryInode); long(value.lockInode)
    }
    fun coordinates(value: SelfOwnedAllocationCoordinatesV1) {
        id(value.allocationId); id(value.scope.organizationId); id(value.scope.companyId); id(value.scope.branchId)
        id(value.installationId); id(value.assignmentId); id(value.deviceId); id(value.storageInstanceId)
        id(value.storageKeyId); id(value.pepperKeyId); id(value.targetSigningKeyId); id(value.firstActorId)
    }
    fun finish(): ByteArray = bytes.copyOf(used)
    fun clear() { bytes.fill(0) }
}

internal class SelfOwnedWireReaderV1(private val bytes: ByteArray) {
    private var at = 0
    private fun available(size: Int) { require(size >= 0 && size <= bytes.size - at) }
    fun int(): Int {
        available(4); var result = 0
        repeat(4) { result = (result shl 8) or (bytes[at++].toInt() and 255) }
        require(result >= 0); return result
    }
    fun long(): Long {
        available(8); var result = 0L
        repeat(8) { result = (result shl 8) or (bytes[at++].toLong() and 255L) }
        require(result >= 0); return result
    }
    fun bool(): Boolean { available(1); return when (bytes[at++].toInt()) { 0 -> false; 1 -> true; else -> throw IllegalArgumentException() } }
    fun field(maximum: Int): ByteArray {
        val size = int(); require(size <= maximum); available(size)
        return bytes.copyOfRange(at, at + size).also { at += size }
    }
    fun string(maximum: Int): String {
        val copy = field(maximum)
        return try { copy.decodeToString(throwOnInvalidSequence = true).also(::selfOwnedUtf16) } finally { copy.fill(0) }
    }
    fun id(): DomainId = DomainId(string(36))
    fun octets(maximum: Int): SelfOwnedOctetsV1 {
        val copy = field(maximum)
        return try { SelfOwnedOctetsV1(copy, maximum) } finally { copy.fill(0) }
    }
    fun <T> enumValue(values: List<T>): T = requireNotNull(values.getOrNull(int()))
    fun protection(): SelfOwnedKeyProtectionV1 = enumValue(SelfOwnedKeyProtectionV1.entries.toList())
    fun signed(): SelfOwnedSignedDocumentV1 {
        val copy = field(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
        return try { requireNotNull(SelfOwnedInstallationWireV1.decodeSigned(copy)) } finally { copy.fill(0) }
    }
    fun target(): SelfOwnedTargetDescriptorV1 = SelfOwnedTargetDescriptorV1(string(255), int(), string(64), string(64))
    fun registryNamespace(): SelfOwnedRegistryNamespaceV1 = SelfOwnedRegistryNamespaceV1(string(4096), int(), string(36), long(), long())
    fun coordinates(): SelfOwnedAllocationCoordinatesV1 {
        val allocation = id(); val scope = BranchScope(id(), id(), id())
        return SelfOwnedAllocationCoordinatesV1(allocation, scope, id(), id(), id(), id(), id(), id(), id(), id())
    }
    fun finish() { require(at == bytes.size) }
}
