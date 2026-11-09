@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.localserialregistration

import com.ukkera.pos.app.*
import com.ukkera.pos.app.independent.localserialregistration.LsrUiFixture.Companion.withSerialController
import com.ukkera.pos.app.navigation.*
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Frozen LSR shared controller/runtime slices over supplied pending/refusal DATA.
 * No native head authentication, actual durable save, Master permission/clock,
 * registration completion, activation, business ledger or composed card run.
 * Whole cases and all actual platform/device journeys remain UNBOUND / NOT_RUN.
 */
class LocalSerialRegistrationFrozenControllerSlicesTest {
    private val f = LsrUiInputs

    @Test fun lsr10And13_savedActivationRefreshShowsExactReviewWithoutAutomaticallySubmitting() = runTest {
        withSerialController { fixture ->
            val before = fixture.port.pendingCalls
            fixture.controller.serialRegistrationGuard(draft = false, working = true)
            fixture.controller.refreshConfigurationPending(fixture.token())
            runCurrent()
            val original = fixture.controller.state.value.pending.single()
            assertEquals(f.operation, original.operationId)
            assertEquals(LocalAction.SERIAL_REGISTRATION_ACTIVATE, original.action)
            assertEquals(f.summary, original.summary)
            assertEquals(before + 1, fixture.port.pendingCalls)
            assertTrue(fixture.port.stages.isEmpty())
            assertTrue(fixture.port.submissions.isEmpty())
            assertEquals(0, fixture.port.acknowledgements)
        }
    }

    @Test fun lsr13And16_repeatedSubmitPreservesUnknownOriginalUntilExplicitSameOriginalLookup() = runTest {
        withSerialController { fixture ->
            val controller = fixture.controller
            controller.submit(f.operation, fixture.token())
            controller.submit(f.operation, fixture.token())
            runCurrent()
            assertEquals(listOf(f.operation), fixture.port.submissions)
            assertEquals(f.operation, assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation).operationId)
            controller.submit(f.operation, fixture.token())
            runCurrent()
            assertEquals(listOf(f.operation), fixture.port.submissions)
            fixture.port.lookupResult = LocalOperationResult.Pending(f.pending())
            controller.lookup(f.operation, fixture.token())
            runCurrent()
            assertEquals(f.summary, assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation.summary)
            controller.submit(f.operation, fixture.token())
            controller.submit(f.operation, fixture.token())
            runCurrent()
            assertEquals(listOf(f.operation, f.operation), fixture.port.submissions)
            assertEquals(listOf(f.operation), fixture.port.lookups)
            assertEquals(f.operation, controller.state.value.operationId)
            assertTrue(fixture.port.stages.isEmpty())
            assertEquals(0, fixture.port.acknowledgements)
            assertEquals(f.actor, fixture.session.display.actorId)
            // Supplied same-original pending data only; not a real NOT_RECORDED
            // race, serialized Master deduplication or business-effect result.
        }
    }

    @Test fun lsr16_unavailableOriginalLookupCannotBecomeSubmitPermissionOrACompletion() = runTest {
        withSerialController { fixture ->
            fixture.controller.lookup(f.operation, fixture.token())
            runCurrent()
            val result = assertIs<LocalOperationResult.Unresolved>(fixture.controller.state.value.operation)
            assertEquals(f.operation, result.operationId)
            assertEquals(LocalServiceProblem.UNAVAILABLE, result.problem)
            fixture.controller.submit(f.operation, fixture.token())
            fixture.controller.acknowledgeRecorded(f.operation, fixture.token())
            runCurrent()
            assertEquals(f.operation, fixture.controller.state.value.operationId)
            assertTrue(fixture.port.stages.isEmpty())
            assertTrue(fixture.port.submissions.isEmpty())
            assertEquals(0, fixture.port.acknowledgements)
        }
    }

    @Test fun lsr11And17_currentDeniedPendingReadHidesPrivateDraftButKeepsOriginalOperationIdentity() = runTest {
        withSerialController { fixture ->
            fixture.port.lookupResult = LocalOperationResult.Pending(f.pending())
            fixture.controller.lookup(f.operation, fixture.token())
            runCurrent()
            val holder = fixture.model.serialRegistrationDrafts
            holder.editor.value = f.editor().let { it.copy(frozen = it.draft()) }
            holder.original.value = f.id(f.operation.value)
            holder.approvalHeld.value = true
            val captured = holder.lifetime.identity
            var privateEntry = "private transient head-entry input"
            holder.lifetime.attach(captured) { privateEntry = "" }
            fixture.port.pendingResult = LocalPendingResult.Unavailable(LocalServiceProblem.DENIED)
            fixture.controller.refreshConfigurationPending(fixture.token())
            runCurrent()
            assertEquals("", privateEntry)
            assertFalse(holder.lifetime.isCurrent(captured))
            assertTrue(holder.lifetime.accessDenied)
            assertFalse(holder.hasDraft)
            assertNull(holder.original.value)
            assertFalse(holder.approvalHeld.value)
            assertTrue(fixture.controller.state.value.pending.isEmpty())
            assertEquals(f.operation, fixture.controller.state.value.operationId)
            val operation = assertIs<LocalOperationResult.Unresolved>(fixture.controller.state.value.operation)
            assertEquals(f.operation, operation.operationId)
            assertEquals(LocalServiceProblem.DENIED, operation.problem)
            assertTrue(fixture.port.submissions.isEmpty())
            assertTrue(fixture.port.stages.isEmpty())
        }
    }

    @Test fun lsr13And17_signingWorkBlocksBackAndUnresolvedDraftRequiresLeaveConfirmation() = runTest {
        withSerialController { fixture ->
            val holder = fixture.model.serialRegistrationDrafts
            holder.editor.value = f.editor().let { it.copy(frozen = it.draft()) }
            holder.original.value = f.id(f.operation.value)
            fixture.controller.serialRegistrationGuard(draft = true, working = true)
            assertEquals(LeaveGuard.CRITICAL, fixture.runtime.navigator.state.value.guard)
            assertEquals(NavigationResult.BLOCKED,
                fixture.runtime.navigator.navigate(NavigationAction.Back, fixture.token()))
            assertEquals(f.id(f.operation.value), holder.original.value)
            fixture.controller.serialRegistrationGuard(draft = true, working = false)
            assertEquals(LeaveGuard.DIRTY, fixture.runtime.navigator.state.value.guard)
            assertEquals(NavigationResult.CONFIRM_DISCARD,
                fixture.runtime.navigator.navigate(NavigationAction.Back, fixture.token()))
            assertTrue(holder.hasDraft)
            assertTrue(fixture.port.stages.isEmpty())
            assertTrue(fixture.port.submissions.isEmpty())
            // Guard only; no assertion that navigation decides native reservation
            // release, durable outcome, cancellation or saved-original resolution.
        }
    }

    @Test fun lsr13And17_attachmentReplacementRetainsFrozenOriginalAndRejectsLatePrivateLookup() = runTest {
        withSerialController { fixture ->
            val holder = fixture.model.serialRegistrationDrafts
            val editor = f.editor().let { it.copy(frozen = it.draft()) }
            holder.editor.value = editor
            holder.original.value = f.id(f.operation.value)
            holder.approvalHeld.value = true
            val identity = holder.lifetime.identity
            var privateEntry = "private transient head-entry input"
            holder.lifetime.attach(identity) { privateEntry = "" }
            val late = fixture.port.holdNextLookup()
            val old = fixture.controller
            old.lookup(f.operation, fixture.token())
            runCurrent()
            assertEquals(listOf(f.operation), fixture.port.lookups)
            val replacementPort = LsrUiPort()
            val replacement = fixture.replaceAttachment(replacementPort)
            assertFalse(holder.lifetime.isCurrent(identity))
            assertEquals("", privateEntry)
            assertEquals(editor, holder.editor.value)
            assertEquals(f.id(f.operation.value), holder.original.value)
            assertTrue(holder.approvalHeld.value)
            assertTrue(replacement.state.value.attachmentReviewRequired)
            assertTrue(replacement.state.value.pending.isEmpty())
            late.complete(LocalOperationResult.Pending(f.pending()))
            runCurrent()
            assertTrue(replacement.state.value.pending.isEmpty())
            assertFalse(replacement.state.value.operation is LocalOperationResult.Pending)
            assertTrue(replacementPort.lookups.isEmpty())
            assertTrue(replacementPort.submissions.isEmpty())
            assertTrue(replacementPort.stages.isEmpty())
        }
    }

    @Test fun lsr11And17_logoutClearsPrivateSerialEntryAndOldRouteCallbacksCannotAdoptOrSubmit() = runTest {
        withSerialController { fixture ->
            val holder = fixture.model.serialRegistrationDrafts
            holder.editor.value = f.editor().let { it.copy(frozen = it.draft()) }
            holder.original.value = f.id(f.operation.value)
            holder.approvalHeld.value = true
            val identity = holder.lifetime.identity
            val oldToken = fixture.token()
            val old = fixture.controller
            fixture.runtime.signOut()
            runCurrent()
            assertEquals(SecurityRoot.BOOTSTRAP, fixture.runtime.navigator.state.value.root)
            assertFalse(holder.lifetime.isCurrent(identity))
            assertFalse(holder.hasDraft)
            assertNull(holder.original.value)
            assertFalse(holder.approvalHeld.value)
            old.lookup(f.operation, oldToken)
            old.submit(f.operation, oldToken)
            runCurrent()
            assertTrue(fixture.port.lookups.isEmpty())
            assertTrue(fixture.port.submissions.isEmpty())
            assertTrue(fixture.port.stages.isEmpty())
            // Root/entry retirement only; actual other-account recovery scope,
            // durable restart and restored-route quarantine remain UNBOUND.
        }
    }
}
