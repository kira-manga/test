package com.ukkera.pos.database.independent.catalogreleasesourcev1

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.catalog.store.LocalCatalogReleaseCodecV1
import com.ukkera.pos.catalog.store.LocalPublishedCatalogReleaseV1
import com.ukkera.pos.catalog.store.LocalSignedCatalogReleaseV1
import com.ukkera.pos.database.BranchRow
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterRoomConstructor
import com.ukkera.pos.database.MasterRoomDatabase
import com.ukkera.pos.database.TransactionView
import com.ukkera.pos.database.buildDurableRoom
import com.ukkera.pos.database.requireDurableWriter
import com.ukkera.pos.database.storageKey
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature

/**
 * Disposable JCA-signed request bytes solely for the public envelope grammar.
 * No installed trust, original-company head ceremony, native completion, membership
 * or employee authorization is provided. Keys/signatures are never persisted.
 */
internal fun detachedReleaseEnvelope(definition: LocalPublishedCatalogReleaseV1): LocalSignedCatalogReleaseV1 {
    val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val canonical = LocalCatalogReleaseCodecV1.canonicalPublication(definition)
    val signature = try {
        Signature.getInstance("Ed25519").run { initSign(pair.private); update(canonical); sign() }
    } finally { canonical.fill(0) }
    val hex = try { signature.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') } }
    finally { signature.fill(0) }
    return LocalSignedCatalogReleaseV1(definition, CatalogReleaseSourceFrozenFixture.metadataId(801), hex)
}

/** A recording wrapper, never an accepting replacement for a production observation. */
internal class CatalogReleaseSourceObservedView(private val actual: TransactionView) : CommandReadView by actual {
    internal var reads: Int = 0
        private set
    internal var nullObservations: Int = 0
        private set

    override suspend fun originalNativeBusinessPublications(): NativeCompanyBusinessPublicationSnapshot? {
        reads++
        return actual.originalNativeBusinessPublications().also { if (it == null) nullObservations++ }
    }
}

internal data class CatalogReleaseSourceTableSnapshot(val columns: List<String>, val quotedRows: List<List<String>>)

/**
 * Actual disposable file-backed Room database/DAO/borrowed transaction, with the
 * production durability and schema guards intact. Detached scope metadata is not
 * native custody, a valid writer, a current employee or an admitted operation.
 */
internal class CatalogReleaseSourceDetachedRoom private constructor(
    private val directory: Path,
    private val file: Path,
    internal val scope: BranchScope,
    private var database: MasterRoomDatabase,
) : AutoCloseable {
    internal suspend fun insertInertScopeMetadata() = withConnection { connection ->
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            // Only an FK parent: no writable appointment, custody or grant.
            database.facts().insertBranch(BranchRow(scope.storageKey(), "", "", 0, 0, 0, "", false, ""))
        }
    }

    internal suspend fun <T> withView(block: suspend (Transactor, TransactionView) -> T): T =
        withConnection { connection ->
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val view = TransactionView(scope, database.facts())
                try { block(connection, view) } finally { view.close() }
            }
        }

    /** Exact column metadata and quoted values preserve every user-table row/BLOB. */
    internal suspend fun snapshot(): Map<String, CatalogReleaseSourceTableSnapshot> = withConnection { connection ->
        val tables = connection.usePrepared("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") {
            buildList { while (it.step()) add(it.getText(0)) }
        }
        check(tables.isNotEmpty())
        tables.associateWith { table ->
            checkSafeSqlName(table)
            val columns = connection.usePrepared("PRAGMA table_info(\"$table\")") { statement ->
                buildList { while (statement.step()) add(statement.getText(1)) }
            }
            check(columns.isNotEmpty())
            columns.forEach { checkSafeSqlName(it) }
            val quoted = columns.joinToString(",") { "quote(\"$it\")" }
            val rows = connection.usePrepared("SELECT $quoted FROM \"$table\" ORDER BY $quoted") { statement ->
                buildList { while (statement.step()) add(columns.indices.map { statement.getText(it) }) }
            }
            CatalogReleaseSourceTableSnapshot(columns, rows)
        }.also { check(Files.isRegularFile(file)) }
    }

    internal fun reopen() {
        database.close()
        database = open(file)
    }

    private suspend fun <T> withConnection(block: suspend (Transactor) -> T): T =
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            block(connection)
        }

    override fun close() {
        try { database.close() } finally { directory.toFile().deleteRecursively() }
    }

    internal companion object {
        internal fun create(): CatalogReleaseSourceDetachedRoom {
            val fixture = CatalogReleaseSourceFrozenFixture
            val directory = Files.createTempDirectory("catalog-release-source-")
            val file = directory.resolve("fixture.sqlite")
            return try {
                CatalogReleaseSourceDetachedRoom(directory, file,
                    BranchScope(fixture.company.organizationId, fixture.company.companyId, fixture.branchId), open(file))
            } catch (failure: Throwable) {
                directory.toFile().deleteRecursively()
                throw failure
            }
        }

        private fun open(file: Path): MasterRoomDatabase = buildDurableRoom(
            Room.databaseBuilder<MasterRoomDatabase>(file.toString(), MasterRoomConstructor::initialize),
        )

        private fun checkSafeSqlName(name: String) {
            check(name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' })
        }
    }
}

internal suspend fun Transactor.catalogReleaseSourceTotalChanges(): Long = usePrepared("SELECT total_changes()") {
    check(it.step())
    it.getLong(0)
}
