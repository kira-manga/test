package com.ukkera.pos.catalog.store.independent.foodcurrentpayablev3

import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.LocalMasterFoodCheckRpcKeysV1
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV3
import com.ukkera.pos.sales.store.LocalFoodQueryV1
import com.ukkera.pos.sales.store.LocalFoodReadCodecV1
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** FCP3-009 actual registry/profile classification only; no admitted RPC or protected source fixture. */
class FoodCurrentPayableV3RegistrySlicesTest {
    @Test
    fun fcp3009_productionCompositionAndClosedFoodChecksProfileExplicitlyAcceptV3() {
        val registry = LocalBranchCommerceRegistry(UnavailableCompanyPublicationSignatureVerifier,
            UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2)
        val order = DomainId("20000000-0000-4000-8000-000000000001")
        val selector = LocalFoodQueryV1.Order(order, limit = 1)
        val keys = listOf(LocalFoodCheckKitchenCodecV3.QUERY, LocalFoodCheckKitchenCodecV2.QUERY,
            LocalFoodCheckKitchenCodecV1.QUERY, LocalFoodReadCodecV1.read(selector).query)
        for (key in keys) {
            assertTrue(key in registry.readKeys, "FCP3-009 installed production read $key")
            assertEquals(1, registry.reads.count { it.key == key }, "FCP3-009 unique production registration")
            assertTrue(LocalMasterFoodCheckRpcKeysV1.accepts(key), "FCP3-009 restricted FOOD_CHECKS key classification")
        }
        val handler = registry.reads.single { it.key == LocalFoodCheckKitchenCodecV3.QUERY }
        val normalized = handler.normalize(LocalFoodCheckKitchenCodecV3.encode(selector))
        assertEquals(LocalFoodCheckKitchenCodecV3.encode(selector), normalized)
        assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.FOOD_CHECKS, normalized))
        for (profile in LocalMasterRpcWorkflowProfile.entries.filter { it != LocalMasterRpcWorkflowProfile.FOOD_CHECKS }) {
            assertFalse(handler.supportsRpcWorkflow(profile, normalized), "FCP3-009 no unrelated profile classification")
        }
        assertFalse(LocalMasterFoodCheckRpcKeysV1.accepts(ReadKey("sales.food.check-kitchen.read", 4)))
        assertEquals(registry.reads.size, registry.readKeys.size)
        // Unavailable collaborators keep composition honest; no successful serving/authority is fabricated.
    }
}
