package com.ukkera.pos.app.gateway.independent.kitchenrpcworkflowv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcControlledJournal
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcControlledMaster
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcGate
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcWriteCut
import com.ukkera.pos.catalog.store.LocalFoodStationDirectoryCodecV1
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * kitchen-station-rpc-workflow/v1 KRPC-001/005/007 gateway SUPPORT ONLY slices,
 * using unchanged kitchen-board-actions/v1 F-AUTH/F-BOARD-Q/F-BOARD-R originals.
 * The real gateway is bound to controlled current-view, endpoint and journal ports.
 * Their Data/Recorded replies and DURABLE answers do not establish authentication,
 * native DISPLAY lineage, business effects, RPC routing/lifetime, protected storage,
 * restart, progressed-task lookup or a server-side matching durable result ACK.
 * SDK terminal decoding/`WireKind.Receipt` precedes the gateway boundary and is
 * absent here: these tests cannot establish zero SDK receipts before validation/
 * remembrance or actual Host retained-result eviction semantics.
 */
class KitchenRpcGatewaySupportingTest {
    private val input get() = FrozenKitchenRpcGatewayInputs

    @Test
    fun krpc005StageAndSubmitKeepEachFrozenLegacyAndNormalizedOriginal() = runTest(timeout = 10.seconds) {
        for (journey in input.journeys) withKitchenRpcGatewayFixture(journey) { fixture ->
            val saveGate = WarehouseRpcGate()
            fixture.journal.stageGate = saveGate
            val saving = async { fixture.gateway.stage(input.intent(journey)) }
            try {
                withTimeout(5_000) { saveGate.entered.await() }
                assertTrue(fixture.journal.entries.isEmpty(), journey.name)
                assertEquals(0, fixture.journal.dispatchCalls, journey.name)
                assertTrue(fixture.master.mutations.isEmpty(), journey.name)
                assertTrue(fixture.master.lookups.isEmpty(), journey.name)
            } finally { saveGate.release() }

            val staged = assertIs<LocalStageResult.Staged>(saving.await(), journey.name).operation
            assertEquals(journey.operation, staged.operationId)
            assertEquals(journey.action, staged.action)
            assertEquals(LocalPendingPhase.STAGED, staged.phase)
            assertTrue(staged.submitAllowed)
            assertEquals(1, fixture.journal.entries.size)
            assertEquals(1, fixture.journal.stageCalls)
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            assertFreshStationReads(fixture, journey)
            val original = fixture.journal.entries.getValue(journey.operation)
            input.assertOriginal(original, journey)
            val identity = original.identity()

            // No source/profile query may reroute an already saved original.
            fixture.master.reads.clear()
            fixture.master.readReply = { error("Saved kitchen submission must not perform a fresh read") }
            val executeGate = WarehouseRpcGate()
            fixture.master.executeGate = executeGate
            val submitting = async { fixture.gateway.submit(journey.operation) }
            try {
                withTimeout(5_000) { executeGate.entered.await() }
                val dispatched = fixture.journal.entries.getValue(journey.operation)
                input.assertOriginal(dispatched, journey)
                assertTrue(original.sameIntent(dispatched))
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, dispatched.localPhase)
                assertNull(dispatched.recorded)
                assertEquals(listOf(identity), fixture.master.lookups)
                assertExactRequest(original.request(), fixture.master.mutations.single())
                val savedIndex = fixture.events.indexOf("intent.retained")
                val dispatchedIndex = fixture.events.indexOf("dispatch.retained")
                val executeIndex = fixture.events.indexOf("execute.enter")
                assertTrue(savedIndex >= 0 && dispatchedIndex > savedIndex && executeIndex > dispatchedIndex)
            } finally { executeGate.release() }

            val result = assertIs<LocalOperationResult.Recorded>(submitting.await(), journey.name)
            assertEquals(LocalRecordedDecision.APPLIED, result.decision)
            assertEquals(journey.resultCode, assertNotNull(result.food).code)
            val retained = fixture.journal.entries.getValue(journey.operation)
            input.assertOriginal(retained, journey)
            assertTrue(original.sameIntent(retained))
            assertEquals(identity, retained.identity())
            assertEquals(original.inputDigest, retained.inputDigest)
            assertEquals(StoredKernelResultV1.from(fixture.master.terminalCarriers.getValue(journey.operation)),
                retained.recorded, "Retain the exact supplied typed carrier, not a new business revision oracle")
            assertFalse(retained.acknowledged)
            assertEquals(1, fixture.journal.dispatchCalls)
            assertEquals(1, fixture.master.mutations.size)
            assertTrue(fixture.master.reads.isEmpty())
            // The barrier proves controlled client ordering only; no native business commit is supplied.
        }
    }

    @Test
    fun krpc005FailedAdvertisedStationProfileCannotStageALegacyReplacement() = runTest {
        for (journey in input.journeys.filterNot { it.normalized }) withKitchenRpcGatewayFixture(journey) { fixture ->
            fixture.master.readReply = { request ->
                if (request.query == LocalStockRelationalQueriesV2.STATION_PROFILE)
                    ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN)
                else fixture.allowedRead(request)
            }
            val refused = assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(input.intent(journey)))
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, refused.problem)
            assertFreshStationReads(fixture, journey)
            assertTrue(fixture.journal.entries.isEmpty())
            assertEquals(0, fixture.journal.stageCalls)
            assertEquals(0, fixture.journal.dispatchCalls)
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            // The otherwise available controlled task/legacy profile setting cannot replace
            // a failed advertised exact profile query. There is no real cache or storage here.
        }
    }

    @Test
    fun krpc001OtherWorkspacesAndNonStationActionsAreDeniedBeforeDispatch() = runTest {
        withKitchenRpcGatewayFixture(input.journeys.first()) { fixture ->
            // Each is a valid public UI query, but not an installed KITCHEN workflow.
            // Forbidden KITCHEN selectors cannot be fabricated past the UI constructor.
            val queries = listOf(
                LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.MyChecks()),
                LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(input.order)),
                LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.IncomingKnownOrder(input.order)),
                LocalWorkflowQuery(LocalWorkflow.REPORTS),
                LocalWorkflowQuery(LocalWorkflow.INVENTORY),
                LocalWorkflowQuery(LocalWorkflow.SETUP),
            )
            for (query in queries) assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(query)).problem)
            for (action in listOf(LocalAction.FOOD_SEND, LocalAction.FOOD_CREATE_DRAFT,
                LocalAction.FOOD_CONFIRM_UNSTARTED, LocalAction.FOOD_OFFER_HANDOFF)) {
                val intent = LocalCommandIntent(input.startOperation, action, 1, null,
                    input.values(input.queued))
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                    assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(intent)).problem)
            }
            assertTrue(fixture.master.reads.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.journal.entries.isEmpty())
            assertEquals(0, fixture.journal.stageCalls)
            assertEquals(0, fixture.journal.dispatchCalls)
            // This is installation selection, not the complete direct-RPC R/E negative bundle.
        }
    }

    @Test
    fun krpc001SharedFoodAndProfileReadBodiesCannotWidenTheGatewayReadPort() = runTest {
        withKitchenRpcGatewayFixture(input.journeys.first()) { fixture ->
            val lease = LocalWorkflowCredentialLease(input.credentials()) { true }
            val access = GatewayAccess(fixture.master, lease, input.view(), fixture.installed)
            try {
                val excluded = listOf(
                    LocalFoodReadCodecV1.read(LocalFoodQueryV1.Order(input.order)),
                    LocalFoodReadCodecV1.read(LocalFoodQueryV1.Own()),
                    LocalFoodReadCodecV1.read(LocalFoodQueryV1.Handoff(input.order)),
                    LocalStockRelationalQueriesV2.profile(),
                    ReadRequest(ReadKey(LocalFoodProfileV1.QUERY.name, 2),
                        LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Task(input.station, input.task))),
                )
                for (request in excluded) assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                    assertFailsWith<GatewayFailure> { access.read(request) }.problem)
                val malformed = listOf(
                    ReadRequest(LocalFoodProfileV1.QUERY, StoredBytes("{".encodeToByteArray())),
                    ReadRequest(LocalStockRelationalQueriesV2.STATION_PROFILE,
                        LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(input.order))),
                    ReadRequest(LocalFoodStationDirectoryCodecV1.QUERY,
                        LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(input.order))),
                )
                for (request in malformed) assertEquals(LocalServiceProblem.INVALID_INPUT,
                    assertFailsWith<GatewayFailure> { access.read(request) }.problem)
                assertTrue(fixture.master.reads.isEmpty())
                assertTrue(fixture.master.lookups.isEmpty())
                assertTrue(fixture.master.mutations.isEmpty())
                assertTrue(fixture.journal.entries.isEmpty())
            } finally { lease.destroy() }
            // The production internal read boundary is exercised with an inert current view,
            // not an authenticated RPC caller or current Master permission evaluator.
        }
    }

    @Test
    fun krpc001ExcludedOrMalformedSavedBodiesNeverReachOriginalLookupOrExecute() = runTest {
        for (normalized in listOf(false, true)) withKitchenRpcGatewayFixture(
            input.journeys.first { it.normalized == normalized }) { fixture ->
            val intent = LocalCommandIntent(input.startOperation, LocalAction.FOOD_SEND, 1, null, emptyList())
            val body = LocalFoodRequestV1.Send(input.order, input.queued.orderRevision, listOf(input.portion))
            val request = if (normalized) LocalFoodStockCodecV2.command(intent.operationId,
                LocalFoodStockRequestV2.Preparation(body)) else LocalFoodCodecV1.command(intent.operationId, body)
            val original = StoredClientIntentV1.staged(input.owner, intent, request,
                LocalText("Excluded retained Send", "إرسال محفوظ مستبعد"), input.summary())
            fixture.journal.entries[intent.operationId] = original
            assertSavedRefused(fixture, intent.operationId)
            assertEquals(original, fixture.journal.entries.getValue(intent.operationId))
        }
        withKitchenRpcGatewayFixture(input.journeys.first()) { fixture ->
            val original = input.staged(input.journeys.first()).copy(payloadHex = "7b")
            fixture.journal.entries[original.operationId] = original
            assertSavedRefused(fixture, original.operationId)
            assertEquals(original, fixture.journal.entries.getValue(original.operationId))
        }
        // Controlled retained-history rejection only; complete public E01/E02 members
        // and authenticated original/body/result association remain separate bindings.
    }

    @Test
    fun krpc005RecoveryKeepsSavedKeyBytesAndTaskInputWithoutNewProfileOrAutomaticSubmission() = runTest {
        for (journey in input.journeys) withKitchenRpcGatewayFixture(journey) { fixture ->
            val intent = input.intent(journey)
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
            val original = fixture.journal.entries.getValue(journey.operation)
            fixture.normalized = !journey.normalized
            fixture.master.reads.clear()
            fixture.master.readReply = { error("Original recovery must not adopt a fresh task/profile") }

            val pending = assertIs<LocalPendingResult.Available>(fixture.gateway.pending(null)).operations.single()
            assertEquals(journey.operation, pending.operationId)
            assertEquals(journey.action, pending.action)
            assertIs<LocalOperationResult.Pending>(fixture.gateway.lookup(journey.operation))
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
            for (field in listOf(LocalField.TASK_ID, LocalField.STATION_ID,
                LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION)) {
                val changed = intent.values.map { value -> if (value.field != field) value else when (field) {
                    LocalField.TASK_ID -> LocalScalarInput(field, LocalScalarValue.Identifier(input.otherTask))
                    LocalField.STATION_ID -> LocalScalarInput(field, LocalScalarValue.Identifier(input.otherStation))
                    else -> LocalScalarInput(field, LocalScalarValue.Integer(journey.row.orderRevision.value + 1))
                } }
                assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(LocalCommandIntent(
                    journey.operation, journey.action, 1, null, changed)))
            }
            assertEquals(original, fixture.journal.entries.getValue(journey.operation))
            assertEquals(1, fixture.journal.stageCalls)
            assertEquals(0, fixture.journal.dispatchCalls)
            assertTrue(fixture.master.mutations.isEmpty())

            assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(journey.operation))
            assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(journey.operation))
            repeat(2) { assertTrue(assertIs<LocalOperationResult.Recorded>(
                fixture.gateway.lookup(journey.operation)).replayed) }
            val retained = fixture.journal.entries.getValue(journey.operation)
            input.assertOriginal(retained, journey)
            assertTrue(original.sameIntent(retained))
            assertEquals(original.identity(), retained.identity())
            assertTrue(fixture.master.lookups.all { it == original.identity() })
            assertEquals(1, fixture.master.mutations.size)
            assertExactRequest(original.request(), fixture.master.mutations.single())
            assertTrue(fixture.master.reads.isEmpty())
            // The Task ID is retained in UI input identity/summary, not added to the
            // unchanged Station wire body. No actual progressed-task/restart claim.
        }
    }

    @Test
    fun krpc007MismatchedTypedTerminalsAreRejectedBeforeRemembranceOrAcknowledgement() = runTest {
        for (journey in input.journeys) {
            val supplied = input.successBody(journey)
            val mismatches = listOf(
                input.recorded(journey, payload = StoredBytes("{".encodeToByteArray())),
                input.recorded(journey, payload = input.bytes(supplied.copy(orderId = input.otherOrder))),
                input.recorded(journey, payload = input.bytes(supplied.copy(portions = supplied.portions.map {
                    it.copy(portionId = input.otherPortion)
                }))),
                input.recorded(journey, payload = input.bytes(supplied.copy(code =
                    if (journey.action == LocalAction.FOOD_STATION_START) "food.station.ready" else "food.started"))),
                input.recorded(journey, payload = input.bytes(supplied.copy(orderRevision = journey.row.orderRevision))),
                input.recorded(journey, actor = input.otherActor),
                input.recorded(journey, operation = input.otherJourney(journey).operation),
            )
            for (mismatch in mismatches) withKitchenRpcGatewayFixture(journey) { fixture ->
                assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input.intent(journey)))
                val original = fixture.journal.entries.getValue(journey.operation)
                fixture.master.executeReply = { request ->
                    mismatch.also { fixture.master.terminalCarriers[request.operationId] = it }
                }
                assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(journey.operation))
                assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(journey.operation))
                assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(journey.operation))
                val retained = fixture.journal.entries.getValue(journey.operation)
                input.assertOriginal(retained, journey)
                assertTrue(original.sameIntent(retained))
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, retained.localPhase)
                assertNull(retained.recorded)
                assertFalse(retained.acknowledged)
                assertEquals(0, fixture.journal.rememberCalls)
                assertEquals(0, fixture.journal.ackCalls)
                assertEquals(1, fixture.master.mutations.size)
            }
        }
        // Typed/outer carrier substitutions only; no native server result custody or ACK.
    }

    @Test
    fun krpc007FailedSaveOrTerminalRemembranceCannotBecomeAcknowledgedSuccess() = runTest {
        val journey = input.journeys.first { it.normalized }
        withKitchenRpcGatewayFixture(journey) { fixture ->
            fixture.journal.stageCut = WarehouseRpcWriteCut.FAILED
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(input.intent(journey)))
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(journey.operation))
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(journey.operation))
            assertTrue(fixture.journal.entries.isEmpty())
            assertEquals(0, fixture.journal.dispatchCalls)
            assertEquals(0, fixture.journal.ackCalls)
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
        }
        withKitchenRpcGatewayFixture(journey) { fixture ->
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input.intent(journey)))
            val original = fixture.journal.entries.getValue(journey.operation)
            fixture.journal.rememberCut = WarehouseRpcWriteCut.FAILED
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(journey.operation)).problem)
            val unresolved = fixture.journal.entries.getValue(journey.operation)
            input.assertOriginal(unresolved, journey)
            assertTrue(original.sameIntent(unresolved))
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, unresolved.localPhase)
            assertNull(unresolved.recorded)
            assertFalse(unresolved.acknowledged)
            val carrier = fixture.master.terminalCarriers.getValue(journey.operation)
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(journey.operation))
            assertEquals(0, fixture.journal.ackCalls)
            fixture.journal.rememberCut = WarehouseRpcWriteCut.DURABLE
            assertIs<LocalOperationResult.Recorded>(fixture.gateway.lookup(journey.operation))
            assertEquals(StoredKernelResultV1.from(carrier), fixture.journal.entries.getValue(journey.operation).recorded)
            assertEquals(1, fixture.master.mutations.size)
            assertTrue(fixture.master.lookups.all { it == original.identity() })
            // A controlled failed remembrance answer cannot erase the supplied original
            // carrier; this does not observe a real durable server retained-result store.
        }
    }

    @Test
    fun krpc007OnlyAnExactRememberedTerminalReachesTheMatchingClientAcknowledgement() = runTest {
        for (journey in input.journeys.filter { it.action == LocalAction.FOOD_STATION_START })
            withKitchenRpcGatewayFixture(journey) { fixture ->
                assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input.intent(journey)))
                assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(journey.operation))
                assertEquals(0, fixture.journal.ackCalls)
                assertTrue(fixture.master.mutations.isEmpty())
                assertIs<LocalOperationResult.Recorded>(fixture.gateway.submit(journey.operation))
                val original = fixture.journal.entries.getValue(journey.operation)
                val retained = assertNotNull(original.recorded)
                val matching = fixture.master.terminalCarriers.getValue(journey.operation)
                val otherJourney = input.otherJourney(journey)
                val otherCarrier = input.recorded(otherJourney)
                val other = input.staged(otherJourney).copy(
                    phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name,
                    recorded = StoredKernelResultV1.from(otherCarrier))
                other.validate(input.owner)
                fixture.journal.entries[otherJourney.operation] = other
                fixture.master.terminalCarriers[otherJourney.operation] = otherCarrier

                // A fresh terminal for another original cannot acknowledge this marker.
                fixture.master.terminalCarriers[journey.operation] = otherCarrier
                assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(journey.operation))
                assertEquals(0, fixture.journal.ackCalls)
                assertEquals(original, fixture.journal.entries.getValue(journey.operation))
                assertEquals(other, fixture.journal.entries.getValue(otherJourney.operation))

                fixture.master.terminalCarriers[journey.operation] = matching
                fixture.journal.ackCut = WarehouseRpcWriteCut.FAILED
                assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(journey.operation))
                assertFalse(fixture.journal.entries.getValue(journey.operation).acknowledged)
                fixture.journal.ackCut = WarehouseRpcWriteCut.DURABLE
                repeat(2) { assertEquals(journey.operation, assertIs<LocalAcknowledgementResult.Acknowledged>(
                    fixture.gateway.acknowledgeRecorded(journey.operation)).operationId) }
                val acknowledged = fixture.journal.entries.getValue(journey.operation)
                input.assertOriginal(acknowledged, journey)
                assertEquals(retained, acknowledged.recorded)
                assertEquals(original.identity(), acknowledged.identity())
                assertTrue(acknowledged.acknowledged)
                assertEquals(other, fixture.journal.entries.getValue(otherJourney.operation))
                assertEquals(matching, fixture.master.terminalCarriers.getValue(journey.operation))
                assertEquals(otherCarrier, fixture.master.terminalCarriers.getValue(otherJourney.operation))
                assertEquals(1, fixture.master.mutations.size)
                // This port acknowledges only the controlled CLIENT journal. SDK/server
                // ACK/Receipt and retained-result clearing are not exercised. In particular,
                // zero SDK receipts before remembrance is not proved; KRPC-007 L3 is unbound.
            }
    }

    @Test
    fun krpc005AChangedCurrentActorCannotAdoptOrSubmitTheRetainedOriginal() = runTest {
        val journey = input.journeys.first()
        withKitchenRpcGatewayFixture(journey) { fixture ->
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input.intent(journey)))
            val original = fixture.journal.entries.getValue(journey.operation)
            fixture.master.reads.clear()
            fixture.master.view = input.view(input.otherActor)
            assertIs<LocalPendingResult.Unavailable>(fixture.gateway.pending(null))
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(journey.operation))
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(journey.operation))
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(journey.operation))
            assertEquals(original, fixture.journal.entries.getValue(journey.operation))
            assertTrue(fixture.master.reads.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            assertTrue(fixture.master.mutations.isEmpty())
            assertEquals(0, fixture.journal.dispatchCalls)
            assertEquals(0, fixture.journal.ackCalls)
            // Inert current-view owner matching only, not logout/login, a native restart,
            // admitted session replacement or the unchanged whole KBA-010 privacy case.
        }
    }

    private fun assertFreshStationReads(fixture: KitchenRpcGatewayFixture, journey: KitchenRpcGatewayJourney) {
        val requests = fixture.master.reads
        assertEquals(listOf(LocalFoodProfileV1.QUERY, LocalStockRelationalQueriesV2.STATION_PROFILE),
            requests.map { it.query })
        assertEquals(LocalFoodQueryV1.Task(input.station, input.task), LocalFoodReadCodecV1.decode(requests[0].payload))
        assertEquals(input.station, LocalStockRelationalQueriesV2.stationProfileTarget(requests[1].payload))
        assertEquals(LocalStockRelationalQueriesV2.stationProfile(input.station).payload, requests[1].payload)
        assertEquals(journey.row.stationId, input.station)
    }

    private fun assertExactRequest(expected: MutationRequest, actual: MutationRequest) {
        assertEquals(expected.operationId, actual.operationId)
        assertEquals(expected.command, actual.command)
        assertEquals(expected.payload, actual.payload)
        assertEquals(expected.actionApprovalId, actual.actionApprovalId)
    }

    private suspend fun assertSavedRefused(fixture: KitchenRpcGatewayFixture, operation: OperationId) {
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(operation))
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(operation))
        assertIs<LocalPendingResult.Unavailable>(fixture.gateway.pending(null))
        assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(operation))
        assertTrue(fixture.master.reads.isEmpty())
        assertTrue(fixture.master.lookups.isEmpty())
        assertTrue(fixture.master.mutations.isEmpty())
        assertEquals(0, fixture.journal.stageCalls)
        assertEquals(0, fixture.journal.dispatchCalls)
        assertEquals(0, fixture.journal.rememberCalls)
        assertEquals(0, fixture.journal.ackCalls)
    }
}

private data class KitchenRpcGatewayJourney(val name: String, val normalized: Boolean,
    val operation: OperationId, val action: LocalAction, val row: LocalFoodStationTaskViewV1) {
    val resultCode: String get() = if (action == LocalAction.FOOD_STATION_START) "food.started" else "food.station.ready"
}

/** Literal frozen semantic inputs; auxiliary coordinates provide no native source or authority. */
private object FrozenKitchenRpcGatewayInputs {
    val scope = BranchScope(DomainId("10000000-0000-4000-8000-000000000001"),
        DomainId("10000000-0000-4000-8000-000000000002"), DomainId("10000000-0000-4000-8000-000000000003"))
    val actor = DomainId("60000000-0000-4000-8000-000000000001")
    val otherActor = DomainId("60000000-0000-4000-8000-000000000002")
    val device = DomainId("70000000-0000-4000-8000-000000000001")
    val owner = LocalClientIdentity(scope, actor, device)
    val station = DomainId("20000000-0000-4000-8000-000000000001")
    val otherStation = DomainId("20000000-0000-4000-8000-000000000002")
    val task = DomainId("50000000-0000-4000-8000-000000000011")
    val otherTask = DomainId("50000000-0000-4000-8000-000000000099")
    val order = DomainId("30000000-0000-4000-8000-000000000001")
    val otherOrder = DomainId("30000000-0000-4000-8000-000000000099")
    val portion = DomainId("40000000-0000-4000-8000-000000000001")
    val otherPortion = DomainId("40000000-0000-4000-8000-000000000099")
    val startOperation = OperationId("80000000-0000-4000-8000-000000000001")
    val readyOperation = OperationId("80000000-0000-4000-8000-000000000002")
    private val unit = auxiliary(1)
    private val issue = auxiliary(2)
    private val session = auxiliary(3)
    private val connection = auxiliary(4)
    private val clock = auxiliary(5)
    private fun auxiliary(ordinal: Int) = DomainId("b0000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    private val metadataRevision = Revision(1)
    private val at = UtcInstant.parseCanonical("2026-11-01T12:00:00Z")
    private val wire = Json { encodeDefaults = true }
    val queued = LocalFoodStationTaskViewV1(order, Revision(41), portion, Revision(17), task, station,
        "Synthetic issued portion", Quantity(unit, 1, 0), "", "Synthetic issued order",
        FoodStationStatus.QUEUED, FoodPreparationStatus.QUEUED, FoodStationDeliveryMode.DISPLAY)
    private val started = queued.copy(orderRevision = Revision(42), portionRevision = Revision(18),
        taskStatus = FoodStationStatus.STARTED, preparationStatus = FoodPreparationStatus.PREPARING)
    val journeys = listOf(
        KitchenRpcGatewayJourney("legacy-Start", false, startOperation, LocalAction.FOOD_STATION_START, queued),
        KitchenRpcGatewayJourney("legacy-Ready", false, readyOperation, LocalAction.FOOD_STATION_READY, started),
        KitchenRpcGatewayJourney("normalized-Start", true, startOperation, LocalAction.FOOD_STATION_START, queued),
        KitchenRpcGatewayJourney("normalized-Ready", true, readyOperation, LocalAction.FOOD_STATION_READY, started),
    )
    fun otherJourney(journey: KitchenRpcGatewayJourney): KitchenRpcGatewayJourney = journeys.single {
        it.normalized == journey.normalized && it.action != journey.action
    }
    fun values(row: LocalFoodStationTaskViewV1): List<LocalScalarInput> = listOf(
        LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(row.orderId)),
        LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(row.orderRevision.value)),
        LocalScalarInput(LocalField.PORTION_ID, LocalScalarValue.Identifier(row.portionId)),
        LocalScalarInput(LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION, LocalScalarValue.Integer(row.portionRevision.value)),
        LocalScalarInput(LocalField.STATION_ID, LocalScalarValue.Identifier(row.stationId)),
        LocalScalarInput(LocalField.TASK_ID, LocalScalarValue.Identifier(row.taskId)),
    )
    fun intent(journey: KitchenRpcGatewayJourney) = LocalCommandIntent(journey.operation, journey.action, 1, null, values(journey.row))
    fun stationRequest(journey: KitchenRpcGatewayJourney) = LocalFoodRequestV1.Station(order, journey.row.orderRevision,
        portion, journey.row.portionRevision, station,
        if (journey.action == LocalAction.FOOD_STATION_START) LocalFoodStationActionV1.START else LocalFoodStationActionV1.READY)
    fun request(journey: KitchenRpcGatewayJourney): MutationRequest = if (journey.normalized)
        LocalFoodStockCodecV2.command(journey.operation, LocalFoodStockRequestV2.Preparation(stationRequest(journey)))
    else LocalFoodCodecV1.command(journey.operation, stationRequest(journey))
    fun summary() = listOf(LocalRecordValue(LocalText("Retained original", "الأصل المحفوظ"), LocalText(task.value, task.value)))
    fun staged(journey: KitchenRpcGatewayJourney) = StoredClientIntentV1.staged(owner, intent(journey), request(journey),
        LocalText("Retained kitchen original", "أصل مطبخ محفوظ"), summary())

    fun assertOriginal(entry: StoredClientIntentV1, journey: KitchenRpcGatewayJourney) {
        entry.validate(owner)
        assertEquals(owner, entry.owner)
        assertEquals(journey.operation, entry.operationId)
        assertEquals(journey.action, entry.localAction)
        assertEquals(1, entry.formVersion)
        assertEquals(if (journey.normalized) "sales.food.stock/2" else "sales.food.write/1",
            "${entry.commandName}/${entry.commandVersion}")
        assertEquals(request(journey).command, entry.request().command)
        assertEquals(request(journey).payload, entry.request().payload)
        assertNull(entry.actionApproval)
        assertNull(entry.request().actionApprovalId)
        val station = if (journey.normalized) assertIs<LocalFoodStockRequestV2.Preparation>(
            LocalFoodStockCodecV2.decode(entry.request().payload)).request else LocalFoodCodecV1.decode(entry.request().payload)
        assertEquals(stationRequest(journey), assertIs<LocalFoodRequestV1.Station>(station))
        assertEquals(inputDigest(intent(journey)), entry.inputDigest)
        assertTrue(entry.summary.any { journey.row.taskId.value in it.value.english },
            "The selected Task ID remains in the retained UI summary; it is not a new wire field")
    }

    /** Current-view construction and grants are display/continuation hints, not activated authority. */
    fun view(actor: DomainId = this.actor): LocalSessionView {
        val permission = Permission(SecurityAction.KITCHEN_TASK, PermissionVerb.PERFORM)
        val grants = listOf(PermissionGrant(permission, scope, GrantEffect.ALLOW, targetIds = setOf(station, otherStation)))
        val policy = ActivatedLocalPolicy(scope, actor, metadataRevision, grants, LocalSessionPolicy(), ThrottlePolicy())
        return LocalSessionView(actor, scope, device, session, SessionGeneration(1), LocalLoginIdentifier("synthetic-kitchen-rpc"),
            policy, MonotonicMoment(clock, 1), MonotonicMoment(clock, 2))
    }
    fun credentials() = LocalGatewayCredentials(connection, OpaqueCredential(ByteArray(16)))
    fun taskData(journey: KitchenRpcGatewayJourney) = ReadReply.Data(StoredBytes(wire.encodeToString(
        LocalFoodReadResultV1.serializer(), LocalFoodReadResultV1(tasks = listOf(journey.row))).encodeToByteArray()), metadataRevision, at)
    fun profileData(normalized: Boolean) = ReadReply.Data(StoredBytes(
        "{\"version\":2,\"normalized\":$normalized}".encodeToByteArray()), metadataRevision, at)
    fun bytes(result: LocalFoodResultV1) = StoredBytes(wire.encodeToString(LocalFoodResultV1.serializer(), result).encodeToByteArray())

    /** Supplied valid terminal uses production's typed-revision binding only. It is not
     * an independently asserted business increment, stock, issue or multi-station oracle. */
    fun successBody(journey: KitchenRpcGatewayJourney) = LocalFoodResultV1(journey.resultCode, order,
        journey.row.orderRevision.next(), listOf(LocalFoodPortionRevisionV1(portion, journey.row.portionRevision.next(),
            FoodPreparationStatus.PREPARING, issue)))
    fun recorded(journey: KitchenRpcGatewayJourney, payload: StoredBytes = bytes(successBody(journey)),
        operation: OperationId = journey.operation, actor: DomainId = this.actor) = MutationReply.Recorded(
        operation, actor, true, "committed", payload, metadataRevision, JournalSequence(1), at, false)
    fun replay(reply: MutationReply.Recorded) = MutationReply.Recorded(reply.operationId, reply.originalActorId,
        reply.committed, reply.code, reply.result, reply.authorityEpoch, reply.sequence, reply.recordedAt, true)
}

/** Existing controlled order ports are reused; their maps/answers are not native durability. */
private class KitchenRpcGatewayFixture(val journey: KitchenRpcGatewayJourney) {
    private val input = FrozenKitchenRpcGatewayInputs
    val events = mutableListOf<String>()
    var normalized = journey.normalized
    val master = WarehouseRpcControlledMaster(events).also { master ->
        master.view = input.view()
        master.currentReply = { LocalSessionViewResult.Current(master.view) }
        master.readReply = ::allowedRead
        master.lookupReply = { identity -> master.terminalCarriers[identity.operationId]?.let {
            LookupReply.Found(input.replay(it))
        } ?: LookupReply.AbsentAtCurrentWriter(identity.operationId) }
        master.executeReply = { request ->
            assertEquals(input.request(journey).command, request.command)
            assertEquals(input.request(journey).payload, request.payload)
            assertEquals(journey.operation, request.operationId)
            input.recorded(journey).also { master.terminalCarriers[request.operationId] = it }
        }
    }
    val journal = WarehouseRpcControlledJournal(input.owner, events)
    private val leases = mutableListOf<LocalWorkflowCredentialLease>()
    private val credentials = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(input.credentials()) { true }.also(leases::add)
    }
    val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.KITCHEN)
    val gateway = LocalEndpointWorkflowGateway(master, journal, credentials, installed)

    fun allowedRead(request: ReadRequest): ReadReply = when (request.query) {
        LocalFoodProfileV1.QUERY -> {
            assertEquals(LocalFoodQueryV1.Task(input.station, input.task), LocalFoodReadCodecV1.decode(request.payload))
            input.taskData(journey)
        }
        LocalStockRelationalQueriesV2.STATION_PROFILE -> {
            assertEquals(input.station, LocalStockRelationalQueriesV2.stationProfileTarget(request.payload))
            input.profileData(normalized)
        }
        else -> error("No controlled kitchen reply for ${request.query}")
    }
    suspend fun close() { leases.forEach { it.destroy() }; journal.close() }
}

private suspend fun <T> withKitchenRpcGatewayFixture(journey: KitchenRpcGatewayJourney,
    body: suspend (KitchenRpcGatewayFixture) -> T): T {
    val fixture = KitchenRpcGatewayFixture(journey)
    return try { body(fixture) } finally { fixture.close() }
}
