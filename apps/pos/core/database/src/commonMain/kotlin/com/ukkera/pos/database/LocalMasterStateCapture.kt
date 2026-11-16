package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId

/**
 * Facts of one consistent Master full-state capture, produced by the Master's own serialized
 * native capture. DATA only; never authority, admission or a restore permit.
 */
public data class LocalMasterStateCapture(
    public val scope: BranchScope,
    public val installationId: DomainId,
    public val schemaVersion: Int,
    public val roomIdentityHash: String,
    public val historyGeneration: Long,
    public val historyRecordDigest: PayloadDigest,
) {
    override fun toString(): String = "LocalMasterStateCapture([REDACTED])"
}
