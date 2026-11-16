package com.ukkera.pos.app.gateway.supporting.resolver

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
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

/** Self-authored, NOT independent. Real native cohost/authority support for blueprint5.3 and
 * frozen LAN-A07/D04/D06: independent current supervisor, original cash custody, one linked
 * resolution/effect and exact recovery. This is NOT TCP, a distributed Client-root join,
 * Windows/macOS native qualification or whole frozen-case execution. No controlled reply,
 * financial fact, authorization or current-source port supplies a positive outcome. */
class SupervisorNativeResolutionSupportingTest {
    private fun id() = DomainId.random()
    private fun op() = OperationId.random()
    private val each = id(); private val sku = id(); private val warehouse = id()
    private val drawer = id(); private val cash = id()
    private val time = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    private fun money(minor: Long) = Money(Currency.EGP, minor)
    private fun applied(reply: MutationReply): MutationReply.Recorded = assertIs<MutationReply.Recorded>(reply).also {
        assertTrue(it.committed, "${it.code}: ${it.result.copyBytes().decodeToString()}")
    }
    private suspend fun prepare(f: ResolverNativeFixture) {
        applied(f.execute(LocalStockRelationalCommandsV2.initializeFresh(op(), id(), "synthetic.resolver.opening")))
        val publication = LocalPublishedSkuV1(CompanyScope(f.scope.organizationId, f.scope.companyId), id(), sku, id(), Revision(1), Revision(1),
            LocalNamesV1("Synthetic resolver SKU", "صنف تسوية اختبار"), "RESOLVER-SUPPORT", emptyList(), each, 0,
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
        applied(f.execute(LocalStockRelationalCommandsV2.opening(op(), LocalGoodsReceiptRequest(id(), basis, Revision(0),
            listOf(LocalStockItemVersion(sku, null)), listOf(LocalGoodsReceiptLine(id(), allocation, id(), sku, warehouse, Quantity(each, 12, 0),
                null, null, null, LocalReceiptDisposition.RELEASED, money(48_000))), "synthetic.resolver.opening"))))
        suspend fun cash(value: LocalCashRequestV1) = applied(f.execute(MutationRequest(op(), LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(value))))
        cash(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic original cashier drawer", Currency.EGP))
        cash(LocalCashRequestV1.ConfigureCustodian(f.actor))
        cash(LocalCashRequestV1.Open(this.cash, drawer, Revision(0), Revision(0), BusinessDate(LocalDate(2026, 10, 23)), id(),
            money(100_000), LocalCashEvidenceV1(id(), time)))
    }

    @Test fun independentlyAuthorizedSupervisorRetainsAndAccountsOldUnknownWithoutChangingOriginalCustody(): Unit = runBlocking {
        val f = ResolverNativeFixture(); var owner: BranchUiSession? = null; var supervisor: BranchUiSession? = null
        try {
            f.install(); prepare(f); f.enrollSupervisor()
            val order = id()
            applied(f.execute(LocalCollectionStockCodecV2.command(op(), LocalCollectionStockRequestV2.CreateOrder(order,
                LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER, Currency.EGP, Revision(0),
                    listOf(LocalOrderLineV1(id(), id(), sku, Quantity(each, 2, 0))))))))
            val orderView = LocalOrderReadCodec.decode(assertIs<ReadReply.Data>(f.read(
                LocalOrderReadCodec.request(LocalOrderQueryV1.Order(order)))).payload).orders.single()
            assertEquals(f.actor, orderView.ownerId); assertEquals(money(22_800), orderView.payable)
            val demand = assertIs<LocalCollectionReadResultV1.NormalizedOrderDemand>(LocalCollectionReadCodec.decode(
                assertIs<ReadReply.Data>(f.read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.OrderStockCut(order)))).payload))
            assertEquals(orderView.revision, demand.orderRevision); assertEquals(orderView.quoteRevision, demand.quoteRevision)
            val ownCash = assertIs<LocalCashReadResultV1.OwnCash>(LocalCashReadCodec.decode(assertIs<ReadReply.Data>(
                f.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash))).payload)).session!!
            retailIntent(LocalClientIdentity(f.scope, f.coordinates.deviceId, f.actor), LocalCollectionStockCodecV2.command(op(),
                LocalCollectionStockRequestV2.Begin(order, orderView.revision, cash, ownCash.revision, id(), id())),
                LocalText("Synthetic preparation", "إعداد اختبار"), listOf(LocalRecordValue(
                    LocalText("Order", "الطلب"), LocalText(order.value, order.value))))
            owner = f.uiOwner()
            suspend fun stage(session: BranchUiSession, request: LocalRetailPhysicalRequest): OperationId {
                val prepared = session.retailPhysical.prepare(request)
                val handle = assertIs<LocalRetailPhysicalReply.Prepared>(prepared, "$request: $prepared").value
                return assertIs<LocalRetailPhysicalReply.Staged>(session.retailPhysical.stage(handle)).value.operationId
            }
            val begin = stage(owner, LocalRetailPhysicalRequest.BeginCollection(order))
            val begun = assertIs<LocalRetailPhysicalReply.Progress>(owner.retailPhysical.continueSql(begin))
            assertIs<LocalAcknowledgementResult.Acknowledged>(owner.workflows.acknowledgeRecorded(begin))
            val collection = checkNotNull(begun.value.collectionId)
            val start = stage(owner, LocalRetailPhysicalRequest.Payment(collection))
            val lifetime = LocalRetailPhysicalLifetime()
            val instruction = assertIs<LocalRetailPhysicalReply.Instruction>(owner.retailPhysical.start(start, lifetime)).value
            assertEquals(money(22_800), instruction.display.amount)
            val recording = instruction.display.recordingOperationId
            assertIs<LocalRetailPhysicalCapture.CapturedInMemory>(instruction.capture(LocalRetailObservation.Unknown(LocalRetailEvidence(id(), UtcInstant(Clock.System.now())))))
            withTimeout(90_000L) {
                while (true) {
                    val read = owner.retailPhysical.recover(recording)
                    if (read is LocalRetailPhysicalReply.Progress && read.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING) break
                    delay(100)
                }
            }
            val unknown = stage(owner, LocalRetailPhysicalRequest.Apply(recording, LocalRetailObservationPhase.ORIGINAL))
            assertIs<LocalRetailPhysicalReply.Progress>(owner.retailPhysical.continueSql(unknown))
            assertEquals(money(100_000), f.expectedCash(cash))
            val deniedOperation = op()
            val denied = f.execute(MutationRequest(deniedOperation, LocalRetailSupervisorCodecV1.COMMAND,
                LocalRetailSupervisorCodecV1.encode(LocalRetailSupervisorRequestV1.Review(recording))))
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(denied).reason)
            assertTrue(f.operationCounts(deniedOperation).values.all { it == 0L })
            owner.close(); owner = null; lifetime.deny()
            supervisor = f.uiSupervisor()
            assertNotEquals(f.actor, supervisor.display.actorId)
            val review = stage(supervisor, LocalRetailPhysicalRequest.SupervisorReview(recording))
            assertIs<LocalRetailPhysicalReply.Progress>(supervisor.retailPhysical.continueSql(review))
            val evidence = LocalRetailEvidence(id(), UtcInstant(Clock.System.now()))
            val resolution = stage(supervisor, LocalRetailPhysicalRequest.Resolve(review,
                LocalRetailObservation.CashReceived(evidence, money(25_000), money(2_200))))
            val retained = assertIs<LocalRetailPhysicalReply.Progress>(supervisor.retailPhysical.continueSql(resolution))
            assertEquals(LocalRetailDurableState.SQL_RESOLUTION_PENDING, retained.value.state)
            assertEquals(resolution, assertIs<LocalRetailPhysicalReply.Progress>(supervisor.retailPhysical.recover(resolution)).value.operationId)
            val refreshed = stage(supervisor, LocalRetailPhysicalRequest.SupervisorReview(recording))
            assertIs<LocalRetailPhysicalReply.Progress>(supervisor.retailPhysical.continueSql(refreshed))
            val application = stage(supervisor, LocalRetailPhysicalRequest.SupervisorApply(refreshed, LocalRetailObservationPhase.RESOLUTION))
            val paid = assertIs<LocalRetailPhysicalReply.Progress>(supervisor.retailPhysical.continueSql(application))
            assertEquals(LocalRetailDurableState.FINANCIALLY_APPLIED, paid.value.state)
            repeat(3) { assertEquals(paid.value, assertIs<LocalRetailPhysicalReply.Progress>(supervisor.retailPhysical.continueSql(application)).value) }
            assertEquals(money(122_800), f.expectedCash(cash))
            for (operation in listOf(recording, resolution, application)) assertTrue(f.operationCounts(operation).values.all { it == 1L })
            assertEquals(LocalSessionEndResult.REVOKED, supervisor.logout())
            assertIs<LocalRetailPhysicalReply.Unavailable>(supervisor.retailPhysical.continueSql(resolution))
            supervisor = null
            f.reopen()
            supervisor = f.uiSupervisor()
            assertEquals(LocalRetailDurableState.FINANCIALLY_APPLIED,
                assertIs<LocalRetailPhysicalReply.Progress>(supervisor.retailPhysical.recover(application)).value.state)
            assertEquals(money(122_800), f.expectedCash(cash))
        } finally { owner?.close(); supervisor?.close(); f.close() }
    }
}
