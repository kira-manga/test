package com.ukkera.pos.database

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.security.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs

internal const val LEGACY_V3_ROOM_IDENTITY = "239f7ef6fc1afb6637a58c9b69668d42"

/** Frozen v3-only queries, rather than inheriting future current-master tables. */
@Dao
internal interface LegacyV3IssuanceDao {
    @Insert suspend fun insert(row: ProtectedLocalAccessIssuanceRow)

    @Query("SELECT * FROM protected_local_access_issuance WHERE scope=:scope AND sessionId=:sessionId")
    suspend fun read(scope: String, sessionId: String): ProtectedLocalAccessIssuanceRow?
}

/** Genuine separately KSP-generated twelve-entity v3. Never restamp a v4 database as v3. */
@Database(
    entities = [BranchRow::class, OperationRow::class, AggregateRow::class, ClaimRow::class,
        JournalRow::class, AuditRow::class, OutboxRow::class, ProtectedLocalAccountRow::class,
        ProtectedEffectivePolicyRow::class, ProtectedDeviceAdmissionRow::class,
        ProtectedLoginThrottleRow::class, ProtectedLocalAccessIssuanceRow::class],
    version = 3,
    exportSchema = false,
)
internal abstract class LegacyV3MasterDatabase : RoomDatabase() {
    abstract fun facts(): MasterDao
    abstract fun protectedV2(): LegacyV2ProtectedDao
    abstract fun localAccessIssuance(): LegacyV3IssuanceDao
}

internal fun buildLegacyV3MasterRoom(
    path: Path,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): LegacyV3MasterDatabase = buildDurableRoom(
    Room.databaseBuilder<LegacyV3MasterDatabase>(path.toString(), ::LegacyV3MasterDatabase_Impl)
        .addMigrations(LocalSecurityMigration1To2, LocalAccessIssuanceMigration1To3, LocalAccessIssuanceMigration2To3)
        .addCallback(ProtectedLocalSchemaCallback).addCallback(LocalAccessIssuanceSchemaCallback),
    driver,
)

/** Synthetic protected facts only; this does not issue or verify a production bearer. */
internal class LegacyV3MasterFixture(
    val path: Path = Files.createTempDirectory("ukkera-legacy-v3-test-").resolve("master.db"),
    driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    val original = LegacyV2MasterFixture(path)
    private val databaseDelegate = lazy { buildLegacyV3MasterRoom(path, driver) }
    val database: LegacyV3MasterDatabase get() = databaseDelegate.value
    val issuance = LocalAccessIssuance(
        TEST_SCOPE, TEST_ACTOR, original.account.state.sessions.last().sessionId, TEST_AUTHORITY.deviceId,
        original.account.state.sessions.last().generation, original.account.credentialRevision,
        original.admission.revision, testId(9860), MonotonicMoment(testId(9861), 200), 10_000,
        LocalAccessTokenHash.hashSecret(ByteArray(32) { (it + 9).toByte() }),
    )

    suspend fun seed() {
        check(!databaseDelegate.isInitialized() && !Files.exists(path)) { "Seed requires an unopened fresh fixture" }
        try { original.seed() } finally { original.close() }
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.SessionIssuance(TEST_SCOPE, issuance.sessionId), issuance.revision)
        val plaintext = ProtectedLocalSecurityCodec.encodeIssuance(issuance)
        val record = try {
            assertIs<LocalSealResult.Sealed>(original.protection.seal(original.keyReference, binding, plaintext)).record
        } finally { plaintext.fill(0) }
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                database.localAccessIssuance().insert(ProtectedLocalAccessIssuanceRow(
                    TEST_SCOPE.storageKey(), issuance.sessionId.value, 1, 1, "AES-256-GCM", 0,
                    original.keyReference.keyId.value, record.copyNonce(), record.copyCiphertextAndTag(),
                ))
            }
        }
    }

    fun close() { if (databaseDelegate.isInitialized()) database.close() }
}
