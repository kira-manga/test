package com.ukkera.pos.android.independent.nativefoodpaymentalreadyappliedv1

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

/** FAA-F02 economic inputs, unchanged. Restaurant/takeaway, zero opening carrying
 * values and the ancillary catalog IDs are explicit synthetic prerequisite selections,
 * not new expected outcomes. Native-issued scope/actor/device/session IDs are observed,
 * never forced to the illustrative UUIDs. Q01/Q03/Q04 remain unqualified. */
internal class NativeFoodAlreadyAppliedPlan(val frozen: JSONObject, val protection: SelfOwnedKeyProtectionV1) {
    private val ids = frozen.getJSONObject("ids")
    private val base = frozen.getJSONObject("FAA-F01")
    private val quote = base.getJSONObject("quote")
    private val original = base.getJSONObject("native_original")
    private val baseline = base.getJSONObject("accounted_baseline")
    private val unsent = frozen.getJSONObject("FAA-F02").getJSONObject("unsent_stock")
    private val sent = base.getJSONObject("sent_stock")
    val afterActions: JSONObject = frozen.getJSONObject("all_post_accounting_actions")
    val companyName = "Synthetic FOOD AlreadyApplied company"
    val branchName = "Synthetic FOOD AlreadyApplied branch"
    val installerLogin = "faa-f02-installer"
    val headLogin = "faa-f02-head"
    val loginA = LocalLoginIdentifier("faa-f02-cashier-a")
    val loginB = LocalLoginIdentifier("faa-f02-cashier-b")
    val authorityEpoch = Revision(7) // Synthetic setup input, distinct from employee policy revision.
    val activePolicyRevision = Revision(base.getJSONObject("rights").getString("activated_policy_revision").toLong())
    val activity = LocalSalesActivity.RESTAURANT
    val service = LocalFoodServiceV1(LocalSalesChannel.TAKEAWAY, "Synthetic FAA F02 pickup", "")
    val effectiveFrom = UtcInstant.parseCanonical("2000-01-01T00:00:00Z")
    val calendar = BusinessCalendar(IanaTimeZone.AFRICA_CAIRO, LocalTime(4, 0),
        CutoffResolution.SHIFT_GAP_FORWARD_USE_EARLIER_OVERLAP)
    val zero = Money(Currency.EGP, 0)
    val payable = money(quote.getString("payable_minor"))
    val portionGross = money(quote.getString("unit_gross_minor"))
    val received = money(original.getString("received_minor"))
    val change = money(original.getString("change_minor"))
    val openingA = money(baseline.getString("drawer_a_opening_minor"))
    val openingB = money(baseline.getString("drawer_b_opening_minor"))
    val expectedA = money(baseline.getString("drawer_a_expected_minor"))
    val expectedB = money(baseline.getString("drawer_b_expected_minor"))
    val orderId = id("order")
    val collectionId = id("collection")
    val attemptId = id("attempt")
    val recordingOperationId = OperationId(id("original_recording").value)
    val rejectionOperationId = OperationId(id("operation").value)
    val drawerA = id("drawer_a")
    val drawerB = id("drawer_b")
    val cashSessionA = id("cash_session_a")
    val cashSessionB = id("cash_session_b")
    val actorB = id("actor_b")
    val portionIds = listOf(id("portion_1"), id("portion_2"))
    val startOperationId = operation(900)
    val earlierApplyOperationId = operation(901)
    val resolutionOperationId = operation(902)
    val quoteOperationId = operation(122)
    val laterWriterOperationId = operation(950)
    val laterOrderId = input(950)
    val enrollmentB = input(951)
    val warehouseId = input(700)
    val initializationId = input(701)
    val openingApprovalId = input(702)
    val openingReceiptId = input(703)
    val stationId = input(800)
    val catalogId = input(900)
    val activityProfileId = input(901)
    val activityPublicationId = input(902)
    val releasePublicationId = input(903)
    val recipeId = input(904)
    val recipePublicationId = input(905)
    val choiceId = input(906)
    val routeId = input(907)
    val routeKey = input(908)
    val masterAllocationId = input(920)
    val clientAllocationId = input(921)
    val outcomeEvidenceId = input(922)
    val openingSourceA = input(910)
    val openingSourceB = input(911)
    val openingEvidenceA = input(912)
    val openingEvidenceB = input(913)
    val reasonCode = "faa.f02.synthetic.opening"
    val roles = listOf("meal", "bread", "meat")
    val stocks = listOf(
        FoodAlreadyAppliedStock("bread", input(101), sent.getString("bread_initial_units").toLong(),
            unsent.getString("bread_reserved_units").toLong(), unsent.getString("bread_available_units").toLong(),
            sent.getString("recipe_bread_per_portion_units").toLong(), input(710), input(720), input(730)),
        FoodAlreadyAppliedStock("meat", input(102), sent.getString("meat_initial_g").toLong(),
            unsent.getString("meat_reserved_g").toLong(), unsent.getString("meat_available_g").toLong(),
            sent.getString("recipe_meat_per_portion_g").toLong(), input(711), input(721), input(731)),
    )
    val warehouse = LocalWarehouseV1(warehouseId, LocalNamesV1("Synthetic FAA ingredients", ""), true)
    val station = LocalFoodStationDefinitionV1(stationId, LocalNamesV1("Synthetic FAA preparation", ""),
        FoodStationDeliveryMode.DISPLAY, true)
    val route = LocalFoodRecipeRouteV1(routeId, recipeId, Revision(1), activity, service.channel, warehouseId,
        listOf(LocalFoodRouteTargetV1(routeKey, stationId)), true)
    val openingApproval = LocalReceivingApprovalV1(openingApprovalId, LocalBasisKindV1.OPENING, null,
        effectiveFrom, null, stocks.map { LocalApprovedStockLineV1(it.allocationId, it.skuId, warehouseId,
            quantity(it.role, it.onHand), zero, false) })

    init {
        check(frozen.getString("fixture_version") == "1.0.0" && base.getString("profile") == "FOOD_CASH_FINANCIAL" &&
            base.getString("procedures_version") == "1" && activePolicyRevision == Revision(7))
        check(quote.getString("currency") == "EGP" && quote.getString("portions") == "2" &&
            listOf("discount_minor", "tax_minor", "charges_minor").all { quote.getString(it) == "0" })
        check(portionGross.minor == 15_000L && payable.minor == 30_000L && portionGross.minor * portionIds.size == payable.minor)
        check(received.minor == 50_000L && change.minor == 20_000L && received - change == payable &&
            original.getString("net_collected_minor") == payable.minor.toString() && original.getString("physical_capture_count") == "1")
        check(openingA.minor == 100_000L && expectedA == openingA + payable && expectedA.minor == 130_000L &&
            openingB.minor == 70_000L && expectedB == openingB)
        check(baseline.getString("collection_coverage_minor") == payable.minor.toString() &&
            listOf("remaining_payable_minor", "refund_minor", "sale_finalization_count").all { baseline.getString(it) == "0" } &&
            baseline.getString("payment_accounting_count_for_original") == "1" && baseline.getString("cash_movement_count_for_original") == "1")
        check(unsent.getString("bread_on_hand_units") == "10" && unsent.getString("meat_on_hand_g") == "1500" &&
            unsent.getString("issued_portions") == "0" && unsent.getString("kitchen_tasks") == "0")
        check(stocks.map { it.onHand } == listOf(10L, 1_500L) && stocks.map { it.reserved } == listOf(2L, 300L) &&
            stocks.map { it.available } == listOf(8L, 1_200L) && stocks.all { it.reserved == it.perPortion * portionIds.size })
        check(listOf(startOperationId, recordingOperationId, earlierApplyOperationId, rejectionOperationId,
            resolutionOperationId, quoteOperationId, laterWriterOperationId).distinct().size == 7 &&
            masterAllocationId != clientAllocationId)
        check(listOf("additional_physical_capture_count", "additional_cash_movement_count", "additional_refund_minor",
            "additional_sale_finalization_count", "additional_stock_issue_or_reversal_count", "additional_bread_reserved_units",
            "additional_meat_reserved_g", "additional_kitchen_task_count", "new_original_recording_count")
            .all { afterActions.getString(it) == "0" })
    }

    val setup = NativeLocalFixtureSetup(companyName, branchName, installerLogin, headLogin, authorityEpoch,
        founding = { selected ->
            check(selected == protection)
            LocalCompanyFoundingFactsV1(SelfOwnedCompanyFactsV1(companyName, "Synthetic installer", installerLogin, selected),
                "Synthetic company head", headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
                LocalCompanyHeadPurposeV1.entries.toSet())
        }, account = { account(FoodAlreadyAppliedActor.A, it) })

    fun actorId(actor: FoodAlreadyAppliedActor, c: SelfOwnedAllocationCoordinatesV1): DomainId =
        if (actor == FoodAlreadyAppliedActor.A) c.firstActorId else actorB
    fun login(actor: FoodAlreadyAppliedActor): LocalLoginIdentifier = if (actor == FoodAlreadyAppliedActor.A) loginA else loginB
    fun account(actor: FoodAlreadyAppliedActor, c: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning =
        LocalAccountProvisioning(actorId(actor, c), login(actor), policy(actor, c, Revision(0)),
            LocalTokenPolicy(1_800_000, 28_800_000), setOf(c.deviceId))

    /** Broader scoped prerequisite grants are recorded, not claimed as the Q03 minimum. */
    fun policy(actor: FoodAlreadyAppliedActor, c: SelfOwnedAllocationCoordinatesV1, revision: Revision): ActivatedLocalPolicy {
        val actorId = actorId(actor, c)
        fun grant(action: SecurityAction, verb: PermissionVerb = PermissionVerb.PERFORM,
            targets: Set<DomainId>? = null, amount: Money? = null) = PermissionGrant(Permission(action, verb), c.scope,
                GrantEffect.ALLOW, PermissionLimits(maximumAmount = amount), OwnerAccess.OWN_ONLY, actorId, targets, null)
        val drawer = if (actor == FoodAlreadyAppliedActor.A) drawerA else drawerB
        val session = if (actor == FoodAlreadyAppliedActor.A) cashSessionA else cashSessionB
        val opening = if (actor == FoodAlreadyAppliedActor.A) openingA else openingB
        val grants = mutableListOf(grant(SecurityAction.CASH_CONFIGURE, targets = setOf(actorId, drawer)),
            grant(SecurityAction.CASH_OPEN, targets = setOf(drawer), amount = opening),
            grant(SecurityAction.CASH_READ, targets = setOf(actorId, session, DomainId(recordingOperationId.value))))
        if (actor == FoodAlreadyAppliedActor.A) grants += listOf(
            grant(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER, (roles.map(::skuId) + recipeId + catalogId).toSet()),
            grant(SecurityAction.BRANCH_ACTIVITY_ACTIVATE, PermissionVerb.ADMINISTER, setOf(c.scope.branchId)),
            grant(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER, setOf(warehouseId, routeId)),
            grant(SecurityAction.KITCHEN_TASK, PermissionVerb.ADMINISTER, setOf(stationId)),
            grant(SecurityAction.KITCHEN_TASK, targets = setOf(stationId)),
            grant(SecurityAction.OPENING_INVENTORY_APPROVE, PermissionVerb.ADMINISTER, setOf(openingApprovalId), zero),
            grant(SecurityAction.INVENTORY_ADJUST, targets = setOf(initializationId, openingReceiptId)),
            grant(SecurityAction.INVENTORY_QUANTITY_READ, targets = stocks.map { it.skuId }.toSet() + c.scope.branchId + openingReceiptId),
            grant(SecurityAction.INVENTORY_COST_READ, targets = stocks.map { it.skuId }.toSet() + openingReceiptId),
            grant(SecurityAction.CATALOG_READ), grant(SecurityAction.PURCHASING_READ),
            grant(SecurityAction.ORDER_CREATE, targets = setOf(orderId, laterOrderId)),
            grant(SecurityAction.ORDER_EDIT, targets = setOf(orderId)),
            grant(SecurityAction.ORDER_READ, targets = setOf(orderId, laterOrderId, collectionId, actorId)),
            grant(SecurityAction.COLLECTION_BEGIN, targets = setOf(orderId), amount = payable),
            grant(SecurityAction.COLLECTION_ATTEST, targets = setOf(collectionId), amount = payable),
            grant(SecurityAction.OPERATION_RESULT_RECOVERY, targets = setOf(DomainId(rejectionOperationId.value),
                DomainId(recordingOperationId.value), DomainId(earlierApplyOperationId.value))),
        )
        return ActivatedLocalPolicy(c.scope, actorId, revision, grants, LocalSessionPolicy(1),
            ThrottlePolicy(3, 1_000, 900_000, 120_000), null)
    }

    fun skuId(role: String): DomainId = input(100 + roles.indexOf(role).also { check(it >= 0) })
    private fun productId(role: String): DomainId = input(200 + roles.indexOf(role).also { check(it >= 0) })
    private fun publicationId(role: String): DomainId = input(300 + roles.indexOf(role).also { check(it >= 0) })
    fun unit(role: String): CatalogUnit {
        check(role in roles)
        return CatalogUnit(input(if (role == "meat") 2 else 1), Code.exact(if (role == "meat") "g" else "unit"),
            if (role == "meat") UnitDimension.MASS else UnitDimension.COUNT, 0)
    }
    fun quantity(role: String, value: Long) = Quantity(unit(role).id, value, 0)
    fun selection(portion: DomainId) = LocalFoodPortionSelectionV1(portion, skuId("meal"), recipeId, Revision(1),
        choiceId, emptyList(), quantity("meal", 1), itemNote = "", lineId = portionIds.first(), seatNote = "")
    fun target() = LocalFoodPaymentTarget(orderId, collectionId, cashSessionA, attemptId, startOperationId,
        recordingOperationId, resolutionOperationId)

    fun sources(company: CompanyScope): FoodAlreadyAppliedRequestedSources {
        val skus = roles.associateWith { role ->
            val price = if (role != "meal") null else LocalPriceV1(input(400), Revision(1), "TAKEAWAY", 0,
                quantity(role, 0), null, effectiveFrom, null, UnitPrice(Currency.EGP, ExactDecimal.parse("150.00")),
                TaxInclusion.EXCLUSIVE, TaxSnapshot(input(401), Revision(1), "SYNTHETIC_ZERO_TAX", PercentageRate.parse("0")))
            LocalPublishedSkuV1(company, publicationId(role), skuId(role), productId(role), Revision(0), Revision(1),
                LocalNamesV1(if (role == "meal") "Synthetic bread and meat meal" else role, ""), "FAA-F02-${role.uppercase()}",
                emptyList(), unit(role).id, 0, LocalTrackingV1.QUANTITY,
                if (role == "meal") LocalFulfillmentV1.MADE_TO_ORDER else LocalFulfillmentV1.RETAIL,
                false, role != "meal", role == "meal", true, emptyList(), listOfNotNull(price), returnPromise = null,
                unitDefinitions = listOf(unit(role)), productDefinition = LocalPublishedProductV1(productId(role), Revision(0),
                    LocalNamesV1(role, ""), emptyList(), emptyList(), emptyList(), LocalProductStatusV1.ACTIVE,
                    allowedVariantCombinations = listOf(emptyList())), variantValues = emptyList())
        }
        val recipe = LocalPublishedFoodRecipeV1(company, recipePublicationId, recipeId, Revision(1), skuId("meal"), Revision(1),
            quantity("meal", 1), quantity("meal", 1), listOf(LocalFoodRecipeChoiceV1(choiceId, LocalNamesV1("Original", ""),
                emptyList(), stocks.mapIndexed { index, row -> LocalFoodIngredientDefinitionV1(input(500 + index), row.skuId,
                    Revision(1), quantity(row.role, row.perPortion)) }, listOf(routeKey), skuId("meal"))), true)
        requireCompatibleLocalFoodRecipe(recipe, skus.values.associateBy { it.skuId })
        val activity = LocalPublishedCompanyActivityProfileV1(company, activityPublicationId, activityProfileId, Revision(0), setOf(this.activity))
        fun proposal(kind: CompanyBusinessPublicationKind, publication: DomainId, entity: DomainId, bytes: ByteArray) =
            try { CompanyBusinessPublicationProposal(company, kind, publication, entity, bytes) } finally { bytes.fill(0) }
        val skuProposals = roles.map { proposal(CompanyBusinessPublicationKind.CATALOG_SKU_V1, publicationId(it),
            skuId(it), LocalConfigurationCodec.canonicalPublication(skus.getValue(it))) }
        val recipeProposal = proposal(CompanyBusinessPublicationKind.FOOD_RECIPE_V1, recipePublicationId, recipeId,
            LocalFoodConfigurationCodecV1.canonicalPublication(recipe))
        val release = LocalPublishedCatalogReleaseV1(company, catalogId, releasePublicationId, Revision(0), null,
            roles.mapIndexed { index, role -> LocalCatalogReleaseMemberV1(publicationId(role), skuProposals[index].contentDigest,
                skuId(role), Revision(1), productId(role), Revision(0)) })
        return FoodAlreadyAppliedRequestedSources(skus, recipe, activity, release,
            skuProposals + recipeProposal + activity.toNativeCompanyActivityProposal() + makeNativeCatalogReleaseProposal(release))
    }

    fun operation(suffix: Int) = OperationId("faa20002-0000-4000-8000-" + suffix.toString().padStart(12, '0'))
    fun input(suffix: Int) = DomainId("faa20001-0000-4000-8000-" + suffix.toString().padStart(12, '0'))
    private fun id(key: String) = DomainId(ids.getString(key))
    private fun money(minor: String) = Money(Currency.EGP, minor.toLong())
    override fun toString() = "NativeFoodAlreadyAppliedPlan([REDACTED])"
}

internal enum class FoodAlreadyAppliedActor { A, B }
internal data class FoodAlreadyAppliedStock(val role: String, val skuId: DomainId, val onHand: Long,
    val reserved: Long, val available: Long, val perPortion: Long, val allocationId: DomainId,
    val receiptLineId: DomainId, val sourceId: DomainId)
internal class FoodAlreadyAppliedRequestedSources(val skus: Map<String, LocalPublishedSkuV1>,
    val recipe: LocalPublishedFoodRecipeV1, val activity: LocalPublishedCompanyActivityProfileV1,
    val release: LocalPublishedCatalogReleaseV1, val proposals: List<CompanyBusinessPublicationProposal>)
