package com.ukkera.pos.app.gateway.independent.managerreassignmentv1

import com.ukkera.pos.app.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Actual gateway with controlled ports only; no qualified Master, durable journal or recipient. */
class ManagerReassignmentGatewaySlicesTest {
    private val f = FrozenManagerReassignmentV1

    @Test
    fun mra001CandidateAndReadonlyManagerRoutesNeverActivateOwnerOrPhysicalForms() = runTest {
        val control = ReassignmentGatewayControl()
        val page = assertIs<LocalReadResult.Available>(control.gateway.read(f.query())).page
        assertEquals(LocalWorkflow.ORDERS, page.workflow)
        val record = page.records.single()
        assertEquals(LocalRecordReference(LocalRecordKind.ORDER, f.target, f.r17), record.reference)
        assertFalse(record.selectable)
        assertEquals(listOf(LocalAction.ORDER_REASSIGN), page.forms.map { it.action })
        val form = page.forms.single()
        assertEquals(2, form.version)
        for ((field, expected) in listOf(
            LocalField.ORDER_ID to LocalScalarValue.Identifier(f.target),
            LocalField.EXPECTED_REVISION to LocalScalarValue.Integer(f.r17.value),
            LocalField.PREVIOUS_OWNER_ID to LocalScalarValue.Identifier(f.previousOwner))) {
            val scalar = form.fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == field }
            assertTrue(scalar.required && scalar.readOnly)
            assertEquals(expected, scalar.initialValue)
        }
        val managerPage = assertIs<LocalReadResult.Available>(control.gateway.read(f.query("manager:${f.target.value}"))).page
        assertTrue(managerPage.forms.isEmpty())
        assertTrue(managerPage.records.none { it.selectable })
        assertNull(page.priceControls)
        assertNull(page.food)
        assertTrue(control.journal.calls.isEmpty())
        assertTrue(control.endpoint.lookups.isEmpty())
        control.assertNoDispatch()
    }

    @Test
    fun mra001MissingEitherNewCapabilityAndWrongRoutesNeverFallbackOrStageGuessedWork() = runTest {
        val legacyReads = setOf(LocalManagerOrderReviewCodec.QUERY, LocalOrderReadCodec.QUERY, LocalOrderReadCodecV2.QUERY)
        for ((commands, reads) in listOf(
            setOf(LocalCollectionStockCodecV2.COMMAND) to legacyReads,
            emptySet<CommandKey>() to (legacyReads + LocalManagerReassignmentCodec.QUERY))) {
            val control = ReassignmentGatewayControl(commands, reads)
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, assertIs<LocalReadResult.Unavailable>(control.gateway.read(f.query())).problem)
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, assertIs<LocalStageResult.NotStaged>(control.gateway.stage(f.intent())).problem)
            assertTrue(control.endpoint.reads.isEmpty())
            assertTrue(control.journal.entries.isEmpty())
            control.assertNoDispatch()
        }
        for (query in listOf(LocalWorkflowQuery(LocalWorkflow.CHECKOUT, "reassign:${f.target.value}"),
            f.query().copy(cursor = "unexpected-cursor"),
            f.query().copy(reference = LocalRecordReference(LocalRecordKind.COLLECTION, f.target)),
            f.query().copy(reference = LocalRecordReference(LocalRecordKind.ORDER, f.id("ORDER-A-OTHER"))))) {
            val control = ReassignmentGatewayControl()
            assertEquals(LocalServiceProblem.INVALID_INPUT, assertIs<LocalReadResult.Unavailable>(control.gateway.read(query)).problem)
            assertTrue(control.endpoint.reads.isEmpty())
            control.assertNoDispatch()
        }
    }

    @Test
    fun mra001And006And022ControlledHintOrReaderRefusalCannotBecomeAUsableCandidate() = runTest {
        for (grants in listOf(emptyList(), listOf(f.hint(f.id("ORDER-A-OTHER"))),
            listOf(f.hint(action = SecurityAction.ORDER_HANDOFF)), listOf(f.hint(), f.hint(effect = GrantEffect.DENY)))) {
            val control = ReassignmentGatewayControl(grants = grants)
            assertEquals(LocalServiceProblem.DENIED, assertIs<LocalReadResult.Unavailable>(control.gateway.read(f.query())).problem)
            assertTrue(control.endpoint.reads.isEmpty())
            control.assertNoDispatch()
        }
        for (failure in listOf(ReadFailure.AUTHORIZATION_DENIED, ReadFailure.WRITER_NOT_QUALIFIED,
            ReadFailure.STORAGE_UNCERTAIN, ReadFailure.UNKNOWN_QUERY)) {
            val control = ReassignmentGatewayControl()
            control.endpoint.readReply = { ReadReply.Unavailable(failure) }
            assertIs<LocalReadResult.Unavailable>(control.gateway.read(f.query()))
            control.assertOnlyTargetReads(1)
            control.assertNoDispatch()
        }
        val obsolete = ReassignmentGatewayControl()
        obsolete.leaseCurrent = false
        assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalReadResult.Unavailable>(obsolete.gateway.read(f.query())).problem)
        assertEquals(0, obsolete.endpoint.currentCalls)
        obsolete.assertNoDispatch()
        // These are explicit metadata/refusal inputs, not current native authorization or writer proof.
    }

    @Test
    fun mra003SavedBodyAndIdSurviveRestagingAndCandidateRefreshWithoutAutomaticSubmission() = runTest {
        val control = ReassignmentGatewayControl()
        val staged = assertIs<LocalStageResult.Staged>(control.gateway.stage(f.intent())).operation
        assertEquals(f.operation(), staged.operationId)
        assertEquals(LocalAction.ORDER_REASSIGN, staged.action)
        assertTrue(staged.submitAllowed)
        val original = control.journal.entries.getValue(f.operation())
        assertEquals(LocalCollectionStockCodecV2.encode(f.request()), original.request().payload)
        assertEquals(f.manager, original.owner.actorId)
        assertEquals(f.device, original.owner.deviceId)
        assertTrue(control.endpoint.lookups.isEmpty())
        control.assertNoDispatch()
        control.endpoint.readReply = {
            val old = f.candidate()
            f.readReply(listOf(old.copy(order = old.order.copy(ownerId = f.alternateRecipient, revision = Revision(4)))))
        }
        assertIs<LocalReadResult.Available>(control.gateway.read(f.query()))
        val readsBeforeRestage = control.endpoint.reads.size
        assertEquals(f.operation(), assertIs<LocalStageResult.Staged>(control.gateway.stage(f.intent())).operation.operationId)
        assertEquals(readsBeforeRestage, control.endpoint.reads.size, "Saved work must not be freshly prepared")
        assertEquals(original, control.journal.entries.getValue(f.operation()))
        assertEquals(1, control.journal.stageWrites)
        control.assertNoDispatch()
        // The in-memory stage return is not durable staging; the later candidate is scripted.
    }

    @Test
    fun mra004And005FreshInvalidInputsRefuseBeforeAnyJournalWriteOrMutation() = runTest {
        val inputs = f.inputs()
        val invalid = mutableListOf<Pair<LocalCommandIntent, LocalField?>>()
        for (field in inputs.map { it.field }) invalid += f.intent(values = inputs.filterNot { it.field == field }) to field
        for (reason in f.rejectedReasons) invalid += f.intent(f.request(reason)) to LocalField.REASON
        invalid += f.intent(f.request().copy(expectedOrderRevision = f.r16)) to LocalField.EXPECTED_REVISION
        invalid += f.intent(f.request().copy(previousOwnerId = f.creator)) to LocalField.PREVIOUS_OWNER_ID
        invalid += f.intent(f.request().copy(recipientId = f.previousOwner)) to LocalField.RECIPIENT_ID
        invalid += f.intent(values = inputs.map { if (it.field == LocalField.OCCURRED_AT)
            it.copy(value = LocalScalarValue.Text("2026-10-30T11:00:00+02:00")) else it }) to null
        invalid += f.intent(values = inputs + LocalScalarInput(LocalField.ACTIVE, LocalScalarValue.Flag(true))) to null
        invalid += f.intent(formVersion = 1) to null
        for ((intent, field) in invalid) {
            val control = ReassignmentGatewayControl()
            val result = assertIs<LocalStageResult.NotStaged>(control.gateway.stage(intent))
            assertEquals(LocalServiceProblem.INVALID_INPUT, result.problem)
            if (field != null) assertEquals(field, result.field)
            assertEquals(0, control.journal.stageWrites)
            assertTrue(control.journal.entries.isEmpty())
            control.assertNoDispatch()
        }
        val absent = ReassignmentGatewayControl()
        absent.endpoint.readReply = { f.readReply(emptyList()) }
        assertEquals(LocalField.ORDER_ID, assertIs<LocalStageResult.NotStaged>(absent.gateway.stage(f.intent())).field)
        absent.assertNoDispatch()
        // Unknown/disabled/foreign/closing/full recipients still require genuine backend qualification.
    }

    @Test
    fun mra012ChangedFrozenSubstantiveInputsCannotReplaceAnExistingOriginalIdentity() = runTest {
        val control = ReassignmentGatewayControl()
        assertIs<LocalStageResult.Staged>(control.gateway.stage(f.intent()))
        val original = control.journal.entries.getValue(f.operation())
        val base = f.request()
        val changed = listOf(base.copy(orderId = f.id("ORDER-A-OTHER")), base.copy(previousOwnerId = f.creator),
            base.copy(recipientId = f.alternateRecipient), base.copy(expectedOrderRevision = f.r18), base.copy(reasonCode = "A"),
            base.copy(evidence = base.evidence.copy(evidenceId = f.id("EVIDENCE-MGR-002"))),
            base.copy(evidence = base.evidence.copy(occurredAt = UtcInstant.parseCanonical("2026-10-30T09:01:00Z"))))
        val intents = changed.map { f.intent(it) } + f.intent(formVersion = 1) +
            LocalCommandIntent(f.operation(), LocalAction.ORDER_ACCEPT_HANDOFF, 1, null, f.inputs())
        for (intent in intents) {
            assertEquals(f.operation(), assertIs<LocalStageResult.Uncertain>(control.gateway.stage(intent)).operationId)
            assertEquals(original, control.journal.entries.getValue(f.operation()))
        }
        assertEquals(1, control.journal.stageWrites)
        assertTrue(control.endpoint.lookups.isEmpty())
        control.assertOnlyTargetReads(1)
        control.assertNoDispatch()
        // Client-journal refusal only, not the Master registry's conflict or concurrency result.
    }

    @Test
    fun mra017And025ControlledLostReplyLookupKeepsOriginalBodyAndRendersReadonlyOrderResult() = runTest {
        val control = ReassignmentGatewayControl()
        assertIs<LocalStageResult.Staged>(control.gateway.stage(f.intent()))
        val original = control.journal.entries.getValue(f.operation())
        control.endpoint.executeReply = { throw IllegalStateException("Controlled lost reply") }
        assertEquals(f.operation(), assertIs<LocalOperationResult.Unresolved>(control.gateway.submit(f.operation())).operationId)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, control.journal.entries.getValue(f.operation()).localPhase)
        assertEquals(original.request().payload, control.endpoint.executions.single().payload)
        assertEquals(f.operation(), control.endpoint.executions.single().operationId)
        control.endpoint.lookupReply = { LookupReply.Found(f.recordedReply(it.operationId, replayed = true)) }
        val recorded = assertIs<LocalOperationResult.Recorded>(control.gateway.lookup(f.operation()))
        assertEquals(f.operation(), recorded.operationId)
        assertEquals(LocalRecordedDecision.APPLIED, recorded.decision)
        assertTrue(recorded.replayed)
        val result = assertNotNull(recorded.result)
        assertEquals(LocalRecordReference(LocalRecordKind.ORDER, f.target, f.r18), result.reference)
        assertFalse(result.selectable)
        assertNull(recorded.food)
        control.endpoint.readReply = {
            val candidate = f.candidate()
            f.readReply(listOf(candidate.copy(order = candidate.order.copy(ownerId = f.alternateRecipient, revision = Revision(4)))))
        }
        assertIs<LocalReadResult.Available>(control.gateway.read(f.query()))
        assertEquals(recorded, control.gateway.lookup(f.operation()))
        val retained = control.journal.entries.getValue(f.operation())
        assertEquals(original.request().payload, retained.request().payload)
        assertEquals(f.manager, retained.owner.actorId)
        assertEquals(f.attestedAt, assertIs<LocalCollectionStockRequestV2.ReassignOrder>(
            LocalCollectionStockCodecV2.decode(retained.request().payload)).evidence.occurredAt)
        assertEquals(1, control.journal.stageWrites)
        assertEquals(1, control.endpoint.executions.size)
        // Recorded/Found are controlled DTOs: no actual commit, lost committed reply, dedup or later move.
    }

    @Test
    fun mra006And018WithdrawalOrCancellationNeverReplacesTheDispatchedOriginal() = runTest {
        val denied = ReassignmentGatewayControl()
        assertIs<LocalStageResult.Staged>(denied.gateway.stage(f.intent()))
        val original = denied.journal.entries.getValue(f.operation())
        denied.endpoint.currentReply = LocalSessionViewResult.Current(f.sessionView(listOf(f.hint(effect = GrantEffect.DENY))))
        assertEquals(LocalServiceProblem.DENIED, assertIs<LocalOperationResult.Unresolved>(denied.gateway.submit(f.operation())).problem)
        assertEquals(original, denied.journal.entries.getValue(f.operation()))
        denied.assertNoDispatch()

        val cancelled = ReassignmentGatewayControl()
        assertIs<LocalStageResult.Staged>(cancelled.gateway.stage(f.intent()))
        val body = cancelled.journal.entries.getValue(f.operation()).request().payload
        cancelled.endpoint.executeReply = { throw CancellationException("Controlled cancelled wait") }
        assertFailsWith<CancellationException> { cancelled.gateway.submit(f.operation()) }
        val retained = cancelled.journal.entries.getValue(f.operation())
        assertEquals(f.operation(), retained.operationId)
        assertEquals(body, retained.request().payload)
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, retained.localPhase)
        assertEquals(1, cancelled.endpoint.executions.size)
        // No accepted native command or actual lock/restart/commit race is established here.
    }
}
