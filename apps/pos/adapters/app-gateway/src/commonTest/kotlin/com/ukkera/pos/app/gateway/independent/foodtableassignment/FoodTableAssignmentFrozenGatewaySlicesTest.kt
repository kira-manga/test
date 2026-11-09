@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.foodtableassignment

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Supporting FTA-v1 checks of actual gateway/form/journal-validation code with synthetic
 * session, transport and client-custody ports. They are not L2/L3 native/RPC case verdicts.
 * Exact request/result serialization is input construction, not a golden-byte/code oracle.
 */
class FoodTableAssignmentFrozenGatewaySlicesTest {
    private val f = FtaGatewayInputs

    @Test
    fun fta002003004006StageRetainsSelectedIdsAllFourExpectationsAndDoesNotDispatch(): Unit = runTest {
        for ((case, request) in listOf(2 to f.first, 3 to f.sameFloor, 4 to f.move, 6 to f.sameDestination)) {
            val fixture = FtaGatewayFixture()
            val operation = f.operation(case)
            val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(f.intent(request, operation)))
            val saved = assertNotNull(fixture.journal.entry)
            assertEquals(operation, staged.operation.operationId)
            assertEquals(LocalAction.FOOD_ASSIGN_TABLE, staged.operation.action)
            assertEquals(fixture.journal.owner, saved.owner)
            assertEquals(1, saved.formVersion)
            assertEquals(operation, saved.request().operationId)
            assertEquals(request, LocalFoodTableAssignmentCodecV1.decode(saved.request().payload))
            assertNull(saved.request().actionApprovalId)
            assertEquals(1, fixture.journal.stageCalls)
            assertNoDispatchOrAcceptedResult(fixture)
        }
    }

    @Test
    fun fta010038UnsupportedFormsNonScalarShapesAndLabelInsteadOfTableIdDoNotStage(): Unit = runTest {
        val original = f.intent(f.move, f.operation(38))
        val shapes = listOf(
            f.intent(f.move, original.operationId, version = 2),
            f.intent(f.move, original.operationId, version = 999),
            LocalCommandIntent(original.operationId, original.action, 1,
                LocalRecordReference(LocalRecordKind.ORDER, f.order), original.values),
            LocalCommandIntent(original.operationId, original.action, 1, null, original.values, LocalField.LINES),
            LocalCommandIntent(f.operation(10), original.action, 1, null, original.values.map {
                if (it.field == LocalField.TABLE_ID) LocalScalarInput(it.field, LocalScalarValue.Text("Table 07")) else it
            }),
        )
        for (intent in shapes) {
            val fixture = FtaGatewayFixture()
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(intent))
            assertNull(fixture.journal.entry)
            assertEquals(0, fixture.journal.stageCalls)
            assertNoDispatchOrAcceptedResult(fixture)
        }
    }

    @Test
    fun fta001ReadMappingDistinguishesSuppliedAbsentAndAssignedBindingAndKeepsCurrentParent(): Unit = runTest {
        for (binding in listOf(null, f.initialBinding)) {
            val fixture = FtaGatewayFixture()
            fixture.endpoint.readPayload = f.observationBytes(f.observation(binding))
            val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(query(
                LocalFoodUiQuery.TableAssignment(f.order, Revision(12))))).page
            val body = assertIs<LocalFoodUiBody.TableAssignment>(assertNotNull(page.food).body)
            assertEquals(f.order, body.target.orderId)
            assertEquals(Revision(12), body.target.expectedOrderStorageRevision)
            assertEquals(binding?.revision, body.target.expectedAssignmentStorageRevision)
            assertEquals(binding?.floorId, body.floorId)
            assertEquals(binding?.tableId, body.tableId)
            assertTrue(page.records.isEmpty())
            assertTrue(page.forms.isEmpty())
            assertEquals(LocalFoodTableAssignmentReadCodecV1.QUERY, fixture.endpoint.reads.single().query)

            fixture.endpoint.readPayload = f.directoryBytes(LocalFoodFloorPageV1(LocalFoodFloorQueryV1.Floors(), true,
                floors = listOf(f.mainRow, f.upperRow, f.retiredRow)))
            val directory = assertIs<LocalReadResult.Available>(fixture.gateway.read(query(
                LocalFoodUiQuery.FloorDirectory(assignment = body.target)))).page
            val floors = assertIs<LocalFoodUiBody.Floors>(assertNotNull(directory.food).body).floors
            assertEquals(listOf(f.floorMain, f.floorUpper, f.floorRetired), floors.map { it.id })
            assertEquals(listOf(Revision(3), Revision(5), Revision(2)), floors.map { it.storageRevision })
            assertTrue(directory.records.isEmpty())
            assertTrue(directory.forms.isEmpty())

            val form = fixture.assignmentForm(body.target)
            assertEquals(LocalAction.FOOD_ASSIGN_TABLE, form.action)
            assertEquals(LocalScalarValue.Identifier(f.order), form.field(LocalField.ORDER_ID).initialValue)
            assertEquals(LocalScalarValue.Integer(12), form.field(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION).initialValue)
            assertEquals(LocalScalarValue.Identifier(f.floorUpper), form.field(LocalField.FLOOR_ID).initialValue)
            assertEquals(LocalScalarValue.Integer(5), form.field(LocalField.EXPECTED_FLOOR_STORAGE_REVISION).initialValue)
            assertEquals(LocalScalarValue.Identifier(f.tableB), form.field(LocalField.TABLE_ID).initialValue)
            assertEquals(LocalScalarValue.Integer(11), form.field(LocalField.EXPECTED_TABLE_STORAGE_REVISION).initialValue)
            assertEquals(binding?.revision?.let { LocalScalarValue.Integer(it.value) },
                form.field(LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION).initialValue)
            assertEquals(0, fixture.journal.stageCalls)
            assertNoDispatchOrAcceptedResult(fixture)
        }
    }

    @Test
    fun fta024FailedTruncatedMismatchedAndRetiredSessionReadsNeverPublishAnAbsentPage(): Unit = runTest {
        for (failure in listOf(ReadFailure.AUTHORIZATION_DENIED, ReadFailure.STORAGE_UNCERTAIN,
            ReadFailure.WRITER_NOT_QUALIFIED)) {
            val fixture = FtaGatewayFixture()
            fixture.endpoint.readFailure = failure
            assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(query(LocalFoodUiQuery.TableAssignment(f.order, Revision(12)))))
            assertNoDispatchOrAcceptedResult(fixture)
        }
        for (payload in listOf(StoredBytes("{\"orderId\":".encodeToByteArray()),
            f.observationBytes(f.observation(null).copy(orderId = f.otherOrder)))) {
            val fixture = FtaGatewayFixture()
            fixture.endpoint.readPayload = payload
            assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(query(LocalFoodUiQuery.TableAssignment(f.order, Revision(12)))))
            assertNoDispatchOrAcceptedResult(fixture)
        }
        val retired = FtaGatewayFixture()
        retired.endpoint.readPayload = f.observationBytes(f.observation(null))
        retired.endpoint.afterRead = { retired.current = false }
        assertIs<LocalReadResult.Unavailable>(retired.gateway.read(query(LocalFoodUiQuery.TableAssignment(f.order, Revision(12)))))
        assertEquals(0, retired.journal.stageCalls)
        assertNoDispatchOrAcceptedResult(retired)
    }

    @Test
    fun fta025031SubmitUsesRetainedOriginalDespiteEditorReplacementAndExactRetryDoesNotRedispatch(): Unit = runTest {
        for (case in listOf(25, 31)) {
            val fixture = FtaGatewayFixture()
            val operation = f.operation(case)
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(f.intent(f.move, operation)))
            val original = assertNotNull(fixture.journal.entry)
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(f.intent(f.sameFloor, operation)))
            assertEquals(original, fixture.journal.entry)
            // GATEWAY_ONLY_BRANCH_DATA: this scripted sentinel is NOT a native absence proof.
            fixture.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
            fixture.endpoint.executeReply = { f.recorded(it.operationId) }
            val submitted = assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(operation))
            assertEquals(LocalRecordedDecision.APPLIED, submitted.decision)
            assertEquals(operation, submitted.operationId)
            val dispatched = fixture.endpoint.executions.single()
            assertEquals(operation, dispatched.operationId)
            assertEquals(f.move, LocalFoodTableAssignmentCodecV1.decode(dispatched.payload))
            assertEquals(original.request().payload, dispatched.payload)
            assertEquals(1, fixture.journal.dispatchCalls)
            assertEquals(1, fixture.journal.acceptedResultCount)
            assertOriginalRequest(original, assertNotNull(fixture.journal.entry))
            fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId, replayed = true)) }
            assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(operation))
            assertIs<LocalOperationResult.Recorded>(fixture.newGateway().lookup(operation))
            assertEquals(1, fixture.endpoint.executions.size)
            assertEquals(1, fixture.journal.dispatchCalls)
            assertEquals(1, fixture.journal.acceptedResultCount)
            assertEquals(1, fixture.journal.stageCalls)
            assertEquals(0, fixture.journal.logicalAcknowledgements)
        }
    }

    @Test
    fun fta026AlteredSemanticInputsAndOtherAccountCannotReplaceRetainedOriginal(): Unit = runTest {
        val fixture = FtaGatewayFixture()
        val operation = f.operation(26)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(f.intent(f.move, operation)))
        fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId)) }
        assertIs<LocalOperationResult.Recorded>(fixture.gateway.lookup(operation))
        val retained = assertNotNull(fixture.journal.entry)
        val changed = listOf(f.sameFloor, f.move.copy(expectedFloorRevision = Revision(6)),
            f.move.copy(expectedTableRevision = Revision(12)), f.move.copy(expectedOrderRevision = Revision(13)),
            f.move.copy(expectedAssignmentRevision = Revision(2)), f.move.copy(expectedAssignmentRevision = null),
            f.move.copy(orderId = f.otherOrder))
        for (request in changed) {
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(f.intent(request, operation)))
            assertEquals(retained, fixture.journal.entry)
        }
        fixture.endpoint.view = f.view(f.otherActor)
        val beforeLookup = fixture.endpoint.lookups.size
        assertIs<LocalOperationResult.Unresolved>(fixture.newGateway().lookup(operation))
        assertEquals(beforeLookup, fixture.endpoint.lookups.size)
        assertEquals(retained, fixture.journal.entry)
        fixture.endpoint.view = f.view()
        assertIs<LocalOperationResult.Recorded>(fixture.newGateway().lookup(operation))
        assertEquals(retained, fixture.journal.entry)
        assertEquals(1, fixture.journal.acceptedResultCount)
        assertEquals(0, fixture.endpoint.executions.size)
        assertEquals(1, fixture.journal.stageCalls)
    }

    @Test
    fun fta027028DeferredLookupKeepsOriginalUntilFoundRecoveryAfterGatewayReconstruction(): Unit = runTest {
        for (case in listOf(27, 28)) {
            val fixture = FtaGatewayFixture()
            val operation = f.operation(case)
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(f.intent(f.move, operation)))
            val original = assertNotNull(fixture.journal.entry)
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(operation))
            assertEquals(original, fixture.journal.entry)
            assertNoDispatchOrAcceptedResult(fixture)
            fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId)) }
            val recovered = assertIs<LocalOperationResult.Recorded>(fixture.newGateway().lookup(operation))
            assertEquals(operation, recovered.operationId)
            assertEquals(LocalRecordedDecision.APPLIED, recovered.decision)
            assertOriginalRequest(original, assertNotNull(fixture.journal.entry))
            assertTrue(fixture.endpoint.lookups.all { it == original.identity() })
            assertEquals(1, fixture.journal.acceptedResultCount)
            assertEquals(0, fixture.endpoint.executions.size)
            assertEquals(0, fixture.journal.logicalAcknowledgements)
        }
    }

    @Test
    fun fta037ConflictingSameOperationResultsFailBeforeRetentionAcrossGatewayEntryPoints(): Unit = runTest {
        for ((name, conflict) in f.conflicts) for (path in ConflictPath.entries) {
            val fixture = FtaGatewayFixture()
            val operation = f.operation(37)
            val intent = f.intent(f.move, operation)
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
            val original = assertNotNull(fixture.journal.entry)
            fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId, conflict)) }
            when (path) {
                ConflictPath.STAGE_RECOVERY -> assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(intent), "$name / $path")
                ConflictPath.SUBMIT_FOUND -> assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(operation), "$name / $path")
                ConflictPath.LOOKUP -> assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(operation), "$name / $path")
                ConflictPath.PENDING -> assertIs<LocalPendingResult.Unavailable>(fixture.gateway.pending(null), "$name / $path")
                ConflictPath.EXECUTE_REPLY -> {
                    // GATEWAY_ONLY_BRANCH_DATA, never qualified native absence/admission.
                    fixture.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
                    fixture.endpoint.executeReply = { f.recorded(it.operationId, conflict) }
                    assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(operation), "$name / $path")
                }
            }
            val pending = assertNotNull(fixture.journal.entry)
            assertOriginalRequest(original, pending)
            assertNull(pending.recorded, "$name / $path")
            assertFalse(pending.acknowledged, "$name / $path")
            assertEquals(0, fixture.journal.resultWriteAttempts, "$name / $path")
            assertEquals(0, fixture.journal.acceptedResultCount, "$name / $path")
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(operation))
            assertEquals(0, fixture.journal.acknowledgementAttempts, "$name / $path")
            assertEquals(0, fixture.journal.logicalAcknowledgements, "$name / $path")
            assertEquals(1, fixture.journal.stageCalls)
            assertEquals(if (path == ConflictPath.EXECUTE_REPLY) 1 else 0, fixture.endpoint.executions.size)
            fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId, conflict)) }
            assertIs<LocalOperationResult.Unresolved>(fixture.newGateway().lookup(operation))
            assertOriginalRequest(original, assertNotNull(fixture.journal.entry))
            assertEquals(0, fixture.journal.resultWriteAttempts)
        }
    }

    @Test
    fun fta038SavedFormVersionOneRestoresExactInputAndAllUnsupportedVersionsRefuseWithoutDispatch(): Unit = runTest {
        val fixture = FtaGatewayFixture()
        val operation = f.operation(38)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(f.intent(f.move, operation)))
        val original = assertNotNull(fixture.journal.entry)
        val encoded = journalJson.encodeToString(StoredClientIntentV1.serializer(), original)
        val restored = journalJson.decodeFromString(StoredClientIntentV1.serializer(), encoded)
        restored.validate(fixture.journal.owner)
        assertEquals(original, restored)
        assertEquals(operation, restored.operationId)
        assertEquals(f.move, LocalFoodTableAssignmentCodecV1.decode(restored.request().payload))
        assertEquals(original.identity(), restored.identity())
        val objectInput = journalJson.parseToJsonElement(encoded).jsonObject
        val unsupported = listOf<JsonElement?>(JsonPrimitive(0), JsonPrimitive(2), JsonPrimitive(999),
            JsonPrimitive("unknown"), JsonNull, null)
        for (version in unsupported) {
            val fields = objectInput.toMutableMap()
            if (version == null) fields.remove("formVersion") else fields["formVersion"] = version
            assertFails {
                journalJson.decodeFromString(StoredClientIntentV1.serializer(), JsonObject(fields).toString())
                    .validate(fixture.journal.owner)
            }
            assertEquals(original, fixture.journal.entry)
        }
        assertNoDispatchOrAcceptedResult(fixture)
        assertEquals(0, fixture.endpoint.reads.size)
        assertEquals(0, fixture.endpoint.lookups.size)
    }

    @Test
    fun fta038039UnchangedFormRebindRetainsSelectedIdentityAndPinsButClearsConfirmation(): Unit = runTest {
        val fixture = FtaGatewayFixture()
        val form = fixture.assignmentForm()
        val draft = selectedDraft(form).copy(confirmed = true, dirty = true)
        val saved = draft.inputContinuation()
        val rebound = assertNotNull(saved.rebind(form))
        assertFalse(rebound.confirmed)
        assertTrue(rebound.dirty)
        assertEquals(draft.values, rebound.values)
        assertPinTexts(saved, "12", "1", "5", "11")
        assertNull(saved.rebind(form.copy(version = 2)))
        assertEquals(0, fixture.journal.stageCalls)
        assertNoDispatchOrAcceptedResult(fixture)
    }

    @Test
    fun fta039EveryUnequalRevisionAndBothAbsentAssignedDirectionsRefuseRebindWithoutSubstitution(): Unit = runTest {
        val fixture = FtaGatewayFixture()
        val captured = selectedDraft(fixture.assignmentForm()).inputContinuation()
        val freshForms = listOf(
            fixture.assignmentForm(LocalFoodUiTableTarget(f.order, Revision(13), Revision(1))),
            fixture.assignmentForm(floor = f.upperRow.copy(storageRevision = Revision(6))),
            fixture.assignmentForm(table = f.tableBRow.copy(storageRevision = Revision(12))),
            fixture.assignmentForm(LocalFoodUiTableTarget(f.order, Revision(12), Revision(2))),
            fixture.assignmentForm(LocalFoodUiTableTarget(f.order, Revision(12), null)),
        )
        for (fresh in freshForms) {
            assertNull(captured.rebind(fresh))
            assertPinTexts(captured, "12", "1", "5", "11")
        }
        val absent = selectedDraft(fixture.assignmentForm(LocalFoodUiTableTarget(f.order, Revision(12), null))).inputContinuation()
        assertNull(absent.rebind(fixture.assignmentForm()))
        assertPinTexts(absent, "12", "", "5", "11")
        assertEquals(0, fixture.journal.stageCalls)
        assertNoDispatchOrAcceptedResult(fixture)
    }

    @Test
    fun fta040ConflictSurvivesAckAttemptsReconstructionAndAccountSwitchThenOriginalRetiresOnce(): Unit = runTest {
        val fixture = FtaGatewayFixture()
        val operation = f.operation(40)
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(f.intent(f.move, operation)))
        val original = assertNotNull(fixture.journal.entry)
        val conflict = f.conflicts.last().second
        fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId, conflict)) }
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(operation))
        assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(operation))
        assertIs<LocalOperationResult.Unresolved>(fixture.newGateway().lookup(operation))
        fixture.endpoint.view = f.view(f.otherActor)
        assertIs<LocalPendingResult.Unavailable>(fixture.newGateway().pending(null))
        assertEquals(original, fixture.journal.entry)
        assertEquals(0, fixture.journal.acceptedResultCount)
        assertEquals(0, fixture.journal.resultWriteAttempts)
        assertEquals(0, fixture.journal.acknowledgementAttempts)

        fixture.endpoint.view = f.view()
        fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId)) }
        assertIs<LocalOperationResult.Recorded>(fixture.newGateway().lookup(operation))
        val retained = assertNotNull(fixture.journal.entry)
        assertOriginalRequest(original, retained)
        assertEquals(StoredKernelResultV1.from(f.recorded(operation)), retained.recorded)
        assertEquals(1, fixture.journal.acceptedResultCount)
        // Even after valid retention, an ACK lookup must validate the actual reply, not its ID.
        fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId, conflict)) }
        assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(operation))
        assertEquals(retained, fixture.journal.entry)
        assertEquals(0, fixture.journal.acknowledgementAttempts)

        fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId, replayed = true)) }
        assertIs<LocalAcknowledgementResult.Acknowledged>(fixture.gateway.acknowledgeRecorded(operation))
        assertIs<LocalAcknowledgementResult.Acknowledged>(fixture.newGateway().acknowledgeRecorded(operation))
        assertEquals(1, fixture.journal.logicalAcknowledgements)
        val retired = assertNotNull(fixture.journal.entry)
        assertTrue(retired.acknowledged)
        assertTrue(assertIs<LocalPendingResult.Available>(fixture.gateway.pending(null)).operations.isEmpty())

        fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId, conflict)) }
        assertIs<LocalOperationResult.Unresolved>(fixture.newGateway().lookup(operation))
        assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(f.intent(f.move, operation)))
        assertEquals(retired, fixture.journal.entry)
        fixture.endpoint.lookupReply = { LookupReply.Found(f.recorded(it.operationId, replayed = true)) }
        assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(operation))
        assertEquals(retired, fixture.journal.entry)
        assertTrue(assertIs<LocalPendingResult.Available>(fixture.newGateway().pending(null)).operations.isEmpty())
        assertEquals(1, fixture.journal.acceptedResultCount)
        assertEquals(1, fixture.journal.logicalAcknowledgements)
        assertEquals(1, fixture.journal.stageCalls)
        assertEquals(0, fixture.endpoint.executions.size)
        assertEquals(0, fixture.journal.dispatchCalls)
    }
}

private enum class ConflictPath { STAGE_RECOVERY, SUBMIT_FOUND, LOOKUP, PENDING, EXECUTE_REPLY }
private fun query(food: LocalFoodUiQuery) = LocalWorkflowQuery(LocalWorkflow.FOOD, food = food)
private fun LocalActionForm.field(field: LocalField) = fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == field }
private fun selectedDraft(form: LocalActionForm): LocalFormDraft {
    val draft = localFormDraft(form)
    val selectedIds = setOf(LocalField.ORDER_ID, LocalField.FLOOR_ID, LocalField.TABLE_ID)
    return draft.copy(values = draft.values.mapValues { (field, value) ->
        if (field in selectedIds) value.copy(inputIdentityOrigin = LocalInputIdentityOrigin.SELECTED) else value
    })
}
private fun assertPinTexts(value: LocalFormInputContinuation, order: String, binding: String, floor: String, table: String) {
    assertEquals(order, value.values.getValue(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION).text)
    assertEquals(binding, value.values.getValue(LocalField.EXPECTED_TABLE_ASSIGNMENT_STORAGE_REVISION).text)
    assertEquals(floor, value.values.getValue(LocalField.EXPECTED_FLOOR_STORAGE_REVISION).text)
    assertEquals(table, value.values.getValue(LocalField.EXPECTED_TABLE_STORAGE_REVISION).text)
}
private fun assertOriginalRequest(original: StoredClientIntentV1, current: StoredClientIntentV1) {
    assertEquals(original.owner, current.owner)
    assertEquals(original.operationId, current.operationId)
    assertEquals(original.action, current.action)
    assertEquals(original.formVersion, current.formVersion)
    assertEquals(original.inputDigest, current.inputDigest)
    assertEquals(original.semanticSha256, current.semanticSha256)
    assertEquals(original.request().payload, current.request().payload)
    assertEquals(original.summary, current.summary)
    assertEquals(original.label, current.label)
    assertTrue(original.sameIntent(current))
}
private fun assertNoDispatchOrAcceptedResult(fixture: FtaGatewayFixture) {
    assertEquals(0, fixture.endpoint.executions.size)
    assertEquals(0, fixture.journal.dispatchCalls)
    assertEquals(0, fixture.journal.resultWriteAttempts)
    assertEquals(0, fixture.journal.acceptedResultCount)
    assertEquals(0, fixture.journal.acknowledgementAttempts)
    assertEquals(0, fixture.journal.logicalAcknowledgements)
}
