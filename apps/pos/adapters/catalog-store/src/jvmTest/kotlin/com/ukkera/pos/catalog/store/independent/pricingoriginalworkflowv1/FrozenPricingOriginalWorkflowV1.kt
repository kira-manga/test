package com.ukkera.pos.catalog.store.independent.pricingoriginalworkflowv1

import com.ukkera.pos.catalog.store.LocalPriceV1
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Frozen facts only. The IDs and constructor metadata below supply no authority. */
internal object FrozenPricingOriginalWorkflowV1 {
    internal val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val document = javaClass.getResourceAsStream(
        "/pricingoriginalworkflowv1/local-pricing-original-workflow-v1.json",
    )!!.use { stream ->
        val bytes = stream.readBytes()
        check(sha256(bytes).value == "49a7150665eff31cfbd8b067aa2a819956f1083e938d4e07b9e4b7179fd4c23c")
        json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }.also { value ->
        check(value.text("design_version") == "1")
        // Historical authoring metadata; the freeze README establishes FROZEN v1.
        check(value.text("status") == "DRAFT_NOT_FROZEN_NOT_RUN")
        val inventory = value["case_inventory"]!!.jsonObject
        check(inventory["portable"]!!.jsonArray.map { it.jsonPrimitive.content } ==
            (1..18).map { "LP-${it.toString().padStart(2, '0')}" })
        check(inventory["native_device"]!!.jsonArray.map { it.jsonPrimitive.content } ==
            (1..5).map { "LP-N${it.toString().padStart(2, '0')}" })
        check(inventory.text("all_actual_results") == "NOT_RUN")
    }

    internal fun fixture(name: String): JsonObject = document["fixtures"]!!.jsonObject[name]!!.jsonObject
    internal fun id(label: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        "pricing-original-workflow-v1/$label".encodeToByteArray(),
    ).toString())
    internal fun ruleId(label: String): DomainId = id(when (label) {
        "C-STD-5", "C-STD-6" -> "C-STD"
        else -> label
    })
    internal val company = CompanyScope(id("ORG-A"), id("COMP-FOOD"))
    internal val skuId = id("P-RED/V-RED")
    internal val unitId = id("BASE-EACH")
    internal val effectiveFrom = UtcInstant.parseCanonical("1970-01-01T00:00:00Z")
    internal val taxFree = TaxSnapshot(id("TAX-FREE"), Revision(5), "TAX-FREE", PercentageRate.parse("0"))

    internal fun price(label: String, revision: String, amount: String, channel: String? = null,
        priority: Int = 0, minimum: String = "0", maximum: String? = null,
        from: UtcInstant = effectiveFrom, until: UtcInstant? = null): LocalPriceV1 = LocalPriceV1(
        ruleId(label), Revision(revision.toLong()), channel, priority,
        Quantity.parse(unitId, minimum, 0), maximum?.let { Quantity.parse(unitId, it, 0) },
        from, until, UnitPrice.parse(Currency.EGP, amount), TaxInclusion.EXCLUSIVE, taxFree,
    )

    internal fun contextRule(label: String): JsonObject = fixture("F-CONTEXTS")["rules"]!!.jsonArray
        .map { it.jsonObject }.single { it.text("id") == label }

    internal fun contextPrices(phase: String): List<LocalPriceV1> {
        val standard = contextRule("C-STD-5").let { price(it.text("id"), it.text("revision"), it.text("unit")) }
        fun row(label: String, channel: String? = null, priority: Int = 1,
            minimum: String = "0", maximum: String? = null,
            from: UtcInstant = effectiveFrom, until: UtcInstant? = null): LocalPriceV1 =
            contextRule(label).let { price(label, it.text("revision"), it.text("unit"), channel,
                priority, minimum, maximum, from, until) }
        return when (phase) {
            "optional channel prices disabled" -> listOf(standard)
            "enabled channel rules" -> listOf(standard, row("C-DINE-2", "DINE_IN"),
                row("C-TAKE-2", "TAKEAWAY"), row("C-DELIVERY-3", "DELIVERY"))
            "tier plus standard" -> listOf(standard, row("C-TIER-2", priority = 2,
                minimum = "5", maximum = "10"))
            "dated plus standard" -> contextRule("C-DATE-2").let { dated -> listOf(standard,
                row("C-DATE-2", priority = 2, from = UtcInstant.parseCanonical(dated.text("period_start_utc")),
                    until = UtcInstant.parseCanonical(dated.text("period_end_utc")))) }
            else -> error("Unbound category/product/variant eligibility is not a selector fixture")
        }
    }

    internal fun calculate(label: String, price: UnitPrice, quantity: Quantity,
        revision: Revision = Revision(5)): PricingSnapshot = OperationalPricingEngine.calculate(PricingRequest(
        id("snapshot/$label"), Revision(1), Currency.EGP,
        listOf(ResolvedPriceLine(id("line/$label"), id("portion/$label"), revision, quantity,
            price, TaxInclusion.EXCLUSIVE, taxFree)),
    ))

    /** Read an already frozen two-digit money oracle; this is not a pricing model. */
    internal fun expectedMoney(text: String): Money {
        check(Regex("[0-9]+\\.[0-9]{2}").matches(text))
        return Money(Currency.EGP, text.replace(".", "").toLong())
    }
}

internal fun JsonObject.text(name: String): String = this[name]!!.jsonPrimitive.content
