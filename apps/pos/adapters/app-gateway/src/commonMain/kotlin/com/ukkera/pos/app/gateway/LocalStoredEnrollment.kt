package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalApprovalPolicy
import com.ukkera.pos.security.LocalEmployeeCreationConfigurationV1
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.NoMonetaryCeiling
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi

@Serializable
internal enum class StoredEnrollmentOutcomeV1 { PENDING, APPLIED, REJECTED }

/** Requested DATA only, encrypted with the client journal. No permit, password or verifier. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal data class StoredClientEnrollmentV1(
    val version: Int = 1,
    val requestId: DomainId,
    val scope: BranchScope,
    val actorId: DomainId,
    val login: String,
    val grants: List<StoredRequestedGrantV1>,
    val maximumConcurrentSessions: Int,
    val freeFailures: Int,
    val initialDelayMillis: Long,
    val maximumDelayMillis: Long,
    val attemptTimeoutMillis: Long,
    val approvalLifetimeMillis: Long?,
    val namedSelfApprovalActions: Set<String>,
    val accessLifetimeMillis: Long,
    val workLifetimeMillis: Long,
    val admittedDeviceIds: Set<DomainId>,
    val credentialWasProvided: Boolean,
    val outcome: StoredEnrollmentOutcomeV1 = StoredEnrollmentOutcomeV1.PENDING,
    val eventId: DomainId? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val employeeCreationConfigurationV1: ByteArray? = null,
) {
    fun definition(): LocalAccountProvisioning = LocalAccountProvisioning(actorId, LocalLoginIdentifier(login),
        ActivatedLocalPolicy(scope, actorId, Revision(0), grants.map { it.grant(scope) },
            LocalSessionPolicy(maximumConcurrentSessions),
            ThrottlePolicy(freeFailures, initialDelayMillis, maximumDelayMillis, attemptTimeoutMillis),
            approvalLifetimeMillis?.let { LocalApprovalPolicy(it, namedSelfApprovalActions.map(SecurityAction::valueOf).toSet()) },
            employeeCreationConfigurationV1?.let(LocalEmployeeCreationConfigurationV1::decode)),
        LocalTokenPolicy(accessLifetimeMillis, workLifetimeMillis), admittedDeviceIds)

    fun validate(owner: LocalClientDeviceIdentity) {
        require(version in 1..2 && (version == 2) == (employeeCreationConfigurationV1 != null))
        require(scope == owner.scope && owner.deviceId in admittedDeviceIds)
        require(credentialWasProvided && grants.size <= 1024)
        require((outcome == StoredEnrollmentOutcomeV1.APPLIED) == (eventId != null))
        require(approvalLifetimeMillis != null || namedSelfApprovalActions.isEmpty())
        definition() // reconstruct every bounded typed field, never merely accept stored JSON
        require(journalJson.encodeToString(serializer(), this).encodeToByteArray().size <= 512 * 1024)
    }
    override fun toString(): String = "StoredClientEnrollmentV1([REDACTED])"

    companion object {
        fun capture(requestId: DomainId, definition: LocalAccountProvisioning): StoredClientEnrollmentV1 {
            val policy = definition.policy
            val throttle = policy.throttlePolicy
            return StoredClientEnrollmentV1(version = if (policy.employeeCreationConfiguration == null) 1 else 2,
                requestId = requestId, scope = policy.scope, actorId = definition.actorId,
                login = definition.login.value, grants = policy.grants.map { StoredRequestedGrantV1.capture(it) },
                maximumConcurrentSessions = policy.sessionPolicy.maximumConcurrentSessions,
                freeFailures = throttle.freeFailures, initialDelayMillis = throttle.initialDelayMillis,
                maximumDelayMillis = throttle.maximumDelayMillis, attemptTimeoutMillis = throttle.attemptTimeoutMillis,
                approvalLifetimeMillis = policy.approvalPolicy?.validForMillis,
                namedSelfApprovalActions = policy.approvalPolicy?.namedSelfApprovalActions.orEmpty().map { it.name }.toSet(),
                accessLifetimeMillis = definition.tokenPolicy.accessLifetimeMillis,
                workLifetimeMillis = definition.tokenPolicy.workLifetimeMillis,
                admittedDeviceIds = definition.admittedDeviceIds, credentialWasProvided = true,
                employeeCreationConfigurationV1 = policy.employeeCreationConfiguration?.copyWireBytes())
        }
    }
}

@Serializable
internal data class StoredRequestedLimitsV1(val amount: Money?, val percentageBasisPoints: Int?, val noCeilingCurrency: Currency?) {
    fun limits(): PermissionLimits = PermissionLimits(amount, percentageBasisPoints, noCeilingCurrency?.let(::NoMonetaryCeiling))
    companion object {
        fun capture(value: PermissionLimits): StoredRequestedLimitsV1 =
            StoredRequestedLimitsV1(value.maximumAmount, value.maximumPercentageBasisPoints, value.noMonetaryCeiling?.currency)
    }
}

@Serializable
internal data class StoredRequestedGrantV1(
    val action: String, val verb: String, val companyScoped: Boolean, val effect: String,
    val limits: StoredRequestedLimitsV1, val ownerAccess: String, val namedSubjectId: DomainId?,
    val targetIds: Set<DomainId>?, val delegationCeiling: StoredRequestedLimitsV1?,
) {
    fun grant(scope: BranchScope): PermissionGrant = PermissionGrant(
        Permission(SecurityAction.valueOf(action), PermissionVerb.valueOf(verb)), if (companyScoped) scope.company() else scope,
        GrantEffect.valueOf(effect), limits.limits(), OwnerAccess.valueOf(ownerAccess), namedSubjectId, targetIds,
        delegationCeiling?.limits())
    companion object {
        fun capture(grant: PermissionGrant): StoredRequestedGrantV1 = StoredRequestedGrantV1(
            grant.permission.action.name, grant.permission.verb.name, grant.scope !is BranchScope, grant.effect.name,
            StoredRequestedLimitsV1.capture(grant.limits), grant.ownerAccess.name, grant.namedSubjectId, grant.targetIds,
            grant.delegationCeiling?.let { StoredRequestedLimitsV1.capture(it) })
    }
}
