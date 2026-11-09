package com.ukkera.pos.android.independent.ordinaryfoodcheckcoldv1

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureSetup
import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.FoodFulfillmentStatus
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

/** Frozen fixture interpretation and previously unspecified synthetic INPUTS only.
 * No publication, native identity, revision, session or business result is minted here. */
internal class NativeFoodColdInputPlan(
    base: JSONObject,
    identity: JSONObject,
    catalog: JSONObject,
    val profile: String,
    val protection: SelfOwnedKeyProtectionV1,
) {
    private val fixture = base.getJSONObject("fixtures").getJSONObject(profile)
    private val inputPrefix = when (profile) {
        "restaurant" -> "a60d0001-0000-4000-8000-"
        "cafe" -> "a60d0002-0000-4000-8000-"
        else -> error("Select exactly restaurant or cafe; neither profile is implicitly skipped")
    }
    private val operationPrefix = if (profile == "restaurant") "a60f0001-0000-4000-8000-"
        else "a60f0002-0000-4000-8000-"
    val activity = if (profile == "restaurant") LocalSalesActivity.RESTAURANT else LocalSalesActivity.CAFE
    val authorityEpoch = Revision(fixture.getString("master_epoch").toLong())
    val orderId = DomainId(fixture.getString("check_id"))
    val tableId = DomainId(fixture.getString("table_id"))
    val ownerSymbol = fixture.getString("owner_id")
    val kdsId = DomainId(fixture.getString("kds_id"))
    val administratorId = DomainId(if (profile == "restaurant") fixture.getString("local_administrator_id")
        else "a60c0000-0000-4000-8000-000000000054")
    val companyName = "Synthetic ordinary food $profile company"
    val branchName = "Synthetic ordinary food $profile branch"
    val installerLogin = "ofc101-$profile-installer"
    val headLogin = "ofc101-$profile-head"
    val ownerLogin = LocalLoginIdentifier("ofc101-$profile-owner")
    val administratorLogin = LocalLoginIdentifier("ofc101-$profile-administrator")
    val kdsLogin = LocalLoginIdentifier("ofc101-$profile-kds")
    val service = LocalFoodServiceV1(LocalSalesChannel.DINE_IN, "Table ${if (profile == "restaurant") "R" else "C"}",
        fixture.getString("seat_note"))
    val effectiveFrom = UtcInstant.parseCanonical("2000-01-01T00:00:00Z")
    val zero = Money(Currency.EGP, 0)
    val reasonCode = "ofc101.synthetic.opening"

    // Disjoint input suffixes: units1..3; stock10..13; SKU100+i/product200+i/
    // publication300+i/price400+i/tax500+i/allocation600+i/receipt-line700+i/source800+i;
    // recipe1000+i/publication1100+i/choice1200+i/route1300+i/route-key1400+i/
    // component1500+16*i+j; station1600/floor1700/catalog1800/activity1801..2/
    // releases1810..15/enrollment1900..1. Operation suffixes are disjoint below.
    val warehouseId = input(10)
    val initializationId = input(11)
    val openingApprovalId = input(12)
    val openingReceiptId = input(13)
    val stationId = input(1600)
    val floorId = input(1700)
    val catalogId = input(1800)
    val activityProfileId = input(1801)
    val activityPublicationId = input(1802)
    val administratorEnrollmentId = input(1900)
    val kdsEnrollmentId = input(1901)

    val roles: List<String> = if (profile == "restaurant")
        listOf("burger", "tea", "bread", "meat", "tea_leaf", "cup") else
        listOf("latte", "cheese_toast", "coffee", "milk", "cup", "bread", "cheese")
    val outputRoles: List<String> = fixture.getJSONArray("lines").objects().map { it.getString("product") }
    val stockRoles: List<String> = roles.filter { it !in outputRoles }
    val lines: List<FoodColdLineInput> = fixture.getJSONArray("lines").objects().map { row ->
        val binding = identity.getJSONArray("seed_group_bindings").objects().single {
            it.getString("profile") == profile && fixtureUuid(it.getInt("base_group_suffix")).value == row.getString("id")
        }
        FoodColdLineInput(row.getString("product"), DomainId(row.getString("id")),
            fixtureUuid(binding.getInt("anchor_suffix")), row.getString("quantity").toLong(),
            row.getString("unit_price_minor").toLong(), row.getString("line_total_minor").toLong(),
            binding.getJSONArray("portion_suffixes").let { values -> (0 until values.length()).map { fixtureUuid(values.getInt(it)) } })
    }
    val portions: List<FoodColdPortionInput> = fixture.getJSONArray("portions").objects().map { row ->
        val line = lines.single { it.originalGroup.value == row.getString("line_id") }
        FoodColdPortionInput(DomainId(row.getString("id")), line.product, line.anchor,
            row.getString("quantity").toLong(), when (row.getString("preparation")) {
                "NotSent" -> FoodPreparationStatus.NOT_SENT
                "Queued" -> FoodPreparationStatus.QUEUED
                "Preparing" -> FoodPreparationStatus.PREPARING
                "Ready" -> FoodPreparationStatus.READY
                else -> error("Unsupported unchanged OFC-COLD-101 preparation oracle")
            }, when (row.getString("fulfillment")) {
                "NotHandedOver" -> FoodFulfillmentStatus.NOT_HANDED_OVER
                "Served" -> FoodFulfillmentStatus.SERVED
                else -> error("Unsupported unchanged OFC-COLD-101 fulfillment oracle")
            }, row.getString("required_station_task_count").toInt())
    }
    val stocks: List<FoodColdStockInput> = stockRoles.map { role ->
        val row = fixture.getJSONObject("stock").getJSONObject(role)
        FoodColdStockInput(role, row.getString("unit"), row.getString("initial").toLong(),
            row.getString("available").toLong(), row.getString("reserved").toLong(), row.getString("issued").toLong())
    }
    val components: Map<String, List<FoodColdComponentInput>> = outputRoles.associateWith { role ->
        val recipe = fixture.getJSONObject("recipes").getJSONObject(role)
        check(recipe.getString("revision") == "1")
        recipe.getJSONArray("components").objects().map { FoodColdComponentInput(
            it.getString("product"), it.getString("unit"), it.getString("quantity").toLong()) }
    }
    val expected = base.getJSONObject("numeric_expectations").getJSONObject(profile).getJSONObject("baseline")
    val payable = Money(Currency.EGP, expected.getString("payable_minor").toLong())
    val outstanding = Money(Currency.EGP, expected.getString("outstanding_minor").toLong())
    val totalQuantity = expected.getString("total_quantity").toLong()
    val groupCount = expected.getString("line_count").toInt()
    val issuedPortions = expected.getString("issued_portions").toInt()
    val taskCount = expected.getString("distinct_preparation_tasks").toInt()
    val warehouse = LocalWarehouseV1(warehouseId, LocalNamesV1("Synthetic ingredient warehouse", ""), true)
    val station = LocalFoodStationDefinitionV1(stationId, LocalNamesV1("Synthetic preparation station", ""),
        FoodStationDeliveryMode.DISPLAY, true)
    val floor = LocalFoodFloorDefinitionV1(floorId, LocalNamesV1("Synthetic dining floor", ""), 0, true)
    val table = LocalFoodTableDefinitionV1(tableId, floorId, LocalNamesV1(service.reference, ""), 0, true)
    val openingApproval = LocalReceivingApprovalV1(openingApprovalId, LocalBasisKindV1.OPENING, null, effectiveFrom, null,
        stocks.map { LocalApprovedStockLineV1(allocationId(it.role), skuId(it.role), warehouseId,
            quantity(it.role, it.initial), zero, false) })

    init {
        check(base.getString("design_id") == "ordinary-food-check-cold-v1" && base.getString("version") == BASE_VERSION &&
            base.getString("execution_status") == "NOT RUN" && base.getString("currency") == "EGP")
        check(identity.getString("variant") == IDENTITY_VARIANT && identity.getString("version") == ADJUNCT_VERSION &&
            identity.getString("execution_status") == "NOT RUN")
        check(catalog.getString("variant") == CATALOG_VARIANT && catalog.getString("version") == ADJUNCT_VERSION &&
            catalog.getString("execution_status") == "NOT RUN")
        val coordinate = catalog.getJSONArray("coordinate_interpretations").objects().single { it.getString("profile") == profile }
        check(coordinate.getString("preserved_original_literal") == "5" && fixture.getString("catalog_revision") == "5" &&
            coordinate.getJSONObject("release").getString("revision") == "5")
        check(coordinate.getJSONObject("release").getJSONArray("required_selected_SKU_roles").strings() == roles)
        check(authorityEpoch == Revision(7) && fixture.getString("commercial_state") == "Open" &&
            fixture.getString("ownership_state") == "Owned" && fixture.getString("collection_state") == "Unpaid" &&
            fixture.getString("refund_state") == "None" && fixture.getString("exception_state") == "None" &&
            fixture.getString("creator_id") == ownerSymbol && fixture.getString("current_owner_id") == ownerSymbol)
        check(listOf("collected_minor", "cash_effect_minor", "refund_due_minor", "paid_sale_finalizations",
            "unpaid_incidents", "inventory_restoration").all { expected.getString(it) == "0" })
        check(listOf("tax_fixture_rate", "discount_minor", "service_charge_minor", "delivery_charge_minor")
            .all { base.getJSONObject("calculations").getString(it) == "0" })
        check(lines.size == groupCount && groupCount == 2 && lines.sumOf { it.quantity } == totalQuantity &&
            lines.sumOf { it.totalMinor } == payable.minor && payable == outstanding)
        check(portions.map { it.id } == lines.flatMap { it.portionIds } && portions.all { it.quantity == 1L } &&
            portions.map { it.id }.distinct().size == totalQuantity.toInt())
        check(lines.all { line -> line.portionIds.size.toLong() == line.quantity && line.anchor == line.portionIds.first() } &&
            portions.count { it.tasks == 1 } == taskCount && portions.count { it.preparation != FoodPreparationStatus.NOT_SENT } == issuedPortions)
    }

    val setup = NativeLocalFixtureSetup(companyName, branchName, installerLogin, headLogin, authorityEpoch,
        founding = { selected ->
            check(selected == protection)
            LocalCompanyFoundingFactsV1(SelfOwnedCompanyFactsV1(companyName, "Synthetic installer", installerLogin, selected),
                "Synthetic company head", headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
                LocalCompanyHeadPurposeV1.entries.toSet())
        }, account = { coordinates -> account(FoodColdActor.OWNER, coordinates) })

    fun account(actor: FoodColdActor, c: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        val id = actorId(actor, c)
        fun grant(action: SecurityAction, verb: PermissionVerb = PermissionVerb.PERFORM,
            targets: Set<DomainId>? = null, amount: Money? = null): PermissionGrant = PermissionGrant(
                Permission(action, verb), c.scope, GrantEffect.ALLOW, PermissionLimits(maximumAmount = amount),
                OwnerAccess.OWN_ONLY, id, targets, delegationCeiling = null)
        val grants = when (actor) {
            FoodColdActor.OWNER -> listOf(
                grant(SecurityAction.ORDER_CREATE, targets = setOf(orderId)),
                grant(SecurityAction.ORDER_EDIT, targets = setOf(orderId)),
                grant(SecurityAction.ORDER_READ, targets = setOf(orderId, id)),
                grant(SecurityAction.CATALOG_READ), grant(SecurityAction.FLOOR_READ),
                grant(SecurityAction.INVENTORY_QUANTITY_READ, targets = stocks.map { skuId(it.role) }.toSet()),
                grant(SecurityAction.OPERATION_RESULT_RECOVERY),
            )
            FoodColdActor.ADMINISTRATOR -> listOf(
                grant(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER,
                    (roles.map(::skuId) + outputRoles.map(::recipeId) + catalogId).toSet()),
                grant(SecurityAction.BRANCH_ACTIVITY_ACTIVATE, PermissionVerb.ADMINISTER, setOf(c.scope.branchId)),
                grant(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER,
                    (outputRoles.map(::routeId) + warehouseId).toSet()),
                grant(SecurityAction.KITCHEN_TASK, PermissionVerb.ADMINISTER, setOf(stationId)),
                grant(SecurityAction.FLOOR_CONFIGURE, PermissionVerb.ADMINISTER, setOf(floorId, tableId)),
                grant(SecurityAction.OPENING_INVENTORY_APPROVE, PermissionVerb.ADMINISTER, setOf(openingApprovalId), zero),
                grant(SecurityAction.INVENTORY_ADJUST, targets = setOf(initializationId, openingReceiptId)),
                grant(SecurityAction.CATALOG_READ), grant(SecurityAction.PURCHASING_READ), grant(SecurityAction.FLOOR_READ),
                grant(SecurityAction.INVENTORY_QUANTITY_READ), grant(SecurityAction.INVENTORY_COST_READ),
                grant(SecurityAction.OPERATION_RESULT_RECOVERY),
            )
            FoodColdActor.KDS -> listOf(grant(SecurityAction.KITCHEN_TASK, targets = setOf(stationId)),
                grant(SecurityAction.OPERATION_RESULT_RECOVERY))
        }
        return LocalAccountProvisioning(id, login(actor), ActivatedLocalPolicy(c.scope, id, Revision(0), grants,
            LocalSessionPolicy(maximumConcurrentSessions = 1), ThrottlePolicy(3, 1_000, 900_000, 120_000), null),
            LocalTokenPolicy(1_800_000, 28_800_000), setOf(c.deviceId))
    }

    fun actorId(actor: FoodColdActor, c: SelfOwnedAllocationCoordinatesV1): DomainId = when (actor) {
        FoodColdActor.OWNER -> c.firstActorId
        FoodColdActor.ADMINISTRATOR -> administratorId
        FoodColdActor.KDS -> kdsId
    }
    fun login(actor: FoodColdActor): LocalLoginIdentifier = when (actor) {
        FoodColdActor.OWNER -> ownerLogin
        FoodColdActor.ADMINISTRATOR -> administratorLogin
        FoodColdActor.KDS -> kdsLogin
    }
    fun skuId(role: String): DomainId = input(100 + roles.indexOf(role).also { check(it >= 0) })
    fun productId(role: String): DomainId = input(200 + roles.indexOf(role).also { check(it >= 0) })
    fun skuPublicationId(role: String): DomainId = input(300 + roles.indexOf(role).also { check(it >= 0) })
    fun allocationId(role: String): DomainId = input(600 + roles.indexOf(role).also { check(it >= 0) })
    fun receiptLineId(role: String): DomainId = input(700 + roles.indexOf(role).also { check(it >= 0) })
    fun stockSourceId(role: String): DomainId = input(800 + roles.indexOf(role).also { check(it >= 0) })
    fun recipeId(role: String): DomainId = input(1000 + outputRoles.indexOf(role).also { check(it >= 0) })
    fun recipePublicationId(role: String): DomainId = input(1100 + outputRoles.indexOf(role).also { check(it >= 0) })
    fun choiceId(role: String): DomainId = input(1200 + outputRoles.indexOf(role).also { check(it >= 0) })
    fun routeId(role: String): DomainId = input(1300 + outputRoles.indexOf(role).also { check(it >= 0) })
    fun routeKey(role: String): DomainId = input(1400 + outputRoles.indexOf(role).also { check(it >= 0) })
    fun componentId(role: String, index: Int): DomainId = input(1500 + outputRoles.indexOf(role).also { check(it >= 0) } * 16 + index)
    fun unit(unit: String): CatalogUnit = when (unit) {
        "unit" -> CatalogUnit(input(1), Code.exact(unit), UnitDimension.COUNT, 0)
        "g" -> CatalogUnit(input(2), Code.exact(unit), UnitDimension.MASS, 0)
        "ml" -> CatalogUnit(input(3), Code.exact(unit), UnitDimension.VOLUME, 0)
        else -> error("Unsupported fixed unit")
    }
    fun quantity(role: String, value: Long): Quantity = Quantity(unit(if (role in outputRoles) "unit"
        else stocks.single { it.role == role }.unit).id, value, 0)
    fun selection(portion: FoodColdPortionInput) = LocalFoodPortionSelectionV1(portion.id, skuId(portion.product),
        recipeId(portion.product), Revision(1), choiceId(portion.product), emptyList(), quantity(portion.product, portion.quantity),
        itemNote = "", lineId = portion.group, seatNote = "")
    fun route(role: String) = LocalFoodRecipeRouteV1(routeId(role), recipeId(role), Revision(1), activity,
        LocalSalesChannel.DINE_IN, warehouseId, listOf(LocalFoodRouteTargetV1(routeKey(role), stationId)), true)

    /** These complete proposed sources are fixed before any prepare/approve producer. */
    fun sources(company: CompanyScope): FoodColdRequestedSources {
        val skus = roles.associateWith { role ->
            val line = lines.singleOrNull { it.product == role }
            val price = line?.let { LocalPriceV1(input(400 + roles.indexOf(role)), Revision(0), "DINE_IN", 0,
                quantity(role, 0), null, effectiveFrom, null,
                UnitPrice(Currency.EGP, ExactDecimal.parse(BigDecimal.valueOf(it.priceMinor, 2).toPlainString())),
                TaxInclusion.INCLUSIVE, TaxSnapshot(input(500 + roles.indexOf(role)), Revision(0),
                    "SYNTHETIC_ZERO", PercentageRate.parse("0"))) }
            LocalPublishedSkuV1(company, skuPublicationId(role), skuId(role), productId(role), Revision(0), Revision(1),
                LocalNamesV1(role, ""), "OFC101-${profile.uppercase()}-${role.uppercase()}", emptyList(),
                quantity(role, 1).unitId, 0, LocalTrackingV1.QUANTITY,
                if (line != null) LocalFulfillmentV1.MADE_TO_ORDER else LocalFulfillmentV1.RETAIL,
                requiresExpiry = false, canPurchase = line == null, canSell = line != null, active = true,
                conversions = emptyList(), prices = listOfNotNull(price), returnPromise = null,
                unitDefinitions = listOf(unit(if (line != null) "unit" else stocks.single { it.role == role }.unit)),
                productDefinition = LocalPublishedProductV1(productId(role), Revision(0), LocalNamesV1(role, ""),
                    emptyList(), emptyList(), emptyList(), LocalProductStatusV1.ACTIVE,
                    allowedVariantCombinations = listOf(emptyList())),
                variantValues = emptyList())
        }
        val recipes = outputRoles.associateWith { role -> LocalPublishedFoodRecipeV1(company, recipePublicationId(role),
            recipeId(role), Revision(1), skuId(role), Revision(1), quantity(role, 1), quantity(role, 1),
            listOf(LocalFoodRecipeChoiceV1(choiceId(role), LocalNamesV1("Standard", ""), emptyList(),
                components.getValue(role).mapIndexed { index, component ->
                    check(quantity(component.role, component.quantity).unitId == unit(component.unit).id)
                    LocalFoodIngredientDefinitionV1(componentId(role, index), skuId(component.role), Revision(1),
                        quantity(component.role, component.quantity))
                }, listOf(routeKey(role)), skuId(role))), true) }
        recipes.values.forEach { requireCompatibleLocalFoodRecipe(it, skus.values.associateBy { sku -> sku.skuId }) }
        val activity = LocalPublishedCompanyActivityProfileV1(company, activityPublicationId, activityProfileId,
            Revision(0), setOf(this.activity))
        fun proposal(kind: CompanyBusinessPublicationKind, publicationId: DomainId, entityId: DomainId, bytes: ByteArray) =
            try { CompanyBusinessPublicationProposal(company, kind, publicationId, entityId, bytes) } finally { bytes.fill(0) }
        val skuProposals = roles.map { role -> proposal(CompanyBusinessPublicationKind.CATALOG_SKU_V1,
            skuPublicationId(role), skuId(role), LocalConfigurationCodec.canonicalPublication(skus.getValue(role))) }
        val recipeProposals = outputRoles.map { role -> proposal(CompanyBusinessPublicationKind.FOOD_RECIPE_V1,
            recipePublicationId(role), recipeId(role), LocalFoodConfigurationCodecV1.canonicalPublication(recipes.getValue(role))) }
        val members = roles.mapIndexed { index, role -> LocalCatalogReleaseMemberV1(skuPublicationId(role),
            skuProposals[index].contentDigest, skuId(role), Revision(1), productId(role), Revision(0)) }
        val releases = mutableListOf<LocalPublishedCatalogReleaseV1>()
        val releaseProposals = mutableListOf<CompanyBusinessPublicationProposal>()
        var previous: LocalCatalogReleaseReferenceV1? = null
        for (revision in 0..5) {
            val definition = LocalPublishedCatalogReleaseV1(company, catalogId, input(1810 + revision), Revision(revision.toLong()),
                previous, members)
            val requested = makeNativeCatalogReleaseProposal(definition)
            releases += definition; releaseProposals += requested
            previous = LocalCatalogReleaseReferenceV1(catalogId, definition.publicationId, definition.revision, requested.contentDigest)
        }
        return FoodColdRequestedSources(company, skus, recipes, activity, releases.toList(),
            skuProposals + recipeProposals + activity.toNativeCompanyActivityProposal() + releaseProposals)
    }

    fun operation(suffix: Int): OperationId = OperationId(operationPrefix + suffix.toString().padStart(12, '0'))
    private fun input(suffix: Int): DomainId = DomainId(inputPrefix + suffix.toString().padStart(12, '0'))
    private fun fixtureUuid(suffix: Int): DomainId = DomainId("a60c0000-0000-4000-8000-" + suffix.toString().padStart(12, '0'))
    override fun toString(): String = "NativeFoodColdInputPlan([REDACTED])"

    companion object {
        const val BASE_VERSION = "1.1.0"
        const val ADJUNCT_VERSION = "1.0.0"
        const val IDENTITY_VARIANT = "OFC-COLD-ANCHOR-GROUP-1"
        const val CATALOG_VARIANT = "OFC-COLD-CATALOG-RELEASE5-1"
    }
}

internal enum class FoodColdActor { OWNER, ADMINISTRATOR, KDS }
internal data class FoodColdLineInput(val product: String, val originalGroup: DomainId, val anchor: DomainId,
    val quantity: Long, val priceMinor: Long, val totalMinor: Long, val portionIds: List<DomainId>)
internal data class FoodColdPortionInput(val id: DomainId, val product: String, val group: DomainId, val quantity: Long,
    val preparation: FoodPreparationStatus, val fulfillment: FoodFulfillmentStatus, val tasks: Int)
internal data class FoodColdStockInput(val role: String, val unit: String, val initial: Long, val available: Long,
    val reserved: Long, val issued: Long)
internal data class FoodColdComponentInput(val role: String, val unit: String, val quantity: Long)
internal class FoodColdRequestedSources(val company: CompanyScope, val skus: Map<String, LocalPublishedSkuV1>,
    val recipes: Map<String, LocalPublishedFoodRecipeV1>, val activity: LocalPublishedCompanyActivityProfileV1,
    val releases: List<LocalPublishedCatalogReleaseV1>, val proposals: List<CompanyBusinessPublicationProposal>) {
    fun proposal(publicationId: DomainId) = proposals.single { it.publicationId == publicationId }
    fun priceDigest(role: String): PayloadDigest = foodColdJson.encodeToString(LocalPriceV1.serializer(),
        skus.getValue(role).prices.single()).encodeToByteArray().let { bytes -> try { sha256(bytes) } finally { bytes.fill(0) } }
    override fun toString(): String = "FoodColdRequestedSources([REDACTED])"
}

internal val foodColdJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
internal fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
