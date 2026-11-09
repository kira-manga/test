package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAggregateSource
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentIssueSources
import com.ukkera.pos.database.RuntimeHistoricalFoodCurrentWriter
import com.ukkera.pos.inventory.InventoryAllocationRecord
import com.ukkera.pos.inventory.InventoryIssueSlice
import com.ukkera.pos.inventory.InventoryReservationHeader
import com.ukkera.pos.inventory.KitchenDisposition
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.inventory.ValueIssuePurpose
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/** Literal current facts for ONE FOOD portion. This is inert domain data, not a stock view,
 * command inspection, producer certificate or resource readmission. The core-created source
 * handle requires the SAME complete native/original joins before this decoder can run. */
public class LocalStockCurrentFoodIssue internal constructor(
    public val reservation: InventoryReservationHeader,
    public val issue: LocalStockIssueHeader,
    public val firstIssue: LocalStockIssueOriginal,
    allocations: List<InventoryAllocationRecord>,
    slices: List<InventoryIssueSlice>,
) {
    public val allocations: List<InventoryAllocationRecord> = localStockFrozen(allocations)
    public val slices: List<InventoryIssueSlice> = localStockFrozen(slices)
    override fun toString(): String = "LocalStockCurrentFoodIssue([REDACTED])"
}

/** SAME strict normalized codec and original scalar/membership/claim checks used by the
 * real stock owner. No DTO can manufacture the core handle, and no original is reopened.
 * All native-current range rows must decode; unknown kinds are never silently filtered. */
public suspend fun requireCurrentFoodIssueSources(sources: RuntimeHistoricalFoodCurrentIssueSources): LocalStockCurrentFoodIssue {
    fun decode(source: RuntimeHistoricalAggregateSource): StockRelationalRecordV2 {
        val original = (source.original as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
        val value = LocalStockRelationalCodecV2.decode(sources.scope, source.row)
        localStockCheck(original.identity.scope == sources.scope && value.producer == original.identity.producer)
        return value
    }
    val rows = sources.sources().map(::decode)
    val reservationKey = LocalStockRelationalProfileV2.reservationKey(sources.reservationId)
    val issueKey = LocalStockRelationalProfileV2.issueKey(sources.issueId)
    val reservationBody = rows.singleOrNull { it.key == reservationKey }?.body as? StockRelationalBody.Reservation ?: inconsistentStock()
    val reservation = reservationBody.value
    val issue = (rows.singleOrNull { it.key == issueKey }?.body as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
    localStockCheck(reservation.id == sources.reservationId && issue.id == sources.issueId &&
        reservation.status == ReservationStatus.ISSUED && reservation.issueId == issue.id &&
        reservation.binding == issue.binding && reservation.allocationCount == issue.allocationCount &&
        reservation.holdCount == 0L && reservation.expiresAtEpochMillis == null &&
        sources.claim(LocalStockStoreProfile.reservationClaim(reservation.id)) == reservation.id &&
        sources.claim(LocalStockStoreProfile.sourceClaim(issue.id)) == issue.id)

    val firstSource = sources.firstIssueSource()
    val first = decode(firstSource)
    val original = (firstSource.original as? RetainedOriginalV2.Streamed)?.evidence ?: unavailableProducer()
    val firstHeader = (first.body as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
    localStockCheck(first.key == issueKey && first.generation == Revision(1) && first.previous == null &&
        firstHeader.revision == Revision(1) && firstHeader.startedStationIds.isEmpty() &&
        firstHeader.purpose == ValueIssuePurpose.DIRECT_RECIPE && firstHeader.disposition == KitchenDisposition.ACTIVE &&
        IssueV2Wire.from(copyIssueHeader(firstHeader, issue.revision, issue.startedStationIds, issue.disposition)) == IssueV2Wire.from(issue))

    val allocations = mutableListOf<InventoryAllocationRecord>()
    val slices = mutableListOf<InventoryIssueSlice>()
    var issueEvents = 0L
    for (row in rows) when (val body = row.body) {
        is StockRelationalBody.Reservation -> localStockCheck(row.key == reservationKey)
        is StockRelationalBody.Issue -> localStockCheck(row.key == issueKey)
        is StockRelationalBody.Allocation -> {
            val allocation = body.value.allocation
            localStockCheck(body.value.reservationId == reservation.id && allocation.allocationId == LocalStockRelationalProfileV2.allocationId(
                reservationBody.allocationSetId, reservationBody.allocationSetRevision, allocation.lineId, allocation.position) &&
                sources.claim(LocalStockStoreProfile.allocationClaim(allocation.allocationId)) == reservation.id)
            allocations += body.value
        }
        is StockRelationalBody.Hold -> inconsistentStock() // An ISSUED reservation retains no holds.
        is StockRelationalBody.IssueSlice -> {
            localStockCheck(body.value.issueId == issue.id)
            slices += body.value
        }
        is StockRelationalBody.ValueEvent -> {
            localStockCheck(body.issueId == issue.id)
            if (body.event == StockValueEventKind.ISSUE) {
                localStockCheck(body.sourceId == issue.id && row.generation == Revision(1) && row.previous == null && row.producer == first.producer)
                issueEvents = stockCheckedAdd(issueEvents, 1)
            }
            if (body.event == StockValueEventKind.RESTORE)
                localStockCheck(sources.claim(LocalStockStoreProfile.sourceClaim(body.sourceId)) == body.sourceId)
        }
        is StockRelationalBody.ValueSlice -> localStockCheck(body.issueId == issue.id)
        else -> inconsistentStock()
    }
    var total = Money(issue.originalValue.currency, 0)
    for (slice in slices) total += slice.originalValue
    val byAllocation = allocations.associateBy { it.allocation.allocationId }
    localStockCheck(allocations.size.toLong() == reservation.allocationCount && byAllocation.size == allocations.size &&
        slices.size.toLong() == issue.allocationCount && slices.map { it.allocation.allocationId }.distinct().size == slices.size &&
        slices.all { it.allocation == byAllocation[it.allocation.allocationId]?.allocation } && total == issue.originalValue && issueEvents > 0L)

    sources.requireExactWriters(rows.map { RuntimeHistoricalFoodCurrentWriter(it.key, it.generation, it.producer.operationId) },
        RuntimeHistoricalFoodCurrentWriter(first.key, first.generation, first.producer.operationId))
    return LocalStockCurrentFoodIssue(reservation, issue,
        LocalStockIssueOriginal(firstHeader, first.generation, first.digest, original), allocations, slices)
}
