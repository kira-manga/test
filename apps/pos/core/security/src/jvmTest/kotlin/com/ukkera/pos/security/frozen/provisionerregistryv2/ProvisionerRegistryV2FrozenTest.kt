package com.ukkera.pos.security.frozen.provisionerregistryv2

import com.ukkera.pos.security.JvmP256Primitives
import com.ukkera.pos.security.LocalProvisionerAllocationPhaseV1
import com.ukkera.pos.security.LocalProvisionerAllocationV2
import com.ukkera.pos.security.LocalProvisionerInstallerV1
import com.ukkera.pos.security.LocalProvisionerRegistryStateV2
import com.ukkera.pos.security.SelfOwnedBootstrapV2
import com.ukkera.pos.security.SelfOwnedDelegationBodyV1
import com.ukkera.pos.security.SelfOwnedDocumentRoleV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedInstallationWireV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityPreparationV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentitySignedBodyV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentitySignedDocumentV2
import com.ukkera.pos.security.SelfOwnedFailureV1
import com.ukkera.pos.security.SelfOwnedNativeKeyRoleV1
import com.ukkera.pos.security.SelfOwnedNativeProvisionerWireV2
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.SelfOwnedOriginalFormat
import com.ukkera.pos.security.SelfOwnedProvisionerNativeKeysV2
import com.ukkera.pos.security.SelfOwnedProvisionerRegistryV2
import com.ukkera.pos.security.SelfOwnedRegistryEnvelopeV2
import com.ukkera.pos.security.SelfOwnedRootBodyV1
import com.ukkera.pos.security.SelfOwnedSignedDocumentV1
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture as A
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as N
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Frozen rules for the core-side authenticated read/seal of the Windows/macOS provisioner registry
 * (`LocalProvisionerRegistryStateV2` in the V2 envelope), authored on 13 November 2026 BEFORE
 * `SelfOwnedProvisionerRegistryV2` existed. NOT independent (same author, owner instruction).
 * Software P-256/AES-GCM keys stand in for the platform custody; synthetic data only.
 *
 * Rules: the issuer key signs `registrySignature(registryAad(bootstrap), nonce, ciphertext)` and
 * the signature is verified BEFORE decryption; the plaintext must be canonical; the root document
 * must be the keys' root body signed by the root key; the delegation is root-signed and names the
 * bootstrap issuer key/point and installer; an allocation must name this root/delegation/installer
 * and the bootstrap target, and a retained grant is the issuer signature over the exact V2 signed
 * body. Any mismatch is STATE_UNCERTAIN; V1 key custody is UNSUPPORTED_PLATFORM (no fallback).
 */
class ProvisionerRegistryV2FrozenTest {
    private class SoftwareKeys(
        val rootBodyValue: () -> SelfOwnedRootBodyV1,
    ) : SelfOwnedProvisionerNativeKeysV2 {
        private fun ec() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val root = ec(); val issuer = ec()
        val registry: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var signWith = issuer.private
        override fun assertOriginalNamespace() = Unit
        override fun assertCompletedOriginalKeys() = Unit
        override fun prepareOriginalKeys() = Unit
        override fun assertAllOriginalKeys() = Unit
        override fun originalPoint(role: SelfOwnedNativeKeyRoleV1): ByteArray = when (role) {
            SelfOwnedNativeKeyRoleV1.ROOT -> JvmP256Primitives.point(root.public as ECPublicKey)
            SelfOwnedNativeKeyRoleV1.ISSUER -> JvmP256Primitives.point(issuer.public as ECPublicKey)
            else -> error("no point")
        }
        override fun rootBody(): SelfOwnedRootBodyV1 = rootBodyValue()
        override fun rootAnnouncement(): SelfOwnedSignedDocumentV1 = JvmP256Primitives.signDocument(
            SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT, SelfOwnedInstallationWireV1.encodeRoot(rootBody()), root.private)
        override fun issuerDelegation(root: SelfOwnedSignedDocumentV1): SelfOwnedSignedDocumentV1 = error("unused")
        override fun signOriginalPreparationV2(body: SelfOwnedInstalledIdentityPreparationV2): SelfOwnedInstalledIdentitySignedDocumentV2 {
            val signed = SelfOwnedInstalledIdentitySignedBodyV2(body)
            return SelfOwnedInstalledIdentitySignedDocumentV2(signed, JvmP256Primitives.sign(issuer.private, signed.copyWireBytes()))
        }
        override fun installerTag(message: ByteArray): ByteArray = error("unused")
        override fun sealRegistryV2(aad: ByteArray, plaintext: ByteArray): SelfOwnedRegistryEnvelopeV2 {
            val nonce = JvmP256Primitives.randomBytes(12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, registry, GCMParameterSpec(128, nonce)); updateAAD(aad) }
            val ciphertext = cipher.doFinal(plaintext)
            val signature = JvmP256Primitives.sign(signWith, SelfOwnedNativeProvisionerWireV2.registrySignature(aad, nonce, ciphertext))
            return SelfOwnedRegistryEnvelopeV2(nonce, ciphertext, signature)
        }
        override fun openRegistryV2(aad: ByteArray, envelope: SelfOwnedRegistryEnvelopeV2): ByteArray =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, registry, GCMParameterSpec(128, envelope.nonce)); updateAAD(aad) }
                .doFinal(envelope.ciphertext)
    }

    private val bootstrap: SelfOwnedBootstrapV2 = N.macCompleteBootstrap().copy(enrollmentStarted = true)
    private lateinit var keys: SoftwareKeys
    private fun rootBody(): SelfOwnedRootBodyV1 =
        SelfOwnedRootBodyV1(A.root, A.rootSigningKey, A.organization, A.company, A.installer, A.facts.companyName,
            A.facts.responsiblePersonName, A.facts.installerLogin,
            SelfOwnedOctetsV1(keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT)), A.facts.protection, A.bootstrapAction)

    private fun fixture(allocation: Boolean = true, mutate: (LocalProvisionerRegistryStateV2) -> LocalProvisionerRegistryStateV2 = { it }):
        LocalProvisionerRegistryStateV2 {
        keys = SoftwareKeys { rootBody() }
        val root = keys.rootAnnouncement()
        val delegation = JvmP256Primitives.signDocument(SelfOwnedDocumentRoleV1.ISSUER_DELEGATION,
            SelfOwnedInstallationWireV1.encodeDelegation(SelfOwnedDelegationBodyV1(A.root, JvmP256Primitives.documentDigest(root),
                A.organization, A.company, A.issuer, A.issuerSigningKey,
                SelfOwnedOctetsV1(keys.originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER)), A.installer, 0L, 0L, A.facts.protection)),
            keys.root.private)
        val preparation = SelfOwnedInstalledIdentityPreparationV2(A.root, JvmP256Primitives.documentDigest(root), A.issuer,
            JvmP256Primitives.documentDigest(delegation), A.installer, 0L, 0L, A.coordinates, 1L, A.bytes(32, 0x51),
            N.macIdentity, "Main Branch")
        val installer = LocalProvisionerInstallerV1(A.installer, A.facts.responsiblePersonName, A.facts.installerLogin, 0L, 0L, true,
            SelfOwnedOctetsV1(A.bytes(32, 0xe1)), SelfOwnedOctetsV1(A.bytes(32, 0xe2)), 0, null, 0, 0L, 0L)
        val allocations = if (allocation) listOf(LocalProvisionerAllocationV2(preparation,
            LocalProvisionerAllocationPhaseV1.GRANT_RETAINED, keys.signOriginalPreparationV2(preparation))) else emptyList()
        return mutate(LocalProvisionerRegistryStateV2(1L, root, delegation, true, allocations.size + 1L, listOf(installer), allocations))
    }

    private fun uncertain(block: () -> Unit) {
        val failure = assertFailsWith<SelfOwnedFailureV1> { block() }
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, failure.reason)
    }

    @Test
    fun sealedStateReadsBackExactlyAndKeysAreV2() {
        val state = fixture()
        assertEquals(SelfOwnedOriginalFormat.V2, keys.originalKeysFormat)
        val sealed = SelfOwnedProvisionerRegistryV2.seal(bootstrap, state, keys)
        val read = SelfOwnedProvisionerRegistryV2.read(bootstrap, sealed, keys)
        assertContentEquals(SelfOwnedNativeProvisionerWireV2.encodeRegistry(state), SelfOwnedNativeProvisionerWireV2.encodeRegistry(read))
        val empty = fixture(allocation = false)
        assertEquals(0, SelfOwnedProvisionerRegistryV2.read(bootstrap, SelfOwnedProvisionerRegistryV2.seal(bootstrap, empty, keys), keys)
            .allocations.size)
    }

    @Test
    fun tamperedEnvelopeOrForeignBootstrapIsRefused() {
        val state = fixture()
        val sealed = SelfOwnedProvisionerRegistryV2.seal(bootstrap, state, keys)
        for (index in listOf(sealed.size - 1, sealed.size - 70, sealed.size / 2)) {
            val tampered = sealed.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertFailsWith<Exception> { SelfOwnedProvisionerRegistryV2.read(bootstrap, tampered, keys) }
        }
        // AAD binds the exact bootstrap: another revision of the same bootstrap is refused.
        assertFailsWith<Exception> { SelfOwnedProvisionerRegistryV2.read(bootstrap.copy(revision = bootstrap.revision + 1), sealed, keys) }
        // A registry signed by a key other than the issuer is refused before decryption.
        keys.signWith = keys.root.private
        val wrongSigner = SelfOwnedProvisionerRegistryV2.sealUnchecked(bootstrap, state, keys)
        uncertain { SelfOwnedProvisionerRegistryV2.read(bootstrap, wrongSigner, keys) }
    }

    @Test
    fun inconsistentDocumentsAllocationsAndGrantsAreRefused() {
        // Root body differs from the actual root key's body.
        val state = fixture()
        val otherRoot = JvmP256Primitives.signDocument(SelfOwnedDocumentRoleV1.ROOT_ANNOUNCEMENT,
            SelfOwnedInstallationWireV1.encodeRoot(rootBody().copy(companyName = "Other Company")), keys.root.private)
        uncertain { SelfOwnedProvisionerRegistryV2.sealUnchecked(bootstrap, state.copy(root = otherRoot), keys)
            .let { SelfOwnedProvisionerRegistryV2.read(bootstrap, it, keys) } }
        // Grant signed by the root key instead of the issuer key.
        val allocation = state.allocations.single()
        val forged = SelfOwnedInstalledIdentitySignedDocumentV2(SelfOwnedInstalledIdentitySignedBodyV2(allocation.body),
            JvmP256Primitives.sign(keys.root.private, SelfOwnedInstalledIdentitySignedBodyV2(allocation.body).copyWireBytes()))
        val forgedState = state.copy(allocations = listOf(allocation.copy(grant = forged)))
        uncertain { SelfOwnedProvisionerRegistryV2.read(bootstrap, SelfOwnedProvisionerRegistryV2.sealUnchecked(bootstrap, forgedState, keys), keys) }
        // Allocation for another installed target (Windows) than the macOS bootstrap target.
        val windowsPreparation = SelfOwnedInstalledIdentityPreparationV2(A.root, allocation.body.rootDocumentDigest, A.issuer,
            allocation.body.delegationDigest, A.installer, 0L, 0L, A.coordinates, 1L, A.bytes(32, 0x51), N.windowsIdentity, "Main Branch")
        val windowsState = state.copy(allocations = listOf(LocalProvisionerAllocationV2(windowsPreparation,
            LocalProvisionerAllocationPhaseV1.GRANT_RETAINED, keys.signOriginalPreparationV2(windowsPreparation))))
        uncertain { SelfOwnedProvisionerRegistryV2.read(bootstrap, SelfOwnedProvisionerRegistryV2.sealUnchecked(bootstrap, windowsState, keys), keys) }
        // An unfinished bootstrap never reads a registry.
        val sealed = SelfOwnedProvisionerRegistryV2.seal(bootstrap, state, keys)
        uncertain { SelfOwnedProvisionerRegistryV2.read(bootstrap.copy(enrollmentStarted = false), sealed, keys) }
        // Seal refuses to retain an inconsistent state in the first place.
        uncertain { SelfOwnedProvisionerRegistryV2.seal(bootstrap, forgedState, keys) }
    }

    @Test
    fun legacyKeyCustodyIsUnsupportedNeverAdapted() {
        val failure = assertFailsWith<SelfOwnedFailureV1> {
            SelfOwnedProvisionerRegistryV2.requireDesktopKeys(object : com.ukkera.pos.security.SelfOwnedOriginalProvisionerKeys {
                override val originalKeysFormat = SelfOwnedOriginalFormat.V1
                override fun assertOriginalNamespace() = Unit
                override fun assertCompletedOriginalKeys() = Unit
                override fun prepareOriginalKeys() = Unit
                override fun assertAllOriginalKeys() = Unit
                override fun originalPoint(role: SelfOwnedNativeKeyRoleV1): ByteArray = ByteArray(65)
            })
        }
        assertEquals(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM, failure.reason)
    }
}
