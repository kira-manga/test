package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Complete original native origin/cut, independently rejoined by the actual owner. */
internal data class NativeDevicePairingOriginV1(
    val coordinates: SelfOwnedAllocationCoordinatesV1,
    val allocationDigest: PayloadDigest, val bindingDigest: PayloadDigest,
    val installationRequestId: DomainId, val installationDefinitionDigest: PayloadDigest,
    val installationReceiptDigest: PayloadDigest, val authorityEpoch: Revision,
    val preparationParent: NativeHistoryRecordedHeadV2, val preparationAnchorDigest: PayloadDigest,
    val rootId: DomainId, val rootKeyId: DomainId, val rootDocumentDigest: PayloadDigest,
    val foundingActionId: DomainId, val headAuthorityId: DomainId, val headEnrollmentId: DomainId,
    val rootFingerprint: String,
) {
    init {
        selfOwnedHexDigest(rootFingerprint)
        require(preparationParent.historySequence >= 1 && preparationParent.commandEpoch == authorityEpoch.value)
    }
    fun reservedOriginalIds(): Set<DomainId> = coordinates.let { c -> setOf(c.allocationId, c.scope.organizationId,
        c.scope.companyId, c.scope.branchId, c.installationId, c.assignmentId, c.deviceId, c.storageInstanceId,
        c.storageKeyId, c.pepperKeyId, c.targetSigningKeyId, c.firstActorId, installationRequestId,
        rootId, rootKeyId, foundingActionId, headAuthorityId, headEnrollmentId) }
    override fun equals(other: Any?): Boolean {
        if (other !is NativeDevicePairingOriginV1) return false
        val left = NativeDevicePairingWireV1.originBytes(this); val right = NativeDevicePairingWireV1.originBytes(other)
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }
    override fun hashCode(): Int {
        val bytes = NativeDevicePairingWireV1.originBytes(this)
        return try { bytes.contentHashCode() } finally { bytes.fill(0) }
    }
}

internal data class NativeDevicePairingHeadV1(val companyName: String, val headName: String, val headLogin: String,
    val accountRevision: Long, val credentialRevision: Long, val retainedRevision: Long,
    val osBootCount: Int, val observation: LocalAuthenticationObservation, val deadlineElapsedMillis: Long) {
    init {
        selfOwnedLabel(companyName); localCompanyHeadName(headName); selfOwnedLogin(headLogin)
        require(accountRevision >= 0 && credentialRevision >= 0 && retainedRevision >= 0 && osBootCount >= 0 &&
            observation.moment.elapsedMillis < deadlineElapsedMillis)
    }
}

internal data class NativeDevicePairingBodyV1(val originalActionId: DomainId,
    val origin: NativeDevicePairingOriginV1, val definition: SelfOwnedOctetsV1,
    val preparedHead: NativeDevicePairingHeadV1, val nonce: SelfOwnedOctetsV1,
    val previousUnsignedRetirementDigest: PayloadDigest? = null) {
    init {
        require(definition.size in 1..LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES && nonce.size == 32)
        val requested = requested(); val ids = listOf(originalActionId, requested.request.requestId,
            requested.admission.deviceId, requested.target.keyId) + requested.sdkIdentity?.let {
                listOf(it.originalAcquisitionId, it.originalPreparationId)
            }.orEmpty()
        require(ids.distinct().size == ids.size && ids.none { it in origin.reservedOriginalIds() } &&
            requested.request.scope == origin.coordinates.scope && requested.admission.deviceId != origin.coordinates.deviceId)
        requested.masterSdkOriginal?.let { master ->
            // Coordinates are separately decoded value DATA, not object-identity capabilities.
            require(master.originalAcquisitionId !in ids && master.originalAcquisitionId !in origin.reservedOriginalIds() &&
                origin == origin.copy(coordinates = master.allocation.coordinates) &&
                master.allocation.digest == origin.allocationDigest &&
                master.allocation.originalNativeBindingDigest == origin.bindingDigest &&
                master.allocation.p1RootDocumentDigest == origin.rootDocumentDigest &&
                master.appId == master.compositionProfile.appId(origin.coordinates.scope))
        }
    }
    fun requested(): LocalDevicePairingDefinition {
        val bytes = definition.copyBytes()
        return try { LocalDevicePairingDefinition.decode(bytes) } finally { bytes.fill(0) }
    }
    val sdkBound: Boolean get() = requested().sdkIdentity != null
    val purpose: String get() = requested().purpose
}

internal data class NativeDevicePairingPossessionV1(val originalActionId: DomainId, val challengeDigest: PayloadDigest,
    val deviceId: DomainId, val keyId: DomainId, val descriptorDigest: PayloadDigest, val signature: SelfOwnedOctetsV1,
    val sdkIdentityDigest: PayloadDigest? = null) {
    init { require(listOf(originalActionId, deviceId, keyId).distinct().size == 3 && signature.size == 64) }
}

/** Durable explicit one-action consent INTENT. It is not proof the head credential matched. */
internal data class NativeDevicePairingConsentV1(val consentId: DomainId, val actionDigest: PayloadDigest,
    val observation: LocalAuthenticationObservation, val accountRevision: Long, val credentialRevision: Long,
    val retainedRevision: Long, val osBootCount: Int, val deadlineElapsedMillis: Long) {
    init {
        require(accountRevision >= 0 && credentialRevision >= 0 && retainedRevision >= 0 && osBootCount >= 0 &&
            observation.moment.elapsedMillis < deadlineElapsedMillis)
    }
}

internal data class NativeDevicePairingApprovedHeadV1(val accountRevision: Long, val credentialRevision: Long,
    val retainedRevision: Long, val osBootCount: Int, val deadlineElapsedMillis: Long) {
    init { require(accountRevision >= 0 && credentialRevision >= 0 && retainedRevision >= 0 && osBootCount >= 0 && deadlineElapsedMillis >= 0) }
}

/** A separately explicit known-no-pairing-signature disposition, not cancellation authority.
 * It preserves the exact original unsigned phase/body/proof/consent and all old deadlines. */
internal data class NativeDevicePairingUnsignedDispositionV1(val dispositionId: DomainId,
    val originalPhase: LocalDevicePairingPhase, val originalUnsignedDigest: PayloadDigest,
    val retainedRevision: Long, val accountRevision: Long, val credentialRevision: Long,
    val osBootCount: Int, val observation: LocalAuthenticationObservation) {
    init {
        require(originalPhase in setOf(LocalDevicePairingPhase.PREPARED, LocalDevicePairingPhase.TARGET_PROVED,
            LocalDevicePairingPhase.HEAD_CONSENT_INTENT) && retainedRevision >= 0 && accountRevision >= 0 &&
            credentialRevision >= 0 && osBootCount >= 0)
    }
}
internal data class NativeDevicePairingRetirementCertificateV1(val record: NativeDevicePairingRecordV1,
    val rootDocument: SelfOwnedOctetsV1)

internal data class NativeDevicePairingRecordV1(val body: NativeDevicePairingBodyV1,
    val phase: LocalDevicePairingPhase, val possession: SelfOwnedOctetsV1? = null,
    val consent: NativeDevicePairingConsentV1? = null, val approvedHead: NativeDevicePairingApprovedHeadV1? = null,
    val rootEndorsement: SelfOwnedOctetsV1 = SelfOwnedOctetsV1(ByteArray(0)),
    val unsignedDisposition: NativeDevicePairingUnsignedDispositionV1? = null,
    val retirementEndorsement: SelfOwnedOctetsV1 = SelfOwnedOctetsV1(ByteArray(0))) {
    init {
        val challenge = LocalDevicePairingChallenge.fromNative(body)
        possession?.let {
            require(it.size in 1..LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES)
            val bytes = it.copyBytes()
            val proof = try { LocalDevicePairingPossession.decode(bytes) } finally { bytes.fill(0) }
            val target = body.requested().target
            require(proof.originalActionId == body.originalActionId && proof.challengeDigest == challenge.challengeDigest &&
                proof.deviceId == target.admission.deviceId && proof.keyId == target.keyId && proof.descriptorDigest == target.descriptorDigest &&
                proof.sdkIdentityDigest == body.requested().sdkIdentity?.descriptorDigest)
        }
        consent?.let {
            require(it.consentId !in nativeIdsWithoutConsent() && it.consentId !in body.origin.reservedOriginalIds() &&
                it.accountRevision == body.preparedHead.accountRevision && it.credentialRevision == body.preparedHead.credentialRevision &&
                it.retainedRevision >= body.preparedHead.retainedRevision && it.osBootCount == body.preparedHead.osBootCount &&
                it.observation.issuerContextId == body.preparedHead.observation.issuerContextId &&
                it.observation.moment.bootId == body.preparedHead.observation.moment.bootId &&
                it.observation.moment.elapsedMillis >= body.preparedHead.observation.moment.elapsedMillis &&
                it.deadlineElapsedMillis <= body.preparedHead.deadlineElapsedMillis)
            val action = NativeDevicePairingWireV1.actionMessage(this)
            try { require(sha256(action) == it.actionDigest) } finally { action.fill(0) }
        }
        approvedHead?.let {
            val c = requireNotNull(consent)
            require(it.accountRevision == c.accountRevision && it.credentialRevision == c.credentialRevision &&
                it.retainedRevision >= c.retainedRevision && it.osBootCount == c.osBootCount &&
                it.deadlineElapsedMillis <= c.deadlineElapsedMillis && it.deadlineElapsedMillis > c.observation.moment.elapsedMillis)
        }
        unsignedDisposition?.let {
            require(it.dispositionId !in nativeIdsWithoutConsent() && it.dispositionId != consent?.consentId &&
                it.dispositionId !in body.origin.reservedOriginalIds() && approvedHead == null && rootEndorsement.size == 0 &&
                it.originalUnsignedDigest == NativeDevicePairingWireV1.unsignedRecordDigest(body, it.originalPhase, possession, consent))
            when (it.originalPhase) {
                LocalDevicePairingPhase.PREPARED -> require(possession == null && consent == null)
                LocalDevicePairingPhase.TARGET_PROVED -> require(possession != null && consent == null)
                LocalDevicePairingPhase.HEAD_CONSENT_INTENT -> require(possession != null && consent != null)
                else -> error("Not a known unsigned original")
            }
        }
        if (phase !in setOf(LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT, LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED))
            require(unsignedDisposition == null && retirementEndorsement.size == 0)
        when (phase) {
            LocalDevicePairingPhase.PREPARED -> require(possession == null && consent == null && approvedHead == null && rootEndorsement.size == 0)
            LocalDevicePairingPhase.TARGET_PROVED -> require(possession != null && consent == null && approvedHead == null && rootEndorsement.size == 0)
            LocalDevicePairingPhase.HEAD_CONSENT_INTENT -> require(possession != null && consent != null && approvedHead == null && rootEndorsement.size == 0)
            LocalDevicePairingPhase.SIGNING_INTENT -> require(possession != null && consent != null && approvedHead != null && rootEndorsement.size == 0)
            LocalDevicePairingPhase.APPROVED -> require(possession != null && consent != null && approvedHead != null && rootEndorsement.size == 64)
            LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT -> require(unsignedDisposition != null && approvedHead == null &&
                rootEndorsement.size == 0 && retirementEndorsement.size == 0)
            LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED -> require(unsignedDisposition != null && approvedHead == null &&
                rootEndorsement.size == 0 && retirementEndorsement.size == 64)
        }
    }
    private fun nativeIdsWithoutConsent(): List<DomainId> = body.requested().let {
        listOf(body.originalActionId, it.request.requestId, it.admission.deviceId, it.target.keyId) +
            it.sdkIdentity?.let { sdk -> listOf(sdk.originalAcquisitionId, sdk.originalPreparationId) }.orEmpty() +
            listOfNotNull(it.masterSdkOriginal?.originalAcquisitionId)
    }
    fun ownedIds(): List<DomainId> = listOf(body.originalActionId, body.requested().request.requestId) +
        listOfNotNull(consent?.consentId, unsignedDisposition?.dispositionId)
    fun targetReferences(): List<DomainId> = body.requested().let {
        listOf(it.admission.deviceId, it.target.keyId) +
            it.sdkIdentity?.let { sdk -> listOf(sdk.originalAcquisitionId, sdk.originalPreparationId) }.orEmpty()
    }
    fun nativeIds(): List<DomainId> = ownedIds() + targetReferences()
    fun reference(): LocalDevicePairingReference = body.requested().let {
        LocalDevicePairingReference(body.originalActionId, it.request.requestId, it.admission.deviceId, it.target.keyId, phase)
    }
    override fun toString(): String = "NativeDevicePairingRecordV1([REDACTED])"
}

internal data class NativeDevicePairingStateV1(val generation: Long, val records: List<NativeDevicePairingRecordV1>) {
    init {
        require(generation in 0 until Long.MAX_VALUE && records.size in 1..LocalDevicePairingProfileV1.MAXIMUM_ACTIONS)
        val owned = records.flatMap { it.ownedIds() }; val references = records.flatMap { it.targetReferences() }.toSet()
        require(owned.distinct().size == owned.size && owned.none { it in references })
        val prior = mutableMapOf<DomainId, NativeDevicePairingRecordV1>(); val keyOwners = mutableMapOf<DomainId, DomainId>()
        val nativeTargetIds = records.flatMap { it.body.requested().let { requested ->
            listOf(requested.admission.deviceId, requested.target.keyId) } }.toSet()
        val masterAcquisitionIds = records.mapNotNull { it.body.requested().masterSdkOriginal?.originalAcquisitionId }.toSet()
        val originalMasterIds = records.flatMap { it.body.origin.reservedOriginalIds() }.toSet() + masterAcquisitionIds
        require(owned.none { it in masterAcquisitionIds } && nativeTargetIds.none { it in masterAcquisitionIds })
        val sdkReferenceOwners = mutableMapOf<DomainId, DomainId>()
        val sdkFingerprintOwners = mutableMapOf<Pair<String, String>, DomainId>()
        val sdkPeerOwners = mutableMapOf<Pair<String, String>, DomainId>()
        for (record in records) {
            val requested = record.body.requested(); val target = requested.target; val device = target.admission.deviceId
            require(keyOwners[target.keyId] == null || keyOwners[target.keyId] == device); keyOwners[target.keyId] = device
            requested.sdkIdentity?.let { sdk ->
                for (reference in listOf(sdk.originalAcquisitionId, sdk.originalPreparationId)) {
                    require(reference !in nativeTargetIds && reference !in originalMasterIds &&
                        (sdkReferenceOwners[reference] == null || sdkReferenceOwners[reference] == device))
                    sdkReferenceOwners[reference] = device
                }
                val fingerprint = sdk.appId.value to sdk.fingerprint.value
                val peer = sdk.appId.value to sdk.peerId.value
                require((sdkFingerprintOwners[fingerprint] == null || sdkFingerprintOwners[fingerprint] == device) &&
                    (sdkPeerOwners[peer] == null || sdkPeerOwners[peer] == device))
                sdkFingerprintOwners[fingerprint] = device; sdkPeerOwners[peer] = device
            }
            val before = prior[device]
            if (before == null) require(record.body.previousUnsignedRetirementDigest == null)
            else {
                require(before.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED &&
                    record.body.previousUnsignedRetirementDigest == NativeDevicePairingWireV1.retirementLinkDigest(before))
                val a = before.body.requested().target.copyWireBytes(); val b = target.copyWireBytes()
                try { require(a.contentEquals(b)) } finally { a.fill(0); b.fill(0) }
                before.body.requested().sdkIdentity?.let { sdk ->
                    val previousSdk = sdk.copyWireBytes(); val nextSdk = requireNotNull(record.body.requested().sdkIdentity).copyWireBytes()
                    try { require(previousSdk.contentEquals(nextSdk)) } finally { previousSdk.fill(0); nextSdk.fill(0) }
                }
            }
            prior[device] = record
        }
        require(keyOwners.keys.none { it in prior.keys })
        require(records.count { it.phase in setOf(LocalDevicePairingPhase.HEAD_CONSENT_INTENT,
            LocalDevicePairingPhase.SIGNING_INTENT, LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT) } <= 1)
    }
    fun next(record: NativeDevicePairingRecordV1): NativeDevicePairingStateV1 {
        if (generation >= Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val old = records.singleOrNull { it.body.originalActionId == record.body.originalActionId }
        return copy(generation = generation + 1, records = if (old == null) records + record else
            records.map { if (it === old) record else it })
    }
    override fun toString(): String = "NativeDevicePairingStateV1([REDACTED])"
}

/** NEW additive ledger/AAD/challenge/consent/signature domains. No old wire/tag/profile changes. */
internal object NativeDevicePairingWireV1 {
    fun encodeTarget(value: DevicePairingTargetFieldsV1): ByteArray = selfOwnedEncode("new-device-pairing-target-data/v1",
        ProtectedLocalTable.ADMISSION.maximumPlaintextBytes + 1024) {
        string(LocalDevicePairingProfileV1.PURPOSE); admission(value.admission); id(value.keyId); octets(value.point)
    }
    fun decodeTarget(bytes: ByteArray): DevicePairingTargetFieldsV1 = selfOwnedDecode(bytes, "new-device-pairing-target-data/v1") {
        purpose(); DevicePairingTargetFieldsV1(admission(), id(), octets(65))
    }
    fun encodeDefinition(value: DevicePairingDefinitionFieldsV1): ByteArray = if (value.masterSdkOriginal != null)
        NativeDevicePairingWireV3.encodeDefinition(value) else if (value.sdkIdentity != null)
        NativeDevicePairingWireV2.encodeDefinition(value) else selfOwnedEncode("full-one-new-device-pairing-definition/v1",
        LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES) {
        string(LocalDevicePairingProfileV1.PURPOSE); request(value.request)
        val target = value.target.copyWireBytes(); try { field(target) } finally { target.fill(0) }
        octets(value.serviceAdmission); int(value.admissions.size); value.admissions.forEach { admission(it) }
    }
    fun decodeDefinition(bytes: ByteArray): DevicePairingDefinitionFieldsV1 = if (NativeDevicePairingWireV3.isDefinition(bytes))
        NativeDevicePairingWireV3.decodeDefinition(bytes) else if (
        NativeDevicePairingWireV2.isV2Domain(bytes, "full-one-new-device-pairing-definition/v1"))
        NativeDevicePairingWireV2.decodeDefinition(bytes) else selfOwnedDecode(bytes, "full-one-new-device-pairing-definition/v1") {
        purpose(); val request = request(); val targetBytes = field(ProtectedLocalTable.ADMISSION.maximumPlaintextBytes + 1024)
        val target = try { LocalDevicePairingTargetDescriptor.decode(targetBytes) } finally { targetBytes.fill(0) }
        val service = octets(ProtectedLocalTable.ADMISSION.maximumPlaintextBytes)
        val count = int(); require(count in 1..256)
        DevicePairingDefinitionFieldsV1(request, target, service, List(count) { admission() })
    }
    fun originBytes(value: NativeDevicePairingOriginV1): ByteArray = selfOwnedEncode("original-native-device-pairing-origin/v1") { origin(value) }
    fun aad(original: LocalCompanyAuthorityBootstrapV1): ByteArray = selfOwnedEncode("company-device-pairing-ledger-aad/v1") {
        string(LocalDevicePairingProfileV1.PURPOSE)
        val bytes = LocalCompanyAuthorityWireV1.registryAad(original)
        try { field(bytes) } finally { bytes.fill(0) }
    }
    fun encodeChallenge(value: NativeDevicePairingBodyV1): ByteArray = selfOwnedEncode(profileDomain("original-full-device-pairing-challenge/v1", value.sdkBound),
        LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES) { string(value.purpose); body(value) }
    fun decodeChallenge(bytes: ByteArray): NativeDevicePairingBodyV1 {
        val sdkBound = NativeDevicePairingWireV2.isV2Domain(bytes, "original-full-device-pairing-challenge/v1")
        return selfOwnedDecode(bytes, profileDomain("original-full-device-pairing-challenge/v1", sdkBound)) {
            require(string(128) == pairingPurpose(sdkBound)); body().also { require(it.sdkBound == sdkBound) }
        }
    }
    fun possessionMessage(challenge: LocalDevicePairingChallenge): ByteArray = selfOwnedEncode(profileDomain("target-private-key-device-pairing-possession/v1", challenge.body.sdkBound),
        LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + 1024) {
        string(challenge.purpose)
        string("POSSESSION_ONLY_NOT_REMOTE_ATTESTATION_OR_MASTER_PEER_OR_EMPLOYEE_AUTHORITY")
        val bytes = challenge.copyWireBytes(); try { field(bytes) } finally { bytes.fill(0) }
    }
    fun encodePossession(value: NativeDevicePairingPossessionV1): ByteArray = if (value.sdkIdentityDigest != null)
        NativeDevicePairingWireV2.encodePossession(value) else selfOwnedEncode("target-device-pairing-possession-data/v1",
        LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES) {
        string(LocalDevicePairingProfileV1.PURPOSE); id(value.originalActionId); digest(value.challengeDigest)
        id(value.deviceId); id(value.keyId); digest(value.descriptorDigest); octets(value.signature)
    }
    fun decodePossession(bytes: ByteArray): NativeDevicePairingPossessionV1 = if (
        NativeDevicePairingWireV2.isV2Domain(bytes, "target-device-pairing-possession-data/v1"))
        NativeDevicePairingWireV2.decodePossession(bytes) else selfOwnedDecode(bytes, "target-device-pairing-possession-data/v1") {
        purpose(); NativeDevicePairingPossessionV1(id(), digest(), id(), id(), digest(), octets(64))
    }
    fun actionMessage(record: NativeDevicePairingRecordV1): ByteArray = selfOwnedEncode(profileDomain("named-head-explicit-one-device-pairing-consent-action/v1", record.body.sdkBound),
        LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES + 2048) {
        string(record.body.purpose); body(record.body); octets(checkNotNull(record.possession))
        string("FRESH_NAMED_HEAD_CONSENT_TO_THIS_ONE_PAIRING_ACTION_NOT_OLD_PURPOSE_OR_GENERAL_GRANT")
    }
    fun endorsementMessage(record: NativeDevicePairingRecordV1): ByteArray = selfOwnedEncode(profileDomain("original-root-head-approved-one-device-pairing/v1", record.body.sdkBound),
        LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + LocalDevicePairingProfileV1.COMPLETION_RESERVATION_BYTES) {
        string(record.body.purpose); body(record.body); octets(checkNotNull(record.possession))
        consent(checkNotNull(record.consent)); approval(checkNotNull(record.approvedHead))
        string("PAIRING_PREPARATION_ONLY_NO_ADMISSION_RPC_DEVICE_ADMIN_DELEGATION_SESSION_OR_STARTUP")
    }
    fun certificate(record: NativeDevicePairingRecordV1): ByteArray = selfOwnedEncode(profileDomain("one-device-pairing-approval-certificate-data/v1", record.body.sdkBound),
        LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + LocalDevicePairingProfileV1.COMPLETION_RESERVATION_BYTES + 1024) {
        require(record.phase == LocalDevicePairingPhase.APPROVED)
        val message = endorsementMessage(record); try { field(message) } finally { message.fill(0) }
        octets(record.rootEndorsement)
    }
    /** Inverse of the existing canonical certificate. Parsing is DATA, never signature verification. */
    fun decodeApprovedCertificate(bytes: ByteArray): NativeDevicePairingRecordV1 {
        require(bytes.size in 1..LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES +
            LocalDevicePairingProfileV1.COMPLETION_RESERVATION_BYTES + 1024)
        val sdkBound = NativeDevicePairingWireV2.isV2Domain(bytes, "one-device-pairing-approval-certificate-data/v1")
        val result = selfOwnedDecode(bytes, profileDomain("one-device-pairing-approval-certificate-data/v1", sdkBound)) {
            val message = field(LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES +
                LocalDevicePairingProfileV1.COMPLETION_RESERVATION_BYTES)
            val signature = octets(64)
            try { selfOwnedDecode(message, profileDomain("original-root-head-approved-one-device-pairing/v1", sdkBound)) {
                require(string(128) == pairingPurpose(sdkBound))
                val original = body(); require(original.sdkBound == sdkBound)
                val possession = octets(LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES)
                val consent = consent(); val approved = approval()
                require(string(128) == "PAIRING_PREPARATION_ONLY_NO_ADMISSION_RPC_DEVICE_ADMIN_DELEGATION_SESSION_OR_STARTUP")
                NativeDevicePairingRecordV1(original, LocalDevicePairingPhase.APPROVED,
                    possession, consent, approved, signature)
            } } finally { message.fill(0) }
        }
        val canonical = certificate(result)
        try { require(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        return result
    }
    fun unsignedRecordDigest(originalBody: NativeDevicePairingBodyV1, phase: LocalDevicePairingPhase,
        retainedPossession: SelfOwnedOctetsV1?, retainedConsent: NativeDevicePairingConsentV1?): PayloadDigest {
        val bytes = selfOwnedEncode(profileDomain("exact-original-unsigned-device-pairing-state/v1", originalBody.sdkBound),
            LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + 8192) {
            string(originalBody.purpose); body(originalBody); string(phase.name)
            bool(retainedPossession != null); retainedPossession?.let { octets(it) }
            bool(retainedConsent != null); retainedConsent?.let { consent(it) }
        }
        return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    fun retirementMessage(record: NativeDevicePairingRecordV1): ByteArray = selfOwnedEncode(
        profileDomain("original-root-explicit-known-unsigned-device-pairing-disposition/v1", record.body.sdkBound), LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + 4096) {
        string(retirementPurpose(record.body.sdkBound)); body(record.body)
        bool(record.possession != null); record.possession?.let { octets(it) }
        bool(record.consent != null); record.consent?.let { consent(it) }
        disposition(requireNotNull(record.unsignedDisposition))
        string("KNOWN_NO_PAIRING_SIGNATURE_TERMINAL_DATA_NOT_HEAD_APPROVAL_REVOCATION_ADMISSION_TRUST_OR_PERMISSION")
    }
    private fun signedRetirementData(record: NativeDevicePairingRecordV1): ByteArray = selfOwnedEncode(
        profileDomain("signed-original-known-unsigned-device-pairing-data/v1", record.body.sdkBound), LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + 6144) {
        require(record.phase == LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED)
        val message = retirementMessage(record); try { field(message) } finally { message.fill(0) }
        octets(record.retirementEndorsement)
    }
    fun retirementLinkDigest(record: NativeDevicePairingRecordV1): PayloadDigest {
        val bytes = signedRetirementData(record); return try { sha256(bytes) } finally { bytes.fill(0) }
    }
    fun retirementCertificate(record: NativeDevicePairingRecordV1, root: SelfOwnedOctetsV1): ByteArray = selfOwnedEncode(
        profileDomain("original-known-unsigned-device-pairing-terminal-certificate-data/v1", record.body.sdkBound), LocalDevicePairingProfileV1.MAXIMUM_RETIREMENT_BYTES) {
        string(retirementPurpose(record.body.sdkBound))
        val bytes = signedRetirementData(record); try { field(bytes) } finally { bytes.fill(0) }; octets(root)
    }
    fun decodeRetirementCertificate(bytes: ByteArray): NativeDevicePairingRetirementCertificateV1 {
        val sdkBound = NativeDevicePairingWireV2.isV2Domain(bytes, "original-known-unsigned-device-pairing-terminal-certificate-data/v1")
        return selfOwnedDecode(bytes, profileDomain("original-known-unsigned-device-pairing-terminal-certificate-data/v1", sdkBound)) {
        require(string(128) == retirementPurpose(sdkBound))
        val signed = field(LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + 6144)
        val record = try { selfOwnedDecode(signed, profileDomain("signed-original-known-unsigned-device-pairing-data/v1", sdkBound)) {
            val message = field(LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES + 4096)
            val signature = octets(64)
            try { selfOwnedDecode(message, profileDomain("original-root-explicit-known-unsigned-device-pairing-disposition/v1", sdkBound)) {
                require(string(128) == retirementPurpose(sdkBound))
                val original = body(); require(original.sdkBound == sdkBound)
                val proof = if (bool()) octets(LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES) else null
                val consent = if (bool()) consent() else null; val disposition = disposition()
                require(string(128) == "KNOWN_NO_PAIRING_SIGNATURE_TERMINAL_DATA_NOT_HEAD_APPROVAL_REVOCATION_ADMISSION_TRUST_OR_PERMISSION")
                NativeDevicePairingRecordV1(original, LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED, proof, consent,
                    unsignedDisposition = disposition, retirementEndorsement = signature)
            } } finally { message.fill(0) }
        } } finally { signed.fill(0) }
        NativeDevicePairingRetirementCertificateV1(record, octets(SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES))
        }
    }
    fun encode(value: NativeDevicePairingStateV1): ByteArray = selfOwnedEncode("native-company-one-device-pairing-ledger/v1",
        LocalDevicePairingProfileV1.MAXIMUM_PLAINTEXT_BYTES) {
        string(LocalDevicePairingProfileV1.PURPOSE); long(value.generation); int(value.records.size)
        value.records.forEach { record(it) }
    }
    fun decode(bytes: ByteArray): NativeDevicePairingStateV1 = selfOwnedDecode(bytes, "native-company-one-device-pairing-ledger/v1") {
        purpose(); val generation = long(); val count = int(); require(count in 1..LocalDevicePairingProfileV1.MAXIMUM_ACTIONS)
        NativeDevicePairingStateV1(generation, List(count) { record() })
    }
    /** Inert exact-record storage for an additive consumer. Preserve the predecessor link
     * without pretending an APPROVED subset is the original pairing ledger. The actual
     * owner must join it to its complete unchanged ledger/predecessor grammar separately. */
    fun encodeRetainedRecord(value: NativeDevicePairingRecordV1): ByteArray = selfOwnedEncode(
        "native-device-pairing-retained-record-data/v1", LocalDevicePairingProfileV1.MAXIMUM_PLAINTEXT_BYTES) { record(value) }
    fun decodeRetainedRecord(bytes: ByteArray): NativeDevicePairingRecordV1 =
        selfOwnedDecode(bytes, "native-device-pairing-retained-record-data/v1") { record() }
    /** Completion fields are bounded independently: possession<=2048, consent<=2048,
     * approval<=1024, disposition<=1024, either endorsement64 plus framing/phase growth.
     * Reserve8192 for EACH unfinished action, including either terminal completion path.
     * No truncated census, synthetic approval or worst-case universal count-fit claim. */
    fun encodeWithCompletionCapacity(value: NativeDevicePairingStateV1): ByteArray {
        val bytes = encode(value)
        try {
            val unfinished = value.records.count { it.phase !in setOf(LocalDevicePairingPhase.APPROVED, LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED) }
            require(bytes.size.toLong() + unfinished.toLong() * LocalDevicePairingProfileV1.COMPLETION_RESERVATION_BYTES <=
                LocalDevicePairingProfileV1.MAXIMUM_PLAINTEXT_BYTES.toLong())
            return bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }
    /** Actual retained bytes plus actual requested/census/origin/head framing, before IDs.
     * 512 covers fixed UUID/nonce/phase/optional-link framing; 8192 reserves either the
     * approval or known-unsigned terminal path (never both endorsements on one action). */
    fun requireAppendCapacity(previous: NativeDevicePairingStateV1?, requested: LocalDevicePairingDefinition,
        original: NativeDevicePairingOriginV1, prepared: NativeDevicePairingHeadV1) {
        if (previous != null && previous.generation >= Long.MAX_VALUE - 2)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val retained = previous?.let { encodeWithCompletionCapacity(it) }
        val definition = requested.copyWireBytes()
        val framing = try { selfOwnedEncode("bounded-one-device-pairing-append-framing/v1",
            LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES) { origin(original); head(prepared); field(definition) } }
        finally { definition.fill(0) }
        try {
            val unfinished = previous?.records.orEmpty().count { it.phase !in setOf(LocalDevicePairingPhase.APPROVED,
                LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED) }
            if ((retained?.size ?: 128).toLong() + framing.size.toLong() + 512L +
                (unfinished.toLong() + 1L) * LocalDevicePairingProfileV1.COMPLETION_RESERVATION_BYTES >
                LocalDevicePairingProfileV1.MAXIMUM_PLAINTEXT_BYTES)
                selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        } finally { retained?.fill(0); framing.fill(0) }
    }
    fun validateTransition(previous: NativeDevicePairingStateV1?, next: NativeDevicePairingStateV1) {
        if (previous == null) { require(next.generation == 0L && next.records.size == 1 &&
            next.records.single().phase == LocalDevicePairingPhase.PREPARED); return }
        require(next.generation == previous.generation + 1 && next.records.size in previous.records.size..previous.records.size + 1)
        if (next.records.size > previous.records.size) {
            require(next.records.take(previous.records.size) == previous.records && next.records.last().phase == LocalDevicePairingPhase.PREPARED &&
                previous.records.none { it.phase in setOf(LocalDevicePairingPhase.HEAD_CONSENT_INTENT, LocalDevicePairingPhase.SIGNING_INTENT,
                    LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT) }); return
        }
        val changes = previous.records.zip(next.records).filter { (before, after) -> before != after }; require(changes.size == 1)
        val (before, after) = changes.single(); require(before.body == after.body)
        if (after.phase == LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT) {
            require(before.phase in setOf(LocalDevicePairingPhase.PREPARED, LocalDevicePairingPhase.TARGET_PROVED,
                LocalDevicePairingPhase.HEAD_CONSENT_INTENT) && after.unsignedDisposition?.originalPhase == before.phase &&
                after == before.copy(phase = after.phase, unsignedDisposition = after.unsignedDisposition)); return
        }
        when (before.phase) {
            LocalDevicePairingPhase.PREPARED -> require(after == before.copy(phase = LocalDevicePairingPhase.TARGET_PROVED, possession = after.possession))
            LocalDevicePairingPhase.TARGET_PROVED -> require(after == before.copy(phase = LocalDevicePairingPhase.HEAD_CONSENT_INTENT, consent = after.consent))
            LocalDevicePairingPhase.HEAD_CONSENT_INTENT -> require(after == before.copy(phase = LocalDevicePairingPhase.SIGNING_INTENT, approvedHead = after.approvedHead))
            LocalDevicePairingPhase.SIGNING_INTENT -> require(after == before.copy(phase = LocalDevicePairingPhase.APPROVED, rootEndorsement = after.rootEndorsement))
            LocalDevicePairingPhase.APPROVED -> error("Original one-device pairing approval is immutable")
            LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT -> require(after == before.copy(
                phase = LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED, retirementEndorsement = after.retirementEndorsement))
            LocalDevicePairingPhase.RETIRED_KNOWN_UNSIGNED -> error("Original known-unsigned disposition is immutable")
        }
    }
    private fun SelfOwnedWireWriterV1.record(value: NativeDevicePairingRecordV1) {
        body(value.body); string(value.phase.name)
        bool(value.possession != null); value.possession?.let { octets(it) }
        bool(value.consent != null); value.consent?.let { consent(it) }
        bool(value.approvedHead != null); value.approvedHead?.let { approval(it) }
        octets(value.rootEndorsement)
        bool(value.unsignedDisposition != null); value.unsignedDisposition?.let { disposition(it) }
        octets(value.retirementEndorsement)
    }
    private fun SelfOwnedWireReaderV1.record(): NativeDevicePairingRecordV1 {
        val body = body(); val phase = named(LocalDevicePairingPhase.entries)
        val proof = if (bool()) octets(LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES) else null
        val consent = if (bool()) consent() else null; val approved = if (bool()) approval() else null
        val root = octets(64); val disposition = if (bool()) disposition() else null
        return NativeDevicePairingRecordV1(body, phase, proof, consent, approved, root, disposition, octets(64))
    }
    private fun SelfOwnedWireWriterV1.body(value: NativeDevicePairingBodyV1) {
        id(value.originalActionId); origin(value.origin); octets(value.definition); head(value.preparedHead); octets(value.nonce)
        bool(value.previousUnsignedRetirementDigest != null); value.previousUnsignedRetirementDigest?.let { digest(it) }
    }
    private fun SelfOwnedWireReaderV1.body(): NativeDevicePairingBodyV1 = NativeDevicePairingBodyV1(id(), origin(),
        octets(LocalDevicePairingProfileV1.MAXIMUM_DEFINITION_BYTES), head(), octets(32), if (bool()) digest() else null)
    private fun SelfOwnedWireWriterV1.origin(value: NativeDevicePairingOriginV1) {
        coordinates(value.coordinates); digest(value.allocationDigest); digest(value.bindingDigest); id(value.installationRequestId)
        digest(value.installationDefinitionDigest); digest(value.installationReceiptDigest); long(value.authorityEpoch.value)
        historyHead(value.preparationParent); digest(value.preparationAnchorDigest); id(value.rootId); id(value.rootKeyId); digest(value.rootDocumentDigest)
        id(value.foundingActionId); id(value.headAuthorityId); id(value.headEnrollmentId); string(value.rootFingerprint)
    }
    private fun SelfOwnedWireReaderV1.origin(): NativeDevicePairingOriginV1 = NativeDevicePairingOriginV1(coordinates(), digest(), digest(),
        id(), digest(), digest(), Revision(long()), historyHead(), digest(), id(), id(), digest(), id(), id(), id(), string(64))
    private fun SelfOwnedWireWriterV1.head(value: NativeDevicePairingHeadV1) {
        string(value.companyName); string(value.headName); string(value.headLogin); long(value.accountRevision); long(value.credentialRevision)
        long(value.retainedRevision); int(value.osBootCount); observation(value.observation); long(value.deadlineElapsedMillis)
    }
    private fun SelfOwnedWireReaderV1.head(): NativeDevicePairingHeadV1 = NativeDevicePairingHeadV1(string(640), string(640), string(64),
        long(), long(), long(), int(), observation(), long())
    private fun SelfOwnedWireWriterV1.consent(value: NativeDevicePairingConsentV1) {
        id(value.consentId); digest(value.actionDigest); observation(value.observation); long(value.accountRevision); long(value.credentialRevision)
        long(value.retainedRevision); int(value.osBootCount); long(value.deadlineElapsedMillis)
    }
    private fun SelfOwnedWireReaderV1.consent(): NativeDevicePairingConsentV1 = NativeDevicePairingConsentV1(id(), digest(), observation(),
        long(), long(), long(), int(), long())
    private fun SelfOwnedWireWriterV1.disposition(value: NativeDevicePairingUnsignedDispositionV1) {
        id(value.dispositionId); string(value.originalPhase.name); digest(value.originalUnsignedDigest)
        long(value.retainedRevision); long(value.accountRevision); long(value.credentialRevision)
        int(value.osBootCount); observation(value.observation)
    }
    private fun SelfOwnedWireReaderV1.disposition(): NativeDevicePairingUnsignedDispositionV1 = NativeDevicePairingUnsignedDispositionV1(
        id(), named(LocalDevicePairingPhase.entries), digest(), long(), long(), long(), int(), observation())
    private fun SelfOwnedWireWriterV1.approval(value: NativeDevicePairingApprovedHeadV1) {
        long(value.accountRevision); long(value.credentialRevision); long(value.retainedRevision); int(value.osBootCount); long(value.deadlineElapsedMillis)
    }
    private fun SelfOwnedWireReaderV1.approval(): NativeDevicePairingApprovedHeadV1 = NativeDevicePairingApprovedHeadV1(long(), long(), long(), int(), long())
    private fun SelfOwnedWireWriterV1.admission(value: LocalDeviceAdmission) {
        scope(value.scope); id(value.deviceId); long(value.revision.value); bool(value.admitted)
        nullableString(value.publicKeyIdentifier); nullableString(value.publicFingerprint); int(value.capabilities.size)
        value.capabilities.sorted().forEach { string(it) }
    }
    private fun SelfOwnedWireReaderV1.admission(): LocalDeviceAdmission {
        val scope = scope(); val device = id(); val revision = Revision(long()); val admitted = bool()
        val key = nullableString(ProtectedLocalStorageFormat.MAX_ADMISSION_DESCRIPTOR_BYTES)
        val fingerprint = nullableString(ProtectedLocalStorageFormat.MAX_ADMISSION_DESCRIPTOR_BYTES)
        val count = int(); require(count in 0..ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES)
        val codes = List(count) { string(ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES) }
        require(codes.distinct().size == codes.size && codes == codes.sorted())
        return LocalDeviceAdmission(scope, device, revision, admitted, key, fingerprint, codes.toSet())
    }
    private fun SelfOwnedWireWriterV1.request(value: LocalProvisioningRequest) { scope(value.scope); id(value.requestId)
        string(value.action.name); id(value.targetId); digest(value.contentDigest) }
    private fun SelfOwnedWireReaderV1.request(): LocalProvisioningRequest = LocalProvisioningRequest(scope(), id(),
        named(LocalProvisioningAction.entries), id(), digest())
    private fun SelfOwnedWireWriterV1.historyHead(value: NativeHistoryRecordedHeadV2) { long(value.historySequence); digest(value.historyDigest)
        digest(value.installationGenesisDigest); long(value.commandEpoch); long(value.commandSequence); digest(value.commandDigest)
        digest(value.roleCommitment); digest(value.recoveryCommitment) }
    private fun SelfOwnedWireReaderV1.historyHead(): NativeHistoryRecordedHeadV2 = NativeHistoryRecordedHeadV2(long(), digest(), digest(), long(), long(), digest(), digest(), digest())
    private fun SelfOwnedWireWriterV1.observation(value: LocalAuthenticationObservation) { id(value.issuerContextId)
        id(value.moment.bootId); long(value.moment.elapsedMillis) }
    private fun SelfOwnedWireReaderV1.observation(): LocalAuthenticationObservation = LocalAuthenticationObservation(id(), MonotonicMoment(id(), long()))
    private fun SelfOwnedWireWriterV1.scope(value: BranchScope) { id(value.organizationId); id(value.companyId); id(value.branchId) }
    private fun SelfOwnedWireReaderV1.scope(): BranchScope = BranchScope(id(), id(), id())
    private fun SelfOwnedWireWriterV1.digest(value: PayloadDigest) { string(value.value) }
    private fun SelfOwnedWireReaderV1.digest(): PayloadDigest = PayloadDigest(string(64))
    private fun SelfOwnedWireWriterV1.nullableString(value: String?) { bool(value != null); value?.let { string(it) } }
    private fun SelfOwnedWireReaderV1.nullableString(maximum: Int): String? = if (bool()) string(maximum) else null
    private fun <T : Enum<T>> SelfOwnedWireReaderV1.named(values: List<T>): T = string(128).let { name ->
        requireNotNull(values.singleOrNull { it.name == name }) }
    private fun SelfOwnedWireReaderV1.purpose() { require(string(128) == LocalDevicePairingProfileV1.PURPOSE) }
    private fun profileDomain(v1: String, sdkBound: Boolean): String = if (sdkBound) v1.removeSuffix("/v1") + "/v2" else v1
    private fun pairingPurpose(sdkBound: Boolean): String = if (sdkBound) LocalDevicePairingProfileV2.PURPOSE else LocalDevicePairingProfileV1.PURPOSE
    private fun retirementPurpose(sdkBound: Boolean): String = if (sdkBound) LocalDevicePairingProfileV2.UNSIGNED_RETIREMENT_PURPOSE else LocalDevicePairingProfileV1.UNSIGNED_RETIREMENT_PURPOSE
}
