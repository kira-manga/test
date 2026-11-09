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
internal data class LocalFoodPaymentResolutionRaw(val version: Int, val scope: BranchScope,
    val target: LocalFoodPaymentTarget, val originalActorId: DomainId, val recordingActorId: DomainId,
    val normalizedBody: String, val bodyDigest: PayloadDigest, val originalBodyDigest: PayloadDigest,
    val semanticDigest: PayloadDigest, val slotDigest: PayloadDigest, val recordedAt: UtcInstant) {
    fun body(): StoredBytes = StoredBytes(normalizedBody.encodeToByteArray(throwOnInvalidSequence = true))
}

internal fun foodPaymentResolutionWrite(raw: LocalFoodPaymentResolutionRaw): AggregateChange = AggregateChange(
    foodPaymentResolutionKey(raw.target.resolutionOperationId), null,
    LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.RESOLUTION),
    localSalesEncode(raw, LocalFoodPaymentResolutionRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES))

internal fun foodPaymentInitialApplication(raw: LocalFoodPaymentResolutionRaw): LocalFoodPaymentApplication =
    LocalFoodPaymentApplication(1, raw.scope, raw.target, raw.originalActorId, Revision(0),
        LocalFoodPaymentApplicationState.PENDING_RECONCILIATION,
        LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION, raw.recordedAt, null, null, emptyList(),
        LocalFoodPaymentFactPhase.RESOLUTION)

internal class FoodPaymentResolutionCut(val original: FoodPaymentRawCut,
    val raw: LocalSalesRow<LocalFoodPaymentResolutionRaw>, val application: LocalSalesRow<LocalFoodPaymentApplication>,
    val resolutionOriginal: RetainedCommandEvidence)

/** Whole current resolution/UNKNOWN/Start/native/source and exact initial-or-advanced app proof. */
internal suspend fun readFoodPaymentResolution(view: CommandReadView, operationId: OperationId,
    stock: LocalStockRepository): FoodPaymentResolutionCut {
    val row = LocalSalesStorage.required(view, foodPaymentResolutionKey(operationId),
        LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.RESOLUTION))
    val raw = localSalesDecode(row.payload, LocalFoodPaymentResolutionRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
    check(row.revision == Revision(1) && raw.version == 1 && raw.scope == view.scope &&
        raw.target.resolutionOperationId == operationId && foodPaymentResolutionWrite(raw).payload == row.payload &&
        raw.bodyDigest == sha256(raw.body().copyBytes()) && raw.semanticDigest == semanticDigest(raw.scope,
            raw.recordingActorId, LocalFoodPaymentProfile.COMMAND, raw.body()))
    val original = readFoodPaymentRaw(view, raw.target.recordingOperationId, stock)
    val slot = original.slot.value
    check(raw.target == slot.target && raw.originalActorId == slot.originalActorId &&
        raw.originalBodyDigest == original.raw.value.bodyDigest && raw.slotDigest == original.raw.value.slotDigest)
    val retained = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireFoodPaymentRetainedRow(retained, row)
    check(LocalFoodPaymentResolutionProducer.requireRaw(retained, original.startOriginal, original.rawOriginal).raw == raw)
    val appRow = LocalSalesStorage.required(view, foodPaymentApplicationKey(operationId),
        LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION))
    val app = localSalesDecode(appRow.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
    check(app.version == 1 && app.scope == raw.scope && app.target == raw.target && app.originalActorId == raw.originalActorId &&
        app.phase == LocalFoodPaymentFactPhase.RESOLUTION && app.revision.next() == appRow.revision &&
        app.financialRevisions.map { it.kind to it.id }.distinct().size == app.financialRevisions.size)
    when (app.state) {
        LocalFoodPaymentApplicationState.PENDING_RECONCILIATION -> {
            check(appRow.revision == Revision(1) && app == foodPaymentInitialApplication(raw))
            requireFoodPaymentRetainedRow(retained, appRow)
        }
        LocalFoodPaymentApplicationState.APPLIED -> {
            check(appRow.revision == Revision(2) && app.revision == Revision(1) && app.reason == null &&
                app.action != null && app.lastApplicationOperationId != null)
            val parent = original.application.value
            check(parent.state == LocalFoodPaymentApplicationState.APPLIED && parent.action == LocalFoodPaymentAction.UNKNOWN)
            val unknownApply = view.retainedCommand(checkNotNull(parent.lastApplicationOperationId))
                ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            val applied = view.retainedCommand(checkNotNull(app.lastApplicationOperationId))
                ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            requireFoodPaymentRetainedRow(applied, appRow)
            check(LocalFoodPaymentResolutionProducer.requireApply(applied, original.startOriginal,
                original.rawOriginal, retained, unknownApply).application == app)
            foodReadView(view).requireFoodPredecessors(applied)
        }
    }
    return FoodPaymentResolutionCut(original, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), retained)
}

/**
 * Distinct FOOD accounting wire. Current accountant, immutable recorder and original cash
 * custodian are all explicit; the ordinary actor==custodian movement writer remains strict.
 */
@Serializable
internal data class FoodPaymentCashMovementFactV1(val version: Int, val scope: BranchScope, val sourceId: DomainId,
    val operationId: OperationId, val accountingActorId: DomainId, val sessionId: DomainId,
    val custodianId: DomainId, val recordingOperationId: OperationId, val recordingActorId: DomainId,
    val phase: LocalFoodPaymentFactPhase, val originalBodyDigest: PayloadDigest,
    val kind: String, val amount: Money, val cashReceived: Money?, val changeGiven: Money?,
    val evidence: LocalCashEvidenceV1, val reason: String?, val recordedAt: UtcInstant)

internal fun foodPaymentAccountedMovementWrite(scope: BranchScope, operationId: OperationId,
    accountingActorId: DomainId, slot: LocalFoodPaymentSlot, phase: LocalFoodPaymentFactPhase,
    recordingActorId: DomainId, originalBodyDigest: PayloadDigest, movement: CashMovement, at: UtcInstant): AggregateChange {
    check(scope == slot.scope && movement.operationId == operationId && movement.sourceId == slot.target.attemptId &&
        movement.sessionId == slot.target.sessionId && movement.custodianId == slot.originalActorId &&
        movement.evidence.actorId == recordingActorId && movement.kind == CashMovementKind.CUSTOMER_RECEIPT &&
        movement.delta == slot.domain.amount && operationId !in setOf(slot.target.intentOperationId,
            slot.target.recordingOperationId, slot.target.resolutionOperationId))
    val value = FoodPaymentCashMovementFactV1(1, scope, movement.sourceId, operationId, accountingActorId,
        movement.sessionId, movement.custodianId, slot.target.factOperation(phase), recordingActorId, phase,
        originalBodyDigest, movement.kind.name, movement.delta, movement.cashReceived, movement.changeGiven,
        LocalCashEvidenceV1(movement.evidence.evidenceId, UtcInstant(movement.evidence.occurredAt)), movement.reason, at)
    return AggregateChange(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, movement.sourceId), null,
        "sales.food.cash-fact/v1", localSalesEncode(value, FoodPaymentCashMovementFactV1.serializer(), 8_192))
}

internal val foodPaymentCommands: Set<CommandKey> = setOf(LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND)
