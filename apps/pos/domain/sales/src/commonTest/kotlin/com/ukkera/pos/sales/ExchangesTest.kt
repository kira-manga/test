package com.ukkera.pos.sales

import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Money
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Exchange offsets against the frozen acceptance scenarios RET-05/06/07
 * (docs/pos/original-handoff/POS_ACCEPTANCE_SCENARIOS.md). Self-authored, NOT independent.
 */
class ExchangesTest {
    private suspend fun returned(f: SalesFixture, price: String = "100"): AcceptedReturn {
        val source = f.retail(f.quote(price))
        source.cash(egp(price.toLong()))
        return f.accept(source.finalize().value.entitlement, f.quantity(1)).acceptedReturn
    }

    private suspend fun offset(f: SalesFixture, accepted: AcceptedReturn, replacement: RetailFixture, amount: Money,
        actor: com.ukkera.pos.types.DomainId = replacement.session.custodianId): SalesTransition<ExchangeOffsetApplied> {
        val slice = ExchangeOffsetSlice(f.nextId(), accepted.tenderObligations.single().originalPaymentId, amount)
        return Exchanges.applyOffset(f.command(SecurityAction.REFUND_RECORD, accepted.id, accepted.revision, amount, actor),
            accepted, accepted.revision, replacement.collection, replacement.collection.revision, f.nextId(), listOf(slice), f.evidence(actor))
    }

    @Test fun ret05ReplacementAboveReturnCollectsOnlyTheResidual() = runTest {
        val f = SalesFixture()
        val accepted = returned(f)
        val replacement = f.retail(f.quote("130"))
        val cashBefore = replacement.session.expectedCash
        replacement.cash(egp(30))
        val applied = offset(f, accepted, replacement, egp(100))
        assertTrue(applied.effects.none { it is CashMovement || it is CashSessionChanged })
        replacement.collection = applied.value.collection
        assertEquals(CollectionStatus.COVERED, replacement.collection.status)
        assertEquals(egp(0), applied.value.acceptedReturn.due)
        assertEquals(RefundStatus.SETTLED, applied.value.acceptedReturn.refundStatus)
        val sale = replacement.finalize().value
        assertEquals(egp(130), sale.sale.quote.payable)
        assertEquals(cashBefore + egp(30), replacement.session.expectedCash) // Actual collection 30 only.
        val offsetPayment = sale.sale.payments.single { it.tender is Tender.ExchangeOffset }
        assertEquals(egp(100), offsetPayment.amount)
        assertEquals(null, offsetPayment.cashReceived)
        // Returning the replacement later refunds the offset portion by its actual source tender.
        val again = f.accept(sale.entitlement, f.quantity(1)).acceptedReturn
        assertEquals(egp(130), again.due)
        assertTrue(again.tenderObligations.all { it.tender == Tender.Cash })
    }

    @Test fun ret06ReplacementBelowReturnOffsetsAndRefundsTheRest() = runTest {
        val f = SalesFixture()
        val accepted = returned(f)
        val replacement = f.retail(f.quote("80"))
        val applied = offset(f, accepted, replacement, egp(80))
        replacement.collection = applied.value.collection
        replacement.finalize()
        val after = applied.value.acceptedReturn
        assertEquals(egp(20), after.due)
        assertEquals(RefundStatus.PART_REFUNDED, after.refundStatus)
        val drawer = f.openCash(egp(100))
        val refunded = f.payRefund(after, drawer, after.tenderObligations.single().originalPaymentId, egp(20))
        assertEquals(RefundStatus.SETTLED, refunded.acceptedReturn.refundStatus)
        assertEquals(egp(80), refunded.session.expectedCash) // Actual refund 20 only; no store-credit balance remains.
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) {
            f.payRefund(refunded.acceptedReturn, refunded.session, after.tenderObligations.single().originalPaymentId, egp(1))
        }
    }

    @Test fun ret07FailedResidualCollectionLeavesTheRefundObligationIntact() = runTest {
        val f = SalesFixture()
        val accepted = returned(f)
        val replacement = f.retail(f.quote("130"))
        val attempt = replacement.start(egp(30), Tender.Cash)
        replacement.record(attempt, CollectionOutcome.NotPaid(f.evidence(replacement.session.custodianId), "card declined"))
        assertRule(SalesRule.INSUFFICIENT_COVERAGE) { offset(f, accepted, replacement, egp(100)) }
        assertEquals(egp(100), accepted.due)
        assertEquals(RefundStatus.DUE, accepted.refundStatus)
        assertEquals(CollectionStatus.LOCKED_UNPAID, replacement.collection.status)
    }

    @Test fun offsetCannotExceedTheRemainingObligationOrBeReused() = runTest {
        val f = SalesFixture()
        val accepted = returned(f, "50")
        val big = f.retail(f.quote("80"))
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) { offset(f, accepted, big, egp(80)) }
        val first = f.retail(f.quote("50"))
        val used = offset(f, accepted, first, egp(50)).value.acceptedReturn
        val second = f.retail(f.quote("10"))
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) { offset(f, used, second, egp(10)) }
    }

    @Test fun onlyTheReplacementCollectorMayApplyAndOrdinaryAttemptsCannotUseOffsetTender() = runTest {
        val f = SalesFixture()
        val accepted = returned(f)
        val replacement = f.retail(f.quote("100"))
        assertRule(SalesRule.OWNER_REQUIRED) { offset(f, accepted, replacement, egp(100), actor = f.other) }
        assertRule(SalesRule.TENDER_MISMATCH) {
            replacement.start(egp(100), Tender.ExchangeOffset(f.nextId(), accepted.id, Tender.Cash))
        }
        assertIs<CollectionStatus>(replacement.collection.status)
    }
}
