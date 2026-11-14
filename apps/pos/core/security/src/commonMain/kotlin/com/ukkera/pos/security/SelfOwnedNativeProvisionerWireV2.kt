package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * Closed Windows/macOS provisioner framing: profile string, domain string, format version int 2,
 * then the body (4-byte big-endian length + bytes fields, UTF-8 strings, big-endian ints/longs,
 * 0/1 int flags). Decoding is bounded, canonical (re-encoding must reproduce the exact bytes) and
 * fail-closed: an unknown profile/domain/version/platform/role, Android target, truncation,
 * oversize field or trailing byte is refused. No decode path verifies a signature, observes OS
 * custody or grants authority. Android V1 decoders refuse these bytes and vice versa.
 */
internal object SelfOwnedNativeProvisionerWireV2 {
    private const val ITERATIONS = SelfOwnedInstallationProfileV1.PASSWORD_ITERATIONS

    fun encodeNamespace(value: SelfOwnedRegistryNamespaceV2): ByteArray =
        frame(SelfOwnedNativeOriginalProfileV2.NAMESPACE_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_NAMESPACE_BYTES) {
            namespaceBody(value)
        }

    fun decodeNamespace(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<SelfOwnedRegistryNamespaceV2> =
        guardedCanonical(bytes, this::encodeNamespace) {
            read(bytes, SelfOwnedNativeOriginalProfileV2.NAMESPACE_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_NAMESPACE_BYTES) {
                namespaceBody()
            }
        }

    fun encodeBootstrap(value: SelfOwnedBootstrapV2): ByteArray =
        frame(SelfOwnedNativeOriginalProfileV2.BOOTSTRAP_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_BOOTSTRAP_BYTES) {
            long(value.revision); id(value.actionId); id(value.rootId); id(value.organizationId); id(value.companyId)
            id(value.issuerId); id(value.installerId)
            string(value.facts.companyName); string(value.facts.responsiblePersonName); string(value.facts.installerLogin)
            int(value.facts.protection.ordinal)
            owned(value.installedTarget.copyWireBytes()); owned(encodeNamespace(value.namespace))
            flag(value.enrollmentStarted); octets(value.nonce); int(value.keys.size)
            for (key in value.keys) {
                int(key.role.wireId); id(key.keyId); int(key.phase.ordinal)
                flag(key.startedBoot != null); key.startedBoot?.let { bootSession(it) }
                flag(key.security != null); key.security?.let { int(it.ordinal) }
                octets(key.proof)
            }
        }

    fun decodeBootstrap(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<SelfOwnedBootstrapV2> =
        guardedCanonical(bytes, this::encodeBootstrap) {
            read(bytes, SelfOwnedNativeOriginalProfileV2.BOOTSTRAP_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_BOOTSTRAP_BYTES) {
                bootstrapBody()
            }
        }

    fun encodeRegistry(value: LocalProvisionerRegistryStateV2): ByteArray =
        frame(SelfOwnedNativeOriginalProfileV2.REGISTRY_STATE_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_REGISTRY_PLAINTEXT_BYTES) {
            long(value.revision); owned(value.root.copyWireBytes()); owned(value.delegation.copyWireBytes())
            flag(value.issuanceEnabled); long(value.nextSerial)
            int(value.installers.size)
            for (record in value.installers) {
                id(record.installerId); string(record.name); string(record.login)
                long(record.credentialRevision); long(record.delegationRevision); flag(record.enabled)
                int(ITERATIONS); octets(record.salt); octets(record.verifier)
                int(record.failedAttempts); flag(record.reservationId != null); record.reservationId?.let { id(it) }
                int(record.throttleBoot); long(record.nextAllowedElapsedMillis); long(record.cooldownMillis)
            }
            int(value.allocations.size)
            for (record in value.allocations) {
                owned(record.body.copyWireBytes()); int(record.phase.ordinal)
                flag(record.grant != null); record.grant?.let { owned(it.copyWireBytes()) }
            }
            string(SelfOwnedNativeOriginalProfileV2.REGISTRY_PURPOSE)
        }

    fun decodeRegistry(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<LocalProvisionerRegistryStateV2> =
        guardedCanonical(bytes, this::encodeRegistry) {
            read(bytes, SelfOwnedNativeOriginalProfileV2.REGISTRY_STATE_DOMAIN,
                SelfOwnedNativeOriginalProfileV2.MAXIMUM_REGISTRY_PLAINTEXT_BYTES) {
                val revision = long()
                val root = signedV1(SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT)
                val delegation = signedV1(SelfOwnedDocumentRoleV1.ISSUER_DELEGATION)
                val enabled = flag(); val serial = long()
                val count = int()
                if (count !in 1..SelfOwnedInstallationProfileV1.MAXIMUM_INSTALLERS) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
                val installers = List(count) {
                    val installer = uuid(); val name = string(); val login = string()
                    val credential = long(); val delegated = long(); val active = flag()
                    if (int() != ITERATIONS) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                    val salt = octets(32); val verifier = octets(32); val failed = int()
                    val reservation = if (flag()) uuid() else null
                    LocalProvisionerInstallerV1(installer, name, login, credential, delegated, active, salt, verifier,
                        failed, reservation, int(), long(), long())
                }
                val allocationCount = int()
                if (allocationCount > SelfOwnedInstallationProfileV1.MAXIMUM_ALLOCATIONS) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
                val allocations = List(allocationCount) {
                    val body = nested(SelfOwnedInstalledIdentityProfileV2.MAXIMUM_PREPARATION_BYTES,
                        SelfOwnedInstalledIdentityWireV2::decodePreparation)
                    val phase = LocalProvisionerAllocationPhaseV1.entries.getOrNull(int())
                        ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                    val grant = if (flag()) nested(SelfOwnedInstalledIdentityProfileV2.MAXIMUM_DOCUMENT_BYTES,
                        SelfOwnedInstalledIdentityWireV2::decodeSignedDocument) else null
                    LocalProvisionerAllocationV2(body, phase, grant)
                }
                if (string() != SelfOwnedNativeOriginalProfileV2.REGISTRY_PURPOSE) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                LocalProvisionerRegistryStateV2(revision, root, delegation, enabled, serial, installers, allocations)
            }
        }

    fun encodeCompanyBootstrap(value: LocalCompanyAuthorityBootstrapV2): ByteArray =
        frame(SelfOwnedNativeOriginalProfileV2.COMPANY_BOOTSTRAP_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_COMPANY_BOOTSTRAP_BYTES) {
            long(value.revision); owned(encodeBootstrap(value.original))
            id(value.headAuthorityId); id(value.headEnrollmentId); string(value.headName); string(value.headLogin)
            string(LIFETIME); string(POLICY_PURPOSE); string(INSTALLATION_PURPOSE)
            int(value.headKeys.size)
            for (key in value.headKeys) {
                string(key.role.wireTag); id(key.keyId); long(key.keyRevision); string(key.phase.name)
                flag(key.startedBoot != null); key.startedBoot?.let { bootSession(it) }
                flag(key.security != null); key.security?.let { string(it.name) }
                octets(key.proof)
            }
        }

    fun decodeCompanyBootstrap(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<LocalCompanyAuthorityBootstrapV2> =
        guardedCanonical(bytes, this::encodeCompanyBootstrap) {
            read(bytes, SelfOwnedNativeOriginalProfileV2.COMPANY_BOOTSTRAP_DOMAIN,
                SelfOwnedNativeOriginalProfileV2.MAXIMUM_COMPANY_BOOTSTRAP_BYTES) {
                val revision = long()
                val original = nested(SelfOwnedNativeOriginalProfileV2.MAXIMUM_BOOTSTRAP_BYTES, SelfOwnedNativeProvisionerWireV2::decodeBootstrap)
                val head = uuid(); val enrollment = uuid(); val name = string(); val login = string()
                if (string() != LIFETIME || string() != POLICY_PURPOSE || string() != INSTALLATION_PURPOSE) {
                    refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                }
                if (int() != 3) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
                val keys = List(3) {
                    val tag = string()
                    val role = LocalCompanyHeadKeyRoleV1.entries.singleOrNull { it.wireTag == tag }
                        ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
                    val key = uuid(); val keyRevision = long()
                    val phaseName = string()
                    val phase = SelfOwnedKeyIntentPhaseV1.entries.singleOrNull { it.name == phaseName }
                        ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                    val boot = if (flag()) bootSession() else null
                    val security = if (flag()) {
                        val label = string()
                        SelfOwnedNativeSecurityV1.entries.singleOrNull { it.name == label }
                            ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                    } else null
                    LocalCompanyHeadKeyIntentV2(role, key, keyRevision, phase, boot, security,
                        octets(SelfOwnedNativeOriginalProfileV2.MAXIMUM_KEY_PROOF_BYTES))
                }
                LocalCompanyAuthorityBootstrapV2(revision, original, head, enrollment, name, login,
                    LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, keys)
            }
        }

    fun encodeBootOrdinalLedger(value: SelfOwnedBootOrdinalLedgerV2): ByteArray =
        frame(SelfOwnedNativeOriginalProfileV2.BOOT_ORDINAL_LEDGER_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_LEDGER_BYTES) {
            int(value.ordinal); bootSession(value.bootSession)
            flag(value.previousDigest != null); value.previousDigest?.let { string(it) }
        }

    fun decodeBootOrdinalLedger(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<SelfOwnedBootOrdinalLedgerV2> =
        guardedCanonical(bytes, this::encodeBootOrdinalLedger) {
            read(bytes, SelfOwnedNativeOriginalProfileV2.BOOT_ORDINAL_LEDGER_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_LEDGER_BYTES) {
                val ordinal = int(); val session = bootSession()
                val previous = if (flag()) digest() else null
                SelfOwnedBootOrdinalLedgerV2(ordinal, session, previous)
            }
        }

    /** Mirrors the Android V1 registry AAD (root, registry key, issuer, full bootstrap) under the V2 frame. */
    fun registryAad(bootstrap: SelfOwnedBootstrapV2): ByteArray =
        frame(SelfOwnedNativeOriginalProfileV2.REGISTRY_AAD_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_BOOTSTRAP_BYTES + 1_024) {
            id(bootstrap.rootId); id(bootstrap.key(SelfOwnedNativeKeyRoleV1.REGISTRY).keyId); id(bootstrap.issuerId)
            owned(encodeBootstrap(bootstrap))
        }

    /** The company registry AAD covers the complete explicit seven-key V2 original. */
    fun companyRegistryAad(value: LocalCompanyAuthorityBootstrapV2): ByteArray =
        frame(SelfOwnedNativeOriginalProfileV2.COMPANY_REGISTRY_AAD_DOMAIN,
            SelfOwnedNativeOriginalProfileV2.MAXIMUM_COMPANY_BOOTSTRAP_BYTES + 1_024) {
            owned(encodeCompanyBootstrap(value))
        }

    fun registrySignature(aad: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray =
        frame(SelfOwnedNativeOriginalProfileV2.REGISTRY_SIGNATURE_DOMAIN,
            SelfOwnedNativeOriginalProfileV2.MAXIMUM_REGISTRY_SIGNATURE_MESSAGE_BYTES) {
            field(aad); field(nonce); field(ciphertext)
        }

    fun encodeRegistryEnvelope(value: SelfOwnedRegistryEnvelopeV2): ByteArray =
        frame(SelfOwnedNativeOriginalProfileV2.REGISTRY_ENVELOPE_DOMAIN, SelfOwnedNativeOriginalProfileV2.MAXIMUM_REGISTRY_ENVELOPE_BYTES) {
            field(value.nonce); field(value.ciphertext); field(value.signature)
        }

    fun decodeRegistryEnvelope(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<SelfOwnedRegistryEnvelopeV2> =
        guardedCanonical(bytes, this::encodeRegistryEnvelope) {
            read(bytes, SelfOwnedNativeOriginalProfileV2.REGISTRY_ENVELOPE_DOMAIN,
                SelfOwnedNativeOriginalProfileV2.MAXIMUM_REGISTRY_ENVELOPE_BYTES) {
                val nonce = field(SelfOwnedNativeOriginalProfileV2.REGISTRY_NONCE_BYTES)
                val ciphertext = field(SelfOwnedNativeOriginalProfileV2.MAXIMUM_REGISTRY_PLAINTEXT_BYTES + 16)
                val signature = field(SelfOwnedNativeOriginalProfileV2.REGISTRY_SIGNATURE_BYTES)
                if (nonce.size != SelfOwnedNativeOriginalProfileV2.REGISTRY_NONCE_BYTES || ciphertext.size < 16 ||
                    signature.size != SelfOwnedNativeOriginalProfileV2.REGISTRY_SIGNATURE_BYTES) {
                    nonce.fill(0); ciphertext.fill(0); signature.fill(0)
                    refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                }
                SelfOwnedRegistryEnvelopeV2(nonce, ciphertext, signature)
            }
        }

    /** Root-signed V2 head enrollment body; the root key signs exactly these bytes. */
    fun encodeHeadEnrollmentBody(value: LocalCompanyHeadEnrollmentBodyV1): ByteArray =
        frame(SelfOwnedNativeCompanyProfileV2.HEAD_ENROLLMENT_BODY_DOMAIN,
            SelfOwnedNativeCompanyProfileV2.MAXIMUM_HEAD_ENROLLMENT_BODY_BYTES) {
            id(value.rootId); id(value.rootKeyId); id(value.organizationId); id(value.companyId); id(value.foundingActionId)
            id(value.headAuthorityId); id(value.headEnrollmentId); string(value.headName); string(value.headLogin)
            string(HEAD_ROLE); string(LIFETIME)
            string(POLICY_PURPOSE); string(LocalCompanyHeadKeyRoleV1.POLICY.wireTag); id(value.policyKeyId); octets(value.policyPoint)
            string(INSTALLATION_PURPOSE); string(LocalCompanyHeadKeyRoleV1.INSTALLATION.wireTag)
            id(value.installationKeyId); octets(value.installationPoint)
        }

    fun decodeHeadEnrollmentBody(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<LocalCompanyHeadEnrollmentBodyV1> =
        guardedCanonical(bytes, this::encodeHeadEnrollmentBody) {
            read(bytes, SelfOwnedNativeCompanyProfileV2.HEAD_ENROLLMENT_BODY_DOMAIN,
                SelfOwnedNativeCompanyProfileV2.MAXIMUM_HEAD_ENROLLMENT_BODY_BYTES) { headEnrollmentBody() }
        }

    fun encodeHeadEnrollment(value: LocalCompanyHeadEnrollmentV1): ByteArray =
        frame(SelfOwnedNativeCompanyProfileV2.HEAD_ENROLLMENT_DOMAIN, SelfOwnedNativeCompanyProfileV2.MAXIMUM_HEAD_ENROLLMENT_BYTES) {
            owned(encodeHeadEnrollmentBody(value.body)); string(SelfOwnedNativeCompanyProfileV2.SIGNATURE_ALGORITHM)
            octets(value.signature)
        }

    fun decodeHeadEnrollment(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<LocalCompanyHeadEnrollmentV1> =
        guardedCanonical(bytes, this::encodeHeadEnrollment) {
            read(bytes, SelfOwnedNativeCompanyProfileV2.HEAD_ENROLLMENT_DOMAIN, SelfOwnedNativeCompanyProfileV2.MAXIMUM_HEAD_ENROLLMENT_BYTES) {
                headEnrollment()
            }
        }

    /** Desktop company container plaintext (sealed in the V2 registry envelope under the company AAD). */
    fun encodeCompanyState(value: LocalCompanyAuthorityStateV2): ByteArray =
        frame(SelfOwnedNativeCompanyProfileV2.COMPANY_STATE_DOMAIN, SelfOwnedNativeCompanyProfileV2.MAXIMUM_COMPANY_STATE_BYTES) {
            owned(encodeRegistry(value.original)); owned(encodeHeadEnrollment(value.enrollment))
            val head = value.head
            id(head.headAuthorityId); id(head.headEnrollmentId); string(head.name); string(head.login)
            long(head.accountRevision); long(head.credentialRevision); flag(head.enabled)
            long(head.policyRevision); flag(head.policyEnabled); long(head.installationRevision); flag(head.installationEnabled)
            string(SelfOwnedNativeCompanyProfileV2.HEAD_KDF); int(ITERATIONS); octets(head.salt); octets(head.verifier)
            int(head.failedAttempts); flag(head.reservationId != null); head.reservationId?.let { id(it) }
            int(head.throttleBoot); long(head.nextAllowedElapsedMillis); long(head.cooldownMillis)
            int(value.policies.size)
            for (record in value.policies) {
                id(record.originalActionId); long(record.headAccountRevision); long(record.headCredentialRevision)
                long(record.purposeRevision); long(record.keyRevision); octets(record.delegationBody); octets(record.policyBody)
                string(record.phase.name); flag(record.signedPolicy != null); record.signedPolicy?.let { octets(it) }
            }
            int(value.installationClaims.size)
            for (record in value.installationClaims) {
                id(record.issuedAttemptId); long(record.attemptSequence); id(record.originalRequestId)
                string(record.allocationDigest.value); string(record.definitionDigest.value); octets(record.approvalFrame)
                flag(record.signedHumanApproval != null); record.signedHumanApproval?.let { octets(it) }
                flag(record.acknowledgementFrame != null); record.acknowledgementFrame?.let { octets(it) }
                flag(record.signedAcknowledgement != null); record.signedAcknowledgement?.let { octets(it) }
                flag(record.retirementEvidence != null); record.retirementEvidence?.let { octets(it) }
            }
            flag(value.installationAdmission != null)
            value.installationAdmission?.let { admission ->
                id(admission.originalRequestId); string(admission.allocationDigest.value)
                string(admission.definitionDigest.value); long(admission.nextAttemptSequence)
            }
            flag(value.serialCustody != null)
            value.serialCustody?.let { owned(NativeCompanySerialAuthorityCustodyWireV1.encode(it)) }
            string(SelfOwnedNativeCompanyProfileV2.COMPANY_STATE_PURPOSE)
        }

    fun decodeCompanyState(bytes: ByteArray): SelfOwnedInstalledIdentityResultV2<LocalCompanyAuthorityStateV2> =
        guardedCanonical(bytes, this::encodeCompanyState) {
            read(bytes, SelfOwnedNativeCompanyProfileV2.COMPANY_STATE_DOMAIN, SelfOwnedNativeCompanyProfileV2.MAXIMUM_COMPANY_STATE_BYTES) {
                val original = nested(SelfOwnedNativeOriginalProfileV2.MAXIMUM_REGISTRY_PLAINTEXT_BYTES, SelfOwnedNativeProvisionerWireV2::decodeRegistry)
                val enrollment = nested(SelfOwnedNativeCompanyProfileV2.MAXIMUM_HEAD_ENROLLMENT_BYTES, SelfOwnedNativeProvisionerWireV2::decodeHeadEnrollment)
                val headId = uuid(); val enrollmentId = uuid(); val name = string(); val login = string()
                val account = long(); val credential = long(); val enabled = flag()
                val policyRevision = long(); val policyEnabled = flag(); val installationRevision = long(); val installationEnabled = flag()
                if (string() != SelfOwnedNativeCompanyProfileV2.HEAD_KDF || int() != ITERATIONS) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                val salt = octets(32); val verifier = octets(32); val failures = int()
                val reservation = if (flag()) uuid() else null
                val head = LocalCompanyHeadAccountV1(headId, enrollmentId, name, login, account, credential, enabled,
                    policyRevision, policyEnabled, installationRevision, installationEnabled, salt, verifier,
                    failures, reservation, int(), long(), long())
                val count = int()
                if (count > LocalCompanyAuthorityStateV1.MAX_POLICY_RECORDS) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
                val policies = List(count) {
                    val action = uuid(); val accountRevision = long(); val credentialRevision = long()
                    val purposeRevision = long(); val keyRevision = long()
                    val delegation = octets(LocalCompanyPolicyFormatV1.MAX_BODY_BYTES)
                    val policy = octets(LocalCompanyPolicyFormatV1.MAX_BODY_BYTES)
                    val label = string()
                    val phase = LocalCompanyPolicyPhaseV1.entries.singleOrNull { it.name == label }
                        ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                    LocalCompanyPolicyRecordV1(action, accountRevision, credentialRevision, purposeRevision, keyRevision,
                        delegation, policy, phase, if (flag()) octets(LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES) else null)
                }
                val claimCount = int()
                if (claimCount > LocalCompanyAuthorityStateV1.MAX_INSTALLATION_CLAIMS) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
                val claims = List(claimCount) {
                    LocalCompanyInstallationClaimV1(uuid(), long(), uuid(), PayloadDigest(digest()), PayloadDigest(digest()),
                        octets(LocalBranchInstallationWireV2.MAXIMUM_APPROVAL_FRAME_BYTES),
                        if (flag()) octets(LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES) else null,
                        if (flag()) octets(LocalBranchInstallationWireV2.MAXIMUM_ACKNOWLEDGEMENT_BYTES) else null,
                        if (flag()) octets(LocalBranchInstallationWireV2.MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES) else null,
                        if (flag()) octets(LocalCompanyInstallationClaimV1.MAXIMUM_RETIREMENT_EVIDENCE_BYTES) else null)
                }
                val admission = if (flag()) LocalCompanyInstallationAdmissionV1(uuid(), PayloadDigest(digest()),
                    PayloadDigest(digest()), long()) else null
                val custody = if (flag()) {
                    val marker = field(NativeCompanySerialAuthorityCustodyWireV1.MAXIMUM_BYTES)
                    try { NativeCompanySerialAuthorityCustodyWireV1.decode(marker) }
                    catch (_: RuntimeException) { refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR) }
                    finally { marker.fill(0) }
                } else null
                if (string() != SelfOwnedNativeCompanyProfileV2.COMPANY_STATE_PURPOSE) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
                LocalCompanyAuthorityStateV2(original, enrollment, head, policies, claims, admission, custody)
            }
        }

    /** Installer verifier message (native installer-pepper HMAC input); V2 domain only. */
    /** V2 counterpart of the V1 protected head-state commitment: enrollment body, head account and head key intents. */
    fun headStateCommitment(value: LocalCompanyAuthorityStateV2, company: LocalCompanyAuthorityBootstrapV2): ByteArray =
        frame(SelfOwnedNativeCompanyProfileV2.PROTECTED_HEAD_STATE_DOMAIN, SelfOwnedNativeCompanyProfileV2.MAXIMUM_COMPANY_STATE_BYTES) {
            owned(encodeHeadEnrollmentBody(value.enrollment.body))
            val head = value.head
            id(head.headAuthorityId); id(head.headEnrollmentId); string(head.name); string(head.login)
            long(head.accountRevision); long(head.credentialRevision); flag(head.enabled)
            long(head.policyRevision); flag(head.policyEnabled); long(head.installationRevision); flag(head.installationEnabled)
            octets(head.salt); octets(head.verifier)
            for (key in company.headKeys) {
                string(key.role.wireTag); id(key.keyId); long(key.keyRevision); string(key.phase.name)
                string(requireNotNull(key.security).name); octets(key.proof)
            }
        }

    /** Exact immutable desktop issuer tombstone reference for the same-worker P4 terminal join. */
    fun encodeRetiredInstallationClaim(company: LocalCompanyAuthorityBootstrapV2, record: LocalCompanyInstallationClaimV1): ByteArray {
        require(record.retirementEvidence != null && record.acknowledgementFrame == null && record.signedAcknowledgement == null)
        return frame(SelfOwnedNativeCompanyProfileV2.RETIRED_INSTALLATION_CLAIM_DOMAIN,
            LocalCompanyInstallationClaimV1.MAXIMUM_RETIREMENT_EVIDENCE_BYTES + LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES +
                LocalBranchInstallationWireV2.MAXIMUM_APPROVAL_FRAME_BYTES + 2048) {
            id(company.original.rootId); id(company.original.actionId); id(company.headAuthorityId); id(company.headEnrollmentId)
            id(record.issuedAttemptId); long(record.attemptSequence); id(record.originalRequestId)
            string(record.allocationDigest.value); string(record.definitionDigest.value); octets(record.approvalFrame)
            flag(record.signedHumanApproval != null); record.signedHumanApproval?.let { octets(it) }
            flag(false); flag(false); flag(true); octets(requireNotNull(record.retirementEvidence))
        }
    }

    fun installerVerifierMessage(bootstrap: SelfOwnedBootstrapV2, installerId: DomainId, credentialRevision: Long,
        salt: ByteArray, derived: ByteArray): ByteArray =
        frame(SelfOwnedNativeCompanyProfileV2.INSTALLER_VERIFIER_DOMAIN, SelfOwnedNativeCompanyProfileV2.MAXIMUM_VERIFIER_MESSAGE_BYTES) {
            id(bootstrap.rootId); id(bootstrap.organizationId); id(bootstrap.companyId); id(installerId)
            id(bootstrap.key(SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER).keyId); long(credentialRevision)
            int(ITERATIONS); string(SelfOwnedNativeCompanyProfileV2.KDF); field(salt); field(derived)
        }

    /** Company-head verifier message (native head-pepper HMAC input); V2 domain only. */
    fun headVerifierMessage(company: LocalCompanyAuthorityBootstrapV2, credentialRevision: Long,
        salt: ByteArray, derived: ByteArray): ByteArray =
        frame(SelfOwnedNativeCompanyProfileV2.HEAD_VERIFIER_DOMAIN, SelfOwnedNativeCompanyProfileV2.MAXIMUM_VERIFIER_MESSAGE_BYTES) {
            string(FRESH_COMPANY_AND_HEAD)
            id(company.original.rootId); id(company.original.organizationId); id(company.original.companyId)
            id(company.headAuthorityId); id(company.headEnrollmentId)
            id(company.key(LocalCompanyHeadKeyRoleV1.PEPPER).keyId); long(credentialRevision)
            int(ITERATIONS); string(SelfOwnedNativeCompanyProfileV2.KDF); field(salt); field(derived)
        }

    private const val HEAD_ROLE = "COMPANY_HEAD"
    private const val FRESH_COMPANY_AND_HEAD = "EXPLICIT_FRESH_COMPANY_AND_HEAD_V2"

    private fun SelfOwnedIdentityReader.headEnrollmentBody(): LocalCompanyHeadEnrollmentBodyV1 {
        val root = uuid(); val rootKey = uuid(); val organization = uuid(); val company = uuid(); val founding = uuid()
        val head = uuid(); val enrollment = uuid(); val name = string(); val login = string()
        if (string() != HEAD_ROLE || string() != LIFETIME) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        if (string() != POLICY_PURPOSE || string() != LocalCompanyHeadKeyRoleV1.POLICY.wireTag) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
        val policyKey = uuid(); val policyPoint = octets(65)
        if (string() != INSTALLATION_PURPOSE || string() != LocalCompanyHeadKeyRoleV1.INSTALLATION.wireTag) {
            refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
        }
        val installationKey = uuid(); val installationPoint = octets(65)
        return LocalCompanyHeadEnrollmentBodyV1(root, rootKey, organization, company, founding, head, enrollment,
            name, login, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, policyKey, policyPoint, installationKey, installationPoint)
    }

    private fun SelfOwnedIdentityReader.headEnrollment(): LocalCompanyHeadEnrollmentV1 {
        val body = nested(SelfOwnedNativeCompanyProfileV2.MAXIMUM_HEAD_ENROLLMENT_BODY_BYTES, SelfOwnedNativeProvisionerWireV2::decodeHeadEnrollmentBody)
        if (string() != SelfOwnedNativeCompanyProfileV2.SIGNATURE_ALGORITHM) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        return LocalCompanyHeadEnrollmentV1(body, octets(64))
    }

    private const val LIFETIME = "UNTIL_LOCAL_DISABLE"
    private const val POLICY_PURPOSE = "POLICY_PUBLISH"
    private const val INSTALLATION_PURPOSE = "FULL_D7_INSTALLATION_APPROVE"

    private fun SelfOwnedIdentityReader.bootstrapBody(): SelfOwnedBootstrapV2 {
        val revision = long(); val action = uuid(); val root = uuid(); val organization = uuid(); val company = uuid()
        val issuer = uuid(); val installer = uuid()
        val companyName = string(); val responsible = string(); val login = string()
        val protection = SelfOwnedKeyProtectionV1.entries.getOrNull(int())
            ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        val facts = SelfOwnedCompanyFactsV1(companyName, responsible, login, protection)
        val target = installedTarget()
        val namespace = nested(SelfOwnedNativeOriginalProfileV2.MAXIMUM_NAMESPACE_BYTES, SelfOwnedNativeProvisionerWireV2::decodeNamespace)
        val enrollmentStarted = flag()
        val nonce = octets(SelfOwnedNativeOriginalProfileV2.NONCE_BYTES)
        if (int() != 4) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
        val keys = List(4) {
            val roleId = int()
            val role = SelfOwnedNativeKeyRoleV1.entries.singleOrNull { it.wireId == roleId }
                ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
            val keyId = uuid()
            val phase = SelfOwnedKeyIntentPhaseV1.entries.getOrNull(int())
                ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
            val boot = if (flag()) bootSession() else null
            val security = if (flag()) SelfOwnedNativeSecurityV1.entries.getOrNull(int())
                ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR) else null
            SelfOwnedProvisionerKeyIntentV2(role, keyId, phase, boot, security,
                octets(SelfOwnedNativeOriginalProfileV2.MAXIMUM_KEY_PROOF_BYTES))
        }
        return SelfOwnedBootstrapV2(revision, action, root, organization, company, issuer, installer, facts, target,
            namespace, nonce, keys, enrollmentStarted)
    }

    private fun SelfOwnedWireWriterV1.namespaceBody(value: SelfOwnedRegistryNamespaceV2) {
        int(value.platform.wireId)
        when (value) {
            is SelfOwnedRegistryNamespaceV2.Windows -> {
                string(value.canonicalPath); string(value.ownerSid); string(value.volumeGuid)
                string(value.directoryFileId); string(value.lockFileId)
            }
            is SelfOwnedRegistryNamespaceV2.Mac -> {
                string(value.canonicalPath); int(value.uid); string(value.volumeUuid)
                long(value.directoryInode); long(value.lockInode)
            }
        }
    }

    private fun SelfOwnedIdentityReader.namespaceBody(): SelfOwnedRegistryNamespaceV2 = when (platform()) {
        SelfOwnedNativePlatformV2.WINDOWS -> SelfOwnedRegistryNamespaceV2.Windows(string(), string(), string(), string(), string())
        SelfOwnedNativePlatformV2.MACOS -> SelfOwnedRegistryNamespaceV2.Mac(string(), int(), string(), long(), long())
    }

    private fun SelfOwnedIdentityReader.platform(): SelfOwnedNativePlatformV2 =
        SelfOwnedNativePlatformV2.fromWireId(int()) ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH)

    private fun SelfOwnedWireWriterV1.bootSession(value: SelfOwnedNativeBootSessionV2) {
        int(value.platform.wireId); string(value.bootSessionId)
    }

    private fun SelfOwnedIdentityReader.bootSession(): SelfOwnedNativeBootSessionV2 {
        val platform = platform()
        val id = string()
        if (!SelfOwnedInstalledIdentityGrammarV2.canonicalUuid(id)) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        return when (platform) {
            SelfOwnedNativePlatformV2.WINDOWS -> SelfOwnedNativeBootSessionV2.Windows(id)
            SelfOwnedNativePlatformV2.MACOS -> SelfOwnedNativeBootSessionV2.Mac(id)
        }
    }

    private fun SelfOwnedIdentityReader.installedTarget(): SelfOwnedNativeInstalledTargetBindingV1 {
        val decoded = nested(SelfOwnedInstalledTargetBindingWireV1.MAXIMUM_BINDING_BYTES, { SelfOwnedInstalledTargetBindingV1.decode(it) })
        return decoded as? SelfOwnedNativeInstalledTargetBindingV1
            ?: refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PLATFORM_MISMATCH)
    }

    private fun SelfOwnedIdentityReader.signedV1(role: SelfOwnedDocumentRoleV1): SelfOwnedSignedDocumentV1 {
        val copy = field(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
        val decoded = try { SelfOwnedInstallationWireV1.decodeSigned(copy) } finally { copy.fill(0) }
        if (decoded == null || decoded.role != role) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_ROLE)
        return decoded
    }

    private fun <T> SelfOwnedIdentityReader.nested(maximum: Int, decode: (ByteArray) -> SelfOwnedInstalledIdentityResultV2<T>): T {
        val copy = field(maximum)
        val result = try { decode(copy) } finally { copy.fill(0) }
        return when (result) {
            is SelfOwnedInstalledIdentityResultV2.Data -> result.value
            is SelfOwnedInstalledIdentityResultV2.Refused -> refuse(result.code)
        }
    }

    private fun SelfOwnedIdentityReader.octets(maximum: Int): SelfOwnedOctetsV1 {
        val copy = field(maximum)
        return try { SelfOwnedOctetsV1(copy, maximum) } finally { copy.fill(0) }
    }

    private fun SelfOwnedIdentityReader.flag(): Boolean = when (int()) {
        0 -> false
        1 -> true
        else -> refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
    }

    private fun SelfOwnedWireWriterV1.flag(value: Boolean) { int(if (value) 1 else 0) }

    private fun SelfOwnedWireWriterV1.owned(bytes: ByteArray) { try { field(bytes) } finally { bytes.fill(0) } }

    private fun frame(domain: String, maximum: Int, body: SelfOwnedWireWriterV1.() -> Unit): ByteArray =
        SelfOwnedInstalledIdentityWireV2.frame(SelfOwnedNativeOriginalProfileV2.ID, domain, maximum) {
            int(SelfOwnedNativeOriginalProfileV2.FORMAT_VERSION); body()
        }

    private inline fun <T> read(bytes: ByteArray, domain: String, maximum: Int, body: SelfOwnedIdentityReader.() -> T): T {
        if (bytes.size > maximum) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        val reader = SelfOwnedIdentityReader(bytes)
        if (reader.string() != SelfOwnedNativeOriginalProfileV2.ID) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_PROFILE_UNSUPPORTED)
        if (reader.string() != domain) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_DOMAIN)
        if (reader.int() != SelfOwnedNativeOriginalProfileV2.FORMAT_VERSION) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_VERSION)
        val result = reader.body()
        reader.finish()
        return result
    }

    private inline fun <T> guardedCanonical(bytes: ByteArray, crossinline encode: (T) -> ByteArray, crossinline decode: () -> T):
        SelfOwnedInstalledIdentityResultV2<T> = selfOwnedIdentityGuarded {
        val value = decode()
        val canonical = encode(value)
        try { if (!canonical.contentEquals(bytes)) refuse(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR) }
        finally { canonical.fill(0) }
        value
    }
}
