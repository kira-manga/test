package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/** Additive direct-only custody profile. No v1 source, guard or request is reinterpreted. */
public object LocalUnpricedArrivalProfileV2 {
    public val RECORD: CommandKey = CommandKey("local.stock.arrival.record", 2)
    public val QUALIFY: CommandKey = CommandKey("local.stock.arrival.qualify", 2)
    public val REJECT: CommandKey = CommandKey("local.stock.arrival.reject", 2)
    public val DISPATCH: CommandKey = CommandKey("local.stock.arrival.dispatch", 2)
    public val SOURCE: ReadKey = ReadKey("local.stock.arrival.source", 2)
    public val READ: ReadKey = ReadKey("local.stock.arrival.read", 2)
    public const val ARRIVAL: String = "local.stock.arrival-v2"
    public const val STATE: String = "local.stock.arrival-state-v2"
    public const val EVENT: String = "local.stock.arrival-event-v2"
    public const val SOURCE_IDENTITY: String = "local.stock.arrival-source-v2"
    public const val SERIAL_GUARD: String = "local.stock.arrival-serial-v2"
    public const val LOT_GUARD: String = "local.stock.arrival-lot-v2"
    public const val MAX_SLICES: Int = LocalUnpricedArrivalProfileV1.MAX_SLICES
    public const val MAX_EVENTS: Int = LocalUnpricedArrivalProfileV1.MAX_EVENTS
    public const val MAX_HEADERS: Int = LocalUnpricedArrivalProfileV1.MAX_HEADERS
    public const val MAX_INPUT_BYTES: Int = LocalUnpricedArrivalProfileV1.MAX_INPUT_BYTES
    public const val MAX_ROW_BYTES: Int = LocalUnpricedArrivalProfileV1.MAX_ROW_BYTES
    public const val MAX_PURCHASING_SOURCES: Int = 512
    public fun schema(kind: String): String = "$kind/v2"
}

// Coordinate-only physical values are unchanged. They contain no source generations or grants.
public typealias LocalArrivalLineV2 = LocalArrivalLineV1
public typealias LocalArrivalSplitV2 = LocalArrivalSplitV1

@Serializable
public enum class LocalArrivalFailureV2 {
    UNSUPPORTED_POLICY, EVIDENCE_REQUIRED, EVIDENCE_UNAVAILABLE, SOURCE_MISSING, SOURCE_ALREADY_USED,
    STALE_REVISION, SUPPLIER_UNAVAILABLE, ITEM_UNAVAILABLE, WAREHOUSE_UNAVAILABLE,
    INVALID_QUANTITY, INVALID_UNIT, INVALID_TRACKING, INVALID_SERIAL, SERIAL_REGISTRATION_REQUIRED,
    INVALID_LOT, KNOWN_METADATA_IMMUTABLE, INVALID_DISPOSITION, WRONG_RECIPIENT,
    NO_NEW_OBSERVATION, HYDRATION_CAPACITY_UNAVAILABLE, CAPACITY_EXCEEDED,
    POLICY_MISMATCH, APPROVAL_HISTORY_UNAVAILABLE, STALE_REQUESTER_POLICY, DOCUMENTARY_EFFECTS_UNAVAILABLE,
}

/** Present generation zero binds an exact row. It is never a null/absence selection. */
@Serializable
public data class LocalArrivalSourceSelectionV2(
    public val kind: String, public val id: DomainId, public val storageRevision: Revision,
    public val digest: PayloadDigest,
) {
    init { AggregateKey(kind, id) }
    public fun stamp(): LocalStockSourceStamp = LocalStockSourceStamp(AggregateKey(kind, id), storageRevision, digest)
    public companion object {
        public fun from(source: LocalStockSourceStamp): LocalArrivalSourceSelectionV2 =
            LocalArrivalSourceSelectionV2(source.key.kind, source.key.id, source.storageRevision, source.digest)
    }
}

@Serializable
public data class LocalArrivalPolicySelectionV2(
    public val scope: BranchScope, public val policyId: DomainId, public val publicationId: DomainId,
    public val publicationRevision: Revision,
    public val activePointer: LocalArrivalSourceSelectionV2, public val publication: LocalArrivalSourceSelectionV2,
    public val validFrom: UtcInstant, public val validUntil: UtcInstant?,
) {
    init {
        require(publicationRevision.value >= 1 && publication.id == publicationId)
        require(validUntil == null || validUntil > validFrom)
    }
}

@Serializable
public data class LocalArrivalCatalogSelectionV2(
    public val scope: BranchScope, public val catalogId: DomainId, public val publicationId: DomainId,
    public val revision: Revision,
    public val activePointer: LocalArrivalSourceSelectionV2, public val publication: LocalArrivalSourceSelectionV2,
) { init { require(publication.id == publicationId) } }

@Serializable
public data class LocalArrivalRetentionSelectionV2(public val classId: DomainId, public val revision: Revision) {
    init { require(revision.value >= 1) }
}

/** Exact original manifest; a supplemental selection additionally binds its original parent/head. */
@Serializable
public data class LocalArrivalDocumentSelectionV2(
    public val scope: BranchScope, public val supplierId: DomainId, public val documentId: DomainId,
    public val storageRevision: Revision, public val exactBytesDigest: PayloadDigest, public val byteLength: Int,
    public val media: String, public val purpose: String,
    public val parentDocumentId: DomainId? = null, public val parentHead: LocalArrivalSourceSelectionV2? = null,
) {
    init {
        require(storageRevision.value >= 1 && byteLength > 0)
        require(media in PurchasingEvidenceMediaV1.entries.map { it.name })
        require(purpose in PurchasingEvidencePurposeV1.entries.map { it.name })
        require((parentDocumentId == null) == (parentHead == null))
        require(parentDocumentId == null || parentDocumentId != documentId)
    }
    public fun reference(): PurchasingArrivalDocumentReference = PurchasingArrivalDocumentReference(scope, supplierId,
        documentId, storageRevision, exactBytesDigest, byteLength, PurchasingEvidenceMediaV1.valueOf(media),
        PurchasingEvidencePurposeV1.valueOf(purpose))
}

/** Every action explicitly selects one rule and retention class, including with zero originals. */
@Serializable
public data class LocalArrivalEvidenceSelectionV2(
    public val observationReference: String, public val policy: LocalArrivalPolicySelectionV2,
    public val catalog: LocalArrivalCatalogSelectionV2, public val ruleId: DomainId,
    public val documents: List<LocalArrivalDocumentSelectionV2>, public val retention: LocalArrivalRetentionSelectionV2,
) {
    init {
        arrivalText(observationReference, 160)
        require(policy.scope == catalog.scope && documents.size <= LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS)
        require(documents.map { it.documentId }.distinct().size == documents.size)
        require(documents.all { it.scope == policy.scope })
    }
}

@Serializable
public data class LocalArrivalProductReferenceV2(
    public val skuId: DomainId, public val skuRevision: Revision,
    public val productId: DomainId, public val productRevision: Revision,
) {
    public fun reference(catalog: LocalArrivalCatalogSelectionV2): PurchasingSkuReferenceV1 = PurchasingSkuReferenceV1(
        skuId, skuRevision, productId, productRevision, PurchasingCatalogReferenceV1(catalog.publicationId, catalog.revision))
}

@Serializable
public data class LocalArrivalItemSelectionV2(
    public val skuId: DomainId, public val productId: DomainId, public val productRevision: Revision,
    public val baseUnitId: DomainId, public val quantityScale: Int,
    /** The signed SKU's coordinate; the active complete release is selected separately. */
    public val catalogRevision: Revision, public val skuRevision: Revision,
    public val storageRevision: Revision, public val stockStorageRevision: Revision?,
) {
    init { require(quantityScale in 0..6 && (stockStorageRevision == null || stockStorageRevision.value >= 1)) }
}

@Serializable
public data class LocalArrivalWarehouseSelectionV2(public val warehouseId: DomainId, public val storageRevision: Revision)

@Serializable
public data class LocalRecordUnpricedArrivalV2(
    public val arrivalId: DomainId, public val supplierId: DomainId, public val expectedSupplierRevision: Revision,
    public val expectedRequesterPolicyRevision: Revision,
    public val supplierDeliveryReference: String? = null, public val localIntakeTag: DomainId? = null,
    public val documentaryPoId: DomainId? = null, public val discrepancyReference: String? = null,
    public val items: List<LocalArrivalItemSelectionV2>, public val warehouses: List<LocalArrivalWarehouseSelectionV2>,
    public val lines: List<LocalArrivalLineV2>, public val evidence: LocalArrivalEvidenceSelectionV2,
    public val reasonCode: String,
) {
    init {
        require((supplierDeliveryReference == null) != (localIntakeTag == null))
        supplierDeliveryReference?.let(::normalizedPurchaseReference)
        discrepancyReference?.let { arrivalText(it, 160) }
        require(lines.size in 1..LocalUnpricedArrivalProfileV2.MAX_SLICES && items.size in 1..16 && warehouses.size in 1..16)
        require(items.map { it.skuId }.distinct().size == items.size && items.map { it.skuId }.toSet() == lines.map { it.skuId }.toSet())
        require(warehouses.map { it.warehouseId }.distinct().size == warehouses.size && warehouses.map { it.warehouseId }.toSet() == lines.map { it.warehouseId }.toSet())
        require(lines.map { it.lineId }.distinct().size == lines.size && lines.map { it.sourceId }.distinct().size == lines.size &&
            lines.map { it.custodyUnitId }.distinct().size == lines.size)
        require(lines.mapNotNull { it.serialId }.distinct().size == lines.count { it.serialId != null } &&
            lines.mapNotNull { it.serialCode }.distinct().size == lines.count { it.serialCode != null })
        require(evidence.documents.all { it.supplierId == supplierId })
        arrivalReason(reasonCode)
    }
}

@Serializable
public data class LocalQualifyUnpricedArrivalV2(
    public val eventId: DomainId, public val arrivalId: DomainId, public val expectedArrivalRevision: Revision,
    public val expectedRequesterPolicyRevision: Revision,
    public val expectedSupplierRevision: Revision, public val item: LocalArrivalItemSelectionV2,
    public val warehouse: LocalArrivalWarehouseSelectionV2,
    public val custodyUnitId: DomainId, public val quantity: Quantity, public val split: LocalArrivalSplitV2? = null,
    public val lotId: DomainId? = null, public val expiresAt: UtcInstant? = null,
    public val serialId: DomainId? = null, public val serialCode: String? = null, public val serialRegistrationRevision: Revision? = null,
    public val inspectionPassed: Boolean = false, public val evidence: LocalArrivalEvidenceSelectionV2, public val reasonCode: String,
) {
    init {
        arrivalTransitionInput(expectedArrivalRevision, quantity, reasonCode)
        expiresAt?.let { require(it.value.toEpochMilliseconds() >= 0) }
        serialCode?.let(::requireStockSerialCode)
        require((serialId == null) == (serialRegistrationRevision == null))
        require(serialRegistrationRevision == null || serialRegistrationRevision.value >= 1)
    }
}

@Serializable
public data class LocalRejectUnpricedArrivalV2(
    public val eventId: DomainId, public val arrivalId: DomainId, public val expectedArrivalRevision: Revision,
    public val expectedRequesterPolicyRevision: Revision,
    public val expectedSupplierRevision: Revision, public val item: LocalArrivalItemSelectionV2,
    public val warehouse: LocalArrivalWarehouseSelectionV2,
    public val custodyUnitId: DomainId, public val quantity: Quantity, public val split: LocalArrivalSplitV2? = null,
    public val evidence: LocalArrivalEvidenceSelectionV2, public val reasonCode: String,
) { init { arrivalTransitionInput(expectedArrivalRevision, quantity, reasonCode) } }

@Serializable
public data class LocalDispatchUnpricedArrivalV2(
    public val eventId: DomainId, public val arrivalId: DomainId, public val expectedArrivalRevision: Revision,
    public val expectedRequesterPolicyRevision: Revision,
    public val expectedSupplierRevision: Revision, public val item: LocalArrivalItemSelectionV2,
    public val warehouse: LocalArrivalWarehouseSelectionV2,
    public val custodyUnitId: DomainId, public val quantity: Quantity, public val split: LocalArrivalSplitV2? = null,
    public val recipientSupplierId: DomainId, public val evidence: LocalArrivalEvidenceSelectionV2, public val reasonCode: String,
) { init { arrivalTransitionInput(expectedArrivalRevision, quantity, reasonCode) } }

@Serializable
public data class LocalArrivalResultV2(
    public val action: LocalArrivalActionV1, public val arrivalId: DomainId, public val eventId: DomainId?,
    public val arrivalRevision: Revision?, public val affectedCustodyUnitId: DomainId?, public val failure: LocalArrivalFailureV2? = null,
    public val financialStatus: LocalArrivalFinancialStatusV1 = LocalArrivalFinancialStatusV1.UNPRICED_UNRESOLVED,
) {
    init {
        require((failure == null) == (arrivalRevision != null) && (action == LocalArrivalActionV1.RECORD) == (eventId == null))
        require(failure == null || affectedCustodyUnitId == null)
        if (failure == null) require(requireNotNull(arrivalRevision).value >= 1 && (affectedCustodyUnitId == null) == (action == LocalArrivalActionV1.RECORD))
    }
}

@Serializable public data class LocalArrivalSupplierViewV2(public val id: DomainId, public val storageRevision: Revision,
    public val names: LocalArrivalNamesV1, public val active: Boolean)
@Serializable public data class LocalArrivalWarehouseViewV2(public val selection: LocalArrivalWarehouseSelectionV2,
    public val names: LocalArrivalNamesV1, public val active: Boolean)
@Serializable public data class LocalArrivalItemViewV2(public val selection: LocalArrivalItemSelectionV2,
    public val names: LocalArrivalNamesV1, public val code: String, public val tracking: LocalArrivalTrackingV1?,
    public val requiresExpiry: Boolean, public val active: Boolean, public val canPurchase: Boolean)

@Serializable
public enum class LocalArrivalApprovalModeV2 { DIRECT, SEPARATE, NAMED_SELF }

@Serializable
public enum class LocalArrivalActorSelectionModeV2 { CURRENT_BRANCH_GRANTEES, NAMED_EMPLOYEES }

/** Signed documentary selector only; current employee grants are still checked by the Master. */
@Serializable
public data class LocalArrivalActorSelectionV2(
    public val mode: LocalArrivalActorSelectionModeV2,
    public val employeeIds: List<DomainId> = emptyList(),
) {
    init {
        when (mode) {
            LocalArrivalActorSelectionModeV2.CURRENT_BRANCH_GRANTEES -> require(employeeIds.isEmpty())
            LocalArrivalActorSelectionModeV2.NAMED_EMPLOYEES -> arrivalNamedEmployeeIdsV2(employeeIds)
        }
    }
}

/** Complete signed route projection for display/equality, never an approval or execution permit. */
@Serializable
public data class LocalArrivalApprovalRouteV2(
    public val mode: LocalArrivalApprovalModeV2,
    public val performers: LocalArrivalActorSelectionV2? = null,
    public val requesters: LocalArrivalActorSelectionV2? = null,
    public val approvers: LocalArrivalActorSelectionV2? = null,
    public val namedEmployeeIds: List<DomainId> = emptyList(),
) {
    init {
        when (mode) {
            LocalArrivalApprovalModeV2.DIRECT ->
                require(performers != null && requesters == null && approvers == null && namedEmployeeIds.isEmpty())
            LocalArrivalApprovalModeV2.SEPARATE ->
                require(performers == null && requesters != null && approvers != null && namedEmployeeIds.isEmpty())
            LocalArrivalApprovalModeV2.NAMED_SELF -> {
                require(performers == null && requesters != null && approvers != null)
                arrivalNamedEmployeeIdsV2(namedEmployeeIds)
                for (selection in listOf(requireNotNull(requesters), requireNotNull(approvers))) {
                    if (selection.mode == LocalArrivalActorSelectionModeV2.NAMED_EMPLOYEES)
                        require(namedEmployeeIds.all { it in selection.employeeIds })
                }
            }
        }
    }
}

private fun arrivalNamedEmployeeIdsV2(employeeIds: List<DomainId>) {
    require(employeeIds.size in 1..LocalCompanyPolicyFormatV1.MAX_NAMED_EMPLOYEES)
    require(employeeIds.zipWithNext().all { (before, after) -> before.value < after.value })
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
public data class LocalArrivalRuleViewV2(
    public val ruleId: DomainId, public val action: LocalArrivalActionV1,
    /** Retained compatibility hint; false never permits direct execution. */
    public val directOnly: Boolean, public val minimumDocuments: Int, public val maximumDocuments: Int,
    public val media: List<String>, public val purposes: List<String>, public val retention: LocalArrivalRetentionSelectionV2,
    /** Null is a missing documentary projection, never an accepting actor or approval default. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val approvalRoute: LocalArrivalApprovalRouteV2? = null,
    /** Complete remaining signed-rule selectors/constraints; display data, not authority. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    public val ruleDetails: String? = null,
) {
    init {
        require(minimumDocuments in 0..maximumDocuments && maximumDocuments in 1..LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS)
        require(media.isNotEmpty() && media.distinct().size == media.size && media.all { it in PurchasingEvidenceMediaV1.entries.map { item -> item.name } })
        require(purposes.isNotEmpty() && purposes.distinct().size == purposes.size && purposes.all { it in PurchasingEvidencePurposeV1.entries.map { item -> item.name } })
        require(approvalRoute == null || directOnly == (approvalRoute.mode == LocalArrivalApprovalModeV2.DIRECT))
        require(ruleDetails == null || ruleDetails.isNotEmpty() &&
            ruleDetails.encodeToByteArray(throwOnInvalidSequence = true).size <= 32_768)
    }
}

@Serializable public data class LocalArrivalPurchasingViewV2(
    public val policy: LocalArrivalPolicySelectionV2, public val catalog: LocalArrivalCatalogSelectionV2,
    public val rules: List<LocalArrivalRuleViewV2>, public val documents: List<LocalArrivalDocumentSelectionV2>,
) { init { require(policy.scope == catalog.scope && rules.map { it.ruleId }.distinct().size == rules.size) } }

@Serializable public data class LocalArrivalSourceQueryV2(
    public val kind: LocalArrivalSourceKindV1, public val id: DomainId? = null, public val after: DomainId? = null,
    public val limit: Int = 5, public val supplierId: DomainId? = null,
    public val action: LocalArrivalActionV1 = LocalArrivalActionV1.RECORD,
    public val skuIds: List<DomainId> = emptyList(), public val documentIds: List<DomainId> = emptyList(),
) {
    init {
        require(limit in 1..5 && (id == null || after == null))
        require(kind != LocalArrivalSourceKindV1.SERIAL_REGISTRATION || id != null)
        require(skuIds.size <= 16 && skuIds.distinct().size == skuIds.size)
        require(documentIds.size <= LocalCompanyPolicyFormatV1.MAX_EVIDENCE_MANIFESTS && documentIds.distinct().size == documentIds.size)
    }
}

/** Employee revision is set only by the protected read projection, not by prepare/source data. */
@Serializable public data class LocalArrivalSourcePageV2(
    public val kind: LocalArrivalSourceKindV1, public val suppliers: List<LocalArrivalSupplierViewV2>?,
    public val items: List<LocalArrivalItemViewV2>?, public val warehouses: List<LocalArrivalWarehouseViewV2>?,
    public val serials: List<LocalArrivalSerialViewV1>?, public val nextAfter: DomainId?,
    public val purchasing: LocalArrivalPurchasingViewV2?, public val requesterPolicyRevision: Revision,
    public val availability: LocalArrivalAvailabilityV1 = LocalArrivalAvailabilityV1.AVAILABLE,
) {
    init {
        if (availability == LocalArrivalAvailabilityV1.AVAILABLE) require(suppliers != null && items != null && warehouses != null && serials != null)
        else require(suppliers == null && items == null && warehouses == null && serials == null && nextAfter == null && purchasing == null)
    }
}

@Serializable public data class LocalArrivalDetailV2(public val header: LocalArrivalHeaderV1,
    public val slices: List<LocalArrivalSliceViewV1>, public val events: List<LocalArrivalEventViewV1>,
    public val purchasing: List<LocalArrivalPurchasingViewV2>)
@Serializable public data class LocalArrivalPageV2(public val headers: List<LocalArrivalHeaderV1>?, public val detail: LocalArrivalDetailV2?,
    public val nextAfter: DomainId?, public val requesterPolicyRevision: Revision,
    public val availability: LocalArrivalAvailabilityV1 = LocalArrivalAvailabilityV1.AVAILABLE) {
    init { if (availability == LocalArrivalAvailabilityV1.AVAILABLE) require(headers != null) else require(headers == null && detail == null && nextAfter == null) }
}

/** Complete inert source coordinates and signed original bytes retained with each custody action. */
@Serializable public data class LocalArrivalPurchasingEvidenceV2(
    public val selection: LocalArrivalEvidenceSelectionV2, public val policyWireHex: String,
    public val products: List<LocalArrivalProductReferenceV2>, public val originalDocuments: List<LocalArrivalDocumentSelectionV2>,
    public val sources: List<LocalArrivalSourceSelectionV2>, public val documentaryUse: LocalArrivalSourceSelectionV2,
) {
    init {
        require(policyWireHex.length in 2..28 * 1024 && policyWireHex.length % 2 == 0 && policyWireHex.all { it in '0'..'9' || it in 'a'..'f' })
        require(products.isNotEmpty() && products.size <= 16 && products.map { it.skuId }.distinct().size == products.size)
        require(originalDocuments == selection.documents && sources.size in 1..LocalUnpricedArrivalProfileV2.MAX_PURCHASING_SOURCES)
        require(sources.map { it.kind to it.id }.distinct().size == sources.size && documentaryUse.storageRevision.value == 1L)
    }
}

/** Narrow actual-source integration; null is unavailable, never an optional evidence policy. */
public interface LocalUnpricedArrivalAuthorityV2 : LocalUnpricedArrivalDocumentaryCodecV2 {
    public suspend fun supplier(view: CommandReadView, id: DomainId): LocalArrivalSupplierAuthorityV2?
    public suspend fun item(view: CommandReadView, id: DomainId): LocalArrivalItemAuthorityV2?
    public suspend fun warehouse(view: CommandReadView, id: DomainId): LocalArrivalWarehouseAuthority?
    public suspend fun serialRegistration(view: CommandReadView, id: DomainId): LocalStockSerialRegistration?
    public suspend fun keys(view: CommandReadView, kind: LocalArrivalSourceKindV1, after: DomainId?, limit: Int): LocalArrivalSourceKeys
    public suspend fun purchasing(view: CommandReadView, supplierId: DomainId, action: LocalArrivalActionV1,
        skuIds: List<DomainId>, documentIds: List<DomainId>): LocalArrivalPurchasingSourceV2?
    public suspend fun prepareEvidence(view: CommandReadView, context: CommandContext, action: LocalArrivalActionV1,
        selection: LocalArrivalEvidenceSelectionV2, expectedRequesterPolicyRevision: Revision,
        supplier: LocalArrivalSupplierAuthorityV2, items: List<LocalArrivalItemAuthorityV2>,
        requirement: AuthorizationRequirement): LocalArrivalPreparedEvidenceV2?
}

/** Installed inert codec for whole custody/use comparisons; it never qualifies original sources. */
public interface LocalUnpricedArrivalDocumentaryCodecV2 {
    public val documentaryKinds: Set<String>
    public val documentaryClaimNamespaces: Set<String>
    public fun retainedEffects(original: RetainedCommandEvidence, evidence: LocalArrivalPurchasingEvidenceV2,
        requirement: AuthorizationRequirement, expectedRequesterPolicyRevision: Revision): LocalArrivalDocumentaryEffectsV2
}

public class LocalArrivalSupplierAuthorityV2(public val scope: BranchScope, public val value: LocalArrivalSupplierViewV2,
    public val source: LocalStockSourceStamp, sources: List<LocalStockSourceStamp>) {
    public val sources: List<LocalStockSourceStamp> = arrivalFrozenSourcesV2(sources)
    init { require(source in sources && source.key.id == value.id && source.storageRevision == value.storageRevision) }
}
public class LocalArrivalItemAuthorityV2(public val value: LocalStockItemAuthority, public val source: LocalStockSourceStamp,
    public val names: LocalArrivalNamesV1, public val code: String, public val productRevision: Revision) {
    init { require(source in value.sources && source.key.id == value.skuId) }
}
public class LocalArrivalPurchasingSourceV2(public val value: LocalArrivalPurchasingViewV2,
    sources: List<LocalStockSourceStamp>, affectedResources: Set<RecoveryResourceKey>) {
    public val sources: List<LocalStockSourceStamp> = arrivalFrozenSourcesV2(sources, LocalUnpricedArrivalProfileV2.MAX_PURCHASING_SOURCES)
    private val resources: Set<RecoveryResourceKey> = affectedResources.toSet()
    public val affectedResources: Set<RecoveryResourceKey> get() = resources.toSet()
}

/** Loaded only within the current borrowed writer; complete is pure and never retains that view. */
public abstract class LocalArrivalPreparedEvidenceV2 {
    public abstract val sources: List<LocalStockSourceStamp>
    public abstract val affectedResources: Set<RecoveryResourceKey>
    public abstract val mustCommitBefore: UtcInstant?
    public abstract fun complete(authorization: AuthorizedAction, recordedAt: UtcInstant): LocalArrivalDocumentaryEffectsV2
}

/** Immutable use/retention effects join the SAME custody Apply, including the zero-file case. */
public class LocalArrivalDocumentaryEffectsV2(public val evidence: LocalArrivalPurchasingEvidenceV2,
    changes: List<AggregateChange>, claims: List<ClaimChange>) {
    public val changes: List<AggregateChange> = localStockFrozen(changes)
    public val claims: List<ClaimChange> = localStockFrozen(claims)
    init {
        require(changes.isNotEmpty() && changes.size <= 128 && claims.size <= 128)
        require(changes.map { it.key }.distinct().size == changes.size && changes.all { it.expectedRevision == null })
        require(claims.map { it.key }.distinct().size == claims.size && claims.all { it is ClaimChange.Acquire })
        val use = changes.singleOrNull { it.key == evidence.documentaryUse.stamp().key }
        require(use != null && sha256(use.payload.copyBytes()) == evidence.documentaryUse.digest)
    }
}

/** Classified source refusal only; corrupt storage/history remains uncertainty. */
public class LocalArrivalEvidenceRejectedV2(public val failure: LocalArrivalFailureV2) : IllegalArgumentException(failure.name)

public object LocalUnpricedArrivalCommandsV2 {
    public fun record(operationId: OperationId, request: LocalRecordUnpricedArrivalV2): MutationRequest =
        MutationRequest(operationId, LocalUnpricedArrivalProfileV2.RECORD, arrivalEncode(request, LocalRecordUnpricedArrivalV2.serializer(), 32_768))
    public fun qualify(operationId: OperationId, request: LocalQualifyUnpricedArrivalV2): MutationRequest =
        MutationRequest(operationId, LocalUnpricedArrivalProfileV2.QUALIFY, arrivalEncode(request, LocalQualifyUnpricedArrivalV2.serializer(), 32_768))
    public fun reject(operationId: OperationId, request: LocalRejectUnpricedArrivalV2): MutationRequest =
        MutationRequest(operationId, LocalUnpricedArrivalProfileV2.REJECT, arrivalEncode(request, LocalRejectUnpricedArrivalV2.serializer(), 32_768))
    public fun dispatch(operationId: OperationId, request: LocalDispatchUnpricedArrivalV2): MutationRequest =
        MutationRequest(operationId, LocalUnpricedArrivalProfileV2.DISPATCH, arrivalEncode(request, LocalDispatchUnpricedArrivalV2.serializer(), 32_768))
    public fun normalize(command: CommandKey, body: StoredBytes): StoredBytes = arrivalNormalizeV2(command, body)
    public fun decodeRecordRequest(body: StoredBytes): LocalRecordUnpricedArrivalV2 = arrivalDecode(body, LocalRecordUnpricedArrivalV2.serializer(), 32_768)
    public fun decodeQualifyRequest(body: StoredBytes): LocalQualifyUnpricedArrivalV2 = arrivalDecode(body, LocalQualifyUnpricedArrivalV2.serializer(), 32_768)
    public fun decodeRejectRequest(body: StoredBytes): LocalRejectUnpricedArrivalV2 = arrivalDecode(body, LocalRejectUnpricedArrivalV2.serializer(), 32_768)
    public fun decodeDispatchRequest(body: StoredBytes): LocalDispatchUnpricedArrivalV2 = arrivalDecode(body, LocalDispatchUnpricedArrivalV2.serializer(), 32_768)
    public fun source(query: LocalArrivalSourceQueryV2): ReadRequest = ReadRequest(LocalUnpricedArrivalProfileV2.SOURCE, arrivalEncode(query, LocalArrivalSourceQueryV2.serializer(), 4_096))
    public fun read(query: LocalArrivalQueryV1): ReadRequest = ReadRequest(LocalUnpricedArrivalProfileV2.READ, arrivalEncode(query, LocalArrivalQueryV1.serializer(), 4_096))
    public fun decodeResult(body: StoredBytes): LocalArrivalResultV2 = arrivalDecode(body, LocalArrivalResultV2.serializer())
    public fun decodeSource(body: StoredBytes): LocalArrivalSourcePageV2 = arrivalDecode(body, LocalArrivalSourcePageV2.serializer())
    public fun decodePage(body: StoredBytes): LocalArrivalPageV2 = arrivalDecode(body, LocalArrivalPageV2.serializer())
}

internal fun arrivalFrozenSourcesV2(sources: List<LocalStockSourceStamp>, maximum: Int = 16): List<LocalStockSourceStamp> {
    require(sources.size in 1..maximum && sources.map { it.key }.distinct().size == sources.size)
    return localStockFrozen(sources)
}
