package com.ukkera.pos.database

import com.ukkera.pos.security.*

/** One bounded crosswalk for native-selected complete policy actions. There is no policy
 * prepass or new reader grammar: ordinary EVENTS binds the original signed source and exact
 * prepared/final child before ordered replay; exhaustive LINEAGE closes every actual physical
 * BEFORE/AFTER and native-opened plaintext. Selection/census/counts alone grant nothing. */
internal class RuntimeEmployeePolicyActivationHistoryJoin(private val session: SelfOwnedNativeHistoryReadSession) {
    private val coordinates = session.original.record.allocation.coordinates
    private val scope = coordinates.scope
    private class Entry(val source: NativeEmployeePolicyActivationHistorySource) {
        var header: RuntimeHistoryEventHeaderV2? = null
        val versions = mutableListOf<RuntimeHistoryRowVersionRowV2>()
        var targetOwner: RuntimeEmployeePolicyActivationTargetOwner? = null
        val lineage = BooleanArray(source.evidence.images.size)
        val plaintext = BooleanArray(source.evidence.images.size)
        fun clear() { versions.forEach { it.key.fill(0) }; versions.clear(); header = null; targetOwner = null }
    }
    private val entries = session.employeePolicyActivations.map(::Entry)
    private val bySequence = entries.associateBy { it.source.child.historySequence }
    private var enabled = true
    val sourceCount: Int get() = entries.size
    var eventCount = 0L
        private set
    var mutationCount = 0L
        private set
    var originalImages = 0L
        private set
    var employeeImages = 0L
        private set

    init {
        historyShapeV2(entries.size <= LocalEmployeePolicyActivationProfileV1.MAXIMUM_ACTIONS && bySequence.size == entries.size &&
            entries.map { it.source.originalActionId }.distinct().size == entries.size &&
            (session.hasEmployeePolicyHistoryTraversal || entries.isEmpty()))
        for (entry in entries) historyShapeV2(entry.source.requested.request.scope == scope &&
            entry.source.child.historySequence > 1L && entry.source.child.commandEpoch ==
                checkNotNull(session.original.record.definition).definition.authorityEpoch.value)
    }

    /** Return only the authentic, complete source for this actual cut, never a ledger lookup
     * promoted directly to replay. Original formats without this source remain unsupported. */
    fun event(header: RuntimeHistoryEventHeaderV2, commitment: PayloadDigest, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2, mutations: List<RuntimeHistoryRowVersionRowV2>,
        previous: RuntimeHistoryEventHeaderV2, previousDigest: PayloadDigest): NativeEmployeePolicyActivationHistorySource? {
        val entry = bySequence[header.sequence] ?: return null
        val source = entry.source
        val request = source.requested.request
        historyShapeV2(entry.header == null && entry.versions.isEmpty() &&
            header.purpose == RuntimeWritePurposeV2.PROVISIONING && header.transactionId == source.frameId &&
            recordedHead(header,commitment) == source.child && recordedHead(previous,previousDigest) == source.preparationParent &&
            header.parentKind == RuntimeHistoryParentKindV2.RECORDED_EVENT.tag &&
            header.parentSequence == source.preparationParent.historySequence && header.parentDigest == source.preparationParent.historyDigest &&
            header.changeCount == source.evidence.images.size && mutations.size == header.changeCount &&
            intent.purposeTag == header.purpose.tag && intent.originalRequestId == request.requestId &&
            intent.originalRequestDigest == request.contentDigest && intent.originalOperationId == null && intent.originalActorId == null &&
            intent.resources.isEmpty() && sha256(capsule.intent) == source.originalIntentDigest)
        val dependencies = localEmployeePolicyActivationDependenciesV1(source.evidence)
        val facts = source.evidence.copyWireBytes()
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies) && facts.contentEquals(capsule.facts)) }
        finally { dependencies.fill(0); facts.fill(0) }
        for ((index, version) in mutations.withIndex()) {
            requireMutation(version,source.evidence.images[index],index)
            entry.versions += version.copy(key = version.key.copyOf())
        }
        entry.header = header
        return source
    }

    fun reduced(source: NativeEmployeePolicyActivationHistorySource, owner: RuntimeEmployeePolicyActivationTargetOwner) {
        val entry = bySequence[source.child.historySequence] ?: malformed()
        historyShapeV2(enabled && entry.source === source && entry.header != null && entry.targetOwner == null &&
            (owner == RuntimeEmployeePolicyActivationTargetOwner.ORIGINAL) == (source.requested.request.targetId == coordinates.firstActorId))
        entry.targetOwner = owner
        eventCount = add(eventCount,1L); mutationCount = add(mutationCount,source.evidence.images.size.toLong())
    }

    fun lineageSourceImage(version: RuntimeHistoryRowVersionRowV2): LocalEmployeePolicyActivationProtectedImage? {
        val entry = bySequence[version.sequence] ?: return null
        historyShapeV2(entry.header != null)
        val retained = entry.versions.getOrNull(version.ordinal) ?: malformed()
        requireSameVersion(version,retained)
        return entry.source.evidence.images[version.ordinal]
    }
    fun lineageSourceEvent(version: RuntimeHistoryRowVersionRowV2, row: RuntimeHistoryEventRowV2,
        header: RuntimeHistoryEventHeaderV2, previousPlaintextDigest: PayloadDigest?) {
        val entry = bySequence[version.sequence] ?: return
        val expected = lineageSourceImage(version) ?: malformed()
        historyShapeV2(entry.header == header && row.eventDigest == entry.source.child.historyDigest.value &&
            !entry.lineage[version.ordinal] && !entry.plaintext[version.ordinal] &&
            previousPlaintextDigest == expected.previousPlaintextDigest)
        entry.lineage[version.ordinal] = true
    }
    fun lineageSourcePlaintext(version: RuntimeHistoryRowVersionRowV2, binding: ProtectedLocalBinding,
        bytes: ByteArray, value: Any) {
        val entry = bySequence[version.sequence] ?: return
        val expected = lineageSourceImage(version) ?: malformed()
        historyShapeV2(entry.lineage[version.ordinal] && !entry.plaintext[version.ordinal] &&
            binding.key.scope == scope && binding.key.table == expected.table && binding.key.recordId == expected.recordId &&
            binding.revision == expected.revision && sha256(bytes) == expected.plaintextDigest)
        val source = entry.source
        when (expected.table) {
            ProtectedLocalTable.EFFECTIVE_POLICY -> {
                historyShapeV2(value is ActivatedLocalPolicy)
                val retained = source.requested.copyCanonicalPolicy()
                val semantic = LocalEffectivePolicyCodec.encodePolicy(source.requested.nextPolicy)
                try { historyShapeV2(bytes.contentEquals(retained) && bytes.contentEquals(semantic)) }
                finally { retained.fill(0); semantic.fill(0) }
            }
            ProtectedLocalTable.ACCOUNT -> historyShapeV2(value is ProtectedLocalAccount)
            ProtectedLocalTable.AUTH_EVENT -> {
                val request = source.requested.request
                val event = LocalAuthEvent(scope,request.requestId,LocalAuthEventKind.POLICY_ACTIVATED,request.targetId,
                    null,null,source.evidence.afterAccountRevision,source.evidence.eventAt,request.contentDigest)
                requireRuntimePolicyActivationAuditValue(event, bytes, value)
            }
            else -> malformed()
        }
        entry.plaintext[version.ordinal] = true
        if (entry.targetOwner == RuntimeEmployeePolicyActivationTargetOwner.ORIGINAL) originalImages = add(originalImages,1L)
        else if (entry.targetOwner == RuntimeEmployeePolicyActivationTargetOwner.ENROLLED_EMPLOYEE) employeeImages = add(employeeImages,1L)
    }
    /** Existing ACCOUNT/AUTH_EVENT capsule traversal also re-joins the exact policy action.
     * POLICY itself needs no additional read: its full ordinary event was already bound. */
    fun lineageSourceCapsule(version: RuntimeHistoryRowVersionRowV2, capsule: RuntimeHistoryCapsule,
        intent: NativeHistoryOriginalIntentV2) {
        val entry = bySequence[version.sequence] ?: return
        val source = entry.source
        val request = source.requested.request
        historyShapeV2(entry.lineage[version.ordinal] && intent.purposeTag == RuntimeWritePurposeV2.PROVISIONING.tag &&
            intent.originalRequestId == request.requestId && intent.originalRequestDigest == request.contentDigest &&
            intent.originalOperationId == null && intent.originalActorId == null && intent.resources.isEmpty() &&
            sha256(capsule.intent) == source.originalIntentDigest)
        val dependencies = localEmployeePolicyActivationDependenciesV1(source.evidence)
        val facts = source.evidence.copyWireBytes()
        try { historyShapeV2(dependencies.contentEquals(capsule.dependencies) && facts.contentEquals(capsule.facts)) }
        finally { dependencies.fill(0); facts.fill(0) }
    }

    fun finish() {
        historyShapeV2(enabled && entries.all { it.header != null && it.targetOwner != null &&
            it.versions.size == it.source.evidence.images.size && it.lineage.all { it } && it.plaintext.all { it } } &&
            eventCount == entries.size.toLong() && mutationCount == originalImages + employeeImages)
    }
    fun abandon() { enabled = false }
    fun clear() { entries.forEach { it.clear() }; enabled = false }

    private fun requireMutation(row: RuntimeHistoryRowVersionRowV2, expected: LocalEmployeePolicyActivationProtectedImage, ordinal: Int) {
        val key = expected.runtimePolicyKey(scope)
        val table = RuntimeHistoryTableV2.entries.single { it.protectedTable == expected.table }
        val insert = table.immutable
        try {
            historyShapeV2(row.ordinal == ordinal && row.tableTag == table.tag && row.key.contentEquals(key) &&
                row.version == expected.revision.value + 1L && row.afterRowId != null && row.afterDigest != null &&
                row.mutationKind == (if (insert) RuntimeHistoryMutationKindV2.INSERT.tag else RuntimeHistoryMutationKindV2.UPDATE.tag) &&
                row.afterKind == (if (insert) RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag else RuntimeHistoryAfterKindV2.IMAGE.tag))
            if (insert) historyShapeV2(row.previousSequence == null && row.previousOrdinal == null && row.previousVersion == null &&
                row.beforeRowId == null && row.beforeDigest == null && row.imageOffset == null && row.imageBytes == null)
            else historyShapeV2(row.previousSequence != null && checkNotNull(row.previousSequence) < row.sequence &&
                row.previousOrdinal != null && row.previousVersion == expected.revision.value &&
                row.beforeRowId != null && row.beforeRowId == row.afterRowId && row.beforeDigest != null &&
                row.imageOffset != null && row.imageBytes != null)
        } finally { key.fill(0) }
    }
    private fun requireSameVersion(left: RuntimeHistoryRowVersionRowV2, right: RuntimeHistoryRowVersionRowV2) {
        val a = RuntimeHistoryCodecV2.encodeMutation(left); val b = RuntimeHistoryCodecV2.encodeMutation(right)
        try { historyShapeV2(a.contentEquals(b)) } finally { a.fill(0); b.fill(0) }
    }
    private fun recordedHead(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest): NativeHistoryRecordedHeadV2 =
        NativeHistoryRecordedHeadV2(header.sequence,digest,header.installationGenesisDigest,header.commandEpoch,header.commandSequence,
            header.commandDigest,header.roleCommitment,header.recoveryCommitment)
    private fun add(left: Long, right: Long): Long = historyAddV2(left,right,RuntimeHistoryProfileV2.MAX_MUTATIONS)
    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}

/** A decoded event is a value, not the same object as its original literal. This
 * comparison follows the native source/lineage/binding/digest checks above; it
 * cannot select a source or grant authority from caller-supplied event DATA. */
internal fun requireRuntimePolicyActivationAuditValue(expected: LocalAuthEvent, bytes: ByteArray, value: Any) {
    historyShapeV2(expected.kind == LocalAuthEventKind.POLICY_ACTIVATED && value is LocalAuthEvent)
    val canonical = LocalAuthenticationCodec.encodeEvent(expected)
    try {
        val decoded = LocalAuthenticationCodec.encodeEvent(value as LocalAuthEvent)
        try { historyShapeV2(bytes.contentEquals(canonical) && decoded.contentEquals(canonical)) }
        finally { decoded.fill(0) }
    } finally { canonical.fill(0) }
}
