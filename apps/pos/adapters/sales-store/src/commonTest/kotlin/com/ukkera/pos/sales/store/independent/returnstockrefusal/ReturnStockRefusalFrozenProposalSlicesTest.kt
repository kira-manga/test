package com.ukkera.pos.sales.store.independent.returnstockrefusal

import com.ukkera.pos.sales.store.LocalReturnStockPostingV1
import com.ukkera.pos.sales.store.LocalReturnStockRefused
import com.ukkera.pos.sales.store.LocalReturnStockStatusV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.ukkera.pos.sales.store.independent.returnstockrefusal.FrozenReturnStockRefusalInputs as F

/**
 * Supplemental pure proposal slices of unchanged RSDF-001, RSDF-004(b/c) and
 * RSDF-006(a). No source row, accepting port, native context, authorization,
 * persistence, inventory pool, entitlement, drawer, journal or outbox is supplied.
 * Original acceptance/refund checks below concern immutable input DATA only.
 * In particular, a pure partial proposal is not a durably committed competitor.
 *
 * D0/D1/D2 bind only to values observed from this pure interface; no counter value
 * or increment is asserted as the frozen native disposition revision.
 * RSDF-002's genuine A-OTHER assertion and mixed precedence remain UNBOUND.
 * Whole-case no-business-effect, retained result, RPC, restart and authority
 * assertions remain UNBOUND, with the complete mapping in the local handoff.
 */
class ReturnStockRefusalFrozenProposalSlicesTest {
    @Test
    fun rsdf001_onlyCurrentDispositionAssertionIsStale_refusesPureProposal() {
        val data = FrozenReturnStockRefusalData()
        val initial = data.request()
        val first = data.post(initial)
        assertPureCut(data, first, "C1", LocalReturnStockStatusV1.PARTIALLY_POSTED)
        val before = data.fingerprint(first.after)

        // Build the C1 request first so its guarded posted quantity is current.
        // Restore only the previously observed disposition revision.
        val stale = data.request(first.after, restorationNumber = 2)
            .copy(expectedDispositionRevision = initial.expectedDispositionRevision)
        assertEquals(data.accepted.quantity, stale.acceptedQuantity)
        assertEquals(first.after.postedQuantity, stale.expectedPostedQuantity)
        val failure = assertFailsWith<LocalReturnStockRefused> {
            data.post(stale, first.after, operationNumber = 2)
        }
        // Public adapter spelling of the frozen stale CURRENT DISPOSITION family.
        assertEquals("DISPOSITION_REVISION_CHANGED", failure.reason)
        assertEquals(before, data.fingerprint(first.after))
        assertPureCut(data, first, "C1", LocalReturnStockStatusV1.PARTIALLY_POSTED)
        assertAcceptedInputUnchanged(data)
    }

    @Test
    fun rsdf004b_explicitDifferentIdentityDoesNotMakeStalePureProposalValid() {
        val data = FrozenReturnStockRefusalData()
        val initial = data.request()
        val first = data.post(initial)
        assertPureCut(data, first, "C1", LocalReturnStockStatusV1.PARTIALLY_POSTED)
        val before = data.fingerprint(first.after)
        val stale = data.request(first.after, restorationNumber = 2)
            .copy(expectedDispositionRevision = initial.expectedDispositionRevision)
        val failures = listOf(2, 3).map { operationNumber ->
            assertFailsWith<LocalReturnStockRefused> {
                data.post(stale, first.after, operationNumber)
            }
        }
        failures.forEach { assertEquals("DISPOSITION_REVISION_CHANGED", it.reason) }
        assertEquals(before, data.fingerprint(first.after))
        assertAcceptedInputUnchanged(data)
        // The pure interface has no original-result store. Independent durable
        // refusal identities and exact matching recovery are not asserted here.
    }

    @Test
    fun rsdf004c_explicitFreshRemainingUnitReachesPureC2_exhaustedRepostRefuses() {
        val data = FrozenReturnStockRefusalData()
        val first = data.post(data.request())
        assertPureCut(data, first, "C1", LocalReturnStockStatusV1.PARTIALLY_POSTED)
        val current = data.request(first.after, restorationNumber = 2)
        val second = data.post(current, first.after, operationNumber = 2)
        assertPureCut(data, second, "C2", LocalReturnStockStatusV1.POSTED)
        assertEquals(F.quantity(F.unitPost.literal("quantity_delta")), second.quantity)
        assertEquals(first.after.acceptanceOperationId, second.after.acceptanceOperationId)
        assertEquals(first.after.acceptanceDigest, second.after.acceptanceDigest)
        val before = data.fingerprint(second.after)

        // A distinct operation/restoration cannot reuse this accepted quantity.
        // No unaccepted original entitlement is inserted into the proposal.
        val exhausted = data.request(second.after, restorationNumber = 3)
        assertEquals(second.after.revision, exhausted.expectedDispositionRevision)
        assertEquals(second.after.postedQuantity, exhausted.expectedPostedQuantity)
        assertFailsWith<LocalReturnStockRefused> {
            data.post(exhausted, second.after, operationNumber = 3)
        }
        assertEquals(before, data.fingerprint(second.after))
        assertPureCut(data, second, "C2", LocalReturnStockStatusV1.POSTED)
        assertAcceptedInputUnchanged(data)
        // +1 EACH is bound. Original +20.00, pools 3/100.00 then 4/120.00,
        // availability, remaining cost and all financial ledgers are UNBOUND.
    }

    @Test
    fun rsdf006a_missingPurePredecessorFingerprintCannotBecomeBusinessRefusal() {
        val data = FrozenReturnStockRefusalData()
        val first = data.post(data.request())
        assertPureCut(data, first, "C1", LocalReturnStockStatusV1.PARTIALLY_POSTED)
        val current = data.request(first.after, restorationNumber = 2)
        val before = data.fingerprint(first.after)
        val failure = assertFailsWith<IllegalArgumentException> {
            data.post(current, first.after, operationNumber = 2, beforeDigest = null)
        }
        assertTrue(failure !is LocalReturnStockRefused,
            "Missing required predecessor DATA must not establish an authoritative business CAS")
        assertEquals(before, data.fingerprint(first.after))
        assertAcceptedInputUnchanged(data)
        // A fingerprint is not actual original BEFORE/history/resource evidence.
        // Durable recovery unavailable/unknown and rollback assertions are UNBOUND.
    }

    private fun assertPureCut(data: FrozenReturnStockRefusalData, posting: LocalReturnStockPostingV1,
        cutId: String, status: LocalReturnStockStatusV1) {
        val cut = F.cut(cutId)
        val posted = F.quantity(cut.literal("current_disposed_quantity"))
        val remaining = F.quantity(cut.literal("remaining_disposition_quantity"))
        assertEquals(posted, posting.after.postedQuantity)
        assertEquals(posted, posting.result.postedQuantity)
        assertEquals(remaining, posting.result.remainingQuantity)
        assertEquals(F.quantity(F.invariants.literal("current_accepted_quantity")), posting.after.acceptedQuantity)
        assertEquals(data.accepted.quantity, posting.result.postedQuantity + posting.result.remainingQuantity)
        assertEquals(status, posting.result.status)
        assertEquals(data.returnId, posting.after.returnId)
        assertEquals(data.saleId, posting.after.saleId)
        assertEquals(data.caseId, posting.after.caseId)
        assertEquals(data.portionId, posting.after.portionId)
        assertEquals(data.issueId, posting.after.issueId)
        assertEquals(posting.after.revision, posting.result.revision)
    }

    private fun assertAcceptedInputUnchanged(data: FrozenReturnStockRefusalData) {
        assertEquals(F.quantity(F.invariants.literal("current_accepted_quantity")), data.accepted.quantity)
        assertEquals(F.quantity("1"), data.accepted.consumedQuantityBefore)
        assertEquals(F.quantity(F.invariants.literal("cumulative_original_accepted_quantity")),
            data.accepted.consumedQuantityBefore + data.accepted.quantity)
        assertEquals(F.money(F.invariants.literal("current_refund_due")), data.accepted.originalPayable)
        assertEquals(F.money(F.invariants.literal("current_refund_due")), data.accepted.due)
        assertEquals(F.money(F.invariants.literal("current_actual_refund")), data.accepted.refunded)
        assertEquals(data.claimEvidence, data.accepted.evidence)
        assertEquals(F.instant(F.timing.literal("immutable_original_master_submission_utc")).value,
            data.accepted.evidence.claimedAt)
        assertTrue(data.accepted.attempts.isEmpty())
    }
}
