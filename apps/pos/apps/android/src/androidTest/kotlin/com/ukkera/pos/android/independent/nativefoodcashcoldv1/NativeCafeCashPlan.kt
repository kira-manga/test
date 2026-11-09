package com.ukkera.pos.android.independent.nativefoodcashcoldv1

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureSetup
import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.datetime.LocalTime
import org.json.JSONObject

/** Unchanged FCC-C-01 inputs plus explicitly chosen synthetic setup selectors.
 * Setup-generated organization/company/branch/actor/device IDs are never forced to the
 * illustrative frozen IDs. Command-selected IDs below are inputs, not native authority. */
internal class NativeCafeCashPlan(val frozen: JSONObject, val protection: SelfOwnedKeyProtectionV1) {
    val cafe: JSONObject = frozen.getJSONObject("cafe")
    private val ids = frozen.getJSONObject("synthetic_ids")
    private val prices = cafe.getJSONObject("original_price_profile")
    private val observation = cafe.getJSONObject("originals").getJSONObject("observation")
    val activity = LocalSalesActivity.CAFE
    val service = LocalFoodServiceV1(LocalSalesChannel.TAKEAWAY, cafe.getString("pickup_reference"), "")
    val authorityEpoch = Revision(7) // Explicit synthetic installation input; not a frozen native binding.
    val companyName = "Synthetic Cafe CASH recovery company"
    val branchName = "Synthetic Cafe CASH recovery branch"
    val installerLogin = "fcc-c01-installer"
    val headLogin = "fcc-c01-head"
    val employeeLogin = LocalLoginIdentifier("fcc-c01-cashier")
    val effectiveFrom = UtcInstant.parseCanonical("2000-01-01T00:00:00Z")
    val calendar = BusinessCalendar(IanaTimeZone.AFRICA_CAIRO, LocalTime(4, 0),
        CutoffResolution.SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP)
    val zero = Money(Currency.EGP, 0)
    val payable = money(prices.getString("original_quote_gross_minor"))
    val net = money(prices.getString("total_net_minor"))
    val tax = money(prices.getString("total_tax_minor"))
    val portionNet = money(prices.getString("per_portion_net_minor"))
    val portionTax = money(prices.getString("per_portion_tax_minor"))
    val portionGross = money(prices.getString("per_portion_gross_minor"))
    val received = money(observation.getJSONObject("received").getString("minor"))
    val change = money(observation.getJSONObject("change_actually_given").getString("minor"))
    val openingCash = money(cafe.getJSONObject("opening_drawer").getString("minor"))
    val paidSnapshot: JSONObject = cafe.getJSONObject("snapshots").getJSONObject("paid_unsent")
    val orderId = DomainId(cafe.getString("order_id"))
    val drawerId = id("cafe_drawer")
    val cashSessionId = id("cafe_cash_session")
    val portionIds = listOf(id("cafe_portion_1"), id("cafe_portion_2"))
    val batchId = DomainId(cafe.getString("price_batch_id"))
    val latteSkuId = id("latte_small_sku")
    val recipeId = DomainId(cafe.getJSONObject("recipe").getString("id"))
    val activityPublicationId = id("cafe_profile_publication")
    val startOperationId = OperationId(cafe.getJSONObject("originals").getJSONObject("instruction")
        .getString("original_operation_id"))
    val recordingOperationId = OperationId(cafe.getJSONObject("originals").getJSONObject("application")
        .getString("links_original_observation_operation_id"))
    val quoteOperationId = OperationId(batchId.value) // Public Add binds its batch to this operation ID.
    val resolutionOperationId = operation(900)
    val applyOperationId = operation(901)
    val finalizeOperationId = operation(902)
    val collectionId = input(920)
    val attemptId = input(921)
    val masterAllocationId = input(922)
    val clientAllocationId = input(923)
    val saleId = input(924)
    val warehouseId = input(700)
    val initializationId = input(701)
    val openingApprovalId = input(702)
    val openingReceiptId = input(703)
    val stationId = input(800)
    val catalogId = input(900)
    val releasePublicationId = input(901)
    val activityProfileId = input(902)
    val cashOpeningSourceId = input(910)
    val cashOpeningEvidenceId = input(911)
    val outcomeEvidenceId = input(912)
    val sizeAxis = LocalVariantAxisV1(input(4), Code.exact("SIZE"), listOf(Code.exact(prices.getString("variant"))))
    val small = LocalVariantValueV1(sizeAxis.id, Code.exact(prices.getString("variant")))
    val choiceId = input(302)
    val routeId = input(600)
    val routeKey = input(601)
    val recipePublicationId = input(500)
    val reasonCode = "fcc.c01.synthetic.opening"
    val roles = listOf("latte", "beans", "milk")
    val stocks = listOf("beans", "milk").mapIndexed { index, role ->
        val opening = cafe.getJSONObject("opening_stock").getJSONObject(role)
        val paid = paidSnapshot.getJSONObject("stock").getJSONObject(role)
        val ingredient = cafe.getJSONObject("recipe").getJSONArray("per_portion").getJSONObject(index)
        CafeStockInput(role, id("${role}_sku"), opening.getString("unit"),
            opening.getString("on_hand").toLong(), paid.getString("reserved").toLong(),
            paid.getString("available").toLong(), money(opening.getString("carrying_value_minor")),
            ingredient.getString("quantity").toLong(), input(710 + index), input(720 + index), input(730 + index))
    }
    val openingValue = stocks.fold(zero) { sum, row -> sum + row.carryingValue }
    val warehouse = LocalWarehouseV1(warehouseId, LocalNamesV1("Synthetic ingredients", ""), true)
    val station = LocalFoodStationDefinitionV1(stationId, LocalNamesV1("Synthetic Cafe preparation", ""),
        FoodStationDeliveryMode.DISPLAY, true)
    val route = LocalFoodRecipeRouteV1(routeId, recipeId, Revision(1), activity, LocalSalesChannel.TAKEAWAY,
        warehouseId, listOf(LocalFoodRouteTargetV1(routeKey, stationId)), true)
    val openingApproval = LocalReceivingApprovalV1(openingApprovalId, LocalBasisKindV1.OPENING, null,
        effectiveFrom, null, stocks.map { row -> LocalApprovedStockLineV1(row.allocationId, row.skuId,
            warehouseId, quantity(row.role, row.onHand), row.carryingValue, false) })

    init {
        check(frozen.getString("schema_version") == "1.0.0" && frozen.getString("expectation_version") == "1.0.0" &&
            frozen.getString("status") == "NOT RUN" && frozen.getBoolean("synthetic_only"))
        check(cafe.getString("activity") == "CAFE" && cafe.getString("channel") == "TAKEAWAY_PREPAID" &&
            cafe.getString("pickup_reference") == "Synthetic pickup C-1" &&
            cafe.getInt("current_amount_read_version") == 3 && cafe.getInt("selected_page_size") == 2 &&
            cafe.getInt("complete_portion_count") == portionIds.size && portionIds.distinct().size == 2)
        val clock = frozen.getJSONObject("fixture_clock")
        check(clock.getString("branch_timezone") == calendar.zone.value && clock.getString("operational_cutoff_local") == "04:00:00")
        check(prices.getBoolean("tax_inclusive") && prices.getString("variant") == "SMALL" &&
            prices.getString("unit_price") == "57.00" && prices.getString("quantity") == "2" &&
            prices.getString("price_revision") == "1" && prices.getString("discount_minor") == "0" &&
            prices.getString("service_delivery_tip_deposit_minor") == "0" &&
            prices.getString("rounding") == "HALF_UP_AT_DECLARED_POSTING_BOUNDARY" &&
            prices.getJSONObject("tax_rate_ratio").getString("numerator") == "14" &&
            prices.getJSONObject("tax_rate_ratio").getString("denominator") == "100")
        check(net.minor == 10_000L && tax.minor == 1_400L && payable.minor == 11_400L &&
            portionNet.minor == 5_000L && portionTax.minor == 700L && portionGross.minor == 5_700L &&
            net + tax == payable && portionNet + portionTax == portionGross && portionGross.minor * 2 == payable.minor)
        check(received.minor == 20_000L && change.minor == 8_600L && received - change == payable && openingCash.minor == 100_000L)
        val instruction = cafe.getJSONObject("originals").getJSONObject("instruction")
        val application = cafe.getJSONObject("originals").getJSONObject("application")
        check(listOf(cafe.getJSONObject("opening_drawer"), instruction.getJSONObject("amount"),
            observation.getJSONObject("received"), observation.getJSONObject("change_actually_given"),
            observation.getJSONObject("net_observed"), application.getJSONObject("applied_coverage"),
            application.getJSONObject("net_cash_effect")).all { it.getString("currency") == "EGP" })
        check(instruction.getJSONObject("amount").getString("minor").toLong() == payable.minor &&
            instruction.getString("tender") == "CASH" && instruction.getBoolean("durable_before_presentation") &&
            instruction.getString("order_id") == orderId.value && instruction.getString("original_cash_session_id") == cashSessionId.value &&
            instruction.getString("required_original_activity_publication") == activityPublicationId.value &&
            observation.getString("original_instruction_operation_id") == startOperationId.value &&
            observation.getString("original_cash_session_id") == cashSessionId.value &&
            observation.getString("outcome_behavior") == "SUCCESS" && observation.getString("tender") == "CASH" &&
            observation.getBoolean("retention_not_financial_acceptance") &&
            observation.getJSONObject("net_observed").getString("minor").toLong() == payable.minor &&
            application.getString("links_original_instruction_operation_id") == startOperationId.value &&
            application.getString("behavior_result") == "COMMITTED" &&
            application.getString("application_original_food_snapshot") == "both_not_sent_not_handed_over" &&
            application.getJSONObject("applied_coverage").getString("minor").toLong() == payable.minor &&
            application.getJSONObject("net_cash_effect").getString("minor").toLong() == payable.minor)
        val recipe = cafe.getJSONObject("recipe")
        check(recipe.getString("version") == "1" && recipe.getString("yield_portions") == "1" &&
            recipe.getJSONArray("per_portion").length() == stocks.size)
        stocks.forEachIndexed { index, stock ->
            val original = cafe.getJSONObject("opening_stock").getJSONObject(stock.role)
            val ingredient = recipe.getJSONArray("per_portion").getJSONObject(index)
            val paid = paidSnapshot.getJSONObject("stock").getJSONObject(stock.role)
            check(stock.unit == unit(stock.role).code.original && ingredient.getString("unit") == stock.unit &&
                ingredient.getString("sku_id") == stock.skuId.value && original.getString("reserved") == "0" &&
                original.getString("available").toLong() == stock.onHand && original.getString("original_issued_value_minor") == "0" &&
                paid.getString("unit") == stock.unit && paid.getString("on_hand").toLong() == stock.onHand &&
                paid.getString("carrying_value_minor").toLong() == stock.carryingValue.minor && paid.getString("original_issued_value_minor") == "0")
        }
        check(cafe.getJSONObject("unit_carrying_cost_egp").getString("beans_per_g") == "0.50" &&
            cafe.getJSONObject("unit_carrying_cost_egp").getString("milk_per_ml") == "0.04")
        check(stocks.map { it.onHand } == listOf(360L, 2_000L) && stocks.map { it.reserved } == listOf(36L, 400L) &&
            stocks.map { it.available } == listOf(324L, 1_600L) && stocks.map { it.perPortion } == listOf(18L, 200L) &&
            stocks.map { it.carryingValue.minor } == listOf(18_000L, 8_000L) && openingValue.minor == 26_000L)
        check(stocks.all { it.onHand - it.reserved == it.available && it.perPortion * 2 == it.reserved } &&
            stocks[0].carryingValue.minor == stocks[0].onHand * 50 && stocks[1].carryingValue.minor == stocks[1].onHand * 4)
        check(paidSnapshot.getString("commercial") == "Open" && paidSnapshot.getString("collection") == "Paid" &&
            paidSnapshot.getString("all_preparation") == "NotSent" && paidSnapshot.getString("all_fulfillment") == "NotHandedOver" &&
            paidSnapshot.getBoolean("pending_preparation") && paidSnapshot.isNull("current_unpaid_payable_minor") &&
            paidSnapshot.getString("historical_quote_minor").toLong() == payable.minor &&
            paidSnapshot.getString("applied_coverage_minor").toLong() == payable.minor && paidSnapshot.getString("remaining_obligation_minor") == "0" &&
            paidSnapshot.getString("cash_received_minor").toLong() == received.minor && paidSnapshot.getString("cash_change_minor").toLong() == change.minor &&
            paidSnapshot.getString("net_applied_cash_minor").toLong() == payable.minor &&
            paidSnapshot.getInt("sold_portion_count") == portionIds.size && paidSnapshot.getInt("sold_portion_coverage_count") == portionIds.size &&
            paidSnapshot.getString("posted_sale_net_minor").toLong() == net.minor && paidSnapshot.getString("posted_sale_tax_minor").toLong() == tax.minor &&
            paidSnapshot.getString("posted_sale_gross_minor").toLong() == payable.minor &&
            paidSnapshot.getString("drawer_ledger_minor").toLong() == (openingCash + payable).minor)
        check(listOf("ingredient_issue_set_count", "kitchen_task_count").all { paidSnapshot.getInt(it) == 0 } &&
            listOf("application_count", "net_cash_effect_set_count", "finalized_sale_count", "receipt_count").all { paidSnapshot.getInt(it) == 1 })
        check(listOf(startOperationId, recordingOperationId, resolutionOperationId, applyOperationId,
            finalizeOperationId, quoteOperationId).distinct().size == 6 && masterAllocationId != clientAllocationId)
    }

    val setup = NativeLocalFixtureSetup(companyName, branchName, installerLogin, headLogin, authorityEpoch,
        founding = { selected ->
            check(selected == protection)
            LocalCompanyFoundingFactsV1(SelfOwnedCompanyFactsV1(companyName, "Synthetic installer", installerLogin, selected),
                "Synthetic company head", headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
                LocalCompanyHeadPurposeV1.entries.toSet())
        }, account = { c ->
            fun grant(action: SecurityAction, verb: PermissionVerb = PermissionVerb.PERFORM,
                targets: Set<DomainId>? = null, amount: Money? = null) = PermissionGrant(Permission(action, verb),
                    c.scope, GrantEffect.ALLOW, PermissionLimits(maximumAmount = amount), OwnerAccess.OWN_ONLY,
                    c.firstActorId, targets, delegationCeiling = null)
            val stockIds = stocks.map { it.skuId }.toSet()
            val grants = listOf(
                grant(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER,
                    (roles.map(::skuId) + recipeId + catalogId).toSet()),
                grant(SecurityAction.BRANCH_ACTIVITY_ACTIVATE, PermissionVerb.ADMINISTER, setOf(c.scope.branchId)),
                grant(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER, setOf(warehouseId, routeId)),
                grant(SecurityAction.KITCHEN_TASK, PermissionVerb.ADMINISTER, setOf(stationId)),
                grant(SecurityAction.KITCHEN_TASK, targets = setOf(stationId)),
                grant(SecurityAction.OPENING_INVENTORY_APPROVE, PermissionVerb.ADMINISTER, setOf(openingApprovalId), openingValue),
                grant(SecurityAction.INVENTORY_ADJUST, targets = setOf(initializationId, openingReceiptId)),
                grant(SecurityAction.INVENTORY_QUANTITY_READ, targets = stockIds + setOf(c.scope.branchId, openingReceiptId)),
                grant(SecurityAction.INVENTORY_COST_READ, targets = stockIds + openingReceiptId),
                grant(SecurityAction.CATALOG_READ), grant(SecurityAction.PURCHASING_READ),
                grant(SecurityAction.CASH_CONFIGURE, targets = setOf(c.firstActorId, drawerId)),
                grant(SecurityAction.CASH_OPEN, targets = setOf(drawerId), amount = openingCash),
                grant(SecurityAction.CASH_READ, targets = setOf(c.firstActorId, cashSessionId, DomainId(recordingOperationId.value))),
                grant(SecurityAction.ORDER_CREATE, targets = setOf(orderId)),
                grant(SecurityAction.ORDER_EDIT, targets = setOf(orderId)),
                grant(SecurityAction.ORDER_READ, targets = setOf(orderId, collectionId, c.firstActorId)),
                grant(SecurityAction.COLLECTION_BEGIN, targets = setOf(orderId), amount = payable),
                grant(SecurityAction.COLLECTION_ATTEST, targets = setOf(collectionId), amount = payable),
                grant(SecurityAction.COLLECTION_FINALIZE, targets = setOf(collectionId), amount = payable),
                grant(SecurityAction.RETURN_RECEIPT_LOOKUP, targets = setOf(saleId)),
                grant(SecurityAction.OPERATION_RESULT_RECOVERY),
            )
            LocalAccountProvisioning(c.firstActorId, employeeLogin, ActivatedLocalPolicy(c.scope, c.firstActorId, Revision(0),
                grants, LocalSessionPolicy(maximumConcurrentSessions = 1), ThrottlePolicy(3, 1_000, 900_000, 120_000), null),
                LocalTokenPolicy(1_800_000, 28_800_000), setOf(c.deviceId))
        })

    fun skuId(role: String): DomainId = if (role == "latte") latteSkuId else stocks.single { it.role == role }.skuId
    fun productId(role: String): DomainId = input(100 + roles.indexOf(role).also { check(it >= 0) })
    fun skuPublicationId(role: String): DomainId = input(200 + roles.indexOf(role).also { check(it >= 0) })
    fun unit(role: String): CatalogUnit = when (role) {
        "latte" -> CatalogUnit(input(1), Code.exact("unit"), UnitDimension.COUNT, 0)
        "beans" -> CatalogUnit(input(2), Code.exact("g"), UnitDimension.MASS, 0)
        "milk" -> CatalogUnit(input(3), Code.exact("ml"), UnitDimension.VOLUME, 0)
        else -> error("Only the frozen latte/beans/milk selection is supported")
    }
    fun quantity(role: String, value: Long): Quantity = Quantity(unit(role).id, value, 0)
    fun selection(portion: DomainId) = LocalFoodPortionSelectionV1(portion, latteSkuId, recipeId, Revision(1), choiceId,
        emptyList(), quantity("latte", 1), itemNote = "", lineId = portionIds.first(), seatNote = "")
    fun target() = LocalFoodPaymentTarget(orderId, collectionId, cashSessionId, attemptId, startOperationId,
        recordingOperationId, resolutionOperationId)

    /** Complete proposed DATA is retained before the actual native company producer is called. */
    fun sources(company: CompanyScope): CafeRequestedSources {
        val skus = roles.associateWith { role ->
            val price = if (role != "latte") null else LocalPriceV1(input(300), Revision(1), "TAKEAWAY", 0,
                quantity(role, 0), null, effectiveFrom, null, UnitPrice(Currency.EGP, ExactDecimal.parse(prices.getString("unit_price"))),
                TaxInclusion.INCLUSIVE, TaxSnapshot(input(301), Revision(1), "SYNTHETIC_14_PERCENT", PercentageRate.parse("14")))
            val variants = if (role == "latte") listOf(small) else emptyList()
            LocalPublishedSkuV1(company, skuPublicationId(role), skuId(role), productId(role), Revision(0), Revision(1),
                LocalNamesV1(if (role == "latte") "Small latte" else role, ""), "FCC-C01-${role.uppercase()}", emptyList(),
                unit(role).id, 0, LocalTrackingV1.QUANTITY,
                if (role == "latte") LocalFulfillmentV1.MADE_TO_ORDER else LocalFulfillmentV1.RETAIL,
                false, role != "latte", role == "latte", true, emptyList(), listOfNotNull(price), returnPromise = null,
                unitDefinitions = listOf(unit(role)), productDefinition = LocalPublishedProductV1(productId(role), Revision(0),
                    LocalNamesV1(role, ""), emptyList(), if (role == "latte") listOf(sizeAxis) else emptyList(), emptyList(),
                    LocalProductStatusV1.ACTIVE, allowedVariantCombinations = listOf(variants)), variantValues = variants)
        }
        val recipe = LocalPublishedFoodRecipeV1(company, recipePublicationId, recipeId, Revision(1), latteSkuId, Revision(1),
            quantity("latte", 1), quantity("latte", 1), listOf(LocalFoodRecipeChoiceV1(choiceId, LocalNamesV1("Small", ""),
                emptyList(), stocks.mapIndexed { index, row -> LocalFoodIngredientDefinitionV1(input(400 + index), row.skuId,
                    Revision(1), quantity(row.role, row.perPortion)) }, listOf(routeKey), latteSkuId)), true)
        requireCompatibleLocalFoodRecipe(recipe, skus.values.associateBy { it.skuId })
        val activity = LocalPublishedCompanyActivityProfileV1(company, activityPublicationId, activityProfileId, Revision(0), setOf(this.activity))
        fun proposal(kind: CompanyBusinessPublicationKind, publicationId: DomainId, entityId: DomainId, bytes: ByteArray) =
            try { CompanyBusinessPublicationProposal(company, kind, publicationId, entityId, bytes) } finally { bytes.fill(0) }
        val skuProposals = roles.map { role -> proposal(CompanyBusinessPublicationKind.CATALOG_SKU_V1, skuPublicationId(role),
            skuId(role), LocalConfigurationCodec.canonicalPublication(skus.getValue(role))) }
        val recipeProposal = proposal(CompanyBusinessPublicationKind.FOOD_RECIPE_V1, recipePublicationId, recipeId,
            LocalFoodConfigurationCodecV1.canonicalPublication(recipe))
        val release = LocalPublishedCatalogReleaseV1(company, catalogId, releasePublicationId, Revision(0), null,
            roles.mapIndexed { index, role -> LocalCatalogReleaseMemberV1(skuPublicationId(role), skuProposals[index].contentDigest,
                skuId(role), Revision(1), productId(role), Revision(0)) })
        return CafeRequestedSources(company, skus, recipe, activity, release,
            skuProposals + recipeProposal + activity.toNativeCompanyActivityProposal() + makeNativeCatalogReleaseProposal(release))
    }

    fun operation(suffix: Int): OperationId = OperationId("cfc10002-0000-4000-8000-" + suffix.toString().padStart(12, '0'))
    private fun input(suffix: Int): DomainId = DomainId("cfc10001-0000-4000-8000-" + suffix.toString().padStart(12, '0'))
    private fun id(key: String): DomainId = DomainId(ids.getString(key))
    private fun money(minor: String): Money = Money(Currency.EGP, minor.toLong())
    override fun toString(): String = "NativeCafeCashPlan([REDACTED])"
}

internal data class CafeStockInput(val role: String, val skuId: DomainId, val unit: String, val onHand: Long,
    val reserved: Long, val available: Long, val carryingValue: Money, val perPortion: Long,
    val allocationId: DomainId, val receiptLineId: DomainId, val sourceId: DomainId)
internal class CafeRequestedSources(val company: CompanyScope, val skus: Map<String, LocalPublishedSkuV1>,
    val recipe: LocalPublishedFoodRecipeV1, val activity: LocalPublishedCompanyActivityProfileV1,
    val release: LocalPublishedCatalogReleaseV1, val proposals: List<CompanyBusinessPublicationProposal>) {
    fun proposal(publicationId: DomainId): CompanyBusinessPublicationProposal = proposals.single { it.publicationId == publicationId }
    override fun toString(): String = "CafeRequestedSources([REDACTED])"
}
