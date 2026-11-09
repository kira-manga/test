package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic facts only: no accepting verifier, qualified clock, persistence or login is installed. */
class LocalAccessIssuanceTest {
    private val issuerContext = id(901)

    @Test
    fun hashDerivationMatchesLiteralIndependentVectors() {
        assertEquals(32, LocalAccessTokenHash.SECRET_BYTES)
        assertEquals("ukkera.local-access-token/sha256/v1", LocalAccessTokenHash.HASH_DOMAIN)
        // Independently derived with Python hashlib from the literal ASCII domain, one NUL,
        // and each specified 32-byte suffix. Expected digests never call the Kotlin helper.
        val vectors = listOf(
            ByteArray(32) to ZERO_SECRET_HASH,
            ByteArray(32) { it.toByte() } to SEQUENCE_SECRET_HASH,
            ByteArray(32) { 0xff.toByte() } to FF_SECRET_HASH,
        )
        for ((secret, expected) in vectors) {
            assertEquals(expected, LocalAccessTokenHash.hashSecret(secret).canonicalSha256())
        }
    }

    @Test
    fun hashFactoryRequiresCanonicalFullSha256() {
        for (valid in listOf("0".repeat(64), "f".repeat(64), SEQUENCE_SECRET_HASH)) {
            assertEquals(valid, LocalAccessTokenHash.fromCanonicalSha256(valid).canonicalSha256())
        }
        for (invalid in listOf(
            "", "0".repeat(63), "0".repeat(65), "F".repeat(64), SEQUENCE_SECRET_HASH.uppercase(),
            "g" + "0".repeat(63), " " + "0".repeat(63), "0".repeat(63) + "\n",
            "\u0000" + "0".repeat(63), "\uff10".repeat(64), "sha256:$SEQUENCE_SECRET_HASH",
        )) {
            val failure = assertFailsWith<IllegalArgumentException> { LocalAccessTokenHash.fromCanonicalSha256(invalid) }
            assertFalse(SEQUENCE_SECRET_HASH in failure.toString())
        }
    }

    @Test
    fun hashSecretRequiresExactly32BytesWithoutGuessingEntropy() {
        for (size in listOf(0, 1, 16, 31, 33, 64, 8192)) {
            val secret = ByteArray(size) { 0x5a }
            val before = secret.copyOf()
            assertFailsWith<IllegalArgumentException> { LocalAccessTokenHash.hashSecret(secret) }
            assertContentEquals(before, secret)
        }
        // Low-entropy synthetic bytes still have the required shape. The helper cannot certify
        // CSPRNG provenance, nor can it distinguish arbitrary digest bytes from secret bytes.
        assertEquals(ZERO_SECRET_HASH, LocalAccessTokenHash.hashSecret(ByteArray(32)).canonicalSha256())
    }

    @Test
    fun hashResultDoesNotRetainMutableSecretAndDoesNotModifyCaller() {
        val secret = ByteArray(32) { it.toByte() }
        val before = secret.copyOf()
        val hash = LocalAccessTokenHash.hashSecret(secret)
        assertContentEquals(before, secret)
        secret.fill(0xff.toByte())
        assertEquals(SEQUENCE_SECRET_HASH, hash.canonicalSha256())
        assertEquals(FF_SECRET_HASH, LocalAccessTokenHash.hashSecret(secret).canonicalSha256())
        assertEquals(SEQUENCE_SECRET_HASH, hash.canonicalSha256())
    }

    @Test
    fun hashValueEqualityAndDiagnosticsAreRedacted() {
        val first = LocalAccessTokenHash.fromCanonicalSha256(SEQUENCE_SECRET_HASH)
        val same = LocalAccessTokenHash.fromCanonicalSha256(SEQUENCE_SECRET_HASH)
        val other = LocalAccessTokenHash.fromCanonicalSha256(ZERO_SECRET_HASH)
        assertEquals(first, same)
        assertEquals(first.hashCode(), same.hashCode())
        assertNotEquals(first, other)
        assertFalse(first.equals(SEQUENCE_SECRET_HASH))
        assertFalse(first.equals(null))
        assertEquals("LocalAccessTokenHash([REDACTED])", first.toString())
        assertFalse(SEQUENCE_SECRET_HASH in first.toString())
    }

    @Test
    fun issuanceExposesOnlyFixedProfileAndDerivedDeadline() {
        val record = issuance()
        assertEquals(branch, record.scope)
        assertEquals(id(4), record.actorId)
        assertEquals(id(7), record.sessionId)
        assertEquals(id(6), record.deviceId)
        assertEquals(SessionGeneration(1), record.generation)
        assertEquals(Revision(0), record.credentialRevisionAtIssue)
        assertEquals(Revision(0), record.admissionRevisionAtIssue)
        assertEquals(issuerContext, record.issuerContextId)
        assertEquals(moment(100), record.issuedAt)
        assertEquals(50L, record.validForMillis)
        assertEquals(150L, record.expiresAtElapsedMillis)
        assertEquals(Revision(0), record.revision)
        assertEquals(AuthenticationPurpose.INTERACTIVE, record.authenticationPurpose)
        assertEquals(SEQUENCE_SECRET_HASH, record.tokenHash.canonicalSha256())
        assertEquals("LocalAccessIssuance([REDACTED])", record.toString())
        assertFalse(id(4).value in record.toString())
        assertFalse(SEQUENCE_SECRET_HASH in record.toString())
    }

    @Test
    fun issuanceGenerationRequiresOneThroughMaxMinusOne() {
        for (generation in listOf(1L, 2L, 9_007_199_254_740_993L, Long.MAX_VALUE - 1)) {
            assertEquals(SessionGeneration(generation), issuance(generation = generation).generation)
        }
        for (generation in listOf(0L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { issuance(generation = generation) }
        }
        // Issuance-specific bounds must not narrow the existing general/UI/session scalar.
        assertEquals(0L, SessionGeneration(0).value)
        assertEquals(Long.MAX_VALUE, SessionGeneration(Long.MAX_VALUE).value)
        assertFailsWith<IllegalArgumentException> { SessionGeneration(-1) }
    }

    @Test
    fun issuancePreservesZeroAndWideOriginalRevisions() {
        for (revision in listOf(0L, 1L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            val record = issuance(credentialRevisionAtIssue = revision, admissionRevisionAtIssue = revision)
            assertEquals(Revision(revision), record.credentialRevisionAtIssue)
            assertEquals(Revision(revision), record.admissionRevisionAtIssue)
            assertEquals(Revision(0), record.revision)
        }
    }

    @Test
    fun validityRejectsNonpositiveAndUnrepresentableDeadlines() {
        for (duration in listOf(Long.MIN_VALUE, -1L, 0L)) {
            assertFailsWith<IllegalArgumentException> { issuance(validForMillis = duration) }
        }
        for ((start, duration) in listOf(
            1L to Long.MAX_VALUE, 100L to Long.MAX_VALUE,
            (Long.MAX_VALUE - 1) to 2L, Long.MAX_VALUE to 1L,
        )) {
            assertFailsWith<IllegalArgumentException> { issuance(issuedAt = moment(start), validForMillis = duration) }
        }
        assertFailsWith<IllegalArgumentException> { moment(-1) }
    }

    @Test
    fun representableValidityHasNoArbitraryCeilingAndAllowsMaxExclusiveDeadline() {
        val cases = listOf(
            Triple(0L, 1L, 1L), Triple(100L, 1L, 101L), Triple(100L, 900_001L, 900_101L),
            Triple(0L, Long.MAX_VALUE, Long.MAX_VALUE),
            Triple(100L, Long.MAX_VALUE - 100, Long.MAX_VALUE),
            Triple(Long.MAX_VALUE - 1, 1L, Long.MAX_VALUE),
        )
        for ((start, duration, deadline) in cases) {
            val record = issuance(issuedAt = moment(start), validForMillis = duration)
            assertEquals(duration, record.validForMillis)
            assertEquals(deadline, record.expiresAtElapsedMillis)
        }
    }

    @Test
    fun issuanceBindingRequiresRevisionZeroWithoutNarrowingOldTables() {
        val key = ProtectedLocalRecordKey.SessionIssuance(branch, id(7))
        val binding = ProtectedLocalBinding(key, Revision(0))
        assertEquals(Revision(0), binding.revision)
        for (revision in listOf(1L, 9_007_199_254_740_993L, Long.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ProtectedLocalBinding(key, Revision(revision)) }
            assertFailsWith<IllegalArgumentException> { binding.copy(revision = Revision(revision)) }
            for (oldKey in oldKeys()) {
                assertEquals(Revision(revision), ProtectedLocalBinding(oldKey, Revision(revision)).revision)
            }
        }
    }

    @Test
    fun issuanceBindingUsesSessionIdentityAndNewBoundedTable() {
        val key = ProtectedLocalRecordKey.SessionIssuance(branch, id(4))
        assertEquals(branch, key.scope)
        assertEquals(id(4), key.sessionId)
        assertEquals(id(4), key.recordId)
        assertEquals(ProtectedLocalTable.SESSION_ISSUANCE, key.table)
        assertEquals("local_session_issuance", key.table.wireName)
        assertEquals(4096, key.table.maximumPlaintextBytes)
        val binding = ProtectedLocalBinding(key, Revision(0))
        assertEquals(1, binding.schemaVersion)
        binding.requirePlaintextSize(1)
        binding.requirePlaintextSize(4096)
        for (size in listOf(-1, 0, 4097)) {
            assertFailsWith<IllegalArgumentException> { binding.requirePlaintextSize(size) }
        }
        val storageKey = ProtectedStorageKeyReference(id(800))
        assertEquals(295, binding.aad(storageKey).size)
        assertEquals("eb031213c8a59b726739b0839244c77db92fa024fa202b463d4283daaa8e9e4c",
            sha256(binding.aad(storageKey)).value)
        assertEquals(4112, SealedLocalRecord(binding, storageKey, ByteArray(12), ByteArray(4112)).ciphertextAndTagSize)
        assertFailsWith<IllegalArgumentException> {
            SealedLocalRecord(binding, storageKey, ByteArray(12), ByteArray(4113))
        }
    }

    @Test
    fun oldAadBytesRemainPinnedForAllFourTablesAndBothThrottleDimensions() {
        // Literal digests independently derived with Python struct.pack('>I', ...)/hashlib for
        // the twelve original fields, fixture IDs and revision zero. No new hash helper is used.
        val expected = listOf(
            280 to "5918dfd8784af990851fa74e315a1b77aab9b4b74ab69f1e8e611e9ebac851aa",
            289 to "9f59a635a148b2096d59e3051137915b27a3fad6ed368ad0fb3f97ca494218c4",
            282 to "b58bcc60ea140c172a934f4e0c04231ce01837e7fe05a3f8bfcf46c064610f19",
            288 to "69691c5363c006af3f98d22577ac3fe2d0539ab7b1867e5d8f8ed888be19a52d",
            324 to "e77742a752d8a2cf58127679d1b72371982769afdb297e67b753258e849d97b7",
        )
        val keys = oldKeys()
        assertEquals(expected.size, keys.size)
        for ((key, oracle) in keys.zip(expected)) {
            val aad = ProtectedLocalBinding(key, Revision(0)).aad(ProtectedStorageKeyReference(id(800)))
            assertEquals(oracle.first, aad.size)
            assertEquals(oracle.second, sha256(aad).value)
        }
    }

    @Test
    fun issuanceAadSeparatesEveryScopeComponentSessionAndConfiguredKey() {
        val key = ProtectedLocalRecordKey.SessionIssuance(branch, id(7))
        val storageKey = ProtectedStorageKeyReference(id(800))
        val binding = ProtectedLocalBinding(key, Revision(0))
        val original = binding.aad(storageKey)
        val alternatives = otherScopes().map { key.copy(scope = it) } + key.copy(sessionId = id(17))
        for (alternative in alternatives) {
            assertFalse(original.contentEquals(ProtectedLocalBinding(alternative, Revision(0)).aad(storageKey)))
        }
        assertFalse(original.contentEquals(binding.aad(ProtectedStorageKeyReference(id(801)))))
        val disposable = binding.aad(storageKey)
        disposable.fill(0)
        assertContentEquals(original, binding.aad(storageKey))
    }

    @Test
    fun matchingCurrentFactsWithRevisionZeroAreEligibleOnlyAsFacts(): Unit = runTest {
        val principal = verifiedPrincipal()
        assertTrue(matches(principal))
        assertNull(TrustedPrincipalFactory(DenyAllPrincipalVerifier)
            .authenticateLocal(LocalGatewayCredentials(id(8), credential())))
        // Independent synthetic input records, NOT replacement of a protected immutable row.
        // A different well-formed stored hash cannot be reverified without a bearer here.
        val otherHash = LocalAccessTokenHash.fromCanonicalSha256("0".repeat(64))
        assertTrue(matches(principal, record = issuance(tokenHash = otherHash)))
    }

    @Test
    fun principalMutantMatrixCannotChangeAnyOriginalBindingOrPurpose(): Unit = runTest {
        val original = identity()
        assertTrue(matches(verifiedPrincipal(original)))
        val mutants = otherScopes().map { "scope $it" to original.copy(scope = it) } + listOf(
            "actor" to original.copy(actorId = id(14)),
            "session" to original.copy(sessionId = id(17)),
            "device" to original.copy(deviceId = id(16)),
            "zero generation" to original.copy(sessionGeneration = SessionGeneration(0)),
            "other generation" to original.copy(sessionGeneration = SessionGeneration(2)),
            "terminal generation" to original.copy(sessionGeneration = SessionGeneration(Long.MAX_VALUE)),
            "admission revision" to original.copy(admissionRevision = Revision(1)),
            "approval purpose" to original.copy(purpose = AuthenticationPurpose.PRIVILEGED_APPROVAL),
            "recovery purpose" to original.copy(purpose = AuthenticationPurpose.PREPARED_LOCAL_RECOVERY),
        )
        for ((name, mutant) in mutants) assertFalse(matches(verifiedPrincipal(mutant)), name)
        assertTrue(matches(verifiedPrincipal(original)))
    }

    @Test
    fun issuanceMutantMatrixCannotSubstituteAnotherOriginalIdentityOrRevision(): Unit = runTest {
        val principal = verifiedPrincipal()
        assertTrue(matches(principal))
        val mutants = otherScopes().map { "scope $it" to issuance(scope = it) } + listOf(
            "actor" to issuance(actorId = id(14)),
            "session" to issuance(sessionId = id(17)),
            "device" to issuance(deviceId = id(16)),
            "generation" to issuance(generation = 2),
            "credential revision" to issuance(credentialRevisionAtIssue = 1),
            "admission revision" to issuance(admissionRevisionAtIssue = 1),
            "issuer context" to issuance(issuerContextId = id(902)),
            "boot" to issuance(issuedAt = moment(100, id(903))),
        )
        for ((name, mutant) in mutants) assertFalse(matches(principal, record = mutant), name)
        assertTrue(matches(principal))
    }

    @Test
    fun accountMutantMatrixCannotSupplyOtherIdentityInactiveOrTerminalState(): Unit = runTest {
        val principal = verifiedPrincipal()
        assertTrue(matches(principal))
        val mutants = otherScopes().map { "scope $it" to account(scope = it) } + listOf(
            "actor" to account(actorId = id(14)),
            "disabled" to account(enabled = false),
            "missing session" to account(sessions = emptyList()),
            "initial empty generation" to account(generation = 0, sessions = emptyList()),
            "terminal revision" to account(revision = Long.MAX_VALUE),
            "terminal generation" to account(generation = Long.MAX_VALUE),
            "full terminal state" to account(enabled = false, revision = Long.MAX_VALUE, generation = Long.MAX_VALUE),
        )
        for ((name, mutant) in mutants) assertFalse(matches(principal, state = mutant), name)
        assertTrue(matches(principal))
    }

    @Test
    fun selectedSessionMutantMatrixCannotSubstituteSessionFieldsOrPurpose(): Unit = runTest {
        val principal = verifiedPrincipal()
        val original = session()
        val mutants = listOf(
            "session identity" to original.copy(sessionId = id(17)),
            "device" to original.copy(deviceId = id(16)),
            "zero generation" to original.copy(generation = SessionGeneration(0)),
            "other generation" to original.copy(generation = SessionGeneration(2)),
            "revoked" to original.copy(status = SessionStatus.REVOKED),
            "approval purpose" to original.copy(authenticationPurpose = AuthenticationPurpose.PRIVILEGED_APPROVAL),
            "recovery purpose" to original.copy(authenticationPurpose = AuthenticationPurpose.PREPARED_LOCAL_RECOVERY),
            "service purpose" to original.copy(authenticationPurpose = AuthenticationPurpose.SERVICE),
        )
        assertTrue(matches(principal, state = account(generation = 2)))
        for ((name, mutant) in mutants) {
            assertFalse(matches(principal, state = account(generation = 2, sessions = listOf(mutant))), name)
        }
    }

    @Test
    fun currentAdmissionMutantMatrixCannotChangeItsOriginalBindingOrStatus(): Unit = runTest {
        val principal = verifiedPrincipal()
        assertTrue(matches(principal))
        val mutants = otherScopes().map { "scope $it" to admission(scope = it) } + listOf(
            "device" to admission(deviceId = id(16)),
            "revision" to admission(revision = 1),
            "not admitted" to admission(admitted = false),
        )
        for ((name, mutant) in mutants) assertFalse(matches(principal, deviceAdmission = mutant), name)
        assertTrue(matches(principal))
    }

    @Test
    fun missingOrChangedCredentialMetadataDeniesWithoutPasswordMaterial(): Unit = runTest {
        val principal = verifiedPrincipal()
        val record = issuance(credentialRevisionAtIssue = 7)
        assertTrue(matches(principal, record = record, credentialRevision = 7))
        assertFalse(matches(principal, record = record, credentialRevision = 7, credentialPresent = false))
        for (revision in listOf(0L, 6L, 8L, Long.MAX_VALUE)) {
            assertFalse(matches(principal, record = record, credentialRevision = revision))
        }
        // A session-only account revision/generation advance never restamps original credentials.
        assertTrue(matches(principal, record = record, state = account(revision = 9, generation = 10), credentialRevision = 7))
        assertFalse(matches(principal, record = record, state = account(revision = 9, generation = 10), credentialRevision = 8))
    }

    @Test
    fun reAdmissionCannotRestampOldIssuanceThroughNewPrincipal(): Unit = runTest {
        val oldPrincipal = verifiedPrincipal(identity(admissionRevision = 7))
        val record = issuance(admissionRevisionAtIssue = 7)
        assertTrue(matches(oldPrincipal, record = record, deviceAdmission = admission(revision = 7)))
        assertFalse(matches(oldPrincipal, record = record, deviceAdmission = admission(revision = 8)))
        val restampedPrincipal = verifiedPrincipal(identity(admissionRevision = 8))
        assertFalse(matches(restampedPrincipal, record = record, deviceAdmission = admission(revision = 8)))
        assertFalse(matches(restampedPrincipal, record = record, deviceAdmission = admission(revision = 7)))
        assertEquals(Revision(7), record.admissionRevisionAtIssue)
    }

    @Test
    fun concurrentSessionsUseIndividualGenerationsNotAllocationCounter(): Unit = runTest {
        val first = session()
        val second = session(sessionId = id(17), deviceId = id(16), generation = 2)
        val state = account(generation = 2, sessions = listOf(first, second))
        val firstPrincipal = verifiedPrincipal()
        val secondPrincipal = verifiedPrincipal(identity(sessionId = id(17), deviceId = id(16), generation = 2))
        val secondIssuance = issuance(sessionId = id(17), deviceId = id(16), generation = 2)
        assertTrue(matches(firstPrincipal, state = state))
        assertTrue(matches(secondPrincipal, record = secondIssuance, state = state, deviceAdmission = admission(deviceId = id(16))))
        assertFalse(matches(verifiedPrincipal(identity(generation = 2)), state = state))
        assertFalse(matches(firstPrincipal, record = issuance(generation = 2), state = state))
        val revokedSecond = assertIs<SessionTransition.Applied>(LocalSessions.revoke(state, id(17))).state
        assertEquals(SessionGeneration(3), revokedSecond.generation)
        assertTrue(matches(firstPrincipal, state = revokedSecond))
        assertFalse(matches(secondPrincipal, record = secondIssuance, state = revokedSecond,
            deviceAdmission = admission(deviceId = id(16))))
    }

    @Test
    fun maxMinusOneSessionAndAccountCountersRemainEligible(): Unit = runTest {
        val generation = Long.MAX_VALUE - 1
        val principal = verifiedPrincipal(identity(generation = generation))
        val record = issuance(generation = generation)
        val state = account(revision = generation, generation = generation, sessions = listOf(session(generation = generation)))
        assertTrue(matches(principal, record = record, state = state))
        val terminal = assertIs<SessionTransition.Applied>(LocalSessions.revoke(state, id(7))).state
        assertEquals(Revision(Long.MAX_VALUE), terminal.revision)
        assertEquals(SessionGeneration(Long.MAX_VALUE), terminal.generation)
        assertFalse(matches(principal, record = record, state = terminal))
    }

    @Test
    fun revokeAndExplicitMoveCannotReviveOldIssuance(): Unit = runTest {
        val principal = verifiedPrincipal()
        val record = issuance()
        val original = account()
        assertTrue(matches(principal, record = record, state = original))
        val revoked = assertIs<SessionTransition.Applied>(LocalSessions.revoke(original, id(7))).state
        assertFalse(matches(principal, record = record, state = revoked))
        val moved = assertIs<SessionTransition.Applied>(LocalSessions.signIn(original, id(17), id(16), explicitDeviceMove = true)).state
        assertFalse(matches(principal, record = record, state = moved))
        val disabled = assertIs<SessionTransition.Applied>(LocalSessions.disable(original)).state
        assertFalse(matches(principal, record = record, state = disabled))
        assertEquals(SessionGeneration(1), record.generation)
        assertEquals(150L, record.expiresAtElapsedMillis)
    }

    @Test
    fun all256HistoryEntriesCanRetainTheirSelectedIndividualSession(): Unit = runTest {
        val sessions = listOf(session()) + List(255) { index ->
            session(sessionId = id(1_000 + index), generation = index.toLong() + 2, status = SessionStatus.REVOKED)
        }
        val state = account(generation = 256, sessions = sessions)
        assertEquals(256, state.sessions.size)
        assertTrue(matches(verifiedPrincipal(), state = state))
        assertEquals(sessions, state.sessions)
    }

    @Test
    fun exactIssueAndExclusiveExpiryBoundaries(): Unit = runTest {
        val principal = verifiedPrincipal()
        val record = issuance(issuedAt = moment(100), validForMillis = 50)
        for ((elapsed, expected) in listOf(0L to false, 99L to false, 100L to true, 149L to true, 150L to false, 151L to false)) {
            assertEquals(expected, matches(principal, record = record, now = moment(elapsed)), "elapsed=$elapsed")
        }
        val minimum = issuance(issuedAt = moment(0), validForMillis = 1)
        assertTrue(matches(principal, record = minimum, now = moment(0)))
        assertFalse(matches(principal, record = minimum, now = moment(1)))
    }

    @Test
    fun maxExclusiveDeadlineNeverWrapsOrBecomesNoExpiry(): Unit = runTest {
        val principal = verifiedPrincipal()
        val maximum = issuance(issuedAt = moment(0), validForMillis = Long.MAX_VALUE)
        assertTrue(matches(principal, record = maximum, now = moment(Long.MAX_VALUE - 1)))
        assertFalse(matches(principal, record = maximum, now = moment(Long.MAX_VALUE)))
        val lastMillisecond = issuance(issuedAt = moment(Long.MAX_VALUE - 1), validForMillis = 1)
        assertFalse(matches(principal, record = lastMillisecond, now = moment(Long.MAX_VALUE - 2)))
        assertTrue(matches(principal, record = lastMillisecond, now = moment(Long.MAX_VALUE - 1)))
        assertFalse(matches(principal, record = lastMillisecond, now = moment(Long.MAX_VALUE)))
    }

    @Test
    fun issuerContextAndBootAreSeparateExactBindings(): Unit = runTest {
        val principal = verifiedPrincipal()
        val record = issuance()
        assertNotEquals(record.issuerContextId, record.issuedAt.bootId)
        assertTrue(matches(principal, record = record))
        assertFalse(matches(principal, record = record, currentIssuerContextId = id(902)))
        assertFalse(matches(principal, record = record, currentIssuerContextId = record.issuedAt.bootId))
        assertFalse(matches(principal, record = record, now = moment(120, id(903))))
        // Neither constructor nor equality can prove qualification or that two IDs are distinct
        // real-world lifetimes. Equal supplied IDs are syntactically valid, not such proof.
        val equalIds = issuance(issuerContextId = boot)
        assertTrue(matches(principal, record = equalIds, currentIssuerContextId = boot))
    }

    @Test
    fun predicateIsStatelessAndDoesNotQualifyClockContinuity(): Unit = runTest {
        val principal = verifiedPrincipal()
        val record = issuance()
        assertFalse(matches(principal, record = record, now = moment(150)))
        // Deliberately only factual range comparison. A DB source MUST independently reject
        // regressing trusted observations; this predicate provides no cross-call high-water.
        assertTrue(matches(principal, record = record, now = moment(120)))
        assertFalse(matches(principal, record = record, currentIssuerContextId = id(902)))
        assertTrue(matches(principal, record = record))
    }

    @Test
    fun validFactsDoNotSupplyPolicyGrantsOrPreserveOldAllow(): Unit = runTest {
        val principal = verifiedPrincipal()
        val record = issuance()
        val needed = requirement()
        assertTrue(matches(principal, record = record))
        assertEquals(AuthorizationDecision.Denied(AuthorizationDenial.PERMISSION_DENIED),
            AuthorizationEvaluator.evaluate(principal, snapshot(principal), needed))
        val allowed = assertIs<AuthorizationDecision.Allowed>(AuthorizationEvaluator.evaluate(principal,
            snapshot(principal, listOf(grant(needed)), policyRevision = Revision(4)), needed))
        assertEquals(Revision(4), allowed.authorization.policyRevision)
        assertEquals(AuthorizationDecision.Denied(AuthorizationDenial.EXPLICIT_DENY),
            AuthorizationEvaluator.evaluate(principal,
                snapshot(principal, listOf(grant(needed), grant(needed, GrantEffect.DENY)), policyRevision = Revision(5)), needed))
        assertTrue(matches(principal, record = record))
        assertEquals(SEQUENCE_SECRET_HASH, record.tokenHash.canonicalSha256())
    }

    private fun issuance(
        scope: BranchScope = branch,
        actorId: DomainId = id(4),
        sessionId: DomainId = id(7),
        deviceId: DomainId = id(6),
        generation: Long = 1,
        credentialRevisionAtIssue: Long = 0,
        admissionRevisionAtIssue: Long = 0,
        issuerContextId: DomainId = issuerContext,
        issuedAt: MonotonicMoment = moment(100),
        validForMillis: Long = 50,
        tokenHash: LocalAccessTokenHash = LocalAccessTokenHash.fromCanonicalSha256(SEQUENCE_SECRET_HASH),
    ): LocalAccessIssuance = LocalAccessIssuance(scope, actorId, sessionId, deviceId, SessionGeneration(generation),
        Revision(credentialRevisionAtIssue), Revision(admissionRevisionAtIssue), issuerContextId, issuedAt, validForMillis, tokenHash)

    private fun identity(
        scope: BranchScope = branch,
        actorId: DomainId = id(4),
        sessionId: DomainId = id(7),
        deviceId: DomainId = id(6),
        generation: Long = 1,
        admissionRevision: Long = 0,
    ): VerifiedLocalIdentity = VerifiedLocalIdentity(actorId, scope, deviceId, sessionId, SessionGeneration(generation),
        Revision(admissionRevision), AuthenticationPurpose.INTERACTIVE)

    private suspend fun verifiedPrincipal(value: VerifiedLocalIdentity = identity()): LocalEmployeePrincipal =
        checkNotNull(TrustedPrincipalFactory(FixtureVerifier(local = value))
            .authenticateLocal(LocalGatewayCredentials(id(8), credential())))

    private fun session(
        scope: BranchScope = branch,
        actorId: DomainId = id(4),
        sessionId: DomainId = id(7),
        deviceId: DomainId = id(6),
        generation: Long = 1,
        status: SessionStatus = SessionStatus.ACTIVE,
    ): LocalSessionRecord = LocalSessionRecord(sessionId, scope, actorId, deviceId, SessionGeneration(generation), status,
        AuthenticationPurpose.INTERACTIVE)

    private fun account(
        scope: BranchScope = branch,
        actorId: DomainId = id(4),
        enabled: Boolean = true,
        revision: Long = 0,
        generation: Long = 1,
        sessions: List<LocalSessionRecord> = listOf(session(scope = scope, actorId = actorId,
            status = if (enabled) SessionStatus.ACTIVE else SessionStatus.REVOKED)),
    ): LocalAccountSessionState = LocalAccountSessionState(scope, actorId, enabled, Revision(revision), SessionGeneration(generation), sessions)

    private fun admission(
        scope: BranchScope = branch,
        deviceId: DomainId = id(6),
        revision: Long = 0,
        admitted: Boolean = true,
    ): LocalDeviceAdmission = LocalDeviceAdmission(scope, deviceId, Revision(revision), admitted,
        "SYNTHETIC-PUBLIC-KEY", null, emptySet())

    private fun matches(
        principal: LocalEmployeePrincipal,
        record: LocalAccessIssuance = issuance(),
        state: LocalAccountSessionState = account(),
        credentialRevision: Long = 0,
        credentialPresent: Boolean = true,
        deviceAdmission: LocalDeviceAdmission = admission(),
        currentIssuerContextId: DomainId = issuerContext,
        now: MonotonicMoment = moment(120),
    ): Boolean = LocalAccessEligibility.matchesCurrentFacts(principal, record, state, Revision(credentialRevision),
        credentialPresent, deviceAdmission, currentIssuerContextId, now)

    private fun otherScopes(): List<BranchScope> = listOf(
        branch.copy(organizationId = id(11)), branch.copy(companyId = id(12)), branch.copy(branchId = id(13)),
    )

    private fun oldKeys(): List<ProtectedLocalRecordKey> = listOf(
        ProtectedLocalRecordKey.Account(branch, id(4)), ProtectedLocalRecordKey.EffectivePolicy(branch, id(4)),
        ProtectedLocalRecordKey.Admission(branch, id(4)),
        ProtectedLocalRecordKey.Throttle(ThrottleKey(branch, id(4), ThrottleDimension.Account)),
        ProtectedLocalRecordKey.Throttle(ThrottleKey(branch, id(4), ThrottleDimension.Device(id(6)))),
    )

    private companion object {
        const val ZERO_SECRET_HASH = "46919d4e1d2b37b7388c1d8b63121d91e009d1f90885ebeb6423f7ff39eaa00c"
        const val SEQUENCE_SECRET_HASH = "208bd4c0ca7941f488f90770a84f24472cf47d0e2b980f400b26ba718b32a0bb"
        const val FF_SECRET_HASH = "7dd6c2c62cfe03aa9d6fccafba03caede5aa6367b6bf5001984f45f85a2eed91"
    }
}
