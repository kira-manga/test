package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Protected root-owned observation. The root must separately qualify OS custody, exclusive
 * directory continuity and rollback/restart history. This object/SecretKey is not that proof.
 * No adjacent key, password-derived default key or auto-created directory is accepted.
 */
public class JvmClientCredentialCustodyView(
    public val directory: Path,
    public val owner: LocalClientDeviceIdentity,
    public val journalId: DomainId,
    public val keyId: DomainId,
    public val processContextId: DomainId,
    public val key: SecretKey,
    private val currentLifetime: () -> Boolean,
) {
    public fun isCurrent(): Boolean = currentLifetime()
    override fun toString(): String = "JvmClientCredentialCustodyView([REDACTED])"
}

public fun interface JvmClientCredentialCustody {
    public fun current(): JvmClientCredentialCustodyView?
}

public sealed interface JvmClientCredentialJournalOpenResult {
    public class Opened(public val journal: JvmEncryptedClientCredentialJournal) : JvmClientCredentialJournalOpenResult
    public data object Unavailable : JvmClientCredentialJournalOpenResult
}

/**
 * Real private-POSIX JVM AEAD/file-force/atomic-rename/directory-force credential journal.
 * Tokens are binary owned buffers, never JSON strings/base64/hex. All decoded token snapshots
 * must be destroyed by their consumer. Missing/corrupt/unknown tails retire without repair.
 * Unsupported filesystems/providers fail closed; this is NOT Windows/mobile qualification,
 * rollback proof, master fencing or protected physical-money outcome storage.
 */
public class JvmEncryptedClientCredentialJournal private constructor(
    owner: LocalClientDeviceIdentity,
    private val custody: JvmClientCredentialCustody,
    private val directory: Path,
    private val journalId: DomainId,
    private val keyId: DomainId,
    private val processContextId: DomainId,
    private val directoryKey: Any,
    private val lockKey: Any,
    private val channel: FileChannel,
    private val lock: FileLock,
) : LocalClientCredentialJournal(owner) {
    private val disk = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val random = SecureRandom()
    private var expectedHead: PayloadDigest? = null

    override internal suspend fun read(): JournalRead<StoredClientCredentials> {
        var snapshot: StoredClientCredentials? = null
        var handedOff = false
        try {
            val result = access {
                readState().also { snapshot = it }
            } ?: return JournalRead.Unavailable
            handedOff = true // after the cancellable IO-to-caller dispatch, never inside it
            return JournalRead.Value(result)
        } finally { if (!handedOff) snapshot?.destroy() }
    }

    override internal suspend fun replace(expectedRevision: Long, next: StoredClientCredentials): JournalWrite = access {
        next.validate(owner)
        check(expectedRevision >= 0 && expectedRevision < Long.MAX_VALUE && next.revision == expectedRevision + 1)
        val actual = readState()
        try {
            if (actual.revision != expectedRevision) JournalWrite.IDENTITY_CONFLICT
            else { writeState(next); JournalWrite.DURABLE }
        } finally { actual.destroy() }
    } ?: JournalWrite.UNAVAILABLE

    override public suspend fun close() {
        closed.set(true)
        withContext(NonCancellable + Dispatchers.IO) { disk.withLock {
            try { if (lock.isValid) lock.release() } finally { channel.close() }
        } }
    }

    private suspend fun <T> access(block: () -> T): T? = withContext(Dispatchers.IO) {
        disk.withLock {
            if (closed.get() || failed.get()) return@withLock null
            try {
                currentCustody()
                val result = block()
                currentCustody()
                result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed.set(true); null }
        }
    }

    private fun currentCustody(temporary: Path? = null): JvmClientCredentialCustodyView {
        check(!closed.get() && !failed.get() && lock.isValid && channel.isOpen)
        val view = custody.current() ?: error("client.credentials.custody.unavailable")
        check(view.owner == owner && view.journalId == journalId && view.keyId == keyId && view.processContextId == processContextId &&
            view.directory.toAbsolutePath().normalize() == directory && view.isCurrent() && view.key.algorithm.equals("AES", true))
        credentialDirectory(directory)
        check(credentialFileKey(directory) == directoryKey && credentialFileKey(directory.resolve(CREDENTIAL_LOCK)) == lockKey)
        credentialPrivateFile(directory.resolve(CREDENTIAL_LOCK))
        if (temporary != null) credentialPrivateFile(temporary)
        credentialRootEntries(directory, temporary?.fileName?.toString())
        return view
    }

    private fun aad(): ByteArray = listOf("ukkera.client-credentials/v1", owner.scope.organizationId.value,
        owner.scope.companyId.value, owner.scope.branchId.value, owner.deviceId.value, journalId.value, keyId.value,
        "binary-manifest").joinToString("\n").encodeToByteArray()

    private fun readState(): StoredClientCredentials {
        val bytes = credentialReadFile(directory.resolve(CREDENTIAL_FILE), MAX_CLIENT_CREDENTIAL_BYTES + CREDENTIAL_OVERHEAD)
        return bytes.useWiped { encrypted ->
            val digest = sha256(encrypted)
            check(expectedHead == null || expectedHead == digest)
            val view = currentCustody()
            check(encrypted.size in CREDENTIAL_OVERHEAD..(MAX_CLIENT_CREDENTIAL_BYTES + CREDENTIAL_OVERHEAD))
            val buffer = ByteBuffer.wrap(encrypted)
            check(buffer.int == CREDENTIAL_MAGIC)
            val nonce = ByteArray(12).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, view.key, GCMParameterSpec(128, nonce))
            aad().useWiped(cipher::updateAAD)
            val plain = cipher.doFinal(encrypted, 16, encrypted.size - 16)
            val decoded = plain.useWiped { decodeCredentials(it, owner, journalId, keyId) }
            try {
                check(view.isCurrent())
                decoded.validate(owner)
                expectedHead = digest
                decoded
            } catch (failure: Throwable) { decoded.destroy(); throw failure }
        }
    }

    private fun writeState(value: StoredClientCredentials) {
        value.validate(owner)
        val view = currentCustody()
        encodeCredentials(value, owner, journalId, keyId).useWiped { plaintext ->
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, view.key, GCMParameterSpec(128, nonce))
            aad().useWiped(cipher::updateAAD)
            val ciphertext = cipher.doFinal(plaintext)
            check(view.isCurrent())
            val encrypted = ByteBuffer.allocate(16 + ciphertext.size).putInt(CREDENTIAL_MAGIC).put(nonce).put(ciphertext).array()
            encrypted.useWiped {
                val temporary = directory.resolve(".pending-${DomainId.random().value}.tmp")
                currentCustody()
                FileChannel.open(temporary, setOf<OpenOption>(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { file ->
                    val buffer = ByteBuffer.wrap(it)
                    while (buffer.hasRemaining()) check(file.write(buffer) > 0)
                    file.force(true)
                }
                currentCustody(temporary)
                Files.move(temporary, directory.resolve(CREDENTIAL_FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                credentialForceDirectory(directory)
                currentCustody()
                expectedHead = sha256(it)
            }
        }
    }

    public companion object {
        /** Explicit trusted initialization only; requires a pre-existing protected EMPTY directory. */
        public suspend fun createNew(custody: JvmClientCredentialCustody): JvmClientCredentialJournalOpenResult = open(custody, true)
        /** Missing/corrupt evidence cannot create a new journal, even when the key is available. */
        public suspend fun openExisting(custody: JvmClientCredentialCustody): JvmClientCredentialJournalOpenResult = open(custody, false)

        private suspend fun open(custody: JvmClientCredentialCustody, create: Boolean): JvmClientCredentialJournalOpenResult {
            var openedChannel: FileChannel? = null
            var openedLock: FileLock? = null
            var handedOff = false
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val view = custody.current() ?: return@withContext JvmClientCredentialJournalOpenResult.Unavailable
                        check(view.isCurrent() && view.key.algorithm.equals("AES", true))
                        val directory = view.directory.toAbsolutePath().normalize()
                        credentialDirectory(directory)
                        if (create) Files.newDirectoryStream(directory).use { check(!it.iterator().hasNext()) }
                        else credentialRootEntries(directory)
                        val lockPath = directory.resolve(CREDENTIAL_LOCK)
                        val options = mutableSetOf<OpenOption>(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                        if (create) options += StandardOpenOption.CREATE_NEW
                        val channel = FileChannel.open(lockPath, options,
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                        openedChannel = channel
                        credentialPrivateFile(lockPath)
                        val lock = channel.tryLock() ?: error("client.credentials.busy")
                        openedLock = lock
                        if (create) { channel.force(true); credentialForceDirectory(directory) }
                        val journal = JvmEncryptedClientCredentialJournal(view.owner, custody, directory, view.journalId,
                            view.keyId, view.processContextId, credentialFileKey(directory), credentialFileKey(lockPath), channel, lock)
                        if (create) journal.writeState(StoredClientCredentials())
                        else journal.readState().destroy()
                        journal.currentCustody()
                        JvmClientCredentialJournalOpenResult.Opened(journal)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { JvmClientCredentialJournalOpenResult.Unavailable }
                }
                handedOff = result is JvmClientCredentialJournalOpenResult.Opened
                return result
            } finally {
                if (!handedOff) withContext(NonCancellable + Dispatchers.IO) {
                    try { openedLock?.let { if (it.isValid) it.release() } } catch (_: Exception) { }
                    try { openedChannel?.close() } catch (_: Exception) { }
                }
            }
        }
    }
}

private const val CREDENTIAL_FILE = "sessions.v1.enc"
private const val CREDENTIAL_LOCK = "sessions.lock"
private const val CREDENTIAL_MAGIC = 0x55435331
private const val CREDENTIAL_OVERHEAD = 32

private fun credentialDirectory(path: Path) {
    check(path.isAbsolute && path.normalize() == path && path.toRealPath() == path && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
    check(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rwx------"))
}
private fun credentialPrivateFile(path: Path) {
    check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
    check(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) == PosixFilePermissions.fromString("rw-------"))
}
private fun credentialRootEntries(path: Path, temporary: String? = null) {
    val allowed = setOf(CREDENTIAL_FILE, CREDENTIAL_LOCK, temporary)
    Files.newDirectoryStream(path).use { files -> for (file in files) check(file.fileName.toString() in allowed) }
}
private fun credentialFileKey(path: Path): Any = Files.readAttributes(path, BasicFileAttributes::class.java,
    LinkOption.NOFOLLOW_LINKS).fileKey() ?: error("client.credentials.identity.unavailable")
private fun credentialForceDirectory(path: Path) { FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) } }
private fun credentialReadFile(path: Path, maximum: Int): ByteArray {
    credentialPrivateFile(path)
    return FileChannel.open(path, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { file ->
        val size = file.size()
        check(size in 1..maximum.toLong())
        val bytes = ByteArray(size.toInt())
        try {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(file.read(buffer) > 0)
            check(file.size() == size)
            bytes
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }
}

/** Fixed bounded binary writer avoids immutable String encodings of credentials and wipes its backing array. */
private fun encodeCredentials(state: StoredClientCredentials, owner: LocalClientDeviceIdentity,
    journalId: DomainId, keyId: DomainId): ByteArray {
    val buffer = ByteBuffer.allocate(MAX_CLIENT_CREDENTIAL_BYTES)
    try {
        buffer.putInt(state.formatVersion).putLong(state.revision)
        buffer.identity(owner); buffer.id(journalId); buffer.id(keyId)
        buffer.putInt(state.attempts.size)
        for (attempt in state.attempts) {
            buffer.id(attempt.id); buffer.text(attempt.login.value); buffer.binding(attempt.binding, state.formatVersion)
            buffer.put(if (attempt.explicitDeviceMove) 1.toByte() else 0.toByte())
        }
        buffer.putInt(state.sessions.size)
        for (session in state.sessions) {
            buffer.id(session.localId); buffer.id(session.identity.actorId); buffer.text(session.login.value)
            buffer.binding(session.binding, state.formatVersion); buffer.id(session.sessionId); buffer.putLong(session.generation.value)
            buffer.moment(session.accessExpiresAt); buffer.moment(session.workExpiresAt); buffer.text(session.phase.name)
            buffer.put(if (session.originalRevoked) 1.toByte() else 0.toByte())
            session.access.useBytes { buffer.put(it) }; session.refresh.useBytes { buffer.put(it) }
        }
        buffer.putInt(state.enrollments.size)
        for (entry in state.enrollments) buffer.text(journalJson.encodeToString(StoredClientEnrollmentV1.serializer(), entry))
        if (state.formatVersion >= 2) {
            buffer.putInt(state.administrations.size)
            for (entry in state.administrations) buffer.text(journalJson.encodeToString(StoredClientAdministrationV1.serializer(), entry))
        }
        return buffer.array().copyOf(buffer.position())
    } finally { buffer.array().fill(0) }
}

private fun decodeCredentials(bytes: ByteArray, owner: LocalClientDeviceIdentity, journalId: DomainId, keyId: DomainId): StoredClientCredentials {
    val sessions = ArrayList<StoredClientSessionV1>()
    try {
        val buffer = ByteBuffer.wrap(bytes)
        val version = buffer.int
        check(version in 1..3)
        val revision = buffer.long
        check(buffer.identity() == owner && buffer.id() == journalId && buffer.id() == keyId)
        val attempts = List(buffer.count(MAX_CLIENT_AUTH_RECORDS)) {
            StoredLoginAttemptV1(buffer.id(), LocalLoginIdentifier(buffer.text(256)), buffer.binding(version), buffer.flag())
        }
        repeat(buffer.count(MAX_CLIENT_AUTH_RECORDS - attempts.size)) {
            val localId = buffer.id(); val actor = buffer.id(); val login = LocalLoginIdentifier(buffer.text(256))
            val binding = buffer.binding(version); val sessionId = buffer.id(); val generation = SessionGeneration(buffer.long)
            val accessExpires = buffer.moment(); val workExpires = buffer.moment()
            val phase = StoredSessionPhaseV1.valueOf(buffer.text(32)); val revoked = buffer.flag()
            var access: OpaqueCredential? = null
            var refresh: OpaqueCredential? = null
            try {
                access = buffer.credential()
                refresh = buffer.credential()
                sessions += StoredClientSessionV1(localId, LocalClientIdentity(owner.scope, actor, owner.deviceId), login,
                    binding, sessionId, generation, accessExpires, workExpires, phase, revoked,
                    requireNotNull(access), requireNotNull(refresh))
                access = null
                refresh = null
            } finally { access?.destroy(); refresh?.destroy() }
        }
        val enrollments = List(buffer.count(MAX_CLIENT_ENROLLMENTS)) {
            journalJson.decodeFromString(StoredClientEnrollmentV1.serializer(), buffer.text(512 * 1024))
        }
        val administrations = if (version == 1) emptyList() else List(buffer.count(MAX_CLIENT_ADMINISTRATIONS)) {
            journalJson.decodeFromString(StoredClientAdministrationV1.serializer(), buffer.text(MAX_CLIENT_ADMINISTRATION_BYTES))
        }
        check(!buffer.hasRemaining())
        return StoredClientCredentials(revision, attempts, sessions, enrollments, administrations, version).also {
            it.validate(owner)
            if (version >= 2) encodeCredentials(it, owner, journalId, keyId).useWiped { canonical -> check(canonical.contentEquals(bytes)) }
        }
    } catch (failure: Throwable) { sessions.forEach { it.destroy() }; throw failure }
}

private fun ByteBuffer.text(value: String) { value.encodeToByteArray(throwOnInvalidSequence = true).useWiped { putInt(it.size); put(it) } }
private fun ByteBuffer.text(maximum: Int): String {
    val count = int
    check(count in 1..maximum && count <= remaining())
    return ByteArray(count).also(::get).useWiped { it.decodeToString(throwOnInvalidSequence = true) }
}
private fun ByteBuffer.id(value: DomainId) { text(value.value) }
private fun ByteBuffer.id(): DomainId = DomainId(text(64))
private fun ByteBuffer.binding(value: LocalClientAuthenticationBinding, version: Int) {
    if (version == 3) {
        put(value.credentialTagV3())
        id(value.credentialIdV3())
    } else {
        check(version in 1..2 && value is LocalClientAuthenticationBinding.MasterServiceIncarnation)
        id(value.serviceIncarnationId)
    }
}
private fun ByteBuffer.binding(version: Int): LocalClientAuthenticationBinding =
    if (version == 3) credentialBindingV3(get(), id())
    else {
        check(version in 1..2)
        LocalClientAuthenticationBinding.MasterServiceIncarnation(id())
    }
private fun ByteBuffer.identity(value: LocalClientDeviceIdentity) {
    id(value.scope.organizationId); id(value.scope.companyId); id(value.scope.branchId); id(value.deviceId)
}
private fun ByteBuffer.identity(): LocalClientDeviceIdentity = LocalClientDeviceIdentity(BranchScope(id(), id(), id()), id())
private fun ByteBuffer.moment(value: MonotonicMoment) { id(value.bootId); putLong(value.elapsedMillis) }
private fun ByteBuffer.moment(): MonotonicMoment = MonotonicMoment(id(), long)
private fun ByteBuffer.count(maximum: Int): Int = int.also { check(it in 0..maximum) }
private fun ByteBuffer.flag(): Boolean = when (get().toInt()) { 0 -> false; 1 -> true; else -> error("client.credentials.flag") }
private fun ByteBuffer.credential(): OpaqueCredential = ByteArray(52).also(::get).useWiped(::OpaqueCredential)
