package com.ukkera.pos.inventory.store.independent.countsafetypreservationv1

import com.ukkera.pos.inventory.InventoryRelationalPlanner
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.types.Quantity
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Frozen stock-count-safety-preservation-v1 L1 portions, through the actual normalized
 * countPlan and receiptPlan. The recording boundary is NOT Room, native authority,
 * protected command execution, historical authentication, RPC or durable recovery.
 * See README.md for missing BLOCKED/claim-quarantine mappings and remaining assertions.
 */
class StockCountSafetyPreservationV1SupportingTest {
    @Test fun scsC01RecallObservedOne() = countMatrix("RECALL", "1")
    @Test fun scsC01RecallObservedSeven() = countMatrix("RECALL", "7")
    @Test fun scsC01RecallObservedTen() = countMatrix("RECALL", "10")
    @Test fun scsC01ExpiredObservedOne() = countMatrix("EXPIRED", "1")
    @Test fun scsC01ExpiredObservedSeven() = countMatrix("EXPIRED", "7")
    @Test fun scsC01ExpiredObservedTen() = countMatrix("EXPIRED", "10")
    @Test fun scsC01QuarantineObservedOne() = countMatrix("QUARANTINED", "1")
    @Test fun scsC01QuarantineObservedSeven() = countMatrix("QUARANTINED", "7")
    @Test fun scsC01QuarantineObservedTen() = countMatrix("QUARANTINED", "10")

    @Test fun scsC02RecallOrdinaryClaim() = preservedClaim("RECALL", "ORDINARY")
    @Test fun scsC02RecallUnknownCollectionClaim() = preservedClaim("RECALL", "UNKNOWN")
    @Test fun scsC02ExpiredOrdinaryClaim() = preservedClaim("EXPIRED", "ORDINARY")
    @Test fun scsC02ExpiredUnknownCollectionClaim() = preservedClaim("EXPIRED", "UNKNOWN")

    @Test fun scsC04RecallSurvivesCountThenReceiptIntoNewCustodyPosition() = laterHeldReceipt("RECALL")
    @Test fun scsC04ExpirySurvivesCountThenReceiptIntoNewCustodyPosition() = laterHeldReceipt("EXPIRED")

    private fun countMatrix(reason: String, observed: String) = runTest {
        val expected = CountSafetyFrozen.countMatrix(observed)
        val fixture = CountSafetyFixture(reason)
        val label = "SCS-C01/$reason/$observed"
        fixture.captureCount()
        val output = fixture.postCount(expected.quantity("observed"))
        assertCountOutput(fixture, output, expected.quantity("adjustment"), label)
        fixture.apply(output)

        val current = fixture.position()
        assertQuantity(expected.quantity("physical"), current.onHand, "$label physical")
        assertSafety(fixture, current, label)
        val availability = fixture.availability(current)
        assertQuantity(CountSafetyFrozen.fixture("F-COUNT").quantity("reserved"), availability.held, "$label reserved")
        assertQuantity(expected.quantity("available"), InventoryRelationalPlanner.available(availability, fixture.millis), "$label available")
        assertPostedObservation(fixture, expected.quantity("observed"), expected.quantity("adjustment"), label)
        assertHistoricalInventorySources(fixture, label)
    }

    private fun preservedClaim(reason: String, variant: String) = runTest {
        val input = CountSafetyFrozen.fixture("F-CLAIMS")
        val expected = input.obj("expected")
        val fixture = CountSafetyFixture(reason, variant)
        val original = checkNotNull(fixture.reservation())
        val originalAllocation = checkNotNull(fixture.claimAllocation())
        val label = "SCS-C02/$reason/$variant"
        fixture.captureCount()
        val output = fixture.postCount(input.quantity("observed_quantity"))
        assertCountOutput(fixture, output, expected.quantity("adjustment"), label)
        fixture.apply(output)

        val current = fixture.position()
        assertQuantity(expected.quantity("physical"), current.onHand, "$label physical")
        assertQuantity(expected.quantity("unsafe_physical"), current.onHand, "$label unsafe physical")
        assertSafety(fixture, current, label)
        val retained = assertNotNull(fixture.reservation(), label)
        assertTrue(retained.value.retainsStockClaim, "$label lost its original stock claim")
        assertEquals(original.value.id, retained.value.id, label)
        assertEquals(original.value.binding, retained.value.binding, "$label changed original order/lock/actor/exposure")
        assertEquals(original.value.expiresAtEpochMillis, retained.value.expiresAtEpochMillis, "$label changed the original expiry")
        assertEquals(original.value.allocationCount, retained.value.allocationCount, label)
        assertEquals(original.allocationSetId, retained.allocationSetId, label)
        assertEquals(original.allocationSetRevision, retained.allocationSetRevision, label)
        assertEquals(originalAllocation, fixture.claimAllocation(), "$label rewrote the original allocation")
        assertTrue(fixture.originalClaimOwnershipRetained(), "$label released original reservation/allocation ownership")

        val availability = fixture.availability(current)
        assertQuantity(expected.quantity("reserved_claim"), availability.held, "$label overlapping claim")
        assertQuantity(expected.quantity("available"), InventoryRelationalPlanner.available(availability, fixture.millis), "$label available")
        // Physical is 8, not 8+4. Neither the new hold policy nor its row count is an oracle here.
        assertPostedObservation(fixture, input.quantity("observed_quantity"), expected.quantity("adjustment"), label)
        assertHistoricalInventorySources(fixture, label)
        // The binding exposes UNKNOWN/UNCOLLECTED and original links, not the external attempt
        // record or 2,000-minor quote. Their actual durable preservation remains unbound.
    }

    private fun laterHeldReceipt(reason: String) = runTest {
        val input = CountSafetyFrozen.fixture("F-LATER-LOCATION")
        val expected = input.obj("expected")
        val fixture = CountSafetyFixture(reason)
        val label = "SCS-C04/$reason distinct-custody-position SUPPORT"
        fixture.captureCount()
        val count = fixture.postCount(expected.quantity("original_count_observed"))
        assertCountOutput(fixture, count, expected.quantity("original_count_adjustment"), label)
        fixture.apply(count)
        val countedPosition = fixture.position()
        val countedDocument = fixture.countDocument()
        val receipt = fixture.receiveHeldSameLot()
        val received = LocalStockCommandCodec.decodeResult(receipt.result)
        assertEquals(LocalStockResultKind.RECEIVED, received.kind, label)
        assertEquals(CountSafetyFrozen.id(input.text("new_receipt_id")), received.documentId, label)
        assertNull(received.failure, label)
        fixture.apply(receipt)

        val original = fixture.position()
        val newlyHeld = fixture.position(StockCustody.QUARANTINE)
        assertEquals(countedPosition, original, "$label changed the counted custody position")
        assertQuantity(expected.quantity("A"), original.onHand, "$label existing position")
        assertQuantity(expected.quantity("B"), newlyHeld.onHand, "$label new held position")
        assertQuantity(expected.quantity("physical_total"), original.onHand + newlyHeld.onHand, "$label physical total")
        assertQuantity(expected.quantity("physical_total"), fixture.pool().onHand, "$label valuation-pool quantity")
        assertEquals(original.key.pool, newlyHeld.key.pool, label)
        assertEquals(original.key.lotId, newlyHeld.key.lotId, label)
        assertEquals(StockCustody.QUARANTINE, newlyHeld.key.custody, label)
        assertSafety(fixture, original, label)
        assertSafety(fixture, newlyHeld, label, sameCustody = false)
        assertQuantity(expected.quantity("available_A"), InventoryRelationalPlanner.available(fixture.availability(original), fixture.millis), label)
        assertQuantity(expected.quantity("available_B"), InventoryRelationalPlanner.available(fixture.availability(newlyHeld), fixture.millis), label)
        assertEquals(countedDocument, fixture.countDocument(), "$label rewrote the earlier count")
        val physicalReceipt = fixture.laterReceipt()
        assertEquals(CountSafetyFrozen.id(input.text("new_receipt_id")), physicalReceipt.receiptId, label)
        assertQuantity(input.obj("new_receipt").quantity("quantity"), physicalReceipt.lines.single().baseQuantity, label)
        assertEquals(LocalReceiptDisposition.QUARANTINE.name, physicalReceipt.lines.single().request.disposition, label)
        assertPostedObservation(fixture, expected.quantity("original_count_observed"), expected.quantity("original_count_adjustment"), label)
        assertHistoricalInventorySources(fixture, label)
        // A/B numbers are reused only for the two custody positions. There is no location
        // identifier at this boundary; physical-location, inspection and retry cases are not claimed.
    }

    private fun assertCountOutput(fixture: CountSafetyFixture, output: CountSafetyOutput, delta: Quantity, label: String) {
        val result = LocalStockCommandCodec.decodeResult(output.plan.result)
        assertEquals(LocalStockResultKind.COUNT_POSTED, result.kind, label)
        assertEquals(fixture.countId, result.documentId, label)
        assertNull(result.failure, label)
        assertEquals(setOf(fixture.countKey), output.documents.keys, "$label rewrote another document")
        assertEquals(mapOf(LocalStockStoreProfile.sourceClaim(fixture.countId) to fixture.countId), output.claims,
            "$label changed unrelated permanent claims")
        assertTrue(output.records.values.all { it is StockRelationalBody.Position || it is StockRelationalBody.Pool ||
            it is StockRelationalBody.Item || it is StockRelationalBody.ValueEvent || it is StockRelationalBody.ValueSlice ||
            it is StockRelationalBody.Hold || it is StockRelationalBody.Reservation }, "$label created a sale/issue or unrelated output")
        assertTrue(output.records.keys.none { it in fixture.originalHistoryKeys }, "$label rewrote original inventory history")
        val events = output.records.values.filterIsInstance<StockRelationalBody.ValueEvent>()
        assertQuantity(delta, events.fold(fixture.zero) { total, event -> total + event.quantityDelta }, "$label signed adjustment")
        for (event in events) {
            assertEquals(fixture.countId, event.sourceId, label)
            assertEquals(StockValueEventKind.COUNT_SHORTAGE, event.event, "$label represented a count as a sale")
            assertNull(event.issueId, label)
        }
        output.records.values.filterIsInstance<StockRelationalBody.Item>().forEach {
            assertEquals(fixture.itemBody, it, "$label altered SKU/unit/precision metadata")
        }
        // Zero variance permits either no movement or a zero-delta movement. Loss value is
        // intentionally not asserted: v1 U5 does not freeze a new count valuation policy.
    }

    private fun assertPostedObservation(fixture: CountSafetyFixture, observed: Quantity, delta: Quantity, label: String) {
        val document = fixture.countDocument()
        val captured = checkNotNull(fixture.originalCount)
        assertEquals(captured.countId, document.countId, label)
        assertEquals(captured.position, document.position, label)
        assertEquals(captured.capturedItemRevision, document.capturedItemRevision, label)
        assertEquals(captured.capturedQuantity, document.capturedQuantity, label)
        assertEquals(captured.capturedBy, document.capturedBy, label)
        assertEquals(captured.capturedAt, document.capturedAt, label)
        assertQuantity(CountSafetyFrozen.history.quantity("current_physical_before_count"), document.capturedQuantity, label)
        assertQuantity(observed, assertNotNull(document.counted, label), label)
        assertQuantity(delta, checkNotNull(document.counted) - document.capturedQuantity, label)
        assertEquals(fixture.actorId, document.postedBy, label)
        assertEquals(fixture.now, document.postedAt, label)
    }

    private fun assertSafety(fixture: CountSafetyFixture, position: StockPosition, label: String, sameCustody: Boolean = true) {
        assertEquals(fixture.initialPosition.expiresAtEpochMillis, position.expiresAtEpochMillis, "$label changed expiry")
        if (sameCustody) assertEquals(fixture.initialPosition.key, position.key, "$label changed qualified stock identity/custody")
        when (fixture.reason) {
            "RECALL" -> assertEquals(StockSafety.RECALLED, position.safety, "$label lost the lot-wide recall")
            "EXPIRED" -> assertEquals(StockSafety.EXPIRED, position.safety, "$label lost the existing expiry restriction")
            "QUARANTINED" -> assertEquals(StockCustody.QUARANTINE, position.key.custody, "$label released quarantine")
            else -> error("No faithful frozen safety binding")
        }
        assertFalse(position.isSellableAt(fixture.millis), "$label became sellable")
    }

    private fun assertHistoricalInventorySources(fixture: CountSafetyFixture, label: String) {
        val history = CountSafetyFrozen.history
        assertQuantity(history.quantity("received_quantity"), fixture.receiptEvent().quantityDelta, "$label original receipt")
        assertQuantity(-history.quantity("sold_before_any_safety_restriction"), fixture.saleEvent().quantityDelta, "$label historical sale issue")
        assertQuantity(history.quantity("sold_before_any_safety_restriction"), fixture.saleSlice().allocation.quantity, label)
        assertEquals(history.money("historical_issue_cost_minor"), fixture.saleSlice().originalValue, "$label historical issued cost")
        assertEquals(history.money("historical_issue_cost_minor"), fixture.saleHeader().originalValue, label)
        assertEquals(fixture.historicalCollectionBinding, fixture.saleHeader().binding, "$label changed historical collection link")
        // No sale/collection money rows are inputs to countPlan. This is immutable inventory
        // source preservation, not a test of the historical 1,000-minor payment projection.
    }
}

private fun assertQuantity(expected: Quantity, actual: Quantity, message: String) = assertEquals(expected, actual, message)
