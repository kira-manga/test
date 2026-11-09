package com.ukkera.pos.app.gateway.independent.catalogreleasesourcev1

import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JvmClientJournalCustody
import com.ukkera.pos.app.gateway.JvmClientJournalCustodyView
import com.ukkera.pos.app.gateway.JvmClientJournalOpenResult
import com.ukkera.pos.app.gateway.JvmEncryptedClientIntentJournal
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Actual production AES-GCM/fsync client journal in a newly allocated synthetic private
 * directory. Custody/key/context inputs are controlled in-process test scaffolding;
 * this is not native-owner, secure-hardware, rollback, Master or RPC qualification.
 */
internal class SyntheticCatalogReleaseEncryptedJournal private constructor(
    private val directory: Path,
    private val key: SecretKey,
    private val owner: LocalClientIdentity,
) {
    private val journalId = DomainId.random()
    private val keyId = DomainId.random()
    private var contextLive = AtomicBoolean(true)
    private var view = newView(owner)
    private val custody = JvmClientJournalCustody { view.takeIf { it.isCurrent() } }
    private var opened: JvmEncryptedClientIntentJournal? = null
    val journal: JvmEncryptedClientIntentJournal get() = requireNotNull(opened)

    private fun newView(clientOwner: LocalClientIdentity): JvmClientJournalCustodyView =
        JvmClientJournalCustodyView(directory, clientOwner, journalId, keyId, DomainId.random(), key, contextLive::get)

    suspend fun openExistingAs(clientOwner: LocalClientIdentity): JvmClientJournalOpenResult {
        opened?.close()
        opened = null
        contextLive.set(false)
        contextLive = AtomicBoolean(true)
        view = newView(clientOwner)
        return JvmEncryptedClientIntentJournal.openExisting(custody).also { result ->
            if (result is JvmClientJournalOpenResult.Opened) opened = result.journal
        }
    }

    suspend fun reopenOriginal() {
        assertIs<JvmClientJournalOpenResult.Opened>(openExistingAs(owner),
            "The actual synthetic encrypted journal must reopen without initializing a replacement")
    }

    suspend fun find(operationId: OperationId): StoredClientIntentV1 {
        val read = assertIs<JournalRead.Value<StoredClientIntentV1?>>(journal.find(operationId),
            "The actual synthetic encrypted journal must return its retained intent")
        return assertNotNull(read.value)
    }

    suspend fun pending(): List<StoredClientIntentV1> =
        assertIs<JournalRead.Value<List<StoredClientIntentV1>>>(journal.pending()).value

    suspend fun close() = withContext(NonCancellable) {
        try { opened?.close() }
        finally {
            opened = null
            contextLive.set(false)
            // Delete only this newly generated fixture directory; no historical evidence is touched.
            withContext(Dispatchers.IO) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    companion object {
        suspend fun create(): SyntheticCatalogReleaseEncryptedJournal {
            val privateDirectory = Files.createTempDirectory("catalog-release-synthetic-client-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val fixture = SyntheticCatalogReleaseEncryptedJournal(privateDirectory, key,
                FrozenCatalogReleaseGatewayContentInputs.owner)
            try {
                fixture.opened = assertIs<JvmClientJournalOpenResult.Opened>(
                    JvmEncryptedClientIntentJournal.createNew(fixture.custody),
                    "This test requires actual private POSIX storage, AES-GCM and supported durable IO").journal
                return fixture
            } catch (failure: Throwable) {
                fixture.close()
                throw failure
            }
        }
    }
}
