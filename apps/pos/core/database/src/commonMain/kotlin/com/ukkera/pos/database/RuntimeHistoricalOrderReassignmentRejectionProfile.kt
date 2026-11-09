package com.ukkera.pos.database

import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction

/** Candidate DATA for one closed legacy reassignment refusal. This command/requirement gate
 * is not a subtype or refusal proof. The installed sales producer must re-normalize the
 * exact ReassignOrder original and derive its first refusal from genuine native BEFORE
 * sources before any command/proof can complete. No current or receiving authority is made. */
internal enum class RuntimeHistoricalOrderReassignmentRejectionProfile(val command: CommandKey) {
    REASSIGNMENT(CommandKey("sales.collection.stock", 2));

    val rejectionCodes: Set<String> = setOf(
        "sales.stock.rule_rejected",
        "sales.stock.reassignment.unsupported_activity",
        "sales.stock.capacity_required",
    )

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.requirement.action == SecurityAction.ORDER_REASSIGN && value.requirement.verb == PermissionVerb.PERFORM &&
            value.requirement.scope == value.authorization.scope && value.requirement.operationId == value.operationId &&
            value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.reasonCode.length in 1..128 && value.requirement.reasonCode.all { it.isLetterOrDigit() || it in "._-" } &&
            value.requirement.limitKind == "none" && value.requirement.amount == null &&
            value.requirement.baseAmount == null && value.requirement.ownerId == null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalOrderReassignmentRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
