package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** Actual same-original native/root/registry/worker pairing participant. No supplied signer,
 * accepting callback, old head-purpose grant or imported master. Consumption is separately
 * owned by the same native lifetime; an approval or historical review remains DATA. */
internal class AndroidLocalCompanyDevicePairings(
    private val owner: AndroidSelfOwnedInstallationLifetime,
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: LocalProvisionerRegistryStore,
    private val keys: LocalProvisionerNativeKeys,
    private val work: AndroidOwnedInstallationWork,
    private val anchor: AndroidSelfOwnedHistoryAnchor,
    private val issuance: AndroidLocalCompanyIssuanceGate,
) {
    private val actionOwner = Any()
    private val authentication = AndroidLocalInstallerAuthentication(environment, registry, keys, work, issuance)

    fun prepare(requested: LocalDevicePairingDefinition, suppliedOriginal: OriginalNativeHistorySnapshot): LocalDevicePairingAction {
        requireNewCeremony()
        val actual = anchor.requireSameOriginalCut(suppliedOriginal)
        requireOperationalOriginal(actual); requireGenuineCohostCensus(requested, actual)
        val state = ledger(); requireNoUnknownSigning(state)
        val previous = state?.records?.singleOrNull { it.body.requested().request.requestId == requested.request.requestId }
        if (previous != null) {
            requireSameDefinition(previous, requested)
            if (previous.phase != LocalDevicePairingPhase.PREPARED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            requireCurrentOrigin(previous); requirePreparedHeadCurrent(previous)
            return LocalDevicePairingAction(actionOwner, previous)
        }
        requireSdkBranchNamespaceForAuthority(requested, actual)
        if (state?.records?.any { it.phase == LocalDevicePairingPhase.HEAD_CONSENT_INTENT } == true)
            refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (state?.records?.size == LocalDevicePairingProfileV1.MAXIMUM_ACTIONS)
            refuse(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val priorTarget = state?.records?.lastOrNull { it.body.requested().admission.deviceId == requested.admission.deviceId }
        if (priorTarget != null) {
            if (priorTarget.phase != LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED)
                refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            requireSameTarget(priorTarget.body.requested().target, requested.target)
            requireSameSdkOriginalIfPresent(priorTarget.body.requested(), requested)
            keys.verifyUnsignedDevicePairingDisposition(priorTarget)
        }
        val original = requireOriginal(); val company = companyState(); val head = company.head
        if (!head.enabled) refuse(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        val native = actual.record; val definition = checkNotNull(native.definition); val receipt = checkNotNull(native.receipt)
        val rootBytes = company.original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        val origin = NativeDevicePairingOriginV1(native.allocation.coordinates, native.allocation.digest,
            native.allocation.originalNativeBindingDigest, definition.definition.originalRequestId, definition.definitionDigest,
            receipt.digest, definition.definition.authorityEpoch, checkNotNull(native.parent), actual.recordDigest,
            original.original.rootId, original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId, rootDigest,
            original.original.actionId, original.headAuthorityId, original.headEnrollmentId,
            AndroidP256Primitives.rootFingerprint(keys.rootBody()))
        val observation = observe()
        if (observation.moment.elapsedMillis > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS)
            refuse(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val deadline = observation.moment.elapsedMillis + SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS
        val prepared = NativeDevicePairingHeadV1(original.original.facts.companyName, head.name, head.login,
            head.accountRevision, head.credentialRevision, company.original.revision, environment.bootCount, observation, deadline)
        val ids = reservedIds(state, origin)
        val request = requested.request
        val targetReferences = listOf(request.targetId, requested.target.keyId) + requested.sdkIdentity?.let {
            listOf(it.originalAcquisitionId, it.originalPreparationId) }.orEmpty()
        val priorReferences = priorTarget?.targetReferences().orEmpty().toSet()
        if (request.requestId in ids || targetReferences.any { it in ids && it !in priorReferences })
            refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        requested.sdkIdentity?.let { sdk ->
            if (state?.records.orEmpty().any { retained ->
                val before = retained.body.requested().sdkIdentity
                retained.body.requested().admission.deviceId != request.targetId && before != null && before.appId == sdk.appId &&
                    (before.fingerprint == sdk.fingerprint || before.peerId == sdk.peerId)
            }) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        if (priorTarget != null) requireOrigin(priorTarget.body.origin, actual, currentCut = false)
        NativeDevicePairingWireV1.requireAppendCapacity(state, requested, origin, prepared)
        val actionId = AndroidP256Primitives.newId()
        if (actionId in ids || actionId == request.requestId || actionId in targetReferences)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val nonce = AndroidP256Primitives.randomBytes(32); val definitionBytes = requested.copyWireBytes()
        val record = try { NativeDevicePairingRecordV1(NativeDevicePairingBodyV1(actionId, origin,
            SelfOwnedOctetsV1(definitionBytes, LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES), prepared, SelfOwnedOctetsV1(nonce, 32),
            priorTarget?.let { NativeDevicePairingWireV1.retirementLinkDigest(it) }), LocalDevicePairingPhase.PREPARED) } finally { nonce.fill(0); definitionBytes.fill(0) }
        requireNewCeremony()
        requireMasterBoundOriginalIfPresent(requested, anchor.requireSameOriginalCut(actual))
        val retained = retain(state?.next(record) ?: NativeDevicePairingStateV1(0L, listOf(record)), actionId)
        requireCurrentOrigin(retained); requirePreparedHeadCurrent(retained)
        return LocalDevicePairingAction(actionOwner, retained)
    }

    fun proveTarget(action: LocalDevicePairingAction, possession: LocalDevicePairingPossession,
        suppliedOriginal: OriginalNativeHistorySnapshot): LocalDevicePairingAction {
        requireNewCeremony()
        val actual = anchor.requireSameOriginalCut(suppliedOriginal)
        val state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNoUnknownSigning(state)
        val record = requireAction(state, action)
        requireCurrentOrigin(record); requirePreparedHeadCurrent(record)
        requireGenuineCohostCensus(record.body.requested(), actual)
        val bytes = possession.copyWireBytes()
        try {
            if (record.phase == LocalDevicePairingPhase.TARGET_PROVED && record.possession?.same(bytes) == true)
                return LocalDevicePairingAction(actionOwner, record) // Capture-free exact retained proof disclosure.
            if (record.phase != LocalDevicePairingPhase.PREPARED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            verifyPossession(record.body, possession)
            val next = record.copy(phase = LocalDevicePairingPhase.TARGET_PROVED,
                possession = SelfOwnedOctetsV1(bytes, LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES))
            requireNewCeremony()
            requireMasterBoundOriginalIfPresent(record.body.requested(), anchor.requireSameOriginalCut(actual))
            val retained = retain(state.next(next), record.body.originalActionId)
            requireCurrentOrigin(retained); requirePreparedHeadCurrent(retained)
            return LocalDevicePairingAction(actionOwner, retained)
        } finally { bytes.fill(0) }
    }

    /** This explicitly invoked NEW operation consents to one exact purpose/action only.
     * HEAD_CONSENT_INTENT is forced before credential verification. It is NOT an approval.
     * Known credential refusal can retry this SAME intent under the existing real throttle;
     * SIGNING_INTENT/unknown writes can never authenticate/sign a replacement or replay. */
    fun approve(action: LocalDevicePairingAction, login: String, secret: CharArray): LocalDevicePairingApproval {
        requireNewCeremony()
        var state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNoUnknownSigning(state)
        var record = requireAction(state, action)
        if (record.phase !in setOf(LocalDevicePairingPhase.TARGET_PROVED, LocalDevicePairingPhase.HEAD_CONSENT_INTENT))
            refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (state.records.any { it.phase == LocalDevicePairingPhase.HEAD_CONSENT_INTENT &&
                it.body.originalActionId != record.body.originalActionId }) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireCurrentOrigin(record); requirePreparedHeadCurrent(record); verifyStoredPossession(record)
        val actionBytes = NativeDevicePairingWireV1.actionMessage(record)
        val actionDigest = try { sha256(actionBytes) } finally { actionBytes.fill(0) }
        if (record.phase == LocalDevicePairingPhase.TARGET_PROVED) {
            val observation = observe(); requireObservation(record, observation)
            val id = AndroidP256Primitives.newId()
            if (id in reservedIds(state, record.body.origin)) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val head = companyState(); val prepared = record.body.preparedHead
            val deadline = minOf(prepared.deadlineElapsedMillis, work.currentDeadlineMillis())
            if (observation.moment.elapsedMillis >= deadline) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            val consent = NativeDevicePairingConsentV1(id, actionDigest, observation, head.head.accountRevision,
                head.head.credentialRevision, head.original.revision, environment.bootCount, deadline)
            requireMasterBoundOriginalIfPresent(record.body.requested())
            record = retain(state.next(record.copy(phase = LocalDevicePairingPhase.HEAD_CONSENT_INTENT, consent = consent)),
                record.body.originalActionId)
            state = checkNotNull(ledger())
        }
        requireCurrentOrigin(record); requirePreparedHeadCurrent(record)
        val consent = checkNotNull(record.consent)
        if (consent.actionDigest != actionDigest) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val approval = authentication.authenticateDevicePairingAction(record, login, secret)
        authentication.requireDevicePairingStillCurrent(approval, companyState(), record)
        requireCurrentOrigin(record); requireNewCeremony()
        // Reread the exact forced consent, after actual head authentication changed its own
        // genuine account-wide throttle/registry revision, before any root signing intent.
        state = checkNotNull(ledger())
        if (state.records.singleOrNull { it.body.originalActionId == record.body.originalActionId } != record)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireMasterBoundOriginalIfPresent(record.body.requested())
        record = retain(state.next(record.copy(phase = LocalDevicePairingPhase.SIGNING_INTENT,
            approvedHead = NativeDevicePairingApprovedHeadV1(approval.accountRevision, approval.credentialRevision,
                approval.retainedRevision, approval.boot, approval.expires))), record.body.originalActionId)
        state = checkNotNull(ledger())
        requireSigningCurrent(record, approval, keys, registry)
        val signature = keys.signDevicePairingEndorsement(record, approval, this)
        try {
            requireSigningCurrent(record, approval, keys, registry)
            record = retain(state.next(record.copy(phase = LocalDevicePairingPhase.APPROVED,
                rootEndorsement = SelfOwnedOctetsV1(signature, 64))), record.body.originalActionId)
            requireCurrentOrigin(record); requirePreparedHeadCurrent(record); requireNewCeremony()
            authentication.requireDevicePairingStillCurrent(approval, companyState(), record)
            keys.verifyDevicePairing(record)
            return LocalDevicePairingApproval(record, companyState().original.root)
        } finally { signature.fill(0) }
    }

    /** Called independently by the CLOSED key owner, before AND after original-root signing.
     * Exact participant identities, actual native head/origin, credential approval and forced
     * ledger body are all rejoined. This is not a supplied current/success callback. */
    fun requireSigningCurrent(record: NativeDevicePairingRecordV1,
        approval: AndroidLocalInstallerAuthentication.DevicePairingHeadApproval,
        actualKeys: LocalProvisionerNativeKeys, actualRegistry: LocalProvisionerRegistryStore) {
        if (actualKeys !== keys || actualRegistry !== registry || approval.owner !== authentication ||
            record.phase != LocalDevicePairingPhase.SIGNING_INTENT) refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        requireNewCeremony(); requireCurrentOrigin(record); requirePreparedHeadCurrent(record)
        val actual = ledger()?.records?.singleOrNull { it.body.originalActionId == record.body.originalActionId }
        if (actual != record) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        verifyStoredPossession(record)
        authentication.requireDevicePairingStillCurrent(approval, companyState(), record)
        val observed = observe(); requireObservation(record, observed)
    }

    /** Existing-only read/review includes expired and partial original phases as DATA.
     * No password, pairing challenge/ID/endorsement, current approval or SQL fate is
     * manufactured. Existing native key-custody/liveness checks are unchanged. */
    fun review(id: DomainId): LocalDevicePairingAction {
        requireOriginal()
        val record = originalRecord(id); val actual = anchor.snapshotOriginal()
        requireOrigin(record.body.origin, actual, currentCut = false)
        requireMasterBoundOriginalIfPresent(record.body.requested(), actual)
        return LocalDevicePairingAction(actionOwner, record)
    }
    fun recoverApproval(id: DomainId): LocalDevicePairingApproval {
        requireOriginal()
        val record = originalRecord(id)
        if (record.phase != LocalDevicePairingPhase.APPROVED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val actual = anchor.snapshotOriginal()
        requireOrigin(record.body.origin, actual, currentCut = false)
        requireMasterBoundOriginalIfPresent(record.body.requested(), actual)
        verifyStoredPossession(record); keys.verifyDevicePairing(record)
        return LocalDevicePairingApproval(record, companyState().original.root) // ORIGINAL signature/deadlines unchanged; no effect API exists.
    }
    fun retained(): List<LocalDevicePairingReference> { requireOriginal(); return ledger()?.records.orEmpty().map { it.reference() } }

    /** The consumption participant resolves the exact original from the full genuine
     * pairing ledger. Public approval DATA cannot replace this existing source. */
    fun originalForAdmission(id: DomainId): NativeDevicePairingRecordV1 {
        requireOriginal()
        return originalRecord(id).also { requireSdkBoundApprovedAdmission(it) }
    }

    fun requireAdmissionCurrent(record: NativeDevicePairingRecordV1, actual: OriginalNativeHistorySnapshot) {
        requireAdmissionAuthorityCurrent(record)
        requireOrigin(record.body.origin, actual, currentCut = true)
        requireMasterBoundOriginalIfPresent(record.body.requested(), actual)
        owner.requireOriginalDeviceAdmissionCensusNative(record.body.requested(), actual, record.body.originalActionId)
    }

    /** All original registry/key/head IO finishes before native's final synchronous sample.
     * A pending prepared child does not renew the approval or bypass current head state. */
    fun requireAdmissionAuthorityCurrent(record: NativeDevicePairingRecordV1) {
        requireNewCeremony(); requireSdkBoundApprovedAdmission(record)
        if (originalRecord(record.body.originalActionId) != record) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requirePreparedHeadCurrent(record)
        val company = companyState(); val head = company.head; val approved = checkNotNull(record.approvedHead)
        if (!head.enabled || head.accountRevision != approved.accountRevision || head.credentialRevision != approved.credentialRevision ||
            company.original.revision < approved.retainedRevision || environment.bootCount != approved.osBootCount ||
            work.clock() >= approved.deadlineElapsedMillis || head.reservationId != null || head.failedAttempts != 0)
            refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        verifyStoredPossession(record); keys.verifyDevicePairing(record)
    }

    /** Immutable original-origin comparison for selected history DATA. The registry already
     * verified the original root/head and full predecessor ledger; no current grant follows. */
    fun matchesOriginalAdmissionOrigin(record: NativeDevicePairingRecordV1, actual: OriginalNativeHistorySnapshot): Boolean {
        val origin = record.body.origin; val native = actual.record; val definition = native.definition ?: return false
        val master = record.body.requested().masterSdkOriginal
        if (master != null && (native.recordFormat != SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT ||
                native.masterSdkOriginal?.let { sameMasterOriginal(master, it) } != true)) return false
        return origin == origin.copy(coordinates = native.allocation.coordinates) && origin.allocationDigest == native.allocation.digest &&
            origin.bindingDigest == native.allocation.originalNativeBindingDigest &&
            origin.installationRequestId == definition.definition.originalRequestId && origin.installationDefinitionDigest == definition.definitionDigest &&
            origin.installationReceiptDigest == native.receipt?.digest && origin.authorityEpoch == definition.definition.authorityEpoch
    }

    private fun requireSdkBoundApprovedAdmission(record: NativeDevicePairingRecordV1) {
        val requested = record.body.requested()
        if (record.phase != LocalDevicePairingPhase.APPROVED || requested.sdkIdentity == null ||
            requested.purpose != LocalDevicePairingProfileV2.PURPOSE || !requested.admission.admitted ||
            requested.admission.revision.value != 0L) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        // This is genuine consumption eligibility, not original approval/receipt DATA.
        requireSdkBranchNamespaceForAuthority(requested, anchor.snapshotOriginal())
    }

    /** Separately explicit terminal DATA ceremony. Expiry does NOT authorize or perform it.
     * Exact old unsigned content is retained; known-drained actual custody is required, not
     * an expired approval deadline or an unchanged historical business/employee cut. */
    fun retireKnownUnsigned(id: DomainId): LocalDevicePairingUnsignedRetirement {
        requireNewCeremony()
        var state = ledger() ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNoUnknownSigning(state)
        var record = state.records.singleOrNull { it.body.originalActionId == id }
            ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (record.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED) return recoverUnsignedRetirement(id)
        if (state.records.any { it.phase == LocalDevicePairingPhase.HEAD_CONSENT_INTENT && it.body.originalActionId != id })
            refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val actual = anchor.snapshotOriginal(); requireOrigin(record.body.origin, actual, currentCut = false)
        requireMasterBoundOriginalIfPresent(record.body.requested(), actual)
        val company = authentication.requireDevicePairingKnownUnsigned(record)
        NativeDevicePairingWireV1.encodeWithCompletionCapacity(state).fill(0)
        if (state.generation >= Long.MAX_VALUE - 2) refuse(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val dispositionId = AndroidP256Primitives.newId()
        if (dispositionId in reservedIds(state, record.body.origin)) refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val disposition = NativeDevicePairingUnsignedDispositionV1(dispositionId, record.phase,
            NativeDevicePairingWireV1.unsignedRecordDigest(record.body, record.phase, record.possession, record.consent),
            company.original.revision, company.head.accountRevision, company.head.credentialRevision, environment.bootCount, observe())
        requireNewCeremony(); authentication.requireDevicePairingKnownUnsigned(record)
        requireMasterBoundOriginalIfPresent(record.body.requested())
        record = retain(state.next(record.copy(phase = LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT,
            unsignedDisposition = disposition)), id)
        state = checkNotNull(ledger())
        requireUnsignedRetirementSigningCurrent(record, keys, registry)
        val signature = keys.signUnsignedDevicePairingDisposition(record, this)
        try {
            requireUnsignedRetirementSigningCurrent(record, keys, registry)
            val completed = record.copy(phase = LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED,
                retirementEndorsement = SelfOwnedOctetsV1(signature, 64))
            keys.verifyUnsignedDevicePairingDisposition(completed)
            requireUnsignedRetirementSigningCurrent(record, keys, registry)
            record = retain(state.next(completed), id)
            keys.verifyUnsignedDevicePairingDisposition(record)
            requireOriginal(); val finalOriginal = anchor.snapshotOriginal()
            requireOrigin(record.body.origin, finalOriginal, currentCut = false)
            requireMasterBoundOriginalIfPresent(record.body.requested(), finalOriginal)
            return LocalDevicePairingUnsignedRetirement.fromNative(record, companyState().original.root)
        } finally { signature.fill(0) }
    }
    /** Closed actual ROOT participant rejoins known unsigned original and drained custody. */
    fun requireUnsignedRetirementSigningCurrent(record: NativeDevicePairingRecordV1,
        actualKeys: LocalProvisionerNativeKeys, actualRegistry: LocalProvisionerRegistryStore) {
        if (actualKeys !== keys || actualRegistry !== registry ||
            record.phase != LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT)
            refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireNewCeremony(); val actual = anchor.snapshotOriginal()
        requireOrigin(record.body.origin, actual, currentCut = false)
        requireMasterBoundOriginalIfPresent(record.body.requested(), actual)
        authentication.requireDevicePairingKnownUnsigned(record)
        if (ledger()?.records?.singleOrNull { it.body.originalActionId == record.body.originalActionId } != record)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }
    fun recoverUnsignedRetirement(id: DomainId): LocalDevicePairingUnsignedRetirement {
        requireOriginal()
        val record = originalRecord(id)
        if (record.phase != LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED) refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val actual = anchor.snapshotOriginal()
        requireOrigin(record.body.origin, actual, currentCut = false)
        requireMasterBoundOriginalIfPresent(record.body.requested(), actual)
        keys.verifyUnsignedDevicePairingDisposition(record)
        return LocalDevicePairingUnsignedRetirement.fromNative(record, companyState().original.root)
    }
    private fun requireSameTarget(before: LocalDevicePairingTargetDescriptor, after: LocalDevicePairingTargetDescriptor) {
        val a = before.copyWireBytes(); val b = after.copyWireBytes()
        try { if (!a.contentEquals(b)) refuse(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { a.fill(0); b.fill(0) }
    }
    private fun requireSameSdkOriginalIfPresent(before: LocalDevicePairingDefinition, after: LocalDevicePairingDefinition) {
        val old = before.sdkIdentity ?: return // Explicit known-unsigned V1-to-V2 successor remains possible.
        val next = after.sdkIdentity ?: refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val a = old.copyWireBytes(); val b = next.copyWireBytes()
        try { if (!a.contentEquals(b)) refuse(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { a.fill(0); b.fill(0) }
    }

    private fun requireAction(state: NativeDevicePairingStateV1, action: LocalDevicePairingAction): NativeDevicePairingRecordV1 {
        val record = state.records.singleOrNull { it.body.originalActionId == action.originalActionId }
            ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (action.owner !== actionOwner || action.record.body != record.body ||
            action.record.possession != null && action.record.possession != record.possession)
            refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return record
    }
    private fun requireSameDefinition(record: NativeDevicePairingRecordV1, requested: LocalDevicePairingDefinition) {
        val bytes = requested.copyWireBytes()
        try { if (!record.body.definition.same(bytes)) refuse(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { bytes.fill(0) }
    }
    private fun verifyStoredPossession(record: NativeDevicePairingRecordV1) {
        val bytes = checkNotNull(record.possession).copyBytes()
        val proof = try { LocalDevicePairingPossession.decode(bytes) } finally { bytes.fill(0) }
        verifyPossession(record.body, proof)
    }
    private fun verifyPossession(body: NativeDevicePairingBodyV1, proof: LocalDevicePairingPossession) {
        val challenge = LocalDevicePairingChallenge.fromNative(body); val target = body.requested().target
        if (proof.originalActionId != body.originalActionId || proof.challengeDigest != challenge.challengeDigest ||
            proof.deviceId != target.admission.deviceId || proof.keyId != target.keyId || proof.descriptorDigest != target.descriptorDigest ||
            proof.sdkIdentityDigest != body.requested().sdkIdentity?.descriptorDigest)
            refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val message = NativeDevicePairingWireV1.possessionMessage(challenge); val point = target.copyPublicPoint(); val signature = proof.copySignature()
        try { if (!AndroidP256Primitives.verify(point, message, signature)) refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED) }
        finally { message.fill(0); point.fill(0); signature.fill(0) }
        // Deliberately no remote OS/hardware-attestation claim from a supplied point/metadata.
    }
    private fun requireGenuineCohostCensus(requested: LocalDevicePairingDefinition, actual: OriginalNativeHistorySnapshot) {
        owner.requireOriginalDeviceAdmissionCensusNative(requested, actual)
    }
    private fun requireCurrentOrigin(record: NativeDevicePairingRecordV1) {
        val actual = anchor.snapshotOriginal(); requireOrigin(record.body.origin, actual, currentCut = true)
        // Covers prove, consent/authentication, and before/after the actual root signature,
        // including a retained incompatible action that predates namespace enforcement.
        requireSdkBranchNamespaceForAuthority(record.body.requested(), actual)
        requireGenuineCohostCensus(record.body.requested(), actual)
    }
    /** Non-mutating native authority preflight only. Legacy/public historical DATA is not
     * rewritten or reauthorized. Pending native consumption may keep this immutable origin;
     * its current-cut/operational/credential checks remain the separate existing guards. */
    private fun requireSdkBranchNamespaceForAuthority(requested: LocalDevicePairingDefinition,
        actual: OriginalNativeHistorySnapshot) {
        val sdk = requested.sdkIdentity
        if (sdk == null) {
            if (requested.masterSdkOriginal != null) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
            return // Preserve the non-SDK V1 ceremony.
        }
        val native = actual.record
        if (native.recordFormat != SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT)
            refuse(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val master = native.masterSdkOriginal ?: refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        // anchor's genuine authenticated read already compared full canonical allocation
        // bytes with current native keys/binding and both actual application participants.
        val namespace = owner.compositionProfile.appId(native.allocation.coordinates.scope)
        if (master.allocation.digest != native.allocation.digest ||
            master.allocation.originalNativeBindingDigest != native.allocation.originalNativeBindingDigest ||
            !master.nativeApplication.same(environment.target) || !master.nativeApplication.same(owner.materialAtOpen.target) ||
            master.compositionProfile != owner.compositionProfile || master.appId != namespace)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (requested.request.scope != native.allocation.coordinates.scope || sdk.appId != master.appId || sdk.appId != namespace)
            refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        requested.masterSdkOriginal?.let {
            if (!sameMasterOriginal(it, master)) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        }
    }
    /** Existing-only Master DATA/readback and terminal paths do not add authority gates to
     * legacy absent profiles. A Master-bound original must still join the actual native source. */
    private fun requireMasterBoundOriginalIfPresent(requested: LocalDevicePairingDefinition,
        actual: OriginalNativeHistorySnapshot? = null) {
        if (requested.masterSdkOriginal != null)
            requireSdkBranchNamespaceForAuthority(requested, actual ?: anchor.snapshotOriginal())
    }
    private fun sameMasterOriginal(expected: LocalMasterSdkOriginalV1, actual: LocalMasterSdkOriginalV1): Boolean {
        val expectedBytes = expected.copyWireBytes()
        try {
            val actualBytes = actual.copyWireBytes()
            return try { expectedBytes.contentEquals(actualBytes) } finally { actualBytes.fill(0) }
        } finally { expectedBytes.fill(0) }
    }
    private fun requireOrigin(origin: NativeDevicePairingOriginV1, actual: OriginalNativeHistorySnapshot, currentCut: Boolean) {
        requireOperationalOriginal(actual)
        val native = actual.record; val definition = checkNotNull(native.definition)
        if (origin != origin.copy(coordinates = native.allocation.coordinates) || native.allocation.digest != origin.allocationDigest ||
            native.allocation.originalNativeBindingDigest != origin.bindingDigest || definition.definition.originalRequestId != origin.installationRequestId ||
            definition.definitionDigest != origin.installationDefinitionDigest || native.receipt?.digest != origin.installationReceiptDigest ||
            definition.definition.authorityEpoch != origin.authorityEpoch || currentCut &&
            (native.parent != origin.preparationParent || actual.recordDigest != origin.preparationAnchorDigest))
            refuse(SelfOwnedInstallationFailureV1.CONFLICT)
        val original = requireOriginal(); val company = companyState(); val rootBytes = company.original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        if (origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.rootDocumentDigest != rootDigest || origin.foundingActionId != original.original.actionId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId ||
            origin.rootFingerprint != AndroidP256Primitives.rootFingerprint(keys.rootBody())) refuse(SelfOwnedInstallationFailureV1.CONFLICT)
    }
    private fun requirePreparedHeadCurrent(record: NativeDevicePairingRecordV1) {
        val company = companyState(); val head = company.head; val prepared = record.body.preparedHead
        if (!head.enabled || head.accountRevision != prepared.accountRevision || head.credentialRevision != prepared.credentialRevision ||
            head.name != prepared.headName || head.login != prepared.headLogin || company.original.revision < prepared.retainedRevision ||
            environment.bootCount != prepared.osBootCount || work.clock() >= (record.approvedHead?.deadlineElapsedMillis ?: record.consent?.deadlineElapsedMillis ?: prepared.deadlineElapsedMillis))
            refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        requireObservation(record, observe())
    }
    private fun requireObservation(record: NativeDevicePairingRecordV1, observation: LocalAuthenticationObservation) {
        val prepared = record.body.preparedHead
        if (observation.issuerContextId != prepared.observation.issuerContextId || observation.moment.bootId != prepared.observation.moment.bootId ||
            observation.moment.elapsedMillis < prepared.observation.moment.elapsedMillis || observation.moment.elapsedMillis >=
            (record.approvedHead?.deadlineElapsedMillis ?: record.consent?.deadlineElapsedMillis ?: prepared.deadlineElapsedMillis) ||
            environment.bootCount != prepared.osBootCount) refuse(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
    }
    private fun requireOperationalOriginal(actual: OriginalNativeHistorySnapshot) {
        val native = actual.record
        if (native.database == null || native.parent == null || native.pending != null || native.issuerAcknowledgement == null ||
            native.attempt?.phase != NativeHistoryAttemptPhaseV2.COMPLETED || native.roleIntent?.role != NativeHistoryDesiredRoleV2.RUN)
            refuse(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    }
    private fun reservedIds(state: NativeDevicePairingStateV1?, origin: NativeDevicePairingOriginV1): Set<DomainId> =
        requireOriginal().allIds().toSet() + origin.reservedOriginalIds() + state?.records.orEmpty().flatMap { it.nativeIds() } +
            registry.deviceAdmissionReservedIdentityIds(keys) +
            registry.readEmployeeEnrollments(keys)?.records.orEmpty().flatMap { it.nativeIds() } +
            registry.readBusinessPublications(keys)?.records.orEmpty().flatMap { listOf(it.originalActionId, it.keyId, it.publicationId, it.entityId) }
    private fun requireNoUnknownSigning(state: NativeDevicePairingStateV1?) {
        if (state?.records?.any { it.phase in setOf(LocalDevicePairingPhase.SIGNING_INTENT,
                LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT) } == true)
            refuse(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }
    private fun requireOriginal(): LocalCompanyAuthorityBootstrapV1 {
        work.requireActive(); environment.requireExistingOriginalObservation(); registry.assertContinuity()
        if (owner.dataUseDeniedNoIo()) refuse(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        return registry.companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
    }
    private fun requireNewCeremony() {
        requireOriginal(); issuance.requireIssuance(null)
        if (!owner.newCeremoniesAllowedNoIo()) refuse(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
    }
    private fun companyState(): LocalCompanyAuthorityStateV1 = registry.readCompanyState(keys)
        ?: refuse(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    private fun ledger(): NativeDevicePairingStateV1? = registry.readDevicePairings(keys)
    private fun originalRecord(id: DomainId): NativeDevicePairingRecordV1 = ledger()?.records?.singleOrNull { it.body.originalActionId == id }
        ?: refuse(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    private fun retain(state: NativeDevicePairingStateV1, id: DomainId): NativeDevicePairingRecordV1 =
        registry.retainDevicePairings(state, keys).records.single { it.body.originalActionId == id }
    private fun observe(): LocalAuthenticationObservation = owner.historyClockSampleNative().let { LocalAuthenticationObservation(it.issuerContextId, it.moment) }
    private fun refuse(reason: SelfOwnedInstallationFailureV1): Nothing = selfOwnedFail(reason)
}
