package com.ukkera.pos.sales

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** Commercial/collection state is deliberately NOT inferred from these preparation facts. */
@Serializable
public enum class FoodPreparationStatus { NOT_SENT, QUEUED, PREPARING, READY, CANCEL_PENDING, CANCELLED_BEFORE_START, WASTE_AFTER_START }
@Serializable
public enum class FoodStationStatus { QUEUED, STARTED, READY, CANCEL_CONFIRMED, CANCELLED }
@Serializable
public enum class FoodFulfillmentStatus { NOT_HANDED_OVER, SERVED, PICKED_UP }
@Serializable
public enum class FoodStationDeliveryMode { DISPLAY, PAPER }
@Serializable
public enum class FoodIngredientReturnDisposition { RELEASED, QUARANTINE }

/** A master-acknowledged station transition; delivery/printing never changes this state. */
@Serializable
public data class FoodStationTask(
    public val taskId: DomainId,
    public val stationId: DomainId,
    public val deliveryMode: FoodStationDeliveryMode,
    public val status: FoodStationStatus,
    public val startedAt: UtcInstant? = null,
    public val readyAt: UtcInstant? = null,
    public val cancellationConfirmationId: DomainId? = null,
    public val cancellationConfirmedBy: DomainId? = null,
    public val cancellationConfirmedAt: UtcInstant? = null,
    public val returnDisposition: FoodIngredientReturnDisposition? = null,
) {
    init {
        require((cancellationConfirmationId == null) == (cancellationConfirmedBy == null) &&
            (cancellationConfirmationId == null) == (cancellationConfirmedAt == null) &&
            (cancellationConfirmationId == null) == (returnDisposition == null))
        require(readyAt == null || startedAt != null)
        require(status != FoodStationStatus.STARTED || startedAt != null)
        require(status != FoodStationStatus.READY || readyAt != null)
        require(status != FoodStationStatus.CANCEL_CONFIRMED || (startedAt == null && cancellationConfirmationId != null))
        require(cancellationConfirmationId == null || startedAt == null)
        when (status) {
            FoodStationStatus.QUEUED -> require(startedAt == null && readyAt == null && cancellationConfirmationId == null)
            FoodStationStatus.STARTED -> require(readyAt == null && cancellationConfirmationId == null)
            FoodStationStatus.READY -> require(cancellationConfirmationId == null)
            FoodStationStatus.CANCEL_CONFIRMED -> require(readyAt == null)
            FoodStationStatus.CANCELLED -> Unit // Retain actual start/ready or unstarted-confirmation history.
        }
    }
}

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
public data class FoodPreparationState(
    public val portionId: DomainId,
    public val revision: Revision,
    public val status: FoodPreparationStatus,
    public val fulfillment: FoodFulfillmentStatus = FoodFulfillmentStatus.NOT_HANDED_OVER,
    public val tasks: List<FoodStationTask> = emptyList(),
    public val reservationId: DomainId? = null,
    public val issueId: DomainId? = null,
    public val issueRevision: Revision? = null,
    public val sentAt: UtcInstant? = null,
    public val cancellationReasonId: DomainId? = null,
    public val cancellationRequestedAt: UtcInstant? = null,
    public val servedAt: UtcInstant? = null,
    /** Additive, omitted on old records; a paid takeaway pickup is not dine-in service. */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    public val pickedUpAt: UtcInstant? = null,
) {
    init {
        require(tasks.size <= 4 && tasks.map { it.taskId }.distinct().size == tasks.size &&
            tasks.map { it.stationId }.distinct().size == tasks.size)
        require((issueId == null) == (reservationId == null) && (issueId == null) == (issueRevision == null) &&
            (issueId == null) == (sentAt == null))
        require(if (issueId == null) tasks.isEmpty() && status in setOf(FoodPreparationStatus.NOT_SENT, FoodPreparationStatus.CANCELLED_BEFORE_START)
            else tasks.isNotEmpty() && status != FoodPreparationStatus.NOT_SENT)
        require((cancellationReasonId == null) == (cancellationRequestedAt == null))
        require((fulfillment == FoodFulfillmentStatus.SERVED) == (servedAt != null))
        require((fulfillment == FoodFulfillmentStatus.PICKED_UP) == (pickedUpAt != null))
        require(fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER || status == FoodPreparationStatus.READY)
        require(status != FoodPreparationStatus.READY || tasks.all { it.status == FoodStationStatus.READY })
        require(status != FoodPreparationStatus.QUEUED || tasks.all { it.status == FoodStationStatus.QUEUED })
        require(status != FoodPreparationStatus.PREPARING || tasks.any { it.startedAt != null } &&
            tasks.any { it.status != FoodStationStatus.READY })
        require((status in setOf(FoodPreparationStatus.CANCEL_PENDING, FoodPreparationStatus.CANCELLED_BEFORE_START,
            FoodPreparationStatus.WASTE_AFTER_START)) == (cancellationReasonId != null))
        require(status != FoodPreparationStatus.WASTE_AFTER_START || tasks.any { it.startedAt != null } &&
            tasks.all { it.status == FoodStationStatus.CANCELLED })
        require(status != FoodPreparationStatus.CANCELLED_BEFORE_START || tasks.all {
            it.startedAt == null && it.status == FoodStationStatus.CANCEL_CONFIRMED
        })
        if (issueId != null) require(checkNotNull(issueRevision).value == 1L + tasks.count { it.startedAt != null } +
            if (status in setOf(FoodPreparationStatus.CANCELLED_BEFORE_START, FoodPreparationStatus.WASTE_AFTER_START)) 1 else 0)
    }
    public val started: Boolean get() = tasks.any { it.startedAt != null }
}

/** Pure quantity-identity rules. The adapter supplies current authorization and original inventory proof atomically. */
public object FoodPreparation {
    public fun sent(before: FoodPreparationState, expected: Revision, reservationId: DomainId, issueId: DomainId,
        tasks: List<FoodStationTask>, at: UtcInstant): FoodPreparationState {
        current(before, expected)
        requireSales(before.status == FoodPreparationStatus.NOT_SENT && tasks.isNotEmpty() &&
            tasks.all { it.status == FoodStationStatus.QUEUED }, SalesRule.INVALID_STATE)
        return before.copy(revision = before.revision.next(), status = FoodPreparationStatus.QUEUED,
            tasks = tasks.toList(), reservationId = reservationId, issueId = issueId, issueRevision = Revision(1), sentAt = at)
    }

    public fun start(before: FoodPreparationState, expected: Revision, stationId: DomainId, at: UtcInstant): FoodPreparationState {
        current(before, expected)
        // A cancellation request is not kitchen confirmation. Start and final confirmation
        // compete on the same revision; actual feedback that work began cannot be erased.
        requireSales(before.status in setOf(FoodPreparationStatus.QUEUED, FoodPreparationStatus.PREPARING,
            FoodPreparationStatus.CANCEL_PENDING), SalesRule.INVALID_STATE)
        val task = station(before, stationId)
        requireSales(task.status == FoodStationStatus.QUEUED, SalesRule.INVALID_STATE)
        val tasks = before.tasks.map { if (it.stationId == stationId) it.copy(status = FoodStationStatus.STARTED, startedAt = at) else it }
        return before.copy(revision = before.revision.next(), tasks = tasks,
            issueRevision = checkNotNull(before.issueRevision).next(),
            status = if (before.status == FoodPreparationStatus.CANCEL_PENDING) before.status else FoodPreparationStatus.PREPARING)
    }

    public fun ready(before: FoodPreparationState, expected: Revision, stationId: DomainId, at: UtcInstant): FoodPreparationState {
        current(before, expected)
        requireSales(before.status == FoodPreparationStatus.PREPARING, SalesRule.INVALID_STATE)
        val task = station(before, stationId)
        requireSales(task.status == FoodStationStatus.STARTED, SalesRule.INVALID_STATE)
        val tasks = before.tasks.map { if (it.stationId == stationId) it.copy(status = FoodStationStatus.READY, readyAt = at) else it }
        return before.copy(revision = before.revision.next(), tasks = tasks,
            status = if (tasks.all { it.status == FoodStationStatus.READY }) FoodPreparationStatus.READY else FoodPreparationStatus.PREPARING)
    }

    public fun serve(before: FoodPreparationState, expected: Revision, at: UtcInstant): FoodPreparationState {
        current(before, expected)
        requireSales(before.status == FoodPreparationStatus.READY && before.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER,
            SalesRule.INVALID_STATE)
        return before.copy(revision = before.revision.next(), fulfillment = FoodFulfillmentStatus.SERVED, servedAt = at)
    }

    /** This proposal alone is not proof of coverage; the adapter requires the genuine paid food document. */
    public fun pickUp(before: FoodPreparationState, expected: Revision, at: UtcInstant): FoodPreparationState {
        current(before, expected)
        requireSales(before.status == FoodPreparationStatus.READY && before.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER,
            SalesRule.INVALID_STATE)
        return before.copy(revision = before.revision.next(), fulfillment = FoodFulfillmentStatus.PICKED_UP, pickedUpAt = at)
    }

    public fun requestCancellation(before: FoodPreparationState, expected: Revision, reasonId: DomainId, at: UtcInstant): FoodPreparationState {
        current(before, expected)
        requireSales(before.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER && before.status in setOf(
            FoodPreparationStatus.NOT_SENT, FoodPreparationStatus.QUEUED, FoodPreparationStatus.PREPARING, FoodPreparationStatus.READY), SalesRule.INVALID_STATE)
        return before.copy(revision = before.revision.next(),
            status = if (before.status == FoodPreparationStatus.NOT_SENT) FoodPreparationStatus.CANCELLED_BEFORE_START else FoodPreparationStatus.CANCEL_PENDING,
            cancellationReasonId = reasonId, cancellationRequestedAt = at)
    }

    public fun confirmUnstarted(before: FoodPreparationState, expected: Revision, stationId: DomainId,
        confirmationId: DomainId, actorId: DomainId, disposition: FoodIngredientReturnDisposition, at: UtcInstant): FoodPreparationState {
        current(before, expected)
        requireSales(before.status == FoodPreparationStatus.CANCEL_PENDING && !before.started, SalesRule.INVALID_STATE)
        requireSales(station(before, stationId).status == FoodStationStatus.QUEUED, SalesRule.INVALID_STATE)
        val tasks = before.tasks.map { if (it.stationId == stationId) it.copy(status = FoodStationStatus.CANCEL_CONFIRMED,
            cancellationConfirmationId = confirmationId, cancellationConfirmedBy = actorId,
            cancellationConfirmedAt = at, returnDisposition = disposition) else it }
        val complete = tasks.all { it.status == FoodStationStatus.CANCEL_CONFIRMED }
        return before.copy(revision = before.revision.next(), tasks = tasks,
            status = if (complete) FoodPreparationStatus.CANCELLED_BEFORE_START else before.status,
            issueRevision = if (complete) checkNotNull(before.issueRevision).next() else before.issueRevision)
    }

    public fun discardStarted(before: FoodPreparationState, expected: Revision): FoodPreparationState {
        current(before, expected)
        requireSales(before.status == FoodPreparationStatus.CANCEL_PENDING && before.started, SalesRule.INVALID_STATE)
        return before.copy(revision = before.revision.next(), status = FoodPreparationStatus.WASTE_AFTER_START,
            tasks = before.tasks.map { it.copy(status = FoodStationStatus.CANCELLED) }, issueRevision = checkNotNull(before.issueRevision).next())
    }

    private fun current(before: FoodPreparationState, expected: Revision) { requireSales(before.revision == expected, SalesRule.REVISION_CONFLICT) }
    private fun station(before: FoodPreparationState, id: DomainId): FoodStationTask =
        before.tasks.singleOrNull { it.stationId == id } ?: throw SalesRuleViolation(SalesRule.PERMISSION_DENIED)
}
