package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Restores no authority and executes no command. The active native scan selects each
 * genuine BEFORE source, whose earlier business join must already be complete. Company
 * signatures, original routing constraints, exact effects and recovery resources remain
 * mandatory. Recipe installation is not ingredient issue, kitchen delivery or fulfillment. */
internal class LocalFoodConfigurationHistoryProducer(
    private val signatures: CompanyPublicationSignatureVerifier,
) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalFoodConfigurationProfileV1.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        requireFoodConfigurationProducer(original, signatures)
        val request = LocalFoodConfigurationCodecV1.decode(command.normalized)
        val (kind, id, action) = foodConfigurationTarget(request)
        val previous = source(command, kind, id)
        val expected = when (request) {
            is LocalFoodConfigurationRequestV1.Station -> request.expectedRevision
            is LocalFoodConfigurationRequestV1.Route -> request.expectedRevision
            is LocalFoodConfigurationRequestV1.InstallRecipe -> previous?.first?.revision
        }
        check(expected == previous?.first?.revision)
        command.requireRequirement(AuthorizationRequirement(Permission(action, PermissionVerb.ADMINISTER), command.scope,
            ActionBinding(id, previous?.first?.revision ?: Revision(0), command.semanticDigest, command.operationId, "food.configure")))
        val resources = mutableSetOf(foodConfigResource(id), foodConfigResource(command.actorId))
        previous?.second?.let {
            resources += foodConfigResource(it.actorId)
            resources += foodConfigResource(DomainId(it.operationId.value))
        }
        val claims = mutableListOf<ClaimChange>()
        var publicationKey: AggregateKey? = null
        when (request) {
            is LocalFoodConfigurationRequestV1.InstallRecipe -> {
                val signed = request.recipe
                val recipe = signed.definition
                verify(signed, command.scope)
                resources += foodConfigResource(signed.signingKeyId)
                resources += foodConfigResource(recipe.publicationId)
                val prior = (previous?.second?.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe?.definition
                check(prior == null || recipe.revision > prior.revision && recipe.outputSkuId == prior.outputSkuId)
                publicationKey = LocalFoodConfigurationProfileV1.key(LocalFoodConfigurationProfileV1.PUBLICATION, recipe.publicationId)
                check(command.aggregateSource(publicationKey) == null)
                val ids = (listOf(recipe.outputSkuId) + recipe.choices.map { it.pricingSkuId } +
                    recipe.choices.flatMap { it.ingredients }.map { it.skuId }).distinct()
                check(ids.size <= 24)
                val definitions = ids.associateWith { skuId ->
                    val row = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, skuId))?.row
                        ?: error("Original food recipe SKU unavailable")
                    val record = decodeRecord(command.scope, row)
                    val sku = record.signedSku()
                    val bytes = LocalConfigurationCodec.canonicalPublication(sku.definition)
                    try { check(signatures.verify(sku.definition.company, sku.signingKeyId, bytes, sku.signatureHex)) }
                    finally { bytes.fill(0) }
                    resources += companySourceResources(record, emptyList())
                    sku.definition
                }
                check(validFoodDefinition(recipe, definitions))
                val claim = ClaimKey(LocalFoodConfigurationProfileV1.CLAIM, "publication/${recipe.publicationId.value}")
                check(command.claim(claim) == null)
                claims += ClaimChange.Acquire(claim, recipe.recipeId)
            }
            is LocalFoodConfigurationRequestV1.Station -> {
                val prior = (previous?.second?.request as? LocalFoodConfigurationRequestV1.Station)?.station
                // The live contract forbids retirement/mode changes without a separate
                // outstanding-output reconciliation. History must not introduce a bypass.
                check(request.station.active && (prior == null || prior.mode == request.station.mode))
            }
            is LocalFoodConfigurationRequestV1.Route -> {
                val route = request.route
                val recipeSource = source(command, LocalFoodConfigurationProfileV1.RECIPE, route.recipeId)
                    ?: error("Original food route recipe unavailable")
                val recipe = (recipeSource.second.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe?.definition
                    ?: error("Original food route recipe incompatible")
                resources += foodConfigurationResources(recipeSource.second)
                val warehouseRow = command.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, route.warehouseId))?.row
                    ?: error("Original food route warehouse unavailable")
                val warehouse = decodeRecord(command.scope, warehouseRow)
                resources += foodConfigResource(warehouse.id)
                resources += foodConfigResource(warehouse.authorizedBy)
                resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, route.warehouseId)
                check(recipe.active && recipe.revision == route.recipeRevision &&
                    (warehouse.body as? ConfigurationBody.Warehouse)?.value?.active == true)
                check(route.targets.map { it.routeKey }.toSet() == recipe.choices.flatMap { it.routeKeys }.toSet())
                for (target in route.targets) {
                    val station = source(command, LocalFoodConfigurationProfileV1.STATION, target.stationId)
                        ?: error("Original food route station unavailable")
                    check((station.second.request as? LocalFoodConfigurationRequestV1.Station)?.station?.active == true)
                    resources += foodConfigurationResources(station.second)
                }
                check(recipe.choices.none { choice -> route.targets.filter { it.routeKey in choice.routeKeys }
                    .map { it.stationId }.distinct().size != choice.routeKeys.size })
                val prior = (previous?.second?.request as? LocalFoodConfigurationRequestV1.Route)?.route
                check(prior == null || prior.recipeId == route.recipeId && prior.activity == route.activity && prior.channel == route.channel)
                val claim = foodRouteClaim(route.recipeId, route.activity, route.channel)
                val owner = command.claim(claim)
                check(owner == null || owner == route.id)
                if (owner == null) claims += ClaimChange.Acquire(claim, route.id)
            }
        }
        val versionKey = LocalFoodConfigurationProfileV1.key(LocalFoodConfigurationProfileV1.VERSION, DomainId(command.operationId.value))
        check(command.aggregateSource(versionKey) == null)
        resources += foodConfigResource(versionKey.id)
        val revision = previous?.first?.revision?.next() ?: Revision(1)
        val record = FoodConfigurationRecordV1(command.scope, kind, id, revision, command.operationId,
            command.actorId, command.policyRevision, command.recordedAt, request)
        val bytes = encodeValue(record, FoodConfigurationRecordV1.serializer())
        val changes = listOf(
            AggregateChange(LocalFoodConfigurationProfileV1.key(kind, id), previous?.first?.revision,
                LocalFoodConfigurationProfileV1.schema(kind), bytes),
            AggregateChange(versionKey, null, LocalFoodConfigurationProfileV1.schema(versionKey.kind), bytes),
        ) + listOfNotNull(publicationKey?.let { AggregateChange(it, null, LocalFoodConfigurationProfileV1.schema(it.kind), bytes) })
        command.requireLegacyEffects(ProposedOutcome.Apply(encodeValue(LocalConfigurationResultV1("food.configuration.saved", id, revision),
            LocalConfigurationResultV1.serializer()), changes, claims), resources)
        return true
    }

    private suspend fun source(command: RuntimeHistoricalCommand, kind: String, id: DomainId): Pair<StoredAggregate, FoodConfigurationRecordV1>? {
        val source = command.aggregateSource(LocalFoodConfigurationProfileV1.key(kind, id)) ?: return null
        val record = decodeFoodConfigurationRecord(command.scope, source.row)
        val original = (source.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("Original food configuration unavailable")
        check(original.operationId == record.operationId)
        requireFoodConfigurationProducer(original, signatures)
        when (val request = record.request) {
            is LocalFoodConfigurationRequestV1.InstallRecipe -> check(command.claim(ClaimKey(LocalFoodConfigurationProfileV1.CLAIM,
                "publication/${request.recipe.definition.publicationId.value}")) == request.recipe.definition.recipeId)
            is LocalFoodConfigurationRequestV1.Route -> check(command.claim(foodRouteClaim(request.route.recipeId,
                request.route.activity, request.route.channel)) == request.route.id)
            is LocalFoodConfigurationRequestV1.Station -> Unit
        }
        return source.row to record
    }

    private fun verify(signed: LocalSignedFoodRecipeV1, scope: BranchScope) {
        val recipe = signed.definition
        val bytes = LocalFoodConfigurationCodecV1.canonicalPublication(recipe)
        try { check(recipe.company == scope.company() && signatures.verify(recipe.company, signed.signingKeyId, bytes, signed.signatureHex)) }
        finally { bytes.fill(0) }
    }
}
