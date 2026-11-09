package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Revision

/** Closed candidate DATA for source-backed QUALIFY and explicit CASE Accept refusals.
 * The installed producer must reconstruct the exact original request, native BEFORE,
 * requirement, first refusal, result and complete resources before closure. The existing
 * profile name is retained; it is not an accepting source or a business-effect proof. */
internal enum class RuntimeHistoricalReturnCaseQualificationRejectionProfile(val command: CommandKey) {
    QUALIFY(CommandKey("sales.return.case.write", 1));

    val rejectionCode: String = "sales.return_case.refused"

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            value.requirement.verb == PermissionVerb.PERFORM && value.requirement.scope == value.authorization.scope &&
            value.requirement.operationId == value.operationId && value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.baseAmount == null && value.requirement.ownerId == null &&
            matchesCaseRequirement(value.requirement)

    private fun matchesCaseRequirement(value: RuntimeCommandRequirementV1): Boolean = when (value.reasonCode) {
        "return.case.qualify" -> value.action == SecurityAction.RETURN_CLAIM && value.documentRevision == Revision(0) &&
            value.limitKind == "none" && value.amount == null
        "return.case.accept.unavailable" -> value.action == SecurityAction.RETURN_CLAIM &&
            value.limitKind == "none" && value.amount == null
        "return.case.accept" -> value.action == SecurityAction.RETURN_ACCEPT && value.limitKind == "monetary" &&
            value.amount?.let { it.currency == Currency.EGP && it.minor >= 0L } == true
        else -> false
    }

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalReturnCaseQualificationRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
