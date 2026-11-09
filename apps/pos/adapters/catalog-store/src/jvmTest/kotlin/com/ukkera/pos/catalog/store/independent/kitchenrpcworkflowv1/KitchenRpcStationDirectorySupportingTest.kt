package com.ukkera.pos.catalog.store.independent.kitchenrpcworkflowv1

import com.ukkera.pos.catalog.store.LocalFoodStationDirectoryCodecV1
import com.ukkera.pos.catalog.store.LocalFoodStationDirectoryQueryV1
import com.ukkera.pos.catalog.store.LocalFoodStationDirectoryReadHandler
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodQueryV1
import com.ukkera.pos.sales.store.LocalFoodReadCodecV1
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * KRPC-001 existing station-directory selector support only. Real production
 * codecs and the typed classifier run with the production unavailable verifier.
 * No directory preparation, signed configuration, current discovery/task grant,
 * private projection, native ownership, RPC or KRPC-002 authority is established.
 */
class KitchenRpcStationDirectorySupportingTest {
    @Test
    fun krpc001DirectoryClassifierKeepsConfiguredAndExactStationSelectors() {
        val handler = LocalFoodStationDirectoryReadHandler(UnavailableCompanyPublicationSignatureVerifier)
        val queries = listOf(LocalFoodStationDirectoryQueryV1.Configured(),
            LocalFoodStationDirectoryQueryV1.Configured(after = DomainId("20000000-0000-4000-8000-000000000001")),
            LocalFoodStationDirectoryQueryV1.Station(DomainId("20000000-0000-4000-8000-000000000001")))
        queries.forEach { query ->
            val request = LocalFoodStationDirectoryCodecV1.read(query)
            assertEquals(handler.key, request.query)
            assertEquals(query, LocalFoodStationDirectoryCodecV1.decode(request.payload))
            assertEquals(request.payload, handler.normalize(request.payload))
            assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, request.payload))
            LocalMasterRpcWorkflowProfile.entries.filter { it != LocalMasterRpcWorkflowProfile.KITCHEN }.forEach { profile ->
                assertFalse(handler.supportsRpcWorkflow(profile, request.payload), profile.name)
            }
        }
    }

    @Test
    fun krpc001DirectoryNormalizationRefusesTruncatedAndForeignSelectors() {
        val handler = LocalFoodStationDirectoryReadHandler(UnavailableCompanyPublicationSignatureVerifier)
        val valid = LocalFoodStationDirectoryCodecV1.encode(LocalFoodStationDirectoryQueryV1.Station(
            DomainId("20000000-0000-4000-8000-000000000001")))
        val foreign = LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(DomainId("30000000-0000-4000-8000-000000000001")))
        listOf(StoredBytes(valid.copyBytes().copyOf(valid.size - 1)), foreign, StoredBytes(byteArrayOf())).forEach { body ->
            assertFailsWith<IllegalArgumentException> { handler.normalize(body) }
        }
        assertEquals(valid, handler.normalize(valid))
    }
}
