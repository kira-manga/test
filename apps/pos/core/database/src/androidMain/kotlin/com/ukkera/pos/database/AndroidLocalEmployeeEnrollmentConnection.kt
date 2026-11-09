package com.ukkera.pos.database

import android.app.Application
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId

/** Final registered original-service connection after the named head's genuine full-definition
 * approval. No public constructor, supplied endpoint/peer/currentness callback, or default grants.
 * It is original-ID-only; missing delivery/outcome is lookup, never another password dispatch. */
public class AndroidLocalEmployeeEnrollmentConnection internal constructor(
    internal val service: AndroidSelfOwnedMasterService,
    internal val original: AndroidOriginalLocalClient,
    internal val bridge: RoomSelfOwnedEmployeeEnrollments,
    public val approval: LocalEmployeeEnrollmentAuthorityReceipt,
    internal val permitsProvisioning: Boolean,
) : LocalMasterEnrollmentClient {
    public val requested: LocalEmployeeEnrollmentDefinition = approval.requested
    public val originalRequestId: DomainId get() = requested.request.requestId
    public val originalActionId: DomainId get() = approval.originalActionId
    public val actorId: DomainId get() = requested.request.targetId
    /** Recovered late-phase handles are permanently read-only, even if their data is signed. */
    public val isLookupOnly: Boolean get() = !permitsProvisioning
    public fun isCurrent(): Boolean = service.ownsEmployeeEnrollment(this)
    public fun belongsTo(original: AndroidOriginalLocalClient): Boolean = this.original === original && isCurrent()
    public fun belongsTo(application: Application): Boolean = original.belongsTo(application) && isCurrent()

    /** Read-only exact native-retained requested data, not a login-to-permissions generator. */
    public suspend fun originalDefinition(requestId: DomainId, login: LocalLoginIdentifier): LocalAccountProvisioning? =
        service.originalEmployeeDefinition(this, requestId, login)

    override public suspend fun provisionAccount(requestId: DomainId, definition: LocalAccountProvisioning,
        password: CharArray?): LocalCredentialAdministrationResult =
        service.provisionOriginalEmployee(this, requestId, definition, password)

    override public suspend fun lookupAccountProvisioning(requestId: DomainId, definition: LocalAccountProvisioning,
        credentialWasProvided: Boolean): LocalProvisioningStatus =
        service.lookupOriginalEmployee(this, requestId, definition, credentialWasProvided)

    /** Separately joins actual consumed provisioning; this signs in no employee and owns no bearer. */
    public suspend fun acquireEmployeeClient(): LocalCompanyAuthorityResult<AndroidEmployeeClientCustody> =
        service.acquireEmployeeClient(this)
    override fun toString(): String = "AndroidLocalEmployeeEnrollmentConnection([REDACTED])"
}

/** Journal custody only, from the actual same service/peer/incarnation AND original native
 * consumed child/final observation. It never inherits the company head or founding session.
 * Reuse this cohost's already admitted device credential owner; acquire only an employee intent. */
public class AndroidEmployeeClientCustody internal constructor(
    internal val service: AndroidSelfOwnedMasterService,
    internal val enrollment: AndroidLocalEmployeeEnrollmentConnection,
    public val native: NativeEmployeeClientCustody,
) {
    /** Immutable genuine consumed/cold-admitted enrollment DATA only; no journal or permission grant. */
    public val physicalEnrollmentHistory: OriginalPhysicalEmployeeEnrollment =
        originalPhysicalEmployeeEnrollment(enrollment.original, native)
    public val actorId: DomainId get() = native.actorId
    public val deviceId: DomainId get() = native.deviceId
    public val scope: BranchScope get() = native.scope
    public val originalRequestId: DomainId get() = native.request.requestId
    public val originalActionId: DomainId get() = native.originalActionId
    public val material: SelfOwnedNativeMaterialView get() = enrollment.original.material
    public val serviceIncarnationId: DomainId get() = enrollment.original.serviceIncarnationId
    public fun isCurrent(): Boolean = service.ownsEmployeeClient(this)
    public fun belongsTo(application: Application): Boolean = enrollment.belongsTo(application) && isCurrent()
    public fun belongsTo(original: AndroidOriginalLocalClient): Boolean = enrollment.belongsTo(original) && isCurrent()
    public suspend fun acquireJournalAction(action: NativeEmployeeClientJournalAction): LocalCompanyAuthorityResult<NativeEmployeeJournalAllocation> =
        service.acquireEmployeeJournalAction(this, action)
    override fun toString(): String = "AndroidEmployeeClientCustody([REDACTED])"
}

/** Final same-original-service port over its privately registered genuine action connections.
 * Original request IDs select existing bound handles, never authorize an arbitrary endpoint,
 * borrow a dispatched permit, or create a definition/custody from scalar IDs. This keeps older
 * original-ID receipts available while a later deliberately approved action is offered. */
public class AndroidOriginalEmployeeEnrollmentClient internal constructor(
    internal val service: AndroidSelfOwnedMasterService,
    internal val original: AndroidOriginalLocalClient,
) : LocalMasterEnrollmentClient {
    public fun isCurrent(): Boolean = service.ownsEmployeeEnrollmentClient(this)
    public fun belongsTo(original: AndroidOriginalLocalClient): Boolean = this.original === original && isCurrent()
    public fun contains(connection: AndroidLocalEmployeeEnrollmentConnection): Boolean =
        connection.service === service && connection.original === original && connection.isCurrent() && isCurrent()

    override public suspend fun provisionAccount(requestId: DomainId, definition: LocalAccountProvisioning,
        password: CharArray?): LocalCredentialAdministrationResult {
        if (!isCurrent()) return LocalCredentialAdministrationResult.Unavailable
        val bound = service.originalEmployeeEnrollment(original, requestId) ?: return LocalCredentialAdministrationResult.Unavailable
        return bound.provisionAccount(requestId, definition, password)
    }
    override public suspend fun lookupAccountProvisioning(requestId: DomainId, definition: LocalAccountProvisioning,
        credentialWasProvided: Boolean): LocalProvisioningStatus {
        if (!isCurrent()) return LocalProvisioningStatus.Unavailable
        val bound = service.originalEmployeeEnrollment(original, requestId) ?: return LocalProvisioningStatus.Unavailable
        return bound.lookupAccountProvisioning(requestId, definition, credentialWasProvided)
    }
    override fun toString(): String = "AndroidOriginalEmployeeEnrollmentClient([REDACTED])"
}
