package com.ukkera.pos.app.setup

import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalEmployeePolicyActivationDefinition
import com.ukkera.pos.security.LocalEmployeePolicyActivationPhase
import com.ukkera.pos.security.LocalEmployeePolicyActivationReference
import com.ukkera.pos.types.DomainId

/** Presentation progress only. Native retained history and the Master determine actual outcomes. */
public enum class LocalSetupEmployeePolicyStage {
    DEFINITION, PREPARATION_UNRESOLVED, REVIEW, APPROVAL_UNRESOLVED, APPROVED,
    ACTIVATION_UNRESOLVED, APPLIED, NOT_RECORDED, ORIGINAL_UNRESOLVED,
}

public enum class LocalSetupEmployeePolicyOutcome {
    APPLIED, NOT_RECORDED, DENIED, UNAVAILABLE, STORAGE_UNCERTAIN, BUSY,
}

/** Complete non-secret DATA. Native action/approval objects never enter the UI projection. */
public data class LocalSetupEmployeePolicyView(
    public val reviewId: DomainId,
    public val originalRequestId: DomainId,
    public val scope: BranchScope,
    public val stage: LocalSetupEmployeePolicyStage,
    public val actorId: DomainId? = null,
    public val originalActionId: DomainId? = null,
    public val nativePhase: LocalEmployeePolicyActivationPhase? = null,
    /** Entered data retained after an unknown prepare; it is not a successful native review. */
    public val submittedPolicy: ActivatedLocalPolicy? = null,
    public val requested: LocalEmployeePolicyActivationDefinition? = null,
    public val companyName: String? = null,
    public val headName: String? = null,
    public val headLogin: String? = null,
    public val rootFingerprint: String? = null,
    public val headAuthorityId: DomainId? = null,
    public val headEnrollmentId: DomainId? = null,
    public val authorityDigest: String? = null,
    public val outcome: LocalSetupEmployeePolicyOutcome? = null,
    public val eventId: DomainId? = null,
) {
    init {
        submittedPolicy?.let { require(it.scope == scope && it.actorId == actorId && it.revision.value > 0L) }
        requested?.let {
            require(it.request.requestId == originalRequestId && it.request.scope == scope &&
                it.request.targetId == actorId)
        }
        require((outcome == LocalSetupEmployeePolicyOutcome.APPLIED) == (eventId != null))
    }
    override fun toString(): String = "LocalSetupEmployeePolicyView([REDACTED])"
}

/** Genuine original-action references, not a current employee or policy directory. */
public class LocalSetupEmployeePolicies(
    public val originals: List<LocalEmployeePolicyActivationReference> = emptyList(),
    public val newDefinitionAvailable: Boolean = false,
) {
    override fun toString(): String = "LocalSetupEmployeePolicies([REDACTED])"
}
