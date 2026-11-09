package com.ukkera.pos.database.independent.androidlifecycleresumev1

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.database.BranchRow
import com.ukkera.pos.database.MasterRoomConstructor
import com.ukkera.pos.database.MasterRoomDatabase
import com.ukkera.pos.database.ProtectedDeviceAdmissionRow
import com.ukkera.pos.database.ProtectedEffectivePolicyRow
import com.ukkera.pos.database.ProtectedLocalAccountRow
import com.ukkera.pos.database.ProtectedLocalSecurityCodec
import com.ukkera.pos.database.RoomCurrentLocalAuthorizationSource
import com.ukkera.pos.database.RoomLocalAccessPeerBinding
import com.ukkera.pos.database.RoomLocalAccessPeerSource
import com.ukkera.pos.database.RoomLocalMasterLifecycleService
import com.ukkera.pos.database.RoomLocalSecurityStore
import com.ukkera.pos.database.RuntimeWritePurposeV2
import com.ukkera.pos.database.TEST_ACTOR
import com.ukkera.pos.database.TEST_AUTHORITY
import com.ukkera.pos.database.TEST_SCOPE
import com.ukkera.pos.database.TestLocalAuthorizationEnvironment
import com.ukkera.pos.database.TestLocalStorageAead
import com.ukkera.pos.database.buildOwnedMasterRoom
import com.ukkera.pos.database.requireDurableWriter
import com.ukkera.pos.database.storageKey
import com.ukkera.pos.database.testId
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CredentialBinding
import com.ukkera.pos.security.DenyAllPrincipalVerifier
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccessIssuance
import com.ukkera.pos.security.LocalAccessTokenHash
import com.ukkera.pos.security.LocalAccountSessionState
import com.ukkera.pos.security.LocalAuthEvent
import com.ukkera.pos.security.LocalAuthEventKind
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalMasterLifecycleAction
import com.ukkera.pos.security.LocalMasterLifecycleDenial
import com.ukkera.pos.security.LocalMasterLifecycleReceipt
import com.ukkera.pos.security.LocalMasterLifecycleRequest
import com.ukkera.pos.security.LocalMasterLifecycleResult
import com.ukkera.pos.security.LocalMasterLifecycleStatus
import com.ukkera.pos.security.LocalSealResult
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionRecord
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.PasswordHashParameters
import com.ukkera.pos.security.PasswordHashRecord
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.ProtectedLocalAccount
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.ProtectedPepperReference
import com.ukkera.pos.security.ProtectedStorageKeyReference
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.security.lifecycleCoordinatesDigest
import com.ukkera.pos.security.lifecycleRequestDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * Supplemental ALR-S06/S07 expectations over the real Room lifecycle service, real protected
 * store/source and a controlled current peer. The accepted principal is a TEST-ONLY verifier;
 * it is not password authentication, native identity, admitted RPC or device acceptance.
 */
internal class RoomLocalMasterLifecycleServiceTest {
    @Test
    fun currentGrantCommitsOneImmutableEventAndSameIdReplaysIt() = withFixture { f, service ->
        val request = request()
        val first = assertIs<LocalMasterLifecycleResult.Committed>(service.apply(credentials(), request))
        assertEquals(request.requestId, first.receipt.eventId, "ALR-S06 event ID")
        assertEquals(LocalMasterLifecycleAction.STOP, first.receipt.action, "ALR-S06 action")
        assertEquals(coordinates().installationId, first.receipt.installationId, "ALR-S06 installation")
        assertEquals(lifecycleCoordinatesDigest(coordinates()), first.receipt.coordinatesDigest,
            "ALR-S06 full twelve coordinates")
        assertEquals(lifecycleRequestDigest(coordinates(), request), first.receipt.requestDigest,
            "ALR-S06 request binding")
        val event = checkNotNull(f.authEvent(request.requestId))
        assertEquals(LocalAuthEventKind.MASTER_LIFECYCLE, event.kind, "ALR-S06 committed kind")
        assertEquals(Revision(4), event.accountRevision, "ALR-S06 current account revision")
        assertEquals(Revision(3), checkNotNull(event.lifecycle).policyRevision,
            "ALR-S06 committed policy revision")

        val lookup = assertIs<LocalMasterLifecycleStatus.Committed>(service.lookup(credentials(), request))
        assertSameReceipt(first.receipt, lookup.receipt, "ALR-S06 lookup replay")
        val replay = assertIs<LocalMasterLifecycleResult.Committed>(service.apply(credentials(), request))
        assertSameReceipt(first.receipt, replay.receipt, "ALR-S07 same-ID replay")
        val after = checkNotNull(f.authEvent(request.requestId))
        assertEquals(event.contentDigest, after.contentDigest, "ALR-S07 replay did not replace event")
        assertEquals(event.at, after.at, "ALR-S07 replay did not replace observation")
    }

    @Test
    fun sameRequestIdWithDifferentActionIsIdentityConflictAndAddsNoEvent() = withFixture { f, service ->
        val request = request()
        assertIs<LocalMasterLifecycleResult.Committed>(service.apply(credentials(), request))
        val conflicting = service.apply(credentials(),
            LocalMasterLifecycleRequest(request.requestId, LocalMasterLifecycleAction.RESUME))
        assertEquals(LocalMasterLifecycleDenial.IDENTITY_CONFLICT,
            assertIs<LocalMasterLifecycleResult.Denied>(conflicting).reason, "ALR-S07 action conflict")
        val event = checkNotNull(f.authEvent(request.requestId))
        assertEquals(LocalMasterLifecycleAction.STOP, checkNotNull(event.lifecycle).action,
            "ALR-S07 conflicting action did not rewrite event")
    }

    @Test
    fun missingLifecycleGrantFailsClosedWithoutAnEvent() = withFixture(grantLifecycle = false) { f, service ->
        val request = request()
        val result = service.apply(credentials(), request)
        assertTrue(result !is LocalMasterLifecycleResult.Committed, "ALR-S06 missing grant committed")
        assertNull(f.authEvent(request.requestId), "ALR-S06 missing grant appended an event")
        val lookup = service.lookup(credentials(), request)
        assertTrue(lookup !is LocalMasterLifecycleStatus.Committed, "ALR-S06 missing grant lookup committed")
    }

    @Test
    fun expiredIssuanceAndUncurrentPeerFailClosedWithoutEvents() = withFixture { f, service ->
        val expiredRequest = request(31)
        f.environment.elapsed = 2_001
        assertTrue(service.apply(credentials(), expiredRequest) !is LocalMasterLifecycleResult.Committed,
            "ALR-S06 expired issuance committed")
        assertNull(f.authEvent(expiredRequest.requestId),
            "ALR-S06 expired issuance appended an event")

        f.environment.elapsed = 1_500
        f.peerCurrent = false
        val peerRequest = request(32)
        assertTrue(service.apply(credentials(), peerRequest) !is LocalMasterLifecycleResult.Committed,
            "ALR-S06 uncurrent peer committed")
        assertNull(f.authEvent(peerRequest.requestId), "ALR-S06 uncurrent peer appended an event")
        f.peerCurrent = true
    }

    @Test
    fun revokedSessionFailsClosedWithoutAnEvent() = withFixture { f, service ->
        val account = checkNotNull(f.account())
        val state = account.state
        val revoked = LocalAccountSessionState(state.scope, state.actorId, state.enabled,
            Revision(state.revision.value + 1), SessionGeneration(state.generation.value + 1),
            state.sessions.map { it.copy(status = SessionStatus.REVOKED) })
        f.store.transaction { transaction ->
            check(transaction.replaceAccount(state.revision, revoked))
        }
        val request = request(33)
        assertTrue(service.apply(credentials(), request) !is LocalMasterLifecycleResult.Committed,
            "ALR-S06 revoked session committed")
        assertNull(f.authEvent(request.requestId), "ALR-S06 revoked session appended an event")
    }

    @Test
    fun changedCurrentPolicyDoesNotRewriteTheCommittedReceipt() = withFixture { f, service ->
        val request = request(34)
        val first = assertIs<LocalMasterLifecycleResult.Committed>(service.apply(credentials(), request))
        check(f.store.transaction { transaction ->
            transaction.replacePolicy(Revision(3), f.policy(revision = 4, grants = lifecycleGrant()))
        })
        val replay = assertIs<LocalMasterLifecycleResult.Committed>(service.apply(credentials(), request))
        assertEquals(Revision(3), replay.receipt.policyRevision, "ALR-S07 original policy revision")
        assertEquals(first.receipt.policyRevision, replay.receipt.policyRevision, "ALR-S07 stable receipt")
        val event = checkNotNull(f.authEvent(request.requestId))
        assertEquals(Revision(3), checkNotNull(event.lifecycle).policyRevision,
            "ALR-S07 changed policy did not rewrite event")
    }

    @Test
    fun foreignEventWithSameIdIsIdentityConflict() = withFixture { f, service ->
        val request = request(35)
        f.store.transaction { transaction ->
            transaction.insertAuthEvent(LocalAuthEvent(TEST_SCOPE, request.requestId, LocalAuthEventKind.SIGNED_IN,
                TEST_ACTOR, TEST_ACTOR, testId(9110), Revision(4), MonotonicMoment(testId(90), 1_500), null))
        }
        val result = service.apply(credentials(), request)
        assertEquals(LocalMasterLifecycleDenial.IDENTITY_CONFLICT,
            assertIs<LocalMasterLifecycleResult.Denied>(result).reason, "ALR-S07 foreign event conflict")
        val event = checkNotNull(f.authEvent(request.requestId))
        assertEquals(LocalAuthEventKind.SIGNED_IN, event.kind, "ALR-S07 foreign event was replaced")
    }

    @Test
    fun requestIdEqualToInstallationIdIsRefusedBeforeInsert() = withFixture { f, service ->
        val request = LocalMasterLifecycleRequest(coordinates().installationId, LocalMasterLifecycleAction.STOP)
        val result = service.apply(credentials(), request)
        assertEquals(LocalMasterLifecycleDenial.IDENTITY_CONFLICT,
            assertIs<LocalMasterLifecycleResult.Denied>(result).reason, "ALR-S07 installation-ID request")
        assertNull(f.authEvent(request.requestId), "ALR-S07 installation-ID request appended an event")
    }

    private fun withFixture(grantLifecycle: Boolean = true,
        block: suspend (OwnedLifecycleFixture, RoomLocalMasterLifecycleService) -> Unit) =
        runTest {
            val f = OwnedLifecycleFixture()
            try {
                f.seed(if (grantLifecycle) lifecycleGrant() else emptyList())
                f.peerCurrent = true
                val peer = RoomLocalAccessPeerBinding(CONNECTION, TEST_SCOPE, TEST_AUTHORITY.deviceId,
                    "synthetic-public-key-id", "synthetic-fingerprint") { f.peerCurrent }
                val peers = RoomLocalAccessPeerSource { id -> if (id == CONNECTION) peer else null }
                val principals = TrustedPrincipalFactory(object : PrincipalVerifier by DenyAllPrincipalVerifier {
                    override suspend fun verifyLocal(credentials: LocalGatewayCredentials) = VerifiedLocalIdentity(
                        TEST_ACTOR, TEST_SCOPE, TEST_AUTHORITY.deviceId, testId(9110), SessionGeneration(5),
                        Revision(6), AuthenticationPurpose.INTERACTIVE)
                })
                block(f, RoomLocalMasterLifecycleService(TEST_SCOPE, coordinates(), f.store,
                    f.source, peers, principals))
            } finally { f.close() }
        }

    private fun lifecycleGrant(): List<PermissionGrant> = listOf(PermissionGrant(
        Permission(SecurityAction.MASTER_LIFECYCLE, PermissionVerb.ADMINISTER), TEST_SCOPE, GrantEffect.ALLOW,
        PermissionLimits(), OwnerAccess.OWN_ONLY, TEST_ACTOR))

    private fun credentials() = LocalGatewayCredentials(CONNECTION, OpaqueCredential(ByteArray(32) { 29 }))

    private fun request(suffix: Int = 13) =
        LocalMasterLifecycleRequest(testId(2_000 + suffix), LocalMasterLifecycleAction.STOP)

    private fun coordinates() = SelfOwnedAllocationCoordinatesV1(
        testId(2_001), TEST_SCOPE,
        testId(2_005), testId(2_006), testId(2_007), testId(2_008), testId(2_009), testId(2_010),
        testId(2_011), testId(2_012),
    )

    private fun assertSameReceipt(expected: LocalMasterLifecycleReceipt, actual: LocalMasterLifecycleReceipt,
        message: String) {
        assertEquals(expected.eventId, actual.eventId, "$message event")
        assertEquals(expected.action, actual.action, "$message action")
        assertEquals(expected.actorId, actual.actorId, "$message actor")
        assertEquals(expected.sessionId, actual.sessionId, "$message session")
        assertEquals(expected.installationId, actual.installationId, "$message installation")
        assertEquals(expected.coordinatesDigest, actual.coordinatesDigest, "$message coordinates")
        assertEquals(expected.requestDigest, actual.requestDigest, "$message request")
        assertEquals(expected.accountRevision, actual.accountRevision, "$message account")
        assertEquals(expected.policyRevision, actual.policyRevision, "$message policy")
        assertEquals(expected.committedAt, actual.committedAt, "$message observation")
    }

    private companion object {
        private val CONNECTION: DomainId = testId(9_599)
    }
}

/**
 * ALR2-S04 over the real Room lifecycle service/store and current-authorization source.
 * Missing/revoked grants and revoked sessions are controlled current facts; the service,
 * transaction, source and protected store are production code. No native master is present.
 */
internal class RoomLocalMasterLifecycleDenialTest {
    @Test
    fun missingLifecycleGrantIsDeniedNotAuthorizedForApplyAndLookupWithoutAnEvent() =
        withDenialFixture(grantLifecycle = false) { f, service ->
            val request = request(41)
            val applied = service.apply(credentials(), request)
            assertEquals(LocalMasterLifecycleDenial.NOT_AUTHORIZED,
                assertIs<LocalMasterLifecycleResult.Denied>(applied, "ALR2-S04 missing grant apply").reason,
                "ALR2-S04 missing grant apply diagnostic")
            val lookedUp = service.lookup(credentials(), request)
            assertEquals(LocalMasterLifecycleDenial.NOT_AUTHORIZED,
                assertIs<LocalMasterLifecycleStatus.Denied>(lookedUp, "ALR2-S04 missing grant lookup").reason,
                "ALR2-S04 missing grant lookup diagnostic")
            assertNull(f.authEvent(request.requestId), "ALR2-S04 missing grant appended an event")
        }

    @Test
    fun revokedLifecycleGrantIsDeniedNotAuthorizedForApplyAndLookupWithoutAnEvent() =
        withDenialFixture { f, service ->
            check(f.store.transaction { transaction ->
                transaction.replacePolicy(Revision(3), f.policy(revision = 4, grants = emptyList()))
            })
            val request = request(42)
            val applied = service.apply(credentials(), request)
            assertEquals(LocalMasterLifecycleDenial.NOT_AUTHORIZED,
                assertIs<LocalMasterLifecycleResult.Denied>(applied, "ALR2-S04 revoked grant apply").reason,
                "ALR2-S04 revoked grant apply diagnostic")
            val lookedUp = service.lookup(credentials(), request)
            assertEquals(LocalMasterLifecycleDenial.NOT_AUTHORIZED,
                assertIs<LocalMasterLifecycleStatus.Denied>(lookedUp, "ALR2-S04 revoked grant lookup").reason,
                "ALR2-S04 revoked grant lookup diagnostic")
            assertNull(f.authEvent(request.requestId), "ALR2-S04 revoked grant appended an event")
        }

    @Test
    fun revokedSessionIsDeniedNotAuthorizedForApplyAndLookupWithoutAnEvent() =
        withDenialFixture { f, service ->
            val account = checkNotNull(f.account())
            val state = account.state
            val revoked = LocalAccountSessionState(state.scope, state.actorId, state.enabled,
                Revision(state.revision.value + 1), SessionGeneration(state.generation.value + 1),
                state.sessions.map { it.copy(status = SessionStatus.REVOKED) })
            f.store.transaction { transaction -> check(transaction.replaceAccount(state.revision, revoked)) }
            val request = request(43)
            val applied = service.apply(credentials(), request)
            assertEquals(LocalMasterLifecycleDenial.NOT_AUTHORIZED,
                assertIs<LocalMasterLifecycleResult.Denied>(applied, "ALR2-S04 revoked session apply").reason,
                "ALR2-S04 revoked session apply diagnostic")
            val lookedUp = service.lookup(credentials(), request)
            assertEquals(LocalMasterLifecycleDenial.NOT_AUTHORIZED,
                assertIs<LocalMasterLifecycleStatus.Denied>(lookedUp, "ALR2-S04 revoked session lookup").reason,
                "ALR2-S04 revoked session lookup diagnostic")
            assertNull(f.authEvent(request.requestId), "ALR2-S04 revoked session appended an event")
        }

    private fun withDenialFixture(grantLifecycle: Boolean = true,
        block: suspend (OwnedLifecycleFixture, RoomLocalMasterLifecycleService) -> Unit) =
        runTest {
            val f = OwnedLifecycleFixture()
            try {
                f.seed(if (grantLifecycle) lifecycleGrant() else emptyList())
                f.peerCurrent = true
                val peer = RoomLocalAccessPeerBinding(DENIAL_CONNECTION, TEST_SCOPE, TEST_AUTHORITY.deviceId,
                    "synthetic-public-key-id", "synthetic-fingerprint") { f.peerCurrent }
                val peers = RoomLocalAccessPeerSource { id -> if (id == DENIAL_CONNECTION) peer else null }
                val principals = TrustedPrincipalFactory(object : PrincipalVerifier by DenyAllPrincipalVerifier {
                    override suspend fun verifyLocal(credentials: LocalGatewayCredentials) = VerifiedLocalIdentity(
                        TEST_ACTOR, TEST_SCOPE, TEST_AUTHORITY.deviceId, testId(9110), SessionGeneration(5),
                        Revision(6), AuthenticationPurpose.INTERACTIVE)
                })
                block(f, RoomLocalMasterLifecycleService(TEST_SCOPE, coordinates(), f.store,
                    f.source, peers, principals))
            } finally { f.close() }
        }

    private fun lifecycleGrant(): List<PermissionGrant> = listOf(PermissionGrant(
        Permission(SecurityAction.MASTER_LIFECYCLE, PermissionVerb.ADMINISTER), TEST_SCOPE, GrantEffect.ALLOW,
        PermissionLimits(), OwnerAccess.OWN_ONLY, TEST_ACTOR))

    private fun credentials() = LocalGatewayCredentials(DENIAL_CONNECTION, OpaqueCredential(ByteArray(32) { 29 }))

    private fun request(suffix: Int) = LocalMasterLifecycleRequest(testId(3_000 + suffix), LocalMasterLifecycleAction.STOP)

    private fun coordinates() = SelfOwnedAllocationCoordinatesV1(
        testId(2_001), TEST_SCOPE,
        testId(2_005), testId(2_006), testId(2_007), testId(2_008), testId(2_009), testId(2_010),
        testId(2_011), testId(2_012),
    )

    private companion object {
        private val DENIAL_CONNECTION: DomainId = testId(9_598)
    }
}

/**
 * Schema-v7 owned Room fixture for the lifecycle service. It uses real Room/SQLite and the
 * production protected codecs, but the accepting storage key and current-peer source are
 * explicitly controlled test ports. No native master or Android runtime is involved.
 */
private class OwnedLifecycleFixture {
    private val keyReference = ProtectedStorageKeyReference(testId(9102))
    private val protection = TestLocalStorageAead(keyReference)
    private val path = Files.createTempDirectory("ukkera-lifecycle-service-test-").resolve("master.db")
    private val database: MasterRoomDatabase = buildOwnedMasterRoom(
        Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize),
        BundledSQLiteDriver(),
    )
    val store = RoomLocalSecurityStore(database, protection, keyReference)
    val environment = TestLocalAuthorizationEnvironment(testId(9101))
    val source = RoomCurrentLocalAuthorizationSource(store, environment)
    var peerCurrent: Boolean = true

    suspend fun seed(grants: List<PermissionGrant>) {
        val account = newAccount()
        val policy = policy(revision = 3, grants = grants)
        val admission = admission()
        val accountRecord = seal(ProtectedLocalBinding(
            ProtectedLocalRecordKey.Account(TEST_SCOPE, TEST_ACTOR), account.state.revision),
            ProtectedLocalSecurityCodec.encodeAccount(account))
        val policyRecord = seal(ProtectedLocalBinding(
            ProtectedLocalRecordKey.EffectivePolicy(TEST_SCOPE, TEST_ACTOR), policy.revision),
            ProtectedLocalSecurityCodec.encodePolicy(policy))
        val admissionRecord = seal(ProtectedLocalBinding(
            ProtectedLocalRecordKey.Admission(TEST_SCOPE, TEST_AUTHORITY.deviceId), admission.revision),
            ProtectedLocalSecurityCodec.encodeAdmission(admission))
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                // The owned schema guards demand an actual original writer frame for the
                // branch seed. This TEST fixture inserts only its explicit synthetic rows
                // with the guards temporarily lifted, then restores every guard before the
                // service writes. The service itself always runs with the real guards.
                val guards = connection.usePrepared("SELECT name,sql FROM main.sqlite_master WHERE type='trigger'") {
                    buildList { while (it.step()) add(it.getText(0) to it.getText(1)) }
                }
                try {
                    for ((name, _) in guards) connection.usePrepared("DROP TRIGGER `$name`") { it.step() }
                    database.facts().insertBranch(BranchRow(TEST_SCOPE.storageKey(),
                        TEST_AUTHORITY.assignmentId.value, TEST_AUTHORITY.deviceId.value, 1, 0, 0,
                        "0".repeat(64), true,
                        Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(TEST_SCOPE.branchId))))
                    val dao = database.localSecurity()
                    dao.insertAccount(ProtectedLocalAccountRow(TEST_SCOPE.storageKey(), TEST_ACTOR.value, 1, 1,
                        "AES-256-GCM", accountRecord.binding.revision.value, keyReference.keyId.value,
                        accountRecord.copyNonce(), accountRecord.copyCiphertextAndTag()))
                    dao.insertPolicy(ProtectedEffectivePolicyRow(TEST_SCOPE.storageKey(), TEST_ACTOR.value, 1, 1,
                        "AES-256-GCM", policyRecord.binding.revision.value, keyReference.keyId.value,
                        policyRecord.copyNonce(), policyRecord.copyCiphertextAndTag()))
                    dao.insertAdmission(ProtectedDeviceAdmissionRow(TEST_SCOPE.storageKey(),
                        TEST_AUTHORITY.deviceId.value, 1, 1, "AES-256-GCM",
                        admissionRecord.binding.revision.value, keyReference.keyId.value,
                        admissionRecord.copyNonce(), admissionRecord.copyCiphertextAndTag()))
                } finally {
                    for ((_, ddl) in guards) connection.usePrepared(ddl) { it.step() }
                }
                // The owned schema needs the transaction-private writer frame row before any
                // protected write. This TEST fixture installs one explicit synthetic frame so
                // the real store/service writes can execute; production always derives it from
                // an actual RoomRuntimeHistoryFrame/native original.
                connection.usePrepared("INSERT INTO runtime_history_write_frame_v2 VALUES(1,?,?,?,?,?,?,?,?)") {
                    it.bindText(1, testId(9001).value)
                    it.bindText(2, testId(9002).value)
                    it.bindLong(3, RuntimeWritePurposeV2.ROLE_TRANSITION.tag.toLong())
                    it.bindLong(4, 1)
                    it.bindLong(5, 0)
                    it.bindText(6, "a".repeat(64))
                    it.bindLong(7, 1)
                    it.bindText(8, "b".repeat(64))
                    check(!it.step())
                }
            }
        }
        store.transaction { transaction -> transaction.insertIssuance(issuance()) }
    }

    suspend fun account(): ProtectedLocalAccount? = store.transaction { transaction ->
        transaction.readProtectedAccount(TEST_SCOPE, TEST_ACTOR)
    }

    suspend fun authEvent(id: DomainId): LocalAuthEvent? = store.transaction { transaction ->
        transaction.readAuthEvent(TEST_SCOPE, id)
    }

    fun policy(revision: Long, grants: List<PermissionGrant>): ActivatedLocalPolicy =
        ActivatedLocalPolicy(TEST_SCOPE, TEST_ACTOR, Revision(revision), grants,
            LocalSessionPolicy(), ThrottlePolicy())

    fun close() = database.close()

    private fun allowLifecycle(): PermissionGrant = PermissionGrant(
        Permission(SecurityAction.MASTER_LIFECYCLE, PermissionVerb.ADMINISTER), TEST_SCOPE,
        GrantEffect.ALLOW, PermissionLimits(), OwnerAccess.OWN_ONLY, TEST_ACTOR)

    private fun newAccount(): ProtectedLocalAccount = ProtectedLocalAccount(
        LocalAccountSessionState(TEST_SCOPE, TEST_ACTOR, true, Revision(4), SessionGeneration(5),
            listOf(LocalSessionRecord(testId(9110), TEST_SCOPE, TEST_ACTOR, TEST_AUTHORITY.deviceId,
                SessionGeneration(5), SessionStatus.ACTIVE, AuthenticationPurpose.INTERACTIVE))),
        Revision(2), PasswordHashRecord(CredentialBinding(TEST_SCOPE, TEST_ACTOR, Revision(2)),
            PasswordHashParameters(), ProtectedPepperReference(testId(9111)),
            ByteArray(16) { (it + 33).toByte() }, ByteArray(32) { (it + 101).toByte() }),
    )

    private fun admission(): LocalDeviceAdmission = LocalDeviceAdmission(TEST_SCOPE,
        TEST_AUTHORITY.deviceId, Revision(6), true, "synthetic-public-key-id",
        "synthetic-fingerprint", setOf("test.local-storage"))

    private fun issuance(): LocalAccessIssuance = LocalAccessIssuance(TEST_SCOPE, TEST_ACTOR,
        testId(9110), TEST_AUTHORITY.deviceId, SessionGeneration(5), Revision(2), Revision(6),
        testId(9500), MonotonicMoment(testId(9101), 1_000), 1_000,
        LocalAccessTokenHash.hashSecret(ByteArray(32) { (it + 83).toByte() }))

    private suspend fun seal(binding: ProtectedLocalBinding, plaintext: ByteArray): com.ukkera.pos.security.SealedLocalRecord =
        try {
            assertIs<LocalSealResult.Sealed>(protection.seal(keyReference, binding, plaintext)).record
        } finally { plaintext.fill(0) }
}
