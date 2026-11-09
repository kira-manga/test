package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer

internal class LocalReturnCaseHistoryAcceptanceWork(val custodian: LocalSalesRow<LocalCustodianState>,
    val cash: LocalCashCut?, val work: LocalSalesRow<LocalWorkState>)

/** Exact current native BEFORE custody/work, with the same checks as the live reader.
 * Every present source has already completed its strictly earlier whole business join. */
internal suspend fun readReturnCaseHistoryAcceptanceWork(command: RuntimeHistoricalCommand): LocalReturnCaseHistoryAcceptanceWork {
    val custodian = returnCaseHistoryCustodian(command, command.actorId)
    val cash = custodian.value.activeSessionId?.let { id ->
        val source = returnCaseHistoryAcceptanceSource(command, CashNonSaleStoreProfile.sessionKey(id))
        val row = source.row
        check(row.schema == CashNonSaleStoreProfile.SESSION_SCHEMA)
        val session = SalesSnapshotCodec.decodeCashSession(row.text(),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, command.scope, id, row.domainRevision()))
        check(command.claim(LocalSalesStoreProfile.unique("cash_session", id)) == id &&
            command.claim(LocalSalesStoreProfile.unique("opening_source", session.openingSourceId)) == id)
        val drawer = returnCaseHistoryAcceptanceRoot(command, LocalSalesStoreProfile.DRAWER_KIND, session.drawerId,
            LocalDrawerState.serializer()) { value, revision ->
            check(value.scope.value() == command.scope && value.id == session.drawerId && value.revision == revision &&
                (value.activeSessionId == null || value.closingTransferId == null))
            localLabel(value.label)
        }
        val owner = returnCaseHistoryCustodian(command, session.custodianId)
        val work = returnCaseHistoryCashWork(command, session.custodianId)
        check(command.claim(LocalSalesStoreProfile.activeDrawer(drawer.value.id)) == drawer.value.activeSessionId &&
            drawer.value.currency == session.expectedCash.currency)
        if (session.status == CashSessionStatus.OPEN || session.status == CashSessionStatus.CLOSING) check(
            drawer.value.activeSessionId == id && owner.value.activeSessionId == id && drawer.value.closingTransferId == null)
        else check(drawer.value.activeSessionId != id && owner.value.activeSessionId != id)
        LocalCashCut(LocalSalesRow(row, session), drawer, owner, work)
    }
    return LocalReturnCaseHistoryAcceptanceWork(custodian, cash, cash?.work ?: returnCaseHistoryCashWork(command, command.actorId))
}

private suspend fun returnCaseHistoryCustodian(command: RuntimeHistoricalCommand, id: DomainId): LocalSalesRow<LocalCustodianState> =
    returnCaseHistoryAcceptanceRoot(command, LocalSalesStoreProfile.CUSTODIAN_KIND, id, LocalCustodianState.serializer()) { value, revision ->
        check(value.scope.value() == command.scope && value.id == id && value.revision == revision &&
            command.claim(LocalSalesStoreProfile.activeCustodian(id)) == value.activeSessionId)
    }

private suspend fun returnCaseHistoryCashWork(command: RuntimeHistoricalCommand, id: DomainId): LocalSalesRow<LocalWorkState> =
    returnCaseHistoryAcceptanceRoot(command, LocalSalesStoreProfile.WORK_KIND, id, LocalWorkState.serializer()) { value, revision ->
        check(value.scope.value() == command.scope && value.id == id && value.revision == revision &&
            listOf(value.orderIds, value.returnIds, value.pendingClaimIds).all {
                it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size
            })
    }

private suspend fun <T> returnCaseHistoryAcceptanceRoot(command: RuntimeHistoricalCommand, kind: String, id: DomainId,
    serializer: KSerializer<T>, validate: suspend (T, Revision) -> Unit): LocalSalesRow<T> {
    val row = returnCaseHistoryAcceptanceSource(command, LocalSalesStoreProfile.key(kind, id)).row
    check(row.schema == LocalSalesStoreProfile.schema(kind))
    val value = decodeLocalSalesSource(row.payload, serializer)
    validate(value, row.domainRevision())
    return LocalSalesRow(row, value)
}

private suspend fun returnCaseHistoryAcceptanceSource(command: RuntimeHistoricalCommand, key: AggregateKey) =
    (command.aggregateSource(key) ?: error("sales.return.case_history.acceptance_source_missing")).also {
        val operationId = when (val original = it.original) {
            is RetainedOriginalV2.Legacy -> original.evidence.operationId
            is RetainedOriginalV2.Streamed -> original.evidence.identity.producer.operationId
        }
        check(it.row.key == key && sha256(it.row.payload.copyBytes()) == it.row.digest &&
            command.earlierOriginal(operationId) === it.original)
    }
