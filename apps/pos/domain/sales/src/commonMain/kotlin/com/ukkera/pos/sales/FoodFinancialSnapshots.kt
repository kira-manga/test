package com.ukkera.pos.sales

import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** Separate persisted profile. Decoding never recreates a stock plan, payment authority or commit proof. */
public object FoodFinancialSnapshots {
    public const val MAX_BYTES: Int = 65_536
    public fun encodeCollection(value: FoodCollection): String = checkedSnapshot {
        validateFoodCollection(value)
        encodeFoodSnapshot("COLLECTION", value.scope, value.id, value.revision, StoredFoodCollection.from(value), StoredFoodCollection.serializer())
    }
    public fun decodeCollection(text: String, scope: BranchScope, id: DomainId, revision: Revision): FoodCollection = checkedSnapshot {
        decodeFoodSnapshot(text, "COLLECTION", scope, id, revision, StoredFoodCollection.serializer()).restore(scope, id, revision)
    }
    public fun encodeSale(value: FoodPaidSale): String = checkedSnapshot {
        validateFoodSale(value)
        encodeFoodSnapshot("PAID_FOOD", value.scope, value.id, Revision(0), StoredFoodSale.from(value), StoredFoodSale.serializer())
    }
    public fun decodeSale(text: String, scope: BranchScope, id: DomainId): FoodPaidSale = checkedSnapshot {
        decodeFoodSnapshot(text, "PAID_FOOD", scope, id, Revision(0), StoredFoodSale.serializer()).restore(scope, id)
    }
    public fun encodeEntitlement(value: FoodReceiptEntitlement): String = checkedSnapshot {
        validateFoodEntitlement(value)
        val stored = StoredFoodEntitlement(StoredFoodSale.from(value.sale), value.cursors.map {
            StoredConsumedQuantity(it.portionId, it.consumedQuantity)
        }, value.remainingTenders.map(StoredComponentTenderRow::from))
        encodeFoodSnapshot("FOOD_ENTITLEMENT", value.sale.scope, value.sale.id, value.revision, stored, StoredFoodEntitlement.serializer())
    }
    public fun decodeEntitlement(text: String, scope: BranchScope, id: DomainId, revision: Revision): FoodReceiptEntitlement = checkedSnapshot {
        val stored = decodeFoodSnapshot(text, "FOOD_ENTITLEMENT", scope, id, revision, StoredFoodEntitlement.serializer())
        val sale = stored.sale.restore(scope, id)
        snapshotCheck(stored.cursors.size in 1..32 && stored.remainingTenders.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        FoodReceiptEntitlement(sale, revision, stored.cursors.map { cursor ->
            restoreOriginalCursor(sale.quote.batchFor(cursor.portionId).quote, cursor.portionId, cursor.consumedQuantity)
        }, stored.remainingTenders.map { it.restore() }).also(::validateFoodEntitlement)
    }
    public fun encodeRefund(value: FoodRefundObligation): String = checkedSnapshot {
        validateFoodRefund(value)
        encodeFoodSnapshot("UNSENT_FOOD_REFUND", value.scope, value.id, value.revision, StoredFoodRefund.from(value), StoredFoodRefund.serializer())
    }
    public fun decodeRefund(text: String, scope: BranchScope, id: DomainId, revision: Revision): FoodRefundObligation = checkedSnapshot {
        val stored = decodeFoodSnapshot(text, "UNSENT_FOOD_REFUND", scope, id, revision, StoredFoodRefund.serializer())
        snapshotCheck(stored.attempts.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        FoodRefundObligation(id, stored.collection.restore(scope, stored.collectionId, stored.collectionRevision),
            stored.cancellationOperationId, stored.reasonId, stored.actorId, stored.acceptedAt, revision,
            stored.attempts.map { it.restore() }).also(::validateFoodRefund)
    }
    /** Canonical identity comparison, not proof that either side is an authentic retained source. */
    public fun sameQuote(first: FoodFinancialQuote, second: FoodFinancialQuote): Boolean = sameFoodQuote(first, second)

    /** Literal identity/economics comparison only; revisions and outcome markers are checked by the caller. */
    public fun sameCollectionSource(first: FoodCollection, second: FoodCollection): Boolean = first.id == second.id &&
        first.scope == second.scope && StoredFoodCollection.from(first).copy(saleId = null, cancellationRefundId = null) ==
            StoredFoodCollection.from(second).copy(saleId = null, cancellationRefundId = null)

    /**
     * Historical paid/refund basis compared to its current collection. Only a responsibility
     * suffix may differ, never an attempt, original actor/session, quoted price or stock link.
     * This is a literal comparison; retained-original authentication remains the adapter's job.
     */
    public fun isHistoricalCollectionSource(original: FoodCollection, current: FoodCollection): Boolean =
        original.id == current.id && original.scope == current.scope && original.revision.value <= current.revision.value &&
            current.responsibility.take(original.responsibility.size) == original.responsibility &&
            current.responsibility.drop(original.responsibility.size).all { it.priorCollectionRevision.value >= original.revision.value } &&
            StoredFoodCollection.from(original).copy(saleId = null, cancellationRefundId = null, responsibility = emptyList()) ==
                StoredFoodCollection.from(current).copy(saleId = null, cancellationRefundId = null, responsibility = emptyList())

    /** Literal successor check for retained producers; does not replay authorization or a transition. */
    public fun isResponsibilitySuccessor(original: FoodCollection, current: FoodCollection,
        acceptance: FoodCollectionResponsibilityV1): Boolean = original.id == current.id && original.scope == current.scope &&
        original.revision.next() == current.revision && !original.unresolved &&
        acceptance.priorCollectionRevision == original.revision && acceptance.priorAttemptCount == original.attempts.size &&
        acceptance.fromActorId == original.currentOwnerId && acceptance.recipientId != original.currentOwnerId &&
        StoredFoodCollection.from(current) == StoredFoodCollection.from(original).copy(
            responsibility = original.responsibility + acceptance)
}

@Serializable private data class StoredFoodBatch(val id: DomainId, val digest: PayloadDigest, val quote: String)
@OptIn(ExperimentalSerializationApi::class)
@Serializable private data class StoredFoodCollection(
    val orderId: DomainId, val lockedCommercialRevision: Revision, val originalCreatorId: DomainId,
    val collectorId: DomainId, val cashSessionId: DomainId, val batches: List<StoredFoodBatch>,
    val portions: List<FoodFinancialPortion>, val attempts: List<StoredPaymentAttempt>,
    val saleId: DomainId?, val cancellationRefundId: DomainId?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val responsibility: List<FoodCollectionResponsibilityV1> = emptyList(),
) {
    fun restore(scope: BranchScope, id: DomainId, revision: Revision): FoodCollection {
        snapshotCheck(batches.size in 1..32 && portions.size in 1..32 && attempts.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        val quote = FoodFinancialQuote(batches.map { FoodPriceBatch(it.id, it.digest, StoredPricingSnapshotCodec.decode(it.quote)) })
        return FoodCollection(id, scope, orderId, lockedCommercialRevision, originalCreatorId, collectorId, cashSessionId,
            quote, portions, revision, attempts.map { it.restore() }, saleId, cancellationRefundId, responsibility).also(::validateFoodCollection)
    }
    companion object {
        fun from(value: FoodCollection): StoredFoodCollection = StoredFoodCollection(value.orderId, value.lockedCommercialRevision,
            value.originalCreatorId, value.collectorId, value.cashSessionId, value.quote.batches.map {
                StoredFoodBatch(it.id, it.sourceDigest, StoredPricingSnapshotCodec.encode(it.quote))
            }, value.portions, value.attempts.map(StoredPaymentAttempt::from), value.saleId, value.cancellationRefundId, value.responsibility)
    }
}
@Serializable private data class StoredFoodSale(
    val collectionId: DomainId, val collectionRevision: Revision, val collection: StoredFoodCollection,
    val finalizationOperationId: OperationId, val finalizedAt: UtcInstant, val tenders: List<StoredComponentTenderRow>,
) {
    fun restore(scope: BranchScope, id: DomainId): FoodPaidSale {
        snapshotCheck(tenders.size <= 64, SalesSnapshotFailure.TOO_LARGE)
        return FoodPaidSale(id, collection.restore(scope, collectionId, collectionRevision), finalizationOperationId, finalizedAt,
            tenders.map { it.restore() }).also(::validateFoodSale)
    }
    companion object {
        fun from(value: FoodPaidSale): StoredFoodSale = StoredFoodSale(value.collection.id, value.collection.revision,
            StoredFoodCollection.from(value.collection), value.finalizationOperationId, value.finalizedAt,
            value.originalTenderAllocations.map(StoredComponentTenderRow::from))
    }
}
@Serializable private data class StoredFoodEntitlement(val sale: StoredFoodSale, val cursors: List<StoredConsumedQuantity>,
    val remainingTenders: List<StoredComponentTenderRow>)
@Serializable private data class StoredFoodRefund(
    val collectionId: DomainId, val collectionRevision: Revision, val collection: StoredFoodCollection,
    val cancellationOperationId: OperationId, val reasonId: DomainId, val actorId: DomainId, val acceptedAt: UtcInstant,
    val attempts: List<StoredRefundAttempt>,
) {
    companion object {
        fun from(value: FoodRefundObligation): StoredFoodRefund = StoredFoodRefund(value.originalCollection.id,
            value.originalCollection.revision, StoredFoodCollection.from(value.originalCollection), value.cancellationOperationId,
            value.cancellationReasonId, value.acceptingActorId, value.acceptedAt, value.attempts.map(StoredRefundAttempt::from))
    }
}
@Serializable private data class FoodSnapshotEnvelope<T>(val profile: String, val kind: String, val scope: StoredSalesScope,
    val id: DomainId, val revision: Revision, val state: T)

private fun <T> encodeFoodSnapshot(kind: String, scope: BranchScope, id: DomainId, revision: Revision, value: T, serializer: KSerializer<T>): String =
    salesSnapshotJson.encodeToString(FoodSnapshotEnvelope.serializer(serializer),
        FoodSnapshotEnvelope("GENUINE_LOCAL_FOOD_FINANCIAL_V1", kind, StoredSalesScope.from(scope), id, revision, value)).also { foodSnapshotBound(it) }

private fun <T> decodeFoodSnapshot(text: String, kind: String, scope: BranchScope, id: DomainId, revision: Revision, serializer: KSerializer<T>): T {
    foodSnapshotBound(text)
    val codec = FoodSnapshotEnvelope.serializer(serializer)
    val value = salesSnapshotJson.decodeFromString(codec, text)
    snapshotCheck(value.profile == "GENUINE_LOCAL_FOOD_FINANCIAL_V1" && value.kind == kind && value.scope.restore() == scope &&
        value.id == id && value.revision == revision, SalesSnapshotFailure.BINDING_MISMATCH)
    snapshotCheck(salesSnapshotJson.encodeToString(codec, value) == text, SalesSnapshotFailure.NON_CANONICAL)
    return value.state
}

internal fun foodSnapshotBound(text: String, maximum: Int = FoodFinancialSnapshots.MAX_BYTES) {
    snapshotCheck(text.length <= maximum &&
        text.encodeToByteArray(throwOnInvalidSequence = true).size <= maximum, SalesSnapshotFailure.TOO_LARGE)
    // Before parsing: quotes inside original price strings are data, not nested object graphs.
    val stack = mutableListOf<Pair<Char, Int>>()
    var quoted = false; var escaped = false
    for (c in text) {
        if (quoted) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else when (c) {
            '"' -> quoted = true
            '{', '[' -> { snapshotCheck(stack.size < 32, SalesSnapshotFailure.TOO_LARGE); stack += c to 0 }
            '}', ']' -> {
                snapshotCheck(stack.isNotEmpty() && stack.last().first == if (c == ']') '[' else '{', SalesSnapshotFailure.MALFORMED)
                stack.removeAt(stack.lastIndex)
            }
            ',' -> if (stack.lastOrNull()?.first == '[') {
                val count = stack.last().second + 1
                snapshotCheck(count < 128, SalesSnapshotFailure.TOO_LARGE); stack[stack.lastIndex] = '[' to count
            }
        }
    }
    snapshotCheck(stack.isEmpty() && !quoted, SalesSnapshotFailure.MALFORMED)
}

internal fun validateFoodSale(value: FoodPaidSale) {
    validateFoodCollection(value.collection)
    snapshotCheck(value.collection.saleId == null && value.collection.cancellationRefundId == null &&
        !value.collection.unresolved && value.collection.collected == value.quote.payable)
    validateTenderRows(value.originalTenderAllocations, value.quote.componentAmounts(), value.payments)
    snapshotCheck(value.originalTenderAllocations.map(StoredComponentTenderRow::from) ==
        allocateFoodTenders(value.collection).map(StoredComponentTenderRow::from))
}

internal fun validateFoodEntitlement(value: FoodReceiptEntitlement) {
    validateFoodSale(value.sale)
    snapshotCheck(value.cursors.map { it.portionId } == value.sale.quote.portionIds)
    val remaining = value.sale.quote.componentAmounts().toMutableMap()
    for (cursor in value.cursors) {
        val original = value.sale.quote.originalCursor(cursor.portionId)
        val reconstructed = restoreOriginalCursor(value.sale.quote.batchFor(cursor.portionId).quote, cursor.portionId, cursor.consumedQuantity)
        snapshotCheck(cursor.originalSnapshotId == original.originalSnapshotId && cursor.originalQuantity == original.originalQuantity &&
            cursor.originalComponents == original.originalComponents && cursor.remainingComponents == reconstructed.remainingComponents)
        cursor.remainingComponents.forEach { remaining[foodComponentKey(original.originalSnapshotId, it.key)] = it.amount }
    }
    snapshotCheck((value.revision == Revision(0)) == value.cursors.all { it.consumedQuantity.scaled == 0L })
    validateTenderRows(value.remainingTenders, remaining, value.sale.payments, value.sale.originalTenderAllocations)
}

internal fun validateFoodRefund(value: FoodRefundObligation) {
    val collection = value.originalCollection
    validateFoodCollection(collection)
    snapshotCheck(collection.cancellationRefundId == null && !collection.unresolved && value.acceptingActorId == collection.currentOwnerId &&
        collection.portions.all { it.stockOrigin == FoodStockOrigin.RESERVED_FOR_COLLECTION } &&
        value.attempts.map { it.id }.distinct().size == value.attempts.size && value.attempts.count { it.status.isUnresolved() } <= 1)
    for (attempt in value.attempts) {
        validateStoredRefundAttempt(attempt)
        val original = value.obligations.single { it.originalPaymentId == attempt.originalPaymentId }
        snapshotCheck(original.tender == attempt.tender && (attempt.refund == null || attempt.refund.returnId == value.id))
        sameCurrency(value.amount, attempt.amount)
    }
    value.obligations.forEach { obligation ->
        val paid = value.attempts.filter { it.originalPaymentId == obligation.originalPaymentId }
            .fold(value.amount.zero()) { sum, attempt -> sum + (attempt.refund?.amount ?: value.amount.zero()) }
        snapshotCheck(paid.minor <= obligation.amount.minor)
    }
    snapshotCheck(value.due.minor >= 0L)
}
