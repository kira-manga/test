@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.applicationauthenticationattachment

import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionEndResult
import com.ukkera.pos.app.LocalSessionLifecycle
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.UnavailableLocalAdministration
import com.ukkera.pos.app.UnavailableLocalCompanyCatalog
import com.ukkera.pos.app.UnavailableLocalRetailPhysical
import com.ukkera.pos.app.UnavailableLocalWorkflows
import com.ukkera.pos.app.gateway.StoredSessionPhaseV1
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.security.LocalAuthenticationDenial
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Actual shared sessions with controlled S ports only; whole APP-AUTH cases remain NOT RUN. */
class ApplicationAuthenticationSessionSupportingTest {
    @Test
    fun appAuth005And020AuthOnlySessionSupportsCurrentButNoBusinessCapabilities() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            assertIs<LocalSessionCheckResult.Current>(session.current())
            assertEquals(2, f.service.currentCount)
            assertEquals(1, f.service.issuedResults.size)
            assertSame(UnavailableLocalWorkflows, session.workflows)
            assertSame(UnavailableLocalAdministration, session.administration)
            assertSame(UnavailableLocalCompanyCatalog, session.companyCatalog)
            assertSame(UnavailableLocalRetailPhysical, session.retailPhysical)
            for (workflow in listOf(LocalWorkflow.ORDERS, LocalWorkflow.CATALOG, LocalWorkflow.REPORTS, LocalWorkflow.INVENTORY))
                assertIs<LocalReadResult.Unavailable>(session.workflows.read(LocalWorkflowQuery(workflow)))
            val operation = OperationId(AppAuthenticationFrozen.slotId("OP_UNKNOWN_A").value)
            assertIs<LocalOperationResult.Unresolved>(session.workflows.submit(operation))
            assertIs<LocalPendingResult.Unavailable>(session.workflows.pending())
            assertFalse(session.mayRestore(AppRoute.Order(AppAuthenticationFrozen.slotId("ORDER_A"))))
            assertEquals(0, f.service.refreshCount)
        }
        // These absent adapters do not qualify direct actual RPC denial or independent business-cut reads.
    }

    @Test
    fun appAuth011LateCurrentCannotReviveClosedOriginalAttachment() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            val gate = AppAuthenticationGate().also { f.service.currentGate = it }
            val result = async { session.current() }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            session.close()
            assertFalse(session.isCurrent())
            gate.release()
            assertIs<LocalSessionCheckResult.Unavailable>(result.await())
            assertFalse(session.isCurrent())
            assertEquals(null, session.attachmentKey)
        }
    }

    @Test
    fun appAuth012FreshCurrentIsNotComparedWithLowerUnrelatedSchedulerNumber() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            assertTrue(testScheduler.currentTime < f.service.activeView.accessExpiresAt.elapsedMillis)
            assertTrue(f.session().isCurrent())
        }
    }

    @Test
    fun appAuth012FreshCurrentIsNotExpiredByHigherUnrelatedSchedulerNumber() = runTest(timeout = 5.seconds) {
        advanceTimeBy(1_001)
        withAppAuthenticationHarness { f ->
            assertTrue(testScheduler.currentTime > f.service.activeView.accessExpiresAt.elapsedMillis)
            assertTrue(f.session().isCurrent())
        }
        // Scheduler/deadline numbers are carrier DATA, not actual clocks, expiry policy or TTL defaults.
    }

    @Test
    fun appAuth013RefreshRetiresOldLocalAttachmentBeforeRetainedFreshSuccessorAdoption() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            val old = assertNotNull(session.attachmentKey)
            val retention = AppAuthenticationGate().also { f.journal.gates[4] = it }
            val result = async { session.refresh() }
            runCurrent()
            assertTrue(retention.entered.isCompleted)
            assertFalse(session.isCurrent())
            assertEquals(old, session.attachmentKey)
            assertEquals(1, f.service.refreshCount)
            retention.release()
            val successor = assertIs<LocalSessionCheckResult.Current>(result.await())
            assertNotEquals(old, successor.attachmentKey)
            assertEquals(successor.attachmentKey, session.attachmentKey)
            assertTrue(session.isCurrent())
            assertSame(f.original, f.connections.value)
            f.journal.inspect {
                val row = it.sessions.single()
                assertEquals(AppAuthenticationFrozen.secondSessionId, row.sessionId)
                assertEquals(AppAuthenticationFrozen.actor, row.identity.actorId)
                assertEquals(AppAuthenticationFrozen.owner.scope, row.identity.scope)
                assertEquals(AppAuthenticationFrozen.owner.deviceId, row.identity.deviceId)
                assertEquals(AppAuthenticationFrozen.view().workExpiresAt, row.workExpiresAt)
                assertEquals(f.original.credentialBinding, row.binding)
                assertTokenCarrier(row.access, AppAuthenticationFrozen.successorView(), refresh = false)
                assertTokenCarrier(row.refresh, AppAuthenticationFrozen.successorView(), refresh = true)
            }
        }
        // Changed local attachment and scripted carrier pairs are not actual RPC rotation/work-lineage proof.
    }

    @Test
    fun appAuth013AnotherOriginalCannotAdoptReceivedSuccessor() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            val retention = AppAuthenticationGate().also { f.journal.gates[4] = it }
            val result = async { session.refresh() }
            runCurrent()
            assertTrue(retention.entered.isCompleted)
            f.connections.value = f.successorOriginal()
            retention.release()
            assertIs<LocalSessionCheckResult.Unavailable>(result.await())
            assertFalse(session.isCurrent())
            f.journal.inspect { assertEquals(f.original.credentialBinding, it.sessions.single().binding) }
        }
    }

    @Test
    fun appAuth015LostRefreshRetainsUnknownMarkerAndNeverAutomaticallyRotatesAgain() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            f.service.refreshResult = { LocalAuthenticationResult.Unavailable }
            assertIs<LocalSessionCheckResult.Unavailable>(session.refresh())
            assertFalse(session.isCurrent())
            assertIs<LocalSessionCheckResult.Unavailable>(session.refresh())
            assertEquals(1, f.service.refreshCount)
            assertTrue(f.gateway.cleanupStatus().authenticationUnresolved)
            f.journal.inspect { assertEquals(StoredSessionPhaseV1.REFRESH_PENDING, it.sessions.single().phase) }
        }
    }

    @Test
    fun appAuth015UncertainSuccessorRetentionKeepsOwnedMaterialWithoutPublicationOrSecondRotation() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            f.journal.faults[4] = ControlledAppWrite.COMMITTED_BUT_UNKNOWN
            assertIs<LocalSessionCheckResult.Unavailable>(session.refresh())
            assertFalse(session.isCurrent())
            assertIs<LocalSessionCheckResult.Unavailable>(session.refresh())
            assertEquals(1, f.service.refreshCount)
            f.journal.inspect { assertEquals(AppAuthenticationFrozen.secondSessionId, it.sessions.single().sessionId) }
            assertFalse(f.gateway.cleanupStatus().clear)
        }
        // AA-GAP-01/04 remain open; the controlled observer is not a native successor-resolution procedure.
    }

    @Test
    fun appAuth016And019LocalCloseAndAdapterRecreationDoNotRestoreStoredTokenAuthority() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            f.gateway.closeAndAwait()
            assertFalse(session.isCurrent())
            f.journal.inspect { assertEquals(1, it.sessions.size) }
            val recreated = f.newGateway()
            runCurrent()
            assertFalse(recreated.cleanupStatus().clear)
            assertEquals(1, f.service.loginCount)
            assertEquals(1, f.service.currentCount)
            assertEquals(0, f.service.refreshCount)
            assertEquals(0, f.service.logoutCount)
        }
        // Actual process/UI-frame/LAN-consent recovery and independent Master service continuity are unbound.
    }

    @Test
    fun appAuth017LogoutLocksBeforeHeldRemoteSettlement() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            val gate = AppAuthenticationGate().also { f.service.logoutGate = it }
            val result = async { session.logout() }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertFalse(session.isCurrent())
            assertFalse(result.isCompleted)
            f.journal.inspect { assertEquals(StoredSessionPhaseV1.REVOCATION_PENDING, it.sessions.single().phase) }
            gate.release()
            assertEquals(LocalSessionEndResult.REVOKED, result.await())
            assertFalse(session.isCurrent())
            f.journal.inspect { assertTrue(it.sessions.isEmpty()) }
        }
        // Revoked is a scripted service result here, not authoritative transaction/physical-close evidence.
    }

    @Test
    fun appAuth017UnknownLogoutRemainsPendingAcrossAdapterRecreation() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            f.service.logoutResult = LocalSignOutResult.Unavailable
            assertEquals(LocalSessionEndResult.UNRESOLVED, session.logout())
            assertFalse(session.isCurrent())
            f.gateway.closeAndAwait()
            val recreated = f.newGateway()
            assertEquals(1, recreated.cleanupStatus().pendingRevocations)
            f.journal.inspect { assertEquals(StoredSessionPhaseV1.REVOCATION_PENDING, it.sessions.single().phase) }
        }
    }

    @Test
    fun appAuth018OriginalRevocationConfirmationCannotEraseUnknownRefreshSuccessorMarker() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            f.service.refreshResult = { LocalAuthenticationResult.Unavailable }
            assertIs<LocalSessionCheckResult.Unavailable>(session.refresh())
            assertEquals(LocalSessionEndResult.UNRESOLVED, session.logout())
            f.journal.inspect {
                val row = it.sessions.single()
                assertTrue(row.originalRevoked)
                assertEquals(StoredSessionPhaseV1.REFRESH_PENDING, row.phase)
                assertEquals(AppAuthenticationFrozen.firstSessionId, row.sessionId)
            }
            f.gateway.closeAndAwait()
            val recreated = f.newGateway()
            assertTrue(recreated.cleanupStatus().authenticationUnresolved)
            assertFalse(recreated.cleanupStatus().clear)
            assertEquals(1, f.service.refreshCount)
            assertEquals(1, f.service.logoutCount)
        }
    }

    @Test
    fun appAuth019ConnectionLossFencesUseBeforeActivePresentationCatchesUp() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value)
            f.connections.value = null
            assertEquals(LocalSessionLifecycle.ACTIVE, session.lifecycle?.value)
            assertFalse(session.isCurrent())
            assertIs<LocalSessionCheckResult.Unavailable>(session.current())
            assertEquals(1, f.service.currentCount)
        }
        // The synchronous real shared fence runs; transport loss itself is a controlled observation.
    }

    @Test
    fun appAuth021ScriptedCurrentAndRefreshDenialClearsInteractiveUse() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            f.service.currentResult = { LocalSessionViewResult.Denied }
            f.service.refreshResult = { LocalAuthenticationResult.Denied(LocalAuthenticationDenial.AUTHENTICATION_REFUSED) }
            assertIs<LocalSessionCheckResult.Unavailable>(session.current())
            assertFalse(session.isCurrent())
            assertEquals(null, session.attachmentKey)
        }
        // Real Master revocation, stale-policy activation and output-commit races remain required R/D/F work.
    }

    @Test
    fun appAuth022EndedServiceResultsAndCurrentCopiesReleaseWithoutDestroyingRetainedCustodySnapshots() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val session = f.session()
            val result = f.service.issuedResults.single()
            assertFailsWith<IllegalStateException> { result.access.useBytes { Unit } }
            assertFailsWith<IllegalStateException> { result.refresh.useBytes { Unit } }
            assertIs<LocalSessionCheckResult.Current>(session.current())
            for (credential in f.service.suppliedCredentials)
                assertFailsWith<IllegalStateException> { credential.bearer.useBytes { Unit } }
            f.journal.inspect { row ->
                assertTokenCarrier(row.sessions.single().access, AppAuthenticationFrozen.view(), refresh = false)
                assertTokenCarrier(row.sessions.single().refresh, AppAuthenticationFrozen.view(), refresh = true)
            }
        }
        // Public owned-credential invalidation is observed; private-memory/immutable secure erasure is not claimed.
    }
}

private fun assertTokenCarrier(credential: com.ukkera.pos.security.OpaqueCredential,
    view: com.ukkera.pos.security.LocalSessionView, refresh: Boolean) {
    val expected = AppAuthenticationFrozen.tokenBytes(view, refresh)
    try { assertTrue(credential.useBytes { it.contentEquals(expected) }, "Retained supporting credential carrier changed") }
    finally { expected.fill(0) }
}
