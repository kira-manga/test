package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.StockCustody
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// Mechanical receipt-v1 wire extraction. Field order, annotations, scanner and errors are unchanged.
@Serializable
internal data class StoredOrigin(val kind: String, val documentId: DomainId, val allocationId: DomainId) {
    fun restore(): FixtureReceiptOrigin {
        receiptCheck(kind == "SYNTHETIC_FIXTURE", InventoryReceiptStoreFailure.UNSUPPORTED_VERSION)
        return FixtureReceiptOrigin(documentId, allocationId)
    }
    companion object { fun from(value: FixtureReceiptOrigin): StoredOrigin = StoredOrigin("SYNTHETIC_FIXTURE", value.documentId, value.allocationId) }
}

@Serializable
internal data class StoredRequest(
    val expectedRevision: Revision, val receiptId: DomainId, val skuId: DomainId, val warehouseId: DomainId,
    val lotId: DomainId?, val quantity: Quantity, val originalValue: Money, val expiresAtEpochMillis: String?, val origin: StoredOrigin,
) {
    fun restore(): InventoryFixtureReceiveRequest = InventoryFixtureReceiveRequest(expectedRevision, receiptId, skuId, warehouseId,
        lotId, quantity, originalValue, receiptEpoch(expiresAtEpochMillis), origin.restore())
    companion object {
        fun from(value: InventoryFixtureReceiveRequest): StoredRequest = StoredRequest(value.expectedRevision, value.receiptId,
            value.skuId, value.warehouseId, value.lotId, value.quantity, value.originalValue, value.expiresAtEpochMillis?.toString(), StoredOrigin.from(value.origin))
    }
}

@Serializable
internal data class StoredScope(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId) {
    fun restore(): BranchScope = BranchScope(organizationId, companyId, branchId)
    companion object { fun from(value: BranchScope): StoredScope = StoredScope(value.organizationId, value.companyId, value.branchId) }
}

@Serializable
internal data class StoredPool(val companyId: DomainId, val branchId: DomainId, val skuId: DomainId, val warehouseId: DomainId, val ownership: String) {
    fun restore(): StockPoolKey {
        receiptCheck(ownership == "COMPANY_OWNED", InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
        return StockPoolKey(InventoryScope(companyId, branchId), skuId, warehouseId)
    }
    companion object {
        fun from(value: StockPoolKey): StoredPool {
            receiptCheck(value.ownership == StockOwnership.CompanyOwned, InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
            return StoredPool(value.scope.companyId, value.scope.branchId, value.skuId, value.warehouseId, "COMPANY_OWNED")
        }
    }
}

@Serializable
internal data class StoredPosition(val pool: StoredPool, val lotId: DomainId?, val custody: String) {
    fun restore(): StockPositionKey {
        receiptCheck(custody == "RELEASED", InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
        return StockPositionKey(pool.restore(), lotId)
    }
    companion object {
        fun from(value: StockPositionKey): StoredPosition {
            receiptCheck(value.custody == StockCustody.RELEASED && value.serialId == null, InventoryReceiptStoreFailure.UNSUPPORTED_STATE)
            return StoredPosition(StoredPool.from(value.pool), value.lotId, "RELEASED")
        }
    }
}

@Serializable
internal data class StoredProducer(val operationId: OperationId, val commandName: String, val commandVersion: Int,
    val originalActorId: DomainId, val semanticDigest: String) {
    fun restore(): InventoryReceiptProducer = InventoryReceiptProducer(operationId, CommandKey(commandName, commandVersion), originalActorId, PayloadDigest(semanticDigest))
    companion object {
        fun from(value: InventoryReceiptProducer): StoredProducer = StoredProducer(value.operationId, value.command.name, value.command.version,
            value.originalActorId, value.semanticDigest.value)
    }
}

@Serializable
internal data class StoredPrevious(val inventoryRevision: Revision, val producerOperationId: OperationId, val cutDigest: String, val companionDigest: String) {
    fun restore(): InventoryReceiptPreviousPair = InventoryReceiptPreviousPair(inventoryRevision, producerOperationId, PayloadDigest(cutDigest), PayloadDigest(companionDigest))
    companion object {
        fun from(value: InventoryReceiptPreviousPair): StoredPrevious = StoredPrevious(value.inventoryRevision, value.producerOperationId,
            value.cutDigest.value, value.companionDigest.value)
    }
}

@Serializable
internal data class StoredSource(val receiptId: DomainId, val producingOperationId: OperationId, val origin: StoredOrigin,
    val position: StoredPosition, val quantity: Quantity, val originalValue: Money, val expiresAtEpochMillis: String?) {
    fun restore(): InventoryReceiptSourceBinding = InventoryReceiptSourceBinding(receiptId, producingOperationId, origin.restore(),
        position.restore(), quantity, originalValue, receiptEpoch(expiresAtEpochMillis))
    companion object {
        fun from(value: InventoryReceiptSourceBinding): StoredSource = StoredSource(value.receiptId, value.producingOperationId,
            StoredOrigin.from(value.origin), StoredPosition.from(value.position), value.quantity, value.originalValue, value.expiresAtEpochMillis?.toString())
    }
}

private val receiptJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    allowSpecialFloatingPointValues = false
}

internal fun receiptEpoch(value: String?): Long? {
    if (value == null) return null
    receiptCheck(value.length in 1..19 && (value == "0" || (value[0] in '1'..'9' && value.all { it in '0'..'9' })))
    return value.toLongOrNull() ?: throw InventoryReceiptStoreRejected(InventoryReceiptStoreFailure.INCONSISTENT_STATE)
}

internal fun <T> encodeReceiptBody(value: T, serializer: KSerializer<T>, maximum: Int): StoredBytes = receiptGuard {
    val text = receiptJson.encodeToString(serializer, value)
    receiptCheck(text.length <= maximum, InventoryReceiptStoreFailure.TOO_LARGE)
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    receiptCheck(bytes.size <= maximum, InventoryReceiptStoreFailure.TOO_LARGE)
    boundReceiptJson(text)
    StoredBytes(bytes)
}

internal fun <T> decodeReceiptBody(input: StoredBytes, serializer: KSerializer<T>, maximum: Int): T = receiptGuard {
    receiptCheck(input.size <= maximum, InventoryReceiptStoreFailure.TOO_LARGE)
    val text = input.copyBytes().decodeToString(throwOnInvalidSequence = true)
    boundReceiptJson(text) // Before allocating recursive serializer DTOs, including unknown fields.
    val value = receiptJson.decodeFromString(serializer, text)
    receiptCheck(receiptJson.encodeToString(serializer, value) == text, InventoryReceiptStoreFailure.NON_CANONICAL)
    value
}

private fun boundReceiptJson(text: String) {
    var quoted = false
    var escaped = false
    var containers = 0
    val frames = mutableListOf<Pair<Char, Int>>()
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                containers++
                receiptCheck(frames.size < 16 && containers <= 1_024, InventoryReceiptStoreFailure.TOO_LARGE)
                frames += character to 0
            }
            '}', ']' -> {
                receiptCheck(frames.isNotEmpty() && frames.last().first == if (character == '}') '{' else '[', InventoryReceiptStoreFailure.MALFORMED)
                frames.removeAt(frames.lastIndex)
            }
            ',' -> if (frames.isNotEmpty() && frames.last().first == '[') {
                val count = frames.last().second + 1
                receiptCheck(count < InventoryReceiptStoreProfile.MAX_RECEIPTS, InventoryReceiptStoreFailure.TOO_LARGE)
                frames[frames.lastIndex] = '[' to count
            }
        }
    }
    receiptCheck(!quoted && frames.isEmpty(), InventoryReceiptStoreFailure.MALFORMED)
}
