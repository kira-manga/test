package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json

/**
 * TEST ONLY. Explicit JCA key, accepting principal factory, clock/context and writer runtime.
 * These fixtures exercise the protected current-state reader, NOT bearer verification, login,
 * entropy, OS key custody, physical monotonic continuity, restart authority or POS acceptance.
 */
internal class LocalAuthorizationStoreFixture(
    path: Path = Files.createTempDirectory("ukkera-current-local-auth-test-").resolve("master.db"),
    driver: SQLiteDriver = BundledSQLiteDriver(),
) {
    val security = LocalSecurityStoreFixture(path, driver)
    val path: Path get() = security.path
    val database get() = security.database
    val protection get() = security.protection
    val protectedStore get() = security.store
    val environment = TestLocalAuthorizationEnvironment(security.bootId)
    val source = RoomCurrentLocalAuthorizationSource(protectedStore, environment)
    val handler = CounterHandler()
    var wallTime = TEST_TIME
    var wallClock: MasterClock = MasterClock { wallTime }
    var writerVerification = RuntimeVerification.VERIFIED
    var absenceVerification = RuntimeVerification.VERIFIED
    var beforeWriterVerification: (suspend () -> Unit)? = null
    var beforeAbsenceVerification: (suspend () -> Unit)? = null
    val runtime = object : MasterRuntimeVerifier {
        override suspend fun writer(observation: BranchObservation): RuntimeVerification {
            beforeWriterVerification?.invoke()
            return writerVerification
        }
        override suspend fun absentOperation(observation: BranchObservation, identity: OperationIdentity): RuntimeVerification {
            beforeAbsenceVerification?.invoke()
            return absenceVerification
        }
    }

    fun kernel(
        fault: StoreFaultProbe = NoStoreFault,
        authorization: TransactionAuthorizationSource = source,
        commandHandler: MasterCommandHandler = handler,
    ): AtomicMasterStore = AtomicMasterStore(database, TEST_SCOPE, TEST_AUTHORITY,
        listOf(commandHandler), authorization, runtime, MasterClock { wallClock.now() }, fault)

    fun issuance(
        actorId: DomainId = TEST_ACTOR,
        sessionId: DomainId = testId(9110),
        scope: BranchScope = TEST_SCOPE,
        deviceId: DomainId = TEST_AUTHORITY.deviceId,
        generation: Long = 5,
        credentialRevision: Long = 2,
        admissionRevision: Long = 6,
        issuerContextId: DomainId = testId(9500),
        bootId: DomainId = security.bootId,
        issuedAt: Long = 1000,
        duration: Long = 1000,
    ): LocalAccessIssuance = LocalAccessIssuance(scope, actorId, sessionId, deviceId,
        SessionGeneration(generation), Revision(credentialRevision), Revision(admissionRevision),
        issuerContextId, MonotonicMoment(bootId, issuedAt), duration,
        LocalAccessTokenHash.hashSecret(ByteArray(32) { (it + 83).toByte() }))

    fun policy(
        revision: Long = 3,
        actor: DomainId = TEST_ACTOR,
        grants: List<PermissionGrant> = listOf(allowLocal(SecurityAction.INVENTORY_ADJUST)),
    ): ActivatedLocalPolicy = ActivatedLocalPolicy(TEST_SCOPE, actor, Revision(revision), grants,
        LocalSessionPolicy(), ThrottlePolicy())

    suspend fun seed(
        account: ProtectedLocalAccount? = security.defaultAccount(),
        policy: ActivatedLocalPolicy? = policy(),
        admission: LocalDeviceAdmission? = security.defaultAdmission(),
        issuance: LocalAccessIssuance? = issuance(),
    ) {
        security.seed(account, policy, admission)
        if (issuance != null) protectedStore.transaction { it.insertIssuance(issuance) }
    }

    /** Adds another test actor/session without re-provisioning the branch or device admission. */
    suspend fun seedOtherActor(actor: DomainId, session: DomainId, grants: List<PermissionGrant>, duration: Long = 1000): LocalEmployeePrincipal {
        val issue = issuance(actorId = actor, sessionId = session, duration = duration)
        val account = ProtectedLocalAccount(
            LocalAccountSessionState(TEST_SCOPE, actor, true, Revision(4), SessionGeneration(5),
                listOf(LocalSessionRecord(session, TEST_SCOPE, actor, TEST_AUTHORITY.deviceId,
                    SessionGeneration(5), SessionStatus.ACTIVE, AuthenticationPurpose.INTERACTIVE))),
            Revision(2), PasswordHashRecord(CredentialBinding(TEST_SCOPE, actor, Revision(2)),
                PasswordHashParameters(), ProtectedPepperReference(testId(9111)),
                ByteArray(16) { (it + 33).toByte() }, ByteArray(32) { (it + 101).toByte() }),
        )
        val actorPolicy = policy(actor = actor, grants = grants)
        val accountRecord = security.seal(ProtectedLocalBinding(ProtectedLocalRecordKey.Account(TEST_SCOPE, actor), Revision(4)),
            ProtectedLocalSecurityCodec.encodeAccount(account))
        val policyRecord = security.seal(ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(TEST_SCOPE, actor), Revision(3)),
            ProtectedLocalSecurityCodec.encodePolicy(actorPolicy))
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                database.localSecurity().insertAccount(ProtectedLocalAccountRow(TEST_SCOPE.storageKey(), actor.value,
                    1, 1, "AES-256-GCM", 4, security.keyReference.keyId.value,
                    accountRecord.copyNonce(), accountRecord.copyCiphertextAndTag()))
                database.localSecurity().insertPolicy(ProtectedEffectivePolicyRow(TEST_SCOPE.storageKey(), actor.value,
                    1, 1, "AES-256-GCM", 3, security.keyReference.keyId.value,
                    policyRecord.copyNonce(), policyRecord.copyCiphertextAndTag()))
            }
        }
        protectedStore.transaction { it.insertIssuance(issue) }
        return localAuthorizationPrincipal(actorId = actor, sessionId = session)
    }

    fun request(number: Int = 9600, input: CounterInput = CounterInput(id = testId(9601), claim = "protected-auth-stock")): MutationRequest =
        MutationRequest(OperationId(testId(number).value), handler.key, StoredBytes(Json.encodeToString(input).encodeToByteArray()))

    fun identity(request: MutationRequest, actor: DomainId = TEST_ACTOR): OperationIdentity =
        OperationIdentity(TEST_SCOPE.branchId, request.operationId, actor, 1,
            semanticDigest(TEST_SCOPE, actor, request.command, handler.normalize(request.payload)).value)

    fun issuanceKey(session: DomainId = testId(9110)): ProtectedLocalRecordKey.SessionIssuance =
        ProtectedLocalRecordKey.SessionIssuance(TEST_SCOPE, session)

    suspend fun snapshot(): Map<String, List<List<String>>> = localAuthorizationRows(database)
    fun close() = security.close()
}

/** Mutable and scripted deliberately unqualified test environment; millisecond values are not policy defaults. */
internal class TestLocalAuthorizationEnvironment(bootId: DomainId) : RoomLocalAuthorizationEnvironment {
    var contextId: DomainId = testId(9500)
    var bootId: DomainId = bootId
    var elapsed: Long = 1500
    var available: Boolean = true
    var calls: Int = 0
    var onObserve: ((Int) -> Unit)? = null
    override fun observe(): RoomLocalAuthorizationObservation? {
        calls++
        onObserve?.invoke(calls)
        return if (available) RoomLocalAuthorizationObservation(contextId, MonotonicMoment(bootId, elapsed)) else null
    }
}

internal fun allowLocal(action: SecurityAction, effect: GrantEffect = GrantEffect.ALLOW): PermissionGrant =
    PermissionGrant(Permission(action, PermissionVerb.PERFORM), TEST_SCOPE, effect)

/** Already-trusted identity input only; intentionally not a password/token or transport adapter. */
internal suspend fun localAuthorizationPrincipal(
    actorId: DomainId = TEST_ACTOR,
    scope: BranchScope = TEST_SCOPE,
    sessionId: DomainId = testId(9110),
    deviceId: DomainId = TEST_AUTHORITY.deviceId,
    generation: Long = 5,
    admissionRevision: Long = 6,
    purpose: AuthenticationPurpose = AuthenticationPurpose.INTERACTIVE,
): LocalEmployeePrincipal = assertNotNull(TrustedPrincipalFactory(object : PrincipalVerifier by DenyAllPrincipalVerifier {
    override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity =
        VerifiedLocalIdentity(actorId, scope, deviceId, sessionId, SessionGeneration(generation), Revision(admissionRevision), purpose)
}).authenticateLocal(LocalGatewayCredentials(testId(9599), OpaqueCredential(ByteArray(32) { 29 }))))

/** All seven financial tables and all five protected tables, as exact typed-cell bytes in ONE transaction. */
internal suspend fun localAuthorizationRows(database: MasterRoomDatabase): Map<String, List<List<String>>> =
    database.useWriterConnection { connection ->
        requireDurableWriter(connection)
        connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val orders = linkedMapOf(
                "branch_authority" to "scope", "operation_result" to "scope,operationId",
                "aggregate_state" to "scope,kind,id", "unique_claim" to "scope,namespace,`key`",
                "journal_envelope" to "scope,epoch,sequence", "audit_event" to "id",
                "outbox_intent" to "scope,operationId,ordinal", "protected_local_account" to "scope,actorId",
                "protected_effective_policy" to "scope,actorId", "protected_device_admission" to "scope,deviceId",
                "protected_login_throttle" to "scope,actorId,dimensionKey", "protected_local_access_issuance" to "scope,sessionId",
            )
            buildMap {
                for ((table, order) in orders) {
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

internal fun assertLocalAuthDenied(reply: MutationReply) {
    assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(reply).reason)
}

internal fun assertLocalAuthDenied(reply: LookupReply) {
    assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<LookupReply.Deferred>(reply).reason)
}
