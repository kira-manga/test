package com.ukkera.pos.database.desktopmasterv2

import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.DesktopSelfOwnedMasterDiagnostics
import com.ukkera.pos.database.DesktopSelfOwnedMasterNativeOwner
import com.ukkera.pos.database.DesktopSelfOwnedMasterServiceFactory
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AndroidOwnedInstallationWork
import com.ukkera.pos.security.AndroidProvisionerProcessLease
import com.ukkera.pos.security.DesktopSelfOwnedInstallationOwner
import com.ukkera.pos.security.DesktopSelfOwnedOriginalPlatform
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedDesktopProvisionerV2
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.SelfOwnedNativeProfileV1
import com.ukkera.pos.security.SelfOwnedProvisionerRegistryStoreV2
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.desktopmasterv2.LinuxTestOs
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as N
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Self-authored (NOT independent) Linux end-to-end check of the desktop Master ORIGINAL INSTALLATION:
 * V2 company/head founding and branch grant -> shared owner PREPARE -> [DesktopSelfOwnedMasterServiceFactory]
 * installation-only Master -> D7 definition -> head-approved installation through the V2 company
 * container issuer (claim, INSTALLATION head signature, target signature, Room genesis, ROOT ACK) ->
 * Installed receipt, re-observed after reopening. Real platform files/keys/journal code over Linux POSIX
 * and software-key stand-ins; synthetic data only. Expectations were written before running; this
 * proves no Windows/macOS native custody.
 */
class DesktopMasterInstallationV2EndToEndTest {
    private val base: Path = Files.createTempDirectory("desktop-master-install",
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    private val os = LinuxTestOs(base, N.binding(N.macIdentity))
    private val facts = SelfOwnedCompanyFactsV1("Synthetic Company", "Synthetic Person", "installer.one",
        SelfOwnedKeyProtectionV1.OS_ISOLATED)
    private val founding = LocalCompanyFoundingFactsV1(facts, "Synthetic Head", "head.one",
        LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE, LocalCompanyHeadPurposeV1.entries.toSet())
    private val profile = SelfOwnedNativeProfileV1.training(LocalTrainingCompositionSlot.TRAINING_1)
    private val headSecret = "synthetic-head-pass-2026"

    @AfterTest fun clean() { base.toFile().deleteRecursively() }

    private fun <T> provisioner(block: (SelfOwnedDesktopProvisionerV2) -> T): T {
        val platform = DesktopSelfOwnedOriginalPlatform(os)
        val work = AndroidOwnedInstallationWork(checkNotNull(AndroidProvisionerProcessLease.acquire(profile)), platform.process)
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

    private fun firstAccount(c: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        val actor = c.firstActorId
        fun grant(action: SecurityAction, verb: PermissionVerb, targets: Set<com.ukkera.pos.types.DomainId>?) =
            PermissionGrant(Permission(action, verb), c.scope, GrantEffect.ALLOW, PermissionLimits(), OwnerAccess.OWN_ONLY,
                actor, targets, null)
        val grants = listOf(
            grant(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER, setOf(actor)),
            grant(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER, setOf(c.deviceId)),
            grant(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM, null))
        return LocalAccountProvisioning(actor, LocalLoginIdentifier("owner.one"),
            ActivatedLocalPolicy(c.scope, actor, Revision(0), grants, LocalSessionPolicy(2), ThrottlePolicy(), approvalPolicy = null),
            LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), setOf(c.deviceId))
    }

    @Test
    fun desktopMasterInstallsOriginalBranchThroughV2CompanyContainer(): Unit = runBlocking {
        val view = provisioner { p ->
            p.completeNewCompanyAndHead(p.prepareNewCompanyAndHead(founding), "synthetic-installer-pass".toCharArray(),
                headSecret.toCharArray())
        }
        provisioner { p ->
            p.issueOriginalPreparation(p.prepareOriginalBranch("Main Branch", view.rootFingerprint), "installer.one",
                "synthetic-installer-pass".toCharArray())
        }
        val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
        val prepared = assertIs<SelfOwnedNativeOpenResult.Opened>(DesktopSelfOwnedInstallationOwner.prepare(os, profile))
        val coordinates = prepared.context.materialAtOpen.coordinates
        prepared.context.close()
        val existing = assertIs<SelfOwnedNativeOpenResult.Opened>(DesktopSelfOwnedInstallationOwner.open(os, profile))
        val opened = factory.forOriginalInstallation(existing.context)
        val master = assertIs<AndroidSelfOwnedMasterOpenResult.Opened>(opened, "installation Master: $opened").service
        try {
            assertEquals(LocalMasterLifecycleMode.ALLOCATION_ONLY, master.mode.value)
            val reviewed = master.prepareOriginalDefinition(firstAccount(coordinates), Revision(1))
            var outcome = master.installOriginal(reviewed.definition, "head.one", headSecret.toCharArray(),
                "synthetic-owner-pass-2026".toCharArray())
            if (outcome == LocalMasterInstallationOutcome.AcknowledgementPending) outcome = master.resumeOriginalAcknowledgement()
            val installed = assertIs<LocalMasterInstallationOutcome.Installed>(outcome, "installation: $outcome")
            assertEquals(reviewed.definition.originalRequestId, installed.receipt.core.originalRequestId)
            assertEquals(reviewed.definitionDigest, installed.receipt.core.definitionDigest)
            val setup = master.readOriginalSetup()
            assertEquals(installed.receipt.digest, assertNotNull(setup.receipt).digest)
            assertEquals("Synthetic Company", setup.company.companyName)
        } finally { master.quiesceAndClose() }
    }
}
