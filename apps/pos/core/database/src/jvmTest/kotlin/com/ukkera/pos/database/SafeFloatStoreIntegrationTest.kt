package com.ukkera.pos.database

import com.ukkera.pos.sales.store.LocalCashCommandCodec
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalCashHandler
import com.ukkera.pos.sales.store.LocalCashRequestV1
import com.ukkera.pos.sales.store.LocalSafeFloatCodec
import com.ukkera.pos.sales.store.LocalSafeFloatHandler
import com.ukkera.pos.sales.store.LocalSafeFloatPageV1
import com.ukkera.pos.sales.store.LocalSafeFloatQueryV1
import com.ukkera.pos.sales.store.LocalSafeFloatReadHandler
import com.ukkera.pos.sales.store.LocalSafeFloatRefusalV1
import com.ukkera.pos.sales.store.LocalSafeFloatRequestV1
import com.ukkera.pos.sales.store.LocalSafeFloatResultV1
import com.ukkera.pos.sales.store.LocalSafeFloatStatusV1
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * NOT independent: self-authored by the implementer of the safe-float command with synthetic
 * identities, grants and money. Real file-backed SQLite and the public typed handlers; every
 * drawer, custodian, safe and session row is created through LocalCashHandler commands.
 */
class SafeFloatStoreIntegrationTest {
    private val cashier = TEST_ACTOR
    private val safeKeeper = testId(41)
    private val manager = testId(42)
    private val drawer = testId(501)
    private val safe = testId(502)
    private val session = testId(503)
    private var serial = 700
    private fun next(): DomainId = testId(serial++)
    private fun egp(minor: Long) = Money(Currency.EGP, minor)
    private fun evidence() = LocalCashEvidenceV1(next(), TEST_TIME)

    private val authorization = TransactionAuthorizationSource { principal, view ->
        val state = view.aggregate(AUTH_KEY)?.payload?.copyBytes()?.decodeToString()
        if (principal.kind != PrincipalKind.LOCAL_EMPLOYEE || state == null) null else AuthorizationSnapshot(
            principal.subjectId, principal.kind, principal.scope, principal.authenticationId, principal.generation,
            principal.deviceId, principal.admissionRevision, Revision(1), true, true, true, AuthenticationPurpose.INTERACTIVE,
            listOf(SecurityAction.CASH_OPEN, SecurityAction.CASH_TRANSFER, SecurityAction.CASH_RECEIVE).map {
                PermissionGrant(Permission(it, PermissionVerb.PERFORM), TEST_SCOPE, GrantEffect.ALLOW,
                    PermissionLimits(Money(Currency.EGP, 1_000_000)))
            } + listOf(SecurityAction.CASH_CONFIGURE, SecurityAction.CASH_READ, SecurityAction.OPERATION_RESULT_RECOVERY).map {
                PermissionGrant(Permission(it, PermissionVerb.PERFORM), TEST_SCOPE, GrantEffect.ALLOW)
            })
    }

    private fun store(kernel: StoreFixture) = AtomicMasterStore(kernel.database, TEST_SCOPE, TEST_AUTHORITY,
        listOf(LocalCashHandler(), LocalSafeFloatHandler()), authorization, kernel.runtime, MasterClock { TEST_TIME },
        readHandlers = listOf(LocalSafeFloatReadHandler()))

    private suspend fun AtomicMasterStore.cash(actor: DomainId, request: LocalCashRequestV1): MutationReply.Recorded =
        assertIs<MutationReply.Recorded>(execute(testPrincipal(actor), MutationRequest(OperationId(next().value),
            com.ukkera.pos.sales.store.LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(request)))).also {
            assertTrue(it.committed, it.code)
        }

    private suspend fun AtomicMasterStore.floatRaw(actor: DomainId, request: LocalSafeFloatRequestV1): MutationReply =
        execute(testPrincipal(actor), MutationRequest(OperationId(next().value), LocalSafeFloatCodec.COMMAND, LocalSafeFloatCodec.encode(request)))

    private suspend fun AtomicMasterStore.float(actor: DomainId, request: LocalSafeFloatRequestV1): MutationReply.Recorded =
        assertIs<MutationReply.Recorded>(floatRaw(actor, request))

    /** The kernel's current owner-private grant check, before any domain evaluation. */
    private fun denied(reply: MutationReply) =
        assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(reply).reason)

    private suspend fun AtomicMasterStore.safePage(actor: DomainId): LocalSafeFloatPageV1 = LocalSafeFloatCodec.page(
        assertIs<ReadReply.Data>(read(testPrincipal(actor), LocalSafeFloatCodec.request(LocalSafeFloatQueryV1.Safe(safe)))).payload)

    private suspend fun AtomicMasterStore.sessionPage(actor: DomainId): LocalSafeFloatPageV1 = LocalSafeFloatCodec.page(
        assertIs<ReadReply.Data>(read(testPrincipal(actor), LocalSafeFloatCodec.request(LocalSafeFloatQueryV1.Session(session)))).payload)

    private fun refused(reply: MutationReply.Recorded): LocalSafeFloatRefusalV1 {
        assertEquals(false, reply.committed)
        return assertIs<LocalSafeFloatResultV1.Refused>(LocalSafeFloatCodec.result(reply.result)).reason
    }

    /** Configure drawer/custodians/safe, open the cashier's session and fund the safe with a real drop + receipt. */
    private suspend fun AtomicMasterStore.prepare(opening: Long = 100_000, drop: Long = 50_000) {
        cash(manager, LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic drawer", Currency.EGP))
        cash(manager, LocalCashRequestV1.ConfigureCustodian(cashier))
        cash(manager, LocalCashRequestV1.ConfigureCustodian(safeKeeper))
        cash(manager, LocalCashRequestV1.ConfigureSafe(safe, safeKeeper, "Synthetic safe", Currency.EGP))
        cash(cashier, LocalCashRequestV1.Open(session, drawer, Revision(0), Revision(0),
            BusinessDate(LocalDate(2026, 10, 23)), next(), egp(opening), evidence()))
        val transfer = next()
        cash(cashier, LocalCashRequestV1.DropToSafe(session, Revision(0), transfer, safe, Revision(0), egp(drop), evidence()))
        cash(safeKeeper, LocalCashRequestV1.ReceiveSafe(transfer, Revision(0), Revision(1), Revision(0), evidence()))
    }

    @Test fun issueThenExactCountedReceiptMovesCustodyIntoTheDrawer() = runTest {
        val kernel = StoreFixture()
        try {
            kernel.seed()
            val store = store(kernel)
            store.prepare()
            val available = assertIs<LocalSafeFloatPageV1.Safe>(store.safePage(safeKeeper))
            assertEquals(egp(50_000), available.available)
            assertEquals(Revision(0), available.ledgerRevision)

            val floatId = next()
            val issued = store.float(safeKeeper, LocalSafeFloatRequestV1.Issue(safe, available.safeRevision, Revision(0),
                session, floatId, egp(20_000), evidence()))
            assertTrue(issued.committed, issued.code)
            val record = assertIs<LocalSafeFloatResultV1.Recorded>(LocalSafeFloatCodec.result(issued.result))
            assertEquals(LocalSafeFloatStatusV1.AWAITING_RECEIPT, record.float.status)
            assertEquals(cashier, record.float.receivingCustodianId)

            val pending = assertIs<LocalSafeFloatPageV1.Safe>(store.safePage(safeKeeper))
            assertEquals(egp(30_000), pending.available)
            assertEquals(listOf(floatId), pending.awaiting.map { it.id })

            val own = assertIs<LocalSafeFloatPageV1.Session>(store.sessionPage(cashier))
            assertEquals(listOf(floatId), own.floats.map { it.id })
            assertEquals(Revision(2), own.sessionRevision) // The issue did not touch the drawer session.

            assertEquals(LocalSafeFloatRefusalV1.COUNT_MISMATCH, refused(store.float(cashier,
                LocalSafeFloatRequestV1.Receive(floatId, Revision(0), Revision(2), egp(19_900), evidence()))))

            val received = store.float(cashier, LocalSafeFloatRequestV1.Receive(floatId, Revision(0), Revision(2), egp(20_000), evidence()))
            assertTrue(received.committed, received.code)
            val after = assertIs<LocalSafeFloatResultV1.Recorded>(LocalSafeFloatCodec.result(received.result))
            assertEquals(LocalSafeFloatStatusV1.RECEIVED, after.float.status)
            assertEquals(Revision(3), after.sessionRevision)
            assertEquals(LocalSafeFloatRefusalV1.ALREADY_SETTLED, refused(store.float(cashier,
                LocalSafeFloatRequestV1.Receive(floatId, Revision(1), Revision(3), egp(20_000), evidence()))))
            val settled = assertIs<LocalSafeFloatPageV1.Safe>(store.safePage(safeKeeper))
            assertTrue(settled.awaiting.isEmpty())
            assertEquals(egp(30_000), settled.available)
        } finally { kernel.close() }
    }

    @Test fun masterRefusesOverdrawWrongActorsAndRecallRestoresAvailability() = runTest {
        val kernel = StoreFixture()
        try {
            kernel.seed()
            val store = store(kernel)
            store.prepare()
            assertEquals(LocalSafeFloatRefusalV1.INSUFFICIENT_SAFE_CASH, refused(store.float(safeKeeper,
                LocalSafeFloatRequestV1.Issue(safe, Revision(1), Revision(0), session, next(), egp(50_001), evidence()))))
            // Client state never grants authority: the cashier cannot issue from a safe it does not hold.
            denied(store.floatRaw(cashier, LocalSafeFloatRequestV1.Issue(safe, Revision(1), Revision(0), session, next(), egp(100), evidence())))
            assertEquals(LocalSafeFloatPageV1.NotAvailable, store.safePage(cashier))
            assertEquals(LocalSafeFloatPageV1.NotAvailable, store.sessionPage(safeKeeper))

            val floatId = next()
            assertTrue(store.float(safeKeeper, LocalSafeFloatRequestV1.Issue(safe, Revision(1), Revision(0), session, floatId,
                egp(10_000), evidence())).committed)
            assertEquals(LocalSafeFloatRefusalV1.STALE, refused(store.float(safeKeeper,
                LocalSafeFloatRequestV1.Issue(safe, Revision(1), Revision(0), session, next(), egp(100), evidence()))))
            assertEquals(LocalSafeFloatRefusalV1.DUPLICATE, refused(store.float(safeKeeper,
                LocalSafeFloatRequestV1.Issue(safe, Revision(1), Revision(1), session, floatId, egp(100), evidence()))))
            denied(store.floatRaw(cashier, LocalSafeFloatRequestV1.Recall(floatId, Revision(0), Revision(1), evidence(), "not mine")))
            val recalled = store.float(safeKeeper, LocalSafeFloatRequestV1.Recall(floatId, Revision(0), Revision(1), evidence(),
                "cashier did not take the float"))
            assertTrue(recalled.committed, recalled.code)
            val page = assertIs<LocalSafeFloatPageV1.Safe>(store.safePage(safeKeeper))
            assertEquals(egp(50_000), page.available)
            assertEquals(egp(10_000), page.recalled)
            assertTrue(page.awaiting.isEmpty())
            assertEquals(LocalSafeFloatRefusalV1.ALREADY_SETTLED, refused(store.float(cashier,
                LocalSafeFloatRequestV1.Receive(floatId, Revision(1), Revision(2), egp(10_000), evidence()))))
        } finally { kernel.close() }
    }
}
