package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Revision

/** Existing normalized forms only; this does not install physical payment or reassignment. */
internal val lanCollectionActions: Set<LocalAction> = setOf(LocalAction.ORDER_OFFER_HANDOFF,
    LocalAction.ORDER_CANCEL_HANDOFF, LocalAction.ORDER_ACCEPT_HANDOFF,
    LocalAction.COLLECTION_PREPARE, LocalAction.SALE_FINALIZE)

internal fun requireLanCollectionIntent(intent: LocalCommandIntent) {
    if (intent.action !in lanCollectionActions || intent.formVersion != 2 || intent.arrivalEvidence != null ||
        intent.lineField != null || intent.lines.isNotEmpty() ||
        intent.reference?.let { it.parentId != null || it.kind !in setOf(LocalRecordKind.ORDER, LocalRecordKind.COLLECTION) } == true)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

internal fun lanCollectionRequest(action: LocalAction, request: MutationRequest): LocalCollectionStockRequestV2 {
    if (action !in lanCollectionActions || request.command != LocalCollectionStockCodecV2.COMMAND || request.actionApprovalId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return try {
        val selected = LocalCollectionStockCodecV2.decode(request.payload)
        require(LocalCollectionStockCodecV2.encode(selected) == request.payload)
        val expected = when (selected) {
            is LocalCollectionStockRequestV2.OfferHandoff -> LocalAction.ORDER_OFFER_HANDOFF
            is LocalCollectionStockRequestV2.CancelHandoff -> LocalAction.ORDER_CANCEL_HANDOFF
            is LocalCollectionStockRequestV2.AcceptHandoff -> LocalAction.ORDER_ACCEPT_HANDOFF
            is LocalCollectionStockRequestV2.Begin -> LocalAction.COLLECTION_PREPARE
            is LocalCollectionStockRequestV2.Finalize -> LocalAction.SALE_FINALIZE
            is LocalCollectionStockRequestV2.CreateOrder, is LocalCollectionStockRequestV2.ReplaceUnlockedOrder,
            is LocalCollectionStockRequestV2.ReassignOrder -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
        require(action == expected)
        selected
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}

/** Recovery validates the original saved form/body; it never refreshes a progressed target. */
internal fun savedLanCollectionRequest(entry: StoredClientIntentV1): LocalCollectionStockRequestV2 {
    if (entry.actionApproval != null || entry.formVersion != 2) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val request = lanCollectionRequest(entry.localAction, entry.request())
    try {
        entry.validate(entry.owner)
        entry.recorded?.let { result ->
            val payload = decodeHex(result.payloadHex, 65_536).useWiped { StoredBytes(it) }
            requireLanCollectionOutcome(request, result.committed, result.code, payload)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    return request
}

internal fun lanCollectionRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded) {
    val request = savedLanCollectionRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId ||
        reply.physicalStart != null || reply.sequence.value <= 0L) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    requireLanCollectionOutcome(request, reply.committed, reply.code, reply.result)
}

private fun requireLanCollectionOutcome(request: LocalCollectionStockRequestV2, committed: Boolean,
    code: String, payload: StoredBytes) {
    if (!committed && code == "precondition.expired" && payload == StoredBytes("precondition.expired".encodeToByteArray())) return
    // The public decoder already compares the complete original body with its
    // canonical encoding before returning. No cross-module encoder is required.
    val result = try { LocalSalesResultCodec.decode(payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    val valid = when (result) {
        is LocalSalesResultV1.Rejected -> !committed && code == "sales.stock.rule_rejected"
        is LocalSalesResultV1.Unavailable -> !committed && when (code) {
            "sales.stock.quote_unavailable", "sales.stock.unavailable" -> result.reason == LocalSalesUnavailable.STOCK_REQUIRED
            "sales.stock.capacity_required" -> result.reason == LocalSalesUnavailable.CAPACITY_REQUIRED
            else -> false
        }
        is LocalSalesResultV1.StockResolutionRequired -> false
        is LocalSalesResultV1.Applied -> committed && code == "committed" && result.stockItems.isEmpty() &&
            result.revisions.map { it.kind to it.id }.distinct().size == result.revisions.size &&
            result.revisions.all { it.revision.value >= 0 } && validLanCollectionApplied(request, result)
    }
    if (!valid) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
}

private fun validLanCollectionApplied(request: LocalCollectionStockRequestV2, result: LocalSalesResultV1.Applied): Boolean {
    fun has(kind: String, id: com.ukkera.pos.types.DomainId, revision: Revision): Boolean =
        result.revisions.count { it.kind == kind && it.id == id && it.revision == revision } == 1
    val orderKind = LocalSalesStoreProfile.ORDER_KIND
    val workKind = LocalSalesStoreProfile.WORK_KIND
    val collectionKind = LocalSalesStoreProfile.COLLECTION_KIND
    return when (request) {
        is LocalCollectionStockRequestV2.OfferHandoff -> result.action == LocalSalesAction.HANDOFF_OFFERED &&
            result.documentId == request.orderId && result.amount == null && result.revisions.size == 1 &&
            has(orderKind, request.orderId, request.expectedOrderRevision.next())
        is LocalCollectionStockRequestV2.CancelHandoff -> result.action == LocalSalesAction.HANDOFF_CANCELLED &&
            result.documentId == request.orderId && result.amount == null && result.revisions.size == 1 &&
            has(orderKind, request.orderId, request.expectedOrderRevision.next())
        is LocalCollectionStockRequestV2.AcceptHandoff -> result.action == LocalSalesAction.HANDOFF_ACCEPTED &&
            result.documentId == request.orderId && result.amount == null && result.revisions.size == 3 &&
            result.revisions.count { it.kind == workKind } == 2 &&
            has(orderKind, request.orderId, request.expectedOrderRevision.next())
        is LocalCollectionStockRequestV2.Begin -> result.action == LocalSalesAction.COLLECTION_LOCKED &&
            result.documentId == request.collectionId && result.amount?.let { it.currency == Currency.EGP && it.minor >= 0 } == true &&
            result.revisions.size == 2 && has(orderKind, request.orderId, request.expectedOrderRevision.next()) &&
            has(collectionKind, request.collectionId, Revision(0))
        is LocalCollectionStockRequestV2.Finalize -> result.action == LocalSalesAction.SALE_FINALIZED &&
            result.documentId == request.saleId && result.amount?.let { it.currency == Currency.EGP && it.minor >= 0 } == true &&
            result.revisions.size == 5 && result.revisions.count { it.kind == workKind } == 1 &&
            has(orderKind, request.orderId, request.expectedOrderRevision.next()) &&
            has(collectionKind, request.collectionId, request.expectedCollectionRevision.next()) &&
            has(LocalSalesStoreProfile.SALE_KIND, request.saleId, Revision(0)) &&
            has(LocalSalesStoreProfile.ENTITLEMENT_KIND, request.saleId, Revision(0))
        is LocalCollectionStockRequestV2.CreateOrder, is LocalCollectionStockRequestV2.ReplaceUnlockedOrder,
        is LocalCollectionStockRequestV2.ReassignOrder -> false
    }
}
