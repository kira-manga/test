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
 * Self-authored, NOT independent. Supporting production integration bound to frozen
 * qa/pos/designs/lan-master-client-v1/{cases,fixtures}.json: LAN-D01/D02/D03/D04/D06/D07/D11
 * and LAN-A07. No frozen oracle is edited. Genuine desktop provisioner, head approval,
 * protected authentication, Room, normalized stock and native history; business rows are
 * created only by production commands. Two independently authenticated sessions of ONE
 * expressly multi-session owner on ONE same-device native connection are NOT two admitted
 * OS Client processes. Counted non-sale cash and zero-total sales are supporting variants,
 * NOT F-RETAIL's paid sales. No TCP, UI convergence, process-kill, external-paid, native
 * Windows/macOS, device withdrawal or full-case pass is claimed by this class.
 */
class LanBusinessTransactionIntegrationTest {
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

    @Test fun staleOrderReplacementKeepsOneWholeWinnerAndIndependentCreatesBothSurvive(): Unit = runBlocking {
        val f = LanBusinessNativeFixture()
        try {
            f.install(); prepareStock(f, 12); openCash(f)
            val second = f.credentials(f.secondSession())
            val id = next(); val original = selection()
            applied(f.execute(collection(LocalCollectionStockRequestV2.CreateOrder(id, original))))
            assertEquals(Revision(0), order(f, id).revision)
            assertEquals(Revision(0), order(f, id, second).revision)
            val x = selection(2); val y = selection(3)
            val requests = listOf(x, y).map { collection(LocalCollectionStockRequestV2.ReplaceUnlockedOrder(id, Revision(0), it)) }
            val replies = race({ f.execute(requests[0]) }, { f.endpoint.execute(second, requests[1]) }).map(::recorded)
            val winner = replies.indexOfFirst { it.committed }; assertTrue(winner >= 0)
            assertEquals(1, replies.count { it.committed })
            assertEquals(LocalSalesResultV1.Rejected(SalesRule.REVISION_CONFLICT), LocalSalesResultCodec.decode(replies[1 - winner].result))
            val retained = order(f, id)
            assertEquals(Revision(1), retained.revision)
            assertEquals(listOf(x, y)[winner], retained.selection)
            val ids = listOf(next(), next())
            val creates = ids.map { collection(LocalCollectionStockRequestV2.CreateOrder(it, selection())) }
            race({ f.execute(creates[0]) }, { f.endpoint.execute(second, creates[1]) }).forEach { applied(it) }
            ids.forEach { assertEquals(Revision(0), order(f, it).revision) }
            assertEquals(Quantity(each, 12, 0), stock(f).available)
            assertEquals(Quantity(each, 0, 0), stock(f).reserved)
            assertEquals(egp(48_000), cost(f).carryingValue)
            assertEquals(egp(100_000), balance(f).expectedCash)
            assertNull(retained.saleId)
            requests.forEach { assertEquals(1L, f.operationCounts(it.operationId).getValue("journal_envelope")) }
        } finally { f.close() }
    }

    @Test fun lastUnitReservationExcludesTheOtherSessionAndZeroSaleReplayNeverIssuesTwice(): Unit = runBlocking {
        val f = LanBusinessNativeFixture()
        try {
            f.install(); prepareStock(f, 1); openCash(f)
            val second = f.credentials(f.secondSession())
            val ids = listOf(next(), next()); val collections = listOf(next(), next()); val reservations = listOf(next(), next())
            ids.forEach { applied(f.execute(collection(LocalCollectionStockRequestV2.CreateOrder(it, selection())))) }
            val requests = ids.indices.map { collection(LocalCollectionStockRequestV2.Begin(ids[it], Revision(0), cashSession,
                Revision(0), collections[it], reservations[it])) }
            val replies = race({ f.execute(requests[0]) }, { f.endpoint.execute(second, requests[1]) }).map(::recorded)
            val winner = replies.indexOfFirst { it.committed }; assertTrue(winner >= 0)
            assertEquals(1, replies.count { it.committed })
            assertEquals(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.STOCK_REQUIRED), LocalSalesResultCodec.decode(replies[1 - winner].result))
            assertEquals(Quantity(each, 1, 0), stock(f).onHand)
            assertEquals(Quantity(each, 1, 0), stock(f).reserved)
            assertEquals(Quantity(each, 0, 0), stock(f).available)
            assertNull(order(f, ids[1 - winner]).collectionId)
            val sale = next()
            val finalize = collection(LocalCollectionStockRequestV2.Finalize(ids[winner], Revision(1), collections[winner],
                Revision(0), Revision(1), sale, next()))
            val deliveries = race({ f.execute(finalize) }, { f.endpoint.execute(second, finalize) }).map(::applied)
            assertEquals(1, deliveries.count { !it.replayed })
            sameOriginal(deliveries.first { !it.replayed }, deliveries.first { it.replayed })
            val counts = f.operationCounts(finalize.operationId)
            assertEquals(mapOf("operation_result" to 1L, "journal_envelope" to 1L, "audit_event" to 1L, "outbox_intent" to 1L), counts)
            assertEquals(sale, order(f, ids[winner]).saleId)
            assertEquals(Quantity(each, 0, 0), stock(f).onHand)
            assertEquals(Quantity(each, 0, 0), stock(f).reserved)
            assertEquals(egp(0), cost(f).carryingValue)
            assertEquals(egp(100_000), balance(f).expectedCash)
            repeat(3) { sameOriginal(deliveries[0], applied(f.endpoint.execute(second, finalize))) }
            assertEquals(counts, f.operationCounts(finalize.operationId))
        } finally { f.close() }
    }

    @Test fun independentZeroTotalSalesFromTwoSessionsBothCommitStockAndRetainTheirOwnResults(): Unit = runBlocking {
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
            val observation = observeLanBusinessStacks()
            val replies = try {
                race({ f.execute(requests[0]) }, { f.endpoint.execute(second, requests[1]) }).map(::applied)
            } finally { observation.close() }
            assertTrue(replies.none { it.replayed })
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

    @Test fun nonSaleExpenseAndExactFirstRefusalsRecoverFromOriginalNativeHistory(): Unit = runBlocking {
        val f = LanBusinessNativeFixture()
        try {
            f.install(); openCash(f)
            val body = LocalCashRequestV1.NonSale(cashSession, Revision(0), next(), NonSaleCashKind.NON_SALE_PAY_IN,
                egp(11_400), evidence(), "Synthetic retained original cash")
            val requests = mutableListOf(cash(body))
            val results = mutableListOf(applied(f.execute(requests.single())))
            suspend fun refused(request: LocalCashRequestV1.NonSale, expected: LocalSalesResultV1) {
                val original = cash(request); requests += original
                val result = recorded(f.execute(original)); assertFalse(result.committed)
                assertEquals(expected, LocalSalesResultCodec.decode(result.result)); results += result
            }
            // Exact live first-refusal order: compatibility source requirement, allocated
            // source, revision, and then funds. None may be replaced by a plausible later one.
            refused(body.copy(kind = NonSaleCashKind.INVENTORY_SETTLEMENT),
                LocalSalesResultV1.Unavailable(LocalSalesUnavailable.SOURCE_REQUIRED))
            refused(body.copy(kind = NonSaleCashKind.OPERATING_EXPENSE, amount = egp(200_000)),
                LocalSalesResultV1.Rejected(SalesRule.DUPLICATE_SOURCE_ALLOCATION))
            refused(body.copy(sourceId = next(), kind = NonSaleCashKind.OPERATING_EXPENSE, amount = egp(200_000)),
                LocalSalesResultV1.Rejected(SalesRule.REVISION_CONFLICT))
            refused(body.copy(sourceId = next(), expectedRevision = Revision(1), kind = NonSaleCashKind.OPERATING_EXPENSE,
                amount = egp(200_000)), LocalSalesResultV1.Rejected(SalesRule.INSUFFICIENT_DRAWER_CASH))
            val expense = cash(body.copy(sourceId = next(), expectedRevision = Revision(1),
                kind = NonSaleCashKind.OPERATING_EXPENSE, amount = egp(1_400)))
            requests += expense; results += applied(f.execute(expense))
            assertEquals(egp(110_000), balance(f).expectedCash)
            val counts = requests.map { f.operationCounts(it.operationId) }
            f.reopen()
            requests.forEachIndexed { index, request ->
                sameOriginal(results[index], assertIs<LookupReply.Found>(lookup(f, f.credentials, request)).result)
                sameOriginal(results[index], recorded(f.execute(request)))
                assertEquals(counts[index], f.operationCounts(request.operationId))
            }
            val after = balance(f)
            assertEquals(egp(110_000), after.expectedCash)
            assertEquals(egp(11_400), after.payIns)
            assertEquals(egp(1_400), after.payOuts)
            assertEquals(Revision(2), after.revision)
        } finally { f.close() }
    }

    @Test fun tamperedMovementWrongRefusalOrMissingBeforeSourceCannotGainColdAdmission(): Unit = runBlocking {
        for (fault in listOf("movement-body", "wrong-first-refusal", "missing-source")) {
            val f = LanBusinessNativeFixture()
            try {
                f.install(); openCash(f)
                val body = LocalCashRequestV1.NonSale(cashSession, Revision(0), next(), NonSaleCashKind.NON_SALE_PAY_IN,
                    egp(11_400), evidence(), "Synthetic retained cash integrity")
                applied(f.execute(cash(body)))
                val stale = cash(body.copy(sourceId = next()))
                val original = recorded(f.execute(stale)); assertFalse(original.committed)
                assertEquals(LocalSalesResultV1.Rejected(SalesRule.REVISION_CONFLICT), LocalSalesResultCodec.decode(original.result))
                // Damage only inert SQL rows after confirmed close. Genuine original frames,
                // hashes and keys remain unchanged and are never forged to bless the damage.
                assertFalse(f.damageForColdReopen(fault, body.sourceId, stale.operationId),
                    "$fault must produce no operational client")
            } finally { f.close() }
        }
    }

    @Test fun cashDuplicateChangedBodyAndColdRecoveryRetainOriginalWithoutRepeatingCash(): Unit = runBlocking {
        val f = LanBusinessNativeFixture()
        try {
            f.install(); openCash(f)
            val second = f.credentials(f.secondSession())
            val body = LocalCashRequestV1.NonSale(cashSession, Revision(0), next(), NonSaleCashKind.NON_SALE_PAY_IN,
                egp(11_400), evidence(), "Synthetic counted supporting cash")
            val request = cash(body)
            val replies = race({ f.execute(request) }, { f.endpoint.execute(second, request) }).map(::applied)
            assertEquals(1, replies.count { !it.replayed })
            sameOriginal(replies.first { !it.replayed }, replies.first { it.replayed })
            assertEquals(egp(111_400), balance(f).expectedCash)
            val counts = f.operationCounts(request.operationId)
            assertEquals(mapOf("operation_result" to 1L, "journal_envelope" to 1L, "audit_event" to 1L, "outbox_intent" to 1L), counts)
            assertIs<MutationReply.IdentityConflict>(f.endpoint.execute(second, cash(body.copy(amount = egp(22_800)), request.operationId)))
            // A different original with a stale expected revision cannot overwrite the winner.
            val stale = recorded(f.endpoint.execute(second, cash(body.copy(sourceId = next()))))
            assertFalse(stale.committed)
            assertEquals(LocalSalesResultV1.Rejected(SalesRule.REVISION_CONFLICT), LocalSalesResultCodec.decode(stale.result))
            assertEquals(egp(111_400), balance(f).expectedCash)
            sameOriginal(replies[0], assertIs<LookupReply.Found>(lookup(f, second, request)).result)
            f.reopen()
            sameOriginal(replies[0], assertIs<LookupReply.Found>(lookup(f, f.credentials, request)).result)
            sameOriginal(replies[0], applied(f.execute(request)))
            assertEquals(counts, f.operationCounts(request.operationId))
            assertEquals(egp(111_400), balance(f).expectedCash)
            assertEquals(Revision(1), balance(f).revision)
        } finally { f.close() }
    }

    @Test fun cancellingAnAcceptedCashWaiterPreservesTheSameLateCommitForRecovery(): Unit = runBlocking {
        val f = LanBusinessNativeFixture()
        val release = CompletableDeferred<Unit>()
        try {
            f.install(); openCash(f)
            val second = f.credentials(f.secondSession())
            val request = cash(LocalCashRequestV1.NonSale(cashSession, Revision(0), next(), NonSaleCashKind.NON_SALE_PAY_IN,
                egp(11_400), evidence(), "Synthetic interrupted counted cash"))
            val entered = CompletableDeferred<Unit>()
            f.beforeCashPrepare = { context -> if (context.operationId == request.operationId) { entered.complete(Unit); release.await() } }
            val waiter = async(Dispatchers.Default) { f.execute(request) }
            withTimeout(10_000) { entered.await() } // Actual production prepare in the IMMEDIATE writer.
            assertEquals(mapOf("operation_result" to 0L, "journal_envelope" to 0L, "audit_event" to 0L, "outbox_intent" to 0L),
                f.operationCounts(request.operationId))
            waiter.cancelAndJoin()
            assertTrue(waiter.isCancelled)
            release.complete(Unit)
            // Same original races with still-capable accepted work. No new financial identity.
            val recovered = applied(f.endpoint.execute(second, request))
            assertTrue(recovered.replayed)
            assertEquals(f.actor, recovered.originalActorId)
            assertEquals(egp(111_400), balance(f).expectedCash)
            sameOriginal(recovered, assertIs<LookupReply.Found>(lookup(f, second, request)).result)
            assertEquals(mapOf("operation_result" to 1L, "journal_envelope" to 1L, "audit_event" to 1L, "outbox_intent" to 1L),
                f.operationCounts(request.operationId))
        } finally { release.complete(Unit); f.close() }
    }

    @Test fun revokedSessionCannotExecuteReadOrRecoverButTheOtherSessionAndCommittedCashSurvive(): Unit = runBlocking {
        val f = LanBusinessNativeFixture()
        try {
            f.install(); openCash(f)
            val tokens = f.secondSession(); val second = f.credentials(tokens)
            val committed = cash(LocalCashRequestV1.NonSale(cashSession, Revision(0), next(), NonSaleCashKind.NON_SALE_PAY_IN,
                egp(11_400), evidence(), "Synthetic pre-revocation cash"))
            val result = applied(f.endpoint.execute(second, committed))
            val detail = assertIs<LocalAdministrationReadResult.Available>(f.administration.readAdministration(f.credentials,
                LocalAdministrationQuery(LocalAdministrationDirectory.ACCOUNTS, f.actor))).view.accountDetail!!
            assertIs<LocalCredentialAdministrationResult.Applied>(f.administration.administer(f.credentials,
                LocalAdministrationCommand(next(), LocalAdministrationAction.REVOKE_SESSION, f.actor,
                    detail.account.accountRevision, sessionId = tokens.view.sessionId)))
            val denied = cash(LocalCashRequestV1.NonSale(cashSession, Revision(1), next(), NonSaleCashKind.NON_SALE_PAY_IN,
                egp(22_800), evidence(), "Synthetic stale session attempt"))
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(f.endpoint.execute(second, denied)).reason)
            assertIs<ReadReply.Unavailable>(f.endpoint.read(second, LocalCashReadCodec.request(LocalCashQueryV1.Session(cashSession))))
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<LookupReply.Deferred>(lookup(f, second, committed)).reason)
            assertTrue(f.operationCounts(denied.operationId).values.all { it == 0L })
            sameOriginal(result, assertIs<LookupReply.Found>(lookup(f, f.credentials, committed)).result)
            assertEquals(egp(111_400), balance(f).expectedCash)
            applied(f.execute(denied)) // Only the still-current independent session may perform it.
            assertEquals(egp(134_200), balance(f).expectedCash)
        } finally { f.close() }
    }

    @Test fun closingCutRefusesLaterCashAndOutstandingCollectionIsNotForgotten(): Unit = runBlocking {
        val f = LanBusinessNativeFixture()
        try {
            f.install(); openCash(f)
            val second = f.credentials(f.secondSession())
            val currentWork = ownCash(f).workRevision
            applied(f.execute(cash(LocalCashRequestV1.BeginClosing(cashSession, Revision(0), currentWork))))
            val movement = cash(LocalCashRequestV1.NonSale(cashSession, Revision(1), next(), NonSaleCashKind.NON_SALE_PAY_IN,
                egp(11_400), evidence(), "Synthetic after-cut cash"))
            val refused = recorded(f.endpoint.execute(second, movement))
            assertFalse(refused.committed)
            assertEquals(egp(100_000), balance(f).closingExpected)
            assertEquals(egp(100_000), balance(f).expectedCash)
            applied(f.execute(cash(LocalCashRequestV1.CancelClosing(cashSession, Revision(1)))))
            prepareStock(f, 1)
            val orderId = next(); val collectionId = next()
            applied(f.execute(collection(LocalCollectionStockRequestV2.CreateOrder(orderId, selection()))))
            applied(f.execute(collection(LocalCollectionStockRequestV2.Begin(orderId, Revision(0), cashSession,
                Revision(2), collectionId, next()))))
            val close = recorded(f.endpoint.execute(second, cash(LocalCashRequestV1.BeginClosing(cashSession,
                Revision(2), ownCash(f).workRevision))))
            assertFalse(close.committed)
            assertEquals(LocalSalesResultV1.Rejected(SalesRule.CLOSING_BLOCKED), LocalSalesResultCodec.decode(close.result))
            assertEquals(collectionId, order(f, orderId).collectionId)
            assertEquals(Quantity(each, 1, 0), stock(f).reserved)
            assertEquals(egp(100_000), balance(f).expectedCash)
        } finally { f.close() }
    }
}
