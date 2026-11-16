package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import org.bouncycastle.math.ec.rfc8032.Ed25519

/** Native execution/crypto adapter for the common publication state machine. */
internal class JcaBusinessPublicationCustody(
    private val environment: SelfOwnedOriginalEnvironment,
    private val work: AndroidOwnedInstallationWork,
    private val issuance: AndroidLocalCompanyIssuanceGate,
    private val custody: SelfOwnedCompanyCustody,
) : NativeBusinessPublicationCustody {
    override fun requireOriginalObservation() {
        work.requireActive(); environment.requireExistingOriginalObservation(); custody.assertContinuity()
    }
    override fun requireNewIssuance() { requireOriginalObservation(); issuance.requireIssuance(null) }
    override fun assertContinuity() = custody.assertContinuity()
    override val boot get() = environment.bootCount
    override fun clock() = work.clock()
    override fun elapsedMillis() = work.platform.elapsedMillis()
    override fun info(message: String) = work.platform.info(message)
    override fun newId() = JvmP256Primitives.newId()
    override fun companyOriginal() = custody.companyOriginal()
    override fun rootFingerprint() = custody.rootFingerprint()
    override fun businessState(): NativeBusinessPublicationCompany = when (val source = state().source) {
        is LocalCompanyAuthorityStateV1 -> NativeBusinessPublicationCompany(source.head, source.serialCustody)
        is LocalCompanyAuthorityStateV2 -> NativeBusinessPublicationCompany(source.head, source.serialCustody)
        else -> uncertain()
    }
    override fun readBusinessPublications() = custody.readBusinessPublications()
    override fun retainBusinessPublications(next: NativeCompanyBusinessPublicationStateV1) = custody.retainBusinessPublications(next)
    override fun readOriginalSerialCustody() = custody.readOriginalSerialCustody()
    override fun recoverOriginalSerialCustody(originalActionId: DomainId) = custody.recoverOriginalSerialCustody(originalActionId)
    override fun devicePairingReservedIdentityIds() = custody.devicePairingReservedIdentityIds()
    override fun readEmployeeEnrollments() = custody.readEmployeeEnrollments()
    override fun authenticatePublication(actionId: DomainId, digest: PayloadDigest, login: String, secret: CharArray,
        deadline: Long): NativeBusinessPublicationApproval = custody.authenticateBusinessPublication(actionId, digest, login, secret, deadline)
    override fun requirePublicationStillCurrent(approval: NativeBusinessPublicationApproval, actionId: DomainId, digest: PayloadDigest) =
        custody.requireBusinessPublicationStillCurrent(native(approval), state(), actionId, digest)
    override fun signBusinessPublicationEndorsement(record: NativeCompanyBusinessPublicationRecordV1,
        approval: NativeBusinessPublicationApproval) = custody.signBusinessPublicationEndorsement(record, native(approval))
    override fun verifyBusinessPublication(record: NativeCompanyBusinessPublicationRecordV1) = custody.verifyBusinessPublication(record)

    override fun actualSerialIssuerOrigin(original: OriginalNativeHistorySnapshot): LocalCompanySerialIssuerOriginV1 {
        requireOriginalObservation()
        val company = companyOriginal() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val current = state()
        val allocation = original.record.allocation
        val coordinates: SelfOwnedAllocationCoordinatesV1
        val phase: LocalProvisionerAllocationPhaseV1
        val root: SelfOwnedSignedDocumentV1
        val delegation: SelfOwnedSignedDocumentV1
        val preparationDigest: PayloadDigest
        when (val source = current.source) {
            is LocalCompanyAuthorityStateV1 -> {
                val assigned = source.original.allocations.singleOrNull() ?: uncertain()
                coordinates = assigned.body.coordinates; phase = assigned.phase
                root = source.original.root; delegation = source.original.delegation
                preparationDigest = PayloadDigest(JvmP256Primitives.documentDigest(assigned.grant ?: uncertain()))
            }
            is LocalCompanyAuthorityStateV2 -> {
                val assigned = source.original.allocations.singleOrNull() ?: uncertain()
                coordinates = assigned.body.coordinates; phase = assigned.phase
                root = source.original.root; delegation = source.original.delegation
                val bytes = (assigned.grant ?: uncertain()).copyWireBytes()
                preparationDigest = try { sha256(bytes) } finally { bytes.fill(0) }
            }
            else -> uncertain()
        }
        val scope = allocation.coordinates.scope
        val rootDigest = PayloadDigest(JvmP256Primitives.documentDigest(root))
        val issuerDigest = PayloadDigest(JvmP256Primitives.documentDigest(delegation))
        if (phase != LocalProvisionerAllocationPhaseV1.GRANT_RETAINED ||
            scope.company() != CompanyScope(company.original.organizationId, company.original.companyId) ||
            scope != coordinates.scope || allocation.coordinates.allocationId != coordinates.allocationId ||
            allocation.p1RootDocumentDigest != rootDigest || allocation.p1DelegationDigest != issuerDigest ||
            allocation.p1PreparationDigest != preparationDigest) uncertain()
        return LocalCompanySerialIssuerOriginV1(scope.company(), company.original.actionId, company.original.rootId,
            company.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId, rootDigest, rootFingerprint(), company.headAuthorityId,
            company.headEnrollmentId, company.original.issuerId, company.original.key(SelfOwnedNativeKeyRoleV1.ISSUER).keyId,
            issuerDigest, scope, allocation.coordinates.allocationId, allocation.digest)
    }
    override fun newContentKey(): NativeBusinessPublicationContentKey = JcaPublicationContentKey()
    private fun state() = custody.companyState() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
    private fun native(approval: NativeBusinessPublicationApproval) = approval as? SelfOwnedCompanyHeadApproval
        ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
}

/** Invocation-local Ed25519 key. It is never persisted, exported or recreated for a signing intent. */
private class JcaPublicationContentKey : NativeBusinessPublicationContentKey {
    private val seed = JvmP256Primitives.randomBytes(Ed25519.SECRET_KEY_SIZE)
    private var point: ByteArray? = null
    private var cleared = false
    override fun publicPoint(): ByteArray {
        check(!cleared)
        val generated = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
        try {
            Ed25519.generatePublicKey(seed, 0, generated, 0)
            if (!Ed25519.validatePublicKeyFull(generated, 0)) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            check(point == null); point = generated.copyOf()
            return generated
        } catch (failure: Throwable) { generated.fill(0); throw failure }
    }
    override fun sign(content: ByteArray): ByteArray {
        check(!cleared)
        val signature = ByteArray(Ed25519.SIGNATURE_SIZE)
        try { Ed25519.sign(seed, 0, checkNotNull(point), 0, content, 0, content.size, signature, 0); return signature }
        catch (failure: Throwable) { signature.fill(0); throw failure }
    }
    override fun clear() { cleared = true; seed.fill(0); point?.fill(0); point = null }
}
