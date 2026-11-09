package com.ukkera.pos.database

import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Revision

/** Candidate DATA for one closed opening supplier-obligation refusal. The installed
 * purchasing producer must re-normalize the exact original request and derive its first
 * refusal/result/resources from genuine already-joined native BEFORE sources. This finite
 * command/amount gate alone proves neither the refusal nor current financial authority. */
internal enum class RuntimeHistoricalOpeningSupplierObligationRejectionProfile(val command: CommandKey) {
    OPENING_OBLIGATION(CommandKey("purchasing.opening-obligation", 1));

    val rejectionCodes: Set<String> = setOf(
        "purchasing.opening_obligation_identity_used",
        "purchasing.opening_obligation_duplicate_source",
        "purchasing.opening_obligation_duplicate_reference",
        "purchasing.opening_obligation_supplier_required",
        "purchasing.opening_obligation_stale_supplier",
    )

    fun matches(header: RuntimeHistoryEventHeaderV2, value: RuntimeCommandAuthorizationEvidenceV1): Boolean =
        header.purpose == RuntimeWritePurposeV2.COMMAND_REJECT && header.changeCount == CAPTURED_CHANGES &&
            value.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag && value.capturedChanges == CAPTURED_CHANGES &&
            CommandKey(value.commandName, value.commandVersion) == command && value.actionApprovalId == null &&
            value.requirement.action == SecurityAction.PURCHASING_APPROVE && value.requirement.verb == PermissionVerb.PERFORM &&
            value.requirement.scope == value.authorization.scope && value.requirement.operationId == value.operationId &&
            value.requirement.documentRevision == Revision(0) && value.requirement.payloadDigest == value.semanticDigest &&
            value.requirement.reasonCode.length in 1..128 && value.requirement.reasonCode.all { it.isLetterOrDigit() || it in "._-" } &&
            value.requirement.limitKind == "monetary" && value.requirement.amount?.let { it.currency == Currency.EGP && it.minor > 0L } == true &&
            value.requirement.baseAmount == null && value.requirement.ownerId == null

    companion object {
        const val CAPTURED_CHANGES: Int = 5
        fun fromCommand(command: CommandKey): RuntimeHistoricalOpeningSupplierObligationRejectionProfile? =
            entries.singleOrNull { it.command == command }
    }
}
