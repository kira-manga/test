package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Input-selection DATA, never absence, a prepared original or target authority. */
public data class LocalEmployeeCreationTargetV1(public val requestId: DomainId, public val actorId: DomainId) {
    override fun toString(): String = "LocalEmployeeCreationTargetV1([REDACTED])"
}

/** Historical protected input projection. Only the actual service can observe current policy;
 * copying or constructing this value cannot delegate grants/configuration or admit a device. */
public class LocalEmployeeCreationContextV1(
    public val scope: BranchScope,
    public val operatorId: DomainId,
    public val target: LocalEmployeeCreationTargetV1,
    public val policyRevision: Revision,
    public val authenticationChoices: LocalEmployeeCreationConfigurationV1,
    admissions: List<LocalDeviceAdmission>,
) {
    private val admissionsValue = admissions.sortedBy { it.deviceId.value }
    public val admittedDeviceIds: Set<DomainId> get() = admissionsValue.filter { it.admitted }.map { it.deviceId }.toSet()
    public fun copyAdmissions(): List<LocalDeviceAdmission> = admissionsValue.toList()
    init {
        require(operatorId != target.actorId && admissionsValue.size in 1..256 && admittedDeviceIds.isNotEmpty())
        require(admissionsValue.all { it.scope == scope } &&
            admissionsValue.map { it.deviceId }.distinct().size == admissionsValue.size)
        require(authenticationChoices.copyProfiles().any { it.namedRecipientId == null || it.namedRecipientId == target.actorId })
    }
    override fun toString(): String = "LocalEmployeeCreationContextV1([REDACTED])"
}

/** Immutable requested definition without a password or fabricated protected encoding.
 * Actual service codecs and retained admissions are still needed to construct the original. */
public class LocalEmployeeCreationDraftV1(
    public val requestId: DomainId,
    definition: LocalAccountProvisioning,
    public val credentialWasProvided: Boolean,
) {
    private val definitionValue = copyDefinition(definition)
    public val scope: BranchScope get() = definitionValue.policy.scope
    public val actorId: DomainId get() = definitionValue.actorId
    public fun copyDefinition(): LocalAccountProvisioning = copyDefinition(definitionValue)

    /** Complete semantic association only. The observed encoding/admissions remain DATA;
     * actual codec consistency, currentness and native admission belong to the service. */
    public fun matchesRequested(original: LocalDelegatedEmployeeCreationV1): Boolean {
        if (original.requestId != requestId || original.scope != scope || original.actorId != actorId) return false
        val requested = original.copyRequestedDefinition()
        if (requested.credentialWasProvided != credentialWasProvided) return false
        val policy = requested.copyCanonicalPolicy()
        try {
            val configuration = requested.copyCanonicalConfiguration()
            try {
                val expected = LocalEmployeeEnrollmentDefinition.capture(requestId, copyDefinition(), credentialWasProvided,
                    policy, configuration, requested.admissions).copyWireBytes()
                try {
                    val observed = requested.copyWireBytes()
                    return try { expected.contentEquals(observed) } finally { observed.fill(0) }
                } finally { expected.fill(0) }
            } finally { configuration.fill(0) }
        } finally { policy.fill(0) }
    }

    override fun toString(): String = "LocalEmployeeCreationDraftV1([REDACTED])"

    private fun copyDefinition(value: LocalAccountProvisioning): LocalAccountProvisioning = LocalAccountProvisioning(
        value.actorId, value.login, value.policy, value.tokenPolicy, value.admittedDeviceIds)
}

public sealed interface LocalEmployeeCreationContextResultV1 {
    public class Available(public val context: LocalEmployeeCreationContextV1) : LocalEmployeeCreationContextResultV1
    public class Refused(public val review: LocalEmployeeCreationReviewV1.Refused) : LocalEmployeeCreationContextResultV1
    public data object Denied : LocalEmployeeCreationContextResultV1
    public data object Unavailable : LocalEmployeeCreationContextResultV1
    public data object StorageUncertain : LocalEmployeeCreationContextResultV1
    public data object Busy : LocalEmployeeCreationContextResultV1
}

/** No-effect definition read. Captured is neither journal preparation nor dispatch authority. */
public sealed interface LocalEmployeeCreationDefinitionResultV1 {
    public class Captured(public val original: LocalDelegatedEmployeeCreationV1) : LocalEmployeeCreationDefinitionResultV1
    public class Refused(public val review: LocalEmployeeCreationReviewV1.Refused) : LocalEmployeeCreationDefinitionResultV1
    public data object Denied : LocalEmployeeCreationDefinitionResultV1
    public data object Unavailable : LocalEmployeeCreationDefinitionResultV1
    public data object StorageUncertain : LocalEmployeeCreationDefinitionResultV1
    public data object Busy : LocalEmployeeCreationDefinitionResultV1
}
