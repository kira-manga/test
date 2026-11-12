package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId

/** Fixed target-only file roles shared by Android and desktop targets (names unchanged from Android). */
internal object DevicePairingTargetFiles {
    const val LOCK = "owner.lock"
    const val IDENTITY = "identity.v1"
    const val KEY_INTENT = "key-signing-intent.v1"
    const val READY = "key-ready.v1"
    const val RESPONSE_INTENT = "response-signing-intent.v1"
    const val COMPLETE = "response-completed.v1"
    const val ALLOCATION_INTENT = "response-allocation-signing-intent.v1"
    const val ALLOCATION_READY = "response-allocation-ready.v1"
    const val SDK_ACQUISITION = "sdk-acquisition-intent.v2"
    const val SDK_SIGNING_INTENT = "sdk-metadata-signing-intent.v2"
    const val SDK_READY = "sdk-ready.v2"
    const val APPROVED_MASTER_IMPORT_INTENT = "approved-master-import-intent.v1"
    const val APPROVED_MASTER_EVIDENCE = "approved-master-evidence.v1"
    const val CREDENTIAL_ALLOCATION = "paired-device-credential-allocation.v1"
    const val MAXIMUM_RESPONSE_CYCLES = DevicePairingTargetLimitsV1.MAXIMUM_RESPONSE_CYCLES

    fun responseRole(index: Int, role: String): String {
        require(index in 0 until MAXIMUM_RESPONSE_CYCLES)
        return if (index == 0) role else "append-${index.toString().padStart(3, '0')}-$role"
    }
    val SDK_ROLES = listOf(SDK_ACQUISITION, SDK_SIGNING_INTENT, SDK_READY)
    val APPROVED_MASTER_ROLES = listOf(APPROVED_MASTER_IMPORT_INTENT, APPROVED_MASTER_EVIDENCE)
    val CREDENTIAL_ROLES = listOf(CREDENTIAL_ALLOCATION)
    val FILE_ROLES: List<String> = listOf(IDENTITY, KEY_INTENT, READY, RESPONSE_INTENT, COMPLETE) + SDK_ROLES +
        APPROVED_MASTER_ROLES + (1 until MAXIMUM_RESPONSE_CYCLES).flatMap { index ->
            (listOf(ALLOCATION_INTENT, ALLOCATION_READY, RESPONSE_INTENT, COMPLETE) + APPROVED_MASTER_ROLES).map { responseRole(index, it) }
        } + CREDENTIAL_ROLES
    /** Roles read at open even when absent. */
    val BASE_ROLES: List<String> = FILE_ROLES.take(5) + SDK_ROLES + APPROVED_MASTER_ROLES + CREDENTIAL_ROLES

    fun maximum(name: String): Int = when (name) {
        IDENTITY -> DevicePairingTargetLimitsV1.IDENTITY_MAX
        KEY_INTENT -> DevicePairingTargetLimitsV1.KEY_INTENT_MAX
        READY -> DevicePairingTargetLimitsV1.READY_MAX
        RESPONSE_INTENT -> DevicePairingTargetLimitsV1.RESPONSE_INTENT_MAX
        COMPLETE -> DevicePairingTargetLimitsV1.COMPLETE_MAX
        SDK_ACQUISITION -> DevicePairingTargetLimitsV1.SDK_ACQUISITION_MAX
        SDK_SIGNING_INTENT -> DevicePairingTargetLimitsV1.SDK_SIGNING_INTENT_MAX
        SDK_READY -> DevicePairingTargetLimitsV1.SDK_READY_MAX
        CREDENTIAL_ALLOCATION -> NativePairedDeviceCredentialJournalWire.MAXIMUM_RECORD_BYTES
        APPROVED_MASTER_IMPORT_INTENT -> DevicePairingTargetLimitsV1.APPROVED_MASTER_IMPORT_INTENT_MAX
        APPROVED_MASTER_EVIDENCE -> DevicePairingTargetLimitsV1.APPROVED_MASTER_EVIDENCE_MAX
        else -> {
            if (name !in FILE_ROLES) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            when {
                name.endsWith(ALLOCATION_INTENT) -> DevicePairingTargetLimitsV1.ALLOCATION_INTENT_MAX
                name.endsWith(ALLOCATION_READY) -> DevicePairingTargetLimitsV1.ALLOCATION_READY_MAX
                name.endsWith(RESPONSE_INTENT) -> DevicePairingTargetLimitsV1.RESPONSE_INTENT_MAX
                name.endsWith(COMPLETE) -> DevicePairingTargetLimitsV1.COMPLETE_MAX
                name.endsWith(APPROVED_MASTER_IMPORT_INTENT) -> DevicePairingTargetLimitsV1.APPROVED_MASTER_IMPORT_INTENT_MAX
                name.endsWith(APPROVED_MASTER_EVIDENCE) -> DevicePairingTargetLimitsV1.APPROVED_MASTER_EVIDENCE_MAX
                else -> error("Unknown target-only append role")
            }
        }
    }
}

/** Official SDK owner of one target original (Android Kit or desktop JVM Kit). */
internal interface DevicePairingTargetSdkOwner {
    /** Called only after the acquisition intent is forced; a lost creation is never retried. */
    fun create(appId: AppId, deviceName: String)
    fun capture(acquisitionId: DomainId, preparationId: DomainId, target: LocalDevicePairingTargetDescriptor): LocalDevicePairingSdkIdentityV2
    fun requireMatches(original: LocalDevicePairingSdkIdentityV2, deviceName: String)
    fun close()
}

/**
 * Platform seam of the shared pairing target: actual storage custody, key custody, boot/origin
 * observations, clock and SDK owner. Every method observes or acts on genuine native state;
 * none may fabricate facts. Snapshot byte arrays returned by [snapshot] are retained, read-only.
 */
internal interface DevicePairingTargetNative {
    val variant: TargetWireVariant
    val sdkProfile: LocalCompositionSdkProfileV1
    fun open(creating: Boolean)
    fun snapshot(name: String): ByteArray?
    /** Exact bounded copy of an unfinished `<name>.pending` write, for original review only. */
    fun pending(name: String, maximum: Int): ByteArray?
    fun forceNew(name: String, bytes: ByteArray)
    fun retainedBytes(): Long
    fun assertContinuity()
    /** Only the expected key (or none before identity) may exist in this target's key namespace. */
    fun assertKeyNamespace(expected: Pair<DomainId, DomainId>?, keyRequired: Boolean)
    fun keyAbsent(preparationId: DomainId, keyId: DomainId): Boolean
    fun generateKey(preparationId: DomainId, keyId: DomainId)
    fun keyPoint(preparationId: DomainId, keyId: DomainId): ByteArray
    fun keySecurity(preparationId: DomainId, keyId: DomainId, retained: SelfOwnedNativeSecurityV1?): SelfOwnedNativeSecurityV1
    fun sign(preparationId: DomainId, keyId: DomainId, message: ByteArray): ByteArray
    fun origin(): TargetOrigin
    fun originMatches(original: TargetOrigin): Boolean
    fun boot(): TargetBoot
    fun clock(): Long
    fun newSdkOwner(): DevicePairingTargetSdkOwner
    fun sdkApplicationMatches(sdk: LocalDevicePairingSdkIdentityV2, original: TargetOrigin): Boolean
    fun releaseNative()
}

/**
 * Shared target-only pairing ceremony for Android and desktop Clients. Immutable forced records form
 * a one-shot sequence, not a repairing/replacement registry; an unfinished intent is reviewable DATA
 * but never permission to try its signature again. Not thread-safe: the owning platform wrapper
 * serializes calls through its own owned work/lock.
 */
internal class DevicePairingTargetCore(private val native: DevicePairingTargetNative) {
    private enum class Phase { NEW, OPEN, CLOSE_STARTED, CLOSED }
    private var phase = Phase.NEW
    private val variant get() = native.variant
    private var identity: TargetIdentity? = null
    private var keyIntent: TargetKeyIntent? = null
    private var ready: TargetReady? = null
    private var sdkAcquisition: TargetSdkAcquisition? = null
    private var sdkSigningIntent: TargetSdkSigningIntent? = null
    private var sdkReady: TargetSdkReady? = null
    private var sdkOwner: DevicePairingTargetSdkOwner? = null
    private var sdkOwnerRejected = false
    private var preparedHere = false
    private var credentialAllocation: NativePairedDeviceCredentialJournalRecord? = null
    private val slots = linkedMapOf(0 to TargetResponseSlot())
    private var responseCycle = 0
    private val verifiedAllocationCycles = mutableSetOf<Int>()
    @Volatile private var lastKnown: LocalDevicePairingTargetReference? = null

    private fun slot(): TargetResponseSlot = checkNotNull(slots[responseCycle])
    private var responseIntent: TargetResponseIntent?
        get() = slot().responseIntent
        set(value) { slot().responseIntent = value }
    private var complete: TargetComplete?
        get() = slot().complete
        set(value) { slot().complete = value }
    private fun responseIntentName(index: Int = responseCycle) = F.responseRole(index, F.RESPONSE_INTENT)
    private fun completeName(index: Int = responseCycle) = F.responseRole(index, F.COMPLETE)
    private fun allocationIntentName(index: Int = responseCycle) = F.responseRole(index, F.ALLOCATION_INTENT)
    private fun allocationReadyName(index: Int = responseCycle) = F.responseRole(index, F.ALLOCATION_READY)
    private fun approvedMasterImportIntentName(index: Int = responseCycle) = F.responseRole(index, F.APPROVED_MASTER_IMPORT_INTENT)
    private fun approvedMasterEvidenceName(index: Int = responseCycle) = F.responseRole(index, F.APPROVED_MASTER_EVIDENCE)
    private fun allocationBytes(index: Int = responseCycle): ByteArray =
        if (index == 0) snapshotBytes(F.IDENTITY) else snapshotBytes(allocationReadyName(index))
    private fun allocatedResponseId(): DomainId = slot().allocation?.responseId ?: checkNotNull(identity).responseId
    private fun allocatedNonce(): SelfOwnedOctetsV1 = slot().allocation?.nonce ?: checkNotNull(identity).nonce
    private fun keyIds(): Pair<DomainId, DomainId> = checkNotNull(identity).let { it.preparationId to it.keyId }

    fun lastKnownOriginalNoIo(): LocalDevicePairingTargetReference? = lastKnown

    fun prepareNew(scope: BranchScope, capabilities: List<String>) {
        check(phase == Phase.NEW); native.open(creating = true); phase = Phase.OPEN
        native.assertKeyNamespace(null, keyRequired = false)
        val forbidden = setOf(scope.organizationId, scope.companyId, scope.branchId)
        requireAppendBytes(DevicePairingTargetLimitsV1.IDENTITY_MAX.toLong() + DevicePairingTargetLimitsV1.KEY_INTENT_MAX +
            DevicePairingTargetLimitsV1.READY_MAX + 8192L)
        val ids = List(4) { JvmP256Primitives.newId() }
        known(ids.distinct().size == ids.size && ids.none { it in forbidden })
        val nonce = JvmP256Primitives.randomBytes(32)
        val original = try { TargetIdentity(ids[0], ids[1], ids[2], ids[3], scope, capabilities.toList(),
            native.origin(), native.boot(), native.clock(), SelfOwnedOctetsV1(nonce, 32)) }
        finally { nonce.fill(0) }
        identity = original; updateReference(LocalDevicePairingTargetPhase.KEY_GENERATION_INTENT)
        val bytes = TargetWire.identity(original, variant)
        try { native.forceNew(F.IDENTITY, bytes) } finally { bytes.fill(0) }
        native.assertKeyNamespace(keyIds(), keyRequired = false)
        known(keyIntent == null && ready == null && native.keyAbsent(original.preparationId, original.keyId))
        // Exact original forced STARTED precedes the only native generation. Any exception,
        // cancellation/lost output or partial file leaves the same IDs, never another attempt.
        known(original.osBoot.sameBoot(native.boot()))
        native.generateKey(original.preparationId, original.keyId)
        native.assertContinuity(); native.assertKeyNamespace(keyIds(), keyRequired = true)
        val security = native.keySecurity(original.preparationId, original.keyId, null)
        val point = native.keyPoint(original.preparationId, original.keyId)
        try {
            val descriptor = expectedDescriptor(original, point)
            val intent = TargetKeyIntent(identityDigest(), native.boot(), native.clock(), security, SelfOwnedOctetsV1(point, 65))
            keyIntent = intent; updateReference(LocalDevicePairingTargetPhase.KEY_METADATA_SIGNING_INTENT)
            val intentBytes = TargetWire.keyIntent(intent, variant)
            try { native.forceNew(F.KEY_INTENT, intentBytes) } finally { intentBytes.fill(0) }
            val descriptorBytes = descriptor.copyWireBytes()
            val message = try { TargetWire.readyMessage(snapshotBytes(F.IDENTITY), snapshotBytes(F.KEY_INTENT), descriptorBytes, variant) }
            finally { descriptorBytes.fill(0) }
            val signature = try { signChecked(message) } catch (failure: Throwable) { message.fill(0); throw failure }
            try {
                checkedKey()
                known(JvmP256Primitives.verify(point, message, signature))
                val signed = TargetReady(SelfOwnedOctetsV1(message, DevicePairingTargetLimitsV1.READY_MAX), SelfOwnedOctetsV1(signature, 64))
                val encoded = TargetWire.ready(signed, variant)
                try { native.forceNew(F.READY, encoded) } finally { encoded.fill(0) }
                ready = signed; validateReady(); updateReference(LocalDevicePairingTargetPhase.KEY_READY)
            } finally { message.fill(0); signature.fill(0) }
        } finally { point.fill(0) }
        requireReadable()
        preparedHere = true // Only actual successful prepareNew; existing-only never sets this.
    }

    fun openExisting() {
        check(phase == Phase.NEW); native.open(creating = false); phase = Phase.OPEN
        // Read the canonical original (or exact pending original DATA) before refusing a
        // pending/unknown write. No pending file is adopted, renamed, removed or repaired.
        val identityBytes = native.snapshot(F.IDENTITY)
        if (identityBytes == null) {
            val pending = native.pending(F.IDENTITY, DevicePairingTargetLimitsV1.IDENTITY_MAX)
            try { pending?.let { candidate ->
                try { identity = TargetWire.decodeIdentity(candidate, variant); updateReference(LocalDevicePairingTargetPhase.KEY_GENERATION_INTENT) }
                catch (_: Exception) { /* Incomplete canonical DATA cannot identify an original. */ }
            } } finally { pending?.fill(0) }
            known(false)
        }
        identity = TargetWire.decodeIdentity(checkNotNull(identityBytes), variant)
        updateReference(LocalDevicePairingTargetPhase.KEY_GENERATION_INTENT)
        credentialAllocation = native.snapshot(F.CREDENTIAL_ALLOCATION)?.let { NativePairedDeviceCredentialJournalWire.decodeRecord(it) }
        sdkAcquisition = native.snapshot(F.SDK_ACQUISITION)?.let { TargetSdkWireV2.decodeAcquisition(it, variant) }
        sdkAcquisition?.let { updateReference(LocalDevicePairingTargetPhase.SDK_ACQUISITION_INTENT) }
        if (sdkAcquisition == null) {
            val pending = native.pending(F.SDK_ACQUISITION, DevicePairingTargetLimitsV1.SDK_ACQUISITION_MAX)
            try { pending?.let { candidate ->
                try { sdkAcquisition = TargetSdkWireV2.decodeAcquisition(candidate, variant)
                    updateReference(LocalDevicePairingTargetPhase.SDK_ACQUISITION_INTENT) }
                catch (_: Exception) { /* Retained pending DATA is not adopted or repaired. */ }
            } } finally { pending?.fill(0) }
        }
        native.assertContinuity()
        validateIdentity()
        keyIntent = native.snapshot(F.KEY_INTENT)?.let { TargetWire.decodeKeyIntent(it, variant) }
        keyIntent?.let { validateKeyIntent(); updateReference(LocalDevicePairingTargetPhase.KEY_METADATA_SIGNING_INTENT) }
        ready = native.snapshot(F.READY)?.let { TargetWire.decodeReady(it, variant) }
        ready?.let { validateReady(); updateReference(LocalDevicePairingTargetPhase.KEY_READY) }
        sdkAcquisition?.let { validateSdkAcquisition(); updateReference(LocalDevicePairingTargetPhase.SDK_ACQUISITION_INTENT) }
        sdkSigningIntent = native.snapshot(F.SDK_SIGNING_INTENT)?.let { TargetSdkWireV2.decodeSigningIntent(it, variant) }
        sdkSigningIntent?.let { validateSdkSigningIntent(); updateReference(LocalDevicePairingTargetPhase.SDK_METADATA_SIGNING_INTENT) }
        sdkReady = native.snapshot(F.SDK_READY)?.let { TargetSdkWireV2.decodeReady(it, variant) }
        sdkReady?.let { validateSdkReady(); updateReference(LocalDevicePairingTargetPhase.SDK_READY) }
        known(sdkAcquisition != null || sdkSigningIntent == null && sdkReady == null)
        known(sdkSigningIntent != null || sdkReady == null)
        for (index in 0 until F.MAXIMUM_RESPONSE_CYCLES) {
            val allocation = if (index == 0) null else native.snapshot(allocationIntentName(index))
            val allocated = if (index == 0) null else native.snapshot(allocationReadyName(index))
            val intent = native.snapshot(responseIntentName(index)); val completed = native.snapshot(completeName(index))
            val masterImport = native.snapshot(approvedMasterImportIntentName(index))
            val masterEvidence = native.snapshot(approvedMasterEvidenceName(index))
            if (index > 0 && listOf(allocation, allocated, intent, completed, masterImport, masterEvidence).all { it == null }) continue
            known(index == 0 || index == slots.keys.last() + 1)
            if (index > 0) slots[index] = TargetResponseSlot()
            responseCycle = index
            if (index > 0) {
                slot().allocation = allocation?.let { TargetWire.decodeAllocation(it, variant) }
                updateReference(LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_SIGNING_INTENT)
                validateAllocationIntent()
                slot().allocationReady = allocated?.let { TargetWire.decodeAllocationReady(it, variant) }
                slot().allocationReady?.let { validateAllocationReady(); updateReference(LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY) }
                known(slot().allocationReady != null || intent == null && completed == null && masterImport == null && masterEvidence == null)
            }
            responseIntent = intent?.let { TargetWire.decodeResponseIntent(it, variant) }
            responseIntent?.let { validateResponseIntent(); updateReference(LocalDevicePairingTargetPhase.RESPONSE_SIGNING_INTENT) }
            complete = completed?.let { TargetWire.decodeComplete(it, variant) }
            complete?.let { validateComplete(); updateReference(LocalDevicePairingTargetPhase.RESPONSE_COMPLETED) }
            slot().approvedMasterImportIntent = masterImport?.let { TargetApprovedMasterWireV1.decodeImportIntent(it) }
            slot().approvedMasterImportIntent?.let { validateApprovedMasterImportIntent() }
            known(slot().approvedMasterImportIntent != null || masterEvidence == null)
            slot().approvedMasterEvidence = masterEvidence?.let { TargetApprovedMasterWireV1.decodeEvidence(it) }
            slot().approvedMasterEvidence?.let { validateApprovedMaster() }
        }
        if (sdkAcquisition != null && sdkReady == null) updateReference(if (sdkSigningIntent == null)
            LocalDevicePairingTargetPhase.SDK_ACQUISITION_INTENT else LocalDevicePairingTargetPhase.SDK_METADATA_SIGNING_INTENT)
        native.assertKeyNamespace(keyIds(), keyRequired = ready != null)
        requireReadable() // Partial original owners expose only DATA/review, not descriptor/sign.
        credentialAllocation?.let { requireCredentialAllocation(it) }
    }

    fun original(): LocalDevicePairingTargetReference { requireReadable(); return checkNotNull(lastKnown) }
    fun descriptor(): LocalDevicePairingTargetDescriptor {
        requireReadable(); validateReady()
        val bytes = TargetWire.decodeReadyMessage(checkNotNull(ready).message, variant).descriptor.copyBytes()
        return try { LocalDevicePairingTargetDescriptor.decode(bytes) } finally { bytes.fill(0) }
    }

    fun acquireSdkIdentity(appId: AppId, deviceName: String): LocalDevicePairingSdkIdentityV2 {
        requireReadable(); validateReady(); requireAllocationReady()
        try { LocalDevicePairingProfileV2.validateAcquisitionText(appId.value)
            LocalDevicePairingProfileV2.validateAcquisitionText(deviceName) }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        sdkAcquisition?.let { retained ->
            if (retained.appId != appId || retained.deviceName != deviceName) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            if (sdkReady == null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return recoverSdkIdentity() // Completed same original DATA, never another Kit/sign.
        }
        // A response for an old definition must first reach an explicit known-unsigned
        // terminal and a separately retained append allocation. Never extend its approval.
        if (responseIntent != null || complete != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        known(sdkSigningIntent == null && sdkReady == null && sdkOwner == null && !sdkOwnerRejected)
        val originalScope = checkNotNull(identity).scope
        if (setOf(originalScope.organizationId, originalScope.companyId, originalScope.branchId).size != 3)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        requireSdkBranchNamespaceForNewWork(appId, SelfOwnedInstallationFailureV1.INVALID_INPUT)
        requireAppendBytes(DevicePairingTargetLimitsV1.SDK_ACQUISITION_MAX.toLong() +
            DevicePairingTargetLimitsV1.SDK_SIGNING_INTENT_MAX + DevicePairingTargetLimitsV1.SDK_READY_MAX + 8192L)
        val acquisitionId = JvmP256Primitives.newId()
        known(acquisitionId !in retainedTargetIds(responseCycle))
        val nonce = JvmP256Primitives.randomBytes(32)
        val acquisition = try { TargetSdkAcquisition(acquisitionId, identityDigest(), readyDigest(), appId, deviceName,
            native.boot(), native.clock(), SelfOwnedOctetsV1(nonce, 32)) }
        finally { nonce.fill(0) }
        sdkAcquisition = acquisition; updateReference(LocalDevicePairingTargetPhase.SDK_ACQUISITION_INTENT)
        val acquisitionBytes = TargetSdkWireV2.acquisition(acquisition, variant)
        try { native.forceNew(F.SDK_ACQUISITION, acquisitionBytes) } finally { acquisitionBytes.fill(0) }
        validateSdkAcquisition()
        known(acquisition.osBoot.sameBoot(native.boot()) && native.clock() >= acquisition.observedElapsed)
        val owned = native.newSdkOwner()
        sdkOwner = owned // Actual partial owner retained BEFORE SDK storage/construction work.
        val sdk = try {
            owned.create(appId, deviceName)
            owned.capture(acquisitionId, checkNotNull(identity).preparationId, descriptorWithoutReentry())
        } catch (failure: Throwable) { closeRejectedSdkOwner(); throw failure }
        val descriptorBytes = sdk.copyWireBytes()
        val intent = try { TargetSdkSigningIntent(identityDigest(), readyDigest(), sha256(snapshotBytes(F.SDK_ACQUISITION)),
            native.boot(), native.clock(), SelfOwnedOctetsV1(descriptorBytes, LocalDevicePairingProfileV2.MAXIMUM_SDK_IDENTITY_BYTES)) }
        finally { descriptorBytes.fill(0) }
        val intentBytes = TargetSdkWireV2.signingIntent(intent, variant)
        try {
            val completionBound = snapshotBytes(F.IDENTITY).size.toLong() + snapshotBytes(F.READY).size.toLong() +
                snapshotBytes(F.SDK_ACQUISITION).size.toLong() + intentBytes.size.toLong() + 2048L
            if (completionBound > DevicePairingTargetLimitsV1.SDK_READY_MAX) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            requireAppendBytes(intentBytes.size.toLong() + completionBound)
            sdkSigningIntent = intent; updateReference(LocalDevicePairingTargetPhase.SDK_METADATA_SIGNING_INTENT)
            native.forceNew(F.SDK_SIGNING_INTENT, intentBytes)
        } finally { intentBytes.fill(0) }
        validateSdkSigningIntent(); requireCurrentSdkMatches()
        known(intent.osBoot.sameBoot(native.boot()) && native.clock() >= intent.observedElapsed)
        val message = TargetSdkWireV2.readyMessage(snapshotBytes(F.IDENTITY), snapshotBytes(F.READY),
            snapshotBytes(F.SDK_ACQUISITION), snapshotBytes(F.SDK_SIGNING_INTENT), variant)
        val signature = try { signChecked(message) } catch (failure: Throwable) { message.fill(0); throw failure }
        try {
            checkedKey(); validateSdkSigningIntent(); requireCurrentSdkMatches()
            val point = keyPoint()
            try { known(JvmP256Primitives.verify(point, message, signature)) } finally { point.fill(0) }
            val signed = TargetSdkReady(SelfOwnedOctetsV1(message, DevicePairingTargetLimitsV1.SDK_READY_MAX - 1024), SelfOwnedOctetsV1(signature, 64))
            val encoded = TargetSdkWireV2.ready(signed, variant)
            try { native.forceNew(F.SDK_READY, encoded) } finally { encoded.fill(0) }
            sdkReady = signed; validateSdkReady(); requireCurrentSdkMatches()
            updateReference(LocalDevicePairingTargetPhase.SDK_READY)
            return sdkIdentityWithoutReentry()
        } finally { message.fill(0); signature.fill(0) }
    }
    fun recoverSdkIdentity(): LocalDevicePairingSdkIdentityV2 = recoverSdkOriginal().identity
    fun recoverSdkOriginal(): LocalDevicePairingSdkOriginal {
        requireReadable(); validateSdkReady()
        return LocalDevicePairingSdkOriginal(sdkIdentityWithoutReentry(), checkNotNull(sdkAcquisition).deviceName)
    }

    fun respond(challenge: LocalDevicePairingChallenge): LocalDevicePairingPossession {
        requireReadable(); validateReady(); requireAllocationReady(); requireOwnDescriptor(challenge)
        requireChallengeRetirementLink(challenge)
        val challengeBytes = challenge.copyWireBytes()
        try {
            complete?.let {
                val intent = checkNotNull(responseIntent)
                if (!intent.challenge.same(challengeBytes)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
                validateComplete()
                return completedPossession() // Exact original disclosure; no new capture/sign/ID.
            }
            if (responseIntent != null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            requireSdkForNewResponse(challenge)
            val id = allocatedResponseId()
            known(id !in listOf(challenge.originalActionId, challenge.requested.request.requestId,
                challenge.requested.admission.deviceId, challenge.requested.target.keyId) +
                challenge.requested.sdkIdentity?.let { listOf(it.originalAcquisitionId, it.originalPreparationId) }.orEmpty() &&
                id !in challenge.body.origin.reservedOriginalIds())
            val intent = TargetResponseIntent(id, responseCycle, sha256(allocationBytes()), readyDigest(),
                native.boot(), native.clock(), allocatedNonce(),
                SelfOwnedOctetsV1(challengeBytes, LocalDevicePairingProfileV1.MAXIMUM_CHALLENGE_BYTES))
            val intentBytes = TargetWire.responseIntent(intent, variant)
            try {
                val completionBound = snapshotBytes(F.READY).size.toLong() + allocationBytes().size.toLong() +
                    intentBytes.size.toLong() + LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES + 8192L
                requireAppendBytes(intentBytes.size.toLong() + completionBound)
                if (completionBound > DevicePairingTargetLimitsV1.COMPLETE_MAX) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
                responseIntent = intent; updateReference(LocalDevicePairingTargetPhase.RESPONSE_SIGNING_INTENT)
                native.forceNew(responseIntentName(), intentBytes)
            } finally { intentBytes.fill(0) }
            validateResponseIntent()
            // The target's own boot/elapsed observations govern only its local key/work life.
            known(intent.osBoot.sameBoot(native.boot()) && native.clock() >= intent.observedElapsed)
            requireSdkForNewResponse(challenge)
            val message = NativeDevicePairingWireV1.possessionMessage(challenge)
            val signature = try { signChecked(message) } catch (failure: Throwable) { message.fill(0); throw failure }
            try {
                checkedKey(); validateResponseIntent(); requireSdkForNewResponse(challenge)
                val point = keyPoint()
                try { known(JvmP256Primitives.verify(point, message, signature)) } finally { point.fill(0) }
                val proof = LocalDevicePairingPossession.capture(challenge, signature)
                val proofBytes = proof.copyWireBytes()
                val completionMessage = try { TargetWire.completeMessage(snapshotBytes(F.READY), allocationBytes(),
                    snapshotBytes(responseIntentName()), proofBytes, variant) }
                finally { proofBytes.fill(0) }
                val snapshotSignature = try { requireSdkForNewResponse(challenge); signChecked(completionMessage) }
                catch (failure: Throwable) { completionMessage.fill(0); throw failure }
                try {
                    checkedKey(); validateResponseIntent(); requireSdkForNewResponse(challenge)
                    val signed = TargetComplete(SelfOwnedOctetsV1(completionMessage, DevicePairingTargetLimitsV1.COMPLETE_MAX - 1024),
                        SelfOwnedOctetsV1(snapshotSignature, 64))
                    val encoded = TargetWire.complete(signed, variant)
                    try { native.forceNew(completeName(), encoded) } finally { encoded.fill(0) }
                    complete = signed; validateComplete(); updateReference(LocalDevicePairingTargetPhase.RESPONSE_COMPLETED)
                    return completedPossession()
                } finally { completionMessage.fill(0); snapshotSignature.fill(0) }
            } finally { message.fill(0); signature.fill(0) }
        } finally { challengeBytes.fill(0) }
    }
    fun recoverPossession(id: DomainId): LocalDevicePairingPossession {
        requireReadable()
        val index = slots.keys.singleOrNull { cycle -> withResponseCycle(cycle) {
            complete != null && completedPossession().originalActionId == id
        } } ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return withResponseCycle(index) { validateComplete(); completedPossession() }
    }
    private fun completedPossession(): LocalDevicePairingPossession {
        val fields = TargetWire.decodeCompleteMessage(checkNotNull(complete).message, variant)
        val bytes = fields.possession.copyBytes()
        return try { LocalDevicePairingPossession.decode(bytes) } finally { bytes.fill(0) }
    }

    fun importApprovedMaster(certificate: ByteArray, rootAnnouncement: ByteArray): LocalDevicePairingApprovedMaster {
        requireReadable()
        val record = try { NativeDevicePairingWireV1.decodeApprovedCertificate(certificate) }
        catch (_: Exception) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        if (record.body.requested().masterSdkOriginal == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireApprovedMasterProfile(record)
        val index = responseCycleForAction(record.body.originalActionId)
        return withResponseCycle(index) {
            slot().approvedMasterEvidence?.let { evidence ->
                val original = validateApprovedMaster()
                val retainedCertificate = NativeDevicePairingWireV1.certificate(approvedMasterRecord(evidence))
                try {
                    if (!retainedCertificate.contentEquals(certificate) || !evidence.rootAnnouncement.same(rootAnnouncement))
                        selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
                } finally { retainedCertificate.fill(0) }
                return@withResponseCycle original // Existing-only; no capture, force, signature or replacement.
            }
            if (slot().approvedMasterImportIntent != null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            if (slots.keys.any { it > index }) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            validateComplete(); validateSdkReady()
            val challenge = retainedChallenge(); val possession = completedPossession()
            val check = try { NativeDevicePairingApprovedMasterPolicyV1.prepare(record, rootAnnouncement, challenge, possession) }
            catch (_: Exception) { selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
            verifyApprovedMasterSignatures(check, SelfOwnedInstallationFailureV1.CONFLICT)
            checkedKey() // Actual original key/storage continuity, never a proof re-signature.
            val importIntent = TargetApprovedMasterImportIntent(challenge.originalActionId, responseCycle,
                allocatedResponseId(), sha256(snapshotBytes(responseIntentName())), sha256(snapshotBytes(completeName())),
                check.identity.approvalCertificateDigest, check.identity.rootDocumentDigest)
            val intentBytes = TargetApprovedMasterWireV1.importIntent(importIntent)
            try {
                val evidence = TargetApprovedMasterEvidence(sha256(intentBytes), checkNotNull(record.consent),
                    checkNotNull(record.approvedHead), record.rootEndorsement,
                    SelfOwnedOctetsV1(rootAnnouncement, SelfOwnedInstallationProfileV1.MAXIMUM_AUTHORITY_DOCUMENT_BYTES))
                val evidenceBytes = TargetApprovedMasterWireV1.evidence(evidence)
                try {
                    requireAppendBytes(intentBytes.size.toLong() + evidenceBytes.size.toLong())
                    slot().approvedMasterImportIntent = importIntent
                    native.forceNew(approvedMasterImportIntentName(), intentBytes)
                    validateApprovedMasterImportIntent()
                    slot().approvedMasterEvidence = evidence
                    native.forceNew(approvedMasterEvidenceName(), evidenceBytes)
                } finally { evidenceBytes.fill(0) }
            } finally { intentBytes.fill(0) }
            validateApprovedMaster()
        }
    }
    fun recoverApprovedMaster(originalPairingActionId: DomainId): LocalDevicePairingApprovedMaster {
        requireReadable()
        return withResponseCycle(responseCycleForAction(originalPairingActionId)) { validateApprovedMaster() }
    }

    /** Shared credential-journal allocation; the platform wrapper wraps the record in its custody type. */
    fun allocateCredentialJournal(originalPairingActionId: DomainId, action: LocalPairedDeviceCredentialJournalAction):
        Triple<LocalDevicePairingApprovedMaster, LocalDevicePairingSdkOriginal, NativePairedDeviceCredentialJournalRecord> {
        requireReadable()
        val approved = recoverApprovedMaster(originalPairingActionId)
        val sdk = recoverSdkOriginal()
        val original = checkNotNull(identity)
        known(approved.scope == original.scope && approved.targetDeviceId == original.deviceId &&
            approved.targetKeyId == original.keyId && sdk.identity.originalPreparationId == original.preparationId &&
            sdk.identity.descriptorDigest == approved.targetSdkIdentity.descriptorDigest)
        checkedKey() // Genuine original OS-isolated target custody, no stronger claim.
        val expected = NativePairedDeviceCredentialJournalWire.allocation(approved, sdk, identityDigest(), readyDigest(),
            SelfOwnedKeyProtectionV1.OS_ISOLATED)
        known(expected.journalId !in retainedTargetIds(responseCycle) && expected.keyId !in retainedTargetIds(responseCycle))
        if (action == LocalPairedDeviceCredentialJournalAction.ENROLL) {
            // Absence is NOT pristine proof: a reopened target cannot infer a first credential history.
            if (!preparedHere) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            if (credentialAllocation != null || native.snapshot(F.CREDENTIAL_ALLOCATION) != null)
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val bytes = NativePairedDeviceCredentialJournalWire.record(expected)
            try {
                requireAppendBytes(bytes.size.toLong())
                credentialAllocation = expected // Consume before immutable native effect.
                native.forceNew(F.CREDENTIAL_ALLOCATION, bytes)
            } finally { bytes.fill(0) }
        } else {
            if (credentialAllocation == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            known(credentialAllocation == expected)
        }
        requireCredentialAllocation(expected)
        return Triple(approved, sdk, expected)
    }
    fun requireCredentialAllocation(expected: NativePairedDeviceCredentialJournalRecord) {
        requireReadable()
        val encoded = NativePairedDeviceCredentialJournalWire.record(expected)
        try { known(credentialAllocation == expected && native.snapshot(F.CREDENTIAL_ALLOCATION)?.contentEquals(encoded) == true) }
        finally { encoded.fill(0) }
        val approved = recoverApprovedMaster(expected.originalPairingActionId)
        val sdk = recoverSdkOriginal()
        known(NativePairedDeviceCredentialJournalWire.allocation(approved, sdk, identityDigest(), readyDigest(),
            SelfOwnedKeyProtectionV1.OS_ISOLATED) == expected)
        checkedKey()
    }

    private fun responseCycleForAction(id: DomainId): Int = slots.keys.singleOrNull { index -> withResponseCycle(index) {
        complete != null && retainedChallenge().originalActionId == id
    } } ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    private fun retainedChallenge(): LocalDevicePairingChallenge {
        val bytes = checkNotNull(responseIntent).challenge.copyBytes()
        return try { LocalDevicePairingChallenge.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun validateApprovedMasterImportIntent() {
        validateComplete(); validateSdkReady()
        val intent = slot().approvedMasterImportIntent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val encoded = TargetApprovedMasterWireV1.importIntent(intent)
        try { known(native.snapshot(approvedMasterImportIntentName())?.contentEquals(encoded) == true) }
        finally { encoded.fill(0) }
        val challenge = retainedChallenge()
        known(challenge.requested.masterSdkOriginal != null && intent.originalPairingActionId == challenge.originalActionId &&
            intent.allocationIndex == responseCycle && intent.responseId == allocatedResponseId() &&
            intent.responseIntentDigest == sha256(snapshotBytes(responseIntentName())) &&
            intent.completeDigest == sha256(snapshotBytes(completeName())) &&
            intent.rootAnnouncementDigest == challenge.body.origin.rootDocumentDigest)
    }
    private fun approvedMasterRecord(evidence: TargetApprovedMasterEvidence): NativeDevicePairingRecordV1 {
        val challenge = retainedChallenge(); val possession = completedPossession().copyWireBytes()
        return try { NativeDevicePairingRecordV1(challenge.body, LocalDevicePairingPhase.APPROVED,
            SelfOwnedOctetsV1(possession, LocalDevicePairingProfileV1.MAXIMUM_POSSESSION_BYTES),
            evidence.consent, evidence.approvedHead, evidence.rootEndorsement) }
        finally { possession.fill(0) }
    }
    /** The target imports only a Master original of its own fixed composition profile. */
    private fun requireApprovedMasterProfile(record: NativeDevicePairingRecordV1) {
        val master = record.body.requested().masterSdkOriginal
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (master.compositionProfile != native.sdkProfile) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
    }
    private fun validateApprovedMaster(): LocalDevicePairingApprovedMaster {
        if (slot().approvedMasterImportIntent == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        validateApprovedMasterImportIntent()
        val evidence = slot().approvedMasterEvidence ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val encoded = TargetApprovedMasterWireV1.evidence(evidence)
        try { known(native.snapshot(approvedMasterEvidenceName())?.contentEquals(encoded) == true) }
        finally { encoded.fill(0) }
        known(evidence.importIntentDigest == sha256(snapshotBytes(approvedMasterImportIntentName())))
        val record = approvedMasterRecord(evidence)
        requireApprovedMasterProfile(record)
        val rootAnnouncement = evidence.rootAnnouncement.copyBytes()
        val check = try { NativeDevicePairingApprovedMasterPolicyV1.prepare(record, rootAnnouncement,
            retainedChallenge(), completedPossession()) } finally { rootAnnouncement.fill(0) }
        val intent = checkNotNull(slot().approvedMasterImportIntent)
        known(check.identity.approvalCertificateDigest == intent.approvalCertificateDigest &&
            check.identity.rootDocumentDigest == intent.rootAnnouncementDigest)
        verifyApprovedMasterSignatures(check, SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        checkedKey()
        return LocalDevicePairingApprovedMaster(check.identity)
    }
    private fun verifyApprovedMasterSignatures(value: NativeDevicePairingApprovedMasterCheckV1,
        mismatch: SelfOwnedInstallationFailureV1) {
        val copies = ArrayList<ByteArray>(5)
        try {
            val point = value.rootPoint.copyBytes().also { copies.add(it) }
            val rootMessage = value.rootMessage.copyBytes().also { copies.add(it) }
            val rootSignature = value.rootSignature.copyBytes().also { copies.add(it) }
            val endorsementMessage = value.endorsementMessage.copyBytes().also { copies.add(it) }
            val endorsementSignature = value.endorsementSignature.copyBytes().also { copies.add(it) }
            if (!JvmP256Primitives.verify(point, rootMessage, rootSignature) ||
                !JvmP256Primitives.verify(point, endorsementMessage, endorsementSignature)) selfOwnedFail(mismatch)
        } finally { copies.forEach { it.fill(0) } }
    }

    /** Explicit append after exact completed terminal DATA. Every old snapshot is retained. */
    fun beginAfterKnownUnsigned(proof: LocalDevicePairingUnsignedRetirement): LocalDevicePairingTargetReference {
        requireReadable(); validateReady(); requireAllocationReady()
        requireNoApprovedMasterImportForRetirement(responseCycle)
        sdkAcquisition?.let {
            requireSdkBranchNamespaceForNewWork(it.appId)
            ensureOriginalSdkOwner()
        }
        val priorIndex = responseCycle
        if (priorIndex >= F.MAXIMUM_RESPONSE_CYCLES - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        validateRetirementForPrior(proof, priorIndex)
        val bytes = proof.copyWireBytes()
        try {
            val intentBound = bytes.size.toLong() + 4096L
            val readyBound = snapshotBytes(F.READY).size.toLong() + intentBound + 4096L
            requireAppendBytes(intentBound + readyBound + 8192L)
            if (intentBound > DevicePairingTargetLimitsV1.ALLOCATION_INTENT_MAX || readyBound > DevicePairingTargetLimitsV1.ALLOCATION_READY_MAX)
                selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
            val forbidden = retainedTargetIds(priorIndex) + proof.record.nativeIds() + proof.record.body.origin.reservedOriginalIds()
            val id = JvmP256Primitives.newId()
            known(id !in forbidden)
            val nonce = JvmP256Primitives.randomBytes(32)
            val allocation = try { TargetResponseAllocation(priorIndex + 1, id, SelfOwnedOctetsV1(nonce, 32), identityDigest(),
                sha256(allocationBytes(priorIndex)), native.snapshot(responseIntentName(priorIndex))?.let { sha256(it) },
                native.snapshot(completeName(priorIndex))?.let { sha256(it) },
                SelfOwnedOctetsV1(bytes, LocalDevicePairingProfileV1.MAXIMUM_RETIREMENT_BYTES), native.boot(), native.clock()) }
            finally { nonce.fill(0) }
            slots[allocation.index] = TargetResponseSlot(allocation = allocation); responseCycle = allocation.index
            updateReference(LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_SIGNING_INTENT)
            val encoded = TargetWire.allocation(allocation, variant)
            try { native.forceNew(allocationIntentName(), encoded) } finally { encoded.fill(0) }
            validateAllocationIntent()
            val message = TargetWire.allocationReadyMessage(snapshotBytes(F.READY), snapshotBytes(allocationIntentName()), variant)
            val signature = try {
                if (sdkAcquisition != null) requireCurrentSdkMatches()
                signChecked(message)
            } catch (failure: Throwable) { message.fill(0); throw failure }
            try {
                checkedKey(); validateAllocationIntent()
                if (sdkAcquisition != null) requireCurrentSdkMatches()
                val point = keyPoint()
                try { known(JvmP256Primitives.verify(point, message, signature)) } finally { point.fill(0) }
                val ready = TargetAllocationReady(SelfOwnedOctetsV1(message, DevicePairingTargetLimitsV1.ALLOCATION_READY_MAX - 1024),
                    SelfOwnedOctetsV1(signature, 64))
                val completed = TargetWire.allocationReady(ready, variant)
                try { native.forceNew(allocationReadyName(), completed) } finally { completed.fill(0) }
                slot().allocationReady = ready; validateAllocationReady()
                updateReference(LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY)
                return checkNotNull(lastKnown)
            } finally { message.fill(0); signature.fill(0) }
        } finally { bytes.fill(0) }
    }
    /** Read only the CURRENT completed allocation for these exact terminal bytes. */
    fun recoverAfterKnownUnsigned(proof: LocalDevicePairingUnsignedRetirement): LocalDevicePairingTargetReference {
        requireReadable(); validateReady()
        if (responseCycle == 0) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val allocation = slot().allocation ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val bytes = proof.copyWireBytes()
        try { if (!allocation.retirement.same(bytes)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { bytes.fill(0) }
        validateAllocationIntent(); validateAllocationReady()
        if (sdkAcquisition != null) validateSdkReady()
        if (responseIntent != null && complete == null) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (complete != null) validateComplete()
        updateReference(if (complete == null) LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_READY
            else LocalDevicePairingTargetPhase.RESPONSE_COMPLETED)
        return original()
    }
    private fun requireAppendBytes(additional: Long) {
        if (additional < 0 || native.retainedBytes() > DevicePairingTargetLimitsV1.TOTAL_SNAPSHOT_MAX.toLong() - additional)
            selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
    }
    private fun <T> withResponseCycle(index: Int, body: () -> T): T {
        val previous = responseCycle
        return try { responseCycle = index; body() } finally { responseCycle = previous }
    }
    private fun allocationProof(index: Int = responseCycle): LocalDevicePairingUnsignedRetirement? {
        val retained = slots[index]?.allocation?.retirement ?: return null
        val bytes = retained.copyBytes()
        return try { LocalDevicePairingUnsignedRetirement.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun retainedTargetIds(upTo: Int, includeSdk: Boolean = true): Set<DomainId> {
        val ids = checkNotNull(identity).let { mutableSetOf(it.preparationId, it.deviceId, it.keyId, it.responseId,
            it.scope.organizationId, it.scope.companyId, it.scope.branchId) }
        if (includeSdk) sdkAcquisition?.let { ids.add(it.acquisitionId) }
        for (index in slots.keys.filter { it <= upTo }) {
            slots[index]?.allocation?.responseId?.let { ids.add(it) }
            allocationProof(index)?.let {
                if (includeSdk) ids.addAll(it.record.nativeIds())
                else { ids.addAll(it.record.ownedIds()); ids.add(it.record.body.requested().admission.deviceId)
                    ids.add(it.record.body.requested().target.keyId) }
                ids.addAll(it.record.body.origin.reservedOriginalIds())
            }
            slots[index]?.responseIntent?.challenge?.let { retained ->
                val bytes = retained.copyBytes()
                val challenge = try { LocalDevicePairingChallenge.decode(bytes) } finally { bytes.fill(0) }
                ids.add(challenge.originalActionId); ids.add(challenge.requested.request.requestId)
                ids.addAll(challenge.body.origin.reservedOriginalIds())
            }
        }
        return ids
    }
    private fun requireAllocationReady() { if (responseCycle > 0) validateAllocationReady() }
    private fun requireChallengeRetirementLink(challenge: LocalDevicePairingChallenge) {
        val proof = allocationProof()
        known(challenge.body.previousUnsignedRetirementDigest == proof?.dispositionDigest)
        if (proof != null) requireSameRetainedOrigin(proof.record.body.origin, challenge.body.origin)
    }
    private fun requireSameRetainedOrigin(before: NativeDevicePairingOriginV1, after: NativeDevicePairingOriginV1) {
        known(before == after.copy(preparationParent = before.preparationParent,
            preparationAnchorDigest = before.preparationAnchorDigest))
    }
    private fun verifyTerminalData(proof: LocalDevicePairingUnsignedRetirement) {
        val origin = proof.record.body.origin
        val rootBytes = proof.value.rootDocument.copyBytes()
        try {
            known(sha256(rootBytes) == origin.rootDocumentDigest)
            val root = SelfOwnedInstallationWireV1.decodeSigned(rootBytes) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            known(root.role == SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT)
            val canonical = root.copyWireBytes()
            try { known(canonical.contentEquals(rootBytes)) } finally { canonical.fill(0) }
            val bodyBytes = root.body()
            val body = try { SelfOwnedInstallationWireV1.decodeRoot(bodyBytes) } finally { bodyBytes.fill(0) }
            known(body.rootId == origin.rootId && body.rootKeyId == origin.rootKeyId &&
                body.organizationId == origin.coordinates.scope.organizationId && body.companyId == origin.coordinates.scope.companyId &&
                body.bootstrapActionId == origin.foundingActionId && JvmP256Primitives.rootFingerprint(body) == origin.rootFingerprint)
            val point = body.rootPublicKey.copyBytes(); val unsigned = root.body()
            val message = try { SelfOwnedInstallationWireV1.signingMessage(root.role, unsigned) } finally { unsigned.fill(0) }
            val signature = root.signature(); val terminal = NativeDevicePairingWireV1.retirementMessage(proof.record)
            val terminalSignature = proof.record.retirementEndorsement.copyBytes()
            try { known(JvmP256Primitives.verify(point, message, signature) &&
                JvmP256Primitives.verify(point, terminal, terminalSignature)) }
            finally { point.fill(0); message.fill(0); signature.fill(0); terminal.fill(0); terminalSignature.fill(0) }
        } finally { rootBytes.fill(0) }
    }
    private fun validateRetirementForPrior(proof: LocalDevicePairingUnsignedRetirement, index: Int) {
        requireNoApprovedMasterImportForRetirement(index)
        verifyTerminalData(proof); requireOwnDescriptor(proof.originalChallenge)
        val previous = checkNotNull(slots[index])
        if (index > 0) withResponseCycle(index) { requireAllocationReady() }
        if (previous.responseIntent != null && previous.complete == null)
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val parentProof = allocationProof(index)
        known(proof.record.body.previousUnsignedRetirementDigest == parentProof?.dispositionDigest)
        parentProof?.let { requireSameRetainedOrigin(it.record.body.origin, proof.record.body.origin) }
        val intent = previous.responseIntent
        if (intent == null) known(proof.record.possession == null)
        else withResponseCycle(index) {
            validateComplete()
            val challenge = proof.originalChallenge.copyWireBytes()
            try { known(intent.challenge.same(challenge)) } finally { challenge.fill(0) }
            proof.record.possession?.let { retained ->
                val original = completedPossession().copyWireBytes()
                try { known(retained.same(original)) } finally { original.fill(0) }
            }
        }
    }
    private fun requireNoApprovedMasterImportForRetirement(index: Int) {
        val retained = checkNotNull(slots[index])
        if (retained.approvedMasterImportIntent != null) selfOwnedFail(if (retained.approvedMasterEvidence == null)
            SelfOwnedInstallationFailureV1.STATE_UNCERTAIN else SelfOwnedInstallationFailureV1.CONFLICT)
    }
    private fun validateAllocationIntent() {
        val allocation = slot().allocation ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val encoded = TargetWire.allocation(allocation, variant)
        try { known(native.snapshot(allocationIntentName())?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        val previous = responseCycle - 1
        known(allocation.index == responseCycle && allocation.identityDigest == identityDigest() && slots.containsKey(previous) &&
            allocation.parentAllocationDigest == sha256(allocationBytes(previous)) &&
            allocation.previousIntentDigest == native.snapshot(responseIntentName(previous))?.let { sha256(it) } &&
            allocation.previousCompleteDigest == native.snapshot(completeName(previous))?.let { sha256(it) } &&
            allocation.osBoot >= checkNotNull(identity).osBoot && allocation.osBoot <= native.boot())
        val proof = checkNotNull(allocationProof())
        validateRetirementForPrior(proof, previous)
        val oldIds = retainedTargetIds(previous)
        known(allocation.responseId !in oldIds && allocation.responseId !in proof.record.nativeIds() &&
            allocation.responseId !in proof.record.body.origin.reservedOriginalIds())
    }
    private fun validateAllocationReady() {
        val signed = slot().allocationReady ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (responseCycle in verifiedAllocationCycles) return
        validateAllocationIntent()
        val fields = TargetWire.decodeAllocationReadyMessage(signed.message, variant)
        val encoded = TargetWire.allocationReady(signed, variant)
        try { known(native.snapshot(allocationReadyName())?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(fields.ready.same(snapshotBytes(F.READY)) && fields.allocation.same(snapshotBytes(allocationIntentName())))
        val point = keyPoint(); val message = signed.message.copyBytes(); val signature = signed.signature.copyBytes()
        try { known(JvmP256Primitives.verify(point, message, signature)) }
        finally { point.fill(0); message.fill(0); signature.fill(0) }
        verifiedAllocationCycles.add(responseCycle)
    }

    fun requireReadable() {
        known(phase == Phase.OPEN)
        native.assertContinuity(); validateIdentity(); native.assertKeyNamespace(keyIds(), keyRequired = ready != null)
        known(native.snapshot(F.KEY_INTENT) == null || keyIntent != null)
        known(native.snapshot(F.READY) == null || ready != null)
        known(native.snapshot(F.SDK_ACQUISITION) == null || sdkAcquisition != null)
        known(native.snapshot(F.SDK_SIGNING_INTENT) == null || sdkSigningIntent != null)
        known(native.snapshot(F.SDK_READY) == null || sdkReady != null)
        known((native.snapshot(F.CREDENTIAL_ALLOCATION) != null) == (credentialAllocation != null))
        credentialAllocation?.let { record ->
            val encoded = NativePairedDeviceCredentialJournalWire.record(record)
            try { known(native.snapshot(F.CREDENTIAL_ALLOCATION)?.contentEquals(encoded) == true) }
            finally { encoded.fill(0) }
        }
        known(native.snapshot(responseIntentName()) == null || responseIntent != null)
        known(native.snapshot(completeName()) == null || complete != null)
        for ((index, response) in slots) {
            known((native.snapshot(approvedMasterImportIntentName(index)) != null) == (response.approvedMasterImportIntent != null))
            known((native.snapshot(approvedMasterEvidenceName(index)) != null) == (response.approvedMasterEvidence != null))
            known(response.approvedMasterImportIntent == null || response.responseIntent != null && response.complete != null)
            known(response.approvedMasterEvidence == null || response.approvedMasterImportIntent != null)
        }
        if (responseCycle > 0) {
            known(native.snapshot(allocationIntentName()) == null || slot().allocation != null)
            known(native.snapshot(allocationReadyName()) == null || slot().allocationReady != null)
        }
        if (ready != null) checkedKey()
        if (sdkAcquisition != null) validateSdkAcquisition()
        if (sdkSigningIntent != null) validateSdkSigningIntent()
        if (sdkReady != null) validateSdkReady()
    }
    private fun validateIdentity() {
        val original = identity ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val bytes = TargetWire.identity(original, variant)
        try { known(native.snapshot(F.IDENTITY)?.contentEquals(bytes) == true) } finally { bytes.fill(0) }
        known(native.originMatches(original.origin) && original.osBoot <= native.boot())
    }
    private fun validateKeyIntent() {
        val intent = keyIntent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val original = checkNotNull(identity)
        val encoded = TargetWire.keyIntent(intent, variant)
        try { known(native.snapshot(F.KEY_INTENT)?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(intent.identityDigest == identityDigest() && intent.osBoot.sameBoot(original.osBoot) &&
            intent.observedElapsed >= original.observedElapsed)
        val point = intent.point.copyBytes()
        try { JvmP256Primitives.publicKey(point) } finally { point.fill(0) }
    }
    private fun validateReady() {
        validateIdentity(); validateKeyIntent()
        val signed = ready ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val fields = TargetWire.decodeReadyMessage(signed.message, variant)
        val encoded = TargetWire.ready(signed, variant)
        try { known(native.snapshot(F.READY)?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(fields.identity.same(snapshotBytes(F.IDENTITY)) && fields.keyIntent.same(snapshotBytes(F.KEY_INTENT)))
        val descriptorBytes = fields.descriptor.copyBytes()
        val descriptor = try { LocalDevicePairingTargetDescriptor.decode(descriptorBytes) } finally { descriptorBytes.fill(0) }
        val original = checkNotNull(identity); val point = checkNotNull(keyIntent).point.copyBytes()
        try {
            val actual = descriptor.copyWireBytes(); val wanted = expectedDescriptor(original, point).copyWireBytes()
            try { known(actual.contentEquals(wanted)) } finally { actual.fill(0); wanted.fill(0) }
            checkedKey()
            val message = signed.message.copyBytes(); val signature = signed.signature.copyBytes()
            try { known(JvmP256Primitives.verify(point, message, signature)) } finally { message.fill(0); signature.fill(0) }
        } finally { point.fill(0) }
    }
    private fun validateSdkAcquisition() {
        validateReady()
        val acquisition = sdkAcquisition ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val encoded = TargetSdkWireV2.acquisition(acquisition, variant)
        try { known(native.snapshot(F.SDK_ACQUISITION)?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        val original = checkNotNull(identity)
        known(acquisition.identityDigest == identityDigest() && acquisition.readyDigest == readyDigest() &&
            acquisition.appId == native.sdkProfile.appId(original.scope) &&
            acquisition.osBoot >= original.osBoot && acquisition.osBoot <= native.boot() &&
            (!acquisition.osBoot.sameBoot(original.osBoot) || acquisition.observedElapsed >= original.observedElapsed) &&
            acquisition.acquisitionId !in retainedTargetIds(responseCycle, includeSdk = false))
    }
    private fun validateSdkSigningIntent() {
        validateSdkAcquisition()
        val intent = sdkSigningIntent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val encoded = TargetSdkWireV2.signingIntent(intent, variant)
        try { known(native.snapshot(F.SDK_SIGNING_INTENT)?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        val acquisition = checkNotNull(sdkAcquisition); val original = checkNotNull(identity)
        known(intent.identityDigest == identityDigest() && intent.readyDigest == readyDigest() &&
            intent.acquisitionDigest == sha256(snapshotBytes(F.SDK_ACQUISITION)) && intent.osBoot.sameBoot(acquisition.osBoot) &&
            intent.observedElapsed >= acquisition.observedElapsed && intent.osBoot <= native.boot())
        val sdk = sdkIdentityWithoutReentry(); val expected = descriptorWithoutReentry()
        val actualBytes = sdk.target.copyWireBytes(); val expectedBytes = expected.copyWireBytes()
        try { known(actualBytes.contentEquals(expectedBytes)) } finally { actualBytes.fill(0); expectedBytes.fill(0) }
        known(sdk.originalAcquisitionId == acquisition.acquisitionId && sdk.originalPreparationId == original.preparationId &&
            native.sdkApplicationMatches(sdk, original.origin) && sdk.appId == acquisition.appId)
    }
    private fun validateSdkReady() {
        if (sdkAcquisition == null) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        validateSdkSigningIntent()
        val signed = sdkReady ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val fields = TargetSdkWireV2.decodeReadyMessage(signed.message, variant)
        val encoded = TargetSdkWireV2.ready(signed, variant)
        try { known(native.snapshot(F.SDK_READY)?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(fields.identity.same(snapshotBytes(F.IDENTITY)) && fields.ready.same(snapshotBytes(F.READY)) &&
            fields.acquisition.same(snapshotBytes(F.SDK_ACQUISITION)) && fields.intent.same(snapshotBytes(F.SDK_SIGNING_INTENT)))
        checkedKey()
        val point = keyPoint(); val message = signed.message.copyBytes(); val signature = signed.signature.copyBytes()
        try { known(JvmP256Primitives.verify(point, message, signature)) }
        finally { point.fill(0); message.fill(0); signature.fill(0) }
    }
    private fun sdkIdentityWithoutReentry(): LocalDevicePairingSdkIdentityV2 {
        val bytes = checkNotNull(sdkSigningIntent).descriptor.copyBytes()
        return try { LocalDevicePairingSdkIdentityV2.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun ensureOriginalSdkOwner() {
        validateSdkReady()
        if (sdkOwnerRejected) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        if (sdkOwner == null) {
            val acquisition = checkNotNull(sdkAcquisition)
            val owned = native.newSdkOwner()
            sdkOwner = owned // Exact reconstructed owner retained BEFORE create or comparison.
            try { owned.create(acquisition.appId, acquisition.deviceName) }
            catch (failure: Throwable) { closeRejectedSdkOwner(); throw failure }
        }
        requireCurrentSdkMatches()
    }
    private fun requireCurrentSdkMatches() {
        if (sdkOwnerRejected) selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        val owned = sdkOwner ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        try { owned.requireMatches(sdkIdentityWithoutReentry(), checkNotNull(sdkAcquisition).deviceName) }
        catch (failure: Throwable) { closeRejectedSdkOwner(); throw failure }
    }
    private fun closeRejectedSdkOwner() {
        sdkOwnerRejected = true
        sdkOwner?.let { it.close(); sdkOwner = null } // Clear only after actual successful stop.
    }
    private fun requireSdkForNewResponse(challenge: LocalDevicePairingChallenge) {
        val offered = challenge.requested.sdkIdentity
        val master = challenge.requested.masterSdkOriginal
        if (master != null && master.compositionProfile != native.sdkProfile) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        if (offered == null) {
            if (sdkAcquisition != null) selfOwnedFail(if (sdkReady == null)
                SelfOwnedInstallationFailureV1.STATE_UNCERTAIN else SelfOwnedInstallationFailureV1.CONFLICT)
        } else {
            requireOwnSdkDescriptorData(challenge)
            requireSdkBranchNamespaceForNewWork(offered.appId)
            ensureOriginalSdkOwner()
        }
    }
    private fun requireSdkBranchNamespaceForNewWork(appId: AppId,
        mismatch: SelfOwnedInstallationFailureV1 = SelfOwnedInstallationFailureV1.CONFLICT) {
        if (appId != native.sdkProfile.appId(checkNotNull(identity).scope)) selfOwnedFail(mismatch)
    }
    private fun requireOwnSdkDescriptorData(challenge: LocalDevicePairingChallenge) {
        val offered = challenge.requested.sdkIdentity ?: return
        validateSdkReady()
        val actual = offered.copyWireBytes(); val retained = sdkIdentityWithoutReentry().copyWireBytes()
        try { if (!actual.contentEquals(retained)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { actual.fill(0); retained.fill(0) }
    }
    private fun validateResponseIntent() {
        validateReady()
        val intent = responseIntent ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val encoded = TargetWire.responseIntent(intent, variant)
        try { known(native.snapshot(responseIntentName())?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(intent.readyDigest == readyDigest() && intent.osBoot >= checkNotNull(identity).osBoot && intent.osBoot <= native.boot())
        val bytes = intent.challenge.copyBytes()
        val challenge = try { LocalDevicePairingChallenge.decode(bytes) } finally { bytes.fill(0) }
        requireOwnDescriptor(challenge)
        requireAllocationReady(); requireChallengeRetirementLink(challenge)
        known(intent.responseId == allocatedResponseId() && intent.nonce == allocatedNonce() &&
            intent.allocationIndex == responseCycle && intent.allocationDigest == sha256(allocationBytes()) &&
            intent.responseId !in listOf(challenge.originalActionId, challenge.requested.request.requestId,
                challenge.requested.admission.deviceId, challenge.requested.target.keyId) +
                challenge.requested.sdkIdentity?.let { listOf(it.originalAcquisitionId, it.originalPreparationId) }.orEmpty() &&
                intent.responseId !in challenge.body.origin.reservedOriginalIds())
    }
    private fun validateComplete() {
        validateResponseIntent()
        val signed = complete ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val fields = TargetWire.decodeCompleteMessage(signed.message, variant)
        val encoded = TargetWire.complete(signed, variant)
        try { known(native.snapshot(completeName())?.contentEquals(encoded) == true) } finally { encoded.fill(0) }
        known(fields.ready.same(snapshotBytes(F.READY)) && fields.allocation.same(allocationBytes()) &&
            fields.intent.same(snapshotBytes(responseIntentName())))
        val proofBytes = fields.possession.copyBytes(); val challengeBytes = checkNotNull(responseIntent).challenge.copyBytes()
        val proof = try { LocalDevicePairingPossession.decode(proofBytes) } finally { proofBytes.fill(0) }
        val challenge = try { LocalDevicePairingChallenge.decode(challengeBytes) } finally { challengeBytes.fill(0) }
        val descriptor = challenge.requested.target
        known(proof.originalActionId == challenge.originalActionId && proof.challengeDigest == challenge.challengeDigest &&
            proof.deviceId == descriptor.admission.deviceId && proof.keyId == descriptor.keyId && proof.descriptorDigest == descriptor.descriptorDigest &&
            proof.sdkIdentityDigest == challenge.requested.sdkIdentity?.descriptorDigest)
        checkedKey()
        val point = keyPoint(); val message = signed.message.copyBytes(); val signature = signed.signature.copyBytes()
        val possessionMessage = NativeDevicePairingWireV1.possessionMessage(challenge); val possessionSignature = proof.copySignature()
        try { known(JvmP256Primitives.verify(point, message, signature) &&
            JvmP256Primitives.verify(point, possessionMessage, possessionSignature)) }
        finally { point.fill(0); message.fill(0); signature.fill(0); possessionMessage.fill(0); possessionSignature.fill(0) }
    }
    private fun requireOwnDescriptor(challenge: LocalDevicePairingChallenge) {
        val expected = descriptorWithoutReentry(); val supplied = challenge.requested.target
        val actual = supplied.copyWireBytes(); val wanted = expected.copyWireBytes()
        try { if (!actual.contentEquals(wanted)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { actual.fill(0); wanted.fill(0) }
        requireOwnSdkDescriptorData(challenge)
    }
    private fun descriptorWithoutReentry(): LocalDevicePairingTargetDescriptor {
        val fields = TargetWire.decodeReadyMessage(checkNotNull(ready).message, variant)
        val bytes = fields.descriptor.copyBytes()
        return try { LocalDevicePairingTargetDescriptor.decode(bytes) } finally { bytes.fill(0) }
    }
    private fun identityDigest(): PayloadDigest = sha256(snapshotBytes(F.IDENTITY))
    private fun readyDigest(): PayloadDigest = sha256(snapshotBytes(F.READY))
    private fun snapshotBytes(name: String): ByteArray = checkNotNull(native.snapshot(name))
    private fun updateReference(value: LocalDevicePairingTargetPhase) {
        val original = checkNotNull(identity); val intent = responseIntent
        val challenge = intent?.challenge?.copyBytes()?.let { bytes ->
            try { LocalDevicePairingChallenge.decode(bytes) } finally { bytes.fill(0) }
        }
        lastKnown = LocalDevicePairingTargetReference(original.preparationId, original.deviceId, original.keyId, value,
            allocatedResponseId(), challenge?.originalActionId, challenge?.challengeDigest,
            sdkAcquisition?.acquisitionId, if (sdkReady != null) sdkIdentityWithoutReentry().descriptorDigest else null)
    }
    private fun keyPoint(): ByteArray { val (p, k) = keyIds(); return native.keyPoint(p, k) }
    /** Same original key, policy and point; no fresh proof signature on reads/current checks. */
    private fun checkedKey() {
        native.assertContinuity(); validateKeyIntent(); native.assertKeyNamespace(keyIds(), keyRequired = true)
        val intent = checkNotNull(keyIntent); val (preparation, key) = keyIds()
        if (native.keySecurity(preparation, key, intent.security) != intent.security) keyFailure()
        val point = native.keyPoint(preparation, key)
        try { if (!intent.point.same(point)) keyFailure() } finally { point.fill(0) }
        native.assertContinuity()
        if (native.keySecurity(preparation, key, intent.security) != intent.security) keyFailure()
    }
    private fun signChecked(message: ByteArray): ByteArray {
        checkedKey(); val (preparation, key) = keyIds()
        return native.sign(preparation, key, message)
    }

    fun closeNative() {
        if (phase == Phase.CLOSED) return
        phase = Phase.CLOSE_STARTED
        sdkOwner?.let { it.close(); sdkOwner = null }
        native.releaseNative()
        identity = null; keyIntent = null; ready = null
        sdkAcquisition = null; sdkSigningIntent = null; sdkReady = null
        credentialAllocation = null; preparedHere = false
        slots.values.forEach { it.responseIntent = null; it.complete = null
            it.approvedMasterImportIntent = null; it.approvedMasterEvidence = null }
        phase = Phase.CLOSED
    }

    private companion object {
        val F = DevicePairingTargetFiles
        fun known(value: Boolean) { if (!value) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        fun keyFailure(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        fun expectedDescriptor(original: TargetIdentity, point: ByteArray): LocalDevicePairingTargetDescriptor =
            LocalDevicePairingTargetDescriptor.capture(LocalDeviceAdmission(original.scope, original.deviceId,
                Revision(0), true, original.keyId.value, sha256(point).value, original.capabilities.toSet()), original.keyId, point)
    }
}
