package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Canonical dependencies of the actual privately reauthenticated manager. There is no
 * persisted manager session, password, verifier, bearer or reconstructed authority here. */
@Serializable
internal data class RuntimeApprovalApproverDependenciesV1(
    val account: PayloadDigest,
    val policy: PayloadDigest,
    val admission: PayloadDigest,
)

@Serializable
internal data class RuntimeApprovalApproverSnapshotV1(
    val scope: BranchScope,
    val actorId: DomainId,
    val deviceId: DomainId,
    val accountRevision: Revision,
    val generation: SessionGeneration,
    val credentialRevision: Revision,
    val admissionRevision: Revision,
    val policyRevision: Revision,
    val dependencies: RuntimeApprovalApproverDependenciesV1,
) {
    fun validate() {
        // Private approval reauthentication does not create an interactive session. A
        // genuine newly provisioned manager therefore still has account generation zero.
        require(accountRevision.value < Long.MAX_VALUE && generation.value in 0L until Long.MAX_VALUE)
    }
    override fun toString(): String = "RuntimeApprovalApproverSnapshotV1([REDACTED])"

    companion object {
        fun capture(scope: BranchScope, deviceId: DomainId, facts: RoomApprovalApproverFacts): RuntimeApprovalApproverSnapshotV1 {
            require(facts.account.scope == scope && facts.policy.scope == scope && facts.admission.scope == scope &&
                facts.policy.actorId == facts.account.actorId && facts.admission.deviceId == deviceId &&
                facts.account.enabled && facts.credentialPresent && facts.admission.admitted)
            return RuntimeApprovalApproverSnapshotV1(scope, facts.account.actorId, deviceId, facts.account.revision,
                facts.account.generation, facts.credentialRevision, facts.admission.revision, facts.policy.revision,
                facts.runtimeApprovalDependencies()).also { it.validate() }
        }
    }
}

/** Approval images have their OWN closed grammar. In particular, an approval UPDATE is
 * not admitted by widening the older insert-only authentication image profile. */
@Serializable
internal data class RuntimeApprovalImageV1(
    val tableTag: Int,
    val recordId: DomainId,
    val mutationTag: Int,
    val revision: Revision,
    val plaintextDigest: PayloadDigest,
) {
    val table: RuntimeHistoryTableV2 get() = RuntimeHistoryTableV2.fromTag(tableTag)
    val mutation: RuntimeHistoryMutationKindV2 get() = RuntimeHistoryMutationKindV2.fromTag(mutationTag)
    fun validate() {
        require(table in setOf(RuntimeHistoryTableV2.APPROVAL, RuntimeHistoryTableV2.AUTH_EVENT))
        require(if (table == RuntimeHistoryTableV2.AUTH_EVENT) mutation == RuntimeHistoryMutationKindV2.INSERT && revision == Revision(0)
            else mutation == RuntimeHistoryMutationKindV2.INSERT && revision == Revision(0) ||
                mutation == RuntimeHistoryMutationKindV2.UPDATE && revision == Revision(1))
    }
    fun key(scope: BranchScope): ByteArray { validate(); return runtimeSecurityKey(table, listOf(scope.storageKey(), recordId.value)) }
    fun matches(binding: ProtectedLocalBinding): Boolean = binding.key.table == table.protectedTable &&
        binding.key.recordId == recordId && binding.revision == revision
    override fun toString(): String = "RuntimeApprovalImageV1([REDACTED])"

    companion object {
        fun capture(binding: ProtectedLocalBinding, digest: PayloadDigest): RuntimeApprovalImageV1 {
            val table = RuntimeHistoryTableV2.entries.single { it.protectedTable == binding.key.table }
            return RuntimeApprovalImageV1(table.tag, binding.key.recordId,
                (if (table == RuntimeHistoryTableV2.APPROVAL && binding.revision == Revision(1))
                    RuntimeHistoryMutationKindV2.UPDATE else RuntimeHistoryMutationKindV2.INSERT).tag,
                binding.revision, digest).also { it.validate() }
        }
        fun capture(key: ProtectedLocalRecordKey, revision: Revision, bytes: ByteArray): RuntimeApprovalImageV1 =
            try { capture(ProtectedLocalBinding(key, revision), sha256(bytes)) } finally { bytes.fill(0) }
    }
}

/** Strict, additive issuance profile. MATCH is the original paired throttle completion;
 * its attempt identity and unchanged timeout are retained, never recreated at restart. */
@Serializable
internal data class RuntimeActionApprovalIssuanceEvidenceV1(
    val schema: String = PROFILE,
    val commandName: String,
    val commandVersion: Int,
    val approvalId: DomainId,
    val approval: String,
    val requestor: RuntimeCommandAuthorizationSnapshotV1,
    val approver: RuntimeApprovalApproverSnapshotV1,
    val throttle: RuntimeThrottleEvidenceV1,
    val nativeRecordDigest: PayloadDigest,
    val images: List<RuntimeApprovalImageV1>,
) {
    fun value(): ProtectedLocalActionApproval = decodeRuntimeApproval(approval, requestor.scope, approvalId, Revision(0))
    fun validate() {
        require(schema == PROFILE)
        CommandKey(commandName, commandVersion); requestor.validate(); approver.validate(); throttle.validate()
        val value = value()
        requireRuntimeApprovalActors(value, requestor, approver)
        require(throttle.outcome == CredentialAttemptOutcome.MATCH && throttle.denial == null &&
            throttle.scope == requestor.scope && throttle.actorId == approver.actorId && throttle.deviceId == approver.deviceId &&
            throttle.dependencies.account == approver.dependencies.account && throttle.dependencies.policy == approver.dependencies.policy &&
            throttle.dependencies.admission == approver.dependencies.admission && throttle.changed().size == 2 &&
            throttle.accountBefore.attemptId == throttle.attemptId && throttle.deviceBefore.attemptId == throttle.attemptId &&
            throttle.accountBefore.attemptStartedAt == throttle.deviceBefore.attemptStartedAt &&
            value.record.issuedAt.bootId == throttle.at.bootId && value.record.issuedAt.elapsedMillis >= throttle.at.elapsedMillis &&
            requestor.access.observedAt == value.record.issuedAt)
        val started = requireNotNull(throttle.accountBefore.attemptStartedAt)
        require(started.bootId == value.record.issuedAt.bootId && value.record.issuedAt.elapsedMillis >= started.elapsedMillis &&
            value.record.issuedAt.elapsedMillis - started.elapsedMillis < throttle.policy.attemptTimeoutMillis)
        require(images == runtimeApprovalIssuanceImages(value))
    }
    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        val value = value()
        val start = requireNotNull(throttle.accountBefore.attemptStartedAt)
        val timeout = if (throttle.policy.attemptTimeoutMillis > Long.MAX_VALUE - start.elapsedMillis) Long.MAX_VALUE
            else start.elapsedMillis + throttle.policy.attemptTimeoutMillis
        return runtimeApprovalFinalDeclaration(value, requestor, value.record.approvalId, requireNotNull(value.issueRequestDigest), timeout)
    }
    override fun toString(): String = "RuntimeActionApprovalIssuanceEvidenceV1([REDACTED])"
    companion object { const val PROFILE: String = "ukkera.runtime-action-approval-issuance/v1" }
}

/** Memory-only capture starts inside the actual private MATCH continuation before the
 * protected approval/audit seals. Only those actual seals supply its two image digests. */
internal class RuntimeActionApprovalIssuanceCapture(
    val command: CommandKey,
    val approval: ProtectedLocalActionApproval,
    val requestor: RuntimeCommandAuthorizationSnapshotV1,
    val approver: RuntimeApprovalApproverSnapshotV1,
) {
    val images = mutableListOf<RuntimeApprovalImageV1>()
    fun evidence(throttle: RuntimeThrottleEvidenceV1, nativeRecordDigest: PayloadDigest): RuntimeActionApprovalIssuanceEvidenceV1 =
        RuntimeActionApprovalIssuanceEvidenceV1(commandName = command.name, commandVersion = command.version,
            approvalId = approval.record.approvalId, approval = runtimeApprovalText(approval), requestor = requestor, approver = approver, throttle = throttle,
            nativeRecordDigest = nativeRecordDigest, images = images.toList()).also { it.validate() }
}

@Serializable
internal enum class RuntimeActionApprovalUseKindV1 { APPLIED_CONSUMED, REJECTED_UNCONSUMED }

@Serializable
internal data class RuntimeApprovalObservationV1(val issuerContextId: DomainId, val moment: MonotonicMoment) {
    fun value(): LocalAuthenticationObservation = LocalAuthenticationObservation(issuerContextId, moment)
    override fun toString(): String = "RuntimeApprovalObservationV1([REDACTED])"
    companion object {
        fun capture(value: LocalAuthenticationObservation): RuntimeApprovalObservationV1 =
            RuntimeApprovalObservationV1(value.issuerContextId, value.moment)
    }
}

/** Exact command-use DATA. Rejection has no consumed record/event/images and must join a
 * complete negative native mutation census; it cannot be relabelled as a direct command. */
@Serializable
internal data class RuntimeActionApprovalUseEvidenceV1(
    val kind: RuntimeActionApprovalUseKindV1,
    val approvalId: DomainId,
    val issuedApproval: String,
    val approver: RuntimeApprovalApproverSnapshotV1,
    val observed: RuntimeApprovalObservationV1,
    val consumedApproval: String?,
    val consumptionEventId: DomainId?,
    val consumptionEvent: String?,
    val images: List<RuntimeApprovalImageV1>,
) {
    fun issued(scope: BranchScope): ProtectedLocalActionApproval = decodeRuntimeApproval(issuedApproval, scope, approvalId, Revision(0))
    fun consumed(scope: BranchScope): ProtectedLocalActionApproval? = consumedApproval?.let { decodeRuntimeApproval(it, scope, approvalId, Revision(1)) }
    fun event(scope: BranchScope): LocalAuthEvent? = consumptionEvent?.let { text ->
        val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
        try {
            val event = LocalAuthenticationCodec.decodeEvent(ProtectedLocalBinding(
                ProtectedLocalRecordKey.AuthEvent(scope, requireNotNull(consumptionEventId)), Revision(0)), bytes)
            require(event.scope == scope)
            val canonical = LocalAuthenticationCodec.encodeEvent(event)
            try { require(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
            event
        } finally { bytes.fill(0) }
    }
    fun validate(command: RuntimeCommandAuthorizationEvidenceV1) {
        val value = issued(command.authorization.scope)
        approver.validate(); requireRuntimeApprovalActors(value, command.authorization, approver)
        require(command.actionApprovalId == value.record.approvalId && command.requirement.value() == value.record.requirement &&
            command.operationId == value.record.requirement.binding?.operationId && command.semanticDigest == value.record.requirement.binding?.payloadDigest)
        require(observed.issuerContextId == value.issuerContextId && observed.moment.bootId == value.record.issuedAt.bootId &&
            observed.moment.bootId == command.authorization.access.observedAt.bootId &&
            observed.moment.elapsedMillis >= value.record.issuedAt.elapsedMillis &&
            observed.moment.elapsedMillis - value.record.issuedAt.elapsedMillis < value.record.validForMillis)
        when (kind) {
            RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED -> {
                require(command.purposeTag == RuntimeWritePurposeV2.COMMAND_APPLY.tag)
                val consumed = requireNotNull(consumed(command.authorization.scope))
                val canonical = runtimeApprovalText(value.consumed(value.record.copy(revision = Revision(1), consumedByOperationId = command.operationId)))
                require(consumedApproval == canonical)
                val event = requireNotNull(event(command.authorization.scope))
                require(event.kind == LocalAuthEventKind.ACTION_APPROVAL_CONSUMED && event.targetId == command.requirement.documentId &&
                    event.operatorId == command.authorization.actorId && event.sessionId == command.authorization.sessionId &&
                    event.accountRevision == command.authorization.accountRevision && event.at == observed.moment &&
                    event.contentDigest == runtimeApprovalDigest(consumed))
                require(images == runtimeApprovalConsumptionImages(consumed, event))
            }
            RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED -> require(command.purposeTag == RuntimeWritePurposeV2.COMMAND_REJECT.tag &&
                consumedApproval == null && consumptionEventId == null && consumptionEvent == null && images.isEmpty())
        }
    }
    fun protectedFinalDeclaration(command: RuntimeCommandAuthorizationEvidenceV1): NativeHistoryProtectedFinalDeclaration =
        runtimeApprovalFinalDeclaration(issued(command.authorization.scope), command.authorization,
            DomainId(command.operationId.value), command.semanticDigest, Long.MAX_VALUE, observed.moment)
    override fun toString(): String = "RuntimeActionApprovalUseEvidenceV1([REDACTED])"
}

/** Additive envelope wrapping the EXACT existing command V1 bytes, not changing direct or
 * legacy encodings. Neither its approval locator nor its decoded facts grant authority. */
@Serializable
internal data class RuntimeApprovedCommandEvidenceV1(
    val schema: String = PROFILE,
    val commandEvidence: String,
    val approvalUse: RuntimeActionApprovalUseEvidenceV1,
) {
    fun command(): RuntimeCommandAuthorizationEvidenceV1 {
        val bytes = commandEvidence.encodeToByteArray(throwOnInvalidSequence = true)
        return try { RuntimeCommandAuthorizationEvidenceCodec.decode(bytes) } finally { bytes.fill(0) }
    }
    fun validate() { require(schema == PROFILE); approvalUse.validate(command()) }
    override fun toString(): String = "RuntimeApprovedCommandEvidenceV1([REDACTED])"
    companion object { const val PROFILE: String = "ukkera.runtime-approved-command-evidence/v1" }
}

internal class RuntimeActionApprovalUseCapture(
    val issued: ProtectedLocalActionApproval,
    val approver: RuntimeApprovalApproverSnapshotV1,
    val preparedAt: LocalAuthenticationObservation,
) {
    val images = mutableListOf<RuntimeApprovalImageV1>()
    var consumed: ProtectedLocalActionApproval? = null
    var event: LocalAuthEvent? = null
    var consumedAt: LocalAuthenticationObservation? = null
    fun evidence(command: RuntimeCommandAuthorizationEvidenceV1): RuntimeActionApprovalUseEvidenceV1 {
        val applied = command.purposeTag == RuntimeWritePurposeV2.COMMAND_APPLY.tag
        require(applied == (consumed != null) && applied == (event != null) && applied == (consumedAt != null))
        return RuntimeActionApprovalUseEvidenceV1(if (applied) RuntimeActionApprovalUseKindV1.APPLIED_CONSUMED else
            RuntimeActionApprovalUseKindV1.REJECTED_UNCONSUMED, issued.record.approvalId, runtimeApprovalText(issued), approver,
            RuntimeApprovalObservationV1.capture(consumedAt ?: preparedAt), consumed?.let(::runtimeApprovalText), event?.eventId, event?.let { value ->
                val bytes = LocalAuthenticationCodec.encodeEvent(value)
                try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
            }, images.toList()).also { it.validate(command) }
    }
}

internal object RuntimeActionApprovalEvidenceCodec {
    private const val MAX_BYTES = 64 * 1024
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false
        isLenient = false; coerceInputValues = false; allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false; prettyPrint = false }
    private val issuePrefix = ("{\"schema\":\"" + RuntimeActionApprovalIssuanceEvidenceV1.PROFILE + "\",").encodeToByteArray()
    private val commandPrefix = ("{\"schema\":\"" + RuntimeApprovedCommandEvidenceV1.PROFILE + "\",").encodeToByteArray()
    fun hasIssuanceProfile(bytes: ByteArray): Boolean = hasPrefix(bytes, issuePrefix)
    fun hasCommandProfile(bytes: ByteArray): Boolean = hasPrefix(bytes, commandPrefix)
    private fun hasPrefix(bytes: ByteArray, prefix: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeActionApprovalIssuanceEvidenceV1): ByteArray = guarded {
        value.validate(); json.encodeToString(RuntimeActionApprovalIssuanceEvidenceV1.serializer(), value).encodeToByteArray()
            .also { historyCapacityV2(it.size in 1..MAX_BYTES) }
    }
    fun encode(value: RuntimeApprovedCommandEvidenceV1): ByteArray = guarded {
        value.validate(); json.encodeToString(RuntimeApprovedCommandEvidenceV1.serializer(), value).encodeToByteArray()
            .also { historyCapacityV2(it.size in 1..MAX_BYTES) }
    }
    fun decodeIssuance(bytes: ByteArray): RuntimeActionApprovalIssuanceEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        json.decodeFromString(RuntimeActionApprovalIssuanceEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true)).also { value ->
            val canonical = encode(value); try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        }
    }
    fun decodeCommand(bytes: ByteArray): RuntimeApprovedCommandEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        json.decodeFromString(RuntimeApprovedCommandEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true)).also { value ->
            val canonical = encode(value); try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        }
    }
    fun dependencies(value: RuntimeActionApprovalIssuanceEvidenceV1): ByteArray = encode(value).let { bytes ->
        try { (value.requestor.dependencies.account.value + ":" + value.requestor.dependencies.policy.value + ":" +
            value.requestor.dependencies.admission.value + ":" + value.requestor.dependencies.issuance.value + ":" +
            value.approver.dependencies.account.value + ":" + value.approver.dependencies.policy.value + ":" +
            value.approver.dependencies.admission.value + ":" + sha256(bytes).value).encodeToByteArray() }
        finally { bytes.fill(0) }
    }
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}

internal fun runtimeApprovalText(value: ProtectedLocalActionApproval): String {
    val bytes = LocalActionApprovalCodec.encode(value)
    return try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
}
internal fun runtimeApprovalDigest(value: ProtectedLocalActionApproval): PayloadDigest {
    val bytes = LocalActionApprovalCodec.encode(value)
    return try { sha256(bytes) } finally { bytes.fill(0) }
}
private fun decodeRuntimeApproval(text: String, scope: BranchScope, approvalId: DomainId, revision: Revision): ProtectedLocalActionApproval {
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    return try {
        val value = LocalActionApprovalCodec.decode(ProtectedLocalBinding(ProtectedLocalRecordKey.ActionApproval(scope, approvalId), revision), bytes)
        require(value.hasApproverIncarnation && value.record.requirement.scope == scope && value.record.revision == revision)
        val canonical = LocalActionApprovalCodec.encode(value)
        try { require(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    } finally { bytes.fill(0) }
}
private fun requireRuntimeApprovalActors(value: ProtectedLocalActionApproval, requestor: RuntimeCommandAuthorizationSnapshotV1,
    approver: RuntimeApprovalApproverSnapshotV1) {
    val record = value.record
    val authority = value.issuingRequestorAuthority
    require(value.hasApproverIncarnation && record.requirement.scope == requestor.scope && approver.scope == requestor.scope &&
        record.requestorId == requestor.actorId && record.requestorPolicyRevision == requestor.policyRevision &&
        authority.sessionId == requestor.sessionId && authority.generation == requestor.generation &&
        authority.deviceId == requestor.deviceId && authority.credentialRevision == requestor.credentialRevision &&
        authority.admissionRevision == requestor.admissionRevision && value.issuerContextId == requestor.access.issuerContextId &&
        record.approverId == approver.actorId && record.approverPolicyRevision == approver.policyRevision &&
        record.selfApprovalPolicyRevision == approver.policyRevision && value.approverAccountRevision == approver.accountRevision &&
        value.approverGeneration == approver.generation && value.approverDeviceId == approver.deviceId &&
        value.approverCredentialRevision == approver.credentialRevision && value.approverAdmissionRevision == approver.admissionRevision &&
        approver.deviceId == requestor.deviceId && value.confirmationDigest == LocalActionApprovalCodec.confirmationDigest(
            record.requestorId, record.requestorPolicyRevision, record.requirement) &&
        record.requirement.permission.action !in setOf(SecurityAction.RECOVERY_COPY, SecurityAction.RECOVERY_ACTIVATION,
            SecurityAction.RECOVERY_RECONCILIATION))
}
internal fun runtimeApprovalIssuanceImages(value: ProtectedLocalActionApproval): List<RuntimeApprovalImageV1> {
    val scope = value.record.requirement.scope as BranchScope
    val event = runtimeApprovalIssueEvent(value)
    return listOf(RuntimeApprovalImageV1.capture(ProtectedLocalRecordKey.ActionApproval(scope, value.record.approvalId), Revision(0),
        LocalActionApprovalCodec.encode(value)), RuntimeApprovalImageV1.capture(ProtectedLocalRecordKey.AuthEvent(scope, event.eventId),
        Revision(0), LocalAuthenticationCodec.encodeEvent(event)))
}
internal fun runtimeApprovalIssueEvent(value: ProtectedLocalActionApproval): LocalAuthEvent =
    LocalAuthEvent(value.record.requirement.scope as BranchScope, value.record.approvalId, LocalAuthEventKind.ACTION_APPROVAL_ISSUED,
        value.record.approvalId, value.record.approverId, value.issuingRequestorAuthority.sessionId, requireNotNull(value.approverAccountRevision),
        value.record.issuedAt, runtimeApprovalDigest(value))
internal fun runtimeApprovalConsumptionImages(value: ProtectedLocalActionApproval, event: LocalAuthEvent): List<RuntimeApprovalImageV1> {
    val scope = value.record.requirement.scope as BranchScope
    return listOf(RuntimeApprovalImageV1.capture(ProtectedLocalRecordKey.ActionApproval(scope, value.record.approvalId), Revision(1),
        LocalActionApprovalCodec.encode(value)), RuntimeApprovalImageV1.capture(ProtectedLocalRecordKey.AuthEvent(scope, event.eventId),
        Revision(0), LocalAuthenticationCodec.encodeEvent(event)))
}
private fun runtimeApprovalFinalDeclaration(value: ProtectedLocalActionApproval, requestor: RuntimeCommandAuthorizationSnapshotV1,
    requestId: DomainId, digest: PayloadDigest, timeout: Long,
    useObservedAt: MonotonicMoment = requestor.access.observedAt): NativeHistoryProtectedFinalDeclaration =
    NativeHistoryProtectedFinalDeclaration(requestId, digest, requestor.actorId, requestor.sessionId,
        requestor.access.issuerContextId, requestor.access.issuedAt,
        MonotonicMoment(requestor.access.observedAt.bootId,
            maxOf(requestor.access.observedAt.elapsedMillis, value.record.issuedAt.elapsedMillis, useObservedAt.elapsedMillis)),
        minOf(requestor.access.expiresAtElapsedMillis, value.record.issuedAt.elapsedMillis + value.record.validForMillis, timeout))
