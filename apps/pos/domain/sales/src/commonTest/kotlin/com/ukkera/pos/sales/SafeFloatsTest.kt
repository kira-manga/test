package com.ukkera.pos.sales

import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NOT independent: self-authored by the implementer of SafeFloats with synthetic data.
 * Expectations derive from POS_OPERATIONAL_SPECIFICATION CASH-01..12 (floats are custody
 * transfers, not revenue; an unacknowledged receipt remains a custody exception).
 */
class SafeFloatsTest {
    private fun ledger(f: SalesFixture, revision: Long = 0, issued: Long = 0, recalled: Long = 0) =
        SafeFloatLedger(f.scope, f.safe, Revision(revision), egp(issued), egp(recalled))

    private fun custody(f: SalesFixture) = SafeCustodyReadSet(f.scope, f.safe, f.safeOwner, Revision(3))

    private suspend fun issue(f: SalesFixture, session: CashSession, amount: Long = 300, received: Long = 1_000,
        current: SafeFloatLedger = ledger(f)): SalesTransition<SafeFloatWithLedger> =
        SafeFloats.issue(f.command(SecurityAction.CASH_TRANSFER, f.safe, current.revision, egp(amount), f.safeOwner),
            custody(f), egp(received), current, current.revision, session, f.nextId(), egp(amount), f.evidence(f.safeOwner))

    @Test fun issuedFloatIsNotDrawerCashUntilCountedReceiptAndNeverRevenue() = runTest {
        val f = SalesFixture()
        val session = f.openCash(egp(500))
        val issued = issue(f, session)
        val float = issued.value.float
        assertEquals(SafeFloatStatus.AWAITING_RECEIPT, float.status)
        assertEquals(egp(300), issued.value.ledger.issued)
        assertEquals(egp(700), issued.value.ledger.available(egp(1_000)))
        assertTrue(issued.effects.none { it is CashMovement || it is CashSessionChanged })
        assertEquals(listOf(SalesUniqueCheck(SalesUniqueKind.SAFE_FLOAT, float.id), SalesUniqueCheck(SalesUniqueKind.CASH_SOURCE, float.id)), issued.unique)

        val counted = CountedCash(egp(300), f.evidence())
        val received = SafeFloats.receive(f.command(SecurityAction.CASH_RECEIVE, float.id, float.revision, egp(300)),
            float, float.revision, session, session.revision, counted)
        val after = received.value.session
        assertEquals(egp(800), after.expectedCash)
        assertEquals(egp(300), after.totals.payIns)
        assertEquals(egp(0), after.totals.received) // Not a customer receipt / revenue.
        assertEquals(session.cashLedgerRevision.next(), after.cashLedgerRevision)
        assertEquals(SafeFloatStatus.RECEIVED, received.value.float.status)
        val movement = received.effects.filterIsInstance<CashMovement>().single()
        assertEquals(CashMovementKind.SAFE_FLOAT_RECEIPT, movement.kind)
        assertEquals(egp(300), movement.delta)
        assertEquals(float.id, movement.sourceId)
    }

    @Test fun safeCannotIssueMoreThanRecordedAvailableCash() = runTest {
        val f = SalesFixture()
        val session = f.openCash(egp(500))
        assertRule(SalesRule.INSUFFICIENT_DRAWER_CASH) { issue(f, session, amount = 600, received = 1_000, current = ledger(f, 2, 500, 0)) }
        // A recall restores availability: 1000 - 500 + 200 = 700.
        assertEquals(egp(300), issue(f, session, amount = 300, received = 1_000, current = ledger(f, 2, 600, 200)).value.float.amount)
    }

    @Test fun onlyTheSafeCustodianIssuesAndNeverToTheirOwnDrawer() = runTest {
        val f = SalesFixture()
        val session = f.openCash(egp(500))
        val current = ledger(f)
        assertRule(SalesRule.OWNER_REQUIRED) {
            SafeFloats.issue(f.command(SecurityAction.CASH_TRANSFER, f.safe, current.revision, egp(100), f.other),
                custody(f), egp(1_000), current, current.revision, session, f.nextId(), egp(100), f.evidence(f.other))
        }
        val own = f.openCash(egp(100), actor = f.safeOwner)
        assertRule(SalesRule.OWNER_REQUIRED) { issue(f, own, amount = 100) }
    }

    @Test fun permissionIsBoundToTheExactActionTargetAndAmount() = runTest {
        val f = SalesFixture()
        val session = f.openCash(egp(500))
        val current = ledger(f)
        assertRule(SalesRule.PERMISSION_DENIED) {
            SafeFloats.issue(f.command(SecurityAction.CASH_RECEIVE, f.safe, current.revision, egp(100), f.safeOwner),
                custody(f), egp(1_000), current, current.revision, session, f.nextId(), egp(100), f.evidence(f.safeOwner))
        }
        assertRule(SalesRule.PERMISSION_DENIED) {
            SafeFloats.issue(f.command(SecurityAction.CASH_TRANSFER, f.safe, current.revision, egp(99), f.safeOwner),
                custody(f), egp(1_000), current, current.revision, session, f.nextId(), egp(100), f.evidence(f.safeOwner))
        }
    }

    @Test fun shortOrOverCountIsRefusedAndWrongCashierCannotReceive() = runTest {
        val f = SalesFixture()
        val session = f.openCash(egp(500))
        val float = issue(f, session).value.float
        assertRule(SalesRule.INVALID_AMOUNT) {
            SafeFloats.receive(f.command(SecurityAction.CASH_RECEIVE, float.id, float.revision, egp(300)),
                float, float.revision, session, session.revision, CountedCash(egp(290), f.evidence()))
        }
        assertRule(SalesRule.OWNER_REQUIRED) {
            SafeFloats.receive(f.command(SecurityAction.CASH_RECEIVE, float.id, float.revision, egp(300), f.other),
                float, float.revision, session, session.revision, CountedCash(egp(300), f.evidence(f.other)))
        }
    }

    @Test fun recallRestoresSafeAvailabilityAndSettledFloatIsImmutable() = runTest {
        val f = SalesFixture()
        val session = f.openCash(egp(500))
        val issued = issue(f, session).value
        val float = issued.float
        val recalled = SafeFloats.recall(f.command(SecurityAction.CASH_TRANSFER, float.id, float.revision, egp(300), f.safeOwner),
            float, float.revision, issued.ledger, issued.ledger.revision, f.evidence(f.safeOwner), "cashier never took the float")
        assertEquals(SafeFloatStatus.RECALLED, recalled.value.float.status)
        assertNull(recalled.value.float.receipt)
        assertEquals(egp(300), recalled.value.ledger.recalled)
        assertEquals(egp(1_000), recalled.value.ledger.available(egp(1_000)))
        assertTrue(recalled.effects.none { it is CashMovement || it is CashSessionChanged })
        val gone = recalled.value.float
        assertRule(SalesRule.IMMUTABLE_RECORD) {
            SafeFloats.receive(f.command(SecurityAction.CASH_RECEIVE, gone.id, gone.revision, egp(300)),
                gone, gone.revision, session, session.revision, CountedCash(egp(300), f.evidence()))
        }
        assertRule(SalesRule.OWNER_REQUIRED) {
            SafeFloats.recall(f.command(SecurityAction.CASH_TRANSFER, float.id, float.revision, egp(300)),
                float, float.revision, issued.ledger, issued.ledger.revision, f.evidence(), "not the safe custodian")
        }
    }
}
