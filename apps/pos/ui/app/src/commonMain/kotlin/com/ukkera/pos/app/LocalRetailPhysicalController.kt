package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class LocalRetailBusy { NONE, READING, PREPARING, STAGING, STARTING, SQL, RECOVERING, ACKNOWLEDGING }
internal enum class LocalRetailObserved { UNKNOWN, NOT_PAID, CASH_RECEIVED, CASH_PAID, EXTERNAL_ATTESTED }
internal enum class LocalRetailInput { AMOUNT, RECEIVED, CHANGE, PAID, REASON, TERMINAL, REFERENCE, RECOVERY_ID }
internal data class LocalRetailObservationDraft(val selected: LocalRetailObserved? = null,
    val received: String = "", val change: String = "", val reason: String = "",
    val terminal: String = "", val reference: String = "", val confirmed: Boolean = false, val paid: String = "")
internal data class LocalRetailPreview(val operationId: OperationId, val kind: LocalRetailPhysicalKind,
    val title: LocalText, val summary: List<LocalRecordValue>, val saveAttempted: Boolean = false)

/** User-entered data and lookup identities only; never transfers a Start, prepared body or grant. */
internal data class LocalRetailDraftContinuation(
    val workflow: LocalWorkflow, val reference: LocalRecordReference?,
    val foodCollectionId: DomainId?, val foodRefundId: DomainId?,
    val paymentAmount: String, val tenderId: DomainId?,
    val observation: LocalRetailObservationDraft, val recoveryId: String, val operationId: OperationId?,
)

/** Ephemeral display only. No native handle, original lifetime or accepting capability is saved. */
internal data class LocalRetailWorkspace(
    val workflow: LocalWorkflow = LocalWorkflow.CHECKOUT,
    val reference: LocalRecordReference? = null,
    val foodCollectionId: DomainId? = null,
    val foodRefundId: DomainId? = null,
    val foodRefund: LocalFoodRefundContext? = null,
    val foodRefundOriginal: LocalFoodRefundOriginalSelection? = null,
    val foodRefundOriginalId: OperationId? = null,
    val busy: LocalRetailBusy = LocalRetailBusy.NONE,
    val preview: LocalRetailPreview? = null,
    val selected: LocalRetailSavedOperation? = null,
    val saved: List<LocalRetailSavedOperation> = emptyList(),
    val facts: List<LocalRetailPhysicalProgress> = emptyList(),
    val pendingLoaded: Boolean = false,
    /** Separate replacing FOOD page; its cursor never continues the original scan. */
    val resolutionPendingPage: Boolean = false,
    val nextFactAfter: DomainId? = null,
    val payment: LocalRetailPaymentContext? = null,
    val paymentAmount: String = "",
    val tenderId: DomainId? = null,
    val dues: List<LocalRetailRefundDue> = emptyList(),
    val refundRecoveries: List<LocalRetailRefundRecovery> = emptyList(),
    val duesLoaded: Boolean = false,
    /** Selected finance and own-unsettled reads replace each other, never share a cursor. */
    val duesForSelectedReturn: Boolean = false,
    val nextDueAfter: DomainId? = null,
    val instruction: LocalRetailInstructionDisplay? = null,
    val observation: LocalRetailObservationDraft = LocalRetailObservationDraft(),
    val progress: LocalRetailPhysicalProgress? = null,
    val review: LocalRetailPhysicalProgress? = null,
    val reviewedOriginalId: OperationId? = null,
    val reviewedSubject: LocalPaymentSubject? = null,
    val capture: LocalRetailPhysicalCapture? = null,
    val recoveryId: String = "",
    val operationId: OperationId? = null,
    val problem: LocalServiceProblem? = null,
    val inputProblem: Boolean = false,
    val notice: LocalText? = null,
) {
    val working: Boolean get() = busy != LocalRetailBusy.NONE
    val canPrepare: Boolean get() = !working && instruction == null && preview == null
}

/**
 * Existing Nav3-entry owner, not a service or native adapter. All callbacks require the exact
 * current token. Async replies also require this activation and entry. The real root/session/
 * navigator synchronously deny originals BEFORE their changes; collectors are not that fence.
 */
internal class LocalRetailPhysicalController(
    private val runtime: UkkeraRuntime,
    private val session: BranchUiSession,
    private val owner: NavigationToken,
    private val scope: CoroutineScope,
    private val parentAvailable: () -> Boolean,
    private val guardChanged: () -> Unit,
) {
    private val attachmentKey = session.attachmentKey
    private val gateway = session.retailPhysical
    private val acknowledgementGateway = session.workflows
    private val mutableState = MutableStateFlow(LocalRetailWorkspace())
    val state: StateFlow<LocalRetailWorkspace> = mutableState.asStateFlow()
    private var active = false
    private var activation = 0L
    private var work: Job? = null
    private var prepared: LocalRetailPhysicalPreparation? = null
    private var originalOwner: LocalRetailPhysicalOwner? = null
    private var instruction: LocalRetailPhysicalInstruction? = null

    val blocksParentWork: Boolean get() = state.value.working || instruction != null || prepared != null
    val leaveGuard: LeaveGuard get() = when {
        state.value.working || instruction != null -> LeaveGuard.CRITICAL
        prepared != null -> LeaveGuard.DIRTY
        else -> LeaveGuard.CLEAN
    }

    fun activate(workflow: LocalWorkflow, reference: LocalRecordReference?, foodQuery: LocalFoodUiQuery? = null) {
        deactivate()
        // Stock disposition has no physical-payment bridge, even though its navigation
        // shares RETURNS with the separately selected RETURN_FINANCE refund route.
        if (reference?.kind == LocalRecordKind.RETURN_STOCK || workflow !in retailWorkflows ||
            !runtime.ownsLocalAttachment(session, owner, attachmentKey)) return
        active = true
        mutableState.value = LocalRetailWorkspace(workflow = workflow, reference = reference,
            foodCollectionId = (foodQuery as? LocalFoodUiQuery.Collection)?.collectionId.takeIf { workflow == LocalWorkflow.FOOD },
            foodRefundId = ((foodQuery as? LocalFoodUiQuery.Refund)?.refundId ?:
                reference?.takeIf { it.kind == LocalRecordKind.FOOD_REFUND }?.id).takeIf { workflow == LocalWorkflow.FOOD })
        guardChanged()
    }

    fun snapshotDraft(): LocalRetailDraftContinuation? = state.value.takeIf { active }?.let { value ->
        LocalRetailDraftContinuation(value.workflow, value.reference, value.foodCollectionId, value.foodRefundId,
            value.paymentAmount, value.tenderId, value.observation.copy(selected = null, confirmed = false),
            value.recoveryId.ifEmpty { value.operationId?.value.orEmpty() }, value.operationId)
    }

    fun restoreDraft(value: LocalRetailDraftContinuation) {
        if (!current() || value.workflow != state.value.workflow || value.reference != state.value.reference ||
            value.foodCollectionId != state.value.foodCollectionId || value.foodRefundId != state.value.foodRefundId) return
        update(state.value.copy(paymentAmount = value.paymentAmount, tenderId = value.tenderId,
            observation = value.observation, recoveryId = value.recoveryId, operationId = value.operationId,
            notice = LocalText("Session renewed. Previous physical instructions ended. Read the original saved operation before any continuation; no payment or refund was repeated.",
                "جُددت الجلسة وانتهت التعليمات المادية السابقة. اقرأ العملية الأصلية المحفوظة قبل المتابعة؛ لم يتكرر أي دفع أو رد مبلغ.")))
    }

    /** Parent calls this BEFORE changing activation/workflow/reference or cancelling its scope. */
    fun deactivate() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { denyOriginal() }
        active = false
        activation++
        cleanup.attempt { work?.cancel(); work = null }
        prepared = null
        mutableState.value = LocalRetailWorkspace()
        cleanup.attempt { guardChanged() }
        cleanup.throwIfFailed()
    }

    private fun denyOriginal() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { originalOwner?.deny(); originalOwner = null }
        // End-only; accepted or uncertain native custody is never cancelled. A failed
        // exact holder stays reachable for negative cleanup, never fresh instructions.
        cleanup.attempt { instruction?.deny(); instruction = null }
        if (cleanup.failed) {
            // Every caller, including explicit instruction end/capture and stale
            // replies, must stop publishing if an exact negative holder failed.
            val previous = state.value
            active = false
            activation++
            prepared = null
            mutableState.value = LocalRetailWorkspace(workflow = previous.workflow, operationId = previous.operationId,
                recoveryId = previous.recoveryId, problem = LocalServiceProblem.UNAVAILABLE)
            cleanup.attempt { work?.cancel(); work = null }
            cleanup.attempt { guardChanged() }
        }
        cleanup.throwIfFailed()
    }

    private fun current(generation: Long = activation): Boolean = active && generation == activation &&
        runtime.ownsLocalAttachment(session, owner, attachmentKey)
    private fun accepts(token: NavigationToken): Boolean = current() && runtime.navigator.isCurrent(token) &&
        token.rootGeneration == owner.rootGeneration && token.entryId == owner.entryId && token.area == owner.area
    private data class Ticket(val activation: Long, val navigation: NavigationToken)
    private fun current(ticket: Ticket): Boolean = current(ticket.activation) && runtime.navigator.isCurrent(ticket.navigation)
    fun displayCurrent(token: NavigationToken): Boolean = accepts(token)
    private fun editable(token: NavigationToken): Boolean = accepts(token) && parentAvailable() && state.value.canPrepare
    private fun update(value: LocalRetailWorkspace) { mutableState.value = value; guardChanged() }
    private fun invalid() { update(state.value.copy(inputProblem = true, problem = null)) }

    fun changeInput(field: LocalRetailInput, value: String, token: NavigationToken) {
        if (!accepts(token) || !parentAvailable() || state.value.working || prepared != null) return
        val limit = when (field) {
            LocalRetailInput.REASON -> 256
            LocalRetailInput.TERMINAL, LocalRetailInput.REFERENCE -> 128
            LocalRetailInput.RECOVERY_ID -> 36
            else -> 32
        }
        if (value.length > limit) return
        val before = state.value
        val draft = before.observation
        val changed = when (field) {
            LocalRetailInput.AMOUNT -> before.copy(paymentAmount = value)
            LocalRetailInput.RECOVERY_ID -> before.copy(recoveryId = value)
            LocalRetailInput.RECEIVED -> before.copy(observation = draft.copy(received = value, confirmed = false))
            LocalRetailInput.CHANGE -> before.copy(observation = draft.copy(change = value, confirmed = false))
            LocalRetailInput.PAID -> before.copy(observation = draft.copy(paid = value, confirmed = false))
            LocalRetailInput.REASON -> before.copy(observation = draft.copy(reason = value, confirmed = false))
            LocalRetailInput.TERMINAL -> before.copy(observation = draft.copy(terminal = value, confirmed = false))
            LocalRetailInput.REFERENCE -> before.copy(observation = draft.copy(reference = value, confirmed = false))
        }
        update(changed.copy(inputProblem = false))
    }

    fun chooseObservation(value: LocalRetailObserved, token: NavigationToken) {
        if (!accepts(token) || !parentAvailable() || state.value.working || prepared != null) return
        val display = state.value.instruction ?: currentFoodRefundOriginal()?.display ?: currentReview()?.reviewDisplay ?: return
        if (value == LocalRetailObserved.CASH_PAID && display.kind != LocalRetailPhysicalKind.REFUND ||
            value == LocalRetailObserved.CASH_RECEIVED && display.kind != LocalRetailPhysicalKind.PAYMENT ||
            state.value.instruction == null && value == LocalRetailObserved.UNKNOWN) return
        update(state.value.copy(observation = state.value.observation.copy(selected = value, confirmed = false), inputProblem = false))
    }

    fun confirmObservation(value: Boolean, token: NavigationToken) {
        if (accepts(token) && parentAvailable() && !state.value.working && prepared == null)
            update(state.value.copy(observation = state.value.observation.copy(confirmed = value), inputProblem = false))
    }

    fun chooseTender(id: DomainId?, token: NavigationToken) {
        if (!editable(token)) return
        if (id != null && state.value.payment?.externalTenders?.none { it.id == id } != false) return
        update(state.value.copy(tenderId = id, inputProblem = false))
    }

    private fun collectionId(): DomainId? = if (foodRefundLane) null else state.value.foodCollectionId ?: state.value.reference?.takeIf {
        it.kind == LocalRecordKind.COLLECTION || it.kind == LocalRecordKind.FOOD_COLLECTION }?.id ?: state.value.progress?.collectionId

    private val refundOnly: Boolean get() = state.value.workflow == LocalWorkflow.RETURNS
    private val foodRefundLane: Boolean get() = state.value.workflow == LocalWorkflow.FOOD && state.value.foodRefundId != null

    private fun collectionSubject(): LocalPaymentSubject = if (state.value.foodCollectionId != null ||
        state.value.reference?.kind == LocalRecordKind.FOOD_COLLECTION) LocalPaymentSubject.FOOD
        else state.value.progress?.subject ?: LocalPaymentSubject.RETAIL

    fun loadPayment(token: NavigationToken, next: Boolean = false) {
        if (!editable(token) || refundOnly || foodRefundLane) return
        val id = collectionId() ?: return
        val after = if (next) state.value.payment?.takeIf { it.collectionId == id }?.nextAfter ?: return else null
        val subject = collectionSubject()
        if (next && subject == LocalPaymentSubject.FOOD) return
        request(LocalRetailBusy.READING) {
            if (subject == LocalPaymentSubject.FOOD) gateway.foodPaymentContext(id) else gateway.paymentContext(id, after)
        }
    }

    fun beginCollection(token: NavigationToken) {
        if (!editable(token) || refundOnly) return
        val id = state.value.reference?.takeIf { it.kind == LocalRecordKind.ORDER }?.id ?: return
        prepare(LocalRetailPhysicalRequest.BeginCollection(id))
    }

    fun preparePayment(token: NavigationToken) {
        if (!editable(token) || refundOnly || foodRefundLane) return
        val context = state.value.payment ?: return
        if (context.collectionId != collectionId() || context.subject != collectionSubject()) return
        val amount = parseRetailMoney(state.value.paymentAmount, context.remaining.currency)
        if (amount == null || amount.minor <= 0L || amount.minor > context.remaining.minor) { invalid(); return }
        val tender = state.value.tenderId?.let { id -> context.externalTenders.singleOrNull { it.id == id } ?: return }
        if (context.subject == LocalPaymentSubject.FOOD && tender != null) { invalid(); return }
        prepare(LocalRetailPhysicalRequest.Payment(context.collectionId, amount, tender?.id, tender?.revision, context.subject))
    }

    fun loadFoodRefund(token: NavigationToken) {
        if (!editable(token) || !foodRefundLane) return
        val id = state.value.foodRefundId ?: return
        update(state.value.copy(foodRefund = null))
        request(LocalRetailBusy.READING) { gateway.foodRefundContext(id) }
    }

    fun prepareFoodRefund(paymentId: DomainId, token: NavigationToken) {
        if (!editable(token) || !foodRefundLane) return
        val context = state.value.foodRefund?.takeIf { it.refundId == state.value.foodRefundId } ?: return
        val due = context.dues.singleOrNull { it.originalPaymentId == paymentId && !it.unresolved && it.tender == "CASH" } ?: return
        prepare(LocalRetailPhysicalRequest.FoodRefund(due.refundId, due.originalPaymentId))
    }

    fun recoverFoodRefund(value: LocalFoodRefundRecovery, token: NavigationToken) {
        if (!editable(token) || !foodRefundLane || state.value.foodRefund?.ownRecoveries?.none { it === value } != false) return
        update(state.value.copy(selected = null, operationId = value.intentOperationId, recoveryId = value.recordingOperationId.value,
            notice = null, progress = null))
        request(LocalRetailBusy.RECOVERING, value.intentOperationId) { gateway.recoverFoodRefundAttempt(value.refundId, value.attemptId) }
    }

    fun readFoodRefundOriginal(token: NavigationToken, fresh: Boolean = false) {
        if (!editable(token) || !foodRefundLane) return
        val id = if (fresh) state.value.foodRefundOriginalId ?: return else parseOperation(state.value.recoveryId) ?: return
        update(state.value.copy(foodRefundOriginal = null, foodRefundOriginalId = id, observation = LocalRetailObservationDraft()))
        request(LocalRetailBusy.READING, id) { gateway.foodRefundOriginal(id) }
    }

    fun readFoodRefundOriginalOf(value: LocalRetailPhysicalProgress, token: NavigationToken) {
        if (!editable(token) || !foodRefundLane || value.subject != LocalPaymentSubject.FOOD ||
            value.financialKind != LocalRetailFinancialKind.REFUND || !canPrepareOwnApplication(value) ||
            state.value.progress !== value && state.value.facts.none { it === value }) return
        val id = value.originalRecordingOperationId ?: return
        update(state.value.copy(recoveryId = id.value))
        readFoodRefundOriginal(token)
    }

    private fun currentFoodRefundOriginal(): LocalFoodRefundOriginalSelection? = state.value.foodRefundOriginal?.takeIf {
        foodRefundLane && it.display.subject == LocalPaymentSubject.FOOD && it.display.kind == LocalRetailPhysicalKind.REFUND &&
            it.display.recordingOperationId == state.value.foodRefundOriginalId &&
            it.original.originalRecordingOperationId == it.display.recordingOperationId &&
            it.original.originalActorId == session.display.actorId && it.original.recordingActorId == session.display.actorId
    }

    fun loadRefunds(token: NavigationToken, next: Boolean = false) {
        if (!editable(token) || state.value.workflow == LocalWorkflow.FOOD) return
        if (next && state.value.duesForSelectedReturn) return
        val after = if (next) state.value.nextDueAfter ?: return else null
        update(state.value.copy(dues = emptyList(), refundRecoveries = emptyList(), duesLoaded = false,
            duesForSelectedReturn = false, nextDueAfter = null))
        request(LocalRetailBusy.READING) { gateway.refundDues(after) }
    }

    fun loadAcceptedRefunds(token: NavigationToken) {
        if (!editable(token) || !refundOnly) return
        val reference = state.value.reference?.takeIf { it.kind == LocalRecordKind.RETURN_FINANCE } ?: return
        val caseId = reference.parentId ?: return
        update(state.value.copy(dues = emptyList(), refundRecoveries = emptyList(), duesLoaded = false,
            duesForSelectedReturn = true, nextDueAfter = null))
        request(LocalRetailBusy.READING) { gateway.acceptedRefundDues(caseId, reference.id) }
    }

    fun prepareRefund(returnId: DomainId, paymentId: DomainId, token: NavigationToken) {
        if (!editable(token) || state.value.workflow == LocalWorkflow.FOOD || !state.value.duesLoaded) return
        val due = state.value.dues.singleOrNull { it.returnId == returnId && it.originalPaymentId == paymentId && !it.unresolved } ?: return
        prepare(LocalRetailPhysicalRequest.Refund(due.saleId, due.claimId, due.returnId, due.originalPaymentId))
    }

    fun recoverRefund(value: LocalRetailRefundRecovery, token: NavigationToken) {
        if (!editable(token) || state.value.workflow == LocalWorkflow.FOOD ||
            state.value.refundRecoveries.none { it === value } || value.protocolVersion != 3 || value.recordingOperationId == null) return
        val recordingId = value.recordingOperationId ?: return
        update(state.value.copy(selected = null, operationId = value.intentOperationId,
            recoveryId = recordingId.value, notice = null, progress = null))
        request(LocalRetailBusy.RECOVERING, value.intentOperationId) {
            gateway.recoverRefund(value.saleId, value.claimId, value.returnId, value.attemptId)
        }
    }

    fun loadPending(token: NavigationToken, next: Boolean = false, resolutions: Boolean = false) {
        if (!editable(token)) return
        val food = state.value.workflow == LocalWorkflow.FOOD
        val refundPage = refundOnly
        if (resolutions && !food) return
        val resolutionPage = food && (if (next) state.value.resolutionPendingPage else resolutions)
        val after = if (next) state.value.nextFactAfter ?: return else null
        if (!next) update(state.value.copy(resolutionPendingPage = resolutionPage, facts = emptyList(),
            nextFactAfter = null, pendingLoaded = false))
        request(LocalRetailBusy.READING) {
            if (refundPage) gateway.pendingRefunds(after)
            else if (!food) gateway.pending(after)
            else if (foodRefundLane) {
                if (resolutionPage) gateway.pendingFoodRefundResolutions(after) else gateway.pendingFoodRefunds(after)
            }
            else if (resolutionPage) gateway.pendingFoodResolutions(after) else gateway.pendingFood(after)
        }
    }

    fun reviewOriginal(token: NavigationToken, fresh: Boolean = false) {
        if (!editable(token) || foodRefundLane) return
        val id = if (fresh) state.value.reviewedOriginalId ?: return else parseOperation(state.value.recoveryId) ?: return
        val subject = if (fresh) state.value.reviewedSubject ?: return
            else if (state.value.workflow == LocalWorkflow.FOOD) LocalPaymentSubject.FOOD
            else if (refundOnly) LocalPaymentSubject.RETAIL else collectionSubject()
        if (refundOnly && subject != LocalPaymentSubject.RETAIL) return
        update(state.value.copy(review = null, observation = LocalRetailObservationDraft(),
            reviewedOriginalId = state.value.reviewedOriginalId.takeIf { fresh },
            reviewedSubject = state.value.reviewedSubject.takeIf { fresh }))
        prepare(LocalRetailPhysicalRequest.SupervisorReview(id, subject))
    }

    /** Historical display has a validated subject/selector; it is never authority itself. */
    private fun currentReview(): LocalRetailPhysicalProgress? = state.value.review?.takeIf {
        it.state == LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED &&
            it.originalRecordingOperationId != null && it.reviewDisplay?.recordingOperationId == it.originalRecordingOperationId &&
            it.originalRecordingOperationId == state.value.reviewedOriginalId &&
            it.subject == state.value.reviewedSubject && it.reviewDisplay?.subject == it.subject &&
            (!refundOnly || it.financialKind == LocalRetailFinancialKind.REFUND && it.reviewDisplay?.kind == LocalRetailPhysicalKind.REFUND)
    }

    /** Presentation routing only. The gateway and kernel freshly enforce custody and permissions. */
    fun canPrepareOwnApplication(value: LocalRetailPhysicalProgress): Boolean =
        value.originalActorId == session.display.actorId && value.recordingActorId == value.originalActorId

    fun prepareApply(known: LocalRetailPhysicalProgress, token: NavigationToken) {
        if (!editable(token)) return
        if (refundOnly && (known.financialKind != LocalRetailFinancialKind.REFUND || known.subject != LocalPaymentSubject.RETAIL)) return
        // Use the exact displayed row, not a different result-only row with the same phase ID
        // and a different button route. Old callbacks cannot select a replacement projection.
        if (state.value.progress !== known && state.value.facts.none { it === known } &&
            state.value.foodRefundOriginal?.original !== known && state.value.foodRefundOriginal?.resolution !== known) return
        val id = known.recordingOperationId ?: return
        val phase = known.phase ?: return
        if (known.state !in setOf(LocalRetailDurableState.SQL_ORIGINAL_PENDING, LocalRetailDurableState.SQL_RESOLUTION_PENDING)) return
        if (known.subject == LocalPaymentSubject.FOOD && known.financialKind == LocalRetailFinancialKind.REFUND) {
            // Foreign payout recorder/custody takeover is deliberately held; no supervisor alias.
            if (canPrepareOwnApplication(known)) prepare(LocalRetailPhysicalRequest.FoodRefundApply(id, phase))
        } else if (canPrepareOwnApplication(known)) prepare(LocalRetailPhysicalRequest.Apply(id, phase, known.subject))
        else {
            // Cross-recorder/custodian or result-only progress is NOT ordinary application
            // authority. Keep the original selector distinct from the resolution fact ID;
            // this button requests only a fresh review, never automatic supervised accounting.
            val original = known.originalRecordingOperationId ?: return
            update(state.value.copy(review = null, reviewedOriginalId = null, reviewedSubject = null,
                recoveryId = original.value, observation = LocalRetailObservationDraft()))
            prepare(LocalRetailPhysicalRequest.SupervisorReview(original, known.subject))
        }
    }

    fun supervisorApply(phase: LocalRetailObservationPhase, token: NavigationToken) {
        if (!editable(token) || foodRefundLane) return
        val review = currentReview() ?: return
        if (phase == LocalRetailObservationPhase.RESOLUTION && !review.resolutionRecorded) return
        prepare(LocalRetailPhysicalRequest.SupervisorApply(review.operationId, phase, review.subject))
    }

    fun resolve(token: NavigationToken) {
        if (!editable(token)) return
        if (foodRefundLane) {
            val original = currentFoodRefundOriginal() ?: return
            if (!original.original.originalUnknown || original.resolution != null) return
            val observed = observation(original.display, allowUnknown = false) ?: return
            prepare(LocalRetailPhysicalRequest.FoodRefundResolve(original.display.recordingOperationId, observed))
            return
        }
        val review = currentReview() ?: return
        if (!review.originalUnknown || review.resolutionRecorded) return
        val display = review.reviewDisplay ?: return
        val observed = observation(display, allowUnknown = false) ?: return
        prepare(LocalRetailPhysicalRequest.Resolve(review.operationId, observed, review.subject))
    }

    private fun prepare(value: LocalRetailPhysicalRequest) {
        if (refundOnly && (value is LocalRetailPhysicalRequest.BeginCollection || value is LocalRetailPhysicalRequest.Payment)) return
        if (foodRefundLane && value !is LocalRetailPhysicalRequest.FoodRefund &&
            value !is LocalRetailPhysicalRequest.FoodRefundApply && value !is LocalRetailPhysicalRequest.FoodRefundResolve) return
        update(state.value.copy(problem = null, inputProblem = false, notice = null, capture = null))
        request(LocalRetailBusy.PREPARING) { gateway.prepare(value) }
    }

    fun discardPreview(token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.preview?.saveAttempted != false) return
        prepared = null
        update(state.value.copy(preview = null, problem = null, inputProblem = false))
    }

    fun stage(token: NavigationToken) {
        if (!accepts(token) || !parentAvailable() || state.value.working || instruction != null) return
        val exact = prepared ?: return
        val preview = state.value.preview ?: return
        if (exact.operationId != preview.operationId) return
        update(state.value.copy(preview = preview.copy(saveAttempted = true), operationId = exact.operationId))
        request(LocalRetailBusy.STAGING, exact.operationId) { gateway.stage(exact) }
    }

    private fun saved(id: OperationId): LocalRetailSavedOperation? = state.value.selected?.takeIf { it.operationId == id }
        ?: state.value.saved.singleOrNull { it.operationId == id }

    fun start(id: OperationId, token: NavigationToken) {
        if (!editable(token)) return
        val saved = saved(id)?.takeIf { it.isPhysicalStart && !it.dispatched && !it.recorded && it.summary.isNotEmpty() } ?: return
        if (refundOnly && saved.financialKind != LocalRetailFinancialKind.REFUND) return
        if (foodRefundLane && (saved.subject != LocalPaymentSubject.FOOD || saved.financialKind != LocalRetailFinancialKind.REFUND)) return
        // Attach the exact root+entry negative lifetime BEFORE the first credential/journal await.
        val original = runtime.retailPhysicalLifetime(session, token, attachmentKey) ?: return
        originalOwner = original
        update(state.value.copy(selected = saved.copy(dispatched = true), operationId = id, progress = null,
            saved = state.value.saved.map { if (it.operationId == id) it.copy(dispatched = true) else it },
            capture = null, observation = LocalRetailObservationDraft(), notice = null))
        request(LocalRetailBusy.STARTING, id, original) { gateway.start(id, original.lifetime) }
    }

    fun continueSql(id: OperationId, token: NavigationToken) {
        if (!editable(token)) return
        val saved = saved(id)?.takeIf { !it.isPhysicalStart && !it.recorded } ?: return
        if (refundOnly && saved.financialKind == LocalRetailFinancialKind.PAYMENT) return
        if (foodRefundLane && (saved.subject != LocalPaymentSubject.FOOD || saved.financialKind != LocalRetailFinancialKind.REFUND)) return
        update(state.value.copy(selected = saved.copy(dispatched = true), operationId = id, review = null,
            observation = LocalRetailObservationDraft(), notice = null))
        request(LocalRetailBusy.SQL, id) { gateway.continueSql(id) }
    }

    fun recoverInput(token: NavigationToken) {
        if (!editable(token)) return
        val id = parseOperation(state.value.recoveryId) ?: return
        recover(id, token)
    }

    fun recover(id: OperationId, token: NavigationToken) {
        if (!editable(token)) return
        update(state.value.copy(selected = saved(id), operationId = id, notice = null, progress = null))
        val food = state.value.workflow == LocalWorkflow.FOOD
        val refundPage = refundOnly
        request(LocalRetailBusy.RECOVERING, id) {
            if (foodRefundLane) gateway.recoverFoodRefund(id)
            else if (food) gateway.recoverFood(id) else if (refundPage) gateway.recoverRefundOperation(id) else gateway.recover(id)
        }
    }

    private fun parseOperation(value: String): OperationId? = try { OperationId(value) }
        catch (_: IllegalArgumentException) { invalid(); null }

    /** Read-only sampling of the actual private handle, never progress-to-prompt conversion. */
    fun instructionCurrent(token: NavigationToken): Boolean = accepts(token) && instruction?.let { exact ->
        state.value.instruction == exact.display && originalOwner?.lifetime?.denied == false && exact.deliverable()
    } == true

    /** UI polling only removes stale display; all authority still ends synchronously in real owners. */
    fun sampleInstruction(token: NavigationToken) {
        if (!accepts(token) || instruction == null) return
        if (!instructionCurrent(token)) endInstruction(token)
    }

    fun endInstruction(token: NavigationToken) {
        if (!accepts(token) || instruction == null && state.value.busy != LocalRetailBusy.STARTING) return
        denyOriginal() // MUST precede cancellation, presentation writes, or generation changes.
        activation++
        work?.cancel()
        work = null
        update(state.value.copy(busy = LocalRetailBusy.NONE, instruction = null, observation = LocalRetailObservationDraft(),
            notice = LocalText("Instruction ended. This is NOT a cancellation of the saved attempt or proof of no human action. Recover its original IDs; do not repeat the physical action.",
                "انتهت التعليمات. هذا ليس إلغاءً للمحاولة المحفوظة أو إثباتًا لعدم حدوث حركة بشرية. استرد المعرفات الأصلية ولا تكرر الحركة الفعلية.")))
    }

    fun capture(token: NavigationToken) {
        if (!accepts(token) || !parentAvailable() || state.value.working) return
        val exact = instruction ?: return
        val observed = observation(exact.display, allowUnknown = true) ?: return
        // No launch, callback, state emission or observer hop before the native immutable copy.
        val captured = try { exact.capture(observed) } catch (_: Exception) { LocalRetailPhysicalCapture.Uncertain }
        if (captured == LocalRetailPhysicalCapture.InvalidInput && accepts(token) && exact.deliverable()) { invalid(); return }
        denyOriginal()
        if (!accepts(token)) return
        update(state.value.copy(instruction = null, capture = captured, progress = null, review = null,
            operationId = exact.display.recordingOperationId, recoveryId = exact.display.recordingOperationId.value,
            observation = LocalRetailObservationDraft(), notice = null, problem = null))
        // Independent native retention/intake owns all later work. Even here we do not enqueue it
        // as an employee operation, inspect native bytes, auto-apply, or mint a replacement ID.
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun observation(display: LocalRetailInstructionDisplay, allowUnknown: Boolean): LocalRetailObservation? {
        val draft = state.value.observation
        if (!draft.confirmed || draft.selected == null || !allowUnknown && draft.selected == LocalRetailObserved.UNKNOWN) { invalid(); return null }
        // Cashier-attested occurrence time only, never a clock-derived permission or ordering key.
        val evidence = LocalRetailEvidence(DomainId.random(), UtcInstant(kotlin.time.Clock.System.now()))
        return when (draft.selected) {
            LocalRetailObserved.UNKNOWN -> LocalRetailObservation.Unknown(evidence)
            LocalRetailObserved.NOT_PAID -> if (draft.reason.isNotBlank()) LocalRetailObservation.NotPaid(evidence, draft.reason) else { invalid(); null }
            LocalRetailObserved.CASH_RECEIVED -> {
                val received = parseRetailMoney(draft.received, display.amount.currency)
                val change = parseRetailMoney(draft.change, display.amount.currency)
                if (display.kind != LocalRetailPhysicalKind.PAYMENT || received == null || change == null) { invalid(); null }
                else LocalRetailObservation.CashReceived(evidence, received, change)
            }
            LocalRetailObserved.CASH_PAID -> {
                if (display.kind != LocalRetailPhysicalKind.REFUND) { invalid(); null }
                else if (display.subject == LocalPaymentSubject.FOOD) {
                    // Entered actual amount only, including zero/mismatches. Never copy expected amount.
                    val actual = parseRetailMoney(draft.paid, display.amount.currency)
                    if (actual == null) { invalid(); null } else LocalRetailObservation.CashPaid(evidence, actual)
                } else LocalRetailObservation.CashPaid(evidence)
            }
            LocalRetailObserved.EXTERNAL_ATTESTED -> LocalRetailObservation.ExternalAttested(evidence,
                draft.terminal.takeIf { it.isNotEmpty() }, draft.reference.takeIf { it.isNotEmpty() })
            null -> null
        }
    }

    fun acknowledge(token: NavigationToken) {
        if (!editable(token)) return
        val saved = state.value.selected?.takeIf { it.recorded && it.operationId == state.value.progress?.operationId } ?: return
        run(LocalRetailBusy.ACKNOWLEDGING, saved.operationId) { ticket ->
            val result = acknowledgementGateway.acknowledgeRecorded(saved.operationId)
            currentCoroutineContext().ensureActive()
            if (!current(ticket)) return@run
            when (result) {
                is LocalAcknowledgementResult.Acknowledged -> {
                    if (result.operationId != saved.operationId) { unavailable(LocalServiceProblem.UNAVAILABLE); return@run }
                    update(state.value.copy(selected = null, saved = state.value.saved.filterNot { it.operationId == saved.operationId },
                        pendingLoaded = false, notice = LocalText("Client result acknowledged only. Native, SQL, cash and audit evidence are NOT removed; unresolved observations still require reconciliation.",
                            "أُقرّت نتيجة العميل فقط. لم تُحذف أدلة التخزين الأصلي وقاعدة البيانات والنقدية والتدقيق؛ ما زالت الإفادات غير المحسومة تتطلب تسوية.")))
                }
                is LocalAcknowledgementResult.Unresolved -> unavailable(result.problem)
            }
        }
    }

    fun collectionToOpen(token: NavigationToken): DomainId? = if (editable(token) && !refundOnly) state.value.progress?.collectionId else null
    fun collectionSubjectToOpen(token: NavigationToken): LocalPaymentSubject? =
        if (editable(token) && !refundOnly) state.value.progress?.subject else null

    private fun request(kind: LocalRetailBusy, id: OperationId? = null, original: LocalRetailPhysicalOwner? = null,
        call: suspend () -> LocalRetailPhysicalReply) {
        run(kind, id) { ticket ->
            var delivered = false
            var reply: LocalRetailPhysicalReply? = null
            try {
                val value = call()
                reply = value
                currentCoroutineContext().ensureActive()
                if (!current(ticket)) return@run
                if (value is LocalRetailPhysicalReply.Instruction) {
                    if (kind != LocalRetailBusy.STARTING || original == null || originalOwner !== original ||
                        value.value.display.operationId != id || original.lifetime.denied || !value.value.deliverable() ||
                        refundOnly && (value.value.display.kind != LocalRetailPhysicalKind.REFUND || value.value.display.subject != LocalPaymentSubject.RETAIL) ||
                        foodRefundLane && (value.value.display.kind != LocalRetailPhysicalKind.REFUND || value.value.display.subject != LocalPaymentSubject.FOOD)) {
                        value.value.deny(); unavailable(LocalServiceProblem.UNAVAILABLE); return@run
                    }
                    instruction = value.value
                    update(state.value.copy(instruction = value.value.display, problem = null, inputProblem = false))
                    delivered = true
                } else consume(value, id)
            } finally {
                if (!delivered) {
                    (reply as? LocalRetailPhysicalReply.Instruction)?.value?.deny()
                    original?.deny()
                    if (originalOwner === original) originalOwner = null
                }
            }
        }
    }

    private fun consume(reply: LocalRetailPhysicalReply, expected: OperationId?) {
        val actual = when (reply) {
            is LocalRetailPhysicalReply.Prepared -> reply.value.operationId
            is LocalRetailPhysicalReply.Staged -> reply.value.operationId
            is LocalRetailPhysicalReply.Progress -> reply.value.operationId
            is LocalRetailPhysicalReply.FoodRefundOriginal -> reply.value.original.operationId
            is LocalRetailPhysicalReply.Unavailable -> reply.operationId
            else -> null
        }
        if (expected != null && actual != null && expected != actual) { unavailable(LocalServiceProblem.UNAVAILABLE); return }
        when (reply) {
            is LocalRetailPhysicalReply.Prepared -> {
                if ((refundOnly || foodRefundLane) && reply.value.kind in setOf(LocalRetailPhysicalKind.PREPARE_COLLECTION, LocalRetailPhysicalKind.PAYMENT)) {
                    unavailable(LocalServiceProblem.UNAVAILABLE); return
                }
                prepared = reply.value
                update(state.value.copy(preview = LocalRetailPreview(reply.value.operationId, reply.value.kind, reply.value.title,
                    reply.value.summary.toList()), operationId = reply.value.operationId, selected = null, problem = null, inputProblem = false))
            }
            is LocalRetailPhysicalReply.Staged -> {
                prepared = null
                val value = reply.value
                if (refundOnly && (value.financialKind == LocalRetailFinancialKind.PAYMENT ||
                    value.isPhysicalStart && value.financialKind != LocalRetailFinancialKind.REFUND)) {
                    update(state.value.copy(preview = null)); unavailable(LocalServiceProblem.UNAVAILABLE); return
                }
                if (foodRefundLane && (value.subject != LocalPaymentSubject.FOOD || value.financialKind != LocalRetailFinancialKind.REFUND)) {
                    update(state.value.copy(preview = null)); unavailable(LocalServiceProblem.UNAVAILABLE); return
                }
                update(state.value.copy(preview = null, selected = value,
                    saved = state.value.saved.map { if (it.operationId == value.operationId) value else it },
                    operationId = value.operationId, progress = null, problem = null, pendingLoaded = false,
                    foodRefund = state.value.foodRefund.takeUnless { foodRefundLane }))
            }
            is LocalRetailPhysicalReply.Progress -> {
                val value = reply.value
                if (foodRefundLane && (value.subject != LocalPaymentSubject.FOOD || value.financialKind != LocalRetailFinancialKind.REFUND)) {
                    unavailable(LocalServiceProblem.UNAVAILABLE); return
                }
                val isRecorded = value.state !in setOf(LocalRetailDurableState.SAVED_NOT_DISPATCHED, LocalRetailDurableState.DISPATCH_UNRESOLVED)
                val review = value.takeIf { it.state == LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED }
                val linkedResolution = (value.subject == LocalPaymentSubject.FOOD || value.financialKind == LocalRetailFinancialKind.REFUND) &&
                    value.phase == LocalRetailObservationPhase.RESOLUTION && value.resolutionRecorded
                update(state.value.copy(progress = value, operationId = value.operationId, problem = null,
                    selected = state.value.selected?.let { if (it.operationId == value.operationId && isRecorded) it.copy(dispatched = true, recorded = true) else it },
                    review = review ?: state.value.review.takeUnless { linkedResolution },
                    reviewedOriginalId = review?.originalRecordingOperationId ?: value.originalRecordingOperationId.takeIf { linkedResolution }
                        ?: state.value.reviewedOriginalId,
                    reviewedSubject = review?.subject ?: value.subject.takeIf { linkedResolution } ?: state.value.reviewedSubject,
                    observation = if (review != null || linkedResolution) LocalRetailObservationDraft() else state.value.observation,
                    foodRefundOriginal = state.value.foodRefundOriginal.takeUnless { foodRefundLane && linkedResolution },
                    foodRefund = state.value.foodRefund.takeUnless { foodRefundLane }))
            }
            is LocalRetailPhysicalReply.Saved -> {
                if (refundOnly && (reply.values.any { it.financialKind == LocalRetailFinancialKind.PAYMENT ||
                    it.isPhysicalStart && it.financialKind != LocalRetailFinancialKind.REFUND } ||
                    reply.facts.any { it.financialKind != LocalRetailFinancialKind.REFUND || it.subject != LocalPaymentSubject.RETAIL })) {
                    unavailable(LocalServiceProblem.UNAVAILABLE); return
                }
                if (foodRefundLane && (reply.values.any { it.subject != LocalPaymentSubject.FOOD || it.financialKind != LocalRetailFinancialKind.REFUND } ||
                    reply.facts.any { it.subject != LocalPaymentSubject.FOOD || it.financialKind != LocalRetailFinancialKind.REFUND })) {
                    unavailable(LocalServiceProblem.UNAVAILABLE); return
                }
                update(state.value.copy(saved = reply.values.toList(), facts = reply.facts.toList(),
                    selected = state.value.selected?.let { selected -> reply.values.singleOrNull { it.operationId == selected.operationId } ?: selected },
                    nextFactAfter = reply.nextFactAfter, pendingLoaded = true, problem = null))
            }
            is LocalRetailPhysicalReply.PaymentContext -> {
                if (refundOnly || foodRefundLane) { unavailable(LocalServiceProblem.UNAVAILABLE); return }
                update(state.value.copy(payment = reply.value.copy(externalTenders = reply.value.externalTenders.toList()),
                    paymentAmount = retailAmount(reply.value.remaining), tenderId = null, problem = null, inputProblem = false))
            }
            is LocalRetailPhysicalReply.RefundDues -> {
                if (foodRefundLane) { unavailable(LocalServiceProblem.UNAVAILABLE); return }
                if (state.value.duesForSelectedReturn) {
                    val reference = state.value.reference?.takeIf { it.kind == LocalRecordKind.RETURN_FINANCE }
                    val caseId = reference?.parentId
                    if (reference == null || caseId == null) {
                        unavailable(LocalServiceProblem.UNAVAILABLE); return
                    }
                    if (reply.nextAfter != null || reply.values.any { it.returnId != reference.id || it.claimId != caseId } ||
                        reply.recoveries.any { it.returnId != reference.id || it.claimId != caseId }) {
                        unavailable(LocalServiceProblem.UNAVAILABLE); return
                    }
                }
                update(state.value.copy(dues = reply.values.toList(), refundRecoveries = reply.recoveries.toList(),
                    nextDueAfter = reply.nextAfter, duesLoaded = true, problem = null))
            }
            is LocalRetailPhysicalReply.FoodRefundContext -> {
                val value = reply.value
                if (!foodRefundLane || value.refundId != state.value.foodRefundId ||
                    value.dues.any { it.refundId != value.refundId } || value.ownRecoveries.any { it.refundId != value.refundId }) {
                    unavailable(LocalServiceProblem.UNAVAILABLE); return
                }
                update(state.value.copy(foodRefund = value.copy(dues = value.dues.toList(), ownRecoveries = value.ownRecoveries.toList()), problem = null))
            }
            is LocalRetailPhysicalReply.FoodRefundOriginal -> {
                val value = reply.value
                val display = value.display
                val original = value.original
                val resolution = value.resolution
                if (!foodRefundLane || display.subject != LocalPaymentSubject.FOOD || display.kind != LocalRetailPhysicalKind.REFUND ||
                    original.subject != LocalPaymentSubject.FOOD || original.financialKind != LocalRetailFinancialKind.REFUND ||
                    original.phase != LocalRetailObservationPhase.ORIGINAL || original.recordingOperationId != display.recordingOperationId ||
                    original.originalRecordingOperationId != display.recordingOperationId ||
                    !canPrepareOwnApplication(original) || resolution != null &&
                    (resolution.subject != LocalPaymentSubject.FOOD || resolution.financialKind != LocalRetailFinancialKind.REFUND ||
                        resolution.phase != LocalRetailObservationPhase.RESOLUTION || resolution.recordingOperationId != display.resolutionOperationId ||
                        resolution.originalRecordingOperationId != display.recordingOperationId || !canPrepareOwnApplication(resolution))) {
                    unavailable(LocalServiceProblem.UNAVAILABLE); return
                }
                // Private immutable read DATA, never relabelled as SUPERVISOR_REVIEW_RECORDED.
                update(state.value.copy(foodRefundOriginal = value, foodRefundOriginalId = display.recordingOperationId,
                    recoveryId = display.recordingOperationId.value, operationId = original.operationId, progress = original,
                    review = null, reviewedOriginalId = null, reviewedSubject = null,
                    observation = LocalRetailObservationDraft(), problem = null))
            }
            is LocalRetailPhysicalReply.Unavailable -> unavailable(reply.problem)
            is LocalRetailPhysicalReply.Instruction -> { reply.value.deny(); unavailable(LocalServiceProblem.UNAVAILABLE) }
        }
    }

    private fun unavailable(problem: LocalServiceProblem) {
        if (problem == LocalServiceProblem.SESSION_ENDED) {
            val cleanup = LocalSessionCleanup()
            cleanup.attempt { denyOriginal() }
            cleanup.attempt { runtime.localSessionEnded(session) }
            cleanup.throwIfFailed()
            return
        }
        if (problem == LocalServiceProblem.DENIED) {
            val cleanup = LocalSessionCleanup()
            cleanup.attempt { denyOriginal() }
            prepared = null
            cleanup.attempt { update(state.value.copy(preview = null, selected = null, capture = null,
                observation = LocalRetailObservationDraft(), payment = null, dues = emptyList(), refundRecoveries = emptyList(),
                duesLoaded = false, duesForSelectedReturn = false, nextDueAfter = null, saved = emptyList(), facts = emptyList(),
                instruction = null, review = null, reviewedOriginalId = null, reviewedSubject = null, progress = null,
                foodRefund = null, foodRefundOriginal = null, foodRefundOriginalId = null,
                pendingLoaded = false, resolutionPendingPage = false, nextFactAfter = null, problem = problem)) }
            cleanup.throwIfFailed()
        } else update(state.value.copy(problem = problem))
    }

    private fun run(kind: LocalRetailBusy, id: OperationId?, block: suspend (Ticket) -> Unit) {
        if (!current() || !parentAvailable() || state.value.working) { originalOwner?.deny(); originalOwner = null; return }
        update(state.value.copy(busy = kind, problem = null, inputProblem = false, operationId = id ?: state.value.operationId))
        // Parent owns the one leave guard. Capture AFTER its synchronous registration.
        val ticket = Ticket(activation, runtime.navigator.token())
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { if (current(ticket)) block(ticket) }
            catch (cancelled: CancellationException) {
                if (current(ticket)) unavailable(LocalServiceProblem.UNAVAILABLE)
                throw cancelled
            } catch (_: Exception) { if (current(ticket)) unavailable(LocalServiceProblem.UNAVAILABLE) }
            finally {
                if (current(ticket.activation)) {
                    if (!runtime.navigator.isCurrent(ticket.navigation)) {
                        denyOriginal()
                        prepared = null
                        mutableState.value = LocalRetailWorkspace(workflow = state.value.workflow, reference = state.value.reference,
                            foodCollectionId = state.value.foodCollectionId, foodRefundId = state.value.foodRefundId,
                            operationId = id, recoveryId = id?.value.orEmpty(), problem = LocalServiceProblem.UNAVAILABLE)
                    }
                    work = null
                    update(state.value.copy(busy = LocalRetailBusy.NONE))
                }
            }
        }
        work = job
        job.start()
    }
}

internal val retailWorkflows: Set<LocalWorkflow> = setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS,
    LocalWorkflow.EXCEPTIONS, LocalWorkflow.RETURNS, LocalWorkflow.FOOD)

/** Exact nonnegative decimal input; no Double, exponent, rounding or inferred currency. */
internal fun parseRetailMoney(text: String, currency: Currency): Money? {
    if (currency.fractionDigits != 2 || text.length !in 1..32) return null
    val normalized = text.map { character -> when (character) {
        in '٠'..'٩' -> '0' + (character - '٠')
        in '۰'..'۹' -> '0' + (character - '۰')
        '٫' -> '.'
        else -> character
    } }.joinToString("")
    if (!Regex("(?:0|[1-9][0-9]{0,18})(?:\\.[0-9]{1,2})?").matches(normalized)) return null
    val parts = normalized.split('.')
    val digits = parts[0] + parts.getOrElse(1) { "" }.padEnd(2, '0')
    val minor = digits.toLongOrNull() ?: return null
    return Money(currency, minor)
}

internal fun retailAmount(value: Money): String {
    val signed = value.minor.toString()
    val digits = signed.removePrefix("-").padStart(value.currency.fractionDigits + 1, '0')
    return (if (value.minor < 0L) "-" else "") + digits.dropLast(value.currency.fractionDigits) + "." + digits.takeLast(value.currency.fractionDigits)
}
internal fun retailMoney(value: Money): String = "${retailAmount(value)} ${value.currency.name}"
