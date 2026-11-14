package com.ukkera.pos.app.gateway.setup.desktopmasterv2

import com.ukkera.pos.app.gateway.setup.DesktopLocalSetupExecution
import com.ukkera.pos.app.gateway.setup.DesktopMasterSetupNativeServices
import com.ukkera.pos.app.gateway.setup.DesktopMasterSetupPlatform
import com.ukkera.pos.app.gateway.setup.DesktopSetupStorages
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.app.setup.LocalSetupBranchReview
import com.ukkera.pos.app.setup.LocalSetupFirstAccountInput
import com.ukkera.pos.app.setup.LocalSetupNewCompanyInput
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.database.DesktopSelfOwnedMasterDiagnostics
import com.ukkera.pos.database.DesktopSelfOwnedMasterNativeOwner
import com.ukkera.pos.database.DesktopSelfOwnedMasterServiceFactory
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.DesktopSelfOwnedEnrollmentSource
import com.ukkera.pos.security.DesktopSelfOwnedInstallationOwner
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountProvisioning
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
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedNativeProfileV1
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
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import com.ukkera.pos.app.gateway.setup.ipc.DesktopRemoteSetupController
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupIpcDocuments
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupIpcServer
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupUnixIpc
import com.ukkera.pos.app.setup.LocalSetupController
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Self-authored (NOT independent) Linux end-to-end check of the D5 setup console: the UI-side
 * [DesktopRemoteSetupController] drives the service-owned coordinator over a real owner-only Unix
 * socket, with secrets in the frame secret section only. Same ceremony as the in-process test: new company and head -> original branch -> V2 grant -> original
 * target -> first account -> head-approved installation -> retained receipt. Real desktop owner,
 * provisioner, Room and journal code over Linux POSIX and software-key stand-ins; synthetic data only.
 * Expectations were fixed before execution. This proves no Windows/macOS native custody.
 */
class DesktopMasterSetupIpcV2EndToEndTest {
    private val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val base: Path = Files.createTempDirectory("desktop-master-setup", ownerOnly)
    private val os = LinuxTestOs(base, N.binding(N.macIdentity))
    private val slot = LocalTrainingCompositionSlot.TRAINING_1
    private val profile = SelfOwnedNativeProfileV1.training(slot)
    private val installerSecret = "synthetic-installer-pass"
    private val headSecret = "synthetic-head-pass-2026"

    @AfterTest fun clean() { base.toFile().deleteRecursively() }

    private fun coordinator(): LocalSetupCoordinator {
        val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
        val services = DesktopMasterSetupNativeServices(DesktopMasterSetupPlatform(
            openProvisioner = { originalOnly -> DesktopSelfOwnedEnrollmentSource.open(os, profile, existingOriginal = originalOnly) },
            openTarget = { preparation ->
                if (preparation) DesktopSelfOwnedInstallationOwner.prepare(os, profile) else DesktopSelfOwnedInstallationOwner.open(os, profile)
            },
            factory = factory,
            identityStore = { error("The LAN host is not exercised by this check") },
        ), slot)
        val storages = DesktopSetupStorages(Files.createDirectory(base.resolve("setup"), ownerOnly))
        return LocalSetupCoordinator(
            services = services,
            employeeSetupIntents = LocalEmployeeSetupIntents(storages.employee),
            launchRecord = LocalMasterLaunchRecord(storages.launch),
            execution = DesktopLocalSetupExecution(),
            beforeRetirement = { _, _ -> },
            beforeClientHosting = {},
            lifecycleIntents = LocalMasterLifecycleIntentStore(storages.lifecycle),
            stopBindingIntents = LocalMasterLifecycleIntentStore(storages.stopBinding),
        )
    }

    private suspend fun LocalSetupController.settle(expected: LocalSetupPhase, step: String): LocalSetupView {
        // The remote projection lags the service: wait for the expected phase (or a refusal) itself.
        val settled = withTimeoutOrNull(180_000L) { state.first { it.work == null &&
            (it.phase == expected || it.phase == LocalSetupPhase.ORIGINAL_UNRESOLVED) } }
            ?: fail("$step did not settle: phase=${state.value.phase} work=${state.value.work}")
        if (settled.phase != expected) {
            fail("$step: expected $expected, was ${settled.phase} problem=${settled.problem} native=${settled.nativeProblem}")
        }
        return settled
    }

    private fun firstAccount(review: LocalSetupBranchReview): LocalAccountProvisioning {
        val scope = BranchScope(review.organizationId, review.companyId, review.branchId)
        val actor = review.firstActorId
        fun grant(action: SecurityAction, verb: PermissionVerb, targets: Set<com.ukkera.pos.types.DomainId>?) =
            PermissionGrant(Permission(action, verb), scope, GrantEffect.ALLOW, PermissionLimits(), OwnerAccess.OWN_ONLY,
                actor, targets, null)
        val grants = listOf(
            grant(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER, setOf(actor)),
            grant(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER, setOf(review.deviceId)),
            grant(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM, null))
        return LocalAccountProvisioning(actor, LocalLoginIdentifier("owner.one"),
            ActivatedLocalPolicy(scope, actor, Revision(0), grants, LocalSessionPolicy(2), ThrottlePolicy(), approvalPolicy = null),
            LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), setOf(review.deviceId))
    }

    @Test
    fun remoteConsoleInstallsDesktopMasterOriginalBranch(): Unit = runBlocking {
        val service = coordinator()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val server = DesktopSetupIpcServer(service, DesktopSetupIpcDocuments(), scope)
        val ipc = Files.createDirectory(base.resolve("ipc"), ownerOnly)
        server.serve(DesktopSetupUnixIpc.listen(ipc))
        val setup = DesktopRemoteSetupController.connect(DesktopSetupUnixIpc.connect(ipc), null, scope)
        assertEquals(service.compositionProfile?.let { it::class }, setup.compositionProfile?.let { it::class })
        withTimeoutOrNull(30_000L) { setup.state.first { !it.locked || it.phase == LocalSetupPhase.CHOOSE_ROUTE } }
        try {
            setup.chooseNewLocalCompany()
            assertNotNull(withTimeoutOrNull(30_000L) { setup.state.first { it.phase == LocalSetupPhase.NEW_COMPANY_DETAILS } })
            setup.prepareNewLocalCompany(LocalSetupNewCompanyInput("Synthetic Company", "Synthetic Person", "installer.one",
                "Synthetic Head", "head.one", SelfOwnedKeyProtectionV1.OS_ISOLATED))
            val company = assertNotNull(setup.settle(LocalSetupPhase.COMPANY_REVIEW, "prepare company").companyReview)
            setup.completeNewLocalCompany(company.reviewId, installerSecret.toCharArray(), headSecret.toCharArray())
            setup.settle(LocalSetupPhase.COMPANY_RETAINED, "complete company")
            setup.prepareOriginalBranch("Main Branch")
            val branch = assertNotNull(setup.settle(LocalSetupPhase.BRANCH_REVIEW, "prepare branch").branchReview)
            setup.issueOriginalBranch(branch.reviewId, installerSecret.toCharArray())
            setup.settle(LocalSetupPhase.ORIGINAL_PREPARATION_RETAINED, "issue V2 grant")
            setup.prepareOriginalNativeTarget()
            setup.settle(LocalSetupPhase.ORIGINAL_TARGET_RETAINED, "prepare original target")
            setup.openOriginalFirstAccount()
            setup.settle(LocalSetupPhase.FIRST_ACCOUNT_DETAILS, "open installation Master")
            setup.prepareFirstAccount(LocalSetupFirstAccountInput(firstAccount(branch), Revision(1)))
            val review = assertNotNull(setup.settle(LocalSetupPhase.FIRST_ACCOUNT_REVIEW, "review first account").definitionReview)
            setup.installFirstAccount(review.reviewId, headSecret.toCharArray(), "synthetic-owner-pass-2026".toCharArray())
            var installed = withTimeoutOrNull(180_000L) { setup.state.first { it.work == null && it.phase in setOf(
                LocalSetupPhase.INSTALLATION_RETAINED, LocalSetupPhase.INSTALLATION_ACK_PENDING, LocalSetupPhase.ORIGINAL_UNRESOLVED) } }
                ?: fail("install did not settle")
            if (installed.phase == LocalSetupPhase.INSTALLATION_ACK_PENDING) {
                setup.resumeOriginalInstallationAcknowledgement()
                installed = setup.settle(LocalSetupPhase.INSTALLATION_RETAINED, "resume acknowledgement")
            }
            assertEquals(LocalSetupPhase.INSTALLATION_RETAINED, installed.phase,
                "install: problem=${installed.problem} native=${installed.nativeProblem}")
            val receipt = assertNotNull(installed.receipt)
            assertEquals(branch.installationId, receipt.installationId)
            // The service-side view and the remote projection agree on the retained receipt.
            assertEquals(service.state.value.receipt, installed.receipt)
        } finally {
            setup.close(); server.close(); scope.cancel()
            withTimeoutOrNull(60_000L) { runCatching { service.requestRetirement().await() } }
        }
    }
}
