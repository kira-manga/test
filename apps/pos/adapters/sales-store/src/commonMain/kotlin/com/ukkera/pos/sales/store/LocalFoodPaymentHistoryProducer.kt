package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockPaymentHistorySources
import com.ukkera.pos.inventory.store.LocalStockPaymentObservations
import com.ukkera.pos.inventory.store.LocalStockRelationalCodecV2
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/**
 * Genuine native BEFORE-cut FOOD CASH Start/original/Apply business joins. No handler,
 * historical/current view, employee grant, native lease or reconstructed original is made.
 * Core closes every range/EOF, prior complete original and exact effects/resources/deadline.
 * Native original retention stays a distinct provenance branch, never employee mirroring.
 */
public class LocalFoodPaymentHistoryProducer : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = foodPaymentCommands

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        check(original.scope == command.scope && original.operationId == command.operationId && original.originalActorId == command.actorId &&
            original.command == command.command && original.semanticDigest == command.semanticDigest && original.recordedAt == command.recordedAt &&
            foodPaymentNormalize(command.command, command.normalized) == command.normalized)
        if (command.command == LocalFoodPaymentSupervisorCodec.COMMAND) {
            val request = LocalFoodPaymentSupervisorCodec.decode(command.normalized) as? LocalFoodPaymentSupervisorRequest.Apply
                ?: return false // Audited rejected Review is core-owned, not a committed business root.
            return if (request.phase == LocalFoodPaymentFactPhase.ORIGINAL) apply(command, original, request.factOperationId)
                else applyResolution(command, original, request.factOperationId)
        }
        return when (val request = LocalFoodPaymentCodec.decode(command.normalized)) {
            is LocalFoodPaymentRequest.StartCash -> start(command, original, request)
            is LocalFoodPaymentRequest.RecordOriginal -> raw(command, original, request)
            is LocalFoodPaymentRequest.RecordResolution -> resolution(command, original, request)
            is LocalFoodPaymentRequest.ApplyOriginal -> apply(command, original, request.recordingOperationId)
            is LocalFoodPaymentRequest.ApplyResolution -> applyResolution(command, original, request.resolutionOperationId)
        }
    }

    private suspend fun start(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalFoodPaymentRequest.StartCash): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            LocalFoodPaymentCodec.result(original.result) !is LocalFoodPaymentResult.AttemptStarted) return false
        val proof = LocalFoodPaymentProducer.requireStart(original)
        val observed = command.requireLegacyReadEvidence() ?: return false
        requireFoodPaymentHistorySources(command, observed, stock = true)
        for (row in proof.cut.portions) {
            val portion = row.body as FoodBodyV1.Portion
            val entry = proof.cut.collection.portions.single { it.portionId == row.id }
            proof.stock.issued[entry.reservationId]?.let { issued ->
                requireFoodFirstIssue(portion, LocalStockPaymentObservations.requireIssuedOriginal(command, issued))
            }
        }
        // Core owns operation-ID uniqueness and BEFORE-cut closure. earlierOriginal is
        // strictly earlier-only: probing either reserved future ID would reject a valid
        // later raw original in the same complete scan, not prove Start-time absence.
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.COLLECTION_ATTEST, command.scope,
            proof.cut.collection.id, request.expectedCollectionRevision, command.operationId, command.semanticDigest,
            "food.payment.start", proof.cut.collection.currentOwnerId, request.amount))
        command.requireFinalDeadlineDecision(proof.result.mustCommitBefore)
        command.requireLegacyEffects(proof.proposal, proof.resources)
        return true
    }

    private class Slot(val value: LocalFoodPaymentSlot, val start: RetainedOriginalV2.Legacy)
    private class Raw(val value: LocalFoodPaymentRaw, val original: RetainedOriginalV2.Legacy, val slot: Slot)

    private suspend fun slot(command: RuntimeHistoricalCommand, target: LocalFoodPaymentTarget): Slot {
        val paired = required(command, foodPaymentSlotKey(target.recordingOperationId))
        val row = paired.row
        check(row.revision == Revision(1) && row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.SLOT))
        val value = localSalesDecode(row.payload, LocalFoodPaymentSlot.serializer(), LocalFoodPaymentProfile.MAX_SLOT_BYTES)
        check(value.version == 1 && value.scope == command.scope && value.target == target && foodPaymentSlotWrite(value).payload == row.payload)
        val earlier = command.earlierOriginal(target.intentOperationId) as? RetainedOriginalV2.Legacy ?: unavailable()
        check(paired.original === earlier && LocalFoodPaymentProducer.requireStart(earlier.evidence).slot == value)
        requireFoodPaymentRetainedRow(earlier.evidence, row)
        val requirement = value.requirement()
        val sources = listOf(value.masterAllocationId, value.clientAllocationId).map { id ->
            val source = required(command, AggregateKey(PhysicalRecordingSourceProfileV1.KIND, id))
            check(source.original === earlier && source.row.revision == Revision(1) && source.row.schema == PhysicalRecordingSourceProfileV1.SCHEMA)
            requireFoodPaymentRetainedRow(earlier.evidence, source.row)
            AggregateChange(source.row.key, null, source.row.schema, source.row.payload)
        }
        val pair = requireFoodPaymentSourcePair(requirement, sources)
        check(value.masterSource == LocalFoodPaymentSourceReference.from(pair.first) && value.clientSource == LocalFoodPaymentSourceReference.from(pair.second))
        for (expected in foodPaymentFences(requirement)) {
            val fence = required(command, expected.key)
            check(fence.original === earlier && fence.row.revision == Revision(1) && fence.row.schema == expected.schema && fence.row.payload == expected.payload)
            requireFoodPaymentRetainedRow(earlier.evidence, fence.row)
        }
        for (claim in foodPaymentLineageClaims(requirement)) check(command.claim(claim.key) == claim.ownerId)
        check(command.claim(LocalSalesStoreProfile.unique("payment_attempt", target.attemptId)) == target.collectionId)
        return Slot(value, earlier)
    }

    private suspend fun raw(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalFoodPaymentRequest.RecordOriginal): Boolean {
        if (LocalFoodPaymentCodec.result(original.result) !is LocalFoodPaymentResult.RawRetained) return false
        val slot = slot(command, request.target)
        val proof = LocalFoodPaymentProducer.requireRaw(original, slot.start.evidence)
        check(command.aggregateSource(foodPaymentRawKey(command.operationId)) == null &&
            command.aggregateSource(foodPaymentApplicationKey(command.operationId)) == null)
        val requirement = slot.value.requirement()
        val resources = slot.value.domain.financialResources.toSet() + foodPaymentAuthorityResources(requirement) + recordingAuthority(command.actorId)
        if (command.authority == RuntimeHistoricalAuthority.NATIVE_ORIGINAL) {
            val native = checkNotNull(command.nativeOriginal)
            check(native.provenance == original.nativeOriginalProvenance && native.canonicalRequirement == requirement.canonicalBytes &&
                native.provenance.nativeOriginal.certificateDigest == sha256(native.certificate.copyBytes()))
            requireFoodPaymentStartCertificate(native.certificate, slot.start.evidence, requirement)
            requireFoodPaymentNativeOriginalEvidence(original, slot.start.evidence, proof.raw, slot.value)
            command.requireNativeOriginalRequirement(requirement.canonicalBytes)
            command.requireNativeOriginalEffects(proof.proposal, resources)
        } else {
            check(original.nativeOriginalProvenance == null)
            command.requireRequirement(salesAuthorizationRequirement(SecurityAction.COLLECTION_ATTEST, command.scope,
                DomainId(slot.value.target.recordingOperationId.value), Revision(0), command.operationId, command.semanticDigest,
                "food.payment.retain", slot.value.originalActorId, slot.value.domain.amount))
            command.requireLegacyEffects(proof.proposal, resources)
        }
        return true
    }

    private suspend fun rawBefore(command: RuntimeHistoricalCommand, operationId: OperationId): Raw {
        val paired = required(command, foodPaymentRawKey(operationId))
        val row = paired.row
        check(row.revision == Revision(1) && row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.ORIGINAL))
        val raw = localSalesDecode(row.payload, LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        val earlier = command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy ?: unavailable()
        check(paired.original === earlier && raw.target.recordingOperationId == operationId && raw.scope == command.scope)
        val slot = slot(command, raw.target)
        check(LocalFoodPaymentProducer.requireRaw(earlier.evidence, slot.start.evidence).raw == raw)
        requireFoodPaymentRetainedRow(earlier.evidence, row)
        return Raw(raw, earlier, slot)
    }

    private suspend fun apply(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        operationId: OperationId): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        when (LocalFoodPaymentCodec.result(original.result)) {
            is LocalFoodPaymentResult.AlreadyApplied -> return if (command.command == LocalFoodPaymentProfile.COMMAND)
                alreadyApplied(command, original, operationId) else false
            is LocalFoodPaymentResult.Pending -> return if (command.command == LocalFoodPaymentProfile.COMMAND)
                valueMismatch(command, original, operationId) else false
            is LocalFoodPaymentResult.Applied -> Unit
            else -> return false
        }
        val selection = foodPaymentApplySelection(command.command, command.normalized)
        check(selection.phase == LocalFoodPaymentFactPhase.ORIGINAL && selection.factOperationId == operationId)
        val raw = rawBefore(command, operationId)
        val before = required(command, foodPaymentApplicationKey(operationId))
        check(before.original === raw.original && before.row.revision == Revision(1) &&
            before.row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION) &&
            localSalesDecode(before.row.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES) ==
                foodPaymentInitialApplication(raw.value))
        requireFoodPaymentRetainedRow(raw.original.evidence, before.row)
        // Entire accounting transition and same immutable Start/original. No stock revalidation
        // or expiry undo is permitted here: the incurred native fact remains in reconciliation.
        val proof = LocalFoodPaymentProducer.requireApply(original, raw.slot.start.evidence, raw.original.evidence)
        val observed = command.requireLegacyReadEvidence() ?: return false
        requireFoodPaymentHistorySources(command, observed, stock = false)
        command.requireRequirement(salesAuthorizationRequirement(if (selection.supervised) SecurityAction.COLLECTION_RESOLVE else SecurityAction.COLLECTION_ATTEST,
            command.scope, proof.cut.collection.id, proof.cut.collection.revision, command.operationId, command.semanticDigest,
            "food.payment.apply", raw.slot.value.originalActorId, raw.slot.value.domain.amount))
        command.requireLegacyEffects(proof.proposal, proof.resources)
        return true
    }

    /** A no-write original requires what THIS refused command actually observed, not
     * only the existence of an earlier successful Apply. All pairings here come from its
     * genuine native BEFORE cut and already fully joined earlier writers on the same scan. */
    private suspend fun alreadyApplied(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        operationId: OperationId): Boolean {
        check(command.authority == RuntimeHistoricalAuthority.EMPLOYEE_COMMAND && command.originalApproval == null &&
            command.command == LocalFoodPaymentProfile.COMMAND)
        val selection = foodPaymentApplySelection(command.command, command.normalized)
        check(!selection.supervised && selection.phase == LocalFoodPaymentFactPhase.ORIGINAL && selection.factOperationId == operationId)
        val raw = rawBefore(command, operationId)
        val before = originalApplicationBefore(command, raw)
        val proof = requireFoodPaymentAlreadyApplied(original, before.row, before.original.evidence,
            raw.slot.start.evidence, raw.original.evidence)
        check(proof.application == before.value)
        command.requireRequirement(proof.requirement)
        command.requireFoodPaymentAlreadyAppliedRejection(proof.proposal, proof.resources)
        return true
    }

    private suspend fun valueMismatch(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        operationId: OperationId): Boolean {
        check(command.authority == RuntimeHistoricalAuthority.EMPLOYEE_COMMAND && command.originalApproval == null &&
            command.command == LocalFoodPaymentProfile.COMMAND)
        val selection = foodPaymentApplySelection(command.command, command.normalized)
        check(!selection.supervised && selection.phase == LocalFoodPaymentFactPhase.ORIGINAL && selection.factOperationId == operationId)
        val raw = rawBefore(command, operationId)
        val before = originalApplicationBefore(command, raw)
        check(before.original === raw.original)
        val proof = requireFoodPaymentValueMismatch(original, before.row, raw.slot.start.evidence, raw.original.evidence)
        check(proof.application == before.value)
        command.requireRequirement(proof.requirement)
        command.requireFoodPaymentValueMismatchRejection(proof.proposal, proof.resources)
        return true
    }

    /** Exact current original accounting; resolution retention may occur before or after UNKNOWN Apply. */
    private class OriginalApplication(val value: LocalFoodPaymentApplication, val original: RetainedOriginalV2.Legacy,
        val row: StoredAggregate)
    private suspend fun originalApplicationBefore(command: RuntimeHistoricalCommand, raw: Raw): OriginalApplication {
        val before = required(command, foodPaymentApplicationKey(raw.value.target.recordingOperationId))
        check(before.row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION))
        val value = localSalesDecode(before.row.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
        check(value.version == 1 && value.scope == command.scope && value.target == raw.value.target &&
            value.originalActorId == raw.value.originalActorId && value.phase == LocalFoodPaymentFactPhase.ORIGINAL &&
            value.revision.next() == before.row.revision)
        val earlier = when (value.state) {
            LocalFoodPaymentApplicationState.PENDING_RECONCILIATION -> {
                check(before.row.revision == Revision(1) && before.original === raw.original && value == foodPaymentInitialApplication(raw.value))
                raw.original
            }
            LocalFoodPaymentApplicationState.APPLIED -> {
                check(before.row.revision == Revision(2) && value.revision == Revision(1) && value.reason == null &&
                    value.action != null && value.lastApplicationOperationId != null)
                val applied = command.earlierOriginal(checkNotNull(value.lastApplicationOperationId)) as? RetainedOriginalV2.Legacy ?: unavailable()
                check(before.original === applied && LocalFoodPaymentProducer.requireApply(applied.evidence,
                    raw.slot.start.evidence, raw.original.evidence).application == value)
                applied
            }
        }
        requireFoodPaymentRetainedRow(earlier.evidence, before.row)
        return OriginalApplication(value, earlier, before.row)
    }

    private suspend fun resolution(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        request: LocalFoodPaymentRequest.RecordResolution): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            LocalFoodPaymentCodec.result(original.result) != LocalFoodPaymentResult.RawRetained(request.target,
                phase = LocalFoodPaymentFactPhase.RESOLUTION)) return false
        val parent = rawBefore(command, request.target.recordingOperationId)
        originalApplicationBefore(command, parent)
        val proof = LocalFoodPaymentResolutionProducer.requireRaw(original, parent.slot.start.evidence, parent.original.evidence)
        check(command.aggregateSource(foodPaymentResolutionKey(command.operationId)) == null &&
            command.aggregateSource(foodPaymentApplicationKey(command.operationId)) == null)
        val slot = parent.slot.value
        check(proof.raw.target == slot.target && command.operationId == slot.target.resolutionOperationId)
        val resources = slot.domain.financialResources.toSet() + foodPaymentAuthorityResources(slot.requirement()) + recordingAuthority(command.actorId)
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.COLLECTION_RESOLVE, command.scope,
            DomainId(slot.target.recordingOperationId.value), Revision(0), command.operationId, command.semanticDigest,
            "food.payment.retain_resolution", slot.originalActorId, slot.domain.amount))
        command.requireLegacyEffects(proof.proposal, resources)
        return true
    }

    private class Resolution(val value: LocalFoodPaymentResolutionRaw, val original: RetainedOriginalV2.Legacy, val parent: Raw)
    private suspend fun resolutionBefore(command: RuntimeHistoricalCommand, operationId: OperationId): Resolution {
        val paired = required(command, foodPaymentResolutionKey(operationId))
        val row = paired.row
        check(row.revision == Revision(1) && row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.RESOLUTION))
        val value = localSalesDecode(row.payload, LocalFoodPaymentResolutionRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        val earlier = command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy ?: unavailable()
        check(paired.original === earlier && value.scope == command.scope && value.target.resolutionOperationId == operationId)
        val parent = rawBefore(command, value.target.recordingOperationId)
        check(LocalFoodPaymentResolutionProducer.requireRaw(earlier.evidence, parent.slot.start.evidence, parent.original.evidence).raw == value)
        requireFoodPaymentRetainedRow(earlier.evidence, row)
        return Resolution(value, earlier, parent)
    }

    private suspend fun applyResolution(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        operationId: OperationId): Boolean {
        if (command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            (LocalFoodPaymentCodec.result(original.result) as? LocalFoodPaymentResult.Applied)?.phase != LocalFoodPaymentFactPhase.RESOLUTION) return false
        val selection = foodPaymentApplySelection(command.command, command.normalized)
        check(selection.phase == LocalFoodPaymentFactPhase.RESOLUTION && selection.factOperationId == operationId)
        val resolution = resolutionBefore(command, operationId)
        val before = required(command, foodPaymentApplicationKey(operationId))
        check(before.original === resolution.original && before.row.revision == Revision(1) &&
            before.row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION) &&
            localSalesDecode(before.row.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES) ==
                foodPaymentInitialApplication(resolution.value))
        requireFoodPaymentRetainedRow(resolution.original.evidence, before.row)
        val parent = originalApplicationBefore(command, resolution.parent)
        check(parent.value.state == LocalFoodPaymentApplicationState.APPLIED && parent.value.action == LocalFoodPaymentAction.UNKNOWN &&
            foodPaymentStoredEvent(original).originalUnknownApplicationOperationId == parent.original.evidence.operationId)
        val proof = LocalFoodPaymentResolutionProducer.requireApply(original, resolution.parent.slot.start.evidence,
            resolution.parent.original.evidence, resolution.original.evidence, parent.original.evidence)
        val observed = command.requireLegacyReadEvidence() ?: return false
        requireFoodPaymentHistorySources(command, observed, stock = false)
        val slot = resolution.parent.slot.value
        command.requireRequirement(salesAuthorizationRequirement(SecurityAction.COLLECTION_RESOLVE, command.scope,
            proof.cut.collection.id, proof.cut.collection.revision, command.operationId, command.semanticDigest,
            "food.payment.apply", slot.originalActorId, slot.domain.amount))
        command.requireLegacyEffects(proof.proposal, proof.resources)
        return true
    }

    private suspend fun required(command: RuntimeHistoricalCommand, key: AggregateKey): RuntimeHistoricalAggregateSource =
        command.aggregateSource(key) ?: unavailable()
    private fun unavailable(): Nothing = error("food.payment.history_original_unavailable")
}

/** All literal observations are paired against the REAL native before cut and whole earlier joins. */
internal suspend fun requireFoodPaymentHistorySources(command: RuntimeHistoricalCommand,
    observed: StockReadObservations, stock: Boolean) {
    if (stock) LocalStockPaymentHistorySources.requireOriginals(command, observed)
    else check(observed.sources.none { it is StreamedSourceObservationV2.StockMember || it is StreamedSourceObservationV2.StockRange ||
        it is StreamedSourceObservationV2.Current && it.key.kind in StockRelationalKindsV2.all ||
        it is StreamedSourceObservationV2.Claim && it.key.namespace in com.ukkera.pos.inventory.store.LocalStockStoreProfile.inventoryClaimNamespaces })
    for (source in observed.sources) when (source) {
        is StreamedSourceObservationV2.Current -> {
            val paired = command.aggregateSource(source.key) ?: error("food.payment.history_source_missing")
            check(paired.row.key == source.key && paired.row.revision == source.generation && paired.row.schema == source.schema &&
                paired.row.payload == source.payload && paired.row.digest == source.payloadHash)
            if (source.key.kind in foodFinancialDocumentKinds) requireFoodFinancialHistorySource(command, paired)
            else if (source.key.kind in setOf(LocalFoodProfileV1.ORDER, LocalFoodProfileV1.OWNER_INDEX, LocalFoodProfileV1.PORTION, LocalFoodProfileV1.PRICE_BATCH)) {
                val earlier = (paired.original as? RetainedOriginalV2.Streamed)?.evidence ?: error("food.payment.history_food_profile")
                val value = foodEnvelope(source.payload)
                check(source.schema == foodStockSchema(source.key.kind) && value.scope == command.scope && value.kind == source.key.kind &&
                    value.id == source.key.id && value.revision == source.generation && value.operationId == earlier.identity.producer.operationId &&
                    earlier.identity.producer.command == StockStreamingCommandsV2.FOOD && command.earlierOriginal(value.operationId) === paired.original &&
                    command.claim(foodSourceClaim(source.key)) == source.key.id)
            }
        }
        is StreamedSourceObservationV2.CurrentAbsent -> check(command.aggregateSource(source.key) == null)
        is StreamedSourceObservationV2.Claim -> check(command.claim(source.key) == source.ownerId)
        is StreamedSourceObservationV2.StockMember -> {
            val identity = source.record.identity
            val paired = command.aggregateSource(identity.key) ?: error("food.payment.history_member_missing")
            val original = (paired.original as? RetainedOriginalV2.Streamed)?.evidence ?: error("food.payment.history_member_profile")
            check(paired.row.revision == identity.generation && paired.row.digest == identity.payloadHash &&
                original.identity.producer.operationId == identity.producerOperationId &&
                command.earlierOriginal(identity.producerOperationId) === paired.original &&
                LocalStockRelationalCodecV2.project(command.scope, paired.row) == source.record)
        }
        is StreamedSourceObservationV2.StockRange -> check(stock) // Core closed the unfiltered range/EOF.
        else -> error("food.payment.history_unexpected_source")
    }
}

/** Exact legacy FOOD payment financial producer using ONLY actual earlier, fully joined originals. */
internal suspend fun requireFoodPaymentHistoricalComplete(command: RuntimeHistoricalCommand,
    original: RetainedCommandEvidence) {
    check(isFoodPaymentFinancialProducer(original) && original.scope == command.scope && original.operationId != command.operationId)
    val lineage = foodPaymentRetainedLineage(original) { id ->
        (command.earlierOriginal(id) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("food.payment.history_earlier_ancestor_missing")
    }
    LocalFoodPaymentProducer.requireComplete(original, lineage.start, lineage.originalRaw,
        lineage.resolutionRaw, lineage.originalUnknownApplication)
}
