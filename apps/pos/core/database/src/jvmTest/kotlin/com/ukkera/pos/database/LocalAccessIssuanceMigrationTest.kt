package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.security.LocalOpenResult
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.SealedLocalRecord
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Disposable genuine Room files and synthetic ciphertext only; never a live migration or login. */
class LocalAccessIssuanceMigrationTest {
    @Test fun freshLegacyV2IsIndependentlyGeneratedWithElevenOriginalEntitiesAndIdentity() = runTest {
        val legacy = LegacyV2MasterFixture()
        try {
            assertOriginalSchema(legacy.database, 2)
            val rows = legacyV2RowSnapshot(legacy.database)
            assertEquals(ORIGINAL_V2_TABLES.toSet(), rows.keys)
            assertTrue(rows.values.all { it.isEmpty() }, "Fresh v2 must not provision protected state")
            assertEquals(ORIGINAL_PROTECTED_TABLES, ProtectedLocalSchemaCallback.tableNames)
            assertEquals(ORIGINAL_PROTECTED_GUARDS.sorted(), ProtectedLocalSchemaCallback.triggerNames.sorted())
            assertEquals(29, legacyV2GuardSnapshot(legacy.database).size)
        } finally { legacy.close() }
        val reopened = LegacyV2MasterFixture(legacy.path)
        try { assertOriginalSchema(reopened.database, 2) } finally { reopened.close() }
    }

    @Test fun populatedGenuineV2RetainsEveryOriginalTableAndEncryptedActiveAndRevokedSessions() = runTest {
        val legacy = LegacyV2MasterFixture()
        try {
            legacy.seed()
            assertOriginalSchema(legacy.database, 2)
            val rows = legacyV2RowSnapshot(legacy.database)
            assertEquals(listOf(1, 1, 2, 1, 1, 1, 2, 1, 1, 1, 2), rows.values.map { it.size })
            val row = assertNotNull(legacy.database.protectedV2().account(TEST_SCOPE.storageKey(), TEST_ACTOR.value))
            val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.Account(TEST_SCOPE, TEST_ACTOR), Revision(row.revision))
            val opened = assertIs<LocalOpenResult.Opened>(legacy.protection.open(legacy.keyReference, binding,
                SealedLocalRecord(binding, legacy.keyReference, row.nonce, row.ciphertextAndTag)))
            val plaintext = opened.copyPlaintext()
            try {
                val account = ProtectedLocalSecurityCodec.decodeAccount(binding, plaintext)
                assertContentEquals(ProtectedLocalSecurityCodec.encodeAccount(legacy.account),
                    ProtectedLocalSecurityCodec.encodeAccount(account))
                assertEquals(listOf(SessionStatus.REVOKED, SessionStatus.ACTIVE), account.state.sessions.map { it.status })
                assertEquals(listOf(4L, 5L), account.state.sessions.map { it.generation.value })
                assertEquals(Revision(2), account.credentialRevision)
            } finally { plaintext.fill(0) }
        } finally { legacy.close() }
    }

    @Test fun freshThreeAndReopenHaveGeneratedIdentityAllGuardsAndNoAutomaticProvisioning() = runTest {
        val path = newMigrationPath()
        val database = openVersionThree(path)
        val identity: String
        val guards: List<List<String>>
        try {
            identity = assertVersionThreeSchema(database)
            guards = issuanceGuardSnapshot(database)
            assertTrue(legacyV2RowSnapshot(database).values.all { it.isEmpty() })
            assertEmptyIssuance(database)
            assertNull(database.localAccessIssuance().read(TEST_SCOPE.storageKey(), testId(9522).value))
        } finally { database.close() }
        val before = rawMasterMigrationMetadata(path)
        val reopened = openVersionThree(path)
        try {
            assertVersionThreeSchema(reopened, identity)
            assertEquals(guards, issuanceGuardSnapshot(reopened))
            assertTrue(legacyV2RowSnapshot(reopened).values.all { it.isEmpty() })
            assertEmptyIssuance(reopened)
        } finally { reopened.close() }
        assertEquals(before, rawMasterMigrationMetadata(path))
    }

    @Test fun actualOneToThreePreservesSevenTablesAndStillSeesVersionOneAtFifthCreate() = runTest {
        val reference = freshVersionThreeReference()
        val source = seedSource(1)
        val probe = IssuanceMigrationProbe()
        val migrated = openVersionThree(source.path, probe)
        try {
            assertVersionThreeSchema(migrated, reference.identity)
            assertSourcePreserved(source, migrated)
            assertEquals(reference.guards, issuanceGuardSnapshot(migrated))
            for (table in ORIGINAL_PROTECTED_TABLES) assertEquals(0L, rowCount(migrated, table))
            assertEmptyIssuance(migrated)
        } finally { migrated.close() }
        assertHealthyCreateSequence(probe, 1)
        val reopened = openVersionThree(source.path)
        try {
            assertVersionThreeSchema(reopened, reference.identity)
            assertSourcePreserved(source, reopened)
            assertEmptyIssuance(reopened)
        } finally { reopened.close() }
    }

    @Test fun actualTwoToThreePreservesElevenTablesAndDoesNotBackfillOldActiveOrRevokedSessions() = runTest {
        val reference = freshVersionThreeReference()
        val legacy = LegacyV2MasterFixture()
        val source: MigrationSource
        try {
            legacy.seed()
            source = captureSource(2, legacy.path, legacy.database)
        } finally { legacy.close() }
        val probe = IssuanceMigrationProbe()
        val migrated = openVersionThree(source.path, probe)
        try {
            assertVersionThreeSchema(migrated, reference.identity)
            assertSourcePreserved(source, migrated)
            assertEquals(reference.guards, issuanceGuardSnapshot(migrated))
            assertEmptyIssuance(migrated)
        } finally { migrated.close() }
        assertHealthyCreateSequence(probe, 2)
        val reopened = openVersionThree(source.path)
        try {
            assertVersionThreeSchema(reopened, reference.identity)
            assertSourcePreserved(source, reopened)
            assertEmptyIssuance(reopened)
        } finally { reopened.close() }
        // Historical migration and reopen above REALLY completed at v3. The same
        // file now advances to v4 solely for the current production-store assertions.
        // Do not merge this phase's driver observations into the original 2->3 probe.
        repeat(2) { // Actual 3->4 followed by genuine current-v4 close/reopen.
            val current = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(
                source.path.toString(), MasterRoomConstructor::initialize))
            try {
                assertSourcePreserved(source, current)
                assertCurrentV4ProtectedValues(current, legacy)
                assertSourcePreserved(source, current)
                assertEmptyIssuance(current)
            } finally { current.close() }
        }
    }

    @Test fun bothSourcesRejectMissingUnknownAndCoercibleIdentityBeforePreparingAnyProtectedDdl() = runTest {
        for (version in 1..2) for ((label, statements) in identityCorruptions(originalIdentity(version))) {
            val source = seedSource(version)
            rawSql(source.path, *statements.toTypedArray())
            val metadata = rawMasterMigrationMetadata(source.path)
            val probe = IssuanceMigrationProbe()
            assertCurrentOpenRefused(source.path, probe)
            assertNoProtectedDdl(probe, "v$version $label")
            assertEquals(source.rows, rawSourceRows(source), "v$version $label")
            assertEquals(metadata, rawMasterMigrationMetadata(source.path), "Refusal must not restamp or repair v$version $label")
        }
    }

    @Test fun bothSourcesRejectEveryMissingOrNoOpOriginalGuardBeforeProtectedDdlWithoutRepair() = runTest {
        for (version in 1..2) {
            val names = ORIGINAL_FACT_GUARDS + if (version == 2) ORIGINAL_PROTECTED_GUARDS else emptyList()
            for (name in names) for (replaceWithNoOp in listOf(false, true)) {
                val source = seedSource(version)
                val original = source.guards.single { it[0] == name }[2]
                rawSql(source.path, "DROP TRIGGER $name")
                if (replaceWithNoOp) rawSql(source.path, original.substringBefore("BEGIN") + "BEGIN SELECT 1; END")
                val metadata = rawMasterMigrationMetadata(source.path)
                val probe = IssuanceMigrationProbe()
                assertCurrentOpenRefused(source.path, probe)
                assertNoProtectedDdl(probe, "v$version $name no-op=$replaceWithNoOp")
                assertEquals(source.rows, rawSourceRows(source))
                assertEquals(metadata, rawMasterMigrationMetadata(source.path), "Refusal must preserve the missing/changed guard")
            }
        }
    }

    @Test fun directMigrationEntriesRejectWrongUserVersionBeforeAnyProtectedDdl() = runTest {
        for (version in 1..2) for (wrongVersion in listOf(0, 3, if (version == 1) 2 else 1)) {
            val source = seedSource(version)
            rawSql(source.path, "PRAGMA user_version=$wrongVersion")
            val metadata = rawMasterMigrationMetadata(source.path)
            val probe = IssuanceMigrationProbe()
            probe.open(source.path.toString()).use { connection ->
                configureMigrationProfile(connection)
                connection.prepare("BEGIN IMMEDIATE").use { it.step() }
                try {
                    assertFailsWith<IllegalStateException> {
                        if (version == 1) LocalAccessIssuanceMigration1To3.migrate(connection)
                        else LocalAccessIssuanceMigration2To3.migrate(connection)
                    }
                } finally { connection.prepare("ROLLBACK").use { it.step() } }
            }
            assertNoProtectedDdl(probe, "v$version entry with user_version=$wrongVersion")
            assertEquals(source.rows, rawSourceRows(source))
            assertEquals(metadata, rawMasterMigrationMetadata(source.path))
        }
    }

    @Test fun strictTwoToThreeEntryRejectsNonDurableProfilesWithoutDdlOrProfileRepair() = runTest {
        for (wrongJournal in listOf(false, true)) {
            val source = seedSource(2)
            val metadata = rawMasterMigrationMetadata(source.path)
            val probe = IssuanceMigrationProbe()
            probe.open(source.path.toString()).use { connection ->
                configureMigrationProfile(connection)
                if (wrongJournal) connection.prepare("PRAGMA journal_mode=DELETE").use { check(it.step()) }
                else connection.prepare("PRAGMA synchronous=NORMAL").use { it.step() }
                connection.prepare("BEGIN IMMEDIATE").use { it.step() }
                try { assertFailsWith<IllegalStateException> { LocalAccessIssuanceMigration2To3.migrate(connection) } }
                finally { connection.prepare("ROLLBACK").use { it.step() } }
                if (wrongJournal) connection.prepare("PRAGMA journal_mode").use {
                    check(it.step()); assertEquals("delete", it.getText(0).lowercase())
                } else connection.prepare("PRAGMA synchronous").use { check(it.step()); assertEquals(1L, it.getLong(0)) }
            }
            assertNoProtectedDdl(probe, "v2 non-durable profile")
            assertEquals(source.rows, rawSourceRows(source))
            assertEquals(metadata, rawMasterMigrationMetadata(source.path))
        }
    }

    @Test fun unexpectedFifthTableFromEitherSourceIsNeverAdoptedOrItsSentinelRewritten() = runTest {
        for (version in 1..2) {
            val source = seedSource(version)
            rawSql(source.path, "CREATE TABLE $ISSUANCE_SQL_TABLE (unexpected TEXT NOT NULL)",
                "INSERT INTO $ISSUANCE_SQL_TABLE VALUES ('synthetic-do-not-adopt')")
            val metadata = rawMasterMigrationMetadata(source.path)
            val probe = IssuanceMigrationProbe()
            assertCurrentOpenRefused(source.path, probe)
            assertEquals(1, probe.preparations.count { it == ISSUANCE_SQL_TABLE })
            assertEquals(0, probe.completions.count { it == ISSUANCE_SQL_TABLE })
            assertEquals(if (version == 1) ORIGINAL_PROTECTED_TABLES else emptyList(), probe.completions.toList())
            assertEquals(source.rows, rawSourceRows(source))
            assertEquals(metadata, rawMasterMigrationMetadata(source.path))
            BundledSQLiteDriver().open(source.path.toString()).use { connection ->
                connection.prepare("PRAGMA query_only=ON").use { it.step() }
                connection.prepare("SELECT unexpected FROM $ISSUANCE_SQL_TABLE").use {
                    check(it.step()); assertEquals("synthetic-do-not-adopt", it.getText(0)); assertFalse(it.step())
                }
            }
        }
    }

    @Test fun failureAfterActualFifthTableCreationRollsBackBothSourcesThenOriginalReopenAndRetryWork() = runTest {
        assertDdlFailureRollsBack(DdlFailurePoint.AFTER_TABLE)
    }

    @Test fun failureAfterAllActualFifthGuardsRollsBackBothSourcesThenOriginalReopenAndRetryWork() = runTest {
        assertDdlFailureRollsBack(DdlFailurePoint.AFTER_GUARDS)
    }

    @Test fun eachMissingOrNoOpFifthGuardRejectsReopenWithoutRepairOrDataMutation() = runTest {
        for (name in ISSUANCE_GUARDS) for (replaceWithNoOp in listOf(false, true)) {
            val source = seedSource(2)
            val current = openVersionThree(source.path)
            val sql: String
            try {
                assertVersionThreeSchema(current)
                sql = issuanceGuardSnapshot(current).single { it[0] == name }[2]
            } finally { current.close() }
            rawSql(source.path, "DROP TRIGGER $name")
            if (replaceWithNoOp) rawSql(source.path, sql.substringBefore("BEGIN") + "BEGIN SELECT 1; END")
            val metadata = rawMasterMigrationMetadata(source.path)
            val probe = IssuanceMigrationProbe()
            assertCurrentOpenRefused(source.path, probe)
            assertNoProtectedDdl(probe, "$name no-op=$replaceWithNoOp")
            assertEquals(source.rows, rawSourceRows(source))
            assertEquals(metadata, rawMasterMigrationMetadata(source.path))
        }
    }

    @Test fun eachFifthGuardMovedToAWrongTableRejectsReopenRatherThanAcceptingItsName() = runTest {
        for (name in ISSUANCE_GUARDS) {
            val source = seedSource(2)
            val current = openVersionThree(source.path)
            try { assertVersionThreeSchema(current) } finally { current.close() }
            rawSql(source.path, "DROP TRIGGER $name", "CREATE TRIGGER $name BEFORE INSERT ON protected_local_account " +
                "BEGIN SELECT RAISE(ABORT, 'synthetic wrong table'); END")
            val metadata = rawMasterMigrationMetadata(source.path)
            val probe = IssuanceMigrationProbe()
            assertCurrentOpenRefused(source.path, probe)
            assertNoProtectedDdl(probe, "$name wrong table")
            assertEquals(source.rows, rawSourceRows(source))
            assertEquals(metadata, rawMasterMigrationMetadata(source.path))
        }
    }
}

/** Current adapter phase only; the historical observations are complete before this call. */
private suspend fun assertCurrentV4ProtectedValues(migrated: MasterRoomDatabase, legacy: LegacyV2MasterFixture) {
    migrated.useWriterConnection { connection ->
        connection.usePrepared("PRAGMA user_version") { check(it.step()); assertEquals(4L, it.getLong(0)) }
        connection.usePrepared("SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name") {
            val names = buildList { while (it.step()) add(it.getText(0)) }
            assertEquals((ORIGINAL_FACT_GUARDS + ORIGINAL_PROTECTED_GUARDS + ISSUANCE_GUARDS +
                REPLACEMENT_EXPECTED_NAMES).sorted(), names)
        }
    }
    val store = RoomLocalSecurityStore(migrated, legacy.protection, legacy.keyReference)
    store.transaction { transaction ->
        val account = assertNotNull(transaction.readProtectedAccount(TEST_SCOPE, TEST_ACTOR))
        assertContentEquals(ProtectedLocalSecurityCodec.encodeAccount(legacy.account),
            ProtectedLocalSecurityCodec.encodeAccount(account))
        assertEquals(listOf(SessionStatus.REVOKED, SessionStatus.ACTIVE), account.state.sessions.map { it.status })
        for (session in account.state.sessions) assertNull(transaction.readIssuance(TEST_SCOPE, session.sessionId))
        assertContentEquals(ProtectedLocalSecurityCodec.encodePolicy(legacy.policy),
            ProtectedLocalSecurityCodec.encodePolicy(assertNotNull(transaction.readPolicy(TEST_SCOPE, TEST_ACTOR))))
        assertContentEquals(ProtectedLocalSecurityCodec.encodeAdmission(legacy.admission),
            ProtectedLocalSecurityCodec.encodeAdmission(assertNotNull(transaction.readAdmission(TEST_SCOPE, TEST_AUTHORITY.deviceId))))
        for (throttle in legacy.throttles) assertContentEquals(ProtectedLocalSecurityCodec.encodeThrottle(throttle),
            ProtectedLocalSecurityCodec.encodeThrottle(assertNotNull(transaction.readThrottle(throttle.key))))
    }
}

private const val ISSUANCE_SQL_TABLE = "protected_local_access_issuance"
private const val ORIGINAL_V1_IDENTITY = "eed72c010956c64963333a5fc4f60632"
private val ORIGINAL_FACT_TABLES = listOf("branch_authority", "operation_result", "aggregate_state", "unique_claim",
    "journal_envelope", "audit_event", "outbox_intent")
private val ORIGINAL_PROTECTED_TABLES = listOf("protected_local_account", "protected_effective_policy",
    "protected_device_admission", "protected_login_throttle")
private val ORIGINAL_V2_TABLES = ORIGINAL_FACT_TABLES + ORIGINAL_PROTECTED_TABLES
private val ORIGINAL_FACT_GUARDS = buildList {
    for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent")) {
        add("${table}_immutable_update"); add("${table}_immutable_delete")
    }
    for (table in ORIGINAL_FACT_TABLES) add("${table}_no_replace")
    add("aggregate_revision_insert"); add("aggregate_revision_update")
}
private val ORIGINAL_PROTECTED_GUARDS = ORIGINAL_PROTECTED_TABLES.flatMap {
    listOf("${it}_no_replace", "${it}_no_delete", "${it}_revision_update")
}
private val ISSUANCE_GUARDS = listOf("${ISSUANCE_SQL_TABLE}_no_replace", "${ISSUANCE_SQL_TABLE}_revision_insert",
    "${ISSUANCE_SQL_TABLE}_immutable_update", "${ISSUANCE_SQL_TABLE}_no_delete")
private const val ENTITY_TABLE_QUERY = "SELECT name FROM sqlite_master WHERE type='table' " +
    "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name"
private const val TYPED_IDENTITY_QUERY = "SELECT typeof(id),CAST(id AS TEXT),typeof(identity_hash),CAST(identity_hash AS TEXT) " +
    "FROM room_master_table ORDER BY id"

private data class MigrationSource(
    val version: Int,
    val path: Path,
    val rows: Map<String, List<List<String>>>,
    val guards: List<List<String>>,
    val rowsWithRowids: Map<String, List<List<String>>>,
)

private fun newMigrationPath(): Path = Files.createTempDirectory("ukkera-issuance-migration-test-").resolve("master.db")

private fun openVersionThree(path: Path, driver: SQLiteDriver = BundledSQLiteDriver()): LegacyV3MasterDatabase =
    buildLegacyV3MasterRoom(path, driver)

private fun originalIdentity(version: Int): String = when (version) {
    1 -> ORIGINAL_V1_IDENTITY
    2 -> LEGACY_V2_ROOM_IDENTITY
    else -> error("Not an original source version")
}

private suspend fun seedSource(version: Int): MigrationSource = when (version) {
    1 -> {
        val fixture = LegacyV1MasterFixture()
        try { fixture.seed(); captureSource(1, fixture.path, fixture.database) } finally { fixture.close() }
    }
    2 -> {
        val fixture = LegacyV2MasterFixture()
        try { fixture.seed(); captureSource(2, fixture.path, fixture.database) } finally { fixture.close() }
    }
    else -> error("Not an original source version")
}

private suspend fun captureSource(version: Int, path: Path, database: RoomDatabase): MigrationSource {
    assertOriginalSchema(database, version)
    return MigrationSource(version, path,
        if (version == 1) legacyRowSnapshot(database) else legacyV2RowSnapshot(database),
        sourceGuardSnapshot(database, version),
        observeReplacementRoom(database) {
            rawReplacementRows(path, if (version == 1) ORIGINAL_FACT_TABLES else ORIGINAL_V2_TABLES)
        })
}

private suspend fun sourceGuardSnapshot(database: RoomDatabase, sourceVersion: Int): List<List<String>> =
    legacyV2GuardSnapshot(database).filter { it[0] in
        (ORIGINAL_FACT_GUARDS + if (sourceVersion == 2) ORIGINAL_PROTECTED_GUARDS else emptyList()) }

private fun rawSourceRows(source: MigrationSource): Map<String, List<List<String>>> =
    if (source.version == 1) rawLegacyRowSnapshot(source.path) else rawLegacyV2RowSnapshot(source.path)

private suspend fun assertSourcePreserved(source: MigrationSource, database: RoomDatabase) {
    assertEquals(source.rowsWithRowids, observeReplacementRoom(database) {
        rawReplacementRows(source.path, source.rowsWithRowids.keys.toList())
    })
    assertEquals(source.rows, if (source.version == 1) legacyRowSnapshot(database) else legacyV2RowSnapshot(database))
    assertEquals(source.guards, sourceGuardSnapshot(database, source.version), "Exact original guard names, tables and SQL must survive")
}

private suspend fun assertOriginalSchema(database: RoomDatabase, version: Int) = database.useWriterConnection { connection ->
    requireDurableWriter(connection)
    connection.usePrepared("PRAGMA user_version") { check(it.step()); assertEquals(version.toLong(), it.getLong(0)); assertFalse(it.step()) }
    connection.usePrepared(TYPED_IDENTITY_QUERY) {
        check(it.step()); assertEquals(listOf("integer", "42", "text", originalIdentity(version)), (0..3).map(it::getText)); assertFalse(it.step())
    }
    connection.usePrepared(ENTITY_TABLE_QUERY) {
        val tables = buildList { while (it.step()) add(it.getText(0)) }
        assertEquals((if (version == 1) ORIGINAL_FACT_TABLES else ORIGINAL_V2_TABLES).sorted(), tables)
    }
    connection.usePrepared("SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name") {
        val names = buildList { while (it.step()) add(it.getText(0)) }
        assertEquals((ORIGINAL_FACT_GUARDS + if (version == 2) ORIGINAL_PROTECTED_GUARDS else emptyList()).sorted(), names)
    }
}

/** The fresh generated target is the migration oracle; never fake v2 by restamping this database. */
private data class VersionThreeReference(val identity: String, val guards: List<List<String>>)

private suspend fun freshVersionThreeReference(): VersionThreeReference {
    val database = openVersionThree(newMigrationPath())
    return try { VersionThreeReference(assertVersionThreeSchema(database), issuanceGuardSnapshot(database)) }
    finally { database.close() }
}

private suspend fun assertVersionThreeSchema(database: LegacyV3MasterDatabase, expectedIdentity: String? = null): String =
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.usePrepared("PRAGMA user_version") { check(it.step()); assertEquals(3L, it.getLong(0)); assertFalse(it.step()) }
        val identity = connection.usePrepared(TYPED_IDENTITY_QUERY) {
            check(it.step()); assertEquals(listOf("integer", "42", "text"), (0..2).map(it::getText))
            val hash = it.getText(3); assertFalse(it.step()); hash
        }
        assertTrue(Regex("[0-9a-f]{32}").matches(identity))
        assertEquals(LEGACY_V3_ROOM_IDENTITY, identity)
        assertNotEquals(ORIGINAL_V1_IDENTITY, identity); assertNotEquals(LEGACY_V2_ROOM_IDENTITY, identity)
        if (expectedIdentity != null) assertEquals(expectedIdentity, identity)
        connection.usePrepared(ENTITY_TABLE_QUERY) {
            val tables = buildList { while (it.step()) add(it.getText(0)) }
            assertEquals((ORIGINAL_V2_TABLES + ISSUANCE_SQL_TABLE).sorted(), tables)
        }
        connection.usePrepared("SELECT name FROM sqlite_master WHERE type='trigger' ORDER BY name") {
            val names = buildList { while (it.step()) add(it.getText(0)) }
            assertEquals((ORIGINAL_FACT_GUARDS + ORIGINAL_PROTECTED_GUARDS + ISSUANCE_GUARDS).sorted(), names)
        }
        connection.usePrepared("PRAGMA foreign_key_check") { assertFalse(it.step()) }
        identity
    }

private suspend fun issuanceGuardSnapshot(database: RoomDatabase): List<List<String>> = database.useWriterConnection { connection ->
    connection.usePrepared("SELECT name,tbl_name,sql FROM sqlite_master WHERE type='trigger' AND tbl_name='$ISSUANCE_SQL_TABLE' ORDER BY name") {
        buildList { while (it.step()) add((0..2).map(it::getText)) }
    }
}

private suspend fun rowCount(database: RoomDatabase, table: String): Long = database.useWriterConnection { connection ->
    check(table in ORIGINAL_V2_TABLES + ISSUANCE_SQL_TABLE)
    connection.usePrepared("SELECT count(*) FROM `$table`") { check(it.step()); it.getLong(0) }
}

private suspend fun assertEmptyIssuance(database: RoomDatabase) { assertEquals(0L, rowCount(database, ISSUANCE_SQL_TABLE)) }

private fun rawSql(path: Path, vararg statements: String) = BundledSQLiteDriver().open(path.toString()).use { connection ->
    for (sql in statements) connection.prepare(sql).use { it.step() }
}

private fun configureMigrationProfile(connection: SQLiteConnection) {
    connection.prepare("PRAGMA journal_mode=TRUNCATE").use { check(it.step()) }
    connection.prepare("PRAGMA synchronous=FULL").use { it.step() }
    connection.prepare("PRAGMA foreign_keys=ON").use { it.step() }
}

private suspend fun assertCurrentOpenRefused(path: Path, probe: IssuanceMigrationProbe) {
    val refused = openVersionThree(path, probe)
    try { assertFails { refused.useWriterConnection { requireDurableWriter(it) } } } finally { refused.close() }
}

private fun assertNoProtectedDdl(probe: IssuanceMigrationProbe, label: String) {
    assertTrue(probe.preparations.isEmpty(), "No protected CREATE preparation: $label")
    assertTrue(probe.executions.isEmpty(), "No protected CREATE execution: $label")
    assertTrue(probe.completions.isEmpty(), "No protected CREATE completion: $label")
    assertTrue(probe.fifthObservations.isEmpty(), "No fifth-table migration entry: $label")
}

private fun identityCorruptions(hash: String): List<Pair<String, List<String>>> = listOf(
    "missing table" to listOf("DROP TABLE room_master_table"),
    "missing row" to listOf("DELETE FROM room_master_table"),
    "wrong hash" to listOf("UPDATE room_master_table SET identity_hash='00000000000000000000000000000000'"),
    "blob hash" to listOf("UPDATE room_master_table SET identity_hash=CAST(identity_hash AS BLOB)"),
    "nul suffix" to listOf("UPDATE room_master_table SET identity_hash=identity_hash || char(0) || 'tail'"),
    "null hash" to listOf("UPDATE room_master_table SET identity_hash=NULL"),
    "oversized hash" to listOf("UPDATE room_master_table SET identity_hash=zeroblob(4096)"),
    "extra row" to listOf("INSERT INTO room_master_table VALUES (43,'$hash')"),
    "wrong id" to listOf("UPDATE room_master_table SET id=43"),
    "case-insensitive column" to listOf("ALTER TABLE room_master_table RENAME TO old_identity",
        "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT COLLATE NOCASE)",
        "INSERT INTO room_master_table VALUES (42,'${hash.uppercase()}')", "DROP TABLE old_identity"),
    "coercible text id" to listOf("ALTER TABLE room_master_table RENAME TO old_identity",
        "CREATE TABLE room_master_table (id TEXT,identity_hash TEXT)",
        "INSERT INTO room_master_table VALUES ('42','$hash')", "DROP TABLE old_identity"),
    "duplicate id" to listOf("ALTER TABLE room_master_table RENAME TO old_identity",
        "CREATE TABLE room_master_table (id INTEGER,identity_hash TEXT)",
        "INSERT INTO room_master_table VALUES (42,'$hash'),(42,'$hash')", "DROP TABLE old_identity"),
)

private suspend fun assertDdlFailureRollsBack(point: DdlFailurePoint) {
    val reference = freshVersionThreeReference()
    for (version in 1..2) {
        val source = seedSource(version)
        val metadata = rawMasterMigrationMetadata(source.path)
        val probe = IssuanceMigrationProbe(point)
        assertCurrentOpenRefused(source.path, probe)
        assertTrue(probe.failureObservations.isNotEmpty(), "The failure must follow real fifth-table DDL")
        for (observation in probe.failureObservations) {
            assertSourceStamp(version, observation)
            assertEquals((ORIGINAL_PROTECTED_TABLES + ISSUANCE_SQL_TABLE).sorted(), observation.protectedTables)
            assertEquals(if (point == DdlFailurePoint.AFTER_TABLE) emptyList() else ISSUANCE_GUARDS.sorted(), observation.issuanceGuards)
        }
        assertEquals(1, probe.completions.count { it == ISSUANCE_SQL_TABLE })
        assertEquals(source.rows, rawSourceRows(source))
        assertEquals(metadata, rawMasterMigrationMetadata(source.path), "Every DDL/guard/identity/version change must roll back")
        // Reopen the separately generated ORIGINAL database, not a current file with forged metadata.
        if (version == 1) {
            val original = LegacyV1MasterFixture(source.path)
            try { assertOriginalSchema(original.database, 1); assertSourcePreserved(source, original.database) }
            finally { original.close() }
        } else {
            val original = LegacyV2MasterFixture(source.path)
            try { assertOriginalSchema(original.database, 2); assertSourcePreserved(source, original.database) }
            finally { original.close() }
        }
        assertEquals(metadata, rawMasterMigrationMetadata(source.path))
        val retried = openVersionThree(source.path)
        try {
            assertVersionThreeSchema(retried, reference.identity)
            assertSourcePreserved(source, retried)
            assertEquals(reference.guards, issuanceGuardSnapshot(retried))
            assertEmptyIssuance(retried)
        } finally { retried.close() }
    }
}

private enum class DdlFailurePoint { AFTER_TABLE, AFTER_GUARDS }

private data class DdlObservation(
    val phase: String,
    val version: Long,
    val identities: List<List<String>>,
    val protectedTables: List<String>,
    val issuanceGuards: List<String>,
)

private fun observeDdl(connection: SQLiteConnection, phase: String): DdlObservation = DdlObservation(
    phase,
    connection.prepare("PRAGMA user_version").use { check(it.step()); it.getLong(0) },
    connection.prepare(TYPED_IDENTITY_QUERY).use { buildList { while (it.step()) add((0..3).map(it::getText)) } },
    connection.prepare("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'protected_%' ORDER BY name").use {
        buildList { while (it.step()) add(it.getText(0)) }
    },
    connection.prepare("SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='$ISSUANCE_SQL_TABLE' ORDER BY name").use {
        buildList { while (it.step()) add(it.getText(0)) }
    },
)

private fun assertSourceStamp(version: Int, observation: DdlObservation) {
    assertEquals(version.toLong(), observation.version, "Room must retain the original version at ${observation.phase}")
    assertEquals(listOf(listOf("integer", "42", "text", originalIdentity(version))), observation.identities,
        "Room must retain the original identity at ${observation.phase}")
}

private fun assertHealthyCreateSequence(probe: IssuanceMigrationProbe, version: Int) {
    val expected = (if (version == 1) ORIGINAL_PROTECTED_TABLES else emptyList()) + ISSUANCE_SQL_TABLE
    assertEquals(expected, probe.preparations.toList())
    assertEquals(expected, probe.executions.toList())
    assertEquals(expected, probe.completions.toList())
    assertEquals(listOf("before-prepare", "before-step"), probe.fifthObservations.map { it.phase })
    for (observation in probe.fifthObservations) {
        assertSourceStamp(version, observation)
        assertEquals(ORIGINAL_PROTECTED_TABLES.sorted(), observation.protectedTables)
        assertTrue(observation.issuanceGuards.isEmpty())
    }
    assertTrue(probe.failureObservations.isEmpty())
}

/** Test-only driver interception; faults happen AFTER actual SQLite DDL, inside Room's transaction. */
private class IssuanceMigrationProbe(private val failure: DdlFailurePoint? = null) : SQLiteDriver {
    private val delegate = BundledSQLiteDriver()
    val preparations = CopyOnWriteArrayList<String>()
    val executions = CopyOnWriteArrayList<String>()
    val completions = CopyOnWriteArrayList<String>()
    val fifthObservations = CopyOnWriteArrayList<DdlObservation>()
    val failureObservations = CopyOnWriteArrayList<DdlObservation>()
    override val hasConnectionPool: Boolean get() = delegate.hasConnectionPool
    override fun open(fileName: String): SQLiteConnection {
        val connection = delegate.open(fileName)
        return object : SQLiteConnection by connection {
            override fun prepare(sql: String): SQLiteStatement {
                val table = TABLE_CREATE.find(sql)?.groupValues?.get(1)?.lowercase()
                val guard = TRIGGER_CREATE.find(sql)?.groupValues?.get(1)?.lowercase()
                if (table != null) preparations.add(table)
                if (table == ISSUANCE_SQL_TABLE) fifthObservations.add(observeDdl(connection, "before-prepare"))
                val statement = connection.prepare(sql)
                return object : SQLiteStatement by statement {
                    override fun step(): Boolean {
                        if (table != null) executions.add(table)
                        if (table == ISSUANCE_SQL_TABLE) fifthObservations.add(observeDdl(connection, "before-step"))
                        val result = statement.step()
                        if (table != null) completions.add(table)
                        if ((failure == DdlFailurePoint.AFTER_TABLE && table == ISSUANCE_SQL_TABLE) ||
                            (failure == DdlFailurePoint.AFTER_GUARDS && guard == "${ISSUANCE_SQL_TABLE}_no_delete")) {
                            failureObservations.add(observeDdl(connection, "after-actual-ddl"))
                            error("Synthetic failure after actual issuance DDL")
                        }
                        return result
                    }
                }
            }
        }
    }

    companion object {
        private val TABLE_CREATE = Regex("^\\s*CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"]?(protected_[a-z_]+)", RegexOption.IGNORE_CASE)
        private val TRIGGER_CREATE = Regex("^\\s*CREATE\\s+TRIGGER\\s+[`\"]?(protected_local_access_issuance_[a-z_]+)", RegexOption.IGNORE_CASE)
    }
}
