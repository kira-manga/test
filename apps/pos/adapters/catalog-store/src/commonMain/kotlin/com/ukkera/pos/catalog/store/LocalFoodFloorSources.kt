package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.Serializable

/** Literal prior writer references, not a supplied directory or a historical authority. */
@Serializable
internal data class FoodFloorSourceV1(val kind: String, val id: DomainId, val revision: Revision,
    val operationId: OperationId, val actorId: DomainId, val digest: PayloadDigest) {
    init { require(kind == LocalFoodFloorProfileV1.FLOOR || kind == LocalFoodFloorProfileV1.TABLE); require(revision.value > 0) }
}

@Serializable
internal data class FoodFloorRecordV1(val scope: BranchScope, val kind: String, val id: DomainId,
    val revision: Revision, val operationId: OperationId, val actorId: DomainId, val policyRevision: Revision,
    val recordedAt: UtcInstant, val request: LocalFoodFloorConfigurationRequestV1, val beforeSources: List<FoodFloorSourceV1>) {
    init {
        require(foodFloorTarget(request) == (kind to id) && revision.value > 0)
        require(beforeSources.size <= LocalFoodFloorProfileV1.MAX_FLOORS + LocalFoodFloorProfileV1.MAX_TABLES)
        require(beforeSources.count { it.kind == LocalFoodFloorProfileV1.FLOOR } <= LocalFoodFloorProfileV1.MAX_FLOORS &&
            beforeSources.count { it.kind == LocalFoodFloorProfileV1.TABLE } <= LocalFoodFloorProfileV1.MAX_TABLES)
        require(beforeSources.map { it.id }.distinct().size == beforeSources.size)
        require(beforeSources.zipWithNext().all { (a, b) -> a.kind < b.kind || a.kind == b.kind && a.id.value < b.id.value })
    }
}

internal class FoodFloorDirectoryV1(val floors: List<FoodFloorRecordV1>, val tables: List<FoodFloorRecordV1>) {
    init {
        check(floors.size <= LocalFoodFloorProfileV1.MAX_FLOORS && tables.size <= LocalFoodFloorProfileV1.MAX_TABLES)
        check(floors.all { it.kind == LocalFoodFloorProfileV1.FLOOR } && tables.all { it.kind == LocalFoodFloorProfileV1.TABLE })
        check((floors + tables).map { it.id }.distinct().size == floors.size + tables.size)
        for (records in listOf(floors, tables)) check(records.zipWithNext().all { (a, b) -> a.id.value < b.id.value })
        for (record in tables) {
            val table = (record.request as LocalFoodFloorConfigurationRequestV1.Table).table
            val parent = floors.singleOrNull { it.id == table.floorId } ?: error("food.floor.parent_missing")
            check(!table.active || (parent.request as LocalFoodFloorConfigurationRequestV1.Floor).floor.active) {
                "food.floor.active_parent_required"
            }
        }
    }
    fun record(kind: String, id: DomainId): FoodFloorRecordV1? = when (kind) {
        LocalFoodFloorProfileV1.FLOOR -> floors.singleOrNull { it.id == id }
        LocalFoodFloorProfileV1.TABLE -> tables.singleOrNull { it.id == id }
        else -> error("food.floor.source_kind")
    }
    fun references(): List<FoodFloorSourceV1> = (floors + tables).map { record ->
        FoodFloorSourceV1(record.kind, record.id, record.revision, record.operationId, record.actorId,
            foodFloorDigest(foodFloorEncode(record, FoodFloorRecordV1.serializer(), LocalFoodFloorProfileV1.MAX_RECORD_BYTES)))
    }
}

/** Only current rows and exact retained immutable writes from the same borrowed transaction.
 * Native history separately proves complete earlier producer chains and original permissions/resources. */
internal class LocalFoodFloorSourceReader(private val view: CommandReadView) {
    private data class ValidatedProducer(
        val original: RetainedCommandEvidence,
        val record: FoodFloorRecordV1,
        val reference: FoodFloorSourceV1,
    )

    // Cache size is an implementation bound, not a history-length or directory acceptance limit.
    private val producers = linkedMapOf<OperationId, ValidatedProducer>()

    suspend fun directory(): FoodFloorDirectoryV1 = FoodFloorDirectoryV1(
        records(LocalFoodFloorProfileV1.FLOOR, LocalFoodFloorProfileV1.MAX_FLOORS),
        records(LocalFoodFloorProfileV1.TABLE, LocalFoodFloorProfileV1.MAX_TABLES))

    private suspend fun records(kind: String, maximum: Int): List<FoodFloorRecordV1> {
        val page = view.aggregateKeys(kind, null, maximum)
        check(page.nextAfter == null && page.keys.size <= maximum && page.keys.all { it.kind == kind } &&
            page.keys.zipWithNext().all { (a, b) -> a.id.value < b.id.value }) { "food.floor.directory_capacity_unavailable" }
        return page.keys.map { key ->
            val row = view.aggregate(key) ?: error("food.floor.listed_source_missing")
            val record = decodeFoodFloorRecord(view.scope, row)
            val (original, produced) = producer(record.operationId)
            check(produced == record)
            requireFoodFloorWrite(original, row)
            val version = view.aggregate(LocalFoodFloorProfileV1.key(LocalFoodFloorProfileV1.VERSION, DomainId(record.operationId.value)))
                ?: error("food.floor.version_missing")
            check(decodeFoodFloorRecord(view.scope, version) == record)
            requireFoodFloorWrite(original, version)
            val prior = record.beforeSources.map { reference ->
                val cached = producer(reference.operationId)
                val (earlier, source) = cached
                check(earlier.scope == original.scope && earlier.sequence < original.sequence &&
                    earlier.authorityEpoch <= original.authorityEpoch) { "food.floor.source_not_earlier" }
                requireFoodFloorReference(reference, earlier, source)
                cached
            }
            val floors = prior.filter { it.record.kind == LocalFoodFloorProfileV1.FLOOR }
            val tables = prior.filter { it.record.kind == LocalFoodFloorProfileV1.TABLE }
            val before = FoodFloorDirectoryV1(floors.map { it.record }, tables.map { it.record })
            check((floors + tables).map { it.reference } == record.beforeSources &&
                foodFloorRejection(record.request, before) == null)
            record
        }
    }

    private suspend fun producer(operationId: OperationId): ValidatedProducer {
        producers.remove(operationId)?.let { cached -> producers[operationId] = cached; return cached }
        val original = view.retainedCommand(operationId) ?: error("food.floor.producer_missing")
        check(original.scope == view.scope && original.operationId == operationId)
        val record = requireFoodFloorProducer(original)
        val reference = FoodFloorSourceV1(record.kind, record.id, record.revision, record.operationId, record.actorId,
            foodFloorDigest(foodFloorEncode(record, FoodFloorRecordV1.serializer(), LocalFoodFloorProfileV1.MAX_RECORD_BYTES)))
        return ValidatedProducer(original, record, reference).also {
            producers[operationId] = it
            if (producers.size > 256) producers.remove(producers.keys.first())
        }
    }
}

internal fun foodFloorRejection(request: LocalFoodFloorConfigurationRequestV1, before: FoodFloorDirectoryV1): String? {
    val (kind, id) = foodFloorTarget(request)
    val previous = before.record(kind, id)
    if (foodFloorExpected(request) != previous?.revision) return "food.floor.stale"
    val other = if (kind == LocalFoodFloorProfileV1.FLOOR) LocalFoodFloorProfileV1.TABLE else LocalFoodFloorProfileV1.FLOOR
    if (before.record(other, id) != null) return "food.floor.identity_conflict"
    val size = if (kind == LocalFoodFloorProfileV1.FLOOR) before.floors.size else before.tables.size
    val maximum = if (kind == LocalFoodFloorProfileV1.FLOOR) LocalFoodFloorProfileV1.MAX_FLOORS else LocalFoodFloorProfileV1.MAX_TABLES
    if (previous == null && size >= maximum) return "food.floor.capacity"
    return when (request) {
        is LocalFoodFloorConfigurationRequestV1.Floor -> if (!request.floor.active && before.tables.any {
            val table = (it.request as LocalFoodFloorConfigurationRequestV1.Table).table
            table.floorId == id && table.active
        }) "food.floor.active_tables" else null
        is LocalFoodFloorConfigurationRequestV1.Table -> {
            val old = (previous?.request as? LocalFoodFloorConfigurationRequestV1.Table)?.table
            val floor = (before.record(LocalFoodFloorProfileV1.FLOOR, request.table.floorId)?.request as?
                LocalFoodFloorConfigurationRequestV1.Floor)?.floor
            if (old != null && old.floorId != request.table.floorId) "food.table.floor_changed"
            else if (floor == null || request.table.active && !floor.active) "food.table.floor_required" else null
        }
    }
}

internal fun foodFloorResources(scope: BranchScope, request: LocalFoodFloorConfigurationRequestV1,
    actorId: DomainId, operationId: OperationId, sources: List<FoodFloorSourceV1>): Set<RecoveryResourceKey> {
    fun resource(id: DomainId): RecoveryResourceKey = RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, id)
    val resources = mutableSetOf(resource(scope.branchId), resource(foodFloorTarget(request).second), resource(actorId), resource(DomainId(operationId.value)))
    if (request is LocalFoodFloorConfigurationRequestV1.Table) resources += resource(request.table.floorId)
    for (source in sources) {
        resources += resource(source.id); resources += resource(source.actorId); resources += resource(DomainId(source.operationId.value))
    }
    return resources.toSet()
}

internal fun decodeFoodFloorRecord(scope: BranchScope, row: StoredAggregate): FoodFloorRecordV1 {
    check(row.key.kind in setOf(LocalFoodFloorProfileV1.FLOOR, LocalFoodFloorProfileV1.TABLE, LocalFoodFloorProfileV1.VERSION) &&
        row.schema == LocalFoodFloorProfileV1.schema(row.key.kind) && foodFloorDigest(row.payload) == row.digest)
    val record = foodFloorDecode(row.payload, FoodFloorRecordV1.serializer(), LocalFoodFloorProfileV1.MAX_RECORD_BYTES)
    check(record.scope == scope)
    if (row.key.kind == LocalFoodFloorProfileV1.VERSION) check(row.revision == Revision(1) && row.key.id.value == record.operationId.value)
    else check(row.key == LocalFoodFloorProfileV1.key(record.kind, record.id) && row.revision == record.revision)
    return record
}

/** Exact literal committed producer. This structural check never replaces the native business join. */
internal fun requireFoodFloorProducer(original: RetainedCommandEvidence): FoodFloorRecordV1 {
    check(original.committed && original.nativeOriginalProvenance == null && original.command == LocalFoodFloorProfileV1.COMMAND &&
        original.jobs.isEmpty() && original.claimChanges.isEmpty() && original.aggregateWrites.size == 2)
    val request = foodFloorDecode(original.normalized, LocalFoodFloorConfigurationRequestV1.serializer(), LocalFoodFloorProfileV1.MAX_REQUEST_BYTES)
    check(semanticDigest(original.scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest)
    val current = original.aggregateWrites.first()
    val record = foodFloorDecode(current.payload, FoodFloorRecordV1.serializer(), LocalFoodFloorProfileV1.MAX_RECORD_BYTES)
    check(foodFloorTarget(request) == (record.kind to record.id) && record.request == request && record.scope == original.scope &&
        record.operationId == original.operationId && record.actorId == original.originalActorId && record.policyRevision == original.policyRevision &&
        record.recordedAt == original.recordedAt && record.revision == (foodFloorExpected(request)?.next() ?: Revision(1)))
    val expected = foodFloorApply(record)
    check(original.result == expected.result && foodFloorDigest(original.result) == original.resultHash)
    for ((actual, wanted) in original.aggregateWrites.zip(expected.changes)) check(actual.key == wanted.key && actual.schema == wanted.schema &&
        actual.storageGeneration == (wanted.expectedRevision?.next() ?: Revision(1)) && actual.payload == wanted.payload &&
        actual.payloadDigest == foodFloorDigest(actual.payload))
    return record
}

internal fun requireFoodFloorWrite(original: RetainedCommandEvidence, row: StoredAggregate) {
    val write = original.aggregateWrites.singleOrNull { it.key == row.key } ?: error("food.floor.producer_write_missing")
    check(write.storageGeneration == row.revision && write.schema == row.schema && write.payload == row.payload && write.payloadDigest == row.digest)
}

internal fun requireFoodFloorReference(reference: FoodFloorSourceV1, original: RetainedCommandEvidence, record: FoodFloorRecordV1) {
    check(reference.kind == record.kind && reference.id == record.id && reference.revision == record.revision &&
        reference.operationId == record.operationId && reference.actorId == record.actorId)
    val write = original.aggregateWrites.singleOrNull { it.key == LocalFoodFloorProfileV1.key(reference.kind, reference.id) }
        ?: error("food.floor.prior_write_missing")
    check(write.storageGeneration == reference.revision && write.schema == LocalFoodFloorProfileV1.schema(reference.kind) &&
        write.payloadDigest == reference.digest && foodFloorDigest(write.payload) == reference.digest)
}

internal fun foodFloorDigest(bytes: StoredBytes): PayloadDigest {
    val raw = bytes.copyBytes()
    return try { sha256(raw) } finally { raw.fill(0) }
}
