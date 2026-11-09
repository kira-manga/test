package com.ukkera.pos.database

import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency

/** Candidate DATA for one closed captured-invoice matching and recognition refusal.
 * The installed purchasing producer must bind the actual immutable invoice revision and
 * full raw claim, then derive the canonical first refusal/result/resources from genuine
 * already-joined native BEFORE sources. This gate grants no matching/payable authority;
 * unavailable invoice or final-time sources cannot become a historical refusal. */
internal enum class RuntimeHistoricalCapturedInvoiceMatchRejectionProfile(val command: CommandKey) {
    MATCH_RECOGNIZE(CommandKey("local.purchasing.invoice.match-recognize", 2));

    val rejectionCodes: Set<String> = setOf(
        "purchasing.original_required",
        "purchasing.original_revision_conflict",
        "purchasing.original_association_mismatch",
        "purchasing.identity_already_used",
        "purchasing.stale_revision",
        "purchasing.order_required",
        "purchasing.order_line_required",
        "purchasing.invoice_line_required",
        "purchasing.order_association_conflict",
        "purchasing.allocation_association_conflict",
        "purchasing.multi_order_association_unsupported",
        "purchasing.variance_requires_approval",
        "purchasing.receipt_required",
        "purchasing.receipt_source_mismatch",
        "purchasing.accepted_receipt_required",
        "purchasing.capacity_required",
        "purchasing.capacity_unavailable",
        "purchasing.quantity_unit_mismatch",
        "purchasing.remaining_quantity_exceeded",
        "purchasing.whole_serial_required",
        "purchasing.receipt_cost_variance",
        "purchasing.rounding_variance",
    )

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.requirement.action == SecurityAction.PURCHASING_INVOICE_MATCH_RECOGNIZE &&
            value.requirement.verb == PermissionVerb.PERFORM &&
            value.requirement.scope == value.authorization.scope && value.requirement.operationId == value.operationId &&
            value.requirement.documentRevision.value >= 1 && value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.reasonCode.length in 1..128 && value.requirement.reasonCode.all { it.isLetterOrDigit() || it in "._-" } &&
            value.requirement.limitKind == "monetary" &&
            value.requirement.amount?.let { it.currency == Currency.EGP && it.minor >= 0L } == true &&
            value.requirement.baseAmount == null && value.requirement.ownerId == null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalCapturedInvoiceMatchRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
