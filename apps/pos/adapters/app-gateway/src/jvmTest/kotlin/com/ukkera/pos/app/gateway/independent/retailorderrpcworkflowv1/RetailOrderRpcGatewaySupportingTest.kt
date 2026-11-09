@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.retailorderrpcworkflowv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcControlledJournal
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcControlledMaster
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcGate
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.WarehouseRpcWriteCut
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.SalesRule
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
 * retail-order-rpc-workflow-v1 RO-S-01/03/07/08 bounded gateway SUPPORT.
 * The real gateway uses existing controlled endpoint/current-view/journal ports.
 * Their replies and DURABLE answers do not authenticate the probe or establish
 * native ownership, installed sources, actual RPC, quote/stock/money effects,
 * durable storage/restart, audit/outbox or official SDK Receipt ordering.
 *
 * The revision-clarification-v2 overlay leaves all positive terminal revisions
 * unresolved. Literal v1 revisions below are unchanged request/read/refusal-probe
 * inputs only; no successful create/replace result or numeric advance is asserted.
 * Durable-remembrance/ACK support uses a typed rejection, never an applied quote.
 */
class RetailOrderRpcGatewaySupportingTest {
    private val input get() = RetailOrderRpcGatewayInputs

    @Test
    fun roS07StageRetainsCanonicalCreateBeforeAnyDispatchAndUnknownDoesNotRewriteIt() = runTest(timeout = 10.seconds) {
        withRetailOrderRpcFixture(input.normalizedCreate) { fixture ->
            val gate = WarehouseRpcGate()
            fixture.journal.stageGate = gate
            val saving = async { fixture.gateway.stage(input.intent(fixture.journey)) }
            try {
                withTimeout(5_000) { gate.entered.await() }
                assertTrue(fixture.journal.entries.isEmpty())
                assertTrue(fixture.master.mutations.isEmpty())
                assertTrue(fixture.master.lookups.isEmpty())
                assertEquals(0, fixture.journal.dispatchCalls)
            } finally { gate.release() }
            assertIs<LocalStageResult.Staged>(saving.await())
            val original = fixture.journal.entries.getValue(fixture.journey.operation)
            input.assertOriginal(original, fixture.journey)
            assertEquals(LocalPendingPhase.STAGED, original.localPhase)
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())

            // A changed draft under O-CREATE cannot replace the exact saved body B.
            assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(input.intent(fixture.journey, changedCreate = true)))
            assertEquals(original, fixture.journal.entries.getValue(fixture.journey.operation))
            fixture.master.reads.clear()
            fixture.master.readReply = { error("Saved originals cannot request a fresh profile or quote") }
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(fixture.journey.operation))
            val retained = fixture.journal.entries.getValue(fixture.journey.operation)
            assertTrue(original.sameIntent(retained))
            assertEquals(original.identity(), retained.identity())
            assertExactRequest(original.request(), fixture.master.mutations.single())
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, retained.localPhase)
            val savedIndex = fixture.events.indexOf("intent.retained")
            val dispatchIndex = fixture.events.indexOf("dispatch.retained")
            val executeIndex = fixture.events.indexOf("execute.enter")
            assertTrue(savedIndex >= 0 && dispatchIndex > savedIndex && executeIndex > dispatchIndex)
            assertIs<LocalOperationResult.Pending>(fixture.recreatedGateway().lookup(fixture.journey.operation))
            assertEquals(1, fixture.master.mutations.size)
            assertTrue(fixture.master.reads.isEmpty())
            assertNull(retained.recorded)
            assertEquals(0, fixture.journal.ackCalls)
            // Adapter recreation over the same controlled map is not native process restart or UI Back.
        }
    }

    @Test
    fun roS03AdvertisedProfileReadFailureCannotFallBackToTheInstalledLegacyReaderOrWriter() = runTest {
        for (journey in input.replacements) withRetailOrderRpcFixture(journey) { fixture ->
            fixture.master.readReply = { ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN) }
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(input.intent(journey)))
            assertEquals(listOf(LocalOrderReadCodecV2.QUERY), fixture.master.reads.map { it.query })
            assertNoFreshMutation(fixture)
        }
    }

    @Test
    fun roS03ExplicitExistingOrderProfilesSelectOnlyTheirWriterAndMissingOrUnknownTagsRefuse() = runTest {
        for (journey in input.replacements) withRetailOrderRpcFixture(journey) { fixture ->
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(input.intent(journey)))
            input.assertOriginal(fixture.journal.entries.getValue(journey.operation), journey)
            assertEquals(LocalOrderReadCodecV2.QUERY, fixture.master.reads.first().query)
            assertTrue(fixture.master.mutations.isEmpty())
            assertEquals(0, fixture.journal.dispatchCalls)
        }
        for (tag in listOf<String?>(null, "UNKNOWN")) withRetailOrderRpcFixture(input.normalizedReplace) { fixture ->
            fixture.master.readReply = { request ->
                if (request.query != LocalOrderReadCodecV2.QUERY) fixture.allowedRead(request)
                else {
                    val text = input.orderData(LocalOrderStockProfileV2.NORMALIZED).payload.copyBytes().decodeToString()
                    val modified = if (tag == null) text.replace(",\"stockProfile\":\"NORMALIZED\"", "")
                        else text.replace("\"stockProfile\":\"NORMALIZED\"", "\"stockProfile\":\"$tag\"")
                    ReadReply.Data(StoredBytes(modified.encodeToByteArray()), input.metadataRevision, input.at)
                }
            }
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(input.intent(fixture.journey)))
            assertEquals(listOf(LocalOrderReadCodecV2.QUERY), fixture.master.reads.map { it.query })
            assertNoFreshMutation(fixture)
        }
        // This is the existing-order discriminator slice, not F-PROFILE's fresh Create or missing-writer composition.
    }

    @Test
    fun roS03RoS07AllRetainedCarrierFamiliesContinueOnlyTheSavedTargetWithoutNewReads() = runTest {
        for (journey in input.journeys) withRetailOrderRpcFixture(journey) { fixture ->
            val original = input.staged(journey)
            fixture.journal.entries[journey.operation] = original
            fixture.master.readReply = { error("Retained originals cannot select a fresh writer") }
            assertIs<LocalOperationResult.Pending>(fixture.gateway.lookup(journey.operation))
            assertIs<LocalPendingResult.Available>(fixture.gateway.pending(null))
            assertEquals(original, fixture.journal.entries.getValue(journey.operation))
            assertTrue(fixture.master.mutations.isEmpty())
            assertTrue(fixture.master.reads.isEmpty())

            fixture.master.view = input.view(input.otherOrder)
            assertFalse(assertIs<LocalOperationResult.Pending>(fixture.gateway.lookup(journey.operation)).operation.submitAllowed)
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(journey.operation))
            assertTrue(fixture.master.mutations.isEmpty())
            assertEquals(0, fixture.journal.dispatchCalls)
            fixture.master.view = input.view(input.order)
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(journey.operation))
            assertExactRequest(original.request(), fixture.master.mutations.single())
            val retained = fixture.journal.entries.getValue(journey.operation)
            assertTrue(original.sameIntent(retained))
            assertEquals(original.identity(), retained.identity())
            assertEquals(original.inputDigest, retained.inputDigest)
            assertTrue(fixture.master.lookups.all { it == original.identity() })
            assertTrue(fixture.master.reads.isEmpty())
            assertNull(retained.recorded)
            assertEquals(0, fixture.journal.ackCalls)
            // Current-view target hints do not qualify the Master's current authoritative resource checks.
        }
    }

    @Test
    fun roS07RoS08CorruptSavedBodyOwnerKeyOrFormRefusesBeforeOriginalLookupOrRemembrance() = runTest {
        val journey = input.normalizedCreate
        val original = input.staged(journey)
        val changed = input.request(journey, changedCreate = true)
        val corrupt = listOf(
            original.copy(owner = original.owner.copy(actorId = input.otherActor)),
            original.copy(owner = original.owner.copy(deviceId = input.otherDevice)),
            original.copy(owner = original.owner.copy(scope = original.owner.scope.copy(branchId = input.otherBranch))),
            original.copy(commandName = "sales.cash.control", commandVersion = 1),
            original.copy(commandVersion = original.commandVersion + 1),
            original.copy(payloadHex = encodeHex(changed.payload.copyBytes())),
            original.copy(formVersion = 1),
            original.copy(action = LocalAction.ORDER_REPLACE.name))
        for (entry in corrupt) withRetailOrderRpcFixture(journey) { fixture ->
            fixture.journal.entries[journey.operation] = entry
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(journey.operation))
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(journey.operation))
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(journey.operation))
            assertEquals(entry, fixture.journal.entries.getValue(journey.operation))
            assertTrue(fixture.master.reads.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            assertTrue(fixture.master.mutations.isEmpty())
            assertEquals(0, fixture.journal.dispatchCalls)
            assertEquals(0, fixture.journal.rememberCalls)
            assertEquals(0, fixture.journal.ackCalls)
        }
        // Device/branch/key/body are stored-original checks; the Recorded DTO does not carry those fields.
    }

    @Test
    fun roS08WrongOriginalTypeResourceOrTransportOnlyTerminalCannotBeRememberedOrAcknowledged() = runTest {
        for (journey in input.journeys) for (invalid in input.invalidTerminals(journey))
            withRetailOrderRpcFixture(journey) { fixture ->
                val original = input.staged(journey)
                fixture.journal.entries[journey.operation] = original
                fixture.master.lookupReply = { LookupReply.Found(invalid) }
                assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(journey.operation))
                assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(journey.operation))
                assertEquals(original, fixture.journal.entries.getValue(journey.operation))
                assertNull(fixture.journal.entries.getValue(journey.operation).recorded)
                assertEquals(0, fixture.journal.rememberCalls)
                assertEquals(0, fixture.journal.ackCalls)
                assertTrue(fixture.master.mutations.isEmpty())
                assertTrue(fixture.master.reads.isEmpty())
            }
        // Applied carriers are corrupt-output refusal probes only. No valid positive revision baseline
        // or isolation of a particular rejection guard is claimed while the v2 binding is unresolved.
    }

    @Test
    fun roS08TypedRejectionIsRememberedBeforeMatchingClientApplicationAcknowledgement() = runTest(timeout = 10.seconds) {
        withRetailOrderRpcFixture(input.normalizedCreate) { fixture ->
            val original = input.staged(fixture.journey)
            fixture.journal.entries[original.operationId] = original
            val rejection = input.rejection(fixture.journey)
            fixture.master.terminalCarriers[original.operationId] = rejection
            val gate = WarehouseRpcGate()
            fixture.journal.rememberGate = gate
            val recovering = async { fixture.gateway.lookup(original.operationId) }
            try {
                withTimeout(5_000) { gate.entered.await() }
                assertNull(fixture.journal.entries.getValue(original.operationId).recorded)
                assertEquals(0, fixture.journal.ackCalls)
                assertFalse(recovering.isCompleted)
            } finally { gate.release() }
            val result = assertIs<LocalOperationResult.Recorded>(recovering.await())
            assertEquals(LocalRecordedDecision.REJECTED, result.decision)
            val remembered = fixture.journal.entries.getValue(original.operationId)
            assertTrue(original.sameIntent(remembered))
            assertEquals(StoredKernelResultV1.from(rejection), remembered.recorded)
            assertFalse(remembered.acknowledged)
            assertEquals(0, fixture.journal.ackCalls)
            assertIs<LocalAcknowledgementResult.Acknowledged>(fixture.gateway.acknowledgeRecorded(original.operationId))
            assertEquals(listOf(original.operationId), fixture.journal.cleared)
            val rememberedIndex = fixture.events.indexOf("result.retained")
            val ackIndex = fixture.events.indexOf("ack.before-retention")
            assertTrue(rememberedIndex >= 0 && ackIndex > rememberedIndex)
            assertTrue(fixture.master.mutations.isEmpty())
            // Controlled client-journal ordering only: no SDK Receipt, Host eviction or durable business result is supplied.
        }
    }

    @Test
    fun roS08FailedTypedRejectionRemembranceCannotBecomeARecordedResultOrApplicationAck() = runTest {
        withRetailOrderRpcFixture(input.normalizedCreate) { fixture ->
            val original = input.staged(fixture.journey)
            fixture.journal.entries[original.operationId] = original
            fixture.master.terminalCarriers[original.operationId] = input.rejection(fixture.journey)
            fixture.journal.rememberCut = WarehouseRpcWriteCut.FAILED
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(original.operationId))
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(original.operationId))
            assertEquals(original, fixture.journal.entries.getValue(original.operationId))
            assertEquals(1, fixture.journal.rememberCalls)
            assertEquals(0, fixture.journal.ackCalls)
            assertTrue(fixture.master.mutations.isEmpty())
            // An injected journal answer is not an actual filesystem failure or process-reopen observation.
        }
    }

    @Test
    fun roS01SharedKeyHandoffOtherConfigurationAndExcludedReadKeysRefuseBeforeEndpointRead() = runTest {
        withRetailOrderRpcFixture(input.normalizedCreate) { fixture ->
            val lease = LocalWorkflowCredentialLease(input.credentials()) { true }
            val access = GatewayAccess(fixture.master, lease, fixture.master.view, fixture.installed)
            try {
                val excluded = listOf(LocalOrderReadCodec.request(LocalOrderQueryV1.Handoff(input.order)),
                    LocalOrderReadCodecV2.request(LocalOrderQueryV1.Handoff(input.order)),
                    ReadRequest(LocalConfigurationProfile.QUERY,
                        LocalConfigurationCodec.query(LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE))),
                    ReadRequest(LocalOrderReadCodec.QUERY,
                        StoredBytes("{\"type\":\"UNKNOWN_RETAIL_SELECTOR\"}".encodeToByteArray()))) +
                    listOf("sales.cash.read", "sales.collection.read", "sales.order.manager-review",
                        "sales.customer-receipt.read", "stock.costs", "sales.branch-report.read").map {
                        ReadRequest(ReadKey(it, 1), StoredBytes(byteArrayOf()))
                    }
                for (request in excluded) assertFailsWith<GatewayFailure> { access.read(request) }
            } finally { lease.destroy() }
            assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(LocalWorkflowQuery(LocalWorkflow.REPORTS)))
            assertTrue(fixture.master.reads.isEmpty())
            assertTrue(fixture.master.lookups.isEmpty())
            assertTrue(fixture.master.mutations.isEmpty())
            assertEquals(0, fixture.journal.stageCalls)
            // These are raw gateway-port refusals, not admitted RPC or a complete authority/disclosure inventory.
        }
    }

    private fun assertExactRequest(expected: MutationRequest, actual: MutationRequest) {
        assertEquals(expected.operationId, actual.operationId)
        assertEquals(expected.command, actual.command)
        assertEquals(expected.payload, actual.payload)
        assertEquals(expected.actionApprovalId, actual.actionApprovalId)
    }

    private fun assertNoFreshMutation(fixture: RetailOrderRpcGatewayFixture) {
        assertTrue(fixture.journal.entries.isEmpty())
        assertEquals(0, fixture.journal.stageCalls)
        assertEquals(0, fixture.journal.dispatchCalls)
        assertTrue(fixture.master.mutations.isEmpty())
        assertTrue(fixture.master.lookups.isEmpty())
    }
}

private data class RetailOrderRpcJourney(val normalized: Boolean, val action: LocalAction, val operation: OperationId) {
    val formVersion get() = if (normalized) 2 else 1
}

/** Literal v1 semantic inputs; auxiliary slots/projections supply no installed lineage or successful revision oracle. */
private object RetailOrderRpcGatewayInputs {
    private fun id(suffix: String) = DomainId("10000000-0000-4000-8000-${suffix.padStart(12, '0')}")
    fun auxiliary(ordinal: Int) = DomainId("d0000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    val scope = BranchScope(id("1"), id("2"), id("3"))
    val actor = id("11")
    val device = id("22")
    val owner = LocalClientIdentity(scope, actor, device)
    val otherActor = id("12")
    val otherDevice = id("23")
    val otherBranch = id("4")
    val order = id("101")
    val otherOrder = auxiliary(99)
    val metadataRevision = Revision(1)
    val at = UtcInstant.parseCanonical("2026-11-04T00:00:00Z")
    private val wire = Json { encodeDefaults = true }
    val normalizedCreate = RetailOrderRpcJourney(true, LocalAction.ORDER_CREATE, OperationId(id("201").value))
    val normalizedReplace = RetailOrderRpcJourney(true, LocalAction.ORDER_REPLACE, OperationId(id("202").value))
    val replacements = listOf(normalizedReplace.copy(normalized = false), normalizedReplace)
    val journeys = listOf(normalizedCreate.copy(normalized = false), normalizedCreate) + replacements
    private val create = LocalOrderSelectionV1(LocalSalesActivity.SUPERMARKET, LocalSalesChannel.COUNTER, Currency.EGP,
        Revision(7), listOf(LocalOrderLineV1(auxiliary(3), auxiliary(5), id("31"), Quantity(auxiliary(1), 2, 0)),
            LocalOrderLineV1(auxiliary(4), auxiliary(6), id("32"), Quantity(auxiliary(2), 250, 3))))
    private val replace = create.copy(lines = listOf(create.lines[0].copy(quantity = Quantity(auxiliary(1), 1, 0)),
        create.lines[1].copy(quantity = Quantity(auxiliary(2), 500, 3))))
    private val changedCreate = create.copy(lines = listOf(create.lines[0].copy(quantity = Quantity(auxiliary(1), 1, 0)), create.lines[1]))

    private fun selection(journey: RetailOrderRpcJourney, changed: Boolean) =
        if (journey.action == LocalAction.ORDER_REPLACE) replace else if (changed) changedCreate else create

    fun intent(journey: RetailOrderRpcJourney, changedCreate: Boolean = false): LocalCommandIntent {
        val selection = selection(journey, changedCreate)
        val values = listOf(LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(order)),
            LocalScalarInput(LocalField.EXPECTED_CATALOG_REVISION, LocalScalarValue.Integer(7)),
            LocalScalarInput(LocalField.ACTIVITY, LocalScalarValue.Choice(selection.activity.name)),
            LocalScalarInput(LocalField.CHANNEL, LocalScalarValue.Choice(selection.channel.name))) +
            if (journey.action == LocalAction.ORDER_REPLACE)
                listOf(LocalScalarInput(LocalField.EXPECTED_REVISION, LocalScalarValue.Integer(1))) else emptyList()
        val lines = selection.lines.map { line -> LocalInputLine(line.lineId, listOf(
            LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Reference(LocalRecordReference(LocalRecordKind.SKU, line.itemId, metadataRevision))),
            LocalScalarInput(LocalField.PORTION_ID, LocalScalarValue.Identifier(line.portionId)),
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(if (line.itemId == id("31")) line.quantity.scaled.toString()
                else if (line.quantity.scaled == 500L) "0.500" else "0.250")))) }
        return LocalCommandIntent(journey.operation, journey.action, journey.formVersion, null, values, LocalField.LINES, lines)
    }

    fun request(journey: RetailOrderRpcJourney, changedCreate: Boolean = false): MutationRequest {
        val selection = selection(journey, changedCreate)
        return if (journey.normalized) LocalCollectionStockCodecV2.command(journey.operation,
            if (journey.action == LocalAction.ORDER_CREATE) LocalCollectionStockRequestV2.CreateOrder(order, selection)
            else LocalCollectionStockRequestV2.ReplaceUnlockedOrder(order, Revision(1), selection))
        else MutationRequest(journey.operation, LocalSalesStoreProfile.ORDER_COMMAND, LocalOrderCommandCodec.encode(
            if (journey.action == LocalAction.ORDER_CREATE) LocalOrderRequestV1.Create(order, selection)
            else LocalOrderRequestV1.ReplaceUnlocked(order, Revision(1), selection)))
    }

    fun staged(journey: RetailOrderRpcJourney) = StoredClientIntentV1.staged(owner, intent(journey), request(journey),
        LocalText("Retained original quote", "عرض السعر الأصلي المحفوظ"),
        listOf(LocalRecordValue(LocalText("Original order", "الطلب الأصلي"), LocalText(order.value, order.value))))

    fun assertOriginal(entry: StoredClientIntentV1, journey: RetailOrderRpcJourney) {
        entry.validate(owner)
        assertEquals(owner, entry.owner)
        assertEquals(journey.operation, entry.operationId)
        assertEquals(journey.action, entry.localAction)
        assertEquals(journey.formVersion, entry.formVersion)
        assertEquals(request(journey).command, entry.request().command)
        assertEquals(request(journey).payload, entry.request().payload)
        assertEquals(inputDigest(intent(journey)), entry.inputDigest)
        assertNull(entry.actionApproval)
        assertNull(entry.request().actionApprovalId)
    }

    /** Current-view grants are target-specific continuation hints, not authenticated Master authority. */
    fun view(target: DomainId = order): LocalSessionView {
        val grants = listOf(SecurityAction.ORDER_CREATE, SecurityAction.ORDER_EDIT, SecurityAction.ORDER_READ).map {
            PermissionGrant(Permission(it, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW, targetIds = setOf(target))
        } + PermissionGrant(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW)
        val policy = ActivatedLocalPolicy(scope, actor, metadataRevision, grants, LocalSessionPolicy(), ThrottlePolicy())
        return LocalSessionView(actor, scope, device, auxiliary(20), SessionGeneration(1), LocalLoginIdentifier("retail-order-support"),
            policy, MonotonicMoment(auxiliary(21), 1), MonotonicMoment(auxiliary(21), 2))
    }

    fun credentials(): LocalGatewayCredentials {
        val probe = ByteArray(52) { (it + 1).toByte() }
        return try { LocalGatewayCredentials(auxiliary(22), OpaqueCredential(probe)) } finally { probe.fill(0) }
    }

    private fun <T> data(value: T, serializer: kotlinx.serialization.KSerializer<T>) = ReadReply.Data(
        StoredBytes(wire.encodeToString(serializer, value).encodeToByteArray()), metadataRevision, at)

    /** Controlled read scaffolding for the exact retained REPLACE input, not a result of executing CREATE. */
    fun orderData(profile: LocalOrderStockProfileV2): ReadReply.Data {
        val existing = LocalOrderViewV1(order, actor, Revision(1), Revision(1), create, Money(Currency.EGP, 2565),
            Money(Currency.EGP, 0), Money(Currency.EGP, 2565),
            listOf(LocalOrderPriceLineV1(create.lines[0].portionId, create.lines[0].quantity, Money(Currency.EGP, 2280)),
                LocalOrderPriceLineV1(create.lines[1].portionId, create.lines[1].quantity, Money(Currency.EGP, 285))),
            emptyList(), null, null, null, null)
        return data(LocalOrderReadResultV2(listOf(LocalProfiledOrderV2(existing, profile)), null), LocalOrderReadResultV2.serializer())
    }

    fun skuData(sku: DomainId): ReadReply.Data {
        val index = if (sku == id("31")) 0 else if (sku == id("32")) 1 else error("Only frozen SKU inputs are provided")
        val names = LocalNamesV1(if (index == 0) "SKU-A" else "SKU-B", if (index == 0) "صنف أ" else "صنف ب")
        val detail = LocalConfigurationDetailV1.Sku(sku, auxiliary(30 + index), metadataRevision, metadataRevision, names,
            if (index == 0) "SKU-A" else "SKU-B", listOf(if (index == 0) "0000001234567" else "0000001234568"),
            auxiliary(1 + index), if (index == 0) 0 else 3, LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL,
            true, true, true, false, emptyList(), emptyList())
        return data(LocalConfigurationPageV1(LocalConfigurationProfile.SKU,
            listOf(LocalConfigurationListItemV1(sku, metadataRevision, metadataRevision, names, true, detail.code)),
            null, Revision(7), detail), LocalConfigurationPageV1.serializer())
    }

    private fun resultBytes(result: LocalSalesResultV1) = StoredBytes(wire.encodeToString(LocalSalesResultV1.serializer(), result).encodeToByteArray())
    private fun recorded(journey: RetailOrderRpcJourney, payload: StoredBytes, committed: Boolean = true,
        code: String = "committed", operation: OperationId = journey.operation, actor: DomainId = this.actor) =
        MutationReply.Recorded(operation, actor, committed, code, payload, metadataRevision, JournalSequence(1), at, true)

    fun rejection(journey: RetailOrderRpcJourney, operation: OperationId = journey.operation, actor: DomainId = this.actor) =
        recorded(journey, resultBytes(LocalSalesResultV1.Rejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION)), false,
            if (journey.normalized) "sales.stock.rule_rejected" else "sales.domain_rejected", operation, actor)

    fun invalidTerminals(journey: RetailOrderRpcJourney): List<MutationReply.Recorded> {
        val expectedAction = if (journey.action == LocalAction.ORDER_CREATE) LocalSalesAction.ORDER_CREATED else LocalSalesAction.ORDER_EDITED
        val otherAction = if (journey.action == LocalAction.ORDER_CREATE) LocalSalesAction.ORDER_EDITED else LocalSalesAction.ORDER_CREATED
        // Preserve literal v1 probe inputs. They are not a replacement approved positive revision oracle.
        val revision = Revision(if (journey.action == LocalAction.ORDER_CREATE) 1 else 2)
        fun applied(action: LocalSalesAction, resource: DomainId = order) = resultBytes(LocalSalesResultV1.Applied(action, resource,
            listOf(LocalSalesRevision(LocalSalesStoreProfile.ORDER_KIND, resource, revision)),
            Money(Currency.EGP, if (journey.action == LocalAction.ORDER_CREATE) 2565 else 1710)))
        val otherOperation = if (journey.operation == normalizedCreate.operation) normalizedReplace.operation else normalizedCreate.operation
        return listOf(rejection(journey, operation = otherOperation), rejection(journey, actor = otherActor),
            recorded(journey, applied(otherAction)), recorded(journey, applied(LocalSalesAction.SALE_FINALIZED)),
            recorded(journey, applied(expectedAction, otherOrder)), recorded(journey, StoredBytes("{}".encodeToByteArray())),
            recorded(journey, StoredBytes("true".encodeToByteArray())))
    }
}

/** Existing controlled ports are reused, without a new storage/authentication fixture implementation. */
private class RetailOrderRpcGatewayFixture(val journey: RetailOrderRpcJourney) {
    private val input = RetailOrderRpcGatewayInputs
    val events = mutableListOf<String>()
    val master = WarehouseRpcControlledMaster(events).also { master ->
        master.view = input.view()
        master.currentReply = { LocalSessionViewResult.Current(master.view) }
        master.readReply = ::allowedRead
        master.lookupReply = { identity -> master.terminalCarriers[identity.operationId]?.let { LookupReply.Found(it) }
            ?: LookupReply.AbsentAtCurrentWriter(identity.operationId) }
        master.executeReply = { request -> MutationReply.Deferred(request.operationId, DeferredReason.SERVICE_BUSY) }
    }
    val journal = WarehouseRpcControlledJournal(input.owner, events)
    private val leases = mutableListOf<LocalWorkflowCredentialLease>()
    private val credentials = LocalWorkflowCredentialSource { LocalWorkflowCredentialLease(input.credentials()) { true }.also(leases::add) }
    val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS)
    val gateway = recreatedGateway()
    fun recreatedGateway() = LocalEndpointWorkflowGateway(master, journal, credentials, installed)
    fun allowedRead(request: ReadRequest): ReadReply = when (request.query) {
        LocalOrderReadCodecV2.QUERY -> {
            assertEquals(LocalOrderQueryV1.Order(input.order), LocalOrderReadCodec.decodeQuery(request.payload))
            input.orderData(if (journey.normalized) LocalOrderStockProfileV2.NORMALIZED else LocalOrderStockProfileV2.LEGACY_BOUNDED)
        }
        LocalConfigurationProfile.QUERY -> {
            val query = LocalConfigurationCodec.decodeQuery(request.payload)
            assertEquals(LocalConfigurationProfile.SKU, query.kind)
            input.skuData(assertNotNull(query.id))
        }
        else -> error("No fallback or unrelated retail read is provided for ${request.query}")
    }
    suspend fun close() { leases.forEach { it.destroy() }; journal.close() }
}

private suspend fun <T> withRetailOrderRpcFixture(journey: RetailOrderRpcJourney,
    body: suspend (RetailOrderRpcGatewayFixture) -> T): T {
    val fixture = RetailOrderRpcGatewayFixture(journey)
    return try { body(fixture) } finally { fixture.close() }
}
