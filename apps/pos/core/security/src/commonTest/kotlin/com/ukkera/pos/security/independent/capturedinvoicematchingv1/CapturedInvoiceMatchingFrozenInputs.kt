package com.ukkera.pos.security.independent.capturedinvoicematchingv1

import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthenticatedPrincipal
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CurrentAuthorizationProvider
import com.ukkera.pos.security.DenyAllPrincipalVerifier
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.NoMonetaryCeiling
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TenantScope
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.assertNotNull

/**
 * Numeric oracles: CCM v1.0.1 and its independently frozen binding v1.0.0.
 * Only SUPPORTING-L1 is wired here. Identities, revisions and digests below are controlled
 * policy-comparison inputs, not actual Master producers, activated grants or command bodies.
 * Execution status at source handoff: NOT RUN.
 */
internal object CapturedInvoiceMatchingFrozenInputs {
    fun id(index: Int): DomainId = DomainId("b1000000-0000-4000-8000-" + index.toString().padStart(12, '0'))

    val scope = BranchScope(id(1), id(2), id(3))
    val actorId = id(4)
    val otherActorId = id(5)
    val invoiceId = id(6)
    val otherInvoiceId = id(7)
    val deviceId = id(8)
    val permission = Permission(SecurityAction.PURCHASING_INVOICE_MATCH_RECOGNIZE, PermissionVerb.PERFORM)

    data class PartialCeiling(
        val fixture: String,
        val fullRawMinor: Long,
        val selectedMinor: Long,
    )

    val partialCeilings = listOf(
        PartialCeiling("F-QUALITY / CCM-03", 24_000L, 22_000L),
        PartialCeiling("F-CAPACITY.claim10 / CCM-06", 10_000L, 6_000L),
        PartialCeiling("F-ARRIVAL.first12 / CCM-02", 24_000L, 12_000L),
        PartialCeiling("F-PRICE.A_only / CCM-04", 21_000L, 10_000L),
    )

    fun money(minor: Long): Money = Money(Currency.EGP, minor)

    fun requirement(fullRawMinor: Long = 24_000L): AuthorizationRequirement = AuthorizationRequirement(
        permission = permission,
        scope = scope,
        binding = ActionBinding(
            documentId = invoiceId,
            revision = Revision(0),
            payloadDigest = sha256("CCM/F-FULL/retained-policy-comparison-body".encodeToByteArray()),
            operationId = OperationId(id(11).value),
            reasonCode = "CCM_EXACT_GOODS",
        ),
        limitUse = LimitUse.Monetary(money(fullRawMinor)),
    )

    fun grant(
        action: SecurityAction = permission.action,
        verb: PermissionVerb = PermissionVerb.PERFORM,
        limits: PermissionLimits = PermissionLimits(maximumAmount = money(24_000L)),
        effect: GrantEffect = GrantEffect.ALLOW,
        grantScope: TenantScope = scope,
        targetIds: Set<DomainId>? = null,
        namedSubjectId: DomainId? = null,
    ): PermissionGrant = PermissionGrant(
        Permission(action, verb), grantScope, effect, limits,
        namedSubjectId = namedSubjectId, targetIds = targetIds,
    )

    fun noCeiling(): PermissionLimits = PermissionLimits(noMonetaryCeiling = NoMonetaryCeiling(Currency.EGP))

    suspend fun principal(): LocalEmployeePrincipal {
        val identity = VerifiedLocalIdentity(
            actorId, scope, deviceId, id(9), SessionGeneration(1),
            Revision(0), AuthenticationPurpose.INTERACTIVE,
        )
        val verifier = object : PrincipalVerifier by DenyAllPrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity = identity
        }
        val bearer = OpaqueCredential(ByteArray(16) { 0x35 })
        return try {
            assertNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(
                LocalGatewayCredentials(id(10), bearer),
            ))
        } finally {
            bearer.destroy()
        }
    }

    fun snapshot(
        principal: AuthenticatedPrincipal,
        grants: List<PermissionGrant>,
        accountEnabled: Boolean = true,
        sessionActive: Boolean = true,
        deviceAdmitted: Boolean = true,
        generation: SessionGeneration = principal.generation,
        policyRevision: Revision = Revision(0),
    ): AuthorizationSnapshot = AuthorizationSnapshot(
        principal.subjectId, principal.kind, principal.scope, principal.authenticationId,
        generation, principal.deviceId, principal.admissionRevision, policyRevision,
        accountEnabled, sessionActive, deviceAdmitted,
        when (principal.kind) {
            PrincipalKind.LOCAL_EMPLOYEE -> (principal as LocalEmployeePrincipal).authenticationPurpose
            PrincipalKind.CENTRAL_HUMAN -> AuthenticationPurpose.INTERACTIVE
            PrincipalKind.ADMITTED_DEVICE, PrincipalKind.BRANCH_SERVICE -> AuthenticationPurpose.SERVICE
        },
        grants,
    )
}

/** Each call to the production policy observes this explicit current cut; no durable-store claim. */
internal class CapturedInvoiceMatchingCurrentCut(var value: AuthorizationSnapshot?) : CurrentAuthorizationProvider {
    var reads: Int = 0
        private set

    override suspend fun current(principal: AuthenticatedPrincipal): AuthorizationSnapshot? {
        reads += 1
        return value
    }
}
