package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.FoodStationTask
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.OperationId

/** Only the actual borrowed Master loan can supply a live DISPLAY consumer. */
public fun CommandReadView.localFoodOwnedDisplayDelivery(): MasterLocalDisplayDeliveryAccess? {
    val borrowed = when (this) {
        is FoodReadView -> handoffBorrowedView()
        is CommerceLoan -> handoffBorrowedView()
        else -> this
    }
    return borrowed.masterLocalDisplayDelivery()
}

/**
 * Rejoin the first Send's exact immutable PREPARE intent, not today's task revision or
 * a newly generated job. Repeated protected reads do not dequeue, acknowledge, start,
 * issue stock or change money. The registered consumer owns the surrounding lifetime.
 * Ordinary legacy readers without that consumer retain their existing projection;
 * they do not establish positive execution support.
 */
internal suspend fun foodRequireDisplayDelivery(view: CommandReadView, order: FoodOrderCut,
    row: FoodRow, task: FoodStationTask) {
    if (task.deliveryMode != FoodStationDeliveryMode.DISPLAY) return
    val delivery = view.localFoodOwnedDisplayDelivery() ?: return
    val bounded = foodReadView(view)
    val current = row.envelope.body as FoodBodyV1.Portion
    val first = current.normalizedIssue?.let { reference ->
        val original = bounded.foodStockProducer(reference.operationId)
        check(original.identity.producer.operationId == reference.operationId &&
            original.identity.producer.originalActorId == reference.actorId &&
            original.identity.producer.semanticDigest == reference.semanticDigest)
        var portion: FoodEnvelopeV1? = null
        var originalOrder: FoodBodyV1.Order? = null
        val jobs = mutableListOf<FoodDisplayJob>()
        original.frames { frame ->
            when (frame) {
                is RetainedCommandFrameV2.Write -> when (frame.value.key) {
                    row.row.key -> {
                        check(portion == null)
                        portion = foodEnvelope(frame.value.payload)
                    }
                    order.order.row.key -> {
                        check(originalOrder == null)
                        originalOrder = foodEnvelope(frame.value.payload).body as FoodBodyV1.Order
                    }
                    else -> Unit
                }
                is RetainedCommandFrameV2.Job -> {
                    foodCapacity(jobs.size < LocalFoodProfileV1.MAX_PORTIONS_PER_COMMAND * 4)
                    check(frame.ordinal == jobs.size.toLong() + 1)
                    jobs += FoodDisplayJob(frame.ordinal.toInt(), frame.value.kind, frame.value.body)
                }
                else -> Unit
            }
        }
        FoodDisplayOriginal(reference.operationId, checkNotNull(originalOrder), checkNotNull(portion), jobs)
    } ?: foodLegacyDisplayOriginal(bounded, row)
    val originalPortion = first.portion.body as FoodBodyV1.Portion
    check(first.portion.scope == view.scope && first.portion.operationId == first.operationId &&
        first.portion.id == row.envelope.id && originalPortion.orderId == current.orderId &&
        first.order.activity == order.value.activity && first.order.service.channel == order.value.service.channel &&
        originalPortion.selection == current.selection && originalPortion.recipe == current.recipe &&
        originalPortion.priceBatchId == current.priceBatchId &&
        originalPortion.preparation.issueId == current.preparation.issueId &&
        originalPortion.preparation.reservationId == current.preparation.reservationId &&
        originalPortion.preparation.sentAt == current.preparation.sentAt &&
        originalPortion.originalIssue == current.originalIssue && originalPortion.normalizedIssue == current.normalizedIssue)
    val originalTask = originalPortion.preparation.tasks.single { it.taskId == task.taskId }
    check(originalTask.stationId == task.stationId && originalTask.deliveryMode == task.deliveryMode)
    val expected = foodDeliveryJobs(first.order, originalPortion, "PREPARE").single {
        val body = localSalesDecode(it.body, FoodDeliveryV1.serializer(), 4_096)
        body.taskId == task.taskId && body.stationId == task.stationId
    }
    val exact = first.jobs.single { it.kind == expected.kind && it.body == expected.body }
    delivery.requireIntent(first.operationId, exact.ordinal, expected.body)
}

private data class FoodDisplayJob(val ordinal: Int, val kind: String, val body: StoredBytes)
private data class FoodDisplayOriginal(val operationId: OperationId, val order: FoodBodyV1.Order,
    val portion: FoodEnvelopeV1, val jobs: List<FoodDisplayJob>)

/** Walk literal, fully validated before-images only; later Start/Ready cannot rekey a Send. */
private suspend fun foodLegacyDisplayOriginal(view: FoodReadView, current: FoodRow): FoodDisplayOriginal {
    var envelope = current.envelope
    val seen = mutableSetOf<OperationId>()
    var bytes = 0L
    repeat(FoodReadLimits.PREDECESSOR_PRODUCERS) {
        check(seen.add(envelope.operationId)) { "food.display_origin_cycle" }
        val original = view.foodProducer(envelope.operationId)
        bytes += foodProducerReadBytes(original)
        foodCapacity(bytes <= FoodReadLimits.PREDECESSOR_BYTES)
        val write = original.aggregateWrites.single { it.key == current.row.key }
        val payload = foodEnvelopeBytes(envelope)
        check(original.scope == view.scope && write.storageGeneration == envelope.revision &&
            write.schema == LocalFoodProfileV1.schema(current.row.key.kind) &&
            write.payload == payload && write.payloadDigest == sha256(payload.copyBytes()))
        val before = when (original.command) {
            LocalFoodProfileV1.COMMAND -> foodEvent(original).beforeIds.map { foodBefore(original, it).envelope }
                .single { it.kind == envelope.kind && it.id == envelope.id }
            LocalFoodFinancialProfileV1.COMMAND -> foodFinancialInputs(original)
                .single { it.key() == current.row.key }.let { foodEnvelope(it.bytes()) }
            else -> error("food.display_origin_profile")
        }
        val portion = envelope.body as FoodBodyV1.Portion
        val prior = before.body as FoodBodyV1.Portion
        check(before.scope == view.scope && before.kind == envelope.kind && before.id == envelope.id &&
            before.revision.next() == envelope.revision && prior.selection == portion.selection &&
            prior.recipe == portion.recipe && prior.orderId == portion.orderId)
        if (prior.preparation.issueId == null) {
            check(portion.preparation.issueId != null && prior.preparation.tasks.isEmpty())
            val order = original.aggregateWrites.single {
                it.key == AggregateKey(LocalFoodProfileV1.ORDER, portion.orderId)
            }.let { foodEnvelope(it.payload).body as FoodBodyV1.Order }
            return FoodDisplayOriginal(original.operationId, order, envelope,
                original.jobs.mapIndexed { index, job -> FoodDisplayJob(index + 1, job.kind, job.body) })
        }
        check(prior.preparation.issueId == portion.preparation.issueId &&
            prior.preparation.sentAt == portion.preparation.sentAt && prior.originalIssue == portion.originalIssue)
        envelope = before
    }
    throw FoodCapacityExceeded()
}
