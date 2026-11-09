package com.ukkera.pos.sales.store.independent.foodcashcustodyv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.store.LocalStockRelationalCodecV2
import com.ukkera.pos.inventory.store.LocalStockRelationalProfileV2
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*

/**
 * Lookup/relationship syntax binding only. The existing canonical stock DATA
 * retains every frozen quantity/value, position, allocation-set ID and pool
 * arithmetic; it is not rewritten in place. No new physical stock is produced.
 *
 * Each reservation/issue ID and allocation line uses its actual FOOD helper.
 * The committed DATA's arbitrary issue producer operation/actor/label digest
 * gets the FOOD command tag solely to compare a literal tuple, not authenticate
 * a producer. The Restaurant current issue business revision is 2 (one started
 * DTO station); row generation remains 1 / previous null, explicitly NON-NATIVE
 * syntax with no native current-header history. Recipe snapshot/station aliases
 * match the typed FOOD DATA. Cafe remains RESERVED/UNKNOWN with no issue rows.
 *
 * Actual inventory key helpers derive allocations and issue slices. Only the
 * existing codec's private value-event/value-slice lookup glue is repeated;
 * every resulting row, including unindexed rows, is validated by the real
 * LocalStockRelationalCodecV2.project. Its actual proposedRangeSources helper
 * rebuilds complete finite DATA commitments in each query's canonical order;
 * these are not an authenticated owner traversal or native range EOF.
 */
internal fun foodCashSalesStockData(data: FoodCashCustodyData, recipes: List<LocalFoodRecipeSnapshotV1>,
    collectionId: DomainId, stationId: DomainId): StockReadObservations {
    val replacements = linkedMapOf<String, String>()
    val allocations = linkedMapOf<String, StockAllocation>()
    val claimKeys = linkedMapOf<ClaimKey, ClaimKey>()
    val issueRecipes = linkedMapOf<DomainId, LocalFoodRecipeSnapshotV1>()
    val source = data.observed
    for ((index, portion) in data.portions.withIndex()) {
        val reservation = foodReservationId(portion.portionId)
        replacements[portion.reservationId.value] = reservation.value
        claimKeys[LocalStockStoreProfile.reservationClaim(portion.reservationId)] = LocalStockStoreProfile.reservationClaim(reservation)
        val header = source.current(LocalStockRelationalProfileV2.reservationKey(portion.reservationId))!!.stockBody().getValue("value").jsonObject
        replacements[header.getValue("binding").jsonObject.text("collectionId")] =
            (if (data.frozen.issued) foodStockContextId(data.frozen.orderId) else collectionId).value
        for (allocation in portion.allocations) {
            val ingredient = recipes[index].ingredients.single { it.skuId == allocation.position.pool.skuId }
            val line = foodIngredientLine(portion.portionId, ingredient.componentId)
            val changed = allocation.copy(allocationId = LocalStockRelationalProfileV2.allocationId(portion.setId,
                Revision(1), line, allocation.position), lineId = line)
            allocations[allocation.allocationId.value] = changed
            replacements[allocation.allocationId.value] = changed.allocationId.value
            claimKeys[LocalStockStoreProfile.allocationClaim(allocation.allocationId)] = LocalStockStoreProfile.allocationClaim(changed.allocationId)
            val oldIssue = portion.issueId ?: continue
            val newIssue = foodIssueId(portion.portionId)
            replacements[LocalStockRelationalProfileV2.issueSliceKey(oldIssue, allocation.allocationId).id.value] =
                LocalStockRelationalProfileV2.issueSliceKey(newIssue, changed.allocationId).id.value
            val poolId = LocalStockRelationalProfileV2.poolKey(allocation.position.pool).id
            val oldEvent = valueId("v2.value-event", "${oldIssue.value}/${poolId.value}")
            val newEvent = valueId("v2.value-event", "${newIssue.value}/${poolId.value}")
            replacements[oldEvent.value] = newEvent.value
            replacements[valueId("v2.value-slice", "${oldEvent.value}/${allocation.allocationId.value}").value] =
                valueId("v2.value-slice", "${newEvent.value}/${changed.allocationId.value}").value
        }
        val issue = portion.issueId ?: continue
        replacements[issue.value] = foodIssueId(portion.portionId).value
        claimKeys[LocalStockStoreProfile.sourceClaim(issue)] = LocalStockStoreProfile.sourceClaim(foodIssueId(portion.portionId))
        issueRecipes[issue] = recipes[index]
        for (station in source.current(LocalStockRelationalProfileV2.issueKey(issue))!!.stockBody()
            .getValue("value").jsonObject.getValue("requiredStationIds").jsonArray)
            replacements[station.jsonPrimitive.content] = stationId.value
    }

    fun replace(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> {
            val changed = JsonObject(value.mapValues { replace(it.value) })
            val allocation = value["id"]?.jsonPrimitive?.content?.let(allocations::get)
            if (allocation != null && "lineId" in value && "position" in value && "quantity" in value)
                JsonObject(changed + ("lineId" to JsonPrimitive(allocation.lineId.value))) else changed
        }
        is JsonArray -> JsonArray(value.map(::replace))
        is JsonPrimitive -> if (value.isString) replacements[value.content]?.let(::JsonPrimitive) ?: value else value
    }
    val keys = linkedMapOf<AggregateKey, AggregateKey>()
    val projections = linkedMapOf<AggregateKey, StockIndexRecordV2?>()
    val rows = source.sources.filterIsInstance<StreamedSourceObservationV2.Current>().map { old ->
        val key = AggregateKey(old.key.kind, DomainId(replacements[old.key.id.value] ?: old.key.id.value))
        keys[old.key] = key
        val oldWire = Json.parseToJsonElement(old.payload.copyBytes().decodeToString()).jsonObject
        var body = replace(old.stockBody()).jsonObject
        if (old.key.kind == StockRelationalKindsV2.ISSUE) {
            val recipe = issueRecipes.getValue(old.key.id)
            body = JsonObject(body + ("value" to JsonObject(body.getValue("value").jsonObject +
                mapOf("revision" to JsonPrimitive("2"), "recipeSnapshotId" to JsonPrimitive(recipe.snapshotId.value)))))
        }
        var wire = replace(oldWire).jsonObject
        val producer = wire.getValue("producer").jsonObject
        if (data.portions.any { it.issueId != null && it.producer.operationId.value == producer.text("operationId") })
            wire = JsonObject(wire + ("producer" to JsonObject(producer + mapOf(
                "command" to JsonPrimitive(StockStreamingCommandsV2.FOOD.name), "version" to JsonPrimitive(StockStreamingCommandsV2.FOOD.version)))))
        wire = JsonObject(wire + ("body" to JsonPrimitive(body.toString())))
        val bytes = StoredBytes(wire.toString().encodeToByteArray()); val hash = sha256(bytes.copyBytes())
        projections[key] = LocalStockRelationalCodecV2.project(source.scope, StoredAggregate(key, old.generation, old.schema, bytes, hash))
        StreamedSourceObservationV2.Current(key, old.generation, old.schema, bytes, hash)
    }
    val claims = source.sources.filterIsInstance<StreamedSourceObservationV2.Claim>().map { claim ->
        StreamedSourceObservationV2.Claim(claimKeys[claim.key] ?: claim.key,
            claim.ownerId?.let { DomainId(replacements[it.value] ?: it.value) })
    }
    fun query(old: StockQueryV2): StockQueryV2 = when (old) {
        is StockQueryV2.ReservationMembers -> StockQueryV2.ReservationMembers(DomainId(replacements.getValue(old.reservationId.value)))
        is StockQueryV2.IssueValues -> StockQueryV2.IssueValues(DomainId(replacements.getValue(old.issueId.value)))
        is StockQueryV2.PositionMembers -> old
        else -> error("Unbound FOOD custody DATA query")
    }
    val ranges = source.sources.filterIsInstance<StreamedSourceObservationV2.StockRange>().flatMap { terminal ->
        val selected = query(terminal.query)
        val members = source.sources.filterIsInstance<StreamedSourceObservationV2.StockMember>().filter { it.query == terminal.query }
            .map { checkNotNull(projections.getValue(keys.getValue(it.record.identity.key))) }
        val ordered = when (selected) {
            is StockQueryV2.ReservationMembers -> members.sortedWith(compareBy<StockIndexRecordV2>({ it.identity.key.kind },
                { (it as StockIndexRecordV2.Allocation).lineId?.value.orEmpty() }, { it.identity.key.id.value }))
            is StockQueryV2.PositionMembers -> members.sortedWith(compareBy<StockIndexRecordV2>({ it.identity.key.kind }, { it.identity.key.id.value }))
            is StockQueryV2.IssueValues -> members.sortedWith(compareBy<StockIndexRecordV2>({ (it as StockIndexRecordV2.Value).allocationId?.value.orEmpty() },
                { it.identity.key.kind }, { it.identity.key.id.value }))
            else -> error("Unbound FOOD custody DATA query")
        }
        StockReadEvidenceV1.proposedRangeSources(selected, ordered)
    }
    return StockReadObservations(source.scope, rows + claims + ranges).also { observed ->
        observed.sources.filterIsInstance<StreamedSourceObservationV2.StockRange>().forEach { terminal ->
            observed.range(terminal.query) { member -> check(projections[member.identity.key] == member) }
        }
    }
}

private fun StreamedSourceObservationV2.Current.stockBody() = Json.parseToJsonElement(
    Json.parseToJsonElement(payload.copyBytes().decodeToString()).jsonObject.text("body")).jsonObject
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
private fun valueId(domain: String, value: String): DomainId {
    val hex = sha256("ukkera.local.stock/v1\u0000$domain\u0000$value".encodeToByteArray()).value
    return DomainId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-5${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}")
}
