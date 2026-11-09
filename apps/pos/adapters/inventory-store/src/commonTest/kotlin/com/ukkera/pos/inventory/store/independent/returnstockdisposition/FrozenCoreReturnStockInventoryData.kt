package com.ukkera.pos.inventory.store.independent.returnstockdisposition

import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.LocalStockRetailReturnRestoration
import com.ukkera.pos.inventory.store.LocalStockRetailReturnSelection
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*

/** Literal CORE fixture from return-stock-disposition v1, unchanged. This common
 * test has no filesystem/platform reader; the handoff checks this complete JSON
 * object against the frozen file. These values are not native source evidence. */
internal object FrozenCoreReturnStockInventoryInputs {
    private const val CORE_JSON = """
    {
      "id": "CORE",
      "state": "NOT RUN",
      "company": "SYN-C1",
      "selling_branch": "SYN-B1",
      "sku": "RSD-RETAIL-EACH",
      "base_unit": "EACH",
      "quantity_precision": 0,
      "lot_and_serial": "not tracked for this core fixture",
      "original_sale": {
        "quantity": "4",
        "paid_unit_amount": "50.00",
        "paid_amount": "200.00",
        "discount": "0.00",
        "tax": "0.00",
        "tender": "CASH",
        "issued_unit_cost": "20.00",
        "immutable_issued_cost": "80.00"
      },
      "independent_business_sequence": [
        {"fact": "owned opening inventory", "quantity": "4", "value": "80.00"},
        {"fact": "original sale issue", "quantity_delta": "-4", "value_delta": "-80.00", "remaining_quantity": "0", "remaining_value": "0.00"},
        {"fact": "earlier accepted return", "quantity": "1", "refund": "50.00", "refund_state": "SETTLED"},
        {"fact": "earlier inspected-sellable return", "quantity_delta": "1", "original_value_delta": "20.00"},
        {"fact": "later acquisition", "quantity_delta": "1", "unit_cost": "60.00", "value_delta": "60.00"},
        {"fact": "current valid accepted return, no stock disposition or payout yet", "quantity": "2", "refund_due": "100.00"}
      ],
      "current_catalog_irrelevant_to_original_return": {
        "unit_base_price": "80.00",
        "unit_discount": "5.00",
        "tax_rate": "0.20",
        "hypothetical_current_unit_payable": "90.00"
      },
      "before_stock_post": {
        "sellable_pool_quantity": "2",
        "sellable_pool_value": "80.00",
        "pool_average": "40.00",
        "available_quantity": "2",
        "current_accepted_quantity": "2",
        "current_accepted_original_cost": "40.00",
        "current_remaining_disposition_quantity": "2",
        "current_remaining_disposition_original_cost": "40.00",
        "cumulative_original_accepted_quantity": "3",
        "remaining_original_return_entitlement_quantity": "1",
        "remaining_original_return_entitlement_money": "50.00",
        "current_refund_due": "100.00",
        "current_actual_refund": "0.00",
        "drawer_cash": "500.00",
        "drawer_derivation": "350.00 opening + 200.00 original collection - 50.00 earlier actual refund"
      },
      "after_one_unit_sellable_post": {
        "quantity": "3",
        "value": "100.00",
        "available_quantity": "3",
        "remaining_disposition_quantity": "1",
        "remaining_disposition_original_cost": "20.00",
        "refund_due": "100.00",
        "drawer_cash": "500.00"
      },
      "after_all_two_units_sellable_post": {
        "quantity_delta": "2",
        "original_value_delta": "40.00",
        "quantity": "4",
        "value": "120.00",
        "pool_average": "30.00",
        "available_quantity": "4",
        "remaining_disposition_quantity": "0",
        "remaining_disposition_original_cost": "0.00",
        "cumulative_disposed_original_quantity": "3",
        "cumulative_disposed_original_cost": "60.00",
        "original_unreturned_cost": "20.00",
        "remaining_original_return_entitlement_quantity": "1",
        "remaining_original_return_entitlement_money": "50.00",
        "refund_due": "100.00",
        "drawer_cash": "500.00"
      },
      "after_separate_actual_cash_payout": {
        "actual_refund_delta": "100.00",
        "drawer_cash_delta": "-100.00",
        "drawer_cash": "400.00",
        "refund_due": "0.00",
        "inventory_quantity": "4",
        "inventory_value": "120.00"
      }
    }
    """

    val core = Json.parseToJsonElement(CORE_JSON).jsonObject.also {
        check(it.literal("id") == "CORE" && it.literal("state") == "NOT RUN")
        check(it.literal("base_unit") == "EACH" && it.getValue("quantity_precision").jsonPrimitive.int == 0)
    }
    val before get() = core.objectAt("before_stock_post")
    val partial get() = core.objectAt("after_one_unit_sellable_post")
    val complete get() = core.objectAt("after_all_two_units_sellable_post")
    val originalSale get() = core.objectAt("original_sale")
    fun fact(name: String) = core.getValue("independent_business_sequence").jsonArray.map { it.jsonObject }
        .single { it.literal("fact") == name }
    fun id(number: Int) = DomainId("75000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    val unitId = id(5)
    fun quantity(literal: String) = Quantity.parse(unitId, literal, core.getValue("quantity_precision").jsonPrimitive.int)
    fun money(literal: String) = Money(Currency.EGP, ExactDecimal.parse(literal, 2).toScaledLongExact(2))
}

internal fun JsonObject.objectAt(name: String): JsonObject = getValue(name).jsonObject
internal fun JsonObject.literal(name: String): String = getValue(name).jsonPrimitive.content

/** Frozen DATA is reduced through actual pure issue/restore-value/receive code.
 * The single untracked allocation/position topology and UUIDs are inert planning
 * coordinates, not a retained source, native original, accepted return or writer.
 * No source/authority/storage/history/financial services are supplied. */
internal class FrozenCoreReturnStockInventoryData {
    val inputs = FrozenCoreReturnStockInventoryInputs
    val poolKey = StockPoolKey(InventoryScope(inputs.id(1), inputs.id(2)), inputs.id(3), inputs.id(4))
    val positionKey = StockPositionKey(poolKey, lotId = null, custody = StockCustody.RELEASED)
    val portionId = inputs.id(6)
    val allocationId = inputs.id(7)
    val issueId = inputs.id(8)
    val saleId = inputs.id(9)
    val acceptedReturnId = inputs.id(10)
    private val opening = inputs.fact("owned opening inventory")
    val openingPool = InventoryPoolProjection(poolKey, Revision(1), inputs.quantity(opening.literal("quantity")),
        inputs.money(opening.literal("value")), lastEventOrdinal = 0)
    val issue = InventoryValuationProjection.issue(openingPool, inputs.quantity(inputs.originalSale.literal("quantity")))
    val originalZeroSlice = InventoryIssueSlice(issueId,
        StockAllocation(allocationId, portionId, positionKey, inputs.quantity(inputs.originalSale.literal("quantity"))),
        -issue.value, inputs.quantity(inputs.fact("original sale issue").literal("remaining_quantity")))
    val earlierQuantity = inputs.quantity(inputs.fact("earlier accepted return").literal("quantity"))
    val earlierValue = InventoryValuationProjection.originalRestorationValue(originalZeroSlice, earlierQuantity)
    val afterEarlier = InventoryValuationProjection.receive(issue.after,
        inputs.quantity(inputs.fact("earlier inspected-sellable return").literal("quantity_delta")), earlierValue)
    private val acquired = inputs.fact("later acquisition")
    val acquisition = InventoryValuationProjection.receive(afterEarlier.after, inputs.quantity(acquired.literal("quantity_delta")),
        inputs.money(acquired.literal("value_delta")))
    val beforePool = acquisition.after
    val beforeSlice = originalZeroSlice.copy(restoredQuantity = earlierQuantity)
    val acceptedQuantity = inputs.quantity(inputs.before.literal("current_accepted_quantity"))
    val acceptedCost = inputs.money(inputs.before.literal("current_accepted_original_cost"))
    val beforePosition = StockPosition(positionKey, beforePool.onHand, StockSafety.CLEAR)
    val now = 0L // Inert guard input; never an authoritative native time qualification.

    fun request(quantity: Quantity = acceptedQuantity, postingNumber: Int = 1) = LocalStockRetailReturnRestoration(
        restorationId = inputs.id(20 + postingNumber), acceptedReturnId = acceptedReturnId, saleId = saleId,
        portionId = portionId, issueId = issueId, expectedIssueRevision = Revision(1), quantityToRestore = quantity,
        selections = listOf(LocalStockRetailReturnSelection(allocationId, quantity, StockCustody.RELEASED)))
}
