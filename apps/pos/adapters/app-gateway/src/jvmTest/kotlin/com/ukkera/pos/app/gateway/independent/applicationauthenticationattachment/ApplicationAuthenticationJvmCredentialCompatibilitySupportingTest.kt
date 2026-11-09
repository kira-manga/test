package com.ukkera.pos.app.gateway.independent.applicationauthenticationattachment

import com.ukkera.pos.app.gateway.LocalClientAuthenticationBinding
import com.ukkera.pos.app.gateway.StoredClientCredentials
import com.ukkera.pos.app.gateway.StoredSessionPhaseV1
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unchanged APP-AUTH-030 history preservation and APP-AUTH-002 binding distinction,
 * exercised through actual JVM file/AEAD writes and freshly reopened reader instances.
 * Synthetic AES/custody and issued-token-shaped carriers remain supporting DATA only.
 * Current writer-to-reader round trips are not independent historical binary goldens,
 * native admission/rollback/RECOVER proof or automatic-session-restoration evidence.
 */
class ApplicationAuthenticationJvmCredentialCompatibilitySupportingTest {
    @Test
    fun appAuth030V1FileReopenPreservesCapturedCiphertextAndOriginalHistory() = legacyReopen(1)

    @Test
    fun appAuth030V2FileReopenPreservesCapturedCiphertextAndOriginalHistory() = legacyReopen(2)

    @Test
    fun appAuth002And030V3FileReopenPreservesDistinctBindingsWithEqualScalarIds() = runTest(timeout = 10.seconds) {
        SyntheticJvmCredentialCompatibilityFixture.use { fixture ->
            val revision = fixture.inspect { it.revision }
            val historical = syntheticLegacyHistory(revision + 1, 3, AppAuthenticationFrozen.connectionId)
            try {
                val remoteSession = syntheticRemoteSession()
                try {
                    // Mixed borrows secrets from these two enclosing owners; both are destroyed.
                    val mixed = StoredClientCredentials(historical.revision,
                        historical.attempts + syntheticRemoteAttempt(), historical.sessions + remoteSession,
                        historical.enrollments, historical.administrations, 3)
                    fixture.write(revision, mixed)
                    val encrypted = fixture.ciphertext()
                    try {
                        fixture.reopen()
                        assertUnchangedCiphertext(encrypted, fixture)
                        fixture.inspect { restored ->
                            assertCredentialHistory(mixed, restored)
                            val old = assertIs<LocalClientAuthenticationBinding.MasterServiceIncarnation>(restored.attempts.first().binding)
                            val remote = assertIs<LocalClientAuthenticationBinding.RemoteOriginalAttachment>(restored.attempts.last().binding)
                            assertEquals(old.serviceIncarnationId, remote.connectionId)
                            assertNotEquals<LocalClientAuthenticationBinding>(old, remote)
                            assertEquals(old.serviceIncarnationId, restored.attempts.first().serviceIncarnationId)
                            assertFails { restored.attempts.last().serviceIncarnationId }
                            assertEquals(old, restored.sessions.first().binding)
                            assertEquals(remote, restored.sessions.last().binding)
                            assertFails { restored.sessions.last().serviceIncarnationId }
                        }
                    } finally { encrypted.fill(0) }
                } finally { remoteSession.destroy() }
            } finally { historical.destroy() }
        }
    }

    @Test
    fun appAuth030V1ToV3FileUpdatePreservesOriginalPendingHistoryAndCredentialCarriers() = promotion(1)

    @Test
    fun appAuth030V2ToV3FileUpdatePreservesOriginalPendingHistoryAndCredentialCarriers() = promotion(2)

    private fun legacyReopen(profile: Int) = runTest(timeout = 10.seconds) {
        SyntheticJvmCredentialCompatibilityFixture.use { fixture ->
            val revision = fixture.inspect { it.revision }
            val original = syntheticLegacyHistory(revision + 1, profile)
            try {
                fixture.write(revision, original)
                fixture.inspect { assertCredentialHistory(original, it) }
                val encrypted = fixture.ciphertext()
                try {
                    fixture.reopen()
                    assertUnchangedCiphertext(encrypted, fixture)
                    fixture.inspect { restored ->
                        assertCredentialHistory(original, restored)
                        assertIs<LocalClientAuthenticationBinding.MasterServiceIncarnation>(restored.attempts.single().binding)
                        assertEquals(original.attempts.single().serviceIncarnationId, restored.attempts.single().serviceIncarnationId)
                        assertEquals(StoredSessionPhaseV1.REFRESH_PENDING, restored.sessions.single().phase)
                        assertTrue(restored.sessions.single().originalRevoked)
                    }
                } finally { encrypted.fill(0) }
            } finally { original.destroy() }
        }
    }

    private fun promotion(profile: Int) = runTest(timeout = 10.seconds) {
        SyntheticJvmCredentialCompatibilityFixture.use { fixture ->
            val revision = fixture.inspect { it.revision }
            val original = syntheticLegacyHistory(revision + 1, profile)
            try {
                fixture.write(revision, original)
                fixture.reopen()
                val retained = fixture.read()
                try {
                    assertCredentialHistory(original, retained)
                    val addedAttempt = syntheticRemoteAttempt()
                    val addedSession = syntheticRemoteSession()
                    try {
                        // Promoted borrows retained/added secrets; their enclosing owners dispose them.
                        val promoted = retained.next(attempts = retained.attempts + addedAttempt,
                            sessions = retained.sessions + addedSession)
                        fixture.write(retained.revision, promoted)
                        val encrypted = fixture.ciphertext()
                        try {
                            fixture.reopen()
                            assertUnchangedCiphertext(encrypted, fixture)
                            fixture.inspect { restored ->
                                assertEquals(3, restored.formatVersion)
                                assertEquals(promoted.revision, restored.revision)
                                assertEquals(original.attempts + addedAttempt, restored.attempts)
                                assertEquals(original.enrollments, restored.enrollments)
                                assertEquals(original.administrations, restored.administrations)
                                assertEquals(original.sessions.size + 1, restored.sessions.size)
                                assertCredentialSession(original.sessions.single(), restored.sessions.first())
                                assertCredentialSession(addedSession, restored.sessions.last())
                                assertEquals(StoredSessionPhaseV1.REFRESH_PENDING, restored.sessions.first().phase)
                                assertTrue(restored.sessions.first().originalRevoked)
                                assertEquals(original.attempts.single().serviceIncarnationId,
                                    restored.attempts.first().serviceIncarnationId)
                                assertFails { restored.attempts.last().serviceIncarnationId }
                                assertFails { restored.sessions.last().serviceIncarnationId }
                            }
                        } finally { encrypted.fill(0) }
                    } finally { addedSession.destroy() }
                } finally { retained.destroy() }
            } finally { original.destroy() }
        }
    }
}

private fun assertUnchangedCiphertext(expected: ByteArray, fixture: SyntheticJvmCredentialCompatibilityFixture) {
    val current = fixture.ciphertext()
    try { assertTrue(expected.contentEquals(current), "Existing file reopen rewrote captured ciphertext") }
    finally { current.fill(0) }
}
