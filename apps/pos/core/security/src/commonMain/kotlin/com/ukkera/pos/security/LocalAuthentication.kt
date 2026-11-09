package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Exact, case-sensitive local identifier. No trimming, Unicode normalization or cloud aliasing. */
public data class LocalLoginIdentifier(public val value: String) {
    init {
        require(value.length in 1..128 && value.first().isWhitespace().not() && value.last().isWhitespace().not())
        require(value.none { it.code < 0x20 || it.code in 0x7f..0x9f })
        require(value.encodeToByteArray(throwOnInvalidSequence = true).size <= 256)
    }

    override fun toString(): String = "LocalLoginIdentifier([REDACTED])"
}

/** Explicit activated LOCAL durations. Neither expiry nor renewal is a cloud connectivity lease. */
public data class LocalTokenPolicy(public val accessLifetimeMillis: Long, public val workLifetimeMillis: Long) {
    init {
        require(accessLifetimeMillis > 0 && workLifetimeMillis >= accessLifetimeMillis)
    }
    /** Pure duration-overflow validity for supplied monotonic DATA. This does not qualify
     * an observation, service lifetime, bearer, employee or permission to start work. */
    public fun canStartAt(moment: MonotonicMoment): Boolean =
        workLifetimeMillis <= Long.MAX_VALUE - moment.elapsedMillis
}

/** A CURRENT qualified service/monotonic observation, not wall time or a caller assertion. */
public data class LocalAuthenticationObservation(public val issuerContextId: DomainId, public val moment: MonotonicMoment)

public fun interface LocalAuthenticationEnvironment {
    /** Bounded synchronous operation. Null/continuity loss permanently retires the current source. */
    public fun observe(): LocalAuthenticationObservation?
}

/**
 * Trusted physical-transport adapter output, never a request DTO. All descriptors originate in
 * the SAME authenticated physical key. [isCurrent] must test that exact captured lifetime and
 * remain synchronous/non-reentrant. This is an observation, not a network disclosure fence.
 */
public class LocalAuthenticatedPeer(
    public val connectionId: DomainId,
    public val scope: BranchScope,
    public val deviceId: DomainId,
    public val publicKeyIdentifier: String?,
    public val publicFingerprint: String?,
    private val currentLifetime: () -> Boolean,
) {
    init {
        require(publicKeyIdentifier != null || publicFingerprint != null)
        for (value in listOf(publicKeyIdentifier, publicFingerprint)) {
            require(value == null || (value.length in 1..ProtectedLocalStorageFormat.MAX_ADMISSION_DESCRIPTOR_BYTES &&
                value.all { it.code in 0x21..0x7e }))
        }
    }

    public fun isCurrent(): Boolean = currentLifetime()
    override fun toString(): String = "LocalAuthenticatedPeer([REDACTED])"
}

public fun interface LocalAuthenticatedPeerSource {
    public suspend fun capture(connectionId: DomainId): LocalAuthenticatedPeer?
}

/** CSPRNG port, owned by the supervised process. Implementations MUST completely fill the buffer. */
public fun interface LocalAuthenticationEntropy {
    public fun fill(bytes: ByteArray)
}

/** Display/capability hints only. Every protected request still requires current master policy. */
public class LocalSessionView(
    public val actorId: DomainId,
    public val scope: BranchScope,
    public val deviceId: DomainId,
    public val sessionId: DomainId,
    public val generation: SessionGeneration,
    public val login: LocalLoginIdentifier,
    public val policy: ActivatedLocalPolicy,
    public val accessExpiresAt: MonotonicMoment,
    public val workExpiresAt: MonotonicMoment,
) {
    override fun toString(): String = "LocalSessionView([REDACTED])"
}

/** Owned secrets, never serializable/loggable; client storage must be separately protected. */
public class LocalSessionTokens(
    public val view: LocalSessionView,
    public val access: OpaqueCredential,
    public val refresh: OpaqueCredential,
) {
    public fun destroy(): Unit {
        access.destroy()
        refresh.destroy()
    }
    override fun toString(): String = "LocalSessionTokens([REDACTED])"
}

public enum class LocalAuthenticationDenial {
    AUTHENTICATION_REFUSED,
    THROTTLED,
    EXPLICIT_DEVICE_MOVE_REQUIRED,
    SESSION_LIMIT,
    /** Bounded work admission/hasher saturation, NOT an incorrect password or damaged store. */
    SERVICE_BUSY,
}

public sealed interface LocalAuthenticationResult {
    public class Authenticated(public val tokens: LocalSessionTokens) : LocalAuthenticationResult {
        override fun toString(): String = "LocalAuthenticationResult.Authenticated([REDACTED])"
    }
    public data class Denied(public val reason: LocalAuthenticationDenial) : LocalAuthenticationResult
    /** Ordinary provider/current-peer refusal; no authority is published and no store poison is inferred. */
    public data object Unavailable : LocalAuthenticationResult
    /** Writer/COMMIT/cleanup is uncertain. Retire the supervised store; do not retry a stale refresh. */
    public data object StorageUncertain : LocalAuthenticationResult
}

public sealed interface LocalSessionViewResult {
    public class Current(public val view: LocalSessionView) : LocalSessionViewResult
    public data object Denied : LocalSessionViewResult
    public data object Unavailable : LocalSessionViewResult
    public data object StorageUncertain : LocalSessionViewResult
    public data object Busy : LocalSessionViewResult
}

public sealed interface LocalSignOutResult {
    /** Only after the local revocation transaction and its outer loan have returned normally. */
    public data object Revoked : LocalSignOutResult
    public data object Denied : LocalSignOutResult
    /** Client must lock immediately but preserve a pending revocation intent, not claim success. */
    public data object Unavailable : LocalSignOutResult
    public data object StorageUncertain : LocalSignOutResult
    public data object Busy : LocalSignOutResult
}

/**
 * Branch-fixed service. Connection IDs come from authenticated transport, never request bodies.
 * Password arrays remain caller-owned; implementations snapshot after bounded work admission.
 * Access is exactly ULA1/52 bytes; refresh is separately domain-separated ULR1/52 bytes.
 * A refresh rotates BOTH tokens/session identity in one local transaction and retains the
 * original work deadline. Lost replies fall back to explicit local re-login, never cloud login.
 * Lock is a synchronous CLIENT presentation operation, not an implicit master/logout claim.
 */
public interface LocalAuthenticationService {
    public suspend fun signIn(
        connectionId: DomainId,
        login: LocalLoginIdentifier,
        password: CharArray,
        explicitDeviceMove: Boolean = false,
    ): LocalAuthenticationResult

    public suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult
    public suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult
    /** Accepts the current access OR refresh secret, including expired secrets, for revocation only. */
    public suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult
}

/**
 * Approved local account definition. Grants/configuration here are REQUESTED data, not authority.
 * A separately injected, current, explicitly delegated provisioning ceremony must approve the
 * complete digest before any insert. There is no default account, password or administrator.
 */
public class LocalAccountProvisioning(
    public val actorId: DomainId,
    public val login: LocalLoginIdentifier,
    public val policy: ActivatedLocalPolicy,
    public val tokenPolicy: LocalTokenPolicy,
    admittedDeviceIds: Set<DomainId>,
) {
    public val admittedDeviceIds: Set<DomainId> = admittedDeviceIds.toSet()
    init {
        require(policy.actorId == actorId && policy.revision == Revision(0))
        require(this.admittedDeviceIds.size in 1..256)
    }
    override fun toString(): String = "LocalAccountProvisioning([REDACTED])"
}

/** Original credentials/history remain; current exact revisions prevent a stale enable request. */
public data class LocalAccountReactivation(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val expectedAccountRevision: Revision,
    public val expectedCredentialRevision: Revision,
    public val expectedPolicyRevision: Revision,
    public val expectedConfigurationRevision: Revision,
)

public enum class LocalProvisioningAction { PROVISION_ACCOUNT, ADMIT_DEVICE, ACTIVATE_POLICY, REACTIVATE_ACCOUNT }

/** Binds the ENTIRE normalized requested definition; this digest is computed by the service. */
public data class LocalProvisioningRequest(
    public val scope: BranchScope,
    public val requestId: DomainId,
    public val action: LocalProvisioningAction,
    public val targetId: DomainId,
    public val contentDigest: PayloadDigest,
)

/**
 * Trusted ceremony output, not cryptographic proof by type. Only the trusted composition may
 * install an authority. The same request ID is consumed immutably in the protected local DB.
 * No synthetic authority may masquerade as centrally/company approved production provisioning.
 */
public class LocalProvisioningPermit private constructor(
    public val request: LocalProvisioningRequest,
    private val currentCeremony: () -> Boolean,
    public val nativeEmployee: NativeLocalEmployeeProvisioningPermit?,
    public val nativePolicy: NativeLocalEmployeePolicyActivationPermit?,
    public val nativeDeviceAdmission: NativeLocalDeviceAdmissionPermit?,
) {
    public constructor(request: LocalProvisioningRequest, currentCeremony: () -> Boolean) :
        this(request, currentCeremony, null, null, null)
    internal constructor(native: NativeLocalEmployeeProvisioningPermit) :
        this(native.request, native::isCurrent, native, null, null)
    internal constructor(native: NativeLocalEmployeePolicyActivationPermit) :
        this(native.request, native::isCurrent, null, native, null)
    internal constructor(native: NativeLocalDeviceAdmissionPermit) :
        this(native.request, native::isCurrent, null, null, native)
    public fun isCurrent(): Boolean = currentCeremony()
    /** Negative-only own-commit disclosure, never a new effect grant after consumption. */
    public fun observedOwnCommitCurrent(): Boolean = nativeEmployee?.observedOwnCommitCurrent()
        ?: nativePolicy?.observedOwnCommitCurrent() ?: nativeDeviceAdmission?.observedOwnCommitCurrent() ?: isCurrent()
    override fun toString(): String = "LocalProvisioningPermit([REDACTED])"
}

public fun interface LocalProvisioningAuthority {
    /**
     * Read-only approval lookup; durable single-use consumption belongs to the local event transaction.
     * Device admission/re-admission must have authenticated pairing proof for the exact requested
     * descriptors and next revision. The trusted pairing adapter retires replaced physical peer
     * lifetimes; neither a caller UUID nor this permit's type establishes that external evidence.
     */
    public suspend fun authorize(request: LocalProvisioningRequest): LocalProvisioningPermit?
}

public object UnavailableLocalProvisioningAuthority : LocalProvisioningAuthority {
    override suspend fun authorize(request: LocalProvisioningRequest): LocalProvisioningPermit? = null
}

public sealed interface LocalCredentialAdministrationResult {
    public data class Applied(public val eventId: DomainId) : LocalCredentialAdministrationResult
    public data object Denied : LocalCredentialAdministrationResult
    public data object Unavailable : LocalCredentialAdministrationResult
    public data object StorageUncertain : LocalCredentialAdministrationResult
    public data object Busy : LocalCredentialAdministrationResult
}

public sealed interface LocalProvisioningStatus {
    public data class Applied(public val eventId: DomainId) : LocalProvisioningStatus
    /** Authenticated absence at the observed writer cut, not proof no still-running request can commit. */
    public data object NotRecorded : LocalProvisioningStatus
    public data object Denied : LocalProvisioningStatus
    public data object Unavailable : LocalProvisioningStatus
    public data object StorageUncertain : LocalProvisioningStatus
    public data object Busy : LocalProvisioningStatus
}

public interface LocalCredentialAdministration {
    /** A null password deliberately provisions an account with NO usable credential. */
    public suspend fun provisionAccount(
        requestId: DomainId,
        definition: LocalAccountProvisioning,
        password: CharArray?,
    ): LocalCredentialAdministrationResult

    /**
     * Explicit authorized pairing ceremony: revision zero for a new ID, or exact next revision for
     * a retained REVOKED device. Re-admission preserves existing throttle buckets/history and
     * revokes that device's old sessions. It is not an active-device key/grant upsert.
     */
    public suspend fun admitDevice(requestId: DomainId, admission: LocalDeviceAdmission): LocalCredentialAdministrationResult

    /**
     * Current DEVICE_ADMINISTRATION/ADMINISTER on this exact target/revision/request identity.
     * Revokes admission and all active sessions on the device atomically, retaining history,
     * descriptors and throttle state. This is not physical-peer closure or master replacement.
     * Persist the request ID before submission. Exact same-actor retries return the original
     * receipt, even after a later re-admission; they never revoke that newer admission again.
     */
    public suspend fun revokeDevice(
        operator: LocalGatewayCredentials,
        requestId: DomainId,
        deviceId: DomainId,
        expectedRevision: Revision,
    ): LocalCredentialAdministrationResult

    /** Original request identity/definition only, no retained password; current ceremony authorizes the lookup. */
    public suspend fun lookupAccountProvisioning(requestId: DomainId, definition: LocalAccountProvisioning,
        credentialWasProvided: Boolean): LocalProvisioningStatus
    public suspend fun lookupDeviceAdmission(requestId: DomainId, admission: LocalDeviceAdmission): LocalProvisioningStatus

    /** Current trusted company/delegated ceremony approves every grant/ceiling/self-policy field. */
    public suspend fun activatePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult
    public suspend fun lookupPolicyActivation(requestId: DomainId, nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus

    /** Explicit reactivation, not directory synchronization or an inverse of a stale disable. */
    public suspend fun reactivateAccount(requestId: DomainId, target: LocalAccountReactivation): LocalCredentialAdministrationResult
    public suspend fun lookupAccountReactivation(requestId: DomainId, target: LocalAccountReactivation): LocalProvisioningStatus

    /** Requires CURRENT LOCAL_ACCOUNT_ADMINISTRATION/ADMINISTER bound to this target+revision. */
    public suspend fun resetCredential(
        operator: LocalGatewayCredentials,
        actorId: DomainId,
        expectedRevision: Revision,
        newPassword: CharArray,
    ): LocalCredentialAdministrationResult

    public suspend fun revokeSession(
        operator: LocalGatewayCredentials,
        actorId: DomainId,
        sessionId: DomainId,
        expectedRevision: Revision,
    ): LocalCredentialAdministrationResult

    public suspend fun disableAccount(
        operator: LocalGatewayCredentials,
        actorId: DomainId,
        expectedRevision: Revision,
    ): LocalCredentialAdministrationResult
}
