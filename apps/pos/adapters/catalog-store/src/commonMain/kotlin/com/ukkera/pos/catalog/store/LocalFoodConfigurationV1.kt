package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

public object LocalFoodConfigurationProfileV1 {
    public val COMMAND: CommandKey = CommandKey("local.food.configuration", 1)
    public const val RECIPE: String = "local.food.recipe"
    public const val PUBLICATION: String = "local.food.publication"
    public const val STATION: String = "local.food.station"
    public const val ROUTE: String = "local.food.route"
    public const val VERSION: String = "local.food.configuration-version"
    public const val CLAIM: String = "local.food.configuration-identity"
    public fun key(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
    public fun schema(kind: String): String = "$kind/v1"
}

@Serializable
public data class LocalFoodIngredientDefinitionV1(public val componentId: DomainId, public val skuId: DomainId,
    public val skuRevision: Revision, public val quantityPerYield: Quantity) {
    init { require(quantityPerYield.scaled > 0) }
}

/** Complete permitted combinations. Required choices/exclusions cannot be skipped or interpreted by a cashier. */
@Serializable
public data class LocalFoodRecipeChoiceV1(
    public val id: DomainId, public val names: LocalNamesV1,
    public val modifierIds: List<DomainId>, public val ingredients: List<LocalFoodIngredientDefinitionV1>,
    public val routeKeys: List<DomainId>,
    /** Company explicitly maps this combination to a real current SKU price of the SAME product. */
    public val pricingSkuId: DomainId,
) {
    init {
        require(modifierIds.size <= 16 && modifierIds.distinct().size == modifierIds.size)
        require(ingredients.size in 1..16 && ingredients.map { it.componentId }.distinct().size == ingredients.size)
        require(routeKeys.size in 1..4 && routeKeys.distinct().size == routeKeys.size)
    }
}

/** Company-owned definitions only; branches do not author recipes by supplying a document ID. */
@Serializable
public data class LocalPublishedFoodRecipeV1(
    public val company: CompanyScope, public val publicationId: DomainId,
    public val recipeId: DomainId, public val revision: Revision,
    public val outputSkuId: DomainId, public val outputSkuRevision: Revision,
    public val yieldQuantity: Quantity, public val portionQuantity: Quantity,
    public val choices: List<LocalFoodRecipeChoiceV1>, public val active: Boolean,
) {
    init {
        require(revision.value > 0 && outputSkuRevision.value > 0 && yieldQuantity.scaled > 0 && portionQuantity.scaled > 0 &&
            yieldQuantity.unitId == portionQuantity.unitId && yieldQuantity.scale == portionQuantity.scale)
        require(choices.size in 1..8 && choices.map { it.id }.distinct().size == choices.size &&
            choices.map { it.modifierIds.toSet() }.distinct().size == choices.size)
        // A signed definition still must represent exact base-unit stock, never silently rounded grams.
        for (choice in choices) for (component in choice.ingredients) require(
            (component.quantityPerYield.toFraction() * portionQuantity.toFraction() / yieldQuantity.toFraction())
                .toScaledLongExact(component.quantityPerYield.scale) > 0)
    }
}
@Serializable
public data class LocalSignedFoodRecipeV1(public val definition: LocalPublishedFoodRecipeV1,
    public val signingKeyId: DomainId, public val signatureHex: String) {
    init { require(signatureHex.length == 128 && signatureHex.all { it in '0'..'9' || it in 'a'..'f' }) }
}
@Serializable
public data class LocalFoodStationDefinitionV1(public val id: DomainId, public val names: LocalNamesV1,
    public val mode: FoodStationDeliveryMode, public val active: Boolean)
@Serializable
public data class LocalFoodRouteTargetV1(public val routeKey: DomainId, public val stationId: DomainId)
@Serializable
public data class LocalFoodRecipeRouteV1(public val id: DomainId, public val recipeId: DomainId,
    public val recipeRevision: Revision, public val activity: LocalSalesActivity, public val channel: LocalSalesChannel,
    public val warehouseId: DomainId, public val targets: List<LocalFoodRouteTargetV1>, public val active: Boolean) {
    init {
        require(activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE))
        require(channel in setOf(LocalSalesChannel.DINE_IN, LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY,
            LocalSalesChannel.DELIVERY))
        require(targets.size in 1..8 && targets.map { it.routeKey }.distinct().size == targets.size)
    }
}
@Serializable
public sealed interface LocalFoodConfigurationRequestV1 {
    @Serializable @SerialName("INSTALL_RECIPE")
    public data class InstallRecipe(public val recipe: LocalSignedFoodRecipeV1) : LocalFoodConfigurationRequestV1
    @Serializable @SerialName("STATION")
    public data class Station(public val expectedRevision: Revision?, public val station: LocalFoodStationDefinitionV1) : LocalFoodConfigurationRequestV1
    @Serializable @SerialName("ROUTE")
    public data class Route(public val expectedRevision: Revision?, public val route: LocalFoodRecipeRouteV1) : LocalFoodConfigurationRequestV1
}
public object LocalFoodConfigurationCodecV1 {
    public fun encode(request: LocalFoodConfigurationRequestV1): StoredBytes = encodeValue(request, LocalFoodConfigurationRequestV1.serializer()).also {
        require(it.size <= 24_576)
        if (request is LocalFoodConfigurationRequestV1.InstallRecipe) require(
            signedPublication(request.recipe).encodeToByteArray(throwOnInvalidSequence = true).size <= LocalFoodProfileV1.MAX_SIGNED_RECIPE_BYTES)
    }
    public fun decode(bytes: StoredBytes): LocalFoodConfigurationRequestV1 {
        require(bytes.size <= 24_576)
        val value = decodeValue(bytes, LocalFoodConfigurationRequestV1.serializer())
        require(encode(value) == bytes)
        return value
    }
    public fun canonicalPublication(value: LocalPublishedFoodRecipeV1): ByteArray =
        ("ukkera.company-food-recipe/v1\n" + localConfigurationJson.encodeToString(LocalPublishedFoodRecipeV1.serializer(), value)).encodeToByteArray()
    public fun signedPublication(value: LocalSignedFoodRecipeV1): String =
        localConfigurationJson.encodeToString(LocalSignedFoodRecipeV1.serializer(), value)
    public fun command(operationId: OperationId, request: LocalFoodConfigurationRequestV1): MutationRequest =
        MutationRequest(operationId, LocalFoodConfigurationProfileV1.COMMAND, encode(request))
}

@Serializable
internal data class FoodConfigurationRecordV1(val scope: BranchScope, val kind: String, val id: DomainId,
    val revision: Revision, val operationId: OperationId, val actorId: DomainId, val policyRevision: Revision,
    val recordedAt: UtcInstant, val request: LocalFoodConfigurationRequestV1)

/** Same installed protected company publication trust as ordinary SKU activation; no new trust root. */
public class LocalFoodConfigurationHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterCommandHandler {
    override val key: CommandKey = LocalFoodConfigurationProfileV1.COMMAND
    override val writableKinds: Set<String> = setOf(LocalFoodConfigurationProfileV1.RECIPE, LocalFoodConfigurationProfileV1.PUBLICATION,
        LocalFoodConfigurationProfileV1.STATION, LocalFoodConfigurationProfileV1.ROUTE, LocalFoodConfigurationProfileV1.VERSION)
    override val claimNamespaces: Set<String> = setOf(LocalFoodConfigurationProfileV1.CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = try {
        LocalFoodConfigurationCodecV1.encode(LocalFoodConfigurationCodecV1.decode(input))
    } catch (_: ArithmeticException) {
        // This boundary decodes ONLY caller input. Exact serving/yield conversion in the
        // publication constructor can reject a fractional or out-of-range base quantity.
        // It is malformed input, not a storage failure and not a reason to retire the master.
        // Stored publications/producers below deliberately do NOT use this translation.
        throw IllegalArgumentException("food.recipe.quantity_not_representable")
    }
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.key == key && context.scope == view.scope && semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalFoodConfigurationCodecV1.decode(normalized)
        val (kind, id, action) = foodConfigurationTarget(request)
        val previous = foodConfiguration(view, kind, id, signatures)
        val expected = when (request) {
            is LocalFoodConfigurationRequestV1.Station -> request.expectedRevision
            is LocalFoodConfigurationRequestV1.Route -> request.expectedRevision
            is LocalFoodConfigurationRequestV1.InstallRecipe -> previous?.first?.revision
        }
        val resources = mutableSetOf(foodConfigResource(id), foodConfigResource(context.originalActorId))
        previous?.second?.let { resources += foodConfigResource(it.actorId); resources += foodConfigResource(DomainId(it.operationId.value)) }
        var rejection: String? = if (expected != previous?.first?.revision) "food.configuration.stale" else null
        var publicationKey: AggregateKey? = null
        val claims = mutableListOf<ClaimChange>()
        when (request) {
            is LocalFoodConfigurationRequestV1.InstallRecipe -> {
                val signed = request.recipe
                val recipe = signed.definition
                if (recipe.company != view.scope.company() || !signatures.verify(recipe.company, signed.signingKeyId,
                        LocalFoodConfigurationCodecV1.canonicalPublication(recipe), signed.signatureHex)) rejection = "food.recipe.signature_required"
                resources += foodConfigResource(signed.signingKeyId)
                resources += foodConfigResource(recipe.publicationId)
                val prior = (previous?.second?.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe?.definition
                if (prior != null && (recipe.revision <= prior.revision || recipe.outputSkuId != prior.outputSkuId)) rejection = "food.recipe.version_conflict"
                publicationKey = LocalFoodConfigurationProfileV1.key(LocalFoodConfigurationProfileV1.PUBLICATION, recipe.publicationId)
                if (view.aggregate(publicationKey) != null) rejection = "food.recipe.publication_used"
                val definitions = mutableMapOf<DomainId, LocalPublishedSkuV1>()
                val ids = (listOf(recipe.outputSkuId) + recipe.choices.map { it.pricingSkuId } +
                    recipe.choices.flatMap { it.ingredients }.map { it.skuId }).distinct()
                if (ids.size > 24) rejection = "food.configuration.capacity"
                else for (skuId in ids) {
                    val source = readSignedSku(view, LocalConfigurationProfile.SKU, skuId, signatures)
                    if (source == null) rejection = "food.recipe.sku_required" else {
                        definitions[skuId] = source.second.signedSku().definition
                        resources += companySourceResources(source.second, emptyList())
                    }
                }
                if (!validFoodDefinition(recipe, definitions)) rejection = "food.recipe.definition_incompatible"
                val identity = ClaimKey(LocalFoodConfigurationProfileV1.CLAIM, "publication/${recipe.publicationId.value}")
                if (view.claim(identity) != null) rejection = "food.recipe.publication_used"
                claims += ClaimChange.Acquire(identity, recipe.recipeId)
            }
            is LocalFoodConfigurationRequestV1.Station -> {
                // Mode/retirement changes require draining and uncertain-output reconciliation;
                // this cut never edits routing underneath outstanding paper or display work.
                val old = (previous?.second?.request as? LocalFoodConfigurationRequestV1.Station)?.station
                if (!request.station.active || old != null && old.mode != request.station.mode) rejection = "food.station.reconciliation_required"
            }
            is LocalFoodConfigurationRequestV1.Route -> {
                val route = request.route
                val recipeRecord = foodConfiguration(view, LocalFoodConfigurationProfileV1.RECIPE, route.recipeId, signatures)
                val recipe = (recipeRecord?.second?.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe?.definition
                recipeRecord?.second?.let { resources += foodConfigurationResources(it) }
                val warehouse = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, route.warehouseId))?.let { decodeRecord(view.scope, it) }
                warehouse?.let { resources += foodConfigResource(it.id); resources += foodConfigResource(it.authorizedBy) }
                resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, route.warehouseId)
                if (recipe == null || !recipe.active || recipe.revision != route.recipeRevision ||
                    (warehouse?.body as? ConfigurationBody.Warehouse)?.value?.active != true) rejection = "food.route.current_source_required"
                if (recipe != null && route.targets.map { it.routeKey }.toSet() != recipe.choices.flatMap { it.routeKeys }.toSet()) rejection = "food.route.incomplete"
                for (target in route.targets) {
                    val station = foodConfiguration(view, LocalFoodConfigurationProfileV1.STATION, target.stationId, signatures)
                    if ((station?.second?.request as? LocalFoodConfigurationRequestV1.Station)?.station?.active != true) rejection = "food.route.station_required"
                    station?.second?.let { resources += foodConfigurationResources(it) }
                }
                if (recipe != null && recipe.choices.any { choice -> route.targets.filter { it.routeKey in choice.routeKeys }
                        .map { it.stationId }.distinct().size != choice.routeKeys.size }) rejection = "food.route.duplicate_station"
                val old = (previous?.second?.request as? LocalFoodConfigurationRequestV1.Route)?.route
                if (old != null && (old.recipeId != route.recipeId || old.activity != route.activity || old.channel != route.channel)) rejection = "food.route.identity_changed"
                val claim = foodRouteClaim(route.recipeId, route.activity, route.channel)
                val owner = view.claim(claim)
                if (owner != null && owner != route.id) rejection = "food.route.duplicate"
                if (owner == null) claims += ClaimChange.Acquire(claim, route.id)
            }
        }
        val requirement = AuthorizationRequirement(Permission(action, PermissionVerb.ADMINISTER), view.scope,
            ActionBinding(id, previous?.first?.revision ?: Revision(0), context.semanticDigest, context.operationId, "food.configure"))
        val versionKey = LocalFoodConfigurationProfileV1.key(LocalFoodConfigurationProfileV1.VERSION, DomainId(context.operationId.value))
        check(view.aggregate(versionKey) == null)
        val scope = view.scope
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = requirement
            override val affectedResources: Set<RecoveryResourceKey> = resources + foodConfigResource(versionKey.id)
            override val allowsActionApproval: Boolean = false
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId))
                if (rejection != null) return foodConfigurationReject(checkNotNull(rejection))
                val revision = previous?.first?.revision?.next() ?: Revision(1)
                val record = FoodConfigurationRecordV1(scope, kind, id, revision, context.operationId,
                    context.originalActorId, authorization.policyRevision, recordedAt, request)
                val bytes = encodeValue(record, FoodConfigurationRecordV1.serializer())
                val changes = listOf(AggregateChange(LocalFoodConfigurationProfileV1.key(kind, id), previous?.first?.revision,
                    LocalFoodConfigurationProfileV1.schema(kind), bytes), AggregateChange(versionKey, null,
                    LocalFoodConfigurationProfileV1.schema(versionKey.kind), bytes)) + listOfNotNull(publicationKey?.let {
                    AggregateChange(it, null, LocalFoodConfigurationProfileV1.schema(it.kind), bytes)
                })
                return ProposedOutcome.Apply(encodeValue(LocalConfigurationResultV1("food.configuration.saved", id, revision),
                    LocalConfigurationResultV1.serializer()), changes, claims)
            }
        }
    }
}

internal fun foodConfigurationTarget(request: LocalFoodConfigurationRequestV1): Triple<String, DomainId, SecurityAction> = when (request) {
    is LocalFoodConfigurationRequestV1.InstallRecipe -> Triple(LocalFoodConfigurationProfileV1.RECIPE, request.recipe.definition.recipeId, SecurityAction.CATALOG_ACTIVATE)
    is LocalFoodConfigurationRequestV1.Station -> Triple(LocalFoodConfigurationProfileV1.STATION, request.station.id, SecurityAction.KITCHEN_TASK)
    is LocalFoodConfigurationRequestV1.Route -> Triple(LocalFoodConfigurationProfileV1.ROUTE, request.route.id, SecurityAction.WAREHOUSE_CONFIGURE)
}
internal fun foodRouteClaim(recipeId: DomainId, activity: LocalSalesActivity, channel: LocalSalesChannel): ClaimKey =
    ClaimKey(LocalFoodConfigurationProfileV1.CLAIM, "route/${recipeId.value}/${activity.name}/${channel.name}")
internal fun foodConfigResource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)
internal fun foodConfigurationResources(record: FoodConfigurationRecordV1): Set<RecoveryResourceKey> =
    setOf(foodConfigResource(record.id), foodConfigResource(record.actorId), foodConfigResource(DomainId(record.operationId.value)))
private fun foodConfigurationReject(code: String): ProposedOutcome = ProposedOutcome.Reject(code,
    encodeValue(LocalConfigurationResultV1(code, null, null), LocalConfigurationResultV1.serializer()))

internal suspend fun foodConfiguration(view: CommandReadView, kind: String, id: DomainId,
    signatures: CompanyPublicationSignatureVerifier): Pair<StoredAggregate, FoodConfigurationRecordV1>? {
    val row = view.aggregate(LocalFoodConfigurationProfileV1.key(kind, id)) ?: return null
    val record = decodeFoodConfigurationRecord(view.scope, row)
    val evidence = view.retainedCommand(record.operationId) ?: error("food.configuration.producer_missing")
    requireFoodConfigurationProducer(evidence, signatures)
    check(evidence.aggregateWrites.singleOrNull { it.key == row.key }?.let {
        it.payload == row.payload && it.payloadDigest == row.digest && it.storageGeneration == row.revision && it.schema == row.schema
    } == true)
    when (val request = record.request) {
        is LocalFoodConfigurationRequestV1.InstallRecipe -> check(view.claim(ClaimKey(LocalFoodConfigurationProfileV1.CLAIM,
            "publication/${request.recipe.definition.publicationId.value}")) == request.recipe.definition.recipeId)
        is LocalFoodConfigurationRequestV1.Route -> check(view.claim(foodRouteClaim(request.route.recipeId,
            request.route.activity, request.route.channel)) == request.route.id)
        is LocalFoodConfigurationRequestV1.Station -> Unit
    }
    return row to record
}

/** Canonical stored data only. Both live and native-history callers must separately join
 * this row to its real retained producer and verify the relevant identity claims. */
internal fun decodeFoodConfigurationRecord(scope: BranchScope, row: StoredAggregate): FoodConfigurationRecordV1 {
    val (kind, id) = row.key
    check(row.schema == LocalFoodConfigurationProfileV1.schema(kind))
    val bytes = row.payload.copyBytes()
    try { check(sha256(bytes) == row.digest) } finally { bytes.fill(0) }
    val record = decodeValue(row.payload, FoodConfigurationRecordV1.serializer())
    check(encodeValue(record, FoodConfigurationRecordV1.serializer()) == row.payload && record.scope == scope)
    if (kind == LocalFoodConfigurationProfileV1.VERSION) check(row.revision == Revision(1) && id.value == record.operationId.value)
    else if (kind == LocalFoodConfigurationProfileV1.PUBLICATION) check(row.revision == Revision(1) &&
        (record.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe?.definition?.publicationId == id)
    else check(record.id == id && record.kind == kind && record.revision == row.revision)
    return record
}

internal fun requireFoodConfigurationProducer(evidence: RetainedCommandEvidence, signatures: CompanyPublicationSignatureVerifier) {
    check(evidence.committed && evidence.command == LocalFoodConfigurationProfileV1.COMMAND && evidence.jobs.isEmpty())
    val request = LocalFoodConfigurationCodecV1.decode(evidence.normalized)
    check(semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest)
    val (kind, id, _) = foodConfigurationTarget(request)
    val current = evidence.aggregateWrites.single { it.key == LocalFoodConfigurationProfileV1.key(kind, id) }
    val record = decodeValue(current.payload, FoodConfigurationRecordV1.serializer())
    check(record.scope == evidence.scope && record.kind == kind && record.id == id && record.revision.value >= 1 && record.revision == current.storageGeneration &&
        record.operationId == evidence.operationId && record.actorId == evidence.originalActorId && record.policyRevision == evidence.policyRevision &&
        record.recordedAt == evidence.recordedAt && record.request == request && encodeValue(record, FoodConfigurationRecordV1.serializer()) == current.payload)
    val keys = mutableSetOf(current.key, LocalFoodConfigurationProfileV1.key(LocalFoodConfigurationProfileV1.VERSION, DomainId(evidence.operationId.value)))
    val expectedClaims = mutableListOf<ClaimChange.Acquire>()
    if (request is LocalFoodConfigurationRequestV1.InstallRecipe) {
        val signed = request.recipe
        check(signed.definition.company == evidence.scope.company() && signatures.verify(signed.definition.company, signed.signingKeyId,
            LocalFoodConfigurationCodecV1.canonicalPublication(signed.definition), signed.signatureHex))
        keys += LocalFoodConfigurationProfileV1.key(LocalFoodConfigurationProfileV1.PUBLICATION, signed.definition.publicationId)
        expectedClaims += ClaimChange.Acquire(ClaimKey(LocalFoodConfigurationProfileV1.CLAIM, "publication/${signed.definition.publicationId.value}"), id)
    }
    if (request is LocalFoodConfigurationRequestV1.Route && record.revision == Revision(1)) expectedClaims +=
        ClaimChange.Acquire(foodRouteClaim(request.route.recipeId, request.route.activity, request.route.channel), id)
    when (request) {
        is LocalFoodConfigurationRequestV1.Station -> check(request.station.active &&
            record.revision == (request.expectedRevision?.next() ?: Revision(1)))
        is LocalFoodConfigurationRequestV1.Route -> check(record.revision == (request.expectedRevision?.next() ?: Revision(1)))
        is LocalFoodConfigurationRequestV1.InstallRecipe -> Unit
    }
    check(evidence.aggregateWrites.map { it.key }.toSet() == keys && evidence.aggregateWrites.size == keys.size && evidence.aggregateWrites.all {
        it.schema == LocalFoodConfigurationProfileV1.schema(it.key.kind) && it.payload == current.payload &&
            it.payloadDigest == sha256(it.payload.copyBytes()) && (it.key == current.key || it.storageGeneration == Revision(1))
    })
    check(evidence.claimChanges.size == expectedClaims.size && expectedClaims.all { expected -> evidence.claimChanges.any {
        it.action == RetainedClaimAction.ACQUIRE && it.key == expected.key && it.ownerId == expected.ownerId
    } })
    check(evidence.result == encodeValue(LocalConfigurationResultV1("food.configuration.saved", id, record.revision), LocalConfigurationResultV1.serializer()) &&
        evidence.resultHash == sha256(evidence.result.copyBytes()))
}

internal fun validFoodDefinition(recipe: LocalPublishedFoodRecipeV1, definitions: Map<DomainId, LocalPublishedSkuV1>): Boolean {
    val output = definitions[recipe.outputSkuId] ?: return false
    if (!output.active || !output.canSell || output.fulfillment != LocalFulfillmentV1.MADE_TO_ORDER ||
        output.skuRevision != recipe.outputSkuRevision || output.baseUnitId != recipe.portionQuantity.unitId ||
        output.quantityScale != recipe.portionQuantity.scale) return false
    for (choice in recipe.choices) {
        val price = definitions[choice.pricingSkuId] ?: return false
        if (!price.active || !price.canSell || price.fulfillment != LocalFulfillmentV1.MADE_TO_ORDER ||
            price.productId != output.productId || price.baseUnitId != output.baseUnitId || price.quantityScale != output.quantityScale) return false
        for (component in choice.ingredients) {
            val sku = definitions[component.skuId] ?: return false
            // Raw stocked components only. Nested recipes/batches/kits need their own timing proof;
            // disallowing them here also prevents indirect recipe cycles and serial fractions.
            if (!sku.active || sku.fulfillment != LocalFulfillmentV1.RETAIL || sku.tracking !in setOf(LocalTrackingV1.QUANTITY, LocalTrackingV1.LOT) ||
                sku.skuRevision != component.skuRevision || sku.baseUnitId != component.quantityPerYield.unitId ||
                sku.quantityScale != component.quantityPerYield.scale || sku.skuId == output.skuId) return false
        }
    }
    return true
}
