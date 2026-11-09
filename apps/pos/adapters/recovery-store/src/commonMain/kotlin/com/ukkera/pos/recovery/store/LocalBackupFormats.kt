package com.ukkera.pos.recovery.store

import com.ukkera.pos.types.DomainId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val MAX_MANIFEST_BYTES = 64 * 1024
internal const val ARCHIVE_SUFFIX = ".ukkera-backup"

internal val backupJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

internal fun fail(reason: LocalBackupFailure): Nothing = throw LocalBackupException(reason)

internal fun requireShallowJson(text: String) {
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 32) fail(LocalBackupFailure.INVALID_ARCHIVE)
            '}', ']' -> if (--depth < 0) fail(LocalBackupFailure.INVALID_ARCHIVE)
        }
    }
    if (quoted || depth != 0) fail(LocalBackupFailure.INVALID_ARCHIVE)
}

/** Existing pin format: the authenticated archive hash, lowercase ASCII, followed by one LF. */
internal fun localBackupPinBytes(backup: VerifiedLocalBackup): ByteArray =
    (backup.archiveSha256 + "\n").encodeToByteArray()

internal fun pinnedLocalBackupId(name: String): DomainId {
    if (!name.endsWith(".pin")) fail(LocalBackupFailure.STORAGE_UNAVAILABLE)
    return DomainId(name.removeSuffix(".pin"))
}

/** The native adapter performs the bounded ASCII read; common validates the persisted format. */
internal fun requireLocalBackupPinHash(hash: String) {
    if (hash.length != 65 || hash.last() != '\n' || !isSha256(hash.dropLast(1))) {
        fail(LocalBackupFailure.STORAGE_UNAVAILABLE)
    }
}

@Serializable
internal data class PendingRestoreRecord(
    val version: Int,
    val request: ControlledLocalRestore,
    val backup: VerifiedLocalBackup,
    val installedHash: String,
    val previousBackup: VerifiedLocalBackup?,
) {
    init { require(isSha256(installedHash)) }
    fun receipt() = InstalledHeldRestore(request.backupId, request.incidentId, request.targetDeviceId,
        installedHash, previousBackup?.let { it.manifest.backupId.value + ARCHIVE_SUFFIX })
}
