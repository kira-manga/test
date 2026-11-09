package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Complete origin and prepared-head facts already carried by the unsigned challenge.
 * These are historical public DATA, not a current observation, authenticated Master,
 * renewed deadline, native owner or permission to perform any protected operation.
 */
public class LocalDevicePairingChallengeReviewContext internal constructor(body: NativeDevicePairingBodyV1) {
    public val coordinates: SelfOwnedAllocationCoordinatesV1 = body.origin.coordinates
    public val allocationDigest: PayloadDigest = body.origin.allocationDigest
    public val bindingDigest: PayloadDigest = body.origin.bindingDigest
    public val installationRequestId: DomainId = body.origin.installationRequestId
    public val installationDefinitionDigest: PayloadDigest = body.origin.installationDefinitionDigest
    public val installationReceiptDigest: PayloadDigest = body.origin.installationReceiptDigest
    public val authorityEpoch: Revision = body.origin.authorityEpoch
    public val preparationParent: NativeHistoryRecordedHeadV2 = body.origin.preparationParent
    public val preparationAnchorDigest: PayloadDigest = body.origin.preparationAnchorDigest
    public val rootId: DomainId = body.origin.rootId
    public val rootKeyId: DomainId = body.origin.rootKeyId
    public val rootDocumentDigest: PayloadDigest = body.origin.rootDocumentDigest
    public val rootFingerprint: String = body.origin.rootFingerprint
    public val foundingActionId: DomainId = body.origin.foundingActionId
    public val headAuthorityId: DomainId = body.origin.headAuthorityId
    public val headEnrollmentId: DomainId = body.origin.headEnrollmentId
    public val accountRevision: Long = body.preparedHead.accountRevision
    public val credentialRevision: Long = body.preparedHead.credentialRevision
    public val retainedRevision: Long = body.preparedHead.retainedRevision
    public val osBootCount: Int = body.preparedHead.osBootCount
    public val observationIssuerContextId: DomainId = body.preparedHead.observation.issuerContextId
    public val observationBootId: DomainId = body.preparedHead.observation.moment.bootId
    public val observationElapsedMillis: Long = body.preparedHead.observation.moment.elapsedMillis
    public val deadlineElapsedMillis: Long = body.preparedHead.deadlineElapsedMillis
    public val previousUnsignedRetirementDigest: PayloadDigest? = body.previousUnsignedRetirementDigest
    private val nonce = body.nonce.copyBytes()
    public val nonceDigest: PayloadDigest = sha256(nonce)
    public fun copyNonceBytes(): ByteArray = nonce.copyOf()
    override fun toString(): String = "LocalDevicePairingChallengeReviewContext([REDACTED])"
}
