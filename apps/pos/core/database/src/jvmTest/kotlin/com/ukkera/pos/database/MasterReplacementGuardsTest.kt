package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.security.LocalSessions
import com.ukkera.pos.security.ProtectedLocalAccount
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.security.SessionTransition
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Actual generated SQLite schema/guards; raw synthetic DML, not authenticated application writes. */
class MasterReplacementGuardsTest {
    private val scope = TEST_SCOPE.storageKey()
    private val otherScope = TEST_SCOPE.copy(branchId = testId(9870)).storageKey()
    private val aliases = listOf("rowid", "_rowid_", "oid")

    @Test fun everyHistoricalInsertTableRejectsAllRowidAliasesWithEitherRecursionModeAndAcrossScopes() = runTest {
        withTemplate { template ->
            for (table in REPLACEMENT_OLD_TABLES) for (alias in aliases) for (recursive in listOf(false, true)) {
                for (crossScope in scopesFor(table)) withCopy(template, recursive) { connection ->
                    val (source, victim) = sides(table, crossScope)
                    parents(connection, table, source, victim)
                    insert(connection, table, values(table, victim), alias, 17L)
                    assertRejected(connection, "$table/$alias/recursive=$recursive/cross=$crossScope") {
                        insert(connection, table, values(table, source), alias, 17L, replace = true)
                    }
                }
            }
        }
    }

    @Test fun genuineV3StillDemonstratesInsertReplacementOnAllElevenOldTablesWithoutChangingItsGuards() = runTest {
        withTemplate(legacy = true) { template ->
            for (table in REPLACEMENT_OLD_TABLES) withCopy(template, recursive = false) { connection ->
                val (source, victim) = sides(table, true)
                parents(connection, table, source, victim)
                insert(connection, table, values(table, victim), "rowid", 17L)
                val before = replacementRows(connection)
                insert(connection, table, values(table, source), "rowid", 17L, replace = true)
                assertEquals(1L, changes(connection), table)
                val after = replacementRows(connection)
                assertEquals(1, before.getValue(table).size)
                assertEquals(1, after.getValue(table).size)
                assertNotEquals(before.getValue(table), after.getValue(table), "Legacy victim must actually be replaced: $table")
                assertEquals(before.filterKeys { it != table }, after.filterKeys { it != table })
                cleanReferences(connection)
            }
        }
    }

    @Test fun negativeZeroAndBoundaryRowidsNeverHaveAnInsertCollisionExemption() = runTest {
        withTemplate { template ->
            for (table in REPLACEMENT_OLD_TABLES) for (rowid in listOf(Long.MIN_VALUE, -17L, -1L, 0L, Long.MAX_VALUE)) {
                withCopy(template) { connection ->
                    val (source, victim) = sides(table, true)
                    parents(connection, table, source, victim)
                    insert(connection, table, values(table, victim), "rowid", rowid)
                    for (alias in aliases) assertRejected(connection, "$table/$alias/$rowid") {
                        insert(connection, table, values(table, source), alias, rowid, replace = true)
                    }
                }
            }
        }
    }

    @Test fun integerCoercibleRowidBindingsCannotEvadeInsertGuardsAndInvalidBindingsDoNotWrite() = runTest {
        withTemplate { template ->
            for (table in REPLACEMENT_OLD_TABLES) withCopy(template) { connection ->
                val (source, victim) = sides(table, true)
                parents(connection, table, source, victim)
                insert(connection, table, values(table, victim), "rowid", 17L)
                for (rowid in listOf<Any>("17", "017", "17.0", 17.0, "17tail", byteArrayOf(49, 55), "9223372036854775808")) {
                    assertRejected(connection, "$table/coercible-or-invalid/$rowid") {
                        insert(connection, table, values(table, source), "oid", rowid, replace = true)
                    }
                }
            }
        }
    }

    @Test fun storedMinusOneConservativelyRefusesAutomaticInsertionButOrdinaryAutomaticInsertionWorks() = runTest {
        withTemplate { template ->
            for (table in REPLACEMENT_OLD_TABLES) withCopy(template) { connection ->
                val (source, victim) = sides(table, true)
                parents(connection, table, source, victim)
                insert(connection, table, values(table, victim), "rowid", -1L)
                assertRejected(connection, "$table/automatic/-1-sentinel") { insert(connection, table, values(table, source)) }
            }
            for (table in REPLACEMENT_OLD_TABLES) withCopy(template) { connection ->
                val (source, victim) = sides(table, true)
                parents(connection, table, source, victim)
                insert(connection, table, values(table, victim))
                insert(connection, table, values(table, source))
                assertEquals(2, replacementRows(connection).getValue(table).size)
                cleanReferences(connection)
            }
        }
    }

    @Test fun everyMutableTableRejectsCollisionAndUnusedRowidMovesThroughEveryAlias() = runTest {
        withTemplate { template ->
            for (table in REPLACEMENT_MUTABLE_TABLES) for (recursive in listOf(false, true)) {
                for (crossScope in scopesFor(table)) withCopy(template, recursive) { connection ->
                    val (source, victim) = sides(table, crossScope)
                    parents(connection, table, source, victim)
                    insert(connection, table, values(table, source), "rowid", 1L)
                    insert(connection, table, values(table, victim), "rowid", 2L)
                    for (alias in aliases) for (next in listOf(Long.MIN_VALUE, -1L, 0L, 2L, 17L, Long.MAX_VALUE)) {
                        assertRejected(connection, "$table/$alias/$next/recursive=$recursive/cross=$crossScope") {
                            update(connection, table, linkedMapOf(alias to next), advanceRevision = hasRevision(table))
                        }
                    }
                }
            }
        }
    }

    @Test fun coercedSelfRowidsRemainValidButCoercedCollisionsAndInvalidUpdateBindingsDoNotWrite() = runTest {
        withTemplate { template ->
            for (table in REPLACEMENT_MUTABLE_TABLES) for (alias in aliases) withCopy(template) { connection ->
                val (source, victim) = sides(table, true)
                parents(connection, table, source, victim)
                insert(connection, table, values(table, source), "rowid", 1L)
                insert(connection, table, values(table, victim), "rowid", 2L)
                for (self in listOf<Any>("1", "01", "1.0", 1.0)) {
                    val before = replacementRows(connection)
                    update(connection, table, linkedMapOf(alias to self), hasRevision(table))
                    assertEquals(1L, changes(connection), "$table/$alias/self=$self")
                    val after = replacementRows(connection)
                    assertEquals(before.keys, after.keys)
                    assertEquals(before.getValue(table).map { it.first() }, after.getValue(table).map { it.first() })
                    assertEquals(before.getValue(table)[1], after.getValue(table)[1], "Victim is unchanged")
                    assertEquals(before.filterKeys { it != table }, after.filterKeys { it != table })
                    if (hasRevision(table)) assertNotEquals(before.getValue(table)[0], after.getValue(table)[0])
                    else assertEquals(before, after)
                }
                for (invalidOrCollision in listOf<Any?>("2", "02", "2.0", 2.0, "1tail", 1.25, null,
                    byteArrayOf(49), "9223372036854775808")) {
                    assertRejected(connection, "$table/$alias/changed-or-invalid=$invalidOrCollision") {
                        update(connection, table, linkedMapOf(alias to invalidOrCollision), hasRevision(table))
                    }
                }
                cleanReferences(connection)
            }
        }
    }

    @Test fun branchAndClaimIdentityMovesAreRejectedEvenWhenNoOtherRowWouldBeReplaced() = runTest {
        withTemplate { template ->
            for (table in listOf("branch_authority", "unique_claim")) withCopy(template) { connection ->
                val (source, victim) = sides(table, true)
                parents(connection, table, source, victim)
                insert(connection, table, values(table, source), "rowid", 1L)
                insert(connection, table, values(table, victim), "rowid", 2L)
                val changes = if (table == "branch_authority") listOf("scope" to "unused-branch") else
                    listOf("scope" to otherScope, "namespace" to "unused-namespace", "key" to "unused-key")
                for ((key, value) in changes) assertRejected(connection, "$table/unused/$key") {
                    update(connection, table, linkedMapOf(key to value), advanceRevision = hasRevision(table))
                }
            }
        }
    }

    @Test fun oldLogicalKeyGuardsStillRejectCollisionsWithValidRevisionAdvancesAndUnchangedRowid() = runTest {
        withTemplate { template ->
            for (table in listOf("aggregate_state") + REPLACEMENT_PROTECTED_TABLES) withCopy(template) { connection ->
                val (source, victim) = sides(table, false)
                parents(connection, table, source, victim)
                insert(connection, table, values(table, source), "rowid", 1L)
                insert(connection, table, values(table, victim), "rowid", 2L)
                val victimKeys = values(table, victim).filterKeys { it in keyColumns(table) }
                assertRejected(connection, "$table/old-key-collision") { update(connection, table, victimKeys, true) }
            }
        }
    }

    @Test fun nullAffinityBlobCaseAndEmbeddedNulLogicalKeyChangesNeverLoseRows() = runTest {
        withTemplate { template ->
            for (table in REPLACEMENT_MUTABLE_TABLES) withCopy(template) { connection ->
                // Ordinary scope UUIDs contain digits only in this fixture. Use
                // actual differing case-sensitive TEXT here, not a vacuous upper-case no-op.
                val (source, victim) = if (table == "branch_authority")
                    Side(1, "guard-source") to Side(2, "guard-victim") else sides(table, true)
                parents(connection, table, source, victim)
                insert(connection, table, values(table, source), "rowid", 1L)
                insert(connection, table, values(table, victim), "rowid", 2L)
                val key = keyColumns(table).last()
                val old = values(table, source).getValue(key).toString()
                assertNotEquals(old, old.uppercase(), "Case mutation must actually change the logical key")
                for (value in listOf<Any?>(null, 17L, "017", old.uppercase(), old.toByteArray(), old + '\u0000' + "tail")) {
                    assertRejected(connection, "$table/$key/key-representation") {
                        update(connection, table, linkedMapOf(key to value), hasRevision(table))
                    }
                }
            }
        }
    }

    @Test fun identityEquivalentTextAffinityUpdatesKeepTheirRowsAndStillAdvanceRevisions() = runTest {
        withTemplate { template ->
            for (table in listOf("aggregate_state") + REPLACEMENT_PROTECTED_TABLES) withCopy(template) { connection ->
                val (source, victim) = sides(table, false)
                parents(connection, table, source, victim)
                val key = keyColumns(table).last()
                insert(connection, table, values(table, source) + (key to "17"), "rowid", 1L)
                val before = replacementRows(connection)
                update(connection, table, linkedMapOf(key to 17L), true)
                assertEquals(1L, changes(connection), table)
                val after = replacementRows(connection)
                assertEquals(1, after.getValue(table).size)
                connection.prepare("SELECT rowid,typeof(`$key`),`$key`,revision FROM `$table`").use {
                    check(it.step()); assertEquals(1L, it.getLong(0)); assertEquals("text", it.getText(1))
                    assertEquals("17", it.getText(2)); assertEquals(2L, it.getLong(3)); assertFalse(it.step())
                }
                val columns = connection.prepare("PRAGMA table_info(`$table`)").use {
                    buildList { while (it.step()) add(it.getText(1)) }
                }
                val expected = before.getValue(table).single().toMutableList()
                expected[columns.indexOf("revision") + 1] = "integer:32" // Exact typed integer2, only changed cell.
                assertEquals(listOf(expected), after.getValue(table))
                assertEquals(before.filterKeys { it != table }, after.filterKeys { it != table })
                cleanReferences(connection)
            }
        }
    }

    @Test fun oldPrimaryAndAlternateUniqueInsertConflictsRemainRejected() = runTest {
        withTemplate { template ->
            for (table in REPLACEMENT_OLD_TABLES) withCopy(template) { connection ->
                val (source, victim) = sides(table, false)
                parents(connection, table, source, victim)
                val row = values(table, source)
                insert(connection, table, row, "rowid", 1L)
                assertRejected(connection, "$table/old-primary") { insert(connection, table, row, "rowid", 9L, true) }
                if (table == "operation_result") assertRejected(connection, "operation/alternate-scope-epoch-sequence") {
                    insert(connection, table, row + ("operationId" to "different-operation"), "rowid", 9L, true)
                }
                if (table == "journal_envelope") assertRejected(connection, "journal/alternate-scope-operation") {
                    insert(connection, table, row + ("sequence" to 99L), "rowid", 9L, true)
                }
            }
        }
    }

    @Test fun immutableFinancialAndIssuanceUpdateDeletePoliciesStillRefuse() = runTest {
        withTemplate { template ->
            for (table in listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent", REPLACEMENT_ISSUANCE_TABLE)) {
                withCopy(template) { connection ->
                    val (source, victim) = sides(table, false)
                    parents(connection, table, source, victim)
                    insert(connection, table, values(table, source), "rowid", 1L)
                    assertRejected(connection, "$table/immutable-update") { update(connection, table, linkedMapOf("rowid" to 1L), false) }
                    assertRejected(connection, "$table/immutable-delete") { execute(connection, "DELETE FROM `$table` WHERE rowid=1") }
                }
            }
        }
    }

    @Test fun multirowStatementAbortUndoesItsEarlierRowsButNotPriorStatementsWhenCaught() = runTest {
        withTemplate { template ->
            for (reverseOrder in listOf(false, true)) withCopy(template) { connection ->
                setGuardCreationOrder(connection, reverseOrder)
                val (source, victim) = sides("aggregate_state", false)
                parents(connection, "aggregate_state", source, victim)
                insert(connection, "aggregate_state", values("aggregate_state", source), "rowid", 1L)
                insert(connection, "aggregate_state", values("aggregate_state", victim), "rowid", 2L)
                execute(connection, "BEGIN IMMEDIATE")
                update(connection, "aggregate_state", emptyMap(), true)
                assertEquals(1L, changes(connection))
                connection.prepare("SELECT revision FROM aggregate_state WHERE rowid=1").use {
                    check(it.step()); assertEquals(2L, it.getLong(0)); assertFalse(it.step())
                }
                val afterEarlierStatement = replacementRows(connection)
                assertRejected(connection, "multirow UPDATE statement only") {
                    // Refusal must depend on row1 having actually advanced INSIDE
                    // this statement. Lazy nested CASE first reads the subquery on
                    // row2; if row2 is visited first the refusal assertion fails.
                    execute(connection, "UPDATE OR REPLACE aggregate_state SET revision=revision+1, " +
                        "rowid=CASE WHEN rowid=2 THEN CASE WHEN " +
                        "(SELECT revision FROM aggregate_state WHERE rowid=1)=3 THEN 1 ELSE rowid END ELSE rowid END")
                }
                assertEquals(afterEarlierStatement, replacementRows(connection))
                execute(connection, "COMMIT")
                assertEquals(afterEarlierStatement, replacementRows(connection), "Caught ABORT must not undo an earlier statement")
            }
        }
    }

    @Test fun multirowInsertAbortRestoresTheWholeStatementInEitherGuardCreationOrder() = runTest {
        withTemplate { template ->
            for (reverseOrder in listOf(false, true)) withCopy(template) { connection ->
                setGuardCreationOrder(connection, reverseOrder)
                val (source, victim) = sides("aggregate_state", false)
                parents(connection, "aggregate_state", source, victim)
                insert(connection, "aggregate_state", values("aggregate_state", source), "rowid", 1L)
                assertRejected(connection, "multirow insert") {
                    execute(connection, "INSERT OR REPLACE INTO aggregate_state " +
                        "(rowid,scope,kind,id,revision,schema,payload,payloadHash) " +
                        "SELECT 9,scope,kind,'new-first',1,schema,payload,payloadHash FROM aggregate_state WHERE rowid=1 " +
                        // Conflict is with a row inserted earlier by THIS statement,
                        // not the preexisting row1; either visitation order has a prefix.
                        "UNION ALL SELECT 9,scope,kind,'new-second',1,schema,payload,payloadHash FROM aggregate_state WHERE rowid=1")
                }
            }
        }
    }

    @Test fun uncaughtGuardFailureRollsBackTheWholeActualRoomTransaction() = runTest {
        val path = Files.createTempDirectory("ukkera-replacement-room-rollback-").resolve("master.db")
        val database = current(path)
        try {
            database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                database.facts().insertBranch(BranchRow(scope, "assignment", "device", 1, 0, 0, "0".repeat(64), true, "{}"))
                val before = rawReplacementRows(path)
                assertFailsWith<SQLiteException> {
                    connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                        database.facts().insertClaim(ClaimRow(scope, "guard-test", "first", "owner"))
                        connection.usePrepared("UPDATE OR REPLACE branch_authority SET rowid=rowid+100") { it.step() }
                    }
                }
                assertEquals(before, rawReplacementRows(path))
            }
        } finally { database.close() }
    }

    @Test fun realDaosStillInsertAdvancePayloadTerminalizeAndReleaseThenReacquireClaims() = runTest {
        val fixture = LocalSecurityStoreFixture()
        try {
            val original = fixture.defaultAccount(generation = Long.MAX_VALUE - 1)
            fixture.seed(account = original)
            fixture.store.transaction { transaction ->
                // Real account terminalization retains immutable credentials/session history.
                val terminal = assertIs<SessionTransition.Applied>(LocalSessions.disable(original.state)).state
                assertTrue(transaction.replaceAccount(original.state.revision, terminal))
                val saved = assertNotNull(transaction.readProtectedAccount(TEST_SCOPE, TEST_ACTOR))
                assertFalse(saved.state.enabled)
                assertEquals(Revision(Long.MAX_VALUE), saved.state.revision)
                assertEquals(Long.MAX_VALUE, saved.state.generation.value)
                assertEquals(original.state.sessions.map { it.copy(status = SessionStatus.REVOKED) }, saved.state.sessions)
                assertEquals(original.credentialRevision, saved.credentialRevision)
                assertContentEquals(ProtectedLocalSecurityCodec.encodeAccount(ProtectedLocalAccount(
                    terminal, original.credentialRevision, original.credential)),
                    ProtectedLocalSecurityCodec.encodeAccount(saved))
            }
            val database = fixture.database
            database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val dao = database.facts()
                    val before = "first".encodeToByteArray()
                    val after = "second".encodeToByteArray()
                    val aggregate = AggregateRow(scope, "guard-test", "mutable", 1, "guard-test/v1", before, sha256(before).value)
                    dao.insertAggregate(aggregate)
                    assertEquals(1, dao.replaceAggregate(scope, aggregate.kind, aggregate.id, 1, 2, aggregate.schema,
                        after, sha256(after).value))
                    val saved = assertNotNull(dao.aggregate(scope, aggregate.kind, aggregate.id))
                    assertEquals(2L, saved.revision); assertContentEquals(after, saved.payload)
                    assertEquals(sha256(after).value, saved.payloadHash)
                    val row = ClaimRow(scope, "guard-test", "custody", "owner-a")
                    dao.insertClaim(row)
                    assertEquals(1, dao.releaseClaim(scope, row.namespace, row.key, row.ownerId))
                    dao.insertClaim(row.copy(ownerId = "owner-b"))
                    assertEquals("owner-b", assertNotNull(dao.claim(scope, row.namespace, row.key)).ownerId)
                }
            }
        } finally { fixture.close() }
    }

    private data class Side(val index: Long, val scope: String)
    private fun scopesFor(table: String) = if (table == "branch_authority") listOf(true) else listOf(false, true)
    private fun sides(table: String, crossScope: Boolean): Pair<Side, Side> = Side(1, scope) to
        Side(2, if (crossScope || table == "branch_authority") otherScope else scope)

    private fun values(table: String, side: Side): Map<String, Any?> {
        val id = "guard-${side.index}"
        val bytes = id.encodeToByteArray()
        return when (table) {
            "branch_authority" -> linkedMapOf("scope" to side.scope, "assignmentId" to id, "deviceId" to id,
                "epoch" to 1L, "revision" to 0L, "sequence" to 0L, "journalHash" to "0".repeat(64), "writable" to 1L, "recoveryJson" to "{}")
            "operation_result" -> linkedMapOf("scope" to side.scope, "operationId" to id, "actorId" to id, "command" to "guard-test",
                "commandVersion" to 1L, "digest" to sha256(bytes).value, "outcome" to "committed", "result" to bytes,
                "resultHash" to sha256(bytes).value, "sequence" to side.index, "epoch" to 1L, "committedAt" to TEST_TIME.toString(),
                "authenticationId" to id, "policyRevision" to 1L)
            "aggregate_state" -> linkedMapOf("scope" to side.scope, "kind" to "guard-test", "id" to id, "revision" to 1L,
                "schema" to "guard-test/v1", "payload" to bytes, "payloadHash" to sha256(bytes).value)
            "unique_claim" -> linkedMapOf("scope" to side.scope, "namespace" to "guard-test", "key" to id, "ownerId" to id)
            "journal_envelope" -> linkedMapOf("scope" to side.scope, "sequence" to side.index, "operationId" to id,
                "assignmentId" to id, "epoch" to 1L, "previousHash" to "0".repeat(64), "hash" to sha256(bytes).value, "envelope" to bytes)
            "audit_event" -> linkedMapOf("id" to id, "scope" to side.scope, "operationId" to id, "kind" to "guard-test",
                "actorId" to id, "originalActorId" to id, "policyRevision" to 1L, "recordedAt" to TEST_TIME.toString(), "digest" to sha256(bytes).value)
            "outbox_intent" -> linkedMapOf("scope" to side.scope, "operationId" to id, "ordinal" to 0L, "kind" to "guard-test",
                "body" to bytes, "bodyHash" to sha256(bytes).value)
            else -> {
                check(table in REPLACEMENT_PROTECTED_TABLES + REPLACEMENT_ISSUANCE_TABLE)
                val keys = linkedMapOf<String, Any?>("scope" to side.scope)
                when (table) {
                    "protected_device_admission" -> keys["deviceId"] = id
                    REPLACEMENT_ISSUANCE_TABLE -> keys["sessionId"] = id
                    else -> keys["actorId"] = id
                }
                if (table == "protected_login_throttle") keys["dimensionKey"] = "account"
                keys + linkedMapOf("schemaVersion" to 1L, "formatVersion" to 1L, "algorithm" to "AES-256-GCM",
                    "revision" to if (table == REPLACEMENT_ISSUANCE_TABLE) 0L else 1L, "keyId" to "synthetic-key",
                    "nonce" to ByteArray(12) { side.index.toByte() }, "ciphertextAndTag" to ByteArray(32) { side.index.toByte() })
            }
        }
    }

    private fun keyColumns(table: String): List<String> = when (table) {
        "branch_authority" -> listOf("scope")
        "aggregate_state" -> listOf("scope", "kind", "id")
        "unique_claim" -> listOf("scope", "namespace", "key")
        "protected_device_admission" -> listOf("scope", "deviceId")
        "protected_login_throttle" -> listOf("scope", "actorId", "dimensionKey")
        else -> listOf("scope", "actorId")
    }

    private fun parents(connection: SQLiteConnection, table: String, source: Side, victim: Side) {
        if (table != "branch_authority") for (side in listOf(source, victim).distinctBy { it.scope }) {
            insert(connection, "branch_authority", values("branch_authority", side))
        }
        if (table in listOf("journal_envelope", "outbox_intent")) for (side in listOf(source, victim)) {
            insert(connection, "operation_result", values("operation_result", side))
        }
    }

    private fun insert(connection: SQLiteConnection, table: String, fields: Map<String, Any?>,
                       alias: String? = null, rowid: Any? = null, replace: Boolean = false) {
        val row = linkedMapOf<String, Any?>()
        if (alias != null) row[alias] = rowid
        row.putAll(fields)
        val sql = "INSERT ${if (replace) "OR REPLACE " else ""}INTO `$table` (${row.keys.joinToString(",") { "`$it`" }}) " +
            "VALUES (${row.keys.joinToString(",") { "?" }})"
        connection.prepare(sql).use { statement -> row.values.forEachIndexed { i, value -> bind(statement, i + 1, value) }; statement.step() }
    }

    private fun hasRevision(table: String) = table != "unique_claim"
    private fun update(connection: SQLiteConnection, table: String, fields: Map<String, Any?>, advanceRevision: Boolean) {
        val assignments = fields.keys.map { "`$it`=?" } + if (advanceRevision) listOf("revision=revision+1") else emptyList()
        connection.prepare("UPDATE OR REPLACE `$table` SET ${assignments.joinToString(",")} WHERE rowid=1").use { statement ->
            fields.values.forEachIndexed { i, value -> bind(statement, i + 1, value) }; statement.step()
        }
    }

    private fun bind(statement: SQLiteStatement, position: Int, value: Any?) = when (value) {
        null -> statement.bindNull(position)
        is String -> statement.bindText(position, value)
        is Long -> statement.bindLong(position, value)
        is Double -> statement.bindDouble(position, value)
        is ByteArray -> statement.bindBlob(position, value)
        else -> error("Unsupported synthetic SQL type")
    }

    private fun execute(connection: SQLiteConnection, sql: String) { connection.prepare(sql).use { it.step() } }
    private fun changes(connection: SQLiteConnection): Long = connection.prepare("SELECT changes()").use { check(it.step()); it.getLong(0) }
    private fun cleanReferences(connection: SQLiteConnection) { connection.prepare("PRAGMA foreign_key_check").use { assertFalse(it.step()) } }
    private fun assertRejected(connection: SQLiteConnection, label: String, block: () -> Unit) {
        val before = replacementRows(connection)
        assertFailsWith<SQLiteException>(label) { block() }
        assertEquals(before, replacementRows(connection), label)
        cleanReferences(connection)
    }

    private fun current(path: Path): LegacyV4MasterDatabase = buildLegacyV4MasterRoom(path)

    /** Copying this closed EMPTY synthetic schema is test setup, not a recovery/custody protocol. */
    private suspend fun withTemplate(legacy: Boolean = false, block: (Path) -> Unit) {
        val path = Files.createTempDirectory("ukkera-replacement-guard-template-").resolve("empty.db")
        val database = if (legacy) buildLegacyV3MasterRoom(path) else current(path)
        try {
            database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.usePrepared("PRAGMA user_version") { check(it.step()); assertEquals(if (legacy) 3L else 4L, it.getLong(0)) }
                connection.usePrepared("SELECT count(*) FROM sqlite_master WHERE type='trigger'") {
                    check(it.step()); assertEquals(if (legacy) 33L else 51L, it.getLong(0))
                }
            }
        } finally { database.close() }
        block(path)
    }

    private fun withCopy(template: Path, recursive: Boolean = false, block: (SQLiteConnection) -> Unit) {
        val path = Files.createTempDirectory("ukkera-replacement-guard-case-").resolve("master.db")
        Files.copy(template, path)
        BundledSQLiteDriver().open(path.toString()).use { connection ->
            execute(connection, "PRAGMA foreign_keys=ON")
            execute(connection, "PRAGMA recursive_triggers=${if (recursive) "ON" else "OFF"}")
            connection.prepare("PRAGMA foreign_keys").use { check(it.step()); assertEquals(1L, it.getLong(0)) }
            connection.prepare("PRAGMA recursive_triggers").use { check(it.step()); assertEquals(if (recursive) 1L else 0L, it.getLong(0)) }
            block(connection)
        }
    }

    private fun setGuardCreationOrder(connection: SQLiteConnection, reversed: Boolean) {
        val before = replacementGuardRows(connection)
        for (guard in before) execute(connection, "DROP TRIGGER `${guard[0]}`")
        for (guard in if (reversed) before.asReversed() else before) execute(connection, guard[2])
        assertEquals(before, replacementGuardRows(connection), "Only creation order changes; every guard body remains exact")
    }
}
