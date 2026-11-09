@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.supplieroriginallookupv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.SecurityRoot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen SAOL v1 Profile S: actual shared controller/runtime, CONTROLLED PORTS.
 * State selection is not rendered presentation or a displayed-result receipt.
 * These slices do not execute the durable prefixes, grant/ownership enforcement,
 * decoding, persistence, native close/reopen, admitted RPC or installed UI.
 * Original names/active/actor/scope/digest and effect counts remain integration
 * obligations. SAOL-05 positive coexistence and SAOL-06 qualified not-recorded
 * are NOT RUN / route unavailable here; guarded uncertainty is not their PASS.
 */
class SupplierOriginalLookupControllerSupportingTest {
    private val inputs = SupplierOriginalLookupUiInputs

    @Test
    fun saol01EarlierCreateAndUpdateRequireExplicitLookupAndKeepOriginalReferences() = runTest {
        withSupplierOriginalLookupUi {
            val later = assertNotNull(controller.state.value.page).records.single()
            assertEquals(inputs.laterNames, later.title)
            assertTrue(port.lookups.isEmpty())
            for (original in listOf(inputs.resultCreate, inputs.resultUpdate, inputs.resultCreate)) {
                val before = port.lookups.toList()
                enter(original.operationId)
                runCurrent()
                assertEquals(before, port.lookups, "Editing is not an application lookup")
                controller.lookupOriginal(token())
                runCurrent()
                assertEquals(original, controller.state.value.operation)
                assertEquals(original.operationId, controller.state.value.operationId)
                assertFalse(controller.state.value.recordedAcknowledged)
                assertEquals(before + original.operationId, port.lookups)
                if (original.operationId == inputs.create) {
                    assertNotEquals(later.reference, assertNotNull(original.result).reference)
                }
                val ackCount = port.acknowledgements.size
                runCurrent()
                assertEquals(ackCount, port.acknowledgements.size, "Delivery is not ACK")
                controller.acknowledgeRecorded(original.operationId, token())
                runCurrent()
                assertTrue(controller.state.value.recordedAcknowledged)
                assertEquals(ackCount + 1, port.acknowledgements.size)
                assertEquals(later, assertNotNull(controller.state.value.page).records.single())
                port.assertNoMutationCalls()
            }
            assertEquals(listOf(inputs.create, inputs.update, inputs.create), port.lookups)
            assertEquals(port.lookups, port.acknowledgements)
            assertEquals(listOf("synthetic-supplier-owner"), signIns)
        }
    }

    @Test
    fun saol02EarlierRefusalStaysConclusiveInsteadOfBecomingTodaysSupplierOrNewWork() = runTest {
        withSupplierOriginalLookupUi(configure = { it.currentSupplier = inputs.currentSupplier(afterRefusal = true) }) {
            val later = assertNotNull(controller.state.value.page).records.single()
            assertEquals(inputs.afterRefusalNames, later.title)
            lookup(inputs.refuse)
            runCurrent()
            val refused = assertIs<LocalOperationResult.Recorded>(controller.state.value.operation)
            assertEquals(inputs.resultRefuse, refused)
            assertEquals(LocalRecordedDecision.REJECTED, refused.decision)
            assertFalse(controller.state.value.stageUncertain)
            assertTrue(port.acknowledgements.isEmpty())
            controller.acknowledgeRecorded(inputs.refuse, token())
            runCurrent()
            assertEquals(later, assertNotNull(controller.state.value.page).records.single())
            lookup(inputs.refuse)
            runCurrent()
            assertEquals(refused, controller.state.value.operation)
            assertEquals(listOf(inputs.refuse, inputs.refuse), port.lookups)
            assertEquals(listOf(inputs.refuse), port.acknowledgements)
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol03MalformedInputCannotReadOrFallBackToTheLastAcknowledgedOriginal() = runTest {
        withSupplierOriginalLookupUi {
            for (input in listOf("", "not-a-domain-id")) {
                controller.changeOriginalOperationId(input, token())
                controller.lookupOriginal(token())
                runCurrent()
                assertNull(controller.state.value.operationId)
                assertNull(controller.state.value.operation)
                assertNotNull(controller.state.value.originalLookupProblem)
                assertTrue(port.lookups.isEmpty())
            }
            lookup(inputs.create)
            runCurrent()
            controller.acknowledgeRecorded(inputs.create, token())
            runCurrent()
            for (input in listOf("", "not-a-domain-id")) {
                controller.changeOriginalOperationId(input, token())
                controller.lookupOriginal(token())
                controller.acknowledgeRecorded(inputs.create, token())
                runCurrent()
                assertNull(controller.state.value.operationId)
                assertNull(controller.state.value.operation)
                assertNotNull(controller.state.value.originalLookupProblem)
                assertEquals(listOf(inputs.create), port.lookups)
                assertEquals(listOf(inputs.create), port.acknowledgements)
            }
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol03MismatchedOperationOrIncompatibleActionCarrierCannotBeAdoptedOrAcknowledged() = runTest {
        // Owner/scope and malformed wire identities are not fields of this typed UI result.
        val unusable = listOf(
            inputs.resultCreate.copy(operationId = inputs.update),
            inputs.resultCreate.copy(originalAction = null),
            inputs.resultCreate.copy(originalAction = LocalAction.ORDER_CREATE),
            inputs.resultCreate.copy(originalAction = LocalAction.PURCHASE_CREATE),
        )
        for (reply in unusable) withSupplierOriginalLookupUi(configure = { it.lookupReply = { reply } }) {
            lookup(inputs.create)
            runCurrent()
            assertNull(controller.state.value.operationId)
            assertNull(controller.state.value.operation)
            assertNotNull(controller.state.value.originalLookupProblem)
            controller.acknowledgeRecorded(inputs.create, token())
            controller.acknowledgeRecorded(inputs.update, token())
            runCurrent()
            assertEquals(listOf(inputs.create), port.lookups)
            assertTrue(port.acknowledgements.isEmpty())
            assertTrue(controller.state.value.pending.isEmpty())
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol04RecoveryControlDoesNotRequireSupplierReadOrPendingReadSuccess() = runTest {
        // Controlled denial callbacks show independent UI routing, not independent Master grants.
        withSupplierOriginalLookupUi(configure = {
            it.readProblem = LocalServiceProblem.DENIED
            it.pendingReply = LocalPendingResult.Unavailable(LocalServiceProblem.DENIED)
        }) {
            assertNull(controller.state.value.page)
            assertFalse(controller.state.value.pendingLoaded)
            assertTrue(controller.state.value.canLookupOriginal)
            lookup(inputs.create)
            runCurrent()
            assertEquals(inputs.resultCreate, controller.state.value.operation)
            controller.openForm(LocalAction.SUPPLIER_CREATE, token())
            assertNull(controller.state.value.form)
            controller.acknowledgeRecorded(inputs.create, token())
            runCurrent()
            assertEquals(listOf(inputs.create), port.acknowledgements)
            assertNull(controller.state.value.page)
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol04KnownIdCannotBypassCurrentnessOrProtectedDenial() = runTest {
        withSupplierOriginalLookupUi {
            enter(inputs.create)
            val oldToken = token()
            session.current = false
            controller.lookupOriginal(oldToken)
            controller.acknowledgeRecorded(inputs.create, oldToken)
            runCurrent()
            assertTrue(port.lookups.isEmpty())
            assertTrue(port.acknowledgements.isEmpty())
            assertNull(controller.state.value.operation)
            assertEquals(listOf("synthetic-supplier-owner"), signIns)
            port.assertNoMutationCalls()
        }
        for (problem in listOf(LocalServiceProblem.DENIED, LocalServiceProblem.SESSION_ENDED)) {
            withSupplierOriginalLookupUi(configure = {
                it.lookupReply = { id -> LocalOperationResult.Unresolved(id, problem) }
            }) {
                lookup(inputs.create)
                runCurrent()
                assertNull(controller.state.value.operationId)
                assertNull(controller.state.value.operation)
                assertEquals("", controller.state.value.originalLookupInput)
                assertEquals(listOf(inputs.create), port.lookups)
                assertTrue(port.acknowledgements.isEmpty())
                assertEquals(listOf("synthetic-supplier-owner"), signIns)
                if (problem == LocalServiceProblem.SESSION_ENDED) {
                    assertEquals(SecurityRoot.BOOTSTRAP, runtime.navigator.state.value.root)
                } else assertEquals(problem, controller.state.value.readProblem)
                port.assertNoMutationCalls()
            }
        }
    }

    @Test
    fun saol05SelectedUnresolvedOriginalCannotBeReplacedOrAcknowledgedByOlderLookup() = runTest {
        val pending = inputs.pendingOriginal()
        withSupplierOriginalLookupUi(configure = { it.pendingReply = LocalPendingResult.Available(listOf(pending)) }) {
            controller.lookup(inputs.pending, token())
            runCurrent()
            val uncertainty = assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation)
            assertEquals(inputs.pending, uncertainty.operationId)
            enter(inputs.create)
            assertFalse(controller.state.value.canLookupOriginal)
            controller.lookupOriginal(token())
            controller.acknowledgeRecorded(inputs.pending, token())
            controller.acknowledgeRecorded(inputs.create, token())
            runCurrent()
            assertEquals(uncertainty, controller.state.value.operation)
            assertEquals(inputs.pending, controller.state.value.operationId)
            assertEquals(listOf(pending), controller.state.value.pending)
            assertEquals(listOf(inputs.pending), port.lookups)
            assertTrue(port.acknowledgements.isEmpty())
            val continuation = assertNotNull(controller.retireForAttachment())
            assertEquals(inputs.pending, continuation.workspace.operationId)
            assertIs<LocalOperationResult.Unresolved>(continuation.workspace.operation)
            assertEquals("", continuation.workspace.originalLookupInput)
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol05DirtySupplierInputIsNotConsumedByAnOriginalLookup() = runTest {
        withSupplierOriginalLookupUi {
            enter(inputs.create)
            controller.openForm(LocalAction.SUPPLIER_UPDATE, token())
            controller.edit(LocalField.NAME_ENGLISH, LocalDraftValue(inputs.refusedEnglishName), token())
            val draft = assertNotNull(controller.state.value.form)
            assertTrue(draft.dirty)
            assertFalse(controller.state.value.canLookupOriginal)
            controller.lookupOriginal(token())
            runCurrent()
            assertEquals(draft, controller.state.value.form)
            assertNull(controller.state.value.operationId)
            assertTrue(port.lookups.isEmpty())
            assertTrue(port.acknowledgements.isEmpty())
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol06UnavailableOrPendingCallbackIsAReadProblemNotANewPendingOriginal() = runTest {
        val replies = listOf(
            LocalOperationResult.Unresolved(inputs.create, LocalServiceProblem.UNAVAILABLE),
            LocalOperationResult.Unresolved(inputs.create, LocalServiceProblem.STORAGE_UNCERTAIN),
            LocalOperationResult.Pending(inputs.pendingOriginal(inputs.create)),
        )
        for (reply in replies) withSupplierOriginalLookupUi(configure = { it.lookupReply = { reply } }) {
            lookup(inputs.create)
            runCurrent()
            assertNull(controller.state.value.operationId)
            assertNull(controller.state.value.operation)
            assertNotNull(controller.state.value.originalLookupProblem)
            assertFalse(controller.state.value.stageUncertain)
            assertTrue(controller.state.value.pending.isEmpty())
            controller.acknowledgeRecorded(inputs.create, token())
            port.lookupReply = { inputs.resultCreate }
            runCurrent()
            assertEquals(listOf(inputs.create), port.lookups, "Restoring a dependency is not explicit retry")
            assertTrue(port.acknowledgements.isEmpty())
            controller.lookupOriginal(token())
            runCurrent()
            assertEquals(inputs.resultCreate, controller.state.value.operation)
            assertEquals(listOf(inputs.create, inputs.create), port.lookups)
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol06ThrownReadAndCancellationDoNotInventDurableUncertaintyOrAutomaticRetry() = runTest {
        for (cancelled in listOf(false, true)) withSupplierOriginalLookupUi(configure = {
            it.lookupReply = {
                if (cancelled) throw CancellationException("Controlled lookup cancellation")
                else error("Controlled lookup read failure")
            }
        }) {
            lookup(inputs.create)
            runCurrent()
            assertNull(controller.state.value.operationId)
            assertNull(controller.state.value.operation)
            assertNotNull(controller.state.value.originalLookupProblem)
            assertFalse(controller.state.value.stageUncertain)
            assertTrue(controller.state.value.pending.isEmpty())
            assertEquals(listOf(inputs.create), port.lookups)
            assertTrue(port.acknowledgements.isEmpty())
            port.lookupReply = { inputs.resultCreate }
            runCurrent()
            assertEquals(listOf(inputs.create), port.lookups)
            controller.lookupOriginal(token())
            runCurrent()
            assertEquals(inputs.resultCreate, controller.state.value.operation)
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol07EditingLookupAndWrongRepeatedOrStaleAckCannotRetargetSelectedOriginal() = runTest {
        withSupplierOriginalLookupUi {
            lookup(inputs.create)
            runCurrent()
            val createToken = token()
            val retainedCreateAck = { controller.acknowledgeRecorded(inputs.create, createToken) }
            enter(inputs.update)
            runCurrent()
            assertEquals(inputs.resultCreate, controller.state.value.operation)
            assertEquals(listOf(inputs.create), port.lookups)
            assertTrue(port.acknowledgements.isEmpty())
            controller.lookupOriginal(token())
            controller.acknowledgeRecorded(inputs.update, token())
            runCurrent()
            assertEquals(inputs.resultCreate, controller.state.value.operation)
            assertEquals(listOf(inputs.create), port.lookups)
            assertTrue(port.acknowledgements.isEmpty())
            retainedCreateAck()
            runCurrent()
            assertTrue(controller.state.value.recordedAcknowledged)
            retainedCreateAck()
            controller.acknowledgeRecorded(inputs.create, token())
            runCurrent()
            assertEquals(listOf(inputs.create), port.acknowledgements)
            lookup(inputs.update)
            runCurrent()
            retainedCreateAck()
            controller.acknowledgeRecorded(inputs.create, token())
            runCurrent()
            assertEquals(inputs.resultUpdate, controller.state.value.operation)
            assertFalse(controller.state.value.recordedAcknowledged)
            assertEquals(listOf(inputs.create), port.acknowledgements)
            controller.acknowledgeRecorded(inputs.update, token())
            runCurrent()
            lookup(inputs.create)
            runCurrent()
            assertEquals(inputs.resultCreate, controller.state.value.operation)
            assertEquals(listOf(inputs.create, inputs.update), port.acknowledgements)
            assertEquals(listOf(inputs.create, inputs.update, inputs.create), port.lookups)
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol07MismatchedAckReplyDoesNotClearTheKnownOriginalOrRetryIt() = runTest {
        withSupplierOriginalLookupUi(configure = {
            it.acknowledgementReply = { LocalAcknowledgementResult.Acknowledged(inputs.update) }
        }) {
            lookup(inputs.create)
            runCurrent()
            controller.acknowledgeRecorded(inputs.create, token())
            runCurrent()
            assertEquals(inputs.resultCreate, controller.state.value.operation)
            assertFalse(controller.state.value.recordedAcknowledged)
            assertNotNull(controller.state.value.acknowledgementProblem)
            assertEquals(listOf(inputs.create), port.acknowledgements)
            port.acknowledgementReply = { LocalAcknowledgementResult.Acknowledged(it) }
            runCurrent()
            assertEquals(listOf(inputs.create), port.acknowledgements)
            controller.acknowledgeRecorded(inputs.create, token())
            runCurrent()
            assertTrue(controller.state.value.recordedAcknowledged)
            assertEquals(listOf(inputs.create, inputs.create), port.acknowledgements)
            assertEquals(listOf(inputs.create), port.lookups)
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol08LockClearsInputAndFencesLateLookupAndEarlierAckCallback() = runTest {
        withSupplierOriginalLookupUi {
            lookup(inputs.create)
            runCurrent()
            val recordedToken = token()
            val oldAck = { controller.acknowledgeRecorded(inputs.create, recordedToken) }
            controller.acknowledgeRecorded(inputs.create, token())
            runCurrent()
            val held = port.holdNextLookup()
            lookup(inputs.create)
            runCurrent()
            assertTrue(held.entered.isCompleted)
            val oldLookupToken = token()
            runtime.lock(token())
            val locked = controller.state.value
            assertEquals(SecurityRoot.BOOTSTRAP, runtime.navigator.state.value.root)
            assertNull(locked.operation)
            assertNull(locked.operationId)
            assertEquals("", locked.originalLookupInput)
            oldAck()
            controller.lookupOriginal(oldLookupToken)
            held.reply.complete(inputs.resultCreate)
            runCurrent()
            assertTrue(held.returned.isCompleted)
            assertEquals(locked, controller.state.value)
            assertEquals(listOf(inputs.create, inputs.create), port.lookups)
            assertEquals(listOf(inputs.create), port.acknowledgements)
            assertEquals(listOf("synthetic-supplier-owner"), signIns)
            port.assertNoMutationCalls()
        }
    }

    @Test
    fun saol08EntryRetirementDoesNotRetainAReadAsNewUnresolvedBusinessOrAdoptLateReply() = runTest {
        withSupplierOriginalLookupUi {
            val held = port.holdNextLookup()
            lookup(inputs.create)
            runCurrent()
            assertTrue(held.entered.isCompleted)
            assertNull(controller.state.value.operationId)
            val oldToken = token()
            val continuation = assertNotNull(controller.retireForAttachment())
            assertNull(continuation.workspace.operationId)
            assertNull(continuation.workspace.operation)
            assertNull(continuation.frozen)
            assertEquals("", continuation.workspace.originalLookupInput)
            assertNull(continuation.workspace.originalLookupProblem)
            val retired = controller.state.value
            held.reply.complete(inputs.resultCreate)
            controller.acknowledgeRecorded(inputs.create, oldToken)
            controller.lookupOriginal(oldToken)
            runCurrent()
            assertTrue(held.returned.isCompleted)
            assertEquals(retired, controller.state.value)
            assertNull(retired.operation)
            assertEquals(listOf(inputs.create), port.lookups)
            assertTrue(port.acknowledgements.isEmpty())
            assertEquals(listOf("synthetic-supplier-owner"), signIns)
            port.assertNoMutationCalls()
        }
    }
}
