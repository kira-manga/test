package com.ukkera.pos.app.gateway.supporting.resolver

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.*
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.*
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Clock

/** SELF-AUTHORED, NOT independent. Blueprint 5.3 and frozen LAN-A07/D04/D06 support:
 * genuine head-signed recipe/activity/ingredient receipt, actual FOOD cash funding and
 * never-sent whole cancellation precede refund UNKNOWN and another authorized resolver.
 * Real cohost/native/Room/current authority; Linux software OS support only. This is not
 * TCP, foreign-Client custody, whole frozen LAN or Windows/macOS platform qualification. */
class FoodRefundSupervisorRefusalNativeSupportingTest {
    private fun id() = DomainId.random()
    private fun op() = OperationId.random()
    private fun money(value: Long) = Money(Currency.EGP, value)
    private val each = id(); private val ingredient = id(); private val output = id()
    private val warehouse = id(); private val recipeId = id(); private val choice = id(); private val station = id()
    private val drawer = id(); private val cash = id()
    private val time = UtcInstant.parseCanonical("2026-10-23T12:00:00Z")
    private fun applied(reply: MutationReply): MutationReply.Recorded = assertIs<MutationReply.Recorded>(reply, if (reply is MutationReply.Deferred) "Actual deferred reason: ${reply.reason.name}" else "$reply").also {
        assertTrue(it.committed, "${it.code}: ${it.result.copyBytes().decodeToString()}")
    }
    private fun progress(reply: LocalRetailPhysicalReply) = assertIs<LocalRetailPhysicalReply.Progress>(reply, "$reply").value
    private suspend fun stage(s: BranchUiSession, request: LocalRetailPhysicalRequest): OperationId {
        val prepared = s.retailPhysical.prepare(request)
        val p = assertIs<LocalRetailPhysicalReply.Prepared>(prepared, "$request: $prepared").value
        return assertIs<LocalRetailPhysicalReply.Staged>(s.retailPhysical.stage(p)).value.operationId
    }
    private suspend fun prepare(f: FoodRefundResolverNativeFixture) {
        val company = CompanyScope(f.scope.organizationId, f.scope.companyId)
        suspend fun publish(p: CompanyBusinessPublicationProposal): CompanyBusinessPublicationOutput {
            val action = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.prepare(p)).value
            val review = assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationAction>>(f.publications.review(action.originalActionId)).value
            return assertIs<LocalCompanyAuthorityResult.Value<CompanyBusinessPublicationOutput>>(f.publications.approve(review,
                "head.one", "synthetic-head-pass-2026".toCharArray())).value
        }
        suspend fun config(body: LocalConfigurationRequestV1) = applied(f.execute(MutationRequest(op(), LocalConfigurationProfile.COMMAND,
            LocalConfigurationCodec.encode(body))))
        for (sku in listOf(ingredient, output)) {
            val food = sku == output
            val definition = LocalPublishedSkuV1(company, id(), sku, id(), Revision(1), Revision(1),
                LocalNamesV1(if (food) "Synthetic refund meal" else "Synthetic refund ingredient", "صنف اختبار"),
                if (food) "REFUND-MEAL" else "REFUND-INGREDIENT", emptyList(), each, 0, LocalTrackingV1.QUANTITY,
                if (food) LocalFulfillmentV1.MADE_TO_ORDER else LocalFulfillmentV1.RETAIL,
                false, !food, food, true, emptyList(), if (food) listOf(LocalPriceV1(id(), Revision(1), "TAKEAWAY", 0,
                    Quantity(each, 1, 0), effectiveFrom = time, unitPrice = UnitPrice.parse(Currency.EGP, "100"),
                    inclusion = TaxInclusion.EXCLUSIVE, tax = TaxSnapshot(id(), Revision(1), "SYNTHETIC14", PercentageRate.parse("14")))) else emptyList(),
                unitDefinitions = listOf(CatalogUnit(each, Code.exact("EA"), UnitDimension.COUNT, 0)))
            val signed = publish(CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
                definition.publicationId, sku, LocalConfigurationCodec.canonicalPublication(definition))).readNativeCompanySku()
            config(LocalConfigurationRequestV1.InstallCompanySku(signed))
        }
        val activity = LocalPublishedCompanyActivityProfileV1(company, id(), id(), Revision(1), setOf(LocalSalesActivity.RESTAURANT))
        val signedActivity = publish(activity.toNativeCompanyActivityProposal()).readNativeCompanyActivityProfile()
        applied(f.execute(LocalBranchActivityCodecV1.activateInitial(op(), LocalActivateInitialBranchActivityV1(
            LocalCompanyActivityPublicationCodecV1.signedPublication(signedActivity)))))
        config(LocalConfigurationRequestV1.ConfigureWarehouse(null, LocalWarehouseV1(warehouse, LocalNamesV1("Synthetic ingredients", "مكونات اختبار"), true)))
        val basis = id(); val allocation = id()
        config(LocalConfigurationRequestV1.ApproveReceiving(LocalReceivingApprovalV1(basis, LocalBasisKindV1.OPENING, null, time, null,
            listOf(LocalApprovedStockLineV1(allocation, ingredient, warehouse, Quantity(each, 12, 0), money(48_000), false)))))
        applied(f.execute(LocalStockRelationalCommandsV2.initializeFresh(op(), id(), "synthetic.food.refund.opening")))
        applied(f.execute(LocalStockRelationalCommandsV2.opening(op(), LocalGoodsReceiptRequest(id(), basis, Revision(0),
            listOf(LocalStockItemVersion(ingredient, null)), listOf(LocalGoodsReceiptLine(id(), allocation, id(), ingredient, warehouse,
                Quantity(each, 12, 0), null, null, null, LocalReceiptDisposition.RELEASED, money(48_000))), "synthetic.food.refund.opening"))))
        val routeKey = id()
        val recipe = LocalPublishedFoodRecipeV1(company, id(), recipeId, Revision(1), output, Revision(1), Quantity(each, 1, 0),
            Quantity(each, 1, 0), listOf(LocalFoodRecipeChoiceV1(choice, LocalNamesV1("Synthetic original", "الأصل الاختباري"),
                emptyList(), listOf(LocalFoodIngredientDefinitionV1(id(), ingredient, Revision(1), Quantity(each, 1, 0))), listOf(routeKey), output)), true)
        val signedRecipe = publish(CompanyBusinessPublicationProposal(company, CompanyBusinessPublicationKind.FOOD_RECIPE_V1,
            recipe.publicationId, recipeId, LocalFoodConfigurationCodecV1.canonicalPublication(recipe))).readNativeCompanyFoodRecipe()
        for (body in listOf(LocalFoodConfigurationRequestV1.Station(null, LocalFoodStationDefinitionV1(station,
            LocalNamesV1("Synthetic preparation", "تحضير اختباري"), FoodStationDeliveryMode.DISPLAY, true)),
            LocalFoodConfigurationRequestV1.InstallRecipe(signedRecipe), LocalFoodConfigurationRequestV1.Route(null,
                LocalFoodRecipeRouteV1(id(), recipeId, Revision(1), LocalSalesActivity.RESTAURANT, LocalSalesChannel.TAKEAWAY,
                    warehouse, listOf(LocalFoodRouteTargetV1(routeKey, station)), true)))) {
            applied(f.execute(LocalFoodConfigurationCodecV1.command(op(), body)))
        }
        for (body in listOf(LocalCashRequestV1.ConfigureDrawer(drawer, "Synthetic original FOOD drawer", Currency.EGP),
            LocalCashRequestV1.ConfigureCustodian(f.actor), LocalCashRequestV1.Open(cash, drawer, Revision(0), Revision(0),
                BusinessDate(LocalDate(2026,10,23)), id(), money(100_000), LocalCashEvidenceV1(id(), time))))
            applied(f.execute(MutationRequest(op(), LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(body))))
    }

    @Test fun supervisorResolvesActualCancelledFoodRefundUnknownWithoutChangingOriginalCashOrRepeatingPayout(): Unit = runBlocking {
        val f = FoodRefundResolverNativeFixture(station); var owner: BranchUiSession? = null; var supervisor: BranchUiSession? = null
        try {
            f.install(); prepare(f); f.enrollSupervisor()
            val menu = LocalFoodMenuCodecV1.result(assertIs<ReadReply.Data>(f.read(LocalFoodMenuCodecV1.read(LocalFoodMenuQueryV1(recipeId)))).payload)
            assertEquals(output, menu.recipes.single().outputSkuId)
            val order = id()
            applied(f.execute(LocalFoodStockCodecV2.command(op(), LocalFoodStockRequestV2.ActivityProtected(LocalFoodDraftCreateV1(order,
                LocalSalesActivity.RESTAURANT, LocalFoodServiceV1(LocalSalesChannel.TAKEAWAY, "Synthetic unsent refund"))))))
            suspend fun orderView() = LocalFoodReadCodecV1.result(assertIs<ReadReply.Data>(f.read(
                LocalFoodReadCodecV1.read(LocalFoodQueryV1.Order(order)))).payload).detail!!
            applied(f.execute(LocalFoodStockCodecV2.command(op(), LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(LocalFoodRequestV1.Add(
                order, orderView().summary.revision, checkNotNull(menu.catalogRevision), listOf(LocalFoodPortionSelectionV1(id(), output,
                    recipeId, Revision(1), choice, emptyList(), Quantity(each, 1, 0))))))))
            assertEquals(money(11_400), orderView().originalQuotedTotal)
            val ownCash = assertIs<LocalCashReadResultV1.OwnCash>(LocalCashReadCodec.decode(assertIs<ReadReply.Data>(f.read(
                LocalCashReadCodec.request(LocalCashQueryV1.OwnCash))).payload)).session!!
            val collection = id()
            applied(f.execute(LocalFoodStockCodecV2.command(op(), LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.BeginCollection(
                order, orderView().summary.revision, collection, cash, ownCash.revision)))))
            owner = f.uiOwner()
            val paymentStart = stage(owner, LocalRetailPhysicalRequest.Payment(collection, subject = LocalPaymentSubject.FOOD))
            val paymentLifetime = LocalRetailPhysicalLifetime()
            val payment = assertIs<LocalRetailPhysicalReply.Instruction>(owner.retailPhysical.start(paymentStart, paymentLifetime)).value
            assertEquals(money(11_400), payment.display.amount)
            val paymentRecording = payment.display.recordingOperationId
            assertIs<LocalRetailPhysicalCapture.CapturedInMemory>(payment.capture(LocalRetailObservation.CashReceived(
                LocalRetailEvidence(id(), UtcInstant(Clock.System.now())), money(11_400), money(0))))
            withTimeout(90_000L) { while (true) {
                val value = owner.retailPhysical.recoverFood(paymentRecording)
                if (value is LocalRetailPhysicalReply.Progress && value.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING) break
                delay(100)
            } }
            val paymentApply = stage(owner, LocalRetailPhysicalRequest.Apply(paymentRecording, LocalRetailObservationPhase.ORIGINAL, LocalPaymentSubject.FOOD))
            assertEquals(LocalRetailDurableState.FINANCIALLY_APPLIED, progress(owner.retailPhysical.continueSql(paymentApply)).state)
            assertEquals(money(111_400), f.expectedCash(cash))
            assertIs<LocalAcknowledgementResult.Acknowledged>(owner.workflows.acknowledgeRecorded(paymentStart))
            assertIs<LocalAcknowledgementResult.Acknowledged>(owner.workflows.acknowledgeRecorded(paymentApply))
            val paid = assertIs<LocalFoodFinancialReadResultV1.Collection>(LocalFoodFinancialReadCodecV1.result(assertIs<ReadReply.Data>(f.read(
                LocalFoodFinancialReadCodecV1.read(LocalFoodFinancialQueryV1.Collection(collection)))).payload)).value
            assertEquals(money(11_400), paid.collected)
            val originalPayment = paid.attempts.single().attemptId; val refund = id()
            applied(f.execute(LocalFoodStockCodecV2.command(op(), LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.CancelWholeUnsent(
                collection, paid.collectionDomainRevision, paid.orderStorageRevision, refund, id())))))
            val refundStart = stage(owner, LocalRetailPhysicalRequest.FoodRefund(refund, originalPayment))
            val lifetime = LocalRetailPhysicalLifetime()
            val instruction = assertIs<LocalRetailPhysicalReply.Instruction>(owner.retailPhysical.start(refundStart, lifetime)).value
            assertEquals(money(11_400), instruction.display.amount)
            val recording = instruction.display.recordingOperationId; val fixedResolution = instruction.display.resolutionOperationId
            assertIs<LocalRetailPhysicalCapture.CapturedInMemory>(instruction.capture(LocalRetailObservation.Unknown(
                LocalRetailEvidence(id(), UtcInstant(Clock.System.now())))))
            owner.close(); owner = null; lifetime.deny(); paymentLifetime.deny()
            f.login(); owner = f.uiOwner()
            withTimeout(90_000L) { while (true) {
                val value = owner.retailPhysical.recoverFoodRefund(recording)
                if (value is LocalRetailPhysicalReply.Progress && value.value.state == LocalRetailDurableState.SQL_ORIGINAL_PENDING) break
                delay(100)
            } }
            val unknown = stage(owner, LocalRetailPhysicalRequest.FoodRefundApply(recording, LocalRetailObservationPhase.ORIGINAL))
            assertEquals(LocalRetailDurableState.OBSERVATION_APPLIED_UNRESOLVED, progress(owner.retailPhysical.continueSql(unknown)).state)
            assertEquals(money(111_400), f.expectedCash(cash))
            val deniedId = op()
            val denied = f.execute(LocalFoodRefundSupervisorCodec.command(deniedId, LocalFoodRefundSupervisorRequest.Review(recording)))
            assertEquals(DeferredReason.AUTHORIZATION_DENIED, assertIs<MutationReply.Deferred>(denied).reason)
            assertTrue(f.operationCounts(deniedId).values.all { it == 0L })
            owner.close(); owner = null; supervisor = f.uiSupervisor()
            assertContains(supervisor.display.allowedAreas, com.ukkera.pos.app.navigation.AppArea.ORDERS)
            assertIs<LocalRetailPhysicalReply.Unavailable>(supervisor.retailPhysical.prepare(
                LocalRetailPhysicalRequest.FoodRefundApply(recording, LocalRetailObservationPhase.ORIGINAL)))
            val reviewId = stage(supervisor, LocalRetailPhysicalRequest.FoodRefundSupervisorReview(recording))
            val reviewed = progress(supervisor.retailPhysical.continueSql(reviewId))
            assertEquals(LocalRetailDurableState.SUPERVISOR_REVIEW_RECORDED, reviewed.state); assertEquals(f.actor, reviewed.originalActorId)
            val resolution = stage(supervisor, LocalRetailPhysicalRequest.FoodRefundSupervisorResolve(reviewId,
                LocalRetailObservation.CashPaid(LocalRetailEvidence(id(), UtcInstant(Clock.System.now())), amount = money(11_400))))
            assertEquals(fixedResolution, resolution)
            assertEquals(LocalRetailDurableState.SQL_RESOLUTION_PENDING, progress(supervisor.retailPhysical.continueSql(resolution)).state)
            assertEquals(resolution, progress(supervisor.retailPhysical.recoverFoodRefund(resolution)).operationId)
            val freshId = stage(supervisor, LocalRetailPhysicalRequest.FoodRefundSupervisorReview(recording))
            val fresh = progress(supervisor.retailPhysical.continueSql(freshId))
            assertTrue(fresh.resolutionRecorded); assertEquals(f.actor, fresh.originalActorId); assertEquals(f.supervisorActor, fresh.recordingActorId)
            val application = stage(supervisor, LocalRetailPhysicalRequest.FoodRefundSupervisorApply(freshId, LocalRetailObservationPhase.RESOLUTION))
            val refunded = progress(supervisor.retailPhysical.continueSql(application))
            assertEquals(LocalRetailDurableState.FINANCIALLY_APPLIED, refunded.state)
            repeat(3) { assertEquals(refunded, progress(supervisor.retailPhysical.continueSql(application))) }
            assertEquals(money(100_000), f.expectedCash(cash))
            for (operation in listOf(recording, resolution, application)) assertTrue(f.operationCounts(operation).values.all { it == 1L })
            // Fresh SQL-only application ID is also a real durable no-effect original.
            // SELF-AUTHORED support for qa/pos/designs/lan-master-client-v1/cases.json
            // LAN-A07/D04/D06 and blueprint5.3; no frozen/independent assertion is changed.
            val afterPaidReview = stage(supervisor, LocalRetailPhysicalRequest.FoodRefundSupervisorReview(recording))
            progress(supervisor.retailPhysical.continueSql(afterPaidReview))
            // Existing UI dedup keeps the paid original. A separate actual protected
            // Master request is required to exercise a NEW no-effect refusal original.
            val duplicateUi = assertIs<LocalRetailPhysicalReply.Staged>(supervisor.retailPhysical.prepare(
                LocalRetailPhysicalRequest.FoodRefundSupervisorApply(afterPaidReview, LocalRetailObservationPhase.RESOLUTION)))
            assertEquals(application, duplicateUi.value.operationId)
            val refusalCredentials = f.supervisorCredentials()
            val freshRequest = LocalFoodRefundSupervisorCodec.command(op(), LocalFoodRefundSupervisorRequest.Apply(resolution, LocalFoodRefundFactPhase.RESOLUTION))
            val refused = freshRequest.operationId
            val refusal = assertIs<MutationReply.Recorded>(f.executeAs(refusalCredentials, freshRequest))
            assertFalse(refusal.committed)
            assertEquals("food.refund.already_applied", refusal.code)
            val refusedResult = assertIs<LocalFoodRefundResult.AlreadyApplied>(LocalFoodRefundCodec.result(refusal.result))
            assertEquals(application, refusedResult.applicationOperationId)
            assertEquals(LocalFoodRefundFactPhase.RESOLUTION, refusedResult.phase)
            repeat(3) {
                val replay = assertIs<MutationReply.Recorded>(f.executeAs(refusalCredentials, freshRequest))
                assertTrue(replay.replayed)
                assertEquals(StoredKernelResultV1.from(refusal), StoredKernelResultV1.from(replay))
            }
            val refusedIdentity = com.ukkera.pos.recovery.OperationIdentity(f.scope.branchId, refused,
                f.supervisorActor, 1, semanticDigest(f.scope, f.supervisorActor, freshRequest.command, freshRequest.payload).value)
            assertTrue(f.operationCounts(refused).values.all { it == 1L })
            assertEquals(money(100_000), f.expectedCash(cash))
            assertEquals(LocalSessionEndResult.REVOKED, supervisor.logout())
            assertIs<LocalRetailPhysicalReply.Unavailable>(supervisor.retailPhysical.continueSql(resolution)); supervisor = null
            f.reopen(); supervisor = f.uiSupervisor()
            assertEquals(LocalRetailDurableState.FINANCIALLY_APPLIED, progress(supervisor.retailPhysical.recoverFoodRefund(application)).state)
            assertEquals(money(100_000), f.expectedCash(cash))
            val coldRefusal = assertIs<LookupReply.Found>(f.lookupAs(f.supervisorCredentials(), refusedIdentity)).result
            assertTrue(coldRefusal.replayed)
            assertEquals(StoredKernelResultV1.from(refusal), StoredKernelResultV1.from(coldRefusal))
            assertTrue(f.operationCounts(refused).values.all { it == 1L })
        } finally { owner?.close(); supervisor?.close(); f.close() }
    }
}
