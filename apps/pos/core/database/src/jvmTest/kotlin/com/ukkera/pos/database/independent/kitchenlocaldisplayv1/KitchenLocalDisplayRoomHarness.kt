package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteStatement
import java.nio.file.Files
import java.nio.file.Path

/**
 * Detached file-backed storage for frozen DAO assertion slices. The generated production
 * Room database and DAO are used without binding runtime history, an endpoint, a peer or
 * an authenticated writer. This fixture supplies no positive Master execution ownership.
 */
internal class KitchenLocalDisplayRoomHarness private constructor(
    private val directory: Path,
    private val path: Path,
    internal var database: MasterRoomDatabase,
) : AutoCloseable {
    internal val dao: MasterDao get() = database.facts()
    private val parentScopes = mutableSetOf<KitchenDisplayPointFrozenInputs.Cell>()
    private val parentOperations = mutableSetOf<Pair<KitchenDisplayPointFrozenInputs.Cell, KitchenDisplayPointFrozenInputs.Cell>>()
    private var parentSequence = 0L

    internal suspend fun <T> withConnection(block: suspend (Transactor) -> T): T =
        database.useWriterConnection(block)

    /**
     * Keep foreign keys enabled. Matching raw parent keys provide referential plumbing only;
     * their other metadata is deliberately empty/invalid and cannot represent an original.
     */
    internal suspend fun insert(row: KitchenDisplayPointFrozenInputs.Row) = withConnection { connection ->
        val scope = row.cells[0]
        val operation = row.cells[1]
        if (parentScopes.add(scope)) {
            connection.usePrepared("""
                INSERT INTO branch_authority
                (scope, assignmentId, deviceId, epoch, revision, sequence, journalHash, writable, recoveryJson)
                VALUES (?, '', '', 0, 0, 0, '', 0, '')
            """.trimIndent()) { statement ->
                bind(statement, 1, scope)
                statement.step()
            }
        }
        if (parentOperations.add(scope to operation)) {
            connection.usePrepared("""
                INSERT INTO operation_result
                (scope, operationId, actorId, command, commandVersion, digest, outcome, result, resultHash,
                 sequence, epoch, committedAt, authenticationId, policyRevision)
                VALUES (?, ?, '', '', 0, '', '', x'', '', ?, 0, '', '', 0)
            """.trimIndent()) { statement ->
                bind(statement, 1, scope)
                bind(statement, 2, operation)
                statement.bindLong(3, ++parentSequence)
                statement.step()
            }
        }
        connection.usePrepared("""
            INSERT INTO outbox_intent (scope, operationId, ordinal, kind, body, bodyHash)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()) { statement ->
            row.cells.forEachIndexed { index, cell -> bind(statement, index + 1, cell) }
            statement.step()
        }
    }

    /** Actual storage classes and complete raw cell bytes, including malformed selectors. */
    internal suspend fun snapshot(): List<KitchenDisplayPointFrozenInputs.Row> = withConnection { connection ->
        connection.usePrepared("""
            SELECT typeof(scope), CAST(scope AS BLOB),
                   typeof(operationId), CAST(operationId AS BLOB),
                   typeof(ordinal), CAST(ordinal AS BLOB),
                   typeof(kind), CAST(kind AS BLOB),
                   typeof(body), CAST(body AS BLOB),
                   typeof(bodyHash), CAST(bodyHash AS BLOB)
            FROM outbox_intent
            ORDER BY typeof(scope), hex(CAST(scope AS BLOB)),
                     typeof(operationId), hex(CAST(operationId AS BLOB)),
                     typeof(ordinal), hex(CAST(ordinal AS BLOB))
        """.trimIndent()) { statement ->
            buildList {
                while (statement.step()) add(KitchenDisplayPointFrozenInputs.Row(List(6) { index ->
                    KitchenDisplayPointFrozenInputs.Cell(
                        KitchenDisplayPointFrozenInputs.StorageClass.valueOf(statement.getText(index * 2).uppercase()),
                        StoredBytes(statement.getBlob(index * 2 + 1)),
                    )
                }))
            }
        }
    }

    /** Reopen the same storage file; this is not a native Master restart. */
    internal fun reopen() {
        database.close()
        database = open(path)
    }

    override fun close() {
        try {
            database.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun bind(statement: SQLiteStatement, index: Int, cell: KitchenDisplayPointFrozenInputs.Cell) {
        val bytes = cell.rawBytes.copyBytes()
        when (cell.storageClass) {
            KitchenDisplayPointFrozenInputs.StorageClass.BLOB -> statement.bindBlob(index, bytes)
            KitchenDisplayPointFrozenInputs.StorageClass.TEXT ->
                statement.bindText(index, bytes.decodeToString(throwOnInvalidSequence = true))
            KitchenDisplayPointFrozenInputs.StorageClass.INTEGER ->
                statement.bindLong(index, bytes.decodeToString(throwOnInvalidSequence = true).toLong())
        }
    }

    internal companion object {
        internal fun create(): KitchenLocalDisplayRoomHarness {
            val directory = Files.createTempDirectory("kitchen-display-dao-")
            val path = directory.resolve("fixture.sqlite")
            return try {
                KitchenLocalDisplayRoomHarness(directory, path, open(path))
            } catch (failure: Throwable) {
                directory.toFile().deleteRecursively()
                throw failure
            }
        }

        private fun open(path: Path): MasterRoomDatabase = buildDurableRoom(
            Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize),
        )
    }
}
