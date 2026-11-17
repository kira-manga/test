package com.ukkera.pos.app.gateway.supporting.resolver

import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.test.*

/** SELF-AUTHORED, NOT independent. Frozen v1 #107 main-lane (native cold) support.
 * Genuine native cohost history: signed SKU, warehouse/route/opening approval, LEGACY
 * stock.opening.post/1, cash drawer/custodian, one or two LEGACY sales.order.write/1 Create
 * (the second reads the WORK/stock rows written by the first), then cash Open (which reads the
 * order-written WORK). Frozen expectation: the same-OS cold reopen is OPERATIONAL (the whole
 * original history joins) and the original cash session's expected cash is unchanged.
 * Opt-in (environment UKKERA_POS_LEGACY107=1). Linux software native cohost only; no retained
 * financial-original authorization, TCP, additional-device or platform qualification. */
class LegacyOrderMainHistoryNativeSupportingTest {
    private fun id() = DomainId.random()
    private fun op() = OperationId.random()
    private val each = id(); private val sku = id(); private val warehouse = id()
    private val drawer = id(); private val cash = id()
    private val time = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    private fun money(minor: Long) = Money(Currency.EGP, minor)
    private fun applied(reply: MutationReply): MutationReply.Recorded = assertIs<MutationReply.Recorded>(reply).also {
        assertTrue(it.committed, "${it.code}: ${it.result.copyBytes().decodeToString()}")
    }
    private fun enabled(): Boolean = System.getenv("UKKERA_POS_LEGACY107") == "1"

    @Test fun oneLegacyOrderBeforeCashOpenJoinsNativeCold(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Explicit legacy107 native supporting executor required", enabled())
        run(orders = 1)
    }

    @Test fun twoChainedLegacyOrdersBeforeCashOpenJoinNativeCold(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Explicit legacy107 native supporting executor required", enabled())
        run(orders = 2)
    }

    private suspend fun prepareLegacyCatalogAndStock(f: FinancialResolverNativeFixture) {
        val publication = LocalPublishedSkuV1(CompanyScope(f.scope.organizationId, f.scope.companyId), id(), sku, id(), Revision(1), Revision(1),
            LocalNamesV1("Synthetic legacy107 SKU", "صنف اختبار قديم"), "LEGACY-107-M", emptyList(), each, 0,
            LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL, false, true, true, true, emptyList(),
            listOf(LocalPriceV1(id(), Revision(1), null, 0, Quantity(each, 1, 0), effectiveFrom = time,
                unitPrice = UnitPrice.parse(Currency.EGP, "100"), inclusion = TaxInclusion.EXCLUSIVE,
                tax = TaxSnapshot(id(), Revision(1), "SYNTHETIC14", PercentageRate.parse("14")))),
            unitDefinitions = listOf(CatalogUnit(each, Code.exact("EA"), UnitDimension.COUNT, 0)))
        val proposed = CompanyBusinessPublicationProposal(publication.company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
            publication.publicationId, sku, LocalConfigurationCodec.canonicalPublication(publication))
        val action = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.prepare(proposed)).value
        val reviewed = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.review(action.originalActionId)).value
        val signed = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationOutput>>(f.publications.approve(reviewed,
            "head.one", "synthetic-head-pass-2026".toCharArray())).value.readNativeCompanySku()
        suspend fun configure(value: LocalConfigurationRequestV1) = applied(f.execute(MutationRequest(op(), LocalConfigurationProfile.COMMAND,
            LocalConfigurationCodec.encode(value))))
        configure(LocalConfigurationRequestV1.InstallCompanySku(signed))
        configure(LocalConfigurationRequestV1.ConfigureWarehouse(null, LocalWarehouseV1(warehouse, LocalNamesV1("Synthetic warehouse", "مخزن اختبار"), true)))
        configure(LocalConfigurationRequestV1.ConfigureSalesRoute(null, LocalSalesRouteV1(id(), LocalSalesActivity.CLOTHING,
            LocalSalesChannel.COUNTER, warehouse, true)))
        val basis = id(); val allocation = id()
        configure(LocalConfigurationRequestV1.ApproveReceiving(LocalReceivingApprovalV1(basis, LocalBasisKindV1.OPENING, null, time, null,
            listOf(LocalApprovedStockLineV1(allocation, sku, warehouse, Quantity(each, 12, 0), money(48_000), false)))))
        val opening = applied(f.execute(LocalStockCommands.opening(op(), LocalGoodsReceiptRequest(id(), basis, Revision(0),
            listOf(LocalStockItemVersion(sku, null)), listOf(LocalGoodsReceiptLine(id(), allocation, id(), sku, warehouse, Quantity(each, 12, 0),
                null, null, null, LocalReceiptDisposition.RELEASED, money(48_000))), "synthetic.legacy107.opening"))))
        assertFalse(opening.replayed)
    }

    private suspend fun run(orders: Int) {
        val f = FinancialResolverNativeFixture()
        val observed = linkedMapOf<String, String>()
        try {
            f.install()
            prepareLegacyCatalogAndStock(f)
            suspend fun cash(request: LocalCashRequestV1) = applied(f.execute(MutationRequest(op(), LocalSalesStoreProfile.CASH_COMMAND,
                LocalCashCommandCodec.encode(request))))
            cash(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic legacy107 drawer", Currency.EGP))
            cash(LocalCashRequestV1.ConfigureCustodian(f.actor))
            repeat(orders) { index ->
                val created = applied(f.execute(MutationRequest(op(), LocalSalesStoreProfile.ORDER_COMMAND, LocalOrderCommandCodec.encode(
                    LocalOrderRequestV1.Create(id(), LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER,
                        Currency.EGP, Revision(0), listOf(LocalOrderLineV1(id(), id(), sku, Quantity(each, 2, 0)))))))))
                observed["legacyOrder$index"] = created.code
            }
            cash(LocalCashRequestV1.Open(cash, drawer, Revision(0), Revision(0),
                BusinessDate(LocalDate(2026, 11, 17)), id(), money(100_000), LocalCashEvidenceV1(id(), UtcInstant(Clock.System.now()))))
            observed["warmExpectedCash"] = f.expectedCash(cash).minor.toString()
            observed["cold"] = try { f.reopen(); "OPERATIONAL" }
                catch (failure: AssertionError) { "NOT_OPERATIONAL: ${failure.message?.take(300)}" }
            if (observed["cold"] == "OPERATIONAL") observed["coldExpectedCash"] = f.expectedCash(cash).minor.toString()
        } finally {
            System.err.println("LEGACY107M observations orders=$orders: $observed")
            f.close()
        }
        repeat(orders) { assertEquals("committed", observed["legacyOrder$it"]) }
        assertEquals("100000", observed["warmExpectedCash"])
        assertEquals("OPERATIONAL", observed["cold"], "cold: $observed")
        assertEquals("100000", observed["coldExpectedCash"], "cold cash: $observed")
    }
}
