package com.ukkera.pos.app

import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
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

internal enum class LocalSupplierBusy { NONE, READING, PREPARING, STAGING, STARTING, SQL, RECOVERING, ACKNOWLEDGING }
internal enum class LocalSupplierObserved { UNKNOWN, NOT_PAID, CASH_PAID, EXTERNAL_ATTESTED }
internal enum class LocalSupplierInput { SUPPLIER_ID, INVOICE_ID, PAYOUT, REASON, ACTUAL_AMOUNT, ACTUAL_CURRENCY, NONPAYMENT_REASON, EXTERNAL_REFERENCE, RECOVERY_ID }
internal data class LocalSupplierObservationDraft(val selected: LocalSupplierObserved? = null,
    val actualAmount: String = "", val actualCurrency: String = "", val nonpaymentReason: String = "",
    val externalReference: String = "", val confirmed: Boolean = false)

/** Data-only continuation. New supplier actions still require current master/native admission. */
internal data class LocalSupplierDraftContinuation(
    val reference: LocalRecordReference?, val query: LocalPurchasingUiQuery,
    val supplierId: String, val invoiceId: String, val payout: String, val reason: String,
    val observation: LocalSupplierObservationDraft, val recoveryId: String, val operationId: OperationId?,
)

/** Ephemeral purchasing display only. No native handle, employee grant or lifetime is persisted. */
internal data class LocalSupplierCashWorkspace(
    val reference: LocalRecordReference? = null,
    val purchasingQuery: LocalPurchasingUiQuery = LocalPurchasingUiQuery(),
    val supplierId: String = "", val invoiceId: String = "", val payout: String = "", val reason: String = "",
    val busy: LocalSupplierBusy = LocalSupplierBusy.NONE,
    val preview: LocalRetailPreview? = null,
    val selected: LocalRetailSavedOperation? = null,
    val saved: List<LocalRetailSavedOperation> = emptyList(),
    val facts: List<LocalRetailPhysicalProgress> = emptyList(),
    val pendingLoaded: Boolean = false,
    /** Actual underlying ORIGINAL-ID cursor. Resolution facts never replace its meaning. */
    val nextFactAfter: DomainId? = null,
    val instruction: LocalRetailInstructionDisplay? = null,
    val observation: LocalSupplierObservationDraft = LocalSupplierObservationDraft(),
    val progress: LocalRetailPhysicalProgress? = null,
    val capture: LocalRetailPhysicalCapture? = null,
    val recoveryId: String = "",
    val operationId: OperationId? = null,
    val problem: LocalServiceProblem? = null,
    val inputProblem: Boolean = false,
    val notice: LocalText? = null,
    /** Negative-only presentation gate; original evidence/recovery remains available. */
    val freshPayoutAllowed: Boolean = false,
) {
    val working: Boolean get() = busy != LocalSupplierBusy.NONE
    val canPrepare: Boolean get() = !working && instruction == null && preview == null
}

/**
 * Separate supplier-cash entry owner. Customer payment/refund, supervisor takeover and FOOD
 * economics never route here. All authority is freshly checked by the real gateway/kernel.
 * Parent synchronously deactivates this controller BEFORE changing its context or cancelling.
 */
internal class LocalSupplierCashController(
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
    private val mutableState = MutableStateFlow(LocalSupplierCashWorkspace())
    val state: StateFlow<LocalSupplierCashWorkspace> = mutableState.asStateFlow()
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

    fun activate(workflow: LocalWorkflow, reference: LocalRecordReference?, query: LocalPurchasingUiQuery,
        selectedSupplierId: DomainId? = null, freshPayoutAllowed: Boolean = reference?.kind != LocalRecordKind.PURCHASE_INVOICE) {
        deactivate()
        if (workflow != LocalWorkflow.PURCHASING || !query.includeCosts || !runtime.ownsLocalAttachment(session, owner, attachmentKey)) return
        active = true
        val supplierId = reference?.takeIf { it.kind == LocalRecordKind.SUPPLIER }?.id ?: selectedSupplierId
        val invoiceId = reference?.takeIf { it.kind in setOf(LocalRecordKind.PURCHASE_INVOICE, LocalRecordKind.SUPPLIER_PAYABLE) }?.id
        update(LocalSupplierCashWorkspace(reference = reference, purchasingQuery = query,
            supplierId = supplierId?.value.orEmpty(), invoiceId = invoiceId?.value.orEmpty(), freshPayoutAllowed = freshPayoutAllowed))
    }

    fun snapshotDraft(): LocalSupplierDraftContinuation? = state.value.takeIf { active }?.let { value ->
        LocalSupplierDraftContinuation(value.reference, value.purchasingQuery, value.supplierId, value.invoiceId,
            value.payout, value.reason, value.observation.copy(selected = null, confirmed = false),
            value.recoveryId.ifEmpty { value.operationId?.value.orEmpty() }, value.operationId)
    }

    fun restoreDraft(value: LocalSupplierDraftContinuation) {
        if (!current() || value.reference != state.value.reference || value.query != state.value.purchasingQuery) return
        update(state.value.copy(supplierId = value.supplierId, invoiceId = value.invoiceId, payout = value.payout,
            reason = value.reason, observation = value.observation, recoveryId = value.recoveryId, operationId = value.operationId,
            notice = LocalText("Session renewed. Previous cash instructions ended. Read the original saved operation; no supplier payout was repeated.",
                "جُددت الجلسة وانتهت تعليمات النقد السابقة. اقرأ العملية الأصلية المحفوظة؛ لم يتكرر أي دفع للمورد.")))
    }

    /** Negative-only lifetime end comes before any state, generation or cancellation write. */
    fun deactivate() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { denyOriginal() }
        active = false
        activation++
        cleanup.attempt { work?.cancel(); work = null }
        prepared = null
        mutableState.value = LocalSupplierCashWorkspace()
        cleanup.attempt { guardChanged() }
        cleanup.throwIfFailed()
    }

    private fun denyOriginal() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { originalOwner?.deny(); originalOwner = null }
        // End-only. Failed exact native holders remain retained; clearing presentation
        // never cancels accepted/uncertain custody or permits a new instruction.
        cleanup.attempt { instruction?.deny(); instruction = null }
        if (cleanup.failed) {
            // Every caller, including explicit instruction end/capture and stale
            // replies, must stop publishing if an exact negative holder failed.
            val previous = state.value
            active = false
            activation++
            prepared = null
            mutableState.value = LocalSupplierCashWorkspace(operationId = previous.operationId,
                recoveryId = previous.recoveryId, problem = LocalServiceProblem.UNAVAILABLE)
            cleanup.attempt { work?.cancel(); work = null }
            cleanup.attempt { guardChanged() }
        }
        cleanup.throwIfFailed()
    }

    private fun current(generation: Long = activation): Boolean = active && generation == activation && runtime.ownsLocalAttachment(session, owner, attachmentKey)
    private fun accepts(token: NavigationToken): Boolean = current() && runtime.navigator.isCurrent(token) &&
        token.rootGeneration == owner.rootGeneration && token.entryId == owner.entryId && token.area == owner.area
    private data class Ticket(val activation: Long, val navigation: NavigationToken)
    private fun current(ticket: Ticket): Boolean = current(ticket.activation) && runtime.navigator.isCurrent(ticket.navigation)
    fun displayCurrent(token: NavigationToken): Boolean = accepts(token)
    private fun editable(token: NavigationToken): Boolean = accepts(token) && parentAvailable() && state.value.canPrepare
    private fun update(value: LocalSupplierCashWorkspace) { mutableState.value = value; guardChanged() }
    private fun invalid() { update(state.value.copy(inputProblem = true, problem = null)) }

    fun changeInput(field: LocalSupplierInput, value: String, token: NavigationToken) {
        if (!accepts(token) || !parentAvailable() || state.value.working || prepared != null) return
        val observationField = field in setOf(LocalSupplierInput.ACTUAL_AMOUNT, LocalSupplierInput.ACTUAL_CURRENCY,
            LocalSupplierInput.NONPAYMENT_REASON, LocalSupplierInput.EXTERNAL_REFERENCE)
        if (!observationField && instruction != null) return
        val limit = when (field) {
            LocalSupplierInput.SUPPLIER_ID, LocalSupplierInput.INVOICE_ID, LocalSupplierInput.RECOVERY_ID -> 36
            LocalSupplierInput.PAYOUT, LocalSupplierInput.ACTUAL_CURRENCY -> 32
            LocalSupplierInput.ACTUAL_AMOUNT -> 96
            else -> 128
        }
        if (value.length > limit) return
        val before = state.value
        val draft = before.observation
        val changed = when (field) {
            LocalSupplierInput.SUPPLIER_ID -> before.copy(supplierId = value)
            LocalSupplierInput.INVOICE_ID -> before.copy(invoiceId = value)
            LocalSupplierInput.PAYOUT -> before.copy(payout = value)
            LocalSupplierInput.REASON -> before.copy(reason = value)
            LocalSupplierInput.RECOVERY_ID -> before.copy(recoveryId = value)
            LocalSupplierInput.ACTUAL_AMOUNT -> before.copy(observation = draft.copy(actualAmount = value, confirmed = false))
            LocalSupplierInput.ACTUAL_CURRENCY -> before.copy(observation = draft.copy(actualCurrency = value, confirmed = false))
            LocalSupplierInput.NONPAYMENT_REASON -> before.copy(observation = draft.copy(nonpaymentReason = value, confirmed = false))
            LocalSupplierInput.EXTERNAL_REFERENCE -> before.copy(observation = draft.copy(externalReference = value, confirmed = false))
        }
        update(changed.copy(inputProblem = false))
    }

    fun chooseObservation(value: LocalSupplierObserved, token: NavigationToken) {
        if (!accepts(token) || !parentAvailable() || state.value.working || prepared != null) return
        if (instruction == null && !canResolve(state.value.progress)) return
        if (instruction == null && value == LocalSupplierObserved.UNKNOWN) return
        update(state.value.copy(observation = state.value.observation.copy(selected = value, confirmed = false), inputProblem = false))
    }

    fun confirmObservation(value: Boolean, token: NavigationToken) {
        if (accepts(token) && parentAvailable() && !state.value.working && prepared == null &&
            (instruction != null || canResolve(state.value.progress)))
            update(state.value.copy(observation = state.value.observation.copy(confirmed = value), inputProblem = false))
    }

    fun preparePayout(token: NavigationToken) {
        if (!editable(token) || !state.value.freshPayoutAllowed) return
        val supplier = try { DomainId(state.value.supplierId) } catch (_: IllegalArgumentException) { invalid(); return }
        val invoice = if (state.value.invoiceId.isEmpty()) null else try { DomainId(state.value.invoiceId) }
            catch (_: IllegalArgumentException) { invalid(); return }
        val amount = parseRetailMoney(state.value.payout, Currency.EGP)
        if (amount == null || amount.minor <= 0L || !supplierReason(state.value.reason)) { invalid(); return }
        prepare(LocalRetailPhysicalRequest.SupplierCash(supplier, invoice, amount, state.value.reason), LocalRetailPhysicalKind.SUPPLIER_CASH_OUT)
    }

    /** Presentation routing only. A result-only projection never supplies current custody. */
    fun canPrepareOwnApplication(value: LocalRetailPhysicalProgress): Boolean = ownFact(value) && when (value.phase) {
        LocalRetailObservationPhase.ORIGINAL -> value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING
        LocalRetailObservationPhase.RESOLUTION -> value.state == LocalRetailDurableState.SQL_RESOLUTION_PENDING
        null -> false
    }

    fun prepareApply(value: LocalRetailPhysicalProgress, token: NavigationToken) {
        if (!editable(token) || !displayed(value) || !canPrepareOwnApplication(value)) return
        val original = value.originalRecordingOperationId ?: return
        val phase = value.phase ?: return
        prepare(LocalRetailPhysicalRequest.SupplierCashApply(original, phase), LocalRetailPhysicalKind.APPLICATION)
    }

    fun readOriginal(value: LocalRetailPhysicalProgress, token: NavigationToken) {
        if (!editable(token) || !displayed(value) || !supplierProgress(value)) return
        val original = value.originalRecordingOperationId ?: return
        recover(original, token)
    }

    fun canResolve(value: LocalRetailPhysicalProgress?): Boolean = value != null && ownFact(value) &&
        value.phase == LocalRetailObservationPhase.ORIGINAL && value.state == LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED &&
        value.originalUnknown && value.originalApplied && !value.resolutionRecorded

    fun resolve(token: NavigationToken) {
        if (!editable(token)) return
        val value = state.value.progress?.takeIf(::canResolve) ?: return
        val original = value.originalRecordingOperationId ?: return
        val observed = observation(allowUnknown = false) ?: return
        prepare(LocalRetailPhysicalRequest.SupplierCashResolve(original, observed), LocalRetailPhysicalKind.RESOLUTION,
            expected = value.resolutionOperationId)
    }

    private fun displayed(value: LocalRetailPhysicalProgress): Boolean = state.value.progress === value || state.value.facts.any { it === value }
    private fun ownFact(value: LocalRetailPhysicalProgress): Boolean {
        if (!supplierProgress(value) || value.originalActorId != session.display.actorId || value.recordingActorId != session.display.actorId) return false
        val original = value.originalRecordingOperationId ?: return false
        val resolution = value.resolutionOperationId ?: return false
        val display = value.reviewDisplay ?: return false
        return original != resolution && display.recordingOperationId == original && display.resolutionOperationId == resolution &&
            value.recordingOperationId == when (value.phase) {
                LocalRetailObservationPhase.ORIGINAL -> original
                LocalRetailObservationPhase.RESOLUTION -> resolution
                null -> return false
            }
    }

    private fun prepare(value: LocalRetailPhysicalRequest, kind: LocalRetailPhysicalKind, expected: OperationId? = null) {
        update(state.value.copy(problem = null, inputProblem = false, notice = null, capture = null))
        request(LocalSupplierBusy.PREPARING, expected, preparationKind = kind) { gateway.prepare(value) }
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
        if (exact.operationId != preview.operationId || !supplierPreparation(exact, preview.kind)) return
        update(state.value.copy(preview = preview.copy(saveAttempted = true), operationId = exact.operationId))
        request(LocalSupplierBusy.STAGING, exact.operationId, preparationKind = exact.kind) { gateway.stage(exact) }
    }

    private fun saved(id: OperationId): LocalRetailSavedOperation? = state.value.selected?.takeIf { it.operationId == id && supplierSaved(it) }
        ?: state.value.saved.singleOrNull { it.operationId == id && supplierSaved(it) }

    fun start(id: OperationId, token: NavigationToken) {
        if (!editable(token)) return
        val saved = saved(id)?.takeIf { it.isPhysicalStart && !it.dispatched && !it.recorded && it.summary.isNotEmpty() } ?: return
        // Actual root/session/entry negative lifetime is attached BEFORE any credential/journal await.
        val original = runtime.retailPhysicalLifetime(session, token, attachmentKey) ?: return
        originalOwner = original
        update(state.value.copy(selected = saved.copy(dispatched = true), operationId = id, progress = null,
            saved = state.value.saved.map { if (it.operationId == id) it.copy(dispatched = true) else it },
            capture = null, observation = LocalSupplierObservationDraft(), notice = null))
        request(LocalSupplierBusy.STARTING, id, original) { gateway.start(id, original.lifetime) }
    }

    fun continueSql(id: OperationId, token: NavigationToken) {
        if (!editable(token)) return
        val saved = saved(id)?.takeIf { !it.isPhysicalStart && !it.recorded } ?: return
        update(state.value.copy(selected = saved.copy(dispatched = true), operationId = id, observation = LocalSupplierObservationDraft(), notice = null))
        request(LocalSupplierBusy.SQL, id) { gateway.continueSql(id) }
    }

    fun loadPending(token: NavigationToken, next: Boolean = false) {
        if (!editable(token)) return
        val after = if (next) state.value.nextFactAfter ?: return else null
        update(state.value.copy(saved = emptyList(), facts = emptyList(), pendingLoaded = false, nextFactAfter = null))
        request(LocalSupplierBusy.READING) { gateway.pendingSupplierCash(after) }
    }

    fun recoverInput(token: NavigationToken) {
        if (!editable(token)) return
        val id = try { OperationId(state.value.recoveryId) } catch (_: IllegalArgumentException) { invalid(); return }
        recover(id, token)
    }

    fun recover(id: OperationId, token: NavigationToken) {
        if (!editable(token)) return
        update(state.value.copy(selected = saved(id), operationId = id, recoveryId = id.value, progress = null,
            observation = LocalSupplierObservationDraft(), notice = null))
        request(LocalSupplierBusy.RECOVERING, id) { gateway.recoverSupplierCash(id) } // No native rearm.
    }

    fun instructionCurrent(token: NavigationToken): Boolean = accepts(token) && instruction?.let { exact ->
        supplierDisplay(exact.display) && state.value.instruction == exact.display && originalOwner?.lifetime?.denied == false && exact.deliverable()
    } == true

    fun sampleInstruction(token: NavigationToken) {
        if (accepts(token) && instruction != null && !instructionCurrent(token)) endInstruction(token)
    }

    fun endInstruction(token: NavigationToken) {
        if (!accepts(token) || instruction == null && state.value.busy != LocalSupplierBusy.STARTING) return
        denyOriginal() // Before cancellation or state/generation changes.
        activation++
        work?.cancel()
        work = null
        update(state.value.copy(busy = LocalSupplierBusy.NONE, instruction = null, observation = LocalSupplierObservationDraft(),
            notice = LocalText("Supplier instruction ended, not the durable attempt or native custody. Recover its original IDs; absence is never proof of NOT PAID or permission to pay again.",
                "انتهت تعليمات المورد، لا المحاولة الدائمة أو العهدة الأصلية. استرد المعرفات الأصلية؛ لا يثبت الغياب عدم الدفع ولا يجيز الدفع مجددًا.")))
    }

    fun capture(token: NavigationToken) {
        if (!accepts(token) || !parentAvailable() || state.value.working) return
        val exact = instruction ?: return
        if (!supplierDisplay(exact.display)) return
        val observed = observation(allowUnknown = true) ?: return
        // NO launch, state emission, callback or worker hop before actual synchronous native capture.
        val captured = try { exact.capture(observed) } catch (_: Exception) { LocalRetailPhysicalCapture.Uncertain }
        if (captured == LocalRetailPhysicalCapture.InvalidInput && accepts(token) && exact.deliverable()) { invalid(); return }
        denyOriginal()
        if (!accepts(token)) return
        update(state.value.copy(instruction = null, capture = captured, progress = null,
            operationId = exact.display.recordingOperationId, recoveryId = exact.display.recordingOperationId.value,
            observation = LocalSupplierObservationDraft(), notice = null, problem = null))
        // Native custody independently owns retention/intake. No enqueue, auto-Apply or new ID.
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun observation(allowUnknown: Boolean): LocalRetailObservation? {
        val draft = state.value.observation
        if (!draft.confirmed || draft.selected == null || !allowUnknown && draft.selected == LocalSupplierObserved.UNKNOWN) { invalid(); return null }
        val evidence = LocalRetailEvidence(DomainId.random(), UtcInstant(kotlin.time.Clock.System.now()))
        return when (draft.selected) {
            LocalSupplierObserved.UNKNOWN -> LocalRetailObservation.Unknown(evidence)
            LocalSupplierObserved.NOT_PAID -> if (supplierReason(draft.nonpaymentReason)) LocalRetailObservation.NotPaid(evidence, draft.nonpaymentReason)
                else { invalid(); null }
            LocalSupplierObserved.CASH_PAID, LocalSupplierObserved.EXTERNAL_ATTESTED -> {
                val actual = parseSupplierObservedAmount(draft.actualCurrency, draft.actualAmount)
                if (actual == null) { invalid(); null }
                else if (draft.selected == LocalSupplierObserved.CASH_PAID) LocalRetailObservation.CashPaid(evidence, literalSupplierAmount = actual)
                else LocalRetailObservation.ExternalAttested(evidence, null, draft.externalReference.takeIf { it.isNotEmpty() }, literalSupplierAmount = actual)
            }
            null -> null
        }
    }

    fun acknowledge(token: NavigationToken) {
        if (!editable(token)) return
        val saved = state.value.selected?.takeIf { it.recorded && supplierSaved(it) && it.operationId == state.value.progress?.operationId } ?: return
        run(LocalSupplierBusy.ACKNOWLEDGING, saved.operationId) { ticket ->
            val result = acknowledgementGateway.acknowledgeRecorded(saved.operationId)
            currentCoroutineContext().ensureActive()
            if (!current(ticket)) return@run
            when (result) {
                is LocalAcknowledgementResult.Acknowledged -> {
                    if (result.operationId != saved.operationId) { unavailable(LocalServiceProblem.UNAVAILABLE); return@run }
                    update(state.value.copy(selected = null, saved = state.value.saved.filterNot { it.operationId == saved.operationId }, pendingLoaded = false,
                        notice = LocalText("Client result acknowledged only. Supplier native, cash, invoice, allocation and audit evidence stays retained; unresolved observations still require genuine reconciliation.",
                            "أُقرّت نتيجة العميل فقط. تبقى أدلة المورد الأصلية والنقدية والفاتورة والتخصيص والتدقيق محفوظة؛ وما زالت الإفادات المعلقة تتطلب تسوية حقيقية.")))
                }
                is LocalAcknowledgementResult.Unresolved -> unavailable(result.problem)
            }
        }
    }

    private fun request(kind: LocalSupplierBusy, id: OperationId? = null, original: LocalRetailPhysicalOwner? = null,
        preparationKind: LocalRetailPhysicalKind? = null, call: suspend () -> LocalRetailPhysicalReply) {
        run(kind, id) { ticket ->
            var delivered = false
            var reply: LocalRetailPhysicalReply? = null
            try {
                val value = call()
                reply = value
                currentCoroutineContext().ensureActive()
                if (!current(ticket)) return@run
                if (value is LocalRetailPhysicalReply.Instruction) {
                    if (kind != LocalSupplierBusy.STARTING || original == null || originalOwner !== original ||
                        value.value.display.operationId != id || !supplierDisplay(value.value.display) || original.lifetime.denied || !value.value.deliverable()) {
                        value.value.deny(); unavailable(LocalServiceProblem.UNAVAILABLE); return@run
                    }
                    instruction = value.value
                    update(state.value.copy(instruction = value.value.display, problem = null, inputProblem = false))
                    delivered = true
                } else consume(value, id, preparationKind, kind)
            } finally {
                if (!delivered) {
                    (reply as? LocalRetailPhysicalReply.Instruction)?.value?.deny()
                    original?.deny()
                    if (originalOwner === original) originalOwner = null
                }
            }
        }
    }

    private fun consume(reply: LocalRetailPhysicalReply, expected: OperationId?, preparationKind: LocalRetailPhysicalKind?, kind: LocalSupplierBusy) {
        val allowed = when (kind) {
            LocalSupplierBusy.PREPARING -> reply is LocalRetailPhysicalReply.Prepared || reply is LocalRetailPhysicalReply.Staged
            LocalSupplierBusy.STAGING -> reply is LocalRetailPhysicalReply.Staged
            LocalSupplierBusy.STARTING, LocalSupplierBusy.SQL, LocalSupplierBusy.RECOVERING -> reply is LocalRetailPhysicalReply.Progress
            LocalSupplierBusy.READING -> reply is LocalRetailPhysicalReply.Saved
            LocalSupplierBusy.NONE, LocalSupplierBusy.ACKNOWLEDGING -> false
        }
        if (!allowed && reply !is LocalRetailPhysicalReply.Unavailable) { unavailable(LocalServiceProblem.UNAVAILABLE); return }
        val actual = when (reply) {
            is LocalRetailPhysicalReply.Prepared -> reply.value.operationId
            is LocalRetailPhysicalReply.Staged -> reply.value.operationId
            is LocalRetailPhysicalReply.Progress -> reply.value.operationId
            is LocalRetailPhysicalReply.Unavailable -> reply.operationId
            else -> null
        }
        if (expected != null && actual != null && expected != actual) { unavailable(LocalServiceProblem.UNAVAILABLE); return }
        when (reply) {
            is LocalRetailPhysicalReply.Prepared -> {
                val value = reply.value
                if (preparationKind == null || !supplierPreparation(value, preparationKind)) { unavailable(LocalServiceProblem.UNAVAILABLE); return }
                prepared = value
                update(state.value.copy(preview = LocalRetailPreview(value.operationId, value.kind, value.title, value.summary.toList()),
                    operationId = value.operationId, selected = null, problem = null, inputProblem = false))
            }
            is LocalRetailPhysicalReply.Staged -> {
                val value = reply.value
                if (!supplierSaved(value) || preparationKind == null || value.isPhysicalStart != (preparationKind == LocalRetailPhysicalKind.SUPPLIER_CASH_OUT) ||
                    value.requiresNativeRetention != (preparationKind == LocalRetailPhysicalKind.RESOLUTION)) { unavailable(LocalServiceProblem.UNAVAILABLE); return }
                prepared = null
                update(state.value.copy(preview = null, selected = value,
                    saved = state.value.saved.map { if (it.operationId == value.operationId) value else it }, operationId = value.operationId,
                    progress = null, problem = null, pendingLoaded = false))
            }
            is LocalRetailPhysicalReply.Progress -> {
                val value = reply.value
                if (!supplierProgress(value)) { unavailable(LocalServiceProblem.UNAVAILABLE); return }
                val recorded = value.state !in setOf(LocalRetailDurableState.SAVED_NOT_DISPATCHED, LocalRetailDurableState.DISPATCH_UNRESOLVED)
                update(state.value.copy(progress = value.copy(reviewValues = value.reviewValues.toList()), operationId = value.operationId, problem = null,
                    selected = state.value.selected?.let { if (it.operationId == value.operationId && recorded) it.copy(dispatched = true, recorded = true) else it },
                    observation = LocalSupplierObservationDraft()))
            }
            is LocalRetailPhysicalReply.Saved -> {
                if (reply.values.any { !supplierSaved(it) } || reply.values.map { it.operationId }.distinct().size != reply.values.size ||
                    reply.facts.size > 5 || reply.facts.any { !ownFact(it) } ||
                    reply.facts.map { it.originalRecordingOperationId }.distinct().size != reply.facts.size) { unavailable(LocalServiceProblem.UNAVAILABLE); return }
                update(state.value.copy(saved = reply.values.map { it.copy(summary = it.summary.toList()) },
                    facts = reply.facts.map { it.copy(reviewValues = it.reviewValues.toList()) }, nextFactAfter = reply.nextFactAfter, pendingLoaded = true,
                    selected = state.value.selected?.let { selected -> reply.values.singleOrNull { it.operationId == selected.operationId } ?: selected }, problem = null))
            }
            is LocalRetailPhysicalReply.Unavailable -> unavailable(reply.problem)
            is LocalRetailPhysicalReply.Instruction -> { reply.value.deny(); unavailable(LocalServiceProblem.UNAVAILABLE) }
            is LocalRetailPhysicalReply.PaymentContext, is LocalRetailPhysicalReply.RefundDues,
            is LocalRetailPhysicalReply.FoodRefundContext, is LocalRetailPhysicalReply.FoodRefundOriginal -> unavailable(LocalServiceProblem.UNAVAILABLE)
        }
    }

    private fun supplierPreparation(value: LocalRetailPhysicalPreparation, kind: LocalRetailPhysicalKind): Boolean =
        value.subject == LocalPaymentSubject.SUPPLIER && value.financialKind == LocalRetailFinancialKind.SUPPLIER_CASH && value.kind == kind &&
            kind in setOf(LocalRetailPhysicalKind.SUPPLIER_CASH_OUT, LocalRetailPhysicalKind.APPLICATION, LocalRetailPhysicalKind.RESOLUTION) && value.summary.size in 1..32
    private fun supplierSaved(value: LocalRetailSavedOperation): Boolean = value.subject == LocalPaymentSubject.SUPPLIER &&
        value.financialKind == LocalRetailFinancialKind.SUPPLIER_CASH && !(value.isPhysicalStart && value.requiresNativeRetention) && value.summary.size <= 32
    private fun supplierDisplay(value: LocalRetailInstructionDisplay): Boolean = value.subject == LocalPaymentSubject.SUPPLIER &&
        value.kind == LocalRetailPhysicalKind.SUPPLIER_CASH_OUT && value.tender == "CASH" && !value.requiresTerminal && !value.requiresReference &&
        value.amount.currency == Currency.EGP && value.amount.minor > 0L &&
        setOf(value.operationId, value.recordingOperationId, value.resolutionOperationId).size == 3
    private fun supplierProgress(value: LocalRetailPhysicalProgress): Boolean = value.subject == LocalPaymentSubject.SUPPLIER &&
        value.financialKind == LocalRetailFinancialKind.SUPPLIER_CASH && value.state !in setOf(LocalRetailDurableState.COLLECTION_PREPARED,
            LocalRetailDurableState.STOCK_HELD_NO_INSTRUCTION, LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED) &&
        (value.reviewDisplay == null || supplierDisplay(value.reviewDisplay)) && value.reviewValues.size <= 32

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
            cleanup.attempt { update(state.value.copy(preview = null, selected = null, saved = emptyList(), facts = emptyList(), instruction = null,
                progress = null, capture = null, pendingLoaded = false, nextFactAfter = null, problem = problem, observation = LocalSupplierObservationDraft())) }
            cleanup.throwIfFailed()
        } else update(state.value.copy(problem = problem))
    }

    private fun run(kind: LocalSupplierBusy, id: OperationId?, block: suspend (Ticket) -> Unit) {
        if (!current() || !parentAvailable() || state.value.working) { originalOwner?.deny(); originalOwner = null; return }
        update(state.value.copy(busy = kind, problem = null, inputProblem = false, operationId = id ?: state.value.operationId))
        // Parent's one shared leave guard registers synchronously before this ticket is taken.
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
                        val previous = state.value
                        mutableState.value = LocalSupplierCashWorkspace(reference = previous.reference, purchasingQuery = previous.purchasingQuery,
                            operationId = id, recoveryId = id?.value.orEmpty(), problem = LocalServiceProblem.UNAVAILABLE,
                            freshPayoutAllowed = previous.freshPayoutAllowed)
                    }
                    work = null
                    update(state.value.copy(busy = LocalSupplierBusy.NONE))
                }
            }
        }
        work = job
        job.start()
    }
}

private fun supplierReason(value: String): Boolean = value.length in 1..128 && value.all { it.isLetterOrDigit() || it in "._-" }

/** Literal signed decimal evidence, including foreign/unsupported precision. Never a payout default. */
internal fun parseSupplierObservedAmount(currencyIdentifier: String, amount: String): LocalSupplierObservedAmount? {
    if (amount.length !in 1..96) return null
    val normalized = amount.map { character -> when (character) {
        in '٠'..'٩' -> '0' + (character - '٠')
        in '۰'..'۹' -> '0' + (character - '۰')
        '٫' -> '.'
        else -> character
    } }.joinToString("")
    return try { LocalSupplierObservedAmount(currencyIdentifier, normalized) } catch (_: IllegalArgumentException) { null }
}
