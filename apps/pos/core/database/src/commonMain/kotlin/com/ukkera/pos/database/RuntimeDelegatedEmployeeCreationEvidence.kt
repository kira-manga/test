package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Separate INSERT vocabulary. Existing authentication images deliberately classify ACCOUNT
 * and THROTTLE as UPDATEs and cannot represent a genuine absent-account creation. */
@Serializable
internal data class RuntimeEmployeeCreationImageV1(
    val tableTag: Int,
    val recordId: DomainId,
    val dimension: String?,
    val plaintextDigest: PayloadDigest,
) {
    val table: RuntimeHistoryTableV2 get() = RuntimeHistoryTableV2.fromTag(tableTag)
    val mutation: RuntimeHistoryMutationKindV2 get() = RuntimeHistoryMutationKindV2.INSERT

    fun key(scope: BranchScope): ByteArray {
        require(table in setOf(RuntimeHistoryTableV2.ACCOUNT, RuntimeHistoryTableV2.POLICY,
            RuntimeHistoryTableV2.CONFIGURATION, RuntimeHistoryTableV2.THROTTLE, RuntimeHistoryTableV2.AUTH_EVENT))
        if (table == RuntimeHistoryTableV2.THROTTLE) {
            val value = requireNotNull(dimension)
            require(value == "account" || value.startsWith("device:") && value.length == 43 &&
                DomainId(value.substring(7)).value == value.substring(7))
        } else require(dimension == null)
        val parts = listOf(scope.storageKey(), recordId.value)
        return runtimeSecurityKey(table, parts + dimension?.let(::listOf).orEmpty())
    }

    fun matches(binding: ProtectedLocalBinding): Boolean = binding.revision == Revision(0) &&
        binding.key.recordId == recordId && binding.key.table == table.protectedTable &&
        (binding.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey == dimension

    override fun toString(): String = "RuntimeEmployeeCreationImageV1([REDACTED])"
}

/** Retained DATA for a separately typed creation. Decoding checks structure and commitments;
 * it proves neither absence nor current/historical authority. A future native integration
 * must join the actual original, authenticated creator prefix, complete account/admission
 * census, native trigger INSERTs, decrypted seeds, final sample and confirmed close.
 * This profile is intentionally not registered as an accepted native history family yet. */
@Serializable
internal data class RuntimeDelegatedEmployeeCreationEvidenceV1(
    val schema: String = PROFILE,
    val originalHex: String,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val at: MonotonicMoment,
    val preflightContextId: DomainId,
    val preflightAt: MonotonicMoment,
    val beforeAccountIds: List<DomainId>,
    val images: List<RuntimeEmployeeCreationImageV1>,
) {
    fun original(): LocalDelegatedEmployeeCreationV1 {
        require(originalHex.length in 2..MAXIMUM_ORIGINAL_BYTES * 2 && originalHex.length % 2 == 0 &&
            originalHex.all { it in '0'..'9' || it in 'a'..'f' })
        val bytes = ByteArray(originalHex.length / 2) { index ->
            originalHex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return try { LocalDelegatedEmployeeCreationV1.decode(bytes) } finally { bytes.fill(0) }
    }

    /** Declaration DATA for the existing native protected-final lane. The actual owner must
     * sample after this same writer's preflight; the event/initial access sample is earlier. */
    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        validateStructure()
        val request = original()
        return NativeHistoryProtectedFinalDeclaration(request.requestId, request.contentDigest, request.operatorId,
            authorization.sessionId, authorization.access.issuerContextId, authorization.access.issuedAt,
            preflightAt, authorization.access.expiresAtElapsedMillis)
    }

    /** Structural validation only: images[0] remains an unproved ACCOUNT commitment until
     * the actual native decrypted seed is independently supplied to requireActualSeed. */
    fun validateStructure() {
        require(schema == PROFILE)
        authorization.validate()
        val original = original()
        requireCreationDefinition(original)
        require(authorization.actorId == original.operatorId && authorization.scope == original.scope &&
            preflightContextId == authorization.access.issuerContextId &&
            at.bootId == authorization.access.observedAt.bootId && preflightAt.bootId == at.bootId &&
            at.elapsedMillis >= authorization.access.observedAt.elapsedMillis &&
            preflightAt.elapsedMillis >= at.elapsedMillis &&
            preflightAt.elapsedMillis < authorization.access.expiresAtElapsedMillis)
        require(beforeAccountIds.size in 1..4095 && beforeAccountIds.distinct().size == beforeAccountIds.size &&
            beforeAccountIds == beforeAccountIds.sortedBy { it.value } &&
            original.operatorId in beforeAccountIds && original.actorId !in beforeAccountIds)
        require(images.isNotEmpty())
        val expected = creationImages(original, authorization, at, images.first().plaintextDigest)
        require(images == expected)
    }

    /** Pure replay comparison, never an accepting native reader. The caller must obtain this
     * ACCOUNT from the exact authenticated historical INSERT, not a current/copied row. */
    fun requireActualSeed(account: ProtectedLocalAccount) {
        validateStructure()
        val seed = RuntimeEmployeeCreationSeed.delegated(original(), authorization, account.credential, at)
        val actual = creationDigest(ProtectedLocalSecurityCodec.encodeAccount(account))
        require(actual == creationDigest(ProtectedLocalSecurityCodec.encodeAccount(seed.account)) &&
            images == creationImages(original(), authorization, at, actual))
    }

    override fun toString(): String = "RuntimeDelegatedEmployeeCreationEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE = "ukkera.runtime-delegated-employee-creation-evidence/v1"
        // Leaves room for hex framing, the complete census and all image commitments inside
        // the unchanged native capsule ceiling. Capacity refusal never truncates a request.
        const val MAXIMUM_ORIGINAL_BYTES = SelfOwnedNativeHistoryWireV2.MAX_CAPSULE_BYTES / 4
    }
}

/** Same-writer capture building block. It stores no verifier, bearer or plaintext ACCOUNT.
 * Only a future closed Room sealing path may feed actual image commitments. This object
 * cannot prepare a child, sign, acquire a writer, grant absence or admit native history. */
internal class RuntimeDelegatedEmployeeCreationCapture private constructor(
    original: LocalDelegatedEmployeeCreationV1,
    private val authorization: RuntimeCommandAuthorizationSnapshotV1,
    private val at: MonotonicMoment,
    private val expectedImages: List<RuntimeEmployeeCreationImageV1>,
) {
    private val originalBytes = original.copyWireBytes()
    val scope: BranchScope = original.scope
    val actorId: DomainId = original.operatorId
    val requestId: DomainId = original.requestId
    val semanticDigest: PayloadDigest = original.contentDigest
    private val targetId = original.actorId
    private var census: List<DomainId>? = null
    private var preflight: RoomLocalAuthorizationObservation? = null
    private val captured = mutableListOf<RuntimeEmployeeCreationImageV1>()
    private var completed = false

    /** Only the actual still-live zero-write transaction's complete protected input
     * comparison can supply this census. It is consumed once inside its original loan;
     * a public read response, ID list or supplied 'absent' flag cannot substitute for it. */
    suspend fun bindPreflight(transaction: RoomLocalSecurityTransaction, inputs: RoomLocalEmployeeCreationInputs,
        observed: RoomLocalAuthorizationObservation) {
        check(!completed && census == null && preflight == null && captured.isEmpty())
        require(observed.issuerContextId == authorization.access.issuerContextId &&
            observed.moment.bootId == at.bootId && observed.moment.elapsedMillis >= at.elapsedMillis &&
            observed.moment.elapsedMillis < authorization.access.expiresAtElapsedMillis)
        val accountIds = inputs.consumeAccountIds(transaction, requestId, targetId, semanticDigest, authorization)
        require(accountIds.size in 1..4095 && actorId in accountIds && targetId !in accountIds)
        census = accountIds
        preflight = observed
    }

    fun captureImage(binding: ProtectedLocalBinding, plaintextDigest: PayloadDigest) {
        check(!completed && census != null && preflight != null && captured.size < expectedImages.size)
        val expected = expectedImages[captured.size]
        require(binding.key.scope == scope && expected.matches(binding) && expected.plaintextDigest == plaintextDigest)
        captured += expected
    }

    fun evidence(): RuntimeDelegatedEmployeeCreationEvidenceV1 {
        check(!completed && captured == expectedImages)
        val observed = checkNotNull(preflight)
        val result = RuntimeDelegatedEmployeeCreationEvidenceV1(originalHex = creationHex(originalBytes),
            authorization = authorization, at = at, preflightContextId = observed.issuerContextId,
            preflightAt = observed.moment, beforeAccountIds = checkNotNull(census).toList(), images = captured.toList())
        result.validateStructure()
        completed = true
        return result
    }

    fun clear() {
        completed = true; originalBytes.fill(0); captured.clear(); census = null; preflight = null
    }

    override fun toString(): String = "RuntimeDelegatedEmployeeCreationCapture([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts,
            permitted: AuthorizedAction, original: LocalDelegatedEmployeeCreationV1,
            observed: RoomLocalAuthorizationObservation, at: MonotonicMoment,
            credential: PasswordHashRecord?): RuntimeDelegatedEmployeeCreationCapture {
            requireCreationDefinition(original)
            val snapshot = facts.snapshot(principal)
            require(LocalEmployeeDelegationReviewV1.failure(principal, snapshot, original) == null &&
                facts.policy.employeeCreationConfiguration?.permits(original.copyRequestedDefinition().definition) == true &&
                permitted.isBoundTo(original.authorizationRequirement(), principal.actorId) &&
                permitted.principalKind == PrincipalKind.LOCAL_EMPLOYEE && permitted.authenticationId == principal.sessionId &&
                permitted.generation == principal.sessionGeneration && permitted.policyRevision == facts.policy.revision &&
                permitted.approvalId == null)
            val authorization = RuntimeCommandAuthorizationSnapshotV1.capture(principal, facts, observed)
            val seed = RuntimeEmployeeCreationSeed.delegated(original, authorization, credential, at)
            val accountDigest = creationDigest(ProtectedLocalSecurityCodec.encodeAccount(seed.account))
            return RuntimeDelegatedEmployeeCreationCapture(original, authorization, at,
                creationImages(original, authorization, at, accountDigest))
        }
    }
}

@Serializable
private data class RuntimeDelegatedEmployeeCreationDependenciesV1(
    val operator: RuntimeCommandAuthorizationDependenciesV1,
    val requestedDefinitionDigest: PayloadDigest,
    val beforeAccountIds: List<DomainId>,
)

internal object RuntimeDelegatedEmployeeCreationWireV1 {
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }

    private val prefix = ("{\"schema\":\"" + RuntimeDelegatedEmployeeCreationEvidenceV1.PROFILE + "\",").encodeToByteArray()

    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES &&
        prefix.indices.all { bytes[it] == prefix[it] }

    fun encode(value: RuntimeDelegatedEmployeeCreationEvidenceV1): ByteArray = guarded {
        value.validateStructure()
        val bytes = json.encodeToString(RuntimeDelegatedEmployeeCreationEvidenceV1.serializer(), value).encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES) {
            bytes.fill(0)
            throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.CAPACITY)
        }
        bytes
    }

    /** Exact original creator dependencies and complete before census, separate from facts.
     * These bytes contain commitments/IDs only and never a verifier or accepting capability. */
    fun dependencies(value: RuntimeDelegatedEmployeeCreationEvidenceV1): ByteArray = guarded {
        value.validateStructure()
        val original = value.original()
        val dependencies = RuntimeDelegatedEmployeeCreationDependenciesV1(value.authorization.dependencies,
            original.copyRequestedDefinition().definitionDigest, value.beforeAccountIds)
        val bytes = json.encodeToString(RuntimeDelegatedEmployeeCreationDependenciesV1.serializer(), dependencies)
            .encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES) {
            bytes.fill(0); historyCapacityV2(false)
        }
        bytes
    }

    fun decode(bytes: ByteArray): RuntimeDelegatedEmployeeCreationEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES)
        val value = json.decodeFromString(RuntimeDelegatedEmployeeCreationEvidenceV1.serializer(),
            bytes.decodeToString(throwOnInvalidSequence = true))
        value.validateStructure()
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}

/** Real production codec regeneration: an adjacent semantic list cannot disguise different
 * retained protected policy/configuration or admission bytes under the same input claim. */
private fun requireCreationDefinition(original: LocalDelegatedEmployeeCreationV1) {
    val bytes = original.copyWireBytes()
    try { require(bytes.size in 1..RuntimeDelegatedEmployeeCreationEvidenceV1.MAXIMUM_ORIGINAL_BYTES) }
    finally { bytes.fill(0) }
    val requested = original.copyRequestedDefinition()
    val definition = requested.definition
    val policy = LocalEffectivePolicyCodec.encodePolicy(definition.policy)
    val configuration = LocalAuthenticationCodec.encodeConfiguration(LocalAuthConfiguration(original.scope,
        original.actorId, Revision(0), definition.login, definition.tokenPolicy))
    try {
        val regenerated = LocalEmployeeEnrollmentDefinition.capture(original.requestId, definition,
            requested.credentialWasProvided, policy, configuration, requested.admissions).copyWireBytes()
        val retained = requested.copyWireBytes()
        try { require(regenerated.contentEquals(retained)) } finally { regenerated.fill(0); retained.fill(0) }
    } finally { policy.fill(0); configuration.fill(0) }
}

private fun creationImages(original: LocalDelegatedEmployeeCreationV1,
    creator: RuntimeCommandAuthorizationSnapshotV1, at: MonotonicMoment,
    accountDigest: PayloadDigest): List<RuntimeEmployeeCreationImageV1> {
    val requested = original.copyRequestedDefinition()
    val definition = requested.definition
    val actor = original.actorId
    fun image(key: ProtectedLocalRecordKey, plaintext: ByteArray): RuntimeEmployeeCreationImageV1 {
        val table = RuntimeHistoryTableV2.entries.single { it.protectedTable == key.table }
        return RuntimeEmployeeCreationImageV1(table.tag, key.recordId,
            (key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey, creationDigest(plaintext))
    }
    fun throttle(dimension: ThrottleDimension): RuntimeEmployeeCreationImageV1 {
        val key = ThrottleKey(original.scope, actor, dimension)
        return image(ProtectedLocalRecordKey.Throttle(key), ProtectedLocalSecurityCodec.encodeThrottle(
            PersistentThrottleState(key, Revision(0), 0, at, 0, null)))
    }
    val configuration = LocalAuthConfiguration(original.scope, actor, Revision(0), definition.login, definition.tokenPolicy)
    require(configuration.tokens.canStartAt(at))
    val event = LocalAuthEvent(original.scope, original.requestId, LocalAuthEventKind.ACCOUNT_CREATED,
        actor, creator.actorId, creator.sessionId, Revision(0), at, original.contentDigest)
    return listOf(RuntimeEmployeeCreationImageV1(RuntimeHistoryTableV2.ACCOUNT.tag, actor, null, accountDigest),
        image(ProtectedLocalRecordKey.EffectivePolicy(original.scope, actor), LocalEffectivePolicyCodec.encodePolicy(definition.policy)),
        image(ProtectedLocalRecordKey.AuthConfiguration(original.scope, actor), LocalAuthenticationCodec.encodeConfiguration(configuration)),
        throttle(ThrottleDimension.Account)) + requested.admissions.sortedBy { it.deviceId.value }.map {
            throttle(ThrottleDimension.Device(it.deviceId))
        } + image(ProtectedLocalRecordKey.AuthEvent(original.scope, original.requestId), LocalAuthenticationCodec.encodeEvent(event))
}

private fun creationDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
private fun creationHex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
