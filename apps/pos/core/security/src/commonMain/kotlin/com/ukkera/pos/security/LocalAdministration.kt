package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

@Serializable
public enum class LocalAdministrationAction { DISABLE_ACCOUNT, REVOKE_SESSION, RESET_CREDENTIAL, REVOKE_DEVICE }

/**
 * One immutable, caller-retained administrative intent. Scope and operator come from the
 * authenticated connection, not this DTO. A password is deliberately NOT part of this record.
 * RESET_CREDENTIAL establishes a secret once; an already submitted reset is lookup-only and
 * cannot be resubmitted with another password under the same identity.
 */
@Serializable
public data class LocalAdministrationCommand(
    public val requestId: DomainId,
    public val action: LocalAdministrationAction,
    public val targetId: DomainId,
    public val expectedRevision: Revision,
    public val sessionId: DomainId? = null,
    public val expectedCredentialRevision: Revision? = null,
) {
    init {
        require((action == LocalAdministrationAction.REVOKE_SESSION) == (sessionId != null))
        require((action == LocalAdministrationAction.RESET_CREDENTIAL) == (expectedCredentialRevision != null))
    }
    override fun toString(): String = "LocalAdministrationCommand([REDACTED])"
}

public enum class LocalAdministrationDirectory { ACCOUNTS, DEVICES }

/** Opaque snapshot binding, including the original reader/session/policy, not authority. */
public data class LocalAdministrationCursor(public val afterId: DomainId, public val snapshot: PayloadDigest)

public data class LocalAdministrationQuery(
    public val directory: LocalAdministrationDirectory,
    public val targetId: DomainId? = null,
    public val cursor: LocalAdministrationCursor? = null,
    public val pageSize: Int = 16,
) {
    init { require(pageSize in 1..32 && (targetId == null || cursor == null)) }
}

/** Deliberately excludes passwords, verifier/salt/pepper, tokens and throttle internals. */
public data class LocalAccountSummary(
    public val actorId: DomainId,
    public val login: LocalLoginIdentifier,
    public val enabled: Boolean,
    public val credentialPresent: Boolean,
    public val accountRevision: Revision,
    public val credentialRevision: Revision,
    public val policyRevision: Revision,
    public val configurationRevision: Revision,
    /** Retained ACTIVE state, not a claim that a token or old service lifetime is still valid. */
    public val activeSessionCount: Int,
) {
    override fun toString(): String = "LocalAccountSummary([REDACTED])"
}

public class LocalAccountAdministrationDetail(
    public val account: LocalAccountSummary,
    public val policy: ActivatedLocalPolicy,
    public val tokenPolicy: LocalTokenPolicy,
    sessions: List<LocalSessionRecord>,
) {
    /** Current bounded account history only. Older archived sessions are not silently listed. */
    public val retainedSessions: List<LocalSessionRecord> = sessions.toList()
    override fun toString(): String = "LocalAccountAdministrationDetail([REDACTED])"
}

public class LocalAdministrationView(
    public val scope: BranchScope,
    public val directory: LocalAdministrationDirectory,
    public val snapshot: PayloadDigest,
    accounts: List<LocalAccountSummary>,
    devices: List<LocalDeviceAdmission>,
    public val accountDetail: LocalAccountAdministrationDetail?,
    public val next: LocalAdministrationCursor?,
) {
    public val accounts: List<LocalAccountSummary> = accounts.toList()
    public val devices: List<LocalDeviceAdmission> = devices.toList()
    override fun toString(): String = "LocalAdministrationView([REDACTED])"
}

public sealed interface LocalAdministrationReadResult {
    public class Available(public val view: LocalAdministrationView) : LocalAdministrationReadResult
    public data object Denied : LocalAdministrationReadResult
    public data object StaleSnapshot : LocalAdministrationReadResult
    public data object Unavailable : LocalAdministrationReadResult
    public data object StorageUncertain : LocalAdministrationReadResult
    public data object Busy : LocalAdministrationReadResult
}

/** Original protected audit identity, not today's target state or permission to repeat an effect. */
@Serializable
public data class LocalAdministrationReceipt(
    public val requestId: DomainId,
    public val action: LocalAdministrationAction,
    public val targetId: DomainId,
    public val originalOperatorId: DomainId,
    public val resultingAccountRevision: Revision?,
    public val requestDigest: PayloadDigest,
) {
    override fun toString(): String = "LocalAdministrationReceipt([REDACTED])"
}

public sealed interface LocalAdministrationStatus {
    public class Applied(public val receipt: LocalAdministrationReceipt) : LocalAdministrationStatus
    /** An immutable no-effect tombstone fences this original identity; never undoes an applied action. */
    public class Cancelled(public val receipt: LocalAdministrationReceipt) : LocalAdministrationStatus
    /** No receipt observed at this writer. NOT safe absence, cancellation, or a new-ID permit. */
    public data object NotObserved : LocalAdministrationStatus
    public data object IdentityConflict : LocalAdministrationStatus
    public data object Denied : LocalAdministrationStatus
    public data object Unavailable : LocalAdministrationStatus
    public data object StorageUncertain : LocalAdministrationStatus
    public data object Busy : LocalAdministrationStatus
}

/**
 * Protected local administration, separate from company/provisioning ceremonies. Every read
 * needs current ADMINISTER for the selected account/device management area; mutations and
 * receipt lookups bind the exact target too. No new implicit read grant is introduced.
 * Same original actor may recover after re-login; another actor is not relabelled as it.
 * Input credentials/passwords remain caller-owned. Missing runtime/custody never yields a pass.
 */
public interface LocalAdministrationService {
    public suspend fun readAdministration(operator: LocalGatewayCredentials, query: LocalAdministrationQuery): LocalAdministrationReadResult
    public suspend fun administer(operator: LocalGatewayCredentials, command: LocalAdministrationCommand,
        newPassword: CharArray? = null): LocalCredentialAdministrationResult
    public suspend fun lookupAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus
    /** Races on the SAME original identity. Applied wins or cancellation fences later execution. */
    public suspend fun cancelAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus
}

/** Canonical metadata commitment. It is not a password verifier or a reusable authorization. */
public fun localAdministrationRequestDigest(scope: BranchScope, operatorId: DomainId,
    command: LocalAdministrationCommand): PayloadDigest {
    // Preserve the existing device-revocation audit domain and byte framing.
    val fields = if (command.action == LocalAdministrationAction.REVOKE_DEVICE) listOf(
        "ukkera.revoke-local-device/v1", "${scope.organizationId.value}/${scope.companyId.value}/${scope.branchId.value}",
        operatorId.value, command.requestId.value, command.targetId.value, command.expectedRevision.value.toString(),
    ) else listOf("ukkera.local-administration-intent/v1", scope.organizationId.value, scope.companyId.value,
        scope.branchId.value, operatorId.value, command.requestId.value, command.action.name, command.targetId.value,
        command.expectedRevision.value.toString(), command.sessionId?.value.orEmpty(),
        command.expectedCredentialRevision?.value?.toString().orEmpty())
    val encoded = fields.map { it.encodeToByteArray(throwOnInvalidSequence = true) }
    val bytes = ByteArray(encoded.sumOf { it.size + 4 })
    return try {
        var offset = 0
        for (field in encoded) {
            for (shift in listOf(24, 16, 8, 0)) bytes[offset++] = (field.size ushr shift).toByte()
            field.copyInto(bytes, offset); offset += field.size
        }
        sha256(bytes)
    } finally { bytes.fill(0); encoded.forEach { it.fill(0) } }
}
