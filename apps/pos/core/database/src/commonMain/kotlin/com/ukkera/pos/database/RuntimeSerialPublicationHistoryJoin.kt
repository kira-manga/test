package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Closed private requester history, separate from native-head approval and business Apply.
 * Canonical capsules remain DATA until the actual native final, chronological employee,
 * earlier exact origin and complete immutable lineage/current/tip have all joined. */
internal class RuntimeSerialPublicationHistoryJoin(private val session: SelfOwnedNativeHistoryReadSession) {
    private val coordinates = session.original.record.allocation.coordinates
    private val scope = coordinates.scope
    private class Entry(val proof: RuntimeSerialPublicationHistoryRequest,
        val request: NativeSerialPrivatePublicationRequestV1, val version: RuntimeHistoryRowVersionRowV2,
        val binding: ProtectedLocalBinding, val bytes: ByteArray) {
        var accounted = false
        var imageJoined = false
        var tipJoined = false
        var currentJoined = false
        var originalActionId: DomainId? = null
        fun clear() { version.key.fill(0); bytes.fill(0) }
    }
    private val requests = linkedMapOf<DomainId, Entry>()
    private val bySequence = linkedMapOf<Long, Entry>()
    private val origins = linkedMapOf<DomainId, Entry>()
    private val actionOrigins = linkedMapOf<DomainId, DomainId>()
    private val byKey = linkedMapOf<ProtectedLocalRecordKey, Entry>()
    private var enabled = true
    private var retainedBytes = 0L
    val eventCount: Long get() = requests.size.toLong()
    val originalEvents: Long get() = requests.values.count { it.proof.actorId == coordinates.firstActorId }.toLong()
    val employeeEvents: Long get() = eventCount - originalEvents
    val mutationCount: Long get() = eventCount
    var imageCount = 0L
        private set
    val tipCount: Long get() = requests.values.count { it.tipJoined }.toLong()

    fun event(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        evidence: RuntimeSerialPublicationRequestEvidenceV1, versions: List<RuntimeHistoryRowVersionRowV2>,
        finalDecision: NativeHistoryProtectedFinalDecision, original: RuntimeOriginalAuthenticationReplay?,
        employees: RuntimeEmployeeEnrollmentHistoryJoin): Boolean {
        historyShapeV2(enabled)
        evidence.validate()
        val request = evidence.requestValue()
        historyCapacityV2(requests.size < RuntimeBusinessHistoryJoin.MAX_VERSIONS)
        historyShapeV2(header.sequence > 1 && header.purpose == RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST &&
            header.changeCount == 1 && header.imageBytes == 0L && versions.size == 1 &&
            request.scope == scope && request.originalAllocationDigest == session.original.record.allocation.digest &&
            request.requestId !in requests && header.sequence !in bySequence)
        val origin = if (request.operation == NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE) {
            historyShapeV2(request.requestId !in origins)
            null
        } else {
            val retained = origins[checkNotNull(request.originRequestId)] ?: return false
            historyShapeV2(retained.proof.header.sequence < header.sequence &&
                retained.request.requestId == request.originRequestId && retained.proof.evidence.semanticDigest == request.originRequestDigest &&
                sameTarget(retained.request, request))
            if (retained.proof.actorId != evidence.authorization.actorId) return false
            retained
        }
        val originalActor = origin?.proof?.actorId ?: evidence.authorization.actorId
        val actor = if (originalActor == coordinates.firstActorId) original?.approvalActor(session, header)
            else employees.approvalActor(originalActor, header)
        if (actor == null) return false
        requireFinal(header, digest, evidence, finalDecision)
        if (!actor.serialPublicationRequest(session, header, evidence, originalActor,
                LocalAuthenticationObservation(evidence.authorization.access.issuerContextId, evidence.at)) ||
            !actor.serialPublicationRequest(session, header, evidence, originalActor, finalDecision.observed)) return false
        requireVersion(header, versions.single(), evidence.images.single())
        request.originalActionId?.let { actionId ->
            val retained = checkNotNull(origin)
            historyShapeV2(retained.originalActionId == null || retained.originalActionId == actionId)
            historyShapeV2(actionOrigins[actionId] == null || actionOrigins[actionId] == retained.request.requestId)
        }
        val entry = register(header, digest, evidence, request, versions.single())
        if (origin == null) origins[request.requestId] = entry
        else request.originalActionId?.let { actionId ->
            origin.originalActionId = actionId; actionOrigins[actionId] = origin.request.requestId
        }
        // Only this registered, final-bound serial proof advances audit accounting. It
        // changes no account/session/policy, creates no principal and replays no business.
        if (originalActor == coordinates.firstActorId) checkNotNull(original).serialPublicationRequest(entry.proof)
        else historyShapeV2(employees.serialPublicationRequest(entry.proof))
        return true
    }

    private fun sameTarget(origin: NativeSerialPrivatePublicationRequestV1,
        request: NativeSerialPrivatePublicationRequestV1): Boolean = origin.scope == request.scope &&
        origin.originalAllocationDigest == request.originalAllocationDigest && origin.kind == request.kind &&
        origin.publicationId == request.publicationId && origin.entityId == request.entityId && origin.contentDigest == request.contentDigest

    private fun requireFinal(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        evidence: RuntimeSerialPublicationRequestEvidenceV1, decision: NativeHistoryProtectedFinalDecision) {
        val facts = RuntimeSerialPublicationRequestEvidenceCodec.encode(evidence)
        try {
            val dependencies = RuntimeSerialPublicationRequestEvidenceCodec.dependencies(evidence)
            try {
                val intent = SelfOwnedNativeHistoryWireV2.encodeIntent(NativeHistoryOriginalIntentV2(
                    NativeSerialPrivatePublicationRequestCodecV1.PURPOSE_TAG, evidence.requestValue().requestId,
                    evidence.semanticDigest, null, evidence.authorization.actorId, sha256(dependencies), sha256(facts), emptyList()))
                try { historyShapeV2(header.factBytes == facts.size && header.factDigest == sha256(facts) &&
                    header.dependencyBytes == dependencies.size && header.dependencyDigest == sha256(dependencies) &&
                    header.intentBytes == intent.size && header.intentDigest == sha256(intent)) }
                finally { intent.fill(0) }
            } finally { dependencies.fill(0) }
        } finally { facts.fill(0) }
        historyShapeV2(session.original.record.protectedFinalDecisions.any { it === decision } && decision.scope == scope &&
            decision.originalAllocationDigest == session.original.record.allocation.digest &&
            decision.originalIntentDigest == header.intentDigest && decision.declaration == evidence.protectedFinalDeclaration() &&
            decision.child == NativeHistoryRecordedHeadV2(header.sequence, digest, header.installationGenesisDigest, header.commandEpoch,
                header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment))
    }

    private fun requireVersion(header: RuntimeHistoryEventHeaderV2, version: RuntimeHistoryRowVersionRowV2,
        image: RuntimeApprovalImageV1) {
        val key = image.key(scope)
        try { historyShapeV2(image.table == RuntimeHistoryTableV2.AUTH_EVENT && image.revision == Revision(0) &&
            image.mutation == RuntimeHistoryMutationKindV2.INSERT && version.sequence == header.sequence && version.ordinal == 0 &&
            version.tableTag == RuntimeHistoryTableV2.AUTH_EVENT.tag && version.key.contentEquals(key) &&
            version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && version.version == 1L &&
            version.previousSequence == null && version.previousOrdinal == null && version.previousVersion == null &&
            version.beforeRowId == null && version.beforeDigest == null && version.afterRowId != null && version.afterDigest != null &&
            version.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag && version.imageOffset == null && version.imageBytes == null) }
        finally { key.fill(0) }
    }

    private fun register(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        evidence: RuntimeSerialPublicationRequestEvidenceV1, request: NativeSerialPrivatePublicationRequestV1,
        version: RuntimeHistoryRowVersionRowV2): Entry {
        val canonical = RuntimeSerialPublicationRequestEvidenceCodec.encode(evidence)
        try { charge(canonical.size.toLong()) } finally { canonical.fill(0) }
        val copied = version.copy(key = version.key.copyOf())
        var ownedBytes: ByteArray? = null
        try {
            val bytes = LocalAuthenticationCodec.encodeEvent(evidence.event()); ownedBytes = bytes
            val wire = RuntimeHistoryCodecV2.encodeMutation(copied)
            try { charge(wire.size.toLong() + copied.key.size + bytes.size) } finally { wire.fill(0) }
            val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.AuthEvent(scope, request.requestId), Revision(0))
            historyShapeV2(binding.key !in byKey && evidence.images.single().matches(binding) &&
                sha256(bytes) == evidence.images.single().plaintextDigest)
            val entry = Entry(RuntimeSerialPublicationHistoryRequest.capture(this, header, digest, evidence), request, copied, binding, bytes)
            requests[request.requestId] = entry; bySequence[header.sequence] = entry; byKey[binding.key] = entry
            return entry
        } catch (failure: Throwable) { copied.key.fill(0); ownedBytes?.fill(0); throw failure }
    }

    fun evidence(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        version: RuntimeHistoryRowVersionRowV2): RuntimeSerialPublicationRequestEvidenceV1? = entry(header, digest, version)?.proof?.evidence

    private fun entry(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
        version: RuntimeHistoryRowVersionRowV2): Entry? {
        historyShapeV2(enabled)
        val selected = bySequence[header.sequence] ?: return null
        historyShapeV2(selected.proof.header == header && selected.proof.eventDigest == digest)
        requireSameVersion(selected.version, version)
        return selected
    }

    fun lineageImage(header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest, version: RuntimeHistoryRowVersionRowV2,
        binding: ProtectedLocalBinding, bytes: ByteArray): Boolean {
        val selected = entry(header, digest, version) ?: return false
        historyShapeV2(selected.accounted && !selected.imageJoined && selected.binding == binding &&
            selected.proof.evidence.images.single().matches(binding) && bytes.contentEquals(selected.bytes) &&
            sha256(bytes) == selected.proof.evidence.images.single().plaintextDigest)
        selected.imageJoined = true; imageCount = historyAddV2(imageCount, 1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
        return true
    }

    /** Generic traversal has already compared the actual tip's complete row/version tuple. */
    fun tip(version: RuntimeHistoryRowVersionRowV2) {
        historyShapeV2(enabled)
        val selected = bySequence[version.sequence] ?: return
        requireSameVersion(selected.version, version)
        historyShapeV2(selected.imageJoined && !selected.tipJoined)
        selected.tipJoined = true
    }

    fun current(binding: ProtectedLocalBinding, bytes: ByteArray): Boolean {
        historyShapeV2(enabled)
        val selected = byKey[binding.key] ?: return false
        historyShapeV2(selected.tipJoined && !selected.currentJoined && selected.binding == binding && bytes.contentEquals(selected.bytes))
        selected.currentJoined = true
        return true
    }

    internal fun accountFounding(proof: RuntimeSerialPublicationHistoryRequest, original: OriginalNativeHistorySnapshot) {
        historyShapeV2(original === session.original && session.hasChronologicalAccountImageTraversal &&
            proof.actorId == coordinates.firstActorId)
        account(proof)
    }

    internal fun accountEmployee(proof: RuntimeSerialPublicationHistoryRequest, source: NativeEmployeeEnrollmentHistorySource) {
        historyShapeV2(session.employeeEnrollments.any { it === source } && source.requested.request.targetId == proof.actorId &&
            source.requested.request.scope == scope && source.originalCohostDeviceId == coordinates.deviceId &&
            source.child.historySequence < proof.header.sequence && source.child.commandEpoch == proof.header.commandEpoch)
        account(proof)
    }

    private fun account(proof: RuntimeSerialPublicationHistoryRequest) {
        historyShapeV2(enabled)
        val selected = bySequence[proof.header.sequence] ?: malformed()
        historyShapeV2(selected.proof === proof && requests[selected.request.requestId] === selected && !selected.accounted)
        selected.accounted = true
    }

    fun requireClosure() {
        historyShapeV2(enabled && imageCount == mutationCount && tipCount == eventCount && requests.values.all {
            it.accounted && it.imageJoined && it.tipJoined && it.currentJoined })
    }

    fun clear() {
        enabled = false; requests.values.forEach { it.clear() }; requests.clear(); bySequence.clear()
        origins.clear(); actionOrigins.clear(); byKey.clear()
    }

    private fun charge(bytes: Long) { retainedBytes = historyAddV2(retainedBytes, bytes, RuntimeBusinessHistoryJoin.MAX_RETAINED_BYTES) }
    private fun requireSameVersion(a: RuntimeHistoryRowVersionRowV2, b: RuntimeHistoryRowVersionRowV2) {
        val left = RuntimeHistoryCodecV2.encodeMutation(a)
        try {
            val right = RuntimeHistoryCodecV2.encodeMutation(b)
            try { historyShapeV2(left.contentEquals(right)) } finally { right.fill(0) }
        } finally { left.fill(0) }
    }
    private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}

/** Registered audit-accounting DATA only; not an owned decision or transferable grant. */
internal class RuntimeSerialPublicationHistoryRequest private constructor(
    private val owner: RuntimeSerialPublicationHistoryJoin,
    val header: RuntimeHistoryEventHeaderV2,
    val eventDigest: PayloadDigest,
    val evidence: RuntimeSerialPublicationRequestEvidenceV1,
) {
    val actorId: DomainId get() = evidence.authorization.actorId
    fun accountFounding(original: OriginalNativeHistorySnapshot) = owner.accountFounding(this, original)
    fun accountEmployee(source: NativeEmployeeEnrollmentHistorySource) = owner.accountEmployee(this, source)

    companion object {
        fun capture(owner: RuntimeSerialPublicationHistoryJoin, header: RuntimeHistoryEventHeaderV2, digest: PayloadDigest,
            evidence: RuntimeSerialPublicationRequestEvidenceV1): RuntimeSerialPublicationHistoryRequest =
            RuntimeSerialPublicationHistoryRequest(owner, header, digest, evidence)
    }
}
