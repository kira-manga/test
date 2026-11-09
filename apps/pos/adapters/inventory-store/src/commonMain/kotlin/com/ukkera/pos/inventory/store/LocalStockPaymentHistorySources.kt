package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.StockReadObservations
import com.ukkera.pos.database.StockRelationalKindsV2
import com.ukkera.pos.database.StreamedSourceObservationV2

/** Validation only, on the core's real active startup scan. No original/record factory,
 * producer callback, current-view reconstruction or operation-id-only approval is exposed.
 * Every literal normalized Current, including nonindexed activation/item rows, must name
 * the exact already fully joined streamed producer of its complete native BEFORE image. */
public object LocalStockPaymentHistorySources {
    public suspend fun requireOriginals(command: RuntimeHistoricalCommand, observed: StockReadObservations) {
        val owner = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailableProducer()
        // Re-select the genuine kernel-retained observations through this active command.
        // Equal caller-authored subsets or relaxed absent-row evidence cannot fit here.
        val actual = command.requireLegacyReadEvidence() ?: inconsistentStock()
        localStockCheck(owner.committed && owner.scope == command.scope && observed.scope == command.scope &&
            observed.scope == actual.scope && observed.sources == actual.sources &&
            observed.sources.none { it is StreamedSourceObservationV2.StockItemAbsent }, LocalStockFailure.STORAGE_INCONSISTENT)
        var present = false
        for (source in observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>()) {
            if (source.key.kind !in StockRelationalKindsV2.all) continue
            present = true
            val row = LocalStockRelationalCodecV2.decode(command.scope, source.key, source.generation,
                source.schema, source.payload, source.payloadHash)
            val paired = command.aggregateSource(source.key) ?: inconsistentStock()
            localStockCheck(paired.row.key == row.key && paired.row.revision == row.generation &&
                paired.row.schema == row.schema && paired.row.payload == row.payload && paired.row.digest == row.digest,
                LocalStockFailure.STORAGE_INCONSISTENT)
            val original = paired.original as? RetainedOriginalV2.Streamed ?: unavailableProducer()
            val earlier = command.earlierOriginal(row.producer.operationId) as? RetainedOriginalV2.Streamed
                ?: unavailableProducer()
            val identity = original.evidence.identity
            localStockCheck(original === earlier && identity.scope == owner.scope && identity.assignmentId == owner.assignmentId &&
                identity.authorityEpoch == owner.authorityEpoch && identity.sequence < owner.sequence &&
                identity.producer.operationId != owner.operationId && identity.producer == row.producer,
                LocalStockFailure.STORAGE_INCONSISTENT)
            // Core selected this exact original only after its complete business join; the
            // repository traversal is not reopened or replaced with an accepting resolver.
            requireOriginalWrite(original.evidence, row.key, row.generation, row.schema, row.payload, row.digest)
        }
        localStockCheck(present, LocalStockFailure.STORAGE_INCONSISTENT)
    }
}
