package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalAcknowledgementResult
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalScalarInput
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSetupAcknowledgementView
import com.ukkera.pos.app.LocalSetupCatalogCompletionInput
import com.ukkera.pos.app.LocalSetupCatalogImportView
import com.ukkera.pos.app.LocalSetupCatalogRow
import com.ukkera.pos.app.LocalSetupCheckpointView
import com.ukkera.pos.app.LocalSetupClosureAvailability
import com.ukkera.pos.app.LocalSetupClosureUiGateway
import com.ukkera.pos.app.LocalSetupConfigurationInput
import com.ukkera.pos.app.LocalSetupConfigurationView
import com.ukkera.pos.app.LocalSetupGoLiveView
import com.ukkera.pos.app.LocalSetupManifestPreview
import com.ukkera.pos.app.LocalSetupMutationResult
import com.ukkera.pos.app.LocalSetupOpeningCompletionInput
import com.ukkera.pos.app.LocalSetupOpeningFloatRow
import com.ukkera.pos.app.LocalSetupOpeningManifestPreview
import com.ukkera.pos.app.LocalSetupOpeningStockRow
import com.ukkera.pos.app.LocalSetupOpeningView
import com.ukkera.pos.app.LocalSetupOperationKind
import com.ukkera.pos.app.LocalSetupOriginalView
import com.ukkera.pos.app.LocalSetupPendingView
import com.ukkera.pos.app.LocalSetupPrerequisiteKind
import com.ukkera.pos.app.LocalSetupPrerequisiteView
import com.ukkera.pos.app.LocalSetupReadResult
import com.ukkera.pos.app.LocalSetupResultView
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportCompleteRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportManifestEntryV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCatalogImportRowRequestV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointCodecV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowsV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowKindV1
import com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointRowReferenceV1
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
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.setup.LocalSetupResultCodecV1
import com.ukkera.pos.database.setup.LocalSetupResultProfileV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock

/**
 * Actual full-business adapter for the installed setup-result/checkpoint grammar. The client
 * journal retains every original request body/id before dispatch; recovery can only look up or
 * resume that same body. The master derives authority, actor, time and effect references.
 */
internal class LocalEndpointSetupClosureGateway(
    private val endpoint: com.ukkera.pos.database.LocalMasterClient,
    private val journal: LocalClientIntentJournal,
    private val credentials: LocalWorkflowCredentialSource,
    private val installed: LocalWorkflowInstallation,
    private val installationId: DomainId?,
    private val nativeOriginalBinding: LocalSetupOriginalBindingV1? = null,
) : LocalSetupClosureUiGateway {
    private val requiredCommands = setOf(
        LocalSetupResultProfileV1.ACKNOWLEDGE,
        LocalSetupResultProfileV1.GO_LIVE,
        LocalSetupCheckpointProfileV1.CONFIGURE,
        LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW,
        LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT,
        LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW,
        LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW,
        LocalSetupCheckpointProfileV1.COMPLETE_OPENING,
    )
    private val requiredReads = setOf(
        LocalSetupResultProfileV1.READ,
        LocalSetupCheckpointProfileV1.READ,
        LocalSetupCheckpointRowsV1.READ,
    )
    private val setupActions = setOf(
        com.ukkera.pos.app.LocalAction.SETUP_RESULT_ACKNOWLEDGE,
        com.ukkera.pos.app.LocalAction.SETUP_BUSINESS_GO_LIVE,
        com.ukkera.pos.app.LocalAction.SETUP_CONFIGURATION_RECORD,
        com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_ROW,
        com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_COMPLETE,
        com.ukkera.pos.app.LocalAction.SETUP_OPENING_STOCK_ROW,
        com.ukkera.pos.app.LocalAction.SETUP_OPENING_FLOAT_ROW,
        com.ukkera.pos.app.LocalAction.SETUP_OPENING_COMPLETE,
    )

    override fun availability(): LocalSetupClosureAvailability {
        if (installationId == null) return LocalSetupClosureAvailability.NOT_CONFIGURED
        // Installation is a capability set. Each operation checks its own command/read;
        // a missing optional read must not disable an independently installed mutation.
        if (requiredCommands.none { installed.has(it) } && requiredReads.none { installed.has(it) })
            return LocalSetupClosureAvailability.NOT_IMPLEMENTED
        return LocalSetupClosureAvailability.AVAILABLE
    }

    override suspend fun readResult(): LocalSetupReadResult<LocalSetupResultView> = journal.requests.withLock {
        read { access ->
            val data = access.read(LocalSetupResultCodecV1.read(requireInstallation()))
            LocalSetupResultCodecV1.decodeProjection(data.payload).toUi()
        }
    }

    override suspend fun readCheckpoints(): LocalSetupReadResult<LocalSetupCheckpointView> = journal.requests.withLock {
        read { access ->
            val data = access.read(LocalSetupCheckpointCodecV1.read(requireInstallation()))
            val projection = LocalSetupCheckpointCodecV1.decodeProjection(data.payload)
            var originalBinding = projection.configuration?.binding ?: projection.catalogImport?.binding ?: projection.opening?.binding
            suspend fun rows(kind: LocalSetupCheckpointRowKindV1): List<LocalSetupCheckpointRowReferenceV1> {
                val collected = mutableListOf<LocalSetupCheckpointRowReferenceV1>()
                var cursor: DomainId? = null
                do {
                    val page = LocalSetupCheckpointRowsV1.decodePage(access.read(
                        LocalSetupCheckpointRowsV1.read(requireInstallation(), kind, cursor)).payload)
                    require(page.kind == kind && page.binding.installationId == requireInstallation())
                    if (originalBinding == null) originalBinding = page.binding
                    require(page.binding == originalBinding) { "Setup original changed while reading retained rows" }
                    require(page.rows.all { cursor == null || it.sourceId.value > cursor!!.value })
                    collected += page.rows
                    require(collected.size <= com.ukkera.pos.app.LOCAL_SETUP_MAX_MANIFEST_ROWS &&
                        collected.distinctBy { it.sourceId }.size == collected.size)
                    cursor = page.nextSourceId
                    require(cursor == null || page.rows.isNotEmpty())
                } while (cursor != null)
                return collected
            }
            val catalog = rows(LocalSetupCheckpointRowKindV1.CATALOG)
            val stock = rows(LocalSetupCheckpointRowKindV1.STOCK)
            val float = rows(LocalSetupCheckpointRowKindV1.FLOAT)
            // Pages are observations, not a multi-page readiness snapshot. The master verifies
            // the final count/digest and current source effects again at completion/go-live.
            projection.toUi().copy(
                retainedRowsLoaded = true,
                retainedCatalogRows = catalog.map { LocalSetupCatalogRow(it.sourceId, it.resourceId, it.originalOperationId) },
                retainedStockRows = stock.map { LocalSetupOpeningStockRow(it.sourceId, it.resourceId, it.originalOperationId) },
                retainedFloatRows = float.map { LocalSetupOpeningFloatRow(it.sourceId, it.resourceId, it.originalOperationId) },
            )
        }
    }

    override suspend fun pending(): LocalSetupReadResult<List<LocalSetupPendingView>> = journal.requests.withLock {
        if (availability() != LocalSetupClosureAvailability.AVAILABLE)
            return@withLock LocalSetupReadResult.Unavailable(availabilityProblem())
        try {
            withAccess { access ->
                val entries = required(journal.pending()).filter { it.localAction in setupActions }
                entries.forEach(access::requireOriginal)
                LocalSetupReadResult.Available(entries.map { it.toPendingView() })
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GatewayFailure) {
            LocalSetupReadResult.Unavailable(failure.problem)
        } catch (_: Exception) {
            LocalSetupReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
        }
    }

    override suspend fun acknowledge(operationId: OperationId): LocalSetupMutationResult =
        mutate(operationId, com.ukkera.pos.app.LocalAction.SETUP_RESULT_ACKNOWLEDGE) {
            LocalSetupResultCodecV1.acknowledge(operationId, requireInstallation())
        }

    override suspend fun goLive(operationId: OperationId): LocalSetupMutationResult =
        mutate(operationId, com.ukkera.pos.app.LocalAction.SETUP_BUSINESS_GO_LIVE) {
            LocalSetupResultCodecV1.goLive(operationId, requireInstallation())
        }

    override suspend fun configure(operationId: OperationId, input: LocalSetupConfigurationInput): LocalSetupMutationResult =
        mutate(operationId, com.ukkera.pos.app.LocalAction.SETUP_CONFIGURATION_RECORD) { access ->
            val request = LocalSetupConfigurationRequestV1(
                target = target(binding()),
                expectedRevision = input.expectedRevision,
                currency = Currency.EGP,
                timeZone = input.timeZone,
                businessDayCutoffMinute = input.businessDayCutoffMinute,
                fiscalIdentity = LocalSetupFiscalIdentityV1(
                    legalName = input.legalName,
                    taxRegistrationId = input.taxRegistrationId,
                    taxRegistrationAbsent = input.taxRegistrationAbsent,
                ),
            )
            LocalSetupCheckpointCodecV1.configure(operationId, request)
        }

    override suspend fun recordCatalogRow(operationId: OperationId, row: LocalSetupCatalogRow): LocalSetupMutationResult =
        mutate(operationId, com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_ROW) { access ->
            val request = LocalSetupCatalogImportRowRequestV1(
                target = target(binding()),
                sourceId = row.sourceId,
                skuId = row.skuId,
                catalogOperationId = row.catalogOperationId,
            )
            LocalSetupCheckpointCodecV1.catalogImportRow(operationId, request)
        }

    override suspend fun completeCatalogImport(operationId: OperationId,
        input: LocalSetupCatalogCompletionInput): LocalSetupMutationResult =
        mutate(operationId, com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_COMPLETE) { access ->
            require(input.rows.distinctBy { it.sourceId }.size == input.rows.size) { "Duplicate catalog source identity" }
            require(input.explicitNoImport == input.rows.isEmpty()) { "Explicit no-import is not an empty manifest" }
            val entries = input.rows.map {
                LocalSetupCatalogImportManifestEntryV1(it.sourceId, it.skuId, it.catalogOperationId)
            }
            val request = LocalSetupCatalogImportCompleteRequestV1(
                target = target(binding()),
                mode = if (input.explicitNoImport) LocalSetupImportModeV1.EXPLICIT_NO_IMPORT else LocalSetupImportModeV1.DECLARED,
                expectedRowCount = entries.size,
                expectedManifestDigest = if (input.explicitNoImport) null
                    else LocalSetupCheckpointCodecV1.catalogInputManifestDigest(entries),
            )
            LocalSetupCheckpointCodecV1.completeCatalogImport(operationId, request)
        }

    override suspend fun recordOpeningStockRow(operationId: OperationId, row: LocalSetupOpeningStockRow): LocalSetupMutationResult =
        mutate(operationId, com.ukkera.pos.app.LocalAction.SETUP_OPENING_STOCK_ROW) { access ->
            val request = LocalSetupOpeningStockRowRequestV1(
                target = target(binding()),
                sourceId = row.sourceId,
                receiptId = row.receiptId,
                openingOperationId = row.openingOperationId,
            )
            LocalSetupCheckpointCodecV1.openingStockRow(operationId, request)
        }

    override suspend fun recordOpeningFloatRow(operationId: OperationId, row: LocalSetupOpeningFloatRow): LocalSetupMutationResult =
        mutate(operationId, com.ukkera.pos.app.LocalAction.SETUP_OPENING_FLOAT_ROW) { access ->
            val request = LocalSetupOpeningFloatRowRequestV1(
                target = target(binding()),
                sourceId = row.sourceId,
                sessionId = row.sessionId,
                openingOperationId = row.openingOperationId,
            )
            LocalSetupCheckpointCodecV1.openingFloatRow(operationId, request)
        }

    override suspend fun completeOpening(operationId: OperationId, input: LocalSetupOpeningCompletionInput): LocalSetupMutationResult =
        mutate(operationId, com.ukkera.pos.app.LocalAction.SETUP_OPENING_COMPLETE) { access ->
            require(input.stockRows.distinctBy { it.sourceId }.size == input.stockRows.size) { "Duplicate opening stock source identity" }
            require(input.floatRows.distinctBy { it.sourceId }.size == input.floatRows.size) { "Duplicate opening float source identity" }
            require(input.stockExplicitZero == input.stockRows.isEmpty()) { "Explicit zero stock is not an empty manifest" }
            require(input.floatExplicitZero == input.floatRows.isEmpty()) { "Explicit zero float is not an empty manifest" }
            val stockEntries = input.stockRows.map {
                LocalSetupOpeningStockManifestEntryV1(it.sourceId, it.receiptId, it.openingOperationId)
            }
            val floatEntries = input.floatRows.map {
                LocalSetupOpeningFloatManifestEntryV1(it.sourceId, it.sessionId, it.openingOperationId)
            }
            val request = LocalSetupOpeningCompleteRequestV1(
                target = target(binding()),
                stockChoice = if (input.stockExplicitZero) LocalSetupOpeningChoiceV1.EXPLICIT_ZERO else LocalSetupOpeningChoiceV1.DECLARED,
                expectedStockRowCount = stockEntries.size,
                expectedStockManifestDigest = if (input.stockExplicitZero) null
                    else LocalSetupCheckpointCodecV1.stockInputManifestDigest(stockEntries),
                floatChoice = if (input.floatExplicitZero) LocalSetupOpeningChoiceV1.EXPLICIT_ZERO else LocalSetupOpeningChoiceV1.DECLARED,
                expectedFloatRowCount = floatEntries.size,
                expectedFloatManifestDigest = if (input.floatExplicitZero) null
                    else LocalSetupCheckpointCodecV1.floatInputManifestDigest(floatEntries),
            )
            LocalSetupCheckpointCodecV1.completeOpening(operationId, request)
        }

    override suspend fun lookup(operationId: OperationId): LocalSetupMutationResult = journal.requests.withLock {
        recover(operationId, dispatch = false)
    }

    override suspend fun resume(operationId: OperationId): LocalSetupMutationResult = journal.requests.withLock {
        recover(operationId, dispatch = true)
    }

    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult = journal.requests.withLock {
        try {
            withAccess { access ->
                val entry = required(journal.find(operationId))
                    ?: return@withAccess LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
                if (entry.localAction !in setupActions) return@withAccess LocalAcknowledgementResult.Unresolved(
                    operationId, LocalServiceProblem.NOT_IMPLEMENTED)
                val retained = entry.recorded ?: return@withAccess LocalAcknowledgementResult.Unresolved(
                    operationId, LocalServiceProblem.UNAVAILABLE)
                val found = access.lookup(entry)
                if (found !is LookupReply.Found || found.result.operationId != operationId ||
                    found.result.originalActorId != journal.owner.actorId)
                    return@withAccess LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
                val actual = StoredKernelResultV1.from(found.result)
                if (actual != retained) return@withAccess LocalAcknowledgementResult.Unresolved(
                    operationId, LocalServiceProblem.STORAGE_UNCERTAIN)
                access.checkCurrent()
                if (journal.acknowledge(operationId, actual) != JournalWrite.DURABLE)
                    LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.STORAGE_UNCERTAIN)
                else LocalAcknowledgementResult.Acknowledged(operationId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GatewayFailure) {
            LocalAcknowledgementResult.Unresolved(operationId, failure.problem)
        } catch (_: Exception) {
            LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
        }
    }

    override fun catalogManifestPreview(rows: List<LocalSetupCatalogRow>): LocalSetupManifestPreview {
        val entries = rows.map {
            LocalSetupCatalogImportManifestEntryV1(it.sourceId, it.skuId, it.catalogOperationId)
        }
        return LocalSetupManifestPreview(entries.size, LocalSetupCheckpointCodecV1.catalogInputManifestDigest(entries)?.value)
    }

    override fun openingManifestPreview(stock: List<LocalSetupOpeningStockRow>,
        float: List<LocalSetupOpeningFloatRow>): LocalSetupOpeningManifestPreview {
        val stockEntries = stock.map {
            LocalSetupOpeningStockManifestEntryV1(it.sourceId, it.receiptId, it.openingOperationId)
        }
        val floatEntries = float.map {
            LocalSetupOpeningFloatManifestEntryV1(it.sourceId, it.sessionId, it.openingOperationId)
        }
        return LocalSetupOpeningManifestPreview(
            LocalSetupManifestPreview(stockEntries.size,
                LocalSetupCheckpointCodecV1.stockInputManifestDigest(stockEntries)?.value),
            LocalSetupManifestPreview(floatEntries.size,
                LocalSetupCheckpointCodecV1.floatInputManifestDigest(floatEntries)?.value),
        )
    }

    private suspend fun <T> read(block: suspend (GatewayAccess) -> T): LocalSetupReadResult<T> {
        if (availability() != LocalSetupClosureAvailability.AVAILABLE)
            return LocalSetupReadResult.Unavailable(availabilityProblem())
        return try {
            withAccess { access -> LocalSetupReadResult.Available(block(access)) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GatewayFailure) {
            LocalSetupReadResult.Unavailable(failure.problem)
        } catch (_: Exception) {
            LocalSetupReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
        }
    }

    private suspend fun mutate(operationId: OperationId, action: com.ukkera.pos.app.LocalAction,
        build: suspend (GatewayAccess) -> MutationRequest): LocalSetupMutationResult = journal.requests.withLock {
        if (availability() != LocalSetupClosureAvailability.AVAILABLE)
            return@withLock LocalSetupMutationResult.NotStaged(operationId, availabilityProblem(), kind(action))
        // Set only after the exact journal write is attempted. Before that point a refusal
        // definitively leaves no retained original for this ID.
        var stageAttempted = false
        try {
            withAccess { access ->
                val existing = required(journal.find(operationId))
                if (existing != null) {
                    stageAttempted = true
                    if (existing.localAction != action) return@withAccess LocalSetupMutationResult.Unresolved(
                        operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind(action))
                    drive(access, existing, dispatch = true)
                } else {
                    // Other workflows recover their own pending originals. A setup mutation admits
                    // a new original only when no setup original is still unresolved here.
                    if (required(journal.pending()).any { it.localAction in setupActions }) {
                        return@withAccess LocalSetupMutationResult.NotStaged(
                            operationId, LocalServiceProblem.RETRY_LATER, kind(action))
                    }
                    val request = build(access)
                    if (!installed.has(request.command)) {
                        return@withAccess LocalSetupMutationResult.NotStaged(
                            operationId, LocalServiceProblem.NOT_IMPLEMENTED, kind(action))
                    }
                    val permission = setupPermission(action)
                    if (!access.hasHint(com.ukkera.pos.security.Permission(permission,
                            setupPermissionVerb(action)), requireInstallation()))
                        return@withAccess LocalSetupMutationResult.NotStaged(
                            operationId, LocalServiceProblem.DENIED, kind(action))
                    val digest = request.payload.copyBytes().let { bytes ->
                        try { sha256(bytes).value } finally { bytes.fill(0) }
                    }
                    val intent = LocalCommandIntent(
                        operationId = operationId,
                        action = action,
                        formVersion = 1,
                        reference = null,
                        values = listOf(LocalScalarInput(LocalField.SETUP_BODY_DIGEST, LocalScalarValue.Text(digest))),
                    )
                    val staged = StoredClientIntentV1.staged(journal.owner, intent, request,
                        actionLabel(action), listOf(LocalRecordValue(
                            LocalText("Installation", "التثبيت"),
                            LocalText(requireInstallation().value, requireInstallation().value))))
                    stageAttempted = true
                    when (journal.stage(staged)) {
                        JournalWrite.DURABLE -> Unit
                        JournalWrite.NO_CAPACITY -> return@withAccess LocalSetupMutationResult.NotStaged(
                            operationId, LocalServiceProblem.RETRY_LATER, kind(action))
                        JournalWrite.IDENTITY_CONFLICT -> return@withAccess LocalSetupMutationResult.Unresolved(
                            operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind(action))
                        JournalWrite.UNAVAILABLE -> return@withAccess LocalSetupMutationResult.Unresolved(
                            operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind(action))
                    }
                    drive(access, staged, dispatch = true)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GatewayFailure) {
            if (stageAttempted) LocalSetupMutationResult.Unresolved(operationId, failure.problem, kind(action))
            else LocalSetupMutationResult.NotStaged(operationId, failure.problem, kind(action))
        } catch (_: IllegalArgumentException) {
            if (stageAttempted) LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.INVALID_INPUT, kind(action))
            else LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.INVALID_INPUT, kind(action))
        } catch (_: Exception) {
            if (stageAttempted) LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind(action))
            else LocalSetupMutationResult.NotStaged(operationId, LocalServiceProblem.UNAVAILABLE, kind(action))
        }
    }

    private suspend fun recover(operationId: OperationId, dispatch: Boolean): LocalSetupMutationResult {
        if (availability() != LocalSetupClosureAvailability.AVAILABLE)
            return LocalSetupMutationResult.Unresolved(operationId, availabilityProblem())
        return try {
            withAccess { access ->
                val entry = required(journal.find(operationId))
                    ?: return@withAccess LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.UNAVAILABLE)
                if (entry.localAction !in setupActions)
                    return@withAccess LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
                drive(access, entry, dispatch)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: GatewayFailure) {
            LocalSetupMutationResult.Unresolved(operationId, failure.problem)
        } catch (_: Exception) {
            LocalSetupMutationResult.Unresolved(operationId, LocalServiceProblem.STORAGE_UNCERTAIN)
        }
    }

    private suspend fun drive(access: GatewayAccess, entry: StoredClientIntentV1, dispatch: Boolean): LocalSetupMutationResult {
        val kind = kind(entry.localAction)
        return when (val found = access.lookup(entry)) {
            is LookupReply.Found -> publish(entry, found.result)
            is LookupReply.AbsentAtCurrentWriter -> {
                if (found.operationId != entry.operationId || entry.recorded != null || entry.acknowledged)
                    return LocalSetupMutationResult.Unresolved(entry.operationId, LocalServiceProblem.UNAVAILABLE, kind)
                if (!dispatch) return LocalSetupMutationResult.Pending(entry.operationId, kind)
                if (!canContinue(entry)) return LocalSetupMutationResult.Unresolved(
                    entry.operationId, LocalServiceProblem.RETRY_LATER, kind)
                if (journal.markDispatched(entry.operationId) != JournalWrite.DURABLE)
                    return LocalSetupMutationResult.Unresolved(entry.operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind)
                access.checkCurrent()
                when (val reply = access.execute(entry, entry.request())) {
                    is MutationReply.Recorded -> publish(entry, reply)
                    is MutationReply.Deferred -> LocalSetupMutationResult.Unresolved(entry.operationId,
                        if (reply.operationId == entry.operationId) problem(reply.reason)
                        else LocalServiceProblem.STORAGE_UNCERTAIN, kind)
                    is MutationReply.IdentityConflict -> LocalSetupMutationResult.Unresolved(
                        entry.operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind)
                }
            }
            is LookupReply.Deferred -> LocalSetupMutationResult.Unresolved(entry.operationId,
                if (found.operationId == entry.operationId) problem(found.reason)
                else LocalServiceProblem.UNAVAILABLE, kind)
            is LookupReply.IdentityConflict -> LocalSetupMutationResult.Unresolved(
                entry.operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind)
        }
    }

    private suspend fun retainForTransportReceipt(access: GatewayAccess, entry: StoredClientIntentV1,
        reply: MutationReply.Recorded) {
        access.checkCurrent()
        if (reply.operationId != entry.operationId || reply.originalActorId != journal.owner.actorId)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        // Exact setup outcome decoding must succeed before persistence or transport Receipt.
        recorded(entry, reply)
        val stored = StoredKernelResultV1.from(reply)
        if (entry.recorded != null && entry.recorded != stored)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        if (journal.remember(entry.operationId, stored) != JournalWrite.DURABLE)
            throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
        access.checkCurrent()
    }

    private suspend fun publish(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalSetupMutationResult {
        val kind = kind(entry.localAction)
        if (reply.operationId != entry.operationId || reply.originalActorId != journal.owner.actorId)
            return LocalSetupMutationResult.Unresolved(entry.operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind)
        val stored = StoredKernelResultV1.from(reply)
        if (entry.recorded != null && entry.recorded != stored)
            return LocalSetupMutationResult.Unresolved(entry.operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind)
        val mapped = try {
            recorded(entry, reply)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LocalSetupMutationResult.Unresolved(entry.operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind)
        }
        if (journal.remember(entry.operationId, stored) != JournalWrite.DURABLE)
            return LocalSetupMutationResult.Unresolved(entry.operationId, LocalServiceProblem.STORAGE_UNCERTAIN, kind)
        // The master result is already durable. Client archival only retires this local queue
        // entry; a failed archive remains visible in pending and never changes the outcome.
        if (reply.committed) journal.acknowledge(entry.operationId, stored)
        return mapped
    }

    private fun recorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalSetupMutationResult {
        val kind = kind(entry.localAction)
        if (!reply.committed) return LocalSetupMutationResult.Recorded(
            entry.operationId, kind, committed = false, code = reply.code)
        return when (entry.request().command) {
            LocalSetupResultProfileV1.ACKNOWLEDGE, LocalSetupResultProfileV1.GO_LIVE -> {
                val outcome = LocalSetupResultCodecV1.decodeOutcome(reply.result)
                LocalSetupMutationResult.Recorded(entry.operationId, kind, committed = true, code = outcome.code)
            }
            LocalSetupCheckpointProfileV1.CONFIGURE,
            LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW,
            LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT,
            LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW,
            LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW,
            LocalSetupCheckpointProfileV1.COMPLETE_OPENING -> {
                val result = LocalSetupCheckpointCodecV1.decodeResult(reply.result)
                LocalSetupMutationResult.Recorded(entry.operationId, kind, committed = true,
                    code = result.code, rowId = result.rowId, rowCount = result.rowCount,
                    manifestDigest = result.manifestDigest?.value,
                    catalogCompletion = if (entry.request().command == LocalSetupCheckpointProfileV1.COMPLETE_CATALOG_IMPORT)
                        LocalSetupCheckpointCodecV1.decodeCompleteCatalogImportRequest(entry.request().payload).let {
                            LocalSetupManifestPreview(it.expectedRowCount, it.expectedManifestDigest?.value)
                        } else null,
                    openingCompletion = if (entry.request().command == LocalSetupCheckpointProfileV1.COMPLETE_OPENING)
                        LocalSetupCheckpointCodecV1.decodeCompleteOpeningRequest(entry.request().payload).let {
                            LocalSetupOpeningManifestPreview(
                                LocalSetupManifestPreview(it.expectedStockRowCount, it.expectedStockManifestDigest?.value),
                                LocalSetupManifestPreview(it.expectedFloatRowCount, it.expectedFloatManifestDigest?.value))
                        } else null,
                    catalogRow = if (entry.request().command == LocalSetupCheckpointProfileV1.CATALOG_IMPORT_ROW)
                        LocalSetupCheckpointCodecV1.decodeCatalogImportRowRequest(entry.request().payload).let {
                            LocalSetupCatalogRow(it.sourceId, it.skuId, it.catalogOperationId)
                        } else null,
                    stockRow = if (entry.request().command == LocalSetupCheckpointProfileV1.OPENING_STOCK_ROW)
                        LocalSetupCheckpointCodecV1.decodeOpeningStockRowRequest(entry.request().payload).let {
                            LocalSetupOpeningStockRow(it.sourceId, it.receiptId, it.openingOperationId)
                        } else null,
                    floatRow = if (entry.request().command == LocalSetupCheckpointProfileV1.OPENING_FLOAT_ROW)
                        LocalSetupCheckpointCodecV1.decodeOpeningFloatRowRequest(entry.request().payload).let {
                            LocalSetupOpeningFloatRow(it.sourceId, it.sessionId, it.openingOperationId)
                        } else null)
            }
            else -> throw IllegalArgumentException("Unsupported setup result")
        }
    }

    private fun canContinue(entry: StoredClientIntentV1): Boolean =
        entry.localAction in setupActions && entry.recorded == null && !entry.acknowledged &&
            installed.has(com.ukkera.pos.database.CommandKey(entry.commandName, entry.commandVersion))

    private fun binding(): LocalSetupOriginalBindingV1 {
        // Captured from the actual native connection receipt, not a protected result read.
        // This is request identity only: the serialized master validates every field against
        // its own saved original under the action's current ADMINISTER grant.
        val binding = nativeOriginalBinding ?: throw GatewayFailure(LocalServiceProblem.NOT_CONFIGURED)
        require(binding.installationId == requireInstallation()) { "Setup original installation mismatch" }
        return binding
    }

    private fun target(binding: LocalSetupOriginalBindingV1): LocalSetupCheckpointTargetV1 =
        LocalSetupCheckpointTargetV1(binding, LocalSetupEnvironmentV1.LIVE)

    private fun requireInstallation(): DomainId =
        installationId ?: throw GatewayFailure(LocalServiceProblem.NOT_CONFIGURED)

    private fun availabilityProblem(): LocalServiceProblem = when (availability()) {
        LocalSetupClosureAvailability.AVAILABLE -> LocalServiceProblem.UNAVAILABLE
        LocalSetupClosureAvailability.NOT_CONFIGURED -> LocalServiceProblem.NOT_CONFIGURED
        LocalSetupClosureAvailability.NOT_IMPLEMENTED -> LocalServiceProblem.NOT_IMPLEMENTED
    }

    private suspend fun <T> withAccess(block: suspend (GatewayAccess) -> T): T {
        currentCoroutineContext().ensureActive()
        val lease = credentials.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        try {
            if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val current = lease.observeSession(endpoint)
            currentCoroutineContext().ensureActive()
            if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val view = when (current) {
                is com.ukkera.pos.security.LocalSessionViewResult.Current -> current.view
                com.ukkera.pos.security.LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                com.ukkera.pos.security.LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                com.ukkera.pos.security.LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                com.ukkera.pos.security.LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            }
            if (view.actorId != journal.owner.actorId || view.scope != journal.owner.scope ||
                view.deviceId != journal.owner.deviceId)
                throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val access = GatewayAccess(endpoint, lease, view, installed, false, ::retainForTransportReceipt)
            val result = block(access)
            access.checkCurrent()
            return result
        } finally {
            lease.destroy()
        }
    }
}

private fun StoredClientIntentV1.toPendingView(): LocalSetupPendingView = LocalSetupPendingView(
    operationId = operationId,
    kind = kind(localAction),
    phase = localPhase.name,
    recorded = recorded != null,
    recordedCode = recorded?.code,
)

private fun setupPermission(action: com.ukkera.pos.app.LocalAction): com.ukkera.pos.security.SecurityAction = when (action) {
    com.ukkera.pos.app.LocalAction.SETUP_RESULT_ACKNOWLEDGE ->
        com.ukkera.pos.security.SecurityAction.SETUP_RESULT_DELIVERY_ACKNOWLEDGE
    com.ukkera.pos.app.LocalAction.SETUP_BUSINESS_GO_LIVE ->
        com.ukkera.pos.security.SecurityAction.SETUP_BUSINESS_GO_LIVE
    com.ukkera.pos.app.LocalAction.SETUP_CONFIGURATION_RECORD ->
        com.ukkera.pos.security.SecurityAction.SETUP_CONFIGURATION_SETTLE
    com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_ROW,
    com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_COMPLETE ->
        com.ukkera.pos.security.SecurityAction.SETUP_CATALOG_IMPORT_SETTLE
    com.ukkera.pos.app.LocalAction.SETUP_OPENING_STOCK_ROW,
    com.ukkera.pos.app.LocalAction.SETUP_OPENING_FLOAT_ROW,
    com.ukkera.pos.app.LocalAction.SETUP_OPENING_COMPLETE ->
        com.ukkera.pos.security.SecurityAction.SETUP_OPENING_SETTLE
    else -> throw IllegalArgumentException("Unsupported setup permission")
}

private fun kind(action: com.ukkera.pos.app.LocalAction): LocalSetupOperationKind = when (action) {
    com.ukkera.pos.app.LocalAction.SETUP_RESULT_ACKNOWLEDGE -> LocalSetupOperationKind.ACKNOWLEDGE
    com.ukkera.pos.app.LocalAction.SETUP_BUSINESS_GO_LIVE -> LocalSetupOperationKind.GO_LIVE
    com.ukkera.pos.app.LocalAction.SETUP_CONFIGURATION_RECORD -> LocalSetupOperationKind.CONFIGURATION
    com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_ROW -> LocalSetupOperationKind.CATALOG_IMPORT_ROW
    com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_COMPLETE -> LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE
    com.ukkera.pos.app.LocalAction.SETUP_OPENING_STOCK_ROW -> LocalSetupOperationKind.OPENING_STOCK_ROW
    com.ukkera.pos.app.LocalAction.SETUP_OPENING_FLOAT_ROW -> LocalSetupOperationKind.OPENING_FLOAT_ROW
    com.ukkera.pos.app.LocalAction.SETUP_OPENING_COMPLETE -> LocalSetupOperationKind.OPENING_COMPLETE
    else -> LocalSetupOperationKind.UNKNOWN
}

private fun com.ukkera.pos.database.setup.LocalSetupResultProjectionV1.toUi(): LocalSetupResultView =
    LocalSetupResultView(
        original = LocalSetupOriginalView(
            installationId = original.installationId,
            assignmentId = original.assignmentId,
            deviceId = original.deviceId,
            firstActorId = original.firstActorId,
            originalRequestId = original.originalRequestId,
            issuedAttemptId = original.issuedAttemptId,
            attemptSequence = original.attemptSequence,
            definitionDigest = original.definitionDigest.value,
            genesisDigest = original.genesisDigest.value,
            receiptDigest = original.receiptDigest.value,
            installationAuthorityEpoch = original.installationAuthorityEpoch,
            currentAuthorityEpoch = original.currentAuthorityEpoch,
            installedAt = original.installedAt,
            nativeSettlement = original.nativeSettlement,
        ),
        acknowledgement = acknowledgement?.let {
            LocalSetupAcknowledgementView(it.actorId, it.acknowledgedAt, it.operationId, it.policyRevision)
        },
        goLive = goLive?.let {
            LocalSetupGoLiveView(it.actorId, it.occurredAt, it.operationId, it.policyRevision)
        },
        prerequisites = prerequisites.map {
            LocalSetupPrerequisiteView(
                kind = when (it.code) {
                    com.ukkera.pos.database.setup.LocalSetupPrerequisiteCodeV1.NATIVE_INSTALLATION_SETTLEMENT ->
                        LocalSetupPrerequisiteKind.NATIVE_INSTALLATION_SETTLEMENT
                    com.ukkera.pos.database.setup.LocalSetupPrerequisiteCodeV1.REQUIRED_CONFIGURATION ->
                        LocalSetupPrerequisiteKind.REQUIRED_CONFIGURATION
                    com.ukkera.pos.database.setup.LocalSetupPrerequisiteCodeV1.CATALOG_IMPORT_SETTLEMENT ->
                        LocalSetupPrerequisiteKind.CATALOG_IMPORT_SETTLEMENT
                    com.ukkera.pos.database.setup.LocalSetupPrerequisiteCodeV1.OPENING_SETTLEMENT ->
                        LocalSetupPrerequisiteKind.OPENING_SETTLEMENT
                },
                settled = it.settled,
                binding = it.binding,
            )
        },
    )

private fun com.ukkera.pos.catalog.store.setup.LocalSetupCheckpointProjectionV1.toUi(): LocalSetupCheckpointView =
    LocalSetupCheckpointView(
        configuration = configuration?.let {
            LocalSetupConfigurationView(
                revision = it.revision,
                activities = it.activities.map { activity -> activity.name },
                currency = it.currency.name,
                timeZone = it.timeZone,
                businessDayCutoffMinute = it.businessDayCutoffMinute,
                legalName = it.fiscalIdentity.legalName,
                taxRegistrationId = it.fiscalIdentity.taxRegistrationId,
                taxRegistrationAbsent = it.fiscalIdentity.taxRegistrationAbsent,
                actorId = it.actorId,
                recordedAt = it.recordedAt,
                operationId = it.operationId,
            )
        },
        catalogImport = catalogImport?.let {
            LocalSetupCatalogImportView(
                explicitNoImport = it.mode == LocalSetupImportModeV1.EXPLICIT_NO_IMPORT,
                rowCount = it.rowCount,
                manifestDigest = it.manifestDigest?.value,
                inputManifestDigest = it.inputManifestDigest?.value,
                actorId = it.actorId,
                recordedAt = it.recordedAt,
                operationId = it.operationId,
            )
        },
        opening = opening?.let {
            LocalSetupOpeningView(
                stockExplicitZero = it.stockChoice == LocalSetupOpeningChoiceV1.EXPLICIT_ZERO,
                stockRowCount = it.stockRowCount,
                stockValueTotalMinor = it.stockValueTotal.minor,
                floatExplicitZero = it.floatChoice == LocalSetupOpeningChoiceV1.EXPLICIT_ZERO,
                floatRowCount = it.floatRowCount,
                floatTotalMinor = it.floatTotal.minor,
                stockManifestDigest = it.stockManifestDigest?.value,
                floatManifestDigest = it.floatManifestDigest?.value,
                currency = it.floatTotal.currency.name,
                actorId = it.actorId,
                recordedAt = it.recordedAt,
                operationId = it.operationId,
            )
        },
    )

private fun setupPermissionVerb(action: com.ukkera.pos.app.LocalAction): com.ukkera.pos.security.PermissionVerb = when (action) {
    com.ukkera.pos.app.LocalAction.SETUP_CONFIGURATION_RECORD,
    com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_ROW,
    com.ukkera.pos.app.LocalAction.SETUP_CATALOG_IMPORT_COMPLETE,
    com.ukkera.pos.app.LocalAction.SETUP_OPENING_STOCK_ROW,
    com.ukkera.pos.app.LocalAction.SETUP_OPENING_FLOAT_ROW,
    com.ukkera.pos.app.LocalAction.SETUP_OPENING_COMPLETE -> com.ukkera.pos.security.PermissionVerb.ADMINISTER
    else -> com.ukkera.pos.security.PermissionVerb.PERFORM
}
