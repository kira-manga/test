package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Pair/codec correspondence only. Real retained evidence and current claims are lead-owned Room tests. */
class InventoryReceiptPairValidationTest {
    private val f = ReceiptFixtures

    @Test fun bothRowsRequireExactKeysSchemasAndMatchingStorageGenerations() {
        val current = f.one()
        val cut = current.cutRow
        val companion = current.companionRow
        val mismatched = listOf(cut.copy(key = AggregateKey("inventory.other", f.scope.branchId)) to companion,
            cut.copy(key = InventoryReceiptStoreProfile.cutKey(f.id(80))) to companion,
            cut to companion.copy(key = InventoryReceiptStoreProfile.cutKey(f.scope.branchId)),
            cut to companion.copy(key = InventoryReceiptStoreProfile.companionKey(f.id(81))),
            cut.copy(schema = "inventory.cut/v2") to companion,
            cut to companion.copy(schema = "inventory.companion/receipt-v2"),
            cut.copy(schema = companion.schema) to companion.copy(schema = cut.schema),
            cut.copy(generation = Revision(3)) to companion,
            cut to companion.copy(generation = Revision(3)))
        for ((badCut, badCompanion) in mismatched) receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) {
            decodeReceiptPair(f.scope, badCut, badCompanion)
        }
        assertEquals(current.cutRow.payload, decodeReceiptPair(f.scope, cut, companion).cutRow.payload)
    }

    @Test fun fullIndependentBranchScopeCannotBeTakenFromEitherStoredBody() {
        val current = f.one()
        for (scope in listOf(BranchScope(f.id(82), f.scope.companyId, f.scope.branchId),
            BranchScope(f.scope.organizationId, f.id(83), f.scope.branchId),
            BranchScope(f.scope.organizationId, f.scope.companyId, f.id(84)))) {
            receiptFailure { decodeReceiptPair(scope, current.cutRow, current.companionRow) }
        }
    }

    @Test fun realSuccessorCannotBeInsertedAtGenerationOneOrArbitrarilySeededAtAnotherGeneration() {
        val successor = f.current(f.receive(f.one(), 2))
        assertEquals(Revision(2), successor.snapshot.revision)
        assertEquals(Revision(3), successor.storageGeneration)
        for (generation in listOf(Revision(0), Revision(1), Revision(2), Revision(99))) receiptFailure {
            decodeReceiptPair(f.scope, successor.cutRow.copy(generation = generation), successor.companionRow.copy(generation = generation))
        }
        val genesis = f.current(f.genesis())
        receiptFailure { decodeReceiptPair(f.scope, genesis.cutRow.copy(generation = Revision(2)), genesis.companionRow.copy(generation = Revision(2))) }
        receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) { InventoryReceiptStoreProfile.toDomainRevision(Revision(0)) }
    }

    @Test fun payloadHashesAndEmbeddedCutDigestAreIndependentlyRequired() {
        val current = f.one()
        val otherDigest = PayloadDigest("f".repeat(64))
        assertNotEquals(current.cutRow.digest, otherDigest)
        assertNotEquals(current.companionRow.digest, otherDigest)
        for ((cut, companion) in listOf(current.cutRow.copy(digest = otherDigest) to current.companionRow,
            current.cutRow to current.companionRow.copy(digest = otherDigest),
            current.cutRow.copy(payload = StoredBytes(current.cutRow.payload.copyBytes() + byteArrayOf(32))) to current.companionRow,
            current.cutRow to current.companionRow.copy(payload = StoredBytes(current.companionRow.payload.copyBytes() + byteArrayOf(32))))) {
            receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) { decodeReceiptPair(f.scope, cut, companion) }
        }
        receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) {
            withCompanion(current, copyCompanion(current.companion, cutDigest = otherDigest))
        }
    }

    @Test fun historicalPairMixingAndAnOlderMemberProducerPointerAreRejected() {
        val previous = f.one()
        val current = f.current(f.receive(previous, 2))
        receiptFailure { decodeReceiptPair(f.scope, current.cutRow, previous.companionRow) }
        receiptFailure { decodeReceiptPair(f.scope, previous.cutRow, current.companionRow) }
        receiptFailure { decodeReceiptPair(f.scope, current.cutRow, previous.companionRow.copy(generation = current.storageGeneration)) }
        assertTrue(previous.companion.producer.operationId in current.snapshot.operationIds)
        val altered = jsonEdit(current.companionRow.payload.copyBytes().decodeToString(), listOf("producer", "operationId"),
            JsonPrimitive(previous.companion.producer.operationId.value))
        val bytes = StoredBytes(altered.encodeToByteArray())
        receiptFailure { decodeReceiptPair(f.scope, current.cutRow, current.companionRow.copy(payload = bytes, digest = sha256(bytes.copyBytes()))) }
    }

    @Test fun genericCodecValidPhysicalSplitIntoAnUnlistedPositionFailsReceiptCrosswalk() {
        val current = f.one()
        val encoded = InventorySnapshotCodec.encode(current.snapshot)
        val position = jsonAt(encoded, "state", "positions", "0").toString()
        val original = jsonEdit(position, listOf("onHand", "scaled"), JsonPrimitive("6"))
        var additional = jsonEdit(position, listOf("onHand", "scaled"), JsonPrimitive("4"))
        additional = jsonEdit(additional, listOf("key", "lotId"), JsonPrimitive(f.id(850).value))
        val split = jsonEdit(encoded, listOf("state", "positions"),
            JsonArray(listOf(Json.parseToJsonElement(original), Json.parseToJsonElement(additional))))
        val validDomainCut = f.decodeCut(current.snapshot, split)
        assertEquals(2, validDomainCut.positions.size)
        assertEquals(10L, validDomainCut.positions.sumOf { it.onHand.scaled })
        assertEquals(10L, validDomainCut.pools.single().value.onHand.scaled)
        // Generic validation knows the pool total, but only this companion pins the receipt's lot.
        receiptFailure(InventoryReceiptStoreFailure.INCONSISTENT_STATE) { f.replaceCut(current, validDomainCut) }
    }

    @Test fun olderReceiptLotExpiryWarehouseAndPhysicalTotalsCannotBeReassociated() {
        val current = f.twoLots()
        val encoded = InventorySnapshotCodec.encode(current.snapshot)
        var redistributed = jsonEdit(encoded, listOf("state", "positions", "0", "onHand", "scaled"), JsonPrimitive("9"))
        redistributed = jsonEdit(redistributed, listOf("state", "positions", "1", "onHand", "scaled"), JsonPrimitive("8"))
        var otherWarehouse = jsonEdit(encoded, listOf("state", "pools", "0", "key", "warehouseId"), JsonPrimitive(f.id(860).value))
        for (index in 0..1) otherWarehouse = jsonEdit(otherWarehouse,
            listOf("state", "positions", index.toString(), "key", "pool", "warehouseId"), JsonPrimitive(f.id(860).value))
        val changed = listOf(redistributed, otherWarehouse,
            jsonEdit(encoded, listOf("state", "positions", "0", "key", "lotId"), JsonPrimitive(f.id(861).value)),
            jsonEdit(encoded, listOf("state", "positions", "0", "expiresAtEpochMillis"), JsonPrimitive("2000000000010")))
        for (text in changed) {
            val validDomainCut = f.decodeCut(current.snapshot, text)
            assertEquals(17L, validDomainCut.pools.single().value.onHand.scaled)
            receiptFailure(InventoryReceiptStoreFailure.INCONSISTENT_STATE) { f.replaceCut(current, validDomainCut) }
        }
    }

    @Test fun genericValidEarlierReceiptQuantityAndValueRewritesCannotHideBehindLatestEffects() {
        val current = f.twoLots()
        val encoded = InventorySnapshotCodec.encode(current.snapshot)
        var quantity = jsonEdit(encoded, listOf("state", "pools", "0", "value", "facts", "0", "quantity", "scaled"), JsonPrimitive("11"))
        quantity = jsonEdit(quantity, listOf("state", "pools", "0", "value", "onHand", "scaled"), JsonPrimitive("18"))
        quantity = jsonEdit(quantity, listOf("state", "positions", "0", "onHand", "scaled"), JsonPrimitive("11"))
        var value = jsonEdit(encoded, listOf("state", "pools", "0", "value", "facts", "0", "originalValue", "minor"), JsonPrimitive("102"))
        value = jsonEdit(value, listOf("state", "pools", "0", "value", "carryingValue", "minor"), JsonPrimitive("155"))
        for (text in listOf(quantity, value)) {
            val validDomainCut = f.decodeCut(current.snapshot, text)
            val latest = assertIs<ValuationFact.Receipt>(validDomainCut.pools.single().value.facts.last())
            assertEquals(current.companion.sources.last().receiptId, latest.factId)
            assertEquals(current.companion.sources.last().quantity, latest.quantity)
            assertEquals(current.companion.sources.last().originalValue, latest.originalValue)
            receiptFailure(InventoryReceiptStoreFailure.INCONSISTENT_STATE) { f.replaceCut(current, validDomainCut) }
        }
    }

    @Test fun earlierCompanionReceiptIdentityPositionQuantityValueAndExpiryMustMatchTheCut() {
        val current = f.twoLots()
        val first = current.companion.sources.first()
        val last = current.companion.sources.last()
        for (changed in listOf(first.copy(receiptId = f.id(870)), first.copy(position = first.position.copy(lotId = f.id(871))),
            first.copy(quantity = first.quantity.copy(scaled = 11)), first.copy(originalValue = first.originalValue.copy(minor = 102)),
            first.copy(expiresAtEpochMillis = 2_000_000_000_001))) {
            val companion = copyCompanion(current.companion, sources = listOf(changed, last))
            InventoryReceiptCompanionCodec.decode(InventoryReceiptCompanionCodec.encode(companion), companionBinding(companion))
            receiptFailure(InventoryReceiptStoreFailure.INCONSISTENT_STATE) { withCompanion(current, companion) }
        }
        val changedOperation = first.copy(producingOperationId = f.op(872))
        val changed = copyCompanion(current.companion, sources = listOf(changedOperation, last),
            previous = current.companion.previous!!.copy(producerOperationId = changedOperation.producingOperationId))
        receiptFailure(InventoryReceiptStoreFailure.INCONSISTENT_STATE) { withCompanion(current, changed) }
    }

    @Test fun independentlyValidSourceAndOperationIndexOrderingStillMustMatchTheCompanion() {
        val current = f.current(f.receive(f.one(), 2, f.request(2).copy(warehouseId = f.id(24))))
        val encoded = InventorySnapshotCodec.encode(current.snapshot)
        val sourceIds = JsonArray(current.snapshot.sourceIds.reversed().map { JsonPrimitive(it.value) })
        val operationIds = JsonArray(current.snapshot.operationIds.reversed().map { JsonPrimitive(it.value) })
        for (text in listOf(jsonEdit(encoded, listOf("state", "sourceIds"), sourceIds),
            jsonEdit(encoded, listOf("state", "operationIds"), operationIds))) {
            val validDomainCut = f.decodeCut(current.snapshot, text)
            receiptFailure(InventoryReceiptStoreFailure.INCONSISTENT_STATE) { f.replaceCut(current, validDomainCut) }
        }
    }

    @Test fun genericOpeningValuationReceiptIsNotAReceiptProfilePurchase() {
        val current = f.one()
        val encoded = jsonEdit(InventorySnapshotCodec.encode(current.snapshot),
            listOf("state", "pools", "0", "value", "facts", "0", "origin"), JsonPrimitive("OPENING"))
        val opening = f.decodeCut(current.snapshot, encoded)
        assertEquals(ReceiptValueOrigin.OPENING, assertIs<ValuationFact.Receipt>(opening.pools.single().value.facts.single()).origin)
        receiptFailure(InventoryReceiptStoreFailure.UNSUPPORTED_STATE) { f.replaceCut(current, opening) }
    }

    @Test fun outerStoredBytesCapAndStrictCutUtf8ApplyBeforeStorageParsing() {
        // Both row payloads use the kernel wrapper: an oversized row cannot reach pair decoding.
        assertEquals(InventoryReceiptStoreProfile.MAX_ROW_BYTES,
            StoredBytes(ByteArray(InventoryReceiptStoreProfile.MAX_ROW_BYTES) { 32 }).size)
        assertFailsWith<IllegalArgumentException> {
            StoredBytes(ByteArray(InventoryReceiptStoreProfile.MAX_ROW_BYTES + 1) { 32 })
        }
        val current = f.one()
        val invalid = StoredBytes(byteArrayOf(0xc3.toByte(), 0x28))
        receiptFailure(InventoryReceiptStoreFailure.MALFORMED) {
            decodeReceiptPair(f.scope, current.cutRow.copy(payload = invalid, digest = sha256(invalid.copyBytes())), current.companionRow)
        }
    }

    @Test fun purePairDecodingDoesNotClaimThatAConsistentHistoricalPairIsTheCurrentFrontier() {
        val old = f.one()
        val newer = f.current(f.receive(old, 2))
        assertEquals(Revision(2), newer.snapshot.revision)
        val historical = decodeReceiptPair(f.scope, old.cutRow, old.companionRow)
        assertEquals(Revision(1), historical.snapshot.revision)
        assertEquals(old.cutRow.payload, historical.cutRow.payload)
        // This is NOT a rollback runtime simulation. Old rows plus coherent old claims can still
        // pass under a synthetic accepting verifier; a separate real-Room case covers that path.
        // Neither a hash match nor the single retained producer read supplies a protected frontier.
    }

    private fun withCompanion(current: InventoryReceiptReadCut, companion: InventoryReceiptCompanion): InventoryReceiptReadCut {
        val bytes = InventoryReceiptCompanionCodec.encode(companion)
        return decodeReceiptPair(current.scope, current.cutRow, current.companionRow.copy(payload = bytes, digest = sha256(bytes.copyBytes())))
    }
}
