package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** These entries are never dispatched by the generic form/submit route. */
internal val retailActions: Set<LocalAction> = setOf(LocalAction.RETAIL_COLLECTION_BEGIN,
    LocalAction.RETAIL_PAYMENT_START, LocalAction.RETAIL_REFUND_START, LocalAction.RETAIL_PAYMENT_APPLY,
    LocalAction.RETAIL_REFUND_APPLY, LocalAction.RETAIL_PAYMENT_RESOLUTION, LocalAction.RETAIL_REFUND_RESOLUTION,
    LocalAction.RETAIL_SUPERVISOR_REVIEW, LocalAction.RETAIL_SUPERVISOR_APPLY)
internal val retailStartActions: Set<LocalAction> = setOf(LocalAction.RETAIL_PAYMENT_START, LocalAction.RETAIL_REFUND_START)
internal val retailRefundActions: Set<LocalAction> = setOf(LocalAction.RETAIL_REFUND_START,
    LocalAction.RETAIL_REFUND_APPLY, LocalAction.RETAIL_REFUND_RESOLUTION,
    LocalAction.RETAIL_SUPERVISOR_REVIEW, LocalAction.RETAIL_SUPERVISOR_APPLY)

/** Closed canonical subtype mapping. In particular, no employee/UI RecordOriginal route exists. */
internal fun retailRequestAction(request: MutationRequest): LocalAction? {
    if (request.actionApprovalId != null) return null
    return when (request.command) {
        LocalSalesStoreProfile.COLLECTION_COMMAND -> if (LocalCollectionCommandCodec.decode(request.payload) is LocalCollectionRequestV1.Begin)
            LocalAction.RETAIL_COLLECTION_BEGIN else null
        com.ukkera.pos.database.StockStreamingCommandsV2.COLLECTION ->
            if (LocalCollectionStockCodecV2.decode(request.payload) is LocalCollectionStockRequestV2.Begin)
                LocalAction.RETAIL_COLLECTION_BEGIN else null
        LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> when (LocalCollectionCommandCodecV3.decode(request.payload)) {
            is LocalCollectionRequestV3.Start -> LocalAction.RETAIL_PAYMENT_START
            is LocalCollectionRequestV3.ApplyObservation -> LocalAction.RETAIL_PAYMENT_APPLY
            is LocalCollectionRequestV3.RecordResolution -> LocalAction.RETAIL_PAYMENT_RESOLUTION
            is LocalCollectionRequestV3.RecordOriginal -> null
        }
        LocalFinancialJournalProfileV3.RETURN_COMMAND -> when (LocalReturnCommandCodecV3.decode(request.payload)) {
            is LocalReturnRequestV3.Start -> LocalAction.RETAIL_REFUND_START
            is LocalReturnRequestV3.ApplyObservation -> LocalAction.RETAIL_REFUND_APPLY
            is LocalReturnRequestV3.RecordResolution -> LocalAction.RETAIL_REFUND_RESOLUTION
            is LocalReturnRequestV3.RecordOriginal -> null
        }
        LocalRetailSupervisorCodecV1.COMMAND -> when (LocalRetailSupervisorCodecV1.decode(request.payload)) {
            is LocalRetailSupervisorRequestV1.Review -> LocalAction.RETAIL_SUPERVISOR_REVIEW
            is LocalRetailSupervisorRequestV1.Apply -> LocalAction.RETAIL_SUPERVISOR_APPLY
        }
        else -> null
    }
}

internal fun retailIntent(owner: LocalClientIdentity, request: MutationRequest,
    label: LocalText, summary: List<LocalRecordValue>): StoredClientIntentV1 {
    val action = retailRequestAction(request) ?: invalid()
    val normalized = normalizeLocalRequest(request.command, request.payload)
    require(normalized == request.payload)
    val digest = semanticDigest(owner.scope, owner.actorId, request.command, normalized)
    return StoredClientIntentV1.staged(owner, physicalUiIntent(request.operationId, action, digest), request, label, summary)
}

/** Existing immutable input-digest shape shared by the dedicated native payment routes. */
internal fun physicalUiIntent(id: OperationId, action: LocalAction, digest: PayloadDigest): LocalCommandIntent =
    LocalCommandIntent(id, action, 2, null, listOf(LocalScalarInput(LocalField.RETAIL_BODY_DIGEST, LocalScalarValue.Text(digest.value))))

internal fun requireRetailEntry(entry: StoredClientIntentV1) {
    entry.validate(entry.owner)
    require(entry.localAction in retailActions && entry.formVersion == 2 && retailRequestAction(entry.request()) == entry.localAction)
    require(entry.inputDigest == inputDigest(physicalUiIntent(entry.operationId, entry.localAction, entry.semanticSha256)))
}

internal fun retailSaved(entry: StoredClientIntentV1): LocalRetailSavedOperation {
    requireRetailEntry(entry)
    return LocalRetailSavedOperation(entry.operationId, entry.label.ui(), entry.localPhase != LocalPendingPhase.STAGED,
        entry.localAction in retailStartActions, entry.recorded != null, entry.summary.map { it.ui() },
        financialKind = retailFinancialKind(entry))
}

internal fun SalesRecordingKind.retailUiFinancialKind(): LocalRetailFinancialKind = when (this) {
    SalesRecordingKind.PAYMENT_ATTESTATION -> LocalRetailFinancialKind.PAYMENT
    SalesRecordingKind.REFUND_ATTESTATION -> LocalRetailFinancialKind.REFUND
}

/** Canonical stored subtype only. Review has no financial kind before its separate audited read. */
internal fun retailFinancialKind(entry: StoredClientIntentV1): LocalRetailFinancialKind? {
    requireRetailEntry(entry)
    return when (entry.localAction) {
        LocalAction.RETAIL_COLLECTION_BEGIN, LocalAction.RETAIL_PAYMENT_START,
        LocalAction.RETAIL_PAYMENT_APPLY, LocalAction.RETAIL_PAYMENT_RESOLUTION -> LocalRetailFinancialKind.PAYMENT
        LocalAction.RETAIL_REFUND_START, LocalAction.RETAIL_REFUND_APPLY,
        LocalAction.RETAIL_REFUND_RESOLUTION -> LocalRetailFinancialKind.REFUND
        LocalAction.RETAIL_SUPERVISOR_APPLY -> (LocalRetailSupervisorCodecV1.decode(entry.request().payload)
            as LocalRetailSupervisorRequestV1.Apply).kind.retailUiFinancialKind()
        LocalAction.RETAIL_SUPERVISOR_REVIEW -> null
        else -> unavailable()
    }
}

internal fun retailTarget(request: MutationRequest): LocalJournalTargetV3? = when (request.command) {
    LocalFinancialJournalProfileV3.COLLECTION_COMMAND -> when (val body = LocalCollectionCommandCodecV3.decode(request.payload)) {
        is LocalCollectionRequestV3.Start -> LocalJournalTargetV3(SalesRecordingKind.PAYMENT_ATTESTATION, body.collectionId,
            body.sessionId, body.attemptId, request.operationId, body.recordingOperationId, body.resolutionOperationId)
        is LocalCollectionRequestV3.RecordResolution -> body.target
        else -> null
    }
    LocalFinancialJournalProfileV3.RETURN_COMMAND -> when (val body = LocalReturnCommandCodecV3.decode(request.payload)) {
        is LocalReturnRequestV3.Start -> LocalJournalTargetV3(SalesRecordingKind.REFUND_ATTESTATION, body.returnId,
            body.sessionId, body.attemptId, request.operationId, body.recordingOperationId, body.resolutionOperationId,
            body.saleId, body.claimId, body.originalPaymentId)
        is LocalReturnRequestV3.RecordResolution -> body.target
        else -> null
    }
    else -> null
}

internal fun retailFactOperation(target: LocalJournalTargetV3, phase: LocalJournalFactPhaseV3): OperationId =
    if (phase == LocalJournalFactPhaseV3.ORIGINAL) target.recordingOperationId else target.resolutionOperationId
internal fun LocalJournalFactPhaseV3.retailUiFactPhase(): LocalRetailObservationPhase = when (this) {
    LocalJournalFactPhaseV3.ORIGINAL -> LocalRetailObservationPhase.ORIGINAL
    LocalJournalFactPhaseV3.RESOLUTION -> LocalRetailObservationPhase.RESOLUTION
}
internal fun LocalRetailObservationPhase.retailDomainFact(): LocalJournalFactPhaseV3 = when (this) {
    LocalRetailObservationPhase.ORIGINAL -> LocalJournalFactPhaseV3.ORIGINAL
    LocalRetailObservationPhase.RESOLUTION -> LocalJournalFactPhaseV3.RESOLUTION
}

internal fun retailDisplay(target: LocalJournalTargetV3, amount: com.ukkera.pos.types.Money,
    tender: LocalTenderViewV1): LocalRetailInstructionDisplay = LocalRetailInstructionDisplay(target.intentOperationId,
    target.recordingOperationId, target.resolutionOperationId,
    if (target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) LocalRetailPhysicalKind.PAYMENT else LocalRetailPhysicalKind.REFUND,
    target.documentId, target.sessionId, amount, tender.kind, tender.requiresTerminal, tender.requiresReference)

internal fun requireRetailSummary(summary: LocalJournalFactSummaryV3) {
    require(summary.recordingOperationId == retailFactOperation(summary.target, summary.phase) && summary.amount.minor > 0L)
    PayloadDigest(summary.bodyDigest)
    require((summary.phase == LocalJournalFactPhaseV3.RESOLUTION) == (summary.originalBodyDigest != null))
    summary.originalBodyDigest?.let(::PayloadDigest)
    require(summary.phase != LocalJournalFactPhaseV3.ORIGINAL || summary.recordingActorId == summary.originalActorId)
    val applied = summary.applicationState == LocalJournalApplicationStateV3.APPLIED
    require(if (applied) summary.appliedAction != null && summary.pendingReason == null && summary.lastApplicationOperationId != null
        else summary.appliedAction == null && summary.pendingReason != null)
    summary.appliedAction?.let { requireRetailAppliedAction(summary.target.kind, summary.phase, it) }
    require(summary.tender.kind in setOf("CASH", "CARD", "EXTERNAL_WALLET", "OTHER"))
    require(if (summary.tender.kind == "CASH") summary.tender.tenderId == null && summary.tender.policyRevision == null &&
        !summary.tender.requiresTerminal && !summary.tender.requiresReference
    else summary.tender.tenderId != null && summary.tender.policyRevision != null)
}

private fun requireRetailAppliedAction(kind: SalesRecordingKind, phase: LocalJournalFactPhaseV3, action: LocalSalesAction) {
    require(action in (if (kind == SalesRecordingKind.PAYMENT_ATTESTATION)
        setOf(LocalSalesAction.PAYMENT_RECORDED, LocalSalesAction.PAYMENT_UNKNOWN, LocalSalesAction.PAYMENT_NOT_PAID)
    else setOf(LocalSalesAction.REFUND_RECORDED, LocalSalesAction.REFUND_UNKNOWN, LocalSalesAction.REFUND_NOT_PAID)))
    require(phase == LocalJournalFactPhaseV3.ORIGINAL || action !in setOf(LocalSalesAction.PAYMENT_UNKNOWN, LocalSalesAction.REFUND_UNKNOWN))
}

private fun retailApplicationSelector(entry: StoredClientIntentV1): Triple<SalesRecordingKind, OperationId, LocalJournalFactPhaseV3>? {
    val request = entry.request()
    return when (entry.localAction) {
        LocalAction.RETAIL_PAYMENT_APPLY -> (LocalCollectionCommandCodecV3.decode(request.payload) as LocalCollectionRequestV3.ApplyObservation)
            .let { Triple(SalesRecordingKind.PAYMENT_ATTESTATION, it.recordingOperationId, it.phase) }
        LocalAction.RETAIL_REFUND_APPLY -> (LocalReturnCommandCodecV3.decode(request.payload) as LocalReturnRequestV3.ApplyObservation)
            .let { Triple(SalesRecordingKind.REFUND_ATTESTATION, it.recordingOperationId, it.phase) }
        LocalAction.RETAIL_SUPERVISOR_APPLY -> (LocalRetailSupervisorCodecV1.decode(request.payload) as LocalRetailSupervisorRequestV1.Apply)
            .let { Triple(it.kind, it.recordingOperationId, it.phase) }
        else -> null
    }
}

private fun requireRetailApplied(entry: StoredClientIntentV1, result: LocalFinancialResultV3.Applied) {
    val selected = retailApplicationSelector(entry) ?: unavailable()
    require(result.target.kind == selected.first && retailFactOperation(result.target, result.phase) == selected.second &&
        result.phase == selected.third && result.applicationOperationId == entry.operationId && selected.second != entry.operationId)
    requireRetailAppliedAction(result.target.kind, result.phase, result.action)
}

internal fun requireRetailFact(fact: LocalFinancialReadResultV3.Fact) {
    requireRetailSummary(fact.summary)
    require(if (fact.summary.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION) fact.payment != null && fact.refund == null
        else fact.refund != null && fact.payment == null)
    require(fact.summary.phase != LocalJournalFactPhaseV3.RESOLUTION ||
        fact.payment !is LocalPaymentOutcomeV3.Unknown && fact.refund !is LocalRefundOutcomeV3.Unknown)
    fact.summary.appliedAction?.let { action ->
        val expected = when {
            fact.payment is LocalPaymentOutcomeV3.Unknown -> LocalSalesAction.PAYMENT_UNKNOWN
            fact.payment is LocalPaymentOutcomeV3.NotPaid -> LocalSalesAction.PAYMENT_NOT_PAID
            fact.payment != null -> LocalSalesAction.PAYMENT_RECORDED
            fact.refund is LocalRefundOutcomeV3.Unknown -> LocalSalesAction.REFUND_UNKNOWN
            fact.refund is LocalRefundOutcomeV3.NotPaid -> LocalSalesAction.REFUND_NOT_PAID
            else -> LocalSalesAction.REFUND_RECORDED
        }
        require(action == expected)
    }
}

/** Historical audited bytes, never a supervisor principal or an instruction/absence certificate. */
internal fun retailReview(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailSupervisorReviewV1 {
    requireRetailEntry(entry)
    require(entry.localAction == LocalAction.RETAIL_SUPERVISOR_REVIEW && !reply.committed && reply.code == "sales.supervisor.reviewed" &&
        reply.operationId == entry.operationId && reply.originalActorId == entry.owner.actorId)
    val request = LocalRetailSupervisorCodecV1.decode(entry.request().payload) as? LocalRetailSupervisorRequestV1.Review ?: unavailable()
    return LocalRetailSupervisorCodecV1.decodeReview(reply.result).also { review ->
        val original = review.original
        requireRetailSummary(original)
        require(original.phase == LocalJournalFactPhaseV3.ORIGINAL && original.recordingOperationId == request.originalRecordingOperationId)
        requireRetailFact(LocalFinancialReadResultV3.Fact(original, review.payment, review.refund))
        require(review.resolution != null || review.resolutionPayment == null && review.resolutionRefund == null)
        review.resolution?.let { resolution ->
            requireRetailSummary(resolution)
            require(review.payment is LocalPaymentOutcomeV3.Unknown || review.refund is LocalRefundOutcomeV3.Unknown)
            require(resolution.target == original.target && resolution.phase == LocalJournalFactPhaseV3.RESOLUTION &&
                resolution.originalActorId == original.originalActorId && resolution.originalBodyDigest == original.bodyDigest &&
                resolution.amount == original.amount && resolution.tender == original.tender)
            requireRetailFact(LocalFinancialReadResultV3.Fact(resolution, review.resolutionPayment, review.resolutionRefund))
        }
    }
}

private fun LocalCashEvidenceV1.uiEvidence(): LocalRetailEvidence = LocalRetailEvidence(evidenceId, occurredAt)
private fun LocalPaymentOutcomeV3.uiObservation(): LocalRetailObservation = when (this) {
    is LocalPaymentOutcomeV3.Unknown -> LocalRetailObservation.Unknown(evidence.uiEvidence())
    is LocalPaymentOutcomeV3.NotPaid -> LocalRetailObservation.NotPaid(evidence.uiEvidence(), reason)
    is LocalPaymentOutcomeV3.CashReceived -> LocalRetailObservation.CashReceived(evidence.uiEvidence(), received, change)
    is LocalPaymentOutcomeV3.ExternalAttested -> LocalRetailObservation.ExternalAttested(evidence.uiEvidence(), terminal, reference)
}
private fun LocalRefundOutcomeV3.uiObservation(): LocalRetailObservation = when (this) {
    is LocalRefundOutcomeV3.Unknown -> LocalRetailObservation.Unknown(evidence.uiEvidence())
    is LocalRefundOutcomeV3.NotPaid -> LocalRetailObservation.NotPaid(evidence.uiEvidence(), reason)
    is LocalRefundOutcomeV3.CashPaid -> LocalRetailObservation.CashPaid(evidence.uiEvidence())
    is LocalRefundOutcomeV3.ExternalAttested -> LocalRetailObservation.ExternalAttested(evidence.uiEvidence(), terminal, reference)
}

/** Exact observation display, including mismatches; never replace it with the intended payment. */
internal fun retailObservationSummary(value: LocalRetailObservation): List<LocalRecordValue> = buildList {
    val outcome = when (value) {
        is LocalRetailObservation.Unknown -> text("UNKNOWN", "غير معلوم")
        is LocalRetailObservation.NotPaid -> text("NOT PAID", "لم يُدفع")
        is LocalRetailObservation.CashReceived -> text("Cash actually received", "نقدية مستلمة فعليًا")
        is LocalRetailObservation.CashPaid -> text("Cash refund actually paid", "رد نقدي مدفوع فعليًا")
        is LocalRetailObservation.ExternalAttested -> text("External tender attested", "إفادة دفع خارجي")
    }
    add(recordValue(text("Actual recorded outcome", "النتيجة الفعلية المسجلة"), outcome))
    when (value) {
        is LocalRetailObservation.NotPaid -> add(recordValue(text("Actual reason", "السبب الفعلي"), literal(value.reason)))
        is LocalRetailObservation.CashReceived -> {
            add(recordValue(text("Actually received", "المستلم فعليًا"), moneyText(value.received)))
            add(recordValue(text("Change actually given", "الباقي المعطى فعليًا"), moneyText(value.change)))
        }
        is LocalRetailObservation.CashPaid -> value.amount?.let {
            add(recordValue(text("Actually paid", "المدفوع فعليًا"), moneyText(it)))
        }
        is LocalRetailObservation.ExternalAttested -> {
            fun metadata(input: String?): LocalText = when (input) {
                null -> text("Not supplied", "غير مقدم")
                "" -> text("Empty recorded value", "قيمة فارغة مسجلة")
                else -> literal(input)
            }
            add(recordValue(text("Actual terminal", "الطرفية الفعلية"), metadata(value.terminal)))
            add(recordValue(text("Actual reference", "المرجع الفعلي"), metadata(value.reference)))
        }
        else -> Unit
    }
    add(recordValue(text("Evidence ID", "معرف الدليل"), literal(value.evidence.evidenceId.value)))
    add(recordValue(text("Cashier-attested occurrence (UTC)", "وقت الحدث بإفادة الموظف — UTC"), literal(value.evidence.occurredAt.canonicalString())))
}

private fun retailReviewValues(review: LocalRetailSupervisorReviewV1): List<LocalRecordValue> = buildList {
    fun append(summary: LocalJournalFactSummaryV3, payment: LocalPaymentOutcomeV3?, refund: LocalRefundOutcomeV3?) {
        add(recordValue(text("Historical phase", "المرحلة التاريخية"), literal(summary.phase.name)))
        add(recordValue(text("Fact SQL application state", "حالة تطبيق الإفادة في قاعدة البيانات"), literal(summary.applicationState.name)))
        summary.pendingReason?.let { add(recordValue(text("Pending reason", "سبب التعليق"), literal(it.name))) }
        addAll(retailObservationSummary(payment?.uiObservation() ?: refund?.uiObservation() ?: unavailable()))
    }
    append(review.original, review.payment, review.refund)
    review.resolution?.let { append(it, review.resolutionPayment, review.resolutionRefund) }
}

internal fun retailFactProgress(summary: LocalJournalFactSummaryV3, id: OperationId = summary.recordingOperationId,
    knownUnknown: Boolean = false): LocalRetailPhysicalProgress {
    requireRetailSummary(summary)
    val applied = summary.applicationState == LocalJournalApplicationStateV3.APPLIED
    val unknown = knownUnknown || summary.appliedAction in setOf(LocalSalesAction.PAYMENT_UNKNOWN, LocalSalesAction.REFUND_UNKNOWN)
    val target = summary.target
    val state = when {
        applied && unknown -> LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED
        applied && summary.appliedAction in setOf(LocalSalesAction.PAYMENT_NOT_PAID, LocalSalesAction.REFUND_NOT_PAID) ->
            LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
        applied -> LocalRetailDurableState.FINANCIALLY_APPLIED
        summary.phase == LocalJournalFactPhaseV3.ORIGINAL -> LocalRetailDurableState.SQL_ORIGINAL_PENDING
        else -> LocalRetailDurableState.SQL_RESOLUTION_PENDING
    }
    val message = when {
        applied && unknown -> text("UNKNOWN observation applied to the ledger. The physical outcome is STILL UNRESOLVED; do not collect or refund again.",
            "سُجلت الإفادة مجهولة النتيجة في الدفتر. ما زالت النتيجة الفعلية غير محسومة؛ لا تكرر التحصيل أو الرد.")
        applied && summary.appliedAction in setOf(LocalSalesAction.PAYMENT_NOT_PAID, LocalSalesAction.REFUND_NOT_PAID) ->
            text("The recorded NOT PAID observation was applied. No money movement was posted by this application.",
                "طُبقت إفادة عدم الدفع المسجلة. لم تسجل هذه المعالجة حركة مالية.")
        applied -> text("The recorded attestation was financially applied once. This is not bank verification or sale finalization.",
            "طُبقت الإفادة المسجلة ماليًا مرة واحدة. هذا ليس تحققًا مصرفيًا أو تثبيتًا للبيع.")
        else -> text("Immutable SQL observation retained; financial application/reconciliation is pending: ${summary.pendingReason?.name ?: "PENDING"}. Do not repeat the physical action.",
            "حُفظت الإفادة الثابتة في قاعدة البيانات؛ التطبيق المالي أو التسوية معلق: ${summary.pendingReason?.name ?: "PENDING"}. لا تكرر الحركة الفعلية.")
    }
    return LocalRetailPhysicalProgress(id, state, message, summary.recordingOperationId, target.resolutionOperationId,
        summary.phase.retailUiFactPhase(), unknown && summary.phase == LocalJournalFactPhaseV3.ORIGINAL,
        target.documentId.takeIf { target.kind == SalesRecordingKind.PAYMENT_ATTESTATION },
        originalApplied = applied && summary.phase == LocalJournalFactPhaseV3.ORIGINAL,
        resolutionRecorded = summary.phase == LocalJournalFactPhaseV3.RESOLUTION,
        originalActorId = summary.originalActorId, recordingActorId = summary.recordingActorId,
        originalRecordingOperationId = target.recordingOperationId, financialKind = target.kind.retailUiFinancialKind())
}

internal fun retailRecordedProgress(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailPhysicalProgress {
    requireRetailEntry(entry)
    require(reply.operationId == entry.operationId && reply.originalActorId == entry.owner.actorId)
    val id = entry.operationId
    if (entry.localAction == LocalAction.RETAIL_SUPERVISOR_REVIEW && reply.code == "sales.supervisor.reviewed") {
        val review = retailReview(entry, reply)
        val original = review.original
        return LocalRetailPhysicalProgress(id, LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED,
            text("Current-authorized review durably audited. Original custody is unchanged. Any resolution is a separate fixed-ID command, never another payment/refund instruction.",
                "سُجلت المراجعة المصرح بها حاليًا في سجل التدقيق. لم تتغير العهدة الأصلية. أي تسوية أمر منفصل بمعرف ثابت، وليست تعليمات دفع أو رد جديدة."),
            original.recordingOperationId, original.target.resolutionOperationId, LocalRetailObservationPhase.ORIGINAL,
            review.payment is LocalPaymentOutcomeV3.Unknown || review.refund is LocalRefundOutcomeV3.Unknown,
            original.target.documentId.takeIf { original.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION },
            retailDisplay(original.target, original.amount, original.tender),
            original.applicationState == LocalJournalApplicationStateV3.APPLIED, review.resolution != null,
            original.originalActorId, review.resolution?.recordingActorId ?: original.recordingActorId,
            retailReviewValues(review), originalRecordingOperationId = original.target.recordingOperationId,
            financialKind = original.target.kind.retailUiFinancialKind())
    }
    if (entry.localAction == LocalAction.RETAIL_COLLECTION_BEGIN) {
        val request = entry.request()
        val collectionId = when (request.command) {
            LocalSalesStoreProfile.COLLECTION_COMMAND -> (LocalCollectionCommandCodec.decode(request.payload) as? LocalCollectionRequestV1.Begin)?.collectionId
            com.ukkera.pos.database.StockStreamingCommandsV2.COLLECTION -> (LocalCollectionStockCodecV2.decode(request.payload) as? LocalCollectionStockRequestV2.Begin)?.collectionId
            else -> null
        } ?: unavailable()
        val result = LocalSalesResultCodec.decode(reply.result)
        if (reply.committed && result is LocalSalesResultV1.Applied && result.action == LocalSalesAction.COLLECTION_LOCKED && result.documentId == collectionId)
            return LocalRetailPhysicalProgress(id, LocalRetailDurableState.COLLECTION_PREPARED,
                text("Collection and stock reservation recorded. NO money has been collected and no physical instruction was issued.",
                    "سُجل التحصيل وحجز المخزون. لم يُحصل أي مال ولم تصدر تعليمات حركة فعلية."), collectionId = collectionId,
                financialKind = LocalRetailFinancialKind.PAYMENT)
        require(!reply.committed)
        return retailRejected(id, reply.code).copy(financialKind = LocalRetailFinancialKind.PAYMENT)
    }
    // An ordinary supervisor refusal may use the handler's V3 failure result, never review data.
    val value = LocalFinancialResultCodecV3.decode(reply.result)
    val target = when (value) {
        is LocalFinancialResultV3.AttemptStarted -> value.target
        is LocalFinancialResultV3.Pending -> value.target
        is LocalFinancialResultV3.Applied -> value.target
        else -> null
    }
    val request = entry.request()
    val expectedTarget = retailTarget(request)
    if (target != null && expectedTarget != null) require(target == expectedTarget)
    val application = retailApplicationSelector(entry)
    if (target != null && application != null) require(target.kind == application.first)
    val phase = when (value) {
        is LocalFinancialResultV3.Pending -> value.phase
        is LocalFinancialResultV3.Applied -> value.phase
        is LocalFinancialResultV3.AlreadyApplied -> value.phase
        else -> null
    }
    val observation = when (entry.localAction) {
        LocalAction.RETAIL_PAYMENT_APPLY -> (LocalCollectionCommandCodecV3.decode(request.payload) as LocalCollectionRequestV3.ApplyObservation).let { it.recordingOperationId to it.phase }
        LocalAction.RETAIL_REFUND_APPLY -> (LocalReturnCommandCodecV3.decode(request.payload) as LocalReturnRequestV3.ApplyObservation).let { it.recordingOperationId to it.phase }
        LocalAction.RETAIL_SUPERVISOR_APPLY -> (LocalRetailSupervisorCodecV1.decode(request.payload) as LocalRetailSupervisorRequestV1.Apply).let { it.recordingOperationId to it.phase }
        LocalAction.RETAIL_PAYMENT_RESOLUTION, LocalAction.RETAIL_REFUND_RESOLUTION -> id to LocalJournalFactPhaseV3.RESOLUTION
        else -> null
    }
    if (phase != null && observation != null) require(phase == observation.second &&
        (target == null || retailFactOperation(target, phase) == observation.first))
    return when (value) {
        is LocalFinancialResultV3.AttemptStarted -> {
            require(reply.committed && entry.localAction in retailStartActions && value.target.intentOperationId == id)
            val amount = if (entry.localAction == LocalAction.RETAIL_PAYMENT_START)
                (LocalCollectionCommandCodecV3.decode(request.payload) as LocalCollectionRequestV3.Start).amount
            else (LocalReturnCommandCodecV3.decode(request.payload) as LocalReturnRequestV3.Start).amount
            require(value.amount == amount)
            PayloadDigest(value.requirementDigest); PayloadDigest(value.effectsDigest)
            LocalRetailPhysicalProgress(id, LocalRetailDurableState.START_RECORDED_NO_INSTRUCTION,
                text("Start is durably recorded. This replay/progress display is NOT an instruction. Recover the original observation; never start again for this ID.",
                    "سُجل البدء بشكل دائم. عرض الاسترداد أو التقدم هذا ليس تعليمات حركة. استرد الإفادة الأصلية ولا تكرر البدء لهذا المعرف."),
                value.target.recordingOperationId, value.target.resolutionOperationId,
                collectionId = value.target.documentId.takeIf { value.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION },
                originalRecordingOperationId = value.target.recordingOperationId)
        }
        is LocalFinancialResultV3.StockHeld -> {
            require(reply.committed && entry.localAction == LocalAction.RETAIL_PAYMENT_START)
            val body = LocalCollectionCommandCodecV3.decode(request.payload) as LocalCollectionRequestV3.Start
            require(value.collectionId == body.collectionId)
            PayloadDigest(value.effectsDigest)
            LocalRetailPhysicalProgress(id, LocalRetailDurableState.STOCK_HELD_NO_INSTRUCTION,
                text("Stock safety hold recorded. No physical instruction was issued. Reconcile stock before continuing.",
                    "سُجل قيد أمان المخزون. لم تصدر تعليمات حركة فعلية. سوِّ المخزون قبل المتابعة."), collectionId = value.collectionId)
        }
        is LocalFinancialResultV3.Pending -> {
            require(observation != null)
            if (application == null) require(reply.committed && value.phase == LocalJournalFactPhaseV3.RESOLUTION &&
                value.reason == LocalJournalPendingReasonV3.AWAITING_FINANCIAL_APPLICATION)
            else require(reply.committed || reply.code == "sales.journal.pending") // exhausted revision can refuse a pending-row update
            LocalRetailPhysicalProgress(id,
            if (value.phase == LocalJournalFactPhaseV3.ORIGINAL) LocalRetailDurableState.SQL_ORIGINAL_PENDING else LocalRetailDurableState.SQL_RESOLUTION_PENDING,
            text("SQL observation/application remains pending: ${value.reason.name}. No permission to repeat the physical action.",
                "ما زال تسجيل الإفادة أو تطبيقها معلقًا: ${value.reason.name}. لا يجيز ذلك تكرار الحركة الفعلية."),
            retailFactOperation(value.target, value.phase), value.target.resolutionOperationId, value.phase.retailUiFactPhase(),
            collectionId = value.target.documentId.takeIf { value.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION },
            resolutionRecorded = value.phase == LocalJournalFactPhaseV3.RESOLUTION,
            originalRecordingOperationId = value.target.recordingOperationId)
        }
        is LocalFinancialResultV3.Applied -> {
            require(reply.committed && observation != null && value.applicationOperationId == id)
            requireRetailApplied(entry, value)
            val unknown = value.action in setOf(LocalSalesAction.PAYMENT_UNKNOWN, LocalSalesAction.REFUND_UNKNOWN)
            val noMoney = value.action in setOf(LocalSalesAction.PAYMENT_NOT_PAID, LocalSalesAction.REFUND_NOT_PAID)
            LocalRetailPhysicalProgress(id, when {
                unknown -> LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED
                noMoney -> LocalRetailDurableState.OBSERVATION_APPLIED_NO_MONEY
                else -> LocalRetailDurableState.FINANCIALLY_APPLIED
            },
                when {
                    unknown -> text("UNKNOWN recorded and applied; physical outcome remains unresolved. Do NOT charge/refund again.", "سُجلت وطُبقت النتيجة المجهولة؛ ما زالت الحركة غير محسومة. لا تكرر التحصيل أو الرد.")
                    noMoney -> text("NOT PAID observation applied; no money movement was posted.", "طُبقت إفادة عدم الدفع؛ لم تسجل حركة مالية.")
                    else -> text("Attestation financially applied once. Sale finalization and bank verification are separate.", "طُبقت الإفادة ماليًا مرة واحدة. تثبيت البيع والتحقق المصرفي أمران منفصلان.")
                }, retailFactOperation(value.target, value.phase), value.target.resolutionOperationId, value.phase.retailUiFactPhase(), unknown,
                value.target.documentId.takeIf { value.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION },
                originalRecordingOperationId = value.target.recordingOperationId)
        }
        is LocalFinancialResultV3.AlreadyApplied -> {
            require(!reply.committed && reply.code == "sales.journal.already_applied" && application != null &&
                observation == (value.recordingOperationId to value.phase) && value.applicationOperationId != id)
            LocalRetailPhysicalProgress(id, LocalRetailDurableState.OBSERVATION_ALREADY_APPLIED,
                text("This observation was already applied by ${value.applicationOperationId.value}; no effects were repeated. Read its fact for the physical outcome, which may still be UNKNOWN.",
                    "طُبقت هذه الإفادة بالفعل بواسطة ${value.applicationOperationId.value}؛ لم تتكرر الآثار. اقرأ الإفادة لمعرفة النتيجة الفعلية فقد تظل مجهولة."),
                value.recordingOperationId, phase = value.phase.retailUiFactPhase(),
                originalRecordingOperationId = value.recordingOperationId.takeIf { value.phase == LocalJournalFactPhaseV3.ORIGINAL })
        }
        is LocalFinancialResultV3.Rejected, is LocalFinancialResultV3.Unavailable, LocalFinancialResultV3.NotAvailable -> {
            require(!reply.committed)
            retailRejected(id, reply.code)
        }
    }.copy(financialKind = target?.kind?.retailUiFinancialKind() ?: retailFinancialKind(entry))
}

private fun retailRejected(id: OperationId, code: String) = LocalRetailPhysicalProgress(id, LocalRetailDurableState.REJECTED,
    text("Command durably refused: $code. This says nothing about an earlier physical action; preserve its original IDs and evidence.",
        "رُفض الأمر وسُجل الرفض: $code. لا يثبت ذلك نتيجة حركة فعلية سابقة؛ احتفظ بمعرفاتها وأدلتها الأصلية."))

internal fun retailRecordedDecision(entry: StoredClientIntentV1, result: StoredKernelResultV1): LocalRecordedDecision {
    if (entry.localAction !in retailActions) return if (result.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED
    requireRetailEntry(entry)
    if (entry.localAction == LocalAction.RETAIL_SUPERVISOR_REVIEW && result.code == "sales.supervisor.reviewed" && !result.committed)
        return LocalRecordedDecision.RECORDED_ONLY
    if (entry.localAction == LocalAction.RETAIL_COLLECTION_BEGIN) return if (result.committed) LocalRecordedDecision.RECORDED_ONLY else LocalRecordedDecision.REJECTED
    val value = LocalFinancialResultCodecV3.decode(decodeHex(result.payloadHex, 65_536).useWiped { StoredBytes(it) })
    return when (value) {
        is LocalFinancialResultV3.Applied -> {
            require(result.committed)
            requireRetailApplied(entry, value)
            if (value.action in setOf(LocalSalesAction.PAYMENT_RECORDED, LocalSalesAction.REFUND_RECORDED))
                LocalRecordedDecision.APPLIED else LocalRecordedDecision.RECORDED_ONLY
        }
        is LocalFinancialResultV3.Rejected, is LocalFinancialResultV3.Unavailable, LocalFinancialResultV3.NotAvailable -> {
            require(!result.committed)
            LocalRecordedDecision.REJECTED
        }
        else -> LocalRecordedDecision.RECORDED_ONLY
    }
}

internal fun retailRecordedUi(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val progress = retailRecordedProgress(entry, reply)
    return LocalOperationResult.Recorded(entry.operationId, retailRecordedDecision(entry, StoredKernelResultV1.from(reply)),
        progress.message, progress.collectionId?.let { LocalRecord(LocalRecordReference(LocalRecordKind.COLLECTION, it), entry.label.ui()) }, reply.replayed)
}
