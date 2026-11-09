package com.ukkera.pos.sales

import com.ukkera.pos.pricing.ChargeKind
import com.ukkera.pos.pricing.ChargeRefundTreatment
import com.ukkera.pos.pricing.PricingCharge
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReturnsTest {
    @Test fun repeatedPartialClaimsConsumeOriginalRemaindersAndTenderRightsBeforeAnyRefund() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("0.33333333", f.quantity(3)))
        val cashPayment = source.cash(money(40))
        val externalPayment = source.external(money(60))
        val paid = source.finalize().value
        assertEquals(money(100), paid.sale.quote.payable)
        var entitlement = paid.entitlement
        val accepted = mutableListOf<AcceptedReturn>()
        repeat(3) {
            val result = f.accept(entitlement, f.quantity(1))
            entitlement = result.entitlement; accepted += result.acceptedReturn
        }
        assertEquals(listOf(33L, 33L, 34L), accepted.map { it.originalPayable.minor })
        assertEquals(0L, entitlement.cursors.single().remainingQuantity.scaled)
        assertEquals(money(100), accepted.fold(money(0)) { sum, item -> sum + item.due })
        assertTrue(accepted.all { it.attempts.isEmpty() && it.refundStatus == RefundStatus.DUE })
        val obligations = accepted.flatMap { it.tenderObligations }
        assertEquals(money(40), obligations.filter { it.originalPaymentId == cashPayment }.fold(money(0)) { sum, item -> sum + item.amount })
        assertEquals(money(60), obligations.filter { it.originalPaymentId == externalPayment }.fold(money(0)) { sum, item -> sum + item.amount })
        val extraClaim = f.claim(entitlement, f.quantity(1)) // Still record the dispute without manufacturing rights.
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) {
            Returns.accept(f.command(SecurityAction.RETURN_ACCEPT, entitlement.saleId, entitlement.revision, money(1), f.other),
                entitlement, entitlement.revision, extraClaim, extraClaim.revision, f.nextId(), ReturnDisposition.QUARANTINE, f.evidence(f.other))
        }
        var today = f.openCash(money(100))
        for (item in accepted) {
            var returned = item
            for (obligation in item.tenderObligations) {
                val paidRefund = f.payRefund(returned, today, obligation.originalPaymentId, obligation.amount, obligation.tender is Tender.External)
                returned = paidRefund.acceptedReturn; today = paidRefund.session
            }
            assertEquals(RefundStatus.SETTLED, returned.refundStatus)
            assertEquals(money(0), returned.due)
        }
        assertEquals(money(60), today.expectedCash)
        assertEquals(money(40), today.totals.refunds)
        assertEquals(money(60), today.totals.externalRefunds)
        assertEquals(0L, source.inventory.positions.single().onHand.scaled) // No automatic restock.
    }

    @Test fun acceptedReturnCannotBeAcceptedAgainEvenWhileUnpaidAndStaleRevisionLoses() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("50", f.quantity(2)))
        source.cash(egp(100))
        val initial = source.finalize().value.entitlement
        val claim = f.claim(initial, f.quantity(1))
        val accepted = Returns.accept(f.command(SecurityAction.RETURN_ACCEPT, initial.saleId, initial.revision, egp(50), f.other),
            initial, initial.revision, claim, claim.revision, f.nextId(), ReturnDisposition.INSPECTED_SELLABLE, f.evidence(f.other))
        assertEquals(egp(50), accepted.value.acceptedReturn.due)
        assertEquals(1L, accepted.value.entitlement.cursors.single().remainingQuantity.scaled)
        assertTrue(accepted.effects.none { it is CashMovement || it is RefundRecord })
        assertTrue(accepted.inventoryDependencies.isEmpty()) // Disposition is requested, not a fake stock effect.
        assertEquals(setOf(SalesUniqueKind.ACCEPTED_RETURN, SalesUniqueKind.CLAIM_ACCEPTANCE), accepted.unique.map { it.kind }.toSet())
        assertRule(SalesRule.IMMUTABLE_RECORD) {
            Returns.accept(f.command(SecurityAction.RETURN_ACCEPT, initial.saleId, accepted.value.entitlement.revision, egp(50), f.other),
                accepted.value.entitlement, accepted.value.entitlement.revision, accepted.value.claim, accepted.value.claim.revision,
                f.nextId(), ReturnDisposition.INSPECTED_SELLABLE, f.evidence(f.other))
        }
        assertRule(SalesRule.REVISION_CONFLICT) {
            Returns.accept(f.command(SecurityAction.RETURN_ACCEPT, initial.saleId, accepted.value.entitlement.revision, egp(50), f.other),
                accepted.value.entitlement, initial.revision, claim, claim.revision, f.nextId(), ReturnDisposition.QUARANTINE, f.evidence(f.other))
        }
        assertEquals(egp(1_100), source.session.expectedCash)
    }

    @Test fun missingCashLeavesRefundDueAndUnknownPayoutReservesCashWithoutPretendingItWasPaid() = runTest {
        val f = SalesFixture()
        val source = f.retail(f.quote("50"))
        val paymentId = source.cash(egp(50))
        val accepted = f.accept(source.finalize().value.entitlement, f.quantity(1)).acceptedReturn
        val empty = f.openCash(money(0))
        assertRule(SalesRule.INSUFFICIENT_DRAWER_CASH) {
            Returns.startRefund(f.command(SecurityAction.REFUND_RECORD, accepted.id, accepted.revision, egp(50)), accepted,
                accepted.revision, empty, empty.revision, f.nextId(), paymentId, egp(50))
        }
        assertEquals(RefundStatus.DUE, accepted.refundStatus)
        assertEquals(egp(50), accepted.due)
        val today = f.openCash(egp(60))
        val attemptId = f.nextId()
        val started = Returns.startRefund(f.command(SecurityAction.REFUND_RECORD, accepted.id, accepted.revision, egp(50)),
            accepted, accepted.revision, today, today.revision, attemptId, paymentId, egp(50)).value
        assertEquals(egp(10), started.session.availableCash)
        assertEquals(egp(60), started.session.expectedCash)
        assertRule(SalesRule.INSUFFICIENT_DRAWER_CASH) {
            CashSessions.recordNonSale(f.command(SecurityAction.CASH_MOVE, today.id, started.session.revision, egp(11)), started.session,
                started.session.revision, f.nextId(), CashMovementKind.OPERATING_EXPENSE, egp(11), f.evidence(), "would consume protected refund cash")
        }
        val unknown = Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RECORD, accepted.id, started.acceptedReturn.revision, egp(50)),
            started.acceptedReturn, started.acceptedReturn.revision, started.session, started.session.revision, attemptId,
            RefundOutcome.Unknown(f.evidence())).value
        assertEquals(RefundStatus.OUTCOME_UNKNOWN, unknown.acceptedReturn.refundStatus)
        assertEquals(egp(50), unknown.acceptedReturn.due)
        assertSame(started.session, unknown.session)
        assertRule(SalesRule.PAYMENT_UNRESOLVED) {
            Returns.startRefund(f.command(SecurityAction.REFUND_RECORD, accepted.id, unknown.acceptedReturn.revision, egp(10)),
                unknown.acceptedReturn, unknown.acceptedReturn.revision, unknown.session, unknown.session.revision, f.nextId(), paymentId, egp(10))
        }
        assertRule(SalesRule.OWNER_REQUIRED) {
            Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RESOLVE, accepted.id, unknown.acceptedReturn.revision, egp(50), f.other),
                unknown.acceptedReturn, unknown.acceptedReturn.revision, unknown.session, unknown.session.revision, attemptId,
                RefundOutcome.CashPaid(f.evidence(f.other)))
        }
        val paid = Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RESOLVE, accepted.id, unknown.acceptedReturn.revision, egp(50)),
            unknown.acceptedReturn, unknown.acceptedReturn.revision, unknown.session, unknown.session.revision, attemptId,
            RefundOutcome.CashPaid(f.evidence())).value
        assertEquals(egp(10), paid.session.expectedCash)
        assertEquals(money(0), paid.session.reservedCashOut)
        assertEquals(RefundStatus.SETTLED, paid.acceptedReturn.refundStatus)
        val refund = assertNotNull(paid.acceptedReturn.attempts.single().refund)
        assertEquals(started.acceptedReturn.attempts.single().originalOperationId, refund.originalOperationId)
        assertEquals(today.id, refund.payingCashSessionId)
        assertEquals(f.owner, refund.payingActorId)
        assertRule(SalesRule.IMMUTABLE_RECORD) {
            Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RECORD, accepted.id, paid.acceptedReturn.revision, egp(50)),
                paid.acceptedReturn, paid.acceptedReturn.revision, paid.session, paid.session.revision, attemptId,
                RefundOutcome.CashPaid(f.evidence()))
        }
    }

    @Test fun notPaidPayoutReleasesOnlyReservationAndNewPartialRefundsCannotExceedOriginalTender() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        val cash = source.cash(egp(30))
        val external = source.external(egp(70))
        var accepted = f.accept(source.finalize().value.entitlement, f.quantity(1)).acceptedReturn
        var today = f.openCash(egp(100))
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) {
            Returns.startRefund(f.command(SecurityAction.REFUND_RECORD, accepted.id, accepted.revision, egp(31)), accepted,
                accepted.revision, today, today.revision, f.nextId(), cash, egp(31))
        }
        val attemptId = f.nextId()
        val start = Returns.startRefund(f.command(SecurityAction.REFUND_RECORD, accepted.id, accepted.revision, egp(30)), accepted,
            accepted.revision, today, today.revision, attemptId, cash, egp(30)).value
        val refused = Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RECORD, accepted.id, start.acceptedReturn.revision, egp(30)),
            start.acceptedReturn, start.acceptedReturn.revision, start.session, start.session.revision, attemptId,
            RefundOutcome.NotPaid(f.evidence(), "counted cash not actually paid")).value
        assertEquals(egp(100), refused.session.expectedCash)
        assertEquals(money(0), refused.session.reservedCashOut)
        assertEquals(egp(100), refused.acceptedReturn.due)
        val first = f.payRefund(refused.acceptedReturn, refused.session, cash, egp(10))
        val second = f.payRefund(first.acceptedReturn, first.session, cash, egp(20))
        accepted = second.acceptedReturn; today = second.session
        assertEquals(RefundStatus.PART_REFUNDED, accepted.refundStatus)
        assertRule(SalesRule.RETURN_ENTITLEMENT_EXHAUSTED) {
            Returns.startRefund(f.command(SecurityAction.REFUND_RECORD, accepted.id, accepted.revision, money(1)), accepted,
                accepted.revision, today, today.revision, f.nextId(), cash, money(1))
        }
        val final = f.payRefund(accepted, today, external, egp(70), external = true)
        assertEquals(egp(70), final.session.expectedCash)
        assertEquals(egp(70), final.session.totals.externalRefunds)
        assertEquals(RefundStatus.SETTLED, final.acceptedReturn.refundStatus)
    }

    @Test fun originalExternalRefundCannotSilentlySwitchToCashOrOmitRequiredReference() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        val payment = source.external(egp(100))
        val accepted = f.accept(source.finalize().value.entitlement, f.quantity(1)).acceptedReturn
        val today = f.openCash(egp(100))
        val attemptId = f.nextId()
        val start = Returns.startRefund(f.command(SecurityAction.REFUND_RECORD, accepted.id, accepted.revision, egp(100)), accepted,
            accepted.revision, today, today.revision, attemptId, payment, egp(100)).value
        assertEquals(money(0), start.session.reservedCashOut)
        assertRule(SalesRule.TENDER_MISMATCH) {
            Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RECORD, accepted.id, start.acceptedReturn.revision, egp(100)),
                start.acceptedReturn, start.acceptedReturn.revision, start.session, start.session.revision, attemptId, RefundOutcome.CashPaid(f.evidence()))
        }
        assertRule(SalesRule.REFERENCE_REQUIRED) {
            Returns.recordRefundOutcome(f.command(SecurityAction.REFUND_RECORD, accepted.id, start.acceptedReturn.revision, egp(100)),
                start.acceptedReturn, start.acceptedReturn.revision, start.session, start.session.revision, attemptId,
                RefundOutcome.ExternalAttested(ExternalTenderEvidence(f.evidence(), null, "reference")))
        }
        assertEquals(egp(100), start.acceptedReturn.due)
        assertEquals(egp(100), start.session.expectedCash)
    }

    @Test fun sameSellingBranchIsMandatoryAndNarrowReceiptLookupDoesNotNeedOriginalOwner() = runTest {
        val f = SalesFixture()
        val source = f.retail()
        source.cash(egp(100))
        val receipt = source.finalize().value.entitlement
        val projection = Returns.receiptProjection(f.command(SecurityAction.RETURN_RECEIPT_LOOKUP, receipt.saleId, receipt.revision, actor = f.other),
            receipt, receipt.revision)
        assertEquals(receipt.saleId, projection.saleId)
        assertEquals(egp(100), projection.lines.single().originalRefundable)
        val foreignBranch = f.scope.copy(branchId = f.nextId())
        assertRule(SalesRule.PERMISSION_DENIED) {
            Returns.receiptProjection(f.command(SecurityAction.RETURN_RECEIPT_LOOKUP, receipt.saleId, receipt.revision,
                actor = f.other, branch = foreignBranch), receipt, receipt.revision)
        }
        val claim = f.claim(receipt, f.quantity(1))
        assertRule(SalesRule.PERMISSION_DENIED) {
            Returns.accept(f.command(SecurityAction.RETURN_ACCEPT, receipt.saleId, receipt.revision, egp(100), f.other, foreignBranch),
                receipt, receipt.revision, claim, claim.revision, f.nextId(), ReturnDisposition.QUARANTINE, f.evidence(f.other))
        }
        assertEquals(1L, receipt.cursors.single().remainingQuantity.scaled)
    }

    @Test fun performedFixedChargeDoesNotBecomeAutomaticItemRefundAndPriceChangeIsIrrelevant() = runTest {
        val f = SalesFixture()
        val charge = PricingCharge.Fixed(f.nextId(), Revision(1), ChargeKind.DELIVERY, UnitPrice.parse(Currency.EGP, "20"),
            TaxInclusion.EXCLUSIVE, TaxSnapshot(f.nextId(), Revision(1), "TEST_ZERO", PercentageRate.parse("0")), ChargeRefundTreatment.EXPLICIT_ONLY)
        val source = f.retail(f.quote("100", charges = listOf(charge)))
        source.cash(egp(120))
        val original = source.finalize().value
        val newerUnrelatedPrice = f.quote("999")
        assertEquals(egp(999), newerUnrelatedPrice.payable)
        val returned = f.accept(original.entitlement, f.quantity(1))
        assertEquals(egp(100), returned.acceptedReturn.originalPayable)
        assertEquals(egp(120), original.sale.quote.payable)
        assertTrue(returned.acceptedReturn.components.all { it.chargeId == null })
        assertEquals(egp(20), returned.entitlement.remainingComponentTenders.flatMap { it.shares }.fold(money(0)) { sum, item -> sum + item.amount })
    }

    @Test fun tinyRoundingAndMultiplePortionsNeverDuplicateOriginalTenderValue() = runTest {
        val f = SalesFixture()
        val tiny = f.retail(f.quote("0.005", f.quantity(3)))
        tiny.cash(money(1)); tiny.external(money(1))
        var entitlement = tiny.finalize().value.entitlement
        val slices = mutableListOf<AcceptedReturn>()
        repeat(3) {
            val result = f.accept(entitlement, f.quantity(1)); entitlement = result.entitlement; slices += result.acceptedReturn
        }
        assertEquals(listOf(0L, 1L, 1L), slices.map { it.originalPayable.minor })
        assertTrue(slices.first().attempts.isEmpty())
        assertEquals(RefundStatus.SETTLED, slices.first().refundStatus)
        val multi = f.retail(f.quote("0.01", portions = 3))
        val cash = multi.cash(money(1)); val external = multi.external(money(2))
        var rights = multi.finalize().value.entitlement
        val obligations = mutableListOf<ReturnTenderObligation>()
        for (portionId in rights.cursors.map { it.portionId }) {
            val returned = f.accept(rights, f.quantity(1), portionId); rights = returned.entitlement
            obligations += returned.acceptedReturn.tenderObligations
        }
        assertEquals(money(1), obligations.filter { it.originalPaymentId == cash }.fold(money(0)) { sum, item -> sum + item.amount })
        assertEquals(money(2), obligations.filter { it.originalPaymentId == external }.fold(money(0)) { sum, item -> sum + item.amount })
        assertTrue(rights.cursors.all { it.remainingQuantity.scaled == 0L })
        assertTrue(rights.remainingComponentTenders.flatMap { it.shares }.all { it.amount.minor == 0L })
    }
}
