package com.ukkera.pos.security.independent.offlinesignature

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.NoSuchAlgorithmException
import java.security.Provider
import java.security.Security
import java.security.Signature
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Android-only stronger malformed-key refusals, exact frozen success/refusal
 * sequence and genuine test-JVM registration absence. No installed API24/API31+
 * result, native owner/custody or qualified secure-key evidence is supplied.
 */
class AndroidOfflineSignatureStrictFrozenSlicesTest {
    @Test
    fun aosv027Through032InvalidAndLowOrderPublicKeysRefused() {
        val inputs = OfflineSignatureFrozenFixtures.strictAndroidPrimitiveCases().take(6)
        assertEquals(6, inputs.size, "AOSV-027–032 six frozen invalid-key fixtures")
        inputs.forEach(::assertFrozenDirectSignature)
    }

    @Test
    fun aosv033Through038NoncanonicalAndMalformedSignaturesRefused() {
        val inputs = OfflineSignatureFrozenFixtures.strictAndroidPrimitiveCases().drop(6)
        assertEquals(6, inputs.size, "AOSV-033–038 six frozen invalid-signature fixtures")
        inputs.forEach(::assertFrozenDirectSignature)
    }

    @Test
    fun aosv039ExactSequentialSuccessAndRefusalOutcomesCannotCarryOver() {
        val inputs = OfflineSignatureFrozenFixtures.sequentialAndroidCases()
        assertEquals(9, inputs.size, "AOSV-039 exact nine-call frozen sequence")
        // The production entry point is fixed; no verifier replacement or cached result.
        inputs.forEach(::assertFrozenDirectSignature)
    }

    @Test
    fun aosv027Through038ActualNativeSignatureHelperRetainsStrictRefusal() = runTest {
        OfflineSignatureFrozenFixtures.strictAndroidPrimitiveCases().forEach { assertFrozenNativeSignature(it) }
    }

    @Test
    fun aosv130FrozenPositivesPassWithGenuinelyAbsentEd25519JcaRegistration() {
        // Registry changes are confined to this host-test JVM and always restored.
        // Run this case in a focused host-test invocation. Restoration reinstalls
        // only the same original provider instances; host files/settings are untouched.
        synchronized(Security::class.java) {
            val original = Security.getProviders().toList()
            val removed = original.withIndex().filter { exposesEd25519Registration(it.value) }
            try {
                removed.forEach { Security.removeProvider(it.value.name) }
                assertNoEd25519Registration()
                val inputs = OfflineSignatureFrozenFixtures.noOsRegistrationCases()
                assertEquals(11, inputs.size, "AOSV-130 exact eleven frozen positive invocations")
                inputs.forEach { input ->
                    assertFrozenDirectSignature(input)
                    assertNoEd25519Registration()
                }
            } finally {
                removed.forEach { indexed ->
                    Security.removeProvider(indexed.value.name)
                    Security.insertProviderAt(indexed.value, indexed.index + 1)
                }
                val restored = Security.getProviders().toList()
                assertEquals(original.map { it.name }, restored.map { it.name }, "AOSV-130 exact original provider order restored")
                original.indices.forEach { index ->
                    assertSame(original[index], restored[index], "AOSV-130 original provider identity at $index")
                }
            }
        }
    }

    private fun exposesEd25519Registration(provider: Provider): Boolean =
        listOf("Signature", "KeyFactory", "KeyPairGenerator").any { type ->
            listOf("Ed25519", "EdDSA", "1.3.101.112", "OID.1.3.101.112").any { algorithm ->
                provider.getService(type, algorithm) != null
            }
        }

    private fun assertNoEd25519Registration() {
        assertTrue(Security.getProviders().none(::exposesEd25519Registration), "AOSV-130 actual registry lacks Ed25519/EdDSA services")
        listOf("Ed25519", "EdDSA").forEach { algorithm ->
            assertFailsWith<NoSuchAlgorithmException>("AOSV-130 no $algorithm Signature registration") { Signature.getInstance(algorithm) }
            assertFailsWith<NoSuchAlgorithmException>("AOSV-130 no $algorithm KeyFactory registration") { KeyFactory.getInstance(algorithm) }
            assertFailsWith<NoSuchAlgorithmException>("AOSV-130 no $algorithm KeyPairGenerator registration") { KeyPairGenerator.getInstance(algorithm) }
        }
    }
}
