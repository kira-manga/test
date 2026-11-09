package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Exact two native closing indices and their already joined writers. Unsupported source
 * schemas/custody walks stay held; no caller-supplied view or empty-work default is used. */
internal suspend fun cashClosingFoodHistory(command: RuntimeHistoricalCommand, ownerId: DomainId): FoodClosingWork? =
    try { CashClosingFoodHistorySources(command).read(ownerId) }
    catch (_: FoodCapacityExceeded) { FoodClosingWork.CapacityRequired }

private class CashClosingFoodHistorySources(private val command: RuntimeHistoricalCommand) {
    private val checkedDirect = mutableSetOf<OperationId>()

    suspend fun read(ownerId: DomainId): FoodClosingWork? {
        val indexKey = LocalFoodProfileV1.key(LocalFoodProfileV1.OWNER_INDEX, ownerId)
        val indexSource = command.aggregateSource(indexKey)
        val indexClaim = command.claim(LocalFoodProfileV1.claim(indexKey.kind, ownerId))
        val index = if (indexSource == null) {
            check(indexClaim == null)
            null
        } else {
            val row = indexSource.row
            check(indexClaim == ownerId && row.key == indexKey && row.revision.value > 0 &&
                row.digest == sha256(row.payload.copyBytes()))
            if (row.schema !in setOf(LocalFoodProfileV1.schema(indexKey.kind), foodStockSchema(indexKey.kind))) return null
            val value = foodStoredEnvelope(row.schema, row.payload)
            check(value.scope == command.scope && value.kind == indexKey.kind && value.id == ownerId &&
                value.revision == row.revision && value.body is FoodBodyV1.OwnerIndex)
            when (val original = indexSource.original) {
                is RetainedOriginalV2.Streamed -> {
                    if (row.schema != foodStockSchema(indexKey.kind)) return null
                    requireStreamed(original, value.operationId)
                    FoodRow(row, value, original.evidence)
                }
                is RetainedOriginalV2.Legacy -> {
                    if (row.schema != LocalFoodProfileV1.schema(indexKey.kind) ||
                        !requireSimpleLegacyRead(original.evidence)) return null
                    check(value.operationId == original.evidence.operationId)
                    FoodRow(row, value, original.evidence)
                }
            }
        }
        val claimId = foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, ownerId)
        val claimKey = AggregateKey(LocalFoodFinancialProfileV1.CLAIM_INDEX, claimId)
        val claimSource = command.aggregateSource(claimKey)
        val claimOwner = command.claim(LocalFoodFinancialProfileV1.source(claimKey.kind, claimId))
        val claims = if (claimSource == null) {
            check(claimOwner == null)
            null
        } else {
            val row = claimSource.row
            check(claimOwner == claimId && row.key == claimKey && row.revision.value > 0 &&
                row.digest == sha256(row.payload.copyBytes()))
            when (val original = claimSource.original) {
                is RetainedOriginalV2.Streamed -> {
                    if (row.schema != foodStockSchema(claimKey.kind)) return null
                    // Normalized CLAIM_INDEX keeps its actual scalar/list body, not a frame root.
                    val value = foodFinancialEnvelope(row.payload)
                    requireStreamed(original, value.operationId)
                    requireClaimIndex(value, row, ownerId)
                    FoodFinancialRow(row, value, original.evidence)
                }
                is RetainedOriginalV2.Legacy -> {
                    val retained = original.evidence
                    if (retained.command != LocalFoodFinancialProfileV1.COMMAND &&
                        !(retained.command == LocalFoodProfileV1.COMMAND && isPaidFoodHandoffProducer(retained))) return null
                    val value = when (row.schema) {
                        LocalFoodFinancialProfileV1.schema(claimKey.kind) -> foodFinancialEnvelope(row.payload)
                        foodFinancialSchemaV2(claimKey.kind) -> {
                            val manifest = if (isPaidFoodHandoffProducer(retained)) paidFoodHandoffEvent(retained).framing
                                else foodFinancialStoredEvent(retained).framing ?: return null
                            requireNativeChunks(claimSource, manifest)
                            FoodFinancialBundleReaderV2(retained, manifest, allowStockInputs = true).document(row.payload)
                        }
                        else -> return null
                    }
                    check(value.operationId == retained.operationId)
                    requireClaimIndex(value, row, ownerId)
                    if (!requireSimpleLegacyRead(retained)) return null
                    FoodFinancialRow(row, value, retained)
                }
            }
        }
        return FoodClosingWork.Observed(index, claims)
    }

    private fun requireStreamed(original: RetainedOriginalV2.Streamed, operationId: OperationId) {
        check(original.evidence.identity.scope == command.scope &&
            original.evidence.identity.producer.operationId == operationId &&
            original.evidence.identity.producer.command == StockStreamingCommandsV2.FOOD)
    }

    private fun requireClaimIndex(value: FoodFinancialEnvelopeV1, row: StoredAggregate, ownerId: DomainId) {
        val body = value.body as FoodFinancialBodyV1.ClaimIndex
        check(value.scope == command.scope && value.kind == row.key.kind && value.id == row.key.id &&
            value.revision == row.revision && body.target == FoodClaimIndexTargetV1.OWNER && body.targetId == ownerId)
    }

    private suspend fun requireNativeChunks(source: RuntimeHistoricalAggregateSource, manifest: FoodFinancialManifestV2) {
        val original = (source.original as RetainedOriginalV2.Legacy).evidence
        for (descriptor in manifest.chunks) {
            val key = AggregateKey(LocalFoodFinancialProfileV1.FRAME, descriptor.id)
            val chunk = command.aggregateSource(key) ?: error("cash.history.food_frame_missing")
            val write = original.aggregateWrites.single { it.key == key }
            check(chunk.original === source.original && chunk.row.revision == Revision(1) &&
                chunk.row.schema == foodFinancialSchemaV2(key.kind) && chunk.row.payload.size == descriptor.byteCount &&
                chunk.row.digest == descriptor.digest && chunk.row.revision == write.storageGeneration &&
                chunk.row.schema == write.schema && chunk.row.payload == write.payload && chunk.row.digest == write.payloadDigest)
        }
    }

    /** Only the finite direct-input envelope already used by current foodRead is projected.
     * Its actual count/byte predicates may prove capacity. Exceptional resolving-v1-offer
     * walks and payment/refund custody cannot be inferred from an index, so remain held.
     * For this simple envelope, two roots + at most two charges per bounded predecessor fit
     * the unchanged request byte/call/row/producer limits; no large traversal is recreated. */
    private suspend fun requireSimpleLegacyRead(retained: RetainedCommandEvidence): Boolean {
        if (!simpleWhole(retained)) return false
        if (!checkedDirect.add(retained.operationId)) return true
        val paidHandoff = retained.command == LocalFoodProfileV1.COMMAND && isPaidFoodHandoffProducer(retained)
        val financialV2 = retained.command == LocalFoodFinancialProfileV1.COMMAND &&
            foodFinancialStoredEvent(retained).framing?.version == 2
        val maximum = if (paidHandoff || financialV2) FOOD_FINANCIAL_MAX_INPUTS_V2 else FoodReadLimits.PREDECESSOR_PRODUCERS
        val inputs = when (retained.command) {
            LocalFoodProfileV1.COMMAND -> if (paidHandoff) paidFoodHandoffInputs(retained)
                else if (isFoodHandoffProducerV2(retained)) foodHandoffInputsV2(retained)
                else foodEvent(retained).beforeIds.map { id ->
                    val before = foodBefore(retained, id).envelope
                    val bytes = foodEnvelopeBytes(before)
                    FoodFinancialInputV1(before.kind, before.id, before.revision, LocalFoodProfileV1.schema(before.kind),
                        sha256(bytes.copyBytes()), before.operationId, bytes.copyBytes().decodeToString(throwOnInvalidSequence = true))
                }
            LocalFoodFinancialProfileV1.COMMAND -> foodFinancialInputs(retained)
            else -> return false
        }
        val legacy = mutableMapOf<OperationId, RetainedCommandEvidence>()
        val normalized = mutableMapOf<OperationId, RetainedStreamedCommand>()
        var bytes = 0L
        for (input in inputs) {
            if (input.schema == foodStockSchema(input.kind)) {
                if (!paidHandoff) return false
                val original = normalized[input.operationId] ?: run {
                    foodCapacity(legacy.size + normalized.size < maximum)
                    val source = command.earlierOriginal(input.operationId) as? RetainedOriginalV2.Streamed ?: return false
                    requireStreamed(source, input.operationId)
                    check(source.evidence.identity.producer.operationId != retained.operationId &&
                        (source.evidence.identity.assignmentId != retained.assignmentId ||
                            source.evidence.identity.authorityEpoch != retained.authorityEpoch ||
                            source.evidence.identity.sequence < retained.sequence))
                    normalized[input.operationId] = source.evidence
                    source.evidence
                }
                requirePaidFoodStreamedInput(original, input)
                continue
            }
            val prior = legacy[input.operationId] ?: run {
                foodCapacity(legacy.size + normalized.size < maximum)
                val original = (command.earlierOriginal(input.operationId) as? RetainedOriginalV2.Legacy)?.evidence ?: return false
                check(original.committed && original.scope == command.scope && original.operationId == input.operationId &&
                    original.operationId != retained.operationId &&
                    (original.assignmentId != retained.assignmentId || original.authorityEpoch != retained.authorityEpoch ||
                        original.sequence < retained.sequence))
                if (input.stock == null && !simpleWhole(original)) return false
                bytes += foodProducerReadBytes(original)
                foodCapacity(bytes <= FoodReadLimits.PREDECESSOR_BYTES)
                legacy[input.operationId] = original
                original
            }
            if (input.stock == null && !simpleWhole(prior)) return false
            val write = prior.aggregateWrites.single { it.key == input.key() }
            check(write.storageGeneration == input.revision && write.schema == input.schema && write.payload == input.bytes() &&
                write.payloadDigest == input.digest && sha256(write.payload.copyBytes()) == input.digest)
            if (input.stock != null) requireFoodStockInput(input, prior)
        }
        return true
    }

    private fun simpleWhole(retained: RetainedCommandEvidence): Boolean {
        check(retained.committed && retained.scope == command.scope && retained.nativeOriginalProvenance == null &&
            foodProducerReadBytes(retained) <= FoodReadLimits.FOOD_PRODUCER_BYTES)
        return when (retained.command) {
            LocalFoodProfileV1.COMMAND -> foodHandoffLegacyOriginInputV2(retained) == null
            LocalFoodFinancialProfileV1.COMMAND -> true
            else -> false
        }
    }
}
