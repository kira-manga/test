package com.ukkera.pos.rpc.independent.factorylifecycle

import com.ukkera.pos.rpc.LocalRpcFactoryOwner
import com.ukkera.pos.rpc.LocalRpcFactoryPhase
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.security.JvmSecureIdentityStore
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.RpcTrustPurpose
import dev.p2pkit.rpc.RpcTrustStore
import dev.p2pkit.rpc.jvm
import dev.p2pkit.transport.lan.OrganizationLan
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Inputs imported unchanged from rpc-factory-identity-readback v1. */
internal object FactoryReadbackInputs {
    val appId: AppId = AppId("pos.synthetic.rpc.readback")
    const val english: String = "Front Counter"
    const val arabic: String = "كاونتر البيع"
    const val nfc: String = "Caf\u00e9 Counter"
    const val decomposed: String = "Cafe\u0301 Counter"
    val names: List<String> = listOf(english, arabic, nfc, decomposed)

    // A selected observation bound, not a frozen SDK/native cleanup guarantee.
    const val observationMillis: Long = 20_000L

    /** Explicit synthetic SDK-only policy; never selected as a live/native LAN or started. */
    fun neverStartedLan(): OrganizationLan = OrganizationLan(
        allowedSubnets = listOf("10.77.0.0/24"),
        interfaceName = "pos-no-network-fixture",
        localAddress = "10.77.0.1",
        listenPort = 0,
    )
}

internal enum class FactoryRole { HOST, CLIENT }

internal suspend fun <T> observe(block: suspend () -> T): T =
    withTimeout(FactoryReadbackInputs.observationMillis) { block() }

internal suspend fun awaitPhase(owner: LocalRpcFactoryOwner, expected: LocalRpcFactoryPhase) {
    observe { owner.phase.first { it == expected } }
}

/** Only facts captured from a genuine returned SDK snapshot; never native expected DATA. */
internal class ActualOriginal(snapshot: RpcLocalIdentity) {
    private val appId = snapshot.appId
    private val peerId = snapshot.peerId
    private val fingerprint = snapshot.fingerprint
    private val pairingQr = snapshot.pairingQr
    private val deviceName = snapshot.deviceName

    fun assertUnchanged(actual: RpcLocalIdentity) {
        assertTrue(actual.appId == appId, "The retained original AppId changed")
        assertTrue(actual.peerId == peerId, "The retained original PeerId changed")
        assertTrue(actual.fingerprint == fingerprint, "The retained original fingerprint changed")
        assertTrue(actual.pairingQr == pairingQr, "The retained original pairing QR changed")
        assertTrue(actual.deviceName == deviceName, "The retained original exact name changed")
    }
}

/**
 * A fault cut in the actual protected-store SPI, after encrypted bytes are forced and atomically
 * committed. It neither replaces RpcPlatform nor injects/observes a sealed SDK role. Provider
 * completion failure is supporting acquisition-uncertainty evidence, not SDK/native close failure.
 */
internal class ProtectedIdentityCommitCut(
    private val pause: Boolean,
    private val failCompletion: Boolean = false,
) {
    val committed: CompletableDeferred<Unit> = CompletableDeferred()
    private val first = AtomicBoolean(true)
    private val released = CountDownLatch(if (pause) 1 else 0)

    fun afterDurableCommit() {
        if (!first.compareAndSet(true, false)) return
        committed.complete(Unit)
        if (!released.await(FactoryReadbackInputs.observationMillis, TimeUnit.MILLISECONDS)) {
            throw IOException("Synthetic protected-store observation remained incomplete")
        }
        if (failCompletion) throw IOException("Synthetic protected-store completion is unavailable")
    }

    fun release() { released.countDown() }
}

internal data class ProviderCounts(val trustLoads: Int, val identityPuts: Int, val durableIdentityInserts: Int)

/** A same-process synthetic encrypted JVM profile, not an OS custody or native POS profile. */
internal class ProtectedJvmProfile internal constructor(
    root: Path,
    material: ByteArray,
    cut: ProtectedIdentityCommitCut? = null,
) {
    private val identityFiles = ProtectedOpaqueFiles(root.resolve("identity"), material, "identity-spi/v1")
    private val trustFiles = ProtectedOpaqueFiles(root.resolve("trust"), material, "empty-trust/v1")
    private val identity = ProtectedIdentityStore(identityFiles, cut)
    private val trust = DurableEmptyTrustStore(trustFiles)
    val platform: RpcPlatform = RpcPlatform.jvm(identity)
    val trustStore: RpcTrustStore get() = trust

    fun counts(): ProviderCounts = ProviderCounts(
        trust.loads.get(), identity.puts.get(), identity.inserts.get(),
    )

    fun protectedEvidence(): Map<String, String> =
        identityFiles.ciphertextDigests().mapKeys { "identity/${it.key}" } +
            trustFiles.ciphertextDigests().mapKeys { "trust/${it.key}" }

    internal fun clearSyntheticKey() {
        identityFiles.clearSyntheticKey()
        trustFiles.clearSyntheticKey()
    }
}

/**
 * Retains an active application Job distinct from all short callers. Real official factories use
 * RpcPlatform.jvm and opaque encrypted filesystem identity state. Reopen is same-process only.
 * Any unconfirmed/uncertain fixture retains its files, key and original context locally; successful
 * wrapper close is not asserted to be an independent global/native resource observation.
 */
internal class ProtectedFactoryFixture private constructor(
    private val root: Path,
    private val material: ByteArray,
    private val cut: ProtectedIdentityCommitCut?,
) {
    val applicationJob = SupervisorJob()
    val applicationScope: CoroutineScope = CoroutineScope(Dispatchers.Default + applicationJob)
    private val callersJob = SupervisorJob()
    val callerScope: CoroutineScope = CoroutineScope(Dispatchers.Default + callersJob)
    val profile: ProtectedJvmProfile = ProtectedJvmProfile(root, material, cut)
    private val profiles = CopyOnWriteArrayList<ProtectedJvmProfile>().also { it.add(profile) }
    private val owners = CopyOnWriteArrayList<LocalRpcFactoryOwner>()

    fun reopenProtectedProfile(): ProtectedJvmProfile =
        ProtectedJvmProfile(root, material).also { profiles.add(it) }

    fun capture(role: FactoryRole, name: String, selected: ProtectedJvmProfile = profile): LocalRpcFactoryOwner {
        val owner = when (role) {
            FactoryRole.HOST -> LocalRpcFactoryOwner.captureHost(selected.platform, applicationScope,
                FactoryReadbackInputs.appId, name, FactoryReadbackInputs.neverStartedLan(), selected.trustStore)
            FactoryRole.CLIENT -> LocalRpcFactoryOwner.captureClient(selected.platform, applicationScope,
                FactoryReadbackInputs.appId, name, FactoryReadbackInputs.neverStartedLan(), selected.trustStore)
        }
        owners.add(owner)
        return owner
    }

    fun reconstruct(role: FactoryRole, original: RpcLocalIdentity,
        selected: ProtectedJvmProfile = profile): LocalRpcFactoryOwner {
        val owner = when (role) {
            FactoryRole.HOST -> LocalRpcFactoryOwner.reconstructHost(selected.platform, applicationScope,
                original, FactoryReadbackInputs.neverStartedLan(), selected.trustStore)
            FactoryRole.CLIENT -> LocalRpcFactoryOwner.reconstructClient(selected.platform, applicationScope,
                original, FactoryReadbackInputs.neverStartedLan(), selected.trustStore)
        }
        owners.add(owner)
        return owner
    }

    fun startCloseCaller(owner: LocalRpcFactoryOwner): Deferred<Unit> = callerScope.async { owner.close() }

    suspend fun closeAndObserve(owner: LocalRpcFactoryOwner) {
        val caller = startCloseCaller(owner)
        observe { caller.await() }
    }

    suspend fun finish() {
        withContext(NonCancellable) {
            cut?.release()
            for (owner in owners) {
                if (owner.phase.value !in setOf(LocalRpcFactoryPhase.CLOSED, LocalRpcFactoryPhase.UNCERTAIN)) {
                    try { closeAndObserve(owner) } catch (_: Exception) { /* Retain unresolved fixture below. */ }
                }
            }
            applicationJob.cancel()
            val applicationEnded = try {
                observe { applicationJob.join() }
                true
            } catch (_: Exception) { false }
            if (!applicationEnded || owners.any { it.phase.value != LocalRpcFactoryPhase.CLOSED }) {
                retainedUncertain.add(this@ProtectedFactoryFixture)
                return@withContext
            }
            callersJob.cancel()
            val callersEnded = try {
                observe { callersJob.join() }
                true
            } catch (_: Exception) { false }
            if (!callersEnded) {
                retainedUncertain.add(this@ProtectedFactoryFixture)
                return@withContext
            }
            profiles.forEach { it.clearSyntheticKey() }
            material.fill(0)
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    companion object {
        private val retainedUncertain = CopyOnWriteArrayList<ProtectedFactoryFixture>()

        fun create(cut: ProtectedIdentityCommitCut? = null): ProtectedFactoryFixture {
            val material = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val root = Files.createTempDirectory("pos-rpc-factory-")
            FileChannel.open(root.parent, READ).use { it.force(true) }
            return ProtectedFactoryFixture(root, material, cut)
        }
    }
}

/** Encrypts opaque values; it does not parse SDK identity/reset records or assume their sizes. */
private class ProtectedIdentityStore(
    private val files: ProtectedOpaqueFiles,
    private val cut: ProtectedIdentityCommitCut?,
) : JvmSecureIdentityStore {
    val puts = AtomicInteger()
    val inserts = AtomicInteger()

    override fun read(namespace: String): ByteArray? = files.read(namespace)

    override fun putIfAbsent(namespace: String, value: ByteArray): ByteArray {
        puts.incrementAndGet()
        val input = value.copyOf()
        return try {
            files.putIfAbsent(namespace, input) {
                inserts.incrementAndGet()
                cut?.afterDurableCommit()
            }
        } finally { input.fill(0) }
    }

    override fun delete(namespace: String): Boolean = files.delete(namespace)
}

/** Fixed durable empty peer sets, separate AppId/purpose namespaces; never accepts a peer. */
private class DurableEmptyTrustStore(private val files: ProtectedOpaqueFiles) : RpcTrustStore {
    val loads = AtomicInteger()
    private val purposes = setOf(RpcTrustPurpose.HostClients, RpcTrustPurpose.SelectedHosts)
    private val emptyEncoding = "synthetic-empty-peer-set/v1".encodeToByteArray()

    init {
        for (purpose in purposes) {
            val durable = files.putIfAbsent(namespace(FactoryReadbackInputs.appId, purpose), emptyEncoding)
            try { check(durable.contentEquals(emptyEncoding)) { "Protected empty trust state differs" } }
            finally { durable.fill(0) }
        }
    }

    override suspend fun load(appId: AppId, purpose: RpcTrustPurpose): Set<PeerFingerprint> {
        loads.incrementAndGet()
        verifyEmpty(appId, purpose)
        return emptySet()
    }

    override suspend fun replace(appId: AppId, purpose: RpcTrustPurpose, fingerprints: Set<PeerFingerprint>) {
        require(fingerprints.isEmpty()) { "The synthetic no-network profile denies every peer" }
        verifyEmpty(appId, purpose)
        files.replace(namespace(appId, purpose), emptyEncoding)
    }

    private fun verifyEmpty(appId: AppId, purpose: RpcTrustPurpose) {
        val durable = files.read(namespace(appId, purpose))
            ?: throw IOException("Protected empty trust state is unavailable")
        try { check(durable.contentEquals(emptyEncoding)) { "Protected empty trust state differs" } }
        finally { durable.fill(0) }
    }

    private fun namespace(appId: AppId, purpose: RpcTrustPurpose): String {
        require(appId == FactoryReadbackInputs.appId && purpose in purposes) { "Unsupported synthetic trust namespace" }
        return "${appId.value}\u0000${purpose.name}"
    }
}

/**
 * Synthetic AES-GCM file protection with namespace-bound AAD, cooperative OS locks, forced writes
 * and atomic replacement. The explicit random test key is retained in memory, never stored beside
 * ciphertext or supplied as a production/passwordless default. No non-atomic/plaintext fallback.
 */
private class ProtectedOpaqueFiles(directory: Path, material: ByteArray, private val domain: String) {
    private val directory: Path = Files.createDirectories(directory).toRealPath().also {
        FileChannel.open(it.parent, READ).use { parent -> parent.force(true) }
    }
    private val material = material.copyOf().also { require(it.size == 32) }
    private val random = SecureRandom()
    private val processLock = processLocks.computeIfAbsent(this.directory) { ReentrantLock() }

    fun read(namespace: String): ByteArray? = locked { readLocked(namespace) }

    fun putIfAbsent(namespace: String, value: ByteArray, afterCommit: () -> Unit = {}): ByteArray = locked {
        readLocked(namespace)?.let { return@locked it }
        writeLocked(namespace, value)
        afterCommit()
        value.copyOf()
    }

    fun replace(namespace: String, value: ByteArray) { locked { writeLocked(namespace, value) } }

    fun delete(namespace: String): Boolean = locked {
        val removed = Files.deleteIfExists(path(namespace))
        if (removed) forceDirectory()
        removed
    }

    fun ciphertextDigests(): Map<String, String> = locked {
        Files.list(directory).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".protected") }.sorted().toList().associate {
                it.fileName.toString() to hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)))
            }
        }
    }

    fun clearSyntheticKey() { material.fill(0) }

    private fun readLocked(namespace: String): ByteArray? {
        val envelope = try { Files.readAllBytes(path(namespace)) } catch (_: NoSuchFileException) { return null }
        check(envelope.size >= header.size + nonceBytes + 16 &&
            envelope.copyOfRange(0, header.size).contentEquals(header)) { "Protected container is invalid" }
        val nonce = envelope.copyOfRange(header.size, header.size + nonceBytes)
        val cipher = cipher(Cipher.DECRYPT_MODE, namespace, nonce)
        return cipher.doFinal(envelope, header.size + nonceBytes, envelope.size - header.size - nonceBytes)
    }

    private fun writeLocked(namespace: String, value: ByteArray) {
        val nonce = ByteArray(nonceBytes).also { random.nextBytes(it) }
        val encrypted = cipher(Cipher.ENCRYPT_MODE, namespace, nonce).doFinal(value)
        val envelope = header + nonce + encrypted
        val pending = Files.createTempFile(directory, ".pending-", ".protected")
        FileChannel.open(pending, WRITE, TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(envelope)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(pending, path(namespace), ATOMIC_MOVE, REPLACE_EXISTING)
        forceDirectory()
    }

    private fun cipher(mode: Int, namespace: String, nonce: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").also {
            it.init(mode, SecretKeySpec(material, "AES"), GCMParameterSpec(128, nonce))
            it.updateAAD("$domain\u0000$namespace".encodeToByteArray())
        }

    private fun path(namespace: String): Path =
        directory.resolve(hex(MessageDigest.getInstance("SHA-256").digest(namespace.encodeToByteArray())) + ".protected")

    private fun forceDirectory() { FileChannel.open(directory, READ).use { it.force(true) } }

    private fun <T> locked(block: () -> T): T = processLock.withLock {
        FileChannel.open(directory.resolve("fixture.lock"), CREATE, WRITE).use { channel ->
            channel.lock().use { block() }
        }
    }

    companion object {
        private val processLocks = ConcurrentHashMap<Path, ReentrantLock>()
        private val header = byteArrayOf(0x50, 0x46, 0x45, 0x01)
        private const val nonceBytes: Int = 12
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
