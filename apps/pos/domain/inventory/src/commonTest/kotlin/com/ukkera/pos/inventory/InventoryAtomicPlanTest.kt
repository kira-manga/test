package com.ukkera.pos.inventory

import com.ukkera.pos.types.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InventoryAtomicPlanTest {
    @Test fun twentyFourCustodyIncludesTwentyTwoOwnedAndTwoSupplierRejectedWithoutValuationLeak() {
        val f = InventoryFixture()
        val rejected = f.key.copy(pool = pool.copy(ownership = StockOwnership.SupplierOwned(id(400))), custody = StockCustody.REJECTED_HELD)
        val plan = InventoryPlanner.receive(f.snapshot, f.snapshot.revision, f.ids.operation(), listOf(
            StockReceipt(f.ids.next(), f.key, quantity(22), money(2_200)),
            StockReceipt(f.ids.next(), rejected, quantity(2), null),
        ))
        f.snapshot = plan.nextSnapshot
        assertEquals(24L, f.snapshot.positions.sumOf { it.onHand.scaled })
        assertEquals(quantity(22), f.snapshot.available(f.key, 1))
        assertEquals(quantity(0), f.snapshot.available(rejected, 1))
        assertEquals(1, f.snapshot.pools.size)
        assertEquals(quantity(22), f.snapshot.pool(pool)!!.value.onHand)
        assertEquals(money(2_200), f.snapshot.pool(pool)!!.value.carryingValue)
        fails(InventoryFailure.WRONG_OWNERSHIP) {
            InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), binding(),
                listOf(StockAllocation(f.ids.next(), id(30), rejected, quantity(1))), 1)
        }
        assertFalse(plan.effects.any { it is InventoryEffect.Valuation && it.pool == rejected.pool })
    }

    @Test fun receiptBatchDoesNotPartiallyApplyWhenOneCustodyLineIsInvalid() {
        val f = InventoryFixture()
        val before = f.snapshot
        val rejected = f.key.copy(pool = pool.copy(ownership = StockOwnership.SupplierOwned(id(400))), custody = StockCustody.REJECTED_HELD)
        fails(InventoryFailure.WRONG_OWNERSHIP) {
            InventoryPlanner.receive(before, before.revision, f.ids.operation(), listOf(
                StockReceipt(f.ids.next(), f.key, quantity(22), money(2_200)),
                StockReceipt(f.ids.next(), rejected, quantity(2), money(200)),
            ))
        }
        assertSame(before, f.snapshot)
        assertTrue(before.positions.isEmpty())
        assertTrue(before.pools.isEmpty())
        assertTrue(before.operationIds.isEmpty())
    }

    @Test fun warehouseSkuOwnershipAndLotKeysCannotBeConflated() {
        val f = InventoryFixture()
        f.receive(2, 200)
        val anotherWarehouse = f.key.copy(pool = pool.copy(warehouseId = id(401)))
        val anotherSku = f.key.copy(pool = pool.copy(skuId = id(402)))
        f.receive(2, 600, anotherWarehouse)
        f.receive(2, 1_000, anotherSku)
        val r = f.reserve(2, key = f.key)
        val issue = f.issue(r.proof.reservationId) as ReservationIssueResult.Issued
        assertEquals(money(200), issue.proof.costedAllocations.single().originalValue)
        assertEquals(quantity(2), f.snapshot.available(anotherWarehouse, 1))
        assertEquals(money(600), f.snapshot.pool(anotherWarehouse.pool)!!.value.carryingValue)
        assertEquals(money(1_000), f.snapshot.pool(anotherSku.pool)!!.value.carryingValue)
        val wrongScope = anotherWarehouse.copy(pool = anotherWarehouse.pool.copy(scope = scope.copy(branchId = id(403))))
        fails(InventoryFailure.WRONG_SCOPE) {
            InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), binding(),
                listOf(StockAllocation(f.ids.next(), id(30), wrongScope, quantity(1))), 1)
        }
    }

    @Test fun physicalFefoLotChoiceDoesNotChangePooledIssueCost() {
        fun issueFrom(lot: Int): Long {
            val f = InventoryFixture()
            f.receive(10, 20_000, f.key, expires = 10)
            val secondLot = f.key.copy(lotId = id(21))
            f.receive(10, 30_000, secondLot, expires = 20)
            val r = f.reserve(5, key = if (lot == 1) f.key else secondLot)
            val result = f.issue(r.proof.reservationId) as ReservationIssueResult.Issued
            return result.proof.costedAllocations.single().originalValue.minor
        }
        assertEquals(12_500L, issueFrom(1))
        assertEquals(12_500L, issueFrom(2))
    }

    @Test fun quarantinedOwnedStockIsValuedButNotReservable() {
        val f = InventoryFixture()
        f.receive(2, 200)
        val quarantine = f.key.copy(custody = StockCustody.QUARANTINE)
        f.receive(2, 600, quarantine)
        assertEquals(quantity(4), f.snapshot.pool(pool)!!.value.onHand)
        assertEquals(quantity(2), f.snapshot.available(f.key, 1))
        assertEquals(quantity(0), f.snapshot.available(quarantine, 1))
        val r = f.reserve(2)
        val issue = f.issue(r.proof.reservationId) as ReservationIssueResult.Issued
        assertEquals(money(400), issue.proof.costedAllocations.single().originalValue)
        assertEquals(money(400), f.snapshot.pool(pool)!!.value.carryingValue)
        fails(InventoryFailure.UNSAFE_STOCK) { f.reserve(1, key = quarantine) }
    }

    @Test fun originalRetailReturnRetainsLotAndBookCostAndCannotBeReusedOrMoveWarehouse() {
        val f = InventoryFixture()
        f.receive(10, 20_000)
        val r = f.reserve(5)
        val sent = f.issue(r.proof.reservationId) as ReservationIssueResult.Issued
        f.receive(5, 20_000)
        val issue = f.snapshot.issue(sent.proof.issueId)!!
        val allocation = issue.allocations.single().allocation
        fails(InventoryFailure.INVALID_ORIGINAL_ALLOCATION) {
            InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), issue.issueId,
                issue.revision, listOf(StockRestoration(allocation.allocationId, quantity(2), f.key.copy(pool = pool.copy(warehouseId = id(404))))), 1)
        }
        val source = f.ids.next()
        val returned = InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), source, issue.issueId,
            issue.revision, listOf(StockRestoration(allocation.allocationId, quantity(2), f.key)), 1)
        f.snapshot = returned.nextSnapshot
        assertEquals(money(34_000), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(quantity(12), f.snapshot.position(f.key)!!.onHand)
        val nextIssue = f.snapshot.issue(issue.issueId)!!
        assertEquals(issue.originalValue, nextIssue.originalValue)
        assertEquals(quantity(2), nextIssue.allocations.single().restoredQuantity)
        fails(InventoryFailure.DUPLICATE_SOURCE) {
            InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), source, issue.issueId,
                nextIssue.revision, listOf(StockRestoration(allocation.allocationId, quantity(2), f.key)), 1)
        }
        fails(InventoryFailure.INVALID_ORIGINAL_ALLOCATION) {
            InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), issue.issueId,
                nextIssue.revision, listOf(StockRestoration(allocation.allocationId, quantity(4), f.key)), 1)
        }
    }

    @Test fun lateCostPlanHasNoPhysicalMovementAndRetailReturnCarriesSeparateCorrection() {
        val f = InventoryFixture()
        val receipt = f.receive(10, 20_000)
        f.receive(10, 30_000)
        val r = f.reserve(5)
        val sent = f.issue(r.proof.reservationId) as ReservationIssueResult.Issued
        val originalPositions = f.snapshot.positions
        val adjusted = InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), pool,
            f.snapshot.pool(pool)!!.value.revision, f.ids.next(), f.ids.next(), receipt, money(12_000))
        f.snapshot = adjusted.nextSnapshot
        assertEquals(originalPositions, f.snapshot.positions)
        assertFalse(adjusted.effects.any { it is InventoryEffect.Movement })
        val issue = f.snapshot.issue(sent.proof.issueId)!!
        val returned = InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), issue.issueId,
            issue.revision, listOf(StockRestoration(issue.allocations.single().allocation.allocationId, quantity(2), f.key)), 1)
        val value = returned.effects.filterIsInstance<InventoryEffect.Valuation>().single().plan
        assertEquals(money(5_000), value.originalValueDelta)
        assertEquals(money(1_200), value.linkedAdjustment!!.heldDelta)
        assertEquals(money(-1_200), value.linkedAdjustment!!.consumedDelta)
        assertEquals(money(52_700), returned.nextSnapshot.pool(pool)!!.value.carryingValue)
    }

    @Test fun pinnedSkuBaseUnitCannotChangeInAnotherWarehouse() {
        val f = InventoryFixture()
        f.receive(1, 100)
        val other = f.key.copy(pool = pool.copy(warehouseId = id(405)))
        fails(InventoryFailure.UNIT_OR_SCALE_MISMATCH) {
            InventoryPlanner.receive(f.snapshot, f.snapshot.revision, f.ids.operation(),
                listOf(StockReceipt(f.ids.next(), other, Quantity(id(999), 1, 0), money(100))))
        }
        assertEquals(1, f.snapshot.pools.size)
    }

    @Test fun suppliedMutableCollectionsCannotMutateSnapshotsOrProofsLater() {
        val f = InventoryFixture()
        f.receive(2, 200)
        val allocations = mutableListOf(StockAllocation(f.ids.next(), id(30), f.key, quantity(1)))
        val originalAllocation = allocations.single()
        val result = InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), binding(), allocations, 1)
        allocations.clear()
        assertEquals(listOf(originalAllocation), result.proof.allocations)
        assertEquals(listOf(originalAllocation), result.plan.nextSnapshot.reservations.single().allocations)
        val changedCopy = result.proof.allocations.toMutableList()
        changedCopy.clear()
        assertEquals(1, result.proof.allocations.size)
        val stations = mutableListOf(id(300), id(301))
        val recipe = RecipeIssueRequest(id(302), stations)
        stations.clear()
        assertEquals(2, recipe.requiredStationIds.size)
    }

    @Test fun duplicateMovementAndOperationRequireRecoveryInsteadOfDomainReplay() {
        val f = InventoryFixture()
        val receiptId = f.ids.next()
        val operation = f.ids.operation()
        val receipt = StockReceipt(receiptId, f.key, quantity(1), money(100))
        val received = InventoryPlanner.receive(f.snapshot, f.snapshot.revision, operation, listOf(receipt))
        f.snapshot = received.nextSnapshot
        fails(InventoryFailure.DUPLICATE_SOURCE) { InventoryPlanner.receive(f.snapshot, f.snapshot.revision, operation, listOf(receipt)) }
        fails(InventoryFailure.DUPLICATE_SOURCE) { InventoryPlanner.receive(f.snapshot, f.snapshot.revision, f.ids.operation(), listOf(receipt)) }
        assertEquals(quantity(1), f.snapshot.position(f.key)!!.onHand)
        assertIs<InventoryEffect.Valuation>(received.effects.first())
    }

    @Test fun sameLotMultiplePortionsHaveDistinctConsumedSourceAllocationEffects() {
        val f = InventoryFixture()
        f.receive(2, 101)
        val allocations = listOf(StockAllocation(f.ids.next(), id(30), f.key, quantity(1)),
            StockAllocation(f.ids.next(), id(31), f.key, quantity(1)))
        val reservation = InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), binding(), allocations, 1)
        f.snapshot = reservation.plan.nextSnapshot
        val issued = assertIs<ReservationIssueResult.Issued>(f.issue(reservation.proof.reservationId))
        val movements = issued.plan.effects.filterIsInstance<InventoryEffect.Movement>()
        assertEquals(allocations.map { it.allocationId }.toSet(), movements.map { it.sourceAllocationId }.toSet())
        assertEquals(101L, issued.proof.costedAllocations.sumOf { it.originalValue.minor })
        assertEquals(quantity(0), f.snapshot.position(f.key)!!.onHand)
    }

    @Test fun multiPoolAggregateIssueValueOverflowRejectsWholePlanWithoutPartialStockIssue() {
        val f = InventoryFixture()
        f.receive(1, Long.MAX_VALUE)
        val other = f.key.copy(pool = pool.copy(skuId = id(407)))
        f.receive(1, 1, other)
        val reservation = InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), binding(),
            listOf(StockAllocation(f.ids.next(), id(30), f.key, quantity(1)), StockAllocation(f.ids.next(), id(31), other, quantity(1))), 1)
        f.snapshot = reservation.plan.nextSnapshot
        val before = f.snapshot
        assertFailsWith<ArithmeticException> { f.issue(reservation.proof.reservationId) }
        assertSame(before, f.snapshot)
        assertEquals(quantity(1), f.snapshot.position(f.key)!!.onHand)
        assertEquals(quantity(1), f.snapshot.position(other)!!.onHand)
        assertTrue(f.snapshot.issues.isEmpty())
    }

    @Test fun acquisitionChargeSourceCannotBeDuplicatedAcrossPoolsEvenAfterCancellation() {
        val f = InventoryFixture()
        val firstReceipt = f.receive(1, 100)
        val other = f.key.copy(pool = pool.copy(skuId = id(408)))
        val secondReceipt = f.receive(1, 200, other)
        val source = f.ids.next()
        f.snapshot = InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), pool,
            f.snapshot.pool(pool)!!.value.revision, f.ids.next(), source, firstReceipt, money(50)).nextSnapshot
        fun tryDifferentPool() {
            fails(InventoryFailure.INVALID_ORIGINAL_ALLOCATION) {
                InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), other.pool,
                    f.snapshot.pool(other.pool)!!.value.revision, f.ids.next(), source, secondReceipt, money(50))
            }
        }
        tryDifferentPool()
        f.snapshot = InventoryPlanner.adjustReceiptCost(f.snapshot, f.snapshot.revision, f.ids.operation(), pool,
            f.snapshot.pool(pool)!!.value.revision, f.ids.next(), source, firstReceipt, money(0)).nextSnapshot
        tryDifferentPool()
        assertEquals(money(100), f.snapshot.pool(pool)!!.value.carryingValue)
        assertEquals(money(200), f.snapshot.pool(other.pool)!!.value.carryingValue)
        assertEquals(1, f.snapshot.pools.sumOf { it.value.costTargets.size })
    }

    @Test fun expiredQuarantineReturnAlsoExpiresReleasedRemainderAndHoldsPaidClaimsAcrossClockRollback() {
        val f = InventoryFixture()
        f.receive(5, 500, expires = 10)
        val r = f.reserve(1)
        val sent = assertIs<ReservationIssueResult.Issued>(f.issue(r.proof.reservationId))
        val paid = f.reserve(1, CollectionExposure.PAID)
        val issue = f.snapshot.issue(sent.proof.issueId)!!
        val allocation = issue.allocations.single().allocation
        val quarantine = f.key.copy(custody = StockCustody.QUARANTINE)
        f.snapshot = InventoryPlanner.restoreOriginalRetail(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(),
            issue.issueId, issue.revision, listOf(StockRestoration(allocation.allocationId, quantity(1), quarantine)), 10).nextSnapshot
        assertEquals(StockSafety.EXPIRED, f.snapshot.position(f.key)!!.safety)
        assertEquals(StockSafety.EXPIRED, f.snapshot.position(quarantine)!!.safety)
        assertEquals(quantity(4), f.snapshot.position(f.key)!!.onHand)
        assertEquals(quantity(0), f.snapshot.available(f.key, 9))
        val held = f.snapshot.reservation(paid.proof.reservationId)!!
        assertEquals(ReservationStatus.HELD, held.status)
        assertEquals(CollectionExposure.PAID, held.binding.exposure)
        assertIs<ReservationIssueResult.Held>(f.issue(held.reservationId, now = 9))
        fails(InventoryFailure.UNSAFE_STOCK) {
            InventoryPlanner.reserve(f.snapshot, f.snapshot.revision, f.ids.operation(), f.ids.next(), binding(),
                listOf(StockAllocation(f.ids.next(), id(32), f.key, quantity(4))), 9)
        }
    }
}
