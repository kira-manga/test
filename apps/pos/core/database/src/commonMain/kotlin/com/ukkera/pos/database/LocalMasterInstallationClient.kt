package com.ukkera.pos.database

import com.ukkera.pos.security.LocalBranchInstallationDefinition
import com.ukkera.pos.security.LocalBranchInstallationResult
import kotlinx.coroutines.flow.StateFlow

/**
 * Separate installation-only ingress, NOT part of [LocalMasterClient] or employee enrollment.
 * The real grouped owner uses its SAME security/store and genuine installed offline context.
 * No caller-selected verifier, principal, writer callback, key provider or shutdown escapes.
 * The endpoint also refuses ALL operational work during installation even if its full
 * reference leaks: narrowing this interface or hiding login buttons is not that enforcement.
 *
 * Implementations capture the full definition and an owned password copy before enqueue.
 * The caller keeps its password; only the service copy is cleared. Admission/preparation,
 * queued work and in-flight work share bounded capacity until actual work cleanup, not until
 * a caller times out. Work runs outside every inherited caller coroutine/Room loan.
 *
 * Retain the original identity/definition durably before submission. Cancellation, Busy,
 * Denied, Unavailable or StorageUncertain never prove absence of an earlier attempt.
 * Service deadline/storage uncertainty retires the owner and withholds late success; only
 * the initializer's actual authorized empty-store lookup can return NotRecorded.
 * Installed is a durable receipt, not sign-in, normal-runtime admission or physical closure.
 * The native owner must resolve/retain the receipt and actually close/reap this activation
 * before independently qualified normal activation; it must never overlap connection pools.
 */
public interface LocalMasterInstallationClient {
    /** Availability hint only. ACTIVE is not installation authority or native qualification. */
    public val health: StateFlow<LocalMasterEndpointHealth>

    public suspend fun initialize(
        definition: LocalBranchInstallationDefinition,
        password: CharArray,
    ): LocalBranchInstallationResult

    /**
     * Exact SAME full definition, no password. The fresh named-human/device/custody ceremony
     * remains mandatory. This initial-footprint lookup is NOT a later operational startup API.
     */
    public suspend fun lookupInstallation(definition: LocalBranchInstallationDefinition): LocalBranchInstallationResult
}
