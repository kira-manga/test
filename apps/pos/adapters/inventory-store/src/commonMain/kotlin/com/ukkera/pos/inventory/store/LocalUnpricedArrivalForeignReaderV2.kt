package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.types.DomainId

/** Current v1 custody keeps its own complete reader. At an original v2 cut, absent
 * legacy coordinates are genuine native absence; present unsupported legacy history
 * remains unavailable, never a converted v2 row or invented conflict owner. */
internal class ArrivalForeignReaderV2(stock: LocalStockRepository, view: CommandReadView?,
    private val before: RuntimeHistoricalCommand?) {
    private val current = view?.let { stock.unpricedArrivals(it) }
    private val absentKeys = linkedSetOf<AggregateKey>()

    suspend fun row(key: AggregateKey): ArrivalRow? {
        current?.let { return it.row(key) }
        val command = checkNotNull(before)
        check(key.kind in arrivalKinds)
        check(command.aggregateSource(key) == null) { "Legacy custody original history integration unavailable" }
        val claim = when (key.kind) {
            LocalUnpricedArrivalProfileV1.SOURCE_IDENTITY -> arrivalSourceClaim(key.id)
            LocalUnpricedArrivalProfileV1.LOT_GUARD -> arrivalLotClaim(key)
            LocalUnpricedArrivalProfileV1.SERIAL_GUARD -> null
            else -> arrivalDocumentClaim(key.kind, key.id)
        }
        check(claim == null || command.claim(claim) == null)
        absentKeys += key
        return null
    }

    suspend fun serialCode(code: String): ArrivalRow? {
        current?.let { return it.serialCode(code) }
        row(arrivalSerialCodeKey(code))
        check(checkNotNull(before).claim(arrivalSerialCodeClaim(code)) == null)
        return null
    }

    suspend fun serialId(id: DomainId): ArrivalRow? {
        current?.let { return it.serialId(id) }
        row(arrivalSerialIdKey(id))
        check(checkNotNull(before).claim(arrivalSerialIdClaim(id)) == null)
        return null
    }

    suspend fun lot(skuId: DomainId, lotId: DomainId): ArrivalRow? {
        current?.let { return it.lot(skuId, lotId) }
        return row(arrivalLotKey(skuId, lotId))
    }

    suspend fun arrival(id: DomainId): ArrivalCut? {
        current?.let { return it.arrival(id) }
        row(arrivalKey(LocalUnpricedArrivalProfileV1.ARRIVAL, id))
        row(arrivalKey(LocalUnpricedArrivalProfileV1.STATE, id))
        return null
    }

    fun resources(): Set<RecoveryResourceKey> = current?.resources() ?: absentKeys.map {
        RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, it.id)
    }.toSet()
}
