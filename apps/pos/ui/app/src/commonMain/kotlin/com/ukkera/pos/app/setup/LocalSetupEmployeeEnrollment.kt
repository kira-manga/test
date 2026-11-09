package com.ukkera.pos.app.setup

import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalEmployeeEnrollmentDefinition
import com.ukkera.pos.security.LocalEmployeeEnrollmentPhase
import com.ukkera.pos.security.LocalEmployeeEnrollmentReference
import com.ukkera.pos.types.DomainId

/** Presentation only. The original native action, approval and custody stay on their actual owner. */
public enum class LocalSetupEmployeeEnrollmentStage {
    DEFINITION, REVIEW, APPROVAL_UNRESOLVED, APPROVED, ENROLLMENT_UNRESOLVED,
    ACCOUNT_CREATED, JOURNAL_UNRESOLVED, JOURNAL_OPENED, ORIGINAL_UNRESOLVED, REJECTED,
}

/** Complete native-reviewed data, never a caller approval, password, journal or employee session. */
public data class LocalSetupEmployeeEnrollmentView(
    public val reviewId: DomainId,
    public val originalRequestId: DomainId,
    public val actorId: DomainId,
    public val scope: BranchScope,
    public val admittedDeviceIds: Set<DomainId>,
    public val stage: LocalSetupEmployeeEnrollmentStage,
    public val originalActionId: DomainId? = null,
    public val nativePhase: LocalEmployeeEnrollmentPhase? = null,
    public val requested: LocalEmployeeEnrollmentDefinition? = null,
    public val companyName: String? = null,
    public val headName: String? = null,
    public val headLogin: String? = null,
    public val rootFingerprint: String? = null,
    public val serviceProblem: LocalServiceProblem? = null,
    /** Existing-only reopening can follow a previously attempted journal enrollment. */
    public val firstJournalEnrollmentAvailable: Boolean = false,
) {
    init {
        require(admittedDeviceIds.isNotEmpty())
        requested?.let {
            require(it.request.requestId == originalRequestId && it.request.targetId == actorId &&
                it.request.scope == scope && it.definition.admittedDeviceIds == admittedDeviceIds)
        }
    }
    override fun toString(): String = "LocalSetupEmployeeEnrollmentView([REDACTED])"
}

/** Native-retained originals for exact selection, not an account directory or enrollment authority. */
public class LocalSetupEmployeeEnrollments(
    public val originals: List<LocalEmployeeEnrollmentReference> = emptyList(),
    public val newDefinitionAvailable: Boolean = false,
) {
    override fun toString(): String = "LocalSetupEmployeeEnrollments([REDACTED])"
}
