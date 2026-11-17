package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.*

/** Exact no-unpriced-custody branch of the ordinary receipt guard at the original
 * BEFORE cut. Every prospective identity and permanent claim is actually read.
 * Present v1/v2 custody needs its own whole producer and is held here; it is never
 * converted into absent stock, an accepting default or a recorded business refusal. */
internal suspend fun originalLegacyReceiptArrivalGuards(before: BusinessFactRead,
    identities: List<ArrivalReceiptIdentity>): ArrivalReceiptGuards {
    val resources = mutableSetOf<RecoveryResourceKey>()
    suspend fun absent(key: AggregateKey, claim: ClaimKey?) {
        check(before.aggregate(key) == null) { "Original unpriced custody producer required" }
        check(claim == null || before.claim(claim) == null) { "Original unpriced custody claim producer required" }
        resources += RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, key.id)
    }
    for (identity in identities) {
        check(identity.serialId == null && identity.serialCode == null) { "Original serial receipt producer required" }
        val sourceV2 = arrivalKeyV2(LocalUnpricedArrivalProfileV2.SOURCE_IDENTITY, identity.sourceId)
        absent(sourceV2, arrivalSourceClaimV2(identity.sourceId))
        absent(foreignArrivalKeyV2(sourceV2), arrivalSourceClaim(identity.sourceId))
        check(before.claim(arrivalLineClaim(identity.lineId)) == null) { "Original unpriced receipt line required" }
        identity.lotId?.let { lot ->
            val key = arrivalLotKeyV2(identity.skuId, lot)
            absent(key, arrivalLotClaimV2(key))
            val foreign = arrivalLotKey(identity.skuId, lot)
            absent(foreign, arrivalLotClaim(foreign))
        }
        resources += RecoveryResourceKey(RecoveryResourceKind.PURCHASE_CUSTODY, identity.lineId)
    }
    return ArrivalReceiptGuards(false, emptyMap(), resources)
}
