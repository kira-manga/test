package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** A NEW action-specific preparation profile, not either enrolled V1 head purpose. */
public object LocalDevicePairingProfileV1 {
    public const val PURPOSE: String = "EXPLICIT_NAMED_COMPANY_HEAD_ONE_NEW_DEVICE_PAIRING_P256_V1"
    public const val UNSIGNED_RETIREMENT_PURPOSE: String = "EXPLICIT_ORIGINAL_KNOWN_UNSIGNED_DEVICE_PAIRING_RETIREMENT_DATA_P256_V1"
    public const val MAXIMUM_ACTIONS: Int = 256
    public const val MAXIMUM_DEFINITION_BYTES: Int = 2 * 1024 * 1024
    public const val MAXIMUM_CHALLENGE_BYTES: Int = MAXIMUM_DEFINITION_BYTES + 16 * 1024
    public const val MAXIMUM_POSSESSION_BYTES: Int = 2048
    public const val MAXIMUM_RETIREMENT_BYTES: Int = MAXIMUM_CHALLENGE_BYTES + 8192 +
        SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES
    internal const val MAXIMUM_STATE_BYTES: Int = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES
    internal const val MAXIMUM_PLAINTEXT_BYTES: Int = MAXIMUM_STATE_BYTES - 2048
    internal const val COMPLETION_RESERVATION_BYTES: Int = 8192
}

/**
 * Canonical public target DATA. A point/fingerprint or a decoded instance proves neither
 * Android custody/nonexportability nor current physical transport membership. The actual
 * Android target producer supplies its own checked native point; the original owner still
 * independently verifies challenge possession and the NEW full-definition head ceremony.
 */
public class LocalDevicePairingTargetDescriptor private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    private val value = NativeDevicePairingWireV1.decodeTarget(bytes)
    public val admission: LocalDeviceAdmission get() = value.admission
    public val keyId: DomainId get() = value.keyId
    public val descriptorDigest: PayloadDigest = sha256(bytes)
    public fun copyPublicPoint(): ByteArray = value.point.copyBytes()
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "LocalDevicePairingTargetDescriptor([REDACTED])"

    public companion object {
        /** Pure DATA capture, never an attestation, peer factory or device-admission grant. */
        public fun capture(admission: LocalDeviceAdmission, keyId: DomainId, publicPoint: ByteArray): LocalDevicePairingTargetDescriptor {
            val fields = DevicePairingTargetFieldsV1(admission, keyId, SelfOwnedOctetsV1(publicPoint, 65))
            val bytes = NativeDevicePairingWireV1.encodeTarget(fields)
            return try { LocalDevicePairingTargetDescriptor(bytes) } finally { bytes.fill(0) }
        }
        public fun decode(canonical: ByteArray): LocalDevicePairingTargetDescriptor {
            require(canonical.size in 1..ProtectedLocalTable.ADMISSION.maximumPlaintextBytes + 1024)
            val result = LocalDevicePairingTargetDescriptor(canonical)
            val encoded = NativeDevicePairingWireV1.encodeTarget(result.value)
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return result
        }
    }
}

/** Complete requested revision-zero admission and protected-census DATA, not authority. */
public class LocalDevicePairingDefinition private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    private val value = NativeDevicePairingWireV1.decodeDefinition(bytes)
    public val request: LocalProvisioningRequest get() = value.request
    public val target: LocalDevicePairingTargetDescriptor get() = value.target
    public val admission: LocalDeviceAdmission get() = value.target.admission
    public val retainedAdmissions: List<LocalDeviceAdmission> get() = value.admissions.toList()
    /** Explicit V2 DATA captured before this action. Legacy V1 definitions have none. */
    public val sdkIdentity: LocalDevicePairingSdkIdentityV2? get() = value.sdkIdentity
    /** Additive V3 selection DATA, not an authenticated Master pin. Legacy definitions have none. */
    public val masterSdkOriginal: LocalMasterSdkOriginalV1? get() = value.masterSdkOriginal
    public val purpose: String get() = if (sdkIdentity == null) LocalDevicePairingProfileV1.PURPOSE else LocalDevicePairingProfileV2.PURPOSE
    public val definitionDigest: PayloadDigest = sha256(bytes)
    public fun copyCanonicalServiceAdmission(): ByteArray = value.serviceAdmission.copyBytes()
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "LocalDevicePairingDefinition([REDACTED])"

    public companion object {
        /** The actual Room bridge regenerates the existing protected admission codec. */
        public fun capture(requestId: DomainId, target: LocalDevicePairingTargetDescriptor,
            canonicalServiceAdmission: ByteArray, retainedAdmissions: List<LocalDeviceAdmission>): LocalDevicePairingDefinition {
            val request = localDevicePairingRequestV1(target.admission.scope, requestId,
                target.admission.deviceId, canonicalServiceAdmission.copyOf())
            val fields = DevicePairingDefinitionFieldsV1(request, target,
                SelfOwnedOctetsV1(canonicalServiceAdmission, ProtectedLocalTable.ADMISSION.maximumPlaintextBytes),
                retainedAdmissions.sortedBy { it.deviceId.value })
            val bytes = NativeDevicePairingWireV1.encodeDefinition(fields)
            return try { LocalDevicePairingDefinition(bytes) } finally { bytes.fill(0) }
        }
        /** Complete new-profile DATA only; the real target possession and fresh named-head
         * ceremony must cover its exact SDK descriptor before any admission consumer. */
        public fun captureSdkBound(requestId: DomainId, target: LocalDevicePairingTargetDescriptor,
            canonicalServiceAdmission: ByteArray, retainedAdmissions: List<LocalDeviceAdmission>,
            sdkIdentity: LocalDevicePairingSdkIdentityV2): LocalDevicePairingDefinition {
            val request = localDevicePairingRequestV1(target.admission.scope, requestId,
                target.admission.deviceId, canonicalServiceAdmission.copyOf())
            val fields = DevicePairingDefinitionFieldsV1(request, target,
                SelfOwnedOctetsV1(canonicalServiceAdmission, ProtectedLocalTable.ADMISSION.maximumPlaintextBytes),
                retainedAdmissions.sortedBy { it.deviceId.value }, sdkIdentity)
            val bytes = NativeDevicePairingWireV1.encodeDefinition(fields)
            return try { LocalDevicePairingDefinition(bytes) } finally { bytes.fill(0) }
        }
        /** New Master-bound definition DATA. The actual native producer independently checks
         * this exact original against authenticated record6 before every protected advancement. */
        public fun captureMasterBound(requestId: DomainId, target: LocalDevicePairingTargetDescriptor,
            canonicalServiceAdmission: ByteArray, retainedAdmissions: List<LocalDeviceAdmission>,
            sdkIdentity: LocalDevicePairingSdkIdentityV2, masterSdkOriginal: LocalMasterSdkOriginalV1): LocalDevicePairingDefinition {
            val request = localDevicePairingRequestV1(target.admission.scope, requestId,
                target.admission.deviceId, canonicalServiceAdmission.copyOf())
            val fields = DevicePairingDefinitionFieldsV1(request, target,
                SelfOwnedOctetsV1(canonicalServiceAdmission, ProtectedLocalTable.ADMISSION.maximumPlaintextBytes),
                retainedAdmissions.sortedBy { it.deviceId.value }, sdkIdentity, masterSdkOriginal)
            val bytes = NativeDevicePairingWireV1.encodeDefinition(fields)
            return try { LocalDevicePairingDefinition(bytes) } finally { bytes.fill(0) }
        }
        public fun decode(canonical: ByteArray): LocalDevicePairingDefinition {
            require(canonical.size in 1..LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES)
            val result = LocalDevicePairingDefinition(canonical)
            val encoded = NativeDevicePairingWireV1.encodeDefinition(result.value)
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return result
        }
    }
}

/** Original ADMIT_DEVICE request framing, not a new persisted action tag or an effect permit.
 * The caller transfers the encoded admission buffer; no secret or authority is accepted. */
public fun localDevicePairingRequestV1(scope: BranchScope, requestId: DomainId, deviceId: DomainId,
    canonicalServiceAdmission: ByteArray): LocalProvisioningRequest {
    val prefix = "ukkera.admit-local-device/v1\u0000".encodeToByteArray()
    val fields = listOf(prefix, canonicalServiceAdmission)
    var joined: ByteArray? = null
    return try {
        val count = fields.sumOf { it.size.toLong() + 4L }
        require(canonicalServiceAdmission.size in 1..ProtectedLocalTable.ADMISSION.maximumPlaintextBytes &&
            count in 1L..LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES.toLong())
        val bytes = ByteArray(count.toInt()); joined = bytes
        var offset = 0
        for (field in fields) {
            bytes[offset++] = (field.size ushr 24).toByte(); bytes[offset++] = (field.size ushr 16).toByte()
            bytes[offset++] = (field.size ushr 8).toByte(); bytes[offset++] = field.size.toByte()
            field.copyInto(bytes, offset); offset += field.size
        }
        LocalProvisioningRequest(scope, requestId, LocalProvisioningAction.ADMIT_DEVICE, deviceId, sha256(bytes))
    } finally { joined?.fill(0); fields.forEach { it.fill(0) } }
}

internal data class DevicePairingTargetFieldsV1(val admission: LocalDeviceAdmission,
    val keyId: DomainId, val point: SelfOwnedOctetsV1) {
    init {
        require(admission.admitted && admission.revision == Revision(0) && admission.deviceId != keyId &&
            admission.publicKeyIdentifier == keyId.value && point.size == 65)
        val bytes = point.copyBytes()
        try { require(bytes[0].toInt() == 4 && admission.publicFingerprint == sha256(bytes).value) }
        finally { bytes.fill(0) }
    }
}

internal data class DevicePairingDefinitionFieldsV1(val request: LocalProvisioningRequest,
    val target: LocalDevicePairingTargetDescriptor, val serviceAdmission: SelfOwnedOctetsV1,
    val admissions: List<LocalDeviceAdmission>, val sdkIdentity: LocalDevicePairingSdkIdentityV2? = null,
    val masterSdkOriginal: LocalMasterSdkOriginalV1? = null) {
    init {
        require(request.action == LocalProvisioningAction.ADMIT_DEVICE && request.scope == target.admission.scope &&
            request.targetId == target.admission.deviceId && request.requestId != request.targetId &&
            request.requestId != target.keyId && serviceAdmission.size in 1..ProtectedLocalTable.ADMISSION.maximumPlaintextBytes)
        require(admissions.size in 1..256 && admissions.all { it.scope == request.scope && it.deviceId != request.targetId } &&
            admissions.map { it.deviceId }.distinct().size == admissions.size && admissions == admissions.sortedBy { it.deviceId.value })
        require(localDevicePairingRequestV1(request.scope, request.requestId, request.targetId,
            serviceAdmission.copyBytes()) == request)
        sdkIdentity?.let {
            val actual = target.copyWireBytes(); val associated = it.target.copyWireBytes()
            try { require(actual.contentEquals(associated)) } finally { actual.fill(0); associated.fill(0) }
            require(request.requestId != it.originalAcquisitionId && request.requestId != it.originalPreparationId)
        }
        masterSdkOriginal?.let { master ->
            val sdk = requireNotNull(sdkIdentity)
            require(master.allocation.coordinates.scope == request.scope &&
                master.allocation.coordinates.deviceId != request.targetId && master.appId == sdk.appId &&
                master.peerId != sdk.peerId && master.fingerprint != sdk.fingerprint &&
                master.originalAcquisitionId !in listOf(request.requestId, request.targetId, target.keyId,
                    sdk.originalAcquisitionId, sdk.originalPreparationId))
        }
    }
}
