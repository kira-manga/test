package com.ukkera.pos.security.frozen.originalseamv2

import com.ukkera.pos.security.SelfOwnedBootMark
import com.ukkera.pos.security.SelfOwnedBootOrdinalLedgerV2
import com.ukkera.pos.security.SelfOwnedFailureV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedNativeInstallationWireV1
import com.ukkera.pos.security.SelfOwnedInstallationWireV1
import com.ukkera.pos.security.SelfOwnedNativeKeyRoleV1
import com.ukkera.pos.security.SelfOwnedNativeOriginalProfileV2
import com.ukkera.pos.security.SelfOwnedNativeProvisionerWireV2
import com.ukkera.pos.security.SelfOwnedOriginalFormat
import com.ukkera.pos.security.SelfOwnedOriginalIntent
import com.ukkera.pos.security.SelfOwnedOriginalJournalState
import com.ukkera.pos.security.SelfOwnedProcessPrincipal
import com.ukkera.pos.security.SelfOwnedRegistryEnvelopeV1
import com.ukkera.pos.security.SelfOwnedRegistryEnvelopeV2
import com.ukkera.pos.security.selfOwnedOriginalFormat
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture as A
import com.ukkera.pos.security.frozen.androidv1baseline.DocumentedLayout
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as F
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Frozen expectations for the WP-C version/platform-neutral original seam, authored on
 * 13 November 2026 BEFORE the neutral types, desktop boot-session ID and V2 registry envelope
 * existed. Expected bytes are written from the documented layout (4-byte big-endian length +
 * UTF-8 strings, 4-byte ints, profile + domain + format version int 2), and boot-ID vectors from
 * the documented SHA-256/UUIDv8 derivation, never rendered by the code under test. Synthetic DATA.
 * NOT independent (same author, owner instruction). Android V1 bytes must stay byte-identical.
 */
class OriginalSeamV2FrozenTest {
    private val profile = "ukkera.installation/self-owned-native-original/v2"
    private val installation = A.id(0x301)

    private fun header(domain: String) = DocumentedLayout().string(profile).string(domain).int(2)

    private fun reason(block: () -> Unit): SelfOwnedInstallationFailureV1 =
        assertFailsWith<SelfOwnedFailureV1> { block() }.reason

    private fun documentedId(prefix: String, value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$prefix\u0000${installation.value}\u0000$value".encodeToByteArray())
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x80).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(digest)
        return UUID(buffer.long, buffer.long).toString()
    }

    private val windowsLedger = SelfOwnedBootOrdinalLedgerV2(0, F.windowsBoot, null)
    private val macLedger = SelfOwnedBootOrdinalLedgerV2(0, F.macBoot, null)
    private val windowsTarget = F.binding(F.windowsIdentity)
    private val macTarget = F.binding(F.macIdentity)
    private val androidTarget = SelfOwnedInstalledTargetBindingV1.android(A.target)

    @Test
    fun registryDomainsAreFrozenAndDisjointFromAndroidV1() {
        assertEquals("native-provisioner-registry-aad/v2", SelfOwnedNativeOriginalProfileV2.REGISTRY_AAD_DOMAIN)
        assertEquals("native-provisioner-registry-signature/v2", SelfOwnedNativeOriginalProfileV2.REGISTRY_SIGNATURE_DOMAIN)
        assertEquals("native-provisioner-registry-envelope/v2", SelfOwnedNativeOriginalProfileV2.REGISTRY_ENVELOPE_DOMAIN)
        assertEquals("native-company-authority-registry-aad/v2", SelfOwnedNativeOriginalProfileV2.COMPANY_REGISTRY_AAD_DOMAIN)
        val v2 = setOf(SelfOwnedNativeOriginalProfileV2.REGISTRY_AAD_DOMAIN, SelfOwnedNativeOriginalProfileV2.REGISTRY_SIGNATURE_DOMAIN,
            SelfOwnedNativeOriginalProfileV2.REGISTRY_ENVELOPE_DOMAIN, SelfOwnedNativeOriginalProfileV2.COMPANY_REGISTRY_AAD_DOMAIN)
        assertEquals(4, v2.size)
        assertTrue(v2.none { it in setOf("registry-aad/v1", "registry-signature/v1", "encrypted-registry/v1", "registry-aad",
            "encrypted-native-registry") })
    }

    @Test
    fun registryAadMirrorsV1LayoutUnderTheV2Frame() {
        val bootstrap = F.macCompleteBootstrap()
        val expected = header("native-provisioner-registry-aad/v2").string(A.root.value)
            .string(bootstrap.key(SelfOwnedNativeKeyRoleV1.REGISTRY).keyId.value).string(A.issuer.value)
            .field(SelfOwnedNativeProvisionerWireV2.encodeBootstrap(bootstrap)).bytes()
        assertContentEquals(expected, SelfOwnedNativeProvisionerWireV2.registryAad(bootstrap))
        val company = F.macCompanyBootstrap()
        assertContentEquals(header("native-company-authority-registry-aad/v2")
            .field(SelfOwnedNativeProvisionerWireV2.encodeCompanyBootstrap(company)).bytes(),
            SelfOwnedNativeProvisionerWireV2.companyRegistryAad(company))
    }

    @Test
    fun registrySignatureMessageAndEnvelopeUseDocumentedFraming() {
        val aad = A.bytes(40, 0x11); val nonce = A.bytes(12, 0x22); val ciphertext = A.bytes(48, 0x33)
        val signature = A.bytes(64, 0x44)
        assertContentEquals(header("native-provisioner-registry-signature/v2").field(aad).field(nonce).field(ciphertext).bytes(),
            SelfOwnedNativeProvisionerWireV2.registrySignature(aad, nonce, ciphertext))
        val expected = header("native-provisioner-registry-envelope/v2").field(nonce).field(ciphertext).field(signature).bytes()
        val encoded = SelfOwnedNativeProvisionerWireV2.encodeRegistryEnvelope(SelfOwnedRegistryEnvelopeV2(
            nonce.copyOf(), ciphertext.copyOf(), signature.copyOf()))
        assertContentEquals(expected, encoded)
        val decoded = assertIs<SelfOwnedInstalledIdentityResultV2.Data<SelfOwnedRegistryEnvelopeV2>>(
            SelfOwnedNativeProvisionerWireV2.decodeRegistryEnvelope(encoded)).value
        assertContentEquals(nonce, decoded.nonce); assertContentEquals(ciphertext, decoded.ciphertext)
        assertContentEquals(signature, decoded.signature)
        assertEquals("SelfOwnedRegistryEnvelopeV2([REDACTED])", decoded.toString())
    }

    @Test
    fun registryEnvelopeRefusesMalformedAndCrossVersionBytes() {
        val nonce = A.bytes(12, 1); val ciphertext = A.bytes(16, 2); val signature = A.bytes(64, 3)
        fun refused(bytes: ByteArray) = assertIs<SelfOwnedInstalledIdentityResultV2.Refused>(
            SelfOwnedNativeProvisionerWireV2.decodeRegistryEnvelope(bytes))
        val good = header("native-provisioner-registry-envelope/v2").field(nonce).field(ciphertext).field(signature).bytes()
        assertIs<SelfOwnedInstalledIdentityResultV2.Data<*>>(SelfOwnedNativeProvisionerWireV2.decodeRegistryEnvelope(good))
        refused(good + byteArrayOf(0))
        refused(good.copyOf(good.size - 1))
        refused(header("native-provisioner-registry-envelope/v2").field(A.bytes(11, 1)).field(ciphertext).field(signature).bytes())
        refused(header("native-provisioner-registry-envelope/v2").field(nonce).field(A.bytes(15, 2)).field(signature).bytes())
        refused(header("native-provisioner-registry-envelope/v2").field(nonce).field(ciphertext).field(A.bytes(63, 3)).bytes())
        refused(DocumentedLayout().string(profile).string("native-provisioner-registry-envelope/v2").int(1)
            .field(nonce).field(ciphertext).field(signature).bytes())
        refused(header("native-provisioner-registry-signature/v2").field(nonce).field(ciphertext).field(signature).bytes())
        val v1 = SelfOwnedInstallationWireV1.encodeEnvelope(nonce, ciphertext, signature)
        refused(v1)
        assertFailsWith<Exception> { SelfOwnedInstallationWireV1.decodeEnvelope(good) }
        assertFailsWith<Exception> { SelfOwnedRegistryEnvelopeV2(A.bytes(12, 1), A.bytes(15, 2), A.bytes(64, 3)) }
    }

    @Test
    fun desktopBootSessionIdUsesDocumentedDerivationAndAndroidKeepsBootCount() {
        val windows = documentedId("ukkera.desktop-boot-session/v2", F.WINDOWS_BOOT)
        assertEquals("bf460e1b-4577-8c15-b333-fa7d21850c4d", windows)
        assertEquals(windows, SelfOwnedBootMark.Session(windowsLedger).bootId(installation).value)
        val mac = documentedId("ukkera.desktop-boot-session/v2", F.MAC_BOOT)
        assertEquals("6b84d825-8556-8849-9a97-cc078f4c9f1a", mac)
        assertEquals(mac, SelfOwnedBootMark.Session(macLedger).bootId(installation).value)
        assertEquals("885b3449-918b-8093-b24a-c1112da87dd6", documentedId("ukkera.android-boot-count/v1", "42"))
        assertEquals("885b3449-918b-8093-b24a-c1112da87dd6", SelfOwnedBootMark.AndroidCount(42).bootId(installation).value)
        // Ledger ordinal never enters the desktop ID: a new session changes it, the same session does not.
        val advanced = SelfOwnedBootOrdinalLedgerV2(1, com.ukkera.pos.security.SelfOwnedNativeBootSessionV2.Windows(F.WINDOWS_NEXT_BOOT),
            windowsLedger.digest())
        assertFalse(SelfOwnedBootMark.Session(advanced).bootId(installation).value == windows)
    }

    @Test
    fun bootMarksCompareOnlyTheSameVariantAndExposeOrdinals() {
        assertEquals(7, SelfOwnedBootMark.AndroidCount(7).ordinal)
        assertEquals(0, SelfOwnedBootMark.Session(windowsLedger).ordinal)
        assertTrue(SelfOwnedBootMark.AndroidCount(7).sameBoot(SelfOwnedBootMark.AndroidCount(7)))
        assertFalse(SelfOwnedBootMark.AndroidCount(7).sameBoot(SelfOwnedBootMark.AndroidCount(8)))
        assertFalse(SelfOwnedBootMark.AndroidCount(0).sameBoot(SelfOwnedBootMark.Session(windowsLedger)))
        assertFalse(SelfOwnedBootMark.Session(windowsLedger).sameBoot(SelfOwnedBootMark.Session(macLedger)))
        assertTrue(SelfOwnedBootMark.Session(windowsLedger).sameBoot(SelfOwnedBootMark.Session(windowsLedger.copy())))
        assertFailsWith<IllegalArgumentException> { SelfOwnedBootMark.AndroidCount(-1) }
    }

    @Test
    fun processPrincipalsValidateAndRedact() {
        assertEquals(SelfOwnedProcessPrincipal.AndroidUid(10123), SelfOwnedProcessPrincipal.AndroidUid(10123))
        assertFalse(SelfOwnedProcessPrincipal.AndroidUid(501) == SelfOwnedProcessPrincipal.MacUid(501) as Any)
        assertFailsWith<IllegalArgumentException> { SelfOwnedProcessPrincipal.AndroidUid(-1) }
        assertFailsWith<IllegalArgumentException> { SelfOwnedProcessPrincipal.MacUid(-1) }
        assertFailsWith<IllegalArgumentException> { SelfOwnedProcessPrincipal.WindowsSid("S-1-5") }
        assertFailsWith<IllegalArgumentException> { SelfOwnedProcessPrincipal.WindowsSid("not-a-sid") }
        val sid = SelfOwnedProcessPrincipal.WindowsSid(F.OWNER_SID)
        assertEquals("SelfOwnedProcessPrincipal.WindowsSid([REDACTED])", sid.toString())
        assertEquals("SelfOwnedProcessPrincipal.AndroidUid([REDACTED])", SelfOwnedProcessPrincipal.AndroidUid(1).toString())
        assertEquals("SelfOwnedProcessPrincipal.MacUid([REDACTED])", SelfOwnedProcessPrincipal.MacUid(1).toString())
    }

    @Test
    fun nativePlatformSelectsTheFormatWithoutFallback() {
        val android = SelfOwnedBootMark.AndroidCount(3)
        val windows = SelfOwnedBootMark.Session(windowsLedger)
        val mac = SelfOwnedBootMark.Session(macLedger)
        assertEquals(SelfOwnedOriginalFormat.V1,
            selfOwnedOriginalFormat(androidTarget, android, SelfOwnedProcessPrincipal.AndroidUid(A.target.applicationUid)))
        assertEquals(SelfOwnedOriginalFormat.V2,
            selfOwnedOriginalFormat(windowsTarget, windows, SelfOwnedProcessPrincipal.WindowsSid(F.OWNER_SID)))
        assertEquals(SelfOwnedOriginalFormat.V2, selfOwnedOriginalFormat(macTarget, mac, SelfOwnedProcessPrincipal.MacUid(501)))
        val unsupported = SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM
        // Android target with any desktop boot/principal, desktop target with Android facts, cross-desktop mixes.
        assertEquals(unsupported, reason { selfOwnedOriginalFormat(androidTarget, windows, SelfOwnedProcessPrincipal.AndroidUid(1)) })
        assertEquals(unsupported, reason { selfOwnedOriginalFormat(androidTarget, android, SelfOwnedProcessPrincipal.MacUid(1)) })
        assertEquals(unsupported, reason { selfOwnedOriginalFormat(windowsTarget, android, SelfOwnedProcessPrincipal.WindowsSid(F.OWNER_SID)) })
        assertEquals(unsupported, reason { selfOwnedOriginalFormat(windowsTarget, windows, SelfOwnedProcessPrincipal.AndroidUid(1)) })
        assertEquals(unsupported, reason { selfOwnedOriginalFormat(windowsTarget, mac, SelfOwnedProcessPrincipal.WindowsSid(F.OWNER_SID)) })
        assertEquals(unsupported, reason { selfOwnedOriginalFormat(windowsTarget, windows, SelfOwnedProcessPrincipal.MacUid(501)) })
        assertEquals(unsupported, reason { selfOwnedOriginalFormat(macTarget, mac, SelfOwnedProcessPrincipal.WindowsSid(F.OWNER_SID)) })
        assertEquals(unsupported, reason { selfOwnedOriginalFormat(macTarget, windows, SelfOwnedProcessPrincipal.MacUid(501)) })
        assertEquals(unsupported, reason { selfOwnedOriginalFormat(macTarget, android, SelfOwnedProcessPrincipal.MacUid(501)) })
    }

    @Test
    fun v1WrappersAreByteIdenticalAndRefuseCrossVersionDecoding() {
        val intent = A.completeIntent()
        val wrapped = SelfOwnedOriginalIntent.V1(intent)
        assertSame(intent, wrapped.value)
        assertEquals(SelfOwnedOriginalFormat.V1, wrapped.format)
        assertContentEquals(SelfOwnedNativeInstallationWireV1.encodeIntent(intent), wrapped.encode())
        val bytes = SelfOwnedNativeInstallationWireV1.encodeIntent(intent)
        assertContentEquals(bytes, SelfOwnedOriginalIntent.decode(SelfOwnedOriginalFormat.V1, bytes).encode())
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
            reason { SelfOwnedOriginalIntent.decode(SelfOwnedOriginalFormat.V2, bytes) })
        val state = A.journalState()
        val stateBytes = SelfOwnedNativeInstallationWireV1.encodeState(state)
        assertContentEquals(stateBytes, SelfOwnedOriginalJournalState.V1(state).encode())
        assertContentEquals(stateBytes, SelfOwnedOriginalJournalState.decode(SelfOwnedOriginalFormat.V1, stateBytes).encode())
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
            reason { SelfOwnedOriginalJournalState.decode(SelfOwnedOriginalFormat.V2, stateBytes) })
        val envelope = SelfOwnedNativeInstallationWireV1.encodeEnvelope(
            SelfOwnedRegistryEnvelopeV1(A.bytes(12, 1), A.bytes(16, 2), A.bytes(64, 3)))
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
            reason { com.ukkera.pos.security.SelfOwnedOriginalJournalEnvelope.decode(SelfOwnedOriginalFormat.V2, envelope) })
    }
}
