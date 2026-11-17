package com.ukkera.pos.database

import androidx.sqlite.driver.bundled.SQLITE_OPEN_NOFOLLOW
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.catalog.store.CompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.NativeCompanyPublicationSignatureVerifier
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.security.desktopmasterv2.LinuxTestOs
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as N
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/**
 * Self-authored, NOT independent. Derived from the existing DepositNativeFixture production
 * provisioner/Room/native-history TRAINING composition. Explicit two-session signed policy, one actual
 * same-device connection. Linux OS/software key doubles do NOT qualify Windows/macOS custody,
 * separate admitted Client processes, physical payment or the whole frozen LAN cases.
 */
internal class LanBusinessNativeFixture {
    // A deterministic scheduling seam only. The actual production handler still prepares and
    // evaluates the command in the genuine kernel writer with unchanged authorization/history.
    var beforeCashPrepare: suspend (CommandContext) -> Unit = { }
    // Negative-only supporting reader seam. The real registry, current authorization,
    // native original and protected SQL loan remain in use; no result is substituted.
    var beforeStockRead: suspend (LocalBranchCommerceRegistry, CommandReadView, ReadContext) -> Unit = { _, _, _ -> }
    private val extraTokens = mutableListOf<LocalSessionTokens>()
    val endpoint: LocalMasterClient get() = client
    val administration: LocalMasterAdministrationClient get() = client as LocalMasterAdministrationClient
    suspend fun secondSession(): LocalSessionTokens = assertIs<LocalAuthenticationResult.Authenticated>(
        client.signIn(checkNotNull(connection), LocalLoginIdentifier("owner.one"),
            "synthetic-owner-pass-2026".toCharArray())).tokens.also { extraTokens += it }
    fun credentials(tokens: LocalSessionTokens) = LocalGatewayCredentials(checkNotNull(connection), tokens.access)
    private val base = Files.createTempDirectory(Path.of("/var/tmp"), "lan-business-native-test",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    private val os = LinuxTestOs(base, N.binding(N.macIdentity)) { System.err.println("Desktop native fixture $it") }
    private val profile = SelfOwnedNativeProfileV1.training(LocalTrainingCompositionSlot.TRAINING_1)
    private val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
    private var service: AndroidSelfOwnedMasterService? = null
    private lateinit var tokens: LocalSessionTokens
    private lateinit var client: LocalMasterClient
    private var connection: DomainId? = null
    lateinit var coordinates: SelfOwnedAllocationCoordinatesV1
        private set
    val scope: BranchScope get() = coordinates.scope
    val actor: DomainId get() = coordinates.firstActorId
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
            SecurityAction.CASH_CLOSE)
        val grants = actions.flatMap { action -> listOf(PermissionVerb.PERFORM, PermissionVerb.ADMINISTER).map { verb ->
            PermissionGrant(Permission(action, verb), scope, GrantEffect.ALLOW,
                PermissionLimits(Money(Currency.EGP, 1_000_000), 10_000), OwnerAccess.OWN_ONLY, actor)
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
    }

    private suspend fun openOperational(signatures: CompanyPublicationSignatureVerifier?, requireOperational: Boolean = true) {
        System.err.println("Desktop native fixture operational open entered")
        val reopened = assertIs<SelfOwnedNativeOpenResult.Opened>(DesktopSelfOwnedInstallationOwner.open(os, profile))
        publications = assertIs<LocalCompanyAuthorityResult.Value<LocalCompanyBusinessPublications>>(
            reopened.context.readOriginalBusinessPublications()).value
        val registry = LocalBranchCommerceRegistry(signatures ?: NativeCompanyPublicationSignatureVerifier(publications.trust),
            UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
            nativePublications = publications)
        val producer = registry.historyProducer
        val observedHistory = object : RuntimeHistoryBusinessProducer by producer {
            override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
                val joined = try { producer.join(command) } catch (failure: Throwable) {
                    System.err.println("Supporting business history join failed: ${command.command}; ${failure.javaClass.simpleName}; " +
                        failure.stackTrace.take(4).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" })
                    throw failure
                }
                if (!joined) System.err.println("Supporting business history unsupported: ${command.command}; " +
                    if (command.command == LocalSalesStoreProfile.CASH_COMMAND)
                        LocalCashCommandCodec.decode(command.normalized)::class.simpleName else "NON_CASH")
                return joined
            }
        }
        val opened = factory.fromOriginalContext(reopened.context,
            registry.commands.map { handler ->
                if (handler.key != LocalSalesStoreProfile.CASH_COMMAND) handler
                else object : MasterCommandHandler by handler {
                    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
                        context: CommandContext): PreparedMutation {
                        beforeCashPrepare(context)
                        return handler.prepare(normalized, view, context)
                    }
                }
            }, registry.reads.map { handler ->
                if (handler.key != com.ukkera.pos.inventory.store.LocalStockStoreProfile.QUANTITIES) handler
                else object : MasterReadHandler by handler {
                    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
                        context: ReadContext): PreparedRead {
                        beforeStockRead(registry, view, context)
                        return handler.prepare(normalized, view, context)
                    }
                }
            }, UnavailablePhysicalRecordingExecutionGate, registry.nativeOriginalImports,
            registry.stockProjection, observedHistory)
        if (!requireOperational && opened is AndroidSelfOwnedMasterOpenResult.Unresolved) {
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, opened.reason,
                "Damage must be refused by the actual native history boundary")
            System.err.println("Supporting damaged history refused before operational service: ${opened.reason}")
            return
        }
        val operational = assertIs<AndroidSelfOwnedMasterOpenResult.Opened>(opened, "Operational open: $opened")
        service = operational.service
        if (!requireOperational && operational.service.client == null) return
        client = checkNotNull(operational.service.client) { "Original operational client unavailable: ${operational.service.mode.value}" }
        connection = checkNotNull(operational.service.localConnectionId)
        assertSame(publications, operational.service.originalLocalClient().originalBusinessPublications())
        login()
        System.err.println("Desktop native fixture operational open completed")
    }

    suspend fun reopen() {
        System.err.println("Desktop native fixture cold reopen entered")
        assertTrue(checkNotNull(service).quiesceAndClose())
        service = null
        os.nextBoot()
        openOperational(null)
    }

    suspend fun login() {
        if (::tokens.isInitialized) tokens.destroy()
        tokens = assertIs<LocalAuthenticationResult.Authenticated>(client.signIn(checkNotNull(connection), LocalLoginIdentifier("owner.one"),
            "synthetic-owner-pass-2026".toCharArray())).tokens
    }
    /** Self-authored supporting enrollment through actual retained original ceremonies.
     * Adds no fixture grant/row directly and does not create another device or native owner. */
    suspend fun enrollStockActor(): LocalGatewayCredentials {
        val actor = DomainId.random()
        val actions = setOf(SecurityAction.CASH_OPEN, SecurityAction.CASH_READ, SecurityAction.ORDER_CREATE,
            SecurityAction.ORDER_EDIT, SecurityAction.ORDER_READ, SecurityAction.COLLECTION_BEGIN,
            SecurityAction.COLLECTION_FINALIZE, SecurityAction.COLLECTION_FINALIZE_ZERO,
            SecurityAction.INVENTORY_QUANTITY_READ, SecurityAction.INVENTORY_COST_READ,
            SecurityAction.OPERATION_RESULT_RECOVERY)
        val grants = actions.map { action -> PermissionGrant(Permission(action, PermissionVerb.PERFORM),
            scope, GrantEffect.ALLOW, PermissionLimits(Money(Currency.EGP, 1_000_000), 10_000), OwnerAccess.OWN_ONLY, actor) }
        val definition = LocalAccountProvisioning(actor, LocalLoginIdentifier("stock.actor.two"),
            ActivatedLocalPolicy(scope, actor, Revision(0), grants, LocalSessionPolicy(1), ThrottlePolicy(), approvalPolicy = null),
            LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), setOf(coordinates.deviceId))
        val original = checkNotNull(service).originalLocalClient()
        val request = DomainId.random()
        val prepared = when (val value = original.prepareEmployeeEnrollment(request, definition, true)) {
            is LocalCompanyAuthorityResult.Value -> value.value
            is LocalCompanyAuthorityResult.Unavailable -> error("Actual employee preparation refused: ${value.reason}")
        }
        val approved = when (val value = original.approveEmployeeEnrollment(prepared, "head.one", "synthetic-head-pass-2026".toCharArray())) {
            is LocalCompanyAuthorityResult.Value -> value.value
            is LocalCompanyAuthorityResult.Unavailable -> error("Actual employee head approval refused: ${value.reason}")
        }
        assertIs<LocalCredentialAdministrationResult.Applied>(approved.provisionAccount(request, definition,
            "synthetic-stock-actor-pass-2026".toCharArray()))
        val authenticated = assertIs<LocalAuthenticationResult.Authenticated>(client.signIn(checkNotNull(connection),
            definition.login, "synthetic-stock-actor-pass-2026".toCharArray())).tokens
        extraTokens += authenticated
        return credentials(authenticated)
    }

    suspend fun execute(request: MutationRequest): MutationReply = client.execute(credentials, request)
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

    /** Fresh external read connection observes durable facts, not a handler response. */
    fun operationCounts(operation: OperationId): Map<String, Long> =
        BundledSQLiteDriver().open(databasePath(), SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { raw ->
            listOf("operation_result", "journal_envelope", "audit_event", "outbox_intent").associateWith { table ->
                raw.prepare("SELECT COUNT(*) FROM $table WHERE operationId=?").use {
                    it.bindText(1, operation.value); check(it.step()); it.getLong(0)
                }
            }
        }

    /** Negative-only external damage AFTER actual native close. Original native history is
     * never rewritten or blessed. Every production guard is restored byte-for-byte before
     * reopen; the only acceptable outcome is refusal of operational admission. */
    suspend fun damageForColdReopen(fault: String, movement: DomainId, refusal: OperationId): Boolean {
        require(fault in setOf("movement-body", "wrong-first-refusal", "missing-source"))
        val path = databasePath()
        assertTrue(checkNotNull(service).quiesceAndClose())
        service = null
        BundledSQLiteDriver().open(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_NOFOLLOW).use { raw ->
            raw.prepare("BEGIN IMMEDIATE").use { it.step() }
            try {
                val table = if (fault == "wrong-first-refusal") "operation_result" else "aggregate_state"
                fun guards() = raw.prepare("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND tbl_name=? ORDER BY name").use {
                    it.bindText(1, table); buildList { while (it.step()) add(it.getText(0) to it.getText(1)) }
                }
                val before = guards(); check(before.isNotEmpty())
                fun quoted(name: String) = "`" + name.replace("`", "``") + "`"
                for ((name, _) in before) raw.prepare("DROP TRIGGER ${quoted(name)}").use { it.step() }
                when (fault) {
                    "wrong-first-refusal" -> {
                        val body = raw.prepare("SELECT result FROM operation_result WHERE scope=? AND operationId=?").use {
                            it.bindText(1, scope.storageKey()); it.bindText(2, refusal.value); check(it.step()); it.getBlob(0)
                        }
                        assertEquals(LocalSalesResultV1.Rejected(com.ukkera.pos.sales.SalesRule.REVISION_CONFLICT),
                            LocalSalesResultCodec.decode(StoredBytes(body)))
                        val wrong = body.decodeToString().replace("REVISION_CONFLICT", "DUPLICATE_SOURCE_ALLOCATION").encodeToByteArray()
                        assertFalse(wrong.contentEquals(body))
                        raw.prepare("UPDATE operation_result SET result=?,resultHash=? WHERE scope=? AND operationId=?").use {
                            it.bindBlob(1, wrong); it.bindText(2, sha256(wrong).value)
                            it.bindText(3, scope.storageKey()); it.bindText(4, refusal.value); it.step()
                        }
                    }
                    "movement-body" -> {
                        val body = raw.prepare("SELECT payload FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use {
                            it.bindText(1, scope.storageKey()); it.bindText(2, LocalSalesStoreProfile.MOVEMENT_KIND)
                            it.bindText(3, movement.value); check(it.step()); it.getBlob(0)
                        }
                        val wrong = body.decodeToString().replace("NON_SALE_PAY_IN", "OPERATING_EXPENSE").encodeToByteArray()
                        assertFalse(wrong.contentEquals(body))
                        raw.prepare("UPDATE aggregate_state SET payload=?,payloadHash=? WHERE scope=? AND kind=? AND id=?").use {
                            it.bindBlob(1, wrong); it.bindText(2, sha256(wrong).value); it.bindText(3, scope.storageKey())
                            it.bindText(4, LocalSalesStoreProfile.MOVEMENT_KIND); it.bindText(5, movement.value); it.step()
                        }
                    }
                    "missing-source" -> raw.prepare("DELETE FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use {
                        it.bindText(1, scope.storageKey()); it.bindText(2, LocalSalesStoreProfile.CUSTODIAN_KIND)
                        it.bindText(3, actor.value); it.step()
                    }
                }
                raw.prepare("SELECT changes()").use { check(it.step() && it.getLong(0) == 1L && !it.step()) }
                for ((_, ddl) in before) raw.prepare(ddl).use { it.step() }
                check(guards() == before)
                raw.prepare("COMMIT").use { it.step() }
            } catch (failure: Throwable) {
                raw.prepare("ROLLBACK").use { it.step() }; throw failure
            }
        }
        os.nextBoot()
        openOperational(null, requireOperational = false)
        val held = service ?: return false // The exact typed STATE_UNCERTAIN refusal was checked above.
        assertEquals(LocalMasterLifecycleMode.UNRESOLVED, held.mode.value)
        return held.client != null
    }

    suspend fun close() {
        if (::tokens.isInitialized) tokens.destroy()
        extraTokens.forEach { it.destroy() }
        assertTrue(service?.quiesceAndClose() != false, "Native retirement must complete before cleanup")
        service = null
        System.err.println("Supporting business native originals retained at $base")
    }
}
