package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** The declared target BEFORE is separate from the original cohost operator's dependencies.
 * Neither this commitment nor a live target row establishes a native admission origin. */
@Serializable
internal data class RuntimeDeviceRevocationDependenciesV2(
    val operator: RuntimeCommandAuthorizationDependenciesV1,
    val admissionBeforeDigest: PayloadDigest,
    val accountCensusDigest: PayloadDigest,
    val accountCount: Int,
    val affectedAccountCount: Int,
) {
    override fun toString(): String = "RuntimeDeviceRevocationDependenciesV2([REDACTED])"
}

/** A distinct admitted device's first withdrawal by a current original-cohost administrator.
 * Target BEFORE is a declaration: replay must join its earlier genuinely consumed native
 * admission, actual EVENTS/LINEAGE and whole current census before removing any history hold.
 * Actual protected images are N+2, including N=0; V1 self-cohost bytes/predicates stay separate. */
@Serializable
internal data class RuntimeDeviceRevocationEvidenceV2(
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
            command.expectedRevision == Revision(0) && command.targetId != authorization.deviceId &&
            authorization.admissionRevision == Revision(0) && authorization.access.admissionRevisionAtIssue == Revision(0) &&
            requirement.value() == deviceRevocationRequirementV1(authorization.scope, authorization.actorId, command))
        val access = authorization.access
        require(at.bootId == access.observedAt.bootId && at.elapsedMillis >= access.observedAt.elapsedMillis &&
            at.elapsedMillis < access.expiresAtElapsedMillis)
        require(accounts.size in 1..MAX_ACCOUNTS && accounts.map { it.actorId.value } ==
            accounts.map { it.actorId.value }.distinct().sorted())
        accounts.forEach { it.validate() }
        val operator = accounts.single { it.actorId == authorization.actorId }
        // The cohost operator can also retain other ACTIVE sessions on the target. Do not
        // assume its whole account is unaffected or require it to supply a target session.
        require(operator.enabled && operator.credentialPresent &&
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

    override fun toString(): String = "RuntimeDeviceRevocationEvidenceV2([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-admitted-device-revocation-evidence/v2"
        const val MAX_ACCOUNTS: Int = 4096
        const val MIN_IMAGES: Int = 2
        const val MAX_IMAGES: Int = MAX_ACCOUNTS + 2
    }
}

/** Memory-only fresh invocation on the actual authorized writer. The full independent
 * account census and predicted AFTERs are not sealing evidence or native target authority.
 * No credential, verifier, bearer, live permission or native completion is retained here. */
internal class RuntimeDeviceRevocationCaptureV2 private constructor(
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
    val operatorDeviceId: DomainId get() = authorization.deviceId
    val targetDeviceId: DomainId get() = command.targetId
    val operatorAdmissionBeforeDigest: PayloadDigest get() = authorization.dependencies.admission
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(scope, actorId, command)
    val expectedWrites: Int get() = expectedImages.size
    val accountCount: Int get() = expectedAccounts.size
    val images = mutableListOf<RuntimeDeviceRevocationImageV1>()

    fun expectedAccount(actor: DomainId): PayloadDigest? = expectedAccounts[actor]

    /** Borrows only the actual participating protected seal's binding and plaintext digest. */
    fun captureImage(binding: ProtectedLocalBinding, commitment: PayloadDigest) {
        require(binding.key.scope == scope && images.size < expectedImages.size)
        val image = RuntimeDeviceRevocationImageV1.capture(binding, commitment)
        require(image == expectedImages[images.size])
        images += image
    }

    private fun value(actual: List<RuntimeDeviceRevocationImageV1>): RuntimeDeviceRevocationEvidenceV2 =
        RuntimeDeviceRevocationEvidenceV2(command = command, requirement = requirement, authorization = authorization,
            at = at, admissionBeforeDigest = admissionBeforeDigest, accounts = accounts, images = actual).also { it.validate() }

    fun evidence(): RuntimeDeviceRevocationEvidenceV2 {
        require(images == expectedImages)
        return value(images.toList()).also(RuntimeDeviceRevocationEvidenceCodecV2::requireCapsuleCapacity)
    }

    override fun toString(): String = "RuntimeDeviceRevocationCaptureV2([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts, permitted: AuthorizedAction,
            command: LocalAdministrationCommand, observed: RoomLocalAuthorizationObservation, event: LocalAuthEvent,
            before: LocalDeviceAdmission, accounts: List<RuntimeDeviceRevocationAccountV1>,
            expectedImages: List<RuntimeDeviceRevocationImageV1>, expectedAccounts: Map<DomainId, PayloadDigest>): RuntimeDeviceRevocationCaptureV2 {
            val requirement = deviceRevocationRequirementV1(principal.scope, principal.actorId, command)
            val snapshot = RuntimeCommandAuthorizationSnapshotV1.capture(principal, facts, observed)
            require(permitted.isBoundTo(requirement, principal.actorId) && permitted.approvalId == null &&
                permitted.principalKind == principal.kind && permitted.authenticationId == principal.sessionId &&
                permitted.generation == principal.generation && permitted.policyRevision == facts.policy.revision &&
                before.scope == principal.scope && before.deviceId == command.targetId && before.deviceId != principal.deviceId &&
                before.admitted && before.revision == Revision(0) &&
                event.scope == principal.scope && event.eventId == command.requestId && event.kind == LocalAuthEventKind.DEVICE_REVOKED &&
                event.targetId == command.targetId && event.operatorId == principal.actorId && event.sessionId == principal.sessionId &&
                event.accountRevision == null && event.at == observed.moment && event.contentDigest ==
                    localAdministrationRequestDigest(principal.scope, principal.actorId, command) &&
                expectedAccounts.keys == accounts.map { it.actorId }.toSet())
            val capture = RuntimeDeviceRevocationCaptureV2(command, RuntimeCommandRequirementV1.capture(requirement), snapshot, event.at,
                deviceRevocationDigestV1(ProtectedLocalSecurityCodec.encodeAdmission(before)), accounts.toList(),
                expectedImages.toList(), expectedAccounts.toMap())
            // Capacity predicts this one exact invocation before effects, without minting
            // any actual image, native admission origin, receipt or final decision.
            RuntimeDeviceRevocationEvidenceCodecV2.requireCapsuleCapacity(capture.value(capture.expectedImages))
            return capture
        }
    }
}

internal fun deviceRevocationAuditImageV2(value: RuntimeDeviceRevocationEvidenceV2): RuntimeDeviceRevocationImageV1 =
    RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.AuthEvent(value.authorization.scope, value.command.requestId), Revision(0),
        LocalAuthenticationCodec.encodeEvent(LocalAuthEvent(value.authorization.scope, value.command.requestId, LocalAuthEventKind.DEVICE_REVOKED,
            value.command.targetId, value.authorization.actorId, value.authorization.sessionId, null, value.at, value.semanticDigest)))

/** Separate strict profile. V1 is neither decoded here nor broadened to read V2. */
internal object RuntimeDeviceRevocationEvidenceCodecV2 {
    private const val MAX_BYTES: Int = RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeDeviceRevocationEvidenceV2.PROFILE + "\",").encodeToByteArray()

    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }

    fun encode(value: RuntimeDeviceRevocationEvidenceV2): ByteArray = guarded {
        value.validate()
        bounded(json.encodeToString(RuntimeDeviceRevocationEvidenceV2.serializer(), value).encodeToByteArray(throwOnInvalidSequence = true))
    }

    fun decode(bytes: ByteArray): RuntimeDeviceRevocationEvidenceV2 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeDeviceRevocationEvidenceV2.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        requireCapsuleCapacity(value)
        value
    }

    fun dependencies(value: RuntimeDeviceRevocationEvidenceV2): ByteArray = guarded {
        value.validate()
        val census = bounded(json.encodeToString(ListSerializer(RuntimeDeviceRevocationAccountV1.serializer()), value.accounts)
            .encodeToByteArray(throwOnInvalidSequence = true))
        val digest = try { sha256(census) } finally { census.fill(0) }
        bounded(json.encodeToString(RuntimeDeviceRevocationDependenciesV2.serializer(), RuntimeDeviceRevocationDependenciesV2(
            value.authorization.dependencies, value.admissionBeforeDigest, digest, value.accounts.size, value.affectedAccounts.size))
            .encodeToByteArray(throwOnInvalidSequence = true))
    }

    fun requireCapsuleCapacity(value: RuntimeDeviceRevocationEvidenceV2): Unit = guarded {
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
