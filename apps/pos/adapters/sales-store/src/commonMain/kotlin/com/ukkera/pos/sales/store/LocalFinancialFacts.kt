package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PhysicalRecordingAction
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

internal suspend fun prepareRetailFact(target: LocalJournalTargetV3, phase: LocalJournalFactPhaseV3,
    normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
    if (context.operationId != target.factOperation(phase) || context.key != retailJournalCommandV3(target.kind) ||
        view.aggregate(retailJournalSlotKeyV3(target.recordingOperationId)) == null) return unavailableRetailFact(context)
    val slot = readRetailSlot(view, target.recordingOperationId)
    if (slot.value.target != target || phase == LocalJournalFactPhaseV3.ORIGINAL && slot.value.actorId != context.originalActorId) return unavailableRetailFact(context)
    val requirement = slot.value.requirement()
    val domain = retailJournalDomain(target.kind)
    val parent = if (phase == LocalJournalFactPhaseV3.RESOLUTION) {
        // Native retains the linked bytes pending when this SQL ancestor has not arrived.
        // No generic Applied result or synthesized original UNKNOWN substitutes for this fact.
        if (view.aggregate(retailJournalFactKeyV3(LocalJournalFactPhaseV3.ORIGINAL, target.recordingOperationId)) == null) {
            return unavailableRetailFact(context)
        }
        readRetailFact(view, target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL).also {
            check(it.slot.stored.digest == slot.stored.digest && domain.classifyOriginal(requirement, it.fact.value.body()) == LocalPhysicalOriginalKind.UNKNOWN)
        }
    } else null
    if (parent == null) domain.classifyOriginal(requirement, normalized)
    else {
        requireRetailResolutionRecorder(context, target, parent, normalized)
        domain.validateResolution(requirement, parent.fact.value.body(), context.originalActorId,
            retailResolutionDescriptorV3(requirement, parent.fact.value.body(), context.originalActorId, normalized), normalized)
    }
    check(view.aggregate(retailJournalFactKeyV3(phase, context.operationId)) == null &&
        view.aggregate(retailJournalApplicationKeyV3(context.operationId)) == null) { "sales.journal.partial_fact" }
    val admission = retailAdmission(requirement, if (phase == LocalJournalFactPhaseV3.ORIGINAL)
        PhysicalRecordingAction.RETAIN_ORIGINAL else PhysicalRecordingAction.RETAIN_RESOLUTION)
    return LocalJournalPrepared(context, retailFactAction(target.kind, phase), slot.value.id, Revision(0), slot.value.actorId,
        "financial.journal.retain", slot.value.domain.amount, retailJournalAuthorityResources(requirement),
        admission = { _, _ ->
            if (context.physicalParticipants?.let { it.authority.deviceId == requirement.masterDeviceId &&
                    it.clientDeviceId == requirement.clientDeviceId } == true) admission else null
        }) { _, now ->
        val physical = context.physicalRecording
        if (physical == null || !physical.matches(admission) || physical.sourceChanges.isNotEmpty()) {
            return@LocalJournalPrepared retailUnavailable(LocalJournalUnavailableV3.JOURNAL_REQUIRED)
        }
        val fact = LocalRetailJournalFactV3(3, LocalSalesScope.from(context.scope), target, phase, slot.value.actorId,
            context.originalActorId, normalized.copyBytes().decodeToString(throwOnInvalidSequence = true),
            retailJournalDigestV3(normalized).value, context.semanticDigest.value, slot.stored.digest.value,
            parent?.fact?.value?.bodyDigest, now)
        val pending = LocalRetailJournalApplicationV3(3, fact.scope, target, phase, context.originalActorId, Revision(0),
            LocalJournalApplicationStateV3.PENDING_RECONCILIATION, LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION,
            now, null, null, emptyList())
        // Exact two insert-only writes. No cash/order/refund/stock transition, claim release,
        // reservation/fence, source rewrite or implicit negative-money assertion is possible.
        ProposedOutcome.Apply(LocalFinancialResultCodecV3.encode(retailPending(target, phase,
            LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION)),
            listOf(retailFactWriteV3(fact), retailApplicationWriteV3(pending, null)))
    }
}

internal fun retailFactAction(kind: SalesRecordingKind, phase: LocalJournalFactPhaseV3): SecurityAction = when (kind) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> if (phase == LocalJournalFactPhaseV3.ORIGINAL) SecurityAction.COLLECTION_ATTEST else SecurityAction.COLLECTION_RESOLVE
    SalesRecordingKind.REFUND_ATTESTATION -> if (phase == LocalJournalFactPhaseV3.ORIGINAL) SecurityAction.REFUND_RECORD else SecurityAction.REFUND_RESOLVE
}

internal fun unavailableRetailFact(context: CommandContext): PreparedMutation = LocalJournalPrepared(context,
    SecurityAction.CASH_READ, DomainId(context.operationId.value), Revision(0), context.originalActorId,
    "financial.journal.unavailable", resources = setOf(recordingAuthority(context.originalActorId), recordingAuthority(DomainId(context.operationId.value)))) { _, _ ->
    ProposedOutcome.Reject("sales.journal.not_available", LocalFinancialResultCodecV3.encode(LocalFinancialResultV3.NotAvailable))
}

internal fun retailPending(target: LocalJournalTargetV3, phase: LocalJournalFactPhaseV3,
    reason: LocalJournalPendingReasonV3): LocalFinancialResultV3.Pending = LocalFinancialResultV3.Pending(target, phase, reason)

/** Positive immutable committed lineage, not a preexisting-row capacity/admission path for starts. */
internal suspend fun readRetailSlot(view: CommandReadView, recordingOperationId: OperationId): LocalSalesRow<LocalRetailJournalSlotV3> {
    val row = LocalSalesStorage.required(view, retailJournalSlotKeyV3(recordingOperationId), LocalFinancialJournalProfileV3.SLOT_SCHEMA)
    val slot = localSalesDecode(row.payload, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES)
    requireLocalSalesSource(slot.schemaVersion == 3 && row.revision == Revision(1) && slot.scope.value() == view.scope &&
        slot.target.recordingOperationId == recordingOperationId && retailSlotWriteV3(slot).payload == row.payload)
    val requirement = slot.requirement()
    retailJournalDomain(slot.target.kind).validateRequirement(requirement)
    val sourceRows = listOf(slot.masterAllocationId, slot.clientAllocationId).map { id ->
        LocalSalesStorage.required(view, AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, id), LocalPhysicalJournalFormatV1.SOURCE_SCHEMA)
            .also { requireLocalSalesSource(it.revision == Revision(1)) }
    }
    // Decoding old committed bytes proves only structural lineage. No native receipt/lease is
    // minted, and these values are NEVER supplied to retailJournalStartSourcesV3 or execute().
    val pair = requireRetailSourcePairV3(requirement, sourceRows.map { AggregateChange(it.key, null, it.schema, it.payload) })
    requireLocalSalesSource(slot.masterSource == retailSourceReferenceV3(pair.first) && slot.clientSource == retailSourceReferenceV3(pair.second))
    val origin = view.retainedCommand(slot.target.intentOperationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireRetailRetainedRowV3(origin, row)
    sourceRows.forEach { requireRetailRetainedRowV3(origin, it) }
    requireRetailStartEvidenceV3(origin, slot, commerceTenderPolicies(view))
    if (origin.aggregateWrites.any { it.key.kind == com.ukkera.pos.database.StockReadEvidenceV1.KIND })
        requireNormalizedPaymentOrigin(view, origin)
    requireLocalSalesSource(view.claim(LocalSalesStoreProfile.unique(
        if (slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) "payment_attempt" else "refund_attempt",
        slot.target.attemptId)) == slot.target.documentId)
    for (claim in retailLineageClaimsV3(requirement)) requireLocalSalesSource(view.claim(claim.key) == claim.ownerId)
    for (expected in retailFutureFencesV3(requirement)) {
        val fence = LocalSalesStorage.required(view, expected.key, expected.schema)
        requireLocalSalesSource(fence.revision == Revision(1) && fence.payload == expected.payload)
        requireRetailRetainedRowV3(origin, fence)
    }
    return LocalSalesRow(row, slot)
}

internal suspend fun readRetailFact(view: CommandReadView, operationId: OperationId,
    phase: LocalJournalFactPhaseV3): LocalRetailJournalFactCutV3 {
    val row = LocalSalesStorage.required(view, retailJournalFactKeyV3(phase, operationId),
        if (phase == LocalJournalFactPhaseV3.ORIGINAL) LocalFinancialJournalProfileV3.ORIGINAL_SCHEMA else LocalFinancialJournalProfileV3.RESOLUTION_SCHEMA)
    val fact = localSalesDecode(row.payload, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES)
    requireLocalSalesSource(fact.schemaVersion == 3 && row.revision == Revision(1) && fact.scope.value() == view.scope &&
        fact.phase == phase && fact.target.factOperation(phase) == operationId && retailFactWriteV3(fact).payload == row.payload)
    val slot = readRetailSlot(view, fact.target.recordingOperationId)
    val requirement = slot.value.requirement()
    val body = fact.body()
    requireLocalSalesSource(body.size in 1..LocalFinancialJournalProfileV3.MAX_REQUEST_BYTES && fact.target == slot.value.target &&
        fact.slotDigest == slot.stored.digest.value && fact.originalActorId == slot.value.actorId &&
        (phase == LocalJournalFactPhaseV3.RESOLUTION || fact.recordingActorId == fact.originalActorId) &&
        retailJournalDigestV3(body).value == fact.bodyDigest &&
        semanticDigest(view.scope, fact.recordingActorId, retailJournalCommandV3(fact.target.kind), body).value == fact.semanticDigest)
    val domain = retailJournalDomain(fact.target.kind)
    val parent = if (phase == LocalJournalFactPhaseV3.ORIGINAL) {
        requireLocalSalesSource(fact.originalBodyDigest == null)
        domain.classifyOriginal(requirement, body)
        null
    } else {
        val parent = readRetailFact(view, fact.target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
        requireLocalSalesSource(parent.slot.stored.digest == slot.stored.digest && parent.fact.value.bodyDigest == fact.originalBodyDigest)
        domain.validateResolution(requirement, parent.fact.value.body(), fact.recordingActorId,
            retailResolutionDescriptorV3(requirement, parent.fact.value.body(), fact.recordingActorId, body), body)
        parent
    }
    val origin = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireRetailRetainedRowV3(origin, row)
    requireRetailRawEvidenceV3(origin, fact, slot.value)
    if (origin.nativeOriginalProvenance != null) {
        val historicalStart = view.retainedCommand(slot.value.target.intentOperationId)
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireRetailNativeOriginalEvidence(origin, historicalStart, fact, slot.value, commerceTenderPolicies(view))
    }
    requireLocalSalesSource(origin.scope == view.scope && origin.originalActorId == fact.recordingActorId &&
        origin.command == retailJournalCommandV3(fact.target.kind) && origin.normalized == body &&
        origin.semanticDigest.value == fact.semanticDigest && origin.recordedAt == fact.recordedAt &&
        origin.claimChanges.isEmpty() && origin.jobs.isEmpty() && origin.aggregateWrites.map { it.key }.toSet() == setOf(row.key,
            retailJournalApplicationKeyV3(operationId)) && origin.aggregateWrites.size == 2 &&
        LocalFinancialResultCodecV3.decode(origin.result) == retailPending(fact.target, phase, LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION))
    val applicationRow = LocalSalesStorage.required(view, retailJournalApplicationKeyV3(operationId), LocalFinancialJournalProfileV3.APPLICATION_SCHEMA)
    val application = localSalesDecode(applicationRow.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
    requireLocalSalesSource(application.schemaVersion == 3 && application.scope == fact.scope && application.target == fact.target &&
        application.phase == phase && application.actorId == fact.recordingActorId && application.revision == applicationRow.domainRevision() &&
        application.financialRevisions.size <= LocalFinancialJournalProfileV3.MAX_FINANCIAL_WRITES &&
        application.financialRevisions.map { it.kind to it.id }.distinct().size == application.financialRevisions.size &&
        (if (application.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION)
            application.reason != null && application.appliedAction == null && application.financialRevisions.isEmpty() &&
                application.financialProof == null && applicationRow.payload.size <= LocalFinancialJournalProfileV3.MAX_PENDING_APPLICATION_BYTES
        else application.reason == null && application.appliedAction != null && application.lastApplicationOperationId != null && application.financialProof != null))
    if (application.revision == Revision(0)) {
        val expected = LocalRetailJournalApplicationV3(3, fact.scope, fact.target, fact.phase, fact.recordingActorId, Revision(0),
            LocalJournalApplicationStateV3.PENDING_RECONCILIATION, LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION,
            fact.recordedAt, null, null, emptyList())
        requireLocalSalesSource(application == expected)
        requireRetailRetainedRowV3(origin, applicationRow)
    } else {
        val update = view.retainedCommand(checkNotNull(application.lastApplicationOperationId))
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireRetailRetainedRowV3(update, applicationRow)
        if (application.state == LocalJournalApplicationStateV3.APPLIED && parent != null) {
            val parentApplied = parent.application.value.lastApplicationOperationId
                ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
            val predecessor = view.retainedCommand(parentApplied) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            // Sequence is not a global order across authority histories. The exact applied
            // parent reference and the actual same-writer source read provide the causal link.
            if (predecessor.assignmentId == update.assignmentId && predecessor.authorityEpoch == update.authorityEpoch) {
                requireLocalSalesSource(predecessor.sequence.value < update.sequence.value)
            }
        }
        requireRetailApplicationEvidenceV3(update, fact, application, slot.value, parent, commerceTenderPolicies(view))
        if (update.aggregateWrites.any { it.key.kind == com.ukkera.pos.database.StockReadEvidenceV1.KIND })
            requireNormalizedPaymentOrigin(view, update)
    }
    return LocalRetailJournalFactCutV3(slot, LocalSalesRow(row, fact), LocalSalesRow(applicationRow, application))
}

internal fun retailApplicationBody(kind: SalesRecordingKind, operationId: OperationId, phase: LocalJournalFactPhaseV3): StoredBytes = when (kind) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.ApplyObservation(operationId, phase))
    SalesRecordingKind.REFUND_ATTESTATION -> LocalReturnCommandCodecV3.encode(LocalReturnRequestV3.ApplyObservation(operationId, phase))
}
