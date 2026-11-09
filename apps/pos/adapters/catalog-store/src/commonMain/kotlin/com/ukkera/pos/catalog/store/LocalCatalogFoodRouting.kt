package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.store.LocalFoodRecipeSnapshotV1
import com.ukkera.pos.sales.store.LocalFoodSourceReferenceV1
import com.ukkera.pos.sales.store.LocalFoodStationRouteV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Actual route-only observations. Neither a price preparation nor an execution grant. */
internal class CapturedFoodRouting(
    val routeVersionId: DomainId,
    val warehouseId: DomainId,
    val routes: List<LocalFoodStationRouteV1>,
    val sources: List<LocalFoodSourceReferenceV1>,
    val resources: Set<RecoveryResourceKey>,
)

/** Shared with the existing routed capture, retaining its ordered references and refusals. */
internal suspend fun captureFoodRouting(view: CommandReadView, recipeId: DomainId, recipeRevision: Revision,
    activity: LocalSalesActivity, channel: LocalSalesChannel, routeKeys: List<DomainId>,
    signatures: CompanyPublicationSignatureVerifier): CapturedFoodRouting? {
    val routeId = view.claim(foodRouteClaim(recipeId, activity, channel)) ?: return null
    val routeRecord = foodConfiguration(view, LocalFoodConfigurationProfileV1.ROUTE, routeId, signatures)
        ?: error("food.source.route_missing")
    val route = (routeRecord.second.request as? LocalFoodConfigurationRequestV1.Route)?.route
        ?: error("food.source.route")
    if (!route.active || route.recipeRevision != recipeRevision || route.recipeId != recipeId ||
        route.activity != activity || route.channel != channel) return null
    val routeVersion = foodConfiguration(view, LocalFoodConfigurationProfileV1.VERSION,
        DomainId(routeRecord.second.operationId.value), signatures) ?: error("food.source.route_version_missing")
    val warehouse = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, route.warehouseId))
        ?.let { decodeRecord(view.scope, it) } ?: return null
    if ((warehouse.body as? ConfigurationBody.Warehouse)?.value?.active != true) return null
    val sources = linkedMapOf<AggregateKey, LocalFoodSourceReferenceV1>()
    fun ref(version: Pair<StoredAggregate, FoodConfigurationRecordV1>) {
        val (row, record) = version
        sources[row.key] = LocalFoodSourceReferenceV1(row.key.kind, row.key.id, row.revision,
            row.digest, record.actorId, operationId = record.operationId)
    }
    ref(routeVersion)
    val resources = (foodConfigurationResources(routeRecord.second) +
        setOf(foodConfigResource(warehouse.id), foodConfigResource(warehouse.authorizedBy),
            RecoveryResourceKey(RecoveryResourceKind.STOCK, route.warehouseId))).toMutableSet()
    val stations = mutableListOf<LocalFoodStationRouteV1>()
    for (routeKey in routeKeys) {
        val target = route.targets.singleOrNull { it.routeKey == routeKey } ?: return null
        val station = foodConfiguration(view, LocalFoodConfigurationProfileV1.STATION, target.stationId, signatures)
            ?: return null
        val definition = (station.second.request as? LocalFoodConfigurationRequestV1.Station)?.station
            ?: error("food.source.station")
        if (!definition.active) return null
        val version = foodConfiguration(view, LocalFoodConfigurationProfileV1.VERSION,
            DomainId(station.second.operationId.value), signatures) ?: error("food.source.station_version_missing")
        ref(version)
        resources += foodConfigurationResources(station.second)
        stations += LocalFoodStationRouteV1(routeKey, definition.id, station.first.revision, definition.mode)
    }
    if (stations.map { it.stationId }.distinct().size != stations.size) return null
    return CapturedFoodRouting(routeVersion.first.key.id, route.warehouseId,
        stations.toList(), sources.values.toList(), resources.toSet())
}

/** Referenced physical/custody aliases complement the literal source-authority observations. */
internal fun foodRoutingSnapshotResources(snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey> = buildSet {
    add(RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, snapshot.snapshotId))
    for (station in snapshot.routes) {
        add(RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, station.stationId))
        add(foodConfigResource(station.stationId))
    }
    for (id in (listOf(snapshot.outputSkuId, snapshot.pricingSkuId) + snapshot.ingredients.map { it.skuId }).distinct())
        add(RecoveryResourceKey(RecoveryResourceKind.STOCK, id))
    add(RecoveryResourceKey(RecoveryResourceKind.STOCK, snapshot.warehouseId))
}
