@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.inventory

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault

@Serializable
internal data class StoredValueSlice(val allocationId: DomainId, val quantity: Quantity, val originalValue: Money) {
    fun restore(): CostedValueSlice = CostedValueSlice(allocationId, quantity, originalValue)
    companion object {
        fun from(value: CostedValueSlice): StoredValueSlice = StoredValueSlice(value.allocationId, value.quantity, value.originalValue)
    }
}

@Serializable
internal data class StoredValueRestoration(
    val issueId: DomainId,
    val allocationId: DomainId,
    val quantity: Quantity,
    val originalValue: Money,
) {
    fun restore(): OriginalValueRestoration = OriginalValueRestoration(issueId, allocationId, quantity, originalValue)
    companion object {
        fun from(value: OriginalValueRestoration): StoredValueRestoration = StoredValueRestoration(value.issueId,
            value.allocationId, value.quantity, value.originalValue)
    }
}

@Serializable
internal sealed class StoredValueFact {
    abstract val factId: DomainId
    abstract fun restore(): ValuationFact

    @Serializable
    @SerialName("receipt")
    data class Receipt(
        override val factId: DomainId,
        val quantity: Quantity,
        val originalValue: Money,
        val origin: String,
    ) : StoredValueFact() {
        override fun restore(): ValuationFact = ValuationFact.Receipt(factId, quantity, originalValue, enumValueOf<ReceiptValueOrigin>(origin))
    }

    @Serializable
    @SerialName("issue")
    data class Issue(override val factId: DomainId, val slices: List<StoredValueSlice>, val purpose: String) : StoredValueFact() {
        override fun restore(): ValuationFact {
            inventorySnapshotCheck(slices.size <= MovingAveragePlanner.MAX_SLICES, InventorySnapshotFailure.TOO_LARGE)
            return ValuationFact.Issue(factId, slices.map { it.restore() }, enumValueOf<ValueIssuePurpose>(purpose))
        }
    }

    @Serializable
    @SerialName("restoration")
    data class Restoration(override val factId: DomainId, val restorations: List<StoredValueRestoration>) : StoredValueFact() {
        override fun restore(): ValuationFact {
            inventorySnapshotCheck(restorations.size <= MovingAveragePlanner.MAX_SLICES, InventorySnapshotFailure.TOO_LARGE)
            return ValuationFact.Restoration(factId, restorations.map { it.restore() })
        }
    }

    @Serializable @SerialName("transfer_out")
    data class TransferOut(val link: StoredTransferValueLink) : StoredValueFact() {
        override val factId: DomainId get() = link.sourceEventId
        override fun restore(): ValuationFact = ValuationFact.TransferOut(link.restore())
    }
    @Serializable @SerialName("transfer_in")
    data class TransferIn(val link: StoredTransferValueLink) : StoredValueFact() {
        override val factId: DomainId get() = link.destinationEventId
        override fun restore(): ValuationFact = ValuationFact.TransferIn(link.restore())
    }

    companion object {
        fun from(value: ValuationFact): StoredValueFact = when (value) {
            is ValuationFact.Receipt -> Receipt(value.factId, value.quantity, value.originalValue, value.origin.name)
            is ValuationFact.TransferOut -> TransferOut(StoredTransferValueLink.from(value.link))
            is ValuationFact.TransferIn -> TransferIn(StoredTransferValueLink.from(value.link))
            is ValuationFact.Issue -> Issue(value.factId, value.slices.map(StoredValueSlice::from), value.purpose.name)
            is ValuationFact.Restoration -> Restoration(value.factId, value.restorations.map(StoredValueRestoration::from))
        }
    }
}

@Serializable
internal data class StoredCostTarget(val sourceId: DomainId, val receiptId: DomainId, val targetDelta: Money) {
    fun restore(): ReceiptCostTarget = ReceiptCostTarget(sourceId, receiptId, targetDelta)
    companion object {
        fun from(value: ReceiptCostTarget): StoredCostTarget = StoredCostTarget(value.sourceId, value.receiptId, value.targetDelta)
    }
}

@Serializable
internal sealed class StoredValueDestination {
    abstract fun restore(): ValueDestination

    @Serializable
    @SerialName("held")
    data object Held : StoredValueDestination() {
        override fun restore(): ValueDestination = ValueDestination.Held
    }

    @Serializable
    @SerialName("consumed")
    data class Consumed(val issueId: DomainId, val allocationId: DomainId) : StoredValueDestination() {
        override fun restore(): ValueDestination = ValueDestination.Consumed(issueId, allocationId)
    }

    @Serializable @SerialName("transferred")
    data class Transferred(val transferId: DomainId, val allocationId: DomainId) : StoredValueDestination() {
        override fun restore(): ValueDestination = ValueDestination.Transferred(transferId, allocationId)
    }

    companion object {
        fun from(value: ValueDestination): StoredValueDestination = when (value) {
            ValueDestination.Held -> Held
            is ValueDestination.Consumed -> Consumed(value.issueId, value.allocationId)
            is ValueDestination.Transferred -> Transferred(value.transferId, value.allocationId)
        }
    }
}

@Serializable
internal data class StoredDestinationValue(val destination: StoredValueDestination, val value: Money) {
    fun restore(): DestinationValue = DestinationValue(destination.restore(), value)
    companion object {
        fun from(value: DestinationValue): StoredDestinationValue = StoredDestinationValue(StoredValueDestination.from(value.destination), value.value)
    }
}

@Serializable
internal data class StoredCostAdjustment(
    val calculationVersion: String,
    val adjustmentId: DomainId,
    val basisRevision: Revision,
    val changedSource: StoredCostTarget?,
    val targetBasis: List<StoredCostTarget>,
    val deltas: List<StoredDestinationValue>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val transferTargets: List<StoredTransferCostTarget> = emptyList(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val transferCorrection: StoredTransferCorrectionBasis? = null,
) {
    fun restore(): LinkedCostAdjustment {
        inventorySnapshotCheck(calculationVersion == MovingAveragePlanner.CALCULATION_VERSION ||
            calculationVersion == LinkedMovingAveragePlanner.CALCULATION_VERSION, InventorySnapshotFailure.UNSUPPORTED_VERSION)
        inventorySnapshotCheck(targetBasis.size <= MovingAveragePlanner.MAX_COST_SOURCES, InventorySnapshotFailure.TOO_LARGE)
        inventorySnapshotCheck(transferTargets.size <= MovingAveragePlanner.MAX_EVENTS, InventorySnapshotFailure.TOO_LARGE)
        inventorySnapshotCheck(deltas.size <= InventorySnapshotCodec.MAX_ARRAY_ENTRIES, InventorySnapshotFailure.TOO_LARGE)
        return LinkedCostAdjustment(adjustmentId, basisRevision, changedSource?.restore(), targetBasis.map { it.restore() }, deltas.map { it.restore() },
            transferTargets.map { it.restore() }, transferCorrection?.restore(), calculationVersion)
    }
    companion object {
        fun from(value: LinkedCostAdjustment): StoredCostAdjustment = StoredCostAdjustment(value.calculationVersion,
            value.adjustmentId, value.basisRevision, value.changedSource?.let(StoredCostTarget::from),
            value.targetBasis.map(StoredCostTarget::from), value.deltas.map(StoredDestinationValue::from),
            value.transferTargetBasis.map(StoredTransferCostTarget::from), value.transferCorrection?.let(StoredTransferCorrectionBasis::from))
    }
}

@Serializable
internal data class StoredMovingAverageState(
    val calculationVersion: String,
    val unitId: DomainId,
    val scale: Int,
    val currency: Currency,
    val revision: Revision,
    val onHand: Quantity,
    val carryingValue: Money,
    val facts: List<StoredValueFact>,
    val costTargets: List<StoredCostTarget>,
    val linkedCorrectionBalances: List<StoredDestinationValue>,
    val adjustments: List<StoredCostAdjustment>,
    val eventIds: List<DomainId>,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val transferTargets: List<StoredTransferCostTarget> = emptyList(),
) {
    fun restore(): MovingAverageState {
        inventorySnapshotCheck(calculationVersion == MovingAveragePlanner.CALCULATION_VERSION ||
            calculationVersion == LinkedMovingAveragePlanner.CALCULATION_VERSION, InventorySnapshotFailure.UNSUPPORTED_VERSION)
        inventorySnapshotCheck(facts.size <= MovingAveragePlanner.MAX_EVENTS && adjustments.size <= MovingAveragePlanner.MAX_EVENTS &&
            eventIds.size <= MovingAveragePlanner.MAX_EVENTS && costTargets.size <= MovingAveragePlanner.MAX_COST_SOURCES &&
            transferTargets.size <= MovingAveragePlanner.MAX_EVENTS && linkedCorrectionBalances.size <= InventorySnapshotCodec.MAX_ARRAY_ENTRIES,
            InventorySnapshotFailure.TOO_LARGE)
        return MovingAverageState(unitId, scale, currency, revision, onHand, carryingValue, facts.map { it.restore() },
            costTargets.map { it.restore() }, linkedCorrectionBalances.map { it.restore() }, adjustments.map { it.restore() }, eventIds,
            transferTargets.map { it.restore() }).also {
                inventorySnapshotCheck(it.calculationVersion == calculationVersion, InventorySnapshotFailure.UNSUPPORTED_VERSION)
            }
    }
    companion object {
        fun from(value: MovingAverageState): StoredMovingAverageState = StoredMovingAverageState(value.calculationVersion,
            value.unitId, value.scale, value.currency, value.revision, value.onHand, value.carryingValue, value.facts.map(StoredValueFact::from),
            value.costTargets.map(StoredCostTarget::from), value.linkedCorrectionBalances.map(StoredDestinationValue::from),
            value.adjustments.map(StoredCostAdjustment::from), value.eventIds, value.transferTargets.map(StoredTransferCostTarget::from))
    }
}

@Serializable
internal data class StoredTransferValueLink(
    val transferId: DomainId, val allocationId: DomainId, val sourcePool: StoredPoolKey, val destinationPool: StoredPoolKey,
    val sourceEventId: DomainId, val destinationEventId: DomainId, val sourceRevision: Revision, val destinationRevision: Revision,
    val quantity: Quantity, val originalValue: Money,
) {
    fun restore(): OwnedTransferValueLink = OwnedTransferValueLink(transferId, allocationId, sourcePool.restore(), destinationPool.restore(),
        sourceEventId, destinationEventId, sourceRevision, destinationRevision, quantity, originalValue).also(::requireTransferLink)
    companion object {
        fun from(v: OwnedTransferValueLink): StoredTransferValueLink = StoredTransferValueLink(v.transferId, v.allocationId,
            StoredPoolKey.from(v.sourcePool), StoredPoolKey.from(v.destinationPool), v.sourceEventId, v.destinationEventId,
            v.sourceRevision, v.destinationRevision, v.quantity, v.originalValue)
    }
}
@Serializable internal data class StoredTransferCostTarget(val receiptEventId: DomainId, val targetDelta: Money) {
    fun restore(): IncomingTransferCostTarget = IncomingTransferCostTarget(receiptEventId, targetDelta)
    companion object { fun from(v: IncomingTransferCostTarget): StoredTransferCostTarget = StoredTransferCostTarget(v.receiptEventId, v.targetDelta) }
}
@Serializable internal data class StoredValuationPoolRevision(val pool: StoredPoolKey, val revision: Revision) {
    fun restore(): ValuationPoolRevision = ValuationPoolRevision(pool.restore(), revision)
    companion object { fun from(v: ValuationPoolRevision): StoredValuationPoolRevision = StoredValuationPoolRevision(StoredPoolKey.from(v.pool), v.revision) }
}
@Serializable internal data class StoredTransferCorrectionBasis(val adjustmentId: DomainId, val sourcePool: StoredPoolKey,
    val changedSource: StoredCostTarget, val pools: List<StoredValuationPoolRevision>) {
    fun restore(): TransferCorrectionBasis {
        inventorySnapshotCheck(pools.size in 1..LinkedMovingAveragePlanner.MAX_POOLS, InventorySnapshotFailure.TOO_LARGE)
        return TransferCorrectionBasis(adjustmentId, sourcePool.restore(), changedSource.restore(), pools.map { it.restore() })
    }
    companion object { fun from(v: TransferCorrectionBasis): StoredTransferCorrectionBasis = StoredTransferCorrectionBasis(v.adjustmentId,
        StoredPoolKey.from(v.sourcePool), StoredCostTarget.from(v.changedSource), v.pools.map(StoredValuationPoolRevision::from)) }
}
