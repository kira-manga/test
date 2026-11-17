package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction

/** Candidate DATA only. The installed producer must still decode Replace/Begin and
 * prove the exact first refusal from the complete native BEFORE cut. This is separate
 * from the manager reassignment profile and never admits a positive stock mutation. */
internal object RuntimeHistoricalNormalizedStockRejectionProfile {
    val command = CommandKey("sales.collection.stock", 2)
    const val CAPTURED_CHANGES = 5
    val rejectionCodes = setOf("sales.stock.rule_rejected", "sales.stock.unavailable")
    fun fromCommand(key: CommandKey): RuntimeHistoricalNormalizedStockRejectionProfile? = takeIf { key == command }

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean {
        val requirement = value.requirement
        if (header.purpose != RuntimeWritePurposeV2.COMMAND_REJECT || header.changeCount != CAPTURED_CHANGES ||
            value.purposeTag != RuntimeWritePurposeV2.COMMAND_REJECT.tag || value.capturedChanges != CAPTURED_CHANGES ||
            CommandKey(value.commandName, value.commandVersion) != command || value.actionApprovalId != null ||
            value.authorization.principalKind != PrincipalKind.LOCAL_EMPLOYEE ||
            value.authorization.authenticationPurpose != AuthenticationPurpose.INTERACTIVE ||
            requirement.verb != PermissionVerb.PERFORM || requirement.scope != value.authorization.scope ||
            requirement.operationId != value.operationId || requirement.payloadDigest != value.semanticDigest ||
            requirement.ownerId == null || requirement.documentRevision.value < 0 || requirement.baseAmount != null) return false
        return when (requirement.action) {
            SecurityAction.ORDER_EDIT -> requirement.reasonCode == "order.replace" &&
                requirement.limitKind == "none" && requirement.amount == null
            SecurityAction.COLLECTION_BEGIN -> requirement.reasonCode == "collection.begin" &&
                requirement.limitKind == "monetary" && requirement.amount?.minor?.let { it >= 0 } == true
            else -> false
        }
    }
}
