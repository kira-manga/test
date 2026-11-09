package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.sales.store.*

/** Called inside the common definite-result renderer's guarded details parser. */
internal fun foodRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalFoodOperationDetails {
    if (entry.localAction == LocalAction.FOOD_CREATE_DRAFT) return foodDraftRecorded(entry, reply)
    val request = savedFoodPreparation(entry.request())
    if (entry.localAction != foodRequestAction(request)) unavailable()
    val result = LocalFoodCodecV1.result(reply.result)
    val wrapper = if (entry.request().command == LocalFoodStockCodecV2.COMMAND)
        LocalFoodStockCodecV2.decode(entry.request().payload) else null
    val quotedAdd = wrapper is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd
    val guardedAdd = wrapper is LocalFoodStockRequestV2.ActivityProtectedAdd || quotedAdd
    if (guardedAdd && (if (reply.committed)
            reply.code != "committed" || result.code != "food.order.added" ||
                result.portions.any { it.status != com.ukkera.pos.sales.FoodPreparationStatus.NOT_SENT || it.issueId != null }
        else result.code != reply.code)) unavailable()
    if (quotedAdd && reply.committed && (entry.formVersion != 3 || request !is LocalFoodRequestV1.Add ||
            result.orderRevision != request.expectedOrderRevision.next() ||
            result.portions.any { it.revision != com.ukkera.pos.types.Revision(0) })) unavailable()
    val orderId = foodRequestOrderId(request)
    if (result.orderId != null && result.orderId != orderId) unavailable()
    if (reply.committed && (result.orderId != orderId || result.orderRevision == null)) unavailable()
    val expectedPortions = when (request) {
        is LocalFoodRequestV1.Create -> request.portions.map { it.portionId }
        is LocalFoodRequestV1.Add -> request.portions.map { it.portionId }
        is LocalFoodRequestV1.Send -> request.portionIds
        is LocalFoodRequestV1.Station -> listOf(request.portionId)
        is LocalFoodRequestV1.Serve -> listOf(request.portionId)
        is LocalFoodRequestV1.Cancel -> listOf(request.portionId)
        is LocalFoodRequestV1.DiscardStarted -> listOf(request.portionId)
        else -> emptyList()
    }
    if (reply.committed && result.portions.map { it.portionId } != expectedPortions) unavailable()
    val followUp = if (!reply.committed) null else when (request) {
        // Completed/cancelled tasks may no longer be indexed. Re-read the exact station, not
        // an inferred owner page or a made-up task revision. Follow-up occurs only AFTER ack.
        is LocalFoodRequestV1.Station -> LocalFoodUiQuery.StationTasks(request.stationId)
        is LocalFoodRequestV1.DiscardStarted -> LocalFoodUiQuery.StationTasks(request.stationId)
        else -> LocalFoodUiQuery.Check(orderId)
    }
    return LocalFoodOperationDetails(result.code, result.orderId, result.orderRevision,
        result.portions.map { LocalFoodRecordedPortion(it.portionId, it.revision,
            LocalFoodPreparation.valueOf(it.status.name), it.issueId) }, followUp)
}
