package com.ukkera.pos.database

import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Actual private-host P3 construction edge. No application/path/SQL/key/provider/scanner/
 * verifier factory or status flag can be supplied. The opaque context must originate in P2.
 * D2's claim operation MUST reject a preparation/creating environment before re-observation.
 *
 * This source depends on the separately owned concrete D2 claim/read-owner implementation;
 * missing producers are integration gates, not replaced by accepting dummy callbacks.
 */
internal object OriginalInstallationReceiptOwnerCore {
    /** [nativeOwner] is the platform facade's genuine security owner, never a caller callback. */
    suspend fun attachOriginal(
        nativeOwner: SelfOwnedMasterNativeOwner,
        actualContext: SelfOwnedLocalSecurityContext,
    ): LocalMasterInstallationReceiptOpenResult {
        currentCoroutineContext().ensureActive()
        var claimed: SelfOwnedNativeHistoryCustody? = null
        var clientDelivered = false
        try {
            return when (val claim = nativeOwner.claimOriginalHistoryCustody(actualContext)) {
                is SelfOwnedNativeHistoryClaimResult.Claimed -> {
                    claimed = claim.custody
                    // This constructor only retains the actual opaque claim. It opens no
                    // second work/lease/pool or database; the D2 owner owns all actual reads.
                    val owner = AndroidRuntimeHistoryReadOwner.fromOriginalCustody(claim.custody)
                    val client = OriginalReceiptClient(owner)
                    val opened = LocalMasterInstallationReceiptOpenResult.Opened(client, claim.custody)
                    currentCoroutineContext().ensureActive()
                    clientDelivered = true
                    opened
                }
                is SelfOwnedNativeHistoryClaimResult.Unresolved ->
                    if (claim.reason == SelfOwnedInstallationFailureV1.BUSY)
                        LocalMasterInstallationReceiptOpenResult.Busy
                    else LocalMasterInstallationReceiptOpenResult.Unresolved
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return LocalMasterInstallationReceiptOpenResult.Unresolved
        } finally {
            val actual = claimed
            if (actual != null && !clientDelivered) {
                // Claimed ownership is not discarded by cancellation or construction failure.
                // P2/D2 retain the actual independent jobs/watchdog on uncertain close; this
                // wrapper never declares their lease free. A rejected BUSY claim closes nothing.
                // The old context is inert after transfer. Only this actual claimed owner
                // can deny/drain/close; closing the context would leak the native lifetime.
                actual.denyDataUse()
                withContext(NonCancellable) {
                    try { actual.close() }
                    catch (_: Exception) { /* Actual native owner retains unknown-close exclusion. */ }
                }
            }
        }
    }

    private class OriginalReceiptClient(
        private val owner: AndroidRuntimeHistoryReadOwner,
    ) : LocalMasterInstallationReceiptClient {
        override suspend fun recoverOriginalReceipt(): OriginalInstallationReceiptResult {
            currentCoroutineContext().ensureActive()
            // D2 performs complete scan, fixed selection, actual physical close and final
            // native/delivery-generation recheck before it can create Found. No local Boolean,
            // isOpen test, P3 callback or caller-provided close proof substitutes for that.
            return owner.readOriginalInstallationReceipt()
        }
        override fun toString(): String = "AndroidOriginalInstallationReceiptOwnerV1.Client([REDACTED])"
    }
}
