package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.normalizedPurchaseReference
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Unpriced physical custody only. These keys never write an owned stock or valuation row. */
public object LocalUnpricedArrivalProfileV1 {
    public val RECORD: CommandKey = CommandKey("local.stock.arrival.record", 1)
    public val QUALIFY: CommandKey = CommandKey("local.stock.arrival.qualify", 1)
    public val REJECT: CommandKey = CommandKey("local.stock.arrival.reject", 1)
    public val DISPATCH: CommandKey = CommandKey("local.stock.arrival.dispatch", 1)
    public val SOURCE: ReadKey = ReadKey("local.stock.arrival.source", 1)
    public val READ: ReadKey = ReadKey("local.stock.arrival.read", 1)
    public const val ARRIVAL: String = "local.stock.arrival"
    public const val STATE: String = "local.stock.arrival-state"
    public const val EVENT: String = "local.stock.arrival-event"
    public const val SOURCE_IDENTITY: String = "local.stock.arrival-source"
    public const val SERIAL_GUARD: String = "local.stock.arrival-serial"
    public const val LOT_GUARD: String = "local.stock.arrival-lot"
    public const val DOCUMENT_CLAIM: String = "local.stock.arrival.document"
    public const val SOURCE_CLAIM: String = "local.stock.arrival.source"
    public const val UNIT_CLAIM: String = "local.stock.arrival.unit"
    public const val LINE_CLAIM: String = "local.stock.arrival.line"
    public const val DELIVERY_CLAIM: String = "local.stock.arrival.delivery"
    public const val SERIAL_ID_CLAIM: String = "local.stock.arrival.serial-id"
    public const val SERIAL_CODE_CLAIM: String = "local.stock.arrival.serial-code"
    public const val LOT_CLAIM: String = "local.stock.arrival.lot"
    public const val MAX_SLICES: Int = 16
    public const val MAX_EVENTS: Int = 64
    public const val MAX_HEADERS: Int = 5
    public const val MAX_INPUT_BYTES: Int = 32_768
    public const val MAX_ROW_BYTES: Int = 65_536
    public fun schema(kind: String): String = "$kind/v1"
}

@Serializable public enum class LocalArrivalActionV1 { RECORD, QUALIFY, REJECT, DISPATCH }
@Serializable public enum class LocalArrivalTrackingV1 { QUANTITY, LOT, SERIAL }
@Serializable public enum class LocalArrivalDispositionV1 { UNINSPECTED_HELD, PASSED_HELD, REJECTED_HELD, REFUSED, DISPATCHED }
@Serializable public enum class LocalArrivalOwnershipV1 { UNRESOLVED, SUPPLIER_ATTESTED, COMPANY_ATTESTED }
@Serializable public enum class LocalArrivalFinancialStatusV1 { UNPRICED_UNRESOLVED }
@Serializable public enum class LocalArrivalAvailabilityV1 { AVAILABLE, HYDRATION_CAPACITY_UNAVAILABLE }
@Serializable public enum class LocalArrivalPolicyStatusV1 { AVAILABLE, UNSUPPORTED_POLICY }
@Serializable public enum class LocalArrivalSourceKindV1 { SUPPLIER, ITEM, WAREHOUSE, SERIAL_REGISTRATION }
@Serializable public enum class LocalArrivalFailureV1 {
    UNSUPPORTED_POLICY, EVIDENCE_REQUIRED, EVIDENCE_UNAVAILABLE, SOURCE_MISSING, SOURCE_ALREADY_USED,
    STALE_REVISION, SUPPLIER_UNAVAILABLE, ITEM_UNAVAILABLE, WAREHOUSE_UNAVAILABLE,
    INVALID_QUANTITY, INVALID_UNIT, INVALID_TRACKING, INVALID_SERIAL, SERIAL_REGISTRATION_REQUIRED,
    INVALID_LOT, KNOWN_METADATA_IMMUTABLE, INVALID_DISPOSITION, WRONG_RECIPIENT,
    NO_NEW_OBSERVATION, HYDRATION_CAPACITY_UNAVAILABLE, CAPACITY_EXCEEDED,
}

/** Every selected generation is a real current storage/domain selection, not approval. */
@Serializable
public data class LocalArrivalItemSelectionV1(
    public val skuId: DomainId, public val productId: DomainId, public val baseUnitId: DomainId,
    public val quantityScale: Int, public val catalogRevision: Revision, public val skuRevision: Revision,
    public val storageRevision: Revision, public val stockStorageRevision: Revision?,
) { init { require(quantityScale in 0..6 && storageRevision.value >= 1 && (stockStorageRevision == null || stockStorageRevision.value >= 1)) } }

@Serializable
public data class LocalArrivalWarehouseSelectionV1(public val warehouseId: DomainId, public val storageRevision: Revision) {
    init { require(storageRevision.value >= 1) }
}

/** A custody-unit tag is NOT a serial registration. Serial codes are never normalized. */
@Serializable
public data class LocalArrivalLineV1(
    public val lineId: DomainId, public val sourceId: DomainId, public val custodyUnitId: DomainId,
    public val skuId: DomainId, public val warehouseId: DomainId, public val baseQuantity: Quantity,
    public val lotId: DomainId? = null, public val expiresAt: UtcInstant? = null,
    public val serialId: DomainId? = null, public val serialCode: String? = null,
    public val serialRegistrationRevision: Revision? = null,
    public val disposition: LocalArrivalDispositionV1 = LocalArrivalDispositionV1.UNINSPECTED_HELD,
    public val ownership: LocalArrivalOwnershipV1 = LocalArrivalOwnershipV1.UNRESOLVED,
    public val ownershipReference: String? = null,
) {
    init {
        require(baseQuantity.scaled > 0 && disposition != LocalArrivalDispositionV1.DISPATCHED)
        arrivalTrackingInput(lotId, expiresAt, serialId, serialCode, serialRegistrationRevision)
        require((ownership == LocalArrivalOwnershipV1.UNRESOLVED) == (ownershipReference == null))
        ownershipReference?.let { arrivalText(it, 160) }
    }
}

/** An evidence reference is an attestation reference, NOT a durable local attachment. */
@Serializable
public data class LocalArrivalEvidenceSelectionV1(
    public val observationReference: String,
    public val policyId: DomainId? = null, public val policyRevision: Revision? = null,
    public val attachmentId: DomainId? = null, public val attachmentStorageRevision: Revision? = null,
) {
    init {
        arrivalText(observationReference, 160)
        require((policyId == null) == (policyRevision == null))
        require(policyRevision == null || policyRevision.value >= 1)
        require((attachmentId == null) == (attachmentStorageRevision == null))
        require(attachmentStorageRevision == null || attachmentStorageRevision.value >= 1)
    }
}

@Serializable
public data class LocalRecordUnpricedArrivalV1(
    public val arrivalId: DomainId, public val supplierId: DomainId, public val expectedSupplierRevision: Revision,
    public val supplierDeliveryReference: String? = null, public val localIntakeTag: DomainId? = null,
    /** Documentary only: UNALLOCATED, not reserved, fulfilled or billable. */
    public val documentaryPoId: DomainId? = null, public val discrepancyReference: String? = null,
    public val items: List<LocalArrivalItemSelectionV1>, public val warehouses: List<LocalArrivalWarehouseSelectionV1>,
    public val lines: List<LocalArrivalLineV1>, public val evidence: LocalArrivalEvidenceSelectionV1,
    public val reasonCode: String,
) {
    init {
        require(expectedSupplierRevision.value >= 1)
        require((supplierDeliveryReference == null) != (localIntakeTag == null))
        supplierDeliveryReference?.let(::normalizedPurchaseReference)
        discrepancyReference?.let { arrivalText(it, 160) }
        require(lines.size in 1..LocalUnpricedArrivalProfileV1.MAX_SLICES)
        require(items.size in 1..16 && warehouses.size in 1..16)
        require(items.map { it.skuId }.distinct().size == items.size && items.map { it.skuId }.toSet() == lines.map { it.skuId }.toSet())
        require(warehouses.map { it.warehouseId }.distinct().size == warehouses.size && warehouses.map { it.warehouseId }.toSet() == lines.map { it.warehouseId }.toSet())
        require(lines.map { it.lineId }.distinct().size == lines.size && lines.map { it.sourceId }.distinct().size == lines.size &&
            lines.map { it.custodyUnitId }.distinct().size == lines.size)
        require(lines.mapNotNull { it.serialId }.distinct().size == lines.count { it.serialId != null } &&
            lines.mapNotNull { it.serialCode }.distinct().size == lines.count { it.serialCode != null })
        arrivalReason(reasonCode)
    }
}

/** Required only for a strict partial partition. The remainder retains its original identities. */
@Serializable
public data class LocalArrivalSplitV1(public val sourceId: DomainId, public val custodyUnitId: DomainId)

@Serializable
public data class LocalQualifyUnpricedArrivalV1(
    public val eventId: DomainId, public val arrivalId: DomainId, public val expectedArrivalRevision: Revision,
    public val custodyUnitId: DomainId, public val quantity: Quantity, public val split: LocalArrivalSplitV1? = null,
    public val lotId: DomainId? = null, public val expiresAt: UtcInstant? = null,
    public val serialId: DomainId? = null, public val serialCode: String? = null, public val serialRegistrationRevision: Revision? = null,
    public val inspectionPassed: Boolean = false, public val evidence: LocalArrivalEvidenceSelectionV1, public val reasonCode: String,
) {
    init {
        arrivalTransitionInput(expectedArrivalRevision, quantity, reasonCode)
        // An expiry or qualified ID can complete an already-known lot/code; exact merging is server-owned.
        expiresAt?.let { require(it.value.toEpochMilliseconds() >= 0) }
        serialCode?.let(::requireStockSerialCode)
        require((serialId == null) == (serialRegistrationRevision == null))
        require(serialRegistrationRevision == null || serialRegistrationRevision.value >= 1)
    }
}

@Serializable
public data class LocalRejectUnpricedArrivalV1(
    public val eventId: DomainId, public val arrivalId: DomainId, public val expectedArrivalRevision: Revision,
    public val custodyUnitId: DomainId, public val quantity: Quantity, public val split: LocalArrivalSplitV1? = null,
    public val evidence: LocalArrivalEvidenceSelectionV1, public val reasonCode: String,
) { init { arrivalTransitionInput(expectedArrivalRevision, quantity, reasonCode) } }

@Serializable
public data class LocalDispatchUnpricedArrivalV1(
    public val eventId: DomainId, public val arrivalId: DomainId, public val expectedArrivalRevision: Revision,
    public val custodyUnitId: DomainId, public val quantity: Quantity, public val split: LocalArrivalSplitV1? = null,
    public val recipientSupplierId: DomainId, public val evidence: LocalArrivalEvidenceSelectionV1, public val reasonCode: String,
) { init { arrivalTransitionInput(expectedArrivalRevision, quantity, reasonCode) } }

@Serializable
public data class LocalArrivalResultV1(
    public val action: LocalArrivalActionV1, public val arrivalId: DomainId, public val eventId: DomainId?,
    public val arrivalRevision: Revision?, public val affectedCustodyUnitId: DomainId?, public val failure: LocalArrivalFailureV1? = null,
    public val financialStatus: LocalArrivalFinancialStatusV1 = LocalArrivalFinancialStatusV1.UNPRICED_UNRESOLVED,
) {
    init {
        require((failure == null) == (arrivalRevision != null) && (action == LocalArrivalActionV1.RECORD) == (eventId == null))
        require(failure == null || affectedCustodyUnitId == null)
        if (failure == null) require(requireNotNull(arrivalRevision).value >= 1 && (affectedCustodyUnitId == null) == (action == LocalArrivalActionV1.RECORD))
    }
}

@Serializable
public data class LocalArrivalQueryV1(public val id: DomainId? = null, public val after: DomainId? = null, public val limit: Int = 5) {
    init { require(limit in 1..5 && (id == null || after == null)) }
}

@Serializable
public data class LocalArrivalSourceQueryV1(
    public val kind: LocalArrivalSourceKindV1, public val id: DomainId? = null, public val after: DomainId? = null,
    public val limit: Int = 5, public val supplierId: DomainId? = null,
) { init { require(limit in 1..5 && (id == null || after == null)); require(kind != LocalArrivalSourceKindV1.SERIAL_REGISTRATION || id != null) } }

@Serializable public data class LocalArrivalNamesV1(public val english: String, public val arabic: String) {
    init {
        require(english.isNotBlank() || arabic.isNotBlank())
        require(english.length <= 160 && arabic.length <= 160 && (english + arabic).none { it.code < 0x20 || it.code == 0x7f })
    }
}
@Serializable public data class LocalArrivalSupplierViewV1(public val id: DomainId, public val storageRevision: Revision, public val names: LocalArrivalNamesV1, public val active: Boolean)
@Serializable public data class LocalArrivalWarehouseViewV1(public val selection: LocalArrivalWarehouseSelectionV1, public val names: LocalArrivalNamesV1, public val active: Boolean)
@Serializable public data class LocalArrivalItemViewV1(
    public val selection: LocalArrivalItemSelectionV1, public val names: LocalArrivalNamesV1, public val code: String,
    public val tracking: LocalArrivalTrackingV1?, public val requiresExpiry: Boolean, public val active: Boolean, public val canPurchase: Boolean,
)
@Serializable public data class LocalArrivalSerialViewV1(
    public val serialId: DomainId, public val serialCode: String, public val skuId: DomainId, public val registrationRevision: Revision,
public val validFrom: UtcInstant, public val validUntil: UtcInstant?,
)
@Serializable public data class LocalArrivalPolicyViewV1(
    public val policyId: DomainId, public val revision: Revision, public val attachmentRequiredFor: List<LocalArrivalActionV1>,
    public val validFrom: UtcInstant, public val validUntil: UtcInstant?,
) {
    init {
        require(revision.value >= 1 && validFrom.value.toEpochMilliseconds() >= 0 && (validUntil == null || validUntil > validFrom))
        require(attachmentRequiredFor.distinct().size == attachmentRequiredFor.size && attachmentRequiredFor == attachmentRequiredFor.sortedBy { it.ordinal })
    }
}
@Serializable
public data class LocalArrivalSourcePageV1(
    public val kind: LocalArrivalSourceKindV1, public val suppliers: List<LocalArrivalSupplierViewV1>?,
    public val items: List<LocalArrivalItemViewV1>?, public val warehouses: List<LocalArrivalWarehouseViewV1>?,
    public val serials: List<LocalArrivalSerialViewV1>?, public val nextAfter: DomainId?,
    public val policy: LocalArrivalPolicyViewV1?, public val policyStatus: LocalArrivalPolicyStatusV1,
    public val availability: LocalArrivalAvailabilityV1 = LocalArrivalAvailabilityV1.AVAILABLE,
) {
    init {
        require((policy != null) == (policyStatus == LocalArrivalPolicyStatusV1.AVAILABLE))
        if (availability == LocalArrivalAvailabilityV1.AVAILABLE) require(suppliers != null && items != null && warehouses != null && serials != null)
        else require(suppliers == null && items == null && warehouses == null && serials == null && nextAfter == null && policy == null)
    }
}

@Serializable
public data class LocalArrivalSliceViewV1(
    public val lineId: DomainId, public val rootSourceId: DomainId, public val originalCustodyUnitId: DomainId,
    public val sourceId: DomainId, public val custodyUnitId: DomainId, public val parentCustodyUnitId: DomainId?,
    public val skuId: DomainId, public val warehouseId: DomainId, public val quantity: Quantity,
    public val disposition: LocalArrivalDispositionV1, public val ownership: LocalArrivalOwnershipV1,
    public val tracking: LocalArrivalTrackingV1, public val requiresExpiry: Boolean,
    public val lotId: DomainId?, public val expiresAt: UtcInstant?, public val serialId: DomainId?, public val serialCode: String?,
    public val serialRegistrationRevision: Revision?,
) // No evidence bodies, ownership evidence, financial documents, values or reusable authority.

@Serializable
public data class LocalArrivalHeaderV1(
    public val arrivalId: DomainId, public val supplierId: DomainId, public val supplierDeliveryReference: String?,
    public val localIntakeTag: DomainId?, public val documentaryPoId: DomainId?, public val recordedAt: UtcInstant,
    public val arrivalRevision: Revision, public val heldSlices: Int, public val physicallyClosed: Boolean,
    public val financialStatus: LocalArrivalFinancialStatusV1 = LocalArrivalFinancialStatusV1.UNPRICED_UNRESOLVED,
)
@Serializable
public data class LocalArrivalEventViewV1(public val eventId: DomainId, public val action: LocalArrivalActionV1,
    public val sourceCustodyUnitId: DomainId, public val affectedCustodyUnitId: DomainId, public val quantity: Quantity, public val recordedAt: UtcInstant)
@Serializable
public data class LocalArrivalDetailV1(public val header: LocalArrivalHeaderV1, public val slices: List<LocalArrivalSliceViewV1>,
    public val events: List<LocalArrivalEventViewV1>, public val policy: LocalArrivalPolicyViewV1?, public val policyStatus: LocalArrivalPolicyStatusV1)
@Serializable
public data class LocalArrivalPageV1(public val headers: List<LocalArrivalHeaderV1>?, public val detail: LocalArrivalDetailV1?,
    public val nextAfter: DomainId?, public val availability: LocalArrivalAvailabilityV1 = LocalArrivalAvailabilityV1.AVAILABLE) {
    init { if (availability == LocalArrivalAvailabilityV1.AVAILABLE) require(headers != null) else require(headers == null && detail == null && nextAfter == null) }
}

/** Trusted borrowed-view sources only. A null policy means UNSUPPORTED_POLICY, NEVER optional evidence. */
public interface LocalUnpricedArrivalAuthority {
    public suspend fun supplier(view: CommandReadView, id: DomainId): LocalArrivalSupplierAuthority?
    public suspend fun item(view: CommandReadView, id: DomainId): LocalArrivalItemAuthority?
    public suspend fun warehouse(view: CommandReadView, id: DomainId): LocalArrivalWarehouseAuthority?
    public suspend fun serialRegistration(view: CommandReadView, id: DomainId): LocalStockSerialRegistration?
    public suspend fun keys(view: CommandReadView, kind: LocalArrivalSourceKindV1, after: DomainId?, limit: Int): LocalArrivalSourceKeys
    public suspend fun policy(view: CommandReadView, supplierId: DomainId): LocalArrivalPolicyAuthority?
    public suspend fun attachment(view: CommandReadView, id: DomainId, supplierId: DomainId, action: LocalArrivalActionV1): LocalArrivalAttachmentAuthority?
}

public class LocalArrivalSourceKeys(ids: List<DomainId>, public val nextAfter: DomainId?) { public val ids: List<DomainId> = localStockFrozen(ids) }
public class LocalArrivalSupplierAuthority(public val scope: BranchScope, public val value: LocalArrivalSupplierViewV1,
    public val source: LocalStockSourceStamp, sources: List<LocalStockSourceStamp>) {
    public val sources: List<LocalStockSourceStamp> = arrivalFrozenSources(sources)
    init { require(source in sources && source.key.id == value.id && source.storageRevision == value.storageRevision) }
}
public class LocalArrivalItemAuthority(public val value: LocalStockItemAuthority, public val source: LocalStockSourceStamp,
    public val names: LocalArrivalNamesV1, public val code: String)
public class LocalArrivalWarehouseAuthority(public val value: LocalStockWarehouseAuthority, public val source: LocalStockSourceStamp, public val names: LocalArrivalNamesV1)
public class LocalArrivalPolicyAuthority(public val scope: BranchScope, public val supplierId: DomainId,
    value: LocalArrivalPolicyViewV1, public val source: LocalStockSourceStamp, sources: List<LocalStockSourceStamp>) {
    public val value: LocalArrivalPolicyViewV1 = value.copy(attachmentRequiredFor = localStockFrozen(value.attachmentRequiredFor))
    public val sources: List<LocalStockSourceStamp> = arrivalFrozenSources(sources)
    init { require(source in sources && source.key.id == value.policyId) }
}
public class LocalArrivalAttachmentAuthority(public val scope: BranchScope, public val supplierId: DomainId,
    public val action: LocalArrivalActionV1, public val attachmentId: DomainId, public val source: LocalStockSourceStamp,
    sources: List<LocalStockSourceStamp>) {
    public val sources: List<LocalStockSourceStamp> = arrivalFrozenSources(sources)
    init { require(source in sources && source.key.id == attachmentId) }
}

private fun arrivalFrozenSources(sources: List<LocalStockSourceStamp>): List<LocalStockSourceStamp> {
    require(sources.size in 1..16 && sources.map { it.key }.distinct().size == sources.size && sources.all { it.storageRevision.value >= 1 })
    return localStockFrozen(sources)
}

/** Exact immutable bytes go through the existing saved-intent/submit/lookup lifecycle. */
public object LocalUnpricedArrivalCommandsV1 {
    public fun record(operationId: OperationId, request: LocalRecordUnpricedArrivalV1): MutationRequest =
        MutationRequest(operationId, LocalUnpricedArrivalProfileV1.RECORD, arrivalEncode(request, LocalRecordUnpricedArrivalV1.serializer(), 32_768))
    public fun qualify(operationId: OperationId, request: LocalQualifyUnpricedArrivalV1): MutationRequest =
        MutationRequest(operationId, LocalUnpricedArrivalProfileV1.QUALIFY, arrivalEncode(request, LocalQualifyUnpricedArrivalV1.serializer(), 32_768))
    public fun reject(operationId: OperationId, request: LocalRejectUnpricedArrivalV1): MutationRequest =
        MutationRequest(operationId, LocalUnpricedArrivalProfileV1.REJECT, arrivalEncode(request, LocalRejectUnpricedArrivalV1.serializer(), 32_768))
    public fun dispatch(operationId: OperationId, request: LocalDispatchUnpricedArrivalV1): MutationRequest =
        MutationRequest(operationId, LocalUnpricedArrivalProfileV1.DISPATCH, arrivalEncode(request, LocalDispatchUnpricedArrivalV1.serializer(), 32_768))
    public fun normalize(command: CommandKey, body: StoredBytes): StoredBytes = arrivalNormalize(command, body)
    public fun decodeRecordRequest(body: StoredBytes): LocalRecordUnpricedArrivalV1 = arrivalDecode(body, LocalRecordUnpricedArrivalV1.serializer(), 32_768)
    public fun decodeQualifyRequest(body: StoredBytes): LocalQualifyUnpricedArrivalV1 = arrivalDecode(body, LocalQualifyUnpricedArrivalV1.serializer(), 32_768)
    public fun decodeRejectRequest(body: StoredBytes): LocalRejectUnpricedArrivalV1 = arrivalDecode(body, LocalRejectUnpricedArrivalV1.serializer(), 32_768)
    public fun decodeDispatchRequest(body: StoredBytes): LocalDispatchUnpricedArrivalV1 = arrivalDecode(body, LocalDispatchUnpricedArrivalV1.serializer(), 32_768)
    public fun source(query: LocalArrivalSourceQueryV1): ReadRequest = ReadRequest(LocalUnpricedArrivalProfileV1.SOURCE, arrivalEncode(query, LocalArrivalSourceQueryV1.serializer(), 4_096))
    public fun read(query: LocalArrivalQueryV1): ReadRequest = ReadRequest(LocalUnpricedArrivalProfileV1.READ, arrivalEncode(query, LocalArrivalQueryV1.serializer(), 4_096))
    public fun decodeResult(body: StoredBytes): LocalArrivalResultV1 = arrivalDecode(body, LocalArrivalResultV1.serializer())
    public fun decodeSource(body: StoredBytes): LocalArrivalSourcePageV1 = arrivalDecode(body, LocalArrivalSourcePageV1.serializer())
    public fun decodePage(body: StoredBytes): LocalArrivalPageV1 = arrivalDecode(body, LocalArrivalPageV1.serializer())
}

internal val arrivalJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false; coerceInputValues = false }
internal fun <T> arrivalEncode(value: T, serializer: KSerializer<T>, maximum: Int = 65_536): StoredBytes {
    val bytes = arrivalJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    require(bytes.size <= maximum)
    return StoredBytes(bytes)
}
internal fun <T> arrivalDecode(bytes: StoredBytes, serializer: KSerializer<T>, maximum: Int = 65_536): T {
    require(bytes.size <= maximum)
    return arrivalJson.decodeFromString(serializer, bytes.copyBytes().decodeToString(throwOnInvalidSequence = true))
}
internal fun arrivalReason(value: String) { require(value.length in 1..96 && value.all { it in 'a'..'z' || it in '0'..'9' || it in "._-" }) }
internal fun arrivalText(value: String, maximum: Int) { require(value.isNotBlank() && value.length <= maximum && value.none { it.code < 0x20 || it.code == 0x7f }) }
internal fun arrivalTransitionInput(revision: Revision, quantity: Quantity, reason: String) { require(revision.value >= 1 && quantity.scaled > 0); arrivalReason(reason) }
internal fun arrivalTrackingInput(lotId: DomainId?, expiry: UtcInstant?, serialId: DomainId?, code: String?, revision: Revision?) {
    require(expiry == null || lotId != null && expiry.value.toEpochMilliseconds() >= 0)
    code?.let(::requireStockSerialCode)
    require((serialId == null) == (revision == null) && (serialId == null || code != null))
    require(revision == null || revision.value >= 1)
}
