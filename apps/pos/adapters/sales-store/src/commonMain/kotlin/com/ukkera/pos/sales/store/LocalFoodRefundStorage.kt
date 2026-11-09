@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
internal data class LocalFoodRefundSourceReference(val allocationId: DomainId, val payloadDigest: PayloadDigest,
    val headerDigest: PayloadDigest, val allocationDigest: PayloadDigest, val slotIndex: Int) {
    companion object {
        fun from(value: LocalPhysicalJournalSourceProjection): LocalFoodRefundSourceReference = LocalFoodRefundSourceReference(
            value.allocationId, sha256(value.sourceCommitment.copyBytes()), value.headerDigest, value.allocationDigest, value.slotIndex)
    }
}

/** Immutable logical revision zero/storage generation one. Never consumed, recycled or re-owned. */
@Serializable
internal data class LocalFoodRefundSlot(val version: Int, val scope: BranchScope, val originalActorId: DomainId,
    val intentSemanticDigest: PayloadDigest, val domain: LocalFoodRefundDomainRequirement,
    val masterAllocationId: DomainId, val clientAllocationId: DomainId,
    val masterStorageProfile: String, val clientStorageProfile: String,
    val masterSource: LocalFoodRefundSourceReference, val clientSource: LocalFoodRefundSourceReference) {
    val target: LocalFoodRefundTarget get() = domain.target
    fun requirement(): LocalPhysicalJournalRequirementV1 = LocalPhysicalJournalRequirementV1(scope, originalActorId,
        target.intentOperationId, intentSemanticDigest, target.recordingOperationId, target.resolutionOperationId,
        target.attemptId, LocalFoodRefundProfile.DOMAIN, LocalFoodRefundProfile.COMMAND, foodRefundTargetBinding(target),
        masterAllocationId, clientAllocationId, domain.masterOwner.deviceId, domain.clientOwner.deviceId,
        LocalPhysicalSpoolStorageProfileV1.valueOf(masterStorageProfile), LocalPhysicalSpoolStorageProfileV1.valueOf(clientStorageProfile),
        foodRefundDomainBytes(domain))
}

/** Exact original normalized FOOD body, not a financial outcome or reconstructed native lease. */
@Serializable
internal data class LocalFoodRefundRaw(val version: Int, val scope: BranchScope, val target: LocalFoodRefundTarget,
    val originalActorId: DomainId, val recordingActorId: DomainId, val normalizedBody: String,
    val bodyDigest: PayloadDigest, val semanticDigest: PayloadDigest, val slotDigest: PayloadDigest,
    val recordedAt: UtcInstant) {
    fun body(): StoredBytes = StoredBytes(normalizedBody.encodeToByteArray(throwOnInvalidSequence = true))
}

@Serializable
public enum class LocalFoodRefundApplicationState { PENDING_RECONCILIATION, APPLIED }

/** Accounting state only. Original UNKNOWN/native capacity/source/fences remain immutable. */
@Serializable
internal data class LocalFoodRefundApplication(val version: Int, val scope: BranchScope,
    val target: LocalFoodRefundTarget, val originalActorId: DomainId, val revision: Revision,
    val state: LocalFoodRefundApplicationState, val reason: LocalFoodRefundPendingReason?,
    val updatedAt: UtcInstant, val lastApplicationOperationId: OperationId?,
    val action: LocalFoodRefundAction?, val financialRevisions: List<LocalFoodStorageRevisionV1>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val phase: LocalFoodRefundFactPhase = LocalFoodRefundFactPhase.ORIGINAL)

@Serializable
internal data class LocalFoodRefundEvent(val version: Int, val scope: BranchScope, val operationId: OperationId,
    val originalActorId: DomainId, val recordedAt: UtcInstant, val inputIds: List<DomainId>,
    val after: List<FoodAfterRefV1>, val framing: FoodFinancialManifestV2,
    val slotDigest: PayloadDigest?, val originalBodyDigest: PayloadDigest?,
    val result: LocalFoodRefundResult,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resolutionBodyDigest: PayloadDigest? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val originalUnknownApplicationOperationId: OperationId? = null)

internal fun foodRefundSlotWrite(value: LocalFoodRefundSlot): AggregateChange = AggregateChange(
    foodRefundSlotKey(value.target.recordingOperationId), null, LocalFoodRefundProfile.schema(LocalFoodRefundProfile.SLOT),
    localSalesEncode(value, LocalFoodRefundSlot.serializer(), LocalFoodRefundProfile.MAX_SLOT_BYTES))
internal fun foodRefundRawWrite(value: LocalFoodRefundRaw): AggregateChange = AggregateChange(
    foodRefundRawKey(value.target.recordingOperationId), null, LocalFoodRefundProfile.schema(LocalFoodRefundProfile.ORIGINAL),
    localSalesEncode(value, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES))
internal fun foodRefundApplicationWrite(value: LocalFoodRefundApplication,
    expectedStorageRevision: Revision?): AggregateChange {
    check(value.revision == (expectedStorageRevision ?: Revision(0)))
    check(value.version == 1 && if (value.state == LocalFoodRefundApplicationState.PENDING_RECONCILIATION)
        value.revision == Revision(0) && value.reason == LocalFoodRefundPendingReason.AWAITING_FINANCIAL_APPLICATION &&
            value.lastApplicationOperationId == null && value.action == null && value.financialRevisions.isEmpty()
        else value.revision == Revision(1) && value.reason == null && value.lastApplicationOperationId != null && value.action != null)
    return AggregateChange(foodRefundApplicationKey(value.target.factOperation(value.phase)), expectedStorageRevision,
        LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION), localSalesEncode(value,
            LocalFoodRefundApplication.serializer(), LocalFoodRefundProfile.MAX_APPLICATION_BYTES))
}

internal fun foodRefundInitialApplication(raw: LocalFoodRefundRaw): LocalFoodRefundApplication =
    LocalFoodRefundApplication(1, raw.scope, raw.target, raw.originalActorId, Revision(0),
        LocalFoodRefundApplicationState.PENDING_RECONCILIATION, LocalFoodRefundPendingReason.AWAITING_FINANCIAL_APPLICATION,
        raw.recordedAt, null, null, emptyList())

internal fun foodRefundStoredEvent(original: RetainedCommandEvidence): LocalFoodRefundEvent {
    check(original.committed && original.command in foodRefundCommands)
    val key = AggregateKey(LocalFoodRefundProfile.EVENT, DomainId(original.operationId.value))
    val row = original.aggregateWrites.single { it.key == key }
    check(row.storageGeneration == Revision(1) && row.schema == LocalFoodRefundProfile.schema(key.kind) &&
        row.payloadDigest == sha256(row.payload.copyBytes()))
    return localSalesDecode(row.payload, LocalFoodRefundEvent.serializer(), 65_536).also {
        check(it.version == 1 && it.scope == original.scope && it.operationId == original.operationId &&
            it.originalActorId == original.originalActorId && it.recordedAt == original.recordedAt &&
            localSalesEncode(it, LocalFoodRefundEvent.serializer(), 65_536) == row.payload)
    }
}

internal fun foodRefundInputs(original: RetainedCommandEvidence,
    frames: FoodFinancialBundleReaderV2? = null): List<FoodFinancialInputV1> {
    val event = foodRefundStoredEvent(original)
    val inputs = (frames ?: FoodFinancialBundleReaderV2(original, event.framing, allowStockInputs = true)).inputs()
    check(event.inputIds == inputs.map { foodFinancialInputId(original.operationId, it.key()) } &&
        inputs.map { it.key() }.distinct().size == inputs.size)
    inputs.forEach { input ->
        if (input.kind in foodRefundFinancialKinds) foodRefundFinancialEnvelope(input, original.scope)
        else {
            check(input.kind == LocalFoodProfileV1.PRICE_BATCH)
            foodPaymentFoodEnvelope(input, original.scope)
        }
    }
    return inputs
}

internal fun foodRefundFinancialEnvelope(input: FoodFinancialInputV1, scope: BranchScope): FoodFinancialEnvelopeV1 {
    check(input.stock == null && input.revision.value >= 1 && input.digest == sha256(input.bytes().copyBytes()) &&
        input.kind in foodRefundFinancialKinds &&
        input.schema in setOf(LocalFoodFinancialProfileV1.schema(input.kind), foodFinancialSchemaV2(input.kind), foodStockSchema(input.kind)))
    return (input.financial ?: foodFinancialEnvelope(input.bytes())).also { value ->
        check(value.scope == scope && value.kind == input.kind && value.id == input.id && value.revision == input.revision &&
            value.operationId == input.operationId)
        if (input.schema == foodStockSchema(input.kind)) requireFoodStockFinancialLiteral(input.bytes(), value)
        else if (value.framed) check(input.schema == foodFinancialSchemaV2(input.kind))
    }
}

internal val foodRefundFinancialKinds: Set<String> = setOf(LocalFoodFinancialProfileV1.REFUND,
    LocalFoodFinancialProfileV1.COLLECTION, LocalFoodFinancialProfileV1.REFUND_INDEX,
    LocalFoodFinancialProfileV1.SALE, LocalFoodFinancialProfileV1.ENTITLEMENT)

/** Framing is reused, not its old legacy-only observed-row wrapper or an absent producer DTO. */
internal class FoodRefundWrites(private val scope: BranchScope, private val operationId: OperationId,
    private val originalActorId: DomainId, private val at: UtcInstant,
    private val inputs: List<FoodFinancialInputV1>, private val cash: FoodFinancialCashInputV1) {
    constructor(context: CommandContext, at: UtcInstant, inputs: List<FoodFinancialInputV1>, cash: FoodFinancialCashInputV1) :
        this(context.scope, context.operationId, context.originalActorId, at, inputs, cash)
    private val frame = FoodFinancialBundleWriterV2(operationId, allowStockInputs = true)
    private val batches = foodFinancialPriceBasesV2(inputs, scope, allowStockInputs = true)
    private var refund: AggregateChange? = null
    fun refund(after: FoodRefundObligation): AggregateChange {
        check(refund == null)
        val key = AggregateKey(LocalFoodFinancialProfileV1.REFUND, after.id)
        val before = inputs.single { it.key() == key }
        // FOOD refund DOMAIN n becomes STORAGE n+1; a transition DOMAIN is the old storage generation.
        check(after.scope == scope && after.revision == before.revision)
        val value = FoodFinancialEnvelopeV1(scope, key.kind, key.id, before.revision.next(), operationId,
            FoodFinancialBodyV1.Refund(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeRefund(after) }),
            framed = true, originalBatches = batches)
        requireFoodFinancialEnvelope(value)
        return AggregateChange(key, before.revision, foodFinancialSchemaV2(key.kind), frame.document(value)).also { refund = it }
    }
    fun finish(result: LocalFoodRefundResult, extra: List<AggregateChange>, claims: List<ClaimChange>,
        readEvidence: List<AggregateChange>, deadline: UtcInstant? = null,
        slotDigest: PayloadDigest? = null, originalBodyDigest: PayloadDigest? = null,
        resolutionBodyDigest: PayloadDigest? = null,
        originalUnknownApplicationOperationId: OperationId? = null): ProposedOutcome.Apply {
        val business = listOf(checkNotNull(refund)) + extra
        check(business.map { it.key }.distinct().size == business.size)
        val (manifest, chunks) = frame.finish(inputs, cash)
        val encoded = LocalFoodRefundCodec.result(result)
        val event = LocalFoodRefundEvent(1, scope, operationId, originalActorId, at,
            inputs.map { foodFinancialInputId(operationId, it.key()) }, business.map { FoodAfterRefV1(
                it.key.kind, it.key.id, it.expectedRevision?.next() ?: Revision(1), sha256(it.payload.copyBytes())) },
            manifest, slotDigest, originalBodyDigest, result, resolutionBodyDigest, originalUnknownApplicationOperationId)
        val all = business + chunks + AggregateChange(AggregateKey(LocalFoodRefundProfile.EVENT, DomainId(operationId.value)),
            null, LocalFoodRefundProfile.schema(LocalFoodRefundProfile.EVENT), localSalesEncode(event, LocalFoodRefundEvent.serializer(), 65_536)) + readEvidence
        foodAdmitProposal(encoded, all, claims, emptyList())
        return ProposedOutcome.Apply(encoded, all, claims, mustCommitBefore = deadline)
    }
}

internal fun foodRefundReject(code: String): ProposedOutcome.Reject = ProposedOutcome.Reject(code,
    LocalFoodRefundCodec.result(LocalFoodRefundResult.Unavailable(code)))
internal fun foodRefundPending(target: LocalFoodRefundTarget, reason: LocalFoodRefundPendingReason,
    phase: LocalFoodRefundFactPhase = LocalFoodRefundFactPhase.ORIGINAL): ProposedOutcome.Reject =
    ProposedOutcome.Reject("food.refund.pending_reconciliation", LocalFoodRefundCodec.result(LocalFoodRefundResult.Pending(target, reason, phase)))
internal fun foodRefundOutcome(value: LocalFoodRefundOutcome, originalActorId: DomainId): RefundOutcome = when (value) {
    is LocalFoodRefundOutcome.Unknown -> RefundOutcome.Unknown(value.evidence.domain(originalActorId))
    is LocalFoodRefundOutcome.NotPaid -> RefundOutcome.NotPaid(value.evidence.domain(originalActorId), value.reason)
    is LocalFoodRefundOutcome.CashPaid -> RefundOutcome.CashPaid(value.evidence.domain(originalActorId))
    is LocalFoodRefundOutcome.ExternalAttested -> error("food.refund.tender_requires_reconciliation")
}
internal fun foodRefundAction(value: LocalFoodRefundOutcome): LocalFoodRefundAction = when (value) {
    is LocalFoodRefundOutcome.Unknown -> LocalFoodRefundAction.UNKNOWN
    is LocalFoodRefundOutcome.NotPaid -> LocalFoodRefundAction.NOT_PAID
    is LocalFoodRefundOutcome.CashPaid -> LocalFoodRefundAction.CASH_PAID
    is LocalFoodRefundOutcome.ExternalAttested -> error("food.refund.tender_requires_reconciliation")
}

internal class FoodRefundRawCut(val slot: LocalSalesRow<LocalFoodRefundSlot>,
    val raw: LocalSalesRow<LocalFoodRefundRaw>, val application: LocalSalesRow<LocalFoodRefundApplication>,
    val startOriginal: RetainedCommandEvidence, val rawOriginal: RetainedCommandEvidence)

internal fun requireFoodRefundRetainedRow(original: RetainedCommandEvidence, row: StoredAggregate) {
    check(original.committed && original.command in foodRefundCommands && original.aggregateWrites.single { it.key == row.key }.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    })
}

/** Current genuine original custody only. Never supplied to a Start/native source constructor. */
internal suspend fun readFoodRefundSlot(view: CommandReadView, operationId: OperationId,
    stock: com.ukkera.pos.inventory.store.LocalStockRepository): LocalSalesRow<LocalFoodRefundSlot> {
    val row = LocalSalesStorage.required(view, foodRefundSlotKey(operationId), LocalFoodRefundProfile.schema(LocalFoodRefundProfile.SLOT))
    val slot = localSalesDecode(row.payload, LocalFoodRefundSlot.serializer(), LocalFoodRefundProfile.MAX_SLOT_BYTES)
    check(row.revision == Revision(1) && slot.version == 1 && slot.scope == view.scope && slot.target.recordingOperationId == operationId &&
        foodRefundSlotWrite(slot).payload == row.payload)
    val requirement = slot.requirement()
    LocalFoodRefundCashJournalDomain.validateRequirement(requirement)
    val sources = listOf(requirement.masterAllocationId, requirement.clientAllocationId).map { id ->
        LocalSalesStorage.required(view, AggregateKey(PhysicalRecordingSourceProfileV1.KIND, id), PhysicalRecordingSourceProfileV1.SCHEMA)
            .also { check(it.revision == Revision(1)) }
    }
    val pair = requireFoodRefundSourcePair(requirement, sources.map { AggregateChange(it.key, null, it.schema, it.payload) })
    check(slot.masterSource == LocalFoodRefundSourceReference.from(pair.first) && slot.clientSource == LocalFoodRefundSourceReference.from(pair.second))
    val origin = view.retainedCommand(slot.target.intentOperationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireFoodRefundRetainedRow(origin, row)
    sources.forEach { requireFoodRefundRetainedRow(origin, it) }
    check(LocalFoodRefundProducer.requireStart(origin).slot == slot)
    requireFoodRefundOriginalCustody(view, origin, stock)
    foodReadView(view).requireFoodPredecessors(origin)
    check(view.claim(LocalSalesStoreProfile.unique("refund_attempt", slot.target.attemptId)) == slot.target.refundId)
    for (claim in foodRefundLineageClaims(requirement)) check(view.claim(claim.key) == claim.ownerId)
    for (expected in foodRefundFences(requirement)) {
        val fence = LocalSalesStorage.required(view, expected.key, expected.schema)
        check(fence.revision == Revision(1) && fence.payload == expected.payload)
        requireFoodRefundRetainedRow(origin, fence)
    }
    return LocalSalesRow(row, slot)
}

/** Raw original and initial OR exactly-once advanced accounting; never rematerializes pending. */
internal suspend fun readFoodRefundRaw(view: CommandReadView, operationId: OperationId,
    stock: com.ukkera.pos.inventory.store.LocalStockRepository): FoodRefundRawCut {
    val row = LocalSalesStorage.required(view, foodRefundRawKey(operationId), LocalFoodRefundProfile.schema(LocalFoodRefundProfile.ORIGINAL))
    val raw = localSalesDecode(row.payload, LocalFoodRefundRaw.serializer(), LocalFoodRefundProfile.MAX_RAW_BYTES)
    check(raw.version == 1 && row.revision == Revision(1) && raw.scope == view.scope && raw.target.recordingOperationId == operationId &&
        raw.recordingActorId == raw.originalActorId && foodRefundRawWrite(raw).payload == row.payload &&
        raw.bodyDigest == sha256(raw.body().copyBytes()) && raw.semanticDigest == semanticDigest(view.scope, raw.recordingActorId,
            LocalFoodRefundProfile.COMMAND, raw.body()))
    val slot = readFoodRefundSlot(view, operationId, stock)
    check(raw.target == slot.value.target && raw.originalActorId == slot.value.originalActorId && raw.slotDigest == slot.stored.digest)
    val start = view.retainedCommand(slot.value.target.intentOperationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val origin = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireFoodRefundRetainedRow(origin, row)
    check(LocalFoodRefundProducer.requireRaw(origin, start).raw == raw)
    val appRow = LocalSalesStorage.required(view, foodRefundApplicationKey(operationId), LocalFoodRefundProfile.schema(LocalFoodRefundProfile.APPLICATION))
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
            foodReadView(view).requireFoodPredecessors(applied)
        }
    }
    return FoodRefundRawCut(slot, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), start, origin)
}
