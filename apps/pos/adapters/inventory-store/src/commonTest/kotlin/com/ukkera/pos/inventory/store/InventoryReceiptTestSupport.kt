package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotBinding
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.InventorySnapshotKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Synthetic pure fixture seams, not a database, protected genesis or authorization implementation. */
internal object ReceiptFixtures {
    fun id(n: Int): DomainId = DomainId("00000000-0000-4000-8000-${n.toString(16).padStart(12, '0')}")
    fun op(n: Int): OperationId = OperationId(id(n).value)
    val scope = BranchScope(id(1), id(2), id(3))
    val actor = id(4)
    val unit = id(5)

    fun request(n: Int = 1, revision: Revision = Revision(n.toLong() - 1)): InventoryFixtureReceiveRequest = InventoryFixtureReceiveRequest(
        revision, id(100 + n), id(20), id(21), id(22), Quantity(unit, 10, 0), Money(Currency.EGP, 101),
        2_000_000_000_000, FixtureReceiptOrigin(id(200 + n), id(300 + n)),
    )

    fun context(request: InventoryFixtureReceiveRequest, n: Int = 1): ReceiptContext = context(
        InventoryReceiptStoreProfile.RECEIVE_COMMAND, InventoryReceiptCommandCodec.encode(request), op(1_000 + n))

    fun context(key: CommandKey, body: StoredBytes, operation: OperationId): ReceiptContext = ReceiptContext(scope, actor, operation,
        key, semanticDigest(scope, actor, key, body))

    fun genesis(): InventoryReceiptWriteSet = fixtureReceiptGenesis(context(InventoryReceiptStoreProfile.GENESIS_COMMAND,
        InventoryReceiptCommandCodec.genesisRequest(), op(999)))

    fun current(writes: InventoryReceiptWriteSet): InventoryReceiptReadCut {
        val cut = writes.changes.single { it.key.kind == InventoryReceiptStoreProfile.CUT_KIND }
        val companion = writes.changes.single { it.key.kind == InventoryReceiptStoreProfile.COMPANION_KIND }
        return decodeReceiptPair(scope,
            ReceiptStoredRow(cut.key, (cut.expectedRevision ?: Revision(0)).next(), cut.schema, cut.payload, sha256(cut.payload.copyBytes())),
            ReceiptStoredRow(companion.key, (companion.expectedRevision ?: Revision(0)).next(), companion.schema, companion.payload, sha256(companion.payload.copyBytes())))
    }

    fun receive(current: InventoryReceiptReadCut, n: Int = 1, request: InventoryFixtureReceiveRequest = request(n, current.snapshot.revision)): InventoryReceiptWriteSet {
        val context = context(request, n)
        val plan = InventoryPlanner.receive(current.snapshot, request.expectedRevision, context.operationId, listOf(request.toReceipt(scope)))
        return translateReceipt(current, context, request, plan)
    }

    fun one(): InventoryReceiptReadCut = current(receive(current(genesis())))

    fun twoLots(): InventoryReceiptReadCut = current(receive(one(), 2, request(2).copy(lotId = id(23),
        quantity = Quantity(unit, 7, 0), originalValue = Money(Currency.EGP, 53), expiresAtEpochMillis = 2_000_000_001_000)))

    fun roundTrip(snapshot: InventorySnapshot): InventorySnapshot = InventorySnapshotCodec.decode(InventorySnapshotCodec.encode(snapshot),
        InventorySnapshotBinding(InventorySnapshotKind.BRANCH_INVENTORY, snapshot.scope, snapshot.scope.branchId, snapshot.revision))

    /** Positive generic-codec control for a structurally valid but receipt-profile-invalid cut. */
    fun decodeCut(snapshot: InventorySnapshot, encoded: String): InventorySnapshot = InventorySnapshotCodec.decode(encoded,
        InventorySnapshotBinding(InventorySnapshotKind.BRANCH_INVENTORY, snapshot.scope, snapshot.scope.branchId, snapshot.revision))
        .also { assertEquals(encoded, InventorySnapshotCodec.encode(it)) }

    fun replaceCut(current: InventoryReceiptReadCut, snapshot: InventorySnapshot): InventoryReceiptReadCut {
        val bytes = StoredBytes(InventorySnapshotCodec.encode(snapshot).encodeToByteArray())
        val companion = copyCompanion(current.companion, cutDigest = sha256(bytes.copyBytes()))
        val encoded = InventoryReceiptCompanionCodec.encode(companion)
        return decodeReceiptPair(scope, current.cutRow.copy(payload = bytes, digest = sha256(bytes.copyBytes())),
            current.companionRow.copy(payload = encoded, digest = sha256(encoded.copyBytes())))
    }
}

internal fun copyCompanion(
    value: InventoryReceiptCompanion,
    scope: BranchScope = value.scope,
    id: DomainId = value.id,
    revision: Revision = value.inventoryRevision,
    cutDigest: PayloadDigest = value.cutDigest,
    producer: InventoryReceiptProducer = value.producer,
    previous: InventoryReceiptPreviousPair? = value.previous,
    sources: List<InventoryReceiptSourceBinding> = value.sources,
    effects: List<InventoryReceiptPostedEffect> = value.effects,
): InventoryReceiptCompanion = InventoryReceiptCompanion(scope, id, revision, cutDigest, producer, previous, sources, effects)

internal fun companionBinding(value: InventoryReceiptCompanion): InventoryReceiptCompanionBinding =
    InventoryReceiptCompanionBinding(value.scope, value.id, value.inventoryRevision)

internal fun receiptFailure(expected: InventoryReceiptStoreFailure? = null, block: () -> Unit): InventoryReceiptStoreRejected =
    assertFailsWith<InventoryReceiptStoreRejected>(block = block).also {
        if (expected != null) assertEquals(expected, it.failure)
        assertEquals(it.failure.name, it.message)
    }

internal fun jsonEdit(encoded: String, path: List<String>, replacement: JsonElement): String {
    fun change(node: JsonElement, index: Int): JsonElement {
        if (index == path.size) return replacement
        val part = path[index]
        return when (node) {
            is JsonObject -> JsonObject(node.toMutableMap().also { it[part] = change(it.getValue(part), index + 1) })
            is JsonArray -> JsonArray(node.toMutableList().also { it[part.toInt()] = change(it[part.toInt()], index + 1) })
            else -> error("Invalid fixture path")
        }
    }
    return change(Json.parseToJsonElement(encoded), 0).toString()
}

internal fun jsonAt(encoded: String, vararg path: String): JsonElement {
    var node = Json.parseToJsonElement(encoded)
    for (part in path) node = when (val current = node) {
        is JsonObject -> current.getValue(part)
        is JsonArray -> current[part.toInt()]
        else -> error("Invalid fixture path")
    }
    return node
}
