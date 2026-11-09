package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public sealed interface LocalFoodFloorQueryV1 {
    @Serializable @SerialName("FLOORS")
    public data class Floors(public val after: DomainId? = null, public val limit: Int = 20) : LocalFoodFloorQueryV1 {
        init { require(limit in 1..20) }
    }
    @Serializable @SerialName("TABLES")
    public data class Tables(public val floorId: DomainId, public val after: DomainId? = null,
        public val limit: Int = 20) : LocalFoodFloorQueryV1 {
        init { require(limit in 1..20) }
    }
    @Serializable @SerialName("TABLE")
    public data class Table(public val tableId: DomainId) : LocalFoodFloorQueryV1
}

@Serializable
public data class LocalFoodFloorRowV1(public val definition: LocalFoodFloorDefinitionV1, public val storageRevision: Revision) {
    init { require(storageRevision.value > 0) }
}
@Serializable
public data class LocalFoodTableRowV1(public val definition: LocalFoodTableDefinitionV1, public val storageRevision: Revision) {
    init { require(storageRevision.value > 0) }
}

/** Actual configuration and descriptive state only. No occupancy, order ID, owner or financial fields. */
@Serializable
public data class LocalFoodFloorPageV1(
    public val query: LocalFoodFloorQueryV1,
    public val configured: Boolean,
    public val floors: List<LocalFoodFloorRowV1> = emptyList(),
    public val tables: List<LocalFoodTableRowV1> = emptyList(),
    public val floor: LocalFoodFloorRowV1? = null,
    public val nextAfter: DomainId? = null,
) {
    init {
        require(floors.size <= 20 && tables.size <= 20 &&
            floors.zipWithNext().all { (a, b) -> a.definition.id.value < b.definition.id.value } &&
            tables.zipWithNext().all { (a, b) -> a.definition.id.value < b.definition.id.value })
        require(configured || floors.isEmpty() && tables.isEmpty() && floor == null && nextAfter == null)
        when (query) {
            is LocalFoodFloorQueryV1.Floors -> {
                require(tables.isEmpty() && floor == null && floors.size <= query.limit &&
                    (query.after == null || floors.all { it.definition.id.value > query.after.value }))
                require(nextAfter == null || floors.size == query.limit && nextAfter == floors.last().definition.id)
            }
            is LocalFoodFloorQueryV1.Tables -> {
                require(floors.isEmpty() && tables.size <= query.limit && (floor == null || floor.definition.id == query.floorId) &&
                    (floor != null || tables.isEmpty()) && tables.all { it.definition.floorId == query.floorId } &&
                    (query.after == null || tables.all { it.definition.id.value > query.after.value }))
                require(nextAfter == null || tables.size == query.limit && nextAfter == tables.last().definition.id)
            }
            is LocalFoodFloorQueryV1.Table -> {
                require(floors.isEmpty() && tables.size <= 1 && nextAfter == null &&
                    tables.all { it.definition.id == query.tableId } && (tables.isEmpty() == (floor == null)))
                require(tables.isEmpty() || tables.single().definition.floorId == floor?.definition?.id)
            }
        }
        require(tables.none { it.definition.active } || floor?.definition?.active == true)
    }
}

public object LocalFoodFloorReadCodecV1 {
    public val QUERY: ReadKey = ReadKey("local.food.floor-read", 1)
    public fun encode(query: LocalFoodFloorQueryV1): StoredBytes = foodFloorEncode(query, LocalFoodFloorQueryV1.serializer(), 2_048)
    public fun read(query: LocalFoodFloorQueryV1): ReadRequest = ReadRequest(QUERY, encode(query))
    public fun decodeResult(bytes: StoredBytes): LocalFoodFloorPageV1 = foodFloorDecode(bytes, LocalFoodFloorPageV1.serializer(), 65_536)
}

/** Same Master cut for the definition, parent floor and current protected read authorization. */
public class LocalFoodFloorReadHandler : MasterReadHandler {
    override val key: ReadKey = LocalFoodFloorReadCodecV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = foodFloorInput {
        LocalFoodFloorReadCodecV1.encode(foodFloorDecode(input, LocalFoodFloorQueryV1.serializer(), 2_048))
    }

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean {
        if (profile != LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS) return false
        val query = foodFloorDecode(normalized, LocalFoodFloorQueryV1.serializer(), 2_048)
        return LocalFoodFloorReadCodecV1.encode(query) == normalized && when (query) {
            is LocalFoodFloorQueryV1.Floors, is LocalFoodFloorQueryV1.Tables, is LocalFoodFloorQueryV1.Table -> true
        }
    }

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope &&
            queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = foodFloorDecode(normalized, LocalFoodFloorQueryV1.serializer(), 2_048)
        val directory = LocalFoodFloorSourceReader(view).directory()
        val configured = directory.floors.isNotEmpty()
        val result = when (query) {
            is LocalFoodFloorQueryV1.Floors -> {
                val remaining = directory.floors.filter { query.after == null || it.id.value > query.after.value }
                val rows = remaining.take(query.limit).map(::foodFloorRow)
                LocalFoodFloorPageV1(query, configured, floors = rows,
                    nextAfter = if (remaining.size > query.limit) rows.last().definition.id else null)
            }
            is LocalFoodFloorQueryV1.Tables -> {
                val parent = directory.record(LocalFoodFloorProfileV1.FLOOR, query.floorId)?.let(::foodFloorRow)
                val remaining = directory.tables.filter {
                    val table = (it.request as LocalFoodFloorConfigurationRequestV1.Table).table
                    table.floorId == query.floorId && (query.after == null || it.id.value > query.after.value)
                }
                val rows = remaining.take(query.limit).map(::foodTableRow)
                LocalFoodFloorPageV1(query, configured, tables = rows, floor = parent,
                    nextAfter = if (remaining.size > query.limit) rows.last().definition.id else null)
            }
            is LocalFoodFloorQueryV1.Table -> {
                val table = directory.record(LocalFoodFloorProfileV1.TABLE, query.tableId)?.let(::foodTableRow)
                val parent = table?.let { directory.record(LocalFoodFloorProfileV1.FLOOR, it.definition.floorId)?.let(::foodFloorRow) }
                LocalFoodFloorPageV1(query, configured, tables = listOfNotNull(table), floor = parent)
            }
        }
        val requirement = AuthorizationRequirement(Permission(SecurityAction.FLOOR_READ, PermissionVerb.PERFORM), view.scope)
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().isBoundTo(requirement, context.actorId))
                return foodFloorEncode(result, LocalFoodFloorPageV1.serializer(), 65_536)
            }
        }
    }
}

private fun foodFloorRow(record: FoodFloorRecordV1): LocalFoodFloorRowV1 =
    LocalFoodFloorRowV1((record.request as LocalFoodFloorConfigurationRequestV1.Floor).floor, record.revision)
private fun foodTableRow(record: FoodFloorRecordV1): LocalFoodTableRowV1 =
    LocalFoodTableRowV1((record.request as LocalFoodFloorConfigurationRequestV1.Table).table, record.revision)
