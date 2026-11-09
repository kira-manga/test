package com.ukkera.pos.inventory.store.independent.unpricedarrivaldirectv2

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.assertIs

/**
 * Public factory/evaluator around explicit synthetic facts, NOT a native Master adapter.
 * No production repository, current employee-policy read, session/device installation,
 * writer qualification or historical authority is supplied by this bounded provisioning.
 * AuthorizedAction is obtained only from AuthorizationEvaluator; it is never constructed.
 */
internal object Uad2PublicAuthorization {
    private val connectionCarrier = DomainId("90000000-0000-4000-8000-000000000041")

    suspend fun principal(): LocalEmployeePrincipal {
        val identity = VerifiedLocalIdentity(Uad2Inputs.id("REQUESTER-R"), Uad2Inputs.scope,
            Uad2Inputs.id("DEVICE-A"), Uad2Inputs.id("SESSION-R"), SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? =
                identity.takeIf { credentials.connectionId == connectionCarrier }
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val credential = OpaqueCredential(ByteArray(16) { it.toByte() })
        return try {
            checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(LocalGatewayCredentials(connectionCarrier, credential)))
        } finally { credential.destroy() }
    }

    fun evaluate(principal: LocalEmployeePrincipal, requirement: AuthorizationRequirement,
        enabled: Boolean = true, sessionActive: Boolean = true, deviceAdmitted: Boolean = true,
        permissionPresent: Boolean = true, explicitlyDenied: Boolean = false,
        policyRevision: Revision = Uad2Inputs.requesterPolicyRevision): AuthorizationDecision {
        val grant = PermissionGrant(requirement.permission, principal.scope, GrantEffect.ALLOW,
            ownerAccess = OwnerAccess.ANY_OWNER, namedSubjectId = principal.actorId)
        val grants = (if (permissionPresent) listOf(grant) else emptyList()) +
            (if (explicitlyDenied) listOf(PermissionGrant(requirement.permission, principal.scope, GrantEffect.DENY,
                ownerAccess = OwnerAccess.ANY_OWNER, namedSubjectId = principal.actorId)) else emptyList())
        val snapshot = AuthorizationSnapshot(principal.subjectId, principal.kind, principal.scope,
            principal.authenticationId, principal.generation, principal.deviceId, principal.admissionRevision,
            policyRevision, enabled, sessionActive, deviceAdmitted, principal.authenticationPurpose, grants)
        return AuthorizationEvaluator.evaluate(principal, snapshot, requirement)
    }

    fun proof(principal: LocalEmployeePrincipal, requirement: AuthorizationRequirement): AuthorizedAction =
        assertIs<AuthorizationDecision.Allowed>(evaluate(principal, requirement)).authorization
}
