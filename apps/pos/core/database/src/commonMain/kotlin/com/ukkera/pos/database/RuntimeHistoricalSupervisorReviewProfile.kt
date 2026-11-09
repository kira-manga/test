package com.ukkera.pos.database

import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision

/** Closed historical audited-Review DATA, not a general rejected-command profile.
 * The installed producer must additionally decode the actual Review subtype and compare
 * its whole original/resolution projection. No request, result code or Boolean creates it. */
public enum class RuntimeHistoricalSupervisorReviewProfile(
    public val command: CommandKey,
    public val rejectionCode: String,
    private val reasonCode: String,
    private val actions: Set<SecurityAction>,
) {
    RETAIL(CommandKey("sales.financial-supervisor", 1), "sales.supervisor.reviewed",
        "financial.supervisor.review", setOf(SecurityAction.COLLECTION_RESOLVE, SecurityAction.REFUND_RESOLVE)),
    FOOD(CommandKey("sales.food.payment-supervisor", 1), "food.payment.supervisor.reviewed",
        "food.payment.supervisor.review", setOf(SecurityAction.COLLECTION_RESOLVE));

    internal fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.requirement.action in actions && value.requirement.verb == PermissionVerb.PERFORM &&
            value.requirement.documentRevision == Revision(0) && value.requirement.reasonCode == reasonCode &&
            value.requirement.limitKind == "monetary" && value.requirement.amount != null &&
            value.requirement.baseAmount == null && value.requirement.ownerId != null

    internal companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalSupervisorReviewProfile? =
            entries.singleOrNull { it.command == command }
    }
}
