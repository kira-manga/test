package com.ukkera.pos.sales

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Validation facts only, never a persisted profile or employee authority. */
internal class StoredAttemptOrigin(val operation: OperationId, val tender: Tender,
    val actor: DomainId, val session: DomainId, val originalPayment: DomainId? = null)

/** A physical attempt owns one unique intent. Exact logical exchange slices instead
 * share one command/revision; their exchange, return, actor and session must all agree. */
internal fun validateStoredAttemptOrigins(attempts: List<StoredAttemptOrigin>): Int {
    val groups = attempts.groupBy { it.operation }
    val exchanges = mutableSetOf<DomainId>()
    for (group in groups.values) {
        val first = group.first()
        val offset = first.tender as? Tender.ExchangeOffset
        if (offset == null) snapshotCheck(group.size == 1)
        else {
            snapshotCheck(group.size <= 16 && exchanges.add(offset.exchangeId))
            for (item in group) {
                val selected = item.tender as? Tender.ExchangeOffset
                    ?: throw SalesSnapshotRejectedException(SalesSnapshotFailure.INCONSISTENT_STATE)
                snapshotCheck(selected.exchangeId == offset.exchangeId && selected.returnId == offset.returnId &&
                    item.actor == first.actor && item.session == first.session)
            }
            val originals = group.mapNotNull { it.originalPayment }
            snapshotCheck(originals.isEmpty() || originals.size == group.size && originals.distinct().size == originals.size)
        }
    }
    return groups.size
}
