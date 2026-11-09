package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId

/** FOOD shares the one-shot lifecycle, but every command/target/result remains its own type. */
internal val foodResolutionActions: Set<LocalAction> = setOf(LocalAction.FOOD_PAYMENT_RESOLUTION,
    LocalAction.FOOD_PAYMENT_RESOLUTION_APPLY, LocalAction.FOOD_SUPERVISOR_REVIEW, LocalAction.FOOD_SUPERVISOR_APPLY)
internal val foodPhysicalActions: Set<LocalAction> = setOf(LocalAction.FOOD_PAYMENT_START, LocalAction.FOOD_PAYMENT_APPLY) + foodResolutionActions
internal val physicalActions: Set<LocalAction> = retailActions + foodPhysicalActions + foodRefundActions + supplierPhysicalActions
internal val physicalStartActions: Set<LocalAction> = retailStartActions +
    setOf(LocalAction.FOOD_PAYMENT_START, LocalAction.FOOD_REFUND_START, LocalAction.SUPPLIER_CASH_START)

/** Closed typed FOOD routes; no caller/UI RecordOriginal, refund or arbitrary body port. */
internal fun foodPhysicalRequestAction(request: MutationRequest): LocalAction? {
    if (request.actionApprovalId != null) return null
    return when (request.command) {
        LocalFoodPaymentProfile.COMMAND -> when (LocalFoodPaymentCodec.decode(request.payload)) {
            is LocalFoodPaymentRequest.StartCash -> LocalAction.FOOD_PAYMENT_START
            is LocalFoodPaymentRequest.ApplyOriginal -> LocalAction.FOOD_PAYMENT_APPLY
            is LocalFoodPaymentRequest.RecordResolution -> LocalAction.FOOD_PAYMENT_RESOLUTION
            is LocalFoodPaymentRequest.ApplyResolution -> LocalAction.FOOD_PAYMENT_RESOLUTION_APPLY
            is LocalFoodPaymentRequest.RecordOriginal -> null
        }
        LocalFoodPaymentSupervisorCodec.COMMAND -> when (LocalFoodPaymentSupervisorCodec.decode(request.payload)) {
            is LocalFoodPaymentSupervisorRequest.Review -> LocalAction.FOOD_SUPERVISOR_REVIEW
            is LocalFoodPaymentSupervisorRequest.Apply -> LocalAction.FOOD_SUPERVISOR_APPLY
        }
        else -> null
    }
}

internal fun physicalIntent(owner: LocalClientIdentity, request: MutationRequest,
    label: LocalText, summary: List<LocalRecordValue>): StoredClientIntentV1 {
    if (request.command == LocalSupplierCashProfile.COMMAND) return supplierPhysicalIntent(owner, request, label, summary)
    if (request.command == LocalFoodRefundProfile.COMMAND) return foodRefundIntent(owner, request, label, summary)
    if (request.command !in setOf(LocalFoodPaymentProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND))
        return retailIntent(owner, request, label, summary)
    val action = foodPhysicalRequestAction(request) ?: invalid()
    val normalized = normalizeLocalRequest(request.command, request.payload)
    require(normalized == request.payload)
    val digest = semanticDigest(owner.scope, owner.actorId, request.command, normalized)
    return StoredClientIntentV1.staged(owner, physicalUiIntent(request.operationId, action, digest), request, label, summary)
}

internal fun requireFoodEntry(entry: StoredClientIntentV1) {
    entry.validate(entry.owner)
    require(entry.localAction in foodPhysicalActions && entry.formVersion == 2 &&
        foodPhysicalRequestAction(entry.request()) == entry.localAction)
    require(entry.inputDigest == inputDigest(physicalUiIntent(entry.operationId, entry.localAction, entry.semanticSha256)))
    when (entry.request().command) {
        LocalFoodPaymentProfile.COMMAND -> when (val body = LocalFoodPaymentCodec.decode(entry.request().payload)) {
            is LocalFoodPaymentRequest.StartCash -> require(entry.operationId !in setOf(body.recordingOperationId, body.resolutionOperationId))
            is LocalFoodPaymentRequest.RecordResolution -> require(entry.operationId == body.target.resolutionOperationId &&
                body.resolverActorId == entry.owner.actorId && body.outcome !is LocalFoodCashOutcome.Unknown)
            is LocalFoodPaymentRequest.ApplyOriginal -> require(entry.operationId != body.recordingOperationId)
            is LocalFoodPaymentRequest.ApplyResolution -> require(entry.operationId != body.resolutionOperationId)
            is LocalFoodPaymentRequest.RecordOriginal -> unavailable()
        }
        LocalFoodPaymentSupervisorCodec.COMMAND -> when (val body = LocalFoodPaymentSupervisorCodec.decode(entry.request().payload)) {
            is LocalFoodPaymentSupervisorRequest.Review -> require(entry.operationId != body.originalRecordingOperationId)
            is LocalFoodPaymentSupervisorRequest.Apply -> require(entry.operationId != body.factOperationId)
        }
        else -> unavailable()
    }
}

internal fun requirePhysicalEntry(entry: StoredClientIntentV1) {
    if (entry.localAction in supplierPhysicalActions) requireSupplierEntry(entry)
    else if (entry.localAction in foodRefundActions) requireFoodRefundEntry(entry)
    else if (entry.localAction in foodPhysicalActions) requireFoodEntry(entry) else requireRetailEntry(entry)
}

internal fun physicalSaved(entry: StoredClientIntentV1): LocalRetailSavedOperation {
    requirePhysicalEntry(entry)
    return LocalRetailSavedOperation(entry.operationId, entry.label.ui(), entry.localPhase != LocalPendingPhase.STAGED,
        entry.localAction in physicalStartActions, entry.recorded != null, entry.summary.map { it.ui() },
        requiresNativeRetention = entry.localAction in setOf(LocalAction.FOOD_PAYMENT_RESOLUTION, LocalAction.FOOD_REFUND_RESOLUTION,
            LocalAction.RETAIL_PAYMENT_RESOLUTION, LocalAction.RETAIL_REFUND_RESOLUTION, LocalAction.SUPPLIER_CASH_RESOLUTION),
        financialKind = when (entry.localAction) {
            in supplierPhysicalActions -> LocalRetailFinancialKind.SUPPLIER_CASH
            in foodRefundActions -> LocalRetailFinancialKind.REFUND
            in foodPhysicalActions -> LocalRetailFinancialKind.PAYMENT
            else -> retailFinancialKind(entry)
        }, subject = when (entry.localAction) {
            in supplierPhysicalActions -> LocalPaymentSubject.SUPPLIER
            in foodPhysicalActions, in foodRefundActions -> LocalPaymentSubject.FOOD
            else -> LocalPaymentSubject.RETAIL
        })
}

internal fun physicalRecordedProgress(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailPhysicalProgress =
    if (entry.localAction in supplierPhysicalActions) supplierRecordedProgress(entry, reply)
    else if (entry.localAction in foodRefundActions) foodRefundRecordedProgress(entry, reply)
    else if (entry.localAction in foodPhysicalActions) foodRecordedProgress(entry, reply) else retailRecordedProgress(entry, reply)

internal fun physicalRecordedDecision(entry: StoredClientIntentV1, result: StoredKernelResultV1): LocalRecordedDecision =
    if (entry.localAction in supplierPhysicalActions) supplierRecordedDecision(entry, result)
    else if (entry.localAction in foodRefundActions) foodRefundRecordedDecision(entry, result)
    else if (entry.localAction in foodPhysicalActions) foodRecordedDecision(entry, result) else retailRecordedDecision(entry, result)

internal fun physicalRecordedUi(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    if (entry.localAction in supplierPhysicalActions) return supplierRecordedUi(entry, reply)
    if (entry.localAction in foodRefundActions) return foodRefundRecordedUi(entry, reply)
    if (entry.localAction !in foodPhysicalActions) return retailRecordedUi(entry, reply)
    val progress = foodRecordedProgress(entry, reply)
    return LocalOperationResult.Recorded(entry.operationId, foodRecordedDecision(entry, StoredKernelResultV1.from(reply)),
        progress.message, progress.collectionId?.let {
            LocalRecord(LocalRecordReference(LocalRecordKind.FOOD_COLLECTION, it), entry.label.ui())
        }, reply.replayed)
}

internal fun foodDisplay(target: LocalFoodPaymentTarget, amount: Money): LocalRetailInstructionDisplay =
    LocalRetailInstructionDisplay(target.intentOperationId, target.recordingOperationId, target.resolutionOperationId,
        LocalRetailPhysicalKind.PAYMENT, target.collectionId, target.sessionId, amount, "CASH", false, false, LocalPaymentSubject.FOOD)

private fun requireFoodStartTarget(entry: StoredClientIntentV1, target: LocalFoodPaymentTarget): LocalFoodPaymentRequest.StartCash {
    requireFoodEntry(entry)
    require(entry.localAction == LocalAction.FOOD_PAYMENT_START)
    val body = LocalFoodPaymentCodec.decode(entry.request().payload) as? LocalFoodPaymentRequest.StartCash ?: unavailable()
    require(target.collectionId == body.collectionId && target.sessionId == body.sessionId && target.attemptId == body.attemptId &&
        target.intentOperationId == entry.operationId && target.recordingOperationId == body.recordingOperationId &&
        target.resolutionOperationId == body.resolutionOperationId)
    return body
}

/** Exact live native projection matched to BOTH the saved request and its recorded Start. */
internal fun requireFoodProjection(entry: StoredClientIntentV1, value: LocalFoodPaymentPhysicalProjection,
    started: LocalFoodPaymentResult.AttemptStarted) {
    val body = requireFoodStartTarget(entry, value.target)
    val request = entry.request()
    val requirement = value.requirement
    require(value.target == started.target && value.amount == started.amount && value.amount == body.amount &&
        sha256(requirement.canonicalBytes.copyBytes()) == started.requirementDigest &&
        requirement.scope == entry.owner.scope && requirement.originalActorId == entry.owner.actorId &&
        requirement.clientDeviceId == entry.owner.deviceId && requirement.intentOperationId == entry.operationId &&
        requirement.intentSemanticDigest == entry.semanticSha256 && requirement.command == request.command &&
        requirement.domainProfile == LocalFoodPaymentProfile.DOMAIN &&
        requirement.masterAllocationId == body.masterAllocationId && requirement.clientAllocationId == body.clientAllocationId)
}

internal fun requireFoodAttemptRecording(entry: StoredClientIntentV1, started: LocalFoodPaymentResult.AttemptStarted,
    read: LocalFoodPaymentReadResult.AttemptRecording) {
    val body = requireFoodStartTarget(entry, read.target)
    require(read.target == started.target && read.requirementDigest == started.requirementDigest &&
        read.amount == started.amount && read.amount == body.amount && read.clientDeviceId == entry.owner.deviceId &&
        read.masterAllocationId == body.masterAllocationId && read.clientAllocationId == body.clientAllocationId)
    requireFoodProjection(entry, LocalFoodPaymentPhysicalProjection.parse(read.requirementBytes()), started)
    read.original?.let { summary ->
        requireFoodSummary(summary)
        require(summary.target == read.target && summary.originalActorId == entry.owner.actorId && summary.amount == read.amount)
    }
}

internal fun requireFoodSummary(summary: LocalFoodPaymentOriginalSummary) {
    require(summary.originalActorId == summary.recordingActorId && summary.amount.currency == Currency.EGP && summary.amount.minor > 0L)
    val applied = summary.applicationState == LocalFoodPaymentApplicationState.APPLIED
    require(if (applied) summary.appliedAction != null && summary.pendingReason == null && summary.lastApplicationOperationId != null
        else summary.appliedAction == null && summary.pendingReason != null)
    summary.lastApplicationOperationId?.let { require(it !in setOf(summary.target.intentOperationId,
        summary.target.recordingOperationId, summary.target.resolutionOperationId)) }
}

internal fun requireFoodOriginal(value: LocalFoodPaymentReadResult.Original) {
    val summary = value.summary
    requireFoodSummary(summary)
    require(sha256(LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.RecordOriginal(summary.target, value.outcome)).copyBytes()) == summary.bodyDigest)
    summary.appliedAction?.let { action ->
        val expected = when (val outcome = value.outcome) {
            is LocalFoodCashOutcome.Unknown -> LocalFoodPaymentAction.UNKNOWN
            is LocalFoodCashOutcome.NotPaid -> LocalFoodPaymentAction.NOT_PAID
            is LocalFoodCashOutcome.CashReceived -> {
                require(outcome.received.currency == summary.amount.currency && outcome.change.currency == summary.amount.currency &&
                    outcome.received.minor > 0L && outcome.change.minor >= 0L && outcome.received - outcome.change == summary.amount)
                LocalFoodPaymentAction.CASH_PAID
            }
            is LocalFoodCashOutcome.ExternalAttested -> unavailable()
        }
        require(action == expected)
    }
}

/** Accounting state may advance between reads; immutable original lineage cannot change. */
internal fun requireSameFoodOriginal(previous: LocalFoodPaymentOriginalSummary, current: LocalFoodPaymentOriginalSummary) {
    require(previous.target == current.target && previous.originalActorId == current.originalActorId &&
        previous.recordingActorId == current.recordingActorId && previous.bodyDigest == current.bodyDigest &&
        previous.recordedAt == current.recordedAt && previous.amount == current.amount)
}

internal fun foodFactProgress(summary: LocalFoodPaymentOriginalSummary, id: OperationId = summary.target.recordingOperationId,
    outcome: LocalFoodCashOutcome? = null): LocalRetailPhysicalProgress {
    requireFoodSummary(summary)
    val applied = summary.applicationState == LocalFoodPaymentApplicationState.APPLIED
    val unknown = outcome is LocalFoodCashOutcome.Unknown || summary.appliedAction == LocalFoodPaymentAction.UNKNOWN
    val state = when {
        !applied -> LocalRetailDurableState.SQL_ORIGINAL_PENDING
        unknown -> LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED
        summary.appliedAction == LocalFoodPaymentAction.NOT_PAID -> LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
        else -> LocalRetailDurableState.FINANCIALLY_APPLIED
    }
    val message = foodProgressMessage(state, summary.pendingReason)
    return LocalRetailPhysicalProgress(id, state, message, summary.target.recordingOperationId, summary.target.resolutionOperationId,
        LocalRetailObservationPhase.ORIGINAL, unknown, summary.target.collectionId, originalApplied = applied,
        originalActorId = summary.originalActorId, recordingActorId = summary.recordingActorId,
        reviewValues = outcome?.let { retailObservationSummary(it.foodUiObservation()) }.orEmpty(),
        originalRecordingOperationId = summary.target.recordingOperationId, subject = LocalPaymentSubject.FOOD)
}

private fun requireFoodRecorded(entry: StoredClientIntentV1, result: LocalFoodPaymentResult, committed: Boolean, code: String) {
    requireFoodEntry(entry)
    val body = LocalFoodPaymentCodec.decode(entry.request().payload)
    when (result) {
        is LocalFoodPaymentResult.AttemptStarted -> {
            val start = requireFoodStartTarget(entry, result.target)
            require(committed && result.amount == start.amount)
        }
        is LocalFoodPaymentResult.Applied -> {
            val selected = body as? LocalFoodPaymentRequest.ApplyOriginal ?: unavailable()
            require(committed && result.phase == LocalFoodPaymentFactPhase.ORIGINAL &&
                result.target.recordingOperationId == selected.recordingOperationId &&
                result.applicationOperationId == entry.operationId && entry.operationId !in setOf(result.target.intentOperationId,
                    result.target.recordingOperationId, result.target.resolutionOperationId))
        }
        is LocalFoodPaymentResult.Pending -> {
            val selected = body as? LocalFoodPaymentRequest.ApplyOriginal ?: unavailable()
            require(!committed && result.phase == LocalFoodPaymentFactPhase.ORIGINAL && code == "food.payment.pending_reconciliation" &&
                result.target.recordingOperationId == selected.recordingOperationId)
        }
        is LocalFoodPaymentResult.AlreadyApplied -> {
            val selected = body as? LocalFoodPaymentRequest.ApplyOriginal ?: unavailable()
            require(!committed && result.phase == LocalFoodPaymentFactPhase.ORIGINAL && code == "food.payment.already_applied" &&
                result.recordingOperationId == selected.recordingOperationId &&
                result.applicationOperationId != entry.operationId && result.applicationOperationId != result.recordingOperationId)
        }
        is LocalFoodPaymentResult.Unavailable -> require(!committed && result.code == code)
        is LocalFoodPaymentResult.RawRetained -> unavailable() // Native intake has no UI command row.
    }
}

internal fun foodRecordedProgress(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailPhysicalProgress {
    require(reply.operationId == entry.operationId && reply.originalActorId == entry.owner.actorId)
    if (entry.localAction in foodResolutionActions) return foodResolutionRecordedProgress(entry, reply)
    val value = LocalFoodPaymentCodec.result(reply.result)
    requireFoodRecorded(entry, value, reply.committed, reply.code)
    val target = when (value) {
        is LocalFoodPaymentResult.AttemptStarted -> value.target
        is LocalFoodPaymentResult.Applied -> value.target
        is LocalFoodPaymentResult.Pending -> value.target
        else -> null
    }
    val state = when (value) {
        is LocalFoodPaymentResult.AttemptStarted -> LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION
        is LocalFoodPaymentResult.Applied -> when (value.action) {
            LocalFoodPaymentAction.CASH_PAID -> LocalRetailDurableState.FINANCIALLY_APPLIED
            LocalFoodPaymentAction.NOT_PAID -> LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
            LocalFoodPaymentAction.UNKNOWN -> LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED
        }
        is LocalFoodPaymentResult.Pending -> LocalRetailDurableState.SQL_ORIGINAL_PENDING
        is LocalFoodPaymentResult.AlreadyApplied -> LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED
        is LocalFoodPaymentResult.Unavailable -> LocalRetailDurableState.REJECTED
        is LocalFoodPaymentResult.RawRetained -> unavailable()
    }
    val recording = target?.recordingOperationId ?: (value as? LocalFoodPaymentResult.AlreadyApplied)?.recordingOperationId
    val message = if (value is LocalFoodPaymentResult.Unavailable) text(
        "Command durably refused: ${value.code}. Preserve earlier physical-action IDs and evidence.",
        "رُفض الأمر بشكل دائم: ${value.code}. احتفظ بمعرفات الحركة الفعلية السابقة وأدلتها.")
    else foodProgressMessage(state, (value as? LocalFoodPaymentResult.Pending)?.reason)
    return LocalRetailPhysicalProgress(entry.operationId, state, message, recording, target?.resolutionOperationId,
        phase = LocalRetailObservationPhase.ORIGINAL.takeIf { recording != null },
        originalUnknown = value is LocalFoodPaymentResult.Applied && value.action == LocalFoodPaymentAction.UNKNOWN,
        collectionId = target?.collectionId, originalRecordingOperationId = recording, subject = LocalPaymentSubject.FOOD)
}

private fun foodRecordedDecision(entry: StoredClientIntentV1, result: StoredKernelResultV1): LocalRecordedDecision {
    require(result.operationId == entry.operationId && result.originalActorId == entry.owner.actorId)
    if (entry.localAction in foodResolutionActions) return foodResolutionRecordedDecision(entry, result)
    val value = LocalFoodPaymentCodec.result(decodeHex(result.payloadHex, 65_536).useWiped { StoredBytes(it) })
    requireFoodRecorded(entry, value, result.committed, result.code)
    return when (value) {
        is LocalFoodPaymentResult.Applied -> if (value.action == LocalFoodPaymentAction.CASH_PAID)
            LocalRecordedDecision.APPLIED else LocalRecordedDecision.RECORDED_ONLY
        is LocalFoodPaymentResult.Unavailable -> LocalRecordedDecision.REJECTED
        else -> LocalRecordedDecision.RECORDED_ONLY
    }
}

private fun foodProgressMessage(state: LocalRetailDurableState, reason: LocalFoodPaymentPendingReason? = null): LocalText = when (state) {
    LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION -> text(
        "FOOD CASH Start is durably recorded. This progress display is not an instruction. Recover its original observation; do not start again.",
        "سُجل بدء تحصيل نقدية الطعام بشكل دائم. عرض التقدم ليس تعليمات حركة. استرد الإفادة الأصلية ولا تكرر البدء.")
    LocalRetailDurableState.SQL_ORIGINAL_PENDING -> text(
        "Immutable FOOD observation retained; accounting/reconciliation remains pending: ${reason?.name ?: "PENDING"}. Do not repeat the physical action.",
        "حُفظت إفادة الطعام الثابتة؛ المحاسبة أو التسوية معلقة: ${reason?.name ?: "PENDING"}. لا تكرر الحركة الفعلية.")
    LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED -> text(
        "UNKNOWN FOOD observation applied to the ledger. The physical outcome remains unresolved; preserve evidence and do not collect again.",
        "طُبقت إفادة الطعام مجهولة النتيجة في الدفتر. ما زالت الحركة الفعلية غير محسومة؛ احتفظ بالدليل ولا تكرر التحصيل.")
    LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY -> text(
        "Recorded FOOD NOT PAID observation applied. This application posted no money movement.",
        "طُبقت إفادة عدم دفع الطعام المسجلة. لم تسجل هذه المعالجة حركة مالية.")
    LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED -> text(
        "This FOOD original was already applied; no effects were repeated. Read the original for its actual outcome, which may remain UNKNOWN.",
        "طُبقت إفادة الطعام الأصلية بالفعل؛ لم تتكرر الآثار. اقرأ الأصل لمعرفة النتيجة الفعلية فقد تظل مجهولة.")
    else -> text("FOOD CASH attestation financially applied once. Sale finalization is a separate authorized operation.",
        "طُبقت إفادة نقدية الطعام ماليًا مرة واحدة. تثبيت البيع عملية مصرح بها منفصلة.")
}

private fun LocalCashEvidenceV1.foodUiEvidence(): LocalRetailEvidence = LocalRetailEvidence(evidenceId, occurredAt)
internal fun LocalFoodCashOutcome.foodUiObservation(): LocalRetailObservation = when (this) {
    is LocalFoodCashOutcome.Unknown -> LocalRetailObservation.Unknown(evidence.foodUiEvidence())
    is LocalFoodCashOutcome.NotPaid -> LocalRetailObservation.NotPaid(evidence.foodUiEvidence(), reason)
    is LocalFoodCashOutcome.CashReceived -> LocalRetailObservation.CashReceived(evidence.foodUiEvidence(), received, change)
    is LocalFoodCashOutcome.ExternalAttested -> LocalRetailObservation.ExternalAttested(evidence.foodUiEvidence(), terminal, reference)
}
