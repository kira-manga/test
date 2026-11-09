package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.MinorUnitsSerializer
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Fingerprints of the actual authenticated canonical records on this writer loan.
 * They identify dependencies to join; they do not authorize a command by themselves. */
@Serializable
internal data class RuntimeCommandAuthorizationDependenciesV1(
    val account: PayloadDigest,
    val policy: PayloadDigest,
    val admission: PayloadDigest,
    val issuance: PayloadDigest,
) {
    override fun toString(): String = "RuntimeCommandAuthorizationDependenciesV1([REDACTED])"
}

/** The successful authorization observation and its unchanged original access deadline.
 * This is not the final native-settlement sample or a clock/service qualification receipt. */
@Serializable
internal data class RuntimeCommandAccessWindowV1(
    val issuerContextId: DomainId,
    val issuedAt: MonotonicMoment,
    val observedAt: MonotonicMoment,
    @Serializable(with = MinorUnitsSerializer::class) val expiresAtElapsedMillis: Long,
    val credentialRevisionAtIssue: Revision,
    val admissionRevisionAtIssue: Revision,
) {
    fun validate() {
        require(observedAt.bootId == issuedAt.bootId && observedAt.elapsedMillis >= issuedAt.elapsedMillis &&
            expiresAtElapsedMillis > issuedAt.elapsedMillis && observedAt.elapsedMillis < expiresAtElapsedMillis)
    }
    override fun toString(): String = "RuntimeCommandAccessWindowV1([REDACTED])"
}

/** Internal retained DATA for one already-authorized local interactive employee.
 * No policy grants, credential/verifier bytes, bearer or refresh secret escape the reader. */
@Serializable
internal data class RuntimeCommandAuthorizationSnapshotV1(
    val scope: BranchScope,
    val actorId: DomainId,
    val sessionId: DomainId,
    val deviceId: DomainId,
    val generation: SessionGeneration,
    val principalKind: PrincipalKind,
    val authenticationPurpose: AuthenticationPurpose,
    val accountRevision: Revision,
    val credentialRevision: Revision,
    val admissionRevision: Revision,
    val policyRevision: Revision,
    val access: RuntimeCommandAccessWindowV1,
    val dependencies: RuntimeCommandAuthorizationDependenciesV1,
) {
    fun validate() {
        require(principalKind == PrincipalKind.LOCAL_EMPLOYEE && authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            generation.value in 1L until Long.MAX_VALUE && accountRevision.value < Long.MAX_VALUE &&
            credentialRevision == access.credentialRevisionAtIssue && admissionRevision == access.admissionRevisionAtIssue)
        access.validate()
    }
    override fun toString(): String = "RuntimeCommandAuthorizationSnapshotV1([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts,
            observed: RoomLocalAuthorizationObservation): RuntimeCommandAuthorizationSnapshotV1 {
            require(facts.policy.scope == principal.scope && facts.policy.actorId == principal.actorId &&
                LocalAccessEligibility.matchesCurrentFacts(principal, facts.issuance, facts.account,
                    facts.credentialRevision, facts.credentialPresent, facts.admission, observed.issuerContextId, observed.moment))
            val issuance = facts.issuance
            return RuntimeCommandAuthorizationSnapshotV1(principal.scope, principal.actorId, principal.sessionId,
                principal.deviceId, principal.generation, principal.kind, principal.authenticationPurpose,
                facts.account.revision, facts.credentialRevision, facts.admission.revision, facts.policy.revision,
                RuntimeCommandAccessWindowV1(issuance.issuerContextId, issuance.issuedAt, observed.moment,
                    issuance.expiresAtElapsedMillis, issuance.credentialRevisionAtIssue, issuance.admissionRevisionAtIssue),
                facts.runtimeCommandDependencies()).also { it.validate() }
        }
    }
}

/** Complete original handler requirement. This profile supports every existing command verb;
 * it does not reinterpret the older PERFORM-only action-approval requirement encoding. */
@Serializable
internal data class RuntimeCommandRequirementV1(
    val action: SecurityAction,
    val verb: PermissionVerb,
    val scope: BranchScope,
    val documentId: DomainId,
    val documentRevision: Revision,
    val payloadDigest: PayloadDigest,
    val operationId: OperationId,
    val reasonCode: String,
    val limitKind: String,
    val amount: Money?,
    val baseAmount: Money?,
    val ownerId: DomainId?,
) {
    fun value(): AuthorizationRequirement {
        val use = when (limitKind) {
            "none" -> { require(amount == null && baseAmount == null); LimitUse.NotApplicable }
            "monetary" -> { require(baseAmount == null); LimitUse.Monetary(requireNotNull(amount)) }
            "proportional" -> LimitUse.Proportional(requireNotNull(amount), requireNotNull(baseAmount))
            else -> throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
        }
        return AuthorizationRequirement(Permission(action, verb), scope,
            ActionBinding(documentId, documentRevision, payloadDigest, operationId, reasonCode), use,
            ownerId?.let { OwnerConstraint.OwnerPrivate(it) } ?: OwnerConstraint.Unrestricted)
    }
    override fun toString(): String = "RuntimeCommandRequirementV1([REDACTED])"

    companion object {
        fun capture(value: AuthorizationRequirement): RuntimeCommandRequirementV1 {
            val scope = requireNotNull(value.scope as? BranchScope)
            val binding = requireNotNull(value.binding)
            val operation = requireNotNull(binding.operationId)
            val kind: String
            val amount: Money?
            val base: Money?
            when (val use = value.limitUse) {
                LimitUse.NotApplicable -> { kind = "none"; amount = null; base = null }
                is LimitUse.Monetary -> { kind = "monetary"; amount = use.amount; base = null }
                is LimitUse.Proportional -> { kind = "proportional"; amount = use.amount; base = use.baseAmount }
            }
            val owner = when (val constraint = value.owner) {
                OwnerConstraint.Unrestricted -> null
                is OwnerConstraint.OwnerPrivate -> constraint.ownerId
            }
            return RuntimeCommandRequirementV1(value.permission.action, value.permission.verb, scope,
                binding.documentId, binding.revision, binding.payloadDigest, operation, binding.reasonCode,
                kind, amount, base, owner).also { require(it.value() == value) }
        }
    }
}

/** Retained format, not an accepting replay verifier. An approval ID is a dependency locator,
 * never proof of approver eligibility/consumption. Native-original import has a different
 * historical Start authority and deliberately does not receive this employee capture. */
@Serializable
internal data class RuntimeCommandAuthorizationEvidenceV1(
    val schema: String = PROFILE,
    val purposeTag: Int,
    val operationId: OperationId,
    val commandName: String,
    val commandVersion: Int,
    val semanticDigest: PayloadDigest,
    val requirement: RuntimeCommandRequirementV1,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val actionApprovalId: DomainId?,
    val nativeRecordDigest: PayloadDigest,
    val capturedChanges: Int,
) {
    fun validate() {
        require(schema == PROFILE && purposeTag in setOf(RuntimeWritePurposeV2.COMMAND_APPLY.tag,
            RuntimeWritePurposeV2.COMMAND_REJECT.tag) && capturedChanges in 1..RuntimeHistoryProfileV2.MAX_CHANGES)
        CommandKey(commandName, commandVersion)
        authorization.validate()
        val original = requirement.value()
        require(original.scope == authorization.scope && requirement.operationId == operationId &&
            requirement.payloadDigest == semanticDigest)
        if (actionApprovalId != null) require(original.permission.verb == PermissionVerb.PERFORM)
    }
    override fun toString(): String = "RuntimeCommandAuthorizationEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-command-authorization-evidence/v1"
    }
}

/** Memory-only capture with no writer, credential or AuthorizedAction capability retained. */
internal class RuntimeCommandAuthorizationCapture private constructor(
    private val operationId: OperationId,
    private val command: CommandKey,
    private val digest: PayloadDigest,
    private val requirement: RuntimeCommandRequirementV1,
    private val authorization: RuntimeCommandAuthorizationSnapshotV1,
    private val actionApprovalId: DomainId?,
    private val applied: Boolean,
) {
    fun evidence(nativeRecordDigest: PayloadDigest, capturedChanges: Int): RuntimeCommandAuthorizationEvidenceV1 =
        RuntimeCommandAuthorizationEvidenceV1(purposeTag = if (applied) RuntimeWritePurposeV2.COMMAND_APPLY.tag
            else RuntimeWritePurposeV2.COMMAND_REJECT.tag, operationId = operationId, commandName = command.name,
            commandVersion = command.version, semanticDigest = digest, requirement = requirement,
            authorization = authorization, actionApprovalId = actionApprovalId, nativeRecordDigest = nativeRecordDigest,
            capturedChanges = capturedChanges).also { it.validate() }

    override fun toString(): String = "RuntimeCommandAuthorizationCapture([REDACTED])"

    companion object {
        fun capture(request: MutationRequest, principal: LocalEmployeePrincipal, digest: PayloadDigest,
            requirement: AuthorizationRequirement, authorization: AuthorizedAction,
            snapshot: RuntimeCommandAuthorizationSnapshotV1, applied: Boolean): RuntimeCommandAuthorizationCapture {
            snapshot.validate()
            val binding = requireNotNull(requirement.binding)
            require(requirement.scope == principal.scope && binding.operationId == request.operationId &&
                binding.payloadDigest == digest && authorization.isBoundTo(requirement, principal.actorId) &&
                authorization.principalKind == principal.kind && authorization.authenticationId == principal.authenticationId &&
                authorization.generation == principal.generation && authorization.approvalId == request.actionApprovalId &&
                snapshot.scope == principal.scope && snapshot.actorId == principal.actorId && snapshot.sessionId == principal.sessionId &&
                snapshot.deviceId == principal.deviceId && snapshot.generation == principal.generation &&
                snapshot.admissionRevision == principal.admissionRevision && snapshot.policyRevision == authorization.policyRevision)
            return RuntimeCommandAuthorizationCapture(request.operationId, request.command, digest,
                RuntimeCommandRequirementV1.capture(requirement), snapshot, request.actionApprovalId, applied)
        }
    }
}

internal object RuntimeCommandAuthorizationEvidenceCodec {
    private const val MAX_BYTES = 16 * 1024
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false
        isLenient = false; coerceInputValues = false; allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeCommandAuthorizationEvidenceV1.PROFILE + "\",").encodeToByteArray()

    /** A discriminator only; a successful decode still supplies DATA, not admission. */
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }

    fun encode(value: RuntimeCommandAuthorizationEvidenceV1): ByteArray = guarded {
        value.validate()
        val bytes = json.encodeToString(RuntimeCommandAuthorizationEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..MAX_BYTES) {
            bytes.fill(0)
            historyCapacityV2(false)
        }
        bytes
    }

    fun decode(bytes: ByteArray): RuntimeCommandAuthorizationEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeCommandAuthorizationEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}
