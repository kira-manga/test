package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction

/** CLOSED counter-observation refusal candidate DATA. The installed receipt producer must
 * qualify the genuine immutable paid Finalize and complete native BEFORE receipt ancestry,
 * then derive the whole original requirement, first refusal, result and resources. Missing
 * or unsupported originals do not become an absence proof or an accepting refusal default.
 * This gate creates no employee, physical-delivery or return-qualification authority. */
internal enum class RuntimeHistoricalRetailCustomerReceiptRejectionProfile(val command: CommandKey) {
    RECEIPT(CommandKey("sales.customer-receipt.write", 1));

    val rejectionCode: String = "sales.customer_receipt.refused"

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.action == SecurityAction.CUSTOMER_RECEIPT_RECORD &&
            value.requirement.verb == PermissionVerb.PERFORM && value.requirement.scope == value.authorization.scope &&
            value.requirement.operationId == value.operationId && value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.reasonCode == "customer.receipt.record" && value.requirement.limitKind == "none" &&
            value.requirement.amount == null && value.requirement.baseAmount == null && value.requirement.ownerId != null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalRetailCustomerReceiptRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
