package com.ukkera.pos.android.independent.nativefoodfloordirectoryv1

import android.content.res.AssetManager
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import org.json.JSONArray
import org.json.JSONObject

/** Mechanical binding of the unchanged semantic supplement; these objects never seed storage. */
internal class NativeFoodFloorFrozen private constructor(val document: JSONObject) {
    val version: String = document.getString("fixtureVersion")
    private val base = document.getJSONObject("base")
    val seedCommands = base.getJSONArray("seedCommands").objects().map(::command)
    val reads = document.getJSONArray("reads").objects()
    val floors = base.getJSONArray("floors").objects().associate {
        it.getString("alias") to floorRow(it)
    }
    val tables = base.getJSONArray("tables").objects().associate {
        it.getString("alias") to tableRow(it)
    }
    val baseState = NativeFoodFloorState(floors.values.toList(), tables.values.toList())
    val targets: Set<DomainId> = (baseState.floors.map { it.definition.id } +
        baseState.tables.map { it.definition.id }).toSet()
    val baseCounts: JSONObject = base.getJSONObject("expectedCounts")

    init {
        check(version == "native-food-floor-directory.1.0-draft")
        check(document.getJSONObject("nativeIdentityBinding").getString("mode") ==
            "OBSERVE_ORIGINAL_ALLOCATION_AND_RETAIN_EXACTLY")
        check(document.getJSONObject("nativeIdentityBinding").requiredNull("fixedOrganizationCompanyBranchEmployeeDeviceIds"))
        check(seedCommands.size == 28 && reads.size == 11)
        check(seedCommands.map { it.request.operationId }.distinct().size == seedCommands.size)
        check(targets.size == baseState.floors.size + baseState.tables.size)
    }

    fun commands(section: String): List<NativeFoodFloorCommand> =
        document.getJSONObject(section).getJSONArray("commands").objects().map(::command)

    fun floorId(alias: String): DomainId = checkNotNull(floors[alias]).definition.id
    fun tableId(alias: String): DomainId = checkNotNull(tables[alias]).definition.id

    companion object {
        fun read(assets: AssetManager): NativeFoodFloorFrozen = assets.open(
            "independent/native-food-floor-directory-v1/fixtures.json",
        ).use { NativeFoodFloorFrozen(JSONObject(it.readBytes().decodeToString(throwOnInvalidSequence = true))) }

        fun command(value: JSONObject): NativeFoodFloorCommand {
            val semantic = value.getJSONObject("request")
            val revision = semantic.nullableString("expectedRevision")?.let { Revision(it.toLong()) }
            val request = when (semantic.getString("kind")) {
                "Floor" -> LocalFoodFloorConfigurationRequestV1.Floor(revision, floor(semantic.getJSONObject("floor")))
                "Table" -> LocalFoodFloorConfigurationRequestV1.Table(revision, table(semantic.getJSONObject("table")))
                else -> error("Frozen configuration request kind is not bound")
            }
            return NativeFoodFloorCommand(
                LocalFoodFloorCodecV1.command(OperationId(value.getString("operationId")), request),
                request, result(value.getJSONObject("expectedResult")),
            )
        }

        fun query(value: JSONObject): LocalFoodFloorQueryV1 = when (value.getString("kind")) {
            "Floors" -> LocalFoodFloorQueryV1.Floors(value.nullableId("after"), value.getInt("limit"))
            "Tables" -> LocalFoodFloorQueryV1.Tables(DomainId(value.getString("floorId")),
                value.nullableId("after"), value.getInt("limit"))
            "Table" -> LocalFoodFloorQueryV1.Table(DomainId(value.getString("tableId")))
            else -> error("Frozen read request kind is not bound")
        }

        fun page(value: JSONObject): LocalFoodFloorPageV1 = LocalFoodFloorPageV1(
            query(value.getJSONObject("query")), value.getBoolean("configured"),
            value.getJSONArray("floors").objects().map(::floorRow),
            value.getJSONArray("tables").objects().map(::tableRow),
            if (value.requiredNull("floor")) null else floorRow(value.getJSONObject("floor")),
            value.nullableId("nextAfter"),
        )

        fun result(value: JSONObject): LocalConfigurationResultV1 = LocalConfigurationResultV1(
            value.getString("code"), value.nullableId("id"),
            value.nullableString("revision")?.let { Revision(it.toLong()) },
        )

        fun floorRow(value: JSONObject): LocalFoodFloorRowV1 = LocalFoodFloorRowV1(
            floor(value.getJSONObject("definition")), Revision(value.getString("storageRevision").toLong()),
        )

        fun tableRow(value: JSONObject): LocalFoodTableRowV1 = LocalFoodTableRowV1(
            table(value.getJSONObject("definition")), Revision(value.getString("storageRevision").toLong()),
        )

        private fun floor(value: JSONObject): LocalFoodFloorDefinitionV1 = LocalFoodFloorDefinitionV1(
            DomainId(value.getString("id")), names(value.getJSONObject("names")),
            value.getInt("layoutOrder"), value.getBoolean("active"),
        )

        private fun table(value: JSONObject): LocalFoodTableDefinitionV1 = LocalFoodTableDefinitionV1(
            DomainId(value.getString("id")), DomainId(value.getString("floorId")),
            names(value.getJSONObject("names")), value.getInt("layoutOrder"), value.getBoolean("active"),
        )

        private fun names(value: JSONObject): LocalNamesV1 =
            LocalNamesV1(value.getString("english"), value.getString("arabic"))
    }
}

internal data class NativeFoodFloorCommand(
    val request: MutationRequest,
    val semantic: LocalFoodFloorConfigurationRequestV1,
    val expected: LocalConfigurationResultV1,
)

/** Expected or observed public directory fields only; never a native row/history representation. */
internal data class NativeFoodFloorState(
    val floors: List<LocalFoodFloorRowV1>,
    val tables: List<LocalFoodTableRowV1>,
) {
    fun expectedAfter(command: NativeFoodFloorCommand): NativeFoodFloorState {
        if (command.expected.code != "food.floor.saved") return this
        val revision = checkNotNull(command.expected.revision)
        return when (val input = command.semantic) {
            is LocalFoodFloorConfigurationRequestV1.Floor -> copy(floors = floors.map {
                if (it.definition.id == input.floor.id) LocalFoodFloorRowV1(input.floor, revision) else it
            })
            is LocalFoodFloorConfigurationRequestV1.Table -> copy(tables = tables.map {
                if (it.definition.id == input.table.id) LocalFoodTableRowV1(input.table, revision) else it
            })
        }
    }

    fun floorsPage(): LocalFoodFloorPageV1 = LocalFoodFloorPageV1(
        LocalFoodFloorQueryV1.Floors(null, 20), floors.isNotEmpty(), floors = floors,
    )

    fun tablesPage(floorId: DomainId): LocalFoodFloorPageV1 = LocalFoodFloorPageV1(
        LocalFoodFloorQueryV1.Tables(floorId, null, 20), floors.isNotEmpty(),
        tables = tables.filter { it.definition.floorId == floorId },
        floor = floors.singleOrNull { it.definition.id == floorId },
    )

    fun tablePage(tableId: DomainId): LocalFoodFloorPageV1 {
        val row = tables.singleOrNull { it.definition.id == tableId }
        return LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Table(tableId), floors.isNotEmpty(),
            tables = listOfNotNull(row), floor = row?.let { table ->
                floors.single { it.definition.id == table.definition.floorId }
            })
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun JSONObject.requiredNull(key: String): Boolean {
    check(has(key)) { "Required frozen field is missing: $key" }
    return isNull(key)
}
private fun JSONObject.nullableString(key: String): String? = if (requiredNull(key)) null else getString(key)
private fun JSONObject.nullableId(key: String): DomainId? = nullableString(key)?.let(::DomainId)
