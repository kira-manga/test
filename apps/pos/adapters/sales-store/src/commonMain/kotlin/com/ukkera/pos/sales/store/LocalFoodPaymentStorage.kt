@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable

@Serializable
internal data class LocalFoodPaymentSourceReference(val allocationId: DomainId, val payloadDigest: PayloadDigest,
    val headerDigest: PayloadDigest, val allocationDigest: PayloadDigest, val slotIndex: Int) {
    companion object {
        fun from(value: LocalPhysicalJournalSourceProjection): LocalFoodPaymentSourceReference = LocalFoodPaymentSourceReference(
            value.allocationId, sha256(value.sourceCommitment.copyBytes()), value.headerDigest, value.allocationDigest, value.slotIndex)
    }
}

/** Immutable logical revision zero/storage generation one. Never consumed, recycled or re-owned. */
@Serializable
internal data class LocalFoodPaymentSlot(val version: Int, val scope: BranchScope, val originalActorId: DomainId,
    val intentSemanticDigest: PayloadDigest, val domain: LocalFoodPaymentDomainRequirement,
    val masterAllocationId: DomainId, val clientAllocationId: DomainId,
    val masterStorageProfile: String, val clientStorageProfile: String,
    val masterSource: LocalFoodPaymentSourceReference, val clientSource: LocalFoodPaymentSourceReference) {
    val target: LocalFoodPaymentTarget get() = domain.target
    fun requirement(): LocalPhysicalJournalRequirementV1 = LocalPhysicalJournalRequirementV1(scope, originalActorId,
        target.intentOperationId, intentSemanticDigest, target.recordingOperationId, target.resolutionOperationId,
        target.attemptId, LocalFoodPaymentProfile.DOMAIN, LocalFoodPaymentProfile.COMMAND, foodPaymentTargetBinding(target),
        masterAllocationId, clientAllocationId, domain.masterOwner.deviceId, domain.clientOwner.deviceId,
        LocalPhysicalSpoolStorageProfileV1.valueOf(masterStorageProfile), LocalPhysicalSpoolStorageProfileV1.valueOf(clientStorageProfile),
        foodPaymentDomainBytes(domain))
}

/** Exact original normalized FOOD body, not a financial outcome or reconstructed native lease. */
@Serializable
internal data class LocalFoodPaymentRaw(val version: Int, val scope: BranchScope, val target: LocalFoodPaymentTarget,
    val originalActorId: DomainId, val recordingActorId: DomainId, val normalizedBody: String,
    val bodyDigest: PayloadDigest, val semanticDigest: PayloadDigest, val slotDigest: PayloadDigest,
    val recordedAt: UtcInstant) {
    fun body(): StoredBytes = StoredBytes(normalizedBody.encodeToByteArray(throwOnInvalidSequence = true))
}

@Serializable
public enum class LocalFoodPaymentApplicationState { PENDING_RECONCILIATION, APPLIED }

/** Accounting state only. Original UNKNOWN/native capacity/source/fences remain immutable. */
@Serializable
internal data class LocalFoodPaymentApplication(val version: Int, val scope: BranchScope,
    val target: LocalFoodPaymentTarget, val originalActorId: DomainId, val revision: Revision,
    val state: LocalFoodPaymentApplicationState, val reason: LocalFoodPaymentPendingReason?,
    val updatedAt: UtcInstant, val lastApplicationOperationId: OperationId?,
    val action: LocalFoodPaymentAction?, val financialRevisions: List<LocalFoodStorageRevisionV1>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val phase: LocalFoodPaymentFactPhase = LocalFoodPaymentFactPhase.ORIGINAL)

@Serializable
internal data class LocalFoodPaymentEvent(val version: Int, val scope: BranchScope, val operationId: OperationId,
    val originalActorId: DomainId, val recordedAt: UtcInstant, val inputIds: List<DomainId>,
    val after: List<FoodAfterRefV1>, val framing: FoodFinancialManifestV2,
    val slotDigest: PayloadDigest?, val originalBodyDigest: PayloadDigest?,
    val result: LocalFoodPaymentResult,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val resolutionBodyDigest: PayloadDigest? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val originalUnknownApplicationOperationId: OperationId? = null)

internal fun foodPaymentSlotWrite(value: LocalFoodPaymentSlot): AggregateChange = AggregateChange(
    foodPaymentSlotKey(value.target.recordingOperationId), null, LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.SLOT),
    localSalesEncode(value, LocalFoodPaymentSlot.serializer(), LocalFoodPaymentProfile.MAX_SLOT_BYTES))
internal fun foodPaymentRawWrite(value: LocalFoodPaymentRaw): AggregateChange = AggregateChange(
    foodPaymentRawKey(value.target.recordingOperationId), null, LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.ORIGINAL),
    localSalesEncode(value, LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES))
internal fun foodPaymentApplicationWrite(value: LocalFoodPaymentApplication,
    expectedStorageRevision: Revision?): AggregateChange {
    check(value.revision == (expectedStorageRevision ?: Revision(0)))
    check(value.version == 1 && if (value.state == LocalFoodPaymentApplicationState.PENDING_RECONCILIATION)
        value.revision == Revision(0) && value.reason == LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION &&
            value.lastApplicationOperationId == null && value.action == null && value.financialRevisions.isEmpty()
        else value.revision == Revision(1) && value.reason == null && value.lastApplicationOperationId != null && value.action != null)
    return AggregateChange(foodPaymentApplicationKey(value.target.factOperation(value.phase)), expectedStorageRevision,
        LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION), localSalesEncode(value,
            LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES))
}

internal fun foodPaymentInitialApplication(raw: LocalFoodPaymentRaw): LocalFoodPaymentApplication =
    LocalFoodPaymentApplication(1, raw.scope, raw.target, raw.originalActorId, Revision(0),
        LocalFoodPaymentApplicationState.PENDING_RECONCILIATION, LocalFoodPaymentPendingReason.AWAITING_FINANCIAL_APPLICATION,
        raw.recordedAt, null, null, emptyList())

internal fun foodPaymentStoredEvent(original: RetainedCommandEvidence): LocalFoodPaymentEvent {
    check(original.committed && original.command in foodPaymentCommands)
    val key = AggregateKey(LocalFoodPaymentProfile.EVENT, DomainId(original.operationId.value))
    val row = original.aggregateWrites.single { it.key == key }
    check(row.storageGeneration == Revision(1) && row.schema == LocalFoodPaymentProfile.schema(key.kind) &&
        row.payloadDigest == sha256(row.payload.copyBytes()))
    return localSalesDecode(row.payload, LocalFoodPaymentEvent.serializer(), 65_536).also {
        check(it.version == 1 && it.scope == original.scope && it.operationId == original.operationId &&
            it.originalActorId == original.originalActorId && it.recordedAt == original.recordedAt &&
            localSalesEncode(it, LocalFoodPaymentEvent.serializer(), 65_536) == row.payload)
    }
}

internal fun foodPaymentInputs(original: RetainedCommandEvidence,
    frames: FoodFinancialBundleReaderV2? = null): List<FoodFinancialInputV1> {
    val event = foodPaymentStoredEvent(original)
    val inputs = (frames ?: FoodFinancialBundleReaderV2(original, event.framing, allowStockInputs = true)).inputs()
    check(event.inputIds == inputs.map { foodFinancialInputId(original.operationId, it.key()) } &&
        inputs.map { it.key() }.distinct().size == inputs.size)
    inputs.forEach { input ->
        if (input.kind == LocalFoodFinancialProfileV1.COLLECTION) foodPaymentFinancialEnvelope(input, original.scope)
        else foodPaymentFoodEnvelope(input, original.scope)
    }
    return inputs
}

internal fun foodPaymentFoodEnvelope(input: FoodFinancialInputV1, scope: BranchScope): FoodEnvelopeV1 {
    check(input.stock == null && input.revision.value >= 1 && input.digest == sha256(input.bytes().copyBytes()))
    check(input.kind in setOf(LocalFoodProfileV1.ORDER, LocalFoodProfileV1.OWNER_INDEX, LocalFoodProfileV1.PORTION, LocalFoodProfileV1.PRICE_BATCH) &&
        input.financial == null && input.schema in setOf(LocalFoodProfileV1.schema(input.kind), foodStockSchema(input.kind)))
    return foodEnvelope(input.bytes()).also { value ->
        check(value.scope == scope && value.kind == input.kind && value.id == input.id && value.revision == input.revision &&
            value.operationId == input.operationId)
    }
}

internal fun foodPaymentFinancialEnvelope(input: FoodFinancialInputV1, scope: BranchScope): FoodFinancialEnvelopeV1 {
    check(input.stock == null && input.revision.value >= 1 && input.digest == sha256(input.bytes().copyBytes()) &&
        input.kind == LocalFoodFinancialProfileV1.COLLECTION &&
        input.schema in setOf(LocalFoodFinancialProfileV1.schema(input.kind), foodFinancialSchemaV2(input.kind), foodStockSchema(input.kind)))
    return (input.financial ?: foodFinancialEnvelope(input.bytes())).also { value ->
        check(value.scope == scope && value.kind == input.kind && value.id == input.id && value.revision == input.revision &&
            value.operationId == input.operationId)
        if (input.schema == foodStockSchema(input.kind)) requireFoodStockFinancialLiteral(input.bytes(), value)
        else if (value.framed) check(input.schema == foodFinancialSchemaV2(input.kind))
    }
}

/** Only exact current rows from a real loan. Normalized producers are never recast as legacy. */
internal fun foodPaymentInput(row: FoodRow): FoodFinancialInputV1 = foodPaymentInput(row.row, row.envelope.operationId)
internal fun foodPaymentInput(row: FoodFinancialRow): FoodFinancialInputV1 =
    foodPaymentInput(row.row, row.envelope.operationId).copy(financial = row.envelope)
private fun foodPaymentInput(row: StoredAggregate, operationId: OperationId): FoodFinancialInputV1 = FoodFinancialInputV1(
    row.key.kind, row.key.id, row.revision, row.schema, row.digest, operationId,
    row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true))
internal fun foodPaymentCutInputs(cut: FoodFinancialCollectionCut): List<FoodFinancialInputV1> = listOf(foodPaymentInput(cut.row),
    foodPaymentInput(cut.order.order), foodPaymentInput(cut.order.ownerIndex)) + cut.portions.map(::foodPaymentInput) + cut.batches.map(::foodPaymentInput)

/**
 * Payment uses the already locked authentic recipe/price originals; it never reprices or sends.
 * Every current row still requires its actual complete producer and direct original proof.
 * Current recipe activation belongs to SEND, not permission to rewrite this locked quote.
 */
internal suspend fun foodPaymentCollectionCut(view: CommandReadView, id: DomainId,
    recipes: TransactionFoodStockSource): FoodFinancialCollectionCut {
    val row = foodFinancialRead(view, LocalFoodFinancialProfileV1.COLLECTION, id) ?: throw SalesRuleViolation(SalesRule.INVALID_STATE)
    val value = foodFinancialCollection(row.envelope)
    // Genuine closed/handoff headers can deny the current CASH lane without being
    // mislabeled as corrupt. Callers require open/original custody before inventory/effects.
    val order = foodOrder(view, value.orderId, includeClosed = true)
    check(order.value.financialCollectionId == id && order.value.ownerId == value.currentOwnerId &&
        order.value.creatorId == value.originalCreatorId)
    val portions = order.value.portionIds.map { portionId -> foodPortionSummary(view, order, portionId).also {
        recipes.requireLegacyRecipe((it.envelope.body as FoodBodyV1.Portion).recipe, view.scope)
    } }
    val batches = order.value.batchIds.map { foodRead(view, LocalFoodProfileV1.PRICE_BATCH, it) ?: error("food.payment.price_missing") }
    requireFoodFinancialQuote(value, order.value, portions.map { it.envelope }, batches.map { it.envelope })
    for (attempt in value.attempts) {
        check(view.claim(LocalSalesStoreProfile.unique("payment_attempt", attempt.id)) == id)
        if (attempt.payment != null) {
            check(view.claim(LocalSalesStoreProfile.unique("payment_record", attempt.id)) == id)
            if (attempt.tender == Tender.Cash) check(view.claim(CashNonSaleStoreProfile.cashSourceClaim(attempt.id)) == attempt.originalCashSessionId)
        }
    }
    return FoodFinancialCollectionCut(row, value, order, portions, batches)
}

/** Framing is reused, not its old legacy-only observed-row wrapper or an absent producer DTO. */
internal class FoodPaymentWrites(private val scope: BranchScope, private val operationId: OperationId,
    private val originalActorId: DomainId, private val at: UtcInstant,
    private val inputs: List<FoodFinancialInputV1>, private val cash: FoodFinancialCashInputV1) {
    constructor(context: CommandContext, at: UtcInstant, inputs: List<FoodFinancialInputV1>, cash: FoodFinancialCashInputV1) :
        this(context.scope, context.operationId, context.originalActorId, at, inputs, cash)
    private val frame = FoodFinancialBundleWriterV2(operationId, allowStockInputs = true)
    private val batches = foodFinancialPriceBasesV2(inputs, scope, allowStockInputs = true)
    private var collection: AggregateChange? = null
    fun collection(after: FoodCollection): AggregateChange {
        check(collection == null)
        val key = AggregateKey(LocalFoodFinancialProfileV1.COLLECTION, after.id)
        val before = inputs.single { it.key() == key }
        // FoodCollection DOMAIN n becomes STORAGE n+1; a transition's DOMAIN is the old storage generation.
        check(after.scope == scope && after.revision == before.revision)
        val value = FoodFinancialEnvelopeV1(scope, key.kind, key.id, before.revision.next(), operationId,
            FoodFinancialBodyV1.Collection(foodFinancialNewSnapshot { FoodFinancialFramesV2.encodeCollection(after) }),
            framed = true, originalBatches = batches)
        requireFoodFinancialEnvelope(value)
        return AggregateChange(key, before.revision, foodFinancialSchemaV2(key.kind), frame.document(value)).also { collection = it }
    }
    fun finish(result: LocalFoodPaymentResult, extra: List<AggregateChange>, claims: List<ClaimChange>,
        readEvidence: List<AggregateChange>, deadline: UtcInstant? = null,
        slotDigest: PayloadDigest? = null, originalBodyDigest: PayloadDigest? = null,
        resolutionBodyDigest: PayloadDigest? = null,
        originalUnknownApplicationOperationId: OperationId? = null): ProposedOutcome.Apply {
        val business = listOf(checkNotNull(collection)) + extra
        check(business.map { it.key }.distinct().size == business.size)
        val (manifest, chunks) = frame.finish(inputs, cash)
        val encoded = LocalFoodPaymentCodec.result(result)
        val event = LocalFoodPaymentEvent(1, scope, operationId, originalActorId, at,
            inputs.map { foodFinancialInputId(operationId, it.key()) }, business.map { FoodAfterRefV1(
                it.key.kind, it.key.id, it.expectedRevision?.next() ?: Revision(1), sha256(it.payload.copyBytes())) },
            manifest, slotDigest, originalBodyDigest, result, resolutionBodyDigest, originalUnknownApplicationOperationId)
        val all = business + chunks + AggregateChange(AggregateKey(LocalFoodPaymentProfile.EVENT, DomainId(operationId.value)),
            null, LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.EVENT), localSalesEncode(event, LocalFoodPaymentEvent.serializer(), 65_536)) + readEvidence
        foodAdmitProposal(encoded, all, claims, emptyList())
        return ProposedOutcome.Apply(encoded, all, claims, mustCommitBefore = deadline)
    }
}

internal fun foodPaymentReject(code: String): ProposedOutcome.Reject = ProposedOutcome.Reject(code,
    LocalFoodPaymentCodec.result(LocalFoodPaymentResult.Unavailable(code)))
internal fun foodPaymentPending(target: LocalFoodPaymentTarget, reason: LocalFoodPaymentPendingReason,
    phase: LocalFoodPaymentFactPhase = LocalFoodPaymentFactPhase.ORIGINAL): ProposedOutcome.Reject =
    ProposedOutcome.Reject("food.payment.pending_reconciliation", LocalFoodPaymentCodec.result(LocalFoodPaymentResult.Pending(target, reason, phase)))
internal fun foodPaymentOutcome(value: LocalFoodCashOutcome, originalActorId: DomainId): CollectionOutcome = when (value) {
    is LocalFoodCashOutcome.Unknown -> CollectionOutcome.Unknown(value.evidence.domain(originalActorId))
    is LocalFoodCashOutcome.NotPaid -> CollectionOutcome.NotPaid(value.evidence.domain(originalActorId), value.reason)
    is LocalFoodCashOutcome.CashReceived -> CollectionOutcome.CashReceived(value.received, value.change, value.evidence.domain(originalActorId))
    is LocalFoodCashOutcome.ExternalAttested -> error("food.payment.tender_requires_reconciliation")
}
internal fun foodPaymentAction(value: LocalFoodCashOutcome): LocalFoodPaymentAction = when (value) {
    is LocalFoodCashOutcome.Unknown -> LocalFoodPaymentAction.UNKNOWN
    is LocalFoodCashOutcome.NotPaid -> LocalFoodPaymentAction.NOT_PAID
    is LocalFoodCashOutcome.CashReceived -> LocalFoodPaymentAction.CASH_PAID
    is LocalFoodCashOutcome.ExternalAttested -> error("food.payment.tender_requires_reconciliation")
}

internal class FoodPaymentRawCut(val slot: LocalSalesRow<LocalFoodPaymentSlot>,
    val raw: LocalSalesRow<LocalFoodPaymentRaw>, val application: LocalSalesRow<LocalFoodPaymentApplication>,
    val startOriginal: RetainedCommandEvidence, val rawOriginal: RetainedCommandEvidence)

internal fun requireFoodPaymentRetainedRow(original: RetainedCommandEvidence, row: StoredAggregate) {
    check(original.committed && original.command in foodPaymentCommands && original.aggregateWrites.single { it.key == row.key }.let {
        it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload && it.payloadDigest == row.digest
    })
}

/** Current genuine original custody only. Never supplied to a Start/native source constructor. */
internal suspend fun readFoodPaymentSlot(view: CommandReadView, operationId: OperationId,
    stock: com.ukkera.pos.inventory.store.LocalStockRepository): LocalSalesRow<LocalFoodPaymentSlot> {
    val row = LocalSalesStorage.required(view, foodPaymentSlotKey(operationId), LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.SLOT))
    val slot = localSalesDecode(row.payload, LocalFoodPaymentSlot.serializer(), LocalFoodPaymentProfile.MAX_SLOT_BYTES)
    check(row.revision == Revision(1) && slot.version == 1 && slot.scope == view.scope && slot.target.recordingOperationId == operationId &&
        foodPaymentSlotWrite(slot).payload == row.payload)
    val requirement = slot.requirement()
    LocalFoodCashJournalDomain.validateRequirement(requirement)
    val sources = listOf(requirement.masterAllocationId, requirement.clientAllocationId).map { id ->
        LocalSalesStorage.required(view, AggregateKey(PhysicalRecordingSourceProfileV1.KIND, id), PhysicalRecordingSourceProfileV1.SCHEMA)
            .also { check(it.revision == Revision(1)) }
    }
    val pair = requireFoodPaymentSourcePair(requirement, sources.map { AggregateChange(it.key, null, it.schema, it.payload) })
    check(slot.masterSource == LocalFoodPaymentSourceReference.from(pair.first) && slot.clientSource == LocalFoodPaymentSourceReference.from(pair.second))
    val origin = view.retainedCommand(slot.target.intentOperationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireFoodPaymentRetainedRow(origin, row)
    sources.forEach { requireFoodPaymentRetainedRow(origin, it) }
    check(LocalFoodPaymentProducer.requireStart(origin).slot == slot)
    requireFoodPaymentOriginalCustody(view, origin, stock)
    foodReadView(view).requireFoodPredecessors(origin)
    check(view.claim(LocalSalesStoreProfile.unique("payment_attempt", slot.target.attemptId)) == slot.target.collectionId)
    for (claim in foodPaymentLineageClaims(requirement)) check(view.claim(claim.key) == claim.ownerId)
    for (expected in foodPaymentFences(requirement)) {
        val fence = LocalSalesStorage.required(view, expected.key, expected.schema)
        check(fence.revision == Revision(1) && fence.payload == expected.payload)
        requireFoodPaymentRetainedRow(origin, fence)
    }
    return LocalSalesRow(row, slot)
}

/** Raw original and initial OR exactly-once advanced accounting; never rematerializes pending. */
internal suspend fun readFoodPaymentRaw(view: CommandReadView, operationId: OperationId,
    stock: com.ukkera.pos.inventory.store.LocalStockRepository): FoodPaymentRawCut {
    val row = LocalSalesStorage.required(view, foodPaymentRawKey(operationId), LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.ORIGINAL))
    val raw = localSalesDecode(row.payload, LocalFoodPaymentRaw.serializer(), LocalFoodPaymentProfile.MAX_RAW_BYTES)
    check(raw.version == 1 && row.revision == Revision(1) && raw.scope == view.scope && raw.target.recordingOperationId == operationId &&
        raw.recordingActorId == raw.originalActorId && foodPaymentRawWrite(raw).payload == row.payload &&
        raw.bodyDigest == sha256(raw.body().copyBytes()) && raw.semanticDigest == semanticDigest(view.scope, raw.recordingActorId,
            LocalFoodPaymentProfile.COMMAND, raw.body()))
    val slot = readFoodPaymentSlot(view, operationId, stock)
    check(raw.target == slot.value.target && raw.originalActorId == slot.value.originalActorId && raw.slotDigest == slot.stored.digest)
    val start = view.retainedCommand(slot.value.target.intentOperationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val origin = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireFoodPaymentRetainedRow(origin, row)
    check(LocalFoodPaymentProducer.requireRaw(origin, start).raw == raw)
    val appRow = LocalSalesStorage.required(view, foodPaymentApplicationKey(operationId), LocalFoodPaymentProfile.schema(LocalFoodPaymentProfile.APPLICATION))
    val app = localSalesDecode(appRow.payload, LocalFoodPaymentApplication.serializer(), LocalFoodPaymentProfile.MAX_APPLICATION_BYTES)
    check(app.version == 1 && app.phase == LocalFoodPaymentFactPhase.ORIGINAL && app.scope == raw.scope && app.target == raw.target && app.originalActorId == raw.originalActorId &&
        app.revision.next() == appRow.revision && app.financialRevisions.map { it.kind to it.id }.distinct().size == app.financialRevisions.size)
    when (app.state) {
        LocalFoodPaymentApplicationState.PENDING_RECONCILIATION -> {
            check(app == foodPaymentInitialApplication(raw) && appRow.revision == Revision(1))
            requireFoodPaymentRetainedRow(origin, appRow)
        }
        LocalFoodPaymentApplicationState.APPLIED -> {
            check(app.revision == Revision(1) && appRow.revision == Revision(2) && app.reason == null && app.action != null && app.lastApplicationOperationId != null)
            val applied = view.retainedCommand(checkNotNull(app.lastApplicationOperationId)) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            requireFoodPaymentRetainedRow(applied, appRow)
            check(LocalFoodPaymentProducer.requireApply(applied, start, origin).application == app)
            foodReadView(view).requireFoodPredecessors(applied)
        }
    }
    return FoodPaymentRawCut(slot, LocalSalesRow(row, raw), LocalSalesRow(appRow, app), start, origin)
}
