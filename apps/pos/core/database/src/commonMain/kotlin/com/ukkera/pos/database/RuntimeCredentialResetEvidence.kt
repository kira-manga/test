package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Canonical target BEFORE from the actual reset writer. Its own native-founded/enrolled
 * replay and independently opened predecessor must prove this digest. The credential may
 * genuinely be absent before reset; no password, salt, verifier or bearer is retained here. */
@Serializable
internal data class RuntimeCredentialResetTargetV1(
    val accountBeforeDigest: PayloadDigest,
    val credentialRevision: Revision,
    val accountGeneration: SessionGeneration,
) {
    fun validate(command: LocalAdministrationCommand, authorization: RuntimeCommandAuthorizationSnapshotV1) {
        require(command.action == LocalAdministrationAction.RESET_CREDENTIAL && command.sessionId == null &&
            command.expectedCredentialRevision == credentialRevision && command.expectedRevision.value < Long.MAX_VALUE - 1L &&
            credentialRevision.value < Long.MAX_VALUE && accountGeneration.value in 0L until Long.MAX_VALUE - 1L)
        if (command.targetId == authorization.actorId) require(command.expectedRevision == authorization.accountRevision &&
            credentialRevision == authorization.credentialRevision && accountBeforeDigest == authorization.dependencies.account)
    }

    override fun toString(): String = "RuntimeCredentialResetTargetV1([REDACTED])"
}

@Serializable
internal data class RuntimeCredentialResetDependenciesV1(
    val operator: RuntimeCommandAuthorizationDependenciesV1,
    val targetAccount: PayloadDigest,
) {
    override fun toString(): String = "RuntimeCredentialResetDependenciesV1([REDACTED])"
}

/** Distinct reset profile, never an extension of old self/retained/disable codecs. The
 * replacement credential stays inside the actual protected ACCOUNT image; replay consumes
 * it only after proving operator/native-final authority and reducing the target's own BEFORE.
 * Exactly ACCOUNT plus immutable CREDENTIAL_RESET audit are declared, never a new issuance. */
@Serializable
internal data class RuntimeCredentialResetEvidenceV1(
    val schema: String = PROFILE,
    val command: LocalAdministrationCommand,
    val requirement: RuntimeCommandRequirementV1,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val target: RuntimeCredentialResetTargetV1,
    val at: MonotonicMoment,
    val images: List<RuntimeAuthenticationImageV1>,
) {
    val purpose: RuntimeWritePurposeV2 get() = RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(authorization.scope, authorization.actorId, command)
    val dependencies: RuntimeCredentialResetDependenciesV1 get() = RuntimeCredentialResetDependenciesV1(
        authorization.dependencies, target.accountBeforeDigest)

    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        validate()
        return NativeHistoryProtectedFinalDeclaration(command.requestId, semanticDigest, authorization.actorId,
            authorization.sessionId, authorization.access.issuerContextId, authorization.access.issuedAt,
            at, authorization.access.expiresAtElapsedMillis)
    }

    fun validate() {
        require(schema == PROFILE)
        authorization.validate()
        target.validate(command, authorization)
        // Identical inert ADMINISTER binding to the actual typed writer; no new permission.
        require(requirement.value() == sessionRevocationRequirement(authorization.scope, authorization.actorId, command))
        val access = authorization.access
        require(at.bootId == access.observedAt.bootId && at.elapsedMillis >= access.observedAt.elapsedMillis &&
            at.elapsedMillis < access.expiresAtElapsedMillis)
        require(images.size == MAX_IMAGES)
        images.forEach { it.validate() }
        require(images[0].table == RuntimeHistoryTableV2.ACCOUNT && images[0].recordId == command.targetId &&
            images[0].dimension == null && images[0].revision == command.expectedRevision.next())
        require(images[1].table == RuntimeHistoryTableV2.AUTH_EVENT && images[1].recordId == command.requestId &&
            images[1].dimension == null && images[1].revision == Revision(0))
    }

    override fun toString(): String = "RuntimeCredentialResetEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-credential-reset-evidence/v1"
        const val MAX_IMAGES: Int = 2
    }
}

/** Memory-only actual typed invocation. Enabled credential-free targets are legitimate
 * only when their complete native enrollment prefix proves them; null credentials never
 * authorize an operator. Unsupported counters/other-device/service histories stay held. */
internal class RuntimeCredentialResetCapture private constructor(
    private val command: LocalAdministrationCommand,
    private val requirement: RuntimeCommandRequirementV1,
    private val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val target: RuntimeCredentialResetTargetV1,
    private val at: MonotonicMoment,
) {
    val scope: BranchScope get() = authorization.scope
    val actorId: DomainId get() = authorization.actorId
    val requestId: DomainId get() = command.requestId
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(scope, actorId, command)
    val images = mutableListOf<RuntimeAuthenticationImageV1>()

    fun evidence(): RuntimeCredentialResetEvidenceV1 = RuntimeCredentialResetEvidenceV1(
        command = command, requirement = requirement, authorization = authorization, target = target,
        at = at, images = images.toList()).also { it.validate() }

    override fun toString(): String = "RuntimeCredentialResetCapture([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts,
            permitted: AuthorizedAction, command: LocalAdministrationCommand,
            observed: RoomLocalAuthorizationObservation, event: LocalAuthEvent,
            targetBefore: ProtectedLocalAccount): RuntimeCredentialResetCapture? {
            val before = targetBefore.state
            if (command.action != LocalAdministrationAction.RESET_CREDENTIAL || command.sessionId != null ||
                command.expectedCredentialRevision != targetBefore.credentialRevision || !before.enabled ||
                before.scope != principal.scope || before.actorId != command.targetId ||
                targetBefore.credentialRevision.value >= Long.MAX_VALUE ||
                before.revision != command.expectedRevision || before.revision.value >= Long.MAX_VALUE - 1L ||
                before.generation.value >= Long.MAX_VALUE - 1L || before.sessions.any {
                    it.deviceId != principal.deviceId || it.authenticationPurpose != AuthenticationPurpose.INTERACTIVE
                }) return null
            val requirement = sessionRevocationRequirement(principal.scope, principal.actorId, command)
            val snapshot = RuntimeCommandAuthorizationSnapshotV1.capture(principal, facts, observed)
            require(permitted.isBoundTo(requirement, principal.actorId) && permitted.approvalId == null &&
                permitted.principalKind == principal.kind && permitted.authenticationId == principal.sessionId &&
                permitted.generation == principal.generation && permitted.policyRevision == facts.policy.revision)
            require(event.scope == principal.scope && event.eventId == command.requestId &&
                event.kind == LocalAuthEventKind.CREDENTIAL_RESET && event.targetId == command.targetId &&
                event.operatorId == principal.actorId && event.sessionId == null &&
                event.accountRevision == command.expectedRevision.next() &&
                event.contentDigest == localAdministrationRequestDigest(principal.scope, principal.actorId, command))
            val target = RuntimeCredentialResetTargetV1(credentialResetDigest(ProtectedLocalSecurityCodec.encodeAccount(targetBefore)),
                targetBefore.credentialRevision, before.generation)
            target.validate(command, snapshot)
            return RuntimeCredentialResetCapture(command, RuntimeCommandRequirementV1.capture(requirement), snapshot, target, event.at)
        }
    }
}

internal object RuntimeCredentialResetEvidenceCodec {
    private const val MAX_BYTES: Int = 16 * 1024
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeCredentialResetEvidenceV1.PROFILE + "\",").encodeToByteArray()
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeCredentialResetEvidenceV1): ByteArray = guarded {
        value.validate()
        val bytes = json.encodeToString(RuntimeCredentialResetEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..MAX_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        bytes
    }
    fun decode(bytes: ByteArray): RuntimeCredentialResetEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeCredentialResetEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }
    fun dependencies(value: RuntimeCredentialResetEvidenceV1): ByteArray = json
        .encodeToString(RuntimeCredentialResetDependenciesV1.serializer(), value.dependencies).encodeToByteArray()
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}

private fun credentialResetDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
