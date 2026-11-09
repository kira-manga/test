package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path

// Independent finite test inventory: do not derive expected coverage from the production callback.
internal val REPLACEMENT_FACT_TABLES = listOf("branch_authority", "operation_result", "aggregate_state",
    "unique_claim", "journal_envelope", "audit_event", "outbox_intent")
internal val REPLACEMENT_PROTECTED_TABLES = listOf("protected_local_account", "protected_effective_policy",
    "protected_device_admission", "protected_login_throttle")
internal val REPLACEMENT_OLD_TABLES = REPLACEMENT_FACT_TABLES + REPLACEMENT_PROTECTED_TABLES
internal const val REPLACEMENT_ISSUANCE_TABLE = "protected_local_access_issuance"
internal val REPLACEMENT_ALL_TABLES = REPLACEMENT_OLD_TABLES + REPLACEMENT_ISSUANCE_TABLE
internal val REPLACEMENT_MUTABLE_TABLES = listOf("branch_authority", "aggregate_state", "unique_claim") +
    REPLACEMENT_PROTECTED_TABLES
internal val REPLACEMENT_EXPECTED_NAMES = REPLACEMENT_OLD_TABLES.map { "${it}_rowid_insert" } +
    REPLACEMENT_MUTABLE_TABLES.map { "${it}_identity_update" }

/**
 * A live Room fixture can start its asynchronous invalidation transaction AFTER
 * a writer borrow ends. Keep the sole Room connection borrowed, but OUTSIDE a
 * transaction, until the synchronous independent raw observer has closed. This
 * serializes this fixture's refresh work; it is NOT other-process fencing. No
 * busy retry, timeout tuning, uncommitted read or durability change is involved.
 */
internal suspend fun <T> observeReplacementRoom(database: RoomDatabase, read: () -> T): T =
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        read()
    }

/** Exact storage classes/cell bytes AND hidden rowids; omitted tables are genuinely absent. */
internal fun replacementRows(connection: SQLiteConnection, tables: List<String> = REPLACEMENT_ALL_TABLES): Map<String, List<List<String>>> =
    buildMap {
        for (table in tables) {
            check(table in REPLACEMENT_ALL_TABLES)
            val columns = connection.prepare("PRAGMA main.table_info(`$table`)").use {
                buildList { while (it.step()) add(it.getText(1)) }
            }
            if (columns.isEmpty()) continue
            val fields = listOf("rowid") + columns
            val projection = fields.joinToString(",") {
                "typeof(`$it`) || ':' || hex(CAST(`$it` AS BLOB))"
            }
            put(table, connection.prepare("SELECT $projection FROM `$table` ORDER BY rowid").use { statement ->
                buildList { while (statement.step()) add(fields.indices.map(statement::getText)) }
            })
        }
    }

internal fun rawReplacementRows(path: Path, tables: List<String> = REPLACEMENT_ALL_TABLES): Map<String, List<List<String>>> =
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("PRAGMA query_only=ON").use { it.step() }
        connection.prepare("BEGIN").use { it.step() }
        try { replacementRows(connection, tables) }
        finally { connection.prepare("ROLLBACK").use { it.step() } }
    }

internal fun replacementGuardRows(connection: SQLiteConnection): List<List<String>> = connection.prepare(
    "SELECT name,tbl_name,sql FROM main.sqlite_master WHERE type='trigger' ORDER BY name",
).use { statement -> buildList { while (statement.step()) add((0..2).map(statement::getText)) } }

internal fun rawReplacementGuards(path: Path): List<List<String>> =
    BundledSQLiteDriver().open(path.toString()).use { replacementGuardRows(it) }
