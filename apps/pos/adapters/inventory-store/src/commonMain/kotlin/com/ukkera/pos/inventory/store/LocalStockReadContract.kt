@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.inventory.store

import com.ukkera.pos.catalog.CatalogVersionRef
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.StockSafety
import com.ukkera.pos.inventory.StockSerialRegistrationStatus
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitConversionRate
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

/** Display projections only, never mutation-ready stock facts or cached permission proofs. */
public data class LocalStockItemSummary(
    public val skuId: DomainId, public val storageRevision: Revision, public val unitId: DomainId,
    public val quantityScale: Int, public val positionCount: Int,
)
public class LocalStockItemsPage(items: List<LocalStockItemSummary>, public val nextAfter: DomainId?) {
    public val items: List<LocalStockItemSummary> = localStockFrozen(items)
}
public data class LocalStockQuantityRow(
    public val positionId: DomainId, public val position: LocalStockPositionRef, public val storageRevision: Revision,
    public val onHand: Quantity, public val reserved: Quantity, public val available: Quantity,
    public val safety: StockSafety, public val expiresAt: UtcInstant?, public val expiredAtRead: Boolean,
    /** Null only when decoding the legacy v1 read body, whose hold detail was not projected. */
    public val heldQuantity: Quantity? = null,
    /** Truthful post-count protected shortage, never a clipped reservation or negative stock. */
    public val heldShortageQuantity: Quantity? = null,
    /** Exact recorded code, not a lot alias or a globally issued identity. */
    public val serialCode: String? = null,
    public val serialRegistrationStatus: StockSerialRegistrationStatus? = null,
)
public class LocalStockQuantityPage(
    public val skuId: DomainId, public val itemStorageRevision: Revision?,
    public val unitId: DomainId?, public val quantityScale: Int?,
    rows: List<LocalStockQuantityRow>, public val nextAfter: DomainId?,
) {
    public val rows: List<LocalStockQuantityRow> = localStockFrozen(rows)
}
public data class LocalStockCostRow(
    public val poolId: DomainId, public val warehouseId: DomainId, public val storageRevision: Revision,
    public val valuationRevision: Revision, public val quantity: Quantity, public val carryingValue: Money,
)
public class LocalStockCostPage(
    public val skuId: DomainId, public val itemStorageRevision: Revision?,
    rows: List<LocalStockCostRow>, public val nextAfter: DomainId?,
) {
    /** Average is the exact carryingValue / quantity ratio; no separately rounded/floating price. */
    public val rows: List<LocalStockCostRow> = localStockFrozen(rows)
}
public data class LocalStockConversionSnapshot(
    public val reference: CatalogVersionRef, public val productId: DomainId, public val sourceUnitId: DomainId,
    public val targetUnitId: DomainId, public val rate: UnitConversionRate, public val massVolumeEvidenceId: DomainId?,
)
public data class LocalStockReceivedLine(
    public val lineId: DomainId, public val allocationId: DomainId, public val sourceId: DomainId,
    public val skuId: DomainId, public val warehouseId: DomainId, public val recordedQuantity: Quantity,
    public val baseQuantity: Quantity, public val conversion: LocalStockConversionSnapshot?,
    public val lotId: DomainId?, public val expiresAt: UtcInstant?, public val disposition: LocalReceiptDisposition,
    /** Null also means hidden when costsIncluded is false. Never treat it as zero book value. */
    public val originalValue: Money?, public val releasedFromQuarantine: Quantity,
    public val serialId: DomainId? = null, public val serialCode: String? = null,
    /** Historical receipt qualification, not current status; null for nonserial/refused lines. */
    public val initialSerialRegistrationStatus: StockSerialRegistrationStatus? = null,
)
public class LocalStockReceiptView(
    public val receiptId: DomainId, public val storageRevision: Revision, public val basisId: DomainId,
    public val basisRevision: Revision, public val basisKind: LocalStockBasisKind, public val supplierId: DomainId?,
    public val recordedBy: DomainId, public val receivedAt: UtcInstant, public val reasonCode: String,
    public val costsIncluded: Boolean, lines: List<LocalStockReceivedLine>,
) {
    public val lines: List<LocalStockReceivedLine> = localStockFrozen(lines)
}
public data class LocalStockCountView(
    public val countId: DomainId, public val storageRevision: Revision, public val position: LocalStockPositionRef,
    public val capturedItemRevision: Revision, public val capturedQuantity: Quantity,
    public val capturedBy: DomainId, public val capturedAt: UtcInstant, public val captureReasonCode: String,
    public val counted: Quantity?, public val postedBy: DomainId?, public val postedAt: UtcInstant?, public val postingReasonCode: String?,
    public val surplusBasisId: DomainId? = null,
)

/** Permanent receipt/qualification provenance plus CURRENT singular custody, not a new receipt. */
public data class LocalStockTransferSerialView(
    public val serialId: DomainId,
    public val serialCode: String,
    public val registrationStatus: StockSerialRegistrationStatus,
    public val registrationId: DomainId?,
    public val originalReceiptId: DomainId,
    public val originalReceiptLineId: DomainId,
    public val originalSourceId: DomainId,
    public val originalPosition: LocalStockPositionRef,
    public val originallyReceivedAt: UtcInstant,
    /** Null means the unit is not currently on hand, not that its original identity was deleted. */
    public val currentPosition: LocalStockPositionRef?,
    public val currentOnHand: Quantity,
    public val currentSafety: StockSafety?,
    public val currentExpiresAt: UtcInstant?,
)

public data class LocalStockTransferView(
    public val transferId: DomainId,
    public val storageRevision: Revision,
    public val sourceEventId: DomainId,
    public val destinationEventId: DomainId,
    public val source: LocalStockPositionRef,
    public val destination: LocalStockPositionRef,
    public val quantity: Quantity,
    public val transferredBy: DomainId,
    public val recordedAt: UtcInstant,
    public val reasonCode: String,
    public val allocationId: DomainId?,
    public val sourceValuationBasisRevision: Revision?,
    public val destinationValuationBasisRevision: Revision?,
    public val valuationCalculationVersion: String?,
    public val costsIncluded: Boolean,
    /** Immutable THEN-carried value, not original purchase cost or another acquisition/expense. */
    public val carriedValue: Money?,
    /** Cumulative current reference-minus-carried edge value, distinct from the immutable value. */
    public val linkedCostDelta: Money?,
    public val serial: LocalStockTransferSerialView?,
) {
    /** Null means hidden, or no owned value for supplier custody. It never means zero. */
    public val referenceCarriedValue: Money? get() = carriedValue?.let { original -> linkedCostDelta?.let { original + it } }
}

/** Decode only successful AtomicMasterStore.read payloads; retain ReadReply's branch revision/time. */
public object LocalStockQueries {
    public fun decodeItems(bytes: StoredBytes): LocalStockItemsPage = stockDecode(bytes, StockItemsReadWire.serializer()).let { wire ->
        localStockCheck(wire.version == 1 && wire.rows.size <= LocalStockStoreProfile.MAX_READ_ROWS)
        LocalStockItemsPage(wire.rows.map { LocalStockItemSummary(it.skuId, it.revision, it.unitId, it.scale, it.positions) }, wire.nextAfter)
    }
    public fun decodeQuantities(bytes: StoredBytes): LocalStockQuantityPage = stockDecode(bytes, StockQuantitiesReadWire.serializer()).let { wire ->
        localStockCheck(wire.version in 1..3 && wire.rows.size <= LocalStockStoreProfile.MAX_READ_ROWS)
        val serialized = wire.rows.any { it.position.serialId != null }
        localStockCheck((wire.version == 3) == serialized && (!serialized || wire.rows.all { it.position.serialId != null }))
        for (row in wire.rows) {
            localStockCheck(row.onHand.scaled >= 0 && listOf(row.reserved, row.available).all {
                it.unitId == row.onHand.unitId && it.scale == row.onHand.scale && it.scaled >= 0
            })
            if (wire.version == 1) localStockCheck(row.heldQuantity == null && row.heldShortageQuantity == null)
            else {
                val held = row.heldQuantity ?: throw LocalStockRejected(LocalStockFailure.MALFORMED)
                val shortage = row.heldShortageQuantity ?: throw LocalStockRejected(LocalStockFailure.MALFORMED)
                val expectedShortage = if (row.reserved > row.onHand) row.reserved - row.onHand
                    else Quantity(row.onHand.unitId, 0, row.onHand.scale)
                localStockCheck(listOf(held, shortage).all { it.unitId == row.onHand.unitId && it.scale == row.onHand.scale && it.scaled >= 0 } &&
                    held <= row.reserved && shortage == expectedShortage)
                if (shortage.scaled > 0) localStockCheck(held == row.reserved && row.available.scaled == 0L)
            }
            if (row.position.serialId == null) localStockCheck(row.serialCode == null && row.serialRegistrationStatus == null)
            else {
                requireStockSerialCode(row.serialCode ?: throw LocalStockRejected(LocalStockFailure.MALFORMED))
                val status = stockEnum<StockSerialRegistrationStatus>(row.serialRegistrationStatus
                    ?: throw LocalStockRejected(LocalStockFailure.MALFORMED))
                val one = stockSerialUnit(row.onHand)
                localStockCheck(listOf(row.onHand, row.reserved, row.available, requireNotNull(row.heldQuantity),
                    requireNotNull(row.heldShortageQuantity)).all { it.scaled == 0L || it == one })
                if (status == StockSerialRegistrationStatus.LOCAL_PENDING_REGISTRATION) localStockCheck(row.available.scaled == 0L &&
                    row.position.custody != com.ukkera.pos.inventory.StockCustody.RELEASED.name)
            }
        }
        LocalStockQuantityPage(wire.skuId, wire.revision, wire.unitId, wire.scale, wire.rows.map {
            LocalStockQuantityRow(it.id, it.position.restore(), it.revision, it.onHand, it.reserved, it.available,
                stockEnum(it.safety), it.expiresAt, it.expiredAtRead, it.heldQuantity, it.heldShortageQuantity,
                it.serialCode, it.serialRegistrationStatus?.let { status -> stockEnum<StockSerialRegistrationStatus>(status) })
        }, wire.nextAfter)
    }
    public fun decodeCosts(bytes: StoredBytes): LocalStockCostPage = stockDecode(bytes, StockCostsReadWire.serializer()).let { wire ->
        localStockCheck(wire.version == 1 && wire.rows.size <= LocalStockStoreProfile.MAX_READ_ROWS)
        LocalStockCostPage(wire.skuId, wire.revision, wire.rows.map {
            LocalStockCostRow(it.id, it.warehouseId, it.revision, it.valuationRevision, it.quantity, it.value)
        }, wire.nextAfter)
    }
    /** Quantity-only receipt route omits all value fields; receiptCosts requires extra permission. */
    public fun decodeReceipt(bytes: StoredBytes): LocalStockReceiptView? = stockDecode(bytes, StockReceiptReadWire.serializer()).let { wire ->
        val version = if (wire.value?.lines?.any { it.serialId != null } == true) 2 else 1
        localStockCheck(wire.version == version)
        wire.value?.let { value ->
            localStockCheck(value.lines.size in 1..LocalStockStoreProfile.MAX_LINES_PER_COMMAND &&
                (value.costsIncluded || value.lines.all { it.originalValue == null }))
            LocalStockReceiptView(value.id, value.revision, value.basisId, value.basisRevision, stockEnum(value.basisKind), value.supplierId,
                value.recordedBy, value.receivedAt, value.reasonCode, value.costsIncluded, value.lines.map { line ->
                    localStockCheck((line.serialId == null) == (line.serialCode == null))
                    val status = line.initialSerialRegistrationStatus?.let { stockEnum<StockSerialRegistrationStatus>(it) }
                    if (line.serialId == null) localStockCheck(status == null)
                    else {
                        requireStockSerialCode(requireNotNull(line.serialCode))
                        localStockCheck(line.base == stockSerialUnit(line.base) && line.released.unitId == line.base.unitId &&
                            line.released.scale == line.base.scale && (line.released.scaled == 0L || line.released == line.base))
                        localStockCheck((status == null) == (line.disposition == LocalReceiptDisposition.REFUSED.name))
                        if (line.disposition == LocalReceiptDisposition.RELEASED.name) localStockCheck(status == StockSerialRegistrationStatus.REGISTERED)
                    }
                    LocalStockReceivedLine(line.lineId, line.allocationId, line.sourceId, line.skuId, line.warehouseId, line.recorded,
                        line.base, line.conversion?.let { LocalStockConversionSnapshot(CatalogVersionRef(it.id, it.revision), it.productId,
                            it.sourceUnitId, it.targetUnitId, it.rate, it.massVolumeEvidenceId) }, line.lotId, line.expiresAt,
                        stockEnum(line.disposition), line.originalValue, line.released, line.serialId, line.serialCode, status)
                })
        }
    }
    public fun decodeCount(bytes: StoredBytes): LocalStockCountView? = stockDecode(bytes, StockCountReadWire.serializer()).let { wire ->
        val version = if (wire.value?.position?.serialId != null) 2 else 1
        localStockCheck(wire.version == version)
        wire.value?.let { value ->
            if (value.position.serialId != null) {
                val one = stockSerialUnit(value.capturedQuantity)
                localStockCheck(value.surplusBasisId == null && (value.capturedQuantity.scaled == 0L || value.capturedQuantity == one) &&
                    (value.counted?.let { it.unitId == one.unitId && it.scale == one.scale &&
                        (it.scaled == 0L || it == one) && it <= value.capturedQuantity } ?: true))
            }
            LocalStockCountView(value.id, value.revision, value.position.restore(), value.capturedItemRevision,
                value.capturedQuantity, value.capturedBy, value.capturedAt, value.captureReasonCode, value.counted, value.postedBy,
                value.postedAt, value.postingReasonCode, value.surplusBasisId)
        }
    }
    /** Both transferDetail and transferCosts use this new read DTO; only the latter carries value. */
    public fun decodeTransfer(bytes: StoredBytes): LocalStockTransferView? = stockDecode(bytes, StockTransferReadWire.serializer()).let { wire ->
        localStockCheck(wire.version in 1..2)
        wire.value?.let { value ->
            val source = value.source.restore()
            val destination = value.destination.restore()
            localStockReason(value.reasonCode)
            localStockCheck(value.revision == Revision(1) && value.quantity.scaled > 0 &&
                source.warehouseId != destination.warehouseId && destination == source.copy(warehouseId = destination.warehouseId) &&
                value.sourceEventId != value.destinationEventId)
            val owned = source.supplierId == null
            localStockCheck(wire.version == 1 || owned)
            if (owned) localStockCheck(source.custody == com.ukkera.pos.inventory.StockCustody.RELEASED &&
                value.allocationId == value.id && value.sourceValuationRevision?.value?.let { it >= 1 } == true &&
                value.destinationValuationRevision?.value?.let { it >= (if (wire.version == 1) 0 else 1) } == true &&
                value.calculationVersion == (if (wire.version == 1) com.ukkera.pos.inventory.LinkedMovingAveragePlanner.CALCULATION_VERSION
                    else com.ukkera.pos.inventory.MovingAveragePlanner.CALCULATION_VERSION))
            else localStockCheck(value.allocationId == null && value.sourceValuationRevision == null &&
                value.destinationValuationRevision == null && value.calculationVersion == null &&
                value.carriedValue == null && value.linkedCostDelta == null)
            if (!value.costsIncluded) localStockCheck(value.carriedValue == null && value.linkedCostDelta == null)
            else if (owned) {
                val carried = value.carriedValue ?: throw LocalStockRejected(LocalStockFailure.MALFORMED)
                val delta = value.linkedCostDelta ?: throw LocalStockRejected(LocalStockFailure.MALFORMED)
                localStockCheck(carried.currency == com.ukkera.pos.types.Currency.EGP && carried.minor >= 0 &&
                    delta.currency == carried.currency && (carried + delta).minor >= 0)
                // v2 has no admitted linked correction writer; its complete pool fold
                // rejects such history. A new corrected representation needs its own profile.
                localStockCheck(wire.version == 1 || delta.minor == 0L)
            }
            localStockCheck((value.serial != null) == (source.serialId != null))
            val serial = value.serial?.let { row ->
                requireStockSerialCode(row.code)
                val status = stockEnum<StockSerialRegistrationStatus>(row.registrationStatus)
                val original = row.originalPosition.restore()
                val current = row.currentPosition?.restore()
                val safety = row.currentSafety?.let { stockEnum<StockSafety>(it) }
                val one = stockSerialUnit(value.quantity)
                localStockCheck(row.id == source.serialId && original.serialId == row.id && original.skuId == source.skuId &&
                    original.lotId == source.lotId && original.supplierId == source.supplierId && value.quantity == one &&
                    row.currentOnHand.unitId == one.unitId && row.currentOnHand.scale == one.scale &&
                    (row.currentOnHand.scaled == 0L || row.currentOnHand == one) &&
                    (current == null) == (row.currentOnHand.scaled == 0L) && (current == null) == (safety == null) &&
                    (current != null || row.currentExpiresAt == null) &&
                    (row.registrationId == null) == (status == StockSerialRegistrationStatus.LOCAL_PENDING_REGISTRATION))
                if (current != null) localStockCheck(current.serialId == row.id && current.skuId == source.skuId &&
                    current.lotId == source.lotId && current.supplierId == source.supplierId)
                if (owned) localStockCheck(status == StockSerialRegistrationStatus.REGISTERED)
                LocalStockTransferSerialView(row.id, row.code, status, row.registrationId, row.receiptId, row.lineId,
                    row.sourceId, original, row.receivedAt, current, row.currentOnHand, safety, row.currentExpiresAt)
            }
            LocalStockTransferView(value.id, value.revision, value.sourceEventId, value.destinationEventId, source, destination,
                value.quantity, value.transferredBy, value.recordedAt, value.reasonCode, value.allocationId,
                value.sourceValuationRevision, value.destinationValuationRevision, value.calculationVersion,
                value.costsIncluded, value.carriedValue, value.linkedCostDelta, serial)
        }
    }
}

@Serializable internal data class StockItemReadWire(val skuId: DomainId, val revision: Revision, val unitId: DomainId, val scale: Int, val positions: Int)
@Serializable internal data class StockItemsReadWire(val version: Int, val rows: List<StockItemReadWire>, val nextAfter: DomainId?)
@Serializable internal data class StockQuantityReadWire(val id: DomainId, val position: StockPositionRefWire, val revision: Revision,
    val onHand: Quantity, val reserved: Quantity, val available: Quantity, val safety: String, val expiresAt: UtcInstant?, val expiredAtRead: Boolean,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val heldQuantity: Quantity? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val heldShortageQuantity: Quantity? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialCode: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialRegistrationStatus: String? = null)
@Serializable internal data class StockQuantitiesReadWire(val version: Int, val skuId: DomainId, val revision: Revision?, val unitId: DomainId?, val scale: Int?,
    val rows: List<StockQuantityReadWire>, val nextAfter: DomainId?)
@Serializable internal data class StockCostReadWire(val id: DomainId, val warehouseId: DomainId, val revision: Revision, val valuationRevision: Revision,
    val quantity: Quantity, val value: Money)
@Serializable internal data class StockCostsReadWire(val version: Int, val skuId: DomainId, val revision: Revision?, val rows: List<StockCostReadWire>, val nextAfter: DomainId?)
@Serializable internal data class StockReceivedReadLineWire(val lineId: DomainId, val allocationId: DomainId, val sourceId: DomainId,
    val skuId: DomainId, val warehouseId: DomainId, val recorded: Quantity, val base: Quantity, val conversion: StockConversionWire?,
    val lotId: DomainId?, val expiresAt: UtcInstant?, val disposition: String, val originalValue: Money?, val released: Quantity,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialId: DomainId? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val serialCode: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val initialSerialRegistrationStatus: String? = null)
@Serializable internal data class StockReceiptReadValueWire(val id: DomainId, val revision: Revision, val basisId: DomainId, val basisRevision: Revision,
    val basisKind: String, val supplierId: DomainId?, val recordedBy: DomainId, val receivedAt: UtcInstant, val reasonCode: String,
    val costsIncluded: Boolean, val lines: List<StockReceivedReadLineWire>)
@Serializable internal data class StockReceiptReadWire(val version: Int, val value: StockReceiptReadValueWire?)
@Serializable internal data class StockCountReadValueWire(val id: DomainId, val revision: Revision, val position: StockPositionRefWire,
    val capturedItemRevision: Revision, val capturedQuantity: Quantity, val capturedBy: DomainId, val capturedAt: UtcInstant, val captureReasonCode: String,
    val counted: Quantity?, val postedBy: DomainId?, val postedAt: UtcInstant?, val postingReasonCode: String?,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val surplusBasisId: DomainId? = null)
@Serializable internal data class StockCountReadWire(val version: Int, val value: StockCountReadValueWire?)

@Serializable internal data class StockTransferSerialReadWire(
    val id: DomainId, val code: String, val registrationStatus: String, val registrationId: DomainId?,
    val receiptId: DomainId, val lineId: DomainId, val sourceId: DomainId, val originalPosition: StockPositionRefWire,
    val receivedAt: UtcInstant, val currentPosition: StockPositionRefWire?, val currentOnHand: Quantity,
    val currentSafety: String?, val currentExpiresAt: UtcInstant?,
)
@Serializable internal data class StockTransferReadValueWire(
    val id: DomainId, val revision: Revision, val sourceEventId: DomainId, val destinationEventId: DomainId,
    val source: StockPositionRefWire, val destination: StockPositionRefWire, val quantity: Quantity,
    val transferredBy: DomainId, val recordedAt: UtcInstant, val reasonCode: String,
    val allocationId: DomainId?, val sourceValuationRevision: Revision?, val destinationValuationRevision: Revision?,
    val calculationVersion: String?, val costsIncluded: Boolean, val carriedValue: Money?, val linkedCostDelta: Money?,
    val serial: StockTransferSerialReadWire?,
)
@Serializable internal data class StockTransferReadWire(val version: Int, val value: StockTransferReadValueWire?)
