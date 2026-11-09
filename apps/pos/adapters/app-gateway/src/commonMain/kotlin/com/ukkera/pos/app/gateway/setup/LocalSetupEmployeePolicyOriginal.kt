package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyOutcome
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyStage
import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyView
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalEmployeePolicyActivationAction
import com.ukkera.pos.security.LocalEmployeePolicyActivationAuthorityReceipt
import com.ukkera.pos.security.LocalEmployeePolicyActivationDefinition
import com.ukkera.pos.security.LocalEmployeePolicyActivationPhase
import com.ukkera.pos.types.DomainId

/** Same-native-owner workflow memory only. This stores no secret and grants no effect authority. */
internal class LocalSetupEmployeePolicyOriginal(
    val requestId: DomainId,
    val scope: BranchScope,
    var actorId: DomainId? = null,
) {
    var preparationAttempted: Boolean = false
    var activationAttempted: Boolean = false
    var actionId: DomainId? = null
    var submittedPolicy: ActivatedLocalPolicy? = null
    var requested: LocalEmployeePolicyActivationDefinition? = null
    var action: LocalEmployeePolicyActivationAction? = null
    var approval: LocalEmployeePolicyActivationAuthorityReceipt? = null
    var phase: LocalEmployeePolicyActivationPhase? = null
    var companyName: String? = null
    var headName: String? = null
    var headLogin: String? = null
    var rootFingerprint: String? = null

    fun matches(requested: LocalEmployeePolicyActivationDefinition): Boolean {
        if (requested.request.requestId != requestId || requested.request.scope != scope ||
            requested.request.targetId != actorId || requested.nextPolicy.revision.value <= 0L) return false
        submittedPolicy?.let { if (!sameEmployeePolicyData(it, requested.nextPolicy)) return false }
        this.requested?.let { if (!sameEmployeePolicyDefinition(it, requested)) return false }
        return true
    }

    fun projection(stage: LocalSetupEmployeePolicyStage, outcome: LocalSetupEmployeePolicyOutcome? = null,
        eventId: DomainId? = null): LocalSetupEmployeePolicyView = LocalSetupEmployeePolicyView(
        reviewId = DomainId.random(), originalRequestId = requestId, scope = scope, stage = stage,
        actorId = actorId, originalActionId = actionId, nativePhase = phase,
        submittedPolicy = submittedPolicy, requested = requested, companyName = companyName,
        headName = headName, headLogin = headLogin, rootFingerprint = rootFingerprint,
        headAuthorityId = approval?.headAuthorityId, headEnrollmentId = approval?.headEnrollmentId,
        authorityDigest = approval?.authorityDigest?.value, outcome = outcome, eventId = eventId,
    )

    override fun toString(): String = "LocalSetupEmployeePolicyOriginal([REDACTED])"
}

/** Complete DATA equality only. The existing native bridge remains the sole policy verifier. */
private fun sameEmployeePolicyData(a: ActivatedLocalPolicy, b: ActivatedLocalPolicy): Boolean {
    if (a.scope != b.scope || a.actorId != b.actorId || a.revision != b.revision ||
        a.sessionPolicy != b.sessionPolicy || a.throttlePolicy != b.throttlePolicy) return false
    val aApproval = a.approvalPolicy
    val bApproval = b.approvalPolicy
    if ((aApproval == null) != (bApproval == null)) return false
    if (aApproval != null && bApproval != null && (aApproval.validForMillis != bApproval.validForMillis ||
            aApproval.namedSelfApprovalActions != bApproval.namedSelfApprovalActions)) return false
    val aGrants = a.grants
    val bGrants = b.grants
    return aGrants.size == bGrants.size && aGrants.indices.all { index ->
        val x = aGrants[index]
        val y = bGrants[index]
        x.permission == y.permission && x.scope == y.scope && x.effect == y.effect &&
            x.limits == y.limits && x.ownerAccess == y.ownerAccess && x.namedSubjectId == y.namedSubjectId &&
            x.targetIds == y.targetIds && x.delegationCeiling == y.delegationCeiling
    }
}

internal fun sameEmployeePolicyDefinition(a: LocalEmployeePolicyActivationDefinition,
    b: LocalEmployeePolicyActivationDefinition): Boolean {
    if (a.definitionDigest != b.definitionDigest || a.request != b.request) return false
    val left = a.copyWireBytes()
    return try {
        val right = b.copyWireBytes()
        try { left.contentEquals(right) } finally { right.fill(0) }
    } finally { left.fill(0) }
}
