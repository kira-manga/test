package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlin.test.*

/**
 * Self-authored, NOT independent. Frozen LAN2-U05 unknown-commit supporting fault cut
 * (qa/pos/designs/lan-master-client-v2/cases.json; synchronization-v2 Client presentation).
 * Actual desktop file/AEAD/head engine; controlled parent binding, software keys and one native
 * fault. NOT genuine Master authority, process death, power loss or whole-case qualification.
 */
class ClientReadViewUnknownCommitSupportingTest {
    @Test fun earlyOpenOrCensusFailureNeverSubstitutesForASettledOriginalProof(): Unit = runBlocking {
        for (fault in listOf("custody", "probe", "write-ahead-probe", "temporary-probe", "names", "heads")) {
            val fixture = Fixture()
            try {
                val first = fixture.open(enroll = true)
                assertEquals(JournalWrite.DURABLE, first.file.readViews().replace(fixture.batch("A")))
                first.file.closeOwner()
                val before = fixture.cacheBytes()
                val keys = fixture.keys.keyNames("").toSet()
                fixture.openFault = fault
                val reopened = fixture.open(enroll = false)
                assertEquals(JournalRead.Unavailable, reopened.file.readViews().load(), fault)
                assertEquals(JournalWrite.UNAVAILABLE, reopened.file.readViews().replace(null), fault)
                assertEquals(0, fixture.quarantines, fault)
                fixture.assertCacheBytes(before)
                assertEquals(keys, fixture.keys.keyNames("").toSet(), fault)
                reopened.file.closeOwner()
            } finally { fixture.close() }
        }
    }

    @Test fun provedSettledCorruptionMayQuarantineOnlyTheDisposablePurpose(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val first = fixture.open(enroll = true)
            assertEquals(JournalWrite.DURABLE, first.file.readViews().replace(fixture.batch("A")))
            first.file.closeOwner()
            val manifest = fixture.cacheDirectory().resolve(ClientJournalFileCore.MANIFEST)
            val bytes = Files.readAllBytes(manifest)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(manifest, bytes)
            val parentBefore = fixture.parentBytes()
            val reopened = fixture.open(enroll = false)
            assertNull(assertIs<JournalRead.Value<StoredClientReadViewBatchV1?>>(reopened.file.readViews().load()).value)
            assertEquals(1, fixture.quarantines)
            val parentAfter = fixture.parentBytes()
            assertEquals(parentBefore.keys, parentAfter.keys)
            parentBefore.forEach { (name, retained) -> assertContentEquals(retained, parentAfter.getValue(name)) }
            reopened.file.closeOwner()
        } finally { fixture.close() }
    }

    @Test fun cancelledDeliveryCannotResetAnAcceptedWriteBeforeNativeSettlement(): Unit = runBlocking {
        val fixture = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val first = fixture.open(enroll = true)
            val views = first.file.readViews()
            assertEquals(JournalWrite.DURABLE, views.replace(fixture.batch("A")))
            val b = fixture.batch("B")
            fixture.childSyncGate = entered to release
            val writing = async { views.replace(b) }
            withContext(Dispatchers.IO) { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
            writing.cancelAndJoin()
            assertEquals(JournalRead.Unavailable, views.load())
            assertEquals(JournalWrite.UNAVAILABLE, views.replace(null))
            assertEquals(0, fixture.quarantines)
            release.countDown()
            first.file.closeOwner() // Actual native settlement precedes releasing exclusion.
            fixture.childSyncGate = null
            val reopened = fixture.open(enroll = false)
            assertEquals(b, assertIs<JournalRead.Value<StoredClientReadViewBatchV1?>>(reopened.file.readViews().load()).value)
            assertEquals(0, fixture.quarantines)
            reopened.file.closeOwner()
        } finally { release.countDown(); fixture.close() }
    }

    @Test fun uncertainReplaceIsUnavailableUntilExactColdRecoveryWithoutQuarantineOrRewrite(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val first = fixture.open(enroll = true)
            val views = first.file.readViews()
            val a = fixture.batch("A")
            val b = fixture.batch("B")
            assertEquals(JournalWrite.DURABLE, views.replace(a))
            fixture.failChildSync = true
            assertEquals(JournalWrite.UNAVAILABLE, views.replace(b))
            assertEquals(0, fixture.quarantines)
            val pending = fixture.cacheBytes()
            assertTrue(pending.keys.any { it.endsWith("/" + ClientJournalWriteCodecV1.FILE_NAME) })
            assertEquals(JournalRead.Unavailable, views.load())
            assertEquals(JournalWrite.UNAVAILABLE, views.replace(null))
            fixture.assertCacheBytes(pending)
            first.file.closeOwner()

            fixture.failChildSync = false
            val reopened = fixture.open(enroll = false)
            val recovered = assertIs<JournalRead.Value<StoredClientReadViewBatchV1?>>(reopened.file.readViews().load()).value
            assertEquals(b, recovered)
            assertEquals(0, fixture.quarantines)
            assertFalse(fixture.cacheBytes().keys.any { it.endsWith("/" + ClientJournalWriteCodecV1.FILE_NAME) })
            reopened.file.closeOwner()
        } finally { fixture.close() }
    }

    @Test fun failedColdRecoveryPreservesPendingBytesAndKeysInsteadOfCreatingAnEmptyPurpose(): Unit = runBlocking {
        val fixture = Fixture()
        try {
            val first = fixture.open(enroll = true)
            assertEquals(JournalWrite.DURABLE, first.file.readViews().replace(fixture.batch("A")))
            fixture.failChildSync = true
            assertEquals(JournalWrite.UNAVAILABLE, first.file.readViews().replace(fixture.batch("B")))
            first.file.closeOwner()
            fixture.failChildSync = false
            // Fault the actual persisted write-ahead body. Recovery cannot authenticate this
            // original and therefore must remain unavailable, retaining it for investigation.
            val ahead = fixture.cacheDirectory().resolve(ClientJournalWriteCodecV1.FILE_NAME)
            val bytes = Files.readAllBytes(ahead)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(ahead, bytes)
            val pending = fixture.cacheBytes()
            val keys = fixture.keys.keyNames("").toSet()
            val reopened = fixture.open(enroll = false)
            assertEquals(JournalRead.Unavailable, reopened.file.readViews().load())
            assertEquals(JournalWrite.UNAVAILABLE, reopened.file.readViews().replace(null))
            assertEquals(0, fixture.quarantines)
            fixture.assertCacheBytes(pending)
            assertEquals(keys, fixture.keys.keyNames("").toSet())
            reopened.file.closeOwner()
        } finally { fixture.close() }
    }

    private class Fixture {
        private fun id(n: Int) = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
        val root: Path = Files.createTempDirectory("pos-readview-unknown-")
        val keys = Keys()
        val owner = LocalClientIdentity(BranchScope(id(91), id(92), id(93)), id(94), id(95))
        var failChildSync = false
        var openFault: String? = null
        var childSyncGate: Pair<CountDownLatch, CountDownLatch>? = null
        var quarantines = 0
        private val files = mutableListOf<TestFile>()
        private val facts = object : DesktopJournalPlatformFacts {
            override val targetDigest = "a".repeat(64)
            override val volumeId = "0123abcd-0000-4000-8000-00000000abcd"
            override fun requireCurrent() = Unit
        }
        suspend fun open(enroll: Boolean): Opened {
            val work = ClientJournalWork(checkNotNull(ClientJournalProcessLease.acquire(id(1))), JvmClientJournalProcess())
            val binding = object : ClientJournalBindingFacts {
                override fun isCurrent() = true
                override fun permitsEnrollment() = enroll
                override fun requireCoordinates(owner: LocalClientDeviceIdentity, actor: DomainId?, journalId: DomainId,
                    keyId: DomainId, protection: SelfOwnedKeyProtectionV1) = Unit
                override val rootName = "supporting-readview-parent"
                override val keyAliasNamespace = "supporting.readview-key/"
                override val headAliasNamespace = "supporting.readview-head/"
                override val aeadDomain = "ukkera.supporting.readview-aead/v1"
                override val headDigestDomain = "ukkera.supporting.readview-head/v1"
                override val fixedKind: ClientJournalKind? = null
                override val employeeEnrollmentCommitment: String? = null
                override val pairedDeviceEnrollmentCommitment: String? = null
                override val pairedActorEnrollmentCommitment: String? = null
            }
            val actual = DesktopClientJournalNative(root, work, keys, facts)
            val parent = object : ClientJournalNative by actual {
                override fun forkReadViews(work: ClientJournalWork): ClientJournalNative {
                    val child = actual.forkReadViews(work)
                    return object : ClientJournalNative by child {
                        override fun readViewPurposeExists(rootName: String, journalId: DomainId): Boolean {
                            check(openFault != "probe")
                            return child.readViewPurposeExists(rootName, journalId)
                        }
                        override fun openCustody(rootName: String, journalId: DomainId, archive: Boolean,
                            enroll: Boolean, enrollChecks: () -> Unit, createDataKey: () -> Unit) {
                            child.openCustody(rootName, journalId, archive, enroll, enrollChecks, createDataKey)
                            check(openFault != "custody")
                        }
                        override fun exists(name: String): Boolean {
                            check(openFault != "write-ahead-probe" || name != ClientJournalWriteCodecV1.FILE_NAME)
                            check(openFault != "temporary-probe" || name != ClientJournalFileCore.TEMPORARY)
                            return child.exists(name)
                        }
                        override fun journalNames(): Set<String> {
                            check(openFault != "names")
                            return child.journalNames()
                        }
                        override fun keyNames(prefix: String): List<String> {
                            check(openFault != "heads")
                            return child.keyNames(prefix)
                        }
                        override fun syncDirectories(archive: Boolean) {
                            child.syncDirectories(archive)
                            childSyncGate?.let { (entered, release) ->
                                entered.countDown()
                                check(release.await(10, TimeUnit.SECONDS))
                            }
                            check(!failChildSync) { "supporting injected post-force uncertainty" }
                        }
                    }
                }
                override fun quarantineReadViews(purpose: ClientReadViewPurpose) { quarantines++; actual.quarantineReadViews(purpose) }
            }
            val file = TestFile(owner, id(1), id(2), work, binding, parent).also { files += it }
            assertIs<ClientJournalOutcome.Value<Unit>>(work.run { object : ClientJournalTask<Unit> {
                override fun execute() {
                    file.openNative(enroll, recoverWrites = false)
                    file.visitArchives { _, _ -> }
                    file.admitNativeHead(enroll)
                    file.attach()
                }
                override fun discard(value: Unit) = Unit
                override fun clearInputs() = Unit
            } })
            return Opened(file)
        }
        fun batch(text: String): StoredClientReadViewBatchV1 {
            val payload = StoredBytes(text.encodeToByteArray())
            val cursor = LocalMasterChangeCursorV1(owner.scope, AuthorityIdentity(id(81), id(82), Revision(1)),
                Revision(2), JournalSequence(2), PayloadDigest("a".repeat(64)), owner.actorId, owner.deviceId,
                id(83), SessionGeneration(1), Revision(1), Revision(1), LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
                PayloadDigest("b".repeat(64)), sha256(payload.copyBytes()))
            val snapshot = LocalMasterChangesReplyV1.Snapshot(ReadReply.Data(payload, Revision(2),
                UtcInstant.parseCanonical("2026-11-15T00:00:00Z")), cursor)
            val secondary = StoredBytes("$text-secondary".encodeToByteArray())
            val secondarySnapshot = LocalMasterChangesReplyV1.Snapshot(ReadReply.Data(secondary, Revision(2),
                snapshot.data.observedAt), cursor.copy(selectorDigest = PayloadDigest("c".repeat(64)),
                projectionDigest = sha256(secondary.copyBytes())))
            return StoredClientReadViewBatchV1(owner, id(84), id(85), listOf(
                StoredClientReadViewV1.from(ReadRequest(ReadKey("supporting.read-view", 1), StoredBytes(byteArrayOf(1))), snapshot),
                StoredClientReadViewV1.from(ReadRequest(ReadKey("supporting.secondary-view", 1), StoredBytes(byteArrayOf(2))), secondarySnapshot)))
        }
        fun cacheDirectory(): Path = Files.list(root.resolve("supporting-readview-parent-read-views-v1")).use {
            it.filter { path -> Files.isDirectory(path) }.findFirst().orElseThrow() }
        fun cacheBytes(): Map<String, ByteArray> = Files.walk(cacheDirectory()).use { paths -> paths.filter { Files.isRegularFile(it) }
            .toList().associate { root.relativize(it).toString() to Files.readAllBytes(it) } }
        fun parentBytes(): Map<String, ByteArray> = Files.walk(root.resolve("supporting-readview-parent")).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.readAllBytes(it) } }
        fun assertCacheBytes(expected: Map<String, ByteArray>) {
            val actual = cacheBytes()
            assertEquals(expected.keys, actual.keys)
            expected.forEach { (name, bytes) -> assertContentEquals(bytes, actual.getValue(name)) }
        }
        suspend fun close() { files.forEach { runCatching { it.closeOwner() } }; root.toFile().deleteRecursively() }
    }

    private class Opened(val file: TestFile)
    private class TestFile(owner: LocalClientIdentity, journal: DomainId, key: DomainId, work: ClientJournalWork,
        binding: ClientJournalBindingFacts, native: ClientJournalNative) : ClientJournalFileCore(
        LocalClientDeviceIdentity(owner.scope, owner.deviceId), owner.actorId, journal, key,
        SelfOwnedKeyProtectionV1.OS_ISOLATED, work, binding, native) {
        override fun initialManifest() = byteArrayOf(1)
        private val facet = object : ClientJournalPhysicalOwner {
            override fun denyPhysical() = Unit
            override val physicalDenied = false
            override val physicalLoansDrained = true
            override suspend fun awaitPhysicalDrain() = Unit
        }
        fun attach() = attachAdmittedOwner(facet)
    }

    /** Controlled software keys for this fault unit only; genuine process fixture uses durable custody. */
    private class Keys : DesktopJournalKeys {
        private val held = linkedMapOf<String, ByteArray>()
        override fun keyNames(prefix: String) = held.keys.filter { it.startsWith(prefix) }
        override fun keyExists(name: String) = name in held
        override fun createKey(name: String, protection: SelfOwnedKeyProtectionV1) {
            check(name !in held); held[name] = ByteArray(32).also { SecureRandom().nextBytes(it) }
        }
        override fun deleteKey(name: String) { checkNotNull(held.remove(name)).fill(0) }
        override fun inspectKey(name: String, protection: SelfOwnedKeyProtectionV1): Any { check(name in held); return "SOFTWARE" }
        override fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            return nonce to cipher(Cipher.ENCRYPT_MODE, name, nonce, aad).doFinal(plaintext)
        }
        override fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray =
            cipher(Cipher.DECRYPT_MODE, name, nonce, aad).doFinal(ciphertext)
        private fun cipher(mode: Int, name: String, nonce: ByteArray, aad: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(checkNotNull(held[name]), "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad)
        }
    }
}
