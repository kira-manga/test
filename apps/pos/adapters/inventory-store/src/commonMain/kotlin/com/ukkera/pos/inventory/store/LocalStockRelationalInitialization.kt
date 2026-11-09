package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

@Serializable internal data class StockFreshRequestV2Wire(val action: Int, val operationId: OperationId,
    val initializationId: DomainId, val reason: String) {
    fun restore(): LocalStockRelationalCommand.InitializeFresh {
        localStockCheck(action == 3); localStockReason(reason)
        return LocalStockRelationalCommand.InitializeFresh(operationId, initializationId, reason)
    }
}
@Serializable internal data class StockFreshResultV2Wire(val format: Int, val initializationId: DomainId, val mode: String)
/** Projection of the existing retained initialization result; no new wire or write semantics. */
public data class LocalStockInitializationResult(public val initializationId: DomainId, public val freshEmpty: Boolean)
internal const val STOCK_INITIALIZE_EFFECT_V2: String = "local.stock.v2.initialize"

/** Real positive empty genesis. Its input has no stock/cost/configuration, counts, key list,
 * migration flag or proof token. The authorized owner generates ALL estate observations on the
 * same borrowed transaction and re-enumerates them before the first business write. */
internal suspend fun initializeFreshStock(repository: LocalStockRepository, view: CommandReadView,
    context: CommandContext, sink: StreamedCommandSink, now: UtcInstant,
    request: LocalStockRelationalCommand.InitializeFresh): StreamedMutationDecision {
    localStockCheck(context.operationId == request.operationId && context.key == LocalStockRelationalProfileV2.COMMAND)
    localStockReason(request.reasonCode)
    val read = repository.freshRelationalRead(view, context, sink)
    var priorStock = false
    val estate = sink.stockEstate(read.access) { row -> when (row) {
        is StockEstateRecordV2.Aggregate -> { if (stockEstateKind(row.key.kind)) priorStock = true }
        is StockEstateRecordV2.Claim -> { if (stockEstateClaim(row.key)) priorStock = true }
        is StockEstateRecordV2.Original -> {
            val actual = read.access.originals.read(row.operationId) ?: unavailableProducer()
            requireEstateOriginalIdentity(row, actual)
            val at = when (actual) { is RetainedOriginalV2.Legacy -> actual.evidence.recordedAt; is RetainedOriginalV2.Streamed -> actual.evidence.identity.recordedAt }
            localStockCheck(row.operationId != context.operationId && at <= now, LocalStockFailure.PRODUCER_UNAVAILABLE)
            // Entire mixed v1/v2 original, INCLUDING all commerce/unpriced stock effects.
            // Do not short circuit later originals after the first positive stock member.
            if (stockEstateHistory(actual)) priorStock = true
        }
    } }
    if (priorStock) return StreamedMutationDecision.Reject("stock-migration-required",
        stockEncode(StockFreshResultV2Wire(2, request.initializationId, "MIGRATION_REQUIRED"), StockFreshResultV2Wire.serializer()))
    val key = LocalStockRelationalProfileV2.activationKey(context.scope.branchId)
    val postingKey = AggregateKey(StockRelationalKindsV2.POSTING, DomainId(context.operationId.value))
    // These negative lookups are corroborated by the owner's complete literal estate terminal.
    localStockCheck(read.access.aggregate(key) == null && read.access.aggregate(postingKey) == null)
    val marker = StockRelationalBody.Activation(request.initializationId, 0, 2, estate.commitment,
        STOCK_FRESH_PROFILE_V2, "local.stock.relational/v2")
    val change = LocalStockRelationalCodecV2.change(context, key, null, marker)
    sink.change(change)
    sink.effect(StreamedEffectV2(LocalStockRelationalProfileV2.EFFECT_KIND, rowEffectId(key), stockEncode(
        StockRowEffectV2Wire(key.kind, key.id, null, null, sha256(change.payload.copyBytes()).value), StockRowEffectV2Wire.serializer())))
    sink.effect(StreamedEffectV2(STOCK_INITIALIZE_EFFECT_V2, request.initializationId, LocalStockRelationalCommandsV2.encode(request)))
    val result = stockEncode(StockFreshResultV2Wire(2, request.initializationId, "FRESH_EMPTY"), StockFreshResultV2Wire.serializer())
    val sources = sink.sectionSummary(StreamedSectionV2.SOURCES)
    val effects = sink.sectionSummary(StreamedSectionV2.EFFECTS)
    val posting = StockRelationalBody.Posting(sources.count, sources.bytes, sources.commitment,
        effects.count, effects.bytes, effects.commitment, sha256(result.copyBytes()))
    sink.change(LocalStockRelationalCodecV2.change(context, postingKey, null, posting))
    read.resources(key, marker); read.resources(postingKey, posting)
    sink.resource(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.initializationId))
    sink.finishInputs(result)
    return StreamedMutationDecision.Apply(result)
}

/** Conservative ownership REJECTION, never permission by prefix. The complete owner enumeration
 * is not filtered by these strings: every current row, claim and original is read to EOF. */
internal fun stockEstateKind(kind: String): Boolean = kind.startsWith("local.stock") ||
    kind.startsWith("inventory") || kind.startsWith("stock") || kind.startsWith("fixture.inventory") ||
    // A surviving commerce owner/right with missing stock originals is NOT evidence of a
    // fresh estate. Be conservative: even previously stock-free commerce uses migration's
    // full original join, rather than parsing an embedded reference as a completeness flag.
    kind.startsWith("sales.food") || kind.startsWith("sales.order") || kind.startsWith("sales.collection") ||
    kind.startsWith("sales.return") || kind.startsWith("sales.paid_sale")
internal fun stockEstateClaim(key: ClaimKey): Boolean = stockEstateKind(key.namespace) ||
    key.namespace == "sales.unique" && stockEstateKind(key.key.substringBefore('/'))
private fun stockEstateCommand(key: CommandKey): Boolean = key in StockStreamingCommandsV2.all ||
    stockEstateKind(key.name) || key.name.startsWith("fixture.inventory.") ||
    key.name == "purchasing.receipt.post" || key.name == "purchasing.inspection.release"

private fun requireEstateOriginalIdentity(ref: StockEstateRecordV2.Original, original: RetainedOriginalV2) {
    localStockCheck(when (original) {
        is RetainedOriginalV2.Legacy -> ref.format == 1 && ref.operationId == original.evidence.operationId && ref.journalHash == original.evidence.journalHash
        is RetainedOriginalV2.Streamed -> ref.format == 2 && ref.operationId == original.evidence.identity.producer.operationId && ref.journalHash == original.evidence.journalHash
    })
}

private suspend fun stockEstateHistory(original: RetainedOriginalV2): Boolean = when (original) {
    is RetainedOriginalV2.Legacy -> {
        val old = original.evidence
        var found = stockEstateCommand(old.command)
        for (write in old.aggregateWrites) if (stockEstateKind(write.key.kind)) found = true
        for (claim in old.claimChanges) if (stockEstateClaim(claim.key)) found = true
        found
    }
    is RetainedOriginalV2.Streamed -> {
        val old = original.evidence
        var found = stockEstateCommand(old.identity.producer.command)
        old.frames { frame -> when (frame) {
            is RetainedCommandFrameV2.Write -> if (stockEstateKind(frame.value.key.kind)) found = true
            is RetainedCommandFrameV2.Claim -> if (stockEstateClaim(frame.value.key)) found = true
            is RetainedCommandFrameV2.Effect -> if (stockEstateKind(frame.value.kind)) found = true
            is RetainedCommandFrameV2.Source -> when (val source = frame.value) {
                is StreamedSourceObservationV2.Aggregate -> if (stockEstateKind(source.key.kind)) found = true
                is StreamedSourceObservationV2.Current -> if (stockEstateKind(source.key.kind)) found = true
                is StreamedSourceObservationV2.CurrentAbsent -> if (stockEstateKind(source.key.kind)) found = true
                is StreamedSourceObservationV2.StockItemAbsent -> inconsistentStock()
                is StreamedSourceObservationV2.Claim -> if (stockEstateClaim(source.key)) found = true
                is StreamedSourceObservationV2.StockRange, is StreamedSourceObservationV2.StockMember,
                is StreamedSourceObservationV2.EstateEnd, is StreamedSourceObservationV2.EstateMember -> found = true
            }
            else -> Unit
        } }
        found
    }
}

/** Accepts ONLY a genuine owner-retained FRESH_EMPTY transition. This is business proof, not
 * native/schema/all-writer activation. Losing any full historical original makes it unavailable. */
internal suspend fun requireFreshStockProducer(original: RetainedStreamedCommand,
    stock: LocalStockRelationalInspection, request: LocalStockRelationalCommand.InitializeFresh) {
    localStockCheck(original.identity.producer.command == LocalStockRelationalProfileV2.COMMAND &&
        request.operationId == stock.operationId && LocalStockRelationalCommandsV2.encode(request) == original.normalized)
    val estate = original.stockEstate { row -> when (row) {
        is StockEstateRecordV2.Aggregate -> localStockCheck(!stockEstateKind(row.key.kind), LocalStockFailure.PRODUCER_UNAVAILABLE)
        is StockEstateRecordV2.Claim -> localStockCheck(!stockEstateClaim(row.key), LocalStockFailure.PRODUCER_UNAVAILABLE)
        is StockEstateRecordV2.Original -> {
            val actual = original.original(row.operationId) ?: unavailableProducer()
            requireEstateOriginalIdentity(row, actual)
            val before = when (actual) {
                is RetainedOriginalV2.Legacy -> with(actual.evidence) { scope == stock.scope && recordedAt <= stock.recordedAt &&
                    (assignmentId != original.identity.assignmentId || authorityEpoch != original.identity.authorityEpoch || sequence < original.identity.sequence) }
                is RetainedOriginalV2.Streamed -> with(actual.evidence.identity) { scope == stock.scope && recordedAt <= stock.recordedAt &&
                    (assignmentId != original.identity.assignmentId || authorityEpoch != original.identity.authorityEpoch || sequence < original.identity.sequence) }
            }
            localStockCheck(before && row.operationId != stock.operationId && !stockEstateHistory(actual), LocalStockFailure.PRODUCER_UNAVAILABLE)
        }
    } }
    val key = LocalStockRelationalProfileV2.activationKey(stock.scope.branchId)
    val row = stock.write(key) ?: inconsistentStock()
    localStockCheck(row.body == StockRelationalBody.Activation(request.initializationId, 0, 2, estate.commitment,
        STOCK_FRESH_PROFILE_V2, "local.stock.relational/v2") && row.generation == Revision(1) && row.previous == null && row.producer == original.identity.producer)
    localStockCheck(stockDecode(original.result, StockFreshResultV2Wire.serializer()) == StockFreshResultV2Wire(2, request.initializationId, "FRESH_EMPTY"))
    var writes = 0L; var effects = 0L
    original.frames { frame -> when (frame) {
        is RetainedCommandFrameV2.Write -> {
            val expected = frame.value.key == key || frame.value.key == AggregateKey(StockRelationalKindsV2.POSTING, DomainId(stock.operationId.value))
            localStockCheck(expected && frame.value.storageGeneration == Revision(1)); writes = stockCheckedAdd(writes, 1)
        }
        is RetainedCommandFrameV2.Claim, is RetainedCommandFrameV2.Job -> inconsistentStock()
        is RetainedCommandFrameV2.Effect -> {
            when (frame.value.kind) {
                LocalStockRelationalProfileV2.EFFECT_KIND -> localStockCheck(frame.value.id == rowEffectId(key) &&
                    stockDecode(frame.value.payload, StockRowEffectV2Wire.serializer()) == StockRowEffectV2Wire(key.kind, key.id, null, null, row.digest.value))
                STOCK_INITIALIZE_EFFECT_V2 -> localStockCheck(frame.value.id == request.initializationId && frame.value.payload == original.normalized)
                else -> inconsistentStock()
            }
            effects = stockCheckedAdd(effects, 1)
        }
        is RetainedCommandFrameV2.Source -> localStockCheck(frame.value is StreamedSourceObservationV2.EstateMember || frame.value is StreamedSourceObservationV2.EstateEnd)
        else -> Unit
    } }
    localStockCheck(writes == 2L && effects == 2L)
    stock.requireResources(setOf(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.initializationId)))
}
