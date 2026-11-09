package com.ukkera.pos.security

/** Explicit additional-device credential storage disposition; absence never selects ENROLL. */
public enum class LocalPairedDeviceCredentialJournalAction {
    /** Only the actual fresh native target's never-consumed first credential acquisition. */
    ENROLL,
    /** Verify the original allocation and existing complete native storage; never create. */
    REOPEN,
    /** Complete only existing ciphertext already fenced by its original native successor. */
    RECOVER,
}
