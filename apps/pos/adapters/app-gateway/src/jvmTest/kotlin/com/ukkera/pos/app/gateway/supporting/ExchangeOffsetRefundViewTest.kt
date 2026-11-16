package com.ukkera.pos.app.gateway.supporting

import com.ukkera.pos.app.gateway.requireAcceptedRefund
import com.ukkera.pos.app.gateway.retailRefundDues
import com.ukkera.pos.sales.store.LocalAcceptedReturnViewV1
import com.ukkera.pos.sales.store.LocalRefundAttemptViewV1
import com.ukkera.pos.sales.store.LocalRefundObligationViewV1
import com.ukkera.pos.sales.store.LocalTenderViewV1
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** RET-06: a partly offset return stays refundable for its residual. Self-authored, NOT independent. */
class ExchangeOffsetRefundViewTest {
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")
    private fun egp(minor: Long) = Money(Currency.EGP, minor)
    private val offset = LocalTenderViewV1("EXCHANGE_OFFSET", id(90))
    private fun attempt(tender: LocalTenderViewV1 = offset, status: String = "ATTESTED_PAID", active: OperationId? = null) =
        LocalRefundAttemptViewV1(id(30), id(20), status, tender, egp(8_000), ownAttempt = true, intentOperationId = OperationId(id(31).value),
            activeRecordingOperationId = active, completedRecordingOperationId = if (status == "ATTESTED_PAID") OperationId(id(31).value) else null,
            cashSessionId = id(40), cashSessionRevision = Revision(3))
    private fun view(attempt: LocalRefundAttemptViewV1) = LocalAcceptedReturnViewV1(id(1), id(2), id(3), id(4), Quantity(id(5), 1, 0),
        Revision(2), "INSPECTED_SELLABLE", egp(10_000), egp(8_000), egp(2_000), "PART_REFUNDED",
        listOf(LocalRefundObligationViewV1(id(20), LocalTenderViewV1("CASH"), egp(10_000))), listOf(attempt))

    @Test fun settledOffsetLeavesOnlyTheResidualDueByTheOriginalTender() {
        val due = retailRefundDues(view(attempt())).single()
        assertEquals(egp(2_000), due.amount)
        assertEquals("CASH", due.tender)
    }

    @Test fun anOffsetMustBeSettledAndExactlyShaped() {
        assertFailsWith<Exception> { requireAcceptedRefund(view(attempt(LocalTenderViewV1("EXCHANGE_OFFSET")))) }
        assertFailsWith<Exception> { requireAcceptedRefund(view(attempt(offset.copy(policyRevision = Revision(1))))) }
        assertFailsWith<Exception> { requireAcceptedRefund(view(attempt(status = "AWAITING_ATTESTATION"))) }
        assertFailsWith<Exception> { requireAcceptedRefund(view(attempt(LocalTenderViewV1("CARD", id(91), Revision(1))))) }
    }
}
