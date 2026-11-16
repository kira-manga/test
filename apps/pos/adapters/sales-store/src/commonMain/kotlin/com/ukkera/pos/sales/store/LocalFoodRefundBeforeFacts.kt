package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Same literal raw/slot/resolution/native/source/initial-or-advanced application checks
 * and read order as the installed readers, over actual core native BEFORE owners only.
 * This is neither a historical CommandReadView nor native/employee authority. */
internal suspend fun readFoodRefundBeforeSlot(view: FoodFinancialBeforeReadBudget, operationId: OperationId): LocalSalesRow<LocalFoodRefundSlot> {
    val row = view.required(foodRefundSlotKey(operationId), LocalFoodRefundProfile.schema(LocalFoodRefundProfile.SLOT))
    val slot = localSalesDecode(row.payload, LocalFoodRefundSlot.serializer(), LocalFoodRefundProfile.MAX_SLOT_BYTES)
    check(row.revision == Revision(1) && slot.version == 1 && slot.scope == view.scope && slot.target.recordingOperationId == operationId &&
        foodRefundSlotWrite(slot).payload == row.payload)
    val requirement = slot.requirement()
    LocalFoodRefundCashJournalDomain.validateRequirement(requirement)
    val sources = listOf(requirement.masterAllocationId, requirement.clientAllocationId).map { id ->
        view.required(AggregateKey(PhysicalRecordingSourceProfileV1.KIND, id), PhysicalRecordingSourceProfileV1.SCHEMA)
            .also { check(it.revision == Revision(1)) }
    }
    val pair = requireFoodRefundSourcePair(requirement, sources.map { AggregateChange(it.key, null, it.schema, it.payload) })
    check(slot.masterSource == LocalFoodRefundSourceReference.from(pair.first) && slot.clientSource == LocalFoodRefundSourceReference.from(pair.second))
    val origin = view.retainedCommand(slot.target.intentOperationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireFoodRefundRetainedRow(origin, row)
    sources.forEach { requireFoodRefundRetainedRow(origin, it) }
    check(LocalFoodRefundProducer.requireStart(origin).slot == slot)
    view.requireRefundCustody(origin)
    view.requireFoodPredecessors(origin)
    check(view.claim(LocalSalesStoreProfile.unique("refund_attempt", slot.target.attemptId)) == slot.target.refundId)
    for (claim in foodRefundLineageClaims(requirement)) check(view.claim(claim.key) == claim.ownerId)
    for (expected in foodRefundFences(requirement)) {
        val fence = view.required(expected.key, expected.schema)
        check(fence.revision == Revision(1) && fence.payload == expected.payload)
        requireFoodRefundRetainedRow(origin, fence)
    }
    return LocalSalesRow(row, slot)
}

internal suspend fun readFoodRefundBeforeRaw(view: FoodFinancialBeforeReadBudget, operationId: OperationId): FoodRefundRawCut {
    val row = view.required(foodRefundRawKey(operationId), LocalFoodRefundProfile.schema(LocalFoodRefundProfile.ORIGINAL))
    val raw = localSalesDecode(row.payload, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
    check(raw.version == 1 && row.revision == Revision(1) && raw.scope == view.scope && raw.target.recordingOperationId == operationId &&
        raw.recordingActorId == raw.originalActorId && foodRefundRawWrite(raw).payload == row.payload &&
        raw.bodyDigest == sha256(raw.body().copyBytes()) && raw.semanticDigest == semanticDigest(view.scope, raw.recordingActorId,
            LocalFoodRefundProfile.COMMAND, raw.body()))
    val slot = readFoodRefundBeforeSlot(view, operationId)
    check(raw.target == slot.value.target && raw.originalActorId == slot.value.originalActorId && raw.slotDigest == slot.stored.digest)
    val start = view.retainedCommand(slot.value.target.intentOperationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val origin = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireFoodRefundRetainedRow(origin, row)
    check(LocalFoodRefundProducer.requireRaw(origin, start).raw == raw)
    val appRow = view.required(foodRefundApplicationKey(operationId), LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION))
    val app = localSalesDecode(appRow.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES)
    check(app.version == 1 && app.phase == LocalFoodRefundFactPhase.ORIGINAL && app.scope == raw.scope && app.target == raw.target && app.originalActorId == raw.originalActorId &&
        app.revision.next() == appRow.revision && app.financialRevisions.map { it.kind to it.id }.distinct().size == app.financialRevisions.size)
    when (app.state) {
        LocalFoodRefundApplicationState.PENDING_RECONCILIATION -> {
            check(app == foodRefundInitialApplication(raw) && appRow.revision == Revision(1))
            requireFoodRefundRetainedRow(origin, appRow)
        }
        LocalFoodRefundApplicationState.APPLIED -> {
            check(app.revision == Revision(1) && appRow.revision == Revision(2) && app.reason == null && app.action != null && app.lastApplicationOperationId != null)
            val applied = view.retainedCommand(checkNotNull(app.lastApplicationOperationId)) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            requireFoodRefundRetainedRow(applied, appRow)
            check(LocalFoodRefundProducer.requireApply(applied, start, origin).application == app)
            view.requireFoodPredecessors(applied)
        }
    }
    return FoodRefundRawCut(slot, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), start, origin)
}

internal suspend fun readFoodRefundBeforeResolution(view: FoodFinancialBeforeReadBudget, operationId: OperationId): FoodRefundResolutionCut {
    val row = view.required(foodRefundResolutionKey(operationId),
        LocalFoodRefundProfile.schema(LocalFoodRefundProfile.RESOLUTION))
    val raw = localSalesDecode(row.payload, LocalFoodRefundResolutionRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
    check(row.revision == Revision(1) && raw.version == 1 && raw.scope == view.scope &&
        raw.target.resolutionOperationId == operationId &&
        foodRefundResolutionWrite(raw).payload == row.payload &&
        raw.bodyDigest == sha256(raw.body().copyBytes()) && raw.semanticDigest == semanticDigest(raw.scope,
            raw.recordingActorId, LocalFoodRefundProfile.COMMAND, raw.body()))
    val original = readFoodRefundBeforeRaw(view, raw.target.recordingOperationId)
    val slot = original.slot.value
    check(raw.target == slot.target && raw.originalActorId == slot.originalActorId &&
        raw.originalBodyDigest == original.raw.value.bodyDigest && raw.slotDigest == original.raw.value.slotDigest)
    val retained = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireFoodRefundRetainedRow(retained, row)
    check(LocalFoodRefundResolutionProducer.requireRaw(retained, original.startOriginal, original.rawOriginal).raw == raw)
    val appRow = view.required(foodRefundApplicationKey(operationId),
        LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION))
    val app = localSalesDecode(appRow.payload, LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES)
    check(app.version == 1 && app.scope == raw.scope && app.target == raw.target && app.originalActorId == raw.originalActorId &&
        app.phase == LocalFoodRefundFactPhase.RESOLUTION && app.revision.next() == appRow.revision &&
        app.financialRevisions.map { it.kind to it.id }.distinct().size == app.financialRevisions.size)
    when (app.state) {
        LocalFoodRefundApplicationState.PENDING_RECONCILIATION -> {
            check(appRow.revision == Revision(1) && app == foodRefundInitialApplication(raw))
            requireFoodRefundRetainedRow(retained, appRow)
        }
        LocalFoodRefundApplicationState.APPLIED -> {
            check(appRow.revision == Revision(2) && app.revision == Revision(1) && app.reason == null &&
                app.action != null && app.lastApplicationOperationId != null)
            val parent = original.application.value
            check(parent.state == LocalFoodRefundApplicationState.APPLIED && parent.action == LocalFoodRefundAction.UNKNOWN)
            val unknownApply = view.retainedCommand(checkNotNull(parent.lastApplicationOperationId))
                ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            val applied = view.retainedCommand(checkNotNull(app.lastApplicationOperationId))
                ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            requireFoodRefundRetainedRow(applied, appRow)
            check(LocalFoodRefundResolutionProducer.requireApply(applied, original.startOriginal,
                original.rawOriginal, retained, unknownApply).application == app)
            view.requireFoodPredecessors(applied)
        }
    }
    return FoodRefundResolutionCut(original, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), retained)
}
