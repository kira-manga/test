package com.ukkera.pos.database

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDate
import kotlin.test.*

/**
 * Self-authored, NOT independent. Exact retained-source authorization regression after
 * two actual normalized stock finalizers, using the existing native supporting setup.
 * Same-owner two-session source-port coverage only; not TCP/timing qualification.
 * Supporting production integration bound to frozen
 * qa/pos/designs/lan-master-client-v1/{cases,fixtures}.json: LAN-D01/D02/D03/D04/D06/D07/D11
 * and LAN-A07. No frozen oracle is edited. Genuine desktop provisioner, head approval,
 * protected authentication, Room, normalized stock and native history; business rows are
 * created only by production commands. Two independently authenticated sessions of ONE
 * expressly multi-session owner on ONE same-device native connection are NOT two admitted
 * OS Client processes. Counted non-sale cash and zero-total sales are supporting variants,
 * NOT F-RETAIL's paid sales. No TCP, UI convergence, process-kill, external-paid, native
 * Windows/macOS, device withdrawal or full-case pass is claimed by this class.
 */
class StockRetainedAuthorizationNativeSupportingTest {
    private fun next() = DomainId.random()
    private fun op() = OperationId.random()
    private fun egp(minor: Long) = Money(Currency.EGP, minor)
    private fun evidence() = LocalCashEvidenceV1(next(), TEST_TIME)
    private val each = testId(21_001)
    private val sku = testId(21_002)
    private val warehouse = testId(21_003)
    private val drawer = testId(21_004)
    private val cashSession = testId(21_005)

    private fun cash(request: LocalCashRequestV1, operation: OperationId = op()) = MutationRequest(operation,
        LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(request))
    private fun collection(request: LocalCollectionStockRequestV2, operation: OperationId = op()) =
        LocalCollectionStockCodecV2.command(operation, request)
    private fun recorded(reply: MutationReply) = assertIs<MutationReply.Recorded>(reply,
        "Expected durable result; deferred=${(reply as? MutationReply.Deferred)?.reason}")
    private fun applied(reply: MutationReply) = recorded(reply).also { assertTrue(it.committed, "${it.code}: ${it.result.copyBytes().decodeToString()}") }
    private fun sameOriginal(expected: MutationReply.Recorded, actual: MutationReply.Recorded) {
        assertEquals(expected.operationId, actual.operationId)
        assertEquals(expected.originalActorId, actual.originalActorId)
        assertEquals(expected.committed, actual.committed)
        assertEquals(expected.code, actual.code)
        assertEquals(expected.result, actual.result)
        assertEquals(expected.authorityEpoch, actual.authorityEpoch)
        assertEquals(expected.sequence, actual.sequence)
        assertEquals(expected.recordedAt, actual.recordedAt)
        assertTrue(actual.replayed)
    }
    private fun identity(f: LanBusinessNativeFixture, request: MutationRequest, normalized: StoredBytes = request.payload) =
        OperationIdentity(f.scope.branchId, request.operationId, f.actor, 1,
            semanticDigest(f.scope, f.actor, request.command, normalized).value)
    private suspend fun lookup(f: LanBusinessNativeFixture, credentials: LocalGatewayCredentials, request: MutationRequest) =
        f.endpoint.lookup(credentials, identity(f, request))
    private suspend fun balance(f: LanBusinessNativeFixture, credentials: LocalGatewayCredentials = f.credentials) =
        assertIs<LocalCashReadResultV1.Session>(LocalCashReadCodec.decode(assertIs<ReadReply.Data>(
            f.endpoint.read(credentials, LocalCashReadCodec.request(LocalCashQueryV1.Session(cashSession)))).payload)).session
    private suspend fun ownCash(f: LanBusinessNativeFixture) = assertIs<LocalCashReadResultV1.OwnCash>(LocalCashReadCodec.decode(
        assertIs<ReadReply.Data>(f.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash))).payload))
    private suspend fun order(f: LanBusinessNativeFixture, id: DomainId, credentials: LocalGatewayCredentials = f.credentials) =
        LocalOrderReadCodecV2.decode(assertIs<ReadReply.Data>(f.endpoint.read(credentials,
            LocalOrderReadCodecV2.request(LocalOrderQueryV1.Order(id)))).payload).orders.single().order
    private suspend fun stock(f: LanBusinessNativeFixture) = LocalStockQueries.decodeQuantities(
        assertIs<ReadReply.Data>(f.read(LocalStockCommands.quantities(sku))).payload).rows.single()
    private suspend fun cost(f: LanBusinessNativeFixture) = LocalStockQueries.decodeCosts(
        assertIs<ReadReply.Data>(f.read(LocalStockCommands.costs(sku))).payload).rows.single()
    private fun selection(quantity: Long = 1, line: DomainId = next(), portion: DomainId = next()) =
        LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER, Currency.EGP, Revision(0),
            listOf(LocalOrderLineV1(line, portion, sku, Quantity(each, quantity, 0))))

    private suspend fun openCash(f: LanBusinessNativeFixture) {
        applied(f.execute(cash(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic LAN drawer", Currency.EGP))))
        applied(f.execute(cash(LocalCashRequestV1.ConfigureCustodian(f.actor))))
        applied(f.execute(cash(LocalCashRequestV1.Open(cashSession, drawer, Revision(0), Revision(0),
            BusinessDate(LocalDate(2026, 10, 23)), next(), egp(100_000), evidence()))))
    }
    private suspend fun prepareStock(f: LanBusinessNativeFixture, units: Long) {
        applied(f.execute(LocalStockRelationalCommandsV2.initializeFresh(op(), next(), "synthetic.opening")))
        val published = LocalPublishedSkuV1(CompanyScope(f.scope.organizationId, f.scope.companyId), next(), sku,
            testId(21_006), Revision(1), Revision(1), LocalNamesV1("Synthetic LAN SKU", "صنف اختبار"),
            "LAN-SUPPORTING", emptyList(), each, 0, LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL,
            false, true, true, true, emptyList(), listOf(LocalPriceV1(testId(21_007), Revision(1), null, 0,
                Quantity(each, 1, 0), effectiveFrom = TEST_TIME, unitPrice = UnitPrice.parse(Currency.EGP, "0"), inclusion = TaxInclusion.EXCLUSIVE,
                tax = TaxSnapshot(testId(21_008), Revision(1), "SYNTHETIC_ZERO", PercentageRate.parse("0")))),
            unitDefinitions = listOf(CatalogUnit(each, Code.exact("EA"), UnitDimension.COUNT, 0)))
        val proposal = CompanyBusinessPublicationProposal(published.company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
            published.publicationId, sku, LocalConfigurationCodec.canonicalPublication(published))
        val action = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.prepare(proposal)).value
        val reviewed = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.review(action.originalActionId)).value
        val signed = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationOutput>>(f.publications.approve(
            reviewed, "head.one", "synthetic-head-pass-2026".toCharArray())).value.readNativeCompanySku()
        suspend fun configure(request: LocalConfigurationRequestV1) = applied(f.execute(MutationRequest(op(),
            LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(request))))
        configure(LocalConfigurationRequestV1.InstallCompanySku(signed))
        configure(LocalConfigurationRequestV1.ConfigureWarehouse(null,
            LocalWarehouseV1(warehouse, LocalNamesV1("Synthetic warehouse", "مخزن اختبار"), true)))
        val basis = next(); val allocation = next()
        configure(LocalConfigurationRequestV1.ApproveReceiving(LocalReceivingApprovalV1(basis, LocalBasisKindV1.OPENING,
            null, TEST_TIME, null, listOf(LocalApprovedStockLineV1(allocation, sku, warehouse,
                Quantity(each, units, 0), egp(units * 4_000), false)))))
        configure(LocalConfigurationRequestV1.ConfigureSalesRoute(null, LocalSalesRouteV1(next(), LocalSalesActivity.CLOTHING,
            LocalSalesChannel.COUNTER, warehouse, true)))
        applied(f.execute(LocalStockRelationalCommandsV2.opening(op(), LocalGoodsReceiptRequest(next(), basis, Revision(0),
            listOf(LocalStockItemVersion(sku, null)), listOf(LocalGoodsReceiptLine(next(), allocation, next(), sku, warehouse,
                Quantity(each, units, 0), null, null, null, LocalReceiptDisposition.RELEASED, egp(units * 4_000))), "synthetic.opening"))))
    }
    /** Both callers reach the start barrier; outcome is asserted from durable state, never timing. */
    private suspend fun race(first: suspend () -> MutationReply, second: suspend () -> MutationReply): List<MutationReply> = coroutineScope {
        val gate = CompletableDeferred<Unit>(); val ready = List(2) { CompletableDeferred<Unit>() }
        val jobs = listOf(first, second).mapIndexed { index, action -> async(Dispatchers.Default) {
            ready[index].complete(Unit); gate.await(); action()
        } }
        ready.forEach { it.await() }; gate.complete(Unit); jobs.awaitAll()
    }

    @Test fun bothFinalizedOriginalsRetainTheirOwnTypedRpcRequirements(): Unit = runBlocking {
        val f = LanBusinessNativeFixture()
        try {
            f.install(); prepareStock(f, 12); openCash(f)
            val second = f.credentials(f.secondSession())
            val ids = listOf(next(), next()); val collections = listOf(next(), next()); val reservations = listOf(next(), next())
            ids.forEach { applied(f.execute(collection(LocalCollectionStockRequestV2.CreateOrder(it, selection())))) }
            val begins = ids.indices.map { collection(LocalCollectionStockRequestV2.Begin(ids[it], Revision(0), cashSession,
                Revision(0), collections[it], reservations[it])) }
            race({ f.execute(begins[0]) }, { f.endpoint.execute(second, begins[1]) }).forEach { applied(it) }
            assertEquals(Quantity(each, 2, 0), stock(f).reserved)
            val sales = listOf(next(), next())
            val requests = ids.indices.map { collection(LocalCollectionStockRequestV2.Finalize(ids[it], Revision(1),
                collections[it], Revision(0), Revision(1), sales[it], next())) }
            val replies = race({ f.execute(requests[0]) }, { f.endpoint.execute(second, requests[1]) }).map(::applied)
            assertTrue(replies.none { it.replayed })
            val endpoint = assertIs<LocalMasterEndpoint>(f.endpoint)
            for (request in begins + requests) {
                val original = identity(f, request)
                repeat(3) {
                    assertEquals(LocalMasterRpcAccessResult.ALLOWED, endpoint.authorizeRpcBusiness(f.credentials,
                        LocalMasterRpcBusinessAccess.Execute(request, LocalMasterRpcWorkflowProfile.LAN_BUSINESS),
                        requireRecovery = true), "Exact original Execute source authority")
                    assertEquals(LocalMasterRpcAccessResult.ALLOWED, endpoint.authorizeRpcBusiness(second,
                        LocalMasterRpcBusinessAccess.Lookup(original, LocalMasterRpcWorkflowProfile.LAN_BUSINESS),
                        requireRecovery = true), "Exact original Lookup source authority")
                }
                val changed = OperationIdentity(f.scope.branchId, request.operationId, f.actor, 1, "0".repeat(64))
                assertEquals(LocalMasterRpcAccessResult.DENIED, endpoint.authorizeRpcBusiness(f.credentials,
                    LocalMasterRpcBusinessAccess.Lookup(changed, LocalMasterRpcWorkflowProfile.LAN_BUSINESS),
                    requireRecovery = true), "Changed digest must not disclose a result")
            }
            ids.indices.forEach { index ->
                assertEquals(sales[index], order(f, ids[index]).saleId)
                sameOriginal(replies[index], assertIs<LookupReply.Found>(lookup(f, second, requests[index])).result)
                assertEquals(mapOf("operation_result" to 1L, "journal_envelope" to 1L, "audit_event" to 1L, "outbox_intent" to 1L),
                    f.operationCounts(requests[index].operationId))
            }
            assertEquals(Quantity(each, 10, 0), stock(f).onHand)
            assertEquals(Quantity(each, 0, 0), stock(f).reserved)
            assertEquals(egp(40_000), cost(f).carryingValue)
            assertEquals(egp(100_000), balance(f).expectedCash)
        } finally { f.close() }
    }


    @Test fun separatelyEnrolledActorsCanRecoverBothFinalizersAfterSharedStockChanges(): Unit = runBlocking {
        val f = LanBusinessNativeFixture()
        try {
            f.install()
            val second = f.enrollStockActor()
            val principal = assertIs<LocalSessionViewResult.Current>(f.endpoint.current(second)).view.actorId
            assertNotEquals(f.actor, principal)
            prepareStock(f, 12); openCash(f)
            val otherDrawer = next(); val otherCash = next()
            applied(f.execute(cash(LocalCashRequestV1.ConfigureDrawer(otherDrawer, "Synthetic second actor drawer", Currency.EGP))))
            applied(f.execute(cash(LocalCashRequestV1.ConfigureCustodian(principal))))
            applied(f.endpoint.execute(second, cash(LocalCashRequestV1.Open(otherCash, otherDrawer, Revision(0), Revision(0),
                BusinessDate(LocalDate(2026, 10, 23)), next(), egp(100_000), evidence()))))
            val credentials = listOf(f.credentials, second)
            val owners = listOf(f.actor, principal)
            val orders = listOf(next(), next()); val collections = listOf(next(), next())
            for (i in orders.indices) applied(f.endpoint.execute(credentials[i], collection(
                LocalCollectionStockRequestV2.CreateOrder(orders[i], selection()))))
            val begins = orders.indices.map { collection(LocalCollectionStockRequestV2.Begin(orders[it], Revision(0),
                if (it == 0) cashSession else otherCash, Revision(0), collections[it], next())) }
            race({ f.endpoint.execute(credentials[0], begins[0]) }, { f.endpoint.execute(credentials[1], begins[1]) }).forEach { applied(it) }
            val requests = orders.indices.map { collection(LocalCollectionStockRequestV2.Finalize(orders[it], Revision(1),
                collections[it], Revision(0), Revision(1), next(), next())) }
            val results = race({ f.endpoint.execute(credentials[0], requests[0]) },
                { f.endpoint.execute(credentials[1], requests[1]) }).map(::applied)
            val endpoint = assertIs<LocalMasterEndpoint>(f.endpoint)
            for (i in requests.indices) {
                val request = requests[i]
                val original = OperationIdentity(f.scope.branchId, request.operationId, owners[i], 1,
                    semanticDigest(f.scope, owners[i], request.command, request.payload).value)
                repeat(3) {
                    assertEquals(LocalMasterRpcAccessResult.ALLOWED, endpoint.authorizeRpcBusiness(credentials[i],
                        LocalMasterRpcBusinessAccess.Execute(request, LocalMasterRpcWorkflowProfile.LAN_BUSINESS),
                        requireRecovery = true), "Own separately enrolled actor retained Execute")
                    assertEquals(LocalMasterRpcAccessResult.ALLOWED, endpoint.authorizeRpcBusiness(credentials[i],
                        LocalMasterRpcBusinessAccess.Lookup(original, LocalMasterRpcWorkflowProfile.LAN_BUSINESS),
                        requireRecovery = true), "Own separately enrolled actor retained Lookup")
                }
                assertEquals(LocalMasterRpcAccessResult.DENIED, endpoint.authorizeRpcBusiness(credentials[1 - i],
                    LocalMasterRpcBusinessAccess.Lookup(original, LocalMasterRpcWorkflowProfile.LAN_BUSINESS),
                    requireRecovery = true), "Foreign original without ANY_OWNER must be denied")
                sameOriginal(results[i], assertIs<LookupReply.Found>(endpoint.lookup(credentials[i], original)).result)
                assertEquals(mapOf("operation_result" to 1L, "journal_envelope" to 1L, "audit_event" to 1L, "outbox_intent" to 1L),
                    f.operationCounts(request.operationId))
            }
            assertEquals(Quantity(each, 10, 0), stock(f).onHand)
            assertEquals(Quantity(each, 0, 0), stock(f).reserved)
            assertEquals(egp(40_000), cost(f).carryingValue)
            assertEquals(egp(100_000), balance(f).expectedCash)
            val other = assertIs<LocalCashReadResultV1.Session>(LocalCashReadCodec.decode(assertIs<ReadReply.Data>(
                f.endpoint.read(second, LocalCashReadCodec.request(LocalCashQueryV1.Session(otherCash)))).payload)).session
            assertEquals(egp(100_000), other.expectedCash)
        } finally { f.close() }
    }
}
