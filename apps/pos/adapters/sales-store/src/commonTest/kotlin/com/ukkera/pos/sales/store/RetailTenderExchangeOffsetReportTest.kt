package com.ukkera.pos.sales.store

import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A sale completed by an exchange offset stays reportable; the offset is never a correctable
 * or cash classification. Self-authored, NOT independent. */
class RetailTenderExchangeOffsetReportTest {
    private val at = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    private val close = LocalPaymentCorrectionCloseViewV1(cashId(1), OperationId(cashId(2).value), Revision(4), cashMoney(30),
        cashMoney(0), cashMoney(0), cashMoney(30), cashMoney(0), cashMoney(0), cashMoney(30), null, cashId(3),
        LocalCashActorEvidenceV1(cashId(4), cashId(3), at), null)
    private val offset = LocalTenderViewV1("EXCHANGE_OFFSET", cashId(9))
    private fun payment(id: Int, amount: Long, original: LocalTenderViewV1, reconciled: LocalTenderViewV1 = original,
        count: Long = 0, correction: Boolean = false) = LocalBranchReportRetailTenderPaymentV3(cashId(id), cashMoney(amount), original,
        reconciled, if (correction) cashId(50 + id) else null, count, if (correction) at else null)
    private fun row(vararg payments: LocalBranchReportRetailTenderPaymentV3) =
        retailTenderBreakdownReportProjection(cashId(20), Revision(1), cashId(21), cashMoney(payments.sumOf { it.amount.minor }), close, payments.toList())

    @Test fun offsetAndCashResidualAreAValidBreakdown() {
        assertTrue(validRetailTenderBreakdownReportRow(row(payment(10, 100, offset), payment(11, 30, LocalTenderViewV1("CASH"))), at))
    }

    @Test fun malformedOrCorrectedOffsetsAreRefused() {
        assertFalse(validRetailTenderBreakdownReportRow(row(payment(10, 100, LocalTenderViewV1("EXCHANGE_OFFSET"))), at))
        assertFalse(validRetailTenderBreakdownReportRow(row(payment(10, 100, offset.copy(policyRevision = Revision(1)))), at))
        assertFalse(validRetailTenderBreakdownReportRow(row(payment(10, 100, offset.copy(requiresReference = true))), at))
        assertFalse(validRetailTenderBreakdownReportRow(row(payment(10, 100, offset, LocalTenderViewV1("CASH"), 1, true)), at))
        assertFalse(validRetailTenderBreakdownReportRow(row(payment(10, 100, LocalTenderViewV1("CASH"), offset, 1, true)), at))
    }
}
