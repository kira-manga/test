@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.counterreceiptrpc

import com.ukkera.pos.app.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Controlled-port S1 SUPPORT, not whole CRRPC cases or native/durable/admitted-RPC outcomes. */
class CounterReceiptControllerSupportTest {
    @Test
    fun crrpc003And010RecordEntryAndExplicitDocumentaryEntryKeepExactSelectors() = runTest {
        withCounterReceiptUi {
            assertEquals(LocalWorkflow.CHECKOUT, port.reads.single().workflow)
            assertNull(port.reads.single().returns) // RECORD's omitted-purpose UI selector, not a protected grant.
            controller.changeSearch(CounterReceiptUiInput.search, token())
            assertEquals(1, port.reads.size)
            controller.refresh(token())
            runCurrent()
            assertEquals(CounterReceiptUiInput.search, port.reads.last().search)
            assertNull(port.reads.last().returns)

            controller.selectWorkflow(LocalWorkflow.ORDERS, token())
            runCurrent()
            controller.changeSearch(CounterReceiptUiInput.eventSearch, token())
            controller.refresh(token())
            runCurrent()
            assertEquals(LocalWorkflow.ORDERS, port.reads.last().workflow)
            assertEquals(CounterReceiptUiInput.eventSearch, port.reads.last().search)
            assertNull(port.reads.last().returns)

            controller.selectWorkflow(LocalWorkflow.RETURNS, token())
            runCurrent()
            assertEquals(LocalReturnSection.RECEIPTS, port.reads.last().returns?.section)
            controller.changeSearch(CounterReceiptUiInput.eventSearch, token())
            controller.refresh(token())
            runCurrent()
            assertEquals(CounterReceiptUiInput.eventSearch, port.reads.last().search)
            assertEquals(LocalReturnSection.RECEIPTS, port.reads.last().returns?.section)
            val calls = port.callCounts()
            controller.selectReturns(LocalReturnUiQuery(LocalReturnSection.CASES), token())
            controller.openForm(LocalAction.RETAIL_CUSTOMER_RECEIPT, token())
            controller.stage(token())
            runCurrent()
            assertEquals(calls, port.callCounts())
            assertNull(controller.state.value.form)
            assertTrue(controller.state.value.page?.forms.isNullOrEmpty())
        }
    }

    @Test
    fun crrpc003DeniedRecordNeverRetriesAsDocumentaryLookup() = runTest {
        withCounterReceiptUi(reference = CounterReceiptUiInput.head,
            configure = { it.readReply = { LocalReadResult.Unavailable(LocalServiceProblem.DENIED) } }) {
            assertEquals(1, port.reads.size)
            assertTrue(port.reads.all { it.workflow == LocalWorkflow.CHECKOUT && it.returns == null })
            assertNull(controller.state.value.page)
            assertNull(controller.state.value.form)
            assertTrue(port.stages.isEmpty() && port.submissions.isEmpty() && port.acknowledgements.isEmpty())
        }
    }

    @Test
    fun crrpc002ReceiptControllerDoesNotActivateUnrelatedWorkOrPhysicalCollection() = runTest {
        withCounterReceiptUi {
            val calls = port.callCounts()
            for (workflow in LocalWorkflow.entries.filterNot { it in setOf(LocalWorkflow.CHECKOUT, LocalWorkflow.ORDERS, LocalWorkflow.RETURNS) }) {
                controller.selectWorkflow(workflow, token())
            }
            for (action in LocalAction.entries.filterNot { it == LocalAction.RETAIL_CUSTOMER_RECEIPT }) {
                controller.openForm(action, token())
            }
            controller.selectRecord(LocalRecordReference(LocalRecordKind.ORDER, CounterReceiptUiInput.sale), token())
            controller.openRetailCollection(token())
            runCurrent()
            assertEquals(calls, port.callCounts())
            assertEquals(LocalWorkflow.CHECKOUT, controller.state.value.workflow)
            assertNull(controller.state.value.reference)
            assertNull(controller.state.value.form)
            val physical = controller.retailPhysical.state.value
            assertFalse(physical.pendingLoaded)
            assertNull(physical.payment)
            assertNull(physical.preview)
            assertNull(physical.instruction)
            assertNull(physical.capture)
            assertTrue(physical.saved.isEmpty() && physical.facts.isEmpty() && physical.dues.isEmpty())
        }
    }

    @Test
    fun crrpc011PageTwoEditorKeepsSixteenOriginalPinsAndNoQuantityOrTimeDefault() = runTest {
        val pageIds = (17..32).map(CounterReceiptUiInput::page33Id)
        val selected = CounterReceiptUiInput.batch(pageIds.first())
        val initial = pageIds.map { CounterReceiptUiInput.line(it, CounterReceiptUiInput.itemUnit, 0) }
        withCounterReceiptUi(reference = selected, configure = { port ->
            port.readReply = { query -> LocalReadResult.Available(LocalWorkflowPage(query.workflow,
                pageIds.map { id -> CounterReceiptUiInput.record(LocalRecordReference(
                    LocalRecordKind.CUSTOMER_RECEIPT_PORTION, id, Revision(0), CounterReceiptUiInput.sale)) },
                listOf(CounterReceiptUiInput.form(initial)))) }
        }) {
            assertEquals(selected, port.reads.single().reference)
            assertEquals(CounterReceiptUiInput.page33Id(17), selected.id)
            assertEquals(CounterReceiptUiInput.sale, selected.parentId)
            assertEquals(Revision(0), selected.revision)
            controller.openForm(LocalAction.RETAIL_CUSTOMER_RECEIPT, token())
            val form = assertNotNull(controller.state.value.form)
            assertEquals(16, form.lines.size)
            assertEquals(pageIds, form.lines.map { it.lineId })
            for (row in form.lines) {
                assertEquals(row.lineId.value, row.values.getValue(LocalField.PORTION_ID).text)
                assertEquals(CounterReceiptUiInput.itemUnit.value, row.values.getValue(LocalField.UNIT).text)
                assertEquals("0", row.values.getValue(LocalField.QUANTITY_SCALE).text)
                assertEquals("", row.values.getValue(LocalField.QUANTITY).text)
                controller.edit(LocalField.PORTION_ID, LocalDraftValue(CounterReceiptUiInput.kg.value), token(), row.lineId)
                controller.edit(LocalField.UNIT, LocalDraftValue(CounterReceiptUiInput.kgUnit.value), token(), row.lineId)
                controller.edit(LocalField.QUANTITY_SCALE, LocalDraftValue("3"), token(), row.lineId)
            }
            assertEquals("", form.values.getValue(LocalField.CUSTOMER_RECEIVED_AT).text)
            assertNull(form.values.getValue(LocalField.CUSTOMER_RECEIPT_TIME_ZONE).selected)
            controller.addLine(token())
            controller.duplicateLine(pageIds.first(), token())
            assertEquals(form.lines, controller.state.value.form?.lines)
            controller.confirmForm(true, token())
            controller.stage(token())
            runCurrent()
            assertTrue(port.stages.isEmpty() && port.submissions.isEmpty())
        }
    }

    @Test
    fun crrpc012And014ExplicitBatchStageIsOneFrozenIntentAndSubmitIsSeparate() = runTest {
        withCounterReceiptUi(reference = CounterReceiptUiInput.batch) {
            enterE1()
            assertTrue(port.stages.isEmpty() && port.submissions.isEmpty())
            controller.stage(token())
            runCurrent()
            val original = port.stages.single()
            assertE1Input(original)
            assertEquals(original.operationId, controller.state.value.operationId)
            assertIs<LocalOperationResult.Pending>(controller.state.value.operation)
            assertTrue(port.submissions.isEmpty() && port.acknowledgements.isEmpty())
            controller.edit(LocalField.QUANTITY, LocalDraftValue("5"), token(), CounterReceiptUiInput.item)
            controller.edit(LocalField.CUSTOMER_RECEIVED_AT, LocalDraftValue("2026-11-03T01:00:00Z"), token())
            controller.changeSearch(CounterReceiptUiInput.eventSearch, token())
            controller.selectWorkflow(LocalWorkflow.RETURNS, token())
            controller.stage(token())
            runCurrent()
            assertEquals(1, port.stages.size)
            assertE1Input(port.stages.single())

            controller.submit(original.operationId, token())
            runCurrent()
            assertEquals(listOf(original.operationId), port.submissions)
            assertEquals(original.operationId, assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation).operationId)
            controller.submit(original.operationId, token())
            controller.acknowledgeRecorded(original.operationId, token())
            runCurrent()
            assertEquals(listOf(original.operationId), port.submissions)
            assertTrue(port.acknowledgements.isEmpty())
        }
    }

    @Test
    fun crrpc014And022UncertainStageCanOnlyRetryTheSameFrozenBodyAndNeverAutoSubmit() = runTest {
        withCounterReceiptUi(reference = CounterReceiptUiInput.batch,
            configure = { it.stageReply = { LocalStageResult.Uncertain(CounterReceiptUiInput.e2) } }) {
            enterE1()
            controller.stage(token())
            runCurrent()
            val original = port.stages.single()
            assertTrue(controller.state.value.stageUncertain)
            assertEquals(original.operationId, controller.state.value.operationId)
            assertEquals(original.operationId, assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation).operationId)
            controller.retryStage(token())
            runCurrent()
            assertEquals(2, port.stages.size)
            assertSame(original, port.stages.last())
            assertE1Input(port.stages.last())
            assertTrue(port.submissions.isEmpty() && port.acknowledgements.isEmpty())
        }
    }

    @Test
    fun crrpc019And022MismatchedOriginalLookupCannotAckOrSubstituteLatestIdentity() = runTest {
        withCounterReceiptUi(continuation = CounterReceiptUiInput.unresolvedContinuation(), configure = {
            it.lookupReply = { CounterReceiptUiInput.recorded().copy(operationId = CounterReceiptUiInput.e2) }
        }) {
            assertTrue(port.stages.isEmpty() && port.submissions.isEmpty() && port.lookups.isEmpty())
            controller.lookup(CounterReceiptUiInput.e1, token())
            runCurrent()
            assertEquals(listOf(CounterReceiptUiInput.e1), port.lookups)
            assertEquals(CounterReceiptUiInput.e1, controller.state.value.operationId)
            assertEquals(CounterReceiptUiInput.e1, assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation).operationId)
            controller.lookup(CounterReceiptUiInput.e2, token())
            controller.submit(CounterReceiptUiInput.e1, token())
            controller.acknowledgeRecorded(CounterReceiptUiInput.e1, token())
            runCurrent()
            assertEquals(listOf(CounterReceiptUiInput.e1), port.lookups)
            assertTrue(port.submissions.isEmpty() && port.acknowledgements.isEmpty())
            assertEquals(CounterReceiptUiInput.batch, controller.state.value.reference)
        }
    }

    @Test
    fun crrpc028PreAdmittedOriginalResultNeedsExplicitAckBeforeExactEventRead() = runTest {
        withCounterReceiptUi(continuation = CounterReceiptUiInput.unresolvedContinuation(), configure = {
            it.lookupReply = { CounterReceiptUiInput.recorded() }
        }) {
            controller.lookup(CounterReceiptUiInput.e1, token())
            runCurrent()
            assertIs<LocalOperationResult.Recorded>(controller.state.value.operation)
            assertEquals(CounterReceiptUiInput.batch, controller.state.value.reference)
            assertTrue(port.acknowledgements.isEmpty() && port.submissions.isEmpty())
            controller.acknowledgeRecorded(CounterReceiptUiInput.e2, token())
            runCurrent()
            assertTrue(port.acknowledgements.isEmpty())
            controller.acknowledgeRecorded(CounterReceiptUiInput.e1, token())
            runCurrent()
            assertEquals(listOf(CounterReceiptUiInput.e1), port.acknowledgements)
            assertEquals(CounterReceiptUiInput.event, controller.state.value.reference)
            assertEquals(CounterReceiptUiInput.event, port.reads.last().reference)
            assertEquals("", port.reads.last().search)
            assertNull(port.reads.last().returns)
            assertTrue(controller.state.value.recordedAcknowledged)
            assertTrue(port.stages.isEmpty() && port.submissions.isEmpty())
        }
    }

    @Test
    fun crrpc002And023IncompatibleRetainedFormInputFrozenActionAndOriginalActionRemainInert() = runTest {
        val original = CounterReceiptUiInput.unresolvedContinuation()
        val oldForm = LocalActionForm(LocalAction.ORDER_CREATE, 1, CounterReceiptUiInput.text("Unrelated original editor"), emptyList())
        val oldDraft = localFormDraft(oldForm)
        val oldIntent = LocalCommandIntent(CounterReceiptUiInput.e1, LocalAction.ORDER_CREATE, 1, CounterReceiptUiInput.batch, emptyList())
        val variants = listOf(
            original.copy(workspace = original.workspace.copy(form = oldDraft)),
            original.copy(workspace = original.workspace.copy(formInput = oldDraft.inputContinuation())),
            original.copy(frozen = oldIntent),
            original.copy(originalAction = LocalAction.ORDER_CREATE),
            original.copy(workspace = original.workspace.copy(pending = listOf(LocalPendingOperation(CounterReceiptUiInput.e1,
                LocalAction.ORDER_CREATE, CounterReceiptUiInput.text("Unrelated saved original"), submitAllowed = true)))),
            original.copy(workspace = original.workspace.copy(page = LocalWorkflowPage(LocalWorkflow.CHECKOUT,
                listOf(CounterReceiptUiInput.record(LocalRecordReference(LocalRecordKind.ORDER, CounterReceiptUiInput.sale))), emptyList()))),
            original.copy(workspace = original.workspace.copy(operation = LocalOperationResult.Recorded(CounterReceiptUiInput.e1,
                LocalRecordedDecision.APPLIED, CounterReceiptUiInput.text("Unrelated result")))),
            original.copy(workspace = original.workspace.copy(workflow = LocalWorkflow.RETURNS,
                returnQuery = LocalReturnUiQuery(LocalReturnSection.CASES))),
        )
        for ((index, retained) in variants.withIndex()) {
            withCounterReceiptUi(continuation = retained) {
                assertEquals(listOf(0, 0, 0, 0, 0, 0), port.callCounts(), "variant $index activation")
                controller.refresh(token())
                controller.changeSearch(CounterReceiptUiInput.search, token())
                controller.selectWorkflow(LocalWorkflow.ORDERS, token())
                controller.selectRecord(CounterReceiptUiInput.head, token())
                controller.openForm(LocalAction.RETAIL_CUSTOMER_RECEIPT, token())
                controller.discardForm(token())
                controller.confirmForm(true, token())
                controller.stage(token())
                controller.retryStage(token())
                controller.submit(CounterReceiptUiInput.e1, token())
                controller.lookup(CounterReceiptUiInput.e1, token())
                controller.acknowledgeRecorded(CounterReceiptUiInput.e1, token())
                controller.reviewAttachment(token())
                runCurrent()
                assertEquals(listOf(0, 0, 0, 0, 0, 0), port.callCounts(), "variant $index attempted callbacks")
                assertEquals(retained.workspace.workflow, controller.state.value.workflow)
                assertEquals(retained.workspace.form, controller.state.value.form)
                assertEquals(retained.workspace.formInput, controller.state.value.formInput)
                assertEquals(CounterReceiptUiInput.e1, controller.state.value.operationId)
                val retired = assertNotNull(controller.retireForAttachment())
                assertEquals(CounterReceiptUiInput.e1, retired.workspace.operationId)
                assertSame(retained.frozen, retired.frozen)
                assertEquals(retained.originalAction, retired.originalAction)
            }
        }
    }

    @Test
    fun crrpc023EndedOwnerSessionCannotDispatchOriginalThroughCapturedCallbacks() = runTest {
        withCounterReceiptUi(continuation = CounterReceiptUiInput.unresolvedContinuation()) {
            val oldCallback = token()
            val before = port.callCounts()
            session.close()
            controller.submit(CounterReceiptUiInput.e1, oldCallback)
            controller.lookup(CounterReceiptUiInput.e1, oldCallback)
            controller.acknowledgeRecorded(CounterReceiptUiInput.e1, oldCallback)
            controller.refresh(oldCallback)
            runCurrent()
            assertEquals(before, port.callCounts())
            assertEquals(CounterReceiptUiInput.e1, controller.state.value.operationId)
        }
    }

    @Test
    fun crrpc029LateLookupAfterRetirementCannotReplaceOriginalRecoveryState() = runTest {
        val reply = CompletableDeferred<LocalOperationResult>()
        withCounterReceiptUi(continuation = CounterReceiptUiInput.unresolvedContinuation(), configure = {
            it.lookupReply = { withContext(NonCancellable) { reply.await() } }
        }) {
            controller.lookup(CounterReceiptUiInput.e1, token())
            runCurrent()
            assertEquals(listOf(CounterReceiptUiInput.e1), port.lookups)
            val retired = assertNotNull(controller.retireForAttachment())
            val closedPresentation = controller.state.value
            assertNull(closedPresentation.operation)
            reply.complete(CounterReceiptUiInput.recorded())
            runCurrent()
            assertEquals(CounterReceiptUiInput.e1, retired.workspace.operationId)
            assertIs<LocalOperationResult.Unresolved>(retired.workspace.operation)
            assertEquals(CounterReceiptUiInput.e1, assertNotNull(retired.frozen).operationId)
            assertEquals(closedPresentation, controller.state.value)
            assertTrue(port.stages.isEmpty() && port.submissions.isEmpty() && port.acknowledgements.isEmpty())
        }
    }

    private fun assertE1Input(intent: LocalCommandIntent) {
        assertEquals(LocalAction.RETAIL_CUSTOMER_RECEIPT, intent.action)
        assertEquals(2, intent.formVersion)
        assertEquals(CounterReceiptUiInput.batch, intent.reference)
        assertEquals(CounterReceiptUiInput.rootValues.associate { it.field to it.value }, intent.values.associate { it.field to it.value })
        assertEquals(LocalField.LINES, intent.lineField)
        assertEquals(listOf(CounterReceiptUiInput.kg, CounterReceiptUiInput.item), intent.lines.map { it.lineId })
        val expectedLines = listOf(CounterReceiptUiInput.line(CounterReceiptUiInput.kg, CounterReceiptUiInput.kgUnit, 3, "0.500"),
            CounterReceiptUiInput.line(CounterReceiptUiInput.item, CounterReceiptUiInput.itemUnit, 0, "2"))
        assertEquals(expectedLines.associate { row -> row.lineId to row.values.associate { it.field to it.value } },
            intent.lines.associate { row -> row.lineId to row.values.associate { it.field to it.value } })
        assertNull(intent.arrivalEvidence)
    }
}
