package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Complete inert FOOD supervisor Review cut selected only by the actual native BEFORE
 * lineage and fully joined strictly earlier originals. Current mutable rows, a reconstructed
 * CommandReadView/stock facade, employee grant and native-resolution importer are absent. */
internal object LocalFoodSupervisorReviewHistorySources {
    suspend fun original(command: RuntimeHistoricalCommand, operationId: OperationId): FoodPaymentRawCut {
        val source = required(command, foodPaymentRawKey(operationId))
        val row = source.row
        check(row.revision == Revision(1) && row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.ORIGINAL))
        val raw = localSalesDecode(row.payload, LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        check(raw.version == 1 && raw.scope == command.scope && raw.target.recordingOperationId == operationId &&
            raw.recordingActorId == raw.originalActorId && foodPaymentRawWrite(raw).payload == row.payload &&
            raw.bodyDigest == sha256(raw.body().copyBytes()) &&
            raw.semanticDigest == semanticDigest(command.scope, raw.recordingActorId, LocalFoodPaymentProfile.COMMAND, raw.body()))
        val slot = slot(command, raw.target)
        check(raw.target == slot.row.value.target && raw.originalActorId == slot.row.value.originalActorId &&
            raw.slotDigest == slot.row.stored.digest)
        val origin = earlier(command, operationId)
        check(source.original === origin)
        requireFoodPaymentRetainedRow(origin.evidence, row)
        // Includes the genuine original's whole Start/native/source/raw-only proof. No
        // native receipt is made from parsed data; core already joined this exact capture.
        check(LocalFoodPaymentProducer.requireRaw(origin.evidence, slot.original.evidence).raw == raw)
        val appSource = required(command, foodPaymentApplicationKey(operationId))
        val appRow = appSource.row
        check(appRow.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION))
        val app = localSalesDecode(appRow.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
        check(app.version == 1 && app.scope == raw.scope && app.target == raw.target && app.originalActorId == raw.originalActorId &&
            app.phase == LocalFoodPaymentFactPhase.ORIGINAL && app.revision.next() == appRow.revision &&
            app.financialRevisions.map { it.kind to it.id }.distinct().size == app.financialRevisions.size)
        when (app.state) {
            LocalFoodPaymentApplicationState.PENDING_RECONCILIATION -> {
                check(appSource.original === origin && appRow.revision == Revision(1) && app == foodPaymentInitialApplication(raw))
                requireFoodPaymentRetainedRow(origin.evidence, appRow)
            }
            LocalFoodPaymentApplicationState.APPLIED -> {
                check(appRow.revision == Revision(2) && app.revision == Revision(1) && app.reason == null &&
                    app.action != null && app.lastApplicationOperationId != null)
                val applied = earlier(command, checkNotNull(app.lastApplicationOperationId))
                check(appSource.original === applied &&
                    LocalFoodPaymentProducer.requireApply(applied.evidence, slot.original.evidence, origin.evidence).application == app)
                requireFoodPaymentRetainedRow(applied.evidence, appRow)
            }
        }
        return FoodPaymentRawCut(slot.row, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), slot.original.evidence, origin.evidence)
    }

    suspend fun resolution(command: RuntimeHistoricalCommand, original: FoodPaymentRawCut): FoodPaymentResolutionCut? {
        val target = original.slot.value.target
        val source = command.aggregateSource(foodPaymentResolutionKey(target.resolutionOperationId))
        if (source == null) {
            // Exact real FOOD Review absence rule. SQL absence is no assertion about a
            // native/human outcome, no permission to retry and no inferred financial right.
            check(command.aggregateSource(foodPaymentApplicationKey(target.resolutionOperationId)) == null)
            return null
        }
        val row = source.row
        check(row.revision == Revision(1) && row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.RESOLUTION))
        val raw = localSalesDecode(row.payload, LocalFoodPaymentResolutionRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
        check(raw.version == 1 && raw.scope == command.scope && raw.target == target &&
            raw.target.resolutionOperationId == target.resolutionOperationId && raw.originalActorId == original.slot.value.originalActorId &&
            raw.originalBodyDigest == original.raw.value.bodyDigest && raw.slotDigest == original.raw.value.slotDigest &&
            foodPaymentResolutionWrite(raw).payload == row.payload && raw.bodyDigest == sha256(raw.body().copyBytes()) &&
            raw.semanticDigest == semanticDigest(raw.scope, raw.recordingActorId, LocalFoodPaymentProfile.COMMAND, raw.body()))
        val origin = earlier(command, target.resolutionOperationId)
        check(source.original === origin)
        requireFoodPaymentRetainedRow(origin.evidence, row)
        // The actual immutable UNKNOWN parent and exact resolver/body link are mandatory;
        // native resolution provenance and generic Applied/retained results cannot fit.
        check(LocalFoodPaymentResolutionProducer.requireRaw(origin.evidence, original.startOriginal, original.rawOriginal).raw == raw)
        val appSource = required(command, foodPaymentApplicationKey(target.resolutionOperationId))
        val appRow = appSource.row
        check(appRow.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION))
        val app = localSalesDecode(appRow.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
        check(app.version == 1 && app.scope == raw.scope && app.target == raw.target && app.originalActorId == raw.originalActorId &&
            app.phase == LocalFoodPaymentFactPhase.RESOLUTION && app.revision.next() == appRow.revision &&
            app.financialRevisions.map { it.kind to it.id }.distinct().size == app.financialRevisions.size)
        when (app.state) {
            LocalFoodPaymentApplicationState.PENDING_RECONCILIATION -> {
                check(appSource.original === origin && appRow.revision == Revision(1) && app == foodPaymentInitialApplication(raw))
                requireFoodPaymentRetainedRow(origin.evidence, appRow)
            }
            LocalFoodPaymentApplicationState.APPLIED -> {
                check(appRow.revision == Revision(2) && app.revision == Revision(1) && app.reason == null &&
                    app.action != null && app.lastApplicationOperationId != null)
                val parent = original.application.value
                check(parent.state == LocalFoodPaymentApplicationState.APPLIED && parent.action == LocalFoodPaymentAction.UNKNOWN)
                val unknownApply = earlier(command, checkNotNull(parent.lastApplicationOperationId))
                val unknownSource = required(command, foodPaymentApplicationKey(target.recordingOperationId))
                check(unknownSource.original === unknownApply)
                requireFoodPaymentRetainedRow(unknownApply.evidence, unknownSource.row)
                val applied = earlier(command, checkNotNull(app.lastApplicationOperationId))
                check(appSource.original === applied && LocalFoodPaymentResolutionProducer.requireApply(applied.evidence,
                    original.startOriginal, original.rawOriginal, origin.evidence, unknownApply.evidence).application == app)
                requireFoodPaymentRetainedRow(applied.evidence, appRow)
            }
        }
        return FoodPaymentResolutionCut(original, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), origin.evidence)
    }

    private class Slot(val row: LocalSalesRow<LocalFoodPaymentSlot>, val original: RetainedOriginalV2.Legacy)

    private suspend fun slot(command: RuntimeHistoricalCommand, target: LocalFoodPaymentTarget): Slot {
        val source = required(command, foodPaymentSlotKey(target.recordingOperationId))
        val row = source.row
        check(row.revision == Revision(1) && row.schema == LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.SLOT))
        val value = localSalesDecode(row.payload, LocalFoodPaymentSlot.serializer(), LocalFoodPaymentProfile.MAX_SLOT_BYTES)
        check(value.version == 1 && value.scope == command.scope && value.target == target && foodPaymentSlotWrite(value).payload == row.payload)
        val requirement = value.requirement()
        LocalFoodCashJournalDomain.validateRequirement(requirement)
        val origin = earlier(command, target.intentOperationId)
        check(source.original === origin && LocalFoodPaymentProducer.requireStart(origin.evidence).slot == value)
        requireFoodPaymentRetainedRow(origin.evidence, row)
        // Whole original stock/recipe/financial custody was already closed by the genuine
        // earlier installed producer, not by a today-row read or retained-source DTO here.
        val sources = listOf(value.masterAllocationId, value.clientAllocationId).map { id ->
            val paired = required(command, AggregateKey(PhysicalRecordingSourceProfileV1.KIND, id))
            check(paired.original === origin && paired.row.revision == Revision(1) &&
                paired.row.schema == PhysicalRecordingSourceProfileV1.SCHEMA)
            requireFoodPaymentRetainedRow(origin.evidence, paired.row)
            AggregateChange(paired.row.key, null, paired.row.schema, paired.row.payload)
        }
        val pair = requireFoodPaymentSourcePair(requirement, sources)
        check(value.masterSource == LocalFoodPaymentSourceReference.from(pair.first) && value.clientSource == LocalFoodPaymentSourceReference.from(pair.second))
        for (expected in foodPaymentFences(requirement)) {
            val paired = required(command, expected.key)
            check(paired.original === origin && paired.row.revision == Revision(1) && paired.row.schema == expected.schema &&
                paired.row.payload == expected.payload)
            requireFoodPaymentRetainedRow(origin.evidence, paired.row)
        }
        for (claim in foodPaymentLineageClaims(requirement)) check(command.claim(claim.key) == claim.ownerId)
        check(command.claim(LocalSalesStoreProfile.unique("payment_attempt", target.attemptId)) == target.collectionId)
        return Slot(LocalSalesRow(row, value), origin)
    }

    private suspend fun required(command: RuntimeHistoricalCommand, key: AggregateKey): RuntimeHistoricalAggregateSource =
        command.aggregateSource(key) ?: error("food.payment.supervisor.history_source_unavailable")
    private suspend fun earlier(command: RuntimeHistoricalCommand, operationId: OperationId): RetainedOriginalV2.Legacy =
        command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy ?: error("food.payment.supervisor.history_original_unavailable")
}
