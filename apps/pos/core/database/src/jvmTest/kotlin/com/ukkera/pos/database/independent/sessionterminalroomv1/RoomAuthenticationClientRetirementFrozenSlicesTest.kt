package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

/** Existing STC-S01/S04/S09/S10 refusal/lifetime observables through actual Room authentication
 * and the public same-device client root. Synthetic initial provisioning, JCA keys, peer and
 * clock remain explicit TEST DATA. No native history, authority, financial fixture, RPC or full
 * frozen case is qualified. No LocalSessionViewResult.Current reply is fabricated here. */
class RoomAuthenticationClientRetirementFrozenSlicesTest {
    @Test fun stcS01_reachableMasterLogoutRevokesActualTokensAndRetiresOldClientRoot() = withFixture { f ->
        val baseline = f.businessRows()
        assertEquals(LocalSignOutResult.Revoked, f.endpoint.logout(f.originalAccess()))
        f.assertOriginalDenied()
        f.assertClientRetired()
        f.assertOtherAccountCurrent()
        assertEquals(baseline, f.businessRows())
    }

    @Test fun stcS09_externalRefreshRotationCannotRepairAnOldClientCredentialBinding() = withFixture { f ->
        val baseline = f.businessRows()
        f.advanceWithinOriginalAccessLifetime()
        val renewed = f.retain(assertIs<LocalAuthenticationResult.Authenticated>(
            f.endpoint.refresh(f.originalRefresh())).tokens)
        assertEquals(f.actorA, renewed.view.actorId)
        assertEquals(f.device, renewed.view.deviceId)
        assertEquals(f.original.view.workExpiresAt, renewed.view.workExpiresAt)
        assertIs<LocalSessionViewResult.Current>(f.endpoint.current(
            LocalGatewayCredentials(f.connectionId, renewed.access)))
        f.assertOriginalDenied()
        f.assertClientRetired()
        f.assertOtherAccountCurrent()
        assertEquals(baseline, f.businessRows())
    }

    @Test fun authV1E005_stcS10_explicitDeviceMoveKeepsSuccessorCurrentButCannotRebindOldRoot() = withFixture { f ->
        val baseline = f.businessRows()
        val moved = f.retain(assertIs<LocalAuthenticationResult.Authenticated>(f.endpoint.signIn(
            f.otherConnectionId, f.loginA, f.passwordA, explicitDeviceMove = true)).tokens)
        assertEquals(f.actorA, moved.view.actorId)
        assertEquals(f.otherDevice, moved.view.deviceId)
        assertIs<LocalSessionViewResult.Current>(f.endpoint.current(
            LocalGatewayCredentials(f.otherConnectionId, moved.access)))
        f.assertOriginalDenied()
        f.assertClientRetired()
        f.assertOtherAccountCurrent()
        assertEquals(baseline, f.businessRows())
    }

    @Test fun stcS04_concurrentDenialCallersCannotRestoreTheLoggedOutRootOrEndOtherUser() = withFixture { f ->
        val baseline = f.businessRows()
        assertEquals(LocalSignOutResult.Revoked, f.endpoint.logout(f.originalAccess()))
        coroutineScope { List(4) { async(Dispatchers.Default) { f.assertClientReadDenied() } }.awaitAll() }
        f.assertClientRetired()
        f.assertOriginalDenied()
        f.assertOtherAccountCurrent()
        assertEquals(baseline, f.businessRows())
    }

    private fun withFixture(body: suspend (RoomAuthenticationClientFixture) -> Unit) = runBlocking {
        val fixture = RoomAuthenticationClientFixture()
        try { fixture.start(); body(fixture) }
        finally { withContext(NonCancellable) { fixture.close() } }
    }
}

/** One actual SQLite pool/grouped service, real authentication and real encrypted client journals.
 * Uses the existing explicitly controlled schema-7 test builder; the legacy production opener,
 * native owner, history admission and business writer are never enabled. */
internal class RoomAuthenticationClientFixture {
    private val frozen: JsonObject = run {
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.isRegularFile(it.resolve("qa/pos/freezes/session-terminal-cleanup-v1/fixtures.json")) }
        Json.parseToJsonElement(Files.readString(root.resolve(
            "qa/pos/freezes/session-terminal-cleanup-v1/fixtures.json"))).jsonObject
    }
    private fun id(name: String) = DomainId(frozen.getValue("identities").jsonObject.getValue(name).jsonPrimitive.content)
    private val scope = BranchScope(id("organization_1"), id("retail_company"), id("retail_branch"))
    val actorA = id("account_a")
    private val actorB = id("account_b")
    // Frozen master-cashier variant: genuinely generated session references are captured at issue.
    val device = id("master_device")
    val otherDevice = id("second_client_device")
    val connectionId = DomainId.random()
    val otherConnectionId = DomainId.random()
    val loginA = LocalLoginIdentifier("stc-room-A")
    private val loginB = LocalLoginIdentifier("stc-room-B")
    private val random = SecureRandom()
    val passwordA = CharArray(48) { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[random.nextInt(62)] }
    private val passwordB = CharArray(48) { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[random.nextInt(62)] }
    private val keys = AuthV1SyntheticKeyPorts(ProtectedStorageKeyReference(DomainId.random()),
        ProtectedPepperReference(DomainId.random()), random)
    private val issuerContextId = DomainId.random()
    private val bootId = DomainId.random()
    private val elapsedMillis = java.util.concurrent.atomic.AtomicLong(0)
    private val environmentCurrent = AtomicBoolean(true)
    private val environment = LocalAuthenticationEnvironment {
        if (environmentCurrent.get()) LocalAuthenticationObservation(issuerContextId,
            MonotonicMoment(bootId, elapsedMillis.get())) else null
    }
    private val peers = AuthV1SyntheticPeers()
    private val requests = List(4) { DomainId.random() }
    private val ceremony = AuthV1SyntheticProvisioning(scope, mapOf(
        requests[0] to (LocalProvisioningAction.ADMIT_DEVICE to device),
        requests[1] to (LocalProvisioningAction.ADMIT_DEVICE to otherDevice),
        requests[2] to (LocalProvisioningAction.PROVISION_ACCOUNT to actorA),
        requests[3] to (LocalProvisioningAction.PROVISION_ACCOUNT to actorB)))
    private val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val directory = Files.createTempDirectory("ukkera-room-client-retirement-", permissions).toRealPath()
    private val path = directory.resolve("master.db")
    private val life = AtomicBoolean(true)
    private val retirements = java.util.concurrent.CopyOnWriteArrayList<LocalMasterRetirementReason>()
    private val ownedTokens = mutableListOf<LocalSessionTokens>()
    private var database: MasterRoomDatabase? = null
    private var service: JvmLocalMasterService? = null
    private var auth: JvmEncryptedClientCredentialJournal? = null
    private var intents: JvmEncryptedClientIntentJournal? = null
    private var gateway: LocalEndpointBranchGateway? = null
    private var session: BranchUiSession? = null
    private var client: ObservedRoomClient? = null
    private var other: LocalSessionTokens? = null
    val endpoint: LocalMasterEndpoint get() = checkNotNull(service).endpoint
    val original: LocalSessionTokens get() = checkNotNull(client).original
    fun retain(tokens: LocalSessionTokens): LocalSessionTokens = tokens.also { ownedTokens.add(it) }

    suspend fun start() {
        check(Files.getFileStore(directory).supportsFileAttributeView("posix"))
        val db = buildControlledMasterRoomFixture(Room.databaseBuilder<MasterRoomDatabase>(
            path.toString(), MasterRoomConstructor::initialize)).also { database = it }
        val authority = AuthorityIdentity(DomainId.random(), device, Revision(7))
        db.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                db.facts().insertBranch(BranchRow(scope.storageKey(), authority.assignmentId.value,
                    device.value, authority.epoch.value, 0, 0, "0".repeat(64), false,
                    Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(scope.branchId))))
            }
        }
        peers.install(connectionId, scope, device, "stc-room-original-peer")
        peers.install(otherConnectionId, scope, otherDevice, "stc-room-other-peer")
        service = JvmLocalMasterService(db, path, scope, authority, emptyList(), emptyList(),
            LocalMasterSecurityDependencies(keys, keys.storageReference,
                JvmLocalPasswordHasher(keys, PasswordHashParameters(), random), keys.pepperReference,
                JvmLocalAuthenticationEntropy(random), environment, peers, 2, ceremony),
            UnqualifiedMasterRuntime, LocalMasterRetirement { retirements.add(it) },
            MasterClock { UtcInstant.parseCanonical("2026-11-01T00:00:00Z") }, Dispatchers.Default,
            2, 8, 30_000, 30_000)
        for ((index, target) in listOf(device, otherDevice).withIndex()) {
            val descriptor = if (target == device) "stc-room-original-peer" else "stc-room-other-peer"
            assertIs<LocalCredentialAdministrationResult.Applied>(endpoint.admitDevice(requests[index],
                LocalDeviceAdmission(scope, target, Revision(0), true, descriptor, null, emptySet())))
        }
        val authFixture = frozen.getValue("F-STC-AUTH").jsonObject
        val throttleInput = authFixture.getValue("throttle_policy").jsonObject
        val throttle = ThrottlePolicy(throttleInput.getValue("freeFailures").jsonPrimitive.int,
            throttleInput.getValue("initialDelayMillis").jsonPrimitive.long,
            throttleInput.getValue("maximumDelayMillis").jsonPrimitive.long,
            throttleInput.getValue("attemptTimeoutMillis").jsonPrimitive.long)
        val tokenInput = authFixture.getValue("session_a").jsonObject
        val tokenPolicy = LocalTokenPolicy(tokenInput.getValue("access_expiry_elapsed_millis").jsonPrimitive.long,
            tokenInput.getValue("work_deadline_elapsed_millis").jsonPrimitive.long)
        for ((index, actor) in listOf(actorA, actorB).withIndex()) {
            // The auth-only slice binds own-order/drawer grants; no report/business fixture is supplied.
            val grants = listOf(SecurityAction.ORDER_READ, SecurityAction.CASH_READ).map { action ->
                PermissionGrant(Permission(action, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
                    ownerAccess = OwnerAccess.OWN_ONLY, namedSubjectId = actor)
            }
            val definition = LocalAccountProvisioning(actor, if (index == 0) loginA else loginB,
                ActivatedLocalPolicy(scope, actor, Revision(0), grants, LocalSessionPolicy(1), throttle),
                tokenPolicy, setOf(device, otherDevice))
            assertIs<LocalCredentialAdministrationResult.Applied>(endpoint.provisionAccount(requests[index + 2],
                definition, if (index == 0) passwordA else passwordB))
        }
        ceremony.close()
        other = retain(assertIs<LocalAuthenticationResult.Authenticated>(endpoint.signIn(
            otherConnectionId, loginB, passwordB)).tokens)
        val observed = ObservedRoomClient(endpoint).also { client = it }
        val owner = LocalClientIdentity(scope, actorA, device)
        val credentialDirectory = Files.createDirectory(directory.resolve("credentials"), permissions)
        val intentDirectory = Files.createDirectory(directory.resolve("intents"), permissions)
        val keyGenerator = KeyGenerator.getInstance("AES").apply { init(256) }
        val authView = JvmClientCredentialCustodyView(credentialDirectory, LocalClientDeviceIdentity(scope, device),
            DomainId.random(), DomainId.random(), DomainId.random(), keyGenerator.generateKey(), life::get)
        val intentView = JvmClientJournalCustodyView(intentDirectory, owner, DomainId.random(), DomainId.random(),
            DomainId.random(), keyGenerator.generateKey(), life::get)
        val credentialJournal = assertIs<JvmClientCredentialJournalOpenResult.Opened>(
            JvmEncryptedClientCredentialJournal.createNew(JvmClientCredentialCustody { authView.takeIf { life.get() } })).journal
            .also { auth = it }
        val intentJournal = assertIs<JvmClientJournalOpenResult.Opened>(
            JvmEncryptedClientIntentJournal.createNew(JvmClientJournalCustody { intentView.takeIf { life.get() } })).journal
            .also { intents = it }
        val connection = LocalMasterClientConnection(observed, checkNotNull(peers.capture(connectionId)), DomainId.random(),
            LocalWorkflowInstallation(emptySet(), setOf(LocalStockStoreProfile.QUANTITIES)),
            LocalBranchDisplayLabels(scope, "Synthetic retail company", "Synthetic retail branch"), life::get)
        val root = LocalEndpointBranchGateway(MutableStateFlow(connection), credentialJournal,
            LocalClientIntentJournalSource { requested -> intentJournal.takeIf { it.owner == requested } }, Dispatchers.Default)
            .also { gateway = it }
        session = assertIs<SignInResult.Authenticated<BranchUiSession>>(root.signIn(loginA.value, passwordA.copyOf())).session
        assertEquals(actorA, original.view.actorId)
        assertEquals(device, original.view.deviceId)
        assertIs<LocalSessionViewResult.Current>(endpoint.current(originalAccess()))
        assertTrue(checkNotNull(session).isCurrent())
        assertEquals(0, observed.protectedCalls.get())
        assertOtherAccountCurrent()
    }

    fun holdSecondWorkflowObservation(): RoomSessionObservationGate = checkNotNull(client).holdSecondObservation()

    /** Observe only local lifecycle here: another endpoint call could conceal a missed retirement. */
    fun assertRetiredBeforeAnotherObservation() {
        val old = checkNotNull(session)
        assertFalse(old.isCurrent())
        assertEquals(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED, old.lifecycle?.value)
        assertEquals(0, checkNotNull(client).protectedCalls.get())
    }

    fun advanceWithinOriginalAccessLifetime() {
        val elapsed = original.view.accessExpiresAt.elapsedMillis / 2
        check(elapsed > 0 && elapsed < original.view.accessExpiresAt.elapsedMillis)
        check(elapsedMillis.compareAndSet(0, elapsed))
    }

    fun originalAccess() = LocalGatewayCredentials(connectionId, original.access)
    fun originalRefresh() = LocalGatewayCredentials(connectionId, original.refresh)

    suspend fun assertOriginalDenied() {
        assertEquals(LocalSessionViewResult.Denied, endpoint.current(originalAccess()))
        assertIs<LocalAuthenticationResult.Denied>(endpoint.refresh(originalRefresh()))
    }

    suspend fun assertClientReadDenied() {
        val query = LocalWorkflowQuery(LocalWorkflow.INVENTORY,
            reference = LocalRecordReference(LocalRecordKind.SKU,
                DomainId(frozen.getValue("F-STC-RETAIL").jsonObject.getValue("product").jsonPrimitive.content)))
        assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalReadResult.Unavailable>(
            checkNotNull(session).workflows.read(query)).problem)
    }

    suspend fun assertClientRetired() {
        assertClientReadDenied()
        val old = checkNotNull(session)
        assertFalse(old.isCurrent())
        assertEquals(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED, old.lifecycle?.value)
        repeat(2) { assertClientReadDenied() }
        assertEquals(0, checkNotNull(client).protectedCalls.get())
        assertEquals(LocalMasterEndpointHealth.ACTIVE, endpoint.health.value)
        assertEquals(LocalMasterServiceState.ACTIVE, checkNotNull(service).state)
        assertTrue(retirements.isEmpty())
    }

    suspend fun assertOtherAccountCurrent() {
        val tokens = checkNotNull(other)
        val current = assertIs<LocalSessionViewResult.Current>(endpoint.current(
            LocalGatewayCredentials(otherConnectionId, tokens.access)))
        assertEquals(actorB, current.view.actorId)
        assertEquals(tokens.view.sessionId, current.view.sessionId)
    }

    suspend fun businessRows(): Map<String, List<List<String>>> =
        localAuthorizationRows(checkNotNull(database)).filterKeys {
            it in setOf("operation_result", "aggregate_state", "unique_claim", "journal_envelope", "outbox_intent")
        }

    suspend fun close() {
        try { gateway?.closeAndAwait() }
        finally {
            try { auth?.close() }
            finally {
                try { intents?.close() }
                finally {
                    life.set(false)
                    ownedTokens.forEach(LocalSessionTokens::destroy)
                    client?.close()
                    passwordA.fill('\u0000'); passwordB.fill('\u0000')
                    ceremony.close()
                    // A failed/unconfirmed service close does not release key/environment custody.
                    val owner = service
                    if (owner == null) database?.close()
                    else assertEquals(LocalMasterCloseResult.CLOSED, owner.quiesceAndClose())
                    peers.close(); environmentCurrent.set(false); keys.close()
                    assertTrue(retirements.isEmpty())
                }
            }
        }
    }
}

/** Observes actual production calls and owns independent copies of the genuinely issued tokens.
 * Every auth/current/business method still runs on the same real grouped endpoint. */
private class ObservedRoomClient(private val delegate: LocalMasterClient) : LocalMasterClient by delegate {
    val protectedCalls = AtomicInteger()
    private val observationGate = java.util.concurrent.atomic.AtomicReference<RoomSessionObservationGate?>(null)
    fun holdSecondObservation(): RoomSessionObservationGate = RoomSessionObservationGate().also {
        check(observationGate.compareAndSet(null, it))
    }
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        val gate = observationGate.get() ?: return delegate.current(credentials)
        return when (gate.calls.incrementAndGet()) {
            1 -> delegate.current(credentials).also { gate.firstObserved.complete(it) }
            2 -> {
                check(observationGate.compareAndSet(gate, null))
                gate.secondEntered.complete(Unit)
                gate.allowObservation.await()
                val actual = delegate.current(credentials)
                gate.secondObserved.complete(actual)
                // Test-only delivery barrier: preserve the genuine observed result while the
                // UI caller cancels, without fabricating Current/Denied or changing authority.
                withContext(NonCancellable) { gate.allowDelivery.await() }
                actual
            }
            else -> error("Unexpected concurrent session observation in controlled schedule")
        }
    }
    private var issued: LocalSessionTokens? = null
    val original: LocalSessionTokens get() = checkNotNull(issued)
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult {
        val result = delegate.signIn(connectionId, login, password, explicitDeviceMove)
        if (result is LocalAuthenticationResult.Authenticated) {
            check(issued == null)
            issued = LocalSessionTokens(result.tokens.view,
                result.tokens.access.useBytes { OpaqueCredential(it) }, result.tokens.refresh.useBytes { OpaqueCredential(it) })
        }
        return result
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        protectedCalls.incrementAndGet(); return delegate.read(credentials, request)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        protectedCalls.incrementAndGet(); return delegate.execute(credentials, request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials,
        identity: com.ukkera.pos.recovery.OperationIdentity): LookupReply {
        protectedCalls.incrementAndGet(); return delegate.lookup(credentials, identity)
    }
    fun close() { issued?.destroy() }
}

/** Controlled scheduling only; all session results still come from the real Room endpoint. */
internal class RoomSessionObservationGate {
    val calls = AtomicInteger()
    val firstObserved = CompletableDeferred<LocalSessionViewResult>()
    val secondEntered = CompletableDeferred<Unit>()
    val allowObservation = CompletableDeferred<Unit>()
    val secondObserved = CompletableDeferred<LocalSessionViewResult>()
    val allowDelivery = CompletableDeferred<Unit>()
    fun release() { allowObservation.complete(Unit); allowDelivery.complete(Unit) }
}
