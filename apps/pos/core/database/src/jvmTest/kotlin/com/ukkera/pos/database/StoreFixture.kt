package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.recovery.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// All identities and accepting trust adapters in this file are SYNTHETIC TEST-ONLY.
// They qualify SQLite command atomicity, not a real employee/key/appointment ceremony.
internal fun testId(value: Int): DomainId = DomainId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")
internal val TEST_SCOPE = BranchScope(testId(1), testId(2), testId(3))
internal val TEST_AUTHORITY = AuthorityIdentity(testId(4), testId(5), Revision(1))
internal val TEST_ACTOR = testId(6)
internal val TEST_TIME = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
internal val AUTH_KEY = AggregateKey("qualification.auth", TEST_ACTOR)

@Serializable
internal data class CounterInput(
    val id: DomainId = testId(10),
    val expectedRevision: Revision? = null,
    @Serializable(with = MinorUnitsSerializer::class) val delta: Long = 1,
    val claim: String? = null,
    val reject: Boolean = false,
    val deadline: UtcInstant? = null,
)

internal class CounterHandler : MasterCommandHandler {
    override val key = CommandKey("qualification.counter", 1)
    override val writableKinds = setOf("qualification.counter")
    override val claimNamespaces = setOf("qualification.custody")
    var extraJobBytes: ByteArray? = null
    var retainedView: CommandReadView? = null
    var prepareCount = 0
    override fun normalize(input: StoredBytes): StoredBytes {
        val text = input.copyBytes().decodeToString(throwOnInvalidSequence = true)
        val value = Json.decodeFromString<CounterInput>(text)
        require(value.claim == null || value.claim.length in 1..100)
        return StoredBytes(Json.encodeToString(value).encodeToByteArray())
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        retainedView = view
        prepareCount++
        val input = Json.decodeFromString<CounterInput>(normalized.copyBytes().decodeToString())
        val document = AggregateKey("qualification.counter", input.id)
        val before = view.aggregate(document)?.payload?.copyBytes()?.decodeToString()?.toLong() ?: 0
        val after = Math.addExact(before, input.delta)
        return object : PreparedMutation() {
            override val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), view.scope,
                ActionBinding(input.id, input.expectedRevision ?: Revision(0), context.semanticDigest, context.operationId, "qualification.counter"))
            override val affectedResources = setOf(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, input.id))
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome =
                if (input.reject || after < 0) ProposedOutcome.Reject("stock.insufficient", StoredBytes("stock.insufficient".encodeToByteArray()))
                else ProposedOutcome.Apply(StoredBytes(after.toString().encodeToByteArray()),
                    listOf(AggregateChange(document, input.expectedRevision, "counter/v1", StoredBytes(after.toString().encodeToByteArray()))),
                    input.claim?.let { listOf(ClaimChange.Acquire(ClaimKey("qualification.custody", it), input.id)) }.orEmpty(),
                    listOf(JobIntent("qualification.receipt", StoredBytes(extraJobBytes ?: after.toString().encodeToByteArray()))), input.deadline)
        }
    }
}

internal class StoreFixture(
    val path: Path = Files.createTempDirectory("ukkera-room-test-").resolve("master.db"),
    driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    val database = buildControlledMasterRoomFixture(Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize), driver)
    val handler = CounterHandler()
    var clockValue = TEST_TIME
    var writerVerification = RuntimeVerification.VERIFIED
    var absenceVerification = RuntimeVerification.VERIFIED
    val runtime = object : MasterRuntimeVerifier {
        override suspend fun writer(observation: BranchObservation) = writerVerification
        override suspend fun absentOperation(observation: BranchObservation, identity: OperationIdentity) = absenceVerification
    }
    val auth = TransactionAuthorizationSource { principal, view ->
        val state = view.aggregate(AUTH_KEY)?.payload?.copyBytes()?.decodeToString()
        if (principal.kind != PrincipalKind.LOCAL_EMPLOYEE || state == null) null else AuthorizationSnapshot(
            principal.subjectId, principal.kind, principal.scope, principal.authenticationId, principal.generation, principal.deviceId,
            principal.admissionRevision, Revision(1), state != "disabled", state != "revoked", true, AuthenticationPurpose.INTERACTIVE,
            if (state == "no-grants") emptyList() else listOf(PermissionGrant(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), TEST_SCOPE, GrantEffect.ALLOW),
                PermissionGrant(Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM), TEST_SCOPE, GrantEffect.ALLOW)),
        )
    }
    fun store(fault: StoreFaultProbe = NoStoreFault, authority: AuthorityIdentity = TEST_AUTHORITY, runtime: MasterRuntimeVerifier = this.runtime) =
        AtomicMasterStore(database, TEST_SCOPE, authority, listOf(handler), auth, runtime, MasterClock { clockValue }, fault)

    suspend fun seed() {
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                database.facts().insertBranch(BranchRow(TEST_SCOPE.storageKey(), TEST_AUTHORITY.assignmentId.value, TEST_AUTHORITY.deviceId.value, 1, 0, 0, "0".repeat(64), true,
                    Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(TEST_SCOPE.branchId))))
                database.facts().insertAggregate(AggregateRow(TEST_SCOPE.storageKey(), AUTH_KEY.kind, AUTH_KEY.id.value, 1, "auth/v1", "enabled".encodeToByteArray(), sha256("enabled".encodeToByteArray()).value))
            }
        }
    }

    suspend fun setAuth(value: String) = database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val row = requireNotNull(database.facts().aggregate(TEST_SCOPE.storageKey(), AUTH_KEY.kind, AUTH_KEY.id.value))
            check(database.facts().replaceAggregate(row.scope, row.kind, row.id, row.revision, row.revision + 1, row.schema,
                value.encodeToByteArray(), sha256(value.encodeToByteArray()).value) == 1)
        }
    }

    suspend fun restoreHold() = database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        val state = RecoveryAdmissionMachine().restore(TEST_SCOPE.branchId, testId(91),
            RecoveryCheckpoint(testId(92), JournalPosition(TEST_SCOPE.branchId, TEST_AUTHORITY, JournalSequence(0)), "1".repeat(64)), emptyList())
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            usePrepared("UPDATE branch_authority SET recoveryJson=? WHERE scope=?") {
                it.bindText(1, Json.encodeToString(state)); it.bindText(2, TEST_SCOPE.storageKey()); it.step()
            }
        }
    }

    suspend fun count(table: String): Long {
        require(table in setOf("operation_result", "journal_envelope", "outbox_intent", "audit_event", "unique_claim"))
        return database.useWriterConnection { connection -> connection.usePrepared("SELECT COUNT(*) FROM $table") { check(it.step()); it.getLong(0) } }
    }
    fun request(number: Int = 20, input: CounterInput = CounterInput()) = MutationRequest(OperationId(testId(number).value), handler.key,
        StoredBytes(Json.encodeToString(input).encodeToByteArray()))
    fun identity(request: MutationRequest, actor: DomainId = TEST_ACTOR) = OperationIdentity(TEST_SCOPE.branchId, request.operationId, actor, 1,
        semanticDigest(TEST_SCOPE, actor, request.command, handler.normalize(request.payload)).value)
    fun close() = database.close()
}

internal suspend fun testPrincipal(actor: DomainId = TEST_ACTOR, scope: BranchScope = TEST_SCOPE): LocalEmployeePrincipal =
    requireNotNull(TrustedPrincipalFactory(object : PrincipalVerifier {
        override suspend fun verifyLocal(credentials: LocalGatewayCredentials) = VerifiedLocalIdentity(actor, scope, TEST_AUTHORITY.deviceId, testId(7), SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
        override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
        override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
        override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
    }).authenticateLocal(LocalGatewayCredentials(testId(8), OpaqueCredential(ByteArray(32) { 7 }))))
