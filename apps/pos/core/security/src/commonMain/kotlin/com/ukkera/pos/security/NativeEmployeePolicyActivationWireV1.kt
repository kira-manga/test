package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal data class NativeEmployeePolicyActivationTargetV1(
    val coordinates: SelfOwnedAllocationCoordinatesV1,
    val allocationDigest: PayloadDigest, val bindingDigest: PayloadDigest,
    val installationRequestId: DomainId, val installationDefinitionDigest: PayloadDigest,
    val installationReceiptDigest: PayloadDigest, val authorityEpoch: Revision,
    val preparationParent: NativeHistoryRecordedHeadV2, val preparationAnchorDigest: PayloadDigest,
    val rootId: DomainId, val rootKeyId: DomainId, val rootDocumentDigest: PayloadDigest,
    val foundingActionId: DomainId, val headAuthorityId: DomainId, val headEnrollmentId: DomainId,
    val rootFingerprint: String,
) {
    // The original coordinates are a capability-data class with reference equality. Ledger
    // decode must compare their complete canonical values, not object identity.
    override fun equals(other: Any?): Boolean {
        if (other !is NativeEmployeePolicyActivationTargetV1) return false
        val left = NativeEmployeePolicyActivationWireV1.targetBytes(this)
        val right = NativeEmployeePolicyActivationWireV1.targetBytes(other)
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }
    override fun hashCode(): Int {
        val bytes = NativeEmployeePolicyActivationWireV1.targetBytes(this)
        return try { bytes.contentHashCode() } finally { bytes.fill(0) }
    }
    fun reservedOriginalIds(): Set<DomainId> = coordinates.let { c -> setOf(c.allocationId, c.scope.organizationId, c.scope.companyId,
        c.scope.branchId, c.installationId, c.assignmentId, c.deviceId, c.storageInstanceId, c.storageKeyId, c.pepperKeyId,
        c.targetSigningKeyId, c.firstActorId, installationRequestId, rootId, rootKeyId, foundingActionId, headAuthorityId, headEnrollmentId) }
    init {
        selfOwnedHexDigest(rootFingerprint)
        require(preparationParent.commandEpoch == authorityEpoch.value && preparationParent.historySequence >= 1)
    }
}

internal data class NativeEmployeePolicyActivationHeadV1(
    val companyName: String, val headName: String, val headLogin: String,
    val accountRevision: Long, val credentialRevision: Long, val retainedRevision: Long,
    val osBootCount: Int, val observation: LocalAuthenticationObservation, val deadlineElapsedMillis: Long,
) {
    init {
        selfOwnedLabel(companyName); selfOwnedLabel(headName); selfOwnedLogin(headLogin)
        require(accountRevision >= 0 && credentialRevision >= 0 && retainedRevision >= 0 && osBootCount >= 0 &&
            observation.moment.elapsedMillis < deadlineElapsedMillis)
    }
}

internal data class NativeEmployeePolicyActivationApprovalV1(
    val accountRevision: Long, val credentialRevision: Long, val retainedRevision: Long,
    val osBootCount: Int, val deadlineElapsedMillis: Long,
) {
    init { require(accountRevision >= 0 && credentialRevision >= 0 && retainedRevision >= 0 && osBootCount >= 0 && deadlineElapsedMillis >= 0) }
}

internal data class NativeEmployeePolicyActivationDispatchV1(
    val dispatchId: DomainId, val originalAnchorDigest: PayloadDigest,
    val observation: LocalAuthenticationObservation,
)

/** Strict original protected AFTER image with the unchanged BEFORE plaintext commitment.
 * These are DATA; only the actual native raw reader can qualify physical/key/plaintext joins. */
public data class LocalEmployeePolicyActivationProtectedImage(
    public val table: ProtectedLocalTable, public val recordId: DomainId, public val revision: Revision,
    public val previousPlaintextDigest: PayloadDigest?, public val plaintextDigest: PayloadDigest,
) {
    init {
        require(table in setOf(ProtectedLocalTable.EFFECTIVE_POLICY, ProtectedLocalTable.ACCOUNT, ProtectedLocalTable.AUTH_EVENT))
        require((table == ProtectedLocalTable.AUTH_EVENT) == (previousPlaintextDigest == null))
        require(if (table == ProtectedLocalTable.AUTH_EVENT) revision == Revision(0) else revision.value > 0L)
    }
}

/** Complete retained approval/dependency/effect DATA, never a source of current authority. */
public class LocalEmployeePolicyActivationEffectEvidence(
    public val request: LocalProvisioningRequest, public val definitionDigest: PayloadDigest,
    public val authorityDigest: PayloadDigest, public val nativeBindingDigest: PayloadDigest,
    public val eventAt: MonotonicMoment, public val protectedPreflight: LocalAuthenticationObservation,
    public val beforePolicyDigest: PayloadDigest, public val beforeAccountDigest: PayloadDigest,
    public val afterAccountDigest: PayloadDigest, public val afterAccountRevision: Revision,
    images: List<LocalEmployeePolicyActivationProtectedImage>,
) {
    public val images: List<LocalEmployeePolicyActivationProtectedImage> = images.toList()
    init {
        require(request.action == LocalProvisioningAction.ACTIVATE_POLICY && this.images.size in 2..3 &&
            eventAt.bootId == protectedPreflight.moment.bootId && eventAt.elapsedMillis <= protectedPreflight.moment.elapsedMillis)
        val actor = request.targetId
        require(this.images[0].let { it.table == ProtectedLocalTable.EFFECTIVE_POLICY && it.recordId == actor &&
            it.previousPlaintextDigest == beforePolicyDigest } &&
            this.images.last().let { it.table == ProtectedLocalTable.AUTH_EVENT && it.recordId == request.requestId })
        if (this.images.size == 3) require(this.images[1].let { it.table == ProtectedLocalTable.ACCOUNT && it.recordId == actor &&
            it.previousPlaintextDigest == beforeAccountDigest && it.plaintextDigest == afterAccountDigest && it.revision == afterAccountRevision })
        else require(afterAccountDigest == beforeAccountDigest)
    }
    /** Bind the original approved complete policy before retaining any effect intent, and
     * again whenever its durable record is decoded. Cold history is an additional join. */
    internal fun matchesRequestedPolicy(requested: LocalEmployeePolicyActivationDefinition): Boolean {
        val policy = requested.copyCanonicalPolicy()
        return try {
            request == requested.request && definitionDigest == requested.definitionDigest &&
                images[0].revision == requested.nextPolicy.revision && images[0].plaintextDigest == sha256(policy)
        } finally { policy.fill(0) }
    }
    public fun copyWireBytes(): ByteArray = NativeEmployeePolicyActivationWireV1.encodeEvidence(this)
    override fun toString(): String = "LocalEmployeePolicyActivationEffectEvidence([REDACTED])"
    public companion object {
        public fun decode(canonical: ByteArray): LocalEmployeePolicyActivationEffectEvidence {
            val result = NativeEmployeePolicyActivationWireV1.decodeEvidence(canonical)
            val bytes = result.copyWireBytes()
            try { require(bytes.contentEquals(canonical)) } finally { bytes.fill(0) }; return result
        }
        public fun hasProfile(bytes: ByteArray): Boolean {
            val prefix = selfOwnedEncode("runtime-native-complete-employee-policy-activation-evidence/v1") { }
            return try { bytes.size > prefix.size && prefix.indices.all { bytes[it] == prefix[it] } }
            finally { prefix.fill(0) }
        }
    }
}

internal data class NativeEmployeePolicyActivationEffectV1(
    val frameId: DomainId, val child: NativeHistoryRecordedHeadV2, val originalIntentDigest: PayloadDigest,
    val dependenciesDigest: PayloadDigest, val originalFactsDigest: PayloadDigest,
    val evidence: SelfOwnedOctetsV1,
) {
    init {
        require(evidence.size in 1..LocalEmployeePolicyActivationProfileV1.MAXIMUM_COMPLETION_BYTES && child.historySequence > 1)
        val bytes = evidence.copyBytes()
        try { require(sha256(bytes) == originalFactsDigest); LocalEmployeePolicyActivationEffectEvidence.decode(bytes) }
        finally { bytes.fill(0) }
    }
}

internal enum class NativeEmployeePolicyActivationTerminalKindV1 { COMMITTED_JOINED_CHILD, KNOWN_NO_POLICY_EFFECT }
internal data class NativeEmployeePolicyActivationTerminalV1(
    val kind: NativeEmployeePolicyActivationTerminalKindV1, val settledAnchorDigest: PayloadDigest,
    val finalObservation: LocalAuthenticationObservation?,
) {
    init { require((kind == NativeEmployeePolicyActivationTerminalKindV1.COMMITTED_JOINED_CHILD) == (finalObservation != null)) }
}

internal data class NativeEmployeePolicyActivationRecordV1(
    val originalActionId: DomainId,
    val target: NativeEmployeePolicyActivationTargetV1, val definition: SelfOwnedOctetsV1,
    val preparedHead: NativeEmployeePolicyActivationHeadV1,
    val phase: LocalEmployeePolicyActivationPhase,
    val approvedHead: NativeEmployeePolicyActivationApprovalV1? = null,
    val rootEndorsement: SelfOwnedOctetsV1 = SelfOwnedOctetsV1(ByteArray(0)),
    val dispatch: NativeEmployeePolicyActivationDispatchV1? = null,
    val effect: NativeEmployeePolicyActivationEffectV1? = null,
    val terminal: NativeEmployeePolicyActivationTerminalV1? = null,
) {
    init {
        val requested = requested(); val request = requested.request
        require(definition.size in 1..LocalEmployeePolicyActivationProfileV1.MAXIMUM_DEFINITION_BYTES && request.scope == target.coordinates.scope &&
            request.action == LocalProvisioningAction.ACTIVATE_POLICY && request.scope == requested.nextPolicy.scope)
        require(listOf(originalActionId, request.requestId).distinct().size == 2)
        approvedHead?.let {
            require(it.accountRevision == preparedHead.accountRevision && it.credentialRevision == preparedHead.credentialRevision &&
                it.osBootCount == preparedHead.osBootCount && it.retainedRevision >= preparedHead.retainedRevision &&
                it.deadlineElapsedMillis <= preparedHead.deadlineElapsedMillis &&
                it.deadlineElapsedMillis > preparedHead.observation.moment.elapsedMillis)
        }
        when (phase) {
            LocalEmployeePolicyActivationPhase.PREPARED -> require(approvedHead == null && rootEndorsement.size == 0 && dispatch == null && effect == null && terminal == null)
            LocalEmployeePolicyActivationPhase.SIGNING_INTENT -> require(approvedHead != null && rootEndorsement.size == 0 && dispatch == null && effect == null && terminal == null)
            LocalEmployeePolicyActivationPhase.APPROVED -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch == null && effect == null && terminal == null)
            LocalEmployeePolicyActivationPhase.DISPATCH_INTENT -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch != null && effect == null && terminal == null)
            LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch != null && effect != null && terminal == null)
            LocalEmployeePolicyActivationPhase.CONSUMED -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch != null && effect != null &&
                terminal?.kind == NativeEmployeePolicyActivationTerminalKindV1.COMMITTED_JOINED_CHILD)
            LocalEmployeePolicyActivationPhase.REJECTED -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch != null &&
                terminal?.kind == NativeEmployeePolicyActivationTerminalKindV1.KNOWN_NO_POLICY_EFFECT)
        }
        effect?.let { retained ->
            val bytes = retained.evidence.copyBytes()
            try { require(LocalEmployeePolicyActivationEffectEvidence.decode(bytes).matchesRequestedPolicy(requested)) }
            finally { bytes.fill(0) }
        }
    }
    fun requested(): LocalEmployeePolicyActivationDefinition {
        val bytes = definition.copyBytes()
        return try { LocalEmployeePolicyActivationDefinition.decode(bytes) } finally { bytes.fill(0) }
    }
    fun nativeIds(): List<DomainId> = listOf(originalActionId, requested().request.requestId) + listOfNotNull(dispatch?.dispatchId)
    override fun toString(): String = "NativeEmployeePolicyActivationRecordV1([REDACTED])"
}

internal data class NativeEmployeePolicyActivationStateV1(val generation: Long, val records: List<NativeEmployeePolicyActivationRecordV1>) {
    init {
        require(generation in 0 until Long.MAX_VALUE && records.size in 1..LocalEmployeePolicyActivationProfileV1.MAXIMUM_ACTIONS)
        val ids = records.flatMap { it.nativeIds() }; require(ids.distinct().size == ids.size)
        require(records.count { it.phase in setOf(LocalEmployeePolicyActivationPhase.SIGNING_INTENT,
            LocalEmployeePolicyActivationPhase.DISPATCH_INTENT, LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT) } <= 1)
    }
    fun next(record: NativeEmployeePolicyActivationRecordV1): NativeEmployeePolicyActivationStateV1 {
        if (generation >= Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val old = records.singleOrNull { it.originalActionId == record.originalActionId }
        return copy(generation = generation + 1, records = if (old == null) records + record else records.map { if (it === old) record else it })
    }
    override fun toString(): String = "NativeEmployeePolicyActivationStateV1([REDACTED])"
}

/** Additive policy ledger/AAD/signature domains under the existing original root. Old domains are unchanged. */
internal object NativeEmployeePolicyActivationWireV1 {
    fun targetBytes(value: NativeEmployeePolicyActivationTargetV1): ByteArray =
        selfOwnedEncode("original-local-employee-policy-target/v1") { target(value) }
    fun aad(original: LocalCompanyAuthorityBootstrapV1): ByteArray = selfOwnedEncode("company-employee-policy-activation-registry-aad/v1") {
        string(LocalEmployeePolicyActivationProfileV1.PURPOSE)
        val originalBytes = LocalCompanyAuthorityWireV1.registryAad(original)
        try { field(originalBytes) } finally { originalBytes.fill(0) }
    }
    fun encode(value: NativeEmployeePolicyActivationStateV1): ByteArray = selfOwnedEncode("native-company-employee-policy-activations/v1",
        LocalEmployeePolicyActivationProfileV1.MAXIMUM_PLAINTEXT_BYTES) {
        long(value.generation); int(value.records.size)
        for (record in value.records) {
            original(record); string(record.phase.name)
            bool(record.approvedHead != null); record.approvedHead?.let { approval(it) }
            octets(record.rootEndorsement); bool(record.dispatch != null); record.dispatch?.let {
                id(it.dispatchId); digest(it.originalAnchorDigest); observation(it.observation)
            }
            bool(record.effect != null); record.effect?.let {
                id(it.frameId); head(it.child); digest(it.originalIntentDigest); digest(it.dependenciesDigest); digest(it.originalFactsDigest); octets(it.evidence)
            }
            bool(record.terminal != null); record.terminal?.let {
                string(it.kind.name); digest(it.settledAnchorDigest); bool(it.finalObservation != null); it.finalObservation?.let { final -> observation(final) }
            }
        }
    }
    fun decode(bytes: ByteArray): NativeEmployeePolicyActivationStateV1 = selfOwnedDecode(bytes, "native-company-employee-policy-activations/v1") {
        val generation = long(); val count = int(); require(count in 1..LocalEmployeePolicyActivationProfileV1.MAXIMUM_ACTIONS)
        NativeEmployeePolicyActivationStateV1(generation, List(count) {
            val action = id(); val target = employeeTarget()
            val definition = octets(LocalEmployeePolicyActivationProfileV1.MAXIMUM_DEFINITION_BYTES); val prepared = preparedHead()
            val phase = named(LocalEmployeePolicyActivationPhase.entries); val approved = if (bool()) approval() else null
            val signature = octets(64); val dispatch = if (bool()) NativeEmployeePolicyActivationDispatchV1(id(), digest(), observation()) else null
            val effect = if (bool()) NativeEmployeePolicyActivationEffectV1(id(), head(), digest(), digest(), digest(),
                octets(LocalEmployeePolicyActivationProfileV1.MAXIMUM_COMPLETION_BYTES)) else null
            val terminal = if (bool()) NativeEmployeePolicyActivationTerminalV1(named(NativeEmployeePolicyActivationTerminalKindV1.entries), digest(),
                if (bool()) observation() else null) else null
            NativeEmployeePolicyActivationRecordV1(action, target, definition, prepared, phase, approved, signature,
                dispatch, effect, terminal)
        })
    }
    fun actionMessage(record: NativeEmployeePolicyActivationRecordV1): ByteArray = selfOwnedEncode("company-head-complete-employee-policy-action/v1") {
        string(LocalEmployeePolicyActivationProfileV1.PURPOSE); originalCommitment(record)
    }
    fun endorsementMessage(record: NativeEmployeePolicyActivationRecordV1): ByteArray = selfOwnedEncode("company-root-complete-employee-policy-approval/v1") {
        string(LocalEmployeePolicyActivationProfileV1.PURPOSE)
        string("NAMED_HEAD_COMPLETE_LOCAL_EMPLOYEE_POLICY_ONLY_NOT_CENTRAL_OR_BRANCH_EMPLOYEE_DELEGATION")
        originalCommitment(record); approval(checkNotNull(record.approvedHead))
    }
    fun authorityCertificate(record: NativeEmployeePolicyActivationRecordV1): ByteArray = selfOwnedEncode("native-local-employee-policy-authority-certificate/v1") {
        require(record.rootEndorsement.size == 64)
        val body = endorsementMessage(record)
        try { field(body) } finally { body.fill(0) }; octets(record.rootEndorsement)
    }
    fun bindingMessage(frame: NativeEmployeePolicyActivationFrame): ByteArray = selfOwnedEncode("actual-native-employee-policy-activation-frame/v1") {
        string(LocalEmployeePolicyActivationProfileV1.PURPOSE); id(frame.native.frameId); digest(frame.native.original.recordDigest)
        id(frame.permit.approval.originalActionId); digest(frame.permit.approval.authorityDigest); observation(frame.bindingObservation)
    }
    fun encodeEvidence(value: LocalEmployeePolicyActivationEffectEvidence): ByteArray =
        selfOwnedEncode("runtime-native-complete-employee-policy-activation-evidence/v1",
            LocalEmployeePolicyActivationProfileV1.MAXIMUM_COMPLETION_BYTES) {
            string(LocalEmployeePolicyActivationProfileV1.PURPOSE); request(value.request); digest(value.definitionDigest)
            digest(value.authorityDigest); digest(value.nativeBindingDigest); moment(value.eventAt); observation(value.protectedPreflight)
            digest(value.beforePolicyDigest); digest(value.beforeAccountDigest); digest(value.afterAccountDigest); long(value.afterAccountRevision.value)
            int(value.images.size); value.images.forEach {
                string(it.table.wireName); id(it.recordId); long(it.revision.value)
                bool(it.previousPlaintextDigest != null); it.previousPlaintextDigest?.let { before -> digest(before) }; digest(it.plaintextDigest)
            }
        }
    fun decodeEvidence(bytes: ByteArray): LocalEmployeePolicyActivationEffectEvidence =
        selfOwnedDecode(bytes, "runtime-native-complete-employee-policy-activation-evidence/v1") {
            require(bytes.size in 1..LocalEmployeePolicyActivationProfileV1.MAXIMUM_COMPLETION_BYTES &&
                string(128) == LocalEmployeePolicyActivationProfileV1.PURPOSE)
            val request = request(); val definition = digest(); val authority = digest(); val binding = digest()
            val event = moment(); val observed = observation(); val policy = digest(); val before = digest(); val after = digest()
            val revision = Revision(long()); val count = int(); require(count in 2..3)
            val images = List(count) {
                val name = string(64); val table = ProtectedLocalTable.entries.single { it.wireName == name }
                LocalEmployeePolicyActivationProtectedImage(table, id(), Revision(long()), if (bool()) digest() else null, digest())
            }
            LocalEmployeePolicyActivationEffectEvidence(request, definition, authority, binding, event, observed,
                policy, before, after, revision, images)
        }
    fun encodeWithCompletionCapacity(value: NativeEmployeePolicyActivationStateV1): ByteArray {
        val bytes = encode(value)
        val reserve = value.records.count { it.phase !in setOf(LocalEmployeePolicyActivationPhase.CONSUMED, LocalEmployeePolicyActivationPhase.REJECTED) }.toLong() *
            LocalEmployeePolicyActivationProfileV1.MAXIMUM_COMPLETION_BYTES
        val revisions = value.records.sumOf { when (it.phase) {
            LocalEmployeePolicyActivationPhase.PREPARED -> 5L
            LocalEmployeePolicyActivationPhase.SIGNING_INTENT -> 4L
            LocalEmployeePolicyActivationPhase.APPROVED -> 3L
            LocalEmployeePolicyActivationPhase.DISPATCH_INTENT -> 2L
            LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT -> 1L
            LocalEmployeePolicyActivationPhase.CONSUMED -> 0L
            LocalEmployeePolicyActivationPhase.REJECTED -> 0L
        } }
        if (bytes.size.toLong() + reserve > LocalEmployeePolicyActivationProfileV1.MAXIMUM_PLAINTEXT_BYTES ||
            revisions > (Long.MAX_VALUE - 1L) - value.generation) {
            bytes.fill(0); selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        }
        return bytes
    }
    fun validateTransition(previous: NativeEmployeePolicyActivationStateV1?, next: NativeEmployeePolicyActivationStateV1) {
        if (previous == null) { require(next.generation == 0L && next.records.size == 1 && next.records.single().phase == LocalEmployeePolicyActivationPhase.PREPARED); return }
        require(next.generation == previous.generation + 1 && next.records.size in previous.records.size..previous.records.size + 1)
        if (next.records.size > previous.records.size) {
            require(next.records.take(previous.records.size) == previous.records && next.records.last().phase == LocalEmployeePolicyActivationPhase.PREPARED &&
                previous.records.none { it.phase in setOf(LocalEmployeePolicyActivationPhase.SIGNING_INTENT, LocalEmployeePolicyActivationPhase.DISPATCH_INTENT,
                    LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT) }); return
        }
        val changed = previous.records.zip(next.records).filter { (a, b) -> a != b }; require(changed.size == 1)
        val (before, after) = changed.single()
        require(before.originalActionId == after.originalActionId && before.target == after.target && before.definition == after.definition && before.preparedHead == after.preparedHead)
        when (before.phase) {
            LocalEmployeePolicyActivationPhase.PREPARED -> require(after == before.copy(
                phase = LocalEmployeePolicyActivationPhase.SIGNING_INTENT, approvedHead = after.approvedHead))
            LocalEmployeePolicyActivationPhase.SIGNING_INTENT -> require(after == before.copy(phase = LocalEmployeePolicyActivationPhase.APPROVED,
                rootEndorsement = after.rootEndorsement))
            LocalEmployeePolicyActivationPhase.APPROVED -> require(after == before.copy(phase = LocalEmployeePolicyActivationPhase.DISPATCH_INTENT, dispatch = after.dispatch))
            LocalEmployeePolicyActivationPhase.DISPATCH_INTENT -> require(after == before.copy(phase = after.phase, effect = after.effect, terminal = after.terminal) &&
                after.phase in setOf(LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT, LocalEmployeePolicyActivationPhase.REJECTED))
            LocalEmployeePolicyActivationPhase.CONSUMPTION_INTENT -> require(after == before.copy(phase = after.phase, terminal = after.terminal) &&
                after.phase in setOf(LocalEmployeePolicyActivationPhase.CONSUMED, LocalEmployeePolicyActivationPhase.REJECTED))
            LocalEmployeePolicyActivationPhase.REJECTED -> error("Rejected original policy action is immutable")
            LocalEmployeePolicyActivationPhase.CONSUMED -> error("Consumed original policy action is immutable")
        }
    }
    private fun SelfOwnedWireWriterV1.original(value: NativeEmployeePolicyActivationRecordV1) {
        id(value.originalActionId)
        target(value.target); octets(value.definition); preparedHead(value.preparedHead)
    }
    private fun SelfOwnedWireWriterV1.originalCommitment(value: NativeEmployeePolicyActivationRecordV1) {
        id(value.originalActionId)
        target(value.target); val requested = value.requested(); request(requested.request); digest(requested.definitionDigest); preparedHead(value.preparedHead)
    }
    private fun SelfOwnedWireWriterV1.request(value: LocalProvisioningRequest) { id(value.scope.organizationId); id(value.scope.companyId); id(value.scope.branchId)
        id(value.requestId); string(value.action.name); id(value.targetId); digest(value.contentDigest) }
    private fun SelfOwnedWireReaderV1.request(): LocalProvisioningRequest = LocalProvisioningRequest(BranchScope(id(), id(), id()), id(),
        named(LocalProvisioningAction.entries), id(), digest())
    private fun SelfOwnedWireWriterV1.target(value: NativeEmployeePolicyActivationTargetV1) {
        coordinates(value.coordinates); digest(value.allocationDigest); digest(value.bindingDigest); id(value.installationRequestId)
        digest(value.installationDefinitionDigest); digest(value.installationReceiptDigest); long(value.authorityEpoch.value)
        head(value.preparationParent); digest(value.preparationAnchorDigest); id(value.rootId); id(value.rootKeyId); digest(value.rootDocumentDigest)
        id(value.foundingActionId); id(value.headAuthorityId); id(value.headEnrollmentId); string(value.rootFingerprint)
    }
    private fun SelfOwnedWireReaderV1.employeeTarget(): NativeEmployeePolicyActivationTargetV1 = NativeEmployeePolicyActivationTargetV1(coordinates(), digest(), digest(),
        id(), digest(), digest(), Revision(long()), head(), digest(), id(), id(), digest(), id(), id(), id(), string(64))
    private fun SelfOwnedWireWriterV1.preparedHead(value: NativeEmployeePolicyActivationHeadV1) {
        string(value.companyName); string(value.headName); string(value.headLogin); long(value.accountRevision); long(value.credentialRevision)
        long(value.retainedRevision); int(value.osBootCount); observation(value.observation); long(value.deadlineElapsedMillis)
    }
    private fun SelfOwnedWireReaderV1.preparedHead(): NativeEmployeePolicyActivationHeadV1 = NativeEmployeePolicyActivationHeadV1(string(640), string(640), string(64),
        long(), long(), long(), int(), observation(), long())
    private fun SelfOwnedWireWriterV1.approval(value: NativeEmployeePolicyActivationApprovalV1) { long(value.accountRevision); long(value.credentialRevision)
        long(value.retainedRevision); int(value.osBootCount); long(value.deadlineElapsedMillis) }
    private fun SelfOwnedWireReaderV1.approval(): NativeEmployeePolicyActivationApprovalV1 = NativeEmployeePolicyActivationApprovalV1(long(), long(), long(), int(), long())
    private fun SelfOwnedWireWriterV1.head(value: NativeHistoryRecordedHeadV2) { long(value.historySequence); digest(value.historyDigest); digest(value.installationGenesisDigest)
        long(value.commandEpoch); long(value.commandSequence); digest(value.commandDigest); digest(value.roleCommitment); digest(value.recoveryCommitment) }
    private fun SelfOwnedWireReaderV1.head(): NativeHistoryRecordedHeadV2 = NativeHistoryRecordedHeadV2(long(), digest(), digest(), long(), long(), digest(), digest(), digest())
    private fun SelfOwnedWireWriterV1.moment(value: MonotonicMoment) { id(value.bootId); long(value.elapsedMillis) }
    private fun SelfOwnedWireReaderV1.moment(): MonotonicMoment = MonotonicMoment(id(), long())
    private fun SelfOwnedWireWriterV1.observation(value: LocalAuthenticationObservation) { id(value.issuerContextId); moment(value.moment) }
    private fun SelfOwnedWireReaderV1.observation(): LocalAuthenticationObservation = LocalAuthenticationObservation(id(), moment())
    private fun SelfOwnedWireWriterV1.digest(value: PayloadDigest) { string(value.value) }
    private fun SelfOwnedWireReaderV1.digest(): PayloadDigest = PayloadDigest(string(64))
    private fun <T : Enum<T>> SelfOwnedWireReaderV1.named(values: List<T>): T { val name = string(128); return values.single { it.name == name } }
}
