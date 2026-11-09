package com.ukkera.pos.security

import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/** New employee ceremony only. Founding purposes, policy/configuration and request bytes stay unchanged. */
public object LocalEmployeeEnrollmentProfileV1 {
    public const val MAXIMUM_ACTIONS: Int = 4096
    public const val MAXIMUM_DEFINITION_BYTES: Int = 2 * 1024 * 1024
    internal const val MAXIMUM_STATE_BYTES: Int = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES
    internal const val MAXIMUM_PLAINTEXT_BYTES: Int = MAXIMUM_STATE_BYTES - 2048
    internal const val MAXIMUM_COMPLETION_BYTES: Int = 128 * 1024
    internal const val PURPOSE: String = "EXPLICIT_NAMED_COMPANY_HEAD_FULL_LOCAL_EMPLOYEE_ENROLLMENT_P256_V1"
}

/**
 * Pure extraction of the existing service digest. The caller transfers the two encoded buffers;
 * framing, sorted device IDs and the credential-presence string are EXACTLY the original V1.
 * This computes requested data only. It does not qualify a definition or authenticate a head.
 */
public fun localAccountProvisioningRequestV1(scope: BranchScope, requestId: DomainId, actorId: DomainId,
    credentialWasProvided: Boolean, canonicalPolicy: ByteArray, canonicalConfiguration: ByteArray,
    admittedDeviceIds: Set<DomainId>): LocalProvisioningRequest {
    val prefix = "ukkera.provision-local-account/v1\u0000$credentialWasProvided\u0000".encodeToByteArray()
    val devices = admittedDeviceIds.map { it.value }.sorted().joinToString("\u0000").encodeToByteArray()
    val fields = listOf(prefix, canonicalPolicy, canonicalConfiguration, devices)
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
        LocalProvisioningRequest(scope, requestId, LocalProvisioningAction.PROVISION_ACCOUNT, actorId, sha256(encoded))
    } finally { bytes?.fill(0); fields.forEach { it.fill(0) } }
}

/**
 * Complete immutable review DATA. Independent semantic bytes include ALL grants/limits and
 * retained admissions; the original service policy/configuration bytes are additionally bound.
 * The real Room bridge must regenerate and compare both original codecs before any effects.
 * Public capture/decoding is never company authority or native/client custody.
 */
public class LocalEmployeeEnrollmentDefinition private constructor(
    canonical: ByteArray,
) {
    private val bytes = canonical.copyOf()
    private val value = LocalEmployeeEnrollmentDefinitionWireV1.decode(bytes)
    public val definitionDigest: PayloadDigest = sha256(bytes)
    public val request: LocalProvisioningRequest get() = value.request
    public val credentialWasProvided: Boolean get() = value.credentialWasProvided
    public val definition: LocalAccountProvisioning get() = value.definition
    public val admissions: List<LocalDeviceAdmission> get() = value.admissions.toList()
    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    public fun copyCanonicalPolicy(): ByteArray = value.policy.copyBytes()
    public fun copyCanonicalConfiguration(): ByteArray = value.configuration.copyBytes()
    override fun toString(): String = "LocalEmployeeEnrollmentDefinition([REDACTED])"

    public companion object {
        /** Buffers remain caller-owned. Their exact relation to the actual effects is checked in Room. */
        public fun capture(requestId: DomainId, definition: LocalAccountProvisioning, credentialWasProvided: Boolean,
            canonicalPolicy: ByteArray, canonicalConfiguration: ByteArray, retainedAdmissions: List<LocalDeviceAdmission>):
            LocalEmployeeEnrollmentDefinition {
            val request = localAccountProvisioningRequestV1(definition.policy.scope, requestId, definition.actorId,
                credentialWasProvided, canonicalPolicy.copyOf(), canonicalConfiguration.copyOf(), definition.admittedDeviceIds)
            val fields = EmployeeDefinitionFieldsV1(request, definition, credentialWasProvided,
                SelfOwnedOctetsV1(canonicalPolicy, ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes),
                SelfOwnedOctetsV1(canonicalConfiguration, ProtectedLocalTable.AUTH_CONFIGURATION.maximumPlaintextBytes),
                retainedAdmissions.sortedBy { it.deviceId.value })
            val bytes = LocalEmployeeEnrollmentDefinitionWireV1.encode(fields)
            return try { LocalEmployeeEnrollmentDefinition(bytes) } finally { bytes.fill(0) }
        }
        public fun decode(canonical: ByteArray): LocalEmployeeEnrollmentDefinition {
            require(canonical.size in 1..LocalEmployeeEnrollmentProfileV1.MAXIMUM_DEFINITION_BYTES)
            val result = LocalEmployeeEnrollmentDefinition(canonical)
            val encoded = LocalEmployeeEnrollmentDefinitionWireV1.encode(result.value)
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return result
        }
    }
}

internal data class EmployeeDefinitionFieldsV1(
    val request: LocalProvisioningRequest,
    val definition: LocalAccountProvisioning,
    val credentialWasProvided: Boolean,
    val policy: SelfOwnedOctetsV1,
    val configuration: SelfOwnedOctetsV1,
    val admissions: List<LocalDeviceAdmission>,
) {
    init {
        require(request.action == LocalProvisioningAction.PROVISION_ACCOUNT && request.scope == definition.policy.scope &&
            request.targetId == definition.actorId && policy.size in 1..ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes &&
            configuration.size in 1..ProtectedLocalTable.AUTH_CONFIGURATION.maximumPlaintextBytes)
        require(admissions.size in 1..256 && admissions.map { it.deviceId }.distinct().size == admissions.size &&
            admissions.all { it.scope == request.scope } && admissions == admissions.sortedBy { it.deviceId.value } &&
            admissions.filter { it.admitted }.map { it.deviceId }.toSet() == definition.admittedDeviceIds)
        val digest = localAccountProvisioningRequestV1(request.scope, request.requestId, request.targetId,
            credentialWasProvided, policy.copyBytes(), configuration.copyBytes(), definition.admittedDeviceIds)
        require(digest == request)
    }
}

internal object LocalEmployeeEnrollmentDefinitionWireV1 {
    fun encode(value: EmployeeDefinitionFieldsV1): ByteArray = selfOwnedEncode("full-local-employee-enrollment-definition/v1",
        LocalEmployeeEnrollmentProfileV1.MAXIMUM_DEFINITION_BYTES) {
        string(LocalEmployeeEnrollmentProfileV1.PURPOSE)
        val request = value.request; val definition = value.definition; val policy = definition.policy
        scope(request.scope); id(request.requestId); id(definition.actorId); string(request.contentDigest.value)
        string(definition.login.value); bool(value.credentialWasProvided)
        long(policy.revision.value); int(policy.grants.size)
        for (grant in policy.grants) {
            string(grant.permission.action.name); string(grant.permission.verb.name)
            when (val tenant = grant.scope) { is CompanyScope -> { int(1); id(tenant.organizationId); id(tenant.companyId) }
                is BranchScope -> { int(2); scope(tenant) } }
            string(grant.effect.name); limits(grant.limits); string(grant.ownerAccess.name)
            nullableId(grant.namedSubjectId); bool(grant.targetIds != null)
            grant.targetIds?.let { targets -> int(targets.size); targets.sortedBy { it.value }.forEach(::id) }
            bool(grant.delegationCeiling != null); grant.delegationCeiling?.let { limits(it) }
        }
        int(policy.sessionPolicy.maximumConcurrentSessions)
        val throttle = policy.throttlePolicy
        int(throttle.freeFailures); long(throttle.initialDelayMillis); long(throttle.maximumDelayMillis); long(throttle.attemptTimeoutMillis)
        bool(policy.approvalPolicy != null); policy.approvalPolicy?.let {
            long(it.validForMillis); int(it.namedSelfApprovalActions.size)
            it.namedSelfApprovalActions.sortedBy { action -> action.name }.forEach { action -> string(action.name) }
        }
        long(definition.tokenPolicy.accessLifetimeMillis); long(definition.tokenPolicy.workLifetimeMillis)
        int(definition.admittedDeviceIds.size); definition.admittedDeviceIds.sortedBy { it.value }.forEach(::id)
        octets(value.policy); octets(value.configuration)
        int(value.admissions.size); value.admissions.forEach { admission(it) }
    }

    fun decode(bytes: ByteArray): EmployeeDefinitionFieldsV1 {
        require(bytes.size in 1..LocalEmployeeEnrollmentProfileV1.MAXIMUM_DEFINITION_BYTES)
        return selfOwnedDecode(bytes, "full-local-employee-enrollment-definition/v1") {
            require(string(128) == LocalEmployeeEnrollmentProfileV1.PURPOSE)
            val branch = scope(); val requestId = id(); val actor = id(); val digest = PayloadDigest(string(64))
            val login = LocalLoginIdentifier(string(256)); val credential = bool(); val revision = Revision(long())
            val count = int(); require(count in 0..1024)
            val grants = List(count) {
                val action = named(SecurityAction.entries); val verb = named(PermissionVerb.entries)
                val tenant: TenantScope = when (int()) { 1 -> CompanyScope(id(), id()); 2 -> scope(); else -> error("employee.scope") }
                val effect = named(GrantEffect.entries); val limits = limits(); val owner = named(OwnerAccess.entries)
                val subject = nullableId(); val targets = if (bool()) sortedIds(1, 256).toSet() else null
                val ceiling = if (bool()) limits() else null
                PermissionGrant(Permission(action, verb), tenant, effect, limits, owner, subject, targets, ceiling)
            }
            val session = LocalSessionPolicy(int()); val throttle = ThrottlePolicy(int(), long(), long(), long())
            val approval = if (bool()) {
                val duration = long(); val actions = int(); require(actions in 0..256)
                val values = List(actions) { named(SecurityAction.entries) }
                require(values.distinct().size == values.size && values == values.sortedBy { it.name })
                LocalApprovalPolicy(duration, values.toSet())
            } else null
            val tokens = LocalTokenPolicy(long(), long()); val devices = sortedIds(1, 256).toSet()
            val policy = octets(ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes)
            val configuration = octets(ProtectedLocalTable.AUTH_CONFIGURATION.maximumPlaintextBytes)
            val admissionsCount = int(); require(admissionsCount in 1..256)
            val admissions = List(admissionsCount) { admission() }
            val definition = LocalAccountProvisioning(actor, login, ActivatedLocalPolicy(branch, actor, revision, grants,
                session, throttle, approval), tokens, devices)
            EmployeeDefinitionFieldsV1(LocalProvisioningRequest(branch, requestId, LocalProvisioningAction.PROVISION_ACCOUNT,
                actor, digest), definition, credential, policy, configuration, admissions)
        }
    }

    private fun SelfOwnedWireWriterV1.scope(value: BranchScope) { id(value.organizationId); id(value.companyId); id(value.branchId) }
    private fun SelfOwnedWireReaderV1.scope(): BranchScope = BranchScope(id(), id(), id())
    private fun SelfOwnedWireWriterV1.nullableId(value: DomainId?) { bool(value != null); value?.let(::id) }
    private fun SelfOwnedWireReaderV1.nullableId(): DomainId? = if (bool()) id() else null
    private fun SelfOwnedWireWriterV1.nullableString(value: String?) { bool(value != null); value?.let(::string) }
    private fun SelfOwnedWireReaderV1.nullableString(): String? = if (bool()) string(1024) else null
    private fun SelfOwnedWireReaderV1.sortedIds(minimum: Int, maximum: Int): List<DomainId> {
        val count = int(); require(count in minimum..maximum)
        val values = List(count) { id() }
        require(values.distinct().size == count && values == values.sortedBy { it.value }); return values
    }
    private fun <T : Enum<T>> SelfOwnedWireReaderV1.named(values: List<T>): T {
        val name = string(128); return values.single { it.name == name }
    }
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
    private fun SelfOwnedWireWriterV1.admission(value: LocalDeviceAdmission) {
        scope(value.scope); id(value.deviceId); long(value.revision.value); bool(value.admitted)
        nullableString(value.publicKeyIdentifier); nullableString(value.publicFingerprint)
        int(value.capabilities.size); value.capabilities.sorted().forEach(::string)
    }
    private fun SelfOwnedWireReaderV1.admission(): LocalDeviceAdmission {
        val branch = scope(); val device = id(); val revision = Revision(long()); val admitted = bool()
        val key = nullableString(); val fingerprint = nullableString(); val count = int()
        require(count in 0..ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITIES)
        val values = List(count) { string(ProtectedLocalStorageFormat.MAX_DEVICE_CAPABILITY_BYTES) }
        require(values.distinct().size == count && values == values.sorted())
        return LocalDeviceAdmission(branch, device, revision, admitted, key, fingerprint, values.toSet())
    }
}
