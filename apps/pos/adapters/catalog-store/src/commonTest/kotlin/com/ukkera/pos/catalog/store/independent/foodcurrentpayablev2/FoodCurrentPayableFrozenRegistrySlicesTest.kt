package com.ukkera.pos.catalog.store.independent.foodcurrentpayablev2

import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** FCPV2-004: actual composition registry exposure, without preparing or authorizing a read. */
class FoodCurrentPayableFrozenRegistrySlicesTest {
    @Test
    fun fcpv2004_commerceRegistryExplicitlyAdvertisesUniqueVersionTwoRead() {
        val registry = LocalBranchCommerceRegistry(UnavailableCompanyPublicationSignatureVerifier,
            UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2)
        val expected = ReadKey("sales.food.check-kitchen.read", 2)
        assertTrue(expected in registry.readKeys, "FCPV2-004: production composition explicitly advertises V2")
        assertEquals(1, registry.reads.count { it.key == expected }, "FCPV2-004: no duplicate V2 registration")
        assertEquals(registry.reads.size, registry.readKeys.size)
        // Registration cannot qualify runtime installation, source custody, employee authority or serving.
    }
}
