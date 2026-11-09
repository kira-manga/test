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

/**
 * Protected canonical approval bytes, not RPC authority. Payload v2 retains an exact canonical
 * v1 base and adds mandatory incarnation/submission witnesses. Replacement v3 wraps an exact
 * canonical v2 base with mandatory current authority and native origin references. The existing
 * AEAD/table format remains v1; no historical payload is silently upgraded or rewritten.
 */
internal object LocalActionApprovalCodec {
    private const val V2 = "ukkera.local-action-approval/v2"
    private const val V3 = "ukkera.local-action-approval/v3"
    private val v2Prefix = ("{\"schema\":\"$V2\",").encodeToByteArray()
    private val v3Prefix = ("{\"schema\":\"$V3\",").encodeToByteArray()
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false
        isLenient = false; coerceInputValues = false; allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false; prettyPrint = false
    }

    fun encode(value: ProtectedLocalActionApproval): ByteArray = guarded {
        if (!value.hasReplacementOrigin) return@guarded encodeV2(value)
        val base = encodeV2(value)
        try {
            bounded(json.encodeToString(ApprovalV3Wire.serializer(), ApprovalV3Wire(V3,
                base.decodeToString(throwOnInvalidSequence = true), checkNotNull(value.currentRequestorAuthority),
                checkNotNull(value.replacementOrigin))))
        } finally { base.fill(0) }
    }

    /** Extension stripping is private to a complete, canonical versioned envelope. */
    private fun encodeV2(value: ProtectedLocalActionApproval): ByteArray {
        val base = encodeV1(value)
        if (!value.hasApproverIncarnation) return base
        return try {
            bounded(json.encodeToString(ApprovalV2Wire.serializer(), ApprovalV2Wire(V2,
                base.decodeToString(throwOnInvalidSequence = true), checkNotNull(value.approverAccountRevision),
                checkNotNull(value.approverGeneration), checkNotNull(value.issueRequestDigest))))
        } finally { base.fill(0) }
    }

    /** Removing extensions is allowed ONLY inside a complete versioned envelope. */
    private fun encodeV1(value: ProtectedLocalActionApproval): ByteArray {
        val record = value.record
        return bounded(json.encodeToString(ApprovalWire.serializer(), ApprovalWire("ukkera.local-action-approval/v1",
            record.approvalId, record.requestorId, record.approverId, RequirementWire.capture(record.requirement),
            record.requestorPolicyRevision, record.approverPolicyRevision, record.selfApprovalPolicyRevision,
            record.issuedAt, record.validForMillis, record.revision, record.consumedByOperationId,
            value.issuerContextId, value.requestorSessionId, value.requestorGeneration, value.requestorDeviceId,
            value.requestorCredentialRevision, value.requestorAdmissionRevision, value.approverDeviceId,
            value.approverCredentialRevision, value.approverAdmissionRevision, value.confirmationDigest)))
    }

    fun decode(binding: ProtectedLocalBinding, plaintext: ByteArray): ProtectedLocalActionApproval = guarded {
        require(binding.table == ProtectedLocalTable.ACTION_APPROVAL && binding.schemaVersion == 1)
        binding.requirePlaintextSize(plaintext.size)
        when {
            hasPrefix(plaintext, v3Prefix) -> decodeV3(binding, plaintext)
            hasPrefix(plaintext, v2Prefix) -> decodeV2(binding, plaintext)
            else -> decodeV1(binding, plaintext)
        }
    }

    private fun hasPrefix(bytes: ByteArray, prefix: ByteArray): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

    private fun decodeV3(binding: ProtectedLocalBinding, plaintext: ByteArray): ProtectedLocalActionApproval {
        val snapshot = plaintext.copyOf()
        return try {
            val wire = json.decodeFromString(ApprovalV3Wire.serializer(), snapshot.decodeToString(throwOnInvalidSequence = true))
            require(wire.schema == V3)
            val baseBytes = wire.baseV2.encodeToByteArray(throwOnInvalidSequence = true)
            val base = try { decodeV2(binding, baseBytes) } finally { baseBytes.fill(0) }
            val value = ProtectedLocalActionApproval(base.record, base.issuerContextId, base.requestorSessionId,
                base.requestorGeneration, base.requestorDeviceId, base.requestorCredentialRevision,
                base.requestorAdmissionRevision, base.approverDeviceId, base.approverCredentialRevision,
                base.approverAdmissionRevision, base.confirmationDigest, base.approverAccountRevision,
                base.approverGeneration, base.issueRequestDigest, wire.currentRequestorAuthority, wire.replacementOrigin)
            val canonical = encode(value)
            try { require(canonical.contentEquals(snapshot)) } finally { canonical.fill(0) }
            value
        } finally { snapshot.fill(0) }
    }

    private fun decodeV2(binding: ProtectedLocalBinding, plaintext: ByteArray): ProtectedLocalActionApproval {
        binding.requirePlaintextSize(plaintext.size)
        val snapshot = plaintext.copyOf()
        return try {
            val wire = json.decodeFromString(ApprovalV2Wire.serializer(), snapshot.decodeToString(throwOnInvalidSequence = true))
            require(wire.schema == V2)
            val baseBytes = wire.baseV1.encodeToByteArray(throwOnInvalidSequence = true)
            val base = try { decodeV1(binding, baseBytes) } finally { baseBytes.fill(0) }
            val value = ProtectedLocalActionApproval(base.record, base.issuerContextId, base.requestorSessionId,
                base.requestorGeneration, base.requestorDeviceId, base.requestorCredentialRevision,
                base.requestorAdmissionRevision, base.approverDeviceId, base.approverCredentialRevision,
                base.approverAdmissionRevision, base.confirmationDigest, wire.approverAccountRevision,
                wire.approverGeneration, wire.issueRequestDigest)
            val canonical = encode(value)
            try { require(canonical.contentEquals(snapshot)) } finally { canonical.fill(0) }
            value
        } finally { snapshot.fill(0) }
    }

    private fun decodeV1(binding: ProtectedLocalBinding, plaintext: ByteArray): ProtectedLocalActionApproval {
        binding.requirePlaintextSize(plaintext.size)
        val snapshot = plaintext.copyOf()
        return try {
            val wire = json.decodeFromString(ApprovalWire.serializer(), snapshot.decodeToString(throwOnInvalidSequence = true))
            require(wire.schema == "ukkera.local-action-approval/v1")
            val record = ActionApprovalRecord(wire.approvalId, wire.requestorId, wire.approverId,
                wire.requirement.value(), wire.requestorPolicyRevision, wire.approverPolicyRevision,
                wire.selfApprovalPolicyRevision, wire.issuedAt, wire.validForMillis, wire.revision, wire.consumedByOperationId)
            val value = ProtectedLocalActionApproval(record, wire.issuerContextId, wire.requestorSessionId,
                wire.requestorGeneration, wire.requestorDeviceId, wire.requestorCredentialRevision,
                wire.requestorAdmissionRevision, wire.approverDeviceId, wire.approverCredentialRevision,
                wire.approverAdmissionRevision, wire.confirmationDigest)
            require(binding.key == ProtectedLocalRecordKey.ActionApproval(record.requirement.scope as BranchScope, record.approvalId) &&
                binding.revision == record.revision)
            val canonical = encode(value)
            try { require(canonical.contentEquals(snapshot)) } finally { canonical.fill(0) }
            value
        } finally { snapshot.fill(0) }
    }

    fun confirmationDigest(requestorId: DomainId, policyRevision: Revision, requirement: AuthorizationRequirement): PayloadDigest = guarded {
        val body = bounded(json.encodeToString(RequirementWire.serializer(), RequirementWire.capture(requirement)))
        val header = "ukkera.local-action-approval-preview/v1\u0000${requestorId.value}\u0000${policyRevision.value}\u0000".encodeToByteArray()
        val bytes = header + body
        try { sha256(bytes) } finally { body.fill(0); header.fill(0); bytes.fill(0) }
    }

    /** Retry identity only. Equivalent-but-different submissions may use historical lookup. */
    fun issueRequestDigest(scope: BranchScope, requestorId: DomainId, request: MutationRequest): PayloadDigest = guarded {
        require(request.actionApprovalId == null)
        val fields = listOf("ukkera.local-action-approval-submission/v1".encodeToByteArray(),
            scope.storageKey().encodeToByteArray(), requestorId.value.encodeToByteArray(),
            request.operationId.value.encodeToByteArray(), request.command.name.encodeToByteArray(),
            request.command.version.toString().encodeToByteArray(), request.payload.copyBytes())
        val bytes = frame(fields)
        try { sha256(bytes) } finally { bytes.fill(0); fields.forEach { it.fill(0) } }
    }

    private fun bounded(value: String): ByteArray {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..ProtectedLocalTable.ACTION_APPROVAL.maximumPlaintextBytes) {
            bytes.fill(0); protectedLocalUnavailable()
        }
        return bytes
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { protectedLocalUnavailable() }
}

@Serializable
private class ApprovalV2Wire(
    val schema: String, val baseV1: String, val approverAccountRevision: Revision,
    val approverGeneration: SessionGeneration, val issueRequestDigest: PayloadDigest,
)

@Serializable
private class ApprovalV3Wire(
    val schema: String, val baseV2: String,
    val currentRequestorAuthority: ActionApprovalRequestorAuthority,
    val replacementOrigin: ActionApprovalReplacementOrigin,
)

@Serializable
private class ApprovalWire(
    val schema: String, val approvalId: DomainId, val requestorId: DomainId, val approverId: DomainId,
    val requirement: RequirementWire, val requestorPolicyRevision: Revision, val approverPolicyRevision: Revision,
    val selfApprovalPolicyRevision: Revision, val issuedAt: MonotonicMoment,
    @Serializable(with = MinorUnitsSerializer::class) val validForMillis: Long,
    val revision: Revision, val consumedByOperationId: OperationId?, val issuerContextId: DomainId,
    val requestorSessionId: DomainId, val requestorGeneration: SessionGeneration, val requestorDeviceId: DomainId,
    val requestorCredentialRevision: Revision, val requestorAdmissionRevision: Revision,
    val approverDeviceId: DomainId, val approverCredentialRevision: Revision, val approverAdmissionRevision: Revision,
    val confirmationDigest: PayloadDigest,
)

@Serializable
private class RequirementWire(
    val action: SecurityAction, val verb: PermissionVerb, val scope: BranchScope,
    val documentId: DomainId, val documentRevision: Revision, val payloadDigest: PayloadDigest,
    val operationId: OperationId, val reasonCode: String, val limitKind: String,
    val amount: Money?, val baseAmount: Money?, val ownerId: DomainId?,
) {
    fun value(): AuthorizationRequirement {
        require(verb == PermissionVerb.PERFORM)
        val use = when (limitKind) {
            "none" -> { require(amount == null && baseAmount == null); LimitUse.NotApplicable }
            "monetary" -> { require(baseAmount == null); LimitUse.Monetary(checkNotNull(amount)) }
            "proportional" -> LimitUse.Proportional(checkNotNull(amount), checkNotNull(baseAmount))
            else -> error("Unsupported approval requirement")
        }
        return AuthorizationRequirement(Permission(action, verb), scope,
            ActionBinding(documentId, documentRevision, payloadDigest, operationId, reasonCode), use,
            ownerId?.let { OwnerConstraint.OwnerPrivate(it) } ?: OwnerConstraint.Unrestricted)
    }
    companion object {
        fun capture(value: AuthorizationRequirement): RequirementWire {
            val scope = value.scope as? BranchScope ?: protectedLocalUnavailable()
            val binding = value.binding ?: protectedLocalUnavailable()
            val operation = binding.operationId ?: protectedLocalUnavailable()
            require(value.permission.verb == PermissionVerb.PERFORM)
            val kind: String
            val amount: Money?
            val base: Money?
            when (val use = value.limitUse) {
                LimitUse.NotApplicable -> { kind = "none"; amount = null; base = null }
                is LimitUse.Monetary -> { kind = "monetary"; amount = use.amount; base = null }
                is LimitUse.Proportional -> { kind = "proportional"; amount = use.amount; base = use.baseAmount }
            }
            return RequirementWire(value.permission.action, value.permission.verb, scope, binding.documentId,
                binding.revision, binding.payloadDigest, operation, binding.reasonCode, kind, amount, base,
                (value.owner as? OwnerConstraint.OwnerPrivate)?.ownerId)
        }
    }
}
