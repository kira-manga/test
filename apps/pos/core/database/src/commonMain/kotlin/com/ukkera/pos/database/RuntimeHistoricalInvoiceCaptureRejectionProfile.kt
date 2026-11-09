package com.ukkera.pos.database

import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision

/** Candidate DATA for a closed V2 invoice-capture refusal. The installed purchasing
 * producer must re-normalize the original request and derive its exact first refusal,
 * result and resources from genuine already-joined native BEFORE sources. This profile
 * provides neither receiving/financial authority nor an accepting historical default. */
internal enum class RuntimeHistoricalInvoiceCaptureRejectionProfile(val command: CommandKey) {
    CAPTURE(CommandKey("local.purchasing.invoice.record", 2));

    val rejectionCodes: Set<String> = setOf(
        "purchasing.identity_already_used",
        "purchasing.duplicate_reference",
        "purchasing.order_required",
        "purchasing.supplier_required",
        "purchasing.current_item_required",
        "purchasing.unit_snapshot_required",
        "purchasing.selected_order_line_required",
        "purchasing.capacity_required",
        "purchasing.capacity_unavailable",
    )

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.requirement.action == SecurityAction.PURCHASING_INVOICE_CAPTURE && value.requirement.verb == PermissionVerb.PERFORM &&
            value.requirement.scope == value.authorization.scope && value.requirement.operationId == value.operationId &&
            value.requirement.documentRevision == Revision(0) && value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.reasonCode.length in 1..128 && value.requirement.reasonCode.all { it.isLetterOrDigit() || it in "._-" } &&
            value.requirement.limitKind == "none" && value.requirement.amount == null &&
            value.requirement.baseAmount == null && value.requirement.ownerId == null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalInvoiceCaptureRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
