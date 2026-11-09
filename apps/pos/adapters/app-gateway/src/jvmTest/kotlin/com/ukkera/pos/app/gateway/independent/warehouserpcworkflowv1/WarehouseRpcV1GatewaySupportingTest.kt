@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * Frozen WH-RPC-001..010/014/015/017/018/019 supporting portions over the actual shared
 * directory workflow. External service replies, current-session observations, journal writes
 * and event barriers are controlled SUPPORT ports, not authentication, protected durability,
 * Master commit/output fences, native ownership, RPC, a restart or F-GUARDS ledger evidence.
 * No whole case or QUALIFICATION result is established by these methods.
 *
 * The frozen 50+3 and 53 singleton pages are checked at configurationRead's typed boundary.
 * Shared UI browse/reference requests remain 25; their checks below do not substitute a
 * 25-row traversal for WH-RPC-002's frozen limit-50/limit-1 usable-UI expectation. Touch,
 * keyboard, RTL/LTR rendering, language switching and genuine changed-cut runs remain unrun.
 */
class WarehouseRpcV1GatewaySupportingTest {
    private val frozen get() = FrozenWarehouseRpcV1

    @Test
    fun wh001wh019ExplicitWarehouseInstallationRejectsUnrelatedWorkBeforePorts() = runTest(timeout = 5.seconds) {
        withWarehouseRpcFixture { f ->
            val queries = listOf(LocalWorkflowQuery(LocalWorkflow.CATALOG), LocalWorkflowQuery(LocalWorkflow.CASH),
                LocalWorkflowQuery(LocalWorkflow.PURCHASING),
                LocalWorkflowQuery(LocalWorkflow.RECEIVING),
                frozen.query(reference = LocalRecordReference(LocalRecordKind.SUPPLIER, frozen.first.id)),
                frozen.query(reference = frozen.reference().copy(parentId = frozen.second.id)))
            for (query in queries) assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertIs<LocalReadResult.Unavailable>(f.gateway.read(query)).problem)
            for (action in listOf(LocalAction.SUPPLIER_CREATE, LocalAction.SUPPLIER_UPDATE, LocalAction.TENDER_CONFIGURE,
                LocalAction.SALES_ROUTE_CONFIGURE, LocalAction.CATALOG_ACTIVATE, LocalAction.RECEIVING_APPROVE,
                LocalAction.STOCK_OPENING, LocalAction.PAYMENT_ATTEST)) {
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, assertIs<LocalStageResult.NotStaged>(f.gateway.stage(
                    LocalCommandIntent(frozen.createOperation, action, 1, null, emptyList()))).problem)
            }
            assertIs<LocalReferenceResult.Unavailable>(f.gateway.references(LocalReferenceQuery(LocalReferenceSource(LocalRecordKind.SUPPLIER))))
            val supplier = LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ConfigureSupplier(null,
                LocalSupplierV1(frozen.created.id, frozen.created.names, true)))
            for (request in listOf(MutationRequest(frozen.createOperation, LocalConfigurationProfile.COMMAND, supplier),
                MutationRequest(frozen.createOperation, LocalConfigurationProfile.COMMAND, frozen.request(frozen.create()).payload,
                    frozen.slot("approval-selector")))) {
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, assertFailsWith<GatewayFailure> {
                    f.installed.requireMutation(LocalAction.WAREHOUSE_CONFIGURE, request)
                }.problem)
            }
            assertIs<LocalActionApprovalResult.Unavailable>(f.gateway.previewActionApproval(frozen.createOperation))
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
            assertEquals(0, f.journal.stageCalls)
        }
        withWarehouseRpcFixture(LocalMasterRpcWorkflowProfile.SUPPLIERS) { f ->
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertIs<LocalStageResult.NotStaged>(f.gateway.stage(frozen.create())).problem)
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertIs<LocalReadResult.Unavailable>(f.gateway.read(frozen.query())).problem)
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.reads.isEmpty())
        }
    }

    @Test
    fun wh002FrozenFiftyAndOneRowTraversalsKeepEveryIdentityNameAndObservedRevision() = runTest(timeout = 5.seconds) {
        for (limit in listOf(50, 1)) withWarehouseRpcFixture { f ->
            f.withReadAccess { access ->
                val cuts = mutableListOf<ConfigurationCut>()
                var after: com.ukkera.pos.types.DomainId? = null
                do {
                    val cut = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, after = after, limit = limit))
                    cuts += cut
                    after = cut.page.nextAfter
                    assertTrue(cuts.size <= frozen.warehouses.size, "A repeated frontier must not loop")
                } while (after != null)
                val pagination = frozen.directoryInputs.getValue("expected_pagination").jsonObject
                if (limit == 50) assertEquals(pagination.getValue("limit_50_page_counts").jsonArray.map { it.jsonPrimitive.int },
                    cuts.map { it.page.items.size })
                else {
                    assertEquals(pagination.getValue("limit_1_page_count").jsonPrimitive.int, cuts.size)
                    assertTrue(cuts.all { it.page.items.size == 1 })
                }
                val rows = cuts.flatMap { it.page.items }
                assertEquals(frozen.warehouses.map { it.id }, rows.map { it.id })
                assertEquals(pagination.getValue("union_unique_ids").jsonPrimitive.int, rows.map { it.id }.distinct().size)
                assertEquals(frozen.warehouses.map { it.names }, rows.map { it.names })
                assertTrue(rows.all { it.active == true && it.storageRevision == frozen.storageRevision })
                assertTrue(cuts.all { it.reply.branchRevision == frozen.observedRevision })
                assertNull(cuts.last().page.nextAfter)
                assertTrue(f.master.mutations.isEmpty())
            }
        }
    }

    @Test
    fun wh002wh005SharedBrowseAndDetailPreserveBothNamesAndDisplayedStorageCas() = runTest(timeout = 5.seconds) {
        withWarehouseRpcFixture { f ->
            val browse = assertIs<LocalReadResult.Available>(f.gateway.read(frozen.query())).page
            assertEquals(25, LocalConfigurationCodec.decodeQuery(f.master.reads.single().payload).limit,
                "Representation binding only: shared UI pages are not the frozen limit-50 run")
            assertEquals(frozen.warehouses.take(25).map { it.id }, browse.records.map { it.reference.id })
            assertEquals(frozen.warehouses.take(25).map { LocalText(it.names.english, it.names.arabic) }, browse.records.map { it.title })
            assertEquals(listOf(LocalAction.WAREHOUSE_CONFIGURE), browse.forms.map { it.action })
            assertEquals(LocalScalarValue.Flag(true), browse.forms.single().initial(LocalField.ACTIVE))
            val selected = browse.records.first().reference
            val detail = assertIs<LocalReadResult.Available>(f.gateway.read(frozen.query(reference = selected))).page
            assertEquals(frozen.first.id, detail.records.single().reference.id)
            assertEquals(LocalText(frozen.first.names.english, frozen.first.names.arabic), detail.records.single().title)
            val edit = detail.forms.single()
            assertEquals(LocalScalarValue.Identifier(frozen.first.id), edit.initial(LocalField.WAREHOUSE_ID))
            assertEquals(LocalScalarValue.Integer(frozen.storageRevision.value), edit.initial(LocalField.EXPECTED_STORAGE_REVISION))
            assertEquals(LocalScalarValue.Flag(false), edit.initial(LocalField.NEW_ITEM))
            assertEquals(LocalScalarValue.Flag(true), edit.initial(LocalField.ACTIVE))
            assertEquals(LocalScalarValue.Text(frozen.first.names.english), edit.initial(LocalField.NAME_ENGLISH))
            assertEquals(LocalScalarValue.Text(frozen.first.names.arabic), edit.initial(LocalField.NAME_ARABIC))
            assertTrue(assertNotNull(detail.notice).english.contains(frozen.observedRevision.value.toString()))
            assertTrue(assertNotNull(detail.notice).arabic.contains(frozen.observedRevision.value.toString()))
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.journal.entries.isEmpty())
        }
    }

    @Test
    fun wh003WholePageValidationPrecedesFilteringAndRefusalIsNotEmptySuccess() = runTest(timeout = 5.seconds) {
        val normal = frozen.warehouses.take(25).map(frozen::row)
        val page = LocalConfigurationPageV1(LocalConfigurationProfile.WAREHOUSE, normal, normal.last().id)
        val invalidPayloads = mutableListOf(
            frozen.data(page.copy(items = frozen.warehouses.take(51).map(frozen::row))).payload,
            frozen.data(page.copy(kind = LocalConfigurationProfile.SUPPLIER)).payload,
            frozen.data(page.copy(items = normal.dropLast(1) + normal.first())).payload,
            frozen.data(page.copy(items = normal.dropLast(1) + normal.last().copy(storageRevision = Revision(1)))).payload,
            frozen.data(page.copy(items = normal.dropLast(1) + normal.last().copy(active = null))).payload,
        )
        val encoded = frozen.wire.encodeToJsonElement(LocalConfigurationPageV1.serializer(), page).jsonObject
        for (control in frozen.invalidInputs.getValue("control_characters").jsonArray) {
            val character = control.jsonPrimitive.content.removePrefix("U+").toInt(16).toChar()
            invalidPayloads += replaceLastPageRow(encoded) { row ->
                val names = row.getValue("names").jsonObject
                JsonObject(row + ("names" to JsonObject(names + ("english" to JsonPrimitive("hidden${character}invalid")))))
            }
        }
        invalidPayloads += replaceLastPageRow(encoded) { JsonObject(it + ("id" to JsonPrimitive("not-a-warehouse-id"))) }
        for (payload in invalidPayloads) withWarehouseRpcFixture { f ->
            f.master.readReply = { ReadReply.Data(payload, frozen.observedRevision, frozen.at) }
            assertIs<LocalReadResult.Unavailable>(f.gateway.read(frozen.query(search = frozen.first.names.english)))
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.journal.entries.isEmpty())
        }
        for (reason in listOf(ReadFailure.AUTHORIZATION_DENIED, ReadFailure.STORAGE_UNCERTAIN,
            ReadFailure.UNKNOWN_QUERY, ReadFailure.SERVICE_BUSY)) withWarehouseRpcFixture { f ->
            f.master.readReply = { ReadReply.Unavailable(reason) }
            assertIs<LocalReadResult.Unavailable>(f.gateway.read(frozen.query()))
        }
        withWarehouseRpcFixture { f ->
            f.master.readReply = { frozen.data(LocalConfigurationPageV1(LocalConfigurationProfile.WAREHOUSE, emptyList(), null)) }
            val empty = assertIs<LocalReadResult.Available>(f.gateway.read(frozen.query())).page
            assertTrue(empty.records.isEmpty())
            assertNull(empty.nextCursor)
        }
    }

    @Test
    fun wh003wh006BadSelectorsAndCreationEditShapeNeverReachMutationOrRetention() = runTest(timeout = 5.seconds) {
        withWarehouseRpcFixture { f ->
            for (cursor in listOf("not-a-cursor", "cfg|${LocalConfigurationProfile.SUPPLIER}|-",
                "cfg|${LocalConfigurationProfile.WAREHOUSE}|not-an-id"))
                assertIs<LocalReadResult.Unavailable>(f.gateway.read(frozen.query(cursor = cursor)))
            val invalid = listOf(frozen.intent(frozen.createOperation, frozen.created, frozen.storageRevision),
                frozen.intent(frozen.renameOperation, frozen.renamed, null, frozen.reference()),
                frozen.intent(frozen.renameOperation, frozen.renamed, frozen.storageRevision, frozen.reference().copy(revision = null)),
                frozen.intent(frozen.renameOperation, frozen.renamed, frozen.storageRevision.next(), frozen.reference()),
                frozen.intent(frozen.renameOperation, frozen.renamed, frozen.storageRevision, frozen.reference().copy(revision = Revision(Long.MAX_VALUE))),
                frozen.intent(frozen.renameOperation, frozen.renamed, frozen.storageRevision, frozen.reference(frozen.second)),
                frozen.intent(frozen.createOperation, frozen.created, null, version = 2))
            for (intent in invalid) assertIs<LocalStageResult.NotStaged>(f.gateway.stage(intent))
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertEquals(0, f.journal.stageCalls)
        }
    }

    @Test
    fun wh004ControlledIntentDispatchAndResultRetentionCutsPrecedeSuccessAndAck() = runTest(timeout = 5.seconds) {
        withWarehouseRpcFixture { f ->
            val intent = frozen.create()
            val save = WarehouseRpcGate().also { f.journal.stageGate = it }
            val staged = async { f.gateway.stage(intent) }
            runCurrent()
            assertTrue(save.entered.isCompleted)
            assertFalse(staged.isCompleted)
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.journal.entries.isEmpty())
            save.release()
            assertIs<LocalStageResult.Staged>(staged.await())
            frozen.assertOriginal(intent, f.journal.entries.getValue(intent.operationId))
            val dispatch = WarehouseRpcGate().also { f.journal.dispatchStoredGate = it }
            val retention = WarehouseRpcGate().also { f.journal.rememberGate = it }
            val submitted = async { f.gateway.submit(intent.operationId) }
            runCurrent()
            assertTrue(dispatch.entered.isCompleted)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, f.journal.entries.getValue(intent.operationId).localPhase)
            assertFalse(submitted.isCompleted)
            assertTrue(f.master.mutations.isEmpty())
            dispatch.release()
            runCurrent()
            assertTrue(retention.entered.isCompleted)
            assertEquals(1, f.master.mutations.size)
            assertFalse(submitted.isCompleted)
            assertNull(f.journal.entries.getValue(intent.operationId).recorded)
            retention.release()
            val result = assertIs<LocalOperationResult.Recorded>(submitted.await())
            assertEquals(LocalRecordedDecision.APPLIED, result.decision)
            assertEquals(intent.operationId, result.operationId)
            assertEquals(frozen.created.id, assertNotNull(result.result).reference.id)
            val retained = f.journal.entries.getValue(intent.operationId)
            frozen.assertOriginal(intent, retained)
            assertEquals(StoredKernelResultV1.from(f.master.terminalCarriers.getValue(intent.operationId)), retained.recorded)
            assertTrue(f.events.indexOf("intent.retained") < f.events.indexOf("dispatch.retained"))
            assertTrue(f.events.indexOf("dispatch.retained") < f.events.indexOf("execute.enter"))
            assertFalse(retained.acknowledged)
            val ack = WarehouseRpcGate().also { f.journal.ackGate = it }
            val acknowledged = async { f.gateway.acknowledgeRecorded(intent.operationId) }
            runCurrent()
            assertTrue(ack.entered.isCompleted)
            assertFalse(acknowledged.isCompleted)
            assertTrue(f.journal.cleared.isEmpty())
            ack.release()
            assertIs<LocalAcknowledgementResult.Acknowledged>(acknowledged.await())
            assertEquals(listOf(intent.operationId), f.journal.cleared)
            assertEquals(1, f.master.mutations.size)
            assertTrue(f.master.terminalCarriers.containsKey(intent.operationId))
            frozen.assertOriginal(intent, f.journal.entries.getValue(intent.operationId))
        }
    }

    @Test
    fun wh005wh007RenameAndInactiveRefusalKeepTheirOwnFrozenOriginalThroughRecoveryAck() = runTest(timeout = 5.seconds) {
        for (intent in listOf(frozen.rename(), frozen.retire(existing = true), frozen.retire(existing = false)))
            withWarehouseRpcFixture { f ->
                assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
                val result = assertIs<LocalOperationResult.Recorded>(f.gateway.submit(intent.operationId))
                val body = frozen.body(intent)
                val inactive = !body.warehouse.active
                assertEquals(if (inactive) LocalRecordedDecision.REJECTED else LocalRecordedDecision.APPLIED, result.decision)
                val terminal = f.master.terminalCarriers.getValue(intent.operationId)
                assertEquals(!inactive, terminal.committed)
                if (inactive) {
                    assertEquals(frozen.retirementCode, terminal.code)
                    assertEquals(LocalConfigurationResultV1(frozen.retirementCode, null, null), LocalConfigurationCodec.decodeResult(terminal.result))
                } else {
                    assertEquals(frozen.renamed.id, assertNotNull(result.result).reference.id)
                    assertEquals(frozen.storageRevision, body.expectedStorageRevision)
                }
                frozen.assertOriginal(intent, f.journal.entries.getValue(intent.operationId))
                assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(intent.operationId))
                assertIs<LocalAcknowledgementResult.Acknowledged>(f.gateway.acknowledgeRecorded(intent.operationId))
                frozen.assertOriginal(intent, f.journal.entries.getValue(intent.operationId))
                assertEquals(1, f.master.mutations.size)
                assertEquals(!inactive, f.master.terminalCarriers.getValue(intent.operationId).committed)
                assertTrue(f.master.lookups.all { it == f.journal.entries.getValue(intent.operationId).identity() })
            }
        // A controlled refusal carrier cannot prove unchanged stock/reservations or count 53.
    }

    @Test
    fun wh008FailedSaveOrDispatchWriteInvokesNoMutator() = runTest(timeout = 5.seconds) {
        for (cut in listOf(WarehouseRpcWriteCut.FAILED, WarehouseRpcWriteCut.RETAINED_REPLY_UNKNOWN)) {
            withWarehouseRpcFixture { f ->
                f.journal.stageCut = cut
                assertIs<LocalStageResult.Uncertain>(f.gateway.stage(frozen.create()))
                assertTrue(f.master.mutations.isEmpty())
                assertEquals(0, f.journal.dispatchCalls)
                f.journal.entries[frozen.createOperation]?.let { frozen.assertOriginal(frozen.create(), it) }
            }
            withWarehouseRpcFixture { f ->
                assertIs<LocalStageResult.Staged>(f.gateway.stage(frozen.create()))
                f.journal.dispatchCut = cut
                assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(frozen.createOperation))
                assertTrue(f.master.mutations.isEmpty())
                frozen.assertOriginal(frozen.create(), f.journal.entries.getValue(frozen.createOperation))
            }
        }
    }

    @Test
    fun wh008SavedIdNamesRevisionActiveAndDigestCannotBeReplacedByUiEditsOrRepeat() = runTest(timeout = 5.seconds) {
        withWarehouseRpcFixture { f ->
            val original = frozen.create()
            assertIs<LocalStageResult.Staged>(f.gateway.stage(original))
            val before = f.journal.entries.getValue(original.operationId)
            val edited = listOf(frozen.intent(original.operationId, frozen.created.copy(id = frozen.second.id), null),
                frozen.intent(original.operationId, frozen.created.copy(names = frozen.renamed.names), null),
                frozen.intent(original.operationId, frozen.created.copy(active = false), null),
                frozen.intent(original.operationId, frozen.created, frozen.storageRevision, frozen.reference(frozen.created)))
            for (intent in edited) {
                assertIs<LocalStageResult.Uncertain>(f.gateway.stage(intent))
                assertEquals(before, f.journal.entries.getValue(original.operationId))
            }
            repeat(2) { assertIs<LocalStageResult.Staged>(f.gateway.stage(original)) }
            assertEquals(LocalServiceProblem.RETRY_LATER,
                assertIs<LocalStageResult.NotStaged>(f.gateway.stage(frozen.rename())).problem)
            assertEquals(1, f.journal.stageCalls)
            assertTrue(f.master.mutations.isEmpty())
            assertIs<LocalOperationResult.Recorded>(f.gateway.submit(original.operationId))
            assertEquals(before.request().payload, f.master.mutations.single().payload)
            frozen.assertOriginal(original, f.journal.entries.getValue(original.operationId))
        }
    }

    @Test
    fun wh009wh010CancelledLostDeliveryRecoversOnlyTheOriginalAndDoesNotReexecute() = runTest(timeout = 5.seconds) {
        withWarehouseRpcFixture { f ->
            assertIs<LocalStageResult.Staged>(f.gateway.stage(frozen.create()))
            val held = WarehouseRpcGate().also { f.master.executeGate = it }
            val submitted = async { f.gateway.submit(frozen.createOperation) }
            runCurrent()
            assertTrue(held.entered.isCompleted)
            assertFalse(submitted.isCompleted)
            assertEquals(1, f.master.mutations.size)
            submitted.cancelAndJoin()
            held.release()
            assertNull(f.journal.entries.getValue(frozen.createOperation).recorded)
            assertFalse(f.journal.entries.getValue(frozen.createOperation).acknowledged)
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, f.journal.entries.getValue(frozen.createOperation).localPhase)
            frozen.assertOriginal(frozen.create(), f.journal.entries.getValue(frozen.createOperation))
            // Retry while dispatch is unresolved, before the explicit lookup. This controlled
            // lookup can recover the original, but retry must not execute it again.
            f.gateway.submit(frozen.createOperation)
            assertEquals(1, f.master.mutations.size)
            frozen.assertOriginal(frozen.create(), f.journal.entries.getValue(frozen.createOperation))
            val recovered = assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(frozen.createOperation))
            assertEquals(frozen.createOperation, recovered.operationId)
            assertTrue(recovered.replayed)
            assertIs<LocalOperationResult.Recorded>(f.gateway.submit(frozen.createOperation))
            assertEquals(1, f.master.mutations.size)
            frozen.assertOriginal(frozen.create(), f.journal.entries.getValue(frozen.createOperation))
        }
    }

    @Test
    fun wh010BadResultOrFailedRetentionCannotBecomeSuccessfulPresentationOrAck() = runTest(timeout = 5.seconds) {
        for (cut in listOf(WarehouseRpcWriteCut.FAILED, WarehouseRpcWriteCut.RETAINED_REPLY_UNKNOWN))
            withWarehouseRpcFixture { f ->
                assertIs<LocalStageResult.Staged>(f.gateway.stage(frozen.create()))
                f.journal.rememberCut = cut
                assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(frozen.createOperation))
                assertFalse(f.journal.entries.getValue(frozen.createOperation).acknowledged)
                frozen.assertOriginal(frozen.create(), f.journal.entries.getValue(frozen.createOperation))
                // ACK performs a fresh lookup itself. Keep that lookup unresolved here: a
                // decoded receipt or an unknown write reply alone cannot clear the original.
                val availableLookup = f.master.lookupReply
                f.master.lookupReply = { LookupReply.Deferred(it.operationId, DeferredReason.STORAGE_UNCERTAIN) }
                try {
                    assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(frozen.createOperation))
                    assertEquals(0, f.journal.ackCalls)
                    assertTrue(f.journal.cleared.isEmpty())
                    assertFalse(f.journal.entries.getValue(frozen.createOperation).acknowledged)
                    assertIs<LocalPendingResult.Unavailable>(f.gateway.pending())
                    frozen.assertOriginal(frozen.create(), f.journal.entries.getValue(frozen.createOperation))
                } finally { f.master.lookupReply = availableLookup }
                // A retained-but-unknown write can be recovered through a fresh matching lookup;
                // it must not have been presented as success by the failed submit call.
                f.journal.rememberCut = WarehouseRpcWriteCut.DURABLE
                assertIs<LocalOperationResult.Recorded>(f.gateway.lookup(frozen.createOperation))
                assertEquals(1, f.master.mutations.size)
            }
        for (inactive in listOf(false, true)) withWarehouseRpcFixture { f ->
            val intent = if (inactive) frozen.retire(existing = true) else frozen.create()
            assertIs<LocalStageResult.Staged>(f.gateway.stage(intent))
            val invalid = if (inactive) frozen.recorded(frozen.request(frozen.rename()))
                .let { frozen.replay(it, operation = intent.operationId) }
            else frozen.recorded(frozen.request(intent)).let { frozen.replay(it,
                result = StoredBytes("{\"unsupported\":true}".encodeToByteArray())) }
            f.master.executeReply = { invalid }
            assertIs<LocalOperationResult.Unresolved>(f.gateway.submit(intent.operationId))
            assertEquals(0, f.journal.rememberCalls)
            assertNull(f.journal.entries.getValue(intent.operationId).recorded)
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(intent.operationId))
            assertTrue(f.journal.cleared.isEmpty())
            frozen.assertOriginal(intent, f.journal.entries.getValue(intent.operationId))
        }
    }

    @Test
    fun wh014PrematureMismatchedFailedAndRepeatedAckClearOnlyTheMatchingMarker() = runTest(timeout = 5.seconds) {
        withWarehouseRpcFixture { f ->
            assertIs<LocalStageResult.Staged>(f.gateway.stage(frozen.create()))
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(frozen.createOperation))
            assertTrue(f.journal.cleared.isEmpty())
            assertIs<LocalOperationResult.Recorded>(f.gateway.submit(frozen.createOperation))
            val result = f.master.terminalCarriers.getValue(frozen.createOperation)
            val mismatches = listOf(frozen.replay(result, operation = frozen.otherOperation),
                frozen.replay(result, actor = frozen.actorB), frozen.replay(result, sequence = JournalSequence(2)),
                frozen.replay(result, result = frozen.recorded(frozen.request(frozen.rename())).result))
            for (mismatch in mismatches) {
                f.master.lookupReply = { LookupReply.Found(mismatch) }
                assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(frozen.createOperation))
                assertTrue(f.journal.cleared.isEmpty())
                assertFalse(f.journal.entries.getValue(frozen.createOperation).acknowledged)
            }
            f.master.lookupReply = { LookupReply.Found(frozen.replay(result)) }
            f.journal.ackCut = WarehouseRpcWriteCut.FAILED
            assertIs<LocalAcknowledgementResult.Unresolved>(f.gateway.acknowledgeRecorded(frozen.createOperation))
            assertTrue(f.journal.cleared.isEmpty())
            f.journal.ackCut = WarehouseRpcWriteCut.DURABLE
            repeat(2) { assertIs<LocalAcknowledgementResult.Acknowledged>(f.gateway.acknowledgeRecorded(frozen.createOperation)) }
            assertEquals(listOf(frozen.createOperation), f.journal.cleared)
            assertEquals(1, f.master.mutations.size)
            assertEquals(result, f.master.terminalCarriers.getValue(frozen.createOperation))
            frozen.assertOriginal(frozen.create(), f.journal.entries.getValue(frozen.createOperation))
        }
        // A separate native owner B, unresolved physical evidence E, ACK reply-loss/reopen and
        // Master history/audit retention are not replaced with fabricated map assertions.
    }

    @Test
    fun wh015wh017wh018CurrentOwnerAndLeaseLossSuppressPrivateUiOutputButKeepOriginal() = runTest(timeout = 5.seconds) {
        val foreignGrants = frozen.grants().map { PermissionGrant(it.permission, frozen.otherScope, it.effect,
            it.limits, it.ownerAccess, it.namedSubjectId, it.targetIds, it.delegationCeiling) }
        for (view in listOf(frozen.view(actor = frozen.actorB), frozen.view(device = frozen.otherDevice),
            frozen.view(scope = frozen.otherScope, grants = foreignGrants))) withWarehouseRpcFixture { f ->
            f.master.view = view
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalReadResult.Unavailable>(f.gateway.read(frozen.query())).problem)
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.journal.entries.isEmpty())
        }
        withWarehouseRpcFixture { f ->
            f.master.view = frozen.view(grants = frozen.grants(configure = false, recovery = false))
            assertEquals(LocalServiceProblem.DENIED,
                assertIs<LocalStageResult.NotStaged>(f.gateway.stage(frozen.create())).problem)
            assertTrue(f.master.mutations.isEmpty())
        }
        withWarehouseRpcFixture { f ->
            val held = WarehouseRpcGate().also { f.master.readGate = it }
            val reading = async { f.gateway.read(frozen.query()) }
            runCurrent()
            assertTrue(held.entered.isCompleted)
            assertFalse(reading.isCompleted)
            f.current = false
            held.release()
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalReadResult.Unavailable>(reading.await()).problem)
        }
        withWarehouseRpcFixture { f ->
            assertIs<LocalStageResult.Staged>(f.gateway.stage(frozen.create()))
            val held = WarehouseRpcGate().also { f.master.executeGate = it }
            val submitted = async { f.gateway.submit(frozen.createOperation) }
            runCurrent()
            assertTrue(held.entered.isCompleted)
            f.current = false
            held.release()
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalOperationResult.Unresolved>(submitted.await()).problem)
            frozen.assertOriginal(frozen.create(), f.journal.entries.getValue(frozen.createOperation))
            assertNotNull(f.journal.entries.getValue(frozen.createOperation).recorded)
            assertFalse(f.journal.entries.getValue(frozen.createOperation).acknowledged)
            assertEquals(1, f.master.mutations.size)
        }
        withWarehouseRpcFixture { f ->
            f.journal.readable = false
            assertIs<LocalPendingResult.Unavailable>(f.gateway.pending())
            assertTrue(f.master.lookups.isEmpty())
        }
    }

    private fun LocalActionForm.initial(field: LocalField): LocalScalarValue? =
        assertIs<LocalFormField.Scalar>(fields.single { it.field == field }).initialValue

    private fun replaceLastPageRow(page: JsonObject, change: (JsonObject) -> JsonObject): StoredBytes {
        val rows = page.getValue("items").jsonArray
        return frozen.bytes(JsonObject(page + ("items" to JsonArray(rows.dropLast(1) + change(rows.last().jsonObject)))))
    }
}
