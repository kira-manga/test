package com.ukkera.pos.security.desktopseamv2

import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedDesktopProvisionerV2
import com.ukkera.pos.security.SelfOwnedFailureV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedInstallationWireV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedMacServiceIdentityV2
import com.ukkera.pos.security.SelfOwnedOriginalFormat
import com.ukkera.pos.security.SelfOwnedOriginalRegistryState
import com.ukkera.pos.security.SelfOwnedOriginalSource
import com.ukkera.pos.security.SelfOwnedProcessPrincipal
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture as A
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as N
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end SLICE of the desktop (V2) original seam over the jvmTest-only [DesktopSeamV2Double]:
 * seven-key company founding -> original branch allocation -> installer authentication and issuer
 * grant -> reopen (new owner) -> the shared owner's version-neutral original-preparation read ->
 * next OS boot (chain +1) reopen. Refusals: tamper, truncated/rolled-back boot chain, wrong
 * principal, wrong installed target, Android V1 bytes on a V2 platform.
 *
 * Not covered here (needs further doubles of the owner's database namespace, Room history,
 * receipt membership, target signing/data keys and Master SDK): history custody claim and the
 * Master SDK V2 capture. Software keys prove no native custody. Synthetic data only.
 */
class DesktopOriginalSeamV2EndToEndTest {
    private val facts = SelfOwnedCompanyFactsV1("Synthetic Company", "Synthetic Person", "installer.one",
        SelfOwnedKeyProtectionV1.OS_ISOLATED)
    private val founding = LocalCompanyFoundingFactsV1(facts, "Synthetic Head", "head.one",
        LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, LocalCompanyHeadPurposeV1.entries.toSet())
    private val installerSecret = "synthetic-installer-pass"
    private val headSecret = "synthetic-head-pass-2026"

    private fun reason(block: () -> Unit): SelfOwnedInstallationFailureV1 = assertFailsWith<SelfOwnedFailureV1> { block() }.reason

    private fun <T> owner(seam: DesktopSeamV2Double, block: (SelfOwnedDesktopProvisionerV2) -> T): T {
        val work = seam.openWork()
        try { return block(SelfOwnedDesktopProvisionerV2(seam.environment, seam.store, seam.keys, work)) }
        finally { seam.closeWork(work) }
    }

    /** The shared owner's original-preparation read path (neutral store -> V2 state -> V2 source). */
    private fun originalSource(seam: DesktopSeamV2Double): SelfOwnedOriginalSource.V2 {
        val bootstrap = assertNotNull(seam.store.originalBootstrap())
        val state = assertIs<SelfOwnedOriginalRegistryState.V2>(seam.store.readOriginalRegistryState(seam.keys))
        assertEquals(SelfOwnedOriginalFormat.V2, state.format)
        return assertIs<SelfOwnedOriginalSource.V2>(state.originalPreparationSource(bootstrap))
    }

    private fun issued(bootsBeforeIssue: Int = 0): Pair<DesktopSeamV2Double, ByteArray> {
        val seam = DesktopSeamV2Double()
        val view = owner(seam) { p ->
            p.completeNewCompanyAndHead(p.prepareNewCompanyAndHead(founding), installerSecret.toCharArray(), headSecret.toCharArray())
        }
        repeat(bootsBeforeIssue) { seam.nextBoot() }
        val grant = owner(seam) { p ->
            p.issueOriginalPreparation(p.prepareOriginalBranch("Main Branch", view.rootFingerprint), "installer.one",
                installerSecret.toCharArray()).preparation.copyWireBytes()
        }
        return seam to grant
    }

    @Test
    fun companyFoundingIssuanceReopenAndNextBootReadTheSameOriginalSource() {
        val (seam, grant) = issued()
        // Reopen: a new owner recovers exactly the retained grant, without credentials.
        val recovered = owner(seam) { p ->
            assertNotNull(p.inspectCompany()).also { assertNotNull(it.headAuthorityId) }
            p.recoverOriginalPreparation()
        }
        assertContentEquals(grant, recovered.preparation.copyWireBytes())
        val source = originalSource(seam)
        assertTrue(source.namesTarget(seam.environment))
        assertEquals("Main Branch", source.branchName)
        assertContentEquals(grant, source.value.preparation.copyWireBytes())
        // Next OS boot: the chain advances by exactly one verified step and the same source reads back.
        val before = seam.chain.last().ordinal
        seam.nextBoot()
        assertEquals(before + 1, seam.chain.last().ordinal)
        val afterBoot = owner(seam) { it.recoverOriginalPreparation() }
        assertContentEquals(grant, afterBoot.preparation.copyWireBytes())
        assertTrue(originalSource(seam).same(source))
    }

    @Test
    fun tamperedEnvelopeIsRefusedBeforeDecryption() {
        val (seam, _) = issued()
        val bytes = seam.envelopeBytes!!
        for (index in listOf(bytes.size - 1, bytes.size - 80, bytes.size / 2)) {
            seam.envelopeBytes = bytes.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason { owner(seam) { it.recoverOriginalPreparation() } })
            assertFailsWith<SelfOwnedFailureV1> { originalSource(seam) }
        }
        seam.envelopeBytes = bytes
        owner(seam) { it.recoverOriginalPreparation() }
    }

    @Test
    fun truncatedOrRolledBackBootChainIsRefused() {
        // Issued on boot ordinal 2, so the retained installer throttle boot names that boot.
        val (seam, _) = issued(bootsBeforeIssue = 2)
        assertEquals(2, seam.chain.last().ordinal)
        val live = seam.chain.toList()
        // Truncated chain (genesis missing) never verifies.
        seam.presentedChain = { live.drop(1) }
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason { owner(seam) { it.recoverOriginalPreparation() } })
        // A chain whose last ledger is not the current boot never verifies.
        seam.presentedChain = { live.dropLast(1) }
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason { owner(seam) { it.recoverOriginalPreparation() } })
        // A consistently rolled-back chain is internally valid, but retained boot facts exceed it.
        seam.presentedChain = { live.take(1) }; seam.presentedCurrent = { live.first() }
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason { owner(seam) { it.inspectCompany() } })
        seam.presentedChain = null; seam.presentedCurrent = null
        owner(seam) { it.recoverOriginalPreparation() }
    }

    @Test
    fun wrongPrincipalOrInstalledTargetIsRefused() {
        val (seam, _) = issued()
        seam.principal = SelfOwnedProcessPrincipal.WindowsSid(N.OWNER_SID)
        assertEquals(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM, reason { owner(seam) { it.recoverOriginalPreparation() } })
        seam.principal = SelfOwnedProcessPrincipal.MacUid(501)
        seam.installedTarget = N.binding(SelfOwnedMacServiceIdentityV2(N.macIdentity.signingIdentifier,
            N.macIdentity.teamIdentifier, "f".repeat(64)))
        assertFalse(originalSource(seam).namesTarget(seam.environment))
        val view = owner(seam) { assertNotNull(it.inspectCompany()) }
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN,
            reason { owner(seam) { it.prepareOriginalBranch("Main Branch", view.rootFingerprint) } })
        seam.installedTarget = N.binding(N.windowsIdentity)
        assertEquals(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM, reason { owner(seam) { it.recoverOriginalPreparation() } })
    }

    @Test
    fun androidV1BytesOnAV2PlatformAreRefusedNeverAdapted() {
        val (seam, _) = issued()
        val envelope = seam.envelopeBytes!!
        seam.envelopeBytes = SelfOwnedInstallationWireV1.encodeRegistry(A.registryState())
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason { owner(seam) { it.recoverOriginalPreparation() } })
        assertFailsWith<SelfOwnedFailureV1> { originalSource(seam) }
        seam.envelopeBytes = envelope
        val bootstrap = seam.bootstrapBytes!!
        seam.bootstrapBytes = SelfOwnedInstallationWireV1.encodeBootstrap(A.completeBootstrap().copy(enrollmentStarted = true))
        assertEquals(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN, reason { owner(seam) { it.recoverOriginalPreparation() } })
        seam.bootstrapBytes = bootstrap
        owner(seam) { it.recoverOriginalPreparation() }
    }
}
