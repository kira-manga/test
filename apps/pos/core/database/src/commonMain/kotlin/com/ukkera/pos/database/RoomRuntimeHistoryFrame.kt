package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.sqlite.SQLiteStatement
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceSelector
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** One real IMMEDIATE frame. The private TEMP images are populated by native-owned triggers,
 * not declarations from handlers. Main capture, ordered images, typed keys and prior row tips
 * must agree before a child is signed. All buffers and SQL stay on this one borrowed loan. */
internal class RoomRuntimeHistoryFrame internal constructor(
    private val owner: RoomRuntimeHistoryOwner,
    internal val connection: Transactor,
    internal val native: SelfOwnedNativeRoomFrame,
) {
    private enum class Phase { NEW, BODY, PREPARING, PREPARED, FINISHED, CLOSED }
    private var phase = Phase.NEW
    internal lateinit var coroutineContext: CoroutineContext
        private set
    internal val originalParent: NativeHistoryRecordedHeadV2 get() = checkNotNull(native.original.record.parent)
    internal val originalBinding: SelfOwnedNativeOriginalBinding get() = native.original.nativeOriginalBinding
    internal val originalInstallationId: DomainId get() = native.original.record.allocation.coordinates.installationId
    internal val originalAuthority: AuthorityIdentity get() = native.original.record.allocation.coordinates.let {
        AuthorityIdentity(it.assignmentId, it.deviceId, checkNotNull(native.original.record.definition).definition.authorityEpoch)
    }
    private val coordinates = native.original.record.allocation.coordinates
    private val scopeKey = coordinates.scope.storageKey()
    private var view: TransactionView? = null
    private var writerActor: DomainId? = null
    private var securityTransaction: RoomLocalSecurityTransaction? = null
    private var originalInvocation: NativeOriginalIntakeInvocation? = null
    private var originalLease: NativeOriginalRetainedLease? = null
    private var originalStore: AtomicMasterStore? = null
    private var requestId = native.frameId
    private var requestDigest = native.original.recordDigest
    private var operationId: DomainId? = null
    private var actorId: DomainId? = null
    private var purpose = RuntimeWritePurposeV2.PROVISIONING
    private var resources = emptyList<NativeHistoryOriginalResourceV2>()
    private val dependencyRows = mutableListOf<ByteArray>()
    private var dependencyBytes = 0L
    private var definitionBytes: ByteArray? = null
    private var factBytes: ByteArray? = null
    private var installationReceipt: LocalBranchInstallationReceiptV2? = null
    private var throttleEvidence: RuntimeThrottleEvidenceV1? = null
    private var authenticationCapture: RuntimeAuthenticationCapture? = null
    private var authenticationEvidence: RuntimeAuthenticationEvidenceV1? = null
    private var commandAuthorizationCapture: RuntimeCommandAuthorizationCapture? = null
    private var approvalAttemptCompletion = false
    private var approvalIssuanceCapture: RuntimeActionApprovalIssuanceCapture? = null
    private var approvalIssuanceEvidence: RuntimeActionApprovalIssuanceEvidenceV1? = null
    private var approvalRetirementCapture: RuntimeActionApprovalRetirementCapture? = null
    private var approvalRetirementEvidence: RuntimeActionApprovalRetirementEvidenceV1? = null
    private var serialPublicationCapture: RuntimeSerialPublicationRequestCapture? = null
    private var serialPublicationEvidence: RuntimeSerialPublicationRequestEvidenceV1? = null
    private var serialPublicationOrigin: NativeSerialPrivateRequestCapsuleV1? = null
    private var masterLifecycleEvidence: RuntimeMasterLifecycleEvidenceV1? = null
    private var approvalUseCapture: RuntimeActionApprovalUseCapture? = null
    private var sessionRevocationCapture: RuntimeSessionRevocationHistoryCapture? = null
    private var sessionRevocationEvidence: RuntimeSessionRevocationHistoryEvidence? = null
    private var administrationCancellationCapture: RuntimeAdministrationCancellationCapture? = null
    private var administrationCancellationEvidence: RuntimeAdministrationCancellationEvidenceV1? = null
    private var deviceRevocationCapture: RuntimeDeviceRevocationCapture? = null
    private var deviceRevocationEvidence: RuntimeDeviceRevocationEvidenceV1? = null
    private var additionalDeviceRevocationCapture: RuntimeDeviceRevocationCaptureV2? = null
    private var additionalDeviceRevocationEvidence: RuntimeDeviceRevocationEvidenceV2? = null
    private var employeeProvisioningCapture: RuntimeEmployeeProvisioningCapture? = null
    private var employeeProvisioningEvidence: LocalEmployeeEnrollmentEffectEvidence? = null
    private var employeeProvisioningNative: NativeEmployeeProvisioningFrame? = null
    private var employeePolicyActivationCapture: RuntimeEmployeePolicyActivationCapture? = null
    private var employeePolicyActivationEvidence: LocalEmployeePolicyActivationEffectEvidence? = null
    private var employeePolicyActivationNative: NativeEmployeePolicyActivationFrame? = null
    private var deviceAdmissionCapture: RuntimeDeviceAdmissionCapture? = null
    private var deviceAdmissionEvidence: LocalDeviceAdmissionEffectEvidence? = null
    private var deviceAdmissionNative: NativeDeviceAdmissionFrame? = null
    private var nativeOriginalCapture: RuntimeNativeOriginalImportCapture? = null
    private var finalDeadline: NativeHistoryFinalDeadlineDeclaration? = null
    private var finalDeadlineCaptured = false
    private var businessPublications: NativeCompanyBusinessPublicationObservation? = null
    private var receiptMembershipDelta: RuntimeRetailCustomerReceiptMembershipDelta? = null
    private var receiptMembershipChild: NativeHistoryRecordedHeadV2? = null
    private var receiptMembershipSelections: MutableList<RuntimeRetailCustomerReceiptMembershipSelection>? = mutableListOf()
    private var entered = false

    internal suspend fun enterTransaction(context: CoroutineContext) {
        check(phase == Phase.NEW && !entered && connection.inTransaction())
        owner.requireFrame(this, connection)
        context.ensureActive(); coroutineContext = context; entered = true
        check(scalar("SELECT count(*) FROM runtime_history_write_frame_v2") == 0L &&
            scalar("SELECT count(*) FROM runtime_history_capture_v2") == 0L)
        requireImageCensus()
        val parent = native.original.record.parent
        if (parent == null) check(scalar("SELECT count(*) FROM runtime_history_head_v2") == 0L)
        else connection.usePrepared("SELECT sequence,eventDigest,commandEpoch,commandSequence,commandDigest FROM runtime_history_head_v2 WHERE singleton=1") {
            check(it.step() && it.getLong(0) == parent.historySequence && it.getText(1) == parent.historyDigest.value &&
                it.getLong(2) == parent.commandEpoch && it.getLong(3) == parent.commandSequence && it.getText(4) == parent.commandDigest.value && !it.step())
        }
        // This frame row is removed before native pending/final sample. No persistent boundary
        // contains a writable frame, capture rows or TEMP-only evidence required for replay.
        execute("INSERT INTO runtime_history_write_frame_v2 VALUES(1,?,?,?,?,?,?,?,?)", listOf(native.frameId.value,
            native.frameId.value,purpose.tag,if (parent == null) 1 else 2,parent?.historySequence ?: 0L,
            (parent?.historyDigest ?: native.original.record.allocation.digest).value,(parent?.historySequence ?: 0L)+1,
            requestDigest.value))
        phase = Phase.BODY
    }
    internal fun bindView(value: TransactionView, context: CoroutineContext, principal: LocalEmployeePrincipal) {
        body(); check(view == null || view === value); check(context == coroutineContext)
        check(employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null)
        check(principal.scope==coordinates.scope && (writerActor==null || writerActor==principal.actorId))
        view = value; writerActor=principal.actorId
    }
    internal fun permitsReservationActor(actor: DomainId): Boolean =
        phase==Phase.BODY && view!=null && originalInvocation==null && writerActor==actor
    internal suspend fun reservedOperation(operation: OperationId): FutureOperationReservation? {
        body(); check(writerActor!=null && originalInvocation==null)
        // The original view performs storage-class/byte-count/hash preflight and records
        // the actual dependency; the immutable reservation codec checks every fixed field.
        return checkNotNull(view).reservedOperation(operation)
    }
    internal fun bindSecurity(value: RoomLocalSecurityTransaction) {
        body(); check(securityTransaction == null); securityTransaction = value
    }
    internal fun captureCommand(request: MutationRequest, principal: LocalEmployeePrincipal, digest: PayloadDigest,
        affected: Set<RecoveryResourceKey>, applied: Boolean, requirement: AuthorizationRequirement,
        authorization: AuthorizedAction, mustCommitBefore: UtcInstant?, foodCheckScalarRefusal: Boolean = false) {
        body(); requireNoApprovalRetirement(); check(principal.scope == coordinates.scope && writerActor == principal.actorId &&
            originalInvocation == null && commandAuthorizationCapture == null && finalDeadline == null && !finalDeadlineCaptured &&
            sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null)
        check(applied || mustCommitBefore == null)
        check(!foodCheckScalarRefusal || !applied && request.command == StockStreamingCommandsV2.FOOD)
        check(applied || request.command != StockStreamingCommandsV2.FOOD || foodCheckScalarRefusal)
        commandAuthorizationCapture = RuntimeCommandAuthorizationCapture.capture(request, principal, digest, requirement,
            authorization, checkNotNull(view).captureRuntimeCommandAuthorization(this, principal), applied)
        if (request.actionApprovalId != null) {
            val approval = checkNotNull(view).captureRuntimeApprovalUse(this, principal, applied)
            check(approvalUseCapture === approval && approval.issued.record.approvalId == request.actionApprovalId &&
                approval.issued.record.requirement == requirement && authorization.approverId == approval.approver.actorId)
        } else check(approvalUseCapture == null)
        requestId = DomainId(request.operationId.value); operationId = requestId; actorId = principal.actorId; requestDigest = digest
        purpose = if (applied) RuntimeWritePurposeV2.COMMAND_APPLY else RuntimeWritePurposeV2.COMMAND_REJECT
        finalDeadline = mustCommitBefore?.let { NativeHistoryFinalDeadlineDeclaration(requestId, checkNotNull(operationId), digest, it) }
        resources = affected.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })).map {
            RuntimeHistoryCodecV2.originalResource(RecoveryResourceSelector.Resource(it), true, digest)
        }
        recordDependency(listOf("command",request.command.name,request.command.version.toString(),principal.actorId.value,
            principal.authenticationId.value,principal.deviceId.value,principal.sessionId.value,principal.generation.value.toString(),digest.value))
        if (foodCheckScalarRefusal) {
            // Only the kernel's real closed FOOD rejection opt-in supplies this flag. The
            // existing dependency format distinguishes newly qualified refusals from old
            // incompatible originals; it supplies no source, employee or current authority.
            recordDependency(listOf(RuntimeHistoricalFoodCheckRejectionProfile.MARKER, request.operationId.value,
                digest.value, originalParent.historySequence.toString(), originalParent.historyDigest.value))
        }
    }
    internal fun captureSecurityPurpose(value: RuntimeWritePurposeV2, actor: DomainId? = null, request: DomainId? = null,
        contentDigest: PayloadDigest? = null) {
        body(); requireNoApprovalRetirement()
        check(sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null)
        check(authenticationCapture == null && (authenticationEvidence == null ||
            value == purpose && actor == actorId && request == requestId && contentDigest == null))
        if (throttleEvidence != null && value != purpose) {
            throttleEvidence = null
            requestDigest = native.original.recordDigest
        }
        purpose = value; actorId = actor; request?.let { requestId = it }
        contentDigest?.let { requestDigest = it }
    }
    internal fun captureThrottle(evidence: RuntimeThrottleEvidenceV1) {
        body(); requireNoApprovalRetirement(); check(throttleEvidence == null && evidence.scope == coordinates.scope && operationId == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null)
        evidence.validate()
        throttleEvidence = evidence
        purpose = evidence.purpose; actorId = evidence.actorId; requestId = evidence.attemptId
        val bytes = RuntimeThrottleEvidenceCodec.encode(evidence)
        try { requestDigest = sha256(bytes) } finally { bytes.fill(0) }
    }
    internal fun captureApprovalAttempt() {
        body(); requireNoApprovalRetirement(); check(!approvalAttemptCompletion && approvalIssuanceCapture == null && approvalIssuanceEvidence == null &&
            approvalUseCapture == null && commandAuthorizationCapture == null && originalInvocation == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null)
        val throttle = checkNotNull(throttleEvidence)
        check(throttle.outcome == CredentialAttemptOutcome.MATCH && throttle.denial == null)
        approvalAttemptCompletion = true
    }
    internal fun beginApprovalIssuance(capture: RuntimeActionApprovalIssuanceCapture) {
        body(); requireNoApprovalRetirement()
        check(approvalAttemptCompletion && securityTransaction != null && approvalIssuanceCapture == null &&
            approvalIssuanceEvidence == null && approvalUseCapture == null && operationId == null &&
            authenticationCapture == null && authenticationEvidence == null && commandAuthorizationCapture == null &&
            sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && originalInvocation == null &&
            capture.requestor.scope == coordinates.scope && capture.images.isEmpty())
        val throttle = checkNotNull(throttleEvidence)
        check(throttle.actorId == capture.approver.actorId && throttle.deviceId == capture.approver.deviceId &&
            throttle.dependencies.account == capture.approver.dependencies.account &&
            throttle.dependencies.policy == capture.approver.dependencies.policy &&
            throttle.dependencies.admission == capture.approver.dependencies.admission)
        approvalIssuanceCapture = capture
        purpose = RuntimeWritePurposeV2.APPROVAL_ISSUE; actorId = capture.requestor.actorId
        requestId = capture.approval.record.approvalId; requestDigest = checkNotNull(capture.approval.issueRequestDigest)
        resources = emptyList()
    }
    internal fun completeApprovalIssuance(capture: RuntimeActionApprovalIssuanceCapture) {
        body(); check(approvalIssuanceCapture === capture && approvalIssuanceEvidence == null)
        approvalIssuanceEvidence = capture.evidence(checkNotNull(throttleEvidence), native.original.recordDigest)
        approvalIssuanceCapture = null
    }
    /** Complete native startup admission and this owner's contiguous, serialized loans are
     * prerequisites even for a nullable tombstone read. A copied or omitted native tail cannot
     * acquire this actual frame; no point-query fallback manufactures an unused approval. */
    internal fun requireApprovalRetirementHistory() {
        body()
        check(owner.permitsHeldResults && view != null && writerActor != null && originalInvocation == null &&
            native.original.record.pending == null && native.original.record.receipt != null &&
            native.original.record.parent != null && native.original.record.parent == owner.originalRecord.record.parent &&
            native.original.record.allocation.digest == owner.originalRecord.record.allocation.digest)
    }

    /** Complete membership DATA at this exact unmutated original cut. Current authorization,
     * every selected aggregate and immutable paid-sale/original joins remain separate reads. */
    internal suspend fun retailCustomerReceiptEventMembership(caller: TransactionView,
        saleId: DomainId): List<RuntimeRetailCustomerReceiptEventLocator>? {
        body()
        val actual = currentCoroutineContext(); actual.ensureActive()
        check(actual == coroutineContext && connection.inTransaction() && view === caller &&
            writerActor != null && caller.scope == coordinates.scope)
        val retained = owner.originalRecord
        val parent = native.original.record.parent
        if (!owner.permitsHeldResults || parent == null || originalInvocation != null || originalLease != null ||
            originalStore != null || nativeOriginalCapture != null || commandAuthorizationCapture != null ||
            operationId != null || actorId != null || installationReceipt != null || definitionBytes != null || factBytes != null ||
            native.original.record.pending != null || native.original.record.receipt == null ||
            native.original.record.firstChild == null || native.original.record.issuerAcknowledgement == null ||
            native.original.lifetimeId != retained.lifetimeId || native.original.recordDigest != retained.recordDigest ||
            native.original.record.allocation.digest != retained.record.allocation.digest || parent != retained.record.parent ||
            parent.commandEpoch != originalAuthority.epoch.value) return null
        if (scalar("SELECT count(*) FROM runtime_history_capture_v2") != 0L ||
            scalar("SELECT count(*) FROM runtime_history_write_frame_v2") != 1L) return null
        requireImageCensus()
        body(); check(currentCoroutineContext() == coroutineContext && view === caller)
        return owner.retailCustomerReceiptEventMembership(this, saleId)
    }

    /** The current frame, not the caller, retains each bounded selection reservation.
     * Delivered locator lists are immutable DATA; closing drops our loan without altering them. */
    internal suspend fun retainRetailCustomerReceiptMembershipSelection(
        selection: RuntimeRetailCustomerReceiptMembershipSelection): List<RuntimeRetailCustomerReceiptEventLocator> {
        body()
        check(currentCoroutineContext() == coroutineContext && connection.inTransaction() &&
            view != null && writerActor != null)
        val entries = selection.entries
        checkNotNull(receiptMembershipSelections).add(selection)
        return entries
    }

    /** Source-only native refusal read on this actual, still unmutated employee writer loan.
     * A complete original startup and serialized custody are necessary eligibility gates,
     * not a substitute for the full immutable tail/row/original authentication below. */
    internal suspend fun foodCheckRejectedOriginalSources(caller: TransactionView,
        operationId: OperationId): RuntimeHistoricalFoodCheckRejectedSources? {
        requireClosedRejectedSourceRead(caller)
        val reader = RoomClosedRejectedOriginalSources(this, caller, operationId)
        return try {
            reader.readFoodCheck()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        finally { reader.clear(); requireClosedRejectedSourceRead(caller) }
    }

    /** Separate closed table-refusal lane; no request-selected profile or FOOD marker.
     * Literal original ownership sources remain DATA pending the installed business join. */
    internal suspend fun foodTableAssignmentRejectedOriginalSources(caller: TransactionView,
        operationId: OperationId): RuntimeHistoricalFoodTableAssignmentRejectedSources? {
        requireClosedRejectedSourceRead(caller)
        val reader = RoomClosedRejectedOriginalSources(this, caller, operationId)
        return try {
            reader.readFoodTableAssignment()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        finally { reader.clear(); requireClosedRejectedSourceRead(caller) }
    }

    /** Separate ordinary payment-refusal selection over the shared bounded native traversal.
     * It supplies actual observed BEFORE literals only; no FOOD marker or financial grant. */
    internal suspend fun foodPaymentAlreadyAppliedOriginalSources(caller: TransactionView,
        operationId: OperationId): RuntimeHistoricalFoodPaymentAlreadyAppliedSources? {
        requireClosedRejectedSourceRead(caller)
        val reader = RoomClosedRejectedOriginalSources(this, caller, operationId)
        return try {
            reader.readFoodPaymentAlreadyApplied()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        finally { reader.clear(); requireClosedRejectedSourceRead(caller) }
    }

    internal suspend fun requireClosedRejectedSourceRead(caller: TransactionView) {
        body(); requireNoApprovalRetirement()
        val actual = currentCoroutineContext()
        actual.ensureActive()
        val retained = owner.originalRecord
        check(actual == coroutineContext && connection.inTransaction() && view === caller && writerActor != null &&
            caller.scope == coordinates.scope && owner.permitsHeldResults && originalInvocation == null &&
            originalLease == null && originalStore == null && native.original.record.pending == null &&
            native.original.record.receipt != null && native.original.record.issuerAcknowledgement != null &&
            native.original.record.firstChild != null && native.original.record.parent != null &&
            native.original.lifetimeId == retained.lifetimeId && native.original.recordDigest == retained.recordDigest &&
            native.original.record.allocation.digest == retained.record.allocation.digest &&
            native.original.record.parent == retained.record.parent && originalParent.commandEpoch == originalAuthority.epoch.value &&
            commandAuthorizationCapture == null && operationId == null && actorId == null &&
            authenticationCapture == null && authenticationEvidence == null && throttleEvidence == null &&
            approvalUseCapture == null && !approvalAttemptCompletion && approvalIssuanceCapture == null && approvalIssuanceEvidence == null &&
            sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            employeeProvisioningCapture == null && employeeProvisioningEvidence == null && employeeProvisioningNative == null &&
            employeePolicyActivationCapture == null && employeePolicyActivationEvidence == null && employeePolicyActivationNative == null &&
            deviceAdmissionCapture == null && deviceAdmissionEvidence == null && deviceAdmissionNative == null &&
            nativeOriginalCapture == null && installationReceipt == null && definitionBytes == null && factBytes == null &&
            finalDeadline == null && !finalDeadlineCaptured)
        check(scalar("SELECT count(*) FROM runtime_history_capture_v2") == 0L &&
            scalar("SELECT count(*) FROM runtime_history_write_frame_v2") == 1L)
        requireImageCensus()
    }

    /** Closed fixed-table wrappers for the bounded native literal reader. The caller cannot
     * supply SQL, a table name, a column list or a historical point-query facade. */
    internal suspend fun foodCheckHistoryHead(): RuntimeHistoryHeadRowV2 =
        foodCheckFixedPhysical(RuntimeHistoryTableV2.HEAD, " FROM runtime_history_head_v2 WHERE singleton=1", emptyList()) { it.head() }
    internal suspend fun foodCheckHistoryEvent(sequence: Long): RuntimeHistoryEventRowV2 =
        foodCheckFixedPhysical(RuntimeHistoryTableV2.EVENT, " FROM runtime_history_event_v2 WHERE sequence=?", listOf(sequence)) { it.event() }
    internal suspend fun foodCheckHistoryVersion(sequence: Long, ordinal: Int): RuntimeHistoryRowVersionRowV2 =
        foodCheckFixedPhysical(RuntimeHistoryTableV2.ROW_VERSION,
            " FROM runtime_history_row_version_v2 WHERE sequence=? AND ordinal=?", listOf(sequence, ordinal)) { it.version() }
    internal suspend fun foodCheckHistoryChunk(sequence: Long, ordinal: Int): RuntimeHistoryChunkRowV2 =
        foodCheckFixedPhysical(RuntimeHistoryTableV2.CHUNK,
            " FROM runtime_history_chunk_v2 WHERE sequence=? AND ordinal=?", listOf(sequence, ordinal)) { it.chunk() }
    internal suspend fun foodCheckHistoryTip(table: RuntimeHistoryTableV2, key: ByteArray): RuntimeHistoryRowTipRowV2 {
        check(table.application)
        return foodCheckFixedPhysical(RuntimeHistoryTableV2.ROW_TIP,
            " FROM runtime_history_row_tip_v2 WHERE tableTag=? AND `key`=?", listOf(table.tag, key)) { it.tip() }
    }
    internal suspend fun foodCheckHistoryCurrentRow(table: RuntimeHistoryTableV2, rowId: Long): NativeHistoryPhysicalRowV2 {
        body(); check(currentCoroutineContext() == coroutineContext && table.application)
        // Table and columns derive only from the native registry and authenticated descriptor.
        return readPhysicalRow(table, "rowid", " FROM `${table.sqlName}` WHERE rowid=?", listOf(rowId))
    }
    internal suspend fun foodCheckHistoryCensus(): RoomFoodCheckNativeCensus {
        body(); check(currentCoroutineContext() == coroutineContext)
        return RoomFoodCheckNativeCensus(scalar("SELECT count(*) FROM runtime_history_head_v2"),
            scalar("SELECT count(*) FROM runtime_history_event_v2"), scalar("SELECT count(*) FROM runtime_history_row_version_v2"),
            scalar("SELECT count(*) FROM runtime_history_chunk_v2"), scalar("SELECT count(*) FROM runtime_history_row_tip_v2"),
            RuntimeHistoryTableV2.entries.filter { it.application }.associateWith { scalar("SELECT count(*) FROM `${it.sqlName}`") })
    }
    private suspend fun <T> foodCheckFixedPhysical(table: RuntimeHistoryTableV2, suffix: String, values: List<Any?>,
        decode: (RuntimeHistoryRowViewV2) -> T): T {
        body(); check(currentCoroutineContext() == coroutineContext)
        val row = RuntimeHistoryRowViewV2(readPhysicalRow(table, "rowid", suffix, values))
        return try { decode(row) } finally { row.clear() }
    }
    /** Native decisions, not an empty SQL query, enumerate every issued approval. The
     * complete original startup census and this serialized before cut remain prerequisites. */
    internal suspend fun approvalIssuanceOrigins(): List<RuntimeApprovalIssuanceOrigin> {
        requireApprovalRetirementHistory()
        val origins = mutableListOf<RuntimeApprovalIssuanceOrigin>()
        var retainedEvidenceBytes = 0L
        for (decision in native.original.record.protectedFinalDecisions) {
            val recorded = readRetirementHeader(decision.child.historySequence)
            val header = recorded.first
            check(header.sequence <= originalParent.historySequence && header.scope == scopeKey &&
                header.storageId == coordinates.storageInstanceId && header.assignmentId == coordinates.assignmentId &&
                header.originalAllocationDigest == native.original.record.allocation.digest &&
                decision.scope == coordinates.scope && decision.originalAllocationDigest == native.original.record.allocation.digest &&
                decision.child == NativeHistoryRecordedHeadV2(header.sequence, recorded.second, header.installationGenesisDigest,
                    header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment))
            val selected = readProtectedDecisionIntent(header, decision)
            if (selected.intent.purposeTag != RuntimeWritePurposeV2.APPROVAL_ISSUE.tag) continue
            check(header.changeCount == 4 && origins.size < RuntimeBusinessHistoryJoin.MAX_COMMANDS)
            val evidence = checkNotNull(selected.approvalIssuance)
            retainedEvidenceBytes = historyAddV2(retainedEvidenceBytes, header.factBytes.toLong() * 2L,
                RuntimeBusinessHistoryJoin.MAX_RETAINED_BYTES)
            val id = decision.declaration.originalRequestId
            val key = runtimeSecurityKey(RuntimeHistoryTableV2.APPROVAL, listOf(scopeKey, id.value))
            var tip: RuntimeHistoryRowTipRowV2? = null
            var row: NativeHistoryPhysicalRowV2? = null
            try {
                tip = readTip(RuntimeHistoryTableV2.APPROVAL, key)
                val retained = checkNotNull(tip)
                check(retained.key.contentEquals(key) && retained.tombstone == 0 && retained.version in 1L..2L &&
                    retained.sequence in header.sequence..originalParent.historySequence && retained.targetRowId != null && retained.rowDigest != null)
                row = readPhysicalRow(RuntimeHistoryTableV2.APPROVAL, "rowid",
                    " FROM protected_local_action_approval WHERE scope=? AND approvalId=?", listOf(scopeKey, id.value))
                check(row.rowId == retained.targetRowId && RuntimeHistoryRowCodecV2.rowDigest(row).value == retained.rowDigest)
                origins += RuntimeApprovalIssuanceOrigin(header.sequence, evidence)
            } finally { key.fill(0); tip?.key?.fill(0); row?.clear() }
        }
        check(origins.map { it.approvalId }.distinct().size == origins.size &&
            origins.map { it.historySequence }.distinct().size == origins.size &&
            scalar("SELECT count(*) FROM protected_local_action_approval") == origins.size.toLong() &&
            scalar("SELECT count(*) FROM runtime_history_row_tip_v2 WHERE tableTag=${RuntimeHistoryTableV2.APPROVAL.tag}") == origins.size.toLong())
        return origins.sortedBy { it.historySequence }
    }
    /** Native immutable version/tip census must agree with the actual protected audit. This
     * includes the absent-row case: deletion of only the audit never makes the grant usable.
     * Full deletion of the tail is refused by original complete native startup admission. */
    internal suspend fun verifyApprovalRetirementHistory(value: ProtectedLocalActionApproval, event: LocalAuthEvent?): Long? {
        requireApprovalRetirementHistory()
        check(value.record.requirement.scope == coordinates.scope)
        val retirementId = actionApprovalRetirementId(coordinates.scope, value.record.approvalId)
        val originalApprovalDigest = runtimeApprovalDigest(value)
        val nativeRetirements = native.original.record.protectedFinalDecisions.filter {
            it.scope == coordinates.scope && it.declaration.originalRequestId == retirementId &&
                it.declaration.originalRequestDigest == originalApprovalDigest
        }
        val key = runtimeSecurityKey(RuntimeHistoryTableV2.AUTH_EVENT, listOf(scopeKey, retirementId.value))
        var tip: RuntimeHistoryRowTipRowV2? = null
        try {
            tip = readTip(RuntimeHistoryTableV2.AUTH_EVENT, key)
            val versions = connection.usePrepared("SELECT count(*) FROM runtime_history_row_version_v2 WHERE tableTag=? AND `key`=?") {
                it.bindLong(1, RuntimeHistoryTableV2.AUTH_EVENT.tag.toLong()); it.bindBlob(2, key)
                check(it.step() && it.getColumnType(0) == 1)
                it.getLong(0).also { _ -> check(!it.step()) }
            }
            val currentCapture = approvalRetirementCapture?.takeIf { it.approval.record.approvalId == value.record.approvalId }
            val currentEvidence = approvalRetirementEvidence?.takeIf { it.approvalId == value.record.approvalId }
            if (event == null) {
                check(tip == null && versions == 0L && nativeRetirements.isEmpty() && currentCapture == null && currentEvidence == null)
                return null
            }
            requireActionApprovalRetirementEvent(value, event)
            val currentEvent = currentCapture?.event() ?: currentEvidence?.event()
            if (currentEvent != null) {
                // The only not-yet-recorded event permitted is this frame's exact one-audit
                // capture, already sealed through the actual owned protected writer.
                check(tip == null && versions == 0L && nativeRetirements.isEmpty() && sameActionApprovalRetirementEvent(currentEvent, event) &&
                    (currentCapture?.approval?.let(::runtimeApprovalDigest) ?: currentEvidence?.value()?.let(::runtimeApprovalDigest)) == originalApprovalDigest &&
                    scalar("SELECT count(*) FROM runtime_history_capture_v2") == 1L)
                val expected = currentCapture?.images?.singleOrNull() ?: currentEvidence?.images?.singleOrNull()
                    ?: error("Retirement image missing")
                check(expected == RuntimeApprovalImageV1.capture(ProtectedLocalRecordKey.AuthEvent(coordinates.scope,
                    retirementId), Revision(0), LocalAuthenticationCodec.encodeEvent(event)))
                val capture = readCapture(1L)
                var image: NativeHistoryPhysicalRowV2? = null
                var current: NativeHistoryPhysicalRowV2? = null
                try {
                    check(capture.frameId == native.frameId.value && capture.tableTag == RuntimeHistoryTableV2.AUTH_EVENT.tag &&
                        capture.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && capture.beforeRowId == null && capture.afterRowId != null)
                    image = readImage(RuntimeHistoryTableV2.AUTH_EVENT, capture.captureOrdinal, 1)
                    requireCapturedKeys(capture, null, image)
                    val actualKey = RuntimeHistoryRowCodecV2.key(image)
                    try { check(actualKey.contentEquals(key)) } finally { actualKey.fill(0) }
                    current = readRetirementRow(retirementId)
                    check(current.rowId == capture.afterRowId && current.rowId == image.rowId &&
                        RuntimeHistoryRowCodecV2.rowDigest(current) == RuntimeHistoryRowCodecV2.rowDigest(image))
                } finally { image?.clear(); current?.clear(); clearCapture(capture) }
                return null // Current tentative capture is not a reusable recorded-retirement origin.
            }
            val retained = checkNotNull(tip)
            check(versions == 1L && retained.tableTag == RuntimeHistoryTableV2.AUTH_EVENT.tag && retained.key.contentEquals(key) &&
                retained.version == 1L && retained.tombstone == 0 && retained.sequence in 2L..originalParent.historySequence &&
                retained.ordinal == 0 && retained.targetRowId != null && retained.rowDigest != null)
            val version = readVersion(retained.sequence, retained.ordinal)
            var current: NativeHistoryPhysicalRowV2? = null
            try {
                check(version.tableTag == RuntimeHistoryTableV2.AUTH_EVENT.tag && version.key.contentEquals(key) &&
                    version.version == 1L && version.previousSequence == null && version.previousOrdinal == null && version.previousVersion == null &&
                    version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && version.beforeRowId == null && version.beforeDigest == null &&
                    version.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag && version.imageOffset == null && version.imageBytes == null &&
                    version.afterRowId == retained.targetRowId && version.afterDigest == retained.rowDigest)
                val decision = native.original.record.protectedFinalDecisions.singleOrNull { it.child.historySequence == retained.sequence }
                    ?: error("Original retirement decision missing")
                check(nativeRetirements.singleOrNull() === decision)
                val recorded = readRetirementHeader(retained.sequence)
                val header = recorded.first
                readProtectedDecisionIntent(header, decision)
                check(header.purpose == RuntimeWritePurposeV2.APPROVAL_RETIRE && header.changeCount == 1 &&
                    header.sequence == retained.sequence && header.scope == scopeKey && header.storageId == coordinates.storageInstanceId &&
                    header.originalAllocationDigest == native.original.record.allocation.digest && header.assignmentId == coordinates.assignmentId &&
                    decision.scope == coordinates.scope && decision.originalAllocationDigest == native.original.record.allocation.digest &&
                    decision.child == NativeHistoryRecordedHeadV2(header.sequence, recorded.second, header.installationGenesisDigest,
                        header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment) &&
                    decision.declaration.originalRequestId == event.eventId && decision.declaration.originalRequestDigest == event.contentDigest &&
                    decision.declaration.actorId == event.operatorId && decision.declaration.sessionId == event.sessionId &&
                    decision.declaration.lastObservedAt == event.at)
                current = readRetirementRow(retirementId)
                check(current.rowId == retained.targetRowId && RuntimeHistoryRowCodecV2.rowDigest(current).value == retained.rowDigest)
            } finally { version.key.fill(0); current?.clear() }
            return retained.sequence
        } finally { tip?.key?.fill(0); key.fill(0) }
    }
    internal fun beginApprovalRetirement(capture: RuntimeActionApprovalRetirementCapture) {
        requireApprovalRetirementHistory(); requireNoApprovalRetirement()
        check(securityTransaction != null && writerActor == capture.requestor.actorId &&
            capture.requestor.scope == coordinates.scope && capture.approval.record.requestorId == writerActor &&
            capture.images.isEmpty() && operationId == null && resources.isEmpty() &&
            !approvalAttemptCompletion && approvalIssuanceCapture == null && approvalIssuanceEvidence == null && approvalUseCapture == null &&
            throttleEvidence == null && authenticationCapture == null && authenticationEvidence == null && commandAuthorizationCapture == null &&
            sessionRevocationCapture == null && sessionRevocationEvidence == null && administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null && employeeProvisioningCapture == null && employeeProvisioningEvidence == null &&
            employeeProvisioningNative == null && employeePolicyActivationCapture == null && deviceAdmissionCapture == null && employeePolicyActivationEvidence == null && deviceAdmissionEvidence == null && employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            nativeOriginalCapture == null && installationReceipt == null && finalDeadline == null && !finalDeadlineCaptured &&
            definitionBytes == null && factBytes == null)
        val event = capture.event()
        requireActionApprovalRetirementEvent(capture.approval, event)
        approvalRetirementCapture = capture
        purpose = RuntimeWritePurposeV2.APPROVAL_RETIRE; actorId = capture.requestor.actorId
        requestId = event.eventId; requestDigest = runtimeApprovalDigest(capture.approval)
        resources = emptyList()
    }
    internal fun requireApprovalRetirementEvent(event: LocalAuthEvent) {
        requireApprovalRetirementHistory()
        val capture = checkNotNull(approvalRetirementCapture)
        check(approvalRetirementEvidence == null && capture.images.isEmpty() && sameActionApprovalRetirementEvent(capture.event(), event) &&
            event.eventId == requestId && event.contentDigest == requestDigest && purpose == RuntimeWritePurposeV2.APPROVAL_RETIRE)
        requireActionApprovalRetirementEvent(capture.approval, event)
    }
    internal fun completeApprovalRetirement(capture: RuntimeActionApprovalRetirementCapture) {
        requireApprovalRetirementHistory()
        check(approvalRetirementCapture === capture && approvalRetirementEvidence == null)
        approvalRetirementEvidence = capture.evidence(native.original.recordDigest, originalParent)
        approvalRetirementCapture = null; capture.images.clear()
    }
    internal fun beginSerialPublication(capture: RuntimeSerialPublicationRequestCapture) {
        requireApprovalRetirementHistory(); requireNoApprovalRetirement()
        check(securityTransaction != null && writerActor == capture.actorId && capture.scope == coordinates.scope &&
            capture.images.isEmpty() && operationId == null && resources.isEmpty() &&
            !approvalAttemptCompletion && approvalIssuanceCapture == null && approvalIssuanceEvidence == null && approvalUseCapture == null &&
            throttleEvidence == null && authenticationCapture == null && authenticationEvidence == null && commandAuthorizationCapture == null &&
            sessionRevocationCapture == null && sessionRevocationEvidence == null && administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null && employeeProvisioningCapture == null && employeeProvisioningEvidence == null &&
            employeeProvisioningNative == null && employeePolicyActivationCapture == null && deviceAdmissionCapture == null && employeePolicyActivationEvidence == null && deviceAdmissionEvidence == null && employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            nativeOriginalCapture == null && installationReceipt == null && finalDeadline == null && !finalDeadlineCaptured &&
            definitionBytes == null && factBytes == null)
        serialPublicationCapture = capture
        purpose = RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST
        actorId = capture.actorId; requestId = capture.requestId; requestDigest = capture.semanticDigest
    }
    internal fun completeSerialPublication(capture: RuntimeSerialPublicationRequestCapture) {
        body()
        check(serialPublicationCapture === capture && serialPublicationEvidence == null && serialPublicationOrigin == null)
        val evidence = capture.evidence()
        evidence.validate()
        check(evidence.matchesEvent(capture.event()) && evidence.images.size == 1)
        serialPublicationOrigin = capture.originCapsule()
        serialPublicationEvidence = evidence
        serialPublicationCapture = null
    }
    internal fun requireSerialPublicationEvent(event: LocalAuthEvent) {
        body()
        val capture = checkNotNull(serialPublicationCapture)
        check(serialPublicationEvidence == null && capture.images.isEmpty() &&
            purpose == RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST && event.eventId == requestId &&
            event.contentDigest == requestDigest)
        val expected = LocalAuthenticationCodec.encodeEvent(capture.event())
        try {
            val actual = LocalAuthenticationCodec.encodeEvent(event)
            try { check(actual.contentEquals(expected)) } finally { actual.fill(0) }
        } finally { expected.fill(0) }
    }

    /** Attach exact history evidence before the protected lifecycle event image is inserted. */
    internal fun captureMasterLifecycleEvidence(value: RuntimeMasterLifecycleEvidenceV1) {
        body(); requireNoApprovalRetirement()
        check(masterLifecycleEvidence == null && securityTransaction != null && writerActor == value.actorId &&
            operationId == null && resources.isEmpty() && definitionBytes == null && factBytes == null &&
            authenticationCapture == null && authenticationEvidence == null && commandAuthorizationCapture == null &&
            sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            employeeProvisioningCapture == null && employeeProvisioningEvidence == null &&
            employeeProvisioningNative == null && employeePolicyActivationCapture == null && deviceAdmissionCapture == null &&
            employeePolicyActivationEvidence == null && deviceAdmissionEvidence == null &&
            employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            approvalIssuanceCapture == null && approvalIssuanceEvidence == null &&
            approvalRetirementCapture == null && approvalRetirementEvidence == null &&
            approvalUseCapture == null && throttleEvidence == null && nativeOriginalCapture == null &&
            serialPublicationCapture == null && serialPublicationEvidence == null && serialPublicationOrigin == null &&
            installationReceipt == null && originalInvocation == null && finalDeadline == null && !finalDeadlineCaptured)
        value.validate()
        check(value.scope == coordinates.scope && value.installationId == coordinates.installationId)
        masterLifecycleEvidence = value
        purpose = RuntimeWritePurposeV2.ROLE_TRANSITION
        actorId = value.actorId
        requestId = value.eventId
        requestDigest = value.requestDigest
    }

    /** Enumerate only the current actor's actual retained origins, never ordinary SQL rows
     * or inferred native publications. Every selected capsule must pass its exact row join. */
    internal suspend fun serialPublicationOrigins(actor: DomainId): List<RuntimeSerialPublicationRequestEvidenceV1> {
        requireApprovalRetirementHistory(); requireNoApprovalRetirement()
        check(writerActor == actor)
        val origins = mutableListOf<RuntimeSerialPublicationRequestEvidenceV1>()
        var retainedBytes = 0L
        for (decision in native.original.record.protectedFinalDecisions) {
            if (decision.declaration.actorId != actor) continue
            val recorded = readRetirementHeader(decision.child.historySequence)
            val header = recorded.first
            check(header.sequence in 2L..originalParent.historySequence && header.scope == scopeKey &&
                header.storageId == coordinates.storageInstanceId && header.assignmentId == coordinates.assignmentId &&
                header.originalAllocationDigest == native.original.record.allocation.digest && decision.scope == coordinates.scope &&
                decision.originalAllocationDigest == native.original.record.allocation.digest &&
                decision.child == NativeHistoryRecordedHeadV2(header.sequence, recorded.second, header.installationGenesisDigest,
                    header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment))
            val classified = readProtectedDecisionIntent(header, decision)
            if (classified.intent.purposeTag != RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST.tag) continue
            val selected = checkNotNull(classified.serialPublication)
            if (selected.requestValue().operation != NativeSerialPrivatePublicationOperationV1.ORIGIN_PREPARE) continue
            retainedBytes = historyAddV2(retainedBytes, header.factBytes.toLong() * 2L, RuntimeBusinessHistoryJoin.MAX_RETAINED_BYTES)
            check(origins.size < CompanyBusinessPublicationProfileV1.MAXIMUM_PUBLICATIONS)
            val exact = checkNotNull(readSerialPublicationRequest(decision.declaration.originalRequestId))
            check(exact == selected && origins.none { it.requestValue().requestId == exact.requestValue().requestId })
            origins += exact
        }
        return origins.toList()
    }

    /** The actual retained native decision selects one bounded capsule. SQL absence alone
     * cannot make an old original fresh, and an orphan event is never an accepting fallback. */
    internal suspend fun readSerialPublicationRequest(id: DomainId): RuntimeSerialPublicationRequestEvidenceV1? {
        requireApprovalRetirementHistory()
        check(approvalRetirementCapture == null && approvalRetirementEvidence == null)
        val event = checkNotNull(securityTransaction).readAuthEvent(coordinates.scope, id)
        val matches = native.original.record.protectedFinalDecisions.filter { it.declaration.originalRequestId == id }
        check(matches.size <= 1)
        val key = runtimeSecurityKey(RuntimeHistoryTableV2.AUTH_EVENT, listOf(scopeKey, id.value))
        var tip: RuntimeHistoryRowTipRowV2? = null
        try {
            tip = readTip(RuntimeHistoryTableV2.AUTH_EVENT, key)
            val versions = connection.usePrepared("SELECT count(*) FROM runtime_history_row_version_v2 WHERE tableTag=? AND `key`=?") {
                it.bindLong(1, RuntimeHistoryTableV2.AUTH_EVENT.tag.toLong()); it.bindBlob(2, key)
                check(it.step()); it.getLong(0).also { _ -> check(!it.step()) }
            }
            val decision = matches.singleOrNull()
            if (decision == null) {
                check(event == null && tip == null && versions == 0L)
                return null
            }
            check(event != null)
            val retained = checkNotNull(tip)
            check(versions == 1L && retained.tableTag == RuntimeHistoryTableV2.AUTH_EVENT.tag && retained.key.contentEquals(key) &&
                retained.version == 1L && retained.tombstone == 0 && retained.sequence in 2L..originalParent.historySequence &&
                retained.ordinal == 0 && retained.targetRowId != null && retained.rowDigest != null)
            val recorded = readRetirementHeader(retained.sequence)
            val header = recorded.first
            check(header.purpose == RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST && header.changeCount == 1 &&
                header.sequence == retained.sequence && header.scope == scopeKey && header.storageId == coordinates.storageInstanceId &&
                header.originalAllocationDigest == native.original.record.allocation.digest && header.assignmentId == coordinates.assignmentId &&
                decision.scope == coordinates.scope && decision.originalAllocationDigest == native.original.record.allocation.digest &&
                decision.child == NativeHistoryRecordedHeadV2(header.sequence, recorded.second, header.installationGenesisDigest,
                    header.commandEpoch, header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment))
            val selected = checkNotNull(readProtectedDecisionIntent(header, decision).serialPublication)
            check(selected.requestValue().requestId == id && selected.matchesEvent(event) &&
                selected.protectedFinalDeclaration() == decision.declaration)
            val version = readVersion(retained.sequence, 0)
            var current: NativeHistoryPhysicalRowV2? = null
            try {
                check(version.tableTag == RuntimeHistoryTableV2.AUTH_EVENT.tag && version.key.contentEquals(key) &&
                    version.version == 1L && version.previousSequence == null && version.previousOrdinal == null && version.previousVersion == null &&
                    version.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag && version.beforeRowId == null && version.beforeDigest == null &&
                    version.afterKind == RuntimeHistoryAfterKindV2.RETAINED_IMMUTABLE.tag && version.imageOffset == null && version.imageBytes == null &&
                    version.afterRowId == retained.targetRowId && version.afterDigest == retained.rowDigest)
                current = readRetirementRow(id)
                check(current.rowId == retained.targetRowId && RuntimeHistoryRowCodecV2.rowDigest(current).value == retained.rowDigest)
            } finally { version.key.fill(0); current?.clear() }
            return selected
        } finally { tip?.key?.fill(0); key.fill(0) }
    }
    internal fun beginApprovalUse(capture: RuntimeActionApprovalUseCapture) {
        body(); requireNoApprovalRetirement(); check(view != null && writerActor == capture.issued.record.requestorId &&
            capture.issued.record.requirement.scope == coordinates.scope && approvalUseCapture == null &&
            approvalIssuanceCapture == null && approvalIssuanceEvidence == null && throttleEvidence == null &&
            authenticationCapture == null && authenticationEvidence == null && commandAuthorizationCapture == null &&
            sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && originalInvocation == null &&
            capture.images.isEmpty() && capture.consumed == null)
        approvalUseCapture = capture
    }
    internal fun beginAuthentication(capture: RuntimeAuthenticationCapture) {
        body(); requireNoApprovalRetirement(); check(authenticationCapture == null && authenticationEvidence == null && operationId == null &&
            capture.scope == coordinates.scope && originalInvocation == null &&
            sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null)
        if (capture.action == RuntimeAuthenticationActionV1.SIGN_IN) {
            val throttle = checkNotNull(throttleEvidence)
            check(throttle.outcome == CredentialAttemptOutcome.MATCH && throttle.denial == null &&
                throttle.actorId == capture.actorId && throttle.deviceId == capture.deviceId && throttle.dependencies == capture.dependencies)
        } else check(throttleEvidence == null)
        authenticationCapture = capture
        purpose = capture.action.purpose
        actorId = capture.actorId; requestId = capture.eventId
    }
    internal fun captureProtectedImage(binding: ProtectedLocalBinding, plaintext: ByteArray) {
        body()
        val authentication = authenticationCapture
        val revocation = sessionRevocationCapture
        val employee = employeeProvisioningCapture
        val policy = employeePolicyActivationCapture
        val admission = deviceAdmissionCapture
        val cancellation = administrationCancellationCapture
        val device = deviceRevocationCapture
        val additionalDevice = additionalDeviceRevocationCapture
        val approvalIssue = approvalIssuanceCapture
        val approvalRetirement = approvalRetirementCapture
        val serialPublication = serialPublicationCapture
        val approvalUse = approvalUseCapture
        check(listOfNotNull(authentication, revocation, employee, policy, admission, cancellation, device, additionalDevice,
            approvalIssue, approvalRetirement, approvalUse, serialPublication).size <= 1)
        if (authentication != null) {
            check(binding.key.scope == authentication.scope && authentication.images.size < RuntimeAuthenticationEvidenceV1.MAX_IMAGES)
            authentication.images += RuntimeAuthenticationImageV1.capture(binding, sha256(plaintext))
        } else if (revocation != null) {
            check(binding.key.scope == revocation.scope && revocation.images.size < RuntimeSessionRevocationHistoryEvidence.MAX_IMAGES)
            revocation.images += RuntimeAuthenticationImageV1.capture(binding, sha256(plaintext))
        } else if (cancellation != null) {
            check(binding.key == ProtectedLocalRecordKey.AuthEvent(cancellation.scope, cancellation.requestId) &&
                binding.revision == Revision(0) && cancellation.images.size < RuntimeAdministrationCancellationEvidenceV1.MAX_IMAGES)
            cancellation.images += RuntimeAuthenticationImageV1.capture(binding, sha256(plaintext))
        } else if (device != null) {
            check(binding.key.scope == device.scope && device.images.size < RuntimeDeviceRevocationEvidenceV1.MAX_IMAGES)
            device.images += RuntimeDeviceRevocationImageV1.capture(binding, sha256(plaintext))
        } else if (additionalDevice != null) {
            additionalDevice.captureImage(binding, sha256(plaintext))
        } else if (employee != null) {
            employee.captureImage(binding, sha256(plaintext))
        } else if (policy != null) {
            policy.captureImage(binding, sha256(plaintext))
        } else if (admission != null) {
            admission.captureImage(binding, sha256(plaintext))
        } else if (approvalIssue != null) {
            check(approvalIssue.images.size < 2)
            approvalIssue.images += RuntimeApprovalImageV1.capture(binding, sha256(plaintext))
        } else if (approvalRetirement != null) {
            check(binding.key == ProtectedLocalRecordKey.AuthEvent(coordinates.scope, approvalRetirement.event().eventId) &&
                binding.revision == Revision(0) && approvalRetirement.images.isEmpty())
            approvalRetirement.images += RuntimeApprovalImageV1.capture(binding, sha256(plaintext))
        } else if (approvalUse != null) {
            check(approvalUse.images.size < 2)
            approvalUse.images += RuntimeApprovalImageV1.capture(binding, sha256(plaintext))
        } else if (serialPublication != null) {
            check(binding.key == ProtectedLocalRecordKey.AuthEvent(coordinates.scope, serialPublication.requestId) &&
                binding.revision == Revision(0) && serialPublication.images.isEmpty())
            serialPublication.images += RuntimeApprovalImageV1.capture(binding, sha256(plaintext))
        }
        // Only actual participating protected sealing supplies these hashes. This borrows
        // the writer's buffer and neither retains nor clears plaintext/credential bytes.
    }
    internal fun beginSessionRevocation(capture: RuntimeSessionRevocationHistoryCapture) {
        body(); requireNoApprovalRetirement()
        check(securityTransaction != null && view == null && writerActor == null && originalInvocation == null &&
            operationId == null && authenticationCapture == null && authenticationEvidence == null &&
            commandAuthorizationCapture == null && nativeOriginalCapture == null && finalDeadline == null && !finalDeadlineCaptured &&
            employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && employeeProvisioningCapture == null && employeeProvisioningEvidence == null &&
            throttleEvidence == null && installationReceipt == null && sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && capture.images.isEmpty() &&
            capture.scope == coordinates.scope && purpose == RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION &&
            actorId == capture.actorId && requestId == capture.requestId && requestDigest == capture.semanticDigest && resources.isEmpty())
        sessionRevocationCapture = capture
    }
    internal fun completeSessionRevocation(capture: RuntimeSessionRevocationHistoryCapture) {
        body()
        check(sessionRevocationCapture === capture && sessionRevocationEvidence == null)
        val evidence = capture.evidence()
        check(evidence.purpose == purpose && evidence.authorization.actorId == actorId &&
            evidence.command.requestId == requestId && evidence.semanticDigest == requestDigest)
        // The original command digest is NOT replaced by a facts digest. Preflight/event
        // observations only declare a window; native's final observation is separate.
        sessionRevocationEvidence = evidence; sessionRevocationCapture = null; capture.images.clear()
    }
    internal fun beginAdministrationCancellation(capture: RuntimeAdministrationCancellationCapture) {
        body(); requireNoApprovalRetirement()
        check(securityTransaction != null && view == null && writerActor == null && originalInvocation == null &&
            operationId == null && authenticationCapture == null && authenticationEvidence == null &&
            commandAuthorizationCapture == null && nativeOriginalCapture == null && finalDeadline == null && !finalDeadlineCaptured &&
            employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && employeeProvisioningCapture == null && employeeProvisioningEvidence == null &&
            throttleEvidence == null && installationReceipt == null && sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && capture.images.isEmpty() &&
            capture.scope == coordinates.scope && purpose == capture.purpose && actorId == capture.actorId &&
            requestId == capture.requestId && requestDigest == capture.semanticDigest && resources.isEmpty())
        administrationCancellationCapture = capture
    }
    internal fun completeAdministrationCancellation(capture: RuntimeAdministrationCancellationCapture) {
        body()
        check(administrationCancellationCapture === capture && administrationCancellationEvidence == null)
        val evidence = capture.evidence()
        check(evidence.purpose == purpose && evidence.authorization.actorId == actorId &&
            evidence.command.requestId == requestId && evidence.semanticDigest == requestDigest)
        // Keep the original intent digest and its actual observation. This one-audit lane
        // never changes a target or shares the old two-image revocation serializers.
        administrationCancellationEvidence = evidence; administrationCancellationCapture = null; capture.images.clear()
    }
    /** The native immutable C1 definition supplies the actual admission origin. Current
     * revoked/re-admitted census rows cannot be reversed into historical rights. This is
     * capture selection ONLY; the original actual authorization remains mandatory. */
    internal fun originalCohostDeviceRevocationOrigin(principal: LocalEmployeePrincipal,
        before: LocalDeviceAdmission): LocalDeviceAdmission? {
        body()
        if (principal.scope != coordinates.scope || principal.deviceId != coordinates.deviceId ||
            before.scope != coordinates.scope || before.deviceId != coordinates.deviceId ||
            !before.admitted || before.revision != Revision(0)) return null
        val origin = native.original.record.definition?.definition?.masterAdmission ?: return null
        val seed = native.original.record.receipt?.core?.seeds?.admission ?: return null
        if (deviceRevocationDigestV1(ProtectedLocalSecurityCodec.encodeAdmission(origin)) != seed ||
            deviceRevocationDigestV1(ProtectedLocalSecurityCodec.encodeAdmission(before)) != seed) return null
        return origin
    }
    /** Selects only an additional-device declaration grammar. The genuine immutable C1
     * binds the OPERATOR; the live target BEFORE is not a native admission origin. */
    internal fun isAdditionalDeviceRevocationCaptureCandidate(principal: LocalEmployeePrincipal,
        operatorBefore: LocalDeviceAdmission, targetBefore: LocalDeviceAdmission): Boolean {
        body()
        if (principal.scope != coordinates.scope || principal.deviceId != coordinates.deviceId ||
            operatorBefore.scope != coordinates.scope || operatorBefore.deviceId != principal.deviceId ||
            !operatorBefore.admitted || operatorBefore.revision != Revision(0) ||
            targetBefore.scope != coordinates.scope || targetBefore.deviceId == coordinates.deviceId ||
            !targetBefore.admitted || targetBefore.revision != Revision(0)) return false
        return matchesOriginalCohostAdmissionBefore(principal.deviceId,
            deviceRevocationDigestV1(ProtectedLocalSecurityCodec.encodeAdmission(operatorBefore)))
    }
    private fun matchesOriginalCohostAdmissionBefore(deviceId: DomainId, digest: PayloadDigest): Boolean {
        if (deviceId != coordinates.deviceId) return false
        val admission = native.original.record.definition?.definition?.masterAdmission ?: return false
        val seed = native.original.record.receipt?.core?.seeds?.admission ?: return false
        return admission.scope == coordinates.scope && admission.deviceId == coordinates.deviceId &&
            admission.admitted && admission.revision == Revision(0) && digest == seed &&
            deviceRevocationDigestV1(ProtectedLocalSecurityCodec.encodeAdmission(admission)) == seed
    }
    internal fun beginDeviceRevocation(capture: RuntimeDeviceRevocationCapture) {
        body(); requireNoApprovalRetirement()
        check(securityTransaction != null && view == null && writerActor == null && originalInvocation == null &&
            operationId == null && authenticationCapture == null && authenticationEvidence == null &&
            commandAuthorizationCapture == null && nativeOriginalCapture == null && finalDeadline == null && !finalDeadlineCaptured &&
            employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && employeeProvisioningCapture == null && employeeProvisioningEvidence == null &&
            throttleEvidence == null && installationReceipt == null && sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && capture.images.isEmpty() &&
            capture.scope == coordinates.scope && purpose == RuntimeWritePurposeV2.DEVICE_ADMINISTRATION &&
            actorId == capture.actorId && requestId == capture.requestId && requestDigest == capture.semanticDigest && resources.isEmpty())
        deviceRevocationCapture = capture
    }
    internal fun completeDeviceRevocation(capture: RuntimeDeviceRevocationCapture) {
        body()
        check(deviceRevocationCapture === capture && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null)
        val evidence = capture.evidence()
        check(evidence.purpose == purpose && evidence.authorization.actorId == actorId &&
            evidence.command.requestId == requestId && evidence.semanticDigest == requestDigest &&
            evidence.command.targetId == coordinates.deviceId)
        deviceRevocationEvidence = evidence; deviceRevocationCapture = null; capture.images.clear()
    }
    internal fun beginAdditionalDeviceRevocation(capture: RuntimeDeviceRevocationCaptureV2) {
        body(); requireNoApprovalRetirement()
        check(securityTransaction != null && view == null && writerActor == null && originalInvocation == null &&
            operationId == null && authenticationCapture == null && authenticationEvidence == null &&
            commandAuthorizationCapture == null && nativeOriginalCapture == null && finalDeadline == null && !finalDeadlineCaptured &&
            employeeProvisioningCapture == null && employeeProvisioningEvidence == null && employeeProvisioningNative == null &&
            employeePolicyActivationCapture == null && employeePolicyActivationEvidence == null && employeePolicyActivationNative == null &&
            deviceAdmissionCapture == null && deviceAdmissionEvidence == null && deviceAdmissionNative == null &&
            throttleEvidence == null && installationReceipt == null && sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            approvalIssuanceCapture == null && approvalIssuanceEvidence == null && approvalUseCapture == null && !approvalAttemptCompletion &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && capture.images.isEmpty() && resources.isEmpty() &&
            capture.scope == coordinates.scope && purpose == RuntimeWritePurposeV2.DEVICE_ADMINISTRATION &&
            actorId == capture.actorId && requestId == capture.requestId && requestDigest == capture.semanticDigest &&
            capture.expectedWrites in RuntimeDeviceRevocationEvidenceV2.MIN_IMAGES..RuntimeDeviceRevocationEvidenceV2.MAX_IMAGES &&
            capture.accountCount in 1..RuntimeDeviceRevocationEvidenceV2.MAX_ACCOUNTS &&
            capture.targetDeviceId != coordinates.deviceId &&
            matchesOriginalCohostAdmissionBefore(capture.operatorDeviceId, capture.operatorAdmissionBeforeDigest))
        additionalDeviceRevocationCapture = capture
    }
    internal fun completeAdditionalDeviceRevocation(capture: RuntimeDeviceRevocationCaptureV2) {
        body()
        check(additionalDeviceRevocationCapture === capture && additionalDeviceRevocationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null)
        val evidence = capture.evidence()
        check(evidence.purpose == purpose && evidence.authorization.scope == coordinates.scope &&
            evidence.authorization.actorId == actorId && evidence.command.requestId == requestId &&
            evidence.semanticDigest == requestDigest && evidence.command.targetId != coordinates.deviceId &&
            matchesOriginalCohostAdmissionBefore(evidence.authorization.deviceId, evidence.authorization.dependencies.admission))
        additionalDeviceRevocationEvidence = evidence; additionalDeviceRevocationCapture = null; capture.images.clear()
    }
    internal fun beginEmployeeProvisioning(capture: RuntimeEmployeeProvisioningCapture) {
        body(); requireNoApprovalRetirement()
        check(securityTransaction != null && view == null && writerActor == null && originalInvocation == null &&
            operationId == null && actorId == null && requestId == native.frameId && requestDigest == native.original.recordDigest &&
            purpose == RuntimeWritePurposeV2.PROVISIONING && authenticationCapture == null && authenticationEvidence == null &&
            commandAuthorizationCapture == null && nativeOriginalCapture == null && finalDeadline == null && !finalDeadlineCaptured &&
            throttleEvidence == null && installationReceipt == null && sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            employeeProvisioningCapture == null && employeeProvisioningEvidence == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && resources.isEmpty() && capture.images.isEmpty() &&
            capture.scope == coordinates.scope && capture.native.frameId == native.frameId &&
            capture.request.action == LocalProvisioningAction.PROVISION_ACCOUNT && capture.request.targetId != coordinates.firstActorId)
        employeeProvisioningCapture = capture; employeeProvisioningNative = capture.native
        // Provisioning is a company-head-issued action, not employee/first-actor authorship.
        // Preserve the ORIGINAL service-compatible request and digest, never this frame's ID.
        requestId = capture.request.requestId; requestDigest = capture.request.contentDigest
    }
    internal fun completeEmployeeProvisioning(capture: RuntimeEmployeeProvisioningCapture) {
        body()
        check(employeeProvisioningCapture === capture && employeeProvisioningNative === capture.native &&
            employeeProvisioningEvidence == null)
        val evidence = capture.evidence()
        check(purpose == RuntimeWritePurposeV2.PROVISIONING && actorId == null && operationId == null &&
            evidence.request.scope == coordinates.scope && evidence.request.requestId == requestId &&
            evidence.request.contentDigest == requestDigest)
        employeeProvisioningEvidence = evidence; employeeProvisioningCapture = null; capture.images.clear()
    }
    internal fun beginEmployeePolicyActivation(capture: RuntimeEmployeePolicyActivationCapture) {
        body(); requireNoApprovalRetirement()
        check(securityTransaction != null && view == null && writerActor == null && originalInvocation == null &&
            operationId == null && actorId == null && requestId == native.frameId && requestDigest == native.original.recordDigest &&
            purpose == RuntimeWritePurposeV2.PROVISIONING && authenticationCapture == null && authenticationEvidence == null &&
            commandAuthorizationCapture == null && nativeOriginalCapture == null && finalDeadline == null && !finalDeadlineCaptured &&
            throttleEvidence == null && installationReceipt == null && sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            employeeProvisioningCapture == null && employeeProvisioningEvidence == null && employeeProvisioningNative == null &&
            employeePolicyActivationCapture == null && deviceAdmissionCapture == null && employeePolicyActivationEvidence == null && deviceAdmissionEvidence == null && employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && resources.isEmpty() && capture.images.isEmpty() &&
            capture.scope == coordinates.scope && capture.native.frameId == native.frameId &&
            capture.request.action == LocalProvisioningAction.ACTIVATE_POLICY)
        employeePolicyActivationCapture = capture; employeePolicyActivationNative = capture.native
        // The named company head, not an employee/first-actor session, issued this action.
        requestId = capture.request.requestId; requestDigest = capture.request.contentDigest
    }
    internal fun completeEmployeePolicyActivation(capture: RuntimeEmployeePolicyActivationCapture) {
        body()
        check(employeePolicyActivationCapture === capture && employeePolicyActivationNative === capture.native &&
            employeePolicyActivationEvidence == null && deviceAdmissionEvidence == null)
        val evidence = capture.evidence()
        check(purpose == RuntimeWritePurposeV2.PROVISIONING && actorId == null && operationId == null &&
            evidence.request.scope == coordinates.scope && evidence.request.requestId == requestId &&
            evidence.request.contentDigest == requestDigest)
        employeePolicyActivationEvidence = evidence; employeePolicyActivationCapture = null; capture.images.clear()
    }
    internal fun beginDeviceAdmission(capture: RuntimeDeviceAdmissionCapture) {
        body(); requireNoApprovalRetirement()
        check(securityTransaction != null && view == null && writerActor == null && originalInvocation == null &&
            operationId == null && actorId == null && requestId == native.frameId && requestDigest == native.original.recordDigest &&
            purpose == RuntimeWritePurposeV2.PROVISIONING && authenticationCapture == null && authenticationEvidence == null &&
            commandAuthorizationCapture == null && nativeOriginalCapture == null && finalDeadline == null && !finalDeadlineCaptured &&
            approvalIssuanceCapture == null && approvalIssuanceEvidence == null && approvalUseCapture == null && !approvalAttemptCompletion &&
            serialPublicationCapture == null && serialPublicationEvidence == null && serialPublicationOrigin == null &&
            throttleEvidence == null && installationReceipt == null && sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            employeeProvisioningCapture == null && employeeProvisioningEvidence == null && employeeProvisioningNative == null &&
            employeePolicyActivationCapture == null && employeePolicyActivationEvidence == null && employeePolicyActivationNative == null &&
            deviceAdmissionCapture == null && deviceAdmissionEvidence == null && deviceAdmissionNative == null &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && resources.isEmpty() && capture.images.isEmpty() &&
            capture.scope == coordinates.scope && capture.native.frameId == native.frameId &&
            capture.request.action == LocalProvisioningAction.ADMIT_DEVICE && capture.request.targetId != coordinates.deviceId)
        deviceAdmissionCapture = capture; deviceAdmissionNative = capture.native
        // This is the original named-head ceremony, not employee/session authorship.
        requestId = capture.request.requestId; requestDigest = capture.request.contentDigest
    }
    internal fun completeDeviceAdmission(capture: RuntimeDeviceAdmissionCapture) {
        body()
        check(deviceAdmissionCapture === capture && deviceAdmissionNative === capture.native && deviceAdmissionEvidence == null)
        val evidence = capture.evidence()
        check(purpose == RuntimeWritePurposeV2.PROVISIONING && actorId == null && operationId == null &&
            evidence.request.scope == coordinates.scope && evidence.request.requestId == requestId &&
            evidence.request.contentDigest == requestDigest)
        deviceAdmissionEvidence = evidence; deviceAdmissionCapture = null; capture.images.clear()
    }
    internal fun completeAuthentication(capture: RuntimeAuthenticationCapture, denial: SessionTransitionDenial?,
        access: LocalAccessIssuance?, refresh: LocalRefreshIssuance?) {
        body(); check(authenticationCapture === capture && authenticationEvidence == null)
        val prefix = throttleEvidence?.changed().orEmpty().map { state -> RuntimeAuthenticationImageV1.capture(
            ProtectedLocalRecordKey.Throttle(state.key), state.revision, ProtectedLocalSecurityCodec.encodeThrottle(state)) }
        val evidence = RuntimeAuthenticationEvidenceV1(scope = capture.scope, actorId = capture.actorId,
            deviceId = capture.deviceId, eventId = capture.eventId, action = capture.action,
            issuerContextId = capture.at.issuerContextId, at = capture.at.moment, newSessionId = capture.newSessionId,
            previousSessionId = capture.previousSessionId, explicitDeviceMove = capture.explicitDeviceMove,
            dependencies = capture.dependencies, throttle = throttleEvidence, denial = denial,
            access = access?.let { RuntimeAuthenticationEvidenceV1.encoded(ProtectedLocalSecurityCodec.encodeIssuance(it)) },
            refresh = refresh?.let { RuntimeAuthenticationEvidenceV1.encoded(LocalAuthenticationCodec.encodeRefresh(it)) },
            images = prefix + capture.images)
        val bytes = RuntimeAuthenticationEvidenceCodec.encode(evidence)
        try { requestDigest = sha256(bytes) } finally { bytes.fill(0) }
        authenticationEvidence = evidence; authenticationCapture = null; capture.images.clear()
    }
    internal fun recordDependency(fields: List<String>) {
        body(); check(employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null)
        val encoded = frame(fields.map(String::encodeToByteArray))
        dependencyBytes = historyAddV2(dependencyBytes, encoded.size.toLong()+4, RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES.toLong())
        dependencyRows += encoded
    }
    internal fun recordAggregate(key: AggregateKey, value: StoredAggregate?) = recordDependency(listOf("aggregate",key.kind,key.id.value,
        value?.revision?.value?.toString() ?: "absent",value?.schema ?: "",value?.digest?.value ?: ""))
    internal fun recordClaim(key: ClaimKey, value: DomainId?) = recordDependency(listOf("claim",key.namespace,key.key,value?.value ?: "absent"))
    internal fun originalBusinessPublications(caller: TransactionView, context: CoroutineContext): NativeCompanyBusinessPublicationSnapshot? {
        body()
        context.ensureActive()
        check(context == coroutineContext && view === caller && writerActor != null && originalInvocation == null &&
            employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null)
        val observation = native.originalBusinessPublicationObservation ?: return null
        if (businessPublications == null) {
            check(observation.frameId == native.frameId && observation.originalAnchorDigest == native.original.recordDigest)
            recordDependency(observation.dependencyFields())
            businessPublications = observation
        } else check(businessPublications === observation)
        return observation.snapshot
    }
    internal fun captureInstallation(snapshot: LocalBranchInstallationSnapshotV2, receipt: LocalBranchInstallationReceiptV2) {
        body(); requireNoApprovalRetirement(); check(employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null &&
            native.original.record.parent == null && native.original.record.definition?.definitionDigest == snapshot.definitionDigest)
        purpose = RuntimeWritePurposeV2.ORIGINAL_INSTALLATION; requestId = snapshot.definition.originalRequestId
        requestDigest = snapshot.definitionDigest; actorId = null; operationId = null; resources = emptyList()
        definitionBytes = snapshot.copyWireBytes(); factBytes = LocalBranchInstallationCodecV2.encodeReceipt(receipt); installationReceipt = receipt
    }
    internal fun captureNativeOriginal(plan: NativeOriginalImportPlan, projection: NativeOriginalProjection,
        provenance: NativeOriginalJournalProvenanceV1) {
        body(); requireNoApprovalRetirement(); check(originalInvocation != null && nativeOriginalCapture == null && commandAuthorizationCapture == null && finalDeadline == null &&
            sessionRevocationCapture == null && sessionRevocationEvidence == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null)
        nativeOriginalCapture = RuntimeNativeOriginalImportCapture.capture(plan, projection,
            checkNotNull(originalInvocation), checkNotNull(originalLease), provenance)
        operationId=DomainId(plan.operationId.value); requestId=checkNotNull(operationId)
        actorId=plan.originalActorId; requestDigest=plan.semanticDigest
        resources=projection.resources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })).map {
            RuntimeHistoryCodecV2.originalResource(RecoveryResourceSelector.Resource(it), true, plan.semanticDigest)
        }
    }
    internal fun bindNativeOriginalView(store: AtomicMasterStore, invocation: NativeOriginalIntakeInvocation,
        lease: NativeOriginalRetainedLease, view: TransactionView) {
        body(); requireNoApprovalRetirement(); owner.requireNativeOriginal(store,invocation,lease)
        check(originalInvocation == null && this.view == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            administrationCancellationCapture == null && administrationCancellationEvidence == null &&
            deviceRevocationCapture == null && deviceRevocationEvidence == null &&
            additionalDeviceRevocationCapture == null && additionalDeviceRevocationEvidence == null)
        originalStore = store; originalInvocation = invocation; originalLease = lease; this.view = view
        purpose = RuntimeWritePurposeV2.COMMAND_APPLY; requestId = invocation.invocationId
        recordDependency(listOf("native-original",invocation.invocationId.value,invocation.locator.clientAllocationId.value,
            sha256(lease.retained.certificate.bytes()).value,sha256(lease.retained.originalBody.bytes()).value))
    }
    internal fun requireNativeOriginalBody(view: TransactionView, context: CoroutineContext) {
        check(phase in setOf(Phase.BODY,Phase.PREPARING,Phase.PREPARED,Phase.FINISHED))
        check(this.view === view && context == coroutineContext)
        owner.requireNativeOriginal(checkNotNull(originalStore),checkNotNull(originalInvocation),checkNotNull(originalLease))
        context.ensureActive(); check(!checkNotNull(originalInvocation).denied)
    }
    internal suspend fun prepareFromView(value: TransactionView) {
        check(view === value)
        if (securityTransaction == null) prepareNative()
    }
    internal fun finishFromView(value: TransactionView) {
        check(view === value)
        if (securityTransaction == null) finishNow()
    }
    internal suspend fun prepareFromSecurity(value: RoomLocalSecurityTransaction) {
        check(securityTransaction === value); prepareNative()
    }
    internal fun finishFromSecurity(value: RoomLocalSecurityTransaction) { check(securityTransaction === value); finishNow() }

    /** Only the original successful kernel final cut supplies this sample. No SQL, clock,
     * suspension or re-authorization occurs here, and no replay/read can mint a decision. */
    internal fun captureFinalDeadlineNow(operation: OperationId, digest: PayloadDigest, deadline: UtcInstant, finalWall: UtcInstant) {
        check(phase == Phase.FINISHED && commandAuthorizationCapture != null && originalInvocation == null &&
            purpose == RuntimeWritePurposeV2.COMMAND_APPLY && !finalDeadlineCaptured)
        val declaration = checkNotNull(finalDeadline)
        check(declaration.originalOperationId.value == operation.value && declaration.originalRequestId == requestId &&
            declaration.originalOperationId == operationId && declaration.originalRequestDigest == digest && digest == requestDigest &&
            declaration.mustCommitBefore == deadline && finalWall < deadline)
        owner.captureFinalDeadlineNow(this, finalWall)
        finalDeadlineCaptured = true
    }

    private suspend fun prepareNative() {
        body(); check(connection.inTransaction()); phase = Phase.PREPARING
        check(authenticationCapture == null && sessionRevocationCapture == null && administrationCancellationCapture == null &&
            deviceRevocationCapture == null && additionalDeviceRevocationCapture == null &&
            employeeProvisioningCapture == null && employeePolicyActivationCapture == null && deviceAdmissionCapture == null &&
            approvalIssuanceCapture == null && approvalRetirementCapture == null && serialPublicationCapture == null &&
            masterLifecycleEvidence == null) // Never retain a partial protected decision.
        originalInvocation?.let { check(!it.denied) }
        val changes = scalar("SELECT count(*) FROM runtime_history_capture_v2")
        historyCapacityV2(changes in 0L..RuntimeHistoryProfileV2.MAX_CHANGES.toLong())
        requireImageCensus()
        if (changes == 0L) {
            check(commandAuthorizationCapture == null && nativeOriginalCapture == null && finalDeadline == null &&
                sessionRevocationEvidence == null && administrationCancellationEvidence == null && deviceRevocationEvidence == null &&
                additionalDeviceRevocationEvidence == null &&
                employeeProvisioningEvidence == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null &&
                employeePolicyActivationEvidence == null && deviceAdmissionEvidence == null && approvalIssuanceCapture == null && approvalIssuanceEvidence == null &&
                approvalRetirementEvidence == null && serialPublicationEvidence == null && serialPublicationOrigin == null &&
                masterLifecycleEvidence == null)
            approvalUseCapture?.let { check(it.images.isEmpty() && it.consumed == null && it.event == null && it.consumedAt == null) }
            authenticationEvidence?.let { check(it.images.isEmpty() && it.denial != null) }
            execute("DELETE FROM runtime_history_write_frame_v2")
            phase = Phase.PREPARED
            return // No fictitious child for a zero-write read.
        }
        val branch = owner.database.facts().branch(scopeKey) ?: error("Original branch missing")
        val parent = native.original.record.parent
        check(branch.assignmentId == coordinates.assignmentId.value && branch.deviceId == coordinates.deviceId.value)
        val next = (parent?.historySequence ?: 0L)+1
        historySequenceV2(next)
        // Mutable images stream into 64KiB chunks. No 256-retail/32x16-food operation is
        // hydrated as one event, capped by the old monolithic envelope or split into commits.
        val payload = PayloadWriter(next)
        var imageBytes = 0L
        val throttle = throttleEvidence?.takeIf { purpose == it.purpose && actorId == it.actorId && requestId == it.attemptId }
        val throttleChanges = throttle?.changed().orEmpty()
        var throttleOnly = throttle != null && changes == throttleChanges.size.toLong() && changes in 1L..2L &&
            definitionBytes == null && factBytes == null && (dependencyRows.isEmpty() || approvalAttemptCompletion) && originalInvocation == null &&
            approvalIssuanceEvidence == null && approvalRetirementEvidence == null && serialPublicationEvidence == null && approvalUseCapture == null
        val authentication = authenticationEvidence
        val sessionRevocation = sessionRevocationEvidence
        val administrationCancellation = administrationCancellationEvidence
        val deviceRevocation = deviceRevocationEvidence
        val additionalDeviceRevocation = additionalDeviceRevocationEvidence
        check(deviceRevocation == null || additionalDeviceRevocation == null)
        val employeeProvisioning = employeeProvisioningEvidence
        val employeePolicyActivation = employeePolicyActivationEvidence
        val deviceAdmission = deviceAdmissionEvidence
        check(listOfNotNull(employeeProvisioning, employeePolicyActivation, deviceAdmission).size <= 1)
        check((deviceAdmission == null) == (deviceAdmissionNative == null))
        val commandAuthorization = commandAuthorizationCapture?.evidence(native.original.recordDigest, changes.toInt())
        val receiptCapture = commandAuthorization?.takeIf {
            it.purposeTag == RuntimeWritePurposeV2.COMMAND_APPLY.tag &&
                CommandKey(it.commandName, it.commandVersion) == RuntimeRetailCustomerReceiptMembershipProfile.COMMAND
        }?.let { RuntimeRetailCustomerReceiptCapture(native.original, it,
            checkNotNull(owner.retailCustomerReceiptEventClassifier(this))) }
        val approvalIssuance = approvalIssuanceEvidence
        val approvalRetirement = approvalRetirementEvidence
        val serialPublication = serialPublicationEvidence
        val masterLifecycle = masterLifecycleEvidence
        val approvedCommand = commandAuthorization?.takeIf { it.actionApprovalId != null }?.let { command ->
            val bytes = RuntimeCommandAuthorizationEvidenceCodec.encode(command)
            try { RuntimeApprovedCommandEvidenceV1(commandEvidence = bytes.decodeToString(throwOnInvalidSequence = true),
                approvalUse = checkNotNull(approvalUseCapture).evidence(command)).also { it.validate() } }
            finally { bytes.fill(0) }
        }
        val nativeOriginal = nativeOriginalCapture?.evidence(native.original.recordDigest, changes.toInt())
        if (nativeOriginal != null) check(originalInvocation != null && commandAuthorization == null && authentication == null &&
            administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null &&
            throttleEvidence == null && definitionBytes == null && factBytes == null && purpose == RuntimeWritePurposeV2.COMMAND_APPLY &&
            nativeOriginal.producer.operationId.value == operationId?.value && nativeOriginal.producer.originalActorId == actorId &&
            nativeOriginal.producer.semanticDigest == requestDigest)
        if (commandAuthorization != null) check(commandAuthorization.purposeTag == purpose.tag &&
            commandAuthorization.operationId.value == operationId?.value && commandAuthorization.operationId.value == requestId.value &&
            commandAuthorization.semanticDigest == requestDigest && commandAuthorization.authorization.actorId == actorId &&
            definitionBytes == null && factBytes == null && authentication == null && administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null &&
            throttleEvidence == null && originalInvocation == null)
        if (approvalIssuance != null) check(purpose == RuntimeWritePurposeV2.APPROVAL_ISSUE &&
            approvalIssuance.requestor.actorId == actorId && approvalIssuance.approvalId == requestId &&
            approvalIssuance.value().issueRequestDigest == requestDigest && changes == 4L && operationId == null &&
            commandAuthorization == null && authentication == null && sessionRevocation == null &&
            administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null && employeeProvisioning == null &&
            employeePolicyActivation == null && nativeOriginal == null && finalDeadline == null &&
            originalInvocation == null && definitionBytes == null && factBytes == null && resources.isEmpty())
        if (approvalRetirement != null) check(purpose == RuntimeWritePurposeV2.APPROVAL_RETIRE &&
            approvalRetirement.requestor.scope == coordinates.scope && approvalRetirement.requestor.actorId == actorId &&
            approvalRetirement.retirementId() == requestId && runtimeApprovalDigest(approvalRetirement.value()) == requestDigest &&
            approvalRetirement.nativeRecordDigest == native.original.recordDigest &&
            approvalRetirement.beforeHistorySequence == originalParent.historySequence && approvalRetirement.beforeHistoryDigest == originalParent.historyDigest &&
            approvalRetirement.beforeCommandEpoch.value == originalParent.commandEpoch &&
            approvalRetirement.beforeCommandSequence.value == originalParent.commandSequence && approvalRetirement.beforeCommandDigest == originalParent.commandDigest &&
            changes == 1L && operationId == null && resources.isEmpty() &&
            approvalIssuance == null && approvalUseCapture == null && !approvalAttemptCompletion && throttleEvidence == null &&
            commandAuthorization == null && authentication == null && sessionRevocation == null && administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null &&
            employeeProvisioning == null && employeeProvisioningNative == null && employeePolicyActivation == null && employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            nativeOriginal == null && finalDeadline == null && originalInvocation == null && installationReceipt == null && definitionBytes == null && factBytes == null &&
            branch.epoch == originalParent.commandEpoch && branch.sequence == originalParent.commandSequence &&
            PayloadDigest(branch.journalHash) == originalParent.commandDigest &&
            RuntimeHistoryCodecV2.roleCommitment(branch) == originalParent.roleCommitment &&
            RuntimeHistoryCodecV2.recoveryCommitment(branch) == originalParent.recoveryCommitment)
        if (serialPublication != null) check(purpose == RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST &&
            serialPublication.requestValue().scope == coordinates.scope && serialPublication.authorization.actorId == actorId &&
            serialPublication.requestValue().requestId == requestId && serialPublication.semanticDigest == requestDigest &&
            serialPublicationOrigin != null && changes == 1L && operationId == null && resources.isEmpty() &&
            approvalIssuance == null && approvalRetirement == null && approvalUseCapture == null && !approvalAttemptCompletion && throttleEvidence == null &&
            commandAuthorization == null && authentication == null && sessionRevocation == null && administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null &&
            employeeProvisioning == null && employeeProvisioningNative == null && employeePolicyActivation == null && employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            nativeOriginal == null && finalDeadline == null && originalInvocation == null && installationReceipt == null && definitionBytes == null && factBytes == null &&
            branch.epoch == originalParent.commandEpoch && branch.sequence == originalParent.commandSequence &&
            PayloadDigest(branch.journalHash) == originalParent.commandDigest &&
            RuntimeHistoryCodecV2.roleCommitment(branch) == originalParent.roleCommitment &&
            RuntimeHistoryCodecV2.recoveryCommitment(branch) == originalParent.recoveryCommitment)
        if (masterLifecycle != null) check(purpose == RuntimeWritePurposeV2.ROLE_TRANSITION &&
            masterLifecycle.scope == coordinates.scope && masterLifecycle.actorId == actorId &&
            masterLifecycle.eventId == requestId && masterLifecycle.requestDigest == requestDigest &&
            masterLifecycle.installationId == coordinates.installationId &&
            masterLifecycle.coordinatesDigest == lifecycleCoordinatesDigest(coordinates) &&
            changes == 1L && operationId == null && resources.isEmpty() && factBytes == null &&
            approvalIssuance == null && approvalRetirement == null && approvalUseCapture == null && !approvalAttemptCompletion &&
            throttleEvidence == null && serialPublication == null && serialPublicationOrigin == null &&
            commandAuthorization == null && authentication == null && sessionRevocation == null &&
            administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null &&
            employeeProvisioning == null && employeeProvisioningNative == null && employeePolicyActivation == null &&
            employeePolicyActivationNative == null && deviceAdmissionNative == null &&
            nativeOriginal == null && finalDeadline == null && originalInvocation == null && installationReceipt == null &&
            branch.epoch == originalParent.commandEpoch && branch.sequence == originalParent.commandSequence &&
            PayloadDigest(branch.journalHash) == originalParent.commandDigest &&
            RuntimeHistoryCodecV2.roleCommitment(branch) == originalParent.roleCommitment &&
            RuntimeHistoryCodecV2.recoveryCommitment(branch) == originalParent.recoveryCommitment)
        if (sessionRevocation != null) check(sessionRevocation.purpose == purpose &&
            sessionRevocation.authorization.scope == coordinates.scope && sessionRevocation.authorization.actorId == actorId &&
            sessionRevocation.command.requestId == requestId && sessionRevocation.semanticDigest == requestDigest &&
            changes == RuntimeSessionRevocationHistoryEvidence.MAX_IMAGES.toLong() &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && resources.isEmpty() &&
            operationId == null && authentication == null && administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null && throttleEvidence == null && commandAuthorization == null &&
            nativeOriginal == null && finalDeadline == null && originalInvocation == null)
        if (administrationCancellation != null) check(administrationCancellation.purpose == purpose &&
            administrationCancellation.authorization.scope == coordinates.scope && administrationCancellation.authorization.actorId == actorId &&
            administrationCancellation.command.requestId == requestId && administrationCancellation.semanticDigest == requestDigest &&
            changes == RuntimeAdministrationCancellationEvidenceV1.MAX_IMAGES.toLong() &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && resources.isEmpty() &&
            operationId == null && authentication == null && sessionRevocation == null && deviceRevocation == null && additionalDeviceRevocation == null && throttleEvidence == null &&
            commandAuthorization == null && nativeOriginal == null && finalDeadline == null && originalInvocation == null &&
            employeeProvisioning == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && installationReceipt == null)
        if (deviceRevocation != null) check(deviceRevocation.purpose == purpose &&
            deviceRevocation.authorization.scope == coordinates.scope && deviceRevocation.authorization.actorId == actorId &&
            deviceRevocation.command.targetId == coordinates.deviceId && deviceRevocation.command.requestId == requestId &&
            deviceRevocation.semanticDigest == requestDigest && changes == deviceRevocation.images.size.toLong() &&
            changes in RuntimeDeviceRevocationEvidenceV1.MIN_IMAGES.toLong()..RuntimeDeviceRevocationEvidenceV1.MAX_IMAGES.toLong() &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && resources.isEmpty() && operationId == null &&
            additionalDeviceRevocation == null && authentication == null && sessionRevocation == null && administrationCancellation == null && throttleEvidence == null &&
            commandAuthorization == null && nativeOriginal == null && finalDeadline == null && originalInvocation == null &&
            employeeProvisioning == null && employeeProvisioningNative == null && employeePolicyActivationNative == null && deviceAdmissionNative == null && installationReceipt == null)
        if (additionalDeviceRevocation != null) check(additionalDeviceRevocation.purpose == purpose &&
            additionalDeviceRevocation.authorization.scope == coordinates.scope && additionalDeviceRevocation.authorization.actorId == actorId &&
            additionalDeviceRevocation.command.targetId != coordinates.deviceId && additionalDeviceRevocation.command.requestId == requestId &&
            matchesOriginalCohostAdmissionBefore(additionalDeviceRevocation.authorization.deviceId,
                additionalDeviceRevocation.authorization.dependencies.admission) &&
            additionalDeviceRevocation.semanticDigest == requestDigest && changes == additionalDeviceRevocation.images.size.toLong() &&
            changes in RuntimeDeviceRevocationEvidenceV2.MIN_IMAGES.toLong()..RuntimeDeviceRevocationEvidenceV2.MAX_IMAGES.toLong() &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && resources.isEmpty() && operationId == null &&
            deviceRevocation == null && authentication == null && sessionRevocation == null && administrationCancellation == null && throttleEvidence == null &&
            commandAuthorization == null && nativeOriginal == null && finalDeadline == null && !finalDeadlineCaptured && originalInvocation == null &&
            employeeProvisioning == null && employeeProvisioningNative == null && employeePolicyActivation == null && employeePolicyActivationNative == null &&
            deviceAdmission == null && deviceAdmissionNative == null && installationReceipt == null &&
            approvalIssuance == null && approvalRetirement == null && approvalUseCapture == null && !approvalAttemptCompletion &&
            serialPublication == null && serialPublicationOrigin == null &&
            branch.epoch == originalParent.commandEpoch && branch.sequence == originalParent.commandSequence &&
            PayloadDigest(branch.journalHash) == originalParent.commandDigest &&
            RuntimeHistoryCodecV2.roleCommitment(branch) == originalParent.roleCommitment &&
            RuntimeHistoryCodecV2.recoveryCommitment(branch) == originalParent.recoveryCommitment)
        if (authentication != null) check(authentication.purpose == purpose && authentication.actorId == actorId && administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null &&
            authentication.eventId == requestId && changes == authentication.images.size.toLong() &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && originalInvocation == null)
        if (employeeProvisioning != null) check(purpose == RuntimeWritePurposeV2.PROVISIONING && actorId == null && operationId == null &&
            employeeProvisioning.request.scope == coordinates.scope && employeeProvisioning.request.requestId == requestId &&
            employeeProvisioning.request.contentDigest == requestDigest && changes == employeeProvisioning.images.size.toLong() &&
            employeeProvisioningNative?.frameId == native.frameId && definitionBytes == null && factBytes == null &&
            dependencyRows.isEmpty() && resources.isEmpty() && authentication == null && sessionRevocation == null && administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null && throttleEvidence == null &&
            commandAuthorization == null && nativeOriginal == null && finalDeadline == null && originalInvocation == null && installationReceipt == null &&
            employeePolicyActivation == null && employeePolicyActivationNative == null && deviceAdmissionNative == null)
        if (employeePolicyActivation != null) check(purpose == RuntimeWritePurposeV2.PROVISIONING && actorId == null && operationId == null &&
            employeePolicyActivation.request.scope == coordinates.scope && employeePolicyActivation.request.requestId == requestId &&
            employeePolicyActivation.request.contentDigest == requestDigest && changes == employeePolicyActivation.images.size.toLong() &&
            changes in 2L..3L && employeePolicyActivationNative?.frameId == native.frameId &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && resources.isEmpty() &&
            authentication == null && sessionRevocation == null && administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null &&
            throttleEvidence == null && commandAuthorization == null && nativeOriginal == null && finalDeadline == null &&
            originalInvocation == null && installationReceipt == null && employeeProvisioning == null && employeeProvisioningNative == null)
        if (deviceAdmission != null) check(purpose == RuntimeWritePurposeV2.PROVISIONING && actorId == null && operationId == null &&
            deviceAdmission.request.action == LocalProvisioningAction.ADMIT_DEVICE &&
            deviceAdmission.request.scope == coordinates.scope && deviceAdmission.request.requestId == requestId &&
            deviceAdmission.request.contentDigest == requestDigest && changes == deviceAdmission.images.size.toLong() &&
            changes in 3L..4098L && deviceAdmissionNative?.frameId == native.frameId &&
            definitionBytes == null && factBytes == null && dependencyRows.isEmpty() && resources.isEmpty() &&
            authentication == null && sessionRevocation == null && administrationCancellation == null && deviceRevocation == null && additionalDeviceRevocation == null &&
            throttleEvidence == null && commandAuthorization == null && nativeOriginal == null && finalDeadline == null &&
            originalInvocation == null && installationReceipt == null && employeeProvisioning == null && employeeProvisioningNative == null &&
            employeePolicyActivation == null && employeePolicyActivationNative == null &&
            approvalIssuance == null && approvalRetirement == null && approvalUseCapture == null && !approvalAttemptCompletion &&
            serialPublication == null && serialPublicationOrigin == null &&
            branch.epoch == originalParent.commandEpoch && branch.sequence == originalParent.commandSequence &&
            PayloadDigest(branch.journalHash) == originalParent.commandDigest &&
            RuntimeHistoryCodecV2.roleCommitment(branch) == originalParent.roleCommitment &&
            RuntimeHistoryCodecV2.recoveryCommitment(branch) == originalParent.recoveryCommitment)
        try {
            var ordinal = 0
            var approvalUseImageAt = 0
            while (ordinal.toLong() < changes) {
                val captured = readCapture(ordinal.toLong()+1)
                val table = RuntimeHistoryTableV2.fromTag(captured.tableTag)
                val before = if (captured.beforeRowId != null) readImage(table,captured.captureOrdinal,0) else null
                val after = if (captured.afterRowId != null) readImage(table,captured.captureOrdinal,1) else null
                var key: ByteArray? = null
                try {
                    check(before?.rowId == captured.beforeRowId && after?.rowId == captured.afterRowId && captured.frameId == native.frameId.value)
                    requireCapturedKeys(captured,before,after)
                    val selectedKey = RuntimeHistoryRowCodecV2.key(after ?: checkNotNull(before)); key = selectedKey
                    val approvalImage = if (approvalIssuance != null && ordinal >= 2) approvalIssuance.images.getOrNull(ordinal - 2)
                        ?: error("Approval issuance image missing") else if (approvalRetirement != null)
                            approvalRetirement.images.getOrNull(ordinal) ?: error("Retirement image missing")
                        else if (serialPublication != null)
                            serialPublication.images.getOrNull(ordinal) ?: error("Serial requester image missing")
                        else if (approvedCommand != null && table.protectedTable != null)
                            approvedCommand.approvalUse.images.getOrNull(approvalUseImageAt++) ?: error("Unexpected protected approval mutation")
                        else null
                    if (approvalIssuance != null && ordinal < 2) {
                        val expected = approvalIssuance.throttle.changed()[ordinal]
                        val expectedKey = runtimeThrottleKey(expected.key)
                        try { check(table == RuntimeHistoryTableV2.THROTTLE && captured.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag &&
                            expectedKey.contentEquals(selectedKey) && before != null && after != null &&
                            RuntimeHistoryRowViewV2(after).number("revision") == expected.revision.value) }
                        finally { expectedKey.fill(0) }
                    }
                    approvalImage?.let { expected ->
                        val expectedKey = expected.key(coordinates.scope)
                        try {
                            check(table == expected.table && captured.mutationKind == expected.mutation.tag && expectedKey.contentEquals(selectedKey) &&
                                after != null && (before == null) == (expected.mutation == RuntimeHistoryMutationKindV2.INSERT))
                            val row = RuntimeHistoryRowViewV2(after)
                            check(row.number("revision") == expected.revision.value && row.number("schemaVersion") == 1L &&
                                row.number("formatVersion") == 1L && row.text("algorithm") == ProtectedLocalStorageFormat.ALGORITHM &&
                                row.text("keyId") == coordinates.storageKeyId.value)
                            if (before != null) check(RuntimeHistoryRowViewV2(before).number("revision") == 0L)
                        } finally { expectedKey.fill(0) }
                    }
                    (authentication?.images ?: sessionRevocation?.images ?: administrationCancellation?.images)?.get(ordinal)?.let { expected ->
                        val expectedKey = expected.key(coordinates.scope)
                        try { check(table == expected.table && captured.mutationKind == expected.mutation.tag &&
                            expectedKey.contentEquals(selectedKey) && after != null &&
                            (before == null) == (expected.mutation == RuntimeHistoryMutationKindV2.INSERT)) }
                        finally { expectedKey.fill(0) }
                    }
                    deviceRevocation?.let { evidence ->
                        val expected = evidence.images[ordinal]
                        val expectedKey = expected.key(coordinates.scope)
                        try {
                            check(table == expected.table && captured.mutationKind == expected.mutation.tag &&
                                expectedKey.contentEquals(selectedKey) && after != null &&
                                (before == null) == (expected.mutation == RuntimeHistoryMutationKindV2.INSERT))
                            val row = RuntimeHistoryRowViewV2(checkNotNull(after))
                            check(row.number("revision") == expected.revision.value && row.number("schemaVersion") == 1L &&
                                row.number("formatVersion") == 1L && row.text("algorithm") == ProtectedLocalStorageFormat.ALGORITHM &&
                                row.text("keyId") == coordinates.storageKeyId.value)
                            if (before != null) {
                                val revision = if (table == RuntimeHistoryTableV2.ADMISSION) evidence.command.expectedRevision
                                    else evidence.accounts.single { it.actorId == expected.recordId }.accountRevision
                                check(RuntimeHistoryRowViewV2(before).number("revision") == revision.value)
                            }
                        } finally { expectedKey.fill(0) }
                    }
                    additionalDeviceRevocation?.let { evidence ->
                        val expected = evidence.images[ordinal]
                        val expectedKey = expected.key(coordinates.scope)
                        try {
                            check(table == expected.table && captured.mutationKind == expected.mutation.tag &&
                                expectedKey.contentEquals(selectedKey) && after != null &&
                                (before == null) == (expected.mutation == RuntimeHistoryMutationKindV2.INSERT))
                            val row = RuntimeHistoryRowViewV2(checkNotNull(after))
                            check(row.number("revision") == expected.revision.value && row.number("schemaVersion") == 1L &&
                                row.number("formatVersion") == 1L && row.text("algorithm") == ProtectedLocalStorageFormat.ALGORITHM &&
                                row.text("keyId") == coordinates.storageKeyId.value)
                            if (before != null) {
                                val revision = if (table == RuntimeHistoryTableV2.ADMISSION) evidence.command.expectedRevision
                                    else evidence.accounts.single { it.actorId == expected.recordId }.accountRevision
                                check(RuntimeHistoryRowViewV2(before).number("revision") == revision.value)
                            }
                        } finally { expectedKey.fill(0) }
                    }
                    employeeProvisioning?.images?.get(ordinal)?.let { expected ->
                        val expectedKey = expected.runtimeKey(coordinates.scope)
                        try {
                            check(table.protectedTable == expected.table && captured.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                                expectedKey.contentEquals(selectedKey) && before == null && after != null && captured.beforeRowId == null)
                            val row = RuntimeHistoryRowViewV2(checkNotNull(after))
                            check(row.number("revision") == 0L && row.number("schemaVersion") == 1L && row.number("formatVersion") == 1L &&
                                row.text("algorithm") == ProtectedLocalStorageFormat.ALGORITHM && row.text("keyId") == coordinates.storageKeyId.value)
                        } finally { expectedKey.fill(0) }
                    }
                    deviceAdmission?.images?.get(ordinal)?.let { expected ->
                        val expectedKey = expected.runtimeDeviceAdmissionKey(coordinates.scope)
                        try {
                            check(table.protectedTable == expected.table && captured.mutationKind == RuntimeHistoryMutationKindV2.INSERT.tag &&
                                expectedKey.contentEquals(selectedKey) && before == null && after != null && captured.beforeRowId == null)
                            val row = RuntimeHistoryRowViewV2(checkNotNull(after))
                            check(row.number("revision") == 0L && row.number("schemaVersion") == 1L && row.number("formatVersion") == 1L &&
                                row.text("algorithm") == ProtectedLocalStorageFormat.ALGORITHM && row.text("keyId") == coordinates.storageKeyId.value)
                        } finally { expectedKey.fill(0) }
                    }
                    employeePolicyActivation?.images?.get(ordinal)?.let { expected ->
                        val expectedKey = expected.runtimePolicyKey(coordinates.scope)
                        try {
                            val insert = expected.table == ProtectedLocalTable.AUTH_EVENT
                            check(table.protectedTable == expected.table &&
                                captured.mutationKind == (if (insert) RuntimeHistoryMutationKindV2.INSERT.tag else RuntimeHistoryMutationKindV2.UPDATE.tag) &&
                                expectedKey.contentEquals(selectedKey) && after != null && (before == null) == insert)
                            val row = RuntimeHistoryRowViewV2(checkNotNull(after))
                            check(row.number("revision") == expected.revision.value && row.number("schemaVersion") == 1L &&
                                row.number("formatVersion") == 1L && row.text("algorithm") == ProtectedLocalStorageFormat.ALGORITHM &&
                                row.text("keyId") == coordinates.storageKeyId.value)
                            if (!insert) check(RuntimeHistoryRowViewV2(checkNotNull(before)).number("revision") == expected.revision.value - 1L)
                        } finally { expectedKey.fill(0) }
                    }
                    if (throttleOnly) {
                        val expectedKey = runtimeThrottleKey(throttleChanges[ordinal].key)
                        try { throttleOnly = table == RuntimeHistoryTableV2.THROTTLE &&
                            captured.mutationKind == RuntimeHistoryMutationKindV2.UPDATE.tag && expectedKey.contentEquals(selectedKey) }
                        finally { expectedKey.fill(0) }
                    }
                    before?.let { row -> val actual = RuntimeHistoryRowCodecV2.key(row); try { check(actual.contentEquals(selectedKey)) } finally { actual.fill(0) } }
                    val prior = readTip(table, selectedKey)
                    approvalImage?.let { expected ->
                        if (expected.mutation == RuntimeHistoryMutationKindV2.INSERT) check(prior == null)
                        else check(prior?.version == 1L)
                    }
                    if (employeeProvisioning != null || deviceAdmission != null || administrationCancellation != null ||
                        (deviceRevocation != null || additionalDeviceRevocation != null || employeePolicyActivation != null) &&
                        table == RuntimeHistoryTableV2.AUTH_EVENT)
                        check(prior == null) // Never resurrect an old actor/event/throttle lineage.
                    if (additionalDeviceRevocation != null && table == RuntimeHistoryTableV2.ADMISSION)
                        check(prior?.version == 1L) // First withdrawal, not a reversed current incarnation.
                    employeePolicyActivation?.images?.get(ordinal)?.let { expected ->
                        if (expected.table != ProtectedLocalTable.AUTH_EVENT) check(prior?.version == expected.revision.value)
                    }
                    val beforeDigest = before?.let(RuntimeHistoryRowCodecV2::rowDigest)
                    val afterDigest = after?.let(RuntimeHistoryRowCodecV2::rowDigest)
                    if (before != null) check(prior != null && prior.tombstone == 0 && prior.targetRowId == before.rowId && prior.rowDigest == beforeDigest?.value)
                    else check(prior == null || prior.tombstone == 1)
                    // Inspect EVERY actual captured EVENT change, including BEFORE-only
                    // deletion or a kind-changing image. No other writer can silently stale
                    // the complete membership cut; fresh immutable inserts require no prior.
                    if (RuntimeRetailCustomerReceiptCapture.isEventChange(table, before, after))
                        check(receiptCapture != null) { "Receipt EVENT writer unavailable" }
                    receiptCapture?.capture(table, captured.mutationKind, before, after, prior != null)
                    val image = if (after != null && !table.immutable) RuntimeHistoryRowCodecV2.encode(after) else null
                    val version = RuntimeHistoryRowVersionRowV2(next,ordinal,table.tag,selectedKey,(prior?.version ?: 0L)+1,
                        prior?.sequence,prior?.ordinal,prior?.version,captured.mutationKind,before?.rowId,beforeDigest?.value,
                        after?.rowId,afterDigest?.value,when { after == null -> 3; table.immutable -> 2; else -> 1 },
                        if (image != null) imageBytes else null,image?.size)
                    try { image?.let { payload.append(it); imageBytes = historyAddV2(imageBytes,it.size.toLong(),RuntimeHistoryProfileV2.MAX_EVENT_BYTES) } }
                    finally { image?.fill(0) }
                    insertVersion(version)
                    if (prior != null) execute("DELETE FROM runtime_history_row_tip_v2 WHERE tableTag=? AND `key`=?",listOf(table.tag,selectedKey))
                    execute("INSERT INTO runtime_history_row_tip_v2 VALUES(?,?,?,?,?,?,?,?)",listOf(table.tag,selectedKey,version.version,next,ordinal,
                        if (after == null) 1 else 0,after?.rowId,afterDigest?.value))
                } finally { key?.fill(0); before?.clear(); after?.clear(); clearCapture(captured) }
                ordinal++
            }
            if (approvedCommand != null) check(approvalUseImageAt == approvedCommand.approvalUse.images.size)
            receiptCapture?.complete(branch)?.let { delta ->
                owner.requireRetailCustomerReceiptMembershipDelta(this, delta)
                receiptMembershipDelta = delta
            }
            val dependencies = if (employeeProvisioning != null) localEmployeeEnrollmentDependenciesV1(employeeProvisioning)
                else if (employeePolicyActivation != null) localEmployeePolicyActivationDependenciesV1(employeePolicyActivation)
                else if (deviceAdmission != null) localDeviceAdmissionDependenciesV1(deviceAdmission)
                else if (sessionRevocation != null) sessionRevocation.dependencies()
                else if (administrationCancellation != null) RuntimeAdministrationCancellationEvidenceCodec.dependencies(administrationCancellation)
                else if (deviceRevocation != null) RuntimeDeviceRevocationEvidenceCodec.dependencies(deviceRevocation)
                else if (additionalDeviceRevocation != null) RuntimeDeviceRevocationEvidenceCodecV2.dependencies(additionalDeviceRevocation)
                else if (authentication != null) RuntimeAuthenticationEvidenceCodec.dependencies(authentication)
                else if (approvalIssuance != null) RuntimeActionApprovalEvidenceCodec.dependencies(approvalIssuance)
                else if (approvalRetirement != null) RuntimeActionApprovalRetirementEvidenceCodec.dependencies(approvalRetirement)
                else if (serialPublication != null) RuntimeSerialPublicationRequestEvidenceCodec.dependencies(serialPublication)
                else if (throttleOnly) RuntimeThrottleEvidenceCodec.dependencies(checkNotNull(throttle))
                else definitionBytes?.copyOf() ?: frame(dependencyRows)
            val facts = if (employeeProvisioning != null) employeeProvisioning.copyWireBytes()
                else if (employeePolicyActivation != null) employeePolicyActivation.copyWireBytes()
                else if (deviceAdmission != null) deviceAdmission.copyWireBytes()
                else if (sessionRevocation != null) sessionRevocation.encode()
                else if (administrationCancellation != null) RuntimeAdministrationCancellationEvidenceCodec.encode(administrationCancellation)
                else if (deviceRevocation != null) RuntimeDeviceRevocationEvidenceCodec.encode(deviceRevocation)
                else if (additionalDeviceRevocation != null) RuntimeDeviceRevocationEvidenceCodecV2.encode(additionalDeviceRevocation)
                else if (authentication != null) RuntimeAuthenticationEvidenceCodec.encode(authentication)
                else if (approvalIssuance != null) RuntimeActionApprovalEvidenceCodec.encode(approvalIssuance)
                else if (approvalRetirement != null) RuntimeActionApprovalRetirementEvidenceCodec.encode(approvalRetirement)
                else if (serialPublication != null) RuntimeSerialPublicationRequestEvidenceCodec.encode(serialPublication)
                else if (masterLifecycle != null) RuntimeMasterLifecycleEvidenceCodec.encode(masterLifecycle)
                else if (throttleOnly) RuntimeThrottleEvidenceCodec.encode(checkNotNull(throttle))
                else if (approvedCommand != null) RuntimeActionApprovalEvidenceCodec.encode(approvedCommand)
                else if (commandAuthorization != null) RuntimeCommandAuthorizationEvidenceCodec.encode(commandAuthorization)
                else if (nativeOriginal != null) RuntimeNativeOriginalImportEvidenceCodec.encode(nativeOriginal)
                else factBytes?.copyOf() ?: frame(listOf("ukkera.runtime-history/owned-decision/v1",purpose.tag.toString(),
                requestId.value,actorId?.value ?: "",native.original.recordDigest.value,changes.toString()).map(String::encodeToByteArray))
            try {
                val originalIntent = NativeHistoryOriginalIntentV2(purpose.tag,requestId,requestDigest,operationId,actorId,
                    sha256(dependencies),sha256(facts),resources)
                val intent = RuntimeHistoryCodecV2.originalIntentBytes(originalIntent)
                try {
                    if (deviceRevocation != null || additionalDeviceRevocation != null)
                        historyCapacityV2(intent.size.toLong() + dependencies.size.toLong() + facts.size.toLong() <=
                            RuntimeHistoryProfileV2.MAX_CAPSULE_BYTES.toLong())
                    payload.append(intent); payload.append(dependencies); payload.append(facts); payload.finish()
                    val genesis = installationReceipt?.genesis ?: checkNotNull(parent).installationGenesisDigest
                    val attempt = checkNotNull(native.original.record.attempt)
                    val header = RuntimeHistoryEventHeaderV2(scopeKey,coordinates.storageInstanceId,
                        native.original.record.allocation.originalNativeBindingDigest,native.original.record.allocation.digest,
                        checkNotNull(native.original.record.definition).definitionDigest,attempt.facts.issuedAttemptId,
                        attempt.facts.attemptSequence,genesis,next,if (parent == null) 1 else 2,
                        parent?.historySequence ?: 0L,parent?.historyDigest ?: native.original.record.allocation.digest,native.frameId,coordinates.assignmentId,
                        purpose,branch.epoch,branch.sequence,PayloadDigest(branch.journalHash),RuntimeHistoryCodecV2.roleCommitment(branch),
                        RuntimeHistoryCodecV2.recoveryCommitment(branch),changes.toInt(),payload.chunks,imageBytes,intent.size,sha256(intent),
                        dependencies.size,sha256(dependencies),facts.size,sha256(facts))
                    val stream = RuntimeHistoryEventStreamV2(header)
                    for (at in 0 until changes.toInt()) {
                        val row = readVersion(next,at); try { stream.appendMutation(row) } finally { row.key.fill(0) }
                    }
                    for (at in 0 until payload.chunks) {
                        val chunk = readChunk(next,at); try { stream.appendChunk(chunk) } finally { chunk.bytes.fill(0) }
                    }
                    val commitment = stream.finish()
                    val headerBytes = RuntimeHistoryCodecV2.encodeHeader(header)
                    try { execute("INSERT INTO runtime_history_event_v2 VALUES(?,?,?,?,?,?,?,?,?,?,?)",listOf(next,native.frameId.value,
                        header.parentKind,header.parentSequence,header.parentDigest.value,commitment.digest.value,changes.toInt(),payload.chunks,
                        header.payloadBytes(),commitment.encodedBytes,headerBytes)) } finally { headerBytes.fill(0) }
                    val child = NativeHistoryRecordedHeadV2(next,commitment.digest,genesis,branch.epoch,branch.sequence,PayloadDigest(branch.journalHash),
                        header.roleCommitment,header.recoveryCommitment)
                    if (parent != null) execute("DELETE FROM runtime_history_head_v2 WHERE singleton=1")
                    execute("INSERT INTO runtime_history_head_v2 VALUES(1,?,?,?,?,?,?,?,?,?,?,?,?)",listOf(scopeKey,RuntimeHistoryProfileV2.PROFILE,
                        coordinates.storageInstanceId.value,header.bindingDigest.value,genesis.value,next,commitment.digest.value,branch.epoch,
                        branch.sequence,branch.journalHash,header.roleCommitment.value,header.recoveryCommitment.value))
                    // Close the SQL capture completely before native preparation. A later application
                    // write is rejected by the fixed installed guard, even if somebody catches it.
                    execute("DELETE FROM runtime_history_capture_v2")
                    for (table in RuntimeHistoryTableV2.entries.filter { it.application }) execute("DELETE FROM temp.`__rh_image_${table.tag}`")
                    execute("DELETE FROM runtime_history_write_frame_v2")
                    val first = installationReceipt?.let { receipt -> SelfOwnedInstallationChild(receipt, LocalInstallationCompletedChildFactsV2(
                        native.original.record.allocation.digest,header.definitionDigest,requestId,header.installationAttemptId,
                        header.installationAttemptSequence,genesis,receipt.digest,commitment.digest,Revision(branch.epoch))) }
                    // A preparation-only approval has no business original. Native publication
                    // observation proofs belong solely to the real terminal command lanes 2/3.
                    val terminalPublications = businessPublications.takeIf {
                        purpose in setOf(RuntimeWritePurposeV2.COMMAND_APPLY, RuntimeWritePurposeV2.COMMAND_REJECT) && operationId != null
                    }
                    if (serialPublication != null) {
                        owner.prepareNativeSerialChild(this, child, serialPublication.capsule(), checkNotNull(serialPublicationOrigin),
                            serialPublication.protectedFinalDeclaration())
                    } else owner.prepareNativeChild(this,child,originalIntent,first,finalDeadline,
                        sessionRevocation?.protectedFinalDeclaration() ?: administrationCancellation?.protectedFinalDeclaration() ?:
                            deviceRevocation?.protectedFinalDeclaration() ?: additionalDeviceRevocation?.protectedFinalDeclaration() ?:
                            approvalIssuance?.protectedFinalDeclaration() ?:
                            approvalRetirement?.protectedFinalDeclaration() ?:
                            approvedCommand?.approvalUse?.protectedFinalDeclaration(checkNotNull(commandAuthorization)), terminalPublications,
                        if (terminalPublications != null) dependencies else null)
                    receiptMembershipChild = child // Only after actual native preparation returned.
                    if (employeeProvisioning != null) checkNotNull(employeeProvisioningNative)
                        .retainOriginalPreparedEffect(originalIntent, employeeProvisioning)
                    if (employeePolicyActivation != null) checkNotNull(employeePolicyActivationNative)
                        .retainOriginalPreparedEffect(originalIntent, employeePolicyActivation)
                    if (deviceAdmission != null) checkNotNull(deviceAdmissionNative)
                        .retainOriginalPreparedEffect(originalIntent, deviceAdmission)
                } finally { intent.fill(0) }
            } finally { dependencies.fill(0); facts.fill(0) }
        } finally { receiptCapture?.clear(); payload.clear() }
        phase = Phase.PREPARED
    }
    private fun finishNow() {
        check(phase == Phase.PREPARED); coroutineContext.ensureActive()
        originalInvocation?.let { check(!it.denied) }
        owner.finishNativeNow(this); phase = Phase.FINISHED
    }
    internal fun requireNormalOuterBody() { check(phase == Phase.FINISHED) }
    /** Only the owner invokes this after real native COMMITTED plus normal outer return.
     * A copied parent, unfinished frame or mismatched settled child returns unavailable. */
    internal fun retailCustomerReceiptMembershipCommit(settled: OriginalNativeHistorySnapshot): RuntimeRetailCustomerReceiptMembershipCommit? {
        val child = receiptMembershipChild
        if (phase != Phase.FINISHED || settled.record.pending != null ||
            settled.lifetimeId != native.original.lifetimeId ||
            settled.record.allocation.digest != native.original.record.allocation.digest ||
            settled.record.parent != (child ?: native.original.record.parent)) return null
        val installation = installationReceipt
        if (installation != null) {
            val first = settled.record.firstChild ?: return null
            if (purpose != RuntimeWritePurposeV2.ORIGINAL_INSTALLATION || native.original.record.parent != null ||
                child == null || child.historySequence != 1L || child.commandSequence != 0L ||
                settled.record.attempt?.phase != NativeHistoryAttemptPhaseV2.COMPLETED ||
                settled.record.receipt?.digest != installation.digest || first.receiptDigest != installation.digest ||
                first.originalAllocationDigest != native.original.record.allocation.digest ||
                first.definitionDigest != native.original.record.definition?.definitionDigest ||
                first.originalRequestId != requestId || first.historyDigest != child.historyDigest ||
                first.installationGenesisDigest != child.installationGenesisDigest ||
                first.commandEpoch.value != child.commandEpoch || receiptMembershipDelta != null) return null
        }
        if (receiptMembershipDelta != null && child == null) return null
        return RuntimeRetailCustomerReceiptMembershipCommit(installation, receiptMembershipDelta)
    }
    internal fun close() {
        phase = Phase.CLOSED; dependencyRows.forEach { it.fill(0) }; dependencyRows.clear()
        // All actual DATA IO has returned under the owner loan before this synchronous close.
        // Do not retain a grown selection container on a closed frame.
        val selections = receiptMembershipSelections; receiptMembershipSelections = null
        if (selections != null) while (selections.isNotEmpty()) selections.removeAt(selections.lastIndex).close()
        definitionBytes?.fill(0); definitionBytes=null; factBytes?.fill(0); factBytes=null
        view=null; writerActor=null; securityTransaction=null
        businessPublications=null
        receiptMembershipDelta=null; receiptMembershipChild=null
        throttleEvidence=null
        authenticationCapture?.images?.clear(); authenticationCapture=null; authenticationEvidence=null
        commandAuthorizationCapture=null
        approvalAttemptCompletion=false
        approvalIssuanceCapture?.images?.clear(); approvalIssuanceCapture=null; approvalIssuanceEvidence=null
        approvalRetirementCapture?.images?.clear(); approvalRetirementCapture=null; approvalRetirementEvidence=null
        serialPublicationCapture?.images?.clear(); serialPublicationCapture=null; serialPublicationEvidence=null; serialPublicationOrigin=null
        approvalUseCapture?.images?.clear(); approvalUseCapture=null
        sessionRevocationCapture?.images?.clear(); sessionRevocationCapture=null; sessionRevocationEvidence=null
        administrationCancellationCapture?.images?.clear(); administrationCancellationCapture=null; administrationCancellationEvidence=null
        deviceRevocationCapture?.images?.clear(); deviceRevocationCapture=null; deviceRevocationEvidence=null
        additionalDeviceRevocationCapture?.images?.clear(); additionalDeviceRevocationCapture=null; additionalDeviceRevocationEvidence=null
        employeeProvisioningCapture?.images?.clear(); employeeProvisioningCapture=null; employeeProvisioningEvidence=null; employeeProvisioningNative=null
        employeePolicyActivationCapture?.images?.clear(); employeePolicyActivationCapture=null; employeePolicyActivationEvidence=null; employeePolicyActivationNative=null
        deviceAdmissionCapture?.images?.clear(); deviceAdmissionCapture=null; deviceAdmissionEvidence=null; deviceAdmissionNative=null
        nativeOriginalCapture=null
        finalDeadline=null; finalDeadlineCaptured=false
    }
    private fun requireNoApprovalRetirement() { check(approvalRetirementCapture == null && approvalRetirementEvidence == null &&
        serialPublicationCapture == null && serialPublicationEvidence == null && serialPublicationOrigin == null) }
    private fun body() { check(phase == Phase.BODY); coroutineContext.ensureActive(); owner.requireFrame(this,connection) }
    private suspend fun scalar(sql: String): Long = connection.usePrepared(sql) { check(it.step()); it.getLong(0) }
    internal suspend fun execute(sql: String, values: List<Any?> = emptyList()) {
        connection.usePrepared(sql) { statement -> values.forEachIndexed { index,value -> statement.bindHistory(index+1,value) }; check(!statement.step()) }
    }
    /** The full per-table image inventory is checked, including tables with no captures.
     * Unique (ordinal,side) in the native-owned spool plus exact one-row reads below make
     * these counts a crosswalk, not a count-only completeness claim. */
    private suspend fun requireImageCensus() {
        val ordinalColumn = NativeHistoryOwnedSqlV2.IMAGE_ORDINAL_COLUMN
        val sideColumn = NativeHistoryOwnedSqlV2.IMAGE_SIDE_COLUMN
        val rowIdColumn = NativeHistoryOwnedSqlV2.IMAGE_ROW_ID_COLUMN
        for (table in RuntimeHistoryTableV2.entries.filter { it.application }) {
            val expected=scalar("SELECT COALESCE(sum((beforeRowId IS NOT NULL)+(afterRowId IS NOT NULL)),0) " +
                "FROM runtime_history_capture_v2 WHERE tableTag=${table.tag}")
            check(scalar("SELECT count(*) FROM temp.`__rh_image_${table.tag}`")==expected)
            check(scalar("SELECT count(*) FROM temp.`__rh_image_${table.tag}` AS i " +
                "LEFT JOIN runtime_history_capture_v2 AS c ON c.captureOrdinal=i.`$ordinalColumn` AND c.tableTag=${table.tag} " +
                "WHERE c.captureOrdinal IS NULL OR typeof(i.`$ordinalColumn`)!='integer' OR typeof(i.`$sideColumn`)!='integer' OR typeof(i.`$rowIdColumn`)!='integer' " +
                "OR i.`$sideColumn` NOT IN (0,1) OR (i.`$sideColumn`=0 AND (c.beforeRowId IS NULL OR i.`$rowIdColumn`!=c.beforeRowId)) " +
                "OR (i.`$sideColumn`=1 AND (c.afterRowId IS NULL OR i.`$rowIdColumn`!=c.afterRowId))")==0L)
        }
    }
    private suspend fun readImage(table: RuntimeHistoryTableV2, ordinal: Long, side: Int): NativeHistoryPhysicalRowV2 =
        readPhysicalRow(table, "`${NativeHistoryOwnedSqlV2.IMAGE_ROW_ID_COLUMN}`",
            " FROM temp.`__rh_image_${table.tag}` WHERE `${NativeHistoryOwnedSqlV2.IMAGE_ORDINAL_COLUMN}`=? AND `${NativeHistoryOwnedSqlV2.IMAGE_SIDE_COLUMN}`=?", listOf(ordinal, side))
    private suspend fun readRetirementRow(retirementId: DomainId): NativeHistoryPhysicalRowV2 =
        readPhysicalRow(RuntimeHistoryTableV2.AUTH_EVENT, "rowid", " FROM protected_local_auth_event WHERE scope=? AND eventId=?",
            listOf(scopeKey, retirementId.value))
    /** Private source-fixed selections share the exact native column/type/size preflight. */
    private suspend fun readPhysicalRow(table: RuntimeHistoryTableV2, rowIdColumn: String, suffix: String, values: List<Any?>): NativeHistoryPhysicalRowV2 {
        val columns=historyColumnsV2(table)
        // No TEXT/BLOB getter is invoked until the complete row's scalar lengths and types
        // pass the source-fixed cell/row capacities. SQLite NULL is storage type5, wire tag0.
        val metadata=connection.usePrepared("SELECT $rowIdColumn,"+columns.joinToString(",") {
            "typeof(`${it.name}`),length(CAST(`${it.name}` AS BLOB)),CASE WHEN typeof(`${it.name}`)='integer' THEN `${it.name}` ELSE NULL END"
        }+suffix) { statement ->
            values.forEachIndexed { index, value -> statement.bindHistory(index + 1, value) }; check(statement.step())
            check(statement.getColumnType(0)==1)
            val rid=statement.getLong(0)
            var total=0L
            val cells=columns.mapIndexed { index,column ->
                val at=1+index*3
                check(statement.getColumnType(at)==3)
                val type=when(statement.getText(at)) {
                    "null" -> RuntimeSqliteTypeV2.NULL; "integer" -> RuntimeSqliteTypeV2.INTEGER
                    "text" -> RuntimeSqliteTypeV2.TEXT; "blob" -> RuntimeSqliteTypeV2.BLOB
                    else -> error("Unexpected original image storage class")
                }
                when(type) {
                    RuntimeSqliteTypeV2.NULL -> {
                        check(column.nullable && statement.isNull(at+1) && statement.isNull(at+2))
                        NativeHistoryCellMetadataV2(column.name,type,0)
                    }
                    RuntimeSqliteTypeV2.INTEGER -> {
                        check(column.type==type && statement.getColumnType(at+2)==1)
                        val value=statement.getLong(at+2); check(value in column.minimum..column.maximum)
                        NativeHistoryCellMetadataV2(column.name,type,0,value)
                    }
                    else -> {
                        check(column.type==type && statement.getColumnType(at+1)==1)
                        val bytes=statement.getLong(at+1)
                        check(bytes in column.minimum..column.maximum && bytes<=NativeHistoryPhysicalReadProfileV2.MAX_CELL_BYTES)
                        total=historyAddV2(total,bytes,NativeHistoryPhysicalReadProfileV2.MAX_ROW_BYTES)
                        NativeHistoryCellMetadataV2(column.name,type,bytes)
                    }
                }
            }
            check(!statement.step()); rid to cells
        }
        val cells=mutableListOf<NativeHistoryPhysicalCellV2>()
        try {
            connection.usePrepared("SELECT $rowIdColumn,"+columns.joinToString(",") { column ->
                val name="`${column.name}`"
                "typeof($name),"+if(column.type in setOf(RuntimeSqliteTypeV2.TEXT,RuntimeSqliteTypeV2.BLOB)) "CAST($name AS BLOB)" else name
            }+suffix) { statement ->
                values.forEachIndexed { index, value -> statement.bindHistory(index + 1, value) }; check(statement.step())
                check(statement.getColumnType(0)==1 && statement.getLong(0)==metadata.first)
                metadata.second.forEachIndexed { index,expected ->
                    val at=1+index*2; check(statement.getText(at)==expected.type.name.lowercase())
                    cells+=when(expected.type) {
                        RuntimeSqliteTypeV2.NULL -> { check(statement.isNull(at+1)); NativeHistoryPhysicalCellV2.Null }
                        RuntimeSqliteTypeV2.INTEGER -> {
                            check(statement.getColumnType(at+1)==1 && statement.getLong(at+1)==expected.integerValue)
                            NativeHistoryPhysicalCellV2.Integer(checkNotNull(expected.integerValue))
                        }
                        else -> {
                            check(statement.getColumnType(at+1)==4)
                            val bytes=statement.getBlob(at+1)
                            try {
                                check(bytes.size.toLong()==expected.byteCount)
                                if(expected.type==RuntimeSqliteTypeV2.TEXT) NativeHistoryPhysicalCellV2.Text(bytes)
                                else NativeHistoryPhysicalCellV2.Blob(bytes)
                            } finally { bytes.fill(0) }
                        }
                    }
                }
                check(!statement.step())
            }
            return NativeHistoryPhysicalRowV2(table,metadata.first,cells).also { cells.clear() }
        } finally { cells.forEach { cell -> when(cell) {
            is NativeHistoryPhysicalCellV2.Text -> cell.clear(); is NativeHistoryPhysicalCellV2.Blob -> cell.clear(); else -> Unit
        } } }
    }
    private fun requireCapturedKeys(capture: RuntimeHistoryCaptureRowV2,before: NativeHistoryPhysicalRowV2?,after: NativeHistoryPhysicalRowV2?) {
        capture.requireShape()
        for ((row,isAfter) in listOf(before to false,after to true)) {
            if(row==null) continue
            val columns=historyColumnsV2(row.table)
            for ((index,name) in row.table.keyColumns.withIndex()) {
                val cell=row.cells[columns.indexOfFirst { it.name==name }]
                val text=if(isAfter) when(index) { 0 -> capture.afterKey1; 1 -> capture.afterKey2Text; else -> capture.afterKey3Text }
                    else when(index) { 0 -> capture.beforeKey1; 1 -> capture.beforeKey2Text; else -> capture.beforeKey3Text }
                val integer=if(isAfter) when(index) { 1 -> capture.afterKey2Integer; 2 -> capture.afterKey3Integer; else -> null }
                    else when(index) { 1 -> capture.beforeKey2Integer; 2 -> capture.beforeKey3Integer; else -> null }
                when(cell) {
                    is NativeHistoryPhysicalCellV2.Text -> {
                        val bytes=cell.bytes()
                        try { check(integer==null && text!=null && bytes.contentEquals(text)) } finally { bytes.fill(0) }
                    }
                    is NativeHistoryPhysicalCellV2.Integer -> check(text==null && integer==cell.value)
                    else -> error("Original key storage class changed")
                }
            }
        }
    }
    private suspend fun readCapture(ordinal: Long): RuntimeHistoryCaptureRowV2 = connection.usePrepared(
        "SELECT * FROM runtime_history_capture_v2 WHERE captureOrdinal=?") { s ->
        s.bindLong(1,ordinal); check(s.step())
        RuntimeHistoryCaptureRowV2(s.getLong(0),s.getText(1),s.getLong(2).toInt(),s.getLong(3).toInt(),s.optionalLong(4),s.optionalLong(5),
            s.optionalBlob(6),s.optionalBlob(7),s.optionalLong(8),s.optionalBlob(9),s.optionalLong(10),s.optionalBlob(11),s.optionalBlob(12),
            s.optionalLong(13),s.optionalBlob(14),s.optionalLong(15)).also { check(!s.step()) }
    }
    private suspend fun readTip(table: RuntimeHistoryTableV2,key: ByteArray): RuntimeHistoryRowTipRowV2? = connection.usePrepared(
        "SELECT * FROM runtime_history_row_tip_v2 WHERE tableTag=? AND `key`=?") { s ->
        s.bindLong(1,table.tag.toLong()); s.bindBlob(2,key)
        if (!s.step()) null else RuntimeHistoryRowTipRowV2(s.getLong(0).toInt(),s.getBlob(1),s.getLong(2),s.getLong(3),s.getLong(4).toInt(),
            s.getLong(5).toInt(),s.optionalLong(6),s.optionalText(7)).also { check(!s.step()) }
    }
    private suspend fun readRetirementHeader(sequence: Long): Pair<RuntimeHistoryEventHeaderV2, PayloadDigest> = connection.usePrepared(
        "SELECT typeof(header),length(header),header,typeof(eventDigest),length(eventDigest),eventDigest FROM runtime_history_event_v2 WHERE sequence=?") { s ->
        s.bindLong(1, sequence); check(s.step())
        check(s.getText(0) == "blob" && s.getColumnType(1) == 1 &&
            s.getLong(1) in 1L..RuntimeHistoryProfileV2.MAX_HEADER_BYTES.toLong() && s.getColumnType(2) == 4 &&
            s.getText(3) == "text" && s.getColumnType(4) == 1 && s.getLong(4) == 64L && s.getColumnType(5) == 3)
        val bytes = s.getBlob(2)
        try {
            check(bytes.size.toLong() == s.getLong(1))
            (RuntimeHistoryCodecV2.decodeHeader(bytes) to PayloadDigest(s.getText(5))).also { check(!s.step()) }
        } finally { bytes.fill(0) }
    }
    /** Classify a native final decision using its actual bounded canonical intent, not
     * an SQL purpose or a copied event-digest string. This also binds the dependencies
     * and facts whose digests are inside that native-protected original intent. */
    private class ProtectedDecisionIntent(val intent: NativeHistoryOriginalIntentV2,
        val approvalIssuance: RuntimeActionApprovalIssuanceEvidenceV1?,
        val serialPublication: RuntimeSerialPublicationRequestEvidenceV1?)

    private suspend fun readProtectedDecisionIntent(header: RuntimeHistoryEventHeaderV2,
        decision: NativeHistoryProtectedFinalDecision): ProtectedDecisionIntent {
        val capsule = RuntimeHistoryCapsule(header, capsuleOnly = true)
        try {
            val first = (header.imageBytes / RuntimeHistoryProfileV2.MAX_CHUNK_BYTES).toInt()
            for (ordinal in first until header.chunkCount) {
                val chunk = connection.usePrepared(
                    "SELECT typeof(digest),length(digest),digest,typeof(bytes),length(bytes),bytes " +
                        "FROM runtime_history_chunk_v2 WHERE sequence=? AND ordinal=?") { s ->
                    s.bindLong(1, header.sequence); s.bindLong(2, ordinal.toLong()); check(s.step())
                    check(s.getText(0) == "text" && s.getColumnType(1) == 1 && s.getLong(1) == 64L &&
                        s.getColumnType(2) == 3 && s.getText(3) == "blob" && s.getColumnType(4) == 1 &&
                        s.getLong(4) in 1L..RuntimeHistoryProfileV2.MAX_CHUNK_BYTES.toLong() && s.getColumnType(5) == 4)
                    val bytes = s.getBlob(5)
                    try {
                        val digest = s.getText(2)
                        check(bytes.size.toLong() == s.getLong(4) &&
                            RuntimeHistoryCodecV2.chunkDigest(header.sequence, ordinal, bytes).value == digest && !s.step())
                        RuntimeHistoryChunkRowV2(header.sequence, ordinal, digest, bytes)
                    } catch (failure: Throwable) { bytes.fill(0); throw failure }
                }
                try { capsule.append(chunk) } finally { chunk.bytes.fill(0) }
            }
            val intent = capsule.finish()
            check(sha256(capsule.intent) == decision.originalIntentDigest &&
                intent.originalRequestId == decision.declaration.originalRequestId &&
                intent.originalRequestDigest == decision.declaration.originalRequestDigest &&
                intent.originalActorId == decision.declaration.actorId)
            val issuance = if (intent.purposeTag == RuntimeWritePurposeV2.APPROVAL_ISSUE.tag) {
                check(RuntimeActionApprovalEvidenceCodec.hasIssuanceProfile(capsule.facts))
                val evidence = RuntimeActionApprovalEvidenceCodec.decodeIssuance(capsule.facts)
                check(header.sequence > 1 && header.purpose == RuntimeWritePurposeV2.APPROVAL_ISSUE && header.changeCount == 4 &&
                    evidence.requestor.scope == coordinates.scope && intent.originalRequestId == evidence.approvalId &&
                    intent.originalActorId == evidence.requestor.actorId && intent.originalOperationId == null && intent.resources.isEmpty() &&
                    intent.originalRequestDigest == evidence.value().issueRequestDigest && decision.declaration == evidence.protectedFinalDeclaration())
                val dependencies = RuntimeActionApprovalEvidenceCodec.dependencies(evidence)
                try { check(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
                evidence
            } else null
            val serial = if (intent.purposeTag == RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST.tag) {
                val evidence = RuntimeSerialPublicationRequestEvidenceCodec.decode(capsule.facts)
                val request = evidence.requestValue()
                check(header.sequence > 1 && header.purpose == RuntimeWritePurposeV2.SERIAL_PUBLICATION_REQUEST && header.changeCount == 1 &&
                    request.scope == coordinates.scope && request.originalAllocationDigest == native.original.record.allocation.digest &&
                    intent.originalRequestId == request.requestId && intent.originalActorId == evidence.authorization.actorId &&
                    intent.originalOperationId == null && intent.resources.isEmpty() && intent.originalRequestDigest == evidence.semanticDigest &&
                    decision.declaration == evidence.protectedFinalDeclaration())
                val dependencies = RuntimeSerialPublicationRequestEvidenceCodec.dependencies(evidence)
                try { check(dependencies.contentEquals(capsule.dependencies)) } finally { dependencies.fill(0) }
                evidence
            } else null
            return ProtectedDecisionIntent(intent, issuance, serial)
        } finally { capsule.clear() }
    }
    private suspend fun insertVersion(v: RuntimeHistoryRowVersionRowV2) = execute(
        "INSERT INTO runtime_history_row_version_v2 VALUES("+List(16) { "?" }.joinToString(",")+")",listOf(v.sequence,v.ordinal,v.tableTag,
            v.key,v.version,v.previousSequence,v.previousOrdinal,v.previousVersion,v.mutationKind,v.beforeRowId,v.beforeDigest,
            v.afterRowId,v.afterDigest,v.afterKind,v.imageOffset,v.imageBytes))
    private suspend fun readVersion(sequence: Long,ordinal: Int): RuntimeHistoryRowVersionRowV2 = connection.usePrepared(
        "SELECT * FROM runtime_history_row_version_v2 WHERE sequence=? AND ordinal=?") { s ->
        s.bindLong(1,sequence); s.bindLong(2,ordinal.toLong()); check(s.step())
        RuntimeHistoryRowVersionRowV2(s.getLong(0),s.getLong(1).toInt(),s.getLong(2).toInt(),s.getBlob(3),s.getLong(4),s.optionalLong(5),
            s.optionalInt(6),s.optionalLong(7),s.getLong(8).toInt(),s.optionalLong(9),s.optionalText(10),s.optionalLong(11),s.optionalText(12),
            s.getLong(13).toInt(),s.optionalLong(14),s.optionalInt(15)).also { check(!s.step()) }
    }
    private suspend fun readChunk(sequence: Long,ordinal: Int): RuntimeHistoryChunkRowV2 = connection.usePrepared(
        "SELECT digest,bytes FROM runtime_history_chunk_v2 WHERE sequence=? AND ordinal=?") { s ->
        s.bindLong(1,sequence); s.bindLong(2,ordinal.toLong()); check(s.step())
        RuntimeHistoryChunkRowV2(sequence,ordinal,s.getText(0),s.getBlob(1)).also { check(!s.step()) }
    }
    private inner class PayloadWriter(private val sequence: Long) {
        private val bytes=ByteArray(RuntimeHistoryProfileV2.MAX_CHUNK_BYTES)
        private var used=0
        var chunks=0; private set
        private var total=0L
        suspend fun append(value: ByteArray) {
            total=historyAddV2(total,value.size.toLong(),RuntimeHistoryProfileV2.MAX_EVENT_BYTES)
            var at=0
            while (at<value.size) {
                val length=minOf(bytes.size-used,value.size-at); value.copyInto(bytes,used,at,at+length); used+=length; at+=length
                if (used==bytes.size) flush()
            }
        }
        private suspend fun flush() {
            historyCapacityV2(chunks<RuntimeHistoryProfileV2.MAX_EVENT_CHUNKS)
            val value=bytes.copyOf(used)
            try { execute("INSERT INTO runtime_history_chunk_v2 VALUES(?,?,?,?)",listOf(sequence,chunks,
                RuntimeHistoryCodecV2.chunkDigest(sequence,chunks,value).value,value)) }
            finally { value.fill(0); bytes.fill(0); used=0 }
            chunks++
        }
        suspend fun finish() { if (used>0) flush(); check(chunks>0) }
        fun clear() { bytes.fill(0) }
    }
}

private fun SQLiteStatement.bindHistory(index: Int,value: Any?) { when(value) {
    null -> bindNull(index); is String -> bindText(index,value); is Long -> bindLong(index,value)
    is Int -> bindLong(index,value.toLong()); is ByteArray -> bindBlob(index,value); else -> error("Unsupported history scalar")
} }
private fun SQLiteStatement.optionalLong(column: Int): Long? = if (isNull(column)) null else getLong(column)
private fun SQLiteStatement.optionalInt(column: Int): Int? = optionalLong(column)?.toInt()
private fun SQLiteStatement.optionalText(column: Int): String? = if (isNull(column)) null else getText(column)
private fun SQLiteStatement.optionalBlob(column: Int): ByteArray? = if (isNull(column)) null else getBlob(column)
private fun clearCapture(c: RuntimeHistoryCaptureRowV2) { listOf(c.beforeKey1,c.beforeKey2Text,c.beforeKey3Text,c.afterKey1,c.afterKey2Text,c.afterKey3Text).forEach { it?.fill(0) } }
