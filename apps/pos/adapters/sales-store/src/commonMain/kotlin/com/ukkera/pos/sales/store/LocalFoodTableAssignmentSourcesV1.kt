package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

/** Definition facts only. A source identity is neither a directory permission nor occupancy. */
@Serializable
public enum class LocalFoodTableDefinitionKindV1 { FLOOR, TABLE }

@Serializable
public data class LocalFoodTableDefinitionSourceV1(
    public val kind: LocalFoodTableDefinitionKindV1,
    public val id: DomainId,
    public val revision: Revision,
    public val operationId: OperationId,
    public val actorId: DomainId,
    public val digest: PayloadDigest,
    public val active: Boolean,
    public val floorId: DomainId?,
) {
    init {
        require(revision.value > 0)
        require(if (kind == LocalFoodTableDefinitionKindV1.FLOOR) floorId == null else floorId != null && floorId != id)
    }
}

/** Complete same-branch definition cut, bounded by the existing floor/table configuration. */
@Serializable
public data class LocalFoodTableAssignmentDirectoryV1(public val definitions: List<LocalFoodTableDefinitionSourceV1>) {
    init {
        require(definitions.count { it.kind == LocalFoodTableDefinitionKindV1.FLOOR } <= 16 &&
            definitions.count { it.kind == LocalFoodTableDefinitionKindV1.TABLE } <= 100)
        require(definitions.map { it.id }.distinct().size == definitions.size)
        require(definitions.zipWithNext().all { (a, b) -> a.kind.ordinal < b.kind.ordinal ||
            a.kind == b.kind && a.id.value < b.id.value })
        for (table in definitions.filter { it.kind == LocalFoodTableDefinitionKindV1.TABLE }) {
            val parent = definitions.singleOrNull { it.kind == LocalFoodTableDefinitionKindV1.FLOOR && it.id == table.floorId }
                ?: error("food.table.assignment.directory_parent_missing")
            require(!table.active || parent.active)
        }
    }

    public fun definition(kind: LocalFoodTableDefinitionKindV1, id: DomainId): LocalFoodTableDefinitionSourceV1? =
        definitions.singleOrNull { it.kind == kind && it.id == id }
}

/** Trusted catalog bridge on the actual borrowed writer. There is no accepting fallback. */
public interface TransactionFoodTableAssignmentDirectorySourceV1 {
    public suspend fun current(view: CommandReadView): LocalFoodTableAssignmentDirectoryV1

    /** Prove every recorded definition against its genuine earlier immutable writer/version. */
    public suspend fun requireRecorded(view: CommandReadView, directory: LocalFoodTableAssignmentDirectoryV1,
        consumer: RetainedCommandEvidence)
}

/** Separate native BEFORE bridge; never constructs a historical CommandReadView or authority. */
public interface HistoricalFoodTableAssignmentDirectorySourceV1 {
    public suspend fun before(command: RuntimeHistoricalCommand): LocalFoodTableAssignmentDirectoryV1

    /** Sources of an already joined earlier assignment, not today's mutable definitions. */
    public suspend fun requireRecorded(command: RuntimeHistoricalCommand, directory: LocalFoodTableAssignmentDirectoryV1,
        consumer: RetainedCommandEvidence)
}
