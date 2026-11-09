package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction

/** Candidate DATA for the exact source-backed stale disposition RETURN refusal.
 * Only DISPOSITION_REVISION_CHANGED may complete this lane. The installed sales
 * producer must derive that first refusal from genuine already-joined native BEFORE
 * acceptance/disposition sources and compare the whole original requirement, result
 * and resources. This gate is not a refusal proof, current grant or Apply profile. */
internal enum class RuntimeHistoricalReturnStockDispositionRejectionProfile(val command: CommandKey) {
    STALE_DISPOSITION(CommandKey("sales.return.stock", 1));

    val rejectionCode: String = "sales.return.stock.rejected"

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.action == SecurityAction.INVENTORY_ADJUST && value.requirement.verb == PermissionVerb.PERFORM &&
            value.requirement.scope == value.authorization.scope && value.requirement.operationId == value.operationId &&
            value.requirement.payloadDigest == value.semanticDigest && value.requirement.reasonCode == "return.stock.post" &&
            value.requirement.limitKind == "none" && value.requirement.amount == null &&
            value.requirement.baseAmount == null && value.requirement.ownerId != null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalReturnStockDispositionRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
