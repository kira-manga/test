package com.ukkera.pos.master.host

import com.ukkera.pos.database.LocalMasterRetirement
import com.ukkera.pos.database.LocalMasterRetirementReason
import com.ukkera.pos.database.StagedLocalSecurityQuarantineRequest
import com.ukkera.pos.database.quarantineStagedLocalSecurity
import com.ukkera.pos.recovery.store.StagedQuarantineProcessProtocol
import com.ukkera.pos.recovery.store.StagedSecurityQuarantineReceipt
import com.ukkera.pos.recovery.store.StagedSecurityQuarantineRequest
import com.ukkera.pos.security.DeviceProtectedLocalStorage
import com.ukkera.pos.security.LocalAuthenticationEnvironment
import com.ukkera.pos.security.ProtectedStorageKeyReference
import java.util.ServiceLoader
import kotlinx.coroutines.runBlocking

/**
 * Installed ONLY in the supervisor's fixed, integrity-qualified child classpath. Supply exactly
 * one service implementation via META-INF/services; this module installs none. No request/argv/
 * environment value selects a provider class, key alias or arbitrary configuration file.
 *
 * The implementation independently authenticates this exact outstanding host/helper loan and
 * private stage, resolves allowed SOURCE recovery keys from protected supervisor configuration,
 * and establishes a fresh qualified child clock/service context. It must not generate replacement
 * keys, copy device identities, export keys over stdin/stdout or trust aliases found in SQLite.
 * Missing/locked keys or absent native containment remain unavailable, never demo success.
 */
public fun interface StagedQuarantineRuntimeProvider {
    /** Own/settle incomplete acquisition when failure prevents delivery; the child will halt. */
    public suspend fun acquire(request: StagedSecurityQuarantineRequest): StagedQuarantineRuntimeLease
}

public interface StagedQuarantineRuntimeLease {
    public val protection: DeviceProtectedLocalStorage
    public val storageKey: ProtectedStorageKeyReference
    public val environment: LocalAuthenticationEnvironment
    public val retirement: LocalMasterRetirement
    public val maximumProtectedRecords: Long

    /** Current exact request/loan, qualified key/service context; never opens another DB handle. */
    public suspend fun ensureAuthorized(request: StagedSecurityQuarantineRequest)

    /** Normal-path key/config loan close only, after actual core SQLite close. Never resumes host. */
    public suspend fun close()
}

/**
 * Actual one-shot child executable, not a test runner or accepting default. Stdout is reserved
 * for the bounded receipt. Failures/provider text are never printed. The child halts rather than
 * running arbitrary shutdown hooks against a possibly poisoned Room pool. Parent-side actual
 * exit/full-containment reaping is STILL mandatory; halt invocation is not that evidence.
 */
public fun main(args: Array<String>): Unit {
    var runtime: StagedQuarantineRuntimeLease? = null
    var exitCode = 1
    try {
        check(args.isEmpty())
        runBlocking {
            val providers = ServiceLoader.load(StagedQuarantineRuntimeProvider::class.java,
                StagedQuarantineRuntimeProvider::class.java.classLoader).iterator()
            check(providers.hasNext())
            val provider = providers.next()
            check(!providers.hasNext())
            val request = StagedQuarantineProcessProtocol.readRequest(System.`in`)
            val lease = provider.acquire(request)
            runtime = lease
            check(lease.maximumProtectedRecords in 1..10_000_000)
            lease.ensureAuthorized(request)
            val result = quarantineStagedLocalSecurity(StagedLocalSecurityQuarantineRequest(
                request.databasePath, request.scope, request.backupId, request.incidentId,
                request.sourceAuthority, request.targetDeviceId, request.approvalId),
                lease.protection, lease.storageKey, lease.environment, lease.retirement, lease.maximumProtectedRecords)
            // The core returned only after real normal close. A lost authorization/key-loan close
            // still withholds the receipt; no failure path calls Room.close or retries quarantine.
            lease.ensureAuthorized(request)
            lease.close()
            StagedQuarantineProcessProtocol.writeReceipt(System.out, StagedSecurityQuarantineReceipt(
                result.scope, result.backupId, result.incidentId, result.targetDeviceId,
                result.authenticatedRowCounts, result.revokedSessionCount))
        }
        exitCode = 0
    } catch (_: Throwable) {
        try { runtime?.retirement?.retire(LocalMasterRetirementReason.STORAGE_UNCERTAIN) }
        catch (_: Throwable) { /* External deadline and parent reaping remain mandatory. */ }
    } finally {
        Runtime.getRuntime().halt(exitCode)
    }
}
