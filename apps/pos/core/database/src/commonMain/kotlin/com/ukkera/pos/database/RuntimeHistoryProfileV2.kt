package com.ukkera.pos.database

import com.ukkera.pos.security.NativeHistoryColumnV2
import com.ukkera.pos.security.NativeHistoryPhysicalReadProfileV2
import com.ukkera.pos.security.NativeHistorySqliteTypeV2
import com.ukkera.pos.security.NativeHistoryTableV2
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2
import com.ukkera.pos.types.DomainId

/**
 * V2 read profile consumed by the original native scanner. One physical registry is shared
 * with security; database must not maintain a second table/key/storage-class vocabulary.
 * These conservative read ceilings do NOT make the writer/large-recovery/backup profile
 * usable. Capacity rejection is unresolved, never a shorter census or a truncated record.
 */
internal object RuntimeHistoryProfileV2 {
    const val PROFILE = SelfOwnedNativeHistoryWireV2.PROFILE
    const val FORMAT = SelfOwnedNativeHistoryWireV2.FORMAT
    const val PROPOSED_ROOM_VERSION = 7
    const val MAX_METADATA_PAGE = NativeHistoryPhysicalReadProfileV2.METADATA_PAGE
    const val MAX_SCHEMA_OBJECTS = NativeHistoryPhysicalReadProfileV2.MAX_SCHEMA_OBJECTS
    const val MAX_KEY_BYTES = NativeHistoryPhysicalReadProfileV2.MAX_KEY_BYTES
    const val MAX_HEADER_BYTES = NativeHistoryPhysicalReadProfileV2.MAX_SQL_TEXT_BYTES
    const val MAX_CHUNK_BYTES = NativeHistoryPhysicalReadProfileV2.CHUNK_BYTES
    const val MAX_ROW_BYTES = NativeHistoryPhysicalReadProfileV2.MAX_ROW_ARRAY_BYTES
    const val MAX_CHANGES = NativeHistoryPhysicalReadProfileV2.MAX_CHANGES
    const val MAX_RESOURCES = SelfOwnedNativeHistoryWireV2.MAX_RESOURCES
    const val MAX_CAPSULE_BYTES = SelfOwnedNativeHistoryWireV2.MAX_CAPSULE_BYTES
    const val MAX_EVENT_BYTES = NativeHistoryPhysicalReadProfileV2.MAX_EVENT_BYTES
    const val MAX_EVENT_CHUNKS = NativeHistoryPhysicalReadProfileV2.MAX_EVENT_CHUNKS
    const val MAX_EVENTS = SelfOwnedNativeHistoryWireV2.MAX_EVENTS
    const val MAX_MUTATIONS = MAX_EVENTS * MAX_CHANGES
    const val MAX_DATASET_BYTES = NativeHistoryPhysicalReadProfileV2.MAX_DATASET_BYTES
}

internal typealias RuntimeHistoryTableV2 = NativeHistoryTableV2
internal typealias RuntimeSqliteTypeV2 = NativeHistorySqliteTypeV2
internal typealias RuntimeHistoryColumnV2 = NativeHistoryColumnV2
internal fun historyColumnsV2(table: RuntimeHistoryTableV2): List<RuntimeHistoryColumnV2> =
    NativeHistoryPhysicalReadProfileV2.columns(table)

internal enum class RuntimeHistoryFormatFailureV2 { MALFORMED, CAPACITY, UNRESOLVED_PROTOCOL }
internal class RuntimeHistoryFormatRejectedV2(val failure: RuntimeHistoryFormatFailureV2) :
    IllegalArgumentException("runtime.history.v2.${failure.name.lowercase()}")
internal fun historyShapeV2(valid: Boolean) {
    if (!valid) throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
}
internal fun historyCapacityV2(valid: Boolean) {
    if (!valid) throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.CAPACITY)
}
internal fun historyAddV2(left: Long, right: Long, maximum: Long): Long {
    historyShapeV2(left >= 0 && right >= 0 && maximum >= 0)
    historyCapacityV2(left <= maximum && right <= maximum - left)
    return left + right
}
internal fun historyScopeV2(value: String) {
    val ids = value.split('/')
    historyShapeV2(value.length == 110 && ids.size == 3)
    ids.forEach(::historyIdV2)
}
internal fun historyIdV2(value: String) {
    try { DomainId(value) } catch (_: IllegalArgumentException) { historyShapeV2(false) }
}
internal fun historyDigestV2(value: String) {
    try { PayloadDigest(value) } catch (_: IllegalArgumentException) { historyShapeV2(false) }
}
internal fun historySequenceV2(value: Long) {
    historyShapeV2(value > 0); historyCapacityV2(value <= RuntimeHistoryProfileV2.MAX_EVENTS)
}
internal fun historyUtf8V2(bytes: ByteArray): String {
    historyCapacityV2(bytes.size <= RuntimeHistoryProfileV2.MAX_ROW_BYTES)
    val text = try { bytes.decodeToString(throwOnInvalidSequence = true) }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
    historyShapeV2('\u0000' !in text)
    return text
}

/** Explicit wire tags. A recognized purpose is not an authorization or a writer capability. */
internal enum class RuntimeWritePurposeV2(val tag: Int) {
    ORIGINAL_INSTALLATION(1), COMMAND_APPLY(2), COMMAND_REJECT(3), RESULT_RECOVERY_AUDIT(4),
    THROTTLE_RESERVE(5), THROTTLE_COMPLETE(6), SESSION_ISSUE(7), SESSION_REFRESH(8),
    SESSION_LOGOUT(9), ACCOUNT_ADMINISTRATION(10), POLICY_ADMINISTRATION(11),
    DEVICE_ADMINISTRATION(12), PROVISIONING(13), APPROVAL_ISSUE(14), APPROVAL_CONSUME(15),
    ROLE_TRANSITION(16), RECOVERY_TRANSITION(17), APPROVAL_RETIRE(18), SERIAL_PUBLICATION_REQUEST(19);
    companion object {
        fun fromTag(tag: Int): RuntimeWritePurposeV2 = entries.singleOrNull { it.tag == tag }
            ?: throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    }
}
