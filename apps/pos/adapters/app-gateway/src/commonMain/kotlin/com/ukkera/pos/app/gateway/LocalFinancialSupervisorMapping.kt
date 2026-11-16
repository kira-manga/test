package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*

internal val financialSupervisorReviewActions: Set<LocalAction> = setOf(LocalAction.FOOD_REFUND_SUPERVISOR_REVIEW,
    LocalAction.SUPPLIER_CASH_SUPERVISOR_REVIEW)
internal val financialSupervisorActions: Set<LocalAction> = financialSupervisorReviewActions +
    setOf(LocalAction.FOOD_REFUND_SUPERVISOR_APPLY, LocalAction.SUPPLIER_CASH_SUPERVISOR_APPLY)

/** Typed routing only. The saved new command and digest are never replaced by this selector. */
internal fun foodRefundSavedRequest(entry: StoredClientIntentV1): LocalFoodRefundRequest = when (entry.request().command) {
    LocalFoodRefundProfile.COMMAND -> LocalFoodRefundCodec.decode(entry.request().payload)
    LocalFoodRefundSupervisorCodec.COMMAND -> {
        val request = LocalFoodRefundSupervisorCodec.decode(entry.request().payload) as? LocalFoodRefundSupervisorRequest.Apply ?: unavailable()
        if (request.phase == LocalFoodRefundFactPhase.ORIGINAL) LocalFoodRefundRequest.ApplyOriginal(request.factOperationId)
        else LocalFoodRefundRequest.ApplyResolution(request.factOperationId)
    }
    else -> unavailable()
}

internal fun foodRefundAuditedReview(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalFoodRefundSupervisorReview {
    requireFoodRefundEntry(entry)
    require(entry.localAction == LocalAction.FOOD_REFUND_SUPERVISOR_REVIEW && reply.operationId == entry.operationId &&
        reply.originalActorId == entry.owner.actorId && !reply.committed && reply.code == "food.refund.supervisor.reviewed")
    return foodRefundReviewBody(entry, reply.result)
}

private fun foodRefundReviewBody(entry: StoredClientIntentV1, body: StoredBytes): LocalFoodRefundSupervisorReview {
    val request = LocalFoodRefundSupervisorCodec.decode(entry.request().payload) as? LocalFoodRefundSupervisorRequest.Review ?: unavailable()
    val result = LocalFoodRefundSupervisorCodec.decodeReview(body)
    requireFoodRefundOriginal(LocalFoodRefundReadResult.Original(result.original, result.originalOutcome))
    require(result.original.target.recordingOperationId == request.originalRecordingOperationId &&
        (result.resolution == null) == (result.resolutionOutcome == null))
    result.resolution?.let {
        require(result.originalOutcome is LocalFoodRefundOutcome.Unknown)
        requireFoodRefundResolution(LocalFoodRefundReadResult.Resolution(it, checkNotNull(result.resolutionOutcome)), result.original)
    }
    return result
}

internal fun supplierAuditedReview(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalSupplierCashSupervisorReview {
    requireSupplierEntry(entry)
    require(entry.localAction == LocalAction.SUPPLIER_CASH_SUPERVISOR_REVIEW && reply.operationId == entry.operationId &&
        reply.originalActorId == entry.owner.actorId && !reply.committed && reply.code == "purchasing.supplier_cash_supervisor_reviewed")
    return supplierReviewBody(entry, reply.result)
}

private fun supplierReviewBody(entry: StoredClientIntentV1, body: StoredBytes): LocalSupplierCashSupervisorReview {
    val request = LocalSupplierCashSupervisorCodec.decode(entry.request().payload) as? LocalSupplierCashSupervisorRequest.Review ?: unavailable()
    val result = LocalSupplierCashSupervisorCodec.decodeReview(body)
    val original = result.original
    require(original.target.recordingOperationId == request.originalRecordingOperationId && original.originalActorId == original.recordingActorId &&
        original.originalBodyDigest == null && original.amount.currency == Currency.EGP && original.amount.minor > 0L &&
        sha256(LocalSupplierCashCodec.encode(LocalSupplierCashRequest.RecordOriginal(original.target, result.originalOutcome)).copyBytes()) == original.bodyDigest &&
        (result.resolution == null) == (result.resolutionOutcome == null))
    fun application(fact: LocalSupplierCashSupervisorFact, outcome: LocalSupplierCashOutcome) {
        require((fact.applicationOperationId == null) == (fact.appliedAction == null))
        fact.applicationOperationId?.let { require(it !in setOf(fact.target.intentOperationId, fact.target.recordingOperationId, fact.target.resolutionOperationId)) }
        fact.appliedAction?.let { action -> require(action == when (outcome) {
            is LocalSupplierCashOutcome.Unknown -> LocalSupplierCashAction.UNKNOWN
            is LocalSupplierCashOutcome.NotPaid -> LocalSupplierCashAction.NOT_PAID
            is LocalSupplierCashOutcome.CashPaid -> { require(outcome.supportedAmountOrNull() == fact.amount); LocalSupplierCashAction.CASH_PAID }
            is LocalSupplierCashOutcome.ExternalAttested -> unavailable()
        }) }
    }
    application(original, result.originalOutcome)
    result.resolution?.let { resolution ->
        val outcome = checkNotNull(result.resolutionOutcome)
        require(result.originalOutcome is LocalSupplierCashOutcome.Unknown && outcome !is LocalSupplierCashOutcome.Unknown &&
            resolution.target == original.target && resolution.originalActorId == original.originalActorId && resolution.amount == original.amount &&
            resolution.originalBodyDigest == original.bodyDigest && sha256(LocalSupplierCashCodec.encode(LocalSupplierCashRequest.RecordResolution(
                resolution.target, original.bodyDigest, resolution.recordingActorId, outcome)).copyBytes()) == resolution.bodyDigest)
        application(resolution, outcome)
        if (resolution.applicationOperationId != null) require(original.appliedAction == LocalSupplierCashAction.UNKNOWN)
    }
    return result
}

internal fun financialSupervisorReviewProgress(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalRetailPhysicalProgress {
    require(reply.operationId == entry.operationId && reply.originalActorId == entry.owner.actorId && !reply.committed)
    return financialReviewProgress(entry, reply.result, reply.code)
}

private fun financialReviewProgress(entry: StoredClientIntentV1, body: StoredBytes, code: String): LocalRetailPhysicalProgress {
    requirePhysicalEntry(entry)
    val message = text("Observation disclosure was authorized and durably audited. Apply and linked resolution require fresh current authorization.",
        "صُرح بكشف الإفادة وسُجل تدقيقها بشكل دائم. يتطلب التطبيق والتسوية المرتبطة تصريحًا حاليًا جديدًا.")
    return when (entry.localAction) {
        LocalAction.FOOD_REFUND_SUPERVISOR_REVIEW -> {
            if (code != "food.refund.supervisor.reviewed") {
                val value = LocalFoodRefundCodec.result(body) as? LocalFoodRefundResult.Unavailable ?: unavailable()
                require(value.code == code)
                return LocalRetailPhysicalProgress(entry.operationId, LocalRetailDurableState.REJECTED,
                    text("Review durably refused: $code", "رُفضت المراجعة بشكل دائم: $code"), subject = LocalPaymentSubject.FOOD,
                    financialKind = LocalRetailFinancialKind.REFUND)
            }
            val review = foodRefundReviewBody(entry, body); val original = review.original
            LocalRetailPhysicalProgress(entry.operationId, LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED, message,
                original.target.recordingOperationId, original.target.resolutionOperationId, LocalRetailObservationPhase.ORIGINAL,
                originalUnknown = review.originalOutcome is LocalFoodRefundOutcome.Unknown,
                reviewDisplay = foodRefundDisplay(original.target, original.amount),
                originalApplied = original.applicationState == LocalFoodRefundApplicationState.APPLIED,
                resolutionRecorded = review.resolution != null, originalActorId = original.originalActorId,
                recordingActorId = review.resolution?.recordingActorId ?: original.recordingActorId,
                originalRecordingOperationId = original.target.recordingOperationId, subject = LocalPaymentSubject.FOOD,
                financialKind = LocalRetailFinancialKind.REFUND)
        }
        LocalAction.SUPPLIER_CASH_SUPERVISOR_REVIEW -> {
            if (code != "purchasing.supplier_cash_supervisor_reviewed") {
                val value = LocalSupplierCashCodec.result(body) as? LocalSupplierCashResult.Unavailable ?: unavailable()
                require(value.code == code)
                return LocalRetailPhysicalProgress(entry.operationId, LocalRetailDurableState.REJECTED,
                    text("Review durably refused: $code", "رُفضت المراجعة بشكل دائم: $code"), subject = LocalPaymentSubject.SUPPLIER,
                    financialKind = LocalRetailFinancialKind.SUPPLIER_CASH)
            }
            val review = supplierReviewBody(entry, body); val original = review.original
            LocalRetailPhysicalProgress(entry.operationId, LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED, message,
                original.target.recordingOperationId, original.target.resolutionOperationId, LocalRetailObservationPhase.ORIGINAL,
                originalUnknown = review.originalOutcome is LocalSupplierCashOutcome.Unknown,
                reviewDisplay = supplierDisplay(original.target, original.amount), originalApplied = original.applicationOperationId != null,
                resolutionRecorded = review.resolution != null, originalActorId = original.originalActorId,
                recordingActorId = review.resolution?.recordingActorId ?: original.recordingActorId,
                originalRecordingOperationId = original.target.recordingOperationId, subject = LocalPaymentSubject.SUPPLIER,
                financialKind = LocalRetailFinancialKind.SUPPLIER_CASH)
        }
        else -> unavailable()
    }
}

internal fun financialSupervisorReviewDecision(entry: StoredClientIntentV1, result: StoredKernelResultV1): LocalRecordedDecision {
    require(result.operationId == entry.operationId && result.originalActorId == entry.owner.actorId && !result.committed)
    val progress = financialReviewProgress(entry, decodeHex(result.payloadHex, 65_536).useWiped { StoredBytes(it) }, result.code)
    return if (progress.state == LocalRetailDurableState.REJECTED) LocalRecordedDecision.REJECTED else LocalRecordedDecision.RECORDED_ONLY
}
