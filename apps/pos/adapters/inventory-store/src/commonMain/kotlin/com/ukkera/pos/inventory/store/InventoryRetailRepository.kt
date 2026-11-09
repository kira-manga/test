package com.ukkera.pos.inventory.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandContext
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.inventory.InventoryPlan
import com.ukkera.pos.inventory.InventoryPosting
import com.ukkera.pos.inventory.InventoryPostingBinding
import com.ukkera.pos.inventory.InventoryPostingEffect
import com.ukkera.pos.inventory.InventoryScope
import com.ukkera.pos.inventory.InventorySnapshot
import com.ukkera.pos.inventory.InventorySnapshotBinding
import com.ukkera.pos.inventory.InventorySnapshotCodec
import com.ukkera.pos.inventory.InventorySnapshotKind
import com.ukkera.pos.inventory.ValuationFact
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Inventory storage contributions, NOT a public producer-verifier callback or expected-write port.
 * observe is intentionally unqualified. A closed complete-producer coordinator must validate every
 * retained composite's full request/result/writes/claims/jobs before accepting it for new work.
 * Here only the two concrete synthetic fixture command/version pairs have complete validators.
 */
public object InventoryRetailRepository {
    public suspend fun observe(view: CommandReadView): InventoryRetailObservedFacts = retailGuard {
        val scope = view.scope
        val cut = view.aggregate(InventoryRetailStoreProfile.cutKey(scope.branchId))
        val companion = view.aggregate(InventoryRetailStoreProfile.companionKey(scope.branchId))
        if (cut == null || companion == null) throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.CURRENT_PAIR_REQUIRED)
        val pair = decodeRetailPair(scope, ReceiptStoredRow.from(cut), ReceiptStoredRow.from(companion))
        // One positive lookup for the last INVENTORY operation, never the global journal head.
        val retained = view.retainedCommand(pair.companion.producer.operationId)
            ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
        validateRetailRetainedInventory(pair, RetailRetainedFacts.from(retained))
        for (receipt in pair.companion.receipts) {
            retailCheck(view.claim(InventoryRetailStoreProfile.sourceClaim(receipt.receiptId)) == DomainId(receipt.producingOperationId.value),
                InventoryRetailStoreFailure.CLAIM_MISMATCH)
            retailCheck(view.claim(InventoryRetailStoreProfile.originClaim(receipt.origin)) == receipt.receiptId,
                InventoryRetailStoreFailure.CLAIM_MISMATCH)
        }
        for (issue in pair.companion.issueSources) {
            retailCheck(view.claim(InventoryRetailStoreProfile.sourceClaim(issue.issueId)) == DomainId(issue.producingOperationId.value),
                InventoryRetailStoreFailure.CLAIM_MISMATCH)
        }
        InventoryRetailObservedFacts(pair, retained)
    }

    /**
     * Pure partial projection from the exact loaded source and an actual live plan. No result,
     * complete-producer approval, sales bypass, authorization, freshness, or whole-envelope promise.
     * The composer must propagate the returned ORIGINAL deadline into its outer atomic commit.
     */
    public fun projectContribution(
        observed: InventoryRetailObservedFacts,
        context: CommandContext,
        plan: InventoryPlan,
        nowEpochMillis: Long,
        receiptInputs: List<InventoryFixtureReceiveRequest> = emptyList(),
    ): InventoryRetailContribution = projectRetailContribution(observed.pair, ReceiptContext.from(context), plan, nowEpochMillis, receiptInputs)

    /** Rejects every producer except the exact two closed fixture command/version pairs. */
    public fun requireFixtureProducer(observed: InventoryRetailObservedFacts): Unit =
        validateRetailFixtureProducer(observed.pair, RetailRetainedFacts.from(observed.retained))

    public suspend fun readFixtureReceipts(view: CommandReadView): InventoryRetailObservedFacts =
        observe(view).also(::requireFixtureProducer)

    /** Explicit synthetic initialization only; never a missing-row fallback or live-file authority. */
    public fun fixtureGenesis(context: CommandContext): InventoryRetailFixtureProposal = fixtureRetailGenesis(ReceiptContext.from(context))

    /**
     * Validates THIS receive producer. It does not silently approve the prior producer; an A2 closed
     * fixture caller uses readFixtureReceipts, while a later complete composite coordinator owns its
     * own accepting read. Neither path can reconstruct authorizations from retained historical facts.
     */
    public fun fixtureReceive(
        observed: InventoryRetailObservedFacts,
        context: CommandContext,
        request: InventoryRetailReceiveBatch,
        plan: InventoryPlan,
        nowEpochMillis: Long,
    ): InventoryRetailFixtureProposal = fixtureRetailReceive(observed.pair, ReceiptContext.from(context), request, plan, nowEpochMillis)
}

internal fun decodeRetailPair(scope: BranchScope, cut: ReceiptStoredRow, companion: ReceiptStoredRow): RetailObservedPair = retailGuard {
    validateRetailRows(scope, cut, companion)
    val revision = InventoryRetailStoreProfile.toDomainRevision(cut.generation)
    val snapshot = InventorySnapshotCodec.decode(cut.payload.copyBytes().decodeToString(throwOnInvalidSequence = true),
        InventorySnapshotBinding(InventorySnapshotKind.BRANCH_INVENTORY, InventoryScope(scope.companyId, scope.branchId), scope.branchId, revision))
    val decoded = InventoryRetailCompanionCodec.decode(companion.payload, InventoryRetailCompanionBinding(scope, scope.branchId, revision))
    retailCheck(decoded.cutDigest == cut.digest, InventoryRetailStoreFailure.BINDING_MISMATCH)
    validateRetailCut(snapshot, decoded)
    RetailObservedPair(scope, snapshot, decoded, cut, companion)
}

private fun validateRetailRows(scope: BranchScope, cut: ReceiptStoredRow, companion: ReceiptStoredRow) {
    retailCheck(cut.key == InventoryRetailStoreProfile.cutKey(scope.branchId) && companion.key == InventoryRetailStoreProfile.companionKey(scope.branchId) &&
        cut.schema == InventoryRetailStoreProfile.CUT_SCHEMA && companion.schema == InventoryRetailStoreProfile.COMPANION_SCHEMA &&
        cut.generation == companion.generation && cut.generation.value > 0, InventoryRetailStoreFailure.BINDING_MISMATCH)
    retailCheck(cut.payload.size <= InventoryRetailStoreProfile.MAX_ROW_BYTES && companion.payload.size <= InventoryRetailStoreProfile.MAX_ROW_BYTES,
        InventoryRetailStoreFailure.TOO_LARGE)
    retailCheck(sha256(cut.payload.copyBytes()) == cut.digest && sha256(companion.payload.copyBytes()) == companion.digest,
        InventoryRetailStoreFailure.BINDING_MISMATCH)
}

internal fun projectRetailContribution(
    current: RetailObservedPair,
    context: ReceiptContext,
    plan: InventoryPlan,
    nowEpochMillis: Long,
    receiptInputs: List<InventoryFixtureReceiveRequest> = emptyList(),
): InventoryRetailContribution = retailGuard {
    val before = current.snapshot
    retailCheck(current.scope == context.scope && before.scope == InventoryScope(context.scope.companyId, context.scope.branchId) &&
        plan.sourceSnapshot === before && plan.operationId == context.operationId && plan.requiredInventoryRevision == before.revision,
        InventoryRetailStoreFailure.BINDING_MISMATCH)
    validateRetailRows(current.scope, current.cutRow, current.companionRow)
    retailCheck(current.storageGeneration == InventoryRetailStoreProfile.toStorageRevision(before.revision) &&
        current.companion.cutDigest == current.cutRow.digest && encodeRetailCut(before) == current.cutRow.payload &&
        InventoryRetailCompanionCodec.encode(current.companion) == current.companionRow.payload,
        InventoryRetailStoreFailure.BINDING_MISMATCH)
    validateRetailCut(before, current.companion)
    val batch = InventoryPosting.fromPlan(before, plan, InventoryPostingBinding(before.scope, context.operationId, before.revision, before.revision.next()),
        nowEpochMillis)
    retailCheck(receiptInputs.size <= InventoryRetailStoreProfile.MAX_RECEIPTS, InventoryRetailStoreFailure.TOO_LARGE)
    val postedReceipts = batch.effects.filterIsInstance<InventoryPostingEffect.Valuation>().filter { it.fact is ValuationFact.Receipt }
    retailCheck(postedReceipts.size == receiptInputs.size, InventoryRetailStoreFailure.BINDING_MISMATCH)
    if (receiptInputs.isNotEmpty()) InventoryRetailReceiveBatch(receiptInputs) // Same revision, distinct receipt IDs/origins, positive quantities.
    val newReceipts = receiptInputs.mapIndexed { index, input ->
        retailCheck(input.expectedRevision == before.revision, InventoryRetailStoreFailure.BINDING_MISMATCH)
        retailCheck(input.receiptId !in before.sourceIds && current.companion.receipts.none { it.origin == input.origin },
            InventoryRetailStoreFailure.SOURCE_CONFLICT)
        val value = postedReceipts[index]
        val fact = value.fact as ValuationFact.Receipt
        val movement = batch.effects[index * 2 + 1] as? InventoryPostingEffect.Movement
            ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.INCONSISTENT_STATE)
        val receipt = input.toReceipt(context.scope)
        retailCheck(value.pool == receipt.position.pool && fact.factId == input.receiptId && fact.quantity == input.quantity &&
            fact.originalValue == input.originalValue && movement.sourceId == input.receiptId && movement.position == receipt.position &&
            movement.quantityDelta == input.quantity, InventoryRetailStoreFailure.BINDING_MISMATCH)
        InventoryReceiptSourceBinding(input.receiptId, context.operationId, input.origin, receipt.position, input.quantity,
            input.originalValue, input.expiresAtEpochMillis)
    }
    val recordedIssues = batch.effects.filterIsInstance<InventoryPostingEffect.IssueRecorded>()
    retailCheck(recordedIssues.size <= 1, InventoryRetailStoreFailure.UNSUPPORTED_STATE)
    val newIssues = recordedIssues.map { InventoryRetailIssueSourceBinding(it.issue.issueId, context.operationId) }
    val after = plan.nextSnapshot
    requireRetailSnapshotBounds(after)
    retailCheck(current.companion.receipts.size + newReceipts.size <= InventoryRetailStoreProfile.MAX_RECEIPTS &&
        current.companion.issueSources.size + newIssues.size <= InventoryRetailStoreProfile.MAX_ISSUES, InventoryRetailStoreFailure.TOO_LARGE)
    val cut = encodeRetailCut(after)
    val companion = InventoryRetailCompanion(context.scope, context.scope.branchId, after.revision, sha256(cut.copyBytes()), context.producer(),
        InventoryReceiptPreviousPair(before.revision, current.companion.producer.operationId, current.cutRow.digest, current.companionRow.digest),
        current.companion.receipts + newReceipts, current.companion.issueSources + newIssues, batch)
    validateRetailCut(after, companion) // Includes full receipt expiry and cumulative physical lot correspondence.
    val claims = newReceipts.flatMap(::retailReceiptClaims) + newIssues.map {
        ClaimChange.Acquire(InventoryRetailStoreProfile.sourceClaim(it.issueId), DomainId(it.producingOperationId.value))
    }
    makeRetailContribution(after, companion, cut, current.storageGeneration, claims, plan.mustCommitBeforeEpochMillis)
}

internal fun fixtureRetailGenesis(context: ReceiptContext): InventoryRetailFixtureProposal = retailGuard {
    val normalized = InventoryRetailCommandCodec.genesisRequest()
    validateRetailFixtureContext(context, InventoryRetailStoreProfile.GENESIS_COMMAND, normalized)
    val snapshot = InventorySnapshot.empty(InventoryScope(context.scope.companyId, context.scope.branchId))
    val cut = encodeRetailCut(snapshot)
    val companion = InventoryRetailCompanion(context.scope, context.scope.branchId, Revision(0), sha256(cut.copyBytes()), context.producer(),
        null, emptyList(), emptyList(), null)
    validateRetailCut(snapshot, companion)
    makeRetailFixtureProposal(makeRetailContribution(snapshot, companion, cut, null, emptyList(), null), normalized)
}

internal fun fixtureRetailReceive(
    current: RetailObservedPair,
    context: ReceiptContext,
    request: InventoryRetailReceiveBatch,
    plan: InventoryPlan,
    nowEpochMillis: Long,
): InventoryRetailFixtureProposal = retailGuard {
    val normalized = InventoryRetailCommandCodec.encodeReceive(request)
    validateRetailFixtureContext(context, InventoryRetailStoreProfile.RECEIVE_COMMAND, normalized)
    val contribution = projectRetailContribution(current, context, plan, nowEpochMillis, request.receipts)
    retailCheck(contribution.mustCommitBeforeEpochMillis == null, InventoryRetailStoreFailure.UNSUPPORTED_STATE)
    requireRetailReceiptOnlyBatch(contribution.companion)
    makeRetailFixtureProposal(contribution, normalized)
}

private fun validateRetailFixtureContext(context: ReceiptContext, command: CommandKey, normalized: StoredBytes) {
    retailCheck(context.command == command && semanticDigest(context.scope, context.originalActorId, command, normalized) == context.semanticDigest,
        InventoryRetailStoreFailure.BINDING_MISMATCH)
}

internal fun encodeRetailCut(snapshot: InventorySnapshot): StoredBytes = retailGuard {
    val text = InventorySnapshotCodec.encode(snapshot)
    retailCheck(text.length <= InventoryRetailStoreProfile.MAX_ROW_BYTES, InventoryRetailStoreFailure.TOO_LARGE)
    val bytes = text.encodeToByteArray(throwOnInvalidSequence = true)
    retailCheck(bytes.size <= InventoryRetailStoreProfile.MAX_ROW_BYTES, InventoryRetailStoreFailure.TOO_LARGE)
    StoredBytes(bytes)
}

private fun makeRetailContribution(snapshot: InventorySnapshot, companion: InventoryRetailCompanion, cut: StoredBytes,
    expectedGeneration: Revision?, claims: List<ClaimChange>, deadline: Long?): InventoryRetailContribution {
    retailCheck((expectedGeneration ?: Revision(0)).next() == InventoryRetailStoreProfile.toStorageRevision(snapshot.revision),
        InventoryRetailStoreFailure.BINDING_MISMATCH)
    val companionBytes = InventoryRetailCompanionCodec.encode(companion)
    return InventoryRetailContribution(snapshot, companion, listOf(
        AggregateChange(InventoryRetailStoreProfile.cutKey(companion.id), expectedGeneration, InventoryRetailStoreProfile.CUT_SCHEMA, cut),
        AggregateChange(InventoryRetailStoreProfile.companionKey(companion.id), expectedGeneration, InventoryRetailStoreProfile.COMPANION_SCHEMA, companionBytes),
    ), claims, deadline)
}

private fun makeRetailFixtureProposal(contribution: InventoryRetailContribution, normalized: StoredBytes): InventoryRetailFixtureProposal {
    val companionBytes = contribution.changes.single { it.key.kind == InventoryRetailStoreProfile.COMPANION_KIND }.payload
    val result = retailFixtureResult(contribution.companion, sha256(companionBytes.copyBytes()))
    requireRetailFixtureEnvelopeBudget(normalized, result, contribution)
    return InventoryRetailFixtureProposal(contribution, result)
}

private fun retailFixtureResult(companion: InventoryRetailCompanion, companionDigest: com.ukkera.pos.security.PayloadDigest): StoredBytes =
    InventoryRetailFixtureResultCodec.encode(InventoryRetailFixtureResult(
        if (companion.inventoryRevision == Revision(0)) InventoryRetailFixtureResultKind.GENESIS else InventoryRetailFixtureResultKind.RECEIVED,
        companion.scope, companion.producer.operationId, companion.inventoryRevision,
        companion.receipts.filter { it.producingOperationId == companion.producer.operationId }.map { it.receiptId }, companion.cutDigest, companionDigest))

private fun retailReceiptClaims(receipt: InventoryReceiptSourceBinding): List<ClaimChange.Acquire> = listOf(
    ClaimChange.Acquire(InventoryRetailStoreProfile.sourceClaim(receipt.receiptId), DomainId(receipt.producingOperationId.value)),
    ClaimChange.Acquire(InventoryRetailStoreProfile.originClaim(receipt.origin), receipt.receiptId),
)

/** Only this closed fixture knows its WHOLE proposal (two writes, no requested jobs). */
private fun requireRetailFixtureEnvelopeBudget(normalized: StoredBytes, result: StoredBytes, contribution: InventoryRetailContribution) {
    // AtomicMasterStore uses 4-byte length framing, not JSON/hex. The 16 KiB slack conservatively
    // covers every bounded scalar/header and the empty jobs frame; explicitly count nested effect
    // framing and their ASCII metadata. This estimate never certifies a later composite envelope.
    var bytes = 16_384L + normalized.size + result.size
    for (change in contribution.changes) {
        bytes += 4 + 5 * 4 + change.key.kind.length + change.key.id.value.length +
            (change.expectedRevision ?: Revision(0)).next().value.toString().length + change.schema.length + change.payload.size
    }
    for (claim in contribution.claims) bytes += 4 + 4 * 4 + "claim.acquire".length + claim.key.namespace.length + claim.key.key.length + claim.ownerId.value.length
    retailCheck(bytes <= InventoryRetailStoreProfile.MAX_PROPOSAL_BYTES, InventoryRetailStoreFailure.TOO_LARGE)
}

/** Exact inventory part only. Additional composite rows/claims/jobs are not approved here. */
internal fun validateRetailRetainedInventory(pair: RetailObservedPair, retained: RetailRetainedFacts): Unit = retailGuard {
    val producer = pair.companion.producer
    retailCheck(retained.committed && retained.scope == pair.scope && retained.producer == producer &&
        semanticDigest(pair.scope, producer.originalActorId, producer.command, retained.normalized) == producer.semanticDigest,
        InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
    val inventoryWrites = retained.writes.filter { it.key.kind == InventoryRetailStoreProfile.CUT_KIND || it.key.kind == InventoryRetailStoreProfile.COMPANION_KIND }
    retailCheck(inventoryWrites.size == 2, InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
    for (row in listOf(pair.cutRow, pair.companionRow)) {
        val matching = inventoryWrites.filter { it.key == row.key }
        retailCheck(matching.size == 1 && matching.single() == row, InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
    }
}

/** Concrete closed dispatch, not caller-supplied expected output or an extensible accepting port. */
internal fun validateRetailFixtureProducer(pair: RetailObservedPair, retained: RetailRetainedFacts): Unit = retailGuard {
    validateRetailRetainedInventory(pair, retained)
    val companion = pair.companion
    val normalized: StoredBytes
    val claims: List<ClaimChange.Acquire>
    when (companion.producer.command) {
        InventoryRetailStoreProfile.GENESIS_COMMAND -> {
            retailCheck(pair.snapshot.revision == Revision(0), InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
            normalized = InventoryRetailCommandCodec.genesisRequest()
            claims = emptyList()
        }
        InventoryRetailStoreProfile.RECEIVE_COMMAND -> {
            requireRetailReceiptOnlyBatch(companion)
            val previous = companion.previous ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
            val received = companion.receipts.filter { it.producingOperationId == companion.producer.operationId }
            normalized = InventoryRetailCommandCodec.encodeReceive(InventoryRetailReceiveBatch(received.map { receipt ->
                InventoryFixtureReceiveRequest(previous.inventoryRevision, receipt.receiptId, receipt.position.pool.skuId, receipt.position.pool.warehouseId,
                    receipt.position.lotId, receipt.quantity, receipt.originalValue, receipt.expiresAtEpochMillis, receipt.origin)
            }))
            claims = received.flatMap(::retailReceiptClaims)
        }
        else -> throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
    }
    val expectedClaims = claims.map { RetailRetainedClaim(RetainedClaimAction.ACQUIRE, it.key, it.ownerId) }.toSet()
    retailCheck(retained.normalized == normalized && retained.result == retailFixtureResult(companion, pair.companionRow.digest) &&
        retained.writes.size == 2 && retained.jobs.isEmpty() && retained.claims.size == expectedClaims.size && retained.claims.toSet() == expectedClaims,
        InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
}

private fun requireRetailReceiptOnlyBatch(companion: InventoryRetailCompanion) {
    val receipts = companion.receipts.filter { it.producingOperationId == companion.producer.operationId }
    val batch = companion.latestBatch ?: throw InventoryRetailStoreRejected(InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
    retailCheck(receipts.isNotEmpty() && companion.issueSources.none { it.producingOperationId == companion.producer.operationId } &&
        batch.effects.size == receipts.size * 2, InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
    for (index in receipts.indices) {
        val value = batch.effects[index * 2] as? InventoryPostingEffect.Valuation
        val movement = batch.effects[index * 2 + 1] as? InventoryPostingEffect.Movement
        val fact = value?.fact as? ValuationFact.Receipt
        retailCheck(fact?.factId == receipts[index].receiptId &&
            movement?.sourceId == receipts[index].receiptId, InventoryRetailStoreFailure.RETAINED_PRODUCER_REQUIRED)
    }
}

// Adapter-owned copied scalar test seam. It is internal, not a public verifier or an authorization
// callback. Production observations always carry the actual immutable RetainedCommandEvidence.
internal data class RetailRetainedClaim(val action: RetainedClaimAction, val key: ClaimKey, val ownerId: DomainId)

internal data class RetailRetainedFacts(
    val committed: Boolean,
    val scope: BranchScope,
    val producer: InventoryReceiptProducer,
    val normalized: StoredBytes,
    val result: StoredBytes,
    val writes: List<ReceiptStoredRow>,
    val claims: List<RetailRetainedClaim>,
    val jobs: List<Pair<String, StoredBytes>>,
) {
    companion object {
        fun from(value: RetainedCommandEvidence): RetailRetainedFacts = RetailRetainedFacts(value.committed, value.scope,
            InventoryReceiptProducer(value.operationId, value.command, value.originalActorId, value.semanticDigest), value.normalized, value.result,
            receiptFrozenList(value.aggregateWrites.map { ReceiptStoredRow(it.key, it.storageGeneration, it.schema, it.payload, it.payloadDigest) }),
            receiptFrozenList(value.claimChanges.map { RetailRetainedClaim(it.action, it.key, it.ownerId) }),
            receiptFrozenList(value.jobs.map { it.kind to it.body }))
    }
}
