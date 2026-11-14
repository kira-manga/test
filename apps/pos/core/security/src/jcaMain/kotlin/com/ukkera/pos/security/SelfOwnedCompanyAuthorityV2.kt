package com.ukkera.pos.security

/**
 * Windows/macOS seven-key company custody: the four original provisioner roles plus the three head
 * roles (policy, installation, pepper) of the retained [LocalCompanyAuthorityBootstrapV2]. Private
 * keys NEVER export. Platform contract: [prepareCompanyKeys] progresses the head key intents through
 * [SelfOwnedProvisionerRegistryStoreV2.retainCompanyBootstrapV2] exactly as [prepareOriginalKeys]
 * progresses the original roles (STARTED retained before key creation, COMPLETE with the public
 * point/key proof after, ordered only by the verified [SelfOwnedBootChainV2]); and every head key
 * is bound to the retained key ID/alias of THIS namespace.
 */
internal interface SelfOwnedCompanyNativeKeysV2 : SelfOwnedProvisionerNativeKeysV2 {
    fun prepareCompanyKeys()
    fun assertAllCompanyKeys()
    /** Uncompressed P-256 point of the POLICY or INSTALLATION head key (PEPPER has none). */
    fun headPoint(role: LocalCompanyHeadKeyRoleV1): ByteArray
    /** Head enrollment body from the actual retained company bootstrap, root key and head points. */
    fun headEnrollmentBodyV2(): LocalCompanyHeadEnrollmentBodyV1
    /** Root key signature over exactly `encodeHeadEnrollmentBody(headEnrollmentBodyV2())`. */
    fun signFreshHeadEnrollmentV2(): LocalCompanyHeadEnrollmentV1
    /** Native head-pepper HMAC-SHA256 tag (32 bytes). */
    fun headTag(message: ByteArray): ByteArray
    /**
     * Original ROOT key P-256 signature (64-byte r||s) over exactly [message]. The platform MUST call
     * [SelfOwnedNativeCompanyLedgerWireV2.requireRootEndorsementMessage] before touching the key, so
     * only a shared client-verified root endorsement is ever signed. Custody without this capability
     * refuses (UNSUPPORTED_PLATFORM).
     */
    fun signRootEndorsement(message: ByteArray): ByteArray = selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    /**
     * INSTALLATION head key signature (64-byte r||s) over exactly one HUMAN original-installation
     * approval frame whose signer is this company's INSTALLATION key. The caller re-checks its
     * retained claim and head approval before and after; the platform verifies before returning.
     */
    fun signInstallationApproval(frame: LocalInstallationApprovalFrameV2): ByteArray =
        selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    /** Original ROOT key signature over exactly one retained original-installation commit acknowledgement frame. */
    fun signInstallationAcknowledgement(frame: LocalInstallationCommitAcknowledgementV2): ByteArray =
        selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
}

/**
 * Core-side authenticated codec of the desktop company container, mirroring the Android V1
 * company registry: issuer signature over `registrySignature(companyRegistryAad(bootstrap), nonce,
 * ciphertext)` verified BEFORE decryption with the V2 registry key; canonical plaintext; the
 * embedded original registry validated exactly as [SelfOwnedProvisionerRegistryV2]; the head
 * account names the bootstrap head; the enrollment is the actual keys' body signed by the root key
 * over the V2 body frame. Policy and serial-custody records have no desktop workflow yet and are
 * refused (STATE_UNCERTAIN); original-installation claims/admission follow the Android V1 rules.
 */
internal object SelfOwnedCompanyAuthorityV2 {
    fun requireCompanyKeys(keys: SelfOwnedOriginalProvisionerKeys): SelfOwnedCompanyNativeKeysV2 =
        (keys as? SelfOwnedCompanyNativeKeysV2)?.takeIf { it.originalKeysFormat == SelfOwnedOriginalFormat.V2 }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)

    /**
     * [bootOrdinal] is the verified current boot ordinal when a credential workflow reads the state
     * (the head throttle boot may not exceed it); null only for the original-preparation read.
     */
    fun read(company: LocalCompanyAuthorityBootstrapV2, envelopeBytes: ByteArray, keys: SelfOwnedCompanyNativeKeysV2,
        bootOrdinal: Int?): LocalCompanyAuthorityStateV2 {
        known(company.complete() && company.original.enrollmentStarted)
        keys.assertAllCompanyKeys()
        val envelope = when (val decoded = SelfOwnedNativeProvisionerWireV2.decodeRegistryEnvelope(envelopeBytes)) {
            is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
            is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        val aad = SelfOwnedNativeProvisionerWireV2.companyRegistryAad(company)
        try {
            SelfOwnedProvisionerRegistryV2.requireIssuerSignature(keys, aad, envelope)
            val plaintext = keys.openRegistryV2(aad, envelope)
            try {
                val state = when (val decoded = SelfOwnedNativeProvisionerWireV2.decodeCompanyState(plaintext)) {
                    is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
                    is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
                val canonical = SelfOwnedNativeProvisionerWireV2.encodeCompanyState(state)
                try { known(canonical.contentEquals(plaintext)) } finally { canonical.fill(0) }
                validate(company, state, keys, bootOrdinal)
                keys.assertAllCompanyKeys()
                return state
            } finally { plaintext.fill(0) }
        } finally { aad.fill(0); envelope.clear() }
    }

    /** Validates [state] first, then seals and re-verifies the produced envelope signature. */
    fun seal(company: LocalCompanyAuthorityBootstrapV2, state: LocalCompanyAuthorityStateV2, keys: SelfOwnedCompanyNativeKeysV2,
        bootOrdinal: Int?): ByteArray {
        known(company.complete() && company.original.enrollmentStarted)
        validate(company, state, keys, bootOrdinal)
        val bytes = sealUnchecked(company, state, keys)
        val aad = SelfOwnedNativeProvisionerWireV2.companyRegistryAad(company)
        try {
            val envelope = when (val decoded = SelfOwnedNativeProvisionerWireV2.decodeRegistryEnvelope(bytes)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
                is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            try { SelfOwnedProvisionerRegistryV2.requireIssuerSignature(keys, aad, envelope) } finally { envelope.clear() }
        } catch (failure: Throwable) { bytes.fill(0); throw failure } finally { aad.fill(0) }
        return bytes
    }

    internal fun sealUnchecked(company: LocalCompanyAuthorityBootstrapV2, state: LocalCompanyAuthorityStateV2,
        keys: SelfOwnedCompanyNativeKeysV2): ByteArray {
        val aad = SelfOwnedNativeProvisionerWireV2.companyRegistryAad(company)
        val plaintext = try { SelfOwnedNativeProvisionerWireV2.encodeCompanyState(state) }
        catch (_: IllegalArgumentException) { aad.fill(0); selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED) }
        try {
            val envelope = keys.sealRegistryV2(aad, plaintext)
            return try { SelfOwnedNativeProvisionerWireV2.encodeRegistryEnvelope(envelope) } finally { envelope.clear() }
        } finally { aad.fill(0); plaintext.fill(0) }
    }

    fun validate(company: LocalCompanyAuthorityBootstrapV2, state: LocalCompanyAuthorityStateV2, keys: SelfOwnedCompanyNativeKeysV2,
        bootOrdinal: Int?) {
        SelfOwnedProvisionerRegistryV2.validate(company.original, state.original, keys)
        val head = state.head
        known(head.headAuthorityId == company.headAuthorityId && head.headEnrollmentId == company.headEnrollmentId &&
            head.name == company.headName && head.login == company.headLogin)
        bootOrdinal?.let { known(head.throttleBoot <= it && state.original.installers.all { account -> account.throttleBoot <= it }) }
        // No desktop policy/serial workflow exists yet: such records are never adopted. Original
        // installation claims (one D7/request across every attempt) follow the Android V1 rules.
        known(state.policies.isEmpty() && state.serialCustody == null)
        val first = state.installationClaims.firstOrNull()
        for (claim in state.installationClaims) known(first != null && claim.originalRequestId == first.originalRequestId &&
            claim.allocationDigest == first.allocationDigest && claim.definitionDigest == first.definitionDigest)
        val body = state.enrollment.body
        known(body.rootId == company.original.rootId && body.rootKeyId == company.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId &&
            body.organizationId == company.original.organizationId && body.companyId == company.original.companyId &&
            body.foundingActionId == company.original.actionId && body.headAuthorityId == company.headAuthorityId &&
            body.headEnrollmentId == company.headEnrollmentId && body.headName == company.headName && body.headLogin == company.headLogin &&
            body.policyKeyId == company.key(LocalCompanyHeadKeyRoleV1.POLICY).keyId &&
            body.installationKeyId == company.key(LocalCompanyHeadKeyRoleV1.INSTALLATION).keyId)
        val expected = SelfOwnedNativeProvisionerWireV2.encodeHeadEnrollmentBody(keys.headEnrollmentBodyV2())
        val retained = SelfOwnedNativeProvisionerWireV2.encodeHeadEnrollmentBody(body)
        val rootPoint = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
        val policyPoint = keys.headPoint(LocalCompanyHeadKeyRoleV1.POLICY)
        val installationPoint = keys.headPoint(LocalCompanyHeadKeyRoleV1.INSTALLATION)
        val signature = state.enrollment.signature.copyBytes()
        try {
            known(expected.contentEquals(retained) && body.policyPoint.same(policyPoint) &&
                body.installationPoint.same(installationPoint) && JvmP256Primitives.verify(rootPoint, retained, signature))
        } finally { expected.fill(0); retained.fill(0); rootPoint.fill(0); policyPoint.fill(0); installationPoint.fill(0); signature.fill(0) }
    }

    /** Android V1 company transition rules restricted to the records a desktop workflow can write. */
    fun requireTransition(previous: LocalCompanyAuthorityStateV2?, next: LocalCompanyAuthorityStateV2) {
        SelfOwnedProvisionerRegistryV2.requireTransition(previous?.original, next.original)
        if (previous == null) {
            val head = next.head
            known(head.accountRevision == 0L && head.credentialRevision == 0L && head.enabled &&
                head.policyRevision == 0L && head.policyEnabled && head.installationRevision == 0L && head.installationEnabled &&
                head.failedAttempts == 0 && head.reservationId == null && next.policies.isEmpty() &&
                next.installationClaims.isEmpty() && next.installationAdmission == null && next.serialCustody == null)
            return
        }
        val before = SelfOwnedNativeProvisionerWireV2.encodeHeadEnrollment(previous.enrollment)
        val after = SelfOwnedNativeProvisionerWireV2.encodeHeadEnrollment(next.enrollment)
        try { known(before.contentEquals(after)) } finally { before.fill(0); after.fill(0) }
        val a = previous.head; val b = next.head
        known(a.headAuthorityId == b.headAuthorityId && a.headEnrollmentId == b.headEnrollmentId && a.name == b.name &&
            a.login == b.login && a.credentialRevision == b.credentialRevision && a.salt == b.salt && a.verifier == b.verifier)
        known((a.enabled || !b.enabled) && (a.policyEnabled || !b.policyEnabled) && (a.installationEnabled || !b.installationEnabled))
        known(b.accountRevision == a.accountRevision + (if (a.enabled != b.enabled) 1L else 0L))
        known(b.policyRevision == a.policyRevision + (if (a.policyEnabled != b.policyEnabled) 1L else 0L))
        known(b.installationRevision == a.installationRevision + (if (a.installationEnabled != b.installationEnabled) 1L else 0L))
        known(b.throttleBoot >= a.throttleBoot)
        known(next.policies.isEmpty() && next.serialCustody == null)
        known(next.installationClaims.size in previous.installationClaims.size..previous.installationClaims.size + 1)
        for ((index, old) in previous.installationClaims.withIndex()) {
            val updated = next.installationClaims[index]
            known(old.issuedAttemptId == updated.issuedAttemptId && old.attemptSequence == updated.attemptSequence &&
                old.originalRequestId == updated.originalRequestId && old.allocationDigest == updated.allocationDigest &&
                old.definitionDigest == updated.definitionDigest && old.approvalFrame == updated.approvalFrame &&
                (old.signedHumanApproval == null || old.signedHumanApproval == updated.signedHumanApproval) &&
                (old.acknowledgementFrame == null || old.acknowledgementFrame == updated.acknowledgementFrame) &&
                (old.signedAcknowledgement == null || old.signedAcknowledgement == updated.signedAcknowledgement) &&
                (old.retirementEvidence == null || old == updated))
        }
        if (next.installationClaims.size > previous.installationClaims.size) {
            val added = next.installationClaims.last()
            val admission = previous.installationAdmission ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            known(previous.installationClaims.all { it.retirementEvidence != null } && added.signedHumanApproval == null &&
                added.acknowledgementFrame == null && added.signedAcknowledgement == null && added.retirementEvidence == null &&
                added.originalRequestId == admission.originalRequestId && added.allocationDigest == admission.allocationDigest &&
                added.definitionDigest == admission.definitionDigest && added.attemptSequence == admission.nextAttemptSequence &&
                next.installationAdmission == null)
        } else {
            // A reservation cannot be dropped/replaced to reclaim space or manufacture a retry.
            previous.installationAdmission?.let { known(it == next.installationAdmission) }
            if (previous.installationAdmission == null && next.installationAdmission != null) {
                known(previous.installationClaims.all { it.retirementEvidence != null })
            }
        }
    }

    /**
     * Completion capacity of the desktop container (Android V1 accounting restricted to the records a
     * desktop workflow writes): an admitted installation must always be able to retain its claim,
     * human output, ACK intent and ACK output (or retirement) in bytes and revisions.
     */
    fun requireCapacity(value: LocalCompanyAuthorityStateV2) {
        val encoded = try { SelfOwnedNativeProvisionerWireV2.encodeCompanyState(value) }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED) }
        val size = encoded.size.toLong(); encoded.fill(0)
        val terminal = maxOf(4L + LocalCompanyInstallationClaimV1.MAXIMUM_RETIREMENT_EVIDENCE_BYTES,
            8L + LocalBranchInstallationWireV2.MAXIMUM_ACKNOWLEDGEMENT_BYTES + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES)
        var reserved = if (value.head.reservationId == null) 40L else 0L
        var revisions = 0L
        reserved += 40L * value.original.installers.count { it.reservationId == null }
        for (allocation in value.original.allocations) if (allocation.grant == null) {
            reserved += 4L + SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES
            revisions += if (allocation.phase == LocalProvisionerAllocationPhaseV1.ALLOCATED) 2L else 1L
        }
        if (value.installationAdmission != null) {
            reserved += 2L * (4 + 36) + 8L + 2L * (4 + 64) + 4L + LocalBranchInstallationWireV2.MAXIMUM_APPROVAL_FRAME_BYTES + 4L
            reserved += 4L + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES + terminal
            revisions += 4L
        }
        for (claim in value.installationClaims) {
            if (claim.retirementEvidence != null || claim.signedAcknowledgement != null) continue
            if (claim.signedHumanApproval == null) reserved += 4L + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES
            if (claim.acknowledgementFrame != null) {
                reserved += 4L + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES; revisions += 1L
            } else {
                reserved += terminal; revisions += if (claim.signedHumanApproval == null) 3L else 2L
            }
        }
        if (size + reserved > SelfOwnedNativeCompanyProfileV2.MAXIMUM_COMPANY_STATE_BYTES ||
            revisions > (Long.MAX_VALUE - 1L) - value.original.revision) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
    }

    private fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
}
