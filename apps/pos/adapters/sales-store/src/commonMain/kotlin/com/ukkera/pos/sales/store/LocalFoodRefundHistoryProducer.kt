package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/**
 * Genuine native BEFORE-cut FOOD CASH payout Start/original/Apply business joins. No handler,
 * historical/current view, employee grant, native lease or reconstructed original is made.
 * Core closes every range/EOF, prior complete original and exact effects/resources/deadline.
 * Native original retention stays a distinct provenance branch, never employee mirroring.
 */
public class LocalFoodRefundHistoryProducer : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = foodRefundCommands

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        check(original.scope == command.scope && original.operationId == command.operationId && original.originalActorId == command.actorId &&
            original.command == command.command && original.semanticDigest == command.semanticDigest && original.recordedAt == command.recordedAt &&
            LocalFoodRefundCodec.normalize(command.normalized) == command.normalized)
        return when (val request = LocalFoodRefundCodec.decode(command.normalized)) {
            is LocalFoodRefundRequest.StartCash -> start(command, original, request)
            is LocalFoodRefundRequest.RecordOriginal -> raw(command, original, request)
            is LocalFoodRefundRequest.RecordResolution -> resolution(command, original, request)
            is LocalFoodRefundRequest.ApplyOriginal -> apply(command, original, request.recordingOperationId)
            is LocalFoodRefundRequest.ApplyResolution -> applyResolution(command, original, request.resolutionOperationId)
        }
    }

    private suspend fun start(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalFoodRefundRequest.StartCash): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            LocalFoodRefundCodec.result(original.result) !is LocalFoodRefundResult.AttemptStarted) return false
        val proof = LocalFoodRefundProducer.requireStart(original)
        val observed = command.requireLegacyReadEvidence() ?: return false
        requireFoodRefundHistorySources(command, observed)
        // Core owns operation-ID uniqueness and BEFORE-cut closure. earlierOriginal is
        // strictly earlier-only: probing either reserved future ID would reject a valid
        // later raw original in the same complete scan, not prove Start-time absence.
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.REFUND_RECORD, command.scope,
            proof.cut.refund.id, request.expectedRefundRevision, command.operationId, command.semanticDigest,
            "food.refund.start", original.originalActorId, request.amount))
        command.requireFinalDeadlineDecision(null)
        command.requireLegacyEffects(proof.proposal, proof.resources)
        return true
    }

    private class Slot(val value: LocalFoodRefundSlot, val start: RetainedOriginalV2.Legacy)
    private class Raw(val value: LocalFoodRefundRaw, val original: RetainedOriginalV2.Legacy, val slot: Slot)

    private suspend fun slot(command: RuntimeHistoricalCommand, target: LocalFoodRefundTarget): Slot {
        val paired = required(command, foodRefundSlotKey(target.recordingOperationId))
        val row = paired.row
        check(row.revision == Revision(1) && row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.SLOT))
        val value = localSalesDecode(row.payload, LocalFoodRefundSlot.serializer(), LocalFoodRefundProfile.MAX_SLOT_BYTES)
        check(value.version == 1 && value.scope == command.scope && value.target == target && foodRefundSlotWrite(value).payload == row.payload)
        val earlier = command.earlierOriginal(target.intentOperationId) as? RetainedOriginalV2.Legacy ?: unavailable()
        check(paired.original === earlier && LocalFoodRefundProducer.requireStart(earlier.evidence).slot == value)
        requireFoodRefundRetainedRow(earlier.evidence, row)
        val requirement = value.requirement()
        val sources = listOf(value.masterAllocationId, value.clientAllocationId).map { id ->
            val source = required(command, AggregateKey(PhysicalRecordingSourceProfileV1.KIND, id))
            check(source.original === earlier && source.row.revision == Revision(1) && source.row.schema == PhysicalRecordingSourceProfileV1.SCHEMA)
            requireFoodRefundRetainedRow(earlier.evidence, source.row)
            AggregateChange(source.row.key, null, source.row.schema, source.row.payload)
        }
        val pair = requireFoodRefundSourcePair(requirement, sources)
        check(value.masterSource == LocalFoodRefundSourceReference.from(pair.first) && value.clientSource == LocalFoodRefundSourceReference.from(pair.second))
        for (expected in foodRefundFences(requirement)) {
            val fence = required(command, expected.key)
            check(fence.original === earlier && fence.row.revision == Revision(1) && fence.row.schema == expected.schema && fence.row.payload == expected.payload)
            requireFoodRefundRetainedRow(earlier.evidence, fence.row)
        }
        for (claim in foodRefundLineageClaims(requirement)) check(command.claim(claim.key) == claim.ownerId)
        check(command.claim(LocalSalesStoreProfile.unique("refund_attempt", target.attemptId)) == target.refundId)
        return Slot(value, earlier)
    }

    private suspend fun raw(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalFoodRefundRequest.RecordOriginal): Boolean {
        if (LocalFoodRefundCodec.result(original.result) !is LocalFoodRefundResult.RawRetained) return false
        val slot = slot(command, request.target)
        val proof = LocalFoodRefundProducer.requireRaw(original, slot.start.evidence)
        check(command.aggregateSource(foodRefundRawKey(command.operationId)) == null &&
            command.aggregateSource(foodRefundApplicationKey(command.operationId)) == null)
        val requirement = slot.value.requirement()
        check(command.actorId == slot.value.originalActorId)
        val resources = slot.value.domain.financialResources.toSet() + foodRefundAuthorityResources(requirement) + recordingAuthority(command.actorId)
        if (command.authority == RuntimeHistoricalAuthority.NATIVE_ORIGINAL) {
            val native = checkNotNull(command.nativeOriginal)
            check(native.provenance == original.nativeOriginalProvenance && native.canonicalRequirement == requirement.canonicalBytes &&
                native.provenance.nativeOriginal.certificateDigest == sha256(native.certificate.copyBytes()))
            requireFoodRefundStartCertificate(native.certificate, slot.start.evidence, requirement)
            requireFoodRefundNativeOriginalEvidence(original, slot.start.evidence, proof.raw, slot.value)
            command.requireNativeOriginalRequirement(requirement.canonicalBytes)
            command.requireNativeOriginalEffects(proof.proposal, resources)
        } else {
            check(original.nativeOriginalProvenance == null)
            command.requireRequirement(salesAuthorizationRequirement(SecurityAction.REFUND_RECORD, command.scope,
                DomainId(slot.value.target.recordingOperationId.value), Revision(0), command.operationId, command.semanticDigest,
                "food.refund.retain", slot.value.originalActorId, slot.value.domain.amount))
            command.requireLegacyEffects(proof.proposal, resources)
        }
        return true
    }

    private suspend fun rawBefore(command: RuntimeHistoricalCommand, operationId: OperationId): Raw {
        val paired = required(command, foodRefundRawKey(operationId))
        val row = paired.row
        check(row.revision == Revision(1) && row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.ORIGINAL))
        val raw = localSalesDecode(row.payload, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        val earlier = command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy ?: unavailable()
        check(paired.original === earlier && raw.target.recordingOperationId == operationId && raw.scope == command.scope)
        val slot = slot(command, raw.target)
        check(LocalFoodRefundProducer.requireRaw(earlier.evidence, slot.start.evidence).raw == raw)
        requireFoodRefundRetainedRow(earlier.evidence, row)
        return Raw(raw, earlier, slot)
    }

    private suspend fun apply(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        operationId: OperationId): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            LocalFoodRefundCodec.result(original.result) !is LocalFoodRefundResult.Applied) return false
        val selection = foodRefundApplySelection(command.command, command.normalized)
        check(selection.phase == LocalFoodRefundFactPhase.ORIGINAL && selection.factOperationId == operationId)
        val raw = rawBefore(command, operationId)
        val before = required(command, foodRefundApplicationKey(operationId))
        check(before.original === raw.original && before.row.revision == Revision(1) &&
            before.row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION) &&
            localSalesDecode(before.row.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES) ==
                foodRefundInitialApplication(raw.value))
        requireFoodRefundRetainedRow(raw.original.evidence, before.row)
        // Entire accounting transition and same immutable Start/original. No stock revalidation
        // or expiry undo is permitted here: the incurred native fact remains in reconciliation.
        val proof = LocalFoodRefundProducer.requireApply(original, raw.slot.start.evidence, raw.original.evidence)
        val observed = command.requireLegacyReadEvidence() ?: return false
        requireFoodRefundHistorySources(command, observed)
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.REFUND_RECORD,
            command.scope, proof.cut.refund.id, proof.cut.refund.revision, command.operationId, command.semanticDigest,
            "food.refund.apply", raw.slot.value.originalActorId, raw.slot.value.domain.amount))
        command.requireLegacyEffects(proof.proposal, proof.resources)
        return true
    }

    /** Exact current original accounting; resolution retention may occur before or after UNKNOWN Apply. */
    private class OriginalApplication(val value: LocalFoodRefundApplication, val original: RetainedOriginalV2.Legacy)
    private suspend fun originalApplicationBefore(command: RuntimeHistoricalCommand, raw: Raw): OriginalApplication {
        val before = required(command, foodRefundApplicationKey(raw.value.target.recordingOperationId))
        check(before.row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION))
        val value = localSalesDecode(before.row.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES)
        check(value.version == 1 && value.scope == command.scope && value.target == raw.value.target &&
            value.originalActorId == raw.value.originalActorId && value.phase == LocalFoodRefundFactPhase.ORIGINAL &&
            value.revision.next() == before.row.revision)
        val earlier = when (value.state) {
            LocalFoodRefundApplicationState.PENDING_RECONCILIATION -> {
                check(before.row.revision == Revision(1) && before.original === raw.original && value == foodRefundInitialApplication(raw.value))
                raw.original
            }
            LocalFoodRefundApplicationState.APPLIED -> {
                check(before.row.revision == Revision(2) && value.revision == Revision(1) && value.reason == null &&
                    value.action != null && value.lastApplicationOperationId != null)
                val applied = command.earlierOriginal(checkNotNull(value.lastApplicationOperationId)) as? RetainedOriginalV2.Legacy ?: unavailable()
                check(before.original === applied && LocalFoodRefundProducer.requireApply(applied.evidence,
                    raw.slot.start.evidence, raw.original.evidence).application == value)
                applied
            }
        }
        requireFoodRefundRetainedRow(earlier.evidence, before.row)
        return OriginalApplication(value, earlier)
    }

    private suspend fun resolution(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalFoodRefundRequest.RecordResolution): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            LocalFoodRefundCodec.result(original.result) != LocalFoodRefundResult.RawRetained(request.target,
                phase = LocalFoodRefundFactPhase.RESOLUTION)) return false
        val parent = rawBefore(command, request.target.recordingOperationId)
        originalApplicationBefore(command, parent)
        val proof = LocalFoodRefundResolutionProducer.requireRaw(original, parent.slot.start.evidence, parent.original.evidence)
        check(command.aggregateSource(foodRefundResolutionKey(command.operationId)) == null &&
            command.aggregateSource(foodRefundApplicationKey(command.operationId)) == null)
        val slot = parent.slot.value
        check(command.actorId == slot.originalActorId)
        check(proof.raw.target == slot.target && command.operationId == slot.target.resolutionOperationId)
        val resources = slot.domain.financialResources.toSet() + foodRefundAuthorityResources(slot.requirement()) + recordingAuthority(command.actorId)
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.REFUND_RESOLVE, command.scope,
            DomainId(slot.target.recordingOperationId.value), Revision(0), command.operationId, command.semanticDigest,
            "food.refund.retain_resolution", slot.originalActorId, slot.domain.amount))
        command.requireLegacyEffects(proof.proposal, resources)
        return true
    }

    private class Resolution(val value: LocalFoodRefundResolutionRaw, val original: RetainedOriginalV2.Legacy, val parent: Raw)
    private suspend fun resolutionBefore(command: RuntimeHistoricalCommand, operationId: OperationId): Resolution {
        val paired = required(command, foodRefundResolutionKey(operationId))
        val row = paired.row
        check(row.revision == Revision(1) && row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.RESOLUTION))
        val value = localSalesDecode(row.payload, LocalFoodRefundResolutionRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        val earlier = command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy ?: unavailable()
        check(paired.original === earlier && value.scope == command.scope && value.target.resolutionOperationId == operationId)
        val parent = rawBefore(command, value.target.recordingOperationId)
        check(LocalFoodRefundResolutionProducer.requireRaw(earlier.evidence, parent.slot.start.evidence, parent.original.evidence).raw == value)
        requireFoodRefundRetainedRow(earlier.evidence, row)
        return Resolution(value, earlier, parent)
    }

    private suspend fun applyResolution(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        operationId: OperationId): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            (LocalFoodRefundCodec.result(original.result) as? LocalFoodRefundResult.Applied)?.phase != LocalFoodRefundFactPhase.RESOLUTION) return false
        val selection = foodRefundApplySelection(command.command, command.normalized)
        check(selection.phase == LocalFoodRefundFactPhase.RESOLUTION && selection.factOperationId == operationId)
        val resolution = resolutionBefore(command, operationId)
        val before = required(command, foodRefundApplicationKey(operationId))
        check(before.original === resolution.original && before.row.revision == Revision(1) &&
            before.row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION) &&
            localSalesDecode(before.row.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES) ==
                foodRefundInitialApplication(resolution.value))
        requireFoodRefundRetainedRow(resolution.original.evidence, before.row)
        val parent = originalApplicationBefore(command, resolution.parent)
        check(parent.value.state == LocalFoodRefundApplicationState.APPLIED && parent.value.action == LocalFoodRefundAction.UNKNOWN &&
            foodRefundStoredEvent(original).originalUnknownApplicationOperationId == parent.original.evidence.operationId)
        val proof = LocalFoodRefundResolutionProducer.requireApply(original, resolution.parent.slot.start.evidence,
            resolution.parent.original.evidence, resolution.original.evidence, parent.original.evidence)
        val observed = command.requireLegacyReadEvidence() ?: return false
        requireFoodRefundHistorySources(command, observed)
        val slot = resolution.parent.slot.value
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.REFUND_RESOLVE, command.scope,
            proof.cut.refund.id, proof.cut.refund.revision, command.operationId, command.semanticDigest,
            "food.refund.apply", slot.originalActorId, slot.domain.amount))
        command.requireLegacyEffects(proof.proposal, proof.resources)
        return true
    }

    private suspend fun required(command: RuntimeHistoricalCommand, key: AggregateKey): RuntimeHistoricalAggregateSource =
        command.aggregateSource(key) ?: unavailable()
    private fun unavailable(): Nothing = error("food.refund.history_original_unavailable")
}

/** Every financial/cash literal is paired against the REAL native BEFORE cut and whole earlier joins. */
internal suspend fun requireFoodRefundHistorySources(command: RuntimeHistoricalCommand,
    observed: StockReadObservations) {
    check(observed.sources.all { it is StreamedSourceObservationV2.Current ||
        it is StreamedSourceObservationV2.CurrentAbsent || it is StreamedSourceObservationV2.Claim })
    for (source in observed.sources) when (source) {
        is StreamedSourceObservationV2.Current -> {
            val paired = command.aggregateSource(source.key) ?: error("food.refund.history_source_missing")
            check(paired.row.key == source.key && paired.row.revision == source.generation && paired.row.schema == source.schema &&
                paired.row.payload == source.payload && paired.row.digest == source.payloadHash)
            if (source.key.kind in foodRefundFinancialKinds) requireFoodFinancialHistorySource(command, paired)
            else if (source.key.kind == LocalFoodProfileV1.PRICE_BATCH) {
                val earlier = (paired.original as? RetainedOriginalV2.Streamed)?.evidence ?: error("food.refund.history_price_profile")
                val value = foodEnvelope(source.payload)
                check(source.schema == foodStockSchema(source.key.kind) && value.scope == command.scope && value.kind == source.key.kind &&
                    value.id == source.key.id && value.revision == source.generation && value.operationId == earlier.identity.producer.operationId &&
                    earlier.identity.producer.command == StockStreamingCommandsV2.FOOD && command.earlierOriginal(value.operationId) === paired.original &&
                    command.claim(foodSourceClaim(source.key)) == source.key.id)
            }
            check(source.key.kind !in StockRelationalKindsV2.all &&
                source.key.kind !in com.ukkera.pos.inventory.store.LocalStockStoreProfile.inventoryKinds)
        }
        is StreamedSourceObservationV2.CurrentAbsent -> check(command.aggregateSource(source.key) == null)
        is StreamedSourceObservationV2.Claim -> {
            check(source.key.namespace !in com.ukkera.pos.inventory.store.LocalStockStoreProfile.inventoryClaimNamespaces)
            check(command.claim(source.key) == source.ownerId)
        }
        else -> error("food.refund.history_unexpected_source")
    }
}

/** Exact scalar FOOD payout root using ONLY actual earlier, fully joined originals. */
internal suspend fun requireFoodRefundHistoricalComplete(command: RuntimeHistoricalCommand,
    original: RetainedCommandEvidence) {
    check(isFoodRefundFinancialProducer(original) && original.scope == command.scope && original.operationId != command.operationId)
    val lineage = foodRefundRetainedLineage(original) { id ->
        (command.earlierOriginal(id) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("food.refund.history_earlier_ancestor_missing")
    }
    LocalFoodRefundProducer.requireComplete(original, lineage.start, lineage.originalRaw,
        lineage.resolutionRaw, lineage.originalUnknownApplication)
}
