package com.ukkera.pos.security

import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/** Historical regression fixture composition only; returns each platform's production wrapper. */
internal expect fun localPasswordRegressionHasher(
    pepper: DeviceProtectedPepper,
    deterministicSalt: Boolean = false,
): LocalPasswordHasher

/** Real platform HMAC with the historical fabricated key; never OS qualification evidence. */
internal expect fun localPasswordRegressionHmacSha256(message: ByteArray): ByteArray

/**
 * All values here are fabricated fixtures. Reporting the qualified enum is solely exercising
 * the interface gate; these synthetic in-memory keys are NOT OS providers or qualification evidence.
 * Relocated historical ordinary regressions, not newly independently designed acceptance.
 */
private class SyntheticPepper : DeviceProtectedPepper {
    override var protectionStatus: KeyProtectionStatus = KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT
    var unavailable: Boolean = false
    var calls: Int = 0

    override suspend fun hmacSha256(reference: ProtectedPepperReference, message: ByteArray): PepperMacResult {
        calls++
        if (unavailable) return PepperMacResult.Unavailable(ProtectedKeyFailure.KEY_LOCKED)
        return PepperMacResult.Authenticated(localPasswordRegressionHmacSha256(message))
    }
}

class LocalPasswordHasherPortableRegressionTest {
    private val binding = CredentialBinding(branch, id(4), Revision(1))
    private val key = ProtectedPepperReference(id(70))
    private fun password(): CharArray = "synthetic-only-4872".toCharArray()

    @Test
    fun reviewedProvidersMatchIndependentPythonPbkdf2HmacKnownAnswer(): Unit = runTest {
        val hasher = localPasswordRegressionHasher(SyntheticPepper(), deterministicSalt = true)
        val created = assertIs<PasswordHashingResult.Created>(hasher.create(password(), binding, key)).record
        val actual = created.copyVerifier().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        // Independently computed with Python hashlib.pbkdf2_hmac + hmac.new for this synthetic fixture.
        assertEquals("f420faa178c973f0b3b6b504745f976b4f1402481bfbba58cfe439604ca45e2d", actual)
        assertContentEquals(ByteArray(16) { (it + 1).toByte() }, created.copySalt())
        assertEquals(PasswordVerificationResult.Match(false), hasher.verify(password(), binding, created))
        assertEquals(PasswordVerificationResult.Mismatch, hasher.verify("synthetic-only-4873".toCharArray(), binding, created))
    }

    @Test
    fun productionRandomSaltMakesEqualPasswordsHaveDifferentVerifiers(): Unit = runTest {
        val hasher = localPasswordRegressionHasher(SyntheticPepper())
        val first = assertIs<PasswordHashingResult.Created>(hasher.create(password(), binding, key)).record
        val second = assertIs<PasswordHashingResult.Created>(hasher.create(password(), binding, key)).record
        assertTrue(!first.copySalt().contentEquals(second.copySalt()))
        assertTrue(!first.copyVerifier().contentEquals(second.copyVerifier()))
    }

    @Test
    fun copyingVerifierIntoAnotherAccountDoesNotTransferTheCredential(): Unit = runTest {
        val hasher = localPasswordRegressionHasher(SyntheticPepper(), deterministicSalt = true)
        val original = assertIs<PasswordHashingResult.Created>(hasher.create(password(), binding, key)).record
        val otherBinding = binding.copy(actorId = id(5))
        assertEquals(
            PasswordVerificationResult.Unavailable(PasswordFailure.WRONG_CREDENTIAL_BINDING),
            hasher.verify(password(), otherBinding, original),
        )
        val transplanted = PasswordHashRecord(
            otherBinding, original.parameters, original.pepperReference, original.copySalt(), original.copyVerifier(),
        )
        assertEquals(PasswordVerificationResult.Mismatch, hasher.verify(password(), otherBinding, transplanted))
    }

    @Test
    fun unsupportedOrUnqualifiedOsKeyContextNeverFallsBackToPlainPbkdf2OrMemoryKey(): Unit = runTest {
        val hasher = localPasswordRegressionHasher(UnavailableDeviceProtectedPepper)
        assertEquals(
            PasswordHashingResult.Unavailable(PasswordFailure.KEY_CONTEXT_NOT_QUALIFIED),
            hasher.create(password(), binding, key),
        )
        val synthetic = SyntheticPepper().also { it.protectionStatus = KeyProtectionStatus.UNQUALIFIED_CONTEXT }
        assertIs<PasswordHashingResult.Unavailable>(localPasswordRegressionHasher(synthetic).create(password(), binding, key))
        assertEquals(0, synthetic.calls)
    }

    @Test
    fun keyLockOrLossPreservesRecordAndDoesNotBecomeMismatchOrSuccess(): Unit = runTest {
        val pepper = SyntheticPepper()
        val hasher = localPasswordRegressionHasher(pepper)
        val record = assertIs<PasswordHashingResult.Created>(hasher.create(password(), binding, key)).record
        val before = record.copyVerifier()
        pepper.unavailable = true
        assertEquals(
            PasswordVerificationResult.Unavailable(PasswordFailure.KEY_UNAVAILABLE),
            hasher.verify(password(), binding, record),
        )
        assertContentEquals(before, record.copyVerifier())
    }

    @Test
    fun passwordIsNotTrimmedNormalizedOrNullTerminated(): Unit = runTest {
        val hasher = localPasswordRegressionHasher(SyntheticPepper())
        val chars = " synthetic-\u0000-مرحبا ".toCharArray()
        val record = assertIs<PasswordHashingResult.Created>(hasher.create(chars, binding, key)).record
        assertEquals(PasswordVerificationResult.Match(false), hasher.verify(chars, binding, record))
        assertEquals(
            PasswordVerificationResult.Mismatch,
            hasher.verify("synthetic-\u0000-مرحبا".toCharArray(), binding, record),
        )
    }

    @Test
    fun boundsAndRedactionAreEnforcedBeforeCryptographicWork(): Unit = runTest {
        assertFailsWith<IllegalArgumentException> { PasswordHashParameters(599_999) }
        assertFailsWith<IllegalArgumentException> { PasswordHashParameters(5_000_001) }
        val pepper = SyntheticPepper()
        val hasher = localPasswordRegressionHasher(pepper)
        for (value in listOf(CharArray(3), CharArray(1025))) {
            assertEquals(PasswordHashingResult.Unavailable(PasswordFailure.INVALID_INPUT), hasher.create(value, binding, key))
        }
        assertEquals(0, pepper.calls)
        val salt = ByteArray(16) { 9 }
        val verifier = ByteArray(32) { 8 }
        val record = PasswordHashRecord(binding, PasswordHashParameters(), key, salt, verifier)
        salt.fill(0)
        verifier.fill(0)
        assertTrue(record.copySalt().all { it == 9.toByte() })
        assertTrue(record.copyVerifier().all { it == 8.toByte() })
        assertEquals("PasswordHashRecord([REDACTED])", record.toString())
        assertTrue(record.toString().contains("REDACTED"))
        assertNotEquals(record.copyVerifier().contentToString(), record.toString())
    }

    @Test
    fun expensiveVerifierHasNoUnboundedWaitingPasswordQueue(): Unit = runTest {
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delegate = SyntheticPepper()
        var calls = 0
        val gated = object : DeviceProtectedPepper {
            override val protectionStatus = KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT
            override suspend fun hmacSha256(reference: ProtectedPepperReference, message: ByteArray): PepperMacResult {
                calls++
                if (calls == 2) ready.complete(Unit)
                release.await()
                return delegate.hmacSha256(reference, message)
            }
        }
        val hasher = localPasswordRegressionHasher(gated)
        val active = List(2) { async { hasher.create(password(), binding, key) } }
        ready.await()
        try {
            assertEquals(
                PasswordHashingResult.Unavailable(PasswordFailure.HASHER_BUSY),
                hasher.create(password(), binding, key),
            )
            assertEquals(2, calls)
        } finally {
            release.complete(Unit)
        }
        active.forEach { assertIs<PasswordHashingResult.Created>(it.await()) }
    }
}
