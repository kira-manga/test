package com.ukkera.pos.sales.store.independent.retailorderrpcworkflowv1

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlin.test.*

/**
 * retail-order-rpc-workflow-v1 RO-S-01 closed typed-selector/body SUPPORT only.
 * Real codecs/classifiers run with an unobserved stock repository and fail-on-use
 * quote ports. No prepare/evaluate, access grant, quote calculation, stock/money
 * effect, installed source, RPC or positive result-revision oracle is supplied.
 * Auxiliary line/portion/unit IDs fill existing required carrier slots only.
 */
class RetailOrderRpcSelectionSupportingTest {
    private val input get() = RetailOrderRpcSelectionInputs

    @Test
    fun roS01LegacyClassifierAdmitsCanonicalCreateReplaceAndExcludesEveryOtherDeclaredAction() {
        val handler = LocalOrderHandler(TransactionSalesQuoteSource { _, _ -> error("Classification cannot quote") })
        val admitted = listOf(LocalOrderRequestV1.Create(input.order, input.create),
            LocalOrderRequestV1.ReplaceUnlocked(input.order, Revision(1), input.replace))
        admitted.forEach { request ->
            val body = LocalOrderCommandCodec.encode(request)
            assertEquals(request, LocalOrderCommandCodec.decode(body))
            assertEquals(body, handler.normalize(body))
            assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, body))
            otherProfiles().forEach { assertFalse(handler.supportsRpcWorkflow(it, body), it.name) }
        }
        input.legacyExcluded.forEach { request ->
            val body = LocalOrderCommandCodec.encode(request)
            assertEquals(body, handler.normalize(body))
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, body), request.toString())
        }
    }

    @Test
    fun roS01NormalizedClassifierAdmitsCanonicalCreateReplaceAndExcludesEveryOtherDeclaredAction() {
        val handler = normalizedHandler()
        val admitted = listOf(LocalCollectionStockRequestV2.CreateOrder(input.order, input.create),
            LocalCollectionStockRequestV2.ReplaceUnlockedOrder(input.order, Revision(1), input.replace))
        admitted.forEach { request ->
            val body = LocalCollectionStockCodecV2.encode(request)
            assertEquals(request, LocalCollectionStockCodecV2.decode(body))
            assertEquals(body, handler.normalize(body))
            assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, body))
            otherProfiles().forEach { assertFalse(handler.supportsRpcWorkflow(it, body), it.name) }
        }
        input.normalizedExcluded.forEach { request ->
            val body = LocalCollectionStockCodecV2.encode(request)
            assertEquals(body, handler.normalize(body))
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, body), request.toString())
        }
    }

    @Test
    fun roS01BothOwnOrderReadVersionsExcludeTheSharedHandoffSelector() {
        val admitted = listOf(LocalOrderQueryV1.Order(input.order), LocalOrderQueryV1.OwnOrders(limit = 1),
            LocalOrderQueryV1.OwnOrders(after = input.order, limit = 1))
        for (handler in listOf(LocalOrderReadHandler(), LocalOrderReadHandler.withStockProfile())) {
            admitted.forEach { query ->
                val body = LocalOrderReadCodec.encode(query)
                assertEquals(query, LocalOrderReadCodec.decodeQuery(body))
                assertEquals(body, handler.normalize(body))
                assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, body))
                otherProfiles().forEach { assertFalse(handler.supportsRpcWorkflow(it, body), it.name) }
            }
            val excluded = LocalOrderReadCodec.encode(LocalOrderQueryV1.Handoff(input.order))
            assertEquals(excluded, handler.normalize(excluded))
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, excluded))
        }
        // Classifier eligibility does not establish owner-private projection or bounded pagination results.
    }

    @Test
    fun roS01UnknownTruncatedAndNoncanonicalBodiesCannotBecomeAdmittedOriginals() {
        val legacy = LocalOrderHandler(UnavailableSalesQuoteSource)
        val normalized = normalizedHandler()
        val read = LocalOrderReadHandler()
        val bodies = listOf<Pair<MasterCommandHandler, StoredBytes>>(
            legacy to LocalOrderCommandCodec.encode(LocalOrderRequestV1.Create(input.order, input.create)),
            normalized to LocalCollectionStockCodecV2.encode(LocalCollectionStockRequestV2.CreateOrder(input.order, input.create)))
        bodies.forEach { (handler, body) ->
            for (invalid in invalidBodies(body))
                assertFailsWith<IllegalArgumentException> { handler.normalize(invalid) }
            assertEquals(body, handler.normalize(body))
        }
        val query = LocalOrderReadCodec.encode(LocalOrderQueryV1.Order(input.order))
        invalidBodies(query).forEach { assertFailsWith<IllegalArgumentException> { read.normalize(it) } }
    }

    @Test
    fun roS01ExistingOrderSelectionAndOwnPageOverLimitsRemainRefusals() {
        // Existing v1/v2 selection caps are binding facts, not expanded budgets for this attachment.
        fun overLimit(count: Int) = input.create.copy(lines = List(count) { ordinal ->
            input.create.lines.first().copy(portionId = input.auxiliary(1_000 + ordinal))
        })
        assertFailsWith<IllegalArgumentException> {
            LocalOrderCommandCodec.encode(LocalOrderRequestV1.Create(input.order, overLimit(129)))
        }
        assertFailsWith<IllegalArgumentException> {
            LocalCollectionStockCodecV2.encode(LocalCollectionStockRequestV2.CreateOrder(input.order, overLimit(257)))
        }
        assertFailsWith<IllegalArgumentException> { LocalOrderReadCodec.encode(LocalOrderQueryV1.OwnOrders(limit = 26)) }
        // No physical recording command is admitted or executed by this bounded test.
    }

    private fun otherProfiles() = LocalMasterRpcWorkflowProfile.entries.filter { it != LocalMasterRpcWorkflowProfile.RETAIL_ORDERS }

    private fun invalidBodies(body: StoredBytes) = listOf(StoredBytes(body.copyBytes().copyOf(body.size - 1)),
        StoredBytes((" " + body.copyBytes().decodeToString()).encodeToByteArray()),
        StoredBytes("{\"type\":\"UNKNOWN_RETAIL_ACTION\"}".encodeToByteArray()))

    private fun normalizedHandler() = LocalCollectionStockHandler(LocalStockRepository(), UnavailableLocalStockAuthority,
        object : TransactionSalesStockQuoteSource {
            override suspend fun currentNormalized(view: CommandReadView, selection: LocalOrderSelectionV1): PreparedSalesQuote? =
                error("Classification cannot quote")
            override suspend fun requireOriginalNormalized(sources: LocalCommerceOriginalSources,
                selection: LocalOrderSelectionV1, actual: LocalSalesQuotedOrder): Set<RecoveryResourceKey> =
                error("Classification cannot consume original sources")
            override suspend fun observeReservationAuthority(view: CommandReadView,
                lines: List<LocalStockAllocationRequest>): Set<RecoveryResourceKey> = error("Classification cannot reserve stock")
            override suspend fun requireOriginalReservationAuthority(sources: LocalCommerceOriginalSources,
                line: LocalStockDemandLine): Set<RecoveryResourceKey> = error("Classification cannot consume stock history")
        })
}

private object RetailOrderRpcSelectionInputs {
    val order = DomainId("10000000-0000-4000-8000-000000000101")
    private val otherActor = DomainId("10000000-0000-4000-8000-000000000012")
    private val skuA = DomainId("10000000-0000-4000-8000-000000000031")
    private val skuB = DomainId("10000000-0000-4000-8000-000000000032")
    fun auxiliary(ordinal: Int) = DomainId("d0000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    val create = LocalOrderSelectionV1(LocalSalesActivity.SUPERMARKET, LocalSalesChannel.COUNTER, Currency.EGP,
        Revision(7), listOf(LocalOrderLineV1(auxiliary(3), auxiliary(5), skuA, Quantity(auxiliary(1), 2, 0)),
            LocalOrderLineV1(auxiliary(4), auxiliary(6), skuB, Quantity(auxiliary(2), 250, 3))))
    val replace = create.copy(lines = listOf(create.lines[0].copy(quantity = Quantity(auxiliary(1), 1, 0)),
        create.lines[1].copy(quantity = Quantity(auxiliary(2), 500, 3))))
    private val handoff = auxiliary(7)
    private val evidence = LocalCashEvidenceV1(auxiliary(8), UtcInstant.parseCanonical("2026-11-04T00:00:00Z"))
    val legacyExcluded = listOf<LocalOrderRequestV1>(
        LocalOrderRequestV1.OfferHandoff(order, Revision(1), handoff, otherActor, evidence),
        LocalOrderRequestV1.CancelHandoff(order, Revision(1), handoff),
        LocalOrderRequestV1.AcceptHandoff(order, Revision(1), handoff, evidence))
    val normalizedExcluded = listOf<LocalCollectionStockRequestV2>(
        LocalCollectionStockRequestV2.OfferHandoff(order, Revision(1), handoff, otherActor, evidence),
        LocalCollectionStockRequestV2.CancelHandoff(order, Revision(1), handoff),
        LocalCollectionStockRequestV2.AcceptHandoff(order, Revision(1), handoff, evidence),
        LocalCollectionStockRequestV2.ReassignOrder(order, Revision(1), DomainId("10000000-0000-4000-8000-000000000011"),
            otherActor, "retail.refusal", evidence),
        LocalCollectionStockRequestV2.Begin(order, Revision(1), auxiliary(9), Revision(1), auxiliary(10), auxiliary(11)),
        LocalCollectionStockRequestV2.Finalize(order, Revision(1), auxiliary(10), Revision(1), Revision(1), auxiliary(12), auxiliary(13)))
}
