package com.ukkera.pos.database

import com.ukkera.pos.security.LocalBranchInstallationDefinition
import com.ukkera.pos.security.LocalBranchInstallationRequest
import com.ukkera.pos.security.sha256
import kotlin.coroutines.cancellation.CancellationException

/**
 * Inert, owned snapshot of the COMPLETE first-install definition, never a verified approval.
 * The bytes are the existing versioned six-field request preimage, not a new signed/receipt
 * protocol. Policy extensions, token durations, login and admission descriptors remain exact.
 * The first actor and singleton admitted-device set are fixed by the definition invariants.
 *
 * No password, bearer, key material, native handle or authorization flag is retained. Identity,
 * login and grants are still private application data: a native journal must independently
 * protect/authenticate them and retain the original unresolved intent BEFORE dispatch.
 * This codec neither writes that journal nor authorizes namespace creation or installation.
 */
public class LocalBranchInstallationDefinitionSnapshot private constructor(
    private val bytes: ByteArray,
    public val request: LocalBranchInstallationRequest,
) {
    /** Caller owns this copy; changing it cannot retarget this snapshot or its computed request. */
    public fun copyBytes(): ByteArray = bytes.copyOf()

    /** A fresh deep reconstruction on every call; returned collections never alias retained bytes. */
    public fun definition(): LocalBranchInstallationDefinition =
        installationSnapshot { LocalBranchInstallationCodec.decodeDefinition(bytes) }

    override fun toString(): String = "LocalBranchInstallationDefinitionSnapshot([REDACTED])"

    public companion object {
        /** The original complete-definition budget, independent of the smaller receipt budget. */
        public const val MAXIMUM_BYTES: Int = 2 * 1024 * 1024

        /** Revalidates/captures full data. Invalid or unsupported input throws a redacted error. */
        public fun capture(definition: LocalBranchInstallationDefinition): LocalBranchInstallationDefinitionSnapshot =
            installationSnapshot { fromOwned(LocalBranchInstallationCodec.encodeDefinition(definition)) }

        /**
         * Bounded before copying. Reconstructs and verifies exact canonical framing, then
         * recomputes the request digest; no persisted/supplied digest is trusted as authority.
         * Caller-owned input is never retained or cleared. Invalid input throws a redacted error.
         */
        public fun decode(bytes: ByteArray): LocalBranchInstallationDefinitionSnapshot = installationSnapshot {
            require(bytes.size in 1..MAXIMUM_BYTES)
            fromOwned(bytes.copyOf())
        }

        private fun fromOwned(bytes: ByteArray): LocalBranchInstallationDefinitionSnapshot = try {
            val definition = LocalBranchInstallationCodec.decodeDefinition(bytes)
            LocalBranchInstallationDefinitionSnapshot(bytes, LocalBranchInstallationRequest(definition.identity, sha256(bytes)))
        } catch (failure: Throwable) { bytes.fill(0); throw failure }
    }
}

private inline fun <T> installationSnapshot(block: () -> T): T = try { block() }
catch (cancelled: CancellationException) { throw cancelled }
catch (_: Exception) { throw IllegalArgumentException("Local branch installation definition snapshot unavailable") }

