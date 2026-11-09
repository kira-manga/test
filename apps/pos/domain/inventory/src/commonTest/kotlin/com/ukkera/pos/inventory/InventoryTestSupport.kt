package com.ukkera.pos.inventory

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal fun id(number: Int): DomainId = DomainId("00000000-0000-0000-0000-${number.toString(16).padStart(12, '0')}")
internal val unit: DomainId = id(1)
internal val scope: InventoryScope = InventoryScope(id(2), id(3))
internal val pool: StockPoolKey = StockPoolKey(scope, id(4), id(5))
internal fun quantity(scaled: Long, scale: Int = 0): Quantity = Quantity(unit, scaled, scale)
internal fun money(minor: Long): Money = Money(Currency.EGP, minor)
internal fun binding(exposure: CollectionExposure = CollectionExposure.UNCOLLECTED): CollectionBinding =
    CollectionBinding(id(10), Revision(2), id(11), id(12), exposure)

internal class IdSequence(private var next: Int = 1_000) {
    fun next(): DomainId = id(next++)
    fun operation(): OperationId = OperationId(next().value)
}

internal fun fails(reason: InventoryFailure, block: () -> Unit) {
    assertEquals(reason, assertFailsWith<InventoryRejection>(block = block).failure)
}

internal class InventoryFixture {
    val ids = IdSequence()
    var snapshot = InventorySnapshot.empty(scope)
    val key = StockPositionKey(pool, id(20))

    fun receive(q: Long, value: Long, key: StockPositionKey = this.key, expires: Long? = null): DomainId {
        val receiptId = ids.next()
        snapshot = InventoryPlanner.receive(snapshot, snapshot.revision, ids.operation(),
            listOf(StockReceipt(receiptId, key, quantity(q), money(value), expires))).nextSnapshot
        return receiptId
    }

    fun reserve(q: Long, exposure: CollectionExposure = CollectionExposure.UNCOLLECTED,
        key: StockPositionKey = this.key, expires: Long? = null): ReservationPlan {
        val result = InventoryPlanner.reserve(snapshot, snapshot.revision, ids.operation(), ids.next(), binding(exposure),
            listOf(StockAllocation(ids.next(), id(30), key, quantity(q))), 1, expires)
        snapshot = result.plan.nextSnapshot
        return result
    }

    fun issue(reservationId: DomainId, now: Long = 1, recipe: RecipeIssueRequest? = null): ReservationIssueResult {
        val reservation = snapshot.reservation(reservationId)!!
        val result = InventoryPlanner.issueReservation(snapshot, snapshot.revision, ids.operation(), reservationId,
            reservation.revision, ids.next(), now,
            if (recipe == null) ValueIssuePurpose.RETAIL else ValueIssuePurpose.DIRECT_RECIPE, recipe)
        snapshot = when (result) {
            is ReservationIssueResult.Issued -> result.plan.nextSnapshot
            is ReservationIssueResult.Held -> result.plan.nextSnapshot
        }
        return result
    }
}
