package com.ukkera.pos.sales

import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CashSessionsTest {
    @Test fun goldenCashCountUsesActualDispositionAndExternalHasNoDrawerEffect() = runTest {
        val f = SalesFixture()
        val previous = f.retail(f.quote("200"), opening = money(0))
        val oldPayment = previous.cash(egp(200))
        val oldSale = previous.finalize().value
        val accepted = f.accept(oldSale.entitlement, f.quantity(1)).acceptedReturn

        val today = f.retail(f.quote("8600"))
        today.cash(egp(4_600), egp(5_000), egp(400))
        today.external(egp(4_000))
        today.finalize()
        var session = f.payRefund(accepted, today.session, oldPayment, egp(200)).session
        session = CashSessions.recordNonSale(f.command(SecurityAction.CASH_MOVE, session.id, session.revision, egp(100)),
            session, session.revision, f.nextId(), CashMovementKind.NON_SALE_PAY_IN, egp(100), f.evidence(), "counted non-sale inflow").value
        session = CashSessions.recordNonSale(f.command(SecurityAction.CASH_MOVE, session.id, session.revision, egp(150)),
            session, session.revision, f.nextId(), CashMovementKind.OPERATING_EXPENSE, egp(150), f.evidence(), "actual operating expense").value
        val drop = CashSessions.dropToSafe(f.command(SecurityAction.CASH_TRANSFER, session.id, session.revision, egp(3_000)),
            session, session.revision, f.nextId(), f.safe, egp(3_000), f.evidence()).value
        assertEquals(egp(2_350), drop.session.expectedCash)
        assertEquals(egp(4_000), drop.session.totals.externalCollections)
        assertEquals(egp(5_000), drop.session.totals.received)
        assertEquals(egp(400), drop.session.totals.change)
        val receiver = SafeCustodyReadSet(f.scope, f.safe, f.safeOwner, Revision(3))
        val receipt = CashSessions.receiveSafeTransfer(
            f.command(SecurityAction.CASH_RECEIVE, drop.transfer.id, drop.transfer.revision, egp(3_000), f.safeOwner),
            drop.transfer, drop.transfer.revision, drop.session, drop.session.revision, receiver, f.evidence(f.safeOwner))
        session = receipt.value.session
        assertEquals(egp(2_350), session.expectedCash)
        assertEquals(egp(2_350), session.totals.expected(session.opening.amount))
        assertTrue(receipt.effects.none { it is CashMovement })
        assertEquals(1, receipt.effects.filterIsInstance<SafeCustodyReceipt>().size)
        val work = ClosingWorkReadSet(f.scope, f.owner, Revision(6), emptyList(), emptyList())
        session = CashSessions.beginClosing(f.command(SecurityAction.CASH_CLOSE, session.id, session.revision),
            session, session.revision, work, work.revision).value
        val assigned = f.assignment(session)
        val closing = CashSessions.submitClose(f.command(SecurityAction.CASH_CLOSE, session.id, session.revision, egp(2_360)),
            session, session.revision, assigned, CountedCash(egp(2_360), f.evidence()), egp(500), f.nextId(), f.safe,
            "counted overage retained for review", f.evidence())
        val report = assertNotNull(closing.value.session.closeReport)
        assertEquals(egp(2_350), report.cut.expected)
        assertEquals(egp(2_360), report.count.amount)
        assertEquals(egp(10), report.variance)
        assertEquals(egp(500), report.retainedFloat)
        assertEquals(egp(1_860), report.actualSafeDelivery)
        assertEquals(egp(1_860), closing.value.transfer?.amount)
        assertTrue(closing.effects.none { it is CashMovement }) // No fictional expense/employee debt.
        assertTrue(closing.value.session.pendingAttemptIds.isEmpty())

        val closed = closing.value.session
        val reviewed = CashSessions.review(f.command(SecurityAction.CASH_REVIEW, closed.id, closed.revision, actor = f.other),
            closed, closed.revision, f.evidence(f.other), "count preserved; later evidence can link").value
        assertSame(report, reviewed.closeReport)
        assertSame(report, reviewed.review?.originalReport)
        assertEquals(CashSessionStatus.REVIEWED, reviewed.status)
        assertRule(SalesRule.IMMUTABLE_RECORD) {
            CashSessions.cancelClosing(f.command(SecurityAction.CASH_CLOSE, reviewed.id, reviewed.revision), reviewed, reviewed.revision)
        }
    }

    @Test fun openingRequiresCountedExclusiveDrawerAndCustodianRowsAndReturnsUniqueConstraints() = runTest {
        val f = SalesFixture()
        val empty = f.assignment()
        suspend fun open(read: CashAssignmentReadSet): SalesTransition<CashSession> = CashSessions.open(
            f.command(SecurityAction.CASH_OPEN, read.drawerId, read.drawerRevision, egp(100)), read, read.drawerRevision,
            read.custodianRevision, f.nextId(), LocalDate(2026, 10, 23), CountedCash(egp(100), f.evidence()), f.nextId())
        assertRule(SalesRule.DRAWER_ASSIGNED) { open(empty.copy(activeDrawerSessionId = f.nextId())) }
        assertRule(SalesRule.CUSTODIAN_ASSIGNED) { open(empty.copy(activeCustodianSessionId = f.nextId())) }
        assertRule(SalesRule.CUSTODY_UNRESOLVED) { open(empty.copy(unresolvedClosingTransferId = f.nextId())) }
        val result = open(empty)
        assertEquals(setOf(SalesUniqueKind.CASH_SESSION, SalesUniqueKind.OPENING_FLOAT_SOURCE, SalesUniqueKind.ACTIVE_DRAWER, SalesUniqueKind.ACTIVE_CUSTODIAN), result.unique.map { it.kind }.toSet())
        assertEquals(2, result.versions.size)
        assertEquals(egp(100), result.value.expectedCash)
        assertTrue(result.effects.none { it is CashMovement })
        assertRule(SalesRule.REVISION_CONFLICT) {
            CashSessions.open(f.command(SecurityAction.CASH_OPEN, empty.drawerId, empty.drawerRevision, egp(100)), empty,
                Revision(0), empty.custodianRevision, f.nextId(), LocalDate(2026, 10, 23), CountedCash(egp(100), f.evidence()), f.nextId())
        }
    }

    @Test fun freezeCutBlocksMoneyAndUnsettledOrdersUntilExplicitSafeCancellation() = runTest {
        val f = SalesFixture()
        val original = f.openCash(egp(50))
        val unsettled = ClosingWorkReadSet(f.scope, f.owner, Revision(2), listOf(f.nextId()), emptyList())
        assertRule(SalesRule.CLOSING_BLOCKED) {
            CashSessions.beginClosing(f.command(SecurityAction.CASH_CLOSE, original.id, original.revision), original, original.revision, unsettled, unsettled.revision)
        }
        val clear = ClosingWorkReadSet(f.scope, f.owner, Revision(3), emptyList(), emptyList())
        val closing = CashSessions.beginClosing(f.command(SecurityAction.CASH_CLOSE, original.id, original.revision),
            original, original.revision, clear, clear.revision).value
        assertRule(SalesRule.INVALID_STATE) {
            CashSessions.recordNonSale(f.command(SecurityAction.CASH_MOVE, closing.id, closing.revision, egp(1)), closing,
                closing.revision, f.nextId(), CashMovementKind.NON_SALE_PAY_IN, egp(1), f.evidence(), "not below closing cut")
        }
        assertEquals(egp(50), original.expectedCash)
        assertEquals(original.cashLedgerRevision, closing.closingCut?.cashLedgerRevision)
        val reopened = CashSessions.cancelClosing(f.command(SecurityAction.CASH_CLOSE, closing.id, closing.revision), closing, closing.revision).value
        assertEquals(CashSessionStatus.OPEN, reopened.status)
        assertEquals(original.expectedCash, reopened.expectedCash)
    }

    @Test fun safeReceiptNeedsActualDifferentReceivingCustodianAndCannotDebitTwice() = runTest {
        val f = SalesFixture()
        val cash = f.openCash(egp(100))
        val sent = CashSessions.dropToSafe(f.command(SecurityAction.CASH_TRANSFER, cash.id, cash.revision, egp(30)), cash,
            cash.revision, f.nextId(), f.safe, egp(30), f.evidence()).value
        val wrongReceiver = SafeCustodyReadSet(f.scope, f.safe, f.other, Revision(1))
        assertRule(SalesRule.OWNER_REQUIRED) {
            CashSessions.receiveSafeTransfer(f.command(SecurityAction.CASH_RECEIVE, sent.transfer.id, sent.transfer.revision, egp(30), f.safeOwner),
                sent.transfer, sent.transfer.revision, sent.session, sent.session.revision, wrongReceiver, f.evidence(f.safeOwner))
        }
        val ready = wrongReceiver.copy(custodianId = f.safeOwner)
        val received = CashSessions.receiveSafeTransfer(f.command(SecurityAction.CASH_RECEIVE, sent.transfer.id, sent.transfer.revision, egp(30), f.safeOwner),
            sent.transfer, sent.transfer.revision, sent.session, sent.session.revision, ready, f.evidence(f.safeOwner)).value
        assertEquals(egp(70), received.session.expectedCash)
        assertEquals(sent.session.cashLedgerRevision, received.session.cashLedgerRevision)
        assertFalse(received.session.pendingTransferIds.contains(sent.transfer.id))
        assertRule(SalesRule.IMMUTABLE_RECORD) {
            CashSessions.receiveSafeTransfer(f.command(SecurityAction.CASH_RECEIVE, received.transfer.id, received.transfer.revision, egp(30), f.safeOwner),
                received.transfer, received.transfer.revision, received.session, received.session.revision, ready, f.evidence(f.safeOwner))
        }
    }

    @Test fun closeDispositionRequiresActualDeliveryAndCannotUseExpectedCashForTransfer() = runTest {
        val f = SalesFixture()
        var session = f.openCash(egp(100))
        val work = ClosingWorkReadSet(f.scope, f.owner, Revision(1), emptyList(), emptyList())
        session = CashSessions.beginClosing(f.command(SecurityAction.CASH_CLOSE, session.id, session.revision), session, session.revision, work, work.revision).value
        assertRule(SalesRule.INVALID_EVIDENCE) {
            CashSessions.submitClose(f.command(SecurityAction.CASH_CLOSE, session.id, session.revision, egp(90)), session, session.revision,
                f.assignment(session), CountedCash(egp(90), f.evidence()), egp(20), f.nextId(), f.safe, "shortage")
        }
        val closed = CashSessions.submitClose(f.command(SecurityAction.CASH_CLOSE, session.id, session.revision, egp(90)), session, session.revision,
            f.assignment(session), CountedCash(egp(90), f.evidence()), egp(20), f.nextId(), f.safe, "shortage", f.evidence()).value
        assertEquals(egp(70), closed.transfer?.amount)
        assertEquals(egp(-10), closed.session.closeReport?.variance)
        val transfer = assertNotNull(closed.transfer)
        val blockedDrawer = f.assignment(actor = f.other, drawer = session.drawerId, unresolved = transfer.id)
        assertRule(SalesRule.CUSTODY_UNRESOLVED) {
            CashSessions.open(f.command(SecurityAction.CASH_OPEN, blockedDrawer.drawerId, blockedDrawer.drawerRevision, egp(20), f.other),
                blockedDrawer, blockedDrawer.drawerRevision, blockedDrawer.custodianRevision, f.nextId(), LocalDate(2026, 10, 23),
                CountedCash(egp(20), f.evidence(f.other)), f.nextId())
        }
        val acknowledged = CashSessions.receiveSafeTransfer(
            f.command(SecurityAction.CASH_RECEIVE, transfer.id, transfer.revision, egp(70), f.safeOwner), transfer, transfer.revision,
            closed.session, closed.session.revision, SafeCustodyReadSet(f.scope, f.safe, f.safeOwner, Revision(1)), f.evidence(f.safeOwner), blockedDrawer)
        assertSame(closed.session, acknowledged.value.session)
        assertEquals(1, acknowledged.effects.filterIsInstance<ClosingCustodyReleased>().size)
    }
}
