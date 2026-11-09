package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalFoodMenuCodecV1
import com.ukkera.pos.catalog.store.LocalFoodMenuQueryV1
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import kotlinx.serialization.json.Json

/** Fixed registry hints only. The actual Master independently admits each exact body. */
internal val foodCheckCommands: Set<CommandKey> = setOf(LocalFoodStockCodecV2.COMMAND)
internal val foodCheckReads: Set<ReadKey> = setOf(LocalFoodProfileV1.QUERY,
    LocalFoodCheckKitchenCodecV1.QUERY, LocalFoodCheckKitchenCodecV2.QUERY, LocalFoodCheckKitchenCodecV3.QUERY,
    LocalFoodMenuCodecV1.QUERY, LocalFoodQuotationProfileReadCodecV1.QUERY,
    LocalFoodSelectionPreviewCodecV1.QUERY)
internal val foodCheckActions: Set<LocalAction> = setOf(LocalAction.FOOD_CREATE_DRAFT, LocalAction.FOOD_ADD)

internal fun requireFoodCheckWorkflow(query: LocalWorkflowQuery) {
    if (query.workflow != LocalWorkflow.FOOD ||
        (query.food !is LocalFoodUiQuery.MyChecks && query.food !is LocalFoodUiQuery.Check && query.food !is LocalFoodUiQuery.Menu) ||
        query.search.isNotEmpty() || query.reference != null || query.cursor != null || query.pricePreview != null ||
        query.branchReport != null || query.purchasing != null || query.returns != null || query.cash != null ||
        query.inventory != null || query.openingAllocations != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

/** Only the existing draft form2 and quoted Add form3 may start new work. */
internal fun requireFoodCheckIntent(intent: LocalCommandIntent) {
    if (intent.reference != null || intent.arrivalEvidence != null || when (intent.action) {
            LocalAction.FOOD_CREATE_DRAFT -> intent.formVersion != 2 || intent.lineField != null || intent.lines.isNotEmpty()
            LocalAction.FOOD_ADD -> intent.formVersion != 3 || intent.lineField != LocalField.LINES || intent.lines.size !in 1..8
            else -> true
        }) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

internal fun requireFoodCheckPreview(input: LocalFoodPreviewInput) {
    if (input.action != LocalAction.FOOD_ADD || input.formVersion != 3 || input.lines.size !in 1..8)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

/** Shared read keys never grant handoff, station, financial or Create-preview selectors. */
internal fun requireFoodCheckRead(request: ReadRequest) {
    try {
        when (request.query) {
            LocalFoodProfileV1.QUERY -> {
                val query = LocalFoodReadCodecV1.decode(request.payload)
                if (query !is LocalFoodQueryV1.Own && query !is LocalFoodQueryV1.Order)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalFoodReadCodecV1.encode(query) != request.payload) invalid()
            }
            LocalFoodCheckKitchenCodecV1.QUERY, LocalFoodCheckKitchenCodecV2.QUERY, LocalFoodCheckKitchenCodecV3.QUERY -> {
                val query = LocalFoodReadCodecV1.decode(request.payload)
                if (query !is LocalFoodQueryV1.Order) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalFoodReadCodecV1.encode(query) != request.payload) invalid()
            }
            LocalFoodMenuCodecV1.QUERY -> {
                val query = foodCheckMenuQuery(request.payload)
                if (LocalFoodMenuCodecV1.read(query).payload != request.payload) invalid()
            }
            LocalFoodQuotationProfileReadCodecV1.QUERY ->
                if (request.payload != LocalFoodQuotationProfileReadCodecV1.encode()) invalid()
            LocalFoodSelectionPreviewCodecV1.QUERY -> {
                val selection = LocalFoodCodecV1.decode(request.payload)
                if (selection !is LocalFoodRequestV1.Add) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalFoodSelectionPreviewCodecV1.encode(selection) != request.payload) invalid()
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { invalid() }
}

private val foodCheckMenuJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

private fun foodCheckMenuQuery(payload: StoredBytes): LocalFoodMenuQueryV1 {
    require(payload.size <= 2_048)
    val text = payload.copyBytes().useWiped { it.decodeToString(throwOnInvalidSequence = true) }
    // This selector contains only nullable canonical identifiers and an integer.
    // Reject nested structures before asking the serializer to build an object graph.
    require(text.count { it == '{' } == 1 && text.count { it == '}' } == 1 && text.none { it == '[' || it == ']' })
    return foodCheckMenuJson.decodeFromString(LocalFoodMenuQueryV1.serializer(), text)
}
