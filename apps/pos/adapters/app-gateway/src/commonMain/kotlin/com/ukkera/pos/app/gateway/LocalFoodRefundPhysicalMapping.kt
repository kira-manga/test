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

/** Distinct FOOD accepted-liability payout. These are never payment or retail-return routes. */
internal val foodRefundActions: Set<LocalAction> = setOf(LocalAction.FOOD_REFUND_START,
    LocalAction.FOOD_REFUND_APPLY, LocalAction.FOOD_REFUND_RESOLUTION, LocalAction.FOOD_REFUND_RESOLUTION_APPLY)

internal fun foodRefundRequestAction(request: MutationRequest): LocalAction? {
    if (request.command != LocalFoodRefundProfile.COMMAND || request.actionApprovalId != null) return null
    return when (LocalFoodRefundCodec.decode(request.payload)) {
        is LocalFoodRefundRequest.StartCash -> LocalAction.FOOD_REFUND_START
        is LocalFoodRefundRequest.ApplyOriginal -> LocalAction.FOOD_REFUND_APPLY
        is LocalFoodRefundRequest.RecordResolution -> LocalAction.FOOD_REFUND_RESOLUTION
        is LocalFoodRefundRequest.ApplyResolution -> LocalAction.FOOD_REFUND_RESOLUTION_APPLY
        is LocalFoodRefundRequest.RecordOriginal -> null // Original capture is native-only.
    }
}

internal fun foodRefundIntent(owner: LocalClientIdentity, request: MutationRequest,
    label: LocalText, summary: List<LocalRecordValue>): StoredClientIntentV1 {
    val action = foodRefundRequestAction(request) ?: invalid()
    require(normalizeLocalRequest(request.command, request.payload) == request.payload)
    val digest = semanticDigest(owner.scope, owner.actorId, request.command, request.payload)
    return StoredClientIntentV1.staged(owner, physicalUiIntent(request.operationId, action, digest), request, label, summary)
}

internal fun requireFoodRefundEntry(entry: StoredClientIntentV1) {
    entry.validate(entry.owner)
    require(entry.localAction in foodRefundActions && entry.formVersion == 2 &&
        foodRefundRequestAction(entry.request()) == entry.localAction &&
        entry.inputDigest == inputDigest(physicalUiIntent(entry.operationId, entry.localAction, entry.semanticSha256)))
    when (val body = LocalFoodRefundCodec.decode(entry.request().payload)) {
        is LocalFoodRefundRequest.StartCash -> require(entry.operationId !in setOf(body.recordingOperationId, body.resolutionOperationId))
        is LocalFoodRefundRequest.RecordResolution -> require(entry.operationId == body.target.resolutionOperationId &&
            body.resolverActorId == entry.owner.actorId && body.outcome !is LocalFoodRefundOutcome.Unknown)
        is LocalFoodRefundRequest.ApplyOriginal -> require(entry.operationId != body.recordingOperationId)
        is LocalFoodRefundRequest.ApplyResolution -> require(entry.operationId != body.resolutionOperationId)
        is LocalFoodRefundRequest.RecordOriginal -> unavailable()
    }
}

internal fun foodRefundDisplay(target: LocalFoodRefundTarget, amount: Money): LocalRetailInstructionDisplay =
    LocalRetailInstructionDisplay(target.intentOperationId, target.recordingOperationId, target.resolutionOperationId,
        LocalRetailPhysicalKind.REFUND, target.refundId, target.sessionId, amount, "CASH", false, false, LocalPaymentSubject.FOOD)

private fun requireFoodRefundStartTarget(entry: StoredClientIntentV1,
    target: LocalFoodRefundTarget): LocalFoodRefundRequest.StartCash {
    requireFoodRefundEntry(entry)
    require(entry.localAction == LocalAction.FOOD_REFUND_START)
    val body = LocalFoodRefundCodec.decode(entry.request().payload) as? LocalFoodRefundRequest.StartCash ?: unavailable()
    require(target.refundId == body.refundId && target.originalPaymentId == body.originalPaymentId &&
        target.sessionId == body.sessionId && target.attemptId == body.attemptId && target.intentOperationId == entry.operationId &&
        target.recordingOperationId == body.recordingOperationId && target.resolutionOperationId == body.resolutionOperationId)
    return body
}

/** Exact live native projection matched to both the immutable saved request and its recorded Start. */
internal fun requireFoodRefundProjection(entry: StoredClientIntentV1, value: LocalFoodRefundPhysicalProjection,
    started: LocalFoodRefundResult.AttemptStarted) {
    val body = requireFoodRefundStartTarget(entry, value.target)
    val requirement = value.requirement
    require(value.target == started.target && value.amount == started.amount && value.amount == body.amount &&
        sha256(requirement.canonicalBytes.copyBytes()) == started.requirementDigest &&
        requirement.scope == entry.owner.scope && requirement.originalActorId == entry.owner.actorId &&
        requirement.clientDeviceId == entry.owner.deviceId && requirement.intentOperationId == entry.operationId &&
        requirement.intentSemanticDigest == entry.semanticSha256 && requirement.command == LocalFoodRefundProfile.COMMAND &&
        requirement.domainProfile == LocalFoodRefundProfile.DOMAIN &&
        requirement.masterAllocationId == body.masterAllocationId && requirement.clientAllocationId == body.clientAllocationId)
}

internal fun requireFoodRefundAttemptRecording(entry: StoredClientIntentV1, started: LocalFoodRefundResult.AttemptStarted,
    read: LocalFoodRefundReadResult.AttemptRecording) {
    val body = requireFoodRefundStartTarget(entry, read.target)
    require(read.target == started.target && read.requirementDigest == started.requirementDigest &&
        read.amount == started.amount && read.amount == body.amount && read.clientDeviceId == entry.owner.deviceId &&
        read.masterAllocationId == body.masterAllocationId && read.clientAllocationId == body.clientAllocationId)
    requireFoodRefundProjection(entry, LocalFoodRefundPhysicalProjection.parse(read.requirementBytes()), started)
    read.original?.let {
        requireFoodRefundSummary(it)
        require(it.target == read.target && it.originalActorId == entry.owner.actorId && it.amount == read.amount)
    }
}

internal fun requireFoodRefundSummary(value: LocalFoodRefundOriginalSummary) {
    require(value.originalActorId == value.recordingActorId && value.amount.currency == Currency.EGP && value.amount.minor > 0L)
    val applied = value.applicationState == LocalFoodRefundApplicationState.APPLIED
    require(if (applied) value.appliedAction != null && value.pendingReason == null && value.lastApplicationOperationId != null
        else value.appliedAction == null && value.pendingReason != null)
    value.lastApplicationOperationId?.let { require(it !in setOf(value.target.intentOperationId,
        value.target.recordingOperationId, value.target.resolutionOperationId)) }
}

internal fun requireFoodRefundOriginal(value: LocalFoodRefundReadResult.Original) {
    val summary = value.summary
    requireFoodRefundSummary(summary)
    require(sha256(LocalFoodRefundCodec.encode(LocalFoodRefundRequest.RecordOriginal(summary.target,
        value.outcome)).copyBytes()) == summary.bodyDigest)
    summary.appliedAction?.let { require(it == foodRefundAppliedAction(value.outcome, summary.amount)) }
}

private fun foodRefundAppliedAction(outcome: LocalFoodRefundOutcome, expectedAmount: Money): LocalFoodRefundAction = when (outcome) {
    is LocalFoodRefundOutcome.Unknown -> LocalFoodRefundAction.UNKNOWN
    is LocalFoodRefundOutcome.NotPaid -> LocalFoodRefundAction.NOT_PAID
    is LocalFoodRefundOutcome.CashPaid -> {
        require(outcome.amount == expectedAmount && outcome.amount.minor > 0L)
        LocalFoodRefundAction.CASH_PAID
    }
    is LocalFoodRefundOutcome.ExternalAttested -> unavailable()
}

/** Accounting can advance between authenticated reads; the immutable original cannot change. */
internal fun requireSameFoodRefundOriginal(previous: LocalFoodRefundOriginalSummary, current: LocalFoodRefundOriginalSummary) {
    require(previous.target == current.target && previous.originalActorId == current.originalActorId &&
        previous.recordingActorId == current.recordingActorId && previous.bodyDigest == current.bodyDigest &&
        previous.recordedAt == current.recordedAt && previous.amount == current.amount)
}

internal fun requireFoodRefundResolutionSummary(value: LocalFoodRefundResolutionSummary) {
    require(value.originalActorId == value.recordingActorId && value.amount.currency == Currency.EGP && value.amount.minor > 0L)
    val applied = value.applicationState == LocalFoodRefundApplicationState.APPLIED
    require(if (applied) value.appliedAction != null && value.appliedAction != LocalFoodRefundAction.UNKNOWN &&
        value.pendingReason == null && value.lastApplicationOperationId != null
        else value.appliedAction == null && value.pendingReason != null)
    value.lastApplicationOperationId?.let { require(it !in setOf(value.target.intentOperationId,
        value.target.recordingOperationId, value.target.resolutionOperationId)) }
}

internal fun requireSameFoodRefundResolution(previous: LocalFoodRefundResolutionSummary, current: LocalFoodRefundResolutionSummary) {
    require(previous.target == current.target && previous.originalActorId == current.originalActorId &&
        previous.recordingActorId == current.recordingActorId && previous.bodyDigest == current.bodyDigest &&
        previous.originalBodyDigest == current.originalBodyDigest && previous.recordedAt == current.recordedAt && previous.amount == current.amount)
}

internal fun requireFoodRefundResolution(value: LocalFoodRefundReadResult.Resolution,
    original: LocalFoodRefundOriginalSummary? = null) {
    val summary = value.summary
    requireFoodRefundResolutionSummary(summary)
    require(value.outcome !is LocalFoodRefundOutcome.Unknown &&
        sha256(LocalFoodRefundCodec.encode(LocalFoodRefundRequest.RecordResolution(summary.target, summary.originalBodyDigest,
            summary.recordingActorId, value.outcome)).copyBytes()) == summary.bodyDigest)
    original?.let {
        requireFoodRefundSummary(it)
        require(summary.target == it.target && summary.originalActorId == it.originalActorId &&
            summary.originalBodyDigest == it.bodyDigest && summary.amount == it.amount)
        if (summary.applicationState == LocalFoodRefundApplicationState.APPLIED)
            require(it.applicationState == LocalFoodRefundApplicationState.APPLIED && it.appliedAction == LocalFoodRefundAction.UNKNOWN)
    }
    summary.appliedAction?.let { require(it == foodRefundAppliedAction(value.outcome, summary.amount)) }
}

internal fun foodRefundFactProgress(summary: LocalFoodRefundOriginalSummary,
    id: OperationId = summary.target.recordingOperationId, outcome: LocalFoodRefundOutcome? = null): LocalRetailPhysicalProgress {
    requireFoodRefundSummary(summary)
    val applied = summary.applicationState == LocalFoodRefundApplicationState.APPLIED
    val unknown = outcome is LocalFoodRefundOutcome.Unknown || summary.appliedAction == LocalFoodRefundAction.UNKNOWN
    val state = when {
        !applied -> LocalRetailDurableState.SQL_ORIGINAL_PENDING
        unknown -> LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED
        summary.appliedAction == LocalFoodRefundAction.NOT_PAID -> LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
        else -> LocalRetailDurableState.FINANCIALLY_APPLIED
    }
    return LocalRetailPhysicalProgress(id, state, foodRefundProgressMessage(state, summary.pendingReason),
        summary.target.recordingOperationId, summary.target.resolutionOperationId, LocalRetailObservationPhase.ORIGINAL,
        originalUnknown = unknown, originalApplied = applied, originalActorId = summary.originalActorId,
        recordingActorId = summary.recordingActorId,
        reviewValues = outcome?.let { retailObservationSummary(it.foodRefundUiObservation()) }.orEmpty(),
        originalRecordingOperationId = summary.target.recordingOperationId,
        subject = LocalPaymentSubject.FOOD, financialKind = LocalRetailFinancialKind.REFUND)
}

internal fun foodRefundResolutionFactProgress(value: LocalFoodRefundReadResult.Resolution,
    id: OperationId = value.summary.target.resolutionOperationId): LocalRetailPhysicalProgress {
    requireFoodRefundResolution(value)
    val summary = value.summary
    val applied = summary.applicationState == LocalFoodRefundApplicationState.APPLIED
    val state = if (!applied) LocalRetailDurableState.SQL_RESOLUTION_PENDING
        else if (summary.appliedAction == LocalFoodRefundAction.NOT_PAID) LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
        else LocalRetailDurableState.FINANCIALLY_APPLIED
    return LocalRetailPhysicalProgress(id, state, foodRefundProgressMessage(state, summary.pendingReason),
        summary.target.resolutionOperationId, summary.target.resolutionOperationId, LocalRetailObservationPhase.RESOLUTION,
        resolutionRecorded = true, originalActorId = summary.originalActorId, recordingActorId = summary.recordingActorId,
        reviewValues = retailObservationSummary(value.outcome.foodRefundUiObservation()),
        originalRecordingOperationId = summary.target.recordingOperationId,
        subject = LocalPaymentSubject.FOOD, financialKind = LocalRetailFinancialKind.REFUND)
}

private fun foodRefundAccountingSelector(body: LocalFoodRefundRequest): Pair<OperationId, LocalFoodRefundFactPhase>? = when (body) {
    is LocalFoodRefundRequest.ApplyOriginal -> body.recordingOperationId to LocalFoodRefundFactPhase.ORIGINAL
    is LocalFoodRefundRequest.ApplyResolution -> body.resolutionOperationId to LocalFoodRefundFactPhase.RESOLUTION
    else -> null
}

private fun requireFoodRefundRecorded(entry: StoredClientIntentV1, result: LocalFoodRefundResult, committed: Boolean, code: String) {
    requireFoodRefundEntry(entry)
    val body = LocalFoodRefundCodec.decode(entry.request().payload)
    val accounting = foodRefundAccountingSelector(body)
    when (result) {
        is LocalFoodRefundResult.AttemptStarted -> {
            val start = requireFoodRefundStartTarget(entry, result.target)
            require(committed && result.amount == start.amount)
        }
        is LocalFoodRefundResult.RawRetained -> {
            val resolution = body as? LocalFoodRefundRequest.RecordResolution ?: unavailable()
            require(committed && result.phase == LocalFoodRefundFactPhase.RESOLUTION && result.target == resolution.target &&
                entry.operationId == result.target.resolutionOperationId)
        }
        is LocalFoodRefundResult.Applied -> require(committed && accounting != null && result.phase == accounting.second &&
            foodRefundFactOperation(result.target, result.phase) == accounting.first && result.applicationOperationId == entry.operationId &&
            entry.operationId !in setOf(result.target.intentOperationId, result.target.recordingOperationId, result.target.resolutionOperationId))
        is LocalFoodRefundResult.Pending -> require(!committed && code == "food.refund.pending_reconciliation" &&
            accounting != null && result.phase == accounting.second && foodRefundFactOperation(result.target, result.phase) == accounting.first)
        is LocalFoodRefundResult.AlreadyApplied -> require(!committed && code == "food.refund.already_applied" && accounting != null &&
            result.phase == accounting.second && result.recordingOperationId == accounting.first &&
            result.applicationOperationId != entry.operationId && result.applicationOperationId != result.recordingOperationId)
        is LocalFoodRefundResult.Unavailable -> require(!committed && result.code == code)
    }
}

internal fun foodRefundFactOperation(target: LocalFoodRefundTarget, phase: LocalFoodRefundFactPhase): OperationId =
    if (phase == LocalFoodRefundFactPhase.ORIGINAL) target.recordingOperationId else target.resolutionOperationId

internal fun LocalFoodRefundFactPhase.foodRefundUiPhase(): LocalRetailObservationPhase = when (this) {
    LocalFoodRefundFactPhase.ORIGINAL -> LocalRetailObservationPhase.ORIGINAL
    LocalFoodRefundFactPhase.RESOLUTION -> LocalRetailObservationPhase.RESOLUTION
}

internal fun LocalRetailObservationPhase.foodRefundFactPhase(): LocalFoodRefundFactPhase = when (this) {
    LocalRetailObservationPhase.ORIGINAL -> LocalFoodRefundFactPhase.ORIGINAL
    LocalRetailObservationPhase.RESOLUTION -> LocalFoodRefundFactPhase.RESOLUTION
}

internal fun foodRefundRecordedProgress(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailPhysicalProgress {
    require(reply.operationId == entry.operationId && reply.originalActorId == entry.owner.actorId)
    val value = LocalFoodRefundCodec.result(reply.result)
    requireFoodRefundRecorded(entry, value, reply.committed, reply.code)
    val target = when (value) {
        is LocalFoodRefundResult.AttemptStarted -> value.target
        is LocalFoodRefundResult.RawRetained -> value.target
        is LocalFoodRefundResult.Applied -> value.target
        is LocalFoodRefundResult.Pending -> value.target
        else -> null
    }
    val phase = when (value) {
        is LocalFoodRefundResult.RawRetained -> value.phase
        is LocalFoodRefundResult.Applied -> value.phase
        is LocalFoodRefundResult.Pending -> value.phase
        is LocalFoodRefundResult.AlreadyApplied -> value.phase
        else -> LocalFoodRefundFactPhase.ORIGINAL
    }
    val state = when (value) {
        is LocalFoodRefundResult.AttemptStarted -> LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION
        is LocalFoodRefundResult.RawRetained, is LocalFoodRefundResult.Pending ->
            if (phase == LocalFoodRefundFactPhase.ORIGINAL) LocalRetailDurableState.SQL_ORIGINAL_PENDING else LocalRetailDurableState.SQL_RESOLUTION_PENDING
        is LocalFoodRefundResult.Applied -> when (value.action) {
            LocalFoodRefundAction.CASH_PAID -> LocalRetailDurableState.FINANCIALLY_APPLIED
            LocalFoodRefundAction.NOT_PAID -> LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
            LocalFoodRefundAction.UNKNOWN -> LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED
        }
        is LocalFoodRefundResult.AlreadyApplied -> LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED
        is LocalFoodRefundResult.Unavailable -> LocalRetailDurableState.REJECTED
    }
    val fact = target?.let { foodRefundFactOperation(it, phase) } ?: (value as? LocalFoodRefundResult.AlreadyApplied)?.recordingOperationId
    val message = if (value is LocalFoodRefundResult.Unavailable)
        text("Command durably refused: ${value.code}. Preserve the earlier payout IDs and evidence.",
            "رُفض الأمر بشكل دائم: ${value.code}. احتفظ بمعرفات الرد السابقة وأدلتها.")
        else foodRefundProgressMessage(state, (value as? LocalFoodRefundResult.Pending)?.reason)
    return LocalRetailPhysicalProgress(entry.operationId, state, message, fact, target?.resolutionOperationId,
        phase = phase.foodRefundUiPhase().takeIf { fact != null },
        originalUnknown = value is LocalFoodRefundResult.Applied && value.action == LocalFoodRefundAction.UNKNOWN,
        resolutionRecorded = value is LocalFoodRefundResult.RawRetained && phase == LocalFoodRefundFactPhase.RESOLUTION,
        originalRecordingOperationId = target?.recordingOperationId,
        subject = LocalPaymentSubject.FOOD, financialKind = LocalRetailFinancialKind.REFUND)
}

internal fun foodRefundRecordedDecision(entry: StoredClientIntentV1, result: StoredKernelResultV1): LocalRecordedDecision {
    require(result.operationId == entry.operationId && result.originalActorId == entry.owner.actorId)
    val value = LocalFoodRefundCodec.result(decodeHex(result.payloadHex, 65_536).useWiped { StoredBytes(it) })
    requireFoodRefundRecorded(entry, value, result.committed, result.code)
    return when (value) {
        is LocalFoodRefundResult.Applied -> if (value.action == LocalFoodRefundAction.CASH_PAID) LocalRecordedDecision.APPLIED
            else LocalRecordedDecision.RECORDED_ONLY
        is LocalFoodRefundResult.Unavailable -> LocalRecordedDecision.REJECTED
        else -> LocalRecordedDecision.RECORDED_ONLY
    }
}

internal fun foodRefundRecordedUi(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val progress = foodRefundRecordedProgress(entry, reply)
    val body = LocalFoodRefundCodec.decode(entry.request().payload)
    val refundId = when (body) {
        is LocalFoodRefundRequest.StartCash -> body.refundId
        is LocalFoodRefundRequest.RecordResolution -> body.target.refundId
        else -> null
    }
    return LocalOperationResult.Recorded(entry.operationId, foodRefundRecordedDecision(entry, StoredKernelResultV1.from(reply)),
        progress.message, refundId?.let { LocalRecord(LocalRecordReference(LocalRecordKind.FOOD_REFUND, it), entry.label.ui()) }, reply.replayed)
}

private fun foodRefundProgressMessage(state: LocalRetailDurableState, reason: LocalFoodRefundPendingReason? = null): LocalText = when (state) {
    LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION -> text(
        "FOOD CASH refund Start is durably recorded. This display is not an instruction. Recover the original IDs; do not pay again.",
        "سُجل بدء رد نقدية الطعام بشكل دائم. هذا العرض ليس تعليمات حركة. استرد المعرفات الأصلية ولا تدفع مجددًا.")
    LocalRetailDurableState.SQL_ORIGINAL_PENDING, LocalRetailDurableState.SQL_RESOLUTION_PENDING -> text(
        "Immutable FOOD payout evidence retained; financial application/reconciliation remains pending: ${reason?.name ?: "PENDING"}. Do not pay again.",
        "حُفظ دليل رد الطعام الثابت؛ التطبيق المالي أو التسوية معلق: ${reason?.name ?: "PENDING"}. لا تدفع مجددًا.")
    LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED -> text(
        "UNKNOWN payout observation applied. The physical outcome and reserved cash remain unresolved; preserve the original evidence and do not pay again.",
        "طُبقت إفادة الرد مجهولة النتيجة. ما زالت الحركة الفعلية والنقدية المحجوزة غير محسومتين؛ احتفظ بالدليل الأصلي ولا تدفع مجددًا.")
    LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY -> text(
        "Recorded NOT PAID payout observation applied. Reserved cash was released; this application posted no money movement.",
        "طُبقت إفادة عدم دفع الرد المسجلة. حُررت النقدية المحجوزة؛ لم تسجل هذه المعالجة حركة مالية.")
    LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED -> text(
        "This payout fact was already applied. No money, stock or revenue effects were repeated; read the original for its actual outcome.",
        "طُبقت إفادة الرد بالفعل. لم تتكرر آثار المال أو المخزون أو الإيراد؛ اقرأ الأصل لمعرفة النتيجة الفعلية.")
    else -> text("FOOD CASH payout attestation financially applied once. No stock, kitchen, entitlement or revenue action was repeated.",
        "طُبقت إفادة رد نقدية الطعام ماليًا مرة واحدة. لم يتكرر إجراء للمخزون أو المطبخ أو الاستحقاق أو الإيراد.")
}

internal fun LocalRetailObservation.foodRefundObservation(): LocalFoodRefundOutcome {
    val actual = LocalCashEvidenceV1(evidence.evidenceId, evidence.occurredAt)
    return when (this) {
        is LocalRetailObservation.Unknown -> LocalFoodRefundOutcome.Unknown(actual)
        is LocalRetailObservation.NotPaid -> LocalFoodRefundOutcome.NotPaid(actual, reason)
        is LocalRetailObservation.CashPaid -> LocalFoodRefundOutcome.CashPaid(amount ?: invalid(), actual)
        is LocalRetailObservation.ExternalAttested -> LocalFoodRefundOutcome.ExternalAttested(actual, terminal, reference)
        is LocalRetailObservation.CashReceived -> invalid()
    }
}

internal fun LocalFoodRefundOutcome.foodRefundUiObservation(): LocalRetailObservation {
    val actual = when (this) {
        is LocalFoodRefundOutcome.Unknown -> evidence
        is LocalFoodRefundOutcome.NotPaid -> evidence
        is LocalFoodRefundOutcome.CashPaid -> evidence
        is LocalFoodRefundOutcome.ExternalAttested -> evidence
    }.let { LocalRetailEvidence(it.evidenceId, it.occurredAt) }
    return when (this) {
        is LocalFoodRefundOutcome.Unknown -> LocalRetailObservation.Unknown(actual)
        is LocalFoodRefundOutcome.NotPaid -> LocalRetailObservation.NotPaid(actual, reason)
        is LocalFoodRefundOutcome.CashPaid -> LocalRetailObservation.CashPaid(actual, amount)
        is LocalFoodRefundOutcome.ExternalAttested -> LocalRetailObservation.ExternalAttested(actual, terminal, reference)
    }
}
