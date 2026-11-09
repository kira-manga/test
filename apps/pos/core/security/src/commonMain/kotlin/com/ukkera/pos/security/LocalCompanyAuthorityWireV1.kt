package com.ukkera.pos.security

/**
 * New native container ONLY. Legacy P1 bytes/roles/decoders are unchanged. D7/approval/ACK
 * frames are retained as exact bounded outputs of LocalBranchInstallationWireV2, never
 * encoded by a second A0b codec. Parsing any of these bytes supplies no authority.
 */
internal object LocalCompanyAuthorityWireV1 {
    private const val PROFILE = "ukkera.local-company-authority/p256/v1"

    fun isCompanyProfile(bytes: ByteArray): Boolean = SelfOwnedWireReaderV1(bytes).string(128) == PROFILE

    fun encodeBootstrap(value: LocalCompanyAuthorityBootstrapV1): ByteArray = encode("native-bootstrap") {
        long(value.revision)
        val base = SelfOwnedInstallationWireV1.encodeBootstrap(value.original)
        try { field(base) } finally { base.fill(0) }
        id(value.headAuthorityId); id(value.headEnrollmentId); string(value.headName); string(value.headLogin)
        string("UNTIL_LOCAL_DISABLE"); string("POLICY_PUBLISH"); string("FULL_D7_INSTALLATION_APPROVE")
        int(value.headKeys.size)
        for (key in value.headKeys) {
            string(key.role.wireTag); id(key.keyId); long(key.keyRevision); string(key.phase.name)
            bool(key.startedBoot != null); key.startedBoot?.let { int(it) }
            bool(key.security != null); key.security?.let { string(it.name) }; octets(key.proof)
        }
    }

    fun decodeBootstrap(bytes: ByteArray): LocalCompanyAuthorityBootstrapV1 = decode(bytes, "native-bootstrap") {
        val revision = long()
        val baseBytes = field(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
        val base = try { SelfOwnedInstallationWireV1.decodeBootstrap(baseBytes) } finally { baseBytes.fill(0) }
        val head = id(); val enrollment = id(); val name = string(256); val login = string(64)
        require(string(64) == "UNTIL_LOCAL_DISABLE" && string(64) == "POLICY_PUBLISH" &&
            string(64) == "FULL_D7_INSTALLATION_APPROVE")
        require(int() == 3)
        val keys = List(3) {
            val role = string(64)
            val kind = requireNotNull(LocalCompanyHeadKeyRoleV1.entries.singleOrNull { it.wireTag == role })
            val key = id(); val keyRevision = long()
            val phaseName = string(32)
            val phase = requireNotNull(SelfOwnedKeyIntentPhaseV1.entries.singleOrNull { it.name == phaseName })
            val boot = if (bool()) int() else null
            val security = if (bool()) {
                val label = string(32)
                requireNotNull(SelfOwnedNativeSecurityV1.entries.singleOrNull { it.name == label })
            } else null
            LocalCompanyHeadKeyIntentV1(kind, key, keyRevision, phase, boot, security, octets(65))
        }
        LocalCompanyAuthorityBootstrapV1(revision, base, head, enrollment, name, login,
            LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, keys)
    }

    fun encodeEnrollmentBody(value: LocalCompanyHeadEnrollmentBodyV1): ByteArray = encode("head-enrollment-body") {
        id(value.rootId); id(value.rootKeyId); id(value.organizationId); id(value.companyId); id(value.foundingActionId)
        id(value.headAuthorityId); id(value.headEnrollmentId); string(value.headName); string(value.headLogin)
        string("COMPANY_HEAD"); string("UNTIL_LOCAL_DISABLE")
        string("POLICY_PUBLISH"); string("HEAD_POLICY_SIGNING"); id(value.policyKeyId); octets(value.policyPoint)
        string("FULL_D7_INSTALLATION_APPROVE"); string("HEAD_INSTALLATION_SIGNING")
        id(value.installationKeyId); octets(value.installationPoint)
    }

    fun decodeEnrollmentBody(bytes: ByteArray): LocalCompanyHeadEnrollmentBodyV1 = decode(bytes, "head-enrollment-body") {
        val root = id(); val rootKey = id(); val organization = id(); val company = id(); val founding = id()
        val head = id(); val enrollment = id(); val name = string(256); val login = string(64)
        require(string(64) == "COMPANY_HEAD" && string(64) == "UNTIL_LOCAL_DISABLE")
        require(string(64) == "POLICY_PUBLISH" && string(64) == "HEAD_POLICY_SIGNING")
        val policyKey = id(); val policyPoint = octets(65)
        require(string(64) == "FULL_D7_INSTALLATION_APPROVE" && string(64) == "HEAD_INSTALLATION_SIGNING")
        LocalCompanyHeadEnrollmentBodyV1(root, rootKey, organization, company, founding, head, enrollment,
            name, login, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, policyKey, policyPoint, id(), octets(65))
    }

    fun encodeEnrollment(value: LocalCompanyHeadEnrollmentV1): ByteArray = encode("root-signed-head-enrollment") {
        val body = encodeEnrollmentBody(value.body)
        try { field(body) } finally { body.fill(0) }
        string("ECDSA_P256_SHA256_P1363_LOW_S"); octets(value.signature)
    }

    fun decodeEnrollment(bytes: ByteArray): LocalCompanyHeadEnrollmentV1 = decode(bytes, "root-signed-head-enrollment") {
        val bodyBytes = field(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
        val body = try { decodeEnrollmentBody(bodyBytes) } finally { bodyBytes.fill(0) }
        require(string(64) == "ECDSA_P256_SHA256_P1363_LOW_S")
        LocalCompanyHeadEnrollmentV1(body, octets(64))
    }

    /** Opt-in serial wrapper only; absent custody executes the unchanged legacy encoder. */
    fun encodeState(value: LocalCompanyAuthorityStateV1): ByteArray {
        val custody = value.serialCustody ?: return encodeLegacyState(value)
        return encode(NativeCompanySerialAuthorityCustodyWireV1.COMPANY_STATE_DOMAIN,
            SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES) {
            val base = encodeLegacyState(value.copy(serialCustody = null))
            val marker = NativeCompanySerialAuthorityCustodyWireV1.encode(custody)
            try { field(base); field(marker) } finally { base.fill(0); marker.fill(0) }
        }
    }

    private fun encodeLegacyState(value: LocalCompanyAuthorityStateV1): ByteArray =
        encode("native-company-registry", SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES) {
            val base = SelfOwnedInstallationWireV1.encodeRegistry(value.original)
            val enrollment = encodeEnrollment(value.enrollment)
            try { field(base); field(enrollment) } finally { base.fill(0); enrollment.fill(0) }
            head(value.head)
            int(value.policies.size)
            for (record in value.policies) {
                id(record.originalActionId); long(record.headAccountRevision); long(record.headCredentialRevision)
                long(record.purposeRevision); long(record.keyRevision); octets(record.delegationBody); octets(record.policyBody)
                string(record.phase.name); bool(record.signedPolicy != null); record.signedPolicy?.let { octets(it) }
            }
            int(value.installationClaims.size)
            for (record in value.installationClaims) installationClaim(record)
            bool(value.installationAdmission != null)
            value.installationAdmission?.let { admission ->
                id(admission.originalRequestId); string(admission.allocationDigest.value)
                string(admission.definitionDigest.value); long(admission.nextAttemptSequence)
            }
        }

    fun decodeState(bytes: ByteArray): LocalCompanyAuthorityStateV1 {
        require(bytes.size in 1..SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES)
        val header = SelfOwnedWireReaderV1(bytes)
        require(header.string(128) == PROFILE)
        val domain = header.string(128)
        if (domain != NativeCompanySerialAuthorityCustodyWireV1.COMPANY_STATE_DOMAIN) return decodeLegacyState(bytes)
        return decode(bytes, NativeCompanySerialAuthorityCustodyWireV1.COMPANY_STATE_DOMAIN,
            SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES) {
            val baseBytes = field(SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES)
            val base = try { decodeLegacyState(baseBytes) } finally { baseBytes.fill(0) }
            val markerBytes = field(NativeCompanySerialAuthorityCustodyWireV1.MAXIMUM_BYTES)
            val marker = try { NativeCompanySerialAuthorityCustodyWireV1.decode(markerBytes) }
                finally { markerBytes.fill(0) }
            base.copy(serialCustody = marker)
        }
    }

    private fun decodeLegacyState(bytes: ByteArray): LocalCompanyAuthorityStateV1 =
        decode(bytes, "native-company-registry", SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES) {
            val baseBytes = field(SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES)
            val base = try { SelfOwnedInstallationWireV1.decodeRegistry(baseBytes) } finally { baseBytes.fill(0) }
            val enrollmentBytes = field(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES)
            val enrollment = try { decodeEnrollment(enrollmentBytes) } finally { enrollmentBytes.fill(0) }
            val head = head()
            val count = int(); require(count <= LocalCompanyAuthorityStateV1.MAX_POLICY_RECORDS)
            val policies = List(count) {
                val action = id(); val accountRevision = long(); val credentialRevision = long()
                val purposeRevision = long(); val keyRevision = long()
                val delegation = octets(LocalCompanyPolicyFormatV1.MAX_BODY_BYTES)
                val policy = octets(LocalCompanyPolicyFormatV1.MAX_BODY_BYTES)
                val label = string(32)
                val phase = requireNotNull(LocalCompanyPolicyPhaseV1.entries.singleOrNull { it.name == label })
                LocalCompanyPolicyRecordV1(action, accountRevision, credentialRevision, purposeRevision, keyRevision,
                    delegation, policy, phase, if (bool()) octets(LocalCompanyPolicyFormatV1.MAX_ENVELOPE_BYTES) else null)
            }
            val claimCount = int(); require(claimCount <= LocalCompanyAuthorityStateV1.MAX_INSTALLATION_CLAIMS)
            val claims = List(claimCount) {
                LocalCompanyInstallationClaimV1(id(), long(), id(), PayloadDigest(string(64)), PayloadDigest(string(64)),
                    octets(LocalBranchInstallationWireV2.MAXIMUM_APPROVAL_FRAME_BYTES),
                    if (bool()) octets(LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES) else null,
                    if (bool()) octets(LocalBranchInstallationWireV2.MAXIMUM_ACKNOWLEDGEMENT_BYTES) else null,
                    if (bool()) octets(LocalBranchInstallationWireV2.MAXIMUM_SIGNED_ACKNOWLEDGEMENT_BYTES) else null,
                    if (bool()) octets(LocalCompanyInstallationClaimV1.MAXIMUM_RETIREMENT_EVIDENCE_BYTES) else null)
            }
            val admission = if (bool()) LocalCompanyInstallationAdmissionV1(id(), PayloadDigest(string(64)),
                PayloadDigest(string(64)), long()) else null
            LocalCompanyAuthorityStateV1(base, enrollment, head, policies, claims, admission)
        }

    /** AAD covers the complete explicit seven-key original, not an editable public root. */
    fun registryAad(value: LocalCompanyAuthorityBootstrapV1): ByteArray = encode("registry-aad") {
        val bytes = encodeBootstrap(value)
        try { field(bytes) } finally { bytes.fill(0) }
    }

    fun encodeEnvelope(value: SelfOwnedRegistryEnvelopeV1): ByteArray =
        encode("encrypted-native-registry", SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES) {
            require(value.nonce.size == 12 && value.signature.size == 64)
            field(value.nonce); field(value.ciphertext); field(value.signature)
        }

    fun decodeEnvelope(bytes: ByteArray): SelfOwnedRegistryEnvelopeV1 =
        decode(bytes, "encrypted-native-registry", SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES) {
            val nonce = field(12); val ciphertext = field(SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES + 16)
            val signature = field(64)
            require(nonce.size == 12 && ciphertext.size >= 16 && signature.size == 64)
            SelfOwnedRegistryEnvelopeV1(nonce, ciphertext, signature)
        }

    /** Not a containing registry hash: this exact independently retained head record predates approval. */
    fun headStateCommitment(value: LocalCompanyAuthorityStateV1, original: LocalCompanyAuthorityBootstrapV1): ByteArray =
        encode("protected-head-state") {
            val enrollmentBody = encodeEnrollmentBody(value.enrollment.body)
            try { field(enrollmentBody) } finally { enrollmentBody.fill(0) }
            head(value.head)
            for (key in original.headKeys) {
                string(key.role.wireTag); id(key.keyId); long(key.keyRevision); string(key.phase.name)
                string(requireNotNull(key.security).name); octets(key.proof)
            }
        }

    /** Exact immutable issuer tombstone reference for the same-worker P4 terminal join. */
    fun encodeRetiredInstallationClaim(original: LocalCompanyAuthorityBootstrapV1, claim: LocalCompanyInstallationClaimV1): ByteArray {
        require(claim.retirementEvidence != null && claim.acknowledgementFrame == null && claim.signedAcknowledgement == null)
        return encode("original-retired-issuer-claim", LocalCompanyInstallationClaimV1.MAXIMUM_RETIREMENT_EVIDENCE_BYTES +
            LocalBranchInstallationWireV2.MAXIMUM_SIGNED_APPROVAL_BYTES + LocalBranchInstallationWireV2.MAXIMUM_APPROVAL_FRAME_BYTES + 2048) {
            id(original.original.rootId); id(original.original.actionId); id(original.headAuthorityId); id(original.headEnrollmentId)
            installationClaim(claim)
        }
    }

    private fun SelfOwnedWireWriterV1.installationClaim(record: LocalCompanyInstallationClaimV1) {
        id(record.issuedAttemptId); long(record.attemptSequence); id(record.originalRequestId)
        string(record.allocationDigest.value); string(record.definitionDigest.value); octets(record.approvalFrame)
        bool(record.signedHumanApproval != null); record.signedHumanApproval?.let { octets(it) }
        bool(record.acknowledgementFrame != null); record.acknowledgementFrame?.let { octets(it) }
        bool(record.signedAcknowledgement != null); record.signedAcknowledgement?.let { octets(it) }
        bool(record.retirementEvidence != null); record.retirementEvidence?.let { octets(it) }
    }

    private fun SelfOwnedWireWriterV1.head(value: LocalCompanyHeadAccountV1) {
        id(value.headAuthorityId); id(value.headEnrollmentId); string(value.name); string(value.login)
        long(value.accountRevision); long(value.credentialRevision); bool(value.enabled)
        long(value.policyRevision); bool(value.policyEnabled); long(value.installationRevision); bool(value.installationEnabled)
        string("PBKDF2-HMAC-SHA256/POST-HEAD-HMAC-SHA256/256")
        int(SelfOwnedInstallationProfileV1.PASSWORD_ITERATIONS); octets(value.salt); octets(value.verifier)
        int(value.failedAttempts); bool(value.reservationId != null); value.reservationId?.let { id(it) }
        int(value.throttleBoot); long(value.nextAllowedElapsedMillis); long(value.cooldownMillis)
    }

    private fun SelfOwnedWireReaderV1.head(): LocalCompanyHeadAccountV1 {
        val head = id(); val enrollment = id(); val name = string(256); val login = string(64)
        val account = long(); val credential = long(); val enabled = bool()
        val policyRevision = long(); val policyEnabled = bool(); val installationRevision = long(); val installationEnabled = bool()
        require(string(64) == "PBKDF2-HMAC-SHA256/POST-HEAD-HMAC-SHA256/256")
        require(int() == SelfOwnedInstallationProfileV1.PASSWORD_ITERATIONS)
        val salt = octets(32); val verifier = octets(32); val failures = int()
        val reservation = if (bool()) id() else null
        return LocalCompanyHeadAccountV1(head, enrollment, name, login, account, credential, enabled,
            policyRevision, policyEnabled, installationRevision, installationEnabled, salt, verifier,
            failures, reservation, int(), long(), long())
    }

    private fun encode(domain: String, maximum: Int = SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES,
        body: SelfOwnedWireWriterV1.() -> Unit): ByteArray {
        val writer = SelfOwnedWireWriterV1(maximum)
        return try { writer.string(PROFILE); writer.string(domain); writer.int(1); writer.body(); writer.finish() }
        finally { writer.clear() }
    }

    private fun <T> decode(bytes: ByteArray, domain: String,
        maximum: Int = SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES,
        body: SelfOwnedWireReaderV1.() -> T): T {
        require(bytes.size in 1..maximum)
        val reader = SelfOwnedWireReaderV1(bytes)
        require(reader.string(128) == PROFILE && reader.string(128) == domain && reader.int() == 1)
        return reader.body().also { reader.finish() }
    }
}
