package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Canonical target BEFORE from the same real writer. No verifier or bearer material is
 * retained. The target's complete original/enrolled reducer and native predecessor plaintext
 * must independently join this digest; this declaration is not account authority. */
@Serializable
internal data class RuntimeAccountActivationTargetV1(
    val accountBeforeDigest: PayloadDigest,
    val credentialRevision: Revision,
    val accountGeneration: SessionGeneration,
    val policyDigest: PayloadDigest,
    val configurationDigest: PayloadDigest,
) {
    fun validate(command: LocalAdministrationCommand, authorization: RuntimeCommandAuthorizationSnapshotV1) {
        require(command.action == LocalAdministrationAction.ACTIVATE_ACCOUNT && command.sessionId == null &&
            command.expectedCredentialRevision == credentialRevision && command.expectedRevision.value < Long.MAX_VALUE - 1L &&
            credentialRevision.value < Long.MAX_VALUE && accountGeneration.value in 0L until Long.MAX_VALUE - 1L)
        checkNotNull(command.activationRevisions)
        require(command.targetId != authorization.actorId && command.expectedCredentialRevision == credentialRevision)
    }

    override fun toString(): String = "RuntimeAccountActivationTargetV1([REDACTED])"
}

@Serializable
internal data class RuntimeAccountActivationDependenciesV1(
    val operator: RuntimeCommandAuthorizationDependenciesV1,
    val targetAccount: PayloadDigest,
    val targetPolicy: PayloadDigest,
    val targetConfiguration: PayloadDigest,
) {
    override fun toString(): String = "RuntimeAccountActivationDependenciesV1([REDACTED])"
}

/** A distinct retained activation format. Old self/retained-session readers remain exact.
 * The explicit activation reducer establishes the first credential and enables ACCOUNT;
 * native final authorization, both real images and full target history are mandatory. */
@Serializable
internal data class RuntimeAccountActivationEvidenceV1(
    val schema: String = PROFILE,
    val command: LocalAdministrationCommand,
    val requirement: RuntimeCommandRequirementV1,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val target: RuntimeAccountActivationTargetV1,
    val at: MonotonicMoment,
    val images: List<RuntimeAuthenticationImageV1>,
) {
    val purpose: RuntimeWritePurposeV2 get() = RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(authorization.scope, authorization.actorId, command)
    val dependencies: RuntimeAccountActivationDependenciesV1 get() = RuntimeAccountActivationDependenciesV1(
        authorization.dependencies, target.accountBeforeDigest, target.policyDigest, target.configurationDigest)

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
        // This existing inert helper is the identical LOCAL_ACCOUNT_ADMINISTRATION binding
        // used by the actual activation writer, not a new permission or granting service.
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

    override fun toString(): String = "RuntimeAccountActivationEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-account-activation-evidence/v1"
        const val MAX_IMAGES: Int = 2
    }
}

/** Memory-only capture for the existing typed writer. Unsupported counters, existing credentials,
 * service or other-device histories keep their held path; no target seed is invented. */
internal class RuntimeAccountActivationCapture private constructor(
    private val command: LocalAdministrationCommand,
    private val requirement: RuntimeCommandRequirementV1,
    private val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val target: RuntimeAccountActivationTargetV1,
    private val at: MonotonicMoment,
) {
    val scope: BranchScope get() = authorization.scope
    val actorId: DomainId get() = authorization.actorId
    val requestId: DomainId get() = command.requestId
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(scope, actorId, command)
    val images = mutableListOf<RuntimeAuthenticationImageV1>()

    fun evidence(): RuntimeAccountActivationEvidenceV1 = RuntimeAccountActivationEvidenceV1(
        command = command, requirement = requirement, authorization = authorization, target = target,
        at = at, images = images.toList()).also { it.validate() }

    override fun toString(): String = "RuntimeAccountActivationCapture([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts,
            permitted: AuthorizedAction, command: LocalAdministrationCommand,
            observed: RoomLocalAuthorizationObservation, event: LocalAuthEvent,
            targetBefore: ProtectedLocalAccount, policy: ActivatedLocalPolicy,
            configuration: LocalAuthConfiguration): RuntimeAccountActivationCapture? {
            val before = targetBefore.state
            val expected = command.activationRevisions ?: return null
            if (before.enabled || before.actorId == principal.actorId ||
                command.expectedCredentialRevision != targetBefore.credentialRevision || policy.scope != before.scope ||
                policy.actorId != before.actorId || policy.revision != expected.policy ||
                configuration.scope != before.scope || configuration.actorId != before.actorId ||
                configuration.revision != expected.configuration) return null
            if (command.action != LocalAdministrationAction.ACTIVATE_ACCOUNT || command.sessionId != null ||
                command.expectedCredentialRevision != targetBefore.credentialRevision || before.scope != principal.scope || before.actorId != command.targetId ||
                targetBefore.credential != null || targetBefore.credentialRevision.value >= Long.MAX_VALUE ||
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
                event.kind == LocalAuthEventKind.ACCOUNT_ACTIVATED && event.targetId == command.targetId &&
                event.operatorId == principal.actorId && event.sessionId == null &&
                event.accountRevision == command.expectedRevision.next() &&
                event.contentDigest == localAdministrationRequestDigest(principal.scope, principal.actorId, command))
            val target = RuntimeAccountActivationTargetV1(accountActivationDigest(ProtectedLocalSecurityCodec.encodeAccount(targetBefore)),
                targetBefore.credentialRevision, before.generation,
                accountActivationDigest(LocalEffectivePolicyCodec.encodePolicy(policy)),
                accountActivationDigest(LocalAuthenticationCodec.encodeConfiguration(configuration)))
            target.validate(command, snapshot)
            return RuntimeAccountActivationCapture(command, RuntimeCommandRequirementV1.capture(requirement), snapshot, target, event.at)
        }
    }
}

internal object RuntimeAccountActivationEvidenceCodec {
    private const val MAX_BYTES: Int = 16 * 1024
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeAccountActivationEvidenceV1.PROFILE + "\",").encodeToByteArray()
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeAccountActivationEvidenceV1): ByteArray = guarded {
        value.validate()
        val bytes = json.encodeToString(RuntimeAccountActivationEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..MAX_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        bytes
    }
    fun decode(bytes: ByteArray): RuntimeAccountActivationEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeAccountActivationEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }
    fun dependencies(value: RuntimeAccountActivationEvidenceV1): ByteArray = json
        .encodeToString(RuntimeAccountActivationDependenciesV1.serializer(), value.dependencies).encodeToByteArray()
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}

private fun accountActivationDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
