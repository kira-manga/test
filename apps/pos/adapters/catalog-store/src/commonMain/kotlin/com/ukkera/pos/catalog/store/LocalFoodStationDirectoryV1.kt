package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface LocalFoodStationDirectoryQueryV1 {
    @Serializable @SerialName("CONFIGURED")
    public data class Configured(public val after: DomainId? = null, public val limit: Int = 20) : LocalFoodStationDirectoryQueryV1 {
        init { require(limit in 1..20) }
    }
    @Serializable @SerialName("STATION")
    public data class Station(public val stationId: DomainId) : LocalFoodStationDirectoryQueryV1
}

@Serializable
public data class LocalFoodStationLabelV1(public val stationId: DomainId, public val names: LocalNamesV1,
    public val mode: FoodStationDeliveryMode, public val active: Boolean, public val configurationStorageRevision: Revision)
@Serializable
public enum class LocalFoodStationDirectoryUnavailableV1 { TARGET_UNAVAILABLE, CAPACITY_REQUIRED }
@Serializable
public data class LocalFoodStationDirectoryResultV1(public val stations: List<LocalFoodStationLabelV1> = emptyList(),
    public val nextAfter: DomainId? = null, public val unavailable: LocalFoodStationDirectoryUnavailableV1? = null) {
    init {
        require(stations.size <= 20 && stations.map { it.stationId }.distinct().size == stations.size)
        require(unavailable == null || stations.isEmpty() && nextAfter == null)
        require(nextAfter == null || stations.isNotEmpty() && stations.last().stationId == nextAfter)
    }
}

public object LocalFoodStationDirectoryCodecV1 {
    public val QUERY: ReadKey = ReadKey("local.food.station-directory", 1)
    public fun read(query: LocalFoodStationDirectoryQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun encode(query: LocalFoodStationDirectoryQueryV1): StoredBytes =
        stationEncode(query, LocalFoodStationDirectoryQueryV1.serializer(), 2_048)
    public fun decode(bytes: StoredBytes): LocalFoodStationDirectoryQueryV1 =
        stationDecode(bytes, LocalFoodStationDirectoryQueryV1.serializer(), 2_048)
    public fun result(bytes: StoredBytes): LocalFoodStationDirectoryResultV1 =
        stationDecode(bytes, LocalFoodStationDirectoryResultV1.serializer(), 65_536)
}

/** Catalog discovery is not station assignment. Direct lookup requires the exact station grant. */
public class LocalFoodStationDirectoryReadHandler(private val signatures: CompanyPublicationSignatureVerifier) : MasterReadHandler {
    override val key: ReadKey = LocalFoodStationDirectoryCodecV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodStationDirectoryCodecV1.encode(
        stationDecode(input, LocalFoodStationDirectoryQueryV1.serializer(), 2_048)).also { require(it == input) }
    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.KITCHEN) return false
        return when (LocalFoodStationDirectoryCodecV1.decode(normalized)) {
            is LocalFoodStationDirectoryQueryV1.Configured, is LocalFoodStationDirectoryQueryV1.Station -> true
        }
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key)
        val query = stationDecode(normalized, LocalFoodStationDirectoryQueryV1.serializer(), 2_048)
        val keys: List<AggregateKey>
        val next: DomainId?
        when (query) {
            is LocalFoodStationDirectoryQueryV1.Configured -> {
                val page = view.aggregateKeys(LocalFoodConfigurationProfileV1.STATION, query.after, query.limit)
                keys = page.keys; next = page.nextAfter
            }
            is LocalFoodStationDirectoryQueryV1.Station -> {
                keys = listOf(LocalFoodConfigurationProfileV1.key(LocalFoodConfigurationProfileV1.STATION, query.stationId)); next = null
            }
        }
        // At most20 current station rows and their actual whole retained producers. No recipes,
        // task/owner reads, finance, arbitrary source strings or caller-authored labels enter R1.
        val labels = keys.mapNotNull { selected ->
            check(selected.kind == LocalFoodConfigurationProfileV1.STATION)
            val stored = foodConfiguration(view, selected.kind, selected.id, signatures)
            check(stored != null || query is LocalFoodStationDirectoryQueryV1.Station) { "food.station.listed_missing" }
            stored?.let { (row, record) ->
                val station = (record.request as LocalFoodConfigurationRequestV1.Station).station
                check(station.id == selected.id)
                LocalFoodStationLabelV1(station.id, station.names, station.mode, station.active, row.revision)
            }
        }
        val requirement = when (query) {
            is LocalFoodStationDirectoryQueryV1.Configured -> AuthorizationRequirement(
                Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), context.scope)
            is LocalFoodStationDirectoryQueryV1.Station -> AuthorizationRequirement(
                Permission(SecurityAction.KITCHEN_TASK, PermissionVerb.PERFORM), context.scope,
                ActionBinding(query.stationId, labels.singleOrNull()?.configurationStorageRevision ?: Revision(0),
                    context.semanticDigest, null, "food.station-label"))
        }
        val result = if (query is LocalFoodStationDirectoryQueryV1.Station && labels.isEmpty())
            LocalFoodStationDirectoryResultV1(unavailable = LocalFoodStationDirectoryUnavailableV1.TARGET_UNAVAILABLE)
        else LocalFoodStationDirectoryResultV1(labels, next)
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().isBoundTo(requirement, context.actorId))
                return try { stationEncode(result, LocalFoodStationDirectoryResultV1.serializer(), 65_536) }
                catch (_: StationDirectoryCapacity) { stationEncode(LocalFoodStationDirectoryResultV1(
                    unavailable = LocalFoodStationDirectoryUnavailableV1.CAPACITY_REQUIRED), LocalFoodStationDirectoryResultV1.serializer(), 65_536) }
            }
        }
    }
}

private class StationDirectoryCapacity : RuntimeException()
private fun <T> stationEncode(value: T, serializer: KSerializer<T>, maximum: Int): StoredBytes {
    val bytes = localConfigurationJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    if (bytes.size > minOf(maximum, 65_536)) throw StationDirectoryCapacity()
    return StoredBytes(bytes)
}
private fun <T> stationDecode(bytes: StoredBytes, serializer: KSerializer<T>, maximum: Int): T {
    require(bytes.size <= minOf(maximum, 65_536))
    val value = localConfigurationJson.decodeFromString(serializer, bytes.copyBytes().decodeToString(throwOnInvalidSequence = true))
    require(stationEncode(value, serializer, maximum) == bytes)
    return value
}
