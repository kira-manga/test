package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.cancellation.CancellationException

/**
 * One actual opening-receipt line retained by the production `stock.opening.post` command.
 * This is a copied observation for setup settlement; it is not a stock read cut, a replayable
 * request or authority to post another receipt. [sourceId] is the stable per-row identity that
 * the stock command already claimed, so retries cannot create a second row for the same source.
 */
public data class LocalStockOpeningEvidenceLineV1(
    public val sourceId: DomainId,
    public val skuId: DomainId,
    public val warehouseId: DomainId,
    public val quantity: Quantity,
    public val originalValue: Money,
    public val lotId: DomainId?,
    public val serialId: DomainId?,
) {
    init {
        require(quantity.scaled > 0)
        require(originalValue.minor >= 0)
    }
    override fun toString(): String = "LocalStockOpeningEvidenceLineV1([REDACTED])"
}

/**
 * Exact opening operation/result/effect observation. The operation command, canonical request,
 * typed result, receipt-document write and digest are all checked before this object exists.
 * It contains no native custody proof, current grant or unrelated receipt inference.
 */
public data class LocalStockOpeningEvidenceV1(
    public val receiptId: DomainId,
    public val operationId: OperationId,
    public val actorId: DomainId,
    public val recordedAt: UtcInstant,
    public val effectDigest: PayloadDigest,
    public val effectRevision: Revision,
    public val effectSchema: String,
    public val lines: List<LocalStockOpeningEvidenceLineV1>,
) {
    init {
        require(lines.isNotEmpty())
        require(lines.map { it.sourceId }.distinct().size == lines.size)
    }
    override fun toString(): String = "LocalStockOpeningEvidenceV1([REDACTED])"
}

/**
 * Reads a retained opening command without inventing opening state. A missing, rejected,
 * noncanonical, non-opening or mismatched producer is unavailable. The live [observe] overload
 * additionally requires the exact current receipt row and the stock command's stable line claims.
 */
public object LocalStockOpeningEvidenceV1Source {
    private val commands: Set<com.ukkera.pos.database.CommandKey> =
        setOf(LocalStockStoreProfile.OPENING, LocalStockStoreProfile.OPENING_SERIAL)

    public fun fromCommand(evidence: RetainedCommandEvidence, receiptId: DomainId): LocalStockOpeningEvidenceV1? {
        if (!evidence.committed || evidence.nativeOriginalProvenance != null || evidence.command !in commands) return null
        val requestReceipt = try {
            LocalStockCommands.retainedOpeningReceiptId(evidence.command, evidence.normalized)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (requestReceipt != receiptId) return null
        val result = try {
            LocalStockCommands.decodeResult(evidence.result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (result.kind != LocalStockResultKind.OPENED || result.documentId != receiptId || result.failure != null) return null
        val key = AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, receiptId)
        val write = evidence.aggregateWrites.singleOrNull { it.key == key } ?: return null
        val stored = try {
            stockDecode(write.payload, StockRecordWire.serializer(), LocalStockStoreProfile.MAX_ROW_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (stored.scope.restore() != evidence.scope || stored.kind != LocalStockStoreProfile.RECEIPT_DOCUMENT ||
            stored.id != receiptId || stored.storageRevision != write.storageGeneration ||
            stored.producer.operationId != evidence.operationId || stored.producer.commandKey() != evidence.command ||
            stored.producer.actorId != evidence.originalActorId ||
            stored.producer.digest != evidence.semanticDigest.value) return null
        val version = stockRecordVersion(LocalStockStoreProfile.RECEIPT_DOCUMENT, stored.body)
        if (stored.version != version ||
            write.schema != LocalStockStoreProfile.schema(LocalStockStoreProfile.RECEIPT_DOCUMENT, version)) return null
        val receipt = try {
            stockBody(stored.body, StockReceiptWire.serializer())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (receipt.receiptId != receiptId || receipt.basisKind != LocalStockBasisKind.OPENING.name ||
            receipt.supplierId != null || receipt.lines.isEmpty()) return null
        val lines = receipt.lines.map { slice ->
            val line = slice.request
            if (line.disposition != LocalReceiptDisposition.RELEASED.name && line.disposition != LocalReceiptDisposition.QUARANTINE.name) {
                return null
            }
            val value = line.originalValue ?: return null
            LocalStockOpeningEvidenceLineV1(line.sourceId, line.skuId, line.warehouseId, slice.baseQuantity,
                value, line.lotId, line.serialId)
        }
        if (lines.map { it.sourceId }.distinct().size != lines.size) return null
        return LocalStockOpeningEvidenceV1(receiptId, evidence.operationId, evidence.originalActorId,
            evidence.recordedAt, write.payloadDigest, write.storageGeneration, write.schema, lines)
    }

    public suspend fun observe(view: CommandReadView, operationId: OperationId,
        receiptId: DomainId): LocalStockOpeningEvidenceV1? {
        val evidence = view.retainedCommand(operationId) ?: return null
        if (evidence.scope != view.scope) return null
        val value = fromCommand(evidence, receiptId) ?: return null
        val key = AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, receiptId)
        val current = view.aggregate(key) ?: return null
        if (current.revision != value.effectRevision || current.digest != value.effectDigest ||
            current.schema != value.effectSchema) return null
        for (line in value.lines) {
            if (view.claim(LocalStockStoreProfile.receiptLineClaim(line.sourceId)) != receiptId) return null
        }
        return value
    }
}
