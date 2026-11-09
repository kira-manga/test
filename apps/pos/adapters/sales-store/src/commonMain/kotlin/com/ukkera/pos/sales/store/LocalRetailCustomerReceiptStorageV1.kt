package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RetainedStreamedCommand
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.ReceiptEntitlement
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

internal class LocalRetailCustomerReceiptOriginalUnsupported : IllegalStateException("sales.customer_receipt.original_unsupported")

internal class LocalRetailCustomerReceiptHeadCut(
    val head: LocalRetailCustomerReceiptHeadImage,
    events: List<LocalSalesRow<LocalRetailCustomerReceiptEventV1>>,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    val events: List<LocalSalesRow<LocalRetailCustomerReceiptEventV1>> = events.toList()
    val recoveryResources: Set<RecoveryResourceKey> = recoveryResources.toSet()
}

/**
 * Genuine same-writer immutable paid-sale source. Remaining entitlement is deliberately
 * separate: the initial entitlement is the actual core-projected original Finalize AFTER.
 * Later return decisions cannot replace original sold quantities or the paid Finalize instant.
 */
internal suspend fun readRetailCustomerReceiptOriginalSource(view: CommandReadView,
    saleId: DomainId): LocalRetailCustomerReceiptOriginalSource {
    val sale = readReturnSaleSource(view, saleId)
    val prices = sale.priceSources
    if (prices?.value?.normalizedStock != true) throw LocalRetailCustomerReceiptOriginalUnsupported()
    val finalize = requireNormalizedReturnSaleSource(view, sale.sale.stored, sale.sale.value, prices.value)
    val key = LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, saleId)
    val row = finalize.writeRow(key) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(row.key == key && row.revision == Revision(1) &&
        row.schema == LocalSalesStoreProfile.schema(key.kind) && sha256(row.payload.copyBytes()) == row.digest)
    val initial = validateLocalSalesSource {
        SalesSnapshotCodec.decodeReceiptEntitlement(row.text(),
            SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, view.scope, saleId, Revision(0)))
    }
    // Both images are the immutable Finalize AFTER. The current entitlement link may
    // have advanced with later returns and must not supply original-sale provenance.
    val linkKey = commerceLinkKey(key)
    val linkRow = finalize.writeRow(linkKey) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(linkRow.key == linkKey && linkRow.revision == Revision(1) &&
        linkRow.schema == commerceLinkSchema() && sha256(linkRow.payload.copyBytes()) == linkRow.digest)
    val link = decodeLocalSalesSource(linkRow.payload, CommerceRowLinkV2.serializer())
    val producer = finalize.identity.producer
    requireLocalSalesSource(link.scope == view.scope && link.kind == key.kind && link.id == key.id &&
        link.generation == row.revision && link.digest == row.digest && link.operationId == producer.operationId &&
        link.actorId == producer.originalActorId && link.command == producer.command.name &&
        link.version == producer.command.version && link.semanticDigest == producer.semanticDigest)
    requireCommerceWrite(finalize, row)
    requireCommerceWrite(finalize, linkRow)
    commerceRecordOriginal(view, row, finalize)
    return retailCustomerReceiptOriginalSource(sale, LocalSalesRow(row, initial), finalize)
}

/**
 * Literal immutable-source builder shared with dedicated history. The caller separately
 * qualifies the complete normalized Finalize producer and historical/current authority.
 * This reads only the genuine supplied original and derives its resources; no view, authority
 * object, fabricated initial entitlement or caller-selected resource expectation is created.
 */
internal suspend fun retailCustomerReceiptOriginalSource(sale: LocalReturnSaleCut,
    initialEntitlement: LocalSalesRow<ReceiptEntitlement>,
    finalize: RetainedStreamedCommand): LocalRetailCustomerReceiptOriginalSource {
    val original = finalize.identity
    val value = sale.sale.value
    val request = LocalCollectionStockCodecV2.decode(finalize.normalized) as? LocalCollectionStockRequestV2.Finalize
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(sale.priceSources?.value?.normalizedStock == true && original.scope == value.scope &&
        original.producer.command == StockStreamingCommandsV2.COLLECTION &&
        original.producer.originalActorId == value.collectorId && request.saleId == value.id &&
        request.orderId == value.orderId && request.issueId == value.stockIssueId &&
        sale.sale.stored.key == LocalSalesStoreProfile.key(LocalSalesStoreProfile.SALE_KIND, value.id) &&
        sale.sale.stored.revision == Revision(1) &&
        sale.sale.stored.schema == LocalSalesStoreProfile.schema(LocalSalesStoreProfile.SALE_KIND) &&
        sha256(sale.sale.stored.payload.copyBytes()) == sale.sale.stored.digest &&
        sale.sale.stored.text() == SalesSnapshotCodec.encodePaidSale(value) &&
        initialEntitlement.stored.key == LocalSalesStoreProfile.key(LocalSalesStoreProfile.ENTITLEMENT_KIND, value.id) &&
        initialEntitlement.stored.revision == Revision(1) && initialEntitlement.value.revision == Revision(0) &&
        initialEntitlement.stored.schema == LocalSalesStoreProfile.schema(LocalSalesStoreProfile.ENTITLEMENT_KIND) &&
        initialEntitlement.value.scope == original.scope && initialEntitlement.value.saleId == value.id &&
        SalesSnapshotCodec.encodePaidSale(initialEntitlement.value.sale) == SalesSnapshotCodec.encodePaidSale(value) &&
        initialEntitlement.stored.text() == SalesSnapshotCodec.encodeReceiptEntitlement(initialEntitlement.value) &&
        sha256(initialEntitlement.stored.payload.copyBytes()) == initialEntitlement.stored.digest)
    requireCommerceWrite(finalize, sale.sale.stored)
    requireCommerceWrite(finalize, initialEntitlement.stored)
    val result = LocalSalesResultCodec.decode(finalize.result) as? LocalSalesResultV1.Applied
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(result.action == LocalSalesAction.SALE_FINALIZED && result.documentId == value.id)
    val portions = initialEntitlement.value.cursors.sortedBy { it.portionId.value }.map {
        requireLocalSalesSource(it.originalQuantity == it.remainingQuantity && it.originalQuantity.scaled > 0)
        LocalRetailCustomerReceiptPortionV1(it.portionId, it.originalQuantity)
    }
    validateLocalSalesSource { requireReceiptPortions(portions, LocalRetailCustomerReceiptProfileV1.MAX_ORIGINAL_PORTIONS) }
    requireLocalSalesSource(portions.size == value.quote.lines.size && portions.all { portion ->
        value.quote.lines.singleOrNull { it.portionId == portion.portionId }?.quantity == portion.quantity
    })
    val proof = initialReturnEntitlementSourceCut(sale, initialEntitlement.stored, finalize)
    val coordinates = LocalRetailCustomerReceiptSaleSourceV1(LocalRetailCustomerReceiptProfileV1.SOURCE_SCHEMA,
        LocalSalesScope.from(original.scope), value.id, value.orderId, value.collectorId, sale.sale.stored.revision,
        sale.sale.stored.schema, sale.sale.stored.digest, original.producer.operationId,
        original.producer.originalActorId, original.producer.command.name, original.producer.command.version,
        original.producer.semanticDigest, original.assignmentId, original.authorityEpoch, original.sequence,
        original.recordedAt)
    return LocalRetailCustomerReceiptOriginalSource(coordinates, portions, proof.recoveryResources)
}

/** Exact current head plus every immutable event/predecessor; no partial history projection. */
internal suspend fun findRetailCustomerReceiptHead(view: CommandReadView,
    original: LocalRetailCustomerReceiptOriginalSource): LocalRetailCustomerReceiptHeadCut? {
    val saleId = original.coordinates.saleId
    requireLocalSalesSource(original.coordinates.scope.value() == view.scope)
    val row = view.aggregate(retailCustomerReceiptHeadKey(saleId))
    val owner = view.claim(retailCustomerReceiptHeadClaim(saleId))
    val indexed = retailCustomerReceiptMembership(view, saleId)
    val retainedEvents = retailCustomerReceiptEventMembership(view, saleId)
    if (row == null) {
        // A row/source/index survivor is incomplete evidence, never an absent observation.
        requireLocalSalesSource(owner == null && indexed.isEmpty() && retainedEvents.isEmpty())
        return null
    }
    requireLocalSalesSource(owner == saleId && row.key == retailCustomerReceiptHeadKey(saleId))
    val head = retailCustomerReceiptHeadImage(row.revision, row.schema, row.payload, row.digest, view.scope, original)
    requireLocalSalesSource(indexed == head.value.eventOperationIds.toSet() && retainedEvents == indexed)
    val events = mutableListOf<LocalSalesRow<LocalRetailCustomerReceiptEventV1>>()
    val resources = original.recoveryResources.toMutableSet()
    var previous: LocalSalesRow<LocalRetailCustomerReceiptEventV1>? = null
    for ((ordinal, operationId) in head.value.eventOperationIds.withIndex()) {
        val event = readRetailCustomerReceiptEventRow(view, operationId, original)
        val value = event.value
        requireLocalSalesSource(value.after.headRevision == Revision(ordinal.toLong() + 1) &&
            value.after.eventOperationIds == head.value.eventOperationIds.take(ordinal + 1) &&
            value.beforeHeadOperationId == previous?.value?.operationId && value.beforeEventDigest == previous?.stored?.digest)
        val beforeRow = view.aggregate(retailCustomerReceiptBeforeKey(operationId))
        val before = if (previous == null) {
            requireLocalSalesSource(beforeRow == null && value.beforeHeadGeneration == null && value.beforeHeadDigest == null)
            null
        } else {
            val witness = beforeRow ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            requireLocalSalesSource(witness.key == retailCustomerReceiptBeforeKey(operationId) && witness.revision == Revision(1) &&
                witness.schema == LocalRetailCustomerReceiptProfileV1.HEAD_BEFORE_SCHEMA &&
                value.beforeHeadGeneration == previous.value.after.headRevision && witness.digest == value.beforeHeadDigest &&
                witness.payload == localSalesEncode(previous.value.after, LocalRetailCustomerReceiptHeadV1.serializer(),
                    LocalRetailCustomerReceiptProfileV1.MAX_ROW_BYTES))
            retailCustomerReceiptHeadImage(checkNotNull(value.beforeHeadGeneration), LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA,
                witness.payload, witness.digest, view.scope, original)
        }
        val producer = view.retainedCommand(operationId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireRetailCustomerReceiptEventProducer(producer, event, original, before, previous?.stored?.digest)
        resources += retailCustomerReceiptResources(saleId, value.actorId, operationId)
        events += event
        previous = event
    }
    val latest = previous ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(latest.value.after == head.value &&
        localSalesEncode(latest.value.after, LocalRetailCustomerReceiptHeadV1.serializer(), LocalRetailCustomerReceiptProfileV1.MAX_ROW_BYTES) == head.payload)
    return LocalRetailCustomerReceiptHeadCut(head, events, resources)
}

/** Complete bounded native TEXT membership; actual event rows are cross-checked separately. */
private suspend fun retailCustomerReceiptMembership(view: CommandReadView, saleId: DomainId): Set<OperationId> {
    val prefix = retailCustomerReceiptMembershipPrefix(saleId)
    val page = view.claimKeys(LocalRetailCustomerReceiptProfileV1.NAMESPACE, prefix, null,
        LocalRetailCustomerReceiptProfileV1.MAX_EVENTS + 1)
    requireLocalSalesSource(page.nextAfter == null && page.entries.size <= LocalRetailCustomerReceiptProfileV1.MAX_EVENTS)
    val operations = page.entries.map { entry ->
        requireLocalSalesSource(entry.key.namespace == LocalRetailCustomerReceiptProfileV1.NAMESPACE &&
            entry.key.key.startsWith(prefix) && entry.ownerId == saleId)
        val operation = validateLocalSalesSource { OperationId(entry.key.key.removePrefix(prefix)) }
        requireLocalSalesSource(entry.key == retailCustomerReceiptMembershipClaim(saleId, operation) && view.claim(entry.key) == saleId)
        operation
    }
    requireLocalSalesSource(operations.distinct().size == operations.size &&
        page.entries.zipWithNext().all { (first, second) -> first.key.key < second.key.key })
    return operations.toSet()
}

/**
 * Complete same-sale EVENT membership from the actual native owner, independent of mutable
 * head/claim locators. Missing capability is unavailable, never an empty or partial census.
 * Every selected point still has to match its exact native locator and canonical source.
 */
private suspend fun retailCustomerReceiptEventMembership(view: CommandReadView,
    saleId: DomainId): Set<OperationId> {
    val members = view.retailCustomerReceiptEventMembership(saleId)
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(members.size <= LocalRetailCustomerReceiptProfileV1.MAX_EVENTS &&
        members.all { it.key.kind == LocalRetailCustomerReceiptProfileV1.EVENT &&
            it.revision == Revision(1) && it.schema == LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA } &&
        members.map { it.key }.distinct().size == members.size &&
        members.zipWithNext().all { (first, second) -> first.key.id.value < second.key.id.value })
    val operations = mutableSetOf<OperationId>()
    for (member in members) {
        val row = view.aggregate(member.key) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        requireLocalSalesSource(row.key == member.key && row.revision == member.revision &&
            row.schema == member.schema && row.digest == member.digest)
        val event = retailCustomerReceiptEventMembershipValue(row.key, row.revision, row.schema,
            row.payload, row.digest, view.scope)
        requireLocalSalesSource(event.after.source.saleId == saleId &&
            member.key == retailCustomerReceiptEventKey(event.operationId) && operations.add(event.operationId))
    }
    return operations.toSet()
}

/** Shared canonical EVENT decoder; complete event producers/chronology are joined separately. */
internal fun retailCustomerReceiptEventMembershipValue(key: AggregateKey, generation: Revision,
    schema: String, payload: StoredBytes, digest: PayloadDigest,
    scope: BranchScope): LocalRetailCustomerReceiptEventV1 {
    requireLocalSalesSource(generation == Revision(1) && schema == LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA &&
        sha256(payload.copyBytes()) == digest)
    val event = decodeLocalSalesSource(payload, LocalRetailCustomerReceiptEventV1.serializer(),
        LocalRetailCustomerReceiptProfileV1.MAX_ROW_BYTES)
    requireLocalSalesSource(key == retailCustomerReceiptEventKey(event.operationId) && event.schema == schema &&
        event.after.source.scope.value() == scope)
    return event
}

internal fun retailCustomerReceiptHeadImage(generation: Revision, schema: String, payload: StoredBytes,
    digest: PayloadDigest, scope: BranchScope, original: LocalRetailCustomerReceiptOriginalSource): LocalRetailCustomerReceiptHeadImage {
    requireLocalSalesSource(generation.value in 1L..LocalRetailCustomerReceiptProfileV1.MAX_EVENTS.toLong() &&
        schema == LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA && sha256(payload.copyBytes()) == digest)
    val value = decodeLocalSalesSource(payload, LocalRetailCustomerReceiptHeadV1.serializer(), LocalRetailCustomerReceiptProfileV1.MAX_ROW_BYTES)
    requireLocalSalesSource(value.schema == schema && value.source == original.coordinates && value.source.scope.value() == scope &&
        value.headRevision == generation && value.eventOperationIds.size.toLong() == generation.value &&
        value.eventOperationIds.distinct().size == value.eventOperationIds.size && value.eventOperationIds.lastOrNull() == value.lastOperationId)
    validateLocalSalesSource {
        requireReceiptPortions(value.recordedPortions, LocalRetailCustomerReceiptProfileV1.MAX_ORIGINAL_PORTIONS)
        requireReceiptWithinOriginal(original.portions, value.recordedPortions)
    }
    return LocalRetailCustomerReceiptHeadImage(generation, schema, payload, digest, value)
}

private suspend fun readRetailCustomerReceiptEventRow(view: CommandReadView, operationId: OperationId,
    original: LocalRetailCustomerReceiptOriginalSource): LocalSalesRow<LocalRetailCustomerReceiptEventV1> {
    val key = retailCustomerReceiptEventKey(operationId)
    val row = view.aggregate(key) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireLocalSalesSource(row.key == key && row.revision == Revision(1) && row.schema == LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA &&
        sha256(row.payload.copyBytes()) == row.digest && view.claim(retailCustomerReceiptEventClaim(operationId)) == original.coordinates.saleId)
    val event = retailCustomerReceiptEventValue(operationId, row.schema, row.payload, row.digest, original)
    return LocalSalesRow(row, event)
}

/** Pure event/schema/digest invariants; historical callers still join actual original writes. */
internal fun retailCustomerReceiptEventValue(operationId: OperationId, schema: String, payload: StoredBytes,
    digest: PayloadDigest, original: LocalRetailCustomerReceiptOriginalSource): LocalRetailCustomerReceiptEventV1 {
    requireLocalSalesSource(schema == LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA && sha256(payload.copyBytes()) == digest)
    val event = decodeLocalSalesSource(payload, LocalRetailCustomerReceiptEventV1.serializer(), LocalRetailCustomerReceiptProfileV1.MAX_ROW_BYTES)
    val head = event.after
    requireLocalSalesSource(event.schema == schema && event.operationId == operationId && event.actorId == event.authorization.actorId &&
        event.authorization.generation.value < Long.MAX_VALUE && head.source == original.coordinates &&
        head.schema == LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA &&
        head.headRevision.value in 1L..LocalRetailCustomerReceiptProfileV1.MAX_EVENTS.toLong() &&
        head.eventOperationIds.size.toLong() == head.headRevision.value && head.eventOperationIds.distinct().size == head.eventOperationIds.size &&
        head.lastOperationId == operationId && head.eventOperationIds.lastOrNull() == operationId &&
        event.sourceRecoveryResources == original.recoveryResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })) &&
        event.result.saleId == original.coordinates.saleId && event.result.eventOperationId == operationId &&
        event.result.headRevision == head.headRevision && event.result.receivedAt == event.receivedAt &&
        event.result.receiptTimeZoneId == event.receiptTimeZoneId && event.result.recordedAt == event.recordedAt)
    if (head.headRevision == Revision(1)) requireLocalSalesSource(event.beforeHeadGeneration == null && event.beforeHeadDigest == null &&
        event.beforeHeadOperationId == null && event.beforeEventDigest == null)
    else requireLocalSalesSource(event.beforeHeadGeneration?.value == head.headRevision.value - 1 && event.beforeHeadDigest != null &&
        event.beforeHeadOperationId == head.eventOperationIds[head.eventOperationIds.lastIndex - 1] && event.beforeEventDigest != null)
    validateLocalSalesSource {
        requireReceiptPortions(head.recordedPortions, LocalRetailCustomerReceiptProfileV1.MAX_ORIGINAL_PORTIONS)
        requireReceiptWithinOriginal(original.portions, head.recordedPortions)
        requireReceiptWithinOriginal(original.portions, event.portions)
        retailCustomerReceiptEventView(event)
    }
    return event
}

/** Literal comparison reusable by native history; this does not validate historical grants. */
internal fun requireRetailCustomerReceiptEventProducer(producer: RetainedCommandEvidence,
    event: LocalSalesRow<LocalRetailCustomerReceiptEventV1>, original: LocalRetailCustomerReceiptOriginalSource,
    before: LocalRetailCustomerReceiptHeadImage?, beforeEventDigest: PayloadDigest?) {
    val value = event.value
    requireLocalSalesSource(producer.committed && producer.nativeOriginalProvenance == null &&
        producer.scope == original.coordinates.scope.value() && producer.operationId == value.operationId &&
        producer.originalActorId == value.actorId && producer.command == LocalRetailCustomerReceiptCodec.COMMAND &&
        producer.semanticDigest == value.semanticDigest && producer.recordedAt == value.recordedAt &&
        producer.authenticationId == value.authorization.authenticationId && producer.policyRevision == value.authorization.policyRevision &&
        producer.jobs.isEmpty() && LocalRetailCustomerReceiptCodec.normalize(producer.normalized) == producer.normalized &&
        semanticDigest(producer.scope, producer.originalActorId, producer.command, producer.normalized) == producer.semanticDigest)
    val request = LocalRetailCustomerReceiptCodec.decode(producer.normalized)
    requireLocalSalesSource(request.saleId == original.coordinates.saleId && request.observation == value.observation &&
        request.portions == value.portions && request.receivedAt == value.receivedAt && request.receiptTimeZoneId == value.receiptTimeZoneId)
    val identity = LocalRetailCustomerReceiptProductionIdentity(producer.scope, producer.originalActorId,
        producer.operationId, producer.semanticDigest)
    val proposal = retailCustomerReceiptProduction(identity, request, original, before, beforeEventDigest)
        .propose(producer.recordedAt, value.authorization) as? ProposedOutcome.Apply
        ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
    requireLocalSalesSource(proposal.result == producer.result && proposal.result == LocalRetailCustomerReceiptCodec.result(value.result) &&
        proposal.jobs.isEmpty() && proposal.mustCommitBefore == null && producer.aggregateWrites.size == proposal.changes.size &&
        producer.claimChanges.size == proposal.claims.size)
    for (change in proposal.changes) requireLocalSalesSource(producer.aggregateWrites.singleOrNull { it.key == change.key }?.let {
        it.storageGeneration == (change.expectedRevision?.next() ?: Revision(1)) && it.schema == change.schema &&
            it.payload == change.payload && it.payloadDigest == sha256(change.payload.copyBytes())
    } == true)
    for (claim in proposal.claims) requireLocalSalesSource(claim is ClaimChange.Acquire && producer.claimChanges.singleOrNull { it.key == claim.key }?.let {
        it.action == RetainedClaimAction.ACQUIRE && it.ownerId == claim.ownerId
    } == true)
    requireLocalSalesSource(producer.aggregateWrites.singleOrNull { it.key == event.stored.key }?.let {
        it.storageGeneration == event.stored.revision && it.schema == event.stored.schema && it.payload == event.stored.payload &&
            it.payloadDigest == event.stored.digest
    } == true)
}

internal suspend fun requireNewRetailCustomerReceiptEvent(view: CommandReadView, saleId: DomainId, operationId: OperationId) {
    requireLocalSalesSource(view.aggregate(retailCustomerReceiptEventKey(operationId)) == null &&
        view.aggregate(retailCustomerReceiptBeforeKey(operationId)) == null &&
        view.claim(retailCustomerReceiptEventClaim(operationId)) == null &&
        view.claim(retailCustomerReceiptMembershipClaim(saleId, operationId)) == null)
}

/** Missing event is distinct from a partial producer; never substitute a successful empty event. */
internal suspend fun retailCustomerReceiptEventAbsent(view: CommandReadView, saleId: DomainId, operationId: OperationId): Boolean {
    val row = view.aggregate(retailCustomerReceiptEventKey(operationId))
    if (row != null) return false
    requireLocalSalesSource(view.claim(retailCustomerReceiptEventClaim(operationId)) == null &&
        view.claim(retailCustomerReceiptMembershipClaim(saleId, operationId)) == null &&
        view.aggregate(retailCustomerReceiptBeforeKey(operationId)) == null)
    return true
}
