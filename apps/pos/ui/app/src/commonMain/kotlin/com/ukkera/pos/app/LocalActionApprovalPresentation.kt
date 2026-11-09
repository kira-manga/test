package com.ukkera.pos.app

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.OperationId

/** Entry-owned confirmation only. The original ceremony and command live in the durable journal. */
internal data class LocalActionApprovalPresentation(
    val operationId: OperationId,
    val approval: LocalActionApprovalState? = null,
    val confirmedDigest: PayloadDigest? = null,
    val passwordEntry: Boolean = false,
    /** An interrupted issuance/retirement is lookup-only; absence cannot finish its invocation. */
    val lookupRequired: Boolean = false,
    val problem: LocalServiceProblem? = null,
) {
    val canConfirm: Boolean get() = !lookupRequired && problem == null &&
        approval?.phase == LocalActionApprovalPhase.READY_TO_CONFIRM && approval.review != null
    val confirmed: Boolean get() = canConfirm && confirmedDigest == approval?.review?.confirmationDigest
    val canRetryRetirement: Boolean get() {
        val selected = approval ?: return false
        val attempt = selected.retirementAttempt ?: return false
        return !lookupRequired && problem == null && selected.phase == LocalActionApprovalPhase.RETIRE_RETRY_REQUIRED &&
            attempt.noAdmissionReason != null && attempt.sequence < Long.MAX_VALUE
    }
}
