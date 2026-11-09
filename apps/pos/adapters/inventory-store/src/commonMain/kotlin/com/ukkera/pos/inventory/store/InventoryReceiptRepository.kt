package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.InventoryEffect
import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotBinding
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.InventorySnapshotKind
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.inventory.ValueDestination
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/**
 * No business authorization, runtime verifier or command handler is supplied here.
 * A closed fixture factory must own the entire finite handler list and freeze its
 * declarations. Generic kernel namespaces are not exclusive producer authority.
 * Point claims cannot enumerate hidden extra claims, and a coherent historical pair
 * plus claims can pass after rollback under synthetic accepting runtime adapters.
 */
public object InventoryReceiptRepository {
    public suspend fun read(view: CommandReadView): InventoryReceiptReadCut {
        val scope = view.scope
        val cut = view.aggregate(InventoryReceiptStoreProfile.cutKey(scope.branchId))
        val companion = view.aggregate(InventoryReceiptStoreProfile.companionKey(scope.branchId))
        if (cut == null || companion == null) {
            throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.CURRENT_PAIR_REQUIRED)
        }
        val current = decodeReceiptPair(scope, ReceiptStoredRow.from(cut), ReceiptStoredRow.from(companion))
        // Exactly one positive retained lookup. Never walk all history or demand the global head.
        val retained = view.retainedCommand(current.companion.producer.operationId)
            ?: throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
        validateReceiptProducer(current, retained)
        for (source in current.companion.sources) {
            receiptCheck(view.claim(InventoryReceiptStoreProfile.sourceClaim(source.receiptId)) == DomainId(source.producingOperationId.value),
                InventoryReceiptStoreFailure.CLAIM_MISMATCH)
            receiptCheck(view.claim(InventoryReceiptStoreProfile.originClaim(source.origin)) == source.receiptId,
                InventoryReceiptStoreFailure.CLAIM_MISMATCH)
        }
        return current
    }

    /**
     * PURE explicit synthetic fixture genesis proposal. Never called by [read],
     * never a missing-state fallback, and not permission to initialize any live file.
     * The test-only caller must establish a new file and exclusive closed composition.
     */
    public fun fixtureGenesis(context: CommandContext): InventoryReceiptWriteSet =
        fixtureReceiptGenesis(ReceiptContext.from(context))

    /**
     * Pure translation for a plan computed from this exact transaction's loaded cut.
     * The caller still owes current authorization, complete dependency/recovery mapping
     * and its constant STOCK denial-only barrier. A source reference is not procurement proof.
     */
    public fun translateReceive(
        current: InventoryReceiptReadCut,
        context: CommandContext,
        request: InventoryFixtureReceiveRequest,
        plan: InventoryPlan,
    ): InventoryReceiptWriteSet = translateReceipt(current, ReceiptContext.from(context), request, plan)
}

internal fun fixtureReceiptGenesis(context: ReceiptContext): InventoryReceiptWriteSet = receiptGuard {
    validateReceiptContext(context, InventoryReceiptStoreProfile.GENESIS_COMMAND, InventoryReceiptCommandCodec.genesisRequest())
    val snapshot = InventorySnapshot.empty(InventoryScope(context.scope.companyId, context.scope.branchId))
    val cut = encodeReceiptCut(snapshot)
    val companion = InventoryReceiptCompanion(context.scope, context.scope.branchId, Revision(0), sha256(cut.copyBytes()),
        context.producer(), null, emptyList(), emptyList())
    makeReceiptWrites(snapshot, companion, cut, null, emptyList())
}

internal fun translateReceipt(
    current: InventoryReceiptReadCut,
    context: ReceiptContext,
    request: InventoryFixtureReceiveRequest,
    plan: InventoryPlan,
): InventoryReceiptWriteSet = receiptGuard {
    validateReceiptContext(context, InventoryReceiptStoreProfile.RECEIVE_COMMAND, InventoryReceiptCommandCodec.encode(request))
    val before = current.snapshot
    receiptCheck(current.scope == context.scope && before.scope == InventoryScope(context.scope.companyId, context.scope.branchId),
        InventoryReceiptStoreFailure.BINDING_MISMATCH)
    // requireCurrent alone checks only scope/revision/op/time, not the exact source history.
    receiptCheck(plan.sourceSnapshot === before && plan.operationId == context.operationId &&
        request.expectedRevision == before.revision, InventoryReceiptStoreFailure.BINDING_MISMATCH)
    InventoryPlanner.requireCurrent(plan, before)
    validateReceiptCut(before, current.companion)
    receiptCheck(current.storageGeneration == InventoryReceiptStoreProfile.toStorageRevision(before.revision) &&
        current.companionRow.generation == current.storageGeneration, InventoryReceiptStoreFailure.BINDING_MISMATCH)
    receiptCheck(current.companion.cutDigest == current.cutRow.digest &&
        InventoryReceiptCompanionCodec.encode(current.companion) == current.companionRow.payload,
        InventoryReceiptStoreFailure.BINDING_MISMATCH)
    receiptCheck(current.companion.sources.size < InventoryReceiptStoreProfile.MAX_RECEIPTS, InventoryReceiptStoreFailure.TOO_LARGE)
    receiptCheck(current.companion.sources.none { it.receiptId == request.receiptId || it.origin == request.origin },
        InventoryReceiptStoreFailure.SOURCE_CONFLICT)
    receiptCheck(plan.effects.size == 2 && plan.mustCommitBeforeEpochMillis == null, InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
    val valuation = plan.effects[0] as? InventoryEffect.Valuation
        ?: throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
    val movement = plan.effects[1] as? InventoryEffect.Movement
        ?: throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
    val receipt = request.toReceipt(context.scope)
    val value = valuation.plan
    val fact = value.fact as? ValuationFact.Receipt
        ?: throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
    receiptCheck(valuation.pool == receipt.position.pool && fact.factId == request.receiptId &&
        fact.origin == ReceiptValueOrigin.PURCHASE && fact.quantity == request.quantity && fact.originalValue == request.originalValue &&
        value.quantityDelta == request.quantity && value.originalValueDelta == request.originalValue &&
        value.carryingValueDelta == request.originalValue && value.linkedAdjustment == null,
        InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
    val sourcePool = before.pool(valuation.pool)?.value
    if (sourcePool != null) {
        receiptCheck(value.sourceBasis === sourcePool && value.requiredRevision == sourcePool.revision,
            InventoryReceiptStoreFailure.BINDING_MISMATCH)
    } else {
        val basis = value.sourceBasis
        receiptCheck(basis.revision == Revision(0) && basis.onHand.scaled == 0L && basis.carryingValue.minor == 0L &&
            basis.facts.isEmpty() && basis.eventIds.isEmpty() && basis.costTargets.isEmpty() && basis.adjustments.isEmpty() &&
            basis.linkedCorrectionBalances.isEmpty() && basis.unitId == request.quantity.unitId && basis.scale == request.quantity.scale &&
            basis.currency == request.originalValue.currency && value.requiredRevision == Revision(0))
    }
    receiptCheck(movement.sourceId == request.receiptId && movement.position == receipt.position &&
        movement.quantityDelta == request.quantity && movement.reason == StockMovementReason.RECEIPT && movement.sourceAllocationId == null)
    val after = plan.nextSnapshot
    receiptCheck(after.scope == before.scope && after.revision == before.revision.next() &&
        after.operationIds == before.operationIds + context.operationId && after.sourceIds == before.sourceIds + request.receiptId &&
        after.pool(valuation.pool)?.value === value.next && value.next.facts.lastOrNull() === fact,
        InventoryReceiptStoreFailure.BINDING_MISMATCH)
    val source = InventoryReceiptSourceBinding(request.receiptId, context.operationId, request.origin, receipt.position,
        request.quantity, request.originalValue, request.expiresAtEpochMillis)
    val posted = listOf(
        InventoryReceiptPostedEffect.Valuation(valuation.pool, value.requiredRevision, value.next.revision, fact.factId,
            fact.quantity, fact.originalValue, fact.origin, value.quantityDelta, value.originalValueDelta, value.carryingValueDelta),
        InventoryReceiptPostedEffect.Movement(movement.sourceId, movement.position, movement.quantityDelta, movement.reason, movement.sourceAllocationId),
    )
    val cut = encodeReceiptCut(after)
    val companion = InventoryReceiptCompanion(context.scope, context.scope.branchId, after.revision, sha256(cut.copyBytes()),
        context.producer(), InventoryReceiptPreviousPair(before.revision, current.companion.producer.operationId,
            current.cutRow.digest, current.companionRow.digest), current.companion.sources + source, posted)
    validateReceiptCut(after, companion)
    makeReceiptWrites(after, companion, cut, current.storageGeneration, receiptClaims(source))
}

private fun validateReceiptContext(context: ReceiptContext, command: com.ukkera.pos.database.CommandKey, normalized: StoredBytes) {
    receiptCheck(context.command == command &&
        semanticDigest(context.scope, context.originalActorId, command, normalized) == context.semanticDigest,
        InventoryReceiptStoreFailure.BINDING_MISMATCH)
}

internal fun decodeReceiptPair(scope: BranchScope, cut: ReceiptStoredRow, companion: ReceiptStoredRow): InventoryReceiptReadCut = receiptGuard {
    receiptCheck(cut.key == InventoryReceiptStoreProfile.cutKey(scope.branchId) &&
        companion.key == InventoryReceiptStoreProfile.companionKey(scope.branchId) && cut.schema == InventoryReceiptStoreProfile.CUT_SCHEMA &&
        companion.schema == InventoryReceiptStoreProfile.COMPANION_SCHEMA && cut.generation == companion.generation,
        InventoryReceiptStoreFailure.BINDING_MISMATCH)
    receiptCheck(cut.payload.size <= InventoryReceiptStoreProfile.MAX_ROW_BYTES && companion.payload.size <= InventoryReceiptStoreProfile.MAX_ROW_BYTES,
        InventoryReceiptStoreFailure.TOO_LARGE)
    receiptCheck(sha256(cut.payload.copyBytes()) == cut.digest && sha256(companion.payload.copyBytes()) == companion.digest,
        InventoryReceiptStoreFailure.BINDING_MISMATCH)
    val revision = InventoryReceiptStoreProfile.toDomainRevision(cut.generation)
    val snapshot = InventorySnapshotCodec.decode(cut.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        InventorySnapshotBinding(InventorySnapshotKind.BRANCH_INVENTORY, InventoryScope(scope.companyId, scope.branchId), scope.branchId, revision))
    val decoded = InventoryReceiptCompanionCodec.decode(companion.payload, InventoryReceiptCompanionBinding(scope, scope.branchId, revision))
    receiptCheck(decoded.cutDigest == cut.digest, InventoryReceiptStoreFailure.BINDING_MISMATCH)
    validateReceiptCut(snapshot, decoded)
    return@receiptGuard InventoryReceiptReadCut(scope, snapshot, decoded, cut, companion)
}

/** Receipt-only cross-row correspondence, not another moving-average engine or a history fold. */
internal fun validateReceiptCut(snapshot: InventorySnapshot, companion: InventoryReceiptCompanion) {
    validateCompanionShape(companion)
    receiptCheck(snapshot.reservations.isEmpty() && snapshot.issues.isEmpty() &&
        snapshot.positions.all { it.safety == StockSafety.CLEAR && it.key.custody == StockCustody.RELEASED && it.key.pool.ownership == StockOwnership.CompanyOwned } &&
        snapshot.pools.all { pool -> pool.value.costTargets.isEmpty() && pool.value.adjustments.isEmpty() &&
            pool.value.facts.all { it is ValuationFact.Receipt && it.origin == ReceiptValueOrigin.PURCHASE } },
        InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
    val sources = companion.sources
    receiptCheck(snapshot.scope == InventoryScope(companion.scope.companyId, companion.scope.branchId) && snapshot.revision == companion.inventoryRevision &&
        snapshot.sourceIds == sources.map { it.receiptId } && snapshot.operationIds == sources.map { it.producingOperationId })
    // Membership alone would permit an older producer to masquerade as this current cut.
    if (snapshot.operationIds.isNotEmpty()) receiptCheck(snapshot.operationIds.last() == companion.producer.operationId)
    val byPool = sources.groupBy { it.position.pool }
    receiptCheck(snapshot.pools.map { it.key }.toSet() == byPool.keys)
    for (pool in snapshot.pools) {
        val bindings = byPool.getValue(pool.key)
        val facts = pool.value.facts
        receiptCheck(facts.size == bindings.size)
        for ((fact, source) in facts.zip(bindings)) {
            fact as ValuationFact.Receipt // All variants were checked above, without filtering any away.
            receiptCheck(fact.factId == source.receiptId && fact.quantity == source.quantity && fact.originalValue == source.originalValue)
        }
        receiptCheck(pool.value.linkedCorrectionBalances.size == 1 &&
            pool.value.linkedCorrectionBalances.single().destination == ValueDestination.Held &&
            pool.value.linkedCorrectionBalances.single().value.minor == 0L, InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
    }
    // Generic inventory validation has pool totals, but only this companion knows receipt lots.
    val byPosition = sources.groupBy { it.position }
    receiptCheck(snapshot.positions.map { it.key }.toSet() == byPosition.keys)
    for (position in snapshot.positions) {
        val bindings = byPosition.getValue(position.key)
        val total = bindings.fold(Quantity(position.onHand.unitId, 0, position.onHand.scale)) { sum, source -> sum + source.quantity }
        receiptCheck(total == position.onHand && bindings.all { it.expiresAtEpochMillis == position.expiresAtEpochMillis })
    }
}

private fun validateReceiptProducer(current: InventoryReceiptReadCut, retained: RetainedCommandEvidence) {
    val companion = current.companion
    val producer = companion.producer
    receiptCheck(retained.committed && retained.scope == current.scope && retained.operationId == producer.operationId &&
        retained.command == producer.command && retained.originalActorId == producer.originalActorId && retained.semanticDigest == producer.semanticDigest &&
        retained.jobs.isEmpty(), InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
    val genesis = current.snapshot.revision == Revision(0)
    receiptCheck(producer.command == if (genesis) InventoryReceiptStoreProfile.GENESIS_COMMAND else InventoryReceiptStoreProfile.RECEIVE_COMMAND,
        InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
    val normalized = if (genesis) InventoryReceiptCommandCodec.genesisRequest() else {
        val source = companion.sources.last()
        InventoryReceiptCommandCodec.encode(InventoryFixtureReceiveRequest(checkNotNull(companion.previous).inventoryRevision, source.receiptId,
            source.position.pool.skuId, source.position.pool.warehouseId, source.position.lotId, source.quantity, source.originalValue,
            source.expiresAtEpochMillis, source.origin))
    }
    receiptCheck(retained.normalized == normalized, InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
    val result = InventoryReceiptResultCodec.decode(retained.result)
    receiptCheck(result == receiptResult(current.snapshot, companion, current.companionRow.digest), InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
    receiptCheck(retained.aggregateWrites.size == 2, InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
    for (row in listOf(current.cutRow, current.companionRow)) {
        val matching = retained.aggregateWrites.filter { it.key == row.key }
        receiptCheck(matching.size == 1, InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
        val write = matching.single()
        receiptCheck(write.storageGeneration == row.generation && write.schema == row.schema && write.payload == row.payload &&
            write.payloadDigest == row.digest, InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
    }
    val claims = if (genesis) emptyList() else receiptClaims(companion.sources.last())
    val expected = claims.map { Triple(RetainedClaimAction.ACQUIRE, it.key, it.ownerId) }.toSet()
    receiptCheck(retained.claimChanges.size == expected.size &&
        retained.claimChanges.map { Triple(it.action, it.key, it.ownerId) }.toSet() == expected,
        InventoryReceiptStoreFailure.RETAINED_PRODUCER_REQUIRED)
}

private fun receiptClaims(source: InventoryReceiptSourceBinding): List<ClaimChange.Acquire> = listOf(
    ClaimChange.Acquire(InventoryReceiptStoreProfile.sourceClaim(source.receiptId), DomainId(source.producingOperationId.value)),
    ClaimChange.Acquire(InventoryReceiptStoreProfile.originClaim(source.origin), source.receiptId),
)

internal fun encodeReceiptCut(snapshot: InventorySnapshot): StoredBytes = receiptGuard {
    val encoded = InventorySnapshotCodec.encode(snapshot)
    receiptCheck(encoded.length <= InventoryReceiptStoreProfile.MAX_ROW_BYTES, InventoryReceiptStoreFailure.TOO_LARGE)
    val bytes = encoded.encodeToByteArray(throwOnInvalidSequence = true)
    receiptCheck(bytes.size <= InventoryReceiptStoreProfile.MAX_ROW_BYTES, InventoryReceiptStoreFailure.TOO_LARGE)
    StoredBytes(bytes)
}

private fun receiptResult(snapshot: InventorySnapshot, companion: InventoryReceiptCompanion,
    companionDigest: com.ukkera.pos.security.PayloadDigest): InventoryReceiptResult = InventoryReceiptResult(
    if (snapshot.revision == Revision(0)) InventoryReceiptResultKind.GENESIS else InventoryReceiptResultKind.RECEIVED,
    companion.scope, companion.producer.operationId, snapshot.revision, companion.sources.lastOrNull()?.receiptId, companion.cutDigest, companionDigest,
)

private fun makeReceiptWrites(snapshot: InventorySnapshot, companion: InventoryReceiptCompanion, cut: StoredBytes,
    expectedGeneration: Revision?, claims: List<ClaimChange>): InventoryReceiptWriteSet {
    val nextGeneration = (expectedGeneration ?: Revision(0)).next()
    receiptCheck(nextGeneration == InventoryReceiptStoreProfile.toStorageRevision(snapshot.revision), InventoryReceiptStoreFailure.BINDING_MISMATCH)
    val companionBytes = InventoryReceiptCompanionCodec.encode(companion)
    val result = InventoryReceiptResultCodec.encode(receiptResult(snapshot, companion, sha256(companionBytes.copyBytes())))
    // 32 is an additional count ceiling, NOT a promise every 32-receipt whole cut fits.
    receiptCheck(cut.size + companionBytes.size + result.size <= InventoryReceiptStoreProfile.MAX_PROPOSAL_BYTES,
        InventoryReceiptStoreFailure.TOO_LARGE)
    return InventoryReceiptWriteSet(result, snapshot, companion, listOf(
        AggregateChange(InventoryReceiptStoreProfile.cutKey(companion.id), expectedGeneration, InventoryReceiptStoreProfile.CUT_SCHEMA, cut),
        AggregateChange(InventoryReceiptStoreProfile.companionKey(companion.id), expectedGeneration, InventoryReceiptStoreProfile.COMPANION_SCHEMA, companionBytes),
    ), claims)
}
