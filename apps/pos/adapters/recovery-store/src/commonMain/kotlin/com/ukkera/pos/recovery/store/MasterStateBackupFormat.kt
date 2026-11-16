package com.ukkera.pos.recovery.store

import com.ukkera.pos.types.DomainId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned Master full-state archive "UKMSB" v1. Additive to the legacy backup format.
 *
 * Layout: MAGIC(8) | u32 n | seal(manifest JSON, aad=MAGIC+"manifest") |
 * chunkCount x ( u32 n | seal(chunk, aad=MAGIC+manifestSha256+index(u64 BE)+last(1)) ).
 * Every byte after MAGIC is AEAD-sealed under the platform recovery custody key; chunk AAD binds
 * the exact manifest, position and final marker, so reordering, truncation, splicing and
 * appending are refused. A reader never accepts trailing bytes.
 */
@Serializable
public data class MasterStateBackupManifestV1(
    public val formatVersion: Int,
    public val backupId: String,
    public val sequence: Long,
    public val previousBackupId: String?,
    public val previousManifestSha256: String?,
    public val createdAtEpochMillis: Long,
    public val trigger: String,
    public val organizationId: String,
    public val companyId: String,
    public val branchId: String,
    public val installationId: String,
    public val schemaVersion: Int,
    public val roomIdentityHash: String,
    public val historyGeneration: Long,
    public val historyRecordDigest: String,
    public val snapshotBytes: Long,
    public val snapshotSha256: String,
    public val chunkBytes: Int,
    public val chunkCount: Long,
    public val keyId: String,
) {
    init {
        require(formatVersion == MasterStateBackupFormatV1.VERSION)
        DomainId(backupId); DomainId(organizationId); DomainId(companyId); DomainId(branchId); DomainId(installationId)
        require(sequence >= 1)
        require((sequence == 1L) == (previousBackupId == null) && (previousBackupId == null) == (previousManifestSha256 == null))
        previousBackupId?.let { DomainId(it) }
        previousManifestSha256?.let { require(isSha256(it)) }
        require(trigger in setOf(MasterBackupTrigger.SCHEDULED.name, MasterBackupTrigger.ON_DEMAND.name))
        require(schemaVersion > 0 && historyGeneration >= 0 && createdAtEpochMillis >= 0)
        require(roomIdentityHash.length == 32 && roomIdentityHash.all(::lowerHexChar))
        require(isSha256(historyRecordDigest) && isSha256(snapshotSha256))
        require(snapshotBytes in MIN_SNAPSHOT_BYTES..MAX_SNAPSHOT_BYTES)
        require(chunkBytes in MasterStateBackupFormatV1.MIN_CHUNK..MasterStateBackupFormatV1.MAX_CHUNK)
        require(chunkCount == (snapshotBytes + chunkBytes - 1) / chunkBytes)
        require(keyId.length in 1..128 && keyId.all { it.code in 0x21..0x7e })
    }
}

/** Durable newest-archive pointer used to detect deletion/rollback of the newest generation. */
@Serializable
internal data class MasterBackupChainHeadV1(
    val version: Int,
    val sequence: Long,
    val backupId: String,
    val manifestSha256: String,
    val createdAtEpochMillis: Long,
    val installationId: String,
    val historyGeneration: Long,
) {
    init {
        require(version == 1 && sequence >= 1 && isSha256(manifestSha256) && historyGeneration >= 0)
        DomainId(backupId); DomainId(installationId)
    }
}

/** HELD restore record written beside a restored candidate; it is never writer admission. */
@Serializable
internal data class MasterRestoreHeldRecordV1(
    val version: Int,
    val state: String,
    val backupId: String,
    val manifestSha256: String,
    val installationId: String,
    val approvalId: String,
    val restoredSha256: String,
    val historyGeneration: Long,
    val historyRecordDigest: String,
)

public object MasterStateBackupFormatV1 {
    public const val VERSION: Int = 1
    internal val MAGIC = byteArrayOf(0x55, 0x4b, 0x4d, 0x53, 0x42, 0x00, 0x00, 0x01) // "UKMSB" 0 0 1
    internal const val MIN_CHUNK = 4 * 1024
    internal const val MAX_CHUNK = 4 * 1024 * 1024
    internal const val MAX_MANIFEST = 16 * 1024
    internal const val MAX_SEAL_OVERHEAD = 256
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

    internal fun manifestAad(): ByteArray = MAGIC + "manifest".encodeToByteArray()

    internal fun chunkAad(manifestSha256: String, index: Long, last: Boolean): ByteArray =
        MAGIC + manifestSha256.encodeToByteArray() + ByteArray(8) { (index ushr (56 - it * 8)).toByte() } +
            byteArrayOf(if (last) 1 else 0)

    internal fun u32(value: Int): ByteArray {
        require(value >= 0)
        return byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    }

    internal fun readU32(bytes: ByteArray): Int {
        check(bytes.size == 4)
        val value = ((bytes[0].toInt() and 0xff) shl 24) or ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        if (value < 0) throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
        return value
    }

    internal fun encodeManifest(manifest: MasterStateBackupManifestV1): ByteArray =
        json.encodeToString(MasterStateBackupManifestV1.serializer(), manifest).encodeToByteArray()

    internal fun decodeManifest(bytes: ByteArray): MasterStateBackupManifestV1 {
        if (bytes.size > MAX_MANIFEST) throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
        val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: Exception) { throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE) }
        val manifest = try { json.decodeFromString(MasterStateBackupManifestV1.serializer(), text) }
        catch (_: Exception) { throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE) }
        // Canonical re-encoding only: a differently formatted equal manifest is refused.
        if (!encodeManifest(manifest).contentEquals(bytes)) throw MasterBackupFormatException(MasterBackupFailure.INVALID_ARCHIVE)
        return manifest
    }
}

internal class MasterBackupFormatException(val failure: MasterBackupFailure) : Exception(failure.name)
