package com.ukkera.pos.database

import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.CompanyBusinessPublicationOutput
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Installed at trusted application composition, never selected by a request or by history.
 * Implementations compare real retained business producers; they do not execute handlers,
 * restore an employee session, supply SQL, or construct a historical CommandReadView. */
public interface RuntimeHistoryBusinessProducer {
    public val commands: Set<CommandKey>
    /** Installed once for native-derived current receipt membership. Missing support stays
     * unavailable; an absent classifier never certifies an empty receipt population. */
    public val retailCustomerReceiptEventClassifier: RuntimeRetailCustomerReceiptEventClassifier? get() = null
    /** Independent installed producer promise, not a source/approval proof. Unsupported
     * producers cannot replay v3 original-business attribution under a current session. */
    public fun actionApprovalBusinessAttribution(command: CommandKey): ActionApprovalBusinessAttributionSupport =
        ActionApprovalBusinessAttributionSupport.UNSUPPORTED
    /** False retains the unsupported-history hold. True alone is not a proof: core also
     * requires the exact original requirement and a complete effects/resource comparison. */
    public suspend fun join(command: RuntimeHistoricalCommand): Boolean

    /** Optional typed FOOD current closure, after EVERY historical command, native image,
     * literal effect and resource has joined. The supplied state contains only actual native
     * current rows and their already joined originals; it cannot create a read/write view.
     * True still requires core's complete row/writer/identity comparison. Unsupported current
     * food states retain their existing hold; this callback does not grant restart admission. */
    public suspend fun joinCurrentFood(state: RuntimeHistoricalFoodCurrentState): Boolean = false
}

/** A core-selected BEFORE row and its exact, already fully joined native original.
 * Neither the row nor this inert pairing grants current authority or permits an effect. */
public class RuntimeHistoricalAggregateSource internal constructor(
    public val row: StoredAggregate,
    public val original: RetainedOriginalV2,
) {
    override fun toString(): String = "RuntimeHistoricalAggregateSource([REDACTED])"
}

/** Inert provenance discriminant. Native retention did not authorize an employee command. */
public enum class RuntimeHistoricalAuthority { EMPLOYEE_COMMAND, NATIVE_ORIGINAL }

/** The whole original native requirement/certificate and retained provenance DATA. This
 * cannot create a lease, instruction, employee grant or missing historical original. */
public class RuntimeHistoricalNativeOriginal internal constructor(evidence: RuntimeNativeOriginalImportEvidenceV1) {
    public val canonicalRequirement: StoredBytes = evidence.canonicalRequirement
    public val certificate: StoredBytes = evidence.certificate
    public val provenance: NativeOriginalJournalProvenanceV1 = evidence.provenance
    public val originalNativeRecordDigest: PayloadDigest = evidence.nativeRecordDigest
    override fun toString(): String = "RuntimeHistoricalNativeOriginal([REDACTED])"
}

/** Immutable original inline approval coordinates, produced only by the complete native
 * chronological issuance/use join. This projection cannot create an AuthorizedAction,
 * authenticate either human, consume an approval or renew an original deadline. */
public class RuntimeHistoricalActionApproval internal constructor(
    value: com.ukkera.pos.security.ProtectedLocalActionApproval,
    public val command: CommandKey,
    public val operationId: OperationId,
    public val semanticDigest: PayloadDigest,
) {
    public val approvalId: DomainId = value.record.approvalId
    public val approverId: DomainId = value.record.approverId
    public val approverPolicyRevision: Revision = value.record.approverPolicyRevision
    public val requestorId: DomainId = value.record.requestorId
    public val requestorPolicyRevision: Revision = value.record.requestorPolicyRevision
    public val requirement: AuthorizationRequirement = value.record.requirement.copy()
    init { require(requirement.binding?.operationId == operationId && requirement.binding?.payloadDigest == semanticDigest) }
    override fun toString(): String = "RuntimeHistoricalActionApproval([REDACTED])"
}

/** A core-created, active-scan-bound historical command. All observations come from the
 * complete native-selected before-cut, not today's mutable rows or retained source DTOs.
 * This inert object cannot authenticate, authorize, write, re-prepare, or mint an admission. */
public class RuntimeHistoricalCommand internal constructor(
    private val owner: RuntimeBusinessHistoryJoin,
    internal val capture: RuntimeBusinessHistoryJoin.CommandCapture,
    public val original: RetainedOriginalV2,
) {
    public val scope: BranchScope get() = capture.scope
    public val operationId: OperationId get() = capture.producer.operationId
    public val command: CommandKey get() = capture.producer.command
    public val actorId: DomainId get() = capture.producer.originalActorId
    /** Original BUSINESS provenance only; native historical mirrors stay explicitly in its Start. */
    public val authenticationId: DomainId get() {
        if (capture.employee == null) unavailable()
        return capture.authenticationId
    }
    public val policyRevision: Revision get() = capture.employee?.authorization?.policyRevision ?: unavailable()
    /** Original native-joined BUSINESS generation, never a restored session or current grant. */
    public val authorizationGeneration: SessionGeneration get() {
        if (capture.employee == null) unavailable()
        return capture.businessGeneration
    }
    public val semanticDigest: PayloadDigest get() = capture.producer.semanticDigest
    public val authority: RuntimeHistoricalAuthority get() = if (capture.nativeOriginal == null)
        RuntimeHistoricalAuthority.EMPLOYEE_COMMAND else RuntimeHistoricalAuthority.NATIVE_ORIGINAL
    public val nativeOriginal: RuntimeHistoricalNativeOriginal? = capture.nativeOriginal?.let(::RuntimeHistoricalNativeOriginal)
    public val originalApproval: RuntimeHistoricalActionApproval? get() = capture.originalApproval
    /** Core-selected CLOSED audited Review rejection only; never a general rejected command. */
    public val supervisorReview: RuntimeHistoricalSupervisorReviewProfile? get() = capture.supervisorReview
    public val normalized: StoredBytes get() = when (val value = original) {
        is RetainedOriginalV2.Legacy -> value.evidence.normalized
        is RetainedOriginalV2.Streamed -> value.evidence.normalized
    }
    public val recordedAt: UtcInstant get() = when (val value = original) {
        is RetainedOriginalV2.Legacy -> value.evidence.recordedAt
        is RetainedOriginalV2.Streamed -> value.evidence.identity.recordedAt
    }
    /** Compares immutable setup identity against this active native history owner's saved
     * receipt. It observes historical DATA only; it grants no current action or session. */
    public suspend fun matchesOriginalSetup(installationId: DomainId, originalRequestId: DomainId,
        issuedAttemptId: DomainId, attemptSequence: Long, definitionDigest: PayloadDigest,
        genesisDigest: PayloadDigest, receiptDigest: PayloadDigest): Boolean =
        owner.matchesOriginalSetup(this, installationId, originalRequestId, issuedAttemptId,
            attemptSequence, definitionDigest, genesisDigest, receiptDigest)

    /** The exact original serial-qualification observation, independent of restart time.
     * Existing native history retains audit timestamps and final deadline decisions,
     * but no qualified serial-time source/precision. Neither fact supplies that gap. */
    public suspend fun qualifiedSerialRegistrationTime(): QualifiedSerialRegistrationTime? {
        owner.requireActive(this)
        return null
    }
    private var requirementChecked = false
    private var effectsChecked = false
    private var finalDeadlineChecked = false
    private var checkedDeadline: UtcInstant? = null

    public suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        owner.requireActive(this)
        val row = owner.aggregateBefore(capture, key)
        owner.requireActive(this)
        return row
    }
    /** Exact native BEFORE source. A present row requires its strictly earlier capture to
     * have completed the installed business join, and its literal original AFTER write must
     * match every row field. Null is only complete before-cut row absence, not empty rights. */
    public suspend fun aggregateSource(key: AggregateKey): RuntimeHistoricalAggregateSource? {
        owner.requireActive(this)
        val source = owner.aggregateSourceBefore(capture, key)
        owner.requireActive(this)
        return source
    }
    /** An actual earlier, fully joined native original in this SAME scope/epoch/scan.
     * Missing is not evidence of an unused ID. Present legacy facts are inert; streamed
     * traversals remain confined to this active command. No supplied history reader exists. */
    public suspend fun earlierOriginal(operationId: OperationId): RetainedOriginalV2? {
        owner.requireActive(this)
        val original = owner.originalBefore(capture, operationId)
        owner.requireActive(this)
        return original
    }
    /** A genuine strictly-earlier original scoped to this callback. Its real owner and
     * exact registered proof remain live throughout use; do not retain this loan beyond
     * the callback. Missing original data is null, not evidence of an unused ID. */
    public suspend fun <T> withEarlierOriginal(operationId: OperationId, use: suspend (RetainedOriginalV2?) -> T): T {
        owner.requireActive(this)
        val result = owner.withOriginalBefore(capture, operationId) { original ->
            owner.requireActive(this)
            val selected = use(original)
            owner.requireActive(this)
            selected
        }
        owner.requireActive(this)
        return result
    }
    /** Complete kind membership at this original cut; never a caller-selected prefix. */
    public suspend fun aggregates(kind: String): List<StoredAggregate> {
        owner.requireActive(this)
        requireProtocolName(kind)
        val rows = owner.aggregatesBefore(capture).filter { it.key.kind == kind }
        owner.requireActive(this)
        return rows
    }
    /** Complete native kind membership with one scoped row at a time. The row's payload
     * is released after consume; retain bounded decoded business facts, not this row or
     * its payload. An exception abandons the whole census, never certifies a prefix. */
    public suspend fun forEachAggregate(kind: String, consume: suspend (StoredAggregate) -> Unit) {
        owner.requireActive(this)
        requireProtocolName(kind)
        owner.forEachAggregateBefore(capture, kind) { row ->
            owner.requireActive(this)
            consume(row)
            owner.requireActive(this)
        }
        owner.requireActive(this)
    }
    /** Complete native completed-output membership observed by THIS original command.
     * The native owner selects/authenticates the signed original child and immutable
     * contents; today's ledger and a caller-supplied manifest cannot supply this cut.
     * Missing original provenance throws unavailable, never an invented empty list. */
    public suspend fun completedNativeBusinessPublicationsBefore(): List<CompanyBusinessPublicationOutput> {
        owner.requireActive(this)
        val source = owner.businessPublicationSourceBefore(capture) ?: unavailable()
        owner.requireActive(this)
        return source.snapshot.completedPublications
    }
    public suspend fun claim(key: ClaimKey): DomainId? {
        owner.requireActive(this)
        val ownerId = owner.claimBefore(capture, key)
        owner.requireActive(this)
        return ownerId
    }

    /** Complete matching membership from this active native BEFORE cut. Each retained
     * claim has a strictly earlier, fully joined writer; no current SQL view, caller-supplied
     * census or partial-page absence is accepted. Owners remain independently read by claim(). */
    public suspend fun claimKeys(namespace: String, prefix: String): List<ClaimKey> {
        owner.requireActive(this)
        requireProtocolName(namespace)
        require(prefix.length in 1..255 && prefix.all { it.code in 0x21..0x7e })
        val keys = owner.claimKeysBefore(capture, namespace, prefix)
        owner.requireActive(this)
        return keys
    }
    /** Complete matching native membership in strict canonical key order without a
     * branch-sized key list. Each present key has its genuine strictly-earlier joined
     * writer; callback failure cannot certify partial membership or absence. */
    public suspend fun forEachClaimKey(namespace: String, prefix: String, consume: suspend (ClaimKey) -> Unit) {
        owner.requireActive(this)
        requireProtocolName(namespace)
        require(prefix.length in 1..255 && prefix.all { it.code in 0x21..0x7e })
        owner.forEachClaimKeyBefore(capture, namespace, prefix) { key ->
            owner.requireActive(this)
            consume(key)
            owner.requireActive(this)
        }
        owner.requireActive(this)
    }

    /** Receiving eligibility for this exact order from genuine native origins strictly
     * BEFORE this registered command. No recipient session/principal or current read is
     * created. Null is known absent/ineligible; unavailable history throws the existing
     * unresolved-protocol signal, never an accepting default or fabricated absence. */
    public suspend fun localHandoffRecipientBefore(orderId: DomainId,
        recipientId: DomainId): RuntimeHistoricalLocalHandoffRecipient? {
        owner.requireActive(this)
        val recipient = owner.localHandoffRecipientBefore(capture, orderId, recipientId)
        owner.requireActive(this)
        return recipient
    }

    public suspend fun requireRequirement(expected: AuthorizationRequirement) {
        owner.requireActive(this)
        historyShapeV2(!requirementChecked && capture.nativeOriginal == null && expected == capture.employee?.requirement?.value())
        requirementChecked = true
    }

    /** A native retention compares the WHOLE historical physical requirement, not a fake
     * current AuthorizationRequirement or a current employee/session/policy evaluation. */
    public suspend fun requireNativeOriginalRequirement(expected: StoredBytes) {
        owner.requireActive(this)
        historyShapeV2(!requirementChecked && expected == capture.nativeOriginal?.canonicalRequirement)
        requirementChecked = true
    }

    /** Compare a deadline derived from this producer's exact retained BEFORE sources to the
     * original native COMMITTED witness. recordedAt is never substituted for finalWall. */
    public suspend fun requireFinalDeadlineDecision(expected: UtcInstant?) {
        owner.requireActive(this)
        historyShapeV2(!finalDeadlineChecked)
        owner.requireFinalDeadlineDecision(capture, expected)
        checkedDeadline = expected; finalDeadlineChecked = true
    }

    /** Missing evidence in the complete native snapshot leaves an older finite original
     * unsupported. A present decision must pass the same exact native comparison; malformed
     * or mismatched evidence still throws. False checks nothing and cannot qualify effects. */
    public suspend fun tryRequireFinalDeadlineDecision(expected: UtcInstant): Boolean {
        owner.requireActive(this)
        historyShapeV2(!finalDeadlineChecked)
        if (!owner.hasRetainedFinalDeadlineDecision(capture)) return false
        requireFinalDeadlineDecision(expected)
        return true
    }

    /** Closes EVERY literal Current/Absent/Claim/complete range against the genuine native
     * BEFORE cut, including exact earlier fully joined original writes for each present row.
     * Returns observations only, never a reconstructed historical CommandReadView. */
    public suspend fun requireLegacyReadEvidence(): StockReadObservations? {
        owner.requireActive(this)
        historyShapeV2(capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.returnCaseQualificationRejection == null && capture.employeeReturnCaseQualificationRejection == null &&
            capture.returnStockDispositionRejection == null && capture.employeeReturnStockDispositionRejection == null)
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        val observed = StockReadEvidenceV1.read(retained) ?: return null
        owner.requireLegacyReadEvidence(capture, observed)
        owner.requireActive(this)
        return observed
    }

    /** Exact canonical result, ordered AFTER writes/claims/jobs, genuine BEFORE revisions,
     * and the original native affected resources. No hash/count-only effects certificate. */
    public suspend fun requireLegacyEffects(expected: ProposedOutcome.Apply, resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        historyShapeV2(!effectsChecked && capture.nativeOriginal == null && capture.foodPaymentRejection == null &&
            capture.employeeFoodPaymentRejection == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            capture.returnCaseQualificationRejection == null && capture.employeeReturnCaseQualificationRejection == null &&
            capture.returnStockDispositionRejection == null && capture.employeeReturnStockDispositionRejection == null)
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(retained.committed && retained.nativeOriginalProvenance == null && retained.result == expected.result &&
            retained.aggregateWrites.size == expected.changes.size && retained.claimChanges.size == expected.claims.size &&
            retained.jobs.size == expected.jobs.size && capture.resources == resources)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(expected.mustCommitBefore)
        historyShapeV2(checkedDeadline == expected.mustCommitBefore)
        requireEffects(retained, expected)
        effectsChecked = true
    }

    /** Original terminal business refusal AFTER genuine inline approval preparation. The
     * registered native proof preserves approved-request provenance without consuming it.
     * No other legacy rejection profile, protected/domain write, claim, job or wall witness
     * can enter this lane. The installed producer must derive the exact first refusal. */
    public suspend fun requireApprovedLegacyRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val approval = capture.approvalHistory ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        approval.requireImagesJoined()
        historyShapeV2(requirementChecked && !effectsChecked && capture.approvalRejected && capture.nativeOriginal == null &&
            capture.employee?.actionApprovalId == approval.wrappedEvidence.approvalUse.approvalId &&
            !retained.committed && retained.nativeOriginalProvenance == null && retained.code == expected.code &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources && capture.mutations.size == 5 &&
            capture.mutations.all { it.table in setOf(RuntimeHistoryTableV2.OPERATION, RuntimeHistoryTableV2.JOURNAL,
                RuntimeHistoryTableV2.AUDIT, RuntimeHistoryTableV2.OUTBOX, RuntimeHistoryTableV2.BRANCH) })
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** Exact audited Review rejection only. The installed typed producer derives the whole
     * sensitive result from genuine native BEFORE sources. Core separately closes the actual
     * operation/result/journal/audit/upload/branch mutation set; no domain effect is allowed. */
    public suspend fun requireSupervisorReviewRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.supervisorReview ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(!effectsChecked && capture.nativeOriginal == null && capture.branchActivityActivationRejection == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.floorConfigurationRejection == null && capture.returnRightsActivationRejection == null &&
            capture.employeeReturnRightsActivationRejection == null && !retained.committed &&
            retained.nativeOriginalProvenance == null && retained.command == profile.command && retained.code == profile.rejectionCode &&
            expected.code == profile.rejectionCode && retained.result == expected.result && retained.aggregateWrites.isEmpty() &&
            retained.claimChanges.isEmpty() && retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalSupervisorReviewProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED deterministic purchasing-policy activation refusal only. The installed policy
     * producer derives the whole result from authenticated original bytes and exact native
     * BEFORE rows/claims with already joined earlier writers. Core independently closes the
     * original operation/result/journal/audit/upload/branch mutations. No domain effects,
     * approval, unavailable-trust certificate or supervisor authority enter this path. */
    public suspend fun requirePurchasingPolicyRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.purchasingPolicyRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(!effectsChecked && capture.nativeOriginal == null && capture.supervisorReview == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.branchActivityActivationRejection == null && capture.floorConfigurationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            !retained.committed && retained.nativeOriginalProvenance == null && retained.command == profile.command &&
            retained.code in profile.rejectionCodes && expected.code == retained.code && retained.result == expected.result &&
            retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
            capture.resources == resources && capture.mutations.size == RuntimeHistoricalPurchasingPolicyRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED original ReassignOrder refusal only. The installed sales producer must first
     * prove the canonical exact Legacy subtype, whole original manager requirement and
     * actual first refusal/result/resources from genuine already-joined native BEFORE
     * sources. A candidate command/action marker or a capacity label is not that proof.
     * Core independently closes every native nonbusiness mutation and read dependency. */
    public suspend fun requireOrderReassignmentRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.orderReassignmentRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(!effectsChecked && capture.nativeOriginal == null && capture.supervisorReview == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.purchasingPolicyRejection == null && capture.branchActivityActivationRejection == null &&
            capture.floorConfigurationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command && retained.code in profile.rejectionCodes && expected.code == retained.code &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalOrderReassignmentRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED opening supplier-obligation refusal only. The installed purchasing producer
     * must derive the exact canonical first refusal/result/resources from genuine native
     * BEFORE supplier, obligation, claim and already-joined original sources, then compare
     * the whole original amount-bound employee requirement. Core separately closes all
     * native operation/result/journal/audit/upload/branch images and read dependencies. */
    public suspend fun requireOpeningSupplierObligationRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.openingSupplierObligationRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(!effectsChecked && capture.nativeOriginal == null && capture.supervisorReview == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.purchasingPolicyRejection == null && capture.orderReassignmentRejection == null &&
            capture.branchActivityActivationRejection == null && capture.floorConfigurationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            !retained.committed && retained.nativeOriginalProvenance == null && retained.command == profile.command &&
            retained.code in profile.rejectionCodes && expected.code == retained.code && retained.result == expected.result &&
            retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
            capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalOpeningSupplierObligationRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED V2 invoice-capture refusal only. The installed purchasing producer first
     * compares the whole original capture requirement, then derives the canonical first
     * refusal/result/resources from genuine already-joined native BEFORE sources. Core
     * independently closes the operation/result/journal/audit/upload/branch images and
     * read dependencies. No receiving, payable, claim, job, approval or deadline fits here. */
    public suspend fun requireInvoiceCaptureRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.invoiceCaptureRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null && capture.employeeHistory == null &&
            capture.nativeEmployeeStart == null && capture.approvalHistory == null && capture.employee?.actionApprovalId == null &&
            !retained.committed && retained.nativeOriginalProvenance == null && retained.command == profile.command &&
            retained.code in profile.rejectionCodes && expected.code == retained.code && retained.result == expected.result &&
            retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
            capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalInvoiceCaptureRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED V2 captured-invoice match and payable-recognition refusal only. The installed
     * purchasing producer must first compare the whole original monetary requirement with
     * the actual immutable invoice revision and full raw claim, then derive the exact first
     * refusal/result/resources from genuine already-joined native BEFORE sources. Core
     * separately binds the original actor/read and closes every nonbusiness image and read
     * dependency. No business write, claim, job, approval or deadline can complete this lane. */
    public suspend fun requireCapturedInvoiceMatchRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.capturedInvoiceMatchRejection ?: unavailable()
        owner.requireCapturedInvoiceMatchRejectionOriginal(this)
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null && capture.employeeHistory == null &&
            capture.nativeEmployeeStart == null && capture.approvalHistory == null && capture.employee?.actionApprovalId == null &&
            capture.invoiceCaptureRejection == null && capture.employeeInvoiceCaptureRejection == null &&
            !retained.committed && retained.nativeOriginalProvenance == null && retained.command == profile.command &&
            retained.code in profile.rejectionCodes && expected.code == retained.code && retained.result == expected.result &&
            retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() && retained.jobs.isEmpty() &&
            capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalCapturedInvoiceMatchRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED initial branch-activity refusal only. The installed activity producer must
     * authenticate the exact original candidate and fully prove the earlier activation,
     * then derive the whole original requirement/refusal/result/resources from genuine
     * already-joined native BEFORE sources. No unavailable-source refusal, replacement,
     * approval, native import or business effect can complete this lane. Core separately
     * closes every operation/result/journal/audit/upload/branch image and read dependency. */
    public suspend fun requireBranchActivityActivationRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.branchActivityActivationRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(!effectsChecked && capture.nativeOriginal == null && capture.employeeHistory == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.nativeEmployeeStart == null && capture.supervisorReview == null && capture.purchasingPolicyRejection == null &&
            capture.orderReassignmentRejection == null && capture.openingSupplierObligationRejection == null &&
            capture.floorConfigurationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            capture.employee?.actionApprovalId == null && !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command && retained.code in profile.rejectionCodes && expected.code == retained.code &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalBranchActivityActivationRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED floor/table configuration refusal only. The installed directory producer
     * first compares the whole original employee requirement and derives the canonical
     * first refusal/result/resources from genuine complete native BEFORE sources with
     * already joined earlier writers. Core separately closes original ownership, every
     * operation/result/journal/audit/upload/branch image and read dependency. No business
     * effects, claims, jobs, approval, native import or deadline can complete this lane. */
    public suspend fun requireFloorConfigurationRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.floorConfigurationRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        owner.requireFloorConfigurationRejectionOriginal(this)
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null && capture.employeeHistory == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.nativeEmployeeStart == null && capture.supervisorReview == null && capture.employeeSupervisorReview == null &&
            capture.purchasingPolicyRejection == null && capture.employeePurchasingPolicyRejection == null &&
            capture.orderReassignmentRejection == null && capture.employeeOrderReassignmentRejection == null &&
            capture.openingSupplierObligationRejection == null && capture.employeeOpeningSupplierObligationRejection == null &&
            capture.branchActivityActivationRejection == null && capture.employeeBranchActivityActivationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            capture.employee?.actionApprovalId == null && !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command && retained.code in profile.rejectionCodes && expected.code == retained.code &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalFloorConfigurationRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED V1 configuration or exact supplier-only V2 refusal. The installed configuration
     * producer first compares the whole original employee requirement and exact subtype,
     * then derives the canonical refusal/result/resources from genuine native BEFORE
     * sources with already joined earlier writers. Core separately closes original
     * ownership, every native operation/result/journal/audit/upload/branch mutation and
     * read dependency. No business effect, claim, job, approval or deadline fits here. */
    public suspend fun requireConfigurationRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        val profile = when (command) {
            RuntimeHistoricalConfigurationRejectionProfile.CONFIGURATION.command -> RuntimeHistoricalConfigurationRejectionProfile.CONFIGURATION
            RuntimeHistoricalConfigurationRejectionProfile.SUPPLIER_V2.command -> RuntimeHistoricalConfigurationRejectionProfile.SUPPLIER_V2
            else -> unavailable()
        }
        requireConfigurationProfileRejection(expected, resources, profile)
    }

    /** CLOSED pricing receipt/activation/branch-change/history-index refusal only. The
     * installed pricing producer compares the whole original employee requirement and
     * derives the canonical first refusal/result/resources from the complete native BEFORE
     * cut and already joined earlier originals. Core separately closes every native mutation
     * and dependency. Action approvals, business effects, jobs and deadlines cannot join. */
    public suspend fun requirePricingRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>): Unit = requireConfigurationProfileRejection(expected, resources,
            RuntimeHistoricalConfigurationRejectionProfile.PRICING)

    /** CLOSED catalog-release activation refusal only. The installed producer positively
     * qualifies the candidate and complete native sequence/member originals, compares the
     * whole catalog/expected-storage requirement, and derives the exact first refusal from
     * genuine BEFORE sources. Core requires this same original's native publication cut and
     * closes all five nonbusiness mutations, result and resources. Missing source, malformed
     * history, business effects, claims, jobs, approval or deadlines cannot complete this lane. */
    public suspend fun requireCatalogReleaseRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>): Unit = requireConfigurationProfileRejection(expected, resources,
            RuntimeHistoricalConfigurationRejectionProfile.CATALOG_RELEASE)

    /** CLOSED documentary Begin/chunk/complete refusal only. The installed producer
     * proves the normalized request, exact original Begin actor and first refusal using
     * genuine BEFORE sources and joined originals. Core closes the original authority,
     * all five nonbusiness mutations, result and resources; no prefix-based fallback,
     * receiving authority, domain writes, claims, jobs or approval fits this lane. */
    public suspend fun requirePurchasingDocumentRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        val profile = when (command) {
            RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_BEGIN.command -> RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_BEGIN
            RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_CHUNK.command -> RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_CHUNK
            RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_COMPLETE.command -> RuntimeHistoricalConfigurationRejectionProfile.DOCUMENT_COMPLETE
            else -> unavailable()
        }
        requireConfigurationProfileRejection(expected, resources, profile)
    }

    /** Closed additive direct-arrival refusal. The installed custody producer must derive
     * the whole first refusal and resources from genuine original BEFORE sources. Core
     * authenticates original employee ownership and every captured image/read dependency;
     * no custody, documentary-use, claim or job AFTER write is allowed on this lane. */
    public suspend fun requireUnpricedArrivalRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        val profile = when (command) {
            CommandKey("local.stock.arrival.record", 2) -> RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_RECORD
            CommandKey("local.stock.arrival.qualify", 2) -> RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_QUALIFY
            CommandKey("local.stock.arrival.reject", 2) -> RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_REJECT
            CommandKey("local.stock.arrival.dispatch", 2) -> RuntimeHistoricalConfigurationRejectionProfile.ARRIVAL_DISPATCH
            else -> unavailable()
        }
        requireConfigurationProfileRejection(expected, resources, profile)
    }

    /** CLOSED counted-closing/legacy combined-close refusal only. The installed cash
     * producer proves the exact canonical subtype, whole original owner/amount-bound
     * requirement and first refusal from genuine native BEFORE originals. Core reuses
     * the separately authenticated founding/enrolled lanes and closes every nonbusiness
     * mutation, result, resource and dependency. No count, delivery, acknowledgement,
     * assignment or cash effect can be introduced by rejected history. */
    public suspend fun requireCashClosingRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>): Unit = requireConfigurationProfileRejection(expected, resources,
            RuntimeHistoricalConfigurationRejectionProfile.CASH_CLOSING)

    /** CLOSED typed table-assignment refusal only. The installed producer compares the
     * complete original owner-bound requirement and derives the exact refusal/result and
     * resources from genuine BEFORE order, binding and destination originals. Core retains
     * the existing authenticated founding/enrolled joins and five nonbusiness mutations;
     * no assignment, order, stock, payment, claim, job or deadline effect is admitted. */
    public suspend fun requireFoodTableAssignmentRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>): Unit = requireConfigurationProfileRejection(expected, resources,
            RuntimeHistoricalConfigurationRejectionProfile.FOOD_TABLE_ASSIGNMENT)

    /** Exact documentary payment-classification refusal only. The installed producer
     * derives the original payment/close, predecessor and first refusal from genuine
     * native BEFORE sources. Core closes original authority and all five nonbusiness
     * mutations; no money, stock, attestation, entitlement, claim or job effect fits. */
    public suspend fun requirePaymentCorrectionRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>): Unit = requireConfigurationProfileRejection(expected, resources,
            RuntimeHistoricalConfigurationRejectionProfile.PAYMENT_CORRECTION)

    private suspend fun requireConfigurationProfileRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>, expectedProfile: RuntimeHistoricalConfigurationRejectionProfile) {
        owner.requireActive(this)
        val profile = capture.configurationRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(profile == expectedProfile)
        owner.requireConfigurationRejectionOriginal(this)
        if (profile == RuntimeHistoricalConfigurationRejectionProfile.CATALOG_RELEASE)
            historyShapeV2(owner.businessPublicationSourceBefore(capture) != null)
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null && capture.employeeHistory == null &&
            capture.nativeEmployeeStart == null && capture.supervisorReview == null && capture.employeeSupervisorReview == null &&
            capture.purchasingPolicyRejection == null && capture.employeePurchasingPolicyRejection == null &&
            capture.orderReassignmentRejection == null && capture.employeeOrderReassignmentRejection == null &&
            capture.openingSupplierObligationRejection == null && capture.employeeOpeningSupplierObligationRejection == null &&
            capture.branchActivityActivationRejection == null && capture.employeeBranchActivityActivationRejection == null &&
            capture.floorConfigurationRejection == null && capture.employeeFloorConfigurationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            capture.retailCustomerReceiptRejection == null && capture.employeeRetailCustomerReceiptRejection == null &&
            capture.returnCaseQualificationRejection == null && capture.employeeReturnCaseQualificationRejection == null &&
            capture.returnStockDispositionRejection == null && capture.employeeReturnStockDispositionRejection == null &&
            capture.employee?.actionApprovalId == null && !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command &&
            profile.acceptsRejection(checkNotNull(capture.employee), retained.code) && expected.code == retained.code &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalConfigurationRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED documentary return-rights activation refusal only. The installed producer
     * first compares the whole original employee requirement and authenticates positive
     * signed candidates/sources, then derives the canonical first refusal/result/resources
     * from genuine complete native BEFORE sources with already joined earlier writers.
     * Core separately closes original ownership, all operation/result/journal/audit/upload/
     * branch mutations and every read dependency. No negative-trust/default proof, business
     * effects, claims, jobs, approval, native import or deadline can complete this lane. */
    public suspend fun requireReturnRightsActivationRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.returnRightsActivationRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        owner.requireReturnRightsActivationRejectionOriginal(this)
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null && capture.employeeHistory == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.nativeEmployeeStart == null && capture.supervisorReview == null && capture.employeeSupervisorReview == null &&
            capture.purchasingPolicyRejection == null && capture.employeePurchasingPolicyRejection == null &&
            capture.orderReassignmentRejection == null && capture.employeeOrderReassignmentRejection == null &&
            capture.openingSupplierObligationRejection == null && capture.employeeOpeningSupplierObligationRejection == null &&
            capture.branchActivityActivationRejection == null && capture.employeeBranchActivityActivationRejection == null &&
            capture.floorConfigurationRejection == null && capture.employeeFloorConfigurationRejection == null &&
            capture.employee?.actionApprovalId == null && !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command && retained.code in profile.rejectionCodes && expected.code == retained.code &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalReturnRightsActivationRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED ordinary counter-observation refusal only. The installed receipt producer
     * first qualifies the genuine immutable paid Finalize and complete native BEFORE
     * receipt ancestry, compares the whole original collector-bound employee requirement,
     * then derives the exact first refusal/result/resources with the shared pure producer.
     * Core separately closes original ownership, every native nonbusiness mutation and
     * dependency. No unavailable-original default, business effects, claims, jobs, approval,
     * native import, streamed original or deadline can complete this lane. */
    public suspend fun requireRetailCustomerReceiptRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.retailCustomerReceiptRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        owner.requireRetailCustomerReceiptRejectionOriginal(this)
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null && capture.employeeHistory == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.nativeEmployeeStart == null && capture.supervisorReview == null && capture.employeeSupervisorReview == null &&
            capture.purchasingPolicyRejection == null && capture.employeePurchasingPolicyRejection == null &&
            capture.orderReassignmentRejection == null && capture.employeeOrderReassignmentRejection == null &&
            capture.openingSupplierObligationRejection == null && capture.employeeOpeningSupplierObligationRejection == null &&
            capture.branchActivityActivationRejection == null && capture.employeeBranchActivityActivationRejection == null &&
            capture.floorConfigurationRejection == null && capture.employeeFloorConfigurationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            capture.returnCaseQualificationRejection == null && capture.employeeReturnCaseQualificationRejection == null &&
            capture.returnStockDispositionRejection == null && capture.employeeReturnStockDispositionRejection == null &&
            capture.employee?.actionApprovalId == null && !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command && retained.code == profile.rejectionCode && expected.code == profile.rejectionCode &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalRetailCustomerReceiptRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** New-record-only scalar FOOD refusal. The installed producer has already derived
     * the exact Draft2 duplicate or Add3 stale revision from real BEFORE sources and enabled
     * activity, and compared the WHOLE captured requirement. The native marker, original
     * ownership, every dependency and exactly five nonbusiness mutations remain separate
     * checks. Old incompatible rejects, effects, claims, jobs, approval/import/deadlines and
     * other FOOD requests cannot enter this lane. Original DATA supplies no current grant. */
    public suspend fun requireFoodCheckRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.foodCheckRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        owner.requireFoodCheckRejectionOriginal(this)
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null &&
            capture.employeeHistory == null && capture.nativeEmployeeStart == null && capture.approvalHistory == null &&
            capture.employee?.actionApprovalId == null && !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command && retained.code in profile.rejectionCodes && expected.code == retained.code &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalFoodCheckRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** CLOSED ordinary ApplyOriginal AlreadyApplied only. The installed payment producer
     * has proved its actual native BEFORE application and complete earlier Apply/Start/raw
     * lineage, and compared the captured CASH_READ requirement. Core independently closes
     * original employee provenance, every native dependency and exactly five nonbusiness
     * mutations. No money, stock, claim, job, approval, physical action or deadline is added. */
    public suspend fun requireFoodPaymentAlreadyAppliedRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.foodPaymentRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        owner.requireFoodPaymentRejectionOriginal(this)
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null &&
            capture.employeeHistory == null && capture.nativeEmployeeStart == null && capture.approvalHistory == null &&
            capture.employee?.actionApprovalId == null && !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command && profile.acceptsRejection(retained.code) && expected.code == profile.rejectionCode &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalFoodPaymentRejectionProfile.CAPTURED_CHANGES &&
            capture.mutations.all { it.table in setOf(RuntimeHistoryTableV2.OPERATION, RuntimeHistoryTableV2.JOURNAL,
                RuntimeHistoryTableV2.AUDIT, RuntimeHistoryTableV2.OUTBOX, RuntimeHistoryTableV2.BRANCH) })
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** Closed source-backed QUALIFY/Accept refusal. The installed producer proves genuine
     * CASE/entitlement BEFORE, exact original dispatch, the first refusal and complete
     * requirement/result/resources. Core separately closes original ownership and every
     * native nonbusiness mutation/dependency. The retained method name does not supply
     * missing sources or allow business effects, jobs, approval, imports or deadlines. */
    public suspend fun requireReturnCaseQualificationRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.returnCaseQualificationRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        owner.requireReturnCaseQualificationRejectionOriginal(this)
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null && capture.employeeHistory == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.nativeEmployeeStart == null && capture.supervisorReview == null && capture.employeeSupervisorReview == null &&
            capture.purchasingPolicyRejection == null && capture.employeePurchasingPolicyRejection == null &&
            capture.orderReassignmentRejection == null && capture.employeeOrderReassignmentRejection == null &&
            capture.openingSupplierObligationRejection == null && capture.employeeOpeningSupplierObligationRejection == null &&
            capture.branchActivityActivationRejection == null && capture.employeeBranchActivityActivationRejection == null &&
            capture.floorConfigurationRejection == null && capture.employeeFloorConfigurationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            capture.retailCustomerReceiptRejection == null && capture.employeeRetailCustomerReceiptRejection == null &&
            capture.approvalHistory == null && capture.employee?.actionApprovalId == null &&
            !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command && retained.code == profile.rejectionCode && expected.code == profile.rejectionCode &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalReturnCaseQualificationRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** Closed stale-disposition RETURN refusal only. The installed sales producer must
     * prove DISPOSITION_REVISION_CHANGED is the exact first refusal from genuine native
     * acceptance/disposition BEFORE sources, and compare the whole original owner-private
     * inventory requirement/result/resources. No other RETURN rejection, expiry, approval,
     * native import, business effect or current employee authority is supplied here. */
    public suspend fun requireReturnStockDispositionRejection(expected: ProposedOutcome.Reject,
        resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val profile = capture.returnStockDispositionRejection ?: unavailable()
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        owner.requireReturnStockDispositionRejectionOriginal(this)
        historyShapeV2(requirementChecked && !effectsChecked && capture.nativeOriginal == null && capture.employeeHistory == null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.nativeEmployeeStart == null && capture.supervisorReview == null && capture.employeeSupervisorReview == null &&
            capture.purchasingPolicyRejection == null && capture.employeePurchasingPolicyRejection == null &&
            capture.orderReassignmentRejection == null && capture.employeeOrderReassignmentRejection == null &&
            capture.openingSupplierObligationRejection == null && capture.employeeOpeningSupplierObligationRejection == null &&
            capture.branchActivityActivationRejection == null && capture.employeeBranchActivityActivationRejection == null &&
            capture.floorConfigurationRejection == null && capture.employeeFloorConfigurationRejection == null &&
            capture.returnRightsActivationRejection == null && capture.employeeReturnRightsActivationRejection == null &&
            capture.retailCustomerReceiptRejection == null && capture.employeeRetailCustomerReceiptRejection == null &&
            capture.returnCaseQualificationRejection == null && capture.employeeReturnCaseQualificationRejection == null &&
            capture.approvalHistory == null && capture.employee?.actionApprovalId == null &&
            !retained.committed && retained.nativeOriginalProvenance == null &&
            retained.command == profile.command && retained.code == profile.rejectionCode && expected.code == profile.rejectionCode &&
            retained.result == expected.result && retained.aggregateWrites.isEmpty() && retained.claimChanges.isEmpty() &&
            retained.jobs.isEmpty() && capture.resources == resources &&
            capture.mutations.size == RuntimeHistoricalReturnStockDispositionRejectionProfile.CAPTURED_CHANGES)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        effectsChecked = true
    }

    /** Separate CLOSED native raw + initial-pending insert effects. Ordinary legacy effects
     * continue to deny native provenance; no money, claims/jobs, stock or deadline fit here. */
    public suspend fun requireNativeOriginalEffects(expected: ProposedOutcome.Apply, resources: Set<RecoveryResourceKey>) {
        owner.requireActive(this)
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: unavailable()
        historyShapeV2(!effectsChecked && capture.nativeOriginal != null &&
            capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.returnCaseQualificationRejection == null && capture.employeeReturnCaseQualificationRejection == null &&
            capture.returnStockDispositionRejection == null && capture.employeeReturnStockDispositionRejection == null &&
            retained.nativeOriginalProvenance == capture.nativeOriginal.provenance &&
            retained.committed && retained.result == expected.result && retained.aggregateWrites.size == 2 && expected.changes.size == 2 &&
            retained.claimChanges.isEmpty() && expected.claims.isEmpty() && retained.jobs.isEmpty() && expected.jobs.isEmpty() &&
            expected.mustCommitBefore == null && expected.changes.all { it.expectedRevision == null } && capture.resources == resources)
        val domain = NativeOriginalRetainedCodecV1.requireDomain(retained.command,
            checkNotNull(capture.nativeOriginal).provenance.nativeOriginal.domainProfile)
        NativeOriginalRetainedCodecV1.requireChanges(domain, operationId, expected.changes)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        historyShapeV2(checkedDeadline == null)
        requireEffects(retained, expected)
        effectsChecked = true
    }

    private suspend fun requireEffects(retained: RetainedCommandEvidence, expected: ProposedOutcome.Apply) {
        for ((actual, wanted) in retained.aggregateWrites.zip(expected.changes)) {
            val before = owner.aggregateBefore(capture, wanted.key)
            historyShapeV2(actual.key == wanted.key && wanted.expectedRevision == before?.revision &&
                actual.storageGeneration == (before?.revision?.next() ?: Revision(1)) && actual.schema == wanted.schema &&
                actual.payload == wanted.payload)
        }
        for ((actual, wanted) in retained.claimChanges.zip(expected.claims)) historyShapeV2(
            actual.key == wanted.key && actual.ownerId == wanted.ownerId && actual.action == when (wanted) {
                is ClaimChange.Acquire -> RetainedClaimAction.ACQUIRE
                is ClaimChange.Release -> RetainedClaimAction.RELEASE
            })
        for ((actual, wanted) in retained.jobs.zip(expected.jobs)) historyShapeV2(actual.kind == wanted.kind && actual.body == wanted.body)
    }

    /** Runs the installed repository's ACTUAL complete producer traversal on the genuine
     * native-history reader. Core independently closes every literal source, complete range,
     * estate, native mutation and resource before a successful callback can be counted. */
    public suspend fun requireStreamedProducer(completeProducer: suspend (RetainedStreamedCommand) -> Unit) {
        owner.requireActive(this)
        historyShapeV2(!effectsChecked && capture.configurationRejection == null && capture.employeeConfigurationRejection == null &&
            capture.returnCaseQualificationRejection == null && capture.employeeReturnCaseQualificationRejection == null &&
            capture.returnStockDispositionRejection == null && capture.employeeReturnStockDispositionRejection == null)
        val retained = (original as? RetainedOriginalV2.Streamed)?.evidence ?: unavailable()
        owner.requireStreamedSourcesAndResources(capture, retained)
        completeProducer(retained)
        owner.requireActive(this)
        if (!finalDeadlineChecked) requireFinalDeadlineDecision(null)
        effectsChecked = true
    }

    internal fun requireJoined() { historyShapeV2(requirementChecked && effectsChecked && finalDeadlineChecked) }
    private fun unavailable(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    override fun toString(): String = "RuntimeHistoricalCommand([REDACTED])"
}
