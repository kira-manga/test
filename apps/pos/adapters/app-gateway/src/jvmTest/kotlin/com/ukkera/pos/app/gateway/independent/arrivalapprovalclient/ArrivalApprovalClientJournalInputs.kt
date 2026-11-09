package com.ukkera.pos.app.gateway.independent.arrivalapprovalclient

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalArrivalActionV1
import com.ukkera.pos.inventory.store.LocalArrivalCatalogSelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalDispositionV1
import com.ukkera.pos.inventory.store.LocalArrivalDocumentSelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalEvidenceSelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalFinancialStatusV1
import com.ukkera.pos.inventory.store.LocalArrivalItemSelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalItemViewV2
import com.ukkera.pos.inventory.store.LocalArrivalLineV2
import com.ukkera.pos.inventory.store.LocalArrivalNamesV1
import com.ukkera.pos.inventory.store.LocalArrivalOwnershipV1
import com.ukkera.pos.inventory.store.LocalArrivalPolicySelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalPurchasingViewV2
import com.ukkera.pos.inventory.store.LocalArrivalResultV2
import com.ukkera.pos.inventory.store.LocalArrivalRetentionSelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalRuleViewV2
import com.ukkera.pos.inventory.store.LocalArrivalSourceKindV1
import com.ukkera.pos.inventory.store.LocalArrivalSourcePageV2
import com.ukkera.pos.inventory.store.LocalArrivalSourceQueryV2
import com.ukkera.pos.inventory.store.LocalArrivalSourceSelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalSplitV2
import com.ukkera.pos.inventory.store.LocalArrivalSupplierViewV2
import com.ukkera.pos.inventory.store.LocalArrivalTrackingV1
import com.ukkera.pos.inventory.store.LocalArrivalWarehouseSelectionV2
import com.ukkera.pos.inventory.store.LocalArrivalWarehouseViewV2
import com.ukkera.pos.inventory.store.LocalDispatchUnpricedArrivalV2
import com.ukkera.pos.inventory.store.LocalQualifyUnpricedArrivalV2
import com.ukkera.pos.inventory.store.LocalRecordUnpricedArrivalV2
import com.ukkera.pos.inventory.store.LocalRejectUnpricedArrivalV2
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalCommandsV2
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalProfileV2
import com.ukkera.pos.inventory.store.LocalArrivalActorSelectionModeV2 as StoreActorMode
import com.ukkera.pos.inventory.store.LocalArrivalActorSelectionV2 as StoreActors
import com.ukkera.pos.inventory.store.LocalArrivalApprovalModeV2 as StoreApprovalMode
import com.ukkera.pos.inventory.store.LocalArrivalApprovalRouteV2 as StoreApprovalRoute
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json

internal enum class AawClientJourney(val operationBase: Int, val deliverySuffix: Int,
    val firstLineSuffix: Int, val namedSelf: Boolean, val selfApprover: Boolean) {
    SEPARATE_R_A(11, 1, 1, false, false),
    NAMED_SELF_R_R(21, 2, 3, true, true),
    NAMED_SELF_R_A(31, 3, 5, true, false),
}

internal enum class AawClientStep(val ui: LocalAction, val physical: LocalArrivalActionV1,
    val quantity: String, val observation: String) {
    RECORD(LocalAction.ARRIVAL_RECORD, LocalArrivalActionV1.RECORD, "24/6", "SYN Delivery 001"),
    QUALIFY(LocalAction.ARRIVAL_QUALIFY, LocalArrivalActionV1.QUALIFY, "10", "SYN Inspection 001"),
    REJECT(LocalAction.ARRIVAL_REJECT, LocalArrivalActionV1.REJECT, "4", "SYN damage observed in four retained units"),
    DISPATCH(LocalAction.ARRIVAL_DISPATCH, LocalArrivalActionV1.DISPATCH, "3", "SYN Supplier dispatch 001"),
}

/**
 * Projection of AAW v1 journeys, source/document selections and retryRestart.
 * Frozen actor/operation/rule/manifest coordinates and 24/6, 10, 4, 3 quantities
 * are unchanged. Organization, UUID-only unit/retention, source hashes/revisions,
 * prior-cut/event/custody IDs and clock values are inert schema carriers. They
 * do not create native prerequisites, authentic history or a preceding custody cut.
 * The supplied confirmation digests remain specification DATA, not Master hashes.
 */
internal class AawClientJournalInputs(
    val journey: AawClientJourney = AawClientJourney.SEPARATE_R_A,
    val step: AawClientStep = AawClientStep.RECORD,
) {
    val requester = DomainId("bd706b87-eaf0-45a9-8212-0f40f038eafe")
    val otherRequester = DomainId("1c93ade3-3a6d-428d-b7c0-1c1a1b65fee8")
    val approver = if (journey.selfApprover) requester else DomainId("a1100000-0000-4000-8000-000000000001")
    val supplier = DomainId("0d92ac11-e8ca-46e1-afcb-e57f219a7277")
    val warehouseId = DomainId("23e86e98-2c8c-48fb-b7d2-029fd7a440b8")
    val scope = BranchScope(DomainId("90000000-0000-4000-8000-000000000001"),
        DomainId("8891f6af-3003-4a10-a40c-56830e000324"), DomainId("17256bce-b955-4c06-898f-b740d9c1f3de"))
    val owner = LocalClientIdentity(scope, requester, DomainId("a1200000-0000-4000-8000-000000000001"))
    val operationId = OperationId(frozenCoordinate("a3200000", journey.operationBase + step.ordinal))
    val arrivalId = DomainId(frozenCoordinate("a3000000", journey.deliverySuffix))
    val originalLineA = DomainId(frozenCoordinate("a3100000", journey.firstLineSuffix))
    val originalLineB = DomainId(frozenCoordinate("a3100000", journey.firstLineSuffix + 1))
    val ruleId = DomainId(frozenCoordinate("a2000000", (if (journey.namedSelf) 21 else 11) + step.ordinal))
    val priorRevisionCarrier = Revision(step.ordinal.toLong())
    val reasonCode = "syn.aaw.${step.name.lowercase()}"
    val unitCarrier = DomainId("90000000-0000-4000-8000-000000000004")
    val retention = LocalArrivalRetentionCoordinateV2(DomainId("90000000-0000-4000-8000-000000000002"), Revision(2))
    val clockCarrier = DomainId("90000000-0000-4000-8000-000000000003")
    val recordedAtCarrier = UtcInstant.parseCanonical("2026-11-01T01:00:00Z")
    private val digestCarrier = PayloadDigest("0".repeat(64))
    val policy = LocalArrivalPolicyCoordinateV2(scope, DomainId("bab7c46a-cfcc-4b24-9691-4100b966c048"),
        DomainId("a1600000-0000-4000-8000-000000000001"), Revision(9),
        LocalArrivalSourceCoordinateV2("aaw.policy-active-carrier", scope.branchId, Revision(1), digestCarrier),
        LocalArrivalSourceCoordinateV2("aaw.policy-publication-carrier", DomainId("a1600000-0000-4000-8000-000000000001"), Revision(1), digestCarrier),
        UtcInstant.parseCanonical("2026-11-01T00:00:00Z"), null)
    val catalog = LocalArrivalCatalogCoordinateV2(scope, DomainId("f62e89ab-9991-4419-a3e5-b3b629045189"),
        DomainId("999805ba-c8de-4949-a775-a3ea5d185976"), Revision(7),
        LocalArrivalSourceCoordinateV2("aaw.catalog-active-carrier", scope.branchId, Revision(1), digestCarrier),
        LocalArrivalSourceCoordinateV2("aaw.catalog-publication-carrier", DomainId("999805ba-c8de-4949-a775-a3ea5d185976"), Revision(1), digestCarrier))
    val documents = listOf(
        LocalArrivalOriginalCoordinateV2(scope, supplier, DomainId("d7b0f975-8ae0-40d7-a9ec-34e698c25c2e"), Revision(1),
            PayloadDigest("58d3e9e632f1b54b8237e895eef84f80cf5d1da8e5f69370b9ccb70bbe058797"),
            614, PurchasingEvidenceMediaV1.PDF, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY),
        LocalArrivalOriginalCoordinateV2(scope, supplier, DomainId("a3e37624-c21b-4c3b-b218-6bbd2d0e1459"), Revision(1),
            PayloadDigest("2ea317ea2f23340a4f4bd5976f09d3019a26d8db465c5700f84ede93e2deeeb1"),
            613, PurchasingEvidenceMediaV1.PDF, PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY),
    )
    val route = LocalArrivalApprovalRouteV2(
        if (journey.namedSelf) LocalArrivalApprovalModeV2.NAMED_SELF else LocalArrivalApprovalModeV2.SEPARATE,
        requesters = LocalArrivalActorSelectionV2(LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES),
        approvers = LocalArrivalActorSelectionV2(LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES),
        namedEmployeeIds = if (journey.namedSelf) listOf(requester) else emptyList())
    val ruleDetails = "Supplied AAW v1 rule metadata; not an authenticated signed rule. " +
        "${ruleId.value} / ${step.name}; company ${scope.companyId.value}; branch ${scope.branchId.value}. " +
        "Policy ${policy.policyId.value} revision 7, publication ${policy.publicationId.value} revision 9. " +
        "Supplier ${supplier.value}; products 286fb181-509a-4caa-9b52-f20c34c963e1,84455e18-4539-4b9b-a7b1-9ad3fa1af79c; " +
        "SKUs ee3fad73-3a68-4225-a5ec-701b656b3935,62260de0-2d68-4b23-ae54-06023d543c9b; " +
        "catalog ${catalog.catalogId.value}, release ${catalog.publicationId.value} revision 7; warehouse ${warehouseId.value}. " +
        "One complete ${route.mode.name} REQUEST/APPROVE route with current employee grants; " +
        (if (journey.namedSelf) "only explicitly named ${requester.value} may use equality; distinct eligible R/A remains allowed. "
        else "R/A must be distinct. ") +
        "Required ordered original PDF/SUPPLIER_DELIVERY manifests, one through two, retention SYN-RET-PUR-ORIGINAL revision 2. " +
        "Delegation is the exact unpriced custody action only, not policy administration, priced acceptance, invoice or settlement. " +
        "Unspecified native acquisition/limit details are UNBOUND, not accepting defaults."
    val rule = LocalArrivalRuleOptionV2(ruleId, step.ui, false, 1, 2,
        listOf(PurchasingEvidenceMediaV1.PDF), listOf(PurchasingEvidencePurposeV1.SUPPLIER_DELIVERY), retention, route, ruleDetails)
    val options = LocalArrivalEvidenceOptionsV2(policy, catalog, Revision(21), listOf(rule), documents)
    val selection = LocalArrivalEvidenceInputV2(policy, catalog, Revision(21), rule, retention, documents, options.equalityDigest)
    val itemA = LocalArrivalItemSelectionV2(DomainId("ee3fad73-3a68-4225-a5ec-701b656b3935"),
        DomainId("286fb181-509a-4caa-9b52-f20c34c963e1"), Revision(4), unitCarrier, 0, Revision(7), Revision(1), Revision(1), null)
    val itemB = LocalArrivalItemSelectionV2(DomainId("62260de0-2d68-4b23-ae54-06023d543c9b"),
        DomainId("84455e18-4539-4b9b-a7b1-9ad3fa1af79c"), Revision(5), unitCarrier, 0, Revision(7), Revision(1), Revision(1), null)
    val warehouse = LocalArrivalWarehouseSelectionV2(warehouseId, Revision(2))
    val sourceACarrier = DomainId.random()
    val sourceBCarrier = DomainId.random()
    val custodyACarrier = DomainId.random()
    val custodyBCarrier = DomainId.random()
    val eventCarrier = DomainId.random()
    val splitCarrier = LocalArrivalSplitV2(DomainId.random(), DomainId.random())
    val confirmationData = PayloadDigest(confirmationDigests.getValue(journey)[step.ordinal])
    val request = originalRequest()
    val semanticDigest = semanticDigest(scope, requester, request.command, request.payload)
    val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), scope,
        ActionBinding(arrivalId, priorRevisionCarrier, semanticDigest, operationId, reasonCode))
    val intent = originalIntent(selection)
    val summary = listOf(LocalRecordValue(text("Supplier"), text(supplier.value)),
        LocalRecordValue(text("Frozen base quantities"), text(step.quantity)),
        LocalRecordValue(text("Financial status"), text("UNPRICED_UNRESOLVED")))

    fun originalIntent(evidenceInput: LocalArrivalEvidenceInputV2 = selection): LocalCommandIntent {
        val common = listOf(id(LocalField.ARRIVAL_ID, arrivalId), id(LocalField.SUPPLIER_ID, supplier),
            integer(LocalField.EXPECTED_SUPPLIER_STORAGE_REVISION, 3), id(LocalField.WAREHOUSE_ID, warehouseId),
            integer(LocalField.EXPECTED_WAREHOUSE_STORAGE_REVISION, 2),
            string(LocalField.ARRIVAL_SELECTION_DIGEST, evidenceInput.formSourceDigest.value),
            string(LocalField.ARRIVAL_SELECTION_DETAILS, evidenceInput.sourceDescription),
            integer(LocalField.EXPECTED_REQUESTER_POLICY_REVISION, 21),
            choice(LocalField.ARRIVAL_RULE_ID, evidenceInput.rule.ruleId.value),
            choice(LocalField.ARRIVAL_RETENTION, evidenceInput.retention.choiceCode),
            choice(LocalField.ARRIVAL_DOCUMENT_SELECTION, "SELECTED"),
            LocalScalarInput(LocalField.ARRIVAL_ORIGINAL_DOCUMENTS, LocalScalarValue.References(evidenceInput.documents.map { it.reference })),
            string(LocalField.OBSERVATION_REFERENCE, step.observation), string(LocalField.REASON, reasonCode))
        if (step == AawClientStep.RECORD) return LocalCommandIntent(operationId, step.ui, 2,
            LocalRecordReference(LocalRecordKind.ARRIVAL_WAREHOUSE_V2, warehouseId, parentId = supplier),
            common + string(LocalField.SUPPLIER_DELIVERY_REFERENCE, "SYN Delivery 001"), LocalField.LINES,
            listOf(LocalInputLine(originalLineA, itemInputs(itemA) + listOf(
                decimal(LocalField.QUANTITY, "24"), id(LocalField.SOURCE_ID, sourceACarrier), id(LocalField.CUSTODY_UNIT_ID, custodyACarrier),
                choice(LocalField.DISPOSITION, "UNINSPECTED_HELD"), choice(LocalField.OWNERSHIP, "UNRESOLVED"))),
                LocalInputLine(originalLineB, itemInputs(itemB) + listOf(
                    decimal(LocalField.QUANTITY, "6"), id(LocalField.SOURCE_ID, sourceBCarrier), id(LocalField.CUSTODY_UNIT_ID, custodyBCarrier),
                    choice(LocalField.DISPOSITION, "UNINSPECTED_HELD"), choice(LocalField.OWNERSHIP, "UNRESOLVED")))), evidenceInput)
        val fields = common + itemInputs(itemA).filter { it.field !in setOf(LocalField.TRACKING, LocalField.REQUIRES_EXPIRY) } +
            listOf(integer(LocalField.EXPECTED_ARRIVAL_REVISION, priorRevisionCarrier.value),
                id(LocalField.CUSTODY_UNIT_ID, custodyACarrier), decimal(LocalField.QUANTITY, step.quantity),
                id(LocalField.SPLIT_SOURCE_ID, splitCarrier.sourceId), id(LocalField.SPLIT_CUSTODY_UNIT_ID, splitCarrier.custodyUnitId),
                id(LocalField.ARRIVAL_EVENT_ID, eventCarrier)) + when (step) {
                AawClientStep.QUALIFY -> listOf(LocalScalarInput(LocalField.INSPECTION_PASSED, LocalScalarValue.Flag(true)))
                AawClientStep.DISPATCH -> listOf(id(LocalField.RECIPIENT_SUPPLIER_ID, supplier))
                else -> emptyList()
            }
        return LocalCommandIntent(operationId, step.ui, 2,
            LocalRecordReference(LocalRecordKind.ARRIVAL_SLICE_V2, custodyACarrier, priorRevisionCarrier, arrivalId),
            fields, arrivalEvidence = evidenceInput)
    }

    fun stagedData(): StoredClientIntentV1 = StoredClientIntentV1.staged(owner, intent, request,
        text("Saved unpriced ${step.name.lowercase()} input"), summary)

    /** Supplied CLIENT metadata only; no restricted Master preview or receipt is constructed. */
    fun readyData(original: StoredClientIntentV1): StoredClientIntentV1 = original.copy(actionApproval =
        checkNotNull(original.actionApproval).copy(phase = LocalActionApprovalPhase.READY_TO_CONFIRM.name,
            preview = StoredClientActionApprovalPreviewV1(requester, Revision(21), scope, arrivalId,
                priorRevisionCarrier, semanticDigest, operationId, reasonCode, confirmationData)))

    fun unresolvedData(ready: StoredClientIntentV1, approvalId: DomainId = DomainId.random()): StoredClientIntentV1 =
        ready.copy(actionApproval = checkNotNull(ready.actionApproval).copy(
            phase = LocalActionApprovalPhase.ISSUE_UNRESOLVED.name, approvalId = approvalId))

    fun attachedData(unresolved: StoredClientIntentV1): StoredClientIntentV1 {
        val ceremony = checkNotNull(unresolved.actionApproval)
        return unresolved.copy(actionApprovalId = ceremony.approvalId,
            actionApproval = ceremony.copy(phase = LocalActionApprovalPhase.ATTACHED.name,
                receipt = StoredClientActionApprovalReceiptV1(approver, MonotonicMoment(clockCarrier, 1),
                    MonotonicMoment(clockCarrier, 2), null)))
    }

    fun consumedData(attached: StoredClientIntentV1): StoredClientIntentV1 {
        val ceremony = checkNotNull(attached.actionApproval)
        return attached.copy(actionApproval = ceremony.copy(phase = LocalActionApprovalPhase.CONSUMED.name,
            receipt = checkNotNull(ceremony.receipt).copy(consumedByOperationId = operationId)))
    }

    /** A client result-retention sample, NOT an executed/proved Master business outcome. */
    fun terminalData(): StoredKernelResultV1 {
        val result = LocalArrivalResultV2(step.physical, arrivalId,
            eventCarrier.takeUnless { step == AawClientStep.RECORD }, Revision(priorRevisionCarrier.value + 1),
            splitCarrier.custodyUnitId.takeUnless { step == AawClientStep.RECORD },
            financialStatus = LocalArrivalFinancialStatusV1.UNPRICED_UNRESOLVED)
        return StoredKernelResultV1(operationId, requester, true, "committed",
            encodeHex(wire.encodeToString(LocalArrivalResultV2.serializer(), result).encodeToByteArray()),
            Revision(1), JournalSequence(1), recordedAtCarrier)
    }

    fun sessionView(actor: DomainId = requester): LocalSessionView = LocalSessionView(actor, scope, owner.deviceId,
        DomainId("a1300000-0000-4000-8000-000000000001"), SessionGeneration(1), LocalLoginIdentifier("REQUESTER-R"),
        ActivatedLocalPolicy(scope, actor, Revision(21), listOf(PermissionVerb.REQUEST, PermissionVerb.PERFORM).map {
            PermissionGrant(Permission(SecurityAction.INVENTORY_ADJUST, it), scope, GrantEffect.ALLOW)
        }, LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(clockCarrier, 10), MonotonicMoment(clockCarrier, 20))

    /** Current-source wire observations exercise form/stage only, never original-history authority. */
    fun sourceData(query: LocalArrivalSourceQueryV2): LocalArrivalSourcePageV2 {
        val suppliers = if (query.kind == LocalArrivalSourceKindV1.SUPPLIER)
            listOf(LocalArrivalSupplierViewV2(supplier, Revision(3), LocalArrivalNamesV1("Synthetic supplier S", "المورد التجريبي"), true)) else emptyList()
        val items = if (query.kind == LocalArrivalSourceKindV1.ITEM) listOf(itemA, itemB)
            .filter { query.id == null || it.skuId == query.id }.sortedBy { it.skuId.value }.map {
                LocalArrivalItemViewV2(it, LocalArrivalNamesV1("Synthetic ${if (it == itemA) "A" else "B"}", "صنف تجريبي"),
                    if (it == itemA) "A" else "B", LocalArrivalTrackingV1.QUANTITY, false, true, true)
            } else emptyList()
        val warehouses = if (query.kind == LocalArrivalSourceKindV1.WAREHOUSE)
            listOf(LocalArrivalWarehouseViewV2(warehouse, LocalArrivalNamesV1("Synthetic hold", "عهدة تجريبية"), true)) else emptyList()
        val purchasing = if (query.kind == LocalArrivalSourceKindV1.SUPPLIER) LocalArrivalPurchasingViewV2(
            storePolicy(), storeCatalog(), listOf(LocalArrivalRuleViewV2(ruleId, step.physical, false, 1, 2,
                listOf("PDF"), listOf("SUPPLIER_DELIVERY"), LocalArrivalRetentionSelectionV2(retention.classId, retention.revision),
                StoreApprovalRoute(StoreApprovalMode.valueOf(route.mode.name),
                    requesters = StoreActors(StoreActorMode.CURRENT_BRANCH_GRANTEES),
                    approvers = StoreActors(StoreActorMode.CURRENT_BRANCH_GRANTEES), namedEmployeeIds = route.namedEmployeeIds), ruleDetails)),
            query.documentIds.map { requested -> storeDocuments().single { it.documentId == requested } }) else null
        return LocalArrivalSourcePageV2(query.kind, suppliers, items, warehouses, emptyList(), null, purchasing, Revision(21))
    }

    private fun originalRequest(): MutationRequest {
        val evidence = LocalArrivalEvidenceSelectionV2(step.observation, storePolicy(), storeCatalog(), ruleId,
            storeDocuments(), LocalArrivalRetentionSelectionV2(retention.classId, retention.revision))
        return when (step) {
            AawClientStep.RECORD -> LocalUnpricedArrivalCommandsV2.record(operationId, LocalRecordUnpricedArrivalV2(
                arrivalId, supplier, Revision(3), Revision(21), supplierDeliveryReference = "SYN Delivery 001",
                items = listOf(itemA, itemB), warehouses = listOf(warehouse), lines = listOf(
                    LocalArrivalLineV2(originalLineA, sourceACarrier, custodyACarrier, itemA.skuId, warehouseId,
                        Quantity.parse(unitCarrier, "24", 0), disposition = LocalArrivalDispositionV1.UNINSPECTED_HELD,
                        ownership = LocalArrivalOwnershipV1.UNRESOLVED),
                    LocalArrivalLineV2(originalLineB, sourceBCarrier, custodyBCarrier, itemB.skuId, warehouseId,
                        Quantity.parse(unitCarrier, "6", 0), disposition = LocalArrivalDispositionV1.UNINSPECTED_HELD,
                        ownership = LocalArrivalOwnershipV1.UNRESOLVED)), evidence = evidence, reasonCode = reasonCode))
            AawClientStep.QUALIFY -> LocalUnpricedArrivalCommandsV2.qualify(operationId, LocalQualifyUnpricedArrivalV2(
                eventCarrier, arrivalId, priorRevisionCarrier, Revision(21), Revision(3), itemA, warehouse,
                custodyACarrier, Quantity.parse(unitCarrier, "10", 0), splitCarrier,
                inspectionPassed = true, evidence = evidence, reasonCode = reasonCode))
            AawClientStep.REJECT -> LocalUnpricedArrivalCommandsV2.reject(operationId, LocalRejectUnpricedArrivalV2(
                eventCarrier, arrivalId, priorRevisionCarrier, Revision(21), Revision(3), itemA, warehouse,
                custodyACarrier, Quantity.parse(unitCarrier, "4", 0), splitCarrier, evidence, reasonCode))
            AawClientStep.DISPATCH -> LocalUnpricedArrivalCommandsV2.dispatch(operationId, LocalDispatchUnpricedArrivalV2(
                eventCarrier, arrivalId, priorRevisionCarrier, Revision(21), Revision(3), itemA, warehouse,
                custodyACarrier, Quantity.parse(unitCarrier, "3", 0), splitCarrier, supplier, evidence, reasonCode))
        }
    }

    private fun storePolicy(): LocalArrivalPolicySelectionV2 = LocalArrivalPolicySelectionV2(scope, policy.policyId,
        policy.publicationId, policy.publicationRevision, storeSource(policy.activePointer), storeSource(policy.publication), policy.validFrom, policy.validUntil)
    private fun storeCatalog(): LocalArrivalCatalogSelectionV2 = LocalArrivalCatalogSelectionV2(scope, catalog.catalogId,
        catalog.publicationId, catalog.revision, storeSource(catalog.activePointer), storeSource(catalog.publication))
    private fun storeDocuments(): List<LocalArrivalDocumentSelectionV2> = documents.map {
        LocalArrivalDocumentSelectionV2(it.scope, it.supplierId, it.documentId, it.storageRevision, it.exactBytesDigest, it.byteLength,
            it.media.name, it.purpose.name)
    }
    private fun storeSource(source: LocalArrivalSourceCoordinateV2): LocalArrivalSourceSelectionV2 =
        LocalArrivalSourceSelectionV2(source.kind, source.id, source.storageRevision, source.digest)

    private fun itemInputs(item: LocalArrivalItemSelectionV2): List<LocalScalarInput> = listOf(
        LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Reference(LocalRecordReference(LocalRecordKind.SKU, item.skuId, item.skuRevision))),
        id(LocalField.PRODUCT_ID, item.productId), integer(LocalField.EXPECTED_PRODUCT_REVISION, item.productRevision.value),
        id(LocalField.UNIT, item.baseUnitId), integer(LocalField.QUANTITY_SCALE, 0),
        integer(LocalField.EXPECTED_CATALOG_REVISION, item.catalogRevision.value),
        integer(LocalField.EXPECTED_CATALOG_STORAGE_REVISION, item.storageRevision.value),
        choice(LocalField.TRACKING, "QUANTITY"), LocalScalarInput(LocalField.REQUIRES_EXPIRY, LocalScalarValue.Flag(false)))

    companion object {
        val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false }
        fun text(value: String): LocalText = LocalText(value, value)
        private fun frozenCoordinate(prefix: String, suffix: Int): String =
            "$prefix-0000-4000-8000-${suffix.toString(16).padStart(12, '0')}"
        private fun id(field: LocalField, value: DomainId): LocalScalarInput = LocalScalarInput(field, LocalScalarValue.Identifier(value))
        private fun integer(field: LocalField, value: Long): LocalScalarInput = LocalScalarInput(field, LocalScalarValue.Integer(value))
        private fun string(field: LocalField, value: String): LocalScalarInput = LocalScalarInput(field, LocalScalarValue.Text(value))
        private fun decimal(field: LocalField, value: String): LocalScalarInput = LocalScalarInput(field, LocalScalarValue.Decimal(value))
        private fun choice(field: LocalField, value: String): LocalScalarInput = LocalScalarInput(field, LocalScalarValue.Choice(value))
        private val confirmationDigests = mapOf(
            AawClientJourney.SEPARATE_R_A to listOf("badfd2ef0a137206d65b9c6949c02c029fdbfbc2f9165733c6c1c03de6a446bc",
                "8b420ae7f162badef75fdb2d09cb5f42cdcdc672b45fe239d9d76a3e6566717a",
                "3e4b0ee07041dd4d9ed7f904982b4506afc7462bfbef1c4db323fc2f0f30a1ce",
                "c00b5be67f4791365fa7ca1b293f23c5b77bc5c2146470209155d57eb0ce72ab"),
            AawClientJourney.NAMED_SELF_R_R to listOf("0585c4d34d73d3034654f06aea3a258e84933b7543ad8c0797be64a1da8457d4",
                "1f7023fded4546f3205c2115f707517e020c97fbe3c040cf5b434d31b3319d5a",
                "3e260c7a0b7c926511ce69ce8c418201e0c75eb4ba95b65af7a9509fbe1695fe",
                "bf3281da023e27179c07107e258687b9c702f502fbcb77b08a1e6e619da7fc09"),
            AawClientJourney.NAMED_SELF_R_A to listOf("aa0981dc5e846a79d3a78a2f79145ada5a7bf360d973554bf75ab75fc3a1b656",
                "ff8eff3f9f45452b922d6df2bfdfce5aa6969dcabd65ca2e42bd42bfdfff57bb",
                "b1c9e079390c98f89d59e9d1fac9ed3edbaa4c165ab034907226bfbb6800d63d",
                "d3eaad8114882fdc7cf85ec8c7998d2e854b9f4c1228e77206f66fdc84f52023"))
    }
}

internal data class AawClientIssueObservation(val approvalId: DomainId, val request: MutationRequest,
    val confirmationDigest: PayloadDigest, val login: LocalLoginIdentifier, val borrowedPassword: CharArray,
    val requestor: LocalGatewayCredentials)

/**
 * No authentication, native proofs, accepting writer, issued AP or recorded business result.
 * Ordinary AbsentAtCurrentWriter is supplied transport DATA solely to discriminate
 * the pre-attachment guard; it is not proof of native absence or safe re-dispatch.
 */
internal class ControlledAawClientEndpoint(val inputs: AawClientJournalInputs) : LocalMasterClient, LocalMasterApprovalClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var view = inputs.sessionView()
    var currentDenied = false
    var previewReply: LocalActionApprovalPreviewResult = LocalActionApprovalPreviewResult.Denied
    var issueReply: LocalActionApprovalIssueResult = LocalActionApprovalIssueResult.StorageUncertain
    var approvalStatus: LocalActionApprovalStatus = LocalActionApprovalStatus.NotRecorded
    var onIssue: suspend (AawClientIssueObservation) -> Unit = {}
    var businessDenial: DeferredReason? = null
    val reads = mutableListOf<ReadRequest>()
    val previews = mutableListOf<MutationRequest>()
    val issues = mutableListOf<AawClientIssueObservation>()
    val approvalLookups = mutableListOf<Pair<DomainId, PayloadDigest>>()
    val businessLookups = mutableListOf<OperationIdentity>()
    val executions = mutableListOf<MutationRequest>()
    var signIns = 0
    var refreshes = 0
    var signOuts = 0

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult { signIns++; return LocalAuthenticationResult.Unavailable }
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult { refreshes++; return LocalAuthenticationResult.Unavailable }
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult { signOuts++; return LocalSignOutResult.Denied }
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        if (currentDenied) LocalSessionViewResult.Denied else LocalSessionViewResult.Current(view)
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        return MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        businessLookups += identity
        return businessDenial?.let { LookupReply.Deferred(identity.operationId, it) }
            ?: LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        if (request.query != LocalUnpricedArrivalProfileV2.SOURCE) return ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY)
        val query = AawClientJournalInputs.wire.decodeFromString(LocalArrivalSourceQueryV2.serializer(), request.payload.copyBytes().decodeToString())
        return ReadReply.Data(StoredBytes(AawClientJournalInputs.wire.encodeToString(LocalArrivalSourcePageV2.serializer(),
            inputs.sourceData(query)).encodeToByteArray()), Revision(1), inputs.recordedAtCarrier)
    }
    override suspend fun previewActionApproval(requestor: LocalGatewayCredentials, request: MutationRequest,
        retiredApprovalId: DomainId?): LocalActionApprovalPreviewResult {
        previews += request
        return previewReply
    }
    override suspend fun issueActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest, approverLogin: LocalLoginIdentifier,
        approverPassword: CharArray, retiredApprovalId: DomainId?): LocalActionApprovalIssueResult {
        val observed = AawClientIssueObservation(approvalId, request, confirmationDigest, approverLogin, approverPassword, requestor)
        issues += observed
        onIssue(observed)
        return issueReply
    }
    override suspend fun lookupActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        confirmationDigest: PayloadDigest): LocalActionApprovalStatus {
        approvalLookups += approvalId to confirmationDigest
        return approvalStatus
    }

    // Compatibility only: this earlier bounded fixture supplies no retirement authority.
    override suspend fun retireActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest): LocalActionApprovalRetireResult =
        LocalActionApprovalRetireResult.Unavailable
}

/** Synthetic opaque input/lease observations only; they are never valid Master access tokens. */
internal class AawClientCredentialInputs : LocalWorkflowCredentialSource {
    var active = true
    val issued = mutableListOf<LocalWorkflowCredentialLease>()
    val marker = "SYN-REQUESTER-OPAQUE-INPUT-NOT-AN-AUTHENTIC-TOKEN"
    override suspend fun acquire(): LocalWorkflowCredentialLease? = if (!active) null else
        LocalWorkflowCredentialLease(LocalGatewayCredentials(DomainId.random(), OpaqueCredential(marker.encodeToByteArray()))) { active }
            .also(issued::add)
}

internal fun aawClientGateway(host: SyntheticArrivalApprovalEncryptedJournal, endpoint: ControlledAawClientEndpoint,
    credentials: AawClientCredentialInputs = AawClientCredentialInputs()): LocalEndpointWorkflowGateway =
    LocalEndpointWorkflowGateway(endpoint, host.journal, credentials,
        LocalWorkflowInstallation(setOf(LocalUnpricedArrivalProfileV2.RECORD, LocalUnpricedArrivalProfileV2.QUALIFY,
            LocalUnpricedArrivalProfileV2.REJECT, LocalUnpricedArrivalProfileV2.DISPATCH), setOf(LocalUnpricedArrivalProfileV2.SOURCE)))
