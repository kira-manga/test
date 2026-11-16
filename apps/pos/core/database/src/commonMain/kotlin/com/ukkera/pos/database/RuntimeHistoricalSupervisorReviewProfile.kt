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
        "food.payment.supervisor.review", setOf(SecurityAction.COLLECTION_RESOLVE)),
    FOOD_REFUND(CommandKey("sales.food.refund-supervisor", 1), "food.refund.supervisor.reviewed",
        "food.refund.supervisor.review", setOf(SecurityAction.REFUND_RESOLVE)),
    SUPPLIER_CASH(CommandKey("purchasing.supplier-cash-supervisor", 1), "purchasing.supplier_cash_supervisor_reviewed",
        "purchasing.supplier_cash_supervisor_review", setOf(SecurityAction.SUPPLIER_CASH_RESOLVE));

    internal fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.requirement.action in actions && value.requirement.verb == PermissionVerb.PERFORM &&
            value.requirement.documentRevision == Revision(0) && value.requirement.reasonCode == reasonCode &&
            value.requirement.limitKind == "monetary" && value.requirement.amount != null &&
            value.requirement.baseAmount == null && value.requirement.ownerId != null

    /** Separate SQL-only application refusal candidate for the two new financial keys.
     * The four audited Review shapes above remain exact and unchanged. This marker grants
     * nothing: a named producer must prove the canonical first refusal from whole native
     * BEFORE originals, requirement, resources, result and all five nonbusiness mutations. */
    internal fun matchesApplicationRefusal(header: RuntimeHistoryEventHeaderV2,
        value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == com.ukkera.pos.security.PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == com.ukkera.pos.security.AuthenticationPurpose.INTERACTIVE &&
            value.requirement.scope == value.authorization.scope && value.requirement.operationId == value.operationId &&
            value.requirement.payloadDigest == value.semanticDigest && matchesApplicationRequirement(value.requirement)

    private fun matchesApplicationRequirement(value: RuntimeCommandRequirementV1): Boolean {
        if (value.verb != PermissionVerb.PERFORM || value.ownerId == null || value.limitKind != "monetary" ||
            value.baseAmount != null || value.amount?.let { it.currency == com.ukkera.pos.types.Currency.EGP && it.minor > 0 } != true)
            return false
        return when (this) {
            FOOD_REFUND -> value.action == SecurityAction.REFUND_RESOLVE && when (value.reasonCode) {
                "food.refund.already_applied" -> value.documentRevision == Revision(1)
                "food.refund.pending" -> value.documentRevision == Revision(0)
                // Installed evaluate can refuse exact output capacity after successful
                // preparation. Whole BEFORE/output proof must select its actual refund ID.
                "food.refund.apply" -> value.documentRevision.value >= 1
                else -> false
            }
            // Selected supplier/progress STORAGE revisions are strictly positive. This
            // cannot overlap revision-zero Review even if original reason text is equal.
            SUPPLIER_CASH -> value.action == SecurityAction.SUPPLIER_CASH_RESOLVE && value.documentRevision.value >= 1 &&
                value.reasonCode.length in 1..96 && value.reasonCode.all { it.code in 0x21..0x7e &&
                    (it.isLetterOrDigit() || it in "._-") }
            RETAIL, FOOD -> false
        }
    }

    internal fun acceptsApplicationRefusal(value: RuntimeCommandAuthorizationEvidenceV1, code: String): Boolean {
        if (CommandKey(value.commandName, value.commandVersion) != command || !matchesApplicationRequirement(value.requirement)) return false
        return when (this) {
            FOOD_REFUND -> when (code) {
                "food.refund.already_applied" -> value.requirement.reasonCode == "food.refund.already_applied"
                "food.refund.pending_reconciliation" -> value.requirement.reasonCode in setOf("food.refund.pending", "food.refund.apply")
                else -> false
            }
            SUPPLIER_CASH -> code in setOf("purchasing.supplier_cash_already_applied", "purchasing.supplier_cash_pending")
            RETAIL, FOOD -> false
        }
    }

    internal fun acceptsCapturedRejection(header: RuntimeHistoryEventHeaderV2,
        value: RuntimeCommandAuthorizationEvidenceV1, code: String): Boolean =
        matches(header, value) && code == rejectionCode ||
            matchesApplicationRefusal(header, value) && acceptsApplicationRefusal(value, code)

    internal companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalSupervisorReviewProfile? =
            entries.singleOrNull { it.command == command }
    }
}
