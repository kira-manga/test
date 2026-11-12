package com.ukkera.pos.app

import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalEmployeeCreationContextV1
import com.ukkera.pos.security.LocalEmployeeCreationTargetV1

/** Presentation and negative recovery custody only. The protected gateway owns the journal. */
internal data class LocalEmployeeCreationWorkspace(
    val original: LocalDelegatedEmployeeCreationV1? = null,
    val target: LocalEmployeeCreationTargetV1? = null,
    val context: LocalEmployeeCreationContextV1? = null,
    val passwordEntryOpen: Boolean = false,
    val pending: LocalEmployeeCreationPending? = null,
    val loaded: Boolean = false,
    val restricted: Boolean = true,
    val problem: LocalServiceProblem? = null,
    val observation: LocalEmployeeCreationObservation? = null,
    val acknowledgementAttempted: Boolean = false,
    val terminalBeforeAcknowledgement: Boolean = false,
) {
    val canSubmit: Boolean get() = loaded && !restricted && problem == null &&
        pending?.let { it.phase == LocalEmployeeCreationPhase.PREPARED && !it.stopRequested } == true
    val displayed: LocalEmployeeCreationPending? get() = pending.takeIf { loaded && !restricted }
    /** No private projection crosses an attachment. Only its immutable recovery identity and
     * any explicit acknowledgement obligation survive for re-observation by the same owner. */
    fun retire(): LocalEmployeeCreationWorkspace = copy(pending = null, loaded = false,
        restricted = true, problem = null, observation = null, target = null, context = null, passwordEntryOpen = false)
}

internal enum class LocalEmployeeCreationObservation { NOT_OBSERVED, PREPARED_REMOVED, TERMINAL_ACKNOWLEDGED }

/** Exact canonical association, not authority. Both temporary comparison buffers are retired. */
internal fun sameEmployeeCreationOriginal(left: LocalDelegatedEmployeeCreationV1,
    right: LocalDelegatedEmployeeCreationV1): Boolean {
    val before = left.copyWireBytes()
    return try {
        val after = right.copyWireBytes()
        try { before.contentEquals(after) } finally { after.fill(0) }
    } finally { before.fill(0) }
}
