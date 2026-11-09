package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AtomicMasterStoreTest {
    @Test fun commitThenReopenReturnsOneOriginalResultAndOneEffect() = runTest {
        val first = StoreFixture(); first.seed()
        val request = first.request()
        val result = assertIs<MutationReply.Recorded>(first.store().execute(testPrincipal(), request))
        assertTrue(result.committed); assertFalse(result.replayed)
        assertEquals("1", result.result.copyBytes().decodeToString())
        assertEquals(1, first.handler.prepareCount)
        assertEquals(1L, first.count("operation_result")); assertEquals(1L, first.count("journal_envelope"))
        assertEquals(1L, first.count("audit_event")); assertEquals(2L, first.count("outbox_intent"))
        first.close()
        val second = StoreFixture(first.path)
        try {
            val replay = assertIs<MutationReply.Recorded>(second.store().execute(testPrincipal(), request))
            assertTrue(replay.replayed); assertEquals(result.result, replay.result)
            assertEquals(0, second.handler.prepareCount)
            assertIs<LookupReply.Found>(second.store().lookup(testPrincipal(), second.identity(request)))
            assertEquals(1L, second.count("operation_result"))
        } finally { second.close() }
    }

    @Test fun everyPrecommitFaultRollsBackAllFiveFamiliesAndRetryKeepsId() = runTest {
        for (point in StoreFaultPoint.entries.filter { it != StoreFaultPoint.AFTER_COMMIT }) {
            val f = StoreFixture(); f.seed()
            try {
                val request = f.request(input = CounterInput(claim = "drawer-1"))
                val reply = f.store(StoreFaultProbe { if (it == point) throw IOException("synthetic local fault") }).execute(testPrincipal(), request)
                assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(reply).reason, point.name)
                for (table in listOf("operation_result", "journal_envelope", "outbox_intent", "audit_event", "unique_claim")) assertEquals(0L, f.count(table), "$point/$table")
                assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request)).committed)
                assertEquals(1L, f.count("operation_result"))
            } finally { f.close() }
        }
    }

    @Test fun lostResponseAfterCommitIsUnknownNotRejectedAndRecoversSameResult() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val request = f.request()
            val unknown = f.store(StoreFaultProbe { if (it == StoreFaultPoint.AFTER_COMMIT) throw IOException("lost response") }).execute(testPrincipal(), request)
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(unknown).reason)
            assertEquals(1L, f.count("operation_result"))
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request)).replayed)
        } finally { f.close() }
    }

    @Test fun concurrentSameIdCommitsOnceDifferentPayloadConflicts() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val principal = testPrincipal(); val request = f.request()
            val replies = coroutineScope { List(32) { async { f.store().execute(principal, request) } }.awaitAll() }
            assertEquals(1, replies.count { it is MutationReply.Recorded && !it.replayed })
            assertEquals(31, replies.count { it is MutationReply.Recorded && it.replayed })
            assertIs<MutationReply.IdentityConflict>(f.store().execute(principal, f.request(input = CounterInput(delta = 2))))
            assertEquals(1L, f.count("operation_result"))
        } finally { f.close() }
    }

    @Test fun realCasAndUniqueConstraintsRejectLosingCommandsWithoutPartialEffects() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val p = testPrincipal()
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(p, f.request(input = CounterInput(claim = "drawer")))).committed)
            val cas = assertIs<MutationReply.Recorded>(f.store().execute(p, f.request(21, CounterInput(delta = 9))))
            assertFalse(cas.committed); assertEquals("revision.conflict", cas.code)
            val unique = assertIs<MutationReply.Recorded>(f.store().execute(p, f.request(22, CounterInput(id = testId(11), claim = "drawer"))))
            assertFalse(unique.committed); assertEquals("unique.conflict", unique.code)
            assertEquals(1L, f.count("unique_claim")); assertEquals(3L, f.count("operation_result"))
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(p, f.request(21, CounterInput(delta = 9)))).replayed)
        } finally { f.close() }
    }

    @Test fun rejectionIsDurableAndCannotBeTurnedIntoNewSuccessByRetry() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val request = f.request(input = CounterInput(reject = true))
            val rejected = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request))
            assertFalse(rejected.committed)
            val replay = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request))
            assertTrue(replay.replayed); assertEquals(rejected.result, replay.result)
            assertEquals(1L, f.count("outbox_intent"))
        } finally { f.close() }
    }

    @Test fun currentAuthIsReadInsideTransactionAndRecoveryDoesNotRequireOldActionGrant() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val p = testPrincipal(); val request = f.request()
            assertIs<MutationReply.Recorded>(f.store().execute(p, request))
            f.setAuth("no-grants")
            assertTrue(assertIs<MutationReply.Recorded>(f.store().execute(p, request)).replayed)
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(f.store().execute(p, f.request(21))).reason)
            f.setAuth("disabled")
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<LookupReply.Deferred>(f.store().lookup(p, f.identity(request))).reason)
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(f.store().execute(p, request)).reason)
        } finally { f.close() }
    }

    @Test fun wrongScopeAndActorDoNotInheritFinancialResult() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val request = f.request(); assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request))
            val wrong = testPrincipal(scope = BranchScope(TEST_SCOPE.organizationId, testId(80), TEST_SCOPE.branchId))
            assertEquals(DeferredReason.WRONG_SCOPE, assertIs<MutationReply.Deferred>(f.store().execute(wrong, request)).reason)
            f.setAuth("no-grants")
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(f.store().execute(testPrincipal(testId(50)), request)).reason)
        } finally { f.close() }
    }

    @Test fun supervisorLookupAuditsOriginalActorAndDoesNotReattributeIt() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val request = f.request(); assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), request))
            val read = assertIs<LookupReply.Found>(f.store().lookup(testPrincipal(testId(50)), f.identity(request)))
            assertEquals(TEST_ACTOR, read.result.originalActorId)
            assertEquals(2L, f.count("audit_event")); assertEquals(1L, f.count("operation_result"))
        } finally { f.close() }
    }

    @Test fun incompleteHistoryHoldsNewIdsAndNeverReturnsSafeAbsence() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val request = f.request(); val p = testPrincipal()
            f.absenceVerification = RuntimeVerification.UNAVAILABLE
            assertEquals(DeferredReason.HISTORY_INCOMPLETE, assertIs<MutationReply.Deferred>(f.store().execute(p, request)).reason)
            assertEquals(DeferredReason.HISTORY_INCOMPLETE, assertIs<LookupReply.Deferred>(f.store().lookup(p, f.identity(request))).reason)
            assertEquals(0L, f.count("operation_result"))
            f.absenceVerification = RuntimeVerification.VERIFIED
            f.restoreHold()
            assertEquals(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED, assertIs<MutationReply.Deferred>(f.store().execute(p, f.request(21))).reason)
            assertEquals(0L, f.count("operation_result"))
        } finally { f.close() }
    }

    @Test fun positiveResultSurvivesLaterHistoryUnavailabilityButStaleWriterCannotMutate() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val p = testPrincipal(); val request = f.request()
            f.store().execute(p, request); f.absenceVerification = RuntimeVerification.UNAVAILABLE
            assertIs<LookupReply.Found>(f.store().lookup(p, f.identity(request)))
            val stale = AuthorityIdentity(testId(100), TEST_AUTHORITY.deviceId, Revision(2))
            assertEquals(DeferredReason.WRITER_NOT_QUALIFIED, assertIs<MutationReply.Deferred>(f.store(authority = stale).execute(p, f.request(21))).reason)
            assertEquals(DeferredReason.WRITER_NOT_QUALIFIED, assertIs<MutationReply.Deferred>(f.store(runtime = UnqualifiedMasterRuntime).execute(p, f.request(21))).reason)
        } finally { f.close() }
    }

    @Test fun nestedTransactionCannotProducePrematureSuccessAndExpiredReadViewFails() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val p = testPrincipal(); val request = f.request()
            f.database.useWriterConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(f.store().execute(p, request)).reason)
                }
            }
            assertEquals(0L, f.count("operation_result"))
            f.store().execute(p, request)
            assertFailsWith<IllegalStateException> { f.handler.retainedView!!.aggregate(AUTH_KEY) }
        } finally { f.close() }
    }

    @Test fun deadlineAndProtocolTimeBoundsCannotSilentlyCommit() = runTest {
        val f = StoreFixture(); f.seed()
        try {
            val expired = assertIs<MutationReply.Recorded>(f.store().execute(testPrincipal(), f.request(input = CounterInput(deadline = TEST_TIME))))
            assertFalse(expired.committed); assertEquals("precondition.expired", expired.code)
            f.clockValue = UtcInstant.parseCanonical("+10000-01-01T00:00:00Z")
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(f.store().execute(testPrincipal(), f.request(21))).reason)
            assertEquals(1L, f.count("operation_result"))
        } finally { f.close() }
    }
}
