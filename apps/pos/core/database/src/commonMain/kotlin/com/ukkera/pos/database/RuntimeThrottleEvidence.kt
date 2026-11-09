package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Captured by the retained password-attempt owner, never supplied by an RPC caller. */
internal data class RuntimeThrottleInvocation(
    val attemptId: DomainId,
    val at: MonotonicMoment,
    val outcome: CredentialAttemptOutcome? = null,
)

/** Digests of the actual authenticated records read on the SAME writer loan, not current
 * policy substituted at restart. No password, verifier bytes or bearer is retained here. */
@Serializable
internal data class RuntimeThrottleDependenciesV1(
    val account: PayloadDigest,
    val policy: PayloadDigest,
    val admission: PayloadDigest,
    val configuration: PayloadDigest?,
)

@Serializable
internal data class RuntimeThrottlePolicyV1(
    val freeFailures: Int,
    val initialDelayMillis: Long,
    val maximumDelayMillis: Long,
    val attemptTimeoutMillis: Long,
) {
    fun value(): ThrottlePolicy = ThrottlePolicy(freeFailures, initialDelayMillis, maximumDelayMillis, attemptTimeoutMillis)
    companion object {
        fun capture(policy: ThrottlePolicy): RuntimeThrottlePolicyV1 = RuntimeThrottlePolicyV1(
            policy.freeFailures, policy.initialDelayMillis, policy.maximumDelayMillis, policy.attemptTimeoutMillis)
    }
}

@Serializable
internal data class RuntimeThrottleBucketV1(
    val revision: Revision,
    val failures: Int,
    val lastObservation: MonotonicMoment,
    val remainingDelayMillis: Long,
    val attemptId: DomainId?,
    val attemptStartedAt: MonotonicMoment?,
) {
    fun value(key: ThrottleKey): PersistentThrottleState {
        require((attemptId == null) == (attemptStartedAt == null))
        return PersistentThrottleState(key, revision, failures, lastObservation, remainingDelayMillis,
            attemptId?.let { ThrottleAttempt(it, requireNotNull(attemptStartedAt)) })
    }
    companion object {
        fun capture(state: PersistentThrottleState): RuntimeThrottleBucketV1 = RuntimeThrottleBucketV1(
            state.revision, state.failures, state.lastObservation, state.remainingDelayMillis,
            state.activeAttempt?.attemptId, state.activeAttempt?.startedAt)
    }
}

/** Explicit retained fact profile. It proves a bounded paired throttle transition only, NOT
 * session issuance, policy activation, another employee's rights, or financial admission. */
@Serializable
internal data class RuntimeThrottleEvidenceV1(
    val schema: String = PROFILE,
    val scope: BranchScope,
    val actorId: DomainId,
    val deviceId: DomainId,
    val attemptId: DomainId,
    val at: MonotonicMoment,
    val outcome: CredentialAttemptOutcome?,
    val policy: RuntimeThrottlePolicyV1,
    val dependencies: RuntimeThrottleDependenciesV1,
    val accountBefore: RuntimeThrottleBucketV1,
    val deviceBefore: RuntimeThrottleBucketV1,
    val accountAfter: RuntimeThrottleBucketV1,
    val deviceAfter: RuntimeThrottleBucketV1,
    val denial: ThrottleDenial?,
) {
    val purpose: RuntimeWritePurposeV2 get() = if (outcome == null)
        RuntimeWritePurposeV2.THROTTLE_RESERVE else RuntimeWritePurposeV2.THROTTLE_COMPLETE
    val accountKey: ThrottleKey get() = ThrottleKey(scope, actorId, ThrottleDimension.Account)
    val deviceKey: ThrottleKey get() = ThrottleKey(scope, actorId, ThrottleDimension.Device(deviceId))
    fun before(key: ThrottleKey): PersistentThrottleState = when (key) {
        accountKey -> accountBefore.value(key)
        deviceKey -> deviceBefore.value(key)
        else -> error("runtime.throttle.key")
    }
    fun after(key: ThrottleKey): PersistentThrottleState = when (key) {
        accountKey -> accountAfter.value(key)
        deviceKey -> deviceAfter.value(key)
        else -> error("runtime.throttle.key")
    }
    fun changed(): List<PersistentThrottleState> = listOf(accountKey, deviceKey)
        .filter { before(it) != after(it) }.map(::after)

    fun validate() {
        require(schema == PROFILE)
        val a = before(accountKey)
        val d = before(deviceKey)
        val result = outcome?.let { ThrottlePairReducer.complete(a, d, attemptId, it, at, policy.value()) }
            ?: ThrottlePairReducer.begin(a, d, attemptId, at, policy.value())
        require(result.account == after(accountKey) && result.device == after(deviceKey) && result.denial == denial)
        // Reuse the actual protected-record codec's additional revision/time/key constraints.
        for (state in listOf(a, d, result.account, result.device))
            ProtectedLocalSecurityCodec.encodeThrottle(state).fill(0)
    }

    companion object {
        const val PROFILE: String = "ukkera.runtime-throttle-evidence/v1"

        fun capture(invocation: RuntimeThrottleInvocation, policy: ThrottlePolicy,
            dependencies: RuntimeThrottleDependenciesV1, accountBefore: PersistentThrottleState,
            deviceBefore: PersistentThrottleState, accountAfter: PersistentThrottleState,
            deviceAfter: PersistentThrottleState, denial: ThrottleDenial?): RuntimeThrottleEvidenceV1 {
            val dimension = deviceBefore.key.dimension as ThrottleDimension.Device
            require(accountBefore.key.scope == deviceBefore.key.scope && accountBefore.key.actorId == deviceBefore.key.actorId &&
                accountBefore.key.dimension == ThrottleDimension.Account && accountAfter.key == accountBefore.key &&
                deviceAfter.key == deviceBefore.key)
            return RuntimeThrottleEvidenceV1(scope = accountBefore.key.scope, actorId = accountBefore.key.actorId,
                deviceId = dimension.deviceId, attemptId = invocation.attemptId, at = invocation.at,
                outcome = invocation.outcome, policy = RuntimeThrottlePolicyV1.capture(policy), dependencies = dependencies,
                accountBefore = RuntimeThrottleBucketV1.capture(accountBefore), deviceBefore = RuntimeThrottleBucketV1.capture(deviceBefore),
                accountAfter = RuntimeThrottleBucketV1.capture(accountAfter), deviceAfter = RuntimeThrottleBucketV1.capture(deviceAfter),
                denial = denial).also { it.validate() }
        }
    }
}

internal object RuntimeThrottleEvidenceCodec {
    private const val MAX_BYTES = 16 * 1024
    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }
    // This is only a profile discriminator. A positive result is never a successful decode.
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in 1..MAX_BYTES &&
        bytes.size >= PROFILE_PREFIX.size && PROFILE_PREFIX.indices.all { bytes[it] == PROFILE_PREFIX[it] }

    fun encode(value: RuntimeThrottleEvidenceV1): ByteArray {
        value.validate()
        return json.encodeToString(RuntimeThrottleEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true).also { historyCapacityV2(it.size in 1..MAX_BYTES) }
    }
    fun dependencies(value: RuntimeThrottleEvidenceV1): ByteArray = json
        .encodeToString(RuntimeThrottleDependenciesV1.serializer(), value.dependencies).encodeToByteArray()

    fun decode(bytes: ByteArray): RuntimeThrottleEvidenceV1 {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeThrottleEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        return value
    }
    private val PROFILE_PREFIX = ("{\"schema\":\"" + RuntimeThrottleEvidenceV1.PROFILE + "\",").encodeToByteArray()
}

/** Same native typed key framing used for every original row lineage. */
internal fun runtimeThrottleKey(key: ThrottleKey): ByteArray {
    val parts = listOf(key.scope.storageKey(), key.actorId.value, ProtectedLocalRecordKey.Throttle(key).dimensionKey)
    return runtimeSecurityKey(RuntimeHistoryTableV2.THROTTLE, parts)
}
