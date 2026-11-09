package com.ukkera.pos.inventory

import com.ukkera.pos.types.DomainId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal fun storedInventoryBinding(snapshot: InventorySnapshot): InventorySnapshotBinding = InventorySnapshotBinding(
    InventorySnapshotKind.BRANCH_INVENTORY, snapshot.scope, snapshot.scope.branchId, snapshot.revision)

internal fun storedInventoryRoundTrip(snapshot: InventorySnapshot): InventorySnapshot {
    val encoded = InventorySnapshotCodec.encode(snapshot)
    val restored = InventorySnapshotCodec.decode(encoded, storedInventoryBinding(snapshot))
    assertEquals(encoded, InventorySnapshotCodec.encode(restored))
    assertEquals(snapshot.scope, restored.scope)
    assertEquals(snapshot.revision, restored.revision)
    assertEquals(snapshot.positions, restored.positions)
    assertEquals(snapshot.operationIds, restored.operationIds)
    assertEquals(snapshot.sourceIds, restored.sourceIds)
    return restored
}

internal fun storedInventoryFailure(
    snapshot: InventorySnapshot,
    encoded: String,
    failure: InventorySnapshotFailure? = null,
) {
    val actual = assertFailsWith<InventorySnapshotRejectedException> {
        InventorySnapshotCodec.decode(encoded, storedInventoryBinding(snapshot))
    }
    if (failure != null) assertEquals(failure, actual.failure)
    assertEquals(actual.failure.name, actual.message) // Never include the protected body/parser transcript.
}

internal fun inventoryJsonAt(encoded: String, vararg path: String): JsonElement {
    var node = Json.parseToJsonElement(encoded)
    for (part in path) node = when (node) {
        is JsonObject -> node.getValue(part)
        is JsonArray -> node[part.toInt()]
        else -> error("Invalid test path")
    }
    return node
}

internal fun inventoryJsonEdit(encoded: String, path: List<String>, replacement: JsonElement): String {
    fun change(node: JsonElement, index: Int): JsonElement {
        if (index == path.size) return replacement
        return when (node) {
            is JsonObject -> JsonObject(node.toMutableMap().also { it[path[index]] = change(it.getValue(path[index]), index + 1) })
            is JsonArray -> JsonArray(node.toMutableList().also { it[path[index].toInt()] = change(it[path[index].toInt()], index + 1) })
            else -> error("Invalid test path")
        }
    }
    return change(Json.parseToJsonElement(encoded), 0).toString()
}

internal fun storedInventoryCorrection(f: InventoryFixture, receipt: DomainId, amount: Long, source: DomainId = f.ids.next(),
    poolKey: StockPoolKey = pool): DomainId {
    f.snapshot = InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), poolKey,
        f.snapshot.pool(poolKey)!!.value.revision, f.ids.next(), source, receipt, money(amount)).nextSnapshot
    return source
}

internal fun storedInventoryReturn(f: InventoryFixture, issueId: DomainId, quantity: Long, now: Long = 1,
    destination: StockPositionKey = f.key) {
    val issue = f.snapshot.issue(issueId)!!
    val allocation = issue.allocations.single().allocation
    f.snapshot = InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), issueId,
        issue.revision, listOf(StockRestoration(allocation.allocationId,
            com.ukkera.pos.types.Quantity(allocation.quantity.unitId, quantity, allocation.quantity.scale), destination)), now).nextSnapshot
}

internal data class StoredFreightFixture(val fixture: InventoryFixture, val receiptId: DomainId, val issueId: DomainId, val costSource: DomainId)

internal fun storedFreightFixture(): StoredFreightFixture {
    val f = InventoryFixture()
    val receipt = f.receive(10, 20_000)
    f.receive(10, 30_000)
    val reservation = f.reserve(5, CollectionExposure.PAID)
    val issue = f.issue(reservation.proof.reservationId) as ReservationIssueResult.Issued
    val source = storedInventoryCorrection(f, receipt, 12_000)
    return StoredFreightFixture(f, receipt, issue.proof.issueId, source)
}
