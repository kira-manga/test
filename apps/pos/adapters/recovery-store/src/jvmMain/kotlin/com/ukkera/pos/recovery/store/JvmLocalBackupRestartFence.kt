package com.ukkera.pos.recovery.store

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * A refusal check for the trusted host BEFORE any Room builder. Absence is NOT writer authority,
 * clean custody, complete history or permission to clear a separate supervisor restore intent.
 * No marker parsing, deletion or repair is offered. Call only under actual exclusive custody.
 */
public object JvmLocalBackupRestartFence {
    public fun requireNoPendingRestore(databasePath: Path): Unit {
        try {
            if (!databasePath.isAbsolute || databasePath != databasePath.normalize() || databasePath.parent == null) {
                fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
            }
            privateDirectory(databasePath.parent)
            val marker = localRestoreMarkerPath(databasePath)
            if (existingMarkerPath(marker.parent)) {
                privateDirectory(marker.parent)
                if (existingMarkerPath(marker)) fail(LocalBackupFailure.RESTORE_ALREADY_PENDING)
            }
        } catch (failure: LocalBackupException) { throw failure }
        catch (_: Exception) { fail(LocalBackupFailure.STORAGE_UNAVAILABLE) }
    }

    /**
     * Read-only anti-migration preflight on an EXISTING stopped database. This is not whole-DDL,
     * row-integrity or native qualification. Never creates a missing DB or repairs a hot journal.
     * The host still needs its own durable startup gate, actual custody and normal core validation.
     */
    public fun requireExactRoomIdentity(databasePath: Path, profile: SqliteSnapshotProfile): Unit {
        try {
            requireNoPendingRestore(databasePath)
            requireRegular(databasePath)
            assertNoSqliteSidecars(databasePath)
            BundledSQLiteDriver().open(databasePath.toString(), 1 /* SQLITE_OPEN_READONLY */).use { connection ->
                connection.prepare("PRAGMA main.user_version").use {
                    if (!it.step() || it.getLong(0) != profile.schemaVersion.toLong() || it.step()) {
                        fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
                    }
                }
                connection.prepare("SELECT count(*), COALESCE(sum(CASE WHEN typeof(id)='integer' AND id=42 " +
                    "AND typeof(identity_hash)='text' AND CAST(identity_hash AS BLOB)=CAST(? AS BLOB) " +
                    "THEN 1 ELSE 0 END),0) FROM room_master_table").use {
                    it.bindText(1, profile.roomIdentityHash)
                    if (!it.step() || it.getLong(0) != 1L || it.getLong(1) != 1L || it.step()) {
                        fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
                    }
                }
            }
        } catch (failure: LocalBackupException) { throw failure }
        catch (_: Exception) { fail(LocalBackupFailure.UNSUPPORTED_DATABASE) }
    }
}

/** Only known absence is safe; access/metadata failures remain unavailable. */
private fun existingMarkerPath(path: Path): Boolean = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    true
} catch (_: NoSuchFileException) { false }

internal fun localRestoreMarkerPath(database: Path): Path = database.parent.resolve(".ukkera-local-recovery")
    .resolve("restore-${sha256(database.fileName.toString().encodeToByteArray())}.json")
