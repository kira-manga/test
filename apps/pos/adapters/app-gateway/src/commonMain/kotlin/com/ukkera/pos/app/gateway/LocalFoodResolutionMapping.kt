package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Phase/subject routing only; no retail target, custody handle or native capacity is inferred. */
internal fun LocalFoodPaymentFactPhase.foodUiPhase(): LocalRetailObservationPhase = when (this) {
    LocalFoodPaymentFactPhase.ORIGINAL -> LocalRetailObservationPhase.ORIGINAL
    LocalFoodPaymentFactPhase.RESOLUTION -> LocalRetailObservationPhase.RESOLUTION
}

internal fun LocalRetailObservationPhase.foodFactPhase(): LocalFoodPaymentFactPhase = when (this) {
    LocalRetailObservationPhase.ORIGINAL -> LocalFoodPaymentFactPhase.ORIGINAL
    LocalRetailObservationPhase.RESOLUTION -> LocalFoodPaymentFactPhase.RESOLUTION
}

internal fun foodFactOperation(target: LocalFoodPaymentTarget, phase: LocalFoodPaymentFactPhase): OperationId =
    if (phase == LocalFoodPaymentFactPhase.ORIGINAL) target.recordingOperationId else target.resolutionOperationId

internal fun requireFoodResolutionSummary(value: LocalFoodPaymentResolutionSummary) {
    require(value.amount.currency == Currency.EGP && value.amount.minor > 0L)
    val applied = value.applicationState == LocalFoodPaymentApplicationState.APPLIED
    require(if (applied) value.appliedAction != null && value.appliedAction != LocalFoodPaymentAction.UNKNOWN &&
        value.pendingReason == null && value.lastApplicationOperationId != null
        else value.appliedAction == null && value.pendingReason != null)
    value.lastApplicationOperationId?.let { require(it !in setOf(value.target.intentOperationId,
        value.target.recordingOperationId, value.target.resolutionOperationId)) }
}

/** Accounting state may advance between reads; immutable linked lineage cannot change. */
internal fun requireSameFoodResolution(previous: LocalFoodPaymentResolutionSummary, current: LocalFoodPaymentResolutionSummary) {
    require(previous.target == current.target && previous.originalActorId == current.originalActorId &&
        previous.recordingActorId == current.recordingActorId && previous.bodyDigest == current.bodyDigest &&
        previous.originalBodyDigest == current.originalBodyDigest && previous.recordedAt == current.recordedAt &&
        previous.amount == current.amount)
}

/** Immutable linked bytes and actual recorder must agree even when value/tender remains held. */
internal fun requireFoodResolution(value: LocalFoodPaymentReadResult.Resolution,
    original: LocalFoodPaymentOriginalSummary? = null) {
    val summary = value.summary
    requireFoodResolutionSummary(summary)
    require(value.outcome !is LocalFoodCashOutcome.Unknown)
    require(sha256(LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.RecordResolution(summary.target,
        summary.originalBodyDigest, summary.recordingActorId, value.outcome)).copyBytes()) == summary.bodyDigest)
    original?.let {
        requireFoodSummary(it)
        require(summary.target == it.target && summary.originalActorId == it.originalActorId &&
            summary.originalBodyDigest == it.bodyDigest && summary.amount == it.amount)
        if (summary.applicationState == LocalFoodPaymentApplicationState.APPLIED)
            require(it.applicationState == LocalFoodPaymentApplicationState.APPLIED && it.appliedAction == LocalFoodPaymentAction.UNKNOWN)
    }
    summary.appliedAction?.let { action ->
        val expected = when (val outcome = value.outcome) {
            is LocalFoodCashOutcome.NotPaid -> LocalFoodPaymentAction.NOT_PAID
            is LocalFoodCashOutcome.CashReceived -> {
                require(outcome.received.currency == summary.amount.currency && outcome.change.currency == summary.amount.currency &&
                    outcome.received.minor > 0L && outcome.change.minor >= 0L && outcome.received - outcome.change == summary.amount)
                LocalFoodPaymentAction.CASH_PAID
            }
            is LocalFoodCashOutcome.Unknown, is LocalFoodCashOutcome.ExternalAttested -> unavailable()
        }
        require(action == expected)
    }
}

internal fun foodResolutionFactProgress(value: LocalFoodPaymentReadResult.Resolution,
    id: OperationId = value.summary.target.resolutionOperationId): LocalRetailPhysicalProgress {
    requireFoodResolution(value)
    val summary = value.summary
    val applied = summary.applicationState == LocalFoodPaymentApplicationState.APPLIED
    val state = if (!applied) LocalRetailDurableState.SQL_RESOLUTION_PENDING
        else if (summary.appliedAction == LocalFoodPaymentAction.NOT_PAID) LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
        else LocalRetailDurableState.FINANCIALLY_APPLIED
    return LocalRetailPhysicalProgress(id, state, foodResolutionMessage(state, summary.pendingReason),
        summary.target.resolutionOperationId, summary.target.resolutionOperationId, LocalRetailObservationPhase.RESOLUTION,
        collectionId = summary.target.collectionId, resolutionRecorded = true,
        originalActorId = summary.originalActorId, recordingActorId = summary.recordingActorId,
        reviewValues = retailObservationSummary(value.outcome.foodUiObservation()),
        originalRecordingOperationId = summary.target.recordingOperationId, subject = LocalPaymentSubject.FOOD)
}

/** A review is accepted only as the exact durably audited command result, never a UI projection. */
internal fun foodReview(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalFoodPaymentSupervisorReview {
    require(reply.operationId == entry.operationId &&
        reply.originalActorId == entry.owner.actorId && !reply.committed && reply.code == "food.payment.supervisor.reviewed")
    return foodReviewBody(entry, reply.result)
}

/** Validate the exact selector and canonical original/resolution bytes on every decision path. */
private fun foodReviewBody(entry: StoredClientIntentV1, payload: StoredBytes): LocalFoodPaymentSupervisorReview {
    requireFoodEntry(entry)
    require(entry.localAction == LocalAction.FOOD_SUPERVISOR_REVIEW)
    val requested = LocalFoodPaymentSupervisorCodec.decode(entry.request().payload) as? LocalFoodPaymentSupervisorRequest.Review ?: unavailable()
    val result = LocalFoodPaymentSupervisorCodec.decodeReview(payload)
    requireFoodOriginal(LocalFoodPaymentReadResult.Original(result.original, result.originalOutcome))
    require(result.original.target.recordingOperationId == requested.originalRecordingOperationId &&
        (result.resolution == null) == (result.resolutionOutcome == null))
    result.resolution?.let {
        require(result.originalOutcome is LocalFoodCashOutcome.Unknown)
        requireFoodResolution(LocalFoodPaymentReadResult.Resolution(it, checkNotNull(result.resolutionOutcome)), result.original)
    }
    return result
}

private fun foodReviewProgress(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailPhysicalProgress {
    val reviewed = foodReview(entry, reply)
    val original = reviewed.original
    return LocalRetailPhysicalProgress(entry.operationId, LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED,
        text("FOOD observation disclosure was authorized and durably audited. This historical review is not an instruction, resolution retention or accounting application.",
            "صُرّح بكشف إفادة الطعام وسُجل تدقيقها بشكل دائم. هذه مراجعة تاريخية وليست تعليمات حركة أو حفظ تسوية أو تطبيقًا محاسبيًا."),
        original.target.recordingOperationId, original.target.resolutionOperationId, LocalRetailObservationPhase.ORIGINAL,
        reviewed.originalOutcome is LocalFoodCashOutcome.Unknown, original.target.collectionId,
        reviewDisplay = foodDisplay(original.target, original.amount),
        originalApplied = original.applicationState == LocalFoodPaymentApplicationState.APPLIED,
        resolutionRecorded = reviewed.resolution != null, originalActorId = original.originalActorId,
        recordingActorId = reviewed.resolution?.recordingActorId ?: original.recordingActorId,
        reviewValues = foodReviewValues(reviewed),
        originalRecordingOperationId = original.target.recordingOperationId, subject = LocalPaymentSubject.FOOD)
}

private fun foodReviewValues(value: LocalFoodPaymentSupervisorReview): List<LocalRecordValue> = buildList {
    fun append(phase: LocalFoodPaymentFactPhase, state: LocalFoodPaymentApplicationState,
        pending: LocalFoodPaymentPendingReason?, recorder: DomainId, outcome: LocalFoodCashOutcome) {
        add(recordValue(text("Historical phase", "المرحلة التاريخية"), literal(phase.name)))
        add(recordValue(text("Fact SQL application state", "حالة تطبيق الإفادة في قاعدة البيانات"), literal(state.name)))
        pending?.let { add(recordValue(text("Pending reason", "سبب التعليق"), literal(it.name))) }
        add(recordValue(text("Immutable fact recorder", "مسجل الإفادة الثابت"), literal(recorder.value)))
        addAll(retailObservationSummary(outcome.foodUiObservation()))
    }
    append(LocalFoodPaymentFactPhase.ORIGINAL, value.original.applicationState, value.original.pendingReason,
        value.original.recordingActorId, value.originalOutcome)
    value.resolution?.let {
        append(LocalFoodPaymentFactPhase.RESOLUTION, it.applicationState, it.pendingReason,
            it.recordingActorId, checkNotNull(value.resolutionOutcome))
    }
}

private data class FoodAccountingSelector(val operationId: OperationId, val phase: LocalFoodPaymentFactPhase)

private fun foodAccountingSelector(entry: StoredClientIntentV1): FoodAccountingSelector? = when (entry.localAction) {
    LocalAction.FOOD_PAYMENT_RESOLUTION_APPLY -> (LocalFoodPaymentCodec.decode(entry.request().payload)
        as? LocalFoodPaymentRequest.ApplyResolution)?.let { FoodAccountingSelector(it.resolutionOperationId, LocalFoodPaymentFactPhase.RESOLUTION) }
    LocalAction.FOOD_SUPERVISOR_APPLY -> (LocalFoodPaymentSupervisorCodec.decode(entry.request().payload)
        as? LocalFoodPaymentSupervisorRequest.Apply)?.let { FoodAccountingSelector(it.factOperationId, it.phase) }
    else -> null
}

private fun requireFoodResolutionRecorded(entry: StoredClientIntentV1, result: LocalFoodPaymentResult,
    committed: Boolean, code: String) {
    requireFoodEntry(entry)
    require(entry.localAction in foodResolutionActions)
    when (result) {
        is LocalFoodPaymentResult.RawRetained -> {
            val request = LocalFoodPaymentCodec.decode(entry.request().payload) as? LocalFoodPaymentRequest.RecordResolution ?: unavailable()
            require(entry.localAction == LocalAction.FOOD_PAYMENT_RESOLUTION && committed &&
                result.phase == LocalFoodPaymentFactPhase.RESOLUTION && result.target == request.target &&
                entry.operationId == result.target.resolutionOperationId)
        }
        is LocalFoodPaymentResult.Applied -> {
            val selected = foodAccountingSelector(entry) ?: unavailable()
            require(committed && result.phase == selected.phase && foodFactOperation(result.target, result.phase) == selected.operationId &&
                result.applicationOperationId == entry.operationId && entry.operationId !in setOf(result.target.intentOperationId,
                    result.target.recordingOperationId, result.target.resolutionOperationId) &&
                (result.phase != LocalFoodPaymentFactPhase.RESOLUTION || result.action != LocalFoodPaymentAction.UNKNOWN))
        }
        is LocalFoodPaymentResult.Pending -> {
            val selected = foodAccountingSelector(entry) ?: unavailable()
            require(!committed && code == "food.payment.pending_reconciliation" && result.phase == selected.phase &&
                foodFactOperation(result.target, result.phase) == selected.operationId &&
                entry.operationId !in setOf(result.target.intentOperationId, result.target.recordingOperationId,
                    result.target.resolutionOperationId))
        }
        is LocalFoodPaymentResult.AlreadyApplied -> {
            val selected = foodAccountingSelector(entry) ?: unavailable()
            require(!committed && code == "food.payment.already_applied" && result.phase == selected.phase &&
                result.recordingOperationId == selected.operationId && result.applicationOperationId != entry.operationId &&
                result.applicationOperationId != selected.operationId)
        }
        is LocalFoodPaymentResult.Unavailable -> require(!committed && result.code == code)
        is LocalFoodPaymentResult.AttemptStarted -> unavailable() // No resolution path can start a physical act.
    }
}

internal fun foodResolutionRecordedProgress(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailPhysicalProgress {
    require(reply.operationId == entry.operationId && reply.originalActorId == entry.owner.actorId)
    if (entry.localAction == LocalAction.FOOD_SUPERVISOR_REVIEW && reply.code == "food.payment.supervisor.reviewed")
        return foodReviewProgress(entry, reply)
    val value = LocalFoodPaymentCodec.result(reply.result)
    requireFoodResolutionRecorded(entry, value, reply.committed, reply.code)
    val target = when (value) {
        is LocalFoodPaymentResult.RawRetained -> value.target
        is LocalFoodPaymentResult.Applied -> value.target
        is LocalFoodPaymentResult.Pending -> value.target
        else -> null
    }
    val phase = when (value) {
        is LocalFoodPaymentResult.RawRetained -> value.phase
        is LocalFoodPaymentResult.Applied -> value.phase
        is LocalFoodPaymentResult.Pending -> value.phase
        is LocalFoodPaymentResult.AlreadyApplied -> value.phase
        else -> null
    }
    val factId = if (target != null && phase != null) foodFactOperation(target, phase)
        else (value as? LocalFoodPaymentResult.AlreadyApplied)?.recordingOperationId
    val state = when (value) {
        is LocalFoodPaymentResult.RawRetained -> LocalRetailDurableState.SQL_RESOLUTION_PENDING
        is LocalFoodPaymentResult.Applied -> when (value.action) {
            LocalFoodPaymentAction.CASH_PAID -> LocalRetailDurableState.FINANCIALLY_APPLIED
            LocalFoodPaymentAction.NOT_PAID -> LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
            LocalFoodPaymentAction.UNKNOWN -> LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED
        }
        is LocalFoodPaymentResult.Pending -> if (value.phase == LocalFoodPaymentFactPhase.RESOLUTION)
            LocalRetailDurableState.SQL_RESOLUTION_PENDING else LocalRetailDurableState.SQL_ORIGINAL_PENDING
        is LocalFoodPaymentResult.AlreadyApplied -> LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED
        is LocalFoodPaymentResult.Unavailable -> LocalRetailDurableState.REJECTED
        is LocalFoodPaymentResult.AttemptStarted -> unavailable()
    }
    val message = if (value is LocalFoodPaymentResult.Unavailable) text(
        "FOOD command durably refused: ${value.code}. Preserve the original and fixed resolution IDs.",
        "رُفض أمر الطعام بشكل دائم: ${value.code}. احتفظ بمعرفي الأصل والتسوية الثابتة.")
        else foodResolutionMessage(state, when (value) {
            is LocalFoodPaymentResult.RawRetained -> value.reason
            is LocalFoodPaymentResult.Pending -> value.reason
            else -> null
        })
    return LocalRetailPhysicalProgress(entry.operationId, state, message, factId, target?.resolutionOperationId,
        phase?.foodUiPhase(), originalUnknown = value is LocalFoodPaymentResult.Applied && value.action == LocalFoodPaymentAction.UNKNOWN,
        collectionId = target?.collectionId, resolutionRecorded = phase == LocalFoodPaymentFactPhase.RESOLUTION,
        originalRecordingOperationId = target?.recordingOperationId, subject = LocalPaymentSubject.FOOD)
}

internal fun foodResolutionRecordedDecision(entry: StoredClientIntentV1, result: StoredKernelResultV1): LocalRecordedDecision {
    require(result.operationId == entry.operationId && result.originalActorId == entry.owner.actorId)
    val payload = decodeHex(result.payloadHex, 65_536).useWiped { StoredBytes(it) }
    if (entry.localAction == LocalAction.FOOD_SUPERVISOR_REVIEW && result.code == "food.payment.supervisor.reviewed") {
        require(!result.committed)
        foodReviewBody(entry, payload)
        return LocalRecordedDecision.RECORDED_ONLY
    }
    val value = LocalFoodPaymentCodec.result(payload)
    requireFoodResolutionRecorded(entry, value, result.committed, result.code)
    return when (value) {
        is LocalFoodPaymentResult.Applied -> if (value.action == LocalFoodPaymentAction.CASH_PAID)
            LocalRecordedDecision.APPLIED else LocalRecordedDecision.RECORDED_ONLY
        is LocalFoodPaymentResult.Unavailable -> LocalRecordedDecision.REJECTED
        else -> LocalRecordedDecision.RECORDED_ONLY
    }
}

private fun foodResolutionMessage(state: LocalRetailDurableState, reason: LocalFoodPaymentPendingReason? = null): LocalText = when (state) {
    LocalRetailDurableState.SQL_RESOLUTION_PENDING -> text(
        "Linked FOOD resolution is retained; accounting remains pending: ${reason?.name ?: "AWAITING_APPLICATION"}. Original UNKNOWN and linked-resolution SQL applications remain separate. Cross-recorder accounting needs fresh audited review. Do not collect again.",
        "حُفظت تسوية الطعام المرتبطة؛ المحاسبة معلقة: ${reason?.name ?: "AWAITING_APPLICATION"}. يظل تطبيق الأصل المجهول وتطبيق التسوية منفصلين في قاعدة البيانات. تحتاج محاسبة إفادة موظف آخر مراجعة مدققة جديدة. لا تكرر التحصيل.")
    LocalRetailDurableState.SQL_ORIGINAL_PENDING -> text("Original FOOD application remains pending: ${reason?.name ?: "PENDING"}. An original UNKNOWN must be applied separately before its linked resolution. No new physical action is authorized.",
        "تطبيق أصل الطعام معلق: ${reason?.name ?: "PENDING"}. يجب تطبيق الأصل المجهول منفصلًا قبل تسويته المرتبطة. لا تُصرّح حركة فعلية جديدة.")
    LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED -> text("Original FOOD UNKNOWN was applied and stays unchanged. Reconcile the old outcome through its separately retained linked known resolution; no new physical action is authorized.",
        "طُبقت النتيجة الأصلية المجهولة للطعام وتظل دون تغيير. سوِّ النتيجة السابقة عبر تسويتها المرتبطة المعلومة المحفوظة منفصلًا؛ ولا تُصرّح حركة فعلية جديدة.")
    LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY -> text("Recorded FOOD NOT PAID was applied once. No money or stock movement was introduced.",
        "طُبقت إفادة عدم دفع الطعام مرة واحدة. لم تُنشأ حركة مالية أو مخزنية.")
    LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED -> text("This exact FOOD fact was already applied. No effects were repeated; read fresh audited progress for the original and resolution.",
        "طُبقت إفادة الطعام الدقيقة بالفعل. لم تتكرر الآثار؛ اقرأ تقدمًا مدققًا جديدًا للأصل والتسوية.")
    else -> text("The existing FOOD cash fact was financially applied once. No new collection or stock action occurred; sale finalization remains separate.",
        "طُبقت إفادة نقدية الطعام الموجودة ماليًا مرة واحدة. لم يحدث تحصيل أو تصرف مخزني جديد؛ وتثبيت البيع منفصل.")
}
