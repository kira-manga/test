package com.ukkera.pos.app.gateway.setup.desktopmasterv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.DesktopClientJournalStorages
import com.ukkera.pos.app.gateway.setup.ipc.*
import com.ukkera.pos.security.DesktopMasterClientJournalCustody
import com.ukkera.pos.security.LocalAdministrationReadResult
import com.ukkera.pos.security.LocalAdministrationQuery
import com.ukkera.pos.security.LocalAdministrationDirectory
import com.ukkera.pos.security.LocalAdministrationCommand
import com.ukkera.pos.security.LocalAdministrationAction
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.types.DomainId
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout

/**
 * Self-authored, NOT independent. Frozen expectations precede execution. Genuine production
 * provisioner, original installed Room Master, service cohost journals and native signing owners
 * communicate with the actual desktop UI gateway over an owner-only Unix socket. The synthetic
 * LIVE composition is isolated in a new test root; Linux software OS seams do not qualify macOS,
 * Windows native custody, rendered/installed UI, real business operation or owner acceptance.
 */
class DesktopMasterEmployeeConsoleNativeTest {
    private val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val base: Path = Files.createTempDirectory(Path.of(System.getProperty("user.home")), ".desktop-master-console-", ownerOnly)
    private val os = LinuxTestOs(base, N.binding(N.macIdentity))
    private val profile = SelfOwnedNativeProfileV1.LIVE
    private val installerSecret = "synthetic-installer-pass"
    private val headSecret = "synthetic-head-pass-2026"

    // Preserve the synthetic root after execution; the private build evidence records its path.

    private lateinit var services: DesktopMasterSetupNativeServices
    private fun coordinator(): LocalSetupCoordinator {
        val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
        services = DesktopMasterSetupNativeServices(DesktopMasterSetupPlatform(
            openProvisioner = { originalOnly -> DesktopSelfOwnedEnrollmentSource.open(os, profile, existingOriginal = originalOnly) },
            openTarget = { preparation ->
                if (preparation) DesktopSelfOwnedInstallationOwner.prepare(os, profile) else DesktopSelfOwnedInstallationOwner.open(os, profile)
            },
            factory = factory,
            identityStore = { error("The LAN host is not exercised by this check") },
            clientJournals = { DesktopClientJournalStorages.masterService(DesktopMasterClientJournalCustody.of(os, profile)) },
        ))
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

    private suspend fun LocalSetupCoordinator.settle(expected: LocalSetupPhase, step: String): LocalSetupView {
        val settled = withTimeoutOrNull(180_000L) { state.first { it.work == null } }
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
            grant(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM, null),
            grant(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER, null),
            grant(SecurityAction.SETUP_RESULT_READ, PermissionVerb.PERFORM, null),
            grant(SecurityAction.SETUP_CHECKPOINTS_READ, PermissionVerb.PERFORM, null),
            grant(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER, setOf(actor)),
            grant(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER, setOf(review.deviceId)),
            grant(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM, null))
        return LocalAccountProvisioning(actor, LocalLoginIdentifier("owner.one"),
            ActivatedLocalPolicy(scope, actor, Revision(0), grants, LocalSessionPolicy(2), ThrottlePolicy(), approvalPolicy = null),
            LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), setOf(review.deviceId))
    }

    @Test
    fun realServiceEmployeePortsCrossTheAuthenticatedConsoleWithoutExportingNativeOwners(): Unit = runBlocking {
        val setup = coordinator()
        try {
            setup.chooseNewLocalCompany()
            assertEquals(LocalSetupPhase.NEW_COMPANY_DETAILS, setup.state.value.phase)
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
            var installed = withTimeoutOrNull(180_000L) { setup.state.first { it.work == null } } ?: fail("install did not settle")
            if (installed.phase == LocalSetupPhase.INSTALLATION_ACK_PENDING) {
                setup.resumeOriginalInstallationAcknowledgement()
                installed = setup.settle(LocalSetupPhase.INSTALLATION_RETAINED, "resume acknowledgement")
            }
            assertEquals(LocalSetupPhase.INSTALLATION_RETAINED, installed.phase,
                "install: problem=${installed.problem} native=${installed.nativeProblem}")
            val receipt = assertNotNull(installed.receipt)
            assertEquals(branch.installationId, receipt.installationId)
            setup.enrollOriginalLocalClient()
            assertEquals(true, setup.settle(LocalSetupPhase.INSTALLATION_RETAINED, "connect native cohost").localClientConnected)
            println("Self-authored desktop employee native root: $base")

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val documents = DesktopSetupIpcDocuments()
            val server = DesktopSetupIpcServer(setup, documents, scope, services.localClient)
            val socket = Files.createDirectory(base.resolve("console"), ownerOnly)
            val listener = DesktopSetupUnixIpc.listen(socket)
            server.serve(listener)
            val remote = DesktopRemoteSetupController.connect(DesktopSetupUnixIpc.connect(socket), null, scope)
            try {
                assertTrue(remote.employeeConsoleSupported)
                val password = "synthetic-owner-pass-2026".toCharArray()
                val session = assertIs<SignInResult.Authenticated<BranchUiSession>>(
                    remote.employeeGateway.signIn("owner.one", password)).session
                assertTrue(password.all { it == '\u0000' })
                assertTrue(session.isCurrent())
                val accounts = assertIs<LocalAdministrationReadResult.Available>(session.administration.read(
                    LocalAdministrationQuery(LocalAdministrationDirectory.ACCOUNTS, targetId = branch.firstActorId))).view
                assertEquals(branch.firstActorId, assertNotNull(accounts.accountDetail).account.actorId)
                val devices = assertIs<LocalAdministrationReadResult.Available>(session.administration.read(
                    LocalAdministrationQuery(LocalAdministrationDirectory.DEVICES, targetId = branch.deviceId))).view
                assertEquals(branch.deviceId, devices.devices.single().deviceId)
                assertTrue(devices.devices.single().admitted)

                assertIs<LocalSetupReadResult.Available<*>>(session.setupClosure.readResult())
                assertIs<LocalSetupReadResult.Available<*>>(session.setupClosure.readCheckpoints())

                val draft = LocalCompanySkuDraft(DomainId.random(), DomainId.random(), DomainId.random(), DomainId.random(),
                    DomainId.random(), DomainId.random(), "Synthetic Item", "صنف اختباري", "SYNTHETIC-1", emptyList(),
                    "10.00", "STANDARD", "0", LocalCompanyPriceTaxInclusion.INCLUDED, canPurchase = true)
                val prepared = assertIs<LocalCompanyCatalogReviewResult.Review>(session.companyCatalog.prepare(draft)).review
                assertEquals(draft.skuId, prepared.preview.skuId)
                assertFailsWith<IllegalStateException> { prepared.action }
                val wrong = "incorrect-head-password".toCharArray()
                assertIs<LocalCompanyCatalogApprovalResult.Unavailable>(session.companyCatalog.approve(prepared, "head.one", wrong))
                assertTrue(wrong.all { it == '\u0000' })
                val head = headSecret.toCharArray()
                val completed = assertIs<LocalCompanyCatalogApprovalResult.Completed>(
                    session.companyCatalog.approve(prepared, "head.one", head)).publication
                assertTrue(head.all { it == '\u0000' })
                assertEquals(prepared.displayAction.originalActionId, completed.originalActionId)
                val activation = assertIs<LocalStageResult.Staged>(session.companyCatalog.stageActivation(completed.originalActionId))
                assertEquals(LocalAction.CATALOG_ACTIVATE, activation.operation.action)
                val activated = assertIs<LocalOperationResult.Recorded>(session.workflows.submit(activation.operation.operationId))
                assertEquals(LocalRecordedDecision.APPLIED, activated.decision)
                assertIs<LocalAcknowledgementResult.Acknowledged>(session.workflows.acknowledgeRecorded(activation.operation.operationId))
                assertIs<LocalReadResult.Available>(session.workflows.read(LocalWorkflowQuery(LocalWorkflow.CATALOG)))

                session.close()
                assertFalse(session.isCurrent())
                assertEquals(LocalSessionEndResult.REVOKED, session.logout(), "revocation survives synchronous UI close")
                assertTrue(services.localClient.configured, "closing employee UI must not retire Master/cohost")

                // The UI projection itself cannot enter the native approval adapter.
                val native = assertIs<SignInResult.Authenticated<BranchUiSession>>(
                    services.localClient.signIn("owner.one", "synthetic-owner-pass-2026".toCharArray())).session
                try {
                    val denied = headSecret.toCharArray()
                    assertIs<LocalCompanyCatalogApprovalResult.Unavailable>(native.companyCatalog.approve(prepared, "head.one", denied))
                    assertTrue(denied.all { it == '\u0000' })
                } finally { native.logout() }

                val next = assertIs<SignInResult.Authenticated<BranchUiSession>>(
                    remote.employeeGateway.signIn("owner.one", "synthetic-owner-pass-2026".toCharArray())).session
                val staleSecret = headSecret.toCharArray()
                assertIs<LocalCompanyCatalogApprovalResult.Unavailable>(next.companyCatalog.approve(prepared, "head.one", staleSecret))
                assertTrue(staleSecret.all { it == '\u0000' })
                assertTrue(next.isCurrent(), "an unavailable old review cannot revoke the current employee session")
                val currentAccount = assertIs<LocalAdministrationReadResult.Available>(next.administration.read(
                    LocalAdministrationQuery(LocalAdministrationDirectory.ACCOUNTS, targetId = branch.firstActorId))).view.accountDetail!!
                val ownSession = currentAccount.retainedSessions.single { it.status == SessionStatus.ACTIVE }
                val revoke = LocalAdministrationCommand(DomainId.random(), LocalAdministrationAction.REVOKE_SESSION,
                    branch.firstActorId, currentAccount.account.accountRevision, sessionId = ownSession.sessionId)
                assertIs<LocalAdministrationResult.Known>(next.administration.prepare(revoke))
                assertIs<LocalAdministrationResult.Known>(next.administration.submit(revoke.requestId))
                val attempted = next.attachmentKey
                assertEquals(attempted, assertIs<LocalSessionCheckResult.Unavailable>(next.current()).attachmentKey)
                assertIs<LocalCompanyCatalogStatusResult.Unavailable>(next.companyCatalog.status())
                assertTrue(services.localClient.configured, "revocation must not stop Master")
                remote.close()
                assertFalse(next.isCurrent())
            } finally { remote.close(); server.close(); scope.cancel() }
            withTimeout(60_000L) { setup.requestRetirement().await() }
            assertEquals(LocalSetupPhase.CLOSED, setup.state.value.phase, "native close must be confirmed")
            assertFalse(services.localClient.configured)

        } finally {
            withTimeoutOrNull(60_000L) { runCatching { setup.requestRetirement().await() } }
        }
    }
}
