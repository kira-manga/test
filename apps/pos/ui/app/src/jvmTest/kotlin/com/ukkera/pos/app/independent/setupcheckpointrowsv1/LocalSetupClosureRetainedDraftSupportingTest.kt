package com.ukkera.pos.app.independent.setupcheckpointrowsv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalAcknowledgementResult
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSetupCatalogCompletionInput
import com.ukkera.pos.app.LocalSetupCatalogImportView
import com.ukkera.pos.app.LocalSetupCatalogRow
import com.ukkera.pos.app.LocalSetupCheckpointView
import com.ukkera.pos.app.LocalSetupClosureAvailability
import com.ukkera.pos.app.LocalSetupClosureController
import com.ukkera.pos.app.LocalSetupClosureUiGateway
import com.ukkera.pos.app.LocalSetupConfigurationInput
import com.ukkera.pos.app.LocalSetupConfigurationView
import com.ukkera.pos.app.LocalSetupManifestPreview
import com.ukkera.pos.app.LocalSetupMutationResult
import com.ukkera.pos.app.LocalSetupOpeningCompletionInput
import com.ukkera.pos.app.LocalSetupOpeningManifestPreview
import com.ukkera.pos.app.LocalSetupOpeningFloatRow
import com.ukkera.pos.app.LocalSetupOpeningStockRow
import com.ukkera.pos.app.LocalSetupOpeningView
import com.ukkera.pos.app.LocalSetupOperationKind
import com.ukkera.pos.app.LocalSetupOriginalView
import com.ukkera.pos.app.LocalSetupPendingView
import com.ukkera.pos.app.LocalSetupPrerequisiteView
import com.ukkera.pos.app.LocalSetupReadResult
import com.ukkera.pos.app.LocalSetupResultView
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.koin.core.parameter.parametersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent JVM qualification of setup-closure draft/recovery presentation. The real controller,
 * runtime attachment and entry ownership run with a controlled [LocalSetupClosureUiGateway]. The
 * stand-in is synthetic data, not master authorization, durable journal, native receipt or device
 * evidence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalSetupClosureRetainedDraftSupportingTest {
    private val actorId = DomainId.random()
    private val companyId = DomainId.random()
    private val branchId = DomainId.random()
    private val recordedAt = UtcInstant.parseCanonical("2026-11-09T12:00:00Z")

    @Test
    fun explicitReloadReplacesDraftsWhileAutomaticCommittedRefreshPreservesThem() = runTest {
        withController { gateway, controller, token ->
            gateway.checkpoints = checkpointView(
                catalog = listOf(catalog(1), catalog(2)),
                stock = listOf(stock(11)),
                float = listOf(float(21)))
            controller.loadCheckpoints(token)
            runCurrent()
            assertEquals(listOf(catalog(1), catalog(2)), controller.state.value.catalogRows)
            assertEquals(listOf(stock(11)), controller.state.value.openingStockRows)
            assertEquals(listOf(float(21)), controller.state.value.openingFloatRows)

            controller.addCatalogReference(token, catalog(3))
            gateway.checkpoints = checkpointView(catalog = listOf(catalog(1)), stock = listOf(stock(12)),
                float = listOf(float(22)))
            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.CATALOG_IMPORT_ROW, committed = true, code = "setup.catalog.row",
                catalogRow = catalog(4))
            controller.recordCatalogRow(token, catalog(4))
            runCurrent()
            assertEquals(listOf(catalog(1), catalog(2), catalog(3), catalog(4)),
                controller.state.value.catalogRows,
                "Automatic post-commit refresh overwrote unsubmitted catalog drafts")
            assertEquals(listOf(stock(11)), controller.state.value.openingStockRows,
                "Automatic post-commit refresh overwrote unsubmitted stock drafts")
            assertEquals(listOf(float(21)), controller.state.value.openingFloatRows,
                "Automatic post-commit refresh overwrote unsubmitted float drafts")

            controller.loadCheckpoints(token)
            runCurrent()
            assertEquals(listOf(catalog(1)), controller.state.value.catalogRows,
                "Explicit checkpoint reload did not replace catalog drafts from retained references")
            assertEquals(listOf(stock(12)), controller.state.value.openingStockRows,
                "Explicit checkpoint reload did not replace stock drafts from retained references")
            assertEquals(listOf(float(22)), controller.state.value.openingFloatRows,
                "Explicit checkpoint reload did not replace float drafts from retained references")
        }
    }

    @Test
    fun committedCompletionClearsOnlyItsDraftSetAndRejectedCompletionPreservesDrafts() = runTest {
        withController { gateway, controller, token ->
            gateway.checkpoints = checkpointView(
                catalog = listOf(catalog(1)), stock = listOf(stock(11)), float = listOf(float(21)))
            controller.loadCheckpoints(token)
            runCurrent()

            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE, committed = true, code = "setup.catalog.settled",
                catalogCompletion = gateway.catalogManifestPreview(controller.state.value.catalogRows))
            controller.completeCatalogImport(token, explicitNoImport = false)
            runCurrent()
            assertTrue(controller.state.value.catalogRows.isEmpty(),
                "Committed catalog completion did not clear its completed draft manifest")
            assertEquals(listOf(stock(11)), controller.state.value.openingStockRows)
            assertEquals(listOf(float(21)), controller.state.value.openingFloatRows)

            controller.addCatalogReference(token, catalog(2))
            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE, committed = false, code = "setup.catalog.rejected",
                catalogCompletion = gateway.catalogManifestPreview(controller.state.value.catalogRows))
            controller.completeCatalogImport(token, explicitNoImport = false)
            runCurrent()
            assertEquals(listOf(catalog(2)), controller.state.value.catalogRows,
                "A terminal rejection cleared the catalog draft manifest")

            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.OPENING_COMPLETE, committed = true, code = "setup.opening.settled",
                openingCompletion = gateway.openingManifestPreview(
                    controller.state.value.openingStockRows, controller.state.value.openingFloatRows))
            controller.completeOpening(token, stockExplicitZero = false, floatExplicitZero = false)
            runCurrent()
            assertTrue(controller.state.value.openingStockRows.isEmpty())
            assertTrue(controller.state.value.openingFloatRows.isEmpty())
            assertEquals(listOf(catalog(2)), controller.state.value.catalogRows,
                "Committed opening completion cleared the unrelated catalog draft")

            controller.addOpeningStockReference(token, stock(13))
            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.OPENING_COMPLETE, committed = false, code = "setup.opening.rejected",
                openingCompletion = gateway.openingManifestPreview(
                    controller.state.value.openingStockRows, controller.state.value.openingFloatRows))
            controller.completeOpening(token, stockExplicitZero = false, floatExplicitZero = true)
            runCurrent()
            assertEquals(listOf(stock(13)), controller.state.value.openingStockRows,
                "A terminal opening rejection cleared the opening draft manifest")
        }
    }

    @Test
    fun pendingKeepsTheSameOperationAndDraftsAndUnknownCommittedRowIsStorageUncertain() = runTest {
        withController { gateway, controller, token ->
            gateway.checkpoints = checkpointView(catalog = emptyList())
            controller.loadCheckpoints(token)
            runCurrent()

            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.CATALOG_IMPORT_ROW, committed = false, code = "setup.catalog.rejected",
                catalogRow = catalog(5))
            controller.recordCatalogRow(token, catalog(5))
            runCurrent()
            assertTrue(controller.state.value.catalogRows.isEmpty(),
                "A terminal rejected row result added a phantom draft")
            assertNull(controller.state.value.catalogRowOperation)

            gateway.nextMutation = LocalSetupMutationResult.Pending(OperationId.random(),
                LocalSetupOperationKind.CATALOG_IMPORT_ROW)
            controller.recordCatalogRow(token, catalog(5))
            runCurrent()
            val firstOperation = assertNotNull(controller.state.value.catalogRowOperation)
            assertTrue(controller.state.value.catalogRows.isEmpty(),
                "A pending row result added a phantom draft")
            assertEquals(firstOperation, gateway.lastOperation,
                "The pending operation identity was not the dispatched original")

            gateway.nextMutation = LocalSetupMutationResult.Pending(firstOperation,
                LocalSetupOperationKind.CATALOG_IMPORT_ROW)
            controller.resume(token, firstOperation)
            runCurrent()
            assertEquals(firstOperation, controller.state.value.catalogRowOperation,
                "Resuming pending minted or replaced the original operation identity")
            assertTrue(controller.state.value.catalogRows.isEmpty())

            gateway.nextMutation = LocalSetupMutationResult.Recorded(firstOperation,
                LocalSetupOperationKind.CATALOG_IMPORT_ROW, committed = true, code = "setup.catalog.row",
                catalogRow = null)
            controller.resume(token, firstOperation)
            runCurrent()
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, controller.state.value.lastProblem,
                "A committed row without its exact journal reference was treated as a success")
            assertTrue(controller.state.value.catalogRows.isEmpty(),
                "An uncertain committed row reference added a phantom draft")
            assertNull(controller.state.value.catalogRowOperation,
                "A terminal uncertain recovery kept the pending operation field")
        }
    }

    @Test
    fun recoveredOlderCompletionPreservesChangedPartitionsAndClearsOnlyExactMatches() = runTest {
        withController { gateway, controller, token ->
            gateway.checkpoints = checkpointView(
                catalog = listOf(catalog(1)), stock = listOf(stock(11)), float = listOf(float(21)))
            controller.loadCheckpoints(token)
            runCurrent()
            controller.addCatalogReference(token, catalog(2))
            controller.addOpeningStockReference(token, stock(12))
            controller.addOpeningFloatReference(token, float(22))

            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE, committed = true,
                code = "setup.catalog.settled",
                catalogCompletion = LocalSetupManifestPreview(1, "older-input"))
            controller.lookup(token, OperationId.random())
            runCurrent()
            assertEquals(listOf(catalog(1), catalog(2)), controller.state.value.catalogRows,
                "An older recovered catalog completion erased later catalog drafts")

            val olderOpening = gateway.openingManifestPreview(listOf(stock(11)), listOf(float(21)))
            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.OPENING_COMPLETE, committed = true,
                code = "setup.opening.settled", openingCompletion = olderOpening)
            controller.lookup(token, OperationId.random())
            runCurrent()
            assertEquals(listOf(stock(11), stock(12)), controller.state.value.openingStockRows,
                "An older recovered opening completion erased later stock drafts")
            assertEquals(listOf(float(21), float(22)), controller.state.value.openingFloatRows,
                "An older recovered opening completion erased later float drafts")

            val catalogCurrent = gateway.catalogManifestPreview(controller.state.value.catalogRows)
            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE, committed = true,
                code = "setup.catalog.settled", catalogCompletion = catalogCurrent)
            controller.resume(token, OperationId.random())
            runCurrent()
            assertTrue(controller.state.value.catalogRows.isEmpty(),
                "A recovered catalog completion matching the current draft did not clear it")

            val openingCurrent = gateway.openingManifestPreview(
                controller.state.value.openingStockRows, controller.state.value.openingFloatRows)
            val floatOlder = gateway.openingManifestPreview(emptyList(), listOf(float(21))).float
            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.OPENING_COMPLETE, committed = true,
                code = "setup.opening.settled",
                openingCompletion = LocalSetupOpeningManifestPreview(openingCurrent.stock, floatOlder))
            controller.resume(token, OperationId.random())
            runCurrent()
            assertTrue(controller.state.value.openingStockRows.isEmpty(),
                "A matching stock partition was not cleared independently")
            assertEquals(listOf(float(21), float(22)), controller.state.value.openingFloatRows,
                "A changed float partition was cleared by an older recovered completion")

            controller.addOpeningStockReference(token, stock(13))
            val floatCurrent = gateway.openingManifestPreview(
                emptyList(), controller.state.value.openingFloatRows).float
            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.OPENING_COMPLETE, committed = true,
                code = "setup.opening.settled",
                openingCompletion = LocalSetupOpeningManifestPreview(
                    LocalSetupManifestPreview(0, null), floatCurrent))
            controller.lookup(token, OperationId.random())
            runCurrent()
            assertEquals(listOf(stock(13)), controller.state.value.openingStockRows,
                "Explicit zero stock cleared a non-empty stock draft")
            assertTrue(controller.state.value.openingFloatRows.isEmpty(),
                "A matching float partition was not cleared independently of explicit zero stock")

            controller.addCatalogReference(token, catalog(3))
            controller.addOpeningStockReference(token, stock(14))
            controller.addOpeningFloatReference(token, float(23))
            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE, committed = true,
                code = "setup.catalog.settled", catalogCompletion = null)
            controller.lookup(token, OperationId.random())
            runCurrent()
            assertEquals(listOf(catalog(3)), controller.state.value.catalogRows,
                "An unknown catalog completion fingerprint cleared the draft")

            gateway.nextMutation = LocalSetupMutationResult.Recorded(OperationId.random(),
                LocalSetupOperationKind.OPENING_COMPLETE, committed = true,
                code = "setup.opening.settled", openingCompletion = null)
            controller.lookup(token, OperationId.random())
            runCurrent()
            assertEquals(listOf(stock(13), stock(14)), controller.state.value.openingStockRows,
                "An unknown opening completion fingerprint cleared stock drafts")
            assertEquals(listOf(float(23)), controller.state.value.openingFloatRows,
                "An unknown opening completion fingerprint cleared float drafts")
        }
    }

    @Test
    fun optionalCheckpointReadFailureDoesNotEraseRecoveredRowsOrDrafts() = runTest {
        withController { gateway, controller, token ->
            gateway.checkpoints = checkpointView(catalog = listOf(catalog(1)))
            controller.loadCheckpoints(token)
            runCurrent()
            controller.addCatalogReference(token, catalog(2))

            gateway.checkpointProblem = LocalServiceProblem.STORAGE_UNCERTAIN
            controller.loadCheckpoints(token)
            runCurrent()
            assertNull(controller.state.value.checkpoints)
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, controller.state.value.checkpointProblem)
            assertEquals(listOf(catalog(1), catalog(2)), controller.state.value.catalogRows,
                "An optional checkpoint read failure erased retained catalog drafts")
        }
    }

    /** Supporting wiring of frozen STC-S04/S05: private state retires; pending originals
     * survive. This is a controlled presentation slice, not whole-case native qualification. */
    @Test
    fun stcRetirementClearsPrivateSetupProjectionAndRejectsRetiredCallbacks() = runTest {
        withController { gateway, controller, token ->
            gateway.checkpoints = checkpointView(listOf(catalog(1)), listOf(stock(11)), listOf(float(21)))
            controller.loadCheckpoints(token)
            runCurrent()
            assertNotNull(controller.state.value.result)
            assertNotNull(controller.state.value.checkpoints)
            gateway.nextMutation = LocalSetupMutationResult.Pending(OperationId.random(), LocalSetupOperationKind.ACKNOWLEDGE)
            controller.acknowledge(token)
            runCurrent()
            val original = assertNotNull(gateway.lastOperation)
            assertEquals(original, controller.state.value.acknowledgeOperation)

            controller.deactivate()
            assertNull(controller.state.value.result)
            assertNull(controller.state.value.checkpoints)
            assertTrue(controller.state.value.catalogRows.isEmpty())
            assertTrue(controller.state.value.openingStockRows.isEmpty())
            assertTrue(controller.state.value.openingFloatRows.isEmpty())
            assertEquals(original, controller.state.value.acknowledgeOperation)
            controller.addCatalogReference(token, catalog(2))
            controller.goLive(token)
            controller.loadCheckpoints(token)
            runCurrent()
            assertTrue(controller.state.value.catalogRows.isEmpty())
            assertNull(controller.state.value.checkpoints)
            assertEquals(original, gateway.lastOperation, "A retired callback dispatched a replacement")

            controller.close()
            controller.activate(token)
            controller.acknowledge(token)
            runCurrent()
            assertNull(controller.state.value.result)
            assertEquals(original, gateway.lastOperation)
        }
    }

    /** Frozen STC-S04 delayed old events must not reopen or overwrite a successor context. */
    @Test
    fun stcDelayedCancelledReplyCannotPublishIntoReactivatedSetupOrClearItsWork() = runTest {
        withController { gateway, controller, token ->
            val oldReply = CompletableDeferred<Unit>()
            gateway.beforeMutationReturn = { withContext(NonCancellable) { oldReply.await() } }
            gateway.nextMutation = LocalSetupMutationResult.Pending(OperationId.random(), LocalSetupOperationKind.ACKNOWLEDGE)
            controller.acknowledge(token)
            runCurrent()
            val original = assertNotNull(gateway.lastOperation)
            controller.deactivate()
            controller.activate(token)
            runCurrent()

            val freshRead = CompletableDeferred<Unit>()
            gateway.checkpoints = checkpointView(listOf(catalog(3)))
            gateway.beforeCheckpointReturn = { freshRead.await() }
            controller.loadCheckpoints(token)
            runCurrent()
            assertTrue(controller.state.value.busy)
            oldReply.complete(Unit)
            runCurrent()
            assertTrue(controller.state.value.busy, "Old work cleared the successor's busy state")
            assertNull(controller.state.value.lastMessage, "Old pending reply republished private presentation")
            assertNull(controller.state.value.checkpoints)
            assertEquals(original, controller.state.value.acknowledgeOperation)
            freshRead.complete(Unit)
            runCurrent()
            assertEquals(listOf(catalog(3)), controller.state.value.catalogRows)
            assertTrue(!controller.state.value.busy)
            assertEquals(original, gateway.lastOperation)
        }
    }

    /** Frozen STC-S05 stale navigation callbacks cannot acquire a current presentation lifetime. */
    @Test
    fun stcStaleNavigationCannotDispatchSetupOrEditItsManifest() = runTest {
        withControllerAndRuntime { gateway, controller, token, runtime ->
            val guard = assertNotNull(runtime.navigator.registerGuard(token.entryId,
                com.ukkera.pos.app.navigation.LeaveGuard.DIRTY))
            val currentToken = runtime.navigator.token()
            assertTrue(!runtime.navigator.isCurrent(token))
            controller.addCatalogReference(token, catalog(1))
            controller.recordCatalogRow(token, catalog(2))
            runCurrent()
            assertTrue(controller.state.value.catalogRows.isEmpty())
            assertNull(gateway.lastOperation)
            controller.addCatalogReference(currentToken, catalog(3))
            assertEquals(listOf(catalog(3)), controller.state.value.catalogRows)
            runtime.navigator.removeGuard(guard)
        }
    }

    private suspend fun TestScope.withController(
        body: suspend TestScope.(ControlledSetupGateway, LocalSetupClosureController,
            com.ukkera.pos.app.navigation.NavigationToken) -> Unit,
    ) = withControllerAndRuntime { gateway, controller, token, _ -> body(gateway, controller, token) }

    private suspend fun TestScope.withControllerAndRuntime(
        body: suspend TestScope.(ControlledSetupGateway, LocalSetupClosureController,
            com.ukkera.pos.app.navigation.NavigationToken, UkkeraRuntime) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val session = ControlledSetupSession(display())
        val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
            override val configured = true
            override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
                SignInResult.Authenticated(session)
        }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
        try {
            runtime.openSignIn(company = false, token = runtime.navigator.token())
            runtime.signIn("synthetic-setup-presentation", charArrayOf('s'), runtime.navigator.token())
            runCurrent()
            assertEquals(AppArea.SETUP, runtime.navigator.state.value.selectedArea)
            val root = runtime.rootScope
            val id = runtime.navigator.state.value.activeEntry.id
            val provider = ViewModelProvider.create(root.viewModelStore,
                factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } } })
            val entry = provider["entry-$id", EntryViewModel::class]
            val controller = assertNotNull(entry.setupClosure(runtime, runtime.navigator.token()))
            controller.activate(runtime.navigator.token())
            runCurrent()
            body(this, session.gateway, controller, runtime.navigator.token(), runtime)
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    private fun display() = SessionDisplayContext(actorId, companyId, branchId, "Synthetic employee",
        "Synthetic company", "Synthetic branch", setOf(AppArea.SETUP))

    private fun checkpointView(catalog: List<LocalSetupCatalogRow>,
        stock: List<LocalSetupOpeningStockRow> = emptyList(),
        float: List<LocalSetupOpeningFloatRow> = emptyList()): LocalSetupCheckpointView =
        LocalSetupCheckpointView(
            configuration = LocalSetupConfigurationView(Revision(1), listOf("SUPERMARKET"), "EGP",
                "Africa/Cairo", 0, "Synthetic setup", null, true, actorId, recordedAt, OperationId.random()),
            catalogImport = LocalSetupCatalogImportView(false, catalog.size, null, null, actorId, recordedAt,
                OperationId.random()),
            opening = LocalSetupOpeningView(false, stock.size, 0, false, float.size, 0, null, null,
                "EGP", actorId, recordedAt, OperationId.random()),
            retainedRowsLoaded = true,
            retainedCatalogRows = catalog,
            retainedStockRows = stock,
            retainedFloatRows = float,
        )

    private fun catalog(seed: Int) = LocalSetupCatalogRow(id(seed), id(seed + 1000),
        OperationId(id(seed + 2000).value))

    private fun stock(seed: Int) = LocalSetupOpeningStockRow(id(seed), id(seed + 1000),
        OperationId(id(seed + 2000).value))

    private fun float(seed: Int) = LocalSetupOpeningFloatRow(id(seed), id(seed + 1000),
        OperationId(id(seed + 2000).value))

    private fun id(seed: Int) = DomainId("00000000-0000-0000-0000-${seed.toString().padStart(12, '0')}")
}

private class ControlledSetupSession(private val displayValue: SessionDisplayContext) : BranchUiSession {
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(DomainId.random(), 1))
    val gateway = ControlledSetupGateway()
    override val display: SessionDisplayContext get() = displayValue
    override val setupClosure: LocalSetupClosureUiGateway get() = gateway
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override fun isCurrent(): Boolean = attachments.value != null
    override suspend fun current(): LocalSessionCheckResult =
        LocalSessionCheckResult.Current(display, attachments.value)
    override suspend fun mayRestore(route: AppRoute): Boolean = false
    override fun close() { attachments.value = null }
}

private class ControlledSetupGateway : LocalSetupClosureUiGateway {
    var checkpoints: LocalSetupCheckpointView = LocalSetupCheckpointView(null, null, null)
    var checkpointProblem: LocalServiceProblem? = null
    var nextMutation: LocalSetupMutationResult = LocalSetupMutationResult.NotStaged(
        OperationId.random(), LocalServiceProblem.UNAVAILABLE, LocalSetupOperationKind.UNKNOWN)
    var lastOperation: OperationId? = null
    var beforeMutationReturn: (suspend () -> Unit)? = null
    var beforeCheckpointReturn: (suspend () -> Unit)? = null
    override fun availability(): LocalSetupClosureAvailability = LocalSetupClosureAvailability.AVAILABLE
    override suspend fun readResult(): LocalSetupReadResult<LocalSetupResultView> =
        LocalSetupReadResult.Available(resultView())
    override suspend fun readCheckpoints(): LocalSetupReadResult<LocalSetupCheckpointView> {
        val result = checkpointProblem?.let { LocalSetupReadResult.Unavailable(it) }
            ?: LocalSetupReadResult.Available(checkpoints)
        beforeCheckpointReturn?.invoke()
        return result
    }
    override suspend fun pending(): LocalSetupReadResult<List<LocalSetupPendingView>> =
        LocalSetupReadResult.Available(emptyList())
    override suspend fun acknowledge(operationId: OperationId): LocalSetupMutationResult =
        record(operationId)
    override suspend fun goLive(operationId: OperationId): LocalSetupMutationResult = record(operationId)
    override suspend fun configure(operationId: OperationId, input: LocalSetupConfigurationInput): LocalSetupMutationResult =
        record(operationId)
    override suspend fun recordCatalogRow(operationId: OperationId, row: LocalSetupCatalogRow): LocalSetupMutationResult =
        record(operationId)
    override suspend fun completeCatalogImport(operationId: OperationId,
        input: LocalSetupCatalogCompletionInput): LocalSetupMutationResult = record(operationId)
    override suspend fun recordOpeningStockRow(operationId: OperationId, row: LocalSetupOpeningStockRow): LocalSetupMutationResult =
        record(operationId)
    override suspend fun recordOpeningFloatRow(operationId: OperationId, row: LocalSetupOpeningFloatRow): LocalSetupMutationResult =
        record(operationId)
    override suspend fun completeOpening(operationId: OperationId,
        input: LocalSetupOpeningCompletionInput): LocalSetupMutationResult = record(operationId)
    override suspend fun lookup(operationId: OperationId): LocalSetupMutationResult = record(operationId)
    override suspend fun resume(operationId: OperationId): LocalSetupMutationResult = record(operationId)
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult =
        LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
    override fun catalogManifestPreview(rows: List<LocalSetupCatalogRow>): LocalSetupManifestPreview =
        preview("catalog", rows.map {
            "${it.sourceId.value}/${it.skuId.value}/${it.catalogOperationId.value}"
        })
    override fun openingManifestPreview(stock: List<LocalSetupOpeningStockRow>,
        float: List<LocalSetupOpeningFloatRow>): LocalSetupOpeningManifestPreview =
        LocalSetupOpeningManifestPreview(
            preview("stock", stock.map {
                "${it.sourceId.value}/${it.receiptId.value}/${it.openingOperationId.value}"
            }),
            preview("float", float.map {
                "${it.sourceId.value}/${it.sessionId.value}/${it.openingOperationId.value}"
            }))

    private fun preview(prefix: String, values: List<String>): LocalSetupManifestPreview =
        LocalSetupManifestPreview(values.size,
            if (values.isEmpty()) null else "$prefix:${values.sorted().joinToString("|")}")

    private suspend fun record(operationId: OperationId): LocalSetupMutationResult {
        lastOperation = operationId
        val observed = when (val result = nextMutation) {
            is LocalSetupMutationResult.Recorded -> result.copy(operationId = operationId)
            is LocalSetupMutationResult.Pending -> result.copy(operationId = operationId)
            is LocalSetupMutationResult.NotStaged -> result.copy(operationId = operationId)
            is LocalSetupMutationResult.Unresolved -> result.copy(operationId = operationId)
        }
        beforeMutationReturn?.invoke()
        return observed
    }

    private fun resultView(): LocalSetupResultView = LocalSetupResultView(
        original = LocalSetupOriginalView(DomainId.random(), DomainId.random(), DomainId.random(),
            DomainId.random(), DomainId.random(), DomainId.random(), 1, "a".repeat(64), "b".repeat(64),
            "c".repeat(64), Revision(1), Revision(1), UtcInstant.parseCanonical("2026-11-09T12:00:00Z"), true),
        acknowledgement = null,
        goLive = null,
        prerequisites = listOf(LocalSetupPrerequisiteView(
            com.ukkera.pos.app.LocalSetupPrerequisiteKind.REQUIRED_CONFIGURATION, true, "synthetic")),
    )
}
