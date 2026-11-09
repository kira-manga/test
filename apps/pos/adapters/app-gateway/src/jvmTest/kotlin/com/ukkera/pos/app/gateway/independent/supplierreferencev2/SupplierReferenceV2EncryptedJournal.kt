package com.ukkera.pos.app.gateway.independent.supplierreferencev2

import com.ukkera.pos.app.gateway.*
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
 * Real production JVM AES-GCM, private-file locking, atomic replace and fsync journal.
 * Fresh test-only JCA key/current-custody inputs are controlled, not hardware/native
 * owner, rollback protection, Master custody, admitted RPC or platform qualification.
 */
internal class SupplierReferenceV2EncryptedJournal private constructor(
    private val directory: Path,
    private val key: SecretKey,
    val owner: LocalClientIdentity,
) {
    private val journalId = DomainId.random()
    private val keyId = DomainId.random()
    private var lifetime = AtomicBoolean(true)
    private var view = view(owner)
    private val custody = JvmClientJournalCustody { view.takeIf { it.isCurrent() } }
    private var opened: JvmEncryptedClientIntentJournal? = null
    val journal get() = checkNotNull(opened)
    private fun view(owner: LocalClientIdentity) =
        JvmClientJournalCustodyView(directory, owner, journalId, keyId, DomainId.random(), key, lifetime::get)

    suspend fun retire() {
        try { opened?.close() } finally { opened = null; lifetime.set(false) }
    }

    suspend fun reopen(asOwner: LocalClientIdentity = owner): JvmClientJournalOpenResult {
        retire()
        lifetime = AtomicBoolean(true)
        view = view(asOwner)
        return JvmEncryptedClientIntentJournal.openExisting(custody).also {
            if (it is JvmClientJournalOpenResult.Opened) opened = it.journal
        }
    }

    suspend fun find(operation: OperationId): StoredClientIntentV1 =
        assertNotNull(assertIs<JournalRead.Value<StoredClientIntentV1?>>(journal.find(operation)).value)
    suspend fun pending(): List<StoredClientIntentV1> =
        assertIs<JournalRead.Value<List<StoredClientIntentV1>>>(journal.pending()).value

    /** Alter only this disposable manifest after confirmed production journal close. */
    suspend fun corruptClosedManifest(): ByteArray {
        retire()
        return withContext(Dispatchers.IO) {
            val path = directory.resolve("active.v1.enc")
            val original = Files.readAllBytes(path)
            val damaged = original.copyOf()
            damaged[damaged.lastIndex] = (damaged.last().toInt() xor 1).toByte()
            Files.write(path, damaged)
            damaged.fill(0)
            original
        }
    }

    suspend fun restoreClosedManifest(original: ByteArray) {
        retire()
        withContext(Dispatchers.IO) { Files.write(directory.resolve("active.v1.enc"), original) }
    }

    suspend fun close() = withContext(NonCancellable) {
        try { retire() } finally {
            // Delete this fixture's newly allocated private directory only.
            withContext(Dispatchers.IO) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    companion object {
        suspend fun create(owner: LocalClientIdentity = SupplierReferenceV2GatewayInputs.owner): SupplierReferenceV2EncryptedJournal {
            val directory = Files.createTempDirectory("supplier-reference-synthetic-client-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val fixture = SupplierReferenceV2EncryptedJournal(directory,
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(), owner)
            try {
                fixture.opened = assertIs<JvmClientJournalOpenResult.Opened>(
                    JvmEncryptedClientIntentJournal.createNew(fixture.custody),
                    "Actual supported private POSIX AES-GCM/durable IO is a prerequisite, not an accepting substitute").journal
                return fixture
            } catch (failure: Throwable) { fixture.close(); throw failure }
        }
    }
}
