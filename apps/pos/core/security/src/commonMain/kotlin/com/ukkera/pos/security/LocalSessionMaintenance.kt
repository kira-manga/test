package com.ukkera.pos.security

/** The exact revoked identities removed from a full account, not permission to erase history. */
public class LocalSessionArchival internal constructor(
    public val retained: LocalAccountSessionState,
    archived: List<LocalSessionRecord>,
) {
    public val archived: List<LocalSessionRecord> = archived.toList()
}

/**
 * Shared by the actual protected writer and historical verification. Neither method reads a
 * clock, authenticates a human, writes storage or grants a session. Callers must establish the
 * provenance of EVERY supplied record. Keeping these rules shared avoids a second retirement
 * algorithm which could silently renew a work session or erase a revoked identity at restart.
 */
public object LocalSessionMaintenance {
    public fun archiveWhenFull(state: LocalAccountSessionState): LocalSessionArchival? {
        if (state.sessions.size < 256) return null
        require(state.revision.value < Long.MAX_VALUE - 1 && state.generation.value < Long.MAX_VALUE - 1)
        val revoked = state.sessions.filter { it.status == SessionStatus.REVOKED }
        require(revoked.isNotEmpty())
        return LocalSessionArchival(LocalAccountSessionState(state.scope, state.actorId, state.enabled,
            state.revision.next(), state.generation.next(), state.sessions.filter { it.status == SessionStatus.ACTIVE }), revoked)
    }

    /** Access expiry alone does not retire a session with an unexpired original work deadline. */
    public fun retirementRequired(
        account: ProtectedLocalAccount,
        session: LocalSessionRecord,
        access: LocalAccessIssuance,
        refresh: LocalRefreshIssuance?,
        admission: LocalDeviceAdmission,
        current: LocalAuthenticationObservation,
    ): Boolean {
        val state = account.state
        require(session.scope == state.scope && session.actorId == state.actorId &&
            session.status == SessionStatus.ACTIVE && session.authenticationPurpose == AuthenticationPurpose.INTERACTIVE)
        require(access.scope == state.scope && access.actorId == state.actorId && access.sessionId == session.sessionId &&
            access.deviceId == session.deviceId && access.generation == session.generation)
        require(admission.scope == state.scope && admission.deviceId == session.deviceId)
        refresh?.let {
            require(it.scope == access.scope && it.actorId == access.actorId && it.sessionId == access.sessionId &&
                it.deviceId == access.deviceId && it.generation == access.generation && it.issuerContextId == access.issuerContextId &&
                it.issuedAt == access.issuedAt && it.credentialRevisionAtIssue == access.credentialRevisionAtIssue &&
                it.admissionRevisionAtIssue == access.admissionRevisionAtIssue)
        }
        val deadline = refresh?.expiresAtElapsedMillis ?: access.expiresAtElapsedMillis
        return access.issuerContextId != current.issuerContextId || access.issuedAt.bootId != current.moment.bootId ||
            current.moment.elapsedMillis >= deadline || access.credentialRevisionAtIssue != account.credentialRevision ||
            !admission.admitted || access.admissionRevisionAtIssue != admission.revision
    }
}
