package com.ukkera.pos.database

import androidx.room3.ConstructedBy
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

// Internal rows/DAOs are never returned by the application gateway. In particular,
// neither the UI nor a generic RPC route may invoke a Room write directly.
@Entity(tableName = "branch_authority")
internal data class BranchRow(
    @PrimaryKey val scope: String,
    val assignmentId: String,
    val deviceId: String,
    val epoch: Long,
    val revision: Long,
    val sequence: Long,
    val journalHash: String,
    val writable: Boolean,
    val recoveryJson: String,
)

@Entity(
    tableName = "operation_result",
    primaryKeys = ["scope", "operationId"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])],
    indices = [Index(value = ["scope", "epoch", "sequence"], unique = true)],
)
internal data class OperationRow(
    val scope: String,
    val operationId: String,
    val actorId: String,
    val command: String,
    val commandVersion: Int,
    val digest: String,
    val outcome: String,
    val result: ByteArray,
    val resultHash: String,
    val sequence: Long,
    val epoch: Long,
    val committedAt: String,
    val authenticationId: String,
    val policyRevision: Long,
)

@Entity(
    tableName = "aggregate_state",
    primaryKeys = ["scope", "kind", "id"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])],
)
internal data class AggregateRow(
    val scope: String,
    val kind: String,
    val id: String,
    val revision: Long,
    val schema: String,
    val payload: ByteArray,
    val payloadHash: String,
)

/** Actual unique claims, e.g. active drawer custody; a preflight is not the constraint. */
@Entity(
    tableName = "unique_claim",
    primaryKeys = ["scope", "namespace", "key"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])],
)
internal data class ClaimRow(val scope: String, val namespace: String, val key: String, val ownerId: String)

@Entity(
    tableName = "journal_envelope",
    primaryKeys = ["scope", "epoch", "sequence"],
    foreignKeys = [ForeignKey(
        entity = OperationRow::class,
        parentColumns = ["scope", "operationId"], childColumns = ["scope", "operationId"],
    )],
    indices = [Index(value = ["scope", "operationId"], unique = true)],
)
internal data class JournalRow(
    val scope: String,
    val sequence: Long,
    val operationId: String,
    val assignmentId: String,
    val epoch: Long,
    val previousHash: String,
    val hash: String,
    val envelope: ByteArray,
)

@Entity(
    tableName = "audit_event",
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])],
    indices = [Index(value = ["scope", "operationId"])],
)
internal data class AuditRow(
    @PrimaryKey val id: String,
    val scope: String,
    val operationId: String,
    val kind: String,
    val actorId: String,
    val originalActorId: String,
    val policyRevision: Long,
    val recordedAt: String,
    val digest: String,
)

@Entity(
    tableName = "outbox_intent",
    primaryKeys = ["scope", "operationId", "ordinal"],
    foreignKeys = [ForeignKey(
        entity = OperationRow::class,
        parentColumns = ["scope", "operationId"], childColumns = ["scope", "operationId"],
    )],
    indices = [Index(value = ["scope", "operationId"])],
)
internal data class OutboxRow(
    val scope: String,
    val operationId: String,
    val ordinal: Int,
    val kind: String,
    val body: ByteArray,
    val bodyHash: String,
)

/** Scalar-only preflight; no unbounded TEXT or BLOB is returned before validation. */
internal data class RetainedBlobMetadata(val byteCount: Long, val storageValid: Boolean)

/** Current point-read preflight. Invalid storage is distinct from a missing key. */
internal data class CurrentAggregateMetadata(val byteCount: Long, val storageValid: Boolean)

/** The SQL query never hydrates an unbounded malformed identifier. */
internal data class CurrentAggregateKey(val boundedId: String?)

@Dao
internal interface MasterDao {
    @Query("SELECT * FROM branch_authority WHERE scope=:scope")
    suspend fun branch(scope: String): BranchRow?

    @Insert suspend fun insertBranch(row: BranchRow)

    @Query("UPDATE branch_authority SET revision=:nextRevision, sequence=:sequence, journalHash=:journalHash WHERE scope=:scope AND revision=:expectedRevision")
    suspend fun advance(scope: String, expectedRevision: Long, nextRevision: Long, sequence: Long, journalHash: String): Int

    @Query("SELECT * FROM operation_result WHERE scope=:scope AND operationId=:operationId")
    suspend fun operation(scope: String, operationId: String): OperationRow?

    // Separate point lookups intentionally expose orphans. No join or length
    // predicate may turn a malformed/oversized pair into an apparent absence.
    // CAST(... AS BLOB) measures text bytes including content after embedded NUL.
    @Query("""
        SELECT COALESCE(length(result), -1) AS byteCount,
        CASE WHEN typeof(result)='blob'
          AND typeof(scope)='text' AND length(CAST(scope AS BLOB))=110
          AND typeof(operationId)='text' AND length(CAST(operationId AS BLOB))=36
          AND typeof(actorId)='text' AND length(CAST(actorId AS BLOB))=36
          AND typeof(command)='text' AND length(CAST(command AS BLOB)) BETWEEN 1 AND 96
          AND typeof(commandVersion)='integer' AND commandVersion BETWEEN 1 AND 1000
          AND typeof(digest)='text' AND length(CAST(digest AS BLOB))=64
          AND typeof(outcome)='text' AND length(CAST(outcome AS BLOB)) BETWEEN 1 AND 96
          AND typeof(resultHash)='text' AND length(CAST(resultHash AS BLOB))=64
          AND typeof(sequence)='integer' AND sequence>=1
          AND typeof(epoch)='integer' AND epoch>=0
          AND typeof(committedAt)='text' AND length(CAST(committedAt AS BLOB)) BETWEEN 20 AND 30
          AND typeof(authenticationId)='text' AND length(CAST(authenticationId AS BLOB))=36
          AND typeof(policyRevision)='integer' AND policyRevision>=0
        THEN 1 ELSE 0 END AS storageValid
        FROM operation_result WHERE scope=:scope AND operationId=:operationId
    """)
    suspend fun retainedOperationMetadata(scope: String, operationId: String): RetainedBlobMetadata?

    @Query("""
        SELECT COALESCE(length(envelope), -1) AS byteCount,
        CASE WHEN typeof(envelope)='blob'
          AND typeof(scope)='text' AND length(CAST(scope AS BLOB))=110
          AND typeof(operationId)='text' AND length(CAST(operationId AS BLOB))=36
          AND typeof(assignmentId)='text' AND length(CAST(assignmentId AS BLOB))=36
          AND typeof(sequence)='integer' AND sequence>=1
          AND typeof(epoch)='integer' AND epoch>=0
          AND typeof(previousHash)='text' AND length(CAST(previousHash AS BLOB))=64
          AND typeof(hash)='text' AND length(CAST(hash AS BLOB))=64
        THEN 1 ELSE 0 END AS storageValid
        FROM journal_envelope WHERE scope=:scope AND operationId=:operationId
    """)
    suspend fun retainedJournalMetadata(scope: String, operationId: String): RetainedBlobMetadata?

    @Query("SELECT * FROM journal_envelope WHERE scope=:scope AND operationId=:operationId")
    suspend fun retainedJournal(scope: String, operationId: String): JournalRow?

    @Insert suspend fun insertOperation(row: OperationRow)

    @Query("SELECT * FROM aggregate_state WHERE scope=:scope AND kind=:kind AND id=:id")
    suspend fun aggregate(scope: String, kind: String, id: String): AggregateRow?

    // Do not put validity predicates in WHERE: a corrupt existing row must not
    // appear absent. Bound SQLite storage before Room can coerce/hydrate it.
    @Query("""
        SELECT COALESCE(length(payload), -1) AS byteCount,
        CASE WHEN typeof(payload)='blob'
          AND typeof(scope)='text' AND length(CAST(scope AS BLOB))=110
          AND typeof(kind)='text' AND length(CAST(kind AS BLOB)) BETWEEN 1 AND 96
          AND typeof(id)='text' AND length(CAST(id AS BLOB))=36
          AND typeof(revision)='integer' AND revision>=1
          AND typeof(schema)='text' AND length(CAST(schema AS BLOB)) BETWEEN 1 AND 96
          AND instr(CAST(schema AS BLOB), x'00')=0
          AND typeof(payloadHash)='text' AND length(CAST(payloadHash AS BLOB))=64
          AND instr(CAST(payloadHash AS BLOB), x'00')=0
        THEN 1 ELSE 0 END AS storageValid
        FROM aggregate_state WHERE scope=:scope AND kind=:kind AND id=:id
    """)
    suspend fun currentAggregateMetadata(scope: String, kind: String, id: String): CurrentAggregateMetadata?

    @Query("""
        SELECT CASE WHEN typeof(id)='text' AND length(CAST(id AS BLOB))=36
          THEN id ELSE NULL END AS boundedId
        FROM aggregate_state
        WHERE scope=:scope AND kind=:kind AND (:after IS NULL OR id>:after)
        ORDER BY id ASC LIMIT :limit
    """)
    suspend fun aggregateKeys(scope: String, kind: String, after: String?, limit: Int): List<CurrentAggregateKey>

    // Existing PK(scope,namespace,key) supplies the ordered TEXT prefix index. SELECT validates
    // every selected entry, including lookahead; it is NOT a census of non-TEXT selector aliases
    // excluded by SQL comparison. Activation depends on D2/F05 typed physical/all-writer closure.
    @Query("""
        SELECT CASE WHEN typeof(scope)='text' AND length(CAST(scope AS BLOB))=110
          AND typeof(namespace)='text' AND length(CAST(namespace AS BLOB)) BETWEEN 1 AND 96
          AND typeof(`key`)='text' AND length(CAST(`key` AS BLOB)) BETWEEN 1 AND 256
          AND instr(CAST(`key` AS BLOB), x'00')=0
          THEN `key` ELSE NULL END AS boundedKey,
        CASE WHEN typeof(ownerId)='text' AND length(CAST(ownerId AS BLOB))=36
          AND instr(CAST(ownerId AS BLOB), x'00')=0
          THEN ownerId ELSE NULL END AS boundedOwnerId
        FROM unique_claim
        WHERE scope=:scope AND namespace=:namespace AND `key`>=:prefix AND `key`<:upper
          AND (:after IS NULL OR `key`>:after)
        ORDER BY `key` ASC LIMIT :limit
    """)
    suspend fun claimKeys(scope: String, namespace: String, prefix: String, upper: String,
        after: String?, limit: Int): List<CurrentClaimKey>

    @Insert suspend fun insertAggregate(row: AggregateRow)

    @Query("UPDATE aggregate_state SET revision=:nextRevision, schema=:schema, payload=:payload, payloadHash=:hash WHERE scope=:scope AND kind=:kind AND id=:id AND revision=:expectedRevision")
    suspend fun replaceAggregate(scope: String, kind: String, id: String, expectedRevision: Long, nextRevision: Long, schema: String, payload: ByteArray, hash: String): Int

    @Query("SELECT * FROM unique_claim WHERE scope=:scope AND namespace=:namespace AND `key`=:key")
    suspend fun claim(scope: String, namespace: String, key: String): ClaimRow?

    @Query("""
        SELECT CASE WHEN typeof(scope)='text' AND length(CAST(scope AS BLOB))=110
          AND typeof(namespace)='text' AND length(CAST(namespace AS BLOB)) BETWEEN 1 AND 96
          AND typeof(`key`)='text' AND length(CAST(`key` AS BLOB)) BETWEEN 1 AND 256
          AND typeof(ownerId)='text' AND length(CAST(ownerId AS BLOB))=36
          AND instr(CAST(ownerId AS BLOB), x'00')=0
        THEN 1 ELSE 0 END
        FROM unique_claim WHERE scope=:scope AND namespace=:namespace AND `key`=:key
    """)
    suspend fun currentClaimStorageValid(scope: String, namespace: String, key: String): Boolean?

    @Insert suspend fun insertClaim(row: ClaimRow)

    @Query("DELETE FROM unique_claim WHERE scope=:scope AND namespace=:namespace AND `key`=:key AND ownerId=:ownerId")
    suspend fun releaseClaim(scope: String, namespace: String, key: String, ownerId: String): Int

    @Insert suspend fun insertJournal(row: JournalRow)
    @Insert suspend fun insertAudit(row: AuditRow)
    @Insert suspend fun insertOutbox(rows: List<OutboxRow>)

    // Point selector only: malformed retained storage must not disappear behind a kind,
    // hash or length WHERE predicate. No delivery status mutates the append-only intent.
    @Query("""
        SELECT COALESCE(length(body), -1) AS byteCount,
        CASE WHEN typeof(body)='blob'
          AND typeof(scope)='text' AND length(CAST(scope AS BLOB))=110
          AND typeof(operationId)='text' AND length(CAST(operationId AS BLOB))=36
          AND typeof(ordinal)='integer' AND ordinal BETWEEN 1 AND 32
          AND typeof(kind)='text' AND length(CAST(kind AS BLOB)) BETWEEN 1 AND 96
          AND typeof(bodyHash)='text' AND length(CAST(bodyHash AS BLOB))=64
        THEN 1 ELSE 0 END AS storageValid
        FROM outbox_intent WHERE scope=:scope AND operationId=:operationId AND ordinal=:ordinal
    """)
    suspend fun localDisplayIntentMetadata(scope: String, operationId: String, ordinal: Int): RetainedBlobMetadata?

    @Query("SELECT * FROM outbox_intent WHERE scope=:scope AND operationId=:operationId AND ordinal=:ordinal")
    suspend fun outboxIntent(scope: String, operationId: String, ordinal: Int): OutboxRow?
}

@Database(
    entities = [BranchRow::class, OperationRow::class, AggregateRow::class, ClaimRow::class, JournalRow::class, AuditRow::class, OutboxRow::class,
        ProtectedLocalAccountRow::class, ProtectedEffectivePolicyRow::class, ProtectedDeviceAdmissionRow::class, ProtectedLoginThrottleRow::class,
        ProtectedLocalAccessIssuanceRow::class, ProtectedLocalAuthConfigurationRow::class,
        ProtectedLocalRefreshIssuanceRow::class, ProtectedLocalAuthEventRow::class, ProtectedLocalSessionArchiveRow::class,
        ProtectedLocalActionApprovalRow::class,
        StockPositionIndexRowV2::class, StockAllocationIndexRowV2::class,
        StockReservationIndexRowV2::class, StockValueIndexRowV2::class, JournalFragmentRowV2::class,
        RuntimeHistoryHeadRowV2::class, RuntimeHistoryEventRowV2::class, RuntimeHistoryRowVersionRowV2::class,
        RuntimeHistoryChunkRowV2::class, RuntimeHistoryRowTipRowV2::class, RuntimeHistoryWriteFrameRowV2::class,
        RuntimeHistoryCaptureRowV2::class],
    version = 7,
    exportSchema = true,
)
@ConstructedBy(MasterRoomConstructor::class)
internal abstract class MasterRoomDatabase : RoomDatabase() {
    internal var runtimeHistoryOwner: RoomRuntimeHistoryOwner? = null
        set(value) {
            // The genuine native producer retains its exact close before binding this
            // owner. This database lifetime can never clear/replace it to regain raw close.
            check(value != null && field == null && value.database === this) {
                "Original Room ownership cannot be cleared or replaced"
            }
            field = value
        }
    abstract fun facts(): MasterDao
    abstract fun localSecurity(): LocalSecurityDao
    abstract fun localAccessIssuance(): LocalAccessIssuanceDao
    abstract fun localAuthentication(): LocalAuthenticationDao
    abstract fun localActionApprovals(): LocalActionApprovalDao
}

// KSP supplies platform actuals; no reflective or handwritten schema implementation.
@Suppress("KotlinNoActualForExpect")
internal expect object MasterRoomConstructor : RoomDatabaseConstructor<MasterRoomDatabase> {
    override fun initialize(): MasterRoomDatabase
}
