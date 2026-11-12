package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Frozen expectations for the desktop client journal custody driving the SHARED journal engine,
 * authored BEFORE the desktop implementation on 12 November 2026. NOT independent (same author,
 * owner instruction). The in-memory AES key store below is test-only; production uses OS keys.
 */
class DesktopClientJournalFrozenTest {
    private val parent: Path = Files.createTempDirectory("desktop-client-journal")
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
    private val owner = LocalClientDeviceIdentity(BranchScope(id(91), id(92), id(93)), id(94))
    private val keys = MemoryKeys()
    private val facts = object : DesktopJournalPlatformFacts {
        override val targetDigest = "a".repeat(64)
        override val volumeId = "0123abcd-0000-4000-8000-00000000abcd"
        override fun requireCurrent() = Unit
    }

    private val created = mutableListOf<Journal>()
    @AfterTest fun cleanup() { created.forEach { it.close() }; parent.toFile().deleteRecursively() }

    private inner class Journal(private val actor: DomainId? = null, enrollAllowed: Boolean) {
        init { created += this }
        val work = ClientJournalWork(checkNotNull(ClientJournalProcessLease.acquire(id(1))), JvmClientJournalProcess())
        val file = TestFile(owner, actor, id(1), id(2), work, Binding(enrollAllowed),
            DesktopClientJournalNative(parent, work, keys, facts))
        fun <T> run(body: () -> T): ClientJournalOutcome<T> = runBlocking {
            work.run { object : ClientJournalTask<T> {
                override fun execute(): T = body()
                override fun discard(value: T) = Unit
                override fun clearInputs() = Unit
            } }
        }
        // Protocol: an intent journal's complete archive census precedes head admission.
        fun open(enroll: Boolean) = run {
            file.openNative(enroll, recoverWrites = false)
            if (actor != null) file.visitArchives { _, _ -> }
            file.admitNativeHead(enroll)
        }
        fun close() = runBlocking { runCatching { file.closeOwner() } }
    }

    private val credentialsMax = MAX_CLIENT_CREDENTIAL_BYTES

    @Test
    fun enrollmentStoresTheInitialManifestAndReplacesIt() {
        val journal = Journal(enrollAllowed = true)
        assertIs<ClientJournalOutcome.Value<Unit>>(journal.open(enroll = true))
        assertContentEquals(INITIAL, (journal.run { journal.file.readManifest(credentialsMax) } as ClientJournalOutcome.Value).value)
        assertIs<ClientJournalOutcome.Value<Unit>>(journal.run { journal.file.replaceManifest(byteArrayOf(9, 9), credentialsMax) })
        assertContentEquals(byteArrayOf(9, 9), (journal.run { journal.file.readManifest(credentialsMax) } as ClientJournalOutcome.Value).value)
        journal.close()
    }

    @Test
    fun reopeningIsExistingOnlyAndReturnsTheLatestCut() {
        Journal(enrollAllowed = true).apply { open(true); run { file.replaceManifest(byteArrayOf(7), credentialsMax) }; close() }
        val reopened = Journal(enrollAllowed = false)
        assertIs<ClientJournalOutcome.Value<Unit>>(reopened.open(enroll = false))
        assertContentEquals(byteArrayOf(7), (reopened.run { reopened.file.readManifest(credentialsMax) } as ClientJournalOutcome.Value).value)
        reopened.close()
    }

    @Test
    fun secondEnrollmentIsRefused() {
        Journal(enrollAllowed = true).apply { open(true); close() }
        val again = Journal(enrollAllowed = true)
        assertEquals(ClientJournalOutcome.Unavailable, again.open(enroll = true))
        again.close()
    }

    @Test
    fun fileRollbackIsRefusedByTheHead() {
        val first = Journal(enrollAllowed = true)
        first.open(true)
        val manifest = journalDirectory().resolve(ClientJournalFileCore.MANIFEST)
        val old = Files.readAllBytes(manifest)
        first.run { first.file.replaceManifest(byteArrayOf(5), credentialsMax) }
        first.close()
        Files.delete(manifest); Files.write(manifest, old)
        Journal(enrollAllowed = false).apply { assertEquals(ClientJournalOutcome.Unavailable, open(enroll = false)); close() }
    }

    @Test
    fun tamperedManifestIsRefused() {
        Journal(enrollAllowed = true).apply { open(true); close() }
        val manifest = journalDirectory().resolve(ClientJournalFileCore.MANIFEST)
        val bytes = Files.readAllBytes(manifest); bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        Files.delete(manifest); Files.write(manifest, bytes)
        Journal(enrollAllowed = false).apply { assertEquals(ClientJournalOutcome.Unavailable, open(enroll = false)); close() }
    }

    @Test
    fun intentArchivesAreAppendOnlyAndCensused() {
        val journal = Journal(actor = id(5), enrollAllowed = true)
        assertIs<ClientJournalOutcome.Value<Unit>>(journal.open(enroll = true))
        val operation = OperationId("00000000-0000-4000-8000-0000000000aa")
        assertIs<ClientJournalOutcome.Value<Unit>>(journal.run { journal.file.appendArchive(operation, byteArrayOf(3, 3)) })
        val seen = mutableListOf<Pair<OperationId, List<Byte>>>()
        val count = (journal.run { journal.file.visitArchives { op, bytes -> seen += op to bytes.toList() } } as ClientJournalOutcome.Value).value
        assertEquals(1L, count)
        assertEquals(listOf(operation to listOf<Byte>(3, 3)), seen)
        assertEquals(ClientJournalOutcome.Unavailable, journal.run { journal.file.appendArchive(operation, byteArrayOf(4)) })
        journal.close()
    }

    private fun journalDirectory(): Path = parent.resolve("test-journals").resolve(id(1).value)

    private class TestFile(owner: LocalClientDeviceIdentity, actor: DomainId?, journal: DomainId, key: DomainId,
        work: ClientJournalWork, binding: ClientJournalBindingFacts, native: ClientJournalNative) :
        ClientJournalFileCore(owner, actor, journal, key, SelfOwnedKeyProtectionV1.OS_ISOLATED, work, binding, native) {
        override fun initialManifest(): ByteArray = INITIAL.copyOf()
    }

    private class Binding(private val enrollAllowed: Boolean) : ClientJournalBindingFacts {
        override fun isCurrent() = true
        override fun permitsEnrollment() = enrollAllowed
        override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId, keyId: DomainId,
            protection: SelfOwnedKeyProtectionV1) = Unit
        override val rootName = "test-journals"
        override val keyAliasNamespace = "test.journal-key/"
        override val headAliasNamespace = "test.journal-head/"
        override val aeadDomain = "ukkera.desktop.test-journal-aead/v1"
        override val headDigestDomain = "ukkera.desktop.test-journal-head/v1"
        override val fixedKind: ClientJournalKind? = null
        override val employeeEnrollmentCommitment: String? = null
        override val pairedDeviceEnrollmentCommitment: String? = null
        override val pairedActorEnrollmentCommitment: String? = null
    }

    /** Test-only software key store (production desktop keys are OS custody). */
    private class MemoryKeys : DesktopJournalKeys {
        private val keys = linkedMapOf<String, ByteArray>()
        override fun keyNames(prefix: String) = keys.keys.filter { it.startsWith(prefix) }
        override fun keyExists(name: String) = name in keys
        override fun createKey(name: String, protection: SelfOwnedKeyProtectionV1) {
            check(name !in keys); keys[name] = ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
        override fun deleteKey(name: String) { checkNotNull(keys.remove(name)) }
        override fun inspectKey(name: String, protection: SelfOwnedKeyProtectionV1): Any { check(name in keys); return "SOFTWARE" }
        override fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(checkNotNull(keys[name]), "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            return nonce to cipher.doFinal(plaintext)
        }
        override fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(checkNotNull(keys[name]), "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(ciphertext)
        }
    }

    private companion object { val INITIAL = byteArrayOf(1, 2, 3) }
}
