package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.inventory.StockReceipt
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision

/**
 * Storage plumbing for a closed, synthetic genesis/one-owned-receipt producer family.
 * No production handler, procurement authority, protected genesis or current-frontier proof.
 */
public object InventoryReceiptStoreProfile {
    public val GENESIS_COMMAND: CommandKey = CommandKey("fixture.inventory.genesis", 1)
    public val RECEIVE_COMMAND: CommandKey = CommandKey("fixture.inventory.receive", 1)
    public const val CUT_KIND: String = "inventory.cut"
    public const val CUT_SCHEMA: String = "inventory.cut/v1"
    public const val COMPANION_KIND: String = "inventory.companion"
    public const val COMPANION_SCHEMA: String = "inventory.companion/receipt-v1"
    public const val SOURCE_NAMESPACE: String = "inventory.source"
    public const val ORIGIN_NAMESPACE: String = "inventory.receipt.origin"
    public const val MAX_RECEIPTS: Int = 32
    public const val MAX_ROW_BYTES: Int = 65_536
    public const val MAX_PROPOSAL_BYTES: Int = 1_048_576
    public const val MAX_REQUEST_BYTES: Int = 4_096
    public const val MAX_RESULT_BYTES: Int = 2_048

    public fun cutKey(branchId: DomainId): AggregateKey = AggregateKey(CUT_KIND, branchId)
    public fun companionKey(branchId: DomainId): AggregateKey = AggregateKey(COMPANION_KIND, branchId)
    public fun sourceClaim(receiptId: DomainId): ClaimKey = ClaimKey(SOURCE_NAMESPACE, receiptId.value)
    public fun originClaim(origin: FixtureReceiptOrigin): ClaimKey =
        ClaimKey(ORIGIN_NAMESPACE, "fixture-v1/${origin.documentId.value}/${origin.allocationId.value}")

    /** Only this whole-cut profile advances its domain revision and both row generations together. */
    public fun toStorageRevision(domain: Revision): Revision = domain.next()
    public fun toDomainRevision(storage: Revision): Revision {
        receiptCheck(storage.value > 0, InventoryReceiptStoreFailure.BINDING_MISMATCH)
        return Revision(storage.value - 1)
    }
}

/** A synthetic allocation identity for fixtures, NEVER evidence that goods/invoices exist. */
public data class FixtureReceiptOrigin(public val documentId: DomainId, public val allocationId: DomainId)

/** Scope, original actor, operation and semantic digest come from the kernel, not this body. */
public data class InventoryFixtureReceiveRequest(
    public val expectedRevision: Revision,
    public val receiptId: DomainId,
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val lotId: DomainId?,
    public val quantity: Quantity,
    public val originalValue: Money,
    public val expiresAtEpochMillis: Long?,
    public val origin: FixtureReceiptOrigin,
) {
    public fun toReceipt(scope: BranchScope): StockReceipt = StockReceipt(receiptId,
        StockPositionKey(StockPoolKey(InventoryScope(scope.companyId, scope.branchId), skuId, warehouseId), lotId),
        quantity, originalValue, expiresAtEpochMillis)
}

public enum class InventoryReceiptStoreFailure {
    MALFORMED, TOO_LARGE, NON_CANONICAL, UNSUPPORTED_VERSION, BINDING_MISMATCH,
    INCONSISTENT_STATE, UNSUPPORTED_STATE, CURRENT_PAIR_REQUIRED, RETAINED_PRODUCER_REQUIRED,
    CLAIM_MISMATCH, SOURCE_CONFLICT,
}

/** Stable code only: no protected payload, origin reference or parser transcript escapes. */
public class InventoryReceiptStoreRejected(public val failure: InventoryReceiptStoreFailure) :
    IllegalArgumentException(failure.name)

public data class InventoryReceiptProducer(
    public val operationId: OperationId,
    public val command: CommandKey,
    public val originalActorId: DomainId,
    public val semanticDigest: PayloadDigest,
)

/** References the previous pair, not this command's resulting journal hash (which would be circular). */
public data class InventoryReceiptPreviousPair(
    public val inventoryRevision: Revision,
    public val producerOperationId: OperationId,
    public val cutDigest: PayloadDigest,
    public val companionDigest: PayloadDigest,
)

/** Complete retained receipt identity/position crosswalk, not just a set of known source UUIDs. */
public data class InventoryReceiptSourceBinding(
    public val receiptId: DomainId,
    public val producingOperationId: OperationId,
    public val origin: FixtureReceiptOrigin,
    public val position: StockPositionKey,
    public val quantity: Quantity,
    public val originalValue: Money,
    public val expiresAtEpochMillis: Long?,
)

/** Immutable posted facts. No planner, proof, deadline capability or executable proposal is decoded. */
public sealed interface InventoryReceiptPostedEffect {
    public data class Valuation(
        public val pool: StockPoolKey,
        public val requiredPoolRevision: Revision,
        public val resultingPoolRevision: Revision,
        public val receiptId: DomainId,
        public val quantity: Quantity,
        public val originalValue: Money,
        public val origin: ReceiptValueOrigin,
        public val quantityDelta: Quantity,
        public val originalValueDelta: Money,
        public val carryingValueDelta: Money,
    ) : InventoryReceiptPostedEffect

    public data class Movement(
        public val sourceId: DomainId,
        public val position: StockPositionKey,
        public val quantityDelta: Quantity,
        public val reason: StockMovementReason,
        public val sourceAllocationId: DomainId?,
    ) : InventoryReceiptPostedEffect
}

/** Expected metadata must come from the independent row/scope, never from this body's fields. */
public data class InventoryReceiptCompanionBinding(
    public val scope: BranchScope,
    public val id: DomainId,
    public val inventoryRevision: Revision,
)

/**
 * Two current CAS rows only. Prior batches are already retained by the immutable command journal.
 * All identities remain synthetic. A coherent historical pair can still be a rolled-back pair.
 * Future cost support must separately retain event IDs, cumulative cost-source-to-pool/receipt
 * bindings, and upstream natural allocation claims, including cancelled zero targets. This
 * receipt-only shape cannot encode cost sources/adjustments or pretend to handle them as empty.
 */
public class InventoryReceiptCompanion internal constructor(
    public val scope: BranchScope,
    public val id: DomainId,
    public val inventoryRevision: Revision,
    public val cutDigest: PayloadDigest,
    public val producer: InventoryReceiptProducer,
    public val previous: InventoryReceiptPreviousPair?,
    sources: List<InventoryReceiptSourceBinding>,
    effects: List<InventoryReceiptPostedEffect>,
) {
    init {
        receiptCheck(sources.size <= InventoryReceiptStoreProfile.MAX_RECEIPTS && effects.size <= 2,
            InventoryReceiptStoreFailure.TOO_LARGE)
    }
    public val sources: List<InventoryReceiptSourceBinding> = receiptFrozenList(sources)
    public val effects: List<InventoryReceiptPostedEffect> = receiptFrozenList(effects)
}

public enum class InventoryReceiptResultKind { GENESIS, RECEIVED }

/** This decoded historical result is not a new authorization or a current aggregate observation. */
public data class InventoryReceiptResult(
    public val kind: InventoryReceiptResultKind,
    public val scope: BranchScope,
    public val operationId: OperationId,
    public val inventoryRevision: Revision,
    public val receiptId: DomainId?,
    public val cutDigest: PayloadDigest,
    public val companionDigest: PayloadDigest,
)

/** Observations from one borrowed transaction; do not reuse as freshness/authorization outside it. */
public class InventoryReceiptReadCut internal constructor(
    public val scope: BranchScope,
    public val snapshot: InventorySnapshot,
    public val companion: InventoryReceiptCompanion,
    internal val cutRow: ReceiptStoredRow,
    internal val companionRow: ReceiptStoredRow,
) {
    public val storageGeneration: Revision get() = cutRow.generation
}

/** Pure writes for the caller's one outer transaction. No success is implied until kernel commit. */
public class InventoryReceiptWriteSet internal constructor(
    public val result: StoredBytes,
    public val snapshot: InventorySnapshot,
    public val companion: InventoryReceiptCompanion,
    changes: List<AggregateChange>,
    claims: List<ClaimChange>,
) {
    public val changes: List<AggregateChange> = receiptFrozenList(changes)
    public val claims: List<ClaimChange> = receiptFrozenList(claims)
}

// Adapter-owned scalar test seams; database context/aggregate/evidence constructors stay internal.
internal data class ReceiptContext(
    val scope: BranchScope,
    val originalActorId: DomainId,
    val operationId: OperationId,
    val command: CommandKey,
    val semanticDigest: PayloadDigest,
) {
    fun producer(): InventoryReceiptProducer = InventoryReceiptProducer(operationId, command, originalActorId, semanticDigest)
    companion object {
        fun from(context: CommandContext): ReceiptContext = ReceiptContext(context.scope, context.originalActorId,
            context.operationId, context.key, context.semanticDigest)
    }
}

internal data class ReceiptStoredRow(
    val key: AggregateKey,
    val generation: Revision,
    val schema: String,
    val payload: StoredBytes,
    val digest: PayloadDigest,
) {
    companion object {
        fun from(row: StoredAggregate): ReceiptStoredRow = ReceiptStoredRow(row.key, row.revision, row.schema, row.payload, row.digest)
    }
}

internal fun receiptCheck(condition: Boolean, failure: InventoryReceiptStoreFailure = InventoryReceiptStoreFailure.INCONSISTENT_STATE) {
    if (!condition) throw InventoryReceiptStoreRejected(failure)
}

internal fun <T> receiptFrozenList(values: List<T>): List<T> = ReceiptFrozenList(values.toList())

private class ReceiptFrozenList<T>(private val values: List<T>) : AbstractList<T>() {
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
}
