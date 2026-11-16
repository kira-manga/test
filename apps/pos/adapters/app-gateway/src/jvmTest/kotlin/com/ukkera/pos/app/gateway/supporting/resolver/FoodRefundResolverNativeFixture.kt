package com.ukkera.pos.app.gateway.supporting.resolver

import com.ukkera.pos.database.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.SignInResult
import java.nio.file.Path

import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.LocalCatalogReturnPolicySource
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.security.desktopmasterv2.LinuxTestOs
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as N
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/**
 * Self-authored, NOT independent. Production provisioner/head approval/Room/current policy and
 * actual cohost native journal composition; derived from the existing native LAN supporting
 * fixture. Two distinct genuinely enrolled actors, explicit scoped supervisor ANY_OWNER grants.
 * Linux software OS support only; not a TCP/distributed Client-root or platform qualification.
 * Bound to blueprint section5.3 and frozen LAN-A07/D04/D06; no original expectations changed.
 */
internal class FoodRefundResolverNativeFixture(private val stationId: DomainId) {
    val base = Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".food-refund-resolver-native-test-",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    private val os = LinuxTestOs(base, N.binding(N.macIdentity)) { System.err.println("Desktop native fixture $it") }
    private val profile = SelfOwnedNativeProfileV1.training(LocalTrainingCompositionSlot.TRAINING_1)
    private val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
    private var service: AndroidSelfOwnedMasterService? = null
    private var runtime: DesktopLocalBranchRuntime? = null
    private var enrolled = false
    private val supervisor = DomainId.random()
    private var supervisorRequest: DomainId? = null
    private var supervisorAction: DomainId? = null
    private val extraSupervisorTokens = mutableListOf<LocalSessionTokens>()
    private lateinit var tokens: LocalSessionTokens
    private lateinit var client: LocalMasterClient
    private var connection: DomainId? = null
    lateinit var coordinates: SelfOwnedAllocationCoordinatesV1
        private set
    val scope: BranchScope get() = coordinates.scope
    val actor: DomainId get() = coordinates.firstActorId
    val supervisorActor: DomainId get() = supervisor
    val credentials: LocalGatewayCredentials get() = LocalGatewayCredentials(checkNotNull(connection), tokens.access)

    private fun <T> provisioner(block: (SelfOwnedDesktopProvisionerV2) -> T): T {
        val platform = DesktopSelfOwnedOriginalPlatform(os)
        val work = AndroidOwnedInstallationWork(checkNotNull(AndroidProvisionerProcessLease.acquire(profile)), platform.process)
        try {
            val environment = platform.captureEnvironment(work, profile, preparation = true)
            val registry = platform.provisionerRegistry(environment, work)
            registry.openNative()
            try {
                return block(SelfOwnedDesktopProvisionerV2(environment, registry as SelfOwnedProvisionerRegistryStoreV2,
                    platform.provisionerKeys(environment, registry, work), work))
            } finally { registry.closeNative() }
        } finally { runBlocking { withTimeout(30_000L) { work.requestClose { }.await() } } }
    }

    lateinit var publications: LocalCompanyBusinessPublications
        private set

    suspend fun install(signatures: CompanyPublicationSignatureVerifier? = null) {
        val founding = LocalCompanyFoundingFactsV1(SelfOwnedCompanyFactsV1("Synthetic Deposits", "Synthetic Person",
            "installer.one", SelfOwnedKeyProtectionV1.OS_ISOLATED), "Synthetic Head", "head.one",
            LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, LocalCompanyHeadPurposeV1.entries.toSet())
        val company = provisioner { p -> p.completeNewCompanyAndHead(p.prepareNewCompanyAndHead(founding),
            "synthetic-installer-pass".toCharArray(), "synthetic-head-pass-2026".toCharArray()) }
        provisioner { p -> p.issueOriginalPreparation(p.prepareOriginalBranch("Synthetic Branch", company.rootFingerprint),
            "installer.one", "synthetic-installer-pass".toCharArray()) }
        val prepared = assertIs<SelfOwnedNativeOpenResult.Opened>(DesktopSelfOwnedInstallationOwner.prepare(os, profile))
        coordinates = prepared.context.materialAtOpen.coordinates
        prepared.context.close()
        val context = assertIs<SelfOwnedNativeOpenResult.Opened>(DesktopSelfOwnedInstallationOwner.open(os, profile)).context
        val installing = assertIs<AndroidSelfOwnedMasterOpenResult.Opened>(factory.forOriginalInstallation(context)).service
        service = installing
        val actions = setOf(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, SecurityAction.DEVICE_ADMINISTRATION,
            SecurityAction.OPERATION_RESULT_RECOVERY, SecurityAction.INVENTORY_ADJUST, SecurityAction.CATALOG_ACTIVATE,
            SecurityAction.WAREHOUSE_CONFIGURE, SecurityAction.OPENING_INVENTORY_APPROVE, SecurityAction.CASH_CONFIGURE,
            SecurityAction.CASH_OPEN, SecurityAction.CASH_MOVE, SecurityAction.CASH_READ, SecurityAction.ORDER_CREATE,
            SecurityAction.COLLECTION_BEGIN, SecurityAction.COLLECTION_FINALIZE, SecurityAction.COLLECTION_FINALIZE_ZERO,
            SecurityAction.REFUND_RECORD, SecurityAction.RETURN_RECEIPT_LOOKUP, SecurityAction.ORDER_EDIT,
            SecurityAction.ORDER_READ, SecurityAction.INVENTORY_QUANTITY_READ, SecurityAction.INVENTORY_COST_READ,
            SecurityAction.CASH_CLOSE, SecurityAction.COLLECTION_ATTEST, SecurityAction.SUPPLIER_ADMINISTRATION,
            SecurityAction.PURCHASING_READ, SecurityAction.SUPPLIER_CASH_SETTLE,
            SecurityAction.CATALOG_READ, SecurityAction.KITCHEN_TASK,
            SecurityAction.COLLECTION_RESOLVE)
        val grants = actions.flatMap { action -> listOf(PermissionVerb.PERFORM, PermissionVerb.ADMINISTER).map { verb ->
            PermissionGrant(Permission(action, verb), scope, GrantEffect.ALLOW,
                PermissionLimits(Money(Currency.EGP, 1_000_000), 10_000), OwnerAccess.OWN_ONLY, actor,
                targetIds = if (action == SecurityAction.KITCHEN_TASK) setOf(stationId) else null)
        } }
        val account = LocalAccountProvisioning(actor, LocalLoginIdentifier("owner.one"),
            ActivatedLocalPolicy(scope, actor, Revision(0), grants, LocalSessionPolicy(2), ThrottlePolicy(), approvalPolicy = null),
            LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), setOf(coordinates.deviceId))
        val definition = installing.prepareOriginalDefinition(account, Revision(1))
        var installed = installing.installOriginal(definition.definition, "head.one", "synthetic-head-pass-2026".toCharArray(),
            "synthetic-owner-pass-2026".toCharArray())
        if (installed == LocalMasterInstallationOutcome.AcknowledgementPending) installed = installing.resumeOriginalAcknowledgement()
        assertIs<LocalMasterInstallationOutcome.Installed>(installed)
        assertTrue(installing.quiesceAndClose())
        service = null
        openOperational(signatures)
        // The frozen installation v2 vocabulary predates activity activation. Use the
        // existing real next-revision head approval/sole-writer policy activation instead
        // of rewriting that format or bypassing current branch administration authority.
        val next = ActivatedLocalPolicy(scope, actor, Revision(1), account.policy.grants + PermissionGrant(
            Permission(SecurityAction.BRANCH_ACTIVITY_ACTIVATE, PermissionVerb.ADMINISTER), scope, GrantEffect.ALLOW,
            PermissionLimits(Money(Currency.EGP, 1_000_000), 10_000), OwnerAccess.OWN_ONLY, actor),
            account.policy.sessionPolicy, account.policy.throttlePolicy, account.policy.approvalPolicy)
        val original = checkNotNull(service).originalLocalClient()
        val policyAction = assertIs<LocalCompanyAuthorityResult.Value<LocalEmployeePolicyActivationAction>>(
            original.prepareEmployeePolicy(DomainId.random(), next)).value
        val approval = assertIs<LocalCompanyAuthorityResult.Value<LocalEmployeePolicyActivationAuthorityReceipt>>(
            original.approveEmployeePolicy(policyAction, "head.one", "synthetic-head-pass-2026".toCharArray())).value
        val changed = assertIs<LocalCredentialAdministrationResult.Applied>(original.activateEmployeePolicy(approval, next))
        val retained = assertIs<LocalProvisioningStatus.Applied>(original.lookupEmployeePolicy(approval, next))
        assertEquals(changed.eventId, retained.eventId)
        login()
    }

    private suspend fun openOperational(signatures: CompanyPublicationSignatureVerifier?) {
        System.err.println("Desktop native fixture operational open entered")
        val reopened = assertIs<SelfOwnedNativeOpenResult.Opened>(DesktopSelfOwnedInstallationOwner.open(os, profile))
        publications = assertIs<LocalCompanyAuthorityResult.Value<LocalCompanyBusinessPublications>>(
            reopened.context.readOriginalBusinessPublications()).value
        val signatureSource = signatures ?: NativeCompanyPublicationSignatureVerifier(publications.trust)
        val storage = DesktopClientJournalStorages.masterService(DesktopMasterClientJournalCustody.of(os, profile))
        val physical = checkNotNull(DesktopOriginalPhysicalJournalExecutionGate.create(factory.owner, storage))
        val registry = LocalBranchCommerceRegistry(signatureSource, LocalCatalogReturnPolicySource(signatureSource),
            UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
            retailParticipants = physical, nativePublications = publications)
        val opened = factory.fromOriginalContext(reopened.context, registry.commands, registry.reads, physical,
            registry.nativeOriginalImports, registry.stockProjection, registry.historyProducer)
        val operational = assertIs<AndroidSelfOwnedMasterOpenResult.Opened>(opened, "Operational open: $opened")
        service = operational.service
        assertEquals(LocalMasterLifecycleMode.OPERATIONAL, operational.service.mode.value, "Whole original history must qualify operational startup")
        client = checkNotNull(operational.service.client)
        connection = checkNotNull(operational.service.localConnectionId)
        assertSame(publications, operational.service.originalLocalClient().originalBusinessPublications())
        runtime = checkNotNull(DesktopLocalBranchRuntime.connectOriginal(factory.owner, operational.service,
            if (enrolled) LocalOriginalClientJournalAction.REOPEN_EXISTING else LocalOriginalClientJournalAction.ENROLL_FIRST_CLIENT, storage))
        supervisorAction?.let { action ->
            assertIs<LocalCompanyAuthorityResult.Value<AndroidLocalEmployeeEnrollmentConnection>>(
                checkNotNull(runtime).recoverEmployeeEnrollmentConnection(action))
            assertTrue(checkNotNull(runtime).acquireEmployeeIntentJournal(checkNotNull(supervisorRequest),
                NativeEmployeeClientJournalAction.REOPEN_EXISTING))
        }
        enrolled = true
        login()
        System.err.println("Resolver native fixture operational open completed")
    }

    suspend fun reopen() {
        System.err.println("Desktop native fixture cold reopen entered")
        runtime?.close(); runtime = null
        assertTrue(checkNotNull(service).quiesceAndClose())
        service = null
        os.nextBoot()
        openOperational(null)
    }

    suspend fun login() {
        if (::tokens.isInitialized) tokens.destroy()
        tokens = assertIs<LocalAuthenticationResult.Authenticated>(client.signIn(checkNotNull(connection), LocalLoginIdentifier("owner.one"),
            "synthetic-owner-pass-2026".toCharArray(), ::tokens.isInitialized)).tokens
    }
    suspend fun uiOwner(): BranchUiSession = assertIs<SignInResult.Authenticated<*>>(
        checkNotNull(runtime).branchGateway.signIn("owner.one", "synthetic-owner-pass-2026".toCharArray(), false)).session as BranchUiSession

    suspend fun enrollSupervisor() {
        val grants = setOf(SecurityAction.COLLECTION_RESOLVE, SecurityAction.REFUND_RESOLVE, SecurityAction.SUPPLIER_CASH_RESOLVE,
            SecurityAction.OPERATION_RESULT_RECOVERY).map { action -> PermissionGrant(Permission(action, PermissionVerb.PERFORM),
                scope, GrantEffect.ALLOW, PermissionLimits(Money(Currency.EGP, 1_000_000), 10_000),
                if (action == SecurityAction.OPERATION_RESULT_RECOVERY) OwnerAccess.OWN_ONLY else OwnerAccess.ANY_OWNER, supervisor) }
        val definition = LocalAccountProvisioning(supervisor, LocalLoginIdentifier("supervisor.two"),
            ActivatedLocalPolicy(scope, supervisor, Revision(0), grants, LocalSessionPolicy(2), ThrottlePolicy(), approvalPolicy = null),
            LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), setOf(coordinates.deviceId))
        val original = checkNotNull(service).originalLocalClient()
        val request = DomainId.random()
        val prepared = assertIs<LocalCompanyAuthorityResult.Value<LocalEmployeeEnrollmentAction>>(
            original.prepareEmployeeEnrollment(request, definition, true)).value
        supervisorRequest = request; supervisorAction = prepared.originalActionId
        val approval = original.approveEmployeeEnrollment(prepared, "head.one", "synthetic-head-pass-2026".toCharArray())
        val approved = assertIs<LocalCompanyAuthorityResult.Value<AndroidLocalEmployeeEnrollmentConnection>>(
            approval, "Original supervisor approval: $approval; request=$request; action=${prepared.originalActionId}").value
        assertIs<LocalCredentialAdministrationResult.Applied>(approved.provisionAccount(request, definition,
            "synthetic-supervisor-pass-2026".toCharArray()))
        assertTrue(checkNotNull(runtime).acquireEmployeeIntentJournal(request, NativeEmployeeClientJournalAction.ENROLL_EMPLOYEE))
    }
    suspend fun uiSupervisor(): BranchUiSession {
        val signed = checkNotNull(runtime).branchGateway.signIn("supervisor.two", "synthetic-supervisor-pass-2026".toCharArray(), true)
        return assertIs<SignInResult.Authenticated<*>>(signed, "Current supervisor sign-in: $signed").session as BranchUiSession
    }
    suspend fun supervisorCredentials(): LocalGatewayCredentials {
        val result = assertIs<LocalAuthenticationResult.Authenticated>(client.signIn(checkNotNull(connection),
            LocalLoginIdentifier("supervisor.two"), "synthetic-supervisor-pass-2026".toCharArray(), false))
        extraSupervisorTokens += result.tokens
        return LocalGatewayCredentials(checkNotNull(connection), result.tokens.access)
    }

    suspend fun execute(request: MutationRequest): MutationReply = client.execute(credentials, request)
    /** Genuine protected endpoint with actual separately issued supervisor credentials.
     * This supplies no authority and bypasses no Master check. UI same-body dedup remains intact. */
    suspend fun executeAs(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply =
        client.execute(credentials, request)
    suspend fun lookupAs(credentials: LocalGatewayCredentials, identity: com.ukkera.pos.recovery.OperationIdentity): LookupReply =
        client.lookup(credentials, identity)
    suspend fun read(request: ReadRequest): ReadReply = client.read(credentials, request)

    /** Only this fixture's genuine, uniquely located database; never an application connection. */
    private fun databasePath(): String {
        checkNotNull(service)
        val paths = Files.walk(base).use { files -> files.filter { it.fileName.toString() == "master.db" }.toList() }
        assertEquals(1, paths.size)
        val path = paths.single().toRealPath()
        check(path.startsWith(base.toRealPath()))
        return path.toString()
    }

    /** Fresh external query-only connection. No application read/grant or source is supplied. */
    private fun sqlite(script: String, vararg arguments: String): List<String> {
        val prelude = """
            import sqlite3, sys, urllib.parse
            db = sqlite3.connect('file:' + urllib.parse.quote(sys.argv[1], safe='/') + '?mode=ro', uri=True)
            db.execute('PRAGMA query_only=ON')
        """.trimIndent()
        val process = ProcessBuilder(listOf("python3", "-c", prelude + "\n" + script, databasePath()) + arguments)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), "Read-only native evidence query failed: $output")
        return output.lineSequence().filter { it.isNotBlank() }.toList()
    }

    fun operationCounts(operation: OperationId): Map<String, Long> = sqlite("""
        for table in ['operation_result', 'journal_envelope', 'audit_event', 'outbox_intent']:
            print(table + '=' + str(db.execute('SELECT COUNT(*) FROM ' + table + ' WHERE operationId=?', (sys.argv[2],)).fetchone()[0]))
    """.trimIndent(), operation.value).associate { it.substringBefore('=') to it.substringAfter('=').toLong() }

    fun expectedCash(sessionId: DomainId): Money {
        val rows = sqlite("""
            rows = db.execute('SELECT revision,payload,payloadHash FROM aggregate_state WHERE scope=? AND kind=? AND id=?', sys.argv[2:]).fetchall()
            assert len(rows) == 1
            print(rows[0][0]); print(bytes(rows[0][1]).hex()); print(rows[0][2])
        """.trimIndent(), scope.storageKey(), CashNonSaleStoreProfile.SESSION_KIND, sessionId.value)
        check(rows.size == 3)
        val generation = Revision(rows[0].toLong())
        val bytes = rows[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        check(sha256(bytes).value == rows[2])
        return com.ukkera.pos.sales.SalesSnapshotCodec.decodeCashSession(bytes.decodeToString(), com.ukkera.pos.sales.SalesSnapshotBinding(
            com.ukkera.pos.sales.SalesSnapshotKind.CASH_SESSION, scope, sessionId, SalesStorageRevision.toDomain(generation))).expectedCash
    }

    suspend fun close() {
        if (::tokens.isInitialized) tokens.destroy()
        extraSupervisorTokens.forEach { it.destroy() }
        runtime?.close(); runtime = null
        assertTrue(service?.quiesceAndClose() != false, "Native retirement must complete before cleanup")
        service = null
        println("Resolver native originals retained at $base")
    }
}
