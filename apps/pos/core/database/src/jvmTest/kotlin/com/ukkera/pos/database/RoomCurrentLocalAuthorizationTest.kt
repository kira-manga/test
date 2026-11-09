package com.ukkera.pos.database

import androidx.room3.useWriterConnection
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import kotlin.test.*
import kotlinx.coroutines.test.runTest

/** Real protected rows + actual command writer; every identity/key/environment is synthetic. */
class RoomCurrentLocalAuthorizationTest {
    @Test fun protectedCurrentSourceAuthorizesWithoutAnySyntheticBusinessAuthAggregate() = runTest {
        val f = LocalAuthorizationStoreFixture()
        try {
            f.seed()
            val principal = localAuthorizationPrincipal()
            val request = f.request()
            val protectedBefore = f.snapshot().filterKeys { it.startsWith("protected_") }
            assertNull(f.database.facts().aggregate(TEST_SCOPE.storageKey(), AUTH_KEY.kind, AUTH_KEY.id.value))
            val reply = assertIs<MutationReply.Recorded>(f.kernel().execute(principal, request))
            assertTrue(reply.committed); assertFalse(reply.replayed)
            assertEquals("1", reply.result.copyBytes().decodeToString())
            val row = assertNotNull(f.database.facts().operation(TEST_SCOPE.storageKey(), request.operationId.value))
            assertEquals(3L, row.policyRevision); assertEquals(principal.sessionId.value, row.authenticationId)
            val once = f.snapshot()
            assertEquals(protectedBefore, once.filterKeys { it.startsWith("protected_") })
            val found = assertIs<LookupReply.Found>(f.kernel().lookup(principal, f.identity(request)))
            assertTrue(found.result.replayed); assertEquals(reply.result, found.result.result)
            assertEquals(once, f.snapshot())
            assertTrue(f.protection.openCalls >= 12, "Current facts must actually be decrypted and rechecked")
            assertEquals(1, f.handler.prepareCount)
        } finally { f.close() }
    }

    @Test fun defaultUnavailableSourceStillDeniesEvenWhenAllProtectedRowsExist() = runTest {
        withCurrentFixture { f ->
            val before = f.snapshot()
            val kernel = f.kernel(authorization = UnavailableTransactionAuthorization)
            assertLocalAuthDenied(kernel.execute(localAuthorizationPrincipal(), f.request()))
            assertLocalAuthDenied(kernel.lookup(localAuthorizationPrincipal(), f.identity(f.request())))
            assertEquals(before, f.snapshot()); assertEquals(0, f.protection.openCalls)
        }
    }

    @Test fun absentIssuanceDoesNotGrandfatherLegacyActiveSessionsOrInitializeAnything() = runTest {
        val f = LocalAuthorizationStoreFixture()
        try {
            f.seed(issuance = null)
            val before = f.snapshot(); val seals = f.protection.sealCalls
            repeat(2) {
                assertLocalAuthDenied(f.kernel().execute(localAuthorizationPrincipal(), f.request()))
                assertLocalAuthDenied(f.kernel().lookup(localAuthorizationPrincipal(), f.identity(f.request())))
            }
            assertNull(f.protectedStore.transaction { it.readIssuance(TEST_SCOPE, testId(9110)) })
            assertEquals(seals, f.protection.sealCalls); assertEquals(before, f.snapshot())
        } finally { f.close() }
    }

    @Test fun eachMissingProtectedDependencyDeniesWithoutCreatingAnOperation() = runTest {
        for (missing in listOf("account", "policy", "admission")) {
            val f = LocalAuthorizationStoreFixture()
            try {
                f.seed(account = if (missing == "account") null else f.security.defaultAccount(),
                    policy = if (missing == "policy") null else f.policy(),
                    admission = if (missing == "admission") null else f.security.defaultAdmission())
                val before = f.snapshot()
                assertLocalAuthDenied(f.kernel().execute(localAuthorizationPrincipal(), f.request()))
                assertLocalAuthDenied(f.kernel().lookup(localAuthorizationPrincipal(), f.identity(f.request())))
                assertEquals(before, f.snapshot(), missing)
            } finally { f.close() }
        }
    }

    @Test fun disabledRevokedMissingAndTerminalAccountSessionsCannotAuthorize() = runTest {
        for (condition in listOf("disabled", "revoked", "missing", "terminal")) {
            val f = LocalAuthorizationStoreFixture()
            try {
                val original = f.security.defaultAccount()
                val revoked = original.state.sessions.map { it.copy(status = SessionStatus.REVOKED) }
                val account = f.security.defaultAccount(
                    enabled = condition != "disabled" && condition != "terminal",
                    revision = if (condition == "terminal") Long.MAX_VALUE else 4,
                    generation = if (condition == "terminal") Long.MAX_VALUE else 5,
                    sessions = when (condition) {
                        "disabled", "revoked", "terminal" -> revoked
                        "missing" -> emptyList()
                        else -> original.state.sessions
                    },
                )
                f.seed(account = account); val before = f.snapshot()
                assertLocalAuthDenied(f.kernel().execute(localAuthorizationPrincipal(), f.request()))
                assertEquals(before, f.snapshot(), condition)
            } finally { f.close() }
        }
    }

    @Test fun absentCredentialOrCredentialRevisionDifferentFromOriginalIssuanceDenies() = runTest {
        for (accountKind in listOf("absent", "changed")) {
            val f = LocalAuthorizationStoreFixture()
            try {
                f.seed(account = f.security.defaultAccount(hasCredential = accountKind != "absent",
                    credentialRevision = if (accountKind == "changed") 3 else 2))
                val before = f.snapshot()
                assertLocalAuthDenied(f.kernel().execute(localAuthorizationPrincipal(), f.request()))
                assertEquals(before, f.snapshot())
            } finally { f.close() }
        }
    }

    @Test fun originalZeroCredentialAndAdmissionRevisionsAreValidNotMissingSentinels() = runTest {
        val f = LocalAuthorizationStoreFixture()
        try {
            f.seed(account = f.security.defaultAccount(credentialRevision = 0),
                admission = f.security.defaultAdmission(revision = 0),
                issuance = f.issuance(credentialRevision = 0, admissionRevision = 0))
            assertTrue(assertIs<MutationReply.Recorded>(f.kernel().execute(
                localAuthorizationPrincipal(admissionRevision = 0), f.request())).committed)
        } finally { f.close() }
    }

    @Test fun deniedOrRevisedAdmissionDoesNotRestampAnExistingIssuance() = runTest {
        for (changedRevision in listOf(false, true)) {
            val f = LocalAuthorizationStoreFixture()
            try {
                f.seed(admission = f.security.defaultAdmission(revision = if (changedRevision) 7 else 6,
                    admitted = changedRevision))
                val before = f.snapshot()
                assertLocalAuthDenied(f.kernel().execute(localAuthorizationPrincipal(
                    admissionRevision = if (changedRevision) 7 else 6), f.request()))
                assertEquals(before, f.snapshot())
            } finally { f.close() }
        }
    }

    @Test fun everyIssuanceBindingMismatchIsDeniedWithoutRestampingOrBusinessWrites() = runTest {
        val cases: List<(LocalAuthorizationStoreFixture) -> LocalAccessIssuance> = listOf(
            { it.issuance(actorId = testId(9670)) }, { it.issuance(sessionId = testId(9671)) },
            { it.issuance(deviceId = testId(9672)) }, { it.issuance(generation = 4) },
            { it.issuance(credentialRevision = 1) }, { it.issuance(admissionRevision = 5) },
            { it.issuance(issuerContextId = testId(9673)) }, { it.issuance(bootId = testId(9674)) },
            { it.issuance(issuedAt = 1501) }, { it.issuance(issuedAt = 500, duration = 1000) },
        )
        for ((index, make) in cases.withIndex()) {
            val f = LocalAuthorizationStoreFixture()
            try {
                f.seed(issuance = make(f)); val before = f.snapshot()
                assertLocalAuthDenied(f.kernel().execute(localAuthorizationPrincipal(), f.request()))
                assertLocalAuthDenied(f.kernel().lookup(localAuthorizationPrincipal(), f.identity(f.request())))
                assertEquals(before, f.snapshot(), "case $index")
            } finally { f.close() }
        }
    }

    @Test fun cachedPrincipalCoordinatesAndNoninteractivePurposeCannotOverrideProtectedFacts() = runTest {
        withCurrentFixture { f ->
            val principals = listOf(localAuthorizationPrincipal(actorId = testId(9675)),
                localAuthorizationPrincipal(sessionId = testId(9676)),
                localAuthorizationPrincipal(deviceId = testId(9677)),
                localAuthorizationPrincipal(generation = 6), localAuthorizationPrincipal(admissionRevision = 7),
                localAuthorizationPrincipal(purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL),
                localAuthorizationPrincipal(purpose = AuthenticationPurpose.PREPARED_LOCAL_RECOVERY))
            val before = f.snapshot()
            for (principal in principals) assertLocalAuthDenied(f.kernel().execute(principal, f.request()))
            assertEquals(before, f.snapshot()); assertEquals(0, f.handler.prepareCount)
        }
    }

    @Test fun anotherLegitimateSessionGenerationDoesNotInvalidateAnUnchangedIndividualSession() = runTest {
        val f = LocalAuthorizationStoreFixture()
        try {
            val policy = ActivatedLocalPolicy(TEST_SCOPE, TEST_ACTOR, Revision(3),
                listOf(allowLocal(SecurityAction.INVENTORY_ADJUST)), LocalSessionPolicy(2), ThrottlePolicy())
            f.seed(policy = policy)
            val before = assertNotNull(f.protectedStore.transaction { it.readAccount(TEST_SCOPE, TEST_ACTOR) })
            val next = assertIs<SessionTransition.Applied>(LocalSessions.signIn(before, testId(9678),
                TEST_AUTHORITY.deviceId, policy.sessionPolicy)).state
            assertTrue(f.protectedStore.transaction { it.replaceAccount(before.revision, next) })
            assertEquals(6L, next.generation.value)
            assertEquals(5L, next.sessions.first().generation.value)
            assertTrue(assertIs<MutationReply.Recorded>(f.kernel().execute(localAuthorizationPrincipal(), f.request())).committed)
        } finally { f.close() }
    }

    @Test fun policyIsReadLiveAndOriginalActorRecoveryNeedsNoOrdinaryMutationGrant() = runTest {
        withCurrentFixture { f ->
            val p = localAuthorizationPrincipal(); val request = f.request(); val kernel = f.kernel()
            assertIs<MutationReply.Recorded>(kernel.execute(p, request))
            f.security.replacePolicyFixture(3, f.policy(4, grants = emptyList()))
            val before = f.snapshot()
            assertLocalAuthDenied(kernel.execute(p, f.request(9680)))
            assertIs<LookupReply.Found>(kernel.lookup(p, f.identity(request)))
            assertTrue(assertIs<MutationReply.Recorded>(kernel.execute(p, request)).replayed)
            assertEquals(before, f.snapshot())
            f.security.replacePolicyFixture(4, f.policy(5, grants = listOf(allowLocal(SecurityAction.INVENTORY_ADJUST))))
            val changed = assertIs<MutationReply.Recorded>(kernel.execute(p,
                f.request(9681, CounterInput(id = testId(9682), claim = "new-policy-stock"))))
            assertTrue(changed.committed)
            assertEquals(5L, f.database.facts().operation(TEST_SCOPE.storageKey(), changed.operationId.value)!!.policyRevision)
        }
    }

    @Test fun explicitRecoveryDenyBlocksEvenOriginalActorReplayAndLookup() = runTest {
        withCurrentFixture { f ->
            val p = localAuthorizationPrincipal(); val request = f.request(); val kernel = f.kernel()
            assertIs<MutationReply.Recorded>(kernel.execute(p, request))
            f.security.replacePolicyFixture(3, f.policy(4, grants = listOf(
                allowLocal(SecurityAction.INVENTORY_ADJUST), allowLocal(SecurityAction.OPERATION_RESULT_RECOVERY, GrantEffect.DENY))))
            val before = f.snapshot()
            assertLocalAuthDenied(kernel.execute(p, request))
            assertLocalAuthDenied(kernel.lookup(p, f.identity(request)))
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun supervisorRecoveryUsesItsOwnCurrentProtectedIdentityAndProducesAuditOnly() = runTest {
        withCurrentFixture { f ->
            val request = f.request(); val kernel = f.kernel()
            assertIs<MutationReply.Recorded>(kernel.execute(localAuthorizationPrincipal(), request))
            val supervisor = f.seedOtherActor(testId(9690), testId(9691), listOf(allowLocal(SecurityAction.OPERATION_RESULT_RECOVERY)))
            val before = f.snapshot()
            val found = assertIs<LookupReply.Found>(kernel.lookup(supervisor, f.identity(request)))
            assertEquals(TEST_ACTOR, found.result.originalActorId)
            val after = f.snapshot()
            assertEquals(before.filterKeys { it != "audit_event" }, after.filterKeys { it != "audit_event" })
            assertEquals(before.getValue("audit_event").size + 1, after.getValue("audit_event").size)
            f.database.useWriterConnection { connection ->
                connection.usePrepared("SELECT actorId,originalActorId,policyRevision FROM audit_event WHERE kind='result.supervisor-read'") {
                    assertTrue(it.step()); assertEquals(supervisor.actorId.value, it.getText(0))
                    assertEquals(TEST_ACTOR.value, it.getText(1)); assertEquals(3L, it.getLong(2)); assertFalse(it.step())
                }
            }
        }
    }

    @Test fun supervisorWithoutRecoveryGrantCannotLearnWhetherAnOperationExists() = runTest {
        withCurrentFixture { f ->
            val request = f.request(); val kernel = f.kernel()
            assertIs<MutationReply.Recorded>(kernel.execute(localAuthorizationPrincipal(), request))
            val other = f.seedOtherActor(testId(9690), testId(9691), listOf(allowLocal(SecurityAction.INVENTORY_ADJUST)))
            val before = f.snapshot()
            assertLocalAuthDenied(kernel.lookup(other, f.identity(request)))
            assertLocalAuthDenied(kernel.lookup(other, f.identity(f.request(9692))))
            assertEquals(before, f.snapshot())
        }
    }

    @Test fun anUnrelatedExpiredOrStaleContextOrOldBootIssuanceCannotPoisonHealthySessions() = runTest {
        for (kind in listOf("expired", "context", "boot")) {
            val f = LocalAuthorizationStoreFixture()
            try {
                val issuance = when (kind) {
                    "expired" -> f.issuance(issuedAt = 500, duration = 1000)
                    "context" -> f.issuance(issuerContextId = testId(9693))
                    else -> f.issuance(bootId = testId(9694))
                }
                f.seed(issuance = issuance)
                val other = f.seedOtherActor(testId(9690), testId(9691), listOf(allowLocal(SecurityAction.INVENTORY_ADJUST)))
                val kernel = f.kernel()
                assertLocalAuthDenied(kernel.execute(localAuthorizationPrincipal(), f.request()))
                assertTrue(assertIs<MutationReply.Recorded>(kernel.execute(other, f.request(9695))).committed, kind)
            } finally { f.close() }
        }
    }

    @Test fun endIsExclusiveAndTheLastRepresentableDeadlineDoesNotWrap() = runTest {
        val f = LocalAuthorizationStoreFixture()
        try {
            f.environment.elapsed = Long.MAX_VALUE - 1
            f.seed(issuance = f.issuance(issuedAt = Long.MAX_VALUE - 2, duration = 2))
            val kernel = f.kernel(); val p = localAuthorizationPrincipal()
            assertIs<MutationReply.Recorded>(kernel.execute(p, f.request()))
            f.environment.elapsed = Long.MAX_VALUE
            val before = f.snapshot()
            assertLocalAuthDenied(kernel.lookup(p, f.identity(f.request())))
            assertEquals(before, f.snapshot())
        } finally { f.close() }
    }
}

internal suspend fun withCurrentFixture(block: suspend (LocalAuthorizationStoreFixture) -> Unit) {
    val f = LocalAuthorizationStoreFixture()
    try { f.seed(); block(f) } finally { f.close() }
}
