@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.pay10paymentcorrectionsui

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Production-controller slices of PAY-10; every service reply and custody store is controlled. */
class Pay10PaymentCorrectionUiControllerTest {
    @Test fun pcui03EmptyEntryPromptsExactLookupAndCannotStartOtherCommerce() = runTest {
        withPay10UiController {
            val original = controller.state.value
            val page = assertNotNull(original.page)
            assertEquals(Pay10UiInputs.emptyPrompt, page.notice?.english)
            assertEquals(emptyList(), page.forms)
            assertEquals(LocalWorkflowQuery(LocalWorkflow.CHECKOUT), port.reads.single())
            val before = port.callCounts()
            for (workflow in LocalWorkflow.entries - setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS)) {
                controller.selectWorkflow(workflow, token())
            }
            for (action in LocalAction.entries - LocalAction.PAYMENT_CLASSIFICATION_CORRECT) controller.openForm(action, token())
            controller.openRetailCollection(token())
            controller.stage(token())
            runCurrent()
            assertEquals(original, controller.state.value)
            assertEquals(before, port.callCounts())
        }
    }

    @Test fun pcui04And06ExactLookupThenPaymentSelectionClearsSearchBeforeHistoryRead() = runTest {
        withPay10UiController(configure = { it.includeCorrection = true }) {
            controller.changeSearch(Pay10UiInputs.search, token())
            controller.refresh(token())
            runCurrent()
            assertEquals(LocalWorkflowQuery(LocalWorkflow.CHECKOUT, Pay10UiInputs.search), port.reads.last())
            val originalPayment = assertNotNull(controller.state.value.page).records.first()
            val originalClose = controller.state.value.page?.records?.last()
            controller.selectRecord(Pay10UiInputs.history, token())
            assertEquals("", controller.state.value.search)
            runCurrent()
            assertEquals(LocalWorkflowQuery(LocalWorkflow.CHECKOUT, reference = Pay10UiInputs.history), port.reads.last())
            val history = assertNotNull(controller.state.value.page)
            assertEquals(originalPayment, history.records.first())
            assertEquals(originalClose, history.records[1])
            assertEquals("10000", history.records.first().values.first().value.english)
            assertEquals(listOf("100000", "110000", "+10000"), history.records[1].values.map { it.value.english })
            assertEquals(listOf("-10000", "+10000", "0"), history.records[2].values.take(3).map { it.value.english })
            assertFalse(history.records[1].selectable)
            assertFalse(history.records[2].selectable)
            assertTrue(assertNotNull(history.notice).english.contains("50000"))
            assertEquals(LocalAction.PAYMENT_CLASSIFICATION_CORRECT, history.forms.single().action)
            assertTrue(port.stages.isEmpty())
            assertTrue(port.submissions.isEmpty())
        }
    }

    @Test fun pcui05And06CanonicalSourceAndHistoryReferencesReadTheirExactOriginal() = runTest {
        for (reference in listOf(Pay10UiInputs.source, Pay10UiInputs.history)) {
            withPay10UiController(reference = reference) {
                assertEquals(LocalWorkflowQuery(LocalWorkflow.CHECKOUT, reference = reference), port.reads.single())
                assertEquals(reference, controller.state.value.reference)
                assertEquals(Pay10UiInputs.originalPayment(), assertNotNull(controller.state.value.page).records.first())
                assertEquals(Pay10UiInputs.originalClose(), controller.state.value.page?.records?.get(1))
                assertEquals(0, port.stages.size)
                assertEquals(0, port.submissions.size)
            }
        }
    }

    @Test fun pcui07InvalidSearchAndMixedStoredSelectionCannotReachAnyReadOrMutation() = runTest {
        withPay10UiController {
            val readsBefore = port.reads.size
            val pendingBefore = port.pendingReads.size
            for (search in listOf("all", "order:${Pay10UiInputs.sale.value}", "manager:${Pay10UiInputs.sale.value}",
                "payment-correction:not-a-uuid", "${Pay10UiInputs.search} ")) {
                controller.changeSearch(search, token())
                controller.refresh(token())
                runCurrent()
                assertEquals(LocalServiceProblem.INVALID_INPUT, controller.state.value.readProblem, search)
                assertNull(controller.state.value.page)
            }
            assertEquals(readsBefore, port.reads.size)
            assertEquals(pendingBefore, port.pendingReads.size)
            assertEquals(0, port.stages.size)
        }
        for (reference in listOf(Pay10UiInputs.source, Pay10UiInputs.history)) {
            val mixed = LocalWorkflowContinuation(LocalWorkspaceState(search = Pay10UiInputs.search, reference = reference), null, null, null, null)
            withPay10UiController(continuation = mixed) {
                assertEquals(LocalServiceProblem.INVALID_INPUT, controller.state.value.readProblem)
                assertEquals(reference, controller.state.value.reference)
                assertEquals(Pay10UiInputs.search, controller.state.value.search)
                assertEquals(listOf(0, 0, 0, 0, 0), port.callCounts())
            }
        }
        for (reference in listOf(Pay10UiInputs.source.copy(revision = Revision(1)),
            Pay10UiInputs.source.copy(parentId = Pay10UiInputs.payment), Pay10UiInputs.history.copy(parentId = null))) {
            withPay10UiController(reference = reference) { assertEquals(listOf(0, 0, 0, 0, 0), port.callCounts()) }
        }
    }

    @Test fun pcui02UnrelatedGatewayRecordsAndEditableOriginalFieldsAreNotAdopted() = runTest {
        val injected = listOf(
            LocalWorkflowPage(LocalWorkflow.CHECKOUT, listOf(LocalRecord(LocalRecordReference(LocalRecordKind.ORDER, Pay10UiInputs.sale), Pay10UiInputs.text("Unrelated checkout"))), emptyList()),
            Pay10UiInputs.historyPage(LocalWorkflow.CHECKOUT).copy(forms = listOf(Pay10UiInputs.form().copy(action = LocalAction.ORDER_CREATE))),
            Pay10UiInputs.historyPage(LocalWorkflow.CHECKOUT).copy(forms = listOf(Pay10UiInputs.form().copy(fields = Pay10UiInputs.form().fields.map {
                if (it.field == LocalField.AMOUNT) (it as LocalFormField.Scalar).copy(readOnly = false) else it
            }))))
        for (page in injected) {
            withPay10UiController(reference = Pay10UiInputs.history, configure = { port -> port.readReply = { LocalReadResult.Available(page) } }) {
                assertNull(controller.state.value.page)
                assertEquals(LocalServiceProblem.UNAVAILABLE, controller.state.value.readProblem)
                controller.openForm(LocalAction.PAYMENT_CLASSIFICATION_CORRECT, token())
                controller.openForm(LocalAction.ORDER_CREATE, token())
                controller.stage(token())
                runCurrent()
                assertNull(controller.state.value.form)
                assertEquals(0, port.stages.size)
            }
        }
    }

    @Test fun pcui09And10StageFreezesFullOriginalInputBeforeSeparateSubmit() = runTest {
        withPay10UiController(reference = Pay10UiInputs.history) {
            val originalClose = assertNotNull(controller.state.value.page).records[1]
            enterEvidence()
            controller.stage(token())
            runCurrent()
            assertEquals(LocalField.CONFIRMED, controller.state.value.issue?.field)
            assertEquals(0, port.stages.size)
            controller.confirmForm(true, token())
            controller.stage(token())
            val originalId = assertNotNull(controller.state.value.operationId)
            assertEquals(0, port.submissions.size)
            runCurrent()
            val saved = port.stages.single()
            assertEquals(originalId, saved.operationId)
            assertOriginalInput(saved)
            assertTrue(controller.state.value.formFrozen)
            controller.edit(LocalField.AMOUNT, LocalDraftValue("100.01"), token())
            controller.edit(LocalField.REASON, LocalDraftValue("changed after staging"), token())
            controller.discardForm(token())
            controller.selectRecord(null, token())
            controller.changeSearch("payment-correction:${Pay10UiInputs.id(30).value}", token())
            controller.stage(token())
            assertEquals(originalId, controller.state.value.operationId)
            assertOriginalInput(port.stages.single())
            assertEquals(originalClose, assertNotNull(controller.state.value.page).records[1])
            controller.submit(originalId, token())
            controller.submit(originalId, token())
            runCurrent()
            assertEquals(listOf(Pay10UiInputs.manager to originalId), port.submissions)
            assertEquals(1, port.stages.size)
            assertEquals(originalId, assertNotNull(controller.state.value.operation as? LocalOperationResult.Recorded).operationId)
        }
    }

    @Test fun pcui11And12UncertainStageRetriesTheSameBodyAndNeverAcceptsAmount10001() = runTest {
        withPay10UiController(reference = Pay10UiInputs.history,
            configure = { port -> port.stageReply = { LocalStageResult.Uncertain(it.operationId) } }) {
            enterEvidence()
            controller.confirmForm(true, token())
            controller.stage(token())
            runCurrent()
            val original = port.stages.single()
            assertOriginalInput(original)
            assertTrue(controller.state.value.stageUncertain)
            controller.edit(LocalField.AMOUNT, LocalDraftValue("100.01"), token())
            controller.edit(LocalField.REASON, LocalDraftValue("replacement reason"), token())
            controller.retryStage(token())
            runCurrent()
            assertEquals(2, port.stages.size)
            assertSame(original, port.stages.last())
            assertOriginalInput(port.stages.last())
            assertEquals(original.operationId, controller.state.value.operationId)
            assertEquals(0, port.submissions.size)
            assertEquals(0, port.lookups.size)
        }
    }

    @Test fun pcui11RestoredFrozenOriginalIsLookupOnlyUntilItsCurrentResultIsRead() = runTest {
        val original = Pay10UiInputs.intent()
        val pending = Pay10UiInputs.pending(original.operationId, submitAllowed = false).copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED)
        val continuation = LocalWorkflowContinuation(LocalWorkspaceState(reference = Pay10UiInputs.history,
            operationId = original.operationId, operation = LocalOperationResult.Unresolved(original.operationId, LocalServiceProblem.UNAVAILABLE),
            attachmentReviewRequired = true), original, original.action, null, null)
        withPay10UiController(continuation = continuation, configure = { port ->
            port.pendingReply = LocalPendingResult.Available(listOf(pending)); port.offerForm = false
        }) {
            assertTrue(controller.state.value.attachmentReviewRequired)
            assertEquals(listOf(0, 0, 0, 0, 0), port.callCounts())
            controller.submit(original.operationId, token())
            controller.stage(token())
            runCurrent()
            assertEquals(0, port.submissions.size)
            controller.reviewAttachment(token())
            runCurrent()
            assertFalse(controller.state.value.attachmentReviewRequired)
            assertEquals(original.operationId, controller.state.value.operationId)
            controller.submit(original.operationId, token())
            controller.lookup(original.operationId, token())
            runCurrent()
            assertEquals(listOf(Pay10UiInputs.manager to original.operationId), port.lookups)
            assertEquals(0, port.stages.size)
            assertEquals(0, port.submissions.size)
            assertOriginalInput(original)
            val result = assertNotNull(controller.state.value.operation as? LocalOperationResult.Recorded)
            assertEquals(original.operationId, result.operationId)
            assertEquals(Pay10UiInputs.history, result.result?.reference)
        }
    }

    @Test fun pcui13CurrentHistoryDenialClearsTheOldPageDespiteRetainedDisplayHints() = runTest {
        withPay10UiController(reference = Pay10UiInputs.history) {
            assertNotNull(controller.state.value.page)
            val display = session.display
            port.readReply = { LocalReadResult.Unavailable(LocalServiceProblem.DENIED) }
            controller.refresh(token())
            runCurrent()
            assertEquals(display, session.display)
            assertEquals(LocalServiceProblem.DENIED, controller.state.value.readProblem)
            assertNull(controller.state.value.page)
            controller.openForm(LocalAction.PAYMENT_CLASSIFICATION_CORRECT, token())
            controller.stage(token())
            runCurrent()
            assertNull(controller.state.value.form)
            assertEquals(0, port.stages.size)
            assertEquals(0, port.submissions.size)
        }
    }

    @Test fun pcui14And15MutationDenialRetainsOriginalIdentityForSeparateCurrentRecovery() = runTest {
        withPay10UiController(reference = Pay10UiInputs.history,
            configure = { port -> port.submitReply = { LocalOperationResult.Unresolved(it, LocalServiceProblem.DENIED) } }) {
            enterEvidence()
            controller.confirmForm(true, token())
            controller.stage(token())
            runCurrent()
            val saved = port.stages.single()
            controller.submit(saved.operationId, token())
            runCurrent()
            assertEquals(saved.operationId, controller.state.value.operationId)
            assertEquals(LocalServiceProblem.DENIED, (controller.state.value.operation as? LocalOperationResult.Unresolved)?.problem)
            assertNull(controller.state.value.page)
            controller.submit(saved.operationId, token())
            assertEquals(1, port.submissions.size)
            port.lookupReply = { LocalOperationResult.Unresolved(it, LocalServiceProblem.DENIED) }
            controller.lookup(saved.operationId, token())
            runCurrent()
            assertNull(controller.state.value.operation as? LocalOperationResult.Recorded)
            assertEquals(saved.operationId, controller.state.value.operationId)
            port.lookupReply = Pay10UiInputs::recorded
            controller.lookup(saved.operationId, token())
            runCurrent()
            assertEquals(listOf(Pay10UiInputs.manager to saved.operationId, Pay10UiInputs.manager to saved.operationId), port.lookups)
            assertNotNull(controller.state.value.operation as? LocalOperationResult.Recorded)
            assertEquals(1, port.stages.size)
            assertEquals(1, port.submissions.size)
            assertOriginalInput(saved)
        }
    }

    @Test fun pcui17UnrelatedContinuationsStayInertAndRetainTheirOriginalActionBody() = runTest {
        val workflows = LocalWorkflow.entries.filter { it !in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS) } +
            listOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS)
        for (workflow in workflows) {
            val action = LocalAction.entries.first { it != LocalAction.PAYMENT_CLASSIFICATION_CORRECT && it.canAppearIn(workflow) }
            val original = LocalCommandIntent(Pay10UiInputs.originalOperation, action, 1,
                LocalRecordReference(LocalRecordKind.ORDER, Pay10UiInputs.id(40)),
                listOf(LocalScalarInput(LocalField.REASON, LocalScalarValue.Text("Unresolved original ${action.name}"))))
            val pending = Pay10UiInputs.pending(original.operationId, action)
            val continuation = LocalWorkflowContinuation(LocalWorkspaceState(workflow = workflow, reference = original.reference,
                pending = listOf(pending), pendingLoaded = true, operationId = original.operationId,
                operation = LocalOperationResult.Pending(pending)), original, action, null, null)
            withPay10UiController(continuation = continuation) {
                assertFalse(controller.paymentCorrectionWorkspaceCompatible(), workflow.name)
                val before = controller.state.value
                controller.changeSearch(Pay10UiInputs.search, token())
                controller.selectWorkflow(LocalWorkflow.CHECKOUT, token())
                controller.selectRecord(Pay10UiInputs.history, token())
                controller.openForm(LocalAction.PAYMENT_CLASSIFICATION_CORRECT, token())
                controller.stage(token())
                controller.submit(original.operationId, token())
                controller.lookup(original.operationId, token())
                controller.reviewAttachment(token())
                runCurrent()
                assertEquals(before, controller.state.value)
                assertEquals(listOf(0, 0, 0, 0, 0), port.callCounts())
                val retained = assertNotNull(controller.retireForAttachment())
                assertSame(original, retained.frozen)
                assertEquals(action, retained.originalAction)
                assertEquals(original.operationId, retained.workspace.operationId)
                assertEquals("Unresolved original ${action.name}", (original.values.single().value as LocalScalarValue.Text).value)
            }
        }
    }

    @Test fun pcui17MixedUnrelatedPendingRowsAreNotExposedAdoptedOrDiscarded() = runTest {
        val originals = LocalPendingResult.Available(listOf(Pay10UiInputs.pending(Pay10UiInputs.originalOperation),
            Pay10UiInputs.pending(com.ukkera.pos.types.OperationId(Pay10UiInputs.id(50).value), LocalAction.ORDER_CREATE)))
        withPay10UiController(configure = { it.pendingReply = originals }) {
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, controller.state.value.pendingProblem)
            assertFalse(controller.state.value.pendingLoaded)
            assertTrue(controller.state.value.pending.isEmpty())
            controller.lookup(Pay10UiInputs.originalOperation, token())
            controller.submit(Pay10UiInputs.originalOperation, token())
            controller.stage(token())
            runCurrent()
            assertSame(originals, port.pendingReply)
            assertEquals(2, originals.operations.size)
            assertEquals(0, port.lookups.size)
            assertEquals(0, port.stages.size)
            assertEquals(0, port.submissions.size)
        }
    }

    @Test fun pcui12And16CredentialExitAndEmployeeSwitchCannotAdoptStoredOriginalIntent() = runTest {
        withPay10UiController(reference = Pay10UiInputs.history,
            configure = { port -> port.stageReply = { LocalStageResult.Uncertain(it.operationId) } }) {
            enterEvidence()
            controller.confirmForm(true, token())
            controller.stage(token())
            runCurrent()
            val saved = port.stages.single()
            val oldToken = token()
            val originalCalls = port.callCounts()
            runtime.signOut()
            runCurrent()
            assertEquals(SecurityRoot.BOOTSTRAP, runtime.navigator.state.value.root)
            assertFalse(session.isCurrent())
            assertEquals(1, session.logoutCalls)
            assertTrue(runtime.presentation.value.cleanupNeedsAttention)
            assertOriginalInput(saved)
            assertEquals(originalCalls, port.callCounts())
            val otherPort = Pay10UiWorkflowPort(Pay10UiInputs.otherEmployee)
            login.nextSession = Pay10UiSession(otherPort)
            val secret = charArrayOf('s', 'y', 'n')
            runtime.openSignIn(false, token())
            runtime.signIn("synthetic-other-employee", secret, token())
            assertTrue(secret.all { it == '\u0000' })
            assertEquals(Pay10UiInputs.otherEmployee, runtime.presentation.value.display?.actorId)
            controller.retryStage(oldToken)
            controller.submit(saved.operationId, oldToken)
            controller.lookup(saved.operationId, oldToken)
            runCurrent()
            assertEquals(originalCalls, port.callCounts())
            assertEquals(listOf(0, 0, 0, 0, 0), otherPort.callCounts())
            assertOriginalInput(saved)
            assertEquals(LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY, runtime.presentation.value.display?.workspaceMode)
        }
    }
}
