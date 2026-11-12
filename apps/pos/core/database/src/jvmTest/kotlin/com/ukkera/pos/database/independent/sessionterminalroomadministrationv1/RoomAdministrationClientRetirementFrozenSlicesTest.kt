package com.ukkera.pos.database

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlin.test.*

/** Implementer wiring of existing STC-S10 and QA-AUTH-I013/I014 refusal/retirement
 * observables. Real grouped Room administration, password hashing, authentication,
 * both public client roots and encrypted journals; existing auth-v1 synthetic
 * provisioning/keys/peers/clocks. No native authority, financial fixture or RPC. */
class RoomAdministrationClientRetirementFrozenSlicesTest {
    @Test fun stcS10_scopedAdministrationDenialDoesNotEndTheCurrentEmployee() = withFixture { f ->
        val retained = f.employee.observeOriginals()
        assertEquals(LocalAdministrationReadResult.Denied, f.employee.session.administration.read(f.query))
        f.assertCreationReviewDeniedWithoutStoredChanges()
        assertTrue(f.employee.session.isCurrent())
        assertEquals(LocalSessionLifecycle.ACTIVE, f.employee.session.lifecycle?.value)
        assertIs<LocalSessionViewResult.Current>(f.auth.endpoint.current(f.employee.access()))
        assertEquals(retained, f.employee.observeOriginals())
        f.assertIndependentContextsCurrent()
        assertEquals(0, f.employee.client.protectedCalls.get())
    }

    @Test fun stcS10_authI014ActualDisableRetiresOldRootAndRetainsOriginals() = withFixture { f ->
        f.applyAndObserve(LocalAdministrationAction.DISABLE_ACCOUNT)
    }

    @Test fun stcS10_authI013ActualCredentialResetRetiresOldRootAndRetainsOriginals() = withFixture { f ->
        f.applyAndObserve(LocalAdministrationAction.RESET_CREDENTIAL)
    }

    /** QA-AUTH-I014-A04's independent newer-resolution variant, bounded to stored
     * administration/authentication and retained original identities. No F-TRADE/native/RPC. */
    @Test fun authI014AuthorizedNewerResolutionRequiresFreshLoginAndPreservesHistory() = withFixture { f ->
        f.reactivateWithNewerResolution()
    }

    /** Existing QA-AUTH-I013 activation observables for frozen U plus I014's explicit
     * newer resolution. Controlled stored-service slice; no financial/native/RPC claim. */
    @Test fun authI013DelegatedFirstCredentialActivatesDisabledUWithoutChangingPriorActors() =
        withFixture(includeUnactivatedU = true) { f -> f.activateCredentialFreeTarget() }

    private fun withFixture(includeUnactivatedU: Boolean = false,
        body: suspend (StoredAdministrationFixture) -> Unit) = runBlocking {
        val fixture = StoredAdministrationFixture(includeUnactivatedU)
        try { fixture.start(); body(fixture) }
        finally { withContext(NonCancellable) { fixture.close() } }
    }
}

private class StoredAdministrationFixture(includeUnactivatedU: Boolean = false) {
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .first { Files.isRegularFile(it.resolve("qa/pos/freezes/auth-v1/cases.json")) }
    private val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val directory = Files.createDirectory(Files.createDirectories(
        Path.of("build", "room-administration-client-slices").toAbsolutePath().normalize())
        .resolve(DomainId.random().value), permissions)
    private val cases = Files.readAllBytes(root.resolve("qa/pos/freezes/auth-v1/cases.json"))
    private val wiring = Files.readAllBytes(root.resolve("qa/pos/wiring/auth-v1/wiring-v3.json"))
    private val journal = AuthV1Journal(directory, DomainId.random().value.replace("-", ""),
        Json.parseToJsonElement(wiring.decodeToString()).jsonObject)
    val auth = AuthV1Fixture("stored-administration", directory.resolve("master.db"),
        Json.parseToJsonElement(cases.decodeToString()).jsonObject, journal,
        AuthV1FixtureSetup(initiallySignedIn = setOf("B"), includeUnactivatedU = includeUnactivatedU))
    private val clients = mutableListOf<StoredAdministrationClient>()
    lateinit var employee: StoredAdministrationClient
    private lateinit var administrator: StoredAdministrationClient
    val query get() = LocalAdministrationQuery(LocalAdministrationDirectory.ACCOUNTS,
        targetId = auth.actors.getValue("A"))

    suspend fun start() {
        check(authV1Sha256(cases) == "e6fa22b836f7fd2c2face14d83544edd8554007faee6214c0ec36492a3c96100")
        check(authV1Sha256(wiring) == "9c6508917316935a42882019396c24a005434abd7b7f711e5ce4a624815236be")
        auth.start()
        employee = openClient("A")
        administrator = openClient("M")
    }

    private suspend fun openClient(who: String): StoredAdministrationClient {
        val client = StoredAdministrationClient(auth, who, Files.createDirectory(directory.resolve(who), permissions)) { metadata ->
            journal.event("credential_observation", "$who-original-retention", "ACTUAL_ENCRYPTED_READER", metadata)
        }.also { clients.add(it) }
        client.start()
        return client
    }

    /** Additional endpoint binding of the SAME frozen ordinary-cashier/scoped-denial
     * and invalidated-session observables (auth-v1 I001-A04/I013-A02/I014-A01; STC-S10).
     * The proposed B definition is only caller DATA. This does not qualify a successful
     * creation review, delegated policy containment or any native creation/history path. */
    suspend fun assertCreationReviewDeniedWithoutStoredChanges() {
        val before = auth.snapshot("before-denied-creation-review")
        val target = before.accounts.getValue("B")
        val definition = LocalAccountProvisioning(target.state.actorId, target.configuration.login,
            target.policy, target.configuration.tokens,
            before.admissions.values.filter { it.admitted }.map { it.deviceId }.toSet())
        val policy = LocalEffectivePolicyCodec.encodePolicy(definition.policy)
        val configuration = LocalAuthenticationCodec.encodeConfiguration(target.configuration)
        val requested = try {
            LocalEmployeeEnrollmentDefinition.capture(DomainId.random(), definition, target.credentialPresent,
                policy, configuration, before.admissions.values.toList())
        } finally { policy.fill(0); configuration.fill(0) }
        val original = LocalDelegatedEmployeeCreationV1.capture(auth.actors.getValue("A"), requested)
        assertEquals(LocalEmployeeCreationReviewV1.Denied,
            auth.endpoint.reviewEmployeeCreation(employee.access(), original))
        val after = auth.snapshot("after-denied-creation-review")
        for ((who, facts) in before.accounts) assertTrue(authV1SameObservedAccount(facts, after.accounts.getValue(who)))
        // Preserve every prior immutable event; the freeze permits additional safe denial
        // auditing and does not prescribe its cardinality. Account/session checks above
        // independently reject any newly issued authority.
        val retainedEvents = after.events.associate { it.eventId to observedDigest(LocalAuthenticationCodec.encodeEvent(it)) }
        for (event in before.events) assertEquals(observedDigest(LocalAuthenticationCodec.encodeEvent(event)), retainedEvents[event.eventId])
        assertEquals(before.admissions.mapValues { observedDigest(ProtectedLocalSecurityCodec.encodeAdmission(it.value)) },
            after.admissions.mapValues { observedDigest(ProtectedLocalSecurityCodec.encodeAdmission(it.value)) })
    }

    suspend fun applyAndObserve(action: LocalAdministrationAction) {
        val baseline = auth.snapshot("before-${action.name.lowercase()}")
        val retained = employee.observeOriginals()
        val detail = assertIs<LocalAdministrationReadResult.Available>(administrator.session.administration.read(query))
            .view.accountDetail ?: error("Actual protected target detail unavailable")
        val command = LocalAdministrationCommand(DomainId.random(), action, detail.account.actorId,
            detail.account.accountRevision, expectedCredentialRevision =
                detail.account.credentialRevision.takeIf { action == LocalAdministrationAction.RESET_CREDENTIAL })
        val prepared = assertIs<LocalAdministrationResult.Known>(administrator.session.administration.prepare(command))
        assertEquals(command, prepared.operation.command)
        assertEquals(LocalAdministrationPhase.PREPARED, prepared.operation.phase)
        val applied = if (action == LocalAdministrationAction.RESET_CREDENTIAL) auth.withFreshPassword { password ->
            administrator.session.administration.submit(command.requestId, password.copyOf())
        } else administrator.session.administration.submit(command.requestId)
        val submitted = assertIs<LocalAdministrationResult.Known>(applied).operation
        assertEquals(command, submitted.command)
        assertEquals(LocalAdministrationPhase.APPLIED, submitted.phase)
        // Submit retains the observed event ID; the exact original lookup obtains
        // the full receipt. No repeat submission or replacement identity is used.
        val known = assertIs<LocalAdministrationResult.Known>(
            administrator.session.administration.lookup(command.requestId)).operation
        assertEquals(command, known.command)
        assertEquals(LocalAdministrationPhase.APPLIED, known.phase)
        assertEquals(command.requestId, assertNotNull(known.receipt).requestId)
        assertEquals(known, assertIs<LocalAdministrationPendingResult.Available>(
            administrator.session.administration.pending()).operation)

        assertEquals(LocalAdministrationReadResult.Denied, employee.session.administration.read(query))
        // Observe local lifecycle before any extra Current can conceal a missed terminal signal.
        assertFalse(employee.session.isCurrent())
        assertEquals(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED, employee.session.lifecycle?.value)
        assertEquals(retained, employee.observeOriginals())
        assertEquals(LocalSessionViewResult.Denied, auth.endpoint.current(employee.access()))
        assertCreationReviewDeniedWithoutStoredChanges()
        assertIs<LocalAuthenticationResult.Denied>(auth.endpoint.refresh(employee.refresh()))
        repeat(2) { assertEquals(LocalAdministrationReadResult.Denied, employee.session.administration.read(query)) }
        assertEquals(0, employee.client.protectedCalls.get())
        assertIndependentContextsCurrent()

        val after = auth.snapshot("after-${action.name.lowercase()}")
        assertTrue(authV1SameObservedAccount(baseline.accounts.getValue("B"), after.accounts.getValue("B")))
        assertTrue(authV1SamePolicy(baseline.accounts.getValue("A").policy, after.accounts.getValue("A").policy))
        val target = after.accounts.getValue("A")
        assertTrue(target.state.sessions.none { it.status == SessionStatus.ACTIVE })
        when (action) {
            LocalAdministrationAction.DISABLE_ACCOUNT -> assertFalse(target.state.enabled)
            LocalAdministrationAction.RESET_CREDENTIAL -> {
                assertTrue(target.state.enabled && target.credentialPresent)
                assertNotEquals(baseline.accounts.getValue("A").credentialRevision, target.credentialRevision)
            }
            else -> error("Only independently frozen disable/reset variants are assigned")
        }
        assertEquals(1, after.events.count { it.eventId == command.requestId })
    }

    /** Wiring of QA-AUTH-I014 A01/A04 and its independent newer-resolution action.
     * The original three methods and all their assertions remain unchanged. */
    suspend fun reactivateWithNewerResolution() {
        applyAndObserve(LocalAdministrationAction.DISABLE_ACCOUNT)
        val retained = employee.observeOriginals()
        val disabled = auth.snapshot("resolution-disabled-baseline")
        val before = disabled.accounts.getValue("A")
        val detail = assertIs<LocalAdministrationReadResult.Available>(administrator.session.administration.read(query))
            .view.accountDetail ?: error("Current protected target detail unavailable")
        val command = LocalAdministrationCommand(DomainId.random(), LocalAdministrationAction.REACTIVATE_ACCOUNT,
            detail.account.actorId, detail.account.accountRevision,
            reactivationRevisions = LocalAdministrationReactivationRevisions(detail.account.credentialRevision,
                detail.account.policyRevision, detail.account.configurationRevision))
        // The frozen ordinary B fixture has no account administration authority.
        assertEquals(LocalCredentialAdministrationResult.Denied, auth.endpoint.administer(auth.access("B"), command))
        val denied = auth.snapshot("ordinary-resolution-refused")
        assertTrue(authV1SameObservedAccount(before, denied.accounts.getValue("A")))
        // A prior account version is not a newer explicit resolution.
        assertEquals(LocalCredentialAdministrationResult.Denied, auth.endpoint.administer(administrator.access(),
            command.copy(requestId = DomainId.random(), expectedRevision = Revision(command.expectedRevision.value - 1))))
        assertTrue(authV1SameObservedAccount(before, auth.snapshot("stale-resolution-refused").accounts.getValue("A")))

        val previous = assertNotNull(assertIs<LocalAdministrationPendingResult.Available>(
            administrator.session.administration.pending()).operation)
        assertTrue(administrator.session.administration.acknowledge(previous.command.requestId))
        val prepared = assertIs<LocalAdministrationResult.Known>(administrator.session.administration.prepare(command)).operation
        assertEquals(command, prepared.command)
        assertEquals(LocalAdministrationPhase.PREPARED, prepared.phase)
        assertEquals(LocalAdministrationPhase.APPLIED, assertIs<LocalAdministrationResult.Known>(
            administrator.session.administration.submit(command.requestId)).operation.phase)
        val known = assertIs<LocalAdministrationResult.Known>(administrator.session.administration.lookup(command.requestId)).operation
        assertEquals(command, known.command)
        val receipt = assertNotNull(known.receipt)
        assertEquals(command.requestId, receipt.requestId)
        assertEquals(auth.actors.getValue("M"), receipt.originalOperatorId)
        assertEquals(LocalAdministrationPhase.APPLIED, known.phase)

        val resolved = auth.snapshot("authorized-newer-resolution")
        val after = resolved.accounts.getValue("A")
        assertTrue(after.state.enabled)
        assertEquals(before.state.actorId, after.state.actorId)
        assertEquals(before.state.sessions, after.state.sessions)
        assertEquals(before.credentialRevision, after.credentialRevision)
        assertEquals(before.credentialPresent, after.credentialPresent)
        assertTrue(authV1SamePolicy(before.policy, after.policy))
        assertEquals(observedDigest(LocalAuthenticationCodec.encodeConfiguration(before.configuration)),
            observedDigest(LocalAuthenticationCodec.encodeConfiguration(after.configuration)))
        assertEquals(before.issuances.keys, after.issuances.keys)
        for ((id, original) in before.issuances) {
            val current = after.issuances.getValue(id)
            assertEquals(original.json(), current.json())
            assertEquals(original.access?.tokenHash, current.access?.tokenHash)
            assertEquals(original.refresh?.tokenHash, current.refresh?.tokenHash)
        }
        val priorEvents = disabled.events.associate { it.eventId to observedDigest(LocalAuthenticationCodec.encodeEvent(it)) }
        val currentEvents = resolved.events.associate { it.eventId to observedDigest(LocalAuthenticationCodec.encodeEvent(it)) }
        assertTrue(priorEvents.all { (id, digest) -> currentEvents[id] == digest })
        val event = resolved.events.single { it.eventId == command.requestId }
        assertEquals(LocalAuthEventKind.ACCOUNT_REACTIVATED, event.kind)
        assertEquals(auth.actors.getValue("A"), event.targetId)
        assertEquals(auth.actors.getValue("M"), event.operatorId)
        assertTrue(authV1SameObservedAccount(disabled.accounts.getValue("B"), resolved.accounts.getValue("B")))
        // Re-enabling the account does not renew an old token, private root or pending journal.
        assertEquals(LocalSessionViewResult.Denied, auth.endpoint.current(employee.access()))
        assertIs<LocalAuthenticationResult.Denied>(auth.endpoint.refresh(employee.refresh()))
        assertFalse(employee.session.isCurrent())
        assertEquals(retained, employee.observeOriginals())
        assertIs<LocalAuthenticationResult.Authenticated>(auth.signIn("A"))
        assertIndependentContextsCurrent()
        // Recovery observes the SAME winning original before today's changed target state.
        val beforeRecovery = auth.snapshot("before-resolution-original-recovery")
        assertIs<LocalCredentialAdministrationResult.Applied>(auth.endpoint.administer(administrator.access(), command))
        assertIs<LocalAdministrationStatus.Applied>(auth.endpoint.cancelAdministration(administrator.access(), command))
        val recovered = auth.snapshot("after-resolution-original-recovery")
        assertTrue(authV1SameObservedAccount(beforeRecovery.accounts.getValue("A"), recovered.accounts.getValue("A")))
        assertEquals(beforeRecovery.events.associate { it.eventId to observedDigest(LocalAuthenticationCodec.encodeEvent(it)) },
            recovered.events.associate { it.eventId to observedDigest(LocalAuthenticationCodec.encodeEvent(it)) })
    }

    private fun observedDigest(bytes: ByteArray): String = try { authV1Sha256(bytes) } finally { bytes.fill(0) }

    /** Frozen U is initially credential-free. Disable via real delegated administration,
     * then exercise first credential plus explicit enablement with unchanged original IDs. */
    suspend fun activateCredentialFreeTarget() {
        val originalA = employee.observeOriginals()
        val targetId = auth.actors.getValue("U")
        val targetQuery = LocalAdministrationQuery(LocalAdministrationDirectory.ACCOUNTS, targetId = targetId)
        assertIs<LocalAuthenticationResult.Denied>(auth.signIn("U", connection = "A"))
        val initial = assertNotNull(assertIs<LocalAdministrationReadResult.Available>(
            administrator.session.administration.read(targetQuery)).view.accountDetail)
        assertFalse(initial.account.credentialPresent)
        val disable = LocalAdministrationCommand(DomainId.random(), LocalAdministrationAction.DISABLE_ACCOUNT,
            targetId, initial.account.accountRevision)
        assertIs<LocalAdministrationResult.Known>(administrator.session.administration.prepare(disable))
        assertEquals(LocalAdministrationPhase.APPLIED, assertIs<LocalAdministrationResult.Known>(
            administrator.session.administration.submit(disable.requestId)).operation.phase)
        assertEquals(LocalAdministrationPhase.APPLIED, assertIs<LocalAdministrationResult.Known>(
            administrator.session.administration.lookup(disable.requestId)).operation.phase)
        assertTrue(administrator.session.administration.acknowledge(disable.requestId))
        val disabled = auth.snapshot("credential-free-U-disabled")
        val before = disabled.accounts.getValue("U")
        assertFalse(before.state.enabled)
        assertFalse(before.credentialPresent)
        val detail = assertNotNull(assertIs<LocalAdministrationReadResult.Available>(
            administrator.session.administration.read(targetQuery)).view.accountDetail)
        val command = LocalAdministrationCommand(DomainId.random(), LocalAdministrationAction.ACTIVATE_ACCOUNT,
            targetId, detail.account.accountRevision, expectedCredentialRevision = detail.account.credentialRevision,
            activationRevisions = LocalAdministrationActivationRevisions(detail.account.policyRevision, detail.account.configurationRevision))
        auth.withFreshPassword { password ->
            assertIs<LocalCredentialAdministrationResult.Denied>(auth.endpoint.administer(auth.access("B"), command, password))
            assertTrue(authV1SameObservedAccount(before, auth.snapshot("ordinary-B-refused-U-activation").accounts.getValue("U")))
            assertIs<LocalCredentialAdministrationResult.Denied>(auth.endpoint.administer(administrator.access(),
                command.copy(requestId = DomainId.random(), expectedRevision = initial.account.accountRevision), password))
            assertTrue(authV1SameObservedAccount(before, auth.snapshot("stale-U-activation-refused").accounts.getValue("U")))
            val prepared = assertIs<LocalAdministrationResult.Known>(administrator.session.administration.prepare(command)).operation
            assertEquals(command, prepared.command)
            assertEquals(LocalAdministrationPhase.PREPARED, prepared.phase)
            assertEquals(LocalAdministrationPhase.APPLIED, assertIs<LocalAdministrationResult.Known>(
                administrator.session.administration.submit(command.requestId, password.copyOf())).operation.phase)
            val known = assertIs<LocalAdministrationResult.Known>(administrator.session.administration.lookup(command.requestId)).operation
            assertEquals(command, known.command)
            assertEquals(command.requestId, assertNotNull(known.receipt).requestId)
            assertEquals(auth.actors.getValue("M"), known.receipt!!.originalOperatorId)
            val committed = auth.snapshot("U-first-credential-committed")
            val after = committed.accounts.getValue("U")
            assertTrue(after.state.enabled && after.credentialPresent)
            assertEquals(before.state.actorId, after.state.actorId)
            assertEquals(before.state.sessions, after.state.sessions)
            assertEquals(before.issuances.keys, after.issuances.keys)
            assertTrue(authV1SamePolicy(before.policy, after.policy))
            assertEquals(observedDigest(LocalAuthenticationCodec.encodeConfiguration(before.configuration)),
                observedDigest(LocalAuthenticationCodec.encodeConfiguration(after.configuration)))
            for (who in listOf("A", "B", "M"))
                assertTrue(authV1SameObservedAccount(disabled.accounts.getValue(who), committed.accounts.getValue(who)))
            val priorEvents = disabled.events.associate { it.eventId to observedDigest(LocalAuthenticationCodec.encodeEvent(it)) }
            val events = committed.events.associate { it.eventId to observedDigest(LocalAuthenticationCodec.encodeEvent(it)) }
            assertTrue(priorEvents.all { (id, digest) -> events[id] == digest })
            val event = committed.events.single { it.eventId == command.requestId }
            assertEquals(LocalAuthEventKind.ACCOUNT_ACTIVATED, event.kind)
            assertEquals(targetId, event.targetId)
            assertEquals(auth.actors.getValue("M"), event.operatorId)
            assertEquals(originalA, employee.observeOriginals())
            assertTrue(employee.session.isCurrent())
            assertIndependentContextsCurrent()
            assertIs<LocalAuthenticationResult.Denied>(auth.signIn("U", connection = "A"))
            assertIs<LocalAuthenticationResult.Authenticated>(auth.signInWith("U", password, connection = "A"))
            val beforeRecovery = auth.snapshot("before-U-original-recovery")
            assertEquals(LocalAdministrationPhase.APPLIED, assertIs<LocalAdministrationResult.Known>(
                administrator.session.administration.submit(command.requestId, password.copyOf())).operation.phase)
            assertIs<LocalAdministrationStatus.Applied>(auth.endpoint.cancelAdministration(administrator.access(), command))
            val recovered = auth.snapshot("after-U-original-recovery")
            assertTrue(authV1SameObservedAccount(beforeRecovery.accounts.getValue("U"), recovered.accounts.getValue("U")))
            assertEquals(beforeRecovery.events.associate { it.eventId to observedDigest(LocalAuthenticationCodec.encodeEvent(it)) },
                recovered.events.associate { it.eventId to observedDigest(LocalAuthenticationCodec.encodeEvent(it)) })
        }
    }

    suspend fun assertIndependentContextsCurrent() {
        auth.currentControl("B", "unrelated-after-administration")
        assertIs<LocalSessionViewResult.Current>(auth.endpoint.current(administrator.access()))
        assertTrue(administrator.session.isCurrent())
        assertEquals(LocalMasterEndpointHealth.ACTIVE, auth.endpoint.health.value)
        assertEquals(0, administrator.client.protectedCalls.get())
    }

    suspend fun close() {
        try { clients.asReversed().forEach { it.close() } }
        finally {
            try { auth.dispose() }
            finally { journal.close() }
        }
    }
}

private class StoredAdministrationClient(private val auth: AuthV1Fixture, private val who: String,
    private val directory: Path, private val observeCleanup: (JsonObject) -> Unit) {
    private val life = AtomicBoolean(true)
    private val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    val client = StoredAdministrationObservedClient(auth.endpoint)
    private var credentialJournal: JvmEncryptedClientCredentialJournal? = null
    private var intentJournal: JvmEncryptedClientIntentJournal? = null
    private var gateway: LocalEndpointBranchGateway? = null
    lateinit var session: BranchUiSession

    suspend fun start() {
        val identity = LocalClientIdentity(auth.scope, auth.actors.getValue(who), auth.device(who))
        val credentialDirectory = Files.createDirectory(directory.resolve("credentials"), permissions)
        val intentDirectory = Files.createDirectory(directory.resolve("intents"), permissions)
        val keyGenerator = KeyGenerator.getInstance("AES").apply { init(256) }
        val credentialView = JvmClientCredentialCustodyView(credentialDirectory,
            LocalClientDeviceIdentity(auth.scope, auth.device(who)), DomainId.random(), DomainId.random(),
            DomainId.random(), keyGenerator.generateKey(), life::get)
        val intentView = JvmClientJournalCustodyView(intentDirectory, identity,
            DomainId.random(), DomainId.random(), DomainId.random(), keyGenerator.generateKey(), life::get)
        val credentials = assertIs<JvmClientCredentialJournalOpenResult.Opened>(
            JvmEncryptedClientCredentialJournal.createNew(JvmClientCredentialCustody { credentialView.takeIf { life.get() } }))
            .journal.also { credentialJournal = it }
        val intents = assertIs<JvmClientJournalOpenResult.Opened>(
            JvmEncryptedClientIntentJournal.createNew(JvmClientJournalCustody { intentView.takeIf { life.get() } }))
            .journal.also { intentJournal = it }
        val connection = LocalMasterClientConnection(client, auth.captureClientPeer(who), DomainId.random(),
            LocalWorkflowInstallation(emptySet(), setOf(LocalStockStoreProfile.QUANTITIES)),
            LocalBranchDisplayLabels(auth.scope, "Synthetic auth company", "Synthetic auth branch"),
            null, client, life::get)
        val root = LocalEndpointBranchGateway(MutableStateFlow(connection), credentials,
            LocalClientIntentJournalSource { owner -> intents.takeIf { it.owner == owner } }, Dispatchers.Default)
            .also { gateway = it }
        session = assertIs<SignInResult.Authenticated<BranchUiSession>>(
            root.signIn(auth.login(who).value, auth.copyClientPassword(who))).session
    }

    fun access() = LocalGatewayCredentials(auth.connections.getValue(who), client.original.access)
    fun refresh() = LocalGatewayCredentials(auth.connections.getValue(who), client.original.refresh)
    /** Read-only TEST instrumentation through the actual internal encrypted-journal reader.
     * No constructor, mutable field, plaintext codec or accepting production seam is used.
     * Stable original facts compare independently of legitimate negative-cleanup metadata. */
    suspend fun observeOriginals(): StoredOriginalObservation {
        val journal = checkNotNull(credentialJournal)
        val reader = journal.javaClass.declaredMethods.single { method ->
            method.name.startsWith("read") && method.parameterCount == 1 &&
                method.parameterTypes.single() == kotlin.coroutines.Continuation::class.java
        }
        val result = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            try { reader.invoke(journal, continuation) }
            catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
        } ?: error("Actual credential reader returned no observation")
        val value = result.javaClass.methods.singleOrNull { it.name == "getValue" && it.parameterCount == 0 }
            ?.invoke(result) ?: error("Actual encrypted credential observation unavailable")
        try {
            value.javaClass.getMethod("validate", LocalClientDeviceIdentity::class.java).invoke(value, journal.owner)
            val sessions = property(value, "Sessions") as List<*>
            val observed = sessions.map { item ->
                val row = checkNotNull(item)
                listOf("LocalId", "Identity", "Login", "Binding", "SessionId", "Generation",
                    "AccessExpiresAt", "WorkExpiresAt").associateWith { property(row, it) } + mapOf(
                    "AccessDigest" to (property(row, "Access") as OpaqueCredential).useBytes { authV1Sha256(it) },
                    "RefreshDigest" to (property(row, "Refresh") as OpaqueCredential).useBytes { authV1Sha256(it) })
            }
            observeCleanup(buildJsonObject {
                put("journal_revision", property(value, "Revision") as Long)
                put("retained_sessions", sessions.size)
                put("cleanup_metadata", JsonArray(sessions.map { item ->
                    val row = checkNotNull(item)
                    buildJsonObject {
                        put("phase", property(row, "Phase").toString())
                        put("original_revoked", property(row, "OriginalRevoked") as Boolean)
                    }
                }))
            })
            return StoredOriginalObservation(observed,
                (property(value, "Attempts") as List<*>).size,
                (property(value, "Enrollments") as List<*>).size,
                (property(value, "Administrations") as List<*>).size,
                Files.walk(directory.resolve("intents")).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.toList().associate {
                        directory.relativize(it).toString() to authV1Sha256(Files.readAllBytes(it))
                    }
                })
        } finally { value.javaClass.getMethod("destroy").invoke(value) }
    }

    private fun property(value: Any, name: String): Any? = value.javaClass.methods.single { method ->
        method.parameterCount == 0 && (method.name == "get$name" || method.name.startsWith("get$name-"))
    }.invoke(value)
    suspend fun close() {
        try { gateway?.closeAndAwait() }
        finally {
            try { credentialJournal?.close() }
            finally {
                try { intentJournal?.close() }
                finally { life.set(false); client.close() }
            }
        }
    }
}

private class StoredAdministrationObservedClient(private val delegate: LocalMasterEndpoint) :
    LocalMasterClient by delegate, LocalMasterAdministrationClient by delegate {
    val protectedCalls = AtomicInteger()
    private var issued: LocalSessionTokens? = null
    val original get() = checkNotNull(issued)
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult {
        val result = delegate.signIn(connectionId, login, password, explicitDeviceMove)
        if (result is LocalAuthenticationResult.Authenticated) {
            check(issued == null)
            issued = LocalSessionTokens(result.tokens.view, result.tokens.access.useBytes { OpaqueCredential(it) },
                result.tokens.refresh.useBytes { OpaqueCredential(it) })
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

/** Original revocation/cleanup metadata may advance; immutable original rows and the
 * untouched financial-intent bytes must survive. Digests never grant authentication. */
private data class StoredOriginalObservation(
    val sessions: List<Map<String, Any?>>,
    val attempts: Int,
    val enrollments: Int,
    val administrations: Int,
    val financialIntentHashes: Map<String, String>,
)
