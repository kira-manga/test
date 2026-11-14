package com.ukkera.pos.app.gateway.setup.desktopmasterv2

import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.gateway.DesktopClientJournalStorages
import com.ukkera.pos.app.gateway.setup.DesktopLocalSetupExecution
import com.ukkera.pos.app.gateway.setup.DesktopMasterSetupNativeServices
import com.ukkera.pos.app.gateway.setup.DesktopMasterSetupPlatform
import com.ukkera.pos.app.gateway.setup.DesktopSetupStorages
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.app.setup.LocalSetupBranchReview
import com.ukkera.pos.app.setup.LocalSetupEmployeeEnrollmentStage
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyOutcome
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyStage
import com.ukkera.pos.app.setup.LocalSetupFirstAccountInput
import com.ukkera.pos.app.setup.LocalSetupNewCompanyInput
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.database.DesktopSelfOwnedMasterDiagnostics
import com.ukkera.pos.database.DesktopSelfOwnedMasterNativeOwner
import com.ukkera.pos.database.DesktopSelfOwnedMasterServiceFactory
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.DesktopMasterClientJournalCustody
import com.ukkera.pos.security.DesktopSelfOwnedEnrollmentSource
import com.ukkera.pos.security.DesktopSelfOwnedInstallationOwner
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAdministrationDirectory
import com.ukkera.pos.security.LocalAdministrationQuery
import com.ukkera.pos.security.LocalAdministrationReadResult
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
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Self-authored (NOT independent) Linux end-to-end check of the desktop Master SERVICE same-device
 * original (cohost) Client. After the shared coordinator installs the original branch it drives,
 * through the SAME coordinator: first local client enrollment -> employee enrollment ceremony
 * (open/begin/prepare/head approval/account/intent journal/finish) -> head-approved employee policy
 * activation -> employee password sign-in through the published client gateway -> a permission the
 * employee lacks is refused by the Master. Journals use the service's own custody from the SAME
 * [LinuxTestOs] the Master uses (POSIX and software-key stand-ins), never the UI-process stores.
 * Synthetic data only. Expectations were fixed before execution. This proves no Windows/macOS
 * native custody and no physical/retail recording (explicitly unavailable on desktop).
 */
class DesktopMasterCohostCeremoniesV2EndToEndTest {
    private val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val base: Path = Files.createTempDirectory("desktop-master-cohost", ownerOnly)
    private val os = LinuxTestOs(base, N.binding(N.macIdentity))
    private val slot = LocalTrainingCompositionSlot.TRAINING_1
    private val profile = SelfOwnedNativeProfileV1.training(slot)
    private val installerSecret = "synthetic-installer-pass"
    private val headSecret = "synthetic-head-pass-2026"
    private val ownerPassword = "synthetic-owner-pass-2026"
    private val employeeLogin = "employee.two"
    private val employeePassword = "synthetic-employee-pass-2026"

    @AfterTest fun clean() { base.toFile().deleteRecursively() }

    private fun compose(): Pair<LocalSetupCoordinator, DesktopMasterSetupNativeServices> {
        val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
        val services = DesktopMasterSetupNativeServices(DesktopMasterSetupPlatform(
            openProvisioner = { originalOnly -> DesktopSelfOwnedEnrollmentSource.open(os, profile, existingOriginal = originalOnly) },
            openTarget = { preparation ->
                if (preparation) DesktopSelfOwnedInstallationOwner.prepare(os, profile) else DesktopSelfOwnedInstallationOwner.open(os, profile)
            },
            factory = factory,
            identityStore = { error("The LAN host is not exercised by this check") },
            clientJournals = { DesktopClientJournalStorages.masterService(DesktopMasterClientJournalCustody.of(os, profile)) },
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
        ) to services
    }

    private suspend fun LocalSetupCoordinator.settle(expected: LocalSetupPhase, step: String): LocalSetupView {
        val settled = withTimeoutOrNull(180_000L) { state.first { it.work == null } }
            ?: fail("$step did not settle: phase=${state.value.phase} work=${state.value.work}")
        if (settled.phase != expected) {
            fail("$step: expected $expected, was ${settled.phase} problem=${settled.problem} native=${settled.nativeProblem}")
        }
        return settled
    }

    private fun grant(scope: BranchScope, actor: DomainId, action: SecurityAction, verb: PermissionVerb,
        targets: Set<DomainId>? = null) =
        PermissionGrant(Permission(action, verb), scope, GrantEffect.ALLOW, PermissionLimits(), OwnerAccess.OWN_ONLY,
            actor, targets, null)

    private fun firstAccount(review: LocalSetupBranchReview): LocalAccountProvisioning {
        val scope = BranchScope(review.organizationId, review.companyId, review.branchId)
        val actor = review.firstActorId
        val grants = listOf(
            grant(scope, actor, SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER, setOf(actor)),
            grant(scope, actor, SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER, setOf(review.deviceId)),
            grant(scope, actor, SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM))
        return LocalAccountProvisioning(actor, LocalLoginIdentifier("owner.one"),
            ActivatedLocalPolicy(scope, actor, Revision(0), grants, LocalSessionPolicy(2), ThrottlePolicy(), approvalPolicy = null),
            LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), setOf(review.deviceId))
    }

    /** Second synthetic employee: no grants at revision zero. */
    private fun employeeAccount(actor: DomainId, scope: BranchScope, deviceId: DomainId): LocalAccountProvisioning =
        LocalAccountProvisioning(actor, LocalLoginIdentifier(employeeLogin),
            ActivatedLocalPolicy(scope, actor, Revision(0), emptyList(), LocalSessionPolicy(2), ThrottlePolicy(), approvalPolicy = null),
            LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), setOf(deviceId))

    /** Next revision adds one unrelated grant; it never includes local account administration. */
    private fun employeePolicy(actor: DomainId, scope: BranchScope): ActivatedLocalPolicy =
        ActivatedLocalPolicy(scope, actor, Revision(1),
            listOf(grant(scope, actor, SecurityAction.SETUP_RESULT_READ, PermissionVerb.PERFORM)),
            LocalSessionPolicy(2), ThrottlePolicy(), approvalPolicy = null)

    @Test
    fun sharedCoordinatorDrivesDesktopMasterCohostCeremonies(): Unit = runBlocking {
        val (setup, services) = compose()
        try {
            // Original installation (same as the setup end-to-end check).
            setup.chooseNewLocalCompany()
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
            setup.installFirstAccount(review.reviewId, headSecret.toCharArray(), ownerPassword.toCharArray())
            var installed = withTimeoutOrNull(180_000L) { setup.state.first { it.work == null } } ?: fail("install did not settle")
            if (installed.phase == LocalSetupPhase.INSTALLATION_ACK_PENDING) {
                setup.resumeOriginalInstallationAcknowledgement()
                installed = setup.settle(LocalSetupPhase.INSTALLATION_RETAINED, "resume acknowledgement")
            }
            assertEquals(LocalSetupPhase.INSTALLATION_RETAINED, installed.phase,
                "install: problem=${installed.problem} native=${installed.nativeProblem}")

            // 1. Same-device original Client over the service's own journal custody.
            setup.enrollOriginalLocalClient()
            val connected = setup.settle(LocalSetupPhase.INSTALLATION_RETAINED, "enroll original local client")
            assertTrue(connected.localClientConnected, "client: problem=${connected.problem} native=${connected.nativeProblem}")
            assertTrue(services.localClient.configured, "the connected client gateway is published")

            // 2. Employee enrollment ceremony for a second synthetic employee.
            setup.openEmployeeEnrollment()
            val opened = setup.settle(LocalSetupPhase.EMPLOYEE_ENROLLMENT, "open employee enrollment")
            assertEquals(true, opened.employeeEnrollments?.newDefinitionAvailable, "no prior enrollment blocks a new one")
            setup.beginEmployeeEnrollment()
            val definition = assertNotNull(setup.settle(LocalSetupPhase.EMPLOYEE_ENROLLMENT, "begin employee enrollment")
                .employeeEnrollment)
            assertEquals(LocalSetupEmployeeEnrollmentStage.DEFINITION, definition.stage)
            val employee = definition.actorId
            setup.prepareEmployeeEnrollment(employeeAccount(employee, definition.scope, definition.admittedDeviceIds.single()))
            val reviewed = assertNotNull(setup.settle(LocalSetupPhase.EMPLOYEE_ENROLLMENT, "prepare employee enrollment")
                .employeeEnrollment)
            assertEquals(LocalSetupEmployeeEnrollmentStage.REVIEW, reviewed.stage, "prepare: ${setup.state.value.problem}")
            setup.approveEmployeeEnrollment(reviewed.reviewId, headSecret.toCharArray())
            val approved = assertNotNull(setup.settle(LocalSetupPhase.EMPLOYEE_ENROLLMENT, "approve employee enrollment")
                .employeeEnrollment)
            assertEquals(LocalSetupEmployeeEnrollmentStage.APPROVED, approved.stage, "approve: ${setup.state.value.problem}")
            setup.enrollApprovedEmployee(approved.reviewId, employeePassword.toCharArray())
            val created = assertNotNull(setup.settle(LocalSetupPhase.EMPLOYEE_ENROLLMENT, "enroll employee account")
                .employeeEnrollment)
            assertEquals(LocalSetupEmployeeEnrollmentStage.ACCOUNT_CREATED, created.stage, "account: ${setup.state.value.problem}")
            setup.enrollEmployeeIntentJournal(created.reviewId)
            val journal = assertNotNull(setup.settle(LocalSetupPhase.EMPLOYEE_ENROLLMENT, "enroll employee intent journal")
                .employeeEnrollment)
            assertEquals(LocalSetupEmployeeEnrollmentStage.JOURNAL_OPENED, journal.stage, "journal: ${setup.state.value.problem}")
            setup.finishEmployeeEnrollment(journal.reviewId)
            setup.settle(LocalSetupPhase.INSTALLATION_RETAINED, "finish employee enrollment")

            // 3. Head-approved policy change for that employee.
            setup.openEmployeePolicies()
            setup.settle(LocalSetupPhase.EMPLOYEE_POLICY, "open employee policies")
            setup.beginEmployeePolicy()
            val policyDefinition = assertNotNull(setup.settle(LocalSetupPhase.EMPLOYEE_POLICY, "begin employee policy")
                .employeePolicy)
            assertEquals(LocalSetupEmployeePolicyStage.DEFINITION, policyDefinition.stage)
            val nextPolicy = employeePolicy(employee, definition.scope)
            setup.prepareEmployeePolicy(policyDefinition.reviewId, nextPolicy)
            val policyReview = assertNotNull(setup.settle(LocalSetupPhase.EMPLOYEE_POLICY, "prepare employee policy")
                .employeePolicy)
            assertEquals(LocalSetupEmployeePolicyStage.REVIEW, policyReview.stage, "policy prepare: ${setup.state.value.problem}")
            setup.approveEmployeePolicy(policyReview.reviewId, headSecret.toCharArray())
            val policyApproved = assertNotNull(setup.settle(LocalSetupPhase.EMPLOYEE_POLICY, "approve employee policy")
                .employeePolicy)
            assertEquals(LocalSetupEmployeePolicyStage.APPROVED, policyApproved.stage, "policy approve: ${setup.state.value.problem}")
            setup.activateApprovedEmployeePolicy(policyApproved.reviewId)
            val applied = assertNotNull(setup.settle(LocalSetupPhase.EMPLOYEE_POLICY, "activate employee policy")
                .employeePolicy)
            assertEquals(LocalSetupEmployeePolicyStage.APPLIED, applied.stage, "policy activate: ${setup.state.value.problem}")
            assertEquals(LocalSetupEmployeePolicyOutcome.APPLIED, applied.outcome)
            assertNotNull(applied.eventId)

            // 4. The employee authenticates through the published client gateway with its password.
            val signedIn = services.localClient.signIn(employeeLogin, employeePassword.toCharArray())
            val session = assertIs<SignInResult.Authenticated<*>>(signedIn, "employee sign-in: $signedIn").session
                as com.ukkera.pos.app.BranchUiSession
            try {
                assertEquals(employee, session.display.actorId)
                // 5. A permission it lacks (local account administration) is refused by the Master.
                val read = session.administration.read(LocalAdministrationQuery(LocalAdministrationDirectory.ACCOUNTS))
                assertEquals(LocalAdministrationReadResult.Denied, read, "administration read without the grant")
            } finally { session.close() }
            // A wrong password is never accepted.
            assertIs<SignInResult.Rejected>(services.localClient.signIn(employeeLogin, "wrong-synthetic-pass".toCharArray()))
        } finally {
            withTimeoutOrNull(60_000L) { runCatching { setup.requestRetirement().await() } }
        }
    }
}
