package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAggregateSource
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentState
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision

internal val foodCurrentFinancialKinds: Set<String> = setOf(LocalFoodFinancialProfileV1.COLLECTION,
    LocalFoodFinancialProfileV1.SALE, LocalFoodFinancialProfileV1.ENTITLEMENT,
    LocalFoodFinancialProfileV1.REFUND, LocalFoodFinancialProfileV1.REFUND_INDEX)

/** Actual current financial documents, from their already joined original writers. All
 * legacy CASH bundle chunks are additionally compared to the complete native census.
 * No original command reader, current SQL, session, cash outcome or stock grant is created. */
internal suspend fun foodCurrentFinancialHistory(state: RuntimeHistoricalFoodCurrentState,
    sources: List<RuntimeHistoricalAggregateSource>): Map<AggregateKey, FoodFinancialEnvelopeV1> {
    val byKey = sources.associateBy { it.row.key }
    check(byKey.size == sources.size)
    val result = linkedMapOf<AggregateKey, FoodFinancialEnvelopeV1>()
    val checkedBundles = mutableSetOf<com.ukkera.pos.types.OperationId>()
    fun legacyManifest(source: RuntimeHistoricalAggregateSource): FoodFinancialManifestV2 {
        val original = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        check(original.committed && original.scope == state.scope && original.nativeOriginalProvenance == null)
        return when {
            isFoodPaymentFinancialProducer(original) -> foodPaymentStoredEvent(original).framing
            isFoodRefundFinancialProducer(original) -> foodRefundStoredEvent(original).framing
            else -> unavailable()
        }
    }
    fun requireLegacyChunks(source: RuntimeHistoricalAggregateSource, manifest: FoodFinancialManifestV2) {
        val original = (source.original as RetainedOriginalV2.Legacy).evidence
        if (!checkedBundles.add(original.operationId)) return
        val keys = manifest.chunks.map { AggregateKey(LocalFoodFinancialProfileV1.FRAME, it.id) }
        check(keys.distinct().size == keys.size &&
            sources.filter { it.original === source.original && it.row.key.kind == LocalFoodFinancialProfileV1.FRAME }
                .map { it.row.key }.toSet() == keys.toSet())
        for (descriptor in manifest.chunks) {
            val key = AggregateKey(LocalFoodFinancialProfileV1.FRAME, descriptor.id)
            val native = byKey[key] ?: unavailable()
            val row = native.row
            val write = original.aggregateWrites.single { it.key == key }
            check(native.original === source.original && row.revision == Revision(1) &&
                row.schema == foodFinancialSchemaV2(key.kind) && row.payload.size == descriptor.byteCount &&
                row.digest == descriptor.digest && row.revision == write.storageGeneration && row.schema == write.schema &&
                row.payload == write.payload && row.digest == write.payloadDigest)
        }
    }
    for (source in sources) {
        val row = source.row
        if (row.key.kind == LocalFoodFinancialProfileV1.FRAME) {
            // Earlier immutable frames remain present even when a later command replaced
            // their document root. Their whole native original already authenticated their
            // exact purpose and complete AFTER membership; retaining them is not erasure.
            check(row.revision == Revision(1) && row.digest == sha256(row.payload.copyBytes()))
            when (val original = source.original) {
                is RetainedOriginalV2.Streamed -> check(row.schema == foodStockSchema(row.key.kind) &&
                    original.evidence.identity.scope == state.scope && original.evidence.identity.producer.command == StockStreamingCommandsV2.FOOD)
                is RetainedOriginalV2.Legacy -> requireLegacyChunks(source, legacyManifest(source))
            }
        } else if (row.key.kind in foodCurrentFinancialKinds) {
            val value = when (val original = source.original) {
                is RetainedOriginalV2.Streamed -> {
                    check(row.schema == foodStockSchema(row.key.kind) && original.evidence.identity.producer.command == StockStreamingCommandsV2.FOOD)
                    if (row.key.kind == LocalFoodFinancialProfileV1.REFUND_INDEX) foodFinancialEnvelope(row.payload)
                    else foodStockReadCurrentDocument(state, source)
                }
                is RetainedOriginalV2.Legacy -> {
                    check(row.schema == foodFinancialSchemaV2(row.key.kind) &&
                        (row.key.kind == LocalFoodFinancialProfileV1.COLLECTION && isFoodPaymentFinancialProducer(original.evidence) ||
                            row.key.kind == LocalFoodFinancialProfileV1.REFUND && isFoodRefundFinancialProducer(original.evidence)))
                    val manifest = legacyManifest(source)
                    requireLegacyChunks(source, manifest)
                    // This bounded legacy original contains literal retained writes; reading
                    // those immutable bytes does not reopen a streamed/native capability.
                    FoodFinancialBundleReaderV2(original.evidence, manifest, allowStockInputs = true).document(row.payload)
                }
            }
            val operationId = when (val original = source.original) {
                is RetainedOriginalV2.Streamed -> original.evidence.identity.producer.operationId
                is RetainedOriginalV2.Legacy -> original.evidence.operationId
            }
            check(value.scope == state.scope && value.kind == row.key.kind && value.id == row.key.id &&
                value.revision == row.revision && value.operationId == operationId && result.put(row.key, value) == null)
        }
    }
    return result
}

private fun unavailable(): Nothing = error("food.current_history.financial_source_unavailable")
