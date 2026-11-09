package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.CashMovement
import com.ukkera.pos.sales.CashMovementKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Immutable exact FOOD linked resolution. Original UNKNOWN body/custody/slot are not rewritten. */
@Serializable
internal data class LocalFoodRefundResolutionRaw(val version: Int, val scope: BranchScope,
    val target: LocalFoodRefundTarget, val originalActorId: DomainId, val recordingActorId: DomainId,
    val normalizedBody: String, val bodyDigest: PayloadDigest, val originalBodyDigest: PayloadDigest,
    val semanticDigest: PayloadDigest, val slotDigest: PayloadDigest, val recordedAt: UtcInstant) {
    fun body(): StoredBytes = StoredBytes(normalizedBody.encodeToByteArray(throwOnInvalidSequence = true))
}

internal fun foodRefundResolutionWrite(raw: LocalFoodRefundResolutionRaw): AggregateChange = AggregateChange(
    foodRefundResolutionKey(raw.target.resolutionOperationId), null,
    LocalFoodRefundProfile.schema(LocalFoodRefundProfile.RESOLUTION),
    localSalesEncode(raw, LocalFoodRefundResolutionRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES))

internal fun foodRefundInitialApplication(raw: LocalFoodRefundResolutionRaw): LocalFoodRefundApplication =
    LocalFoodRefundApplication(1, raw.scope, raw.target, raw.originalActorId, Revision(0),
        LocalFoodRefundApplicationState.PENDING_RECONCILIATION,
        LocalFoodRefundPendingReason.AWAITING_FINANCIAL_APPLICATION, raw.recordedAt, null, null, emptyList(),
        LocalFoodRefundFactPhase.RESOLUTION)

internal class FoodRefundResolutionCut(val original: FoodRefundRawCut,
    val raw: LocalSalesRow<LocalFoodRefundResolutionRaw>, val application: LocalSalesRow<LocalFoodRefundApplication>,
    val resolutionOriginal: RetainedCommandEvidence)

/** Whole current resolution/UNKNOWN/Start/native/source and exact initial-or-advanced app proof. */
internal suspend fun readFoodRefundResolution(view: CommandReadView, operationId: OperationId,
    stock: LocalStockRepository): FoodRefundResolutionCut {
    val row = LocalSalesStorage.required(view, foodRefundResolutionKey(operationId),
        LocalFoodRefundProfile.schema(LocalFoodRefundProfile.RESOLUTION))
    val raw = localSalesDecode(row.payload, LocalFoodRefundResolutionRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
    check(row.revision == Revision(1) && raw.version == 1 && raw.scope == view.scope &&
        raw.target.resolutionOperationId == operationId && raw.originalActorId == raw.recordingActorId &&
        foodRefundResolutionWrite(raw).payload == row.payload &&
        raw.bodyDigest == sha256(raw.body().copyBytes()) && raw.semanticDigest == semanticDigest(raw.scope,
            raw.recordingActorId, LocalFoodRefundProfile.COMMAND, raw.body()))
    val original = readFoodRefundRaw(view, raw.target.recordingOperationId, stock)
    val slot = original.slot.value
    check(raw.target == slot.target && raw.originalActorId == slot.originalActorId &&
        raw.originalBodyDigest == original.raw.value.bodyDigest && raw.slotDigest == original.raw.value.slotDigest)
    val retained = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireFoodRefundRetainedRow(retained, row)
    check(LocalFoodRefundResolutionProducer.requireRaw(retained, original.startOriginal, original.rawOriginal).raw == raw)
    val appRow = LocalSalesStorage.required(view, foodRefundApplicationKey(operationId),
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
            foodReadView(view).requireFoodPredecessors(applied)
        }
    }
    return FoodRefundResolutionCut(original, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), retained)
}

internal val foodRefundCommands: Set<CommandKey> = setOf(LocalFoodRefundProfile.COMMAND)
