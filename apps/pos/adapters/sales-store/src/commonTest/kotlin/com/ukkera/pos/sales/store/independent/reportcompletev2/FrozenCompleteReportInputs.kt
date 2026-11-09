package com.ukkera.pos.sales.store.independent.reportcompletev2

import com.ukkera.pos.sales.store.LocalBranchReportAmountV1
import com.ukkera.pos.sales.store.LocalBranchReportDeliveryV1
import com.ukkera.pos.sales.store.LocalBranchReportMetricV1
import com.ukkera.pos.sales.store.LocalBranchReportQueryV1
import com.ukkera.pos.sales.store.LocalBranchReportResultV1
import com.ukkera.pos.sales.store.LocalBranchReportRowV1
import com.ukkera.pos.sales.store.LocalBranchReportSectionV1
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Semantic inputs from RE32-DESIGN-v1; literal business vocabulary needs its public binding. */
internal object FrozenCompleteReportInputs {
    val exportId = DomainId("40000000-0000-4000-8000-000000000032")
    val receiptId = DomainId("60000000-0000-4000-8000-000000000032")
    val beginOperationId = OperationId("50000000-0000-4000-8000-000000000001")
    val finishOperationId = OperationId("50000000-0000-4000-8000-000000000002")
    val recoveryOperationId = OperationId("50000000-0000-4000-8000-000000000003")
    val actorId = DomainId("20000000-0000-4000-8000-000000000001")
    val sourceRevision = Revision(7009)
    val changedRevision = Revision(7010)
    val observedAt = UtcInstant.parseCanonical("2026-10-30T12:00:00Z")
    val rootQuery = LocalBranchReportQueryV1(LocalBranchReportSectionV1.SAFE_TRANSFERS,
        after = null, limit = 5, delivery = LocalBranchReportDeliveryV1.EXPORT)
    val sourceKeys = (1..12).map(::key)
    val sourceMinorAmounts = listOf(100L, 200L, 300L, 400L, 500L, 600L,
        700L, 800L, 900L, 1000L, 1100L, 1200L)

    fun key(index: Int): DomainId = DomainId("30000000-0000-4000-8000-" + index.toString().padStart(12, '0'))

    fun row(index: Int, status: String, metric: LocalBranchReportMetricV1): LocalBranchReportRowV1 =
        LocalBranchReportRowV1(key(index), Revision(1), status = status, pendingCount = 1,
            amounts = listOf(LocalBranchReportAmountV1(metric, Money(Currency.EGP, sourceMinorAmounts[index - 1]))))

    fun page(first: Int, last: Int, after: DomainId?, next: DomainId?, status: String,
        metric: LocalBranchReportMetricV1): LocalBranchReportResultV1.Page =
        LocalBranchReportResultV1.Page(LocalBranchReportSectionV1.SAFE_TRANSFERS,
            LocalBranchReportDeliveryV1.EXPORT, after, (first..last).map { row(it, status, metric) }, next)

    fun emptyPage(): LocalBranchReportResultV1.Page = LocalBranchReportResultV1.Page(
        LocalBranchReportSectionV1.SAFE_TRANSFERS, LocalBranchReportDeliveryV1.EXPORT,
        after = null, rows = emptyList(), nextAfter = null)
}
