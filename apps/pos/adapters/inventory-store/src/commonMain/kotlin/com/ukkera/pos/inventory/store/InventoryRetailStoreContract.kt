package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.inventory.InventoryPostingBatch
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/**
 * Bounded purchase/retail/expiry storage plumbing, not a sales or procurement authority.
 * The two fixture commands are synthetic, closed complete producers. No handlers are installed.
 * Receipt-v1 rows are deliberately not upgraded or accepted as retail-v1 companions.
 */
public object InventoryRetailStoreProfile {
    public val GENESIS_COMMAND: CommandKey = CommandKey("fixture.inventory.retail.genesis", 1)
    public val RECEIVE_COMMAND: CommandKey = CommandKey("fixture.inventory.retail.receive", 1)
    public const val CUT_KIND: String = "inventory.cut"
    public const val CUT_SCHEMA: String = "inventory.cut/v1"
    public const val COMPANION_KIND: String = "inventory.companion"
    public const val COMPANION_SCHEMA: String = "inventory.companion/retail-v1"
    public const val SOURCE_NAMESPACE: String = "inventory.source"
    public const val ORIGIN_NAMESPACE: String = "inventory.receipt.origin"
    public const val MAX_RECEIPTS: Int = 32
    public const val MAX_ISSUES: Int = 32
    /** Includes every retained reservation, not only active reservations. */
    public const val MAX_RESERVATIONS: Int = 32
    public const val MAX_PHYSICAL_SOURCES: Int = 64
    /** Includes source-free operations. */
    public const val MAX_INVENTORY_OPERATIONS: Int = 128
    public const val MAX_ROW_BYTES: Int = 65_536
    public const val MAX_REQUEST_BYTES: Int = 32_768
    public const val MAX_RESULT_BYTES: Int = 4_096
    public const val MAX_PROPOSAL_BYTES: Int = 1_048_576

    public fun cutKey(branchId: DomainId): AggregateKey = AggregateKey(CUT_KIND, branchId)
    public fun companionKey(branchId: DomainId): AggregateKey = AggregateKey(COMPANION_KIND, branchId)
    public fun sourceClaim(sourceId: DomainId): ClaimKey = ClaimKey(SOURCE_NAMESPACE, sourceId.value)
    public fun originClaim(origin: FixtureReceiptOrigin): ClaimKey = InventoryReceiptStoreProfile.originClaim(origin)
    public fun toStorageRevision(domain: Revision): Revision = retailGuard { domain.next() }
    public fun toDomainRevision(storage: Revision): Revision {
        retailCheck(storage.value > 0, InventoryRetailStoreFailure.BINDING_MISMATCH)
        return Revision(storage.value - 1)
    }
}

public enum class InventoryRetailStoreFailure {
    MALFORMED, TOO_LARGE, NON_CANONICAL, UNSUPPORTED_VERSION, BINDING_MISMATCH,
    INCONSISTENT_STATE, UNSUPPORTED_STATE, CURRENT_PAIR_REQUIRED, RETAINED_PRODUCER_REQUIRED,
    CLAIM_MISMATCH, SOURCE_CONFLICT,
}

/** Stable code only. No protected request, origin or parser/arithmetic transcript escapes. */
public class InventoryRetailStoreRejected(public val failure: InventoryRetailStoreFailure) :
    IllegalArgumentException(failure.name)

/** Immutable, ordered nonempty batch. Every entry uses the same expected inventory revision. */
public class InventoryRetailReceiveBatch(receipts: List<InventoryFixtureReceiveRequest>) {
    init {
        retailCheck(receipts.size <= InventoryRetailStoreProfile.MAX_RECEIPTS, InventoryRetailStoreFailure.TOO_LARGE)
        retailCheck(receipts.isNotEmpty())
        retailCheck(receipts.all { it.expectedRevision == receipts.first().expectedRevision })
        retailCheck(receipts.map { it.receiptId }.distinct().size == receipts.size &&
            receipts.map { it.origin }.distinct().size == receipts.size, InventoryRetailStoreFailure.SOURCE_CONFLICT)
        retailGuard { receipts.forEach(::validateReceiptRequest) }
    }
    public val receipts: List<InventoryFixtureReceiveRequest> = receiptFrozenList(receipts)
    public val expectedRevision: Revision get() = this.receipts.first().expectedRevision
}

/** Only the owner needed by the historical physical-source claim; no duplicate issue DTO. */
public data class InventoryRetailIssueSourceBinding(
    public val issueId: DomainId,
    public val producingOperationId: OperationId,
)

/** Expected scope/id/revision are independently obtained from the borrowed row transaction. */
public data class InventoryRetailCompanionBinding(
    public val scope: BranchScope,
    public val id: DomainId,
    public val inventoryRevision: Revision,
)

/**
 * Cumulative receipt provenance, minimal issue ownership, and only the latest A1 posting batch.
 * No reservation-birth index, all-history sidecar, decoded plan, proof, or deadline is retained.
 */
public class InventoryRetailCompanion internal constructor(
    public val scope: BranchScope,
    public val id: DomainId,
    public val inventoryRevision: Revision,
    public val cutDigest: PayloadDigest,
    public val producer: InventoryReceiptProducer,
    public val previous: InventoryReceiptPreviousPair?,
    receipts: List<InventoryReceiptSourceBinding>,
    issueSources: List<InventoryRetailIssueSourceBinding>,
    public val latestBatch: InventoryPostingBatch?,
) {
    init {
        retailCheck(receipts.size <= InventoryRetailStoreProfile.MAX_RECEIPTS &&
            issueSources.size <= InventoryRetailStoreProfile.MAX_ISSUES, InventoryRetailStoreFailure.TOO_LARGE)
    }
    public val receipts: List<InventoryReceiptSourceBinding> = receiptFrozenList(receipts)
    public val issueSources: List<InventoryRetailIssueSourceBinding> = receiptFrozenList(issueSources)
}

/**
 * Unqualified immutable observations from one borrowed transaction, including one selected retained
 * inventory producer. NOT complete-producer approval, authorization, a frontier or capacity proof.
 * In particular, extra composite after-writes have not been compared with today's mutable sales rows.
 */
public class InventoryRetailObservedFacts internal constructor(
    internal val pair: RetailObservedPair,
    public val retained: RetainedCommandEvidence,
) {
    public val scope: BranchScope get() = pair.scope
    public val snapshot: InventorySnapshot get() = pair.snapshot
    public val companion: InventoryRetailCompanion get() = pair.companion
    public val storageGeneration: Revision get() = pair.cutRow.generation
    public val cutBytes: StoredBytes get() = pair.cutRow.payload
    public val companionBytes: StoredBytes get() = pair.companionRow.payload
    public val cutDigest: PayloadDigest get() = pair.cutRow.digest
    public val companionDigest: PayloadDigest get() = pair.companionRow.digest
}

/**
 * Pure partial contribution: exactly two inventory CAS writes, new inventory claims, and the
 * ORIGINAL live plan deadline. No result, requested jobs, Apply, producer approval or whole-envelope
 * size promise. A complete composer still owes its entire 1 MiB binary framing budget and commit.
 */
public class InventoryRetailContribution internal constructor(
    public val snapshot: InventorySnapshot,
    public val companion: InventoryRetailCompanion,
    changes: List<AggregateChange>,
    claims: List<ClaimChange>,
    public val mustCommitBeforeEpochMillis: Long?,
) {
    public val changes: List<AggregateChange> = receiptFrozenList(changes)
    public val claims: List<ClaimChange> = receiptFrozenList(claims)
}

/** Complete synthetic inventory fixture content, still only a proposal until the outer commit. */
public class InventoryRetailFixtureProposal internal constructor(
    public val contribution: InventoryRetailContribution,
    public val result: StoredBytes,
)

public enum class InventoryRetailFixtureResultKind { GENESIS, RECEIVED }

/** Historical result data is not current authorization or an aggregate observation. */
public class InventoryRetailFixtureResult internal constructor(
    public val kind: InventoryRetailFixtureResultKind,
    public val scope: BranchScope,
    public val operationId: OperationId,
    public val inventoryRevision: Revision,
    receiptIds: List<DomainId>,
    public val cutDigest: PayloadDigest,
    public val companionDigest: PayloadDigest,
) {
    init { retailCheck(receiptIds.size <= InventoryRetailStoreProfile.MAX_RECEIPTS, InventoryRetailStoreFailure.TOO_LARGE) }
    public val receiptIds: List<DomainId> = receiptFrozenList(receiptIds)
}

// Internal scalar seam only. The public bridge always supplies real kernel retained evidence.
internal class RetailObservedPair(
    val scope: BranchScope,
    val snapshot: InventorySnapshot,
    val companion: InventoryRetailCompanion,
    val cutRow: ReceiptStoredRow,
    val companionRow: ReceiptStoredRow,
) {
    val storageGeneration: Revision get() = cutRow.generation
}

internal fun retailCheck(condition: Boolean, failure: InventoryRetailStoreFailure = InventoryRetailStoreFailure.INCONSISTENT_STATE) {
    if (!condition) throw InventoryRetailStoreRejected(failure)
}
