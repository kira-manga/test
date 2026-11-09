package com.ukkera.pos.app.gateway.independent.arrivalapprovalclient

import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JvmClientJournalCustody
import com.ukkera.pos.app.gateway.JvmClientJournalCustodyView
import com.ukkera.pos.app.gateway.JvmClientJournalOpenResult
import com.ukkera.pos.app.gateway.JvmEncryptedClientIntentJournal
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Real AES-GCM, atomic replacement and fsync client-journal storage in a new private
 * directory. The JCA key and current-owner observations are controlled inputs;
 * this fixture supplies no native authority, Master storage, approval or RPC.
 */
internal class SyntheticArrivalApprovalEncryptedJournal private constructor(
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
    val journal: JvmEncryptedClientIntentJournal get() = requireNotNull(opened)

    private fun view(clientOwner: LocalClientIdentity): JvmClientJournalCustodyView =
        JvmClientJournalCustodyView(directory, clientOwner, journalId, keyId, DomainId.random(), key, lifetime::get)

    suspend fun retire() {
        try { opened?.close() } finally { opened = null; lifetime.set(false) }
    }

    suspend fun openExistingAs(clientOwner: LocalClientIdentity = owner): JvmClientJournalOpenResult {
        retire()
        lifetime = AtomicBoolean(true)
        view = view(clientOwner)
        return JvmEncryptedClientIntentJournal.openExisting(custody).also { result ->
            if (result is JvmClientJournalOpenResult.Opened) opened = result.journal
        }
    }

    suspend fun reopenOriginal() {
        assertIs<JvmClientJournalOpenResult.Opened>(openExistingAs(),
            "Reopen must use the same saved client owner and encrypted evidence, without initialization.")
    }

    suspend fun find(operationId: OperationId): StoredClientIntentV1 =
        assertNotNull(assertIs<JournalRead.Value<StoredClientIntentV1?>>(journal.find(operationId)).value)

    suspend fun pending(): List<StoredClientIntentV1> =
        assertIs<JournalRead.Value<List<StoredClientIntentV1>>>(journal.pending()).value

    /** Read only this fixture's plaintext to test that transient authentication is absent. */
    suspend fun storedPlaintexts(): List<String> = withContext(Dispatchers.IO) {
        buildList {
            add(decrypt(Files.readAllBytes(directory.resolve("active.v1.enc")), "manifest"))
            val archives = directory.resolve("archive")
            if (Files.exists(archives)) Files.list(archives).use { files ->
                files.forEach { path ->
                    val id = path.fileName.toString().removeSuffix(".v1.enc")
                    add(decrypt(Files.readAllBytes(path), "archive/$id"))
                }
            }
        }
    }

    private fun decrypt(encrypted: ByteArray, purpose: String): String {
        val buffer = ByteBuffer.wrap(encrypted)
        check(buffer.int == 0x55434931)
        val nonce = ByteArray(12).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce))
        val additional = listOf("ukkera.client-intents/v1", owner.scope.organizationId.value,
            owner.scope.companyId.value, owner.scope.branchId.value, owner.actorId.value, owner.deviceId.value,
            journalId.value, keyId.value, purpose).joinToString("\n").encodeToByteArray()
        try {
            cipher.updateAAD(additional)
            val plain = cipher.doFinal(encrypted, 16, encrypted.size - 16)
            try { return plain.decodeToString(throwOnInvalidSequence = true) }
            finally { plain.fill(0) }
        } finally { additional.fill(0); nonce.fill(0); encrypted.fill(0) }
    }

    suspend fun close() = withContext(NonCancellable) {
        try { retire() } finally {
            withContext(Dispatchers.IO) {
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    companion object {
        suspend fun create(owner: LocalClientIdentity): SyntheticArrivalApprovalEncryptedJournal {
            val directory = Files.createTempDirectory("arrival-approval-client-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            val host = SyntheticArrivalApprovalEncryptedJournal(directory, key, owner)
            try {
                host.opened = assertIs<JvmClientJournalOpenResult.Opened>(
                    JvmEncryptedClientIntentJournal.createNew(host.custody),
                    "Actual supported private storage, AES-GCM and durable file IO are required.").journal
                return host
            } catch (failure: Throwable) { host.close(); throw failure }
        }
    }
}

internal suspend fun withArrivalApprovalJournal(owner: LocalClientIdentity,
    block: suspend (SyntheticArrivalApprovalEncryptedJournal) -> Unit) {
    val host = SyntheticArrivalApprovalEncryptedJournal.create(owner)
    try { block(host) } finally { host.close() }
}
