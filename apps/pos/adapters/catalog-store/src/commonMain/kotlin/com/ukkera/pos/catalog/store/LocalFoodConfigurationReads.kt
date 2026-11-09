package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer

@Serializable
public enum class LocalFoodConfigurationSourceKindV1 { SKU, RECIPE, STATION, ROUTE }

@Serializable
public data class LocalFoodConfigurationQueryV1(public val kind: LocalFoodConfigurationSourceKindV1,
    public val id: DomainId? = null, public val after: DomainId? = null, public val limit: Int = 20) {
    init { require(limit in 1..20 && (id == null || after == null)) }
}

@Serializable
public data class LocalFoodConfigurationLabelV1(public val id: DomainId, public val revision: Revision,
    public val storageRevision: Revision, public val names: LocalNamesV1?, public val active: Boolean)

/** Unsigned current descriptive definitions only. No publication signature, cost, authority or task rows. */
@Serializable
public sealed interface LocalFoodConfigurationDetailV1 {
    @Serializable @SerialName("SKU")
    public data class Sku(public val definition: LocalPublishedSkuV1) : LocalFoodConfigurationDetailV1
    @Serializable @SerialName("RECIPE")
    public data class Recipe(public val definition: LocalPublishedFoodRecipeV1) : LocalFoodConfigurationDetailV1
    @Serializable @SerialName("STATION")
    public data class Station(public val definition: LocalFoodStationDefinitionV1) : LocalFoodConfigurationDetailV1
    @Serializable @SerialName("ROUTE")
    public data class Route(public val definition: LocalFoodRecipeRouteV1) : LocalFoodConfigurationDetailV1
}
@Serializable
public data class LocalFoodConfigurationPageV1(public val kind: LocalFoodConfigurationSourceKindV1,
    public val labels: List<LocalFoodConfigurationLabelV1>, public val nextAfter: DomainId?,
    public val detail: LocalFoodConfigurationDetailV1? = null) {
    init {
        require(labels.size <= 20 && labels.map { it.id }.distinct().size == labels.size)
        require(detail == null || labels.size == 1 && nextAfter == null)
    }
}

public object LocalFoodConfigurationReadCodecV1 {
    public val QUERY: ReadKey = ReadKey("local.food.configuration-read", 1)
    public fun read(query: LocalFoodConfigurationQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalFoodConfigurationQueryV1): StoredBytes = foodReadEncode(query, LocalFoodConfigurationQueryV1.serializer(), 2_048)
    public fun result(bytes: StoredBytes): LocalFoodConfigurationPageV1 = foodReadDecode(bytes, LocalFoodConfigurationPageV1.serializer(), 65_536)
}

/** Same actual signed catalog/retained food producers and loan; bounded setup selection, not task authority. */
public class LocalFoodConfigurationReadHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterReadHandler {
    override val key: ReadKey = LocalFoodConfigurationReadCodecV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodConfigurationReadCodecV1.encode(
        foodReadDecode(input, LocalFoodConfigurationQueryV1.serializer(), 2_048)).also { require(it == input) }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope)
        val query = foodReadDecode(normalized, LocalFoodConfigurationQueryV1.serializer(), 2_048)
        val kind = when (query.kind) {
            LocalFoodConfigurationSourceKindV1.SKU -> LocalConfigurationProfile.SKU
            LocalFoodConfigurationSourceKindV1.RECIPE -> LocalFoodConfigurationProfileV1.RECIPE
            LocalFoodConfigurationSourceKindV1.STATION -> LocalFoodConfigurationProfileV1.STATION
            LocalFoodConfigurationSourceKindV1.ROUTE -> LocalFoodConfigurationProfileV1.ROUTE
        }
        val page = if (query.id == null) view.aggregateKeys(kind, query.after, query.limit) else null
        val keys = page?.keys ?: listOf(AggregateKey(kind, requireNotNull(query.id)))
        var detail: LocalFoodConfigurationDetailV1? = null
        val labels = keys.mapNotNull { selected ->
            check(selected.kind == kind)
            if (query.kind == LocalFoodConfigurationSourceKindV1.SKU) {
                val stored = readSignedSku(view, kind, selected.id, signatures)
                check(stored != null || page == null) { "food.configuration.listed_sku_missing" }
                stored?.let { (row, record) ->
                    val sku = record.signedSku().definition
                    if (page == null) detail = LocalFoodConfigurationDetailV1.Sku(sku)
                    LocalFoodConfigurationLabelV1(sku.skuId, sku.skuRevision, row.revision, sku.names, sku.active)
                }
            } else {
                val stored = foodConfiguration(view, kind, selected.id, signatures)
                check(stored != null || page == null) { "food.configuration.listed_source_missing" }
                stored?.let { (row, record) -> when (val request = record.request) {
                    is LocalFoodConfigurationRequestV1.InstallRecipe -> {
                        check(query.kind == LocalFoodConfigurationSourceKindV1.RECIPE)
                        val recipe = request.recipe.definition
                        if (page == null) detail = LocalFoodConfigurationDetailV1.Recipe(recipe)
                        LocalFoodConfigurationLabelV1(recipe.recipeId, recipe.revision, row.revision, recipe.choices.first().names, recipe.active)
                    }
                    is LocalFoodConfigurationRequestV1.Station -> {
                        check(query.kind == LocalFoodConfigurationSourceKindV1.STATION)
                        if (page == null) detail = LocalFoodConfigurationDetailV1.Station(request.station)
                        LocalFoodConfigurationLabelV1(request.station.id, row.revision, row.revision, request.station.names, request.station.active)
                    }
                    is LocalFoodConfigurationRequestV1.Route -> {
                        check(query.kind == LocalFoodConfigurationSourceKindV1.ROUTE)
                        if (page == null) detail = LocalFoodConfigurationDetailV1.Route(request.route)
                        LocalFoodConfigurationLabelV1(request.route.id, row.revision, row.revision, null, request.route.active)
                    }
                } }
            }
        }
        val requirement = AuthorizationRequirement(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), context.scope)
        val result = LocalFoodConfigurationPageV1(query.kind, labels, page?.nextAfter, detail)
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().isBoundTo(requirement, context.actorId))
                // Capacity failure is unavailable, never a truncated/empty definition or source list.
                return foodReadEncode(result, LocalFoodConfigurationPageV1.serializer(), 65_536)
            }
        }
    }
}

private fun <T> foodReadEncode(value: T, serializer: KSerializer<T>, maximum: Int): StoredBytes {
    val bytes = localConfigurationJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    try { require(bytes.size <= maximum); return StoredBytes(bytes) } finally { bytes.fill(0) }
}
private fun <T> foodReadDecode(bytes: StoredBytes, serializer: KSerializer<T>, maximum: Int): T {
    require(bytes.size <= maximum)
    val raw = bytes.copyBytes()
    val value = try { localConfigurationJson.decodeFromString(serializer, raw.decodeToString(throwOnInvalidSequence = true)) }
        finally { raw.fill(0) }
    require(foodReadEncode(value, serializer, maximum) == bytes)
    return value
}
