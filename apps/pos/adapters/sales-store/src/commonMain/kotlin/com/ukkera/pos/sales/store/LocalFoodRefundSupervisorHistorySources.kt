package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Complete inert FOOD refund supervisor Review cut selected only by the actual native BEFORE
 * lineage and fully joined strictly earlier originals. Current mutable rows, a reconstructed
 * CommandReadView/stock facade, employee grant and native-resolution importer are absent. */
internal object LocalFoodRefundSupervisorHistorySources {
    suspend fun original(command: RuntimeHistoricalCommand, operationId: OperationId): FoodRefundRawCut {
        val source = required(command, foodRefundRawKey(operationId))
        val row = source.row
        check(row.revision == Revision(1) && row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.ORIGINAL))
        val raw = localSalesDecode(row.payload, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        check(raw.version == 1 && raw.scope == command.scope && raw.target.recordingOperationId == operationId &&
            raw.recordingActorId == raw.originalActorId && foodRefundRawWrite(raw).payload == row.payload &&
            raw.bodyDigest == sha256(raw.body().copyBytes()) &&
            raw.semanticDigest == semanticDigest(command.scope, raw.recordingActorId, LocalFoodRefundProfile.COMMAND, raw.body()))
        val slot = slot(command, raw.target)
        check(raw.target == slot.row.value.target && raw.originalActorId == slot.row.value.originalActorId &&
            raw.slotDigest == slot.row.stored.digest)
        val origin = earlier(command, operationId)
        check(source.original === origin)
        requireFoodRefundRetainedRow(origin.evidence, row)
        // Includes the genuine original's whole Start/native/source/raw-only proof. No
        // native receipt is made from parsed data; core already joined this exact capture.
        check(LocalFoodRefundProducer.requireRaw(origin.evidence, slot.original.evidence).raw == raw)
        val appSource = required(command, foodRefundApplicationKey(operationId))
        val appRow = appSource.row
        check(appRow.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION))
        val app = localSalesDecode(appRow.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES)
        check(app.version == 1 && app.scope == raw.scope && app.target == raw.target && app.originalActorId == raw.originalActorId &&
            app.phase == LocalFoodRefundFactPhase.ORIGINAL && app.revision.next() == appRow.revision &&
            app.financialRevisions.map { it.kind to it.id }.distinct().size == app.financialRevisions.size)
        when (app.state) {
            LocalFoodRefundApplicationState.PENDING_RECONCILIATION -> {
                check(appSource.original === origin && appRow.revision == Revision(1) && app == foodRefundInitialApplication(raw))
                requireFoodRefundRetainedRow(origin.evidence, appRow)
            }
            LocalFoodRefundApplicationState.APPLIED -> {
                check(appRow.revision == Revision(2) && app.revision == Revision(1) && app.reason == null &&
                    app.action != null && app.lastApplicationOperationId != null)
                val applied = earlier(command, checkNotNull(app.lastApplicationOperationId))
                check(appSource.original === applied &&
                    LocalFoodRefundProducer.requireApply(applied.evidence, slot.original.evidence, origin.evidence).application == app)
                requireFoodRefundRetainedRow(applied.evidence, appRow)
            }
        }
        return FoodRefundRawCut(slot.row, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), slot.original.evidence, origin.evidence)
    }

    suspend fun resolution(command: RuntimeHistoricalCommand, original: FoodRefundRawCut): FoodRefundResolutionCut? {
        val target = original.slot.value.target
        val source = command.aggregateSource(foodRefundResolutionKey(target.resolutionOperationId))
        if (source == null) {
            // Exact real FOOD Review absence rule. SQL absence is no assertion about a
            // native/human outcome, no permission to retry and no inferred financial right.
            check(command.aggregateSource(foodRefundApplicationKey(target.resolutionOperationId)) == null)
            return null
        }
        val row = source.row
        check(row.revision == Revision(1) && row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.RESOLUTION))
        val raw = localSalesDecode(row.payload, LocalFoodRefundResolutionRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
        check(raw.version == 1 && raw.scope == command.scope && raw.target == target &&
            raw.target.resolutionOperationId == target.resolutionOperationId && raw.originalActorId == original.slot.value.originalActorId &&
            raw.originalBodyDigest == original.raw.value.bodyDigest && raw.slotDigest == original.raw.value.slotDigest &&
            foodRefundResolutionWrite(raw).payload == row.payload && raw.bodyDigest == sha256(raw.body().copyBytes()) &&
            raw.semanticDigest == semanticDigest(raw.scope, raw.recordingActorId, LocalFoodRefundProfile.COMMAND, raw.body()))
        val origin = earlier(command, target.resolutionOperationId)
        check(source.original === origin)
        requireFoodRefundRetainedRow(origin.evidence, row)
        // The actual immutable UNKNOWN parent and exact resolver/body link are mandatory;
        // native resolution provenance and generic Applied/retained results cannot fit.
        check(LocalFoodRefundResolutionProducer.requireRaw(origin.evidence, original.startOriginal, original.rawOriginal).raw == raw)
        val appSource = required(command, foodRefundApplicationKey(target.resolutionOperationId))
        val appRow = appSource.row
        check(appRow.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION))
        val app = localSalesDecode(appRow.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES)
        check(app.version == 1 && app.scope == raw.scope && app.target == raw.target && app.originalActorId == raw.originalActorId &&
            app.phase == LocalFoodRefundFactPhase.RESOLUTION && app.revision.next() == appRow.revision &&
            app.financialRevisions.map { it.kind to it.id }.distinct().size == app.financialRevisions.size)
        when (app.state) {
            LocalFoodRefundApplicationState.PENDING_RECONCILIATION -> {
                check(appSource.original === origin && appRow.revision == Revision(1) && app == foodRefundInitialApplication(raw))
                requireFoodRefundRetainedRow(origin.evidence, appRow)
            }
            LocalFoodRefundApplicationState.APPLIED -> {
                check(appRow.revision == Revision(2) && app.revision == Revision(1) && app.reason == null &&
                    app.action != null && app.lastApplicationOperationId != null)
                val parent = original.application.value
                check(parent.state == LocalFoodRefundApplicationState.APPLIED && parent.action == LocalFoodRefundAction.UNKNOWN)
                val unknownApply = earlier(command, checkNotNull(parent.lastApplicationOperationId))
                val unknownSource = required(command, foodRefundApplicationKey(target.recordingOperationId))
                check(unknownSource.original === unknownApply)
                requireFoodRefundRetainedRow(unknownApply.evidence, unknownSource.row)
                val applied = earlier(command, checkNotNull(app.lastApplicationOperationId))
                check(appSource.original === applied && LocalFoodRefundResolutionProducer.requireApply(applied.evidence,
                    original.startOriginal, original.rawOriginal, origin.evidence, unknownApply.evidence).application == app)
                requireFoodRefundRetainedRow(applied.evidence, appRow)
            }
        }
        return FoodRefundResolutionCut(original, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), origin.evidence)
    }

    private class Slot(val row: LocalSalesRow<LocalFoodRefundSlot>, val original: RetainedOriginalV2.Legacy)

    private suspend fun slot(command: RuntimeHistoricalCommand, target: LocalFoodRefundTarget): Slot {
        val source = required(command, foodRefundSlotKey(target.recordingOperationId))
        val row = source.row
        check(row.revision == Revision(1) && row.schema == LocalFoodRefundProfile.schema(LocalFoodRefundProfile.SLOT))
        val value = localSalesDecode(row.payload, LocalFoodRefundSlot.serializer(), LocalFoodRefundProfile.MAX_SLOT_BYTES)
        check(value.version == 1 && value.scope == command.scope && value.target == target && foodRefundSlotWrite(value).payload == row.payload)
        val requirement = value.requirement()
        LocalFoodRefundCashJournalDomain.validateRequirement(requirement)
        val origin = earlier(command, target.intentOperationId)
        check(source.original === origin && LocalFoodRefundProducer.requireStart(origin.evidence).slot == value)
        requireFoodRefundRetainedRow(origin.evidence, row)
        // Whole original stock/recipe/financial custody was already closed by the genuine
        // earlier installed producer, not by a today-row read or retained-source DTO here.
        val sources = listOf(value.masterAllocationId, value.clientAllocationId).map { id ->
            val paired = required(command, AggregateKey(PhysicalRecordingSourceProfileV1.KIND, id))
            check(paired.original === origin && paired.row.revision == Revision(1) &&
                paired.row.schema == PhysicalRecordingSourceProfileV1.SCHEMA)
            requireFoodRefundRetainedRow(origin.evidence, paired.row)
            AggregateChange(paired.row.key, null, paired.row.schema, paired.row.payload)
        }
        val pair = requireFoodRefundSourcePair(requirement, sources)
        check(value.masterSource == LocalFoodRefundSourceReference.from(pair.first) && value.clientSource == LocalFoodRefundSourceReference.from(pair.second))
        for (expected in foodRefundFences(requirement)) {
            val paired = required(command, expected.key)
            check(paired.original === origin && paired.row.revision == Revision(1) && paired.row.schema == expected.schema &&
                paired.row.payload == expected.payload)
            requireFoodRefundRetainedRow(origin.evidence, paired.row)
        }
        for (claim in foodRefundLineageClaims(requirement)) check(command.claim(claim.key) == claim.ownerId)
        check(command.claim(LocalSalesStoreProfile.unique("refund_attempt", target.attemptId)) == target.refundId)
        return Slot(LocalSalesRow(row, value), origin)
    }

    private suspend fun required(command: RuntimeHistoricalCommand, key: AggregateKey): RuntimeHistoricalAggregateSource =
        command.aggregateSource(key) ?: error("food.refund.supervisor.history_source_unavailable")
    private suspend fun earlier(command: RuntimeHistoricalCommand, operationId: OperationId): RetainedOriginalV2.Legacy =
        command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy ?: error("food.refund.supervisor.history_original_unavailable")
}
