package com.ukkera.pos.app.gateway.independent.applicationauthenticationattachment

import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.JvmClientCredentialCustody
import com.ukkera.pos.app.gateway.JvmClientCredentialCustodyView
import com.ukkera.pos.app.gateway.JvmClientCredentialJournalOpenResult
import com.ukkera.pos.app.gateway.JvmEncryptedClientCredentialJournal
import com.ukkera.pos.app.gateway.LocalClientAuthenticationBinding
import com.ukkera.pos.app.gateway.StoredClientCredentials
import com.ukkera.pos.app.gateway.StoredClientSessionV1
import com.ukkera.pos.app.gateway.StoredLoginAttemptV1
import com.ukkera.pos.app.gateway.StoredSessionPhaseV1
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Actual JVM file/AEAD integration with explicitly synthetic AES and custody DATA.
 * No OS key isolation, native approval, allocation, rollback or recovery fence is supplied.
 * Profile numbers select existing codec variants, not new frozen acceptance parameters.
 */
internal class SyntheticJvmCredentialCompatibilityFixture private constructor(
    private val directory: Path,
    private val key: SecretKey,
) {
    private val journalId = AppAuthenticationFrozen.slotId("supporting-file-journal")
    private val keyId = AppAuthenticationFrozen.slotId("supporting-aes-key")
    private val processContextId = AppAuthenticationFrozen.slotId("supporting-file-context")
    private var live = true
    private var opened: JvmEncryptedClientCredentialJournal? = null
    private val custody = JvmClientCredentialCustody {
        // A fresh carrier preserves the same synthetic key/coordinates; this is not native proof.
        JvmClientCredentialCustodyView(directory, AppAuthenticationFrozen.owner, journalId, keyId,
            processContextId, key) { live }
    }
    private val journal: JvmEncryptedClientCredentialJournal get() = checkNotNull(opened)

    suspend fun read(): StoredClientCredentials =
        assertIs<JournalRead.Value<StoredClientCredentials>>(journal.read()).value

    suspend fun write(expectedRevision: Long, next: StoredClientCredentials) {
        assertEquals(JournalWrite.DURABLE, journal.replace(expectedRevision, next))
    }

    /** Read bytes only inside the disposable fixture; never export or print custody records. */
    fun ciphertext(): ByteArray = Files.readAllBytes(directory.resolve("sessions.v1.enc"))

    suspend fun reopen() {
        closeCurrent()
        opened = assertIs<JvmClientCredentialJournalOpenResult.Opened>(
            JvmEncryptedClientCredentialJournal.openExisting(custody),
        ).journal
    }

    private suspend fun closeCurrent() {
        val original = opened ?: return
        opened = null // Each owned journal is closed once; no unknown-close retry is scripted.
        original.close()
    }

    private suspend fun dispose() = withContext(NonCancellable + Dispatchers.IO) {
        try { closeCurrent() }
        finally {
            live = false
            // This fixture creates only files in its one uniquely owned disposable directory.
            removeDisposableDirectory(directory)
        }
    }

    companion object {
        suspend fun use(block: suspend (SyntheticJvmCredentialCompatibilityFixture) -> Unit) {
            val directory = Files.createTempDirectory("app-auth-aead-compat-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                .toAbsolutePath().normalize()
            var fixture: SyntheticJvmCredentialCompatibilityFixture? = null
            try {
                val bytes = ByteArray(32)
                val initialized = try {
                    SecureRandom().nextBytes(bytes)
                    SyntheticJvmCredentialCompatibilityFixture(directory, SecretKeySpec(bytes, "AES"))
                } finally { bytes.fill(0) }
                fixture = initialized
                // Explicit synthetic fixture initialization, never genuine native ENROLL evidence.
                initialized.opened = assertIs<JvmClientCredentialJournalOpenResult.Opened>(
                    JvmEncryptedClientCredentialJournal.createNew(initialized.custody),
                ).journal
                block(initialized)
            } finally {
                val original = fixture
                if (original != null) original.dispose()
                else withContext(NonCancellable + Dispatchers.IO) { removeDisposableDirectory(directory) }
            }
        }
    }
}

private fun removeDisposableDirectory(directory: Path) {
    Files.newDirectoryStream(directory).use { entries ->
        for (entry in entries) Files.deleteIfExists(entry)
    }
    Files.deleteIfExists(directory)
}

/** A captured pending-history carrier, not PENDING_D1_WRITE native-fence evidence. */
internal fun syntheticLegacyHistory(
    revision: Long,
    profile: Int,
    incarnation: DomainId = AppAuthenticationFrozen.slotId("MASTER_M1:historical-incarnation-carrier"),
): StoredClientCredentials {
    val binding = LocalClientAuthenticationBinding.MasterServiceIncarnation(incarnation)
    return StoredClientCredentials(revision,
        attempts = listOf(StoredLoginAttemptV1(AppAuthenticationFrozen.exchange("LOGIN_1"),
            AppAuthenticationFrozen.login, binding, false)),
        sessions = listOf(syntheticCredentialSession(binding, AppAuthenticationFrozen.view(),
            AppAuthenticationFrozen.slotId("S1:retained-local-record"), StoredSessionPhaseV1.REFRESH_PENDING, true)),
        formatVersion = profile)
}

internal fun syntheticRemoteAttempt(): StoredLoginAttemptV1 =
    StoredLoginAttemptV1(AppAuthenticationFrozen.exchange("LOGIN_RELOGIN"), AppAuthenticationFrozen.login,
        LocalClientAuthenticationBinding.RemoteOriginalAttachment(AppAuthenticationFrozen.connectionId), false)

internal fun syntheticRemoteSession(): StoredClientSessionV1 =
    syntheticCredentialSession(LocalClientAuthenticationBinding.RemoteOriginalAttachment(AppAuthenticationFrozen.connectionId),
        AppAuthenticationFrozen.successorView(), AppAuthenticationFrozen.slotId("S2:retained-local-record"),
        StoredSessionPhaseV1.RETAINED, false)

private fun syntheticCredentialSession(binding: LocalClientAuthenticationBinding, view: LocalSessionView,
    localId: DomainId, phase: StoredSessionPhaseV1, originalRevoked: Boolean): StoredClientSessionV1 {
    val tokens = AppAuthenticationFrozen.tokens(view)
    try {
        val captured = StoredClientSessionV1.capture(localId, binding, tokens)
        // Transfer the captured snapshot's owned copies to its selected pending/retained row.
        return captured.phase(phase, originalRevoked)
    } finally { tokens.destroy() }
}

internal fun assertCredentialHistory(expected: StoredClientCredentials, actual: StoredClientCredentials) {
    assertEquals(expected.revision, actual.revision)
    assertEquals(expected.formatVersion, actual.formatVersion)
    assertEquals(expected.attempts, actual.attempts)
    assertEquals(expected.enrollments, actual.enrollments)
    assertEquals(expected.administrations, actual.administrations)
    assertEquals(expected.sessions.size, actual.sessions.size)
    expected.sessions.zip(actual.sessions).forEach { (left, right) -> assertCredentialSession(left, right) }
}

internal fun assertCredentialSession(expected: StoredClientSessionV1, actual: StoredClientSessionV1) {
    assertEquals(expected.localId, actual.localId)
    assertEquals(expected.identity, actual.identity)
    assertEquals(expected.login, actual.login)
    assertEquals(expected.binding, actual.binding)
    assertEquals(expected.sessionId, actual.sessionId)
    assertEquals(expected.generation, actual.generation)
    assertEquals(expected.accessExpiresAt, actual.accessExpiresAt)
    assertEquals(expected.workExpiresAt, actual.workExpiresAt)
    assertEquals(expected.phase, actual.phase)
    assertEquals(expected.originalRevoked, actual.originalRevoked)
    assertCredentialCarrier(expected.access, actual.access)
    assertCredentialCarrier(expected.refresh, actual.refresh)
}

private fun assertCredentialCarrier(expected: OpaqueCredential, actual: OpaqueCredential) {
    expected.useBytes { left -> actual.useBytes { right ->
        assertTrue(left.contentEquals(right), "Retained syntax-only credential carrier changed")
    } }
}

internal suspend fun <T> SyntheticJvmCredentialCompatibilityFixture.inspect(
    block: (StoredClientCredentials) -> T,
): T {
    val state = read()
    try { return block(state) }
    finally { state.destroy() }
}
