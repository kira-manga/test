package com.ukkera.pos.database

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

/** Only predeclared frozen fixture variants; never a policy activation under test. */
internal data class AuthV1FixtureSetup(
    val initiallySignedIn: Set<String> = setOf("A", "B", "M"),
    val includeUnactivatedU: Boolean = false,
    val maximumConcurrentA: Int = 1,
    val initialDeviceAdministrationForM: Boolean = false,
) {
    init {
        require(initiallySignedIn.all { it in setOf("A", "B", "M") })
        require(maximumConcurrentA in setOf(1, 2)) // I009's explicit independent two-session variant.
    }
}

/** Frozen auth-only subfixture. This is not F-TRADE, production onboarding or a writer appointment. */
internal class AuthV1Fixture(
    val name: String,
    private val path: Path,
    private val design: JsonObject,
    private val journal: AuthV1Journal,
    private val setup: AuthV1FixtureSetup = AuthV1FixtureSetup(),
) {
    private val random = SecureRandom()
    private val fixtures = design.getValue("fixtures").jsonObject
    private val handles = fixtures.getValue("F-AUTH-BASE-v1").jsonObject.getValue("identity_handles").jsonObject
    private val time = fixtures.getValue("F-TIME-QA-v1").jsonObject
    fun id(handle: String): DomainId = DomainId(handles.getValue(handle).jsonPrimitive.content)
    val scope: BranchScope = BranchScope(id("O1"), id("C1_CLOTHING"), id("B1_CLOTHING_DOWNTOWN"))
    private val scopeB2 = BranchScope(id("O1"), id("C1_CLOTHING"), id("B2_CLOTHING_MALL"))
    val actors: Map<String, DomainId> = buildMap {
        put("A", id("A_CASHIER_B1")); put("B", id("B_CASHIER_B1")); put("M", id("M_LOCAL_ADMIN_B1"))
        if (setup.includeUnactivatedU) put("U", id("U_UNACTIVATED_B1"))
    }
    private val devices = mapOf("A" to id("CLIENT_1_B1"), "B" to id("CLIENT_2_B1"), "M" to id("MASTER_B1"))
    val connections: Map<String, DomainId> = (devices.keys + listOf("UNADMITTED", "WRONG_BRANCH"))
        .associateWith { authV1EphemeralId() }
    private val descriptors = (devices.keys + "UNADMITTED").associateWith { "synthetic-key-${authV1EphemeralId().value}" }
    private val keys = AuthV1SyntheticKeyPorts(ProtectedStorageKeyReference(authV1EphemeralId()),
        ProtectedPepperReference(authV1EphemeralId()), random)
    private val peers = AuthV1SyntheticPeers()
    // No expiry boundary is exercised. New service compositions must use a new issuer ID.
    private val environment = AuthV1SyntheticEnvironment(authV1EphemeralId(), MonotonicMoment(authV1EphemeralId(), 0))
    private val admissionRequests = devices.keys.associateWith { authV1EphemeralId() }
    private val accountRequests = actors.keys.associateWith { authV1EphemeralId() }
    private val ceremony = AuthV1SyntheticProvisioning(scope, buildMap {
        devices.forEach { (name, id) -> put(admissionRequests.getValue(name), LocalProvisioningAction.ADMIT_DEVICE to id) }
        actors.forEach { (name, id) -> put(accountRequests.getValue(name), LocalProvisioningAction.PROVISION_ACCOUNT to id) }
    })
    private fun newPassword(): CharArray {
        val bytes = ByteArray(48).also(random::nextBytes)
        return try { CharArray(bytes.size) { index -> "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"[(bytes[index].toInt() and 255) % 62] } }
        finally { bytes.fill(0) }
    }
    private val passwords = actors.keys.associateWith { newPassword() }
    private val logins = actors.keys.associateWith {
        if (it == "U") LocalLoginIdentifier(design.getValue("cases").jsonArray.single { item ->
            item.jsonObject.getValue("case_id").jsonPrimitive.content == "QA-AUTH-I004"
        }.jsonObject.getValue("inputs").jsonObject.getValue("new_login").jsonPrimitive.content)
        else LocalLoginIdentifier("qa-auth-v1-$name-$it-${authV1EphemeralId().value}")
    }
    private val sessions = mutableMapOf<String, LocalSessionTokens>()
    private val ownedTokens = mutableListOf<LocalSessionTokens>()
    private var service: JvmLocalMasterService? = null
    private var retirementDiagnostic: AuthV1RetirementDiagnostic? = null
    private var closed = false
    val endpoint: LocalMasterEndpoint get() = checkNotNull(service).endpoint
    fun tokens(who: String): LocalSessionTokens = sessions.getValue(who)
    fun access(who: String, connection: String = who): LocalGatewayCredentials =
        LocalGatewayCredentials(connections.getValue(connection), tokens(who).access)
    fun refresh(who: String): LocalGatewayCredentials = LocalGatewayCredentials(connections.getValue(who), tokens(who).refresh)
    fun device(connection: String): DomainId = devices.getValue(connection)
    suspend fun syntheticPeerIsCurrent(connection: String): Boolean =
        peers.capture(connections.getValue(connection))?.isCurrent() == true
    fun login(who: String): LocalLoginIdentifier = logins.getValue(who)
    val accessLifetimeMillis: Long get() = time.getValue("access_lifetime_seconds").jsonPrimitive.long * 1000
    val workLifetimeMillis: Long get() = time.getValue("work_session_absolute_lifetime_seconds").jsonPrimitive.long * 1000

    fun recordDiagnosticCut(cut: AuthV1RetirementCut) { checkNotNull(retirementDiagnostic).markCut(cut) }

    fun access(tokens: LocalSessionTokens, connection: String): LocalGatewayCredentials =
        LocalGatewayCredentials(connections.getValue(connection), tokens.access)

    private fun retain(result: LocalAuthenticationResult): LocalAuthenticationResult {
        if (result is LocalAuthenticationResult.Authenticated) synchronized(ownedTokens) { ownedTokens.add(result.tokens) }
        return result
    }

    suspend fun signIn(who: String, connection: String = who, explicitDeviceMove: Boolean = false): LocalAuthenticationResult =
        signInWith(who, passwords.getValue(who), connection, explicitDeviceMove)

    suspend fun signInWith(who: String, password: CharArray, connection: String = who,
        explicitDeviceMove: Boolean = false): LocalAuthenticationResult =
        retain(endpoint.signIn(connections.getValue(connection), logins.getValue(who), password, explicitDeviceMove))

    suspend fun rotate(tokens: LocalSessionTokens, connection: String): LocalAuthenticationResult =
        retain(endpoint.refresh(LocalGatewayCredentials(connections.getValue(connection), tokens.refresh)))

    /** Discard at the executor return boundary, NOT a network loss, cancellation or process fault. */
    fun discardTokens(tokens: LocalSessionTokens) {
        check(synchronized(ownedTokens) { ownedTokens.remove(tokens) })
        tokens.destroy()
    }

    suspend fun <T> withFreshPassword(block: suspend (CharArray) -> T): T {
        val password = newPassword()
        return try { block(password) } finally { password.fill('\u0000') }
    }

    suspend fun start() {
        check(!Files.exists(path)) { "Auth fixture must be a new disposable file" }
        val authority = AuthorityIdentity(authV1EphemeralId(), id("MASTER_B1"), Revision(0))
        seedInertInitialTrust(authority)
        devices.forEach { (who, device) ->
            peers.install(connections.getValue(who), scope, device, descriptors.getValue(who))
        }
        peers.install(connections.getValue("UNADMITTED"), scope, id("UNADMITTED_DEVICE"), descriptors.getValue("UNADMITTED"))
        peers.install(connections.getValue("WRONG_BRANCH"), scopeB2, devices.getValue("A"), descriptors.getValue("A"))
        val parameters = PasswordHashParameters()
        val throttle = ThrottlePolicy()
        val retirementSlot = journal.prepareRetirementDiagnostic(name)
        retirementDiagnostic = retirementSlot
        service = openLocalMasterService(path, scope, authority, emptyList(), emptyList(),
            LocalMasterSecurityDependencies(keys, keys.storageReference,
                JvmLocalPasswordHasher(keys, parameters, random), keys.pepperReference,
                JvmLocalAuthenticationEntropy(random), environment, peers, 2, ceremony),
            UnqualifiedMasterRuntime,
            // The external executor owns/reaps this disposable child. No file lock/custody
            // is released and no replacement pool is opened on an uncertain retirement.
            LocalMasterRetirement { reason ->
                try { retirementSlot.record(reason) }
                finally { Runtime.getRuntime().halt(75) }
            },
            MasterClock { UtcInstant.parseCanonical(time.getValue("t0_utc").jsonPrimitive.content) },
            workers = 2, maximumQueuedRequests = 8, maximumRequestMillis = 30_000, maximumCloseMillis = 30_000)
        recordDiagnosticCut(AuthV1RetirementCut.AFTER_SERVICE_OPEN_RETURN)
        journal.event("fixture", "$name-configuration", "SYNTHETIC_PORTS_REAL_AUTH_AND_ROOM", buildJsonObject {
            put("scope", scope.authV1ScopeJson())
            put("business_kernel", "UNQUALIFIED_NOT_EXERCISED")
            put("secret_ports", "EPHEMERAL_JCA_NOT_OS_QUALIFIED")
            put("peer_ports", "SYNTHETIC_NOT_PHYSICAL_PAIRING_OR_RPC")
            put("clock", "FIXED_SYNTHETIC_MONOTONIC_NOT_TIMING_EVIDENCE")
            put("password_hasher", "JvmLocalPasswordHasher")
            put("pbkdf2_iterations_observed_configuration", parameters.iterations)
            put("kdf_hardware_qualification", false)
            put("access_lifetime_millis", time.getValue("access_lifetime_seconds").jsonPrimitive.long * 1000)
            put("work_lifetime_millis", time.getValue("work_session_absolute_lifetime_seconds").jsonPrimitive.long * 1000)
            put("throttle_free_failures_wiring_only", throttle.freeFailures)
            put("throttle_initial_delay_millis_wiring_only", throttle.initialDelayMillis)
            put("throttle_maximum_delay_millis_wiring_only", throttle.maximumDelayMillis)
            put("frozen_failed_credentials_before_lock_mapping", "UNRESOLVED_NOT_TESTED")
            put("initially_signed_in_actor_handles", JsonArray(setup.initiallySignedIn.sorted().map(::JsonPrimitive)))
            put("initial_unactivated_U", setup.includeUnactivatedU)
            put("initial_A_maximum_concurrent_sessions", setup.maximumConcurrentA)
            put("two_session_policy_activation_under_test", false)
            put("initial_M_device_administration_for_CLIENT_1", setup.initialDeviceAdministrationForM)
        })
        for ((who, device) in devices) {
            val admission = LocalDeviceAdmission(scope, device, Revision(0), true, descriptors.getValue(who), null, emptySet())
            val result = endpoint.admitDevice(admissionRequests.getValue(who), admission)
            journal.control("$name-admit-$who", result is LocalCredentialAdministrationResult.Applied,
                buildJsonObject { put("result", result.authV1Code()); put("request_id", admissionRequests.getValue(who).value) })
        }
        for ((who, actor) in actors) {
            val grants = if (who == "M") buildList {
                add(PermissionGrant(Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER), scope,
                    GrantEffect.ALLOW, namedSubjectId = actor))
                if (setup.initialDeviceAdministrationForM) add(PermissionGrant(
                    Permission(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER), scope,
                    GrantEffect.ALLOW, namedSubjectId = actor, targetIds = setOf(devices.getValue("A"))))
            }
            else listOf(SecurityAction.ORDER_READ, SecurityAction.CASH_READ).map { action ->
                PermissionGrant(Permission(action, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
                    ownerAccess = OwnerAccess.OWN_ONLY, namedSubjectId = actor)
            }
            val definition = LocalAccountProvisioning(actor, logins.getValue(who),
                ActivatedLocalPolicy(scope, actor, Revision(0), grants,
                    LocalSessionPolicy(if (who == "A") setup.maximumConcurrentA else 1), throttle),
                LocalTokenPolicy(accessLifetimeMillis, workLifetimeMillis),
                devices.values.toSet()) // Complete admitted set, never an account-preferred subset.
            val result = endpoint.provisionAccount(accountRequests.getValue(who), definition,
                if (who == "U") null else passwords.getValue(who))
            journal.control("$name-provision-$who", result is LocalCredentialAdministrationResult.Applied,
                buildJsonObject { put("result", result.authV1Code()); put("request_id", accountRequests.getValue(who).value) })
        }
        ceremony.close()
        val provisioned = snapshot("before-first-login")
        journal.control("$name-no-seeded-sessions", provisioned.accounts.values.all { it.state.sessions.isEmpty() },
            buildJsonObject { put("initial_ceremony_closed", true) })
        for (who in setup.initiallySignedIn) {
            val result = signIn(who)
            journal.control("$name-login-$who", result is LocalAuthenticationResult.Authenticated,
                buildJsonObject { put("result", result.authV1Code()) })
            sessions[who] = (result as LocalAuthenticationResult.Authenticated).tokens
            currentControl(who, "initial")
        }
    }

    /** Lead-authorized initial trust only: no security/session/grant/stock/money rows. */
    private suspend fun seedInertInitialTrust(authority: AuthorityIdentity) {
        val database = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize))
        try {
            database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    check(database.facts().branch(scope.storageKey()) == null)
                    database.facts().insertBranch(BranchRow(scope.storageKey(), authority.assignmentId.value,
                        authority.deviceId.value, authority.epoch.value, 0, 0, "0".repeat(64), false,
                        Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(scope.branchId))))
                }
            }
        } finally { database.close() }
        journal.event("fixture", "$name-initial-trust", "SYNTHETIC_INERT_BRANCH_ONLY", buildJsonObject {
            put("direct_seed_tables", JsonArray(listOf(JsonPrimitive("branch_authority"))))
            put("writable", false)
            put("security_rows_seeded", false)
            put("business_rows_seeded", false)
            put("production_onboarding_covered", false)
        })
    }

    suspend fun currentControl(who: String, label: String) {
        val result = endpoint.current(access(who))
        val view = (result as? LocalSessionViewResult.Current)?.view
        journal.control("$name-$label-current-$who", view != null && view.actorId == actors.getValue(who) &&
            view.scope == scope && view.deviceId == devices.getValue(who) && view.sessionId == tokens(who).view.sessionId,
            buildJsonObject { put("result", result.authV1Code()); put("actor_handle", who) })
    }

    suspend fun tryValidPasswordOnUnadmittedPeer(): LocalAuthenticationResult =
        endpoint.signIn(connections.getValue("UNADMITTED"), logins.getValue("A"), passwords.getValue("A"))

    /** An authorized executor inspection, NOT a new client read endpoint or an auth replacement. */
    suspend fun snapshot(label: String): AuthV1Snapshot {
        val database = buildMasterRoom(Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize))
        val result = try {
            val store = RoomLocalSecurityStore(database, keys, keys.storageReference)
            database.useWriterConnection { connection ->
                requireDurableWriter(connection)
                store.transaction { transaction ->
                    val accounts = actors.mapValues { (_, actor) ->
                        val record = checkNotNull(transaction.readProtectedAccount(scope, actor))
                        val policy = checkNotNull(transaction.readPolicy(scope, actor))
                        val configuration = checkNotNull(transaction.readAuthConfiguration(scope, actor))
                        AuthV1AccountFacts(record.state, record.credentialRevision, record.credential != null,
                            policy, configuration, record.state.sessions.associate { session ->
                                session.sessionId to AuthV1IssuanceFacts(transaction.readIssuance(scope, session.sessionId),
                                    transaction.readRefresh(scope, session.sessionId))
                            })
                    }
                    val admissions = devices.mapValues { (_, device) -> checkNotNull(transaction.readAdmission(scope, device)) }
                    val ids = connection.usePrepared("SELECT CASE WHEN typeof(eventId)='text' AND " +
                        "length(CAST(eventId AS BLOB))=36 THEN eventId ELSE '' END " +
                        "FROM protected_local_auth_event WHERE scope=? ORDER BY eventId LIMIT 257") { statement ->
                        statement.bindText(1, scope.storageKey())
                        buildList { while (statement.step()) add(DomainId(statement.getText(0))) }
                    }
                    check(ids.size <= 256) { "Auth-only fixture observation event budget exceeded" }
                    val events = ids.map { checkNotNull(transaction.readAuthEvent(scope, it)) }
                    AuthV1Snapshot(accounts, events, admissions)
                }
            }
        } finally { database.close() }
        journal.event("snapshot", "$name-$label", "FRESH_ROOM_PROTECTED_STORE_READ_COMMITTED", result.json())
        return result
    }

    suspend fun closeService() {
        if (closed) return
        val current = service ?: return
        journal.event("diagnostic", "$name-service-close", "BEFORE_QUIESCE_AND_CLOSE", buildJsonObject {
            put("diagnostic_only", true)
        })
        recordDiagnosticCut(AuthV1RetirementCut.BEFORE_QUIESCE_AND_CLOSE)
        val result = current.quiesceAndClose()
        recordDiagnosticCut(AuthV1RetirementCut.AFTER_QUIESCE_AND_CLOSE_RETURN)
        try {
            journal.event("diagnostic", "$name-service-close", "AFTER_QUIESCE_AND_CLOSE", buildJsonObject {
                put("diagnostic_only", true)
                put("result", result.name)
            })
        } finally {
            if (result != LocalMasterCloseResult.CLOSED) Runtime.getRuntime().halt(75)
        }
        closed = true
        journal.event("fixture_close", name, "REAL_GROUPED_SERVICE_CLOSED", buildJsonObject {
            put("process_restart", false)
            put("close_returned_before_fresh_inspection", true)
        })
    }

    suspend fun dispose() {
        closeService()
        val retained = synchronized(ownedTokens) { ownedTokens.toList().also { ownedTokens.clear() } }
        retained.forEach(LocalSessionTokens::destroy)
        sessions.clear()
        passwords.values.forEach { it.fill('\u0000') }
        ceremony.close()
        peers.close()
        environment.close()
        keys.close()
    }
}

internal class AuthV1AccountFacts(
    val state: LocalAccountSessionState,
    val credentialRevision: Revision,
    val credentialPresent: Boolean,
    val policy: ActivatedLocalPolicy,
    val configuration: LocalAuthConfiguration,
    val issuances: Map<DomainId, AuthV1IssuanceFacts>,
) {
    val policyRevision: Revision get() = policy.revision
    val grantActions: List<String> get() = policy.grants.map { it.permission.action.name }.sorted()
    fun json(): JsonObject = buildJsonObject {
        put("actor_id", state.actorId.value)
        put("scope", state.scope.authV1ScopeJson())
        put("enabled", state.enabled)
        put("revision", state.revision.value)
        put("generation", state.generation.value)
        put("credential_present", credentialPresent)
        put("credential_revision", credentialRevision.value)
        put("policy_revision", policyRevision.value)
        put("grant_action_names", JsonArray(grantActions.map(::JsonPrimitive)))
        put("session_policy_maximum_concurrent", policy.sessionPolicy.maximumConcurrentSessions)
        put("configuration_revision", configuration.revision.value)
        put("access_lifetime_millis_configuration", configuration.tokens.accessLifetimeMillis)
        put("work_lifetime_millis_configuration", configuration.tokens.workLifetimeMillis)
        put("sessions", JsonArray(state.sessions.map { session -> buildJsonObject {
            put("session_id", session.sessionId.value)
            put("actor_id", session.actorId.value)
            put("device_id", session.deviceId.value)
            put("generation", session.generation.value)
            put("status", session.status.name)
            put("purpose", session.authenticationPurpose.name)
            put("protected_issuance", issuances.getValue(session.sessionId).json())
        } }))
    }
}

/** Hash objects remain memory-only. No bearer/hash/password/verifier is serialized to evidence. */
internal class AuthV1IssuanceFacts(val access: LocalAccessIssuance?, val refresh: LocalRefreshIssuance?) {
    fun json(): JsonObject = buildJsonObject {
        put("access_record_present", access != null)
        put("refresh_record_present", refresh != null)
        access?.let {
            put("access_session_id", it.sessionId.value)
            put("access_actor_id", it.actorId.value)
            put("access_scope", it.scope.authV1ScopeJson())
            put("access_device_id", it.deviceId.value)
            put("access_generation", it.generation.value)
            put("access_credential_revision", it.credentialRevisionAtIssue.value)
            put("access_admission_revision", it.admissionRevisionAtIssue.value)
            put("access_issuer_context_id", it.issuerContextId.value)
            put("access_issued_at", it.issuedAt.authV1MomentJson())
            put("access_expires_at_elapsed_millis", it.expiresAtElapsedMillis)
        }
        refresh?.let {
            put("refresh_session_id", it.sessionId.value)
            put("refresh_actor_id", it.actorId.value)
            put("refresh_scope", it.scope.authV1ScopeJson())
            put("refresh_device_id", it.deviceId.value)
            put("refresh_generation", it.generation.value)
            put("refresh_credential_revision", it.credentialRevisionAtIssue.value)
            put("refresh_admission_revision", it.admissionRevisionAtIssue.value)
            put("refresh_configuration_revision", it.configurationRevisionAtIssue.value)
            put("refresh_issuer_context_id", it.issuerContextId.value)
            put("refresh_issued_at", it.issuedAt.authV1MomentJson())
            put("original_session_id", it.originalSessionId.value)
            put("work_started_at", it.workStartedAt.authV1MomentJson())
            put("work_lifetime_millis", it.workLifetimeMillis)
            put("work_expires_at_elapsed_millis", it.expiresAtElapsedMillis)
        }
    }
}

internal class AuthV1Snapshot(
    val accounts: Map<String, AuthV1AccountFacts>,
    val events: List<LocalAuthEvent>,
    val admissions: Map<String, LocalDeviceAdmission>,
) {
    fun json(): JsonObject = buildJsonObject {
        put("accounts", JsonObject(accounts.mapValues { it.value.json() }))
        put("device_admissions", JsonObject(admissions.mapValues { (_, admission) -> buildJsonObject {
            put("device_id", admission.deviceId.value)
            put("scope", admission.scope.authV1ScopeJson())
            put("revision", admission.revision.value)
            put("admitted", admission.admitted)
        } }))
        put("auth_events", JsonArray(events.map { event -> buildJsonObject {
            put("event_id", event.eventId.value)
            put("kind", event.kind.name)
            put("target_id", event.targetId.value)
            put("operator_id", event.operatorId?.value?.let(::JsonPrimitive) ?: JsonNull)
            put("session_id", event.sessionId?.value?.let(::JsonPrimitive) ?: JsonNull)
            put("account_revision", event.accountRevision?.value?.let(::JsonPrimitive) ?: JsonNull)
        } }))
    }
}

internal fun MonotonicMoment.authV1MomentJson(): JsonObject = buildJsonObject {
    put("boot_id", bootId.value)
    put("elapsed_millis", elapsedMillis)
}

internal fun BranchScope.authV1ScopeJson(): JsonObject = buildJsonObject {
    put("organization_id", organizationId.value)
    put("company_id", companyId.value)
    put("branch_id", branchId.value)
}

internal fun LocalAuthenticationResult.authV1Code(): String = when (this) {
    is LocalAuthenticationResult.Authenticated -> "AUTHENTICATED"
    is LocalAuthenticationResult.Denied -> "DENIED_${reason.name}"
    LocalAuthenticationResult.Unavailable -> "UNAVAILABLE"
    else -> "OTHER_NONACCEPTING_${javaClass.simpleName}"
}
internal fun LocalSessionViewResult.authV1Code(): String = when (this) {
    is LocalSessionViewResult.Current -> "CURRENT"
    LocalSessionViewResult.Denied -> "DENIED"
    LocalSessionViewResult.Unavailable -> "UNAVAILABLE"
    LocalSessionViewResult.Busy -> "BUSY"
    else -> "OTHER_NONACCEPTING_${javaClass.simpleName}"
}
internal fun LocalCredentialAdministrationResult.authV1Code(): String = when (this) {
    is LocalCredentialAdministrationResult.Applied -> "APPLIED"
    LocalCredentialAdministrationResult.Denied -> "DENIED"
    LocalCredentialAdministrationResult.Unavailable -> "UNAVAILABLE"
    LocalCredentialAdministrationResult.Busy -> "BUSY"
    else -> "OTHER_NONACCEPTING_${javaClass.simpleName}"
}
