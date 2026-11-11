package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.sales.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** An ordinary collection/link CAS pair. It neither writes stock nor confers a physical right. */
internal fun normalizedPaymentLink(context: CommandContext, change: AggregateChange,
    before: StoredAggregate, old: StoredAggregate): AggregateChange {
    check(change.key == before.key && change.key.kind == LocalSalesStoreProfile.COLLECTION_KIND &&
        change.expectedRevision == before.revision && old.key == commerceLinkKey(before.key) && old.schema == commerceLinkSchema())
    val previous = localSalesDecode(old.payload, CommerceRowLinkV2.serializer())
    check(previous.scope == context.scope && previous.kind == before.key.kind && previous.id == before.key.id &&
        previous.generation == before.revision && previous.digest == before.digest)
    val value = CommerceRowLinkV2(context.scope, change.key.kind, change.key.id, before.revision.next(),
        sha256(change.payload.copyBytes()), context.operationId, context.originalActorId, context.key.name,
        context.key.version, context.semanticDigest)
    return AggregateChange(old.key, old.revision, commerceLinkSchema(), localSalesEncode(value, CommerceRowLinkV2.serializer()))
}

internal fun normalizedCollection(row: StoredAggregate, scope: BranchScope): CollectionLock =
    SalesSnapshotCodec.decodeHistoricalCollection(row.text(),
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, scope, row.key.id, row.domainRevision()))

/** Pure finite original interpretation. Actual original qualification is a separate owner-bound traversal below. */
internal class NormalizedPaymentSources(private val observed: StockReadObservations) {
    private val rows = mutableSetOf<AggregateKey>()
    private val absences = mutableSetOf<AggregateKey>()
    private val claims = mutableSetOf<ClaimKey>()
    fun current(key: AggregateKey, schema: String): StreamedSourceObservationV2.Current {
        rows += key
        val row = observed.current(key) ?: error("sales.stock.payment_source_missing")
        check(row.schema == schema && row.generation.value > 0 && sha256(row.payload.copyBytes()) == row.payloadHash)
        return row
    }
    fun claim(key: ClaimKey, owner: DomainId?) {
        claims += key
        val source = observed.claim(key) ?: error("sales.stock.payment_claim_missing")
        check(source.ownerId == owner)
    }
    fun absent(key: AggregateKey) { absences += key; check(observed.absent(key)) }
    fun image(key: AggregateKey, schema: String, generation: Revision, payload: StoredBytes): StreamedSourceObservationV2.Current =
        current(key, schema).also { check(it.generation == generation && it.payload == payload) }
    fun link(row: StreamedSourceObservationV2.Current): Pair<StreamedSourceObservationV2.Current, CommerceRowLinkV2> {
        val source = current(commerceLinkKey(row.key), commerceLinkSchema())
        val link = localSalesDecode(source.payload, CommerceRowLinkV2.serializer())
        check(link.scope == observed.scope && link.kind == row.key.kind && link.id == row.key.id &&
            link.generation == row.generation && link.digest == row.payloadHash)
        return source to link
    }
    fun collection(collection: CollectionLock, order: OwnedOrder, cash: CashSession): StreamedSourceObservationV2.Current {
        check(collection.stockRequirements.normalized && order.stockRequirements.normalized)
        val collectionRow = image(AggregateKey(LocalSalesStoreProfile.COLLECTION_KIND, collection.id),
            LocalSalesStoreProfile.schema(LocalSalesStoreProfile.COLLECTION_KIND), collection.revision.next(),
            originalBytes(SalesSnapshotCodec.encodeCollection(collection)))
        val orderRow = image(AggregateKey(LocalSalesStoreProfile.ORDER_KIND, order.id), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.ORDER_KIND),
            order.revision.next(), originalBytes(SalesSnapshotCodec.encodeOrder(order)))
        image(CashNonSaleStoreProfile.sessionKey(cash.id), CashNonSaleStoreProfile.SESSION_SCHEMA, cash.revision.next(),
            originalBytes(SalesSnapshotCodec.encodeCashSession(cash)))
        link(collectionRow); link(orderRow)
        val contextRow = current(AggregateKey(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, order.id), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.ORDER_CONTEXT_KIND))
        val context = localSalesDecode(contextRow.payload, LocalOrderContext.serializer())
        check(context.scope.value() == observed.scope && context.orderId == order.id && context.revision.next() == contextRow.generation &&
            context.quoteRevision == order.quote.revision)
        requireSelectedQuote(context.selection, order.quote, order.id, context.quoteRevision, normalized = true)
        val prices = order.priceSourceReference?.let { reference ->
            val price = current(AggregateKey(LocalSalesStoreProfile.PRICE_SOURCES_KIND, reference.id), LocalSalesStoreProfile.PRICE_SOURCES_SCHEMA)
            check(price.generation == Revision(1))
            requirePriceSources(price.payload, observed.scope, order.id, order.quote, reference,
                normalizedLines = checkNotNull(order.stockRequirements.normalizedLines))
        }
        order.returnPolicySourceReference?.let { reference ->
            val capture = current(AggregateKey(LocalSalesReturnPolicyCaptureCodecV2.KIND, reference.id), LocalSalesReturnPolicyCaptureCodecV2.SCHEMA)
            check(capture.generation == Revision(1))
            requirePaymentReturnPolicyCapture(capture.payload, observed.scope, order, checkNotNull(prices))
        }
        val workRow = current(AggregateKey(LocalSalesStoreProfile.WORK_KIND, order.ownerId), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.WORK_KIND))
        val work = localSalesDecode(workRow.payload, LocalWorkState.serializer())
        check(work.scope.value() == observed.scope && work.id == order.ownerId && work.revision.next() == workRow.generation &&
            order.id in work.orderIds && listOf(work.orderIds, work.returnIds, work.pendingClaimIds).all {
                it.size <= LocalSalesStoreProfile.MAX_OPEN_WORK && it.distinct().size == it.size })
        check(order.ownerId == collection.collectorId && cash.custodianId == collection.collectorId)
        val drawerRow = current(AggregateKey(LocalSalesStoreProfile.DRAWER_KIND, cash.drawerId), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.DRAWER_KIND))
        val drawer = localSalesDecode(drawerRow.payload, LocalDrawerState.serializer())
        check(drawer.scope.value() == observed.scope && drawer.id == cash.drawerId && drawer.revision.next() == drawerRow.generation &&
            drawer.currency == cash.expectedCash.currency && drawer.activeSessionId == cash.id && drawer.closingTransferId == null)
        localLabel(drawer.label)
        val custodianRow = current(AggregateKey(LocalSalesStoreProfile.CUSTODIAN_KIND, cash.custodianId), LocalSalesStoreProfile.schema(LocalSalesStoreProfile.CUSTODIAN_KIND))
        val custodian = localSalesDecode(custodianRow.payload, LocalCustodianState.serializer())
        check(custodian.scope.value() == observed.scope && custodian.id == cash.custodianId && custodian.revision.next() == custodianRow.generation &&
            custodian.activeSessionId == cash.id && cash.status == CashSessionStatus.OPEN)
        claim(configuredClaim(LocalSalesStoreProfile.DRAWER_KIND, cash.drawerId), cash.drawerId)
        claim(configuredClaim(LocalSalesStoreProfile.CUSTODIAN_KIND, cash.custodianId), cash.custodianId)
        claim(LocalSalesStoreProfile.activeDrawer(cash.drawerId), cash.id)
        claim(LocalSalesStoreProfile.activeCustodian(cash.custodianId), cash.id)
        claim(LocalSalesStoreProfile.unique("cash_session", cash.id), cash.id)
        claim(LocalSalesStoreProfile.unique("opening_source", cash.openingSourceId), cash.id)
        claim(LocalSalesStoreProfile.unique("order", order.id), order.id)
        claim(LocalSalesStoreProfile.unique("collection", collection.id), order.id)
        for (attempt in collection.attempts) {
            claim(LocalSalesStoreProfile.unique("payment_attempt", attempt.id), collection.id)
            if (attempt.payment != null) claim(LocalSalesStoreProfile.unique("payment_record", attempt.id), collection.id)
        }
        return collectionRow
    }
    /** Interpret the exact original catalog row; a tender-shaped marker cannot replace it. */
    fun tender(tender: Tender, policies: HistoricalSalesTenderPolicySourceV1) {
        if (tender == Tender.Cash) return
        val external = tender as Tender.External
        val row = current(LocalSalesTenderPolicyProfileV1.key(external.tenderId), LocalSalesTenderPolicyProfileV1.SCHEMA)
        val decoded = policies.decode(observed.scope, row)
            ?: error("sales.stock.payment_tender_source_required")
        check(decoded.scope == observed.scope && decoded.tender == external &&
            decoded.recoveryResources == setOf(recordingAuthority(external.tenderId))) { "sales.stock.payment_tender_source_mismatch" }
    }
    fun startVacancy(request: LocalCollectionRequestV3.Start, requirement: LocalPhysicalJournalRequirementV1) {
        claim(LocalSalesStoreProfile.unique("payment_attempt", request.attemptId), null)
        claim(LocalSalesStoreProfile.unique("refund_attempt", request.attemptId), null)
        claim(CashNonSaleStoreProfile.cashSourceClaim(request.attemptId), null)
        absent(AggregateKey(LocalSalesStoreProfile.MOVEMENT_KIND, request.attemptId))
        for (operation in listOf(requirement.recordingOperationId, requirement.resolutionOperationId)) {
            absent(ReservedOperations.key(operation))
            absent(retailJournalApplicationKeyV3(operation))
            claim(retailJournalOperationClaimV3(operation), null)
        }
        absent(retailJournalSlotKeyV3(requirement.recordingOperationId))
        absent(retailJournalFactKeyV3(LocalJournalFactPhaseV3.ORIGINAL, requirement.recordingOperationId))
        absent(retailJournalFactKeyV3(LocalJournalFactPhaseV3.RESOLUTION, requirement.resolutionOperationId))
        claim(retailJournalAttemptClaimV3(request.attemptId), null)
        for (allocation in listOf(requirement.masterAllocationId, requirement.clientAllocationId)) {
            absent(AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, allocation))
            claim(retailJournalAllocationClaimV3(allocation), null)
        }
    }
    fun applicationVacancy(attemptId: DomainId) {
        claim(LocalSalesStoreProfile.unique("payment_record", attemptId), null)
        claim(CashNonSaleStoreProfile.cashSourceClaim(attemptId), null)
        absent(AggregateKey(LocalSalesStoreProfile.MOVEMENT_KIND, attemptId))
    }
    fun advanceLink(collectionRow: StreamedSourceObservationV2.Current, producer: StreamedProducerIdentityV2,
        collectionWrite: AggregateChange, changes: List<AggregateChange>): AggregateKey {
        val (old, _) = link(collectionRow)
        check(collectionWrite.key == collectionRow.key && collectionWrite.expectedRevision == collectionRow.generation)
        val expected = CommerceRowLinkV2(observed.scope, collectionRow.key.kind, collectionRow.key.id, collectionRow.generation.next(),
            sha256(collectionWrite.payload.copyBytes()), producer.operationId, producer.originalActorId,
            producer.command.name, producer.command.version, producer.semanticDigest)
        val change = changes.singleOrNull { it.key == old.key } ?: error("sales.stock.payment_link_missing")
        check(change.expectedRevision == old.generation && change.schema == commerceLinkSchema() &&
            change.payload == localSalesEncode(expected, CommerceRowLinkV2.serializer()))
        return old.key
    }
    fun finish(stock: Boolean) {
        check(observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>().filter { it.key.kind !in StockRelationalKindsV2.all }
            .map { it.key }.toSet() == rows && observed.sources.filterIsInstance<StreamedSourceObservationV2.CurrentAbsent>()
            .map { it.key }.toSet() == absences && observed.sources.filterIsInstance<StreamedSourceObservationV2.Claim>()
            .filter { it.key.namespace !in LocalStockStoreProfile.inventoryClaimNamespaces }.map { it.key }.toSet() == claims)
        if (!stock) check(observed.sources.none { it is StreamedSourceObservationV2.StockMember || it is StreamedSourceObservationV2.StockRange ||
            it is StreamedSourceObservationV2.Current && it.key.kind in StockRelationalKindsV2.all ||
            it is StreamedSourceObservationV2.Claim && it.key.namespace in LocalStockStoreProfile.inventoryClaimNamespaces })
    }
}

/** Logical-line conservation over literal per-ID allocations, not a manufactured StockCut. */
internal fun requireNormalizedPaymentDemand(collection: CollectionLock, stock: LocalStockPaymentCut) {
    val lines = checkNotNull(collection.stockRequirements.normalizedLines)
    check(stock.header.id == collection.reservationId && stock.header.binding == CollectionBinding(collection.orderId,
        collection.quote.revision, collection.id, collection.collectorId, CollectionExposure.UNKNOWN))
    val byLine = lines.associateBy { it.portionId }
    val totals = lines.associate { it.portionId to Quantity(it.quantity.unitId, 0, it.quantity.scale) }.toMutableMap()
    for (record in stock.allocations) {
        val a = record.allocation; val line = byLine[a.lineId] ?: error("sales.stock.unrequested_line")
        check(record.reservationId == stock.header.id && a.position.pool.skuId == line.skuId && a.position.pool.warehouseId == line.warehouseId &&
            a.position.pool.ownership == StockOwnership.CompanyOwned && a.position.custody == StockCustody.RELEASED &&
            a.position.serialId == null && a.quantity.scaled > 0)
        totals[a.lineId] = totals.getValue(a.lineId) + a.quantity
    }
    check(stock.allocations.size.toLong() == stock.header.allocationCount && lines.all { totals.getValue(it.portionId) == it.quantity })
}

/** Static qualification only; actual source/original identities still need the traversal below. */
internal fun requireNormalizedPaymentStartOriginal(original: RetainedCommandEvidence,
    tenderPolicies: HistoricalSalesTenderPolicySourceV1 = UnavailableHistoricalSalesTenderPolicySourceV1): LocalRetailJournalSlotV3 {
    check(original.command == LocalFinancialJournalProfileV3.COLLECTION_COMMAND)
    val result = LocalFinancialResultCodecV3.decode(original.result) as? LocalFinancialResultV3.AttemptStarted
        ?: error("sales.stock.payment_start_required")
    val write = original.aggregateWrites.singleOrNull { it.key == retailJournalSlotKeyV3(result.target.recordingOperationId) }
        ?: error("sales.stock.payment_slot_missing")
    check(write.storageGeneration == Revision(1) && write.schema == LocalFinancialJournalProfileV3.SLOT_SCHEMA)
    val slot = localSalesDecode(write.payload, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES)
    requireRetailStartEvidenceV3(original, slot, tenderPolicies)
    check(StockReadEvidenceV1.read(original) != null)
    return slot
}

/** Genuine original-pair equality, never a copied identity standing in for the owner's read. */
private fun requireSamePaymentOriginal(actual: RetainedCommandEvidence, expected: RetainedCommandEvidence) {
    check(actual.scope == expected.scope && actual.operationId == expected.operationId && actual.originalActorId == expected.originalActorId &&
        actual.command == expected.command && actual.assignmentId == expected.assignmentId && actual.authorityEpoch == expected.authorityEpoch &&
        actual.sequence == expected.sequence && actual.recordedAt == expected.recordedAt && actual.semanticDigest == expected.semanticDigest &&
        actual.code == expected.code && actual.normalized == expected.normalized && actual.result == expected.result &&
        actual.resultHash == expected.resultHash && actual.previousJournalHash == expected.previousJournalHash && actual.journalHash == expected.journalHash &&
        actual.nativeOriginalProvenance == expected.nativeOriginalProvenance && actual.aggregateWrites.size == expected.aggregateWrites.size &&
        actual.aggregateWrites.zip(expected.aggregateWrites).all { (a, b) -> a.key == b.key && a.storageGeneration == b.storageGeneration &&
            a.schema == b.schema && a.payload == b.payload && a.payloadDigest == b.payloadDigest } &&
        actual.claimChanges.size == expected.claimChanges.size && actual.claimChanges.zip(expected.claimChanges).all { (a, b) ->
            a.key == b.key && a.ownerId == b.ownerId && a.action == b.action } && actual.jobs.size == expected.jobs.size &&
        actual.jobs.zip(expected.jobs).all { (a, b) -> a.kind == b.kind && a.body == b.body })
    if (actual.nativeOriginalProvenance == null)
        check(actual.authenticationId == expected.authenticationId && actual.policyRevision == expected.policyRevision)
}

private fun requirePaymentSourceWrite(original: RetainedCommandEvidence, source: StreamedSourceObservationV2.Current) {
    val write = original.aggregateWrites.singleOrNull { it.key == source.key } ?: error("sales.stock.payment_original_write_missing")
    check(write.storageGeneration == source.generation && write.schema == source.schema && write.payload == source.payload &&
        write.payloadDigest == source.payloadHash)
}
/** Finite nonstock source selection for ONE financial original. Each selection comes
 * from an actual original lookup. A repeated producer must match its complete retained
 * identity/body before its rows join the group; no producer pass is inferred here. */
private class PaymentStreamedSources {
    private class Group(val original: RetainedStreamedCommand) {
        val rows = linkedMapOf<AggregateKey, StreamedSourceObservationV2.Current>()
    }
    private val groups = linkedMapOf<OperationId, Group>()

    fun select(original: RetainedStreamedCommand, vararg rows: StreamedSourceObservationV2.Current) {
        check(rows.isNotEmpty())
        val group = groups.getOrPut(original.identity.producer.operationId) { Group(original) }
        val previous = group.original
        check(previous.identity == original.identity && previous.journalHash == original.journalHash &&
            previous.normalized == original.normalized && previous.result == original.result)
        for (row in rows) {
            val existing = group.rows.put(row.key, row)
            check(existing == null || existing == row)
        }
    }

    suspend fun qualify(view: CommandReadView) {
        try {
            for (group in groups.values) {
                // Complete producer proof still precedes the selected-write proof. Every
                // frame must return normally; a matching early write proves no completeness.
                commerceRepository(view).requireRelationalProducer(view, group.original)
                val found = mutableSetOf<AggregateKey>()
                group.original.frames { frame ->
                    val write = (frame as? RetainedCommandFrameV2.Write)?.value ?: return@frames
                    val row = group.rows[write.key] ?: return@frames
                    check(found.add(write.key) && write.storageGeneration == row.generation &&
                        write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.payloadHash)
                }
                check(found == group.rows.keys) { "sales.stock.payment_original_write_missing" }
            }
        } finally { groups.clear() }
    }
}

internal class NormalizedPaymentOriginCapacity : IllegalStateException("sales.stock.payment_origin_capacity_required")

/** Real forwarding decoration, tied to one finite original proof. It supplies no synthetic row or grant. */
private class NormalizedPaymentOriginView(val borrowed: CommandReadView, val traversal: NormalizedPaymentTraversal) : CommerceStockView {
    override val commerceStock: LocalStockRepository get() = commerceRepository(borrowed)
    override val commerceTenderPolicies: HistoricalSalesTenderPolicySourceV1 get() = commerceTenderPolicies(borrowed)
    override val scope: BranchScope get() = borrowed.scope
    override suspend fun stockRelationalAccess(): StockRelationalAccess = borrowed.requireStockRelational()
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = borrowed.aggregate(key)
    override suspend fun claim(key: ClaimKey): DomainId? = borrowed.claim(key)
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage = borrowed.aggregateKeys(kind, after, limit)
    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage = borrowed.claimKeys(namespace, prefix, after, limit)
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = borrowed.retainedCommand(operationId)
    override suspend fun recordCommerceOriginal(row: StoredAggregate, original: RetainedStreamedCommand) = commerceRecordOriginal(borrowed, row, original)
    override suspend fun observeRecoveryResources(resources: Set<com.ukkera.pos.recovery.RecoveryResourceKey>) {
        (borrowed as? LocalCommerceResourceCollector)?.observeRecoveryResources(resources)
    }
}

/**
 * Entire finite financial ancestry, iteratively closed on genuine originals from the SAME core
 * loan. Required originals are queued, not accepted by an allowlist/certificate. Mixed stock
 * subtrees additionally keep the repository's existing full iterative producer proof and caps.
 */
private class NormalizedPaymentTraversal(private val borrowed: CommandReadView) {
    private class Required(val original: RetainedCommandEvidence) {
        val dependencies = mutableSetOf<OperationId>()
        var complete = false
    }
    private val required = linkedMapOf<OperationId, Required>()
    private val pending = ArrayDeque<Required>()
    private var current: Required? = null
    private var calls = 0
    private var bytes = 0L
    private var open = true
    val view = NormalizedPaymentOriginView(borrowed, this)
    private fun data(amount: Long) {
        check(open && amount >= 0)
        if (amount > 64L * 1_024 * 1_024 - bytes) throw NormalizedPaymentOriginCapacity()
        bytes += amount
    }
    suspend fun original(id: OperationId, owner: RetainedCommandEvidence? = current?.original): RetainedOriginalV2 {
        check(open)
        if (calls >= 4_096) throw NormalizedPaymentOriginCapacity()
        calls++
        val actual = view.requireStockRelational().originals.read(id) ?: error("sales.stock.payment_original_missing")
        when (actual) {
            is RetainedOriginalV2.Legacy -> {
                val value = actual.evidence
                check(value.scope == view.scope && value.committed && value.operationId == id)
                if (owner != null) check(id != owner.operationId && (value.assignmentId != owner.assignmentId ||
                    value.authorityEpoch != owner.authorityEpoch || value.sequence < owner.sequence))
                data(foodProducerReadBytes(value))
            }
            is RetainedOriginalV2.Streamed -> {
                val value = actual.evidence
                check(value.identity.scope == view.scope && value.identity.producer.operationId == id)
                if (owner != null) check(id != owner.operationId && (value.identity.assignmentId != owner.assignmentId ||
                    value.identity.authorityEpoch != owner.authorityEpoch || value.identity.sequence < owner.sequence))
                data(512L + value.normalized.size + value.result.size)
            }
        }
        return actual
    }
    fun require(original: RetainedCommandEvidence) {
        check(open && original.scope == view.scope && original.committed && original.nativeOriginalProvenance == null)
        val id = original.operationId
        val existing = required[id]
        if (existing != null) requireSamePaymentOriginal(existing.original, original)
        current?.let { from ->
            check(id != from.original.operationId && !reaches(id, from.original.operationId)) { "sales.stock.payment_origin_cycle" }
            from.dependencies += id
        }
        if (existing == null) {
            if (required.size >= 4_096) throw NormalizedPaymentOriginCapacity()
            val value = Required(original); required[id] = value; pending.addLast(value)
        }
    }
    private fun reaches(start: OperationId, target: OperationId): Boolean {
        val visited = mutableSetOf<OperationId>(); val queue = ArrayDeque<OperationId>(); queue.addLast(start)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (id == target) return true
            if (visited.add(id)) required[id]?.dependencies?.forEach { queue.addLast(it) }
        }
        return false
    }
    /** A top-level reader never returns with queued, unqualified ancestry. Nested
     * original joins keep the existing iterative queue instead of recursive proof calls. */
    suspend fun qualifyIfIdle() {
        check(open)
        if (current == null) drain()
    }
    private suspend fun drain() {
        check(open && current == null)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst(); check(!node.complete && current == null); current = node
            requireOne(node.original)
            node.complete = true; current = null
        }
        check(required.values.all { it.complete })
    }
    suspend fun finish() {
        try { drain() } finally { discard() }
    }
    fun discard() { open = false; current = null; required.clear(); pending.clear() }
    private suspend fun qualifyLink(owner: RetainedCommandEvidence, observed: StockReadObservations,
        row: StreamedSourceObservationV2.Current, streamed: PaymentStreamedSources) {
        val source = observed.current(commerceLinkKey(row.key)) ?: error("sales.stock.payment_link_source_missing")
        check(source.schema == commerceLinkSchema())
        val link = localSalesDecode(source.payload, CommerceRowLinkV2.serializer())
        check(link.scope == owner.scope && link.kind == row.key.kind && link.id == row.key.id &&
            link.generation == row.generation && link.digest == row.payloadHash)
        val identity = StreamedProducerIdentityV2(link.operationId, CommandKey(link.command, link.version), link.actorId, link.semanticDigest)
        when (val previous = original(link.operationId)) {
            is RetainedOriginalV2.Streamed -> {
                val value = previous.evidence
                check(value.identity.producer == identity && value.identity.producer.command == StockStreamingCommandsV2.COLLECTION)
                streamed.select(value, row, source)
            }
            is RetainedOriginalV2.Legacy -> {
                val value = previous.evidence
                check(row.key.kind == LocalSalesStoreProfile.COLLECTION_KIND && value.command == identity.command &&
                    value.operationId == identity.operationId && value.originalActorId == identity.originalActorId && value.semanticDigest == identity.semanticDigest)
                require(value) // The whole prior financial producer is closed before finish returns.
                requirePaymentSourceWrite(value, row); requirePaymentSourceWrite(value, source)
            }
        }
    }
    private suspend fun slot(owner: RetainedCommandEvidence, target: LocalJournalTargetV3): Pair<RetainedCommandEvidence, LocalRetailJournalSlotV3> {
        val start = (original(target.intentOperationId, owner) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("sales.stock.payment_start_missing")
        require(start)
        val slot = requireNormalizedPaymentStartOriginal(start, commerceTenderPolicies(view))
        check(slot.target == target)
        return start to slot
    }
    private suspend fun fact(owner: RetainedCommandEvidence, start: RetainedCommandEvidence, slot: LocalRetailJournalSlotV3,
        phase: LocalJournalFactPhaseV3): LocalRetailJournalFactV3 {
        val operation = slot.target.factOperation(phase)
        val raw = (original(operation, owner) as? RetainedOriginalV2.Legacy)?.evidence ?: error("sales.stock.payment_fact_missing")
        val write = raw.aggregateWrites.singleOrNull { it.key == retailJournalFactKeyV3(phase, operation) } ?: error("sales.stock.payment_fact_write_missing")
        check(write.storageGeneration == Revision(1) && write.schema == (if (phase == LocalJournalFactPhaseV3.ORIGINAL)
            LocalFinancialJournalProfileV3.ORIGINAL_SCHEMA else LocalFinancialJournalProfileV3.RESOLUTION_SCHEMA))
        val fact = localSalesDecode(write.payload, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES)
        requireRetailRawEvidenceV3(raw, fact, slot)
        check(fact.target == slot.target && fact.phase == phase && fact.originalActorId == slot.actorId)
        if (raw.nativeOriginalProvenance != null) requireRetailNativeOriginalEvidence(raw, start, fact, slot, commerceTenderPolicies(view))
        // Same lineage is ordered; cross-authority joins retain explicit immutable target links.
        check(start.operationId != raw.operationId && (start.assignmentId != raw.assignmentId || start.authorityEpoch != raw.authorityEpoch ||
            start.sequence < raw.sequence))
        if (phase == LocalJournalFactPhaseV3.RESOLUTION) {
            val parent = fact(owner, start, slot, LocalJournalFactPhaseV3.ORIGINAL)
            check(fact.originalBodyDigest == parent.bodyDigest)
            retailJournalDomain(slot.target.kind).validateResolution(slot.requirement(), parent.body(), fact.recordingActorId,
                retailResolutionDescriptorV3(slot.requirement(), parent.body(), fact.recordingActorId, fact.body()), fact.body())
        }
        return fact
    }
    private suspend fun requireOne(owner: RetainedCommandEvidence) {
        val observed = StockReadEvidenceV1.read(owner) ?: error("sales.stock.payment_read_required")
        when (val result = LocalFinancialResultCodecV3.decode(owner.result)) {
            is LocalFinancialResultV3.AttemptStarted -> {
                val slot = requireNormalizedPaymentStartOriginal(owner, commerceTenderPolicies(view))
                check(slot.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION)
                LocalStockPaymentObservations.requireOriginalStock(view, view.commerceStock, observed, owner)
            }
            is LocalFinancialResultV3.Applied -> {
                check(result.target.kind == SalesRecordingKind.PAYMENT_ATTESTATION && result.applicationOperationId == owner.operationId)
                val (start, slot) = slot(owner, result.target)
                val fact = fact(owner, start, slot, result.phase)
                val write = owner.aggregateWrites.singleOrNull { it.key == retailJournalApplicationKeyV3(result.target.factOperation(result.phase)) }
                    ?: error("sales.stock.payment_application_missing")
                check(write.schema == LocalFinancialJournalProfileV3.APPLICATION_SCHEMA)
                val application = localSalesDecode(write.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
                check(write.storageGeneration == application.revision.next() && application.state == LocalJournalApplicationStateV3.APPLIED)
                val reference = application.financialProof?.parentApplication
                val parent = if (result.phase == LocalJournalFactPhaseV3.ORIGINAL) { check(reference == null); null } else {
                    val requiredReference = checkNotNull(reference)
                    val predecessor = (original(requiredReference.operationId) as? RetainedOriginalV2.Legacy)?.evidence
                        ?: error("sales.stock.payment_parent_missing")
                    require(predecessor)
                    val parentWrite = predecessor.aggregateWrites.singleOrNull { it.key == retailJournalApplicationKeyV3(result.target.recordingOperationId) }
                        ?: error("sales.stock.payment_parent_write_missing")
                    check(parentWrite.storageGeneration == requiredReference.storageRevision && parentWrite.payloadDigest.value == requiredReference.digest &&
                        parentWrite.schema == LocalFinancialJournalProfileV3.APPLICATION_SCHEMA)
                    val parentApplication = localSalesDecode(parentWrite.payload, LocalRetailJournalApplicationV3.serializer(), LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES)
                    val parentFact = fact(owner, start, slot, LocalJournalFactPhaseV3.ORIGINAL)
                    RetailApplicationParentV3(parentFact, parentApplication, requiredReference)
                }
                requireRetailApplicationOriginalV3(owner, fact, application, slot, parent, commerceTenderPolicies(view))
            }
            else -> error("sales.stock.payment_producer_subtype")
        }
        val streamed = PaymentStreamedSources()
        for (row in observed.sources.filterIsInstance<StreamedSourceObservationV2.Current>()) {
            if (row.key.kind in setOf(LocalSalesStoreProfile.ORDER_KIND, LocalSalesStoreProfile.COLLECTION_KIND))
                qualifyLink(owner, observed, row, streamed)
            if (row.key.kind == LocalSalesStoreProfile.PRICE_SOURCES_KIND) {
                val price = localSalesDecode(row.payload, LocalSalesPriceSourcesV2.serializer())
                val quote = (original(price.originalQuoteOperationId) as? RetainedOriginalV2.Streamed)?.evidence
                    ?: error("sales.stock.payment_quote_original_missing")
                check(price.normalizedStock)
                requireNormalizedPaymentQuoteRequest(quote, price.orderId, price.quoteRevision)
                streamed.select(quote, row)
            }
            if (row.key.kind == LocalSalesReturnPolicyCaptureCodecV2.KIND) {
                val projection = requirePaymentReturnPolicySource(observed, row)
                val capture = projection.capture
                val quote = (original(capture.originalQuoteOperationId) as? RetainedOriginalV2.Streamed)?.evidence
                    ?: error("sales.stock.payment_quote_original_missing")
                requireNormalizedPaymentQuoteRequest(quote, capture.orderId, capture.quoteRevision)
                // The whole original quote closes every signed PRESENT/ABSENT publication.
                // BOTH exact immutable writes belong to that same genuinely joined producer.
                streamed.select(quote, row, projection.priceSource)
            }
        }
        streamed.qualify(view)
    }
}

/** Full actual-view qualifier, shared by current collection links, slot/native intake and Finalize sources. */
internal suspend fun requireNormalizedPaymentOrigin(view: CommandReadView, expected: RetainedCommandEvidence) {
    check(view is CommerceStockView)
    val active = (view as? NormalizedPaymentOriginView)?.traversal
    val traversal = active ?: NormalizedPaymentTraversal(view)
    val actual = (traversal.original(expected.operationId, owner = null) as? RetainedOriginalV2.Legacy)?.evidence
        ?: error("sales.stock.payment_original_missing")
    requireSamePaymentOriginal(actual, expected)
    traversal.require(actual)
    if (active == null) traversal.finish() else traversal.qualifyIfIdle()
}

/** One collection-instruction authorization on the SAME active kernel writer loan.
 * Slot and current-collection reads still perform their actual original lookups and
 * exact identity/write comparisons, closing all required ancestry before each reader
 * returns. Only already-complete immutable financial originals may share completion
 * inside this one proof. No current row, permission, native lease or grant is cached;
 * the private view and every proof entry are discarded before the requirement returns. */
internal suspend fun withCollectionInstructionOriginalProof(view: CommandReadView,
    prepare: suspend (CommandReadView) -> PhysicalRetainedStartRequirement?): PhysicalRetainedStartRequirement? {
    check(view is CommerceStockView && view !is NormalizedPaymentOriginView)
    val traversal = NormalizedPaymentTraversal(view)
    try {
        val result = prepare(traversal.view)
        traversal.finish()
        return result
    } finally { traversal.discard() }
}
