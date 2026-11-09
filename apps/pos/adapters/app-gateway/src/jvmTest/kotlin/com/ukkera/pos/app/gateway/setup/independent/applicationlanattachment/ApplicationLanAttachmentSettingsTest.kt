package com.ukkera.pos.app.gateway.setup.independent.applicationlanattachment

import com.ukkera.pos.app.gateway.setup.LocalSetupLanSettings
import com.ukkera.pos.app.gateway.setup.validateEndpoint
import com.ukkera.pos.app.setup.LocalSetupLanEndpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Guard-only checks. An accepted numeric/string selection is not native physical eligibility. */
class ApplicationLanAttachmentSettingsTest {
    private val frozen = ApplicationLanAttachmentFrozenInputs

    @Test fun v01_policyCountsUseTheOfficialMapperWithoutAnInferredDefault() {
        for (row in frozen.policyCounts) {
            // Public input has no nullable policy. Omission uses its existing empty-list carrier;
            // this does not establish a distinct nullable API variant.
            val result = runCatching { LocalSetupLanSettings.from(frozen.hostZero.copyInput(allowedSubnets = row.cidrs.orEmpty())) }
            assertEquals(row.eligible, result.isSuccess, "ALAN-V01/${row.id}")
            row.cidrs?.let { assertEquals(it.size, frozen.hostZero.copyInput(allowedSubnets = it).allowedSubnets.size) }
        }
    }

    @Test fun v01_eachFrozenMalformedOrNonPrivateCidrRefuses() {
        for (cidr in frozen.badCidrs) {
            assertTrue(runCatching { LocalSetupLanSettings.from(frozen.hostZero.copyInput(allowedSubnets = listOf(cidr))) }.isFailure,
                "ALAN-V01/$cidr")
        }
    }

    @Test fun v02_localAddressMatrixUsesTheOfficialMapperVerbatim() {
        for (row in frozen.localAddresses) {
            val result = runCatching { LocalSetupLanSettings.from(frozen.hostZero.copyInput(localAddress = row.value)) }
            assertEquals(row.eligible, result.isSuccess, "ALAN-V02/local/${row.value}")
            if (row.eligible) assertEquals(row.value, result.getOrThrow().lan.localAddress)
        }
    }

    @Test fun v02_selectedEndpointAddressMatrixUsesTheOfficialPolicy() {
        val settings = LocalSetupLanSettings.from(frozen.clientPrimary)
        for (row in frozen.endpointAddresses) {
            val result = runCatching { settings.validateEndpoint(LocalSetupLanEndpoint(row.value, frozen.clientEndpoint.port)) }
            assertEquals(row.eligible, result.isSuccess, "ALAN-V02/endpoint/${row.value}")
        }
    }

    @Test fun v03_omissionRefusesAndSupportedExplicitStringsAreRetainedExactly() {
        val omitted = frozen.interfaces.single { it.id == null }
        assertFalse(omitted.eligible)
        // No nullable interface exists: blank is the application's existing omission carrier.
        assertTrue(runCatching { LocalSetupLanSettings.from(frozen.hostZero.copyInput(interfaceName = omitted.id.orEmpty())) }.isFailure)
        for (row in frozen.interfaces.filter { it.describedEligibility == true }) {
            val explicit = requireNotNull(row.id)
            val settings = LocalSetupLanSettings.from(frozen.hostZero.copyInput(interfaceName = explicit))
            assertEquals(explicit, settings.lan.interfaceName)
            assertTrue(settings.sameSelection(LocalSetupLanSettings.from(frozen.hostZero.copyInput(interfaceName = explicit))))
        }
        // kind/explicitly_eligible are frozen native qualification DATA, not policy arguments.
        // VPN/cellular/loopback/unqualified physical outcomes remain ALAN-Q03 NOT RUN.
    }

    @Test fun v04_listenerPortsKeepExplicitZeroAndEnforceTheirFrozenBounds() {
        for (row in frozen.listenerPorts) {
            val result = runCatching { LocalSetupLanSettings.from(frozen.hostZero.copyInput(listenPort = row.value)) }
            assertEquals(row.eligible, result.isSuccess, "ALAN-V04/listener/${row.value}")
            if (row.eligible) assertEquals(row.value, result.getOrThrow().lan.listenPort)
        }
    }

    @Test fun v04_selectedEndpointPortsCannotUseZeroOrAnOutOfRangePort() {
        val settings = LocalSetupLanSettings.from(frozen.clientPrimary)
        for (row in frozen.endpointPorts) {
            val result = runCatching { settings.validateEndpoint(frozen.clientEndpoint.copy(port = row.value)) }
            assertEquals(row.eligible, result.isSuccess, "ALAN-V04/endpoint/${row.value}")
        }
    }

    @Test fun c06_eachFrozenHostConflictDiffersFromTheExactRequestedSelection() {
        val retained = LocalSetupLanSettings.from(frozen.hostZero)
        assertTrue(retained.sameSelection(LocalSetupLanSettings.from(frozen.hostZero)))
        for (row in frozen.hostConflicts) {
            assertFalse(retained.sameSelection(LocalSetupLanSettings.from(row.input)), "ALAN-C06/Host/${row.id}")
        }
        assertFalse(retained.sameSelection(LocalSetupLanSettings.from(frozen.hostFixed)),
            "Requested 0 is not requested 42017, even when 42017 is the observed assigned port")
    }
}
