package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

public enum class PrincipalKind {
    LOCAL_EMPLOYEE,
    CENTRAL_HUMAN,
    ADMITTED_DEVICE,
    BRANCH_SERVICE,
}

public enum class AuthenticationPurpose {
    INTERACTIVE,
    PRIVILEGED_APPROVAL,
    PREPARED_LOCAL_RECOVERY,
    SERVICE,
}

public enum class BranchServicePurpose {
    MASTER,
    CENTRAL_INGESTION,
    KITCHEN_DISPLAY,
    RECOVERY_COPY_PROVIDER,
}

/**
 * An opaque transport secret. Never serializable, printable, an actor ID, or a business payload.
 * The verifier receives a disposable copy; destroying this value does not erase persisted evidence.
 */
public class OpaqueCredential(bytes: ByteArray) {
    init {
        require(bytes.size in 16..8192) { "Credential length outside supported bounds" }
    }

    private val value: ByteArray = bytes.copyOf()
    private var destroyed: Boolean = false

    public fun <T> useBytes(block: (ByteArray) -> T): T {
        check(!destroyed) { "Credential is unavailable" }
        val copy = value.copyOf()
        return try {
            block(copy)
        } finally {
            copy.fill(0)
        }
    }

    public fun destroy(): Unit {
        value.fill(0)
        destroyed = true
    }

    override fun toString(): String = "OpaqueCredential([REDACTED])"
}

/** The connection ID must come from the authenticated transport, not from the command body. */
public class LocalGatewayCredentials(
    public val connectionId: DomainId,
    public val bearer: OpaqueCredential,
)

public class CentralGatewayCredentials(public val bearer: OpaqueCredential)

public class DeviceGatewayCredentials(public val connectionId: DomainId)

public class ServiceGatewayCredentials(
    public val connectionId: DomainId,
    public val credential: OpaqueCredential,
)

/**
 * Trusted adapter results, not RPC DTOs. Implementations validate the transport peer and the
 * protected, current identity registry. These values must never be decoded from a request body.
 */
public data class VerifiedLocalIdentity(
    public val actorId: DomainId,
    public val scope: BranchScope,
    public val deviceId: DomainId,
    public val sessionId: DomainId,
    public val sessionGeneration: SessionGeneration,
    public val admissionRevision: Revision,
    public val purpose: AuthenticationPurpose,
)

public data class VerifiedCentralIdentity(
    public val humanId: DomainId,
    public val scope: CompanyScope,
    public val sessionId: DomainId,
    public val sessionGeneration: SessionGeneration,
)

public data class VerifiedDeviceIdentity(
    public val deviceId: DomainId,
    public val scope: BranchScope,
    public val admissionRevision: Revision,
)

public data class VerifiedServiceIdentity(
    public val serviceId: DomainId,
    public val deviceId: DomainId,
    public val scope: BranchScope,
    public val authenticationId: DomainId,
    public val generation: SessionGeneration,
    public val admissionRevision: Revision,
    public val purpose: BranchServicePurpose,
)

/** Injection into the process composition root is trusted code, not a callable business endpoint. */
public interface PrincipalVerifier {
    public suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity?
    public suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity?
    public suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity?
    public suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity?
}

/** No production in-memory or default-identity fallback. */
public object DenyAllPrincipalVerifier : PrincipalVerifier {
    override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? = null
    override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
    override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
    override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
}

/**
 * Non-serializable authority capability. No public constructor or copy method exists. The factory
 * verifies credentials; the authorization policy additionally rechecks current state in the writer
 * transaction. Possessing this object alone does not preserve revoked grants.
 */
public sealed class AuthenticatedPrincipal private constructor() {
    public abstract val subjectId: DomainId
    public abstract val scope: TenantScope
    public abstract val kind: PrincipalKind
    public abstract val authenticationId: DomainId
    public abstract val generation: SessionGeneration
    public abstract val deviceId: DomainId?
    public abstract val admissionRevision: Revision?

    public class LocalEmployeePrincipal private constructor(
        override val subjectId: DomainId,
        override val scope: BranchScope,
        override val deviceId: DomainId,
        override val authenticationId: DomainId,
        override val generation: SessionGeneration,
        override val admissionRevision: Revision,
        public val authenticationPurpose: AuthenticationPurpose,
    ) : AuthenticatedPrincipal() {
        override val kind: PrincipalKind = PrincipalKind.LOCAL_EMPLOYEE
        public val actorId: DomainId get() = subjectId
        public val sessionId: DomainId get() = authenticationId
        public val sessionGeneration: SessionGeneration get() = generation

        internal companion object {
            internal fun verified(value: VerifiedLocalIdentity): LocalEmployeePrincipal =
                LocalEmployeePrincipal(
                    value.actorId, value.scope, value.deviceId, value.sessionId,
                    value.sessionGeneration, value.admissionRevision, value.purpose,
                )
        }
    }

    public class CentralHumanPrincipal private constructor(
        override val subjectId: DomainId,
        override val scope: CompanyScope,
        override val authenticationId: DomainId,
        override val generation: SessionGeneration,
    ) : AuthenticatedPrincipal() {
        override val kind: PrincipalKind = PrincipalKind.CENTRAL_HUMAN
        override val deviceId: DomainId? = null
        override val admissionRevision: Revision? = null

        internal companion object {
            internal fun verified(value: VerifiedCentralIdentity): CentralHumanPrincipal =
                CentralHumanPrincipal(value.humanId, value.scope, value.sessionId, value.sessionGeneration)
        }
    }

    public class AdmittedDevicePrincipal private constructor(
        override val subjectId: DomainId,
        override val scope: BranchScope,
        override val admissionRevision: Revision,
    ) : AuthenticatedPrincipal() {
        override val kind: PrincipalKind = PrincipalKind.ADMITTED_DEVICE
        override val authenticationId: DomainId get() = subjectId
        override val generation: SessionGeneration = SessionGeneration(0)
        override val deviceId: DomainId get() = subjectId

        internal companion object {
            internal fun verified(value: VerifiedDeviceIdentity): AdmittedDevicePrincipal =
                AdmittedDevicePrincipal(value.deviceId, value.scope, value.admissionRevision)
        }
    }

    public class BranchServicePrincipal private constructor(
        override val subjectId: DomainId,
        override val scope: BranchScope,
        override val deviceId: DomainId,
        override val authenticationId: DomainId,
        override val generation: SessionGeneration,
        override val admissionRevision: Revision,
        public val servicePurpose: BranchServicePurpose,
    ) : AuthenticatedPrincipal() {
        override val kind: PrincipalKind = PrincipalKind.BRANCH_SERVICE

        internal companion object {
            internal fun verified(value: VerifiedServiceIdentity): BranchServicePrincipal =
                BranchServicePrincipal(
                    value.serviceId, value.scope, value.deviceId, value.authenticationId,
                    value.generation, value.admissionRevision, value.purpose,
                )
        }
    }

    final override fun toString(): String = "AuthenticatedPrincipal(kind=$kind)"
}

public typealias LocalEmployeePrincipal = AuthenticatedPrincipal.LocalEmployeePrincipal
public typealias CentralHumanPrincipal = AuthenticatedPrincipal.CentralHumanPrincipal
public typealias AdmittedDevicePrincipal = AuthenticatedPrincipal.AdmittedDevicePrincipal
public typealias BranchServicePrincipal = AuthenticatedPrincipal.BranchServicePrincipal

/** Not request-deserializable, and deliberately no factory accepting unverified actor/grant DTOs. */
public class TrustedPrincipalFactory(private val verifier: PrincipalVerifier) {
    public suspend fun authenticateLocal(credentials: LocalGatewayCredentials): LocalEmployeePrincipal? {
        val identity = verifier.verifyLocal(credentials) ?: return null
        if (identity.purpose == AuthenticationPurpose.SERVICE) return null
        return LocalEmployeePrincipal.verified(identity)
    }

    public suspend fun authenticateCentral(credentials: CentralGatewayCredentials): CentralHumanPrincipal? =
        verifier.verifyCentral(credentials)?.let { CentralHumanPrincipal.verified(it) }

    public suspend fun authenticateDevice(credentials: DeviceGatewayCredentials): AdmittedDevicePrincipal? =
        verifier.verifyDevice(credentials)?.let { AdmittedDevicePrincipal.verified(it) }

    public suspend fun authenticateService(credentials: ServiceGatewayCredentials): BranchServicePrincipal? =
        verifier.verifyService(credentials)?.let { BranchServicePrincipal.verified(it) }
}
