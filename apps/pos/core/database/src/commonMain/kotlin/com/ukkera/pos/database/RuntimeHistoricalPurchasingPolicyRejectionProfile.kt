package com.ukkera.pos.database

import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction

/** Closed policy-activation rejection DATA. The installed policy producer must derive the
 * exact refusal from authenticated policy bytes and genuine native BEFORE sources. This
 * profile supplies no current employee, policy, receiving or supervisor authority. */
internal enum class RuntimeHistoricalPurchasingPolicyRejectionProfile(val command: CommandKey) {
    ACTIVATION(CommandKey("local.purchasing.policy.activate", 1));

    val rejectionCodes: Set<String> = setOf(
        "purchasing.policy.stale_revision",
        "purchasing.policy.publication_already_used",
    )

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.requirement.action == SecurityAction.CATALOG_ACTIVATE && value.requirement.verb == PermissionVerb.ADMINISTER &&
            value.requirement.reasonCode == "purchasing.policy.activate" && value.requirement.limitKind == "none" &&
            value.requirement.amount == null && value.requirement.baseAmount == null && value.requirement.ownerId == null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalPurchasingPolicyRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
