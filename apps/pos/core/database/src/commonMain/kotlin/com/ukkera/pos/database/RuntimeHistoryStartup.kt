package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Original startup decisions over the actual native custody: rollback-pager recovery,
 * complete typed original traversal, actual read close/native reobservation, then admission
 * for the native adapter's one Room pool. No missing-file recreation, schema migration,
 * pending-guess or supplied health callback. An already native-settled C1 missing only its
 * issuer ACK has a separate explicit completion route after the full scan. A retained pending
 * original C1 is irreversibly fenced BEFORE the pager/read and may be positively classified
 * only by that complete original scan. Neither path admits Room before ACK or promotes
 * reconciliation into RUN. Ready retains the original closed-scan admission; it does not
 * acquire a native driver, open Room or replace the original custody. */
internal object RuntimeHistoryStartup {
    sealed interface Selection {
        class Allocation(val original: OriginalNativeHistorySnapshot): Selection
        class Ready(val admission: RoomOriginalHistoryAdmission): Selection
        class AcknowledgementPending(val original: OriginalNativeHistorySnapshot): Selection
        class Held(val holds: Set<RuntimeHistoryReadHold>): Selection
    }

    suspend fun select(custody: SelfOwnedNativeHistoryCustody,
        producer: RuntimeHistoryBusinessProducer? = null, stockCodec: StockCanonicalProjectionV2? = null): Selection {
        val original=custody.snapshotOriginal()
        if(original.record.database==null) {
            check(original.record.parent==null && original.record.pending==null && original.record.attempt==null)
            return Selection.Allocation(original)
        }
        val pendingOriginal = original.record.parent == null && original.record.firstChild == null && original.record.receipt == null &&
            original.record.pending?.child?.historySequence == 1L &&
            original.record.attempt?.phase in setOf(NativeHistoryAttemptPhaseV2.NATIVE_PENDING, NativeHistoryAttemptPhaseV2.RETIRING) &&
            original.record.retiredAttempts.all { it.issuerRetirementDigest != null }
        // Before-submission/missing-file/rolled-back and unjoined later histories remain held.
        // The one positive extension is the EXACT already-retained native pending original C1.
        if(!pendingOriginal && (original.record.firstChild==null || original.record.receipt==null))
            return Selection.Held(setOf(RuntimeHistoryReadHold.ORIGINAL_INSTALLATION_NATIVE_COMPLETION_UNRESOLVED))
        var projection: RuntimeRetailCustomerReceiptMembershipBuilder? = null
        return custody.runRoomWork(block = {
            if(pendingOriginal) custody.fenceOriginalPendingInstallation()
            custody.recoverOriginalRollbackPager()
            var read: SelfOwnedNativeHistoryReadSession?=null
            var closeStarted=false
            try {
                val session=custody.openOriginalReadOnly(); read=session
                val classifier = producer?.retailCustomerReceiptEventClassifier
                val factory: (suspend (RuntimeHistoryColdMemory) -> RuntimeRetailCustomerReceiptMembershipBuilder)? =
                    if (!pendingOriginal && classifier != null && stockCodec != null) { memory ->
                        RuntimeRetailCustomerReceiptMembershipBuilder.create(custody, session, classifier, memory)
                            .also { projection = it }
                    } else null
                val selected=if(pendingOriginal) RuntimeHistoryScanner.scanFencedPendingOriginal(session)
                    else RuntimeHistoryScanner.scanOriginal(session, producer, stockCodec, factory)
                closeStarted=true
                val closed=withContext(NonCancellable) { session.closeOriginalRead() }
                val after=custody.reobserveAfterActualClose(closed)
                check(after.recordDigest==session.original.recordDigest && after.lifetimeId==session.original.lifetimeId &&
                    after.record.database==session.original.record.database)
                if(pendingOriginal && selected.holds == setOf(
                        RuntimeHistoryReadHold.ORIGINAL_INSTALLATION_NATIVE_COMPLETION_UNRESOLVED,
                        RuntimeHistoryReadHold.ORIGINAL_INSTALLATION_ISSUER_TERMINAL_JOIN_UNRESOLVED)) {
                    check(selected.recordDigest == after.recordDigest && after.record.pending?.child?.historySequence == 1L &&
                        after.record.attempt?.phase == NativeHistoryAttemptPhaseV2.RETIRING)
                    val settled=custody.settleOriginalPendingInstallation(after,SelfOwnedInstallationChild(selected.receipt,selected.firstChild))
                    // Native settlement only; ACK remains explicit. No SQL replay or RUN grant.
                    Selection.AcknowledgementPending(settled)
                } else if(selected.holds == setOf(RuntimeHistoryReadHold.ORIGINAL_INSTALLATION_ISSUER_TERMINAL_JOIN_UNRESOLVED) &&
                    after.record.issuerAcknowledgement == null && after.record.pending == null &&
                    after.record.parent?.historySequence == 1L &&
                    after.record.attempt?.phase == NativeHistoryAttemptPhaseV2.COMPLETED &&
                    after.record.retiredAttempts.all { it.issuerRetirementDigest != null }) {
                    // The full scanner, not receipt/count/metadata shortcuts, established all
                    // C1 seed/lineage/relationship semantics. Native still owns the exact
                    // post-close capability and must independently check its retained COMMIT.
                    check(selected.recordDigest == after.recordDigest &&
                        selected.receipt.digest == after.record.receipt?.digest &&
                        selected.receipt.genesis == after.record.receipt?.genesis)
                    custody.prepareOriginalAcknowledgementRecovery(after)
                    Selection.AcknowledgementPending(after)
                } else if(selected.holds.isNotEmpty()) {
                    custody.completeOriginalReadDelivery(after)
                    Selection.Held(selected.holds)
                } else {
                    // Reopen only the independently owned DATA after the genuine original
                    // read-close/reobservation. This is not a promotion of source scratch.
                    val membership = selected.retailCustomerReceiptMembership
                    membership?.index?.prepareAfterClosedRead(custody, after)
                    val admission=RoomOriginalHistoryAdmission.afterClosedScan(selected,after)
                    custody.admitOriginalRoomAfterClosedRead(after, membership?.index?.nativeData)
                    Selection.Ready(admission)
                }
            } catch(failure: Throwable) {
                withContext(NonCancellable) {
                    // The independent DATA handles must park before an original read close.
                    // A finished build park is never repeated; a prepare attempt has its own
                    // cleanup park. The native lifetime retains final artifact/key disposal.
                    try { projection?.abort() } catch (cleanup: Throwable) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                    val unfinished=read
                    if(unfinished!=null && !closeStarted) {
                        closeStarted=true
                        try { unfinished.closeOriginalRead() } catch (cleanup: Throwable) {
                            if (cleanup !== failure) failure.addSuppressed(cleanup)
                        }
                    }
                    try { custody.denyDataUse() } catch (cleanup: Throwable) {
                        if (cleanup !== failure) failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }, discard = {
            // This disposal is still the original watched invocation. Never await terminal
            // custody close here: it must drain this very invocation before actual cleanup.
            custody.denyDataUse()
            projection?.abort()
        })
    }

    suspend fun requireFirstRoomOpen(custody: SelfOwnedNativeHistoryCustody) {
        val original=custody.snapshotOriginal()
        check(original.record.database==null && original.record.parent==null && original.record.pending==null &&
            original.record.attempt?.phase==NativeHistoryAttemptPhaseV2.SQL_SUBMISSION_ARMED)
    }
}
