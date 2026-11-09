package com.ukkera.pos.sales.store.independent.foodcashcustodyv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.StockAllocation
import com.ukkera.pos.inventory.StockPoolKey
import com.ukkera.pos.inventory.StockPositionKey
import com.ukkera.pos.inventory.store.LocalStockRelationalCodecV2
import com.ukkera.pos.inventory.store.LocalStockRelationalProfileV2
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import java.math.BigDecimal
import kotlinx.serialization.json.*
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The untouched v1.0.0 DATA input is not a native seed or a passed FCC case. */
internal class FoodCashCustodyFrozen(val mode: String) {
    val issued: Boolean = mode == "restaurant"
    private val root: JsonObject
    val state: JsonObject
    val scope: BranchScope
    val actorId: DomainId
    val orderId: DomainId
    val recipeAlias: DomainId
    val portionIds: List<DomainId>
    val completePortionCount: Int
    val ingredients: List<FoodCashCustodyIngredient>
    val originalTotalValue: Money
    val currency: Currency

    init {
        require(mode == "restaurant" || mode == "cafe")
        val bytes = checkNotNull(javaClass.getResourceAsStream(
            "/independent/foodcashcustodyv1/food-cash-cold-recovery-v1-fixtures.json")).use { it.readBytes() }
        assertEquals(48_183, bytes.size)
        assertEquals("a7e281c76cf650742b92ebb47ded9f7d7338d6d6cb5eb2b58f179b0972bfc0be", sha256(bytes).value)
        root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        assertEquals("1.0.0", root.text("expectation_version"))
        assertEquals("NOT RUN", root.text("status"))
        assertTrue(root.getValue("synthetic_only").jsonPrimitive.boolean)
        assertNull(root.getValue("tested_revision").takeUnless { it == JsonNull })
        assertTrue(root.getValue("execution_evidence").jsonArray.isEmpty())
        val faults = root.getValue("source_fault_bindings").jsonObject
        assertEquals(JsonNull, faults.getValue("actual_native_source_keys"))
        assertTrue(faults.getValue("current_v3_faults").jsonArray.any {
            it.jsonPrimitive.content == "original_stock_relationship_mismatch" })
        assertTrue(faults.getValue("original_cash_faults").jsonArray.any {
            it.jsonPrimitive.content == "missing_or_mismatched_original_price_portion_allocation" })
        currency = Currency.valueOf(root.getValue("money_contract").jsonObject.text("currency"))
        state = root.getValue(mode).jsonObject
        scope = BranchScope(id("organization"), id("${mode}_company"), id("${mode}_branch"))
        actorId = id("${mode}_actor")
        orderId = id("${mode}_order")
        recipeAlias = id(if (issued) "burger_recipe" else "latte_recipe")
        portionIds = (if (issued) listOf("restaurant_portion_inclusive", "restaurant_portion_exclusive")
            else listOf("cafe_portion_1", "cafe_portion_2")).map(::id)
        completePortionCount = state.number("complete_portion_count").toInt()
        assertEquals(completePortionCount, portionIds.size)
        val recipe = state.getValue("recipe").jsonObject.getValue("per_portion").jsonArray
        val snapshot = state.getValue("snapshots").jsonObject
            .getValue(if (issued) "paid_closed" else "paid_unsent").jsonObject.getValue("stock").jsonObject
        ingredients = (if (issued) listOf("bread", "meat") else listOf("beans", "milk")).map { name ->
            val sku = id("${name}_sku")
            val entry = recipe.map { it.jsonObject }.single { it.text("sku_id") == sku.value }
            val unitLabel = entry.text("unit")
            val unit = syntaxId(when (unitLabel) { "piece" -> 1; "g" -> 2; "ml" -> 3; else -> error("Unbound DATA unit") })
            fun quantity(value: Long): Quantity = Quantity(unit, value, 0)
            val opening = state.getValue("opening_stock").jsonObject.getValue(name).jsonObject
            val after = snapshot.getValue(name).jsonObject
            val total = if (issued) state.getValue("stock_issue").jsonObject
                .number(if (name == "bread") "bread_quantity" else "meat_quantity_g") else after.number("reserved")
            val cost = state.getValue("unit_carrying_cost_egp").jsonObject.text("${name}_per_$unitLabel")
            FoodCashCustodyIngredient(sku, quantity(entry.number("quantity")), quantity(opening.number("on_hand")),
                Money(currency, opening.number("carrying_value_minor")), quantity(after.number("on_hand")),
                BigDecimal(cost).movePointRight(currency.fractionDigits).longValueExact(), quantity(total),
                Money(currency, after.number("original_issued_value_minor")))
        }
        originalTotalValue = Money(currency, if (issued) state.getValue("stock_issue").jsonObject
            .number("original_total_cost_minor") else 0)
    }

    private fun id(name: String): DomainId = DomainId(root.getValue("synthetic_ids").jsonObject.text(name))
}

internal data class FoodCashCustodyIngredient(val skuId: DomainId, val perPortion: Quantity, val opening: Quantity,
    val openingValue: Money, val snapshotOnHand: Quantity, val unitCostMinor: Long, val expectedCustody: Quantity,
    val expectedIssuedValue: Money) {
    val portionValue: Money get() = Money(openingValue.currency, Math.multiplyExact(perPortion.scaled, unitCostMinor))
}

internal data class FoodCashCustodyPortion(val portionId: DomainId, val reservationId: DomainId, val setId: DomainId,
    val issueId: DomainId?, val producer: StreamedProducerIdentityV2, val allocations: List<StockAllocation>)

/**
 * NON-NATIVE canonical fixtures, limited to nine row shapes. The real inventory project/decode
 * helper validates every emitted row, including unindexed rows; no inventory codec is replaced.
 * Finite member/terminal DATA uses the real proposedRangeSources chain, not an actual owner EOF.
 *
 * Frozen scope/actor/order/SKU/portion UUIDs retain their literal meanings as DATA. Each portion
 * UUID occupies allocation.lineId. The frozen recipe UUID is only a recipeSnapshotId DATA alias,
 * not a captured original snapshot. All additional identities use syntaxId's separate namespace:
 * units 1/2/3 alias piece/g/ml with scale 0; mode base 100 (Restaurant) or 200 (Cafe) supplies
 * warehouse +0, migration +1, collection +2, station +3 and generic producer operation +4.
 * Portion index i uses base+10+10*i reservation, +11+10*i allocation set, +12+10*i issue and
 * +13+10*i issue producer operation. Cafe does not emit issue/station rows. Public key helpers
 * derive positions/pools/allocations/issue slices; only value-event/value-slice lookup-key glue
 * is repeated below, and its result is checked by the real decoder. None is a native source ID.
 *
 * All row, binding, header and allocation-set revisions are "1", previous is null, and every
 * producer has comparison.food-cash-custody-data/v1, the frozen actor and a label SHA-256. These
 * are syntax, not authenticated commands or actual revision handles. Activation's 0/2 counts,
 * label commitment and fresh-empty/relational profile tags are decoder DATA, not activation.
 * Company-owned/released/clear/nonserial/null-lot/null-expiry tags are syntax. Restaurant uses
 * ISSUED/UNCOLLECTED, DIRECT_RECIPE/ACTIVE and one synthetic required/started station; Cafe uses
 * RESERVED/UNKNOWN. These do not establish paid application or genuine kitchen/start state.
 * Per-portion Restaurant issue IDs are helper inputs, NOT the frozen native issue-set count.
 * Pool revisions 1..3/ordinals 0..2 are exact arithmetic DATA between frozen opening amounts
 * and per-portion costs, not native historical continuity or timestamps. There is no clock.
 */
internal class FoodCashCustodyData(mode: String) {
    val frozen: FoodCashCustodyFrozen = FoodCashCustodyFrozen(mode)
    private val base = if (frozen.issued) 100 else 200
    private val warehouse = syntaxId(base)
    private val emitter = FoodCashCanonicalRows(frozen.scope)
    private val rows = linkedMapOf<AggregateKey, FoodCashCanonicalRow>()
    private val claims = linkedMapOf<ClaimKey, DomainId>()
    private val ranges = linkedMapOf<StockQueryV2, List<AggregateKey>>()
    val portions: List<FoodCashCustodyPortion> = frozen.portionIds.mapIndexed { index, id ->
        val reservation = syntaxId(base + 10 + index * 10)
        val set = syntaxId(base + 11 + index * 10)
        val allocations = frozen.ingredients.map { ingredient ->
            val position = StockPositionKey(StockPoolKey(InventoryScope(frozen.scope.companyId,
                frozen.scope.branchId), ingredient.skuId, warehouse), null)
            StockAllocation(LocalStockRelationalProfileV2.allocationId(set, Revision(1), id, position),
                id, position, ingredient.perPortion)
        }
        FoodCashCustodyPortion(id, reservation, set, if (frozen.issued) syntaxId(base + 12 + index * 10) else null,
            producer(base + 13 + index * 10), allocations)
    }
    val observed: StockReadObservations

    init {
        fun add(key: AggregateKey, body: JsonObject, identity: StreamedProducerIdentityV2 = producer(base + 4)): AggregateKey {
            check(rows.put(key, emitter.emit(key, identity, body)) == null)
            return key
        }
        add(LocalStockRelationalProfileV2.activationKey(frozen.scope.branchId), body("activation") {
            put("migrationId", syntaxId(base + 1).value); put("legacyAggregateCount", "0"); put("normalizedAggregateCount", "2")
            put("legacyCommitment", sha256("food-cash-custody-DATA/activation/$mode".encodeToByteArray()).value)
            put("sourceProfile", "local.stock.fresh-empty/v2"); put("targetProfile", "local.stock.relational/v2")
        })
        if (!frozen.issued) for (ingredient in frozen.ingredients) {
            add(LocalStockRelationalProfileV2.itemKey(ingredient.skuId), body("item") {
                put("skuId", ingredient.skuId.value); put("unitId", ingredient.perPortion.unitId.value)
                put("scale", 0); put("serialized", false)
            })
            claims[LocalStockStoreProfile.itemClaim(ingredient.skuId)] = ingredient.skuId
            val position = portions.first().allocations.single { it.position.pool.skuId == ingredient.skuId }.position
            add(LocalStockRelationalProfileV2.positionKey(position), body("position") {
                put("value", buildJsonObject {
                    put("key", positionJson(position)); put("onHand", quantity(ingredient.snapshotOnHand)); put("safety", 1)
                    put("expiryMillis", JsonNull); put("serialRegistrationId", JsonNull)
                })
            })
            ranges[StockQueryV2.PositionMembers(LocalStockRelationalProfileV2.positionKey(position).id)] =
                portions.flatMap { it.allocations }.filter { it.position == position }
                    .map { LocalStockRelationalProfileV2.allocationKey(it.allocationId) }
        }
        for ((index, portion) in portions.withIndex()) {
            val binding = buildJsonObject {
                put("orderId", frozen.orderId.value); put("quoteRevision", "1"); put("collectionId", syntaxId(base + 2).value)
                put("actorId", frozen.actorId.value); put("exposure", if (frozen.issued) 1 else 4)
            }
            add(LocalStockRelationalProfileV2.reservationKey(portion.reservationId), body("reservation") {
                put("value", buildJsonObject {
                    put("id", portion.reservationId.value); put("binding", binding); put("revision", "1")
                    put("status", if (frozen.issued) 3 else 1); put("expiryMillis", JsonNull)
                    put("allocationCount", portion.allocations.size.toString()); put("holdCount", "0")
                    put("issueId", portion.issueId?.let { JsonPrimitive(it.value) } ?: JsonNull)
                })
                put("allocationSetId", portion.setId.value); put("allocationSetRevision", "1")
            })
            claims[LocalStockStoreProfile.reservationClaim(portion.reservationId)] = portion.reservationId
            ranges[StockQueryV2.ReservationMembers(portion.reservationId)] = portion.allocations.map { allocation ->
                claims[LocalStockStoreProfile.allocationClaim(allocation.allocationId)] = portion.reservationId
                add(LocalStockRelationalProfileV2.allocationKey(allocation.allocationId), body("allocation") {
                    put("reservationId", portion.reservationId.value); put("value", allocationJson(allocation))
                })
            }
            val issue = portion.issueId ?: continue
            claims[LocalStockStoreProfile.sourceClaim(issue)] = issue
            val original = frozen.ingredients.fold(Money(frozen.currency, 0)) { total, ingredient -> total + ingredient.portionValue }
            add(LocalStockRelationalProfileV2.issueKey(issue), body("issue") {
                put("value", buildJsonObject {
                    put("id", issue.value); put("reservationId", portion.reservationId.value); put("binding", binding)
                    put("purpose", 2); put("revision", "1"); put("allocationCount", portion.allocations.size.toString())
                    put("originalValue", money(original)); put("recipeSnapshotId", frozen.recipeAlias.value)
                    put("requiredStationIds", JsonArray(listOf(JsonPrimitive(syntaxId(base + 3).value))))
                    put("startedStationIds", JsonArray(listOf(JsonPrimitive(syntaxId(base + 3).value)))); put("disposition", 1)
                })
            }, portion.producer)
            ranges[StockQueryV2.IssueValues(issue)] = portion.allocations.flatMap { allocation ->
                val ingredient = frozen.ingredients.single { it.skuId == allocation.position.pool.skuId }
                val pool = allocation.position.pool
                val eventId = valueLookupId("v2.value-event", "${issue.value}/${LocalStockRelationalProfileV2.poolKey(pool).id.value}")
                listOf(
                    add(LocalStockRelationalProfileV2.issueSliceKey(issue, allocation.allocationId), body("issue-slice") {
                        put("issueId", issue.value); put("allocation", allocationJson(allocation)); put("originalValue", money(ingredient.portionValue))
                        put("restoredQuantity", quantity(ingredient.perPortion.copy(scaled = 0)))
                    }, portion.producer),
                    add(AggregateKey(StockRelationalKindsV2.VALUE_EVENT, eventId), body("value-event") {
                        put("pool", poolJson(pool)); put("sourceId", issue.value); put("ordinal", (index + 1).toString()); put("event", 2)
                        put("before", poolState(pool, ingredient, index)); put("after", poolState(pool, ingredient, index + 1))
                        put("quantityDelta", quantity(-ingredient.perPortion)); put("originalValueDelta", money(-ingredient.portionValue))
                        put("carryingValueDelta", money(-ingredient.portionValue)); put("sliceCount", "1"); put("issueId", issue.value)
                    }, portion.producer),
                    add(AggregateKey(StockRelationalKindsV2.VALUE_SLICE,
                        valueLookupId("v2.value-slice", "${eventId.value}/${allocation.allocationId.value}")), body("value-slice") {
                        put("pool", poolJson(pool)); put("sourceId", issue.value); put("eventId", eventId.value)
                        put("eventOrdinal", (index + 1).toString()); put("issueId", issue.value); put("allocationId", allocation.allocationId.value)
                        put("quantity", quantity(ingredient.perPortion)); put("originalValue", money(ingredient.portionValue))
                    }, portion.producer),
                )
            }
        }
        observed = observations(rows)
    }

    fun missingAllocationSource(key: AggregateKey): StockReadObservations = StockReadObservations(frozen.scope,
        observed.sources.filterNot { it is StreamedSourceObservationV2.Current && it.key == key })

    /** Keep the original required membership, but rebuild canonical projections/commitments.
     * The mismatch is the reservation association, not a stale hash or a repaired partial cut. */
    fun mismatchedAllocationSource(key: AggregateKey, otherReservation: DomainId): StockReadObservations =
        observations(rows + (key to emitter.reassociateAllocation(rows.getValue(key), otherReservation)))

    private fun observations(data: Map<AggregateKey, FoodCashCanonicalRow>): StockReadObservations = StockReadObservations(
        frozen.scope, data.values.map { it.source } + claims.map { StreamedSourceObservationV2.Claim(it.key, it.value) } +
            ranges.flatMap { (query, keys) ->
                val members = keys.map { checkNotNull(data.getValue(it).projection) }
                val ordered = when (query) {
                    is StockQueryV2.ReservationMembers -> members.sortedWith(compareBy<StockIndexRecordV2>({ it.identity.key.kind },
                        { (it as StockIndexRecordV2.Allocation).lineId?.value.orEmpty() }, { it.identity.key.id.value }))
                    is StockQueryV2.PositionMembers -> members.sortedWith(compareBy<StockIndexRecordV2>({ it.identity.key.kind }, { it.identity.key.id.value }))
                    is StockQueryV2.IssueValues -> members.sortedWith(compareBy<StockIndexRecordV2>({ (it as StockIndexRecordV2.Value).allocationId?.value.orEmpty() },
                        { it.identity.key.kind }, { it.identity.key.id.value }))
                    else -> error("Unbound DATA query")
                }
                StockReadEvidenceV1.proposedRangeSources(query, ordered)
            })

    private fun producer(slot: Int): StreamedProducerIdentityV2 = StreamedProducerIdentityV2(OperationId(syntaxId(slot).value),
        CommandKey("comparison.food-cash-custody-data", 1), frozen.actorId,
        sha256("food-cash-custody-DATA/${frozen.mode}/$slot".encodeToByteArray()))
}

private data class FoodCashCanonicalRow(val source: StreamedSourceObservationV2.Current, val projection: StockIndexRecordV2?)

/** Small JSON glue only: canonical field order is validated by the real row/body decoder. */
private class FoodCashCanonicalRows(private val scope: BranchScope) {
    fun emit(key: AggregateKey, producer: StreamedProducerIdentityV2, body: JsonObject): FoodCashCanonicalRow = validate(key, buildJsonObject {
        put("format", 2); put("scope", buildJsonObject {
            put("organizationId", scope.organizationId.value); put("companyId", scope.companyId.value); put("branchId", scope.branchId.value)
        })
        put("kind", key.kind); put("id", key.id.value); put("generation", "1")
        put("producer", buildJsonObject {
            put("operationId", producer.operationId.value); put("command", producer.command.name); put("version", producer.command.version)
            put("actorId", producer.originalActorId.value); put("digest", producer.semanticDigest.value)
        })
        put("previous", JsonNull); put("body", body.toString())
    })

    fun reassociateAllocation(row: FoodCashCanonicalRow, reservation: DomainId): FoodCashCanonicalRow {
        require(row.source.key.kind == StockRelationalKindsV2.ALLOCATION)
        val wire = Json.parseToJsonElement(row.source.payload.copyBytes().decodeToString()).jsonObject
        val body = Json.parseToJsonElement(wire.text("body")).jsonObject
        val changed = JsonObject(body + ("reservationId" to JsonPrimitive(reservation.value)))
        return validate(row.source.key, JsonObject(wire + ("body" to JsonPrimitive(changed.toString()))))
    }

    private fun validate(key: AggregateKey, wire: JsonObject): FoodCashCanonicalRow {
        val bytes = StoredBytes(wire.toString().encodeToByteArray())
        val hash = sha256(bytes.copyBytes())
        val schema = StockRelationalKindsV2.schema(key.kind)
        val projected = LocalStockRelationalCodecV2.project(scope, StoredAggregate(key, Revision(1), schema, bytes, hash))
        return FoodCashCanonicalRow(StreamedSourceObservationV2.Current(key, Revision(1), schema, bytes, hash), projected)
    }
}

private fun body(type: String, fields: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject { put("type", type); fields() }
private fun positionJson(value: StockPositionKey): JsonObject = buildJsonObject {
    put("skuId", value.pool.skuId.value); put("warehouseId", value.pool.warehouseId.value); put("ownership", 1); put("ownerId", JsonNull)
    put("custody", 1); put("lotId", JsonNull); put("serialId", JsonNull)
}
private fun poolJson(value: StockPoolKey): JsonObject = buildJsonObject { put("skuId", value.skuId.value); put("warehouseId", value.warehouseId.value) }
private fun allocationJson(value: StockAllocation): JsonObject = buildJsonObject {
    put("id", value.allocationId.value); put("lineId", value.lineId.value); put("position", positionJson(value.position)); put("quantity", quantity(value.quantity))
}
private fun poolState(pool: StockPoolKey, ingredient: FoodCashCustodyIngredient, index: Int): JsonObject = buildJsonObject {
    put("key", poolJson(pool)); put("revision", (index + 1).toString())
    put("onHand", quantity(ingredient.opening - ingredient.perPortion.copy(scaled = Math.multiplyExact(ingredient.perPortion.scaled, index.toLong()))))
    put("value", money(ingredient.openingValue - Money(ingredient.openingValue.currency, Math.multiplyExact(ingredient.portionValue.minor, index.toLong()))))
    put("ordinal", index.toString())
}
private fun quantity(value: Quantity): JsonElement = Json.encodeToJsonElement(Quantity.serializer(), value)
private fun money(value: Money): JsonElement = Json.encodeToJsonElement(Money.serializer(), value)
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.number(key: String): Long = text(key).toLong()
private fun syntaxId(slot: Int): DomainId = DomainId("fcca5100-0000-4000-8000-${slot.toString(16).padStart(12, '0')}")
private fun valueLookupId(domain: String, value: String): DomainId {
    val hex = sha256("ukkera.local.stock/v1\u0000$domain\u0000$value".encodeToByteArray()).value
    return DomainId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-5${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}")
}
