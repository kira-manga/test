package com.ukkera.pos.app.gateway

import com.ukkera.pos.database.DeferredReason

/** Fixed opt-in failure observation only; no identifiers, values, request bodies or authority. */
internal enum class LocalRetailPhysicalFailureStage { MUTATION_DEFERRED, MUTATION_UNEXPECTED, GUARDED_INPUT, GUARDED_FAILURE,
    REMOTE_RESOLUTION_SELECTION_UNAVAILABLE, REMOTE_RESOLUTION_EXCHANGE_UNAVAILABLE,
    REMOTE_RESOLUTION_HELD, REMOTE_RESOLUTION_UNCERTAIN, REMOTE_RESOLUTION_STAGE_BUDGET_EXHAUSTED,
}
internal expect fun reportLocalRetailPhysicalFailure(stage: LocalRetailPhysicalFailureStage,
    reason: DeferredReason?, failure: Throwable?)
