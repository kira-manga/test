package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** Real file-backed Room + TEST-ONLY AES-GCM. No real login, OS key or physical durability claim. */
class RoomPersistentThrottleStoreTest {
    @Test fun pairedReservationAndMismatchPreserveUnequalRevisionsAndFailures() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed(
                accountThrottle = fixture.defaultThrottle(revision = 7, failures = 2),
                deviceThrottle = fixture.defaultThrottle(ThrottleDimension.Device(fixture.deviceId), revision = 19, failures = 4),
            )
            val accountEnvelope = fixture.rawRecord(ProtectedLocalRecordKey.Account(fixture.scope, fixture.actorId))
            val attempt = capture(fixture)
            val permit = assertIs<ThrottleReservation.Allowed>(attempt.reserve(fixture.now(1001))).permit
            val reserved = fixture.states()
            assertEquals(listOf(8L, 20L), reserved.map { it.revision.value })
            assertEquals(listOf(2, 4), reserved.map { it.failures })
            assertTrue(reserved.all { it.activeAttempt == ThrottleAttempt(permit.attemptId, fixture.now(1001)) })
            assertEquals(CredentialAttemptOutcome.MISMATCH,
                assertIs<ThrottleCompletion.Persisted>(attempt.complete(permit, CredentialAttemptOutcome.MISMATCH, fixture.now(1002))).outcome)
            val finished = fixture.states()
            assertEquals(listOf(9L, 21L), finished.map { it.revision.value })
            assertEquals(listOf(3, 5), finished.map { it.failures })
            assertEquals(listOf(0L, 2000L), finished.map { it.remainingDelayMillis })
            assertTrue(finished.all { it.activeAttempt == null })
            // Throttle completion itself does not mutate/issue any employee session.
            assertEquals(accountEnvelope, fixture.rawRecord(ProtectedLocalRecordKey.Account(fixture.scope, fixture.actorId)))
        } finally { fixture.close() }
    }

    @Test fun matchingCompletionClearsBothUnequalBucketsOnlyAfterDurablePair() = runTest {
        val fixture = LocalSecurityStoreFixture()
        val password = ThrottleFixturePassword()
        try {
            fixture.seed(
                account = password.account(fixture),
                accountThrottle = fixture.defaultThrottle(revision = 3, failures = 2),
                deviceThrottle = fixture.defaultThrottle(ThrottleDimension.Device(fixture.deviceId), revision = 27, failures = 8),
            )
            val attempt = capture(fixture)
            val permit = assertIs<ThrottleReservation.Allowed>(attempt.reserve(fixture.now(1001))).permit
            password.verifyMatch(attempt)
            assertEquals(CredentialAttemptOutcome.MATCH,
                assertIs<ThrottleCompletion.Persisted>(attempt.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(1002))).outcome)
            assertEquals(listOf(5L, 29L), fixture.states().map { it.revision.value })
            assertTrue(fixture.states().all { it.failures == 0 && it.remainingDelayMillis == 0L && it.activeAttempt == null })
            val beforeRepeat = fixture.rawBuckets()
            assertEquals(ThrottleDenial.STALE_ATTEMPT,
                assertIs<ThrottleCompletion.Denied>(attempt.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(1003))).reason)
            assertEquals(beforeRepeat, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun activatedPolicyRatherThanHelperDefaultsControlsFailureDelay() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            val base = fixture.defaultPolicy()
            fixture.seed(policy = ActivatedLocalPolicy(base.scope, base.actorId, base.revision, base.grants, base.sessionPolicy,
                ThrottlePolicy(freeFailures = 0, initialDelayMillis = 5000, maximumDelayMillis = 20_000, attemptTimeoutMillis = 1000)))
            val attempt = capture(fixture)
            val permit = assertIs<ThrottleReservation.Allowed>(attempt.reserve(fixture.now(1001))).permit
            assertIs<ThrottleCompletion.Persisted>(attempt.complete(permit, CredentialAttemptOutcome.MISMATCH, fixture.now(1002)))
            assertEquals(listOf(1, 1), fixture.states().map { it.failures })
            assertEquals(listOf(5000L, 5000L), fixture.states().map { it.remainingDelayMillis })
            val later = capture(fixture, testId(9202))
            assertEquals(ThrottleDenial.DELAYED, assertIs<ThrottleReservation.Denied>(later.reserve(fixture.now(1003))).reason)
            assertEquals(listOf(4999L, 4999L), fixture.states().map { it.remainingDelayMillis })
        } finally { fixture.close() }
    }

    @Test fun activatedTimeoutRejectsStillMatchingButExpiredAttemptAndCountsFailure() = runTest {
        val fixture = LocalSecurityStoreFixture()
        val password = ThrottleFixturePassword()
        try {
            val base = fixture.defaultPolicy()
            fixture.seed(account = password.account(fixture),
                policy = ActivatedLocalPolicy(base.scope, base.actorId, base.revision, base.grants, base.sessionPolicy,
                    ThrottlePolicy(freeFailures = 0, initialDelayMillis = 3000, attemptTimeoutMillis = 1000)))
            val attempt = capture(fixture)
            val permit = assertIs<ThrottleReservation.Allowed>(attempt.reserve(fixture.now(1001))).permit
            password.verifyMatch(attempt)
            assertEquals(ThrottleDenial.STALE_ATTEMPT,
                assertIs<ThrottleCompletion.Denied>(attempt.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(2001))).reason)
            assertEquals(listOf(1, 1), fixture.states().map { it.failures })
            assertEquals(listOf(3000L, 3000L), fixture.states().map { it.remainingDelayMillis })
            assertTrue(fixture.states().all { it.activeAttempt == null })
        } finally { fixture.close() }
    }

    @Test fun changedIncarnationsDenyReservationAndRetainChangedDependencies() = runTest {
        for (change in DependencyChange.entries) {
            val fixture = LocalSecurityStoreFixture()
            try {
                fixture.seed()
                val attempt = capture(fixture)
                changeDependency(fixture, change)
                val before = fixture.rawBuckets()
                val dependencies = fixture.rawDependencies()
                // LTL-10/21/22 rejection semantics on the older regression fixture.
                // LTL-34 forbids restoring the obsolete arbitrary helper callback.
                assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                    assertIs<ThrottleReservation.Denied>(attempt.reserve(fixture.now(1001))).reason, change.name)
                assertEquals(before, fixture.rawBuckets(), change.name)
                assertEquals(dependencies, fixture.rawDependencies(), change.name)
            } finally { fixture.close() }
        }
    }

    @Test fun staleTimelyMatchCannotClearNewerFailuresForAnyCapturedDependency() = runTest {
        for (change in DependencyChange.entries) {
            val fixture = LocalSecurityStoreFixture()
            val password = ThrottleFixturePassword()
            try {
                fixture.seed(
                    account = password.account(fixture),
                    accountThrottle = fixture.defaultThrottle(revision = 7, failures = 4),
                    deviceThrottle = fixture.defaultThrottle(ThrottleDimension.Device(fixture.deviceId), revision = 19, failures = 8),
                )
                val attempt = capture(fixture)
                val permit = assertIs<ThrottleReservation.Allowed>(attempt.reserve(fixture.now(1001))).permit
                changeDependency(fixture, change)
                // Keep the original permit ID/start UNEXPIRED and still matching both buckets.
                // STALE_ATTEMPT in the unchanged reducer must not hide a missing dependency cut.
                for (state in fixture.states()) {
                    fixture.replaceThrottleFixture(state.revision.value,
                        state.copy(revision = state.revision.next(), failures = state.failures + 3))
                }
                val pending = fixture.states()
                assertTrue(pending.all { it.activeAttempt == ThrottleAttempt(permit.attemptId, fixture.now(1001)) })
                assertEquals(listOf(7, 11), pending.map { it.failures })
                val before = fixture.rawBuckets()
                // The actual hasher still matches the attempt's private original credential,
                // not the changed current account; completion must separately reject the cut.
                password.verifyMatch(attempt)
                assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                    assertIs<ThrottleCompletion.Denied>(attempt.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(1002))).reason,
                    change.name)
                assertEquals(before, fixture.rawBuckets(), change.name)
            } finally { fixture.close() }
        }
    }

    @Test fun missingRequiredRowsNeverInitializeAnAccountOrEitherBucket() = runTest {
        for (missing in 0..4) {
            val fixture = LocalSecurityStoreFixture()
            try {
                fixture.seed(
                    account = if (missing == 0) null else fixture.defaultAccount(),
                    policy = if (missing == 1) null else fixture.defaultPolicy(),
                    admission = if (missing == 2) null else fixture.defaultAdmission(),
                    accountThrottle = if (missing == 3) null else fixture.defaultThrottle(),
                    deviceThrottle = if (missing == 4) null else fixture.defaultThrottle(ThrottleDimension.Device(fixture.deviceId)),
                )
                val before = fixture.rawBuckets()
                assertNull(RoomPersistentThrottleStore.capture(fixture.store, fixture.scope, fixture.actorId, fixture.deviceId, testId(9201)))
                assertEquals(before, fixture.rawBuckets())
            } finally { fixture.close() }
        }
    }

    @Test fun explicitlyDisabledUnadmittedOrCredentiallessInputsAreNotAttempts() = runTest {
        for (inactive in 0..2) {
            val fixture = LocalSecurityStoreFixture()
            try {
                fixture.seed(
                    account = when (inactive) {
                        0 -> fixture.defaultAccount(enabled = false, sessions = emptyList())
                        1 -> fixture.defaultAccount(hasCredential = false)
                        else -> fixture.defaultAccount()
                    },
                    admission = fixture.defaultAdmission(admitted = inactive != 2),
                )
                val before = fixture.rawBuckets()
                assertNull(RoomPersistentThrottleStore.capture(fixture.store, fixture.scope, fixture.actorId, fixture.deviceId, testId(9201)))
                assertEquals(before, fixture.rawBuckets())
            } finally { fixture.close() }
        }
    }

    @Test fun wrongScopeActorOrDeviceCannotCaptureAnotherBucketsAuthority() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            val before = fixture.rawBuckets()
            val dependencies = fixture.rawDependencies()
            val account = fixture.accountKey()
            val wrongKeys = listOf(
                account.copy(scope = BranchScope(fixture.scope.organizationId, fixture.scope.companyId, testId(9220))),
                account.copy(scope = BranchScope(fixture.scope.organizationId, testId(9221), fixture.scope.branchId)),
                account.copy(scope = BranchScope(testId(9222), fixture.scope.companyId, fixture.scope.branchId)),
                account.copy(actorId = testId(9223)),
                account.copy(dimension = ThrottleDimension.Device(testId(9224))),
            )
            for (key in wrongKeys) {
                // Supported routing/capture refusal, not a private adapter read/write probe.
                // These legacy inputs contribute only a storage slice of LTL-10/13/34.
                val device = (key.dimension as? ThrottleDimension.Device)?.deviceId ?: fixture.deviceId
                assertNull(RoomPersistentThrottleStore.capture(fixture.store, key.scope, key.actorId, device, testId(9201)))
                assertEquals(before, fixture.rawBuckets())
                assertEquals(dependencies, fixture.rawDependencies())
                assertNull(fixture.rawRecord(ProtectedLocalRecordKey.Throttle(key)))
            }
        } finally { fixture.close() }
    }

    @Test fun expiredBorrowedRoomThrottleViewCannotReadOrWriteAfterCommit() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            // The fixed helper no longer exposes its restricted callback. Its underlying
            // actual Room loan still enforces the same valid-key read/write lifetime.
            val escaped = fixture.store.transaction { it }
            val before = fixture.rawBuckets()
            val opens = fixture.protection.openCalls
            assertUnavailable(assertFailsWith<IllegalStateException> { escaped.readThrottle(fixture.accountKey()) })
            assertUnavailable(assertFailsWith<IllegalStateException> {
                escaped.replaceThrottle(Revision(0), fixture.defaultThrottle().copy(revision = Revision(1)))
            })
            assertEquals(opens, fixture.protection.openCalls)
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun aCaughtLaterThrottleCasConflictStillRollsBackEarlierBucketInRealStorage() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            val before = fixture.rawBuckets()
            assertUnavailable(assertFailsWith<IllegalStateException> {
                fixture.store.transaction { transaction ->
                    val account = assertNotNull(transaction.readThrottle(fixture.accountKey()))
                    val device = assertNotNull(transaction.readThrottle(fixture.deviceKey()))
                    assertTrue(transaction.replaceThrottle(account.revision, account.copy(revision = account.revision.next(), failures = 1)))
                    assertFalse(transaction.replaceThrottle(device.revision.next(), device.copy(revision = Revision(device.revision.value + 2))))
                    "not a committed pair"
                }
            })
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun foreignEqualScalarCaptureCannotConsumePermitOrMutateAuthenticPair() = runTest {
        val fixture = LocalSecurityStoreFixture()
        val password = ThrottleFixturePassword()
        try {
            fixture.seed(account = password.account(fixture))
            val original = capture(fixture)
            val permit = assertIs<ThrottleReservation.Allowed>(original.reserve(fixture.now(1001))).permit
            password.verifyMatch(original)
            val before = fixture.rawBuckets()
            val dependencies = fixture.rawDependencies()
            // LTL-13 equal-scalars-new-capture/other-request semantics. No new permit is
            // constructed and no arbitrary mutable view is exposed for the transplant.
            for (foreign in listOf(capture(fixture, permit.attemptId), capture(fixture, testId(9235)))) {
                assertNotSame(original, foreign)
                assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                    assertIs<ThrottleCompletion.Denied>(foreign.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(1002))).reason)
                assertEquals(before, fixture.rawBuckets())
                assertEquals(dependencies, fixture.rawDependencies())
            }
            assertEquals(CredentialAttemptOutcome.MATCH,
                assertIs<ThrottleCompletion.Persisted>(original.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(1002))).outcome)
            assertTrue(fixture.states().all { it.activeAttempt == null && it.remainingDelayMillis == 0L })
            val completed = fixture.rawBuckets()
            // LTL-14 one-use storage slice. Session/approval/audit issuance is not tested here.
            assertEquals(ThrottleDenial.STALE_ATTEMPT,
                assertIs<ThrottleCompletion.Denied>(original.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(1003))).reason)
            assertEquals(completed, fixture.rawBuckets())
            assertEquals(dependencies, fixture.rawDependencies())
        } finally { fixture.close() }
    }

    @Test fun enclosingWriterTransactionCannotReturnProvisionalThrottleSuccess() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            val attempt = capture(fixture)
            val before = fixture.rawBuckets()
            fixture.database.useWriterConnection { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                        assertIs<ThrottleReservation.Denied>(attempt.reserve(fixture.now(1001))).reason)
                }
            }
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun secondBucketSealFailureRollsBackFirstActualSqlUpdateWithoutPrivateDiagnostics() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            var observedFirstWrite = false
            val hooked = SealHookStorage(fixture.protection) { binding, _ ->
                if (binding.isDeviceBucket()) {
                    observedFirstWrite = fixture.database.localSecurity().throttle(
                        fixture.scope.storageKey(), fixture.actorId.value, "account",
                    )?.revision == 1L
                    throw IllegalStateException("SYNTHETIC-private-verifier-provider-transcript")
                }
            }
            val attempt = capture(fixture, store = RoomLocalSecurityStore(fixture.database, hooked, fixture.keyReference))
            val before = fixture.rawBuckets()
            val denied = assertIs<ThrottleReservation.Denied>(attempt.reserve(fixture.now(1001)))
            assertEquals(ThrottleDenial.STORE_UNAVAILABLE, denied.reason)
            assertFalse(denied.toString().contains("SYNTHETIC-private"))
            assertTrue(observedFirstWrite)
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun actualSecondCasMissInUnchangedHelperRollsBackBothSqlUpdates() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            var interposed = false
            val hooked = SealHookStorage(fixture.protection) { binding, result ->
                if (binding.isDeviceBucket()) {
                    val sealed = assertIs<LocalSealResult.Sealed>(result).record
                    val key = binding.key as ProtectedLocalRecordKey.Throttle
                    // Deliberate TEST-ONLY same-transaction CAS interposition, after the first
                    // bucket write but before the unchanged helper's second adapter CAS.
                    assertEquals(1L, fixture.database.localSecurity().throttle(
                        fixture.scope.storageKey(), fixture.actorId.value, "account",
                    )?.revision)
                    assertEquals(1, fixture.database.localSecurity().replaceThrottle(
                        fixture.scope.storageKey(), fixture.actorId.value, key.dimensionKey,
                        binding.revision.value - 1, binding.revision.value, binding.schemaVersion,
                        sealed.formatVersion, sealed.algorithm, fixture.keyReference.keyId.value,
                        sealed.copyNonce(), sealed.copyCiphertextAndTag(),
                    ))
                    interposed = true
                }
            }
            val attempt = capture(fixture, store = RoomLocalSecurityStore(fixture.database, hooked, fixture.keyReference))
            val before = fixture.rawBuckets()
            assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                assertIs<ThrottleReservation.Denied>(attempt.reserve(fixture.now(1001))).reason)
            assertTrue(interposed)
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun realStorageCallbackExceptionAfterFirstThrottleWriteRollsBackAndSanitizesWithoutCause() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            val before = fixture.rawBuckets()
            val failure = assertFailsWith<IllegalStateException> {
                fixture.store.transaction { transaction ->
                    val account = assertNotNull(transaction.readThrottle(fixture.accountKey()))
                    assertTrue(transaction.replaceThrottle(account.revision, account.copy(revision = account.revision.next(), failures = 1)))
                    throw IllegalArgumentException("SYNTHETIC-private-salt-and-verifier")
                }
            }
            assertUnavailable(failure)
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun cancellationAfterFirstWriteAndNonCancellableProviderResumeCannotCommit() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val hooked = SealHookStorage(fixture.protection) { binding, _ ->
                if (binding.isDeviceBucket()) {
                    assertEquals(1L, fixture.database.localSecurity().throttle(
                        fixture.scope.storageKey(), fixture.actorId.value, "account",
                    )?.revision)
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
            }
            val attempt = capture(fixture, store = RoomLocalSecurityStore(fixture.database, hooked, fixture.keyReference))
            val before = fixture.rawBuckets()
            val result = async { attempt.reserve(fixture.now(1001)) }
            entered.await()
            result.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { result.await() }
            result.join()
            assertEquals(before, fixture.rawBuckets())
            fixture.database.useWriterConnection { assertFalse(it.inTransaction()) }
        } finally { fixture.close() }
    }

    @Test fun directlyThrownCancellationOnSecondSealIsPreservedAndRollsBack() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            val cancellation = LocalStorageCancellationSentinel("synthetic cancellation")
            val hooked = SealHookStorage(fixture.protection) { binding, _ -> if (binding.isDeviceBucket()) throw cancellation }
            val attempt = capture(fixture, store = RoomLocalSecurityStore(fixture.database, hooked, fixture.keyReference))
            val before = fixture.rawBuckets()
            assertSame(cancellation, assertFailsWith<CancellationException> { attempt.reserve(fixture.now(1001)) })
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun cancelledCompletionIsRetiredEvenWhenItsPairWasRolledBack() = runTest {
        val fixture = LocalSecurityStoreFixture()
        val password = ThrottleFixturePassword()
        try {
            fixture.seed(account = password.account(fixture))
            var cancelCompletion = false
            val cancellation = LocalStorageCancellationSentinel("synthetic completion cancellation")
            val hooked = SealHookStorage(fixture.protection) { binding, _ ->
                if (cancelCompletion && binding.isDeviceBucket()) throw cancellation
            }
            val attempt = capture(fixture, store = RoomLocalSecurityStore(fixture.database, hooked, fixture.keyReference))
            val permit = assertIs<ThrottleReservation.Allowed>(attempt.reserve(fixture.now(1001))).permit
            password.verifyMatch(attempt)
            val before = fixture.rawBuckets()
            cancelCompletion = true
            assertSame(cancellation, assertFailsWith<CancellationException> {
                attempt.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(1002))
            })
            assertEquals(before, fixture.rawBuckets())
            cancelCompletion = false
            val seals = fixture.protection.sealCalls
            assertEquals(ThrottleDenial.STALE_ATTEMPT,
                assertIs<ThrottleCompletion.Denied>(attempt.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(1002))).reason)
            assertEquals(seals, fixture.protection.sealCalls)
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun unavailableAndUnqualifiedKeysDenyWithoutReplacingProtectedBuckets() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            val before = fixture.rawBuckets()
            for (failure in ProtectedKeyFailure.entries) {
                fixture.protection.failure = null
                val attempt = capture(fixture)
                fixture.protection.failure = failure
                assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                    assertIs<ThrottleReservation.Denied>(attempt.reserve(fixture.now(1001))).reason)
                assertEquals(before, fixture.rawBuckets())
            }
            fixture.protection.failure = null
            for (status in listOf(KeyProtectionStatus.UNQUALIFIED_CONTEXT, KeyProtectionStatus.UNSUPPORTED)) {
                fixture.protection.status = KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT
                val attempt = capture(fixture)
                fixture.protection.status = status
                val opens = fixture.protection.openCalls
                assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                    assertIs<ThrottleReservation.Denied>(attempt.reserve(fixture.now(1001))).reason)
                assertEquals(opens, fixture.protection.openCalls)
                assertEquals(before, fixture.rawBuckets())
            }
            fixture.protection.status = KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT
            val wrongKeyStore = RoomLocalSecurityStore(fixture.database, fixture.protection, ProtectedStorageKeyReference(testId(9299)))
            assertUnavailable(assertFailsWith<IllegalStateException> {
                RoomPersistentThrottleStore.capture(wrongKeyStore, fixture.scope, fixture.actorId, fixture.deviceId, testId(9201))
            })
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun corruptSecondBucketCannotTurnIntoZeroOrLeaveAnAccountReservation() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed()
            val attempt = capture(fixture)
            fixture.corrupt(ProtectedLocalRecordKey.Throttle(fixture.deviceKey()), "ciphertextAndTag=zeroblob(length(ciphertextAndTag))")
            val before = fixture.rawBuckets()
            assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                assertIs<ThrottleReservation.Denied>(attempt.reserve(fixture.now(1001))).reason)
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun exhaustedAccountBucketDoesNotReserveOtherwiseAvailableDeviceBucket() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed(accountThrottle = fixture.defaultThrottle(revision = Long.MAX_VALUE))
            val attempt = capture(fixture)
            val before = fixture.rawBuckets()
            assertEquals(ThrottleDenial.COUNTER_EXHAUSTED,
                assertIs<ThrottleReservation.Denied>(attempt.reserve(fixture.now(1001))).reason)
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun concurrentCapturesSerializeExactlyOneReservationWithoutEqualizingBuckets() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed(
                accountThrottle = fixture.defaultThrottle(revision = 1, failures = 1),
                deviceThrottle = fixture.defaultThrottle(ThrottleDimension.Device(fixture.deviceId), revision = 8, failures = 2),
            )
            val first = capture(fixture, testId(9230))
            val second = capture(fixture, testId(9231))
            val firstResult = async { first.reserve(fixture.now(1001)) }
            val secondResult = async { second.reserve(fixture.now(1001)) }
            val results = listOf(firstResult.await(), secondResult.await())
            val allowed = assertIs<ThrottleReservation.Allowed>(results.single { it is ThrottleReservation.Allowed })
            assertEquals(ThrottleDenial.ATTEMPT_IN_PROGRESS,
                assertIs<ThrottleReservation.Denied>(results.single { it is ThrottleReservation.Denied }).reason)
            val states = fixture.states()
            assertEquals(listOf(3L, 10L), states.map { it.revision.value })
            assertEquals(listOf(1, 2), states.map { it.failures })
            assertTrue(states.all { it.activeAttempt?.attemptId == allowed.permit.attemptId })
        } finally { fixture.close() }
    }

    @Test fun recapturingAfterCredentialChangeCannotAdoptAnOldStillMatchingPermit() = runTest {
        val fixture = LocalSecurityStoreFixture()
        val password = ThrottleFixturePassword()
        try {
            fixture.seed(account = password.account(fixture))
            val original = capture(fixture)
            val permit = assertIs<ThrottleReservation.Allowed>(original.reserve(fixture.now(1001))).permit
            changeDependency(fixture, DependencyChange.CREDENTIAL_REVISION)
            password.verifyMatch(original)
            val recaptured = capture(fixture, permit.attemptId)
            assertTrue(fixture.states().all { it.activeAttempt?.attemptId == permit.attemptId })
            val before = fixture.rawBuckets()
            assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                assertIs<ThrottleCompletion.Denied>(recaptured.complete(permit, CredentialAttemptOutcome.MATCH, fixture.now(1002))).reason)
            assertEquals(before, fixture.rawBuckets())
        } finally { fixture.close() }
    }

    @Test fun oneCapturedContextPublishesAtMostOnePermitUnderConcurrentReserveReentry() = runTest {
        val fixture = LocalSecurityStoreFixture()
        val password = ThrottleFixturePassword()
        try {
            fixture.seed(account = password.account(fixture))
            val attempt = capture(fixture)
            val first = async { attempt.reserve(fixture.now(1001)) }
            val second = async { attempt.reserve(fixture.now(1001)) }
            val results = listOf(first.await(), second.await())
            val allowed = assertIs<ThrottleReservation.Allowed>(results.single { it is ThrottleReservation.Allowed })
            assertEquals(ThrottleDenial.STALE_ATTEMPT,
                assertIs<ThrottleReservation.Denied>(results.single { it is ThrottleReservation.Denied }).reason)
            assertEquals(listOf(1L, 1L), fixture.states().map { it.revision.value })
            password.verifyMatch(attempt)
            assertIs<ThrottleCompletion.Persisted>(attempt.complete(allowed.permit, CredentialAttemptOutcome.MATCH, fixture.now(1002)))
        } finally { fixture.close() }
    }

    @Test fun oldUncompletedPermitCannotClearReusedSameIdWithNewerStartWhileCurrentPermitWorks() = runTest {
        val fixture = LocalSecurityStoreFixture()
        val password = ThrottleFixturePassword()
        try {
            fixture.seed(account = password.account(fixture))
            val old = capture(fixture)
            val oldPermit = assertIs<ThrottleReservation.Allowed>(old.reserve(fixture.now(1001))).permit
            // First observe abandonment with the unchanged reducer. The original verifier has
            // not completed/retired its capability and may still return MATCH later.
            assertEquals(ThrottleDenial.DELAYED,
                assertIs<ThrottleReservation.Denied>(capture(fixture, testId(9250)).reserve(fixture.now(121001))).reason)
            val current = capture(fixture, oldPermit.attemptId)
            val currentPermit = assertIs<ThrottleReservation.Allowed>(current.reserve(fixture.now(121002))).permit
            assertEquals(oldPermit.attemptId, currentPermit.attemptId)
            assertTrue(fixture.states().all {
                it.failures == 1 && it.activeAttempt == ThrottleAttempt(oldPermit.attemptId, fixture.now(121002))
            })
            val before = fixture.rawBuckets()
            password.verifyMatch(old)
            // This time is timely for B: the helper alone would accept A's reused ID.
            assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                assertIs<ThrottleCompletion.Denied>(old.complete(oldPermit, CredentialAttemptOutcome.MATCH, fixture.now(121003))).reason)
            assertEquals(before, fixture.rawBuckets())
            // Healthy original-start twin demonstrates the gate does not simply deny all.
            assertIs<ThrottleCompletion.Persisted>(current.complete(currentPermit, CredentialAttemptOutcome.MISMATCH, fixture.now(121003)))
            assertTrue(fixture.states().all { it.failures == 2 && it.activeAttempt == null })
        } finally { fixture.close() }
    }

    @Test fun retiredCompletionCannotClearReusedIdEvenAtIdenticalMonotonicTick() = runTest {
        val fixture = LocalSecurityStoreFixture()
        val password = ThrottleFixturePassword()
        try {
            fixture.seed(account = password.account(fixture))
            val old = capture(fixture)
            val oldPermit = assertIs<ThrottleReservation.Allowed>(old.reserve(fixture.now(1001))).permit
            password.verifyMatch(old)
            assertIs<ThrottleCompletion.Persisted>(old.complete(oldPermit, CredentialAttemptOutcome.MATCH, fixture.now(1001)))
            val current = capture(fixture, oldPermit.attemptId)
            val currentPermit = assertIs<ThrottleReservation.Allowed>(current.reserve(fixture.now(1001))).permit
            assertTrue(fixture.states().all { it.activeAttempt == ThrottleAttempt(oldPermit.attemptId, fixture.now(1001)) })
            val before = fixture.rawBuckets()
            assertEquals(ThrottleDenial.STALE_ATTEMPT,
                assertIs<ThrottleCompletion.Denied>(old.complete(oldPermit, CredentialAttemptOutcome.MATCH, fixture.now(1001))).reason)
            assertEquals(before, fixture.rawBuckets())
            password.verifyMatch(current)
            assertIs<ThrottleCompletion.Persisted>(current.complete(currentPermit, CredentialAttemptOutcome.MATCH, fixture.now(1001)))
        } finally { fixture.close() }
    }

    @Test fun identicalIdsInAnotherDatabaseCannotAdoptAnIssuedPermit() = runTest {
        val firstFixture = LocalSecurityStoreFixture()
        val secondFixture = LocalSecurityStoreFixture()
        val password = ThrottleFixturePassword()
        try {
            firstFixture.seed(account = password.account(firstFixture))
            secondFixture.seed(account = password.account(secondFixture))
            val first = capture(firstFixture)
            val second = capture(secondFixture)
            val firstPermit = assertIs<ThrottleReservation.Allowed>(first.reserve(firstFixture.now(1001))).permit
            val secondPermit = assertIs<ThrottleReservation.Allowed>(second.reserve(secondFixture.now(1001))).permit
            assertEquals(firstPermit.accountKey, secondPermit.accountKey)
            assertEquals(firstPermit.deviceKey, secondPermit.deviceKey)
            assertEquals(firstPermit.attemptId, secondPermit.attemptId)
            password.verifyMatch(second)
            val before = secondFixture.rawBuckets()
            assertEquals(ThrottleDenial.STORE_UNAVAILABLE,
                assertIs<ThrottleCompletion.Denied>(second.complete(firstPermit, CredentialAttemptOutcome.MATCH, secondFixture.now(1002))).reason)
            assertEquals(before, secondFixture.rawBuckets())
            assertIs<ThrottleCompletion.Persisted>(second.complete(secondPermit, CredentialAttemptOutcome.MATCH, secondFixture.now(1002)))
            assertTrue(firstFixture.states().all { it.activeAttempt?.attemptId == firstPermit.attemptId })
        } finally { firstFixture.close(); secondFixture.close() }
    }

    @Test fun actualOuterReservationCommitErrorsRemainStorageUncertainWithMeasuredBeforeAfterCuts() = runTest {
        for (cut in CommitFailureCut.entries) exerciseCommitCut(cut, completion = false)
    }

    @Test fun actualOuterCompletionCommitErrorsRemainStorageUncertainWithMeasuredBeforeAfterCuts() = runTest {
        for (cut in CommitFailureCut.entries) exerciseCommitCut(cut, completion = true)
    }

    @Test fun persistedAbandonedAttemptAfterReopenAndNewBootCountsAgainstBothUnequalBuckets() = runTest {
        val fixture = LocalSecurityStoreFixture()
        val saved: List<List<String>?>
        try {
            fixture.seed(
                accountThrottle = fixture.defaultThrottle(revision = 4, failures = 2),
                deviceThrottle = fixture.defaultThrottle(ThrottleDimension.Device(fixture.deviceId), revision = 16, failures = 4),
            )
            assertIs<ThrottleReservation.Allowed>(capture(fixture).reserve(fixture.now(1001)))
            saved = fixture.rawBuckets()
        } finally { fixture.close() }
        val reopened = LocalSecurityStoreFixture(fixture.path)
        try {
            assertEquals(saved, reopened.rawBuckets())
            val attempt = capture(reopened, testId(9240))
            val rebooted = MonotonicMoment(testId(9241), 10)
            assertEquals(ThrottleDenial.DELAYED, assertIs<ThrottleReservation.Denied>(attempt.reserve(rebooted)).reason)
            val states = reopened.states()
            assertEquals(listOf(6L, 18L), states.map { it.revision.value })
            assertEquals(listOf(3, 5), states.map { it.failures })
            assertEquals(listOf(0L, 2000L), states.map { it.remainingDelayMillis })
            assertTrue(states.all { it.activeAttempt == null && it.lastObservation == rebooted })
        } finally { reopened.close() }
    }

    @Test fun persistedDelayRearmsOnNewBootRatherThanTrustingElapsedOrWallTime() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            fixture.seed(
                accountThrottle = fixture.defaultThrottle(revision = 4, failures = 4).copy(remainingDelayMillis = 4000),
                deviceThrottle = fixture.defaultThrottle(ThrottleDimension.Device(fixture.deviceId), revision = 16, failures = 7)
                    .copy(remainingDelayMillis = 9000),
            )
        } finally { fixture.close() }
        val reopened = LocalSecurityStoreFixture(fixture.path)
        try {
            val attempt = capture(reopened)
            val rebooted = MonotonicMoment(testId(9242), 5_000_000)
            assertEquals(ThrottleDenial.DELAYED, assertIs<ThrottleReservation.Denied>(attempt.reserve(rebooted)).reason)
            assertEquals(listOf(4000L, 9000L), reopened.states().map { it.remainingDelayMillis })
            assertEquals(listOf(4, 7), reopened.states().map { it.failures })
            assertTrue(reopened.states().all { it.lastObservation == rebooted && it.activeAttempt == null })
        } finally { reopened.close() }
    }
}

/** Genuine production create/verify with a synthetic password, not an accepting verifier stub. */
private class ThrottleFixturePassword {
    private val hasher = JvmLocalPasswordHasher(SyntheticThrottlePepper)

    suspend fun account(fixture: LocalSecurityStoreFixture): ProtectedLocalAccount {
        val original = fixture.defaultAccount()
        val credential = assertNotNull(original.credential)
        val input = input()
        return try {
            val created = assertIs<PasswordHashingResult.Created>(
                hasher.create(input, credential.binding, credential.pepperReference),
            )
            ProtectedLocalAccount(original.state, original.credentialRevision, created.record)
        } finally {
            input.fill('\u0000')
        }
    }

    suspend fun verifyMatch(attempt: RoomPersistentThrottleStore) {
        val input = input()
        try {
            assertIs<PasswordVerificationResult.Match>(attempt.verifyPassword(hasher, input))
        } finally {
            input.fill('\u0000')
        }
    }

    private fun input(): CharArray = "SYNTHETIC-throttle-fixture-only".toCharArray()
}

/**
 * Actual JCA HMAC with an EXPLICIT SYNTHETIC TEST KEY. The status is only a fixture seam,
 * not OS custody qualification; no application composition root installs this provider.
 */
private object SyntheticThrottlePepper : DeviceProtectedPepper {
    override val protectionStatus: KeyProtectionStatus = KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT

    override suspend fun hmacSha256(reference: ProtectedPepperReference, message: ByteArray): PepperMacResult {
        if (reference != ProtectedPepperReference(testId(9111))) {
            return PepperMacResult.Unavailable(ProtectedKeyFailure.KEY_UNAVAILABLE)
        }
        val keyMaterial = ByteArray(32) { (it + 151).toByte() }
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyMaterial, "HmacSHA256"))
            val tag = mac.doFinal(message)
            try {
                return PepperMacResult.Authenticated(tag)
            } finally {
                tag.fill(0)
            }
        } finally {
            keyMaterial.fill(0)
        }
    }
}

private suspend fun capture(
    fixture: LocalSecurityStoreFixture,
    attemptId: DomainId = testId(9201),
    store: RoomLocalSecurityStore = fixture.store,
): RoomPersistentThrottleStore = assertNotNull(
    RoomPersistentThrottleStore.capture(store, fixture.scope, fixture.actorId, fixture.deviceId, attemptId),
)

private fun LocalSecurityStoreFixture.now(elapsed: Long): MonotonicMoment = MonotonicMoment(bootId, elapsed)
private fun LocalSecurityStoreFixture.accountKey(): ThrottleKey = ThrottleKey(scope, actorId, ThrottleDimension.Account)
private fun LocalSecurityStoreFixture.deviceKey(): ThrottleKey = ThrottleKey(scope, actorId, ThrottleDimension.Device(deviceId))
private suspend fun LocalSecurityStoreFixture.states(): List<PersistentThrottleState> = store.transaction {
    listOf(assertNotNull(it.readThrottle(accountKey())), assertNotNull(it.readThrottle(deviceKey())))
}
private suspend fun LocalSecurityStoreFixture.rawBuckets(): List<List<String>?> = listOf(
    rawRecord(ProtectedLocalRecordKey.Throttle(accountKey())), rawRecord(ProtectedLocalRecordKey.Throttle(deviceKey())),
)
private suspend fun LocalSecurityStoreFixture.rawDependencies(): List<List<String>?> = listOf(
    rawRecord(ProtectedLocalRecordKey.Account(scope, actorId)),
    rawRecord(ProtectedLocalRecordKey.EffectivePolicy(scope, actorId)),
    rawRecord(ProtectedLocalRecordKey.Admission(scope, deviceId)),
)

private fun assertUnavailable(failure: IllegalStateException) {
    assertEquals("Protected local record unavailable", failure.message)
    assertNull(failure.cause)
    assertTrue(failure.suppressedExceptions.isEmpty())
}

private fun assertStorageUncertain(failure: RoomLocalStorageUncertain) {
    assertEquals("Local security storage outcome uncertain", failure.message)
    assertNull(failure.cause)
    assertTrue(failure.suppressedExceptions.isEmpty())
}

private fun ProtectedLocalBinding.isDeviceBucket(): Boolean =
    (key as? ProtectedLocalRecordKey.Throttle)?.key?.dimension is ThrottleDimension.Device

private class SealHookStorage(
    private val delegate: DeviceProtectedLocalStorage,
    private val afterSeal: suspend (ProtectedLocalBinding, LocalSealResult) -> Unit,
) : DeviceProtectedLocalStorage by delegate {
    override suspend fun seal(reference: ProtectedStorageKeyReference, binding: ProtectedLocalBinding, plaintext: ByteArray): LocalSealResult {
        val result = delegate.seal(reference, binding, plaintext)
        afterSeal(binding, result)
        return result
    }
}

private enum class DependencyChange {
    ACCOUNT_REVISION, SESSION_GENERATION, CREDENTIAL_REVISION, POLICY_REVISION, ADMISSION_REVISION,
    DISABLED, CREDENTIAL_REMOVED, UNADMITTED, ACTIVATED_THROTTLE_POLICY,
}

private suspend fun changeDependency(fixture: LocalSecurityStoreFixture, change: DependencyChange) {
    when (change) {
        DependencyChange.POLICY_REVISION -> {
            val old = fixture.store.transaction { assertNotNull(it.readPolicy(fixture.scope, fixture.actorId)) }
            fixture.replacePolicyFixture(old.revision.value,
                ActivatedLocalPolicy(old.scope, old.actorId, old.revision.next(), old.grants, old.sessionPolicy, old.throttlePolicy))
        }
        DependencyChange.ADMISSION_REVISION -> {
            val old = fixture.store.transaction { assertNotNull(it.readAdmission(fixture.scope, fixture.deviceId)) }
            fixture.replaceAdmissionFixture(old.revision.value,
                LocalDeviceAdmission(old.scope, old.deviceId, old.revision.next(), old.admitted,
                    old.publicKeyIdentifier, old.publicFingerprint, old.capabilities))
        }
        DependencyChange.UNADMITTED -> {
            val old = fixture.store.transaction { assertNotNull(it.readAdmission(fixture.scope, fixture.deviceId)) }
            val changed = LocalDeviceAdmission(old.scope, old.deviceId, old.revision, false,
                old.publicKeyIdentifier, old.publicFingerprint, old.capabilities)
            resealSameRevision(fixture, ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(old.scope, old.deviceId), old.revision),
                ProtectedLocalSecurityCodec.encodeAdmission(changed))
        }
        DependencyChange.ACTIVATED_THROTTLE_POLICY -> {
            val old = fixture.store.transaction { assertNotNull(it.readPolicy(fixture.scope, fixture.actorId)) }
            val changed = ActivatedLocalPolicy(old.scope, old.actorId, old.revision, old.grants, old.sessionPolicy,
                old.throttlePolicy.copy(freeFailures = 0))
            resealSameRevision(fixture, ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(old.scope, old.actorId), old.revision),
                ProtectedLocalSecurityCodec.encodePolicy(changed))
        }
        else -> {
            val old = fixture.store.transaction { assertNotNull(it.readProtectedAccount(fixture.scope, fixture.actorId)) }
            val state = old.state
            val changedState = LocalAccountSessionState(state.scope, state.actorId,
                state.enabled && change != DependencyChange.DISABLED,
                if (change == DependencyChange.ACCOUNT_REVISION) state.revision.next() else state.revision,
                if (change == DependencyChange.SESSION_GENERATION) state.generation.next() else state.generation,
                if (change == DependencyChange.DISABLED) state.sessions.map { it.copy(status = SessionStatus.REVOKED) } else state.sessions)
            val revision = if (change == DependencyChange.CREDENTIAL_REVISION) old.credentialRevision.next() else old.credentialRevision
            val credential = when (change) {
                DependencyChange.CREDENTIAL_REMOVED -> null
                DependencyChange.CREDENTIAL_REVISION -> assertNotNull(old.credential).let {
                    PasswordHashRecord(CredentialBinding(state.scope, state.actorId, revision), it.parameters,
                        it.pepperReference, it.copySalt(), it.copyVerifier())
                }
                else -> old.credential
            }
            val changed = ProtectedLocalAccount(changedState, revision, credential)
            if (change == DependencyChange.ACCOUNT_REVISION) fixture.replaceAccountFixture(state.revision.value, changed)
            else resealSameRevision(fixture, ProtectedLocalBinding(ProtectedLocalRecordKey.Account(state.scope, state.actorId), state.revision),
                ProtectedLocalSecurityCodec.encodeAccount(changed))
        }
    }
}

/**
 * Deliberately bypass the SQL revision guard in a disposable TEST fixture, while retaining a
 * valid AEAD tag, to isolate each independently captured dependency from the account revision.
 * This is not a supported mutation, reset/provisioning API or database-rollback defense claim.
 */
private suspend fun resealSameRevision(fixture: LocalSecurityStoreFixture, binding: ProtectedLocalBinding, plaintext: ByteArray) {
    val sealed = fixture.seal(binding, plaintext)
    fixture.corrupt(binding.key, "nonce=X'${sealed.copyNonce().hexForSql()}',ciphertextAndTag=X'${sealed.copyCiphertextAndTag().hexForSql()}'")
}

private fun ByteArray.hexForSql(): String = joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

private suspend fun exerciseCommitCut(cut: CommitFailureCut, completion: Boolean) {
    val driver = CommitFaultDriver(cut)
    val fixture = LocalSecurityStoreFixture(driver = driver)
    val password = if (completion) ThrottleFixturePassword() else null
    val before: List<List<String>?>
    val after: List<List<String>?>
    val dependencies: List<List<String>?>
    try {
        fixture.seed(
            account = password?.account(fixture) ?: fixture.defaultAccount(),
            accountThrottle = fixture.defaultThrottle(revision = 4, failures = 2),
            deviceThrottle = fixture.defaultThrottle(ThrottleDimension.Device(fixture.deviceId), revision = 16, failures = 4),
        )
        dependencies = fixture.rawDependencies()
        var arm = !completion
        val hooked = SealHookStorage(fixture.protection) { binding, _ ->
            if (arm && binding.isDeviceBucket()) { arm = false; driver.arm() }
        }
        val attempt = capture(fixture, store = RoomLocalSecurityStore(fixture.database, hooked, fixture.keyReference))
        val permit = if (completion) assertIs<ThrottleReservation.Allowed>(attempt.reserve(fixture.now(1001))).permit else null
        if (completion) assertNotNull(password).verifyMatch(attempt)
        before = fixture.rawBuckets()
        // Frozen LTL-26/27 immediate-outcome oracle: both actual driver cuts are
        // unresolved to the caller. The older seed/count assertions remain regressions,
        // not full F0/session/audit qualification. A genuinely unknown cut is unbound.
        val failure = if (completion) {
            arm = true
            assertFailsWith<RoomLocalStorageUncertain> {
                attempt.complete(assertNotNull(permit), CredentialAttemptOutcome.MATCH, fixture.now(1002))
            }
        } else {
            assertFailsWith<RoomLocalStorageUncertain> { attempt.reserve(fixture.now(1001)) }
        }
        assertStorageUncertain(failure)
        driver.assertInjectedAtExpectedBoundary()
        fixture.database.useWriterConnection { connection ->
            assertFalse(connection.inTransaction())
            requireDurableWriter(connection)
        }
        after = fixture.rawBuckets()
        assertEquals(dependencies, fixture.rawDependencies())
        if (cut == CommitFailureCut.BEFORE_SQLITE_COMMIT) assertEquals(before, after) else assertNotEquals(before, after)
        if (completion) {
            // The capability is retired even when SQLite committed but reporting failed, and
            // equally conservatively when rollback left the original reservation outstanding.
            val seals = fixture.protection.sealCalls
            assertEquals(ThrottleDenial.STALE_ATTEMPT,
                assertIs<ThrottleCompletion.Denied>(attempt.complete(assertNotNull(permit), CredentialAttemptOutcome.MATCH, fixture.now(1002))).reason)
            assertEquals(seals, fixture.protection.sealCalls)
            assertEquals(after, fixture.rawBuckets())
        }
    } finally { fixture.close() }
    val reopened = LocalSecurityStoreFixture(fixture.path)
    try {
        assertEquals(after, reopened.rawBuckets())
        assertEquals(dependencies, reopened.rawDependencies())
        val states = reopened.states()
        val committed = cut == CommitFailureCut.AFTER_SQLITE_COMMIT
        assertEquals(if (completion && committed) listOf(0, 0) else listOf(2, 4), states.map { it.failures })
        val shouldHaveAttempt = if (completion) !committed else committed
        assertTrue(states.all { (it.activeAttempt != null) == shouldHaveAttempt })
    } finally { reopened.close() }
}
