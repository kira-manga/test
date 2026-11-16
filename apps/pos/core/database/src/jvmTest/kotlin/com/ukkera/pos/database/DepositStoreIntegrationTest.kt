package com.ukkera.pos.database

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.test.*

/**
 * Self-authored, NOT independent. Supporting CAT-10 checks using synthetic identities/grants and
 * money, the actual native head-approved SKU/unit publication ceremony, file-backed SQLite and the
 * production registry. All business rows originate in production commands. Zero-priced goods
 * exercise a genuine normalized finalized sale without qualifying physical payment, deposit cold
 * restart, admitted-network RPC or owner acceptance. The signature-loss case can refuse actual
 * native verification but cannot replace its trust or accept a test signing key.
 */
class DepositStoreIntegrationTest {
    private lateinit var current: DepositNativeFixture
    private val scope get() = current.scope
    private val actor get() = current.actor
    private var serial = 10_000
    private fun next() = testId(serial++)
    private fun op() = OperationId(next().value)
    private fun egp(value: Long) = Money(Currency.EGP, value)
    private fun evidence() = LocalCashEvidenceV1(next(), TEST_TIME)
    private val each = testId(201)
    private val sku = testId(202)
    private val warehouse = testId(203)
    private val drawer = testId(204)
    private val session = testId(205)
    private val order = testId(206)
    private val portion = testId(207)
    private val sale = testId(208)
    private var signatureAvailable = true
    private val signatures = CompanyPublicationSignatureVerifier { company, key, bytes, hex ->
        signatureAvailable && current.publications.trust.verify(company, key, bytes, hex)
    }

    private fun publication(deposit: Long? = 100, version: Long = 1) = LocalPublishedSkuV1(
        company = CompanyScope(scope.organizationId, scope.companyId), publicationId = next(),
        skuId = sku, productId = testId(210), catalogRevision = Revision(version), skuRevision = Revision(version),
        names = LocalNamesV1("Synthetic container", "عبوة اختبار"), code = "CONTAINER-TEST", barcodes = emptyList(),
        baseUnitId = each, quantityScale = 0, tracking = LocalTrackingV1.QUANTITY, fulfillment = LocalFulfillmentV1.RETAIL,
        requiresExpiry = false, canPurchase = true, canSell = true, active = true, conversions = emptyList(),
        prices = listOf(LocalPriceV1(testId(211), Revision(version), null, 0, Quantity(each, 1, 0),
            effectiveFrom = TEST_TIME, unitPrice = UnitPrice.parse(Currency.EGP, "0"), inclusion = TaxInclusion.EXCLUSIVE,
            tax = TaxSnapshot(testId(212), Revision(1), "SYNTHETIC_ZERO", PercentageRate.parse("0")))),
        unitDefinitions = listOf(CatalogUnit(each, Code.exact("EA"), UnitDimension.COUNT, 0)),
        containerDeposit = deposit?.let { LocalContainerDepositV1(egp(it), testId(213), testId(214)) })

    private suspend fun signed(value: LocalPublishedSkuV1): LocalSignedSkuV1 {
        val publications = current.publications
        val proposal = CompanyBusinessPublicationProposal(value.company,
            CompanyBusinessPublicationKind.CATALOG_SKU_V1, value.publicationId, value.skuId,
            LocalConfigurationCodec.canonicalPublication(value))
        val action = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(
            publications.prepare(proposal)).value
        val review = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(
            publications.review(action.originalActionId)).value
        return assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationOutput>>(
            publications.approve(review, "head.one", "synthetic-head-pass-2026".toCharArray()))
            .value.readNativeCompanySku()
    }
    private suspend fun DepositNativeFixture.apply(request: MutationRequest): MutationReply.Recorded {
        val reply = execute(request)
        return assertIs<MutationReply.Recorded>(reply, "${request.command}: ${(reply as? MutationReply.Deferred)?.reason}")
            .also { assertTrue(it.committed, "${request.command}: ${it.code} ${it.result.copyBytes().decodeToString()}") }
    }
    private suspend fun DepositNativeFixture.configure(request: LocalConfigurationRequestV1) =
        apply(MutationRequest(op(), LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(request)))
    private suspend fun DepositNativeFixture.cash(request: LocalCashRequestV1) =
        apply(MutationRequest(op(), LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(request)))
    private suspend fun DepositNativeFixture.collection(request: LocalCollectionStockRequestV2) =
        apply(LocalCollectionStockCodecV2.command(op(), request))

    private fun selection() = LocalOrderSelectionV1(LocalSalesActivity.SUPERMARKET, LocalSalesChannel.COUNTER,
        Currency.EGP, Revision(0), listOf(LocalOrderLineV1(testId(215), portion, sku, Quantity(each, 2, 0))))

    private suspend fun prepare(kernel: DepositNativeFixture, deposit: Long? = 100): Pair<DepositNativeFixture, LocalPublishedSkuV1> {
        current = kernel
        kernel.install(signatures)
        val store = kernel
        store.apply(LocalStockRelationalCommandsV2.initializeFresh(op(), next(), "synthetic.opening"))
        val published = publication(deposit)
        store.configure(LocalConfigurationRequestV1.InstallCompanySku(signed(published)))
        store.configure(LocalConfigurationRequestV1.ConfigureWarehouse(null,
            LocalWarehouseV1(warehouse, LocalNamesV1("Synthetic warehouse", "مخزن اختبار"), true)))
        val basis = next(); val allocation = next()
        store.configure(LocalConfigurationRequestV1.ApproveReceiving(LocalReceivingApprovalV1(basis,
            LocalBasisKindV1.OPENING, null, TEST_TIME, null, listOf(LocalApprovedStockLineV1(allocation, sku, warehouse,
                Quantity(each, 2, 0), egp(200), false)))))
        store.configure(LocalConfigurationRequestV1.ConfigureSalesRoute(null, LocalSalesRouteV1(next(),
            LocalSalesActivity.SUPERMARKET, LocalSalesChannel.COUNTER, warehouse, true)))
        store.apply(LocalStockRelationalCommandsV2.opening(op(), LocalGoodsReceiptRequest(next(), basis, Revision(0),
            listOf(LocalStockItemVersion(sku, null)), listOf(LocalGoodsReceiptLine(next(), allocation, next(), sku, warehouse,
                Quantity(each, 2, 0), null, null, null, LocalReceiptDisposition.RELEASED, egp(200))), "synthetic.opening")))
        store.cash(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic drawer", Currency.EGP))
        store.cash(LocalCashRequestV1.ConfigureCustodian(actor))
        store.cash(LocalCashRequestV1.Open(session, drawer, Revision(0), Revision(0),
            BusinessDate(LocalDate(2026, 10, 23)), next(), egp(1_000), evidence()))
        store.collection(LocalCollectionStockRequestV2.CreateOrder(order, selection()))
        val collection = next(); val reservation = next()
        store.collection(LocalCollectionStockRequestV2.Begin(order, Revision(0), session, Revision(0), collection, reservation))
        store.collection(LocalCollectionStockRequestV2.Finalize(order, Revision(1), collection, Revision(0), Revision(1), sale, next()))
        return store to published
    }

    private fun collect(id: DomainId = next(), count: Long = 2, revision: Long = 0) = LocalDepositRequestV1.Collect(
        id, sale, portion, session, Revision(revision), count, evidence())
    private fun refund(id: DomainId, depositRevision: Long = 0, sessionRevision: Long = 1, count: Long = 1,
        refundId: DomainId = next()) = LocalDepositRequestV1.Refund(id, Revision(depositRevision), session,
        Revision(sessionRevision), refundId, count, evidence())
    private suspend fun DepositNativeFixture.deposit(request: LocalDepositRequestV1, operation: OperationId = op()) =
        execute(MutationRequest(operation, LocalDepositCodec.COMMAND, LocalDepositCodec.encode(request)))
    private fun recorded(reply: MutationReply): LocalDepositResultV1.Recorded = assertIs<LocalDepositResultV1.Recorded>(
        LocalDepositCodec.result(assertIs<MutationReply.Recorded>(reply).also { assertTrue(it.committed, it.code) }.result))
    private fun refused(reply: MutationReply, reason: LocalDepositRefusalV1) {
        val retained = assertIs<MutationReply.Recorded>(reply, "${(reply as? MutationReply.Deferred)?.reason}")
        assertFalse(retained.committed)
        assertEquals(reason, assertIs<LocalDepositResultV1.Refused>(LocalDepositCodec.result(retained.result)).reason)
    }
    private suspend fun DepositNativeFixture.page(query: LocalDepositQueryV1) = LocalDepositCodec.page(
        assertIs<ReadReply.Data>(read(LocalDepositCodec.request(query))).payload)
    private suspend fun DepositNativeFixture.balance() = assertIs<LocalCashReadResultV1.Session>(LocalCashReadCodec.decode(
        assertIs<ReadReply.Data>(read(LocalCashReadCodec.request(LocalCashQueryV1.Session(session)))).payload)).session

    @Test fun collectionAndPartialThenFullRefundPreserveLiabilityCashAndIdempotency(): Unit = runBlocking {
        val kernel = DepositNativeFixture()
        try {
            val (store, published) = prepare(kernel)
            val request = collect(); val operation = op()
            val first = store.deposit(request, operation)
            val collected = recorded(first).deposit
            assertEquals(published.publicationId, collected.publicationId)
            assertEquals(egp(1_200), store.balance().expectedCash)
            assertEquals(egp(200), store.balance().payIns)
            assertEquals(recorded(first), recorded(store.deposit(request, operation)))
            refused(store.deposit(collect()), LocalDepositRefusalV1.DUPLICATE)
            val partial = refund(collected.id)
            assertEquals(1L, recorded(store.deposit(partial)).deposit.returned)
            assertEquals(egp(1_100), store.balance().expectedCash)
            refused(store.deposit(refund(collected.id, refundId = partial.refundId)), LocalDepositRefusalV1.DUPLICATE)
            refused(store.deposit(refund(collected.id)), LocalDepositRefusalV1.STALE)
            val complete = recorded(store.deposit(refund(collected.id, 1, 2))).deposit
            assertEquals(2L, complete.returned)
            assertEquals(egp(1_000), store.balance().expectedCash)
            assertEquals(egp(200), store.balance().payOuts)
            refused(store.deposit(refund(collected.id, 2, 3)), LocalDepositRefusalV1.CONTAINERS_EXCEEDED)
            assertEquals(complete, assertIs<LocalDepositPageV1.Deposit>(store.page(LocalDepositQueryV1.Deposit(collected.id))).deposit)
            assertEquals(listOf(complete), assertIs<LocalDepositPageV1.Sale>(store.page(LocalDepositQueryV1.Sale(sale))).deposits)
        } finally { kernel.close() }
    }

    @Test fun overSoldStaleAndLoggedOutRequestsLeaveCashUnchanged(): Unit = runBlocking {
        val kernel = DepositNativeFixture()
        try {
            val (store) = prepare(kernel)
            refused(store.deposit(collect(count = 3)), LocalDepositRefusalV1.CONTAINERS_EXCEEDED)
            refused(store.deposit(collect(revision = 1)), LocalDepositRefusalV1.STALE)
            kernel.logout()
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(store.deposit(collect())).reason)
            assertIs<ReadReply.Unavailable>(store.read(LocalDepositCodec.request(LocalDepositQueryV1.Sale(sale))))
            kernel.login()
            assertEquals(egp(1_000), store.balance().expectedCash)
            assertTrue(assertIs<LocalDepositPageV1.Sale>(store.page(LocalDepositQueryV1.Sale(sale))).deposits.isEmpty())
            recorded(store.deposit(collect()))
        } finally { kernel.close() }
    }

    @Test fun originalSignedTermsSurviveLaterCatalogReplacementAndSignatureLossFailsClosed(): Unit = runBlocking {
        val kernel = DepositNativeFixture()
        try {
            val (store, original) = prepare(kernel)
            store.configure(LocalConfigurationRequestV1.InstallCompanySku(signed(publication(900, 2))))
            val result = recorded(store.deposit(collect())).deposit
            assertEquals(original.publicationId, result.publicationId)
            assertEquals(egp(100), result.amountPerContainer)
            assertEquals(egp(1_200), store.balance().expectedCash)
            signatureAvailable = false
            val before = kernel.count("operation_result")
            assertIs<MutationReply.Deferred>(store.deposit(collect()))
            assertEquals(before, kernel.count("operation_result"))
        } finally { kernel.close() }
    }

    @Test fun signedAbsenceDoesNotAcquireTermsFromTheCurrentCatalog(): Unit = runBlocking {
        val kernel = DepositNativeFixture()
        try {
            val (store) = prepare(kernel, null)
            store.configure(LocalConfigurationRequestV1.InstallCompanySku(signed(publication(900, 2))))
            refused(store.deposit(collect()), LocalDepositRefusalV1.NO_DEPOSIT_TERMS)
            assertEquals(egp(1_000), store.balance().expectedCash)
        } finally { kernel.close() }
    }

    @Test fun refundCannotSpendUnavailableDrawerCash(): Unit = runBlocking {
        val kernel = DepositNativeFixture()
        try {
            val (store) = prepare(kernel)
            val deposit = recorded(store.deposit(collect())).deposit
            store.cash(LocalCashRequestV1.NonSale(session, Revision(1), next(), NonSaleCashKind.OPERATING_EXPENSE,
                egp(1_200), evidence(), "Synthetic payout"))
            refused(store.deposit(refund(deposit.id, sessionRevision = 2)), LocalDepositRefusalV1.INSUFFICIENT_DRAWER_CASH)
            assertEquals(egp(0), store.balance().expectedCash)
            assertEquals(deposit, assertIs<LocalDepositPageV1.Deposit>(store.page(LocalDepositQueryV1.Deposit(deposit.id))).deposit)
        } finally { kernel.close() }
    }

    @Test fun missingOrTamperedOriginalPublicationCannotCreateCashOrAnEntitlement(): Unit = runBlocking {
        for (missing in listOf(true, false)) {
            val kernel = DepositNativeFixture()
            try {
                val (store, publication) = prepare(kernel)
                val key = AggregateKey(LocalConfigurationProfile.PUBLICATION, publication.publicationId)
                // Deliberate external damage; production custody and guards remain enforced.
                kernel.corruptOriginalPublication(key, missing)
                val before = kernel.count("operation_result")
                assertIs<MutationReply.Deferred>(store.deposit(collect()))
                assertEquals(before, kernel.count("operation_result"))
            } finally { kernel.close() }
        }
    }

    @Test fun legacyQuoteCannotCreateAnOrderForADepositBearingSku(): Unit = runBlocking {
        val kernel = DepositNativeFixture()
        try {
            val (store) = prepare(kernel)
            val request = LocalOrderRequestV1.Create(next(), selection())
            val reply = assertIs<MutationReply.Recorded>(store.execute(MutationRequest(op(),
                LocalSalesStoreProfile.ORDER_COMMAND, LocalOrderCommandCodec.encode(request))))
            assertFalse(reply.committed)
            assertEquals(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.UNSUPPORTED_ACTIVITY),
                LocalSalesResultCodec.decode(reply.result))
            assertEquals(egp(1_000), store.balance().expectedCash)
        } finally { kernel.close() }
    }
}
