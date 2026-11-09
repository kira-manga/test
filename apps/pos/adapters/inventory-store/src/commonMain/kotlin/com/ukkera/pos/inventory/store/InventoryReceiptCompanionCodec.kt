package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.text.CharacterCodingException

/** Canonical fixture inputs only. No production route is registered by this codec. */
public object InventoryReceiptCommandCodec {
    public fun genesisRequest(): StoredBytes = encodeReceiptBody(StoredGenesis(), StoredGenesis.serializer(),
        InventoryReceiptStoreProfile.MAX_REQUEST_BYTES)

    public fun normalizeGenesis(input: StoredBytes): StoredBytes {
        val decoded = decodeReceiptBody(input, StoredGenesis.serializer(), InventoryReceiptStoreProfile.MAX_REQUEST_BYTES)
        receiptCheck(decoded == StoredGenesis(), InventoryReceiptStoreFailure.UNSUPPORTED_VERSION)
        return genesisRequest()
    }

    public fun encode(request: InventoryFixtureReceiveRequest): StoredBytes {
        validateReceiptRequest(request)
        return encodeReceiptBody(StoredRequest.from(request), StoredRequest.serializer(), InventoryReceiptStoreProfile.MAX_REQUEST_BYTES)
    }

    public fun decode(input: StoredBytes): InventoryFixtureReceiveRequest = receiptGuard {
        decodeReceiptBody(input, StoredRequest.serializer(), InventoryReceiptStoreProfile.MAX_REQUEST_BYTES).restore()
            .also(::validateReceiptRequest)
    }

    public fun normalizeReceive(input: StoredBytes): StoredBytes = encode(decode(input))
}

/** Bounded local facts, never a decoded InventoryPlan/ValuationPlan or a provenance certificate. */
public object InventoryReceiptCompanionCodec {
    public fun encode(value: InventoryReceiptCompanion): StoredBytes = receiptGuard {
        validateCompanionShape(value)
        encodeReceiptBody(StoredCompanion.from(value), StoredCompanion.serializer(), InventoryReceiptStoreProfile.MAX_ROW_BYTES)
    }

    public fun decode(input: StoredBytes, expected: InventoryReceiptCompanionBinding): InventoryReceiptCompanion = receiptGuard {
        val decoded = decodeReceiptBody(input, StoredCompanion.serializer(), InventoryReceiptStoreProfile.MAX_ROW_BYTES).restore()
        receiptCheck(expected.id == expected.scope.branchId && decoded.scope == expected.scope && decoded.id == expected.id &&
            decoded.inventoryRevision == expected.inventoryRevision, InventoryReceiptStoreFailure.BINDING_MISMATCH)
        validateCompanionShape(decoded)
        decoded
    }
}

public object InventoryReceiptResultCodec {
    public fun decode(input: StoredBytes): InventoryReceiptResult = receiptGuard {
        decodeReceiptBody(input, StoredResult.serializer(), InventoryReceiptStoreProfile.MAX_RESULT_BYTES).restore()
            .also(::validateReceiptResult)
    }

    internal fun encode(value: InventoryReceiptResult): StoredBytes = receiptGuard {
        validateReceiptResult(value)
        encodeReceiptBody(StoredResult.from(value), StoredResult.serializer(), InventoryReceiptStoreProfile.MAX_RESULT_BYTES)
    }
}

internal fun validateReceiptRequest(value: InventoryFixtureReceiveRequest) {
    receiptCheck(value.quantity.scaled > 0 && value.originalValue.minor >= 0 &&
        (value.expiresAtEpochMillis == null || (value.expiresAtEpochMillis >= 0 && value.lotId != null)))
}

internal fun validateCompanionShape(value: InventoryReceiptCompanion) {
    val sources = value.sources
    receiptCheck(value.id == value.scope.branchId, InventoryReceiptStoreFailure.BINDING_MISMATCH)
    receiptCheck(sources.size <= InventoryReceiptStoreProfile.MAX_RECEIPTS, InventoryReceiptStoreFailure.TOO_LARGE)
    receiptCheck(value.inventoryRevision.value == sources.size.toLong())
    receiptCheck(sources.map { it.receiptId }.distinct().size == sources.size &&
        sources.map { it.producingOperationId }.distinct().size == sources.size &&
        sources.map { it.origin }.distinct().size == sources.size)
    sources.forEach { source ->
        receiptCheck(source.position.pool.scope == InventoryScope(value.scope.companyId, value.scope.branchId) &&
            source.position.pool.ownership == StockOwnership.CompanyOwned && source.position.custody == StockCustody.RELEASED && source.position.serialId == null)
        receiptCheck(source.quantity.scaled > 0 && source.originalValue.minor >= 0 &&
            (source.expiresAtEpochMillis == null || (source.expiresAtEpochMillis >= 0 && source.position.lotId != null)))
    }
    if (sources.isEmpty()) {
        receiptCheck(value.producer.command == InventoryReceiptStoreProfile.GENESIS_COMMAND && value.previous == null && value.effects.isEmpty())
        return
    }
    receiptCheck(value.producer.command == InventoryReceiptStoreProfile.RECEIVE_COMMAND)
    val previous = value.previous ?: throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.INCONSISTENT_STATE)
    receiptCheck(previous.inventoryRevision.next() == value.inventoryRevision && previous.cutDigest != value.cutDigest &&
        previous.producerOperationId != value.producer.operationId)
    if (sources.size > 1) receiptCheck(previous.producerOperationId == sources[sources.lastIndex - 1].producingOperationId)
    else receiptCheck(previous.producerOperationId !in sources.map { it.producingOperationId })
    val source = sources.last()
    receiptCheck(source.producingOperationId == value.producer.operationId && value.effects.size == 2)
    val valuation = value.effects[0] as? InventoryReceiptPostedEffect.Valuation
        ?: throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.INCONSISTENT_STATE)
    val movement = value.effects[1] as? InventoryReceiptPostedEffect.Movement
        ?: throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.INCONSISTENT_STATE)
    val priorPoolReceipts = sources.dropLast(1).count { it.position.pool == source.position.pool }
    receiptCheck(valuation.pool == source.position.pool && valuation.requiredPoolRevision == Revision(priorPoolReceipts.toLong()) &&
        valuation.resultingPoolRevision == valuation.requiredPoolRevision.next() && valuation.receiptId == source.receiptId &&
        valuation.quantity == source.quantity && valuation.originalValue == source.originalValue && valuation.origin == ReceiptValueOrigin.PURCHASE &&
        valuation.quantityDelta == source.quantity && valuation.originalValueDelta == source.originalValue && valuation.carryingValueDelta == source.originalValue)
    receiptCheck(movement.sourceId == source.receiptId && movement.position == source.position && movement.quantityDelta == source.quantity &&
        movement.reason == StockMovementReason.RECEIPT && movement.sourceAllocationId == null)
}

private fun validateReceiptResult(value: InventoryReceiptResult) {
    when (value.kind) {
        InventoryReceiptResultKind.GENESIS -> receiptCheck(value.inventoryRevision == Revision(0) && value.receiptId == null)
        InventoryReceiptResultKind.RECEIVED -> receiptCheck(value.inventoryRevision.value in 1..InventoryReceiptStoreProfile.MAX_RECEIPTS.toLong() &&
            value.receiptId != null)
    }
}

@Serializable
private data class StoredGenesis(val profile: String = "fixture-inventory-genesis-v1")

@Serializable
private sealed interface StoredPostedEffect {
    fun restore(): InventoryReceiptPostedEffect

    @Serializable @SerialName("RECEIPT_VALUATION")
    data class Valuation(val pool: StoredPool, val requiredPoolRevision: Revision, val resultingPoolRevision: Revision,
        val receiptId: DomainId, val quantity: Quantity, val originalValue: Money, val origin: String,
        val quantityDelta: Quantity, val originalValueDelta: Money, val carryingValueDelta: Money) : StoredPostedEffect {
        override fun restore(): InventoryReceiptPostedEffect = InventoryReceiptPostedEffect.Valuation(pool.restore(), requiredPoolRevision,
            resultingPoolRevision, receiptId, quantity, originalValue, enumValueOf<ReceiptValueOrigin>(origin), quantityDelta, originalValueDelta, carryingValueDelta)
    }

    @Serializable @SerialName("RECEIPT_MOVEMENT")
    data class Movement(val sourceId: DomainId, val position: StoredPosition, val quantityDelta: Quantity, val reason: String,
        val sourceAllocationId: DomainId?) : StoredPostedEffect {
        override fun restore(): InventoryReceiptPostedEffect = InventoryReceiptPostedEffect.Movement(sourceId, position.restore(), quantityDelta,
            enumValueOf<StockMovementReason>(reason), sourceAllocationId)
    }

    companion object {
        fun from(value: InventoryReceiptPostedEffect): StoredPostedEffect = when (value) {
            is InventoryReceiptPostedEffect.Valuation -> Valuation(StoredPool.from(value.pool), value.requiredPoolRevision, value.resultingPoolRevision,
                value.receiptId, value.quantity, value.originalValue, value.origin.name, value.quantityDelta, value.originalValueDelta, value.carryingValueDelta)
            is InventoryReceiptPostedEffect.Movement -> Movement(value.sourceId, StoredPosition.from(value.position), value.quantityDelta, value.reason.name, value.sourceAllocationId)
        }
    }
}

@Serializable
private data class StoredCompanion(val schemaVersion: Int, val profile: String, val scope: StoredScope, val id: DomainId,
    val inventoryRevision: Revision, val cutDigest: String, val producer: StoredProducer, val previous: StoredPrevious?,
    val sources: List<StoredSource>, val effects: List<StoredPostedEffect>) {
    fun restore(): InventoryReceiptCompanion {
        receiptCheck(schemaVersion == 1 && profile == "fixture-receipts-v1", InventoryReceiptStoreFailure.UNSUPPORTED_VERSION)
        return InventoryReceiptCompanion(scope.restore(), id, inventoryRevision, PayloadDigest(cutDigest), producer.restore(), previous?.restore(),
            sources.map { it.restore() }, effects.map { it.restore() })
    }
    companion object {
        fun from(value: InventoryReceiptCompanion): StoredCompanion = StoredCompanion(1, "fixture-receipts-v1", StoredScope.from(value.scope),
            value.id, value.inventoryRevision, value.cutDigest.value, StoredProducer.from(value.producer), value.previous?.let(StoredPrevious::from),
            value.sources.map(StoredSource::from), value.effects.map(StoredPostedEffect::from))
    }
}

@Serializable
private data class StoredResult(val schemaVersion: Int, val kind: String, val scope: StoredScope, val operationId: OperationId,
    val inventoryRevision: Revision, val receiptId: DomainId?, val cutDigest: String, val companionDigest: String) {
    fun restore(): InventoryReceiptResult {
        receiptCheck(schemaVersion == 1, InventoryReceiptStoreFailure.UNSUPPORTED_VERSION)
        return InventoryReceiptResult(enumValueOf<InventoryReceiptResultKind>(kind), scope.restore(), operationId, inventoryRevision,
            receiptId, PayloadDigest(cutDigest), PayloadDigest(companionDigest))
    }
    companion object {
        fun from(value: InventoryReceiptResult): StoredResult = StoredResult(1, value.kind.name, StoredScope.from(value.scope), value.operationId,
            value.inventoryRevision, value.receiptId, value.cutDigest.value, value.companionDigest.value)
    }
}

internal inline fun <T> receiptGuard(block: () -> T): T = try {
    block()
} catch (failure: InventoryReceiptStoreRejected) {
    throw failure
} catch (_: CharacterCodingException) {
    throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.MALFORMED)
} catch (_: SerializationException) {
    throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.MALFORMED)
} catch (_: IllegalArgumentException) {
    throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.INCONSISTENT_STATE)
} catch (_: ArithmeticException) {
    throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.INCONSISTENT_STATE)
} catch (_: IllegalStateException) {
    throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.INCONSISTENT_STATE)
}
