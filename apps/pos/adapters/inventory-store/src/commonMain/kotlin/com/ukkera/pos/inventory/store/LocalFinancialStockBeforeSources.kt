package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*

/** Original-only financial consumer on the actual active native history. The owner is an
 * already completely joined genuine earlier original; equal caller-authored evidence or
 * an operation-id assertion cannot replace it. No current row/grant, accepting resolver,
 * fabricated record or new native retention capability exists in this adapter. */
public object LocalFinancialStockBeforeSources {
    private suspend fun owner(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        observed: StockReadObservations) {
        val actual = command.earlierOriginal(original.operationId) as? RetainedOriginalV2.Legacy ?: unavailableProducer()
        localStockCheck(actual.evidence === original && original.committed && original.scope == command.scope &&
            StockReadEvidenceV1.read(original) == observed && observed.scope == original.scope &&
            observed.sources.none { it is StreamedSourceObservationV2.StockItemAbsent })
    }
    public suspend fun requireInputs(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        observed: StockReadObservations) {
        owner(command, original, observed)
        val records = observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>()
            .filter { it.key.kind in StockRelationalKindsV2.all }.map {
                LocalStockRelationalCodecV2.decode(observed.scope, it.key, it.generation, it.schema, it.payload, it.payloadHash)
            }
        localStockCheck(records.map { it.key }.distinct().size == records.size)
        for ((id, rows) in records.groupBy { it.producer.operationId }) {
            val actual = (command.earlierOriginal(id) as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
            localStockCheck(actual.identity.scope == original.scope && id != original.operationId &&
                (actual.identity.assignmentId != original.assignmentId || actual.identity.authorityEpoch != original.authorityEpoch ||
                    actual.identity.sequence < original.sequence))
            val selected = rows.associateBy { it.key }; val found = mutableSetOf<AggregateKey>()
            actual.frames { frame -> if (frame is RetainedCommandFrameV2.Write) selected[frame.value.key]?.let { row ->
                localStockCheck(found.add(row.key) && row.producer == actual.identity.producer)
                requireSameWrite(frame.value, row.key, row.generation, row.schema, row.payload, row.digest)
            } }
            localStockCheck(found == selected.keys)
        }
    }
    public suspend fun requireIssued(command: RuntimeHistoricalCommand, original: RetainedCommandEvidence,
        observed: StockReadObservations, issued: LocalStockPaymentIssuedCut): LocalStockIssueOriginal {
        owner(command, original, observed)
        val source = observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>().single {
            it.key == issued.issueRecord.key }
        localStockCheck(source.generation == issued.issueRecord.generation && source.schema == issued.issueRecord.schema &&
            source.payload == issued.issueRecord.payload && source.payloadHash == issued.issueRecord.digest)
        val first = (command.earlierOriginal(issued.firstIssueProducer.operationId) as? RetainedOriginalV2.Streamed)?.evidence
            ?: unavailableProducer()
        localStockCheck(first.identity.scope == original.scope && first.identity.producer == issued.firstIssueProducer)
        return stockIssueOriginal(first, issued.issue.id, issued.issue)
    }
}
