package com.ukkera.pos.security.independent.capturedinvoicematchingv1

import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationPolicy
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAuthConfiguration
import com.ukkera.pos.security.LocalBranchInstallationWireV2
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalEmployeeEnrollmentDefinition
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * CCM-08 + binding v1.0.0: grant/limit data must not change through provisioning serialization.
 * These are public V2 policy and enrollment-definition codecs, not a native enrollment ceremony,
 * company-head qualification, durable activation or application authorization integration.
 * Frozen numeric oracles are reused unchanged. Source handoff execution status: NOT RUN.
 */
class CapturedInvoiceMatchingProvisioningSerializationSupportingTest {
    private val input = CapturedInvoiceMatchingFrozenInputs

    @Test
    fun ccm08NativeGrantRoundTripPreservesFiniteExplicitNoCeilingAndOmittedLimits() = runTest {
        val principal = input.principal()
        val cases = listOf(
            PermissionLimits(maximumAmount = input.money(23_999L)) to false,
            PermissionLimits(maximumAmount = input.money(24_000L)) to true,
            input.noCeiling() to true,
            PermissionLimits() to false,
        )
        for ((limits, expectedAllowed) in cases) {
            val decoded = roundTrip(listOf(input.grant(limits = limits)))
            val grants = decoded.definition.policy.grants

            assertEquals(listOf(input.permission), grants.map { it.permission })
            assertEquals(limits, grants.single().limits)
            val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, grants))
            val decision = AuthorizationPolicy(current).authorize(principal, input.requirement())
            if (expectedAllowed) assertIs<AuthorizationDecision.Allowed>(decision)
            else assertIs<AuthorizationDecision.Denied>(decision)
        }
    }

    @Test
    fun ccm08CaptureOnlyEnrollmentRoundTripDoesNotInstallCombinedGrant() = runTest {
        val principal = input.principal()
        val requested = listOf(
            input.grant(action = SecurityAction.PURCHASING_INVOICE_CAPTURE),
            input.grant(action = SecurityAction.PURCHASING_DOCUMENT_READ),
        )
        val decoded = roundTrip(requested)
        val grants = decoded.definition.policy.grants

        assertEquals(requested.map { it.permission }, grants.map { it.permission })
        val current = CapturedInvoiceMatchingCurrentCut(input.snapshot(principal, grants))
        assertIs<AuthorizationDecision.Denied>(AuthorizationPolicy(current).authorize(principal, input.requirement()))
    }

    private fun roundTrip(grants: List<PermissionGrant>): LocalEmployeeEnrollmentDefinition {
        // Inert codec plumbing. These data do not provision or activate an account/device.
        val policy = ActivatedLocalPolicy(input.scope, input.actorId, Revision(0), grants,
            LocalSessionPolicy(), ThrottlePolicy())
        val login = LocalLoginIdentifier("ccm.fin.a")
        val tokens = LocalTokenPolicy(60_000L, 120_000L)
        val definition = LocalAccountProvisioning(input.actorId, login, policy, tokens, setOf(input.deviceId))
        val configuration = LocalAuthConfiguration(input.scope, input.actorId, Revision(0), login, tokens)
        val policyBytes = LocalBranchInstallationWireV2.encodePolicy(policy)
        val configurationBytes = LocalBranchInstallationWireV2.encodeConfiguration(configuration)
        val admission = LocalDeviceAdmission(input.scope, input.deviceId, Revision(0), true,
            "ccm-public-synthetic-device", null, emptySet())
        val captured = LocalEmployeeEnrollmentDefinition.capture(
            requestId = input.id(18),
            definition = definition,
            credentialWasProvided = true,
            canonicalPolicy = policyBytes,
            canonicalConfiguration = configurationBytes,
            retainedAdmissions = listOf(admission),
        )
        val wire = captured.copyWireBytes()
        return try {
            val decoded = LocalEmployeeEnrollmentDefinition.decode(wire)
            assertContentEquals(wire, decoded.copyWireBytes())
            assertContentEquals(policyBytes, decoded.copyCanonicalPolicy())
            assertContentEquals(configurationBytes, decoded.copyCanonicalConfiguration())
            assertEquals(captured.request, decoded.request)
            decoded
        } finally {
            policyBytes.fill(0)
            configurationBytes.fill(0)
            wire.fill(0)
        }
    }
}
