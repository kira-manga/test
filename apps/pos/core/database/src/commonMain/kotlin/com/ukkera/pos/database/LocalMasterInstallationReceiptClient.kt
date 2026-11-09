package com.ukkera.pos.database

import com.ukkera.pos.security.LocalBranchInstallationReceiptV2
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody

/**
 * Private installation-host surface, NOT an employee/client RPC or operational service.
 * Only the concrete original Android P2/P4 owner is a production implementation.
 * It is never registered as a signer/custody/runtime verifier or generic transport dependency.
 */
public interface LocalMasterInstallationReceiptClient {
    /**
     * Positive original receipt only, after a complete same-snapshot history check and actual
     * physical read cleanup. No caller scope/request/password/definition/key/path is accepted.
     * This never creates files, repairs a pager, settles a pending attempt, writes an ACK,
     * renews installer/head authority, authorizes business-result disclosure or starts runtime.
     */
    public suspend fun recoverOriginalReceipt(): OriginalInstallationReceiptResult
}

public sealed interface OriginalInstallationReceiptResult {
    /**
     * Immutable receipt DATA for the private installation host. It is not a runtime capability,
     * custody proof, complete-history proof transferable to another lifetime, or fresh permit.
     * UI/transport must receive only their separately authorized bounded progress projection.
     */
    public class Found internal constructor(
        public val receipt: LocalBranchInstallationReceiptV2,
    ) : OriginalInstallationReceiptResult {
        override fun toString(): String = "OriginalInstallationReceiptResultV1.Found([REDACTED])"
    }

    public data object Busy : OriginalInstallationReceiptResult

    /** No safe absence inference, repair permission or new-company route follows this result. */
    public data object Unresolved : OriginalInstallationReceiptResult
}

/** Bound private reader only. Opened never means a receipt exists or a runtime is admitted. */
public sealed interface LocalMasterInstallationReceiptOpenResult {
    public class Opened internal constructor(
        public val client: LocalMasterInstallationReceiptClient,
        private val custody: SelfOwnedNativeHistoryCustody,
    ) : LocalMasterInstallationReceiptOpenResult {
        /** Host-only lifetime controls. The read client deliberately cannot close its owner. */
        public fun denyNewReads(): Unit = custody.denyDataUse()
        public suspend fun close() {
            custody.denyDataUse()
            custody.close()
        }
        override fun toString(): String = "LocalMasterInstallationReceiptOpenResultV1.Opened([REDACTED])"
    }
    public data object Busy : LocalMasterInstallationReceiptOpenResult
    public data object Unresolved : LocalMasterInstallationReceiptOpenResult
}
