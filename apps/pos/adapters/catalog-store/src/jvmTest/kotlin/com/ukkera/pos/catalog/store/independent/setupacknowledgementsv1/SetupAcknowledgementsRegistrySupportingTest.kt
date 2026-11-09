package com.ukkera.pos.catalog.store.independent.setupacknowledgementsv1

import com.ukkera.pos.catalog.store.LocalBranchCommerceRegistry
import com.ukkera.pos.catalog.store.UnavailableCompanyPublicationSignatureVerifier
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.setup.LocalSetupResultCodecV1
import com.ukkera.pos.database.setup.LocalSetupResultProfileV1
import com.ukkera.pos.sales.store.UnavailableSalesPhysicalRecordingCapacityV2
import com.ukkera.pos.sales.store.UnavailableSalesRecordingCapacity
import com.ukkera.pos.sales.store.UnavailableSalesReturnPolicy
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Supplemental Linux/JVM wiring coverage only. It proves the actual production registry installs
 * the three additive setup identities and their restart producer, and that their strict request
 * normalization refuses unknown caller fields. It does not execute a master command/read, grant
 * authority, commit a fact or satisfy any SAS case.
 */
class SetupAcknowledgementsRegistrySupportingTest {
    private val installationId = DomainId.random()

    @Test
    fun registryInstallsAckGoLiveReadAndRestartProducerWithoutRpcOrImplicitKinds() {
        val registry = LocalBranchCommerceRegistry(
            signatures = UnavailableCompanyPublicationSignatureVerifier,
            returnPolicy = UnavailableSalesReturnPolicy,
            recordingCapacity = UnavailableSalesRecordingCapacity,
            physicalRecordingCapacity = UnavailableSalesPhysicalRecordingCapacityV2,
        )
        val commands = mapOf(
            LocalSetupResultProfileV1.ACKNOWLEDGE to LocalSetupResultProfileV1.ACKNOWLEDGEMENT_KIND,
            LocalSetupResultProfileV1.GO_LIVE to LocalSetupResultProfileV1.GO_LIVE_KIND,
        )
        for ((key, kind) in commands) {
            assertEquals(1, registry.commands.count { it.key == key })
            assertTrue(key in registry.commandKeys)
            assertTrue(key in registry.historyProducer.commands)
            val handler = registry.commands.single { it.key == key }
            assertEquals(setOf(kind), handler.writableKinds)
            assertTrue(handler.claimNamespaces.isEmpty())
            val canonical = LocalSetupResultCodecV1.encodeRequest(installationId)
            assertEquals(canonical, handler.normalize(canonical))
            for (profile in LocalMasterRpcWorkflowProfile.entries) {
                assertFalse(handler.supportsRpcWorkflow(profile, canonical),
                    "Setup command must not advertise the $profile RPC workflow")
            }
        }
        assertEquals(1, registry.reads.count { it.key == LocalSetupResultProfileV1.READ })
        assertTrue(LocalSetupResultProfileV1.READ in registry.readKeys)
        val reader = registry.reads.single { it.key == LocalSetupResultProfileV1.READ }
        val read = LocalSetupResultCodecV1.read(installationId)
        assertEquals(read.payload, reader.normalize(read.payload))
        for (profile in LocalMasterRpcWorkflowProfile.entries) {
            assertFalse(reader.supportsRpcWorkflow(profile, read.payload),
                "Setup read must not advertise the $profile RPC workflow")
        }
    }

    @Test
    fun registryHandlerNormalizationRefusesCallerSuppliedReadinessAuthorityFields() {
        val registry = LocalBranchCommerceRegistry(
            signatures = UnavailableCompanyPublicationSignatureVerifier,
            returnPolicy = UnavailableSalesReturnPolicy,
            recordingCapacity = UnavailableSalesRecordingCapacity,
            physicalRecordingCapacity = UnavailableSalesPhysicalRecordingCapacityV2,
        )
        for (key in listOf(LocalSetupResultProfileV1.ACKNOWLEDGE, LocalSetupResultProfileV1.GO_LIVE)) {
            val handler = registry.commands.single { it.key == key }
            val forged = StoredBytes(
                ("{\"installationId\":\"${installationId.value}\",\"ready\":true," +
                    "\"actorId\":\"${DomainId.random().value}\",\"permission\":\"SETUP_BUSINESS_GO_LIVE\"," +
                    "\"replacementInstallationId\":\"${DomainId.random().value}\"}").encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { handler.normalize(forged) }
        }
    }
}
