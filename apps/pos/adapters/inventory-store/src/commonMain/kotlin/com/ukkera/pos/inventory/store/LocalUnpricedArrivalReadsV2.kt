package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

internal fun arrivalReadHandlersV2(stock: LocalStockRepository, authority: LocalUnpricedArrivalAuthorityV2): List<MasterReadHandler> =
    listOf(ArrivalSourceReadV2(stock, authority), ArrivalReadV2(stock, authority))

private class ArrivalSourceReadV2(private val stock: LocalStockRepository, private val authority: LocalUnpricedArrivalAuthorityV2) : MasterReadHandler {
    override val key: ReadKey get() = LocalUnpricedArrivalProfileV2.SOURCE
    override fun normalize(input: StoredBytes): StoredBytes = arrivalEncode(arrivalDecode(input,
        LocalArrivalSourceQueryV2.serializer(), 4_096), LocalArrivalSourceQueryV2.serializer(), 4_096)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = arrivalDecode(normalized, LocalArrivalSourceQueryV2.serializer(), 4_096)
        val bindings = linkedSetOf<Pair<DomainId, Revision>>()
        val documentBindings = linkedSetOf<Pair<DomainId, Revision>>()
        val suppliers = mutableListOf<LocalArrivalSupplierViewV2>()
        val items = mutableListOf<LocalArrivalItemViewV2>()
        val warehouses = mutableListOf<LocalArrivalWarehouseViewV2>()
        val serials = mutableListOf<LocalArrivalSerialViewV1>()
        val purchasing: LocalArrivalPurchasingViewV2?
        val next: DomainId?
        try {
            val page = if (query.id == null) authority.keys(view, query.kind, query.after, query.limit) else null
            val ids = page?.ids ?: listOf(requireNotNull(query.id))
            check(ids.size <= query.limit && ids.distinct().size == ids.size && ids == ids.sortedBy { it.value } &&
                (query.after == null || ids.all { it.value > query.after.value }) &&
                (page?.nextAfter == null || page.nextAfter == ids.lastOrNull()))
            next = page?.nextAfter
            when (query.kind) {
                LocalArrivalSourceKindV1.SUPPLIER -> for (id in ids) {
                    val source = authority.supplier(view, id)
                    check(source != null || page == null)
                    source?.let {
                        check(it.scope == view.scope && it.value.id == id)
                        requireArrivalAuthorityStampsV2(view, it.sources)
                        suppliers += it.value; bindings += id to it.source.storageRevision
                    }
                }
                LocalArrivalSourceKindV1.ITEM -> {
                    val sources = ids.mapNotNull { id -> authority.item(view, id).also { source ->
                        check(source != null || page == null)
                        check(source == null || source.value.scope == view.scope && source.value.skuId == id && source.source.key.id == id)
                    } }
                    val stockVersions = arrivalStockItemVersionsV2(stock, view, sources.map { it.value.skuId })
                    for (source in sources) {
                        val item = source.value
                        requireArrivalAuthorityStampsV2(view, item.sources)
                        val selection = LocalArrivalItemSelectionV2(item.skuId, item.productId, source.productRevision, item.baseUnitId,
                            item.quantityScale, item.catalogRevision, item.skuRevision, source.source.storageRevision, stockVersions.getValue(item.skuId))
                        items += LocalArrivalItemViewV2(selection, source.names, source.code,
                            LocalArrivalTrackingV1.entries.singleOrNull { it.name == item.tracking.name }, item.requiresExpiry, item.active, item.canPurchase)
                        bindings += item.skuId to source.source.storageRevision
                        bindings += item.productId to source.productRevision
                    }
                }
                LocalArrivalSourceKindV1.WAREHOUSE -> for (id in ids) {
                    val source = authority.warehouse(view, id)
                    check(source != null || page == null)
                    source?.let {
                        check(it.value.scope == view.scope && it.value.warehouseId == id && it.source.key.id == id && it.source in it.value.sources)
                        requireArrivalAuthorityStampsV2(view, it.value.sources)
                        warehouses += LocalArrivalWarehouseViewV2(LocalArrivalWarehouseSelectionV2(id, it.source.storageRevision), it.names, it.value.active)
                        bindings += id to it.source.storageRevision
                    }
                }
                LocalArrivalSourceKindV1.SERIAL_REGISTRATION -> for (id in ids) authority.serialRegistration(view, id)?.let {
                    check(it.scope == view.scope && it.serialId == id)
                    requireArrivalAuthorityStampsV2(view, it.sources)
                    serials += LocalArrivalSerialViewV1(id, it.serialCode, it.skuId, it.revision, it.validFrom, it.validUntil)
                    bindings += id to it.revision
                }
            }
            val supplierId = query.supplierId ?: query.id?.takeIf { query.kind == LocalArrivalSourceKindV1.SUPPLIER }
            val source = supplierId?.let { authority.purchasing(view, it, query.action, query.skuIds, query.documentIds) }
            purchasing = source?.let {
                requireArrivalPurchasingReadV2(view, it, supplierId, query.action, query.documentIds, bindings, documentBindings)
                it.value
            }
        } catch (_: ArrivalHydrationCapacityUnavailableV2) {
            return unavailableSourceReadV2(context, query)
        } catch (_: ArrivalHydrationCapacityUnavailable) {
            return unavailableSourceReadV2(context, query)
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            return unavailableSourceReadV2(context, query)
        } catch (_: LocalArrivalEvidenceRejectedV2) {
            throw MasterReadInvalidSelection()
        }
        val selectedSuppliers = localStockFrozen(suppliers); val selectedItems = localStockFrozen(items)
        val selectedWarehouses = localStockFrozen(warehouses); val selectedSerials = localStockFrozen(serials)
        return try {
            arrivalPreparedReadV2(context, bindings.ifEmpty { setOf((query.id ?: context.scope.branchId) to Revision(0)) },
                documentBindings, "arrival.source") { revision ->
                arrivalReadEncodeV2(LocalArrivalSourcePageV2(query.kind, selectedSuppliers, selectedItems, selectedWarehouses,
                    selectedSerials, next, purchasing, revision), LocalArrivalSourcePageV2.serializer()) ?: unavailableSourceV2(query.kind, revision)
            }
        } catch (_: ArrivalHydrationCapacityUnavailableV2) {
            unavailableSourceReadV2(context, query)
        }
    }
}

private class ArrivalReadV2(private val stock: LocalStockRepository, private val authority: LocalUnpricedArrivalAuthorityV2) : MasterReadHandler {
    override val key: ReadKey get() = LocalUnpricedArrivalProfileV2.READ
    override fun normalize(input: StoredBytes): StoredBytes = arrivalEncode(arrivalDecode(input,
        LocalArrivalQueryV1.serializer(), 4_096), LocalArrivalQueryV1.serializer(), 4_096)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = arrivalDecode(normalized, LocalArrivalQueryV1.serializer(), 4_096)
        val bindings = linkedSetOf<Pair<DomainId, Revision>>()
        val documentBindings = linkedSetOf<Pair<DomainId, Revision>>()
        val headers: List<LocalArrivalHeaderV1>
        val detail: LocalArrivalDetailV2?
        val next: DomainId?
        try {
            val reader = stock.unpricedArrivalsV2(view)
            val page = if (query.id == null) view.aggregateKeys(LocalUnpricedArrivalProfileV2.ARRIVAL, query.after, query.limit) else null
            val keys = page?.keys ?: listOf(arrivalKeyV2(LocalUnpricedArrivalProfileV2.ARRIVAL, requireNotNull(query.id)))
            check(keys.size <= query.limit && keys.distinct().size == keys.size && keys.all { it.kind == LocalUnpricedArrivalProfileV2.ARRIVAL } &&
                keys == keys.sortedBy { it.id.value } && (query.after == null || keys.all { it.id.value > query.after.value }) &&
                (page?.nextAfter == null || page.nextAfter == keys.lastOrNull()?.id))
            val selected = keys.mapNotNull { key -> reader.arrival(key.id).also { check(it != null || page == null) } }
            selected.forEach { bindings += it.request.arrivalId to it.state.row.revision }
            headers = localStockFrozen(selected.map(::arrivalHeaderV2)); next = page?.nextAfter
            detail = if (query.id == null || selected.isEmpty()) null else {
                val cut = selected.single()
                val original = cut.original.value.body as ArrivalBodyV2.Original
                val purchasing = LocalArrivalActionV1.entries.filter { it != LocalArrivalActionV1.RECORD }.mapNotNull { action ->
                    authority.purchasing(view, cut.request.supplierId, action, cut.request.items.map { it.skuId },
                        cut.request.evidence.documents.map { it.documentId })?.let { source ->
                        requireArrivalPurchasingReadV2(view, source, cut.request.supplierId, action,
                            cut.request.evidence.documents.map { it.documentId }, bindings, documentBindings)
                        source.value
                    }
                }
                LocalArrivalDetailV2(headers.single(), cut.current.slices.map { slice ->
                    val line = cut.request.lines.single { it.lineId == slice.lineId }
                    val item = original.authority.items.single { it.selection.skuId == line.skuId }
                    LocalArrivalSliceViewV1(slice.lineId, slice.rootSourceId, slice.originalCustodyUnitId, slice.sourceId,
                        slice.custodyUnitId, slice.parentCustodyUnitId, line.skuId, line.warehouseId, slice.quantity, slice.disposition,
                        line.ownership, item.tracking, item.requiresExpiry, slice.lotId, slice.expiresAt, slice.serialId, slice.serialCode,
                        slice.serialRegistrationRevision)
                }, cut.events.map { row ->
                    val event = row.value.body as ArrivalBodyV2.Event
                    val input = arrivalTransitionV2(event.action.commandV2(), StoredBytes(event.request.encodeToByteArray(throwOnInvalidSequence = true)))
                    LocalArrivalEventViewV1(input.eventId, event.action, input.custodyUnitId, event.changedSlice.custodyUnitId, input.quantity, row.value.recordedAt)
                }, purchasing)
            }
        } catch (_: ArrivalHydrationCapacityUnavailableV2) {
            return unavailableArrivalReadV2(context, query)
        } catch (_: ArrivalHydrationCapacityUnavailable) {
            return unavailableArrivalReadV2(context, query)
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            return unavailableArrivalReadV2(context, query)
        } catch (_: LocalArrivalEvidenceRejectedV2) {
            throw MasterReadInvalidSelection()
        }
        return try {
            arrivalPreparedReadV2(context, bindings.ifEmpty { setOf((query.id ?: context.scope.branchId) to Revision(0)) },
                documentBindings, "arrival.read") { revision ->
                arrivalReadEncodeV2(LocalArrivalPageV2(headers, detail, next, revision), LocalArrivalPageV2.serializer()) ?: unavailableArrivalsV2(revision)
            }
        } catch (_: ArrivalHydrationCapacityUnavailableV2) {
            unavailableArrivalReadV2(context, query)
        }
    }
}

private fun arrivalHeaderV2(cut: ArrivalCutV2): LocalArrivalHeaderV1 = LocalArrivalHeaderV1(cut.request.arrivalId,
    cut.request.supplierId, cut.request.supplierDeliveryReference, cut.request.localIntakeTag, cut.request.documentaryPoId,
    cut.original.value.recordedAt, cut.state.row.revision, cut.current.slices.count { arrivalHeldV2(it.disposition) },
    cut.current.slices.none { arrivalHeldV2(it.disposition) })

/** This path obtains inert manifests only. The catalog metadata reader must not call
 * requireFile()/bytes()/slot loading; every projected original and parent/head gets a grant. */
private suspend fun requireArrivalPurchasingReadV2(view: CommandReadView, source: LocalArrivalPurchasingSourceV2,
    supplierId: DomainId?, action: LocalArrivalActionV1, selectedDocuments: List<DomainId>,
    bindings: MutableSet<Pair<DomainId, Revision>>, documentBindings: MutableSet<Pair<DomainId, Revision>>) {
    val value = source.value
    check(value.policy.scope == view.scope && value.catalog.scope == view.scope && value.rules.all { it.action == action } &&
        value.documents.map { it.documentId } == selectedDocuments && value.documents.all { it.scope == view.scope && it.supplierId == supplierId })
    requireArrivalAuthorityStampsV2(view, source.sources)
    bindings += value.policy.policyId to value.policy.publicationRevision
    bindings += value.catalog.catalogId to value.catalog.revision
    source.sources.forEach { bindings += it.key.id to it.storageRevision }
    for (document in value.documents) {
        documentBindings += document.documentId to document.storageRevision
        document.parentDocumentId?.let { parent ->
            val head = requireNotNull(document.parentHead)
            check(head.stamp() in source.sources)
            val parentSources = source.sources.filter { it.key.id == parent }
            check(parentSources.any { it != head.stamp() })
            parentSources.forEach { documentBindings += it.key.id to it.storageRevision }
            documentBindings += head.id to head.storageRevision
        }
    }
}

private fun arrivalPreparedReadV2(context: ReadContext, bindings: Collection<Pair<DomainId, Revision>>,
    documentBindings: Collection<Pair<DomainId, Revision>>, reason: String, projection: (Revision) -> StoredBytes): PreparedRead {
    val requirements = bindings.distinct().flatMap { (id, revision) ->
        listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_QUANTITY_READ).map { action ->
            arrivalReadRequirementV2(context, action, id, revision, reason)
        }
    } + documentBindings.distinct().map { (id, revision) ->
        arrivalReadRequirementV2(context, SecurityAction.PURCHASING_DOCUMENT_READ, id, revision, reason)
    }
    check(requirements.isNotEmpty())
    if (requirements.size > 256) throw ArrivalHydrationCapacityUnavailableV2()
    return object : PreparedRead() {
        override val requirements: List<AuthorizationRequirement> = localStockFrozen(requirements)
        override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
            check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (proof, required) ->
                proof.principalKind == PrincipalKind.LOCAL_EMPLOYEE && proof.isBoundTo(required, context.actorId) &&
                    proof.approvalId == null && proof.approverId == null && proof.approverPolicyRevision == null
            })
            val revision = authorizations.first().policyRevision
            check(authorizations.all { it.actorId == context.actorId && it.policyRevision == revision })
            return projection(revision)
        }
    }
}

private fun arrivalReadRequirementV2(context: ReadContext, action: SecurityAction, id: DomainId, revision: Revision,
    reason: String): AuthorizationRequirement = AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
    ActionBinding(id, revision, context.semanticDigest, reasonCode = reason))

/** Genuine requester revision is inserted only after the complete current proof list. */
private fun unavailableSourceReadV2(context: ReadContext, query: LocalArrivalSourceQueryV2): PreparedRead =
    arrivalPreparedReadV2(context, listOf((query.id ?: context.scope.branchId) to Revision(0)), emptyList(), "arrival.source") {
        unavailableSourceV2(query.kind, it)
    }
private fun unavailableArrivalReadV2(context: ReadContext, query: LocalArrivalQueryV1): PreparedRead =
    arrivalPreparedReadV2(context, listOf((query.id ?: context.scope.branchId) to Revision(0)), emptyList(), "arrival.read", ::unavailableArrivalsV2)
private fun unavailableSourceV2(kind: LocalArrivalSourceKindV1, revision: Revision): StoredBytes = arrivalEncode(
    LocalArrivalSourcePageV2(kind, null, null, null, null, null, null, revision, LocalArrivalAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE),
    LocalArrivalSourcePageV2.serializer())
private fun unavailableArrivalsV2(revision: Revision): StoredBytes = arrivalEncode(
    LocalArrivalPageV2(null, null, null, revision, LocalArrivalAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE), LocalArrivalPageV2.serializer())
private fun <T> arrivalReadEncodeV2(value: T, serializer: KSerializer<T>): StoredBytes? {
    val bytes = arrivalJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    return if (bytes.size <= 65_536) StoredBytes(bytes) else null
}
