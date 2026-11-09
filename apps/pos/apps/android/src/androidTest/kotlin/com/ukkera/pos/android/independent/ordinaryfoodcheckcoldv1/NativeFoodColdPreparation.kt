package com.ukkera.pos.android.independent.ordinaryfoodcheckcoldv1

import com.ukkera.pos.android.independent.nativefixture.nativeFixtureSecret
import com.ukkera.pos.android.independent.nativefixture.nativeFixtureValue
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import org.json.JSONArray
import org.json.JSONObject

/** Actual public/native producers only. All previously unspecified company contents are
 * proposed and durably retained before the first prepare, never backfilled from an output. */
internal object NativeFoodColdPreparation {
    suspend fun prepare(client: NativeFoodColdClient, plan: NativeFoodColdInputPlan,
        evidence: NativeFoodColdEvidence, headSecret: CharArray): FoodColdSeedBindings {
        val c = client.original.material.coordinates
        val requested = plan.sources(c.scope.company())
        evidence.freezeSources(requested)
        val published = publish(client, requested, evidence, plan.headLogin, headSecret)
        val symbols = linkedSetOf<String>()
        suspend fun command(actor: FoodColdActor, symbol: String, request: MutationRequest): MutationReply.Recorded {
            check(symbols.add(symbol))
            return client.dispatch(actor, symbol, request)
        }
        suspend fun configuration(symbol: String, suffix: Int, request: LocalConfigurationRequestV1,
            expectedId: DomainId): LocalConfigurationResultV1 {
            val reply = command(FoodColdActor.ADMINISTRATOR, symbol, MutationRequest(plan.operation(suffix),
                LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(request)))
            return LocalConfigurationCodec.decodeResult(reply.result).also {
                check(it.code == "configuration.applied" && it.id == expectedId && it.revision != null)
            }
        }
        plan.roles.forEachIndexed { index, role ->
            configuration("INSTALL_SKU_$role", 100 + index,
                LocalConfigurationRequestV1.InstallCompanySku(published.skus.getValue(role)), plan.skuId(role))
            foodColdRequireSku(client, FoodColdActor.ADMINISTRATOR, plan, requested, role)
        }
        val activityReply = command(FoodColdActor.ADMINISTRATOR, "ACTIVATE_ACTIVITY", LocalBranchActivityCodecV1.activateInitial(
            plan.operation(200), LocalActivateInitialBranchActivityV1(
                LocalCompanyActivityPublicationCodecV1.signedPublication(published.activity))))
        val activity = LocalBranchActivityCodecV1.decodeResult(activityReply.result)
        check(activity.code == LocalBranchActivityProfileV1.ACTIVATED && activity.applied.definition == requested.activity &&
            activity.applied.scope == c.scope && activity.applied.actorId == plan.administratorId &&
            activity.applied.operationId == plan.operation(200) && activity.applied.recordedAt == activityReply.recordedAt &&
            activity.applied.activities == setOf(plan.activity))
        configuration("CONFIGURE_WAREHOUSE", 201,
            LocalConfigurationRequestV1.ConfigureWarehouse(null, plan.warehouse), plan.warehouseId)
        configuration("APPROVE_OPENING", 202,
            LocalConfigurationRequestV1.ApproveReceiving(plan.openingApproval), plan.openingApprovalId)

        val emptyItems = LocalStockQueries.decodeItems(client.read(FoodColdActor.ADMINISTRATOR, LocalStockCommands.items()).payload)
        check(emptyItems.items.isEmpty() && emptyItems.nextAfter == null)
        check(!LocalStockRelationalQueriesV2.normalized(client.read(FoodColdActor.ADMINISTRATOR,
            LocalStockRelationalQueriesV2.profile()).payload))
        val initializationRequest = LocalStockRelationalCommandsV2.initializeFresh(plan.operation(203), plan.initializationId, plan.reasonCode)
        val initialized = command(FoodColdActor.ADMINISTRATOR, "INITIALIZE_FRESH_STOCK", initializationRequest)
        val initialization = LocalStockRelationalCommandsV2.decodeInitializationResult(initializationRequest.payload, initialized.result)
        check(initialization.initializationId == plan.initializationId && initialization.freshEmpty)
        check(LocalStockRelationalQueriesV2.normalized(client.read(FoodColdActor.ADMINISTRATOR,
            LocalStockRelationalQueriesV2.profile()).payload))
        val items = plan.stocks.map { stock ->
            val absent = LocalStockQueries.decodeQuantities(client.read(FoodColdActor.ADMINISTRATOR,
                LocalStockCommands.quantities(plan.skuId(stock.role))).payload)
            check(absent.skuId == plan.skuId(stock.role) && absent.rows.isEmpty() && absent.nextAfter == null &&
                absent.itemStorageRevision == null && absent.unitId == null && absent.quantityScale == null)
            LocalStockItemVersion(absent.skuId, absent.itemStorageRevision)
        }
        val basis = client.foodColdConfiguration(FoodColdActor.ADMINISTRATOR, LocalConfigurationProfile.BASIS, plan.openingApprovalId)
        check(basis.items.size == 1 && basis.nextAfter == null &&
            (basis.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value == plan.openingApproval)
        val basisRevision = basis.items.single().revision // Domain basis revision; not physical storageRevision.
        evidence.bind("ACTUAL_OPENING_BASIS_DOMAIN_REVISION", basisRevision.value.toString())
        val receipt = LocalGoodsReceiptRequest(plan.openingReceiptId, plan.openingApprovalId, basisRevision, items,
            plan.stocks.map { stock -> LocalGoodsReceiptLine(plan.receiptLineId(stock.role), plan.allocationId(stock.role),
                plan.stockSourceId(stock.role), plan.skuId(stock.role), plan.warehouseId, plan.quantity(stock.role, stock.initial),
                conversion = null, lotId = null, expiresAt = null, disposition = LocalReceiptDisposition.RELEASED,
                originalValue = plan.zero) }, plan.reasonCode)
        val openingRequest = LocalStockRelationalCommandsV2.opening(plan.operation(204), receipt)
        val openingReply = command(FoodColdActor.ADMINISTRATOR, "OPENING_STOCK", openingRequest)
        val opening = LocalStockRelationalCommandsV2.decodeResult(openingRequest.payload, openingReply.result)
        check(opening.kind == LocalStockResultKind.OPENED && opening.documentId == plan.openingReceiptId && opening.failure == null &&
            opening.items.map { it.skuId }.toSet() == plan.stocks.map { plan.skuId(it.role) }.toSet() &&
            opening.items.all { it.storageRevision != null })
        foodColdRequireOpening(client, plan, basisRevision, openingReply.recordedAt)
        plan.stocks.forEach { foodColdRequireStock(client, plan, it, atSeed = false) }

        val stationReply = command(FoodColdActor.ADMINISTRATOR, "CONFIGURE_STATION", LocalFoodConfigurationCodecV1.command(
            plan.operation(205), LocalFoodConfigurationRequestV1.Station(null, plan.station)))
        requireFoodConfiguration(stationReply, plan.stationId)
        for ((symbol, suffix, input, id) in listOf(
            FoodColdFloorInput("CONFIGURE_FLOOR", 206, LocalFoodFloorConfigurationRequestV1.Floor(null, plan.floor), plan.floorId),
            FoodColdFloorInput("CONFIGURE_TABLE", 207, LocalFoodFloorConfigurationRequestV1.Table(null, plan.table), plan.tableId))) {
            val reply = command(FoodColdActor.ADMINISTRATOR, symbol, LocalFoodFloorCodecV1.command(plan.operation(suffix), input))
            val result = LocalFoodFloorCodecV1.decodeResult(reply.result)
            check(result.code == LocalFoodFloorProfileV1.SAVED && result.id == id && result.revision != null)
        }
        plan.outputRoles.forEachIndexed { index, role ->
            val recipeReply = command(FoodColdActor.ADMINISTRATOR, "INSTALL_RECIPE_$role", LocalFoodConfigurationCodecV1.command(
                plan.operation(300 + index), LocalFoodConfigurationRequestV1.InstallRecipe(published.recipes.getValue(role))))
            requireFoodConfiguration(recipeReply, plan.recipeId(role))
            val routeReply = command(FoodColdActor.ADMINISTRATOR, "CONFIGURE_ROUTE_$role", LocalFoodConfigurationCodecV1.command(
                plan.operation(400 + index), LocalFoodConfigurationRequestV1.Route(null, plan.route(role))))
            requireFoodConfiguration(routeReply, plan.routeId(role))
        }

        // Documentary activation must not rewrite any current SKU/body/revision/cursor,
        // recipe/route/station, floor/activity, stock/value or original opening receipt.
        val beforeRelease = foodColdConfigurationCut(client, plan, requested, basisRevision, openingReply.recordedAt)
        val prior = LocalCatalogReleaseCodecV1.decodeRead(client.read(FoodColdActor.ADMINISTRATOR,
            LocalCatalogReleaseCodecV1.query(LocalCatalogReleaseQueryV1(plan.catalogId))).payload)
        check(prior.catalogId == plan.catalogId && prior.active == null && prior.publication == null && prior.activeStorageRevision == null)
        published.requireCurrent(checkNotNull(client.publications))
        evidence.bind("ACTUAL_NATIVE_COMPLETED_CUT_BEFORE_RELEASE_ACTIVATION", published.cutData())
        evidence.bind("ACTUAL_RELEASE_ACTIVE_POINTER_CAS", prior.activeStorageRevision?.value ?: JSONObject.NULL)
        val release5 = published.releases.last()
        check(release5.definition.revision == Revision(5))
        val releaseReply = command(FoodColdActor.ADMINISTRATOR, "ACTIVATE_RELEASE5", LocalCatalogReleaseCodecV1.activate(plan.operation(500),
            LocalActivateCatalogReleaseV1(release5, prior.activeStorageRevision)))
        val releaseResult = LocalCatalogReleaseCodecV1.decodeResult(releaseReply.result)
        check(releaseResult.code == "catalog.release.activated" && releaseResult.catalogId == plan.catalogId &&
            releaseResult.publicationId == release5.definition.publicationId && releaseResult.storageRevision != null)
        val releaseRead = foodColdRequireRelease(client, plan, published)
        check(releaseRead.activeStorageRevision == releaseResult.storageRevision)
        val afterRelease = foodColdConfigurationCut(client, plan, requested, basisRevision, openingReply.recordedAt)
        beforeRelease.requireNoDocumentaryRewrite(afterRelease)
        evidence.event("DOCUMENTARY_RELEASE5_NO_SKU_CURSOR_CONFIGURATION_STOCK_VALUE_REWRITE")

        val draftReply = command(FoodColdActor.OWNER, "SEED_DRAFT", LocalFoodStockCodecV2.command(plan.operation(600),
            LocalFoodStockRequestV2.ActivityProtected(LocalFoodDraftCreateV1(plan.orderId, plan.activity, plan.service))))
        requireFood(draftReply, LocalFoodDraftProfileV1.CREATED, plan.orderId, emptyList())
        val empty = client.foodColdOrder(plan)
        check(empty.summary.portionCount == 0 && empty.portions.isEmpty() && empty.originalQuotedTotal == plan.zero &&
            empty.summary.service == plan.service && empty.summary.ownerId == c.firstActorId && !empty.summary.workClosed &&
            empty.summary.financialCollectionId == null && empty.collectionState == "UNPAID_NO_COLLECTION")
        val floorRead = foodColdRequireFloor(client, FoodColdActor.OWNER, plan)
        val beforeAssignment = LocalFoodTableAssignmentReadCodecV1.decodeResult(client.read(FoodColdActor.OWNER,
            LocalFoodTableAssignmentReadCodecV1.read(LocalFoodTableAssignmentQueryV1(plan.orderId))).payload)
        check(beforeAssignment.assignment == null && beforeAssignment.orderRevision == empty.summary.revision)
        val assignmentReply = command(FoodColdActor.OWNER, "SEED_TABLE_ASSIGNMENT", LocalFoodTableAssignmentCodecV1.command(plan.operation(601),
            LocalFoodTableAssignmentRequestV1(plan.orderId, empty.summary.revision, plan.floorId,
                checkNotNull(floorRead.floor).storageRevision, plan.tableId, floorRead.tables.single().storageRevision, null)))
        val assignmentResult = LocalFoodTableAssignmentCodecV1.decodeResult(assignmentReply.result)
        check(assignmentResult.code == LocalFoodTableAssignmentProfileV1.SAVED && assignmentResult.orderId == plan.orderId &&
            assignmentResult.orderRevision == empty.summary.revision)
        val assignment = checkNotNull(assignmentResult.assignment)
        check(assignment.floorId == plan.floorId && assignment.tableId == plan.tableId &&
            assignment.floorRevision == floorRead.floor?.storageRevision && assignment.tableRevision == floorRead.tables.single().storageRevision)

        val quoteSources = foodColdConfigurationCut(client, plan, requested, basisRevision, openingReply.recordedAt,
            sourceActor = FoodColdActor.OWNER)
        afterRelease.requireNoDocumentaryRewrite(quoteSources)
        val cursor = quoteSources.catalogCursor // Independently observed actual branch quotation cursor.
        evidence.bind("${plan.profile}_quote_cursor_at_seed", cursor.value.toString())
        published.requireCurrent(checkNotNull(client.publications))
        evidence.bind("ACTUAL_NATIVE_COMPLETED_CUT_BEFORE_ORIGINAL_QUOTE", published.cutData())
        val beforeAdd = client.foodColdOrder(plan)
        check(beforeAdd.summary == empty.summary && beforeAdd.portions.isEmpty())
        val selections = plan.portions.map(plan::selection)
        check(selections.all { it.lineId != null } && selections.map { it.portionId } == plan.portions.map { it.id })
        val addReply = command(FoodColdActor.OWNER, "SEED_QUOTED_ADD", LocalFoodStockCodecV2.command(plan.operation(602),
            LocalFoodStockRequestV2.ActivityProtectedQuotedAdd(LocalFoodRequestV1.Add(plan.orderId, beforeAdd.summary.revision, cursor, selections))))
        val added = requireFood(addReply, "food.order.added", plan.orderId, plan.portions.map { it.id })
        check(added.portions.all { it.status == FoodPreparationStatus.NOT_SENT && it.issueId == null })
        evidence.bind("ACTUAL_ORIGINAL_QUOTED_ADD_COMMIT", JSONObject().put("operationId", addReply.operationId.value)
            .put("actorId", addReply.originalActorId.value).put("recordedAt", addReply.recordedAt.canonicalString())
            .put("sequence", addReply.sequence.value).put("orderRevision", checkNotNull(added.orderRevision).value.toString())
            .put("quoteCursor", cursor.value.toString())
            .put("originalCommitDataTiming", "RETAINED_BEFORE_CLOSE")
            .put("originalCommitDataMeaning", "REPLY_TIME_AND_CURSOR_DATA_NOT_FOOD_NATIVE_ORIGINAL_EVENT_SOURCE_PROOF")
            .put("closedFoodNativeOriginalEventSourceProof", "UNBOUND")
            .put("requiredPreCloseFoodProducerSourceProof", "UNBOUND"))
        val sentInputs = plan.portions.filter { it.tasks == 1 }
        val beforeSend = client.foodColdOrder(plan)
        check(beforeSend.portions.map { it.portionId } == plan.portions.map { it.id } &&
            beforeSend.portions.all { it.preparation.status == FoodPreparationStatus.NOT_SENT })
        val sendReply = command(FoodColdActor.OWNER, "SEED_SEND", LocalFoodStockCodecV2.command(plan.operation(603),
            LocalFoodStockRequestV2.Preparation(LocalFoodRequestV1.Send(plan.orderId, beforeSend.summary.revision, sentInputs.map { it.id }))))
        check(requireFood(sendReply, "food.sent", plan.orderId, sentInputs.map { it.id }).portions.all {
            it.status == FoodPreparationStatus.QUEUED && it.issueId != null })

        // The fixed literals determine this schedule; every expected revision is an actual
        // protected observation. Preparation-domain and aggregate revisions are not conflated.
        for ((index, input) in plan.portions.withIndex()) if (input.preparation in
            setOf(FoodPreparationStatus.PREPARING, FoodPreparationStatus.READY)) {
            val before = transitionPrerequisites(client, plan, input)
            val reply = command(FoodColdActor.KDS, foodColdTransitionSymbol("START", input.id), LocalFoodStockCodecV2.command(plan.operation(700 + index),
                LocalFoodStockRequestV2.Preparation(LocalFoodRequestV1.Station(plan.orderId, before.summary.revision, input.id,
                    before.portions.single { it.portionId == input.id }.preparation.revision, plan.stationId, LocalFoodStationActionV1.START))))
            check(requireFood(reply, "food.started", plan.orderId, listOf(input.id)).portions.single().status == FoodPreparationStatus.PREPARING)
        }
        for ((index, input) in plan.portions.withIndex()) if (input.preparation == FoodPreparationStatus.READY) {
            val before = transitionPrerequisites(client, plan, input)
            val reply = command(FoodColdActor.KDS, foodColdTransitionSymbol("READY", input.id), LocalFoodStockCodecV2.command(plan.operation(800 + index),
                LocalFoodStockRequestV2.Preparation(LocalFoodRequestV1.Station(plan.orderId, before.summary.revision, input.id,
                    before.portions.single { it.portionId == input.id }.preparation.revision, plan.stationId, LocalFoodStationActionV1.READY))))
            check(requireFood(reply, "food.station.ready", plan.orderId, listOf(input.id)).portions.single().status == FoodPreparationStatus.READY)
        }
        for ((index, input) in plan.portions.withIndex()) if (input.fulfillment == FoodFulfillmentStatus.SERVED) {
            val before = client.foodColdOrder(plan)
            val portion = before.portions.single { it.portionId == input.id }
            check(portion.preparation.status == FoodPreparationStatus.READY && portion.preparation.fulfillment == FoodFulfillmentStatus.NOT_HANDED_OVER)
            val reply = command(FoodColdActor.OWNER, foodColdTransitionSymbol("SERVE", input.id), LocalFoodStockCodecV2.command(plan.operation(900 + index),
                LocalFoodStockRequestV2.Preparation(LocalFoodRequestV1.Serve(plan.orderId, before.summary.revision, input.id, portion.preparation.revision))))
            requireFood(reply, "food.served.uncollected", plan.orderId, listOf(input.id))
        }
        check(evidence.originals().map { it.symbol }.toSet() == symbols)
        return FoodColdSeedBindings(published, afterRelease, releaseRead, cursor, assignment, basisRevision,
            openingReply.recordedAt, symbols.toSet())
    }

    private suspend fun transitionPrerequisites(client: NativeFoodColdClient, plan: NativeFoodColdInputPlan,
        input: FoodColdPortionInput): LocalFoodOrderDetailV1 {
        val detail = client.foodColdOrder(plan)
        val portion = detail.portions.single { it.portionId == input.id }
        val task = portion.preparation.tasks.single()
        val kds = LocalFoodReadCodecV1.result(client.read(FoodColdActor.KDS,
            LocalFoodReadCodecV1.read(LocalFoodQueryV1.Task(plan.stationId, task.taskId))).payload)
        check(kds.unavailable == null && kds.detail == null && kds.orders.isEmpty() && kds.nextAfter == null && kds.tasks.size == 1)
        val actual = kds.tasks.single()
        check(actual.orderId == plan.orderId && actual.orderRevision == detail.summary.revision && actual.portionId == input.id &&
            actual.portionRevision == portion.preparation.revision && actual.taskId == task.taskId && actual.stationId == plan.stationId)
        return detail
    }

    private fun requireFoodConfiguration(reply: MutationReply.Recorded, id: DomainId) {
        val result = LocalConfigurationCodec.decodeResult(reply.result)
        check(result.code == "food.configuration.saved" && result.id == id && result.revision != null)
    }
    private fun requireFood(reply: MutationReply.Recorded, code: String, id: DomainId, portions: List<DomainId>): LocalFoodResultV1 =
        LocalFoodCodecV1.result(reply.result).also { result ->
            check(result.code == code && result.orderId == id && result.orderRevision != null && result.portions.map { it.portionId } == portions)
        }

    private suspend fun publish(client: NativeFoodColdClient, requested: FoodColdRequestedSources,
        evidence: NativeFoodColdEvidence, headLogin: String, secret: CharArray): FoodColdPublishedSources {
        val owner = checkNotNull(client.publications) { "The actual native company publication owner is required" }
        check(owner.trust.company == requested.company && client.original.isCurrent())
        val initial = checkNotNull(owner.currentCompletedSnapshot())
        check(initial.company == requested.company && initial.completedPublications.isEmpty())
        val outputs = requested.proposals.map { proposal ->
            val action = nativeFixtureValue(owner.prepare(proposal))
            foodColdRequireProposal(proposal, action.proposal)
            when (proposal.kind) {
                CompanyBusinessPublicationKind.CATALOG_SKU_V1 -> check(action.readNativeCompanySkuDefinition() == requested.skus.values.single { it.publicationId == proposal.publicationId })
                CompanyBusinessPublicationKind.FOOD_RECIPE_V1 -> check(action.readNativeCompanyFoodRecipeDefinition() == requested.recipes.values.single { it.publicationId == proposal.publicationId })
                CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1 -> check(action.readNativeCompanyActivityProfileDefinition() == requested.activity)
                CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 -> check(action.readNativeCatalogReleaseDefinition() == requested.releases.single { it.publicationId == proposal.publicationId })
                else -> error("Only the pre-frozen bounded company publication kinds are requested")
            }
            val output = nativeFixtureSecret(secret) { nativeFixtureValue(owner.approve(action, headLogin, it)) }
            foodColdRequireProposal(proposal, output.proposal)
            check(output.originalActionId == action.originalActionId && output.signingKeyId == action.signingKeyId &&
                output.rootFingerprint == action.rootFingerprint && output.rootFingerprint == owner.trust.rootFingerprint)
            val canonical = output.proposal.copyCanonicalContent()
            try { check(owner.trust.verify(requested.company, output.signingKeyId, canonical, output.signatureHex)) }
            finally { canonical.fill(0) }
            val retained = nativeFixtureValue(owner.recover(output.originalActionId))
            foodColdRequireOutput(FoodColdPublicationBinding(output), retained, requested)
            evidence.event("ACTUAL_NATIVE_COMPANY_PUBLICATION_COMPLETED", FoodColdPublicationBinding(output).data())
            output
        }
        val snapshot = checkNotNull(owner.currentCompletedSnapshot())
        val published = FoodColdPublishedSources(requested, outputs.map(::FoodColdPublicationBinding), snapshot.generation,
            outputs.filter { it.proposal.kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1 }.associate { output ->
                requested.skus.entries.single { it.value.publicationId == output.proposal.publicationId }.key to output.readNativeCompanySku() },
            outputs.filter { it.proposal.kind == CompanyBusinessPublicationKind.FOOD_RECIPE_V1 }.associate { output ->
                requested.recipes.entries.single { it.value.publicationId == output.proposal.publicationId }.key to output.readNativeCompanyFoodRecipe() },
            outputs.single { it.proposal.kind == CompanyBusinessPublicationKind.COMPANY_ACTIVITY_PROFILE_V1 }.readNativeCompanyActivityProfile(),
            outputs.filter { it.proposal.kind == CompanyBusinessPublicationKind.CATALOG_RELEASE_V1 }.map { it.readNativeCatalogRelease() })
        published.requireCurrent(owner)
        val references = nativeFixtureValue(owner.retained())
        check(references.size == outputs.size && references.all { it.phase == CompanyBusinessPublicationPhase.COMPLETED } &&
            references.map { it.originalActionId }.toSet() == outputs.map { it.originalActionId }.toSet() &&
            references.map { it.publicationId }.toSet() == requested.proposals.map { it.publicationId }.toSet())
        evidence.bind("COMPLETE_ACTUAL_NATIVE_PUBLICATION_OUTPUTS", published.cutData())
        return published
    }
}

private data class FoodColdFloorInput(val symbol: String, val suffix: Int,
    val request: LocalFoodFloorConfigurationRequestV1, val id: DomainId)
internal data class FoodColdSeedBindings(val published: FoodColdPublishedSources, val configuration: FoodColdConfigurationCut,
    val release: LocalCatalogReleaseReadReplyV1, val quoteCursor: Revision, val assignment: LocalFoodTableBindingV1,
    val openingBasisRevision: Revision, val openingRecordedAt: UtcInstant, val originalSymbols: Set<String>)

/** Detached observations of genuine completed outputs. These scalar/signed DATA do not own
 * a publication service, signing key, source witness, history verifier or activation grant. */
internal class FoodColdPublishedSources(val requested: FoodColdRequestedSources, val bindings: List<FoodColdPublicationBinding>,
    val generation: Long, val skus: Map<String, LocalSignedSkuV1>, val recipes: Map<String, LocalSignedFoodRecipeV1>,
    val activity: LocalSignedCompanyActivityProfileV1, val releases: List<LocalSignedCatalogReleaseV1>) {
    fun requireSnapshot(snapshot: NativeCompanyBusinessPublicationSnapshot) {
        check(snapshot.company == requested.company && snapshot.nativeLedgerPresent && snapshot.generation == generation &&
            snapshot.rootFingerprint == bindings.first().rootFingerprint)
        val actual = snapshot.completedPublications
        check(actual.size == bindings.size && actual.map { it.proposal.publicationId } == requested.proposals.map { it.publicationId })
        actual.zip(bindings).forEach { (output, binding) -> foodColdRequireOutput(binding, output, requested) }
    }
    fun requireCurrent(owner: LocalCompanyBusinessPublications) {
        val snapshot = checkNotNull(owner.currentCompletedSnapshot())
        requireSnapshot(snapshot)
        snapshot.completedPublications.forEach { output ->
            val canonical = output.proposal.copyCanonicalContent()
            try { check(owner.trust.verify(requested.company, output.signingKeyId, canonical, output.signatureHex)) }
            finally { canonical.fill(0) }
        }
    }
    fun cutData(): JSONObject = JSONObject().put("companyId", requested.company.companyId.value)
        .put("organizationId", requested.company.organizationId.value).put("generation", generation)
        .put("rootFingerprint", bindings.first().rootFingerprint).put("orderedCompleted", JSONArray(bindings.map { it.data() }))
}
internal data class FoodColdPublicationBinding(val kind: CompanyBusinessPublicationKind, val publicationId: DomainId,
    val entityId: DomainId, val originalActionId: DomainId, val signingKeyId: DomainId, val contentDigest: PayloadDigest,
    val signatureHex: String, val rootFingerprint: String) {
    constructor(output: CompanyBusinessPublicationOutput) : this(output.proposal.kind, output.proposal.publicationId,
        output.proposal.entityId, output.originalActionId, output.signingKeyId, output.proposal.contentDigest, output.signatureHex, output.rootFingerprint)
    fun data(): JSONObject = JSONObject().put("kind", kind.name).put("publicationId", publicationId.value).put("entityId", entityId.value)
        .put("originalActionId", originalActionId.value).put("signingKeyId", signingKeyId.value).put("contentDigest", contentDigest.value)
        .put("signatureHex", signatureHex).put("rootFingerprint", rootFingerprint)
}
internal fun foodColdRequireProposal(expected: CompanyBusinessPublicationProposal, actual: CompanyBusinessPublicationProposal) {
    check(expected.company == actual.company && expected.kind == actual.kind && expected.publicationId == actual.publicationId &&
        expected.entityId == actual.entityId && expected.contentDigest == actual.contentDigest)
    val expectedBytes = expected.copyCanonicalContent()
    val actualBytes = actual.copyCanonicalContent()
    try { check(actualBytes.contentEquals(expectedBytes)) } finally { expectedBytes.fill(0); actualBytes.fill(0) }
}
internal fun foodColdRequireOutput(expected: FoodColdPublicationBinding, actual: CompanyBusinessPublicationOutput, requested: FoodColdRequestedSources) {
    check(FoodColdPublicationBinding(actual) == expected)
    foodColdRequireProposal(requested.proposal(expected.publicationId), actual.proposal)
}
internal fun foodColdTransitionSymbol(action: String, portionId: DomainId) = "SEED_${action}_${portionId.value.takeLast(12)}"
