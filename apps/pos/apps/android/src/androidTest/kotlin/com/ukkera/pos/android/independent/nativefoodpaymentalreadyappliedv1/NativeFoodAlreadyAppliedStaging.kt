package com.ukkera.pos.android.independent.nativefoodpaymentalreadyappliedv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One separately selected ordinary ApplyOriginal, through the unchanged pending-only
 * public preparation gate. Stage while genuine O awaits accounting; finish earlier A;
 * Submit this already-staged operation once. Recovery never stages or executes it again.
 * This mechanical race setup is binding DATA, not an independent FAA-Q02 resolution.
 * The observer forwards actual protected calls unchanged and manufactures no reply. */
internal class NativeFoodAlreadyAppliedStaging(
    private val original: AndroidOriginalLocalClient,
    private val journals: AndroidOriginalClientJournals,
    private val credentials: LocalWorkflowCredentialSource,
    private val plan: NativeFoodAlreadyAppliedPlan,
    private val evidence: NativeFoodAlreadyAppliedEvidence,
) {
    private val owner = original.material.coordinates.let { LocalClientIdentity(it.scope, it.firstActorId, it.deviceId) }
    private val monitor = Any()
    @Volatile private var retired = false
    private var active: CompletableDeferred<Unit>? = null
    private var journal: LocalClientIntentJournal? = null
    private var gateway: LocalEndpointWorkflowGateway? = null
    private var entry: Entry? = null
    private class Entry(val request: MutationRequest, val identity: OperationIdentity,
        var immutable: FoodAlreadyAppliedRecordedResult? = null) {
        var staged = false
        var acknowledged = false
        var dispatchAttempted = false
        var dispatched: MutationReply.Recorded? = null
        var found: MutationReply.Recorded? = null
        var observedIdentity: OperationIdentity? = null
        var completedLookups = 0
        fun observe(value: MutationReply.Recorded) {
            check(!value.committed && value.operationId == request.operationId && value.originalActorId == identity.originalActorId &&
                value.physicalStart == null)
            immutable?.requireSame(value) ?: run { immutable = FoodAlreadyAppliedRecordedResult(value) }
        }
    }
    private val observer = object : LocalMasterClient by original.client {
        override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
            requireLive(credentials)
            val selected = checkNotNull(entry)
            check(identity == selected.identity) { "Native employee-journal lookup changed the pre-pinned original identity" }
            selected.observedIdentity = identity
            return original.client.lookup(credentials, identity).also { reply ->
                if (reply is LookupReply.Found) { selected.observe(reply.result); selected.found = reply.result }
                selected.completedLookups += 1
            }
        }
        override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
            requireLive(credentials)
            val selected = checkNotNull(entry)
            check(selected.staged && !selected.dispatchAttempted && selected.immutable == null &&
                selected.observedIdentity == selected.identity && sameFoodAlreadyAppliedRequest(selected.request, request)) {
                "Only this single byte-exact ordinary staged original may dispatch; recovery has no execute fallback"
            }
            selected.dispatchAttempted = true
            evidence.markCall(REJECTION, "ACTUAL_PUBLIC_FINANCIAL_WORKFLOW_NATIVE_INTENT_SUBMIT_ONCE")
            return original.client.execute(credentials, request).also { reply ->
                evidence.observeReply(REJECTION, reply)
                if (reply is MutationReply.Recorded) { selected.observe(reply); selected.dispatched = reply }
            }
        }
    }

    suspend fun stage(originalRead: LocalFoodOriginalForStage, view: LocalSessionView): Unit = call {
        check(entry == null && originalRead.summary.applicationState == com.ukkera.pos.sales.store.LocalFoodPaymentApplicationState.PENDING_RECONCILIATION)
        val request = com.ukkera.pos.sales.store.LocalFoodPaymentCodec.command(plan.rejectionOperationId,
            com.ukkera.pos.sales.store.LocalFoodPaymentRequest.ApplyOriginal(plan.recordingOperationId))
        val identity = OperationIdentity(view.scope.branchId, request.operationId, view.actorId, 1,
            semanticDigest(view.scope, view.actorId, request.command, request.payload).value)
        evidence.freezeRequest(REJECTION, request, view, original, nativeStage = true)
        val selected = Entry(request, identity).also { entry = it }
        val workflow = workflow(view)
        requireEmpty(workflow.pending())
        val intent = LocalCommandIntent(plan.rejectionOperationId, LocalAction.FOOD_PAYMENT_APPLY, 1, null, listOf(
            LocalScalarInput(LocalField.FOOD_PAYMENT_RECORDING_OPERATION_ID, LocalScalarValue.Identifier(DomainId(plan.recordingOperationId.value))),
            LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(plan.orderId)),
            LocalScalarInput(LocalField.COLLECTION_ID, LocalScalarValue.Identifier(plan.collectionId)),
            LocalScalarInput(LocalField.AMOUNT, LocalScalarValue.Decimal(foodAlreadyAppliedDecimal(plan.payable.minor, 2))),
            LocalScalarInput(LocalField.FOOD_PAYMENT_ORIGINAL_BODY_DIGEST, LocalScalarValue.Text(originalRead.summary.bodyDigest.value)),
        ))
        val staged = (workflow.stage(intent) as? LocalStageResult.Staged)?.operation
            ?: error("The genuine pending-only financial preparation/native journal could not Stage the separate ordinary operation")
        check(staged.operationId == request.operationId && staged.action == LocalAction.FOOD_PAYMENT_APPLY &&
            staged.phase == LocalPendingPhase.STAGED && staged.recordedDecision == null && staged.submitAllowed)
        selected.staged = true
        evidence.staged(REJECTION, staged.operationId, staged.action.name, intent.formVersion)
        val pending = requireSingle(workflow.pending(), LocalPendingPhase.STAGED)
        check(pending.submitAllowed && pending.summary == staged.summary && pending.label == staged.label &&
            selected.observedIdentity == selected.identity && !selected.dispatchAttempted && selected.immutable == null)
    }

    suspend fun submitAfterEarlierApply(view: LocalSessionView): MutationReply.Recorded = call {
        val selected = checkNotNull(entry)
        check(selected.staged && !selected.dispatchAttempted && !selected.acknowledged)
        val workflow = workflow(view)
        val staged = requireSingle(workflow.pending(), LocalPendingPhase.STAGED)
        check(staged.submitAllowed && selected.immutable == null)
        val terminal = workflow.submit(plan.rejectionOperationId) as? LocalOperationResult.Recorded
            ?: error("The genuine staged ordinary Apply did not return a qualified recorded outcome")
        requireAlreadyAppliedUi(terminal, replayed = false)
        val actual = checkNotNull(selected.dispatched) { "No genuine one-time native-journal dispatch was observed" }
        check(selected.dispatchAttempted && !actual.replayed)
        requireOrdinaryAlreadyApplied(actual, plan)
        val pending = requireSingle(workflow.pending(), LocalPendingPhase.RECORDED_UNACKNOWLEDGED)
        check(!pending.submitAllowed && pending.recordedDecision == terminal.decision &&
            pending.summary == staged.summary && pending.label == staged.label)
        evidence.event("ORDINARY_ALREADY_APPLIED_DURABLY_REMEMBERED_BEFORE_ACK", JSONObject()
            .put("operationId", actual.operationId.value).put("recordedDecision", terminal.decision.name)
            .put("priorApplicationOperationId", plan.earlierApplyOperationId.value).put("newPhysicalStart", false))
        val ack = workflow.acknowledgeRecorded(plan.rejectionOperationId) as? LocalAcknowledgementResult.Acknowledged
            ?: error("The native employee journal has not acknowledged the exact ordinary recorded outcome")
        check(ack.operationId == plan.rejectionOperationId)
        selected.acknowledged = true
        requireEmpty(workflow.pending())
        evidence.event("ACTUAL_ORDINARY_CLIENT_ACK_NOT_AUTHORITY_OR_TRANSPORT_RECEIPT", JSONObject()
            .put("operationId", ack.operationId.value).put("masterEvidenceDeleted", false).put("transportReceiptQualification", "UNBOUND"))
        actual
    }

    suspend fun lookup(saved: FoodAlreadyAppliedSavedOriginal, view: LocalSessionView): MutationReply.Recorded = call {
        requireOwner(view)
        check(saved.symbol == REJECTION && saved.nativeStaged && saved.scope == owner.scope && saved.deviceId == owner.deviceId &&
            saved.identity.originalActorId == owner.actorId && saved.request.operationId == plan.rejectionOperationId)
        requireOrdinaryAlreadyApplied(saved.result, plan)
        val selected = entry?.also { check(it.identity == saved.identity && sameFoodAlreadyAppliedRequest(it.request, saved.request) &&
            it.immutable == saved.result) }
            ?: Entry(saved.request, saved.identity, saved.result).also { entry = it }
        val before = selected.completedLookups
        val workflow = workflow(view)
        requireEmpty(workflow.pending())
        val value = workflow.lookup(saved.request.operationId) as? LocalOperationResult.Recorded
            ?: error("Current-authorized financial lookup through the actual existing native journal is unavailable")
        requireAlreadyAppliedUi(value, replayed = true)
        check(selected.completedLookups > before && selected.observedIdentity == saved.identity)
        val actual = checkNotNull(selected.found)
        saved.result.requireSame(actual)
        requireOrdinaryAlreadyApplied(actual, plan)
        check(actual.replayed && actual.physicalStart == null)
        requireEmpty(workflow.pending()) // The original ACK stays acknowledged; no new intent is outstanding.
        evidence.event("EXACT_EXISTING_NATIVE_FINANCIAL_ORIGINAL_LOOKUP_ONLY", JSONObject()
            .put("operationId", actual.operationId.value).put("sequence", actual.sequence.value)
            .put("originalActorId", actual.originalActorId.value).put("originalRecordedAt", actual.recordedAt.canonicalString())
            .put("newStage", false).put("newExecute", false).put("newCapture", false)
            .put("admittedRemoteRpcQualification", "UNBOUND_SAME_DEVICE_PUBLIC_WORKFLOW"))
        actual
    }

    private fun requireAlreadyAppliedUi(value: LocalOperationResult.Recorded, replayed: Boolean) {
        check(value.operationId == plan.rejectionOperationId && value.originalAction == LocalAction.FOOD_PAYMENT_APPLY &&
            value.replayed == replayed && value.decision != LocalRecordedDecision.APPLIED)
        // Existing public mapping only; neither its wording nor the full Q01 projection is an oracle.
        val detail = checkNotNull(value.foodCashFinancial)
        check(detail.originalActorId == owner.actorId && !detail.committed &&
            detail.application == LocalFoodCashFinancialApplication.ALREADY_APPLIED &&
            detail.applicationOperationId == plan.earlierApplyOperationId)
    }
    private suspend fun workflow(view: LocalSessionView): LocalEndpointWorkflowGateway {
        requireOwner(view)
        val actual = checkNotNull(journals.current(owner)) { "The original employee intent journal is unavailable" }
        return synchronized(monitor) {
            check(!retired && actual.owner == owner && (journal == null || journal === actual))
            journal = actual
            gateway ?: LocalEndpointWorkflowGateway(observer, actual, credentials,
                LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL), actionApprovals = null)
                .also { gateway = it }
        }
    }
    private fun requireOwner(view: LocalSessionView) {
        check(!retired && original.isCurrent() && original.peer.isCurrent() && view.scope == owner.scope &&
            view.actorId == owner.actorId && view.deviceId == owner.deviceId)
    }
    private fun requireLive(credentials: LocalGatewayCredentials) {
        check(!retired && original.isCurrent() && original.peer.isCurrent() && credentials.connectionId == original.peer.connectionId)
    }
    private fun requireEmpty(value: LocalPendingResult) {
        val page = value as? LocalPendingResult.Available ?: error("Actual native pending history is unavailable")
        check(page.nextCursor == null && page.operations.isEmpty())
    }
    private fun requireSingle(value: LocalPendingResult, phase: LocalPendingPhase): LocalPendingOperation {
        val page = value as? LocalPendingResult.Available ?: error("Actual native pending history is unavailable")
        check(page.nextCursor == null && page.operations.size == 1)
        return page.operations.single().also { check(it.operationId == plan.rejectionOperationId && it.action == LocalAction.FOOD_PAYMENT_APPLY && it.phase == phase) }
    }
    private suspend fun <T> call(block: suspend () -> T): T {
        val completion = CompletableDeferred<Unit>()
        synchronized(monitor) { check(!retired && active == null); active = completion }
        try { return block() }
        finally { synchronized(monitor) { check(active === completion); active = null; completion.complete(Unit) } }
    }
    fun retire() = synchronized(monitor) { retired = true }
    suspend fun awaitRetiredCalls(): Unit = withContext(NonCancellable) {
        val (retained, pending) = synchronized(monitor) { check(retired); journal to active }
        var failure: Throwable? = null
        try { retained?.close() } catch (caught: Throwable) { failure = caught }
        try { pending?.await(); check(synchronized(monitor) { active == null }) }
        catch (caught: Throwable) { failure?.let { if (it !== caught) it.addSuppressed(caught) } ?: run { failure = caught } }
        failure?.let { throw it }
    }
    companion object { const val REJECTION = "R_ORDINARY_ALREADY_APPLIED" }
}

internal typealias LocalFoodOriginalForStage = com.ukkera.pos.sales.store.LocalFoodPaymentReadResult.Original
internal fun requireOrdinaryAlreadyApplied(reply: MutationReply.Recorded, plan: NativeFoodAlreadyAppliedPlan) =
    requireOrdinaryAlreadyApplied(FoodAlreadyAppliedRecordedResult(reply), plan)
internal fun requireOrdinaryAlreadyApplied(reply: FoodAlreadyAppliedRecordedResult, plan: NativeFoodAlreadyAppliedPlan) {
    val result = com.ukkera.pos.sales.store.LocalFoodPaymentCodec.result(reply.payload)
        as? com.ukkera.pos.sales.store.LocalFoodPaymentResult.AlreadyApplied
        ?: error("This is not a genuinely recorded ordinary AlreadyApplied result; prior Applied replay is insufficient")
    check(!reply.committed && reply.operationId == plan.rejectionOperationId && reply.code == "food.payment.already_applied" &&
        result.phase == com.ukkera.pos.sales.store.LocalFoodPaymentFactPhase.ORIGINAL &&
        result.recordingOperationId == plan.recordingOperationId && result.applicationOperationId == plan.earlierApplyOperationId &&
        result.applicationOperationId != reply.operationId)
}
internal fun foodAlreadyAppliedDecimal(value: Long, scale: Int): String {
    check(value >= 0 && scale in 0..6)
    val digits = value.toString().padStart(scale + 1, '0')
    return if (scale == 0) digits else digits.dropLast(scale) + "." + digits.takeLast(scale)
}
