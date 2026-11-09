package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** A new explicit native container, not a fifth member of the legacy four-role enum. */
internal enum class LocalCompanyHeadKeyRoleV1(val wireTag: String, val aliasPart: String) {
    POLICY("HEAD_POLICY_SIGNING", "head-policy"),
    INSTALLATION("HEAD_INSTALLATION_SIGNING", "head-installation"),
    PEPPER("HEAD_PEPPER", "head-pepper"),
}

internal data class LocalCompanyHeadKeyIntentV1(
    val role: LocalCompanyHeadKeyRoleV1,
    val keyId: DomainId,
    val keyRevision: Long,
    val phase: SelfOwnedKeyIntentPhaseV1,
    val startedBoot: Int?,
    val security: SelfOwnedNativeSecurityV1?,
    val proof: SelfOwnedOctetsV1,
) {
    init {
        // Explicit first key revision in this NEW profile; no retrofit to a legacy key.
        require(keyRevision == 0L && (startedBoot == null || startedBoot >= 0))
        when (phase) {
            SelfOwnedKeyIntentPhaseV1.NOT_STARTED -> require(startedBoot == null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.STARTED -> require(startedBoot != null && security == null && proof.size == 0)
            SelfOwnedKeyIntentPhaseV1.COMPLETE -> require(startedBoot != null && security != null &&
                proof.size == (if (role == LocalCompanyHeadKeyRoleV1.PEPPER) 32 else 65))
        }
    }
}

internal data class LocalCompanyAuthorityBootstrapV1(
    val revision: Long,
    val original: SelfOwnedBootstrapV1,
    val headAuthorityId: DomainId,
    val headEnrollmentId: DomainId,
    val headName: String,
    val headLogin: String,
    val lifetime: LocalCompanyHeadLifetimeV1,
    val headKeys: List<LocalCompanyHeadKeyIntentV1>,
) {
    init {
        require(revision in 0 until Long.MAX_VALUE && revision >= original.revision)
        localCompanyHeadName(headName); selfOwnedLogin(headLogin)
        require(lifetime == LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE)
        require(headKeys.map { it.role } == LocalCompanyHeadKeyRoleV1.entries.toList())
        require(allIds().distinct().size == 15)
        var unfinished = !original.complete()
        for (key in headKeys) {
            if (key.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE) require(!unfinished)
            else {
                if (key.phase == SelfOwnedKeyIntentPhaseV1.STARTED) require(!unfinished)
                unfinished = true
            }
        }
        require(!original.enrollmentStarted || complete())
    }

    fun allIds(): List<DomainId> = listOf(original.actionId, original.rootId, original.organizationId,
        original.companyId, original.issuerId, original.installerId) + original.keys.map { it.keyId } +
        listOf(headAuthorityId, headEnrollmentId) + headKeys.map { it.keyId }
    fun key(role: LocalCompanyHeadKeyRoleV1): LocalCompanyHeadKeyIntentV1 = headKeys.single { it.role == role }
    fun complete(): Boolean = original.complete() && headKeys.all { it.phase == SelfOwnedKeyIntentPhaseV1.COMPLETE }
    fun nextOriginal(value: SelfOwnedBootstrapV1): LocalCompanyAuthorityBootstrapV1 {
        capacityRevision(revision)
        return copy(revision = revision + 1L, original = value)
    }
    fun replaceKey(value: LocalCompanyHeadKeyIntentV1): LocalCompanyAuthorityBootstrapV1 {
        capacityRevision(revision)
        require(!original.enrollmentStarted && value.keyId == key(value.role).keyId)
        return copy(revision = revision + 1L, headKeys = headKeys.map { if (it.role == value.role) value else it })
    }
    override fun toString(): String = "LocalCompanyAuthorityBootstrapV1([REDACTED])"
}

/** Nonrecursive root-signed founding body. No D7, attempt, mutable revision or credential. */
internal data class LocalCompanyHeadEnrollmentBodyV1(
    val rootId: DomainId,
    val rootKeyId: DomainId,
    val organizationId: DomainId,
    val companyId: DomainId,
    val foundingActionId: DomainId,
    val headAuthorityId: DomainId,
    val headEnrollmentId: DomainId,
    val headName: String,
    val headLogin: String,
    val lifetime: LocalCompanyHeadLifetimeV1,
    val policyKeyId: DomainId,
    val policyPoint: SelfOwnedOctetsV1,
    val installationKeyId: DomainId,
    val installationPoint: SelfOwnedOctetsV1,
) {
    init {
        localCompanyHeadName(headName); selfOwnedLogin(headLogin)
        require(policyPoint.size == 65 && installationPoint.size == 65)
        require(lifetime == LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE)
    }
}

internal data class LocalCompanyHeadEnrollmentV1(
    val body: LocalCompanyHeadEnrollmentBodyV1,
    val signature: SelfOwnedOctetsV1,
) {
    init { require(signature.size == 64) }
}

/** Independent credential and durable account-wide head bucket, not the installer account. */
internal data class LocalCompanyHeadAccountV1(
    val headAuthorityId: DomainId,
    val headEnrollmentId: DomainId,
    val name: String,
    val login: String,
    val accountRevision: Long,
    val credentialRevision: Long,
    val enabled: Boolean,
    val policyRevision: Long,
    val policyEnabled: Boolean,
    val installationRevision: Long,
    val installationEnabled: Boolean,
    val salt: SelfOwnedOctetsV1,
    val verifier: SelfOwnedOctetsV1,
    val failedAttempts: Int,
    val reservationId: DomainId?,
    val throttleBoot: Int,
    val nextAllowedElapsedMillis: Long,
    val cooldownMillis: Long,
) {
    init {
        localCompanyHeadName(name); selfOwnedLogin(login)
        require(accountRevision in 0 until Long.MAX_VALUE && credentialRevision == 0L)
        require(policyRevision in 0 until Long.MAX_VALUE && installationRevision in 0 until Long.MAX_VALUE)
        require(enabled || !policyEnabled && !installationEnabled)
        require(salt.size == 32 && verifier.size == 32)
        require(failedAttempts in 0..SelfOwnedInstallationProfileV1.MAXIMUM_FAILED_ATTEMPTS && throttleBoot >= 0 && nextAllowedElapsedMillis >= 0)
        require(cooldownMillis in 0..SelfOwnedInstallationProfileV1.MAXIMUM_COOLDOWN_MILLIS)
        require(if (failedAttempts == 0) reservationId == null && cooldownMillis == 0L else cooldownMillis > 0L)
    }
    fun purposeRevision(purpose: LocalCompanyHeadPurposeV1): Long = when (purpose) {
        LocalCompanyHeadPurposeV1.POLICY_PUBLISH -> policyRevision
        LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE -> installationRevision
    }
    fun purposeEnabled(purpose: LocalCompanyHeadPurposeV1): Boolean = enabled && when (purpose) {
        LocalCompanyHeadPurposeV1.POLICY_PUBLISH -> policyEnabled
        LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE -> installationEnabled
    }
    override fun toString(): String = "LocalCompanyHeadAccountV1([REDACTED])"
}

internal enum class LocalCompanyPolicyPhaseV1 { PREPARED, SIGNING_INTENT, ISSUED }

internal data class LocalCompanyPolicyRecordV1(
    val originalActionId: DomainId,
    val headAccountRevision: Long,
    val headCredentialRevision: Long,
    val purposeRevision: Long,
    val keyRevision: Long,
    val delegationBody: SelfOwnedOctetsV1,
    val policyBody: SelfOwnedOctetsV1,
    val phase: LocalCompanyPolicyPhaseV1,
    val signedPolicy: SelfOwnedOctetsV1?,
) {
    init {
        require(headAccountRevision >= 0 && headCredentialRevision == 0L && purposeRevision >= 0 && keyRevision == 0L)
        require(delegationBody.size in 1..LocalCompanyPolicyFormatV1.MAX_BODY_BYTES)
        require(policyBody.size in 1..LocalCompanyPolicyFormatV1.MAX_BODY_BYTES)
        require((phase == LocalCompanyPolicyPhaseV1.ISSUED) == (signedPolicy != null))
        require(signedPolicy == null || signedPolicy.size in 1..LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES)
    }
    fun policy(): PurchasingPolicyPublicationV1 {
        val bytes = policyBody.copyBytes()
        return try { LocalCompanyPolicyWireV1.decodePolicyBody(bytes) } finally { bytes.fill(0) }
    }
    fun delegation(): PurchasingAuthorityDelegationV1 {
        val bytes = delegationBody.copyBytes()
        return try { LocalCompanyPolicyWireV1.decodeDelegationBody(bytes) } finally { bytes.fill(0) }
    }
    fun result(): PurchasingSignedPolicyV1 {
        val bytes = (signedPolicy ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)).copyBytes()
        return try { LocalCompanyPolicyWireV1.decodeSignedPolicy(bytes) } finally { bytes.fill(0) }
    }
    override fun toString(): String = "LocalCompanyPolicyRecordV1([REDACTED])"
}

/**
 * Compact issuer-side capacity reservation BEFORE P4 allocates an attempt. No attempt ID,
 * complete D7, authentication or SQL fate is invented here. It may only become that actual
 * next original claim; disappearance cannot be treated as cancellation/negative evidence.
 */
internal data class LocalCompanyInstallationAdmissionV1(
    val originalRequestId: DomainId,
    val allocationDigest: PayloadDigest,
    val definitionDigest: PayloadDigest,
    val nextAttemptSequence: Long,
) {
    init { require(nextAttemptSequence in 1 until Long.MAX_VALUE) }
    override fun toString(): String = "LocalCompanyInstallationAdmissionV1([REDACTED])"
}

/**
 * Exact compact issuer claim/output only. P4 exclusively owns complete D7 and SQL/retirement
 * lifecycle. Frames/envelopes below MUST come from the ONE P3 V2 encoder. No shadow grammar.
 * An absent output after prior claim is not proof of no SQL, nor permission to reissue IDs.
 */
internal data class LocalCompanyInstallationClaimV1(
    val issuedAttemptId: DomainId,
    val attemptSequence: Long,
    val originalRequestId: DomainId,
    val allocationDigest: PayloadDigest,
    val definitionDigest: PayloadDigest,
    val approvalFrame: SelfOwnedOctetsV1,
    val signedHumanApproval: SelfOwnedOctetsV1?,
    val acknowledgementFrame: SelfOwnedOctetsV1?,
    val signedAcknowledgement: SelfOwnedOctetsV1?,
    val retirementEvidence: SelfOwnedOctetsV1?,
) {
    init {
        require(attemptSequence in 1 until Long.MAX_VALUE)
        require(approvalFrame.size in 1..LocalBranchInstallationWireV2.MAXIMUM_APPROVAL_FRAME_BYTES)
        require(signedHumanApproval == null || signedHumanApproval.size in 1..LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES)
        require(acknowledgementFrame == null || acknowledgementFrame.size in 1..LocalBranchInstallationWireV2.MAXIMUM_ACKNOWLEDGEMENT_BYTES)
        require(signedAcknowledgement == null || signedAcknowledgement.size in 1..LocalBranchInstallationWireV2.MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES)
        require(acknowledgementFrame == null || signedHumanApproval != null)
        require(signedAcknowledgement == null || acknowledgementFrame != null)
        require(retirementEvidence == null || acknowledgementFrame == null)
        require(retirementEvidence == null || retirementEvidence.size in 1..MAXIMUM_RETIREMENT_EVIDENCE_BYTES)
    }
    override fun toString(): String = "LocalCompanyInstallationClaimV1([REDACTED])"
    companion object {
        // Admission reserves this whole exact-evidence field, not just its current digest.
        const val MAXIMUM_RETIREMENT_EVIDENCE_BYTES = SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES
    }
}

internal data class LocalCompanyAuthorityStateV1(
    val original: LocalProvisionerRegistryStateV1,
    val enrollment: LocalCompanyHeadEnrollmentV1,
    val head: LocalCompanyHeadAccountV1,
    val policies: List<LocalCompanyPolicyRecordV1>,
    val installationClaims: List<LocalCompanyInstallationClaimV1>,
    val installationAdmission: LocalCompanyInstallationAdmissionV1? = null,
    /** Created only by the explicit serial native ceremony; permanent even on uncertainty. */
    val serialCustody: NativeCompanySerialAuthorityCustodyV1? = null,
) {
    init {
        require(policies.size <= MAX_POLICY_RECORDS && installationClaims.size <= MAX_INSTALLATION_CLAIMS)
        require(policies.map { it.originalActionId }.distinct().size == policies.size)
        require(installationClaims.map { it.issuedAttemptId }.distinct().size == installationClaims.size)
        require(installationClaims.zipWithNext().all { (a, b) -> b.attemptSequence == a.attemptSequence + 1L })
        require(installationClaims.isEmpty() || installationClaims.first().attemptSequence == 1L)
        require(installationClaims.dropLast(1).all { it.retirementEvidence != null })
        installationAdmission?.let { admission ->
            require(installationClaims.size < MAX_INSTALLATION_CLAIMS &&
                admission.nextAttemptSequence == installationClaims.size.toLong() + 1L &&
                installationClaims.all { it.retirementEvidence != null })
            installationClaims.firstOrNull()?.let { originalClaim ->
                require(originalClaim.originalRequestId == admission.originalRequestId &&
                    originalClaim.allocationDigest == admission.allocationDigest && originalClaim.definitionDigest == admission.definitionDigest)
            }
        }
    }
    fun next(head: LocalCompanyHeadAccountV1 = this.head, policies: List<LocalCompanyPolicyRecordV1> = this.policies,
        claims: List<LocalCompanyInstallationClaimV1> = installationClaims,
        admission: LocalCompanyInstallationAdmissionV1? = installationAdmission,
        serialCustody: NativeCompanySerialAuthorityCustodyV1? = this.serialCustody): LocalCompanyAuthorityStateV1 =
        copy(original = original.next(), head = head, policies = policies.toList(), installationClaims = claims.toList(),
            installationAdmission = admission, serialCustody = serialCustody)
    fun replacePolicy(value: LocalCompanyPolicyRecordV1): LocalCompanyAuthorityStateV1 {
        require(policies.count { it.originalActionId == value.originalActionId } == 1)
        return next(policies = policies.map { if (it.originalActionId == value.originalActionId) value else it })
    }
    fun replaceClaim(value: LocalCompanyInstallationClaimV1): LocalCompanyAuthorityStateV1 {
        require(installationClaims.count { it.issuedAttemptId == value.issuedAttemptId } == 1)
        return next(claims = installationClaims.map { if (it.issuedAttemptId == value.issuedAttemptId) value else it })
    }
    override fun toString(): String = "LocalCompanyAuthorityStateV1([REDACTED])"
    companion object {
        // Permanent history, no trimming. Whole encoded 4 MiB bound still applies FIRST.
        const val MAX_POLICY_RECORDS = 256
        const val MAX_INSTALLATION_CLAIMS = 256
    }
}

internal fun capacityRevision(value: Long) {
    if (value !in 0 until Long.MAX_VALUE - 1L) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
}
