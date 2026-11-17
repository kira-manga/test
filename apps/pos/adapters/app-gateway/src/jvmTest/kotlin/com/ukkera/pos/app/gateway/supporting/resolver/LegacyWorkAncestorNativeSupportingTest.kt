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
import kotlin.time.Clock
import kotlin.test.*

/** SELF-AUTHORED, NOT independent. Frozen v1 #107 boundary support (blueprint5.3, frozen
 * LAN-A07/D04/D06 retained-result access). Genuine native cohost history: signed SKU,
 * warehouse/route/opening approval, LEGACY stock.opening.post/1, cash drawer/custodian,
 * optionally ONE legacy sales.order.write/1 Create (the actual WORK writer later read by
 * cash Open), cash Open, supplier configuration and a real supplier-cash Start.
 *
 * Frozen expectations are the #107 REQUIRED outcomes, not current behavior: the exact
 * retained Start is ALLOWED for its original actor, a wrong digest is DENIED, and the same-OS
 * cold reopen is OPERATIONAL with the exact Start still ALLOWED. The control variant omits
 * only the legacy order. Observations are collected before the frozen assertions so one
 * run records every outcome. Opt-in (environment UKKERA_POS_LEGACY107=1): until #107 is
 * implemented this is expected to FAIL and is preserved as evidence, not a regression gate.
 * Linux software native cohost only; not TCP, additional-device or platform qualification. */
class LegacyWorkAncestorNativeSupportingTest {
    private fun id() = DomainId.random()
    private fun op() = OperationId.random()
    private val each = id(); private val sku = id(); private val warehouse = id()
    private val drawer = id(); private val cash = id(); private val supplier = id()
    private val time = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    private fun money(minor: Long) = Money(Currency.EGP, minor)
    private fun applied(reply: MutationReply): MutationReply.Recorded = assertIs<MutationReply.Recorded>(reply).also {
        assertTrue(it.committed, "${it.code}: ${it.result.copyBytes().decodeToString()}")
    }
    private fun enabled(): Boolean = System.getenv("UKKERA_POS_LEGACY107") == "1"

    @Test fun legacyOrderWorkWriterBeforeCashOpenSupportsRetainedSupplierStart(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Explicit legacy107 native supporting executor required", enabled())
        run(legacyOrder = true)
    }

    @Test fun controlWithoutLegacyOrderSupportsRetainedSupplierStart(): Unit = runBlocking {
        org.junit.Assume.assumeTrue("Explicit legacy107 native supporting executor required", enabled())
        run(legacyOrder = false)
    }

    /** SELF-AUTHORED v1.0.0 additional order sequence, frozen separately before execution.
     * Existing v1 mixed/control cases and their expectations remain unchanged. */
    @Test fun everyLegacyOrderSubtypeClosesWorkAncestryBeforeCashOpen(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(enabled())
        run(legacyOrder = true, allOrderSubtypes = true)
    }

    /** SELF-AUTHORED v1.0.0 collection extension; exact zero-total stock/native
     * effects and retained financial ancestry, not a substitute for physical payment. */
    @Test fun legacyReservationAndZeroFinalizationSupportRetainedSupplierOriginal(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(enabled())
        run(legacyOrder = true, legacyZeroCollection = true)
    }

    /** SELF-AUTHORED, NOT independent; frozen legacy-return-intake-ancestry-v1.0.0.
     * Original pending legal intake writes actual cash WORK before Open. */
    @Test fun legacyReturnIntakeWorkBeforeCashOpenSupportsRetainedSupplierStart(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(enabled())
        run(legacyOrder = false, legacyReturnIntake = true)
    }

    private suspend fun prepareLegacyCatalogAndStock(f: FinancialResolverNativeFixture, zeroPrice: Boolean = false): LocalSignedSkuV1 {
        val publication = LocalPublishedSkuV1(CompanyScope(f.scope.organizationId, f.scope.companyId), id(), sku, id(), Revision(1), Revision(1),
            LocalNamesV1("Synthetic legacy107 SKU", "صنف اختبار قديم"), "LEGACY-107", emptyList(), each, 0,
            LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL, false, true, true, true, emptyList(),
            listOf(LocalPriceV1(id(), Revision(1), null, 0, Quantity(each, 1, 0), effectiveFrom = time,
                unitPrice = UnitPrice.parse(Currency.EGP, if (zeroPrice) "0" else "100"), inclusion = TaxInclusion.EXCLUSIVE,
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
        // LEGACY stock.opening.post/1, deliberately not the normalized relational profile.
        val opening = applied(f.execute(LocalStockCommands.opening(op(), LocalGoodsReceiptRequest(id(), basis, Revision(0),
            listOf(LocalStockItemVersion(sku, null)), listOf(LocalGoodsReceiptLine(id(), allocation, id(), sku, warehouse, Quantity(each, 12, 0),
                null, null, null, LocalReceiptDisposition.RELEASED, money(48_000))), "synthetic.legacy107.opening"))))
        assertFalse(opening.replayed)
        return signed
    }

    /** SELF-AUTHORED, NOT independent; legacy source graph support v1.0.0.
     * Three real orders bracket the selected second original; its BEFORE points/pages
     * must retain the first writer and exclude the third. A later unactivated publication
     * is excluded by the first install's original signed membership. The same assertions
     * run warm/cold, and an escaped source must refuse after its actual callback closes.
     * This qualifies DATA selection only, not whole financial ancestor business proof. */
    @Test fun nativeBeforeGraphSelectsPastSourcesAndOriginalPublicationMembership(): Unit = runBlocking { beforeGraph() }

    /** SELF-AUTHORED, NOT independent; frozen native-current-legacy-writer-data-v1.0.0. */
    @Test fun nativeCurrentWriterSelectsExactLatestLiteralAndOriginalCut(): Unit = runBlocking {
        beforeGraph(checkCurrent = true)
    }

    private suspend fun beforeGraph(checkCurrent: Boolean = false) {
        org.junit.Assume.assumeTrue("Explicit legacy107 native supporting executor required", enabled())
        val firstOperation = op(); val selectedOperation = op(); val futureOperation = op()
        val firstOrder = id(); val secondOrder = id(); val thirdOrder = id(); val unknownOrder = id()
        var escaped: RuntimeHistoricalLegacySources? = null
        var escapedCurrent: RuntimeHistoricalLegacySources? = null
        var currentOperation = futureOperation
        var expectedBefore = listOf(firstOrder, secondOrder)
        var expectedGeneration = 4L
        lateinit var f: FinancialResolverNativeFixture
        val query = ReadKey("supporting.legacy.before_graph", 1)
        val payload = StoredBytes(byteArrayOf(1))
        val probe = object : MasterReadHandler {
            override val key: ReadKey get() = query
            override fun normalize(input: StoredBytes): StoredBytes { require(input == payload); return input }
            override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
                val values = view.withLegacyBusinessSources(selectedOperation) { graph ->
                    val source = graph.source(selectedOperation) ?: return@withLegacyBusinessSources mapOf("source" to "missing")
                    escaped = source
                    val work = source.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, f.actor))
                    val claim = source.claimSource(LocalSalesStoreProfile.unique("order", firstOrder))
                    val orders = source.aggregateKeys(LocalSalesStoreProfile.ORDER_KIND, null, 1)
                    val claims = source.claimKeys(LocalSalesStoreProfile.UNIQUE_NAMESPACE, "order/", null, 1)
                    val skuSource = source.aggregateSource(LocalConfigurationProfile.key(LocalConfigurationProfile.SKU, sku))
                    val installation = (skuSource?.original as? RetainedOriginalV2.Legacy)?.evidence?.operationId
                        ?.let { graph.source(it) }
                    val proof = installation?.publicationObservation()
                    val contents = proof?.let { f.publications.originalCompletedContents(it) }
                    mapOf("source" to "present", "actor" to graph.root.evidence.originalActorId.value,
                        "workWriter" to ((work?.original as? RetainedOriginalV2.Legacy)?.evidence?.operationId?.value ?: "missing"),
                        "claimOwner" to (claim?.ownerId?.value ?: "missing"),
                        "claimWriter" to ((claim?.original as? RetainedOriginalV2.Legacy)?.evidence?.operationId?.value ?: "missing"),
                        "orderPage" to orders.keys.joinToString(",") { it.id.value }, "orderNext" to (orders.nextAfter?.value ?: "none"),
                        "claimPage" to claims.entries.joinToString(",") { it.ownerId.value }, "claimNext" to (claims.nextAfter ?: "none"),
                        "futureSourceAbsent" to (graph.source(futureOperation) == null).toString(),
                        "futureOriginalAbsent" to (source.earlierOriginal(futureOperation) == null).toString(),
                        "unknownPointAbsent" to (source.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, unknownOrder)) == null).toString(),
                        "publicationCount" to (contents?.size?.toString() ?: "missing"),
                        "publicationIds" to (contents?.joinToString(",") { it.proposal.publicationId.value } ?: "missing"))
                } ?: mapOf("source" to "graph-unavailable")
                val currentValues = if (!checkCurrent) emptyMap() else {
                    val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, f.actor)
                    val selected = view.withCurrentLegacyBusinessSources(key) { current ->
                        val original = current.graph.root.evidence
                        val before = checkNotNull(current.graph.source(original.operationId))
                        escapedCurrent = before
                        val page = before.aggregateKeys(LocalSalesStoreProfile.ORDER_KIND, null, 100)
                        val actual = checkNotNull(view.aggregate(key))
                        mapOf("currentWriter" to original.operationId.value,
                            "currentGeneration" to current.row.revision.value.toString(),
                            "currentMatches" to (current.row.key == actual.key && current.row.revision == actual.revision &&
                                current.row.schema == actual.schema && current.row.payload == actual.payload &&
                                current.row.digest == actual.digest).toString(),
                            "currentBeforeOrders" to page.keys.joinToString(",") { it.id.value },
                            "currentBeforeNext" to (page.nextAfter?.value ?: "none"),
                            "currentSelfAbsent" to (before.earlierOriginal(original.operationId) == null).toString())
                    } ?: mapOf("currentWriter" to "unavailable")
                    val missing = view.withCurrentLegacyBusinessSources(
                        LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, unknownOrder)) { "unexpected" }
                    selected + ("currentMissingUnavailable" to (missing == null).toString())
                }
                val owner = values["actor"]?.let(::DomainId) ?: context.actorId
                return object : PreparedRead() {
                    override val requirements = listOf(AuthorizationRequirement(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM),
                        context.scope, ActionBinding(context.actorId, Revision(0), context.semanticDigest, null,
                            "supporting.legacy.before"), owner = OwnerConstraint.OwnerPrivate(owner)))
                    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes =
                        StoredBytes((values + currentValues).entries.joinToString("\n") { "${it.key}=${it.value}" }.encodeToByteArray())
                }
            }
        }
        f = FinancialResolverNativeFixture(listOf(probe))
        try {
            f.install()
            val signed = prepareLegacyCatalogAndStock(f)
            applied(f.execute(MutationRequest(op(), LocalSalesStoreProfile.CASH_COMMAND,
                LocalCashCommandCodec.encode(LocalCashRequestV1.ConfigureCustodian(f.actor)))))
            suspend fun create(operation: OperationId, order: DomainId) = applied(f.execute(MutationRequest(operation,
                LocalSalesStoreProfile.ORDER_COMMAND, LocalOrderCommandCodec.encode(LocalOrderRequestV1.Create(order,
                    LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER, Currency.EGP, Revision(0),
                        listOf(LocalOrderLineV1(id(), id(), sku, Quantity(each, 2, 0)))))))))
            create(firstOperation, firstOrder); create(selectedOperation, secondOrder)
            val later = signed.definition.copy(publicationId = id(), skuId = id(), productId = id(), code = "LEGACY-107-LATER")
            val proposal = CompanyBusinessPublicationProposal(later.company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
                later.publicationId, later.skuId, LocalConfigurationCodec.canonicalPublication(later))
            val action = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.prepare(proposal)).value
            val reviewed = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.review(action.originalActionId)).value
            assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationOutput>>(f.publications.approve(reviewed,
                "head.one", "synthetic-head-pass-2026".toCharArray()))
            assertIs<LocalCompanyAuthorityResult.Value<List<CompanyBusinessPublicationReference>>>(f.publications.retained())
            assertEquals(2, f.publications.currentCompletedSnapshot()?.completedPublications?.size)
            create(futureOperation, thirdOrder)
            suspend fun observe(): Map<String, String> {
                val reply = f.read(ReadRequest(query, payload))
                val result = assertIs<ReadReply.Data>(reply, "Legacy source probe: " +
                    if (reply is ReadReply.Unavailable) reply.reason.name else "DATA")
                return result.payload.copyBytes().decodeToString().lineSequence().associate { line ->
                    val index = line.indexOf('='); line.substring(0, index) to line.substring(index + 1)
                }
            }
            fun verify(values: Map<String, String>) {
                assertEquals("present", values["source"], "$values")
                assertEquals(firstOperation.value, values["workWriter"])
                assertEquals(firstOrder.value, values["claimOwner"]); assertEquals(firstOperation.value, values["claimWriter"])
                assertEquals(firstOrder.value, values["orderPage"]); assertEquals("none", values["orderNext"])
                assertEquals(firstOrder.value, values["claimPage"]); assertEquals("none", values["claimNext"])
                assertEquals("true", values["futureSourceAbsent"]); assertEquals("true", values["futureOriginalAbsent"])
                assertEquals("true", values["unknownPointAbsent"])
                assertEquals("1", values["publicationCount"]); assertEquals(signed.definition.publicationId.value, values["publicationIds"])
                if (checkCurrent) {
                    assertEquals(currentOperation.value, values["currentWriter"])
                    assertEquals(expectedGeneration.toString(), values["currentGeneration"])
                    assertEquals("true", values["currentMatches"])
                    assertEquals(expectedBefore.map { it.value }.sorted().joinToString(","), values["currentBeforeOrders"])
                    assertEquals("none", values["currentBeforeNext"])
                    assertEquals("true", values["currentSelfAbsent"])
                    assertEquals("true", values["currentMissingUnavailable"])
                }
            }
            verify(observe())
            val closedSource = assertNotNull(escaped)
            var refused = false
            try { closedSource.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, f.actor)) }
            catch (_: IllegalStateException) { refused = true }
            assertTrue(refused, "Source query must refuse after its actual callback closed")
            if (checkCurrent) {
                val closed = checkNotNull(escapedCurrent)
                var currentRefused = false
                try { closed.aggregateSource(LocalSalesStoreProfile.key(LocalSalesStoreProfile.WORK_KIND, f.actor)) }
                catch (_: IllegalStateException) { currentRefused = true }
                assertTrue(currentRefused, "Current writer source must refuse after its actual callback closed")
                val fourthOrder = id(); currentOperation = op()
                create(currentOperation, fourthOrder)
                expectedBefore = listOf(firstOrder, secondOrder, thirdOrder)
                expectedGeneration = 5L
                verify(observe())
            }
            f.reopen()
            verify(observe())
        } finally { f.close() }
    }

    private suspend fun run(legacyOrder: Boolean, allOrderSubtypes: Boolean = false, legacyZeroCollection: Boolean = false,
        legacyReturnIntake: Boolean = false) {
        val f = FinancialResolverNativeFixture(
            extraOwnerActions = (if (allOrderSubtypes) setOf(SecurityAction.ORDER_HANDOFF) else emptySet()) +
                (if (legacyReturnIntake) setOf(SecurityAction.RETURN_CLAIM) else emptySet()),
            extraSupervisorActions = if (allOrderSubtypes) setOf(SecurityAction.ORDER_HANDOFF) else emptySet())
        var owner: BranchUiSession? = null
        val observed = linkedMapOf<String, String>()
        val orderOperations = mutableListOf<OperationId>()
        var zeroOrder: DomainId? = null
        suspend fun verifyZeroStock() {
            val quantities = LocalStockQueries.decodeQuantities(assertIs<ReadReply.Data>(f.read(LocalStockCommands.quantities(sku))).payload)
            assertNull(quantities.nextAfter)
            val position = quantities.rows.single()
            assertEquals(Quantity(each, 10, 0), position.onHand)
            assertEquals(Quantity(each, 0, 0), position.reserved)
            assertEquals(Quantity(each, 10, 0), position.available)
            val costs = LocalStockQueries.decodeCosts(assertIs<ReadReply.Data>(f.read(LocalStockCommands.costs(sku))).payload)
            assertNull(costs.nextAfter)
            assertEquals(Quantity(each, 10, 0), costs.rows.single().quantity)
            assertEquals(money(40_000), costs.rows.single().carryingValue)
        }
        try {
            f.install()
            prepareLegacyCatalogAndStock(f, zeroPrice = legacyZeroCollection)
            suspend fun cash(request: LocalCashRequestV1) = applied(f.execute(MutationRequest(op(), LocalSalesStoreProfile.CASH_COMMAND,
                LocalCashCommandCodec.encode(request))))
            cash(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic legacy107 drawer", Currency.EGP))
            cash(LocalCashRequestV1.ConfigureCustodian(f.actor))
            if (legacyOrder) {
                // Actual installed legacy writer: catalogue quote + legacy stock allocation +
                // WORK(orderIds += order). Cash Open below reads this WORK row as BEFORE.
                val order = id()
                val selection = LocalOrderSelectionV1(LocalSalesActivity.CLOTHING, LocalSalesChannel.COUNTER,
                    Currency.EGP, Revision(0), listOf(LocalOrderLineV1(id(), id(), sku, Quantity(each, 2, 0))))
                suspend fun orderWrite(value: LocalOrderRequestV1, credentials: LocalGatewayCredentials = f.credentials): MutationReply.Recorded {
                    val operation = op(); orderOperations += operation
                    return applied(f.executeAs(credentials, MutationRequest(operation, LocalSalesStoreProfile.ORDER_COMMAND,
                        LocalOrderCommandCodec.encode(value))))
                }
                val created = orderWrite(LocalOrderRequestV1.Create(order, selection))
                observed["legacyOrder"] = created.code
                if (legacyZeroCollection) zeroOrder = order
                if (allOrderSubtypes) {
                    f.enrollSupervisor()
                    cash(LocalCashRequestV1.ConfigureCustodian(f.supervisorActor))
                    orderWrite(LocalOrderRequestV1.ReplaceUnlocked(order, Revision(0), selection))
                    val firstOffer = id()
                    orderWrite(LocalOrderRequestV1.OfferHandoff(order, Revision(1), firstOffer, f.supervisorActor,
                        LocalCashEvidenceV1(id(), UtcInstant(Clock.System.now()))))
                    orderWrite(LocalOrderRequestV1.CancelHandoff(order, Revision(2), firstOffer))
                    val secondOffer = id()
                    orderWrite(LocalOrderRequestV1.OfferHandoff(order, Revision(3), secondOffer, f.supervisorActor,
                        LocalCashEvidenceV1(id(), UtcInstant(Clock.System.now()))))
                    val accepted = orderWrite(LocalOrderRequestV1.AcceptHandoff(order, Revision(4), secondOffer,
                        LocalCashEvidenceV1(id(), UtcInstant(Clock.System.now()))), f.supervisorCredentials())
                    val result = assertIs<LocalSalesResultV1.Applied>(LocalSalesResultCodec.decode(accepted.result))
                    assertEquals(LocalSalesAction.HANDOFF_ACCEPTED, result.action)
                    assertEquals(Revision(5), result.revisions.single { it.kind == LocalSalesStoreProfile.ORDER_KIND }.revision)
                    observed["orderSubtypes"] = "CREATE,REPLACE,OFFER,CANCEL,OFFER,ACCEPT"
                }
            }
            if (legacyReturnIntake) {
                val operation = op(); orderOperations += operation
                val time = UtcInstant(Clock.System.now())
                val result = applied(f.execute(MutationRequest(operation, LocalSalesStoreProfile.RETURN_COMMAND,
                    LocalReturnCommandCodec.encode(LocalReturnRequestV1.RecordClaim(id(), LocalReturnClaimFactsV1(
                        null, null, null, null, time, "synthetic.return.intake", LocalCashEvidenceV1(id(), time)))))))
                assertEquals(LocalSalesAction.RETURN_CLAIM_PENDING,
                    assertIs<LocalSalesResultV1.Applied>(LocalSalesResultCodec.decode(result.result)).action)
                observed["legacyReturnIntake"] = result.code
            }
            cash(LocalCashRequestV1.Open(cash, drawer, Revision(0), Revision(0),
                BusinessDate(LocalDate(2026, 11, 17)), id(), money(100_000), LocalCashEvidenceV1(id(), UtcInstant(Clock.System.now()))))
            if (legacyZeroCollection) {
                val order = checkNotNull(zeroOrder)
                val collection = id(); val reservation = id()
                val begin = op(); orderOperations += begin
                val locked = applied(f.execute(MutationRequest(begin, LocalSalesStoreProfile.COLLECTION_COMMAND,
                    LocalCollectionCommandCodec.encode(LocalCollectionRequestV1.Begin(order, Revision(0), cash, Revision(0),
                        collection, reservation, listOf(LocalSalesStockRevisionV1(sku, Revision(1))))))))
                assertEquals(LocalSalesAction.COLLECTION_LOCKED,
                    assertIs<LocalSalesResultV1.Applied>(LocalSalesResultCodec.decode(locked.result)).action)
                val finish = op(); orderOperations += finish
                val finalized = applied(f.execute(MutationRequest(finish, LocalSalesStoreProfile.COLLECTION_COMMAND,
                    LocalCollectionCommandCodec.encode(LocalCollectionRequestV1.Finalize(collection, Revision(0), Revision(1),
                        Revision(0), Revision(1), listOf(LocalSalesStockRevisionV1(sku, Revision(2))), id(), id())))))
                val sale = assertIs<LocalSalesResultV1.Applied>(LocalSalesResultCodec.decode(finalized.result))
                assertEquals(LocalSalesAction.SALE_FINALIZED, sale.action)
                assertEquals(money(0), sale.amount)
                assertEquals(listOf(LocalSalesStockRevisionV1(sku, Revision(3))), sale.stockItems)
                observed["legacyCollection"] = "BEGIN,ZERO_FINALIZE"
                verifyZeroStock()
            }
            committedConfig(f)
            suspend fun stage(session: BranchUiSession, request: LocalRetailPhysicalRequest): OperationId {
                val prepared = session.retailPhysical.prepare(request)
                val handle = assertIs<LocalRetailPhysicalReply.Prepared>(prepared, "$request: $prepared").value
                return assertIs<LocalRetailPhysicalReply.Staged>(session.retailPhysical.stage(handle)).value.operationId
            }
            owner = f.uiOwner()
            val start = stage(owner, LocalRetailPhysicalRequest.SupplierCash(supplier, null, money(3_000), "synthetic.legacy107.advance"))
            val lifetime = LocalRetailPhysicalLifetime()
            val instruction = assertIs<LocalRetailPhysicalReply.Instruction>(owner.retailPhysical.start(start, lifetime)).value
            val recording = instruction.display.recordingOperationId
            assertIs<LocalRetailPhysicalCapture.CapturedInMemory>(instruction.capture(LocalRetailObservation.Unknown(
                LocalRetailEvidence(id(), UtcInstant(Clock.System.now())))))
            owner.close(); owner = null; lifetime.deny()
            f.login(); owner = f.uiOwner()
            withTimeout(90_000L) {
                while (true) {
                    val read = owner.retailPhysical.recoverSupplierCash(recording)
                    if (read is LocalRetailPhysicalReply.Progress && read.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING) break
                    delay(100)
                }
            }
            val startIdentity = f.originalIdentity(start, LocalSupplierCashProfile.COMMAND)
            observed["warmStart"] = f.authorizeLanOriginalAs(f.credentials, startIdentity).name
            observed["warmWrongDigest"] = f.authorizeLanOriginalAs(f.credentials,
                startIdentity.copy(semanticSha256 = sha256("wrong.legacy107.start".encodeToByteArray()).value)).name
            if (allOrderSubtypes) observed["foreignStart"] = f.authorizeLanOriginalAs(f.supervisorCredentials(), startIdentity).name
            if (allOrderSubtypes || legacyZeroCollection || legacyReturnIntake)
                for (operation in orderOperations) assertTrue(f.operationCounts(operation).values.all { it == 1L })
            assertEquals(money(100_000), f.expectedCash(cash))
            owner.close(); owner = null
            observed["cold"] = try { f.reopen(); "OPERATIONAL" }
                catch (failure: AssertionError) { "NOT_OPERATIONAL: ${failure.message?.take(300)}" }
            if (observed["cold"] == "OPERATIONAL") {
                observed["coldStart"] = f.authorizeLanOriginalAs(f.credentials, startIdentity).name
                if (allOrderSubtypes || legacyZeroCollection || legacyReturnIntake) {
                    for (operation in orderOperations) assertTrue(f.operationCounts(operation).values.all { it == 1L })
                    assertEquals(money(100_000), f.expectedCash(cash))
                    if (legacyZeroCollection) verifyZeroStock()
                }
            }
        } finally {
            System.err.println("LEGACY107 observations legacyOrder=$legacyOrder: $observed")
            owner?.close(); f.close()
        }
        // Frozen #107 REQUIRED outcomes; asserted only after every observation is recorded.
        if (legacyOrder) assertEquals("committed", observed["legacyOrder"])
        if (legacyReturnIntake) assertEquals("committed", observed["legacyReturnIntake"])
        if (legacyZeroCollection) assertEquals("BEGIN,ZERO_FINALIZE", observed["legacyCollection"])
        if (allOrderSubtypes) {
            assertEquals("CREATE,REPLACE,OFFER,CANCEL,OFFER,ACCEPT", observed["orderSubtypes"])
            assertEquals("DENIED", observed["foreignStart"])
        }
        assertEquals("ALLOWED", observed["warmStart"], "retained Start: $observed")
        assertEquals("DENIED", observed["warmWrongDigest"], "wrong digest: $observed")
        assertEquals("OPERATIONAL", observed["cold"], "cold: $observed")
        assertEquals("ALLOWED", observed["coldStart"], "cold Start: $observed")
    }

    private suspend fun committedConfig(f: FinancialResolverNativeFixture) {
        applied(f.execute(MutationRequest(op(), LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(
            LocalConfigurationRequestV1.ConfigureSupplier(null, LocalSupplierV1(supplier, LocalNamesV1("Synthetic supplier", "مورد اختبار"), true))))))
    }
}
