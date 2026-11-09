package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.InventoryPostingCodec
import com.ukkera.pos.inventory.InventoryPostingEffect
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InventoryRetailPairValidationTest {
    private val f = RetailFixtures

    @Test fun exactKeysSchemasPositiveGenerationsFullScopeAndBothHashesAreRequired() {
        val current = f.one()
        val cut = current.cutRow
        val companion = current.companionRow
        for ((a, b) in listOf(cut.copy(key = AggregateKey("inventory.other", f.scope.branchId)) to companion,
            cut.copy(key = InventoryRetailStoreProfile.cutKey(f.id(80))) to companion,
            cut to companion.copy(key = InventoryRetailStoreProfile.cutKey(f.scope.branchId)),
            cut.copy(schema = "inventory.cut/v2") to companion,
            cut to companion.copy(schema = InventoryReceiptStoreProfile.COMPANION_SCHEMA),
            cut.copy(generation = Revision(0)) to companion.copy(generation = Revision(0)),
            cut.copy(generation = Revision(3)) to companion,
            cut.copy(digest = PayloadDigest("f".repeat(64))) to companion,
            cut to companion.copy(digest = PayloadDigest("f".repeat(64))))) {
            retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) { decodeRetailPair(f.scope, a, b) }
        }
        for (scope in listOf(BranchScope(f.id(81), f.scope.companyId, f.scope.branchId),
            BranchScope(f.scope.organizationId, f.id(82), f.scope.branchId), BranchScope(f.scope.organizationId, f.scope.companyId, f.id(83)))) {
            retailFailure { decodeRetailPair(scope, cut, companion) }
        }
        retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) {
            f.withCompanion(current, copyRetailCompanion(current.companion, cutDigest = PayloadDigest("f".repeat(64))))
        }
        retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) { InventoryRetailStoreProfile.toDomainRevision(Revision(0)) }
    }

    @Test fun receiptV1IsNeitherAnImplicitUpgradeNorACompatibleRetailCompanion() {
        val legacy = ReceiptFixtures.one()
        retailFailure(InventoryRetailStoreFailure.BINDING_MISMATCH) { decodeRetailPair(f.scope, legacy.cutRow, legacy.companionRow) }
        retailFailure { decodeRetailPair(f.scope, legacy.cutRow, legacy.companionRow.copy(schema = InventoryRetailStoreProfile.COMPANION_SCHEMA)) }
        val retail = f.one()
        receiptFailure(InventoryReceiptStoreFailure.BINDING_MISMATCH) { decodeReceiptPair(f.scope, retail.cutRow, retail.companionRow) }
        assertEquals(InventoryReceiptStoreProfile.cutKey(f.scope.branchId), InventoryRetailStoreProfile.cutKey(f.scope.branchId))
        assertEquals(InventoryReceiptStoreProfile.companionKey(f.scope.branchId), InventoryRetailStoreProfile.companionKey(f.scope.branchId))
        assertNotEquals(InventoryReceiptStoreProfile.COMPANION_SCHEMA, InventoryRetailStoreProfile.COMPANION_SCHEMA)
    }

    @Test fun olderReceiptQuantityValuePositionAndExpiryCannotBeReassociatedWhileKeepingTheCut() {
        val before = f.one()
        val current = f.current(f.receive(before, listOf(f.request(2, before.snapshot.revision).copy(lotId = f.id(23))), f.op(1_002)))
        val first = current.companion.receipts.first()
        val second = current.companion.receipts.last()
        for (bad in listOf(first.copy(quantity = Quantity(f.unit, 9, 0)), first.copy(originalValue = Money(Currency.EGP, 100)),
            first.copy(position = second.position), first.copy(expiresAtEpochMillis = first.expiresAtEpochMillis!! + 1),
            first.copy(position = first.position.copy(pool = first.position.pool.copy(skuId = f.id(84)))),
            first.copy(origin = second.origin), first.copy(receiptId = second.receiptId))) {
            retailFailure { f.withCompanion(current, copyRetailCompanion(current.companion, receipts = listOf(bad, second))) }
        }
        assertEquals(2, current.companion.receipts.size)
    }

    @Test fun chronologicalLotOverdrawCannotBeHiddenByLaterReceiptsWithTheSameFinalTotals() {
        val first = f.one()
        val reserved = f.current(f.project(first, f.reserve(first,
            allocations = listOf(f.allocation(1, first.snapshot.positions.single().key, 10)))))
        val reservation = reserved.snapshot.reservations.single()
        val issuePlan = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(reserved.snapshot, reserved.snapshot.revision,
            f.op(8_101), reservation.reservationId, reservation.revision, f.id(9_101), f.now)).plan
        val issued = f.current(f.project(reserved, issuePlan))
        val laterRequest = f.request(2, issued.snapshot.revision).copy(lotId = f.id(23))
        val current = f.current(f.receive(issued, listOf(laterRequest), f.op(1_002)))
        assertEquals(listOf(0L, 10L), current.snapshot.positions.map { it.onHand.scaled })
        ReceiptFixtures.roundTrip(current.snapshot) // Positive generic whole-cut control.

        // Rebind the initial A receipt to B and the later B receipt to A. Lifetime totals still
        // say A=0,B=10, but the original A issue occurred before any A receipt. Only the ordered
        // physical fold catches that historical overdraft; summing lifetime receipts would not.
        val earlier = current.companion.receipts.first()
        val later = current.companion.receipts.last()
        val batch = current.companion.latestBatch!!
        val changedText = jsonEdit(InventoryPostingCodec.encode(batch), listOf("effects", "1", "position", "lotId"),
            JsonPrimitive(earlier.position.lotId!!.value))
        val changedBatch = InventoryPostingCodec.decode(changedText, batch.binding)
        val changed = copyRetailCompanion(current.companion, receipts = listOf(earlier.copy(position = later.position), later.copy(position = earlier.position)),
            latestBatch = changedBatch)
        val bytes = InventoryRetailCompanionCodec.encode(changed) // Intrinsically valid bounded companion.
        assertEquals(bytes, InventoryRetailCompanionCodec.encode(InventoryRetailCompanionCodec.decode(bytes, retailBinding(changed))))
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { f.withCompanion(current, changed) }
    }

    @Test fun chronologicalExistingLotPrefixUnderflowCannotBeHiddenByARepairingLaterReceipt() {
        val initial = listOf(f.request(1), f.request(2).copy(lotId = f.id(23)),
            f.request(3).copy(quantity = Quantity(f.unit, 5, 0)))
        val received = f.current(f.receive(f.current(f.genesis()), initial))
        val a = received.companion.receipts[0].position
        val b = received.companion.receipts[1].position
        assertEquals(listOf(a, b, a), received.companion.receipts.map { it.position })
        assertEquals(a.copy(lotId = b.lotId), b) // Same pool/custody; only the lot differs.
        assertEquals(Quantity(f.unit, 15, 0), received.snapshot.position(a)!!.onHand)
        val reserved = f.current(f.project(received, f.reserve(received, allocations = listOf(f.allocation(1, a, 15)))))
        val reservation = reserved.snapshot.reservations.single()
        val issuePlan = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(reserved.snapshot, reserved.snapshot.revision,
            f.op(8_106), reservation.reservationId, reservation.revision, f.id(9_106), f.now)).plan
        val issued = f.current(f.project(reserved, issuePlan))
        val laterRequest = f.request(4, issued.snapshot.revision).copy(lotId = b.lotId, quantity = Quantity(f.unit, 5, 0))
        val current = f.current(f.receive(issued, listOf(laterRequest), f.op(1_002)))
        assertEquals(mapOf(a to Quantity(f.unit, 0, 0), b to Quantity(f.unit, 15, 0)),
            current.snapshot.positions.associate { it.key to it.onHand })
        ReceiptFixtures.decodeCut(current.snapshot, InventorySnapshotCodec.encode(current.snapshot)) // Genuine generic-cut control.
        val issue = current.snapshot.issues.single()
        assertEquals(initial.map { it.receiptId } + issue.issueId + laterRequest.receiptId, current.snapshot.sourceIds)
        val receipts = current.companion.receipts
        assertEquals(initial.map { it.receiptId } + laterRequest.receiptId, receipts.map { it.receiptId })

        // Move only the earlier A5 binding to B and the later B5 binding to A. IDs, owners,
        // origins, quantities, values, expiry and ordering stay intact; pool facts have no lot.
        val changedReceipts = receipts.mapIndexed { index, receipt -> when (index) {
            2 -> receipt.copy(position = b)
            3 -> receipt.copy(position = a)
            else -> receipt
        } }
        assertEquals(receipts, changedReceipts.mapIndexed { index, receipt -> receipt.copy(position = receipts[index].position) })
        assertEquals(listOf(a, b, b, a), changedReceipts.map { it.position })
        assertEquals(1, changedReceipts.map { it.position.pool }.distinct().size)
        assertEquals(1, changedReceipts.map { it.expiresAtEpochMillis }.distinct().size)
        assertEquals(4, changedReceipts.map { it.origin }.distinct().size)

        // Unlike the missing-lot regression, A already exists with +10 when its -15 issue
        // arrives. Quantity supports -5: removing only the issue-prefix nonnegative guard
        // would let the later +5 repair A to zero and leave B=15, matching the unchanged cut.
        val allocation = issue.allocations.single().allocation
        assertEquals(a, allocation.position)
        assertEquals(Quantity(f.unit, 15, 0), allocation.quantity)
        val earlierA = changedReceipts.take(3).single { it.position == a }
        assertEquals(initial.first().receiptId, earlierA.receiptId)
        assertEquals(Quantity(f.unit, 10, 0), earlierA.quantity)
        val afterIssue = earlierA.quantity - allocation.quantity
        assertEquals(Quantity(f.unit, -5, 0), afterIssue)
        assertEquals(current.snapshot.position(a)!!.onHand, afterIssue + changedReceipts.last().quantity)
        assertEquals(current.snapshot.position(b)!!.onHand, changedReceipts[1].quantity + changedReceipts[2].quantity)

        val batch = current.companion.latestBatch!!
        assertEquals(2, batch.effects.size)
        val movement = assertIs<InventoryPostingEffect.Movement>(batch.effects[1])
        assertEquals(laterRequest.receiptId, movement.sourceId)
        assertEquals(b, movement.position)
        val batchText = InventoryPostingCodec.encode(batch)
        val movementLotPath = listOf("effects", "1", "position", "lotId")
        val changedText = jsonEdit(batchText, movementLotPath, JsonPrimitive(a.lotId!!.value))
        val changedBatch = InventoryPostingCodec.decode(changedText, batch.binding)
        assertEquals(changedText, InventoryPostingCodec.encode(changedBatch)) // Modified A1 batch decodes before rejection.
        assertEquals(batchText, jsonEdit(changedText, movementLotPath, JsonPrimitive(b.lotId!!.value)))
        val changed = copyRetailCompanion(current.companion, receipts = changedReceipts, latestBatch = changedBatch)
        val bytes = InventoryRetailCompanionCodec.encode(changed)
        val decoded = InventoryRetailCompanionCodec.decode(bytes, retailBinding(changed))
        assertEquals(bytes, InventoryRetailCompanionCodec.encode(decoded)) // Canonical companion, not a scanner failure.
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { f.withCompanion(current, decoded) }
    }

    @Test fun crossPoolSourceOrderMustStillFollowProducingInventoryOperations() {
        val first = f.one()
        val current = f.current(f.receive(first, listOf(f.request(2, first.snapshot.revision).copy(skuId = f.id(24), lotId = f.id(25))), f.op(1_002)))
        val changedText = jsonEdit(InventorySnapshotCodec.encode(current.snapshot), listOf("state", "sourceIds"),
            JsonArray(current.snapshot.sourceIds.reversed().map { JsonPrimitive(it.value) }))
        val changedSnapshot = ReceiptFixtures.decodeCut(current.snapshot, changedText)
        val changedCompanion = copyRetailCompanion(current.companion, receipts = current.companion.receipts.reversed())
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) { rebind(current, changedSnapshot, changedCompanion) }
    }

    @Test fun missingReboundDuplicatedOrReceiptOwnedIssueIndexesAreRejected() {
        val received = f.one()
        val reserved = f.current(f.project(received, f.reserve(received)))
        val reservation = reserved.snapshot.reservations.single()
        val issued = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(reserved.snapshot, reserved.snapshot.revision,
            f.op(8_102), reservation.reservationId, reservation.revision, f.id(9_102), f.now)).plan
        val current = f.current(f.project(reserved, issued))
        val owner = current.companion.issueSources.single()
        for (bad in listOf(emptyList(), listOf(owner, owner), listOf(owner.copy(issueId = f.id(85))),
            listOf(owner.copy(issueId = current.companion.receipts.single().receiptId)),
            listOf(owner.copy(producingOperationId = current.companion.receipts.single().producingOperationId)),
            listOf(owner.copy(producingOperationId = f.op(86))))) {
            retailFailure { f.withCompanion(current, copyRetailCompanion(current.companion, issueSources = bad)) }
        }
    }

    @Test fun lastInventoryProducerAndImmediatePreviousOperationCannotBeReplacedWithAnOlderMember() {
        val first = f.one()
        val reserved = f.current(f.project(first, f.reserve(first)))
        val noOp = InventoryPlanner.expireReservations(reserved.snapshot, reserved.snapshot.revision, f.op(8_103), 101)
        val current = f.current(f.project(reserved, noOp, 101))
        val previous = current.companion.previous!!
        retailFailure {
            f.withCompanion(current, copyRetailCompanion(current.companion,
                previous = previous.copy(producerOperationId = first.companion.producer.operationId)))
        }
        val changedBatch = InventoryPostingCodec.decode(jsonEdit(InventoryPostingCodec.encode(current.companion.latestBatch!!), listOf("operationId"),
            JsonPrimitive(first.companion.producer.operationId.value)), current.companion.latestBatch!!.binding.copy(operationId = first.companion.producer.operationId))
        retailFailure {
            f.withCompanion(current, copyRetailCompanion(current.companion,
                producer = current.companion.producer.copy(operationId = first.companion.producer.operationId), latestBatch = changedBatch))
        }
    }

    @Test fun omittingAWholeReceiptPairCanPassA1DecodeButCannotHideItsCumulativeOwner() {
        val current = f.current(f.receive(f.current(f.genesis()), listOf(f.request(1), f.request(2))))
        val batch = current.companion.latestBatch!!
        val text = InventoryPostingCodec.encode(batch)
        val effects = jsonAt(text, "effects") as JsonArray
        val omitted = InventoryPostingCodec.decode(jsonEdit(text, listOf("effects"), JsonArray(effects.drop(2))), batch.binding)
        assertEquals(2, omitted.effects.size)
        retailFailure(InventoryRetailStoreFailure.INCONSISTENT_STATE) {
            f.withCompanion(current, copyRetailCompanion(current.companion, latestBatch = omitted))
        }
        assertEquals(4, current.companion.latestBatch!!.effects.size)
    }

    @Test fun sourceFreeIntermediateOmissionIsNotMistakenForAReservationBirthCertificate() {
        val first = f.one(f.request().copy(expiresAtEpochMillis = 1_000))
        val reserved = f.current(f.project(first, f.reserve(first, expires = 900)))
        val plan = InventoryPlanner.expireReservations(reserved.snapshot, reserved.snapshot.revision, f.op(8_104), 1_000)
        val complete = f.project(reserved, plan, 1_000)
        val current = f.current(complete)
        val batch = current.companion.latestBatch!!
        assertEquals(3, batch.effects.size)
        val encoded = InventoryPostingCodec.encode(batch)
        val effects = jsonAt(encoded, "effects") as JsonArray
        val omitted = InventoryPostingCodec.decode(jsonEdit(encoded, listOf("effects"), JsonArray(listOf(effects[0], effects[2]))), batch.binding)
        val observationOnly = f.withCompanion(current, copyRetailCompanion(current.companion, latestBatch = omitted))
        assertEquals(2, observationOnly.companion.latestBatch!!.effects.size)
        assertEquals(3, complete.companion.latestBatch!!.effects.size)
        assertEquals(plan.effects.size, complete.companion.latestBatch!!.effects.size)
        // No public decoded-batch-to-contribution entrypoint exists. Projection consumes the actual
        // plan and A1's live crosswalk. This pair-only observation has NOT approved a producer.
        assertEquals(f.compositeCommand, observationOnly.companion.producer.command)
    }

    @Test fun safetyOnlyPositionReorderingIsNotReplacedBySourceOrder() {
        val first = f.request(1).copy(expiresAtEpochMillis = 200)
        val second = f.request(2).copy(lotId = f.id(23), expiresAtEpochMillis = 500)
        val received = f.current(f.receive(f.current(f.genesis()), listOf(first, second)))
        val plan = InventoryPlanner.expireReservations(received.snapshot, received.snapshot.revision, f.op(8_105), 200)
        val current = f.current(f.project(received, plan, 200))
        assertEquals(listOf(second.lotId, first.lotId), current.snapshot.positions.map { it.key.lotId })
        assertEquals(received.snapshot.sourceIds, current.snapshot.sourceIds)
        assertEquals(StockSafety.EXPIRED, current.snapshot.positions.last().safety)
        assertIs<InventoryPostingEffect.SafetyChanged>(current.companion.latestBatch!!.effects.single())
    }

    private fun rebind(current: RetailObservedPair, snapshot: InventorySnapshot, companion: InventoryRetailCompanion): RetailObservedPair {
        val cut = StoredBytes(InventorySnapshotCodec.encode(snapshot).encodeToByteArray())
        val digest = sha256(cut.copyBytes())
        val next = copyRetailCompanion(companion, cutDigest = digest)
        val bytes = InventoryRetailCompanionCodec.encode(next)
        return decodeRetailPair(current.scope, current.cutRow.copy(payload = cut, digest = digest),
            current.companionRow.copy(payload = bytes, digest = sha256(bytes.copyBytes())))
    }
}
