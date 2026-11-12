package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpoint
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Exact native company publication plus a separate branch activation journal. No constructor
 * accepts an employee principal, signing key, root anchor, raw signed request or approval callback.
 * This borrows the actual service/native lifetime; UI lock/close never closes either owner.
 */
public class LocalEndpointCompanyCatalogGateway(
    private val publications: LocalCompanyBusinessPublications,
    private val endpoint: LocalMasterClient,
    private val journal: LocalClientIntentJournal,
    private val credentials: LocalWorkflowCredentialSource,
    private val installed: LocalWorkflowInstallation,
) : LocalCompanyCatalogGateway {
    // This is NOT the financial journal mutex. Head entry/review never locks ordinary session work.
    private val actions = Mutex()
    override public val configured: Boolean = true
    override val foodConfiguration: LocalFoodConfigurationGateway = LocalEndpointFoodConfigurationGateway(
        publications, endpoint, journal, credentials, installed)
    override val returnRights: LocalReturnRightsPublicationGateway = LocalEndpointReturnRightsPublicationGateway(
        publications, endpoint, journal, credentials, installed)

    override val pricePolicies: LocalPricePolicyPublicationGateway = LocalEndpointPricePolicyPublicationGateway(
        publications, endpoint, journal, credentials, installed)
    override val releases: LocalCatalogReleasePublicationGateway = LocalEndpointCatalogReleasePublicationGateway(
        publications, endpoint, journal, credentials, installed)

    override val serialRegistrations: LocalSerialRegistrationPublicationGateway =
        (endpoint as? LocalMasterEndpoint)?.serialPublications?.let { serial ->
            LocalEndpointSerialRegistrationPublicationGateway(publications, endpoint, journal, credentials, installed, serial)
        } ?: UnavailableLocalSerialRegistrationPublication

    init { require(publications.trust.company == journal.owner.scope.company()) }

    override suspend fun status(): LocalCompanyCatalogStatusResult = actions.withLock {
        try { withAccess { access ->
            val cut = catalogAdmission(access)
            val references = native(publications.retained()).filter { it.kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1 }
            LocalCompanyCatalogStatusResult.Available(LocalCompanyCatalogStatus(references,
                access.allow(LocalAction.CATALOG_ACTIVATE), cut.reply.observedAt, cut.page.catalogRevision))
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CompanyCatalogFailure) { LocalCompanyCatalogStatusResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { LocalCompanyCatalogStatusResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalCompanyCatalogStatusResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun prepare(draft: LocalCompanySkuDraft): LocalCompanyCatalogReviewResult = prepareSku(draft, NativeSkuCapability.RETAIL)
    override suspend fun prepareIngredient(draft: LocalCompanyIngredientSkuDraft): LocalCompanyCatalogReviewResult =
        prepareSku(draft.sku, NativeSkuCapability.INGREDIENT)
    override suspend fun prepareFoodOutput(draft: LocalCompanyFoodOutputSkuDraft): LocalCompanyCatalogReviewResult =
        prepareSku(draft.sku, NativeSkuCapability.FOOD_OUTPUT)

    private suspend fun prepareSku(input: LocalCompanySkuDraft, capability: NativeSkuCapability): LocalCompanyCatalogReviewResult = actions.withLock {
        try { withAccess { access ->
            // Revalidate one copied public-input cut; callers cannot mutate a retained draft's
            // lists later and silently rebind a conversion to another product at mapping.
            require(input.variantValues.size <= 16)
            val draft = input.copy(barcodes = input.barcodes.toList(), unitDefinitions = input.unitDefinitions?.toList(),
                conversions = input.conversions.toList(), productDefinition = input.productDefinition?.snapshot(),
                variantValues = input.variantValues.toList())
            val cut = catalogAdmission(access)
            access.requireAction(LocalAction.CATALOG_ACTIVATE, draft.skuId)
            if (journal.requests.withLock { required(journal.pending()).isNotEmpty() }) {
                throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            }
            val existing = native(publications.retained()).singleOrNull { it.publicationId == draft.publicationId }
            if (existing != null) {
                if (existing.kind != CompanyBusinessPublicationKind.CATALOG_SKU_V1) throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
                val result = retainedReview(existing.originalActionId)
                val retainedPreview = when (result) {
                    is LocalCompanyCatalogReviewResult.Review -> result.review.preview
                    is LocalCompanyCatalogReviewResult.Completed -> result.publication.preview
                    is LocalCompanyCatalogReviewResult.Unavailable -> return@withAccess result
                }
                // A changed draft cannot rewrite the retained publication, price time or revisions.
                if (retainedPreview.variantValues == null && draft.variantValues.isNotEmpty())
                    throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
                val retainedProduct = retainedPreview.productDefinition?.let { productWire(draft.productId, it.revision, it.definition) }
                val enteredProduct = draft.productDefinition
                if (enteredProduct != null && (retainedProduct == null ||
                    productWire(draft.productId, retainedProduct.revision, enteredProduct) != retainedProduct))
                    throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
                val expected = definition(draft, retainedPreview.catalogRevision, retainedPreview.effectiveFrom, capability,
                    retainedProduct, retainedPreview.variantValues?.let { draft.variantValues.map { LocalVariantValueV1(it.axisId, it.value) } })
                if (preview(expected) != retainedPreview) throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
                return@withAccess result
            }
            if (cut.page.catalogRevision?.value == Long.MAX_VALUE) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
            // This NEW local publication's revision follows the actual selected-catalog cut;
            // it is not a fabricated central-company release or client wall-clock observation.
            val product = productDefinition(draft)
            val definition = definition(draft, cut.page.catalogRevision?.next() ?: Revision(0), cut.reply.observedAt, capability,
                product, draft.variantValues.map { LocalVariantValueV1(it.axisId, it.value) })
            if (localCatalogProductShapeRejection(definition) != null) throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            val bytes = LocalConfigurationCodec.canonicalPublication(definition)
            val proposal = try { CompanyBusinessPublicationProposal(journal.owner.scope.company(),
                CompanyBusinessPublicationKind.CATALOG_SKU_V1, definition.publicationId, definition.skuId, bytes) }
            finally { bytes.fill(0) }
            access.checkCurrent()
            val action = native(publications.prepare(proposal))
            reviewResult(action)
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CompanyCatalogFailure) { LocalCompanyCatalogReviewResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { LocalCompanyCatalogReviewResult.Unavailable(failure.problem) }
        catch (_: IllegalArgumentException) { LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.INVALID_INPUT) }
        catch (_: Exception) { LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun review(originalActionId: DomainId): LocalCompanyCatalogReviewResult = actions.withLock {
        try { withAccess { access -> catalogAdmission(access); retainedReview(originalActionId) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CompanyCatalogFailure) { LocalCompanyCatalogReviewResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { LocalCompanyCatalogReviewResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun approve(review: LocalCompanyCatalogReview, headLogin: String, headSecret: CharArray): LocalCompanyCatalogApprovalResult {
        try {
            return actions.withLock {
                try { withAccess { access ->
                    catalogAdmission(access)
                    val exact = review.action.readNativeCompanySkuDefinition()
                    if (exact.company != journal.owner.scope.company() || preview(exact) != review.preview) {
                        throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
                    }
                    // Do not ask the head to sign metadata already known to conflict.
                    // This UX check is not a native CAS or the Master's independent activation check.
                    if (exact.productDefinition != null) {
                        val retained = native(publications.retained()).singleOrNull {
                            it.originalActionId == review.action.originalActionId &&
                                it.publicationId == exact.publicationId && it.entityId == exact.skuId &&
                                it.kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1
                        } ?: unavailable()
                        // A completed original recovers unchanged even after later versions.
                        // SIGNING_INTENT remains the native owner's uncertain outcome, not a
                        // fresh-signing opportunity or a reason to replace its original body.
                        if (retained.phase == CompanyBusinessPublicationPhase.PREPARED)
                            validateProductBeforeSigning(exact)
                    }
                    access.checkCurrent()
                    // Separate password-authenticated company-head action. Employee activation
                    // hints are NEVER interpreted as head authority or reusable signing approval.
                    val output = native(publications.approve(review.action, headLogin, headSecret))
                    LocalCompanyCatalogApprovalResult.Completed(completedView(output))
                } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: CompanyCatalogFailure) { LocalCompanyCatalogApprovalResult.Unavailable(failure.problem, failure.nativeReason) }
                catch (failure: GatewayFailure) { LocalCompanyCatalogApprovalResult.Unavailable(failure.problem) }
                catch (_: Exception) { LocalCompanyCatalogApprovalResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
            }
        } finally { headSecret.fill('\u0000') }
    }

    override suspend fun completed(originalActionId: DomainId): LocalCompanyCatalogApprovalResult = actions.withLock {
        try { withAccess { access ->
            catalogAdmission(access)
            LocalCompanyCatalogApprovalResult.Completed(completedView(native(publications.recover(originalActionId))))
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CompanyCatalogFailure) { LocalCompanyCatalogApprovalResult.Unavailable(failure.problem, failure.nativeReason) }
        catch (failure: GatewayFailure) { LocalCompanyCatalogApprovalResult.Unavailable(failure.problem) }
        catch (_: Exception) { LocalCompanyCatalogApprovalResult.Unavailable(LocalServiceProblem.UNAVAILABLE) }
    }

    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult = actions.withLock {
        // One exact native action owns one activation ID at this branch. Lost UI/process replies
        // never turn the same signed publication into a newly attributed operation.
        val operationId = OperationId(originalActionId.value)
        var absenceEstablished = false
        var writeStarted = false
        try { withAccess { access ->
            catalogAdmission(access)
            val output = native(publications.recover(originalActionId)) // NEVER held under journal.requests.
            val signed = output.readNativeCompanySku()
            completedView(output) // Canonical/domain/company/content and native signature checks.
            val request = MutationRequest(operationId, LocalConfigurationProfile.COMMAND,
                LocalConfigurationCodec.encode(LocalConfigurationRequestV1.InstallCompanySku(signed)))
            val intent = LocalCommandIntent(operationId, LocalAction.CATALOG_ACTIVATE, 1,
                LocalRecordReference(LocalRecordKind.SKU, signed.definition.skuId, signed.definition.skuRevision),
                listOf(LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(signed.definition.skuId)),
                    LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(signed.definition.publicationId)),
                    LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Text(output.proposal.contentDigest.value)),
                    LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true))))
            val entry = StoredClientIntentV1.staged(journal.owner, intent, request,
                text("Activate company-signed SKU", "تفعيل صنف موقع من الشركة"), activationSummary(output))
            journal.requests.withLock stage@{
                val existing = required(journal.find(operationId))
                if (existing != null) {
                    if (!existing.sameIntent(entry)) return@stage LocalStageResult.Uncertain(operationId)
                    access.checkCurrent()
                    return@stage LocalStageResult.Staged(pendingActivation(existing, access.canContinue(existing)))
                }
                absenceEstablished = true
                if (required(journal.pending()).isNotEmpty()) return@stage LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
                access.requireAction(LocalAction.CATALOG_ACTIVATE, signed.definition.skuId)
                if (!installed.has(request.command)) return@stage LocalStageResult.NotStaged(LocalServiceProblem.NOT_IMPLEMENTED)
                access.checkCurrent()
                writeStarted = true
                when (journal.stage(entry)) {
                    JournalWrite.DURABLE -> {
                        access.checkCurrent()
                        LocalStageResult.Staged(pendingActivation(entry, access.canContinue(entry)))
                    }
                    JournalWrite.NO_CAPACITY -> LocalStageResult.NotStaged(LocalServiceProblem.RETRY_LATER)
                    JournalWrite.IDENTITY_CONFLICT, JournalWrite.UNAVAILABLE -> LocalStageResult.Uncertain(operationId)
                }
            }
        } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: CompanyCatalogFailure) {
            if (absenceEstablished && !writeStarted) LocalStageResult.NotStaged(failure.problem) else LocalStageResult.Uncertain(operationId)
        }
        catch (failure: GatewayFailure) {
            if (absenceEstablished && !writeStarted) LocalStageResult.NotStaged(failure.problem) else LocalStageResult.Uncertain(operationId)
        }
        catch (_: Exception) { LocalStageResult.Uncertain(operationId) }
    }

    private suspend fun retainedReview(id: DomainId): LocalCompanyCatalogReviewResult {
        val reference = native(publications.retained()).singleOrNull { it.originalActionId == id && it.kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1 }
            ?: throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
        return when (reference.phase) {
            CompanyBusinessPublicationPhase.PREPARED -> reviewResult(native(publications.review(id)))
            CompanyBusinessPublicationPhase.COMPLETED -> LocalCompanyCatalogReviewResult.Completed(completedView(native(publications.recover(id))))
            CompanyBusinessPublicationPhase.SIGNING_INTENT -> throw CompanyCatalogFailure(LocalServiceProblem.STORAGE_UNCERTAIN,
                SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun reviewResult(action: CompanyBusinessPublicationAction): LocalCompanyCatalogReviewResult.Review {
        val exact = action.readNativeCompanySkuDefinition()
        if (exact.company != journal.owner.scope.company() || action.rootFingerprint != publications.trust.rootFingerprint) unavailable()
        return LocalCompanyCatalogReviewResult.Review(LocalCompanyCatalogReview(action, preview(exact)))
    }

    private fun completedView(output: CompanyBusinessPublicationOutput): LocalCompanyCatalogCompleted {
        val signed = output.readNativeCompanySku()
        val bytes = LocalConfigurationCodec.canonicalPublication(signed.definition)
        try {
            if (output.rootFingerprint != publications.trust.rootFingerprint || signed.definition.company != journal.owner.scope.company() ||
                !publications.trust.verify(signed.definition.company, signed.signingKeyId, bytes, signed.signatureHex)) unavailable()
        } finally { bytes.fill(0) }
        return LocalCompanyCatalogCompleted(output.originalActionId, preview(signed.definition), output.rootFingerprint)
    }

    private suspend fun catalogAdmission(access: GatewayAccess): ConfigurationCut =
        configurationRead(access, LocalConfigurationQueryV1(LocalConfigurationProfile.SKU, limit = 1))

    private fun definition(draft: LocalCompanySkuDraft, catalogRevision: Revision, effectiveFrom: UtcInstant,
        capability: NativeSkuCapability, product: LocalPublishedProductV1?, variants: List<LocalVariantValueV1>?): LocalPublishedSkuV1 {
        when (capability) {
            NativeSkuCapability.RETAIL -> Unit
            NativeSkuCapability.INGREDIENT -> LocalCompanyIngredientSkuDraft(draft)
            NativeSkuCapability.FOOD_OUTPUT -> LocalCompanyFoodOutputSkuDraft(draft)
        }
        draft.validatePublicationInput()
        val price = LocalPriceV1(draft.priceRuleId, Revision(0), channel = null, priority = 0,
            minimumQuantity = Quantity(unitId = draft.baseUnitId, scaled = 0, scale = draft.quantityScale), effectiveFrom = effectiveFrom,
            unitPrice = UnitPrice.parse(Currency.EGP, draft.unitPriceEgp),
            inclusion = if (draft.taxInclusion == LocalCompanyPriceTaxInclusion.INCLUDED) TaxInclusion.INCLUSIVE else TaxInclusion.EXCLUSIVE,
            tax = TaxSnapshot(draft.taxPolicyId, Revision(0), draft.taxCategory, PercentageRate.parse(draft.taxPercent)))
        return LocalPublishedSkuV1(journal.owner.scope.company(), draft.publicationId, draft.skuId, draft.productId,
            catalogRevision, if (capability == NativeSkuCapability.FOOD_OUTPUT) Revision(1) else Revision(0),
            LocalNamesV1(draft.englishName, draft.arabicName), draft.code, draft.barcodes.toList(),
            draft.baseUnitId, draft.quantityScale, when (draft.tracking) {
                LocalCompanyStockTracking.QUANTITY -> LocalTrackingV1.QUANTITY
                LocalCompanyStockTracking.LOT -> LocalTrackingV1.LOT
                LocalCompanyStockTracking.SERIAL -> LocalTrackingV1.SERIAL
            }, if (capability == NativeSkuCapability.FOOD_OUTPUT) LocalFulfillmentV1.MADE_TO_ORDER else LocalFulfillmentV1.RETAIL,
            requiresExpiry = draft.requiresExpiry, canPurchase = draft.canPurchase, canSell = capability != NativeSkuCapability.INGREDIENT,
            active = true, conversions = draft.conversions.map {
                LocalUnitConversionV1(it.id, it.revision, it.sourceUnitId, it.targetUnitId, it.rate, it.massVolumeEvidenceId)
            }, prices = listOf(price), returnPromise = draft.returnPromise?.let {
                require(capability == NativeSkuCapability.RETAIL)
                LocalRetailReturnPromiseV1(it.policyId, it.policyRevision, it.categoryCode, it.englishTerms, it.arabicTerms,
                    changeOfMindReceiptWindowDays = it.changeOfMindReceiptWindowDays,
                    defectiveReceiptWindowDays = it.defectiveReceiptWindowDays,
                    defectiveResolutionDays = it.defectiveResolutionDays)
            }, unitDefinitions = draft.unitDefinitions?.toList(), productDefinition = product, variantValues = variants)
    }

    /** Authenticated declarations from the preceding actual owner's retained() refresh. No grant is returned. */
    private fun nativeDefinitions(): List<LocalPublishedSkuV1> {
        val snapshot = publications.currentCompletedSnapshot() ?: unavailable()
        if (snapshot.company != journal.owner.scope.company() || snapshot.rootFingerprint != publications.trust.rootFingerprint) unavailable()
        return snapshot.completedPublications.filter {
            it.proposal.kind == CompanyBusinessPublicationKind.CATALOG_SKU_V1
        }.map { output ->
            val signed = output.readNativeCompanySku()
            val bytes = LocalConfigurationCodec.canonicalPublication(signed.definition)
            try {
                if (!publications.trust.verify(snapshot.company, signed.signingKeyId, bytes, signed.signatureHex)) unavailable()
            } finally { bytes.fill(0) }
            signed.definition
        }
    }

    private fun validateProductBeforeSigning(sku: LocalPublishedSkuV1) {
        val product = sku.productDefinition ?: return
        if (localCatalogProductShapeRejection(sku) != null) throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
        val completed = nativeDefinitions()
        when (val selected = selectLocalCatalogProductVersion(sku.productId, completed)) {
            is LocalCatalogProductSelectionV1.Conflict -> throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            is LocalCatalogProductSelectionV1.NoVersion -> if (selected.legacyIdentityPresent || product.revision != Revision(0))
                throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            is LocalCatalogProductSelectionV1.Selected -> {
                val previous = selected.definition
                if (product.revision < previous.revision || product.revision == previous.revision && product != previous ||
                    product.revision > previous.revision && (previous.revision.value == Long.MAX_VALUE || product.revision != previous.revision.next()))
                    throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            }
        }
        // Refuse already-known identity/combination conflicts before head approval. This is a
        // refreshed UX check, not a cross-owner signing lock or the Master's admission decision.
        if (selectLocalCatalogProductVersion(sku.productId, completed + sku) is LocalCatalogProductSelectionV1.Conflict)
            throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
    }

    private fun productDefinition(draft: LocalCompanySkuDraft): LocalPublishedProductV1 {
        val definitions = nativeDefinitions()
        return when (val selected = selectLocalCatalogProductVersion(draft.productId, definitions)) {
            is LocalCatalogProductSelectionV1.Conflict -> throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
            is LocalCatalogProductSelectionV1.NoVersion -> {
                // A legacy identity is not a proved new product and cannot be silently upgraded.
                if (selected.legacyIdentityPresent) throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
                productWire(draft.productId, Revision(0), draft.productDefinition ?: LocalCompanyProductDraft(draft.englishName, draft.arabicName))
            }
            is LocalCatalogProductSelectionV1.Selected -> {
                val previous = selected.definition
                val entered = draft.productDefinition ?: return previous
                val sameVersion = productWire(draft.productId, previous.revision, entered)
                if (sameVersion == previous) previous
                else {
                    if (previous.revision.value == Long.MAX_VALUE) throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                    productWire(draft.productId, previous.revision.next(), entered)
                }
            }
        }
    }

    private fun productWire(id: DomainId, revision: Revision, value: LocalCompanyProductDraft): LocalPublishedProductV1 =
        LocalPublishedProductV1(id, revision, LocalNamesV1(value.englishName, value.arabicName), value.categoryIds.toList(),
            value.variantAxes.map { LocalVariantAxisV1(it.id, it.code, it.values.toList()) },
            value.attributes.map { attribute -> LocalProductAttributeV1(attribute.key, when (val body = attribute.value) {
                is CatalogAttributeValue.Text -> LocalProductAttributeValueV1.Text(body.value)
                is CatalogAttributeValue.Decimal -> LocalProductAttributeValueV1.Decimal(body.value)
                is CatalogAttributeValue.Flag -> LocalProductAttributeValueV1.Flag(body.value)
            }) }, if (value.status == CatalogStatus.ACTIVE) LocalProductStatusV1.ACTIVE else LocalProductStatusV1.ARCHIVED,
            value.allowedVariantCombinations?.map { row -> row.map { LocalVariantValueV1(it.axisId, it.value) } })

    private fun productDraft(value: LocalPublishedProductV1): LocalCompanyProductDraft = LocalCompanyProductDraft(
        value.names.english, value.names.arabic, value.categoryIds.toList(),
        value.variantAxes.map { VariantAxis(it.id, it.code, it.values.toList()) },
        value.attributes.map { attribute -> CatalogAttribute(attribute.key, when (val body = attribute.value) {
            is LocalProductAttributeValueV1.Text -> CatalogAttributeValue.Text(body.value)
            is LocalProductAttributeValueV1.Decimal -> CatalogAttributeValue.Decimal(body.value)
            is LocalProductAttributeValueV1.Flag -> CatalogAttributeValue.Flag(body.value)
        }) }, if (value.status == LocalProductStatusV1.ACTIVE) CatalogStatus.ACTIVE else CatalogStatus.ARCHIVED,
        value.allowedVariantCombinations?.map { row -> row.map { VariantValue(it.axisId, it.value) } })

    private fun preview(value: LocalPublishedSkuV1): LocalCompanySkuPreview {
        // Old RETAIL/revision0 publications remain exact. Only a separately requested new food
        // output was genuinely signed at revision1; no old signature or payload is rewritten.
        val tracking = when (value.tracking) {
            LocalTrackingV1.QUANTITY -> LocalCompanyStockTracking.QUANTITY
            LocalTrackingV1.LOT -> LocalCompanyStockTracking.LOT
            LocalTrackingV1.SERIAL -> LocalCompanyStockTracking.SERIAL
            LocalTrackingV1.NONE -> throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
        }
        require(value.active && value.prices.size == 1)
        when (value.fulfillment) {
            LocalFulfillmentV1.RETAIL -> require(value.skuRevision == Revision(0) &&
                (value.canSell || value.canPurchase && value.tracking in setOf(LocalTrackingV1.QUANTITY, LocalTrackingV1.LOT)))
            LocalFulfillmentV1.MADE_TO_ORDER -> require(value.skuRevision == Revision(1) && value.canSell && !value.canPurchase &&
                value.tracking == LocalTrackingV1.QUANTITY && !value.requiresExpiry)
            else -> throw GatewayFailure(LocalServiceProblem.INVALID_INPUT)
        }
        val price = value.prices.single()
        require(price.channel == null && price.priority == 0 && price.minimumQuantity.scaled == 0L &&
            price.maximumQuantityExclusive == null && price.effectiveUntil == null && price.revision.value == 0L && price.tax.revision.value == 0L)
        return LocalCompanySkuPreview(value.publicationId, value.skuId, value.productId, value.baseUnitId,
            value.catalogRevision, value.skuRevision, value.names.english, value.names.arabic, value.code, value.barcodes.toList(),
            price.unitPrice.amount.canonicalString(), price.tax.categoryCode, price.tax.rate.percent.canonicalString(),
            if (price.inclusion == TaxInclusion.INCLUSIVE) LocalCompanyPriceTaxInclusion.INCLUDED else LocalCompanyPriceTaxInclusion.EXCLUDED,
            price.tax.policyId, price.ruleId, price.effectiveFrom, value.canPurchase,
            value.quantityScale, tracking, value.requiresExpiry,
            if (value.fulfillment == LocalFulfillmentV1.MADE_TO_ORDER) LocalCompanySkuFulfillment.MADE_TO_ORDER else LocalCompanySkuFulfillment.RETAIL,
            value.canSell, value.returnPromise?.let {
                LocalCompanyReturnPromiseDraft(it.policyId, it.policyRevision, it.categoryCode, it.englishTerms, it.arabicTerms,
                    changeOfMindReceiptWindowDays = it.changeOfMindReceiptWindowDays,
                    defectiveReceiptWindowDays = it.defectiveReceiptWindowDays,
                    defectiveResolutionDays = it.defectiveResolutionDays)
            }, value.unitDefinitions?.toList(), value.conversions.map { it.domain(value.productId) },
            value.productDefinition?.let { LocalCompanyProductPreview(it.revision, productDraft(it)) },
            value.variantValues?.map { VariantValue(it.axisId, it.value) })
    }

    private fun activationSummary(output: CompanyBusinessPublicationOutput): List<LocalRecordValue> {
        val value = completedView(output).preview
        val summary = listOf(recordValue(text("SKU", "الصنف"), literal(value.skuId.value)),
            recordValue(text("Code", "الرمز"), literal(value.code)),
            recordValue(text("Company publication", "إصدار الشركة"), literal(value.publicationId.value)),
            recordValue(text("Exact content SHA-256", "بصمة المحتوى الدقيق SHA-256"), literal(output.proposal.contentDigest.value)),
            recordValue(text("EGP unit price", "سعر الوحدة بالجنيه"), literal(value.unitPriceEgp)),
            recordValue(text("Tax category / rate / inclusion", "الفئة الضريبية / النسبة / التضمين"),
                literal("${value.taxCategory} / ${value.taxPercent}% / ${value.taxInclusion.name}")),
            recordValue(text("Authority separation", "فصل الصلاحيات"),
                text("Company signing completed; this employee request activates at this branch only. It creates no stock or sale.",
                    "اكتمل توقيع الشركة؛ يفعّل طلب الموظف الصنف في هذا الفرع فقط. لا ينشئ مخزونًا أو بيعًا."))) +
            (if (value.fulfillment == LocalCompanySkuFulfillment.RETAIL && value.canSell) emptyList() else listOf(
                recordValue(text("Signed capability", "القدرة الموقعة"), literal("${value.fulfillment.name} / canSell=${value.canSell} / skuRevision=${value.skuRevision.value}")))) + value.returnPromise?.let { promise -> listOf(
                recordValue(text("Exact company commercial return promise", "وعد الإرجاع التجاري الدقيق للشركة"),
                    literal("${promise.policyId.value} / ${promise.policyRevision.value} / ${promise.categoryCode}")),
                recordValue(text("Promise scope", "نطاق الوعد"),
                    text("Exact terms are in the signed publication. Original-sale capture only; no legal eligibility or delivery is asserted.",
                        "توجد الشروط الدقيقة في الإصدار الموقع. حفظ للبيعة الأصلية فقط؛ دون تأكيد أهلية قانونية أو تسليم."))) +
                returnPromisePeriodsSummary(promise)
            }.orEmpty()
        val productRows = value.productDefinition?.let { product -> listOf(recordValue(
            text("Signed product / independent revision / selected axes", "المنتج الموقع / مراجعته المستقلة / المحاور المختارة"),
            literal("${value.productId.value} / ${product.revision.value} / ${value.variantValues.orEmpty().size}"))) }.orEmpty()
        val units = value.unitDefinitions.orEmpty().map { unit ->
                recordValue(text("Signed unit identity / code / dimension / precision", "معرف الوحدة الموقع أو الرمز أو البُعد أو الدقة"),
                    literal("${unit.id.value} / ${unit.code.original} / ${unit.dimension.name} / ${unit.maximumScale}"))
            }
        val conversions = value.conversions.map { conversion ->
                recordValue(text("Signed conversion / revision / source → base / exact rate", "التحويل الموقع أو المراجعة أو المصدر إلى الأساس أو النسبة الدقيقة"),
                    literal("${conversion.id.value} / ${conversion.revision.value} / ${conversion.sourceUnitId.value} → ${conversion.targetUnitId.value} / ${conversion.rate.canonicalString()}"))
            }
        if (summary.size + productRows.size + units.size + conversions.size <= 32) return summary + productRows + units + conversions
        // Preserve existing small/legacy summary bytes. Larger valid publications group
        // four exact values per row: at most 30 rows, each below LocalText's 2048 limit.
        fun grouped(rows: List<LocalRecordValue>): List<LocalRecordValue> = rows.chunked(4).map { chunk ->
            recordValue(chunk.first().label, literal(chunk.joinToString("\n") { it.value.english }))
        }
        return summary + productRows + grouped(units) + grouped(conversions)
    }

    private fun returnPromisePeriodsSummary(promise: LocalCompanyReturnPromiseDraft): List<LocalRecordValue> {
        // Legacy all-null publications keep their original activation summary bytes.
        if (promise.changeOfMindReceiptWindowDays == null && promise.defectiveReceiptWindowDays == null &&
            promise.defectiveResolutionDays == null) return emptyList()
        fun days(value: Int?) = value?.let { literal(it.toString()) } ?: text("Unspecified", "غير محدد")
        return listOf(
            recordValue(text("Change-of-mind receipt window (days)", "مهلة تغيير الرأي من الاستلام — أيام"),
                days(promise.changeOfMindReceiptWindowDays)),
            recordValue(text("Defective-goods receipt window (days)", "مهلة السلع المعيبة من الاستلام — أيام"),
                days(promise.defectiveReceiptWindowDays)),
            recordValue(text("Defective-goods resolution (days)", "تسوية السلع المعيبة — أيام"),
                days(promise.defectiveResolutionDays)),
        )
    }

    private fun pendingActivation(entry: StoredClientIntentV1, allowed: Boolean): LocalPendingOperation = LocalPendingOperation(
        entry.operationId, entry.localAction, entry.label.ui(), entry.summary.map { it.ui() },
        allowed && entry.recorded == null && !entry.acknowledged, entry.localPhase,
        entry.recorded?.let { if (it.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED })

    private suspend fun <T> withAccess(body: suspend (GatewayAccess) -> T): T {
        currentCoroutineContext().ensureActive()
        val lease = credentials.acquire() ?: throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
        try {
            if (!lease.isCurrent()) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val view = when (val current = lease.observeSession(endpoint)) {
                is LocalSessionViewResult.Current -> current.view
                LocalSessionViewResult.Denied -> throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
                LocalSessionViewResult.Busy -> throw GatewayFailure(LocalServiceProblem.RETRY_LATER)
                LocalSessionViewResult.Unavailable -> throw GatewayFailure(LocalServiceProblem.UNAVAILABLE)
                LocalSessionViewResult.StorageUncertain -> throw GatewayFailure(LocalServiceProblem.STORAGE_UNCERTAIN)
            }
            if (!lease.isCurrent() || view.actorId != journal.owner.actorId || view.scope != journal.owner.scope ||
                view.deviceId != journal.owner.deviceId) throw GatewayFailure(LocalServiceProblem.SESSION_ENDED)
            val access = GatewayAccess(endpoint, lease, view, installed)
            val result = body(access)
            access.checkCurrent()
            return result
        } finally { lease.destroy() }
    }

    private fun <T> native(result: LocalCompanyAuthorityResult<T>): T = when (result) {
        is LocalCompanyAuthorityResult.Value -> result.value
        is LocalCompanyAuthorityResult.Unavailable -> throw CompanyCatalogFailure(when (result.nativeReason) {
            SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED -> LocalServiceProblem.DENIED
            SelfOwnedInstallationFailureV1.THROTTLED, SelfOwnedInstallationFailureV1.BUSY,
            SelfOwnedInstallationFailureV1.CAPACITY_EXHAUSTED -> LocalServiceProblem.RETRY_LATER
            SelfOwnedInstallationFailureV1.INVALID_INPUT -> LocalServiceProblem.INVALID_INPUT
            SelfOwnedInstallationFailureV1.STATE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
            else -> LocalServiceProblem.UNAVAILABLE
        }, result.nativeReason)
    }
}

private enum class NativeSkuCapability { RETAIL, INGREDIENT, FOOD_OUTPUT }

private class CompanyCatalogFailure(val problem: LocalServiceProblem, val nativeReason: SelfOwnedInstallationFailureV1?) : Exception()
