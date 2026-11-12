package com.ukkera.pos.app.gateway.setup.independent.employeepolicyauthoring

import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.gateway.setup.LocalEmployeeSetupIntents
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord
import com.ukkera.pos.app.gateway.setup.LocalMasterLaunchStorage
import com.ukkera.pos.app.gateway.setup.LocalSetupClient
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.app.gateway.setup.LocalSetupEmployeeConnection
import com.ukkera.pos.app.gateway.setup.LocalSetupExecution
import com.ukkera.pos.app.gateway.setup.LocalSetupIntentStorage
import com.ukkera.pos.app.gateway.setup.LocalSetupMaster
import com.ukkera.pos.app.gateway.setup.LocalSetupMasterOpenResult
import com.ukkera.pos.app.gateway.setup.LocalSetupNativeServices
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyTarget
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalApprovalPolicy
import com.ukkera.pos.security.LocalProvisionerOpenResult
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen v2 public shared-coordinator admission slices with no native owner supplied.
 * No native constructors, test custody, success, storage or authoritative policy are fabricated.
 * Valid-owner prepare/approve/activate/history obligations remain explicitly unbound.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EmployeePolicyAuthoringCoordinatorAdmissionTest {
    @Test fun epaC08UnavailableOwnerBeforeSubmissionCannotAdmitPolicyWork() = runTest {
        val harness = Harness(testScheduler)
        try {
            val staleView = DomainId.random() // Transient presentation discriminator, not a native original.
            val policy = FrozenAdmissionFixture.replacement()
            harness.coordinator.openEmployeePolicies()
            harness.coordinator.beginEmployeePolicy()
            harness.coordinator.prepareEmployeePolicy(staleView, policy)
            harness.coordinator.activateApprovedEmployeePolicy(staleView)
            testScheduler.runCurrent()
            harness.assertNoNativeWorkOrProtectedPolicy()
        } finally { harness.close() }
    }

    @Test fun epaC20EarlyInvalidViewRejectionClearsPrefixAndUnusedTail() = runTest {
        val harness = Harness(testScheduler)
        try {
            listOf(FrozenAdmissionFixture.secret(), FrozenAdmissionFixture.secretWithTail()).forEach { buffer ->
                harness.coordinator.approveEmployeePolicy(DomainId.random(), buffer)
                assertContentEquals(CharArray(buffer.size), buffer)
            }
            testScheduler.runCurrent()
            harness.assertNoNativeWorkOrProtectedPolicy()
        } finally { harness.close() }
    }

    @Test fun epaC20LockedViewRejectionClearsPrefixAndUnusedTail() = runTest {
        val harness = Harness(testScheduler)
        try {
            harness.coordinator.lockPresentation()
            listOf(FrozenAdmissionFixture.secret(), FrozenAdmissionFixture.secretWithTail()).forEach { buffer ->
                harness.coordinator.approveEmployeePolicy(DomainId.random(), buffer)
                assertContentEquals(CharArray(buffer.size), buffer)
            }
            testScheduler.runCurrent()
            assertTrue(harness.coordinator.state.value.locked)
            harness.assertNoNativeWorkOrProtectedPolicy()
        } finally { harness.close() }
    }

    @Test fun epaC20RetiredViewRejectionClearsPrefixAndUnusedTail() = runTest {
        val harness = Harness(testScheduler)
        harness.close()
        assertEquals(LocalSetupPhase.CLOSED, harness.coordinator.state.value.phase)
        listOf(FrozenAdmissionFixture.secret(), FrozenAdmissionFixture.secretWithTail()).forEach { buffer ->
            harness.coordinator.approveEmployeePolicy(DomainId.random(), buffer)
            assertContentEquals(CharArray(buffer.size), buffer)
        }
        testScheduler.runCurrent()
        harness.assertNoNativeWorkOrProtectedPolicy()
    }

    @Test fun epaC21IdleLockDeniesPresentationActionsWithoutRetiringTheOwner() = runTest {
        val harness = Harness(testScheduler)
        try {
            harness.coordinator.lockPresentation()
            harness.coordinator.prepareEmployeePolicy(DomainId.random(), FrozenAdmissionFixture.replacement())
            harness.coordinator.inspectEmployeePolicy(FrozenAdmissionFixture.originalRequest)
            testScheduler.runCurrent()
            assertTrue(harness.coordinator.state.value.locked)
            assertEquals(0, harness.retirementNotifications)
            assertTrue(harness.execution.watching)
            harness.assertNoNativeWorkOrProtectedPolicy()
            // No actual writer was acquired; genuine no-close/native-history coverage stays unbound.
        } finally { harness.close() }
    }

    @Test fun epaC25ColdRecreationAndRouteIdentityCannotGrantProtectedPresentation() = runTest {
        val first = Harness(testScheduler)
        try {
            first.coordinator.inspectEmployeePolicy(FrozenAdmissionFixture.originalRequest)
            first.coordinator.lookupEmployeePolicy(DomainId.random())
            testScheduler.runCurrent()
            first.assertNoNativeWorkOrProtectedPolicy()
        } finally { first.close() }
        val recreated = Harness(testScheduler)
        try {
            recreated.assertNoNativeWorkOrProtectedPolicy()
            recreated.coordinator.inspectEmployeePolicy(FrozenAdmissionFixture.originalRequest)
            recreated.coordinator.lookupEmployeePolicy(DomainId.random())
            recreated.coordinator.activateApprovedEmployeePolicy(DomainId.random())
            testScheduler.runCurrent()
            recreated.assertNoNativeWorkOrProtectedPolicy()
            // This recreates the controller only. Native process/storage/history recovery is unbound.
        } finally { recreated.close() }
    }

    /** Additional EPA-C25 route-DATA binding; no valid native owner is supplied. */
    @Test fun epaC25SelectedEmployeeAndPredecessorCannotGrantProtectedPresentation() = runTest {
        val harness = Harness(testScheduler)
        try {
            val replacement = FrozenAdmissionFixture.replacement()
            harness.coordinator.openEmployeePolicies(LocalSetupEmployeePolicyTarget(
                replacement.scope, replacement.actorId, Revision(replacement.revision.value - 1)))
            harness.coordinator.beginEmployeePolicy()
            testScheduler.runCurrent()
            harness.assertNoNativeWorkOrProtectedPolicy()
        } finally { harness.close() }
    }

    private class Harness(private val scheduler: TestCoroutineScheduler) {
        val execution = ControlledExecution(scheduler)
        private val native = NoNativeOwner()
        private val storage = ForbiddenStorage()
        var retirementNotifications = 0
        val coordinator = LocalSetupCoordinator(native, LocalEmployeeSetupIntents(storage),
            LocalMasterLaunchRecord(storage), execution,
            beforeRetirement = { _, _ -> retirementNotifications++ }, beforeClientHosting = { native.unexpected() })

        fun assertNoNativeWorkOrProtectedPolicy() {
            assertEquals(0, native.calls, "no native authority, work, sign-in or publication")
            assertEquals(0, storage.calls, "no implied durable original, policy or session")
            val view = coordinator.state.value
            assertNull(view.employeePolicy)
            assertNull(view.employeePolicies)
            assertNull(view.employeeEnrollment)
            assertNull(view.definitionReview)
            assertFalse(view.localClientConnected)
            assertFalse(view.toString().contains(FrozenAdmissionFixture.secretText))
        }

        suspend fun close() {
            val result = coordinator.requestRetirement()
            scheduler.runCurrent()
            result.await()
        }
    }

    private class ControlledExecution(private val scheduler: TestCoroutineScheduler) : LocalSetupExecution {
        override val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)
        var watching = false
        override fun elapsedMillis(): Long = scheduler.currentTime
        override fun startWatching(tick: () -> Unit) { watching = true }
        override fun stopWatching() { watching = false }
    }

    private class NoNativeOwner : LocalSetupNativeServices {
        var calls = 0
        fun unexpected(): Nothing { calls++; error("No native acquisition is bound in this frozen admission slice") }
        override suspend fun openProvisioner(originalOnly: Boolean): LocalProvisionerOpenResult = unexpected()
        override suspend fun openTarget(preparation: Boolean): SelfOwnedNativeOpenResult = unexpected()
        override suspend fun openMaster(context: SelfOwnedLocalSecurityContext): LocalSetupMasterOpenResult = unexpected()
        override suspend fun openOriginalReceipt(context: SelfOwnedLocalSecurityContext): LocalMasterInstallationReceiptOpenResult = unexpected()
        override suspend fun connectClient(master: LocalSetupMaster, action: LocalOriginalClientJournalAction): LocalSetupClient? = unexpected()
        override fun ownsEmployeeConnection(connection: LocalSetupEmployeeConnection): Boolean = unexpected()
        override fun publishClient(owner: Any, client: LocalSetupClient) = unexpected()
        override fun withdrawClient(owner: Any) = unexpected()
    }

    private class ForbiddenStorage : LocalSetupIntentStorage, LocalMasterLaunchStorage {
        var calls = 0
        private fun unexpected(): Nothing { calls++; error("No persistence is bound in this frozen admission slice") }
        override fun names(): List<String>? = unexpected()
        override fun read(name: String): ByteArray = unexpected()
        override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) = unexpected()
        override fun verify() = unexpected()
        override fun read(): ByteArray? = unexpected()
        override fun replaceAndForce(bytes: ByteArray) = unexpected()
    }
}

/** Exact frozen v2 fixtures.json replacement_policy/journey/secret_fixture values; no current authority. */
private object FrozenAdmissionFixture {
    const val frozenSpecificationVersion = 2
    const val frozenSpecificationSource = "qa/pos/freezes/employee-policy-authoring-v2/FREEZE.json"
    private val scope = BranchScope(DomainId("11111111-1111-4111-8111-111111111111"),
        DomainId("22222222-2222-4222-8222-222222222221"), DomainId("33333333-3333-4333-8333-333333333331"))
    private val employee = DomainId("44444444-4444-4444-8444-444444444441")
    val originalRequest = DomainId("66666666-6666-4666-8666-666666666661")
    const val secretText = "EPA-TEST-SECRET!"
    fun secret(): CharArray = secretText.toCharArray()
    fun secretWithTail(): CharArray = (secretText.toList() + (1..8).map { it.toChar() }).toCharArray()
    fun replacement(): ActivatedLocalPolicy = ActivatedLocalPolicy(scope, employee, Revision(8), listOf(
        PermissionGrant(Permission(SecurityAction.ORDER_CREATE, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
            PermissionLimits(), OwnerAccess.OWN_ONLY, employee, null, null),
        PermissionGrant(Permission(SecurityAction.DISCOUNT, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
            PermissionLimits(Money(Currency.EGP, 5000), 1000, null), OwnerAccess.OWN_ONLY, employee, null, null),
        PermissionGrant(Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
            PermissionLimits(), OwnerAccess.OWN_ONLY, employee,
            setOf(DomainId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa1"), DomainId("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2")), null),
    ), LocalSessionPolicy(2), ThrottlePolicy(2, 2000, 8000, 10000), LocalApprovalPolicy(120000, setOf(SecurityAction.DISCOUNT)))
}
