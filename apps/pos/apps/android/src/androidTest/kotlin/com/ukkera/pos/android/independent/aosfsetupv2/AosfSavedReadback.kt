package com.ukkera.pos.android.independent.aosfsetupv2

import android.app.Application
import com.ukkera.pos.database.AndroidOriginalInstallationReceiptOwner
import com.ukkera.pos.database.LocalMasterInstallationReceiptOpenResult
import com.ukkera.pos.database.OriginalInstallationReceiptResult
import com.ukkera.pos.security.AndroidSelfOwnedInstallationOwner
import com.ukkera.pos.security.LocalBranchInstallationReceiptV2
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Reads only the saved original installation receipt through the actual private receipt owner.
 * The reader performs its own complete historical check and physical read cleanup. It is not a
 * master client, session, ACK, go-live or business-read substitute.
 */
internal suspend fun readAosfSavedReceipt(application: Application): LocalBranchInstallationReceiptV2 {
    var context: SelfOwnedLocalSecurityContext? = null
    var opened: LocalMasterInstallationReceiptOpenResult.Opened? = null
    try {
        val acquired = AndroidSelfOwnedInstallationOwner.openOriginal(application)
        val actualContext = (acquired as? SelfOwnedNativeOpenResult.Opened)?.context
            ?: error("Saved original receipt read could not reopen the original: $acquired")
        context = actualContext
        withContext(NonCancellable) {
            context = null // This producer cleans claimed/undelivered ownership itself.
            when (val result = AndroidOriginalInstallationReceiptOwner.attachOriginal(actualContext)) {
                is LocalMasterInstallationReceiptOpenResult.Opened -> opened = result
                LocalMasterInstallationReceiptOpenResult.Busy -> {
                    context = actualContext // BUSY leaves the context caller-owned.
                    error("Saved original receipt read is busy")
                }
                LocalMasterInstallationReceiptOpenResult.Unresolved ->
                    error("Saved original receipt read is unresolved")
            }
        }
        val receipt = when (val read = checkNotNull(opened).client.recoverOriginalReceipt()) {
            is OriginalInstallationReceiptResult.Found -> read.receipt
            OriginalInstallationReceiptResult.Busy -> error("Saved original receipt read returned busy")
            OriginalInstallationReceiptResult.Unresolved -> error("Saved original receipt read returned unresolved")
        }
        return receipt
    } finally {
        cleanupAosf(
            { opened?.let { actual -> opened = null; actual.close() } },
            { context?.let { actual -> context = null; actual.close() } },
        )
    }
}
