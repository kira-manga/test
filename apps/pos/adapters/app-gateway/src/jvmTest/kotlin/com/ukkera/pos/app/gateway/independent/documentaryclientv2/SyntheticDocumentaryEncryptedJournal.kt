package com.ukkera.pos.app.gateway.independent.documentaryclientv2

import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JvmClientJournalCustody
import com.ukkera.pos.app.gateway.JvmClientJournalCustodyView
import com.ukkera.pos.app.gateway.JvmClientJournalOpenResult
import com.ukkera.pos.app.gateway.JvmEncryptedClientIntentJournal
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.StoredClientDocumentarySourceV1
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Real production AES-GCM/atomic-replace/fsync journal in a fresh private directory.
 * The JCA key and custody/current-context inputs are controlled; no protected native
 * owner, hardware key, rollback guard, Master, grant provisioning or RPC is qualified.
 */
internal class SyntheticDocumentaryEncryptedJournal private constructor(
    private val directory: Path,
    private val key: SecretKey,
    private val owner: LocalClientIdentity,
) {
    private val journalId = DomainId.random()
    private val keyId = DomainId.random()
    private var lifetime = AtomicBoolean(true)
    private var view = newView(owner)
    private val custody = JvmClientJournalCustody { view.takeIf { it.isCurrent() } }
    private var opened: JvmEncryptedClientIntentJournal? = null
    val journal: JvmEncryptedClientIntentJournal get() = requireNotNull(opened)

    private fun newView(clientOwner: LocalClientIdentity): JvmClientJournalCustodyView =
        JvmClientJournalCustodyView(directory, clientOwner, journalId, keyId, DomainId.random(), key, lifetime::get)

    suspend fun retire() {
        try { opened?.close() } finally { opened = null; lifetime.set(false) }
    }

    suspend fun openExistingAs(clientOwner: LocalClientIdentity = owner): JvmClientJournalOpenResult {
        retire()
        lifetime = AtomicBoolean(true)
        view = newView(clientOwner)
        return JvmEncryptedClientIntentJournal.openExisting(custody).also { result ->
            if (result is JvmClientJournalOpenResult.Opened) opened = result.journal
        }
    }

    suspend fun reopenOriginal() {
        assertIs<JvmClientJournalOpenResult.Opened>(openExistingAs(),
            "The original synthetic encrypted custody must reopen without initializing replacement state")
    }

    suspend fun source(id: DomainId): StoredClientDocumentarySourceV1 =
        assertNotNull(assertIs<JournalRead.Value<StoredClientDocumentarySourceV1?>>(journal.documentarySource(id)).value)

    suspend fun sources(): List<StoredClientDocumentarySourceV1> =
        assertIs<JournalRead.Value<List<StoredClientDocumentarySourceV1>>>(journal.documentarySources()).value

    suspend fun find(id: OperationId): StoredClientIntentV1 =
        assertNotNull(assertIs<JournalRead.Value<StoredClientIntentV1?>>(journal.find(id)).value)

    suspend fun pending(): List<StoredClientIntentV1> =
        assertIs<JournalRead.Value<List<StoredClientIntentV1>>>(journal.pending()).value

    /** Controlled corruption only after closing, against this test's new directory/key. */
    suspend fun removeDocumentarySources() {
        retire()
        withContext(Dispatchers.IO) {
            val plain = decryptManifest(Files.readAllBytes(directory.resolve(MANIFEST)))
            try {
                val original = FrozenDocumentaryClientInputsV2.json.parseToJsonElement(plain.decodeToString()).jsonObject
                val withoutSources = JsonObject(original.filterKeys { it != "documentarySources" })
                val altered = FrozenDocumentaryClientInputsV2.json.encodeToString(JsonObject.serializer(), withoutSources).encodeToByteArray()
                try { Files.write(directory.resolve(MANIFEST), encryptManifest(altered)) }
                finally { altered.fill(0) }
            } finally { plain.fill(0) }
        }
    }

    suspend fun removeArchivedStep(id: OperationId) {
        retire()
        withContext(Dispatchers.IO) { Files.delete(directory.resolve("archive").resolve("${id.value}.v1.enc")) }
    }

    suspend fun encryptedManifest(): ByteArray = withContext(Dispatchers.IO) { Files.readAllBytes(directory.resolve(MANIFEST)) }

    private fun aad(): ByteArray = listOf("ukkera.client-intents/v1", owner.scope.organizationId.value,
        owner.scope.companyId.value, owner.scope.branchId.value, owner.actorId.value, owner.deviceId.value,
        journalId.value, keyId.value, "manifest").joinToString("\n").encodeToByteArray()

    private fun decryptManifest(bytes: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(bytes)
        check(buffer.int == MAGIC)
        val nonce = ByteArray(12).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val additional = aad()
        try { cipher.updateAAD(additional); return cipher.doFinal(bytes, 16, bytes.size - 16) }
        finally { additional.fill(0) }
    }

    private fun encryptManifest(plain: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val additional = aad()
        val encrypted = try { cipher.updateAAD(additional); cipher.doFinal(plain) } finally { additional.fill(0) }
        return ByteBuffer.allocate(16 + encrypted.size).putInt(MAGIC).put(nonce).put(encrypted).array()
    }

    suspend fun close() = withContext(NonCancellable) {
        try { retire() } finally {
            // Cleanup only this newly allocated fixture directory, never preserved evidence.
            withContext(Dispatchers.IO) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    companion object {
        private const val MANIFEST = "active.v1.enc"
        private const val MAGIC = 0x55434931

        suspend fun create(owner: LocalClientIdentity = FrozenDocumentaryClientInputsV2.owner): SyntheticDocumentaryEncryptedJournal {
            val directory = Files.createTempDirectory("documentary-synthetic-client-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val fixture = SyntheticDocumentaryEncryptedJournal(directory, key, owner)
            try {
                fixture.opened = assertIs<JvmClientJournalOpenResult.Opened>(JvmEncryptedClientIntentJournal.createNew(fixture.custody),
                    "This test needs actual private POSIX storage, AES-GCM and supported durable file IO").journal
                return fixture
            } catch (failure: Throwable) { fixture.close(); throw failure }
        }
    }
}

internal suspend fun withDocumentaryJournal(block: suspend (SyntheticDocumentaryEncryptedJournal) -> Unit) {
    val host = SyntheticDocumentaryEncryptedJournal.create()
    try { block(host) } finally { host.close() }
}
