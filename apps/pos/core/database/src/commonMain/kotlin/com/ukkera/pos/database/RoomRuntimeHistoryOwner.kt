package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The single production owner of all Room loans on a self-owned database. Construction is
 * confined to startup/installation composition. No supplied MasterRuntimeVerifier, callback
 * COMMITTED result, epoch or healthy Boolean can install this owner on another database. */
internal class RoomRuntimeHistoryOwner private constructor(
    internal val database: MasterRoomDatabase,
    private val custody: SelfOwnedNativeHistoryCustody,
    original: OriginalNativeHistorySnapshot,
    mode: Mode,
    membershipSeed: RuntimeRetailCustomerReceiptMembershipSeed? = null,
) {
    internal enum class Mode { INSTALLING, OPERATIONAL, POLICY_HELD, UNKNOWN, CLOSED }
    private val loans = Mutex()
    private var serialLoan: SerialPublicationLoan? = null
    // Client/journal workers also inspect this owner. Retirement/uncertainty must be
    // visible outside the Room loan, not only to the writer that changed the mode.
    private val modeState = MutableStateFlow(mode)
    private var mode: Mode
        get() = modeState.value
        set(value) { modeState.value = value }
    // The Android cohost enrollment boundary samples the last actually settled native head
    // from another worker. Publish it atomically; a stale C1 must not look pristine after auth.
    private val originalState = MutableStateFlow(original)
    private var original: OriginalNativeHistorySnapshot
        get() = originalState.value
        set(value) { originalState.value = value }
    private var active: RoomRuntimeHistoryFrame? = null
    private var store: AtomicMasterStore? = null
    private val receiptClassifierAtAdmission = membershipSeed?.classifier
    private var receiptClassifier: RuntimeRetailCustomerReceiptEventClassifier? = null
    private val receiptMembershipUseDenied = MutableStateFlow(mode in setOf(Mode.POLICY_HELD, Mode.UNKNOWN, Mode.CLOSED))
    // Keep the resource even if its active cut is denied. Terminal native cleanup owns its
    // artifacts; bounded common root/accounting is released only after that actual cleanup.
    private var receiptMembershipResource: RuntimeRetailCustomerReceiptMembershipIndex? = membershipSeed?.index
    private var receiptMembership: ReceiptMembershipCut? = membershipSeed?.let { seed ->
        check(seed.scope == original.record.allocation.coordinates.scope && seed.recordDigest == original.recordDigest &&
            seed.index.matchesPreparedAdmission(original))
        ReceiptMembershipCut(original, seed.index)
    }
    private var endpoint: LocalMasterEndpoint? = null
    private var ingress: NativeOriginalIntake? = null
    private var securityBound=false
    private var nativeInvocation: NativeOriginalIntakeInvocation? = null
    private var nativeLease: NativeOriginalRetainedLease? = null
    internal val verifier: RoomRuntimeHistoryVerifier = RoomRuntimeHistoryVerifier(this)
    internal val originalScope: BranchScope get() = original.record.allocation.coordinates.scope
    internal val compositionProfile: com.ukkera.pos.security.LocalCompositionSdkProfileV1
        get() = custody.compositionProfile
    internal val originalAuthority: AuthorityIdentity get() = original.record.allocation.coordinates.let {
        AuthorityIdentity(it.assignmentId,it.deviceId,checkNotNull(original.record.definition).definition.authorityEpoch)
    }
    internal val originalRecord: OriginalNativeHistorySnapshot get() = original
    internal val originalCustody: SelfOwnedNativeHistoryCustody get() = custody
    internal val operational: Boolean get() = mode == Mode.OPERATIONAL
    /** Read-only eligibility cut, never a replacement for the actual native writer authority. */
    internal fun permitsFirstClientEnrollment(receipt: LocalBranchInstallationReceiptV2): Boolean {
        if (!operational || loans.isLocked) return false
        val snapshot = originalState.value
        val record = snapshot.record
        return record.pending == null && record.issuerAcknowledgement != null && record.parent?.historySequence == 1L &&
            record.parent?.commandSequence == 0L && record.receipt?.digest == receipt.digest &&
            record.roleIntent?.role == NativeHistoryDesiredRoleV2.RUN && originalState.value === snapshot &&
            operational && !loans.isLocked
    }
    internal val permitsHeldResults: Boolean get() = mode in setOf(Mode.OPERATIONAL,Mode.POLICY_HELD)
    /** Presentation of this SAME owner's settled C1, never restart or SQL-fate evidence. */
    internal val hasUnacknowledgedOriginalInstallation: Boolean get() =
        mode == Mode.INSTALLING && active == null && original.record.pending == null &&
            original.record.parent != null && original.record.firstChild != null && original.record.receipt != null &&
            original.record.attempt?.phase == NativeHistoryAttemptPhaseV2.COMPLETED
    init {
        check(database.runtimeHistoryOwner == null)
        // This constructor runs only in the original actual Room-open producer, after its
        // same native driver completed open. Retain the bound operation, never a close flag.
        receiptMembershipResource?.bindAdmitted(original)
        if (receiptMembershipUseDenied.value) invalidateReceiptMembership()
        custody.retainOriginalRoomClose(database, database::close)
        database.runtimeHistoryOwner = this
    }

    internal fun requireOwnedSecurity(dependencies: LocalMasterSecurityDependencies) {
        val actual=custody.runtimeSecurity
        check(!securityBound && dependencies.protection===actual.protection && dependencies.passwords===actual.passwords &&
            dependencies.environment===actual.environment && dependencies.entropy===actual.entropy &&
            dependencies.storageKey==actual.storageKey && dependencies.pepperReference==actual.pepper)
        securityBound=true
    }
    internal fun bindStore(actual: AtomicMasterStore, classifier: RuntimeRetailCustomerReceiptEventClassifier?) {
        check(store == null && actual.scope == originalScope &&
            (receiptClassifierAtAdmission == null || receiptClassifierAtAdmission === classifier))
        receiptClassifier = classifier; store = actual
    }
    internal fun bindEndpoint(actual: LocalMasterEndpoint) { check(endpoint == null && store != null); endpoint = actual }
    internal fun activeFrameForStore(actual: AtomicMasterStore): RoomRuntimeHistoryFrame? {
        check(store === actual); return active
    }
    internal fun frameFor(connection: Transactor): RoomRuntimeHistoryFrame = checkNotNull(active).also { requireFrame(it,connection) }
    internal fun requireFrame(frame: RoomRuntimeHistoryFrame,connection: Transactor) {
        check(active === frame && frame.connection === connection && database.runtimeHistoryOwner === this &&
            mode !in setOf(Mode.UNKNOWN,Mode.CLOSED))
    }
    internal fun retailCustomerReceiptEventClassifier(frame: RoomRuntimeHistoryFrame): RuntimeRetailCustomerReceiptEventClassifier? {
        requireFrame(frame, frame.connection)
        return receiptClassifier.takeIf { store != null && !receiptMembershipUseDenied.value }
    }
    internal suspend fun retailCustomerReceiptEventMembership(frame: RoomRuntimeHistoryFrame,
        saleId: DomainId): List<RuntimeRetailCustomerReceiptEventLocator>? {
        requireFrame(frame, frame.connection)
        if (store == null || receiptClassifier == null || receiptMembershipUseDenied.value) return null
        val selected = receiptMembership ?: return null
        if (!sameReceiptCut(selected.original, frame.native.original) || !sameReceiptCut(original, frame.native.original)) return null
        var selection: RuntimeRetailCustomerReceiptMembershipSelection? = null
        var transferred = false
        try {
            val result = selected.index.select(saleId); selection = result
            currentCoroutineContext().ensureActive()
            requireFrame(frame, frame.connection)
            // close() denies use before waiting for this loan. Recheck after all DATA IO;
            // neither a suspended lookup nor a retained scalar cut can survive that denial.
            if (receiptMembershipUseDenied.value || receiptMembership !== selected ||
                receiptMembershipResource !== selected.index ||
                !sameReceiptCut(selected.original, frame.native.original) ||
                !sameReceiptCut(original, frame.native.original)) return null
            val entries = frame.retainRetailCustomerReceiptMembershipSelection(result)
            transferred = true
            // Frame revalidation may itself suspend on Room. The final availability cut
            // therefore follows the successful transfer too, with no further DATA or SQL IO.
            return entries.takeIf { !receiptMembershipUseDenied.value && receiptMembership === selected &&
                receiptMembershipResource === selected.index && sameReceiptCut(selected.original, frame.native.original) &&
                sameReceiptCut(original, frame.native.original) }
        } finally { if (!transferred) selection?.close() }
    }
    internal suspend fun requireRetailCustomerReceiptMembershipDelta(frame: RoomRuntimeHistoryFrame,
        delta: RuntimeRetailCustomerReceiptMembershipDelta) {
        requireFrame(frame, frame.connection)
        val selected = checkNotNull(receiptMembership)
        check(store != null && receiptClassifier != null && !receiptMembershipUseDenied.value &&
            receiptMembershipResource === selected.index && sameReceiptCut(selected.original, frame.native.original) &&
            sameReceiptCut(original, frame.native.original))
        check(selected.index.canAppend(delta.saleId, delta.locator))
        requireFrame(frame, frame.connection)
        check(!receiptMembershipUseDenied.value && receiptMembership === selected &&
            sameReceiptCut(selected.original, frame.native.original) && sameReceiptCut(original, frame.native.original))
    }

    /** Called only after this owner's actual outer/native settlement, under its existing loan.
     * An unavailable or mismatched cut is never repaired by a live census or a default empty. */
    private suspend fun settleReceiptMembership(frame: RoomRuntimeHistoryFrame, settlement: SelfOwnedNativeRoomSettlement) {
        if (receiptMembershipUseDenied.value || !sameReceiptCut(original, frame.native.original) ||
            !sameReceiptOrigin(frame.native.original, settlement.original)) {
            invalidateReceiptMembership(); return
        }
        val selected = receiptMembership
        if (selected != null && !sameReceiptCut(selected.original, frame.native.original)) {
            invalidateReceiptMembership(); return
        }
        when (settlement.disposition) {
            SelfOwnedNativeSqlDisposition.COMMITTED -> {
                val committed = frame.retailCustomerReceiptMembershipCommit(settlement.original)
                if (committed == null) { invalidateReceiptMembership(); return }
                if (committed.installation != null) {
                    // RoomSelfOwnedBranchInstallationFrame.initialize/prepareNormalReturn
                    // (RoomLocalBranchInstallationFrame.kt) use the complete schema7
                    // RoomBranchInstallationStorage.originalFootprint and exact seed rereads:
                    // all29 tables, including AGGREGATE=0, before this frame finishes normally.
                    // Neither a missing native parent nor installation DATA alone creates empty.
                    if (selected != null || receiptMembershipResource != null ||
                        frame.native.original.record.parent != null || committed.delta != null) {
                        invalidateReceiptMembership(); return
                    }
                    val created = RuntimeRetailCustomerReceiptMembershipIndex.afterCommittedInstallation(
                        custody, settlement, RuntimeHistoryColdMemory())
                    receiptMembershipResource = created
                    if (receiptMembershipUseDenied.value || !sameReceiptCut(original, frame.native.original)) {
                        invalidateReceiptMembership(); return
                    }
                    receiptMembership = ReceiptMembershipCut(settlement.original, created)
                } else if (selected != null) {
                    val delta = committed.delta
                    if (delta != null) selected.index.appendCommitted(delta.saleId, delta.locator)
                    if (receiptMembershipUseDenied.value || receiptMembership !== selected) {
                        invalidateReceiptMembership(); return
                    }
                    // Root and current original cut publish together under the existing owner
                    // loan, only after every required committed DATA write actually returned.
                    selected.original = settlement.original
                }
            }
            SelfOwnedNativeSqlDisposition.ROLLED_BACK, SelfOwnedNativeSqlDisposition.NOT_SUBMITTED -> {
                // Rollback can change the authenticated record digest, but cannot change
                // the SQL/native parent or publish a staged receipt EVENT.
                if (settlement.original.record.pending != null ||
                    settlement.original.record.parent != frame.native.original.record.parent) invalidateReceiptMembership()
                else selected?.original = settlement.original
            }
            SelfOwnedNativeSqlDisposition.UNKNOWN -> invalidateReceiptMembership()
        }
    }
    private fun advanceReceiptMembershipAfterAcknowledgement(before: OriginalNativeHistorySnapshot,
        after: OriginalNativeHistorySnapshot) {
        val selected = receiptMembership ?: return
        if (receiptMembershipUseDenied.value || !sameReceiptCut(selected.original, before) ||
            !sameReceiptOrigin(before, after) || before.record.parent != after.record.parent ||
            after.record.pending != null || before.record.receipt?.digest != after.record.receipt?.digest ||
            before.record.firstChild != after.record.firstChild) invalidateReceiptMembership()
        else selected.original = after
    }
    private fun sameReceiptOrigin(left: OriginalNativeHistorySnapshot, right: OriginalNativeHistorySnapshot): Boolean =
        left.lifetimeId == right.lifetimeId && left.record.allocation.digest == right.record.allocation.digest &&
            left.record.allocation.coordinates.scope == right.record.allocation.coordinates.scope
    private fun sameReceiptCut(left: OriginalNativeHistorySnapshot, right: OriginalNativeHistorySnapshot): Boolean =
        sameReceiptOrigin(left, right) && left.recordDigest == right.recordDigest && left.record.parent == right.record.parent &&
            left.record.pending == null && right.record.pending == null

    private class ReceiptMembershipCut(
        var original: OriginalNativeHistorySnapshot,
        val index: RuntimeRetailCustomerReceiptMembershipIndex,
    )
    private fun invalidateReceiptMembership() {
        // Atomic denial only: an active operation owns its root/buffers until it returns.
        receiptMembershipUseDenied.value = true
        receiptMembershipResource?.denyUse()
        receiptMembership = null
    }
    internal fun permits(observation: BranchObservation, business: Boolean): Boolean {
        val frame = active ?: return false
        val parent = frame.native.original.record.parent ?: return false
        return (if (business) mode == Mode.OPERATIONAL else permitsHeldResults) && observation.runtimeFrame === frame &&
            observation.scope == originalScope && observation.authority == originalAuthority &&
            observation.sequence.value == parent.commandSequence && observation.journalHash == parent.commandDigest &&
            observation.authority.epoch.value == parent.commandEpoch
    }
    /** A security service's wider phase is deliberately NOT a Room/native loan. Each actual
     * reserve/read/completion is separately owned; password hashing never borrows SQLite. */
    internal suspend fun <T> securityLane(block: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        check(mode !in setOf(Mode.UNKNOWN,Mode.CLOSED))
        check(active == null) { "Security phase inside an active original Room loan" }
        return block()
    }
    internal suspend fun <T> writerLoan(block: suspend (Transactor) -> T): T = loans.withLock {
        writerLoanLocked(null, block)
    }
    /** Keep revocation serialized until the protected request has fully settled and the
     * following native operation has returned. Authentication must precede this loan. */
    internal suspend fun <T> serialPublicationLoan(block: suspend (SerialPublicationLoan) -> T): T = loans.withLock {
        currentCoroutineContext().ensureActive()
        check(operational && active == null)
        val loan = SerialPublicationLoan()
        check(serialLoan == null)
        serialLoan = loan
        try { block(loan) } finally { serialLoan = null }
    }

    /** Existing authenticated original DATA only, freshly checked under the same native loan.
     * A legacy/missing original is unavailable; this path never captures or repairs SDK state. */
    internal suspend fun readOriginalMasterSdk(): LocalMasterSdkOriginalV1 = serialPublicationLoan { loan ->
        val settled = loan.original
        check(settled.record.pending == null && settled.record.issuerAcknowledgement != null)
        val originalSdk = checkNotNull(settled.record.masterSdkOriginal)
        val actual = custody.snapshotOriginal()
        check(operational && actual.lifetimeId == settled.lifetimeId && actual.recordDigest == settled.recordDigest &&
            actual.record.pending == null && actual.record.masterSdkOriginal?.descriptorDigest == originalSdk.descriptorDigest &&
            originalSdk.allocation.digest == actual.record.allocation.digest &&
            originalSdk.compositionProfile == compositionProfile &&
                originalSdk.appId == compositionProfile.appId(originalScope))
        currentCoroutineContext().ensureActive()
        originalSdk
    }

    internal inner class SerialPublicationLoan internal constructor() {
        private fun requireOpen() {
            check(serialLoan === this && loans.isLocked && operational && active == null)
        }
        // No suspension, native IO or new frame while selecting the last settled cut.
        internal val original: OriginalNativeHistorySnapshot get() {
            requireOpen()
            return this@RoomRuntimeHistoryOwner.original
        }
        internal suspend fun <T> writer(block: suspend (Transactor) -> T): T {
            currentCoroutineContext().ensureActive()
            requireOpen()
            return writerLoanLocked(null, block)
        }
    }
    internal suspend fun <T> approvedCommandWriterLoan(operationId: com.ukkera.pos.types.OperationId,
        block: suspend (Transactor) -> T): T = loans.withLock {
        writerLoanLocked(operationId, block)
    }
    private suspend fun <T> writerLoanLocked(approvedOperationId: com.ukkera.pos.types.OperationId?,
        block: suspend (Transactor) -> T): T {
        currentCoroutineContext().ensureActive()
        check(active == null && mode !in setOf(Mode.UNKNOWN,Mode.CLOSED))
        return custody.runRoomWork(block = {
            val native = if (approvedOperationId == null) custody.beginRoomFrame()
                else custody.beginApprovedCommandRoomFrame(DomainId(approvedOperationId.value))
            var frame: RoomRuntimeHistoryFrame? = null
            var returnedSettlement: SelfOwnedNativeRoomSettlement? = null
            var loanFailure: Throwable? = null
            fun denyFailedLoan(failure: Throwable) {
                mode = Mode.UNKNOWN
                invalidateReceiptMembership()
                try {
                    val settled = returnedSettlement
                    if (settled == null) custody.retainUnknownRoomFrame(native)
                    else {
                        // Runtime/DATA failure after an actual native return does not
                        // make the already consumed frame's financial settlement unknown.
                        original = settled.original
                        custody.denyDataUse()
                    }
                } catch (denialFailure: Throwable) {
                    if (denialFailure !== failure) failure.addSuppressed(denialFailure)
                }
            }
            try {
                // The real OUTER connection return is mandatory. Room3 schedules separate
                // TEMP-only invalidation work; it is NOT the financial transaction's fate.
                // Native settlement below awaits that actual work and its dispatch/restore tails.
                val result = database.useWriterConnection { connection ->
                    requireDurableWriter(connection)
                    val owned = RoomRuntimeHistoryFrame(this,connection,native)
                    active = owned; frame = owned
                    block(connection)
                }
                checkNotNull(frame)
                val settlement = custody.settleRoomFrameAfterOuter(native)
                returnedSettlement = settlement // Only the actual native return establishes this boundary.
                check(settlement.disposition != SelfOwnedNativeSqlDisposition.UNKNOWN)
                if(settlement.disposition==SelfOwnedNativeSqlDisposition.COMMITTED) checkNotNull(frame).requireNormalOuterBody()
                settleReceiptMembership(checkNotNull(frame), settlement)
                original = settlement.original
                if (original.record.attempt?.phase == NativeHistoryAttemptPhaseV2.RETIRING) {
                    invalidateReceiptMembership(); mode = Mode.POLICY_HELD
                }
                // Native settlement does not replay committed effects or reauthorize the caller.
                result
            } catch (failure: Throwable) {
                loanFailure = failure
                // The mandatory unknown-frame exclusion below may terminate this process
                // before the original caller can observe its failure. Capture static source
                // locations only, outside native gates and under the unchanged work deadline.
                try { reportRuntimeHistoryLoanFailure(failure) }
                catch (_: Throwable) { /* Observation never changes mandatory denial. */ }
                denyFailedLoan(failure)
                throw failure
            } finally {
                try { frame?.close() }
                catch (cleanup: Throwable) {
                    val failure = loanFailure
                    if (failure == null) {
                        denyFailedLoan(cleanup)
                        throw cleanup
                    }
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                } finally { active = null }
            }
        }, discard = { value ->
            // Actual native/SQL work has already completed independently. Only private output
            // is disposed here; cancellation never undoes or resubmits its committed effects.
            when (value) { is LocalAuthenticationResult.Authenticated -> value.tokens.destroy(); else -> Unit }
        })
    }
    internal suspend fun prepareNativeChild(frame: RoomRuntimeHistoryFrame,child: NativeHistoryRecordedHeadV2,
        intent: NativeHistoryOriginalIntentV2,installation: SelfOwnedInstallationChild?,
        finalDeadline: NativeHistoryFinalDeadlineDeclaration?, protectedFinal: NativeHistoryProtectedFinalDeclaration?,
        businessPublications: NativeCompanyBusinessPublicationObservation? = null,
        businessPublicationDependencies: ByteArray? = null) {
        requireFrame(frame,frame.connection)
        custody.prepareRoomChild(frame.native,child,intent,installation,finalDeadline,protectedFinal,
            businessPublications,businessPublicationDependencies)
    }
    internal suspend fun prepareNativeSerialChild(frame: RoomRuntimeHistoryFrame, child: NativeHistoryRecordedHeadV2,
        access: NativeSerialPrivateRequestCapsuleV1, origin: NativeSerialPrivateRequestCapsuleV1,
        declaration: NativeHistoryProtectedFinalDeclaration) {
        requireFrame(frame, frame.connection)
        check(serialLoan != null && operational)
        custody.prepareSerialPrivateRoomChild(frame.native, child, access, origin, declaration)
    }
    internal suspend fun bindOriginalEmployeeFrame(frame: RoomRuntimeHistoryFrame, permit: NativeLocalEmployeeProvisioningPermit): NativeEmployeeProvisioningFrame {
        requireFrame(frame, frame.connection)
        check(operational)
        return custody.bindOriginalEmployeeProvisioningFrame(frame.native, permit)
    }
    internal suspend fun bindOriginalEmployeePolicyFrame(frame: RoomRuntimeHistoryFrame,
        permit: NativeLocalEmployeePolicyActivationPermit): NativeEmployeePolicyActivationFrame {
        requireFrame(frame, frame.connection)
        check(operational)
        return custody.bindOriginalEmployeePolicyActivationFrame(frame.native, permit)
    }
    internal suspend fun bindOriginalDeviceAdmissionFrame(frame: RoomRuntimeHistoryFrame,
        permit: NativeLocalDeviceAdmissionPermit): NativeDeviceAdmissionFrame {
        requireFrame(frame, frame.connection)
        check(operational)
        return custody.bindOriginalDeviceAdmissionFrame(frame.native, permit)
    }
    internal fun captureFinalDeadlineNow(frame: RoomRuntimeHistoryFrame, finalWall: com.ukkera.pos.types.UtcInstant) {
        requireFrame(frame,frame.connection); custody.captureRoomFinalDeadlineNow(frame.native,finalWall)
    }
    internal fun finishNativeNow(frame: RoomRuntimeHistoryFrame) {
        requireFrame(frame,frame.connection); custody.finishRoomFrameNow(frame.native)
    }
    internal suspend fun acknowledgeInstallation() {
        check(mode == Mode.INSTALLING && active == null && original.record.parent != null)
        val before = original
        custody.acknowledgeOriginalInstallation()
        val after = custody.snapshotOriginal()
        check(after.record.pending == null && after.record.issuerAcknowledgement != null)
        advanceReceiptMembershipAfterAcknowledgement(before, after)
        original = after
        mode = if (original.record.roleIntent?.role == NativeHistoryDesiredRoleV2.RUN) Mode.OPERATIONAL else Mode.POLICY_HELD
        if (mode == Mode.POLICY_HELD) invalidateReceiptMembership()
    }
    /** Reuses the actual native completion retained after our successful outer Room return.
     * Never opens a new writer, reruns the initializer or constructs a completion from data.
     * Native still refuses if custody/continuity or its same-lifetime capability was lost. */
    internal suspend fun resumeInstallationAcknowledgement(): LocalBranchInstallationReceiptV2 = loans.withLock {
        check(hasUnacknowledgedOriginalInstallation)
        acknowledgeInstallation()
        checkNotNull(original.record.receipt)
    }
    internal fun bindNativeOriginalIntake(store: AtomicMasterStore,database: MasterRoomDatabase,
        endpoint: LocalMasterEndpoint,ingress: NativeOriginalIntake) {
        check(this.store === store && this.database === database && this.endpoint === endpoint && this.ingress == null &&
            mode == Mode.OPERATIONAL && store.ownsNativeOriginalIntake(endpoint,ingress))
        this.ingress = ingress
    }
    internal fun requireNativeOriginal(store: AtomicMasterStore,invocation: NativeOriginalIntakeInvocation,
        lease: NativeOriginalRetainedLease) {
        check(this.store === store && invocation.store === store && invocation.endpoint === endpoint &&
            ingress?.registeredSource === invocation.source && nativeInvocation === invocation && nativeLease === lease &&
            lease.invocation === invocation && invocation.scope == originalScope && !invocation.denied)
    }
    internal suspend fun importNativeOriginal(store: AtomicMasterStore,ingress: NativeOriginalIntake,
        invocation: NativeOriginalIntakeInvocation,lease: NativeOriginalRetainedLease): NativeOriginalIntakeDisposition {
        check(this.store === store && this.ingress === ingress && nativeInvocation == null && nativeLease == null &&
            ingress.registeredSource === invocation.source && invocation.endpoint === endpoint && lease.invocation === invocation)
        nativeInvocation=invocation; nativeLease=lease
        try {
            val result = writerLoan { connection ->
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    val frame = frameFor(connection); frame.enterTransaction(currentCoroutineContext())
                    requireNativeOriginal(store,invocation,lease)
                    val body = store.importNativeOriginalInFrame(frame,invocation,lease)
                    if (body == NativeOriginalHistoryBodyResult.HELD) rollback(body)
                    body
                }
            }
            return NativeOriginalIntakeDisposition(invocation,if (result == NativeOriginalHistoryBodyResult.HELD)
                NativeOriginalIntakeDisposition.State.DISPOSED else NativeOriginalIntakeDisposition.State.SETTLED)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return NativeOriginalIntakeDisposition(invocation,NativeOriginalIntakeDisposition.State.UNCERTAIN) }
        finally { if (mode != Mode.UNKNOWN) { nativeInvocation=null; nativeLease=null } }
    }
    internal fun denyBusiness() {
        invalidateReceiptMembership()
        if (mode == Mode.OPERATIONAL) mode=Mode.POLICY_HELD
    }
    /** Private undelivered output only, inside the same actual watched open invocation.
     * Do not await terminal custody close here: it drains this invocation before Room close. */
    internal suspend fun discardOpenedOutput() = withContext(NonCancellable) {
        denyBusiness(); custody.denyDataUse()
        check(active == null && !loans.isLocked && serialLoan == null)
        receiptMembershipResource?.abort()
    }
    internal suspend fun close() = withContext(NonCancellable) {
        // Stop admission first, then wait for this owner's actual external Room loan. Terminal
        // native cleanup remains available after one-way data denial, but cannot run SQL. It
        // drains the retained Room jobs and invokes our once-bound database::close before
        // the real driver/namespace/process lease can close or release.
        if (mode == Mode.CLOSED) return@withContext
        denyBusiness()
        loans.withLock {
            try {
                check(database.runtimeHistoryOwner === this@RoomRuntimeHistoryOwner && active == null)
                // Never re-enter ordinary runRoomWork after retirement, nor use a fallback
                // that forgets actual Room close. The same terminal owner retains the action.
                // A candidate created by an in-flight settled C1 may have appeared after
                // the initial denial. The loan is now drained, so deny that exact resource too.
                receiptMembershipResource?.denyUse()
                custody.close()
                receiptMembershipResource?.releaseAfterNativeClose()
                receiptMembershipResource=null; receiptMembership=null; mode=Mode.CLOSED
            } catch (failure: Throwable) { invalidateReceiptMembership(); mode=Mode.UNKNOWN; custody.denyDataUse(); throw failure }
        }
    }
    internal companion object {
        fun installation(database: MasterRoomDatabase,custody: SelfOwnedNativeHistoryCustody,
            original: OriginalNativeHistorySnapshot): RoomRuntimeHistoryOwner {
            check(original.record.parent==null && original.record.pending==null && original.record.database!=null &&
                original.record.attempt?.phase==NativeHistoryAttemptPhaseV2.DATABASE_BOUND)
            return RoomRuntimeHistoryOwner(database,custody,original,Mode.INSTALLING)
        }
        fun existing(database: MasterRoomDatabase,custody: SelfOwnedNativeHistoryCustody,
            admitted: RoomOriginalHistoryAdmission, openedOriginal: OriginalNativeHistorySnapshot): RoomRuntimeHistoryOwner {
            admitted.requireOpenedOriginal(openedOriginal)
            return RoomRuntimeHistoryOwner(database,custody,openedOriginal,
                if (openedOriginal.record.roleIntent?.role==NativeHistoryDesiredRoleV2.RUN) Mode.OPERATIONAL else Mode.POLICY_HELD,
                admitted.retailCustomerReceiptMembership)
        }
    }
}

/** Only startup constructs this AFTER complete typed traversal, actual read close, and the
 * exact native post-close reobservation. Its private constructor is not a caller verifier. */
internal class RoomOriginalHistoryAdmission private constructor(
    val original: OriginalNativeHistorySnapshot,
    val retailCustomerReceiptMembership: RuntimeRetailCustomerReceiptMembershipSeed?,
) {
    fun requireOpenedOriginal(opened: OriginalNativeHistorySnapshot) {
        check(opened.lifetimeId == original.lifetimeId && opened.recordDigest == original.recordDigest &&
            opened.record.allocation.digest == original.record.allocation.digest &&
            opened.record.allocation.coordinates.scope == original.record.allocation.coordinates.scope &&
            opened.record.database == original.record.database && opened.record.parent == original.record.parent &&
            opened.record.pending == null)
        retailCustomerReceiptMembership?.let { check(it.index.matchesPreparedAdmission(opened)) }
    }
    companion object {
        fun afterClosedScan(selected: RuntimeHistoryReadSelection,original: OriginalNativeHistorySnapshot): RoomOriginalHistoryAdmission {
            check(selected.holds.isEmpty() && selected.recordDigest==original.recordDigest && original.record.pending==null &&
                original.record.parent!=null && original.record.issuerAcknowledgement!=null &&
                original.record.receipt?.digest==selected.receipt.digest)
            val membership = selected.retailCustomerReceiptMembership
            membership?.let { check(it.recordDigest == original.recordDigest &&
                it.scope == original.record.allocation.coordinates.scope && it.index.matchesPreparedAdmission(original)) }
            return RoomOriginalHistoryAdmission(original, membership)
        }
    }
}

internal suspend fun <T> MasterRoomDatabase.runtimeWriterConnection(block: suspend (Transactor)->T): T =
    runtimeHistoryOwner?.writerLoan(block) ?: useWriterConnection(block)

internal suspend fun <T> MasterRoomDatabase.runtimeApprovedWriterConnection(operationId: com.ukkera.pos.types.OperationId?,
    block: suspend (Transactor)->T): T = if (operationId == null) runtimeWriterConnection(block)
    else runtimeHistoryOwner?.approvedCommandWriterLoan(operationId, block) ?: useWriterConnection(block)
