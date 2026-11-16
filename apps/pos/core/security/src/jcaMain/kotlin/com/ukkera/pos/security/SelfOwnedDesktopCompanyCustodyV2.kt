package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * Windows/macOS company workflow custody over the seven-key V2 company container. It mirrors the
 * Android V1 registry ledgers, original-root endorsements and installer authentication step for
 * step, with only the at-rest custody changed:
 *
 * - each ledger is the shared record grammar inside the closed V2 ledger frame, sealed
 *   by the V2 registry key with its own ledger AAD (embedding the complete company-registry AAD)
 *   and signed by the issuer over `registrySignature(aad, nonce, ciphertext)`; the signature is
 *   verified BEFORE decryption, the plaintext must be canonical and every record is validated
 *   against the actual bootstrap, root document digest and root fingerprint on EVERY read;
 * - writes validate the transition and records first, reject identity collisions across ledgers,
 *   reserve completion capacity, seal, atomically replace, and require the exact authenticated
 *   read-back;
 * - the original root signs only the shared, client-verified endorsement messages (the platform
 *   enforces [SelfOwnedNativeCompanyLedgerWireV2.requireRootEndorsementMessage]); each signature
 *   is preceded and followed by the full current-approval re-check and verified with the actual
 *   root point before it is returned;
 * - head approvals come only from [SelfOwnedDesktopCompanyHeadV2] (durable throttle, verified boot
 *   ledger, revocation serialized with commit).
 *
 * Business publications use the unchanged shared record and endorsement grammar. Serial authority
 * adds the mandatory company-container marker before its companion ledger cut, with exact-cut
 * recovery only; an unresolved signing intent never regenerates a key or output.
 */
internal class SelfOwnedDesktopCompanyCustodyV2(
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: SelfOwnedProvisionerRegistryStoreV2,
    keys: SelfOwnedOriginalProvisionerKeys,
    private val work: AndroidOwnedInstallationWork,
    private val issuance: AndroidLocalCompanyIssuanceGate,
) : SelfOwnedCompanyCustody {
    private val keys: SelfOwnedCompanyNativeKeysV2 = SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys)
    private val head = SelfOwnedDesktopCompanyHeadV2(environment, registry, this.keys, work, issuance)

    override val format: SelfOwnedOriginalFormat get() = SelfOwnedOriginalFormat.V2
    override fun assertContinuity() = registry.assertContinuity()
    override fun companyOriginal(): SelfOwnedCompanyBootstrapView? = registry.companyBootstrapRecordV2()?.let { SelfOwnedCompanyBootstrapView.of(it) }
    override fun companyState(): SelfOwnedCompanyStateView? = head.readState()?.let { SelfOwnedCompanyStateView.of(it) }
    override fun rootFingerprint(): String = JvmP256Primitives.rootFingerprint(keys.rootBody())


    // Exact same-owner authenticated envelope identity for actual history read-close/reobserve.
    private var businessIdentity: Pair<SelfOwnedOctetsV1, NativeCompanyBusinessPublicationStateV1>? = null

    override fun readBusinessPublications(): NativeCompanyBusinessPublicationStateV1? {
        val company = head.readState() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val source = readBusinessPublicationsSource()
        val custody = company.serialCustody
        if (custody == null) known(source?.records.orEmpty().none { it.kind.isOriginalSerialKindV1() })
        else try { custody.requireSettledSource(source) } catch (_: IllegalArgumentException) { uncertain() }
        return source
    }

    private fun readBusinessPublicationsSource(): NativeCompanyBusinessPublicationStateV1? {
        val state = readLedger(SelfOwnedBusinessPublicationLedgerV2, NativeCompanyBusinessPublicationWireV1::decode,
            NativeCompanyBusinessPublicationWireV1::encode, ::validateBusinessPublications) { bytes, value ->
            val previous = businessIdentity
            if (previous != null && previous.first.same(bytes)) {
                known(previous.second == value)
                previous.second
            } else value.also { businessIdentity = SelfOwnedOctetsV1(bytes) to it }
        }
        if (state == null) businessIdentity = null
        return state
    }

    override fun retainBusinessPublications(next: NativeCompanyBusinessPublicationStateV1): NativeCompanyBusinessPublicationStateV1 {
        val company = head.readState() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val previous = readBusinessPublications()
        NativeCompanyBusinessPublicationWireV1.validateTransition(previous, next)
        validateBusinessPublications(next, requireCompany())
        val beforeIds = previous?.records.orEmpty().flatMap { it.reservedIdentityIdsV1() }.toSet()
        val newIds = next.records.flatMap { it.reservedIdentityIdsV1() }.filterNot { it in beforeIds }
        requireNoNewPairingIdentityCollisions(newIds)
        requireNoNewPolicyIdentityCollisions(newIds)
        requireNoNewDeviceAdmissionIdentityCollisions(newIds)
        val employees = readEmployeeEnrollments()?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        if (newIds.any { it in employees }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        // Reserve all completion bytes BEFORE retaining the permanent mandatory marker.
        val plaintext = NativeCompanyBusinessPublicationWireV1.encodeWithCompletionCapacity(next)
        try {
            val changed = next.records.filter { it.kind.isOriginalSerialKindV1() && it !in previous?.records.orEmpty() }
            known(changed.size <= 1)
            val intent = changed.singleOrNull()?.let {
                val pending = try { company.serialCustody?.beginNext(previous, next, it)
                    ?: NativeCompanySerialAuthorityCustodyV1.firstIntent(previous, next, it) }
                catch (_: IllegalArgumentException) { uncertain() }
                head.retainSerialCustody(company, pending).serialCustody ?: uncertain()
            }
            replaceLedger(SelfOwnedBusinessPublicationLedgerV2, plaintext)
            if (intent != null) {
                val actual = readBusinessPublicationsSource() ?: uncertain()
                val current = head.readState() ?: uncertain()
                known(actual == next && current.serialCustody == intent)
                val settled = try { intent.settleActualNext(actual) } catch (_: IllegalArgumentException) { uncertain() }
                head.retainSerialCustody(current, settled)
            }
        } finally { plaintext.fill(0) }
        return (readBusinessPublications() ?: uncertain()).also { known(it == next) }
    }

    override fun readOriginalSerialCustody(): OriginalLocalSerialCustodyReferenceV1 =
        head.readState()?.serialCustody?.reference() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)

    override fun recoverOriginalSerialCustody(id: DomainId): OriginalLocalSerialCustodyReferenceV1 {
        val state = head.readState() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val custody = state.serialCustody ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val pending = custody.intent
        if (pending == null) {
            val source = readBusinessPublications() ?: uncertain()
            known(source.records.any { it.kind.isOriginalSerialKindV1() && it.originalActionId == id })
            return custody.reference()
        }
        if (pending.originalActionId != id) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val source = readBusinessPublicationsSource() ?: uncertain()
        val settled = try { custody.settleActualNext(source) } catch (_: IllegalArgumentException) { uncertain() }
        val retained = head.retainSerialCustody(state, settled).serialCustody ?: uncertain()
        readBusinessPublications() ?: uncertain()
        return retained.reference()
    }

    private fun validateBusinessPublications(value: NativeCompanyBusinessPublicationStateV1, original: LocalCompanyAuthorityBootstrapV2) {
        val company = CompanyScope(original.original.organizationId, original.original.companyId)
        val state = head.readState() ?: uncertain()
        val reserved = SelfOwnedDesktopCompanyPolicyPublicationV2.reservedIds(original, state)
        known(value.records.all { it.company == company && it.originalActionId !in reserved && it.keyId !in reserved &&
            it.originalActionId != it.keyId && it.originalActionId != it.publicationId && it.keyId != it.publicationId })
        val ids = value.records.flatMap { listOf(it.originalActionId, it.keyId) }
        known(ids.distinct().size == ids.size)
        value.records.filter { it.phase == CompanyBusinessPublicationPhase.COMPLETED }.forEach(::verifyBusinessPublication)
    }

    override fun authenticateBusinessPublication(id: DomainId, digest: PayloadDigest, login: String, secret: CharArray,
        deadline: Long): SelfOwnedCompanyHeadApproval = SelfOwnedCompanyHeadApproval.DesktopV2(
        head.authenticate(SelfOwnedDesktopHeadCeremonyV2.BUSINESS_PUBLICATION, id, digest, login, secret, deadline))
    override fun requireBusinessPublicationStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        id: DomainId, digest: PayloadDigest) = head.requireStillCurrent(desktop(approval), v2(state),
            SelfOwnedDesktopHeadCeremonyV2.BUSINESS_PUBLICATION, id, digest)

    override fun signBusinessPublicationEndorsement(record: NativeCompanyBusinessPublicationRecordV1,
        approval: SelfOwnedCompanyHeadApproval): ByteArray {
        val original = requireOriginalForKey()
        val retained = readBusinessPublications()?.records?.singleOrNull { it.originalActionId == record.originalActionId }
        val approved = record.head ?: keyFailure()
        if (retained != record || record.phase != CompanyBusinessPublicationPhase.SIGNING_INTENT ||
            approved.accountRevision != approval.accountRevision || approved.credentialRevision != approval.credentialRevision ||
            approved.boot != approval.boot || approved.expiresElapsedMillis != approval.expires) keyFailure()
        val action = NativeCompanyBusinessPublicationWireV1.actionMessage(record, original)
        val digest = try { sha256(action) } finally { action.fill(0) }
        val current = {
            requireBusinessPublicationStillCurrent(approval, companyState() ?: keyFailure(), record.originalActionId, digest)
            if (readBusinessPublications()?.records?.singleOrNull { it.originalActionId == record.originalActionId } != record) keyFailure()
        }
        current()
        return signRoot(NativeCompanyBusinessPublicationWireV1.endorsementMessage(record, original), current)
    }
    override fun verifyBusinessPublication(record: NativeCompanyBusinessPublicationRecordV1) {
        if (record.phase != CompanyBusinessPublicationPhase.COMPLETED || record.rootEndorsement.size != 64) keyFailure()
        val original = requireOriginalForKey()
        if (record.company != CompanyScope(original.original.organizationId, original.original.companyId)) keyFailure()
        verifyRoot(NativeCompanyBusinessPublicationWireV1.endorsementMessage(record, original), record.rootEndorsement)
        val point = record.publicKey.copyBytes(); val content = record.content.copyBytes(); val signature = record.contentSignature.copyBytes()
        try { if (!nativeBusinessPublicationVerifyEd25519(point, content, signature)) keyFailure() }
        finally { point.fill(0); content.fill(0); signature.fill(0) }
    }

    // ---- Ledger custody -------------------------------------------------------------------------

    private fun requireCompany(): LocalCompanyAuthorityBootstrapV2 =
        registry.companyBootstrapRecordV2() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)

    private fun <T> readLedger(kind: SelfOwnedCompanyLedgerDefinitionV2, decode: (ByteArray) -> T, encode: (T) -> ByteArray,
        validate: (T, LocalCompanyAuthorityBootstrapV2) -> Unit, observed: (ByteArray, T) -> T = { _, state -> state }): T? {
        assertContinuity()
        val company = requireCompany()
        companyState() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
        val stored = when (kind) {
            is SelfOwnedCompanyLedgerKindV2 -> registry.companyLedgerEnvelopeBytes(kind)
            SelfOwnedBusinessPublicationLedgerV2 -> registry.businessPublicationEnvelopeBytes()
        } ?: return null
        try {
            val envelope = when (val decoded = SelfOwnedNativeCompanyLedgerWireV2.decodeEnvelope(stored)) {
                is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
                is SelfOwnedInstalledIdentityResultV2.Refused -> uncertain()
            }
            val aad = SelfOwnedNativeCompanyLedgerWireV2.aad(kind, company)
            try {
                SelfOwnedProvisionerRegistryV2.requireIssuerSignature(keys, aad, envelope)
                val framed = keys.openRegistryV2(aad, envelope)
                try {
                    val records = when (val decoded = SelfOwnedNativeCompanyLedgerWireV2.decodeLedger(kind, framed)) {
                        is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
                        is SelfOwnedInstalledIdentityResultV2.Refused -> uncertain()
                    }
                    try {
                        val state = try { decode(records) } catch (_: IllegalArgumentException) { uncertain() }
                        val canonical = encode(state)
                        try { known(canonical.contentEquals(records)) } finally { canonical.fill(0) }
                        validate(state, company); assertContinuity()
                        return observed(stored, state)
                    } finally { records.fill(0) }
                } finally { framed.fill(0) }
            } finally { aad.fill(0); envelope.clear() }
        } finally { stored.fill(0) }
    }

    /** Seals the exact (capacity-checked) record grammar [plaintext] as the [kind] ledger. */
    private fun replaceLedger(kind: SelfOwnedCompanyLedgerDefinitionV2, plaintext: ByteArray) {
        val company = requireCompany()
        val framed = try { SelfOwnedNativeCompanyLedgerWireV2.encodeLedger(kind, plaintext) } finally { plaintext.fill(0) }
        val aad = SelfOwnedNativeCompanyLedgerWireV2.aad(kind, company)
        val envelope = try { keys.sealRegistryV2(aad, framed) } finally { framed.fill(0); aad.fill(0) }
        val bytes = try { SelfOwnedNativeCompanyLedgerWireV2.encodeEnvelope(envelope) } finally { envelope.clear() }
        try {
            when (kind) {
                is SelfOwnedCompanyLedgerKindV2 -> registry.replaceCompanyLedgerEnvelope(kind, bytes)
                SelfOwnedBusinessPublicationLedgerV2 -> registry.replaceBusinessPublicationEnvelope(bytes)
            }
        } finally { bytes.fill(0) }
    }

    override fun readEmployeeEnrollments(): NativeEmployeeEnrollmentStateV1? = readLedger(SelfOwnedCompanyLedgerKindV2.EMPLOYEE_ENROLLMENTS,
        NativeEmployeeEnrollmentWireV1::decode, NativeEmployeeEnrollmentWireV1::encode, ::validateEmployeeEnrollments)

    override fun retainEmployeeEnrollments(next: NativeEmployeeEnrollmentStateV1): NativeEmployeeEnrollmentStateV1 {
        val company = requireCompany()
        val previous = readEmployeeEnrollments()
        NativeEmployeeEnrollmentWireV1.validateTransition(previous, next)
        validateEmployeeEnrollments(next, company)
        val existingActions = previous?.records.orEmpty().map { it.originalActionId }.toSet()
        requireNoNewPairingIdentityCollisions(next.records.filter { it.originalActionId !in existingActions }.flatMap { it.nativeIds() })
        val previousIds = previous?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        requireNoNewBusinessIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds })
        requireNoNewPolicyIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds })
        requireNoNewDeviceAdmissionIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds })
        replaceLedger(SelfOwnedCompanyLedgerKindV2.EMPLOYEE_ENROLLMENTS, NativeEmployeeEnrollmentWireV1.encodeWithCompletionCapacity(next))
        return requireNotNull(readEmployeeEnrollments()).also { known(it == next) }
    }

    override fun readEmployeePolicyActivations(): NativeEmployeePolicyActivationStateV1? =
        readLedger(SelfOwnedCompanyLedgerKindV2.EMPLOYEE_POLICY_ACTIVATIONS, NativeEmployeePolicyActivationWireV1::decode,
            NativeEmployeePolicyActivationWireV1::encode, ::validateEmployeePolicyActivations)

    override fun retainEmployeePolicyActivations(next: NativeEmployeePolicyActivationStateV1): NativeEmployeePolicyActivationStateV1 {
        val company = requireCompany()
        val previous = readEmployeePolicyActivations()
        NativeEmployeePolicyActivationWireV1.validateTransition(previous, next)
        validateEmployeePolicyActivations(next, company)
        val existingActions = previous?.records.orEmpty().map { it.originalActionId }.toSet()
        requireNoNewPairingIdentityCollisions(next.records.filter { it.originalActionId !in existingActions }.flatMap { it.nativeIds() })
        val previousIds = previous?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        requireNoNewBusinessIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds })
        requireNoNewDeviceAdmissionIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds })
        replaceLedger(SelfOwnedCompanyLedgerKindV2.EMPLOYEE_POLICY_ACTIVATIONS,
            NativeEmployeePolicyActivationWireV1.encodeWithCompletionCapacity(next))
        return requireNotNull(readEmployeePolicyActivations()).also { known(it == next) }
    }

    override fun readDevicePairings(): NativeDevicePairingStateV1? = readLedger(SelfOwnedCompanyLedgerKindV2.DEVICE_PAIRINGS,
        NativeDevicePairingWireV1::decode, NativeDevicePairingWireV1::encode, ::validateDevicePairings)

    override fun retainDevicePairings(next: NativeDevicePairingStateV1): NativeDevicePairingStateV1 {
        val company = requireCompany()
        val previous = readDevicePairings()
        NativeDevicePairingWireV1.validateTransition(previous, next)
        validateDevicePairings(next, company)
        val previousIds = previous?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        requireNoNewBusinessIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds })
        requireNoNewPolicyIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds })
        requireNoNewDeviceAdmissionIdentityCollisions(next.records.flatMap { it.nativeIds() }.filterNot { it in previousIds })
        replaceLedger(SelfOwnedCompanyLedgerKindV2.DEVICE_PAIRINGS, NativeDevicePairingWireV1.encodeWithCompletionCapacity(next))
        return requireNotNull(readDevicePairings()).also { known(it == next) }
    }

    override fun devicePairingReservedIdentityIds(): Set<DomainId> =
        readDevicePairings()?.records.orEmpty().flatMap { it.nativeIds() }.toSet()

    override fun readDeviceAdmissions(): NativeDeviceAdmissionStateV1? = readLedger(SelfOwnedCompanyLedgerKindV2.DEVICE_ADMISSIONS,
        NativeDeviceAdmissionWireV1::decode, NativeDeviceAdmissionWireV1::encode, ::validateDeviceAdmissions)

    override fun retainDeviceAdmissions(next: NativeDeviceAdmissionStateV1): NativeDeviceAdmissionStateV1 {
        val company = requireCompany()
        val previous = readDeviceAdmissions()
        NativeDeviceAdmissionWireV1.validateTransition(previous, next)
        validateDeviceAdmissions(next, company)
        requireNoNewBusinessIdentityCollisions(next.records.map { it.dispatchId }.filterNot { id -> previous?.records.orEmpty().any { it.dispatchId == id } })
        NativeDeviceAdmissionWireV1.requireCompletionCapacity(next, 4096,
            next.records.maxOf { it.pairing.body.requested().retainedAdmissions.size })
        replaceLedger(SelfOwnedCompanyLedgerKindV2.DEVICE_ADMISSIONS, NativeDeviceAdmissionWireV1.encode(next))
        return requireNotNull(readDeviceAdmissions()).also { known(it == next) }
    }

    override fun deviceAdmissionReservedIdentityIds(): Set<DomainId> =
        readDeviceAdmissions()?.records.orEmpty().map { it.dispatchId }.toSet()

    private fun requireNoNewBusinessIdentityCollisions(ids: List<DomainId>) {
        val reserved = readBusinessPublications()?.records.orEmpty().flatMap { it.reservedIdentityIdsV1() }.toSet()
        if (ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    private fun requireNoNewPairingIdentityCollisions(ids: List<DomainId>) {
        val reserved = devicePairingReservedIdentityIds()
        if (ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    private fun requireNoNewPolicyIdentityCollisions(ids: List<DomainId>) {
        if (registry.companyLedgerEnvelopeBytes(SelfOwnedCompanyLedgerKindV2.EMPLOYEE_POLICY_ACTIVATIONS)?.also { it.fill(0) } == null) return
        val reserved = readEmployeePolicyActivations()?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        if (ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    private fun requireNoNewDeviceAdmissionIdentityCollisions(ids: List<DomainId>) {
        if (registry.companyLedgerEnvelopeBytes(SelfOwnedCompanyLedgerKindV2.DEVICE_ADMISSIONS)?.also { it.fill(0) } == null) return
        val reserved = deviceAdmissionReservedIdentityIds()
        if (ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }

    // ---- Record validation (mirror of the Android V1 registry) --------------------------------

    private fun rootDocumentDigest(): PayloadDigest {
        val state = companyState() ?: uncertain()
        val bytes = state.original.root.copyWireBytes()
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }

    private fun validateDeviceAdmissions(value: NativeDeviceAdmissionStateV1, company: LocalCompanyAuthorityBootstrapV2) {
        val pairings = readDevicePairings() ?: uncertain()
        val reserved = company.allIds().toSet() + pairings.records.flatMap { it.nativeIds() + it.body.origin.reservedOriginalIds() } +
            readEmployeeEnrollments()?.records.orEmpty().flatMap { it.nativeIds() } +
            readEmployeePolicyActivations()?.records.orEmpty().flatMap { it.nativeIds() }
        for (record in value.records) {
            known(pairings.records.singleOrNull { it.body.originalActionId == record.pairing.body.originalActionId } == record.pairing &&
                record.dispatchId !in reserved)
        }
    }

    private fun validateDevicePairings(value: NativeDevicePairingStateV1, company: LocalCompanyAuthorityBootstrapV2) {
        val original = SelfOwnedCompanyBootstrapView.of(company)
        val rootDigest = rootDocumentDigest()
        val fingerprint = rootFingerprint()
        val reserved = original.allIds().toSet()
        val otherIds = readEmployeeEnrollments()?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        for (record in value.records) {
            val origin = record.body.origin; val requested = record.body.requested(); val target = requested.target
            known(origin.coordinates.scope.organizationId == original.original.organizationId &&
                origin.coordinates.scope.companyId == original.original.companyId && origin.rootId == original.original.rootId &&
                origin.rootKeyId == original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId && origin.rootDocumentDigest == rootDigest &&
                origin.foundingActionId == original.original.actionId && origin.headAuthorityId == original.headAuthorityId &&
                origin.headEnrollmentId == original.headEnrollmentId && origin.rootFingerprint == fingerprint &&
                record.body.preparedHead.companyName == original.original.facts.companyName &&
                record.body.preparedHead.headName == original.headName && record.body.preparedHead.headLogin == original.headLogin &&
                record.nativeIds().none { it in reserved || it in origin.reservedOriginalIds() || it in otherIds } &&
                requested.admission.deviceId != origin.coordinates.deviceId)
            val point = target.copyPublicPoint()
            try {
                JvmP256Primitives.publicKey(point) // Validate the actual curve point, not attestation from DATA.
                record.possession?.let { retained ->
                    val bytes = retained.copyBytes()
                    val proof = try { LocalDevicePairingPossession.decode(bytes) } finally { bytes.fill(0) }
                    val challenge = LocalDevicePairingChallenge.fromNative(record.body)
                    val message = NativeDevicePairingWireV1.possessionMessage(challenge); val signature = proof.copySignature()
                    try { known(JvmP256Primitives.verify(point, message, signature)) } finally { message.fill(0); signature.fill(0) }
                }
            } finally { point.fill(0) }
            if (record.rootEndorsement.size != 0) verifyDevicePairing(record)
            if (record.retirementEndorsement.size != 0) verifyUnsignedDevicePairingDisposition(record)
        }
    }

    private fun validateEmployeeEnrollments(value: NativeEmployeeEnrollmentStateV1, company: LocalCompanyAuthorityBootstrapV2) {
        val original = SelfOwnedCompanyBootstrapView.of(company)
        val rootDigest = rootDocumentDigest()
        val fingerprint = rootFingerprint()
        val reserved = original.allIds().toSet()
        for (record in value.records) {
            val target = record.target; val requested = record.requested()
            known(target.coordinates.scope.organizationId == original.original.organizationId &&
                target.coordinates.scope.companyId == original.original.companyId && target.rootId == original.original.rootId &&
                target.rootKeyId == original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId && target.rootDocumentDigest == rootDigest &&
                target.foundingActionId == original.original.actionId && target.headAuthorityId == original.headAuthorityId &&
                target.headEnrollmentId == original.headEnrollmentId && target.rootFingerprint == fingerprint &&
                record.preparedHead.companyName == original.original.facts.companyName && record.preparedHead.headName == original.headName &&
                record.preparedHead.headLogin == original.headLogin && record.nativeIds().none { it in reserved || it in target.reservedOriginalIds() } &&
                requested.definition.actorId != target.coordinates.firstActorId)
            if (record.rootEndorsement.size != 0) verifyEmployeeEnrollment(record)
        }
    }

    private fun validateEmployeePolicyActivations(value: NativeEmployeePolicyActivationStateV1, company: LocalCompanyAuthorityBootstrapV2) {
        val original = SelfOwnedCompanyBootstrapView.of(company)
        val rootDigest = rootDocumentDigest()
        val fingerprint = rootFingerprint()
        val reserved = original.allIds().toSet()
        for (record in value.records) {
            val target = record.target; val requested = record.requested()
            known(target.coordinates.scope.organizationId == original.original.organizationId &&
                target.coordinates.scope.companyId == original.original.companyId && target.rootId == original.original.rootId &&
                target.rootKeyId == original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId && target.rootDocumentDigest == rootDigest &&
                target.foundingActionId == original.original.actionId && target.headAuthorityId == original.headAuthorityId &&
                target.headEnrollmentId == original.headEnrollmentId && target.rootFingerprint == fingerprint &&
                record.preparedHead.companyName == original.original.facts.companyName && record.preparedHead.headName == original.headName &&
                record.preparedHead.headLogin == original.headLogin && record.nativeIds().none { it in reserved || it in target.reservedOriginalIds() } &&
                requested.request.action == LocalProvisioningAction.ACTIVATE_POLICY)
            if (record.rootEndorsement.size != 0) verifyEmployeePolicyActivation(record)
        }
        val employeeIds = readEmployeeEnrollments()?.records.orEmpty().flatMap { it.nativeIds() }.toSet()
        val pairingIds = devicePairingReservedIdentityIds()
        known(value.records.flatMap { it.nativeIds() }.none { it in employeeIds || it in pairingIds })
    }

    // ---- Head ceremonies -------------------------------------------------------------------------

    override fun authenticateEmployeeEnrollment(originalActionId: DomainId, actionDigest: PayloadDigest, login: String,
        secret: CharArray, actionDeadline: Long): SelfOwnedCompanyHeadApproval = SelfOwnedCompanyHeadApproval.DesktopV2(
        head.authenticate(SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT, originalActionId, actionDigest, login, secret, actionDeadline))

    override fun requireEmployeeEnrollmentStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        originalActionId: DomainId, actionDigest: PayloadDigest) = head.requireStillCurrent(desktop(approval), v2(state),
        SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_ENROLLMENT, originalActionId, actionDigest)

    override fun authenticateEmployeePolicyActivation(originalActionId: DomainId, actionDigest: PayloadDigest, login: String,
        secret: CharArray, actionDeadline: Long): SelfOwnedCompanyHeadApproval = SelfOwnedCompanyHeadApproval.DesktopV2(
        head.authenticate(SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_POLICY_ACTIVATION, originalActionId, actionDigest, login, secret,
            actionDeadline))

    override fun requireEmployeePolicyActivationStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        originalActionId: DomainId, actionDigest: PayloadDigest) = head.requireStillCurrent(desktop(approval), v2(state),
        SelfOwnedDesktopHeadCeremonyV2.EMPLOYEE_POLICY_ACTIVATION, originalActionId, actionDigest)

    override fun authenticateDevicePairing(record: NativeDevicePairingRecordV1, login: String, secret: CharArray): SelfOwnedCompanyHeadApproval {
        issuance.requireIssuance(null)
        environment.requireCurrent()
        if (record.phase != LocalDevicePairingPhase.HEAD_CONSENT_INTENT) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireCompany()
        val consent = record.consent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val possession = record.possession ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val approval = head.authenticateDevicePairing(consent.actionDigest, login, secret, consent.deadlineElapsedMillis) {
            requireDevicePairingConsent(record, SelfOwnedCompanyStateView.of(it))
        }
        return SelfOwnedCompanyHeadApproval.DesktopPairingV2(approval, record.body, possession, consent)
    }

    override fun requireDevicePairingStillCurrent(approval: SelfOwnedCompanyHeadApproval, state: SelfOwnedCompanyStateView,
        record: NativeDevicePairingRecordV1) {
        environment.requireCurrent(); issuance.requireIssuance(null)
        requireDevicePairingConsent(record, state)
        val actual = approval as? SelfOwnedCompanyHeadApproval.DesktopPairingV2 ?: refused()
        val consent = record.consent ?: refused()
        if (actual.body != record.body || actual.possession != record.possession || actual.consent != consent) refused()
        head.requireDevicePairingStillCurrent(actual.value, v2(state), consent.actionDigest, consent.deadlineElapsedMillis)
        record.approvedHead?.let {
            if (it.accountRevision != actual.accountRevision || it.credentialRevision != actual.credentialRevision ||
                it.retainedRevision != actual.retainedRevision || it.osBootCount != actual.boot ||
                it.deadlineElapsedMillis != actual.expires) refused()
        }
        work.requireActive(); issuance.requireIssuance(null)
    }

    /** Independent registry/root/head/proof join (mirror of the Android V1 consent join). */
    private fun requireDevicePairingConsent(record: NativeDevicePairingRecordV1, state: SelfOwnedCompanyStateView) {
        environment.requireCurrent(); work.requireActive(); issuance.requireIssuance(null)
        if (record.phase !in setOf(LocalDevicePairingPhase.HEAD_CONSENT_INTENT,
                LocalDevicePairingPhase.SIGNING_INTENT, LocalDevicePairingPhase.APPROVED))
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val retained = readDevicePairings()?.records?.singleOrNull { it.body.originalActionId == record.body.originalActionId }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (retained != record) uncertain()
        val original = companyOriginal() ?: uncertain()
        val origin = record.body.origin; val prepared = record.body.preparedHead; val consent = record.consent ?: refused()
        val account = state.head
        val rootBytes = state.original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        val action = NativeDevicePairingWireV1.actionMessage(record)
        val digest = try { sha256(action) } finally { action.fill(0) }
        if (origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.rootDocumentDigest != rootDigest || origin.foundingActionId != original.original.actionId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId ||
            origin.rootFingerprint != rootFingerprint() || consent.actionDigest != digest ||
            !account.enabled || account.headAuthorityId != origin.headAuthorityId || account.headEnrollmentId != origin.headEnrollmentId ||
            account.accountRevision != prepared.accountRevision || account.credentialRevision != prepared.credentialRevision ||
            account.name != prepared.headName || account.login != prepared.headLogin ||
            account.accountRevision != consent.accountRevision || account.credentialRevision != consent.credentialRevision ||
            state.original.revision < consent.retainedRevision || environment.bootCount != prepared.osBootCount ||
            environment.bootCount != consent.osBootCount || work.clock() < consent.observation.moment.elapsedMillis ||
            work.clock() >= consent.deadlineElapsedMillis) refused()
        val proofBytes = (record.possession ?: refused()).copyBytes()
        val proof = try { LocalDevicePairingPossession.decode(proofBytes) } finally { proofBytes.fill(0) }
        val challenge = LocalDevicePairingChallenge.fromNative(record.body)
        val message = NativeDevicePairingWireV1.possessionMessage(challenge)
        val point = record.body.requested().target.copyPublicPoint(); val signature = proof.copySignature()
        try { if (!JvmP256Primitives.verify(point, message, signature)) refused() }
        finally { message.fill(0); point.fill(0); signature.fill(0) }
    }

    override fun requireDevicePairingKnownUnsigned(record: NativeDevicePairingRecordV1): SelfOwnedCompanyStateView {
        environment.requireCurrent(); work.requireActive(); issuance.requireIssuance(null)
        if (record.phase !in setOf(LocalDevicePairingPhase.PREPARED, LocalDevicePairingPhase.TARGET_PROVED,
                LocalDevicePairingPhase.HEAD_CONSENT_INTENT, LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT) ||
            record.approvedHead != null || record.rootEndorsement.size != 0 || record.retirementEndorsement.size != 0)
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val retained = readDevicePairings()?.records?.singleOrNull { it.body.originalActionId == record.body.originalActionId }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (retained != record) uncertain()
        val original = companyOriginal() ?: uncertain()
        val state = companyState() ?: uncertain()
        val account = state.head; val origin = record.body.origin
        if (account.reservationId != null || state.original.installers.any { it.reservationId != null }) uncertain()
        val root = state.original.root.copyWireBytes()
        val digest = try { sha256(root) } finally { root.fill(0) }
        if (origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.rootDocumentDigest != digest || origin.foundingActionId != original.original.actionId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId ||
            account.headAuthorityId != origin.headAuthorityId || account.headEnrollmentId != origin.headEnrollmentId ||
            origin.rootFingerprint != rootFingerprint()) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        record.unsignedDisposition?.let {
            if (it.retainedRevision > state.original.revision || it.accountRevision != account.accountRevision ||
                it.credentialRevision != account.credentialRevision || it.osBootCount != environment.bootCount) uncertain()
        }
        return state
    }

    // ---- Original-root endorsements --------------------------------------------------------------

    override fun signEmployeeEnrollmentEndorsement(record: NativeEmployeeEnrollmentRecordV1, approval: SelfOwnedCompanyHeadApproval): ByteArray {
        val original = requireOriginalForKey()
        fun retainedSame() = readEmployeeEnrollments()?.records?.singleOrNull { it.originalActionId == record.originalActionId } == record
        if (!retainedSame() || record.phase != LocalEmployeeEnrollmentPhase.SIGNING_INTENT) keyFailure()
        val approved = record.approvedHead ?: keyFailure()
        if (approved.accountRevision != approval.accountRevision || approved.credentialRevision != approval.credentialRevision ||
            approved.osBootCount != approval.boot || approved.deadlineElapsedMillis != approval.expires ||
            approved.retainedRevision != approval.retainedRevision || record.target.rootId != original.original.rootId ||
            record.target.headAuthorityId != original.headAuthorityId || record.target.headEnrollmentId != original.headEnrollmentId) keyFailure()
        val action = NativeEmployeeEnrollmentWireV1.actionMessage(record)
        val digest = try { sha256(action) } finally { action.fill(0) }
        val current = { requireEmployeeEnrollmentStillCurrent(approval, companyState() ?: keyFailure(), record.originalActionId, digest) }
        current()
        return signRoot(NativeEmployeeEnrollmentWireV1.endorsementMessage(record)) { current(); if (!retainedSame()) keyFailure() }
    }

    override fun verifyEmployeeEnrollment(record: NativeEmployeeEnrollmentRecordV1) {
        if (record.rootEndorsement.size != 64) keyFailure()
        val original = requireOriginalForKey()
        if (record.target.rootId != original.original.rootId || record.target.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            record.target.headAuthorityId != original.headAuthorityId || record.target.headEnrollmentId != original.headEnrollmentId) keyFailure()
        verifyRoot(NativeEmployeeEnrollmentWireV1.endorsementMessage(record), record.rootEndorsement)
    }

    override fun signEmployeePolicyActivationEndorsement(record: NativeEmployeePolicyActivationRecordV1,
        approval: SelfOwnedCompanyHeadApproval): ByteArray {
        val original = requireOriginalForKey()
        fun retainedSame() = readEmployeePolicyActivations()?.records?.singleOrNull { it.originalActionId == record.originalActionId } == record
        if (!retainedSame() || record.phase != LocalEmployeePolicyActivationPhase.SIGNING_INTENT) keyFailure()
        val approved = record.approvedHead ?: keyFailure()
        if (approved.accountRevision != approval.accountRevision || approved.credentialRevision != approval.credentialRevision ||
            approved.osBootCount != approval.boot || approved.deadlineElapsedMillis != approval.expires ||
            approved.retainedRevision != approval.retainedRevision || record.target.rootId != original.original.rootId ||
            record.target.headAuthorityId != original.headAuthorityId || record.target.headEnrollmentId != original.headEnrollmentId) keyFailure()
        val action = NativeEmployeePolicyActivationWireV1.actionMessage(record)
        val digest = try { sha256(action) } finally { action.fill(0) }
        val current = { requireEmployeePolicyActivationStillCurrent(approval, companyState() ?: keyFailure(), record.originalActionId, digest) }
        current()
        return signRoot(NativeEmployeePolicyActivationWireV1.endorsementMessage(record)) { current(); if (!retainedSame()) keyFailure() }
    }

    override fun verifyEmployeePolicyActivation(record: NativeEmployeePolicyActivationRecordV1) {
        if (record.rootEndorsement.size != 64) keyFailure()
        val original = requireOriginalForKey()
        if (record.target.rootId != original.original.rootId || record.target.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            record.target.headAuthorityId != original.headAuthorityId || record.target.headEnrollmentId != original.headEnrollmentId) keyFailure()
        verifyRoot(NativeEmployeePolicyActivationWireV1.endorsementMessage(record), record.rootEndorsement)
    }

    override fun signDevicePairingEndorsement(record: NativeDevicePairingRecordV1, approval: SelfOwnedCompanyHeadApproval,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings): ByteArray {
        val original = requireOriginalForKey()
        fun retainedSame() = readDevicePairings()?.records?.singleOrNull { it.body.originalActionId == record.body.originalActionId } == record
        if (!retainedSame() || record.phase != LocalDevicePairingPhase.SIGNING_INTENT) keyFailure()
        val actual = approval as? SelfOwnedCompanyHeadApproval.DesktopPairingV2 ?: keyFailure()
        val approved = record.approvedHead ?: keyFailure(); val origin = record.body.origin
        if (origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId ||
            actual.body != record.body || actual.possession != record.possession || actual.consent != record.consent ||
            approved.accountRevision != actual.accountRevision || approved.credentialRevision != actual.credentialRevision ||
            approved.retainedRevision != actual.retainedRevision || approved.osBootCount != actual.boot ||
            approved.deadlineElapsedMillis != actual.expires) keyFailure()
        actualPairingsOwner.requireSigningCurrentNeutral(record, approval, this)
        return signRoot(NativeDevicePairingWireV1.endorsementMessage(record)) {
            actualPairingsOwner.requireSigningCurrentNeutral(record, approval, this)
            if (!retainedSame()) keyFailure()
        }
    }

    override fun verifyDevicePairing(record: NativeDevicePairingRecordV1) {
        if (record.phase != LocalDevicePairingPhase.APPROVED || record.rootEndorsement.size != 64) keyFailure()
        requirePairingOrigin(record.body.origin, scope = true)
        verifyRoot(NativeDevicePairingWireV1.endorsementMessage(record), record.rootEndorsement)
    }

    override fun signUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1,
        actualPairingsOwner: AndroidLocalCompanyDevicePairings): ByteArray {
        val original = requireOriginalForKey(); val origin = record.body.origin
        if (record.phase != LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT || record.approvedHead != null ||
            record.rootEndorsement.size != 0 || record.retirementEndorsement.size != 0 ||
            origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId) keyFailure()
        actualPairingsOwner.requireUnsignedRetirementSigningCurrentNeutral(record, this)
        return signRoot(NativeDevicePairingWireV1.retirementMessage(record)) {
            actualPairingsOwner.requireUnsignedRetirementSigningCurrentNeutral(record, this)
        }
    }

    override fun verifyUnsignedDevicePairingDisposition(record: NativeDevicePairingRecordV1) {
        if (record.phase != LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED || record.approvedHead != null ||
            record.rootEndorsement.size != 0 || record.retirementEndorsement.size != 64) keyFailure()
        requirePairingOrigin(record.body.origin, scope = false)
        verifyRoot(NativeDevicePairingWireV1.retirementMessage(record), record.retirementEndorsement)
    }

    private fun requirePairingOrigin(origin: NativeDevicePairingOriginV1, scope: Boolean) {
        val original = requireOriginalForKey()
        val company = companyState() ?: keyFailure()
        val rootBytes = company.original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        if (scope && (origin.coordinates.scope.organizationId != original.original.organizationId ||
                origin.coordinates.scope.companyId != original.original.companyId) ||
            origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.rootDocumentDigest != rootDigest || origin.foundingActionId != original.original.actionId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId ||
            origin.rootFingerprint != rootFingerprint()) keyFailure()
    }

    /** Root signature with the full re-check after it; the result is verified before release. */
    private fun signRoot(message: ByteArray, after: () -> Unit): ByteArray {
        try {
            SelfOwnedNativeCompanyLedgerWireV2.requireRootEndorsementMessage(message)
            val signature = keys.signRootEndorsement(message)
            try {
                if (signature.size != 64) keyFailure()
                val point = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)
                try { if (!JvmP256Primitives.verify(point, message, signature)) keyFailure() } finally { point.fill(0) }
                after()
                return signature
            } catch (failure: Throwable) { signature.fill(0); throw failure }
        } finally { message.fill(0) }
    }

    private fun verifyRoot(message: ByteArray, endorsement: SelfOwnedOctetsV1) {
        val point = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT); val signature = endorsement.copyBytes()
        try { if (!JvmP256Primitives.verify(point, message, signature)) keyFailure() }
        finally { message.fill(0); point.fill(0); signature.fill(0) }
    }

    private fun requireOriginalForKey(): SelfOwnedCompanyBootstrapView {
        work.requireActive(); environment.requireCurrent()
        return companyOriginal() ?: keyFailure()
    }

    private fun desktop(approval: SelfOwnedCompanyHeadApproval): SelfOwnedDesktopCompanyHeadV2.Approval =
        (approval as? SelfOwnedCompanyHeadApproval.DesktopV2)?.value ?: refused()
    private fun v2(state: SelfOwnedCompanyStateView): LocalCompanyAuthorityStateV2 = state.source as? LocalCompanyAuthorityStateV2 ?: refused()

    private fun refused(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    private fun keyFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
    private fun known(value: Boolean) { if (!value) uncertain() }

    override fun toString(): String = "SelfOwnedDesktopCompanyCustodyV2([REDACTED])"
}
