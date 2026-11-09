package com.ukkera.pos.security.independent.approvalprovenancev1

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.assertIs

/** Canonical U-layer bindings for the unchanged approval-provenance-v1 business aliases. */
internal object ApprovalProvenanceV1Inputs {
    fun id(ordinal: Int): DomainId = DomainId("30000000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    private fun operation(ordinal: Int): OperationId = OperationId(id(ordinal).value)

    val organization = id(1)
    val company = id(2)
    val branch = id(3)
    val otherCompany = id(4)
    val otherBranch = id(5)
    val otherCompanyBranch = id(6)
    val scope = BranchScope(organization, company, branch)
    val otherBranchScope = BranchScope(organization, company, otherBranch)
    val otherCompanyScope = BranchScope(organization, otherCompany, otherCompanyBranch)

    val requester = UnitAccount(10, "R", id(10), scope, "Synthetic User")
    val manager = UnitAccount(11, "M", id(11), scope, "Synthetic User")
    val anotherApprover = UnitAccount(12, "N", id(12), scope, "Synthetic N")
    val directActor = UnitAccount(13, "D", id(13), scope, "Synthetic D")
    val soleOperator = UnitAccount(14, "S", id(14), scope, "Synthetic S")
    val otherBranchRequester = UnitAccount(15, "RB", id(15), otherBranchScope, "Synthetic RB")
    val otherCompanyRequester = UnitAccount(16, "RC", id(16), otherCompanyScope, "Synthetic RC")
    private val accounts = listOf(requester, manager, anotherApprover, directActor, soleOperator,
        otherBranchRequester, otherCompanyRequester)

    val device = id(20)
    val otherDevice = id(21)
    val boot = id(22)
    val receipt = id(40)
    val secondReceipt = id(41)
    val directReceipt = id(42)
    val selfReceipt = id(43)
    val line = id(44)
    val secondLine = id(45)
    val approval = id(60)
    val secondApproval = id(61)
    val selfApproval = id(62)
    val operation = operation(100)
    val secondOperation = operation(101)
    val thirdOperation = operation(102)
    val directOperation = operation(103)
    val selfOperation = operation(104)
    val policy = Revision(10)
    val nextPolicy = Revision(11)

    // A goods-receipt behavior alias mapped to a public purchasing permission only.
    // This does not construct or implement a goods-receipt business operation.
    val action = SecurityAction.PURCHASING_APPROVE
    val otherAction = SecurityAction.SUPPLIER_CASH_SETTLE
    const val reason = "synthetic accepted supplier delivery"
    const val changedReason = "synthetic changed supplier delivery"
    val amount = Money(Currency.EGP, 20_000)
    val changedAmount = Money(Currency.EGP, 20_100)

    // U-only mapping: wall 00:00/00:05/00:10:01 -> same-boot 0/300000/601000.
    // Chosen issue origin/lifetime are not a production policy or platform clock.
    val issuedAt = MonotonicMoment(boot, 0)
    val now = MonotonicMoment(boot, 300_000)
    val expiredNow = MonotonicMoment(boot, 601_000)
    const val validForMillis = 600_000L

    val selfPolicy = SelfApprovalPolicy(policy, setOf(NamedSelfApproval(scope, soleOperator.id, action)))
    val baseline = BoundReceipt(operation, receipt, Revision(7), line, 2, amount, reason)
    val second = baseline.copy(operationId = secondOperation, documentId = secondReceipt)
    val direct = baseline.copy(operationId = directOperation, documentId = directReceipt)
    val self = baseline.copy(operationId = selfOperation, documentId = selfReceipt)

    /** Deterministic U semantic bytes only, expressly not the production request serialization. */
    fun requirement(input: BoundReceipt, scope: BranchScope = this.scope,
        action: SecurityAction = this.action): AuthorizationRequirement {
        val bytes = ("approval-provenance-v1-unit\n" +
            "receipt=${input.documentId.value}\nversion=${input.documentRevision.value}\n" +
            "line=${input.lineId.value}\nquantity=${input.quantity}\n" +
            "currency=${input.amount.currency.name}\namountMinor=${input.amount.minor}\nreason=${input.reason}").encodeToByteArray()
        val digest = try { sha256(bytes) } finally { bytes.fill(0) }
        return AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), scope,
            ActionBinding(input.documentId, input.documentRevision, digest, input.operationId, "goods_receipt.accepted"),
            LimitUse.Monetary(input.amount), OwnerConstraint.Unrestricted)
    }

    fun forVerb(requirement: AuthorizationRequirement, verb: PermissionVerb): AuthorizationRequirement =
        requirement.copy(permission = requirement.permission.copy(verb = verb))

    /** Public factory around explicitly controlled identity inputs; no real authentication claim. */
    suspend fun principal(account: UnitAccount, purpose: AuthenticationPurpose): LocalEmployeePrincipal {
        val purposeOffset = if (purpose == AuthenticationPurpose.PRIVILEGED_APPROVAL) 100 else 0
        val sessionId = id(1000 + purposeOffset + account.ordinal)
        val connectionId = id(2000 + purposeOffset + account.ordinal)
        val identity = VerifiedLocalIdentity(account.id, account.scope, device, sessionId,
            SessionGeneration(1), Revision(1), purpose)
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? =
                identity.takeIf { credentials.connectionId == connectionId }
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val bearer = OpaqueCredential(ByteArray(16) { it.toByte() })
        return try { checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(LocalGatewayCredentials(connectionId, bearer))) }
        finally { bearer.destroy() }
    }

    fun snapshot(principal: LocalEmployeePrincipal, policyRevision: Revision = policy,
        accountEnabled: Boolean = true, sessionActive: Boolean = true,
        approvalPermission: Boolean = true): AuthorizationSnapshot {
        val account = accounts.single { it.id == principal.subjectId }
        val verbs = when (account) {
            requester, otherBranchRequester, otherCompanyRequester -> setOf(PermissionVerb.REQUEST)
            manager, soleOperator -> setOf(PermissionVerb.REQUEST, PermissionVerb.APPROVE)
            anotherApprover -> setOf(PermissionVerb.APPROVE)
            directActor -> setOf(PermissionVerb.PERFORM)
            else -> error("Unbound frozen account")
        }.filter { approvalPermission || it != PermissionVerb.APPROVE }
        val grants = verbs.map { verb -> PermissionGrant(Permission(action, verb), account.scope,
            GrantEffect.ALLOW, PermissionLimits(noMonetaryCeiling = NoMonetaryCeiling(Currency.EGP))) }
        return AuthorizationSnapshot(principal.subjectId, principal.kind, principal.scope,
            principal.authenticationId, principal.generation, principal.deviceId, principal.admissionRevision,
            policyRevision, accountEnabled, sessionActive, true, principal.authenticationPurpose, grants)
    }

    suspend fun evaluate(account: UnitAccount, requirement: AuthorizationRequirement,
        verb: PermissionVerb, policyRevision: Revision = policy, accountEnabled: Boolean = true,
        sessionActive: Boolean = true, approvalPermission: Boolean = true): AuthorizationDecision {
        val principal = principal(account, if (verb == PermissionVerb.APPROVE)
            AuthenticationPurpose.PRIVILEGED_APPROVAL else AuthenticationPurpose.INTERACTIVE)
        return AuthorizationEvaluator.evaluate(principal,
            snapshot(principal, policyRevision, accountEnabled, sessionActive, approvalPermission), forVerb(requirement, verb))
    }

    suspend fun permit(account: UnitAccount, requirement: AuthorizationRequirement,
        verb: PermissionVerb, policyRevision: Revision = policy): AuthorizedAction =
        assertIs<AuthorizationDecision.Allowed>(evaluate(account, requirement, verb, policyRevision)).authorization

    suspend fun issue(approvalId: DomainId = approval, input: BoundReceipt = baseline,
        requester: UnitAccount = this.requester, approver: UnitAccount = manager,
        selfPolicy: SelfApprovalPolicy = this.selfPolicy): ActionApprovalRecord {
        val requirement = requirement(input)
        return assertIs<ApprovalIssueDecision.Issued>(ActionApprovals.issue(approvalId, requirement,
            permit(requester, requirement, PermissionVerb.REQUEST),
            permit(approver, requirement, PermissionVerb.APPROVE), selfPolicy, issuedAt, validForMillis)).record
    }

    fun currentApprover(account: UnitAccount, policyRevision: Revision = policy,
        enabled: Boolean = true): CurrentApproverState = CurrentApproverState(account.scope, account.id, enabled, policyRevision)
}

internal data class UnitAccount(val ordinal: Int, val label: String, val id: DomainId,
    val scope: BranchScope, val displayLabel: String)

internal data class BoundReceipt(val operationId: OperationId, val documentId: DomainId,
    val documentRevision: Revision, val lineId: DomainId, val quantity: Int, val amount: Money, val reason: String)

internal data class UnitObservationCounts(val finalProofs: Int, val acceptedConsumerCalls: Int, val successfulConsumptions: Int) {
    companion object { val ZERO = UnitObservationCounts(0, 0, 0) }
}

internal data class UnitConsumerObservation(val requester: DomainId, val approver: DomainId?,
    val operation: OperationId?, val approval: DomainId?, val policy: Revision, val approverPolicy: Revision?)

/** Passive observations of actual public outputs; no business service, persistence or recovery store. */
internal class ApprovalUnitObservations {
    val proofs = mutableListOf<AuthorizedAction>()
    val consumerCalls = mutableListOf<UnitConsumerObservation>()
    private var consumptions = 0
    val counts: UnitObservationCounts get() = UnitObservationCounts(proofs.size, consumerCalls.size, consumptions)

    fun observe(decision: AuthorizationDecision) {
        if (decision is AuthorizationDecision.Allowed) receive(decision.authorization)
    }
    fun observe(decision: ApprovalConsumeDecision) {
        if (decision is ApprovalConsumeDecision.Consumed) {
            consumptions++
            receive(decision.authorization)
        }
    }
    private fun receive(proof: AuthorizedAction) {
        check(proof.requirement.permission.verb == PermissionVerb.PERFORM)
        proofs += proof
        consumerCalls += UnitConsumerObservation(proof.actorId, proof.approverId,
            proof.requirement.binding?.operationId, proof.approvalId, proof.policyRevision, proof.approverPolicyRevision)
    }
}
