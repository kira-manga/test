package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

/** Read-only loan from the SAME sales repository; no caller snapshot/assignment is accepted. */
public class LocalSupplierCashCut internal constructor(internal val cut: LocalCashCut) {
    public val session: CashSession get() = cut.session.value
    public val sessionRow: StoredAggregate get() = cut.session.stored
    public val drawerRevision: Revision get() = cut.drawer.stored.revision
    public val custodianRevision: Revision get() = cut.custodian.stored.revision
    public val workRevision: Revision get() = cut.work.stored.revision
    public val rows: List<StoredAggregate> get() = listOf(cut.session.stored, cut.drawer.stored, cut.custodian.stored, cut.work.stored)
    public val assignments: CashAssignmentReadSet get() = cut.assignments()
    public val resources: Set<RecoveryResourceKey> get() = setOf(
        RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, session.id),
        RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, session.drawerId),
        RecoveryResourceKey(RecoveryResourceKind.CASH_CUSTODY, session.custodianId),
        RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, cut.work.value.id),
    ) + cut.work.value.orderIds.map { RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, it) } +
        (cut.work.value.returnIds + cut.work.value.pendingClaimIds).map { RecoveryResourceKey(RecoveryResourceKind.REFUND_ENTITLEMENT, it) }
}

public class LocalSupplierCashSourceUnavailable : IllegalStateException("purchasing.supplier_cash_source_unavailable")

/** The only adapter for supplier cash uses existing cash codecs, claims and custody readers. */
public object LocalSupplierCashStorage {
    public val writableKinds: Set<String> = setOf(CashNonSaleStoreProfile.SESSION_KIND, LocalSalesStoreProfile.MOVEMENT_KIND)
    public val claimNamespaces: Set<String> = setOf(CashNonSaleStoreProfile.CASH_SOURCE_NAMESPACE)
    public suspend fun current(view: CommandReadView, id: DomainId): LocalSupplierCashCut = try {
        LocalSupplierCashCut(LocalSalesStorage.cash(view, id))
    } catch (_: LocalSalesSourceUnavailable) { throw LocalSupplierCashSourceUnavailable() }

    /** Reads a core-created BEFORE cut; no historical CommandReadView or current-row fallback. */
    public suspend fun historical(command: RuntimeHistoricalCommand, id: DomainId): LocalSupplierCashCut =
        LocalSupplierCashCut(LocalCashHistoryRead(command).cash(id))

    public fun sessionWrite(cut: LocalSupplierCashCut, after: CashSession): AggregateChange {
        check(after.id == cut.session.id && after.scope == cut.session.scope && after.revision == cut.session.revision.next())
        return cashWrite(after, cut.sessionRow)
    }
    public fun movementWrite(context: CommandContext, movement: CashMovement, at: UtcInstant): AggregateChange =
        cashMovementLiteralWrite(context.scope, context.operationId, context.originalActorId, movement, at)
    public fun historicalMovementWrite(command: RuntimeHistoricalCommand, movement: CashMovement): AggregateChange =
        cashMovementLiteralWrite(command.scope, command.operationId, command.actorId, movement, command.recordedAt)
    public fun movementKey(attemptId: DomainId): AggregateKey = LocalSalesStoreProfile.key(LocalSalesStoreProfile.MOVEMENT_KIND, attemptId)
    public fun sourceClaim(attemptId: DomainId): ClaimKey = CashNonSaleStoreProfile.cashSourceClaim(attemptId)
    public fun sessionKey(id: DomainId): AggregateKey = CashNonSaleStoreProfile.sessionKey(id)
    public fun decodeSession(scope: com.ukkera.pos.security.BranchScope, row: StoredAggregate): CashSession {
        check(row.key.kind == CashNonSaleStoreProfile.SESSION_KIND && row.schema == CashNonSaleStoreProfile.SESSION_SCHEMA &&
            row.revision.value > 0L && row.digest == sha256(row.payload.copyBytes()))
        return SalesSnapshotCodec.decodeCashSession(row.text(), SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION,
            scope, row.key.id, row.domainRevision()))
    }
    public fun afterSession(evidence: RetainedCommandEvidence, id: DomainId): CashSession {
        val write = evidence.aggregateWrites.single { it.key == sessionKey(id) }
        check(write.schema == CashNonSaleStoreProfile.SESSION_SCHEMA && write.storageGeneration.value > 0L &&
            write.payloadDigest == sha256(write.payload.copyBytes()))
        return SalesSnapshotCodec.decodeCashSession(write.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, evidence.scope, id, Revision(write.storageGeneration.value - 1L)))
    }
    public suspend fun requireUnusedSource(view: CommandReadView, id: DomainId): Boolean =
        view.aggregate(movementKey(id)) == null && view.claim(sourceClaim(id)) == null &&
            view.claim(LocalSalesStoreProfile.unique("payment_attempt", id)) == null &&
            view.claim(LocalSalesStoreProfile.unique("refund_attempt", id)) == null
    public suspend fun requireUnusedSource(command: RuntimeHistoricalCommand, id: DomainId): Unit {
        check(command.aggregateSource(movementKey(id)) == null && command.claim(sourceClaim(id)) == null &&
            command.claim(LocalSalesStoreProfile.unique("payment_attempt", id)) == null &&
            command.claim(LocalSalesStoreProfile.unique("refund_attempt", id)) == null)
    }

    /** Domain-owned immutable movement comparison, not another cash-source schema. */
    public fun requirePayoutWrite(evidence: RetainedCommandEvidence, movement: CashMovement): Unit {
        val actual = evidence.aggregateWrites.single { it.key == movementKey(movement.sourceId) }
        val expected = cashMovementLiteralWrite(evidence.scope, evidence.operationId, evidence.originalActorId,
            movement, evidence.recordedAt)
        check(actual.storageGeneration == Revision(1) && actual.schema == expected.schema && actual.payload == expected.payload &&
            actual.payloadDigest == sha256(expected.payload.copyBytes()))
    }
}
