package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRelationalCodecV2
import com.ukkera.pos.inventory.store.LocalStockRelationalCommand
import com.ukkera.pos.inventory.store.LocalStockRelationalCommandsV2
import com.ukkera.pos.inventory.store.LocalStockRelationalProfileV2
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer

/**
 * CLOSED ordinary refusal of the exact manager reassignment subtype. A rejected streaming
 * proposal has no retained spool: this reader consumes only genuine native BEFORE rows and
 * their already completely joined originals, never a recreated view or employee authority.
 * Core separately requires the closed rejection capture, original manager authorization,
 * five nonbusiness mutations, literal result/resources/dependencies and final native close.
 * This historical proof does not replace the writer's current protected receiving check.
 */
internal suspend fun joinOrderReassignmentRejectionHistory(command: RuntimeHistoricalCommand): Boolean {
    if (command.command != StockStreamingCommandsV2.COLLECTION ||
        command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
    val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
    if (retained.committed || retained.nativeOriginalProvenance != null) return false
    val request = LocalCollectionStockCodecV2.decode(command.normalized) as? LocalCollectionStockRequestV2.ReassignOrder
        ?: return false
    check(LocalCollectionStockCodecV2.encode(request) == command.normalized &&
        semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest &&
        retained.scope == command.scope && retained.operationId == command.operationId && retained.command == command.command &&
        retained.originalActorId == command.actorId && retained.semanticDigest == command.semanticDigest &&
        retained.authenticationId == command.authenticationId && retained.policyRevision == command.policyRevision &&
        retained.normalized == command.normalized && retained.recordedAt == command.recordedAt &&
        retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty())

    val sources = OrderReassignmentRejectionSources(command)
    // prepare() observes this actual owner even when the submitted prior owner is wrong.
    val selected = sources.source(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, request.orderId))
    val order = selected?.let { sources.orderValue(it.row) }
    val owner = order?.ownerId ?: command.actorId
    command.requireRequirement(salesAuthorizationRequirement(SecurityAction.ORDER_REASSIGN, command.scope,
        request.orderId, request.expectedOrderRevision, command.operationId, command.semanticDigest,
        request.reasonCode, owner))
    val resources = setOf(orderResource(request.orderId), orderResource(owner), orderResource(request.recipientId),
        orderResource(DomainId(command.operationId.value)),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, request.recipientId))

    if (order == null) {
        check(command.claim(LocalSalesStoreProfile.unique("order", request.orderId)) == null &&
            sources.source(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, request.orderId)) == null)
        // Current readOrder() throws MISSING, which rolls back/defer rather than recording a
        // sales refusal. The manager fallback requirement/resources do not invent a result.
        return false
    }
    // streamRelational() validates actual fresh activation BEFORE entering reassign(). A
    // marker alone is insufficient: its genuine writer must already have fully native-joined.
    if (!sources.requireFreshStockActivation()) return false
    val cut = sources.orderCut(checkNotNull(selected), order)
    val refusal = firstOrderReassignmentRefusal(command, sources, cut, request) ?: return false
    command.requireOrderReassignmentRejection(refusal, resources)
    return true
}

/** Exact proposal precedence, after readOrder()'s successful complete source joins. Missing
 * trust/IO/invalid sources are not caught as rule denials. An arbitrary capacity label is
 * unsupported: only the actual bounded recipient-work predicate below proves this result. */
private suspend fun firstOrderReassignmentRefusal(command: RuntimeHistoricalCommand,
    sources: OrderReassignmentRejectionSources, cut: LocalOrderCut,
    request: LocalCollectionStockRequestV2.ReassignOrder): ProposedOutcome.Reject? {
    val before = cut.order.value
    if (cut.context.value.selection.activity !in setOf(LocalSalesActivity.CLOTHING, LocalSalesActivity.SUPERMARKET))
        return ProposedOutcome.Reject("sales.stock.reassignment.unsupported_activity",
            LocalSalesResultCodec.encode(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.UNSUPPORTED_ACTIVITY)))
    if (before.revision != request.expectedOrderRevision) return orderReassignmentRuleRefusal(SalesRule.REVISION_CONFLICT)
    if (before.ownerId != request.previousOwnerId) return orderReassignmentRuleRefusal(SalesRule.OWNER_REQUIRED)
    if (!before.stockRequirements.normalized || before.priceSourceReference == null)
        return orderReassignmentRuleRefusal(SalesRule.UNSUPPORTED_STOCK_TIMING)
    if (request.recipientId == before.ownerId || before.handoff != null)
        return orderReassignmentRuleRefusal(SalesRule.HANDOFF_CONFLICT)
    if (before.collectionId != null) return orderReassignmentRuleRefusal(SalesRule.PAYABLE_LOCKED)
    if (before.paidSaleId != null) return orderReassignmentRuleRefusal(SalesRule.IMMUTABLE_RECORD)
    val receiving = command.localHandoffRecipientBefore(before.id, request.recipientId)
    if (receiving == null) return orderReassignmentRuleRefusal(SalesRule.PERMISSION_DENIED)
    check(receiving.actorId == request.recipientId)
    val recipient = sources.work(request.recipientId)
    // Preserve && short-circuiting: a blocked source owner does not evaluate the recipient cut.
    if (!sources.acceptsNewWork(before.ownerId) || !sources.acceptsNewWork(request.recipientId))
        return orderReassignmentRuleRefusal(SalesRule.CLOSING_BLOCKED)
    if (recipient.value.orderIds.size >= LocalSalesStoreProfile.MAX_OPEN_WORK)
        return ProposedOutcome.Reject("sales.stock.capacity_required",
            LocalSalesResultCodec.encode(LocalSalesResultV1.Unavailable(LocalSalesUnavailable.CAPACITY_REQUIRED)))
    if (sources.source(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_REASSIGNMENT_KIND,
            DomainId(command.operationId.value))) != null)
        return orderReassignmentRuleRefusal(SalesRule.DUPLICATE_SOURCE_ALLOCATION)
    // Remaining current code applies the pure transition then serializes/posts. No retained
    // AFTER exists for a rejected spool. Do not infer later encoding/traversal capacity or an
    // arbitrary caught failure from the persisted outer code; preserve the unsupported hold.
    return null
}

private fun orderReassignmentRuleRefusal(rule: SalesRule): ProposedOutcome.Reject =
    ProposedOutcome.Reject("sales.stock.rule_rejected", LocalSalesResultCodec.encode(LocalSalesResultV1.Rejected(rule)))

/** Inert direct native source reader, confined to the active command. It implements no
 * CommandReadView and never re-prepares a handler, replays current policy or writes a row. */
private class OrderReassignmentRejectionSources(private val command: RuntimeHistoricalCommand) {
    suspend fun source(key: AggregateKey, schema: String = LocalSalesStoreProfile.schema(key.kind)): RuntimeHistoricalAggregateSource? {
        val selected = command.aggregateSource(key) ?: return null
        val row = selected.row
        check(row.key == key && row.revision.value > 0 && row.schema == schema && sha256(row.payload.copyBytes()) == row.digest)
        return selected
    }

    private suspend fun required(key: AggregateKey, schema: String = LocalSalesStoreProfile.schema(key.kind)): RuntimeHistoricalAggregateSource =
        source(key, schema) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)

    fun orderValue(row: StoredAggregate): OwnedOrder = SalesSnapshotCodec.decodeOrder(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.ORDER, command.scope, row.key.id, row.domainRevision()))

    suspend fun requireFreshStockActivation(): Boolean {
        val key = LocalStockRelationalProfileV2.activationKey(command.scope.branchId)
        val selected = command.aggregateSource(key) ?: return false
        val original = (selected.original as? RetainedOriginalV2.Streamed)?.evidence ?: return false
        if (original.identity.producer.command != LocalStockRelationalProfileV2.COMMAND) return false
        val request = LocalStockRelationalCommandsV2.decode(original.normalized) as? LocalStockRelationalCommand.InitializeFresh
            ?: return false
        check(original.identity.scope == command.scope && request.operationId == original.identity.producer.operationId &&
            LocalStockRelationalCommandsV2.encode(request) == original.normalized && selected.row.key == key &&
            selected.row.revision == Revision(1) && LocalStockRelationalCodecV2.project(command.scope, selected.row) == null)
        // The public projector validates the whole canonical activation payload. The actual
        // paired InitializeFresh original, already fully joined by core, proves fresh profile;
        // null is merely this nonindexed kind's projection, not an activation flag.
        return true
    }

    suspend fun orderCut(selected: RuntimeHistoricalAggregateSource, order: OwnedOrder): LocalOrderCut {
        val id = order.id
        check(command.claim(LocalSalesStoreProfile.unique("order", id)) == id)
        val contextRow = required(LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, id)).row
        val context = decodeLocalSalesSource(contextRow.payload, LocalOrderContext.serializer())
        check(context.scope.value() == command.scope && context.orderId == id && context.revision == contextRow.domainRevision() &&
            context.quoteRevision == order.quote.revision)
        requireSelectedQuote(context.selection, order.quote, id, context.quoteRevision, order.stockRequirements.normalized)
        if (order.stockRequirements.normalized) requireCommerceSource(selected)
        val prices = priceSources(order)
        val returns = returnPolicy(order, prices?.value?.sources.orEmpty())
        val work = work(order.ownerId)
        check((id in work.value.orderIds) == (order.paidSaleId == null))
        return LocalOrderCut(LocalSalesRow(selected.row, order), LocalSalesRow(contextRow, context), work, prices, returns)
    }

    private suspend fun requireCommerceSource(selected: RuntimeHistoricalAggregateSource) {
        val row = selected.row
        val original = (selected.original as? RetainedOriginalV2.Streamed)?.evidence
            ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
        check(original.identity.scope == command.scope && original.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
        val linked = required(commerceLinkKey(row.key), commerceLinkSchema())
        val link = decodeLocalSalesSource(linked.row.payload, CommerceRowLinkV2.serializer())
        val producer = original.identity.producer
        check(linked.original === selected.original && link.scope == command.scope && link.kind == row.key.kind && link.id == row.key.id &&
            link.generation == row.revision && link.digest == row.digest && link.operationId == producer.operationId &&
            link.actorId == producer.originalActorId && link.command == producer.command.name && link.version == producer.command.version &&
            link.semanticDigest == producer.semanticDigest)
        requireCommerceWrite(original, row)
        requireCommerceWrite(original, linked.row)
    }

    private suspend fun priceSources(order: OwnedOrder): LocalSalesRow<LocalSalesPriceSourcesV2>? {
        val reference = order.priceSourceReference ?: return null
        val selected = required(LocalSalesStoreProfile.key(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id),
            LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA)
        check(selected.row.revision == Revision(1))
        val value = requirePriceSources(selected.row.payload, command.scope, order.id, order.quote, reference,
            if (order.stockRequirements.normalized) null else order.stockRequirements.allocations, order.stockRequirements.normalizedLines)
        val quoted = requireQuoteOriginal(selected, value.originalQuoteOperationId, order, value.normalizedStock)
        check(quoted.priceSourceReference == reference)
        if (value.normalizedStock) {
            // readOrder passes normalizedLines for this profile. A normalized price attached
            // to legacy allocations needs the separate actual paid-sale/return source route.
            check(order.stockRequirements.normalized)
        } else requirePriceSources(selected.row.payload, command.scope, quoted.id, quoted.quote, reference, quoted.stockRequirements.allocations)
        return LocalSalesRow(selected.row, value)
    }

    private suspend fun returnPolicy(order: OwnedOrder, prices: List<LocalSalesPriceSourceV2>): LocalSalesRow<LocalSalesReturnPolicyCaptureV2>? {
        val reference = order.returnPolicySourceReference ?: return null
        val selected = required(AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, reference.id), LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
        check(selected.row.revision == Revision(1))
        val value = requireReturnPolicyCapture(selected.row.payload, command.scope, order.id, order.quote, reference,
            prices, order.stockRequirements.normalizedLines)
        val quoted = requireQuoteOriginal(selected, value.originalQuoteOperationId, order, true)
        check(quoted.returnPolicySourceReference == reference)
        return LocalSalesRow(selected.row, value)
    }

    /** The quote's ACTUAL already joined source producer and literal original order/context.
     * This repeats the immutable link checks, not authorization or today's catalogue pricing. */
    private suspend fun requireQuoteOriginal(selected: RuntimeHistoricalAggregateSource, operationId: OperationId,
        order: OwnedOrder, normalized: Boolean): OwnedOrder {
        val original = selected.original
        val selection: LocalOrderSelectionV1
        when (original) {
            is RetainedOriginalV2.Streamed -> {
                check(normalized && original.evidence.identity.scope == command.scope &&
                    original.evidence.identity.producer.command == StockStreamingCommandsV2.COLLECTION &&
                    original.evidence.identity.producer.operationId == operationId)
                val request = LocalCollectionStockCodecV2.decode(original.evidence.normalized)
                check(LocalCollectionStockCodecV2.encode(request) == original.evidence.normalized)
                selection = when (request) {
                    is LocalCollectionStockRequestV2.CreateOrder -> {
                        check(request.orderId == order.id && order.quote.revision == Revision(0)); request.selection
                    }
                    is LocalCollectionStockRequestV2.ReplaceUnlockedOrder -> {
                        check(request.orderId == order.id && order.quote.revision.value > 0); request.selection
                    }
                    else -> throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
                }
            }
            is RetainedOriginalV2.Legacy -> {
                val retained = original.evidence
                check(!normalized && retained.committed && retained.scope == command.scope && retained.command == LocalSalesStoreProfile.ORDER_COMMAND &&
                    retained.operationId == operationId && retained.jobs.isEmpty() && LocalOrderCommandCodec.normalize(retained.normalized) == retained.normalized)
                selection = when (val request = LocalOrderCommandCodec.decode(retained.normalized)) {
                    is LocalOrderRequestV1.Create -> {
                        check(request.orderId == order.id && order.quote.revision == Revision(0)); request.selection
                    }
                    is LocalOrderRequestV1.ReplaceUnlocked -> {
                        check(request.orderId == order.id && order.quote.revision.value > 0); request.selection
                    }
                    else -> throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.INVALID)
                }
            }
        }
        val quoteRow = writtenRow(original, LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_KIND, order.id))
        check(quoteRow.schema == LocalSalesStoreProfile.schema(quoteRow.key.kind))
        val quoted = SalesSnapshotCodec.decodeOrder(quoteRow.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
            SalesSnapshotBinding(SalesSnapshotKind.ORDER, command.scope, quoteRow.key.id,
                SalesStorageRevision.toDomain(quoteRow.storageGeneration)))
        val actor = when (original) {
            is RetainedOriginalV2.Streamed -> original.evidence.identity.producer.originalActorId
            is RetainedOriginalV2.Legacy -> original.evidence.originalActorId
        }
        check(quoted.ownerId == actor && quoted.collectionId == null && quoted.paidSaleId == null && quoted.handoff == null &&
            quoted.stockRequirements.normalized == normalized && StoredPricingSnapshotCodec.encode(quoted.quote) == StoredPricingSnapshotCodec.encode(order.quote))
        requireSelectedQuote(selection, quoted.quote, order.id, order.quote.revision, normalized)
        val contextRow = writtenRow(original, LocalSalesStoreProfile.key(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, order.id))
        check(contextRow.schema == LocalSalesStoreProfile.schema(contextRow.key.kind))
        val context = decodeLocalSalesSource(contextRow.payload, LocalOrderContext.serializer())
        check(context.revision == SalesStorageRevision.toDomain(contextRow.storageGeneration) &&
            context == LocalOrderContext(LocalSalesScope.from(command.scope), order.id, order.quote.revision, order.quote.revision, selection))
        return quoted
    }

    private suspend fun writtenRow(original: RetainedOriginalV2, key: AggregateKey): RetainedAggregateWrite = when (original) {
        is RetainedOriginalV2.Legacy -> {
            val write = original.evidence.aggregateWrites.singleOrNull { it.key == key }
                ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            write
        }
        is RetainedOriginalV2.Streamed -> {
            var row: RetainedAggregateWrite? = null
            original.evidence.frames { frame ->
                val write = (frame as? RetainedCommandFrameV2.Write)?.value ?: return@frames
                if (write.key == key) {
                    check(row == null)
                    row = write
                }
            }
            row ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
        }
    }

    suspend fun work(id: DomainId): LocalSalesRow<LocalWorkState> = root(LocalSalesStoreProfile.WORK_KIND, id,
        LocalWorkState.serializer()) { value, revision ->
        check(value.scope.value() == command.scope && value.id == id && value.revision == revision &&
            listOf(value.orderIds, value.returnIds, value.pendingClaimIds).all {
                it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size
            })
    }

    private suspend fun custodian(id: DomainId): LocalSalesRow<LocalCustodianState> = root(LocalSalesStoreProfile.CUSTODIAN_KIND,
        id, LocalCustodianState.serializer()) { value, revision ->
        check(value.scope.value() == command.scope && value.id == id && value.revision == revision)
    }.also { check(command.claim(LocalSalesStoreProfile.activeCustodian(id)) == it.value.activeSessionId) }

    private suspend fun drawer(id: DomainId): LocalSalesRow<LocalDrawerState> = root(LocalSalesStoreProfile.DRAWER_KIND, id,
        LocalDrawerState.serializer()) { value, revision ->
        check(value.scope.value() == command.scope && value.id == id && value.revision == revision &&
            (value.activeSessionId == null || value.closingTransferId == null))
        localLabel(value.label)
    }.also { check(command.claim(LocalSalesStoreProfile.activeDrawer(id)) == it.value.activeSessionId) }

    suspend fun acceptsNewWork(id: DomainId): Boolean {
        val assignment = custodian(id).value
        val sessionId = assignment.activeSessionId ?: return true
        val sessionRow = required(CashNonSaleStoreProfile.sessionKey(sessionId), CashNonSaleStoreProfile.SESSION_SCHEMA).row
        val session = SalesSnapshotCodec.decodeCashSession(sessionRow.text(),
            SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, command.scope, sessionId, sessionRow.domainRevision()))
        check(command.claim(LocalSalesStoreProfile.unique("cash_session", sessionId)) == sessionId &&
            command.claim(LocalSalesStoreProfile.unique("opening_source", session.openingSourceId)) == sessionId)
        val drawer = drawer(session.drawerId).value
        val custodian = custodian(session.custodianId).value
        work(session.custodianId)
        check(session.custodianId == id && drawer.currency == session.expectedCash.currency)
        if (session.status == CashSessionStatus.OPEN || session.status == CashSessionStatus.CLOSING)
            check(drawer.activeSessionId == sessionId && custodian.activeSessionId == sessionId && drawer.closingTransferId == null)
        else check(drawer.activeSessionId != sessionId && custodian.activeSessionId != sessionId)
        return session.status == CashSessionStatus.OPEN
    }

    private suspend fun <T> root(kind: String, id: DomainId, serializer: KSerializer<T>,
        validate: (T, Revision) -> Unit): LocalSalesRow<T> {
        val row = required(LocalSalesStoreProfile.key(kind, id)).row
        val value = decodeLocalSalesSource(row.payload, serializer)
        validate(value, row.domainRevision())
        val configured = if (kind == LocalSalesStoreProfile.WORK_KIND) LocalSalesStoreProfile.CUSTODIAN_KIND else kind
        check(command.claim(configuredClaim(configured, id)) == id)
        return LocalSalesRow(row, value)
    }
}
