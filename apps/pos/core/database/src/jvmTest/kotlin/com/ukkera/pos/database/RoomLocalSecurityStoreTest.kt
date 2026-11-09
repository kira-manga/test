package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*
import kotlinx.coroutines.test.runTest

/** Real disposable SQLite and JCA AEAD with synthetic keys only; no accepting identity runtime. */
class RoomLocalSecurityStoreTest {
    @Test fun allFourRecordKindsAndBothThrottleDimensionsRoundTripWithoutWritesAcrossReopen() = runTest {
        val f = LocalSecurityStoreFixture(); f.seed()
        val before = f.protectedSnapshot()
        val encoded = f.expectedRecords()
        try {
            repeat(2) {
                f.store.transaction { tx ->
                    protectedKeys(f).zip(encoded).forEach { (key, bytes) -> assertContentEquals(bytes, tx.encoded(key)) }
                }
                assertEquals(before, f.protectedSnapshot())
            }
            assertEquals(10, f.protection.openCalls)
        } finally { f.close() }
        val reopened = LocalSecurityStoreFixture(f.path)
        try {
            reopened.store.transaction { tx ->
                protectedKeys(reopened).zip(encoded).forEach { (key, bytes) -> assertContentEquals(bytes, tx.encoded(key)) }
            }
            assertEquals(before, reopened.protectedSnapshot())
            assertEquals(5, reopened.protection.openCalls)
            assertEquals(0, reopened.protection.sealCalls)
        } finally { reopened.close() }
    }

    @Test fun anEmptyDatabaseReadNeverProvisionsAccountsPoliciesAdmissionBucketsOrKeys() = runTest {
        val f = LocalSecurityStoreFixture()
        try {
            repeat(2) { f.store.transaction { tx -> protectedKeys(f).forEach { assertNull(tx.encoded(it)) } } }
            assertEquals(List(5) { null }, f.protectedSnapshot())
            assertEquals(0, f.protection.openCalls); assertEquals(0, f.protection.sealCalls)
            f.database.useWriterConnection { c ->
                c.usePrepared("SELECT count(*) FROM branch_authority") { check(it.step()); assertEquals(0L, it.getLong(0)) }
            }
        } finally { f.close() }
    }

    @Test fun actualAeadUsesIndependentFreshNoncesAndDoesNotStoreCanonicalPlaintext() = runTest {
        withSecurityFixture { f ->
            val key = accountRecordKey(f)
            val binding = ProtectedLocalBinding(key, Revision(4))
            val bytes = ProtectedLocalSecurityCodec.encodeAccount(f.defaultAccount())
            val first = f.seal(binding, bytes.copyOf())
            val second = f.seal(binding, bytes.copyOf())
            assertFalse(first.copyNonce().contentEquals(second.copyNonce()))
            assertFalse(first.copyCiphertextAndTag().contentEquals(second.copyCiphertextAndTag()))
            val row = assertNotNull(f.database.localSecurity().account(f.scope.storageKey(), f.actorId.value))
            assertEquals(bytes.size + 16, row.ciphertextAndTag.size)
            assertFalse(row.ciphertextAndTag.copyOf(bytes.size).contentEquals(bytes))
            assertContentEquals(bytes, f.store.transaction { it.encoded(key) })
        }
    }

    @Test fun companyGrantsAndAllPolicyFieldsRemainMeaningfulThroughRealStorage() = runTest {
        val f = LocalSecurityStoreFixture()
        val grant = PermissionGrant(Permission(SecurityAction.DISCOUNT, PermissionVerb.APPROVE), f.scope.company(),
            GrantEffect.ALLOW, ownerAccess = OwnerAccess.ANY_OWNER, namedSubjectId = f.actorId,
            targetIds = setOf(testId(9401), testId(9400)))
        val policy = ActivatedLocalPolicy(f.scope, f.actorId, Revision(7), listOf(grant), LocalSessionPolicy(4),
            ThrottlePolicy(1, 2000, 64000, 3000))
        try {
            f.seed(policy = policy)
            val read = assertNotNull(f.store.transaction { it.readPolicy(f.scope, f.actorId) })
            assertContentEquals(ProtectedLocalSecurityCodec.encodePolicy(policy), ProtectedLocalSecurityCodec.encodePolicy(read))
            assertEquals(f.scope.company(), read.grants.single().scope)
            assertEquals(LocalSessionPolicy(4), read.sessionPolicy)
            assertEquals(policy.throttlePolicy, read.throttlePolicy)
        } finally { f.close() }
    }

    @Test fun full256SessionHistoryAboveCommandBudgetIsStoredAndDisabledWithoutTruncation() = runTest {
        val f = LocalSecurityStoreFixture()
        val original = f.defaultAccount(generation = 300, sessions = history(f))
        try {
            val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
            assertTrue(bytes.size > 64 * 1024)
            assertTrue(bytes.size <= ProtectedLocalTable.ACCOUNT.maximumPlaintextBytes)
            f.seed(account = original)
            assertContentEquals(bytes, f.store.transaction { it.encoded(accountRecordKey(f)) })
            val disabled = assertIs<SessionTransition.Applied>(LocalSessions.disable(original.state)).state
            assertTrue(f.store.transaction { it.replaceAccount(original.state.revision, disabled) })
            val saved = assertNotNull(f.store.transaction { it.readProtectedAccount(f.scope, f.actorId) })
            assertEquals(256, saved.state.sessions.size)
            assertEquals(original.state.sessions.map { it.sessionId }, saved.state.sessions.map { it.sessionId })
            assertTrue(saved.state.sessions.all { it.status == SessionStatus.REVOKED })
            assertEquals(original.credentialRevision, saved.credentialRevision)
            assertContentEquals(original.credential!!.copyVerifier(), saved.credential!!.copyVerifier())
        } finally { f.close() }
    }

    @Test fun credentialRevisionZeroAndExplicitNullRemainDistinctAfterSessionOnlyChanges() = runTest {
        for (hasCredential in listOf(true, false)) {
            val f = LocalSecurityStoreFixture()
            val original = f.defaultAccount(credentialRevision = 0, hasCredential = hasCredential)
            try {
                f.seed(account = original)
                val next = assertIs<SessionTransition.Applied>(LocalSessions.disable(original.state)).state
                assertTrue(f.store.transaction { it.replaceAccount(Revision(4), next) })
                val saved = assertNotNull(f.store.transaction { it.readProtectedAccount(f.scope, f.actorId) })
                assertEquals(Revision(0), saved.credentialRevision)
                if (hasCredential) {
                    assertEquals(CredentialBinding(f.scope, f.actorId, Revision(0)), saved.credential!!.binding)
                    assertContentEquals(original.credential!!.copySalt(), saved.credential!!.copySalt())
                } else assertNull(saved.credential)
            } finally { f.close() }
        }
    }

    @Test fun healthyMetadataControlActuallyHydratesEachSelectedEnvelopeAndInvokesAead() = runTest {
        val probe = ProtectedReadProbe()
        val f = LocalSecurityStoreFixture(driver = probe)
        try {
            f.seed(); probe.reset()
            f.store.transaction { tx -> protectedKeys(f).forEach { assertNotNull(tx.encoded(it)) } }
            assertEquals(5, probe.fullRows.get())
            assertEquals(10, probe.blobReads.get())
            assertEquals(5, f.protection.openCalls)
        } finally { f.close() }
    }

    @Test fun sqliteTypeVersionAndHeaderFailuresStopBeforeAnyEnvelopeOrCiphertextHydration() = runTest {
        val invalidHeaders = listOf(
            "schemaVersion=4294967297", "schemaVersion=1.5", "schemaVersion=CAST('1' AS BLOB)",
            "formatVersion=4294967297", "formatVersion=CAST('1' AS BLOB)",
            "revision=4.25", "revision=CAST('4' AS BLOB)", "revision=-1",
            "algorithm='AES-256-GCM' || char(0) || 'tail'", "algorithm=CAST('AES-256-GCM' AS BLOB)",
            "keyId=keyId || char(0) || 'tail'", "keyId=zeroblob(1048576)",
            "nonce=zeroblob(13)", "nonce=CAST(nonce AS TEXT)",
            "ciphertextAndTag=CAST(ciphertextAndTag AS TEXT)", "ciphertextAndTag=zeroblob(16)",
        )
        for (index in 0..3) for (corruption in invalidHeaders) {
            val probe = ProtectedReadProbe()
            val f = LocalSecurityStoreFixture(driver = probe)
            try {
                f.seed(); val key = protectedKeys(f)[index]
                f.corrupt(key, corruption)
                val before = f.rawRecord(key)
                probe.reset()
                assertLocalUnavailable { f.store.transaction { it.encoded(key) } }
                assertEquals(0, probe.fullRows.get(), corruption)
                assertEquals(0, probe.blobReads.get(), corruption)
                assertEquals(0, f.protection.openCalls, corruption)
                assertEquals(before, f.rawRecord(key))
            } finally { f.close() }
        }
    }

    @Test fun everyCiphertextBudgetIsInclusiveAndExcessIsRejectedBeforeHydration() = runTest {
        for (index in 0..4) for (atBoundary in listOf(true, false)) {
            val probe = ProtectedReadProbe()
            val f = LocalSecurityStoreFixture(driver = probe)
            try {
                f.seed(); val key = protectedKeys(f)[index]
                val size = key.table.maximumPlaintextBytes + 16 + if (atBoundary) 0 else 1
                f.corrupt(key, "ciphertextAndTag=zeroblob($size)"); probe.reset()
                // At the exact size the AEAD tag is invalid, not the metadata size.
                assertLocalUnavailable { f.store.transaction { it.encoded(key) } }
                assertEquals(if (atBoundary) 1 else 0, probe.fullRows.get())
                assertEquals(if (atBoundary) 2 else 0, probe.blobReads.get())
                assertEquals(if (atBoundary) 1 else 0, f.protection.openCalls)
            } finally { f.close() }
        }
    }

    @Test fun storedKeyIdCannotSelectAnotherKeyEvenWhenItsHeaderIsWellFormed() = runTest {
        withSecurityFixture { f ->
            val key = accountRecordKey(f)
            f.corrupt(key, "keyId='${testId(9402).value}'")
            val before = f.rawRecord(key)
            assertLocalUnavailable { f.store.transaction { it.encoded(key) } }
            assertEquals(0, f.protection.openCalls)
            assertEquals(before, f.rawRecord(key))
        }
    }

    @Test fun aDifferentConfiguredKeyIsNotAnAutomaticFallbackToTheStoredKey() = runTest {
        withSecurityFixture { f ->
            val store = RoomLocalSecurityStore(f.database, f.protection, ProtectedStorageKeyReference(testId(9402)))
            assertLocalUnavailable { store.transaction { it.encoded(accountRecordKey(f)) } }
            assertEquals(0, f.protection.openCalls)
        }
    }

    @Test fun nonceCiphertextAndTagBitChangesFailActualAeadWithoutChangingTheRow() = runTest {
        for (cut in listOf("nonce", "ciphertext", "tag")) withSecurityFixture { f ->
            val key = accountRecordKey(f)
            val row = assertNotNull(f.database.localSecurity().account(f.scope.storageKey(), f.actorId.value))
            val bytes = if (cut == "nonce") row.nonce else row.ciphertextAndTag
            val index = if (cut == "tag") bytes.lastIndex else 0
            bytes[index] = (bytes[index].toInt() xor 1).toByte()
            f.corrupt(key, "${if (cut == "nonce") "nonce" else "ciphertextAndTag"}=x'${bytes.sqlHex()}'")
            val before = f.rawRecord(key)
            assertLocalUnavailable { f.store.transaction { it.encoded(key) } }
            assertEquals(1, f.protection.openCalls)
            assertEquals(before, f.rawRecord(key))
        }
    }

    @Test fun actualTagBindsLogicalTableEachScopeCoordinateActorAndRevision() = runTest {
        withSecurityFixture { f ->
            val key = accountRecordKey(f)
            val binding = ProtectedLocalBinding(key, Revision(4))
            val wrongBindings = listOf(
                binding.copy(key = ProtectedLocalRecordKey.EffectivePolicy(f.scope, f.actorId)),
                binding.copy(key = key.copy(scope = f.scope.copy(organizationId = testId(9403)))),
                binding.copy(key = key.copy(scope = f.scope.copy(companyId = testId(9403)))),
                binding.copy(key = key.copy(scope = f.scope.copy(branchId = testId(9403)))),
                binding.copy(key = key.copy(actorId = testId(9403))), binding.copy(revision = Revision(5)),
            )
            for (wrong in wrongBindings) {
                val record = f.seal(wrong, ProtectedLocalSecurityCodec.encodeAccount(f.defaultAccount()))
                f.corrupt(key, record.sqlEnvelope())
                val before = f.rawRecord(key)
                val calls = f.protection.openCalls
                assertLocalUnavailable { f.store.transaction { it.encoded(key) } }
                assertEquals(calls + 1, f.protection.openCalls)
                assertEquals(before, f.rawRecord(key))
            }
        }
    }

    @Test fun clearRevisionCannotBeIncrementedWithoutRetaggingTheCiphertext() = runTest {
        withSecurityFixture { f ->
            f.corrupt(accountRecordKey(f), "revision=revision+1")
            assertLocalUnavailable { f.store.transaction { it.encoded(accountRecordKey(f)) } }
            assertEquals(1, f.protection.openCalls)
        }
    }

    @Test fun validTagDoesNotBlessWrongInnerIdentityOrNoncanonicalJson() = runTest {
        withSecurityFixture { f ->
            val key = accountRecordKey(f); val binding = ProtectedLocalBinding(key, Revision(4))
            val wrong = ProtectedLocalAccount(LocalAccountSessionState(f.scope, testId(9404), true, Revision(4),
                SessionGeneration(5), emptyList()), Revision(2), null)
            val normal = ProtectedLocalSecurityCodec.encodeAccount(f.defaultAccount())
            for (bytes in listOf(ProtectedLocalSecurityCodec.encodeAccount(wrong), normal + byteArrayOf(32))) {
                val record = f.seal(binding, bytes)
                f.corrupt(key, record.sqlEnvelope())
                val calls = f.protection.openCalls
                assertLocalUnavailable { f.store.transaction { it.encoded(key) } }
                assertEquals(calls + 1, f.protection.openCalls)
            }
        }
    }

    @Test fun unsupportedDefaultProviderAndEveryReportedKeyFailurePreserveStoredData() = runTest {
        withSecurityFixture { f ->
            val before = f.protectedSnapshot()
            val unavailable = RoomLocalSecurityStore(f.database, UnavailableDeviceProtectedLocalStorage, f.keyReference)
            assertLocalUnavailable { unavailable.transaction { it.encoded(accountRecordKey(f)) } }
            for (failure in ProtectedKeyFailure.entries) {
                f.protection.failure = failure
                assertLocalUnavailable { f.store.transaction { it.encoded(accountRecordKey(f)) } }
            }
            f.protection.failure = null
            f.protection.throwOnOpen = IllegalArgumentException("SYNTHETIC PRIVATE provider detail", IllegalStateException("PRIVATE cause"))
            assertLocalUnavailable { f.store.transaction { it.encoded(accountRecordKey(f)) } }
            f.protection.throwOnOpen = null
            assertEquals(before, f.protectedSnapshot())
        }
    }

    @Test fun lossOfKeyContextDuringAeadCannotPublishDecodedState() = runTest {
        withSecurityFixture { f ->
            var published = false
            f.protection.afterOpen = { f.protection.status = KeyProtectionStatus.UNSUPPORTED }
            assertLocalUnavailable { f.store.transaction { it.encoded(accountRecordKey(f)); published = true } }
            assertFalse(published); assertEquals(1, f.protection.openCalls)
        }
    }

    @Test fun syntheticSessionMoveAndRevokeUseCasAndRetainCredentialsWithoutIssuingTokens() = runTest {
        withSecurityFixture { f ->
            val original = f.defaultAccount()
            val move = assertIs<SessionTransition.Applied>(LocalSessions.signIn(original.state, testId(9405), testId(9406),
                explicitDeviceMove = true)).state
            assertTrue(f.store.transaction { it.replaceAccount(original.state.revision, move) })
            val saved = assertNotNull(f.store.transaction { it.readProtectedAccount(f.scope, f.actorId) })
            assertEquals(Revision(5), saved.state.revision); assertEquals(SessionGeneration(6), saved.state.generation)
            assertEquals(2, saved.state.sessions.size)
            assertEquals(SessionStatus.REVOKED, saved.state.sessions.first().status)
            assertEquals(testId(9406), saved.state.sessions.last().deviceId)
            assertEquals(original.credential!!.binding, saved.credential!!.binding)
            assertContentEquals(original.credential!!.copyVerifier(), saved.credential!!.copyVerifier())
            val revoke = assertIs<SessionTransition.Applied>(LocalSessions.revoke(saved.state, testId(9405))).state
            assertTrue(f.store.transaction { it.replaceAccount(saved.state.revision, revoke) })
            assertEquals(revoke.sessions, f.store.transaction { it.readAccount(f.scope, f.actorId) }!!.sessions)
        }
    }

    @Test fun staleWrongStepDroppedReorderedOrReforgedSessionHistoryIsNeverAccepted() = runTest {
        val f = LocalSecurityStoreFixture()
        val original = f.defaultAccount(sessions = history(f).take(2), generation = 5)
        try {
            f.seed(account = original)
            val s = original.state
            fun next(revision: Long = 5, generation: Long = 6, sessions: List<LocalSessionRecord> = s.sessions) =
                LocalAccountSessionState(s.scope, s.actorId, true, Revision(revision), SessionGeneration(generation), sessions)
            val candidates = listOf(
                next(revision = 4), next(revision = 6), next(generation = 5), next(generation = 7),
                next(sessions = s.sessions.drop(1)), next(sessions = s.sessions.reversed()),
                next(sessions = listOf(s.sessions.first().copy(deviceId = testId(9410))) + s.sessions.drop(1)),
                next(sessions = listOf(s.sessions.first().copy(authenticationPurpose = AuthenticationPurpose.SERVICE)) + s.sessions.drop(1)),
                next(sessions = s.sessions + s.sessions.first().copy(sessionId = testId(9411), generation = SessionGeneration(6), status = SessionStatus.REVOKED)),
            )
            val before = f.protectedSnapshot(); val seals = f.protection.sealCalls
            for (candidate in candidates) assertFalse(f.store.transaction { it.replaceAccount(Revision(4), candidate) })
            val valid = assertIs<SessionTransition.Applied>(LocalSessions.disable(s)).state
            assertFalse(f.store.transaction { it.replaceAccount(Revision(3), valid) })
            assertEquals(seals, f.protection.sealCalls)
            assertEquals(before, f.protectedSnapshot())
        } finally { f.close() }
    }

    @Test fun revokedHistoryAndDisabledAccountCannotBeReactivatedByAStaleReplacement() = runTest {
        withSecurityFixture { f ->
            val disabled = assertIs<SessionTransition.Applied>(LocalSessions.disable(f.defaultAccount().state)).state
            assertTrue(f.store.transaction { it.replaceAccount(Revision(4), disabled) })
            val before = f.protectedSnapshot()
            val reactivated = LocalAccountSessionState(f.scope, f.actorId, true, Revision(6), SessionGeneration(7),
                disabled.sessions.map { it.copy(status = SessionStatus.ACTIVE) })
            assertFalse(f.store.transaction { it.replaceAccount(Revision(5), reactivated) })
            assertEquals(before, f.protectedSnapshot())
        }
    }

    @Test fun terminalGenerationCanJumpLowAccountRevisionToBothMaxWithAllHistoryRevoked() = runTest {
        val f = LocalSecurityStoreFixture()
        val original = f.defaultAccount(revision = 4, generation = Long.MAX_VALUE - 1, sessions = history(f))
        try {
            f.seed(account = original)
            val terminal = assertIs<SessionTransition.Applied>(LocalSessions.disable(original.state)).state
            assertTrue(f.store.transaction { it.replaceAccount(Revision(4), terminal) })
            val saved = assertNotNull(f.store.transaction { it.readProtectedAccount(f.scope, f.actorId) })
            assertEquals(Revision(Long.MAX_VALUE), saved.state.revision)
            assertEquals(SessionGeneration(Long.MAX_VALUE), saved.state.generation)
            assertFalse(saved.state.enabled); assertEquals(256, saved.state.sessions.size)
            assertEquals(original.state.sessions.map { it.copy(status = SessionStatus.REVOKED) }, saved.state.sessions)
            assertEquals(original.credentialRevision, saved.credentialRevision)
            val bytes = f.rawRecord(accountRecordKey(f)); val seals = f.protection.sealCalls
            assertTrue(f.store.transaction { it.replaceAccount(Revision(Long.MAX_VALUE), saved.state) })
            assertEquals(bytes, f.rawRecord(accountRecordKey(f))); assertEquals(seals, f.protection.sealCalls)
            assertFalse(f.store.transaction { it.replaceAccount(Revision(4), saved.state) })
            val dropped = LocalAccountSessionState(f.scope, f.actorId, false, Revision(Long.MAX_VALUE),
                SessionGeneration(Long.MAX_VALUE), emptyList())
            assertFalse(f.store.transaction { it.replaceAccount(Revision(Long.MAX_VALUE), dropped) })
            assertEquals(bytes, f.rawRecord(accountRecordKey(f)))
        } finally { f.close() }
    }

    @Test fun callbackFailureAndCaughtReadFailureCannotCommitEarlierAccountReplacement() = runTest {
        for (caught in listOf(false, true)) withSecurityFixture { f ->
            val before = f.protectedSnapshot()
            val disabled = assertIs<SessionTransition.Applied>(LocalSessions.disable(f.defaultAccount().state)).state
            assertLocalUnavailable {
                f.store.transaction { tx ->
                    assertTrue(tx.replaceAccount(Revision(4), disabled))
                    if (caught) {
                        f.protection.throwOnOpen = IllegalArgumentException("SYNTHETIC private read")
                        try { tx.readPolicy(f.scope, f.actorId) } catch (_: IllegalStateException) { /* Deliberately ignored. */ }
                    } else error("SYNTHETIC private callback")
                }
            }
            f.protection.throwOnOpen = null
            assertEquals(before, f.protectedSnapshot())
        }
    }

    @Test fun ignoredCasConflictBeforeOrAfterAValidWritePoisonsTheWholeTransaction() = runTest {
        for (conflictFirst in listOf(false, true)) withSecurityFixture { f ->
            val before = f.protectedSnapshot()
            val disabled = assertIs<SessionTransition.Applied>(LocalSessions.disable(f.defaultAccount().state)).state
            assertLocalUnavailable {
                f.store.transaction { tx ->
                    if (conflictFirst) assertFalse(tx.replaceAccount(Revision(3), disabled))
                    assertTrue(tx.replaceAccount(Revision(4), disabled))
                    if (!conflictFirst) assertFalse(tx.replaceAccount(Revision(4), disabled))
                }
            }
            assertEquals(before, f.protectedSnapshot())
        }
    }

    @Test fun directlyThrownCancellationPreservesItsIdentityAndRollsBackPriorAccountWrite() = runTest {
        withSecurityFixture { f ->
            val before = f.protectedSnapshot()
            val cancelled = LocalStorageCancellationSentinel("SYNTHETIC cancellation")
            assertSame(cancelled, assertFailsWith<CancellationException> {
                f.store.transaction { tx ->
                    val disabled = assertIs<SessionTransition.Applied>(LocalSessions.disable(f.defaultAccount().state)).state
                    assertTrue(tx.replaceAccount(Revision(4), disabled))
                    f.protection.throwOnOpen = cancelled
                    tx.readPolicy(f.scope, f.actorId)
                }
            })
            f.protection.throwOnOpen = null
            assertEquals(before, f.protectedSnapshot())
        }
    }

    @Test fun escapedTransactionCannotReadOrReplaceAfterItsOwningCommit() = runTest {
        withSecurityFixture { f ->
            val escaped = f.store.transaction { it }
            val calls = f.protection.openCalls
            assertLocalUnavailable { escaped.readAccount(f.scope, f.actorId) }
            val next = assertIs<SessionTransition.Applied>(LocalSessions.disable(f.defaultAccount().state)).state
            assertLocalUnavailable { escaped.replaceAccount(Revision(4), next) }
            assertEquals(calls, f.protection.openCalls)
        }
    }

    @Test fun noReplaceAndNoDeleteGuardsRejectRealSqlForAllFourTables() = runTest {
        withSecurityFixture { f ->
            val before = f.protectedSnapshot()
            for (table in ProtectedLocalSchemaCallback.tableNames) {
                assertFails { f.executeProtectedSql("INSERT OR REPLACE INTO $table SELECT * FROM $table") }
                assertFails { f.executeProtectedSql("DELETE FROM $table") }
                assertEquals(before, f.protectedSnapshot())
            }
        }
    }

    @Test fun sqlIdentityAndRevisionGuardsRejectRebindingNonIntegersAndUnapprovedJumps() = runTest {
        withSecurityFixture { f ->
            val otherScope = f.scope.copy(branchId = testId(9413)).storageKey()
            // A missing destination branch would let the FK mask a missing scope guard.
            f.database.useWriterConnection { c ->
                requireDurableWriter(c)
                c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val dao = f.database.facts()
                    dao.insertBranch(assertNotNull(dao.branch(f.scope.storageKey())).copy(scope = otherScope))
                    assertNotNull(dao.branch(otherScope))
                }
            }
            val before = f.protectedSnapshot()
            for ((index, table) in ProtectedLocalSchemaCallback.tableNames.withIndex()) {
                val identity = if (index == 2) "deviceId" else "actorId"
                val mutations = mutableListOf(
                    "revision=revision", "revision=revision-1", "revision=revision+2", "revision=-1",
                    "revision=revision+0.5", "revision=CAST('5' AS BLOB)",
                    "revision=revision+1, $identity='${testId(9412).value}'",
                    "revision=revision+1, scope='$otherScope'",
                )
                if (index != 0) mutations += "revision=9223372036854775807"
                for (set in mutations) {
                    assertFails { f.executeProtectedSql("UPDATE $table SET $set") }
                    assertEquals(before, f.protectedSnapshot())
                }
            }
            // Target one bucket and a genuinely absent destination, not two rows that
            // would collide on their common new dimension and fail a UNIQUE constraint.
            val absent = ProtectedLocalRecordKey.Throttle(ThrottleKey(f.scope, f.actorId, ThrottleDimension.Device(testId(9414))))
            assertNull(f.rawRecord(absent))
            assertFails {
                f.executeProtectedSql("UPDATE protected_login_throttle SET revision=revision+1, dimensionKey='${absent.dimensionKey}' " +
                    "WHERE scope='${f.scope.storageKey()}' AND actorId='${f.actorId.value}' AND dimensionKey='account'")
            }
            assertEquals(before, f.protectedSnapshot())
            assertNull(f.rawRecord(absent))
        }
    }

    @Test fun healthyPlusOneSqlReplacementsRemainPossibleForEveryProtectedTable() = runTest {
        withSecurityFixture { f ->
            val original = f.defaultAccount()
            val next = assertIs<SessionTransition.Applied>(LocalSessions.disable(original.state)).state
            assertTrue(f.store.transaction { it.replaceAccount(Revision(4), next) })
            f.replacePolicyFixture(3, f.defaultPolicy(4))
            f.replaceAdmissionFixture(6, f.defaultAdmission(7))
            f.replaceThrottleFixture(0, f.defaultThrottle(revision = 1))
            f.replaceThrottleFixture(0, f.defaultThrottle(ThrottleDimension.Device(f.deviceId), revision = 1))
            f.store.transaction { tx ->
                assertEquals(Revision(5), tx.readAccount(f.scope, f.actorId)!!.revision)
                assertEquals(Revision(4), tx.readPolicy(f.scope, f.actorId)!!.revision)
                assertEquals(Revision(7), tx.readAdmission(f.scope, f.deviceId)!!.revision)
                assertEquals(Revision(1), tx.readThrottle(f.defaultThrottle().key)!!.revision)
                assertEquals(Revision(1), tx.readThrottle(f.defaultThrottle(ThrottleDimension.Device(f.deviceId)).key)!!.revision)
            }
        }
    }

    @Test fun maxRevisionRowsRejectEvenSameRevisionRawUpdatesIncludingAccountTerminalNoop() = runTest {
        val f = LocalSecurityStoreFixture()
        val terminal = ProtectedLocalAccount(assertIs<SessionTransition.Applied>(
            LocalSessions.disable(f.defaultAccount(generation = Long.MAX_VALUE - 1).state)).state, Revision(2), null)
        try {
            f.seed(account = terminal, policy = f.defaultPolicy(Long.MAX_VALUE), admission = f.defaultAdmission(Long.MAX_VALUE),
                accountThrottle = f.defaultThrottle(revision = Long.MAX_VALUE),
                deviceThrottle = f.defaultThrottle(ThrottleDimension.Device(f.deviceId), revision = Long.MAX_VALUE))
            val before = f.protectedSnapshot()
            for (table in ProtectedLocalSchemaCallback.tableNames) {
                for (set in listOf("revision=revision", "revision=revision+1", "revision=0")) {
                    assertFails { f.executeProtectedSql("UPDATE $table SET $set") }
                }
            }
            assertEquals(before, f.protectedSnapshot())
        } finally { f.close() }
    }

    @Test fun eachProtectedTableReferencesTheExistingBranchRatherThanManufacturingOne() = runTest {
        withSecurityFixture { f ->
            val before = f.protectedSnapshot()
            val missingScope = f.scope.copy(branchId = testId(9415)).storageKey()
            for ((index, table) in ProtectedLocalSchemaCallback.tableNames.withIndex()) {
                val id = if (index == 2) "deviceId" else "actorId"
                val dimension = if (index == 3) ",dimensionKey" else ""
                val rest = "$id$dimension,schemaVersion,formatVersion,algorithm,revision,keyId,nonce,ciphertextAndTag"
                assertFails { f.executeProtectedSql("INSERT INTO $table (scope,$rest) SELECT '$missingScope',$rest FROM $table LIMIT 1") }
            }
            assertEquals(before, f.protectedSnapshot())
            f.database.useWriterConnection { c -> c.usePrepared("PRAGMA foreign_key_check") { assertFalse(it.step()) } }
        }
    }

    @Test fun coherentHistoricalCiphertextAndRevisionStillPassSoAeadIsNotRollbackResistance() = runTest {
        withSecurityFixture { f ->
            val key = accountRecordKey(f)
            val original = assertNotNull(f.database.localSecurity().account(f.scope.storageKey(), f.actorId.value))
            val oldPlaintext = ProtectedLocalSecurityCodec.encodeAccount(f.defaultAccount())
            val disabled = assertIs<SessionTransition.Applied>(LocalSessions.disable(f.defaultAccount().state)).state
            assertTrue(f.store.transaction { it.replaceAccount(Revision(4), disabled) })
            assertFalse(f.store.transaction { it.readAccount(f.scope, f.actorId) }!!.enabled)
            // Deliberately bypass guards in the disposable fixture to model coherent old-disk restoration.
            f.corrupt(key, "revision=4,nonce=x'${original.nonce.sqlHex()}',ciphertextAndTag=x'${original.ciphertextAndTag.sqlHex()}'")
            assertContentEquals(oldPlaintext, f.store.transaction { it.encoded(key) })
            assertTrue(f.store.transaction { it.readAccount(f.scope, f.actorId) }!!.enabled)
        }
    }
}

private suspend fun withSecurityFixture(block: suspend (LocalSecurityStoreFixture) -> Unit) {
    val fixture = LocalSecurityStoreFixture()
    try { fixture.seed(); block(fixture) } finally { fixture.close() }
}

private fun accountRecordKey(f: LocalSecurityStoreFixture) = ProtectedLocalRecordKey.Account(f.scope, f.actorId)
private fun protectedKeys(f: LocalSecurityStoreFixture): List<ProtectedLocalRecordKey> = listOf(
    accountRecordKey(f), ProtectedLocalRecordKey.EffectivePolicy(f.scope, f.actorId),
    ProtectedLocalRecordKey.Admission(f.scope, f.deviceId),
    ProtectedLocalRecordKey.Throttle(f.defaultThrottle().key),
    ProtectedLocalRecordKey.Throttle(f.defaultThrottle(ThrottleDimension.Device(f.deviceId)).key),
)

private fun LocalSecurityStoreFixture.expectedRecords(): List<ByteArray> = listOf(
    ProtectedLocalSecurityCodec.encodeAccount(defaultAccount()), ProtectedLocalSecurityCodec.encodePolicy(defaultPolicy()),
    ProtectedLocalSecurityCodec.encodeAdmission(defaultAdmission()), ProtectedLocalSecurityCodec.encodeThrottle(defaultThrottle()),
    ProtectedLocalSecurityCodec.encodeThrottle(defaultThrottle(ThrottleDimension.Device(deviceId))),
)

private suspend fun LocalSecurityStoreFixture.protectedSnapshot(): List<List<String>?> = protectedKeys(this).map { rawRecord(it) }
private suspend fun RoomLocalSecurityTransaction.encoded(key: ProtectedLocalRecordKey): ByteArray? = when (key) {
    is ProtectedLocalRecordKey.Account -> readProtectedAccount(key.scope, key.actorId)?.let(ProtectedLocalSecurityCodec::encodeAccount)
    is ProtectedLocalRecordKey.EffectivePolicy -> readPolicy(key.scope, key.actorId)?.let(ProtectedLocalSecurityCodec::encodePolicy)
    is ProtectedLocalRecordKey.Admission -> readAdmission(key.scope, key.deviceId)?.let(ProtectedLocalSecurityCodec::encodeAdmission)
    is ProtectedLocalRecordKey.Throttle -> readThrottle(key.key)?.let(ProtectedLocalSecurityCodec::encodeThrottle)
    is ProtectedLocalRecordKey.SessionIssuance -> readIssuance(key.scope, key.sessionId)?.let(ProtectedLocalSecurityCodec::encodeIssuance)
    is ProtectedLocalRecordKey.ActionApproval,
    is ProtectedLocalRecordKey.AuthConfiguration,
    is ProtectedLocalRecordKey.AuthEvent,
    is ProtectedLocalRecordKey.RefreshIssuance,
    is ProtectedLocalRecordKey.SessionArchive -> error("Unsupported protected record key in legacy fixture")
}

private fun history(f: LocalSecurityStoreFixture): List<LocalSessionRecord> = List(256) {
    LocalSessionRecord(testId(10000 + it), f.scope, f.actorId, f.deviceId, SessionGeneration(it.toLong() + 1),
        SessionStatus.ACTIVE, AuthenticationPurpose.INTERACTIVE)
}

private suspend fun assertLocalUnavailable(block: suspend () -> Any?) {
    val failure = assertFailsWith<IllegalStateException> { block() }
    assertEquals("Protected local record unavailable", failure.message)
    assertNull(failure.cause)
}

private fun ByteArray.sqlHex(): String = HexFormat.of().formatHex(this)
private fun SealedLocalRecord.sqlEnvelope(): String =
    "nonce=x'${copyNonce().sqlHex()}',ciphertextAndTag=x'${copyCiphertextAndTag().sqlHex()}'"

private suspend fun LocalSecurityStoreFixture.executeProtectedSql(sql: String) = database.useWriterConnection { c ->
    requireDurableWriter(c)
    c.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) { usePrepared(sql) { it.step() } }
}

/** Actual generated SELECT * row steps and BLOB accessor calls, not inference from thrown errors. */
private class ProtectedReadProbe : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    val fullRows = AtomicInteger()
    val blobReads = AtomicInteger()
    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool
    fun reset() { fullRows.set(0); blobReads.set(0) }
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val fullRow = sql.trimStart().startsWith("SELECT * FROM protected_", ignoreCase = true)
                val statement = connection.prepare(sql)
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean = statement.step().also { if (it && fullRow) fullRows.incrementAndGet() }
                    override fun getBlob(index: Int): ByteArray {
                        if (fullRow) blobReads.incrementAndGet()
                        return statement.getBlob(index)
                    }
                }
            }
        }
    }
}
