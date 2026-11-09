package com.ukkera.pos.app.gateway.independent.returnrightsclientrecoveryv1

import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalCompanyCatalogGateway
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowController
import com.ukkera.pos.app.LocalWorkflowGateway
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.database.LookupReply
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual shared receipt controller callbacks; the UI session is controlled, not real authentication or OS proof. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReturnRightsClientRecoveryReceiptLifecycleTest {
    private val inputs = FrozenReturnRightsClientRecoveryInputs

    @Test
    fun rrc018SharedControllerCloseAndAckIncludingDuplicateCallbacksNeverReadPolicyOrActivate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val matrix = inputs.matrix("receiptCompletionMatrix")
            assertEquals(12, matrix.size)
            for (fixture in matrix) {
                val name = fixture.rrcString("replyFixture") + ":" + fixture.rrcString("action") + ":" + fixture.rrcFlag("duplicateCallback")
                val original = inputs.entry()
                val harness = ControlledReturnRightsClientRecoveryHarness(initial = original)
                val session = ControlledReturnRightsReceiptSession(harness)
                val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
                    override val configured = true
                    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
                        SignInResult.Authenticated(session)
                }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
                var controller: LocalWorkflowController? = null
                try {
                    runtime.openSignIn(company = false, token = runtime.navigator.token())
                    runtime.signIn("synthetic-receipt-controller", charArrayOf('s'), runtime.navigator.token())
                    assertEquals(AppArea.CATALOG, runtime.navigator.state.value.selectedArea, name)
                    val replyName = fixture.rrcString("replyFixture")
                    harness.master.lookupReply = LookupReply.Found(inputs.recorded(replyName))
                    val active = LocalWorkflowController(runtime, session, runtime.navigator.token(), backgroundScope)
                    controller = active
                    active.activate(LocalWorkflow.CATALOG)
                    runCurrent()
                    active.lookup(original.operationId, runtime.navigator.token())
                    runCurrent()
                    assertFrozenReturnRightsReceipt(replyName, original, inputs.recorded(replyName),
                        assertIs<LocalOperationResult.Recorded>(active.state.value.operation, name))
                    val grants = returnRightsGrantScalars(harness.view.policy.grants)
                    if (fixture.rrcString("action") == "CLOSE") {
                        active.close()
                        if (fixture.rrcFlag("duplicateCallback")) active.close()
                        runCurrent()
                        assertNull(active.state.value.operation, name)
                        assertFalse(assertNotNull(harness.journal.entry).acknowledged, name)
                        assertEquals(0, harness.journal.acknowledgeCalls, name)
                    } else {
                        active.acknowledgeRecorded(original.operationId, runtime.navigator.token())
                        if (fixture.rrcFlag("duplicateCallback"))
                            active.acknowledgeRecorded(original.operationId, runtime.navigator.token())
                        runCurrent()
                        if (fixture.rrcFlag("duplicateCallback")) {
                            active.acknowledgeRecorded(original.operationId, runtime.navigator.token())
                            runCurrent()
                        }
                        assertTrue(active.state.value.recordedAcknowledged, name)
                        assertEquals(original.operationId, active.state.value.operationId, name)
                        assertNull(active.state.value.reference, name)
                        assertEquals(1, harness.journal.acknowledgeCalls, name)
                    }
                    assertReturnRightsOriginalRetained(original, assertNotNull(harness.journal.entry), name)
                    val currentGrants = returnRightsGrantScalars(harness.view.policy.grants)
                    assertEquals(grants, currentGrants, name)
                    assertEquals(fixture.rrcString("expectedPermissionGrants").toInt(),
                        currentGrants.count { it !in grants }, name)
                    assertEquals(fixture.rrcString("expectedNewActivationSubmissions").toInt(), harness.master.mutations.size, name)
                    assertEquals(fixture.rrcString("expectedPolicyContentReads").toInt(), harness.master.reads.size, name)
                    assertEquals(fixture.rrcString("expectedAuthoringPreparations").toInt(), session.authoringAccesses, name)
                    assertEquals(0, harness.journal.stageCalls, name)
                    assertTrue(harness.master.lookups.all { it == original.identity() }, name)
                } finally {
                    controller?.close()
                    runtime.close()
                    harness.close()
                }
            }
        } finally { Dispatchers.resetMain() }
    }
}

private class ControlledReturnRightsReceiptSession(private val harness: ControlledReturnRightsClientRecoveryHarness) : BranchUiSession {
    private val owner = FrozenReturnRightsClientRecoveryInputs.owner
    private var live = true
    var authoringAccesses = 0
        private set
    override val display = SessionDisplayContext(owner.actorId, owner.scope.companyId, owner.scope.branchId,
        "Synthetic documentary owner", "Synthetic company", "Synthetic branch", setOf(AppArea.CATALOG))
    override val workflows: LocalWorkflowGateway get() = harness.gateway
    override val companyCatalog: LocalCompanyCatalogGateway get() {
        authoringAccesses++
        throw AssertionError("Receipt completion must not access native authoring")
    }
    override fun isCurrent(): Boolean = live
    override suspend fun mayRestore(route: AppRoute): Boolean = false
    override fun close() { live = false; harness.close() }
}
