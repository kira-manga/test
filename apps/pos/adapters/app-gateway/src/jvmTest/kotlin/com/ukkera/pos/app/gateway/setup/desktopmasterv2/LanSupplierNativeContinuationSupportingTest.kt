package com.ukkera.pos.app.gateway.setup.desktopmasterv2

import com.ukkera.pos.app.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Self-authored, NOT independent. Supporting native composition check for existing frozen
 * LAN-D04/D06/D08 and LAN-R04 (qa/pos/designs/lan-master-client-v1/cases.json), plus the
 * original payment UNKNOWN/linked-resolution contract. Uses actual desktop setup, protected
 * authentication, Room, original native journals and application gateways with synthetic data.
 * Linux software-OS support is disclosed; this is same-device support, not TCP, whole LAN,
 * Windows/macOS/Android/iOS runtime acceptance or execution of the complete frozen cases.
 * Failed synthetic originals remain available locally for diagnosis. */
class LanSupplierNativeContinuationSupportingTest {
    private val ownerOnly = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
    // The real physical adapter refuses shared writable ancestors such as the system temp root.
    private val base: Path = Files.createTempDirectory(Path.of(System.getProperty("user.home")),
        ".desktop-master-cohost-", ownerOnly)
    private val os = LinuxTestOs(base, N.binding(N.macIdentity)) { System.err.println("Desktop cohost fixture $it") }
    private val slot = LocalTrainingCompositionSlot.TRAINING_1
    private val profile = SelfOwnedNativeProfileV1.training(slot)
    private val installerSecret = "synthetic-installer-pass"
    private val headSecret = "synthetic-head-pass-2026"
    private val ownerPassword = "synthetic-owner-pass-2026"
    private val employeeLogin = "employee.two"
    private val employeePassword = "synthetic-employee-pass-2026"

    private var custody: DesktopMasterClientJournalCustody? = null



    private fun compose(): Pair<LocalSetupCoordinator, DesktopMasterSetupNativeServices> {
        val factory = DesktopSelfOwnedMasterServiceFactory(DesktopSelfOwnedMasterNativeOwner, DesktopSelfOwnedMasterDiagnostics)
        val services = DesktopMasterSetupNativeServices(DesktopMasterSetupPlatform(
            openProvisioner = { originalOnly -> DesktopSelfOwnedEnrollmentSource.open(os, profile, existingOriginal = originalOnly) },
            openTarget = { preparation ->
                if (preparation) DesktopSelfOwnedInstallationOwner.prepare(os, profile) else DesktopSelfOwnedInstallationOwner.open(os, profile)
            },
            factory = factory,
            identityStore = { error("The LAN host is not exercised by this check") },
            clientJournals = { DesktopClientJournalStorages.masterService(DesktopMasterClientJournalCustody.of(os, profile).also { custody = it }) },
        ), slot)
        val storages = DesktopSetupStorages(Files.createDirectories(base.resolve("setup"), ownerOnly))
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
            grant(scope, actor, SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER),
            grant(scope, actor, SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM)) +
            setOf(SecurityAction.CASH_CONFIGURE, SecurityAction.CASH_OPEN, SecurityAction.CASH_READ,
                SecurityAction.PURCHASING_READ,
                SecurityAction.INVENTORY_COST_READ,
                SecurityAction.SUPPLIER_CASH_SETTLE, SecurityAction.SUPPLIER_CASH_RESOLVE).map { action ->
                PermissionGrant(Permission(action, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
                    PermissionLimits(Money(Currency.EGP, 1_000_000), 10_000), OwnerAccess.OWN_ONLY, actor)
            }
        return LocalAccountProvisioning(actor, LocalLoginIdentifier("owner.one"),
            ActivatedLocalPolicy(scope, actor, Revision(0), grants, LocalSessionPolicy(2), ThrottlePolicy(), approvalPolicy = null),
            LocalTokenPolicy(30L * 60_000L, 8L * 3_600_000L), setOf(review.deviceId))
    }


    @Test
    fun nativeUnknownCaptureSurvivesClientRetirementAndOriginalContinuationsDoNotPayTwice(): Unit = runBlocking {
        val composition = compose()
        var setup = composition.first
        var services = composition.second
        var session: BranchUiSession? = null
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
            // Retail/FOOD physical recording enrolled its original catalogue on the service's own custody,
            // in this training slot's own namespace (never the live one).
            val parent = assertNotNull(custody).parentDirectory
            assertFalse(Files.exists(parent.resolve("ukkera-desktop-master-physical-journals-v1")), "no live physical namespace in training")
            val physicalRoot = parent.resolve("ukkera-desktop-master-physical-journals-training-v1")
            assertTrue(Files.isDirectory(physicalRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS), "original physical journals enrolled")


            suspend fun signIn(explicitMove: Boolean = false): BranchUiSession =
                assertIs<SignInResult.Authenticated<*>>(services.localClient.signIn("owner.one", ownerPassword.toCharArray(), explicitMove)).session as BranchUiSession
            session = signIn()
            val actor = session.display.actorId
            val drawer = DomainId.random()
            val cashId = DomainId.random()
            val supplier = DomainId.random()
            fun v(field: LocalField, value: LocalScalarValue) = LocalScalarInput(field, value)
            fun id(field: LocalField, value: DomainId) = v(field, LocalScalarValue.Identifier(value))
            suspend fun execute(action: LocalAction, values: List<LocalScalarInput>): LocalOperationResult.Recorded {
                val operation = OperationId.random()
                val staged = session!!.workflows.stage(LocalCommandIntent(operation, action, 1, null, values))
                assertIs<LocalStageResult.Staged>(staged, "$action: $staged; retained fixture at $base")
                val result = assertIs<LocalOperationResult.Recorded>(session!!.workflows.submit(operation))
                assertEquals(LocalRecordedDecision.APPLIED, result.decision, "$action: ${result.message.english}")
                val recovered = assertIs<LocalOperationResult.Recorded>(session!!.workflows.lookup(operation))
                assertEquals(result.operationId, recovered.operationId)
                assertEquals(result.decision, recovered.decision)
                assertIs<LocalAcknowledgementResult.Acknowledged>(session!!.workflows.acknowledgeRecorded(operation))
                return result
            }
            execute(LocalAction.DRAWER_CONFIGURE, listOf(id(LocalField.DRAWER_ID, drawer), v(LocalField.LABEL, LocalScalarValue.Text("Synthetic supplier drawer"))))
            execute(LocalAction.CUSTODIAN_CONFIGURE, listOf(id(LocalField.CUSTODIAN_ID, actor)))
            execute(LocalAction.CASH_OPEN, listOf(id(LocalField.SESSION_ID, cashId),
                v(LocalField.DRAWER_ID, LocalScalarValue.Reference(LocalRecordReference(LocalRecordKind.DRAWER, drawer, Revision(0)))),
                v(LocalField.EXPECTED_CUSTODIAN_REVISION, LocalScalarValue.Integer(0)),
                v(LocalField.BUSINESS_DATE, LocalScalarValue.Date("2026-11-15")), id(LocalField.SOURCE_ID, DomainId.random()),
                v(LocalField.OPENING_FLOAT, LocalScalarValue.Decimal("1000")), id(LocalField.EVIDENCE_ID, DomainId.random()),
                v(LocalField.OCCURRED_AT, LocalScalarValue.Text(UtcInstant(Clock.System.now()).canonicalString()))))
            execute(LocalAction.SUPPLIER_CREATE, listOf(id(LocalField.SUPPLIER_ID, supplier),
                v(LocalField.NEW_ITEM, LocalScalarValue.Flag(true)), v(LocalField.NAME_ENGLISH, LocalScalarValue.Text("Synthetic supplier")),
                v(LocalField.NAME_ARABIC, LocalScalarValue.Text("مورد اختبار")), v(LocalField.ACTIVE, LocalScalarValue.Flag(true))))

            suspend fun staged(request: LocalRetailPhysicalRequest): OperationId {
                val reply = session!!.retailPhysical.prepare(request)
                val prepared = assertIs<LocalRetailPhysicalReply.Prepared>(reply, "$request: $reply; retained fixture at $base")
                return assertIs<LocalRetailPhysicalReply.Staged>(session!!.retailPhysical.stage(prepared.value)).value.operationId
            }
            val start = staged(LocalRetailPhysicalRequest.SupplierCash(supplier, null, Money(Currency.EGP, 3_000), "synthetic.supplier.advance"))
            val lifetime = LocalRetailPhysicalLifetime()
            val instruction = assertIs<LocalRetailPhysicalReply.Instruction>(session!!.retailPhysical.start(start, lifetime)).value
            assertEquals(Money(Currency.EGP, 3_000), instruction.display.amount)
            val recording = instruction.display.recordingOperationId
            assertIs<LocalRetailPhysicalCapture.CapturedInMemory>(instruction.capture(LocalRetailObservation.Unknown(
                LocalRetailEvidence(DomainId.random(), UtcInstant(Clock.System.now())))))
            // Retire employee/UI immediately after canonical in-memory capture. The accepted
            // native evidence worker remains independently owned; no second capture is made.
            session!!.close(); session = null
            lifetime.deny()
            session = signIn(explicitMove = true)
            withTimeout(90_000L) {
                while (true) {
                    val read = session!!.retailPhysical.recoverSupplierCash(recording)
                    if (read is LocalRetailPhysicalReply.Progress && read.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING) break
                    delay(100)
                }
            }
            val apply = staged(LocalRetailPhysicalRequest.SupplierCashApply(recording, LocalRetailObservationPhase.ORIGINAL))
            val unknown = assertIs<LocalRetailPhysicalReply.Progress>(session!!.retailPhysical.continueSql(apply))
            assertEquals(LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED, unknown.value.state)
            assertEquals(unknown.value, assertIs<LocalRetailPhysicalReply.Progress>(session!!.retailPhysical.continueSql(apply)).value)
            val resolution = staged(LocalRetailPhysicalRequest.SupplierCashResolve(recording,
                LocalRetailObservation.CashPaid(LocalRetailEvidence(DomainId.random(), UtcInstant(Clock.System.now())),
                    literalSupplierAmount = LocalSupplierObservedAmount("EGP", "30"))))
            assertEquals(LocalRetailDurableState.SQL_RESOLUTION_PENDING,
                assertIs<LocalRetailPhysicalReply.Progress>(session!!.retailPhysical.continueSql(resolution)).value.state)
            val applyResolution = staged(LocalRetailPhysicalRequest.SupplierCashApply(recording, LocalRetailObservationPhase.RESOLUTION))
            val paid = assertIs<LocalRetailPhysicalReply.Progress>(session!!.retailPhysical.continueSql(applyResolution))
            assertEquals(LocalRetailDurableState.FINANCIALLY_APPLIED, paid.value.state)
            repeat(3) { assertEquals(paid.value, assertIs<LocalRetailPhysicalReply.Progress>(session!!.retailPhysical.continueSql(applyResolution)).value) }
            val cash = assertIs<LocalReadResult.Available>(session!!.workflows.read(LocalWorkflowQuery(LocalWorkflow.CASH))).page
            val cashRecord = cash.records.single { it.reference.kind == LocalRecordKind.CASH_SESSION && it.reference.id == cashId }
            val expectedCash = cashRecord.values.single { it.label.english == "Expected cash" }.value.english
            assertEquals("970.00", Regex("-?[0-9]+\\.[0-9]{2}").find(expectedCash)?.value)
            assertTrue("EGP" in expectedCash)
            session!!.close(); session = null
            assertNotNull(withTimeoutOrNull(60_000L) { setup.requestRetirement().await(); true }, "Old native owner must certainly close")
            val reopened = compose()
            setup = reopened.first; services = reopened.second
            setup.reopenRetainedMaster()
            val reopenedState = setup.settle(LocalSetupPhase.INSTALLATION_RETAINED, "existing-only native reopen")
            assertTrue(reopenedState.localClientConnected)
            session = signIn(explicitMove = true)
            val masterFact = session!!.retailPhysical.recoverSupplierCash(recording)
            assertEquals(LocalRetailDurableState.FINANCIALLY_APPLIED,
                assertIs<LocalRetailPhysicalReply.Progress>(masterFact, "Cold immutable supplier fact: $masterFact").value.state)
            val recovery = session!!.retailPhysical.recoverSupplierCash(applyResolution)
            val recoveredPaid = assertIs<LocalRetailPhysicalReply.Progress>(recovery,
                "Cold exact client intent recovery: $recovery; pending=${session!!.workflows.pending()}; retained fixture at $base")
            assertEquals(LocalRetailDurableState.FINANCIALLY_APPLIED, recoveredPaid.value.state)
            val reopenedCash = assertIs<LocalReadResult.Available>(session!!.workflows.read(LocalWorkflowQuery(LocalWorkflow.CASH))).page
            val afterCash = reopenedCash.records.single { it.reference.kind == LocalRecordKind.CASH_SESSION && it.reference.id == cashId }
                .values.single { it.label.english == "Expected cash" }.value.english
            assertEquals(expectedCash, afterCash)
            println("Bounded native supplier UNKNOWN/capture retirement/original retry/confirmed reopen completed; evidence at $base")
        } finally {
            session?.close()
            assertNotNull(withTimeoutOrNull(60_000L) { setup.requestRetirement().await(); true }, "Native owner must confirm actual close")
        }
    }
}
