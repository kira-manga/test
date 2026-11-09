package com.ukkera.pos.inventory.store.independent.kitchenrpcworkflowv1

import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.inventory.store.LocalStockRelationalServices
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.inventory.store.UnavailableLocalStockAuthority
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KRPC-001 / R08 exact station-profile selection support only. Production read
 * registration/codecs/classifiers run with an unobserved real repository and the
 * unavailable authority. No activated stock, assigned-station grant, protected
 * profile result, fresh-staging success, native source or RPC is established.
 */
class KitchenRpcStationProfileSupportingTest {
    @Test
    fun krpc001ProfileClassifierKeepsExactStationTargetsAndExcludesBroadReads() {
        val handlers = LocalStockRelationalServices(LocalStockRepository(), UnavailableLocalStockAuthority).reads
        val station = handlers.single { it.key == LocalStockRelationalQueriesV2.STATION_PROFILE }
        listOf(DomainId("20000000-0000-4000-8000-000000000001"),
            DomainId("20000000-0000-4000-8000-000000000002")).forEach { target ->
            val request = LocalStockRelationalQueriesV2.stationProfile(target)
            assertEquals(station.key, request.query)
            assertEquals(target, LocalStockRelationalQueriesV2.stationProfileTarget(request.payload))
            assertEquals(request.payload, station.normalize(request.payload))
            assertTrue(station.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, request.payload))
            LocalMasterRpcWorkflowProfile.entries.filter { it != LocalMasterRpcWorkflowProfile.KITCHEN }.forEach { profile ->
                assertFalse(station.supportsRpcWorkflow(profile, request.payload), profile.name)
            }
        }
        val broad = LocalStockRelationalQueriesV2.profile()
        val history = LocalStockRelationalQueriesV2.history(DomainId("90000000-0000-4000-8000-000000000001"),
            DomainId("90000000-0000-4000-8000-000000000002"))
        listOf(broad, history).forEach { request ->
            val handler = handlers.single { it.key == request.query }
            val body = handler.normalize(request.payload)
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, body))
        }
    }

    @Test
    fun krpc001StationProfileNormalizationRefusesMissingTruncatedAndBroadInput() {
        val station = LocalStockRelationalServices(LocalStockRepository(), UnavailableLocalStockAuthority).reads
            .single { it.key == LocalStockRelationalQueriesV2.STATION_PROFILE }
        val valid = LocalStockRelationalQueriesV2.stationProfile(DomainId("20000000-0000-4000-8000-000000000001")).payload
        val history = LocalStockRelationalQueriesV2.history(DomainId("90000000-0000-4000-8000-000000000001"),
            DomainId("90000000-0000-4000-8000-000000000002")).payload
        listOf(LocalStockRelationalQueriesV2.profile().payload,
            StoredBytes(valid.copyBytes().copyOf(valid.size - 1)), history).forEach { body ->
            assertFailsWith<IllegalArgumentException> { station.normalize(body) }
        }
        assertEquals(valid, station.normalize(valid))
    }
}
