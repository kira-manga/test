package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer

internal fun arrivalReadHandlers(stock: LocalStockRepository, authority: LocalUnpricedArrivalAuthority): List<MasterReadHandler> =
    listOf(ArrivalSourceRead(stock, authority), ArrivalRead(stock, authority))

private class ArrivalSourceRead(private val stock: LocalStockRepository, private val authority: LocalUnpricedArrivalAuthority) : MasterReadHandler {
    override val key: ReadKey get() = LocalUnpricedArrivalProfileV1.SOURCE
    override fun normalize(input: StoredBytes): StoredBytes = arrivalEncode(arrivalDecode(input, LocalArrivalSourceQueryV1.serializer(), 4_096), LocalArrivalSourceQueryV1.serializer(), 4_096)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = arrivalDecode(normalized, LocalArrivalSourceQueryV1.serializer(), 4_096)
        val bindings = mutableListOf<Pair<DomainId, Revision>>()
        val result = try {
            val page = if (query.id == null) authority.keys(view, query.kind, query.after, query.limit) else null
            val ids = page?.ids ?: listOf(requireNotNull(query.id))
            check(ids.size <= query.limit && ids.distinct().size == ids.size && ids == ids.sortedBy { it.value } &&
                (query.after == null || ids.all { it.value > query.after.value }) &&
                (page?.nextAfter == null || page.nextAfter == ids.lastOrNull()))
            val suppliers = mutableListOf<LocalArrivalSupplierViewV1>()
            val items = mutableListOf<LocalArrivalItemViewV1>()
            val warehouses = mutableListOf<LocalArrivalWarehouseViewV1>()
            val serials = mutableListOf<LocalArrivalSerialViewV1>()
            when (query.kind) {
                LocalArrivalSourceKindV1.SUPPLIER -> for (id in ids) {
                    val source = authority.supplier(view, id)
                    check(source != null || page == null)
                    source?.let {
                        check(it.scope == view.scope && it.value.id == id && it.sources.isNotEmpty())
                        requireStockAuthorityStamps(view, it.sources)
                        suppliers += it.value; bindings += id to it.value.storageRevision
                    }
                }
                LocalArrivalSourceKindV1.ITEM -> {
                    val sources = ids.mapNotNull { id -> authority.item(view, id).also { source ->
                        check(source != null || page == null)
                        check(source == null || source.value.skuId == id)
                    } }
                    val cut = stock.observe(view, sources.map { it.value.skuId })
                    for (source in sources) {
                        val item = source.value
                        check(item.scope == view.scope && source.source.key.id == item.skuId && source.source in item.sources)
                        requireStockAuthorityStamps(view, item.sources)
                        val selection = LocalArrivalItemSelectionV1(item.skuId, item.productId, item.baseUnitId, item.quantityScale,
                            item.catalogRevision, item.skuRevision, source.source.storageRevision, cut.state.items.getValue(item.skuId)?.row?.revision)
                        items += LocalArrivalItemViewV1(selection, source.names, source.code,
                            LocalArrivalTrackingV1.entries.singleOrNull { it.name == item.tracking.name }, item.requiresExpiry, item.active, item.canPurchase)
                        bindings += item.skuId to source.source.storageRevision
                    }
                }
                LocalArrivalSourceKindV1.WAREHOUSE -> for (id in ids) {
                    val source = authority.warehouse(view, id)
                    check(source != null || page == null)
                    source?.let {
                        check(it.value.scope == view.scope && it.value.warehouseId == id && it.source.key.id == id && it.source in it.value.sources)
                        requireStockAuthorityStamps(view, it.value.sources)
                        warehouses += LocalArrivalWarehouseViewV1(LocalArrivalWarehouseSelectionV1(id, it.source.storageRevision), it.names, it.value.active)
                        bindings += id to it.source.storageRevision
                    }
                }
                LocalArrivalSourceKindV1.SERIAL_REGISTRATION -> for (id in ids) authority.serialRegistration(view, id)?.let {
                    check(it.scope == view.scope && it.serialId == id)
                    requireStockAuthorityStamps(view, it.sources)
                    serials += LocalArrivalSerialViewV1(id, it.serialCode, it.skuId, it.revision, it.validFrom, it.validUntil)
                    bindings += id to it.revision
                }
            }
            val supplierId = query.supplierId ?: query.id?.takeIf { query.kind == LocalArrivalSourceKindV1.SUPPLIER }
            val policy = supplierId?.let { arrivalReadPolicy(authority, view, it) }
            policy?.let { bindings += it.policyId to it.revision }
            arrivalReadEncode(LocalArrivalSourcePageV1(query.kind, suppliers, items, warehouses, serials, page?.nextAfter,
                policy, if (policy == null) LocalArrivalPolicyStatusV1.UNSUPPORTED_POLICY else LocalArrivalPolicyStatusV1.AVAILABLE), LocalArrivalSourcePageV1.serializer())
        } catch (_: ArrivalHydrationCapacityUnavailable) {
            unavailableArrivalSource(query.kind)
        } catch (_: LocalStockHydrationCapacityUnavailable) {
            unavailableArrivalSource(query.kind)
        }
        return arrivalPreparedRead(context, bindings.ifEmpty { listOf((query.id ?: context.scope.branchId) to Revision(0)) }, "arrival.source", result)
    }
}

private class ArrivalRead(private val stock: LocalStockRepository, private val authority: LocalUnpricedArrivalAuthority) : MasterReadHandler {
    override val key: ReadKey get() = LocalUnpricedArrivalProfileV1.READ
    override fun normalize(input: StoredBytes): StoredBytes = arrivalEncode(arrivalDecode(input, LocalArrivalQueryV1.serializer(), 4_096), LocalArrivalQueryV1.serializer(), 4_096)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope && queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = arrivalDecode(normalized, LocalArrivalQueryV1.serializer(), 4_096)
        val bindings = mutableListOf<Pair<DomainId, Revision>>()
        val result = try {
            val reader = stock.unpricedArrivals(view)
            val page = if (query.id == null) view.aggregateKeys(LocalUnpricedArrivalProfileV1.ARRIVAL, query.after, query.limit) else null
            val keys = page?.keys ?: listOf(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, requireNotNull(query.id)))
            check(keys.size <= query.limit && keys.distinct().size == keys.size && keys.all { it.kind == LocalUnpricedArrivalProfileV1.ARRIVAL } &&
                keys == keys.sortedBy { it.id.value } && (query.after == null || keys.all { it.id.value > query.after.value }) &&
                (page?.nextAfter == null || page.nextAfter == keys.lastOrNull()?.id))
            val selected = keys.mapNotNull { key -> reader.arrival(key.id).also { check(it != null || page == null) } }
            selected.forEach { bindings += it.request.arrivalId to it.state.row.revision }
            val headers = selected.map(::arrivalHeader)
            val detail = if (query.id == null || selected.isEmpty()) null else {
                val cut = selected.single()
                val policy = arrivalReadPolicy(authority, view, cut.request.supplierId)
                policy?.let { bindings += it.policyId to it.revision }
                LocalArrivalDetailV1(headers.single(), cut.current.slices.map { slice ->
                    val line = cut.request.lines.single { it.lineId == slice.lineId }
                    val item = (cut.original.value.body as ArrivalBodyV1.Original).authority.items.single { it.selection.skuId == line.skuId }
                    LocalArrivalSliceViewV1(slice.lineId, slice.rootSourceId, slice.originalCustodyUnitId, slice.sourceId, slice.custodyUnitId,
                        slice.parentCustodyUnitId, line.skuId, line.warehouseId, slice.quantity, slice.disposition, line.ownership,
                        item.tracking, item.requiresExpiry, slice.lotId, slice.expiresAt, slice.serialId, slice.serialCode, slice.serialRegistrationRevision)
                }, cut.events.map { row ->
                    val event = row.value.body as ArrivalBodyV1.Event
                    val request = arrivalTransition(event.action.command(), StoredBytes(event.request.encodeToByteArray(throwOnInvalidSequence = true)))
                    LocalArrivalEventViewV1(request.eventId, event.action, request.custodyUnitId, event.changedSlice.custodyUnitId, request.quantity, row.value.recordedAt)
                }, policy, if (policy == null) LocalArrivalPolicyStatusV1.UNSUPPORTED_POLICY else LocalArrivalPolicyStatusV1.AVAILABLE)
            }
            arrivalReadEncode(LocalArrivalPageV1(headers, detail, page?.nextAfter), LocalArrivalPageV1.serializer())
        } catch (_: ArrivalHydrationCapacityUnavailable) { unavailableArrivals() }
        catch (_: LocalStockHydrationCapacityUnavailable) { unavailableArrivals() }
        return arrivalPreparedRead(context, bindings.ifEmpty { listOf((query.id ?: context.scope.branchId) to Revision(0)) }, "arrival.read", result)
    }
}

private fun arrivalHeader(cut: ArrivalCut): LocalArrivalHeaderV1 = LocalArrivalHeaderV1(cut.request.arrivalId, cut.request.supplierId,
    cut.request.supplierDeliveryReference, cut.request.localIntakeTag, cut.request.documentaryPoId, cut.original.value.recordedAt,
    cut.state.row.revision, cut.current.slices.count { arrivalHeld(it.disposition) }, cut.current.slices.none { arrivalHeld(it.disposition) })

private suspend fun arrivalReadPolicy(authority: LocalUnpricedArrivalAuthority, view: CommandReadView, supplierId: DomainId): LocalArrivalPolicyViewV1? {
    val policy = authority.policy(view, supplierId) ?: return null
    check(policy.scope == view.scope && policy.supplierId == supplierId && policy.sources.isNotEmpty())
    requireStockAuthorityStamps(view, policy.sources)
    return policy.value
}

private fun arrivalPreparedRead(context: ReadContext, bindings: List<Pair<DomainId, Revision>>, reason: String, result: StoredBytes): PreparedRead = object : PreparedRead() {
    override val requirements: List<AuthorizationRequirement> = bindings.distinct().flatMap { (id, revision) ->
        listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_QUANTITY_READ).map { action -> AuthorizationRequirement(
            Permission(action, PermissionVerb.PERFORM), context.scope, ActionBinding(id, revision, context.semanticDigest, reasonCode = reason)) }
    }
    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
        check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (authorized, required) ->
            authorized.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorized.isBoundTo(required, context.actorId) })
        return result
    }
}

/** This is owned OUTPUT admission, not a catch for corrupted stored rows or unknown producers. */
private fun <T> arrivalReadEncode(value: T, serializer: KSerializer<T>): StoredBytes {
    val bytes = arrivalJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    if (bytes.size > 65_536) throw ArrivalHydrationCapacityUnavailable()
    return StoredBytes(bytes)
}
private fun unavailableArrivals(): StoredBytes = arrivalEncode(LocalArrivalPageV1(null, null, null,
    LocalArrivalAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE), LocalArrivalPageV1.serializer())
private fun unavailableArrivalSource(kind: LocalArrivalSourceKindV1): StoredBytes = arrivalEncode(LocalArrivalSourcePageV1(kind,
    null, null, null, null, null, null, LocalArrivalPolicyStatusV1.UNSUPPORTED_POLICY,
    LocalArrivalAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE), LocalArrivalSourcePageV1.serializer())
