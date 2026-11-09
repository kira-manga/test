package com.ukkera.pos.database

import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalAdministrationService
import com.ukkera.pos.security.LocalAuthenticationService
import com.ukkera.pos.security.LocalCredentialAdministrationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed client-facing requests to the appointed branch master. This is an interface boundary,
 * NOT another authority, authentication engine or permission to finalize on a disconnected client.
 * The real supervised [LocalMasterEndpoint] implements the same contract for protected same-device
 * use. Remote implementations must preserve the original operation identity, typed refusals and
 * uncertainty; a transport error cannot become a NotRecorded result or permission for a new ID.
 *
 * A remote adapter must independently authenticate/bind the actual client device and connection,
 * enforce current employee authorization for every inbound request and outbound disclosure, and
 * bind a reply to its immutable original presentation/physical lifetime. Neither caller-supplied
 * connection IDs, this interface, a health flag nor a StateFlow provides those security guarantees.
 * Existing sensitive transport admission gates still apply. This interface exposes no writer,
 * database, arbitrary callback, maintenance, shutdown or process-retirement controls.
 *
 * Secret ownership follows [LocalAuthenticationService]: implementations never destroy caller-owned
 * input credentials, retain an independently owned copy only as long as needed, and destroy owned
 * secrets on every abandoned delivery. Cancellation stops the caller's wait, not an accepted
 * financial operation. Recover that original operation using [lookup] after fresh authorization.
 */
public interface LocalMasterClient : LocalAuthenticationService {
    /** Availability observation only. ACTIVE is neither authentication nor runtime qualification. */
    public val health: StateFlow<LocalMasterEndpointHealth>

    public suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply
    public suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply
    public suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply
}

/**
 * Separately bound, protected management port of the SAME master/peer lifetime. An interface
 * or cast is not a grant: the service reauthenticates every directory/receipt/mutation request.
 * No company grant publication, additional-device pairing or recovery activation is implied.
 */
public interface LocalMasterAdministrationClient : LocalAdministrationService

/**
 * Separately installed account-enrollment capability. These definitions are requested data; the
 * master still requires the full current, independently trusted provisioning ceremony. Merely
 * possessing a client transport or implementing this interface cannot enroll an employee.
 * No device admission, policy activation, credential reset or writer appointment is implied.
 *
 * The caller retains ownership of [CharArray] inputs. Implementations make their own bounded
 * copy before asynchronous dispatch, never clear caller-owned input, and clear that copy on
 * completion or abandoned delivery. Cancellation/lost delivery does not prove non-enrollment:
 * retain the original request identity/definition and use [lookupAccountProvisioning].
 * A narrowed interface is not a cast-resistant security boundary when the same object also
 * implements other ports. Trusted master-side ceremony/authorization checks are mandatory.
 */
public interface LocalMasterEnrollmentClient {
    public suspend fun provisionAccount(
        requestId: DomainId,
        definition: LocalAccountProvisioning,
        password: CharArray?,
    ): LocalCredentialAdministrationResult

    /** Lookup binds the SAME full original definition; no new ID or credential reset on retry. */
    public suspend fun lookupAccountProvisioning(
        requestId: DomainId,
        definition: LocalAccountProvisioning,
        credentialWasProvided: Boolean,
    ): LocalProvisioningStatus
}

/**
 * Separately installed action-approval ceremony; never a manager sign-in or an unrestricted
 * command endpoint. Original body/actor/operation, confirmation digest and approval ID must be
 * durably staged before issuance. Recovery returns historical status, not current permission to
 * reuse an approval after a session/authority change. No remote serialization authority is granted
 * to the caller and existing restricted approval projection constructors stay restricted.
 *
 * Implementations never destroy caller-owned credentials or passwords. They own bounded copies
 * for asynchronous dispatch and must clear them, plus any abandoned returned secret material,
 * on every completion/abandonment path. A lost response is uncertainty: use the same approval
 * identity and [lookupActionApproval], not another issuance. An interface cast or client-side
 * method visibility never substitutes for the master's current authorization/ceremony checks.
 */
public interface LocalMasterApprovalClient {
    /** [retiredApprovalId] selects the latest original for explicit replacement, not a grant. */
    public suspend fun previewActionApproval(
        requestor: LocalGatewayCredentials,
        request: MutationRequest,
        retiredApprovalId: DomainId? = null,
    ): LocalActionApprovalPreviewResult

    public suspend fun issueActionApproval(
        approvalId: DomainId,
        requestor: LocalGatewayCredentials,
        request: MutationRequest,
        confirmationDigest: PayloadDigest,
        approverLogin: LocalLoginIdentifier,
        approverPassword: CharArray,
        retiredApprovalId: DomainId? = null,
    ): LocalActionApprovalIssueResult

    public suspend fun lookupActionApproval(
        approvalId: DomainId,
        requestor: LocalGatewayCredentials,
        confirmationDigest: PayloadDigest,
    ): LocalActionApprovalStatus

    public suspend fun retireActionApproval(
        approvalId: DomainId,
        requestor: LocalGatewayCredentials,
        request: MutationRequest,
        confirmationDigest: PayloadDigest,
    ): LocalActionApprovalRetireResult

    /**
     * Separately binds one durably staged invocation. Only the actual endpoint can report
     * [LocalActionApprovalRetireResult.NotAdmitted] at a proved pre-queue boundary.
     * The compatibility path preserves generic uncertainty; it never upgrades a denial,
     * busy response, lookup absence or transport failure into no-admission evidence.
     * The direct caller dispatches once and binds the response to the same live attempt
     * instance as well as its saved coordinates. Coordinates alone are not a replay fence.
     * A remote adapter cannot translate a rejected duplicate into this completion while an
     * earlier copy might execute; it requires an actual invocation/late-execution fence.
     * Until that is provided, preserve generic uncertainty rather than manufacture a
     * [LocalActionApprovalRetireResult.NotAdmitted] from a transport status or decoded tuple.
     */
    public suspend fun retireActionApprovalAttempt(
        attempt: LocalActionApprovalRetirementAttempt,
        requestor: LocalGatewayCredentials,
        request: MutationRequest,
    ): LocalActionApprovalRetireResult {
        if (!attempt.matches(request)) return LocalActionApprovalRetireResult.Denied
        return when (val result = retireActionApproval(attempt.approvalId, requestor, request, attempt.confirmationDigest)) {
            // The older method has no live invocation binding. Do not let an implementation
            // retrofit a coordinate-only negative into the accepting compatibility path.
            is LocalActionApprovalRetireResult.NotAdmitted -> LocalActionApprovalRetireResult.Unavailable
            else -> result
        }
    }
}
