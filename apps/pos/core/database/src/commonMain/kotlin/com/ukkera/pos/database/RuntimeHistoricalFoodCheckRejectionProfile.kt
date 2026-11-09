package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction

/** Closed scalar-refusal candidate only. The installed FOOD producer must independently
 * derive Draft2 duplicate or Add3 stale revision from the genuine BEFORE row/claim/original,
 * enabled activity and (Add) complete open owner-index cut. This gate grants no authority
 * and admits neither success, an absent source, recipes, capacity nor another FOOD form. */
internal enum class RuntimeHistoricalFoodCheckRejectionProfile(val command: CommandKey) {
    FOOD(StockStreamingCommandsV2.FOOD);

    val rejectionCodes: Set<String> get() = setOf(DRAFT_DUPLICATE, ADD_REVISION_CONFLICT)

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.verb == PermissionVerb.PERFORM && value.requirement.scope == value.authorization.scope &&
            value.requirement.operationId == value.operationId && value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.reasonCode == "food.operation" && value.requirement.limitKind == "none" &&
            value.requirement.amount == null && value.requirement.baseAmount == null && value.requirement.ownerId != null &&
            when (value.requirement.action) {
                SecurityAction.ORDER_CREATE -> value.requirement.documentRevision.value == 0L &&
                    value.requirement.ownerId == value.authorization.actorId
                SecurityAction.ORDER_EDIT -> true // Requested revision; actual BEFORE owner is joined separately.
                else -> false
            }

    fun markerFields(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): List<String> =
        listOf(MARKER, value.operationId.value, value.semanticDigest.value,
            header.parentSequence.toString(), header.parentDigest.value)

    /** A missing marker leaves compatible old originals inert. A duplicate or different
     * marker is contradictory native evidence, not a fallback to another refusal lane. */
    fun hasMarker(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1,
        dependencies: ByteArray): Boolean {
        val markers = runtimeFoodCheckDependencyFields(dependencies).filter { it.firstOrNull() == MARKER }
        if (markers.isEmpty()) return false
        historyShapeV2(markers.size == 1 && markers.single() == markerFields(header, value))
        return true
    }

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        const val MARKER: String = "food-check.scalar-refusal/v1"
        const val DRAFT_DUPLICATE: String = "food.stock.rule.duplicate_source_allocation"
        const val ADD_REVISION_CONFLICT: String = "food.stock.rule.revision_conflict"
        fun fromCommand(command: CommandKey): RuntimeHistoricalFoodCheckRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}

/** The unchanged native dependency framing, with the same closed field-count bound as
 * the general business join. Decoding supplies inert observations, never read authority. */
internal fun runtimeFoodCheckDependencyFields(bytes: ByteArray): List<List<String>> {
    historyCapacityV2(bytes.size <= RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES)
    fun fields(input: ByteArray): List<ByteArray> {
        val values = mutableListOf<ByteArray>(); var at = 0
        try {
            while (at < input.size) {
                historyShapeV2(input.size - at >= 4 && values.size < 1024)
                var size = 0L
                repeat(4) { size = (size shl 8) or (input[at++].toLong() and 255) }
                historyShapeV2(size <= input.size - at)
                val end = at + size.toInt(); values += input.copyOfRange(at, end); at = end
            }
            return values
        } catch (failure: Throwable) { values.forEach { it.fill(0) }; throw failure }
    }
    val rows = fields(bytes)
    return try {
        rows.map { row ->
            val parts = fields(row)
            try { parts.map(::historyUtf8V2) } finally { parts.forEach { it.fill(0) } }
        }
    } finally { rows.forEach { it.fill(0) } }
}
