package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Inert exact-row locator. Only the current native-owned read view establishes complete
 * membership; constructing or retaining a locator grants neither access nor completeness. */
public data class RuntimeRetailCustomerReceiptEventLocator(
    public val key: AggregateKey,
    public val revision: Revision,
    public val schema: String,
    public val digest: PayloadDigest,
) {
    override fun toString(): String = "RuntimeRetailCustomerReceiptEventLocator([REDACTED])"
}

/** One trusted composition-time canonical receipt decoder. Core selects every actual EVENT
 * and its exact committed employee original; the decoder must reject malformed or mismatched
 * row/request/result attribution. This port cannot select rows, create a writer, or replace
 * native lineage/settlement checks with a health assertion. */
public fun interface RuntimeRetailCustomerReceiptEventClassifier {
    public fun saleId(scope: BranchScope, row: StoredAggregate, original: RetainedCommandEvidence): DomainId
}

internal object RuntimeRetailCustomerReceiptMembershipProfile {
    const val EVENT_KIND: String = "sales.customer-receipt.event"
    const val EVENT_SCHEMA: String = "sales.customer-receipt.event/v1"
    val COMMAND: CommandKey = CommandKey("sales.customer-receipt.write", 1)
    const val MAX_EVENTS: Int = 64
}

/** Closed-scan DATA with one parked, independent encrypted resource and a bounded live root.
 * Admission still requires the original native read to close and be reobserved; this object
 * retains no historical reader, SQL loan, whole-branch map or transferable authority. */
internal class RuntimeRetailCustomerReceiptMembershipSeed(
    val classifier: RuntimeRetailCustomerReceiptEventClassifier,
    val scope: BranchScope,
    val recordDigest: PayloadDigest,
    val index: RuntimeRetailCustomerReceiptMembershipIndex,
) {
    init {
        check(index.matchesClosedSeed(scope, recordDigest))
    }

    override fun toString(): String = "RuntimeRetailCustomerReceiptMembershipSeed([REDACTED])"
}
