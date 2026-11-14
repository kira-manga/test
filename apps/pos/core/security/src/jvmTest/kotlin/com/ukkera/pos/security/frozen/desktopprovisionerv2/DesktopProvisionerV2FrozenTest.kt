package com.ukkera.pos.security.frozen.desktopprovisionerv2

import com.ukkera.pos.security.LocalCompanyAuthorityRefusal
import com.ukkera.pos.security.LocalCompanyAuthorityFailure
import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.LocalProvisionerAllocationPhaseV1
import com.ukkera.pos.security.SelfOwnedCompanyAuthorityV2
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedDesktopProvisionerV2
import com.ukkera.pos.security.SelfOwnedFailureV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentitySignedBodyV2
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedNativeKeyRoleV1
import com.ukkera.pos.security.SelfOwnedNativeProvisionerWireV2
import com.ukkera.pos.security.SelfOwnedOriginalFormat
import com.ukkera.pos.security.SelfOwnedOriginalProvisionerKeys
import com.ukkera.pos.security.SelfOwnedProvisionerRegistryV2
import com.ukkera.pos.security.JvmP256Primitives
import com.ukkera.pos.security.desktopseamv2.DesktopSeamV2Double
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen rules for the Windows/macOS (V2) provisioner write path, authored on 13 November 2026 with
 * the desktop provisioner core. NOT independent (same author, owner instruction). Software keys in
 * the jvmTest-only [DesktopSeamV2Double] stand in for native custody; synthetic data only.
 *
 * Rules (mirroring Android V1 founding/issuance/installer authentication over V2 formats only):
 * - Four-key founding retains the bootstrap, prepares the keys, marks enrollment started BEFORE the
 *   first registry, and the registry is the issuer-signed, registry-key-sealed V2 envelope.
 * - Seven-key founding retains ONE company container (registry + root-signed head enrollment + head
 *   account) under the company AAD; the four-key registry AAD never opens it.
 * - Exactly one original allocation for this actual installed target, serial 1; the grant is the
 *   issuer signature over the exact V2 signed body and is retained (GRANT_RETAINED) with readback.
 * - Installer authentication retains the failure reservation before derivation; a wrong secret is
 *   AUTHENTICATION_REFUSED, then THROTTLED until the cooldown elapses; a later boot re-arms the full
 *   retained cooldown. Pending actions do not survive a boot.
 * - V1 key custody, Android environments and foreign/tampered bytes are refused, never adapted.
 */
class DesktopProvisionerV2FrozenTest {
    private val password = "synthetic-installer-pass".toCharArray()
    private val headPassword = "synthetic-head-pass-2026".toCharArray()
    private val facts = SelfOwnedCompanyFactsV1("Synthetic Company", "Synthetic Person", "installer.one",
        SelfOwnedKeyProtectionV1.OS_ISOLATED)

    private fun reason(block: () -> Unit): SelfOwnedInstallationFailureV1 = assertFailsWith<SelfOwnedFailureV1> { block() }.reason

    private fun <T> withProvisioner(seam: DesktopSeamV2Double, block: (SelfOwnedDesktopProvisionerV2) -> T): T {
        val work = seam.openWork()
        try { return block(SelfOwnedDesktopProvisionerV2(seam.environment, seam.store, seam.keys, work)) }
        finally { seam.closeWork(work) }
    }

    private fun foundFourKey(seam: DesktopSeamV2Double) = withProvisioner(seam) { p ->
        p.completeNewCompany(p.prepareNewCompany(facts), password.copyOf())
    }

    @Test
    fun fourKeyFoundingRetainsAnIssuerSignedV2RegistryAfterTheEnrollmentMarker() {
        val seam = DesktopSeamV2Double()
        val view = foundFourKey(seam)
        assertNull(view.headAuthorityId)
        assertTrue(view.issuanceEnabled)
        val bootstrap = assertNotNull(seam.store.bootstrapRecordV2())
        assertTrue(bootstrap.complete() && bootstrap.enrollmentStarted)
        assertNull(seam.store.companyBootstrapRecordV2())
        val state = SelfOwnedProvisionerRegistryV2.read(bootstrap, seam.envelopeBytes!!, seam.keys)
        assertEquals(0L, state.revision); assertEquals(1L, state.nextSerial); assertTrue(state.allocations.isEmpty())
        assertEquals(view.rootFingerprint, JvmP256Primitives.rootFingerprint(seam.keys.rootBody()))
        // A second founding on the same namespace is refused.
        assertEquals(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED, reason { withProvisioner(seam) { it.prepareNewCompany(facts) } })
    }

    @Test
    fun sevenKeyFoundingRetainsOneCompanyContainerUnderTheCompanyAad() {
        val seam = DesktopSeamV2Double()
        val founding = LocalCompanyFoundingFactsV1(facts, "Synthetic Head", "head.one", LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
            LocalCompanyHeadPurposeV1.entries.toSet())
        val view = withProvisioner(seam) { p ->
            p.completeNewCompanyAndHead(p.prepareNewCompanyAndHead(founding), password.copyOf(), headPassword.copyOf())
        }
        val company = assertNotNull(seam.store.companyBootstrapRecordV2())
        assertEquals(company.headAuthorityId, view.headAuthorityId)
        val state = SelfOwnedCompanyAuthorityV2.read(company, seam.envelopeBytes!!, seam.keys, seam.chain.last().ordinal)
        assertEquals(company.headLogin, state.head.login)
        // The root signed the exact V2 head enrollment body frame.
        assertTrue(JvmP256Primitives.verify(seam.keys.originalPoint(SelfOwnedNativeKeyRoleV1.ROOT),
            SelfOwnedNativeProvisionerWireV2.encodeHeadEnrollmentBody(state.enrollment.body), state.enrollment.signature.copyBytes()))
        // The original-only registry AAD never opens the company container (and vice versa).
        assertFailsWith<SelfOwnedFailureV1> { SelfOwnedProvisionerRegistryV2.read(company.original, seam.envelopeBytes!!, seam.keys) }
        // A four-key founding cannot start on a namespace that holds a company bootstrap.
        assertEquals(SelfOwnedInstallationFailureV1.CONFLICT, reason { withProvisioner(seam) { it.prepareNewCompany(facts) } })
        // A company is never founded over an existing original-only bootstrap.
        val legacy = DesktopSeamV2Double(); foundFourKey(legacy)
        val refusal = assertFailsWith<LocalCompanyAuthorityRefusal> { withProvisioner(legacy) { it.prepareNewCompanyAndHead(founding) } }
        assertEquals(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED, refusal.reason)
    }

    @Test
    fun branchIssuanceRetainsTheIssuerGrantOverTheExactV2SignedBody() {
        val seam = DesktopSeamV2Double()
        val view = foundFourKey(seam)
        val packet = withProvisioner(seam) { p ->
            val action = p.prepareOriginalBranch("Main Branch", view.rootFingerprint)
            // Another branch name than the single allocation is a conflict.
            assertEquals(SelfOwnedInstallationFailureV1.CONFLICT, reason { p.prepareOriginalBranch("Other Branch", view.rootFingerprint) })
            p.issueOriginalPreparation(action, "installer.one", password.copyOf())
        }
        val bootstrap = seam.store.bootstrapRecordV2()!!
        val state = SelfOwnedProvisionerRegistryV2.read(bootstrap, seam.envelopeBytes!!, seam.keys)
        val allocation = state.allocations.single()
        assertEquals(LocalProvisionerAllocationPhaseV1.GRANT_RETAINED, allocation.phase)
        assertEquals(1L, allocation.body.serial); assertEquals(2L, state.nextSerial)
        val signed = SelfOwnedInstalledIdentitySignedBodyV2(allocation.body).copyWireBytes()
        assertContentEquals(signed, packet.preparation.signedBody.copyWireBytes())
        assertTrue(JvmP256Primitives.verify(seam.keys.originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER), signed, packet.preparation.copySignature()))
        assertTrue(packet.installedTarget.matches(seam.environment.installedTarget))
        // Exact-result recovery without credentials returns the same grant bytes.
        val recovered = withProvisioner(seam) { it.recoverOriginalPreparation() }
        assertContentEquals(packet.preparation.copyWireBytes(), recovered.preparation.copyWireBytes())
        // A wrong enrolled-root fingerprint never selects the issuer.
        assertEquals(SelfOwnedInstallationFailureV1.EXISTING_AUTHORITY_REQUIRED,
            reason { withProvisioner(seam) { it.prepareOriginalBranch("Main Branch", "0".repeat(64)) } })
    }

    @Test
    fun aGrantNotSignedByTheIssuerIsNeverRetained() {
        val seam = DesktopSeamV2Double()
        val view = foundFourKey(seam)
        seam.forgeGrantsWithRoot = true
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason {
            withProvisioner(seam) { p -> p.issueOriginalPreparation(p.prepareOriginalBranch("Main Branch", view.rootFingerprint),
                "installer.one", password.copyOf()) }
        })
        val state = SelfOwnedProvisionerRegistryV2.read(seam.store.bootstrapRecordV2()!!, seam.envelopeBytes!!, seam.keys)
        assertEquals(LocalProvisionerAllocationPhaseV1.SIGNING_INTENT, state.allocations.single().phase)
    }

    @Test
    fun installerAuthenticationThrottlesDurablyAndAcrossBoots() {
        val seam = DesktopSeamV2Double()
        val view = foundFourKey(seam)
        withProvisioner(seam) { p ->
            val action = p.prepareOriginalBranch("Main Branch", view.rootFingerprint)
            assertEquals(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED,
                reason { p.issueOriginalPreparation(action, "installer.one", "wrong-synthetic-pass".toCharArray()) })
            val retained = SelfOwnedProvisionerRegistryV2.read(seam.store.bootstrapRecordV2()!!, seam.envelopeBytes!!, seam.keys)
            val account = retained.installers.single()
            assertEquals(1, account.failedAttempts); assertNull(account.reservationId); assertEquals(1_000L, account.cooldownMillis)
            // Still within the cooldown: refused before any derivation.
            assertEquals(SelfOwnedInstallationFailureV1.THROTTLED, reason { p.issueOriginalPreparation(action, "installer.one", password.copyOf()) })
        }
        seam.nextBoot()
        withProvisioner(seam) { p ->
            val action = p.prepareOriginalBranch("Main Branch", view.rootFingerprint)
            // A later boot re-arms the full retained cooldown (elapsed time is not comparable across boots).
            assertEquals(SelfOwnedInstallationFailureV1.THROTTLED, reason { p.issueOriginalPreparation(action, "installer.one", password.copyOf()) })
            val account = SelfOwnedProvisionerRegistryV2.read(seam.store.bootstrapRecordV2()!!, seam.envelopeBytes!!, seam.keys).installers.single()
            assertEquals(seam.chain.last().ordinal, account.throttleBoot)
            seam.elapsed.addAndGet(1_000L)
            val packet = p.issueOriginalPreparation(action, "installer.one", password.copyOf())
            assertEquals("Main Branch", packet.preparation.preparation.branchName)
        }
    }

    @Test
    fun pendingActionsDoNotSurviveABootOrAnotherOwner() {
        val seam = DesktopSeamV2Double()
        val view = foundFourKey(seam)
        val work = seam.openWork()
        try {
            val first = SelfOwnedDesktopProvisionerV2(seam.environment, seam.store, seam.keys, work)
            val action = first.prepareOriginalBranch("Main Branch", view.rootFingerprint)
            val other = SelfOwnedDesktopProvisionerV2(seam.environment, seam.store, seam.keys, work)
            assertEquals(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED,
                reason { other.issueOriginalPreparation(action, "installer.one", password.copyOf()) })
            seam.nextBoot()
            assertEquals(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED,
                reason { first.issueOriginalPreparation(action, "installer.one", password.copyOf()) })
            first.withdrawNewIssuance()
            assertEquals(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN,
                reason { other.prepareOriginalBranch("Main Branch", view.rootFingerprint) })
            assertFalse(SelfOwnedProvisionerRegistryV2.read(seam.store.bootstrapRecordV2()!!, seam.envelopeBytes!!, seam.keys).issuanceEnabled)
        } finally { seam.closeWork(work) }
    }

    @Test
    fun legacyCustodyAndAndroidEnvironmentsAreUnsupported() {
        val seam = DesktopSeamV2Double()
        val legacyKeys = object : SelfOwnedOriginalProvisionerKeys {
            override val originalKeysFormat = SelfOwnedOriginalFormat.V1
            override fun assertOriginalNamespace() = Unit
            override fun assertCompletedOriginalKeys() = Unit
            override fun prepareOriginalKeys() = Unit
            override fun assertAllOriginalKeys() = Unit
            override fun originalPoint(role: SelfOwnedNativeKeyRoleV1): ByteArray = ByteArray(65)
        }
        val work = seam.openWork()
        try {
            assertEquals(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM,
                reason { SelfOwnedDesktopProvisionerV2(seam.environment, seam.store, legacyKeys, work) })
            assertEquals(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM,
                reason { SelfOwnedCompanyAuthorityV2.requireCompanyKeys(legacyKeys) })
            seam.principal = com.ukkera.pos.security.SelfOwnedProcessPrincipal.AndroidUid(10123)
            assertEquals(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM,
                reason { SelfOwnedDesktopProvisionerV2(seam.environment, seam.store, seam.keys, work) })
        } finally { seam.closeWork(work) }
    }
}
