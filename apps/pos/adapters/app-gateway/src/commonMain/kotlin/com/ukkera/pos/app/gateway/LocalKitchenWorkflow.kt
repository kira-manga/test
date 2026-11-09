package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalFoodStationDirectoryCodecV1
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*

/** Fixed application installation hints; the actual Master separately classifies each body. */
internal val kitchenCommands: Set<CommandKey> = setOf(LocalFoodProfileV1.COMMAND, LocalFoodStockCodecV2.COMMAND)
internal val kitchenReads: Set<ReadKey> = setOf(LocalFoodStationDirectoryCodecV1.QUERY,
    LocalFoodProfileV1.QUERY, LocalStockRelationalQueriesV2.STATION_PROFILE)
internal val kitchenStationActions: Set<LocalAction> = setOf(LocalAction.FOOD_STATION_START, LocalAction.FOOD_STATION_READY)

internal fun requireKitchenWorkflow(query: LocalWorkflowQuery) {
    if (query.workflow != LocalWorkflow.KITCHEN || query.food?.canAppearIn(LocalWorkflow.KITCHEN) != true ||
        query.search.isNotEmpty() || query.reference != null || query.cursor != null || query.pricePreview != null ||
        query.branchReport != null || query.purchasing != null || query.returns != null || query.cash != null ||
        query.inventory != null || query.openingAllocations != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

internal fun requireKitchenIntent(intent: LocalCommandIntent) {
    if (intent.action !in kitchenStationActions || intent.formVersion != 1 || intent.reference != null ||
        intent.arrivalEvidence != null || intent.lineField != null || intent.lines.isNotEmpty())
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

/** The shared FOOD query key never grants owner, order or handoff selectors. */
internal fun requireKitchenRead(request: ReadRequest) {
    try {
        when (request.query) {
            LocalFoodStationDirectoryCodecV1.QUERY -> {
                val query = LocalFoodStationDirectoryCodecV1.decode(request.payload)
                if (LocalFoodStationDirectoryCodecV1.encode(query) != request.payload) invalid()
            }
            LocalFoodProfileV1.QUERY -> {
                val query = LocalFoodReadCodecV1.decode(request.payload)
                if (query !is LocalFoodQueryV1.Station && query !is LocalFoodQueryV1.Task)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalFoodReadCodecV1.encode(query) != request.payload) invalid()
            }
            LocalStockRelationalQueriesV2.STATION_PROFILE -> {
                val station = LocalStockRelationalQueriesV2.stationProfileTarget(request.payload)
                if (LocalStockRelationalQueriesV2.stationProfile(station).payload != request.payload) invalid()
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { invalid() }
}

/** Validate the exact original format. No current profile or task is read during recovery. */
internal fun kitchenStationRequest(action: LocalAction, request: MutationRequest): LocalFoodRequestV1.Station {
    if (action !in kitchenStationActions || request.command !in kitchenCommands || request.actionApprovalId != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val station = try {
        val value = when (request.command) {
            LocalFoodProfileV1.COMMAND -> {
                val decoded = LocalFoodCodecV1.decode(request.payload)
                require(LocalFoodCodecV1.encode(decoded) == request.payload)
                decoded
            }
            LocalFoodStockCodecV2.COMMAND -> {
                val decoded = LocalFoodStockCodecV2.decode(request.payload)
                require(LocalFoodStockCodecV2.encode(decoded) == request.payload)
                if (decoded !is LocalFoodStockRequestV2.Preparation)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                decoded.request
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
        if (value !is LocalFoodRequestV1.Station || value.action !in setOf(LocalFoodStationActionV1.START, LocalFoodStationActionV1.READY))
            throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        require(foodRequestAction(value) == action && value.confirmationId == null && value.returnDisposition == null &&
            value.expectedOrderRevision.value in 1 until Long.MAX_VALUE &&
            value.expectedPortionRevision.value in 1 until Long.MAX_VALUE)
        value
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    return station
}

internal fun savedKitchenStationRequest(entry: StoredClientIntentV1): LocalFoodRequestV1.Station {
    if (entry.formVersion != 1 || entry.actionApproval != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val station = kitchenStationRequest(entry.localAction, entry.request())
    try {
        entry.validate(entry.owner)
        entry.recorded?.let { retained ->
            val payload = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            kitchenStationOutcome(station, retained.committed, retained.code, payload)
        }
    } catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    return station
}

/** Typed original-result validation precedes durable observation and matching presentation ACK. */
internal fun kitchenStationRecorded(entry: StoredClientIntentV1, reply: MutationReply.Recorded): LocalFoodResultV1 {
    val station = savedKitchenStationRequest(entry)
    if (reply.operationId != entry.operationId || reply.originalActorId != entry.owner.actorId || reply.physicalStart != null)
        throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return kitchenStationOutcome(station, reply.committed, reply.code, reply.result)
}

private fun kitchenStationOutcome(station: LocalFoodRequestV1.Station, committed: Boolean,
    code: String, payload: StoredBytes): LocalFoodResultV1 {
    val result = try { LocalFoodCodecV1.result(payload) }
    catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    val appliedCode = if (station.action == LocalFoodStationActionV1.START) "food.started" else "food.station.ready"
    val allowedStatuses = if (station.action == LocalFoodStationActionV1.START)
        setOf(FoodPreparationStatus.PREPARING, FoodPreparationStatus.CANCEL_PENDING)
    else setOf(FoodPreparationStatus.PREPARING, FoodPreparationStatus.READY)
    val portion = result.portions.singleOrNull()
    val valid = if (committed) code == "committed" && result.code == appliedCode &&
        result.orderId == station.orderId && result.orderRevision == station.expectedOrderRevision.next() &&
        portion != null && portion.portionId == station.portionId && portion.revision == station.expectedPortionRevision.next() &&
        portion.issueId != null && portion.status in allowedStatuses
    else code == result.code && code.startsWith("food.") && code !in setOf("food.started", "food.station.ready") &&
        result.orderId == null && result.orderRevision == null && result.portions.isEmpty()
    if (!valid) throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
    return result
}

/** Station presentation uses the existing protected source and original preparation commands. */
internal suspend fun kitchenWorkflow(access: GatewayAccess, query: LocalWorkflowQuery): LocalWorkflowPage {
    if (query.workflow != LocalWorkflow.KITCHEN) invalid()
    val selected = query.food ?: invalid()
    if (!selected.canAppearIn(LocalWorkflow.KITCHEN)) invalid()
    val original = foodWorkflow(access, LocalWorkflowQuery(LocalWorkflow.FOOD, food = selected))
    val source = original.food ?: unavailable()
    // Keep this one authorized response cut. Owner reads, finance and cancellation/stock
    // disposition forms are not part of the Kitchen workspace; no backend grant is changed.
    return original.copy(workflow = LocalWorkflow.KITCHEN,
        forms = original.forms.filter { it.action.canAppearIn(LocalWorkflow.KITCHEN) }, food = source)
}
