package com.ukkera.pos.android.independent.nativefoodcashcoldv1

import com.ukkera.pos.android.independent.nativefixture.nativeFixtureSecret
import com.ukkera.pos.android.independent.nativefixture.nativeFixtureValue
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import org.json.JSONArray
import org.json.JSONObject

/** Actual native company publications and protected production setup commands only.
 * No alternate pricing, recipe, reservation, CASH or business-storage implementation. */
internal object NativeCafeCashPreparation {
    suspend fun prepare(client: NativeCafeCashClient, plan: NativeCafeCashPlan,
        evidence: NativeCafeCashEvidence, headSecret: CharArray): CafeCashSeed {
        val c = client.original.material.coordinates
        val requested = plan.sources(c.scope.company())
        evidence.freezeSources(requested)
        val published = publish(client, requested, plan, evidence, headSecret)
        suspend fun configuration(symbol: String, suffix: Int, input: LocalConfigurationRequestV1, id: DomainId) {
            val reply = client.dispatch(symbol, MutationRequest(plan.operation(suffix), LocalConfigurationProfile.COMMAND,
                LocalConfigurationCodec.encode(input)))
            val result = LocalConfigurationCodec.decodeResult(reply.result)
            check(result.code == "configuration.applied" && result.id == id && result.revision != null)
        }
        plan.roles.forEachIndexed { index, role -> configuration("INSTALL_SKU_$role", 100 + index,
            LocalConfigurationRequestV1.InstallCompanySku(published.skus.getValue(role)), plan.skuId(role)) }
        val activityReply = client.dispatch("ACTIVATE_CAFE_ONLY", LocalBranchActivityCodecV1.activateInitial(plan.operation(110),
            LocalActivateInitialBranchActivityV1(LocalCompanyActivityPublicationCodecV1.signedPublication(published.activity))))
        val activity = LocalBranchActivityCodecV1.decodeResult(activityReply.result)
        check(activity.code == LocalBranchActivityProfileV1.ACTIVATED && activity.applied.definition == requested.activity &&
            activity.applied.scope == c.scope && activity.applied.actorId == c.firstActorId &&
            activity.applied.operationId == plan.operation(110) && activity.applied.recordedAt == activityReply.recordedAt &&
            activity.applied.activities == setOf(LocalSalesActivity.CAFE))
        configuration("CONFIGURE_WAREHOUSE", 111, LocalConfigurationRequestV1.ConfigureWarehouse(null, plan.warehouse), plan.warehouseId)
        configuration("APPROVE_EXACT_OPENING", 112, LocalConfigurationRequestV1.ApproveReceiving(plan.openingApproval), plan.openingApprovalId)
        val empty = LocalStockQueries.decodeItems(client.read(LocalStockCommands.items()).payload)
        check(empty.items.isEmpty() && empty.nextAfter == null && !LocalStockRelationalQueriesV2.normalized(
            client.read(LocalStockRelationalQueriesV2.profile()).payload))
        val initializeRequest = LocalStockRelationalCommandsV2.initializeFresh(plan.operation(113), plan.initializationId, plan.reasonCode)
        val initializeReply = client.dispatch("INITIALIZE_FRESH_STOCK", initializeRequest)
        val initialized = LocalStockRelationalCommandsV2.decodeInitializationResult(initializeRequest.payload, initializeReply.result)
        check(initialized.initializationId == plan.initializationId && initialized.freshEmpty &&
            LocalStockRelationalQueriesV2.normalized(client.read(LocalStockRelationalQueriesV2.profile()).payload))
        val items = plan.stocks.map { row ->
            val absent = LocalStockQueries.decodeQuantities(client.read(LocalStockCommands.quantities(row.skuId)).payload)
            check(absent.skuId == row.skuId && absent.rows.isEmpty() && absent.nextAfter == null &&
                absent.itemStorageRevision == null && absent.unitId == null && absent.quantityScale == null)
            LocalStockItemVersion(row.skuId, absent.itemStorageRevision)
        }
        val basis = cafeConfiguration(client, LocalConfigurationProfile.BASIS, plan.openingApprovalId)
        check(basis.items.size == 1 && basis.nextAfter == null &&
            (basis.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value == plan.openingApproval)
        val basisRevision = basis.items.single().revision // Actual basis DOMAIN revision, not storage generation.
        val receipt = LocalGoodsReceiptRequest(plan.openingReceiptId, plan.openingApprovalId, basisRevision, items,
            plan.stocks.map { row -> LocalGoodsReceiptLine(row.receiptLineId, row.allocationId, row.sourceId, row.skuId,
                plan.warehouseId, plan.quantity(row.role, row.onHand), conversion = null, lotId = null, expiresAt = null,
                disposition = LocalReceiptDisposition.RELEASED, originalValue = row.carryingValue) }, plan.reasonCode)
        val openingRequest = LocalStockRelationalCommandsV2.opening(plan.operation(114), receipt)
        val openingReply = client.dispatch("OPENING_STOCK_WITH_FROZEN_VALUE", openingRequest)
        val opening = LocalStockRelationalCommandsV2.decodeResult(openingRequest.payload, openingReply.result)
        check(opening.kind == LocalStockResultKind.OPENED && opening.documentId == plan.openingReceiptId && opening.failure == null &&
            opening.items.map { it.skuId }.toSet() == plan.stocks.map { it.skuId }.toSet() && opening.items.all { it.storageRevision != null })
        requireCafeOpening(client, plan, basisRevision, openingReply.recordedAt, CafeReadCut())
        plan.stocks.forEach { requireCafeStock(client, plan, it, reserved = false, cut = CafeReadCut()) }
        suspend fun foodConfiguration(symbol: String, suffix: Int, input: LocalFoodConfigurationRequestV1, id: DomainId) {
            val reply = client.dispatch(symbol, LocalFoodConfigurationCodecV1.command(plan.operation(suffix), input))
            val result = LocalConfigurationCodec.decodeResult(reply.result)
            check(result.code == "food.configuration.saved" && result.id == id && result.revision != null)
        }
        foodConfiguration("CONFIGURE_STATION", 115, LocalFoodConfigurationRequestV1.Station(null, plan.station), plan.stationId)
        foodConfiguration("INSTALL_FROZEN_RECIPE", 116, LocalFoodConfigurationRequestV1.InstallRecipe(published.recipe), plan.recipeId)
        foodConfiguration("CONFIGURE_CAFE_TAKEAWAY_ROUTE", 117, LocalFoodConfigurationRequestV1.Route(null, plan.route), plan.routeId)
        val releasePrior = LocalCatalogReleaseCodecV1.decodeRead(client.read(LocalCatalogReleaseCodecV1.query(
            LocalCatalogReleaseQueryV1(plan.catalogId))).payload)
        check(releasePrior.active == null && releasePrior.publication == null && releasePrior.activeStorageRevision == null)
        published.requireCurrent(checkNotNull(client.publications))
        val releaseReply = client.dispatch("ACTIVATE_SELECTED_CATALOG_RELEASE", LocalCatalogReleaseCodecV1.activate(plan.operation(118),
            LocalActivateCatalogReleaseV1(published.release, releasePrior.activeStorageRevision)))
        val released = LocalCatalogReleaseCodecV1.decodeResult(releaseReply.result)
        check(released.code == "catalog.release.activated" && released.catalogId == plan.catalogId &&
            released.publicationId == plan.releasePublicationId && released.storageRevision != null)

        val beforeCash = LocalCashReadCodec.decode(client.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash)).payload)
        check(beforeCash == LocalCashReadResultV1.NotConfigured(c.firstActorId))
        suspend fun cash(symbol: String, suffix: Int, input: LocalCashRequestV1, action: LocalSalesAction, id: DomainId): MutationReply.Recorded {
            val reply = client.dispatch(symbol, MutationRequest(plan.operation(suffix), LocalSalesStoreProfile.CASH_COMMAND,
                LocalCashCommandCodec.encode(input)))
            val result = LocalSalesResultCodec.decode(reply.result) as? LocalSalesResultV1.Applied
                ?: error("Actual protected cash setup did not apply")
            check(result.action == action && result.documentId == id)
            return reply
        }
        cash("CONFIGURE_ORIGINAL_CUSTODIAN", 119, LocalCashRequestV1.ConfigureCustodian(c.firstActorId),
            LocalSalesAction.CUSTODIAN_CONFIGURED, c.firstActorId)
        cash("CONFIGURE_FROZEN_DRAWER", 120, LocalCashRequestV1.ConfigureDrawer(plan.drawerId, "Synthetic Cafe drawer", Currency.EGP),
            LocalSalesAction.DRAWER_CONFIGURED, plan.drawerId)
        val own = cafeOwnCash(client)
        check(own.custodianId == c.firstActorId && own.session == null && own.unsettledOrderIds.isEmpty() && own.unresolvedReturnIds.isEmpty())
        val drawers = LocalCashReadCodec.decode(client.read(LocalCashReadCodec.request(LocalCashQueryV1.Drawers())).payload)
            as? LocalCashReadResultV1.Drawers ?: error("Actual drawer CAS projection is unavailable")
        check(drawers.nextAfter == null && drawers.drawers.size == 1 && drawers.drawers.single().id == plan.drawerId &&
            !drawers.drawers.single().assigned && !drawers.drawers.single().awaitingClosingReceipt)
        val openingAttestationAt = cafeNow()
        val businessDate = plan.calendar.businessDateAt(openingAttestationAt)
        val cashOpeningReply = cash("OPEN_FROZEN_FLOAT", 121, LocalCashRequestV1.Open(plan.cashSessionId, plan.drawerId,
            drawers.drawers.single().revision, own.assignmentRevision, businessDate, plan.cashOpeningSourceId, plan.openingCash,
            LocalCashEvidenceV1(plan.cashOpeningEvidenceId, openingAttestationAt)), LocalSalesAction.CASH_OPENED, plan.cashSessionId)
        check(plan.calendar.businessDateAt(cashOpeningReply.recordedAt) == businessDate)
        val session = checkNotNull(cafeOwnCash(client).session)
        check(session.businessDate == businessDate && session.opening == plan.openingCash && session.expectedCash == plan.openingCash)
        evidence.bind("ORIGINAL_CASH_SESSION_BUSINESS_DATE", session.businessDate.canonicalString())
        evidence.bind("CASH_OPEN_ATTESTATION_AT", openingAttestationAt.canonicalString())
        evidence.bind("CASH_OPEN_ORIGINAL_NATIVE_RECORDED_AT", cashOpeningReply.recordedAt.canonicalString())
        evidence.bind("SUPPLIED_DATE_MAPPING_NOT_RETAINED_BRANCH_CONFIGURATION", JSONObject().put("zone", plan.calendar.zone.value)
            .put("cutoff", "04:00:00").put("retainedProductionCalendar", "UNBOUND").put("taxDocumentDate", "UNBOUND"))

        val draft = client.dispatchStaged("CREATE_CAFE_TAKEAWAY_DRAFT", cafeDraftSqlIntent(plan), LocalFoodStockCodecV2.command(plan.operation(122),
            LocalFoodStockRequestV2.ActivityProtected(LocalFoodDraftCreateV1(plan.orderId, LocalSalesActivity.CAFE, plan.service))))
        requireCafeFoodResult(draft, LocalFoodDraftProfileV1.CREATED, plan.orderId, emptyList())
        val emptyOrder = checkNotNull(cafeFood(client, plan).food.detail)
        check(emptyOrder.summary.ownerId == c.firstActorId && emptyOrder.summary.service == plan.service &&
            emptyOrder.summary.portionCount == 0 && !emptyOrder.summary.workClosed && emptyOrder.summary.financialCollectionId == null &&
            emptyOrder.portions.isEmpty() && emptyOrder.originalQuotedTotal == plan.zero)
        val catalogue = cafeConfiguration(client, LocalConfigurationProfile.SKU)
        check(catalogue.nextAfter == null && catalogue.items.size == plan.roles.size &&
            catalogue.items.map { it.id }.toSet() == plan.roles.map(plan::skuId).toSet())
        val actualCursor = checkNotNull(catalogue.catalogRevision)
        published.requireCurrent(checkNotNull(client.publications))
        evidence.bind("ACTUAL_QUOTATION_CURSOR_OBSERVED_BEFORE_ADD", actualCursor.value)
        val add = client.dispatchStaged("QUOTE_ORIGINAL_TWO_SMALL_LATTES",
            cafeQuotedAddSqlIntent(plan, emptyOrder.summary.revision, actualCursor, requested.recipe), LocalFoodStockCodecV2.command(plan.quoteOperationId,
            LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(LocalFoodRequestV1.Add(plan.orderId, emptyOrder.summary.revision,
                actualCursor, plan.portionIds.map(plan::selection)))))
        val added = requireCafeFoodResult(add, "food.order.added", plan.orderId, plan.portionIds)
        check(added.portions.all { it.status == FoodPreparationStatus.NOT_SENT && it.issueId == null })
        evidence.bind("ORIGINAL_QUOTED_ADD_COMMIT_DATA_BEFORE_CLOSE", cafeRecordedJson(add))
        return CafeCashSeed(published, basisRevision, openingReply.recordedAt, businessDate, actualCursor)
    }

    suspend fun begin(client: NativeCafeCashClient, plan: NativeCafeCashPlan, quoted: CafeProtectedCut): MutationReply.Recorded {
        check(quoted.phase == CafeCutPhase.QUOTED_UNPAID && quoted.collection == null)
        val reply = client.dispatchStaged("BEGIN_COLLECTION_RESERVE_ALL_UNSENT", cafeBeginSqlIntent(plan, quoted), LocalFoodStockCodecV2.command(plan.operation(123),
            LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.BeginCollection(plan.orderId,
                quoted.food.food.detail!!.summary.revision, plan.collectionId, plan.cashSessionId, checkNotNull(quoted.cash.session).revision))))
        val result = LocalFoodFinancialCodecV1.result(reply.result)
        check(result.code == "food.collection.locked" && result.documentId == plan.collectionId && result.amount == plan.payable &&
            result.revisions.isNotEmpty())
        return reply
    }
    suspend fun finalize(client: NativeCafeCashClient, plan: NativeCafeCashPlan): MutationReply.Recorded {
        val covered = cafeCollection(client, plan)
        check(covered.status == "COVERED" && covered.collected == plan.payable && covered.unpaidRemaining == plan.zero && covered.saleId == null)
        val reply = client.dispatchStaged("FINALIZE_ONE_PAID_SALE", cafeFinalizeSqlIntent(plan, covered), LocalFoodStockCodecV2.command(plan.finalizeOperationId,
            LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.FinalizePaid(plan.collectionId,
                covered.collectionDomainRevision, plan.saleId))))
        val result = LocalFoodFinancialCodecV1.result(reply.result)
        check(result.code == "food.sale.finalized" && result.documentId == plan.saleId && result.amount == plan.payable)
        return reply
    }

    private suspend fun publish(client: NativeCafeCashClient, requested: CafeRequestedSources, plan: NativeCafeCashPlan,
        evidence: NativeCafeCashEvidence, secret: CharArray): CafePublishedSources {
        val owner = checkNotNull(client.publications) { "Actual native original company publication owner is required" }
        check(owner.trust.company == requested.company && client.original.isCurrent())
        val initial = checkNotNull(owner.currentCompletedSnapshot())
        check(initial.company == requested.company && initial.completedPublications.isEmpty())
        val outputs = requested.proposals.map { proposal ->
            val action = nativeFixtureValue(owner.prepare(proposal))
            requireCafeProposal(proposal, action.proposal)
            when (proposal.kind) {
                CompanyBusinessPublicationKind.CATALOG_SKU_V1 -> check(action.readNativeCompanySkuDefinition() ==
                    requested.skus.values.single { it.publicationId == proposal.publicationId })
                CompanyBusinessPublicationKind.FOOD_RECIPE_V1 -> check(action.readNativeCompanyFoodRecipeDefinition() == requested.recipe)
                CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1 -> check(action.readNativeCompanyActivityProfileDefinition() == requested.activity)
                CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 -> check(action.readNativeCatalogReleaseDefinition() == requested.release)
                else -> error("Only the selected pre-retained company source kinds may be published")
            }
            val output = nativeFixtureSecret(secret) { nativeFixtureValue(owner.approve(action, plan.headLogin, it)) }
            requireCafeProposal(proposal, output.proposal)
            check(output.originalActionId == action.originalActionId && output.signingKeyId == action.signingKeyId &&
                output.rootFingerprint == action.rootFingerprint && output.rootFingerprint == owner.trust.rootFingerprint)
            val bytes = output.proposal.copyCanonicalContent()
            try { check(owner.trust.verify(requested.company, output.signingKeyId, bytes, output.signatureHex)) }
            finally { bytes.fill(0) }
            val recovered = nativeFixtureValue(owner.recover(output.originalActionId))
            requireCafeProposal(proposal, recovered.proposal)
            check(CafePublicationBinding(recovered) == CafePublicationBinding(output))
            evidence.event("ACTUAL_NATIVE_COMPANY_PUBLICATION_COMPLETED", CafePublicationBinding(output).data())
            output
        }
        val snapshot = checkNotNull(owner.currentCompletedSnapshot())
        val published = CafePublishedSources(requested, outputs.map(::CafePublicationBinding), snapshot.generation,
            outputs.filter { it.proposal.kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1 }.associate { output ->
                requested.skus.entries.single { it.value.publicationId == output.proposal.publicationId }.key to output.readNativeCompanySku() },
            outputs.single { it.proposal.kind == CompanyBusinessPublicationKind.FOOD_RECIPE_V1 }.readNativeCompanyFoodRecipe(),
            outputs.single { it.proposal.kind == CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1 }.readNativeCompanyActivityProfile(),
            outputs.single { it.proposal.kind == CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 }.readNativeCatalogRelease())
        published.requireCurrent(owner)
        val retained = nativeFixtureValue(owner.retained())
        check(retained.size == outputs.size && retained.all { it.phase == CompanyBusinessPublicationPhase.COMPLETED } &&
            retained.map { it.originalActionId }.toSet() == outputs.map { it.originalActionId }.toSet())
        evidence.bind("ACTUAL_COMPLETE_NATIVE_PUBLICATION_OUTPUT_DATA", published.data())
        return published
    }
}

internal data class CafeCashSeed(val published: CafePublishedSources, val openingBasisRevision: Revision,
    val openingRecordedAt: UtcInstant, val cashBusinessDate: BusinessDate, val quoteCursor: Revision)
internal fun requireCafeFoodResult(reply: MutationReply.Recorded, code: String, orderId: DomainId,
    portions: List<DomainId>): LocalFoodResultV1 = LocalFoodCodecV1.result(reply.result).also {
    check(it.code == code && it.orderId == orderId && it.orderRevision != null && it.portions.map { portion -> portion.portionId } == portions)
}
internal data class CafePublicationBinding(val kind: CompanyBusinessPublicationKind, val publicationId: DomainId,
    val entityId: DomainId, val actionId: DomainId, val signingKeyId: DomainId, val contentDigest: PayloadDigest,
    val signatureHex: String, val rootFingerprint: String) {
    constructor(output: CompanyBusinessPublicationOutput) : this(output.proposal.kind, output.proposal.publicationId,
        output.proposal.entityId, output.originalActionId, output.signingKeyId, output.proposal.contentDigest, output.signatureHex, output.rootFingerprint)
    fun data(): JSONObject = JSONObject().put("kind", kind.name).put("publicationId", publicationId.value).put("entityId", entityId.value)
        .put("originalActionId", actionId.value).put("signingKeyId", signingKeyId.value).put("contentDigest", contentDigest.value)
        .put("signatureHex", signatureHex).put("rootFingerprint", rootFingerprint)
}
/** Detached signed/native output DATA, not publication/custody/signing authority. */
internal class CafePublishedSources(val requested: CafeRequestedSources, private val bindings: List<CafePublicationBinding>,
    private val generation: Long, val skus: Map<String, LocalSignedSkuV1>, val recipe: LocalSignedFoodRecipeV1,
    val activity: LocalSignedCompanyActivityProfileV1, val release: LocalSignedCatalogReleaseV1) {
    fun requireCurrent(owner: LocalCompanyBusinessPublications) {
        val snapshot = checkNotNull(owner.currentCompletedSnapshot())
        check(snapshot.company == requested.company && snapshot.nativeLedgerPresent && snapshot.generation == generation &&
            snapshot.rootFingerprint == bindings.first().rootFingerprint && snapshot.completedPublications.size == bindings.size &&
            snapshot.completedPublications.map { it.proposal.publicationId } == requested.proposals.map { it.publicationId })
        snapshot.completedPublications.zip(bindings).forEach { (output, bound) ->
            check(CafePublicationBinding(output) == bound)
            requireCafeProposal(requested.proposal(bound.publicationId), output.proposal)
            val bytes = output.proposal.copyCanonicalContent()
            try { check(owner.trust.verify(requested.company, output.signingKeyId, bytes, output.signatureHex)) }
            finally { bytes.fill(0) }
        }
    }
    fun data(): JSONObject = JSONObject().put("organizationId", requested.company.organizationId.value)
        .put("companyId", requested.company.companyId.value).put("generation", generation)
        .put("orderedCompleted", JSONArray(bindings.map { it.data() }))
    override fun toString(): String = "CafePublishedSources([REDACTED])"
}
private fun requireCafeProposal(expected: CompanyBusinessPublicationProposal, actual: CompanyBusinessPublicationProposal) {
    check(expected.company == actual.company && expected.kind == actual.kind && expected.publicationId == actual.publicationId &&
        expected.entityId == actual.entityId && expected.contentDigest == actual.contentDigest)
    val left = expected.copyCanonicalContent()
    val right = actual.copyCanonicalContent()
    try { check(left.contentEquals(right)) } finally { left.fill(0); right.fill(0) }
}
