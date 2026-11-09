package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal enum class OfflineDocumentRole { TRUST_STATE, SOURCE, HUMAN_DELEGATION, EXACT_REQUEST_APPROVAL, CUSTODY_BINDING, CUSTODY_LEASE }
@Serializable
internal enum class OfflineIssuerRole { SOURCE, HUMAN_DELEGATION, EXACT_REQUEST_APPROVAL, CUSTODY }

internal interface OfflineSignedBody {
    val schema: String
    val documentId: DomainId
    val issuerKeyId: DomainId
    val scope: BranchScope
    val validity: OfflineInstallationUtcWindow
}

@Serializable
internal class OfflineIssuerWire(
    val keyId: DomainId,
    val role: OfflineIssuerRole,
    val scope: BranchScope,
    val publicKeyHex: String,
    val validity: OfflineInstallationUtcWindow,
)

@Serializable
internal class OfflineTrustStateBody(
    override val schema: String,
    override val documentId: DomainId,
    override val issuerKeyId: DomainId,
    override val scope: BranchScope,
    override val validity: OfflineInstallationUtcWindow,
    val rootEnrollmentId: DomainId,
    val rootRevision: Revision,
    val rootPinsDigest: PayloadDigest,
    val generation: Revision,
    val issuers: List<OfflineIssuerWire>,
    val revokedKeyIds: List<DomainId>,
    val revokedDocumentIds: List<DomainId>,
    val revokedInstallationIds: List<DomainId>,
    val revokedAssignmentIds: List<DomainId>,
    val revokedHumanIds: List<DomainId>,
) : OfflineSignedBody

/** Descriptor strings are derived from THIS key; arbitrary optional admission descriptors are not allowed. */
@Serializable
internal class OfflineDeviceWire(
    val deviceId: DomainId,
    val keyId: DomainId,
    val publicKeyHex: String,
    val capabilities: List<String>,
) {
    fun requireValid() {
        check(offlinePublicKeySyntax(publicKeyHex))
        check(capabilities.size <= ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES)
        check(capabilities.all { it.length in 1..ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES &&
            it.all { character -> character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' || character in "._-" } })
        check(capabilities == capabilities.distinct().sorted())
    }
    fun keyIdentifier(): String = "ed25519:${keyId.value}"
    fun fingerprint(): String {
        val bytes = offlinePublicKeyBytes(publicKeyHex)
        return try { "sha256:${sha256(bytes).value}" } finally { bytes.fill(0) }
    }
    fun digest(): PayloadDigest {
        val body = OfflineInstallationWire.encode(serializer(), this, 8192)
        return try { offlineHashFrame("ukkera.offline-installation/device-binding/v1", listOf(body)) }
        finally { body.fill(0) }
    }
}

/** Stable body has NO source self-hash, delegation hash or full requested-definition digest. */
@Serializable
internal class OfflineSourceBody(
    override val schema: String,
    override val documentId: DomainId,
    override val issuerKeyId: DomainId,
    override val scope: BranchScope,
    override val validity: OfflineInstallationUtcWindow,
    val installationId: DomainId,
    val assignmentId: DomainId,
    val authorityEpoch: Revision,
    val firstActorId: DomainId,
    val responsibleHumanId: DomainId,
    val device: OfflineDeviceWire,
    val storageInstanceId: DomainId,
    val storageKeyId: DomainId,
    val pepperKeyId: DomainId,
    val custodyReference: PayloadDigest,
    val freshHistoryOriginReference: PayloadDigest,
    val monotonicContextReference: PayloadDigest,
    val configurationProfile: String,
) : OfflineSignedBody

/** Delegates only this exact source/installation to a separate named human; no subdelegation. */
@Serializable
internal class OfflineHumanDelegationBody(
    override val schema: String,
    override val documentId: DomainId,
    override val issuerKeyId: DomainId,
    override val scope: BranchScope,
    override val validity: OfflineInstallationUtcWindow,
    val installationId: DomainId,
    val assignmentId: DomainId,
    val masterDeviceId: DomainId,
    val authorityEpoch: Revision,
    val sourceId: DomainId,
    val sourceDigest: PayloadDigest,
    val humanId: DomainId,
    val humanKeyId: DomainId,
    val humanPublicKeyHex: String,
    val action: String,
) : OfflineSignedBody

/** Detached approval may now commit the complete request without self-referential hashes. */
@Serializable
internal class OfflineExactRequestApprovalBody(
    override val schema: String,
    override val documentId: DomainId,
    override val issuerKeyId: DomainId,
    override val scope: BranchScope,
    override val validity: OfflineInstallationUtcWindow,
    val delegationId: DomainId,
    val request: LocalBranchInstallationRequest,
) : OfflineSignedBody

/** Stable native origin/keys, separate from the boot/service/session-specific short lease. */
@Serializable
internal class OfflineCustodyBindingBody(
    override val schema: String,
    override val documentId: DomainId,
    override val issuerKeyId: DomainId,
    override val scope: BranchScope,
    override val validity: OfflineInstallationUtcWindow,
    val installationId: DomainId,
    val assignmentId: DomainId,
    val masterDeviceId: DomainId,
    val authorityEpoch: Revision,
    val deviceKeyId: DomainId,
    val devicePublicKeyHex: String,
    val nativeOwnerId: DomainId,
    val storageInstanceId: DomainId,
    val storageKeyId: DomainId,
    val pepperKeyId: DomainId,
    val custodyKeyId: DomainId,
    val custodyPublicKeyHex: String,
    val namespace: OfflineInstallationNamespace,
    val freshOriginId: DomainId,
    val monotonicContextId: DomainId,
    val passwordHashIterations: Int,
) : OfflineSignedBody

@Serializable
internal class OfflineCustodyLeaseBody(
    override val schema: String,
    override val documentId: DomainId,
    override val issuerKeyId: DomainId,
    override val scope: BranchScope,
    override val validity: OfflineInstallationUtcWindow,
    val installationId: DomainId,
    val nativeOwnerId: DomainId,
    val custodyBindingDigest: PayloadDigest,
    val rootPinsDigest: PayloadDigest,
    val trustGeneration: Revision,
    val trustStateDigest: PayloadDigest,
    val issuerContextId: DomainId,
    val validFrom: MonotonicMoment,
    @Serializable(with = MinorUnitsSerializer::class)
    val expiresAtElapsedMillis: Long,
    val humanId: DomainId,
    val humanKeyId: DomainId,
    val humanPublicKeyHex: String,
    val humanSessionId: DomainId,
) : OfflineSignedBody

@Serializable
internal class OfflineRootPinWire(
    val keyId: DomainId,
    val company: CompanyScope,
    val publicKeyHex: String,
    val validity: OfflineInstallationUtcWindow,
)

@Serializable
internal class OfflineRootPinsWire(
    val schema: String,
    val enrollmentId: DomainId,
    val revision: Revision,
    val minimumTrustGeneration: Revision,
    val pins: List<OfflineRootPinWire>,
)

/** Owned canonical bytes/signature live only during the actual factory verification. */
internal class OfflineParsedDocument<T : OfflineSignedBody>(
    val body: T,
    val digest: PayloadDigest,
    private val bytes: ByteArray,
    private val signature: ByteArray,
) {
    fun verify(keyHex: String, role: OfflineDocumentRole): Boolean {
        val roleBytes = role.name.encodeToByteArray()
        val framed = try { offlineFrame("ukkera.offline-installation/document-signature/v1", listOf(roleBytes, bytes)) }
        finally { roleBytes.fill(0) }
        return try {
            val key = offlinePublicKeyBytes(keyHex)
            try { offlineInstallationVerifyEd25519(key, framed, signature) } finally { key.fill(0) }
        } finally { framed.fill(0) }
    }
    fun clear() { bytes.fill(0); signature.fill(0) }
}

internal object OfflineInstallationWire {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false
        prettyPrint = false
    }

    fun <T : OfflineSignedBody> decode(
        document: OfflineInstallationSignedDocument,
        serializer: KSerializer<T>,
        maximumBytes: Int,
    ): OfflineParsedDocument<T> {
        check(document.bodySize in 1..maximumBytes && maximumBytes <= OfflineInstallationFormat.MAX_DOCUMENT_BYTES)
        val bytes = document.copyBody()
        var retained = false
        try {
            preflight(bytes)
            val body = json.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
            val canonical = encode(serializer, body, maximumBytes)
            try { check(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
            val parsed = OfflineParsedDocument(body, sha256(bytes), bytes, document.copySignature())
            retained = true
            return parsed
        } finally { if (!retained) bytes.fill(0) }
    }

    fun <T> encode(serializer: KSerializer<T>, value: T, maximumBytes: Int): ByteArray {
        val bytes = json.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
        try {
            check(bytes.size in 1..maximumBytes && maximumBytes <= OfflineInstallationFormat.MAX_DOCUMENT_BYTES)
            preflight(bytes)
            return bytes
        } catch (failure: Exception) { bytes.fill(0); throw failure }
    }

    /** No whitespace/escapes/non-ASCII, bounded strings and depth BEFORE recursive JSON parsing. */
    private fun preflight(bytes: ByteArray) {
        var depth = 0
        var inString = false
        var stringLength = 0
        for (byte in bytes) {
            val character = byte.toInt() and 0xff
            check(character in 0x21..0x7e)
            if (inString) {
                if (character == 0x22) { inString = false }
                else { check(character != 0x5c && ++stringLength <= 128) }
            } else when (character) {
                0x22 -> { inString = true; stringLength = 0 }
                0x7b, 0x5b -> { depth++; check(depth <= 12) }
                0x7d, 0x5d -> { depth--; check(depth >= 0) }
            }
        }
        check(!inString && depth == 0)
    }
}

internal fun offlinePublicKeySyntax(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun offlinePublicKeyBytes(value: String): ByteArray {
    check(offlinePublicKeySyntax(value))
    return ByteArray(32) { index -> value.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

/** Unsigned 32-bit big-endian field count followed by each unsigned length and exact bytes. */
internal fun offlineFrame(domain: String, fields: List<ByteArray>): ByteArray {
    check(domain.length in 1..128 && fields.size <= 63)
    val header = domain.encodeToByteArray()
    try {
        val size = 4L + 4 + header.size + fields.sumOf { 4L + it.size }
        check(size in 1..(64L * 1024))
        val output = ByteArray(size.toInt())
        var offset = 0
        fun integer(value: Int) {
            output[offset++] = (value ushr 24).toByte(); output[offset++] = (value ushr 16).toByte()
            output[offset++] = (value ushr 8).toByte(); output[offset++] = value.toByte()
        }
        fun field(value: ByteArray) { integer(value.size); value.copyInto(output, offset); offset += value.size }
        integer(fields.size + 1)
        field(header)
        fields.forEach(::field)
        return output
    } finally { header.fill(0) }
}

internal fun offlineFrameStrings(domain: String, fields: List<String>): ByteArray {
    val bytes = fields.map(String::encodeToByteArray)
    return try { offlineFrame(domain, bytes) } finally { bytes.forEach { it.fill(0) } }
}

internal fun offlineHashFrame(domain: String, fields: List<ByteArray>): PayloadDigest {
    val bytes = offlineFrame(domain, fields)
    return try { sha256(bytes) } finally { bytes.fill(0) }
}

internal fun offlineConstantEquals(first: ByteArray, second: ByteArray): Boolean {
    if (first.size != second.size) return false
    var difference = 0
    for (index in first.indices) difference = difference or (first[index].toInt() xor second[index].toInt())
    return difference == 0
}
