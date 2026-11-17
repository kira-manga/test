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
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.test.*

/** SELF-AUTHORED, NOT independent. Frozen legacy-return-case-native-supporting-v1.0.0.
 * Actual native cohost provisioner/Room/current employee/normalized stock and signed original
 * promise/rights. Real receipt and case writes are then whole-qualified by the installed
 * financial source services on a protected read, warm and existing-only cold. Zero total
 * changes no cash and supplies no pretend physical recording. Not TCP, additional-device,
 * natural paid cash-WORK ancestry or native-platform/owner acceptance. */
class LegacyReturnCaseSourceNativeSupportingTest {
    private fun id() = DomainId.random()
    private fun op() = OperationId.random()
    private fun now() = UtcInstant(Clock.System.now())
    private fun money(minor: Long) = Money(Currency.EGP, minor)
    private val each = id(); private val sku = id(); private val warehouse = id()
    private val drawer = id(); private val cash = id(); private val supplier = id()
    private val query = ReadKey("supporting.legacy.return_case_sources", 1)
    private val queryBody = StoredBytes(byteArrayOf(1))
    private val selected = mutableListOf<OperationId>()
    private val originals = mutableMapOf<OperationId, MutationReply.Recorded>()
    private var root: OperationId? = null

    private fun applied(reply: MutationReply): MutationReply.Recorded =
        assertIs<MutationReply.Recorded>(reply).also {
            assertTrue(it.committed, "${it.code}: ${it.result.copyBytes().decodeToString()}")
            originals.putIfAbsent(it.operationId, it)
        }

    private fun sameOriginal(expected: MutationReply.Recorded, actual: MutationReply.Recorded) {
        assertTrue(actual.replayed)
        assertEquals(expected.operationId, actual.operationId)
        assertEquals(expected.originalActorId, actual.originalActorId)
        assertEquals(expected.committed, actual.committed)
        assertEquals(expected.code, actual.code)
        assertEquals(expected.result, actual.result)
        assertEquals(expected.authorityEpoch, actual.authorityEpoch)
        assertEquals(expected.sequence, actual.sequence)
        assertEquals(expected.recordedAt, actual.recordedAt)
        assertNull(actual.physicalStart)
    }

    private fun sourceRead(registry: LocalBranchCommerceRegistry): MasterReadHandler {
        // Test composition only: borrow THIS installed private service, not a substituted
        // verifier, capability, fake view or source. All reads remain actual native loans.
        val field = registry.javaClass.getDeclaredField("financialOriginalSources").also { it.isAccessible = true }
        val services = field.get(registry) as LocalFinancialOriginalSourceServices
        return object : MasterReadHandler {
            override val key: ReadKey get() = query
            override fun normalize(input: StoredBytes): StoredBytes { require(input == queryBody); return input }
            override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
                val parent = checkNotNull(view.retainedCommand(checkNotNull(root)))
                val count = services.read(view, parent) { borrowed ->
                    for (operation in selected) {
                        val original = checkNotNull(borrowed.requireStockRelational().originals.read(operation))
                        LocalFinancialOriginalSourceAccess.require(borrowed, original)
                    }
                    selected.size
                }
                return object : PreparedRead() {
                    override val requirements = listOf(AuthorizationRequirement(
                        Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM), context.scope,
                        ActionBinding(context.actorId, Revision(0), context.semanticDigest, null,
                            "supporting.legacy.return_case"), owner = OwnerConstraint.OwnerPrivate(context.actorId)))
                    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes =
                        StoredBytes(count.toString().encodeToByteArray())
                }
            }
        }
    }

    private suspend fun publish(f: FinancialResolverNativeFixture, proposal: CompanyBusinessPublicationProposal): CompanyBusinessPublicationOutput {
        val action = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.prepare(proposal)).value
        val reviewed = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(
            f.publications.review(action.originalActionId)).value
        return assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationOutput>>(f.publications.approve(reviewed,
            "head.one", "synthetic-head-pass-2026".toCharArray())).value
    }

    private suspend fun prepare(f: FinancialResolverNativeFixture) {
        applied(f.execute(LocalStockRelationalCommandsV2.initializeFresh(op(), id(), "synthetic.legacy107.case.opening")))
        val time = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
        val company = CompanyScope(f.scope.organizationId, f.scope.companyId)
        val publication = LocalPublishedSkuV1(company, id(), sku, id(), Revision(1), Revision(1),
            LocalNamesV1("Synthetic return source", "صنف إرجاع اختبار"), "LEGACY-107-CASE", emptyList(), each, 0,
            LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL, false, true, true, true, emptyList(),
            listOf(LocalPriceV1(id(), Revision(1), null, 0, Quantity(each, 1, 0), effectiveFrom = time,
                unitPrice = UnitPrice.parse(Currency.EGP, "0"), inclusion = TaxInclusion.EXCLUSIVE,
                tax = TaxSnapshot(id(), Revision(1), "SYNTHETIC_ZERO", PercentageRate.parse("0")))),
            unitDefinitions = listOf(CatalogUnit(each, Code.exact("EA"), UnitDimension.COUNT, 0)),
            returnPromise = LocalRetailReturnPromiseV1(id(), Revision(1), "SYNTHETIC", "Synthetic 14 day promise",
                "وعد إرجاع اختبار", changeOfMindReceiptWindowDays = 14))
        val signed = publish(f, CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
            publication.publicationId, sku, LocalConfigurationCodec.canonicalPublication(publication))).readNativeCompanySku()
        suspend fun configure(value: LocalConfigurationRequestV1) = applied(f.execute(MutationRequest(op(),
            LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(value))))
        configure(LocalConfigurationRequestV1.InstallCompanySku(signed))
        configure(LocalConfigurationRequestV1.ConfigureWarehouse(null,
            LocalWarehouseV1(warehouse, LocalNamesV1("Synthetic warehouse", "مخزن اختبار"), true)))
        configure(LocalConfigurationRequestV1.ConfigureSalesRoute(null, LocalSalesRouteV1(id(), LocalSalesActivity.CLOTHING,
            LocalSalesChannel.COUNTER, warehouse, true)))
        val basis = id(); val allocation = id()
        configure(LocalConfigurationRequestV1.ApproveReceiving(LocalReceivingApprovalV1(basis, LocalBasisKindV1.OPENING, null, time, null,
            listOf(LocalApprovedStockLineV1(allocation, sku, warehouse, Quantity(each, 12, 0), money(48_000), false)))))
        applied(f.execute(LocalStockRelationalCommandsV2.opening(op(), LocalGoodsReceiptRequest(id(), basis, Revision(0),
            listOf(LocalStockItemVersion(sku, null)), listOf(LocalGoodsReceiptLine(id(), allocation, id(), sku, warehouse,
                Quantity(each, 12, 0), null, null, null, LocalReceiptDisposition.RELEASED, money(48_000))),
            "synthetic.legacy107.case.opening"))))
        val rights = LocalPublishedReturnRightsPolicy(company, id(), id(), Revision(1), "EG",
            "https://cpa.gov.eg/en-us/Consumer-Rights", "2026-11-14", listOf(LocalReturnRightsRule("SYNTHETIC",
                LocalReturnRightsReason.CHANGE_OF_MIND, LocalReturnRightsTreatment.RECEIPT_WINDOW, receiptWindowDays = 14)),
            "Synthetic continuing rights", "حقوق مستمرة اختبار")
        val rightsOutput = publish(f, rights.toNativeCompanyReturnRightsProposal()).readNativeCompanyReturnRightsPolicy()
        val rightsOperation = op()
        applied(f.execute(LocalReturnRightsActivationCodecV1.activate(rightsOperation, LocalActivateReturnRightsV1(Revision(0),
            LocalReturnRightsPublicationCodec.signedPublication(rightsOutput)))))
        selected += rightsOperation
        suspend fun cash(value: LocalCashRequestV1) = applied(f.execute(MutationRequest(op(),
            LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(value))))
        cash(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic return drawer", Currency.EGP))
        cash(LocalCashRequestV1.ConfigureCustodian(f.actor))
        cash(LocalCashRequestV1.Open(this.cash, drawer, Revision(0), Revision(0), BusinessDate(LocalDate(2026, 10, 23)),
            id(), money(100_000), LocalCashEvidenceV1(id(), time)))
    }

    @Test fun signedReceiptAndEveryCaseSubtypeRetainWholeSourcesAcrossColdHistory(): Unit = runBlocking { run() }

    /** Separate SELF-AUTHORED legacy-return-binding-native-supporting-v1.0.0. */
    @Test fun signedLegacyClaimBindingRetainsItsExactWholeOriginalAcrossColdHistory(): Unit = runBlocking {
        run(legacyBinding = true)
    }

    /** Separate SELF-AUTHORED legacy-return-acceptance-native-supporting-v1.0.1. */
    @Test fun legacyAcceptancePreservesCurrentRemainingRightsAndOriginalColdRecovery(): Unit = runBlocking {
        run(legacyBinding = true, legacyAcceptance = true)
    }

    /** Separate SELF-AUTHORED legacy-return-multiple-acceptance-native-supporting-v1.0.0. */
    @Test fun secondLegacyAcceptanceConsumesOnlyRemainingRightsAcrossColdHistory(): Unit = runBlocking {
        run(legacyBinding = true, legacyAcceptance = true, legacyAcceptanceTwice = true)
    }

    private suspend fun run(legacyBinding: Boolean = false, legacyAcceptance: Boolean = false,
        legacyAcceptanceTwice: Boolean = false) {
        check(!legacyAcceptance || legacyBinding)
        check(!legacyAcceptanceTwice || legacyAcceptance)

        org.junit.Assume.assumeTrue("Explicit legacy107 native supporting executor required",
            System.getenv("UKKERA_POS_LEGACY107") == "1")
        val f = FinancialResolverNativeFixture(extraOwnerActions = setOf(SecurityAction.RETURN_CLAIM, SecurityAction.CUSTOMER_RECEIPT_RECORD) +
            (if (legacyBinding && !legacyAcceptance) emptySet() else setOf(SecurityAction.RETURN_ACCEPT)),
            supportingReadFactory = { listOf(sourceRead(it)) })
        try {
            f.install(); prepare(f)
            val order = id(); val collection = id(); val sale = id(); val portion = id()
            applied(f.execute(LocalCollectionStockCodecV2.command(op(), LocalCollectionStockRequestV2.CreateOrder(order,
                LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER, Currency.EGP, Revision(0),
                    listOf(LocalOrderLineV1(id(), portion, sku, Quantity(each, 2, 0))))))))
            applied(f.execute(LocalCollectionStockCodecV2.command(op(), LocalCollectionStockRequestV2.Begin(order, Revision(0),
                cash, Revision(0), collection, id()))))
            applied(f.execute(LocalCollectionStockCodecV2.command(op(), LocalCollectionStockRequestV2.Finalize(order, Revision(1),
                collection, Revision(0), Revision(1), sale, id()))))
            val receipt = op()
            applied(f.execute(LocalRetailCustomerReceiptCodec.command(receipt, LocalRetailCustomerReceiptRequestV1(sale, Revision(0), now(),
                "Africa/Cairo", LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER,
                listOf(LocalRetailCustomerReceiptPortionV1(portion, Quantity(each, 2, 0)))))))
            selected += receipt
            val accepted: MutationRequest
            val acceptedRequests = mutableListOf<LocalReturnRequestV1.Accept>()
            if (legacyBinding) {
                val claim = id(); val claimedAt = now()
                val intake = MutationRequest(op(), LocalSalesStoreProfile.RETURN_COMMAND,
                    LocalReturnCommandCodec.encode(LocalReturnRequestV1.RecordClaim(claim,
                        LocalReturnClaimFactsV1(sale, portion, Quantity(each, 1, 0), null, claimedAt,
                            "synthetic.ordinary.return", LocalCashEvidenceV1(id(), claimedAt)))))
                assertEquals(LocalSalesAction.RETURN_CLAIM_PENDING,
                    assertIs<LocalSalesResultV1.Applied>(LocalSalesResultCodec.decode(applied(f.execute(intake)).result)).action)
                selected += intake.operationId
                val binding = MutationRequest(op(), LocalSalesStoreProfile.RETURN_COMMAND,
                    LocalReturnCommandCodec.encode(LocalReturnRequestV1.BindClaim(claim, Revision(0), Revision(0))))
                val bound = assertIs<LocalSalesResultV1.Applied>(LocalSalesResultCodec.decode(applied(f.execute(binding)).result))
                assertEquals(LocalSalesAction.RETURN_CLAIMED, bound.action)
                assertEquals(Revision(1), bound.revisions.single { it.kind == LocalSalesStoreProfile.RETURN_INTAKE_KIND }.revision)
                assertEquals(Revision(0), bound.revisions.single { it.kind == LocalSalesStoreProfile.RETURN_CLAIM_KIND }.revision)
                selected += binding.operationId
                accepted = if (legacyAcceptance) {
                    val mutation = MutationRequest(op(), LocalSalesStoreProfile.RETURN_COMMAND,
                        LocalReturnCommandCodec.encode(LocalReturnRequestV1.Accept(sale, claim, Revision(0), Revision(0),
                            id(), LocalReturnDispositionV1.INSPECTED_SELLABLE, LocalCashEvidenceV1(id(), now()))))
                    val value = assertIs<LocalSalesResultV1.Applied>(LocalSalesResultCodec.decode(applied(f.execute(mutation)).result))
                    assertEquals(LocalSalesAction.RETURN_ACCEPTED, value.action)
                    assertEquals(Revision(1), value.revisions.single { it.kind == LocalSalesStoreProfile.ENTITLEMENT_KIND }.revision)
                    assertEquals(Revision(1), value.revisions.single { it.kind == LocalSalesStoreProfile.RETURN_CLAIM_KIND }.revision)
                    assertEquals(Revision(0), value.revisions.single { it.kind == LocalSalesStoreProfile.RETURN_KIND }.revision)
                    selected += mutation.operationId
                    acceptedRequests += assertIs<LocalReturnRequestV1.Accept>(LocalReturnCommandCodec.decode(mutation.payload))
                    assertTrue(f.operationCounts(mutation.operationId).values.all { it == 1L })
                    System.err.println("Legacy acceptance durably committed; original cardinalities1; native root=${f.base}")
                    if (legacyAcceptanceTwice) {
                        val secondClaim = id(); val secondClaimedAt = now()
                        val secondIntake = MutationRequest(op(), LocalSalesStoreProfile.RETURN_COMMAND,
                            LocalReturnCommandCodec.encode(LocalReturnRequestV1.RecordClaim(secondClaim,
                                LocalReturnClaimFactsV1(sale, portion, Quantity(each, 1, 0), null, secondClaimedAt,
                                    "synthetic.ordinary.return", LocalCashEvidenceV1(id(), secondClaimedAt)))))
                        assertEquals(LocalSalesAction.RETURN_CLAIM_PENDING, assertIs<LocalSalesResultV1.Applied>(
                            LocalSalesResultCodec.decode(applied(f.execute(secondIntake)).result)).action)
                        selected += secondIntake.operationId
                        val secondBinding = MutationRequest(op(), LocalSalesStoreProfile.RETURN_COMMAND,
                            LocalReturnCommandCodec.encode(LocalReturnRequestV1.BindClaim(secondClaim, Revision(0), Revision(1))))
                        assertEquals(LocalSalesAction.RETURN_CLAIMED, assertIs<LocalSalesResultV1.Applied>(
                            LocalSalesResultCodec.decode(applied(f.execute(secondBinding)).result)).action)
                        selected += secondBinding.operationId
                        val secondRequest = LocalReturnRequestV1.Accept(sale, secondClaim, Revision(1), Revision(0),
                            id(), LocalReturnDispositionV1.INSPECTED_SELLABLE, LocalCashEvidenceV1(id(), now()))
                        val secondMutation = MutationRequest(op(), LocalSalesStoreProfile.RETURN_COMMAND,
                            LocalReturnCommandCodec.encode(secondRequest))
                        val secondValue = assertIs<LocalSalesResultV1.Applied>(LocalSalesResultCodec.decode(applied(f.execute(secondMutation)).result))
                        assertEquals(LocalSalesAction.RETURN_ACCEPTED, secondValue.action)
                        assertEquals(Revision(2), secondValue.revisions.single { it.kind == LocalSalesStoreProfile.ENTITLEMENT_KIND }.revision)
                        assertEquals(Revision(1), secondValue.revisions.single { it.kind == LocalSalesStoreProfile.RETURN_CLAIM_KIND }.revision)
                        assertEquals(Revision(0), secondValue.revisions.single { it.kind == LocalSalesStoreProfile.RETURN_KIND }.revision)
                        selected += secondMutation.operationId
                        acceptedRequests += secondRequest
                        secondMutation
                    } else mutation
                } else binding
            } else {
                val case = id(); val recordedAt = now()
                val facts = LocalReturnCaseFactsV1(LocalReturnClaimFactsV1(sale, portion, Quantity(each, 1, 0), null,
                    recordedAt, "synthetic.return", LocalCashEvidenceV1(id(), recordedAt)), "UNCHANGED",
                    LocalReturnRequestedRemedyV1.REFUND, "Africa/Cairo", LocalReturnClaimReasonV1.CHANGE_OF_MIND)
                suspend fun caseWrite(request: LocalReturnCaseRequestV1, expectedRevision: Long,
                    expectedAction: LocalReturnCaseActionV1): MutationRequest {
                    val mutation = LocalReturnCaseCommandCodecV1.command(op(), request)
                    val reply = applied(f.execute(mutation))
                    val result = assertIs<LocalReturnCaseResultV1.Applied>(LocalReturnCaseCommandCodecV1.decodeResult(reply.result))
                    assertEquals(Revision(expectedRevision), result.caseRevision)
                    assertEquals(expectedAction, result.action)
                    selected += mutation.operationId
                    return mutation
                }
                caseWrite(LocalReturnCaseRequestV1.Record(case, facts), 0, LocalReturnCaseActionV1.RECORDED)
                caseWrite(LocalReturnCaseRequestV1.AppendReceiptEvidence(case, Revision(0), LocalReturnCaseReceiptBindingV1(sale, portion,
                    Quantity(each, 1, 0), LocalCashEvidenceV1(id(), now()))), 1, LocalReturnCaseActionV1.EVIDENCE_APPENDED)
                val inspectedAt = now()
                val qualified = caseWrite(LocalReturnCaseRequestV1.Qualify(case, Revision(1), Revision(0),
                    ordinaryInspection = LocalReturnOrdinaryInspectionV1(LocalReturnInspectionConditionV1.UNCHANGED,
                        LocalCashEvidenceV1(id(), inspectedAt), inspectedAt.canonicalString())), 2, LocalReturnCaseActionV1.BOUND)
                accepted = caseWrite(LocalReturnCaseRequestV1.Accept(case, Revision(2), qualified.operationId, Revision(0), Revision(0),
                    id(), LocalReturnDispositionV1.INSPECTED_SELLABLE, LocalCashEvidenceV1(id(), now())), 3, LocalReturnCaseActionV1.ACCEPTED)
            }
            root = op()
            applied(f.execute(MutationRequest(checkNotNull(root), LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(
                LocalConfigurationRequestV1.ConfigureSupplier(null, LocalSupplierV1(supplier, LocalNamesV1("Synthetic supplier", "مورد اختبار"), true))))))
            val identities = selected.mapIndexed { index, operation ->
                f.originalIdentity(operation, when (index) {
                    0 -> LocalReturnRightsActivationProfileV1.ACTIVATE
                    1 -> LocalRetailCustomerReceiptCodec.COMMAND
                    else -> if (legacyBinding) LocalSalesStoreProfile.RETURN_COMMAND else LocalReturnCaseProfileV1.COMMAND
                })
            }
            suspend fun verify() {
                if (legacyAcceptance) {
                    System.err.println("Legacy acceptance public remaining-rights read entered")
                    val receipt = assertIs<LocalReturnReadResultV1.Receipt>(LocalReturnReadCodec.decode(
                        assertIs<ReadReply.Data>(f.read(LocalReturnReadCodec.request(LocalReturnQueryV1.Receipt(sale)))).payload))
                    assertEquals(Revision(if (legacyAcceptanceTwice) 2L else 1L), receipt.entitlementRevision)
                    assertEquals(Quantity(each, 2, 0), receipt.lines.single().originalQuantity)
                    assertEquals(Quantity(each, if (legacyAcceptanceTwice) 0L else 1L, 0), receipt.lines.single().remainingQuantity)
                    for (request in acceptedRequests) {
                        val returns = assertIs<LocalReturnReadResultV1.AcceptedReturns>(LocalReturnReadCodec.decode(
                            assertIs<ReadReply.Data>(f.read(LocalReturnReadCodec.request(LocalReturnQueryV1.Accepted(
                                sale, request.claimId, request.returnId)))).payload))
                        val current = returns.returns.single()
                        assertNull(returns.nextAfter)
                        assertEquals(request.returnId, current.returnId)
                        assertEquals(sale, current.saleId)
                        assertEquals(request.claimId, current.claimId)
                        assertEquals(Revision(0), current.revision)
                        assertEquals(Quantity(each, 1, 0), current.quantity)
                        assertEquals(money(0), current.originalPayable)
                        assertEquals(money(0), current.refunded)
                        assertEquals(money(0), current.due)
                        assertTrue(current.attempts.isEmpty() && current.obligations.isEmpty())
                    }
                    System.err.println("Legacy acceptance public remaining-rights verified")
                }
                assertEquals(if (legacyAcceptanceTwice) "8" else if (legacyAcceptance) "5" else if (legacyBinding) "4" else "6", assertIs<ReadReply.Data>(f.read(ReadRequest(query, queryBody))).payload.copyBytes().decodeToString())
                assertEquals(money(100_000), f.expectedCash(cash))
                for ((operation, identity) in selected.zip(identities)) {
                    assertTrue(f.operationCounts(operation).values.all { it == 1L })
                    sameOriginal(checkNotNull(originals[operation]),
                        assertIs<LookupReply.Found>(f.lookupAs(f.credentials, identity)).result)
                }
                val quantities = LocalStockQueries.decodeQuantities(assertIs<ReadReply.Data>(f.read(LocalStockCommands.quantities(sku))).payload)
                assertEquals(Quantity(each, 10, 0), quantities.rows.single().onHand)
                assertEquals(Quantity(each, 0, 0), quantities.rows.single().reserved)
                val costs = LocalStockQueries.decodeCosts(assertIs<ReadReply.Data>(f.read(LocalStockCommands.costs(sku))).payload)
                assertEquals(money(40_000), costs.rows.single().carryingValue)
                repeat(3) { sameOriginal(checkNotNull(originals[accepted.operationId]), applied(f.execute(accepted))) }
            }
            verify(); f.reopen(); verify()
            System.err.println("Legacy return native sources warm/cold verified; originals=${selected.size}; cash100000/stock10/value40000")
        } finally { f.close() }
    }
}
