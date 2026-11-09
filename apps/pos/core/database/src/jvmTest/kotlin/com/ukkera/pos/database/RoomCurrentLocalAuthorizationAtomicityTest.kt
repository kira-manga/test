package com.ukkera.pos.database

import androidx.room3.useWriterConnection
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.test.*
import kotlinx.coroutines.test.runTest

/** Tests authorization's final normal-return boundary against actual staged Room effects. */
class RoomCurrentLocalAuthorizationAtomicityTest {
    @Test fun expiryAfterEveryStagingCutRollsBackAllTwelveTablesInsteadOfCommittingADenial() = runTest {
        for (cut in StoreFaultPoint.entries.filter { it != StoreFaultPoint.AFTER_COMMIT }) {
            withCurrentFixture { f ->
                val before = f.snapshot(); var reached = false
                val kernel = f.kernel(fault = StoreFaultProbe { point ->
                    if (point == cut) {
                        assertStagedAuthorizationRows(f, cut)
                        reached = true
                        f.environment.elapsed = 2000
                    }
                })
                assertLocalAuthDenied(kernel.execute(localAuthorizationPrincipal(), f.request()))
                assertTrue(reached, "actual staging hook $cut")
                assertEquals(1, f.handler.prepareCount)
                assertEquals(before, f.snapshot(), "all effects must roll back at $cut")
                f.database.useWriterConnection { assertFalse(it.inTransaction()) }
            }
        }
    }

    @Test fun aStagedBusinessRejectionIsNotPublishedWhenCurrentAuthorizationExpires() = runTest {
        withCurrentFixture { f ->
            val before = f.snapshot(); var staged = false
            val kernel = f.kernel(fault = StoreFaultProbe { point ->
                if (point == StoreFaultPoint.BEFORE_COMMIT) {
                    staged = true
                    assertEquals("stock.insufficient", assertNotNull(f.database.facts().operation(
                        TEST_SCOPE.storageKey(), f.request().operationId.value)).outcome)
                    f.environment.elapsed = 2000
                }
            })
            assertLocalAuthDenied(kernel.execute(localAuthorizationPrincipal(), f.request(input = CounterInput(reject = true))))
            assertTrue(staged); assertEquals(before, f.snapshot())
        }
    }

    @Test fun replayAndIdentityConflictFinalizeEvenThoughNoHandlerRuns() = runTest {
        for (conflict in listOf(false, true)) withCurrentFixture { f ->
            val p = localAuthorizationPrincipal(); val request = f.request()
            assertIs<MutationReply.Recorded>(f.kernel().execute(p, request))
            val before = f.snapshot(); var calls = 0
            val source = TransactionAuthorizationSource { principal, view ->
                f.source.current(principal, view).also {
                    calls++
                    if (calls == 2) f.environment.elapsed = 2000
                }
            }
            val replay = if (conflict) f.request(input = CounterInput(delta = 2)) else request
            assertLocalAuthDenied(f.kernel(authorization = source).execute(p, replay))
            assertEquals(2, calls); assertEquals(1, f.handler.prepareCount)
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun foundAbsentAndConflictingLookupAllFinalizeAfterTheirLastAuthorizationRead() = runTest {
        for (kind in listOf("found", "absent", "conflict")) withCurrentFixture { f ->
            val p = localAuthorizationPrincipal(); val request = f.request()
            assertIs<MutationReply.Recorded>(f.kernel().execute(p, request))
            val before = f.snapshot(); var calls = 0
            val source = TransactionAuthorizationSource { principal, view ->
                f.source.current(principal, view).also { if (++calls == 2) f.environment.elapsed = 2000 }
            }
            val identity = when (kind) {
                "absent" -> f.identity(f.request(9700))
                "conflict" -> f.identity(f.request(input = CounterInput(delta = 2)))
                else -> f.identity(request)
            }
            assertLocalAuthDenied(f.kernel(authorization = source).lookup(p, identity))
            assertEquals(2, calls); assertEquals(before, f.snapshot(), kind)
        }
    }

    @Test fun earlyWriterAndHistoryRefusalsStillObserveFinalAuthorizationContinuity() = runTest {
        for (kind in listOf("writer-execute", "history-execute", "writer-lookup", "history-lookup")) withCurrentFixture { f ->
            var refusalHookReached = false
            if (kind.startsWith("writer")) {
                f.writerVerification = RuntimeVerification.REFUSED
                f.beforeWriterVerification = { refusalHookReached = true; f.environment.elapsed = 2000 }
            } else {
                f.absenceVerification = RuntimeVerification.UNAVAILABLE
                f.beforeAbsenceVerification = { refusalHookReached = true; f.environment.elapsed = 2000 }
            }
            val before = f.snapshot(); val kernel = f.kernel(); val p = localAuthorizationPrincipal()
            if (kind.endsWith("execute")) assertLocalAuthDenied(kernel.execute(p, f.request()))
            else assertLocalAuthDenied(kernel.lookup(p, f.identity(f.request())))
            assertTrue(refusalHookReached, "must actually reach the selected $kind refusal")
            assertEquals(before, f.snapshot(), kind); assertEquals(0, f.handler.prepareCount)
        }
    }

    @Test fun supervisorAuditIsActuallyStagedThenRolledBackWhenFinalIssuanceExpires() = runTest {
        withCurrentFixture { f ->
            val request = f.request(); assertIs<MutationReply.Recorded>(f.kernel().execute(localAuthorizationPrincipal(), request))
            val supervisor = f.seedOtherActor(testId(9701), testId(9702), listOf(allowLocal(SecurityAction.OPERATION_RESULT_RECOVERY)))
            val before = f.snapshot(); var auditClockCalled = false; var stagedAuditSeen = false
            f.wallClock = MasterClock { auditClockCalled = true; TEST_TIME }
            f.protection.afterOpen = {
                if (auditClockCalled) {
                    val count = f.database.useWriterConnection { connection ->
                        connection.usePrepared("SELECT count(*) FROM audit_event WHERE kind='result.supervisor-read'") {
                            check(it.step()); it.getLong(0)
                        }
                    }
                    if (count == 1L) { stagedAuditSeen = true; f.environment.elapsed = 2000 }
                }
            }
            assertLocalAuthDenied(f.kernel().lookup(supervisor, f.identity(request)))
            assertTrue(auditClockCalled); assertTrue(stagedAuditSeen, "failure must occur after a real audit INSERT")
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun currentAccountCredentialPolicyAndAdmissionChangesInsideTheWriterInvalidateCapturedAuthorization() = runTest {
        for (kind in listOf("account", "credential", "policy", "admission")) withCurrentFixture { f ->
            val before = f.snapshot(); var changed = false
            val kernel = f.kernel(fault = StoreFaultProbe { point ->
                if (point == StoreFaultPoint.BEFORE_COMMIT) {
                    replaceProtectedDependencyInTestWriter(f, kind)
                    changed = true
                }
            })
            assertLocalAuthDenied(kernel.execute(localAuthorizationPrincipal(), f.request()))
            assertTrue(changed); assertEquals(before, f.snapshot(), kind)
        }
    }

    @Test fun currentPolicyRevisionCannotSilentlyChangeBetweenIdentityReadAndActionRead() = runTest {
        withCurrentFixture { f ->
            val before = f.snapshot(); var calls = 0; var changed = false
            val source = TransactionAuthorizationSource { principal, view ->
                f.source.current(principal, view).also {
                    if (++calls == 1) { replaceProtectedDependencyInTestWriter(f, "policy"); changed = true }
                }
            }
            assertLocalAuthDenied(f.kernel(authorization = source).execute(localAuthorizationPrincipal(), f.request()))
            assertTrue(changed); assertEquals(1, calls); assertEquals(1, f.handler.prepareCount)
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun finalSuspendableProtectedReadsCannotMoveTheLastBusinessDeadlineCheckEarlier() = runTest {
        withCurrentFixture { f ->
            val deadline = UtcInstant.parseCanonical("2026-10-23T12:00:01Z")
            val before = f.snapshot(); var finalReadReached = false
            val kernel = f.kernel(fault = StoreFaultProbe { point ->
                if (point == StoreFaultPoint.BEFORE_COMMIT) {
                    f.protection.afterOpen = { finalReadReached = true; f.wallTime = deadline }
                }
            })
            val reply = assertIs<MutationReply.Deferred>(kernel.execute(localAuthorizationPrincipal(),
                f.request(input = CounterInput(deadline = deadline))))
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, reply.reason)
            assertTrue(finalReadReached); assertEquals(before, f.snapshot())
        }
    }

    @Test fun noWallClockOrProtectedProviderRunsAfterTheLastMonotonicObservation() = runTest {
        withCurrentFixture { f ->
            val events = mutableListOf<String>(); var finalizing = false
            f.wallClock = MasterClock { if (finalizing) events += "wall"; TEST_TIME }
            f.protection.afterOpen = { if (finalizing) events += "open" }
            f.protection.onProtectionStatusRead = { if (finalizing) events += "protectionStatus" }
            f.environment.onObserve = { if (finalizing) events += "moment" }
            val kernel = f.kernel(fault = StoreFaultProbe { if (it == StoreFaultPoint.BEFORE_COMMIT) finalizing = true })
            assertTrue(assertIs<MutationReply.Recorded>(kernel.execute(localAuthorizationPrincipal(), f.request(input =
                CounterInput(deadline = UtcInstant.parseCanonical("2026-10-23T12:00:01Z"))))).committed)
            assertTrue(events.contains("open")); assertTrue(events.contains("wall")); assertTrue(events.contains("moment"))
            assertTrue(events.contains("protectionStatus"))
            assertEquals("moment", events.last())
            assertTrue(events.lastIndexOf("open") < events.lastIndexOf("wall"))
            assertTrue(events.lastIndexOf("protectionStatus") < events.lastIndexOf("wall"))
            assertTrue(events.lastIndexOf("wall") < events.lastIndexOf("moment"))
        }
    }

    @Test fun actualPreCommitFailureRemainsUncertainThenSameOperationCanCommitWithFreshTestSession() = runTest {
        exerciseProtectedCommitFailure(CommitFailureCut.BEFORE_SQLITE_COMMIT)
    }

    @Test fun actualAppliedCommitErrorRecoversOriginalOperationWithFreshTestSessionWithoutRepeatingEffects() = runTest {
        exerciseProtectedCommitFailure(CommitFailureCut.AFTER_SQLITE_COMMIT)
    }

    private suspend fun exerciseProtectedCommitFailure(cut: CommitFailureCut) {
        val driver = CommitFaultDriver(cut)
        val f = LocalAuthorizationStoreFixture(driver = driver)
        val request = f.request(); val originalPrincipal = localAuthorizationPrincipal()
        val before: Map<String, List<List<String>>>
        val after: Map<String, List<List<String>>>
        try {
            f.seed(); before = f.snapshot()
            val kernel = f.kernel(fault = StoreFaultProbe { if (it == StoreFaultPoint.BEFORE_COMMIT) driver.arm() })
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(kernel.execute(originalPrincipal, request)).reason)
            driver.assertInjectedAtExpectedBoundary()
            f.database.useWriterConnection { assertFalse(it.inTransaction()); requireDurableWriter(it) }
            after = f.snapshot()
            if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) assertEquals(before, after) else assertNotEquals(before, after)
        } finally { f.close() }

        val reopened = LocalAuthorizationStoreFixture(f.path)
        try {
            assertEquals(after, reopened.snapshot())
            // Explicit TEST-only new service context and new individual session; never recreate
            // a source with the old context and pretend it establishes restart continuity.
            reopened.environment.contextId = testId(9720)
            val kernel = reopened.kernel()
            assertLocalAuthDenied(kernel.lookup(originalPrincipal, reopened.identity(request)))
            val state = assertNotNull(reopened.protectedStore.transaction { it.readAccount(TEST_SCOPE, TEST_ACTOR) })
            val next = assertIs<SessionTransition.Applied>(LocalSessions.signIn(state, testId(9721),
                TEST_AUTHORITY.deviceId, explicitDeviceMove = true)).state
            reopened.protectedStore.transaction {
                assertTrue(it.replaceAccount(state.revision, next))
                it.insertIssuance(reopened.issuance(sessionId = testId(9721), generation = next.generation.value,
                    issuerContextId = testId(9720)))
            }
            assertEquals(after.filterKeys { !it.startsWith("protected_") }, reopened.snapshot().filterKeys { !it.startsWith("protected_") })
            val principal = localAuthorizationPrincipal(sessionId = testId(9721), generation = next.generation.value)
            val lookup = kernel.lookup(principal, reopened.identity(request))
            if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) assertIs<LookupReply.AbsentAtCurrentWriter>(lookup)
            else assertIs<LookupReply.Found>(lookup)
            val reply = assertIs<MutationReply.Recorded>(kernel.execute(principal, request))
            assertEquals(cut == CommitFailureCut.AFTER_SQLITE_COMMIT, reply.replayed)
            assertEquals("1", reply.result.copyBytes().decodeToString()); assertEquals(TEST_ACTOR, reply.originalActorId)
            assertEquals(if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) 1 else 0, reopened.handler.prepareCount)
            val once = reopened.snapshot()
            repeat(2) { assertTrue(assertIs<MutationReply.Recorded>(kernel.execute(principal, request)).replayed) }
            assertEquals(once, reopened.snapshot())
            assertEquals(1, once.getValue("operation_result").size)
            assertEquals(1, once.getValue("journal_envelope").size)
            assertEquals(1, once.getValue("aggregate_state").size)
            assertEquals(1, once.getValue("unique_claim").size)
            assertEquals(2, once.getValue("outbox_intent").size)
        } finally { reopened.close() }
    }
}

/** Inspect the actual uncommitted writer at the named cut; never infer staging from a reply. */
private suspend fun assertStagedAuthorizationRows(f: LocalAuthorizationStoreFixture, cut: StoreFaultPoint) {
    val operation = assertNotNull(f.database.facts().operation(TEST_SCOPE.storageKey(), f.request().operationId.value))
    assertEquals("committed", operation.outcome)
    fun countAfter(point: StoreFaultPoint, count: Long = 1): Long = if (cut.ordinal >= point.ordinal) count else 0
    val counts = mapOf("operation_result" to 1L,
        "aggregate_state" to countAfter(StoreFaultPoint.AFTER_EFFECTS),
        "unique_claim" to countAfter(StoreFaultPoint.AFTER_EFFECTS),
        "journal_envelope" to countAfter(StoreFaultPoint.AFTER_JOURNAL),
        "audit_event" to countAfter(StoreFaultPoint.AFTER_AUDIT),
        "outbox_intent" to countAfter(StoreFaultPoint.AFTER_OUTBOX, 2))
    f.database.useWriterConnection { connection ->
        assertTrue(connection.inTransaction())
        for ((table, count) in counts) connection.usePrepared("SELECT count(*) FROM $table") {
            assertTrue(it.step()); assertEquals(count, it.getLong(0), "$cut $table")
        }
    }
    val branch = assertNotNull(f.database.facts().branch(TEST_SCOPE.storageKey()))
    assertEquals(if (cut == StoreFaultPoint.BEFORE_COMMIT) 1L else 0L, branch.sequence)
}

/** Deliberate TEST-only same-writer mutation through the internal DAO, not a production activation API. */
private suspend fun replaceProtectedDependencyInTestWriter(f: LocalAuthorizationStoreFixture, kind: String) {
    val dao = f.database.localSecurity()
    val key = f.security.keyReference
    when (kind) {
        "account", "credential" -> {
            val value = f.security.defaultAccount(revision = 5, credentialRevision = if (kind == "credential") 3 else 2)
            val record = f.security.seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Account(TEST_SCOPE, TEST_ACTOR), Revision(5)),
                ProtectedLocalSecurityCodec.encodeAccount(value))
            assertEquals(1, dao.replaceAccount(TEST_SCOPE.storageKey(), TEST_ACTOR.value, 4, 5, 1, 1, "AES-256-GCM",
                key.keyId.value, record.copyNonce(), record.copyCiphertextAndTag()))
        }
        "policy" -> {
            val value = f.policy(4)
            val record = f.security.seal(ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(TEST_SCOPE, TEST_ACTOR), Revision(4)),
                ProtectedLocalSecurityCodec.encodePolicy(value))
            f.database.useWriterConnection { connection ->
                connection.usePrepared("UPDATE protected_effective_policy SET revision=4,nonce=?,ciphertextAndTag=? WHERE scope=? AND actorId=? AND revision=3") {
                    it.bindBlob(1, record.copyNonce()); it.bindBlob(2, record.copyCiphertextAndTag())
                    it.bindText(3, TEST_SCOPE.storageKey()); it.bindText(4, TEST_ACTOR.value); it.step()
                }
                connection.usePrepared("SELECT changes()") { check(it.step()); assertEquals(1L, it.getLong(0)) }
            }
        }
        "admission" -> {
            val value = f.security.defaultAdmission(revision = 7)
            val record = f.security.seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(TEST_SCOPE, TEST_AUTHORITY.deviceId), Revision(7)),
                ProtectedLocalSecurityCodec.encodeAdmission(value))
            f.database.useWriterConnection { connection ->
                connection.usePrepared("UPDATE protected_device_admission SET revision=7,nonce=?,ciphertextAndTag=? WHERE scope=? AND deviceId=? AND revision=6") {
                    it.bindBlob(1, record.copyNonce()); it.bindBlob(2, record.copyCiphertextAndTag())
                    it.bindText(3, TEST_SCOPE.storageKey()); it.bindText(4, TEST_AUTHORITY.deviceId.value); it.step()
                }
                connection.usePrepared("SELECT changes()") { check(it.step()); assertEquals(1L, it.getLong(0)) }
            }
        }
        else -> error("Unknown test dependency")
    }
}
