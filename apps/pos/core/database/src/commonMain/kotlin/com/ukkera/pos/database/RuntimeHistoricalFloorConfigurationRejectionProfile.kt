package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction

/** CLOSED floor/table configuration refusal candidate DATA. The installed directory
 * producer must derive the exact canonical request, first refusal, requirement, result
 * and resources from genuine native BEFORE rows and their already joined originals.
 * A code or configuration grant alone supplies no business/history proof or authority. */
internal enum class RuntimeHistoricalFloorConfigurationRejectionProfile(val command: CommandKey) {
    CONFIGURATION(CommandKey("local.food.floor-configuration", 1));

    val rejectionCodes: Set<String> = setOf(
        "food.floor.stale",
        "food.floor.capacity",
        "food.floor.identity_conflict",
        "food.floor.active_tables",
        "food.table.floor_required",
        "food.table.floor_changed",
    )

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.action == SecurityAction.FLOOR_CONFIGURE &&
            value.requirement.verb == PermissionVerb.ADMINISTER && value.requirement.scope == value.authorization.scope &&
            value.requirement.operationId == value.operationId && value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.reasonCode == "food.floor.configure" && value.requirement.limitKind == "none" &&
            value.requirement.amount == null && value.requirement.baseAmount == null && value.requirement.ownerId == null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalFloorConfigurationRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
