package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.setup.LocalSetupFreshDeviceMasterOriginalView
import com.ukkera.pos.app.setup.LocalSetupFreshDevicePairingRole
import com.ukkera.pos.app.setup.LocalSetupFreshDevicePairingStage
import com.ukkera.pos.app.setup.LocalSetupFreshDevicePairingView
import com.ukkera.pos.app.setup.LocalSetupFreshDeviceTargetInput
import com.ukkera.pos.app.setup.LocalSetupPairingDocumentView
import com.ukkera.pos.security.LocalDevicePairingAction
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingMasterTransfer
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingProfileV2
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingTargetDescriptor
import com.ukkera.pos.security.LocalDevicePairingTargetReference
import com.ukkera.pos.security.LocalDevicePairingUnsignedRetirement
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId

/** Same-owner workflow memory. Journals preserve uncertainty; native reads supply outcomes. */
internal class LocalSetupFreshDeviceTargetOriginal(var intentId: DomainId? = null,
    var input: LocalSetupFreshDeviceTargetInput? = null) {
    var record: LocalFreshDevicePairingIntents.TargetRecord? = null
        private set
    private class ResponseState {
        var reference: LocalDevicePairingTargetReference? = null
        var challenge: LocalDevicePairingChallenge? = null
        var possession: LocalDevicePairingPossession? = null
        var entered = false
        var cycle: LocalFreshDevicePairingIntents.ResponseCycle? = null
        var certificate: LocalDevicePairingUnsignedRetirement? = null
        var masterTransfer: LocalDevicePairingMasterTransfer? = null
        var verifiedMaster: LocalDevicePairingApprovedMaster? = null
        var masterImportEntered = false
    }
    private val responses = mutableMapOf(0 to ResponseState())
    var responseCycle: Int = 0
        private set
    private val response: ResponseState get() = checkNotNull(responses[responseCycle])
    var reference: LocalDevicePairingTargetReference?
        get() = response.reference
        private set(value) { response.reference = value }
    var retirement: LocalDevicePairingUnsignedRetirement? = null
        private set
    val continuationAttempted: Boolean get() = response.cycle?.allocationResponseId == null && responseCycle > 0
    val continuationConfirmed: Boolean get() = responseCycle > 0 && response.certificate != null &&
        response.cycle?.allocationResponseId != null && reference != null
    var descriptor: LocalDevicePairingTargetDescriptor? = null
        private set
    var identity: LocalDevicePairingSdkIdentityV2? = null
        private set
    var challenge: LocalDevicePairingChallenge?
        get() = response.challenge
        private set(value) { response.challenge = value }
    var possession: LocalDevicePairingPossession?
        get() = response.possession
        private set(value) { response.possession = value }
    var preparationEntered = false
    var sdkEntered = false
    var responseEntered: Boolean
        get() = response.entered
        set(value) { response.entered = value }
    var document: LocalSetupPairingDocumentView? = null
    val masterTransfer: LocalDevicePairingMasterTransfer? get() = response.masterTransfer
    val verifiedMaster: LocalDevicePairingApprovedMaster? get() = response.verifiedMaster
    var masterImportEntered: Boolean
        get() = response.masterImportEntered
        set(value) { response.masterImportEntered = value }
    val responseActionId: DomainId? get() = response.cycle?.responseActionId ?:
        (if (responseCycle == 0) record?.responseActionId else null) ?: reference?.originalPairingActionId ?: challenge?.originalActionId

    fun retainRecord(next: LocalFreshDevicePairingIntents.TargetRecord) {
        val requested = next.identity
        require(intentId == null || intentId == requested.intentId)
        val retainedInput = LocalSetupFreshDeviceTargetInput(requested.scope, requested.capabilities,
            requested.appId, requested.deviceName)
        input?.let { require(it.matches(retainedInput)) }
        record?.let { require(it.identity == next.identity) }
        intentId = requested.intentId; input = retainedInput
        reference?.let { require(next.originalPreparationId == null ||
            next.originalPreparationId == it.originalPreparationId && next.deviceId == it.deviceId && next.keyId == it.keyId) }
        identity?.let { require(next.sdkDigest == null || next.sdkDigest == it.descriptorDigest.value &&
            next.acquisitionId == it.originalAcquisitionId) }
        responses[0]?.challenge?.let { require(next.responseActionId == null || next.responseActionId == it.originalActionId &&
            next.challengeDigest == it.challengeDigest.value) }
        responses[0]?.possession?.let { require(next.possessionDigest == null || next.possessionDigest == freshPossessionDigest(it)) }
        record = next
        preparationEntered = preparationEntered || next.preparationAttempted
        sdkEntered = sdkEntered || next.sdkAttempted
        checkNotNull(responses[0]).entered = checkNotNull(responses[0]).entered || next.responseActionId != null
    }

    fun retainReference(next: LocalDevicePairingTargetReference) {
        reference?.let { before ->
            require(before.originalPreparationId == next.originalPreparationId && before.deviceId == next.deviceId && before.keyId == next.keyId)
            before.originalResponseId?.let { require(it == next.originalResponseId) }
            before.originalPairingActionId?.let { require(it == next.originalPairingActionId) }
            before.challengeDigest?.let { require(it == next.challengeDigest) }
            before.originalSdkAcquisitionId?.let { require(it == next.originalSdkAcquisitionId) }
            before.sdkIdentityDigest?.let { require(it == next.sdkIdentityDigest) }
        }
        record?.let { before ->
            before.originalPreparationId?.let { require(it == next.originalPreparationId && before.deviceId == next.deviceId && before.keyId == next.keyId) }
            next.originalSdkAcquisitionId?.let { require(before.acquisitionId == null || before.acquisitionId == it) }
            next.sdkIdentityDigest?.let { require(before.sdkDigest == null || before.sdkDigest == it.value) }
            if (responseCycle == 0) {
                next.originalPairingActionId?.let { require(before.responseActionId == null || before.responseActionId == it) }
                next.challengeDigest?.let { require(before.challengeDigest == null || before.challengeDigest == it.value) }
            }
        }
        challenge?.let { retained -> next.originalPairingActionId?.let { require(it == retained.originalActionId &&
            next.challengeDigest == retained.challengeDigest) } }
        response.cycle?.let { cycle ->
            require(cycle.allocationResponseId != null && next.originalResponseId == cycle.allocationResponseId)
            cycle.responseActionId?.let { require(next.originalPairingActionId == it && next.challengeDigest?.value == cycle.challengeDigest) }
        }
        reference = next
        preparationEntered = true
        sdkEntered = sdkEntered || next.originalSdkAcquisitionId != null
        responseEntered = responseEntered || next.originalPairingActionId != null
    }

    fun retainDescriptor(next: LocalDevicePairingTargetDescriptor) {
        require(next.admission.deviceId == (reference?.deviceId ?: record?.deviceId) && next.keyId == (reference?.keyId ?: record?.keyId))
        input?.let { require(next.admission.scope == it.scope && next.admission.capabilities == it.capabilities) }
        descriptor?.let { require(sameFreshDescriptor(it, next)) }
        descriptor = next
    }

    fun retainIdentity(next: LocalDevicePairingSdkIdentityV2) {
        require(next.originalPreparationId == (reference?.originalPreparationId ?: record?.originalPreparationId) &&
            next.target.admission.deviceId == (reference?.deviceId ?: record?.deviceId) && next.target.keyId == (reference?.keyId ?: record?.keyId))
        reference?.originalSdkAcquisitionId?.let { require(it == next.originalAcquisitionId) }
        reference?.sdkIdentityDigest?.let { require(it == next.descriptorDigest) }
        descriptor?.let { require(sameFreshDescriptor(it, next.target)) }
        input?.let { require(next.target.admission.scope == it.scope && next.target.admission.capabilities == it.capabilities && next.appId.value == it.appId) }
        record?.let { before ->
            before.sdkDigest?.let { require(it == next.descriptorDigest.value && before.acquisitionId == next.originalAcquisitionId) }
        }
        identity?.let { require(sameFreshIdentity(it, next)) }
        identity = next; sdkEntered = true
    }

    fun retainChallenge(next: LocalDevicePairingChallenge) {
        requireFreshChallengeIdentity(next, checkNotNull(identity))
        descriptor?.let { require(sameFreshDescriptor(it, next.requested.target)) }
        if (responseCycle == 0) record?.responseActionId?.let { require(it == next.originalActionId && record?.challengeDigest == next.challengeDigest.value) }
        require(next.reviewContext.previousUnsignedRetirementDigest?.value == response.cycle?.retirementDigest)
        response.certificate?.let { prior -> requireSameFreshOrigin(prior.originalChallenge, next) }
        response.cycle?.responseActionId?.let { require(it == next.originalActionId && response.cycle?.challengeDigest == next.challengeDigest.value) }
        reference?.originalPairingActionId?.let { require(it == next.originalActionId && reference?.challengeDigest == next.challengeDigest) }
        challenge?.let { require(sameFreshChallenge(it, next)) }
        challenge = next
    }

    fun retainPossession(next: LocalDevicePairingPossession) {
        val sdk = checkNotNull(identity)
        require(next.originalActionId == responseActionId && next.deviceId == sdk.target.admission.deviceId &&
            next.keyId == sdk.target.keyId && next.descriptorDigest == sdk.target.descriptorDigest && next.sdkIdentityDigest == sdk.descriptorDigest)
        challenge?.let { requireFreshPossession(it, next) }
        if (responseCycle == 0) record?.challengeDigest?.let { require(it == next.challengeDigest.value) }
        reference?.challengeDigest?.let { require(it == next.challengeDigest) }
        if (responseCycle == 0) record?.possessionDigest?.let { require(it == freshPossessionDigest(next)) }
        response.cycle?.possessionDigest?.let { require(it == freshPossessionDigest(next)) }
        possession?.let { require(sameFreshPossession(it, next)) }
        possession = next; responseEntered = true
    }

    fun retainMasterTransfer(next: LocalDevicePairingMasterTransfer) {
        require(possession != null)
        retainChallenge(next.originalChallenge)
        masterTransfer?.let { require(it.transferDigest == next.transferDigest) }
        verifiedMaster?.let { require(it.masterSdkOriginal.descriptorDigest == next.masterSdkOriginal.descriptorDigest &&
            it.approvalCertificateDigest == next.authorityCertificateDigest && it.rootDocumentDigest == next.rootDocumentDigest) }
        response.masterTransfer = next
    }

    fun retainVerifiedMaster(next: LocalDevicePairingApprovedMaster) {
        // The native result is qualified independently; still join it to this exact UI original.
        require(next.originalPairingActionId == responseActionId && next.targetSdkIdentity.descriptorDigest == checkNotNull(identity).descriptorDigest)
        require(freshPossessionDigest(checkNotNull(possession)) == next.possessionDigest.value)
        reference?.challengeDigest?.let { require(it == next.challengeDigest) }
        challenge?.let { require(it.challengeDigest == next.challengeDigest &&
            it.requested.masterSdkOriginal?.descriptorDigest == next.masterSdkOriginal.descriptorDigest) }
        masterTransfer?.let { require(it.masterSdkOriginal.descriptorDigest == next.masterSdkOriginal.descriptorDigest &&
            it.authorityCertificateDigest == next.approvalCertificateDigest && it.rootDocumentDigest == next.rootDocumentDigest) }
        verifiedMaster?.let { require(it.masterSdkOriginal.descriptorDigest == next.masterSdkOriginal.descriptorDigest &&
            it.approvalCertificateDigest == next.approvalCertificateDigest && it.rootDocumentDigest == next.rootDocumentDigest) }
        response.verifiedMaster = next
    }

    fun retainCycles(next: List<LocalFreshDevicePairingIntents.ResponseCycle>) {
        require(next.map { it.index } == (1..next.size).toList() && next.size >= responseCycle)
        for (cycle in next) {
            val retained = responses.getOrPut(cycle.index) { ResponseState() }
            retained.cycle?.let { before ->
                require(before.index == cycle.index && before.retiredActionId == cycle.retiredActionId &&
                    before.retiredChallengeDigest == cycle.retiredChallengeDigest && before.retirementDigest == cycle.retirementDigest &&
                    before.previousResponseId == cycle.previousResponseId)
                before.allocationResponseId?.let { require(it == cycle.allocationResponseId) }
                before.responseActionId?.let { require(it == cycle.responseActionId && before.challengeDigest == cycle.challengeDigest) }
                before.possessionDigest?.let { require(it == cycle.possessionDigest) }
            }
            retained.cycle = cycle; retained.entered = retained.entered || cycle.responseActionId != null
        }
        if (next.size > responseCycle) {
            val imported = retirement
            responseCycle = next.size
            response.certificate = imported?.takeIf { it.dispositionDigest.value == response.cycle?.retirementDigest }
            retirement = null
        }
    }
    fun retainRetirement(next: LocalDevicePairingUnsignedRetirement) {
        requireFreshChallengeIdentity(next.originalChallenge, checkNotNull(identity))
        val cycle = response.cycle
        // An attempted successor must first recover its exact certificate; no replacement DATA.
        if (cycle != null && (cycle.allocationResponseId == null || response.certificate == null)) {
            require(next.originalActionId == cycle.retiredActionId && next.originalChallenge.challengeDigest.value == cycle.retiredChallengeDigest &&
                next.dispositionDigest.value == cycle.retirementDigest)
            response.certificate?.let { require(sameFreshRetirement(it, next)) }
            response.certificate = next
            return
        }
        require(!responseEntered || possession != null)
        challenge?.let { require(sameFreshChallenge(it, next.originalChallenge)) }
        require(next.originalChallenge.reviewContext.previousUnsignedRetirementDigest?.value == cycle?.retirementDigest)
        response.certificate?.let { requireSameFreshOrigin(it.originalChallenge, next.originalChallenge) }
        retirement?.let { require(sameFreshRetirement(it, next)) }
        retirement = next
    }
    val retainedContinuationCertificate: LocalDevicePairingUnsignedRetirement? get() = response.certificate

    fun projection(stage: LocalSetupFreshDevicePairingStage): LocalSetupFreshDevicePairingView =
        LocalSetupFreshDevicePairingView(DomainId.random(), LocalSetupFreshDevicePairingRole.FRESH_TARGET,
            stage, input, intentId, targetReference = reference, identity = identity, challenge = challenge,
            possession = possession, preparationAttempted = preparationEntered, sdkAttempted = sdkEntered,
            responseAttempted = responseEntered, document = document, retirement = retirement ?: response.certificate,
            continuationAttempted = continuationAttempted, responseCycle = responseCycle,
            masterTransfer = masterTransfer, verifiedMaster = verifiedMaster?.masterSdkOriginal)
}

internal class LocalSetupFreshDeviceMasterOriginal(val context: LocalDeviceAdmissionIntents.Context,
    val requestId: DomainId) {
    var record: LocalFreshDevicePairingIntents.MasterRecord? = null
        private set
    var identity: LocalDevicePairingSdkIdentityV2? = null
        private set
    var action: LocalDevicePairingAction? = null
        private set
    var possession: LocalDevicePairingPossession? = null
        private set
    var preparationEntered = false
    var proofEntered = false
    var nativeConfirmed = false
    var retirement: LocalDevicePairingUnsignedRetirement? = null
        private set
    var retirementReview = false
    val retirementEntered: Boolean get() = record?.retirementAttempted == true
    var document: LocalSetupPairingDocumentView? = null

    fun retainRecord(next: LocalFreshDevicePairingIntents.MasterRecord) {
        require(next.identity.context == context && next.identity.requestId == requestId)
        record?.let { require(it.identity == next.identity) }
        identity?.let { requireIdentity(next, it) }
        action?.let { retained -> require(next.actionId == null || next.actionId == retained.originalActionId &&
            next.challengeDigest == retained.challenge.challengeDigest.value) }
        possession?.let { require(next.possessionDigest == null || next.possessionDigest == freshPossessionDigest(it)) }
        record?.let { before ->
            require(!before.retirementAttempted || next.retirementAttempted)
            before.retirementDigest?.let { require(it == next.retirementDigest) }
        }
        retirement?.let { require(next.retirementDigest == null || next.retirementDigest == it.dispositionDigest.value) }
        record = next
        preparationEntered = preparationEntered || next.preparationAttempted
        proofEntered = proofEntered || next.proofAttempted
    }

    fun retainIdentity(next: LocalDevicePairingSdkIdentityV2) {
        require(next.target.admission.scope == context.scope && next.target.admission.deviceId != context.cohostDeviceId)
        require(requestId !in listOf(next.originalAcquisitionId, next.originalPreparationId, next.target.admission.deviceId, next.target.keyId))
        record?.let { requireIdentity(it, next) }
        identity?.let { require(sameFreshIdentity(it, next)) }
        identity = next
    }

    fun retainAction(next: LocalDevicePairingAction) {
        val challenge = next.challenge
        require(next.requested.request.requestId == requestId && next.requested.request.scope == context.scope &&
            challenge.cohostDeviceId == context.cohostDeviceId && challenge.reviewContext.coordinates.installationId == context.installationId)
        val sdk = checkNotNull(next.requested.sdkIdentity)
        require(next.requested.purpose == LocalDevicePairingProfileV2.PURPOSE)
        retainIdentity(sdk)
        requireFreshChallengeIdentity(challenge, sdk)
        record?.let { retained ->
            retained.actionId?.let { require(it == next.originalActionId && retained.challengeDigest == challenge.challengeDigest.value) }
        }
        action?.let { require(it.originalActionId == next.originalActionId && sameFreshChallenge(it.challenge, challenge)) }
        next.possession?.let { retained ->
            requireFreshPossession(challenge, retained)
            record?.possessionDigest?.let { require(it == freshPossessionDigest(retained)) }
            possession?.let { require(sameFreshPossession(it, retained)) }
            possession = retained
        }
        action = next; preparationEntered = true
    }

    fun retainPossession(next: LocalDevicePairingPossession) {
        requireFreshPossession(checkNotNull(action).challenge, next)
        record?.possessionDigest?.let { require(it == freshPossessionDigest(next)) }
        possession?.let { require(sameFreshPossession(it, next)) }
        possession = next
    }

    fun retainRetirement(next: LocalDevicePairingUnsignedRetirement) {
        val retained = checkNotNull(action)
        require(next.originalActionId == retained.originalActionId && sameFreshChallenge(next.originalChallenge, retained.challenge))
        record?.retirementDigest?.let { require(it == next.dispositionDigest.value) }
        retirement?.let { require(sameFreshRetirement(it, next)) }
        retirement = next; retirementReview = false
    }

    fun projection(stage: LocalSetupFreshDevicePairingStage,
        originals: List<LocalFreshDevicePairingIntents.MasterRecord> = emptyList(), blockedRequestId: DomainId? = null): LocalSetupFreshDevicePairingView =
        LocalSetupFreshDevicePairingView(DomainId.random(), LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER,
            stage, originalRequestId = requestId, identity = identity, challenge = action?.challenge,
            possession = possession, nativePhase = action?.phase?.takeIf { nativeConfirmed }, preparationAttempted = preparationEntered,
            proofAttempted = proofEntered, masterOriginals = originals.map { LocalSetupFreshDeviceMasterOriginalView(
                it.identity.requestId, it.actionId, it.identity.deviceId, it.identity.keyId) },
            blockedRequestId = blockedRequestId, document = document, retirement = retirement, retirementAttempted = retirementEntered)

    private fun requireIdentity(record: LocalFreshDevicePairingIntents.MasterRecord, sdk: LocalDevicePairingSdkIdentityV2) {
        val retained = record.identity
        require(retained.sdkDigest == sdk.descriptorDigest.value && retained.acquisitionId == sdk.originalAcquisitionId &&
            retained.originalPreparationId == sdk.originalPreparationId && retained.deviceId == sdk.target.admission.deviceId &&
            retained.keyId == sdk.target.keyId)
    }
}

internal fun requireFreshChallengeIdentity(challenge: LocalDevicePairingChallenge, identity: LocalDevicePairingSdkIdentityV2) {
    val requested = challenge.requested
    require(requested.purpose == LocalDevicePairingProfileV2.PURPOSE &&
        sameFreshIdentity(checkNotNull(requested.sdkIdentity), identity) && sameFreshDescriptor(requested.target, identity.target) &&
        requested.request.scope == identity.target.admission.scope && requested.admission.scope == identity.target.admission.scope &&
        requested.admission.admitted && requested.admission.revision.value == 0L &&
        challenge.cohostDeviceId != identity.target.admission.deviceId)
}

internal fun requireFreshPossession(challenge: LocalDevicePairingChallenge, possession: LocalDevicePairingPossession) {
    val target = challenge.requested.target; val sdk = checkNotNull(challenge.requested.sdkIdentity)
    require(possession.originalActionId == challenge.originalActionId && possession.challengeDigest == challenge.challengeDigest &&
        possession.deviceId == target.admission.deviceId && possession.keyId == target.keyId &&
        possession.descriptorDigest == target.descriptorDigest && possession.sdkIdentityDigest == sdk.descriptorDigest)
}

internal fun sameFreshIdentity(a: LocalDevicePairingSdkIdentityV2, b: LocalDevicePairingSdkIdentityV2): Boolean =
    sameFreshBytes(a.copyWireBytes(), b.copyWireBytes())
internal fun sameFreshDescriptor(a: LocalDevicePairingTargetDescriptor, b: LocalDevicePairingTargetDescriptor): Boolean =
    sameFreshBytes(a.copyWireBytes(), b.copyWireBytes())
internal fun sameFreshChallenge(a: LocalDevicePairingChallenge, b: LocalDevicePairingChallenge): Boolean =
    sameFreshBytes(a.copyWireBytes(), b.copyWireBytes())
internal fun sameFreshPossession(a: LocalDevicePairingPossession, b: LocalDevicePairingPossession): Boolean =
    sameFreshBytes(a.copyWireBytes(), b.copyWireBytes())
private fun sameFreshBytes(a: ByteArray, b: ByteArray): Boolean = try { a.contentEquals(b) }
    finally { a.fill(0); b.fill(0) }
internal fun freshPossessionDigest(possession: LocalDevicePairingPossession): String {
    val bytes = possession.copyWireBytes()
    return try { sha256(bytes).value } finally { bytes.fill(0) }
}

internal fun sameFreshRetirement(a: LocalDevicePairingUnsignedRetirement, b: LocalDevicePairingUnsignedRetirement): Boolean {
    val x = a.copyWireBytes(); val y = b.copyWireBytes()
    return try { x.contentEquals(y) } finally { x.fill(0); y.fill(0) }
}

/** Public original-origin continuity; actual native producer independently validates authority. */
internal fun requireSameFreshOrigin(a: LocalDevicePairingChallenge, b: LocalDevicePairingChallenge) {
    val x = a.reviewContext; val y = b.reviewContext
    val c = x.coordinates; val d = y.coordinates
    require(c.allocationId == d.allocationId && c.scope == d.scope && c.installationId == d.installationId &&
        c.assignmentId == d.assignmentId && c.deviceId == d.deviceId && c.storageInstanceId == d.storageInstanceId &&
        c.storageKeyId == d.storageKeyId && c.pepperKeyId == d.pepperKeyId && c.targetSigningKeyId == d.targetSigningKeyId &&
        c.firstActorId == d.firstActorId && x.rootId == y.rootId && x.rootKeyId == y.rootKeyId &&
        x.rootDocumentDigest == y.rootDocumentDigest && x.rootFingerprint == y.rootFingerprint &&
        x.foundingActionId == y.foundingActionId && x.headAuthorityId == y.headAuthorityId &&
        x.headEnrollmentId == y.headEnrollmentId && x.allocationDigest == y.allocationDigest && x.bindingDigest == y.bindingDigest &&
        x.installationRequestId == y.installationRequestId && x.installationDefinitionDigest == y.installationDefinitionDigest &&
        x.installationReceiptDigest == y.installationReceiptDigest && x.authorityEpoch == y.authorityEpoch)
}
