package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Additive consumption storage. Existing pairing originals and native history tags are unchanged. */
internal enum class NativeDeviceAdmissionPhaseV1 { DISPATCH_INTENT, CONSUMPTION_INTENT, CONSUMED, KNOWN_NO_EFFECT }

public data class LocalDeviceAdmissionProtectedImage(public val table: ProtectedLocalTable,
    public val recordId: DomainId, public val dimension: String, public val plaintextDigest: PayloadDigest) {
    init {
        require(table in setOf(ProtectedLocalTable.ADMISSION, ProtectedLocalTable.THROTTLE, ProtectedLocalTable.AUTH_EVENT))
        require(if (table == ProtectedLocalTable.THROTTLE) dimension.startsWith("device:") &&
            DomainId(dimension.removePrefix("device:")).value == dimension.removePrefix("device:") else dimension.isEmpty())
    }
}

/** Complete protected census and N+2 original INSERTs, not an authority or absence proof. */
public class LocalDeviceAdmissionEffectEvidence(public val request: LocalProvisioningRequest,
    public val definitionDigest: PayloadDigest, public val authorityDigest: PayloadDigest,
    public val nativeBindingDigest: PayloadDigest, public val eventAt: MonotonicMoment,
    public val protectedPreflight: LocalAuthenticationObservation,
    admissionDigests: List<Pair<DomainId, PayloadDigest>>, accountDigests: List<Pair<DomainId, PayloadDigest>>,
    images: List<LocalDeviceAdmissionProtectedImage>) {
    public val admissionDigests: List<Pair<DomainId, PayloadDigest>> = admissionDigests.toList()
    public val accountDigests: List<Pair<DomainId, PayloadDigest>> = accountDigests.toList()
    public val images: List<LocalDeviceAdmissionProtectedImage> = images.toList()
    init {
        require(request.action == LocalProvisioningAction.ADMIT_DEVICE && this.admissionDigests.size in 1..256 &&
            this.admissionDigests.map { it.first }.distinct().size == this.admissionDigests.size &&
            this.admissionDigests == this.admissionDigests.sortedBy { it.first.value } &&
            request.targetId !in this.admissionDigests.map { it.first } && this.accountDigests.size in 1..4096 &&
            this.accountDigests.map { it.first }.distinct().size == this.accountDigests.size &&
            this.accountDigests == this.accountDigests.sortedBy { it.first.value } &&
            eventAt.bootId == protectedPreflight.moment.bootId && eventAt.elapsedMillis <= protectedPreflight.moment.elapsedMillis)
        require(this.images.size == this.accountDigests.size + 2 &&
            this.images.first().let { it.table == ProtectedLocalTable.ADMISSION && it.recordId == request.targetId && it.dimension.isEmpty() } &&
            this.images.last().let { it.table == ProtectedLocalTable.AUTH_EVENT && it.recordId == request.requestId && it.dimension.isEmpty() } &&
            this.images.subList(1, this.images.lastIndex).map { it.recordId } == this.accountDigests.map { it.first } &&
            this.images.subList(1, this.images.lastIndex).all {
                it.table == ProtectedLocalTable.THROTTLE && it.dimension == "device:${request.targetId.value}" })
    }
    public fun copyWireBytes(): ByteArray = NativeDeviceAdmissionWireV1.encodeEvidence(this)
    override fun toString(): String = "LocalDeviceAdmissionEffectEvidence([REDACTED])"
    public companion object {
        public fun decode(canonical: ByteArray): LocalDeviceAdmissionEffectEvidence {
            require(canonical.size in 1..NativeDeviceAdmissionWireV1.MAX_EVIDENCE_BYTES)
            val result = NativeDeviceAdmissionWireV1.decodeEvidence(canonical)
            val encoded = result.copyWireBytes()
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return result
        }
    }
}

internal data class NativeDeviceAdmissionEffectV1(val frameId: DomainId, val child: NativeHistoryRecordedHeadV2,
    val intentDigest: PayloadDigest, val evidenceBytes: SelfOwnedOctetsV1) {
    fun evidence(): LocalDeviceAdmissionEffectEvidence = evidenceBytes.copyBytes().let {
        try { LocalDeviceAdmissionEffectEvidence.decode(it) } finally { it.fill(0) }
    }
}
internal data class NativeDeviceAdmissionRecordV1(val pairing: NativeDevicePairingRecordV1,
    val dispatchId: DomainId, val dispatchObservation: LocalAuthenticationObservation,
    val phase: NativeDeviceAdmissionPhaseV1, val effect: NativeDeviceAdmissionEffectV1? = null,
    val settledAnchorDigest: PayloadDigest? = null, val finalObservation: LocalAuthenticationObservation? = null) {
    init {
        require(pairing.phase == LocalDevicePairingPhase.APPROVED && pairing.body.requested().sdkIdentity != null &&
            pairing.body.requested().admission.revision == Revision(0) && dispatchId !in pairing.nativeIds() &&
            dispatchId !in pairing.body.origin.reservedOriginalIds())
        val prepared = pairing.body.preparedHead.observation
        require(dispatchObservation.issuerContextId == prepared.issuerContextId && dispatchObservation.moment.bootId == prepared.moment.bootId &&
            dispatchObservation.moment.elapsedMillis >= prepared.moment.elapsedMillis &&
            dispatchObservation.moment.elapsedMillis < checkNotNull(pairing.approvedHead).deadlineElapsedMillis)
        when (phase) {
            NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT -> require(effect == null && settledAnchorDigest == null && finalObservation == null)
            NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT -> require(effect != null && settledAnchorDigest == null && finalObservation == null)
            NativeDeviceAdmissionPhaseV1.CONSUMED -> require(effect != null && settledAnchorDigest != null && finalObservation != null)
            NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT -> require(settledAnchorDigest != null && finalObservation == null)
        }
        effect?.let {
            val evidence = it.evidence(); val requested = pairing.body.requested(); val parent = pairing.body.origin.preparationParent
            require(evidence.request == requested.request && evidence.definitionDigest == requested.definitionDigest &&
                evidence.authorityDigest == LocalDevicePairingApproval(pairing).authorityDigest &&
                evidence.admissionDigests.map { it.first } == requested.retainedAdmissions.map { it.deviceId } &&
                it.child.historySequence == parent.historySequence + 1L && it.child.commandEpoch == parent.commandEpoch &&
                it.child.commandSequence == parent.commandSequence && it.child.commandDigest == parent.commandDigest &&
                it.child.installationGenesisDigest == parent.installationGenesisDigest && it.child.roleCommitment == parent.roleCommitment &&
                it.child.recoveryCommitment == parent.recoveryCommitment && evidence.eventAt.elapsedMillis >= dispatchObservation.moment.elapsedMillis &&
                evidence.protectedPreflight.issuerContextId == dispatchObservation.issuerContextId &&
                evidence.protectedPreflight.moment.bootId == dispatchObservation.moment.bootId)
            finalObservation?.let { observed -> require(observed.issuerContextId == dispatchObservation.issuerContextId &&
                observed.moment.bootId == dispatchObservation.moment.bootId && observed.moment.elapsedMillis >= evidence.protectedPreflight.moment.elapsedMillis &&
                observed.moment.elapsedMillis < checkNotNull(pairing.approvedHead).deadlineElapsedMillis) }
        }
    }
    fun evidence(): LocalDeviceAdmissionEffectEvidence? = if (phase == NativeDeviceAdmissionPhaseV1.CONSUMED) effect?.evidence() else null
}

internal data class NativeDeviceAdmissionStateV1(val generation: Long, val records: List<NativeDeviceAdmissionRecordV1>) {
    init {
        require(generation in 0 until Long.MAX_VALUE && records.size in 1..256 &&
            records.map { it.pairing.body.originalActionId }.distinct().size == records.size &&
            records.map { it.dispatchId }.distinct().size == records.size)
        // These are APPROVED records, not a replacement/subset pairing ledger. Retirement
        // predecessors remain in the actual original ledger and are joined by its owner.
        val originalIds = records.flatMap { it.pairing.nativeIds() }
        require(originalIds.distinct().size == originalIds.size)
        val requests = records.map { it.pairing.body.requested() }
        require(requests.map { it.admission.deviceId }.distinct().size == requests.size &&
            requests.map { it.target.keyId }.distinct().size == requests.size &&
            requests.map { checkNotNull(it.sdkIdentity).let { sdk -> sdk.appId.value to sdk.fingerprint.value } }.distinct().size == requests.size &&
            requests.map { checkNotNull(it.sdkIdentity).let { sdk -> sdk.appId.value to sdk.peerId.value } }.distinct().size == requests.size)
        val children = records.filter { it.phase == NativeDeviceAdmissionPhaseV1.CONSUMED }.map { checkNotNull(it.effect).child }
        require(children.map { it.historySequence }.distinct().size == children.size)
        val reserved = records.flatMap { it.pairing.nativeIds() + it.pairing.body.origin.reservedOriginalIds() }.toSet()
        require(records.none { it.dispatchId in reserved })
    }
    fun next(record: NativeDeviceAdmissionRecordV1): NativeDeviceAdmissionStateV1 {
        require(generation < Long.MAX_VALUE - 1)
        val index = records.indexOfFirst { it.pairing.body.originalActionId == record.pairing.body.originalActionId }
        return NativeDeviceAdmissionStateV1(generation + 1, if (index < 0) records + record else records.toMutableList().also { it[index] = record })
    }
}

public fun localDeviceAdmissionDependenciesV1(evidence: LocalDeviceAdmissionEffectEvidence): ByteArray =
    selfOwnedEncode("actual-room-original-device-admission-dependencies/v1", NativeDeviceAdmissionWireV1.MAX_EVIDENCE_BYTES) {
        scope(evidence.request.scope); id(evidence.request.requestId); id(evidence.request.targetId); digest(evidence.request.contentDigest)
        digest(evidence.definitionDigest); digest(evidence.authorityDigest); digest(evidence.nativeBindingDigest)
        int(evidence.admissionDigests.size); evidence.admissionDigests.forEach { (id, hash) -> id(id); digest(hash) }
        int(evidence.accountDigests.size); evidence.accountDigests.forEach { (id, hash) -> id(id); digest(hash) }
    }

internal object NativeDeviceAdmissionWireV1 {
    const val MAX_EVIDENCE_BYTES = 2 * 1024 * 1024
    const val MAX_STATE_BYTES = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_PLAINTEXT_BYTES
    fun aad(original: LocalCompanyAuthorityBootstrapV1): ByteArray = selfOwnedEncode("native-device-admission-ledger-aad/v1") {
        id(original.original.rootId); id(original.original.actionId); id(original.headAuthorityId); id(original.headEnrollmentId)
    }
    fun binding(frame: NativeDeviceAdmissionFrame): ByteArray = selfOwnedEncode("original-native-device-admission-frame/v1") {
        id(frame.frameId); digest(frame.native.original.recordDigest); digest(frame.requested.definitionDigest)
        digest(frame.authorityDigest); id(frame.permit.dispatch.record.dispatchId); observation(frame.bindingObservation)
    }
    fun encodeEvidence(value: LocalDeviceAdmissionEffectEvidence): ByteArray = selfOwnedEncode("original-device-admission-effects/v1", MAX_EVIDENCE_BYTES) {
        request(value.request); digest(value.definitionDigest); digest(value.authorityDigest); digest(value.nativeBindingDigest)
        moment(value.eventAt); observation(value.protectedPreflight); pairs(value.admissionDigests); pairs(value.accountDigests)
        int(value.images.size); value.images.forEach { string(it.table.name); id(it.recordId); string(it.dimension); digest(it.plaintextDigest) }
    }
    fun decodeEvidence(bytes: ByteArray): LocalDeviceAdmissionEffectEvidence = selfOwnedDecode(bytes, "original-device-admission-effects/v1") {
        val request = request(); val definition = digest(); val authority = digest(); val binding = digest()
        val at = moment(); val preflight = observation(); val admissions = pairs(256); val accounts = pairs(4096)
        val count = int(); require(count in 3..4098)
        LocalDeviceAdmissionEffectEvidence(request, definition, authority, binding, at, preflight, admissions, accounts,
            List(count) { LocalDeviceAdmissionProtectedImage(named(ProtectedLocalTable.entries), id(), string(64), digest()) })
    }
    fun encode(value: NativeDeviceAdmissionStateV1): ByteArray = selfOwnedEncode("native-original-device-admission-ledger/v1", MAX_STATE_BYTES) {
        long(value.generation); int(value.records.size)
        value.records.forEach { record ->
            val pairing = NativeDevicePairingWireV1.encodeRetainedRecord(record.pairing)
            try { field(pairing) } finally { pairing.fill(0) }
            id(record.dispatchId); observation(record.dispatchObservation); string(record.phase.name); bool(record.effect != null)
            record.effect?.let { effect -> id(effect.frameId); head(effect.child); digest(effect.intentDigest); octets(effect.evidenceBytes) }
            bool(record.settledAnchorDigest != null); record.settledAnchorDigest?.let(::digest)
            bool(record.finalObservation != null); record.finalObservation?.let(::observation)
        }
    }
    fun decode(bytes: ByteArray): NativeDeviceAdmissionStateV1 = selfOwnedDecode(bytes, "native-original-device-admission-ledger/v1") {
        val generation = long(); val count = int(); require(count in 1..256)
        NativeDeviceAdmissionStateV1(generation, List(count) {
            val encoded = field(MAX_STATE_BYTES)
            val pairing = try { NativeDevicePairingWireV1.decodeRetainedRecord(encoded) }
                finally { encoded.fill(0) }
            val dispatch = id(); val observed = observation(); val phase = named(NativeDeviceAdmissionPhaseV1.entries)
            val effect = if (bool()) NativeDeviceAdmissionEffectV1(id(), head(), digest(), octets(MAX_EVIDENCE_BYTES)) else null
            NativeDeviceAdmissionRecordV1(pairing, dispatch, observed, phase, effect, if (bool()) digest() else null, if (bool()) observation() else null)
        })
    }
    /** Reserve the complete census/effect and terminal before any irreversible dispatch. */
    fun requireCompletionCapacity(state: NativeDeviceAdmissionStateV1, accountCount: Int, admissionCount: Int) {
        require(accountCount in 1..4096 && admissionCount in 1..256)
        val bytes = encode(state)
        val reserve = state.records.sumOf { record -> when (record.phase) {
            NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT -> accountCount.toLong() * 384L +
                maxOf(admissionCount, record.pairing.body.requested().retainedAdmissions.size).toLong() * 192L + 16_384L
            NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT -> 1024L
            else -> 0L
        } }
        val revisions = state.records.sumOf { when (it.phase) {
            NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT -> 2L
            NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT -> 1L
            else -> 0L
        } }
        try {
            if (bytes.size.toLong() + reserve > MAX_STATE_BYTES || revisions > (Long.MAX_VALUE - 1L) - state.generation)
                selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        }
        finally { bytes.fill(0) }
    }
    fun validateTransition(previous: NativeDeviceAdmissionStateV1?, next: NativeDeviceAdmissionStateV1) {
        if (previous == null) require(next.generation == 0L && next.records.size == 1 && next.records.single().phase == NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT)
        else {
            require(next.generation == previous.generation + 1 && next.records.size in previous.records.size..previous.records.size + 1)
            var changed = 0
            previous.records.forEachIndexed { index, before ->
                val after = next.records[index]
                if (before != after) {
                    changed++; require(after.pairing == before.pairing && after.dispatchId == before.dispatchId && after.dispatchObservation == before.dispatchObservation)
                    require(when (before.phase) {
                        NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT -> after.phase == NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT ||
                            after.phase == NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT && after.effect == null
                        NativeDeviceAdmissionPhaseV1.CONSUMPTION_INTENT -> after.phase in setOf(NativeDeviceAdmissionPhaseV1.CONSUMED, NativeDeviceAdmissionPhaseV1.KNOWN_NO_EFFECT) && after.effect == before.effect
                        else -> false
                    })
                }
            }
            if (next.records.size > previous.records.size) { changed++; require(next.records.last().phase == NativeDeviceAdmissionPhaseV1.DISPATCH_INTENT) }
            require(changed == 1)
        }
    }
    private fun SelfOwnedWireWriterV1.request(value: LocalProvisioningRequest) { scope(value.scope); id(value.requestId); string(value.action.name); id(value.targetId); digest(value.contentDigest) }
    private fun SelfOwnedWireReaderV1.request(): LocalProvisioningRequest = LocalProvisioningRequest(scope(), id(), named(LocalProvisioningAction.entries), id(), digest())
    private fun SelfOwnedWireWriterV1.pairs(value: List<Pair<DomainId, PayloadDigest>>) { int(value.size); value.forEach { (id, hash) -> id(id); digest(hash) } }
    private fun SelfOwnedWireReaderV1.pairs(maximum: Int): List<Pair<DomainId, PayloadDigest>> { val count = int(); require(count in 1..maximum); return List(count) { id() to digest() } }
    private fun SelfOwnedWireWriterV1.head(value: NativeHistoryRecordedHeadV2) { long(value.historySequence); digest(value.historyDigest); digest(value.installationGenesisDigest); long(value.commandEpoch); long(value.commandSequence); digest(value.commandDigest); digest(value.roleCommitment); digest(value.recoveryCommitment) }
    private fun SelfOwnedWireReaderV1.head(): NativeHistoryRecordedHeadV2 = NativeHistoryRecordedHeadV2(long(), digest(), digest(), long(), long(), digest(), digest(), digest())
}

private fun SelfOwnedWireWriterV1.scope(value: BranchScope) {
    id(value.organizationId); id(value.companyId); id(value.branchId)
}
private fun SelfOwnedWireReaderV1.scope(): BranchScope = BranchScope(id(), id(), id())
private fun SelfOwnedWireWriterV1.digest(value: PayloadDigest) { string(value.value) }
private fun SelfOwnedWireReaderV1.digest(): PayloadDigest = PayloadDigest(string(64))
private fun SelfOwnedWireWriterV1.moment(value: MonotonicMoment) { id(value.bootId); long(value.elapsedMillis) }
private fun SelfOwnedWireReaderV1.moment(): MonotonicMoment = MonotonicMoment(id(), long())
private fun SelfOwnedWireWriterV1.observation(value: LocalAuthenticationObservation) { id(value.issuerContextId); moment(value.moment) }
private fun SelfOwnedWireReaderV1.observation(): LocalAuthenticationObservation = LocalAuthenticationObservation(id(), moment())
private fun <T : Enum<T>> SelfOwnedWireReaderV1.named(values: List<T>): T = string(128).let { name ->
    requireNotNull(values.singleOrNull { it.name == name })
}
