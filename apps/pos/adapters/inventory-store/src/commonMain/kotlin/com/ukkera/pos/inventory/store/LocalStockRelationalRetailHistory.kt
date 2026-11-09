package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.types.*

/** Entire accepted-return inventory frontier, not the selected page or last restoration.
 * The owning repository still proves every literal source's complete installed producer.
 * No acceptance, authorization, carrying-cost replacement or history default is fabricated. */
internal suspend fun requireStockRetailIssueHistory(read: StockPoolHistoryAccess, issueId: DomainId) {
    val headerRow = read.normal(LocalStockRelationalProfileV2.issueKey(issueId)) ?: inconsistentStock()
    val header = (headerRow.body as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
    localStockCheck(header.id == issueId && header.purpose == ValueIssuePurpose.RETAIL &&
        header.revision == headerRow.generation && read.claim(LocalStockStoreProfile.sourceClaim(issueId)) == issueId,
        LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    // Historical pool producers retained the complete issue/value range, not necessarily the
    // issue owner's reservation/allocation BEFORE rows. Original allocation facts close to the
    // genuine first COLLECTION producer; do not manufacture new observations for old originals.
    var originalProducer: StreamedProducerIdentityV2? = null
    var count = 0L; var total = Money(header.originalValue.currency, 0); var originalEvents = 0L
    var postings = 0L; var currentProducerFound = false; var previousProducerFound = false
    fun original(producer: StreamedProducerIdentityV2) {
        localStockCheck(producer.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND &&
            (originalProducer == null || originalProducer == producer))
        originalProducer = producer
    }
    read.range(StockQueryV2.IssueValues(issueId)) { row -> when (val body = row.body) {
        is StockRelationalBody.IssueSlice -> {
            val slice = body.value
            localStockCheck(slice.issueId == issueId && slice.allocation.position.pool.ownership == StockOwnership.CompanyOwned &&
                slice.allocation.position.custody == StockCustody.RELEASED &&
                (slice.allocation.position.serialId == null || (slice.restoredQuantity.scaled == 0L && row.generation == Revision(1))))
            val issued = stockOriginalSliceEvent(read, row)
            original(issued.producer)
            requireStockRetailSliceHistory(read, row)
            count = stockCheckedAdd(count, 1); total += slice.originalValue
        }
        is StockRelationalBody.ValueEvent -> {
            localStockCheck(body.issueId == issueId && row.generation == Revision(1) && row.previous == null)
            requireStockRetailHistoryEvent(read, row)
            when (body.event) {
                StockValueEventKind.ISSUE -> {
                    original(row.producer); originalEvents = stockCheckedAdd(originalEvents, 1)
                }
                StockValueEventKind.RESTORE -> {
                    var representative = true
                    // One posting may span many pools. Count its actual permanent source once,
                    // without an ever-growing source-ID snapshot or materialized history set.
                    read.range(StockQueryV2.IssueValues(issueId)) { other ->
                        val event = other.body as? StockRelationalBody.ValueEvent
                        if (event?.event == StockValueEventKind.RESTORE) {
                            if (event.sourceId == body.sourceId) {
                                localStockCheck(other.producer == row.producer)
                                if (other.key.id.value < row.key.id.value) representative = false
                            } else localStockCheck(other.producer != row.producer)
                        }
                    }
                    if (representative) postings = stockCheckedAdd(postings, 1)
                    if (row.producer == headerRow.producer) currentProducerFound = true
                    if (row.producer.operationId == headerRow.previous?.operationId) previousProducerFound = true
                }
                else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
            }
        }
        is StockRelationalBody.ValueSlice -> {
            val eventRow = read.normal(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, body.eventId)) ?: inconsistentStock()
            val event = eventRow.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
            val current = (read.normal(LocalStockRelationalProfileV2.issueSliceKey(issueId, body.allocationId))?.body
                as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
            requireStockRetailHistoricalValueSlice(row, body, eventRow, event, current)
            if (event.event == StockValueEventKind.ISSUE) original(row.producer)
        }
        else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    } }
    val first = originalProducer ?: inconsistentStock()
    localStockCheck(originalEvents > 0 && count == header.allocationCount && total == header.originalValue &&
        headerRow.generation.value == stockCheckedAdd(postings, 1))
    if (postings == 0L) localStockCheck(headerRow.previous == null && headerRow.producer == first)
    else localStockCheck(headerRow.producer.command == LocalStockRelationalProfileV2.RETURN_COMMAND && currentProducerFound &&
        headerRow.previous?.revision?.value == postings &&
        (if (postings == 1L) headerRow.previous?.operationId == first.operationId else previousProducerFound))
}

/** Exact chronological cumulative restoration for ONE immutable allocation. Repeated complete
 * issue-range scans select the next real pool ordinal; ID order is never a rounding prefix.
 * This requires no newly invented pool range in an older producer's retained BEFORE source. */
internal suspend fun requireStockRetailSliceHistory(read: StockPoolHistoryAccess, row: StockRelationalRecordV2) {
    val slice = (row.body as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
    val header = (read.normal(LocalStockRelationalProfileV2.issueKey(slice.issueId))?.body
        as? StockRelationalBody.Issue)?.value ?: inconsistentStock()
    localStockCheck(header.purpose == ValueIssuePurpose.RETAIL && header.revision.value >= row.generation.value &&
        slice.allocation.position.pool.ownership == StockOwnership.CompanyOwned &&
        slice.allocation.position.custody == StockCustody.RELEASED &&
        (slice.allocation.position.serialId == null || (slice.restoredQuantity.scaled == 0L && row.generation == Revision(1))))
    val originalRow = stockOriginalSliceEvent(read, row)
    val issued = originalRow.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    localStockCheck(originalRow.producer.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND)
    val zero = slice.copy(restoredQuantity = stockZeroQuantity(slice.allocation.quantity))
    var cumulative = zero.restoredQuantity; var value = Money(slice.originalValue.currency, 0)
    var restorations = 0L; var ordinal = issued.ordinal
    var last = originalRow.producer; var previous = last
    while (true) {
        var next: StockRelationalRecordV2? = null; var originals = 0L; var actualRestorations = 0L
        read.range(StockQueryV2.IssueValues(slice.issueId)) { valueRow ->
            val body = valueRow.body as? StockRelationalBody.ValueSlice ?: return@range
            if (body.allocationId != slice.allocation.allocationId) return@range
            val eventRow = read.normal(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, body.eventId)) ?: inconsistentStock()
            val event = eventRow.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
            requireStockRetailHistoricalValueSlice(valueRow, body, eventRow, event, slice)
            when (event.event) {
                StockValueEventKind.ISSUE -> {
                    localStockCheck(originals == 0L && eventRow.key == originalRow.key && eventRow.producer == originalRow.producer)
                    originals = stockCheckedAdd(originals, 1)
                }
                StockValueEventKind.RESTORE -> {
                    localStockCheck(event.ordinal > issued.ordinal &&
                        read.claim(LocalStockStoreProfile.sourceClaim(event.sourceId)) == event.sourceId)
                    actualRestorations = stockCheckedAdd(actualRestorations, 1)
                    if (event.ordinal > ordinal) {
                        val selected = next
                        if (selected == null) next = valueRow
                        else {
                            val selectedBody = selected.body as? StockRelationalBody.ValueSlice ?: inconsistentStock()
                            if (event.ordinal < selectedBody.eventOrdinal) next = valueRow
                            else if (event.ordinal == selectedBody.eventOrdinal) localStockCheck(selected.key == valueRow.key)
                        }
                    }
                }
                else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
            }
        }
        localStockCheck(originals == 1L)
        val selected = next
        if (selected == null) { localStockCheck(actualRestorations == restorations); break }
        val body = selected.body as? StockRelationalBody.ValueSlice ?: inconsistentStock()
        val eventRow = read.normal(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, body.eventId)) ?: inconsistentStock()
        val amount = InventoryValuationProjection.originalRestorationValue(slice.copy(restoredQuantity = cumulative), body.quantity)
        localStockCheck(body.originalValue == amount)
        cumulative += body.quantity; value += body.originalValue
        previous = last; last = eventRow.producer; ordinal = body.eventOrdinal
        restorations = stockCheckedAdd(restorations, 1)
    }
    localStockCheck(cumulative == slice.restoredQuantity &&
        row.generation.value == stockCheckedAdd(restorations, 1) && row.producer == last)
    if (restorations == 0L) localStockCheck(row.previous == null && value.minor == 0L)
    else localStockCheck(row.previous?.revision?.value == restorations && row.previous?.operationId == previous.operationId &&
        value == InventoryValuationProjection.originalRestorationValue(zero, cumulative))
}

/** Complete bidirectional event membership, allowing only a selected positive original subset
 * for RETAIL. This is deliberately separate from the old whole-recipe restoration validator. */
internal suspend fun requireStockRetailHistoryEvent(read: StockPoolHistoryAccess, row: StockRelationalRecordV2) {
    val event = row.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
    val issueId = event.issueId ?: inconsistentStock()
    val header = (read.normal(LocalStockRelationalProfileV2.issueKey(issueId))?.body as? StockRelationalBody.Issue)?.value
        ?: inconsistentStock()
    localStockCheck(header.purpose == ValueIssuePurpose.RETAIL && row.generation == Revision(1) && row.previous == null &&
        row.key == stockValueEventKey(event.pool, event.sourceId) && event.sliceCount > 0)
    when (event.event) {
        StockValueEventKind.ISSUE -> localStockCheck(event.sourceId == issueId && event.quantityDelta.scaled < 0 &&
            event.originalValueDelta == event.carryingValueDelta &&
            row.producer.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND &&
            read.claim(LocalStockStoreProfile.sourceClaim(issueId)) == issueId)
        StockValueEventKind.RESTORE -> {
            val issuedRow = read.normal(stockValueEventKey(event.pool, issueId)) ?: inconsistentStock()
            val issued = issuedRow.body as? StockRelationalBody.ValueEvent ?: inconsistentStock()
            localStockCheck(event.sourceId != issueId && event.quantityDelta.scaled > 0 && event.originalValueDelta.minor >= 0 &&
                event.originalValueDelta == event.carryingValueDelta && row.producer.command == LocalStockRelationalProfileV2.RETURN_COMMAND &&
                issued.event == StockValueEventKind.ISSUE && issued.issueId == issueId && issued.sourceId == issueId && event.ordinal > issued.ordinal &&
                read.claim(LocalStockStoreProfile.sourceClaim(event.sourceId)) == event.sourceId)
        }
        else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    }
    val delta = if (event.event == StockValueEventKind.ISSUE) InventoryValuationProjection.issue(event.before, -event.quantityDelta)
        else InventoryValuationProjection.receive(event.before, event.quantityDelta, event.originalValueDelta)
    localStockCheck(event.after == delta.after && event.quantityDelta == delta.quantity && event.carryingValueDelta == delta.value)
    var count = 0L; var quantity = stockZeroQuantity(event.before.onHand); var value = Money(event.before.carryingValue.currency, 0)
    read.range(StockQueryV2.IssueValues(issueId)) { valueRow ->
        val body = valueRow.body as? StockRelationalBody.ValueSlice
        if (body?.eventId == row.key.id) {
            val current = (read.normal(LocalStockRelationalProfileV2.issueSliceKey(issueId, body.allocationId))?.body
                as? StockRelationalBody.IssueSlice)?.value ?: inconsistentStock()
            requireStockRetailHistoricalValueSlice(valueRow, body, row, event, current)
            count = stockCheckedAdd(count, 1); quantity += body.quantity; value += body.originalValue
        }
    }
    localStockCheck(count == event.sliceCount && count > 0 && quantity ==
        (if (event.event == StockValueEventKind.ISSUE) -event.quantityDelta else event.quantityDelta) && value ==
        (if (event.event == StockValueEventKind.ISSUE) -event.originalValueDelta else event.originalValueDelta))
}

private fun requireStockRetailHistoricalValueSlice(row: StockRelationalRecordV2, body: StockRelationalBody.ValueSlice,
    eventRow: StockRelationalRecordV2, event: StockRelationalBody.ValueEvent, current: InventoryIssueSlice) {
    localStockCheck(row.generation == Revision(1) && row.previous == null && eventRow.generation == Revision(1) &&
        eventRow.previous == null && row.producer == eventRow.producer && row.key == stockValueSliceKey(body.eventId, body.allocationId) &&
        body.issueId == current.issueId && event.issueId == current.issueId && body.pool == current.allocation.position.pool &&
        body.pool == event.pool && body.sourceId == event.sourceId && body.eventId == eventRow.key.id && body.eventOrdinal == event.ordinal &&
        body.quantity.unitId == current.allocation.quantity.unitId && body.quantity.scale == current.allocation.quantity.scale &&
        body.quantity.scaled > 0 && body.quantity <= current.allocation.quantity && body.originalValue.currency == current.originalValue.currency)
    when (event.event) {
        StockValueEventKind.ISSUE -> localStockCheck(body.sourceId == current.issueId && body.quantity == current.allocation.quantity &&
            body.originalValue == current.originalValue && row.producer.command == LocalStockRelationalProfileV2.COLLECTION_COMMAND)
        StockValueEventKind.RESTORE -> localStockCheck(current.allocation.position.serialId == null &&
            body.sourceId != current.issueId && body.originalValue.minor >= 0 &&
            row.producer.command == LocalStockRelationalProfileV2.RETURN_COMMAND)
        else -> throw LocalStockRejected(LocalStockFailure.UNSUPPORTED_VALUE_LINEAGE)
    }
}
