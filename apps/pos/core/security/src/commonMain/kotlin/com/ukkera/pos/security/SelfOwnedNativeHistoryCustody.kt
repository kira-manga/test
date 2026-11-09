package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.CoroutineContext

/**
 * A single affine claim on the original P2 lifetime. There is no public constructor, injected
 * controller, clock, path, SQL handle, verifier or close callback. Public visibility exists
 * only for the downstream database module; this capability must not enter UI/RPC containers.
 * A native record is authenticated data, not a complete-history or writer grant.
 */
public class SelfOwnedNativeHistoryCustody internal constructor(
    internal val controller: SelfOwnedNativeHistoryController,
) {
    public suspend fun snapshotOriginal(): OriginalNativeHistorySnapshot = controller.snapshotOriginal()
    public suspend fun openOriginalReadOnly(): SelfOwnedNativeHistoryReadSession = controller.openOriginalReadOnly()

    /** Register independent DATA with this lifetime, bound to its actually open original read. */
    public suspend fun openReceiptMembershipData(
        session: SelfOwnedNativeHistoryReadSession,
    ): SelfOwnedNativeReceiptMembershipData = controller.openReceiptMembershipData(session)

    /** Reopen only the exact parked DATA candidate after the actual read-close reobservation. */
    public suspend fun prepareReceiptMembershipDataAfterClosedRead(
        afterClose: OriginalNativeHistorySnapshot,
        data: SelfOwnedNativeReceiptMembershipData,
    ): Unit = controller.prepareReceiptMembershipDataAfterClosedRead(afterClose, data)

    /**
     * The actual installation COMMITTED settlement selects this empty resource, not a caller
     * fate/empty Boolean. The concrete installation owner must separately prove its full empty
     * installation before using an empty common DATA root; creation itself proves no history.
     */
    public suspend fun openEmptyReceiptMembershipDataAfterCommittedInstallation(
        settlement: SelfOwnedNativeRoomSettlement,
    ): SelfOwnedNativeReceiptMembershipData = controller.openEmptyReceiptMembershipDataAfterCommittedInstallation(settlement)

    /** Consumes an acknowledgement created AFTER this exact session's actual watched close. */
    public suspend fun reobserveAfterActualClose(
        close: SelfOwnedNativeHistoryReadClosed,
    ): OriginalNativeHistorySnapshot = controller.reobserveAfterActualClose(close)

    /**
     * One-shot final publication cut for the exact post-close observation, serialized against
     * this P2 lifetime's denial. Performs no SQL/file/key/provider IO and grants no writer or
     * completion capability. Snapshot equality alone cannot supply this exact owned object.
     */
    public fun completeOriginalReadDelivery(afterClose: OriginalNativeHistorySnapshot): Unit =
        controller.completeOriginalReadDelivery(afterClose)

    /** Real downstream owner work, under this SAME P2 exclusion/watchdog. Not a fate callback. */
    public suspend fun <T> runRoomWork(block: suspend () -> T, discard: suspend (T) -> Unit): T =
        controller.runRoomWork(block, discard)
    /** The private original Room-open producer retains its exact database::close operation
     * ONCE, in the same watched native driver-open invocation. This is a resource operation,
     * not a data-work/verification callback or caller-provided success. Terminal close later
     * invokes it only after actual Room task drain, before native descriptor/lease release. */
    public fun retainOriginalRoomClose(databaseIdentity: Any, closeActualRoom: () -> Unit): Unit =
        controller.retainOriginalRoomClose(databaseIdentity, closeActualRoom)
    /** Drain an unregistered Room builder failure before its original raw cleanup. Native
     * registration permanently refuses this lane once an original Room close is retained;
     * that exact operation belongs solely to terminal native cleanup, not output discard.
     * Only callable inside this custody's retained Room invocation; does not claim SQL fate. */
    public suspend fun prepareActualRoomClose(): Unit = controller.prepareActualRoomClose()
    public suspend fun recoverOriginalRollbackPager(): Unit = controller.recoverOriginalRollbackPager()
    public suspend fun beginRoomFrame(): SelfOwnedNativeRoomFrame = controller.beginRoomFrame()
    /** Reservation selection only, before native attach/BEGIN. It grants no approval and
     * cannot be added to an already submitted frame or used for another operation. */
    public suspend fun beginApprovedCommandRoomFrame(operationId: DomainId): SelfOwnedNativeRoomFrame =
        controller.beginApprovedCommandRoomFrame(operationId)
    public suspend fun prepareRoomChild(frame: SelfOwnedNativeRoomFrame, child: NativeHistoryRecordedHeadV2,
        intent: NativeHistoryOriginalIntentV2, installation: SelfOwnedInstallationChild? = null,
        finalDeadline: NativeHistoryFinalDeadlineDeclaration? = null,
        protectedFinal: NativeHistoryProtectedFinalDeclaration? = null,
        businessPublications: NativeCompanyBusinessPublicationObservation? = null,
        businessPublicationDependencies: ByteArray? = null): Unit =
        controller.prepareRoomChild(frame, child, intent, installation, finalDeadline, protectedFinal,
            businessPublications, businessPublicationDependencies)
    /** Final denial/elapsed observation only; never IO or renewed authorization. */
    public fun finishRoomFrameNow(frame: SelfOwnedNativeRoomFrame): Unit = controller.finishRoomFrameNow(frame)
    /** Exact-frame memory capture of the ACTUAL kernel finalWall, only after the existing
     * final pure checks passed. No IO, clock sampling, renewed authorization or fate flag. */
    public fun captureRoomFinalDeadlineNow(frame: SelfOwnedNativeRoomFrame, finalWall: UtcInstant): Unit =
        controller.captureRoomFinalDeadlineNow(frame, finalWall)
    /** Called by the concrete owner only AFTER its actual outer Room loan returned normally. */
    public suspend fun settleRoomFrameAfterOuter(frame: SelfOwnedNativeRoomFrame): SelfOwnedNativeRoomSettlement =
        controller.settleRoomFrameAfterOuter(frame)
    public fun retainUnknownRoomFrame(frame: SelfOwnedNativeRoomFrame): Unit = controller.retainUnknownRoomFrame(frame)
    /** Original independently founded human DATA, read by the same native owner, not approval. */
    public suspend fun readOriginalInstallationHumanReference(): LocalInstallationHumanReferenceV2 =
        controller.readOriginalInstallationHumanReference()
    public suspend fun readOriginalCompanyAuthority(): LocalCompanyAuthorityView = controller.readOriginalCompanyAuthority()
    public suspend fun prepareOriginalInstallation(snapshot: LocalBranchInstallationSnapshotV2): LocalCompanyInstallationAction =
        controller.prepareOriginalInstallation(snapshot)
    public suspend fun approveOriginalInstallation(action: LocalCompanyInstallationAction, headLogin: String,
        headSecret: CharArray): OriginalInstallationAttempt = controller.approveOriginalInstallation(action, headLogin, headSecret)
    public suspend fun armOriginalInstallation(attempt: OriginalInstallationAttempt): Unit = controller.armOriginalInstallation(attempt)
    public suspend fun observeOriginalInstallationCommit(attempt: OriginalInstallationAttempt): LocalInstallationCommitFactsV2 =
        controller.observeOriginalInstallationCommit(attempt)
    /** Exact closed native read identity, consumed only by the concrete full-scanner startup. */
    public fun admitOriginalRoomAfterClosedRead(afterClose: OriginalNativeHistorySnapshot,
        data: SelfOwnedNativeReceiptMembershipData? = null): Unit = controller.admitOriginalRoomAfterClosedRead(afterClose, data)
    /**
     * Rebind only an ALREADY native-settled original C1 completion to this retained owner,
     * after the concrete startup's full semantic scan and actual watched native read close.
     * Consumes the exact post-close cut. It neither settles pending SQL nor signs an ACK,
     * opens Room, renews approval or grants business admission. Parsed snapshot copies fail.
     */
    public suspend fun prepareOriginalAcknowledgementRecovery(afterClose: OriginalNativeHistorySnapshot): Unit =
        controller.prepareOriginalAcknowledgementRecovery(afterClose)
    /** Original first-install pending only. Forces irreversible attempt denial BEFORE the
     * concrete startup opens the original rollback pager/full reader. No new SQL is allowed. */
    public suspend fun fenceOriginalPendingInstallation(): OriginalNativeHistorySnapshot =
        controller.fenceOriginalPendingInstallation()
    /** Complete-scan DATA plus the exact actual post-close token. Native independently binds
     * it to its already retained original pending child; absence is never a rollback result.
     * A positive reconciliation retains STOP, not RUN, and grants no new installation. */
    public suspend fun settleOriginalPendingInstallation(afterClose: OriginalNativeHistorySnapshot,
        child: SelfOwnedInstallationChild): OriginalNativeHistorySnapshot =
        controller.settleOriginalPendingInstallation(afterClose, child)
    public suspend fun originalLocalPeer(): LocalAuthenticatedPeer = controller.originalLocalPeer()
    public suspend fun acknowledgeOriginalInstallation(): Unit = controller.acknowledgeOriginalInstallation()
    public val runtimeSecurity: SelfOwnedRuntimeSecurity get() = controller.runtimeSecurity

    /** Denial only. It does not close a descriptor, settle SQL, release a lease or prove death. */
    public fun denyDataUse(): Unit = controller.denyDataUse()
    public suspend fun close(): Unit = controller.close()
    override fun toString(): String = "SelfOwnedNativeHistoryCustodyV1([REDACTED])"
}

public sealed interface SelfOwnedNativeHistoryClaimResult {
    public class Claimed internal constructor(public val custody: SelfOwnedNativeHistoryCustody) : SelfOwnedNativeHistoryClaimResult
    public data class Unresolved(public val reason: SelfOwnedInstallationFailureV1) : SelfOwnedNativeHistoryClaimResult
}

/** The original native cut, not a transferable authority token and not SQL completeness. */
public class OriginalNativeHistorySnapshot internal constructor(
    internal val ownerIdentity: Any,
    internal val cutIdentity: Any,
    public val lifetimeId: DomainId,
    public val originalBinding: SelfOwnedNativeBindingV1,
    public val record: NativeHistoryAnchorRecordV2,
    public val recordDigest: PayloadDigest,
) {
    override fun toString(): String = "OriginalNativeHistorySnapshotV2([REDACTED])"
}

/**
 * Finite source-fixed traversal. The resource owner retains the real handle/statements even
 * if a delivered wrapper is abandoned. Schema must end before rows, and each announced row's
 * cells must be consumed sequentially. No caller may select a table/key/column/offset/pass.
 */
public class SelfOwnedNativeHistoryReadSession internal constructor(
    internal val controller: SelfOwnedNativeHistoryReadController,
    public val original: OriginalNativeHistorySnapshot,
    internal val employeeSelection: NativeEmployeeEnrollmentHistorySelection? = null,
    internal val policySelection: NativeEmployeePolicyActivationHistorySelection? = null,
    internal val businessPublicationSelection: NativeBusinessPublicationHistorySelection? = null,
    internal val deviceAdmissionSelection: NativeDeviceAdmissionHistorySelection? = null,
) {
    /** Additive source protocol only; no custody or completeness is inferred from presence. */
    public val hasEmployeeSeedTraversal: Boolean get() = employeeSelection != null
    /** Selected by this actual internal controller, never a caller/constructor flag. It
     * announces a closed additive row grammar, not authority or traversal completeness. */
    public val hasChronologicalAccountImageTraversal: Boolean get() = controller.hasChronologicalAccountImageTraversal
    /** Availability only. It neither certifies a captured history nor grants admission. */
    public val hasDataWorkspace: Boolean get() = controller.hasDataWorkspace
    public val employeeEnrollments: List<NativeEmployeeEnrollmentHistorySource> get() = employeeSelection?.sources.orEmpty().toList()
    public val hasEmployeePolicyHistoryTraversal: Boolean get() = policySelection != null
    public val employeePolicyActivations: List<NativeEmployeePolicyActivationHistorySource> get() = policySelection?.sources.orEmpty().toList()
    public val hasDeviceAdmissionHistoryTraversal: Boolean get() = deviceAdmissionSelection != null
    public val deviceAdmissions: List<NativeDeviceAdmissionHistorySource> get() = deviceAdmissionSelection?.sources.orEmpty().toList()
    /** Source protocol only. Absence of this protocol cannot prove an empty original cut. */
    public val hasBusinessPublicationObservationTraversal: Boolean get() = businessPublicationSelection != null
    public val businessPublicationObservations: List<NativeCompanyBusinessPublicationHistorySource>
        get() = businessPublicationSelection?.sources.orEmpty().toList()
    public suspend fun readSchemaPage(): NativeHistorySchemaPageV2? = controller.readSchemaPage()
    public suspend fun nextRowMetadata(): NativeHistoryRowMetadataV2? = controller.nextRowMetadata()
    public suspend fun readCellChunk(): NativeHistoryCellChunkV2 = controller.readCellChunk()
    /** Inert private DATA only; retains this same original session and its actual owner. */
    public suspend fun openDataWorkspace(): SelfOwnedNativeHistoryWorkspace = controller.openDataWorkspace(this)
    public suspend fun closeOriginalRead(): SelfOwnedNativeHistoryReadClosed = controller.closeOriginalRead()
    override fun toString(): String = "SelfOwnedNativeHistoryReadSessionV1([REDACTED])"
}

/** Security creates this only on actual close return; neither a Boolean nor isOpen suffices. */
public class SelfOwnedNativeHistoryReadClosed internal constructor(
    internal val ownerIdentity: Any,
    internal val sessionIdentity: Any,
    internal val original: OriginalNativeHistorySnapshot,
    internal val completeTraversal: Boolean,
    internal val employeeSelection: NativeEmployeeEnrollmentHistorySelection? = null,
    internal val policySelection: NativeEmployeePolicyActivationHistorySelection? = null,
    internal val businessPublicationSelection: NativeBusinessPublicationHistorySelection? = null,
    internal val deviceAdmissionSelection: NativeDeviceAdmissionHistorySelection? = null,
) {
    internal var consumed: Boolean = false
    override fun toString(): String = "SelfOwnedNativeHistoryReadClosedV1([REDACTED])"
}

/** A failure carries no safe-absence, rollback, close, freshness or replacement permission. */
public class NativeHistoryUnavailable internal constructor(
    public val reason: SelfOwnedInstallationFailureV1,
) : IllegalStateException("native.history.unavailable.${reason.name.lowercase()}")

/*
 * Native A0b joins. These are exact-object, same-worker capabilities, not data deserializers.
 * D2-R never constructs a completion/retirement from a parsed snapshot or a successful read.
 * The actual writer/startup producer must retain and validate each object in its owner registry.
 * The closed-scan ACK recovery producer may rebind an already native-settled C1 only; it
 * cannot manufacture missing settlement. The separate pending-C1 coordinator must first
 * force original-attempt denial, fully scan the original database and complete both real
 * closes before its distinct positive classification; absent/ambiguous work remains held.
 */
public class OriginalInstallationProposal internal constructor(
    internal val ownerIdentity: Any,
    internal val proposalIdentity: Any,
    public val snapshot: LocalBranchInstallationSnapshotV2,
    internal val retainedGeneration: Long,
    internal val retainedDigest: PayloadDigest,
) {
    override fun toString(): String = "OriginalInstallationProposalV2([REDACTED])"
}

public class OriginalInstallationAttempt internal constructor(
    internal val ownerIdentity: Any,
    internal val attemptIdentity: Any,
    public val snapshot: LocalBranchInstallationSnapshotV2,
    public val facts: LocalInstallationAttemptFactsV2,
    internal val denialGeneration: Long,
) {
    override fun toString(): String = "OriginalInstallationAttemptV2([REDACTED])"
}

public class OriginalInstallationCompletion internal constructor(
    internal val ownerIdentity: Any,
    internal val completionIdentity: Any,
    public val snapshot: LocalBranchInstallationSnapshotV2,
    public val facts: LocalInstallationAttemptFactsV2,
    public val approvals: LocalInstallationOriginalApprovalsV2,
    public val receipt: LocalBranchInstallationReceiptV2,
    public val childFacts: LocalInstallationCompletedChildFactsV2,
    internal val settledGeneration: Long,
    internal val settledRecordDigest: PayloadDigest,
) {
    override fun toString(): String = "OriginalInstallationCompletionV2([REDACTED])"
}

public class OriginalAttemptRetirement internal constructor(
    internal val ownerIdentity: Any,
    internal val retirementIdentity: Any,
    public val snapshot: LocalBranchInstallationSnapshotV2,
    public val facts: LocalInstallationAttemptFactsV2,
    public val fate: NativeHistoryRetirementFateV2,
    public val retirementDigest: PayloadDigest,
    evidenceBytes: ByteArray,
    internal val denialGeneration: Long,
    internal val retainedGeneration: Long,
) {
    private val evidence = evidenceBytes.copyOf()
    public fun copyEvidenceBytes(): ByteArray = evidence.copyOf()
    override fun toString(): String = "OriginalAttemptRetirementV2([REDACTED])"
}

/** Security-module implementation seam, never a supplied accepting database callback. */
internal interface SelfOwnedNativeHistoryController {
    suspend fun snapshotOriginal(): OriginalNativeHistorySnapshot
    suspend fun openOriginalReadOnly(): SelfOwnedNativeHistoryReadSession
    suspend fun openReceiptMembershipData(session: SelfOwnedNativeHistoryReadSession): SelfOwnedNativeReceiptMembershipData =
        throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    suspend fun prepareReceiptMembershipDataAfterClosedRead(afterClose: OriginalNativeHistorySnapshot,
        data: SelfOwnedNativeReceiptMembershipData): Unit =
        throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    suspend fun openEmptyReceiptMembershipDataAfterCommittedInstallation(
        settlement: SelfOwnedNativeRoomSettlement): SelfOwnedNativeReceiptMembershipData =
        throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    suspend fun reobserveAfterActualClose(close: SelfOwnedNativeHistoryReadClosed): OriginalNativeHistorySnapshot
    fun completeOriginalReadDelivery(afterClose: OriginalNativeHistorySnapshot)
    suspend fun <T> runRoomWork(block: suspend () -> T, discard: suspend (T) -> Unit): T
    fun retainOriginalRoomClose(databaseIdentity: Any, closeActualRoom: () -> Unit)
    suspend fun prepareActualRoomClose()
    suspend fun recoverOriginalRollbackPager()
    suspend fun beginRoomFrame(): SelfOwnedNativeRoomFrame
    suspend fun beginApprovedCommandRoomFrame(operationId: DomainId): SelfOwnedNativeRoomFrame =
        throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
    suspend fun prepareRoomChild(frame: SelfOwnedNativeRoomFrame, child: NativeHistoryRecordedHeadV2,
        intent: NativeHistoryOriginalIntentV2, installation: SelfOwnedInstallationChild?,
        finalDeadline: NativeHistoryFinalDeadlineDeclaration?, protectedFinal: NativeHistoryProtectedFinalDeclaration?,
        businessPublications: NativeCompanyBusinessPublicationObservation?, businessPublicationDependencies: ByteArray?)
    fun captureRoomFinalDeadlineNow(frame: SelfOwnedNativeRoomFrame, finalWall: UtcInstant)
    fun finishRoomFrameNow(frame: SelfOwnedNativeRoomFrame)
    suspend fun settleRoomFrameAfterOuter(frame: SelfOwnedNativeRoomFrame): SelfOwnedNativeRoomSettlement
    fun retainUnknownRoomFrame(frame: SelfOwnedNativeRoomFrame)
    suspend fun readOriginalInstallationHumanReference(): LocalInstallationHumanReferenceV2
    suspend fun readOriginalCompanyAuthority(): LocalCompanyAuthorityView
    suspend fun prepareOriginalInstallation(snapshot: LocalBranchInstallationSnapshotV2): LocalCompanyInstallationAction
    suspend fun approveOriginalInstallation(action: LocalCompanyInstallationAction, headLogin: String, headSecret: CharArray): OriginalInstallationAttempt
    suspend fun armOriginalInstallation(attempt: OriginalInstallationAttempt)
    suspend fun observeOriginalInstallationCommit(attempt: OriginalInstallationAttempt): LocalInstallationCommitFactsV2
    fun admitOriginalRoomAfterClosedRead(afterClose: OriginalNativeHistorySnapshot)
    fun admitOriginalRoomAfterClosedRead(afterClose: OriginalNativeHistorySnapshot,
        data: SelfOwnedNativeReceiptMembershipData?) {
        if (data != null) throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        admitOriginalRoomAfterClosedRead(afterClose)
    }
    suspend fun prepareOriginalAcknowledgementRecovery(afterClose: OriginalNativeHistorySnapshot)
    suspend fun fenceOriginalPendingInstallation(): OriginalNativeHistorySnapshot
    suspend fun settleOriginalPendingInstallation(afterClose: OriginalNativeHistorySnapshot,
        child: SelfOwnedInstallationChild): OriginalNativeHistorySnapshot
    suspend fun originalLocalPeer(): LocalAuthenticatedPeer
    suspend fun acknowledgeOriginalInstallation()
    val runtimeSecurity: SelfOwnedRuntimeSecurity
    fun denyDataUse()
    suspend fun close()
}

internal interface SelfOwnedNativeHistoryReadController {
    /** Older source controllers keep their exact traversal and cannot supply reset images. */
    val hasChronologicalAccountImageTraversal: Boolean get() = false
    val hasDataWorkspace: Boolean get() = false
    suspend fun readSchemaPage(): NativeHistorySchemaPageV2?
    suspend fun nextRowMetadata(): NativeHistoryRowMetadataV2?
    suspend fun readCellChunk(): NativeHistoryCellChunkV2
    /** Older source controllers refuse this additive DATA protocol; they cannot fake storage. */
    suspend fun openDataWorkspace(session: SelfOwnedNativeHistoryReadSession): SelfOwnedNativeHistoryWorkspace =
        throw NativeHistoryUnavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
    suspend fun closeOriginalRead(): SelfOwnedNativeHistoryReadClosed
}

/** Opaque native registry entry, never constructible from a transaction id or caller result. */
public class SelfOwnedNativeRoomFrame internal constructor(
    internal val ownerIdentity: Any,
    public val frameId: DomainId,
    public val original: OriginalNativeHistorySnapshot,
    internal val approvedOperationId: DomainId? = null,
) {
    internal var nativeBegan: Boolean = false
    internal var nativeDisposition: SelfOwnedNativeSqlDisposition = SelfOwnedNativeSqlDisposition.NOT_SUBMITTED
    internal var preparedChild: NativeHistoryRecordedHeadV2? = null
    internal var installationChild: SelfOwnedInstallationChild? = null
    internal var preparedIntentDigest: PayloadDigest? = null
    internal var finalDeadline: NativeHistoryFinalDeadlineDeclaration? = null
    internal var finalDeadlineDecision: NativeHistoryFinalDeadlineDecision? = null
    internal var protectedFinal: NativeHistoryProtectedFinalDeclaration? = null
    internal var protectedFinalDecision: NativeHistoryProtectedFinalDecision? = null
    internal var registeredBusinessPublicationObservation: NativeCompanyBusinessPublicationObservation? = null
    /** Actual native-owned complete observation for this frame; null is unavailable. */
    public val originalBusinessPublicationObservation: NativeCompanyBusinessPublicationObservation?
        get() = registeredBusinessPublicationObservation
    internal var preparedBusinessPublicationProof: NativeHistoryBusinessPublicationObservationProof? = null
    internal var finalized: Boolean = false
    internal var consumed: Boolean = false
    override fun toString(): String = "SelfOwnedNativeRoomFrameV1([REDACTED])"
}
public enum class SelfOwnedNativeSqlDisposition { NOT_SUBMITTED, COMMITTED, ROLLED_BACK, UNKNOWN }
public class SelfOwnedNativeRoomSettlement internal constructor(
    public val disposition: SelfOwnedNativeSqlDisposition,
    public val original: OriginalNativeHistorySnapshot,
)
/** Canonical C1/R7 DATA; the actual driver/outer/native path or fenced complete-scan startup
 * must still settle it. Possession never proves native/SQL fate or authorizes replay. */
public class SelfOwnedInstallationChild(public val receipt: LocalBranchInstallationReceiptV2,
    public val facts: LocalInstallationCompletedChildFactsV2)

/** Same-owner concrete adapters. Not native-completeness or antirollback certificates. */
public class SelfOwnedRuntimeSecurity internal constructor(
    public val protection: DeviceProtectedLocalStorage,
    public val passwords: LocalPasswordHasher,
    public val environment: LocalAuthenticationEnvironment,
    public val entropy: LocalAuthenticationEntropy,
    public val storageKey: ProtectedStorageKeyReference,
    public val pepper: ProtectedPepperReference,
)

/** Downstream builder recognition only. A forwarding/look-alike driver cannot reuse the
 * capability: both operations require the exact native driver registered at construction. */
public interface SelfOwnedNativeRoomCoroutineDriver {
    public val ownedRoomCoroutines: SelfOwnedNativeRoomCoroutineBinding
}

/** No public constructor, supplied dispatcher, parent Job, callback or accepting verifier.
 * The concrete native owner retains the real Job tree even if the actual builder throws. */
public class SelfOwnedNativeRoomCoroutineBinding internal constructor(
    private val driver: Any,
    private val controller: SelfOwnedNativeRoomCoroutineController,
) {
    public fun contextForActualBuilder(actual: Any): CoroutineContext {
        check(actual === driver)
        return controller.queryContext()
    }
    public fun afterActualBuilder(actual: Any): Unit {
        check(actual === driver)
        controller.afterActualBuilder()
    }
}

internal interface SelfOwnedNativeRoomCoroutineController {
    fun queryContext(): CoroutineContext
    fun afterActualBuilder()
}
