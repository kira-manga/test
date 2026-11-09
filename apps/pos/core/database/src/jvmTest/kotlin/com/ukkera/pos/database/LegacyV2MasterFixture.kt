package com.ukkera.pos.database

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertIs

internal const val LEGACY_V2_ROOM_IDENTITY = "c52a9fdfbd302c090a0d063c023432a7"

/** Frozen v2 DAO: never inherit a future table/query from the current security DAO. */
@Dao
internal interface LegacyV2ProtectedDao {
    @Insert suspend fun insertAccount(row: ProtectedLocalAccountRow)
    @Insert suspend fun insertPolicy(row: ProtectedEffectivePolicyRow)
    @Insert suspend fun insertAdmission(row: ProtectedDeviceAdmissionRow)
    @Insert suspend fun insertThrottle(row: ProtectedLoginThrottleRow)

    @Query("SELECT * FROM protected_local_account WHERE scope=:scope AND actorId=:actorId")
    suspend fun account(scope: String, actorId: String): ProtectedLocalAccountRow?

    @Query("SELECT * FROM protected_effective_policy WHERE scope=:scope AND actorId=:actorId")
    suspend fun policy(scope: String, actorId: String): ProtectedEffectivePolicyRow?

    @Query("SELECT * FROM protected_device_admission WHERE scope=:scope AND deviceId=:deviceId")
    suspend fun admission(scope: String, deviceId: String): ProtectedDeviceAdmissionRow?

    @Query("SELECT * FROM protected_login_throttle WHERE scope=:scope AND actorId=:actorId AND dimensionKey=:dimensionKey")
    suspend fun throttle(scope: String, actorId: String, dimensionKey: String): ProtectedLoginThrottleRow?
}

/**
 * Separately generated original eleven-entity v2, not the current master with its
 * version/identity changed. Keep the entity list and old-table DAO frozen at v2.
 */
@Database(
    entities = [BranchRow::class, OperationRow::class, AggregateRow::class, ClaimRow::class,
        JournalRow::class, AuditRow::class, OutboxRow::class, ProtectedLocalAccountRow::class,
        ProtectedEffectivePolicyRow::class, ProtectedDeviceAdmissionRow::class, ProtectedLoginThrottleRow::class],
    version = 2,
    exportSchema = false,
)
internal abstract class LegacyV2MasterDatabase : RoomDatabase() {
    abstract fun facts(): MasterDao
    abstract fun protectedV2(): LegacyV2ProtectedDao
}

internal fun buildLegacyV2MasterRoom(
    path: Path,
    driver: SQLiteDriver = BundledSQLiteDriver(),
): LegacyV2MasterDatabase = buildDurableRoom(
    Room.databaseBuilder<LegacyV2MasterDatabase>(path.toString(), ::LegacyV2MasterDatabase_Impl)
        .addMigrations(LocalSecurityMigration1To2).addCallback(ProtectedLocalSchemaCallback),
    driver,
)

/** Synthetic encrypted v2 facts only. Nothing here authenticates a production principal. */
internal class LegacyV2MasterFixture(
    val path: Path = Files.createTempDirectory("ukkera-legacy-v2-test-").resolve("master.db"),
    driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    private val databaseDelegate = lazy { buildLegacyV2MasterRoom(path, driver) }
    val database: LegacyV2MasterDatabase get() = databaseDelegate.value
    val keyReference = ProtectedStorageKeyReference(testId(9520))
    val protection = TestLocalStorageAead(keyReference)
    val account = ProtectedLocalAccount(
        LocalAccountSessionState(TEST_SCOPE, TEST_ACTOR, true, Revision(4), SessionGeneration(5), listOf(
            LocalSessionRecord(testId(9521), TEST_SCOPE, TEST_ACTOR, TEST_AUTHORITY.deviceId,
                SessionGeneration(4), SessionStatus.REVOKED, AuthenticationPurpose.INTERACTIVE),
            LocalSessionRecord(testId(9522), TEST_SCOPE, TEST_ACTOR, TEST_AUTHORITY.deviceId,
                SessionGeneration(5), SessionStatus.ACTIVE, AuthenticationPurpose.INTERACTIVE),
        )),
        Revision(2),
        PasswordHashRecord(CredentialBinding(TEST_SCOPE, TEST_ACTOR, Revision(2)), PasswordHashParameters(),
            ProtectedPepperReference(testId(9523)), ByteArray(16) { (it + 33).toByte() },
            ByteArray(32) { (it + 101).toByte() }),
    )
    val policy = ActivatedLocalPolicy(TEST_SCOPE, TEST_ACTOR, Revision(3), listOf(PermissionGrant(
        Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.PERFORM), TEST_SCOPE, GrantEffect.ALLOW,
    )), LocalSessionPolicy(), ThrottlePolicy())
    val admission = LocalDeviceAdmission(TEST_SCOPE, TEST_AUTHORITY.deviceId, Revision(6), true,
        "synthetic-v2-public-key", "synthetic-v2-fingerprint", setOf("test.local-storage"))
    val throttles: List<PersistentThrottleState> = listOf(ThrottleDimension.Account, ThrottleDimension.Device(TEST_AUTHORITY.deviceId))
        .mapIndexed { index, dimension -> PersistentThrottleState(ThrottleKey(TEST_SCOPE, TEST_ACTOR, dimension),
            Revision(index.toLong() + 1), index + 1, MonotonicMoment(testId(9524), 1_000), 0, null) }

    /**
     * Populate every original table: real generated v1 facts -> unchanged actual
     * 1-to-2 migration into the independently generated v2 target -> explicit v2
     * protected inserts. No current-master builder or manual metadata restamping.
     * An unseeded fixture can instead exercise genuine fresh-v2 creation.
     */
    suspend fun seed() {
        check(!databaseDelegate.isInitialized() && !Files.exists(path)) { "Seed requires an unopened fresh fixture" }
        val legacy = LegacyV1MasterFixture(path)
        try { legacy.seed() } finally { legacy.close() }
        val accountRecord = seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Account(TEST_SCOPE, TEST_ACTOR), account.state.revision),
            ProtectedLocalSecurityCodec.encodeAccount(account))
        val policyRecord = seal(ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(TEST_SCOPE, TEST_ACTOR), policy.revision),
            ProtectedLocalSecurityCodec.encodePolicy(policy))
        val admissionRecord = seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(TEST_SCOPE, TEST_AUTHORITY.deviceId), admission.revision),
            ProtectedLocalSecurityCodec.encodeAdmission(admission))
        val buckets = throttles.map { seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Throttle(it.key), it.revision),
            ProtectedLocalSecurityCodec.encodeThrottle(it)) }
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val dao = database.protectedV2()
                dao.insertAccount(ProtectedLocalAccountRow(TEST_SCOPE.storageKey(), TEST_ACTOR.value, 1, 1, "AES-256-GCM",
                    accountRecord.binding.revision.value, keyReference.keyId.value,
                    accountRecord.copyNonce(), accountRecord.copyCiphertextAndTag()))
                dao.insertPolicy(ProtectedEffectivePolicyRow(TEST_SCOPE.storageKey(), TEST_ACTOR.value, 1, 1, "AES-256-GCM",
                    policyRecord.binding.revision.value, keyReference.keyId.value,
                    policyRecord.copyNonce(), policyRecord.copyCiphertextAndTag()))
                dao.insertAdmission(ProtectedDeviceAdmissionRow(TEST_SCOPE.storageKey(), TEST_AUTHORITY.deviceId.value, 1, 1, "AES-256-GCM",
                    admissionRecord.binding.revision.value, keyReference.keyId.value,
                    admissionRecord.copyNonce(), admissionRecord.copyCiphertextAndTag()))
                for (record in buckets) {
                    val key = record.binding.key as ProtectedLocalRecordKey.Throttle
                    dao.insertThrottle(ProtectedLoginThrottleRow(TEST_SCOPE.storageKey(), TEST_ACTOR.value, key.dimensionKey,
                        1, 1, "AES-256-GCM", record.binding.revision.value, keyReference.keyId.value,
                        record.copyNonce(), record.copyCiphertextAndTag()))
                }
            }
        }
    }

    private suspend fun seal(binding: ProtectedLocalBinding, plaintext: ByteArray): SealedLocalRecord = try {
        assertIs<LocalSealResult.Sealed>(protection.seal(keyReference, binding, plaintext)).record
    } finally { plaintext.fill(0) }

    fun close() { if (databaseDelegate.isInitialized()) database.close() }
}

private val legacyV2TableOrder = linkedMapOf(
    "branch_authority" to "scope", "operation_result" to "scope,operationId",
    "aggregate_state" to "scope,kind,id", "unique_claim" to "scope,namespace,`key`",
    "journal_envelope" to "scope,epoch,sequence", "audit_event" to "id",
    "outbox_intent" to "scope,operationId,ordinal", "protected_local_account" to "scope,actorId",
    "protected_effective_policy" to "scope,actorId", "protected_device_admission" to "scope,deviceId",
    "protected_login_throttle" to "scope,actorId,dimensionKey",
)

/** Exact storage classes and cell bytes across all eleven OLD tables, not decoded equivalents. */
internal suspend fun legacyV2RowSnapshot(database: RoomDatabase): Map<String, List<List<String>>> =
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            buildMap {
                for ((table, order) in legacyV2TableOrder) {
                    val columns = usePrepared("PRAGMA table_info(`$table`)") { statement ->
                        buildList { while (statement.step()) add(statement.getText(1)) }
                    }
                    check(columns.isNotEmpty())
                    val projection = columns.joinToString(",") { "typeof(`$it`) || ':' || hex(CAST(`$it` AS BLOB))" }
                    put(table, usePrepared("SELECT $projection FROM `$table` ORDER BY $order") { statement ->
                        buildList { while (statement.step()) add(columns.indices.map(statement::getText)) }
                    })
                }
            }
        }
    }

internal fun rawLegacyV2RowSnapshot(path: Path): Map<String, List<List<String>>> =
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("PRAGMA query_only=ON").use { it.step() }
        connection.prepare("BEGIN").use { it.step() }
        try {
            buildMap {
                for ((table, order) in legacyV2TableOrder) {
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
        } finally { connection.prepare("ROLLBACK").use { it.step() } }
    }

/** Exclude the new table by original table identity, not a version-following callback list. */
internal suspend fun legacyV2GuardSnapshot(database: RoomDatabase): List<List<String>> = database.useWriterConnection { connection ->
    val tables = legacyV2TableOrder.keys.joinToString(",") { "'$it'" }
    connection.usePrepared("SELECT name,tbl_name,sql FROM sqlite_master WHERE type='trigger' AND tbl_name IN ($tables) ORDER BY name") {
        buildList { while (it.step()) add((0..2).map(it::getText)) }
    }
}

/** Refusal/rollback observer: metadata includes exact identity cells and every main-schema object. */
internal fun rawMasterMigrationMetadata(path: Path): List<List<String>> =
    BundledSQLiteDriver().open(path.toString()).use { connection ->
        connection.prepare("PRAGMA query_only=ON").use { it.step() }
        connection.prepare("BEGIN").use { it.step() }
        try { migrationMetadata(connection) }
        finally { connection.prepare("ROLLBACK").use { it.step() } }
    }

private fun migrationMetadata(connection: SQLiteConnection): List<List<String>> = buildList {
    connection.prepare("PRAGMA user_version").use { check(it.step()); add(listOf(it.getLong(0).toString())) }
    connection.prepare("SELECT type,name,tbl_name,COALESCE(sql,'') FROM sqlite_master ORDER BY type,name").use {
        while (it.step()) add((0..3).map(it::getText))
    }
    val hasIdentity = connection.prepare("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='room_master_table'").use {
        check(it.step()); it.getLong(0) == 1L
    }
    if (hasIdentity) connection.prepare("SELECT typeof(id)||':'||hex(CAST(id AS BLOB)),typeof(identity_hash)||':'||hex(CAST(identity_hash AS BLOB)) FROM room_master_table ORDER BY id").use {
        while (it.step()) add(listOf(it.getText(0), it.getText(1)))
    }
}
