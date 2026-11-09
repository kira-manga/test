package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

internal class SupplierSettlementSourceRead(private val stock: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalSupplierSettlementProfile.READ_SOURCE
    override fun normalize(input: StoredBytes): StoredBytes = LocalSupplierSettlementCodec.source(purchasingDecode(input, LocalSupplierSettlementSourceQueryV1.serializer()))
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        val query = purchasingDecode(normalized, LocalSupplierSettlementSourceQueryV1.serializer())
        val supplier = supplierSettlementSupplier(view, query.supplierId)
        val reader = PurchasingReader(view)
        var cash: LocalSupplierCashCut? = null
        var invoiceRevision: Revision? = null
        val projection = try {
            cash = query.sessionId?.let { LocalSupplierCashStorage.current(view, it) }
            invoiceRevision = query.invoiceId?.let { reader.row(LocalPurchasingProfile.INVOICE, it)?.row?.revision }
            val cut = query.invoiceId?.let { reader.invoice(it) }
            if (cut != null) requirePurchasingCrossAccounts(reader, cut, stock, view)
            val advance = query.advanceId?.let { reader.advance(it) }
            val invoiceSupplier = cut?.let { purchasingInvoiceClaim(it.invoice).supplierId }
            val source = when {
                supplier == null || !supplier.value.active -> null
                query.invoiceId == null -> LocalSupplierCashSource.Advance(query.supplierId, supplier.row.revision)
                cut == null || cut.payable == null || invoiceSupplier != query.supplierId || !purchasingCaptureSettlementEligible(cut) -> null
                else -> LocalSupplierCashSource.Invoice(query.supplierId, query.invoiceId, supplier.row.revision, cut.invoice.row.revision,
                    cut.progress.row.revision, cut.payable.row.revision, cut.credits.index?.row?.revision, cut.settlements.index?.row?.revision,
                    checkNotNull(purchasingDue(cut)).remainingDue, cut.settlements.reservedTotal)
            }
            val availableCash = cash?.takeIf { it.session.status == CashSessionStatus.OPEN && it.session.custodianId == context.actorId }?.session?.availableCash
            val due = cut?.let(::purchasingDue)
            val unavailable = when {
                source == null -> "purchasing.supplier_cash_source_unavailable"
                query.sessionId != null && availableCash == null -> "purchasing.supplier_cash_current_custody_required"
                query.advanceId != null && (advance == null || (advance.advance.value.body as PurchasingBody.SupplierAdvance).value.supplierId != query.supplierId) -> "purchasing.advance_required"
                else -> null
            }
            LocalSupplierSettlementSourceV1(query = query, source = source, sessionRevision = cash?.sessionRow?.revision,
                drawerRevision = cash?.drawerRevision, custodianRevision = cash?.custodianRevision, workRevision = cash?.workRevision,
                availableCash = availableCash, designatedPostingBranch = view.scope.branchId, allocatedPayment = due?.allocatedPayment,
                appliedCredit = due?.appliedCredit, reservedCash = cut?.takeIf { due != null }?.settlements?.reservedTotal,
                availableToAllocate = cut?.let(::purchasingAvailableToAllocate), advanceRevision = advance?.advance?.row?.revision,
                advanceAllocationRevision = advance?.index?.row?.revision, unappliedAdvance = advance?.unapplied, unavailableCode = unavailable)
        } catch (_: PurchasingHydrationCapacityUnavailable) { unavailableSupplierSource(query, "purchasing.capacity_unavailable") }
          catch (_: PurchasingInvoiceCaptureUnqualified) { unavailableSupplierSource(query, "purchasing.invoice_capture_unqualified") }
          catch (_: LocalSupplierCashSourceUnavailable) { unavailableSupplierSource(query, "purchasing.supplier_cash_source_unavailable") }
        return supplierPreparedRead(context, listOf(query.supplierId to (supplier?.row?.revision ?: Revision(0))) +
            listOfNotNull(query.invoiceId?.let { it to (invoiceRevision ?: (projection.source as? LocalSupplierCashSource.Invoice)?.expectedInvoiceRevision ?: Revision(0)) }),
            cash?.let { it.session.id to (it.sessionRow.revision to it.session.custodianId) }, query.sessionId,
            { purchasingEncode(projection, LocalSupplierSettlementSourceV1.serializer()) })
    }
}

internal class SupplierAdvanceRead : MasterReadHandler {
    override val key: ReadKey = LocalSupplierSettlementProfile.READ_ADVANCES
    override fun normalize(input: StoredBytes): StoredBytes = LocalSupplierSettlementCodec.advances(purchasingDecode(input, LocalSupplierAdvanceQueryV1.serializer()))
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        val query = purchasingDecode(normalized, LocalSupplierAdvanceQueryV1.serializer())
        val reader = PurchasingReader(view)
        val page = if (query.id == null) view.aggregateKeys(LocalSupplierSettlementProfile.ADVANCE, query.after, query.limit) else null
        val keys = page?.keys ?: listOf(purchasingKey(LocalSupplierSettlementProfile.ADVANCE, checkNotNull(query.id)))
        check(keys.size <= query.limit && keys.distinct().size == keys.size)
        val selected = keys.mapNotNull { key -> reader.row(key.kind, key.id).also { check(it != null || page == null) } }
        val bindings = selected.map { it.row.key.id to it.row.revision }.ifEmpty { listOf((query.id ?: query.supplierId ?: view.scope.branchId) to Revision(0)) }
        val projected = try {
            val advances = selected.mapNotNull { row ->
                val cut = reader.advance(row.row.key.id) ?: error("Purchasing selected advance unavailable")
                val value = (cut.advance.value.body as PurchasingBody.SupplierAdvance).value
                if (query.supplierId != null && query.supplierId != value.supplierId) null else LocalSupplierAdvanceViewV1(value.id, value.supplierId,
                    value.paymentId, cut.advance.row.revision, cut.index.row.revision, value.amount, cut.allocated, cut.unapplied,
                    cut.advance.value.recordedAt, (cut.index.value.body as PurchasingBody.AdvanceAllocations).allocationIds)
            }
            LocalSupplierAdvancePageV1(advances, page?.nextAfter)
        } catch (_: PurchasingHydrationCapacityUnavailable) { LocalSupplierAdvancePageV1(null, null, "purchasing.capacity_unavailable") }
          catch (_: PurchasingInvoiceCaptureUnqualified) { LocalSupplierAdvancePageV1(null, null, "purchasing.invoice_capture_unqualified") }
        return supplierPreparedRead(context, bindings, null, null, { purchasingEncode(projected, LocalSupplierAdvancePageV1.serializer()) }, query.export)
    }
}

internal class SupplierCashRead : MasterReadHandler {
    override val key: ReadKey = LocalSupplierCashProfile.READ
    override fun normalize(input: StoredBytes): StoredBytes = LocalSupplierSettlementCodec.cash(purchasingDecode(input, LocalSupplierCashQueryV1.serializer()))
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        val query = purchasingDecode(normalized, LocalSupplierCashQueryV1.serializer())
        val slot = LocalSupplierCashFacts.slot(view, query.recordingOperationId)
        val binding = slot?.let { supplierCashSessionBinding(view, it) }
        val result = if (slot == null) unavailableSupplierCash(query.recordingOperationId, "purchasing.supplier_cash_unavailable") else try {
            supplierCashProjection(view, slot)
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            unavailableSupplierCash(query.recordingOperationId, "purchasing.capacity_unavailable")
        } catch (_: PurchasingInvoiceCaptureUnqualified) {
            unavailableSupplierCash(query.recordingOperationId, "purchasing.invoice_capture_unqualified")
        }
        val targetId = slot?.value?.target?.invoiceId ?: slot?.value?.target?.supplierId ?: DomainId(query.recordingOperationId.value)
        return supplierPreparedRead(context, listOf(targetId to (slot?.row?.revision ?: Revision(0))), binding,
            slot?.value?.target?.sessionId, { purchasingEncode(result, LocalSupplierCashViewV1.serializer()) },
            ownerIfMissing = slot?.value?.originalActorId)
    }
}

/** Discover the caller's retained original IDs after client/root loss, without guessed selectors. */
internal class SupplierOwnCashRead : MasterReadHandler {
    override val key: ReadKey = LocalSupplierSettlementProfile.READ_OWN_CASH
    override fun normalize(input: StoredBytes): StoredBytes = LocalSupplierSettlementCodec.ownCash(
        purchasingDecode(input, LocalSupplierOwnCashQueryV1.serializer()))
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        val query = purchasingDecode(normalized, LocalSupplierOwnCashQueryV1.serializer())
        val page = view.aggregateKeys(LocalSupplierCashProfile.SLOT, query.after, query.limit)
        check(page.keys.size <= query.limit && page.keys.distinct().size == page.keys.size && page.keys.all { it.kind == LocalSupplierCashProfile.SLOT })
        val own = page.keys.map { key ->
            LocalSupplierCashFacts.slot(view, OperationId(key.id.value)) ?: error("Purchasing supplier slot page lost its member")
        }.filter { it.value.originalActorId == context.actorId }
        val cashBindings = own.map { supplierCashSessionBinding(view, it) }.distinct()
        val bindings = own.map { (it.value.target.invoiceId ?: it.value.target.supplierId) to it.row.revision }
            .ifEmpty { listOf(view.scope.branchId to Revision(0)) }
        val result = try {
            LocalSupplierOwnCashPageV1(own.map { supplierCashProjection(view, it) }, page.nextAfter)
        } catch (_: PurchasingHydrationCapacityUnavailable) {
            LocalSupplierOwnCashPageV1(null, null, "purchasing.capacity_unavailable")
        } catch (_: PurchasingInvoiceCaptureUnqualified) {
            LocalSupplierOwnCashPageV1(null, null, "purchasing.invoice_capture_unqualified")
        }
        val base = supplierPreparedRead(context, bindings, null, null, { purchasingEncode(result, LocalSupplierOwnCashPageV1.serializer()) })
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = base.requirements +
                (cashBindings.map { (id, row) -> Triple(id, row.first, row.second) }.ifEmpty {
                    listOf(Triple(view.scope.branchId, Revision(0), context.actorId))
                }).map { (id, revision, owner) -> AuthorizationRequirement(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM),
                    context.scope, ActionBinding(id, revision, context.semanticDigest, reasonCode = "purchasing.supplier_cash_own_read"),
                    owner = OwnerConstraint.OwnerPrivate(owner)) }
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (auth, requirement) ->
                    auth.principalKind == PrincipalKind.LOCAL_EMPLOYEE && auth.isBoundTo(requirement, context.actorId) })
                return base.project(authorizations.take(base.requirements.size), observedAt)
            }
        }
    }
}

/** Recovery reads observe the actual immutable owner, not only currently active work/custody. */
private suspend fun supplierCashSessionBinding(view: CommandReadView, slot: LocalSupplierCashStoredSlot): Pair<DomainId, Pair<Revision, DomainId>> {
    val id = slot.value.target.sessionId
    val row = view.aggregate(LocalSupplierCashStorage.sessionKey(id)) ?: error("Purchasing supplier slot lost its cash session")
    val session = LocalSupplierCashStorage.decodeSession(view.scope, row)
    check(session.id == id && session.custodianId == slot.value.originalActorId)
    return id to (row.revision to session.custodianId)
}

private suspend fun supplierCashProjection(view: CommandReadView, slot: LocalSupplierCashStoredSlot): LocalSupplierCashViewV1 {
    requirePurchasingProducer(slot.start)
    val reader = PurchasingReader(view)
    reader.supplierAttempt(slot.value.target.settlementId) ?: error("Purchasing supplier slot lost its attempt")
    val original = supplierCashFact(view, slot.value.target.recordingOperationId, LocalSupplierCashPhase.ORIGINAL, reader)
    val resolution = supplierCashFact(view, slot.value.target.resolutionOperationId, LocalSupplierCashPhase.RESOLUTION, reader)
    val state = when {
        resolution?.application?.result is LocalSupplierCashResult.Applied -> "RESOLUTION_APPLIED"
        resolution != null -> "RESOLUTION_AWAITING_APPLICATION"
        original?.application?.result is LocalSupplierCashResult.Applied -> (original.application.result as LocalSupplierCashResult.Applied).action.name
        original != null -> "RAW_AWAITING_APPLICATION"
        else -> "AWAITING_ATTESTATION"
    }
    return LocalSupplierCashViewV1(slot.value.target.recordingOperationId, slot.value.target,
        supplierRequirementHex(slot.value.requirement().canonicalBytes), original?.value?.normalizedBody,
        resolution?.value?.normalizedBody, original?.application?.result, resolution?.application?.result,
        slot.value.domain.amount, state)
}

private fun unavailableSupplierCash(operationId: OperationId, code: String): LocalSupplierCashViewV1 =
    LocalSupplierCashViewV1(operationId, null, null, null, null, null, null, null, null, code)

private fun supplierRequirementHex(bytes: StoredBytes): String = bytes.copyBytes().joinToString("") { value ->
    (value.toInt() and 255).toString(16).padStart(2, '0')
}

private fun unavailableSupplierSource(query: LocalSupplierSettlementSourceQueryV1, code: String): LocalSupplierSettlementSourceV1 =
    LocalSupplierSettlementSourceV1(query, null, null, null, null, null, null, null, null, null, null, null, null, null, null, code)

/** Costs and physical evidence are never revealed through an unrestricted purchasing read. */
private fun supplierPreparedRead(context: ReadContext, bindings: List<Pair<DomainId, Revision>>,
    cash: Pair<DomainId, Pair<Revision, DomainId>>?, requestedSession: DomainId?, projection: () -> StoredBytes,
    export: Boolean = false, ownerIfMissing: DomainId? = null): PreparedRead = object : PreparedRead() {
    override val requirements: List<AuthorizationRequirement> = bindings.distinct().flatMap { (id, revision) ->
        listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_COST_READ).flatMap { action ->
            (listOf(PermissionVerb.PERFORM) + if (export) listOf(PermissionVerb.EXPORT) else emptyList()).map { verb ->
                AuthorizationRequirement(Permission(action, verb), context.scope,
                    ActionBinding(id, revision, context.semanticDigest, reasonCode = "purchasing.supplier_settlement_read"))
            }
        }
    } + listOfNotNull(requestedSession?.let { sessionId -> AuthorizationRequirement(Permission(SecurityAction.CASH_READ, PermissionVerb.PERFORM),
        context.scope, ActionBinding(sessionId, cash?.second?.first ?: Revision(0), context.semanticDigest, reasonCode = "purchasing.supplier_cash_read"),
        owner = OwnerConstraint.OwnerPrivate(cash?.second?.second ?: ownerIfMissing ?: context.actorId)) })
    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
        check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (auth, requirement) ->
            auth.principalKind == PrincipalKind.LOCAL_EMPLOYEE && auth.isBoundTo(requirement, context.actorId)
        })
        return projection()
    }
}
