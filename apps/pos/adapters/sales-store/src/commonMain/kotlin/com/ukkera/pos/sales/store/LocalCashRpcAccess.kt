package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.salesAuthorizationRequirement
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Disclosure reads the immutable original AFTER, not today's cash/session state and not a
 * caller-selected owner. A later close, recount or receipt cannot re-prepare this command.
 */
internal suspend fun localCashRpcRetainedResultRequirements(normalized: StoredBytes, view: CommandReadView,
    context: CommandContext): List<AuthorizationRequirement>? {
    if (context.scope != view.scope || context.key != LocalSalesStoreProfile.CASH_COMMAND ||
        LocalCashCommandCodec.normalize(normalized) != normalized ||
        semanticDigest(context.scope, context.originalActorId, context.key, normalized) != context.semanticDigest) return null
    val original = view.retainedCommand(context.operationId) ?: return null
    if (original.nativeOriginalProvenance != null || original.scope != context.scope ||
        original.operationId != context.operationId || original.originalActorId != context.originalActorId ||
        original.command != context.key || original.semanticDigest != context.semanticDigest || original.normalized != normalized) return null
    val request = LocalCashCommandCodec.decode(normalized)
    val result = LocalSalesResultCodec.decode(original.result)
    if (LocalSalesResultCodec.encode(result) != original.result) return null
    fun requirement(action: SecurityAction, target: DomainId, revision: Revision, owner: DomainId): List<AuthorizationRequirement> =
        listOf(salesAuthorizationRequirement(action, context.scope, target, revision, context.operationId,
            context.semanticDigest, "cash.original.read", owner))

    if (!original.committed) {
        // These exact scalar refusals contain no source balances, owners or revisions. An
        // unavailable original is never reconstructed from a newer financial projection.
        if (original.aggregateWrites.isNotEmpty() || original.claimChanges.isNotEmpty() || original.jobs.isNotEmpty() ||
            !(original.code == "sales.domain_rejected" && result is LocalSalesResultV1.Rejected ||
                original.code == "sales.profile_unavailable" && result is LocalSalesResultV1.Unavailable)) return null
        val action = when (request) {
            is LocalCashRequestV1.ConfigureDrawer, is LocalCashRequestV1.ConfigureCustodian,
            is LocalCashRequestV1.ConfigureSafe -> SecurityAction.CASH_CONFIGURE
            is LocalCashRequestV1.Review -> SecurityAction.CASH_REVIEW
            else -> SecurityAction.CASH_READ
        }
        return requirement(action, context.originalActorId, Revision(0), context.originalActorId)
    }
    val applied = result as? LocalSalesResultV1.Applied ?: return null
    if (applied.stockItems.isNotEmpty() || original.jobs.isNotEmpty()) return null
    val expected = when (request) {
        is LocalCashRequestV1.ConfigureDrawer -> LocalSalesAction.DRAWER_CONFIGURED to request.drawerId
        is LocalCashRequestV1.ConfigureCustodian -> LocalSalesAction.CUSTODIAN_CONFIGURED to request.custodianId
        is LocalCashRequestV1.ConfigureSafe -> LocalSalesAction.SAFE_CONFIGURED to request.custodyId
        is LocalCashRequestV1.Open -> LocalSalesAction.CASH_OPENED to request.sessionId
        is LocalCashRequestV1.NonSale -> LocalSalesAction.CASH_MOVEMENT_RECORDED to request.sourceId
        is LocalCashRequestV1.BeginClosing -> LocalSalesAction.CASH_CLOSING to request.sessionId
        is LocalCashRequestV1.CancelClosing -> LocalSalesAction.CASH_CLOSING_CANCELLED to request.sessionId
        is LocalCashRequestV1.RecordClosingCount -> LocalSalesAction.CASH_COUNT_RECORDED to request.evidence.evidenceId
        is LocalCashRequestV1.DeliverCountedClose -> LocalSalesAction.CASH_COUNTED_DELIVERY_SENT to request.transferId
        is LocalCashRequestV1.SubmitCountedClose -> LocalSalesAction.CASH_COUNTED_CLOSED to request.sessionId
        is LocalCashRequestV1.SubmitClose -> LocalSalesAction.CASH_CLOSED to request.sessionId
        is LocalCashRequestV1.Review -> LocalSalesAction.CASH_REVIEWED to request.sessionId
        is LocalCashRequestV1.DropToSafe -> LocalSalesAction.SAFE_TRANSFER_SENT to request.transferId
        is LocalCashRequestV1.ReceiveSafe -> LocalSalesAction.SAFE_TRANSFER_RECEIVED to request.transferId
    }
    if (applied.action != expected.first || applied.documentId != expected.second) return null
    when (request) {
        is LocalCashRequestV1.ConfigureDrawer, is LocalCashRequestV1.ConfigureCustodian,
        is LocalCashRequestV1.ConfigureSafe -> {
            if (applied.amount != null) return null
            return requirement(SecurityAction.CASH_CONFIGURE, applied.documentId, Revision(0), context.originalActorId)
        }
        is LocalCashRequestV1.ReceiveSafe -> {
            val transferWrite = original.aggregateWrites.singleOrNull {
                it.key == LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, request.transferId)
            } ?: return null
            if (!transferWrite.cashRpcWrite(LocalSalesStoreProfile.schema(LocalSalesStoreProfile.TRANSFER_KIND))) return null
            val transfer = SalesSnapshotCodec.decodeSafeTransfer(transferWrite.cashRpcText(),
                SalesSnapshotBinding(SalesSnapshotKind.SAFE_TRANSFER, context.scope, request.transferId, transferWrite.cashRpcRevision()))
            if (transfer.revision != request.expectedTransferRevision.next() || transfer.receipt?.actorId != context.originalActorId ||
                transfer.amount != applied.amount) return null
            val safeWrite = original.aggregateWrites.singleOrNull {
                it.key == LocalSalesStoreProfile.key(LocalSalesStoreProfile.SAFE_KIND, transfer.receivingCustodyId)
            } ?: return null
            if (!safeWrite.cashRpcWrite(LocalSalesStoreProfile.schema(LocalSalesStoreProfile.SAFE_KIND))) return null
            val safe = localSalesDecode(safeWrite.payload, LocalSafeState.serializer())
            if (safe.scope.value() != context.scope || safe.id != transfer.receivingCustodyId ||
                safe.revision != safeWrite.cashRpcRevision() || safe.revision != request.expectedSafeRevision.next() ||
                safe.custodianId != context.originalActorId || safe.received.currency != transfer.amount.currency) return null
            return requirement(SecurityAction.CASH_READ, safe.id, safe.revision, safe.custodianId)
        }
        else -> Unit
    }
    val sessionId = when (request) {
        is LocalCashRequestV1.Open -> request.sessionId
        is LocalCashRequestV1.NonSale -> request.sessionId
        is LocalCashRequestV1.BeginClosing -> request.sessionId
        is LocalCashRequestV1.CancelClosing -> request.sessionId
        is LocalCashRequestV1.RecordClosingCount -> request.sessionId
        is LocalCashRequestV1.DeliverCountedClose -> request.sessionId
        is LocalCashRequestV1.SubmitCountedClose -> request.sessionId
        is LocalCashRequestV1.SubmitClose -> request.sessionId
        is LocalCashRequestV1.Review -> request.sessionId
        is LocalCashRequestV1.DropToSafe -> request.sessionId
        else -> return null
    }
    val sessionWrite = original.aggregateWrites.singleOrNull { it.key == CashNonSaleStoreProfile.sessionKey(sessionId) } ?: return null
    if (!sessionWrite.cashRpcWrite(CashNonSaleStoreProfile.SESSION_SCHEMA)) return null
    val session = SalesSnapshotCodec.decodeCashSession(sessionWrite.cashRpcText(),
        SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, context.scope, sessionId, sessionWrite.cashRpcRevision()))
    if (applied.revisions.count { it.kind == CashNonSaleStoreProfile.SESSION_KIND && it.id == session.id &&
            it.revision == session.revision } != 1) return null
    if (request is LocalCashRequestV1.Review) {
        if (session.review?.reviewerId != context.originalActorId || session.review?.evidence?.actorId != context.originalActorId ||
            session.custodianId == context.originalActorId) return null
        return requirement(SecurityAction.CASH_REVIEW, session.id, session.revision, session.custodianId)
    }
    if (session.custodianId != context.originalActorId) return null
    return requirement(SecurityAction.CASH_READ, session.id, session.revision, session.custodianId)
}

private fun RetainedAggregateWrite.cashRpcWrite(expectedSchema: String): Boolean =
    schema == expectedSchema && storageGeneration.value > 0 && sha256(payload.copyBytes()) == payloadDigest

private fun RetainedAggregateWrite.cashRpcRevision(): Revision = Revision(storageGeneration.value - 1)

private fun RetainedAggregateWrite.cashRpcText(): String {
    val bytes = payload.copyBytes()
    return try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
}
