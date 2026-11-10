package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction

/** CLOSED ordinary ApplyOriginal refusal candidates. The installed payment
 * producer must join the genuine native BEFORE application and complete earlier
 * Start/native-original lineage (plus earlier Apply for AlreadyApplied), then compare
 * the exact result and resources. The Pending candidate alone proves no mismatch.
 * Captured CASH_READ DATA does not grant current access or qualify another refusal. */
internal enum class RuntimeHistoricalFoodPaymentRejectionProfile(
    val command: CommandKey,
    val rejectionCode: String,
    val requirementReason: String,
    val documentRevision: Long,
) {
    ALREADY_APPLIED(CommandKey("sales.food.payment", 1), "food.payment.already_applied", "food.payment.already_applied", 1L),
    VALUE_OR_TENDER_MISMATCH(CommandKey("sales.food.payment", 1), "food.payment.pending_reconciliation", "food.payment.pending", 0L);

    fun acceptsRejection(code: String): Boolean = code == rejectionCode

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.action == SecurityAction.CASH_READ && value.requirement.verb == PermissionVerb.PERFORM &&
            value.requirement.scope == value.authorization.scope && value.requirement.operationId == value.operationId &&
            value.requirement.payloadDigest == value.semanticDigest && value.requirement.reasonCode == requirementReason &&
            value.requirement.documentRevision.value == documentRevision && value.requirement.limitKind == "none" &&
            value.requirement.amount == null && value.requirement.baseAmount == null &&
            value.requirement.ownerId == value.authorization.actorId

    companion object {
        const val CAPTURED_CHANGES: Int = 5

        /** Historical command-only selection remains AlreadyApplied-only. A command name
         * cannot distinguish the additive Pending lane; actual captures must select below. */
        fun fromCommand(command: CommandKey): RuntimeHistoricalFoodPaymentRejectionProfile? =
            ALREADY_APPLIED.takeIf { it.command == command }

        fun fromEvidence(value: RuntimeCommandAuthorizationEvidenceV1): RuntimeHistoricalFoodPaymentRejectionProfile? =
            entries.singleOrNull { it.command == CommandKey(value.commandName, value.commandVersion) &&
                it.requirementReason == value.requirement.reasonCode &&
                it.documentRevision == value.requirement.documentRevision.value }
    }
}
