package com.ukkera.pos.database.independent.employeepolicyfocusedv1

import com.ukkera.pos.database.LocalEffectivePolicyCodec
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalEmployeePolicyActivationDefinition
import com.ukkera.pos.security.LocalProvisioningAction
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bounded frozen EPF value/codec slices. No activation, native authority, durable history,
 * session admission, authenticated Master decision or complete-case result is exercised.
 */
class EmployeePolicyFocusedFrozenCodecTest {
    private val fixture = EmployeePolicyFocusedFrozenFixtures

    @Test
    fun epf01_allFiveMappedPolicyValueSnapshotsRetainFrozenValuesAcrossProtectedCodec() {
        for (input in fixture.allPolicyInputs) {
            val original = fixture.policy(input)
            val bytes = LocalEffectivePolicyCodec.encodePolicy(original)
            val decoded = LocalEffectivePolicyCodec.decodePolicy(binding(original), bytes)
            assertFrozenMappedPolicy(input, decoded)
            assertContentEquals(bytes, LocalEffectivePolicyCodec.encodePolicy(decoded))
        }
    }

    @Test
    fun epf01_originalDefinitionBytesRetainDeclaredPredecessorAndExactTarget() {
        for (input in fixture.definitionInputs) {
            val next = fixture.policy(input.next)
            val canonical = LocalEffectivePolicyCodec.encodePolicy(next)
            val captured = LocalEmployeePolicyActivationDefinition.capture(input.requestId, next, canonical)
            val originalWire = captured.copyWireBytes()
            val restored = LocalEmployeePolicyActivationDefinition.decode(originalWire)
            assertEquals(input.requestId, restored.request.requestId)
            assertEquals(LocalProvisioningAction.ACTIVATE_POLICY, restored.request.action)
            assertEquals(fixture.scopeA, restored.request.scope)
            assertEquals(input.next.actorId, restored.request.targetId)
            assertEquals(Revision(input.predecessor), restored.previousPolicyRevision)
            assertFrozenMappedPolicy(input.next, restored.nextPolicy)
            assertContentEquals(canonical, restored.copyCanonicalPolicy())
            assertContentEquals(originalWire, restored.copyWireBytes())
        }
        // These are only the three declared fixture dependencies, not a universal N+1 rule.
    }

    @Test
    fun epf02_negativeAndSigned64OverflowRevisionInputsCannotProducePolicyRevisionValues() {
        assertFails { Revision(-1) }
        assertFails { Json.decodeFromString(Revision.serializer(), "\"-1\"") }
        assertFails { Json.decodeFromString(Revision.serializer(), "\"9223372036854775808\"") }
    }

    @Test
    fun epf02_subjectPolicyBytesCannotDecodeAsTheForeignBranchAccountBinding() {
        val bytes = LocalEffectivePolicyCodec.encodePolicy(fixture.policy(fixture.subject12))
        val foreign = ProtectedLocalBinding(
            ProtectedLocalRecordKey.EffectivePolicy(fixture.scopeB, fixture.branchBAccount), Revision(12),
        )
        assertFails { LocalEffectivePolicyCodec.decodePolicy(foreign, bytes.copyOf()) }
        // Codec identity rejection is not an authenticated direct-request scope refusal.
    }

    @Test
    fun epf07_capturedOriginalDefinitionRetainsBytesWhenCallerBuffersAreChanged() {
        val input = fixture.definitionInputs.first()
        val next = fixture.policy(input.next)
        val callerCanonical = LocalEffectivePolicyCodec.encodePolicy(next)
        val originalCanonical = callerCanonical.copyOf()
        val captured = LocalEmployeePolicyActivationDefinition.capture(input.requestId, next, callerCanonical)
        val originalWire = captured.copyWireBytes()
        callerCanonical.fill(0)
        captured.copyCanonicalPolicy().fill(0)
        captured.copyWireBytes().fill(0)
        assertContentEquals(originalCanonical, captured.copyCanonicalPolicy())
        assertContentEquals(originalWire, captured.copyWireBytes())
        assertEquals(input.requestId, captured.request.requestId)
        assertEquals(fixture.subject, captured.request.targetId)
        assertFrozenMappedPolicy(fixture.subject12, captured.nextPolicy)
        // Input custody only: no R1 result, restart, operation conflict or replay was modeled.
    }

    @Test
    fun epf06_namedDelegationTargetAndExactFiniteCeilingsRetainAcrossProtectedPolicyCodec() {
        val original = fixture.namedDelegationPolicy()
        val bytes = LocalEffectivePolicyCodec.encodePolicy(original)
        val decoded = LocalEffectivePolicyCodec.decodePolicy(binding(original), bytes)
        assertEquals(fixture.scopeA, decoded.scope)
        assertEquals(fixture.namedAdministrator, decoded.actorId)
        val grant = decoded.grants.single()
        assertEquals(Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER), grant.permission)
        assertEquals(GrantEffect.ALLOW, grant.effect)
        assertEquals(fixture.scopeA, grant.scope)
        assertEquals(fixture.namedAdministrator, grant.namedSubjectId)
        assertEquals(setOf(fixture.subject), grant.targetIds)
        assertEquals(OwnerAccess.OWN_ONLY, grant.ownerAccess)
        assertEquals(fixture.egp(2_000), grant.limits.maximumAmount)
        assertEquals(2_000, grant.limits.maximumPercentageBasisPoints)
        val ceiling = grant.delegationCeiling ?: error("Frozen finite delegation ceiling was lost")
        assertEquals(fixture.egp(3_000), ceiling.maximumAmount)
        assertEquals(3_000, ceiling.maximumPercentageBasisPoints)
        assertNull(grant.limits.noMonetaryCeiling)
        assertNull(ceiling.noMonetaryCeiling)
        assertFalse(fixture.namedAdministrator in grant.targetIds.orEmpty())
        assertContentEquals(bytes, LocalEffectivePolicyCodec.encodePolicy(decoded))
        // No numeric limit-change proposal, head approval, local write or emergency-disable authority.
    }

    private fun binding(policy: ActivatedLocalPolicy) = ProtectedLocalBinding(
        ProtectedLocalRecordKey.EffectivePolicy(policy.scope, policy.actorId), policy.revision,
    )

    private fun assertFrozenMappedPolicy(input: EmployeePolicyFocusedFrozenFixtures.PolicyInput,
        actual: ActivatedLocalPolicy) {
        assertEquals(fixture.scopeA, actual.scope)
        assertEquals(input.actorId, actual.actorId)
        assertEquals(Revision(input.revision), actual.revision)
        assertEquals(input.sessionCap, actual.sessionPolicy.maximumConcurrentSessions)
        val expected = linkedMapOf(
            Permission(SecurityAction.ORDER_CREATE, PermissionVerb.PERFORM) to true,
            Permission(SecurityAction.DISCOUNT, PermissionVerb.PERFORM) to input.discountPerform,
            Permission(SecurityAction.CUSTOMER_RECEIPT_RECORD, PermissionVerb.PERFORM) to input.receiptRecordOwn,
            Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM) to input.returnLookup,
            Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM) to true,
            Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM) to input.costRead,
            Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT) to false,
            Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER) to false,
            Permission(SecurityAction.RECOVERY_ACTIVATION, PermissionVerb.PERFORM) to false,
        )
        // Preserve this adapter's exact input list and optional fields, not a normative grant
        // order or a claim that all dimensions of a production complete manifest are bound.
        assertEquals(expected.keys.toList(), actual.grants.map { it.permission })
        for ((permission, allow) in expected) {
            val grant = actual.grants.single { it.permission == permission }
            assertEquals(if (allow) GrantEffect.ALLOW else GrantEffect.DENY, grant.effect)
            assertEquals(fixture.scopeA, grant.scope)
            assertEquals(input.actorId, grant.namedSubjectId)
            assertEquals(OwnerAccess.OWN_ONLY, grant.ownerAccess)
            assertNull(grant.targetIds)
            assertNull(grant.delegationCeiling)
            assertNull(grant.limits.noMonetaryCeiling)
        }
        val discount = actual.grants.single { it.permission.action == SecurityAction.DISCOUNT }
        assertEquals(fixture.egp(input.discountAmountMinor), discount.limits.maximumAmount)
        assertEquals(input.discountBasisPoints, discount.limits.maximumPercentageBasisPoints)
        assertTrue(actual.grants.none { it.ownerAccess == OwnerAccess.ANY_OWNER })
    }
}
