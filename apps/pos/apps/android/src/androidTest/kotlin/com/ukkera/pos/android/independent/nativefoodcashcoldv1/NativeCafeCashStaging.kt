package com.ukkera.pos.android.independent.nativefoodcashcoldv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.LocalPublishedFoodRecipeV1
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Four SQL originals only, over the actual already-open native employee intent journal.
 * The observer forwards the public client contract unchanged. Its expected request/result
 * values are comparison DATA, never a journal, principal, result factory or native gate.
 * Credentials are independently owned snapshots of the fixture's SAME genuine session.
 * This is not full Branch authentication, installed UI or physical I/O/A staging. */
internal class NativeCafeCashStaging(
    private val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    private val credentials: LocalWorkflowCredentialSource,
    private val evidence: NativeCafeCashEvidence,
) {
    private val owner = original.material.coordinates.let { LocalClientIdentity(it.scope, it.firstActorId, it.deviceId) }
    private val monitor = Any()
    @Volatile private var retired = false
    private var active: CompletableDeferred<Unit>? = null
    private var journal: LocalClientIntentJournal? = null
    private var gateway: LocalEndpointWorkflowGateway? = null
    private val entries = linkedMapOf<OperationId, Entry>()

    private class Entry(val symbol: String, val action: LocalAction, val request: MutationRequest,
        val identity: OperationIdentity, var immutable: CafeRecordedResult? = null) {
        var staged = false
        var acknowledged = false
        var dispatchAttempted = false
        var dispatched: MutationReply.Recorded? = null
        var observedIdentity: OperationIdentity? = null
        var found: MutationReply.Recorded? = null
        var completedLookups = 0
        var publicRecoveries = 0

        fun observe(value: MutationReply.Recorded) {
            check(value.operationId == request.operationId && value.originalActorId == identity.originalActorId &&
                value.committed && value.physicalStart == null)
            immutable?.requireSame(value) ?: run { immutable = CafeRecordedResult(value.operationId, value.originalActorId,
                value.committed, value.code, value.result, value.authorityEpoch, value.sequence, value.recordedAt) }
        }
    }

    private val observer = object : LocalMasterClient by original.client {
        override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
            requireLive(credentials)
            val entry = checkNotNull(entries[identity.operationId]) { "Lookup did not select a retained SQL original" }
            check(identity == entry.identity) { "The actual native-journal original identity differs from the frozen DATA" }
            entry.observedIdentity = identity // Actual saved-original selector, not an absence or permission.
            return original.client.lookup(credentials, identity).also { reply ->
                if (reply is LookupReply.Found) {
                    entry.observe(reply.result)
                    entry.found = reply.result
                }
                entry.completedLookups += 1
            }
        }

        override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
            requireLive(credentials)
            val entry = checkNotNull(entries[request.operationId]) { "Dispatch did not select a staged SQL original" }
            check(entry.staged && !entry.dispatchAttempted && entry.immutable == null &&
                entry.observedIdentity == entry.identity && sameRequest(entry.request, request)) {
                "Only one byte-exact native-journal SQL dispatch is selected; no replacement or recovery execute"
            }
            entry.dispatchAttempted = true
            evidence.markCall(entry.symbol, "GENUINE_SHARED_SQL_WORKFLOW_ORIGINAL_NATIVE_INTENT_DISPATCH")
            return original.client.execute(credentials, request).also { reply ->
                evidence.observeReply(entry.symbol, reply)
                if (reply is MutationReply.Recorded) { entry.observe(reply); entry.dispatched = reply }
            }
        }
    }

    suspend fun stageAndSubmit(symbol: String, intent: LocalCommandIntent, expected: MutationRequest,
        view: LocalSessionView): MutationReply.Recorded = call {
        check(actions[symbol] == intent.action && intent.operationId == expected.operationId && expected.actionApprovalId == null)
        val entry = Entry(symbol, intent.action, expected, expectedIdentity(view, expected))
        check(entries[expected.operationId] == null && entries.size < actions.size)
        entries[expected.operationId] = entry
        val workflow = workflow(view)
        requireEmpty(workflow.pending())
        val staged = (workflow.stage(intent) as? LocalStageResult.Staged)?.operation
            ?: error("The actual native employee journal did not durably stage the selected SQL original")
        check(staged.operationId == expected.operationId && staged.action == intent.action &&
            staged.phase == LocalPendingPhase.STAGED && staged.recordedDecision == null && staged.submitAllowed)
        entry.staged = true
        evidence.recordSqlStage(symbol, intent.formVersion, staged, owner)
        val pending = requireSingle(workflow.pending(), entry, LocalPendingPhase.STAGED)
        check(pending.summary == staged.summary && pending.label == staged.label && pending.submitAllowed &&
            entry.observedIdentity == entry.identity)
        val recorded = workflow.submit(expected.operationId) as? LocalOperationResult.Recorded
            ?: error("The shared gateway did not return the actual recorded SQL original")
        requireRecorded(recorded, entry, replayed = false)
        val actual = checkNotNull(entry.dispatched) { "No genuine native-journal SQL execute reply was observed" }
        check(!actual.replayed && entry.dispatchAttempted)
        val terminal = requireSingle(workflow.pending(), entry, LocalPendingPhase.RECORDED_UNACKNOWLEDGED)
        check(!terminal.submitAllowed && terminal.recordedDecision == LocalRecordedDecision.APPLIED &&
            terminal.summary == staged.summary && terminal.label == staged.label)
        evidence.event("ACTUAL_SHARED_SQL_RECORDED_PENDING", phaseData(entry).put("phase", terminal.phase.name)
            .put("decision", checkNotNull(terminal.recordedDecision).name).put("installedUiDisplay", false))
        val acknowledgement = workflow.acknowledgeRecorded(expected.operationId) as? LocalAcknowledgementResult.Acknowledged
            ?: error("The actual shared SQL original acknowledgement is unresolved; no new stage is permitted")
        check(acknowledgement.operationId == expected.operationId)
        entry.acknowledged = true
        requireEmpty(workflow.pending())
        evidence.event("ACTUAL_SHARED_SQL_CLIENT_ACKNOWLEDGED", phaseData(entry)
            .put("acknowledgementOrigin", "SYNTHETIC_PUBLIC_API_NOT_INSTALLED_UI").put("masterEvidenceDeleted", false))
        actual
    }

    /** Lookup uses the actual reopened journal's immutable original, never restaging or a
     * DATA-constructed request. Missing/corrupt/uncertain history has no direct-call fallback. */
    suspend fun recover(saved: CafeSavedOriginal, view: LocalSessionView): MutationReply.Recorded = call {
        val action = checkNotNull(actions[saved.symbol])
        requireOwner(view)
        check(saved.scope == owner.scope && saved.deviceId == owner.deviceId && saved.identity.originalActorId == owner.actorId)
        val entry = entries[saved.request.operationId]?.also {
            check(it.symbol == saved.symbol && it.action == action && it.identity == saved.identity && sameRequest(it.request, saved.request))
            checkNotNull(it.immutable).let { actual -> check(actual == saved.result) }
        } ?: Entry(saved.symbol, action, saved.request, saved.identity, saved.result).also {
            entries[saved.request.operationId] = it
            check(entries.size <= actions.size)
        }
        val workflow = workflow(view)
        val before = entry.completedLookups
        val result = workflow.lookup(saved.request.operationId) as? LocalOperationResult.Recorded
            ?: error("The actual existing-only native employee journal cannot recover its selected original")
        requireRecorded(result, entry, replayed = true)
        check(entry.completedLookups > before && entry.observedIdentity == saved.identity)
        val actual = checkNotNull(entry.found)
        saved.result.requireSame(actual)
        check(actual.replayed && actual.physicalStart == null)
        entry.publicRecoveries += 1
        evidence.event("EXISTING_NATIVE_SQL_JOURNAL_PUBLIC_LOOKUP_ONLY", phaseData(entry)
            .put("originalNativeRecordedAt", actual.recordedAt.canonicalString()).put("newStage", false)
            .put("newExecute", false).put("newPhysicalInstruction", false))
        actual
    }

    fun requireFourOriginals(saved: List<CafeSavedOriginal>, recovered: Boolean) {
        check(!retired && original.isCurrent() && entries.size == actions.size)
        val selected = saved.filter { it.symbol in actions }
        check(selected.size == actions.size && selected.map { it.symbol }.toSet() == actions.keys)
        selected.forEach { original ->
            val entry = checkNotNull(entries[original.request.operationId])
            check(entry.symbol == original.symbol && entry.identity == original.identity && entry.immutable == original.result &&
                sameRequest(entry.request, original.request))
            if (recovered) check(!entry.staged && !entry.dispatchAttempted && entry.publicRecoveries == 2)
            else check(entry.staged && entry.acknowledged && entry.dispatchAttempted && entry.dispatched != null && entry.publicRecoveries == 0)
        }
        evidence.requireFourSqlStages(selected)
    }

    fun retire() = synchronized(monitor) { retired = true }
    suspend fun awaitRetiredCalls(): Unit = withContext(NonCancellable) {
        // Attachment shares the retirement lock, so no later caller can install another view.
        val (retained, pending) = synchronized(monitor) { check(retired); journal to active }
        var failure: Throwable? = null
        try {
            // Caller cancellation is NOT native completion. This exact public view close
            // retires admission and awaits the native worker's actual claim/output disposal.
            // It neither erases unresolved entries nor releases/replaces the root's native lease.
            retained?.close()
        } catch (caught: Throwable) { failure = caught }
        try {
            // Separately await gateway return/finally and destruction of every owned lease.
            pending?.await()
            check(synchronized(monitor) { active == null })
        } catch (caught: Throwable) {
            val prior = failure
            if (prior == null) failure = caught else if (prior !== caught) prior.addSuppressed(caught)
        }
        failure?.let { throw it } // Unconfirmed drain blocks this client's logout/root-journal closure.
    }

    private suspend fun workflow(view: LocalSessionView): LocalEndpointWorkflowGateway {
        requireOwner(view)
        val actual = checkNotNull(journals.current(owner)) { "The already-open original employee intent journal is unavailable" }
        return synchronized(monitor) {
            check(!retired && actual.owner == owner && (journal == null || journal === actual))
            journal = actual // Retain the exact native view; never attach one after retirement.
            gateway ?: LocalEndpointWorkflowGateway(observer, actual, credentials,
                LocalWorkflowInstallation(original.commandKeys, original.readKeys), actionApprovals = null).also { gateway = it }
        }
    }
    private fun requireOwner(view: LocalSessionView) {
        check(!retired && original.isCurrent() && original.peer.isCurrent() &&
            view.scope == owner.scope && view.actorId == owner.actorId && view.deviceId == owner.deviceId)
    }
    private fun requireLive(credentials: LocalGatewayCredentials) {
        check(!retired && original.isCurrent() && original.peer.isCurrent() && credentials.connectionId == original.peer.connectionId)
    }
    private fun expectedIdentity(view: LocalSessionView, request: MutationRequest): OperationIdentity {
        requireOwner(view)
        return OperationIdentity(view.scope.branchId, request.operationId, view.actorId, 1,
            semanticDigest(view.scope, view.actorId, request.command, request.payload).value)
    }
    private suspend fun <T> call(block: suspend () -> T): T {
        val completion = CompletableDeferred<Unit>()
        synchronized(monitor) { check(!retired && active == null); active = completion }
        try { return block() }
        finally { synchronized(monitor) { check(active === completion); active = null; completion.complete(Unit) } }
    }
    private fun requireEmpty(value: LocalPendingResult) {
        val page = value as? LocalPendingResult.Available ?: error("Actual native SQL pending history is unavailable")
        check(page.nextCursor == null && page.operations.isEmpty())
    }
    private fun requireSingle(value: LocalPendingResult, entry: Entry, phase: LocalPendingPhase): LocalPendingOperation {
        val page = value as? LocalPendingResult.Available ?: error("Actual native SQL pending history is unavailable")
        check(page.nextCursor == null && page.operations.size == 1)
        return page.operations.single().also { check(it.operationId == entry.request.operationId && it.action == entry.action && it.phase == phase) }
    }
    private fun requireRecorded(value: LocalOperationResult.Recorded, entry: Entry, replayed: Boolean) {
        check(value.operationId == entry.request.operationId && value.originalAction == entry.action &&
            value.decision == LocalRecordedDecision.APPLIED && value.replayed == replayed)
    }
    private fun phaseData(entry: Entry) = JSONObject().put("symbol", entry.symbol).put("action", entry.action.name)
        .put("operationId", entry.request.operationId.value).put("originalActorId", owner.actorId.value)
        .put("originalDeviceId", owner.deviceId.value).put("semanticDigest", entry.identity.semanticSha256)

    companion object {
        val actions = mapOf("CREATE_CAFE_TAKEAWAY_DRAFT" to LocalAction.FOOD_CREATE_DRAFT,
            "QUOTE_ORIGINAL_TWO_SMALL_LATTES" to LocalAction.FOOD_ADD,
            "BEGIN_COLLECTION_RESERVE_ALL_UNSENT" to LocalAction.FOOD_BEGIN_COLLECTION,
            "FINALIZE_ONE_PAID_SALE" to LocalAction.FOOD_FINALIZE_PAID)
        private fun sameRequest(a: MutationRequest, b: MutationRequest) = a.operationId == b.operationId &&
            a.command == b.command && a.payload == b.payload && a.actionApprovalId == b.actionApprovalId
    }
}

/** Public typed selectors preserve the current frozen request exactly. Formatting below
 * only renders exact positive scalar inputs; the production gateway owns all preparation. */
internal fun cafeDraftSqlIntent(plan: NativeCafeCashPlan) = LocalCommandIntent(plan.operation(122), LocalAction.FOOD_CREATE_DRAFT,
    2, null, listOf(cafeId(LocalField.ORDER_ID, plan.orderId), cafeChoice(LocalField.ACTIVITY, plan.activity.name),
        cafeChoice(LocalField.CHANNEL, plan.service.channel.name), cafeText(LocalField.SERVICE_REFERENCE, plan.service.reference),
        cafeText(LocalField.SEAT_NOTE, plan.service.seatNote)))

internal fun cafeQuotedAddSqlIntent(plan: NativeCafeCashPlan, orderRevision: Revision, catalogRevision: Revision,
    recipe: LocalPublishedFoodRecipeV1): LocalCommandIntent {
    check(recipe.recipeId == plan.recipeId && recipe.revision == Revision(1) && recipe.outputSkuId == plan.latteSkuId &&
        recipe.portionQuantity == plan.quantity("latte", 1) && recipe.choices.size == 1)
    val choice = recipe.choices.single()
    check(choice.id == plan.choiceId && choice.modifierIds.isEmpty())
    return LocalCommandIntent(plan.quoteOperationId, LocalAction.FOOD_ADD, 3, null,
        listOf(cafeId(LocalField.ORDER_ID, plan.orderId), cafeRevision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, orderRevision),
            cafeRevision(LocalField.EXPECTED_CATALOG_REVISION, catalogRevision)), LocalField.LINES,
        listOf(LocalInputLine(plan.portionIds.first(), listOf(cafeId(LocalField.RECIPE_ID, recipe.recipeId),
            cafeRevision(LocalField.EXPECTED_FOOD_RECIPE_REVISION, recipe.revision), cafeId(LocalField.SKU_ID, recipe.outputSkuId),
            cafeId(LocalField.CHOICE_ID, choice.id), cafeId(LocalField.UNIT, recipe.portionQuantity.unitId),
            LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(recipe.portionQuantity.scale.toLong())),
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(cafeInputDecimal(recipe.portionQuantity.scaled, recipe.portionQuantity.scale))),
            cafeText(LocalField.NAME_ENGLISH, choice.names.english), cafeText(LocalField.NAME_ARABIC, choice.names.arabic),
            LocalScalarInput(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(plan.portionIds.size.toLong())),
            cafeText(LocalField.FOOD_PORTION_IDS, plan.portionIds.joinToString(",") { it.value })))))
}

internal fun cafeBeginSqlIntent(plan: NativeCafeCashPlan, quoted: CafeProtectedCut) = LocalCommandIntent(plan.operation(123),
    LocalAction.FOOD_BEGIN_COLLECTION, 1, null, listOf(cafeId(LocalField.ORDER_ID, plan.orderId),
        cafeRevision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, checkNotNull(quoted.food.food.detail).summary.revision),
        cafeId(LocalField.COLLECTION_ID, plan.collectionId), cafeId(LocalField.SESSION_ID, plan.cashSessionId),
        cafeRevision(LocalField.EXPECTED_SESSION_REVISION, checkNotNull(quoted.cash.session).revision),
        LocalScalarInput(LocalField.AMOUNT, LocalScalarValue.Decimal(cafeInputDecimal(plan.payable.minor, plan.payable.currency.fractionDigits)))))

internal fun cafeFinalizeSqlIntent(plan: NativeCafeCashPlan, collection: com.ukkera.pos.sales.store.LocalFoodCollectionViewV1) =
    LocalCommandIntent(plan.finalizeOperationId, LocalAction.FOOD_FINALIZE_PAID, 1, null, listOf(
        cafeId(LocalField.COLLECTION_ID, plan.collectionId), cafeRevision(LocalField.EXPECTED_COLLECTION_REVISION, collection.collectionDomainRevision),
        cafeId(LocalField.ORDER_ID, plan.orderId), cafeRevision(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, collection.orderStorageRevision),
        cafeId(LocalField.SALE_ID, plan.saleId)))

private fun cafeId(field: LocalField, value: DomainId) = LocalScalarInput(field, LocalScalarValue.Identifier(value))
private fun cafeRevision(field: LocalField, value: Revision) = LocalScalarInput(field, LocalScalarValue.Integer(value.value))
private fun cafeChoice(field: LocalField, value: String) = LocalScalarInput(field, LocalScalarValue.Choice(value))
private fun cafeText(field: LocalField, value: String) = LocalScalarInput(field, LocalScalarValue.Text(value))
private fun cafeInputDecimal(scaled: Long, scale: Int): String {
    check(scaled >= 0 && scale in 0..6)
    val digits = scaled.toString().padStart(scale + 1, '0')
    return if (scale == 0) digits else digits.dropLast(scale) + "." + digits.takeLast(scale)
}
