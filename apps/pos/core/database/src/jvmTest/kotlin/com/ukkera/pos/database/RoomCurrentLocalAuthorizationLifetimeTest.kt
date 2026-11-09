package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest

/** Borrowed-view ownership, caught failure poison and source-lifetime monotonic continuity. */
class RoomCurrentLocalAuthorizationLifetimeTest {
    @Test fun fabricatedLegacyAndWrappedViewsCannotReadAnyProtectedRecord() = runTest {
        withCurrentFixture { f ->
            val principal = localAuthorizationPrincipal(); val before = f.snapshot()
            val legacy = TransactionView(TEST_SCOPE, f.database.facts())
            val fake = object : CommandReadView {
                override val scope = TEST_SCOPE
                override suspend fun aggregate(key: AggregateKey): StoredAggregate? = error("must not read business data")
                override suspend fun claim(key: ClaimKey): DomainId? = error("must not read business data")
                override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = error("must not read business data")
            }
            val wrapped = object : CommandReadView by legacy {}
            for (view in listOf(legacy, fake, wrapped)) {
                assertFailsWith<IllegalStateException> { f.source.current(principal, view) }
            }
            legacy.close()
            assertFailsWith<IllegalStateException> { f.source.current(principal, legacy) }
            assertEquals(0, f.protection.openCalls); assertEquals(0, f.environment.calls); assertEquals(before, f.snapshot())
        }
    }

    @Test fun retainedRealWriterViewCannotReauthorizeAfterCommitOrInALaterTransaction() = runTest {
        withCurrentFixture { f ->
            val p = localAuthorizationPrincipal()
            assertIs<MutationReply.Recorded>(f.kernel().execute(p, f.request()))
            val retained = assertNotNull(f.handler.retainedView)
            val before = f.snapshot(); val opens = f.protection.openCalls; val observations = f.environment.calls
            assertFailsWith<IllegalStateException> { f.source.current(p, retained) }
            f.database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    assertFailsWith<IllegalStateException> { f.source.current(p, retained) }
                }
            }
            assertEquals(opens, f.protection.openCalls); assertEquals(observations, f.environment.calls)
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun identicalPathAndKeysDoNotMakeADifferentRoomDatabaseTheProtectedOwner() = runTest {
        withCurrentFixture { f ->
            val other = LocalAuthorizationStoreFixture(f.path)
            try {
                val before = f.snapshot()
                val result = assertIs<MutationReply.Deferred>(other.kernel(authorization = f.source).execute(
                    localAuthorizationPrincipal(), other.request()))
                assertEquals(DeferredReason.STORAGE_UNCERTAIN, result.reason)
                assertEquals(0, f.protection.openCalls); assertEquals(0, other.protection.openCalls)
                assertEquals(before, f.snapshot())
                assertIs<MutationReply.Recorded>(f.kernel().execute(localAuthorizationPrincipal(), f.request()))
            } finally { other.close() }
        }
    }

    @Test fun switchingToAnEquivalentButDifferentPrincipalDuringOneViewPoisonsItsOwnerBinding() = runTest {
        withCurrentFixture { f ->
            val p = localAuthorizationPrincipal(); val equivalent = localAuthorizationPrincipal(); var calls = 0
            val source = TransactionAuthorizationSource { principal, view ->
                f.source.current(if (++calls == 1) principal else equivalent, view)
            }
            val before = f.snapshot()
            val reply = assertIs<MutationReply.Deferred>(f.kernel(authorization = source).execute(p, f.request()))
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, reply.reason); assertEquals(2, calls)
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun aSecondSourceCannotReplaceTheFirstViewsProtectedBinding() = runTest {
        withCurrentFixture { f ->
            val second = RoomCurrentLocalAuthorizationSource(f.protectedStore, f.environment); var calls = 0
            val source = TransactionAuthorizationSource { principal, view ->
                (if (++calls == 1) f.source else second).current(principal, view)
            }
            val before = f.snapshot()
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(
                f.kernel(authorization = source).execute(localAuthorizationPrincipal(), f.request())).reason)
            assertEquals(2, calls); assertEquals(before, f.snapshot())
        }
    }

    @Test fun policyCatchingAProtectedReadFailureCannotTurnStoragePoisonIntoACommittableDenial() = runTest {
        withCurrentFixture { f ->
            val before = f.snapshot(); val kernel = f.kernel(); val p = localAuthorizationPrincipal()
            f.protection.throwOnOpen = IllegalArgumentException("synthetic private provider detail")
            val reply = assertIs<MutationReply.Deferred>(kernel.execute(p, f.request()))
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, reply.reason); assertEquals(before, f.snapshot())
            // A failed storage read poisons this view, not every other session/source observation.
            f.protection.throwOnOpen = null
            assertIs<MutationReply.Recorded>(kernel.execute(p, f.request()))
        }
    }

    @Test fun cancellationDuringTheFirstProtectedReadPropagatesUnchangedWithoutFinalizers() = runTest {
        withCurrentFixture { f ->
            val cancellation = LocalStorageCancellationSentinel("synthetic current read cancellation")
            f.protection.throwOnOpen = cancellation
            val before = f.snapshot()
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> {
                f.kernel().execute(localAuthorizationPrincipal(), f.request())
            })
            assertEquals(before, f.snapshot()); assertEquals(0, f.environment.calls)
        }
    }

    @Test fun cancellationDuringFinalProtectedRereadRollsBackStagedEffectsAndPreservesItsIdentity() = runTest {
        withCurrentFixture { f ->
            val cancellation = LocalStorageCancellationSentinel("synthetic final read cancellation")
            val before = f.snapshot(); var staged = false
            val kernel = f.kernel(fault = StoreFaultProbe {
                if (it == StoreFaultPoint.BEFORE_COMMIT) { staged = true; f.protection.throwOnOpen = cancellation }
            })
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> {
                kernel.execute(localAuthorizationPrincipal(), f.request())
            })
            assertTrue(staged); assertEquals(before, f.snapshot())
        }
    }

    @Test fun exceptionalBodyExitDoesNotRunAnEnvironmentFinalizerOrMaskTheOriginalCancellation() = runTest {
        withCurrentFixture { f ->
            val cancellation = LocalStorageCancellationSentinel("synthetic original command cancellation")
            val before = f.snapshot(); var observationsAtFailure = -1
            val kernel = f.kernel(fault = StoreFaultProbe {
                if (it == StoreFaultPoint.AFTER_EFFECTS) {
                    observationsAtFailure = f.environment.calls
                    f.environment.available = false
                    throw cancellation
                }
            })
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> {
                kernel.execute(localAuthorizationPrincipal(), f.request())
            })
            assertTrue(observationsAtFailure > 0); assertEquals(observationsAtFailure, f.environment.calls)
            assertEquals(before, f.snapshot())
            f.environment.available = true
            assertIs<MutationReply.Recorded>(f.kernel().execute(localAuthorizationPrincipal(), f.request()))
        }
    }

    @Test fun nonCancellationBodyFailureAlsoSkipsNormalReturnFinalization() = runTest {
        withCurrentFixture { f ->
            val before = f.snapshot(); var observationsAtFailure = -1
            val kernel = f.kernel(fault = StoreFaultProbe {
                if (it == StoreFaultPoint.BEFORE_COMMIT) {
                    observationsAtFailure = f.environment.calls
                    f.environment.onObserve = { error("must not replace original body error") }
                    error("synthetic original body error")
                }
            })
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, assertIs<MutationReply.Deferred>(
                kernel.execute(localAuthorizationPrincipal(), f.request())).reason)
            assertEquals(observationsAtFailure, f.environment.calls); assertEquals(before, f.snapshot())
        }
    }

    @Test fun observedRegressionWithinOneTransactionLatchesTheSourceEvenAfterTimeMovesForward() = runTest {
        withCurrentFixture { f ->
            f.environment.onObserve = { if (it == 2) f.environment.elapsed = 1499 }
            val before = f.snapshot(); val kernel = f.kernel(); val p = localAuthorizationPrincipal()
            assertLocalAuthDenied(kernel.execute(p, f.request()))
            assertEquals(before, f.snapshot())
            f.environment.onObserve = null; f.environment.elapsed = 1501
            assertLocalAuthDenied(kernel.execute(p, f.request()))
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun expiredIssuanceStillAdvancesCurrentHighWaterAndRegressionCannotReviveAnyOtherSession() = runTest {
        withCurrentFixture { f ->
            val other = f.seedOtherActor(testId(9760), testId(9761), listOf(allowLocal(SecurityAction.INVENTORY_ADJUST)), duration = 10000)
            val before = f.snapshot(); val kernel = f.kernel()
            f.environment.elapsed = 2000
            assertLocalAuthDenied(kernel.execute(localAuthorizationPrincipal(), f.request()))
            f.environment.elapsed = 1999
            assertLocalAuthDenied(kernel.execute(other, f.request(9762)))
            f.environment.elapsed = 2001 // Other issuance is still individually valid here.
            assertLocalAuthDenied(kernel.execute(other, f.request(9762)))
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun rolledBackExpiryObservationRemainsInSourceHighWaterAcrossWriterTransactions() = runTest {
        withCurrentFixture { f ->
            val other = f.seedOtherActor(testId(9760), testId(9761), listOf(allowLocal(SecurityAction.INVENTORY_ADJUST)), duration = 10000)
            val before = f.snapshot()
            val kernel = f.kernel(fault = StoreFaultProbe { if (it == StoreFaultPoint.BEFORE_COMMIT) f.environment.elapsed = 2000 })
            assertLocalAuthDenied(kernel.execute(localAuthorizationPrincipal(), f.request()))
            assertEquals(before, f.snapshot())
            f.environment.elapsed = 1999
            assertLocalAuthDenied(f.kernel().execute(other, f.request(9762)))
            f.environment.elapsed = 2001
            assertLocalAuthDenied(f.kernel().execute(other, f.request(9762)))
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun actualCurrentContextBootOrUnavailableContinuityDisablesSourceWithoutForwardRevival() = runTest {
        for (discontinuity in listOf("context", "boot", "unavailable")) withCurrentFixture { f ->
            val p = localAuthorizationPrincipal(); val kernel = f.kernel(); val before = f.snapshot()
            assertIs<LookupReply.AbsentAtCurrentWriter>(kernel.lookup(p, f.identity(f.request())))
            when (discontinuity) {
                "context" -> f.environment.contextId = testId(9763)
                "boot" -> f.environment.bootId = testId(9764)
                else -> f.environment.available = false
            }
            assertLocalAuthDenied(kernel.execute(p, f.request()))
            f.environment.contextId = testId(9500); f.environment.bootId = f.security.bootId
            f.environment.available = true; f.environment.elapsed = 1501
            assertLocalAuthDenied(kernel.execute(p, f.request()))
            assertEquals(before, f.snapshot(), discontinuity)
        }
    }

    @Test fun unavailableFirstObservationDoesNotAllowAReinitializedEnvironmentToReviveSource() = runTest {
        withCurrentFixture { f ->
            f.environment.available = false
            val before = f.snapshot(); val p = localAuthorizationPrincipal(); val kernel = f.kernel()
            assertLocalAuthDenied(kernel.execute(p, f.request()))
            f.environment.available = true
            assertLocalAuthDenied(kernel.execute(p, f.request()))
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun environmentCancellationIsNotInventedContinuityEvidenceOrGlobalSourcePoison() = runTest {
        withCurrentFixture { f ->
            val cancellation = LocalStorageCancellationSentinel("synthetic environment cancellation")
            val p = localAuthorizationPrincipal(); val before = f.snapshot(); val kernel = f.kernel()
            f.environment.onObserve = { throw cancellation }
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> { kernel.execute(p, f.request()) })
            assertEquals(before, f.snapshot())
            f.environment.onObserve = null
            assertIs<MutationReply.Recorded>(kernel.execute(p, f.request()))
        }
    }

    @Test fun normallyReturnedExpiryObservationSurvivesCancellationOfOnlyItsCommandJob() = runTest {
        withCurrentFixture { f ->
            val other = f.seedOtherActor(testId(9783), testId(9784), listOf(allowLocal(SecurityAction.INVENTORY_ADJUST)), duration = 10000)
            val before = f.snapshot(); var callbackReturned = false
            val cancellation = LocalStorageCancellationSentinel("synthetic cancel with returned expiry")
            val p = localAuthorizationPrincipal()
            val execution = async(Dispatchers.Default) {
                val commandJob = currentCoroutineContext().job
                f.kernel(fault = StoreFaultProbe { point ->
                    if (point == StoreFaultPoint.BEFORE_COMMIT) {
                        f.environment.onObserve = {
                            f.environment.elapsed = 2000
                            commandJob.cancel(cancellation)
                            callbackReturned = true // No exception: observe() returns the later sample.
                        }
                    }
                }).execute(p, f.request())
            }
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> { execution.await() })
            assertTrue(callbackReturned); assertEquals(before, f.snapshot())
            f.environment.onObserve = null; f.environment.elapsed = 1999
            assertLocalAuthDenied(f.kernel().execute(other, f.request(9785)))
            f.environment.elapsed = 2001 // The other session is individually valid; source must stay latched.
            assertLocalAuthDenied(f.kernel().execute(other, f.request(9785)))
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun normallyReturnedNullBootAndContextDiscontinuityAreLatchedBeforeCallerCancellationEscapes() = runTest {
        for (kind in listOf("null", "boot", "context")) withCurrentFixture { f ->
            val before = f.snapshot(); val p = localAuthorizationPrincipal(); val kernel = f.kernel()
            assertIs<LookupReply.AbsentAtCurrentWriter>(kernel.lookup(p, f.identity(f.request())))
            val cancellation = LocalStorageCancellationSentinel("synthetic cancel with returned discontinuity")
            var callbackReturned = false
            val execution = async(Dispatchers.Default) {
                val commandJob = currentCoroutineContext().job
                f.environment.onObserve = {
                    when (kind) {
                        "null" -> f.environment.available = false
                        "boot" -> f.environment.bootId = testId(9786)
                        else -> f.environment.contextId = testId(9787)
                    }
                    commandJob.cancel(cancellation)
                    callbackReturned = true
                }
                kernel.execute(p, f.request())
            }
            assertSame(cancellation, assertFailsWith<LocalStorageCancellationSentinel> { execution.await() })
            assertTrue(callbackReturned); assertEquals(before, f.snapshot())
            f.environment.onObserve = null; f.environment.available = true
            f.environment.bootId = f.security.bootId; f.environment.contextId = testId(9500); f.environment.elapsed = 1501
            assertLocalAuthDenied(kernel.execute(p, f.request()))
            assertEquals(before, f.snapshot(), kind)
        }
    }

    @Test fun concurrentProtectedRevocationCannotOvertakeACommandHoldingTheSameWriter() = runTest {
        withCurrentFixture { f ->
            val staged = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val revokeStarted = CompletableDeferred<Unit>(); val revokeEntered = CompletableDeferred<Unit>()
            val p = localAuthorizationPrincipal()
            val execution = async(Dispatchers.Default) {
                f.kernel(fault = StoreFaultProbe { if (it == StoreFaultPoint.BEFORE_COMMIT) { staged.complete(Unit); release.await() } })
                    .execute(p, f.request())
            }
            staged.await()
            val revocation = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                revokeStarted.complete(Unit)
                f.protectedStore.transaction { tx ->
                    revokeEntered.complete(Unit)
                    assertTrue(release.isCompleted, "revocation must not enter an already borrowed writer")
                    val before = assertNotNull(tx.readAccount(TEST_SCOPE, TEST_ACTOR))
                    val next = assertIs<SessionTransition.Applied>(LocalSessions.disable(before)).state
                    assertTrue(tx.replaceAccount(before.revision, next))
                }
            }
            try {
                revokeStarted.await(); assertFalse(revokeEntered.isCompleted)
            } finally { release.complete(Unit) }
            assertIs<MutationReply.Recorded>(execution.await()); revocation.await()
            val after = f.snapshot()
            assertLocalAuthDenied(f.kernel().execute(p, f.request(9770)))
            assertEquals(after, f.snapshot())
        }
    }

    @Test fun aCommandQueuedBehindProtectedRevocationCannotAuthorizeFromItsOldPrincipal() = runTest {
        withCurrentFixture { f ->
            val updated = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val revoke = async(Dispatchers.Default) {
                f.protectedStore.transaction { tx ->
                    val before = assertNotNull(tx.readAccount(TEST_SCOPE, TEST_ACTOR))
                    assertTrue(tx.replaceAccount(before.revision, assertIs<SessionTransition.Applied>(LocalSessions.disable(before)).state))
                    updated.complete(Unit); release.await()
                }
            }
            updated.await()
            val p = localAuthorizationPrincipal()
            val execute = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { f.kernel().execute(p, f.request()) }
            try { assertFalse(execute.isCompleted) } finally { release.complete(Unit) }
            revoke.await(); assertLocalAuthDenied(execute.await())
            assertEquals(0, f.handler.prepareCount)
            assertTrue(f.snapshot().getValue("operation_result").isEmpty())
        }
    }
}
