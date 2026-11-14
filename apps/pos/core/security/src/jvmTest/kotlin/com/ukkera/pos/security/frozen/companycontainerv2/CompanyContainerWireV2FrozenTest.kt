package com.ukkera.pos.security.frozen.companycontainerv2

import com.ukkera.pos.security.LocalCompanyAuthorityStateV1
import com.ukkera.pos.security.LocalCompanyAuthorityStateV2
import com.ukkera.pos.security.LocalCompanyAuthorityWireV1
import com.ukkera.pos.security.LocalCompanyHeadAccountV1
import com.ukkera.pos.security.LocalCompanyHeadEnrollmentBodyV1
import com.ukkera.pos.security.LocalCompanyHeadEnrollmentV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyInstallationAdmissionV1
import com.ukkera.pos.security.LocalCompanyInstallationClaimV1
import com.ukkera.pos.security.LocalCompanyPolicyPhaseV1
import com.ukkera.pos.security.LocalCompanyPolicyRecordV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedIdentityReader
import com.ukkera.pos.security.SelfOwnedInstalledIdentityProfileV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentitySignedBodyV2
import com.ukkera.pos.security.SelfOwnedNativeCompanyProfileV2
import com.ukkera.pos.security.SelfOwnedNativeOriginalProfileV2
import com.ukkera.pos.security.SelfOwnedNativeProvisionerWireV2 as W
import com.ukkera.pos.security.SelfOwnedOctetsV1
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture as A
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as N
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

/**
 * Frozen wire rules for the Windows/macOS (V2) company container, authored on 13 November 2026
 * BEFORE `encodeCompanyState`/`encodeHeadEnrollmentBody`/verifier frames existed. NOT independent
 * (same author, owner instruction). Synthetic DATA only; nothing here proves custody or authority.
 *
 * Rules: the container mirrors the Android V1 company layout (registry, root-signed head enrollment,
 * head account, policies, installation claims, admission, optional serial custody) inside the closed
 * V2 frame `ukkera.installation/self-owned-native-original/v2` with the new domains
 * `native-company-authority-state/v2`, `native-company-head-enrollment(-body)/v2` and the verifier
 * domains `native-provisioner-installer-verifier/v2` / `native-company-head-verifier/v2`.
 * Decoding is canonical; truncation, trailing bytes, foreign domains and Android V1 company bytes are
 * refused in both directions (no cross-version fallback).
 *
 * Signature convention frozen here: the issuer V2 grant signs the exact signed-body bytes, whose
 * first two strings are the V2 identity profile and the dedicated `signed-body/v2` domain; the
 * root signs the exact `native-company-head-enrollment-body/v2` frame.
 */
class CompanyContainerWireV2FrozenTest {
    private val enrollmentBody = LocalCompanyHeadEnrollmentBodyV1(A.root, A.rootSigningKey, A.organization, A.company,
        A.bootstrapAction, A.headAuthority, A.headEnrollment, "Synthetic Head", "head.one",
        LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, A.headPolicyKey, SelfOwnedOctetsV1(A.point(0x41)),
        A.headInstallationKey, SelfOwnedOctetsV1(A.point(0x42)))
    private val enrollment = LocalCompanyHeadEnrollmentV1(enrollmentBody, SelfOwnedOctetsV1(A.bytes(64, 0x43)))
    private val head = LocalCompanyHeadAccountV1(A.headAuthority, A.headEnrollment, "Synthetic Head", "head.one",
        0L, 0L, true, 0L, true, 0L, true, SelfOwnedOctetsV1(A.bytes(32, 0x44)), SelfOwnedOctetsV1(A.bytes(32, 0x45)),
        0, null, 2, 1_000L, 0L)

    private fun state(): LocalCompanyAuthorityStateV2 = LocalCompanyAuthorityStateV2(N.registry(), enrollment, head,
        emptyList(), emptyList())

    private fun richState(): LocalCompanyAuthorityStateV2 {
        val policy = LocalCompanyPolicyRecordV1(A.id(0x601), 0L, 0L, 0L, 0L, SelfOwnedOctetsV1(A.bytes(40, 0x46)),
            SelfOwnedOctetsV1(A.bytes(48, 0x47)), LocalCompanyPolicyPhaseV1.ISSUED, SelfOwnedOctetsV1(A.bytes(80, 0x48)))
        val claim = LocalCompanyInstallationClaimV1(A.id(0x602), 1L, A.id(0x603), PayloadDigest("1".repeat(64)),
            PayloadDigest("2".repeat(64)), SelfOwnedOctetsV1(A.bytes(30, 0x49)), null, null, null,
            SelfOwnedOctetsV1(A.bytes(20, 0x4a)))
        val admission = LocalCompanyInstallationAdmissionV1(A.id(0x603), PayloadDigest("1".repeat(64)),
            PayloadDigest("2".repeat(64)), 2L)
        return LocalCompanyAuthorityStateV2(N.registry(), enrollment, head.copy(failedAttempts = 1,
            reservationId = A.id(0x604), cooldownMillis = 1_000L), listOf(policy), listOf(claim), admission)
    }

    private fun <T> data(result: SelfOwnedInstalledIdentityResultV2<T>): T =
        assertIs<SelfOwnedInstalledIdentityResultV2.Data<T>>(result).value

    private fun refused(result: SelfOwnedInstalledIdentityResultV2<*>) {
        assertIs<SelfOwnedInstalledIdentityResultV2.Refused>(result)
    }

    private fun header(bytes: ByteArray): Pair<String, String> {
        val reader = SelfOwnedIdentityReader(bytes)
        return reader.string() to reader.string()
    }

    @Test
    fun companyStateRoundTripsCanonicallyUnderItsOwnV2Domain() {
        for (value in listOf(state(), richState())) {
            val bytes = W.encodeCompanyState(value)
            assertEquals(SelfOwnedNativeOriginalProfileV2.ID to "native-company-authority-state/v2", header(bytes))
            val decoded = data(W.decodeCompanyState(bytes))
            assertContentEquals(bytes, W.encodeCompanyState(decoded))
            assertEquals(value.head, decoded.head)
            assertEquals(value.policies, decoded.policies)
            assertEquals(value.installationClaims, decoded.installationClaims)
            assertEquals(value.installationAdmission, decoded.installationAdmission)
            assertContentEquals(W.encodeRegistry(value.original), W.encodeRegistry(decoded.original))
        }
        assertEquals("LocalCompanyAuthorityStateV2([REDACTED])", state().toString())
    }

    @Test
    fun truncatedTrailingAndForeignBytesAreRefused() {
        val bytes = W.encodeCompanyState(state())
        refused(W.decodeCompanyState(bytes.copyOf(bytes.size - 1)))
        refused(W.decodeCompanyState(bytes + byteArrayOf(0)))
        refused(W.decodeCompanyState(W.encodeRegistry(N.registry())))
        refused(W.decodeRegistry(bytes))
        // The Android V1 company container never decodes as V2, and V2 never decodes as V1.
        val v1 = LocalCompanyAuthorityWireV1.encodeState(LocalCompanyAuthorityStateV1(A.registryState(),
            LocalCompanyAuthorityWireV1.decodeEnrollment(LocalCompanyAuthorityWireV1.encodeEnrollment(enrollment)), head,
            emptyList(), emptyList()))
        refused(W.decodeCompanyState(v1))
        assertFailsWith<Exception> { LocalCompanyAuthorityWireV1.decodeState(bytes) }
        assertFalse(LocalCompanyAuthorityWireV1.isCompanyProfile(bytes))
    }

    @Test
    fun headEnrollmentBodyIsDomainSeparatedFromAndroidV1() {
        val v2 = W.encodeHeadEnrollmentBody(enrollmentBody)
        val v1 = LocalCompanyAuthorityWireV1.encodeEnrollmentBody(enrollmentBody)
        assertEquals(SelfOwnedNativeOriginalProfileV2.ID to SelfOwnedNativeCompanyProfileV2.HEAD_ENROLLMENT_BODY_DOMAIN, header(v2))
        assertFalse(v1.contentEquals(v2))
        assertEquals(enrollmentBody, data(W.decodeHeadEnrollmentBody(v2)))
        refused(W.decodeHeadEnrollmentBody(v1))
        assertFailsWith<Exception> { LocalCompanyAuthorityWireV1.decodeEnrollmentBody(v2) }
        val document = W.encodeHeadEnrollment(enrollment)
        assertEquals(SelfOwnedNativeOriginalProfileV2.ID to "native-company-head-enrollment/v2", header(document))
        val decoded = data(W.decodeHeadEnrollment(document))
        assertEquals(enrollment.body, decoded.body)
        assertEquals(enrollment.signature, decoded.signature)
        refused(W.decodeHeadEnrollment(LocalCompanyAuthorityWireV1.encodeEnrollment(enrollment)))
    }

    @Test
    fun verifierFramesUseDisjointV2DomainsAndBindTheirInputs() {
        val bootstrap = N.macCompleteBootstrap()
        val salt = A.bytes(32, 0x50); val derived = A.bytes(32, 0x51)
        val installer = W.installerVerifierMessage(bootstrap, A.installer, 0L, salt, derived)
        assertEquals(SelfOwnedNativeOriginalProfileV2.ID to "native-provisioner-installer-verifier/v2", header(installer))
        assertNotEquals(installer.toList(), W.installerVerifierMessage(bootstrap, A.installer, 1L, salt, derived).toList())
        assertNotEquals(installer.toList(), W.installerVerifierMessage(bootstrap, A.installer, 0L, salt, A.bytes(32, 0x52)).toList())
        val company = N.macCompanyBootstrap()
        val headMessage = W.headVerifierMessage(company, 0L, salt, derived)
        assertEquals(SelfOwnedNativeOriginalProfileV2.ID to "native-company-head-verifier/v2", header(headMessage))
        assertFalse(headMessage.contentEquals(installer))
    }

    @Test
    fun issuerGrantSignsTheExactDomainSeparatedV2SignedBody() {
        val signed = SelfOwnedInstalledIdentitySignedBodyV2(N.preparation()).copyWireBytes()
        assertEquals(SelfOwnedInstalledIdentityProfileV2.ID to SelfOwnedInstalledIdentityProfileV2.SIGNING_BODY_DOMAIN, header(signed))
        assertEquals("signed-body/v2", SelfOwnedInstalledIdentityProfileV2.SIGNING_BODY_DOMAIN)
    }

    @Test
    fun containerKeepsTheAndroidV1Invariants() {
        val claim = richState().installationClaims.single()
        // A second claim must follow the first in sequence and the first must be retired.
        assertFailsWith<IllegalArgumentException> {
            LocalCompanyAuthorityStateV2(N.registry(), enrollment, head, emptyList(),
                listOf(claim, claim.copy(issuedAttemptId = A.id(0x605), attemptSequence = 3L)))
        }
        assertFailsWith<IllegalArgumentException> {
            LocalCompanyAuthorityStateV2(N.registry(), enrollment, head, emptyList(), emptyList(),
                LocalCompanyInstallationAdmissionV1(A.id(0x603), PayloadDigest("1".repeat(64)), PayloadDigest("2".repeat(64)), 2L))
        }
        // One whole-container revision step advances exactly the embedded registry revision.
        val next = state().next()
        assertEquals(state().original.revision + 1L, next.original.revision)
        assertFailsWith<IllegalArgumentException> { state().next(original = N.registry()) }
    }
}
