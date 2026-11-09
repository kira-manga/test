package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockCompositeVerifier
import com.ukkera.pos.pricing.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.FoodStationDeliveryMode
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/**
 * Real local selected recipe/option/station/price source. Reads the same activated signed SKU
 * store, branch pricing history, and current food routes in the borrowed writer transaction.
 * Nothing comes from a caller's recipe cache and no stock or paid proof is fabricated here.
 * Advisory and recipe quotation capture do not require fulfillment; routed V1 preparation does.
 */
public class LocalCatalogFoodSource(private val signatures: CompanyPublicationSignatureVerifier) :
    TransactionFoodRecipeSourceV1, TransactionFoodRecipeQuotationVerificationSourceV1, TransactionFoodRecipeRoutingVerificationSource {
    override suspend fun prepareKitchenExecution(view: CommandReadView, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, snapshots: List<LocalFoodRecipeSnapshotV1>): PreparedMutationAdmissionRestriction? {
        if (activity !in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE) ||
            service.channel !in setOf(LocalSalesChannel.DINE_IN, LocalSalesChannel.COUNTER, LocalSalesChannel.TAKEAWAY) ||
            snapshots.isEmpty()) return PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
        // Public/copy views cannot establish this source. Refuse them without consulting
        // configuration or trust; actual ingress separately validates its retained history.
        if (view.localFoodOwnedDisplayDelivery() == null)
            return PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
        check(snapshots.size <= LocalFoodProfileV1.MAX_ORDER_PORTIONS &&
            snapshots.map { it.portionId }.distinct().size == snapshots.size)
        var covered = true
        for (snapshot in snapshots) {
            // These are the actual stored work identities. A malformed retained recipe/history
            // must still fail explicitly, even when another route is currently unavailable.
            check(snapshot.activity == activity && snapshot.channel == service.channel)
            val current = currentForSend(view, snapshot)
            if (!current || snapshot.routes.any { it.mode != FoodStationDeliveryMode.DISPLAY }) covered = false
        }
        if (!covered) return PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE
        // Signed routes alone prove no delivery. Only this same loan's concrete registered
        // local consumer may establish support; PAPER, DELIVERY and unowned views stay held.
        return null
    }

    override suspend fun prepareSelectionPreview(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodSelectionPreviewV1? {
        val captured = captureSelection(view, activity, service, catalogRevision, portions, FoodSelectionCapturePurpose.ADVISORY) ?: return null
        return object : PreparedFoodSelectionPreviewV1 {
            override val scope: BranchScope = captured.scope
            override fun quote(snapshotId: DomainId, observedAt: UtcInstant): PricingSnapshot =
                captured.quote(snapshotId, observedAt).quote
        }
    }

    override suspend fun prepareQuotation(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodRecipeQuotationBatchV1? {
        val captured = captureSelection(view, activity, service, catalogRevision, portions, FoodSelectionCapturePurpose.QUOTATION) ?: return null
        val quotations = captured.selections.map { checkNotNull(it.quotation) }
        return object : PreparedFoodRecipeQuotationBatchV1 {
            override val scope: BranchScope = captured.scope
            override val recoveryResources: Set<RecoveryResourceKey> = captured.recoveryResources
            override fun quote(batchId: DomainId, observedAt: UtcInstant): LocalFoodRecipeQuotationBatchV1 {
                val price = captured.quote(batchId, observedAt)
                return LocalFoodRecipeQuotationBatchV1(price.quote, quotations, price.sources)
            }
        }
    }

    /** Current routing facts only. This neither binds stored work nor grants execution support. */
    override suspend fun prepareRouting(view: CommandReadView,
        quotation: LocalFoodRecipeQuotationV1): PreparedFoodRecipeRouting? {
        val observed = FoodCatalogueResourceRead(view)
        // Authenticate the complete original quotation before consulting replacement/current
        // routing. Its immutable publication references are never rebuilt from current prices.
        requireHistoricalQuotation(observed, quotation)
        val current = foodConfiguration(observed, LocalFoodConfigurationProfileV1.RECIPE, quotation.recipeId, signatures)
            ?: return null
        val signed = (current.second.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe
            ?: error("food.routing.current_recipe")
        val recipe = signed.definition
        if (!recipe.active || recipe.revision != quotation.recipeRevision || recipe.publicationId != quotation.publicationId)
            return null
        check(LocalFoodConfigurationCodecV1.signedPublication(signed) == quotation.signedPublication) {
            "food.routing.current_publication_mismatch"
        }
        val routing = captureFoodRouting(observed, quotation.recipeId, quotation.recipeRevision,
            quotation.activity, quotation.channel, quotation.routeKeys, signatures) ?: return null
        val snapshot = FoodRecipeRouting.fromQuotation(quotation, routing.routeVersionId,
            routing.warehouseId, routing.routes, routing.sources)
        // Reuse the real routed applicability checks for the original output/ingredient
        // revisions and every captured route/station version, with no pricing/cursor read.
        if (!currentForSend(observed, snapshot)) return null
        return PreparedFoodRecipeRouting(view.scope, snapshot,
            observed.resources + routing.resources + foodRoutingSnapshotResources(snapshot))
    }

    override suspend fun requireOriginalRouting(sources: LocalCommerceOriginalSources,
        quotation: LocalFoodRecipeQuotationV1, snapshot: LocalFoodRecipeSnapshotV1): Set<RecoveryResourceKey> =
        originalFoodRouting(sources, signatures, quotation, snapshot)

    override suspend fun requireHistoricalQuotation(view: CommandReadView, quotation: LocalFoodRecipeQuotationV1) {
        val signed = requireSignedFoodQuotation(quotation, view.scope, signatures)
        val skus = linkedMapOf<DomainId, LocalPublishedSkuV1>()
        val skuReferences = linkedMapOf<DomainId, LocalFoodSourceReferenceV1>()
        var recipeReference: LocalFoodSourceReferenceV1? = null
        for (reference in quotation.sources) {
            when (reference.kind) {
                LocalFoodConfigurationProfileV1.PUBLICATION -> {
                    val (row, record) = foodConfiguration(view, reference.kind, reference.id, signatures)
                        ?: error("food.quotation.recipe_history_missing")
                    val publication = (record.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe
                        ?: error("food.quotation.recipe_history_kind")
                    val actual = LocalFoodSourceReferenceV1(row.key.kind, row.key.id, row.revision, row.digest,
                        record.actorId, publication.signingKeyId, record.operationId)
                    check(recipeReference == null && reference == actual && publication == signed)
                    recipeReference = actual
                }
                LocalConfigurationProfile.PUBLICATION -> {
                    val (row, record) = readSignedSku(view, reference.kind, reference.id, signatures)
                        ?: error("food.quotation.sku_history_missing")
                    val publication = record.signedSku()
                    val actual = LocalFoodSourceReferenceV1(row.key.kind, row.key.id, row.revision, row.digest,
                        record.authorizedBy, publication.signingKeyId)
                    check(row.revision == Revision(1) && reference == actual && sha256(row.payload.copyBytes()) == row.digest)
                    check(skus.put(publication.definition.skuId, publication.definition) == null)
                    skuReferences[publication.definition.skuId] = actual
                }
                else -> error("food.quotation.history_kind")
            }
        }
        val recipe = signed.definition
        val choice = recipe.choices.single { it.id == quotation.choiceId }
        val ids = (listOf(recipe.outputSkuId, choice.pricingSkuId) + choice.ingredients.map { it.skuId }).distinct()
        check(skus.keys == ids.toSet() && validFoodDefinition(recipe.copy(choices = listOf(choice)), skus))
        requireFoodQuotationProjection(quotation, foodRecipeQuotation(quotation.portionId, quotation.activity,
            quotation.channel, signed, choice, listOf(checkNotNull(recipeReference)) + ids.map { skuReferences.getValue(it) }))
    }

    override suspend fun requireOriginalHistoricalQuotation(sources: LocalCommerceOriginalSources,
        quotation: LocalFoodRecipeQuotationV1): Set<RecoveryResourceKey> =
        originalFoodQuotationHistorical(sources, signatures, quotation)

    override suspend fun requireOriginalQuotation(sources: LocalCommerceOriginalSources, activity: LocalSalesActivity,
        service: LocalFoodServiceV1, catalogRevision: Revision, selections: List<LocalFoodPortionSelectionV1>,
        quoted: LocalFoodRecipeQuotationBatchV1): Set<RecoveryResourceKey> =
        originalFoodQuotation(sources, signatures, activity, service, catalogRevision, selections, quoted)

    override suspend fun prepare(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>): PreparedFoodRecipeBatchV1? {
        val captured = captureSelection(view, activity, service, catalogRevision, portions, FoodSelectionCapturePurpose.ROUTED) ?: return null
        val snapshots = captured.selections.map { checkNotNull(it.snapshot) }
        return object : PreparedFoodRecipeBatchV1 {
            override val scope: BranchScope = captured.scope
            override val recoveryResources: Set<RecoveryResourceKey> = captured.recoveryResources
            override fun quote(batchId: DomainId, at: UtcInstant): LocalFoodQuotedBatchV1 {
                val price = captured.quote(batchId, at)
                return LocalFoodQuotedBatchV1(price.quote, snapshots, price.sources)
            }
        }
    }

    /** The private purpose distinguishes capture projections, never a caller-provided capability.
     * Every path validates authentic recipe/SKU/dimensional/pricing facts; only routed work
     * captures real route/warehouse/station identities. No borrowed view leaves this method. */
    private suspend fun captureSelection(view: CommandReadView, activity: LocalSalesActivity, service: LocalFoodServiceV1,
        catalogRevision: Revision, portions: List<LocalFoodPortionSelectionV1>, purpose: FoodSelectionCapturePurpose): CapturedFoodSelection? {
        if (activity !in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE) ||
            portions.size !in 1..LocalFoodProfileV1.MAX_PORTIONS_PER_COMMAND) return null
        // Preserve exact selection bytes/order while severing every caller-owned list before
        // the first suspend/read; later edits cannot alter this preparation's price identities.
        val selections = try { portions.map { it.copy(modifierIds = it.modifierIds.toList()) } }
        catch (_: IllegalArgumentException) { return null }
        val groups = try { LocalFoodSelectionGroups.group(selections) }
        catch (_: IllegalArgumentException) { return null }
        val groupQuantities = groups.associate { it.lineId to it.totalQuantity }
        val cursor = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.CATALOG_CURSOR, view.scope.branchId)) ?: return null
        val cursorValue = decodeRecord(view.scope, cursor)
        check(cursorValue.body is ConfigurationBody.CatalogCursor)
        if (cursorValue.revision != catalogRevision) return null
        val resources = mutableSetOf(foodConfigResource(cursor.key.id), foodConfigResource(cursorValue.authorizedBy))
        val captures = mutableListOf<FoodQuotedSelection>()
        var priceContexts = 0
        for (selected in selections) {
            val recipeRecord = foodConfiguration(view, LocalFoodConfigurationProfileV1.RECIPE, selected.recipeId, signatures) ?: return null
            val signed = (recipeRecord.second.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe ?: error("food.source.recipe")
            val recipe = signed.definition
            val choice = recipe.choices.singleOrNull { it.id == selected.choiceId } ?: return null
            if (!recipe.active || recipe.revision != selected.expectedRecipeRevision || recipe.outputSkuId != selected.itemId ||
                choice.modifierIds.toSet() != selected.modifierIds.toSet() || selected.quantity != recipe.portionQuantity) return null
            val pub = foodConfiguration(view, LocalFoodConfigurationProfileV1.PUBLICATION, recipe.publicationId, signatures)
                ?: error("food.source.publication_missing")
            check(pub.second == recipeRecord.second)
            val refs = linkedMapOf<AggregateKey, LocalFoodSourceReferenceV1>()
            fun ref(row: StoredAggregate, actor: DomainId, signingKeyId: DomainId? = null, operationId: OperationId? = null) {
                refs[row.key] = LocalFoodSourceReferenceV1(row.key.kind, row.key.id, row.revision, row.digest, actor, signingKeyId, operationId)
            }
            ref(pub.first, pub.second.actorId, signed.signingKeyId, pub.second.operationId)
            resources += foodConfigurationResources(recipeRecord.second)
            if (purpose == FoodSelectionCapturePurpose.QUOTATION) resources += foodConfigResource(pub.first.key.id)
            var routing: CapturedFoodRouting? = null
            if (purpose == FoodSelectionCapturePurpose.ROUTED) {
                routing = captureFoodRouting(view, recipe.recipeId, recipe.revision, activity,
                    service.channel, choice.routeKeys, signatures) ?: return null
                for (source in routing.sources) refs[AggregateKey(source.kind, source.id)] = source
                resources += routing.resources
            }
            val ids = (listOf(recipe.outputSkuId, choice.pricingSkuId) + choice.ingredients.map { it.skuId }).distinct()
            val definitions = linkedMapOf<DomainId, LocalPublishedSkuV1>()
            for (id in ids) {
                val sku = readSignedSku(view, LocalConfigurationProfile.SKU, id, signatures) ?: return null
                val definition = sku.second.signedSku().definition
                val publication = readSignedSku(view, LocalConfigurationProfile.PUBLICATION, definition.publicationId, signatures)
                    ?: error("food.source.sku_publication_missing")
                check(publication.second.signedSku() == sku.second.signedSku())
                definitions[id] = definition
                ref(publication.first, publication.second.authorizedBy, publication.second.signedSku().signingKeyId)
                resources += companySourceResources(sku.second, if (id == choice.pricingSkuId) definition.prices else emptyList())
                if (purpose == FoodSelectionCapturePurpose.QUOTATION)
                    resources += companySourceResources(publication.second, emptyList())
                resources += RecoveryResourceKey(RecoveryResourceKind.STOCK, id)
            }
            // Validate only the selected complete choice; unavailable alternatives do not alter it.
            if (!validFoodDefinition(recipe.copy(choices = listOf(choice)), definitions)) return null
            val pricingSku = definitions.getValue(choice.pricingSkuId)
            priceContexts += pricingSku.prices.size
            if (priceContexts > 128) return null
            val pricing = prepareLocalSkuPricing(view, pricingSku, signatures)
            resources += pricing.resources
            val description = listOf(choice.names.english, choice.names.arabic).filter { it.isNotBlank() }.joinToString(" / ")
            val ingredients = if (purpose == FoodSelectionCapturePurpose.ROUTED) foodRecipeIngredients(recipe, choice) else null
            val snapshot = if (purpose == FoodSelectionCapturePurpose.ROUTED) LocalFoodRecipeSnapshotV1(
                LocalFoodProfileV1.recipeSnapshotId(selected.portionId, recipe.publicationId, checkNotNull(routing).routeVersionId),
                selected.portionId, checkNotNull(routing).routeVersionId, activity, service.channel,
                recipe.recipeId, recipe.revision, recipe.publicationId, recipe.outputSkuId, recipe.outputSkuRevision,
                choice.id, choice.modifierIds.toList(), description, recipe.yieldQuantity, recipe.portionQuantity, choice.pricingSkuId,
                checkNotNull(ingredients),
                checkNotNull(routing).warehouseId, checkNotNull(routing).routes,
                LocalFoodConfigurationCodecV1.signedPublication(signed), refs.values.toList()) else null
            val quotation = if (purpose == FoodSelectionCapturePurpose.QUOTATION)
                foodRecipeQuotation(selected.portionId, activity, service.channel, signed, choice, refs.values.toList()) else null
            captures += FoodQuotedSelection(selected, snapshot, quotation, pricing)
            resources += foodConfigResource(signed.signingKeyId)
            if (snapshot != null) resources += RecoveryResourceKey(RecoveryResourceKind.ORDER_CUSTODY, snapshot.snapshotId)
        }
        return CapturedFoodSelection(view.scope, captures.toList(), resources.toSet(),
            service.channel, groupQuantities.toMap())
    }

    override suspend fun currentForSend(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1): Boolean {
        requireHistorical(view, snapshot)
        val current = foodConfiguration(view, LocalFoodConfigurationProfileV1.RECIPE, snapshot.recipeId, signatures) ?: return false
        val recipe = (current.second.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe.definition
        if (!recipe.active || recipe.revision != snapshot.recipeRevision || recipe.publicationId != snapshot.publicationId) return false
        for (source in snapshot.sources.filter { it.kind == LocalFoodConfigurationProfileV1.VERSION }) {
            val version = foodConfiguration(view, source.kind, source.id, signatures) ?: error("food.source.version_missing")
            val latest = foodConfiguration(view, version.second.kind, version.second.id, signatures) ?: return false
            if (latest.second.operationId != version.second.operationId) return false
            when (val value = latest.second.request) {
                is LocalFoodConfigurationRequestV1.Route -> if (!value.route.active) return false
                is LocalFoodConfigurationRequestV1.Station -> if (!value.station.active) return false
                else -> error("food.source.version_kind")
            }
        }
        for ((skuId, revision) in (listOf(snapshot.outputSkuId to snapshot.outputSkuRevision) + snapshot.ingredients.map { it.skuId to it.skuRevision })) {
            val sku = readSignedSku(view, LocalConfigurationProfile.SKU, skuId, signatures)?.second?.signedSku()?.definition ?: return false
            if (!sku.active || sku.skuRevision != revision) return false
        }
        val warehouse = view.aggregate(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, snapshot.warehouseId))?.let { decodeRecord(view.scope, it) }
        return (warehouse?.body as? ConfigurationBody.Warehouse)?.value?.active == true
    }

    override suspend fun requireHistorical(view: CommandReadView, snapshot: LocalFoodRecipeSnapshotV1) {
        requireSignedFoodSnapshot(snapshot, view.scope, signatures)
        val configurations = mutableListOf<FoodConfigurationRecordV1>()
        val skus = mutableMapOf<DomainId, LocalPublishedSkuV1>()
        for (source in snapshot.sources) {
            val key = AggregateKey(source.kind, source.id)
            when (source.kind) {
                LocalFoodConfigurationProfileV1.PUBLICATION, LocalFoodConfigurationProfileV1.VERSION -> {
                    val record = foodConfiguration(view, source.kind, source.id, signatures) ?: error("food.source.history_missing")
                    check(record.first.revision == source.revision && record.first.digest == source.digest &&
                        record.second.operationId == source.operationId && record.second.actorId == source.authorizedBy &&
                        (record.second.request as? LocalFoodConfigurationRequestV1.InstallRecipe)?.recipe?.signingKeyId == source.signingKeyId)
                    check(if (source.kind == LocalFoodConfigurationProfileV1.PUBLICATION)
                        source.id == snapshot.publicationId && record.second.request is LocalFoodConfigurationRequestV1.InstallRecipe
                    else record.second.request is LocalFoodConfigurationRequestV1.Route || record.second.request is LocalFoodConfigurationRequestV1.Station)
                    configurations += record.second
                }
                LocalConfigurationProfile.PUBLICATION -> {
                    val record = readSignedSku(view, source.kind, source.id, signatures) ?: error("food.source.sku_history_missing")
                    check(record.first.key == key && record.first.revision == source.revision && record.first.digest == source.digest &&
                        source.operationId == null && record.second.authorizedBy == source.authorizedBy &&
                        record.second.signedSku().signingKeyId == source.signingKeyId)
                    val sku = record.second.signedSku().definition
                    check(skus.put(sku.skuId, sku) == null)
                }
                else -> error("food.source.history_kind")
            }
        }
        val publication = configurations.single { it.request is LocalFoodConfigurationRequestV1.InstallRecipe }
        val signed = (publication.request as LocalFoodConfigurationRequestV1.InstallRecipe).recipe
        check(LocalFoodConfigurationCodecV1.signedPublication(signed) == snapshot.signedPublication)
        val choice = signed.definition.choices.single { it.id == snapshot.choiceId }
        check(skus.keys == (listOf(snapshot.outputSkuId, snapshot.pricingSkuId) + snapshot.ingredients.map { it.skuId }).toSet() &&
            validFoodDefinition(signed.definition.copy(choices = listOf(choice)), skus))
        val routeRecord = configurations.single { it.request is LocalFoodConfigurationRequestV1.Route }
        val route = (routeRecord.request as LocalFoodConfigurationRequestV1.Route).route
        check(routeRecord.operationId.value == snapshot.routeVersionId.value && route.recipeId == snapshot.recipeId &&
            route.recipeRevision == snapshot.recipeRevision && route.warehouseId == snapshot.warehouseId && route.active &&
            route.activity == snapshot.activity && route.channel == snapshot.channel)
        val stations = configurations.filter { it.request is LocalFoodConfigurationRequestV1.Station }
        check(stations.size == snapshot.routes.size)
        for (station in snapshot.routes) {
            check(route.targets.single { it.routeKey == station.routeKey }.stationId == station.stationId)
            val record = stations.single { it.id == station.stationId }
            val definition = (record.request as LocalFoodConfigurationRequestV1.Station).station
            check(record.revision == station.stationRevision && definition.mode == station.mode && definition.active)
        }
    }
}

/** Shared exact base-unit projection; distinct components remain distinct even for the same SKU. */
internal fun foodRecipeIngredients(recipe: LocalPublishedFoodRecipeV1,
    choice: LocalFoodRecipeChoiceV1): List<LocalFoodIngredientV1> = choice.ingredients.map { component ->
    LocalFoodIngredientV1(component.componentId, component.skuId, component.skuRevision,
        component.quantityPerYield, Quantity(component.quantityPerYield.unitId,
            (component.quantityPerYield.toFraction() * recipe.portionQuantity.toFraction() / recipe.yieldQuantity.toFraction())
                .toScaledLongExact(component.quantityPerYield.scale), component.quantityPerYield.scale))
}

/** Pure fact projection shared by capture and source checks; never an authority token. */
internal fun foodRecipeQuotation(portionId: DomainId, activity: LocalSalesActivity, channel: LocalSalesChannel,
    signed: LocalSignedFoodRecipeV1, choice: LocalFoodRecipeChoiceV1,
    sources: List<LocalFoodSourceReferenceV1>): LocalFoodRecipeQuotationV1 {
    val recipe = signed.definition
    return LocalFoodRecipeQuotationV1(LocalFoodQuotationProfileV1.quotationId(portionId, recipe.publicationId), portionId,
        activity, channel, recipe.recipeId, recipe.revision, recipe.publicationId, recipe.outputSkuId, recipe.outputSkuRevision,
        choice.id, choice.modifierIds.toList(), listOf(choice.names.english, choice.names.arabic).filter { it.isNotBlank() }.joinToString(" / "),
        recipe.yieldQuantity, recipe.portionQuantity, choice.pricingSkuId, foodRecipeIngredients(recipe, choice),
        choice.routeKeys.toList(), LocalFoodConfigurationCodecV1.signedPublication(signed), sources)
}

internal fun requireFoodQuotationProjection(actual: LocalFoodRecipeQuotationV1, expected: LocalFoodRecipeQuotationV1) {
    check(encodeValue(actual, LocalFoodRecipeQuotationV1.serializer()) == encodeValue(expected, LocalFoodRecipeQuotationV1.serializer())) {
        "food.quotation.recipe_projection_mismatch"
    }
}

/** Signature and complete signed recipe projection only; immutable source membership is checked separately. */
internal fun requireSignedFoodQuotation(quotation: LocalFoodRecipeQuotationV1, scope: BranchScope,
    signatures: CompanyPublicationSignatureVerifier): LocalSignedFoodRecipeV1 {
    val signed = localConfigurationJson.decodeFromString(LocalSignedFoodRecipeV1.serializer(), quotation.signedPublication)
    check(LocalFoodConfigurationCodecV1.signedPublication(signed) == quotation.signedPublication && signed.definition.company == scope.company() &&
        signatures.verify(signed.definition.company, signed.signingKeyId, LocalFoodConfigurationCodecV1.canonicalPublication(signed.definition), signed.signatureHex))
    check(signed.definition.active && quotation.sources.count { it.kind == LocalFoodConfigurationProfileV1.PUBLICATION &&
        it.id == signed.definition.publicationId && it.signingKeyId == signed.signingKeyId && it.operationId != null } == 1)
    val choice = signed.definition.choices.single { it.id == quotation.choiceId }
    requireFoodQuotationProjection(quotation, foodRecipeQuotation(quotation.portionId, quotation.activity,
        quotation.channel, signed, choice, quotation.sources))
    return signed
}

private enum class FoodSelectionCapturePurpose { ADVISORY, QUOTATION, ROUTED }

private class FoodQuotedSelection(val selection: LocalFoodPortionSelectionV1, val snapshot: LocalFoodRecipeSnapshotV1?,
    val quotation: LocalFoodRecipeQuotationV1?, val pricing: PreparedLocalSkuPricing)

private class FoodSelectionPrice(val quote: PricingSnapshot, val sources: List<LocalSalesPriceSourceV2>)

/** Selected immutable facts only. The original signed publication readers have finished. */
private class CapturedFoodSelection(
    val scope: BranchScope, val selections: List<FoodQuotedSelection>, val recoveryResources: Set<RecoveryResourceKey>,
    private val channel: LocalSalesChannel, private val groupQuantities: Map<DomainId, Quantity>,
) {
    fun quote(snapshotId: DomainId, at: UtcInstant): FoodSelectionPrice {
        val selected = selections.map { it to it.pricing.resolve(channel.name,
            groupQuantities.getValue(it.selection.commercialLineId), at) }
        val resolved = selected.map { (capture, price) -> ResolvedPriceLine(capture.selection.commercialLineId,
            capture.selection.portionId, price.company.revision, capture.selection.quantity, price.effectiveUnitPrice,
            price.company.inclusion, price.company.tax) }
        val quote = try { OperationalPricingEngine.calculate(PricingRequest(snapshotId, Revision(0), Currency.EGP, resolved)) }
        catch (failure: PricingRejectedException) { throw LocalSalesQuoteUnavailable(
            if (failure.failure == PricingFailure.ARITHMETIC_OR_POSTING_LIMIT) LocalSalesQuoteUnavailableReason.CAPACITY_REQUIRED
            else LocalSalesQuoteUnavailableReason.UNSUPPORTED_SELECTOR) }
        return FoodSelectionPrice(quote, selected.map { (capture, price) ->
            price.provenance(capture.selection.commercialLineId, capture.selection.portionId)
        })
    }
}

/** Mandatory companion of the SAME normalized stock repository, installed only in protected process composition. */
public class LocalCatalogFoodStockVerifier(private val signatures: CompanyPublicationSignatureVerifier) : LocalStockCompositeVerifier {
    override val commands: Set<CommandKey> = setOf(LocalFoodProfileV1.COMMAND)
    override fun requireComplete(evidence: RetainedCommandEvidence) {
        LocalFoodProducer.requireComplete(evidence).forEach { requireSignedFoodSnapshot(it, evidence.scope, signatures) }
    }
}

internal fun requireSignedFoodSnapshot(snapshot: LocalFoodRecipeSnapshotV1, scope: BranchScope, signatures: CompanyPublicationSignatureVerifier) {
    val signed = localConfigurationJson.decodeFromString(LocalSignedFoodRecipeV1.serializer(), snapshot.signedPublication)
    check(LocalFoodConfigurationCodecV1.signedPublication(signed) == snapshot.signedPublication && signed.definition.company == scope.company() &&
        signatures.verify(signed.definition.company, signed.signingKeyId, LocalFoodConfigurationCodecV1.canonicalPublication(signed.definition), signed.signatureHex))
    val recipe = signed.definition
    val choice = recipe.choices.single { it.id == snapshot.choiceId }
    check(snapshot.snapshotId == LocalFoodProfileV1.recipeSnapshotId(snapshot.portionId, snapshot.publicationId, snapshot.routeVersionId) &&
        snapshot.sources.count { it.kind == LocalFoodConfigurationProfileV1.PUBLICATION && it.id == snapshot.publicationId &&
            it.signingKeyId == signed.signingKeyId } == 1 &&
        snapshot.sources.count { it.kind == LocalFoodConfigurationProfileV1.VERSION && it.id == snapshot.routeVersionId } == 1)
    check(recipe.active && recipe.recipeId == snapshot.recipeId && recipe.revision == snapshot.recipeRevision &&
        recipe.publicationId == snapshot.publicationId && recipe.outputSkuId == snapshot.outputSkuId &&
        recipe.outputSkuRevision == snapshot.outputSkuRevision && recipe.yieldQuantity == snapshot.yieldQuantity && recipe.portionQuantity == snapshot.portionQuantity &&
        choice.modifierIds == snapshot.modifierIds && choice.pricingSkuId == snapshot.pricingSkuId &&
        choice.routeKeys == snapshot.routes.map { it.routeKey } &&
        choice.ingredients == snapshot.ingredients.map { LocalFoodIngredientDefinitionV1(it.componentId, it.skuId, it.skuRevision, it.quantityPerYield) } &&
        snapshot.description == listOf(choice.names.english, choice.names.arabic).filter { it.isNotBlank() }.joinToString(" / "))
}
