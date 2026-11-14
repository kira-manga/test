package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** New preparation phases only. None is an admitted device, current peer or Room/RUN grant. */
public enum class LocalDevicePairingPhase {
    PREPARED, TARGET_PROVED, HEAD_CONSENT_INTENT, SIGNING_INTENT, APPROVED,
    UNSIGNED_RETIREMENT_SIGNING_INTENT, RETIRED_KNOWN_UNSIGNED,
}

public data class LocalDevicePairingReference(public val originalActionId: DomainId,
    public val originalRequestId: DomainId, public val deviceId: DomainId, public val keyId: DomainId,
    public val phase: LocalDevicePairingPhase)

/** Full unsigned challenge DATA. Decoding or signing it does NOT authenticate a master.
 * The actual target responds only to an explicit request, with its own native key. */
public class LocalDevicePairingChallenge private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    internal val body = NativeDevicePairingWireV1.decodeChallenge(bytes)
    public val originalActionId: DomainId get() = body.originalActionId
    public val requested: LocalDevicePairingDefinition get() = body.requested()
    public val rootFingerprint: String get() = body.origin.rootFingerprint
    public val cohostDeviceId: DomainId get() = body.origin.coordinates.deviceId
    public val companyName: String get() = body.preparedHead.companyName
    public val headName: String get() = body.preparedHead.headName
    public val headLogin: String get() = body.preparedHead.headLogin
    public val challengeDigest: PayloadDigest = sha256(bytes)
    public val reviewContext: LocalDevicePairingChallengeReviewContext = LocalDevicePairingChallengeReviewContext(body)
    public val purpose: String get() = requested.purpose
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "LocalDevicePairingChallenge([REDACTED])"
    public companion object {
        internal fun fromNative(body: NativeDevicePairingBodyV1): LocalDevicePairingChallenge {
            val bytes = NativeDevicePairingWireV1.encodeChallenge(body)
            return try { LocalDevicePairingChallenge(bytes) } finally { bytes.fill(0) }
        }
        public fun decode(canonical: ByteArray): LocalDevicePairingChallenge {
            require(canonical.size in 1..LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES)
            val result = LocalDevicePairingChallenge(canonical)
            val bytes = NativeDevicePairingWireV1.encodeChallenge(result.body)
            try { require(bytes.contentEquals(canonical)) } finally { bytes.fill(0) }
            return result
        }
    }
}

/** Public signature DATA only. Verification proves key possession, not remote Android
 * attestation/nonexportability, physical-peer lifetime or company/head authority. */
public class LocalDevicePairingPossession private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    internal val value = NativeDevicePairingWireV1.decodePossession(bytes)
    public val originalActionId: DomainId get() = value.originalActionId
    public val challengeDigest: PayloadDigest get() = value.challengeDigest
    public val deviceId: DomainId get() = value.deviceId
    public val keyId: DomainId get() = value.keyId
    public val descriptorDigest: PayloadDigest get() = value.descriptorDigest
    public val sdkIdentityDigest: PayloadDigest? get() = value.sdkIdentityDigest
    public fun copySignature(): ByteArray = value.signature.copyBytes()
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "LocalDevicePairingPossession([REDACTED])"
    public companion object {
        /** DATA capture; the original producer independently verifies this signature. */
        public fun capture(challenge: LocalDevicePairingChallenge, signature: ByteArray): LocalDevicePairingPossession {
            val target = challenge.requested.target
            val value = NativeDevicePairingPossessionV1(challenge.originalActionId, challenge.challengeDigest,
                target.admission.deviceId, target.keyId, target.descriptorDigest, SelfOwnedOctetsV1(signature, 64),
                challenge.requested.sdkIdentity?.descriptorDigest)
            val bytes = NativeDevicePairingWireV1.encodePossession(value)
            return try { LocalDevicePairingPossession(bytes) } finally { bytes.fill(0) }
        }
        public fun decode(canonical: ByteArray): LocalDevicePairingPossession {
            require(canonical.size in 1..LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES)
            val result = LocalDevicePairingPossession(canonical)
            val bytes = NativeDevicePairingWireV1.encodePossession(result.value)
            try { require(bytes.contentEquals(canonical)) } finally { bytes.fill(0) }
            return result
        }
    }
}

/** Exact actual-owner review. Stale/partial reviews remain DATA, never replay authority. */
public class LocalDevicePairingAction internal constructor(internal val owner: Any,
    internal val record: NativeDevicePairingRecordV1) {
    public val originalActionId: DomainId get() = record.body.originalActionId
    public val requested: LocalDevicePairingDefinition get() = record.body.requested()
    public val challenge: LocalDevicePairingChallenge get() = LocalDevicePairingChallenge.fromNative(record.body)
    public val phase: LocalDevicePairingPhase get() = record.phase
    public val possession: LocalDevicePairingPossession? get() = record.possession?.let {
        val bytes = it.copyBytes(); try { LocalDevicePairingPossession.decode(bytes) } finally { bytes.fill(0) }
    }
    public val purpose: String get() = requested.purpose
    override fun toString(): String = "LocalDevicePairingAction([REDACTED])"
}

/** Original-root endorsed one-action DATA, with no conversion to a provisioning permit,
 * peer/session, journal custody, admitted-device row or automatic future enrollment. */
public class LocalDevicePairingApproval internal constructor(internal val record: NativeDevicePairingRecordV1,
    private val originalRoot: SelfOwnedSignedDocumentV1? = null) {
    init {
        require(record.phase == LocalDevicePairingPhase.APPROVED)
        originalRoot?.let { root ->
            require(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT)
            val bytes = root.copyWireBytes()
            try { require(sha256(bytes) == record.body.origin.rootDocumentDigest) }
            finally { bytes.fill(0) }
        }
    }
    public val originalActionId: DomainId get() = record.body.originalActionId
    public val requested: LocalDevicePairingDefinition get() = record.body.requested()
    /** Included immutable Master DATA only. Legacy originals retain their explicit absence. */
    public val masterSdkOriginal: LocalMasterSdkOriginal? get() = requested.masterSdkOriginal
    public val challengeDigest: PayloadDigest get() = LocalDevicePairingChallenge.fromNative(record.body).challengeDigest
    public val purpose: String get() = requested.purpose
    public val authorityDigest: PayloadDigest get() {
        val bytes = copyAuthorityCertificate(); return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    public fun copyAuthorityCertificate(): ByteArray = NativeDevicePairingWireV1.certificate(record)
    /** Exact original public root DATA supplied by the actual producer, not a new signature
     * or authority. DATA-only callers without that source cannot manufacture its export. */
    public fun copyRootAnnouncement(): ByteArray = checkNotNull(originalRoot) {
        "Original root announcement is unavailable"
    }.copyWireBytes()
    override fun toString(): String = "LocalDevicePairingApproval([REDACTED])"
}

/** Explicit terminal DATA from the actual original producer, never head approval,
 * revocation, admission, remote trust or permission. Pure decoding is not verification. */
public class LocalDevicePairingUnsignedRetirement private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    internal val value = NativeDevicePairingWireV1.decodeRetirementCertificate(bytes)
    internal val record: NativeDevicePairingRecordV1 get() = value.record
    public val originalActionId: DomainId get() = record.body.originalActionId
    public val requested: LocalDevicePairingDefinition get() = record.body.requested()
    public val originalChallenge: LocalDevicePairingChallenge get() = LocalDevicePairingChallenge.fromNative(record.body)
    public val dispositionDigest: PayloadDigest get() = NativeDevicePairingWireV1.retirementLinkDigest(record)
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    override fun toString(): String = "LocalDevicePairingUnsignedRetirement([REDACTED])"
    public companion object {
        internal fun fromNative(record: NativeDevicePairingRecordV1, root: SelfOwnedSignedDocumentV1): LocalDevicePairingUnsignedRetirement {
            val rootBytes = root.copyWireBytes()
            val bytes = try { NativeDevicePairingWireV1.retirementCertificate(record,
                SelfOwnedOctetsV1(rootBytes, SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)) }
            finally { rootBytes.fill(0) }
            return try { LocalDevicePairingUnsignedRetirement(bytes) } finally { bytes.fill(0) }
        }
        public fun decode(canonical: ByteArray): LocalDevicePairingUnsignedRetirement {
            require(canonical.size in 1..LocalDevicePairingProfileV1.MAXIMUM_RETIREMENT_BYTES)
            val result = LocalDevicePairingUnsignedRetirement(canonical)
            val bytes = NativeDevicePairingWireV1.retirementCertificate(result.record, result.value.rootDocument)
            try { require(bytes.contentEquals(canonical)) } finally { bytes.fill(0) }
            return result
        }
    }
}

/** Actual native original owner only; no public constructor, supplied signer or current callback. */
public class LocalCompanyDevicePairings internal constructor(private val controller: NativeLocalDevicePairingController) {
    public fun belongsTo(custody: SelfOwnedNativeHistoryCustody): Boolean = controller === custody.controller
    public suspend fun prepare(requested: LocalDevicePairingDefinition, original: OriginalNativeHistorySnapshot):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> = controller.prepareDevicePairing(requested, original)
    public suspend fun proveTarget(action: LocalDevicePairingAction, possession: LocalDevicePairingPossession,
        original: OriginalNativeHistorySnapshot): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
        controller.proveDevicePairingTarget(action, possession, original)
    /** Explicit invocation consents to THIS new purpose/action only, before real head authentication. */
    public suspend fun approve(action: LocalDevicePairingAction, headLogin: String, headSecret: CharArray):
        LocalCompanyAuthorityResult<LocalDevicePairingApproval> = controller.approveDevicePairing(action, headLogin, headSecret)
    public suspend fun review(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction> =
        controller.reviewDevicePairing(originalActionId)
    /** Existing DATA only: no head password, pairing signature/key/ID/deadline renewal or dispatch. */
    public suspend fun recoverApproval(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingApproval> =
        controller.recoverDevicePairingApproval(originalActionId)
    public suspend fun retained(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>> = controller.retainedDevicePairings()
    /** Separately explicit known-no-pairing-signature DATA disposition; no old grant inference. */
    public suspend fun retireKnownUnsigned(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
        controller.retireKnownUnsignedDevicePairing(originalActionId)
    /** Exact completed terminal DATA only, with no new signature or deadline/cut requirement. */
    public suspend fun recoverUnsignedRetirement(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> =
        controller.recoverUnsignedDevicePairingRetirement(originalActionId)
    /** Explicit actual-owner consumption of the exact retained APPROVED V2 original.
     * Knowing an ID/certificate cannot create a dispatch or renew its original authority. */
    public suspend fun beginDeviceAdmission(originalActionId: DomainId): NativeLocalDeviceAdmissionDispatch =
        (controller as? NativeLocalDeviceAdmissionController)?.beginDeviceAdmission(originalActionId)
            ?: throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    /** Negative-only UNUSED terminalization, after the exact endpoint's actual cleanup.
     * The native owner independently requires no bound frame/child and settled original fate. */
    public suspend fun finishKnownNoEffect(dispatch: NativeLocalDeviceAdmissionDispatch): Boolean =
        (controller as? NativeLocalDeviceAdmissionController)?.finishUnusedDeviceAdmission(dispatch) ?: false
    /** Exact native/protected terminal receipt eligibility only; never redispatch authority. */
    public suspend fun lookupOriginalDeviceAdmission(originalActionId: DomainId): LocalDeviceAdmissionReceiptPermit? =
        (controller as? NativeLocalDeviceAdmissionController)?.lookupOriginalDeviceAdmission(originalActionId)
    override fun toString(): String = "LocalCompanyDevicePairings([REDACTED])"
    public companion object {
        public suspend fun acquireOriginal(custody: SelfOwnedNativeHistoryCustody): LocalCompanyAuthorityResult<LocalCompanyDevicePairings> =
            (custody.controller as? NativeLocalDevicePairingController)?.readOriginalDevicePairings()
                ?: LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.ORIGINAL_TARGET_OWNER_REQUIRED)
    }
}

internal interface NativeLocalDevicePairingController {
    suspend fun readOriginalDevicePairings(): LocalCompanyAuthorityResult<LocalCompanyDevicePairings>
    suspend fun prepareDevicePairing(requested: LocalDevicePairingDefinition, original: OriginalNativeHistorySnapshot):
        LocalCompanyAuthorityResult<LocalDevicePairingAction>
    suspend fun proveDevicePairingTarget(action: LocalDevicePairingAction, possession: LocalDevicePairingPossession,
        original: OriginalNativeHistorySnapshot): LocalCompanyAuthorityResult<LocalDevicePairingAction>
    suspend fun approveDevicePairing(action: LocalDevicePairingAction, login: String, secret: CharArray):
        LocalCompanyAuthorityResult<LocalDevicePairingApproval>
    suspend fun reviewDevicePairing(id: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingAction>
    suspend fun recoverDevicePairingApproval(id: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingApproval>
    suspend fun retainedDevicePairings(): LocalCompanyAuthorityResult<List<LocalDevicePairingReference>>
    suspend fun retireKnownUnsignedDevicePairing(id: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement>
    suspend fun recoverUnsignedDevicePairingRetirement(id: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement>
}
