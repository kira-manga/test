package com.ukkera.pos.inventory.store

import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.ReservationIssueResult
import com.ukkera.pos.inventory.ReservationStatus
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InventoryRetailBoundsTest {
    private val f = RetailFixtures

    @Test fun allOneHundredTwentyEightInventoryOperationsCountIncludingSourceFreeEmptyBatches() {
        var current = f.current(f.genesis())
        for (index in 1..128) {
            val plan = InventoryPlanner.expireReservations(current.snapshot, current.snapshot.revision, f.op(20_000 + index), f.now)
            val contribution = f.project(current, plan)
            assertTrue(contribution.claims.isEmpty())
            current = f.current(contribution)
        }
        assertEquals(Revision(128), current.snapshot.revision)
        assertEquals(Revision(129), current.storageGeneration)
        assertEquals(128, current.snapshot.operationIds.size)
        assertTrue(current.snapshot.sourceIds.isEmpty())
        assertTrue(current.companion.latestBatch!!.effects.isEmpty())
        val next = InventoryPlanner.expireReservations(current.snapshot, current.snapshot.revision, f.op(20_129), f.now)
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) { f.project(current, next) }
    }

    @Test fun reservationCeilingCountsRetainedIssuedReservationsNotOnlyActiveClaims() {
        val received = f.one(f.request().copy(quantity = Quantity(f.unit, 100, 0)))
        val first = f.current(f.project(received, f.reserve(received)))
        val reservation = first.snapshot.reservations.single()
        val issue = assertIs<ReservationIssueResult.Issued>(InventoryPlanner.issueReservation(first.snapshot, first.snapshot.revision,
            f.op(21_000), reservation.reservationId, reservation.revision, f.id(22_000), f.now)).plan
        var current = f.current(f.project(first, issue))
        for (index in 2..32) current = f.current(f.project(current, f.reserve(current, n = index)))
        assertEquals(32, current.snapshot.reservations.size)
        assertEquals(31, current.snapshot.reservations.count { it.retainsStockClaim })
        assertEquals(1, current.snapshot.reservations.count { it.status == ReservationStatus.ISSUED })
        assertEquals(1, current.snapshot.issues.size)
        val next = f.reserve(current, n = 33)
        assertEquals(32, next.nextSnapshot.reservations.count { it.retainsStockClaim }) // Active-only counting would incorrectly admit it.
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) { f.project(current, next) }
    }

    @Test fun cumulativeIndexCapsAreAdditionalLimitsNotPromisesThatAnyMaximumWholeCutFits() {
        val base = f.one().companion
        val receipts = (1..32).map { n ->
            val request = f.request(n)
            InventoryReceiptSourceBinding(request.receiptId, base.producer.operationId, request.origin, request.toReceipt(f.scope).position,
                request.quantity, request.originalValue, request.expiresAtEpochMillis)
        }
        val issues = (1..32).map { InventoryRetailIssueSourceBinding(f.id(23_000 + it), f.op(24_000 + it)) }
        // Codec-only count controls. No whole-cut correspondence/provenance is claimed for these indexes.
        val maximalIndexes = copyRetailCompanion(base, receipts = receipts, issueSources = issues)
        assertEquals(64, maximalIndexes.receipts.size + maximalIndexes.issueSources.size)
        assertTrue(InventoryRetailCompanionCodec.encode(maximalIndexes).size <= InventoryRetailStoreProfile.MAX_ROW_BYTES)
        val extra = receipts.last().copy(receiptId = f.id(25_000), origin = FixtureReceiptOrigin(f.id(25_001), f.id(25_002)))
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) { copyRetailCompanion(base, receipts = receipts + extra) }
        retailFailure(InventoryRetailStoreFailure.TOO_LARGE) {
            copyRetailCompanion(base, issueSources = issues + InventoryRetailIssueSourceBinding(f.id(25_003), f.op(25_004)))
        }
    }
}
