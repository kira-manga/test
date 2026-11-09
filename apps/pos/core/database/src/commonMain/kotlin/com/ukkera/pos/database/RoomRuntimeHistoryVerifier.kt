package com.ukkera.pos.database

import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.NativeHistoryPhysicalCellV2
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** This verifier exists only on the actual original all-writer owner. Observations carry the
 * exact borrowed frame privately; matching epoch/status/data cannot create that affinity. */
internal class RoomRuntimeHistoryVerifier internal constructor(private val owner: RoomRuntimeHistoryOwner) : MasterRuntimeVerifier {
    override suspend fun writer(observation: BranchObservation): RuntimeVerification =
        if (owner.permits(observation,true)) RuntimeVerification.VERIFIED else RuntimeVerification.UNAVAILABLE
    override suspend fun absentOperation(observation: BranchObservation,identity: OperationIdentity): RuntimeVerification {
        if (!owner.permits(observation,true) || identity.branchId!=owner.originalScope.branchId) return RuntimeVerification.UNAVAILABLE
        val frame=observation.runtimeFrame ?: return RuntimeVerification.UNAVAILABLE
        // Complete original history was admitted, every subsequent owner loan is contiguous,
        // and BOTH retained members are absent in this exact current borrowed snapshot.
        return frame.connection.usePrepared("SELECT 1 FROM operation_result WHERE scope=? AND operationId=? UNION ALL SELECT 1 FROM journal_envelope WHERE scope=? AND operationId=? LIMIT 1") {
            it.bindText(1,observation.scope.storageKey()); it.bindText(2,identity.operationId.value)
            it.bindText(3,observation.scope.storageKey()); it.bindText(4,identity.operationId.value)
            if (!it.step()) RuntimeVerification.VERIFIED else RuntimeVerification.REFUSED
        }
    }
    override suspend fun reservableOperation(observation: BranchObservation,operationId: OperationId,actorId: DomainId): RuntimeVerification {
        if (!owner.permits(observation,true)) return RuntimeVerification.UNAVAILABLE
        val frame=observation.runtimeFrame ?: return RuntimeVerification.UNAVAILABLE
        if (!frame.permitsReservationActor(actorId)) return RuntimeVerification.UNAVAILABLE
        // Reservations are canonical immutable aggregate rows, NOT arbitrary unique-claim
        // keys. Even a same-actor reservation is occupied; malformed payloads are not absence.
        if (frame.reservedOperation(operationId)!=null) return RuntimeVerification.REFUSED
        val scope=observation.scope.storageKey()
        val retained=frame.connection.usePrepared("SELECT 1 FROM operation_result WHERE scope=? AND operationId=? UNION ALL SELECT 1 FROM journal_envelope WHERE scope=? AND operationId=? LIMIT 1") {
            it.bindText(1,scope); it.bindText(2,operationId.value)
            it.bindText(3,scope); it.bindText(4,operationId.value)
            it.step()
        }
        if(retained || historicalKey(frame,RuntimeHistoryTableV2.OPERATION,listOf(scope,operationId.value)) ||
            historicalKey(frame,RuntimeHistoryTableV2.AGGREGATE,listOf(scope,ReservedOperations.KIND,operationId.value)))
            return RuntimeVerification.REFUSED
        // Include this actual transaction's not-yet-sealed versions. An insert followed by
        // deletion cannot make the same future ID free inside one frame.
        if(capturedReservation(frame,scope,operationId)) return RuntimeVerification.REFUSED
        frame.recordDependency(listOf("future-operation-absence",operationId.value,actorId.value,
            frame.originalParent.historySequence.toString(),frame.originalParent.historyDigest.value))
        return if(owner.permits(observation,true)) RuntimeVerification.VERIFIED else RuntimeVerification.UNAVAILABLE
    }

    /** Full scan admitted every historical lineage; point reads use the SAME canonical typed
     * key as its complete row-version/tip census, including tombstones and superseded values. */
    private suspend fun historicalKey(frame: RoomRuntimeHistoryFrame,table: RuntimeHistoryTableV2,parts: List<String>): Boolean {
        val cells=parts.map { part ->
            val bytes=part.encodeToByteArray(throwOnInvalidSequence=true)
            try { NativeHistoryPhysicalCellV2.Text(bytes) } finally { bytes.fill(0) }
        }
        var key: ByteArray?=null
        return try {
            val encoded=RuntimeHistoryRowCodecV2.encodeKey(table,cells); key=encoded
            frame.connection.usePrepared("SELECT 1 FROM runtime_history_row_tip_v2 WHERE tableTag=? AND `key`=? UNION ALL SELECT 1 FROM runtime_history_row_version_v2 WHERE tableTag=? AND `key`=? LIMIT 1") {
                it.bindLong(1,table.tag.toLong()); it.bindBlob(2,encoded)
                it.bindLong(3,table.tag.toLong()); it.bindBlob(4,encoded)
                it.step()
            }
        } finally { key?.fill(0); cells.forEach { it.clear() } }
    }
    private suspend fun capturedReservation(frame: RoomRuntimeHistoryFrame,scope: String,operation: OperationId): Boolean {
        val parts=listOf(scope,ReservedOperations.KIND,operation.value).map { it.encodeToByteArray(throwOnInvalidSequence=true) }
        return try {
            frame.connection.usePrepared("SELECT 1 FROM runtime_history_capture_v2 WHERE tableTag=? AND " +
                "((beforeKey1=? AND beforeKey2Text=? AND beforeKey3Text=?) OR (afterKey1=? AND afterKey2Text=? AND afterKey3Text=?)) LIMIT 1") {
                it.bindLong(1,RuntimeHistoryTableV2.AGGREGATE.tag.toLong())
                parts.forEachIndexed { index,bytes -> it.bindBlob(index+2,bytes); it.bindBlob(index+5,bytes) }
                it.step()
            }
        } finally { parts.forEach { it.fill(0) } }
    }
}
