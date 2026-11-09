package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalConfigurationProfile
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction

/** Closed composition hints; every read still needs the actual Master's current authority. */
internal val stockTransferReads: Set<ReadKey> = setOf(
    LocalStockStoreProfile.ITEMS, LocalStockStoreProfile.QUANTITIES, LocalStockStoreProfile.QUANTITY_POSITION,
    LocalStockStoreProfile.COSTS, LocalStockStoreProfile.TRANSFER_DETAIL, LocalStockStoreProfile.TRANSFER_COSTS,
    LocalStockRelationalQueriesV2.PROFILE, LocalStockRelationalQueriesV2.HISTORY, LocalConfigurationProfile.QUERY,
)

internal fun requireStockTransferWorkflow(query: LocalWorkflowQuery) {
    if (query.workflow != LocalWorkflow.INVENTORY ||
        query.inventory !in setOf(LocalInventorySection.QUANTITIES, LocalInventorySection.COSTS) ||
        query.pricePreview != null || query.branchReport != null || query.food != null || query.purchasing != null ||
        query.returns != null || query.cash != null || query.openingAllocations != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val reference = query.reference
    if (reference != null) {
        if (!stockTransferSelection(reference) || query.search.isNotBlank() &&
            !(reference.kind == LocalRecordKind.STOCK_CUT && query.search.trim().equals("history", ignoreCase = true)))
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    } else if (':' in query.search && !query.search.startsWith("sku:") && !query.search.startsWith("transfer:")) {
        // Unrelated exact stock/document routes must not become a successful empty item filter.
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
}

private fun stockTransferSelection(reference: LocalRecordReference): Boolean = when (reference.kind) {
    LocalRecordKind.SKU, LocalRecordKind.TRANSFER -> reference.parentId == null && reference.revision == null
    LocalRecordKind.STOCK_CUT -> reference.parentId != null && reference.revision == null
    else -> false
}

internal fun requireStockTransferIntent(intent: LocalCommandIntent) {
    if (intent.action != LocalAction.WAREHOUSE_TRANSFER || intent.formVersion != 1 ||
        intent.arrivalEvidence != null || intent.lines.isNotEmpty() || intent.lineField != null ||
        intent.reference?.let { it.kind == LocalRecordKind.STOCK_CUT && stockTransferSelection(it) } != true)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

internal fun requireStockTransferRead(request: ReadRequest) {
    if (request.query !in stockTransferReads) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (request.query == LocalStockRelationalQueriesV2.PROFILE &&
        request.payload != LocalStockRelationalQueriesV2.profile().payload) invalid()
    if (request.query != LocalConfigurationProfile.QUERY) return
    val query = try { LocalConfigurationCodec.decodeQuery(request.payload) }
    catch (_: Exception) { invalid() }
    if (query.kind != LocalConfigurationProfile.WAREHOUSE || query.barcode != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (LocalConfigurationCodec.query(query) != request.payload) invalid()
}

/** New intent preparation only. A missing normalized authority never routes to legacy stock. */
internal suspend fun requireNormalizedTransferStorage(access: GatewayAccess) {
    if (!access.installed.has(LocalStockRelationalQueriesV2.PROFILE) ||
        !LocalStockRelationalQueriesV2.normalized(access.read(LocalStockRelationalQueriesV2.profile()).payload))
        throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
}

/** The draft has no transfer document yet. Specific targets are checked after explicit entry. */
internal fun stockTransferFormHint(access: GatewayAccess): Boolean {
    val grants = access.view.policy.grants.filter { it.permission == Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM) &&
        (it.scope == access.view.scope || it.scope == access.view.scope.company()) &&
        (it.namedSubjectId == null || it.namedSubjectId == access.view.actorId) }
    return grants.none { it.effect == GrantEffect.DENY && it.targetIds == null } && grants.any { it.effect == GrantEffect.ALLOW }
}

/** The shared normalized STOCK key does not admit receiving, opening, counts or safety bodies. */
internal fun normalizedStockTransferRequest(action: LocalAction, request: MutationRequest): LocalStockRelationalCommand.Transfer {
    if (action != LocalAction.WAREHOUSE_TRANSFER || request.command != LocalStockRelationalProfileV2.COMMAND ||
        request.actionApprovalId != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val transfer = try { LocalStockRelationalCommandsV2.decode(request.payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    if (transfer !is LocalStockRelationalCommand.Transfer || transfer.request.source.supplierId != null ||
        transfer.request.source.custody != StockCustody.RELEASED)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    if (transfer.operationId != request.operationId || LocalStockRelationalCommandsV2.encode(transfer) != request.payload ||
        transfer.request.expectedItemRevision.value !in 1 until Long.MAX_VALUE || transfer.request.quantity.scaled <= 0 ||
        transfer.request.destinationWarehouseId == transfer.request.source.warehouseId)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return transfer
}

/** Recovery validates the saved original; it never re-reads a position to rebuild its payload. */
internal fun savedStockTransferRequest(entry: StoredClientIntentV1): LocalStockRelationalCommand.Transfer {
    if (entry.formVersion != 1 || entry.actionApproval != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val transfer = normalizedStockTransferRequest(entry.localAction, entry.request())
    try {
        entry.validate(entry.owner)
        entry.recorded?.let { retained ->
            val payload = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            stockTransferOutcome(transfer, retained.committed, payload)
        }
    } catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    return transfer
}

internal fun stockTransferRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalStockResult {
    val transfer = savedStockTransferRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId || reply.physicalStart != null)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return stockTransferOutcome(transfer, reply.committed, reply.result)
}

private fun stockTransferOutcome(transfer: LocalStockRelationalCommand.Transfer, committed: Boolean,
    payload: StoredBytes): LocalStockResult {
    val result = try { LocalStockRelationalCommandsV2.decodeResult(LocalStockRelationalCommandsV2.encode(transfer), payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    if (result.documentId != transfer.request.transferId ||
        committed && (result.kind != LocalStockResultKind.TRANSFERRED || result.failure != null ||
            result.items != listOf(LocalStockItemVersion(transfer.request.source.skuId, transfer.request.expectedItemRevision.next()))) ||
        !committed && (result.kind != LocalStockResultKind.REJECTED || result.failure == null))
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return result
}
