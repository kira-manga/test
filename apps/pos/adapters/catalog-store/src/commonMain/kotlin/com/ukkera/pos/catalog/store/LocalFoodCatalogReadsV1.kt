package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

@Serializable
public data class LocalFoodMenuQueryV1(public val recipeId: DomainId? = null, public val after: DomainId? = null, public val limit: Int = 20) {
    init { require(limit in 1..20 && (recipeId == null || after == null)) }
}
@Serializable
public data class LocalFoodMenuChoiceV1(public val choiceId: DomainId, public val names: LocalNamesV1,
    public val modifierIds: List<DomainId>, public val pricingSkuId: DomainId)
@Serializable
public data class LocalFoodMenuRecipeV1(public val recipeId: DomainId, public val recipeRevision: Revision,
    public val outputSkuId: DomainId, public val portionQuantity: Quantity, public val active: Boolean,
    public val choices: List<LocalFoodMenuChoiceV1>)
@Serializable
public data class LocalFoodMenuPageV1(public val catalogRevision: Revision?, public val recipes: List<LocalFoodMenuRecipeV1>, public val nextAfter: DomainId?)

public object LocalFoodMenuCodecV1 {
    public val QUERY: ReadKey = ReadKey("local.food.menu", 1)
    public fun read(query: LocalFoodMenuQueryV1): ReadRequest = ReadRequest(QUERY, encodeValue(query, LocalFoodMenuQueryV1.serializer()))
    public fun result(bytes: StoredBytes): LocalFoodMenuPageV1 = decodeValue(bytes, LocalFoodMenuPageV1.serializer())
}

/** Public menu selectors without raw signatures, component costs, warehouse stock or other owners' orders. */
public class LocalFoodMenuReadHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterReadHandler {
    override val key: ReadKey = LocalFoodMenuCodecV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes {
        require(input.size <= 2_048)
        val value = decodeValue(input, LocalFoodMenuQueryV1.serializer())
        return encodeValue(value, LocalFoodMenuQueryV1.serializer()).also { require(it == input) }
    }
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS && normalize(normalized) == normalized
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope)
        val query = decodeValue(normalized, LocalFoodMenuQueryV1.serializer())
        val keys: List<AggregateKey>
        val next: DomainId?
        if (query.recipeId != null) { keys = listOf(LocalFoodConfigurationProfileV1.key(LocalFoodConfigurationProfileV1.RECIPE, query.recipeId)); next = null }
        else {
            val page = view.aggregateKeys(LocalFoodConfigurationProfileV1.RECIPE, query.after, query.limit)
            keys = page.keys; next = page.nextAfter
        }
        val recipes = keys.mapNotNull { selected ->
            val stored = foodConfiguration(view, selected.kind, selected.id, signatures)
            check(stored != null || query.recipeId != null) { "food.menu.listed_recipe_missing" }
            stored?.second?.let { record ->
                val recipe = (record.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe.definition
                LocalFoodMenuRecipeV1(recipe.recipeId, recipe.revision, recipe.outputSkuId, recipe.portionQuantity,
                    recipe.active, recipe.choices.map { LocalFoodMenuChoiceV1(it.id, it.names, it.modifierIds, it.pricingSkuId) })
            }
        }
        val cursor = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.CATALOG_CURSOR, view.scope.branchId))?.let { decodeRecord(view.scope, it) }
        check(cursor == null || cursor.body is ConfigurationBody.CatalogCursor)
        val requirement = AuthorizationRequirement(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), view.scope)
        val result = LocalFoodMenuPageV1(cursor?.revision, recipes, next)
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().isBoundTo(requirement, context.actorId))
                return encodeValue(result, LocalFoodMenuPageV1.serializer())
            }
        }
    }
}
