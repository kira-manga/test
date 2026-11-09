package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Only the actual operator's unchanged historical dependencies. Cancellation does not
 * observe, infer or change a target account/device/session; no target seed is retained. */
@Serializable
internal data class RuntimeAdministrationCancellationDependenciesV1(
    val operator: RuntimeCommandAuthorizationDependenciesV1,
) {
    override fun toString(): String = "RuntimeAdministrationCancellationDependenciesV1([REDACTED])"
}

/** Distinct one-audit no-effect profile. The original intent is fenced at its same immutable
 * auth-event key, never erased, repeated or reversed. Native operator/final authority and
 * complete actual image/lineage/current/count closure remain mandatory. No bearer is restored. */
@Serializable
internal data class RuntimeAdministrationCancellationEvidenceV1(
    val schema: String = PROFILE,
    val command: LocalAdministrationCommand,
    val requirement: RuntimeCommandRequirementV1,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val at: MonotonicMoment,
    val images: List<RuntimeAuthenticationImageV1>,
) {
    val purpose: RuntimeWritePurposeV2 get() = if (command.action == LocalAdministrationAction.REVOKE_DEVICE)
        RuntimeWritePurposeV2.DEVICE_ADMINISTRATION else RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(authorization.scope, authorization.actorId, command)
    val dependencies: RuntimeAdministrationCancellationDependenciesV1 get() = RuntimeAdministrationCancellationDependenciesV1(
        authorization.dependencies)

    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        validate()
        return NativeHistoryProtectedFinalDeclaration(command.requestId, semanticDigest, authorization.actorId,
            authorization.sessionId, authorization.access.issuerContextId, authorization.access.issuedAt,
            at, authorization.access.expiresAtElapsedMillis)
    }

    fun validate() {
        require(schema == PROFILE)
        authorization.validate()
        // Preserve the actual cancel/lookup ADMINISTER action and original target binding;
        // this is not the legacy device-effect verb or evidence of target existence.
        require(requirement.value() == administrationCancellationRequirement(authorization.scope, authorization.actorId, command))
        val access = authorization.access
        require(at.bootId == access.observedAt.bootId && at.elapsedMillis >= access.observedAt.elapsedMillis &&
            at.elapsedMillis < access.expiresAtElapsedMillis)
        require(images.size == MAX_IMAGES)
        val image = images.single().also { it.validate() }
        require(image.table == RuntimeHistoryTableV2.AUTH_EVENT && image.recordId == command.requestId &&
            image.dimension == null && image.revision == Revision(0))
        // No expected target revision/generation/credential counter is advanced or tested.
    }

    override fun toString(): String = "RuntimeAdministrationCancellationEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-administration-cancellation-evidence/v1"
        const val MAX_IMAGES: Int = 1
    }
}

/** Only the explicit fresh absent-prior cancellation invocation supplies this capture.
 * Pure reads, lookup and already Applied/Cancelled results cannot produce it or a new audit.
 * This memory-only data carries neither a principal capability nor secret/credential bytes. */
internal class RuntimeAdministrationCancellationCapture private constructor(
    private val command: LocalAdministrationCommand,
    private val requirement: RuntimeCommandRequirementV1,
    private val authorization: RuntimeCommandAuthorizationSnapshotV1,
    private val at: MonotonicMoment,
) {
    val scope: BranchScope get() = authorization.scope
    val actorId: DomainId get() = authorization.actorId
    val requestId: DomainId get() = command.requestId
    val purpose: RuntimeWritePurposeV2 get() = if (command.action == LocalAdministrationAction.REVOKE_DEVICE)
        RuntimeWritePurposeV2.DEVICE_ADMINISTRATION else RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(scope, actorId, command)
    val images = mutableListOf<RuntimeAuthenticationImageV1>()

    fun evidence(): RuntimeAdministrationCancellationEvidenceV1 = RuntimeAdministrationCancellationEvidenceV1(
        command = command, requirement = requirement, authorization = authorization,
        at = at, images = images.toList()).also { it.validate() }

    override fun toString(): String = "RuntimeAdministrationCancellationCapture([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts,
            permitted: AuthorizedAction, command: LocalAdministrationCommand,
            observed: RoomLocalAuthorizationObservation, event: LocalAuthEvent): RuntimeAdministrationCancellationCapture {
            val requirement = administrationCancellationRequirement(principal.scope, principal.actorId, command)
            val snapshot = RuntimeCommandAuthorizationSnapshotV1.capture(principal, facts, observed)
            require(permitted.isBoundTo(requirement, principal.actorId) && permitted.approvalId == null &&
                permitted.principalKind == principal.kind && permitted.authenticationId == principal.sessionId &&
                permitted.generation == principal.generation && permitted.policyRevision == facts.policy.revision)
            require(event.scope == principal.scope && event.eventId == command.requestId &&
                event.kind == LocalAuthEventKind.ADMINISTRATION_CANCELLED && event.targetId == command.targetId &&
                event.operatorId == principal.actorId && event.sessionId == command.sessionId && event.accountRevision == null &&
                event.contentDigest == localAdministrationRequestDigest(principal.scope, principal.actorId, command))
            return RuntimeAdministrationCancellationCapture(command, RuntimeCommandRequirementV1.capture(requirement), snapshot, event.at)
        }
    }
}

/** Identical inert requirement to the actual Room cancellation/lookup service, including
 * its existing DEVICE_ADMINISTRATION ADMINISTER binding. No new permission is granted. */
internal fun administrationCancellationRequirement(scope: BranchScope, operator: DomainId,
    command: LocalAdministrationCommand): AuthorizationRequirement {
    val device = command.action == LocalAdministrationAction.REVOKE_DEVICE
    return AuthorizationRequirement(Permission(if (device) SecurityAction.DEVICE_ADMINISTRATION else SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION,
        PermissionVerb.ADMINISTER), scope, ActionBinding(command.targetId, command.expectedRevision,
        localAdministrationRequestDigest(scope, operator, command), OperationId(command.requestId.value),
        if (device) "local-device-revocation" else "local-credential-administration"))
}

internal object RuntimeAdministrationCancellationEvidenceCodec {
    private const val MAX_BYTES: Int = 16 * 1024
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeAdministrationCancellationEvidenceV1.PROFILE + "\",").encodeToByteArray()
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeAdministrationCancellationEvidenceV1): ByteArray = guarded {
        value.validate()
        val bytes = json.encodeToString(RuntimeAdministrationCancellationEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..MAX_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        bytes
    }
    fun decode(bytes: ByteArray): RuntimeAdministrationCancellationEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeAdministrationCancellationEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }
    fun dependencies(value: RuntimeAdministrationCancellationEvidenceV1): ByteArray = json
        .encodeToString(RuntimeAdministrationCancellationDependenciesV1.serializer(), value.dependencies).encodeToByteArray()
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}
