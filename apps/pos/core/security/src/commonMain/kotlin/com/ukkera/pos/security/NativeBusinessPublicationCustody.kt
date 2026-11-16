package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Native IO/keys/head ceremony only. Possessing these data projections never grants authority. */
internal interface NativeBusinessPublicationCustody {
    fun requireOriginalObservation()
    fun requireNewIssuance()
    fun assertContinuity()
    val boot: Int
    fun clock(): Long
    fun elapsedMillis(): Long
    fun info(message: String)
    fun newId(): DomainId
    fun companyOriginal(): SelfOwnedCompanyBootstrapView?
    fun businessState(): NativeBusinessPublicationCompany
    fun rootFingerprint(): String
    fun readBusinessPublications(): NativeCompanyBusinessPublicationStateV1?
    fun retainBusinessPublications(next: NativeCompanyBusinessPublicationStateV1): NativeCompanyBusinessPublicationStateV1
    fun readOriginalSerialCustody(): OriginalLocalSerialCustodyReferenceV1
    fun recoverOriginalSerialCustody(originalActionId: DomainId): OriginalLocalSerialCustodyReferenceV1
    fun actualSerialIssuerOrigin(original: OriginalNativeHistorySnapshot): LocalCompanySerialIssuerOriginV1
    fun devicePairingReservedIdentityIds(): Set<DomainId>
    fun readEmployeeEnrollments(): NativeEmployeeEnrollmentStateV1?
    fun authenticatePublication(actionId: DomainId, digest: PayloadDigest, login: String, secret: CharArray,
        deadline: Long): NativeBusinessPublicationApproval
    fun requirePublicationStillCurrent(approval: NativeBusinessPublicationApproval, actionId: DomainId, digest: PayloadDigest)
    fun signBusinessPublicationEndorsement(record: NativeCompanyBusinessPublicationRecordV1,
        approval: NativeBusinessPublicationApproval): ByteArray
    fun verifyBusinessPublication(record: NativeCompanyBusinessPublicationRecordV1)
    fun newContentKey(): NativeBusinessPublicationContentKey
}

internal class NativeBusinessPublicationCompany(val head: LocalCompanyHeadAccountV1,
    val serialCustody: NativeCompanySerialAuthorityCustodyV1?)

/** Opaque, invocation-local native head approval. Currentness is always rechecked by its owner. */
internal interface NativeBusinessPublicationApproval {
    val accountRevision: Long
    val credentialRevision: Long
    val boot: Int
    val expires: Long
}

/** Fresh ephemeral content key; implementations destroy its secret on every outcome. */
internal interface NativeBusinessPublicationContentKey {
    fun publicPoint(): ByteArray
    fun sign(content: ByteArray): ByteArray
    fun clear()
}
