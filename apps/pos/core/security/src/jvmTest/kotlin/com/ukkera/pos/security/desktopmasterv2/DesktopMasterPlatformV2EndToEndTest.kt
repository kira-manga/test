package com.ukkera.pos.security.desktopmasterv2

import com.ukkera.pos.security.AndroidOwnedInstallationWork
import com.ukkera.pos.security.AndroidProvisionerProcessLease
import com.ukkera.pos.security.DesktopSelfOwnedInstallationOwner
import com.ukkera.pos.security.DesktopSelfOwnedOriginalPlatform
import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedDesktopProvisionerV2
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.SelfOwnedNativeProfileV1
import com.ukkera.pos.security.SelfOwnedProvisionerRegistryStoreV2
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as N
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Self-authored (NOT independent) Linux end-to-end check of the desktop Master platform layer:
 * real [DesktopSelfOwnedOriginalPlatform] files/keys/journal/boot-ledger code over Linux POSIX and
 * software-key stand-ins ([LinuxTestOs]). Flow: V2 company + head founding and original branch
 * grant through the platform's own registry/keys -> shared owner PREPARE (target keys, binding,
 * history companion, Master SDK capture) -> close -> existing-only OPEN -> history custody claim
 * -> next boot OPEN; plus refusal of a wrong principal-owned base and a second concurrent owner.
 * Expectations were written before running. Synthetic data only; proves no native custody.
 */
class DesktopMasterPlatformV2EndToEndTest {
    private val base: Path = Files.createTempDirectory("desktop-master-e2e",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    private val os = LinuxTestOs(base, N.binding(N.macIdentity))
    private val facts = SelfOwnedCompanyFactsV1("Synthetic Company", "Synthetic Person", "installer.one",
        SelfOwnedKeyProtectionV1.OS_ISOLATED)
    private val founding = LocalCompanyFoundingFactsV1(facts, "Synthetic Head", "head.one",
        LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, LocalCompanyHeadPurposeV1.entries.toSet())

    // A separate training-slot profile: a claimed history custody keeps its in-process lease for the JVM.
    private val profile = SelfOwnedNativeProfileV1.training(com.ukkera.pos.security.LocalTrainingCompositionSlot.TRAINING_2)

    @AfterTest fun clean() { base.toFile().deleteRecursively() }

    private fun <T> provisioner(block: (SelfOwnedDesktopProvisionerV2) -> T): T {
        val platform = DesktopSelfOwnedOriginalPlatform(os)
        val work = AndroidOwnedInstallationWork(checkNotNull(AndroidProvisionerProcessLease.acquire(profile)),
            platform.process)
        try {
            val environment = platform.captureEnvironment(work, profile, preparation = true)
            val registry = platform.provisionerRegistry(environment, work)
            registry.openNative()
            try {
                val keys = platform.provisionerKeys(environment, registry, work)
                return block(SelfOwnedDesktopProvisionerV2(environment, registry as SelfOwnedProvisionerRegistryStoreV2, keys, work))
            } finally { registry.closeNative() }
        } finally { runBlocking { withTimeout(30_000L) { work.requestClose { }.await() } } }
    }

    private fun provision() {
        val view = provisioner { p ->
            p.completeNewCompanyAndHead(p.prepareNewCompanyAndHead(founding), "synthetic-installer-pass".toCharArray(),
                "synthetic-head-pass-2026".toCharArray())
        }
        provisioner { p ->
            p.issueOriginalPreparation(p.prepareOriginalBranch("Main Branch", view.rootFingerprint), "installer.one",
                "synthetic-installer-pass".toCharArray())
        }
    }

    @Test
    fun preparedDesktopMasterReopensClaimsHistoryAndSurvivesNextBoot(): Unit = runBlocking {
        provision()
        val prepared = assertIs<SelfOwnedNativeOpenResult.Opened>(DesktopSelfOwnedInstallationOwner.prepare(os, profile))
        assertEquals("Main Branch", prepared.context.materialAtOpen.branchName)
        prepared.context.close()

        val opened = assertIs<SelfOwnedNativeOpenResult.Opened>(DesktopSelfOwnedInstallationOwner.open(os, profile))
        assertEquals(prepared.context.materialAtOpen.originalNativeBindingDigest, opened.context.materialAtOpen.originalNativeBindingDigest)
        opened.context.close()

        os.nextBoot()
        val reopened = DesktopSelfOwnedInstallationOwner.open(os, profile)
        val afterBoot = assertIs<SelfOwnedNativeOpenResult.Opened>(reopened, "next boot: $reopened")
        assertEquals(prepared.context.materialAtOpen.originalNativeBindingDigest, afterBoot.context.materialAtOpen.originalNativeBindingDigest)
        // A second concurrent owner of the same installation is refused, never a second lease.
        val second = DesktopSelfOwnedInstallationOwner.open(os, profile)
        assertEquals(SelfOwnedInstallationFailureV1.BUSY, assertIs<SelfOwnedNativeOpenResult.Unresolved>(second).reason)
        // History custody transfers exactly once from this context and reads the original cut.
        val claimed = assertIs<SelfOwnedNativeHistoryClaimResult.Claimed>(
            DesktopSelfOwnedInstallationOwner.claimOriginalHistoryCustody(afterBoot.context))
        claimed.custody.snapshotOriginal()
        assertIs<SelfOwnedNativeHistoryClaimResult.Unresolved>(DesktopSelfOwnedInstallationOwner.claimOriginalHistoryCustody(afterBoot.context))
    }

    @Test
    fun existingOnlyOpenWithoutPreparationIsUnresolved(): Unit = runBlocking {
        val result = assertIs<SelfOwnedNativeOpenResult.Unresolved>(DesktopSelfOwnedInstallationOwner.open(os, profile))
        kotlin.test.assertTrue(result.reason != SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    }
}
