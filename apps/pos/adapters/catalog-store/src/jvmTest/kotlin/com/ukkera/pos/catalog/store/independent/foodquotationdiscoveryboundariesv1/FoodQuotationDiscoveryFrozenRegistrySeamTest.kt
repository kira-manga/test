package com.ukkera.pos.catalog.store.independent.foodquotationdiscoveryboundariesv1

import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodQuotationProfileReadCodecV1
import com.ukkera.pos.sales.store.LocalFoodQuotationProfileReadHandler
import com.ukkera.pos.sales.store.LocalFoodStockCodecV2
import com.ukkera.pos.sales.store.TransactionSalesReturnPolicySource
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FQDB-01's actual-registry/canonical-request binding seam only. Preparation,
 * supplied authorization and profile projection remain unbound here. Registry
 * membership does not satisfy complete installation/trust/native custody, and
 * unavailable inputs below never manufacture successful source/native loans.
 */
public class FoodQuotationDiscoveryFrozenRegistrySeamTest {
    @Test
    public fun fqdb01ActualRegistrySuppliesTheCanonicalProfileReadHandler() {
        val registry = LocalBranchCommerceRegistry(
            signatures = UnavailableCompanyPublicationSignatureVerifier,
            returnPolicy = TransactionSalesReturnPolicySource { _, _ ->
                error("No return-policy loan is supplied to this registry constructor seam")
            },
            recordingCapacity = UnavailableSalesRecordingCapacity,
            physicalRecordingCapacity = UnavailableSalesPhysicalRecordingCapacityV2,
        )
        val expectedKey = ReadKey("sales.food.quotation.profile", 1)
        val handler: MasterReadHandler = assertIs<LocalFoodQuotationProfileReadHandler>(
            registry.reads.single { it.key == expectedKey })
        assertEquals(expectedKey, handler.key)
        assertTrue(LocalFoodStockCodecV2.COMMAND in registry.commandKeys)
        val canonical = StoredBytes("{}".encodeToByteArray())
        assertEquals(canonical, handler.normalize(canonical))
        val request = LocalFoodQuotationProfileReadCodecV1.read()
        assertEquals(expectedKey, request.query)
        assertEquals(canonical, request.payload)
    }
}
