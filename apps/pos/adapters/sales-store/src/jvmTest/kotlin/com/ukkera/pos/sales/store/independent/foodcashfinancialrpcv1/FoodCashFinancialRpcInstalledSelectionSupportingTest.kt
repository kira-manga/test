package com.ukkera.pos.sales.store.independent.foodcashfinancialrpcv1

import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import kotlinx.serialization.json.*
import kotlin.test.*

/** Independently frozen FFRC v1.0.0 classifier/codec slices only. Installed adapters
 * run, but never prepare/evaluate writes or acquire authority/native custody. These
 * assertions are not admitted RPC, a durable no-effects vector, or whole-case passes. */
class FoodCashFinancialRpcInstalledSelectionSupportingTest {
    private val f get() = FoodCashFinancialRpcSupportingInputs
    private val profile = LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL

    @Test
    fun ffrc002NormalizedStockAdmitsOnlyCanonicalBeginAndFinalize() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        assertEquals(CommandKey("sales.food.stock", 2), fixture.stockHandler.key)
        for (request in listOf(f.begin, f.finalize)) {
            val body = LocalFoodStockCodecV2.encode(request)
            val before = body.copyBytes()
            assertEquals(request, LocalFoodStockCodecV2.decode(body))
            assertEquals(body, fixture.stockHandler.normalize(body))
            assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(fixture.stockHandler.key))
            assertTrue(fixture.stockHandler.supportsRpcWorkflow(profile, body))
            for (other in LocalMasterRpcWorkflowProfile.entries.filterNot { it == profile }) {
                assertFalse(fixture.stockHandler.supportsRpcWorkflow(other, body), other.name)
            }
            assertContentEquals(before, body.copyBytes())
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun ffrc002SharedStockKeyRefusesDraftAddSendAndOtherFinancialBodies() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        for (request in f.excludedStockBodies()) {
            val body = LocalFoodStockCodecV2.encode(request) // Genuine canonical construction precedes refusal.
            assertEquals(request, LocalFoodStockCodecV2.decode(body))
            assertEquals(body, fixture.stockHandler.normalize(body))
            assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(fixture.stockHandler.key))
            assertFalse(fixture.stockHandler.supportsRpcWorkflow(profile, body), request.toString())
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun ffrc002SharedPaymentKeyAdmitsApplyNotPhysicalCaptureOrResolution() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val admitted = LocalFoodPaymentCodec.command(f.application, f.apply)
        assertEquals(CommandKey("sales.food.payment", 1), fixture.paymentHandler.key)
        assertEquals(admitted.payload, fixture.paymentHandler.normalize(admitted.payload))
        assertTrue(fixture.paymentHandler.supportsRpcWorkflow(profile, admitted.payload))
        assertNull(fixture.paymentHandler.physicalRecordingAction(admitted.payload))
        for (request in f.excludedPaymentBodies()) {
            val candidate = LocalFoodPaymentCodec.command(f.application, request)
            assertEquals(admitted.operationId, candidate.operationId, "Shared ID cannot change body membership")
            assertEquals(request, LocalFoodPaymentCodec.decode(candidate.payload))
            assertEquals(candidate.payload, fixture.paymentHandler.normalize(candidate.payload))
            assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(candidate.command))
            assertFalse(fixture.paymentHandler.supportsRpcWorkflow(profile, candidate.payload), request.toString())
        }
        for (other in LocalMasterRpcWorkflowProfile.entries.filterNot { it == profile }) {
            assertFalse(fixture.paymentHandler.supportsRpcWorkflow(other, admitted.payload), other.name)
        }
        assertEquals(0, fixture.source.calls)
        // The kernel's same-ID retained-result association is separate, not exercised here.
    }

    @Test
    fun ffrc002LegacyFinancialHandoffRefundAndSupervisorKeysHaveNoFallback() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val legacyFinancial = LocalFoodFinancialHandler(fixture.stock, fixture.source.value)
        val legacyFood = LocalFoodHandler(fixture.stock, fixture.source.value)
        for (request in listOf(f.begin.request, f.finalize.request)) {
            val body = LocalFoodFinancialCodecV1.encode(request)
            assertEquals(body, legacyFinancial.normalize(body))
            assertFalse(LocalMasterFoodCashFinancialRpcKeysV1.accepts(legacyFinancial.key))
            assertFalse(legacyFinancial.supportsRpcWorkflow(profile, body))
        }
        val handoff = LocalFoodCodecV1.encode(LocalFoodRequestV1.OfferHandoff(f.order, f.orderRevision,
            f.id("handoff"), f.otherActor))
        assertEquals(handoff, legacyFood.normalize(handoff))
        assertFalse(LocalMasterFoodCashFinancialRpcKeysV1.accepts(legacyFood.key))
        assertFalse(legacyFood.supportsRpcWorkflow(profile, handoff))
        for (key in listOf(LocalFoodRefundProfile.COMMAND, LocalFoodPaymentSupervisorCodec.COMMAND,
            CommandKey("sales.food.stock", 1), CommandKey("sales.food.payment", 2))) {
            assertFalse(LocalMasterFoodCashFinancialRpcKeysV1.accepts(key), key.toString())
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun ffrc014ffrc016OwnFoodReadRejectsStationTaskAndHandoffUnderItsSharedKey() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        for (query in listOf(LocalFoodQueryV1.Own(), LocalFoodQueryV1.Order(f.order),
            LocalFoodQueryV1.Order(f.order, f.portions[0], 1))) {
            val body = LocalFoodReadCodecV1.encode(query)
            assertEquals(body, fixture.foodRead.normalize(body))
            assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(fixture.foodRead.key))
            assertTrue(fixture.foodRead.supportsRpcWorkflow(profile, body))
        }
        for (query in listOf(LocalFoodQueryV1.Station(f.id("station")),
            LocalFoodQueryV1.Task(f.id("station"), f.id("task")), LocalFoodQueryV1.Handoff(f.order))) {
            val body = LocalFoodReadCodecV1.encode(query)
            assertEquals(body, fixture.foodRead.normalize(body))
            assertFalse(fixture.foodRead.supportsRpcWorkflow(profile, body), query.toString())
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun ffrc014ffrc015ffrc016FinancialReadAdmitsCollectionAndReceiptNotClaimsOrRefunds() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        for (query in listOf(LocalFoodFinancialQueryV1.Collection(f.collection), LocalFoodFinancialQueryV1.Receipt(f.sale))) {
            val body = LocalFoodFinancialReadCodecV1.encode(query)
            assertEquals(body, fixture.financialRead.normalize(body))
            assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(fixture.financialRead.key))
            assertTrue(fixture.financialRead.supportsRpcWorkflow(profile, body))
        }
        for (query in listOf(LocalFoodFinancialQueryV1.Refund(f.id("refund")),
            LocalFoodFinancialQueryV1.Claim(f.id("claim")), LocalFoodFinancialQueryV1.OwnClaims())) {
            val body = LocalFoodFinancialReadCodecV1.encode(query)
            assertEquals(body, fixture.financialRead.normalize(body))
            assertFalse(fixture.financialRead.supportsRpcWorkflow(profile, body), query.toString())
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun ffrc014ffrc016PaymentReadAdmitsOwnOriginalSelectorsNotResolutionSelectors() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val attempt = LocalFoodPaymentQuery.AttemptRecording(f.order, f.collection, f.session,
            f.target.attemptId, f.target.intentOperationId)
        for (query in listOf(attempt, LocalFoodPaymentQuery.Original(f.recording), LocalFoodPaymentQuery.OwnPending())) {
            val body = LocalFoodPaymentReadCodec.encode(query)
            assertEquals(body, fixture.paymentRead.normalize(body))
            assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(fixture.paymentRead.key))
            assertTrue(fixture.paymentRead.supportsRpcWorkflow(profile, body))
        }
        for (query in listOf(LocalFoodPaymentQuery.Resolution(f.target.resolutionOperationId),
            LocalFoodPaymentQuery.OwnResolutionPending())) {
            val body = LocalFoodPaymentReadCodec.encode(query)
            assertEquals(body, fixture.paymentRead.normalize(body))
            assertFalse(fixture.paymentRead.supportsRpcWorkflow(profile, body), query.toString())
        }
        assertEquals(0, fixture.source.calls)
    }

    @Test
    fun ffrc003ffrc016ffrc017AllKitchenVersionsAdmitOnlyBoundedExactOrder() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        assertEquals(listOf(1, 2, 3), fixture.kitchenReads.map { it.key.version })
        for (handler in fixture.kitchenReads) {
            for (limit in listOf(1, 8)) {
                val body = LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(f.order, limit = limit))
                assertEquals(body, handler.normalize(body))
                assertTrue(LocalMasterFoodCashFinancialRpcKeysV1.accepts(handler.key))
                assertTrue(handler.supportsRpcWorkflow(profile, body))
                assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.FOOD_CHECKS, body))
            }
            for (query in listOf(LocalFoodQueryV1.Own(), LocalFoodQueryV1.Station(f.id("station")),
                LocalFoodQueryV1.Task(f.id("station"), f.id("task")), LocalFoodQueryV1.Handoff(f.order))) {
                assertNotSelected { handler.supportsRpcWorkflow(profile, LocalFoodReadCodecV1.encode(query)) }
            }
            for (limit in listOf(0, 9)) {
                val invalid = localSalesEncode(LocalFoodQueryV1.Order(f.order, limit = limit), LocalFoodQueryV1.serializer())
                assertNotSelected { handler.supportsRpcWorkflow(profile, invalid) }
            }
        }
        assertFalse(LocalMasterFoodCashFinancialRpcKeysV1.accepts(ReadKey("sales.food.check-kitchen.read", 4)))
        assertEquals(0, fixture.source.calls)
        // Compatible selection only: no amount, page-total, lower-version request or transport is exercised.
    }

    @Test
    fun ffrc002ffrc006CallerPaidFlagsAndClaimedZeroCannotWidenNormalizedFinancialBodies() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        for (request in listOf(f.begin, f.finalize)) {
            val body = LocalFoodStockCodecV2.encode(request)
            val root = Json.parseToJsonElement(body.copyBytes().decodeToString()).jsonObject
            val original = root.getValue("request").jsonObject
            for (field in listOf("paid" to JsonPrimitive(true), "payable" to JsonPrimitive(0),
                "permission" to JsonPrimitive("COLLECTION_FINALIZE_ZERO"))) {
                val injected = JsonObject(root + ("request" to JsonObject(original + field)))
                assertNotSelected { fixture.stockHandler.supportsRpcWorkflow(profile, f.bytes(injected.toString())) }
            }
            assertEquals(body, fixture.stockHandler.normalize(body))
        }
        assertEquals(0, fixture.source.calls)
        // No zero/positive native collection source or four-grant permission matrix is qualified.
    }

    @Test
    fun ffrc016MalformedOversizedAndProfileInjectedReadsNeverSelectAnotherBody() {
        val fixture = FoodCashFinancialRpcSupportingFixture()
        val reads = listOf(fixture.foodRead to LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(f.order)),
            fixture.financialRead to LocalFoodFinancialReadCodecV1.encode(LocalFoodFinancialQueryV1.Collection(f.collection)),
            fixture.paymentRead to LocalFoodPaymentReadCodec.encode(LocalFoodPaymentQuery.Original(f.recording))) +
            fixture.kitchenReads.map { it to LocalFoodReadCodecV1.encode(LocalFoodQueryV1.Order(f.order)) }
        for ((handler, body) in reads) {
            val root = Json.parseToJsonElement(body.copyBytes().decodeToString()).jsonObject
            val candidates = listOf(StoredBytes(body.copyBytes().copyOf(body.size - 1)),
                f.bytes(JsonObject(root + ("profile" to JsonPrimitive("FOOD_CASH_FINANCIAL"))).toString()),
                f.bytes(JsonObject(root + ("unexpected" to JsonPrimitive("x".repeat(2_049)))).toString()))
            for (candidate in candidates) assertNotSelected { handler.supportsRpcWorkflow(profile, candidate) }
            assertEquals(body, handler.normalize(body))
        }
        for (key in listOf(ReadKey("local.food.menu", 1), ReadKey("local.food.station-directory", 1),
            ReadKey("sales.food.selection-preview", 1), ReadKey("sales.food.payment.read", 2),
            ReadKey("sales.food.financial.read", 2))) {
            assertFalse(LocalMasterFoodCashFinancialRpcKeysV1.accepts(key), key.toString())
        }
        assertEquals(0, fixture.source.calls)
    }
}

private fun assertNotSelected(selection: () -> Boolean) {
    val selected = try { selection() } catch (_: IllegalArgumentException) { false }
    assertFalse(selected)
}
