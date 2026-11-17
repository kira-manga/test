package com.ukkera.pos.app.gateway.supporting.resolver

import com.ukkera.pos.app.*
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
import kotlin.test.*
import kotlin.time.Clock

/** SELF-AUTHORED, NOT independent. legacy-exchange-native-supporting-v1.0.5.
 * Genuine provisioner/Room/current employee/native paid recording and signed rights.
 * A real offset's WORK becomes a later supplier Start's native BEFORE ancestor.
 * Software-custody cohost/source-port coverage, NOT TCP/additional-device/platform acceptance. */
class LegacyExchangeNativeSupportingTest {
    private fun id() = DomainId.random()
    private fun op() = OperationId.random()
    private fun now() = UtcInstant(Clock.System.now())
    private fun money(value: Long) = Money(Currency.EGP, value)
    private val each = id(); private val sku = id(); private val warehouse = id()
    private val cash = id(); private val drawer = id(); private val supplier = id()
    private val query = ReadKey("supporting.legacy.exchange_source", 1)
    private val queryBody = StoredBytes(byteArrayOf(1))
    private lateinit var exchange: MutationRequest
    private lateinit var exchangeResult: MutationReply.Recorded
    private var supplierStart: OperationId? = null

    private fun applied(reply: MutationReply): MutationReply.Recorded = assertIs<MutationReply.Recorded>(reply).also {
        assertTrue(it.committed, "${it.code}: ${it.result.copyBytes().decodeToString()}")
    }

    private fun sameOriginal(actual: MutationReply.Recorded) {
        assertTrue(actual.replayed)
        assertEquals(exchangeResult.operationId, actual.operationId)
        assertEquals(exchangeResult.originalActorId, actual.originalActorId)
        assertEquals(exchangeResult.committed, actual.committed)
        assertEquals(exchangeResult.code, actual.code)
        assertEquals(exchangeResult.result, actual.result)
        assertEquals(exchangeResult.authorityEpoch, actual.authorityEpoch)
        assertEquals(exchangeResult.sequence, actual.sequence)
        assertEquals(exchangeResult.recordedAt, actual.recordedAt)
        assertNull(actual.physicalStart)
    }

    private fun sourceRead(registry: LocalBranchCommerceRegistry): MasterReadHandler {
        val field = registry.javaClass.getDeclaredField("financialOriginalSources").also { it.isAccessible = true }
        val services = field.get(registry) as LocalFinancialOriginalSourceServices
        return object : MasterReadHandler {
            override val key: ReadKey get() = query
            override fun normalize(input: StoredBytes): StoredBytes { require(input == queryBody); return input }
            override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
                // Actual installed private service and original/native sources only. No
                // substituted source, historical command view or successful callback.
                val parent = checkNotNull(view.retainedCommand(checkNotNull(supplierStart)))
                services.read(view, parent) { borrowed ->
                    LocalFinancialOriginalSourceAccess.require(borrowed,
                        checkNotNull(borrowed.requireStockRelational().originals.read(exchange.operationId)))
                }
                return object : PreparedRead() {
                    override val requirements = listOf(AuthorizationRequirement(
                        Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), context.scope,
                        ActionBinding(context.actorId, Revision(0), context.semanticDigest, null, "supporting.legacy.exchange"),
                        owner = OwnerConstraint.OwnerPrivate(context.actorId)))
                    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes = queryBody
                }
            }
        }
    }

    private suspend fun publish(f: FinancialResolverNativeFixture,
        proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationOutput {
        val prepared = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.prepare(proposal)).value
        val reviewed = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(
            f.publications.review(prepared.originalActionId)).value
        return assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationOutput>>(f.publications.approve(reviewed,
            "head.one", "synthetic-head-pass-2026".toCharArray())).value
    }

    private suspend fun prepare(f: FinancialResolverNativeFixture) {
        applied(f.execute(LocalStockRelationalCommandsV2.initializeFresh(op(), id(), "synthetic.legacy107.exchange")))
        val at = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
        val company = CompanyScope(f.scope.organizationId, f.scope.companyId)
        val publication = LocalPublishedSkuV1(company, id(), sku, id(), Revision(1), Revision(1),
            LocalNamesV1("Synthetic exchange item", "صنف استبدال اختبار"), "LEGACY-107-EXCHANGE", emptyList(), each, 0,
            LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL, false, true, true, true, emptyList(),
            listOf(LocalPriceV1(id(), Revision(1), null, 0, Quantity(each, 1, 0), effectiveFrom = at,
                unitPrice = UnitPrice.parse(Currency.EGP, "100"), inclusion = TaxInclusion.EXCLUSIVE,
                tax = TaxSnapshot(id(), Revision(1), "SYNTHETIC14", PercentageRate.parse("14")))),
            unitDefinitions = listOf(CatalogUnit(each, Code.exact("EA"), UnitDimension.COUNT, 0)),
            returnPromise = LocalRetailReturnPromiseV1(id(), Revision(1), "SYNTHETIC", "Synthetic 14 day promise",
                "وعد إرجاع اختبار", changeOfMindReceiptWindowDays = 14))
        val signed = publish(f, CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
            publication.publicationId, sku, LocalConfigurationCodec.canonicalPublication(publication))).readNativeCompanySku()
        suspend fun configure(request: LocalConfigurationRequestV1) = applied(f.execute(MutationRequest(op(),
            LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(request))))
        configure(LocalConfigurationRequestV1.InstallCompanySku(signed))
        configure(LocalConfigurationRequestV1.ConfigureWarehouse(null,
            LocalWarehouseV1(warehouse, LocalNamesV1("Synthetic warehouse", "مخزن اختبار"), true)))
        configure(LocalConfigurationRequestV1.ConfigureSalesRoute(null, LocalSalesRouteV1(id(), LocalSalesActivity.CLOTHING,
            LocalSalesChannel.COUNTER, warehouse, true)))
        val basis = id(); val allocation = id()
        configure(LocalConfigurationRequestV1.ApproveReceiving(LocalReceivingApprovalV1(basis, LocalBasisKindV1.OPENING, null, at, null,
            listOf(LocalApprovedStockLineV1(allocation, sku, warehouse, Quantity(each, 12, 0), money(48_000), false)))))
        applied(f.execute(LocalStockRelationalCommandsV2.opening(op(), LocalGoodsReceiptRequest(id(), basis, Revision(0),
            listOf(LocalStockItemVersion(sku, null)), listOf(LocalGoodsReceiptLine(id(), allocation, id(), sku, warehouse,
                Quantity(each, 12, 0), null, null, null, LocalReceiptDisposition.RELEASED, money(48_000))), "synthetic.legacy107.exchange"))))
        val rights = LocalPublishedReturnRightsPolicy(company, id(), id(), Revision(1), "EG",
            "https://cpa.gov.eg/en-us/Consumer-Rights", "2026-11-14", listOf(LocalReturnRightsRule("SYNTHETIC",
                LocalReturnRightsReason.CHANGE_OF_MIND, LocalReturnRightsTreatment.RECEIPT_WINDOW, receiptWindowDays = 14)),
            "Synthetic continuing rights", "حقوق مستمرة اختبار")
        val signedRights = publish(f, rights.toNativeCompanyReturnRightsProposal()).readNativeCompanyReturnRightsPolicy()
        applied(f.execute(LocalReturnRightsActivationCodecV1.activate(op(), LocalActivateReturnRightsV1(Revision(0),
            LocalReturnRightsPublicationCodec.signedPublication(signedRights)))))
        suspend fun cash(request: LocalCashRequestV1) = applied(f.execute(LocalSalesCommands.cash(op(), request)))
        cash(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic exchange drawer", Currency.EGP))
        cash(LocalCashRequestV1.ConfigureCustodian(f.actor))
        cash(LocalCashRequestV1.Open(this.cash, drawer, Revision(0), Revision(0), BusinessDate(LocalDate(2026, 10, 23)), id(),
            money(100_000), LocalCashEvidenceV1(id(), at)))
    }

    private suspend fun collection(f: FinancialResolverNativeFixture, id: DomainId): LocalCollectionViewV1 =
        assertIs<LocalCollectionReadResultV1.Collection>(LocalCollectionReadCodec.decode(assertIs<ReadReply.Data>(
            f.read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.Collection(id)))).payload)).value

    private suspend fun currentCash(f: FinancialResolverNativeFixture): LocalCashSessionViewV1 =
        assertIs<LocalCashReadResultV1.Session>(LocalCashReadCodec.decode(assertIs<ReadReply.Data>(
            f.read(LocalCashReadCodec.request(LocalCashQueryV1.Session(cash)))).payload)).session

    private suspend fun begin(f: FinancialResolverNativeFixture, quantity: Long): Pair<DomainId, DomainId> {
        val order = id(); val portion = id()
        applied(f.execute(LocalCollectionStockCodecV2.command(op(), LocalCollectionStockRequestV2.CreateOrder(order,
            LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER, Currency.EGP, Revision(0),
                listOf(LocalOrderLineV1(id(), portion, sku, Quantity(each, quantity, 0))))))))
        val collection = id()
        applied(f.execute(LocalCollectionStockCodecV2.command(op(), LocalCollectionStockRequestV2.Begin(order, Revision(0),
            cash, currentCash(f).revision, collection, id()))))
        return collection to portion
    }

    private suspend fun finalize(f: FinancialResolverNativeFixture, collectionId: DomainId): DomainId {
        val current = collection(f, collectionId)
        val reservation = assertIs<LocalCollectionReadResultV1.NormalizedReservation>(LocalCollectionReadCodec.decode(
            assertIs<ReadReply.Data>(f.read(LocalCollectionReadCodec.request(LocalCollectionQueryV1.StockCut(collectionId)))).payload))
        val sale = id()
        applied(f.execute(LocalCollectionStockCodecV2.command(op(), LocalCollectionStockRequestV2.Finalize(current.orderId,
            current.orderRevision, collectionId, current.collectionRevision, reservation.revision, sale, id()))))
        return sale
    }

    private suspend fun stage(owner: BranchUiSession, request: LocalRetailPhysicalRequest): OperationId {
        val prepared = owner.retailPhysical.prepare(request)
        val handle = assertIs<LocalRetailPhysicalReply.Prepared>(prepared, "$request: $prepared").value
        return assertIs<LocalRetailPhysicalReply.Staged>(owner.retailPhysical.stage(handle)).value.operationId
    }

    private suspend fun awaitOriginal(owner: BranchUiSession, recording: OperationId, supplier: Boolean = false) {
        withTimeout(90_000L) {
            while (true) {
                val reply = if (supplier) owner.retailPhysical.recoverSupplierCash(recording) else owner.retailPhysical.recover(recording)
                if (reply is LocalRetailPhysicalReply.Progress && reply.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING) return@withTimeout
                delay(100)
            }
        }
    }

    @Test fun paidExchangeWorkRetainsExactOriginalAndSupplierCashAncestryThroughColdHistory(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Explicit legacy107 native supporting executor required", System.getenv("UKKERA_POS_LEGACY107") == "1")
        val f = FinancialResolverNativeFixture(extraOwnerActions = setOf(SecurityAction.RETURN_CLAIM,
            SecurityAction.RETURN_ACCEPT, SecurityAction.CUSTOMER_RECEIPT_RECORD), supportingReadFactory = { listOf(sourceRead(it)) })
        var owner: BranchUiSession? = null
        try {
            System.err.println("Exchange supporting native root=${f.base}")
            f.install(); prepare(f); f.enrollSupervisor()
            val (originalCollection, portion) = begin(f, 2)
            owner = f.uiOwner()
            val payment = stage(owner, LocalRetailPhysicalRequest.Payment(originalCollection))
            val lifetime = LocalRetailPhysicalLifetime()
            val instruction = assertIs<LocalRetailPhysicalReply.Instruction>(owner.retailPhysical.start(payment, lifetime)).value
            assertEquals(money(22_800), instruction.display.amount)
            val paymentRecording = instruction.display.recordingOperationId
            assertIs<LocalRetailPhysicalCapture.CapturedInMemory>(instruction.capture(LocalRetailObservation.CashReceived(
                LocalRetailEvidence(id(), now()), money(25_000), money(2_200))))
            awaitOriginal(owner, paymentRecording)
            val paymentApplication = stage(owner, LocalRetailPhysicalRequest.Apply(paymentRecording, LocalRetailObservationPhase.ORIGINAL))
            assertEquals(LocalRetailDurableState.FINANCIALLY_APPLIED,
                assertIs<LocalRetailPhysicalReply.Progress>(owner.retailPhysical.continueSql(paymentApplication)).value.state)
            assertIs<LocalAcknowledgementResult.Acknowledged>(owner.workflows.acknowledgeRecorded(payment))
            assertIs<LocalAcknowledgementResult.Acknowledged>(owner.workflows.acknowledgeRecorded(paymentApplication))
            assertEquals(money(122_800), f.expectedCash(cash))
            val sale = finalize(f, originalCollection)
            applied(f.execute(LocalRetailCustomerReceiptCodec.command(op(), LocalRetailCustomerReceiptRequestV1(sale, Revision(0), now(),
                "Africa/Cairo", LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER,
                listOf(LocalRetailCustomerReceiptPortionV1(portion, Quantity(each, 2, 0)))))))
            val case = id(); val returnId = id(); val claimedAt = now()
            val facts = LocalReturnCaseFactsV1(LocalReturnClaimFactsV1(sale, portion, Quantity(each, 1, 0), null, claimedAt,
                "synthetic.exchange.return", LocalCashEvidenceV1(id(), claimedAt)), "UNCHANGED", LocalReturnRequestedRemedyV1.REFUND,
                "Africa/Cairo", LocalReturnClaimReasonV1.CHANGE_OF_MIND)
            suspend fun caseWrite(request: LocalReturnCaseRequestV1): MutationRequest {
                val mutation = LocalReturnCaseCommandCodecV1.command(op(), request); applied(f.execute(mutation)); return mutation
            }
            caseWrite(LocalReturnCaseRequestV1.Record(case, facts))
            caseWrite(LocalReturnCaseRequestV1.AppendReceiptEvidence(case, Revision(0), LocalReturnCaseReceiptBindingV1(sale, portion,
                Quantity(each, 1, 0), LocalCashEvidenceV1(id(), now()))))
            val inspectedAt = now()
            val qualified = caseWrite(LocalReturnCaseRequestV1.Qualify(case, Revision(1), Revision(0), ordinaryInspection =
                LocalReturnOrdinaryInspectionV1(LocalReturnInspectionConditionV1.UNCHANGED, LocalCashEvidenceV1(id(), inspectedAt),
                    inspectedAt.canonicalString())))
            caseWrite(LocalReturnCaseRequestV1.Accept(case, Revision(2), qualified.operationId, Revision(0), Revision(0), returnId,
                LocalReturnDispositionV1.INSPECTED_SELLABLE, LocalCashEvidenceV1(id(), now())))
            suspend fun returned(): LocalAcceptedReturnViewV1 = assertIs<LocalReturnReadResultV1.AcceptedReturns>(LocalReturnReadCodec.decode(
                assertIs<ReadReply.Data>(f.read(LocalReturnReadCodec.request(LocalReturnQueryV1.Accepted(sale, case, returnId)))).payload)).returns.single()
            val accepted = returned()
            assertEquals(money(11_400), accepted.originalPayable); assertEquals(money(11_400), accepted.due)
            val originalPayment = accepted.obligations.single().originalPaymentId
            val (replacement, _) = begin(f, 1)
            val replacementBefore = collection(f, replacement)
            assertEquals(money(11_400), replacementBefore.remainingPayable)
            exchange = MutationRequest(op(), LocalExchangeCodec.COMMAND, LocalExchangeCodec.encode(LocalExchangeRequestV1(id(), sale, case,
                returnId, accepted.revision, replacement, replacementBefore.collectionRevision,
                listOf(LocalExchangeSliceV1(id(), originalPayment, money(11_400))), LocalCashEvidenceV1(id(), now()))))
            exchangeResult = applied(f.execute(exchange))
            val offset = assertIs<LocalExchangeResultV1.Applied>(LocalExchangeCodec.result(exchangeResult.result))
            assertEquals(money(11_400), offset.amount); assertEquals(money(0), offset.returnDue)
            assertEquals(Revision(1), offset.returnRevision); assertEquals(Revision(1), offset.collectionRevision)
            assertEquals(money(0), returned().due)
            val afterOffset = collection(f, replacement)
            assertEquals(money(11_400), afterOffset.collected); assertEquals(money(0), afterOffset.remainingPayable)
            System.err.println("Actual exchange committed; cash122800/no payout; root=${f.base}")
            applied(f.execute(MutationRequest(op(), LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(
                LocalConfigurationRequestV1.ConfigureSupplier(null, LocalSupplierV1(supplier, LocalNamesV1("Synthetic supplier", "مورد اختبار"), true))))))
            val supplierStart = stage(owner, LocalRetailPhysicalRequest.SupplierCash(supplier, null, money(3_000), "synthetic.exchange.ancestry"))
            this@LegacyExchangeNativeSupportingTest.supplierStart = supplierStart
            val supplierLifetime = LocalRetailPhysicalLifetime()
            val supplierInstruction = assertIs<LocalRetailPhysicalReply.Instruction>(owner.retailPhysical.start(supplierStart, supplierLifetime)).value
            assertEquals(money(3_000), supplierInstruction.display.amount)
            val supplierRecording = supplierInstruction.display.recordingOperationId
            assertIs<LocalRetailPhysicalCapture.CapturedInMemory>(supplierInstruction.capture(
                LocalRetailObservation.Unknown(LocalRetailEvidence(id(), now()))))
            awaitOriginal(owner, supplierRecording, supplier = true)
            val unknown = stage(owner, LocalRetailPhysicalRequest.SupplierCashApply(supplierRecording, LocalRetailObservationPhase.ORIGINAL))
            assertEquals(LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED,
                assertIs<LocalRetailPhysicalReply.Progress>(owner.retailPhysical.continueSql(unknown)).value.state)
            val startIdentity = f.originalIdentity(supplierStart, LocalSupplierCashProfile.COMMAND)
            val exchangeIdentity = f.originalIdentity(exchange.operationId, LocalExchangeCodec.COMMAND)
            suspend fun verify(finalized: Boolean) {
                assertEquals(queryBody, assertIs<ReadReply.Data>(f.read(ReadRequest(query, queryBody))).payload)
                assertEquals(LocalMasterRpcAccessResult.ALLOWED, f.authorizeLanOriginalAs(f.credentials, startIdentity))
                assertEquals(LocalMasterRpcAccessResult.DENIED, f.authorizeLanOriginalAs(f.credentials,
                    startIdentity.copy(semanticSha256 = sha256("wrong.exchange.ancestor.digest".encodeToByteArray()).value)))
                assertEquals(LocalMasterRpcAccessResult.DENIED,
                    f.authorizeLanOriginalAs(f.supervisorCredentials(explicitDeviceMove = true), startIdentity))
                sameOriginal(assertIs<LookupReply.Found>(f.lookupAs(f.credentials, exchangeIdentity)).result)
                repeat(3) { sameOriginal(applied(f.execute(exchange))) }
                for (operation in listOf(exchange.operationId, supplierStart, paymentRecording, paymentApplication, supplierRecording, unknown))
                    assertTrue(f.operationCounts(operation).values.all { it == 1L })
                val actual = returned()
                assertEquals(money(0), actual.due); assertEquals(money(11_400), actual.refunded)
                val cashView = currentCash(f)
                assertEquals(money(122_800), cashView.expectedCash); assertEquals(Revision(1), cashView.cashLedgerRevision)
                assertEquals(money(25_000), cashView.received); assertEquals(money(2_200), cashView.change)
                assertEquals(money(0), cashView.refunds); assertEquals(money(0), cashView.payOuts)
                assertEquals(money(3_000), cashView.reservedCashOut); assertEquals(1, cashView.pendingAttemptIds.size)
                val stock = LocalStockQueries.decodeQuantities(assertIs<ReadReply.Data>(f.read(LocalStockCommands.quantities(sku))).payload)
                assertEquals(Quantity(each, if (finalized) 9L else 10L, 0), stock.rows.single().onHand)
                assertEquals(Quantity(each, if (finalized) 0L else 1L, 0), stock.rows.single().reserved)
                val cost = LocalStockQueries.decodeCosts(assertIs<ReadReply.Data>(f.read(LocalStockCommands.costs(sku))).payload)
                assertEquals(money(if (finalized) 36_000L else 40_000L), cost.rows.single().carryingValue)
            }
            verify(false)
            owner.close(); owner = null; lifetime.deny(); supplierLifetime.deny()
            f.reopen(); verify(false)
            finalize(f, replacement)
            verify(true)
            f.reopen(); verify(true)
            System.err.println("Exchange native source/financial ancestry/exact originals/two cold boots verified; cash122800/stock9/value36000")
        } finally { owner?.close(); f.close() }
    }
}
