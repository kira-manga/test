package com.ukkera.pos.database

import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.OperationId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json

/**
 * Genuine separately generated legacy schema, not a version-following StoreFixture,
 * a v2 file with user_version changed, or a production migration/initializer.
 * Keep the seven original entities here when the current master grows new tables.
 */
@Database(
    entities = [BranchRow::class, OperationRow::class, AggregateRow::class, ClaimRow::class,
        JournalRow::class, AuditRow::class, OutboxRow::class],
    version = 1,
    exportSchema = false,
)
internal abstract class LegacyV1MasterDatabase : RoomDatabase() {
    abstract fun facts(): MasterDao
}

/** Versioned SYNTHETIC fixture facts; this is not an accepting legacy auth/runtime. */
internal class LegacyV1MasterFixture(
    val path: Path = Files.createTempDirectory("ukkera-legacy-v1-test-").resolve("master.db"),
) {
    val database = buildDurableRoom(
        Room.databaseBuilder<LegacyV1MasterDatabase>(path.toString(), ::LegacyV1MasterDatabase_Impl),
    )
    val request = MutationRequest(
        OperationId(testId(20).value), CounterHandler().key,
        StoredBytes(Json.encodeToString(CounterInput(delta = 7, claim = "legacy-custody")).encodeToByteArray()),
    )
    val resultBytes = "7".encodeToByteArray()
    private val normalized = CounterHandler().normalize(request.payload)
    private val digest = semanticDigest(TEST_SCOPE, TEST_ACTOR, request.command, normalized)
    private val priorHash = "0".repeat(64)
    private val resource = testId(10)
    val operation = OperationRow(
        TEST_SCOPE.storageKey(), request.operationId.value, TEST_ACTOR.value,
        request.command.name, request.command.version, digest.value, "committed",
        resultBytes, sha256(resultBytes).value, 1, TEST_AUTHORITY.epoch.value,
        TEST_TIME.toString(), testId(7).value, 1,
    )
    val envelope: ByteArray = frame(
        listOf(
            "ukkera.journal/v1", TEST_SCOPE.storageKey(), TEST_AUTHORITY.assignmentId.value,
            "1", "1", request.operationId.value, TEST_ACTOR.value, request.command.name,
            "1", testId(7).value, "1", digest.value, "committed", TEST_TIME.toString(),
            operation.resultHash, priorHash,
        ).map(String::encodeToByteArray) + listOf(
            normalized.copyBytes(), resultBytes,
            frame(listOf(
                frame(listOf("qualification.counter", resource.value, "1", "counter/v1")
                    .map(String::encodeToByteArray) + resultBytes),
                frame(listOf("claim.acquire", "qualification.custody", "legacy-custody", resource.value)
                    .map(String::encodeToByteArray)),
            )),
            frame(listOf(frame(listOf("qualification.receipt".encodeToByteArray(), resultBytes)))),
        ),
    )

    /** Seeds explicit valid v1 records via its real DAO; it does not execute a v2 kernel. */
    suspend fun seed() {
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val dao = database.facts()
                val envelopeHash = sha256(envelope).value
                dao.insertBranch(BranchRow(
                    TEST_SCOPE.storageKey(), TEST_AUTHORITY.assignmentId.value, TEST_AUTHORITY.deviceId.value,
                    1, 0, 0, priorHash, true,
                    Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(TEST_SCOPE.branchId)),
                ))
                val enabled = "enabled".encodeToByteArray()
                dao.insertAggregate(AggregateRow(
                    TEST_SCOPE.storageKey(), AUTH_KEY.kind, AUTH_KEY.id.value, 1, "auth/v1",
                    enabled, sha256(enabled).value,
                ))
                dao.insertOperation(operation)
                dao.insertAggregate(AggregateRow(
                    TEST_SCOPE.storageKey(), "qualification.counter", resource.value, 1, "counter/v1",
                    resultBytes, operation.resultHash,
                ))
                dao.insertClaim(ClaimRow(TEST_SCOPE.storageKey(), "qualification.custody", "legacy-custody", resource.value))
                dao.insertJournal(JournalRow(
                    TEST_SCOPE.storageKey(), 1, request.operationId.value,
                    TEST_AUTHORITY.assignmentId.value, 1, priorHash, envelopeHash, envelope,
                ))
                dao.insertAudit(AuditRow(
                    testId(90).value, TEST_SCOPE.storageKey(), request.operationId.value,
                    "command.committed", TEST_ACTOR.value, TEST_ACTOR.value, 1, TEST_TIME.toString(), digest.value,
                ))
                val upload = frame(listOf("ukkera.journal-upload/v1", TEST_SCOPE.branchId.value, "1", "1", envelopeHash)
                    .map(String::encodeToByteArray))
                dao.insertOutbox(listOf(
                    OutboxRow(TEST_SCOPE.storageKey(), request.operationId.value, 0, "journal.upload", upload, sha256(upload).value),
                    OutboxRow(TEST_SCOPE.storageKey(), request.operationId.value, 1, "qualification.receipt", resultBytes, operation.resultHash),
                ))
                check(dao.advance(TEST_SCOPE.storageKey(), 0, 1, 1, envelopeHash) == 1)
            }
        }
    }

    fun close() = database.close()
}

/** Exact typed-cell bytes across the seven original tables; excludes Room's version identity. */
internal suspend fun legacyRowSnapshot(database: RoomDatabase): Map<String, List<List<String>>> =
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val orders = linkedMapOf(
                "branch_authority" to "scope", "operation_result" to "scope,operationId",
                "aggregate_state" to "scope,kind,id", "unique_claim" to "scope,namespace,`key`",
                "journal_envelope" to "scope,epoch,sequence", "audit_event" to "id",
                "outbox_intent" to "scope,operationId,ordinal",
            )
            buildMap {
                for ((table, order) in orders) {
                    val columns = usePrepared("PRAGMA table_info(`$table`)") { statement ->
                        buildList { while (statement.step()) add(statement.getText(1)) }
                    }
                    check(columns.isNotEmpty())
                    val projection = columns.joinToString(",") {
                        "typeof(`$it`) || ':' || hex(CAST(`$it` AS BLOB))"
                    }
                    put(table, usePrepared("SELECT $projection FROM `$table` ORDER BY $order") { statement ->
                        buildList {
                            while (statement.step()) add(columns.indices.map(statement::getText))
                        }
                    })
                }
            }
        }
    }

/** Read-only raw observer for refused files whose Room identity/guards are deliberately corrupt. */
internal fun rawLegacyRowSnapshot(path: Path): Map<String, List<List<String>>> =
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("PRAGMA query_only=ON").use { it.step() }
        connection.prepare("BEGIN").use { it.step() }
        try {
            val orders = linkedMapOf(
                "branch_authority" to "scope", "operation_result" to "scope,operationId",
                "aggregate_state" to "scope,kind,id", "unique_claim" to "scope,namespace,`key`",
                "journal_envelope" to "scope,epoch,sequence", "audit_event" to "id",
                "outbox_intent" to "scope,operationId,ordinal",
            )
            buildMap {
                for ((table, order) in orders) {
                    val columns = connection.prepare("PRAGMA table_info(`$table`)").use { statement ->
                        buildList { while (statement.step()) add(statement.getText(1)) }
                    }
                    check(columns.isNotEmpty())
                    val projection = columns.joinToString(",") { "typeof(`$it`) || ':' || hex(CAST(`$it` AS BLOB))" }
                    put(table, connection.prepare("SELECT $projection FROM `$table` ORDER BY $order").use { statement ->
                        buildList { while (statement.step()) add(columns.indices.map(statement::getText)) }
                    })
                }
            }
        } finally {
            connection.prepare("ROLLBACK").use { it.step() }
        }
    }
