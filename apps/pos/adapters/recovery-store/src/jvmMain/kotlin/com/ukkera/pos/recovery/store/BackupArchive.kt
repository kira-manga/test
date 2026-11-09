package com.ukkera.pos.recovery.store

import com.ukkera.pos.security.KeyProtectionStatus
import com.ukkera.pos.types.DomainId
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private val MAGIC = "UKKERA-LOCAL-BACKUP\u0000".toByteArray(Charsets.US_ASCII)
private const val TAG_BYTES = 16
private const val NONCE_BYTES = 12
private const val WRAPPED_KEY_BYTES = 32 + TAG_BYTES

/**
 * AES-256-GCM data records, each at most 256 KiB. A new random 256-bit data key per archive
 * makes its counter nonces unique. The protected wrapping key sees one random 96-bit nonce
 * per backup; provider key lifetime/rotation remains an explicit custody prerequisite.
 * Bounded doFinal calls authenticate each chunk before it reaches private staging. Manifest
 * length/hash plus the authenticated final record reject truncation, reordering and appending.
 */
internal class BackupArchive(
    private val configuration: JvmLocalBackupConfiguration,
    private val keys: ProtectedBackupKeyProvider,
) {
    private val random = SecureRandom()
    val maximumArchiveBytes: Long = configuration.maxDatabaseBytes +
        (configuration.maxDatabaseBytes / TRANSFER_BYTES + 2) * 32 + MAX_MANIFEST_BYTES + 4096

    suspend fun write(snapshot: Path, manifest: LocalBackupManifest, target: Path) {
        checkManifest(manifest, manifest.backupId)
        if (configuration.policyConfiguration.readProfile(manifest).schemaVersion != configuration.schema.schemaVersion) {
            fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        }
        val plainManifest = backupJson.encodeToString(manifest).encodeToByteArray()
        if (plainManifest.size > MAX_MANIFEST_BYTES) fail(LocalBackupFailure.CAPACITY_EXCEEDED)
        val prefix = headerPrefix(manifest.backupId, configuration.activeKeyId)
        val wrappingNonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val dataKeyBytes = ByteArray(32).also(random::nextBytes)
        val dataKey = SecretKeySpec(dataKeyBytes, "AES")
        val wrapped = try {
            withProtectedKey(configuration.activeKeyId) { key ->
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, wrappingNonce))
                    updateAAD(prefix)
                }.doFinal(dataKeyBytes)
            }
        } finally { dataKeyBytes.fill(0) }
        if (wrapped.size != WRAPPED_KEY_BYTES) fail(LocalBackupFailure.KEY_UNAVAILABLE)
        val header = prefix + wrappingNonce + wrapped
        val headerHash = MessageDigest.getInstance("SHA-256").digest(header)
        val sourceHash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(TRANSFER_BYTES)
        try {
            privateNewFile(target).use { channel ->
                DataOutputStream(Channels.newOutputStream(channel)).use { output ->
                    output.write(header)
                    writeFrame(output, dataKey, headerHash, 0, 0, plainManifest)
                    var remaining = manifest.databaseBytes
                    var index = 1L
                    inputFile(snapshot).use { input ->
                        while (remaining > 0) {
                            currentCoroutineContext().ensureActive()
                            val wanted = minOf(remaining, TRANSFER_BYTES.toLong()).toInt()
                            var count = 0
                            while (count < wanted) {
                                val received = input.read(buffer, count, wanted - count)
                                if (received < 0) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
                                if (received > 0) count += received
                            }
                            sourceHash.update(buffer, 0, count)
                            writeFrame(output, dataKey, headerHash, index++, 1, buffer, count)
                            remaining -= count
                        }
                        if (input.read() != -1) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
                    }
                    if (sourceHash.digest().hex() != manifest.databaseSha256) fail(LocalBackupFailure.DATABASE_INTEGRITY_UNAVAILABLE)
                    writeFrame(output, dataKey, headerHash, index, 2, ByteArray(0))
                    output.flush()
                    channel.force(true)
                }
            }
        } finally { buffer.fill(0); plainManifest.fill(0) }
    }

    /** Never invokes the security helper or exposes a staged filename before full authentication. */
    suspend fun read(source: Path, expectedId: DomainId, destination: Path? = null): VerifiedLocalBackup {
        if (requireRegular(source).size() > maximumArchiveBytes) fail(LocalBackupFailure.CAPACITY_EXCEEDED)
        var output: FileChannel? = null
        try {
            // Hash precisely the bytes whose frames authenticate, through the same open handle.
            // Reopening the pathname afterwards could bind metadata to a different replacement.
            val archiveDigest = MessageDigest.getInstance("SHA-256")
            val manifest = DataInputStream(DigestInputStream(inputFile(source), archiveDigest)).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                if (!magic.contentEquals(MAGIC) || input.readInt() != 1) fail(LocalBackupFailure.INVALID_ARCHIVE)
                val id = readId(input)
                val keyId = readId(input)
                if (id != expectedId || keyId !in configuration.permittedReadKeyIds) fail(LocalBackupFailure.INVALID_ARCHIVE)
                val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
                val wrapped = ByteArray(WRAPPED_KEY_BYTES).also(input::readFully)
                val prefix = headerPrefix(id, keyId)
                val keyBytes = withProtectedKey(keyId) { key ->
                    Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD(prefix)
                    }.doFinal(wrapped)
                }
                val dataKey = try {
                    if (keyBytes.size != 32) fail(LocalBackupFailure.INVALID_ARCHIVE)
                    SecretKeySpec(keyBytes, "AES")
                } finally { keyBytes.fill(0) }
                val headerHash = MessageDigest.getInstance("SHA-256").digest(prefix + nonce + wrapped)
                val manifestBytes = readFrame(input, dataKey, headerHash, 0, 0, MAX_MANIFEST_BYTES)
                val decoded = try {
                    decodeCanonicalManifest(manifestBytes).also { checkManifest(it, expectedId) }
                } finally { manifestBytes.fill(0) }
                output = destination?.let(::privateNewFile)
                val digest = MessageDigest.getInstance("SHA-256")
                var remaining = decoded.databaseBytes
                var index = 1L
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val expected = minOf(remaining, TRANSFER_BYTES.toLong()).toInt()
                    val plain = readFrame(input, dataKey, headerHash, index++, 1, expected)
                    try {
                        if (plain.size != expected) fail(LocalBackupFailure.INVALID_ARCHIVE)
                        digest.update(plain)
                        output?.let { writeFully(it, plain) }
                        remaining -= plain.size
                    } finally { plain.fill(0) }
                }
                if (readFrame(input, dataKey, headerHash, index, 2, 0).isNotEmpty() || input.read() != -1 ||
                    digest.digest().hex() != decoded.databaseSha256) fail(LocalBackupFailure.INVALID_ARCHIVE)
                output?.force(true)
                decoded
            }
            return VerifiedLocalBackup(manifest, archiveDigest.digest().hex())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: LocalBackupException) {
            throw failure
        } catch (_: Exception) {
            fail(LocalBackupFailure.INVALID_ARCHIVE)
        } finally { output?.close() }
    }

    private fun checkManifest(manifest: LocalBackupManifest, expectedId: DomainId) {
        if (manifest.backupId != expectedId || manifest.databaseBytes > configuration.maxDatabaseBytes) fail(LocalBackupFailure.INVALID_ARCHIVE)
        if (manifest.scope != configuration.scope) fail(LocalBackupFailure.WRONG_SCOPE)
        configuration.policyConfiguration.readProfile(manifest)
    }

    private suspend fun <T> withProtectedKey(id: DomainId, action: (javax.crypto.SecretKey) -> T): T {
        try {
            if (keys.protectionStatus != KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT) fail(LocalBackupFailure.KEY_UNAVAILABLE)
            val result = keys.withKey(id) { key ->
                if (key.algorithm != "AES") fail(LocalBackupFailure.KEY_UNAVAILABLE)
                action(key)
            }
            if (keys.protectionStatus != KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT) fail(LocalBackupFailure.KEY_UNAVAILABLE)
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { fail(LocalBackupFailure.KEY_UNAVAILABLE) }
    }
}

private fun headerPrefix(id: DomainId, keyId: DomainId): ByteArray = ByteArrayOutputStream().use { buffer ->
    DataOutputStream(buffer).use {
        it.write(MAGIC); it.writeInt(1)
        it.write(id.value.toByteArray(Charsets.US_ASCII)); it.write(keyId.value.toByteArray(Charsets.US_ASCII))
    }
    buffer.toByteArray()
}

private fun readId(input: DataInputStream): DomainId = DomainId(
    ByteArray(36).also(input::readFully).toString(Charsets.US_ASCII),
)

private fun cipher(mode: Int, key: SecretKeySpec, header: ByteArray, index: Long, kind: Int, size: Int): Cipher =
    Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key, GCMParameterSpec(128, ByteBuffer.allocate(12).putInt(0).putLong(index).array()))
        updateAAD(header)
        updateAAD(ByteBuffer.allocate(16).putLong(index).putInt(kind).putInt(size).array())
    }

private fun writeFrame(
    output: DataOutputStream, key: SecretKeySpec, header: ByteArray, index: Long, kind: Int,
    plain: ByteArray, size: Int = plain.size,
) {
    val sealed = cipher(Cipher.ENCRYPT_MODE, key, header, index, kind, size).doFinal(plain, 0, size)
    output.writeInt(size); output.write(sealed)
}

private fun readFrame(
    input: DataInputStream, key: SecretKeySpec, header: ByteArray, index: Long, kind: Int, maximum: Int,
): ByteArray {
    val size = input.readInt()
    if (size < 0 || size > maximum) fail(LocalBackupFailure.INVALID_ARCHIVE)
    val sealed = ByteArray(size + TAG_BYTES).also(input::readFully)
    return cipher(Cipher.DECRYPT_MODE, key, header, index, kind, size).doFinal(sealed)
}

private fun decodeCanonicalManifest(bytes: ByteArray): LocalBackupManifest {
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    requireShallowJson(text)
    val result = backupJson.decodeFromString<LocalBackupManifest>(text)
    if (backupJson.encodeToString(result) != text) fail(LocalBackupFailure.INVALID_ARCHIVE)
    return result
}
