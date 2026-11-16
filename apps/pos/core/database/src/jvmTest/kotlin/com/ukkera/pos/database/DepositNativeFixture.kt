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
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** Self-authored, NOT independent. Real desktop provisioner/Room/native journal on isolated Linux
 * files; the existing Linux OS and software key doubles do NOT qualify macOS/Windows custody. */
internal class DepositNativeFixture {
    private val base = Files.createTempDirectory("deposit-native-test",
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
    val serialPublications: LocalMasterSerialPublications get() = checkNotNull((client as LocalMasterEndpoint).serialPublications)

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
            SecurityAction.REFUND_RECORD, SecurityAction.RETURN_RECEIPT_LOOKUP)
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

    private suspend fun openOperational(signatures: CompanyPublicationSignatureVerifier?) {
        System.err.println("Desktop native fixture operational open entered")
        val reopened = assertIs<SelfOwnedNativeOpenResult.Opened>(DesktopSelfOwnedInstallationOwner.open(os, profile))
        publications = assertIs<LocalCompanyAuthorityResult.Value<LocalCompanyBusinessPublications>>(
            reopened.context.readOriginalBusinessPublications()).value
        val registry = LocalBranchCommerceRegistry(signatures ?: NativeCompanyPublicationSignatureVerifier(publications.trust),
            UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2,
            nativePublications = publications)
        val opened = factory.fromOriginalContext(reopened.context,
            registry.commands, registry.reads, UnavailablePhysicalRecordingExecutionGate, registry.nativeOriginalImports,
            registry.stockProjection, registry.historyProducer)
        val operational = assertIs<AndroidSelfOwnedMasterOpenResult.Opened>(opened, "Operational open: $opened")
        service = operational.service
        client = checkNotNull(operational.service.client)
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

    /** Corruption check only after the actual service releases its native custody. */
    suspend fun removeSerialCompanionAndTryReopen(): SelfOwnedNativeOpenResult {
        assertTrue(checkNotNull(service).quiesceAndClose())
        service = null
        val paths = Files.walk(base).use { stream -> stream.filter {
            it.fileName.toString() == "ledger-business-publications.v2"
        }.toList() }
        assertEquals(1, paths.size)
        Files.delete(paths.single())
        os.nextBoot()
        return DesktopSelfOwnedInstallationOwner.open(os, profile)
    }

    suspend fun login() {
        if (::tokens.isInitialized) tokens.destroy()
        tokens = assertIs<LocalAuthenticationResult.Authenticated>(client.signIn(checkNotNull(connection), LocalLoginIdentifier("owner.one"),
            "synthetic-owner-pass-2026".toCharArray())).tokens
    }
    suspend fun logout() { assertEquals(LocalSignOutResult.Revoked, client.logout(credentials)) }
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

    /** Read-only external inspection, after a completed command, without borrowing native custody. */
    suspend fun count(table: String): Long {
        require(table == "operation_result")
        return BundledSQLiteDriver().open(databasePath(), SQLITE_OPEN_READONLY or SQLITE_OPEN_NOFOLLOW).use { raw ->
            raw.prepare("SELECT COUNT(*) FROM operation_result").use { check(it.step()); it.getLong(0) }
        }
    }

    /**
     * Negative fault injection only, while the fixture invokes no service operation. The external
     * connection damages the exact original row; it mints no frame, receipt or authority. Restore
     * every aggregate guard byte-for-byte in the same transaction before production runs again.
     * Native history/capture is deliberately not rewritten to bless the damaged row.
     */
    fun corruptOriginalPublication(key: AggregateKey, missing: Boolean) {
        require(key.kind == com.ukkera.pos.catalog.store.LocalConfigurationProfile.PUBLICATION)
        BundledSQLiteDriver().open(databasePath(), SQLITE_OPEN_READWRITE or SQLITE_OPEN_NOFOLLOW).use { raw ->
            raw.prepare("BEGIN IMMEDIATE").use { it.step() }
            try {
                fun guards() = raw.prepare("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND tbl_name='aggregate_state' ORDER BY name").use {
                    buildList { while (it.step()) add(it.getText(0) to it.getText(1)) }
                }
                val before = guards()
                check(before.isNotEmpty())
                fun quote(name: String) = "`" + name.replace("`", "``") + "`"
                for ((name, _) in before) raw.prepare("DROP TRIGGER ${quote(name)}").use { it.step() }
                if (missing) raw.prepare("DELETE FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use {
                    it.bindText(1, scope.storageKey()); it.bindText(2, key.kind); it.bindText(3, key.id.value); it.step()
                } else {
                    val original = raw.prepare("SELECT payload FROM aggregate_state WHERE scope=? AND kind=? AND id=?").use {
                        it.bindText(1, scope.storageKey()); it.bindText(2, key.kind); it.bindText(3, key.id.value)
                        check(it.step()); it.getBlob(0).also { _ -> check(!it.step()) }
                    }
                    val damaged = original.decodeToString().replace("\"minor\":\"100\"", "\"minor\":\"101\"").encodeToByteArray()
                    assertFalse(damaged.contentEquals(original))
                    raw.prepare("UPDATE aggregate_state SET payload=?,payloadHash=? WHERE scope=? AND kind=? AND id=?").use {
                        it.bindBlob(1, damaged); it.bindText(2, sha256(damaged).value); it.bindText(3, scope.storageKey())
                        it.bindText(4, key.kind); it.bindText(5, key.id.value); it.step()
                    }
                }
                raw.prepare("SELECT changes()").use { check(it.step() && it.getLong(0) == 1L && !it.step()) }
                for ((_, ddl) in before) raw.prepare(ddl).use { it.step() }
                check(guards() == before)
                raw.prepare("COMMIT").use { it.step() }
            } catch (failure: Throwable) {
                raw.prepare("ROLLBACK").use { it.step() }
                throw failure
            }
        }
    }
    suspend fun close() {
        if (::tokens.isInitialized) tokens.destroy()
        assertTrue(service?.quiesceAndClose() != false, "The native fixture must confirm retirement before cleanup")
        service = null
        base.toFile().deleteRecursively()
    }
}
