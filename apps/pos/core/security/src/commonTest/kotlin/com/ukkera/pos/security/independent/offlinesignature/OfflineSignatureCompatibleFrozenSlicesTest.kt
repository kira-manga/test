package com.ukkera.pos.security.independent.offlinesignature

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unchanged AOSV v1 slices compatible with the existing JVM and Android paths.
 * Stronger Android invalid-key and sequence cases live in androidHostTest.
 * No whole original scenario, installed runtime or native-authority claim follows.
 */
class OfflineSignatureCompatibleFrozenSlicesTest {
    @Test
    fun aosv001Through004PublishedNonemptyOrdinaryRfcVectors() {
        assertPrimitiveRange(1, 4)
    }

    @Test
    fun aosv005MatchingPublishedEmptyMessageStillRefused() {
        assertPrimitiveRange(5, 5)
    }

    @Test
    fun aosv006Through014WrongKeyChangedBytesAndOrdinaryAbc() {
        assertPrimitiveRange(6, 14)
    }

    @Test
    fun aosv015Through019ExactRawMessageMaximumBoundaries() {
        assertPrimitiveRange(15, 19)
    }

    @Test
    fun aosv020Through026ExactPublicKeyAndSignatureLengths() {
        assertPrimitiveRange(20, 26)
    }

    @Test
    fun aosv100Through105ExactRoleFramesAndActualParsedDocumentCaller() {
        val roles = OfflineSignatureFrozenFixtures.roles
        assertEquals(6, roles.size, "AOSV-100–105 six frozen document roles")
        roles.forEach { role ->
            val input = OfflineSignatureFrozenFixtures.roleInvocation(role)
            assertFrozenDirectSignature(input)
            assertFrozenParsedDocument(input, role.name, OfflineSignatureFrozenFixtures.bodyB1())
        }
    }

    @Test
    fun aosv110AllThirtyDistinctRoleSubstitutionsRefusedByBothCallers() {
        var substitutions = 0
        OfflineSignatureFrozenFixtures.roles.forEach { signed ->
            OfflineSignatureFrozenFixtures.roles.filter { it.name != signed.name }.forEach { presented ->
                val input = OfflineSignatureFrozenFixtures.roleSubstitution(signed, presented)
                assertFrozenDirectSignature(input)
                assertFrozenParsedDocument(input, presented.name, OfflineSignatureFrozenFixtures.bodyB1())
                substitutions++
            }
        }
        assertEquals(30, substitutions, "AOSV-110 six times five frozen substitutions")
    }

    @Test
    fun aosv111AllSixChangedBodiesRefusedByBothCallers() {
        OfflineSignatureFrozenFixtures.roles.forEach { role ->
            val input = OfflineSignatureFrozenFixtures.changedBody(role)
            assertFrozenDirectSignature(input)
            assertFrozenParsedDocument(input, role.name, OfflineSignatureFrozenFixtures.bodyB2())
        }
    }

    @Test
    fun aosv112ChangedBodyAcceptedWithItsIndependentlyMatchingSignature() {
        val input = OfflineSignatureFrozenFixtures.matchingB2()
        assertFrozenDirectSignature(input)
        assertFrozenParsedDocument(input, "TRUST_STATE", OfflineSignatureFrozenFixtures.bodyB2())
    }

    @Test
    fun aosv113AllSixChangedDomainsRefused() {
        OfflineSignatureFrozenFixtures.roles.forEach { assertFrozenDirectSignature(OfflineSignatureFrozenFixtures.changedDomain(it)) }
    }

    @Test
    fun aosv114AllSixBareBodiesCannotReuseFramedSignatures() {
        OfflineSignatureFrozenFixtures.roles.forEach { assertFrozenDirectSignature(OfflineSignatureFrozenFixtures.bareBody(it)) }
    }

    @Test
    fun aosv115Through117ExactFieldCountOrderAndRoleSpelling() {
        OfflineSignatureFrozenFixtures.changedFramingCases().forEach(::assertFrozenDirectSignature)
    }

    @Test
    fun aosv120Through122WholeMessageFrameLengthIncludesAllOverhead() {
        // These are raw signature-message bounds, not admitted 32-KiB wire documents.
        OfflineSignatureFrozenFixtures.roleBoundaryCases().forEach(::assertFrozenDirectSignature)
    }

    @Test
    fun aosvApplicableCompatibleFrozenOutcomesReachActualNativeSignatureHelper() = runTest {
        // The helper accepts a syntax-valid 32-byte hex key. AOSV-020–023 test the
        // raw primitive's byte-key boundary and have no unchanged helper binding.
        val unavailableKeySyntaxCases = setOf("AOSV-020", "AOSV-021", "AOSV-022", "AOSV-023")
        val inputs = OfflineSignatureFrozenFixtures.compatibleCases().filterNot { it.id in unavailableKeySyntaxCases }
        assertEquals(83, inputs.size, "AOSV frozen compatible native-helper vectors")
        inputs.forEach { assertFrozenNativeSignature(it) }
    }

    private fun assertPrimitiveRange(first: Int, last: Int) {
        val inputs = OfflineSignatureFrozenFixtures.compatiblePrimitiveCases()
            .filter { it.id.removePrefix("AOSV-").toInt() in first..last }
        assertEquals(last - first + 1, inputs.size, "AOSV frozen primitive range $first–$last")
        inputs.forEach(::assertFrozenDirectSignature)
    }
}
