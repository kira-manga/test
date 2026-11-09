package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

internal data class NativeEmployeeEnrollmentTargetV1(
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
        if (other !is NativeEmployeeEnrollmentTargetV1) return false
        val left = NativeEmployeeEnrollmentWireV1.targetBytes(this)
        val right = NativeEmployeeEnrollmentWireV1.targetBytes(other)
        return try { left.contentEquals(right) } finally { left.fill(0); right.fill(0) }
    }
    override fun hashCode(): Int {
        val bytes = NativeEmployeeEnrollmentWireV1.targetBytes(this)
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

internal data class NativeEmployeeEnrollmentHeadV1(
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

internal data class NativeEmployeeEnrollmentApprovalV1(
    val accountRevision: Long, val credentialRevision: Long, val retainedRevision: Long,
    val osBootCount: Int, val deadlineElapsedMillis: Long,
) {
    init { require(accountRevision >= 0 && credentialRevision >= 0 && retainedRevision >= 0 && osBootCount >= 0 && deadlineElapsedMillis >= 0) }
}

internal data class NativeEmployeeEnrollmentDispatchV1(
    val dispatchId: DomainId, val originalAnchorDigest: PayloadDigest,
    val observation: LocalAuthenticationObservation,
)

/** DATA only. The owning Room producer joins these hashes to the actual native-trigger INSERTs. */
public data class LocalEmployeeEnrollmentProtectedImage(
    public val table: ProtectedLocalTable, public val recordId: DomainId,
    public val dimension: String, public val plaintextDigest: PayloadDigest,
) {
    init {
        require(table in setOf(ProtectedLocalTable.ACCOUNT, ProtectedLocalTable.EFFECTIVE_POLICY,
            ProtectedLocalTable.AUTH_CONFIGURATION, ProtectedLocalTable.THROTTLE, ProtectedLocalTable.AUTH_EVENT))
        require(if (table == ProtectedLocalTable.THROTTLE) dimension == "account" ||
            dimension.startsWith("device:") && DomainId(dimension.removePrefix("device:")).value == dimension.removePrefix("device:")
            else dimension.isEmpty())
    }
}

/** Strict redacted definition/authority/dependency/image evidence, not a source of currentness. */
public class LocalEmployeeEnrollmentEffectEvidence(
    public val request: LocalProvisioningRequest, public val definitionDigest: PayloadDigest,
    public val authorityDigest: PayloadDigest, public val nativeBindingDigest: PayloadDigest,
    public val eventAt: MonotonicMoment, public val protectedPreflight: LocalAuthenticationObservation,
    public val absenceDependenciesDigest: PayloadDigest,
    admissionDigests: List<Pair<DomainId, PayloadDigest>>, images: List<LocalEmployeeEnrollmentProtectedImage>,
) {
    public val admissionDigests: List<Pair<DomainId, PayloadDigest>> = admissionDigests.toList()
    public val images: List<LocalEmployeeEnrollmentProtectedImage> = images.toList()
    init {
        require(request.action == LocalProvisioningAction.PROVISION_ACCOUNT && this.admissionDigests.size in 1..256 &&
            this.admissionDigests.map { it.first }.distinct().size == this.admissionDigests.size &&
            this.admissionDigests == this.admissionDigests.sortedBy { it.first.value })
        require(eventAt.bootId == protectedPreflight.moment.bootId && eventAt.elapsedMillis <= protectedPreflight.moment.elapsedMillis)
        val actor = request.targetId
        require(this.images.size == this.admissionDigests.size + 5 && this.images.map { Triple(it.table, it.recordId, it.dimension) }.distinct().size == this.images.size)
        require(this.images[0].let { it.table == ProtectedLocalTable.ACCOUNT && it.recordId == actor } &&
            this.images[1].let { it.table == ProtectedLocalTable.EFFECTIVE_POLICY && it.recordId == actor } &&
            this.images[2].let { it.table == ProtectedLocalTable.AUTH_CONFIGURATION && it.recordId == actor } &&
            this.images[3].let { it.table == ProtectedLocalTable.THROTTLE && it.recordId == actor && it.dimension == "account" } &&
            this.images.last().let { it.table == ProtectedLocalTable.AUTH_EVENT && it.recordId == request.requestId })
        require(this.images.subList(4, this.images.lastIndex).all { it.table == ProtectedLocalTable.THROTTLE && it.recordId == actor } &&
            this.images.subList(4, this.images.lastIndex).map { it.dimension } == this.admissionDigests.map { "device:${it.first.value}" })
    }
    public fun copyWireBytes(): ByteArray = NativeEmployeeEnrollmentWireV1.encodeEvidence(this)
    override fun toString(): String = "LocalEmployeeEnrollmentEffectEvidence([REDACTED])"
    public companion object {
        public fun decode(canonical: ByteArray): LocalEmployeeEnrollmentEffectEvidence {
            val result = NativeEmployeeEnrollmentWireV1.decodeEvidence(canonical)
            val bytes = result.copyWireBytes()
            try { require(bytes.contentEquals(canonical)) } finally { bytes.fill(0) }; return result
        }
    }
}

internal data class NativeEmployeeEnrollmentEffectV1(
    val frameId: DomainId, val child: NativeHistoryRecordedHeadV2, val originalIntentDigest: PayloadDigest,
    val dependenciesDigest: PayloadDigest, val originalFactsDigest: PayloadDigest,
    val evidence: SelfOwnedOctetsV1,
) {
    init {
        require(evidence.size in 1..LocalEmployeeEnrollmentProfileV1.MAXIMUM_COMPLETION_BYTES && child.historySequence > 1)
        val bytes = evidence.copyBytes()
        try { require(sha256(bytes) == originalFactsDigest); LocalEmployeeEnrollmentEffectEvidence.decode(bytes) }
        finally { bytes.fill(0) }
    }
}

internal enum class NativeEmployeeEnrollmentTerminalKindV1 { COMMITTED_JOINED_CHILD, KNOWN_NO_EMPLOYEE_EFFECT }
internal data class NativeEmployeeEnrollmentTerminalV1(
    val kind: NativeEmployeeEnrollmentTerminalKindV1, val settledAnchorDigest: PayloadDigest,
    val finalObservation: LocalAuthenticationObservation?,
) {
    init { require((kind == NativeEmployeeEnrollmentTerminalKindV1.COMMITTED_JOINED_CHILD) == (finalObservation != null)) }
}

internal data class NativeEmployeeEnrollmentRecordV1(
    val originalActionId: DomainId, val clientAllocationId: DomainId,
    val intentJournalId: DomainId, val intentJournalKeyId: DomainId,
    val target: NativeEmployeeEnrollmentTargetV1, val definition: SelfOwnedOctetsV1,
    val preparedHead: NativeEmployeeEnrollmentHeadV1,
    val phase: LocalEmployeeEnrollmentPhase,
    val approvedHead: NativeEmployeeEnrollmentApprovalV1? = null,
    val rootEndorsement: SelfOwnedOctetsV1 = SelfOwnedOctetsV1(ByteArray(0)),
    val dispatch: NativeEmployeeEnrollmentDispatchV1? = null,
    val effect: NativeEmployeeEnrollmentEffectV1? = null,
    val terminal: NativeEmployeeEnrollmentTerminalV1? = null,
    val journalEnrollmentStarted: Boolean = false,
) {
    init {
        val requested = requested(); val request = requested.request
        require(definition.size in 1..LocalEmployeeEnrollmentProfileV1.MAXIMUM_DEFINITION_BYTES && request.scope == target.coordinates.scope &&
            request.targetId != target.coordinates.firstActorId && request.scope == requested.definition.policy.scope &&
            target.coordinates.deviceId in requested.definition.admittedDeviceIds)
        require(listOf(originalActionId, clientAllocationId, intentJournalId, intentJournalKeyId, request.requestId, request.targetId).distinct().size == 6)
        approvedHead?.let {
            require(it.accountRevision == preparedHead.accountRevision && it.credentialRevision == preparedHead.credentialRevision &&
                it.osBootCount == preparedHead.osBootCount && it.retainedRevision >= preparedHead.retainedRevision &&
                it.deadlineElapsedMillis <= preparedHead.deadlineElapsedMillis &&
                it.deadlineElapsedMillis > preparedHead.observation.moment.elapsedMillis)
        }
        when (phase) {
            LocalEmployeeEnrollmentPhase.PREPARED -> require(approvedHead == null && rootEndorsement.size == 0 && dispatch == null && effect == null && terminal == null)
            LocalEmployeeEnrollmentPhase.SIGNING_INTENT -> require(approvedHead != null && rootEndorsement.size == 0 && dispatch == null && effect == null && terminal == null)
            LocalEmployeeEnrollmentPhase.APPROVED -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch == null && effect == null && terminal == null)
            LocalEmployeeEnrollmentPhase.DISPATCH_INTENT -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch != null && effect == null && terminal == null)
            LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch != null && effect != null && terminal == null)
            LocalEmployeeEnrollmentPhase.CONSUMED -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch != null && effect != null &&
                terminal?.kind == NativeEmployeeEnrollmentTerminalKindV1.COMMITTED_JOINED_CHILD)
            LocalEmployeeEnrollmentPhase.REJECTED -> require(approvedHead != null && rootEndorsement.size == 64 && dispatch != null &&
                terminal?.kind == NativeEmployeeEnrollmentTerminalKindV1.KNOWN_NO_EMPLOYEE_EFFECT)
        }
        require(!journalEnrollmentStarted || phase == LocalEmployeeEnrollmentPhase.CONSUMED)
    }
    fun requested(): LocalEmployeeEnrollmentDefinition {
        val bytes = definition.copyBytes()
        return try { LocalEmployeeEnrollmentDefinition.decode(bytes) } finally { bytes.fill(0) }
    }
    fun nativeIds(): List<DomainId> = listOf(originalActionId, clientAllocationId, intentJournalId, intentJournalKeyId,
        requested().request.requestId, requested().request.targetId) + listOfNotNull(dispatch?.dispatchId)
    override fun toString(): String = "NativeEmployeeEnrollmentRecordV1([REDACTED])"
}

internal data class NativeEmployeeEnrollmentStateV1(val generation: Long, val records: List<NativeEmployeeEnrollmentRecordV1>) {
    init {
        require(generation in 0 until Long.MAX_VALUE && records.size in 1..LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS)
        val ids = records.flatMap { it.nativeIds() }; require(ids.distinct().size == ids.size)
        require(records.count { it.phase in setOf(LocalEmployeeEnrollmentPhase.SIGNING_INTENT,
            LocalEmployeeEnrollmentPhase.DISPATCH_INTENT, LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT) } <= 1)
    }
    fun next(record: NativeEmployeeEnrollmentRecordV1): NativeEmployeeEnrollmentStateV1 {
        if (generation >= Long.MAX_VALUE - 1) selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        val old = records.singleOrNull { it.originalActionId == record.originalActionId }
        return copy(generation = generation + 1, records = if (old == null) records + record else records.map { if (it === old) record else it })
    }
    override fun toString(): String = "NativeEmployeeEnrollmentStateV1([REDACTED])"
}

/** Additive native file/AAD/signature domains, not a new legacy company-head purpose flag. */
internal object NativeEmployeeEnrollmentWireV1 {
    fun targetBytes(value: NativeEmployeeEnrollmentTargetV1): ByteArray =
        selfOwnedEncode("original-local-employee-target/v1") { target(value) }
    fun aad(original: LocalCompanyAuthorityBootstrapV1): ByteArray = selfOwnedEncode("company-employee-enrollment-registry-aad/v1") {
        string(LocalEmployeeEnrollmentProfileV1.PURPOSE)
        val originalBytes = LocalCompanyAuthorityWireV1.registryAad(original)
        try { field(originalBytes) } finally { originalBytes.fill(0) }
    }
    fun encode(value: NativeEmployeeEnrollmentStateV1): ByteArray = selfOwnedEncode("native-company-employee-enrollments/v1",
        LocalEmployeeEnrollmentProfileV1.MAXIMUM_PLAINTEXT_BYTES) {
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
            bool(record.journalEnrollmentStarted)
        }
    }
    fun decode(bytes: ByteArray): NativeEmployeeEnrollmentStateV1 = selfOwnedDecode(bytes, "native-company-employee-enrollments/v1") {
        val generation = long(); val count = int(); require(count in 1..LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS)
        NativeEmployeeEnrollmentStateV1(generation, List(count) {
            val action = id(); val client = id(); val journal = id(); val key = id(); val target = employeeTarget()
            val definition = octets(LocalEmployeeEnrollmentProfileV1.MAXIMUM_DEFINITION_BYTES); val prepared = preparedHead()
            val phase = named(LocalEmployeeEnrollmentPhase.entries); val approved = if (bool()) approval() else null
            val signature = octets(64); val dispatch = if (bool()) NativeEmployeeEnrollmentDispatchV1(id(), digest(), observation()) else null
            val effect = if (bool()) NativeEmployeeEnrollmentEffectV1(id(), head(), digest(), digest(), digest(),
                octets(LocalEmployeeEnrollmentProfileV1.MAXIMUM_COMPLETION_BYTES)) else null
            val terminal = if (bool()) NativeEmployeeEnrollmentTerminalV1(named(NativeEmployeeEnrollmentTerminalKindV1.entries), digest(),
                if (bool()) observation() else null) else null
            NativeEmployeeEnrollmentRecordV1(action, client, journal, key, target, definition, prepared, phase, approved, signature,
                dispatch, effect, terminal, bool())
        })
    }
    fun actionMessage(record: NativeEmployeeEnrollmentRecordV1): ByteArray = selfOwnedEncode("company-head-full-employee-action/v1") {
        string(LocalEmployeeEnrollmentProfileV1.PURPOSE); originalCommitment(record)
    }
    fun endorsementMessage(record: NativeEmployeeEnrollmentRecordV1): ByteArray = selfOwnedEncode("company-root-full-employee-approval/v1") {
        string(LocalEmployeeEnrollmentProfileV1.PURPOSE)
        string("NAMED_HEAD_NEW_LOCAL_EMPLOYEE_ONLY_NOT_CENTRAL_OR_BRANCH_EMPLOYEE_DELEGATION")
        originalCommitment(record); approval(checkNotNull(record.approvedHead))
    }
    fun authorityCertificate(record: NativeEmployeeEnrollmentRecordV1): ByteArray = selfOwnedEncode("native-local-employee-authority-certificate/v1") {
        require(record.rootEndorsement.size == 64)
        val body = endorsementMessage(record)
        try { field(body) } finally { body.fill(0) }; octets(record.rootEndorsement)
    }
    fun journalCustodyMessage(record: NativeEmployeeEnrollmentRecordV1): ByteArray =
        selfOwnedEncode("native-enrolled-employee-intent-journal-custody/v1", 32 * 1024) {
            require(record.phase == LocalEmployeeEnrollmentPhase.CONSUMED)
            string(LocalEmployeeEnrollmentProfileV1.PURPOSE); originalCommitment(record)
            approval(checkNotNull(record.approvedHead)); octets(record.rootEndorsement)
            val effect = checkNotNull(record.effect)
            id(effect.frameId); head(effect.child); digest(effect.originalIntentDigest)
            digest(effect.dependenciesDigest); digest(effect.originalFactsDigest)
            val terminal = checkNotNull(record.terminal)
            require(terminal.kind == NativeEmployeeEnrollmentTerminalKindV1.COMMITTED_JOINED_CHILD)
            string(terminal.kind.name); digest(terminal.settledAnchorDigest); observation(checkNotNull(terminal.finalObservation))
        }
    fun bindingMessage(frame: NativeEmployeeProvisioningFrame): ByteArray = selfOwnedEncode("actual-native-employee-provisioning-frame/v1") {
        string(LocalEmployeeEnrollmentProfileV1.PURPOSE); id(frame.native.frameId); digest(frame.native.original.recordDigest)
        id(frame.permit.approval.originalActionId); digest(frame.permit.approval.authorityDigest); observation(frame.bindingObservation)
    }
    fun encodeEvidence(value: LocalEmployeeEnrollmentEffectEvidence): ByteArray = selfOwnedEncode("runtime-native-full-employee-provisioning-evidence/v1",
        LocalEmployeeEnrollmentProfileV1.MAXIMUM_COMPLETION_BYTES) {
        string(LocalEmployeeEnrollmentProfileV1.PURPOSE); request(value.request); digest(value.definitionDigest); digest(value.authorityDigest)
        digest(value.nativeBindingDigest); moment(value.eventAt); observation(value.protectedPreflight); digest(value.absenceDependenciesDigest)
        int(value.admissionDigests.size); value.admissionDigests.forEach { (deviceId, commitment) -> id(deviceId); digest(commitment) }
        int(value.images.size); value.images.forEach { string(it.table.wireName); id(it.recordId); string(it.dimension); digest(it.plaintextDigest) }
    }
    fun decodeEvidence(bytes: ByteArray): LocalEmployeeEnrollmentEffectEvidence = selfOwnedDecode(bytes, "runtime-native-full-employee-provisioning-evidence/v1") {
        require(bytes.size in 1..LocalEmployeeEnrollmentProfileV1.MAXIMUM_COMPLETION_BYTES && string(128) == LocalEmployeeEnrollmentProfileV1.PURPOSE)
        val request = request(); val definition = digest(); val authority = digest(); val binding = digest()
        val event = moment(); val observed = observation(); val absence = digest(); val count = int(); require(count in 1..256)
        val admissions = List(count) { id() to digest() }; val imagesCount = int(); require(imagesCount == count + 5)
        val images = List(imagesCount) {
            val name = string(64); val table = ProtectedLocalTable.entries.single { it.wireName == name }
            LocalEmployeeEnrollmentProtectedImage(table, id(), string(64), digest())
        }
        LocalEmployeeEnrollmentEffectEvidence(request, definition, authority, binding, event, observed, absence, admissions, images)
    }
    fun encodeWithCompletionCapacity(value: NativeEmployeeEnrollmentStateV1): ByteArray {
        val bytes = encode(value)
        val reserve = value.records.count { it.phase !in setOf(LocalEmployeeEnrollmentPhase.CONSUMED, LocalEmployeeEnrollmentPhase.REJECTED) }.toLong() *
            LocalEmployeeEnrollmentProfileV1.MAXIMUM_COMPLETION_BYTES
        val revisions = value.records.sumOf { when (it.phase) {
            LocalEmployeeEnrollmentPhase.PREPARED -> 6L
            LocalEmployeeEnrollmentPhase.SIGNING_INTENT -> 5L
            LocalEmployeeEnrollmentPhase.APPROVED -> 4L
            LocalEmployeeEnrollmentPhase.DISPATCH_INTENT -> 3L
            LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT -> 2L
            LocalEmployeeEnrollmentPhase.CONSUMED -> if (it.journalEnrollmentStarted) 0L else 1L
            LocalEmployeeEnrollmentPhase.REJECTED -> 0L
        } }
        if (bytes.size.toLong() + reserve > LocalEmployeeEnrollmentProfileV1.MAXIMUM_PLAINTEXT_BYTES ||
            revisions > (Long.MAX_VALUE - 1L) - value.generation) {
            bytes.fill(0); selfOwnedFail(SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED)
        }
        return bytes
    }
    fun validateTransition(previous: NativeEmployeeEnrollmentStateV1?, next: NativeEmployeeEnrollmentStateV1) {
        if (previous == null) { require(next.generation == 0L && next.records.size == 1 && next.records.single().phase == LocalEmployeeEnrollmentPhase.PREPARED); return }
        require(next.generation == previous.generation + 1 && next.records.size in previous.records.size..previous.records.size + 1)
        if (next.records.size > previous.records.size) {
            require(next.records.take(previous.records.size) == previous.records && next.records.last().phase == LocalEmployeeEnrollmentPhase.PREPARED &&
                previous.records.none { it.phase in setOf(LocalEmployeeEnrollmentPhase.SIGNING_INTENT, LocalEmployeeEnrollmentPhase.DISPATCH_INTENT,
                    LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT) }); return
        }
        val changed = previous.records.zip(next.records).filter { (a, b) -> a != b }; require(changed.size == 1)
        val (before, after) = changed.single()
        require(before.originalActionId == after.originalActionId && before.clientAllocationId == after.clientAllocationId &&
            before.intentJournalId == after.intentJournalId && before.intentJournalKeyId == after.intentJournalKeyId &&
            before.target == after.target && before.definition == after.definition && before.preparedHead == after.preparedHead)
        if (before.phase == LocalEmployeeEnrollmentPhase.CONSUMED) {
            require(!before.journalEnrollmentStarted && after == before.copy(journalEnrollmentStarted = true)); return
        }
        require(!after.journalEnrollmentStarted && !before.journalEnrollmentStarted)
        when (before.phase) {
            LocalEmployeeEnrollmentPhase.PREPARED -> require(after == before.copy(
                phase = LocalEmployeeEnrollmentPhase.SIGNING_INTENT, approvedHead = after.approvedHead))
            LocalEmployeeEnrollmentPhase.SIGNING_INTENT -> require(after == before.copy(phase = LocalEmployeeEnrollmentPhase.APPROVED,
                rootEndorsement = after.rootEndorsement))
            LocalEmployeeEnrollmentPhase.APPROVED -> require(after == before.copy(phase = LocalEmployeeEnrollmentPhase.DISPATCH_INTENT, dispatch = after.dispatch))
            LocalEmployeeEnrollmentPhase.DISPATCH_INTENT -> require(after == before.copy(phase = after.phase, effect = after.effect, terminal = after.terminal) &&
                after.phase in setOf(LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT, LocalEmployeeEnrollmentPhase.REJECTED))
            LocalEmployeeEnrollmentPhase.CONSUMPTION_INTENT -> require(after == before.copy(phase = after.phase, terminal = after.terminal) &&
                after.phase in setOf(LocalEmployeeEnrollmentPhase.CONSUMED, LocalEmployeeEnrollmentPhase.REJECTED))
            LocalEmployeeEnrollmentPhase.REJECTED -> error("Rejected original employee action is immutable")
            LocalEmployeeEnrollmentPhase.CONSUMED -> error("Consumed original employee action is immutable")
        }
    }
    private fun SelfOwnedWireWriterV1.original(value: NativeEmployeeEnrollmentRecordV1) {
        id(value.originalActionId); id(value.clientAllocationId); id(value.intentJournalId); id(value.intentJournalKeyId)
        target(value.target); octets(value.definition); preparedHead(value.preparedHead)
    }
    private fun SelfOwnedWireWriterV1.originalCommitment(value: NativeEmployeeEnrollmentRecordV1) {
        id(value.originalActionId); id(value.clientAllocationId); id(value.intentJournalId); id(value.intentJournalKeyId)
        target(value.target); val requested = value.requested(); request(requested.request); digest(requested.definitionDigest); preparedHead(value.preparedHead)
    }
    private fun SelfOwnedWireWriterV1.request(value: LocalProvisioningRequest) { id(value.scope.organizationId); id(value.scope.companyId); id(value.scope.branchId)
        id(value.requestId); string(value.action.name); id(value.targetId); digest(value.contentDigest) }
    private fun SelfOwnedWireReaderV1.request(): LocalProvisioningRequest = LocalProvisioningRequest(BranchScope(id(), id(), id()), id(),
        named(LocalProvisioningAction.entries), id(), digest())
    private fun SelfOwnedWireWriterV1.target(value: NativeEmployeeEnrollmentTargetV1) {
        coordinates(value.coordinates); digest(value.allocationDigest); digest(value.bindingDigest); id(value.installationRequestId)
        digest(value.installationDefinitionDigest); digest(value.installationReceiptDigest); long(value.authorityEpoch.value)
        head(value.preparationParent); digest(value.preparationAnchorDigest); id(value.rootId); id(value.rootKeyId); digest(value.rootDocumentDigest)
        id(value.foundingActionId); id(value.headAuthorityId); id(value.headEnrollmentId); string(value.rootFingerprint)
    }
    private fun SelfOwnedWireReaderV1.employeeTarget(): NativeEmployeeEnrollmentTargetV1 = NativeEmployeeEnrollmentTargetV1(coordinates(), digest(), digest(),
        id(), digest(), digest(), Revision(long()), head(), digest(), id(), id(), digest(), id(), id(), id(), string(64))
    private fun SelfOwnedWireWriterV1.preparedHead(value: NativeEmployeeEnrollmentHeadV1) {
        string(value.companyName); string(value.headName); string(value.headLogin); long(value.accountRevision); long(value.credentialRevision)
        long(value.retainedRevision); int(value.osBootCount); observation(value.observation); long(value.deadlineElapsedMillis)
    }
    private fun SelfOwnedWireReaderV1.preparedHead(): NativeEmployeeEnrollmentHeadV1 = NativeEmployeeEnrollmentHeadV1(string(640), string(640), string(64),
        long(), long(), long(), int(), observation(), long())
    private fun SelfOwnedWireWriterV1.approval(value: NativeEmployeeEnrollmentApprovalV1) { long(value.accountRevision); long(value.credentialRevision)
        long(value.retainedRevision); int(value.osBootCount); long(value.deadlineElapsedMillis) }
    private fun SelfOwnedWireReaderV1.approval(): NativeEmployeeEnrollmentApprovalV1 = NativeEmployeeEnrollmentApprovalV1(long(), long(), long(), int(), long())
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
