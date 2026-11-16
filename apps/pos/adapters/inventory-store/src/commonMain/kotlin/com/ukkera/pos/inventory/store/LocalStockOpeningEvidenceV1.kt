package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RetainedStreamedCommand
import com.ukkera.pos.database.RuntimeHistoricalSetupReadView
import com.ukkera.pos.database.StockRelationalReadView
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.cancellation.CancellationException

/**
 * One actual opening-receipt line retained by a production legacy or normalized opening command.
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
        return fromReceipt(evidence.scope, evidence.operationId, evidence.originalActorId, evidence.command,
            evidence.semanticDigest, evidence.recordedAt, receiptId, write.storageGeneration, write.schema,
            write.payloadDigest, write.payload)
    }

    private suspend fun fromStreamed(original: RetainedStreamedCommand, receiptId: DomainId,
        repository: LocalStockRepository): LocalStockOpeningEvidenceV1? {
        val identity = original.identity
        val producer = identity.producer
        if (producer.command != LocalStockRelationalProfileV2.COMMAND) return null
        val request = LocalStockRelationalCommandsV2.decode(original.normalized) as? LocalStockRelationalCommand.Receive
            ?: return null
        if (!request.opening || request.operationId != producer.operationId || request.request.receiptId != receiptId) return null
        val result = LocalStockRelationalCommandsV2.decodeResult(original.normalized, original.result)
        if (result.kind != LocalStockResultKind.OPENED || result.documentId != receiptId || result.failure != null) return null
        // Full installed producer, literal source graph, claims, effects and resource closure
        // precede selecting the receipt. A favorable early frame is never sufficient.
        repository.requireRetainedOpeningProducer(original)
        val write = original.writeRow(AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, receiptId)) ?: return null
        return fromReceipt(identity.scope, producer.operationId, producer.originalActorId, producer.command,
            producer.semanticDigest, identity.recordedAt, receiptId, write.revision, write.schema,
            write.digest, write.payload)
    }

    private fun fromReceipt(scope: BranchScope, operationId: OperationId, actorId: DomainId,
        command: CommandKey, semanticDigest: PayloadDigest, recordedAt: UtcInstant, receiptId: DomainId,
        generation: Revision, schema: String, digest: PayloadDigest, payload: StoredBytes): LocalStockOpeningEvidenceV1? {
        val stored = try {
            stockDecode(payload, StockRecordWire.serializer(), LocalStockStoreProfile.MAX_ROW_BYTES)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        if (stored.scope.restore() != scope || stored.kind != LocalStockStoreProfile.RECEIPT_DOCUMENT ||
            stored.id != receiptId || stored.storageRevision != generation ||
            stored.producer.operationId != operationId || stored.producer.commandKey() != command ||
            stored.producer.actorId != actorId || stored.producer.digest != semanticDigest.value) return null
        val version = stockRecordVersion(LocalStockStoreProfile.RECEIPT_DOCUMENT, stored.body)
        if (stored.version != version ||
            schema != LocalStockStoreProfile.schema(LocalStockStoreProfile.RECEIPT_DOCUMENT, version)) return null
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
        return LocalStockOpeningEvidenceV1(receiptId, operationId, actorId, recordedAt, digest, generation, schema, lines)
    }

    public suspend fun observe(view: CommandReadView, operationId: OperationId,
        receiptId: DomainId, repository: LocalStockRepository? = null): LocalStockOpeningEvidenceV1? {
        suspend fun extract(original: RetainedOriginalV2?): LocalStockOpeningEvidenceV1? = when (original) {
            is RetainedOriginalV2.Legacy -> original.evidence.takeIf { it.scope == view.scope }
                ?.let { fromCommand(it, receiptId) }
            is RetainedOriginalV2.Streamed -> if (original.evidence.identity.scope == view.scope && repository != null)
                fromStreamed(original.evidence, receiptId, repository) else null
            null -> null
        }
        val value = when (view) {
            is RuntimeHistoricalSetupReadView -> view.withEarlierOriginal(operationId) { extract(it) }
            is StockRelationalReadView -> extract(view.stockRelationalAccess().originals.read(operationId))
            else -> view.retainedCommand(operationId)?.takeIf { it.scope == view.scope }?.let { fromCommand(it, receiptId) }
        } ?: return null
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
