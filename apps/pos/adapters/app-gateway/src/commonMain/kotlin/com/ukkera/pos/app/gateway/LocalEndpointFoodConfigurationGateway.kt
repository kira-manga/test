package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.LocalFoodProfileV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The actual original native company owner and actual employee endpoint/journal, not supplied authority. */
public class LocalEndpointFoodConfigurationGateway(
    private val publications: LocalCompanyBusinessPublications,
    private val endpoint: LocalMasterClient,
    private val journal: LocalClientIntentJournal,
    private val credentials: LocalWorkflowCredentialSource,
    private val installed: LocalWorkflowInstallation,
) : LocalFoodConfigurationGateway {
    private val actions = Mutex()
    override val configured: Boolean = true
    init { require(publications.trust.company == journal.owner.scope.company()) }

    override suspend fun status(): LocalFoodConfigurationStatusResult = actions.withLock {
        try { withAccess { access ->
            val cut = foodRead(access, LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.RECIPE, limit = 1))
            val references = native(publications.retained()).filter { it.kind == CompanyBusinessPublicationKind.FOOD_RECIPE_V1 }
            LocalFoodConfigurationStatusResult.Available(LocalFoodConfigurationStatus(references, cut.reply.observedAt))
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: FoodPublicationFailure) { LocalFoodConfigurationStatusResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { LocalFoodConfigurationStatusResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalFoodConfigurationStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun sources(kind: LocalFoodConfigurationSourceKind, after: DomainId?): LocalFoodConfigurationReadResult =
        readResult { access ->
            if (kind == LocalFoodConfigurationSourceKind.WAREHOUSE) {
                val cut = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, after = after, limit = 20))
                LocalFoodConfigurationReadResult.Page(LocalFoodConfigurationSourcePage(kind, cut.page.items.map { row ->
                    LocalFoodConfigurationLabel(row.id, row.revision, row.storageRevision, row.names.ui(row.id), row.active == true)
                }, cut.page.nextAfter))
            } else {
                val cut = foodRead(access, LocalFoodConfigurationQueryV1(kind.wire(), after = after))
                LocalFoodConfigurationReadResult.Page(LocalFoodConfigurationSourcePage(kind, cut.page.labels.map { row ->
                    LocalFoodConfigurationLabel(row.id, row.revision, row.storageRevision, row.names.ui(row.id), row.active)
                }, cut.page.nextAfter))
            }
        }

    override suspend fun source(kind: LocalFoodConfigurationSourceKind, id: DomainId): LocalFoodConfigurationReadResult =
        readResult { access -> LocalFoodConfigurationReadResult.Detail(detail(access, kind, id)) }

    override suspend fun prepare(draft: LocalFoodRecipeDraft): LocalFoodRecipeResult = actions.withLock {
        recipeResult { access ->
            foodRead(access, LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.RECIPE, limit = 1))
            access.requireAction(LocalAction.RECIPE_SET, draft.recipeId)
            if (journal.requests.withLock { required(journal.pending()).isNotEmpty() }) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            val retained = native(publications.retained()).singleOrNull { it.publicationId == draft.publicationId }
            if (retained != null) {
                if (retained.kind != CompanyBusinessPublicationKind.FOOD_RECIPE_V1 || retained.entityId != draft.recipeId) invalid()
                val result = retainedReview(retained.originalActionId)
                val exact = when (result) {
                    is LocalFoodRecipeResult.Review -> result.review.action.readNativeCompanyFoodRecipeDefinition()
                    is LocalFoodRecipeResult.Completed -> native(publications.recover(result.publication.originalActionId)).readNativeCompanyFoodRecipe().definition
                    is LocalFoodRecipeResult.Unavailable -> return@recipeResult result
                }
                // Compare to the ORIGINAL retained bytes. No current SKU/recipe is substituted
                // into an old proposal, and no live revision change regenerates a native action.
                if (definition(draft, exact.revision) != exact) invalid()
                return@recipeResult result
            }
            val previousCut = foodRead(access, LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.RECIPE, id = draft.recipeId))
            val previous = (previousCut.page.detail as? LocalFoodConfigurationDetailV1.Recipe)?.definition
            if (previous?.revision != draft.expectedRecipeRevision || previous != null && previous.outputSkuId != draft.outputSku.id) invalid()
            if (previous?.revision?.value == Long.MAX_VALUE) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            val selected = listOf(draft.outputSku) + draft.choices.map { it.pricingSku } + draft.choices.flatMap { it.ingredients }.map { it.sku }
            if (selected.map { it.id }.distinct().size > 24 || selected.groupBy { it.id }.values.any { it.distinct().size != 1 }) invalid()
            val definitions = mutableMapOf<DomainId, LocalPublishedSkuV1>()
            for (selection in selected.distinctBy { it.id }) {
                val cut = foodRead(access, LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.SKU, id = selection.id))
                val sku = (cut.page.detail as? LocalFoodConfigurationDetailV1.Sku)?.definition ?: invalid(LocalField.SKU_ID)
                if ((foodDetail(cut.page) as LocalFoodConfigurationDetail.Sku).source != selection) invalid(LocalField.SKU_ID)
                definitions[sku.skuId] = sku
            }
            if (!draft.outputSku.active || !draft.outputSku.canSell || draft.outputSku.fulfillment != LocalCompanySkuFulfillment.MADE_TO_ORDER ||
                draft.outputSku.skuRevision.value <= 0 || draft.choices.flatMap { it.ingredients }.any { component ->
                    !component.sku.active || !component.sku.canPurchase || component.sku.fulfillment != LocalCompanySkuFulfillment.RETAIL ||
                        component.sku.tracking !in setOf(LocalCompanyStockTracking.QUANTITY, LocalCompanyStockTracking.LOT)
                }) invalid()
            val recipe = definition(draft, previous?.revision?.next() ?: Revision(1))
            requireCompatibleLocalFoodRecipe(recipe, definitions)
            val content = LocalFoodConfigurationCodecV1.canonicalPublication(recipe)
            val proposal = try {
                // Conservative finite envelope capacity, not a fake signature for sizing.
                require(content.size <= LocalFoodProfileV1.MAX_SIGNED_RECIPE_BYTES - 512)
                CompanyBusinessPublicationProposal(journal.owner.scope.company(), CompanyBusinessPublicationKind.FOOD_RECIPE_V1,
                    recipe.publicationId, recipe.recipeId, content)
            } finally { content.fill(0) }
            access.checkCurrent()
            reviewView(native(publications.prepare(proposal)))
        }
    }

    override suspend fun review(originalActionId: DomainId): LocalFoodRecipeResult = actions.withLock {
        recipeResult { access ->
            foodRead(access, LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.RECIPE, limit = 1))
            retainedReview(originalActionId)
        }
    }

    override suspend fun approve(review: LocalFoodRecipeReview, headLogin: String, headSecret: CharArray): LocalFoodRecipeResult {
        try { return actions.withLock {
            recipeResult { access ->
                foodRead(access, LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.RECIPE, limit = 1))
                val exact = review.action.readNativeCompanyFoodRecipeDefinition()
                if (exact.company != journal.owner.scope.company() || preview(exact) != review.preview ||
                    review.action.rootFingerprint != publications.trust.rootFingerprint) invalid()
                access.checkCurrent()
                // Password-authenticated original head, independent of branch grants. The
                // native owner checks exact retained action, account/credential revisions and expiry.
                LocalFoodRecipeResult.Completed(completedView(native(publications.approve(review.action, headLogin, headSecret))))
            }
        } } finally { headSecret.fill('\u0000') }
    }

    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult = actions.withLock {
        val operationId = OperationId(originalActionId.value)
        try {
            // Native recovery is never held under the ordinary journal mutex or regenerated.
            val output = withAccess { access ->
                foodRead(access, LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.RECIPE, limit = 1))
                access.checkCurrent()
                native(publications.recover(originalActionId))
            }
            completedView(output)
            val signed = output.readNativeCompanyFoodRecipe()
            val request = LocalFoodConfigurationCodecV1.command(operationId, LocalFoodConfigurationRequestV1.InstallRecipe(signed))
            val intent = LocalCommandIntent(operationId, LocalAction.RECIPE_SET, 1,
                LocalRecordReference(LocalRecordKind.FOOD_RECIPE, signed.definition.recipeId, signed.definition.revision),
                listOf(LocalScalarInput(LocalField.RECIPE_ID, LocalScalarValue.Identifier(signed.definition.recipeId)),
                    LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(signed.definition.publicationId)),
                    LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Text(output.proposal.contentDigest.value)),
                    LocalScalarInput(LocalField.FOOD_CONFIGURATION_BODY_DIGEST, LocalScalarValue.Text(requestDigest(request))),
                    LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true))))
            stageExact(operationId, LocalAction.RECIPE_SET, signed.definition.recipeId, { request to intent },
                text("Activate company-signed food recipe", "تفعيل وصفة طعام موقعة من الشركة"), {
                    listOf(recordValue(text("Recipe / signed revision", "الوصفة ومراجعتها الموقعة"),
                        literal("${signed.definition.recipeId.value} / ${signed.definition.revision.value}")),
                        recordValue(text("Original company publication", "إصدار الشركة الأصلي"), literal(signed.definition.publicationId.value)),
                        recordValue(text("Exact content SHA-256", "بصمة المحتوى الدقيق SHA-256"), literal(output.proposal.contentDigest.value)),
                        recordValue(text("Authority separation", "فصل الصلاحيات"),
                            text("Company signing is complete. This separate employee request activates the exact recipe at this branch; it issues no ingredients, payment or kitchen output.",
                                "اكتمل توقيع الشركة. يفعّل طلب الموظف المنفصل الوصفة الدقيقة في هذا الفرع؛ لا يصرف مكونات أو مدفوعات أو مخرجات مطبخ.")))
                }) { _ -> Unit }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LocalStageResult.Uncertain(operationId) }
    }

    override suspend fun stageStation(draft: LocalFoodStationDraft): LocalStageResult = actions.withLock {
        stageExact(draft.operationId, LocalAction.FOOD_STATION_CONFIGURE, draft.stationId, {
            val request = LocalFoodConfigurationCodecV1.command(draft.operationId, LocalFoodConfigurationRequestV1.Station(
                draft.expectedStorageRevision, LocalFoodStationDefinitionV1(draft.stationId, draft.names.wire(), draft.mode.wire(), true)))
            val intent = LocalCommandIntent(draft.operationId, LocalAction.FOOD_STATION_CONFIGURE, 1,
                LocalRecordReference(LocalRecordKind.FOOD_STATION, draft.stationId), buildList {
                    add(LocalScalarInput(LocalField.STATION_ID, LocalScalarValue.Identifier(draft.stationId)))
                    draft.expectedStorageRevision?.let { add(LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(it.value))) }
                    add(LocalScalarInput(LocalField.FOOD_CONFIGURATION_BODY_DIGEST, LocalScalarValue.Text(requestDigest(request))))
                    add(LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true)))
                })
            request to intent
        }, text("Configure kitchen station", "إعداد محطة المطبخ"), {
            listOf(recordValue(text("Station", "المحطة"), literal(draft.stationId.value)),
                recordValue(text("Name", "الاسم"), draft.names), recordValue(text("Mode", "الوضع"), literal(draft.mode.name)),
                recordValue(text("Physical output", "الإخراج الفعلي"), text("Configuration only. PAPER is a mode, not printer qualification or print acknowledgement. Existing station modes cannot be changed in this cut.",
                    "إعداد فقط. PAPER وضع وليس اعتمادًا للطابعة أو تأكيدًا للطباعة. لا يغير هذا النطاق أوضاع المحطات القائمة.")))
        }) { access ->
            val current = (detail(access, LocalFoodConfigurationSourceKind.STATION, draft.stationId) as? LocalFoodConfigurationDetail.Station)?.source
            if (current?.storageRevision != draft.expectedStorageRevision || current != null && (!current.active || current.mode != draft.mode)) invalid()
        }
    }

    override suspend fun stageRoute(draft: LocalFoodRouteDraft): LocalStageResult = actions.withLock {
        stageExact(draft.operationId, LocalAction.FOOD_ROUTE_CONFIGURE, draft.routeId, {
            val route = LocalFoodRecipeRouteV1(draft.routeId, draft.recipe.id, draft.recipe.revision, draft.activity.wire(), draft.channel.wire(),
                draft.warehouse.id, draft.targets.map { LocalFoodRouteTargetV1(it.routeKey, it.station.id) }, true)
            val request = LocalFoodConfigurationCodecV1.command(draft.operationId, LocalFoodConfigurationRequestV1.Route(draft.expectedStorageRevision, route))
            val intent = LocalCommandIntent(draft.operationId, LocalAction.FOOD_ROUTE_CONFIGURE, 1,
                LocalRecordReference(LocalRecordKind.FOOD_ROUTE, draft.routeId), buildList {
                    add(LocalScalarInput(LocalField.RECIPE_ID, LocalScalarValue.Identifier(draft.recipe.id)))
                    add(LocalScalarInput(LocalField.EXPECTED_FOOD_RECIPE_REVISION, LocalScalarValue.Integer(draft.recipe.revision.value)))
                    add(LocalScalarInput(LocalField.EXPECTED_FOOD_RECIPE_STORAGE_REVISION, LocalScalarValue.Integer(draft.recipe.storageRevision.value)))
                    add(LocalScalarInput(LocalField.WAREHOUSE_ID, LocalScalarValue.Identifier(draft.warehouse.id)))
                    add(LocalScalarInput(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION, LocalScalarValue.Integer(draft.warehouse.storageRevision.value)))
                    draft.expectedStorageRevision?.let { add(LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(it.value))) }
                    add(LocalScalarInput(LocalField.FOOD_CONFIGURATION_BODY_DIGEST, LocalScalarValue.Text(requestDigest(request))))
                    add(LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true)))
                }, LocalField.LINES, draft.targets.map { target -> LocalInputLine(target.routeKey, listOf(
                    LocalScalarInput(LocalField.STATION_ID, LocalScalarValue.Identifier(target.station.id)),
                    LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(target.station.storageRevision.value)))) })
            request to intent
        }, text("Configure complete food recipe routing", "إعداد توجيه وصفة الطعام الكامل"), {
            listOf(recordValue(text("Route", "المسار"), literal(draft.routeId.value)),
                recordValue(text("Recipe / signed revision", "الوصفة ومراجعتها الموقعة"), literal("${draft.recipe.id.value} / ${draft.recipe.revision.value}")),
                recordValue(text("Activity / channel", "النشاط والقناة"), literal("${draft.activity.name} / ${draft.channel.name}")),
                recordValue(text("Ingredient stock warehouse", "مخزن مكونات الطعام"), literal(draft.warehouse.id.value))) +
                draft.targets.map { recordValue(literal(it.routeKey.value), literal(it.station.id.value)) } +
                recordValue(text("Scope", "النطاق"), text("Complete selected recipe routing only. No send, stock issue, payment or printer acknowledgement is produced.",
                    "توجيه كامل للوصفة المختارة فقط. لا ينتج إرسالًا أو صرف مخزون أو دفعًا أو تأكيد طباعة."))
        }) { access ->
            val current = (detail(access, LocalFoodConfigurationSourceKind.ROUTE, draft.routeId) as? LocalFoodConfigurationDetail.Route)?.source
            if (current?.storageRevision != draft.expectedStorageRevision || current != null &&
                (current.recipeId != draft.recipe.id || current.activity != draft.activity || current.channel != draft.channel)) invalid()
            val recipeCut = foodRead(access, LocalFoodConfigurationQueryV1(LocalFoodConfigurationSourceKindV1.RECIPE, id = draft.recipe.id))
            val recipe = (recipeCut.page.detail as? LocalFoodConfigurationDetailV1.Recipe)?.definition ?: invalid(LocalField.RECIPE_ID)
            if ((foodDetail(recipeCut.page) as LocalFoodConfigurationDetail.Recipe).source != draft.recipe || !recipe.active) invalid(LocalField.RECIPE_ID)
            val warehouse = (detail(access, LocalFoodConfigurationSourceKind.WAREHOUSE, draft.warehouse.id) as? LocalFoodConfigurationDetail.Warehouse)?.source
            if (warehouse != draft.warehouse || warehouse?.active != true) invalid(LocalField.WAREHOUSE_ID)
            if (draft.targets.map { it.routeKey }.toSet() != recipe.choices.flatMap { it.routeKeys }.toSet()) invalid()
            for (target in draft.targets) {
                val station = (detail(access, LocalFoodConfigurationSourceKind.STATION, target.station.id) as? LocalFoodConfigurationDetail.Station)?.source
                if (station != target.station || station?.active != true) invalid(LocalField.STATION_ID)
            }
            for (choice in recipe.choices) if (draft.targets.filter { it.routeKey in choice.routeKeys }.map { it.station.id }.distinct().size != choice.routeKeys.size) invalid()
        }
    }

    /** Read the own original entry FIRST. Retried station/routes never re-prepare from current sources. */
    private suspend fun stageExact(operationId: OperationId, action: LocalAction, target: DomainId,
        original: () -> Pair<MutationRequest, LocalCommandIntent>, label: LocalText, summary: () -> List<LocalRecordValue>,
        preflight: suspend (GatewayAccess) -> Unit): LocalStageResult {
        var absenceEstablished = false
        var writeStarted = false
        try { return withAccess { access -> journal.requests.withLock stage@{
            val existing = required(journal.find(operationId))
            if (existing != null) {
                val (request, intent) = original()
                if (existing.localAction != action || existing.formVersion != intent.formVersion ||
                    existing.inputDigest != inputDigest(intent) || existing.request() != request) return@stage LocalStageResult.Uncertain(operationId)
                access.checkCurrent()
                return@stage LocalStageResult.Staged(pending(existing, access.canContinue(existing)))
            }
            absenceEstablished = true
            if (required(journal.pending()).isNotEmpty()) return@stage LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
            if (!installed.has(LocalFoodConfigurationProfileV1.COMMAND)) return@stage LocalStageResult.NotStaged(LocalServiceProblem.NOT_IMPLEMENTED)
            access.requireAction(action, target)
            val (request, intent) = original()
            preflight(access)
            val entry = StoredClientIntentV1.staged(journal.owner, intent, request, label, summary())
            access.checkCurrent()
            writeStarted = true
            when (journal.stage(entry)) {
                JournalWrite.DURABLE -> { access.checkCurrent(); LocalStageResult.Staged(pending(entry, access.canContinue(entry))) }
                // NO_CAPACITY is the journal's definite no-write outcome, unlike unavailable.
                JournalWrite.NO_CAPACITY -> { writeStarted = false; LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER) }
                JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE -> LocalStageResult.Uncertain(operationId)
            }
        } } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) {
            return if (absenceEstablished && !writeStarted) LocalStageResult.NotStaged(failure.problem) else LocalStageResult.Uncertain(operationId)
        }
        catch (_: IllegalArgumentException) {
            return if (absenceEstablished && !writeStarted) LocalStageResult.NotStaged(LocalServiceProblem.INVALID_INPUT) else LocalStageResult.Uncertain(operationId)
        }
        catch (_: Exception) { return LocalStageResult.Uncertain(operationId) }
    }

    private suspend fun retainedReview(id: DomainId): LocalFoodRecipeResult {
        val reference = native(publications.retained()).singleOrNull { it.originalActionId == id && it.kind == CompanyBusinessPublicationKind.FOOD_RECIPE_V1 }
            ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        return when (reference.phase) {
            CompanyBusinessPublicationPhase.PREPARED -> reviewView(native(publications.review(id)))
            CompanyBusinessPublicationPhase.COMPLETED -> LocalFoodRecipeResult.Completed(completedView(native(publications.recover(id))))
            CompanyBusinessPublicationPhase.SIGNING_INTENT -> throw FoodPublicationFailure(LocalServiceProblem.STORAGE_UNCERTAIN, SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun reviewView(action: CompanyBusinessPublicationAction): LocalFoodRecipeResult.Review {
        val exact = action.readNativeCompanyFoodRecipeDefinition()
        if (exact.company != journal.owner.scope.company() || action.rootFingerprint != publications.trust.rootFingerprint) unavailable()
        return LocalFoodRecipeResult.Review(LocalFoodRecipeReview(action, preview(exact)))
    }

    private fun completedView(output: CompanyBusinessPublicationOutput): LocalFoodRecipeCompleted {
        val signed = output.readNativeCompanyFoodRecipe()
        val content = LocalFoodConfigurationCodecV1.canonicalPublication(signed.definition)
        try {
            if (signed.definition.company != journal.owner.scope.company() || output.rootFingerprint != publications.trust.rootFingerprint ||
                !publications.trust.verify(signed.definition.company, signed.signingKeyId, content, signed.signatureHex)) unavailable()
        } finally { content.fill(0) }
        return LocalFoodRecipeCompleted(output.originalActionId, preview(signed.definition), output.rootFingerprint)
    }

    private fun definition(draft: LocalFoodRecipeDraft, revision: Revision): LocalPublishedFoodRecipeV1 = LocalPublishedFoodRecipeV1(
        journal.owner.scope.company(), draft.publicationId, draft.recipeId, revision, draft.outputSku.id, draft.outputSku.skuRevision,
        Quantity.parse(draft.outputSku.baseUnitId, draft.yieldQuantity, draft.outputSku.quantityScale).requirePositive(),
        Quantity.parse(draft.outputSku.baseUnitId, draft.portionQuantity, draft.outputSku.quantityScale).requirePositive(), draft.choices.map { choice ->
            LocalFoodRecipeChoiceV1(choice.id, choice.names.wire(), choice.modifierIds.toList(), choice.ingredients.map { component ->
                LocalFoodIngredientDefinitionV1(component.componentId, component.sku.id, component.sku.skuRevision,
                    Quantity.parse(component.sku.baseUnitId, component.quantityPerYield, component.sku.quantityScale).requirePositive())
            }, choice.routeKeys.toList(), choice.pricingSku.id)
        }, true)

    private fun preview(value: LocalPublishedFoodRecipeV1): LocalFoodRecipePreview = LocalFoodRecipePreview(value.publicationId,
        value.recipeId, value.revision, value.outputSkuId, value.outputSkuRevision, value.yieldQuantity, value.portionQuantity,
        value.choices.map { choice -> LocalFoodRecipeChoicePreview(choice.id, choice.names.ui(choice.id), choice.modifierIds.toList(),
            choice.ingredients.map { LocalFoodRecipeIngredientPreview(it.componentId, it.skuId, it.skuRevision, it.quantityPerYield) },
            choice.routeKeys.toList(), choice.pricingSkuId) }, value.active)

    private data class FoodCut(val page: LocalFoodConfigurationPageV1, val reply: ReadReply.Data)
    private suspend fun foodRead(access: GatewayAccess, query: LocalFoodConfigurationQueryV1): FoodCut {
        val reply = access.read(LocalFoodConfigurationReadCodecV1.read(query))
        val page = LocalFoodConfigurationReadCodecV1.result(reply.payload)
        val after = query.after
        if (page.kind != query.kind || page.labels.size > query.limit || page.labels.map { it.id.value } != page.labels.map { it.id.value }.sorted() ||
            after != null && page.labels.any { it.id.value <= after.value }) unavailable()
        if (query.id == null) {
            if (page.detail != null || page.nextAfter != null && page.nextAfter != page.labels.lastOrNull()?.id) unavailable()
        } else {
            if (page.nextAfter != null || page.labels.any { it.id != query.id } || (page.detail == null) != page.labels.isEmpty()) unavailable()
            if (page.detail != null) foodDetail(page) // Bind complete detail to its selected label and kind.
        }
        return FoodCut(page, reply)
    }

    private suspend fun detail(access: GatewayAccess, kind: LocalFoodConfigurationSourceKind, id: DomainId): LocalFoodConfigurationDetail? {
        if (kind != LocalFoodConfigurationSourceKind.WAREHOUSE) return foodDetail(foodRead(access,
            LocalFoodConfigurationQueryV1(kind.wire(), id = id)).page)
        val page = configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, id = id, limit = 1)).page
        if (page.items.isEmpty() && page.detail == null) return null
        val row = page.items.singleOrNull() ?: unavailable()
        val warehouse = (page.detail as? LocalConfigurationDetailV1.Warehouse)?.value ?: unavailable()
        if (row.id != id || warehouse.id != id || row.names != warehouse.names || row.active != warehouse.active) unavailable()
        return LocalFoodConfigurationDetail.Warehouse(LocalFoodWarehouseSource(id, row.storageRevision, warehouse.names.ui(id), warehouse.active))
    }

    private fun foodDetail(page: LocalFoodConfigurationPageV1): LocalFoodConfigurationDetail? {
        val value = page.detail ?: return null
        val row = page.labels.singleOrNull() ?: unavailable()
        return when (value) {
            is LocalFoodConfigurationDetailV1.Sku -> {
                val sku = value.definition
                if (page.kind != LocalFoodConfigurationSourceKindV1.SKU || sku.company != journal.owner.scope.company() ||
                    row.id != sku.skuId || row.revision != sku.skuRevision || row.names != sku.names || row.active != sku.active) unavailable()
                LocalFoodConfigurationDetail.Sku(LocalFoodSkuSource(sku.skuId, sku.skuRevision, row.storageRevision, sku.productId,
                    sku.baseUnitId, sku.quantityScale, sku.names.ui(sku.skuId), when (sku.tracking) {
                        LocalTrackingV1.QUANTITY -> LocalCompanyStockTracking.QUANTITY
                        LocalTrackingV1.LOT -> LocalCompanyStockTracking.LOT
                        LocalTrackingV1.SERIAL -> LocalCompanyStockTracking.SERIAL
                        LocalTrackingV1.NONE -> null
                    }, when (sku.fulfillment) {
                        LocalFulfillmentV1.RETAIL -> LocalCompanySkuFulfillment.RETAIL
                        LocalFulfillmentV1.MADE_TO_ORDER -> LocalCompanySkuFulfillment.MADE_TO_ORDER
                        else -> null
                    }, sku.active, sku.canSell, sku.canPurchase))
            }
            is LocalFoodConfigurationDetailV1.Recipe -> {
                val recipe = value.definition
                if (page.kind != LocalFoodConfigurationSourceKindV1.RECIPE || recipe.company != journal.owner.scope.company() ||
                    row.id != recipe.recipeId || row.revision != recipe.revision || row.names != recipe.choices.first().names || row.active != recipe.active) unavailable()
                LocalFoodConfigurationDetail.Recipe(LocalFoodRecipeSource(recipe.recipeId, recipe.revision, row.storageRevision,
                    recipe.outputSkuId, row.names.ui(recipe.recipeId), recipe.choices.flatMap { it.routeKeys }.distinct(), recipe.active))
            }
            is LocalFoodConfigurationDetailV1.Station -> {
                val station = value.definition
                if (page.kind != LocalFoodConfigurationSourceKindV1.STATION || row.id != station.id || row.revision != row.storageRevision ||
                    row.names != station.names || row.active != station.active) unavailable()
                LocalFoodConfigurationDetail.Station(LocalFoodStationSource(station.id, row.storageRevision, station.names.ui(station.id),
                    if (station.mode == FoodStationDeliveryMode.DISPLAY) LocalFoodConfigurationStationMode.DISPLAY else LocalFoodConfigurationStationMode.PAPER, station.active))
            }
            is LocalFoodConfigurationDetailV1.Route -> {
                val route = value.definition
                if (page.kind != LocalFoodConfigurationSourceKindV1.ROUTE || row.id != route.id || row.revision != row.storageRevision || row.active != route.active) unavailable()
                LocalFoodConfigurationDetail.Route(LocalFoodRouteSource(route.id, row.storageRevision, route.recipeId, route.recipeRevision,
                    if (route.activity == LocalSalesActivity.RESTAURANT) LocalFoodConfigurationActivity.RESTAURANT else LocalFoodConfigurationActivity.CAFE,
                    when (route.channel) {
                        LocalSalesChannel.DINE_IN -> LocalFoodConfigurationChannel.DINE_IN
                        LocalSalesChannel.COUNTER -> LocalFoodConfigurationChannel.COUNTER
                        LocalSalesChannel.TAKEAWAY -> LocalFoodConfigurationChannel.TAKEAWAY
                        LocalSalesChannel.DELIVERY -> LocalFoodConfigurationChannel.DELIVERY
                        else -> unavailable()
                    }, route.warehouseId, route.targets.associate { it.routeKey to it.stationId }, route.active))
            }
        }
    }

    private suspend fun readResult(body: suspend (GatewayAccess) -> LocalFoodConfigurationReadResult): LocalFoodConfigurationReadResult {
        try { return withAccess(body) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: GatewayFailure) { return LocalFoodConfigurationReadResult.Unavailable(failure.problem) }
        catch (_: Exception) { return LocalFoodConfigurationReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }
    private suspend fun recipeResult(body: suspend (GatewayAccess) -> LocalFoodRecipeResult): LocalFoodRecipeResult {
        try { return withAccess(body) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: FoodPublicationFailure) { return LocalFoodRecipeResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { return LocalFoodRecipeResult.Unavailable(failure.problem) }
        catch (_: IllegalArgumentException) { return LocalFoodRecipeResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
        catch (_: ArithmeticException) { return LocalFoodRecipeResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
        catch (_: Exception) { return LocalFoodRecipeResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }
    private suspend fun <T> withAccess(body: suspend (GatewayAccess) -> T): T {
        currentCoroutineContext().ensureActive()
        val lease = credentials.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        try {
            if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val view = when (val current = endpoint.current(lease.credentials)) {
                is LocalSessionViewResult.Current -> current.view
                LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            }
            if (!lease.isCurrent() || view.actorId != journal.owner.actorId || view.scope != journal.owner.scope || view.deviceId != journal.owner.deviceId)
                throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val access = GatewayAccess(endpoint, lease, view, installed)
            val result = body(access)
            access.checkCurrent()
            return result
        } finally { lease.destroy() }
    }
    private fun <T> native(result: LocalCompanyAuthorityResult<T>): T = when (result) {
        is LocalCompanyAuthorityResult.Value -> result.value
        is LocalCompanyAuthorityResult.Unavailable -> throw FoodPublicationFailure(when (result.nativeReason) {
            SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED -> LocalServiceProblem.DENIED
            SelfOwnedInstallationFailureV1.THROTTLED, SelfOwnedInstallationFailureV1.BUSY, SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED -> LocalServiceProblem.RETRY_LATER
            SelfOwnedInstallationFailureV1.INVALID_INPUT -> LocalServiceProblem.INVALID_INPUT
            SelfOwnedInstallationFailureV1.STATE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
            else -> LocalServiceProblem.UNAVAILABLE
        }, result.nativeReason)
    }
    private fun pending(entry: StoredClientIntentV1, allowed: Boolean): LocalPendingOperation = LocalPendingOperation(entry.operationId,
        entry.localAction, entry.label.ui(), entry.summary.map { it.ui() }, allowed && entry.recorded == null && !entry.acknowledged,
        entry.localPhase, entry.recorded?.let { if (it.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED })
    private fun requestDigest(request: MutationRequest): String {
        val bytes = request.payload.copyBytes()
        try { return sha256(bytes).value } finally { bytes.fill(0) }
    }
    private fun LocalNamesV1?.ui(id: DomainId): LocalText = this?.let { LocalText(it.english, it.arabic) } ?: literal(id.value)
    private fun LocalText.wire(): LocalNamesV1 = LocalNamesV1(english, arabic)
    private fun LocalFoodConfigurationSourceKind.wire(): LocalFoodConfigurationSourceKindV1 = when (this) {
        LocalFoodConfigurationSourceKind.SKU -> LocalFoodConfigurationSourceKindV1.SKU
        LocalFoodConfigurationSourceKind.RECIPE -> LocalFoodConfigurationSourceKindV1.RECIPE
        LocalFoodConfigurationSourceKind.STATION -> LocalFoodConfigurationSourceKindV1.STATION
        LocalFoodConfigurationSourceKind.ROUTE -> LocalFoodConfigurationSourceKindV1.ROUTE
        LocalFoodConfigurationSourceKind.WAREHOUSE -> invalid()
    }
    private fun LocalFoodConfigurationStationMode.wire(): FoodStationDeliveryMode = if (this == LocalFoodConfigurationStationMode.DISPLAY)
        FoodStationDeliveryMode.DISPLAY else FoodStationDeliveryMode.PAPER
    private fun LocalFoodConfigurationActivity.wire(): LocalSalesActivity = if (this == LocalFoodConfigurationActivity.RESTAURANT)
        LocalSalesActivity.RESTAURANT else LocalSalesActivity.CAFE
    private fun LocalFoodConfigurationChannel.wire(): LocalSalesChannel = when (this) {
        LocalFoodConfigurationChannel.DINE_IN -> LocalSalesChannel.DINE_IN
        LocalFoodConfigurationChannel.COUNTER -> LocalSalesChannel.COUNTER
        LocalFoodConfigurationChannel.TAKEAWAY -> LocalSalesChannel.TAKEAWAY
        LocalFoodConfigurationChannel.DELIVERY -> LocalSalesChannel.DELIVERY
    }
}

internal fun foodConfigurationAction(request: LocalFoodConfigurationRequestV1): LocalAction = when (request) {
    is LocalFoodConfigurationRequestV1.InstallRecipe -> LocalAction.RECIPE_SET
    is LocalFoodConfigurationRequestV1.Station -> LocalAction.FOOD_STATION_CONFIGURE
    is LocalFoodConfigurationRequestV1.Route -> LocalAction.FOOD_ROUTE_CONFIGURE
}
internal fun foodConfigurationTargetId(request: LocalFoodConfigurationRequestV1): DomainId = when (request) {
    is LocalFoodConfigurationRequestV1.InstallRecipe -> request.recipe.definition.recipeId
    is LocalFoodConfigurationRequestV1.Station -> request.station.id
    is LocalFoodConfigurationRequestV1.Route -> request.route.id
}
private class FoodPublicationFailure(val problem: LocalServiceProblem, val nativeReason: SelfOwnedInstallationFailureV1?) : Exception()
