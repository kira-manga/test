package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision

/** Closed original-publication observation candidate, not business authority. The
 * installed producer must independently bind the complete normalized request,
 * original sources, requirement, effects and native settlement witness. */
internal object RuntimeHistoricalUnpricedArrivalProfileV2 {
    private val record = CommandKey("local.stock.arrival.record", 2)
    private val transitions = setOf(CommandKey("local.stock.arrival.qualify", 2),
        CommandKey("local.stock.arrival.reject", 2), CommandKey("local.stock.arrival.dispatch", 2))

    fun matches(value: RuntimeCommandAuthorizationEvidenceV1): Boolean {
        val command = CommandKey(value.commandName, value.commandVersion)
        if (command != record && command !in transitions) return false
        val requirement = value.requirement
        return value.actionApprovalId == null &&
            value.authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
            value.authorization.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            requirement.action == SecurityAction.INVENTORY_ADJUST && requirement.verb == PermissionVerb.PERFORM &&
            requirement.scope == value.authorization.scope && requirement.ownerId == null &&
            requirement.operationId == value.operationId && requirement.payloadDigest == value.semanticDigest &&
            requirement.limitKind == "none" && requirement.amount == null && requirement.baseAmount == null &&
            (if (command == record) requirement.documentRevision == Revision(0) else requirement.documentRevision.value >= 1) &&
            requirement.reasonCode.length in 1..128 &&
            requirement.reasonCode.all { it.isLetterOrDigit() || it in "._-" }
    }
}
