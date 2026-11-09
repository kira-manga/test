package com.ukkera.pos.inventory.store

import com.ukkera.pos.types.*

/** Source-neutral physical facts. No synthetic source generation or v1 envelope is constructed. */
internal data class ArrivalPhysicalItem(val skuId: DomainId, val baseUnitId: DomainId, val quantityScale: Int,
    val tracking: LocalArrivalTrackingV1)
/** Physical capability only, never persisted source metadata or an authorization fallback. */
internal enum class ArrivalPhysicalProfile { V1_STRICT, V2_UNIDENTIFIED_SERIAL_PARTITIONS }
internal data class ArrivalPhysicalOriginal(val arrivalId: DomainId, val supplierId: DomainId,
    val lines: List<LocalArrivalLineV1>, val items: List<ArrivalPhysicalItem>,
    val profile: ArrivalPhysicalProfile = ArrivalPhysicalProfile.V1_STRICT)
internal data class ArrivalPhysicalTransition(val action: LocalArrivalActionV1, val arrivalId: DomainId,
    val custodyUnitId: DomainId, val quantity: Quantity, val split: LocalArrivalSplitV1?, val lotId: DomainId?,
    val expiresAt: UtcInstant?, val serialId: DomainId?, val serialCode: String?, val serialRegistrationRevision: Revision?,
    val inspectionPassed: Boolean, val recipientSupplierId: DomainId?)

internal fun ArrivalBodyV1.Original.physical(): ArrivalPhysicalOriginal = ArrivalPhysicalOriginal(request.arrivalId,
    request.supplierId, request.lines, authority.items.map { ArrivalPhysicalItem(it.selection.skuId,
        it.selection.baseUnitId, it.selection.quantityScale, it.tracking) })
internal fun ArrivalTransitionInput.physical(): ArrivalPhysicalTransition = ArrivalPhysicalTransition(action, arrivalId,
    custodyUnitId, quantity, split, lotId, expiresAt, serialId, serialCode, serialRegistrationRevision,
    inspectionPassed, recipientSupplierId)

internal fun arrivalInitialSlicesShared(lines: List<LocalArrivalLineV1>): List<ArrivalSliceV1> = lines.map { line ->
    ArrivalSliceV1(line.lineId, line.sourceId, line.custodyUnitId, line.sourceId, line.custodyUnitId, null,
        line.baseQuantity, line.lotId, line.expiresAt, line.serialId, line.serialCode, line.serialRegistrationRevision, line.disposition)
}

/** Literal conserved physical successor. No catalog lookup, approval, money or command execution. */
internal fun arrivalPhysicalSuccessorShared(original: ArrivalPhysicalOriginal, beforeArrivalId: DomainId, beforeSlices: List<ArrivalSliceV1>,
    input: ArrivalPhysicalTransition): Pair<List<ArrivalSliceV1>, ArrivalSliceV1> {
    val selected = beforeSlices.singleOrNull { it.custodyUnitId == input.custodyUnitId }
        ?: throw ArrivalRejected(LocalArrivalFailureV1.SOURCE_MISSING)
    val line = original.lines.single { it.lineId == selected.lineId }
    val item = original.items.single { it.skuId == line.skuId }
    arrivalRequire(input.arrivalId == original.arrivalId && beforeArrivalId == input.arrivalId,
        LocalArrivalFailureV1.SOURCE_MISSING)
    arrivalRequire(arrivalHeld(selected.disposition), LocalArrivalFailureV1.INVALID_DISPOSITION)
    if (original.profile == ArrivalPhysicalProfile.V2_UNIDENTIFIED_SERIAL_PARTITIONS && input.action == LocalArrivalActionV1.QUALIFY)
        arrivalRequire(selected.disposition != LocalArrivalDispositionV1.REJECTED_HELD, LocalArrivalFailureV1.INVALID_DISPOSITION)
    arrivalRequire(input.quantity.unitId == selected.quantity.unitId && input.quantity.scale == selected.quantity.scale,
        LocalArrivalFailureV1.INVALID_UNIT)
    arrivalRequire(input.quantity.scaled > 0 && input.quantity <= selected.quantity, LocalArrivalFailureV1.INVALID_QUANTITY)
    val partial = input.quantity < selected.quantity
    arrivalRequire(partial == (input.split != null), LocalArrivalFailureV1.INVALID_QUANTITY)
    if (item.tracking == LocalArrivalTrackingV1.SERIAL) {
        val unknownPartition = original.profile == ArrivalPhysicalProfile.V2_UNIDENTIFIED_SERIAL_PARTITIONS &&
            selected.serialId == null && selected.serialCode == null
        arrivalRequire(if (unknownPartition) input.quantity.scaled % stockSerialUnit(input.quantity).scaled == 0L
            else !partial && input.quantity == stockSerialUnit(input.quantity), LocalArrivalFailureV1.INVALID_SERIAL)
    }
    if (partial) {
        arrivalRequire(beforeSlices.size < LocalUnpricedArrivalProfileV1.MAX_SLICES, LocalArrivalFailureV1.CAPACITY_EXCEEDED)
        val split = requireNotNull(input.split)
        arrivalRequire(beforeSlices.none { it.sourceId == split.sourceId || it.custodyUnitId == split.custodyUnitId }, LocalArrivalFailureV1.SOURCE_ALREADY_USED)
    }
    var changed = selected.copy(quantity = input.quantity,
        sourceId = input.split?.sourceId ?: selected.sourceId, custodyUnitId = input.split?.custodyUnitId ?: selected.custodyUnitId,
        parentCustodyUnitId = if (partial) selected.custodyUnitId else selected.parentCustodyUnitId)
    when (input.action) {
        LocalArrivalActionV1.RECORD -> error("Record is not a transition")
        LocalArrivalActionV1.QUALIFY -> {
            fun <T> append(known: T?, observed: T?): T? {
                arrivalRequire(known == null || observed == null || known == observed, LocalArrivalFailureV1.KNOWN_METADATA_IMMUTABLE)
                return known ?: observed
            }
            changed = changed.copy(lotId = append(selected.lotId, input.lotId), expiresAt = append(selected.expiresAt, input.expiresAt),
                serialId = append(selected.serialId, input.serialId), serialCode = append(selected.serialCode, input.serialCode),
                serialRegistrationRevision = append(selected.serialRegistrationRevision, input.serialRegistrationRevision))
            if (input.inspectionPassed) {
                arrivalRequire(selected.disposition != LocalArrivalDispositionV1.REJECTED_HELD, LocalArrivalFailureV1.INVALID_DISPOSITION)
                changed = changed.copy(disposition = LocalArrivalDispositionV1.PASSED_HELD)
            }
            // Splitting by itself is not an inspection/identification observation.
            arrivalRequire(changed.copy(quantity = selected.quantity, sourceId = selected.sourceId, custodyUnitId = selected.custodyUnitId,
                parentCustodyUnitId = selected.parentCustodyUnitId) != selected, LocalArrivalFailureV1.NO_NEW_OBSERVATION)
        }
        LocalArrivalActionV1.REJECT -> {
            arrivalRequire(selected.disposition != LocalArrivalDispositionV1.REJECTED_HELD, LocalArrivalFailureV1.INVALID_DISPOSITION)
            changed = changed.copy(disposition = LocalArrivalDispositionV1.REJECTED_HELD)
        }
        LocalArrivalActionV1.DISPATCH -> {
            arrivalRequire(selected.disposition == LocalArrivalDispositionV1.REJECTED_HELD, LocalArrivalFailureV1.INVALID_DISPOSITION)
            arrivalRequire(input.recipientSupplierId == original.supplierId, LocalArrivalFailureV1.WRONG_RECIPIENT)
            changed = changed.copy(disposition = LocalArrivalDispositionV1.DISPATCHED)
        }
    }
    requireArrivalSliceTrackingShared(changed, item, original.profile)
    val slices = beforeSlices.flatMap { slice -> if (slice.custodyUnitId != selected.custodyUnitId) listOf(slice)
        else if (partial) listOf(slice.copy(quantity = selected.quantity - input.quantity), changed) else listOf(changed) }
    requireArrivalPhysicalStateShared(original, slices)
    return slices to changed
}

internal fun requireArrivalSliceTrackingShared(slice: ArrivalSliceV1, item: ArrivalPhysicalItem,
    profile: ArrivalPhysicalProfile = ArrivalPhysicalProfile.V1_STRICT) {
    arrivalRequire(slice.quantity.scaled > 0 && slice.quantity.unitId == item.baseUnitId &&
        slice.quantity.scale == item.quantityScale, LocalArrivalFailureV1.INVALID_UNIT)
    arrivalRequire(slice.expiresAt == null || slice.lotId != null && slice.expiresAt.value.toEpochMilliseconds() >= 0, LocalArrivalFailureV1.INVALID_LOT)
    if (item.tracking == LocalArrivalTrackingV1.SERIAL) {
        val unknownPartition = profile == ArrivalPhysicalProfile.V2_UNIDENTIFIED_SERIAL_PARTITIONS && slice.serialId == null && slice.serialCode == null
        arrivalRequire((if (unknownPartition) slice.quantity.scaled % stockSerialUnit(slice.quantity).scaled == 0L
            else slice.quantity == stockSerialUnit(slice.quantity)) && slice.lotId == null && slice.expiresAt == null, LocalArrivalFailureV1.INVALID_SERIAL)
    } else arrivalRequire(slice.serialId == null && slice.serialCode == null && slice.serialRegistrationRevision == null, LocalArrivalFailureV1.INVALID_TRACKING)
    slice.serialCode?.let(::requireStockSerialCode)
    arrivalRequire((slice.serialId == null) == (slice.serialRegistrationRevision == null) && (slice.serialId == null || slice.serialCode != null), LocalArrivalFailureV1.INVALID_SERIAL)
}

internal fun requireArrivalPhysicalStateShared(original: ArrivalPhysicalOriginal, slices: List<ArrivalSliceV1>) {
    check(slices.size in 1..LocalUnpricedArrivalProfileV1.MAX_SLICES && slices.map { it.custodyUnitId }.distinct().size == slices.size &&
        slices.map { it.sourceId }.distinct().size == slices.size)
    check(slices.map { it.lineId }.toSet() == original.lines.map { it.lineId }.toSet())
    for (line in original.lines) {
        val partitions = slices.filter { it.lineId == line.lineId }
        val item = original.items.single { it.skuId == line.skuId }
        var remaining = line.baseQuantity
        for (slice in partitions) {
            requireArrivalSliceTrackingShared(slice, item, original.profile)
            check(slice.rootSourceId == line.sourceId && slice.originalCustodyUnitId == line.custodyUnitId && slice.quantity <= remaining)
            remaining -= slice.quantity
            check(line.lotId == null || slice.lotId == line.lotId)
            check(line.expiresAt == null || slice.expiresAt == line.expiresAt)
            check(line.serialId == null || slice.serialId == line.serialId)
            check(line.serialCode == null || slice.serialCode == line.serialCode)
            if (slice.custodyUnitId == line.custodyUnitId) check(slice.sourceId == line.sourceId && slice.parentCustodyUnitId == null)
            else check(slice.parentCustodyUnitId != null && slice.parentCustodyUnitId != slice.custodyUnitId &&
                partitions.any { it.custodyUnitId == slice.parentCustodyUnitId })
            if (line.disposition == LocalArrivalDispositionV1.REFUSED) check(slice.disposition == LocalArrivalDispositionV1.REFUSED)
            else check(slice.disposition != LocalArrivalDispositionV1.REFUSED)
        }
        check(remaining.scaled == 0L && partitions.count { it.custodyUnitId == line.custodyUnitId } == 1)
        if (item.tracking == LocalArrivalTrackingV1.SERIAL && (original.profile == ArrivalPhysicalProfile.V1_STRICT ||
                line.serialId != null || line.serialCode != null)) check(partitions.size == 1)
        for (slice in partitions) {
            val visited = mutableSetOf<DomainId>()
            var parent: ArrivalSliceV1? = slice
            while (parent != null) {
                check(visited.add(parent.custodyUnitId))
                val parentId = parent.parentCustodyUnitId
                parent = if (parentId == null) null else partitions.single { it.custodyUnitId == parentId }
            }
        }
    }
    arrivalRequire(slices.mapNotNull { it.serialCode }.distinct().size == slices.count { it.serialCode != null } &&
        slices.mapNotNull { it.serialId }.distinct().size == slices.count { it.serialId != null }, LocalArrivalFailureV1.SOURCE_ALREADY_USED)
}
