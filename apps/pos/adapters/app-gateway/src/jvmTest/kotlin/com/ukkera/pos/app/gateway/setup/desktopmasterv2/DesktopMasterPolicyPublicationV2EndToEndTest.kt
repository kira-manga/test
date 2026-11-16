package com.ukkera.pos.app.gateway.setup.desktopmasterv2

import com.ukkera.pos.app.gateway.DesktopClientJournalStorages
import com.ukkera.pos.app.gateway.setup.DesktopLocalSetupExecution
import com.ukkera.pos.app.gateway.setup.DesktopMasterSetupNativeServices
import com.ukkera.pos.app.gateway.setup.DesktopMasterSetupPlatform
import com.ukkera.pos.app.gateway.setup.DesktopSetupStorages
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLifecycleIntentStore
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.app.gateway.setup.LocalSetupMasterOpenResult
import com.ukkera.pos.app.gateway.setup.LocalSetupNativeServices
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
import com.ukkera.pos.security.DesktopMasterClientJournalCustody
import com.ukkera.pos.security.DesktopSelfOwnedEnrollmentSource
import com.ukkera.pos.security.DesktopSelfOwnedInstallationOwner
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalCompanyAuthorityResult
import com.ukkera.pos.security.LocalCompanyPolicyProposal
import com.ukkera.pos.security.LocalCompanyPurchasingPolicies
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalPurchasingPublicationPhase
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.LocalTrainingCompositionSlot
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PurchasingAuthorityGrantV1
import com.ukkera.pos.security.PurchasingCatalogReferenceV1
import com.ukkera.pos.security.PurchasingLimitsV1
import com.ukkera.pos.security.PurchasingProductSelectionV1
import com.ukkera.pos.security.PurchasingPublicationReferenceV1
import com.ukkera.pos.security.PurchasingRuleActionV1
import com.ukkera.pos.security.PurchasingSelectionV1
import com.ukkera.pos.security.PurchasingSkuReferenceV1
import com.ukkera.pos.security.PurchasingSupplierReferenceV1
import com.ukkera.pos.security.PurchasingValidityV1
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeProfileV1
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.security.desktopmasterv2.LinuxTestOs
import com.ukkera.pos.security.readOriginalPurchasingPolicies
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as N
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Self-authored (NOT independent) Linux end-to-end check of head-approved company purchasing-policy
 * publication on the desktop (Windows/macOS) V2 Master. After the shared coordinator installs the
 * original branch (INSTALLATION_RETAINED) it uses the SAME security context the desktop Master was
 * opened with: prepare + head approval issues a publication (ISSUED), the publication is recovered
 * by its original action id, a wrong head password is refused, a second publication chains to the
 * first (revision 2, same policy, exact predecessor), and the Master's purchasing trust (the same
 * instance handed to the Master's commerce registry) accepts both issued canonical envelopes.
 * Synthetic data only; [LinuxTestOs] POSIX and software-key stand-ins replace Windows/macOS custody.
 * Expectations were fixed before execution. This proves no real Windows/macOS native custody and
 * no purchasing command flow consuming the policy.
 */
class DesktopMasterPolicyPublicationV2EndToEndTest {
    private val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    private val base: Path = Files.createTempDirectory("desktop-master-policy", ownerOnly)
    private val os = LinuxTestOs(base, N.binding(N.macIdentity))
    private val slot = LocalTrainingCompositionSlot.TRAINING_1
    private val profile = SelfOwnedNativeProfileV1.training(slot)
    private val installerSecret = "synthetic-installer-pass"
    private val headLogin = "head.one"
    private val headSecret = "synthetic-head-pass-2026"
    private val ownerPassword = "synthetic-owner-pass-2026"

    @AfterTest fun clean() { base.toFile().deleteRecursively() }

    /** Records the exact security context the coordinator opens the Master with; never authority. */
    private class CapturingServices(val actual: DesktopMasterSetupNativeServices) : LocalSetupNativeServices by actual {
        @Volatile var masterContext: SelfOwnedLocalSecurityContext? = null
        override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult {
            masterContext = context
            return actual.openMaster(context)
        }
    }

    private fun compose(): Pair<LocalSetupCoordinator, CapturingServices> {
        val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
        val services = CapturingServices(DesktopMasterSetupNativeServices(DesktopMasterSetupPlatform(
            openProvisioner = { originalOnly -> DesktopSelfOwnedEnrollmentSource.open(os, profile, existingOriginal = originalOnly) },
            openTarget = { preparation ->
                if (preparation) DesktopSelfOwnedInstallationOwner.prepare(os, profile) else DesktopSelfOwnedInstallationOwner.open(os, profile)
            },
            factory = factory,
            identityStore = { error("The LAN host is not exercised by this check") },
            clientJournals = { DesktopClientJournalStorages.masterService(DesktopMasterClientJournalCustody.of(os, profile)) },
        ), slot))
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

    /** Synthetic arrival-record grant over one synthetic supplier/SKU; no monetary effect, no rules. */
    private fun proposal(scope: BranchScope, predecessor: PurchasingPublicationReferenceV1?): LocalCompanyPolicyProposal {
        val catalog = PurchasingCatalogReferenceV1(DomainId.random(), Revision(1))
        val sku = PurchasingSkuReferenceV1(DomainId.random(), Revision(1), DomainId.random(), Revision(1), catalog)
        val selection = PurchasingSelectionV1(listOf(PurchasingSupplierReferenceV1(DomainId.random(), Revision(1))),
            PurchasingProductSelectionV1(listOf(sku), emptyList()))
        val grant = PurchasingAuthorityGrantV1.PolicyPublish(DomainId.random(), listOf(scope.branchId),
            PurchasingRuleActionV1.ARRIVAL_RECORD, selection, PurchasingLimitsV1.NoMonetaryEffect)
        return LocalCompanyPolicyProposal(predecessor,
            PurchasingValidityV1(UtcInstant.parseCanonical("2026-01-01T00:00:00Z"), null), listOf(grant), emptyList(), emptyList())
    }

    private fun <T> LocalCompanyAuthorityResult<T>.value(step: String): T = when (this) {
        is LocalCompanyAuthorityResult.Value -> value
        is LocalCompanyAuthorityResult.Unavailable -> fail("$step: unavailable $reason native=$nativeReason")
    }

    @Test
    fun desktopMasterPublishesHeadApprovedPurchasingPolicies(): Unit = runBlocking {
        val (setup, services) = compose()
        try {
            setup.chooseNewLocalCompany()
            setup.prepareNewLocalCompany(LocalSetupNewCompanyInput("Synthetic Company", "Synthetic Person", "installer.one",
                "Synthetic Head", headLogin, SelfOwnedKeyProtectionV1.OS_ISOLATED))
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

            val context = assertNotNull(services.masterContext, "the coordinator opened the desktop Master")
            val policies: LocalCompanyPurchasingPolicies = context.readOriginalPurchasingPolicies().value("read policies")
            val trust = policies.trust
            val scope = BranchScope(branch.organizationId, branch.companyId, branch.branchId)
            assertEquals(scope, trust.scope)
            assertTrue(policies.retained().value("empty ledger").isEmpty(), "no publication before the first")

            // 1. Prepare + head approval issues the first publication.
            val first = policies.prepare(proposal(scope, null)).value("prepare first")
            assertEquals(Revision(1), first.policy.revision)
            assertNull(first.policy.predecessor)
            val pending = policies.retained().value("pending ledger").single()
            assertEquals(LocalPurchasingPublicationPhase.PREPARED, pending.phase)
            val firstSecret = headSecret.toCharArray()
            val issued = policies.approve(first, headLogin, firstSecret).value("approve first")
            assertTrue(firstSecret.all { it == '\u0000' }, "the caller's head secret copy is cleared")
            assertEquals(first.originalActionId, issued.originalActionId)
            val firstLedger = policies.retained().value("issued ledger").single()
            assertEquals(LocalPurchasingPublicationPhase.ISSUED, firstLedger.phase)
            assertEquals(first.policy.publicationId, firstLedger.publicationId)
            assertEquals(first.policyBodyDigest, firstLedger.bodyDigest)

            // 2. Recovered by its original action id with identical canonical bytes.
            val firstBytes = issued.copyWireBytes()
            val recovered = policies.recover(first.originalActionId).value("recover first")
            assertContentEquals(firstBytes, recovered.copyWireBytes())
            assertIs<LocalCompanyAuthorityResult.Unavailable>(policies.recover(DomainId.random()), "unknown action id")

            // 3. The Master sees it: the SAME trust instance the Master's commerce registry holds.
            assertSame(trust, context.readOriginalPurchasingPolicies().value("reread policies").trust)
            assertTrue(trust.verify(scope, firstBytes), "Master trusts the issued first publication")

            // 4. A second publication chains to the first; a wrong head password is refused.
            val predecessor = PurchasingPublicationReferenceV1(first.policy.publicationId, first.policy.revision,
                first.policyBodyDigest)
            val second = policies.prepare(proposal(scope, predecessor)).value("prepare second")
            assertIs<LocalCompanyAuthorityResult.Unavailable>(
                policies.approve(second, headLogin, "wrong-synthetic-head-pass".toCharArray()), "wrong head password")
            assertFalse(policies.retained().value("after refusal").any {
                it.originalActionId == second.originalActionId && it.phase == LocalPurchasingPublicationPhase.ISSUED
            }, "a refused approval issues nothing")
            // The native clock now measures elapsed time rather than advancing on reads.
            // Respect the retained first-failure cooldown before the next credential attempt.
            delay(1_000L)
            val chained = policies.approve(second, headLogin, headSecret.toCharArray()).value("approve second")
            assertEquals(first.policy.policyId, second.policy.policyId)
            assertEquals(Revision(2), second.policy.revision)
            val chainedPredecessor = assertNotNull(chained.policy.body.predecessor)
            assertEquals(first.policy.publicationId, chainedPredecessor.publicationId)
            assertEquals(first.policy.revision, chainedPredecessor.revision)
            assertEquals(first.policyBodyDigest, chainedPredecessor.bodyDigest)
            val ledger = policies.retained().value("chained ledger")
            assertEquals(listOf(LocalPurchasingPublicationPhase.ISSUED, LocalPurchasingPublicationPhase.ISSUED), ledger.map { it.phase })
            val secondBytes = chained.copyWireBytes()
            assertTrue(trust.verify(scope, secondBytes), "Master trusts the chained publication")
            assertTrue(trust.verify(scope, firstBytes), "the earlier publication stays trusted for history")
            // A stale predecessor (the first again) is a conflict, not a fork.
            assertIs<LocalCompanyAuthorityResult.Unavailable>(policies.prepare(proposal(scope, predecessor)), "stale predecessor")
        } finally {
            withTimeoutOrNull(60_000L) { runCatching { setup.requestRetirement().await() } }
        }
    }
}
