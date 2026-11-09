package com.ukkera.pos.sales.store

import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.sales.CashClosingCount
import com.ukkera.pos.sales.CashClosingCut
import com.ukkera.pos.sales.CashCountedClosing
import com.ukkera.pos.sales.CashSession
import com.ukkera.pos.sales.CashSessionStatus
import com.ukkera.pos.sales.CountedCash
import com.ukkera.pos.sales.SafeTransfer
import com.ukkera.pos.sales.SafeTransferStatus
import com.ukkera.pos.sales.SalesSnapshotLinks
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** One immutable record per observation. No append-to-64KiB-list or historical pruning. */
@Serializable
internal data class LocalClosingCountRecordV1(
    val scope: LocalSalesScope, val sessionId: DomainId, val position: Revision,
    val previousCountId: DomainId?, val replacesCountId: DomainId?,
    val cycleRevision: Revision, val sessionRevision: Revision, val ledgerRevision: Revision,
    val expected: Money, val externalCollections: Money, val externalRefunds: Money,
    val counted: Money, val evidence: LocalCashActorEvidenceV1, val reason: String?,
    val operationId: OperationId, val recordedAt: UtcInstant,
) {
    val id: DomainId get() = evidence.evidenceId
    fun value(): CashClosingCount = CashClosingCount(scope.value(), sessionId, position, previousCountId,
        replacesCountId, cycleRevision, sessionRevision, CashClosingCut(ledgerRevision, expected, externalCollections, externalRefunds),
        CountedCash(counted, LocalCashEvidenceV1(evidence.evidenceId, evidence.occurredAt).domain(evidence.actorId)), reason, operationId)

    companion object {
        fun capture(value: CashClosingCount, recordedAt: UtcInstant): LocalClosingCountRecordV1 = LocalClosingCountRecordV1(
            LocalSalesScope.from(value.scope), value.sessionId, value.position, value.previousCountId, value.replacesCountId,
            value.cycleRevision, value.sessionRevision, value.cut.cashLedgerRevision, value.cut.expected,
            value.cut.externalCollections, value.cut.externalRefunds, value.count.amount,
            LocalCashActorEvidenceV1(value.id, value.count.evidence.actorId, UtcInstant(value.count.evidence.occurredAt)),
            value.reason, value.operationId, recordedAt)
    }
}

@Serializable
internal data class LocalClosingCountIndexV1(
    val scope: LocalSalesScope, val sessionId: DomainId, val revision: Revision,
    val countId: DomainId, val position: Revision, val sessionRevision: Revision,
    val transferId: DomainId? = null, val retainedFloat: Money? = null,
) // The count/transfer are independently stored and joined; this is not proof of receipt.

internal class LocalClosingCountHead(
    val index: LocalSalesRow<LocalClosingCountIndexV1>, val record: LocalSalesRow<LocalClosingCountRecordV1>,
    val transfer: LocalSalesRow<SafeTransfer>?,
) {
    val count: CashClosingCount get() = record.value.value()
    fun isCurrent(session: CashSession): Boolean = session.status == CashSessionStatus.CLOSING &&
        index.value.sessionRevision == session.revision && count.cut == session.closingCut
}

internal fun cashCountHistoryClaim(sessionId: DomainId): ClaimKey = LocalSalesStoreProfile.unique("cash_count_history", sessionId)
internal fun cashCountClaim(id: DomainId): ClaimKey = LocalSalesStoreProfile.unique("cash_count", id)
internal fun cashCountPositionClaim(sessionId: DomainId, position: Revision): ClaimKey =
    ClaimKey(LocalSalesStoreProfile.UNIQUE_NAMESPACE, "cash_count_position/${sessionId.value}/${position.value}")

internal object LocalCashCountStorage {
    private suspend fun storedRecord(view: CommandReadView, id: DomainId): LocalSalesRow<LocalClosingCountRecordV1> {
        val row = LocalSalesStorage.required(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.CLOSING_COUNT_KIND, id))
        val value = decodeLocalSalesSource(row.payload, LocalClosingCountRecordV1.serializer())
        validateLocalSalesSource {
            requireLocalSalesSource(row.domainRevision() == Revision(0) && value.scope.value() == view.scope && value.id == id)
            value.value()
        }
        requireLocalSalesSource(view.claim(cashCountClaim(id)) == value.sessionId &&
            view.claim(cashCountPositionClaim(value.sessionId, value.position)) == id)
        return LocalSalesRow(row, value)
    }

    suspend fun record(view: CommandReadView, session: CashSession, id: DomainId): LocalSalesRow<LocalClosingCountRecordV1> =
        storedRecord(view, id).also { row ->
            validateLocalSalesSource { CashCountedClosing.requireCountSession(row.value.value(), session) }
        }

    /** An arbitrary missing/foreign read cursor is not evidence of a broken stored chain. */
    suspend fun cursorRecord(view: CommandReadView, session: CashSession, id: DomainId): LocalSalesRow<LocalClosingCountRecordV1>? {
        if (!localSalesSourcePresent(view, LocalSalesStoreProfile.key(LocalSalesStoreProfile.CLOSING_COUNT_KIND, id),
                listOf(cashCountClaim(id)))) return null
        val row = storedRecord(view, id)
        if (row.value.sessionId != session.id) return null
        validateLocalSalesSource { CashCountedClosing.requireCountSession(row.value.value(), session) }
        return row
    }

    suspend fun head(view: CommandReadView, session: CashSession): LocalClosingCountHead? {
        val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, session.id)
        if (!localSalesSourcePresent(view, key, listOf(cashCountHistoryClaim(session.id)))) return null
        val row = LocalSalesStorage.required(view, key)
        val index = decodeLocalSalesSource(row.payload, LocalClosingCountIndexV1.serializer())
        requireLocalSalesSource(index.scope.value() == view.scope && index.sessionId == session.id &&
            index.revision == row.domainRevision() && index.sessionRevision <= session.revision &&
            (index.transferId == null) == (index.retainedFloat == null) &&
            view.claim(cashCountHistoryClaim(session.id)) == session.id)
        val record = record(view, session, index.countId)
        val count = record.value.value()
        requireLocalSalesSource(index.position == count.position &&
            index.revision == if (index.transferId == null) index.position else index.position.next())
        val transfer = index.transferId?.let { LocalSalesStorage.transfer(view, it) }
        if (transfer == null) requireLocalSalesSource(index.sessionRevision == count.sessionRevision)
        else {
            val retained = index.retainedFloat ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
            val safe = LocalSalesStorage.safe(view, transfer.value.receivingCustodyId).value
            requireLocalSalesSource(index.sessionRevision == count.sessionRevision.next() &&
                retained.minor >= 0 && retained.currency == count.count.amount.currency &&
                transfer.value.amount == count.count.amount - retained && transfer.value.closingDisposition &&
                transfer.value.senderSessionId == session.id && transfer.value.senderDrawerId == session.drawerId &&
                transfer.value.senderCustodianId == session.custodianId && safe.custodianId != session.custodianId &&
                safe.received.currency == count.count.amount.currency)
            val received = view.claim(ClaimKey(LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, transfer.value.id.value))
            val receipt = transfer.value.receipt
            requireLocalSalesSource(if (transfer.value.status == SafeTransferStatus.RECEIVED)
                received == safe.id && receipt != null && receipt.actorId == safe.custodianId
                else received == null && receipt == null)
        }
        val result = LocalClosingCountHead(LocalSalesRow(row, index), record, transfer)
        validateLocalSalesSource {
            if (transfer != null) {
                if (session.status == CashSessionStatus.CLOSING) {
                    requireLocalSalesSource(result.isCurrent(session))
                    CashCountedClosing.requirePendingDisposition(count, session, transfer.value)
                } else {
                    requireLocalSalesSource(session.status in setOf(CashSessionStatus.SUBMITTED_CLOSED, CashSessionStatus.REVIEWED))
                    SalesSnapshotLinks.requireSafeTransfer(transfer.value, session)
                }
            }
            if (session.status in setOf(CashSessionStatus.SUBMITTED_CLOSED, CashSessionStatus.REVIEWED)) {
                val report = session.closeReport
                requireLocalSalesSource(report != null && report.count == count.count && report.cut == count.cut &&
                    report.safeTransferId == index.transferId && report.retainedFloat == (index.retainedFloat ?: count.count.amount) &&
                    session.revision > index.sessionRevision)
            }
            // A later cancel/re-enter is a new cycle, not permission to reuse its old count.
            if (result.isCurrent(session)) requireLocalSalesSource(count.cut == session.closingCut)
        }
        return result
    }

    fun requirePrevious(newer: LocalClosingCountRecordV1, older: LocalClosingCountRecordV1) {
        requireLocalSalesSource(newer.sessionId == older.sessionId && newer.scope == older.scope &&
            newer.previousCountId == older.id && newer.position == older.position.next() &&
            newer.sessionRevision > older.sessionRevision)
        if (newer.replacesCountId != null) requireLocalSalesSource(newer.replacesCountId == older.id &&
            newer.sessionRevision == older.sessionRevision.next() && newer.cycleRevision == older.cycleRevision &&
            newer.value().cut == older.value().cut)
        else requireLocalSalesSource(newer.cycleRevision > older.sessionRevision)
    }
}

/** Existing ordinary/closed transfer relationships remain exact; only the new owned cut adds a path. */
internal suspend fun requireLocalCashTransfer(view: CommandReadView, transfer: SafeTransfer, session: CashSession) {
    if (transfer.closingDisposition && session.status == CashSessionStatus.CLOSING) {
        val head = LocalCashCountStorage.head(view, session) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        requireLocalSalesSource(head.transfer?.value?.id == transfer.id && head.isCurrent(session))
        validateLocalSalesSource { CashCountedClosing.requirePendingDisposition(head.count, session, transfer) }
    } else SalesSnapshotLinks.requireSafeTransfer(transfer, session)
}
