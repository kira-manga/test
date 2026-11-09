package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalFoodFloorQueryV1
import com.ukkera.pos.catalog.store.LocalFoodFloorReadCodecV1
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.*
import kotlinx.serialization.KSerializer

/** Fixed installation hints only; the Master separately admits each exact protected request. */
internal val foodTableAssignmentCommands: Set<CommandKey> = setOf(LocalFoodTableAssignmentProfileV1.COMMAND)
internal val foodTableAssignmentReads: Set<ReadKey> = setOf(LocalFoodProfileV1.QUERY,
    LocalFoodTableAssignmentReadCodecV1.QUERY, LocalFoodFloorReadCodecV1.QUERY)

internal fun requireFoodTableAssignmentWorkflow(query: LocalWorkflowQuery) {
    val selected = query.food
    if (query.workflow != LocalWorkflow.FOOD || when (selected) {
            is LocalFoodUiQuery.MyChecks, is LocalFoodUiQuery.Check,
            is LocalFoodUiQuery.FloorDirectory, is LocalFoodUiQuery.FloorTables,
            is LocalFoodUiQuery.TableDefinition -> false
            is LocalFoodUiQuery.TableAssignment -> selected.expectedOrderStorageRevision.value < 1
            else -> true
        } || query.search.isNotEmpty() || query.reference != null || query.cursor != null ||
        query.pricePreview != null || query.branchReport != null || query.purchasing != null ||
        query.returns != null || query.cash != null || query.inventory != null || query.openingAllocations != null)
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

/** Only the existing exact assignment form1 may begin work. */
internal fun requireFoodTableAssignmentIntent(intent: LocalCommandIntent) {
    if (intent.action != LocalAction.FOOD_ASSIGN_TABLE || intent.formVersion != 1 || intent.reference != null ||
        intent.arrivalEvidence != null || intent.lineField != null || intent.lines.isNotEmpty())
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
}

internal fun requireFoodTableAssignmentRead(request: ReadRequest) {
    try {
        when (request.query) {
            LocalFoodProfileV1.QUERY -> {
                val query = LocalFoodReadCodecV1.decode(request.payload)
                if (query !is LocalFoodQueryV1.Own && query !is LocalFoodQueryV1.Order)
                    throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                if (LocalFoodReadCodecV1.encode(query) != request.payload) invalid()
            }
            LocalFoodTableAssignmentReadCodecV1.QUERY -> {
                val query = tableAssignmentReadSelector(request.payload, LocalFoodTableAssignmentQueryV1.serializer())
                if (LocalFoodTableAssignmentReadCodecV1.encode(query) != request.payload) invalid()
            }
            LocalFoodFloorReadCodecV1.QUERY -> {
                val query = tableAssignmentReadSelector(request.payload, LocalFoodFloorQueryV1.serializer())
                if (LocalFoodFloorReadCodecV1.encode(query) != request.payload) invalid()
            }
            else -> throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
        }
    } catch (failure: GatewayFailure) { throw failure }
    catch (_: Exception) { invalid() }
}

private fun <T> tableAssignmentReadSelector(payload: StoredBytes, serializer: KSerializer<T>): T {
    require(payload.size <= 2_048)
    val text = payload.copyBytes().useWiped { it.decodeToString(throwOnInvalidSequence = true) }
    // These selectors contain only identifiers and a page limit, never nested business bodies.
    require(text.count { it == '{' } == 1 && text.count { it == '}' } == 1 && text.none { it == '[' || it == ']' })
    return journalJson.decodeFromString(serializer, text)
}

internal fun foodTableAssignmentRequest(action: LocalAction, request: MutationRequest): LocalFoodTableAssignmentRequestV1 {
    if (action != LocalAction.FOOD_ASSIGN_TABLE || request.command != LocalFoodTableAssignmentProfileV1.COMMAND ||
        request.actionApprovalId != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    return try {
        LocalFoodTableAssignmentCodecV1.decode(request.payload).also {
            require(LocalFoodTableAssignmentCodecV1.encode(it) == request.payload)
        }
    } catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
}

/** Saved Submit/Lookup never rereads a destination or replaces any original expectation. */
internal fun savedFoodTableAssignmentRequest(entry: StoredClientIntentV1): LocalFoodTableAssignmentRequestV1 {
    if (entry.formVersion != 1 || entry.actionApproval != null) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val original = foodTableAssignmentRequest(entry.localAction, entry.request())
    try {
        entry.validate(entry.owner)
        entry.recorded?.let { retained ->
            require(retained.sequence.value > 0)
            val payload = decodeHex(retained.payloadHex, 65_536).useWiped { StoredBytes(it) }
            tableAssignmentOutcome(original, retained.committed, retained.code, payload)
        }
    } catch (_: Exception) { throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN) }
    return original
}
