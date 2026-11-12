@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable

@Serializable
public enum class LocalAdministrationAction {
    DISABLE_ACCOUNT, REVOKE_SESSION, RESET_CREDENTIAL, REVOKE_DEVICE, REACTIVATE_ACCOUNT, ACTIVATE_ACCOUNT;

    /** Password-bearing originals become lookup/cancel-only after their first dispatch. */
    public val requiresNewPassword: Boolean get() = this == RESET_CREDENTIAL || this == ACTIVATE_ACCOUNT
}

/** Exact current dependencies for a newer explicit resolution; never a grant or a session. */
@Serializable
public data class LocalAdministrationReactivationRevisions(
    public val credential: Revision,
    public val policy: Revision,
    public val configuration: Revision,
)

/** Current policy/configuration of a disabled credential-free activation target. */
@Serializable
public data class LocalAdministrationActivationRevisions(
    public val policy: Revision,
    public val configuration: Revision,
)

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
    // Preserve the canonical bytes of every pre-existing command, including codecs that
    // encode defaults/nulls. Only the new action carries this additional dependency set.
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    public val reactivationRevisions: LocalAdministrationReactivationRevisions? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    public val activationRevisions: LocalAdministrationActivationRevisions? = null,
) {
    init {
        require((action == LocalAdministrationAction.REACTIVATE_ACCOUNT) == (reactivationRevisions != null))
        require((action == LocalAdministrationAction.REVOKE_SESSION) == (sessionId != null))
        require(action.requiresNewPassword == (expectedCredentialRevision != null))
        require((action == LocalAdministrationAction.ACTIVATE_ACCOUNT) == (activationRevisions != null))
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
public interface LocalAdministrationService : LocalEmployeeCreationServiceV1 {
    /** Current target-scoped input DATA only. Returns no password, permit, safe absence or
     * first-dispatch handoff. Missing providers remain unavailable. */
    override public suspend fun employeeCreationContext(operator: LocalGatewayCredentials,
        target: LocalEmployeeCreationTargetV1): LocalEmployeeCreationContextResultV1 = LocalEmployeeCreationContextResultV1.Unavailable
    /** Assemble the complete original with actual production codecs and retained admissions
     * under a confirmed no-write protected read. This does not prepare or submit it. */
    override public suspend fun captureEmployeeCreationDefinition(operator: LocalGatewayCredentials,
        draft: LocalEmployeeCreationDraftV1): LocalEmployeeCreationDefinitionResultV1 = LocalEmployeeCreationDefinitionResultV1.Unavailable
    /** First submission of the complete retained original. Caller owns credentials/password;
     * an asynchronous adapter must copy them before handing them to its work queue. Providers
     * must reauthorize the original creator/target at the writer and confirm native settlement.
     * Reopening or looking up the original cannot reconstruct permission to submit it again.
     * Unimplemented or incomplete native/history composition stays unavailable. */
    override public suspend fun executeEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1, newPassword: CharArray?): LocalEmployeeCreationExecutionV1 =
        LocalEmployeeCreationExecutionV1.Unavailable
    /** Competes with creation on the same immutable original key. A fresh tombstone requires
     * the real protected writer and native settlement. A prior Applied result wins unchanged;
     * no new password, account replacement, login or implicit retry is introduced. */
    override public suspend fun cancelEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationCancellationV1 =
        LocalEmployeeCreationCancellationV1.Unavailable
    /** Protected, no-effect input review only. A checked result cannot dispatch or recover a
     * creation. Missing providers remain unavailable; native creation is a separate operation. */
    override public suspend fun reviewEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationReviewV1 = LocalEmployeeCreationReviewV1.Unavailable
    /** Read the exact retained creation original under the original creator's current
     * target-bound ADMINISTER grant, including after re-login. A missing provider refuses.
     * Current account contents never substitute for an original result; this does not create
     * an account or resolve native close/commit uncertainty. */
    override public suspend fun lookupEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationStatusV1 = LocalEmployeeCreationStatusV1.Unavailable
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
    val fields = if (command.action == LocalAdministrationAction.ACTIVATE_ACCOUNT) {
        val expected = checkNotNull(command.activationRevisions)
        listOf("ukkera.local-administration-activation-intent/v1", scope.organizationId.value,
            scope.companyId.value, scope.branchId.value, operatorId.value, command.requestId.value,
            command.action.name, command.targetId.value, command.expectedRevision.value.toString(),
            checkNotNull(command.expectedCredentialRevision).value.toString(), expected.policy.value.toString(),
            expected.configuration.value.toString())
    } else if (command.action == LocalAdministrationAction.REACTIVATE_ACCOUNT) {
        val expected = checkNotNull(command.reactivationRevisions)
        listOf("ukkera.local-administration-reactivation-intent/v1", scope.organizationId.value,
            scope.companyId.value, scope.branchId.value, operatorId.value, command.requestId.value,
            command.action.name, command.targetId.value, command.expectedRevision.value.toString(),
            expected.credential.value.toString(), expected.policy.value.toString(), expected.configuration.value.toString())
    } else if (command.action == LocalAdministrationAction.REVOKE_DEVICE) listOf(
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
