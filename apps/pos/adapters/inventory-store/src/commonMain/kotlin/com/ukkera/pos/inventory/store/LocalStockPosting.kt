package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.JobIntent
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.InventoryPostedRecords
import com.ukkera.pos.inventory.InventoryRecordCodec
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.ReceiptValueOrigin
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.StockMovementReason
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** One immutable per-operation manifest; prior operations stay in the kernel's original journal. */
@Serializable internal data class StockPostingWire(
    val effects: String?, val observations: List<StockObservedWire>, val writes: List<StockPostedWriteWire>, val claims: List<StockPostedClaimWire>,
    val jobs: List<StockPostedJobWire>, val resultDigest: String,
)
@Serializable internal data class StockPostedWriteWire(val kind: String, val id: DomainId, val revision: Revision,
    val schema: String, val bytes: Int, val digest: String)
@Serializable internal data class StockPostedClaimWire(val action: String, val namespace: String, val key: String, val ownerId: DomainId)
@Serializable internal data class StockPostedJobWire(val kind: String, val bytes: Int, val digest: String)

internal fun completeStockProposal(
    context: CommandContext,
    changes: List<AggregateChange>,
    claims: List<ClaimChange>,
    result: StoredBytes,
    jobs: List<JobIntent> = emptyList(),
    effects: StoredBytes? = null,
    deadline: UtcInstant? = null,
    observations: List<StockObservedWire> = emptyList(),
): ProposedOutcome.Apply {
    localStockCheck(changes.none { it.key.kind == LocalStockStoreProfile.POSTING } && changes.map { it.key }.distinct().size == changes.size &&
        claims.map { it.key }.distinct().size == claims.size, LocalStockFailure.STORAGE_INCONSISTENT)
    localStockCheck(observations.map { it.key() }.distinct().size == observations.size, LocalStockFailure.STORAGE_INCONSISTENT)
    val manifest = StockPostingWire(effects?.copyBytes()?.decodeToString(throwOnInvalidSequence = true),
        observations.sortedWith(compareBy<StockObservedWire> { it.kind }.thenBy { it.id.value }), changes.map { StockPostedWriteWire(it.key.kind, it.key.id, (it.expectedRevision ?: Revision(0)).next(),
            it.schema, it.payload.size, sha256(it.payload.copyBytes()).value) }.sortedWith(compareBy<StockPostedWriteWire> { it.kind }.thenBy { it.id.value }),
        claims.map { StockPostedClaimWire(if (it is ClaimChange.Acquire) "acquire" else "release", it.key.namespace, it.key.key, it.ownerId) }
            .sortedWith(compareBy<StockPostedClaimWire> { it.namespace }.thenBy { it.key }),
        jobs.map { StockPostedJobWire(it.kind, it.body.size, sha256(it.body.copyBytes()).value) }, sha256(result.copyBytes()).value)
    val key = AggregateKey(LocalStockStoreProfile.POSTING, DomainId(context.operationId.value))
    val body = stockBody(manifest, StockPostingWire.serializer())
    val posting = AggregateChange(key, null, stockSchema(key.kind, body), stockRecord(context.scope, key, null,
        StockProducerWire.from(context), body))
    // Includes framed metadata/input slack as well as actual payload bytes. The kernel remains the
    // final authority for the complete envelope bound; no contribution advertises reserved capacity.
    val framed = 32_768L + result.size + (changes + posting).sumOf { it.payload.size.toLong() + 512 } +
        claims.sumOf { 128L + it.key.namespace.length + it.key.key.length } + jobs.sumOf { it.body.size.toLong() + 128 }
    localStockCheck(changes.size + 1 <= 128 && claims.size <= 128 && jobs.size <= 32 && framed <= 1_048_576,
        LocalStockFailure.BOUND_EXCEEDED)
    return ProposedOutcome.Apply(result, changes + posting, claims, jobs, deadline)
}

/** Inventory facts plus the EXACT WHOLE envelope commitment. Composite business checks remain external. */
internal fun requireStockInventoryPosting(evidence: RetainedCommandEvidence): InventoryPostedRecords? {
    localStockCheck(evidence.committed, LocalStockFailure.PRODUCER_UNAVAILABLE)
    localStockCheck(evidence.aggregateWrites.map { it.key }.distinct().size == evidence.aggregateWrites.size &&
        evidence.claimChanges.map { it.key }.distinct().size == evidence.claimChanges.size &&
        evidence.aggregateWrites.all { sha256(it.payload.copyBytes()) == it.payloadDigest } &&
        semanticDigest(evidence.scope, evidence.originalActorId, evidence.command, evidence.normalized) == evidence.semanticDigest,
        LocalStockFailure.STORAGE_INCONSISTENT)
    val postingKey = AggregateKey(LocalStockStoreProfile.POSTING, DomainId(evidence.operationId.value))
    val posting = evidence.aggregateWrites.singleOrNull { it.key == postingKey }
        ?: throw LocalStockRejected(LocalStockFailure.PRODUCER_UNAVAILABLE)
    localStockCheck(evidence.aggregateWrites.count { it.key.kind == LocalStockStoreProfile.POSTING } == 1 &&
        posting.storageGeneration == Revision(1), LocalStockFailure.STORAGE_INCONSISTENT)
    val envelope = stockDecode(posting.payload, StockRecordWire.serializer())
    requireRetainedStockEnvelope(evidence, postingKey, posting.storageGeneration, envelope)
    val manifest = stockBody(envelope.body, StockPostingWire.serializer())
    localStockCheck(manifest.observations.map { it.key() }.distinct().size == manifest.observations.size &&
        manifest.observations == manifest.observations.sortedWith(compareBy<StockObservedWire> { it.kind }.thenBy { it.id.value }) &&
        manifest.observations.all { it.kind in LocalStockStoreProfile.inventoryKinds && it.kind != LocalStockStoreProfile.VALUE_PAGE &&
            it.kind != LocalStockStoreProfile.POSTING && it.revision.value >= 1 && it.operationId != evidence.operationId }, LocalStockFailure.STORAGE_INCONSISTENT)
    manifest.observations.forEach { com.ukkera.pos.security.PayloadDigest(it.digest) }
    val expectedWrites = evidence.aggregateWrites.filter { it.key != postingKey }.map {
        StockPostedWriteWire(it.key.kind, it.key.id, it.storageGeneration, it.schema, it.payload.size, it.payloadDigest.value)
    }.sortedWith(compareBy<StockPostedWriteWire> { it.kind }.thenBy { it.id.value })
    val expectedClaims = evidence.claimChanges.map { StockPostedClaimWire(if (it.action == RetainedClaimAction.ACQUIRE) "acquire" else "release",
        it.key.namespace, it.key.key, it.ownerId) }.sortedWith(compareBy<StockPostedClaimWire> { it.namespace }.thenBy { it.key })
    val expectedJobs = evidence.jobs.map { StockPostedJobWire(it.kind, it.body.size, sha256(it.body.copyBytes()).value) }
    localStockCheck(manifest.writes == expectedWrites && manifest.claims == expectedClaims && manifest.jobs == expectedJobs &&
        manifest.resultDigest == evidence.resultHash.value && sha256(evidence.result.copyBytes()) == evidence.resultHash,
        LocalStockFailure.STORAGE_INCONSISTENT)
    val owned = evidence.aggregateWrites.filter { it.key.kind in LocalStockStoreProfile.inventoryKinds && it.key != postingKey }
    localStockCheck(owned.none { it.key.kind == LocalStockStoreProfile.SERIAL_ORIGIN } || evidence.command in setOf(
        LocalStockStoreProfile.OPENING_SERIAL, LocalStockStoreProfile.RECEIVE_SERIAL, LocalStockStoreProfile.INSPECT_SERIAL,
        LocalStockStoreProfile.RECEIVE_PO),
        LocalStockFailure.STORAGE_INCONSISTENT)
    val envelopes = owned.filter { it.key.kind != LocalStockStoreProfile.VALUE_PAGE }.associate { row ->
        val value = stockDecode(row.payload, StockRecordWire.serializer())
        requireRetainedStockEnvelope(evidence, row.key, row.storageGeneration, value)
        row.key to value
    }
    val records = manifest.effects?.let { InventoryRecordCodec.inspectEffects(it,
        InventoryScope(evidence.scope.companyId, evidence.scope.branchId), evidence.operationId) }
    if (records == null) localStockCheck(owned.isEmpty() && evidence.claimChanges.none { it.key.namespace in LocalStockStoreProfile.inventoryClaimNamespaces },
        LocalStockFailure.STORAGE_INCONSISTENT)
    else {
        val sources = evidence.claimChanges.filter { it.key.namespace == LocalStockStoreProfile.SOURCE_CLAIM }
        localStockCheck(sources.map { DomainId(it.key.key) }.toSet() == records.newSourceIds && sources.size == records.newSourceIds.size &&
            sources.all { it.action == RetainedClaimAction.ACQUIRE && it.ownerId.value == evidence.operationId.value },
            LocalStockFailure.STORAGE_INCONSISTENT)
        val positionIds = (records.movements.map { it.position } + records.safety.map { it.second.key } +
            records.serialRegistrations.map { it.second.key }).map(LocalStockStoreProfile::positionKey).toSet()
        localStockCheck(envelopes.keys.filter { it.kind == LocalStockStoreProfile.POSITION }.toSet() == positionIds, LocalStockFailure.STORAGE_INCONSISTENT)
        val pools = records.valuations.groupBy { it.pool }
        localStockCheck(envelopes.keys.filter { it.kind == LocalStockStoreProfile.POOL }.toSet() == pools.keys.map(LocalStockStoreProfile::poolKey).toSet(),
            LocalStockFailure.STORAGE_INCONSISTENT)
        for (key in positionIds) {
            val body = stockBody(envelopes.getValue(key).body, StockPositionWire.serializer())
            localStockCheck(LocalStockStoreProfile.positionKey(body.position.key(evidence.scope)) == key, LocalStockFailure.STORAGE_INCONSISTENT)
            val position = InventoryRecordCodec.decodePosition(body.encoded, body.position.key(evidence.scope))
            records.safety.lastOrNull { it.second.key == position.key }?.let {
                // Expiry may precede an original return into this retained quarantine row.
                // The predecessor crosswalk separately binds every physical quantity delta.
                localStockCheck(it.second.copy(onHand = position.onHand) == position, LocalStockFailure.STORAGE_INCONSISTENT)
            }
        }
        val newPageIds = mutableSetOf<DomainId>()
        for ((pool, values) in pools) {
            val key = LocalStockStoreProfile.poolKey(pool)
            val header = stockBody(envelopes.getValue(key).body, StockPoolWire.serializer())
            localStockCheck(header.pool.key(evidence.scope) == pool && header.revision == values.last().resultingRevision,
                LocalStockFailure.STORAGE_INCONSISTENT)
            requireStockPoolPages(header, key.id, envelopes.getValue(key).storageRevision)
            values.zipWithNext().forEach { (before, after) -> localStockCheck(before.resultingRevision == after.requiredRevision, LocalStockFailure.STORAGE_INCONSISTENT) }
            for (page in header.pages) {
                val written = owned.singleOrNull { it.key == AggregateKey(LocalStockStoreProfile.VALUE_PAGE, page.id) }
                if (written != null) {
                    localStockCheck(page.creationGeneration == envelopes.getValue(key).storageRevision && written.storageGeneration == Revision(1) &&
                        written.schema == stockPoolPageSchema(header) &&
                        written.payload.size == page.bytes && written.payloadDigest.value == page.digest, LocalStockFailure.STORAGE_INCONSISTENT)
                    newPageIds += page.id
                } else localStockCheck(page.creationGeneration < envelopes.getValue(key).storageRevision, LocalStockFailure.STORAGE_INCONSISTENT)
            }
        }
        localStockCheck(owned.filter { it.key.kind == LocalStockStoreProfile.VALUE_PAGE }.map { it.key.id }.toSet() == newPageIds,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val scope = InventoryScope(evidence.scope.companyId, evidence.scope.branchId)
        val reservations = records.reservations.groupBy { it.reservationId }
        localStockCheck(envelopes.keys.filter { it.kind == LocalStockStoreProfile.RESERVATION }.map { it.id }.toSet() == reservations.keys,
            LocalStockFailure.STORAGE_INCONSISTENT)
        for ((id, history) in reservations) {
            val body = stockBody(envelopes.getValue(LocalStockStoreProfile.reservationKey(id)).body, StockReservationWire.serializer())
            localStockCheck(body.revision == history.last().revision && body.encoded == InventoryRecordCodec.encodeReservation(history.last(), scope),
                LocalStockFailure.STORAGE_INCONSISTENT)
            history.zipWithNext().forEach { (before, after) -> localStockCheck(after.revision == before.revision.next(), LocalStockFailure.STORAGE_INCONSISTENT) }
        }
        val issues = records.issues.groupBy { it.issueId }
        localStockCheck(envelopes.keys.filter { it.kind == LocalStockStoreProfile.ISSUE }.map { it.id }.toSet() == issues.keys,
            LocalStockFailure.STORAGE_INCONSISTENT)
        for ((id, history) in issues) {
            val body = stockBody(envelopes.getValue(LocalStockStoreProfile.issueKey(id)).body, StockIssueWire.serializer())
            localStockCheck(body.revision == history.last().revision && body.encoded == InventoryRecordCodec.encodeIssue(history.last(), scope),
                LocalStockFailure.STORAGE_INCONSISTENT)
        }
        val affectedSkus = (records.movements.map { it.position.pool.skuId } + records.valuations.map { it.pool.skuId } +
            records.safety.map { it.second.key.pool.skuId } + records.serialRegistrations.map { it.second.key.pool.skuId } +
            records.reservations.flatMap { it.allocations }.map { it.position.pool.skuId } +
            records.issues.flatMap { it.allocations }.map { it.allocation.position.pool.skuId } + records.counts.map { it.position.pool.skuId }).toSet()
        localStockCheck(envelopes.keys.filter { it.kind == LocalStockStoreProfile.ITEM }.map { it.id }.toSet() == affectedSkus,
            LocalStockFailure.STORAGE_INCONSISTENT)
        val insertedItems = evidence.aggregateWrites.filter { it.key.kind == LocalStockStoreProfile.ITEM && it.storageGeneration == Revision(1) }
        val itemClaims = evidence.claimChanges.filter { it.key.namespace == LocalStockStoreProfile.ITEM_CLAIM }
        localStockCheck(itemClaims.size == insertedItems.size && itemClaims.all { claim -> claim.action == RetainedClaimAction.ACQUIRE &&
            insertedItems.any { row -> claim.key == LocalStockStoreProfile.itemClaim(row.key.id) && claim.ownerId == row.key.id } }, LocalStockFailure.STORAGE_INCONSISTENT)
        val insertedReservations = evidence.aggregateWrites.filter { it.key.kind == LocalStockStoreProfile.RESERVATION && it.storageGeneration == Revision(1) }
            .map { reservations.getValue(it.key.id).first() }
        val reservationClaims = evidence.claimChanges.filter { it.key.namespace == LocalStockStoreProfile.RESERVATION_CLAIM }
        val allocationClaims = evidence.claimChanges.filter { it.key.namespace == LocalStockStoreProfile.ALLOCATION_CLAIM }
        localStockCheck(insertedReservations.all { it.revision == Revision(1) } && reservationClaims.size == insertedReservations.size &&
            reservationClaims.all { claim -> claim.action == RetainedClaimAction.ACQUIRE && insertedReservations.any {
                claim.key == LocalStockStoreProfile.reservationClaim(it.reservationId) && claim.ownerId == it.reservationId } } &&
            allocationClaims.size == insertedReservations.sumOf { it.allocations.size } && allocationClaims.all { claim -> claim.action == RetainedClaimAction.ACQUIRE &&
                insertedReservations.any { reservation -> claim.ownerId == reservation.reservationId && reservation.allocations.any {
                    claim.key == LocalStockStoreProfile.allocationClaim(it.allocationId) } } }, LocalStockFailure.STORAGE_INCONSISTENT)
        for (key in envelopes.keys.filter { it.kind == LocalStockStoreProfile.ITEM }) {
            val item = stockBody(envelopes.getValue(key).body, StockItemWire.serializer())
            requireStockItemIndex(item, evidence.scope, key.id)
        }
    }
    return records
}

internal fun requireRetainedStockEnvelope(evidence: RetainedCommandEvidence, key: AggregateKey, revision: Revision, envelope: StockRecordWire) {
    localStockCheck(envelope.version == stockRecordVersion(key.kind, envelope.body) &&
        evidence.aggregateWrites.singleOrNull { it.key == key }?.schema == LocalStockStoreProfile.schema(key.kind, envelope.version) &&
        envelope.scope.restore() == evidence.scope && envelope.kind == key.kind && envelope.id == key.id &&
        envelope.storageRevision == revision && envelope.producer.operationId == evidence.operationId &&
        envelope.producer.commandKey() == evidence.command && envelope.producer.actorId == evidence.originalActorId &&
        envelope.producer.digest == evidence.semanticDigest.value && revision.value >= 1 &&
        (if (revision == Revision(1)) envelope.previous == null else envelope.previous != null &&
            envelope.previous.revision.next() == revision && envelope.previous.operationId != evidence.operationId), LocalStockFailure.STORAGE_INCONSISTENT)
    envelope.previous?.let { com.ukkera.pos.security.PayloadDigest(it.digest) }
}

internal fun <T> retainedBody(evidence: RetainedCommandEvidence, key: AggregateKey, serializer: kotlinx.serialization.KSerializer<T>): T {
    val row = evidence.aggregateWrites.singleOrNull { it.key == key } ?: throw LocalStockRejected(LocalStockFailure.STORAGE_INCONSISTENT)
    val envelope = stockDecode(row.payload, StockRecordWire.serializer())
    requireRetainedStockEnvelope(evidence, key, row.storageGeneration, envelope)
    return stockBody(envelope.body, serializer)
}
