@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.applicationauthenticationattachment

import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalSessionWorkspaceMode
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.gateway.LocalClientAuthenticationBinding
import com.ukkera.pos.security.LocalAuthenticationDenial
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unchanged APP-AUTH v1 supporting application comparisons/schedules. The real shared gateway
 * runs; service and journal returns are controlled ports, not genuine R/D, native approval,
 * remote exchange exactly-once evidence, persistent intents or the frozen business cut.
 */
class ApplicationAuthenticationGatewaySupportingTest {
    @Test
    fun appAuth001AbsentOriginalCannotDispatchOrPublish() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.connections.value = null
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(0, f.service.loginCount)
            assertEquals(0, f.journal.writeCount)
        }
    }

    @Test
    fun appAuth001DeniedOriginalCannotDispatchDespiteRetainedConnectionData() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.originalCurrent = false
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(0, f.service.loginCount)
            assertEquals(0, f.journal.writeCount)
        }
    }

    @Test
    fun appAuth002WrongDeviceCustodyCannotSubstituteForOriginalConnection() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness(AppAuthenticationFrozen.owner.copy(deviceId = AppAuthenticationFrozen.otherDevice)) { f ->
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(0, f.service.loginCount)
            assertEquals(0, f.journal.writeCount)
        }
    }

    @Test
    fun appAuth003ControlledIntentRetentionCutPrecedesAuthenticationDispatch() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val retention = AppAuthenticationGate().also { f.journal.gates[1] = it }
            val request = async { f.signIn() }
            runCurrent()
            assertTrue(retention.entered.isCompleted)
            assertEquals(0, f.service.loginCount)
            assertFalse(request.isCompleted)
            retention.release()
            assertIs<SignInResult.Authenticated<BranchUiSession>>(request.await())
            assertTrue(f.events.indexOf("credentials.write.1.retained") < f.events.indexOf("authentication.login.enter"))
            assertEquals(listOf(AppAuthenticationFrozen.login.value), f.service.suppliedLogins)
            assertEquals(listOf(false), f.service.suppliedMoves)
        }
        // The local dispatch record's association with the actual LOGIN_1 RPC UUID is unbound.
    }

    @Test
    fun appAuth004KnownPrecommitIntentFailurePreventsDispatch() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.journal.faults[1] = ControlledAppWrite.KNOWN_PRECOMMIT_FAILURE
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(0, f.service.loginCount)
            f.journal.inspect { assertTrue(it.attempts.isEmpty()) }
        }
    }

    @Test
    fun appAuth004UnknownIntentRetentionPreventsDispatchAndPreservesItsControlledRecord() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.journal.faults[1] = ControlledAppWrite.COMMITTED_BUT_UNKNOWN
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(0, f.service.loginCount)
            f.journal.inspect {
                assertEquals(AppAuthenticationFrozen.login, it.attempts.single().login)
                assertEquals(f.original.credentialBinding, it.attempts.single().binding)
            }
            f.gateway.closeAndAwait()
            val reopenedGateway = f.newGateway()
            runCurrent()
            assertTrue(reopenedGateway.cleanupStatus().authenticationUnresolved)
            assertEquals(0, f.service.loginCount)
            assertEquals(0, f.service.refreshCount)
        }
        // Recreating a gateway around this test port is not a genuine storage/process reopen.
    }

    @Test
    fun appAuth005CredentialRetentionAndFreshCurrentBothPrecedeInteractiveDelivery() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val retention = AppAuthenticationGate().also { f.journal.gates[2] = it }
            val current = AppAuthenticationGate().also { f.service.currentGate = it }
            val request = async { f.signIn() }
            runCurrent()
            assertTrue(retention.entered.isCompleted)
            assertEquals(1, f.service.loginCount)
            assertEquals(0, f.service.currentCount)
            assertFalse(request.isCompleted)
            retention.release()
            runCurrent()
            assertTrue(current.entered.isCompleted)
            assertFalse(request.isCompleted)
            f.journal.inspect {
                val row = it.sessions.single()
                assertEquals(AppAuthenticationFrozen.firstSessionId, row.sessionId)
                assertEquals(AppAuthenticationFrozen.owner.scope, row.identity.scope)
                assertEquals(AppAuthenticationFrozen.owner.deviceId, row.identity.deviceId)
                assertIs<LocalClientAuthenticationBinding.RemoteOriginalAttachment>(row.binding)
            }
            current.release()
            val session = assertIs<SignInResult.Authenticated<BranchUiSession>>(request.await()).session
            assertTrue(session.isCurrent())
            assertEquals(AppAuthenticationFrozen.actor, session.display.actorId)
            assertEquals(AppAuthenticationFrozen.scope.branchId, session.display.branchId)
            assertEquals(LocalSessionWorkspaceMode.AUTHENTICATION_ONLY, session.display.workspaceMode)
            assertTrue(session.display.allowedAreas.isEmpty())
        }
    }

    @Test
    fun appAuth006UnavailableCredentialReadCannotInitializeReplacementHistory() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.journal.readable = false
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(0, f.service.loginCount)
            assertEquals(0, f.journal.writeCount)
        }
        // Missing/corrupt native record variants are not manufactured by this read-unavailable port.
    }

    @Test
    fun appAuth006UncertainReceivedCredentialRetentionRefusesCurrentAndPublication() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.journal.faults[2] = ControlledAppWrite.COMMITTED_BUT_UNKNOWN
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(1, f.service.loginCount)
            assertEquals(0, f.service.currentCount)
            f.journal.inspect { assertEquals(AppAuthenticationFrozen.firstSessionId, it.sessions.single().sessionId) }
            assertFalse(f.gateway.cleanupStatus().clear)
        }
        // The scripted issued result may exist; rejection is never asserted to be Master rollback.
    }

    @Test
    fun appAuth007ScriptedServiceDenialNeverBecomesSuccessfulApplicationAuthentication() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.service.loginResult = { LocalAuthenticationResult.Denied(LocalAuthenticationDenial.AUTHENTICATION_REFUSED) }
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(1, f.service.loginCount)
            assertTrue(f.service.issuedResults.isEmpty())
            assertEquals(0, f.service.currentCount)
        }
        // Actual correct/wrong/disabled/branch-B credential verification remains required R/D work.
    }

    @Test
    fun appAuth008DeviceMoveConsentRemainsExplicitAtTheApplicationDispatch() = runTest(timeout = 5.seconds) {
        for (consent in listOf(false, true)) withAppAuthenticationHarness { f ->
            f.service.loginResult = { LocalAuthenticationResult.Denied(LocalAuthenticationDenial.EXPLICIT_DEVICE_MOVE_REQUIRED) }
            assertIs<SignInResult.Rejected>(f.signIn(explicitDeviceMove = consent))
            assertEquals(listOf(consent), f.service.suppliedMoves)
        }
        // Forwarding exact consent is supporting; actual P_SINGLE device move is not performed.
    }

    @Test fun appAuth009CurrentEmployeeMismatchCannotPublish() = currentMismatch { AppAuthenticationFrozen.view(actorId = AppAuthenticationFrozen.otherActor) }
    @Test fun appAuth009CurrentDeviceMismatchCannotPublish() = currentMismatch { AppAuthenticationFrozen.view(deviceId = AppAuthenticationFrozen.otherDevice) }
    @Test fun appAuth009CurrentBranchMismatchCannotPublish() = currentMismatch { AppAuthenticationFrozen.view(branchId = AppAuthenticationFrozen.branchB) }
    @Test fun appAuth009CurrentIssuedSessionMismatchCannotPublish() = currentMismatch { AppAuthenticationFrozen.view(sessionId = AppAuthenticationFrozen.secondSessionId) }

    @Test
    fun appAuth010ExactOwnerIsRecheckedAfterFreshCurrentBeforeAdoption() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.service.beforeCurrentReturn = { f.originalCurrent = false }
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(1, f.service.currentCount)
            f.journal.inspect { assertEquals(1, it.sessions.size) }
        }
    }

    @Test
    fun appAuth014LostLoginOutcomeRetainsIntentWithoutAutomaticAuthenticationReissue() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.service.loginResult = { LocalAuthenticationResult.Unavailable }
            assertIs<SignInResult.Rejected>(f.signIn())
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(1, f.service.loginCount)
            assertTrue(f.gateway.cleanupStatus().authenticationUnresolved)
            f.gateway.closeAndAwait()
            val recreated = f.newGateway()
            runCurrent()
            assertTrue(recreated.cleanupStatus().authenticationUnresolved)
            assertEquals(1, f.service.loginCount)
            assertEquals(0, f.service.refreshCount)
        }
        // Neither actual duplicate RPC delivery nor explicit LOGIN_RELOGIN service policy runs here.
    }

    @Test
    fun appAuth022CallerPasswordRemainsOwnedWhileEndedApplicationCopiesClear() = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            val caller = AppAuthenticationFrozen.password()
            val application = caller.copyOf()
            val gate = AppAuthenticationGate().also { f.service.loginGate = it }
            val request = async { f.gateway.signIn(AppAuthenticationFrozen.login.value, application) }
            try {
                runCurrent()
                assertTrue(gate.entered.isCompleted)
                val sourceInput = f.service.suppliedPasswords.single()
                assertNotSame(caller, application)
                assertNotSame(application, sourceInput)
                assertNotSame(caller, sourceInput)
                assertTrue(caller.contentEquals(sourceInput))
                request.cancelAndJoin()
                assertTrue(application.all { it == '\u0000' })
                gate.release()
                runCurrent()
                assertTrue(sourceInput.all { it == '\u0000' })
                val expected = AppAuthenticationFrozen.password()
                try { assertTrue(caller.contentEquals(expected), "Caller-owned password changed") }
                finally { expected.fill('\u0000') }
            } finally { gate.release(); request.cancelAndJoin(); application.fill('\u0000'); caller.fill('\u0000') }
        }
        // Immutable UI/codec string erasure and timing-specific input events remain unclaimed.
    }

    private fun currentMismatch(view: () -> LocalSessionView) = runTest(timeout = 5.seconds) {
        withAppAuthenticationHarness { f ->
            f.service.currentResult = { LocalSessionViewResult.Current(view()) }
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(1, f.service.currentCount)
            f.journal.inspect { assertEquals(1, it.sessions.size) }
        }
    }
}
