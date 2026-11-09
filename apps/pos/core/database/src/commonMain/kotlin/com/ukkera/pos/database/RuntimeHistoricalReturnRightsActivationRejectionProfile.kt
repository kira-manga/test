package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction

/** CLOSED documentary return-rights activation refusal candidate DATA. The installed
 * producer must authenticate the original signed candidate and derive the exact canonical
 * request, first refusal, requirement, result and resources from genuine native BEFORE
 * sources with already joined earlier writers. Negative/unknown signature truth is not a
 * supported durable refusal. A command/code/grant alone supplies no history or authority. */
internal enum class RuntimeHistoricalReturnRightsActivationRejectionProfile(val command: CommandKey) {
    ACTIVATION(CommandKey("local.return-rights.activate", 1));

    val rejectionCodes: Set<String> = setOf(
        "return.rights.revision_conflict",
        "return.rights.publication_already_used",
        "return.rights.policy_sequence_required",
    )

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.action == SecurityAction.CATALOG_ACTIVATE &&
            value.requirement.verb == PermissionVerb.ADMINISTER && value.requirement.scope == value.authorization.scope &&
            value.requirement.operationId == value.operationId && value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.reasonCode == "return.rights.activate" && value.requirement.limitKind == "none" &&
            value.requirement.amount == null && value.requirement.baseAmount == null && value.requirement.ownerId == null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalReturnRightsActivationRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
