package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal fun arrivalRequirementV2(scope: BranchScope, arrivalId: DomainId, revision: Revision, digest: PayloadDigest,
    operationId: OperationId, reason: String): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), scope,
    ActionBinding(arrivalId, revision, digest, operationId, reason))

internal fun arrivalRejectV2(action: LocalArrivalActionV1, id: DomainId, eventId: DomainId?, failure: LocalArrivalFailureV2): ProposedOutcome.Reject =
    ProposedOutcome.Reject("arrival.${failure.name.lowercase()}", arrivalEncode(LocalArrivalResultV2(action, id, eventId, null, null, failure), LocalArrivalResultV2.serializer()))

internal fun arrivalEvidenceSelectionResourcesV2(evidence: LocalArrivalEvidenceSelectionV2): Set<RecoveryResourceKey> = buildSet {
    fun add(kind: RecoveryResourceKind, id: DomainId) { add(RecoveryResourceKey(kind, id)) }
    listOf(evidence.policy.policyId, evidence.policy.publicationId, evidence.catalog.catalogId, evidence.catalog.publicationId,
        evidence.ruleId, evidence.retention.classId).forEach { add(RecoveryResourceKind.AUTHORIZATION, it) }
    for (source in listOf(evidence.policy.activePointer, evidence.policy.publication, evidence.catalog.activePointer, evidence.catalog.publication)) {
        add(RecoveryResourceKind.AUTHORIZATION, source.id); add(RecoveryResourceKind.PURCHASE_CUSTODY, source.id)
    }
    for (document in evidence.documents) {
        listOfNotNull(document.documentId, document.parentDocumentId, document.parentHead?.id).forEach { add(RecoveryResourceKind.PURCHASE_CUSTODY, it) }
        add(RecoveryResourceKind.AUTHORIZATION, document.documentId)
    }
}

internal fun arrivalRequestResourcesV2(scope: BranchScope, request: LocalRecordUnpricedArrivalV2): Set<RecoveryResourceKey> = buildSet {
    val delivery = arrivalDeliveryClaimV2(scope, request)
    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, localStockId("arrival-delivery-resource", "${delivery.namespace}/${delivery.key}")))
    listOfNotNull(request.arrivalId, request.supplierId, request.localIntakeTag).forEach { add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
    add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, request.supplierId))
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.supplierId))
    request.documentaryPoId?.let { add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, it)) }
    addAll(arrivalEvidenceSelectionResourcesV2(request.evidence))
    for (line in request.lines) {
        listOf(line.lineId, line.sourceId, line.custodyUnitId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
        listOfNotNull(line.skuId, line.warehouseId, line.lotId, line.serialId, line.serialCode?.let { localStockId("serial-code", it) }).forEach {
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
        listOf(line.skuId, line.warehouseId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it)) }
    }
    request.items.forEach { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.productId)) }
}

internal fun arrivalTransitionResourcesV2(request: ArrivalTransitionInputV2): Set<RecoveryResourceKey> = buildSet {
    listOfNotNull(request.arrivalId, request.eventId, request.custodyUnitId, request.split?.sourceId, request.split?.custodyUnitId).forEach {
        add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
    listOfNotNull(request.item.skuId, request.warehouse.warehouseId, request.lotId, request.serialId,
        request.serialCode?.let { localStockId("serial-code", it) }).forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
    listOf(request.item.skuId, request.item.productId, request.warehouse.warehouseId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it)) }
    request.recipientSupplierId?.let { add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, it)) }
    addAll(arrivalEvidenceSelectionResourcesV2(request.evidence))
}

/** Shared source-coordinate projection. Generation zero is kept as a genuine coordinate. */
internal fun arrivalAuthoritySourceResourcesV2(sources: List<LocalStockSourceStamp>, selection: LocalArrivalEvidenceSelectionV2,
    registrations: List<LocalStockSerialRegistration>): Set<RecoveryResourceKey> = buildSet {
    check(sources.groupBy { it.key }.values.all { it.distinct().size == 1 })
    for (source in sources.distinct()) {
        add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, source.key.id))
        add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, source.key.id))
    }
    addAll(arrivalEvidenceSelectionResourcesV2(selection))
    registrations.forEach { addAll(serialRegistrationResources(it)) }
}

/** Retained history resources come from complete immutable authority facts, not a saved certificate. */
internal fun arrivalAuthorityResourcesV2(authority: ArrivalAuthorityWireV2): Set<RecoveryResourceKey> {
    val sources = authority.supplierSources + authority.items.flatMap { it.sources } + authority.warehouses.flatMap { it.sources } +
        authority.serialRegistrations.flatMap { it.sources }
    return arrivalAuthoritySourceResourcesV2(sources.map { it.restore() } + authority.purchasing.sources.map { it.stamp() },
        authority.purchasing.selection, authority.serialRegistrations.map { it.restore() }) +
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, authority.purchasing.documentaryUse.id)
}

internal fun arrivalRowResourcesV2(scope: BranchScope, key: AggregateKey, value: ArrivalRecordV2?): Set<RecoveryResourceKey> = buildSet {
    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, key.id))
    if (value == null) return@buildSet
    check(value.scope == scope && key == arrivalBodyKeyV2(value.body))
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, value.producer.actorId))
    when (val body = value.body) {
        is ArrivalBodyV2.Original -> addAll(arrivalRequestResourcesV2(scope, body.request))
        is ArrivalBodyV2.SourceIdentity -> listOf(body.arrivalId, body.lineId, body.rootSourceId, body.sourceId, body.custodyUnitId).forEach {
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
        is ArrivalBodyV2.SerialGuard -> {
            listOfNotNull(body.skuId, body.serialId, localStockId("serial-code", body.serialCode)).forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, body.arrivalId))
        }
        is ArrivalBodyV2.LotGuard -> listOf(body.skuId, body.lotId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it)) }
        else -> Unit
    }
}

internal fun arrivalProducerResourcesV2(evidence: RetainedCommandEvidence): Set<RecoveryResourceKey> = buildSet {
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, evidence.originalActorId))
    evidence.aggregateWrites.filter { it.key.kind == StockReadEvidenceV1.KIND }.forEach {
        add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it.key.id))
    }
    for (write in evidence.aggregateWrites.filter { it.key.kind in arrivalKindsV2 }) when (val body = arrivalDecodeRecordV2(write.payload).body) {
        is ArrivalBodyV2.Original -> addAll(arrivalAuthorityResourcesV2(body.authority))
        is ArrivalBodyV2.Event -> addAll(arrivalAuthorityResourcesV2(body.authority))
        else -> Unit
    }
}

/** Same exact union for preparation and original-native comparison. Source-reader resources
 * must be recomputed on the genuine current/BEFORE reader; no resource certificate is persisted. */
internal fun arrivalMutationResourcesV2(actorId: DomainId, scope: BranchScope, request: LocalRecordUnpricedArrivalV2?,
    transition: ArrivalTransitionInputV2?, originalRequest: LocalRecordUnpricedArrivalV2?,
    custodyResources: Set<RecoveryResourceKey>, stockResources: Set<RecoveryResourceKey>,
    authorityResources: Set<RecoveryResourceKey>, sourceReaderResources: Set<RecoveryResourceKey>,
    operationId: OperationId): Set<RecoveryResourceKey> = buildSet {
    check((request == null) != (transition == null))
    request?.let { addAll(arrivalRequestResourcesV2(scope, it)) }
    transition?.let { addAll(arrivalTransitionResourcesV2(it)) }
    originalRequest?.let { addAll(arrivalRequestResourcesV2(scope, it)) }
    addAll(custodyResources); addAll(stockResources); addAll(authorityResources); addAll(sourceReaderResources)
    add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actorId))
    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, DomainId(operationId.value)))
}
