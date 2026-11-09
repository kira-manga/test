package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest

/** Real file/AEAD/parser/hash/verifier/factory/kernel. Scripted trust providers remain TEST ONLY. */
class RoomLocalAccessPrincipalVerifierTest {
    @Test fun actualFactoryAuthenticatesOriginalIssuanceAndKernelCommitsOnceWithRealActor() = runTest {
        withAccessFixture { f ->
            val before = f.base.snapshot()
            val principal = assertNotNull(f.authenticate())
            assertEquals(TEST_ACTOR, principal.actorId); assertEquals(TEST_SCOPE, principal.scope)
            assertEquals(TEST_AUTHORITY.deviceId, principal.deviceId); assertEquals(testId(9110), principal.sessionId)
            assertEquals(SessionGeneration(5), principal.sessionGeneration); assertEquals(Revision(6), principal.admissionRevision)
            assertEquals(AuthenticationPurpose.INTERACTIVE, principal.authenticationPurpose)
            assertEquals(before, f.base.snapshot(), "authentication must not mutate throttle or any of twelve tables")
            val request = f.base.request(); val kernel = f.base.kernel()
            val committed = assertIs<MutationReply.Recorded>(kernel.execute(principal, request))
            assertFalse(committed.replayed); assertEquals(TEST_ACTOR, committed.originalActorId)
            val once = f.base.snapshot()
            assertTrue(assertIs<MutationReply.Recorded>(kernel.execute(assertNotNull(f.authenticate()), request)).replayed)
            assertEquals(once, f.base.snapshot()); assertEquals(1, f.base.handler.prepareCount)
        }
    }

    @Test fun malformedWireIsRejectedBeforePeersStorageAndCurrentEnvironment() = runTest {
        withAccessFixture { f ->
            val before = f.base.snapshot(); val opens = f.base.protection.openCalls
            val malformed = listOf(localAccessWire().copyOf(51), localAccessWire().copyOf(53),
                localAccessWire().also { it[3] = 0x32 }, ByteArray(8192), ByteArray(52) { 0x20 },
                "{\"token\":\"synthetic-no-json\"}".encodeToByteArray(),
                java.util.Base64.getEncoder().encode(localAccessWire()))
            for (wire in malformed) assertNull(f.authenticate(wire))
            assertEquals(0, f.peers.captures.get()); assertEquals(opens, f.base.protection.openCalls)
            assertEquals(0, f.base.environment.calls); assertEquals(before, f.base.snapshot())
        }
    }

    @Test fun wrongSecretUnknownLocatorAndAlteredExistingLocatorCannotAuthenticate() = runTest {
        val f = LocalAccessVerifierFixture()
        try {
            val primary = f.base.security.defaultAccount().state.sessions.single()
            val other = primary.copy(sessionId = testId(9951))
            f.base.seed(account = f.base.security.defaultAccount(sessions = listOf(primary, other)))
            val original = f.base.issuance(sessionId = other.sessionId)
            val otherSecret = ByteArray(32) { (it + 121).toByte() }
            f.base.protectedStore.transaction { it.insertIssuance(LocalAccessIssuance(original.scope,
                original.actorId, original.sessionId, original.deviceId, original.generation,
                original.credentialRevisionAtIssue, original.admissionRevisionAtIssue, original.issuerContextId,
                original.issuedAt, original.validForMillis, LocalAccessTokenHash.hashSecret(otherSecret))) }
            val before = f.base.snapshot()
            assertNull(f.authenticate(localAccessWire(secret = ByteArray(32))))
            assertNull(f.authenticate(localAccessWire(sessionId = testId(9999))))
            assertNull(f.authenticate(localAccessWire(sessionId = other.sessionId)))
            assertEquals(0, f.base.environment.calls, "unauthenticated inputs cannot establish clock high-water")
            assertEquals(other.sessionId, assertNotNull(f.authenticate(localAccessWire(other.sessionId, otherSecret))).sessionId)
            assertNotNull(f.authenticate()); assertEquals(before, f.base.snapshot())
        } finally { f.close() }
    }

    @Test fun scopeConnectionDeviceAndEachRequiredPeerDescriptorMustMatch() = runTest {
        for (mismatch in listOf("connection", "organization", "company", "branch", "device", "key", "fingerprint", "absent-key", "absent-fingerprint")) {
            withAccessFixture { f ->
                when (mismatch) {
                    "connection" -> f.peers.connectionId = testId(9952)
                    "organization" -> f.peers.scope = TEST_SCOPE.copy(organizationId = testId(9952))
                    "company" -> f.peers.scope = TEST_SCOPE.copy(companyId = testId(9952))
                    "branch" -> f.peers.scope = TEST_SCOPE.copy(branchId = testId(9952))
                    "device" -> f.peers.deviceId = testId(9952)
                    "key" -> f.peers.key = "wrong-key"
                    "fingerprint" -> f.peers.fingerprint = "wrong-fingerprint"
                    "absent-key" -> f.peers.key = null
                    "absent-fingerprint" -> f.peers.fingerprint = null
                }
                val before = f.base.snapshot()
                assertNull(f.authenticate(), mismatch); assertEquals(0, f.base.environment.calls)
                assertEquals(before, f.base.snapshot(), mismatch)
            }
        }
    }

    @Test fun aSingleConfiguredAdmissionDescriptorIsSufficientButNeverAnOrFallback() = runTest {
        for (keyOnly in listOf(true, false)) {
            val f = LocalAccessVerifierFixture()
            try {
                val admission = LocalDeviceAdmission(TEST_SCOPE, TEST_AUTHORITY.deviceId, Revision(6), true,
                    if (keyOnly) "synthetic-public-key-id" else null,
                    if (keyOnly) null else "synthetic-fingerprint", emptySet())
                f.base.seed(admission = admission)
                if (keyOnly) f.peers.fingerprint = null else f.peers.key = null
                assertNotNull(f.authenticate())
                if (keyOnly) f.peers.key = "wrong" else f.peers.fingerprint = "wrong"
                assertNull(f.authenticate())
            } finally { f.close() }
        }
    }

    @Test fun missingAnyProtectedDependencyDeniesWithoutCreatingOrBackfillingAnything() = runTest {
        for (missing in listOf("account", "policy", "admission", "issuance")) {
            val f = LocalAccessVerifierFixture()
            try {
                f.base.seed(account = if (missing == "account") null else f.base.security.defaultAccount(),
                    policy = if (missing == "policy") null else f.base.policy(),
                    admission = if (missing == "admission") null else f.base.security.defaultAdmission(),
                    issuance = if (missing == "issuance") null else f.base.issuance())
                val before = f.base.snapshot()
                assertNull(f.authenticate(), missing); assertEquals(0, f.base.environment.calls)
                assertEquals(before, f.base.snapshot(), missing)
            } finally { f.close() }
        }
    }

    @Test fun authenticatedButDisabledRevokedMissingCredentiallessOrTerminalAccountNeverAuthenticates() = runTest {
        for (variant in listOf("disabled", "revoked", "missing-session", "credentialless", "credential-revised", "terminal", "purpose", "device")) {
            val f = LocalAccessVerifierFixture()
            try {
                val original = f.base.security.defaultAccount().state.sessions.single()
                val session = when (variant) {
                    "disabled", "revoked", "terminal" -> original.copy(status = SessionStatus.REVOKED)
                    "purpose" -> original.copy(authenticationPurpose = AuthenticationPurpose.PRIVILEGED_APPROVAL)
                    "device" -> original.copy(deviceId = testId(9953))
                    else -> original
                }
                f.base.seed(account = f.base.security.defaultAccount(
                    enabled = variant != "disabled" && variant != "terminal",
                    sessions = if (variant == "missing-session") emptyList() else listOf(session),
                    revision = if (variant == "terminal") Long.MAX_VALUE else 4,
                    generation = if (variant == "terminal") Long.MAX_VALUE else 5,
                    hasCredential = variant != "credentialless", credentialRevision = if (variant == "credential-revised") 3 else 2))
                val before = f.base.snapshot()
                assertNull(f.authenticate(), variant); assertEquals(before, f.base.snapshot(), variant)
            } finally { f.close() }
        }
    }

    @Test fun committedExplicitDeviceMoveRetainsHistoryButRevokesTheOriginalBearer() = runTest {
        withAccessFixture { f ->
            assertNotNull(f.authenticate())
            f.base.protectedStore.transaction { transaction ->
                val original = assertNotNull(transaction.readAccount(TEST_SCOPE, TEST_ACTOR))
                val moved = assertIs<SessionTransition.Applied>(LocalSessions.signIn(original, testId(9961),
                    testId(9962), explicitDeviceMove = true))
                assertEquals(setOf(testId(9110)), moved.revokedSessionIds)
                assertTrue(transaction.replaceAccount(original.revision, moved.state))
            }
            val afterMove = f.base.snapshot()
            assertNull(f.authenticate()); assertEquals(afterMove, f.base.snapshot())
            assertEquals(1, afterMove.getValue("protected_local_access_issuance").size,
                "no issuance rewrite, deletion or synthetic new-device issuance")
        }
    }

    @Test fun deniedOrReadmittedDeviceCannotReuseAnOldIssuance() = runTest {
        for (variant in listOf("revoked", "revision")) {
            val f = LocalAccessVerifierFixture()
            try {
                f.base.seed(admission = f.base.security.defaultAdmission(admitted = variant != "revoked",
                    revision = if (variant == "revision") 7 else 6))
                val before = f.base.snapshot()
                assertNull(f.authenticate()); assertEquals(before, f.base.snapshot())
            } finally { f.close() }
        }
    }

    @Test fun corruptEachProtectedRowFailsSanitizedWithoutPoisoningTheEnvironment() = runTest {
        for (kind in listOf("account", "policy", "admission", "issuance")) withAccessFixture { f ->
            val key = when (kind) {
                "account" -> ProtectedLocalRecordKey.Account(TEST_SCOPE, TEST_ACTOR)
                "policy" -> ProtectedLocalRecordKey.EffectivePolicy(TEST_SCOPE, TEST_ACTOR)
                "admission" -> ProtectedLocalRecordKey.Admission(TEST_SCOPE, TEST_AUTHORITY.deviceId)
                else -> f.base.issuanceKey()
            }
            f.base.security.corrupt(key, "ciphertextAndTag=zeroblob(length(ciphertextAndTag))")
            val before = f.base.snapshot()
            assertUnavailable { f.authenticate() }
            assertEquals(0, f.base.environment.calls); assertEquals(before, f.base.snapshot(), kind)
        }
    }

    @Test fun locatorReadUsesRealAeadInIssuanceFirstOrderButKeepsExistingFingerprintOrder() = runTest {
        withAccessFixture { f ->
            val opened = mutableListOf<ProtectedLocalRecordKey>()
            val real = f.base.protection
            val probe = object : DeviceProtectedLocalStorage by real {
                override suspend fun open(reference: ProtectedStorageKeyReference, expected: ProtectedLocalBinding,
                    sealed: SealedLocalRecord): LocalOpenResult {
                    opened += expected.key
                    return real.open(reference, expected, sealed)
                }
            }
            val store = RoomLocalSecurityStore(f.base.database, probe, f.base.security.keyReference)
            val principal = assertNotNull(f.authenticate())
            store.transaction { transaction ->
                val first = assertNotNull(transaction.readLocalAccessFacts(TEST_SCOPE, principal.sessionId))
                assertEquals(listOf(f.base.issuanceKey(), ProtectedLocalRecordKey.Account(TEST_SCOPE, TEST_ACTOR),
                    ProtectedLocalRecordKey.EffectivePolicy(TEST_SCOPE, TEST_ACTOR),
                    ProtectedLocalRecordKey.Admission(TEST_SCOPE, TEST_AUTHORITY.deviceId)), opened)
                val second = assertNotNull(transaction.readAuthorizationFacts(principal))
                assertTrue(first.sameDependencies(second)); assertEquals(8, opened.size)
                assertTrue(first.credentialPresent); assertEquals(Revision(2), first.credentialRevision)
            }
        }
    }

    @Test fun grantsRemainCurrentKernelAuthorityNotBearerClaims() = runTest {
        withAccessFixture { f ->
            var evaluations = 0
            val observedHandler = object : MasterCommandHandler by f.base.handler {
                override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
                    val prepared = f.base.handler.prepare(normalized, view, context)
                    return object : PreparedMutation() {
                        override val requirement = prepared.requirement
                        override val affectedResources = prepared.affectedResources
                        override val recoveryHoldBarrierKinds = prepared.recoveryHoldBarrierKinds
                        override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                            evaluations++
                            return prepared.evaluate(authorization, recordedAt)
                        }
                    }
                }
            }
            val kernel = f.base.kernel(commandHandler = observedHandler)
            val request = f.base.request()
            val principal = assertNotNull(f.authenticate())
            f.base.security.replacePolicyFixture(3, f.base.policy(4, grants = emptyList()))
            val before = f.base.snapshot()
            assertNotNull(f.authenticate(), "valid identity with no business grant is still authenticated")
            assertLocalAuthDenied(kernel.execute(principal, request))
            assertEquals(before, f.base.snapshot())
            // The existing kernel derives its action-specific requirement in read-only prepare,
            // THEN authorizes it. Valid identity alone must never reach effect evaluation.
            assertEquals(1, f.base.handler.prepareCount); assertEquals(0, evaluations)
            f.base.security.replacePolicyFixture(4, f.base.policy(5))
            val committed = assertIs<MutationReply.Recorded>(kernel.execute(principal, request))
            assertTrue(committed.committed); assertFalse(committed.replayed)
            assertEquals(2, f.base.handler.prepareCount); assertEquals(1, evaluations)
            assertEquals(5L, assertNotNull(f.base.database.facts().operation(TEST_SCOPE.storageKey(), request.operationId.value)).policyRevision)
            // SAME principal/operation, newly current grant: the observer has a positive control,
            // and neither bearer permissions nor a persisted denial determined this outcome.
        }
    }

    @Test fun retainedRealPrincipalCannotReplayOrReadACommittedResultAfterRevocation() = runTest {
        withAccessFixture { f ->
            val principal = assertNotNull(f.authenticate()); val request = f.base.request(); val kernel = f.base.kernel()
            assertIs<MutationReply.Recorded>(kernel.execute(principal, request))
            f.revoke(); val before = f.base.snapshot()
            assertNull(f.authenticate())
            assertLocalAuthDenied(kernel.execute(principal, request))
            assertLocalAuthDenied(kernel.lookup(principal, f.base.identity(request)))
            assertLocalAuthDenied(kernel.execute(principal, f.base.request(9954)))
            assertEquals(before, f.base.snapshot()); assertEquals(1, f.base.handler.prepareCount)
        }
    }

    @Test fun expirationIsExclusiveAndOriginalContextBootOrNotYetIssuedTimeMustMatch() = runTest {
        for (variant in listOf("before", "at-issue", "last", "expired", "context", "boot")) {
            withAccessFixture { f ->
                when (variant) {
                    "before" -> f.base.environment.elapsed = 999
                    "at-issue" -> f.base.environment.elapsed = 1000
                    "last" -> f.base.environment.elapsed = 1999
                    "expired" -> f.base.environment.elapsed = 2000
                    "context" -> f.base.environment.contextId = testId(9955)
                    "boot" -> f.base.environment.bootId = testId(9955)
                }
                val before = f.base.snapshot()
                if (variant in listOf("at-issue", "last")) assertNotNull(f.authenticate()) else assertNull(f.authenticate())
                assertEquals(before, f.base.snapshot(), variant)
            }
        }
    }

    @Test fun verifierAndKernelShareHighWaterAndCannotReviveAfterRollbackOrRegression() = runTest {
        withAccessFixture { f ->
            val principal = assertNotNull(f.authenticate()); val before = f.base.snapshot()
            f.base.environment.elapsed = 1700
            assertIs<LookupReply.AbsentAtCurrentWriter>(f.base.kernel().lookup(principal, f.base.identity(f.base.request())))
            f.base.environment.elapsed = 1600
            assertNull(f.authenticate(), "must see the kernel's higher observation")
            f.base.environment.elapsed = 1800
            assertNull(f.authenticate()); assertLocalAuthDenied(f.base.kernel().execute(principal, f.base.request()))
            assertEquals(before, f.base.snapshot())
        }
    }

    @Test fun environmentUnavailableOrChangedCurrentContextAndBootLatchAcrossRequests() = runTest {
        for (variant in listOf("null", "context", "boot", "exception")) withAccessFixture { f ->
            assertNotNull(f.authenticate()); val before = f.base.snapshot()
            when (variant) {
                "null" -> f.base.environment.available = false
                "context" -> f.base.environment.contextId = testId(9956)
                "boot" -> f.base.environment.bootId = testId(9956)
                else -> f.base.environment.onObserve = { error("synthetic private environment detail") }
            }
            assertNull(f.authenticate())
            f.base.environment.available = true; f.base.environment.contextId = testId(9500)
            f.base.environment.bootId = f.base.security.bootId; f.base.environment.onObserve = null
            f.base.environment.elapsed = 1600
            assertNull(f.authenticate()); assertEquals(before, f.base.snapshot(), variant)
        }
    }

    @Test fun retiredOrReboundPhysicalLifetimeCannotBecomeCurrentByReusingItsConnectionId() = runTest {
        for (rebind in listOf(true, false)) withAccessFixture { f ->
            f.base.protection.afterOpen = { if (rebind) f.peers.lifetime = Any() else f.peers.current = false }
            val before = f.base.snapshot()
            assertNull(f.authenticate()); assertEquals(1, f.peers.finalChecks.get())
            assertEquals(0, f.base.environment.calls); assertEquals(before, f.base.snapshot())
            f.base.protection.afterOpen = null; f.peers.current = true
            assertNotNull(f.authenticate(), "a newly captured authenticated lifetime can be checked independently")
        }
    }

    @Test fun ownershipMustMatchExactStoreRatherThanSameDatabaseOrEquivalentProvider() = runTest {
        withAccessFixture { f ->
            val anotherStore = RoomLocalSecurityStore(f.base.database, f.base.protection, f.base.security.keyReference)
            val opens = f.base.protection.openCalls
            assertUnavailable { RoomLocalAccessPrincipalVerifier(TEST_SCOPE, anotherStore, f.base.source, f.peers, 1) }
            assertEquals(opens, f.base.protection.openCalls); assertEquals(0, f.peers.captures.get())
            assertNotNull(f.authenticate())
        }
    }

    @Test fun actualEnclosingSqlTransactionIsUnavailableInsteadOfProvisionalIdentity() = runTest {
        withAccessFixture(capacity = 1) { f ->
            val before = f.base.snapshot(); val opens = f.base.protection.openCalls
            f.base.database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    assertUnavailable { f.authenticate() }
                    assertTrue(connection.inTransaction())
                }
            }
            assertEquals(opens, f.base.protection.openCalls); assertEquals(0, f.base.environment.calls)
            assertEquals(before, f.base.snapshot()); assertNotNull(f.authenticate(), "permit must recover")
        }
    }

    @Test fun centralDeviceAndServiceKindsHaveNoLocalBearerFallback() = runTest {
        withAccessFixture { f ->
            val credential = OpaqueCredential(localAccessWire())
            try {
                assertNull(f.factory.authenticateCentral(CentralGatewayCredentials(credential)))
                assertNull(f.factory.authenticateDevice(DeviceGatewayCredentials(ACCESS_CONNECTION)))
                assertNull(f.factory.authenticateService(ServiceGatewayCredentials(ACCESS_CONNECTION, credential)))
                assertEquals(0, f.peers.captures.get()); assertEquals(0, f.base.environment.calls)
            } finally { credential.destroy() }
        }
    }

    @Test fun explicitPositiveServiceLifetimeCapacityIsRequired() = runTest {
        withAccessFixture { f ->
            for (capacity in listOf(0, -1, Int.MIN_VALUE)) assertFailsWith<IllegalArgumentException> {
                RoomLocalAccessPrincipalVerifier(TEST_SCOPE, f.base.protectedStore, f.base.source, f.peers, capacity)
            }
        }
    }
}

internal suspend fun assertUnavailable(block: suspend () -> Any?) {
    val failure = assertFailsWith<RoomLocalAccessVerificationUnavailable> { block() }
    assertEquals("Local access verification unavailable", failure.message)
    assertNull(failure.cause); assertTrue(failure.suppressedExceptions.isEmpty())
}
