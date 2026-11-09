package com.ukkera.pos.database

import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path

/** Historical twelve-entity v4 for unchanged row-replacement guard assertions.
 * This is not a schema-7 opener, a migration to owned history, or a native owner. */
@Database(
    entities = [BranchRow::class, OperationRow::class, AggregateRow::class, ClaimRow::class,
        JournalRow::class, AuditRow::class, OutboxRow::class, ProtectedLocalAccountRow::class,
        ProtectedEffectivePolicyRow::class, ProtectedDeviceAdmissionRow::class,
        ProtectedLoginThrottleRow::class, ProtectedLocalAccessIssuanceRow::class],
    version = 4,
    exportSchema = false,
)
internal abstract class LegacyV4MasterDatabase : RoomDatabase() {
    abstract fun facts(): MasterDao
}

internal fun buildLegacyV4MasterRoom(
    path: Path,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): LegacyV4MasterDatabase = buildDurableRoom(
    Room.databaseBuilder<LegacyV4MasterDatabase>(path.toString(), ::LegacyV4MasterDatabase_Impl)
        .addMigrations(MasterReplacementMigration1To4, MasterReplacementMigration2To4,
            MasterReplacementMigration3To4)
        .addCallback(ProtectedLocalSchemaCallback)
        .addCallback(LocalAccessIssuanceSchemaCallback)
        .addCallback(MasterReplacementGuardsCallback),
    driver,
)
