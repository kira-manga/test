package com.ukkera.pos.database.independent.setupprovisioningv1

import androidx.room3.Room
import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportCompleteRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportManifestEntryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointCodecV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProfileV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointTargetV1
import com.ukkera.pos.catalog.store.setup.LocalSetupConfigurationRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupEnvironmentV1
import com.ukkera.pos.catalog.store.setup.LocalSetupFiscalIdentityV1
import com.ukkera.pos.catalog.store.setup.LocalSetupImportModeV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningChoiceV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningCompleteRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningFloatManifestEntryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningFloatRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningStockManifestEntryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningStockRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOriginalBindingV1
import com.ukkera.pos.database.AggregateRow
import com.ukkera.pos.database.AtomicMasterStore
import com.ukkera.pos.database.AUTH_KEY
import com.ukkera.pos.database.BranchRow
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalActionApprovalSchemaCallback
import com.ukkera.pos.database.LocalAccessIssuanceSchemaCallback
import com.ukkera.pos.database.LocalAuthenticationSchemaCallback
import com.ukkera.pos.database.MasterClock
import com.ukkera.pos.database.MasterReplacementGuardsCallback
import com.ukkera.pos.database.MasterRoomConstructor
import com.ukkera.pos.database.MasterRoomDatabase
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.NoStoreFault
import com.ukkera.pos.database.ProtectedLocalSchemaCallback
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.TEST_AUTHORITY
import com.ukkera.pos.database.TEST_SCOPE
import com.ukkera.pos.database.TEST_TIME
import com.ukkera.pos.database.TransactionAuthorizationSource
import com.ukkera.pos.database.buildDurableRoom
import com.ukkera.pos.database.requireDurableWriter
import com.ukkera.pos.database.storageKey
import com.ukkera.pos.database.testId
import com.ukkera.pos.database.testPrincipal
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Supplemental Linux/JVM production-kernel coverage only.
 *
 * It installs the actual production registry checkpoint handlers in a real durable Room/SQLite
 * master store. That non-native JVM writer has no saved native original, so every checkpoint
 * command must fail closed with no operation/journal/audit/claim effect. The detached profile
 * cannot produce a native saved original, so this test does not claim the normal current-artifact
 * hold shape; it only proves the real durable kernel commits no partial setup state.
 * It does not execute a native installation, protected setup read, delivery ACK or go-live, and
 * it is not evidence for any SAS/AOSF case PASS.
 */
class LocalSetupCheckpointKernelSupportingTest {
    private val installationId = DomainId.random()

    @Test
    fun durableNonNativeWriterFailsClosedEveryCheckpointWithoutAnyCommittedEffect() = runTest {
        val fixture = DurableStoreFixture()
        try {
            fixture.seed()
            val store = fixture.store(checkpointHandlers(), setupAuthorization(), checkpointReads())
            val principal = testPrincipal()
            val before = fixture.counts()
            for ((key, request) in checkpointRequests()) {
                val reply = store.execute(principal, request)
                val deferred = assertIs<MutationReply.Deferred>(reply, "Checkpoint $key was not held without a native original")
                assertEquals(DeferredReason.STORAGE_UNCERTAIN, deferred.reason, "Checkpoint $key reason")
                assertEquals(before, fixture.counts(), "Checkpoint $key changed durable state without a source")
            }
            val read = store.read(principal, LocalSetupCheckpointCodecV1.read(installationId))
            val unavailable = assertIs<ReadReply.Unavailable>(read)
            assertEquals(ReadFailure.STORAGE_UNCERTAIN, unavailable.reason,
                "The detached non-native writer disclosed a checkpoint read instead of failing closed")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun sameOperationRetryStaysHeldWithoutAnyCommittedEffect() = runTest {
        val fixture = DurableStoreFixture()
        try {
            fixture.seed()
            val store = fixture.store(checkpointHandlers(), setupAuthorization())
            val principal = testPrincipal()
            val before = fixture.counts()
            val (key, request) = checkpointRequests().first()
            val first = assertIs<MutationReply.Deferred>(store.execute(principal, request))
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, first.reason)
            val retry = assertIs<MutationReply.Deferred>(store.execute(principal, request))
            assertEquals(DeferredReason.STORAGE_UNCERTAIN, retry.reason)
            assertEquals(before, fixture.counts(), "A held same-ID retry committed an effect for $key")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun durableStoreReopenKeepsTheSameNoEffectHold() = runTest {
        val fixture = DurableStoreFixture()
        val path = fixture.path
        try {
            fixture.seed()
            val (key, request) = checkpointRequests().first()
            fixture.store(checkpointHandlers(), setupAuthorization()).execute(testPrincipal(), request)
            assertEquals(0L, fixture.count("operation_result"))
            fixture.close()

            val reopened = DurableStoreFixture(path)
            try {
                val deferred = assertIs<MutationReply.Deferred>(
                    reopened.store(checkpointHandlers(), setupAuthorization()).execute(testPrincipal(), request))
                assertEquals(DeferredReason.STORAGE_UNCERTAIN, deferred.reason)
                assertEquals(0L, reopened.count("operation_result"), "Reopen replay committed the held $key request")
                assertEquals(0L, reopened.count("journal_envelope"))
                assertEquals(0L, reopened.count("audit_event"))
                assertEquals(0L, reopened.count("unique_claim"))
            } finally {
                reopened.close()
            }
        } finally {
            runCatching { fixture.close() }
        }
    }

    private fun checkpointReads() = LocalBranchCommerceRegistry(
        signatures = UnavailableCompanyPublicationSignatureVerifier,
        returnPolicy = UnavailableSalesReturnPolicy,
        recordingCapacity = UnavailableSalesRecordingCapacity,
        physicalRecordingCapacity = UnavailableSalesPhysicalRecordingCapacityV2,
    ).reads.filter { it.key == LocalSetupCheckpointProfileV1.READ }

    private fun checkpointHandlers() = LocalBranchCommerceRegistry(
        signatures = UnavailableCompanyPublicationSignatureVerifier,
        returnPolicy = UnavailableSalesReturnPolicy,
        recordingCapacity = UnavailableSalesRecordingCapacity,
        physicalRecordingCapacity = UnavailableSalesPhysicalRecordingCapacityV2,
    ).let { registry ->
        val handlers = registry.commands.filter { it.key in CHECKPOINT_COMMANDS }
        assertEquals(CHECKPOINT_COMMANDS.size, handlers.size)
        assertTrue(registry.historyProducer.commands.containsAll(CHECKPOINT_COMMANDS))
        handlers
    }

    private fun checkpointRequests(): List<Pair<com.ukkera.pos.database.CommandKey, MutationRequest>> {
        val target = LocalSetupCheckpointTargetV1(binding(), LocalSetupEnvironmentV1.LIVE)
        val source = DomainId.random()
        val sku = DomainId.random()
        val receipt = DomainId.random()
        val session = DomainId.random()
        val catalogOperation = OperationId.random()
        val stockOperation = OperationId.random()
        val cashOperation = OperationId.random()
        val catalogEntry = LocalSetupCatalogImportManifestEntryV1(source, sku, catalogOperation)
        val stockEntry = LocalSetupOpeningStockManifestEntryV1(source, receipt, stockOperation)
        val floatEntry = LocalSetupOpeningFloatManifestEntryV1(source, session, cashOperation)
        return listOf(
            LocalSetupCheckpointProfileV1.CONFIGURE to LocalSetupCheckpointCodecV1.configure(
                OperationId.random(), LocalSetupConfigurationRequestV1(target, Revision(0), Currency.EGP,
                    "Africa/Cairo", 0, LocalSetupFiscalIdentityV1("Synthetic setup", taxRegistrationAbsent = true))),
            LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW to LocalSetupCheckpointCodecV1.catalogImportRow(
                OperationId.random(), LocalSetupCatalogImportRowRequestV1(target, source, sku, catalogOperation)),
            LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT to LocalSetupCheckpointCodecV1.completeCatalogImport(
                OperationId.random(), LocalSetupCatalogImportCompleteRequestV1(target,
                    LocalSetupImportModeV1.DECLARED, 1,
                    LocalSetupCheckpointCodecV1.catalogInputManifestDigest(listOf(catalogEntry)))),
            LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW to LocalSetupCheckpointCodecV1.openingStockRow(
                OperationId.random(), LocalSetupOpeningStockRowRequestV1(target, source, receipt, stockOperation)),
            LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW to LocalSetupCheckpointCodecV1.openingFloatRow(
                OperationId.random(), LocalSetupOpeningFloatRowRequestV1(target, source, session, cashOperation)),
            LocalSetupCheckpointProfileV1.COMPLETE_OPENING to LocalSetupCheckpointCodecV1.completeOpening(
                OperationId.random(), LocalSetupOpeningCompleteRequestV1(target,
                    LocalSetupOpeningChoiceV1.DECLARED, 1,
                    LocalSetupCheckpointCodecV1.stockInputManifestDigest(listOf(stockEntry)),
                    LocalSetupOpeningChoiceV1.DECLARED, 1,
                    LocalSetupCheckpointCodecV1.floatInputManifestDigest(listOf(floatEntry)))),
        )
    }

    private fun binding(): LocalSetupOriginalBindingV1 = LocalSetupOriginalBindingV1(
        installationId = installationId,
        originalRequestId = DomainId.random(),
        issuedAttemptId = DomainId.random(),
        attemptSequence = 1L,
        definitionDigest = PayloadDigest("a".repeat(64)),
        genesisDigest = PayloadDigest("b".repeat(64)),
        receiptDigest = PayloadDigest("c".repeat(64)),
    )

    private fun setupAuthorization(): TransactionAuthorizationSource = TransactionAuthorizationSource { principal, _ ->
        snapshot(principal, SETUP_ACTIONS.map { action -> grant(principal.scope, principal.subjectId, action) })
    }

    private fun snapshot(principal: com.ukkera.pos.security.AuthenticatedPrincipal,
        grants: List<PermissionGrant>): AuthorizationSnapshot = AuthorizationSnapshot(
        principal.subjectId, principal.kind, principal.scope, principal.authenticationId,
        principal.generation, TEST_AUTHORITY.deviceId, Revision(1), Revision(1), true, true, true,
        AuthenticationPurpose.INTERACTIVE, grants,
    )

    private fun grant(scope: com.ukkera.pos.security.TenantScope, actor: DomainId,
        action: SecurityAction): PermissionGrant =
        PermissionGrant(Permission(action, PermissionVerb.ADMINISTER), scope, GrantEffect.ALLOW,
            PermissionLimits(), OwnerAccess.OWN_ONLY, actor, null, null)

    private fun DurableStoreFixture.store(handlers: List<com.ukkera.pos.database.MasterCommandHandler>,
        auth: TransactionAuthorizationSource,
        reads: List<com.ukkera.pos.database.MasterReadHandler> = emptyList()): AtomicMasterStore = AtomicMasterStore(
        database, TEST_SCOPE, TEST_AUTHORITY, handlers, auth, runtime, MasterClock { TEST_TIME }, NoStoreFault,
        readHandlers = reads,
    )

    private companion object {
        val CHECKPOINT_COMMANDS = setOf(
            LocalSetupCheckpointProfileV1.CONFIGURE,
            LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW,
            LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT,
            LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW,
            LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW,
            LocalSetupCheckpointProfileV1.COMPLETE_OPENING,
        )
        val SETUP_ACTIONS = setOf(
            SecurityAction.SETUP_CONFIGURATION_SETTLE,
            SecurityAction.SETUP_CATALOG_IMPORT_SETTLE,
            SecurityAction.SETUP_OPENING_SETTLE,
            SecurityAction.SETUP_RESULT_READ,
        )
    }
}

/** Minimal detached durable Room/SQLite fixture; no native original/history frame is created. */
internal class DurableStoreFixture(val path: Path = Files.createTempDirectory("setup-checkpoint-kernel-")
    .resolve("master.sqlite")) {
    val database: MasterRoomDatabase = buildDurableRoom(
        Room.databaseBuilder<MasterRoomDatabase>(path.toString(), MasterRoomConstructor::initialize)
            .addCallback(ProtectedLocalSchemaCallback)
            .addCallback(LocalAccessIssuanceSchemaCallback)
            .addCallback(MasterReplacementGuardsCallback)
            .addCallback(LocalAuthenticationSchemaCallback)
            .addCallback(LocalActionApprovalSchemaCallback),
        BundledSQLiteDriver(),
    )
    val runtime = object : com.ukkera.pos.database.MasterRuntimeVerifier {
        override suspend fun writer(observation: com.ukkera.pos.database.BranchObservation) =
            com.ukkera.pos.database.RuntimeVerification.VERIFIED
        override suspend fun absentOperation(observation: com.ukkera.pos.database.BranchObservation,
            identity: com.ukkera.pos.recovery.OperationIdentity) = com.ukkera.pos.database.RuntimeVerification.VERIFIED
    }

    suspend fun seed() {
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                database.facts().insertBranch(BranchRow(TEST_SCOPE.storageKey(), TEST_AUTHORITY.assignmentId.value,
                    TEST_AUTHORITY.deviceId.value, 1, 0, 0, "0".repeat(64), true,
                    Json.encodeToString(RecoveryAdmissionState.initiallyProvisioned(TEST_SCOPE.branchId))))
                database.facts().insertAggregate(AggregateRow(TEST_SCOPE.storageKey(), AUTH_KEY.kind,
                    AUTH_KEY.id.value, 1, "auth/v1", "enabled".encodeToByteArray(),
                    sha256("enabled".encodeToByteArray()).value))
            }
        }
    }

    suspend fun count(table: String): Long {
        require(table in setOf("operation_result", "journal_envelope", "audit_event", "unique_claim"))
        return database.useWriterConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM $table") { check(it.step()); it.getLong(0) }
        }
    }

    suspend fun counts(): List<Long> = listOf(count("operation_result"), count("journal_envelope"),
        count("audit_event"), count("unique_claim"))

    fun close() = database.close()
}
