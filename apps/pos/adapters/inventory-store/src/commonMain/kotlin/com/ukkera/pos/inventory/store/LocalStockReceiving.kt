package com.ukkera.pos.inventory.store

import com.ukkera.pos.catalog.StockTracking
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.PreparedMutation
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventoryPlanner
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockReceipt
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.UtcInstant

/** Physical receiving only. Supplier invoices and money NEVER acquire another unit here. */
internal class StockReceiptHandler(
    private val repository: LocalStockRepository,
    private val authority: LocalStockAuthorityReader,
    private val opening: Boolean,
    serial: Boolean = false,
    private val purchaseOrder: Boolean = false,
) : StockCommandHandler(
    if (purchaseOrder) LocalStockStoreProfile.RECEIVE_PO
        else if (opening) { if (serial) LocalStockStoreProfile.OPENING_SERIAL else LocalStockStoreProfile.OPENING }
        else if (serial) LocalStockStoreProfile.RECEIVE_SERIAL else LocalStockStoreProfile.RECEIVE,
    LocalStockStoreProfile.inventoryKinds + setOf(LocalStockStoreProfile.RECEIPT_DOCUMENT, LocalStockStoreProfile.ALLOWANCE),
    setOf(LocalStockStoreProfile.SOURCE_CLAIM, LocalStockStoreProfile.ITEM_CLAIM, LocalStockStoreProfile.DOCUMENT_CLAIM,
        LocalStockStoreProfile.ALLOWANCE_CLAIM, LocalStockStoreProfile.RECEIPT_LINE_CLAIM,
        LocalStockStoreProfile.SERIAL_ID_CLAIM, LocalStockStoreProfile.SERIAL_CODE_CLAIM),
) {
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val input = if (purchaseOrder) StockReceiptPreparationInput.purchase(LocalStockCommandCodec.poReceipt(normalized))
            else StockReceiptPreparationInput.legacy(LocalStockCommandCodec.receipt(normalized))
        return try { prepareReceipt(input, view, context) }
        catch (capacity: LocalStockHydrationCapacityUnavailable) {
            if (!purchaseOrder) throw capacity
            // Capacity is not a corrupt source or a missing price; authorize the diagnostic before releasing it.
            preparedStock(context, input.receiptId, input.expectedBasisRevision, input.reasonCode,
                poReceiptSelectionResources(input), poReceiptBarriers) { rejectStock(input.receiptId, LocalStockFailure.BOUND_EXCEEDED) }
        }
    }

    private suspend fun prepareReceipt(request: StockReceiptPreparationInput, view: CommandReadView, context: CommandContext): PreparedMutation {
        val purchaseSource = if (purchaseOrder) repository.approvedPurchaseOrder(view, request.basisId) else null
        val priorReceipt = stockDocument(repository, view, LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId, StockReceiptWire.serializer())
        val cut = repository.observe(view, request.items.map { it.skuId })
        // Separate unpriced custody is never a free new stock origin, even after its dispatch.
        // These are preparation-only interlocks over the same borrowed stock transaction.
        val arrivalGuards = repository.unpricedArrivalReceiptGuards(view, request.lines.map {
            ArrivalReceiptIdentity(it.skuId, it.lotId, it.serialId, it.serialCode, it.sourceId, it.lineId)
        })
        val basis = if (opening) authority.openingBasis(view, request.basisId) else authority.receivingBasis(view, request.basisId)
        localStockCheck(basis == null || basis.scope == context.scope && basis.basisId == request.basisId, LocalStockFailure.STORAGE_INCONSISTENT)
        val items = request.items.associate { it.skuId to authority.item(view, it.skuId) }
        for ((id, item) in items) localStockCheck(item == null || item.scope == context.scope && item.skuId == id, LocalStockFailure.STORAGE_INCONSISTENT)
        val warehouses = request.lines.map { it.warehouseId }.distinct().associateWith { authority.warehouse(view, it) }
        for ((id, warehouse) in warehouses) localStockCheck(warehouse == null || warehouse.scope == context.scope && warehouse.warehouseId == id,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val serialized = request.lines.filter { it.serialId != null && it.disposition != LocalReceiptDisposition.REFUSED }
        val serials = serialized.associate { requireNotNull(it.serialId) to stockSerial(repository, view, requireNotNull(it.serialId)) }
        val serialCodes = serialized.associate { requireNotNull(it.serialId) to view.claim(LocalStockStoreProfile.serialCodeClaim(requireNotNull(it.serialCode))) }
        val registrations = serialized.associate { requireNotNull(it.serialId) to authority.serialRegistration(view, requireNotNull(it.serialId)) }
        registrations.forEach { (id, registration) -> localStockCheck(registration == null || registration.scope == context.scope &&
            registration.serialId == id, LocalStockFailure.STORAGE_INCONSISTENT) }
        val rawSources = basis?.sources.orEmpty() + items.values.filterNotNull().flatMap { it.sources } +
            warehouses.values.filterNotNull().flatMap { it.sources } + registrations.values.filterNotNull().flatMap { it.sources } +
            listOfNotNull(purchaseSource?.source)
        val sources = if (rawSources.isEmpty()) emptyList() else requireStockAuthorityStamps(view, rawSources)
        val allocations = request.lines.map { it.allocationId }.distinct().associateWith { id -> stockAllowance(repository, view, id) }
        val reusedSource = request.lines.any { line -> view.claim(LocalStockStoreProfile.sourceClaim(line.sourceId)) != null ||
            view.claim(LocalStockStoreProfile.receiptLineClaim(line.sourceId)) != null }
        val resources = repository.resources(cut) + sourceResources(sources) + arrivalGuards.resources + buildSet {
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptId))
            add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, request.basisId))
            request.lines.forEach {
                add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, it.allocationId))
                add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it.sourceId))
                if (purchaseOrder) add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it.lineId))
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.sourceId))
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.skuId))
                add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it.warehouseId))
                it.lotId?.let { id -> add(RecoveryResourceKey(RecoveryResourceKind.STOCK, id)) }
                it.serialId?.let { id -> add(RecoveryResourceKey(RecoveryResourceKind.STOCK, id)) }
                it.serialCode?.let { code -> add(RecoveryResourceKey(RecoveryResourceKind.STOCK, localStockId("serial-code", code))) }
                if (it.disposition != LocalReceiptDisposition.REFUSED &&
                    (it.disposition != LocalReceiptDisposition.REJECTED_HELD || basis?.supplierId != null)) {
                    val position = receiptPosition(it, basis?.supplierId).inScope(context.scope)
                    add(RecoveryResourceKey(RecoveryResourceKind.STOCK, LocalStockStoreProfile.positionKey(position).id))
                    if (position.pool.ownership == StockOwnership.CompanyOwned) {
                        add(RecoveryResourceKey(RecoveryResourceKind.STOCK, LocalStockStoreProfile.poolKey(position.pool).id))
                    }
                }
            }
            basis?.let {
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.approvedBy))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.policyId))
                it.supplierId?.let { id ->
                    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, id))
                    if (purchaseOrder) add(RecoveryResourceKey(RecoveryResourceKind.SUPPLIER_SETTLEMENT, id))
                }
            }
            registrations.values.filterNotNull().forEach { addAll(serialRegistrationResources(it)) }
        }
        return preparedStock(context, request.receiptId, request.expectedBasisRevision, request.reasonCode, resources,
            (if (purchaseOrder) poReceiptBarriers else setOf(RecoveryResourceKind.STOCK)) + arrivalBarriers) { now ->
            localStockCheck(priorReceipt == null && !reusedSource, LocalStockFailure.SOURCE_ALREADY_USED)
            localStockCheck(serials.values.all { it == null } && serialCodes.values.all { it == null }, LocalStockFailure.SOURCE_ALREADY_USED)
            localStockCheck(!arrivalGuards.identityAlreadyHeld, LocalStockFailure.SOURCE_ALREADY_USED)
            requireItemVersions(cut, request.items)
            if (opening) localStockCheck(request.items.all { it.storageRevision == null } &&
                request.lines.all { it.disposition == LocalReceiptDisposition.RELEASED || it.disposition == LocalReceiptDisposition.QUARANTINE },
                LocalStockFailure.OPENING_ALREADY_ESTABLISHED)
            val approved = basis ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE)
            localStockCheck(approved.revision == request.expectedBasisRevision, LocalStockFailure.STALE_REVISION)
            localStockCheck((approved.kind == LocalStockBasisKind.OPENING) == opening, LocalStockFailure.BASIS_UNAVAILABLE)
            localStockCheck(now >= approved.validFrom && (approved.validUntil == null || now < approved.validUntil), LocalStockFailure.BASIS_EXPIRED)
            localStockCheck(purchaseSource == null || purchaseSource.approval.recordedAt <= now, LocalStockFailure.AUTHORITY_UNAVAILABLE)
            val (valuedRequest, poValuation) = request.resolve(purchaseSource, approved, allocations)
            localStockCheck(valuedRequest.lines.filter { it.disposition != LocalReceiptDisposition.REFUSED }
                .groupBy { it.skuId to it.lotId }.values.all { lines -> lines.map { it.expiresAt }.distinct().size == 1 },
                LocalStockFailure.INVALID_LOT)
            val lines = valuedRequest.lines.map { line ->
                val item = items[line.skuId] ?: throw LocalStockRejected(LocalStockFailure.ITEM_UNAVAILABLE)
                arrivalGuards.requireCompatible(item, line.lotId, line.expiresAt)
                purchaseSource?.order?.lines?.singleOrNull { it.allocationId == line.allocationId }?.let { ordered ->
                    // Actual counted base units may outlive a changed carton conversion, but not a changed product/base contract.
                    localStockCheck(item.productId == ordered.goods.units.productId && item.baseUnitId == ordered.goods.units.baseUnitId &&
                        item.quantityScale == ordered.goods.units.baseScale, LocalStockFailure.INVALID_UNIT)
                }
                localStockCheck(warehouses[line.warehouseId]?.active == true, LocalStockFailure.WAREHOUSE_UNAVAILABLE)
                val approvedLine = approved.lines.singleOrNull { it.allocationId == line.allocationId &&
                    it.skuId == line.skuId && it.warehouseId == line.warehouseId } ?: throw LocalStockRejected(LocalStockFailure.BASIS_UNAVAILABLE)
                val registration = line.serialId?.let { registrations[it] }
                registration?.let {
                    localStockCheck(it.skuId == line.skuId && it.serialCode == line.serialCode, LocalStockFailure.INVALID_SERIAL)
                    requireSerialRegistration(it, context.scope, requireNotNull(line.serialId), line.skuId, requireNotNull(line.serialCode), now)
                }
                localStockCheck(line.serialId == null || line.disposition != LocalReceiptDisposition.RELEASED || registration != null,
                    LocalStockFailure.SERIAL_REGISTRATION_REQUIRED)
                val resolved = resolveReceiptLine(line, item, opening, approvedLine.inspectionRequired, now)
                    .copy(serialRegistration = registration?.let(StockSerialRegistrationWire::from))
                localStockCheck(approvedLine.maximumBaseQuantity.unitId == resolved.baseQuantity.unitId &&
                    approvedLine.maximumBaseQuantity.scale == resolved.baseQuantity.scale, LocalStockFailure.INVALID_UNIT)
                val priorItem = cut.state.items[line.skuId]
                localStockCheck(priorItem == null || priorItem.value.unitId == item.baseUnitId && priorItem.value.quantityScale == item.quantityScale,
                    LocalStockFailure.INVALID_UNIT)
                localStockCheck(priorItem == null || priorItem.value.positions.all { (it.serialId != null) == (line.serialId != null) },
                    LocalStockFailure.SERIAL_WORKFLOW_REQUIRED)
                if (line.disposition != LocalReceiptDisposition.REFUSED) {
                    val sameLot = cut.snapshot.positions.filter { it.key.pool.skuId == line.skuId && it.key.lotId == line.lotId }
                    localStockCheck(sameLot.all { it.expiresAtEpochMillis == line.expiresAt?.value?.toEpochMilliseconds() }, LocalStockFailure.INVALID_LOT)
                    if (line.disposition == LocalReceiptDisposition.RELEASED) {
                        val target = cut.snapshot.position(receiptPosition(line, approved.supplierId).inScope(context.scope))
                        localStockCheck(sameLot.none { it.safety == StockSafety.RECALLED || it.safety == StockSafety.EXPIRED } &&
                            (target == null || target.safety == StockSafety.CLEAR), LocalStockFailure.UNSAFE_STOCK)
                    }
                }
                resolved
            }
            val changes = mutableListOf(stockChange(context, AggregateKey(LocalStockStoreProfile.RECEIPT_DOCUMENT, valuedRequest.receiptId), null,
                StockReceiptWire(valuedRequest.receiptId, approved.basisId, approved.revision, approved.kind.name, approved.supplierId,
                    approved.approvedBy, approved.policyId, context.originalActorId, now, lines, sources.map(StockSourceStampWire::from), valuedRequest.reasonCode, poValuation),
                StockReceiptWire.serializer()))
            val claims = mutableListOf<ClaimChange>(ClaimChange.Acquire(LocalStockStoreProfile.documentClaim(valuedRequest.receiptId), valuedRequest.receiptId))
            // Includes refused-at-door source identities without pretending they are stock movements.
            valuedRequest.lines.forEach { claims += ClaimChange.Acquire(LocalStockStoreProfile.receiptLineClaim(it.sourceId), valuedRequest.receiptId) }
            for (line in lines.filter { it.request.serialId != null && it.request.disposition != LocalReceiptDisposition.REFUSED.name }) {
                val input = line.request.restore()
                val id = requireNotNull(input.serialId)
                val code = requireNotNull(input.serialCode)
                val serialValue = StockSerialWire(id, code, input.skuId, valuedRequest.receiptId, input.lineId, input.sourceId,
                    StockPositionRefWire.from(receiptPosition(input, approved.supplierId)), line.baseQuantity, context.originalActorId, now,
                    line.serialRegistration, if (line.serialRegistration == null) null else now)
                changes += stockChange(context, LocalStockStoreProfile.serialKey(id), null, serialValue, StockSerialWire.serializer())
                claims += ClaimChange.Acquire(LocalStockStoreProfile.serialClaim(id), id)
                claims += ClaimChange.Acquire(LocalStockStoreProfile.serialCodeClaim(code), id)
            }
            val committed = lines.filter { it.request.disposition == LocalReceiptDisposition.RELEASED.name ||
                it.request.disposition == LocalReceiptDisposition.QUARANTINE.name }.groupBy { it.request.allocationId }
            for ((allocationId, slices) in committed) {
                val previous = allocations.getValue(allocationId)
                val allocation = approved.lines.single { it.allocationId == allocationId }
                val unit = allocation.maximumBaseQuantity
                val zero = Quantity(unit.unitId, 0, unit.scale)
                val before = previous?.value ?: StockAllowanceWire(allocationId, approved.basisId, approved.supplierId,
                    allocation.skuId, allocation.warehouseId, zero, Money(Currency.EGP, 0), zero, zero)
                requireAllowanceBasis(before, approved, allocation)
                val added = slices.fold(zero) { total, slice -> total + slice.baseQuantity }
                val value = slices.fold(Money(Currency.EGP, 0)) { total, slice -> total + requireNotNull(slice.request.originalValue) }
                val accepted = slices.filter { it.request.disposition == LocalReceiptDisposition.RELEASED.name }
                    .fold(zero) { total, slice -> total + slice.baseQuantity }
                val pending = added - accepted
                val next = before.copy(committedQuantity = before.committedQuantity + added,
                    committedOwnedValue = before.committedOwnedValue + value,
                    acceptedQuantity = before.acceptedQuantity + accepted, pendingInspectionQuantity = before.pendingInspectionQuantity + pending)
                localStockCheck(next.committedQuantity <= allocation.maximumBaseQuantity && next.committedOwnedValue.minor <= allocation.maximumOwnedValue.minor,
                    LocalStockFailure.ALLOWANCE_EXCEEDED)
                changes += stockChange(context, AggregateKey(LocalStockStoreProfile.ALLOWANCE, allocationId), previous, next, StockAllowanceWire.serializer())
                if (previous == null) claims += ClaimChange.Acquire(LocalStockStoreProfile.allowanceClaim(allocationId), allocationId)
            }
            val receipts = lines.filter { it.request.disposition != LocalReceiptDisposition.REFUSED.name }.map { line ->
                val requestLine = line.request.restore()
                StockReceipt(requestLine.sourceId, receiptPosition(requestLine, approved.supplierId).inScope(context.scope), line.baseQuantity,
                    requestLine.originalValue, requestLine.expiresAt?.value?.toEpochMilliseconds(),
                    if (opening) ReceiptValueOrigin.OPENING else ReceiptValueOrigin.PURCHASE, line.serialRegistration?.registrationId)
            }
            val deadline = (listOfNotNull(approved.validUntil) + registrations.values.mapNotNull { it?.validUntil } + lines.filter { it.request.disposition == LocalReceiptDisposition.RELEASED.name }
                .mapNotNull { it.request.expiresAt }).minOrNull()
            if (receipts.isEmpty()) completeStockProposal(context, changes, claims,
                stockResult(if (opening) LocalStockResultKind.OPENED else LocalStockResultKind.RECEIVED, valuedRequest.receiptId), deadline = deadline,
                observations = stockReadObservations(cut.state))
            else {
                val plan = InventoryPlanner.receive(cut.snapshot, cut.snapshot.revision, context.operationId, receipts)
                val contribution = repository.project(cut, context, plan, now)
                repository.compose(context, contribution, stockResult(if (opening) LocalStockResultKind.OPENED else LocalStockResultKind.RECEIVED,
                    valuedRequest.receiptId, contribution.items), changes, claims, mustCommitBefore = deadline)
            }
        }
    }
}

private val poReceiptBarriers: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.PO_ALLOWANCE,
    RecoveryResourceKind.PURCHASE_CUSTODY, RecoveryResourceKind.SUPPLIER_SETTLEMENT, RecoveryResourceKind.AUTHORIZATION)

private fun poReceiptSelectionResources(input: StockReceiptPreparationInput): Set<RecoveryResourceKey> = buildSet {
    add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, input.receiptId))
    add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, input.basisId))
    input.lines.forEach { line ->
        add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, line.allocationId))
        listOf(line.lineId, line.sourceId).forEach { add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it)) }
        listOfNotNull(line.skuId, line.warehouseId, line.sourceId, line.lotId, line.serialId).forEach {
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, it))
        }
    }
}

internal fun receiptPosition(line: LocalGoodsReceiptLine, supplierId: DomainId?): LocalStockPositionRef =
    receiptPosition(line.skuId, line.warehouseId, line.lotId, line.disposition, line.serialId, supplierId)

private fun receiptPosition(line: StockReceiptPhysicalInput, supplierId: DomainId?): LocalStockPositionRef =
    receiptPosition(line.skuId, line.warehouseId, line.lotId, line.disposition, line.serialId, supplierId)

private fun receiptPosition(skuId: DomainId, warehouseId: DomainId, lotId: DomainId?, disposition: LocalReceiptDisposition,
                            serialId: DomainId?, supplierId: DomainId?): LocalStockPositionRef = LocalStockPositionRef(
    skuId, warehouseId, lotId, when (disposition) {
        LocalReceiptDisposition.RELEASED -> StockCustody.RELEASED
        LocalReceiptDisposition.QUARANTINE -> StockCustody.QUARANTINE
        LocalReceiptDisposition.REJECTED_HELD -> StockCustody.REJECTED_HELD
        LocalReceiptDisposition.REFUSED -> throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
    }, if (disposition == LocalReceiptDisposition.REJECTED_HELD) requireNotNull(supplierId) else null, serialId)

internal fun resolveReceiptLine(
    line: LocalGoodsReceiptLine, item: LocalStockItemAuthority, opening: Boolean, inspectionRequired: Boolean, now: UtcInstant,
): StockReceivedLineWire {
    localStockCheck(item.active && (opening || item.canPurchase) && item.tracking != StockTracking.NONE, LocalStockFailure.ITEM_UNAVAILABLE)
    localStockCheck((item.tracking == StockTracking.SERIAL) == (line.serialId != null), LocalStockFailure.SERIAL_WORKFLOW_REQUIRED)
    localStockCheck((item.tracking != StockTracking.LOT || line.lotId != null) &&
        (!item.requiresExpiry || line.lotId != null && line.expiresAt != null), LocalStockFailure.INVALID_LOT)
    localStockCheck(line.expiresAt == null || line.expiresAt.value.toEpochMilliseconds() >= 0, LocalStockFailure.INVALID_LOT)
    localStockCheck(!inspectionRequired || line.disposition != LocalReceiptDisposition.RELEASED, LocalStockFailure.INSPECTION_REQUIRED)
    localStockCheck(line.disposition != LocalReceiptDisposition.RELEASED || line.expiresAt == null || now < line.expiresAt,
        LocalStockFailure.UNSAFE_STOCK)
    // New current conversions have exact authenticated unit metadata. Legacy original
    // reconstruction deliberately retains its old arithmetic rather than inventing precision.
    item.unitDefinitions?.let { units ->
        localStockCheck(units.singleOrNull { it.id == line.quantity.unitId }
            ?.let { line.quantity.scale <= it.maximumScale } == true, LocalStockFailure.INVALID_UNIT)
    }
    val conversion = if (line.quantity.unitId == item.baseUnitId) {
        localStockCheck(line.conversion == null, LocalStockFailure.INVALID_UNIT)
        null
    } else item.conversions.singleOrNull { it.reference == line.conversion && it.productId == item.productId &&
        it.sourceUnitId == line.quantity.unitId && it.targetUnitId == item.baseUnitId } ?: throw LocalStockRejected(LocalStockFailure.INVALID_UNIT)
    val base = try {
        conversion?.convertExact(line.quantity, item.quantityScale) ?: line.quantity.rescaleExact(item.quantityScale)
    } catch (_: ArithmeticException) {
        throw LocalStockRejected(LocalStockFailure.INVALID_UNIT)
    }
    localStockCheck(base.unitId == item.baseUnitId && base.scaled > 0, LocalStockFailure.INVALID_QUANTITY)
    localStockCheck(line.serialId == null || base == stockSerialUnit(base), LocalStockFailure.INVALID_SERIAL)
    return StockReceivedLineWire(StockReceiptLineWire.from(line), base, conversion?.let(StockConversionWire::from), Quantity(base.unitId, 0, base.scale))
}

internal suspend fun stockAllowance(repository: LocalStockRepository, view: CommandReadView, id: DomainId): LocalStockRecord<StockAllowanceWire>? {
    val record = repository.record(view, AggregateKey(LocalStockStoreProfile.ALLOWANCE, id), StockAllowanceWire.serializer())
    val claim = view.claim(LocalStockStoreProfile.allowanceClaim(id))
    localStockCheck(if (record == null) claim == null else claim == id, LocalStockFailure.STORAGE_INCONSISTENT)
    record?.value?.let(::requireAllowance)
    return record
}

internal fun requireAllowance(value: StockAllowanceWire) {
    val quantities = listOf(value.committedQuantity, value.acceptedQuantity, value.pendingInspectionQuantity)
    localStockCheck(quantities.all { it.unitId == value.committedQuantity.unitId && it.scale == value.committedQuantity.scale && it.scaled >= 0 } &&
        value.committedQuantity == value.acceptedQuantity + value.pendingInspectionQuantity && value.committedOwnedValue.currency == Currency.EGP &&
        value.committedOwnedValue.minor >= 0, LocalStockFailure.STORAGE_INCONSISTENT)
}

private fun requireAllowanceBasis(value: StockAllowanceWire, basis: LocalStockReceivingBasis, line: LocalStockApprovedLine) {
    requireAllowance(value)
    localStockCheck(value.allocationId == line.allocationId && value.basisId == basis.basisId && value.supplierId == basis.supplierId &&
        value.skuId == line.skuId && value.warehouseId == line.warehouseId, LocalStockFailure.BASIS_UNAVAILABLE)
    localStockCheck(value.committedQuantity.unitId == line.maximumBaseQuantity.unitId && value.committedQuantity.scale == line.maximumBaseQuantity.scale,
        LocalStockFailure.INVALID_UNIT)
}

/**
 * The explicit command is the current employee's inspection attestation. evidenceId is retained
 * as their evidence reference, NOT asserted to be a verified external certificate/attachment.
 * Release consumes this original receipt's remaining quarantine, never its approved basis again.
 */
internal class StockInspectionHandler(private val repository: LocalStockRepository, private val authority: LocalStockAuthorityReader,
    serial: Boolean = false) : StockCommandHandler(
    if (serial) LocalStockStoreProfile.INSPECT_SERIAL else LocalStockStoreProfile.INSPECT,
    setOf(LocalStockStoreProfile.ITEM, LocalStockStoreProfile.POSITION, LocalStockStoreProfile.POSTING,
        LocalStockStoreProfile.RECEIPT_DOCUMENT, LocalStockStoreProfile.ALLOWANCE, LocalStockStoreProfile.INSPECTION, LocalStockStoreProfile.SERIAL_ORIGIN),
    setOf(LocalStockStoreProfile.SOURCE_CLAIM, LocalStockStoreProfile.DOCUMENT_CLAIM),
) {
    override suspend fun prepareStock(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        val request = LocalStockCommandCodec.inspection(normalized)
        val inspection = stockDocument(repository, view, LocalStockStoreProfile.INSPECTION, request.inspectionId, StockInspectionWire.serializer())
        val receipt = stockDocument(repository, view, LocalStockStoreProfile.RECEIPT_DOCUMENT, request.receiptId, StockReceiptWire.serializer())
        val original = receipt?.value?.lines?.singleOrNull { it.request.lineId == request.lineId }
        val cut = repository.observe(view, original?.let { listOf(it.request.skuId) } ?: emptyList())
        val serialRecord = original?.request?.serialId?.let { cut.state.serials[it]
            ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT) }
        val registration = serialRecord?.takeIf { it.value.registration == null }?.let { authority.serialRegistration(view, it.value.serialId) }
        registration?.let { requireStockAuthorityStamps(view, it.sources) }
        val allowance = original?.let { stockAllowance(repository, view, it.request.allocationId) }
        val used = view.claim(LocalStockStoreProfile.sourceClaim(request.inspectionId))
        val resources = repository.resources(cut) + registration?.let(::serialRegistrationResources).orEmpty() + buildSet {
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.receiptId))
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.inspectionId))
            add(RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, request.evidenceId))
            receipt?.value?.let {
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.approvedBy))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.policyId))
                add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.recordedBy))
                add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, it.basisId))
            }
            original?.let {
                add(RecoveryResourceKey(RecoveryResourceKind.PO_ALLOWANCE, it.request.allocationId))
                // Compute the actual new released coordinate, not a branch sentinel.
                if (it.request.disposition == LocalReceiptDisposition.QUARANTINE.name) {
                    val destination = receiptPosition(it.request.restore(), receipt?.value?.supplierId).inScope(context.scope)
                        .copy(custody = StockCustody.RELEASED)
                    add(RecoveryResourceKey(RecoveryResourceKind.STOCK, LocalStockStoreProfile.positionKey(destination).id))
                }
            }
            add(RecoveryResourceKey(RecoveryResourceKind.STOCK, request.inspectionId))
        }
        return preparedStock(context, request.receiptId, request.expectedReceiptRevision, request.reasonCode, resources) { now ->
            localStockCheck(inspection == null && used == null, LocalStockFailure.SOURCE_ALREADY_USED)
            val record = receipt ?: throw LocalStockRejected(LocalStockFailure.ORIGINAL_RECEIPT_REQUIRED)
            val slice = original ?: throw LocalStockRejected(LocalStockFailure.ORIGINAL_RECEIPT_REQUIRED)
            localStockCheck(request.serialId == slice.request.serialId, LocalStockFailure.INVALID_SERIAL)
            registration?.let { requireSerialRegistration(it, context.scope, requireNotNull(slice.request.serialId), slice.request.skuId,
                requireNotNull(slice.request.serialCode), now) }
            localStockCheck(slice.request.serialId == null || serialRecord?.value?.registration != null || registration != null,
                LocalStockFailure.SERIAL_REGISTRATION_REQUIRED)
            localStockCheck(record.row.revision == request.expectedReceiptRevision, LocalStockFailure.STALE_REVISION)
            localStockCheck(slice.request.disposition == LocalReceiptDisposition.QUARANTINE.name, LocalStockFailure.ORIGINAL_RECEIPT_REQUIRED)
            requireItemVersions(cut, listOf(LocalStockItemVersion(slice.request.skuId, request.expectedItemRevision)))
            localStockCheck(request.quantity.unitId == slice.baseQuantity.unitId && request.quantity.scale == slice.baseQuantity.scale, LocalStockFailure.INVALID_UNIT)
            localStockCheck(request.quantity <= slice.baseQuantity - slice.releasedFromQuarantine, LocalStockFailure.INVALID_QUANTITY)
            val allocation = allowance ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
            localStockCheck(allocation.value.allocationId == slice.request.allocationId && allocation.value.basisId == record.value.basisId &&
                allocation.value.supplierId == record.value.supplierId && allocation.value.skuId == slice.request.skuId &&
                allocation.value.warehouseId == slice.request.warehouseId && allocation.value.pendingInspectionQuantity >= request.quantity,
                LocalStockFailure.STORAGE_INCONSISTENT)
            val position = receiptPosition(slice.request.restore(), record.value.supplierId).inScope(context.scope)
            val plan = InventoryPlanner.releaseQuarantine(cut.snapshot, cut.snapshot.revision, context.operationId,
                request.inspectionId, position, request.quantity, now.value.toEpochMilliseconds(), registration?.confirmation())
            val contribution = repository.project(cut, context, plan, now)
            val updated = record.value.copy(lines = record.value.lines.map { if (it.request.lineId == request.lineId)
                it.copy(releasedFromQuarantine = it.releasedFromQuarantine + request.quantity) else it })
            val nextAllowance = allocation.value.copy(acceptedQuantity = allocation.value.acceptedQuantity + request.quantity,
                pendingInspectionQuantity = allocation.value.pendingInspectionQuantity - request.quantity)
            val serialChanges = if (registration == null) emptyList() else {
                val originalSerial = serialRecord ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
                listOf(stockChange(context, originalSerial.row.key, originalSerial,
                    originalSerial.value.copy(registration = StockSerialRegistrationWire.from(registration), registeredAt = now), StockSerialWire.serializer()))
            }
            repository.compose(context, contribution, stockResult(LocalStockResultKind.INSPECTED, request.inspectionId, contribution.items),
                listOf(stockChange(context, record.row.key, record, updated, StockReceiptWire.serializer()),
                    stockChange(context, allocation.row.key, allocation, nextAllowance, StockAllowanceWire.serializer()),
                    stockChange(context, AggregateKey(LocalStockStoreProfile.INSPECTION, request.inspectionId), null,
                        StockInspectionWire(StockInspectionRequestWire.from(request), context.originalActorId, now,
                            registration?.let(StockSerialRegistrationWire::from)), StockInspectionWire.serializer())) +
                    serialChanges,
                listOf(ClaimChange.Acquire(LocalStockStoreProfile.documentClaim(request.inspectionId), request.inspectionId)),
                mustCommitBefore = registration?.validUntil)
        }
    }
}
