package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Composed setup-closure presentation. Manifests held here are explicit operator input for the
 * current entry; completed checkpoint facts are always reloaded from the protected production
 * read. A lost mutation reply retains its original operation ID and is recovered through the
 * durable client journal, never by minting a replacement ID.
 */
internal data class LocalSetupClosureState(
    val availability: LocalSetupClosureAvailability,
    val busy: Boolean = false,
    val result: LocalSetupResultView? = null,
    val resultProblem: LocalServiceProblem? = null,
    val checkpointsLoaded: Boolean = false,
    val checkpoints: LocalSetupCheckpointView? = null,
    val checkpointProblem: LocalServiceProblem? = null,
    val pending: List<LocalSetupPendingView> = emptyList(),
    val pendingProblem: LocalServiceProblem? = null,
    val lastProblem: LocalServiceProblem? = null,
    val lastRecorded: LocalSetupMutationResult.Recorded? = null,
    val lastMessage: LocalText? = null,
    val acknowledgeOperation: OperationId? = null,
    val goLiveOperation: OperationId? = null,
    val configurationOperation: OperationId? = null,
    val catalogRowOperation: OperationId? = null,
    val catalogCompleteOperation: OperationId? = null,
    val openingStockOperation: OperationId? = null,
    val openingFloatOperation: OperationId? = null,
    val openingCompleteOperation: OperationId? = null,
    val catalogRows: List<LocalSetupCatalogRow> = emptyList(),
    val openingStockRows: List<LocalSetupOpeningStockRow> = emptyList(),
    val openingFloatRows: List<LocalSetupOpeningFloatRow> = emptyList(),
) {
    val canMutate: Boolean get() = availability == LocalSetupClosureAvailability.AVAILABLE && !busy
    val allPrerequisitesSettled: Boolean
        get() = result?.prerequisites?.let { values -> values.isNotEmpty() && values.all { it.settled } } == true
}

internal class LocalSetupClosureController(
    private val runtime: UkkeraRuntime,
    private val session: BranchUiSession,
    private val owner: NavigationToken,
    private val scope: CoroutineScope,
) {
    private val attachmentKey = session.attachmentKey
    private val gateway = session.setupClosure
    private val mutableState = MutableStateFlow(LocalSetupClosureState(availability = gateway.availability()))
    val state: StateFlow<LocalSetupClosureState> = mutableState.asStateFlow()
    private var activated = false
    private var work: Job? = null

    fun activate(token: NavigationToken) {
        if (activated) return
        activated = true
        refresh(token)
    }

    fun deactivate() {
        activated = false
    }

    fun refresh(token: NavigationToken) = launch(token) {
        loadResultLocked(token)
        loadPendingLocked(token)
    }

    fun loadCheckpoints(token: NavigationToken) = launch(token) {
        loadCheckpointsLocked(token)
    }

    fun acknowledge(token: NavigationToken) {
        val operation = state.value.acknowledgeOperation ?: OperationId.random()
        launch(token) {
            mutableState.value = state.value.copy(acknowledgeOperation = operation)
            when (val result = gateway.acknowledge(operation)) {
                is LocalSetupMutationResult.Recorded -> adoptRecovery(token, result)
                is LocalSetupMutationResult.Pending -> mutableState.value = state.value.copy(
                    acknowledgeOperation = operation,
                    lastMessage = sameOriginalMessage())
                is LocalSetupMutationResult.NotStaged -> mutableState.value = state.value.copy(
                    acknowledgeOperation = null, lastProblem = result.problem)
                is LocalSetupMutationResult.Unresolved -> mutableState.value = state.value.copy(
                    acknowledgeOperation = operation, lastProblem = result.problem)
            }
        }
    }

    fun goLive(token: NavigationToken) {
        val operation = state.value.goLiveOperation ?: OperationId.random()
        launch(token) {
            mutableState.value = state.value.copy(goLiveOperation = operation)
            when (val result = gateway.goLive(operation)) {
                is LocalSetupMutationResult.Recorded -> adoptRecovery(token, result)
                is LocalSetupMutationResult.Pending -> mutableState.value = state.value.copy(
                    goLiveOperation = operation,
                    lastMessage = sameOriginalMessage())
                is LocalSetupMutationResult.NotStaged -> mutableState.value = state.value.copy(
                    goLiveOperation = null, lastProblem = result.problem)
                is LocalSetupMutationResult.Unresolved -> mutableState.value = state.value.copy(
                    goLiveOperation = operation, lastProblem = result.problem)
            }
        }
    }

    fun configure(token: NavigationToken, input: LocalSetupConfigurationInput) {
        val operation = state.value.configurationOperation ?: OperationId.random()
        launch(token) {
            mutableState.value = state.value.copy(configurationOperation = operation)
            when (val result = gateway.configure(operation, input)) {
                is LocalSetupMutationResult.Recorded -> adoptRecovery(token, result)
                is LocalSetupMutationResult.Pending -> mutableState.value = state.value.copy(
                    configurationOperation = operation,
                    lastMessage = sameOriginalMessage())
                is LocalSetupMutationResult.NotStaged -> mutableState.value = state.value.copy(
                    configurationOperation = null, lastProblem = result.problem)
                is LocalSetupMutationResult.Unresolved -> mutableState.value = state.value.copy(
                    configurationOperation = operation, lastProblem = result.problem)
            }
        }
    }

    fun recordCatalogRow(token: NavigationToken, row: LocalSetupCatalogRow) {
        if (state.value.catalogRows.any { it.sourceId == row.sourceId } ||
            state.value.catalogRows.size >= LOCAL_SETUP_MAX_MANIFEST_ROWS) {
            mutableState.value = state.value.copy(lastProblem = LocalServiceProblem.INVALID_INPUT)
            return
        }
        val operation = state.value.catalogRowOperation ?: OperationId.random()
        launch(token) {
            mutableState.value = state.value.copy(catalogRowOperation = operation)
            when (val result = gateway.recordCatalogRow(operation, row)) {
                is LocalSetupMutationResult.Recorded -> adoptRecovery(token, result)
                is LocalSetupMutationResult.Pending -> mutableState.value = state.value.copy(
                    catalogRowOperation = operation,
                    lastMessage = sameOriginalMessage())
                is LocalSetupMutationResult.NotStaged -> mutableState.value = state.value.copy(
                    catalogRowOperation = null, lastProblem = result.problem)
                is LocalSetupMutationResult.Unresolved -> mutableState.value = state.value.copy(
                    catalogRowOperation = operation, lastProblem = result.problem)
            }
        }
    }

    /** Add an already retained row reference to the completion manifest without dispatching. */
    fun addCatalogReference(row: LocalSetupCatalogRow) {
        if (!state.value.canMutate || state.value.catalogRows.size >= LOCAL_SETUP_MAX_MANIFEST_ROWS ||
            state.value.catalogRows.any { it.sourceId == row.sourceId }) return
        mutableState.value = state.value.copy(catalogRows = state.value.catalogRows + row)
    }

    fun removeCatalogRow(sourceId: com.ukkera.pos.types.DomainId) {
        if (!state.value.canMutate) return
        mutableState.value = state.value.copy(catalogRows = state.value.catalogRows.filterNot { it.sourceId == sourceId })
    }

    fun completeCatalogImport(token: NavigationToken, explicitNoImport: Boolean) {
        val input = try {
            LocalSetupCatalogCompletionInput(explicitNoImport, if (explicitNoImport) emptyList() else state.value.catalogRows)
        } catch (_: IllegalArgumentException) {
            mutableState.value = state.value.copy(lastProblem = LocalServiceProblem.INVALID_INPUT)
            return
        }
        val operation = state.value.catalogCompleteOperation ?: OperationId.random()
        launch(token) {
            mutableState.value = state.value.copy(catalogCompleteOperation = operation)
            when (val result = gateway.completeCatalogImport(operation, input)) {
                is LocalSetupMutationResult.Recorded -> adoptRecovery(token, result)
                is LocalSetupMutationResult.Pending -> mutableState.value = state.value.copy(
                    catalogCompleteOperation = operation,
                    lastMessage = sameOriginalMessage())
                is LocalSetupMutationResult.NotStaged -> mutableState.value = state.value.copy(
                    catalogCompleteOperation = null, lastProblem = result.problem)
                is LocalSetupMutationResult.Unresolved -> mutableState.value = state.value.copy(
                    catalogCompleteOperation = operation, lastProblem = result.problem)
            }
        }
    }

    fun recordOpeningStockRow(token: NavigationToken, row: LocalSetupOpeningStockRow) {
        if (state.value.openingStockRows.any { it.sourceId == row.sourceId } ||
            state.value.openingStockRows.size >= LOCAL_SETUP_MAX_MANIFEST_ROWS) {
            mutableState.value = state.value.copy(lastProblem = LocalServiceProblem.INVALID_INPUT)
            return
        }
        val operation = state.value.openingStockOperation ?: OperationId.random()
        launch(token) {
            mutableState.value = state.value.copy(openingStockOperation = operation)
            when (val result = gateway.recordOpeningStockRow(operation, row)) {
                is LocalSetupMutationResult.Recorded -> adoptRecovery(token, result)
                is LocalSetupMutationResult.Pending -> mutableState.value = state.value.copy(
                    openingStockOperation = operation,
                    lastMessage = sameOriginalMessage())
                is LocalSetupMutationResult.NotStaged -> mutableState.value = state.value.copy(
                    openingStockOperation = null, lastProblem = result.problem)
                is LocalSetupMutationResult.Unresolved -> mutableState.value = state.value.copy(
                    openingStockOperation = operation, lastProblem = result.problem)
            }
        }
    }

    fun addOpeningStockReference(row: LocalSetupOpeningStockRow) {
        if (!state.value.canMutate || state.value.openingStockRows.size >= LOCAL_SETUP_MAX_MANIFEST_ROWS ||
            state.value.openingStockRows.any { it.sourceId == row.sourceId }) return
        mutableState.value = state.value.copy(openingStockRows = state.value.openingStockRows + row)
    }

    fun recordOpeningFloatRow(token: NavigationToken, row: LocalSetupOpeningFloatRow) {
        if (state.value.openingFloatRows.any { it.sourceId == row.sourceId } ||
            state.value.openingFloatRows.size >= LOCAL_SETUP_MAX_MANIFEST_ROWS) {
            mutableState.value = state.value.copy(lastProblem = LocalServiceProblem.INVALID_INPUT)
            return
        }
        val operation = state.value.openingFloatOperation ?: OperationId.random()
        launch(token) {
            mutableState.value = state.value.copy(openingFloatOperation = operation)
            when (val result = gateway.recordOpeningFloatRow(operation, row)) {
                is LocalSetupMutationResult.Recorded -> adoptRecovery(token, result)
                is LocalSetupMutationResult.Pending -> mutableState.value = state.value.copy(
                    openingFloatOperation = operation,
                    lastMessage = sameOriginalMessage())
                is LocalSetupMutationResult.NotStaged -> mutableState.value = state.value.copy(
                    openingFloatOperation = null, lastProblem = result.problem)
                is LocalSetupMutationResult.Unresolved -> mutableState.value = state.value.copy(
                    openingFloatOperation = operation, lastProblem = result.problem)
            }
        }
    }

    fun addOpeningFloatReference(row: LocalSetupOpeningFloatRow) {
        if (!state.value.canMutate || state.value.openingFloatRows.size >= LOCAL_SETUP_MAX_MANIFEST_ROWS ||
            state.value.openingFloatRows.any { it.sourceId == row.sourceId }) return
        mutableState.value = state.value.copy(openingFloatRows = state.value.openingFloatRows + row)
    }

    fun removeOpeningStockRow(sourceId: com.ukkera.pos.types.DomainId) {
        if (!state.value.canMutate) return
        mutableState.value = state.value.copy(openingStockRows = state.value.openingStockRows.filterNot { it.sourceId == sourceId })
    }

    fun removeOpeningFloatRow(sourceId: com.ukkera.pos.types.DomainId) {
        if (!state.value.canMutate) return
        mutableState.value = state.value.copy(openingFloatRows = state.value.openingFloatRows.filterNot { it.sourceId == sourceId })
    }

    fun completeOpening(token: NavigationToken, stockExplicitZero: Boolean, floatExplicitZero: Boolean) {
        val input = try {
            LocalSetupOpeningCompletionInput(
                stockExplicitZero = stockExplicitZero,
                stockRows = if (stockExplicitZero) emptyList() else state.value.openingStockRows,
                floatExplicitZero = floatExplicitZero,
                floatRows = if (floatExplicitZero) emptyList() else state.value.openingFloatRows,
            )
        } catch (_: IllegalArgumentException) {
            mutableState.value = state.value.copy(lastProblem = LocalServiceProblem.INVALID_INPUT)
            return
        }
        val operation = state.value.openingCompleteOperation ?: OperationId.random()
        launch(token) {
            mutableState.value = state.value.copy(openingCompleteOperation = operation)
            when (val result = gateway.completeOpening(operation, input)) {
                is LocalSetupMutationResult.Recorded -> adoptRecovery(token, result)
                is LocalSetupMutationResult.Pending -> mutableState.value = state.value.copy(
                    openingCompleteOperation = operation,
                    lastMessage = sameOriginalMessage())
                is LocalSetupMutationResult.NotStaged -> mutableState.value = state.value.copy(
                    openingCompleteOperation = null, lastProblem = result.problem)
                is LocalSetupMutationResult.Unresolved -> mutableState.value = state.value.copy(
                    openingCompleteOperation = operation, lastProblem = result.problem)
            }
        }
    }

    fun lookup(token: NavigationToken, operationId: OperationId) = launch(token) {
        adoptRecovery(token, gateway.lookup(operationId))
    }

    fun resume(token: NavigationToken, operationId: OperationId) = launch(token) {
        adoptRecovery(token, gateway.resume(operationId))
    }

    fun acknowledgeRecorded(token: NavigationToken, operationId: OperationId) = launch(token) {
        when (val result = gateway.acknowledgeRecorded(operationId)) {
            is LocalAcknowledgementResult.Acknowledged -> {
                if (!current(token)) return@launch
                mutableState.value = state.value.copy(lastMessage = LocalText(
                    "The reviewed client result was archived. It remains retained by the master.",
                    "أُرشف نتيجة العميل التي تمت مراجعتها. تبقى محفوظة لدى الجهاز الرئيسي."))
                loadPendingLocked(token)
            }
            is LocalAcknowledgementResult.Unresolved -> mutableState.value = state.value.copy(lastProblem = result.problem)
        }
    }

    fun catalogPreview(): LocalSetupManifestPreview = gateway.catalogManifestPreview(state.value.catalogRows)

    fun openingPreview(): LocalSetupOpeningManifestPreview =
        gateway.openingManifestPreview(state.value.openingStockRows, state.value.openingFloatRows)

    private suspend fun adoptRecovery(token: NavigationToken, result: LocalSetupMutationResult) {
        if (!current(token)) return
        when (result) {
            is LocalSetupMutationResult.Recorded -> {
                var next = state.value.copy(lastRecorded = result, lastProblem = null)
                next = when (result.kind) {
                    LocalSetupOperationKind.ACKNOWLEDGE -> next.copy(acknowledgeOperation = null)
                    LocalSetupOperationKind.GO_LIVE -> next.copy(goLiveOperation = null)
                    LocalSetupOperationKind.CONFIGURATION -> next.copy(configurationOperation = null)
                    LocalSetupOperationKind.CATALOG_IMPORT_ROW -> next.copy(catalogRowOperation = null)
                    LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE -> next.copy(catalogCompleteOperation = null)
                    LocalSetupOperationKind.OPENING_STOCK_ROW -> next.copy(openingStockOperation = null)
                    LocalSetupOperationKind.OPENING_FLOAT_ROW -> next.copy(openingFloatOperation = null)
                    LocalSetupOperationKind.OPENING_COMPLETE -> next.copy(openingCompleteOperation = null)
                    LocalSetupOperationKind.UNKNOWN -> next
                }
                if (result.committed) {
                    // Row identities come from the retained actor journal request, including after
                    // lookup/resume. A terminal rejection never adds rows or clears input manifests.
                    next = when (result.kind) {
                        LocalSetupOperationKind.CATALOG_IMPORT_ROW -> result.catalogRow?.let { row ->
                            if (next.catalogRows.any { it.sourceId == row.sourceId && it != row } ||
                                (next.catalogRows.none { it.sourceId == row.sourceId } &&
                                    next.catalogRows.size >= LOCAL_SETUP_MAX_MANIFEST_ROWS)) null
                            else next.copy(catalogRows = next.catalogRows.filterNot { it.sourceId == row.sourceId } + row)
                        } ?: next.copy(lastProblem = LocalServiceProblem.STORAGE_UNCERTAIN)
                        LocalSetupOperationKind.OPENING_STOCK_ROW -> result.stockRow?.let { row ->
                            if (next.openingStockRows.any { it.sourceId == row.sourceId && it != row } ||
                                (next.openingStockRows.none { it.sourceId == row.sourceId } &&
                                    next.openingStockRows.size >= LOCAL_SETUP_MAX_MANIFEST_ROWS)) null
                            else next.copy(openingStockRows = next.openingStockRows.filterNot { it.sourceId == row.sourceId } + row)
                        } ?: next.copy(lastProblem = LocalServiceProblem.STORAGE_UNCERTAIN)
                        LocalSetupOperationKind.OPENING_FLOAT_ROW -> result.floatRow?.let { row ->
                            if (next.openingFloatRows.any { it.sourceId == row.sourceId && it != row } ||
                                (next.openingFloatRows.none { it.sourceId == row.sourceId } &&
                                    next.openingFloatRows.size >= LOCAL_SETUP_MAX_MANIFEST_ROWS)) null
                            else next.copy(openingFloatRows = next.openingFloatRows.filterNot { it.sourceId == row.sourceId } + row)
                        } ?: next.copy(lastProblem = LocalServiceProblem.STORAGE_UNCERTAIN)
                        // A recovered older completion can coexist with later editor input.
                        // Clear only a draft with the exact submitted count/digest. If it has
                        // changed, preserve it in full; a digest cannot identify an added subset.
                        LocalSetupOperationKind.CATALOG_IMPORT_COMPLETE -> next.copy(
                            catalogRows = if (result.catalogCompletion != null &&
                                gateway.catalogManifestPreview(next.catalogRows) == result.catalogCompletion)
                                emptyList() else next.catalogRows)
                        LocalSetupOperationKind.OPENING_COMPLETE -> {
                            val current = gateway.openingManifestPreview(next.openingStockRows, next.openingFloatRows)
                            next.copy(
                                openingStockRows = if (result.openingCompletion != null &&
                                    current.stock == result.openingCompletion.stock) emptyList() else next.openingStockRows,
                                openingFloatRows = if (result.openingCompletion != null &&
                                    current.float == result.openingCompletion.float) emptyList() else next.openingFloatRows)
                        }
                        else -> next
                    }
                }
                mutableState.value = next
                loadPendingLocked(token)
                loadResultLocked(token)
                if (result.committed && state.value.checkpointsLoaded) {
                    // Refresh facts without overwriting unsubmitted draft references. Explicit
                    // reload remains the operator's way to replace drafts with all retained rows.
                    loadCheckpointsLocked(token, restoreRows = false)
                }
            }
            is LocalSetupMutationResult.Pending -> mutableState.value = state.value.copy(
                lastMessage = sameOriginalMessage())
            is LocalSetupMutationResult.NotStaged -> mutableState.value = state.value.copy(lastProblem = result.problem)
            is LocalSetupMutationResult.Unresolved -> mutableState.value = state.value.copy(lastProblem = result.problem)
        }
    }

    private suspend fun loadResultLocked(token: NavigationToken) {
        val result = gateway.readResult()
        if (!current(token)) return
        mutableState.value = when (result) {
            is LocalSetupReadResult.Available -> state.value.copy(result = result.value, resultProblem = null)
            is LocalSetupReadResult.Unavailable -> state.value.copy(result = null, resultProblem = result.problem)
        }
    }

    private suspend fun loadCheckpointsLocked(token: NavigationToken, restoreRows: Boolean = true) {
        val result = gateway.readCheckpoints()
        if (!current(token)) return
        mutableState.value = when (result) {
            is LocalSetupReadResult.Available -> state.value.copy(
                checkpointsLoaded = true, checkpoints = result.value, checkpointProblem = null,
                catalogRows = if (restoreRows && result.value.retainedRowsLoaded) result.value.retainedCatalogRows else state.value.catalogRows,
                openingStockRows = if (restoreRows && result.value.retainedRowsLoaded) result.value.retainedStockRows else state.value.openingStockRows,
                openingFloatRows = if (restoreRows && result.value.retainedRowsLoaded) result.value.retainedFloatRows else state.value.openingFloatRows,
            )
            is LocalSetupReadResult.Unavailable -> state.value.copy(
                checkpointsLoaded = true, checkpoints = null, checkpointProblem = result.problem)
        }
    }

    private suspend fun loadPendingLocked(token: NavigationToken) {
        val result = gateway.pending()
        if (!current(token)) return
        mutableState.value = when (result) {
            is LocalSetupReadResult.Available -> state.value.copy(pending = result.value, pendingProblem = null)
            is LocalSetupReadResult.Unavailable -> state.value.copy(pending = emptyList(), pendingProblem = result.problem)
        }
    }

    private fun launch(token: NavigationToken, block: suspend () -> Unit) {
        if (mutableState.value.busy || !runtime.ownsLocalAttachment(session, owner, attachmentKey)) return
        mutableState.value = state.value.copy(busy = true, lastProblem = null, lastMessage = null)
        work = scope.launch {
            var failure: LocalServiceProblem? = null
            try {
                if (!current(token)) return@launch
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failure = LocalServiceProblem.UNAVAILABLE
            } finally {
                if (runtime.ownsLocalAttachment(session, owner, attachmentKey)) {
                    mutableState.value = state.value.copy(
                        busy = false,
                        lastProblem = state.value.lastProblem ?: failure,
                    )
                }
            }
        }
    }

    private suspend fun current(token: NavigationToken): Boolean =
        runtime.ownsLocalAttachment(session, owner, attachmentKey) &&
            try { session.isCurrent() } catch (_: Exception) { false }

    private fun sameOriginalMessage(): LocalText = LocalText(
        "No terminal result was observed. Keep this original operation ID and look it up or resume the same original; do not start a replacement attempt.",
        "لم تُرصد نتيجة نهائية. احتفظ بمعرف العملية الأصلية هذا وابحث عنه أو استأنف الأصل نفسه؛ لا تبدأ محاولة بديلة.")
}
