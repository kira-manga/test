package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** The actual Review's inert RETAIL cut. Every present row comes from the genuine complete
 * native BEFORE lineage and its strictly earlier, already fully joined original AFTER write.
 * No historical CommandReadView, handler execution, stock facade, native lease or grant exists. */
internal class LocalRetailSupervisorReviewHistorySources(
    private val tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1,
) {
    suspend fun original(command: RuntimeHistoricalCommand, operationId: OperationId): LocalRetailJournalFactCutV3 =
        fact(command, operationId, LocalJournalFactPhaseV3.ORIGINAL, null)

    suspend fun resolution(command: RuntimeHistoricalCommand,
        original: LocalRetailJournalFactCutV3): LocalRetailJournalFactCutV3? {
        val target = original.slot.value.target
        // The real RETAIL Review selects only raw-resolution presence here. This complete
        // native SQL absence is display DATA, never evidence that no native/human action exists.
        if (command.aggregateSource(retailJournalFactKeyV3(LocalJournalFactPhaseV3.RESOLUTION, target.resolutionOperationId)) == null) return null
        return fact(command, target.resolutionOperationId, LocalJournalFactPhaseV3.RESOLUTION, original)
    }

    private class Slot(val row: LocalSalesRow<LocalRetailJournalSlotV3>, val original: RetainedOriginalV2.Legacy)

    private suspend fun slot(command: RuntimeHistoricalCommand, target: LocalJournalTargetV3): Slot {
        val paired = required(command, retailJournalSlotKeyV3(target.recordingOperationId))
        val row = paired.row
        check(row.revision == Revision(1) && row.schema == LocalFinancialJournalProfileV3.SLOT_SCHEMA)
        val value = localSalesDecode(row.payload, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES)
        check(value.schemaVersion == 3 && value.scope.value() == command.scope && value.target == target &&
            retailSlotWriteV3(value).payload == row.payload)
        val requirement = value.requirement()
        retailJournalDomain(target.kind).validateRequirement(requirement)
        val origin = earlier(command, target.intentOperationId)
        check(paired.original === origin)
        requireRetailRetainedRowV3(origin.evidence, row)
        // The same earlier native capture has already passed its whole installed stock/
        // financial producer. This pure original envelope does not create Start authority.
        requireRetailStartEvidenceV3(origin.evidence, value, tenderPolicies)
        val sourceRows = listOf(value.masterAllocationId, value.clientAllocationId).map { id ->
            val source = required(command, AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, id))
            check(source.original === origin && source.row.revision == Revision(1) &&
                source.row.schema == LocalPhysicalJournalFormatV1.SOURCE_SCHEMA)
            requireRetailRetainedRowV3(origin.evidence, source.row)
            source.row
        }
        val pair = requireRetailSourcePairV3(requirement, sourceRows.map { AggregateChange(it.key, null, it.schema, it.payload) })
        check(value.masterSource == retailSourceReferenceV3(pair.first) && value.clientSource == retailSourceReferenceV3(pair.second))
        for (expected in retailFutureFencesV3(requirement)) {
            val source = required(command, expected.key)
            check(source.original === origin && source.row.revision == Revision(1) && source.row.schema == expected.schema &&
                source.row.payload == expected.payload)
            requireRetailRetainedRowV3(origin.evidence, source.row)
        }
        for (claim in retailLineageClaimsV3(requirement)) check(command.claim(claim.key) == claim.ownerId)
        check(command.claim(LocalSalesStoreProfile.unique(if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION)
            "payment_attempt" else "refund_attempt", target.attemptId)) == target.documentId)
        return Slot(LocalSalesRow(row, value), origin)
    }

    private suspend fun fact(command: RuntimeHistoricalCommand, operationId: OperationId,
        phase: LocalJournalFactPhaseV3, parent: LocalRetailJournalFactCutV3?): LocalRetailJournalFactCutV3 {
        val source = required(command, retailJournalFactKeyV3(phase, operationId))
        val row = source.row
        check(row.revision == Revision(1) && row.schema == if (phase == LocalJournalFactPhaseV3.ORIGINAL)
            LocalFinancialJournalProfileV3.ORIGINAL_SCHEMA else LocalFinancialJournalProfileV3.RESOLUTION_SCHEMA)
        val value = localSalesDecode(row.payload, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES)
        check(value.schemaVersion == 3 && value.scope.value() == command.scope && value.phase == phase &&
            value.target.factOperation(phase) == operationId && retailFactWriteV3(value).payload == row.payload)
        val slot = slot(command, value.target)
        val requirement = slot.row.value.requirement()
        val body = value.body()
        check(body.size in 1..LocalFinancialJournalProfileV3.MAX_REQUEST_BYTES && value.target == slot.row.value.target &&
            value.slotDigest == slot.row.stored.digest.value && value.originalActorId == slot.row.value.actorId &&
            (phase == LocalJournalFactPhaseV3.RESOLUTION || value.recordingActorId == value.originalActorId) &&
            retailJournalDigestV3(body).value == value.bodyDigest &&
            semanticDigest(command.scope, value.recordingActorId, retailJournalCommandV3(value.target.kind), body).value == value.semanticDigest)
        val domain = retailJournalDomain(value.target.kind)
        if (phase == LocalJournalFactPhaseV3.ORIGINAL) {
            check(parent == null && value.originalBodyDigest == null)
            domain.classifyOriginal(requirement, body)
        } else {
            check(parent != null && parent.slot.stored.digest == slot.row.stored.digest && parent.fact.value.target == value.target &&
                parent.fact.value.phase == LocalJournalFactPhaseV3.ORIGINAL && parent.fact.value.bodyDigest == value.originalBodyDigest &&
                domain.classifyOriginal(requirement, parent.fact.value.body()) == LocalPhysicalOriginalKind.UNKNOWN)
            domain.validateResolution(requirement, parent.fact.value.body(), value.recordingActorId,
                retailResolutionDescriptorV3(requirement, parent.fact.value.body(), value.recordingActorId, body), body)
        }
        val origin = earlier(command, operationId)
        check(source.original === origin)
        requireRetailRetainedRowV3(origin.evidence, row)
        requireRetailRawEvidenceV3(origin.evidence, value, slot.row.value)
        if (origin.evidence.nativeOriginalProvenance != null) {
            check(phase == LocalJournalFactPhaseV3.ORIGINAL)
            requireRetailNativeOriginalEvidence(origin.evidence, slot.original.evidence, value, slot.row.value, tenderPolicies)
        }
        check(origin.evidence.scope == command.scope && origin.evidence.originalActorId == value.recordingActorId &&
            origin.evidence.command == retailJournalCommandV3(value.target.kind) && origin.evidence.normalized == body &&
            origin.evidence.semanticDigest.value == value.semanticDigest && origin.evidence.recordedAt == value.recordedAt &&
            origin.evidence.claimChanges.isEmpty() && origin.evidence.jobs.isEmpty() &&
            origin.evidence.aggregateWrites.map { it.key }.toSet() == setOf(row.key, retailJournalApplicationKeyV3(operationId)) &&
            origin.evidence.aggregateWrites.size == 2 && LocalFinancialResultCodecV3.decode(origin.evidence.result) ==
                retailPending(value.target, phase, LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION))
        val application = application(command, origin, slot.row, LocalSalesRow(row, value), parent)
        return LocalRetailJournalFactCutV3(slot.row, LocalSalesRow(row, value), application)
    }

    private suspend fun application(command: RuntimeHistoricalCommand, raw: RetainedOriginalV2.Legacy,
        slot: LocalSalesRow<LocalRetailJournalSlotV3>, fact: LocalSalesRow<LocalRetailJournalFactV3>,
        parent: LocalRetailJournalFactCutV3?): LocalSalesRow<LocalRetailJournalApplicationV3> {
        val source = required(command, retailJournalApplicationKeyV3(fact.value.target.factOperation(fact.value.phase)))
        val row = source.row
        check(row.schema == LocalFinancialJournalProfileV3.APPLICATION_SCHEMA)
        val value = localSalesDecode(row.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
        val bound = if (value.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION)
            LocalFinancialJournalProfileV3.MAX_PENDING_APPLICATION_BYTES else LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES
        check(value.schemaVersion == 3 && value.scope == fact.value.scope && value.target == fact.value.target &&
            value.phase == fact.value.phase && value.actorId == fact.value.recordingActorId && value.revision == row.domainRevision() &&
            value.financialRevisions.size <= LocalFinancialJournalProfileV3.MAX_FINANCIAL_WRITES &&
            value.financialRevisions.map { it.kind to it.id }.distinct().size == value.financialRevisions.size &&
            localSalesEncode(value, LocalRetailJournalApplicationV3.serializer(), bound) == row.payload &&
            if (value.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION)
                value.reason != null && value.appliedAction == null && value.financialRevisions.isEmpty() && value.financialProof == null
            else value.reason == null && value.appliedAction != null && value.lastApplicationOperationId != null && value.financialProof != null)
        if (value.revision == Revision(0)) {
            val expected = LocalRetailJournalApplicationV3(3, fact.value.scope, fact.value.target, fact.value.phase,
                fact.value.recordingActorId, Revision(0), LocalJournalApplicationStateV3.PENDING_RECONCILIATION,
                LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION, fact.value.recordedAt, null, null, emptyList())
            check(source.original === raw && value == expected)
            requireRetailRetainedRowV3(raw.evidence, row)
        } else {
            val update = earlier(command, checkNotNull(value.lastApplicationOperationId))
            check(source.original === update)
            requireRetailRetainedRowV3(update.evidence, row)
            if (value.state == LocalJournalApplicationStateV3.APPLIED && parent != null) {
                val predecessor = earlier(command, checkNotNull(parent.application.value.lastApplicationOperationId))
                check(predecessor.evidence.sequence < update.evidence.sequence)
            }
            // Actual current-at-cut accounting, including exact UNKNOWN parent reference,
            // literal source proof and whole retained effects. No current-row grant or fake
            // stock-read evidence is supplied; the paired earlier capture was fully joined.
            requireRetailApplicationEvidenceV3(update.evidence, fact.value, value, slot.value, parent, tenderPolicies)
        }
        return LocalSalesRow(row, value)
    }

    private suspend fun required(command: RuntimeHistoricalCommand, key: AggregateKey): RuntimeHistoricalAggregateSource =
        command.aggregateSource(key) ?: error("sales.supervisor.history_source_unavailable")
    private suspend fun earlier(command: RuntimeHistoricalCommand, operationId: OperationId): RetainedOriginalV2.Legacy =
        command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy ?: error("sales.supervisor.history_original_unavailable")
}
