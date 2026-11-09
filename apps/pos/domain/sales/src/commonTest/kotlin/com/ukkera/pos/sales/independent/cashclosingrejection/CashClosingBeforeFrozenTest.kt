package com.ukkera.pos.sales.independent.cashclosingrejection

import com.ukkera.pos.sales.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Instant

/** CCJR-v1 supplied-value slices only: no command, principal, native cut or durable store. */
class CashClosingBeforeFrozenTest {
    @Test fun lawfulBeforeGuardsDoNotInventRejectionFromAnAbsentAfterImage() {
        val f = Values()
        f.unchanged {
            CashSessions.requireHistoricalBeginClosingBefore(f.open, f.owner, f.open.revision, f.work, f.work.revision)
            CashSessions.requireHistoricalCancelClosingBefore(f.closing, f.owner, f.closing.revision)
            CashCountedClosing.requireHistoricalRecordBefore(f.closing, f.owner, f.closing.revision,
                null, null, f.first.count, null)
            CashCountedClosing.requireHistoricalRecordBefore(f.counted, f.owner, f.counted.revision,
                f.second, f.second.id, CountedCash(f.second.count.amount, f.evidence(42)), f.second.reason)
            CashCountedClosing.requireHistoricalDeliveryBefore(f.counted, f.owner, f.counted.revision,
                f.second, f.retained, f.safe, f.evidence(43))
            CashSessions.requireHistoricalSafeReceiptBefore(f.sent, f.delivered, f.receiver, f.sent.revision,
                f.delivered.revision, f.custody, f.receipt, f.assignment, f.second)
            CashCountedClosing.requireHistoricalSubmitBefore(f.delivered, f.owner, f.delivered.revision,
                f.assignment, f.second, f.retained, f.received, "Counted overage")
            CashSessions.requireHistoricalReviewBefore(f.submitted, f.reviewer, f.submitted.revision,
                f.evidence(44, f.reviewer), "Original count preserved")
        }
    }

    @Test fun submittedCountsAndReportCannotBeOrdinarilyReopenedOrRecounted() {
        val f = Values()
        f.unchanged {
            refused { CashSessions.requireHistoricalBeginClosingBefore(f.submitted, f.owner,
                f.submitted.revision, f.work, f.work.revision) }
            refused { CashSessions.requireHistoricalCancelClosingBefore(f.submitted, f.owner, f.submitted.revision) }
            refused { CashCountedClosing.requireHistoricalRecordBefore(f.submitted, f.owner, f.submitted.revision,
                f.second, f.second.id, CountedCash(f.cash(230000), f.evidence(45)), "Attempted replacement") }
        }
    }

    @Test fun reviewNeedsAnActualSubmittedReportAndADifferentReviewer() {
        val f = Values()
        f.unchanged {
            refused { CashSessions.requireHistoricalReviewBefore(f.open, f.reviewer, f.open.revision,
                f.evidence(46, f.reviewer), "No report exists") }
            refused { CashSessions.requireHistoricalReviewBefore(f.submitted, f.owner, f.submitted.revision,
                f.evidence(47), "Self review") }
        }
    }

    @Test fun actualCountAndDispositionRemainSeparateFromExpectedAndExternalCash() {
        val f = Values()
        assertEquals(235000L, f.open.totals.expected(f.open.opening.amount).minor)
        assertEquals(400000L, f.second.cut.externalCollections.minor)
        assertEquals(235500L, f.first.count.amount.minor)
        assertEquals(236000L, f.second.count.amount.minor)
        assertEquals(1000L, f.report.variance.minor)
        assertEquals(50000L, f.report.retainedFloat.minor)
        assertEquals(186000L, f.report.actualSafeDelivery.minor)
        assertEquals(f.sent.id, f.received.id)
        assertEquals(f.receiver, f.received.receipt?.actorId)
        f.unchanged {
            refused { CashCountedClosing.requireHistoricalSubmitBefore(f.delivered, f.owner, f.delivered.revision,
                f.assignment, f.second, f.retained, f.sent, "Receipt still unresolved") }
            refused { CashCountedClosing.requireHistoricalSubmitBefore(f.delivered, f.owner, f.delivered.revision,
                f.assignment, f.second, f.open.opening.amount, f.received, "Wrong retained amount") }
        }
    }

    @Test fun receiptGuardsRejectWrongRecipientStaleRevisionAndAlreadyReceivedData() {
        val f = Values()
        f.unchanged {
            refused { CashSessions.requireHistoricalSafeReceiptBefore(f.sent, f.delivered, f.reviewer,
                f.sent.revision, f.delivered.revision, f.custody, f.evidence(48, f.reviewer), f.assignment, f.second) }
            refused { CashSessions.requireHistoricalSafeReceiptBefore(f.received, f.delivered, f.receiver,
                Revision(0), f.delivered.revision, f.custody, f.receipt, f.assignment, f.second) }
            refused { CashSessions.requireHistoricalSafeReceiptBefore(f.received, f.submitted, f.receiver,
                f.received.revision, f.submitted.revision, f.custody, f.receipt, f.assignment, null) }
        }
    }

    @Test fun exactBeforeOwnerRevisionAndCountSourcesCannotBeReplaced() {
        val f = Values()
        f.unchanged {
            refused { CashSessions.requireHistoricalCancelClosingBefore(f.closing, f.reviewer, f.closing.revision) }
            refused { CashSessions.requireHistoricalCancelClosingBefore(f.closing, f.owner, Revision(0)) }
            refused { CashCountedClosing.requireHistoricalRecordBefore(f.counted, f.owner, f.counted.revision,
                f.second, f.first.id, CountedCash(f.second.count.amount, f.evidence(49)), f.second.reason) }
            refused { CashCountedClosing.requireHistoricalDeliveryBefore(f.counted, f.owner, f.counted.revision,
                f.first, f.retained, f.safe, f.evidence(50)) }
            refused { CashCountedClosing.requireHistoricalSubmitBefore(f.delivered, f.owner, f.delivered.revision,
                f.assignment.copy(activeDrawerSessionId = null), f.second, f.retained, f.received, "Missing custody") }
        }
    }

    @Test fun missingCountedDispositionAndWrongEvidenceNeverCreateAReceipt() {
        val f = Values()
        f.unchanged {
            refused { CashSessions.requireHistoricalSafeReceiptBefore(f.sent, f.delivered, f.receiver,
                f.sent.revision, f.delivered.revision, f.custody, f.receipt, f.assignment, null) }
            refused { CashSessions.requireHistoricalSafeReceiptBefore(f.sent, f.delivered, f.receiver,
                f.sent.revision, f.delivered.revision, f.custody, f.evidence(51, f.owner), f.assignment, f.second) }
            refused { CashCountedClosing.requireHistoricalRecordBefore(f.closing, f.owner, f.closing.revision,
                null, null, CountedCash(f.cash(236000), f.evidence(52, f.receiver)), null) }
        }
    }

    private fun refused(block: () -> Unit) { assertFailsWith<SalesRuleViolation>(block = block) }
}

/** Fixed semantic vectors from CCJR-v1; these constructors provide DATA, never native evidence. */
private class Values {
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-${n.toString().padStart(12, '0')}")
    val scope = BranchScope(id(1), id(2), id(3))
    val owner = id(4)
    val receiver = id(5)
    val reviewer = id(6)
    val safe = id(7)
    fun cash(minor: Long) = Money(Currency.EGP, minor)
    fun evidence(n: Int, actor: DomainId = owner) = CashierEvidence(id(n), actor, Instant.parse("2026-11-03T04:00:00Z"))
    val retained = cash(50000)
    private val totals = CashTotals(cash(500000), cash(40000), cash(20000), cash(10000), cash(15000),
        cash(300000), cash(400000), cash(0))
    private val cut = CashClosingCut(Revision(8), cash(235000), cash(400000), cash(0))
    val first = CashClosingCount(scope, id(8), Revision(0), null, null, Revision(11), Revision(12),
        cut, CountedCash(cash(235500), evidence(20)), null, OperationId(id(30).value))
    val second = CashClosingCount(scope, id(8), Revision(1), first.id, first.id, Revision(11), Revision(13),
        cut, CountedCash(cash(236000), evidence(21)), "Corrected denomination entry; preserve first count and append recount",
        OperationId(id(31).value))
    val sent = SafeTransfer(id(10), scope, Revision(0), id(8), id(9), owner, safe, cash(186000),
        evidence(22), true, SafeTransferStatus.AWAITING_RECEIPT)
    val receipt = evidence(23, receiver)
    val received = SafeTransfer(sent.id, scope, Revision(1), sent.senderSessionId, sent.senderDrawerId,
        sent.senderCustodianId, sent.receivingCustodyId, sent.amount, sent.sentEvidence, true, SafeTransferStatus.RECEIVED, receipt)
    val report = CashCloseReport(id(8), cut, second.count, cash(1000), retained, cash(186000), sent.id,
        "Counted overage", owner, OperationId(id(32).value))
    private fun session(status: CashSessionStatus, revision: Long) = CashSession(id(8), scope, id(9), owner,
        LocalDate(2026, 11, 3), Revision(revision), CountedCash(cash(100000), evidence(24)), id(11), totals,
        cash(235000), cash(0), Revision(8), status, closingCut = if (status == CashSessionStatus.OPEN) null else cut,
        closeReport = if (status == CashSessionStatus.SUBMITTED_CLOSED) report else null)
    val open = session(CashSessionStatus.OPEN, 10)
    val closing = session(CashSessionStatus.CLOSING, 11)
    val counted = session(CashSessionStatus.CLOSING, 13)
    val delivered = session(CashSessionStatus.CLOSING, 14)
    val submitted = session(CashSessionStatus.SUBMITTED_CLOSED, 15)
    val work = ClosingWorkReadSet(scope, owner, Revision(6), emptyList(), emptyList())
    val assignment = CashAssignmentReadSet(scope, id(9), Revision(3), id(8), null, owner, Revision(3), id(8))
    val custody = SafeCustodyReadSet(scope, safe, receiver, Revision(2))

    fun unchanged(block: () -> Unit) {
        fun snapshots() = listOf(open, closing, counted, delivered, submitted).map(SalesSnapshotCodec::encodeCashSession) +
            listOf(sent, received).map(SalesSnapshotCodec::encodeSafeTransfer) +
            listOf(first.toString(), second.toString(), report.toString())
        val before = snapshots()
        block()
        assertEquals(before, snapshots(), "CCJR-v1 supplied original DATA remains unchanged")
    }
}
