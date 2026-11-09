package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Actual shared gateway with controlled ports. These H slices do not establish I/E/P outcomes. */
class RetailCustomerReceiptFrozenV1GatewayWorkflowTest {
    private val f = RetailCustomerReceiptFrozenV1GatewayFixture

    @Test
    fun rcr035S0AndS1KeepOriginalPortionsBoundedAndNoObservationDistinct() = runTest {
        val h = ControlledRetailReceiptGateway()
        h.endpoint.head = f.head(0)
        val absent = available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, search = f.headSearch)))
        val summary = absent.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT }
        val status = assertNotNull(summary.status)
        assertTrue(status.english.contains("no observation", ignoreCase = true))
        assertEquals(listOf(f.eachPortion, f.kgPortion), absent.records.filter {
            it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_PORTION }.map { it.reference.id })
        assertTrue(absent.records.count { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_PORTION } <= 16)
        assertTrue(absent.forms.isEmpty())
        assertTrue(h.journal.entries.isEmpty())
        h.endpoint.head = f.head(1)
        val present = available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.CHECKOUT, reference = f.headReference)))
        assertNull(present.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT }.status)
        assertEquals(f.e1.value, present.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_EVENT }.reference.id.value)
    }

    @Test
    fun rcr035And036KgFormPinsOriginalContractButNoObservedTimeOrZone() = runTest {
        val h = ControlledRetailReceiptGateway()
        val page = available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.kgReference)))
        val form = page.forms.single { it.action == LocalAction.RETAIL_CUSTOMER_RECEIPT }
        assertEquals(1, form.version)
        fun field(value: LocalField) = form.fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == value }
        for ((key, value) in listOf(
            LocalField.SALE_ID to LocalScalarValue.Identifier(f.sale),
            LocalField.PORTION_ID to LocalScalarValue.Identifier(f.kgPortion),
            LocalField.UNIT to LocalScalarValue.Identifier(f.kgUnit),
            LocalField.QUANTITY_SCALE to LocalScalarValue.Integer(6),
            LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION to LocalScalarValue.Integer(1),
        )) {
            assertEquals(value, field(key).initialValue)
            assertTrue(field(key).readOnly)
        }
        assertNull(field(LocalField.QUANTITY).initialValue)
        assertNull(field(LocalField.CUSTOMER_RECEIVED_AT).initialValue)
        assertNull(field(LocalField.CUSTOMER_RECEIPT_TIME_ZONE).initialValue)
        assertEquals(listOf("Africa/Cairo"), field(LocalField.CUSTOMER_RECEIPT_TIME_ZONE).choices.map { it.code })
        assertTrue(h.journal.entries.isEmpty())
        assertTrue(h.endpoint.executions.isEmpty())
    }

    @Test
    fun rcr035MissingCommandOrRemainingQuantityNeverOffersEditor() = runTest {
        val noCommand = ControlledRetailReceiptGateway(commandInstalled = false)
        assertTrue(available(noCommand.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.kgReference))).forms.isEmpty())
        val full = ControlledRetailReceiptGateway()
        full.endpoint.head = f.head(3)
        val fullReference = f.kgReference.copy(revision = Revision(3))
        assertTrue(available(full.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = fullReference))).forms.isEmpty())
        assertTrue(noCommand.journal.entries.isEmpty())
        assertTrue(full.journal.entries.isEmpty())
    }

    @Test
    fun rcr036ExplicitStageSavesExactSingleKgBodyWithoutDispatch() = runTest {
        val h = ControlledRetailReceiptGateway()
        val staged = assertIs<LocalStageResult.Staged>(h.gateway.stage(f.intent()))
        assertEquals(f.uiOperation, staged.operation.operationId)
        assertEquals(LocalPendingPhase.STAGED, staged.operation.phase)
        val entry = h.journal.entries.getValue(f.uiOperation)
        assertEquals(f.collector, entry.owner.actorId)
        assertEquals(f.uiOperation, entry.operationId)
        assertEquals(LocalRetailCustomerReceiptCodec.encode(f.uiRequest()), entry.request().payload)
        assertEquals(f.uiRequest(), LocalRetailCustomerReceiptCodec.decode(entry.request().payload))
        assertEquals(f.head(1), h.endpoint.head)
        assertTrue(h.endpoint.executions.isEmpty())
    }

    @Test
    fun rcr036MissingExplicitTimeOrCairoCannotCreateStagedBody() = runTest {
        for (missing in listOf(LocalField.CUSTOMER_RECEIVED_AT, LocalField.CUSTOMER_RECEIPT_TIME_ZONE)) {
            val h = ControlledRetailReceiptGateway()
            assertIs<LocalStageResult.NotStaged>(h.gateway.stage(f.intent(f.uiValues().filter { it.field != missing })))
            assertTrue(h.journal.entries.isEmpty())
            assertTrue(h.endpoint.executions.isEmpty())
        }
    }

    @Test
    fun rcr036ConcurrentHeadBeforeStageCannotReplacePinnedCasOrTime() = runTest {
        val h = ControlledRetailReceiptGateway()
        val intent = f.intent()
        val originalValues = intent.values
        h.endpoint.head = f.head(2)
        assertIs<LocalStageResult.NotStaged>(h.gateway.stage(intent))
        assertEquals(originalValues, intent.values)
        assertEquals(LocalScalarValue.Integer(1), intent.values.single {
            it.field == LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION }.value)
        assertTrue(h.journal.entries.isEmpty())
        assertTrue(h.endpoint.executions.isEmpty())
    }

    @Test
    fun rcr036OriginalStagedSubmissionDoesNotRebaseOnNewHead() = runTest {
        val h = ControlledRetailReceiptGateway()
        assertIs<LocalStageResult.Staged>(h.gateway.stage(f.intent()))
        val saved = h.journal.entries.getValue(f.uiOperation).request().payload
        h.endpoint.head = f.head(2)
        h.endpoint.executionReply = f.reply(f.resultBytes(LocalRetailCustomerReceiptResultV1.Refused(
            LocalRetailCustomerReceiptRefusalV1.REVISION_CONFLICT)), committed = false, code = f.refusedCode)
        val result = assertIs<LocalOperationResult.Recorded>(h.gateway.submit(f.uiOperation))
        assertEquals(LocalRecordedDecision.REJECTED, result.decision)
        assertEquals(f.uiOperation, result.operationId)
        assertEquals(saved, h.endpoint.executions.single().payload)
        assertEquals(f.uiRequest(), LocalRetailCustomerReceiptCodec.decode(h.endpoint.executions.single().payload))
        assertEquals(1, h.journal.stagedCalls)
    }

    @Test
    fun rcr036AdapterReconstructionRecoversSameBodyActorAndObservedTime() = runTest {
        val h = ControlledRetailReceiptGateway()
        assertIs<LocalStageResult.Staged>(h.gateway.stage(f.intent()))
        val saved = h.journal.entries.getValue(f.uiOperation)
        h.endpoint.head = f.head(2)
        val pending = assertIs<LocalOperationResult.Pending>(h.reopenedGateway().lookup(f.uiOperation))
        assertEquals(f.uiOperation, pending.operation.operationId)
        assertEquals(saved.request().payload, h.journal.entries.getValue(f.uiOperation).request().payload)
        assertEquals(f.collector, h.endpoint.lookups.single().originalActorId)
        assertEquals(f.uiOperation, h.endpoint.lookups.single().operationId)
        assertEquals(f.uiRequest(), LocalRetailCustomerReceiptCodec.decode(saved.request().payload))
        assertEquals(1, h.journal.stagedCalls)
        assertTrue(h.endpoint.executions.isEmpty())
    }

    @Test
    fun rcr036InvalidFreshReadsCannotFabricateStageOrCasZero() = runTest {
        val unavailable = ControlledRetailReceiptGateway().also { it.endpoint.head = LocalRetailCustomerReceiptReadResultV1.NotAvailable }
        val unavailableTransport = ControlledRetailReceiptGateway().also { it.endpoint.readFailure = ReadFailure.WRITER_NOT_QUALIFIED }
        val denied = ControlledRetailReceiptGateway().also { it.endpoint.readFailure = ReadFailure.AUTHORIZATION_DENIED }
        val malformed = ControlledRetailReceiptGateway().also { it.endpoint.readPayload = StoredBytes("not-a-receipt-source".encodeToByteArray()) }
        val capacity = ControlledRetailReceiptGateway().also { it.endpoint.head = LocalRetailCustomerReceiptReadResultV1.CapacityRequired }
        val wrongUnit = ControlledRetailReceiptGateway().also {
            val original = f.original.copy(portions = listOf(f.each(3), f.kg(2_750_000, f.gramUnit)))
            it.endpoint.head = LocalRetailCustomerReceiptReadResultV1.Head(LocalRetailCustomerReceiptHeadViewV1(original, Revision(1), 1,
                listOf(f.each(1), f.kg(1_125_000, f.gramUnit)), f.e1))
        }
        for (h in listOf(unavailable, unavailableTransport, denied, malformed, capacity, wrongUnit)) {
            assertFalse(h.gateway.stage(f.intent()) is LocalStageResult.Staged)
            assertTrue(h.journal.entries.isEmpty())
            assertTrue(h.endpoint.executions.isEmpty())
        }
    }

    @Test
    fun rcr036RevokedRecordGrantBeforeStageCreatesNoIntent() = runTest {
        val h = ControlledRetailReceiptGateway()
        available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.kgReference)))
        h.endpoint.grants = listOf(PermissionGrant(Permission(SecurityAction.CUSTOMER_RECEIPT_RECORD, PermissionVerb.PERFORM),
            f.scope, GrantEffect.DENY))
        val result = assertIs<LocalStageResult.NotStaged>(h.gateway.stage(f.intent()))
        assertEquals(LocalServiceProblem.DENIED, result.problem)
        assertTrue(h.journal.entries.isEmpty())
    }

    @Test
    fun rcr037OrdersCheckoutRecordAndReturnsLookupNeverShareEditorPurpose() = runTest {
        val h = ControlledRetailReceiptGateway()
        for (workflow in listOf(LocalWorkflow.ORDERS, LocalWorkflow.CHECKOUT)) {
            assertTrue(available(h.gateway.read(LocalWorkflowQuery(workflow, reference = f.kgReference))).forms.isNotEmpty())
            assertEquals(LocalRetailCustomerReceiptReadAccessV1.RECORD, (h.endpoint.queries.last() as LocalRetailCustomerReceiptQueryV1.Head).access)
        }
        val returns = available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.RETURNS, reference = f.kgReference)))
        assertTrue(returns.forms.isEmpty())
        assertTrue(returns.records.filter { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_PORTION }.none { it.selectable })
        assertEquals(LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP, (h.endpoint.queries.last() as LocalRetailCustomerReceiptQueryV1.Head).access)
    }

    @Test
    fun rcr037LookupOnlyRecordDenialNeverFallsBackAutomatically() = runTest {
        val h = ControlledRetailReceiptGateway(actor = f.lookupC, record = false)
        val record = assertIs<LocalReadResult.Unavailable>(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.headReference)))
        assertEquals(LocalServiceProblem.DENIED, record.problem)
        assertTrue(h.endpoint.queries.isEmpty())
        assertTrue(available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.RETURNS, reference = f.headReference))).forms.isEmpty())
        assertEquals(LocalRetailCustomerReceiptReadAccessV1.RETURN_LOOKUP, (h.endpoint.queries.single() as LocalRetailCustomerReceiptQueryV1.Head).access)
        assertFalse(h.gateway.stage(f.intent()) is LocalStageResult.Staged)
        assertTrue(h.journal.entries.isEmpty())
    }

    @Test
    fun rcr037DeniedMasterRecordReadNeverRetriesAsLookup() = runTest {
        val h = ControlledRetailReceiptGateway()
        h.endpoint.readFailure = ReadFailure.AUTHORIZATION_DENIED
        val result = assertIs<LocalReadResult.Unavailable>(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.headReference)))
        assertEquals(LocalServiceProblem.DENIED, result.problem)
        assertEquals(1, h.endpoint.queries.size)
        assertEquals(LocalRetailCustomerReceiptReadAccessV1.RECORD, (h.endpoint.queries.single() as LocalRetailCustomerReceiptQueryV1.Head).access)
    }

    @Test
    fun rcr037LateReadAfterSessionLifetimeEndsReleasesNoPrivatePage() = runTest {
        val h = ControlledRetailReceiptGateway()
        h.endpoint.onRead = { h.lifetimeCurrent = false }
        val result = assertIs<LocalReadResult.Unavailable>(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.headReference)))
        assertEquals(LocalServiceProblem.SESSION_ENDED, result.problem)
        assertTrue(h.journal.entries.isEmpty())
    }

    @Test
    fun rcr038ExactHeadAndHistoricalEventSearchNeverSubstituteLatest() = runTest {
        val h = ControlledRetailReceiptGateway()
        h.endpoint.head = f.head(2)
        val head = available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, search = f.headSearch)))
        assertEquals(f.e2.value, head.records.single { it.reference.kind == LocalRecordKind.CUSTOMER_RECEIPT_EVENT }.reference.id.value)
        for ((operation, received, kgDecimal) in listOf(Triple(f.e1, f.e1ReceivedAt, "1.125000"),
            Triple(f.e2, f.uiReceivedAt, "0.625000"))) {
            val page = available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, search = f.eventSearch(operation))))
            assertEquals(operation.value, page.records.first().reference.id.value)
            assertEquals(f.sale, page.records.first().reference.parentId)
            assertTrue(page.records.first().values.any { it.value.english == received.canonicalString() })
            assertTrue(page.records.first().values.any { it.value.english == f.zone })
            assertTrue(page.records.single { it.reference.id == f.kgPortion }.values.any { it.value.english.contains(kgDecimal) })
            assertTrue(page.forms.isEmpty())
            assertEquals(operation, (h.endpoint.queries.last() as LocalRetailCustomerReceiptQueryV1.Event).operationId)
        }
    }

    @Test
    fun rcr038MalformedAndWildcardSearchNeverExpandRoster() = runTest {
        val h = ControlledRetailReceiptGateway()
        for (search in listOf("customer-receipt:not-a-uuid", "customer-receipt-event:${f.sale.value}:not-a-uuid",
            "customer-receipt-event:${f.sale.value}:*")) {
            val result = assertIs<LocalReadResult.Unavailable>(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, search = search)))
            assertEquals(LocalServiceProblem.INVALID_INPUT, result.problem)
        }
        assertTrue(h.endpoint.queries.isEmpty())
        assertTrue(h.endpoint.executions.isEmpty())
    }

    @Test
    fun rcr033And038MissingEventAndUnavailableSourceCannotBecomeZeroHead() = runTest {
        val h = ControlledRetailReceiptGateway()
        h.endpoint.head = f.head(1)
        val missing = available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, search = f.eventSearch(f.unknownEvent))))
        assertTrue(missing.records.isEmpty())
        assertTrue(missing.forms.isEmpty())
        assertNotNull(missing.notice)
        assertEquals(f.unknownEvent, (h.endpoint.queries.single() as LocalRetailCustomerReceiptQueryV1.Event).operationId)
        h.endpoint.head = LocalRetailCustomerReceiptReadResultV1.NotAvailable
        val unavailable = available(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.headReference)))
        assertTrue(unavailable.records.isEmpty())
        assertTrue(unavailable.forms.isEmpty())
        assertNotNull(unavailable.notice)
        assertFalse(unavailable.notice == missing.notice)
        assertFalse(h.gateway.stage(f.intent()) is LocalStageResult.Staged)
        assertTrue(h.journal.entries.isEmpty())
    }

    @Test
    fun rcr034OriginalCollectorAnchorControlsEditorHint() = runTest {
        val ownOnly = ControlledRetailReceiptGateway(actor = f.recorderB, ownerAccess = OwnerAccess.OWN_ONLY)
        assertTrue(available(ownOnly.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.kgReference))).forms.isEmpty())
        val anyOwner = ControlledRetailReceiptGateway(actor = f.recorderB, ownerAccess = OwnerAccess.ANY_OWNER)
        assertTrue(available(anyOwner.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.kgReference))).forms.isNotEmpty())
        assertEquals(f.collector, (anyOwner.endpoint.head as LocalRetailCustomerReceiptReadResultV1.Head).value.original.originalCollectorId)
    }

    @Test
    fun rcr033WrongSaleProjectionIsUnavailableNotAnOriginalForCasZero() = runTest {
        val h = ControlledRetailReceiptGateway()
        val bytes = f.readBytes(f.head(1))
        val root = f.wire.parseToJsonElement(bytes.copyBytes().decodeToString()).jsonObject
        val value = root.getValue("value").jsonObject
        val original = value.getValue("original").jsonObject
        h.endpoint.readPayload = StoredBytes(JsonObject(root + ("value" to JsonObject(value + ("original" to
            JsonObject(original + ("saleId" to JsonPrimitive(f.unknownSale.value))))))).toString().encodeToByteArray())
        val result = assertIs<LocalReadResult.Unavailable>(h.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, reference = f.headReference)))
        assertEquals(LocalServiceProblem.UNAVAILABLE, result.problem)
        assertFalse(h.gateway.stage(f.intent()) is LocalStageResult.Staged)
        assertTrue(h.journal.entries.isEmpty())
    }

    private fun available(result: LocalReadResult): LocalWorkflowPage = assertIs<LocalReadResult.Available>(result).page
}
