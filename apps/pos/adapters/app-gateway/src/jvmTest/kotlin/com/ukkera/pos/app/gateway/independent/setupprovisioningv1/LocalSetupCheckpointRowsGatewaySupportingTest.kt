package com.ukkera.pos.app.gateway.independent.setupprovisioningv1

import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalScalarInput
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSetupCatalogRow
import com.ukkera.pos.app.LocalSetupCheckpointView
import com.ukkera.pos.app.LocalSetupClosureAvailability
import com.ukkera.pos.app.LocalSetupConfigurationInput
import com.ukkera.pos.app.LocalSetupManifestPreview
import com.ukkera.pos.app.LocalSetupMutationResult
import com.ukkera.pos.app.LocalSetupOpeningFloatRow
import com.ukkera.pos.app.LocalSetupOpeningManifestPreview
import com.ukkera.pos.app.LocalSetupOpeningStockRow
import com.ukkera.pos.app.LocalSetupOperationKind
import com.ukkera.pos.app.LocalSetupReadResult
import com.ukkera.pos.app.LocalSessionWorkspaceMode
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.LocalBranchDisplayLabels
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.LocalClientIntentJournal
import com.ukkera.pos.app.gateway.LocalEndpointSetupClosureGateway
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialSource
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportCompleteRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportManifestEntryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointCodecV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProfileV1 as Profile
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProjectionV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointResultV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowsPageV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowsV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowKindV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowReferenceV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointTargetV1
import com.ukkera.pos.catalog.store.setup.LocalSetupEnvironmentV1
import com.ukkera.pos.catalog.store.setup.LocalSetupImportModeV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningChoiceV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningCompleteRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOpeningFloatManifestEntryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupOriginalBindingV1
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.setup.LocalSetupResultProfileV1
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Independent JVM qualification of the retained-row gateway aggregation, setup grant staging and
 * journal-derived recovered references.
 *
 * The actual gateway/staging/area mapper and production setup codecs run over controlled transport
 * and journal stand-ins. The stand-ins are synthetic DATA, not a real master transaction, native
 * receipt, current authorization, durable commit or SAS/AOSF/device result.
 */
class LocalSetupCheckpointRowsGatewaySupportingTest {
    private val scope = BranchScope(DomainId.random(), DomainId.random(), DomainId.random())
    private val actorId = DomainId.random()
    private val deviceId = DomainId.random()
    private val connectionId = DomainId.random()
    private val installationId = DomainId.random()
    private val recordedAt = UtcInstant.parseCanonical("2026-11-09T12:00:00Z")
    private val binding = LocalSetupOriginalBindingV1(
        installationId, DomainId.random(), DomainId.random(), 1, PayloadDigest("a".repeat(64)),
        PayloadDigest("b".repeat(64)), PayloadDigest("c".repeat(64)))
    private val owner = LocalClientIdentity(scope, actorId, deviceId)
    private val installation = LocalWorkflowInstallation(
        setOf(LocalSetupResultProfileV1.ACKNOWLEDGE, LocalSetupResultProfileV1.GO_LIVE,
            Profile.CONFIGURE, Profile.CATALOG_IMPORT_ROW, Profile.COMPLETE_CATALOG_IMPORT,
            Profile.OPENING_STOCK_ROW, Profile.OPENING_FLOAT_ROW, Profile.COMPLETE_OPENING),
        setOf(LocalSetupResultProfileV1.READ, Profile.READ, LocalSetupCheckpointRowsV1.READ))

    @Test
    fun setupAreaAdmitsAdministerSettleGrantsButNotPerformOnly() {
        for (action in listOf(SecurityAction.SETUP_CONFIGURATION_SETTLE,
            SecurityAction.SETUP_CATALOG_IMPORT_SETTLE, SecurityAction.SETUP_OPENING_SETTLE)) {
            assertTrue(AppArea.SETUP in areasFor(action, PermissionVerb.ADMINISTER),
                "$action ADMINISTER did not expose the SETUP area")
            assertTrue(AppArea.SETUP !in areasFor(action, PermissionVerb.PERFORM),
                "$action PERFORM alone exposed the SETUP area")
        }
        for (action in listOf(SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE,
            SecurityAction.SETUP_BUSINESS_GO_LIVE, SecurityAction.SETUP_CHECKPOINTS_READ)) {
            assertTrue(AppArea.SETUP in areasFor(action, PermissionVerb.PERFORM),
                "$action PERFORM was incorrectly gated by a settle ADMINISTER grant")
        }
    }

    @Test
    fun administeringSettleStagesTheExactNativeOriginalButPerformingOnlyIsDenied() = runTest {
        val cases: List<Pair<LocalAction, suspend (com.ukkera.pos.app.gateway.LocalEndpointSetupClosureGateway,
            OperationId) -> LocalSetupMutationResult>> = listOf(
            LocalAction.SETUP_CONFIGURATION_RECORD to { gateway, operation ->
                gateway.configure(operation, configurationInput())
            },
            LocalAction.SETUP_CATALOG_IMPORT_ROW to { gateway, operation ->
                gateway.recordCatalogRow(operation, LocalSetupCatalogRow(DomainId.random(),
                    DomainId.random(), OperationId.random()))
            },
            LocalAction.SETUP_OPENING_STOCK_ROW to { gateway, operation ->
                gateway.recordOpeningStockRow(operation, LocalSetupOpeningStockRow(DomainId.random(),
                    DomainId.random(), OperationId.random()))
            },
        )
        for ((action, invoke) in cases) {
            val administer = harness(policy(SecurityAction.SETUP_CATALOG_IMPORT_SETTLE to PermissionVerb.ADMINISTER,
                SecurityAction.SETUP_CONFIGURATION_SETTLE to PermissionVerb.ADMINISTER,
                SecurityAction.SETUP_OPENING_SETTLE to PermissionVerb.ADMINISTER))
            val operation = OperationId.random()
            val recorded = assertIs<LocalSetupMutationResult.Recorded>(invoke(administer.gateway, operation),
                "$action did not stage with the exact ADMINISTER settle grant")
            assertTrue(!recorded.committed)
            val staged = administer.journal.staged.single()
            assertEquals(action.name, staged.action)
            val request = staged.request()
            val target = when (action) {
                LocalAction.SETUP_CONFIGURATION_RECORD ->
                    LocalSetupCheckpointCodecV1.decodeConfigurationRequest(request.payload).target
                LocalAction.SETUP_CATALOG_IMPORT_ROW ->
                    LocalSetupCheckpointCodecV1.decodeCatalogImportRowRequest(request.payload).target
                LocalAction.SETUP_OPENING_STOCK_ROW ->
                    LocalSetupCheckpointCodecV1.decodeOpeningStockRowRequest(request.payload).target
                else -> error("Unexpected settle action")
            }
            assertEquals(binding, target.original)

            val performOnly = harness(policy(
                SecurityAction.SETUP_CONFIGURATION_SETTLE to PermissionVerb.PERFORM,
                SecurityAction.SETUP_CATALOG_IMPORT_SETTLE to PermissionVerb.PERFORM,
                SecurityAction.SETUP_OPENING_SETTLE to PermissionVerb.PERFORM))
            val denied = assertIs<LocalSetupMutationResult.NotStaged>(
                invoke(performOnly.gateway, OperationId.random()))
            assertEquals(LocalServiceProblem.DENIED, denied.problem)
            assertTrue(performOnly.journal.staged.isEmpty(), "$action PERFORM staged an action")
            assertEquals(0, performOnly.endpoint.executeCalls)
        }
    }

    @Test
    fun acknowledgingAndGoingLiveNeedNoSavedResultReadGrant() = runTest {
        val ackHarness = harness(policy(
            SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE to PermissionVerb.PERFORM))
        val acknowledge = assertIs<LocalSetupMutationResult.Recorded>(ackHarness.gateway.acknowledge(OperationId.random()))
        assertEquals(LocalSetupOperationKind.ACKNOWLEDGE, acknowledge.kind)
        assertEquals(LocalSetupResultProfileV1.ACKNOWLEDGE,
            ackHarness.journal.staged.last().request().command)

        val liveHarness = harness(policy(
            SecurityAction.SETUP_BUSINESS_GO_LIVE to PermissionVerb.PERFORM))
        val goLive = assertIs<LocalSetupMutationResult.Recorded>(liveHarness.gateway.goLive(OperationId.random()))
        assertEquals(LocalSetupOperationKind.GO_LIVE, goLive.kind)
        assertEquals(LocalSetupResultProfileV1.GO_LIVE,
            liveHarness.journal.staged.last().request().command)
    }

    @Test
    fun partialInstallationKeepsInstalledAckAndGoLiveAvailableWithoutGrantingOtherActions() = runTest {
        val partial = LocalWorkflowInstallation(setOf(
            LocalSetupResultProfileV1.ACKNOWLEDGE, LocalSetupResultProfileV1.GO_LIVE), emptySet())
        val harness = harness(policy(
            SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE to PermissionVerb.PERFORM,
            SecurityAction.SETUP_BUSINESS_GO_LIVE to PermissionVerb.PERFORM,
            SecurityAction.SETUP_CONFIGURATION_SETTLE to PermissionVerb.ADMINISTER),
            installation = partial)
        assertEquals(LocalSetupClosureAvailability.AVAILABLE, harness.gateway.availability())

        val configure = assertIs<LocalSetupMutationResult.NotStaged>(harness.gateway.configure(
            OperationId.random(), configurationInput()))
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, configure.problem)
        assertTrue(harness.journal.staged.isEmpty(),
            "An uninstalled detail command staged a journal original")
        assertEquals(0, harness.endpoint.executeCalls,
            "An uninstalled detail command reached the master transport")

        val read = assertIs<LocalSetupReadResult.Unavailable>(harness.gateway.readCheckpoints())
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, read.problem)
        assertTrue(harness.endpoint.rowCursors.isEmpty(),
            "The gateway attempted a retained-row read without the installed read capability")

        val acknowledge = assertIs<LocalSetupMutationResult.Recorded>(
            harness.gateway.acknowledge(OperationId.random()))
        assertEquals(LocalSetupOperationKind.ACKNOWLEDGE, acknowledge.kind)
        assertEquals(1, harness.journal.staged.size)

        val liveHarness = harness(policy(
            SecurityAction.SETUP_BUSINESS_GO_LIVE to PermissionVerb.PERFORM),
            installation = partial)
        val goLive = assertIs<LocalSetupMutationResult.Recorded>(
            liveHarness.gateway.goLive(OperationId.random()))
        assertEquals(LocalSetupOperationKind.GO_LIVE, goLive.kind)
        assertEquals(1, liveHarness.journal.staged.size)

        val readOnly = harness(policy(
            SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE to PermissionVerb.PERFORM),
            installation = LocalWorkflowInstallation(emptySet(), setOf(LocalSetupResultProfileV1.READ)))
        assertEquals(LocalSetupClosureAvailability.AVAILABLE, readOnly.gateway.availability())
        val missingCommand = assertIs<LocalSetupMutationResult.NotStaged>(
            readOnly.gateway.acknowledge(OperationId.random()))
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, missingCommand.problem)
        assertTrue(readOnly.journal.staged.isEmpty(),
            "A read-only installation staged an uninstalled ACK command")

        val noAckGrant = harness(policy(
            SecurityAction.SETUP_CONFIGURATION_SETTLE to PermissionVerb.ADMINISTER),
            installation = partial)
        val denied = assertIs<LocalSetupMutationResult.NotStaged>(
            noAckGrant.gateway.acknowledge(OperationId.random()))
        assertEquals(LocalServiceProblem.DENIED, denied.problem)
        assertTrue(noAckGrant.journal.staged.isEmpty(),
            "A missing ACK PERFORM grant staged an original")

        val empty = harness(policy(
            SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE to PermissionVerb.PERFORM),
            installation = LocalWorkflowInstallation(emptySet(), emptySet()),
            expectedAvailability = LocalSetupClosureAvailability.NOT_IMPLEMENTED)
        val unavailable = assertIs<LocalSetupMutationResult.NotStaged>(
            empty.gateway.acknowledge(OperationId.random()))
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, unavailable.problem)
        assertTrue(empty.journal.staged.isEmpty())
    }

    @Test
    fun recoveredCompletionUsesTheRetainedRequestInputFingerprintNotTheEffectDigest() = runTest {
        val catalogRows = (1..2).map {
            LocalSetupCatalogRow(DomainId.random(), DomainId.random(), OperationId.random())
        }
        val entries = catalogRows.map { LocalSetupCatalogImportManifestEntryV1(
            it.sourceId, it.skuId, it.catalogOperationId) }
        val inputDigest = checkNotNull(
            LocalSetupCheckpointCodecV1.catalogInputManifestDigest(entries))
        val operation = OperationId.random()
        val request = LocalSetupCheckpointCodecV1.completeCatalogImport(operation,
            LocalSetupCatalogImportCompleteRequestV1(
                LocalSetupCheckpointTargetV1(binding, LocalSetupEnvironmentV1.LIVE),
                LocalSetupImportModeV1.DECLARED, entries.size, inputDigest))
        val staged = intent(operation, LocalAction.SETUP_CATALOG_IMPORT_COMPLETE, request)
        val effectDigest = PayloadDigest("9".repeat(64))
        val harness = harness(policy(
            SecurityAction.SETUP_CATALOG_IMPORT_SETTLE to PermissionVerb.ADMINISTER),
            journal = SetupJournal(owner, listOf(staged)))
        harness.endpoint.lookupReply = {
            LookupReply.Found(reply(operation, LocalSetupCheckpointCodecV1.encodeResult(
                LocalSetupCheckpointResultV1(Profile.CATALOG_SETTLED_CODE, installationId,
                    manifestDigest = effectDigest)), Profile.CATALOG_SETTLED_CODE))
        }

        val found = assertIs<LocalSetupMutationResult.Recorded>(harness.gateway.lookup(operation))
        assertEquals(LocalSetupManifestPreview(entries.size, inputDigest.value), found.catalogCompletion,
            "Recovered completion did not retain the submitted input fingerprint")
        assertEquals(effectDigest.value, found.manifestDigest)
        assertTrue(found.catalogCompletion!!.digest != found.manifestDigest,
            "The effect-result digest was substituted for the submitted input digest")
    }

    @Test
    fun resumeKeepsExplicitZeroStockAndDeclaredFloatFingerprintsIndependent() = runTest {
        val floatRows = (1..2).map {
            LocalSetupOpeningFloatRow(DomainId.random(), DomainId.random(), OperationId.random())
        }
        val floatEntries = floatRows.map { LocalSetupOpeningFloatManifestEntryV1(
            it.sourceId, it.sessionId, it.openingOperationId) }
        val floatDigest = checkNotNull(
            LocalSetupCheckpointCodecV1.floatInputManifestDigest(floatEntries))
        val operation = OperationId.random()
        val request = LocalSetupCheckpointCodecV1.completeOpening(operation,
            LocalSetupOpeningCompleteRequestV1(
                LocalSetupCheckpointTargetV1(binding, LocalSetupEnvironmentV1.LIVE),
                LocalSetupOpeningChoiceV1.EXPLICIT_ZERO, 0, null,
                LocalSetupOpeningChoiceV1.DECLARED, floatEntries.size, floatDigest))
        val staged = intent(operation, LocalAction.SETUP_OPENING_COMPLETE, request)
        val effectDigest = PayloadDigest("8".repeat(64))
        val harness = harness(policy(
            SecurityAction.SETUP_OPENING_SETTLE to PermissionVerb.ADMINISTER),
            journal = SetupJournal(owner, listOf(staged)))
        harness.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(operation) }
        harness.endpoint.executeReply = {
            reply(operation, LocalSetupCheckpointCodecV1.encodeResult(
                LocalSetupCheckpointResultV1(Profile.OPENING_SETTLED_CODE, installationId,
                    manifestDigest = effectDigest)), Profile.OPENING_SETTLED_CODE)
        }

        val resumed = assertIs<LocalSetupMutationResult.Recorded>(harness.gateway.resume(operation))
        assertEquals(LocalSetupOpeningManifestPreview(
            LocalSetupManifestPreview(0, null),
            LocalSetupManifestPreview(floatEntries.size, floatDigest.value)), resumed.openingCompletion)
        assertEquals(effectDigest.value, resumed.manifestDigest)
        assertTrue(resumed.openingCompletion!!.stock.digest == null &&
            resumed.openingCompletion!!.float.digest != resumed.manifestDigest,
            "Explicit zero or the declared float partition inherited the effect-result digest")
    }

    @Test
    fun readCheckpointsCollectsOrderedPagesWithExactBindingAndNoRepeats() = runTest {
        val catalog = (1..130).map { reference(it) }.sortedBy { it.sourceId.value }
        val stock = listOf(reference(201), reference(202))
        val float = listOf(reference(301))
        val plans = pagePlans(LocalSetupCheckpointRowKindV1.CATALOG, catalog) +
            pagePlans(LocalSetupCheckpointRowKindV1.STOCK, stock) +
            pagePlans(LocalSetupCheckpointRowKindV1.FLOAT, float)
        val harness = harness(policy(SecurityAction.SETUP_CHECKPOINTS_READ to PermissionVerb.PERFORM),
            readPlans = plans)
        val result = assertIs<LocalSetupReadResult.Available<LocalSetupCheckpointView>>(
            harness.gateway.readCheckpoints())
        val view = result.value

        assertTrue(view.retainedRowsLoaded)
        assertNull(view.configuration, "The rows read invented a checkpoint readiness fact")
        assertEquals(catalog.map { LocalSetupCatalogRow(it.sourceId, directResource(it), it.originalOperationId) },
            view.retainedCatalogRows)
        assertEquals(stock.map { LocalSetupOpeningStockRow(it.sourceId, directResource(it), it.originalOperationId) },
            view.retainedStockRows)
        assertEquals(float.map { LocalSetupOpeningFloatRow(it.sourceId, directResource(it), it.originalOperationId) },
            view.retainedFloatRows)
        assertEquals(listOf<DomainId?>(null, catalog[63].sourceId, catalog[127].sourceId),
            harness.endpoint.rowCursors[LocalSetupCheckpointRowKindV1.CATALOG] ?: emptyList<DomainId?>())
    }

    @Test
    fun repeatedNonMonotonicForeignOrOverCapacityPagesMakeTheWholeReadUnavailable() = runTest {
        val one = reference(1)
        val two = reference(2)
        val duplicatePlans = listOf(
            RowPagePlan(LocalSetupCheckpointRowKindV1.CATALOG, null,
                LocalSetupCheckpointRowsPageV1(binding, LocalSetupCheckpointRowKindV1.CATALOG,
                    listOf(one), one.sourceId)),
            RowPagePlan(LocalSetupCheckpointRowKindV1.CATALOG, one.sourceId,
                LocalSetupCheckpointRowsPageV1(binding, LocalSetupCheckpointRowKindV1.CATALOG,
                    listOf(one), null)),
        )
        assertUnavailable(duplicatePlans, "Repeated source page was accepted")

        val nonMonotonicPlans = listOf(
            RowPagePlan(LocalSetupCheckpointRowKindV1.CATALOG, null,
                LocalSetupCheckpointRowsPageV1(binding, LocalSetupCheckpointRowKindV1.CATALOG,
                    listOf(two), two.sourceId)),
            RowPagePlan(LocalSetupCheckpointRowKindV1.CATALOG, two.sourceId,
                LocalSetupCheckpointRowsPageV1(binding, LocalSetupCheckpointRowKindV1.CATALOG,
                    listOf(one), null)),
        )
        assertUnavailable(nonMonotonicPlans, "A page behind its cursor was accepted")

        val foreign = binding.copy(receiptDigest = PayloadDigest("9".repeat(64)))
        val foreignBindingPlans = listOf(
            RowPagePlan(LocalSetupCheckpointRowKindV1.CATALOG, null,
                LocalSetupCheckpointRowsPageV1(binding, LocalSetupCheckpointRowKindV1.CATALOG, listOf(one), null)),
            RowPagePlan(LocalSetupCheckpointRowKindV1.STOCK, null,
                LocalSetupCheckpointRowsPageV1(foreign, LocalSetupCheckpointRowKindV1.STOCK,
                    listOf(reference(2)), null)),
        )
        assertUnavailable(foreignBindingPlans, "A page from another original was accepted")

        val overCapacity = (1..(Profile.MAX_MANIFEST_ROWS + 1)).map { reference(it) }
            .sortedBy { it.sourceId.value }
        assertUnavailable(pagePlans(LocalSetupCheckpointRowKindV1.CATALOG, overCapacity),
            "More than 1024 rows were accepted across pages")
    }

    @Test
    fun lookupAndResumeRestoreTheExactJournalReferenceAndArchiveTheTerminalEntry() = runTest {
        val sourceId = DomainId.random()
        val skuId = DomainId.random()
        val originalOperation = OperationId.random()
        val operationId = OperationId.random()
        val request = LocalSetupCheckpointCodecV1.catalogImportRow(operationId,
            LocalSetupCatalogImportRowRequestV1(
                LocalSetupCheckpointTargetV1(binding, LocalSetupEnvironmentV1.LIVE), sourceId, skuId,
                originalOperation))
        val staged = intent(operationId, LocalAction.SETUP_CATALOG_IMPORT_ROW, request)
        val rowId = Profile.rowId(Profile.CATALOG_IMPORT_ROW_KIND, installationId, sourceId)
        val result = LocalSetupCheckpointCodecV1.encodeResult(LocalSetupCheckpointResultV1(
            Profile.CATALOG_ROW_CODE, installationId, rowId = rowId, rowCount = 1))
        val harness = harness(policy(SecurityAction.SETUP_CATALOG_IMPORT_SETTLE to PermissionVerb.ADMINISTER),
            journal = SetupJournal(owner, listOf(staged)))
        harness.endpoint.lookupReply = { identity ->
            assertEquals(operationId, identity.operationId)
            LookupReply.Found(reply(operationId, result))
        }
        val found = assertIs<LocalSetupMutationResult.Recorded>(harness.gateway.lookup(operationId))
        assertTrue(found.committed)
        assertEquals(LocalSetupOperationKind.CATALOG_IMPORT_ROW, found.kind)
        assertEquals(LocalSetupCatalogRow(sourceId, skuId, originalOperation), found.catalogRow)
        assertTrue(operationId in harness.journal.acknowledged,
            "A committed recovered reference was not archived from the terminal pending set")

        val resumeSource = DomainId.random()
        val resumeSku = DomainId.random()
        val resumeOriginalOperation = OperationId.random()
        val resumeOperation = OperationId.random()
        val resumeStaged = intent(resumeOperation, LocalAction.SETUP_CATALOG_IMPORT_ROW,
            LocalSetupCheckpointCodecV1.catalogImportRow(resumeOperation,
                LocalSetupCatalogImportRowRequestV1(
                    LocalSetupCheckpointTargetV1(binding, LocalSetupEnvironmentV1.LIVE), resumeSource,
                    resumeSku, resumeOriginalOperation)))
        val resumeHarness = harness(policy(SecurityAction.SETUP_CATALOG_IMPORT_SETTLE to PermissionVerb.ADMINISTER),
            journal = SetupJournal(owner, listOf(resumeStaged)))
        val resumeOp = resumeStaged.operationId
        val resumeResult = LocalSetupCheckpointCodecV1.encodeResult(LocalSetupCheckpointResultV1(
            Profile.CATALOG_ROW_CODE, installationId,
            rowId = Profile.rowId(Profile.CATALOG_IMPORT_ROW_KIND, installationId, resumeSource), rowCount = 1))
        resumeHarness.endpoint.lookupReply = {
            LookupReply.AbsentAtCurrentWriter(resumeOp)
        }
        resumeHarness.endpoint.executeReply = {
            reply(resumeOp, resumeResult)
        }
        val resumed = assertIs<LocalSetupMutationResult.Recorded>(resumeHarness.gateway.resume(resumeOp))
        val resumedRequest = resumeHarness.endpoint.executed.single()
        assertEquals(Profile.CATALOG_IMPORT_ROW, resumedRequest.command)
        val resumedSource = LocalSetupCheckpointCodecV1.decodeCatalogImportRowRequest(resumedRequest.payload)
        assertEquals(resumeSource, resumedSource.sourceId)
        assertEquals(resumeSku, resumedSource.skuId)
        assertEquals(resumeOriginalOperation, resumedSource.catalogOperationId)
        assertTrue(resumed.committed)
        assertEquals(LocalSetupCatalogRow(resumeSource, resumeSku, resumeOriginalOperation), resumed.catalogRow)
    }

    private data class RowPagePlan(
        val kind: LocalSetupCheckpointRowKindV1,
        val cursor: DomainId?,
        val page: LocalSetupCheckpointRowsPageV1,
    )

    private data class Harness(
        val gateway: LocalEndpointSetupClosureGateway,
        val endpoint: ControlledSetupEndpoint,
        val journal: SetupJournal,
    )

    private fun harness(policy: ActivatedLocalPolicy, readPlans: List<RowPagePlan> = emptyList(),
        journal: SetupJournal = SetupJournal(owner),
        installation: LocalWorkflowInstallation = this.installation,
        expectedAvailability: LocalSetupClosureAvailability = LocalSetupClosureAvailability.AVAILABLE): Harness {
        val endpoint = ControlledSetupEndpoint(policy, scope, actorId, deviceId, connectionId, recordedAt)
        endpoint.readPlans = readPlans
        val credentials = LocalWorkflowCredentialSource {
            LocalWorkflowCredentialLease(LocalGatewayCredentials(connectionId,
                OpaqueCredential(ByteArray(32) { 7 })), { true })
        }
        val gateway = LocalEndpointSetupClosureGateway(endpoint, journal, credentials, installation,
            installationId, binding)
        assertEquals(expectedAvailability, gateway.availability())
        return Harness(gateway, endpoint, journal)
    }

    private fun policy(vararg grants: Pair<SecurityAction, PermissionVerb>): ActivatedLocalPolicy =
        ActivatedLocalPolicy(scope, actorId, Revision(1), grants.map { (action, verb) ->
            PermissionGrant(Permission(action, verb), scope, GrantEffect.ALLOW, PermissionLimits(),
                OwnerAccess.OWN_ONLY, actorId, null, null)
        }, LocalSessionPolicy(), ThrottlePolicy())

    @Suppress("UNCHECKED_CAST")
    private fun areasFor(action: SecurityAction, verb: PermissionVerb): Set<AppArea> {
        val owner = Class.forName("com.ukkera.pos.app.gateway.LocalEndpointBranchGatewayKt")
        val mapper = owner.getDeclaredMethod("display", ActivatedLocalPolicy::class.java,
            LocalLoginIdentifier::class.java, LocalBranchDisplayLabels::class.java, java.lang.Boolean.TYPE,
            LocalSessionWorkspaceMode::class.java)
        mapper.isAccessible = true
        return (mapper.invoke(null, policy(action to verb), LocalLoginIdentifier("synthetic-setup"),
            LocalBranchDisplayLabels(scope, "Synthetic company", "Synthetic branch"), false,
            LocalSessionWorkspaceMode.POLICY_FILTERED) as com.ukkera.pos.app.SessionDisplayContext).allowedAreas
    }

    private fun reference(seed: Int): LocalSetupCheckpointRowReferenceV1 =
        LocalSetupCheckpointRowReferenceV1(DomainId("00000000-0000-0000-0000-${seed.toString().padStart(12, '0')}"),
            DomainId.random(), OperationId.random())

    private fun directResource(reference: LocalSetupCheckpointRowReferenceV1): DomainId =
        reference.resourceId

    private fun pagePlans(kind: LocalSetupCheckpointRowKindV1,
        rows: List<LocalSetupCheckpointRowReferenceV1>): List<RowPagePlan> {
        val sorted = rows.sortedBy { it.sourceId.value }
        val chunks = sorted.chunked(LocalSetupCheckpointRowsV1.PAGE_SIZE)
        val plans = mutableListOf<RowPagePlan>()
        var cursor: DomainId? = null
        for ((index, chunk) in chunks.withIndex()) {
            val next = if (index < chunks.lastIndex) chunk.last().sourceId else null
            plans += RowPagePlan(kind, cursor,
                LocalSetupCheckpointRowsPageV1(binding, kind, chunk, next))
            cursor = next
        }
        return plans
    }

    private suspend fun assertUnavailable(plans: List<RowPagePlan>, message: String) {
        val harness = harness(policy(SecurityAction.SETUP_CHECKPOINTS_READ to PermissionVerb.PERFORM),
            readPlans = plans)
        val result = harness.gateway.readCheckpoints()
        assertIs<LocalSetupReadResult.Unavailable>(result, message)
    }

    private fun configurationInput() = LocalSetupConfigurationInput(
        expectedRevision = Revision(0), currencyCode = "EGP", timeZone = "Africa/Cairo",
        businessDayCutoffMinute = 0, legalName = "Synthetic setup", taxRegistrationId = null,
        taxRegistrationAbsent = true)

    private fun intent(operationId: OperationId, action: LocalAction, request: MutationRequest): StoredClientIntentV1 =
        StoredClientIntentV1.staged(owner, LocalCommandIntent(operationId, action, 1, null,
            listOf(LocalScalarInput(LocalField.SETUP_BODY_DIGEST,
                LocalScalarValue.Text(sha256(request.payload.copyBytes()).value)))), request,
            LocalText("Synthetic setup operation", "عملية إعداد اصطناعية"),
            listOf(LocalRecordValue(LocalText("Installation", "التثبيت"),
                LocalText(installationId.value, installationId.value))))

    private fun reply(operationId: OperationId, result: com.ukkera.pos.database.StoredBytes,
        code: String = Profile.CATALOG_ROW_CODE) =
        MutationReply.Recorded(operationId, actorId, true, code, result,
            Revision(1), JournalSequence(1), recordedAt, false)

    private class SetupJournal(owner: LocalClientIdentity,
        private val entries: List<StoredClientIntentV1> = emptyList()) : LocalClientIntentJournal(owner) {
        val staged = mutableListOf<StoredClientIntentV1>()
        val remembered = mutableListOf<OperationId>()
        val acknowledged = mutableListOf<OperationId>()
        override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
            JournalRead.Value((entries + staged).lastOrNull { it.operationId == operationId })
        override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> =
            JournalRead.Value((entries + staged).filter { it.recorded == null && !it.acknowledged })
        override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
            staged += intent
            return JournalWrite.DURABLE
        }
        override suspend fun markDispatched(operationId: OperationId): JournalWrite = JournalWrite.DURABLE
        override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
            remembered += operationId
            return JournalWrite.DURABLE
        }
        override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
            acknowledged += operationId
            return JournalWrite.DURABLE
        }
        override suspend fun close() = Unit
    }

    private class ControlledSetupEndpoint(
        private val policy: ActivatedLocalPolicy,
        private val scope: BranchScope,
        private val actorId: DomainId,
        private val deviceId: DomainId,
        connectionId: DomainId,
        private val recordedAt: UtcInstant,
    ) : LocalMasterClient {
        private val healthState = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
        private val view = LocalSessionView(actorId, scope, deviceId, DomainId.random(), SessionGeneration(1),
            LocalLoginIdentifier("synthetic-setup"), policy,
            MonotonicMoment(DomainId.random(), 10_000), MonotonicMoment(DomainId.random(), 60_000))
        override val health: StateFlow<LocalMasterEndpointHealth> get() = healthState
        var readPlans: List<RowPagePlan> = emptyList()
        val rowCursors = mutableMapOf<LocalSetupCheckpointRowKindV1, MutableList<DomainId?>>()
        val executed = mutableListOf<MutationRequest>()
        val executeCalls: Int get() = executed.size

        override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
            explicitDeviceMove: Boolean): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable

        override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult =
            LocalAuthenticationResult.Unavailable

        override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
            LocalSessionViewResult.Current(view)

        override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult =
            LocalSignOutResult.Unavailable

        override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
            executed += request
            return executeReply(request)
        }

        override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply =
            lookupReply(identity)

        var lookupReply: suspend (OperationIdentity) -> LookupReply = {
            LookupReply.AbsentAtCurrentWriter(it.operationId)
        }
        var executeReply: suspend (MutationRequest) -> MutationReply = {
            MutationReply.Recorded(it.operationId, actorId, false, "setup.test.rejected",
                StoredBytes("{}".encodeToByteArray()), Revision(1), JournalSequence(1), recordedAt, false)
        }

        override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
            return when (request.query) {
                Profile.READ -> ReadReply.Data(
                    LocalSetupCheckpointCodecV1.encodeProjection(LocalSetupCheckpointProjectionV1()),
                    Revision(1), recordedAt)
                LocalSetupCheckpointRowsV1.READ -> {
                    val query = LocalSetupCheckpointRowsV1.decodeQuery(request.payload)
                    rowCursors.getOrPut(query.kind) { mutableListOf() } += query.afterSourceId
                    val plan = readPlans.singleOrNull {
                        it.kind == query.kind && it.cursor == query.afterSourceId
                    } ?: return ReadReply.Unavailable(ReadFailure.MALFORMED_REQUEST)
                    ReadReply.Data(LocalSetupCheckpointRowsV1.encodePage(plan.page), Revision(1), recordedAt)
                }
                else -> ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY)
            }
        }
    }
}
