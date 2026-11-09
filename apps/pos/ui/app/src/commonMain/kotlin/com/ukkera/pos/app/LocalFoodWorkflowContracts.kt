package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.text.CharacterCodingException

/** Selectors only. No route or query contains an order snapshot, grant, price or financial receipt. */
sealed interface LocalFoodUiQuery {
    data class MyChecks(val after: DomainId? = null) : LocalFoodUiQuery
    data class Check(val orderId: DomainId, val afterPortionId: DomainId? = null) : LocalFoodUiQuery
    data class Menu(val after: DomainId? = null, val orderId: DomainId? = null,
        val expectedOrderStorageRevision: Revision? = null) : LocalFoodUiQuery {
        init { require((orderId == null) == (expectedOrderStorageRevision == null)) }
    }
    data class ConfiguredStations(val after: DomainId? = null) : LocalFoodUiQuery
    data class StationLabel(val stationId: DomainId) : LocalFoodUiQuery
    data class StationTasks(val stationId: DomainId, val afterTaskId: DomainId? = null) : LocalFoodUiQuery
    data class Task(val stationId: DomainId, val taskId: DomainId) : LocalFoodUiQuery
    data class Recipients(val orderId: DomainId, val expectedOrderStorageRevision: Revision, val after: DomainId? = null) : LocalFoodUiQuery
    data class Incoming(val cursor: LocalFoodUiHandoffCursor? = null) : LocalFoodUiQuery
    data class IncomingKnownOrder(val orderId: DomainId, val handoffId: DomainId? = null,
        val expectedOrderStorageRevision: Revision? = null) : LocalFoodUiQuery
    data class Collection(val collectionId: DomainId) : LocalFoodUiQuery
    data class Receipt(val saleId: DomainId) : LocalFoodUiQuery
    /** Read-only original SQL lineage. None of these selectors can create a cash instruction. */
    data class PaymentOriginal(val recordingOperationId: OperationId) : LocalFoodUiQuery
    data class PaymentAttempt(val orderId: DomainId, val collectionId: DomainId, val sessionId: DomainId,
        val attemptId: DomainId, val intentOperationId: OperationId) : LocalFoodUiQuery
    data class OwnPendingPayments(val after: DomainId? = null) : LocalFoodUiQuery
    data class Refund(val refundId: DomainId) : LocalFoodUiQuery
    data class Claim(val claimId: DomainId) : LocalFoodUiQuery
    data class OwnClaims(val after: DomainId? = null) : LocalFoodUiQuery
    data class TableAssignment(val orderId: DomainId, val expectedOrderStorageRevision: Revision) : LocalFoodUiQuery
    data class FloorDirectory(val after: DomainId? = null, val assignment: LocalFoodUiTableTarget? = null) : LocalFoodUiQuery
    data class FloorTables(val floorId: DomainId, val after: DomainId? = null,
        val assignment: LocalFoodUiTableTarget? = null) : LocalFoodUiQuery
    data class TableDefinition(val tableId: DomainId, val assignment: LocalFoodUiTableTarget? = null) : LocalFoodUiQuery
}

/** Advisory exact read coordinates, never a snapshot or authority to edit a check. */
data class LocalFoodUiTableTarget(val orderId: DomainId, val expectedOrderStorageRevision: Revision,
    val expectedAssignmentStorageRevision: Revision?) {
    init { require(expectedOrderStorageRevision.value >= 1 &&
        (expectedAssignmentStorageRevision == null || expectedAssignmentStorageRevision.value >= 1)) }
}

fun LocalFoodUiQuery.tableAssignmentTarget(): LocalFoodUiTableTarget? = when (this) {
    is LocalFoodUiQuery.FloorDirectory -> assignment
    is LocalFoodUiQuery.FloorTables -> assignment
    is LocalFoodUiQuery.TableDefinition -> assignment
    else -> null
}

/** Presentation locations only. Each existing master service retains its own authorization. */
fun LocalFoodUiQuery.canAppearIn(target: LocalWorkflow): Boolean = when (target) {
    LocalWorkflow.FOOD -> true
    LocalWorkflow.FULFILLMENT -> this is LocalFoodUiQuery.MyChecks || this is LocalFoodUiQuery.Check ||
        this is LocalFoodUiQuery.Collection
    LocalWorkflow.KITCHEN -> this is LocalFoodUiQuery.ConfiguredStations || this is LocalFoodUiQuery.StationLabel ||
        this is LocalFoodUiQuery.StationTasks || this is LocalFoodUiQuery.Task
    else -> false
}

/** A fresh selector, never a reused private page or a permission to discover station names. */
fun LocalWorkflow.defaultFoodQuery(): LocalFoodUiQuery =
    if (this == LocalWorkflow.KITCHEN) LocalFoodUiQuery.ConfiguredStations() else LocalFoodUiQuery.MyChecks()

/** Opaque actor-scoped scan coordinate. Never an employee ID, permission or responsibility proof. */
data class LocalFoodUiHandoffCursor(val phase: String, val after: String) {
    init { require(phase in setOf("INDEXED", "LEGACY") && after.length == 64 && after.all { it in '0'..'9' || it in 'a'..'f' }) }
}
data class LocalFoodUiRecipient(val actorId: DomainId, val label: String) {
    init { require(label.isNotBlank() && label.length <= 256 && label.none { it.code < 0x20 || it.code == 0x7f }) }
}
data class LocalFoodUiIncomingOffer(val check: LocalFoodUiCheck, val offeredAt: UtcInstant,
    val financial: LocalFoodUiFinancial? = null) {
    init { require(check.handoffId != null && check.recipientId != null && check.ownerId != check.recipientId) }
}

enum class LocalFoodUnavailable { TARGET_UNAVAILABLE, CAPACITY_REQUIRED, NOT_PRECOLLECTION, PAYMENT_UNRESOLVED }
fun foodUnavailableReason(problem: LocalServiceProblem): LocalFoodUnavailable? = when (problem) {
    LocalServiceProblem.FOOD_TARGET_UNAVAILABLE -> LocalFoodUnavailable.TARGET_UNAVAILABLE
    LocalServiceProblem.FOOD_CAPACITY_REQUIRED -> LocalFoodUnavailable.CAPACITY_REQUIRED
    LocalServiceProblem.FOOD_NOT_PRECOLLECTION -> LocalFoodUnavailable.NOT_PRECOLLECTION
    LocalServiceProblem.FOOD_PAYMENT_UNRESOLVED -> LocalFoodUnavailable.PAYMENT_UNRESOLVED
    else -> null
}
enum class LocalFoodPreparation { NOT_SENT, QUEUED, PREPARING, READY, CANCEL_PENDING, CANCELLED_BEFORE_START, WASTE_AFTER_START }
enum class LocalFoodFulfillment { NOT_HANDED_OVER, SERVED, PICKED_UP }
enum class LocalFoodTaskState { QUEUED, STARTED, READY, CANCEL_CONFIRMED, CANCELLED }
enum class LocalFoodDeliveryMode { DISPLAY, PAPER }

/** Reply-selected presentation validation only, never unpaid evidence or Master authority. */
enum class LocalFoodUiCurrentUnpaidPayableProfile { NEVER_SENT_ONLY, ORDINARY_UNPAID_V3 }

/** Advisory current Master observation, never an execution grant or a payment outcome. */
enum class LocalFoodUiKitchenStatus { UNKNOWN, UNAVAILABLE, NO_UNSENT_WORK }
data class LocalFoodUiKitchenObservation(val orderId: DomainId, val orderStorageRevision: Revision,
    val unsentPortionCount: Int, val status: LocalFoodUiKitchenStatus) {
    init { require(unsentPortionCount in 0..32 &&
        (status != LocalFoodUiKitchenStatus.NO_UNSENT_WORK || unsentPortionCount == 0)) }
}

/** Same-response financial responsibility only, never a drawer grant or immutable receipt replacement. */
data class LocalFoodUiFinancial(val collectionId: DomainId, val collectionDomainRevision: Revision,
    val status: String, val collected: Money, val remaining: Money, val refundDue: Money?,
    val pendingClaimCount: Int, val paymentUnresolved: Boolean) {
    init { require(status in setOf("LOCKED_UNPAID", "PART_PAID", "OUTCOME_UNKNOWN", "COVERED", "FINALIZED_PAID",
        "CANCELLED_REFUND_OBLIGATION") && collected.minor >= 0 && remaining.minor >= 0 &&
        (refundDue == null || refundDue.minor >= 0) && pendingClaimCount in 0..64) }
}

data class LocalFoodUiService(val channel: String, val reference: String, val seatNote: String) {
    init { require(channel in setOf("DINE_IN", "COUNTER", "TAKEAWAY", "DELIVERY") && reference.isNotBlank() &&
        reference.length <= 80 && seatNote.length <= 160 && (reference + seatNote).none { it.code < 0x20 || it.code == 0x7f }) }
}

/** Owner-private delivery input; never part of a service label, station or handoff summary. */
data class LocalFoodUiDeliveryDetails(val recipient: String, val contact: String, val address: String) {
    init {
        require(validFoodDeliveryText(recipient, 160) && validFoodDeliveryText(contact, 160) &&
            validFoodDeliveryText(address, 600))
    }
    override fun toString(): String = "LocalFoodUiDeliveryDetails([REDACTED])"
}

data class LocalFoodUiDeliveryObservation(val orderId: DomainId, val orderStorageRevision: Revision,
    val details: LocalFoodUiDeliveryDetails) {
    init { require(orderStorageRevision.value >= 1) }
    override fun toString(): String = "LocalFoodUiDeliveryObservation([REDACTED])"
}

internal fun validFoodDeliveryText(value: String, maximum: Int): Boolean = try {
    value.isNotBlank() && value.length <= maximum && value.none { it.code < 0x20 || it.code == 0x7f } &&
        value.encodeToByteArray(throwOnInvalidSequence = true).isNotEmpty()
} catch (_: IllegalArgumentException) { false } catch (_: CharacterCodingException) { false }
/** Storage revision never occupies LocalRecordReference's domain-only revision field. */
data class LocalFoodUiCheck(val orderId: DomainId, val ownerId: DomainId, val orderStorageRevision: Revision,
    val activity: String, val service: LocalFoodUiService, val portionCount: Int,
    val handoffId: DomainId?, val recipientId: DomainId?, val workClosed: Boolean = false,
    val collectionId: DomainId? = null) {
    init {
        require(activity in setOf("RESTAURANT", "CAFE") && portionCount in 0..32 && (handoffId == null) == (recipientId == null))
        require(portionCount != 0 || handoffId == null && collectionId == null && !workClosed)
    }
}
data class LocalFoodUiPortion(val portionId: DomainId, val description: String, val quantity: Quantity,
    val itemNote: String, val preparationDomainRevision: Revision, val preparation: LocalFoodPreparation,
    val fulfillment: LocalFoodFulfillment, val started: Boolean, val cancellationRequested: Boolean,
    val lineId: DomainId? = null, val seatNote: String = "") {
    val commercialLineId: DomainId get() = lineId ?: portionId
    init { require(description.isNotBlank() && description.length <= 323 && itemNote.length <= 240 && quantity.scaled > 0 &&
        seatNote.length <= 160 && seatNote.none { it.code < 0x20 || it.code == 0x7f }) }
}
data class LocalFoodUiTask(val orderId: DomainId, val orderStorageRevision: Revision, val portionId: DomainId,
    val preparationDomainRevision: Revision, val taskId: DomainId, val stationId: DomainId,
    val description: String, val quantity: Quantity, val itemNote: String, val serviceReference: String,
    val taskState: LocalFoodTaskState, val preparation: LocalFoodPreparation, val deliveryMode: LocalFoodDeliveryMode,
    val seatNote: String = "") {
    init { require(description.isNotBlank() && description.length <= 323 && itemNote.length <= 240 &&
        serviceReference.isNotBlank() && serviceReference.length <= 80 && quantity.scaled > 0 && seatNote.length <= 160 &&
        seatNote.none { it.code < 0x20 || it.code == 0x7f }) }
}

/** A display-only sum of all original preparation members, never an independently repriced row. */
internal data class LocalFoodUiCommercialLine(val lineId: DomainId, val description: String,
    val quantity: Quantity, val itemNote: String, val seatNote: String, val portionCount: Int)

/** Only a complete same-response member set may supply a whole commercial quantity. */
internal fun LocalFoodUiBody.Check.completeCommercialLines(): List<LocalFoodUiCommercialLine>? {
    if (portions.size != check.portionCount) return null
    return try {
        portions.groupBy { it.commercialLineId }.map { (lineId, members) ->
            val first = members.first()
            if (members.none { it.portionId == lineId } || members.any { it.description != first.description ||
                    it.itemNote != first.itemNote || it.seatNote != first.seatNote || it.quantity != first.quantity }) return null
            LocalFoodUiCommercialLine(lineId, first.description,
                members.fold(first.quantity.copy(scaled = 0)) { total, portion -> total + portion.quantity },
                first.itemNote, first.seatNote, members.size)
        }
    } catch (_: IllegalArgumentException) { null } catch (_: ArithmeticException) { null }
}
data class LocalFoodUiChoice(val choiceId: DomainId, val names: LocalText, val modifierIds: List<DomainId>) {
    init { require(modifierIds.size <= 16 && modifierIds.distinct().size == modifierIds.size &&
        names.english.length <= 160 && names.arabic.length <= 160 && (names.english.isNotBlank() || names.arabic.isNotBlank())) }
}
data class LocalFoodUiRecipe(val recipeId: DomainId, val recipeRevision: Revision, val outputSkuId: DomainId,
    val portionQuantity: Quantity, val active: Boolean, val choices: List<LocalFoodUiChoice>) {
    init { require(choices.size in 1..8 && choices.map { it.choiceId }.distinct().size == choices.size && portionQuantity.scaled > 0) }
}
data class LocalFoodUiStation(val stationId: DomainId, val names: LocalText, val active: Boolean,
    val deliveryMode: LocalFoodDeliveryMode, val configurationStorageRevision: Revision) {
    init { require(names.english.length <= 160 && names.arabic.length <= 160 && (names.english.isNotBlank() || names.arabic.isNotBlank())) }
}

/** Display groups reuse exact read-only value rendering; keys are UI keys, not invented domain IDs. */
data class LocalFoodFinancialGroup(val key: String, val title: LocalText, val values: List<LocalRecordValue>) {
    init { require(key.length in 1..192 && values.size <= 32) }
}
data class LocalFoodFinancialLink(val title: LocalText, val query: LocalFoodUiQuery)

fun LocalFoodUiQuery.isFinancial(): Boolean = this is LocalFoodUiQuery.Collection || this is LocalFoodUiQuery.Receipt ||
    this is LocalFoodUiQuery.Refund || this is LocalFoodUiQuery.Claim || this is LocalFoodUiQuery.OwnClaims ||
    this is LocalFoodUiQuery.PaymentOriginal || this is LocalFoodUiQuery.PaymentAttempt || this is LocalFoodUiQuery.OwnPendingPayments

sealed interface LocalFoodUiBody {
    /** One protected assignment response; configuration pages do not refresh these coordinates. */
    data class TableAssignment(val target: LocalFoodUiTableTarget, val floorId: DomainId?, val tableId: DomainId?,
        val workClosed: Boolean, val handoffPending: Boolean, val assignmentAllowed: Boolean) : LocalFoodUiBody {
        init { require((floorId == null) == (tableId == null) &&
            (tableId == null) == (target.expectedAssignmentStorageRevision == null)) }
        val eligible: Boolean get() = !workClosed && !handoffPending
    }
    /** Configuration only: no occupancy, bill identity or ownership is inferred. */
    data class Floors(val floors: List<LocalFoodUiFloor>) : LocalFoodUiBody {
        init { require(floors.size <= 20 && floors.map { it.id }.distinct().size == floors.size) }
    }
    data class Tables(val floor: LocalFoodUiFloor, val tables: List<LocalFoodUiTable>) : LocalFoodUiBody {
        init { require(tables.size <= 20 && tables.map { it.id }.distinct().size == tables.size &&
            tables.all { it.floorId == floor.id && (!it.active || floor.active) }) }
    }
    data class Table(val floor: LocalFoodUiFloor, val table: LocalFoodUiTable) : LocalFoodUiBody {
        init { require(table.floorId == floor.id && (!table.active || floor.active)) }
    }
    /** All groups belong to this ONE authorized server response, not a merged financial/preparation cut. */
    data class Financial(val query: LocalFoodUiQuery, val groups: List<LocalFoodFinancialGroup>,
        val links: List<LocalFoodFinancialLink>,
        /** Genuine response coordinates for the explicitly selected financial-only editor. Not authority. */
        val cashFinancialSource: LocalFoodCashFinancialReadSource? = null) : LocalFoodUiBody {
        init { require(query.isFinancial() && groups.size <= 2048 && groups.map { it.key }.distinct().size == groups.size && links.size <= 96 &&
            (cashFinancialSource == null || cashFinancialSource.query == query)) }
    }
    data class Checks(val checks: List<LocalFoodUiCheck>) : LocalFoodUiBody {
        init { require(checks.size <= 20 && checks.map { it.orderId }.distinct().size == checks.size) }
    }
    data class Check(val check: LocalFoodUiCheck, val portions: List<LocalFoodUiPortion>,
        val originalQuotedTotal: Money, val chargeResolutionRequired: Boolean, val financial: LocalFoodUiFinancial? = null,
        val kitchen: LocalFoodUiKitchenObservation? = null,
        val delivery: LocalFoodUiDeliveryObservation? = null,
        val currentUnpaidPayable: Money? = null,
        /** Installation/current permission hint only; the assignment Master rechecks authority. */
        val tableAssignmentAvailable: Boolean = false,
        val currentUnpaidPayableProfile: LocalFoodUiCurrentUnpaidPayableProfile = LocalFoodUiCurrentUnpaidPayableProfile.NEVER_SENT_ONLY) : LocalFoodUiBody {
        init {
            require(portions.size <= minOf(8, check.portionCount) && portions.map { it.portionId }.distinct().size == portions.size)
            require(check.portionCount != 0 || originalQuotedTotal.currency == com.ukkera.pos.types.Currency.EGP &&
                originalQuotedTotal.minor == 0L && !chargeResolutionRequired && financial == null)
            currentUnpaidPayable?.let { amount ->
                require(amount.currency == originalQuotedTotal.currency && amount.minor >= 0 &&
                    amount.minor <= originalQuotedTotal.minor && !check.workClosed && check.collectionId == null &&
                    financial == null && !chargeResolutionRequired)
                require(portions.all { portion ->
                    val legacyPresentation = !portion.started &&
                        portion.fulfillment == LocalFoodFulfillment.NOT_HANDED_OVER && when (portion.preparation) {
                            LocalFoodPreparation.NOT_SENT -> !portion.cancellationRequested
                            LocalFoodPreparation.CANCELLED_BEFORE_START -> portion.cancellationRequested
                            else -> false
                        }
                    legacyPresentation || currentUnpaidPayableProfile == LocalFoodUiCurrentUnpaidPayableProfile.ORDINARY_UNPAID_V3 &&
                        !portion.cancellationRequested && when (portion.preparation) {
                            LocalFoodPreparation.QUEUED -> !portion.started && portion.fulfillment == LocalFoodFulfillment.NOT_HANDED_OVER
                            LocalFoodPreparation.PREPARING -> portion.started && portion.fulfillment == LocalFoodFulfillment.NOT_HANDED_OVER
                            LocalFoodPreparation.READY -> portion.started &&
                                (portion.fulfillment == LocalFoodFulfillment.NOT_HANDED_OVER ||
                                    portion.fulfillment == LocalFoodFulfillment.SERVED && check.service.channel == "DINE_IN")
                            else -> false
                        }
                })
            }
            require((check.service.channel == "DELIVERY") == (delivery != null))
            delivery?.let { observation ->
                require(observation.orderId == check.orderId && observation.orderStorageRevision == check.orderStorageRevision)
            }
            kitchen?.let { observation ->
                val visibleUnsent = portions.count { it.preparation == LocalFoodPreparation.NOT_SENT }
                require(observation.orderId == check.orderId && observation.orderStorageRevision == check.orderStorageRevision &&
                    observation.unsentPortionCount >= visibleUnsent &&
                    observation.unsentPortionCount <= check.portionCount - (portions.size - visibleUnsent))
            }
        }
        val kitchenUnavailableForUnsentWork: Boolean get() =
            kitchen?.status == LocalFoodUiKitchenStatus.UNAVAILABLE && kitchen.unsentPortionCount > 0
    }
    data class Menu(val catalogRevision: Revision?, val recipes: List<LocalFoodUiRecipe>) : LocalFoodUiBody {
        init { require(recipes.size <= 20 && recipes.map { it.recipeId }.distinct().size == recipes.size) }
    }
    data class Stations(val stations: List<LocalFoodUiStation>) : LocalFoodUiBody {
        init { require(stations.size <= 20 && stations.map { it.stationId }.distinct().size == stations.size) }
    }
    data class Tasks(val tasks: List<LocalFoodUiTask>) : LocalFoodUiBody {
        init { require(tasks.size <= 16 && tasks.map { it.taskId }.distinct().size == tasks.size) }
    }
    data class Recipients(val orderId: DomainId, val orderStorageRevision: Revision,
        val recipients: List<LocalFoodUiRecipient>, val financial: LocalFoodUiFinancial? = null) : LocalFoodUiBody {
        init { require(recipients.size <= 20 && recipients.map { it.actorId }.distinct().size == recipients.size) }
    }
    data class Incoming(val offers: List<LocalFoodUiIncomingOffer>, val cursorExpired: Boolean = false) : LocalFoodUiBody {
        init { require(offers.size <= 8 && offers.map { it.check.handoffId }.distinct().size == offers.size && (!cursorExpired || offers.isEmpty())) }
    }
    /** Full responsibility proof, including exact settled collection expectation when bound. */
    data class Handoff(val check: LocalFoodUiCheck, val portions: List<LocalFoodUiPortion>,
        val originalQuotedTotal: Money, val chargeResolutionRequired: Boolean, val financial: LocalFoodUiFinancial? = null) : LocalFoodUiBody {
        init { require(portions.size == check.portionCount && portions.size <= 32 &&
            portions.map { it.portionId }.distinct().size == portions.size && check.handoffId != null) }
    }
}

/** One real public response cut. Menus, labels and task pages are never merged into a fictional cut. */
data class LocalFoodUiPage(val query: LocalFoodUiQuery, val companyId: DomainId, val branchId: DomainId,
    val actorId: DomainId, val branchRevision: Revision, val observedAt: UtcInstant,
    val body: LocalFoodUiBody, val next: LocalFoodUiQuery? = null) {
    init {
        require(when (query) {
            is LocalFoodUiQuery.TableAssignment -> body is LocalFoodUiBody.TableAssignment &&
                body.target.orderId == query.orderId && body.target.expectedOrderStorageRevision == query.expectedOrderStorageRevision
            is LocalFoodUiQuery.FloorDirectory -> body is LocalFoodUiBody.Floors
            is LocalFoodUiQuery.FloorTables -> body is LocalFoodUiBody.Tables && body.floor.id == query.floorId
            is LocalFoodUiQuery.TableDefinition -> body is LocalFoodUiBody.Table && body.table.id == query.tableId
            is LocalFoodUiQuery.MyChecks -> body is LocalFoodUiBody.Checks && body.checks.all { it.ownerId == actorId }
            is LocalFoodUiQuery.Check -> body is LocalFoodUiBody.Check && body.check.orderId == query.orderId && body.check.ownerId == actorId
            is LocalFoodUiQuery.Menu -> body is LocalFoodUiBody.Menu
            is LocalFoodUiQuery.ConfiguredStations -> body is LocalFoodUiBody.Stations
            is LocalFoodUiQuery.StationLabel -> body is LocalFoodUiBody.Stations && body.stations.size == 1 && body.stations.single().stationId == query.stationId
            is LocalFoodUiQuery.StationTasks -> body is LocalFoodUiBody.Tasks && body.tasks.all { it.stationId == query.stationId }
            is LocalFoodUiQuery.Task -> body is LocalFoodUiBody.Tasks && body.tasks.size == 1 && body.tasks.single().let {
                it.stationId == query.stationId && it.taskId == query.taskId }
            is LocalFoodUiQuery.Recipients -> body is LocalFoodUiBody.Recipients && body.orderId == query.orderId &&
                body.orderStorageRevision == query.expectedOrderStorageRevision && body.recipients.none { it.actorId == actorId }
            is LocalFoodUiQuery.Incoming -> body is LocalFoodUiBody.Incoming && body.offers.all { it.check.recipientId == actorId }
            is LocalFoodUiQuery.IncomingKnownOrder -> body is LocalFoodUiBody.Handoff && body.check.orderId == query.orderId &&
                body.check.recipientId == actorId && (query.handoffId == null || body.check.handoffId == query.handoffId) &&
                (query.expectedOrderStorageRevision == null || body.check.orderStorageRevision == query.expectedOrderStorageRevision)
            is LocalFoodUiQuery.Collection, is LocalFoodUiQuery.Receipt, is LocalFoodUiQuery.Refund,
            is LocalFoodUiQuery.Claim, is LocalFoodUiQuery.OwnClaims, is LocalFoodUiQuery.PaymentOriginal,
            is LocalFoodUiQuery.PaymentAttempt, is LocalFoodUiQuery.OwnPendingPayments -> body is LocalFoodUiBody.Financial && body.query == query
        })
        require(next == null || when (query) {
            is LocalFoodUiQuery.FloorDirectory -> next is LocalFoodUiQuery.FloorDirectory && next.assignment == query.assignment &&
                next.after != null && next.after != query.after
            is LocalFoodUiQuery.FloorTables -> next is LocalFoodUiQuery.FloorTables && next.floorId == query.floorId &&
                next.assignment == query.assignment && next.after != null && next.after != query.after
            is LocalFoodUiQuery.MyChecks -> next is LocalFoodUiQuery.MyChecks && next.after != null && next.after != query.after
            is LocalFoodUiQuery.Check -> next is LocalFoodUiQuery.Check && next.orderId == query.orderId && next.afterPortionId != null && next.afterPortionId != query.afterPortionId
            is LocalFoodUiQuery.Menu -> next is LocalFoodUiQuery.Menu && next.orderId == query.orderId &&
                next.expectedOrderStorageRevision == query.expectedOrderStorageRevision && next.after != null && next.after != query.after
            is LocalFoodUiQuery.ConfiguredStations -> next is LocalFoodUiQuery.ConfiguredStations && next.after != null && next.after != query.after
            is LocalFoodUiQuery.Recipients -> next is LocalFoodUiQuery.Recipients && next.orderId == query.orderId &&
                next.expectedOrderStorageRevision == query.expectedOrderStorageRevision && next.after != null && next.after != query.after
            is LocalFoodUiQuery.Incoming -> next is LocalFoodUiQuery.Incoming && next.cursor != null && next.cursor != query.cursor &&
                body is LocalFoodUiBody.Incoming && !body.cursorExpired
            is LocalFoodUiQuery.StationTasks -> next is LocalFoodUiQuery.StationTasks && next.stationId == query.stationId && next.afterTaskId != null && next.afterTaskId != query.afterTaskId
            is LocalFoodUiQuery.OwnClaims -> next is LocalFoodUiQuery.OwnClaims && next.after != null && next.after != query.after
            is LocalFoodUiQuery.OwnPendingPayments -> next is LocalFoodUiQuery.OwnPendingPayments && next.after != null &&
                (query.after == null || next.after.value > query.after.value)
            else -> false
        })
    }
}

data class LocalFoodRecordedPortion(val portionId: DomainId, val preparationDomainRevision: Revision,
    val preparation: LocalFoodPreparation, val originalIssueId: DomainId?)
data class LocalFoodOperationDetails(val code: String, val orderId: DomainId?, val orderStorageRevision: Revision?,
    val portions: List<LocalFoodRecordedPortion>, val followUp: LocalFoodUiQuery?) {
    init { require(code.isNotBlank() && code.length <= 160 && portions.size <= 8 &&
        portions.map { it.portionId }.distinct().size == portions.size &&
        (orderId == null) == (orderStorageRevision == null) && (orderId != null || portions.isEmpty() && followUp == null)) }
}

internal fun LocalFoodUiPage.snapshotFood(): LocalFoodUiPage = copy(body = when (val value = body) {
    is LocalFoodUiBody.Floors -> value.copy(floors = value.floors.toList())
    is LocalFoodUiBody.Tables -> value.copy(tables = value.tables.toList())
    is LocalFoodUiBody.Table -> value.copy()
    is LocalFoodUiBody.TableAssignment -> value.copy()
    is LocalFoodUiBody.Financial -> value.copy(groups = value.groups.map { it.copy(values = it.values.toList()) }, links = value.links.toList())
    is LocalFoodUiBody.Checks -> value.copy(checks = value.checks.toList())
    is LocalFoodUiBody.Check -> value.copy(portions = value.portions.toList())
    is LocalFoodUiBody.Menu -> value.copy(recipes = value.recipes.map { recipe -> recipe.copy(choices = recipe.choices.map {
        it.copy(modifierIds = it.modifierIds.toList()) }) })
    is LocalFoodUiBody.Stations -> value.copy(stations = value.stations.toList())
    is LocalFoodUiBody.Tasks -> value.copy(tasks = value.tasks.toList())
    is LocalFoodUiBody.Recipients -> value.copy(recipients = value.recipients.toList())
    is LocalFoodUiBody.Incoming -> value.copy(offers = value.offers.toList())
    is LocalFoodUiBody.Handoff -> value.copy(portions = value.portions.toList())
})
