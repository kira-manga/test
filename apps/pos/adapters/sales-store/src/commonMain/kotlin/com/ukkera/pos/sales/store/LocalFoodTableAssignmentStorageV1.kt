package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.types.*
import com.ukkera.pos.security.BranchScope

/** Work bounds only. Exhaustion is unavailable, never a partial/unassigned observation. */
private const val FOOD_TABLE_ASSIGNMENT_VERSION_PAGE = 100
private const val FOOD_TABLE_ASSIGNMENT_VERSION_ROWS = 16_384
private const val FOOD_TABLE_ASSIGNMENT_VERSION_BYTES = 64L * 1_024 * 1_024

internal fun foodTableAssignmentRecord(scope: BranchScope, row: StoredAggregate): FoodTableAssignmentRecordV1 {
    check(row.key.kind == LocalFoodTableAssignmentProfileV1.BINDING || row.key.kind == LocalFoodTableAssignmentProfileV1.VERSION)
    check(row.schema == LocalFoodTableAssignmentProfileV1.schema(row.key.kind) && foodTableAssignmentDigest(row.payload) == row.digest)
    val record = localSalesDecode(row.payload, FoodTableAssignmentRecordV1.serializer(), LocalFoodTableAssignmentProfileV1.MAX_RECORD_BYTES)
    check(record.scope == scope && foodTableAssignmentRecordBytes(record) == row.payload)
    if (row.key.kind == LocalFoodTableAssignmentProfileV1.BINDING) {
        check(row.key == foodTableAssignmentBindingKey(record.request.orderId) && row.revision == record.binding.revision)
    } else check(row.key == foodTableAssignmentVersionKey(record.operationId) && row.revision == Revision(1))
    return record
}

/** Exact committed literal producer. It supplies no native authority or missing BEFORE proof. */
internal fun requireFoodTableAssignmentProducer(original: RetainedCommandEvidence): FoodTableAssignmentRecordV1 {
    check(original.committed && original.nativeOriginalProvenance == null && original.command == LocalFoodTableAssignmentProfileV1.COMMAND &&
        original.aggregateWrites.size == 2 && original.claimChanges.isEmpty() && original.jobs.isEmpty())
    val request = LocalFoodTableAssignmentCodecV1.decode(original.normalized)
    check(LocalFoodTableAssignmentCodecV1.encode(request) == original.normalized &&
        semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest)
    val binding = original.aggregateWrites.singleOrNull { it.key == foodTableAssignmentBindingKey(request.orderId) }
        ?: error("food.table.assignment.original_binding_missing")
    val record = localSalesDecode(binding.payload, FoodTableAssignmentRecordV1.serializer(), LocalFoodTableAssignmentProfileV1.MAX_RECORD_BYTES)
    check(record.scope == original.scope && record.request == request && record.operationId == original.operationId &&
        record.semanticDigest == original.semanticDigest && record.authorization.actorId == original.originalActorId &&
        record.authorization.authenticationId == original.authenticationId && record.authorization.policyRevision == original.policyRevision &&
        record.recordedAt == original.recordedAt)
    val expected = foodTableAssignmentApply(record)
    requireFoodTableAssignmentLiteralEffects(original, expected)
    return record
}

internal fun requireFoodTableAssignmentLiteralEffects(original: RetainedCommandEvidence, expected: ProposedOutcome.Apply) {
    check(original.committed && original.nativeOriginalProvenance == null && original.result == expected.result &&
        original.resultHash == foodTableAssignmentDigest(original.result) && original.aggregateWrites.size == expected.changes.size &&
        original.claimChanges.isEmpty() && expected.claims.isEmpty() && original.jobs.isEmpty() && expected.jobs.isEmpty() &&
        expected.mustCommitBefore == null)
    for ((actual, wanted) in original.aggregateWrites.zip(expected.changes)) {
        check(actual.key == wanted.key && actual.storageGeneration == (wanted.expectedRevision?.next() ?: Revision(1)) &&
            actual.schema == wanted.schema && actual.payload == wanted.payload && actual.payloadDigest == foodTableAssignmentDigest(actual.payload))
    }
}

internal fun requireFoodTableAssignmentWrite(original: RetainedCommandEvidence, row: StoredAggregate) {
    val write = original.aggregateWrites.singleOrNull { it.key == row.key } ?: error("food.table.assignment.original_write_missing")
    check(write.storageGeneration == row.revision && write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest)
}

internal suspend fun readFoodTableAssignmentBinding(view: FoodReadView, orderId: DomainId,
    directory: TransactionFoodTableAssignmentDirectorySourceV1): FoodTableAssignmentBindingCutV1? {
    val row = view.aggregate(foodTableAssignmentBindingKey(orderId))
    val versions = foodTableAssignmentVersionRows(view, orderId)
    return requireFoodTableAssignmentBinding(view.scope, orderId, row, versions,
        original = { operation -> view.retainedCommand(operation) ?: error("food.table.assignment.original_missing") },
        order = { record, original -> foodTableAssignmentRecordedOrder(view, record, original) },
        directory = { record, original -> directory.requireRecorded(view, record.directory, original) })
}

/** Original-only disclosure closure. Mutable heads and today's directory are never read.
 * Literal producer validation checks the existing result/effects; no assignment is prepared
 * or evaluated. Each BEFORE binding and FOOD/directory source must be genuinely earlier. */
internal suspend fun foodTableAssignmentRetainedOrder(view: FoodReadView, original: RetainedCommandEvidence,
    directory: TransactionFoodTableAssignmentDirectorySourceV1): FoodTableAssignmentOrderCutV1 {
    val events = mutableListOf<Pair<RetainedCommandEvidence, FoodTableAssignmentRecordV1>>()
    var cursor = original
    var bytes = 0L
    var expectedBefore: FoodTableAssignmentBeforeV1? = null
    val orderId = LocalFoodTableAssignmentCodecV1.decode(original.normalized).orderId
    while (true) {
        foodCapacity(events.size < FOOD_TABLE_ASSIGNMENT_VERSION_ROWS)
        check(cursor.scope == view.scope)
        val record = requireFoodTableAssignmentProducer(cursor)
        check(record.request.orderId == orderId)
        val version = view.aggregate(foodTableAssignmentVersionKey(cursor.operationId))
            ?: error("food.table.assignment.retained_version_missing")
        check(foodTableAssignmentRecord(view.scope, version) == record)
        requireFoodTableAssignmentWrite(cursor, version)
        val charge = version.payload.size.toLong() + 512L
        foodCapacity(charge <= FOOD_TABLE_ASSIGNMENT_VERSION_BYTES - bytes)
        bytes += charge
        expectedBefore?.let { before ->
            check(record.binding.revision == before.revision && record.operationId == before.operationId &&
                record.authorization.actorId == before.actorId && version.digest == before.digest) {
                "food.table.assignment.retained_before_mismatch"
            }
        }
        events += cursor to record
        val before = record.before ?: break
        val earlier = view.retainedCommand(before.operationId)
            ?: error("food.table.assignment.retained_before_missing")
        requireFoodTableAssignmentChronology(earlier, cursor)
        expectedBefore = before
        cursor = earlier
    }

    var before: LocalFoodTableBindingV1? = null
    var selectedOrder: FoodTableAssignmentOrderCutV1? = null
    val provedOrders = mutableMapOf<Pair<FoodTableAssignmentFoodSourceV1, FoodTableAssignmentFoodSourceV1>,
        Pair<FoodTableAssignmentOrderCutV1, RetainedCommandEvidence>>()
    val provedDirectories = mutableMapOf<LocalFoodTableAssignmentDirectoryV1, RetainedCommandEvidence>()
    for ((producer, record) in events.asReversed()) {
        val sources = record.orderSource to record.ownerIndexSource
        val order = provedOrders[sources]?.let { (cut, earlierConsumer) ->
            requireFoodTableAssignmentChronology(earlierConsumer, producer)
            cut
        } ?: foodTableAssignmentRecordedOrder(view, record, producer).also { provedOrders[sources] = it to producer }
        val earlierDirectoryConsumer = provedDirectories[record.directory]
        if (earlierDirectoryConsumer == null) {
            directory.requireRecorded(view, record.directory, producer)
            provedDirectories[record.directory] = producer
        } else requireFoodTableAssignmentChronology(earlierDirectoryConsumer, producer)
        check(foodTableAssignmentRejection(producer.originalActorId, record.request, order, before, record.directory) == null) {
            "food.table.assignment.retained_recorded_refusal"
        }
        before = record.binding
        selectedOrder = order
    }
    return checkNotNull(selectedOrder)
}

/** Complete native keyset census catches missing heads and unindexed/orphan immutable events. */
private suspend fun foodTableAssignmentVersionRows(view: CommandReadView, orderId: DomainId): List<StoredAggregate> {
    val selected = mutableListOf<StoredAggregate>()
    var after: DomainId? = null
    var rows = 0
    var bytes = 0L
    while (true) {
        foodCapacity(rows < FOOD_TABLE_ASSIGNMENT_VERSION_ROWS)
        val limit = minOf(FOOD_TABLE_ASSIGNMENT_VERSION_PAGE, FOOD_TABLE_ASSIGNMENT_VERSION_ROWS - rows)
        val page = view.aggregateKeys(LocalFoodTableAssignmentProfileV1.VERSION, after, limit)
        check(page.keys.size <= limit && page.keys.all { it.kind == LocalFoodTableAssignmentProfileV1.VERSION &&
            (after == null || it.id.value > checkNotNull(after).value) } &&
            page.keys.zipWithNext().all { (a, b) -> a.id.value < b.id.value })
        val next = page.nextAfter
        check(next == null || page.keys.size == limit && next == page.keys.lastOrNull()?.id)
        for (key in page.keys) {
            val row = view.aggregate(key) ?: error("food.table.assignment.listed_version_missing")
            check(row.key == key)
            val charge = row.payload.size.toLong() + 512L
            foodCapacity(charge <= FOOD_TABLE_ASSIGNMENT_VERSION_BYTES - bytes)
            bytes += charge
            val record = foodTableAssignmentRecord(view.scope, row)
            if (record.request.orderId == orderId) selected += row
        }
        rows += page.keys.size
        if (next == null) return selected.toList()
        foodCapacity(rows < FOOD_TABLE_ASSIGNMENT_VERSION_ROWS)
        after = next
    }
}

/** Real native census only. Every selected event additionally has its exact joined original. */
internal suspend fun foodTableAssignmentHistoricalBinding(command: RuntimeHistoricalCommand, orderId: DomainId,
    directory: HistoricalFoodTableAssignmentDirectorySourceV1): FoodTableAssignmentBindingCutV1? {
    val head = command.aggregateSource(foodTableAssignmentBindingKey(orderId))
    val rows = command.aggregates(LocalFoodTableAssignmentProfileV1.VERSION).sortedBy { it.key.id.value }
    foodCapacity(rows.size <= FOOD_TABLE_ASSIGNMENT_VERSION_ROWS &&
        rows.sumOf { it.payload.size.toLong() + 512L } <= FOOD_TABLE_ASSIGNMENT_VERSION_BYTES)
    check(rows.zipWithNext().all { (a, b) -> a.key.id.value < b.key.id.value })
    val selected = rows.filter { row ->
        check(row.key.kind == LocalFoodTableAssignmentProfileV1.VERSION)
        foodTableAssignmentRecord(command.scope, row).request.orderId == orderId
    }
    val originals = mutableMapOf<OperationId, RetainedCommandEvidence>()
    for (row in selected) {
        val source = command.aggregateSource(row.key) ?: error("food.table.assignment.history_version_missing")
        requireFoodTableAssignmentSameRow(row, source.row)
        val record = foodTableAssignmentRecord(command.scope, row)
        val origin = requireFoodTableAssignmentEarlier(command, source.original, record.operationId)
        check(originals.put(record.operationId, origin) == null)
    }
    if (head != null) {
        val record = foodTableAssignmentRecord(command.scope, head.row)
        val origin = requireFoodTableAssignmentEarlier(command, head.original, record.operationId)
        check(originals[record.operationId] === origin)
    }
    return requireFoodTableAssignmentBinding(command.scope, orderId, head?.row, selected,
        original = { operation -> originals[operation] ?: error("food.table.assignment.history_original_missing") },
        order = { record, original -> foodTableAssignmentHistoricalRecordedOrder(command, record, original) },
        directory = { record, original -> directory.requireRecorded(command, record.directory, original) })
}

/** Shared consistency traversal over actual live/native observations, never a reconstructed view. */
private suspend fun requireFoodTableAssignmentBinding(scope: BranchScope, orderId: DomainId, head: StoredAggregate?,
    versions: List<StoredAggregate>, original: suspend (OperationId) -> RetainedCommandEvidence,
    order: suspend (FoodTableAssignmentRecordV1, RetainedCommandEvidence) -> FoodTableAssignmentOrderCutV1,
    directory: suspend (FoodTableAssignmentRecordV1, RetainedCommandEvidence) -> Unit): FoodTableAssignmentBindingCutV1? {
    if (head == null) {
        check(versions.isEmpty()) { "food.table.assignment.version_without_binding" }
        return null
    }
    val current = foodTableAssignmentRecord(scope, head)
    check(head.key == foodTableAssignmentBindingKey(orderId) && current.request.orderId == orderId &&
        versions.size.toLong() == current.binding.revision.value && versions.isNotEmpty())
    val events = versions.map { row -> row to foodTableAssignmentRecord(scope, row) }.sortedBy { it.second.binding.revision.value }
    check(events.map { it.second.operationId }.distinct().size == events.size)
    var previous: FoodTableAssignmentBindingCutV1? = null
    var previousOriginal: RetainedCommandEvidence? = null
    // Request-local reuse of fully proved inert immutable cuts, not a current grant or
    // retained view. Every subsequent consumer still has to be strictly later in scope.
    val provedOrders = mutableMapOf<Pair<FoodTableAssignmentFoodSourceV1, FoodTableAssignmentFoodSourceV1>,
        Pair<FoodTableAssignmentOrderCutV1, RetainedCommandEvidence>>()
    val provedDirectories = mutableMapOf<LocalFoodTableAssignmentDirectoryV1, RetainedCommandEvidence>()
    for ((ordinal, event) in events.withIndex()) {
        val (row, record) = event
        check(row.key == foodTableAssignmentVersionKey(record.operationId) && record.request.orderId == orderId &&
            record.binding.revision == Revision(ordinal.toLong() + 1) && record.before == previous?.reference())
        val producer = original(record.operationId)
        check(producer.scope == scope && producer.operationId == record.operationId && requireFoodTableAssignmentProducer(producer) == record)
        requireFoodTableAssignmentWrite(producer, row)
        previousOriginal?.let { earlier -> requireFoodTableAssignmentChronology(earlier, producer) }
        val orderKey = record.orderSource to record.ownerIndexSource
        val observedOrder = provedOrders[orderKey]?.let { (cut, earlierConsumer) ->
            requireFoodTableAssignmentChronology(earlierConsumer, producer)
            cut
        } ?: order(record, producer).also { provedOrders[orderKey] = it to producer }
        val earlierDirectoryConsumer = provedDirectories[record.directory]
        if (earlierDirectoryConsumer == null) {
            directory(record, producer)
            provedDirectories[record.directory] = producer
        } else requireFoodTableAssignmentChronology(earlierDirectoryConsumer, producer)
        val identity = FoodTableAssignmentIdentityV1(scope, producer.originalActorId, producer.operationId, producer.semanticDigest)
        val production = foodTableAssignmentProduction(identity, record.request, observedOrder, previous, record.directory)
        val expected = production.propose(record.recordedAt, record.authorization) as? ProposedOutcome.Apply
            ?: error("food.table.assignment.recorded_refusal")
        requireFoodTableAssignmentLiteralEffects(producer, expected)
        previous = FoodTableAssignmentBindingCutV1(row, record, production.affectedResources)
        previousOriginal = producer
    }
    val last = checkNotNull(previous)
    check(last.value == current && last.row.payload == head.payload && last.row.digest == head.digest)
    requireFoodTableAssignmentWrite(checkNotNull(previousOriginal), head)
    return last.copy(row = head, recoveryResources = last.recoveryResources.toSet())
}

internal suspend fun foodTableAssignmentRecordedOrder(view: FoodReadView, record: FoodTableAssignmentRecordV1,
    consumer: RetainedCommandEvidence): FoodTableAssignmentOrderCutV1 {
    val order = foodTableAssignmentRecordedFood(view, record.orderSource, consumer)
    val index = foodTableAssignmentRecordedFood(view, record.ownerIndexSource, consumer)
    return FoodTableAssignmentOrderCutV1(order, index, record.orderSource, record.ownerIndexSource).also {
        requireFoodTableAssignmentOrderCut(it, record.scope)
    }
}

private suspend fun foodTableAssignmentRecordedFood(view: FoodReadView, source: FoodTableAssignmentFoodSourceV1,
    consumer: RetainedCommandEvidence): FoodEnvelopeV1 {
    if (foodStreamedSchema(source.kind, source.schema)) {
        val original = view.foodStockProducer(source.operationId)
        val identity = original.identity
        check(identity.scope == view.scope && identity.producer.originalActorId == source.actorId &&
            identity.sequence < consumer.sequence && identity.authorityEpoch <= consumer.authorityEpoch &&
            identity.producer.operationId != consumer.operationId)
        val row = original.writeRow(AggregateKey(source.kind, source.id)) ?: error("food.table.assignment.food_original_write_missing")
        return foodTableAssignmentSourceEnvelope(view.scope, source, row.key, row.revision, row.schema, row.payload, row.digest)
    }
    val original = view.foodProducer(source.operationId)
    view.requireFoodPredecessors(original)
    requireFoodTableAssignmentChronology(original, consumer)
    check(original.originalActorId == source.actorId)
    val row = original.aggregateWrites.singleOrNull { it.key == AggregateKey(source.kind, source.id) }
        ?: error("food.table.assignment.food_original_write_missing")
    return foodTableAssignmentSourceEnvelope(view.scope, source, row.key, row.storageGeneration, row.schema, row.payload, row.payloadDigest)
}

internal fun foodTableAssignmentSourceEnvelope(scope: BranchScope, source: FoodTableAssignmentFoodSourceV1,
    key: AggregateKey, revision: Revision, schema: String, payload: StoredBytes,
    digest: com.ukkera.pos.security.PayloadDigest): FoodEnvelopeV1 {
    check(key == AggregateKey(source.kind, source.id) && revision == source.revision && schema == source.schema &&
        digest == source.digest && foodTableAssignmentDigest(payload) == digest)
    val value = foodStoredEnvelope(schema, payload)
    check(value.scope == scope && value.kind == source.kind && value.id == source.id && value.revision == source.revision &&
        value.operationId == source.operationId && foodStoredEnvelopeBytes(value, schema) == payload)
    return value
}

internal fun requireFoodTableAssignmentChronology(earlier: RetainedCommandEvidence, later: RetainedCommandEvidence) {
    check(earlier.committed && earlier.nativeOriginalProvenance == null && earlier.scope == later.scope &&
        earlier.sequence < later.sequence && earlier.authorityEpoch <= later.authorityEpoch && earlier.operationId != later.operationId) {
        "food.table.assignment.source_not_earlier"
    }
}

internal fun requireFoodTableAssignmentSameRow(a: StoredAggregate, b: StoredAggregate) {
    check(a.key == b.key && a.revision == b.revision && a.schema == b.schema && a.payload == b.payload && a.digest == b.digest)
}

internal suspend fun requireFoodTableAssignmentEarlier(command: RuntimeHistoricalCommand, original: RetainedOriginalV2,
    operationId: OperationId): RetainedCommandEvidence {
    val value = (original as? RetainedOriginalV2.Legacy)?.evidence ?: error("food.table.assignment.history_original_profile")
    check(value.committed && value.nativeOriginalProvenance == null && value.scope == command.scope && value.operationId == operationId &&
        operationId != command.operationId && command.earlierOriginal(operationId) === original)
    return value
}
