package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A distinct image vocabulary: admission/account UPDATEs and one original audit INSERT.
 * The old authentication/session serializers and their native table selection do not change.
 * Only actual protected sealing fills capture images; predictions are compared separately. */
@Serializable
internal data class RuntimeDeviceRevocationImageV1(
    val tableTag: Int,
    val recordId: DomainId,
    val revision: Revision,
    val plaintextDigest: PayloadDigest,
) {
    val table: RuntimeHistoryTableV2 get() = RuntimeHistoryTableV2.fromTag(tableTag)
    val mutation: RuntimeHistoryMutationKindV2 get() = if (table == RuntimeHistoryTableV2.AUTH_EVENT)
        RuntimeHistoryMutationKindV2.INSERT else RuntimeHistoryMutationKindV2.UPDATE

    fun validate() {
        require(table in setOf(RuntimeHistoryTableV2.ADMISSION, RuntimeHistoryTableV2.ACCOUNT, RuntimeHistoryTableV2.AUTH_EVENT))
        if (table == RuntimeHistoryTableV2.ADMISSION) require(revision == Revision(1))
        if (table == RuntimeHistoryTableV2.AUTH_EVENT) require(revision == Revision(0))
    }
    fun key(scope: BranchScope): ByteArray {
        validate()
        return runtimeSecurityKey(table, listOf(scope.storageKey(), recordId.value))
    }
    fun matches(binding: ProtectedLocalBinding): Boolean = revision == binding.revision &&
        recordId == binding.key.recordId && table.protectedTable == binding.key.table
    override fun toString(): String = "RuntimeDeviceRevocationImageV1([REDACTED])"

    companion object {
        fun capture(binding: ProtectedLocalBinding, digest: PayloadDigest): RuntimeDeviceRevocationImageV1 {
            val table = RuntimeHistoryTableV2.entries.single { it.protectedTable == binding.key.table }
            return RuntimeDeviceRevocationImageV1(table.tag, binding.key.recordId, binding.revision, digest).also { it.validate() }
        }
        fun capture(key: ProtectedLocalRecordKey, revision: Revision, bytes: ByteArray): RuntimeDeviceRevocationImageV1 =
            try { capture(ProtectedLocalBinding(key, revision), sha256(bytes)) } finally { bytes.fill(0) }
    }
}

/** Complete sorted account census, including unaffected and credential-free accounts.
 * No password/verifier, bearer, policy grants or session credential is retained here.
 * Affected flags/counts are checked against EACH actor's genuine chronological replay.
 * The BEFORE commitment covers the complete canonical protected account, not these fields. */
@Serializable
internal data class RuntimeDeviceRevocationAccountV1(
    val actorId: DomainId,
    val accountRevision: Revision,
    val accountGeneration: SessionGeneration,
    val credentialRevision: Revision,
    val credentialPresent: Boolean,
    val enabled: Boolean,
    val retainedSessions: Int,
    val selectedActiveSessions: Int,
    val accountBeforeDigest: PayloadDigest,
) {
    val affected: Boolean get() = selectedActiveSessions != 0
    val afterRevision: Revision get() = if (!affected) accountRevision else if (
        accountRevision.value >= Long.MAX_VALUE - 1L || accountGeneration.value >= Long.MAX_VALUE - 1L)
        Revision(Long.MAX_VALUE) else accountRevision.next()

    fun validate() {
        require(retainedSessions in 0..256 && selectedActiveSessions in 0..retainedSessions)
        if (affected) require(accountRevision.value < Long.MAX_VALUE && accountGeneration.value < Long.MAX_VALUE)
    }
    override fun toString(): String = "RuntimeDeviceRevocationAccountV1([REDACTED])"

    companion object {
        fun capture(account: ProtectedLocalAccount, deviceId: DomainId): RuntimeDeviceRevocationAccountV1 {
            val state = account.state
            return RuntimeDeviceRevocationAccountV1(state.actorId, state.revision, state.generation,
                account.credentialRevision, account.credential != null, state.enabled, state.sessions.size,
                state.sessions.count { it.deviceId == deviceId && it.status == SessionStatus.ACTIVE },
                deviceRevocationDigestV1(ProtectedLocalSecurityCodec.encodeAccount(account))).also { it.validate() }
        }
    }
}

@Serializable
internal data class RuntimeDeviceRevocationDependenciesV1(
    val operator: RuntimeCommandAuthorizationDependenciesV1,
    val admissionBeforeDigest: PayloadDigest,
    val accountCensusDigest: PayloadDigest,
    val accountCount: Int,
    val affectedAccountCount: Int,
) {
    override fun toString(): String = "RuntimeDeviceRevocationDependenciesV1([REDACTED])"
}

/** Exactly the native original cohost's first revocation, not enrollment/re-admission or
 * multi-device startup. The operator's original event/final authority is proved BEFORE
 * any account/admission prediction advances. Revoked current cohost admission stays HELD.
 * Native-owned actual images are N+2: admission, affected accounts in actor order, audit. */
@Serializable
internal data class RuntimeDeviceRevocationEvidenceV1(
    val schema: String = PROFILE,
    val command: LocalAdministrationCommand,
    val requirement: RuntimeCommandRequirementV1,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val at: MonotonicMoment,
    val admissionBeforeDigest: PayloadDigest,
    val accounts: List<RuntimeDeviceRevocationAccountV1>,
    val images: List<RuntimeDeviceRevocationImageV1>,
) {
    val purpose: RuntimeWritePurposeV2 get() = RuntimeWritePurposeV2.DEVICE_ADMINISTRATION
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(authorization.scope, authorization.actorId, command)
    val affectedAccounts: List<RuntimeDeviceRevocationAccountV1> get() = accounts.filter { it.affected }

    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        validate()
        return NativeHistoryProtectedFinalDeclaration(command.requestId, semanticDigest, authorization.actorId,
            authorization.sessionId, authorization.access.issuerContextId, authorization.access.issuedAt,
            at, authorization.access.expiresAtElapsedMillis)
    }
    fun validate() {
        require(schema == PROFILE)
        authorization.validate()
        require(command.action == LocalAdministrationAction.REVOKE_DEVICE && command.sessionId == null &&
            command.expectedRevision == Revision(0) && command.targetId == authorization.deviceId &&
            authorization.admissionRevision == Revision(0) && authorization.access.admissionRevisionAtIssue == Revision(0) &&
            requirement.value() == deviceRevocationRequirementV1(authorization.scope, authorization.actorId, command) &&
            admissionBeforeDigest == authorization.dependencies.admission)
        val access = authorization.access
        require(at.bootId == access.observedAt.bootId && at.elapsedMillis >= access.observedAt.elapsedMillis &&
            at.elapsedMillis < access.expiresAtElapsedMillis)
        require(accounts.size in 1..MAX_ACCOUNTS && accounts.map { it.actorId.value } ==
            accounts.map { it.actorId.value }.distinct().sorted())
        accounts.forEach { it.validate() }
        val operator = accounts.single { it.actorId == authorization.actorId }
        require(operator.affected && operator.enabled && operator.credentialPresent &&
            operator.accountRevision == authorization.accountRevision && operator.accountGeneration.value >= authorization.generation.value &&
            operator.credentialRevision == authorization.credentialRevision && operator.accountBeforeDigest == authorization.dependencies.account)
        val affected = affectedAccounts
        require(images.size == affected.size + 2 && images.size in MIN_IMAGES..MAX_IMAGES)
        images.forEach { it.validate() }
        require(images.first().table == RuntimeHistoryTableV2.ADMISSION && images.first().recordId == command.targetId &&
            images.last().table == RuntimeHistoryTableV2.AUTH_EVENT && images.last().recordId == command.requestId)
        for ((index, account) in affected.withIndex()) require(images[index + 1].table == RuntimeHistoryTableV2.ACCOUNT &&
            images[index + 1].recordId == account.actorId && images[index + 1].revision == account.afterRevision)
    }
    override fun toString(): String = "RuntimeDeviceRevocationEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-original-cohost-device-revocation-evidence/v1"
        const val MAX_ACCOUNTS: Int = 4096
        const val MIN_IMAGES: Int = 3
        const val MAX_IMAGES: Int = MAX_ACCOUNTS + 2
    }
}

/** Memory-only fresh invocation. Its independent full census and predicted AFTER hashes
 * are not actual image evidence; only the same loan's real protected sealing supplies that.
 * Complete encoded capacity is checked before effects AND against the actual evidence. */
internal class RuntimeDeviceRevocationCapture private constructor(
    private val command: LocalAdministrationCommand,
    private val requirement: RuntimeCommandRequirementV1,
    private val authorization: RuntimeCommandAuthorizationSnapshotV1,
    private val at: MonotonicMoment,
    private val admissionBeforeDigest: PayloadDigest,
    private val accounts: List<RuntimeDeviceRevocationAccountV1>,
    private val expectedImages: List<RuntimeDeviceRevocationImageV1>,
    private val expectedAccounts: Map<DomainId, PayloadDigest>,
) {
    val scope: BranchScope get() = authorization.scope
    val actorId: DomainId get() = authorization.actorId
    val requestId: DomainId get() = command.requestId
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(scope, actorId, command)
    val expectedWrites: Int get() = expectedImages.size
    val images = mutableListOf<RuntimeDeviceRevocationImageV1>()
    fun expectedAccount(actor: DomainId): PayloadDigest? = expectedAccounts[actor]
    val accountCount: Int get() = expectedAccounts.size
    private fun value(actual: List<RuntimeDeviceRevocationImageV1>): RuntimeDeviceRevocationEvidenceV1 =
        RuntimeDeviceRevocationEvidenceV1(command = command, requirement = requirement, authorization = authorization,
            at = at, admissionBeforeDigest = admissionBeforeDigest, accounts = accounts, images = actual).also { it.validate() }
    fun evidence(): RuntimeDeviceRevocationEvidenceV1 {
        require(images == expectedImages)
        return value(images.toList()).also(RuntimeDeviceRevocationEvidenceCodec::requireCapsuleCapacity)
    }
    override fun toString(): String = "RuntimeDeviceRevocationCapture([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts, permitted: AuthorizedAction,
            command: LocalAdministrationCommand, observed: RoomLocalAuthorizationObservation, event: LocalAuthEvent,
            before: LocalDeviceAdmission, accounts: List<RuntimeDeviceRevocationAccountV1>,
            expectedImages: List<RuntimeDeviceRevocationImageV1>, expectedAccounts: Map<DomainId, PayloadDigest>): RuntimeDeviceRevocationCapture {
            val requirement = deviceRevocationRequirementV1(principal.scope, principal.actorId, command)
            val snapshot = RuntimeCommandAuthorizationSnapshotV1.capture(principal, facts, observed)
            require(permitted.isBoundTo(requirement, principal.actorId) && permitted.approvalId == null &&
                permitted.principalKind == principal.kind && permitted.authenticationId == principal.sessionId &&
                permitted.generation == principal.generation && permitted.policyRevision == facts.policy.revision &&
                before.scope == principal.scope && before.deviceId == principal.deviceId && before.admitted && before.revision == Revision(0) &&
                event.scope == principal.scope && event.eventId == command.requestId && event.kind == LocalAuthEventKind.DEVICE_REVOKED &&
                event.targetId == command.targetId && event.operatorId == principal.actorId && event.sessionId == principal.sessionId &&
                event.accountRevision == null && event.at == observed.moment && event.contentDigest ==
                    localAdministrationRequestDigest(principal.scope, principal.actorId, command) &&
                expectedAccounts.keys == accounts.map { it.actorId }.toSet())
            val capture = RuntimeDeviceRevocationCapture(command, RuntimeCommandRequirementV1.capture(requirement), snapshot, event.at,
                deviceRevocationDigestV1(ProtectedLocalSecurityCodec.encodeAdmission(before)), accounts.toList(),
                expectedImages.toList(), expectedAccounts.toMap())
            // Predictions only: no image, receipt or native final decision is minted here.
            RuntimeDeviceRevocationEvidenceCodec.requireCapsuleCapacity(capture.value(capture.expectedImages))
            return capture
        }
    }
}

/** Preserve the actual whole-account reducer and terminal lockdown. Do not filter to
 * interactive sessions or advance an unaffected account's counters/credentials. */
internal fun deviceRevocationAccountAfterV1(account: ProtectedLocalAccount, deviceId: DomainId): ProtectedLocalAccount {
    val active = account.state.sessions.filter { it.deviceId == deviceId && it.status == SessionStatus.ACTIVE }
        .map { it.sessionId }.toSet()
    if (active.isEmpty()) return account
    val next = (LocalSessions.revokeSelected(account.state, active) as? SessionTransition.Applied)?.state
        ?: throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    return ProtectedLocalAccount(next, account.credentialRevision, account.credential)
}
internal fun deviceRevocationAdmissionAfterV1(before: LocalDeviceAdmission): LocalDeviceAdmission {
    require(before.admitted && before.revision == Revision(0))
    return LocalDeviceAdmission(before.scope, before.deviceId, before.revision.next(), false,
        before.publicKeyIdentifier, before.publicFingerprint, before.capabilities)
}
internal fun deviceRevocationAuditImageV1(value: RuntimeDeviceRevocationEvidenceV1): RuntimeDeviceRevocationImageV1 =
    RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.AuthEvent(value.authorization.scope, value.command.requestId), Revision(0),
        LocalAuthenticationCodec.encodeEvent(LocalAuthEvent(value.authorization.scope, value.command.requestId, LocalAuthEventKind.DEVICE_REVOKED,
            value.command.targetId, value.authorization.actorId, value.authorization.sessionId, null, value.at, value.semanticDigest)))
internal fun deviceRevocationRequirementV1(scope: BranchScope, operator: DomainId,
    command: LocalAdministrationCommand): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER), scope,
    ActionBinding(command.targetId, command.expectedRevision, localAdministrationRequestDigest(scope, operator, command),
        OperationId(command.requestId.value), "local-device-revocation"))
internal fun deviceRevocationDigestV1(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }

internal object RuntimeDeviceRevocationEvidenceCodec {
    private const val MAX_BYTES: Int = RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeDeviceRevocationEvidenceV1.PROFILE + "\",").encodeToByteArray()
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeDeviceRevocationEvidenceV1): ByteArray = guarded {
        value.validate()
        bounded(json.encodeToString(RuntimeDeviceRevocationEvidenceV1.serializer(), value).encodeToByteArray(throwOnInvalidSequence = true))
    }
    fun decode(bytes: ByteArray): RuntimeDeviceRevocationEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeDeviceRevocationEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        requireCapsuleCapacity(value)
        value
    }
    fun dependencies(value: RuntimeDeviceRevocationEvidenceV1): ByteArray = guarded {
        value.validate()
        val census = bounded(json.encodeToString(ListSerializer(RuntimeDeviceRevocationAccountV1.serializer()), value.accounts)
            .encodeToByteArray(throwOnInvalidSequence = true))
        val digest = try { sha256(census) } finally { census.fill(0) }
        bounded(json.encodeToString(RuntimeDeviceRevocationDependenciesV1.serializer(), RuntimeDeviceRevocationDependenciesV1(
            value.authorization.dependencies, value.admissionBeforeDigest, digest, value.accounts.size, value.affectedAccounts.size))
            .encodeToByteArray(throwOnInvalidSequence = true))
    }
    fun requireCapsuleCapacity(value: RuntimeDeviceRevocationEvidenceV1): Unit = guarded {
        val dependencies = dependencies(value)
        try {
            val facts = encode(value)
            try {
                val intent = RuntimeHistoryCodecV2.originalIntentBytes(NativeHistoryOriginalIntentV2(value.purpose.tag, value.command.requestId,
                    value.semanticDigest, null, value.authorization.actorId, sha256(dependencies), sha256(facts), emptyList()))
                try { historyCapacityV2(intent.size.toLong() + dependencies.size.toLong() + facts.size.toLong() <= MAX_BYTES.toLong()) }
                finally { intent.fill(0) }
            } finally { facts.fill(0) }
        } finally { dependencies.fill(0) }
    }
    private fun bounded(bytes: ByteArray): ByteArray {
        if (bytes.size !in 1..MAX_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        return bytes
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}
