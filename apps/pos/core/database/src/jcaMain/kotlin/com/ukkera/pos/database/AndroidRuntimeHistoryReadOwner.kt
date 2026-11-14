package com.ukkera.pos.database

import com.ukkera.pos.security.NativeHistoryUnavailable
import com.ukkera.pos.security.OriginalNativeHistorySnapshot
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody
import com.ukkera.pos.security.SelfOwnedNativeHistoryReadSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Private installation-host read owner. Only the concrete opaque original P2 custody may
 * construct it. No SQL/path/driver/provider/decryptor/verifier/close callback is injectable.
 * Native keeps its actual handle/jobs/statements/buffers until their watched disposal; this
 * class owns neither another pool nor an independent lease and never announces lease release.
 *
 * A complete, exactly supported original-C1-only history can yield its original receipt;
 * observed unsupported history or unsettled native/issuer fate cannot. Both actual resource
 * close and original native/file reobservation precede one-shot delivery under P2 denial.
 * This is never installation completion, attempt retirement, settlement or a runtime grant.
 * Build/all-writer/schema/device qualification remains required before product activation.
 */
internal class AndroidRuntimeHistoryReadOwner private constructor(
    private val custody: SelfOwnedNativeHistoryCustody,
) {
    private val busy = AtomicBoolean(false)
    private val denied = AtomicBoolean(false)

    suspend fun readOriginalInstallationReceipt(): OriginalInstallationReceiptResult {
        currentCoroutineContext().ensureActive()
        if (denied.get()) return OriginalInstallationReceiptResult.Unresolved
        if (!busy.compareAndSet(false, true)) return OriginalInstallationReceiptResult.Busy
        var session: SelfOwnedNativeHistoryReadSession? = null
        var closeAttempted = false
        var beforeAdmissionBusy = false
        var delivered = false
        try {
            currentCoroutineContext().ensureActive()
            // Actual original snapshot, original-key opening and fixed no-CREATE raw handle
            // all originate in the SAME retained native owner, before any bytes are accepted.
            val originalRead = custody.openOriginalReadOnly()
            session = originalRead
            val original = originalRead.original
            val selected = RuntimeHistoryScanner.scanOriginal(originalRead)
            check(selected.recordDigest == original.recordDigest)

            closeAttempted = true
            val actuallyClosed = withContext(NonCancellable) { originalRead.closeOriginalRead() }
            currentCoroutineContext().ensureActive()
            // This consumes the exact one-time native close acknowledgement, not a Boolean.
            // Native checks original owner/session/full traversal and reopens no SQLite handle.
            val afterClose = custody.reobserveAfterActualClose(actuallyClosed)
            requireSameOriginal(original, afterClose)
            currentCoroutineContext().ensureActive()
            if (selected.holds.isNotEmpty()) return OriginalInstallationReceiptResult.Unresolved

            val result = OriginalInstallationReceiptResult.Found(selected.receipt)
            currentCoroutineContext().ensureActive()
            // No SQL/key/file/provider IO or suspension after this affine publication cut.
            // It consumes only this exact actually closed/reobserved read, never a writer,
            // original-completion or retirement capability. Data-lifetime denial wins first.
            custody.completeOriginalReadDelivery(afterClose)
            delivered = true
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: NativeHistoryUnavailable) {
            // A work-slot/read admission BUSY occurs before this call owns a raw session.
            // It must not deny or close the unrelated invocation already occupying that slot.
            if (session == null && unavailable.reason == SelfOwnedInstallationFailureV1.BUSY) {
                beforeAdmissionBusy = true
                return OriginalInstallationReceiptResult.Busy
            }
            return OriginalInstallationReceiptResult.Unresolved
        } catch (_: Exception) {
            return OriginalInstallationReceiptResult.Unresolved
        } finally {
            try {
                if (!delivered && !beforeAdmissionBusy) {
                    denied.set(true)
                    withContext(NonCancellable) {
                        try {
                            // Partial traversal can be actually closed, but cannot yield an
                            // accepting reobserve. Never retry a close whose outcome is unknown.
                            val opened = session
                            if (opened != null && !closeAttempted) {
                                closeAttempted = true
                                try { opened.closeOriginalRead() }
                                catch (_: Exception) { /* Native retains actual/unknown resources. */ }
                            }
                        } finally {
                            custody.denyDataUse()
                            try { custody.close() }
                            catch (_: Exception) {
                                // The actual P2 watchdog/owner retains exclusion on uncertain
                                // close. No local flag, finally or receipt releases its lease.
                            }
                        }
                    }
                }
            } finally { busy.set(false) }
        }
    }

    private fun requireSameOriginal(before: OriginalNativeHistorySnapshot, after: OriginalNativeHistorySnapshot) {
        check(after.lifetimeId == before.lifetimeId && after.recordDigest == before.recordDigest &&
            after.record.generation == before.record.generation && after.record.denialGeneration == before.record.denialGeneration &&
            after.record.allocation.digest == before.record.allocation.digest && after.record.database == before.record.database)
        val expected = before.originalBinding.copyWireBytes()
        try {
            val observed = after.originalBinding.copyWireBytes()
            try { check(expected.contentEquals(observed)) } finally { observed.fill(0) }
        } finally { expected.fill(0) }
    }

    override fun toString(): String = "AndroidRuntimeHistoryReadOwnerV1([REDACTED])"

    companion object {
        internal fun fromOriginalCustody(custody: SelfOwnedNativeHistoryCustody): AndroidRuntimeHistoryReadOwner =
            AndroidRuntimeHistoryReadOwner(custody)
    }
}
