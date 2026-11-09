package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventoryPostingBinding
import com.ukkera.pos.inventory.InventoryPostingCodec
import com.ukkera.pos.inventory.InventoryPostingRejectedException
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshotRejectedException
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.text.CharacterCodingException

/** Bounded canonical synthetic fixture bodies. Scope, operation and actor are kernel-derived. */
public object InventoryRetailCommandCodec {
    public fun genesisRequest(): StoredBytes = retailGuard {
        encodeReceiptBody(StoredRetailGenesis(), StoredRetailGenesis.serializer(), InventoryRetailStoreProfile.MAX_REQUEST_BYTES)
    }

    public fun normalizeGenesis(input: StoredBytes): StoredBytes = retailGuard {
        val decoded = decodeReceiptBody(input, StoredRetailGenesis.serializer(), InventoryRetailStoreProfile.MAX_REQUEST_BYTES)
        retailCheck(decoded == StoredRetailGenesis(), InventoryRetailStoreFailure.UNSUPPORTED_VERSION)
        genesisRequest()
    }

    public fun encodeReceive(request: InventoryRetailReceiveBatch): StoredBytes = retailGuard {
        encodeReceiptBody(StoredRetailReceive(1, "fixture-inventory-retail-receive-v1", request.receipts.map(StoredRequest::from)),
            StoredRetailReceive.serializer(), InventoryRetailStoreProfile.MAX_REQUEST_BYTES)
    }

    public fun decodeReceive(input: StoredBytes): InventoryRetailReceiveBatch = retailGuard {
        val decoded = decodeReceiptBody(input, StoredRetailReceive.serializer(), InventoryRetailStoreProfile.MAX_REQUEST_BYTES)
        retailCheck(decoded.schemaVersion == 1 && decoded.profile == "fixture-inventory-retail-receive-v1",
            InventoryRetailStoreFailure.UNSUPPORTED_VERSION)
        InventoryRetailReceiveBatch(decoded.receipts.map { it.restore() })
    }

    public fun normalizeReceive(input: StoredBytes): StoredBytes = encodeReceive(decodeReceive(input))
}

/**
 * The canonical A1 batch is a JSON STRING, not copied five-tag/domain DTOs. The entire escaped
 * companion, including cumulative indexes and metadata, must fit the independent 64 KiB row cap.
 * Decoding yields only historical facts; it cannot recreate a plan, proof or commit deadline.
 */
public object InventoryRetailCompanionCodec {
    public fun encode(value: InventoryRetailCompanion): StoredBytes = retailGuard {
        validateRetailCompanionShape(value)
        encodeReceiptBody(StoredRetailCompanion.from(value), StoredRetailCompanion.serializer(), InventoryRetailStoreProfile.MAX_ROW_BYTES)
    }

    public fun decode(input: StoredBytes, expected: InventoryRetailCompanionBinding): InventoryRetailCompanion = retailGuard {
        val stored = decodeReceiptBody(input, StoredRetailCompanion.serializer(), InventoryRetailStoreProfile.MAX_ROW_BYTES)
        retailCheck(stored.schemaVersion == 1 && stored.profile == "purchase-retail-expiry-v1",
            InventoryRetailStoreFailure.UNSUPPORTED_VERSION)
        retailCheck(expected.id == expected.scope.branchId && stored.scope.restore() == expected.scope && stored.id == expected.id &&
            stored.inventoryRevision == expected.inventoryRevision, InventoryRetailStoreFailure.BINDING_MISMATCH)
        val producer = stored.producer.restore()
        val previous = stored.previous?.restore()
        val posting = stored.latestBatch?.let { encoded ->
            retailCheck(expected.inventoryRevision.value > 0, InventoryRetailStoreFailure.BINDING_MISMATCH)
            // The producer is selected here, then independently checked against retained kernel
            // evidence by observe. This codec alone makes no complete-producer claim.
            InventoryPostingCodec.decode(encoded, InventoryPostingBinding(
                InventoryScope(expected.scope.companyId, expected.scope.branchId), producer.operationId,
                Revision(expected.inventoryRevision.value - 1), expected.inventoryRevision))
        }
        InventoryRetailCompanion(expected.scope, expected.id, expected.inventoryRevision, PayloadDigest(stored.cutDigest),
            producer, previous, stored.receipts.map { it.restore() }, stored.issueSources.map { it.restore() }, posting)
            .also(::validateRetailCompanionShape)
    }
}

public object InventoryRetailFixtureResultCodec {
    public fun decode(input: StoredBytes): InventoryRetailFixtureResult = retailGuard {
        val stored = decodeReceiptBody(input, StoredRetailResult.serializer(), InventoryRetailStoreProfile.MAX_RESULT_BYTES)
        retailCheck(stored.schemaVersion == 1, InventoryRetailStoreFailure.UNSUPPORTED_VERSION)
        InventoryRetailFixtureResult(enumValueOf<InventoryRetailFixtureResultKind>(stored.kind), stored.scope.restore(),
            stored.operationId, stored.inventoryRevision, stored.receiptIds, PayloadDigest(stored.cutDigest), PayloadDigest(stored.companionDigest))
            .also(::validateRetailResult)
    }

    internal fun encode(value: InventoryRetailFixtureResult): StoredBytes = retailGuard {
        validateRetailResult(value)
        encodeReceiptBody(StoredRetailResult(1, value.kind.name, StoredScope.from(value.scope), value.operationId,
            value.inventoryRevision, value.receiptIds, value.cutDigest.value, value.companionDigest.value),
            StoredRetailResult.serializer(), InventoryRetailStoreProfile.MAX_RESULT_BYTES)
    }
}

internal fun validateRetailCompanionShape(value: InventoryRetailCompanion) {
    retailCheck(value.id == value.scope.branchId, InventoryRetailStoreFailure.BINDING_MISMATCH)
    retailCheck(value.receipts.size <= InventoryRetailStoreProfile.MAX_RECEIPTS &&
        value.issueSources.size <= InventoryRetailStoreProfile.MAX_ISSUES &&
        value.inventoryRevision.value <= InventoryRetailStoreProfile.MAX_INVENTORY_OPERATIONS, InventoryRetailStoreFailure.TOO_LARGE)
    retailCheck(value.receipts.map { it.receiptId }.distinct().size == value.receipts.size &&
        value.receipts.map { it.origin }.distinct().size == value.receipts.size &&
        value.issueSources.map { it.issueId }.distinct().size == value.issueSources.size &&
        value.issueSources.none { issue -> value.receipts.any { it.receiptId == issue.issueId } }, InventoryRetailStoreFailure.SOURCE_CONFLICT)
    val scope = InventoryScope(value.scope.companyId, value.scope.branchId)
    for (receipt in value.receipts) {
        retailCheck(receipt.position.pool.scope == scope, InventoryRetailStoreFailure.BINDING_MISMATCH)
        retailCheck(receipt.position.pool.ownership == StockOwnership.CompanyOwned && receipt.position.custody == StockCustody.RELEASED,
            InventoryRetailStoreFailure.UNSUPPORTED_STATE)
        retailCheck(receipt.quantity.scaled > 0 && receipt.originalValue.minor >= 0 &&
            (receipt.expiresAtEpochMillis == null || (receipt.expiresAtEpochMillis >= 0 && receipt.position.lotId != null)))
    }
    if (value.inventoryRevision == Revision(0)) {
        retailCheck(value.producer.command == InventoryRetailStoreProfile.GENESIS_COMMAND && value.previous == null &&
            value.latestBatch == null && value.receipts.isEmpty() && value.issueSources.isEmpty())
    } else {
        val previous = value.previous ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
        val posting = value.latestBatch ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
        retailCheck(previous.inventoryRevision.next() == value.inventoryRevision && previous.cutDigest != value.cutDigest &&
            previous.producerOperationId != value.producer.operationId)
        retailCheck(posting.binding == InventoryPostingBinding(scope, value.producer.operationId, previous.inventoryRevision, value.inventoryRevision),
            InventoryRetailStoreFailure.BINDING_MISMATCH)
    }
}

private fun validateRetailResult(value: InventoryRetailFixtureResult) {
    retailCheck(value.receiptIds.size <= InventoryRetailStoreProfile.MAX_RECEIPTS, InventoryRetailStoreFailure.TOO_LARGE)
    retailCheck(value.receiptIds.distinct().size == value.receiptIds.size)
    when (value.kind) {
        InventoryRetailFixtureResultKind.GENESIS -> retailCheck(value.inventoryRevision == Revision(0) && value.receiptIds.isEmpty())
        InventoryRetailFixtureResultKind.RECEIVED -> retailCheck(
            value.inventoryRevision.value in 1..InventoryRetailStoreProfile.MAX_INVENTORY_OPERATIONS.toLong() && value.receiptIds.isNotEmpty())
    }
}

@Serializable
private data class StoredRetailGenesis(val profile: String = "fixture-inventory-retail-genesis-v1")

@Serializable
private data class StoredRetailReceive(val schemaVersion: Int, val profile: String, val receipts: List<StoredRequest>)

@Serializable
private data class StoredRetailIssueSource(val issueId: DomainId, val producingOperationId: OperationId) {
    fun restore(): InventoryRetailIssueSourceBinding = InventoryRetailIssueSourceBinding(issueId, producingOperationId)
    companion object {
        fun from(value: InventoryRetailIssueSourceBinding): StoredRetailIssueSource = StoredRetailIssueSource(value.issueId, value.producingOperationId)
    }
}

@Serializable
private data class StoredRetailCompanion(
    val schemaVersion: Int, val profile: String, val scope: StoredScope, val id: DomainId, val inventoryRevision: Revision,
    val cutDigest: String, val producer: StoredProducer, val previous: StoredPrevious?, val receipts: List<StoredSource>,
    val issueSources: List<StoredRetailIssueSource>, val latestBatch: String?,
) {
    companion object {
        fun from(value: InventoryRetailCompanion): StoredRetailCompanion = StoredRetailCompanion(1, "purchase-retail-expiry-v1",
            StoredScope.from(value.scope), value.id, value.inventoryRevision, value.cutDigest.value, StoredProducer.from(value.producer),
            value.previous?.let(StoredPrevious::from), value.receipts.map(StoredSource::from), value.issueSources.map(StoredRetailIssueSource::from),
            value.latestBatch?.let(InventoryPostingCodec::encode))
    }
}

@Serializable
private data class StoredRetailResult(val schemaVersion: Int, val kind: String, val scope: StoredScope, val operationId: OperationId,
    val inventoryRevision: Revision, val receiptIds: List<DomainId>, val cutDigest: String, val companionDigest: String)

internal inline fun <T> retailGuard(block: () -> T): T = try {
    block()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: InventoryRetailStoreRejected) {
    throw failure
} catch (failure: InventoryReceiptStoreRejected) {
    throw InventoryRetailStoreRejected(enumValueOf<InventoryRetailStoreFailure>(failure.failure.name))
} catch (failure: InventoryPostingRejectedException) {
    throw InventoryRetailStoreRejected(enumValueOf<InventoryRetailStoreFailure>(failure.failure.name))
} catch (failure: InventorySnapshotRejectedException) {
    throw InventoryRetailStoreRejected(enumValueOf<InventoryRetailStoreFailure>(failure.failure.name))
} catch (_: CharacterCodingException) {
    throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.MALFORMED)
} catch (_: SerializationException) {
    throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.MALFORMED)
} catch (_: IllegalArgumentException) {
    throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
} catch (_: ArithmeticException) {
    throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
} catch (_: IllegalStateException) {
    throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
} catch (_: NoSuchElementException) {
    throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
}
