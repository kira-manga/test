package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

/** Exact installed cash hydration order for financial first-refusal proofs. The ordinary
 * cash historical reader stays unchanged. Missing/invalid SALES data is the same typed
 * refusal as LocalSalesStorage.cash; native/read/provenance failures are never caught.
 * Only actual complete core-created BEFORE sources construct this reader. */
internal class LocalFinancialCashBeforeRead private constructor(private val before: FinancialCashBefore) {
    constructor(command: RuntimeHistoricalCommand) : this(FinancialCashScanBefore(command))
    constructor(sources: RuntimeHistoricalFinancialSources, view: CommandReadView) : this(FinancialCashRetainedBefore(sources, view))
    constructor(budget: FoodFinancialBeforeReadBudget) : this(FinancialCashFoodBefore(budget))

    private suspend fun required(key: AggregateKey, schema: String = LocalSalesStoreProfile.schema(key.kind)): StoredAggregate {
        val row = before.aggregate(key) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireLocalSalesSource(row.key == key && row.schema == schema && row.revision.value > 0)
        return row
    }
    private suspend fun <T> root(kind: String, id: DomainId, serializer: KSerializer<T>,
        validate: (T, Revision) -> Unit): LocalSalesRow<T> {
        val row = required(LocalSalesStoreProfile.key(kind, id))
        val value = decodeLocalSalesSource(row.payload, serializer)
        validateLocalSalesSource { validate(value, row.domainRevision()) }
        val configured = if (kind == LocalSalesStoreProfile.WORK_KIND) LocalSalesStoreProfile.CUSTODIAN_KIND else kind
        requireLocalSalesSource(before.claim(configuredClaim(configured, id)) == id)
        return LocalSalesRow(row, value)
    }
    suspend fun cash(id: DomainId): LocalCashCut {
        val row = required(CashNonSaleStoreProfile.sessionKey(id), CashNonSaleStoreProfile.SESSION_SCHEMA)
        val session = SalesSnapshotCodec.decodeCashSession(row.text(),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, before.scope, id, row.domainRevision()))
        requireLocalSalesSource(before.claim(LocalSalesStoreProfile.unique("cash_session", id)) == id)
        requireLocalSalesSource(before.claim(LocalSalesStoreProfile.unique("opening_source", session.openingSourceId)) == id)
        val drawer = root(LocalSalesStoreProfile.DRAWER_KIND, session.drawerId, LocalDrawerState.serializer()) { value, revision ->
            requireLocalSalesSource(value.scope.value() == before.scope && value.id == session.drawerId && value.revision == revision)
            localLabel(value.label)
            requireLocalSalesSource(value.activeSessionId == null || value.closingTransferId == null)
        }
        requireLocalSalesSource(before.claim(LocalSalesStoreProfile.activeDrawer(session.drawerId)) == drawer.value.activeSessionId)
        val custodian = root(LocalSalesStoreProfile.CUSTODIAN_KIND, session.custodianId, LocalCustodianState.serializer()) { value, revision ->
            requireLocalSalesSource(value.scope.value() == before.scope && value.id == session.custodianId && value.revision == revision)
        }
        requireLocalSalesSource(before.claim(LocalSalesStoreProfile.activeCustodian(session.custodianId)) == custodian.value.activeSessionId)
        val work = root(LocalSalesStoreProfile.WORK_KIND, session.custodianId, LocalWorkState.serializer()) { value, revision ->
            requireLocalSalesSource(value.scope.value() == before.scope && value.id == session.custodianId && value.revision == revision)
            requireLocalSalesSource(listOf(value.orderIds, value.returnIds, value.pendingClaimIds).all {
                it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
        }
        requireLocalSalesSource(drawer.value.currency == session.expectedCash.currency)
        if (session.status == CashSessionStatus.OPEN || session.status == CashSessionStatus.CLOSING)
            requireLocalSalesSource(drawer.value.activeSessionId == id && custodian.value.activeSessionId == id && drawer.value.closingTransferId == null)
        else requireLocalSalesSource(drawer.value.activeSessionId != id && custodian.value.activeSessionId != id)
        return LocalCashCut(LocalSalesRow(row, session), drawer, custodian, work)
    }
}

private sealed interface FinancialCashBefore {
    val scope: BranchScope
    suspend fun aggregate(key: AggregateKey): StoredAggregate?
    suspend fun claim(key: ClaimKey): DomainId?
}
private class FinancialCashScanBefore(private val command: RuntimeHistoricalCommand) : FinancialCashBefore {
    override val scope get() = command.scope
    override suspend fun aggregate(key: AggregateKey) = command.aggregateSource(key)?.row
    override suspend fun claim(key: ClaimKey) = command.claim(key)
}
private class FinancialCashRetainedBefore(private val sources: RuntimeHistoricalFinancialSources,
    private val view: CommandReadView) : FinancialCashBefore {
    override val scope get() = sources.evidence.scope
    override suspend fun aggregate(key: AggregateKey) = financialOriginalProof(view).aggregate(sources, key)
    override suspend fun claim(key: ClaimKey) = financialOriginalProof(view).claim(sources, key)
}

private class FinancialCashFoodBefore(private val budget: FoodFinancialBeforeReadBudget) : FinancialCashBefore {
    override val scope get() = budget.scope
    override suspend fun aggregate(key: AggregateKey) = budget.aggregate(key)
    override suspend fun claim(key: ClaimKey) = budget.claim(key)
}
