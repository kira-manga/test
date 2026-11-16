package com.ukkera.pos.database

import com.ukkera.pos.types.DomainId

/**
 * Exact generation-one source DATA from the already committed original Start. Only an actual
 * currently acquiring kernel intake invocation can obtain it, once, for its held original
 * certificate/requirement. It is neither native custody nor a new Start/lifetime/instruction.
 * The native source must still hold/recheck its real Master FD, certificate and immutable raw
 * original, and the kernel repeats the unchanged serialized recovery-policy/import checks.
 */
public class NativeOriginalSourceData internal constructor(
    private val invocation: NativeOriginalIntakeInvocation,
    private val requirement: StoredBytes,
    private val certificate: StoredBytes,
    sources: List<AggregateChange>,
    originalInstallationId: DomainId,
) {
    private val snapshot = NativeOriginalSourceSnapshot(originalInstallationId, requirement, certificate, sources)
    public val sourceRows: List<AggregateChange> get() = snapshot.sourceRows

    public fun belongsTo(invocation: NativeOriginalIntakeInvocation,
        canonicalRequirement: StoredBytes, originalCertificate: StoredBytes): Boolean =
        this.invocation === invocation && requirement == canonicalRequirement && certificate == originalCertificate

    internal fun belongsTo(invocation: NativeOriginalIntakeInvocation): Boolean = this.invocation === invocation
    internal fun matchesMasterRoot(invocation: NativeOriginalIntakeInvocation, originalInstallationId: DomainId,
        retained: NativeOriginalRetained): Boolean = this.invocation === invocation && snapshot.matches(originalInstallationId, retained)

    override fun toString(): String = "NativeOriginalSourceData([REDACTED])"
}

/** Immutable comparison DATA, never an original owner, native lease or issued permission.
 * Production attaches it only after the actual owner finishes its source-restoration read. */
internal class NativeOriginalSourceSnapshot(
    private val originalInstallationId: DomainId,
    private val requirement: StoredBytes,
    private val certificate: StoredBytes,
    sources: List<AggregateChange>,
) {
    private val sources = sources.toList()
    val sourceRows: List<AggregateChange> get() = sources.toList()
    fun matches(originalInstallationId: DomainId, retained: NativeOriginalRetained): Boolean {
        val selected = retained.sourceRows
        return this.originalInstallationId == originalInstallationId && requirement == retained.canonicalRequirement &&
            certificate == retained.certificate && sources.size == 2 && selected.size == 2 &&
            sources.map { it.key }.distinct().size == 2 && selected.map { it.key }.distinct().size == 2 &&
            sources.all { original -> selected.singleOrNull { it.key == original.key }?.let {
                it.expectedRevision == original.expectedRevision && it.schema == original.schema && it.payload == original.payload
            } == true }
    }
}
