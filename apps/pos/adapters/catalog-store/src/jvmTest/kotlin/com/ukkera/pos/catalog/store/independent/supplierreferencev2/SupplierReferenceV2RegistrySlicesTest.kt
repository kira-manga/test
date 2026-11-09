package com.ukkera.pos.catalog.store.independent.supplierreferencev2

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import kotlin.test.*

/** SREF2-007 actual registration/classification only; unavailable collaborators grant nothing. */
class SupplierReferenceV2RegistrySlicesTest {
    @Test
    fun sref2007_registryInstallsBothVersionsAndV2HistoryProducerWithoutAdvertisingUnrelatedRpcProfiles() {
        val registry = LocalBranchCommerceRegistry(UnavailableCompanyPublicationSignatureVerifier,
            UnavailableSalesReturnPolicy, UnavailableSalesRecordingCapacity, UnavailableSalesPhysicalRecordingCapacityV2)
        for (key in listOf(LocalConfigurationProfile.COMMAND, LocalSupplierProfileV2.COMMAND)) {
            assertEquals(1, registry.commands.count { it.key == key })
            assertTrue(key in registry.commandKeys)
            assertTrue(key in registry.historyProducer.commands)
        }
        for (key in listOf(LocalConfigurationProfile.QUERY, LocalSupplierProfileV2.QUERY)) {
            assertEquals(1, registry.reads.count { it.key == key })
            assertTrue(key in registry.readKeys)
        }
        val writer = registry.commands.single { it.key == LocalSupplierProfileV2.COMMAND }
        val reader = registry.reads.single { it.key == LocalSupplierProfileV2.QUERY }
        val writeBytes = writer.normalize(LocalSupplierCodecV2.encode(LocalSupplierRequestV2(null, SupplierReferenceV2Inputs.supplier())))
        val readBytes = reader.normalize(LocalSupplierCodecV2.query(LocalSupplierQueryV2()))
        assertEquals(setOf(LocalConfigurationProfile.SUPPLIER), writer.writableKinds)
        assertTrue(writer.claimNamespaces.isEmpty())
        for (profile in LocalMasterRpcWorkflowProfile.entries) {
            assertEquals(profile == LocalMasterRpcWorkflowProfile.SUPPLIERS, writer.supportsRpcWorkflow(profile, writeBytes))
            assertEquals(profile == LocalMasterRpcWorkflowProfile.SUPPLIERS, reader.supportsRpcWorkflow(profile, readBytes))
        }
        assertEquals(registry.commands.size, registry.commandKeys.size)
        assertEquals(registry.reads.size, registry.readKeys.size)
    }
}
