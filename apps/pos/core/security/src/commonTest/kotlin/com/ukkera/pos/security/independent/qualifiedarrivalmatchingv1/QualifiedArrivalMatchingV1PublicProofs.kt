package com.ukkera.pos.security.independent.qualifiedarrivalmatchingv1

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.assertIs

/** Counts fixture provisioning only; there is no business/storage/RPC adapter in this harness. */
internal class QamProofCalls {
    var authentications = 0
    var evaluations = 0
    var issues = 0
    var consumptions = 0
    fun snapshot(): QamProofCallSnapshot = QamProofCallSnapshot(authentications, evaluations, issues, consumptions)
}

internal data class QamProofCallSnapshot(val authentications: Int, val evaluations: Int, val issues: Int, val consumptions: Int)

internal data class QamProvisionedProof(
    val authorization: AuthorizedAction,
    val requesterPrincipal: LocalEmployeePrincipal,
    val requesterAuthorization: AuthorizedAction,
    val requesterRevision: Revision,
    val currentApprover: CurrentApproverState?,
    val selfPolicy: SelfApprovalPolicy,
    val issued: ActionApprovalRecord?,
    val consumed: ActionApprovalRecord?,
    val callsRequirement: AuthorizationRequirement,
    val issuedAt: MonotonicMoment,
    val consumedAt: MonotonicMoment,
    val validForMillis: Long,
    val counts: QamProofCalls,
)

/** Public factory/evaluator/reducer path around explicit synthetic facts, without native history. */
internal object QamProofs {
    private val issuedAt = MonotonicMoment(QamInputs.boot, 0)
    private val consumedAt = MonotonicMoment(QamInputs.boot, 1)
    private const val validityMillis = 1_000L

    private suspend fun principal(actorId: DomainId, scope: BranchScope,
        purpose: AuthenticationPurpose, counts: QamProofCalls): LocalEmployeePrincipal {
        val ordinal = when (actorId) {
            QamInputs.requester -> 10
            QamInputs.approver -> 11
            QamInputs.otherRequester -> 12
            else -> error("No frozen actor binding")
        }
        val purposeOffset = if (purpose == AuthenticationPurpose.PRIVILEGED_APPROVAL) 100 else 0
        val connection = QamInputs.id(2_000 + purposeOffset + ordinal)
        val identity = VerifiedLocalIdentity(actorId, scope, QamInputs.device,
            QamInputs.id(1_000 + purposeOffset + ordinal), SessionGeneration(1), Revision(1), purpose)
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? {
                counts.authentications++
                return identity.takeIf { credentials.connectionId == connection }
            }
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val credential = OpaqueCredential(ByteArray(16) { it.toByte() })
        return try {
            checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(LocalGatewayCredentials(connection, credential)))
        } finally {
            credential.destroy()
        }
    }

    /** These grants/status bits are supplied facts, not installed activated policy or admission. */
    private fun snapshot(principal: LocalEmployeePrincipal, requirement: AuthorizationRequirement,
        policyRevision: Revision, accountEnabled: Boolean, sessionActive: Boolean,
        deviceAdmitted: Boolean, permissionPresent: Boolean): AuthorizationSnapshot {
        val limits = when (val use = requirement.limitUse) {
            LimitUse.NotApplicable -> PermissionLimits()
            is LimitUse.Monetary -> PermissionLimits(noMonetaryCeiling = NoMonetaryCeiling(use.amount.currency))
            is LimitUse.Proportional -> PermissionLimits(maximumPercentageBasisPoints = 10_000,
                noMonetaryCeiling = NoMonetaryCeiling(use.amount.currency))
        }
        val grants = if (permissionPresent) listOf(PermissionGrant(requirement.permission, principal.scope,
            GrantEffect.ALLOW, limits, OwnerAccess.ANY_OWNER, principal.actorId)) else emptyList()
        return AuthorizationSnapshot(principal.subjectId, principal.kind, principal.scope,
            principal.authenticationId, principal.generation, principal.deviceId, principal.admissionRevision,
            policyRevision, accountEnabled, sessionActive, deviceAdmitted, principal.authenticationPurpose, grants)
    }

    fun evaluateCurrent(principal: LocalEmployeePrincipal, requirement: AuthorizationRequirement,
        policyRevision: Revision, accountEnabled: Boolean = true, sessionActive: Boolean = true,
        deviceAdmitted: Boolean = true, permissionPresent: Boolean = true,
        counts: QamProofCalls = QamProofCalls()): AuthorizationDecision {
        counts.evaluations++
        return AuthorizationEvaluator.evaluate(principal,
            snapshot(principal, requirement, policyRevision, accountEnabled, sessionActive, deviceAdmitted, permissionPresent),
            requirement)
    }

    suspend fun publicAuthorization(requirement: AuthorizationRequirement,
        actorId: DomainId = QamInputs.requester, policyRevision: Revision = QamInputs.requesterPolicyRevision,
        counts: QamProofCalls = QamProofCalls()): Pair<LocalEmployeePrincipal, AuthorizedAction> {
        val purpose = if (requirement.permission.verb == PermissionVerb.APPROVE)
            AuthenticationPurpose.PRIVILEGED_APPROVAL else AuthenticationPurpose.INTERACTIVE
        val principal = principal(actorId, requirement.scope as BranchScope, purpose, counts)
        return principal to assertIs<AuthorizationDecision.Allowed>(
            evaluateCurrent(principal, requirement, policyRevision, counts = counts)).authorization
    }

    suspend fun provision(route: QamRoute, independentlyMaterializedRequirement: AuthorizationRequirement,
        requesterId: DomainId = QamInputs.requester,
        requesterRevision: Revision = QamInputs.requesterPolicyRevision,
        approverId: DomainId = if (route == QamRoute.NAMED_SELF) requesterId else QamInputs.approver,
        approverRevision: Revision = if (approverId == requesterId) requesterRevision else QamInputs.approverPolicyRevision,
        approvalId: DomainId = if (approverId == requesterId) QamInputs.selfApprovalId else QamInputs.separateApprovalId):
        QamProvisionedProof {
        val counts = QamProofCalls()
        val requirement = independentlyMaterializedRequirement
        val selfPolicy = SelfApprovalPolicy(Revision(1),
            if (approverId == requesterId && route != QamRoute.DIRECT)
                setOf(NamedSelfApproval(requirement.scope as BranchScope, requesterId, requirement.permission.action))
            else emptySet())
        if (route == QamRoute.DIRECT) {
            val (principal, authorization) = publicAuthorization(requirement, requesterId, requesterRevision, counts)
            return QamProvisionedProof(authorization, principal, authorization, requesterRevision, null,
                selfPolicy, null, null, requirement, issuedAt, consumedAt, validityMillis, counts)
        }
        val requestRequirement = requirement.copy(permission = requirement.permission.copy(verb = PermissionVerb.REQUEST))
        val approveRequirement = requirement.copy(permission = requirement.permission.copy(verb = PermissionVerb.APPROVE))
        val (requesterPrincipal, issueRequest) = publicAuthorization(requestRequirement, requesterId, requesterRevision, counts)
        val (_, approve) = publicAuthorization(approveRequirement, approverId, approverRevision, counts)
        counts.issues++
        val issued = assertIs<ApprovalIssueDecision.Issued>(ActionApprovals.issue(approvalId, requirement,
            issueRequest, approve, selfPolicy, issuedAt, validityMillis)).record
        val freshRequest = assertIs<AuthorizationDecision.Allowed>(evaluateCurrent(requesterPrincipal,
            requestRequirement, requesterRevision, counts = counts)).authorization
        val currentApprover = CurrentApproverState(requirement.scope as BranchScope, approverId, true, approverRevision)
        counts.consumptions++
        val consumed = assertIs<ApprovalConsumeDecision.Consumed>(ActionApprovals.consume(
            issued, requirement, freshRequest, currentApprover, selfPolicy, consumedAt))
        return QamProvisionedProof(consumed.authorization, requesterPrincipal, freshRequest, requesterRevision,
            currentApprover, selfPolicy, issued, consumed.record, requirement, issuedAt, consumedAt, validityMillis, counts)
    }
}
