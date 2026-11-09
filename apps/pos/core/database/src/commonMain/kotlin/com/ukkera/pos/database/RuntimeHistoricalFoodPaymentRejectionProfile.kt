package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction

/** CLOSED ordinary ApplyOriginal AlreadyApplied candidate. The installed payment
 * producer must join the genuine native BEFORE application and complete earlier
 * Apply/Start/native-original lineage, then compare the exact result and resources.
 * Captured CASH_READ DATA does not grant current access or qualify another refusal. */
internal enum class RuntimeHistoricalFoodPaymentRejectionProfile(val command: CommandKey) {
    ALREADY_APPLIED(CommandKey("sales.food.payment", 1));

    val rejectionCode: String = "food.payment.already_applied"

    fun acceptsRejection(code: String): Boolean = code == rejectionCode

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.action == SecurityAction.CASH_READ && value.requirement.verb == PermissionVerb.PERFORM &&
            value.requirement.scope == value.authorization.scope && value.requirement.operationId == value.operationId &&
            value.requirement.payloadDigest == value.semanticDigest && value.requirement.reasonCode == rejectionCode &&
            value.requirement.documentRevision.value == 1L && value.requirement.limitKind == "none" &&
            value.requirement.amount == null && value.requirement.baseAmount == null &&
            value.requirement.ownerId == value.authorization.actorId

    companion object {
        const val CAPTURED_CHANGES: Int = 5

        fun fromCommand(command: CommandKey): RuntimeHistoricalFoodPaymentRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
