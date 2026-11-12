package com.ukkera.pos.security

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/** Additive complete employee-policy ceremony. Existing provisioning/request formats stay unchanged. */
public object LocalEmployeePolicyActivationProfileV1 {
    public const val MAXIMUM_ACTIONS: Int = 4096
    public const val MAXIMUM_DEFINITION_BYTES: Int = 2 * 1024 * 1024
    internal const val MAXIMUM_STATE_BYTES: Int = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES
    internal const val MAXIMUM_PLAINTEXT_BYTES: Int = MAXIMUM_STATE_BYTES - 2048
    internal const val MAXIMUM_COMPLETION_BYTES: Int = 128 * 1024
    internal const val PURPOSE: String = "EXPLICIT_NAMED_COMPANY_HEAD_COMPLETE_LOCAL_EMPLOYEE_POLICY_ACTIVATION_P256_V1"
}

/** The original activate-policy V1 framing, including both big-endian length prefixes.
 * The canonical buffer transfers to this pure digest function; no authority is produced. */
public fun localPolicyActivationRequestV1(scope: BranchScope, requestId: DomainId, actorId: DomainId,
    canonicalPolicy: ByteArray): LocalProvisioningRequest {
    val fields = listOf("ukkera.activate-local-policy/v1\u0000".encodeToByteArray(), canonicalPolicy)
    var bytes: ByteArray? = null
    return try {
        val count = fields.sumOf { it.size.toLong() + 4L }
        require(count in 1L..(2L * 1024 * 1024))
        val encoded = ByteArray(count.toInt()); bytes = encoded
        var offset = 0
        for (field in fields) {
            encoded[offset++] = (field.size ushr 24).toByte(); encoded[offset++] = (field.size ushr 16).toByte()
            encoded[offset++] = (field.size ushr 8).toByte(); encoded[offset++] = field.size.toByte()
            field.copyInto(encoded, offset); offset += field.size
        }
        LocalProvisioningRequest(scope, requestId, LocalProvisioningAction.ACTIVATE_POLICY, actorId, sha256(encoded))
    } finally { bytes?.fill(0); fields.forEach { it.fill(0) } }
}

/** Complete immutable review DATA, not a head approval. The real Room bridge regenerates
 * the existing policy codec and compares it to these retained bytes before any effects. */
public class LocalEmployeePolicyActivationDefinition private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    private val value = LocalEmployeePolicyActivationDefinitionWireV1.decode(bytes)
    public val definitionDigest: PayloadDigest = sha256(bytes)
    public val request: LocalProvisioningRequest get() = value.request
    public val nextPolicy: ActivatedLocalPolicy get() = value.policy
    public val previousPolicyRevision: Revision get() = Revision(nextPolicy.revision.value - 1L)
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    public fun copyCanonicalPolicy(): ByteArray = value.canonicalPolicy.copyBytes()
    override fun toString(): String = "LocalEmployeePolicyActivationDefinition([REDACTED])"

    public companion object {
        public fun capture(requestId: DomainId, nextPolicy: ActivatedLocalPolicy, canonicalPolicy: ByteArray):
            LocalEmployeePolicyActivationDefinition {
            val request = localPolicyActivationRequestV1(nextPolicy.scope, requestId, nextPolicy.actorId, canonicalPolicy.copyOf())
            val fields = EmployeePolicyActivationFieldsV1(request, nextPolicy,
                SelfOwnedOctetsV1(canonicalPolicy, ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes))
            val bytes = LocalEmployeePolicyActivationDefinitionWireV1.encode(fields)
            return try { LocalEmployeePolicyActivationDefinition(bytes) } finally { bytes.fill(0) }
        }
        public fun decode(canonical: ByteArray): LocalEmployeePolicyActivationDefinition {
            require(canonical.size in 1..LocalEmployeePolicyActivationProfileV1.MAXIMUM_DEFINITION_BYTES)
            val result = LocalEmployeePolicyActivationDefinition(canonical)
            val encoded = LocalEmployeePolicyActivationDefinitionWireV1.encode(result.value)
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return result
        }
    }
}

internal data class EmployeePolicyActivationFieldsV1(val request: LocalProvisioningRequest,
    val policy: ActivatedLocalPolicy, val canonicalPolicy: SelfOwnedOctetsV1) {
    init {
        require(request.action == LocalProvisioningAction.ACTIVATE_POLICY && request.scope == policy.scope &&
            request.targetId == policy.actorId && policy.revision.value > 0L &&
            canonicalPolicy.size in 1..ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes)
        require(localPolicyActivationRequestV1(request.scope, request.requestId, request.targetId, canonicalPolicy.copyBytes()) == request)
    }
}

/** Separate semantic definition domain; no existing employee/installation serializer changes. */
internal object LocalEmployeePolicyActivationDefinitionWireV1 {
    private const val EXTENDED_DOMAIN = "complete-local-employee-policy-definition/v2"

    fun encode(value: EmployeePolicyActivationFieldsV1): ByteArray {
        val configuration = value.policy.employeeCreationConfiguration
        if (configuration == null) return encodeLegacy(value)
        val legacy = encodeLegacy(value.copy(policy = value.policy.withEmployeeCreationConfiguration(null)))
        val extension = configuration.copyWireBytes()
        return try {
            selfOwnedEncode(EXTENDED_DOMAIN, LocalEmployeePolicyActivationProfileV1.MAXIMUM_DEFINITION_BYTES) {
                field(legacy); field(extension)
            }
        } finally { legacy.fill(0); extension.fill(0) }
    }

    private fun encodeLegacy(value: EmployeePolicyActivationFieldsV1): ByteArray = selfOwnedEncode("complete-local-employee-policy-definition/v1",
        LocalEmployeePolicyActivationProfileV1.MAXIMUM_DEFINITION_BYTES) {
        string(LocalEmployeePolicyActivationProfileV1.PURPOSE)
        val request = value.request; val policy = value.policy
        scope(request.scope); id(request.requestId); id(request.targetId); string(request.contentDigest.value)
        long(policy.revision.value); int(policy.grants.size)
        for (grant in policy.grants) {
            string(grant.permission.action.name); string(grant.permission.verb.name)
            when (val tenant = grant.scope) {
                is CompanyScope -> { int(1); id(tenant.organizationId); id(tenant.companyId) }
                is BranchScope -> { int(2); scope(tenant) }
            }
            string(grant.effect.name); limits(grant.limits); string(grant.ownerAccess.name)
            nullableId(grant.namedSubjectId); bool(grant.targetIds != null)
            grant.targetIds?.let { ids -> int(ids.size); ids.sortedBy { it.value }.forEach(::id) }
            bool(grant.delegationCeiling != null); grant.delegationCeiling?.let { limits(it) }
        }
        int(policy.sessionPolicy.maximumConcurrentSessions)
        val throttle = policy.throttlePolicy
        int(throttle.freeFailures); long(throttle.initialDelayMillis); long(throttle.maximumDelayMillis); long(throttle.attemptTimeoutMillis)
        bool(policy.approvalPolicy != null); policy.approvalPolicy?.let {
            long(it.validForMillis); int(it.namedSelfApprovalActions.size)
            it.namedSelfApprovalActions.sortedBy { action -> action.name }.forEach { action -> string(action.name) }
        }
        octets(value.canonicalPolicy)
    }
    fun decode(bytes: ByteArray): EmployeePolicyActivationFieldsV1 {
        require(bytes.size in 1..LocalEmployeePolicyActivationProfileV1.MAXIMUM_DEFINITION_BYTES)
        if (selfOwnedDefinitionDomain(bytes) == EXTENDED_DOMAIN) return selfOwnedDecode(bytes, EXTENDED_DOMAIN) {
            val legacy = field(LocalEmployeePolicyActivationProfileV1.MAXIMUM_DEFINITION_BYTES)
            val extension = field(LocalEmployeeCreationConfigurationV1.MAXIMUM_BYTES)
            try {
                require(selfOwnedDefinitionDomain(legacy) == "complete-local-employee-policy-definition/v1")
                val value = decode(legacy)
                value.copy(policy = value.policy.withEmployeeCreationConfiguration(
                    LocalEmployeeCreationConfigurationV1.decode(extension)))
            } finally { legacy.fill(0); extension.fill(0) }
        }
        return decodeLegacy(bytes)
    }

    private fun decodeLegacy(bytes: ByteArray): EmployeePolicyActivationFieldsV1 = selfOwnedDecode(bytes, "complete-local-employee-policy-definition/v1") {
        require(bytes.size in 1..LocalEmployeePolicyActivationProfileV1.MAXIMUM_DEFINITION_BYTES &&
            string(128) == LocalEmployeePolicyActivationProfileV1.PURPOSE)
        val branch = scope(); val requestId = id(); val actor = id(); val digest = PayloadDigest(string(64)); val revision = Revision(long())
        val count = int(); require(count in 0..1024)
        val grants = List(count) {
            val action = named(SecurityAction.entries); val verb = named(PermissionVerb.entries)
            val tenant: TenantScope = when (int()) { 1 -> CompanyScope(id(), id()); 2 -> scope(); else -> error("policy.scope") }
            val effect = named(GrantEffect.entries); val limits = limits(); val owner = named(OwnerAccess.entries)
            val subject = nullableId(); val targets = if (bool()) sortedIds(1, 256).toSet() else null
            val ceiling = if (bool()) limits() else null
            PermissionGrant(Permission(action, verb), tenant, effect, limits, owner, subject, targets, ceiling)
        }
        val session = LocalSessionPolicy(int()); val throttle = ThrottlePolicy(int(), long(), long(), long())
        val approval = if (bool()) {
            val duration = long(); val count = int(); require(count in 0..256)
            val actions = List(count) { named(SecurityAction.entries) }
            require(actions.distinct().size == count && actions == actions.sortedBy { it.name })
            LocalApprovalPolicy(duration, actions.toSet())
        } else null
        val canonical = octets(ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes)
        EmployeePolicyActivationFieldsV1(LocalProvisioningRequest(branch, requestId, LocalProvisioningAction.ACTIVATE_POLICY, actor, digest),
            ActivatedLocalPolicy(branch, actor, revision, grants, session, throttle, approval), canonical)
    }
    private fun SelfOwnedWireWriterV1.scope(value: BranchScope) { id(value.organizationId); id(value.companyId); id(value.branchId) }
    private fun SelfOwnedWireReaderV1.scope(): BranchScope = BranchScope(id(), id(), id())
    private fun SelfOwnedWireWriterV1.nullableId(value: DomainId?) { bool(value != null); value?.let(::id) }
    private fun SelfOwnedWireReaderV1.nullableId(): DomainId? = if (bool()) id() else null
    private fun SelfOwnedWireReaderV1.sortedIds(minimum: Int, maximum: Int): List<DomainId> {
        val count = int(); require(count in minimum..maximum)
        val values = List(count) { id() }; require(values.distinct().size == count && values == values.sortedBy { it.value }); return values
    }
    private fun <T : Enum<T>> SelfOwnedWireReaderV1.named(values: List<T>): T { val name = string(128); return values.single { it.name == name } }
    private fun SelfOwnedWireWriterV1.limits(value: PermissionLimits) {
        bool(value.maximumAmount != null); value.maximumAmount?.let { string(it.currency.name); long(it.minor) }
        bool(value.maximumPercentageBasisPoints != null); value.maximumPercentageBasisPoints?.let(::int)
        bool(value.noMonetaryCeiling != null); value.noMonetaryCeiling?.let { string(it.currency.name) }
    }
    private fun SelfOwnedWireReaderV1.limits(): PermissionLimits {
        val amount = if (bool()) Money(named(Currency.entries), long()) else null
        val percentage = if (bool()) int() else null
        val noCeiling = if (bool()) NoMonetaryCeiling(named(Currency.entries)) else null
        return PermissionLimits(amount, percentage, noCeiling)
    }
}
