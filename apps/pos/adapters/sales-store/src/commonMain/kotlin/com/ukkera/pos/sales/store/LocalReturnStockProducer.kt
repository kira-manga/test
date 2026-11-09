package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Closed literal accepted-source, independent consumption and stock action proof. */
public class LocalReturnStockProducer : LocalStockRelationalCompositeVerifier {
    override val commands: Set<CommandKey> = setOf(LocalReturnStockProfileV1.COMMAND)
    override suspend fun requireComplete(original: RetainedStreamedCommand, stock: LocalStockRelationalInspection) {
        check(original.identity.producer.command in commands && LocalReturnStockCodecV1.normalize(original.normalized) == original.normalized)
        requireNoStockActions(stock, retailReturn = true)
        val request = LocalReturnStockCodecV1.decode(original.normalized)
        val proof = CommerceProof(original); proof.load()
        val caseKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, request.caseId)
        val caseRow = checkNotNull(proof.sources.aggregateOriginal(caseKey))
        val state = localSalesDecode(caseRow.payload, LocalReturnCaseStateV1.serializer())
        validateReturnCaseState(state)
        check(caseRow.schema == LocalSalesStoreProfile.schema(caseKey.kind) && state.scope.value() == stock.scope &&
            state.caseId == request.caseId && state.revision == SalesStorageRevision.toDomain(caseRow.generation) &&
            state.revision == request.expectedCaseRevision && state.stage == LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING &&
            state.acceptedReturnId == request.returnId)
        val eventKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(state.lastOperationId.value))
        val eventRow = checkNotNull(proof.sources.aggregateOriginal(eventKey, state.lastOperationId))
        val event = localSalesDecode(eventRow.payload, LocalReturnCaseEventV1.serializer())
        check(eventRow.schema == LocalSalesStoreProfile.schema(eventKey.kind) && eventRow.generation == Revision(1) &&
            event.after == state && event.operationId == state.lastOperationId && event.action == LocalReturnCaseActionV1.ACCEPTED)
        val acceptance = (stock.original(state.lastOperationId) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("sales.return.stock.accepted_original_missing")
        requireReturnStockLiteral(acceptance, caseRow)
        requireReturnStockLiteral(acceptance, eventRow)
        check(returnStockSourceIdentity(original, caseKey) == StreamedProducerIdentityV2(acceptance.operationId,
            acceptance.command, acceptance.originalActorId, acceptance.semanticDigest))
        val saleKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, request.saleId)
        val saleRow = checkNotNull(proof.sources.aggregateOriginal(saleKey))
        check(saleRow.schema == LocalSalesStoreProfile.schema(saleKey.kind) && saleRow.generation == Revision(1))
        val sale = SalesSnapshotCodec.decodePaidSale(saleRow.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
            SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, stock.scope, request.saleId, Revision(0)))
        val accepted = returnStockAcceptedOriginal(stock.scope, sale, request.caseId, request.returnId, acceptance).accepted
        check(accepted.acceptingOperatorId == state.ownerId && sale.stockIssueId == request.issueId)
        // Genuine first issue belongs to the SAME complete paid Finalize, not merely a matching ID.
        val first = checkNotNull(stock.issueOriginal(request.issueId))
        val saleIdentity = returnStockSourceIdentity(original, saleKey)
        val saleOriginal = (stock.original(saleIdentity.operationId) as? RetainedOriginalV2.Streamed)?.evidence
            ?: error("sales.return.stock.sale_original_missing")
        stock.requireCompleteOriginal(saleOriginal)
        check(saleOriginal.identity.producer == saleIdentity && saleIdentity.command == StockStreamingCommandsV2.COLLECTION &&
            first.original.identity == saleOriginal.identity && first.original.journalHash == saleOriginal.journalHash &&
            LocalCollectionStockCodecV2.normalize(saleOriginal.normalized) == saleOriginal.normalized)
        val finalize = LocalCollectionStockCodecV2.decode(saleOriginal.normalized) as? LocalCollectionStockRequestV2.Finalize
            ?: error("sales.return.stock.paid_finalize_required")
        check(finalize.saleId == sale.id && finalize.orderId == sale.orderId && finalize.issueId == sale.stockIssueId)
        requireReturnStockLiteral(saleOriginal, saleRow)
        val issue = checkNotNull(stock.beforeIssue(request.issueId))
        check(issue.revision == request.expectedIssueRevision && issue.purpose == ValueIssuePurpose.RETAIL &&
            issue.binding.orderId == sale.orderId && issue.binding.quoteRevision == sale.quote.revision &&
            issue.binding.originalActorId == sale.collectorId)
        val claims = listOf(
            LocalReturnCaseProfileV1.source("case", request.caseId) to state.ownerId,
            LocalReturnCaseProfileV1.source("claim", request.caseId) to request.caseId,
            LocalSalesStoreProfile.unique("return_claim", request.caseId) to sale.id,
            LocalSalesStoreProfile.unique("accepted_return", request.returnId) to sale.id,
            LocalSalesStoreProfile.unique("return_claim_source", request.returnId) to request.caseId,
            LocalSalesStoreProfile.unique("claim_acceptance", request.caseId) to request.returnId,
            LocalReturnCaseProfileV1.source("event", DomainId(acceptance.operationId.value)) to request.caseId,
            LocalSalesStoreProfile.unique("sale", sale.id) to sale.orderId,
            LocalSalesStoreProfile.unique("sale_order", sale.orderId) to sale.id,
            LocalSalesStoreProfile.unique("return_entitlement", sale.id) to sale.id,
        )
        for ((key, owner) in claims) check(proof.sources.claim(key) == owner)
        val stateKey = LocalReturnStockProfileV1.stateKey(request.returnId)
        val previousRow = proof.sources.aggregateOriginal(stateKey)
        val previous = previousRow?.let {
            check(it.schema == returnStockSchema(stateKey.kind))
            localSalesDecode(it.payload, LocalReturnStockStateV1.serializer(), 8_192).also { before ->
                check(before.revision == it.generation && before.lastOperationId == returnStockSourceIdentity(original, stateKey).operationId)
            }
        }
        check(proof.sources.claim(LocalReturnStockProfileV1.stateClaim(request.returnId)) == previous?.let { request.returnId })
        val postingKey = LocalReturnStockProfileV1.postingKey(request.restorationId)
        check(proof.sources.aggregate(postingKey) == null && proof.sources.claim(LocalReturnStockProfileV1.postingClaim(request.restorationId)) == null)
        val producer = original.identity.producer
        val posting = returnStockPostingValue(stock.scope, producer.operationId, producer.semanticDigest, producer.originalActorId,
            original.identity.recordedAt, request, accepted, acceptance.operationId, acceptance.semanticDigest, previous, previousRow?.digest)
        val expected = returnStockInventoryRequest(posting)
        var count = 0
        stock.retailReturnRestorationIntents { actual ->
            check(count++ == 0 && actual.restorationId == expected.restorationId && actual.acceptedReturnId == expected.acceptedReturnId &&
                actual.saleId == expected.saleId && actual.portionId == expected.portionId && actual.issueId == expected.issueId &&
                actual.expectedIssueRevision == expected.expectedIssueRevision && actual.quantityToRestore == expected.quantityToRestore &&
                actual.selections == expected.selections)
        }
        check(count == 1)
        proof.account(stateKey, localSalesEncode(posting.after, LocalReturnStockStateV1.serializer(), 8_192),
            returnStockSchema(stateKey.kind), previousRow, true)
        proof.account(postingKey, localSalesEncode(posting, LocalReturnStockPostingV1.serializer(), 16_384),
            returnStockSchema(postingKey.kind), null)
        proof.finish(LocalReturnStockResultCodecV1.encode(posting.result), buildList {
            if (previous == null) add(ClaimChange.Acquire(LocalReturnStockProfileV1.stateClaim(request.returnId), request.returnId))
            add(ClaimChange.Acquire(LocalReturnStockProfileV1.postingClaim(request.restorationId), request.returnId))
        })
        proof.requireSourceComplement(stock)
        stock.requireResources(returnStockAcceptanceResources(state, sale.id, accepted.id) + commerceRowResources(original) +
            RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, producer.originalActorId))
    }
    override suspend fun requireOriginalSource(original: RetainedOriginalV2, observation: StreamedSourceObservationV2.Aggregate) {
        // Legacy CASE needs the actual current loan below; a DTO alone is never qualification.
        check(original is RetainedOriginalV2.Streamed)
        check((observation.key.kind == LocalSalesStoreProfile.SALE_KIND && original.evidence.identity.producer.command == StockStreamingCommandsV2.COLLECTION) ||
            (observation.key.kind == LocalReturnStockProfileV1.STATE && original.evidence.identity.producer.command in commands))
    }
    override suspend fun requireOriginalSource(view: CommandReadView, original: RetainedOriginalV2,
        observation: StreamedSourceObservationV2.Aggregate) {
        if (original !is RetainedOriginalV2.Legacy) return requireOriginalSource(original, observation)
        check(original.evidence.command == LocalReturnCaseProfileV1.COMMAND &&
            observation.key.kind in setOf(LocalReturnCaseProfileV1.CASE, LocalReturnCaseProfileV1.EVENT))
        val event = readReturnCaseEvent(view, original.evidence.operationId, LocalReturnCaseDependencies())
        check(event.value.action == LocalReturnCaseActionV1.ACCEPTED)
        val actual = returnCaseEvidence(view, original.evidence.operationId, LocalReturnCaseDependencies())
        requireSameFoodPaymentOriginal(actual, original.evidence)
        val row = actual.aggregateWrites.single { it.key == observation.key }
        check(row.storageGeneration == observation.generation && row.schema == observation.schema &&
            row.payload == observation.payload && row.payloadDigest == observation.payloadHash)
    }
}

private suspend fun returnStockSourceIdentity(original: RetainedStreamedCommand, key: AggregateKey): StreamedProducerIdentityV2 {
    var result: StreamedProducerIdentityV2? = null
    original.frames { frame ->
        val source = (frame as? RetainedCommandFrameV2.Source)?.value as? StreamedSourceObservationV2.Aggregate
        if (source?.key == key) { check(result == null); result = source.producer }
    }
    return checkNotNull(result)
}
private fun requireReturnStockLiteral(original: RetainedCommandEvidence, row: LocalCommerceOriginalRow) {
    val write = original.aggregateWrites.single { it.key == row.key }
    check(write.storageGeneration == row.generation && write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest)
}
private suspend fun requireReturnStockLiteral(original: RetainedStreamedCommand, row: LocalCommerceOriginalRow) {
    var found = false
    original.frames { frame ->
        val write = (frame as? RetainedCommandFrameV2.Write)?.value
        if (write?.key == row.key) {
            check(!found && write.storageGeneration == row.generation && write.schema == row.schema && write.payload == row.payload &&
                write.payloadDigest == row.digest && sha256(row.payload.copyBytes()) == row.digest)
            found = true
        }
    }
    check(found)
}
