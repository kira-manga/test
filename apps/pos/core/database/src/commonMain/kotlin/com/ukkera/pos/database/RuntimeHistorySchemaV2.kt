package com.ukkera.pos.database

import androidx.room3.RoomDatabase
import androidx.room3.Transactor
import androidx.sqlite.SQLiteConnection
import com.ukkera.pos.security.NativeHistoryOwnedSqlV2
import com.ukkera.pos.security.NativeHistoryPhysicalReadProfileV2
import com.ukkera.pos.security.NativeHistorySchemaItemV2
import com.ukkera.pos.security.OriginalNativeHistorySnapshot

/** Observed original-history limitations, not a static list of unrelated future features. */
internal enum class RuntimeHistoryReadHold {
    ORIGINAL_INSTALLATION_NATIVE_COMPLETION_UNRESOLVED,
    ORIGINAL_INSTALLATION_ISSUER_TERMINAL_JOIN_UNRESOLVED,
    NON_INSTALLATION_CAPTURED_AUTHORIZATION_FACTS_UNJOINED,
    STOCK_CANONICAL_CODEC_AND_ORIGINAL_PRODUCER_UNJOINED,
    RECOVERY_ORIGIN_AND_RESOURCE_CROSSWALKS_UNJOINED,
    FOOD_CURRENT_TYPED_CENSUS_WRITER_CLOSURE_UNJOINED,
    ORIGINAL_COHOST_DEVICE_REVOKED, // Historical proof is not permission for revoked-device Room/RUN startup.
}

/**
 * Source-fixed schema comparison, actually used by the original raw scanner. No CREATE,
 * migration, schema version bump, Room builder, identity publisher or accepting callback.
 * The exact V1/v6 definitions are READ and compared; none is changed or repurposed as Room7.
 */
internal object RuntimeHistorySchemaV2 {
    // This raw, read-only profile supports the EXACT structural inventory below, not a Room
    // opening/migration and not an arbitrary schema identified by a caller/native hash. Every
    // sqlite_schema row, column, index, FK and setting is checked before a physical scan.
    // Actual Room registration/generated identity, capture-guard integration, all-writer
    // admission and platform qualification remain separate activation work; a literal hash
    // guessed here (or their absence as a constant runtime hold) would prove none of them.

    internal data class ForeignKey(val target: String, val from: List<String>, val to: List<String>)
    internal fun foreignKeys(table: RuntimeHistoryTableV2): List<ForeignKey> = when (table) {
        RuntimeHistoryTableV2.BRANCH -> emptyList()
        RuntimeHistoryTableV2.JOURNAL, RuntimeHistoryTableV2.OUTBOX, RuntimeHistoryTableV2.JOURNAL_FRAGMENT ->
            listOf(ForeignKey("operation_result", listOf("scope", "operationId"), listOf("scope", "operationId")))
        RuntimeHistoryTableV2.STOCK_POSITION, RuntimeHistoryTableV2.STOCK_ALLOCATION,
        RuntimeHistoryTableV2.STOCK_RESERVATION, RuntimeHistoryTableV2.STOCK_VALUE ->
            listOf(ForeignKey("aggregate_state", listOf("scope", "recordKind", "recordId"), listOf("scope", "kind", "id")))
        else -> if (table.application) listOf(ForeignKey("branch_authority", listOf("scope"), listOf("scope"))) else emptyList()
    }
    private fun quote(name: String): String = "`" + name.replace("`", "``") + "`"
    private fun names(values: List<String>): String = values.joinToString(", ", transform = ::quote)
    internal fun rowIdPrimaryKey(table: RuntimeHistoryTableV2): Boolean = table.keyColumns.size == 1 &&
        historyColumnsV2(table).single { it.name == table.keyColumns.single() }.type == RuntimeSqliteTypeV2.INTEGER

    /** Exact candidate DDL strings are comparison inputs ONLY, never executable installers. */
    private fun tableSql(table: RuntimeHistoryTableV2): String {
        val columns = historyColumnsV2(table).joinToString(", ") {
            "${quote(it.name)} ${it.type.name}" + if (it.nullable) "" else " NOT NULL"
        }
        val foreign = foreignKeys(table).joinToString("") {
            ", FOREIGN KEY(${names(it.from)}) REFERENCES ${quote(it.target)}(${names(it.to)}) ON UPDATE NO ACTION ON DELETE NO ACTION "
        }
        return "CREATE TABLE ${quote(table.sqlName)} ($columns, PRIMARY KEY(${names(table.keyColumns)})$foreign)"
    }

    internal fun requiredObjects(): Map<String, RoomBranchInstallationSchemaProfile.Object> = buildMap {
        // Includes original17 tables, old exact guards/indices and room_master_table SQL,
        // NOT RoomBranchInstallationSchemaProfile.IDENTITY or its optional metadata grants.
        putAll(RoomBranchInstallationSchemaProfile.requiredMain)
        for (table in RuntimeHistoryTableV2.entries.filter { it.tag >= 18 }) {
            put(table.sqlName, RoomBranchInstallationSchemaProfile.Object("table", table.sqlName, tableSql(table)))
            if (!rowIdPrimaryKey(table)) put("sqlite_autoindex_${table.sqlName}_1",
                RoomBranchInstallationSchemaProfile.Object("index", table.sqlName, null))
            for (index in NativeHistoryPhysicalReadProfileV2.indices(table)) {
                put(index.name, RoomBranchInstallationSchemaProfile.Object("index", table.sqlName,
                    "CREATE ${if (index.unique) "UNIQUE " else ""}INDEX ${quote(index.name)} ON ${quote(table.sqlName)} (${names(index.columns)})"))
            }
        }
        for((name,guard) in NativeHistoryOwnedSqlV2.persistentGuards) {
            check(name !in this)
            put(name,RoomBranchInstallationSchemaProfile.Object(guard.type,guard.table,guard.sql))
        }
    }

    internal fun requireRoomIdentity(original: OriginalNativeHistorySnapshot, value: String) {
        // The completed FULL structure establishes raw-reader support; native equality binds
        // the observed bookkeeping to the original allocated DB. This never blesses a Room
        // builder or publishes a generated schema identity that has not actually been built.
        historyShapeV2(value.length == 32 && value.all { it in "0123456789abcdef" } &&
            value == requireNotNull(original.record.database).roomIdentityHash)
    }
}

/** Bounded FULL sqlite_schema, xinfo, index, FK and bookkeeping census; not a MAX/count grant. */
internal class RuntimeHistorySchemaCensusV2 {
    private val objects = linkedMapOf<String, NativeHistorySchemaItemV2.Object>()
    private val columns = linkedMapOf<String, MutableList<NativeHistorySchemaItemV2.Column>>()
    private val indices = linkedMapOf<String, MutableList<NativeHistorySchemaItemV2.Index>>()
    private val indexColumns = linkedMapOf<String, MutableList<NativeHistorySchemaItemV2.IndexColumn>>()
    private val foreign = linkedMapOf<String, MutableList<NativeHistorySchemaItemV2.ForeignKey>>()
    private val settings = linkedMapOf<String, String>()
    private var count = 0
    private var previousObjectRowId: Long? = null

    fun consume(item: NativeHistorySchemaItemV2) {
        historyCapacityV2(++count <= 16_384)
        when (item) {
            is NativeHistorySchemaItemV2.Object -> {
                historyCapacityV2(objects.size < RuntimeHistoryProfileV2.MAX_SCHEMA_OBJECTS)
                previousObjectRowId?.let { historyShapeV2(item.rowId > it) }
                previousObjectRowId = item.rowId
                historyShapeV2(objects.put(item.name, item) == null && item.type in setOf("table", "index", "trigger"))
                historyShapeV2(if (item.type == "trigger") item.rootPage == 0L else item.rootPage > 0L)
            }
            is NativeHistorySchemaItemV2.Column -> columns.getOrPut(item.table) { mutableListOf() }.add(item)
            is NativeHistorySchemaItemV2.Index -> indices.getOrPut(item.table) { mutableListOf() }.add(item)
            is NativeHistorySchemaItemV2.IndexColumn -> indexColumns.getOrPut(item.index) { mutableListOf() }.add(item)
            is NativeHistorySchemaItemV2.ForeignKey -> foreign.getOrPut(item.table) { mutableListOf() }.add(item)
            is NativeHistorySchemaItemV2.Setting -> historyShapeV2(settings.put(item.name, item.value) == null)
        }
    }

    fun finish(original: OriginalNativeHistorySnapshot) {
        val expected = RuntimeHistorySchemaV2.requiredObjects()
        // Refuse BEFORE claiming a complete fixed-table traversal of an unsupported inventory.
        // In particular, unknown persistent tables/triggers are not silently skipped while a
        // successful row-count check is mislabeled a full original schema census.
        historyShapeV2(objects.keys == expected.keys)
        for ((name, value) in expected) {
            val actual = objects.getValue(name)
            historyShapeV2(actual.type == value.type && actual.table == value.table &&
                RoomBranchInstallationSchemaProfile.normalized(actual.sql) == RoomBranchInstallationSchemaProfile.normalized(value.sql))
        }
        val roots = objects.values.filter { it.rootPage != 0L }.map { it.rootPage }
        historyShapeV2(roots.distinct().size == roots.size)
        val tables = RuntimeHistoryTableV2.entries
        historyShapeV2(columns.keys == tables.map { it.sqlName }.toSet() + "room_master_table")
        for (table in tables) {
            val actual = columns.getValue(table.sqlName)
            val declared = historyColumnsV2(table)
            historyShapeV2(actual.size == declared.size)
            for ((ordinal, pair) in declared.zip(actual).withIndex()) {
                val (want, got) = pair
                historyShapeV2(got.ordinal == ordinal && got.name == want.name && got.declaredType == want.type.name &&
                    got.notNull != want.nullable && got.defaultSql == null && got.hidden == 0 &&
                    got.primaryKeyOrdinal == table.keyColumns.indexOf(want.name) + 1)
            }
            requireIndices(table)
            requireForeignKeys(table)
        }
        val room = columns.getValue("room_master_table")
        historyShapeV2(room.size == 2 && room[0] == NativeHistorySchemaItemV2.Column("room_master_table", 0, "id", "INTEGER", false, null, 1, 0) &&
            room[1] == NativeHistorySchemaItemV2.Column("room_master_table", 1, "identity_hash", "TEXT", false, null, 0, 0))
        historyShapeV2(indices["room_master_table"].isNullOrEmpty() && foreign["room_master_table"].isNullOrEmpty())
        val allExpectedIndices = indices.values.flatten().map { it.name }.toSet()
        historyShapeV2(indexColumns.keys == allExpectedIndices)
        historyShapeV2(settings.keys == setOf("room.identity_hash", "user_version", "application_id") &&
            settings["user_version"] == RuntimeHistoryProfileV2.PROPOSED_ROOM_VERSION.toString() && settings["application_id"] == "0")
        RuntimeHistorySchemaV2.requireRoomIdentity(original, settings.getValue("room.identity_hash"))
    }

    private fun requireIndices(table: RuntimeHistoryTableV2) {
        val actual = indices[table.sqlName].orEmpty()
        val ordinary = NativeHistoryPhysicalReadProfileV2.indices(table)
        val primaryName = if (RuntimeHistorySchemaV2.rowIdPrimaryKey(table)) null else "sqlite_autoindex_${table.sqlName}_1"
        historyShapeV2(actual.map { it.ordinal } == actual.indices.toList() &&
            actual.map { it.name }.toSet() == ordinary.map { it.name }.toSet() + listOfNotNull(primaryName) &&
            actual.map { it.name }.distinct().size == actual.size)
        for (index in actual) {
            val primary = index.name == primaryName
            val spec = ordinary.singleOrNull { it.name == index.name }
            val expectedColumns = if (primary) table.keyColumns else requireNotNull(spec).columns
            historyShapeV2(index.unique == (primary || requireNotNull(spec).unique) && index.origin == (if (primary) "pk" else "c") && !index.partial)
            val parts = indexColumns[index.name].orEmpty()
            historyShapeV2(parts.size == expectedColumns.size + 1 && parts.map { it.ordinal } == parts.indices.toList())
            for ((ordinal, name) in expectedColumns.withIndex()) {
                val part = parts[ordinal]
                historyShapeV2(part.name == name && part.key && !part.descending && part.collation == "BINARY" &&
                    part.columnOrdinal == historyColumnsV2(table).indexOfFirst { it.name == name })
            }
            val suffix = parts.last()
            historyShapeV2(!suffix.key && suffix.columnOrdinal == -1 && suffix.name == null && !suffix.descending && suffix.collation == "BINARY")
        }
    }

    private fun requireForeignKeys(table: RuntimeHistoryTableV2) {
        val actual = foreign[table.sqlName].orEmpty().groupBy { it.id }
        val expected = RuntimeHistorySchemaV2.foreignKeys(table)
        historyShapeV2(actual.keys.sorted() == expected.indices.toList())
        for ((id, spec) in expected.withIndex()) {
            val parts = actual.getValue(id)
            historyShapeV2(parts.size == spec.from.size)
            for ((ordinal, part) in parts.withIndex()) historyShapeV2(part.ordinal == ordinal && part.targetTable == spec.target &&
                part.from == spec.from[ordinal] && part.to == spec.to[ordinal] && part.onUpdate == "NO ACTION" &&
                part.onDelete == "NO ACTION" && part.match == "NONE")
        }
    }
}


/** Only the original fresh builder installs this. Existing openings compare all main objects
 * exactly and never repair a missing guard. Generated Room identity remains tooling-owned. */
internal object RuntimeHistoryOwnedSchemaCallbackV2 : RoomDatabase.Callback() {
    override suspend fun onCreate(connection: SQLiteConnection) {
        for(guard in NativeHistoryOwnedSqlV2.persistentGuards.values) connection.prepare(checkNotNull(guard.sql)).use { check(!it.step()) }
    }
    override suspend fun onOpen(connection: SQLiteConnection) {
        val expected=RuntimeHistorySchemaV2.requiredObjects()
        val remaining=expected.keys.toMutableSet()
        connection.prepare("SELECT type,name,tbl_name,sql FROM main.sqlite_schema ORDER BY rowid").use { statement ->
            var count=0
            while(statement.step()) {
                check(++count<=RuntimeHistoryProfileV2.MAX_SCHEMA_OBJECTS)
                val type=statement.getText(0); val name=statement.getText(1); val table=statement.getText(2)
                val sql=if(statement.isNull(3)) null else statement.getText(3)
                val want=checkNotNull(expected[name])
                check(remaining.remove(name) && type==want.type && table==want.table &&
                    NativeHistoryOwnedSqlV2.normalized(sql)==NativeHistoryOwnedSqlV2.normalized(want.sql))
            }
        }
        check(remaining.isEmpty())
    }
}
