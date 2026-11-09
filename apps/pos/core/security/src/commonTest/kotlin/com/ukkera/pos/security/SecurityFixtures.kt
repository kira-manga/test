package com.ukkera.pos.security

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.assertIs

internal fun id(value: Int): DomainId =
    DomainId("00000000-0000-0000-0000-" + value.toString().padStart(12, '0'))

internal fun op(value: Int): OperationId = OperationId(id(value).value)
internal fun money(value: Long): Money = Money(Currency.EGP, value)
internal val branch: BranchScope = BranchScope(id(1), id(2), id(3))
internal val boot: DomainId = id(900)
internal fun moment(elapsed: Long = 0, bootId: DomainId = boot): MonotonicMoment = MonotonicMoment(bootId, elapsed)

/** Synthetic fixture only; never registered in an application composition root. */
internal class FixtureVerifier(
    var local: VerifiedLocalIdentity? = null,
    var central: VerifiedCentralIdentity? = null,
    var device: VerifiedDeviceIdentity? = null,
    var service: VerifiedServiceIdentity? = null,
) : PrincipalVerifier {
    override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? = local
    override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = central
    override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = device
    override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = service
}

internal fun credential(): OpaqueCredential = OpaqueCredential(ByteArray(32) { (it + 1).toByte() })

internal suspend fun local(
    actorId: DomainId = id(4),
    purpose: AuthenticationPurpose = AuthenticationPurpose.INTERACTIVE,
    scope: BranchScope = branch,
    generation: SessionGeneration = SessionGeneration(1),
    sessionId: DomainId = id(7),
): LocalEmployeePrincipal = checkNotNull(
    TrustedPrincipalFactory(
        FixtureVerifier(local = VerifiedLocalIdentity(actorId, scope, id(6), sessionId, generation, Revision(1), purpose)),
    ).authenticateLocal(LocalGatewayCredentials(id(8), credential())),
)

internal fun binding(value: String = "synthetic-payload", operationId: OperationId? = op(10)): ActionBinding =
    ActionBinding(id(11), Revision(1), sha256(value.encodeToByteArray()), operationId, "synthetic.reason")

internal fun requirement(
    action: SecurityAction = SecurityAction.ORDER_EDIT,
    verb: PermissionVerb = PermissionVerb.PERFORM,
    scope: TenantScope = branch,
    ownerId: DomainId = id(4),
    limitUse: LimitUse = LimitUse.NotApplicable,
): AuthorizationRequirement = AuthorizationRequirement(
    Permission(action, verb), scope, binding(), limitUse,
    if (action.ownerPrivate) OwnerConstraint.OwnerPrivate(ownerId) else OwnerConstraint.Unrestricted,
)

internal fun grant(
    requirement: AuthorizationRequirement,
    effect: GrantEffect = GrantEffect.ALLOW,
    limits: PermissionLimits = PermissionLimits(),
    ownerAccess: OwnerAccess = OwnerAccess.OWN_ONLY,
    namedSubjectId: DomainId? = null,
    targets: Set<DomainId>? = null,
    delegationCeiling: PermissionLimits? = null,
): PermissionGrant = PermissionGrant(
    requirement.permission, requirement.scope, effect, limits, ownerAccess, namedSubjectId, targets, delegationCeiling,
)

internal fun snapshot(
    principal: AuthenticatedPrincipal,
    grants: List<PermissionGrant> = emptyList(),
    enabled: Boolean = true,
    active: Boolean = true,
    admitted: Boolean = true,
    generation: SessionGeneration = principal.generation,
    admissionRevision: Revision? = principal.admissionRevision,
    policyRevision: Revision = Revision(1),
    scope: TenantScope = principal.scope,
    subjectId: DomainId = principal.subjectId,
): AuthorizationSnapshot = AuthorizationSnapshot(
    subjectId = subjectId,
    kind = principal.kind,
    scope = scope,
    authenticationId = principal.authenticationId,
    generation = generation,
    deviceId = principal.deviceId,
    admissionRevision = admissionRevision,
    policyRevision = policyRevision,
    accountEnabled = enabled,
    sessionActive = active,
    deviceAdmitted = admitted,
    authenticationPurpose = when (principal) {
        is LocalEmployeePrincipal -> principal.authenticationPurpose
        is BranchServicePrincipal, is AdmittedDevicePrincipal -> AuthenticationPurpose.SERVICE
        is CentralHumanPrincipal -> AuthenticationPurpose.INTERACTIVE
    },
    grants = grants,
)

internal fun authorized(
    principal: AuthenticatedPrincipal,
    requirement: AuthorizationRequirement,
    grant: PermissionGrant = grant(
        requirement,
        limits = PermissionLimits(money(Long.MAX_VALUE), 10_000),
        ownerAccess = OwnerAccess.ANY_OWNER,
    ),
    policyRevision: Revision = Revision(1),
): AuthorizedAction = assertIs<AuthorizationDecision.Allowed>(
    AuthorizationEvaluator.evaluate(principal, snapshot(principal, listOf(grant), policyRevision = policyRevision), requirement),
).authorization
