package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.migration.Migration
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.security.LocalAccessIssuance
import com.ukkera.pos.security.SessionStatus
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Genuine separately generated old versions and disposable synthetic files, never a live upgrade. */
class MasterReplacementMigrationTest {
    @Test fun freshFourAndReopenHaveTwelveTablesFiftyOneGuardsAndNoProvisionedState() = runTest {
        val path = replacementMigrationPath()
        val current = replacementCurrent(path)
        val guards: List<List<String>>
        try {
            assertReplacementSchema(current, 4)
            guards = observeReplacementRoom(current) { rawReplacementGuards(path) }
            observeReplacementRoom(current) {
                assertEquals(REPLACEMENT_ALL_TABLES.toSet(), rawReplacementRows(path).keys)
                assertTrue(rawReplacementRows(path).values.all { it.isEmpty() })
            }
        } finally { current.close() }
        val metadata = rawMasterMigrationMetadata(path)
        val reopened = replacementCurrent(path)
        try {
            assertReplacementSchema(reopened, 4)
            observeReplacementRoom(reopened) {
                assertTrue(rawReplacementRows(path).values.all { it.isEmpty() })
                assertEquals(guards, rawReplacementGuards(path))
            }
        } finally { reopened.close() }
        assertEquals(metadata, rawMasterMigrationMetadata(path))
    }

    @Test fun genuineVersionThreeHasFrozenIdentityAndGuardsAndPopulatedEncryptedIssuance() = runTest {
        val emptyPath = replacementMigrationPath()
        val empty = buildLegacyV3MasterRoom(emptyPath)
        try {
            assertReplacementSchema(empty, 3)
            observeReplacementRoom(empty) { assertTrue(rawReplacementRows(emptyPath).values.all { it.isEmpty() }) }
        } finally { empty.close() }
        val fixture = LegacyV3MasterFixture()
        try {
            fixture.seed()
            assertReplacementSchema(fixture.database, 3)
            val row = assertNotNull(fixture.database.localAccessIssuance().read(
                TEST_SCOPE.storageKey(), fixture.issuance.sessionId.value))
            assertEquals(0L, row.revision)
            observeReplacementRoom(fixture.database) {
                assertEquals(1, rawReplacementRows(fixture.path).getValue(REPLACEMENT_ISSUANCE_TABLE).size)
            }
        } finally { fixture.close() }
        val rows = rawReplacementRows(fixture.path)
        val metadata = rawMasterMigrationMetadata(fixture.path)
        val reopened = buildLegacyV3MasterRoom(fixture.path)
        try {
            assertReplacementSchema(reopened, 3)
            observeReplacementRoom(reopened) { assertEquals(rows, rawReplacementRows(fixture.path)) }
        } finally { reopened.close() }
        assertEquals(metadata, rawMasterMigrationMetadata(fixture.path))
    }

    @Test fun allThreeDirectRoutesPreserveExactOldCellsRowidsDdlIndexesAndGuardBodies() = runTest {
        val reference = replacementFreshReference()
        for (version in 1..3) {
            val source = replacementSeed(version, unusualRowids = true)
            assertTrue(source.rows.values.flatten().all { it.first() !in listOf("integer:31", "integer:32") },
                "Every stored rowid must actually differ from the ordinary one/two seed")
            assertTrue(source.rows.values.flatten().any { it.first().startsWith("integer:2D") }, "Include negative rowids")
            val probe = ReplacementCreateProbe()
            val current = replacementCurrent(source.path, probe)
            try {
                assertReplacementSchema(current, 4)
                assertReplacementSourcePreserved(source, current)
                observeReplacementRoom(current) { assertEquals(reference.guards, rawReplacementGuards(source.path)) }
                assertReplacementProtectedValues(current, source)
                assertReplacementSourcePreserved(source, current)
                observeReplacementRoom(current) {
                    for (table in REPLACEMENT_ALL_TABLES - source.rows.keys) {
                        assertTrue(rawReplacementRows(source.path).getValue(table).isEmpty(), "No automatic $table backfill")
                    }
                }
            } finally { current.close() }
            assertReplacementHealthyDdl(probe, version)
            val metadata = rawMasterMigrationMetadata(source.path)
            val reopened = replacementCurrent(source.path)
            try {
                assertReplacementSchema(reopened, 4)
                assertReplacementProtectedValues(reopened, source)
                assertReplacementSourcePreserved(source, reopened)
            } finally { reopened.close() }
            assertEquals(metadata, rawMasterMigrationMetadata(source.path))
        }
    }

    @Test fun allSourceIdentityCorruptionsAreRefusedBeforeAnyCreatePreparationOrStep() = runTest {
        for (version in 1..3) {
            val template = replacementSeed(version)
            for ((label, sql) in replacementIdentityCorruptions(replacementIdentity(version))) {
                val source = replacementCopy(template)
                replacementRawSql(source.path, *sql.toTypedArray())
                val metadata = rawMasterMigrationMetadata(source.path)
                val probe = ReplacementCreateProbe()
                assertReplacementOpenRefused(source.path, probe)
                assertReplacementNoCreate(probe, "v$version $label")
                assertEquals(source.rows, rawReplacementRows(source.path))
                assertEquals(metadata, rawMasterMigrationMetadata(source.path), "Do not repair/restamp v$version $label")
            }
        }
    }

    @Test fun eachRequiredOldGuardMissingChangedOrMovedIsRefusedBeforeAnyCreate() = runTest {
        for (version in 1..3) {
            val template = replacementSeed(version)
            for (guard in template.guards) for (corruption in listOf("missing", "changed", "wrong-table")) {
                val source = replacementCopy(template)
                val name = guard[0]
                replacementRawSql(source.path, "DROP TRIGGER " + replacementQuote(name))
                when (corruption) {
                    "changed" -> replacementRawSql(source.path, guard[2].substringBefore("BEGIN") + "BEGIN SELECT 1; END")
                    "wrong-table" -> {
                        val wrongTable = if (guard[1] == "branch_authority") "aggregate_state" else "branch_authority"
                        replacementRawSql(source.path, "CREATE TRIGGER " + replacementQuote(name) +
                            " BEFORE INSERT ON $wrongTable BEGIN SELECT 1; END")
                    }
                }
                val metadata = rawMasterMigrationMetadata(source.path)
                val probe = ReplacementCreateProbe()
                assertReplacementOpenRefused(source.path, probe)
                assertReplacementNoCreate(probe, "v$version $name $corruption")
                assertEquals(source.rows, rawReplacementRows(source.path))
                assertEquals(metadata, rawMasterMigrationMetadata(source.path))
            }
        }
    }

    @Test fun uncorrectedVersionThreeIssuanceBodyIsNotAdoptedDespiteItsMatchingRoomIdentity() = runTest {
        val source = replacementSeed(3)
        val name = REPLACEMENT_ISSUANCE_TABLE + "_no_replace"
        val original = source.guards.single { it[0] == name }[2]
        val uncorrected = original.replace(" OR rowid=NEW.rowid", "")
        assertNotEquals(original, uncorrected)
        replacementRawSql(source.path, "DROP TRIGGER $name", uncorrected)
        val metadata = rawMasterMigrationMetadata(source.path)
        val probe = ReplacementCreateProbe()
        assertReplacementOpenRefused(source.path, probe)
        assertReplacementNoCreate(probe, "uncorrected development v3 issuance guard")
        assertEquals(source.rows, rawReplacementRows(source.path))
        assertEquals(metadata, rawMasterMigrationMetadata(source.path))
    }

    @Test fun everyReservedNameInEitherCaseAndEveryMainObjectTypeStopsEvenInheritedCreate() = runTest {
        for (version in 1..3) {
            val template = replacementSeed(version)
            for (reserved in REPLACEMENT_EXPECTED_NAMES) for (mixedCase in listOf(false, true)) {
                val name = if (mixedCase) replacementMixedCase(reserved) else reserved
                if (mixedCase) assertNotEquals(reserved, name)
                for (kind in listOf("table", "view", "index", "trigger")) {
                    val source = replacementCopy(template)
                    val quoted = replacementQuote(name)
                    val sql = when (kind) {
                        "table" -> listOf("CREATE TABLE $quoted (marker TEXT NOT NULL)",
                            "INSERT INTO $quoted VALUES ('synthetic-preserve')")
                        "view" -> listOf("CREATE VIEW $quoted AS SELECT 'synthetic-preserve' AS marker")
                        "index" -> listOf("CREATE INDEX $quoted ON branch_authority(deviceId)")
                        else -> {
                            val actualTable = if (reserved.startsWith("branch_authority_")) "aggregate_state" else "branch_authority"
                            listOf("CREATE TRIGGER $quoted BEFORE INSERT ON $actualTable BEGIN SELECT 1; END")
                        }
                    }
                    replacementRawSql(source.path, *sql.toTypedArray())
                    val metadata = rawMasterMigrationMetadata(source.path)
                    val probe = ReplacementCreateProbe()
                    assertReplacementOpenRefused(source.path, probe)
                    assertReplacementNoCreate(probe, "v$version reserved $name/$kind")
                    assertEquals(source.rows, rawReplacementRows(source.path))
                    assertEquals(metadata, rawMasterMigrationMetadata(source.path))
                    if (kind in listOf("table", "view")) assertEquals("synthetic-preserve",
                        replacementText(source.path, "SELECT marker FROM $quoted"))
                }
            }
        }
    }

    @Test fun evenAnExactCorrectPreexistingNewGuardIsRefusedRatherThanAdopted() = runTest {
        val guards = replacementFreshReference().guards.filter { it[0] in REPLACEMENT_EXPECTED_NAMES }
        assertEquals(18, guards.size)
        for (version in 1..3) {
            val template = replacementSeed(version)
            val applicable = guards.filter { it[1] in template.rows.keys }
            assertEquals(if (version == 1) 10 else 18, applicable.size)
            for (guard in applicable) {
                val source = replacementCopy(template)
                replacementRawSql(source.path, guard[2])
                val metadata = rawMasterMigrationMetadata(source.path)
                val probe = ReplacementCreateProbe()
                assertReplacementOpenRefused(source.path, probe)
                assertReplacementNoCreate(probe, "v$version exact existing " + guard[0])
                assertEquals(source.rows, rawReplacementRows(source.path))
                assertEquals(metadata, rawMasterMigrationMetadata(source.path))
            }
        }
    }

    @Test fun everyDirectEntryRejectsWrongUserVersionBeforeAnyCreateWithoutRestamping() = runTest {
        for (version in 1..3) {
            val template = replacementSeed(version)
            for (wrongVersion in (0..4).filter { it != version }) {
                val source = replacementCopy(template)
                replacementRawSql(source.path, "PRAGMA user_version=$wrongVersion")
                val metadata = rawMasterMigrationMetadata(source.path)
                val probe = ReplacementCreateProbe()
                probe.open(source.path.toString()).use { connection ->
                    replacementProfile(connection)
                    replacementExecute(connection, "BEGIN IMMEDIATE")
                    try {
                        assertFailsWith<IllegalStateException> { replacementMigration(version).migrate(connection) }
                    } finally { replacementExecute(connection, "ROLLBACK") }
                }
                assertReplacementNoCreate(probe, "v$version entry with version $wrongVersion")
                assertEquals(source.rows, rawReplacementRows(source.path))
                assertEquals(metadata, rawMasterMigrationMetadata(source.path))
            }
        }
    }

    @Test fun strictThreeEntryRejectsNonDurableAndMemoryProfilesWithoutRepair() = runTest {
        val template = replacementSeed(3)
        for (wrongJournal in listOf(false, true)) {
            val source = replacementCopy(template)
            val metadata = rawMasterMigrationMetadata(source.path)
            val probe = ReplacementCreateProbe()
            probe.open(source.path.toString()).use { connection ->
                replacementProfile(connection)
                replacementExecute(connection, if (wrongJournal) "PRAGMA journal_mode=DELETE" else "PRAGMA synchronous=NORMAL")
                replacementExecute(connection, "BEGIN IMMEDIATE")
                try { assertFailsWith<IllegalStateException> { MasterReplacementMigration3To4.migrate(connection) } }
                finally { replacementExecute(connection, "ROLLBACK") }
                connection.prepare(if (wrongJournal) "PRAGMA journal_mode" else "PRAGMA synchronous").use {
                    check(it.step())
                    if (wrongJournal) assertEquals("delete", it.getText(0).lowercase()) else assertEquals(1L, it.getLong(0))
                }
            }
            assertReplacementNoCreate(probe, "v3 wrong durable profile")
            assertEquals(source.rows, rawReplacementRows(source.path))
            assertEquals(metadata, rawMasterMigrationMetadata(source.path))
        }
        // This is explicitly an empty in-memory direct-entry probe, NOT a forged genuine v3 fixture.
        val memoryProbe = ReplacementCreateProbe()
        memoryProbe.open(":memory:").use { connection ->
            replacementProfile(connection)
            assertFailsWith<IllegalStateException> { MasterReplacementMigration3To4.migrate(connection) }
            connection.prepare("PRAGMA journal_mode").use { check(it.step()); assertEquals("memory", it.getText(0)) }
            connection.prepare("SELECT count(*) FROM sqlite_master").use { check(it.step()); assertEquals(0L, it.getLong(0)) }
        }
        assertReplacementNoCreate(memoryProbe, "memory direct entry")
    }

    @Test fun failuresAfterOneNineAndAllEighteenActualGuardsRollBackEverySourceThenGenuineReopenAndRetryWork() = runTest {
        val reference = replacementFreshReference()
        for (version in 1..3) {
            val template = replacementSeed(version)
            for (completedGuards in listOf(1, 9, 18)) {
                val source = replacementCopy(template)
                val metadata = rawMasterMigrationMetadata(source.path)
                val probe = ReplacementCreateProbe(completedGuards)
                assertReplacementOpenRefused(source.path, probe)
                val fault = probe.faults.single()
                assertEquals(completedGuards, fault.newNames.size, "Fault must follow actual partial/all new guard DDL")
                assertEquals(REPLACEMENT_EXPECTED_NAMES.take(completedGuards).sorted(), fault.newNames)
                assertReplacementOriginalStamp(version, fault)
                assertEquals(completedGuards, probe.completions.count { it.name in REPLACEMENT_EXPECTED_NAMES })
                assertEquals(source.rows, rawReplacementRows(source.path))
                assertEquals(metadata, rawMasterMigrationMetadata(source.path), "No partial inherited DDL, restamp or repair may survive")
                // The healthy original file reopens under a genuine generated OLD class.
                val original = replacementOld(source.path, version)
                try {
                    assertReplacementSchema(original, version)
                    assertReplacementSourcePreserved(source, original)
                } finally { original.close() }
                assertEquals(metadata, rawMasterMigrationMetadata(source.path))
                val retried = replacementCurrent(source.path)
                try {
                    assertReplacementSchema(retried, 4)
                    assertReplacementSourcePreserved(source, retried)
                    assertReplacementProtectedValues(retried, source)
                    observeReplacementRoom(retried) { assertEquals(reference.guards, rawReplacementGuards(source.path)) }
                } finally { retried.close() }
            }
        }
    }

    @Test fun eachNewGuardMissingChangedOrOnWrongTableRefusesVersionFourReopenWithoutRepair() = runTest {
        val source = replacementSeed(3)
        val initial = replacementCurrent(source.path)
        try { assertReplacementSchema(initial, 4) } finally { initial.close() }
        val guards = rawReplacementGuards(source.path).filter { it[0] in REPLACEMENT_EXPECTED_NAMES }
        assertEquals(18, guards.size)
        for (guard in guards) for (corruption in listOf("missing", "changed", "wrong-table")) {
            val copy = replacementCopy(source)
            val name = guard[0]
            replacementRawSql(copy.path, "DROP TRIGGER " + replacementQuote(name))
            when (corruption) {
                "changed" -> replacementRawSql(copy.path, guard[2].substringBefore("BEGIN") + "BEGIN SELECT 1; END")
                "wrong-table" -> {
                    val wrongTable = if (guard[1] == "branch_authority") "aggregate_state" else "branch_authority"
                    replacementRawSql(copy.path, "CREATE TRIGGER " + replacementQuote(name) +
                        " BEFORE INSERT ON $wrongTable BEGIN SELECT 1; END")
                }
            }
            val metadata = rawMasterMigrationMetadata(copy.path)
            val probe = ReplacementCreateProbe()
            assertReplacementOpenRefused(copy.path, probe)
            assertTrue(probe.preparations.none { it.name in REPLACEMENT_EXPECTED_NAMES }, "Verification cannot reinstall a guard")
            assertEquals(source.rows, rawReplacementRows(copy.path))
            assertEquals(metadata, rawMasterMigrationMetadata(copy.path))
        }
    }

    @Test fun inheritedUnexpectedTablesAreNotAdoptedAndAnyEarlierDdlRollsBack() = runTest {
        for ((version, table) in listOf(1 to "protected_local_account", 1 to REPLACEMENT_ISSUANCE_TABLE,
            2 to REPLACEMENT_ISSUANCE_TABLE)) {
            val source = replacementSeed(version)
            replacementRawSql(source.path, "CREATE TABLE $table (marker TEXT NOT NULL)",
                "INSERT INTO $table VALUES ('synthetic-preserve')")
            val metadata = rawMasterMigrationMetadata(source.path)
            val rows = rawReplacementRows(source.path)
            val probe = ReplacementCreateProbe()
            assertReplacementOpenRefused(source.path, probe)
            assertTrue(probe.preparations.any { it.kind == "table" && it.name == table })
            assertTrue(probe.completions.none { it.kind == "table" && it.name == table })
            assertEquals(rows, rawReplacementRows(source.path))
            assertEquals(metadata, rawMasterMigrationMetadata(source.path))
            assertEquals("synthetic-preserve", replacementText(source.path, "SELECT marker FROM $table"))
        }
    }

    @Test fun preexistingForeignKeyCorruptionIsPreservedAndRefusedAfterRollbackRatherThanPretendingHealthyRetry() = runTest {
        for (version in 1..3) {
            val source = replacementSeed(version)
            replacementRawSql(source.path, "PRAGMA foreign_keys=OFF",
                "INSERT INTO aggregate_state (scope,kind,id,revision,schema,payload,payloadHash) " +
                    "VALUES ('missing-parent','synthetic','orphan',1,'synthetic/v1',x'01','synthetic')")
            val rows = rawReplacementRows(source.path)
            val metadata = rawMasterMigrationMetadata(source.path)
            val violations = replacementForeignKeys(source.path)
            assertEquals(1, violations.size)
            repeat(2) {
                val probe = ReplacementCreateProbe()
                assertReplacementOpenRefused(source.path, probe)
                assertTrue(probe.completions.isNotEmpty(), "This is an actual LATE reference failure, not the zero-CREATE provenance gate")
                assertEquals(if (version == 3) 18 else 0, probe.completions.count { it.name in REPLACEMENT_EXPECTED_NAMES })
                assertEquals(rows, rawReplacementRows(source.path))
                assertEquals(metadata, rawMasterMigrationMetadata(source.path))
                assertEquals(violations, replacementForeignKeys(source.path))
            }
            // Do not assert healthy old-version reopening/retry for an already corrupt source.
        }
    }
}

private val REPLACEMENT_FACT_GUARD_NAMES = buildList {
    for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
        add(table + "_immutable_update"); add(table + "_immutable_delete")
    }
    for (table in REPLACEMENT_FACT_TABLES) add(table + "_no_replace")
    add("aggregate_revision_insert"); add("aggregate_revision_update")
}
private val REPLACEMENT_PROTECTED_GUARD_NAMES = REPLACEMENT_PROTECTED_TABLES.flatMap {
    listOf(it + "_no_replace", it + "_no_delete", it + "_revision_update")
}
private val REPLACEMENT_ISSUANCE_GUARD_NAMES = listOf("_no_replace", "_revision_insert", "_immutable_update", "_no_delete")
    .map { REPLACEMENT_ISSUANCE_TABLE + it }
private const val REPLACEMENT_TYPED_IDENTITY = "SELECT typeof(id),CAST(id AS TEXT),typeof(identity_hash),CAST(identity_hash AS TEXT) " +
    "FROM room_master_table ORDER BY id"

private fun replacementGuardNames(version: Int): List<String> = REPLACEMENT_FACT_GUARD_NAMES +
    (if (version >= 2) REPLACEMENT_PROTECTED_GUARD_NAMES else emptyList()) +
    (if (version >= 3) REPLACEMENT_ISSUANCE_GUARD_NAMES else emptyList()) +
    (if (version >= 4) REPLACEMENT_EXPECTED_NAMES else emptyList())

private fun replacementTables(version: Int): List<String> = when (version) {
    1 -> REPLACEMENT_FACT_TABLES
    2 -> REPLACEMENT_OLD_TABLES
    3, 4 -> REPLACEMENT_ALL_TABLES
    else -> error("Unsupported synthetic schema")
}

private fun replacementIdentity(version: Int): String = when (version) {
    1 -> "eed72c010956c64963333a5fc4f60632"
    2 -> LEGACY_V2_ROOM_IDENTITY
    3, 4 -> LEGACY_V3_ROOM_IDENTITY // Trigger-only v4 intentionally has the SAME Room entity identity as v3.
    else -> error("Unsupported synthetic schema")
}

private fun replacementMigration(version: Int): Migration = when (version) {
    1 -> MasterReplacementMigration1To4
    2 -> MasterReplacementMigration2To4
    3 -> MasterReplacementMigration3To4
    else -> error("Unsupported migration source")
}

private fun replacementMigrationPath(): Path = Files.createTempDirectory("ukkera-replacement-migration-").resolve("master.db")
private fun replacementCurrent(path: Path, driver: SQLiteDriver = BundledSQLiteDriver()): MasterRoomDatabase =
    buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize), driver)

private fun replacementOld(path: Path, version: Int): RoomDatabase = when (version) {
    1 -> LegacyV1MasterFixture(path).database
    2 -> buildLegacyV2MasterRoom(path)
    3 -> buildLegacyV3MasterRoom(path)
    else -> error("Not an old database")
}

private suspend fun assertReplacementSchema(database: RoomDatabase, version: Int) {
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.usePrepared("PRAGMA user_version") {
            check(it.step()); assertEquals(version.toLong(), it.getLong(0)); assertFalse(it.step())
        }
        connection.usePrepared(REPLACEMENT_TYPED_IDENTITY) {
            check(it.step())
            assertEquals(listOf("integer", "42", "text", replacementIdentity(version)), (0..3).map(it::getText))
            assertFalse(it.step())
        }
        connection.usePrepared("SELECT name FROM sqlite_master WHERE type='table' " +
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name") {
            assertEquals(replacementTables(version).sorted(), buildList { while (it.step()) add(it.getText(0)) })
        }
        connection.usePrepared("SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name") {
            assertEquals(replacementGuardNames(version).sorted(), buildList { while (it.step()) add(it.getText(0)) })
        }
        connection.usePrepared("PRAGMA foreign_key_check") { assertFalse(it.step()) }
    }
}

private data class ReplacementSource(
    val version: Int,
    val path: Path,
    val rows: Map<String, List<List<String>>>,
    val guards: List<List<String>>,
    val objects: List<List<String>>,
    val protectedFixture: LegacyV2MasterFixture? = null,
    val issuance: LocalAccessIssuance? = null,
)

private suspend fun replacementSeed(version: Int, unusualRowids: Boolean = false): ReplacementSource {
    val path: Path
    var protected: LegacyV2MasterFixture? = null
    var issuance: LocalAccessIssuance? = null
    when (version) {
        1 -> {
            val fixture = LegacyV1MasterFixture()
            path = fixture.path
            try { fixture.seed(); assertReplacementSchema(fixture.database, 1) } finally { fixture.close() }
        }
        2 -> {
            val fixture = LegacyV2MasterFixture()
            path = fixture.path; protected = fixture
            try { fixture.seed(); assertReplacementSchema(fixture.database, 2) } finally { fixture.close() }
        }
        3 -> {
            val fixture = LegacyV3MasterFixture()
            path = fixture.path; protected = fixture.original; issuance = fixture.issuance
            try { fixture.seed(); assertReplacementSchema(fixture.database, 3) } finally { fixture.close() }
        }
        else -> error("Not a genuine original version")
    }
    val sourcePath = if (unusualRowids) replacementReseedRowids(path, version) else path
    return ReplacementSource(version, sourcePath, rawReplacementRows(sourcePath),
        rawReplacementGuards(sourcePath), replacementTableObjects(sourcePath, replacementTables(version)), protected, issuance)
}

/** Copy a CLOSED disposable synthetic fixture only; this does not implement restore, backup or fencing. */
private fun replacementCopy(source: ReplacementSource): ReplacementSource {
    val path = replacementMigrationPath()
    Files.copy(source.path, path)
    return source.copy(path = path)
}

/**
 * Seed a fresh genuine old schema with the same typed cells and explicit unusual
 * rowids. No guard is disabled/rewritten, no version/hash is restamped, and no
 * encrypted record is rebound/re-encrypted. This is synthetic fixture setup.
 */
private suspend fun replacementReseedRowids(original: Path, version: Int): Path {
    val path = replacementMigrationPath()
    val empty = replacementOld(path, version)
    try { assertReplacementSchema(empty, version) } finally { empty.close() }
    val ids = listOf(Long.MIN_VALUE + 32, -37L, -1L, 0L, 73L, 4_000_000_000L, Long.MAX_VALUE - 32)
    BundledSQLiteDriver().open(original.toString()).use { source ->
        replacementExecute(source, "PRAGMA query_only=ON")
        BundledSQLiteDriver().open(path.toString()).use { target ->
            replacementProfile(target)
            replacementExecute(target, "BEGIN IMMEDIATE")
            try {
                for ((tableIndex, table) in replacementTables(version).withIndex()) {
                    val columns = source.prepare("PRAGMA table_info(" + replacementQuote(table) + ")").use {
                        buildList { while (it.step()) add(it.getText(1)) }
                    }
                    val fields = columns.flatMap { listOf("typeof(" + replacementQuote(it) + ")", replacementQuote(it)) }.joinToString(",")
                    source.prepare("SELECT $fields FROM " + replacementQuote(table) + " ORDER BY rowid").use { old ->
                        var rowIndex = 0
                        while (old.step()) {
                            val insert = "INSERT INTO " + replacementQuote(table) + " (rowid," +
                                columns.joinToString(",") { replacementQuote(it) } + ") VALUES (" +
                                List(columns.size + 1) { "?" }.joinToString(",") + ")"
                            target.prepare(insert).use { next ->
                                next.bindLong(1, ids[(tableIndex + rowIndex) % ids.size])
                                for (index in columns.indices) {
                                    val position = index * 2 + 1
                                    when (old.getText(index * 2)) {
                                        "integer" -> next.bindLong(index + 2, old.getLong(position))
                                        "real" -> next.bindDouble(index + 2, old.getDouble(position))
                                        "text" -> next.bindText(index + 2, old.getText(position))
                                        "blob" -> next.bindBlob(index + 2, old.getBlob(position))
                                        "null" -> next.bindNull(index + 2)
                                        else -> error("Unexpected SQLite storage class")
                                    }
                                }
                                next.step()
                            }
                            rowIndex++
                        }
                    }
                }
                replacementExecute(target, "COMMIT")
            } catch (failure: Throwable) {
                replacementExecute(target, "ROLLBACK")
                throw failure
            }
        }
    }
    val before = rawReplacementRows(original).mapValues { (_, rows) -> rows.map { it.drop(1) }.sortedBy { it.toString() } }
    val after = rawReplacementRows(path).mapValues { (_, rows) -> rows.map { it.drop(1) }.sortedBy { it.toString() } }
    assertEquals(before, after, "All non-rowid storage classes and bytes, including ciphertext, remain identical")
    assertEquals(rawReplacementGuards(original), rawReplacementGuards(path))
    return path
}

private fun replacementTableObjects(path: Path, tables: List<String>): List<List<String>> =
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("SELECT type,name,tbl_name,COALESCE(sql,'') FROM sqlite_master " +
            "WHERE type IN ('table','index') ORDER BY type,name").use { statement ->
            buildList {
                while (statement.step()) {
                    val row = (0..3).map(statement::getText)
                    if (row[2] in tables) add(row)
                }
            }
        }
    }

private suspend fun assertReplacementSourcePreserved(source: ReplacementSource, database: RoomDatabase) {
    observeReplacementRoom(database) {
        assertEquals(source.rows, rawReplacementRows(source.path, source.rows.keys.toList()))
        assertEquals(source.guards, rawReplacementGuards(source.path).filter { it[0] in replacementGuardNames(source.version) })
        assertEquals(source.objects, replacementTableObjects(source.path, source.rows.keys.toList()), "Exact old table/index DDL stays intact")
    }
}

private suspend fun assertReplacementProtectedValues(current: MasterRoomDatabase, source: ReplacementSource) {
    val fixture = source.protectedFixture ?: run {
        observeReplacementRoom(current) {
            for (table in REPLACEMENT_PROTECTED_TABLES + REPLACEMENT_ISSUANCE_TABLE)
                assertTrue(rawReplacementRows(source.path).getValue(table).isEmpty())
        }
        return
    }
    RoomLocalSecurityStore(current, fixture.protection, fixture.keyReference).transaction { tx ->
        val account = assertNotNull(tx.readProtectedAccount(TEST_SCOPE, TEST_ACTOR))
        assertContentEquals(ProtectedLocalSecurityCodec.encodeAccount(fixture.account), ProtectedLocalSecurityCodec.encodeAccount(account))
        assertEquals(listOf(SessionStatus.REVOKED, SessionStatus.ACTIVE), account.state.sessions.map { it.status })
        assertContentEquals(ProtectedLocalSecurityCodec.encodePolicy(fixture.policy),
            ProtectedLocalSecurityCodec.encodePolicy(assertNotNull(tx.readPolicy(TEST_SCOPE, TEST_ACTOR))))
        assertContentEquals(ProtectedLocalSecurityCodec.encodeAdmission(fixture.admission),
            ProtectedLocalSecurityCodec.encodeAdmission(assertNotNull(tx.readAdmission(TEST_SCOPE, TEST_AUTHORITY.deviceId))))
        for (throttle in fixture.throttles) assertContentEquals(ProtectedLocalSecurityCodec.encodeThrottle(throttle),
            ProtectedLocalSecurityCodec.encodeThrottle(assertNotNull(tx.readThrottle(throttle.key))))
        val expectedIssuance = source.issuance
        for (session in account.state.sessions) {
            val actual = tx.readIssuance(TEST_SCOPE, session.sessionId)
            if (expectedIssuance != null && expectedIssuance.sessionId == session.sessionId) {
                assertContentEquals(ProtectedLocalSecurityCodec.encodeIssuance(expectedIssuance),
                    ProtectedLocalSecurityCodec.encodeIssuance(assertNotNull(actual)))
            } else assertNull(actual, "No backfill of preexisting active/revoked sessions")
        }
    }
}

private data class ReplacementReference(val guards: List<List<String>>)
private suspend fun replacementFreshReference(): ReplacementReference {
    val path = replacementMigrationPath()
    val current = replacementCurrent(path)
    return try {
        assertReplacementSchema(current, 4)
        observeReplacementRoom(current) { ReplacementReference(rawReplacementGuards(path)) }
    }
    finally { current.close() }
}

private suspend fun assertReplacementOpenRefused(path: Path, probe: ReplacementCreateProbe) {
    val current = replacementCurrent(path, probe)
    try { assertFails { current.useWriterConnection { requireDurableWriter(it) } } } finally { current.close() }
}

private fun replacementQuote(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
private fun replacementExecute(connection: SQLiteConnection, sql: String) { connection.prepare(sql).use { it.step() } }
private fun replacementRawSql(path: Path, vararg sql: String) = BundledSQLiteDriver().open(path.toString()).use { connection ->
    sql.forEach { replacementExecute(connection, it) }
}
private fun replacementText(path: Path, sql: String): String = BundledSQLiteDriver().open(path.toString()).use { connection ->
    connection.prepare(sql).use { check(it.step()); val value = it.getText(0); assertFalse(it.step()); value }
}
private fun replacementProfile(connection: SQLiteConnection) {
    replacementExecute(connection, "PRAGMA journal_mode=TRUNCATE")
    replacementExecute(connection, "PRAGMA synchronous=FULL")
    replacementExecute(connection, "PRAGMA foreign_keys=ON")
}
private fun replacementForeignKeys(path: Path): List<List<String>> =
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("PRAGMA foreign_key_check").use { statement ->
            buildList { while (statement.step()) add((0..3).map(statement::getText)) }
        }
    }
private fun replacementMixedCase(value: String): String = value.mapIndexed { i, char ->
    if (i % 2 == 0) char.uppercaseChar() else char
}.joinToString("")

private fun replacementIdentityCorruptions(hash: String): List<Pair<String, List<String>>> = listOf(
    "missing table" to listOf("DROP TABLE room_master_table"),
    "missing row" to listOf("DELETE FROM room_master_table"),
    "wrong hash" to listOf("UPDATE room_master_table SET identity_hash='00000000000000000000000000000000'"),
    "blob hash" to listOf("UPDATE room_master_table SET identity_hash=CAST(identity_hash AS BLOB)"),
    "embedded NUL" to listOf("UPDATE room_master_table SET identity_hash=identity_hash || char(0) || 'tail'"),
    "null hash" to listOf("UPDATE room_master_table SET identity_hash=NULL"),
    "oversized hash" to listOf("UPDATE room_master_table SET identity_hash=zeroblob(4096)"),
    "wrong id" to listOf("UPDATE room_master_table SET id=43"),
    "extra row" to listOf("INSERT INTO room_master_table VALUES (43,'$hash')"),
    "case-insensitive hash" to listOf("ALTER TABLE room_master_table RENAME TO old_identity",
        "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT COLLATE NOCASE)",
        "INSERT INTO room_master_table VALUES (42,'" + hash.uppercase() + "')", "DROP TABLE old_identity"),
    "coercible text id" to listOf("ALTER TABLE room_master_table RENAME TO old_identity",
        "CREATE TABLE room_master_table (id TEXT,identity_hash TEXT)",
        "INSERT INTO room_master_table VALUES ('42','$hash')", "DROP TABLE old_identity"),
    "duplicate id" to listOf("ALTER TABLE room_master_table RENAME TO old_identity",
        "CREATE TABLE room_master_table (id INTEGER,identity_hash TEXT)",
        "INSERT INTO room_master_table VALUES (42,'$hash'),(42,'$hash')", "DROP TABLE old_identity"),
)

private data class ReplacementCreate(val kind: String, val name: String, val sql: String)
private data class ReplacementDdlObservation(
    val phase: String,
    val version: Long,
    val identities: List<List<String>>,
    val newNames: List<String>,
)
private fun replacementObserve(connection: SQLiteConnection, phase: String): ReplacementDdlObservation =
    ReplacementDdlObservation(phase,
        connection.prepare("PRAGMA user_version").use { check(it.step()); it.getLong(0) },
        connection.prepare(REPLACEMENT_TYPED_IDENTITY).use { statement ->
            buildList { while (statement.step()) add((0..3).map(statement::getText)) }
        },
        replacementGuardRows(connection).map { it[0] }.filter { it in REPLACEMENT_EXPECTED_NAMES })

private fun assertReplacementOriginalStamp(version: Int, observation: ReplacementDdlObservation) {
    assertEquals(version.toLong(), observation.version, "Original version must remain at " + observation.phase)
    assertEquals(listOf(listOf("integer", "42", "text", replacementIdentity(version))), observation.identities,
        "Original identity must remain at " + observation.phase)
}
private fun assertReplacementNoCreate(probe: ReplacementCreateProbe, label: String) {
    assertTrue(probe.preparations.isEmpty(), "No CREATE preparation: $label")
    assertTrue(probe.executions.isEmpty(), "No CREATE step: $label")
    assertTrue(probe.completions.isEmpty(), "No completed CREATE: $label")
    assertTrue(probe.observations.isEmpty(), "No new-guard DDL boundary: $label")
}
private fun assertReplacementHealthyDdl(probe: ReplacementCreateProbe, version: Int) {
    val newTables = when (version) {
        1 -> REPLACEMENT_PROTECTED_TABLES + REPLACEMENT_ISSUANCE_TABLE
        2 -> listOf(REPLACEMENT_ISSUANCE_TABLE)
        else -> emptyList()
    }
    for (events in listOf(probe.preparations, probe.executions, probe.completions)) {
        assertEquals(newTables, events.filter { it.kind == "table" && it.name in REPLACEMENT_ALL_TABLES }.map { it.name })
        assertEquals(REPLACEMENT_EXPECTED_NAMES, events.filter { it.name in REPLACEMENT_EXPECTED_NAMES }.map { it.name })
    }
    assertEquals(54, probe.observations.size, "18 guards observed before prepare, before step and after actual DDL")
    for (observation in probe.observations) assertReplacementOriginalStamp(version, observation)
    assertEquals(REPLACEMENT_EXPECTED_NAMES.sorted(), probe.observations.last().newNames)
    assertTrue(probe.faults.isEmpty())
}

/** Observe ALL CREATE TABLE/TRIGGER (also INDEX/VIEW), not just protected-table SQL. */
private class ReplacementCreateProbe(private val failAfterNewGuards: Int? = null) : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool
    val preparations = CopyOnWriteArrayList<ReplacementCreate>()
    val executions = CopyOnWriteArrayList<ReplacementCreate>()
    val completions = CopyOnWriteArrayList<ReplacementCreate>()
    val observations = CopyOnWriteArrayList<ReplacementDdlObservation>()
    val faults = CopyOnWriteArrayList<ReplacementDdlObservation>()

    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val match = CREATE.find(sql)
                val event = match?.let { ReplacementCreate(it.groupValues[1].lowercase(), it.groupValues[2].lowercase(), sql) }
                if (sql.trimStart().startsWith("CREATE", ignoreCase = true)) check(event != null) { "Unobserved CREATE form in test driver" }
                if (event != null) preparations.add(event)
                val newGuard = event != null && event.kind == "trigger" && event.name in REPLACEMENT_EXPECTED_NAMES
                if (newGuard) observations.add(replacementObserve(connection, "before-prepare"))
                val statement = connection.prepare(sql)
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        if (event != null) executions.add(event)
                        if (newGuard) observations.add(replacementObserve(connection, "before-step"))
                        val result = statement.step()
                        if (event != null) completions.add(event)
                        if (newGuard) {
                            val after = replacementObserve(connection, "after-actual-ddl")
                            observations.add(after)
                            if (after.newNames.size == failAfterNewGuards) {
                                faults.add(after)
                                error("Synthetic failure after actual replacement-guard DDL")
                            }
                        }
                        return result
                    }
                }
            }
        }
    }

    companion object {
        private val CREATE = Regex(
            "^\\s*CREATE\\s+(?:(?:TEMP|TEMPORARY)\\s+)?(?:UNIQUE\\s+)?(TABLE|TRIGGER|INDEX|VIEW)\\s+" +
                "(?:IF\\s+NOT\\s+EXISTS\\s+)?(?:[\"\\x60]?main[\"\\x60]?\\.)?[\"\\x60]?([a-z_][a-z_0-9]*)",
            RegexOption.IGNORE_CASE,
        )
    }
}
