package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.inventory.StockPosition
import com.ukkera.pos.inventory.StockOwnership
import com.ukkera.pos.inventory.StockSerialRegistrationConfirmation
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** Exact local code bytes; never trim, case-fold or silently normalize a different identity. */
internal fun requireStockSerialCode(code: String) {
    localStockCheck(code.isNotEmpty() && code.length <= 128 && code.encodeToByteArray(throwOnInvalidSequence = true).size <= 256 &&
        code == code.trim() && code.none { it.code < 32 || it.code == 127 }, LocalStockFailure.INVALID_SERIAL)
}

internal fun stockSerialUnit(quantity: Quantity): Quantity = Quantity(quantity.unitId, when (quantity.scale) {
    0 -> 1L; 1 -> 10L; 2 -> 100L; 3 -> 1_000L; 4 -> 10_000L; 5 -> 100_000L; 6 -> 1_000_000L
    else -> throw LocalStockRejected(LocalStockFailure.INVALID_UNIT)
}, quantity.scale)

@Serializable internal data class StockSerialRegistrationWire(
    val scope: StockScopeWire, val serialId: DomainId, val skuId: DomainId, val serialCode: String,
    val registrationId: DomainId, val revision: Revision, val issuerId: DomainId, val provenanceId: DomainId,
    val validFrom: UtcInstant, val validUntil: UtcInstant?, val sources: List<StockSourceStampWire>,
) {
    fun restore(): LocalStockSerialRegistration = LocalStockSerialRegistration(scope.restore(), serialId, skuId, serialCode,
        registrationId, revision, issuerId, provenanceId, validFrom, validUntil, sources.map { it.restore() })
    companion object {
        fun from(v: LocalStockSerialRegistration): StockSerialRegistrationWire = StockSerialRegistrationWire(StockScopeWire.from(v.scope),
            v.serialId, v.skuId, v.serialCode, v.registrationId, v.revision, v.issuerId, v.provenanceId, v.validFrom, v.validUntil,
            v.sources.sortedWith(compareBy<LocalStockSourceStamp> { it.key.kind }.thenBy { it.key.id.value }).map(StockSourceStampWire::from))
    }
}

/** Original custody fields never change; only a pending qualification may advance once. */
@Serializable internal data class StockSerialWire(
    val serialId: DomainId, val serialCode: String, val skuId: DomainId, val receiptId: DomainId, val lineId: DomainId,
    val sourceId: DomainId, val originalPosition: StockPositionRefWire, val baseQuantity: Quantity,
    val recordedBy: DomainId, val receivedAt: UtcInstant,
    val registration: StockSerialRegistrationWire?, val registeredAt: UtcInstant?,
)

internal fun requireSerialRegistration(
    registration: LocalStockSerialRegistration, scope: BranchScope, serialId: DomainId, skuId: DomainId, code: String, now: UtcInstant,
) {
    localStockCheck(registration.scope == scope && registration.serialId == serialId && registration.skuId == skuId &&
        registration.serialCode == code, LocalStockFailure.STORAGE_INCONSISTENT)
    localStockCheck(now >= registration.validFrom && (registration.validUntil?.let { now < it } ?: true), LocalStockFailure.BASIS_EXPIRED)
}

internal fun serialRegistrationResources(registration: LocalStockSerialRegistration): Set<RecoveryResourceKey> =
    sourceResources(registration.sources) + setOf(
        RecoveryResourceKey(RecoveryResourceKind.STOCK, registration.serialId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, registration.registrationId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, registration.issuerId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, registration.provenanceId),
    )

internal fun LocalStockSerialRegistration.confirmation(): StockSerialRegistrationConfirmation = StockSerialRegistrationConfirmation(
    InventoryScope(scope.companyId, scope.branchId), skuId, serialId, registrationId)

internal suspend fun stockSerial(repository: LocalStockRepository, view: CommandReadView, id: DomainId): LocalStockRecord<StockSerialWire>? =
    repository.record(view, LocalStockStoreProfile.serialKey(id), StockSerialWire.serializer()).also { requireStockSerialClaims(view, id, it) }

internal suspend fun requireStockSerialClaims(view: CommandReadView, id: DomainId, row: LocalStockRecord<StockSerialWire>?) {
    val owner = view.claim(LocalStockStoreProfile.serialClaim(id))
    localStockCheck(if (row == null) owner == null else owner == id, LocalStockFailure.STORAGE_INCONSISTENT)
    row?.value?.let { serial ->
        requireStockSerialCode(serial.serialCode)
        localStockCheck(serial.serialId == id && serial.skuId == serial.originalPosition.skuId &&
            serial.originalPosition.serialId == id && serial.baseQuantity == stockSerialUnit(serial.baseQuantity) &&
            view.claim(LocalStockStoreProfile.serialCodeClaim(serial.serialCode)) == id &&
            (serial.registration == null) == (serial.registeredAt == null), LocalStockFailure.STORAGE_INCONSISTENT)
        serial.registration?.let { registration ->
            val restored = registration.restore()
            localStockCheck(registration == StockSerialRegistrationWire.from(restored), LocalStockFailure.STORAGE_INCONSISTENT)
            requireSerialRegistration(restored, view.scope, id, serial.skuId, serial.serialCode, requireNotNull(serial.registeredAt))
        }
    }
}

internal fun requireStockSerialPosition(serial: StockSerialWire, position: StockPosition) {
    localStockCheck(position.key.serialId == serial.serialId && position.key.pool.skuId == serial.skuId &&
        position.key.lotId == serial.originalPosition.lotId &&
        (position.key.pool.ownership as? StockOwnership.SupplierOwned)?.supplierId == serial.originalPosition.supplierId &&
        position.onHand.unitId == serial.baseQuantity.unitId && position.onHand.scale == serial.baseQuantity.scale &&
        position.serialRegistrationId == serial.registration?.registrationId,
        LocalStockFailure.STORAGE_INCONSISTENT)
}
