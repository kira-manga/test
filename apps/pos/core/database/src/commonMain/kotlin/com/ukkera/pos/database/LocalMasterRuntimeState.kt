package com.ukkera.pos.database

/** Sanitized observed phases only. Even ACTIVE is not a client, custody or admission token. */
public enum class LocalMasterRuntimePhase { STOPPED, RECOVERING, ACTIVE, HELD, RETIRED }

public enum class LocalMasterRuntimeWork { ORIGINAL_PAGER_RECOVERY, SCHEMA, HISTORY, ROWS, CROSSWALK, CLOSING }

/** No raw exception, private path, credential, original request/resource ID or evidence text. */
public enum class LocalMasterRuntimeHold {
    ORIGINAL_IDENTITY_UNAVAILABLE, UNSUPPORTED_PROFILE, INCOMPLETE_HISTORY, UNCERTAIN_COMMIT,
    ORIGINAL_AUTHORIZATION_REQUIRED, RESOURCE_RECONCILIATION_REQUIRED, ROLE_DENIED,
    CAPACITY, NATIVE_WORK_UNAVAILABLE, CLOSE_UNCONFIRMED,
}

/**
 * Counts are observations, not a completed scan. Null totals mean unknown, never zero work
 * remaining. No percentage or equality of counts can authorize an operational transition.
 * These are candidate reporting envelopes, not measured device memory/step budgets or RTOs.
 */
public data class LocalMasterRuntimeProgress(
    public val work: LocalMasterRuntimeWork,
    public val inspectedItems: Long,
    public val inspectedBytes: Long,
    public val totalItems: Long? = null,
    public val totalBytes: Long? = null,
) {
    init {
        require(inspectedItems >= 0 && inspectedBytes >= 0)
        require(totalItems == null || totalItems >= inspectedItems)
        require(totalBytes == null || totalBytes >= inspectedBytes)
    }
}

/**
 * Public inert projection. Constructing/copying it does not open Room, verify history,
 * expose a DAO/endpoint, change a desired role, drop an audit or finish any native work.
 * A later service owns real transitions and may independently deny disclosure/publication.
 */
public data class LocalMasterRuntimeState(
    public val phase: LocalMasterRuntimePhase,
    public val progress: LocalMasterRuntimeProgress? = null,
    public val hold: LocalMasterRuntimeHold? = null,
) {
    init {
        require((phase == LocalMasterRuntimePhase.HELD) == (hold != null))
        require(progress == null || phase == LocalMasterRuntimePhase.RECOVERING)
    }
}
