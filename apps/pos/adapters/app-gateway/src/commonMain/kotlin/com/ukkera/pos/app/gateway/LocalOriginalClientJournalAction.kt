package com.ukkera.pos.app.gateway

/** Shared opening intent; the native adapter still enforces existing-only and original custody. */
public enum class LocalOriginalClientJournalAction {
    ENROLL_FIRST_CLIENT,
    /** Completed unused credentials, before the first intent-journal key was created. */
    CONTINUE_FIRST_CLIENT,
    REOPEN_EXISTING,
    /** Resume only an exact write-ahead record already fenced by its native successor. */
    RECOVER_PENDING_WRITES,
}
