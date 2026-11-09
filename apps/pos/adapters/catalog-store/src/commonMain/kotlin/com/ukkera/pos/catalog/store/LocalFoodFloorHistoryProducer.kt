package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.types.*

/** Genuine native BEFORE sources and earlier joined producers only. Never executes a handler,
 * reconstructs a CommandReadView or restores an employee/session permission. */
internal class LocalFoodFloorHistoryProducer : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalFoodFloorProfileV1.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (original.nativeOriginalProvenance != null) return false
        val request = foodFloorDecode(original.normalized, LocalFoodFloorConfigurationRequestV1.serializer(), LocalFoodFloorProfileV1.MAX_REQUEST_BYTES)
        check(semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val floors = sources(command, LocalFoodFloorProfileV1.FLOOR, LocalFoodFloorProfileV1.MAX_FLOORS)
        val tables = sources(command, LocalFoodFloorProfileV1.TABLE, LocalFoodFloorProfileV1.MAX_TABLES)
        val before = FoodFloorDirectoryV1(floors, tables)
        val references = before.references()
        val (kind, id) = foodFloorTarget(request)
        val previous = before.record(kind, id)
        val rejection = foodFloorRejection(request, before)
        val requirement = foodFloorRequirement(command.scope, request, previous?.revision, command.semanticDigest, command.operationId)
        val resources = foodFloorResources(command.scope, request, command.actorId, command.operationId, references)
        val versionKey = LocalFoodFloorProfileV1.key(LocalFoodFloorProfileV1.VERSION, DomainId(command.operationId.value))
        check(command.aggregateSource(versionKey) == null) { "food.floor.original_version_already_present" }
        command.requireRequirement(requirement)
        if (!original.committed) {
            check(rejection != null && original.code == rejection && original.aggregateWrites.isEmpty() &&
                original.claimChanges.isEmpty() && original.jobs.isEmpty())
            val expected = ProposedOutcome.Reject(rejection,
                foodFloorEncode(LocalConfigurationResultV1(rejection, null, null), LocalConfigurationResultV1.serializer(), 2_048))
            check(original.result == expected.result && foodFloorDigest(original.result) == original.resultHash)
            command.requireFloorConfigurationRejection(expected, resources)
            return true
        }
        check(rejection == null)
        val actual = requireFoodFloorProducer(original)
        val expected = FoodFloorRecordV1(command.scope, kind, id, previous?.revision?.next() ?: Revision(1), command.operationId,
            command.actorId, command.policyRevision, command.recordedAt, request, references)
        check(actual == expected)
        command.requireLegacyEffects(foodFloorApply(expected), resources)
        return true
    }

    private suspend fun sources(command: RuntimeHistoricalCommand, kind: String, maximum: Int): List<FoodFloorRecordV1> {
        val rows = command.aggregates(kind).sortedBy { it.key.id.value }
        check(rows.size <= maximum && rows.zipWithNext().all { (a, b) -> a.key.id.value < b.key.id.value })
        return rows.map { row ->
            val paired = command.aggregateSource(row.key) ?: error("food.floor.original_source_missing")
            check(paired.row.key == row.key && paired.row.revision == row.revision && paired.row.schema == row.schema &&
                paired.row.digest == row.digest && paired.row.payload == row.payload)
            val record = decodeFoodFloorRecord(command.scope, row)
            val original = (paired.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("food.floor.original_producer_unavailable")
            check(original.scope == command.scope && original.operationId == record.operationId && requireFoodFloorProducer(original) == record)
            requireFoodFloorWrite(original, row)
            val versionKey = LocalFoodFloorProfileV1.key(LocalFoodFloorProfileV1.VERSION, DomainId(record.operationId.value))
            val version = command.aggregateSource(versionKey) ?: error("food.floor.original_version_missing")
            check(decodeFoodFloorRecord(command.scope, version.row) == record)
            val versionOriginal = (version.original as? RetainedOriginalV2.Legacy)?.evidence ?: error("food.floor.original_version_producer_unavailable")
            check(versionOriginal.scope == command.scope && versionOriginal.operationId == record.operationId &&
                requireFoodFloorProducer(versionOriginal) == record)
            requireFoodFloorWrite(versionOriginal, version.row)
            record
        }
    }
}
