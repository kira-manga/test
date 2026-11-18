package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LocalMasterLanBusinessRpcKeysV1
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Self-authored, NOT independent. Exercises the production selector used by both installed
 * Client compositions. The endpoint below supplies no authentication, native custody or
 * physical success. Genuine TCP/native supporting fixtures cover the successful binding;
 * these checks do not qualify installed Android, Windows or macOS payment/recovery.
 */
class InstalledPairedClientConnectionSupportingTest {
    private val scope = BranchScope(DomainId("11000000-0000-0000-0000-000000000001"),
        DomainId("11000000-0000-0000-0000-000000000002"), DomainId("11000000-0000-0000-0000-000000000003"))
    private val identity = LocalClientDeviceIdentity(scope, DomainId("11000000-0000-0000-0000-000000000004"))
    private val connectionId = DomainId("11000000-0000-0000-0000-000000000005")
    private val labels = LocalBranchDisplayLabels(scope, "Synthetic Company", "Synthetic Branch")

    @Test
    fun authenticationOnlyDoesNotAcquirePhysicalCustodyAndRetainsActualLifetime(): Unit = runBlocking {
        val endpoint = Endpoint()
        val authentication = object : LocalAuthenticationService by endpoint {}
        var current = true
        val connection = assertIs<LocalAuthenticationOnlyConnection>(localPairedClientConnection(
            authentication, connectionId, identity, labels, profile = null,
            acquirePhysicalBinding = { error("Authentication-only must not acquire physical custody") }
        ) { current })
        assertSame(authentication, connection.authentication)
        assertSame(identity, connection.identity)
        assertEquals(connectionId, connection.connectionId)
        assertTrue(connection.isCurrent())
        current = false
        assertFalse(connection.isCurrent())
    }

    @Test
    fun everyNarrowProfileAvoidsPhysicalAcquisitionAndKeepsItsOwnVocabulary(): Unit = runBlocking {
        for (profile in LocalMasterRpcWorkflowProfile.entries.filter { it != LocalMasterRpcWorkflowProfile.LAN_BUSINESS }) {
            val endpoint = Endpoint()
            val connection = assertIs<LocalDirectoryClientConnection>(localPairedClientConnection(
                endpoint, connectionId, identity, labels, profile,
                acquirePhysicalBinding = { error("Narrow profile $profile must not acquire physical custody") }
            ) { true })
            assertSame(endpoint, connection.endpoint)
            assertSame(identity, connection.identity)
            assertEquals(profile, connection.profile)
            // Preserve the additive supplier-reference installation used by actual app setup.
            assertEquals(profile == LocalMasterRpcWorkflowProfile.SUPPLIERS,
                connection.installed.has(CommandKey("local.configuration.write", 2)), "$profile supplier references")
            assertTrue(connection.isCurrent())
            endpoint.health.value = LocalMasterEndpointHealth.RETIRED
            assertFalse(connection.isCurrent())
        }
    }

    @Test
    fun absentPhysicalBindingIsRequestedOnceWithoutRemovingOrdinaryBusiness(): Unit = runBlocking {
        val endpoint = Endpoint()
        var acquisitions = 0
        var current = true
        val connection = assertIs<LocalRemoteBusinessClientConnection>(localPairedClientConnection(
            endpoint, connectionId, identity, labels, LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
            acquirePhysicalBinding = { acquisitions++; null }
        ) { current })
        assertEquals(1, acquisitions)
        assertNull(connection.retailPhysicalBinding)
        assertSame(endpoint, connection.endpoint)
        assertSame(identity, connection.identity)
        assertSame(labels, connection.labels)
        for (command in LocalMasterLanBusinessRpcKeysV1.commandKeys) assertTrue(connection.installed.has(command))
        assertTrue(connection.isCurrent())
        current = false
        assertFalse(connection.isCurrent())
        assertEquals(1, acquisitions)
    }

    @Test
    fun failedPhysicalAcquisitionPropagatesWithoutRetryOrOwnershipTransfer(): Unit = runBlocking {
        val endpoint = Endpoint()
        var acquisitions = 0
        val failure = IllegalStateException("Synthetic retained native acquisition failure")
        val observed = assertFailsWith<IllegalStateException> {
            localPairedClientConnection(endpoint, connectionId, identity, labels,
                LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
                acquirePhysicalBinding = { acquisitions++; throw failure }) { true }
        }
        assertSame(failure, observed)
        assertEquals(1, acquisitions)
        assertEquals(LocalMasterEndpointHealth.ACTIVE, endpoint.health.value)
    }

    @Test
    fun invalidBusinessCoordinatesDoNotStartNativeAcquisition(): Unit = runBlocking {
        val endpoint = Endpoint()
        val authenticationOnly = object : LocalAuthenticationService by endpoint {}
        var acquisitions = 0
        assertFailsWith<IllegalArgumentException> {
            localPairedClientConnection(authenticationOnly, connectionId, identity, labels,
                LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
                acquirePhysicalBinding = { acquisitions++; null }) { true }
        }
        val otherScope = scope.copy(branchId = DomainId("11000000-0000-0000-0000-000000000006"))
        assertFailsWith<IllegalArgumentException> {
            localPairedClientConnection(endpoint, connectionId, identity,
                LocalBranchDisplayLabels(otherScope, "Synthetic Company", "Other Branch"),
                LocalMasterRpcWorkflowProfile.LAN_BUSINESS,
                acquirePhysicalBinding = { acquisitions++; null }) { true }
        }
        assertEquals(0, acquisitions)
    }

    private class Endpoint : LocalMasterClient {
        override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
        override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply =
            error("No business authority is supplied")
        override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply =
            error("No business authority is supplied")
        override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply =
            error("No business authority is supplied")
        override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
            explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No authentication is supplied")
        override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult =
            error("No authentication is supplied")
        override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
            error("No authentication is supplied")
        override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult =
            error("No authentication is supplied")
    }
}
