package com.ukkera.pos.app.gateway.independent.immutablesalesdocuments

import com.ukkera.pos.sales.store.LocalOrderComponentV1
import com.ukkera.pos.sales.store.LocalOrderPriceLineV1
import com.ukkera.pos.sales.store.LocalPaidSaleViewV1
import com.ukkera.pos.sales.store.LocalRefundObligationViewV1
import com.ukkera.pos.sales.store.LocalTenderViewV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*

/** Executor bindings to the unchanged immutable-sales-documents v1 oracle.
 * These partial wire inputs are not authentic committed originals or complete documents.
 * No original company label, document number, actor, date or legal fact is defaulted.
 */
internal object ImmutableSalesDocumentSupportingInputs {
    private const val FREEZE = "qa/pos/designs/immutable-sales-documents/fixtures.json"
    private val fixtures: JsonObject by lazy {
        val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve(FREEZE) }.firstOrNull { Files.isRegularFile(it) }
            ?: error("Missing frozen immutable-sales-documents v1 fixtures")
        val document = Json.parseToJsonElement(Files.readString(file)).jsonObject
        check(document.getValue("version").jsonPrimitive.int == 1)
        document.getValue("fixtures").jsonObject
    }
    val scope: BranchScope get() = fixture("F-SCOPE").let {
        BranchScope(DomainId(it.text("organization_a")), DomainId(it.text("company_a")), DomainId(it.text("branch_a")))
    }
    // This is the viewing session, never the original operator or actual collector.
    val viewer: DomainId get() = DomainId(fixture("F-SCOPE").obj("other_view_or_copy_operator").text("id"))
    val policyRevision: Revision get() = Revision(fixture("F-AUTH").obj("policy_versions").text("initial").toLong())
    val unit: DomainId = bindingId("portion-unit")

    fun bindingId(alias: String): DomainId = DomainId(UUID.nameUUIDFromBytes(
        ("immutable-sales-documents-v1:" + alias).encodeToByteArray()).toString())
    fun fixture(name: String): JsonObject = fixtures.getValue(name).jsonObject
    fun money(minor: String): Money = Money(Currency.EGP, minor.toLong())
    fun moneyText(minor: Long): String = BigDecimal.valueOf(minor).movePointLeft(2).setScale(2).toPlainString() + " EGP"
    fun quantityText(value: Quantity): String = BigDecimal.valueOf(value.scaled).movePointLeft(value.scale)
        .setScale(value.scale).toPlainString() + " · " + value.unitId.value

    fun mixed(): ImmutableSalesDocumentSupportingSource {
        val f = fixture("F-MIXED")
        val lines = f.array("lines").map { it.jsonObject }.map { line ->
            LocalOrderPriceLineV1(DomainId(line.text("line_id")), Quantity.parse(unit, line.text("quantity"), 0),
                money(line.text("paid_allocation_minor")))
        }
        val components = buildList {
            f.array("lines").map { it.jsonObject }.forEach { line ->
                val portion = DomainId(line.text("line_id"))
                add(component(line.text("line_alias") + ":net", portion, null, "ITEM_NET", line.text("net_minor"), "SYNTH14"))
                add(component(line.text("line_alias") + ":tax", portion, null, "ITEM_TAX", line.text("tax_minor"), "SYNTH14"))
            }
            f.array("charges").map { it.jsonObject }.forEach { charge ->
                val alias = charge.text("kind")
                val id = bindingId("mixed-charge:" + alias)
                add(component(alias + ":net", null, id, "CHARGE_NET", charge.text("net_minor"), "SYNTH0"))
                add(component(alias + ":tax", null, id, "CHARGE_TAX", charge.text("tax_minor"), "SYNTH0"))
            }
        }
        val tenderRows = f.array("tenders").map { it.jsonObject }
        val tenders = listOf(
            LocalRefundObligationViewV1(bindingId("mixed-cash-payment"), LocalTenderViewV1("CASH"),
                money(tenderRows[0].text("applied_minor"))),
            // IDs, revision 71 and requirement flags are explicit passthrough bindings,
            // not a claim that F-MIXED supplies those retained policy facts or evidence.
            LocalRefundObligationViewV1(bindingId("mixed-card-payment"), LocalTenderViewV1("CARD",
                bindingId("mixed-card-tender"), Revision(71), requiresTerminal = true, requiresReference = true),
                money(tenderRows[1].text("applied_minor"))),
        )
        val totals = f.obj("totals")
        return ImmutableSalesDocumentSupportingSource("F-MIXED", LocalPaidSaleViewV1(DomainId(f.text("source_sale_id")),
            money(totals.text("payable_minor")), money(totals.text("discount_minor")), money(totals.text("payable_minor")),
            lines, components, tenders), tenderMembershipBound = true)
    }

    fun promo(): ImmutableSalesDocumentSupportingSource {
        val f = fixture("F-PROMO")
        val rows = f.array("allocations").map { it.jsonObject }
        val lines = rows.map { line -> LocalOrderPriceLineV1(bindingId(line.text("line_alias")), Quantity(unit, 1, 0),
            money(line.text("allocated_paid_minor"))) }
        val components = rows.flatMap { line ->
            val id = bindingId(line.text("line_alias"))
            listOf(component(line.text("line_alias") + ":net", id, null, "ITEM_NET", line.text("allocated_paid_minor"), "SYNTH0"),
                component(line.text("line_alias") + ":tax", id, null, "ITEM_TAX", f.text("tax_minor"), "SYNTH0"))
        }
        // F-PROMO does not supply tender membership. The DTO requires a list, so this
        // unbound control dimension is empty; it is NOT an authentic no-tender claim.
        return ImmutableSalesDocumentSupportingSource("F-PROMO", LocalPaidSaleViewV1(bindingId("promo-sale"),
            money(f.text("undiscounted_total_minor")), money(f.text("advertised_discount_minor")), money(f.text("paid_total_minor")),
            lines, components, emptyList()), tenderMembershipBound = false)
    }

    fun large(): ImmutableSalesDocumentSupportingSource {
        val f = fixture("F-LARGE")
        val recipe = f.obj("frozen_line_generation")
        val lines = (1..f.text("line_count").toInt()).map { ordinal ->
            val alias = "large-" + ordinal.toString().padStart(3, '0')
            LocalOrderPriceLineV1(bindingId(alias), Quantity.parse(unit, recipe.text("quantity_each"), 0),
                money(recipe.text("paid_minor_each")))
        }
        val components = lines.mapIndexed { index, line -> component("large-" + (index + 1).toString().padStart(3, '0') + ":net",
            line.portionId, null, "ITEM_NET", recipe.text("net_minor_each"), "SYNTH0") }
        val totals = f.obj("totals")
        // Original descriptions, per-line tax/discount/charge detail and tenders are
        // not carried by this partial wire binding. Empty tenders are unbound, not zero.
        return ImmutableSalesDocumentSupportingSource("F-LARGE", LocalPaidSaleViewV1(bindingId("large-sale"),
            money(totals.text("payable_minor")), money(totals.text("discount_minor")), money(totals.text("payable_minor")),
            lines, components, emptyList()), tenderMembershipBound = false)
    }

    fun zero(): ImmutableSalesDocumentSupportingSource {
        val f = fixture("F-ZERO")
        val portion = bindingId("zero-portion")
        // This binding does not establish the mandatory authentic zero authorization.
        return ImmutableSalesDocumentSupportingSource("F-ZERO", LocalPaidSaleViewV1(bindingId("zero-sale"),
            money(f.text("original_items_gross_minor")), money(f.text("explicit_authorized_payable_discount_minor")),
            money(f.text("posted_payable_minor")),
            listOf(LocalOrderPriceLineV1(portion, Quantity(unit, 1, 0), money(f.text("posted_payable_minor")))),
            listOf(component("zero:net", portion, null, "ITEM_NET", f.text("posted_net_minor"), "SYNTH14"),
                component("zero:tax", portion, null, "ITEM_TAX", f.text("posted_tax_minor"), "SYNTH14")),
            emptyList()), tenderMembershipBound = true)
    }

    private fun component(key: String, portion: DomainId?, charge: DomainId?, kind: String, minor: String, category: String) =
        LocalOrderComponentV1(key, portion, charge, kind, money(minor), category)
}

internal data class ImmutableSalesDocumentSupportingSource(
    val fixtureName: String,
    val value: LocalPaidSaleViewV1,
    val tenderMembershipBound: Boolean,
)

internal fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
internal fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
