package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

/** Closed reader for the active core-created scan. All present dependencies require their
 * strictly earlier whole original to have joined; missing row + surviving claim is corruption. */
internal class LocalCashHistoryRead(private val command: RuntimeHistoricalCommand) {
    private val original = (command.original as RetainedOriginalV2.Legacy).evidence

    private suspend fun required(key: AggregateKey, schema: String = LocalSalesStoreProfile.schema(key.kind)): StoredAggregate {
        val row = command.aggregateSource(key)?.row ?: error("cash.history.source_missing")
        check(row.key == key && row.schema == schema && row.revision.value > 0)
        return row
    }

    private suspend fun <T> root(kind: String, id: DomainId, serializer: KSerializer<T>,
        validate: (T, Revision) -> Unit): LocalSalesRow<T> {
        val row = required(LocalSalesStoreProfile.key(kind, id))
        val value = decodeLocalSalesSource(row.payload, serializer)
        validate(value, row.domainRevision())
        val configured = if (kind == LocalSalesStoreProfile.WORK_KIND) LocalSalesStoreProfile.CUSTODIAN_KIND else kind
        check(command.claim(configuredClaim(configured, id)) == id)
        return LocalSalesRow(row, value)
    }

    suspend fun cash(id: DomainId): LocalCashCut {
        val row = required(CashNonSaleStoreProfile.sessionKey(id), CashNonSaleStoreProfile.SESSION_SCHEMA)
        val session = SalesSnapshotCodec.decodeCashSession(row.text(),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, command.scope, id, row.domainRevision()))
        check(command.claim(LocalSalesStoreProfile.unique("cash_session", id)) == id &&
            command.claim(LocalSalesStoreProfile.unique("opening_source", session.openingSourceId)) == id)
        val drawer = root(LocalSalesStoreProfile.DRAWER_KIND, session.drawerId, LocalDrawerState.serializer()) { value, revision ->
            check(value.scope.value() == command.scope && value.id == session.drawerId && value.revision == revision &&
                (value.activeSessionId == null || value.closingTransferId == null))
            localLabel(value.label)
        }
        val custodian = root(LocalSalesStoreProfile.CUSTODIAN_KIND, session.custodianId, LocalCustodianState.serializer()) { value, revision ->
            check(value.scope.value() == command.scope && value.id == session.custodianId && value.revision == revision)
        }
        val work = root(LocalSalesStoreProfile.WORK_KIND, session.custodianId, LocalWorkState.serializer()) { value, revision ->
            check(value.scope.value() == command.scope && value.id == session.custodianId && value.revision == revision &&
                listOf(value.orderIds, value.returnIds, value.pendingClaimIds).all {
                    it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
        }
        check(command.claim(LocalSalesStoreProfile.activeDrawer(drawer.value.id)) == drawer.value.activeSessionId &&
            command.claim(LocalSalesStoreProfile.activeCustodian(custodian.value.id)) == custodian.value.activeSessionId &&
            drawer.value.currency == session.expectedCash.currency)
        if (session.status == CashSessionStatus.OPEN || session.status == CashSessionStatus.CLOSING)
            check(drawer.value.activeSessionId == id && custodian.value.activeSessionId == id && drawer.value.closingTransferId == null)
        else check(drawer.value.activeSessionId != id && custodian.value.activeSessionId != id)
        return LocalCashCut(LocalSalesRow(row, session), drawer, custodian, work)
    }

    fun afterSession(id: DomainId): CashSession {
        val write = original.aggregateWrites.single { it.key == CashNonSaleStoreProfile.sessionKey(id) }
        check(write.schema == CashNonSaleStoreProfile.SESSION_SCHEMA && write.storageGeneration.value > 0)
        val bytes = write.payload.copyBytes()
        return try { SalesSnapshotCodec.decodeCashSession(bytes.decodeToString(throwOnInvalidSequence = true),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, command.scope, id, Revision(write.storageGeneration.value - 1))) }
        finally { bytes.fill(0) }
    }

    fun <T> afterRoot(key: AggregateKey, serializer: KSerializer<T>): T {
        val write = original.aggregateWrites.single { it.key == key }
        check(write.schema == LocalSalesStoreProfile.schema(key.kind) && write.storageGeneration == Revision(1))
        return decodeLocalSalesSource(write.payload, serializer)
    }

    suspend fun countHead(session: CashSession): LocalClosingCountHead? {
        val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, session.id)
        val source = command.aggregateSource(key)
        val claim = command.claim(cashCountHistoryClaim(session.id))
        if (source == null) { check(claim == null); return null }
        val row = source.row
        check(row.schema == LocalSalesStoreProfile.schema(key.kind) && claim == session.id)
        val index = decodeLocalSalesSource(row.payload, LocalClosingCountIndexV1.serializer())
        check(index.scope.value() == command.scope && index.sessionId == session.id && index.revision == row.domainRevision() &&
            index.sessionRevision <= session.revision && (index.transferId == null) == (index.retainedFloat == null))
        val recordRow = required(LocalSalesStoreProfile.key(LocalSalesStoreProfile.CLOSING_COUNT_KIND, index.countId))
        val record = decodeLocalSalesSource(recordRow.payload, LocalClosingCountRecordV1.serializer())
        check(recordRow.domainRevision() == Revision(0) && record.scope.value() == command.scope && record.id == index.countId &&
            command.claim(cashCountClaim(record.id)) == session.id &&
            command.claim(cashCountPositionClaim(session.id, record.position)) == record.id)
        val count = record.value()
        CashCountedClosing.requireCountSession(count, session)
        check(index.position == count.position &&
            index.revision == if (index.transferId == null) index.position else index.position.next())
        val transfer = index.transferId?.let { transfer(it) }
        if (transfer == null) check(index.sessionRevision == count.sessionRevision)
        else {
            val retained = checkNotNull(index.retainedFloat)
            val destination = safe(transfer.value.receivingCustodyId).value
            check(index.sessionRevision == count.sessionRevision.next() && retained.minor >= 0 &&
                retained.currency == count.count.amount.currency && transfer.value.amount == count.count.amount - retained &&
                transfer.value.closingDisposition && transfer.value.senderSessionId == session.id &&
                transfer.value.senderDrawerId == session.drawerId && transfer.value.senderCustodianId == session.custodianId &&
                destination.custodianId != session.custodianId && destination.received.currency == count.count.amount.currency)
            val receiptOwner = command.claim(ClaimKey(LocalSalesStoreProfile.SAFE_RECEIPT_NAMESPACE, transfer.value.id.value))
            val receipt = transfer.value.receipt
            check(if (transfer.value.status == SafeTransferStatus.RECEIVED)
                receiptOwner == destination.id && receipt != null && receipt.actorId == destination.custodianId
                else receiptOwner == null && receipt == null)
        }
        val result = LocalClosingCountHead(LocalSalesRow(row, index), LocalSalesRow(recordRow, record), transfer)
        if (transfer != null) {
            if (session.status == CashSessionStatus.CLOSING) {
                check(result.isCurrent(session))
                CashCountedClosing.requirePendingDisposition(count, session, transfer.value)
            } else {
                check(session.status in setOf(CashSessionStatus.SUBMITTED_CLOSED, CashSessionStatus.REVIEWED))
                SalesSnapshotLinks.requireSafeTransfer(transfer.value, session)
            }
        }
        if (session.status in setOf(CashSessionStatus.SUBMITTED_CLOSED, CashSessionStatus.REVIEWED)) {
            val report = checkNotNull(session.closeReport)
            check(report.count == count.count && report.cut == count.cut && report.safeTransferId == index.transferId &&
                report.retainedFloat == (index.retainedFloat ?: count.count.amount) && session.revision > index.sessionRevision)
        }
        return result
    }

    suspend fun safe(id: DomainId): LocalSalesRow<LocalSafeState> =
        root(LocalSalesStoreProfile.SAFE_KIND, id, LocalSafeState.serializer()) { value, revision ->
            check(value.scope.value() == command.scope && value.id == id && value.revision == revision && value.received.minor >= 0)
            localLabel(value.label)
        }

    suspend fun transfer(id: DomainId): LocalSalesRow<SafeTransfer> {
        val row = required(LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, id))
        val value = SalesSnapshotCodec.decodeSafeTransfer(row.text(),
            SalesSnapshotBinding(SalesSnapshotKind.SAFE_TRANSFER, command.scope, id, row.domainRevision()))
        check(command.claim(LocalSalesStoreProfile.unique("safe_transfer", id)) == value.senderSessionId)
        return LocalSalesRow(row, value)
    }

    fun afterTransfer(id: DomainId): SafeTransfer {
        val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, id)
        val write = original.aggregateWrites.single { it.key == key }
        check(write.schema == LocalSalesStoreProfile.schema(key.kind) && write.storageGeneration.value > 0)
        val bytes = write.payload.copyBytes()
        return try { SalesSnapshotCodec.decodeSafeTransfer(bytes.decodeToString(throwOnInvalidSequence = true),
            SalesSnapshotBinding(SalesSnapshotKind.SAFE_TRANSFER, command.scope, id, Revision(write.storageGeneration.value - 1))) }
        finally { bytes.fill(0) }
    }

    suspend fun requireUnusedFinancialSource(id: DomainId) {
        check(command.claim(LocalSalesStoreProfile.unique("payment_attempt", id)) == null &&
            command.claim(LocalSalesStoreProfile.unique("refund_attempt", id)) == null &&
            command.claim(CashNonSaleStoreProfile.cashSourceClaim(id)) == null &&
            command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, id)) == null)
    }

    suspend fun countRecordAllocated(sessionId: DomainId, id: DomainId, position: Revision): Boolean {
        val row = command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.CLOSING_COUNT_KIND, id))
        if (row != null) return true
        // claimed() observes both unique identities even when the first is already allocated.
        val recordOwner = command.claim(cashCountClaim(id))
        val positionOwner = command.claim(cashCountPositionClaim(sessionId, position))
        return recordOwner != null || positionOwner != null
    }

    suspend fun transferAllocated(id: DomainId): Boolean =
        transferExists(id) || command.claim(LocalSalesStoreProfile.unique("payment_attempt", id)) != null ||
            command.claim(LocalSalesStoreProfile.unique("refund_attempt", id)) != null ||
            command.claim(CashNonSaleStoreProfile.cashSourceClaim(id)) != null ||
            command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, id)) != null

    suspend fun transferExists(id: DomainId): Boolean =
        command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.TRANSFER_KIND, id)) != null ||
            command.claim(LocalSalesStoreProfile.unique("safe_transfer", id)) != null

    /** A real counted delivery remains identifiable after its receipt and final submission.
     * Status alone cannot turn an ordinary or legacy combined transfer into this lineage. */
    suspend fun countedTransferLineage(session: CashSession, head: LocalClosingCountHead, transfer: SafeTransfer): Boolean {
        if (!transfer.closingDisposition || head.transfer?.value?.id != transfer.id) return false
        val source = command.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.CLOSING_COUNT_INDEX_KIND, session.id))
            ?: return false
        val retained = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (!retained.committed || retained.command != LocalSalesStoreProfile.CASH_COMMAND) return false
        val request = LocalCashCommandCodec.decode(retained.normalized) as? LocalCashRequestV1.DeliverCountedClose
            ?: return false
        check(LocalCashCommandCodec.encode(request) == retained.normalized && retained.scope == command.scope &&
            retained.originalActorId == session.custodianId && request.sessionId == session.id &&
            request.countId == head.count.id && request.counted == head.count.count.amount &&
            request.transferId == transfer.id && request.receivingCustodyId == transfer.receivingCustodyId &&
            request.retainedFloat == head.index.value.retainedFloat &&
            request.expectedRevision.next() == head.index.value.sessionRevision)
        return true
    }

    /** Complete real BEFORE indices, not today's mutable work or a successful empty UI page. */
    suspend fun requireEmptyFoodWork(ownerId: DomainId): Boolean {
        val indexKey = LocalFoodProfileV1.key(LocalFoodProfileV1.OWNER_INDEX, ownerId)
        val index = command.aggregateSource(indexKey)?.row
        val indexClaim = command.claim(LocalFoodProfileV1.claim(indexKey.kind, ownerId))
        if (index == null) check(indexClaim == null)
        else {
            check(indexClaim == ownerId && index.schema in setOf(LocalFoodProfileV1.schema(indexKey.kind), foodStockSchema(indexKey.kind)))
            val value = foodEnvelope(index.payload)
            check(value.scope == command.scope && value.kind == indexKey.kind && value.id == ownerId && value.revision == index.revision)
            check((value.body as FoodBodyV1.OwnerIndex).orderIds.isEmpty())
        }
        val claimId = foodClaimIndexId(FoodClaimIndexTargetV1.OWNER, ownerId)
        val key = AggregateKey(LocalFoodFinancialProfileV1.CLAIM_INDEX, claimId)
        val claims = command.aggregateSource(key)?.row
        val owner = command.claim(LocalFoodFinancialProfileV1.source(key.kind, claimId))
        if (claims == null) check(owner == null)
        else {
            // Framed financial index decoding requires its actual complete frame join;
            // unsupported bytes cannot be interpreted as an empty obligation set.
            if (claims.schema != LocalFoodFinancialProfileV1.schema(key.kind)) return false
            val value = foodFinancialEnvelope(claims.payload)
            val body = value.body as FoodFinancialBodyV1.ClaimIndex
            check(owner == claimId && value.scope == command.scope && value.kind == key.kind && value.id == claimId &&
                value.revision == claims.revision && body.target == FoodClaimIndexTargetV1.OWNER &&
                body.targetId == ownerId && body.claimIds.isEmpty())
        }
        return true
    }
}
