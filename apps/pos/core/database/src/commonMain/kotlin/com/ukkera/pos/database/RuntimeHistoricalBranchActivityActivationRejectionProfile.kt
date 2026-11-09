package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction

/** CLOSED initial branch-activity refusal DATA. The installed activity producer must
 * authenticate the exact candidate and prove the genuine earlier activation before
 * deriving this single refusal. No current grant, replacement, unavailable-source
 * certificate, borrowed approval or native-original authority is supplied here. */
internal enum class RuntimeHistoricalBranchActivityActivationRejectionProfile(val command: CommandKey) {
    INITIAL_ACTIVATION(CommandKey("local.branch.activity.activate-initial", 1));

    val rejectionCodes: Set<String> = setOf("branch.activity.initial_already_activated")

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.action == SecurityAction.BRANCH_ACTIVITY_ACTIVATE &&
            value.requirement.verb == PermissionVerb.ADMINISTER && value.requirement.scope == value.authorization.scope &&
            value.requirement.operationId == value.operationId && value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.reasonCode == "branch.activity.activate_initial" && value.requirement.limitKind == "none" &&
            value.requirement.amount == null && value.requirement.baseAmount == null && value.requirement.ownerId == null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalBranchActivityActivationRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
