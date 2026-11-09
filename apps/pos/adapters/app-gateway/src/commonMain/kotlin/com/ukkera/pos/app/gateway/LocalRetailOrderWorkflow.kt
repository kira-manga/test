package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Registry hints only; the actual Master classifies each shared-key selector and command. */
internal val retailOrderCommands: Set<CommandKey> = setOf(LocalSalesStoreProfile.ORDER_COMMAND,
    LocalCollectionStockCodecV2.COMMAND)
internal val retailOrderReads: Set<ReadKey> = setOf(LocalConfigurationProfile.QUERY,
    LocalOrderReadCodec.QUERY, LocalOrderReadCodecV2.QUERY)
internal val retailOrderActions: Set<LocalAction> = setOf(LocalAction.ORDER_CREATE, LocalAction.ORDER_REPLACE)

internal fun requireRetailOrderWorkflow(query: LocalWorkflowQuery) {
    if (query.workflow !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS) ||
        query.pricePreview != null || query.branchReport != null || query.food != null ||
        query.purchasing != null || query.returns != null || query.cash != null ||
        query.inventory != null || query.openingAllocations != null ||
        query.reference?.let { it.parentId != null || it.kind !in setOf(LocalRecordKind.ORDER, LocalRecordKind.SKU) ||
            it.kind == LocalRecordKind.SKU && query.workflow != LocalWorkflow.CHECKOUT } == true ||
        listOf("manager:", "reassign:", "handoff:", "collection:", "sale:").any(query.search::startsWith) ||
        isCustomerReceiptSearch(query.search))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

internal fun requireRetailOrderIntent(intent: LocalCommandIntent) {
    if (intent.action !in retailOrderActions || intent.formVersion !in 1..2 ||
        intent.arrivalEvidence != null || intent.lineField != LocalField.LINES || intent.lines.size !in 1..16 ||
        intent.reference?.let { it.parentId != null ||
            it.kind != if (intent.action == LocalAction.ORDER_CREATE) LocalRecordKind.SKU else LocalRecordKind.ORDER } == true)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

internal fun requireRetailOrderRead(request: ReadRequest) {
    try {
        when (request.query) {
            LocalConfigurationProfile.QUERY -> {
                val query = LocalConfigurationCodec.decodeQuery(request.payload)
                if (query.kind != LocalConfigurationProfile.SKU)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalConfigurationCodec.query(query) != request.payload) invalid()
            }
            LocalOrderReadCodec.QUERY, LocalOrderReadCodecV2.QUERY -> {
                val query = LocalOrderReadCodec.decodeQuery(request.payload)
                if (query !is LocalOrderQueryV1.Order && query !is LocalOrderQueryV1.OwnOrders)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalOrderReadCodec.encode(query) != request.payload) invalid()
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { invalid() }
}

internal class RetailOrderOriginal(val orderId: DomainId, val expectedRevision: Revision?,
    val selection: LocalOrderSelectionV1, val formVersion: Int)

/** Recovery checks only the exact stored format; no current quote, profile or order is fetched. */
internal fun retailOrderRequest(action: LocalAction, request: MutationRequest): RetailOrderOriginal {
    if (action !in retailOrderActions || request.command !in retailOrderCommands || request.actionApprovalId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return try {
        val original = when (request.command) {
            LocalSalesStoreProfile.ORDER_COMMAND -> {
                val value = LocalOrderCommandCodec.decode(request.payload)
                require(LocalOrderCommandCodec.encode(value) == request.payload)
                when (value) {
                    is LocalOrderRequestV1.Create -> RetailOrderOriginal(value.orderId, null, value.selection, 1)
                    is LocalOrderRequestV1.ReplaceUnlocked -> RetailOrderOriginal(value.orderId, value.expectedRevision, value.selection, 1)
                    else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                }
            }
            LocalCollectionStockCodecV2.COMMAND -> {
                val value = LocalCollectionStockCodecV2.decode(request.payload)
                require(LocalCollectionStockCodecV2.encode(value) == request.payload)
                when (value) {
                    is LocalCollectionStockRequestV2.CreateOrder -> RetailOrderOriginal(value.orderId, null, value.selection, 2)
                    is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> RetailOrderOriginal(value.orderId,
                        value.expectedOrderRevision, value.selection, 2)
                    else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                }
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
        require(action == if (original.expectedRevision == null) LocalAction.ORDER_CREATE else LocalAction.ORDER_REPLACE)
        require(original.expectedRevision?.value?.let { it in 0 until Long.MAX_VALUE } != false)
        original
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}

internal fun savedRetailOrderRequest(entry: StoredClientIntentV1): RetailOrderOriginal {
    if (entry.actionApproval != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val original = retailOrderRequest(entry.localAction, entry.request())
    try {
        entry.validate(entry.owner)
        require(entry.formVersion == original.formVersion)
        entry.recorded?.let { retained ->
            val payload = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            retailOrderOutcome(original, retained.committed, retained.code, payload)
        }
    } catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    return original
}

/** Matching typed result validation precedes durable remembrance and presentation ACK. */
internal fun retailOrderRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalSalesResultV1 {
    val original = savedRetailOrderRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId || reply.physicalStart != null)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return retailOrderOutcome(original, reply.committed, reply.code, reply.result)
}

private fun retailOrderOutcome(original: RetailOrderOriginal, committed: Boolean,
    code: String, payload: StoredBytes): LocalSalesResultV1 {
    val result = try { LocalSalesResultCodec.decode(payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    val valid = when (result) {
        is LocalSalesResultV1.Applied -> {
            val action = if (original.expectedRevision == null) LocalSalesAction.ORDER_CREATED else LocalSalesAction.ORDER_EDITED
            val revision = original.expectedRevision?.next() ?: Revision(0)
            val order = result.revisions.singleOrNull { it.kind == LocalSalesStoreProfile.ORDER_KIND }
            committed && code == "committed" && result.action == action && result.documentId == original.orderId &&
                order != null && order.id == original.orderId && order.revision == revision && result.stockItems.isEmpty() &&
                result.amount?.let { it.currency == original.selection.currency && it.minor >= 0 } == true &&
                result.revisions.map { it.kind to it.id }.distinct().size == result.revisions.size &&
                result.revisions.all { it.kind in setOf(LocalSalesStoreProfile.ORDER_KIND,
                    LocalSalesStoreProfile.WORK_KIND, LocalSalesStoreProfile.PRICE_SOURCES_KIND,
                    LocalSalesReturnPolicyCaptureCodecV2.KIND) }
        }
        is LocalSalesResultV1.Rejected -> !committed && code ==
            if (original.formVersion == 1) "sales.domain_rejected" else "sales.stock.rule_rejected"
        is LocalSalesResultV1.Unavailable -> !committed && code in
            if (original.formVersion == 1) setOf("sales.profile_unavailable")
            else setOf("sales.stock.quote_unavailable", "sales.stock.unavailable", "sales.stock.capacity_required")
        is LocalSalesResultV1.StockResolutionRequired -> false
    }
    if (!valid) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return result
}
