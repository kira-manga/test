package com.ukkera.pos.android.independent.nativefoodpaymentalreadyappliedv1

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

/** Real public publication/configuration/stock/CASH/FOOD commands. No private-table
 * insertion, alternate business service or fabricated native publication is used. */
internal object NativeFoodAlreadyAppliedPreparation {
    suspend fun prepare(client: NativeFoodAlreadyAppliedClient, plan: NativeFoodAlreadyAppliedPlan,
        evidence: NativeFoodAlreadyAppliedEvidence, headSecret: CharArray): FoodAlreadyAppliedSeed {
        val c = client.original.material.coordinates
        val requested = plan.sources(c.scope.company())
        evidence.bind("PROPOSED_SYNTHETIC_COMPANY_SOURCE_DATA_BEFORE_PREPARE", JSONArray(requested.proposals.map { proposal ->
            val bytes = proposal.copyCanonicalContent()
            try { JSONObject().put("kind", proposal.kind.name).put("publicationId", proposal.publicationId.value)
                .put("entityId", proposal.entityId.value).put("digestData", proposal.contentDigest.value)
                .put("canonicalData", foodAlreadyAppliedBase64(StoredBytes(bytes))) }
            finally { bytes.fill(0) }
        }))
        val published = publish(client, plan, requested, evidence, headSecret)
        suspend fun configure(symbol: String, suffix: Int, input: LocalConfigurationRequestV1, id: DomainId) {
            val reply = client.dispatch(symbol, MutationRequest(plan.operation(suffix), LocalConfigurationProfile.COMMAND,
                LocalConfigurationCodec.encode(input)))
            val result = LocalConfigurationCodec.decodeResult(reply.result)
            check(result.code == "configuration.applied" && result.id == id && result.revision != null)
        }
        plan.roles.forEachIndexed { index, role -> configure("INSTALL_SKU_$role", 100 + index,
            LocalConfigurationRequestV1.InstallCompanySku(published.skus.getValue(role)), plan.skuId(role)) }
        val activityReply = client.dispatch("ACTIVATE_SYNTHETIC_FOOD_ACTIVITY", LocalBranchActivityCodecV1.activateInitial(plan.operation(110),
            LocalActivateInitialBranchActivityV1(LocalCompanyActivityPublicationCodecV1.signedPublication(published.activity))))
        val activity = LocalBranchActivityCodecV1.decodeResult(activityReply.result)
        check(activity.code == LocalBranchActivityProfileV1.ACTIVATED && activity.applied.definition == requested.activity &&
            activity.applied.scope == c.scope && activity.applied.actorId == c.firstActorId && activity.applied.activities == setOf(plan.activity))
        configure("CONFIGURE_WAREHOUSE", 111, LocalConfigurationRequestV1.ConfigureWarehouse(null, plan.warehouse), plan.warehouseId)
        configure("APPROVE_SYNTHETIC_OPENING", 112, LocalConfigurationRequestV1.ApproveReceiving(plan.openingApproval), plan.openingApprovalId)
        val empty = LocalStockQueries.decodeItems(client.read(LocalStockCommands.items()).payload)
        check(empty.items.isEmpty() && empty.nextAfter == null &&
            !LocalStockRelationalQueriesV2.normalized(client.read(LocalStockRelationalQueriesV2.profile()).payload))
        val initialize = LocalStockRelationalCommandsV2.initializeFresh(plan.operation(113), plan.initializationId, plan.reasonCode)
        val initialized = LocalStockRelationalCommandsV2.decodeInitializationResult(initialize.payload,
            client.dispatch("INITIALIZE_FRESH_STOCK", initialize).result)
        check(initialized.initializationId == plan.initializationId && initialized.freshEmpty &&
            LocalStockRelationalQueriesV2.normalized(client.read(LocalStockRelationalQueriesV2.profile()).payload))
        val items = plan.stocks.map { row ->
            val absent = LocalStockQueries.decodeQuantities(client.read(LocalStockCommands.quantities(row.skuId)).payload)
            check(absent.skuId == row.skuId && absent.rows.isEmpty() && absent.nextAfter == null && absent.itemStorageRevision == null)
            LocalStockItemVersion(row.skuId, absent.itemStorageRevision)
        }
        val basis = foodAlreadyAppliedConfiguration(client, LocalConfigurationProfile.BASIS, plan.openingApprovalId)
        check(basis.items.size == 1 && basis.nextAfter == null &&
            (basis.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value == plan.openingApproval)
        val receipt = LocalGoodsReceiptRequest(plan.openingReceiptId, plan.openingApprovalId, basis.items.single().revision, items,
            plan.stocks.map { LocalGoodsReceiptLine(it.receiptLineId, it.allocationId, it.sourceId, it.skuId, plan.warehouseId,
                plan.quantity(it.role, it.onHand), conversion = null, lotId = null, expiresAt = null,
                disposition = LocalReceiptDisposition.RELEASED, originalValue = plan.zero) }, plan.reasonCode)
        val opening = LocalStockRelationalCommandsV2.opening(plan.operation(114), receipt)
        val openingReply = client.dispatch("OPEN_FROZEN_INGREDIENT_QUANTITIES", opening)
        val opened = LocalStockRelationalCommandsV2.decodeResult(opening.payload, openingReply.result)
        check(opened.kind == LocalStockResultKind.OPENED && opened.documentId == plan.openingReceiptId && opened.failure == null &&
            opened.items.map { it.skuId }.toSet() == plan.stocks.map { it.skuId }.toSet() && opened.items.all { it.storageRevision != null })
        suspend fun foodConfigure(symbol: String, suffix: Int, input: LocalFoodConfigurationRequestV1, id: DomainId) {
            val result = LocalConfigurationCodec.decodeResult(client.dispatch(symbol,
                LocalFoodConfigurationCodecV1.command(plan.operation(suffix), input)).result)
            check(result.code == "food.configuration.saved" && result.id == id && result.revision != null)
        }
        foodConfigure("CONFIGURE_STATION", 115, LocalFoodConfigurationRequestV1.Station(null, plan.station), plan.stationId)
        foodConfigure("INSTALL_ORIGINAL_RECIPE", 116, LocalFoodConfigurationRequestV1.InstallRecipe(published.recipe), plan.recipeId)
        foodConfigure("CONFIGURE_ORIGINAL_ROUTE", 117, LocalFoodConfigurationRequestV1.Route(null, plan.route), plan.routeId)
        val priorRelease = LocalCatalogReleaseCodecV1.decodeRead(client.read(LocalCatalogReleaseCodecV1.query(LocalCatalogReleaseQueryV1(plan.catalogId))).payload)
        check(priorRelease.active == null && priorRelease.publication == null && priorRelease.activeStorageRevision == null)
        published.requireCurrent(checkNotNull(client.publications))
        val release = LocalCatalogReleaseCodecV1.decodeResult(client.dispatch("ACTIVATE_SELECTED_CATALOG_RELEASE",
            LocalCatalogReleaseCodecV1.activate(plan.operation(118), LocalActivateCatalogReleaseV1(published.release, priorRelease.activeStorageRevision))).result)
        check(release.code == "catalog.release.activated" && release.catalogId == plan.catalogId && release.publicationId == plan.releasePublicationId)

        val dateA = openDrawer(client, plan, FoodAlreadyAppliedActor.A, 119, evidence)
        val dateB = openDrawer(client, plan, FoodAlreadyAppliedActor.B, 130, evidence)
        val draftReply = client.dispatch("CREATE_ORIGINAL_FOOD_DRAFT", LocalFoodStockCodecV2.command(plan.operation(124),
            LocalFoodStockRequestV2.ActivityProtected(LocalFoodDraftCreateV1(plan.orderId, plan.activity, plan.service))))
        requireFoodAlreadyAppliedFoodResult(draftReply, LocalFoodDraftProfileV1.CREATED, plan.orderId, emptyList())
        val draft = checkNotNull(foodAlreadyAppliedFood(client, plan).food.detail)
        check(draft.summary.ownerId == c.firstActorId && draft.summary.portionCount == 0 && draft.portions.isEmpty() &&
            draft.summary.financialCollectionId == null && draft.originalQuotedTotal == plan.zero)
        val catalogue = foodAlreadyAppliedConfiguration(client, LocalConfigurationProfile.SKU)
        check(catalogue.nextAfter == null && catalogue.items.map { it.id }.toSet() == plan.roles.map(plan::skuId).toSet())
        val cursor = checkNotNull(catalogue.catalogRevision)
        published.requireCurrent(checkNotNull(client.publications))
        val add = client.dispatch("QUOTE_TWO_ORIGINAL_PORTIONS", LocalFoodStockCodecV2.command(plan.quoteOperationId,
            LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(LocalFoodRequestV1.Add(plan.orderId, draft.summary.revision,
                cursor, plan.portionIds.map(plan::selection)))))
        val added = requireFoodAlreadyAppliedFoodResult(add, "food.order.added", plan.orderId, plan.portionIds)
        check(added.portions.all { it.status == FoodPreparationStatus.NOT_SENT && it.issueId == null })
        val quoted = foodAlreadyAppliedFood(client, plan)
        check(checkNotNull(quoted.food.detail).originalQuotedTotal == plan.payable && quoted.currentUnpaidPayable == plan.payable)
        val session = checkNotNull(foodAlreadyAppliedOwnCash(client).session)
        val begin = client.dispatch("BEGIN_RESERVE_BOTH_UNSENT_PORTIONS", LocalFoodStockCodecV2.command(plan.operation(125),
            LocalFoodStockRequestV2.Financial(LocalFoodFinancialRequestV1.BeginCollection(plan.orderId,
                checkNotNull(quoted.food.detail).summary.revision, plan.collectionId, plan.cashSessionA, session.revision))))
        val collection = LocalFoodFinancialCodecV1.result(begin.result)
        check(collection.code == "food.collection.locked" && collection.documentId == plan.collectionId && collection.amount == plan.payable)
        return FoodAlreadyAppliedSeed(published, basis.items.single().revision, openingReply.recordedAt, dateA, dateB)
    }

    /** Deliberate unrelated successful FOOD writer W, not a no-business-write read/recovery.
     * The empty draft has no price, portions, payment, stock or kitchen work. */
    suspend fun laterWriter(client: NativeFoodAlreadyAppliedClient, plan: NativeFoodAlreadyAppliedPlan): MutationReply.Recorded {
        val reply = client.dispatch("W_LATER_EMPTY_FOOD_DRAFT", LocalFoodStockCodecV2.command(plan.laterWriterOperationId,
            LocalFoodStockRequestV2.ActivityProtected(LocalFoodDraftCreateV1(plan.laterOrderId, plan.activity,
                LocalFoodServiceV1(plan.service.channel, "Synthetic FAA later empty prefix writer", "")))))
        requireFoodAlreadyAppliedFoodResult(reply, LocalFoodDraftProfileV1.CREATED, plan.laterOrderId, emptyList())
        return reply
    }

    private suspend fun openDrawer(client: NativeFoodAlreadyAppliedClient, plan: NativeFoodAlreadyAppliedPlan,
        actor: FoodAlreadyAppliedActor, firstOperation: Int, evidence: NativeFoodAlreadyAppliedEvidence): BusinessDate {
        val c = client.original.material.coordinates
        val actorId = plan.actorId(actor, c)
        val drawer = if (actor == FoodAlreadyAppliedActor.A) plan.drawerA else plan.drawerB
        val sessionId = if (actor == FoodAlreadyAppliedActor.A) plan.cashSessionA else plan.cashSessionB
        val opening = if (actor == FoodAlreadyAppliedActor.A) plan.openingA else plan.openingB
        val source = if (actor == FoodAlreadyAppliedActor.A) plan.openingSourceA else plan.openingSourceB
        val evidenceId = if (actor == FoodAlreadyAppliedActor.A) plan.openingEvidenceA else plan.openingEvidenceB
        val before = LocalCashReadCodec.decode(client.read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash), actor).payload)
        check(before == LocalCashReadResultV1.NotConfigured(actorId))
        suspend fun cash(symbol: String, suffix: Int, input: LocalCashRequestV1, action: LocalSalesAction, id: DomainId): MutationReply.Recorded {
            val reply = client.dispatch(symbol, MutationRequest(plan.operation(suffix), LocalSalesStoreProfile.CASH_COMMAND,
                LocalCashCommandCodec.encode(input)), actor)
            val result = LocalSalesResultCodec.decode(reply.result) as? LocalSalesResultV1.Applied
                ?: error("Genuine protected drawer setup did not apply")
            check(result.action == action && result.documentId == id)
            return reply
        }
        cash("CONFIGURE_CUSTODIAN_${actor.name}", firstOperation, LocalCashRequestV1.ConfigureCustodian(actorId), LocalSalesAction.CUSTODIAN_CONFIGURED, actorId)
        cash("CONFIGURE_DRAWER_${actor.name}", firstOperation + 1, LocalCashRequestV1.ConfigureDrawer(drawer,
            "Synthetic FAA drawer ${actor.name}", Currency.EGP), LocalSalesAction.DRAWER_CONFIGURED, drawer)
        val own = foodAlreadyAppliedOwnCash(client, actor)
        check(own.custodianId == actorId && own.session == null && own.unsettledOrderIds.isEmpty() && own.unresolvedReturnIds.isEmpty())
        val drawers = LocalCashReadCodec.decode(client.read(LocalCashReadCodec.request(LocalCashQueryV1.Drawers()), actor).payload)
            as? LocalCashReadResultV1.Drawers ?: error("The actual drawer CAS projection is unavailable")
        check(drawers.nextAfter == null)
        val selected = drawers.drawers.single { it.id == drawer }
        check(!selected.assigned && !selected.awaitingClosingReceipt)
        val occurredAt = foodAlreadyAppliedNow()
        val businessDate = plan.calendar.businessDateAt(occurredAt)
        val reply = cash("OPEN_FROZEN_FLOAT_${actor.name}", firstOperation + 2, LocalCashRequestV1.Open(sessionId,
            drawer, selected.revision, own.assignmentRevision, businessDate, source, opening, LocalCashEvidenceV1(evidenceId, occurredAt)),
            LocalSalesAction.CASH_OPENED, sessionId)
        check(plan.calendar.businessDateAt(reply.recordedAt) == businessDate)
        val actual = checkNotNull(foodAlreadyAppliedOwnCash(client, actor).session)
        check(actual.id == sessionId && actual.drawerId == drawer && actual.custodianId == actorId &&
            actual.opening == opening && actual.expectedCash == opening && actual.businessDate == businessDate)
        evidence.bind("ORIGINAL_DRAWER_${actor.name}_BUSINESS_DATE", businessDate.canonicalString())
        return businessDate
    }

    private suspend fun publish(client: NativeFoodAlreadyAppliedClient, plan: NativeFoodAlreadyAppliedPlan,
        requested: FoodAlreadyAppliedRequestedSources, evidence: NativeFoodAlreadyAppliedEvidence,
        headSecret: CharArray): FoodAlreadyAppliedPublishedSources {
        val owner = checkNotNull(client.publications)
        check(owner.trust.company == client.original.material.coordinates.scope.company())
        val initial = checkNotNull(owner.currentCompletedSnapshot())
        check(initial.completedPublications.isEmpty())
        val outputs = requested.proposals.map { proposal ->
            val action = nativeFixtureValue(owner.prepare(proposal))
            requireFoodAlreadyAppliedProposal(proposal, action.proposal)
            val output = nativeFixtureSecret(headSecret) { nativeFixtureValue(owner.approve(action, plan.headLogin, it)) }
            requireFoodAlreadyAppliedProposal(proposal, output.proposal)
            check(output.originalActionId == action.originalActionId && output.signingKeyId == action.signingKeyId &&
                output.rootFingerprint == owner.trust.rootFingerprint)
            val bytes = output.proposal.copyCanonicalContent()
            try { check(owner.trust.verify(output.proposal.company, output.signingKeyId, bytes, output.signatureHex)) }
            finally { bytes.fill(0) }
            val recovered = nativeFixtureValue(owner.recover(output.originalActionId))
            requireFoodAlreadyAppliedProposal(proposal, recovered.proposal)
            check(recovered.originalActionId == output.originalActionId && recovered.signingKeyId == output.signingKeyId &&
                recovered.signatureHex == output.signatureHex && recovered.rootFingerprint == output.rootFingerprint)
            evidence.event("GENUINE_NATIVE_COMPANY_PUBLICATION_COMPLETED", JSONObject().put("kind", proposal.kind.name)
                .put("publicationId", proposal.publicationId.value).put("originalActionId", output.originalActionId.value))
            output
        }
        val snapshot = checkNotNull(owner.currentCompletedSnapshot())
        val result = FoodAlreadyAppliedPublishedSources(requested, outputs, snapshot.generation,
            outputs.filter { it.proposal.kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1 }.associate { output ->
                requested.skus.entries.single { it.value.publicationId == output.proposal.publicationId }.key to output.readNativeCompanySku() },
            outputs.single { it.proposal.kind == CompanyBusinessPublicationKind.FOOD_RECIPE_V1 }.readNativeCompanyFoodRecipe(),
            outputs.single { it.proposal.kind == CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1 }.readNativeCompanyActivityProfile(),
            outputs.single { it.proposal.kind == CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 }.readNativeCatalogRelease())
        check(result.skus.mapValues { it.value.definition } == requested.skus && result.recipe.definition == requested.recipe &&
            result.activity.definition == requested.activity && result.release.definition == requested.release)
        result.requireCurrent(owner)
        return result
    }
}

internal data class FoodAlreadyAppliedSeed(val published: FoodAlreadyAppliedPublishedSources,
    val openingBasisRevision: Revision, val stockOpeningRecordedAt: UtcInstant,
    val cashBusinessDateA: BusinessDate, val cashBusinessDateB: BusinessDate)
internal class FoodAlreadyAppliedPublishedSources(val requested: FoodAlreadyAppliedRequestedSources,
    private val outputs: List<CompanyBusinessPublicationOutput>, private val generation: Long,
    val skus: Map<String, LocalSignedSkuV1>, val recipe: LocalSignedFoodRecipeV1,
    val activity: LocalSignedCompanyActivityProfileV1, val release: LocalSignedCatalogReleaseV1) {
    fun requireCurrent(owner: LocalCompanyBusinessPublications) {
        val snapshot = checkNotNull(owner.currentCompletedSnapshot())
        check(snapshot.nativeLedgerPresent && snapshot.generation == generation && snapshot.completedPublications.size == outputs.size)
        snapshot.completedPublications.zip(outputs).forEach { (actual, expected) ->
            check(actual.originalActionId == expected.originalActionId && actual.signingKeyId == expected.signingKeyId &&
                actual.rootFingerprint == expected.rootFingerprint && actual.signatureHex == expected.signatureHex)
            requireFoodAlreadyAppliedProposal(expected.proposal, actual.proposal)
            val bytes = actual.proposal.copyCanonicalContent()
            try { check(owner.trust.verify(actual.proposal.company, actual.signingKeyId, bytes, actual.signatureHex)) }
            finally { bytes.fill(0) }
        }
    }
}
private fun requireFoodAlreadyAppliedProposal(expected: CompanyBusinessPublicationProposal, actual: CompanyBusinessPublicationProposal) {
    check(expected.company == actual.company && expected.kind == actual.kind && expected.publicationId == actual.publicationId &&
        expected.entityId == actual.entityId && expected.contentDigest == actual.contentDigest)
    val left = expected.copyCanonicalContent(); val right = actual.copyCanonicalContent()
    try { check(left.contentEquals(right)) } finally { left.fill(0); right.fill(0) }
}
internal fun requireFoodAlreadyAppliedFoodResult(reply: MutationReply.Recorded, code: String, orderId: DomainId,
    portions: List<DomainId>): LocalFoodResultV1 = LocalFoodCodecV1.result(reply.result).also {
    check(it.code == code && it.orderId == orderId && it.orderRevision != null && it.portions.map { row -> row.portionId } == portions)
}
