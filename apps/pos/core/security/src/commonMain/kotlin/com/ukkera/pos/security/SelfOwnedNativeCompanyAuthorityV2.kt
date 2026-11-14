package com.ukkera.pos.security

/**
 * Additive Windows/macOS company container profile (desktop mirror of the Android V1
 * `ukkera.local-company-authority/p256/v1` container). Every domain below is new and disjoint from
 * the Android V1 company/registry domains and from the other desktop provisioner domains; the frame
 * is the closed [SelfOwnedNativeProvisionerWireV2] frame (profile, domain, version 2).
 *
 * Signature conventions (all ECDSA P-256 / SHA-256, 64-byte P1363 low-S):
 * - Issuer V2 preparation grant: the issuer key signs the exact
 *   [SelfOwnedInstalledIdentitySignedBodyV2.copyWireBytes] bytes, whose leading
 *   `SelfOwnedInstalledIdentityProfileV2.SIGNING_BODY_DOMAIN` string is the domain separator.
 * - Root-signed head enrollment: the root key signs the exact
 *   [SelfOwnedNativeProvisionerWireV2.encodeHeadEnrollmentBody] bytes ([HEAD_ENROLLMENT_BODY_DOMAIN]).
 * - Registry/company envelope: the issuer key signs
 *   `registrySignature(aad, nonce, ciphertext)`; the company AAD is `companyRegistryAad(bootstrap)`.
 * Credential verifiers are native-pepper HMAC tags over the V2 verifier frames below; no Android V1
 * verifier message, AAD or key is reused or accepted.
 */
internal object SelfOwnedNativeCompanyProfileV2 {
    const val COMPANY_STATE_DOMAIN: String = "native-company-authority-state/v2"
    const val HEAD_ENROLLMENT_BODY_DOMAIN: String = "native-company-head-enrollment-body/v2"
    const val HEAD_ENROLLMENT_DOMAIN: String = "native-company-head-enrollment/v2"
    const val INSTALLER_VERIFIER_DOMAIN: String = "native-provisioner-installer-verifier/v2"
    const val HEAD_VERIFIER_DOMAIN: String = "native-company-head-verifier/v2"
    const val PROTECTED_HEAD_STATE_DOMAIN: String = "native-company-protected-head-state/v2"
    const val RETIRED_INSTALLATION_CLAIM_DOMAIN: String = "native-company-retired-installation-claim/v2"
    const val COMPANY_STATE_PURPOSE: String = "ORIGINAL_NATIVE_COMPANY_AUTHORITY_V2"
    const val SIGNATURE_ALGORITHM: String = "ECDSA_P256_SHA256_P1363_LOW_S"
    const val KDF: String = "PBKDF2-HMAC-SHA256/256"
    const val HEAD_KDF: String = "PBKDF2-HMAC-SHA256/POST-HEAD-HMAC-SHA256/256"
    const val MAXIMUM_HEAD_ENROLLMENT_BODY_BYTES: Int = 8_192
    const val MAXIMUM_HEAD_ENROLLMENT_BYTES: Int = MAXIMUM_HEAD_ENROLLMENT_BODY_BYTES + 512
    const val MAXIMUM_VERIFIER_MESSAGE_BYTES: Int = 4_096
    const val MAXIMUM_COMPANY_STATE_BYTES: Int = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES
}

/**
 * Desktop company container state: the same shape and invariants as [LocalCompanyAuthorityStateV1]
 * over the desktop [LocalProvisionerRegistryStateV2]. The head enrollment and head account reuse
 * the V1 value shapes, but are encoded and signed only through the V2 company domains. DATA only.
 */
internal data class LocalCompanyAuthorityStateV2(
    val original: LocalProvisionerRegistryStateV2,
    val enrollment: LocalCompanyHeadEnrollmentV1,
    val head: LocalCompanyHeadAccountV1,
    val policies: List<LocalCompanyPolicyRecordV1>,
    val installationClaims: List<LocalCompanyInstallationClaimV1>,
    val installationAdmission: LocalCompanyInstallationAdmissionV1? = null,
    val serialCustody: NativeCompanySerialAuthorityCustodyV1? = null,
) {
    init {
        require(policies.size <= LocalCompanyAuthorityStateV1.MAX_POLICY_RECORDS &&
            installationClaims.size <= LocalCompanyAuthorityStateV1.MAX_INSTALLATION_CLAIMS)
        require(policies.map { it.originalActionId }.distinct().size == policies.size)
        require(installationClaims.map { it.issuedAttemptId }.distinct().size == installationClaims.size)
        require(installationClaims.zipWithNext().all { (a, b) -> b.attemptSequence == a.attemptSequence + 1L })
        require(installationClaims.isEmpty() || installationClaims.first().attemptSequence == 1L)
        require(installationClaims.dropLast(1).all { it.retirementEvidence != null })
        installationAdmission?.let { admission ->
            require(installationClaims.size < LocalCompanyAuthorityStateV1.MAX_INSTALLATION_CLAIMS &&
                admission.nextAttemptSequence == installationClaims.size.toLong() + 1L &&
                installationClaims.all { it.retirementEvidence != null })
            installationClaims.firstOrNull()?.let { originalClaim ->
                require(originalClaim.originalRequestId == admission.originalRequestId &&
                    originalClaim.allocationDigest == admission.allocationDigest &&
                    originalClaim.definitionDigest == admission.definitionDigest)
            }
        }
    }

    /** One whole-container revision step (the embedded registry revision), as on Android V1. */
    fun next(
        original: LocalProvisionerRegistryStateV2 = this.original.next(),
        head: LocalCompanyHeadAccountV1 = this.head,
        policies: List<LocalCompanyPolicyRecordV1> = this.policies,
        claims: List<LocalCompanyInstallationClaimV1> = installationClaims,
        admission: LocalCompanyInstallationAdmissionV1? = installationAdmission,
        serialCustody: NativeCompanySerialAuthorityCustodyV1? = this.serialCustody,
    ): LocalCompanyAuthorityStateV2 {
        require(original.revision == this.original.revision + 1L)
        return copy(original = original, head = head, policies = policies.toList(), installationClaims = claims.toList(),
            installationAdmission = admission, serialCustody = serialCustody)
    }

    override fun toString(): String = "LocalCompanyAuthorityStateV2([REDACTED])"
}
