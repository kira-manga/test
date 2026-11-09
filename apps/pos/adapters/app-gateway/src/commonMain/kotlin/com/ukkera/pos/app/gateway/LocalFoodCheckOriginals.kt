package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.Revision

/** Exact original decoding only; no current menu, quotation discovery or order is read. */
internal fun foodCheckRequest(action: LocalAction, request: MutationRequest): LocalFoodStockRequestV2 {
    if (action !in foodCheckActions || request.command != LocalFoodStockCodecV2.COMMAND || request.actionApprovalId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return try {
        val original = LocalFoodStockCodecV2.decode(request.payload)
        require(LocalFoodStockCodecV2.encode(original) == request.payload)
        when (original) {
            is LocalFoodStockRequestV2.ActivityProtected ->
                if (action != LocalAction.FOOD_CREATE_DRAFT) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd ->
                if (action != LocalAction.FOOD_ADD) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
        original
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}

internal fun savedFoodCheckRequest(entry: StoredClientIntentV1): LocalFoodStockRequestV2 {
    if (entry.actionApproval != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val original = foodCheckRequest(entry.localAction, entry.request())
    val formVersion = when (original) {
        is LocalFoodStockRequestV2.ActivityProtected -> 2
        is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> 3
        else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    if (entry.formVersion != formVersion) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    try {
        entry.validate(entry.owner)
        entry.recorded?.let { retained ->
            require(retained.sequence.value > 0)
            val payload = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            foodCheckOutcome(original, retained.committed, retained.code, payload)
        }
    } catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    return original
}

/** Typed terminal qualification precedes durable remembrance and matching application ACK. */
internal fun foodCheckRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalFoodResultV1 {
    val original = savedFoodCheckRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId ||
        reply.physicalStart != null || reply.sequence.value == 0L)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return foodCheckOutcome(original, reply.committed, reply.code, reply.result)
}

private fun foodCheckOutcome(original: LocalFoodStockRequestV2, committed: Boolean,
    code: String, payload: StoredBytes): LocalFoodResultV1 {
    // The existing public result decoder requires the exact canonical representation.
    val result = try { LocalFoodCodecV1.result(payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    // FoodStockWrites.food creates an absent ORDER at storage revision1, or advances
    // the genuine existing ORDER generation after its exact expected-generation check.
    // Initial quoted preparation is revision0; neither invariant is inferred from UI metadata.
    val valid = when (original) {
        is LocalFoodStockRequestV2.ActivityProtected -> if (committed)
            code == "committed" && result.code == LocalFoodDraftProfileV1.CREATED &&
                result.orderId == original.request.orderId && result.orderRevision == Revision(1) && result.portions.isEmpty()
        else foodCheckEmptyRefusal(result, code, "food.stock.rule.duplicate_source_allocation")
        is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> if (committed)
            code == "committed" && result.code == "food.order.added" && result.orderId == original.request.orderId &&
                original.request.expectedOrderRevision.value in 1 until Long.MAX_VALUE &&
                result.orderRevision == original.request.expectedOrderRevision.next() &&
                result.portions.map { it.portionId } == original.request.portions.map { it.portionId } &&
                result.portions.all { it.revision == Revision(0) && it.status == FoodPreparationStatus.NOT_SENT && it.issueId == null }
        else foodCheckEmptyRefusal(result, code, "food.stock.rule.revision_conflict")
        else -> false
    }
    if (!valid) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return result
}

/** These two shapes do not themselves supply native admission or original-source qualification. */
private fun foodCheckEmptyRefusal(result: LocalFoodResultV1, code: String, admittedCode: String): Boolean =
    code == admittedCode && result.code == code && result.orderId == null && result.orderRevision == null && result.portions.isEmpty()

/** Only the saved target supplies a continuation hint. The Master still authorizes dispatch. */
internal fun foodCheckContinuationHint(access: GatewayAccess, entry: StoredClientIntentV1): Boolean =
    when (val original = savedFoodCheckRequest(entry)) {
        is LocalFoodStockRequestV2.ActivityProtected -> access.allow(entry.localAction, original.request.orderId)
        is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> access.allow(entry.localAction, original.request.orderId)
        else -> false
    }

internal fun foodCheckRecordedUi(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalOperationResult.Recorded {
    val result = foodCheckRecorded(entry, reply)
    val detail = LocalFoodOperationDetails(result.code, result.orderId, result.orderRevision,
        result.portions.map { LocalFoodRecordedPortion(it.portionId, it.revision,
            LocalFoodPreparation.valueOf(it.status.name), it.issueId) },
        if (reply.committed) result.orderId?.let { LocalFoodUiQuery.Check(it) } else null)
    return LocalOperationResult.Recorded(entry.operationId,
        if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED,
        text("Food check result recorded: ${result.code}. This operation starts no stock, kitchen work or payment.",
            "سُجلت نتيجة طلب الطعام: ${result.code}. هذه العملية لا تبدأ حركة مخزون أو عمل مطبخ أو دفع."),
        replayed = reply.replayed, food = detail)
}
