package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Nonphysical stock disposition, independently authorized and atomic with inventory/result. */
public class LocalReturnStockHandler(private val stock: LocalStockRepository,
    private val authority: LocalStockAuthorityReader) : MasterStreamingCommandHandler {
    override val key: CommandKey = LocalReturnStockProfileV1.COMMAND
    override val writableKinds: Set<String> = StockRelationalKindsV2.all - StockRelationalKindsV2.ACTIVATION +
        setOf(LocalReturnStockProfileV1.STATE, LocalReturnStockProfileV1.POSTING, COMMERCE_STOCK_LINK_KIND_V2)
    override val claimNamespaces: Set<String> = LocalStockStoreProfile.inventoryClaimNamespaces + LocalReturnStockProfileV1.NAMESPACE
    override fun normalize(input: StoredBytes): StoredBytes = LocalReturnStockCodecV1.normalize(input)
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedStreamingMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalReturnStockCodecV1.decode(normalized)
        // Owner policy is derived from the real CASE row, never from a client field.
        // The proposal closes its complete immutable acceptance before any stock effect.
        val caseRow = view.aggregate(LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, request.caseId))
        val caseOwner = caseRow?.let {
            val state = decodeLocalSalesSource(it.payload, LocalReturnCaseStateV1.serializer())
            requireLocalSalesSource(state.scope.value() == view.scope && state.caseId == request.caseId &&
                state.revision == it.domainRevision() && it.schema == LocalSalesStoreProfile.schema(it.key.kind) &&
                com.ukkera.pos.security.sha256(it.payload.copyBytes()) == it.digest)
            state.ownerId
        } ?: context.originalActorId
        val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), context.scope,
            ActionBinding(request.returnId, request.expectedDispositionRevision, context.semanticDigest, context.operationId, "return.stock.post"),
            owner = OwnerConstraint.OwnerPrivate(caseOwner))
        return object : PreparedStreamingMutation() {
            override val requirement = requirement
            override val allowsActionApproval: Boolean = false
            override fun supportsRecordedRejection(rejection: StreamedMutationDecision.Reject): Boolean =
                rejection.code == "sales.return.stock.rejected" && rejection.result == LocalReturnStockResultCodecV1.encode(
                    LocalReturnStockResultV1.Rejected("DISPOSITION_REVISION_CHANGED"))
            override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.STOCK, RecoveryResourceKind.AUTHORIZATION)
            override val affectedResources: Set<RecoveryResourceKey> = setOf(entitlementResource(request.returnId),
                entitlementResource(request.saleId), orderResource(request.caseId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, request.issueId),
                RecoveryResourceKey(RecoveryResourceKind.STOCK, request.restorationId),
                RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
            override suspend fun propose(authorization: AuthorizedAction, recordedAt: UtcInstant,
                sink: StreamedCommandSink): StreamedMutationDecision {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.originalActorId))
                return try { postReturnStock(stock, authority, view, context, request, caseOwner, recordedAt, sink) }
                catch (failure: LocalReturnStockRefused) {
                    if (failure.reason !in LocalReturnStockProfileV1.rejectionReasons) throw failure
                    commerceReject("sales.return.stock.rejected", LocalReturnStockResultCodecV1.encode(LocalReturnStockResultV1.Rejected(failure.reason)))
                } catch (failure: LocalStockRejected) {
                    // Corrupt/missing/unsupported producer evidence and storage faults stay uncertain.
                    val reason = when (failure.failure) {
                        LocalStockFailure.UNSAFE_STOCK -> "STOCK_UNAVAILABLE"
                        LocalStockFailure.STALE_REVISION -> "STOCK_REVISION_CHANGED"
                        else -> throw failure
                    }
                    commerceReject("sales.return.stock.rejected", LocalReturnStockResultCodecV1.encode(LocalReturnStockResultV1.Rejected(reason)))
                }
            }
        }
    }
}

private suspend fun postReturnStock(stock: LocalStockRepository, authority: LocalStockAuthorityReader, borrowed: CommandReadView,
    context: CommandContext, request: LocalReturnStockRequestV1, expectedOwner: DomainId, at: UtcInstant, sink: StreamedCommandSink): StreamedMutationDecision {
    sink.requireContext(borrowed.requireStockRelational(), context)
    val qualified = commerceLoan(borrowed, stock)
    val acceptance = readReturnStockAcceptance(qualified, request.saleId, request.caseId, request.returnId)
    requireLocalSalesSource(acceptance.caseRow.value.ownerId == expectedOwner)
    requireReturnStock(acceptance.caseRow.value.revision == request.expectedCaseRevision, "CASE_REVISION_CHANGED")
    requireReturnStock(acceptance.sale.sale.value.stockIssueId == request.issueId, "ORIGINAL_ISSUE_CHANGED")
    val before = readReturnStockState(qualified, request.returnId)
    val posting = returnStockPostingValue(context.scope, context.operationId, context.semanticDigest, context.originalActorId, at,
        request, acceptance.original.accepted, acceptance.acceptance.operationId, acceptance.acceptance.semanticDigest,
        before?.value, before?.stored?.digest)
    val read = stock.observeRelational(qualified)
    val issue = read.issue(request.issueId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    val first = read.issueOriginal(issue.id) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
    requireReturnStock(issue.revision == request.expectedIssueRevision, "ORIGINAL_ISSUE_CHANGED")
    requireReturnStockIssue(acceptance, issue, first)
    val selected = request.selections.associateBy { it.allocationId }
    val found = mutableSetOf<DomainId>()
    read.issueAllocations(issue.id) { slice ->
        val selection = selected[slice.allocation.allocationId] ?: return@issueAllocations
        requireReturnStock(found.add(selection.allocationId) && slice.allocation.lineId == posting.after.portionId,
            "ORIGINAL_ALLOCATION_REQUIRED")
        requireReturnStock(slice.allocation.position.serialId == null, "DISPOSITION_NOT_SUPPORTED")
        requireReturnStock(selection.quantity.unitId == slice.allocation.quantity.unitId &&
            selection.quantity.scale == slice.allocation.quantity.scale, "ORIGINAL_UNIT_REQUIRED")
        requireReturnStock(selection.quantity <= slice.allocation.quantity - slice.restoredQuantity, "ACCEPTED_QUANTITY_EXHAUSTED")
    }
    requireReturnStock(found == selected.keys, "ORIGINAL_ALLOCATION_REQUIRED")
    val view = CommerceLoan(borrowed, stock, sink)
    captureReturnStockAcceptance(qualified, sink, acceptance)
    captureReturnStockState(sink, before, request.returnId, qualified)
    val stateClaim = view.claim(LocalReturnStockProfileV1.stateClaim(request.returnId))
    requireLocalSalesSource(stateClaim == before?.let { request.returnId })
    val postingKey = LocalReturnStockProfileV1.postingKey(request.restorationId)
    val existing = view.aggregate(postingKey)
    val existingClaim = view.claim(LocalReturnStockProfileV1.postingClaim(request.restorationId))
    requireLocalSalesSource((existing == null) == (existingClaim == null))
    requireReturnStock(existing == null, "DUPLICATE_SOURCE")
    val inventory = stock.streamRelational(view, context, sink, authority, at)
    inventory.restoreRetailReturn(returnStockInventoryRequest(posting))
    val writes = CommerceWrites(view, context, sink)
    writes.put(AggregateChange(LocalReturnStockProfileV1.stateKey(request.returnId), before?.stored?.revision,
        returnStockSchema(LocalReturnStockProfileV1.STATE), localSalesEncode(posting.after, LocalReturnStockStateV1.serializer(), 8_192)),
        before?.stored, true)
    writes.put(AggregateChange(postingKey, null, returnStockSchema(LocalReturnStockProfileV1.POSTING),
        localSalesEncode(posting, LocalReturnStockPostingV1.serializer(), 16_384)), null)
    if (before == null) writes.claim(ClaimChange.Acquire(LocalReturnStockProfileV1.stateClaim(request.returnId), request.returnId))
    writes.claim(ClaimChange.Acquire(LocalReturnStockProfileV1.postingClaim(request.restorationId), request.returnId))
    writes.resources(acceptance.resources + RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
    emitCommerceRowResources(sink)
    return inventory.finish(LocalReturnStockResultCodecV1.encode(posting.result))
}
