@file:Suppress("UNCHECKED_CAST")

package com.ukkera.pos.app.gateway.setup.ipc

import com.ukkera.pos.app.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import com.ukkera.pos.rpc.LocalSetupPolicyTransferV1
import kotlinx.serialization.json.*
import java.util.Base64

/**
 * Closed v1 desktop employee-console DATA grammar. Numeric tags have explicit constructors;
 * no class loading, object serialization, reflection, credential or native-owner decoding exists.
 * The IPC frame bounds bytes; this grammar additionally bounds depth, nodes, strings and lists.
 * Only the catalog/setup workflow subset is represented; unsupported optional business payloads
 * are refused instead of being silently truncated. All values remain untrusted input DATA.
 */
internal object DesktopMasterEmployeeData {
    private const val MAX_NODES = 100_000
    private const val MAX_DEPTH = 48
    private const val MAX_STRING = 128 * 1024
    private const val MAX_COLLECTION = 2048
    fun encode(value: Any?, reviewId: (Any) -> DomainId = { error("No retained review owner") }): JsonElement =
        Writer(reviewId).write(value)
    fun decode(value: JsonElement): Any? = Reader().read(value)

    private class Writer(private val reviewId: (Any) -> DomainId) {
        private var count = 0
        private var depth = 0
        private fun node(tag: Int, vararg fields: Any?): JsonArray =
            JsonArray(listOf(JsonPrimitive(tag)) + fields.map(::write))
        fun write(v: Any?): JsonElement {
            require(++count <= MAX_NODES && ++depth <= MAX_DEPTH)
            try { return when (v) {
                null -> JsonNull
                is String -> { require(v.length <= MAX_STRING); JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(v))) }
                is Boolean -> JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(v)))
                is Int -> JsonArray(listOf(JsonPrimitive(3), JsonPrimitive(v)))
                is Long -> JsonArray(listOf(JsonPrimitive(4), JsonPrimitive(v)))
                is List<*> -> { require(v.size <= MAX_COLLECTION); JsonArray(listOf(JsonPrimitive(5)) + v.map(::write)) }
                is Set<*> -> { require(v.size <= MAX_COLLECTION); JsonArray(listOf(JsonPrimitive(6)) + v.map(::write)) }
                is Map<*, *> -> { require(v.size <= MAX_COLLECTION); JsonArray(listOf(JsonPrimitive(7)) + v.entries.flatMap { listOf(write(it.key), write(it.value)) }) }
                is ByteArray -> { require(v.size <= MAX_STRING); node(8, Base64.getEncoder().encodeToString(v)) }
            is com.ukkera.pos.app.LocalAcknowledgementResult.Acknowledged -> node(100, v.operationId)
            is com.ukkera.pos.app.LocalAcknowledgementResult.Unresolved -> node(101, v.operationId, v.problem)
            is com.ukkera.pos.app.LocalAction -> node(102, v.name)
            is com.ukkera.pos.app.LocalActionForm -> { require(v.arrivalEvidence == null && v.openingAllocations == null); node(103 , v.action, v.version, v.title, v.fields, v.explanation, v.confirmation, v.reservedOperationId) }
            is com.ukkera.pos.app.LocalAdministrationPending -> node(104, v.command, v.phase, v.cancellationRequested, v.receipt)
            is com.ukkera.pos.app.LocalAdministrationPendingResult.Available -> node(105, v.operation)
            is com.ukkera.pos.app.LocalAdministrationPendingResult.Unavailable -> node(106, v.problem)
            is com.ukkera.pos.app.LocalAdministrationPhase -> node(107, v.name)
            is com.ukkera.pos.app.LocalAdministrationResult.Known -> node(108, v.operation)
            is com.ukkera.pos.app.LocalAdministrationResult.NotPrepared -> node(109, v.problem)
            is com.ukkera.pos.app.LocalAdministrationResult.Unresolved -> node(110, v.requestId, v.problem)
            is com.ukkera.pos.app.LocalCatalogReleaseAuthoringSource -> node(111, v.catalogId, v.nextRevision, v.previous, v.activeStorageRevision, v.active, v.members)
            is com.ukkera.pos.app.LocalCatalogReleaseMemberInput -> node(112, v.publicationId, v.contentDigest, v.skuId, v.skuRevision, v.productId, v.productRevision)
            is com.ukkera.pos.app.LocalCatalogReleaseMemberSource -> node(113, v.member, v.skuNames, v.productNames, v.skuCode, v.active)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationCompleted -> node(114, v.originalActionId, v.preview, v.rootFingerprint)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationDraft -> node(115, v.publicationId, v.catalogId, v.revision, v.previous, v.members)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationPreview -> node(116, v.companyId, v.definition)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationReference -> node(117, v.catalogId, v.publicationId, v.revision, v.contentDigest)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationResult.Completed -> node(118, v.publication)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationResult.Review -> node(119, v.review)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationResult.Unavailable -> node(120, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationReview -> node(121, v.displayAction, v.preview, reviewId(v))
            is com.ukkera.pos.app.LocalCatalogReleasePublicationStatus -> node(122, v.references, v.source, v.mayPrepare, v.masterObservedAt)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationStatusResult.Available -> node(123, v.status)
            is com.ukkera.pos.app.LocalCatalogReleasePublicationStatusResult.Unavailable -> node(124, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalChoice -> node(125, v.code, v.label)
            is com.ukkera.pos.app.LocalClientCleanupStatus -> node(126, v.available, v.pendingRevocations, v.authenticationUnresolved)
            is com.ukkera.pos.app.LocalCommandIntent -> { require(v.arrivalEvidence == null); node(127 , v.operationId, v.action, v.formVersion, v.reference, v.values, v.lineField, v.lines) }
            is com.ukkera.pos.app.LocalCompanyCatalogApprovalResult.Completed -> node(128, v.publication)
            is com.ukkera.pos.app.LocalCompanyCatalogApprovalResult.Unavailable -> node(129, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalCompanyCatalogCompleted -> node(130, v.originalActionId, v.preview, v.rootFingerprint)
            is com.ukkera.pos.app.LocalCompanyCatalogReview -> node(131, v.displayAction, v.preview, reviewId(v))
            is com.ukkera.pos.app.LocalCompanyCatalogReviewResult.Completed -> node(132, v.publication)
            is com.ukkera.pos.app.LocalCompanyCatalogReviewResult.Review -> node(133, v.review)
            is com.ukkera.pos.app.LocalCompanyCatalogReviewResult.Unavailable -> node(134, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalCompanyCatalogStatus -> node(135, v.references, v.mayPrepareNewItem, v.masterObservedAt, v.catalogCut)
            is com.ukkera.pos.app.LocalCompanyCatalogStatusResult.Available -> node(136, v.status)
            is com.ukkera.pos.app.LocalCompanyCatalogStatusResult.Unavailable -> node(137, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalCompanyFoodOutputSkuDraft -> node(138, v.sku)
            is com.ukkera.pos.app.LocalCompanyIngredientSkuDraft -> node(139, v.sku)
            is com.ukkera.pos.app.LocalCompanyPriceTaxInclusion -> node(140, v.name)
            is com.ukkera.pos.app.LocalCompanyProductDraft -> node(141, v.englishName, v.arabicName, v.categoryIds, v.variantAxes, v.attributes, v.status, v.allowedVariantCombinations)
            is com.ukkera.pos.app.LocalCompanyProductPreview -> node(142, v.revision, v.definition)
            is com.ukkera.pos.app.LocalCompanyPublicationReviewData -> node(143, v.originalActionId, v.proposal, v.signingKeyId, v.rootFingerprint, v.companyName, v.headName, v.headLogin)
            is com.ukkera.pos.app.LocalCompanyReturnPromiseDraft -> node(144, v.policyId, v.policyRevision, v.categoryCode, v.englishTerms, v.arabicTerms, v.changeOfMindReceiptWindowDays, v.defectiveReceiptWindowDays, v.defectiveResolutionDays)
            is com.ukkera.pos.app.LocalCompanySkuDraft -> node(145, v.publicationId, v.skuId, v.productId, v.baseUnitId, v.priceRuleId, v.taxPolicyId, v.englishName, v.arabicName, v.code, v.barcodes, v.unitPriceEgp, v.taxCategory, v.taxPercent, v.taxInclusion, v.canPurchase, v.quantityScale, v.tracking, v.requiresExpiry, v.returnPromise, v.unitDefinitions, v.conversions, v.productDefinition, v.variantValues)
            is com.ukkera.pos.app.LocalCompanySkuFulfillment -> node(146, v.name)
            is com.ukkera.pos.app.LocalCompanySkuPreview -> node(147, v.publicationId, v.skuId, v.productId, v.baseUnitId, v.catalogRevision, v.skuRevision, v.englishName, v.arabicName, v.code, v.barcodes, v.unitPriceEgp, v.taxCategory, v.taxPercent, v.taxInclusion, v.taxPolicyId, v.priceRuleId, v.effectiveFrom, v.canPurchase, v.quantityScale, v.tracking, v.requiresExpiry, v.fulfillment, v.canSell, v.returnPromise, v.unitDefinitions, v.conversions, v.productDefinition, v.variantValues)
            is com.ukkera.pos.app.LocalCompanyStockTracking -> node(148, v.name)
            is com.ukkera.pos.app.LocalField -> node(149, v.name)
            is com.ukkera.pos.app.LocalFoodConfigurationActivity -> node(150, v.name)
            is com.ukkera.pos.app.LocalFoodConfigurationChannel -> node(151, v.name)
            is com.ukkera.pos.app.LocalFoodConfigurationDetail.Recipe -> node(152, v.source)
            is com.ukkera.pos.app.LocalFoodConfigurationDetail.Route -> node(153, v.source)
            is com.ukkera.pos.app.LocalFoodConfigurationDetail.Sku -> node(154, v.source)
            is com.ukkera.pos.app.LocalFoodConfigurationDetail.Station -> node(155, v.source)
            is com.ukkera.pos.app.LocalFoodConfigurationDetail.Warehouse -> node(156, v.source)
            is com.ukkera.pos.app.LocalFoodConfigurationLabel -> node(157, v.id, v.revision, v.storageRevision, v.names, v.active)
            is com.ukkera.pos.app.LocalFoodConfigurationReadResult.Detail -> node(158, v.detail)
            is com.ukkera.pos.app.LocalFoodConfigurationReadResult.Page -> node(159, v.page)
            is com.ukkera.pos.app.LocalFoodConfigurationReadResult.Unavailable -> node(160, v.problem)
            is com.ukkera.pos.app.LocalFoodConfigurationSourceKind -> node(161, v.name)
            is com.ukkera.pos.app.LocalFoodConfigurationSourcePage -> node(162, v.kind, v.labels, v.nextAfter)
            is com.ukkera.pos.app.LocalFoodConfigurationStationMode -> node(163, v.name)
            is com.ukkera.pos.app.LocalFoodConfigurationStatus -> node(164, v.references, v.observedAt)
            is com.ukkera.pos.app.LocalFoodConfigurationStatusResult.Available -> node(165, v.status)
            is com.ukkera.pos.app.LocalFoodConfigurationStatusResult.Unavailable -> node(166, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalFoodRecipeChoiceDraft -> node(167, v.id, v.names, v.modifierIds, v.ingredients, v.routeKeys, v.pricingSku)
            is com.ukkera.pos.app.LocalFoodRecipeChoicePreview -> node(168, v.id, v.names, v.modifierIds, v.ingredients, v.routeKeys, v.pricingSkuId)
            is com.ukkera.pos.app.LocalFoodRecipeCompleted -> node(169, v.originalActionId, v.preview, v.rootFingerprint)
            is com.ukkera.pos.app.LocalFoodRecipeDraft -> node(170, v.publicationId, v.recipeId, v.outputSku, v.expectedRecipeRevision, v.yieldQuantity, v.portionQuantity, v.choices)
            is com.ukkera.pos.app.LocalFoodRecipeIngredientDraft -> node(171, v.componentId, v.sku, v.quantityPerYield)
            is com.ukkera.pos.app.LocalFoodRecipeIngredientPreview -> node(172, v.componentId, v.skuId, v.skuRevision, v.quantityPerYield)
            is com.ukkera.pos.app.LocalFoodRecipePreview -> node(173, v.publicationId, v.recipeId, v.revision, v.outputSkuId, v.outputSkuRevision, v.yieldQuantity, v.portionQuantity, v.choices, v.active)
            is com.ukkera.pos.app.LocalFoodRecipeResult.Completed -> node(174, v.publication)
            is com.ukkera.pos.app.LocalFoodRecipeResult.Review -> node(175, v.review)
            is com.ukkera.pos.app.LocalFoodRecipeResult.Unavailable -> node(176, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalFoodRecipeReview -> node(177, v.displayAction, v.preview, reviewId(v))
            is com.ukkera.pos.app.LocalFoodRecipeSource -> node(178, v.id, v.revision, v.storageRevision, v.outputSkuId, v.names, v.routeKeys, v.active)
            is com.ukkera.pos.app.LocalFoodRouteDraft -> node(179, v.operationId, v.routeId, v.expectedStorageRevision, v.recipe, v.activity, v.channel, v.warehouse, v.targets)
            is com.ukkera.pos.app.LocalFoodRouteSource -> node(180, v.id, v.storageRevision, v.recipeId, v.recipeRevision, v.activity, v.channel, v.warehouseId, v.targets, v.active)
            is com.ukkera.pos.app.LocalFoodRouteTarget -> node(181, v.routeKey, v.station)
            is com.ukkera.pos.app.LocalFoodSkuSource -> node(182, v.id, v.skuRevision, v.storageRevision, v.productId, v.baseUnitId, v.quantityScale, v.names, v.tracking, v.fulfillment, v.active, v.canSell, v.canPurchase)
            is com.ukkera.pos.app.LocalFoodStationDraft -> node(183, v.operationId, v.stationId, v.expectedStorageRevision, v.names, v.mode)
            is com.ukkera.pos.app.LocalFoodStationSource -> node(184, v.id, v.storageRevision, v.names, v.mode, v.active)
            is com.ukkera.pos.app.LocalFoodWarehouseSource -> node(185, v.id, v.storageRevision, v.names, v.active)
            is com.ukkera.pos.app.LocalFormField.Lines -> node(186, v.field, v.label, v.columns, v.minimumLines, v.maximumLines, v.initialLines, v.allowBlankLines, v.allowDuplicateLines)
            is com.ukkera.pos.app.LocalFormField.Scalar -> node(187, v.field, v.label, v.kind, v.required, v.help, v.maximumCharacters, v.allowNegative, v.choices, v.references, v.initialValue, v.readOnly, v.referenceSource)
            is com.ukkera.pos.app.LocalInputKind -> node(188, v.name)
            is com.ukkera.pos.app.LocalInputLine -> node(189, v.lineId, v.values)
            is com.ukkera.pos.app.LocalInvoiceEntryKind -> node(190, v.name)
            is com.ukkera.pos.app.LocalOperationResult.Pending -> node(191, v.operation)
            is com.ukkera.pos.app.LocalOperationResult.Recorded -> { require(v.foodCashFinancial == null && v.returnStock == null && v.food == null); node(192 , v.operationId, v.decision, v.message, v.result, v.replayed, v.originalAction) }
            is com.ukkera.pos.app.LocalOperationResult.Unresolved -> node(193, v.operationId, v.problem)
            is com.ukkera.pos.app.LocalPendingOperation -> { require(v.actionApproval == null); node(194 , v.operationId, v.action, v.label, v.summary, v.submitAllowed, v.phase, v.recordedDecision) }
            is com.ukkera.pos.app.LocalPendingPhase -> node(195, v.name)
            is com.ukkera.pos.app.LocalPendingResult.Available -> node(196, v.operations, v.nextCursor)
            is com.ukkera.pos.app.LocalPendingResult.Unavailable -> node(197, v.problem)
            is com.ukkera.pos.app.LocalPricePolicyPublicationCompleted -> node(198, v.originalActionId, v.preview, v.rootFingerprint)
            is com.ukkera.pos.app.LocalPricePolicyPublicationDraft -> node(199, v.publicationId, v.entryId, v.revision, v.skuId, v.ruleId, v.branchOverridesEnabled, v.authorizationReferenceQuantity, v.effectiveFrom, v.effectiveUntil)
            is com.ukkera.pos.app.LocalPricePolicyPublicationPreview -> node(200, v.companyId, v.definition)
            is com.ukkera.pos.app.LocalPricePolicyPublicationResult.Completed -> node(201, v.publication)
            is com.ukkera.pos.app.LocalPricePolicyPublicationResult.Review -> node(202, v.review)
            is com.ukkera.pos.app.LocalPricePolicyPublicationResult.Unavailable -> node(203, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalPricePolicyPublicationReview -> node(204, v.displayAction, v.preview, reviewId(v))
            is com.ukkera.pos.app.LocalPricePolicyPublicationStatus -> node(205, v.references, v.mayPrepare, v.masterObservedAt)
            is com.ukkera.pos.app.LocalPricePolicyPublicationStatusResult.Available -> node(206, v.status)
            is com.ukkera.pos.app.LocalPricePolicyPublicationStatusResult.Unavailable -> node(207, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalPricePreviewQuery -> node(208, v.skuId, v.channel, v.quantity)
            is com.ukkera.pos.app.LocalPriceReadControls -> node(209, v.skuId, v.unitId, v.quantityScale)
            is com.ukkera.pos.app.LocalReadResult.Available -> node(210, v.page)
            is com.ukkera.pos.app.LocalReadResult.Unavailable -> { require(v.foodReason == null); node(211 , v.problem) }
            is com.ukkera.pos.app.LocalRecord -> node(212, v.reference, v.title, v.status, v.values, v.selectable, v.supplierId, v.invoiceEntryKind)
            is com.ukkera.pos.app.LocalRecordKind -> node(213, v.name)
            is com.ukkera.pos.app.LocalRecordReference -> node(214, v.kind, v.id, v.revision, v.parentId)
            is com.ukkera.pos.app.LocalRecordValue -> node(215, v.label, v.value)
            is com.ukkera.pos.app.LocalRecordedDecision -> node(216, v.name)
            is com.ukkera.pos.app.LocalReferenceChoice -> node(217, v.reference, v.label)
            is com.ukkera.pos.app.LocalReferencePage -> node(218, v.query, v.choices, v.nextAfter, v.notice)
            is com.ukkera.pos.app.LocalReferenceQuery -> node(219, v.source, v.after, v.exactId)
            is com.ukkera.pos.app.LocalReferenceResult.Available -> node(220, v.page)
            is com.ukkera.pos.app.LocalReferenceResult.Unavailable -> node(221, v.problem)
            is com.ukkera.pos.app.LocalReferenceSource -> node(222, v.kind, v.excludedId)
            is com.ukkera.pos.app.LocalReturnRightsPublicationCompleted -> node(223, v.originalActionId, v.preview, v.rootFingerprint)
            is com.ukkera.pos.app.LocalReturnRightsPublicationDraft -> node(224, v.publicationId, v.policyId, v.companyRevision, v.jurisdictionCode, v.legalSourceUri, v.legalSourceAccessDate, v.continuingRights, v.rules)
            is com.ukkera.pos.app.LocalReturnRightsPublicationException -> node(225, v.name)
            is com.ukkera.pos.app.LocalReturnRightsPublicationPreview -> node(226, v.companyId, v.definition)
            is com.ukkera.pos.app.LocalReturnRightsPublicationReason -> node(227, v.name)
            is com.ukkera.pos.app.LocalReturnRightsPublicationResult.Completed -> node(228, v.publication)
            is com.ukkera.pos.app.LocalReturnRightsPublicationResult.Review -> node(229, v.review)
            is com.ukkera.pos.app.LocalReturnRightsPublicationResult.Unavailable -> node(230, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalReturnRightsPublicationReview -> node(231, v.displayAction, v.preview, reviewId(v))
            is com.ukkera.pos.app.LocalReturnRightsPublicationRule -> node(232, v.categoryCode, v.reason, v.treatment, v.receiptWindowDays, v.coveredResolutionDays, v.exceptionBasis)
            is com.ukkera.pos.app.LocalReturnRightsPublicationStatus -> node(233, v.references, v.mayPrepare, v.masterObservedAt)
            is com.ukkera.pos.app.LocalReturnRightsPublicationStatusResult.Available -> node(234, v.status)
            is com.ukkera.pos.app.LocalReturnRightsPublicationStatusResult.Unavailable -> node(235, v.problem, v.nativeReason)
            is com.ukkera.pos.app.LocalReturnRightsPublicationTreatment -> node(236, v.name)
            is com.ukkera.pos.app.LocalReturnRightsUiPage -> node(237, v.selection, v.companyId, v.branchId, v.attribution, v.continuingRights, v.rules)
            is com.ukkera.pos.app.LocalReturnRightsUiRule -> node(238, v.categoryCode, v.reasonCode, v.values)
            is com.ukkera.pos.app.LocalScalarInput -> node(239, v.field, v.value)
            is com.ukkera.pos.app.LocalScalarValue.Choice -> node(240, v.code)
            is com.ukkera.pos.app.LocalScalarValue.Choices -> node(241, v.codes)
            is com.ukkera.pos.app.LocalScalarValue.Date -> node(242, v.iso)
            is com.ukkera.pos.app.LocalScalarValue.Decimal -> node(243, v.canonical)
            is com.ukkera.pos.app.LocalScalarValue.Flag -> node(244, v.value)
            is com.ukkera.pos.app.LocalScalarValue.Identifier -> node(245, v.value)
            is com.ukkera.pos.app.LocalScalarValue.Integer -> node(246, v.value)
            is com.ukkera.pos.app.LocalScalarValue.Reference -> node(247, v.value)
            is com.ukkera.pos.app.LocalScalarValue.References -> node(248, v.values)
            is com.ukkera.pos.app.LocalScalarValue.Text -> node(249, v.value)
            is com.ukkera.pos.app.LocalSerialPublicationCompleted -> node(250, v.originalActionId, v.preview)
            is com.ukkera.pos.app.LocalSerialPublicationPreview.Authority -> node(251, v.definition)
            is com.ukkera.pos.app.LocalSerialPublicationPreview.Registration -> node(252, v.definition)
            is com.ukkera.pos.app.LocalSerialPublicationResult.Completed -> node(253, v.publication)
            is com.ukkera.pos.app.LocalSerialPublicationResult.Review -> node(254, v.review)
            is com.ukkera.pos.app.LocalSerialPublicationResult.Unavailable -> node(255, v.problem, v.nativeReason, v.unsignedInputMayChange)
            is com.ukkera.pos.app.LocalSerialPublicationReview -> node(256, v.displayAction, v.preview, reviewId(v))
            is com.ukkera.pos.app.LocalSerialPublicationStatus -> node(257, v.references, v.source)
            is com.ukkera.pos.app.LocalSerialPublicationStatusResult.Available -> node(258, v.status)
            is com.ukkera.pos.app.LocalSerialPublicationStatusResult.Unavailable -> node(259, v.problem)
            is com.ukkera.pos.app.LocalSerialRegistrationAuthoringSource -> node(260, v.registrationId, v.skuId, v.skuPublicationId, v.skuPublicationDigest, v.previous, v.previousDefinition, v.nextRevision)
            is com.ukkera.pos.app.LocalSerialRegistrationDraft -> node(261, v.publicationId, v.registrationId, v.revision, v.previous, v.serialId, v.serialCode, v.skuId, v.skuPublicationId, v.skuPublicationDigest, v.validFrom, v.validUntil)
            is com.ukkera.pos.app.LocalServiceProblem -> node(262, v.name)
            is com.ukkera.pos.app.LocalSessionAttachmentKey -> node(263, v.ownerId, v.generation)
            is com.ukkera.pos.app.LocalSessionCheckResult.Current -> node(264, v.display, v.attachmentKey)
            is com.ukkera.pos.app.LocalSessionCheckResult.Unavailable -> node(265, v.problem, v.attachmentKey)
            is com.ukkera.pos.app.LocalSessionDraftEpochs -> node(266, v.general, v.cashier)
            is com.ukkera.pos.app.LocalSessionEndResult -> node(267, v.name)
            is com.ukkera.pos.app.LocalSessionWorkspaceMode -> node(268, v.name)
            is com.ukkera.pos.app.LocalSetupAcknowledgementView -> node(269, v.actorId, v.acknowledgedAt, v.operationId, v.policyRevision)
            is com.ukkera.pos.app.LocalSetupCatalogCompletionInput -> node(270, v.explicitNoImport, v.rows)
            is com.ukkera.pos.app.LocalSetupCatalogImportView -> node(271, v.explicitNoImport, v.rowCount, v.manifestDigest, v.inputManifestDigest, v.actorId, v.recordedAt, v.operationId)
            is com.ukkera.pos.app.LocalSetupCatalogRow -> node(272, v.sourceId, v.skuId, v.catalogOperationId)
            is com.ukkera.pos.app.LocalSetupCheckpointView -> node(273, v.configuration, v.catalogImport, v.opening, v.retainedRowsLoaded, v.retainedCatalogRows, v.retainedStockRows, v.retainedFloatRows)
            is com.ukkera.pos.app.LocalSetupClosureAvailability -> node(274, v.name)
            is com.ukkera.pos.app.LocalSetupConfigurationInput -> node(275, v.expectedRevision, v.currencyCode, v.timeZone, v.businessDayCutoffMinute, v.legalName, v.taxRegistrationId, v.taxRegistrationAbsent)
            is com.ukkera.pos.app.LocalSetupConfigurationView -> node(276, v.revision, v.activities, v.currency, v.timeZone, v.businessDayCutoffMinute, v.legalName, v.taxRegistrationId, v.taxRegistrationAbsent, v.actorId, v.recordedAt, v.operationId)
            is com.ukkera.pos.app.LocalSetupGoLiveView -> node(277, v.actorId, v.occurredAt, v.operationId, v.policyRevision)
            is com.ukkera.pos.app.LocalSetupManifestPreview -> node(278, v.rowCount, v.digest)
            is com.ukkera.pos.app.LocalSetupMutationResult.NotStaged -> node(279, v.operationId, v.problem, v.kind)
            is com.ukkera.pos.app.LocalSetupMutationResult.Pending -> node(280, v.operationId, v.kind)
            is com.ukkera.pos.app.LocalSetupMutationResult.Recorded -> node(281, v.operationId, v.kind, v.committed, v.code, v.rowId, v.rowCount, v.manifestDigest, v.catalogRow, v.stockRow, v.floatRow, v.catalogCompletion, v.openingCompletion)
            is com.ukkera.pos.app.LocalSetupMutationResult.Unresolved -> node(282, v.operationId, v.problem, v.kind)
            is com.ukkera.pos.app.LocalSetupOpeningCompletionInput -> node(283, v.stockExplicitZero, v.stockRows, v.floatExplicitZero, v.floatRows)
            is com.ukkera.pos.app.LocalSetupOpeningFloatRow -> node(284, v.sourceId, v.sessionId, v.openingOperationId)
            is com.ukkera.pos.app.LocalSetupOpeningManifestPreview -> node(285, v.stock, v.float)
            is com.ukkera.pos.app.LocalSetupOpeningStockRow -> node(286, v.sourceId, v.receiptId, v.openingOperationId)
            is com.ukkera.pos.app.LocalSetupOpeningView -> node(287, v.stockExplicitZero, v.stockRowCount, v.stockValueTotalMinor, v.floatExplicitZero, v.floatRowCount, v.floatTotalMinor, v.stockManifestDigest, v.floatManifestDigest, v.currency, v.actorId, v.recordedAt, v.operationId)
            is com.ukkera.pos.app.LocalSetupOperationKind -> node(288, v.name)
            is com.ukkera.pos.app.LocalSetupOriginalView -> node(289, v.installationId, v.assignmentId, v.deviceId, v.firstActorId, v.originalRequestId, v.issuedAttemptId, v.attemptSequence, v.definitionDigest, v.genesisDigest, v.receiptDigest, v.installationAuthorityEpoch, v.currentAuthorityEpoch, v.installedAt, v.nativeSettlement)
            is com.ukkera.pos.app.LocalSetupPendingView -> node(290, v.operationId, v.kind, v.phase, v.recorded, v.recordedCode)
            is com.ukkera.pos.app.LocalSetupPrerequisiteKind -> node(291, v.name)
            is com.ukkera.pos.app.LocalSetupPrerequisiteView -> node(292, v.kind, v.settled, v.binding)
            is com.ukkera.pos.app.LocalSetupReadResult.Available<*> -> node(293, v.value)
            is com.ukkera.pos.app.LocalSetupReadResult.Unavailable -> node(294, v.problem)
            is com.ukkera.pos.app.LocalSetupResultView -> node(295, v.original, v.acknowledgement, v.goLive, v.prerequisites)
            is com.ukkera.pos.app.LocalStageResult.NotStaged -> node(296, v.problem, v.field)
            is com.ukkera.pos.app.LocalStageResult.Staged -> node(297, v.operation)
            is com.ukkera.pos.app.LocalStageResult.Uncertain -> node(298, v.operationId, v.accessProblem)
            is com.ukkera.pos.app.LocalText -> node(299, v.english, v.arabic)
            is com.ukkera.pos.app.LocalWorkflow -> node(300, v.name)
            is com.ukkera.pos.app.LocalWorkflowPage -> { require(v.branchReport == null && v.managerQuoteDetails == null && v.food == null && v.openingAllocations == null); node(301 , v.workflow, v.records, v.forms, v.notice, v.nextCursor, v.priceControls, v.returnRights) }
            is com.ukkera.pos.app.LocalWorkflowQuery -> { require(v.inventory == null && v.purchasing == null && v.food == null && v.openingAllocations == null && v.branchReport == null && v.cash == null && v.returns == null); node(302 , v.workflow, v.search, v.reference, v.cursor, v.pricePreview) }
            is com.ukkera.pos.app.SessionDisplayContext -> node(303, v.actorId, v.companyId, v.branchId, v.actorName, v.companyName, v.branchName, v.allowedAreas, v.localDraftEpochs, v.workspaceMode)
            is com.ukkera.pos.app.SignInFailure -> node(304, v.name)
            is com.ukkera.pos.app.navigation.AppArea -> node(305, v.name)
            is com.ukkera.pos.catalog.CatalogAttribute -> node(306, v.key, v.value)
            is com.ukkera.pos.catalog.CatalogAttributeValue.Decimal -> node(307, v.value)
            is com.ukkera.pos.catalog.CatalogAttributeValue.Flag -> node(308, v.value)
            is com.ukkera.pos.catalog.CatalogAttributeValue.Text -> node(309, v.value)
            is com.ukkera.pos.catalog.CatalogStatus -> node(310, v.name)
            is com.ukkera.pos.catalog.CatalogUnit -> node(311, v.id, v.code, v.dimension, v.maximumScale)
            is com.ukkera.pos.catalog.ProductUnitConversion -> node(312, v.id, v.revision, v.productId, v.sourceUnitId, v.targetUnitId, v.rate, v.massVolumeEvidenceId)
            is com.ukkera.pos.catalog.UnitDimension -> node(313, v.name)
            is com.ukkera.pos.catalog.VariantAxis -> node(314, v.id, v.code, v.values)
            is com.ukkera.pos.catalog.VariantValue -> node(315, v.axisId, v.value)
            is com.ukkera.pos.security.ActivatedLocalPolicy -> node(316, LocalSetupPolicyTransferV1.encodePolicy(v))
            is com.ukkera.pos.security.AuthenticationPurpose -> node(317, v.name)
            is com.ukkera.pos.security.BranchScope -> node(318, v.organizationId, v.companyId, v.branchId)
            is com.ukkera.pos.security.CompanyBusinessPublicationKind -> node(319, v.name)
            is com.ukkera.pos.security.CompanyBusinessPublicationPhase -> node(320, v.name)
            is com.ukkera.pos.security.CompanyBusinessPublicationProposal -> node(321, v.company, v.kind, v.publicationId, v.entityId, v.copyCanonicalContent())
            is com.ukkera.pos.security.CompanyBusinessPublicationReference -> node(322, v.originalActionId, v.publicationId, v.entityId, v.phase, v.kind)
            is com.ukkera.pos.security.CompanyScope -> node(323, v.organizationId, v.companyId)
            is com.ukkera.pos.security.LocalAccountAdministrationDetail -> node(324, v.account, v.policy, v.tokenPolicy, v.retainedSessions)
            is com.ukkera.pos.security.LocalAccountSummary -> node(325, v.actorId, v.login, v.enabled, v.credentialPresent, v.accountRevision, v.credentialRevision, v.policyRevision, v.configurationRevision, v.activeSessionCount)
            is com.ukkera.pos.security.LocalAdministrationAction -> node(326, v.name)
            is com.ukkera.pos.security.LocalAdministrationActivationRevisions -> node(327, v.policy, v.configuration)
            is com.ukkera.pos.security.LocalAdministrationCommand -> node(328, v.requestId, v.action, v.targetId, v.expectedRevision, v.sessionId, v.expectedCredentialRevision, v.reactivationRevisions, v.activationRevisions)
            is com.ukkera.pos.security.LocalAdministrationCursor -> node(329, v.afterId, v.snapshot)
            is com.ukkera.pos.security.LocalAdministrationDirectory -> node(330, v.name)
            is com.ukkera.pos.security.LocalAdministrationQuery -> node(331, v.directory, v.targetId, v.cursor, v.pageSize)
            is com.ukkera.pos.security.LocalAdministrationReactivationRevisions -> node(332, v.credential, v.policy, v.configuration)
            is com.ukkera.pos.security.LocalAdministrationReadResult.Available -> node(333, v.view)
            is com.ukkera.pos.security.LocalAdministrationReadResult.Busy -> node(334)
            is com.ukkera.pos.security.LocalAdministrationReadResult.Denied -> node(335)
            is com.ukkera.pos.security.LocalAdministrationReadResult.StaleSnapshot -> node(336)
            is com.ukkera.pos.security.LocalAdministrationReadResult.StorageUncertain -> node(337)
            is com.ukkera.pos.security.LocalAdministrationReadResult.Unavailable -> node(338)
            is com.ukkera.pos.security.LocalAdministrationReceipt -> node(339, v.requestId, v.action, v.targetId, v.originalOperatorId, v.resultingAccountRevision, v.requestDigest)
            is com.ukkera.pos.security.LocalAdministrationView -> node(340, v.scope, v.directory, v.snapshot, v.accounts, v.devices, v.accountDetail, v.next)
            is com.ukkera.pos.security.LocalCompanySerialIssuerOriginV1 -> node(341, v.company, v.foundingActionId, v.rootId, v.rootKeyId, v.rootDocumentDigest, v.rootFingerprint, v.headAuthorityId, v.headEnrollmentId, v.originalIssuerId, v.originalIssuerKeyId, v.originalIssuerDelegationDigest, v.originalScope, v.originalAllocationId, v.originalAllocationDigest)
            is com.ukkera.pos.security.LocalDeviceAdmission -> node(342, v.scope, v.deviceId, v.revision, v.admitted, v.publicKeyIdentifier, v.publicFingerprint, v.capabilities)
            is com.ukkera.pos.security.LocalLoginIdentifier -> node(343, v.value)
            is com.ukkera.pos.security.LocalPublishedSerialAuthorityV1 -> node(344, v.authorityId, v.publicationId, v.origin, v.purpose)
            is com.ukkera.pos.security.LocalPublishedSerialRegistrationV1 -> node(345, v.scope, v.allocationId, v.allocationDigest, v.serialAuthority, v.publicationId, v.registrationId, v.revision, v.previous, v.serialId, v.serialCode, v.skuId, v.skuPublicationId, v.skuPublicationDigest, v.validFrom, v.validUntil)
            is com.ukkera.pos.security.LocalSerialAuthorityReferenceV1 -> node(346, v.authorityId, v.publicationId, v.contentDigest, v.issuerOriginDigest)
            is com.ukkera.pos.security.LocalSerialRegistrationReferenceV1 -> node(347, v.registrationId, v.publicationId, v.revision, v.contentDigest)
            is com.ukkera.pos.security.LocalSessionRecord -> node(348, v.sessionId, v.scope, v.actorId, v.deviceId, v.generation, v.status, v.authenticationPurpose)
            is com.ukkera.pos.security.LocalTokenPolicy -> node(349, v.accessLifetimeMillis, v.workLifetimeMillis)
            is com.ukkera.pos.security.PayloadDigest -> node(350, v.value)
            is com.ukkera.pos.security.SelfOwnedInstallationFailureV1 -> node(351, v.name)
            is com.ukkera.pos.security.SessionGeneration -> node(352, v.value)
            is com.ukkera.pos.security.SessionStatus -> node(353, v.name)
            is com.ukkera.pos.types.Code -> node(354, v.original, v.normalizedKey, v.normalization)
            is com.ukkera.pos.types.CodeNormalization -> node(355, v.name)
            is com.ukkera.pos.types.DomainId -> node(356, v.value)
            is com.ukkera.pos.types.ExactDecimal -> node(357, v.toString())
            is com.ukkera.pos.types.OperationId -> node(358, v.value)
            is com.ukkera.pos.types.Quantity -> node(359, v.unitId, v.scaled, v.scale)
            is com.ukkera.pos.types.Revision -> node(360, v.value)
            is com.ukkera.pos.types.UnitConversionRate -> node(361, v.canonicalString())
            is com.ukkera.pos.types.UtcInstant -> node(362, v.canonicalString())
                else -> error("Unsupported desktop employee DATA")
            } } finally { depth-- }
        }
    }
    private class Reader {
        private var count = 0
        private var depth = 0
        private fun string(v: JsonElement): String = read(v) as String
        private fun long(v: JsonElement): Long = read(v) as Long
        fun read(v: JsonElement): Any? {
            require(++count <= MAX_NODES && ++depth <= MAX_DEPTH)
            try {
                if (v === JsonNull) return null
                val a = v.jsonArray
                require(a.isNotEmpty() && a.size <= MAX_COLLECTION * 2 + 1 && !a[0].jsonPrimitive.isString)
                return when (a[0].jsonPrimitive.int) {
                    1 -> { require(a.size == 2 && a[1].jsonPrimitive.isString); a[1].jsonPrimitive.content.also { require(it.length <= MAX_STRING) } }
                    2 -> { require(a.size == 2 && !a[1].jsonPrimitive.isString); a[1].jsonPrimitive.boolean }
                    3 -> { require(a.size == 2 && !a[1].jsonPrimitive.isString); a[1].jsonPrimitive.int }
                    4 -> { require(a.size == 2 && !a[1].jsonPrimitive.isString); a[1].jsonPrimitive.long }
                    5 -> { require(a.size - 1 <= MAX_COLLECTION); a.drop(1).map(::read) }
                    6 -> { require(a.size - 1 <= MAX_COLLECTION); a.drop(1).map(::read).toSet().also { require(it.size == a.size - 1) } }
                    7 -> { require((a.size - 1) % 2 == 0 && (a.size - 1) / 2 <= MAX_COLLECTION); a.drop(1).chunked(2).associate { read(it[0]) to read(it[1]) }.also { require(it.size == (a.size - 1) / 2) } }
                    8 -> { require(a.size == 2); Base64.getDecoder().decode(string(a[1])).also { require(it.size <= MAX_STRING) } }
                100 -> { require(a.size == 2); com.ukkera.pos.app.LocalAcknowledgementResult.Acknowledged(operationId = read(a[1]) as com.ukkera.pos.types.OperationId) }
                101 -> { require(a.size == 3); com.ukkera.pos.app.LocalAcknowledgementResult.Unresolved(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, problem = read(a[2]) as com.ukkera.pos.app.LocalServiceProblem) }
                102 -> { require(a.size == 2); com.ukkera.pos.app.LocalAction.valueOf(string(a[1])) }
                103 -> { require(a.size == 8); com.ukkera.pos.app.LocalActionForm(action = read(a[1]) as com.ukkera.pos.app.LocalAction, version = read(a[2]) as Int, title = read(a[3]) as com.ukkera.pos.app.LocalText, fields = read(a[4]) as List<com.ukkera.pos.app.LocalFormField>, explanation = read(a[5]) as com.ukkera.pos.app.LocalText?, confirmation = read(a[6]) as com.ukkera.pos.app.LocalText?, reservedOperationId = read(a[7]) as com.ukkera.pos.types.OperationId?) }
                104 -> { require(a.size == 5); com.ukkera.pos.app.LocalAdministrationPending(command = read(a[1]) as com.ukkera.pos.security.LocalAdministrationCommand, phase = read(a[2]) as com.ukkera.pos.app.LocalAdministrationPhase, cancellationRequested = read(a[3]) as Boolean, receipt = read(a[4]) as com.ukkera.pos.security.LocalAdministrationReceipt?) }
                105 -> { require(a.size == 2); com.ukkera.pos.app.LocalAdministrationPendingResult.Available(operation = read(a[1]) as com.ukkera.pos.app.LocalAdministrationPending?) }
                106 -> { require(a.size == 2); com.ukkera.pos.app.LocalAdministrationPendingResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem) }
                107 -> { require(a.size == 2); com.ukkera.pos.app.LocalAdministrationPhase.valueOf(string(a[1])) }
                108 -> { require(a.size == 2); com.ukkera.pos.app.LocalAdministrationResult.Known(operation = read(a[1]) as com.ukkera.pos.app.LocalAdministrationPending) }
                109 -> { require(a.size == 2); com.ukkera.pos.app.LocalAdministrationResult.NotPrepared(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem) }
                110 -> { require(a.size == 3); com.ukkera.pos.app.LocalAdministrationResult.Unresolved(requestId = read(a[1]) as com.ukkera.pos.types.DomainId, problem = read(a[2]) as com.ukkera.pos.app.LocalServiceProblem) }
                111 -> { require(a.size == 7); com.ukkera.pos.app.LocalCatalogReleaseAuthoringSource(catalogId = read(a[1]) as com.ukkera.pos.types.DomainId, nextRevision = read(a[2]) as com.ukkera.pos.types.Revision?, previous = read(a[3]) as com.ukkera.pos.app.LocalCatalogReleasePublicationReference?, activeStorageRevision = read(a[4]) as com.ukkera.pos.types.Revision?, active = read(a[5]) as com.ukkera.pos.app.LocalCatalogReleasePublicationReference?, members = read(a[6]) as List<com.ukkera.pos.app.LocalCatalogReleaseMemberSource>) }
                112 -> { require(a.size == 7); com.ukkera.pos.app.LocalCatalogReleaseMemberInput(publicationId = read(a[1]) as com.ukkera.pos.types.DomainId, contentDigest = read(a[2]) as com.ukkera.pos.security.PayloadDigest, skuId = read(a[3]) as com.ukkera.pos.types.DomainId, skuRevision = read(a[4]) as com.ukkera.pos.types.Revision, productId = read(a[5]) as com.ukkera.pos.types.DomainId, productRevision = read(a[6]) as com.ukkera.pos.types.Revision) }
                113 -> { require(a.size == 6); com.ukkera.pos.app.LocalCatalogReleaseMemberSource(member = read(a[1]) as com.ukkera.pos.app.LocalCatalogReleaseMemberInput, skuNames = read(a[2]) as com.ukkera.pos.app.LocalText, productNames = read(a[3]) as com.ukkera.pos.app.LocalText, skuCode = read(a[4]) as String, active = read(a[5]) as Boolean) }
                114 -> { require(a.size == 4); com.ukkera.pos.app.LocalCatalogReleasePublicationCompleted(originalActionId = read(a[1]) as com.ukkera.pos.types.DomainId, preview = read(a[2]) as com.ukkera.pos.app.LocalCatalogReleasePublicationPreview, rootFingerprint = read(a[3]) as String) }
                115 -> { require(a.size == 6); com.ukkera.pos.app.LocalCatalogReleasePublicationDraft(publicationId = read(a[1]) as com.ukkera.pos.types.DomainId, catalogId = read(a[2]) as com.ukkera.pos.types.DomainId, revision = read(a[3]) as com.ukkera.pos.types.Revision, previous = read(a[4]) as com.ukkera.pos.app.LocalCatalogReleasePublicationReference?, members = read(a[5]) as List<com.ukkera.pos.app.LocalCatalogReleaseMemberInput>) }
                116 -> { require(a.size == 3); com.ukkera.pos.app.LocalCatalogReleasePublicationPreview(companyId = read(a[1]) as com.ukkera.pos.types.DomainId, definition = read(a[2]) as com.ukkera.pos.app.LocalCatalogReleasePublicationDraft) }
                117 -> { require(a.size == 5); com.ukkera.pos.app.LocalCatalogReleasePublicationReference(catalogId = read(a[1]) as com.ukkera.pos.types.DomainId, publicationId = read(a[2]) as com.ukkera.pos.types.DomainId, revision = read(a[3]) as com.ukkera.pos.types.Revision, contentDigest = read(a[4]) as com.ukkera.pos.security.PayloadDigest) }
                118 -> { require(a.size == 2); com.ukkera.pos.app.LocalCatalogReleasePublicationResult.Completed(publication = read(a[1]) as com.ukkera.pos.app.LocalCatalogReleasePublicationCompleted) }
                119 -> { require(a.size == 2); com.ukkera.pos.app.LocalCatalogReleasePublicationResult.Review(review = read(a[1]) as com.ukkera.pos.app.LocalCatalogReleasePublicationReview) }
                120 -> { require(a.size == 3); com.ukkera.pos.app.LocalCatalogReleasePublicationResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                121 -> { require(a.size == 4); com.ukkera.pos.app.LocalCatalogReleasePublicationReview.remote(read(a[1]) as LocalCompanyPublicationReviewData, read(a[2]) as LocalCatalogReleasePublicationPreview, read(a[3]) as DomainId) }
                122 -> { require(a.size == 5); com.ukkera.pos.app.LocalCatalogReleasePublicationStatus(references = read(a[1]) as List<com.ukkera.pos.security.CompanyBusinessPublicationReference>, source = read(a[2]) as com.ukkera.pos.app.LocalCatalogReleaseAuthoringSource?, mayPrepare = read(a[3]) as Boolean, masterObservedAt = read(a[4]) as com.ukkera.pos.types.UtcInstant) }
                123 -> { require(a.size == 2); com.ukkera.pos.app.LocalCatalogReleasePublicationStatusResult.Available(status = read(a[1]) as com.ukkera.pos.app.LocalCatalogReleasePublicationStatus) }
                124 -> { require(a.size == 3); com.ukkera.pos.app.LocalCatalogReleasePublicationStatusResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                125 -> { require(a.size == 3); com.ukkera.pos.app.LocalChoice(code = read(a[1]) as String, label = read(a[2]) as com.ukkera.pos.app.LocalText) }
                126 -> { require(a.size == 4); com.ukkera.pos.app.LocalClientCleanupStatus(available = read(a[1]) as Boolean, pendingRevocations = read(a[2]) as Int, authenticationUnresolved = read(a[3]) as Boolean) }
                127 -> { require(a.size == 8); com.ukkera.pos.app.LocalCommandIntent(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, action = read(a[2]) as com.ukkera.pos.app.LocalAction, formVersion = read(a[3]) as Int, reference = read(a[4]) as com.ukkera.pos.app.LocalRecordReference?, values = read(a[5]) as List<com.ukkera.pos.app.LocalScalarInput>, lineField = read(a[6]) as com.ukkera.pos.app.LocalField?, lines = read(a[7]) as List<com.ukkera.pos.app.LocalInputLine>) }
                128 -> { require(a.size == 2); com.ukkera.pos.app.LocalCompanyCatalogApprovalResult.Completed(publication = read(a[1]) as com.ukkera.pos.app.LocalCompanyCatalogCompleted) }
                129 -> { require(a.size == 3); com.ukkera.pos.app.LocalCompanyCatalogApprovalResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                130 -> { require(a.size == 4); com.ukkera.pos.app.LocalCompanyCatalogCompleted(originalActionId = read(a[1]) as com.ukkera.pos.types.DomainId, preview = read(a[2]) as com.ukkera.pos.app.LocalCompanySkuPreview, rootFingerprint = read(a[3]) as String) }
                131 -> { require(a.size == 4); com.ukkera.pos.app.LocalCompanyCatalogReview.remote(read(a[1]) as LocalCompanyPublicationReviewData, read(a[2]) as LocalCompanySkuPreview, read(a[3]) as DomainId) }
                132 -> { require(a.size == 2); com.ukkera.pos.app.LocalCompanyCatalogReviewResult.Completed(publication = read(a[1]) as com.ukkera.pos.app.LocalCompanyCatalogCompleted) }
                133 -> { require(a.size == 2); com.ukkera.pos.app.LocalCompanyCatalogReviewResult.Review(review = read(a[1]) as com.ukkera.pos.app.LocalCompanyCatalogReview) }
                134 -> { require(a.size == 3); com.ukkera.pos.app.LocalCompanyCatalogReviewResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                135 -> { require(a.size == 5); com.ukkera.pos.app.LocalCompanyCatalogStatus(references = read(a[1]) as List<com.ukkera.pos.security.CompanyBusinessPublicationReference>, mayPrepareNewItem = read(a[2]) as Boolean, masterObservedAt = read(a[3]) as com.ukkera.pos.types.UtcInstant, catalogCut = read(a[4]) as com.ukkera.pos.types.Revision?) }
                136 -> { require(a.size == 2); com.ukkera.pos.app.LocalCompanyCatalogStatusResult.Available(status = read(a[1]) as com.ukkera.pos.app.LocalCompanyCatalogStatus) }
                137 -> { require(a.size == 3); com.ukkera.pos.app.LocalCompanyCatalogStatusResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                138 -> { require(a.size == 2); com.ukkera.pos.app.LocalCompanyFoodOutputSkuDraft(sku = read(a[1]) as com.ukkera.pos.app.LocalCompanySkuDraft) }
                139 -> { require(a.size == 2); com.ukkera.pos.app.LocalCompanyIngredientSkuDraft(sku = read(a[1]) as com.ukkera.pos.app.LocalCompanySkuDraft) }
                140 -> { require(a.size == 2); com.ukkera.pos.app.LocalCompanyPriceTaxInclusion.valueOf(string(a[1])) }
                141 -> { require(a.size == 8); com.ukkera.pos.app.LocalCompanyProductDraft(englishName = read(a[1]) as String, arabicName = read(a[2]) as String, categoryIds = read(a[3]) as List<com.ukkera.pos.types.DomainId>, variantAxes = read(a[4]) as List<com.ukkera.pos.catalog.VariantAxis>, attributes = read(a[5]) as List<com.ukkera.pos.catalog.CatalogAttribute>, status = read(a[6]) as com.ukkera.pos.catalog.CatalogStatus, allowedVariantCombinations = read(a[7]) as List<List<com.ukkera.pos.catalog.VariantValue>>?) }
                142 -> { require(a.size == 3); com.ukkera.pos.app.LocalCompanyProductPreview(revision = read(a[1]) as com.ukkera.pos.types.Revision, definition = read(a[2]) as com.ukkera.pos.app.LocalCompanyProductDraft) }
                143 -> { require(a.size == 8); com.ukkera.pos.app.LocalCompanyPublicationReviewData(originalActionId = read(a[1]) as com.ukkera.pos.types.DomainId, proposal = read(a[2]) as com.ukkera.pos.security.CompanyBusinessPublicationProposal, signingKeyId = read(a[3]) as com.ukkera.pos.types.DomainId, rootFingerprint = read(a[4]) as String, companyName = read(a[5]) as String, headName = read(a[6]) as String, headLogin = read(a[7]) as String) }
                144 -> { require(a.size == 9); com.ukkera.pos.app.LocalCompanyReturnPromiseDraft(policyId = read(a[1]) as com.ukkera.pos.types.DomainId, policyRevision = read(a[2]) as com.ukkera.pos.types.Revision, categoryCode = read(a[3]) as String, englishTerms = read(a[4]) as String, arabicTerms = read(a[5]) as String, changeOfMindReceiptWindowDays = read(a[6]) as Int?, defectiveReceiptWindowDays = read(a[7]) as Int?, defectiveResolutionDays = read(a[8]) as Int?) }
                145 -> { require(a.size == 24); com.ukkera.pos.app.LocalCompanySkuDraft(publicationId = read(a[1]) as com.ukkera.pos.types.DomainId, skuId = read(a[2]) as com.ukkera.pos.types.DomainId, productId = read(a[3]) as com.ukkera.pos.types.DomainId, baseUnitId = read(a[4]) as com.ukkera.pos.types.DomainId, priceRuleId = read(a[5]) as com.ukkera.pos.types.DomainId, taxPolicyId = read(a[6]) as com.ukkera.pos.types.DomainId, englishName = read(a[7]) as String, arabicName = read(a[8]) as String, code = read(a[9]) as String, barcodes = read(a[10]) as List<String>, unitPriceEgp = read(a[11]) as String, taxCategory = read(a[12]) as String, taxPercent = read(a[13]) as String, taxInclusion = read(a[14]) as com.ukkera.pos.app.LocalCompanyPriceTaxInclusion, canPurchase = read(a[15]) as Boolean, quantityScale = read(a[16]) as Int, tracking = read(a[17]) as com.ukkera.pos.app.LocalCompanyStockTracking, requiresExpiry = read(a[18]) as Boolean, returnPromise = read(a[19]) as com.ukkera.pos.app.LocalCompanyReturnPromiseDraft?, unitDefinitions = read(a[20]) as List<com.ukkera.pos.catalog.CatalogUnit>?, conversions = read(a[21]) as List<com.ukkera.pos.catalog.ProductUnitConversion>, productDefinition = read(a[22]) as com.ukkera.pos.app.LocalCompanyProductDraft?, variantValues = read(a[23]) as List<com.ukkera.pos.catalog.VariantValue>) }
                146 -> { require(a.size == 2); com.ukkera.pos.app.LocalCompanySkuFulfillment.valueOf(string(a[1])) }
                147 -> { require(a.size == 29); com.ukkera.pos.app.LocalCompanySkuPreview(publicationId = read(a[1]) as com.ukkera.pos.types.DomainId, skuId = read(a[2]) as com.ukkera.pos.types.DomainId, productId = read(a[3]) as com.ukkera.pos.types.DomainId, baseUnitId = read(a[4]) as com.ukkera.pos.types.DomainId, catalogRevision = read(a[5]) as com.ukkera.pos.types.Revision, skuRevision = read(a[6]) as com.ukkera.pos.types.Revision, englishName = read(a[7]) as String, arabicName = read(a[8]) as String, code = read(a[9]) as String, barcodes = read(a[10]) as List<String>, unitPriceEgp = read(a[11]) as String, taxCategory = read(a[12]) as String, taxPercent = read(a[13]) as String, taxInclusion = read(a[14]) as com.ukkera.pos.app.LocalCompanyPriceTaxInclusion, taxPolicyId = read(a[15]) as com.ukkera.pos.types.DomainId, priceRuleId = read(a[16]) as com.ukkera.pos.types.DomainId, effectiveFrom = read(a[17]) as com.ukkera.pos.types.UtcInstant, canPurchase = read(a[18]) as Boolean, quantityScale = read(a[19]) as Int, tracking = read(a[20]) as com.ukkera.pos.app.LocalCompanyStockTracking, requiresExpiry = read(a[21]) as Boolean, fulfillment = read(a[22]) as com.ukkera.pos.app.LocalCompanySkuFulfillment, canSell = read(a[23]) as Boolean, returnPromise = read(a[24]) as com.ukkera.pos.app.LocalCompanyReturnPromiseDraft?, unitDefinitions = read(a[25]) as List<com.ukkera.pos.catalog.CatalogUnit>?, conversions = read(a[26]) as List<com.ukkera.pos.catalog.ProductUnitConversion>, productDefinition = read(a[27]) as com.ukkera.pos.app.LocalCompanyProductPreview?, variantValues = read(a[28]) as List<com.ukkera.pos.catalog.VariantValue>?) }
                148 -> { require(a.size == 2); com.ukkera.pos.app.LocalCompanyStockTracking.valueOf(string(a[1])) }
                149 -> { require(a.size == 2); com.ukkera.pos.app.LocalField.valueOf(string(a[1])) }
                150 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationActivity.valueOf(string(a[1])) }
                151 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationChannel.valueOf(string(a[1])) }
                152 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationDetail.Recipe(source = read(a[1]) as com.ukkera.pos.app.LocalFoodRecipeSource) }
                153 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationDetail.Route(source = read(a[1]) as com.ukkera.pos.app.LocalFoodRouteSource) }
                154 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationDetail.Sku(source = read(a[1]) as com.ukkera.pos.app.LocalFoodSkuSource) }
                155 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationDetail.Station(source = read(a[1]) as com.ukkera.pos.app.LocalFoodStationSource) }
                156 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationDetail.Warehouse(source = read(a[1]) as com.ukkera.pos.app.LocalFoodWarehouseSource) }
                157 -> { require(a.size == 6); com.ukkera.pos.app.LocalFoodConfigurationLabel(id = read(a[1]) as com.ukkera.pos.types.DomainId, revision = read(a[2]) as com.ukkera.pos.types.Revision, storageRevision = read(a[3]) as com.ukkera.pos.types.Revision, names = read(a[4]) as com.ukkera.pos.app.LocalText, active = read(a[5]) as Boolean) }
                158 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationReadResult.Detail(detail = read(a[1]) as com.ukkera.pos.app.LocalFoodConfigurationDetail?) }
                159 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationReadResult.Page(page = read(a[1]) as com.ukkera.pos.app.LocalFoodConfigurationSourcePage) }
                160 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationReadResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem) }
                161 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationSourceKind.valueOf(string(a[1])) }
                162 -> { require(a.size == 4); com.ukkera.pos.app.LocalFoodConfigurationSourcePage(kind = read(a[1]) as com.ukkera.pos.app.LocalFoodConfigurationSourceKind, labels = read(a[2]) as List<com.ukkera.pos.app.LocalFoodConfigurationLabel>, nextAfter = read(a[3]) as com.ukkera.pos.types.DomainId?) }
                163 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationStationMode.valueOf(string(a[1])) }
                164 -> { require(a.size == 3); com.ukkera.pos.app.LocalFoodConfigurationStatus(references = read(a[1]) as List<com.ukkera.pos.security.CompanyBusinessPublicationReference>, observedAt = read(a[2]) as com.ukkera.pos.types.UtcInstant) }
                165 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodConfigurationStatusResult.Available(status = read(a[1]) as com.ukkera.pos.app.LocalFoodConfigurationStatus) }
                166 -> { require(a.size == 3); com.ukkera.pos.app.LocalFoodConfigurationStatusResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                167 -> { require(a.size == 7); com.ukkera.pos.app.LocalFoodRecipeChoiceDraft(id = read(a[1]) as com.ukkera.pos.types.DomainId, names = read(a[2]) as com.ukkera.pos.app.LocalText, modifierIds = read(a[3]) as List<com.ukkera.pos.types.DomainId>, ingredients = read(a[4]) as List<com.ukkera.pos.app.LocalFoodRecipeIngredientDraft>, routeKeys = read(a[5]) as List<com.ukkera.pos.types.DomainId>, pricingSku = read(a[6]) as com.ukkera.pos.app.LocalFoodSkuSource) }
                168 -> { require(a.size == 7); com.ukkera.pos.app.LocalFoodRecipeChoicePreview(id = read(a[1]) as com.ukkera.pos.types.DomainId, names = read(a[2]) as com.ukkera.pos.app.LocalText, modifierIds = read(a[3]) as List<com.ukkera.pos.types.DomainId>, ingredients = read(a[4]) as List<com.ukkera.pos.app.LocalFoodRecipeIngredientPreview>, routeKeys = read(a[5]) as List<com.ukkera.pos.types.DomainId>, pricingSkuId = read(a[6]) as com.ukkera.pos.types.DomainId) }
                169 -> { require(a.size == 4); com.ukkera.pos.app.LocalFoodRecipeCompleted(originalActionId = read(a[1]) as com.ukkera.pos.types.DomainId, preview = read(a[2]) as com.ukkera.pos.app.LocalFoodRecipePreview, rootFingerprint = read(a[3]) as String) }
                170 -> { require(a.size == 8); com.ukkera.pos.app.LocalFoodRecipeDraft(publicationId = read(a[1]) as com.ukkera.pos.types.DomainId, recipeId = read(a[2]) as com.ukkera.pos.types.DomainId, outputSku = read(a[3]) as com.ukkera.pos.app.LocalFoodSkuSource, expectedRecipeRevision = read(a[4]) as com.ukkera.pos.types.Revision?, yieldQuantity = read(a[5]) as String, portionQuantity = read(a[6]) as String, choices = read(a[7]) as List<com.ukkera.pos.app.LocalFoodRecipeChoiceDraft>) }
                171 -> { require(a.size == 4); com.ukkera.pos.app.LocalFoodRecipeIngredientDraft(componentId = read(a[1]) as com.ukkera.pos.types.DomainId, sku = read(a[2]) as com.ukkera.pos.app.LocalFoodSkuSource, quantityPerYield = read(a[3]) as String) }
                172 -> { require(a.size == 5); com.ukkera.pos.app.LocalFoodRecipeIngredientPreview(componentId = read(a[1]) as com.ukkera.pos.types.DomainId, skuId = read(a[2]) as com.ukkera.pos.types.DomainId, skuRevision = read(a[3]) as com.ukkera.pos.types.Revision, quantityPerYield = read(a[4]) as com.ukkera.pos.types.Quantity) }
                173 -> { require(a.size == 10); com.ukkera.pos.app.LocalFoodRecipePreview(publicationId = read(a[1]) as com.ukkera.pos.types.DomainId, recipeId = read(a[2]) as com.ukkera.pos.types.DomainId, revision = read(a[3]) as com.ukkera.pos.types.Revision, outputSkuId = read(a[4]) as com.ukkera.pos.types.DomainId, outputSkuRevision = read(a[5]) as com.ukkera.pos.types.Revision, yieldQuantity = read(a[6]) as com.ukkera.pos.types.Quantity, portionQuantity = read(a[7]) as com.ukkera.pos.types.Quantity, choices = read(a[8]) as List<com.ukkera.pos.app.LocalFoodRecipeChoicePreview>, active = read(a[9]) as Boolean) }
                174 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodRecipeResult.Completed(publication = read(a[1]) as com.ukkera.pos.app.LocalFoodRecipeCompleted) }
                175 -> { require(a.size == 2); com.ukkera.pos.app.LocalFoodRecipeResult.Review(review = read(a[1]) as com.ukkera.pos.app.LocalFoodRecipeReview) }
                176 -> { require(a.size == 3); com.ukkera.pos.app.LocalFoodRecipeResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                177 -> { require(a.size == 4); com.ukkera.pos.app.LocalFoodRecipeReview.remote(read(a[1]) as LocalCompanyPublicationReviewData, read(a[2]) as LocalFoodRecipePreview, read(a[3]) as DomainId) }
                178 -> { require(a.size == 8); com.ukkera.pos.app.LocalFoodRecipeSource(id = read(a[1]) as com.ukkera.pos.types.DomainId, revision = read(a[2]) as com.ukkera.pos.types.Revision, storageRevision = read(a[3]) as com.ukkera.pos.types.Revision, outputSkuId = read(a[4]) as com.ukkera.pos.types.DomainId, names = read(a[5]) as com.ukkera.pos.app.LocalText, routeKeys = read(a[6]) as List<com.ukkera.pos.types.DomainId>, active = read(a[7]) as Boolean) }
                179 -> { require(a.size == 9); com.ukkera.pos.app.LocalFoodRouteDraft(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, routeId = read(a[2]) as com.ukkera.pos.types.DomainId, expectedStorageRevision = read(a[3]) as com.ukkera.pos.types.Revision?, recipe = read(a[4]) as com.ukkera.pos.app.LocalFoodRecipeSource, activity = read(a[5]) as com.ukkera.pos.app.LocalFoodConfigurationActivity, channel = read(a[6]) as com.ukkera.pos.app.LocalFoodConfigurationChannel, warehouse = read(a[7]) as com.ukkera.pos.app.LocalFoodWarehouseSource, targets = read(a[8]) as List<com.ukkera.pos.app.LocalFoodRouteTarget>) }
                180 -> { require(a.size == 10); com.ukkera.pos.app.LocalFoodRouteSource(id = read(a[1]) as com.ukkera.pos.types.DomainId, storageRevision = read(a[2]) as com.ukkera.pos.types.Revision, recipeId = read(a[3]) as com.ukkera.pos.types.DomainId, recipeRevision = read(a[4]) as com.ukkera.pos.types.Revision, activity = read(a[5]) as com.ukkera.pos.app.LocalFoodConfigurationActivity, channel = read(a[6]) as com.ukkera.pos.app.LocalFoodConfigurationChannel, warehouseId = read(a[7]) as com.ukkera.pos.types.DomainId, targets = read(a[8]) as Map<com.ukkera.pos.types.DomainId, com.ukkera.pos.types.DomainId>, active = read(a[9]) as Boolean) }
                181 -> { require(a.size == 3); com.ukkera.pos.app.LocalFoodRouteTarget(routeKey = read(a[1]) as com.ukkera.pos.types.DomainId, station = read(a[2]) as com.ukkera.pos.app.LocalFoodStationSource) }
                182 -> { require(a.size == 13); com.ukkera.pos.app.LocalFoodSkuSource(id = read(a[1]) as com.ukkera.pos.types.DomainId, skuRevision = read(a[2]) as com.ukkera.pos.types.Revision, storageRevision = read(a[3]) as com.ukkera.pos.types.Revision, productId = read(a[4]) as com.ukkera.pos.types.DomainId, baseUnitId = read(a[5]) as com.ukkera.pos.types.DomainId, quantityScale = read(a[6]) as Int, names = read(a[7]) as com.ukkera.pos.app.LocalText, tracking = read(a[8]) as com.ukkera.pos.app.LocalCompanyStockTracking?, fulfillment = read(a[9]) as com.ukkera.pos.app.LocalCompanySkuFulfillment?, active = read(a[10]) as Boolean, canSell = read(a[11]) as Boolean, canPurchase = read(a[12]) as Boolean) }
                183 -> { require(a.size == 6); com.ukkera.pos.app.LocalFoodStationDraft(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, stationId = read(a[2]) as com.ukkera.pos.types.DomainId, expectedStorageRevision = read(a[3]) as com.ukkera.pos.types.Revision?, names = read(a[4]) as com.ukkera.pos.app.LocalText, mode = read(a[5]) as com.ukkera.pos.app.LocalFoodConfigurationStationMode) }
                184 -> { require(a.size == 6); com.ukkera.pos.app.LocalFoodStationSource(id = read(a[1]) as com.ukkera.pos.types.DomainId, storageRevision = read(a[2]) as com.ukkera.pos.types.Revision, names = read(a[3]) as com.ukkera.pos.app.LocalText, mode = read(a[4]) as com.ukkera.pos.app.LocalFoodConfigurationStationMode, active = read(a[5]) as Boolean) }
                185 -> { require(a.size == 5); com.ukkera.pos.app.LocalFoodWarehouseSource(id = read(a[1]) as com.ukkera.pos.types.DomainId, storageRevision = read(a[2]) as com.ukkera.pos.types.Revision, names = read(a[3]) as com.ukkera.pos.app.LocalText, active = read(a[4]) as Boolean) }
                186 -> { require(a.size == 9); com.ukkera.pos.app.LocalFormField.Lines(field = read(a[1]) as com.ukkera.pos.app.LocalField, label = read(a[2]) as com.ukkera.pos.app.LocalText, columns = read(a[3]) as List<com.ukkera.pos.app.LocalFormField.Scalar>, minimumLines = read(a[4]) as Int, maximumLines = read(a[5]) as Int, initialLines = read(a[6]) as List<com.ukkera.pos.app.LocalInputLine>, allowBlankLines = read(a[7]) as Boolean, allowDuplicateLines = read(a[8]) as Boolean) }
                187 -> { require(a.size == 13); com.ukkera.pos.app.LocalFormField.Scalar(field = read(a[1]) as com.ukkera.pos.app.LocalField, label = read(a[2]) as com.ukkera.pos.app.LocalText, kind = read(a[3]) as com.ukkera.pos.app.LocalInputKind, required = read(a[4]) as Boolean, help = read(a[5]) as com.ukkera.pos.app.LocalText?, maximumCharacters = read(a[6]) as Int, allowNegative = read(a[7]) as Boolean, choices = read(a[8]) as List<com.ukkera.pos.app.LocalChoice>, references = read(a[9]) as List<com.ukkera.pos.app.LocalReferenceChoice>, initialValue = read(a[10]) as com.ukkera.pos.app.LocalScalarValue?, readOnly = read(a[11]) as Boolean, referenceSource = read(a[12]) as com.ukkera.pos.app.LocalReferenceSource?) }
                188 -> { require(a.size == 2); com.ukkera.pos.app.LocalInputKind.valueOf(string(a[1])) }
                189 -> { require(a.size == 3); com.ukkera.pos.app.LocalInputLine(lineId = read(a[1]) as com.ukkera.pos.types.DomainId, values = read(a[2]) as List<com.ukkera.pos.app.LocalScalarInput>) }
                190 -> { require(a.size == 2); com.ukkera.pos.app.LocalInvoiceEntryKind.valueOf(string(a[1])) }
                191 -> { require(a.size == 2); com.ukkera.pos.app.LocalOperationResult.Pending(operation = read(a[1]) as com.ukkera.pos.app.LocalPendingOperation) }
                192 -> { require(a.size == 7); com.ukkera.pos.app.LocalOperationResult.Recorded(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, decision = read(a[2]) as com.ukkera.pos.app.LocalRecordedDecision, message = read(a[3]) as com.ukkera.pos.app.LocalText, result = read(a[4]) as com.ukkera.pos.app.LocalRecord?, replayed = read(a[5]) as Boolean, originalAction = read(a[6]) as com.ukkera.pos.app.LocalAction?) }
                193 -> { require(a.size == 3); com.ukkera.pos.app.LocalOperationResult.Unresolved(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, problem = read(a[2]) as com.ukkera.pos.app.LocalServiceProblem) }
                194 -> { require(a.size == 8); com.ukkera.pos.app.LocalPendingOperation(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, action = read(a[2]) as com.ukkera.pos.app.LocalAction, label = read(a[3]) as com.ukkera.pos.app.LocalText, summary = read(a[4]) as List<com.ukkera.pos.app.LocalRecordValue>, submitAllowed = read(a[5]) as Boolean, phase = read(a[6]) as com.ukkera.pos.app.LocalPendingPhase, recordedDecision = read(a[7]) as com.ukkera.pos.app.LocalRecordedDecision?) }
                195 -> { require(a.size == 2); com.ukkera.pos.app.LocalPendingPhase.valueOf(string(a[1])) }
                196 -> { require(a.size == 3); com.ukkera.pos.app.LocalPendingResult.Available(operations = read(a[1]) as List<com.ukkera.pos.app.LocalPendingOperation>, nextCursor = read(a[2]) as String?) }
                197 -> { require(a.size == 2); com.ukkera.pos.app.LocalPendingResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem) }
                198 -> { require(a.size == 4); com.ukkera.pos.app.LocalPricePolicyPublicationCompleted(originalActionId = read(a[1]) as com.ukkera.pos.types.DomainId, preview = read(a[2]) as com.ukkera.pos.app.LocalPricePolicyPublicationPreview, rootFingerprint = read(a[3]) as String) }
                199 -> { require(a.size == 10); com.ukkera.pos.app.LocalPricePolicyPublicationDraft(publicationId = read(a[1]) as com.ukkera.pos.types.DomainId, entryId = read(a[2]) as com.ukkera.pos.types.DomainId, revision = read(a[3]) as com.ukkera.pos.types.Revision, skuId = read(a[4]) as com.ukkera.pos.types.DomainId, ruleId = read(a[5]) as com.ukkera.pos.types.DomainId, branchOverridesEnabled = read(a[6]) as Boolean, authorizationReferenceQuantity = read(a[7]) as com.ukkera.pos.types.Quantity, effectiveFrom = read(a[8]) as com.ukkera.pos.types.UtcInstant, effectiveUntil = read(a[9]) as com.ukkera.pos.types.UtcInstant?) }
                200 -> { require(a.size == 3); com.ukkera.pos.app.LocalPricePolicyPublicationPreview(companyId = read(a[1]) as com.ukkera.pos.types.DomainId, definition = read(a[2]) as com.ukkera.pos.app.LocalPricePolicyPublicationDraft) }
                201 -> { require(a.size == 2); com.ukkera.pos.app.LocalPricePolicyPublicationResult.Completed(publication = read(a[1]) as com.ukkera.pos.app.LocalPricePolicyPublicationCompleted) }
                202 -> { require(a.size == 2); com.ukkera.pos.app.LocalPricePolicyPublicationResult.Review(review = read(a[1]) as com.ukkera.pos.app.LocalPricePolicyPublicationReview) }
                203 -> { require(a.size == 3); com.ukkera.pos.app.LocalPricePolicyPublicationResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                204 -> { require(a.size == 4); com.ukkera.pos.app.LocalPricePolicyPublicationReview.remote(read(a[1]) as LocalCompanyPublicationReviewData, read(a[2]) as LocalPricePolicyPublicationPreview, read(a[3]) as DomainId) }
                205 -> { require(a.size == 4); com.ukkera.pos.app.LocalPricePolicyPublicationStatus(references = read(a[1]) as List<com.ukkera.pos.security.CompanyBusinessPublicationReference>, mayPrepare = read(a[2]) as Boolean, masterObservedAt = read(a[3]) as com.ukkera.pos.types.UtcInstant) }
                206 -> { require(a.size == 2); com.ukkera.pos.app.LocalPricePolicyPublicationStatusResult.Available(status = read(a[1]) as com.ukkera.pos.app.LocalPricePolicyPublicationStatus) }
                207 -> { require(a.size == 3); com.ukkera.pos.app.LocalPricePolicyPublicationStatusResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                208 -> { require(a.size == 4); com.ukkera.pos.app.LocalPricePreviewQuery(skuId = read(a[1]) as com.ukkera.pos.types.DomainId, channel = read(a[2]) as String, quantity = read(a[3]) as com.ukkera.pos.app.LocalScalarValue.Decimal) }
                209 -> { require(a.size == 4); com.ukkera.pos.app.LocalPriceReadControls(skuId = read(a[1]) as com.ukkera.pos.types.DomainId, unitId = read(a[2]) as com.ukkera.pos.types.DomainId, quantityScale = read(a[3]) as Int) }
                210 -> { require(a.size == 2); com.ukkera.pos.app.LocalReadResult.Available(page = read(a[1]) as com.ukkera.pos.app.LocalWorkflowPage) }
                211 -> { require(a.size == 2); com.ukkera.pos.app.LocalReadResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem) }
                212 -> { require(a.size == 8); com.ukkera.pos.app.LocalRecord(reference = read(a[1]) as com.ukkera.pos.app.LocalRecordReference, title = read(a[2]) as com.ukkera.pos.app.LocalText, status = read(a[3]) as com.ukkera.pos.app.LocalText?, values = read(a[4]) as List<com.ukkera.pos.app.LocalRecordValue>, selectable = read(a[5]) as Boolean, supplierId = read(a[6]) as com.ukkera.pos.types.DomainId?, invoiceEntryKind = read(a[7]) as com.ukkera.pos.app.LocalInvoiceEntryKind?) }
                213 -> { require(a.size == 2); com.ukkera.pos.app.LocalRecordKind.valueOf(string(a[1])) }
                214 -> { require(a.size == 5); com.ukkera.pos.app.LocalRecordReference(kind = read(a[1]) as com.ukkera.pos.app.LocalRecordKind, id = read(a[2]) as com.ukkera.pos.types.DomainId, revision = read(a[3]) as com.ukkera.pos.types.Revision?, parentId = read(a[4]) as com.ukkera.pos.types.DomainId?) }
                215 -> { require(a.size == 3); com.ukkera.pos.app.LocalRecordValue(label = read(a[1]) as com.ukkera.pos.app.LocalText, value = read(a[2]) as com.ukkera.pos.app.LocalText) }
                216 -> { require(a.size == 2); com.ukkera.pos.app.LocalRecordedDecision.valueOf(string(a[1])) }
                217 -> { require(a.size == 3); com.ukkera.pos.app.LocalReferenceChoice(reference = read(a[1]) as com.ukkera.pos.app.LocalRecordReference, label = read(a[2]) as com.ukkera.pos.app.LocalText) }
                218 -> { require(a.size == 5); com.ukkera.pos.app.LocalReferencePage(query = read(a[1]) as com.ukkera.pos.app.LocalReferenceQuery, choices = read(a[2]) as List<com.ukkera.pos.app.LocalReferenceChoice>, nextAfter = read(a[3]) as com.ukkera.pos.types.DomainId?, notice = read(a[4]) as com.ukkera.pos.app.LocalText?) }
                219 -> { require(a.size == 4); com.ukkera.pos.app.LocalReferenceQuery(source = read(a[1]) as com.ukkera.pos.app.LocalReferenceSource, after = read(a[2]) as com.ukkera.pos.types.DomainId?, exactId = read(a[3]) as com.ukkera.pos.types.DomainId?) }
                220 -> { require(a.size == 2); com.ukkera.pos.app.LocalReferenceResult.Available(page = read(a[1]) as com.ukkera.pos.app.LocalReferencePage) }
                221 -> { require(a.size == 2); com.ukkera.pos.app.LocalReferenceResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem) }
                222 -> { require(a.size == 3); com.ukkera.pos.app.LocalReferenceSource(kind = read(a[1]) as com.ukkera.pos.app.LocalRecordKind, excludedId = read(a[2]) as com.ukkera.pos.types.DomainId?) }
                223 -> { require(a.size == 4); com.ukkera.pos.app.LocalReturnRightsPublicationCompleted(originalActionId = read(a[1]) as com.ukkera.pos.types.DomainId, preview = read(a[2]) as com.ukkera.pos.app.LocalReturnRightsPublicationPreview, rootFingerprint = read(a[3]) as String) }
                224 -> { require(a.size == 9); com.ukkera.pos.app.LocalReturnRightsPublicationDraft(publicationId = read(a[1]) as com.ukkera.pos.types.DomainId, policyId = read(a[2]) as com.ukkera.pos.types.DomainId, companyRevision = read(a[3]) as com.ukkera.pos.types.Revision, jurisdictionCode = read(a[4]) as String, legalSourceUri = read(a[5]) as String, legalSourceAccessDate = read(a[6]) as String, continuingRights = read(a[7]) as com.ukkera.pos.app.LocalText, rules = read(a[8]) as List<com.ukkera.pos.app.LocalReturnRightsPublicationRule>) }
                225 -> { require(a.size == 2); com.ukkera.pos.app.LocalReturnRightsPublicationException.valueOf(string(a[1])) }
                226 -> { require(a.size == 3); com.ukkera.pos.app.LocalReturnRightsPublicationPreview(companyId = read(a[1]) as com.ukkera.pos.types.DomainId, definition = read(a[2]) as com.ukkera.pos.app.LocalReturnRightsPublicationDraft) }
                227 -> { require(a.size == 2); com.ukkera.pos.app.LocalReturnRightsPublicationReason.valueOf(string(a[1])) }
                228 -> { require(a.size == 2); com.ukkera.pos.app.LocalReturnRightsPublicationResult.Completed(publication = read(a[1]) as com.ukkera.pos.app.LocalReturnRightsPublicationCompleted) }
                229 -> { require(a.size == 2); com.ukkera.pos.app.LocalReturnRightsPublicationResult.Review(review = read(a[1]) as com.ukkera.pos.app.LocalReturnRightsPublicationReview) }
                230 -> { require(a.size == 3); com.ukkera.pos.app.LocalReturnRightsPublicationResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                231 -> { require(a.size == 4); com.ukkera.pos.app.LocalReturnRightsPublicationReview.remote(read(a[1]) as LocalCompanyPublicationReviewData, read(a[2]) as LocalReturnRightsPublicationPreview, read(a[3]) as DomainId) }
                232 -> { require(a.size == 7); com.ukkera.pos.app.LocalReturnRightsPublicationRule(categoryCode = read(a[1]) as String, reason = read(a[2]) as com.ukkera.pos.app.LocalReturnRightsPublicationReason, treatment = read(a[3]) as com.ukkera.pos.app.LocalReturnRightsPublicationTreatment, receiptWindowDays = read(a[4]) as Int?, coveredResolutionDays = read(a[5]) as Int?, exceptionBasis = read(a[6]) as com.ukkera.pos.app.LocalReturnRightsPublicationException?) }
                233 -> { require(a.size == 4); com.ukkera.pos.app.LocalReturnRightsPublicationStatus(references = read(a[1]) as List<com.ukkera.pos.security.CompanyBusinessPublicationReference>, mayPrepare = read(a[2]) as Boolean, masterObservedAt = read(a[3]) as com.ukkera.pos.types.UtcInstant) }
                234 -> { require(a.size == 2); com.ukkera.pos.app.LocalReturnRightsPublicationStatusResult.Available(status = read(a[1]) as com.ukkera.pos.app.LocalReturnRightsPublicationStatus) }
                235 -> { require(a.size == 3); com.ukkera.pos.app.LocalReturnRightsPublicationStatusResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?) }
                236 -> { require(a.size == 2); com.ukkera.pos.app.LocalReturnRightsPublicationTreatment.valueOf(string(a[1])) }
                237 -> { require(a.size == 7); com.ukkera.pos.app.LocalReturnRightsUiPage(selection = read(a[1]) as com.ukkera.pos.app.LocalRecordReference, companyId = read(a[2]) as com.ukkera.pos.types.DomainId, branchId = read(a[3]) as com.ukkera.pos.types.DomainId, attribution = read(a[4]) as List<com.ukkera.pos.app.LocalRecordValue>, continuingRights = read(a[5]) as com.ukkera.pos.app.LocalText, rules = read(a[6]) as List<com.ukkera.pos.app.LocalReturnRightsUiRule>) }
                238 -> { require(a.size == 4); com.ukkera.pos.app.LocalReturnRightsUiRule(categoryCode = read(a[1]) as String, reasonCode = read(a[2]) as String, values = read(a[3]) as List<com.ukkera.pos.app.LocalRecordValue>) }
                239 -> { require(a.size == 3); com.ukkera.pos.app.LocalScalarInput(field = read(a[1]) as com.ukkera.pos.app.LocalField, value = read(a[2]) as com.ukkera.pos.app.LocalScalarValue) }
                240 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.Choice(code = read(a[1]) as String) }
                241 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.Choices(codes = read(a[1]) as List<String>) }
                242 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.Date(iso = read(a[1]) as String) }
                243 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.Decimal(canonical = read(a[1]) as String) }
                244 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.Flag(value = read(a[1]) as Boolean) }
                245 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.Identifier(value = read(a[1]) as com.ukkera.pos.types.DomainId) }
                246 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.Integer(value = read(a[1]) as Long) }
                247 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.Reference(value = read(a[1]) as com.ukkera.pos.app.LocalRecordReference) }
                248 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.References(values = read(a[1]) as List<com.ukkera.pos.app.LocalRecordReference>) }
                249 -> { require(a.size == 2); com.ukkera.pos.app.LocalScalarValue.Text(value = read(a[1]) as String) }
                250 -> { require(a.size == 3); com.ukkera.pos.app.LocalSerialPublicationCompleted(originalActionId = read(a[1]) as com.ukkera.pos.types.DomainId, preview = read(a[2]) as com.ukkera.pos.app.LocalSerialPublicationPreview) }
                251 -> { require(a.size == 2); com.ukkera.pos.app.LocalSerialPublicationPreview.Authority(definition = read(a[1]) as com.ukkera.pos.security.LocalPublishedSerialAuthorityV1) }
                252 -> { require(a.size == 2); com.ukkera.pos.app.LocalSerialPublicationPreview.Registration(definition = read(a[1]) as com.ukkera.pos.security.LocalPublishedSerialRegistrationV1) }
                253 -> { require(a.size == 2); com.ukkera.pos.app.LocalSerialPublicationResult.Completed(publication = read(a[1]) as com.ukkera.pos.app.LocalSerialPublicationCompleted) }
                254 -> { require(a.size == 2); com.ukkera.pos.app.LocalSerialPublicationResult.Review(review = read(a[1]) as com.ukkera.pos.app.LocalSerialPublicationReview) }
                255 -> { require(a.size == 4); com.ukkera.pos.app.LocalSerialPublicationResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, nativeReason = read(a[2]) as com.ukkera.pos.security.SelfOwnedInstallationFailureV1?, unsignedInputMayChange = read(a[3]) as Boolean) }
                256 -> { require(a.size == 4); com.ukkera.pos.app.LocalSerialPublicationReview.remote(read(a[1]) as LocalCompanyPublicationReviewData, read(a[2]) as LocalSerialPublicationPreview, read(a[3]) as DomainId) }
                257 -> { require(a.size == 3); com.ukkera.pos.app.LocalSerialPublicationStatus(references = read(a[1]) as List<com.ukkera.pos.security.CompanyBusinessPublicationReference>, source = read(a[2]) as com.ukkera.pos.app.LocalSerialRegistrationAuthoringSource?) }
                258 -> { require(a.size == 2); com.ukkera.pos.app.LocalSerialPublicationStatusResult.Available(status = read(a[1]) as com.ukkera.pos.app.LocalSerialPublicationStatus) }
                259 -> { require(a.size == 2); com.ukkera.pos.app.LocalSerialPublicationStatusResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem) }
                260 -> { require(a.size == 8); com.ukkera.pos.app.LocalSerialRegistrationAuthoringSource(registrationId = read(a[1]) as com.ukkera.pos.types.DomainId, skuId = read(a[2]) as com.ukkera.pos.types.DomainId, skuPublicationId = read(a[3]) as com.ukkera.pos.types.DomainId, skuPublicationDigest = read(a[4]) as com.ukkera.pos.security.PayloadDigest, previous = read(a[5]) as com.ukkera.pos.security.LocalSerialRegistrationReferenceV1?, previousDefinition = read(a[6]) as com.ukkera.pos.security.LocalPublishedSerialRegistrationV1?, nextRevision = read(a[7]) as com.ukkera.pos.types.Revision?) }
                261 -> { require(a.size == 12); com.ukkera.pos.app.LocalSerialRegistrationDraft(publicationId = read(a[1]) as com.ukkera.pos.types.DomainId, registrationId = read(a[2]) as com.ukkera.pos.types.DomainId, revision = read(a[3]) as com.ukkera.pos.types.Revision, previous = read(a[4]) as com.ukkera.pos.security.LocalSerialRegistrationReferenceV1?, serialId = read(a[5]) as com.ukkera.pos.types.DomainId, serialCode = read(a[6]) as String, skuId = read(a[7]) as com.ukkera.pos.types.DomainId, skuPublicationId = read(a[8]) as com.ukkera.pos.types.DomainId, skuPublicationDigest = read(a[9]) as com.ukkera.pos.security.PayloadDigest, validFrom = read(a[10]) as com.ukkera.pos.types.UtcInstant, validUntil = read(a[11]) as com.ukkera.pos.types.UtcInstant?) }
                262 -> { require(a.size == 2); com.ukkera.pos.app.LocalServiceProblem.valueOf(string(a[1])) }
                263 -> { require(a.size == 3); com.ukkera.pos.app.LocalSessionAttachmentKey(ownerId = read(a[1]) as com.ukkera.pos.types.DomainId, generation = read(a[2]) as Long) }
                264 -> { require(a.size == 3); com.ukkera.pos.app.LocalSessionCheckResult.Current(display = read(a[1]) as com.ukkera.pos.app.SessionDisplayContext, attachmentKey = read(a[2]) as com.ukkera.pos.app.LocalSessionAttachmentKey?) }
                265 -> { require(a.size == 3); com.ukkera.pos.app.LocalSessionCheckResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, attachmentKey = read(a[2]) as com.ukkera.pos.app.LocalSessionAttachmentKey?) }
                266 -> { require(a.size == 3); com.ukkera.pos.app.LocalSessionDraftEpochs(general = read(a[1]) as Long, cashier = read(a[2]) as Long) }
                267 -> { require(a.size == 2); com.ukkera.pos.app.LocalSessionEndResult.valueOf(string(a[1])) }
                268 -> { require(a.size == 2); com.ukkera.pos.app.LocalSessionWorkspaceMode.valueOf(string(a[1])) }
                269 -> { require(a.size == 5); com.ukkera.pos.app.LocalSetupAcknowledgementView(actorId = read(a[1]) as com.ukkera.pos.types.DomainId, acknowledgedAt = read(a[2]) as com.ukkera.pos.types.UtcInstant, operationId = read(a[3]) as com.ukkera.pos.types.OperationId, policyRevision = read(a[4]) as com.ukkera.pos.types.Revision) }
                270 -> { require(a.size == 3); com.ukkera.pos.app.LocalSetupCatalogCompletionInput(explicitNoImport = read(a[1]) as Boolean, rows = read(a[2]) as List<com.ukkera.pos.app.LocalSetupCatalogRow>) }
                271 -> { require(a.size == 8); com.ukkera.pos.app.LocalSetupCatalogImportView(explicitNoImport = read(a[1]) as Boolean, rowCount = read(a[2]) as Int, manifestDigest = read(a[3]) as String?, inputManifestDigest = read(a[4]) as String?, actorId = read(a[5]) as com.ukkera.pos.types.DomainId, recordedAt = read(a[6]) as com.ukkera.pos.types.UtcInstant, operationId = read(a[7]) as com.ukkera.pos.types.OperationId) }
                272 -> { require(a.size == 4); com.ukkera.pos.app.LocalSetupCatalogRow(sourceId = read(a[1]) as com.ukkera.pos.types.DomainId, skuId = read(a[2]) as com.ukkera.pos.types.DomainId, catalogOperationId = read(a[3]) as com.ukkera.pos.types.OperationId) }
                273 -> { require(a.size == 8); com.ukkera.pos.app.LocalSetupCheckpointView(configuration = read(a[1]) as com.ukkera.pos.app.LocalSetupConfigurationView?, catalogImport = read(a[2]) as com.ukkera.pos.app.LocalSetupCatalogImportView?, opening = read(a[3]) as com.ukkera.pos.app.LocalSetupOpeningView?, retainedRowsLoaded = read(a[4]) as Boolean, retainedCatalogRows = read(a[5]) as List<com.ukkera.pos.app.LocalSetupCatalogRow>, retainedStockRows = read(a[6]) as List<com.ukkera.pos.app.LocalSetupOpeningStockRow>, retainedFloatRows = read(a[7]) as List<com.ukkera.pos.app.LocalSetupOpeningFloatRow>) }
                274 -> { require(a.size == 2); com.ukkera.pos.app.LocalSetupClosureAvailability.valueOf(string(a[1])) }
                275 -> { require(a.size == 8); com.ukkera.pos.app.LocalSetupConfigurationInput(expectedRevision = read(a[1]) as com.ukkera.pos.types.Revision, currencyCode = read(a[2]) as String, timeZone = read(a[3]) as String, businessDayCutoffMinute = read(a[4]) as Int, legalName = read(a[5]) as String, taxRegistrationId = read(a[6]) as String?, taxRegistrationAbsent = read(a[7]) as Boolean) }
                276 -> { require(a.size == 12); com.ukkera.pos.app.LocalSetupConfigurationView(revision = read(a[1]) as com.ukkera.pos.types.Revision, activities = read(a[2]) as List<String>, currency = read(a[3]) as String, timeZone = read(a[4]) as String, businessDayCutoffMinute = read(a[5]) as Int, legalName = read(a[6]) as String, taxRegistrationId = read(a[7]) as String?, taxRegistrationAbsent = read(a[8]) as Boolean, actorId = read(a[9]) as com.ukkera.pos.types.DomainId, recordedAt = read(a[10]) as com.ukkera.pos.types.UtcInstant, operationId = read(a[11]) as com.ukkera.pos.types.OperationId) }
                277 -> { require(a.size == 5); com.ukkera.pos.app.LocalSetupGoLiveView(actorId = read(a[1]) as com.ukkera.pos.types.DomainId, occurredAt = read(a[2]) as com.ukkera.pos.types.UtcInstant, operationId = read(a[3]) as com.ukkera.pos.types.OperationId, policyRevision = read(a[4]) as com.ukkera.pos.types.Revision) }
                278 -> { require(a.size == 3); com.ukkera.pos.app.LocalSetupManifestPreview(rowCount = read(a[1]) as Int, digest = read(a[2]) as String?) }
                279 -> { require(a.size == 4); com.ukkera.pos.app.LocalSetupMutationResult.NotStaged(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, problem = read(a[2]) as com.ukkera.pos.app.LocalServiceProblem, kind = read(a[3]) as com.ukkera.pos.app.LocalSetupOperationKind) }
                280 -> { require(a.size == 3); com.ukkera.pos.app.LocalSetupMutationResult.Pending(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, kind = read(a[2]) as com.ukkera.pos.app.LocalSetupOperationKind) }
                281 -> { require(a.size == 13); com.ukkera.pos.app.LocalSetupMutationResult.Recorded(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, kind = read(a[2]) as com.ukkera.pos.app.LocalSetupOperationKind, committed = read(a[3]) as Boolean, code = read(a[4]) as String, rowId = read(a[5]) as com.ukkera.pos.types.DomainId?, rowCount = read(a[6]) as Int, manifestDigest = read(a[7]) as String?, catalogRow = read(a[8]) as com.ukkera.pos.app.LocalSetupCatalogRow?, stockRow = read(a[9]) as com.ukkera.pos.app.LocalSetupOpeningStockRow?, floatRow = read(a[10]) as com.ukkera.pos.app.LocalSetupOpeningFloatRow?, catalogCompletion = read(a[11]) as com.ukkera.pos.app.LocalSetupManifestPreview?, openingCompletion = read(a[12]) as com.ukkera.pos.app.LocalSetupOpeningManifestPreview?) }
                282 -> { require(a.size == 4); com.ukkera.pos.app.LocalSetupMutationResult.Unresolved(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, problem = read(a[2]) as com.ukkera.pos.app.LocalServiceProblem, kind = read(a[3]) as com.ukkera.pos.app.LocalSetupOperationKind) }
                283 -> { require(a.size == 5); com.ukkera.pos.app.LocalSetupOpeningCompletionInput(stockExplicitZero = read(a[1]) as Boolean, stockRows = read(a[2]) as List<com.ukkera.pos.app.LocalSetupOpeningStockRow>, floatExplicitZero = read(a[3]) as Boolean, floatRows = read(a[4]) as List<com.ukkera.pos.app.LocalSetupOpeningFloatRow>) }
                284 -> { require(a.size == 4); com.ukkera.pos.app.LocalSetupOpeningFloatRow(sourceId = read(a[1]) as com.ukkera.pos.types.DomainId, sessionId = read(a[2]) as com.ukkera.pos.types.DomainId, openingOperationId = read(a[3]) as com.ukkera.pos.types.OperationId) }
                285 -> { require(a.size == 3); com.ukkera.pos.app.LocalSetupOpeningManifestPreview(stock = read(a[1]) as com.ukkera.pos.app.LocalSetupManifestPreview, float = read(a[2]) as com.ukkera.pos.app.LocalSetupManifestPreview) }
                286 -> { require(a.size == 4); com.ukkera.pos.app.LocalSetupOpeningStockRow(sourceId = read(a[1]) as com.ukkera.pos.types.DomainId, receiptId = read(a[2]) as com.ukkera.pos.types.DomainId, openingOperationId = read(a[3]) as com.ukkera.pos.types.OperationId) }
                287 -> { require(a.size == 13); com.ukkera.pos.app.LocalSetupOpeningView(stockExplicitZero = read(a[1]) as Boolean, stockRowCount = read(a[2]) as Int, stockValueTotalMinor = read(a[3]) as Long, floatExplicitZero = read(a[4]) as Boolean, floatRowCount = read(a[5]) as Int, floatTotalMinor = read(a[6]) as Long, stockManifestDigest = read(a[7]) as String?, floatManifestDigest = read(a[8]) as String?, currency = read(a[9]) as String, actorId = read(a[10]) as com.ukkera.pos.types.DomainId, recordedAt = read(a[11]) as com.ukkera.pos.types.UtcInstant, operationId = read(a[12]) as com.ukkera.pos.types.OperationId) }
                288 -> { require(a.size == 2); com.ukkera.pos.app.LocalSetupOperationKind.valueOf(string(a[1])) }
                289 -> { require(a.size == 15); com.ukkera.pos.app.LocalSetupOriginalView(installationId = read(a[1]) as com.ukkera.pos.types.DomainId, assignmentId = read(a[2]) as com.ukkera.pos.types.DomainId, deviceId = read(a[3]) as com.ukkera.pos.types.DomainId, firstActorId = read(a[4]) as com.ukkera.pos.types.DomainId, originalRequestId = read(a[5]) as com.ukkera.pos.types.DomainId, issuedAttemptId = read(a[6]) as com.ukkera.pos.types.DomainId, attemptSequence = read(a[7]) as Long, definitionDigest = read(a[8]) as String, genesisDigest = read(a[9]) as String, receiptDigest = read(a[10]) as String, installationAuthorityEpoch = read(a[11]) as com.ukkera.pos.types.Revision, currentAuthorityEpoch = read(a[12]) as com.ukkera.pos.types.Revision, installedAt = read(a[13]) as com.ukkera.pos.types.UtcInstant, nativeSettlement = read(a[14]) as Boolean) }
                290 -> { require(a.size == 6); com.ukkera.pos.app.LocalSetupPendingView(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, kind = read(a[2]) as com.ukkera.pos.app.LocalSetupOperationKind, phase = read(a[3]) as String, recorded = read(a[4]) as Boolean, recordedCode = read(a[5]) as String?) }
                291 -> { require(a.size == 2); com.ukkera.pos.app.LocalSetupPrerequisiteKind.valueOf(string(a[1])) }
                292 -> { require(a.size == 4); com.ukkera.pos.app.LocalSetupPrerequisiteView(kind = read(a[1]) as com.ukkera.pos.app.LocalSetupPrerequisiteKind, settled = read(a[2]) as Boolean, binding = read(a[3]) as String) }
                293 -> { require(a.size == 2); com.ukkera.pos.app.LocalSetupReadResult.Available(value = read(a[1]) as Any?) }
                294 -> { require(a.size == 2); com.ukkera.pos.app.LocalSetupReadResult.Unavailable(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem) }
                295 -> { require(a.size == 5); com.ukkera.pos.app.LocalSetupResultView(original = read(a[1]) as com.ukkera.pos.app.LocalSetupOriginalView, acknowledgement = read(a[2]) as com.ukkera.pos.app.LocalSetupAcknowledgementView?, goLive = read(a[3]) as com.ukkera.pos.app.LocalSetupGoLiveView?, prerequisites = read(a[4]) as List<com.ukkera.pos.app.LocalSetupPrerequisiteView>) }
                296 -> { require(a.size == 3); com.ukkera.pos.app.LocalStageResult.NotStaged(problem = read(a[1]) as com.ukkera.pos.app.LocalServiceProblem, field = read(a[2]) as com.ukkera.pos.app.LocalField?) }
                297 -> { require(a.size == 2); com.ukkera.pos.app.LocalStageResult.Staged(operation = read(a[1]) as com.ukkera.pos.app.LocalPendingOperation) }
                298 -> { require(a.size == 3); com.ukkera.pos.app.LocalStageResult.Uncertain(operationId = read(a[1]) as com.ukkera.pos.types.OperationId, accessProblem = read(a[2]) as com.ukkera.pos.app.LocalServiceProblem?) }
                299 -> { require(a.size == 3); com.ukkera.pos.app.LocalText(english = read(a[1]) as String, arabic = read(a[2]) as String) }
                300 -> { require(a.size == 2); com.ukkera.pos.app.LocalWorkflow.valueOf(string(a[1])) }
                301 -> { require(a.size == 8); com.ukkera.pos.app.LocalWorkflowPage(workflow = read(a[1]) as com.ukkera.pos.app.LocalWorkflow, records = read(a[2]) as List<com.ukkera.pos.app.LocalRecord>, forms = read(a[3]) as List<com.ukkera.pos.app.LocalActionForm>, notice = read(a[4]) as com.ukkera.pos.app.LocalText?, nextCursor = read(a[5]) as String?, priceControls = read(a[6]) as com.ukkera.pos.app.LocalPriceReadControls?, returnRights = read(a[7]) as com.ukkera.pos.app.LocalReturnRightsUiPage?) }
                302 -> { require(a.size == 6); com.ukkera.pos.app.LocalWorkflowQuery(workflow = read(a[1]) as com.ukkera.pos.app.LocalWorkflow, search = read(a[2]) as String, reference = read(a[3]) as com.ukkera.pos.app.LocalRecordReference?, cursor = read(a[4]) as String?, pricePreview = read(a[5]) as com.ukkera.pos.app.LocalPricePreviewQuery?) }
                303 -> { require(a.size == 10); com.ukkera.pos.app.SessionDisplayContext(actorId = read(a[1]) as com.ukkera.pos.types.DomainId, companyId = read(a[2]) as com.ukkera.pos.types.DomainId, branchId = read(a[3]) as com.ukkera.pos.types.DomainId?, actorName = read(a[4]) as String, companyName = read(a[5]) as String, branchName = read(a[6]) as String?, allowedAreas = read(a[7]) as Set<com.ukkera.pos.app.navigation.AppArea>, localDraftEpochs = read(a[8]) as com.ukkera.pos.app.LocalSessionDraftEpochs?, workspaceMode = read(a[9]) as com.ukkera.pos.app.LocalSessionWorkspaceMode) }
                304 -> { require(a.size == 2); com.ukkera.pos.app.SignInFailure.valueOf(string(a[1])) }
                305 -> { require(a.size == 2); com.ukkera.pos.app.navigation.AppArea.valueOf(string(a[1])) }
                306 -> { require(a.size == 3); com.ukkera.pos.catalog.CatalogAttribute(key = read(a[1]) as com.ukkera.pos.types.Code, value = read(a[2]) as com.ukkera.pos.catalog.CatalogAttributeValue) }
                307 -> { require(a.size == 2); com.ukkera.pos.catalog.CatalogAttributeValue.Decimal(value = read(a[1]) as com.ukkera.pos.types.ExactDecimal) }
                308 -> { require(a.size == 2); com.ukkera.pos.catalog.CatalogAttributeValue.Flag(value = read(a[1]) as Boolean) }
                309 -> { require(a.size == 2); com.ukkera.pos.catalog.CatalogAttributeValue.Text(value = read(a[1]) as com.ukkera.pos.types.Code) }
                310 -> { require(a.size == 2); com.ukkera.pos.catalog.CatalogStatus.valueOf(string(a[1])) }
                311 -> { require(a.size == 5); com.ukkera.pos.catalog.CatalogUnit(id = read(a[1]) as com.ukkera.pos.types.DomainId, code = read(a[2]) as com.ukkera.pos.types.Code, dimension = read(a[3]) as com.ukkera.pos.catalog.UnitDimension, maximumScale = read(a[4]) as Int) }
                312 -> { require(a.size == 8); com.ukkera.pos.catalog.ProductUnitConversion(id = read(a[1]) as com.ukkera.pos.types.DomainId, revision = read(a[2]) as com.ukkera.pos.types.Revision, productId = read(a[3]) as com.ukkera.pos.types.DomainId, sourceUnitId = read(a[4]) as com.ukkera.pos.types.DomainId, targetUnitId = read(a[5]) as com.ukkera.pos.types.DomainId, rate = read(a[6]) as com.ukkera.pos.types.UnitConversionRate, massVolumeEvidenceId = read(a[7]) as com.ukkera.pos.types.DomainId?) }
                313 -> { require(a.size == 2); com.ukkera.pos.catalog.UnitDimension.valueOf(string(a[1])) }
                314 -> { require(a.size == 4); com.ukkera.pos.catalog.VariantAxis(id = read(a[1]) as com.ukkera.pos.types.DomainId, code = read(a[2]) as com.ukkera.pos.types.Code, values = read(a[3]) as List<com.ukkera.pos.types.Code>) }
                315 -> { require(a.size == 3); com.ukkera.pos.catalog.VariantValue(axisId = read(a[1]) as com.ukkera.pos.types.DomainId, value = read(a[2]) as com.ukkera.pos.types.Code) }
                316 -> { require(a.size == 2); LocalSetupPolicyTransferV1.decodePolicy(string(a[1])) }
                317 -> { require(a.size == 2); com.ukkera.pos.security.AuthenticationPurpose.valueOf(string(a[1])) }
                318 -> { require(a.size == 4); com.ukkera.pos.security.BranchScope(organizationId = read(a[1]) as com.ukkera.pos.types.DomainId, companyId = read(a[2]) as com.ukkera.pos.types.DomainId, branchId = read(a[3]) as com.ukkera.pos.types.DomainId) }
                319 -> { require(a.size == 2); com.ukkera.pos.security.CompanyBusinessPublicationKind.valueOf(string(a[1])) }
                320 -> { require(a.size == 2); com.ukkera.pos.security.CompanyBusinessPublicationPhase.valueOf(string(a[1])) }
                321 -> { require(a.size == 6); com.ukkera.pos.security.CompanyBusinessPublicationProposal(read(a[1]) as CompanyScope, read(a[2]) as CompanyBusinessPublicationKind, read(a[3]) as DomainId, read(a[4]) as DomainId, read(a[5]) as ByteArray) }
                322 -> { require(a.size == 6); com.ukkera.pos.security.CompanyBusinessPublicationReference(originalActionId = read(a[1]) as com.ukkera.pos.types.DomainId, publicationId = read(a[2]) as com.ukkera.pos.types.DomainId, entityId = read(a[3]) as com.ukkera.pos.types.DomainId, phase = read(a[4]) as com.ukkera.pos.security.CompanyBusinessPublicationPhase, kind = read(a[5]) as com.ukkera.pos.security.CompanyBusinessPublicationKind) }
                323 -> { require(a.size == 3); com.ukkera.pos.security.CompanyScope(organizationId = read(a[1]) as com.ukkera.pos.types.DomainId, companyId = read(a[2]) as com.ukkera.pos.types.DomainId) }
                324 -> { require(a.size == 5); com.ukkera.pos.security.LocalAccountAdministrationDetail(account = read(a[1]) as com.ukkera.pos.security.LocalAccountSummary, policy = read(a[2]) as com.ukkera.pos.security.ActivatedLocalPolicy, tokenPolicy = read(a[3]) as com.ukkera.pos.security.LocalTokenPolicy, sessions = read(a[4]) as List<com.ukkera.pos.security.LocalSessionRecord>) }
                325 -> { require(a.size == 10); com.ukkera.pos.security.LocalAccountSummary(actorId = read(a[1]) as com.ukkera.pos.types.DomainId, login = read(a[2]) as com.ukkera.pos.security.LocalLoginIdentifier, enabled = read(a[3]) as Boolean, credentialPresent = read(a[4]) as Boolean, accountRevision = read(a[5]) as com.ukkera.pos.types.Revision, credentialRevision = read(a[6]) as com.ukkera.pos.types.Revision, policyRevision = read(a[7]) as com.ukkera.pos.types.Revision, configurationRevision = read(a[8]) as com.ukkera.pos.types.Revision, activeSessionCount = read(a[9]) as Int) }
                326 -> { require(a.size == 2); com.ukkera.pos.security.LocalAdministrationAction.valueOf(string(a[1])) }
                327 -> { require(a.size == 3); com.ukkera.pos.security.LocalAdministrationActivationRevisions(policy = read(a[1]) as com.ukkera.pos.types.Revision, configuration = read(a[2]) as com.ukkera.pos.types.Revision) }
                328 -> { require(a.size == 9); com.ukkera.pos.security.LocalAdministrationCommand(requestId = read(a[1]) as com.ukkera.pos.types.DomainId, action = read(a[2]) as com.ukkera.pos.security.LocalAdministrationAction, targetId = read(a[3]) as com.ukkera.pos.types.DomainId, expectedRevision = read(a[4]) as com.ukkera.pos.types.Revision, sessionId = read(a[5]) as com.ukkera.pos.types.DomainId?, expectedCredentialRevision = read(a[6]) as com.ukkera.pos.types.Revision?, reactivationRevisions = read(a[7]) as com.ukkera.pos.security.LocalAdministrationReactivationRevisions?, activationRevisions = read(a[8]) as com.ukkera.pos.security.LocalAdministrationActivationRevisions?) }
                329 -> { require(a.size == 3); com.ukkera.pos.security.LocalAdministrationCursor(afterId = read(a[1]) as com.ukkera.pos.types.DomainId, snapshot = read(a[2]) as com.ukkera.pos.security.PayloadDigest) }
                330 -> { require(a.size == 2); com.ukkera.pos.security.LocalAdministrationDirectory.valueOf(string(a[1])) }
                331 -> { require(a.size == 5); com.ukkera.pos.security.LocalAdministrationQuery(directory = read(a[1]) as com.ukkera.pos.security.LocalAdministrationDirectory, targetId = read(a[2]) as com.ukkera.pos.types.DomainId?, cursor = read(a[3]) as com.ukkera.pos.security.LocalAdministrationCursor?, pageSize = read(a[4]) as Int) }
                332 -> { require(a.size == 4); com.ukkera.pos.security.LocalAdministrationReactivationRevisions(credential = read(a[1]) as com.ukkera.pos.types.Revision, policy = read(a[2]) as com.ukkera.pos.types.Revision, configuration = read(a[3]) as com.ukkera.pos.types.Revision) }
                333 -> { require(a.size == 2); com.ukkera.pos.security.LocalAdministrationReadResult.Available(view = read(a[1]) as com.ukkera.pos.security.LocalAdministrationView) }
                334 -> { require(a.size == 1); com.ukkera.pos.security.LocalAdministrationReadResult.Busy }
                335 -> { require(a.size == 1); com.ukkera.pos.security.LocalAdministrationReadResult.Denied }
                336 -> { require(a.size == 1); com.ukkera.pos.security.LocalAdministrationReadResult.StaleSnapshot }
                337 -> { require(a.size == 1); com.ukkera.pos.security.LocalAdministrationReadResult.StorageUncertain }
                338 -> { require(a.size == 1); com.ukkera.pos.security.LocalAdministrationReadResult.Unavailable }
                339 -> { require(a.size == 7); com.ukkera.pos.security.LocalAdministrationReceipt(requestId = read(a[1]) as com.ukkera.pos.types.DomainId, action = read(a[2]) as com.ukkera.pos.security.LocalAdministrationAction, targetId = read(a[3]) as com.ukkera.pos.types.DomainId, originalOperatorId = read(a[4]) as com.ukkera.pos.types.DomainId, resultingAccountRevision = read(a[5]) as com.ukkera.pos.types.Revision?, requestDigest = read(a[6]) as com.ukkera.pos.security.PayloadDigest) }
                340 -> { require(a.size == 8); com.ukkera.pos.security.LocalAdministrationView(scope = read(a[1]) as com.ukkera.pos.security.BranchScope, directory = read(a[2]) as com.ukkera.pos.security.LocalAdministrationDirectory, snapshot = read(a[3]) as com.ukkera.pos.security.PayloadDigest, accounts = read(a[4]) as List<com.ukkera.pos.security.LocalAccountSummary>, devices = read(a[5]) as List<com.ukkera.pos.security.LocalDeviceAdmission>, accountDetail = read(a[6]) as com.ukkera.pos.security.LocalAccountAdministrationDetail?, next = read(a[7]) as com.ukkera.pos.security.LocalAdministrationCursor?) }
                341 -> { require(a.size == 15); com.ukkera.pos.security.LocalCompanySerialIssuerOriginV1(company = read(a[1]) as com.ukkera.pos.security.CompanyScope, foundingActionId = read(a[2]) as com.ukkera.pos.types.DomainId, rootId = read(a[3]) as com.ukkera.pos.types.DomainId, rootKeyId = read(a[4]) as com.ukkera.pos.types.DomainId, rootDocumentDigest = read(a[5]) as com.ukkera.pos.security.PayloadDigest, rootFingerprint = read(a[6]) as String, headAuthorityId = read(a[7]) as com.ukkera.pos.types.DomainId, headEnrollmentId = read(a[8]) as com.ukkera.pos.types.DomainId, originalIssuerId = read(a[9]) as com.ukkera.pos.types.DomainId, originalIssuerKeyId = read(a[10]) as com.ukkera.pos.types.DomainId, originalIssuerDelegationDigest = read(a[11]) as com.ukkera.pos.security.PayloadDigest, originalScope = read(a[12]) as com.ukkera.pos.security.BranchScope, originalAllocationId = read(a[13]) as com.ukkera.pos.types.DomainId, originalAllocationDigest = read(a[14]) as com.ukkera.pos.security.PayloadDigest) }
                342 -> { require(a.size == 8); com.ukkera.pos.security.LocalDeviceAdmission(scope = read(a[1]) as com.ukkera.pos.security.BranchScope, deviceId = read(a[2]) as com.ukkera.pos.types.DomainId, revision = read(a[3]) as com.ukkera.pos.types.Revision, admitted = read(a[4]) as Boolean, publicKeyIdentifier = read(a[5]) as String?, publicFingerprint = read(a[6]) as String?, capabilities = read(a[7]) as Set<String>) }
                343 -> { require(a.size == 2); com.ukkera.pos.security.LocalLoginIdentifier(value = read(a[1]) as String) }
                344 -> { require(a.size == 5); com.ukkera.pos.security.LocalPublishedSerialAuthorityV1(authorityId = read(a[1]) as com.ukkera.pos.types.DomainId, publicationId = read(a[2]) as com.ukkera.pos.types.DomainId, origin = read(a[3]) as com.ukkera.pos.security.LocalCompanySerialIssuerOriginV1, purpose = read(a[4]) as String) }
                345 -> { require(a.size == 16); com.ukkera.pos.security.LocalPublishedSerialRegistrationV1(scope = read(a[1]) as com.ukkera.pos.security.BranchScope, allocationId = read(a[2]) as com.ukkera.pos.types.DomainId, allocationDigest = read(a[3]) as com.ukkera.pos.security.PayloadDigest, serialAuthority = read(a[4]) as com.ukkera.pos.security.LocalSerialAuthorityReferenceV1, publicationId = read(a[5]) as com.ukkera.pos.types.DomainId, registrationId = read(a[6]) as com.ukkera.pos.types.DomainId, revision = read(a[7]) as com.ukkera.pos.types.Revision, previous = read(a[8]) as com.ukkera.pos.security.LocalSerialRegistrationReferenceV1?, serialId = read(a[9]) as com.ukkera.pos.types.DomainId, serialCode = read(a[10]) as String, skuId = read(a[11]) as com.ukkera.pos.types.DomainId, skuPublicationId = read(a[12]) as com.ukkera.pos.types.DomainId, skuPublicationDigest = read(a[13]) as com.ukkera.pos.security.PayloadDigest, validFrom = read(a[14]) as com.ukkera.pos.types.UtcInstant, validUntil = read(a[15]) as com.ukkera.pos.types.UtcInstant?) }
                346 -> { require(a.size == 5); com.ukkera.pos.security.LocalSerialAuthorityReferenceV1(authorityId = read(a[1]) as com.ukkera.pos.types.DomainId, publicationId = read(a[2]) as com.ukkera.pos.types.DomainId, contentDigest = read(a[3]) as com.ukkera.pos.security.PayloadDigest, issuerOriginDigest = read(a[4]) as com.ukkera.pos.security.PayloadDigest) }
                347 -> { require(a.size == 5); com.ukkera.pos.security.LocalSerialRegistrationReferenceV1(registrationId = read(a[1]) as com.ukkera.pos.types.DomainId, publicationId = read(a[2]) as com.ukkera.pos.types.DomainId, revision = read(a[3]) as com.ukkera.pos.types.Revision, contentDigest = read(a[4]) as com.ukkera.pos.security.PayloadDigest) }
                348 -> { require(a.size == 8); com.ukkera.pos.security.LocalSessionRecord(sessionId = read(a[1]) as com.ukkera.pos.types.DomainId, scope = read(a[2]) as com.ukkera.pos.security.BranchScope, actorId = read(a[3]) as com.ukkera.pos.types.DomainId, deviceId = read(a[4]) as com.ukkera.pos.types.DomainId, generation = read(a[5]) as com.ukkera.pos.security.SessionGeneration, status = read(a[6]) as com.ukkera.pos.security.SessionStatus, authenticationPurpose = read(a[7]) as com.ukkera.pos.security.AuthenticationPurpose) }
                349 -> { require(a.size == 3); com.ukkera.pos.security.LocalTokenPolicy(accessLifetimeMillis = read(a[1]) as Long, workLifetimeMillis = read(a[2]) as Long) }
                350 -> { require(a.size == 2); PayloadDigest(string(a[1])) }
                351 -> { require(a.size == 2); com.ukkera.pos.security.SelfOwnedInstallationFailureV1.valueOf(string(a[1])) }
                352 -> { require(a.size == 2); com.ukkera.pos.security.SessionGeneration(value = read(a[1]) as Long) }
                353 -> { require(a.size == 2); com.ukkera.pos.security.SessionStatus.valueOf(string(a[1])) }
                354 -> { require(a.size == 4); com.ukkera.pos.types.Code(original = read(a[1]) as String, normalizedKey = read(a[2]) as String, normalization = read(a[3]) as com.ukkera.pos.types.CodeNormalization) }
                355 -> { require(a.size == 2); com.ukkera.pos.types.CodeNormalization.valueOf(string(a[1])) }
                356 -> { require(a.size == 2); DomainId(string(a[1])) }
                357 -> { require(a.size == 2); ExactDecimal.parse(string(a[1])) }
                358 -> { require(a.size == 2); OperationId(string(a[1])) }
                359 -> { require(a.size == 4); com.ukkera.pos.types.Quantity(unitId = read(a[1]) as com.ukkera.pos.types.DomainId, scaled = read(a[2]) as Long, scale = read(a[3]) as Int) }
                360 -> { require(a.size == 2); Revision(long(a[1])) }
                361 -> { require(a.size == 2); UnitConversionRate.parseCanonical(string(a[1])) }
                362 -> { require(a.size == 2); UtcInstant.parseCanonical(string(a[1])) }
                    else -> error("Unknown desktop employee DATA tag")
                }
            } finally { depth-- }
        }
    }
}
