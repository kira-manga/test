package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/**
 * One company-authored, complete authentication configuration that a delegated creator may
 * assign. These values are requested policy DATA. Their presence in an arbitrary object grants
 * no authority; only the actual current protected policy can supply a creation catalogue.
 * A named self-approval exception always names its recipient, never a role or future user.
 */
public class LocalEmployeeCreationAuthenticationProfileV1(
    public val sessionPolicy: LocalSessionPolicy,
    public val throttlePolicy: ThrottlePolicy,
    public val tokenPolicy: LocalTokenPolicy,
    public val approvalPolicy: LocalApprovalPolicy?,
    public val namedRecipientId: DomainId?,
) {
    init {
        require(approvalPolicy?.namedSelfApprovalActions.orEmpty().all { AuthorizationEvaluator.localRealmAllows(it) }) {
            "A local creation profile cannot delegate a company or recovery action"
        }
        require(approvalPolicy?.namedSelfApprovalActions.isNullOrEmpty() || namedRecipientId != null) {
            "A self-approval exception requires an explicitly named recipient"
        }
    }

    internal fun matches(definition: LocalAccountProvisioning): Boolean {
        val policy = definition.policy
        return (namedRecipientId == null || namedRecipientId == definition.actorId) &&
            sessionPolicy == policy.sessionPolicy && throttlePolicy == policy.throttlePolicy &&
            tokenPolicy == definition.tokenPolicy && approvalsEqual(approvalPolicy, policy.approvalPolicy)
    }

    override fun toString(): String = "LocalEmployeeCreationAuthenticationProfileV1([REDACTED])"
}

/**
 * Exact, immutable company-defined choices for delegated creation. Authentication parameters
 * are compared as COMPLETE profiles: bounds from separate profiles cannot be combined and no
 * ordering of throttle/security parameters is guessed. Missing policy never means unrestricted
 * configuration. Grant transfer ceilings and current account-management authorization remain
 * separate mandatory checks. This catalogue is not a creation permit or native history proof.
 */
public class LocalEmployeeCreationConfigurationV1 private constructor(canonical: ByteArray) {
    private val bytes = canonical.copyOf()
    private val profiles = LocalEmployeeCreationConfigurationWireV1.decode(bytes)
    public val contentDigest: PayloadDigest = sha256(bytes)

    public fun copyWireBytes(): ByteArray = bytes.copyOf()
    public fun copyProfiles(): List<LocalEmployeeCreationAuthenticationProfileV1> =
        LocalEmployeeCreationConfigurationWireV1.decode(bytes)

    /** Pure negative preflight only. Its caller must obtain this object from CURRENT protected
     * policy and repeat all checks at the actual writer. No public DATA becomes authority. */
    public fun permits(definition: LocalAccountProvisioning): Boolean {
        if (profiles.none { it.matches(definition) }) return false
        val onward = definition.policy.employeeCreationConfiguration ?: return true
        // An onward catalogue must consist of exact choices that this creator already has.
        // No implicit delegation of named exceptions or combination of configuration fields.
        val retained = profiles.map(::profileDigest).toSet()
        return onward.profiles.all { profileDigest(it) in retained }
    }

    override fun toString(): String = "LocalEmployeeCreationConfigurationV1([REDACTED])"

    public companion object {
        public const val MAXIMUM_BYTES: Int = 64 * 1024
        public const val MAXIMUM_PROFILES: Int = 64

        public fun capture(profiles: List<LocalEmployeeCreationAuthenticationProfileV1>): LocalEmployeeCreationConfigurationV1 {
            val bytes = LocalEmployeeCreationConfigurationWireV1.encode(profiles)
            return try { LocalEmployeeCreationConfigurationV1(bytes) } finally { bytes.fill(0) }
        }

        public fun decode(canonical: ByteArray): LocalEmployeeCreationConfigurationV1 {
            require(canonical.size in 1..MAXIMUM_BYTES)
            val value = LocalEmployeeCreationConfigurationV1(canonical)
            val encoded = LocalEmployeeCreationConfigurationWireV1.encode(value.profiles)
            try { require(encoded.contentEquals(canonical)) } finally { encoded.fill(0) }
            return value
        }
    }
}

private fun approvalsEqual(left: LocalApprovalPolicy?, right: LocalApprovalPolicy?): Boolean =
    if (left == null || right == null) left == null && right == null
    else left.validForMillis == right.validForMillis && left.namedSelfApprovalActions == right.namedSelfApprovalActions

private fun profileDigest(value: LocalEmployeeCreationAuthenticationProfileV1): PayloadDigest {
    val bytes = LocalEmployeeCreationConfigurationWireV1.profile(value)
    return try { sha256(bytes) } finally { bytes.fill(0) }
}

private object LocalEmployeeCreationConfigurationWireV1 {
    private const val DOMAIN = "company-delegated-employee-authentication-configurations/v1"
    private const val PROFILE = "company-delegated-employee-authentication-profile/v1"
    private const val PROFILE_MAXIMUM_BYTES = 32 * 1024

    fun encode(profiles: List<LocalEmployeeCreationAuthenticationProfileV1>): ByteArray {
        require(profiles.size in 1..LocalEmployeeCreationConfigurationV1.MAXIMUM_PROFILES)
        val ordered = profiles.map { profileDigest(it).value to it }.sortedBy { it.first }
        require(ordered.map { it.first }.distinct().size == ordered.size)
        return selfOwnedEncode(DOMAIN, LocalEmployeeCreationConfigurationV1.MAXIMUM_BYTES) {
            int(ordered.size)
            for ((_, value) in ordered) {
                val encoded = profile(value)
                try { field(encoded) } finally { encoded.fill(0) }
            }
        }
    }

    fun decode(bytes: ByteArray): List<LocalEmployeeCreationAuthenticationProfileV1> = selfOwnedDecode(bytes, DOMAIN) {
        require(bytes.size in 1..LocalEmployeeCreationConfigurationV1.MAXIMUM_BYTES)
        val count = int(); require(count in 1..LocalEmployeeCreationConfigurationV1.MAXIMUM_PROFILES)
        var previous: String? = null
        List(count) {
            val encoded = field(PROFILE_MAXIMUM_BYTES)
            try {
                val digest = sha256(encoded).value
                require(previous == null || checkNotNull(previous) < digest)
                previous = digest
                val value = decodeProfile(encoded)
                val canonical = profile(value)
                try { require(canonical.contentEquals(encoded)) } finally { canonical.fill(0) }
                value
            } finally { encoded.fill(0) }
        }
    }

    fun profile(value: LocalEmployeeCreationAuthenticationProfileV1): ByteArray = selfOwnedEncode(PROFILE, PROFILE_MAXIMUM_BYTES) {
        bool(value.namedRecipientId != null); value.namedRecipientId?.let(::id)
        int(value.sessionPolicy.maximumConcurrentSessions)
        with(value.throttlePolicy) { int(freeFailures); long(initialDelayMillis); long(maximumDelayMillis); long(attemptTimeoutMillis) }
        long(value.tokenPolicy.accessLifetimeMillis); long(value.tokenPolicy.workLifetimeMillis)
        bool(value.approvalPolicy != null)
        value.approvalPolicy?.let {
            long(it.validForMillis)
            val actions = it.namedSelfApprovalActions.sortedBy(SecurityAction::name)
            int(actions.size); actions.forEach { action -> string(action.name) }
        }
    }

    private fun decodeProfile(bytes: ByteArray): LocalEmployeeCreationAuthenticationProfileV1 = selfOwnedDecode(bytes, PROFILE) {
        val recipient = if (bool()) id() else null
        val sessions = LocalSessionPolicy(int())
        val throttle = ThrottlePolicy(int(), long(), long(), long())
        val tokens = LocalTokenPolicy(long(), long())
        val approval = if (bool()) {
            val duration = long(); val count = int(); require(count in 0..256)
            val actions = List(count) { val name = string(128); SecurityAction.entries.single { it.name == name } }
            require(actions.distinct().size == count && actions == actions.sortedBy(SecurityAction::name))
            LocalApprovalPolicy(duration, actions.toSet())
        } else null
        LocalEmployeeCreationAuthenticationProfileV1(sessions, throttle, tokens, approval, recipient)
    }
}

/** Preserve all old semantic encodings when a policy has no additive configuration catalogue. */
internal fun ActivatedLocalPolicy.withEmployeeCreationConfiguration(
    configuration: LocalEmployeeCreationConfigurationV1?,
): ActivatedLocalPolicy = ActivatedLocalPolicy(scope, actorId, revision, grants, sessionPolicy,
    throttlePolicy, approvalPolicy, configuration)

/** Only selects a versioned DATA decoder; it does not accept or authenticate the payload. */
internal fun selfOwnedDefinitionDomain(bytes: ByteArray): String {
    val reader = SelfOwnedWireReaderV1(bytes)
    require(reader.string(128) == SelfOwnedInstallationProfileV1.ID)
    return reader.string(128)
}
