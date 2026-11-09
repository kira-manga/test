package com.ukkera.pos.app.gateway.independent.manageroriginalquotev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.managerorderpresentation.RejectingManagerReadJournal
import com.ukkera.pos.app.gateway.independent.managerorderpresentation.managerControlBody
import com.ukkera.pos.app.gateway.independent.managerorderreviewv1.ControlledManagerReviewView
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.pricing.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.math.BigDecimal
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.test.*

/** Unchanged semantic inputs and numeric oracles from frozen MRQ1 v1. */
internal object OriginalQuoteFrozen {
    val wire = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val declarations = wire.parseToJsonElement("""
{"ids":{"namespace":"f353d12c-b38f-4e22-92c0-52f46c9987b1","actors":{"A-original-creator":"b14b6e99-e199-5ca9-a672-1f7846d8cf89","B-current-owner":"ae057185-159a-5b45-8d12-be11ac449a42","M-manager":"8857ff79-b1f9-5fdb-a492-0564d71462ec","N-unprivileged":"9f962d87-d3d7-59ec-b046-98a49be8f6d0","H-central-human":"bf4798e8-7c51-5089-8c3b-dc7a6cc4ceba"},"organization":"65a1c481-4f0e-51a0-b371-47cf33ef3877","company":"99a7c62f-f643-5908-9fd6-3b61836f45b8","branch":"b3a07179-de89-51c8-af0e-6eaa4dee931a","other_branch":"c8102934-15c0-5c71-9f48-818ed9ca8775","other_company":"1eb8d63b-7212-5266-94c6-102c2fd8e6f0","unrelated_org":"3b24850a-f88a-56ab-bd24-f6c4d0e43089","admitted_device":"a44a6040-4d9d-5fac-8f8f-1cc9c2b0fd7a","retired_device":"10bb2394-5f3c-5b4c-a204-9bbd3498050f","current_session":"07585786-06f5-5c23-9470-57fe2b822e11","retired_session":"64b643af-cc6a-52a3-bd4d-612ebce30bef","next_user_session":"32846b7b-2430-5647-83e1-b3cbad624eca","original_unknown_operation":"46444ad6-3e79-5d64-8ff9-195008a363dc","original_cash_session":"b5639565-8bb7-5946-8cfb-33236754eead","B_cash_session":"ba3114de-ace8-5841-9328-09269bbd46ec"},"public_read":{"key":"sales.order.manager-quote","version":1,"authorization_action":"ORDER_REASSIGN","authorization_mode":"PERFORM","authorization_purpose":"order.manager-review","canonical_input_limit_bytes":2048,"complete_response_limit_bytes":65536},"F-ABSENT":{"order_id":"d762745f-3b38-5bfc-a754-d80391e0867b","retail_source":"GENUINELY_ABSENT","authorization_revision":"0","entries":0,"continuation":null},"F-FOODONLY":{"order_id":"6d4b8741-a7ab-5cf1-8a20-15cc6b31c0d1","retail_source":"GENUINELY_ABSENT","food_source":"PRESENT","retail_authorization_revision":"0","retail_entries":0,"continuation":null,"food_private_fields_disclosed":0},"corruptions":[{"id":"C-SNAPSHOT-ID","field":"snapshot_id","replacement":"0d646839-2d6d-5a00-871a-9d9d92a34026","unchanged":"Selected target, quote revision7, creator, currency, every portion/quantity/component/amount remain F-EX"},{"id":"C-TARGET-ID","field":"returned_target_order_id","replacement":"bf8c35c2-ebdc-5217-8347-5b8b5f2efc15"},{"id":"C-QUOTE-REV","field":"stored_snapshot_revision","replacement":"8","target_remains":"7"},{"id":"C-CURRENCY","field":"snapshot_currency","replacement":"USD","target_remains":"EGP"},{"id":"C-FINAL","field":"snapshot_total_minor","replacement":"12313","target_remains":"12312"},{"id":"C-BEFORE","field":"snapshot_before_payable_minor","replacement":"13681","target_remains":"13680"},{"id":"C-DISCOUNT","field":"snapshot_payable_reduction_minor","replacement":"1369","target_remains":"1368"},{"id":"C-QUANTITY","field":"snapshot_portion_quantity","replacement":"2","target_remains":"1"},{"id":"C-DUPLICATE-PORTION","change":"Repeat the F-EX sole portion ID twice; do not deduplicate to accept"},{"id":"C-MISSING-PORTION","change":"Remove sole F-EX snapshot portion, target retains it"},{"id":"C-POSTED-TAX","field":"snapshot_item_tax_minor","replacement":"1513","target_remains":"1512"},{"id":"C-INCOMPLETE","change":"Present retail target with missing/null/incomplete required snapshot"},{"id":"C-MISSING-CREATOR","change":"Present retail source missing its actual original creator; no accepting default"},{"id":"C-UNJOINED","change":"Present target with required actual source join missing"},{"id":"C-PROFILE","change":"Missing, contradictory or unsupported explicit stock profile; never infer legacy"}],"response_variants":[{"id":"R-OTHER-TARGET","entries":1,"order_id":"bf8c35c2-ebdc-5217-8347-5b8b5f2efc15"},{"id":"R-TWO-ENTRIES","entries":2},{"id":"R-CURSOR","entries":1,"continuation":"nonempty-cursor"},{"id":"R-PARTIAL","entries":1,"snapshot":null},{"id":"R-SNAPSHOT-ID","corruption":"C-SNAPSHOT-ID"},{"id":"R-EMPTY","entries":0,"continuation":null,"authorized":true}],"F-LONG-RATIO":{"fixture_id":"F-LONG-RATIO","input_seam":"Direct public exact-ratio display seam; no fabricated whole native pricing snapshot","numerator":"1000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000","denominator":"1000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001","short_ratio_control":"512500/57","numerator_digits":1024,"denominator_digits":1024,"combined_ratio_characters":2049,"expected_fields":[{"meaning":"Numerator / البسط","value":"1000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"},{"meaning":"Denominator / المقام","value":"1000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000001"}],"short_expected_single_field":"512500/57","postings":"No posted money recalculation, whole-source validity or native snapshot claim is made by this display-only fixture"},"snapshots":{"F-EX":{"fixture_id":"F-EX","target_order_id":"6e7d6613-f90e-57c4-915a-226d1509af4a","selector_semantic":{"orderId":"6e7d6613-f90e-57c4-915a-226d1509af4a"},"proposed_canonical_selector_UTF8":"{\"orderId\":\"6e7d6613-f90e-57c4-915a-226d1509af4a\"}","binding_note":"Semantic fixture, not a claim that these are production schema field names. Bind existing typed selector, snapshot schema, profile and enum literals under B01/B02 before execution; no new calculation or weaker expected values.","required_stock_profiles":["NORMALIZED","LEGACY_BOUNDED"],"stored_order_business_revision":"17","driver_storage_generation":"91","snapshot_id":"6e7d6613-f90e-57c4-915a-226d1509af4a","target_quote_revision":"7","stored_snapshot_revision":"7","currency":"EGP","actual_original_creator":"b14b6e99-e199-5ca9-a672-1f7846d8cf89","current_owner":"ae057185-159a-5b45-8d12-be11ac449a42","operational_pricing_profile_literal":"B02-existing-supported-stored-literal","posting_profile_literal":"B02-existing-supported-half-up-component-boundary-literal","portions":[{"portion_id":"8f0e4b44-b768-5adf-a4c2-d04152d0a150","line_id":"a1ce6894-2642-5829-9128-bdba2814e137","sku_id":"5d713975-8c98-5519-9b09-d06ab4fd0ceb","quantity_exact_decimal":"1","unit_price_exact_EGP":"120.00","inclusion_mode":"EXCLUSIVE","price_context_revision":"5","tax_policy_id":"e84a09a6-5725-51ce-9317-f2e2a59d897a","tax_policy_revision":"4","tax_category":"SYNTHETIC-ILLUSTRATIVE-14","tax_rate":{"numerator":"7","denominator":"50","meaning":"fraction of merchandise net"},"original_item_net_exact_minor":{"numerator":"12000","denominator":"1","unit":"EGP_MINOR"},"original_tax_exact_minor":{"numerator":"1680","denominator":"1","unit":"EGP_MINOR"},"discounted_item_net_exact_minor":{"numerator":"10800","denominator":"1","unit":"EGP_MINOR"},"discounted_tax_exact_minor":{"numerator":"1512","denominator":"1","unit":"EGP_MINOR"},"original_posted_components_minor":{"ITEM_NET":"12000","ITEM_TAX":"1680"},"posted_components_minor":{"ITEM_NET":"10800","ITEM_TAX":"1512"},"posted_payable_minor":"12312","discount_allocations":[{"allocation_id":"066fa0ab-b4e7-553d-8b74-c6a05b8d6f21","definition":{"kind":"PERCENTAGE_ITEM_NET","rate":{"numerator":"1","denominator":"10"}},"item_net_reduction_exact_minor":{"numerator":"1200","denominator":"1","unit":"EGP_MINOR"},"item_tax_reduction_exact_minor":{"numerator":"168","denominator":"1","unit":"EGP_MINOR"},"payable_reduction_minor":"1368","posted_item_net_reduction_minor":"1200","posted_item_tax_reduction_minor":"168"}]}],"charges":[],"summary":{"before_discount_payable_minor":"13680","advertised_payable_reduction_minor":"1368","exact_final_payable_minor":"12312"},"document":{"discounted_item_net_exact_minor":{"numerator":"10800","denominator":"1","unit":"EGP_MINOR"},"posted_item_net_minor":"10800","posted_total_tax_minor":"1512","posted_total_minor":"12312"},"original_human_item_variant_modifier_names":null,"disclosure_meanings":{"pricing":"COMPLETE","original_creator":"SUPPLIED","complete_ownership_history":"NOT_SUPPLIED","previous_collectors":"NOT_SUPPLIED","native_cash_custody":"NOT_SUPPLIED","unresolved_payment_facts":"NOT_SUPPLIED","original_human_selection_descriptions":"NOT_SUPPLIED","transfer_eligibility":"NOT_ESTABLISHED"}},"F-SCALE":{"fixture_id":"F-SCALE","target_order_id":"6a529e28-e77c-5987-9118-9cbef5b7e17a","selector_semantic":{"orderId":"6a529e28-e77c-5987-9118-9cbef5b7e17a"},"proposed_canonical_selector_UTF8":"{\"orderId\":\"6a529e28-e77c-5987-9118-9cbef5b7e17a\"}","binding_note":"Semantic fixture, not a claim that these are production schema field names. Bind existing typed selector, snapshot schema, profile and enum literals under B01/B02 before execution; no new calculation or weaker expected values.","required_stock_profiles":["NORMALIZED","LEGACY_BOUNDED"],"stored_order_business_revision":"9007199254740993","driver_storage_generation":"9007199254741001","snapshot_id":"6a529e28-e77c-5987-9118-9cbef5b7e17a","target_quote_revision":"9007199254740995","stored_snapshot_revision":"9007199254740995","currency":"EGP","actual_original_creator":"b14b6e99-e199-5ca9-a672-1f7846d8cf89","current_owner":"ae057185-159a-5b45-8d12-be11ac449a42","operational_pricing_profile_literal":"B02-existing-supported-stored-literal","posting_profile_literal":"B02-existing-supported-half-up-component-boundary-literal","portions":[{"portion_id":"84602023-0f1e-5cca-b6ab-cd5d945b71f9","line_id":"2f31f7f6-ea8a-5b1b-abba-4bc4889347ba","sku_id":"4dd4597c-ebf4-53ea-b455-567ca6417cf9","quantity_exact_decimal":"0.123456","unit_price_exact_EGP":"1.23456789","inclusion_mode":"EXCLUSIVE","price_context_revision":"5","tax_policy_id":"e84a09a6-5725-51ce-9317-f2e2a59d897a","tax_policy_revision":"4","tax_category":"SYNTHETIC-ILLUSTRATIVE-14","tax_rate":{"numerator":"7","denominator":"50","meaning":"fraction of merchandise net"},"original_item_net_exact_minor":{"numerator":"238148145981","denominator":"15625000000","unit":"EGP_MINOR"},"original_tax_exact_minor":{"numerator":"1667037021867","denominator":"781250000000","unit":"EGP_MINOR"},"discounted_item_net_exact_minor":{"numerator":"238148145981","denominator":"15625000000","unit":"EGP_MINOR"},"discounted_tax_exact_minor":{"numerator":"1667037021867","denominator":"781250000000","unit":"EGP_MINOR"},"original_posted_components_minor":{"ITEM_NET":"15","ITEM_TAX":"2"},"posted_components_minor":{"ITEM_NET":"15","ITEM_TAX":"2"},"posted_payable_minor":"17","discount_allocations":[]}],"charges":[],"summary":{"before_discount_payable_minor":"17","advertised_payable_reduction_minor":"0","exact_final_payable_minor":"17"},"document":{"discounted_item_net_exact_minor":{"numerator":"238148145981","denominator":"15625000000","unit":"EGP_MINOR"},"posted_item_net_minor":"15","posted_total_tax_minor":"2","posted_total_minor":"17"},"original_human_item_variant_modifier_names":null,"disclosure_meanings":{"pricing":"COMPLETE","original_creator":"SUPPLIED","complete_ownership_history":"NOT_SUPPLIED","previous_collectors":"NOT_SUPPLIED","native_cash_custody":"NOT_SUPPLIED","unresolved_payment_facts":"NOT_SUPPLIED","original_human_selection_descriptions":"NOT_SUPPLIED","transfer_eligibility":"NOT_ESTABLISHED"}}}}
    """.trimIndent()).jsonObject
    fun fixture(name: String): JsonObject = declarations.obj("snapshots").obj(name)
    fun declaration(name: String): JsonObject = declarations.obj(name)
    val exclusive = fixture("F-EX")
    val scale = fixture("F-SCALE")
    val ids = declarations.obj("ids")
    val scope = BranchScope(ids.id("organization"), ids.id("company"), ids.id("branch"))
    val manager = ids.obj("actors").id("M-manager")
    val creator = ids.obj("actors").id("A-original-creator")
    val owner = ids.obj("actors").id("B-current-owner")
    val nextActor = ids.obj("actors").id("N-unprivileged")
    val device = ids.id("admitted_device")
    val session = ids.id("current_session")
    val nextSession = ids.id("next_user_session")
    val quoteKey = ReadKey(declarations.obj("public_read").text("key"), declarations.obj("public_read").long("version").toInt())
    val legacyKey = ReadKey("sales.order.manager-review", 1)
    val ownerKeys = setOf(ReadKey("sales.order.read", 1), ReadKey("sales.order.read", 2))
    val corruptions = declarations.array("corruptions").map { it.jsonObject }
    val observedAt = UtcInstant.parseCanonical("2026-10-30T09:00:00Z")

    // UOM, warehouse, connection and rule revision are schema coordinates, not frozen oracles.
    fun coordinate(ordinal: Int) = DomainId("7f460000-0000-4000-8000-" + ordinal.toString().padStart(12, '0'))
    fun query(fixture: JsonObject = exclusive) = LocalManagerOrderReviewQueryV1(fixture.id("target_order_id"))
    fun workflow(fixture: JsonObject = exclusive) = LocalWorkflowQuery(LocalWorkflow.ORDERS, "manager:" + fixture.text("target_order_id"))
    fun quantity(portion: JsonObject): Quantity {
        val value = portion.text("quantity_exact_decimal")
        val scale = value.substringAfter('.', "").length
        return Quantity.parse(coordinate(1), value, scale)
    }
    fun percentage(ratio: JsonObject): PercentageRate = PercentageRate.parse(
        BigDecimal(ratio.text("numerator")).multiply(BigDecimal("100"))
            .divide(BigDecimal(ratio.text("denominator"))).toPlainString())

    /** Exact dimensional conversion, EGP_MINOR / 100 = EGP; no posting or rounding. */
    fun major(minor: JsonObject): ExactRational {
        require(minor.text("unit") == "EGP_MINOR")
        return ExactRational.ratio(minor.long("numerator"), minor.long("denominator")) / ExactRational.ratio(100, 1)
    }

    fun quote(fixture: JsonObject = exclusive): PricingSnapshot {
        val currency = Currency.valueOf(fixture.text("currency"))
        val discounted = fixture.array("portions").any { it.jsonObject.array("discount_allocations").isNotEmpty() }
        // Explicit synthetic setup ceilings in MRQ1 v1: amount 2000, percentage 15.
        val limits = if (discounted) DiscountLimits(Money(currency, 2000), PercentageRate.parse("15"))
            else DiscountLimits.none(currency)
        val lines = fixture.array("portions").map { item ->
            val portion = item.jsonObject
            ResolvedPriceLine(portion.id("line_id"), portion.id("portion_id"), Revision(portion.long("price_context_revision")),
                quantity(portion), UnitPrice.parse(currency, portion.text("unit_price_exact_EGP")),
                TaxInclusion.valueOf(portion.text("inclusion_mode")),
                TaxSnapshot(portion.id("tax_policy_id"), Revision(portion.long("tax_policy_revision")),
                    portion.text("tax_category"), percentage(portion.obj("tax_rate"))), limits)
        }
        val adjustments = fixture.array("portions").flatMap { item ->
            val portion = item.jsonObject
            portion.array("discount_allocations").map { allocationValue ->
                val allocation = allocationValue.jsonObject
                PriceAdjustment(allocation.id("allocation_id"), Revision(1), AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT,
                    DiscountValue.Percentage(percentage(allocation.obj("definition").obj("rate"))),
                    listOf(EligibleDiscountPortion(portion.id("portion_id"))), limits)
            }
        }
        return OperationalPricingEngine.calculate(PricingRequest(fixture.id("snapshot_id"),
            Revision(fixture.long("stored_snapshot_revision")), currency, lines,
            adjustments = adjustments, discountLimits = limits))
    }

    fun selection(fixture: JsonObject = exclusive) = LocalOrderSelectionV1(
        LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER, Currency.valueOf(fixture.text("currency")), Revision(5),
        fixture.array("portions").map { item ->
            val portion = item.jsonObject
            LocalOrderLineV1(portion.id("line_id"), portion.id("portion_id"), portion.id("sku_id"), quantity(portion))
        })

    fun entry(fixture: JsonObject = exclusive, profile: LocalOrderStockProfileV2 = LocalOrderStockProfileV2.LEGACY_BOUNDED): LocalManagerOriginalQuote {
        val quote = quote(fixture)
        val summary = fixture.obj("summary")
        val target = LocalOrderViewV1(fixture.id("target_order_id"), fixture.id("current_owner"),
            Revision(fixture.long("stored_order_business_revision")), Revision(fixture.long("target_quote_revision")), selection(fixture),
            Money(quote.currency, summary.long("before_discount_payable_minor")),
            Money(quote.currency, summary.long("advertised_payable_reduction_minor")),
            Money(quote.currency, summary.long("exact_final_payable_minor")),
            quote.lines.map { LocalOrderPriceLineV1(it.portionId, it.quantity, it.payable) },
            quote.components.map { LocalOrderComponentV1(it.key, it.portionId, it.chargeId, it.kind.name, it.posted, it.tax.categoryCode) },
            null, null, null, null)
        return LocalManagerOriginalQuote(LocalProfiledOrderV2(target, profile), fixture.id("actual_original_creator"),
            StoredPricingSnapshotCodec.encode(quote))
    }

    fun hint(target: DomainId = exclusive.id("target_order_id"), permission: Permission = Permission(SecurityAction.ORDER_REASSIGN, PermissionVerb.PERFORM),
        grantScope: BranchScope = scope) = PermissionGrant(permission, grantScope, GrantEffect.ALLOW,
            namedSubjectId = manager, targetIds = setOf(target))

    fun sessionView(target: DomainId = exclusive.id("target_order_id"), grants: List<PermissionGrant> = listOf(hint(target))) =
        LocalSessionView(manager, scope, device, session, SessionGeneration(1), LocalLoginIdentifier("Synthetic manager"),
            ActivatedLocalPolicy(scope, manager, Revision(1), grants, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(coordinate(2), 1), MonotonicMoment(coordinate(2), 2))
}

internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.long(key: String): Long = text(key).toLong()
internal fun JsonObject.id(key: String): DomainId = DomainId(text(key))
internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonObject.changed(key: String, value: JsonElement): JsonObject = JsonObject(toMutableMap().also { it[key] = value })
internal fun JsonObject.without(key: String): JsonObject = JsonObject(toMutableMap().also { it.remove(key) })
internal fun <T> controlElement(value: T, serializer: KSerializer<T>): JsonElement = OriginalQuoteFrozen.wire.encodeToJsonElement(serializer, value)
internal fun quoteData(value: LocalManagerOriginalQuoteResult): ReadReply.Data =
    ReadReply.Data(managerControlBody(value, LocalManagerOriginalQuoteResult.serializer()), Revision(1), OriginalQuoteFrozen.observedAt)
internal fun quoteData(value: JsonElement): ReadReply.Data =
    ReadReply.Data(managerControlBody(value, JsonElement.serializer()), Revision(1), OriginalQuoteFrozen.observedAt)
internal fun legacyData(value: LocalOrderReadResultV2): ReadReply.Data =
    ReadReply.Data(managerControlBody(value, LocalOrderReadResultV2.serializer()), Revision(1), OriginalQuoteFrozen.observedAt)
internal fun fullQuoteData(): ReadReply.Data = quoteData(LocalManagerOriginalQuoteResult(listOf(OriginalQuoteFrozen.entry())))

/** Expected economic values come only from the semantic fixture, never calculator output. */
internal fun assertFrozenQuote(actual: LocalManagerOriginalQuote, fixture: JsonObject = OriginalQuoteFrozen.exclusive) {
    val quote = StoredPricingSnapshotCodec.decode(actual.quoteSnapshot)
    val summary = fixture.obj("summary")
    val currency = Currency.valueOf(fixture.text("currency"))
    assertEquals(currency, quote.currency)
    assertEquals(currency, actual.target.order.selection.currency)
    assertEquals(fixture.id("target_order_id"), actual.target.order.orderId)
    assertEquals(fixture.id("snapshot_id"), quote.snapshotId)
    assertEquals(fixture.id("actual_original_creator"), actual.originalCreatorId)
    assertEquals(fixture.id("current_owner"), actual.target.order.ownerId)
    assertNotEquals(actual.originalCreatorId, actual.target.order.ownerId)
    assertEquals(Revision(fixture.long("stored_order_business_revision")), actual.target.order.revision)
    assertEquals(Revision(fixture.long("stored_snapshot_revision")), quote.revision)
    assertEquals(Revision(fixture.long("target_quote_revision")), actual.target.order.quoteRevision)
    assertEquals(Money(currency, summary.long("before_discount_payable_minor")), quote.beforeDiscountPayable)
    assertEquals(Money(currency, summary.long("advertised_payable_reduction_minor")), quote.advertisedDiscount)
    assertEquals(Money(currency, summary.long("exact_final_payable_minor")), quote.payable)
    assertEquals(Money(currency, summary.long("before_discount_payable_minor")), actual.target.order.beforeDiscount)
    assertEquals(Money(currency, summary.long("advertised_payable_reduction_minor")), actual.target.order.discount)
    assertEquals(Money(currency, summary.long("exact_final_payable_minor")), actual.target.order.payable)
    assertEquals(fixture.array("portions").size, quote.lines.size)
    fixture.array("portions").forEach { item ->
        val frozen = item.jsonObject
        val line = quote.lines.single { it.portionId == frozen.id("portion_id") }
        assertEquals(frozen.id("line_id"), line.lineId)
        assertEquals(OriginalQuoteFrozen.quantity(frozen), line.quantity)
        assertEquals(UnitPrice.parse(currency, frozen.text("unit_price_exact_EGP")), line.unitPrice)
        assertEquals(TaxInclusion.valueOf(frozen.text("inclusion_mode")), line.inclusion)
        assertEquals(Revision(frozen.long("price_context_revision")), line.priceRevision)
        assertEquals(frozen.id("tax_policy_id"), line.tax.policyId)
        assertEquals(Revision(frozen.long("tax_policy_revision")), line.tax.revision)
        assertEquals(frozen.text("tax_category"), line.tax.categoryCode)
        assertEquals(ExactRational.ratio(frozen.obj("tax_rate").long("numerator"), frozen.obj("tax_rate").long("denominator")), line.tax.rate.toFraction())
        assertEquals(OriginalQuoteFrozen.major(frozen.obj("original_item_net_exact_minor")), line.exactOriginalItemNet)
        assertEquals(OriginalQuoteFrozen.major(frozen.obj("discounted_item_net_exact_minor")), line.exactItemNet)
        assertEquals(Money(currency, frozen.long("posted_payable_minor")), line.payable)
        val components = quote.components.filter { it.portionId == line.portionId }
        for ((kind, amount) in frozen.obj("posted_components_minor")) {
            val component = components.single { it.kind.name == kind }
            assertEquals(Money(currency, amount.jsonPrimitive.content.toLong()), component.posted)
            assertEquals(Money(currency, frozen.obj("original_posted_components_minor").long(kind)), component.postedBeforeDiscounts)
            val before = if (kind == "ITEM_NET") "original_item_net_exact_minor" else "original_tax_exact_minor"
            val after = if (kind == "ITEM_NET") "discounted_item_net_exact_minor" else "discounted_tax_exact_minor"
            assertEquals(OriginalQuoteFrozen.major(frozen.obj(before)), component.exactBeforeDiscounts)
            assertEquals(OriginalQuoteFrozen.major(frozen.obj(after)), component.exact)
        }
        assertEquals(frozen.array("discount_allocations").size, quote.discounts.size)
        for (allocationValue in frozen.array("discount_allocations")) {
            val allocation = allocationValue.jsonObject
            val discount = quote.discounts.single { it.adjustmentId == allocation.id("allocation_id") }
            assertEquals(Money(currency, allocation.long("payable_reduction_minor")), discount.advertisedPayableReduction)
            val net = discount.allocations.single { it.kind == PriceComponentKind.ITEM_NET }
            val tax = discount.allocations.single { it.kind == PriceComponentKind.ITEM_TAX }
            assertEquals(OriginalQuoteFrozen.major(allocation.obj("item_net_reduction_exact_minor")), net.exactReduction)
            assertEquals(OriginalQuoteFrozen.major(allocation.obj("item_tax_reduction_exact_minor")), tax.exactReduction)
            assertEquals(Money(currency, allocation.long("posted_item_net_reduction_minor")), net.advertisedReduction)
            assertEquals(Money(currency, allocation.long("posted_item_tax_reduction_minor")), tax.advertisedReduction)
            assertNotEquals(net.advertisedReduction.minor, discount.advertisedPayableReduction.minor)
        }
    }
}

@Serializable private data class QuoteSourceScope(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId)
@Serializable private data class QuoteLegacyAllocation(val allocationId: DomainId, val portionId: DomainId, val companyId: DomainId,
    val branchId: DomainId, val skuId: DomainId, val warehouseId: DomainId, val supplierOwnerId: DomainId?, val lotId: DomainId?,
    val custody: String, val quantity: Quantity)
@Serializable private data class QuoteSourceState(val originalCreatorId: DomainId, val ownerId: DomainId, val quote: String,
    val stockRequirements: List<QuoteLegacyAllocation>, val handoff: String?, val collectionId: DomainId?, val paidSaleId: DomainId?)
@Serializable private data class QuoteSourceEnvelope(val schemaVersion: Int, val kind: String, val scope: QuoteSourceScope,
    val id: DomainId, val revision: Revision, val state: QuoteSourceState)
@Serializable private data class QuoteSourceContext(val scope: QuoteSourceScope, val orderId: DomainId, val revision: Revision,
    val quoteRevision: Revision, val selection: LocalOrderSelectionV1)
@Serializable private data class QuoteSourceWork(val scope: QuoteSourceScope, val id: DomainId, val revision: Revision,
    val orderIds: List<DomainId>, val returnIds: List<DomainId> = emptyList(), val pendingClaimIds: List<DomainId> = emptyList())

internal data class ControlledQuoteSource(val order: JsonObject, val context: JsonObject, val work: JsonObject) {
    fun view(orderOverride: JsonObject = order, includeContext: Boolean = true): ControlledManagerReviewView {
        val f = OriginalQuoteFrozen.exclusive
        val rows = listOfNotNull(
            row(LocalSalesStoreProfile.ORDER_KIND, f.id("target_order_id"), Revision(f.long("stored_order_business_revision")), orderOverride),
            if (includeContext) row(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, f.id("target_order_id"), Revision(f.long("stored_order_business_revision")), context) else null,
            row(LocalSalesStoreProfile.WORK_KIND, OriginalQuoteFrozen.owner, Revision(1), work))
        return ControlledManagerReviewView(scope = OriginalQuoteFrozen.scope, rows = rows, claims = mapOf(
            LocalSalesStoreProfile.unique("order", f.id("target_order_id")) to f.id("target_order_id"),
            ClaimKey(LocalSalesStoreProfile.CONFIGURED_NAMESPACE, LocalSalesStoreProfile.CUSTODIAN_KIND + "/" + OriginalQuoteFrozen.owner.value) to OriginalQuoteFrozen.owner))
    }
    private fun row(kind: String, id: DomainId, revision: Revision, value: JsonObject): StoredAggregate {
        val payload = StoredBytes(value.toString().encodeToByteArray())
        // This adapter coordinate does not establish the frozen actual driver-generation 91.
        return StoredAggregate(LocalSalesStoreProfile.key(kind, id), SalesStorageRevision.toStorage(revision),
            LocalSalesStoreProfile.schema(kind), payload, sha256(payload.copyBytes()))
    }
}
internal fun controlledQuoteSource(): ControlledQuoteSource {
    val f = OriginalQuoteFrozen.exclusive
    val scope = OriginalQuoteFrozen.scope.let { QuoteSourceScope(it.organizationId, it.companyId, it.branchId) }
    val quote = OriginalQuoteFrozen.quote()
    val allocations = f.array("portions").mapIndexed { index, item ->
        val p = item.jsonObject
        QuoteLegacyAllocation(OriginalQuoteFrozen.coordinate(10 + index), p.id("portion_id"), scope.companyId, scope.branchId,
            p.id("sku_id"), OriginalQuoteFrozen.coordinate(20), null, null, StockCustody.RELEASED.name, OriginalQuoteFrozen.quantity(p))
    }
    val source = QuoteSourceEnvelope(SalesSnapshotCodec.SCHEMA_VERSION, SalesSnapshotKind.ORDER.name, scope,
        f.id("target_order_id"), Revision(f.long("stored_order_business_revision")),
        QuoteSourceState(OriginalQuoteFrozen.creator, OriginalQuoteFrozen.owner, StoredPricingSnapshotCodec.encode(quote),
            allocations, null, null, null))
    val envelope = OriginalQuoteFrozen.wire.encodeToString(QuoteSourceEnvelope.serializer(), source)
    val restored = SalesSnapshotCodec.decodeOrder(envelope, SalesSnapshotBinding(SalesSnapshotKind.ORDER,
        OriginalQuoteFrozen.scope, source.id, source.revision))
    val context = QuoteSourceContext(scope, source.id, source.revision, quote.revision, OriginalQuoteFrozen.selection())
    val work = QuoteSourceWork(scope, OriginalQuoteFrozen.owner, Revision(1), listOf(source.id))
    return ControlledQuoteSource(
        OriginalQuoteFrozen.wire.parseToJsonElement(SalesSnapshotCodec.encodeOrder(restored)).jsonObject,
        controlElement(context, QuoteSourceContext.serializer()).jsonObject,
        controlElement(work, QuoteSourceWork.serializer()).jsonObject)
}

internal data class ControlledPreparedQuote(val prepared: PreparedRead, val context: ReadContext, val query: LocalManagerOrderReviewQueryV1)
internal suspend fun prepareQuote(view: CommandReadView = controlledQuoteSource().view(), target: DomainId = OriginalQuoteFrozen.exclusive.id("target_order_id")): ControlledPreparedQuote {
    val handler = LocalManagerOriginalQuoteReadHandler()
    val query = LocalManagerOrderReviewQueryV1(target)
    val normalized = handler.normalize(LocalManagerOrderReviewCodec.encode(query))
    val context = ReadContext(view.scope, OriginalQuoteFrozen.manager, handler.key, queryDigest(view.scope, OriginalQuoteFrozen.manager, handler.key, normalized))
    return ControlledPreparedQuote(handler.prepare(normalized, view, context), context, query)
}

/** Scripted identity/policy seam; this does not qualify actual current Master authority. */
internal object QuoteControlAuthority {
    suspend fun principal(): LocalEmployeePrincipal {
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials) = VerifiedLocalIdentity(
                OriginalQuoteFrozen.manager, OriginalQuoteFrozen.scope, OriginalQuoteFrozen.device, OriginalQuoteFrozen.session,
                SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val bearer = OpaqueCredential(ByteArray(16))
        return try { assertNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(LocalGatewayCredentials(OriginalQuoteFrozen.coordinate(3), bearer))) }
        finally { bearer.destroy() }
    }
    fun snapshot(principal: LocalEmployeePrincipal, grants: List<PermissionGrant>) = AuthorizationSnapshot(
        principal.actorId, PrincipalKind.LOCAL_EMPLOYEE, principal.scope, principal.authenticationId, principal.generation,
        principal.deviceId, principal.admissionRevision, Revision(1), accountEnabled = true, sessionActive = true,
        deviceAdmitted = true, authenticationPurpose = AuthenticationPurpose.INTERACTIVE, grants = grants)
    fun proof(principal: LocalEmployeePrincipal, requirement: AuthorizationRequirement): AuthorizedAction =
        assertIs<AuthorizationDecision.Allowed>(AuthorizationEvaluator.evaluate(principal,
            snapshot(principal, listOf(OriginalQuoteFrozen.hint(assertNotNull(requirement.binding).documentId, requirement.permission))), requirement)).authorization
}

/** Actual gateway; session, reply and credential-lifetime inputs are controlled. */
internal class OriginalQuoteGatewayControl(
    val fixture: JsonObject = OriginalQuoteFrozen.exclusive,
    reads: Set<ReadKey> = setOf(OriginalQuoteFrozen.quoteKey, OriginalQuoteFrozen.legacyKey) + OriginalQuoteFrozen.ownerKeys,
    grants: List<PermissionGrant> = listOf(OriginalQuoteFrozen.hint(fixture.id("target_order_id"))),
    response: suspend (ReadRequest) -> ReadReply = { fullQuoteData() },
) {
    val journal = RejectingManagerReadJournal(LocalClientIdentity(OriginalQuoteFrozen.scope, OriginalQuoteFrozen.manager, OriginalQuoteFrozen.device))
    val endpoint = QuoteControlEndpoint(OriginalQuoteFrozen.sessionView(fixture.id("target_order_id"), grants), response)
    var leaseCurrent = true
    var attachedLease = OriginalQuoteFrozen.session
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        val captured = attachedLease
        LocalWorkflowCredentialLease(LocalGatewayCredentials(OriginalQuoteFrozen.coordinate(3), OpaqueCredential(ByteArray(16)))) {
            leaseCurrent && captured == attachedLease && captured == OriginalQuoteFrozen.session
        }
    }, LocalWorkflowInstallation(emptySet(), reads))

    fun assertCalls(keys: List<ReadKey>) {
        assertEquals(keys, endpoint.keys)
        assertEquals(List(keys.size) { OriginalQuoteFrozen.query(fixture) }, endpoint.selectors)
        assertEquals(List(keys.size) { setOf("orderId") }, endpoint.fields)
        assertReadOnly()
    }
    fun assertReadOnly() {
        assertTrue(journal.calls.isEmpty(), "No business journal, staging, recovery or acknowledgement calls")
        assertTrue(endpoint.rejected.isEmpty(), "No mutation, lookup, authentication or unrelated endpoint call")
    }
}
internal class QuoteControlEndpoint(val view: LocalSessionView, private val response: suspend (ReadRequest) -> ReadReply) : LocalMasterClient {
    val keys = mutableListOf<ReadKey>()
    val selectors = mutableListOf<LocalManagerOrderReviewQueryV1>()
    val fields = mutableListOf<Set<String>>()
    val rejected = mutableListOf<String>()
    private fun reject(name: String): Nothing { rejected += name; throw AssertionError("Unexpected controlled endpoint call: " + name) }
    override val health: StateFlow<LocalMasterEndpointHealth> get() = reject("health")
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = LocalSessionViewResult.Current(view)
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        keys += request.query
        val bytes = request.payload.copyBytes()
        try {
            fields += OriginalQuoteFrozen.wire.parseToJsonElement(bytes.decodeToString()).jsonObject.keys
            selectors += OriginalQuoteFrozen.wire.decodeFromString(LocalManagerOrderReviewQueryV1.serializer(), bytes.decodeToString())
        } finally { bytes.fill(0) }
        return response(request)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply = reject("execute")
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply = reject("lookup")
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray, explicitDeviceMove: Boolean): LocalAuthenticationResult = reject("signIn")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = reject("refresh")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = reject("logout")
}
