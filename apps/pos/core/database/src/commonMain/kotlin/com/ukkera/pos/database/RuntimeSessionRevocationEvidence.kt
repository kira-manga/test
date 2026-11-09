package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Closed retained DATA for the interactive operator revoking exactly its own session.
 * No password, bearer, policy grants or accepting callback is retained. The authorization
 * observation is the actual successful protected preflight, NOT a native final-time witness.
 * A separate native decision must bind this full intent and its original access window.
 * Original authority, every reducer image and complete native lineage still require joins. */
@Serializable
internal data class RuntimeSessionRevocationEvidenceV1(
    val schema: String = PROFILE,
    val command: LocalAdministrationCommand,
    val requirement: RuntimeCommandRequirementV1,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val at: MonotonicMoment,
    val images: List<RuntimeAuthenticationImageV1>,
) {
    val purpose: RuntimeWritePurposeV2 get() = RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(authorization.scope, authorization.actorId, command)

    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        validate()
        return NativeHistoryProtectedFinalDeclaration(command.requestId, semanticDigest, authorization.actorId,
            authorization.sessionId, authorization.access.issuerContextId, authorization.access.issuedAt,
            at, authorization.access.expiresAtElapsedMillis)
    }

    fun validate() {
        require(schema == PROFILE)
        authorization.validate()
        require(command.action == LocalAdministrationAction.REVOKE_SESSION &&
            command.targetId == authorization.actorId && command.sessionId == authorization.sessionId &&
            command.expectedRevision == authorization.accountRevision &&
            authorization.accountRevision.value < Long.MAX_VALUE - 1L)
        require(requirement.value() == sessionRevocationRequirement(authorization.scope, authorization.actorId, command))
        val access = authorization.access
        require(at.bootId == access.observedAt.bootId && at.elapsedMillis >= access.observedAt.elapsedMillis &&
            at.elapsedMillis < access.expiresAtElapsedMillis)
        require(images.size == MAX_IMAGES)
        images.forEach { it.validate() }
        val account = images[0]
        val event = images[1]
        require(account.table == RuntimeHistoryTableV2.ACCOUNT && account.recordId == command.targetId &&
            account.dimension == null && account.revision == command.expectedRevision.next())
        require(event.table == RuntimeHistoryTableV2.AUTH_EVENT && event.recordId == command.requestId &&
            event.dimension == null && event.revision == Revision(0))
    }

    override fun toString(): String = "RuntimeSessionRevocationEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-self-session-revocation-evidence/v1"
        const val MAX_IMAGES: Int = 2
    }
}

/** Memory-only same-writer capture. Protected sealing, not a proposed outcome, fills images. */
internal class RuntimeSessionRevocationCapture private constructor(
    private val command: LocalAdministrationCommand,
    private val requirement: RuntimeCommandRequirementV1,
    private val authorization: RuntimeCommandAuthorizationSnapshotV1,
    private val at: MonotonicMoment,
) {
    val scope: BranchScope get() = authorization.scope
    val actorId: DomainId get() = authorization.actorId
    val requestId: DomainId get() = command.requestId
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(scope, actorId, command)
    val images = mutableListOf<RuntimeAuthenticationImageV1>()

    fun evidence(): RuntimeSessionRevocationEvidenceV1 = RuntimeSessionRevocationEvidenceV1(
        command = command, requirement = requirement, authorization = authorization, at = at,
        images = images.toList()).also { it.validate() }

    override fun toString(): String = "RuntimeSessionRevocationCapture([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts,
            permitted: AuthorizedAction, command: LocalAdministrationCommand,
            observed: RoomLocalAuthorizationObservation, event: LocalAuthEvent): RuntimeSessionRevocationCapture {
            val requirement = sessionRevocationRequirement(principal.scope, principal.actorId, command)
            val snapshot = RuntimeCommandAuthorizationSnapshotV1.capture(principal, facts, observed)
            require(command.action == LocalAdministrationAction.REVOKE_SESSION &&
                command.targetId == principal.actorId && command.sessionId == principal.sessionId &&
                command.expectedRevision == facts.account.revision && facts.account.revision.value < Long.MAX_VALUE - 1L &&
                facts.account.generation.value < Long.MAX_VALUE - 1L &&
                permitted.isBoundTo(requirement, principal.actorId) && permitted.approvalId == null &&
                permitted.principalKind == principal.kind && permitted.authenticationId == principal.sessionId &&
                permitted.generation == principal.generation && permitted.policyRevision == facts.policy.revision)
            require(event.scope == principal.scope && event.eventId == command.requestId &&
                event.kind == LocalAuthEventKind.SESSION_REVOKED && event.targetId == principal.actorId &&
                event.operatorId == principal.actorId && event.sessionId == principal.sessionId &&
                event.accountRevision == command.expectedRevision.next() &&
                event.contentDigest == localAdministrationRequestDigest(principal.scope, principal.actorId, command))
            return RuntimeSessionRevocationCapture(command, RuntimeCommandRequirementV1.capture(requirement), snapshot, event.at)
        }
    }
}

internal fun sessionRevocationRequirement(scope: BranchScope, operatorId: DomainId,
    command: LocalAdministrationCommand): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER), scope,
    ActionBinding(command.targetId, command.expectedRevision, localAdministrationRequestDigest(scope, operatorId, command),
        OperationId(command.requestId.value), "local-credential-administration"))

internal object RuntimeSessionRevocationEvidenceCodec {
    private const val MAX_BYTES: Int = 16 * 1024
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeSessionRevocationEvidenceV1.PROFILE + "\",").encodeToByteArray()

    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }

    fun encode(value: RuntimeSessionRevocationEvidenceV1): ByteArray = guarded {
        value.validate()
        val bytes = json.encodeToString(RuntimeSessionRevocationEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..MAX_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        bytes
    }

    fun decode(bytes: ByteArray): RuntimeSessionRevocationEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeSessionRevocationEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }

    fun dependencies(value: RuntimeSessionRevocationEvidenceV1): ByteArray = json
        .encodeToString(RuntimeCommandAuthorizationDependenciesV1.serializer(), value.authorization.dependencies).encodeToByteArray()

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}
