package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Source-only retained disclosure. Native BEFORE pairing and the unchanged complete typed
 * Start/raw/application validators are both necessary. No current aggregate, prepare,
 * evaluate, native lease or physical instruction is used to recover an original result. */
internal suspend fun retailJournalRpcOriginalRequirements(normalized: StoredBytes, original: RetainedOriginalV2,
    view: CommandReadView, context: CommandContext,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1): List<AuthorizationRequirement>? {
    val evidence = (original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
    if (view.scope != context.scope || evidence.scope != context.scope || evidence.command != context.key ||
        evidence.operationId != context.operationId || evidence.originalActorId != context.originalActorId ||
        evidence.semanticDigest != context.semanticDigest || evidence.normalized != normalized ||
        evidence.nativeOriginalProvenance != null || semanticDigest(context.scope, context.originalActorId,
            context.key, normalized) != context.semanticDigest) return null
    return listOf(RetailJournalOriginalProof(view, tenderPolicies).require(evidence))
}

internal class RetailJournalOriginalProof(private val view: CommandReadView,
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1) {
    private val active = mutableSetOf<OperationId>()
    private val completed = mutableMapOf<OperationId, Pair<PayloadDigest, AuthorizationRequirement>>()
    private var bytes = 0L

    suspend fun require(original: RetainedCommandEvidence): AuthorizationRequirement {
        completed[original.operationId]?.let { check(it.first == original.journalHash); return it.second }
        check(active.size + completed.size < 256 && active.add(original.operationId))
        bytes += original.normalized.size + original.result.size + original.aggregateWrites.sumOf { it.payload.size.toLong() }
        check(bytes <= 16_777_216L)
        try {
            val sources = view.financialOriginalSources(original.operationId) ?: error("sales.journal.original_sources_required")
            bytes += sources.retainedDataBytes
            check(bytes <= 16_777_216L)
            val actual = sources.evidence
            check(actual.scope == original.scope && actual.operationId == original.operationId && actual.command == original.command &&
                actual.originalActorId == original.originalActorId && actual.semanticDigest == original.semanticDigest &&
                actual.normalized == original.normalized && actual.journalHash == original.journalHash && actual.result == original.result &&
                actual.resultHash == original.resultHash && actual.committed == original.committed && actual.code == original.code)
            for (source in sources.beforeAggregateSources.filter { it.row.key.kind == CashNonSaleStoreProfile.SESSION_KIND })
                LocalFinancialCashBeforeRead(sources, view).cash(source.row.key.id)
            val startRequest = when (original.command) {
                LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> LocalCollectionCommandCodecV3.decode(original.normalized) as? LocalCollectionRequestV3.Start
                LocalFinancialJournalProfileV3.RETURN_COMMAND -> LocalReturnCommandCodecV3.decode(original.normalized) as? LocalReturnRequestV3.Start
                else -> null
            }
            if (startRequest != null) {
                check(original.committed && original.code == "committed")
                val result = LocalFinancialResultCodecV3.decode(original.result) as LocalFinancialResultV3.AttemptStarted
                check(sources.mustCommitBefore == result.mustCommitBefore)
                val write = original.aggregateWrites.single { it.key == retailJournalSlotKeyV3(result.target.recordingOperationId) }
                val slot = localSalesDecode(write.payload, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES)
                requireRetailStartEvidenceV3(original, slot, tenderPolicies)
                for (imageWrite in original.aggregateWrites.filter { it.key.kind == LocalFinancialJournalProfileV3.BEFORE_KIND }) {
                    val image = localSalesDecode(imageWrite.payload, LocalRetailBeforeV3.serializer(), LocalFinancialJournalProfileV3.MAX_BEFORE_BYTES)
                    val actualBefore = sources.aggregateSource(AggregateKey(image.sourceKind, image.sourceId))?.row
                        ?: error("sales.journal.start_source_required")
                    check(image.capturingOperationId == original.operationId && image.scope.value() == original.scope &&
                        image.storageRevision == actualBefore.revision && image.sourceSchema == actualBefore.schema &&
                        image.sourceDigest == actualBefore.digest.value && image.body() == actualBefore.payload)
                }
                if (original.aggregateWrites.any { it.key.kind == StockReadEvidenceV1.KIND }) requireNormalizedPaymentOrigin(view, original)
                val beforeKind = if (slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalSalesStoreProfile.COLLECTION_KIND else LocalSalesStoreProfile.RETURN_KIND
                val before = sources.aggregateSource(AggregateKey(beforeKind, slot.target.documentId))?.row ?: error("sales.journal.start_before_required")
                val expected = requirement(original, retailFactAction(slot.target.kind, LocalJournalFactPhaseV3.ORIGINAL),
                    slot.target.documentId, Revision(before.revision.value - 1), slot.actorId,
                    if (slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) "collection.attempt.start" else "refund.start", slot.domain.amount)
                check(sources.requirement == expected)
                completed[original.operationId] = original.journalHash to expected
                return expected
            }
            val resolutionTarget = when (original.command) {
                LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> (LocalCollectionCommandCodecV3.decode(original.normalized) as? LocalCollectionRequestV3.RecordResolution)?.target
                LocalFinancialJournalProfileV3.RETURN_COMMAND -> (LocalReturnCommandCodecV3.decode(original.normalized) as? LocalReturnRequestV3.RecordResolution)?.target
                else -> null
            }
            if (resolutionTarget != null) {
                check(original.committed && original.operationId == resolutionTarget.resolutionOperationId)
                val parent = LocalRetailSupervisorReviewHistorySources(tenderPolicies).fact(sources,
                    resolutionTarget.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
                val slot = parent.slot.value
                check(slot.target == resolutionTarget)
                val write = original.aggregateWrites.single { it.key == retailJournalFactKeyV3(LocalJournalFactPhaseV3.RESOLUTION, original.operationId) }
                val fact = localSalesDecode(write.payload, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES)
                requireRetailRawEvidenceV3(original, fact, slot)
                val requirement = slot.requirement()
                val domain = retailJournalDomain(slot.target.kind)
                check(domain.classifyOriginal(requirement, parent.fact.value.body()) == LocalPhysicalOriginalKind.UNKNOWN)
                domain.validateResolution(requirement, parent.fact.value.body(), original.originalActorId,
                    retailResolutionDescriptorV3(requirement, parent.fact.value.body(), original.originalActorId, original.normalized), original.normalized)
                check(sources.aggregateSource(retailJournalFactKeyV3(LocalJournalFactPhaseV3.RESOLUTION, original.operationId)) == null &&
                    sources.aggregateSource(retailJournalApplicationKeyV3(original.operationId)) == null)
                val expected = requirement(original, retailFactAction(slot.target.kind, LocalJournalFactPhaseV3.RESOLUTION), slot.id,
                    Revision(0), slot.actorId, "financial.journal.retain", slot.domain.amount)
                check(sources.requirement == expected)
                completed[original.operationId] = original.journalHash to expected
                return expected
            }
            val selected = selection(original)
            val reader = LocalRetailSupervisorReviewHistorySources(tenderPolicies)
            val fact = reader.fact(sources, selected.operation, selected.phase)
            val slot = fact.slot.value
            check(selected.kind == null || selected.kind == slot.target.kind)
            val supervised = original.command == LocalRetailSupervisorCodecV1.COMMAND
            check(supervised || slot.actorId == original.originalActorId && fact.fact.value.recordingActorId == slot.actorId)
            // Earlier bookkeeping is not shape-only authority. Rejoin every preceding Apply
            // used by this BEFORE cut using its own genuine native observations.
            for (source in sources.beforeAggregateSources.filter { it.row.key.kind == LocalFinancialJournalProfileV3.APPLICATION_KIND }) {
                val previous = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("sales.journal.application_original_required")
                if (previous.command == LocalRetailSupervisorCodecV1.COMMAND ||
                    previous.command == LocalFinancialJournalProfileV3.COLLECTION_COMMAND &&
                    LocalCollectionCommandCodecV3.decode(previous.normalized) is LocalCollectionRequestV3.ApplyObservation ||
                    previous.command == LocalFinancialJournalProfileV3.RETURN_COMMAND &&
                    LocalReturnCommandCodecV3.decode(previous.normalized) is LocalReturnRequestV3.ApplyObservation) require(previous)
            }
            val start = view.retainedCommand(slot.target.intentOperationId) ?: error("sales.journal.start_required")
            requireRetailStartEvidenceV3(start, slot, tenderPolicies)
            if (start.aggregateWrites.any { it.key.kind == StockReadEvidenceV1.KIND }) requireNormalizedPaymentOrigin(view, start)
            val expected = if (selected.review) {
                check(!original.committed && original.code == "sales.supervisor.reviewed" && original.aggregateWrites.isEmpty() &&
                    original.claimChanges.isEmpty() && original.jobs.isEmpty())
                val resolution = reader.resolution(sources, fact)
                val result = LocalRetailSupervisorCodecV1.decodeReview(original.result)
                check(result.original == summary(fact) && result.resolution == resolution?.let(::summary) &&
                    result.payment == fact.takeIf { slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION }?.let { retailPaymentObservation(it.fact.value) } &&
                    result.refund == fact.takeIf { slot.target.kind == SalesRecordingKind.REFUND_ATTESTATION }?.let { retailRefundObservation(it.fact.value) } &&
                    result.resolutionPayment == resolution?.takeIf { slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION }?.let { retailPaymentObservation(it.fact.value) } &&
                    result.resolutionRefund == resolution?.takeIf { slot.target.kind == SalesRecordingKind.REFUND_ATTESTATION }?.let { retailRefundObservation(it.fact.value) })
                requirement(original, retailFactAction(slot.target.kind, LocalJournalFactPhaseV3.RESOLUTION), slot.id,
                    Revision(0), slot.actorId, "financial.supervisor.review", slot.domain.amount)
            } else if (!original.committed) {
                check(original.code == "sales.journal.already_applied" && original.aggregateWrites.isEmpty() &&
                    original.claimChanges.isEmpty() && original.jobs.isEmpty() && fact.application.value.state == LocalJournalApplicationStateV3.APPLIED)
                check(LocalFinancialResultCodecV3.decode(original.result) == LocalFinancialResultV3.AlreadyApplied(selected.operation,
                    selected.phase, checkNotNull(fact.application.value.lastApplicationOperationId)))
                requirement(original, if (supervised) retailFactAction(slot.target.kind, LocalJournalFactPhaseV3.RESOLUTION) else SecurityAction.CASH_READ,
                    slot.id, fact.application.value.revision, slot.actorId, "financial.journal.already_applied", if (supervised) slot.domain.amount else null)
            } else {
                check(original.code == "committed" && fact.application.value.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION)
                val write = original.aggregateWrites.single { it.key == fact.application.stored.key }
                val application = localSalesDecode(write.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
                check(write.storageGeneration == fact.application.stored.revision.next() &&
                    application.revision == fact.application.value.revision.next())
                val parent = if (selected.phase == LocalJournalFactPhaseV3.ORIGINAL) null else
                    reader.fact(sources, slot.target.recordingOperationId, LocalJournalFactPhaseV3.ORIGINAL)
                requireRetailApplicationEvidenceV3(original, fact.fact.value, application, slot, parent, tenderPolicies)
                val proof = application.financialProof
                if (proof == null) {
                    check(application.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION)
                    requirement(original, retailFactAction(slot.target.kind, if (supervised) LocalJournalFactPhaseV3.RESOLUTION else selected.phase),
                        slot.id, Revision(0), slot.actorId, "financial.journal.pending", slot.domain.amount)
                } else {
                    for (image in proof.sources) {
                        val source = sources.beforeAggregateSources.single { it.row.key == AggregateKey(image.sourceKind, image.sourceId) }.row
                        check(image.capturingOperationId == original.operationId && image.scope.value() == original.scope &&
                            image.storageRevision == source.revision && image.sourceSchema == source.schema &&
                            image.sourceDigest == source.digest.value && image.body() == source.payload)
                    }
                    if (slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION &&
                        original.aggregateWrites.any { it.key.kind == StockReadEvidenceV1.KIND }) requireNormalizedPaymentOrigin(view, original)
                    val role = if (slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) "collection" else "accepted"
                    val before = proof.sources.single { it.role == role }
                    requirement(original, retailFactAction(slot.target.kind, if (supervised) LocalJournalFactPhaseV3.RESOLUTION else selected.phase),
                        slot.target.documentId, Revision(before.storageRevision.value - 1), slot.actorId,
                        if (slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) "financial.journal.payment.apply" else "financial.journal.refund.apply",
                        slot.domain.amount)
                }
            }
            check(sources.requirement == expected)
            completed[original.operationId] = original.journalHash to expected
            return expected
        } finally { active.remove(original.operationId) }
    }

    private class Selection(val operation: OperationId, val phase: LocalJournalFactPhaseV3,
        val kind: SalesRecordingKind?, val review: Boolean = false)
    private fun selection(original: RetainedCommandEvidence): Selection = when (original.command) {
        LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> (LocalCollectionCommandCodecV3.decode(original.normalized) as LocalCollectionRequestV3.ApplyObservation)
            .let { Selection(it.recordingOperationId, it.phase, SalesRecordingKind.PAYMENT_ATTESTATION) }
        LocalFinancialJournalProfileV3.RETURN_COMMAND -> (LocalReturnCommandCodecV3.decode(original.normalized) as LocalReturnRequestV3.ApplyObservation)
            .let { Selection(it.recordingOperationId, it.phase, SalesRecordingKind.REFUND_ATTESTATION) }
        LocalRetailSupervisorCodecV1.COMMAND -> when (val request = LocalRetailSupervisorCodecV1.decode(original.normalized)) {
            is LocalRetailSupervisorRequestV1.Apply -> Selection(request.recordingOperationId, request.phase, request.kind)
            is LocalRetailSupervisorRequestV1.Review -> Selection(request.originalRecordingOperationId, LocalJournalFactPhaseV3.ORIGINAL, null, review = true)
        }
        else -> error("sales.journal.original_command_unsupported")
    }

    private fun requirement(original: RetainedCommandEvidence, action: SecurityAction, target: DomainId, revision: Revision,
        owner: DomainId, reason: String, amount: Money?): AuthorizationRequirement = salesAuthorizationRequirement(action,
        original.scope, target, revision, original.operationId, original.semanticDigest, reason, owner, amount)

    private fun summary(cut: LocalRetailJournalFactCutV3): LocalJournalFactSummaryV3 = with(cut.fact.value) {
        LocalJournalFactSummaryV3(target, phase, target.factOperation(phase), originalActorId, recordingActorId, bodyDigest,
            originalBodyDigest, recordedAt, cut.slot.value.domain.amount, cut.slot.value.domain.tender, cut.application.value.state,
            cut.application.value.reason, cut.application.value.lastApplicationOperationId, cut.application.value.appliedAction)
    }
}
