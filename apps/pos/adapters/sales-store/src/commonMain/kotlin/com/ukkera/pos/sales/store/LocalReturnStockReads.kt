package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** A quantity-only original allocation page. Cost visibility is never inferred from this grant. */
public class LocalReturnStockReadHandler(private val stock: LocalStockRepository) : MasterReadHandler {
    override val key: ReadKey = LocalReturnStockProfileV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalReturnStockReadCodecV1.encode(
        localSalesDecode(input, LocalReturnStockQueryV1.serializer(), 2_048))
    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key)
        val query = localSalesDecode(normalized, LocalReturnStockQueryV1.serializer(), 2_048)
        val loan = commerceLoan(view, stock)
        var result: LocalReturnStockReadResultV1 = LocalReturnStockReadResultV1.NotAvailable
        var revision = Revision(0)
        var owner = context.actorId
        val caseKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.CASE, query.caseId)
        if (loan.aggregate(caseKey) != null) {
            val cut = readReturnStockAcceptance(loan, query.saleId, query.caseId, query.returnId)
            val accepted = cut.original.accepted
            owner = cut.caseRow.value.ownerId
            val state = readReturnStockState(loan, query.returnId)
            state?.value?.let {
                requireLocalSalesSource(it.saleId == accepted.saleId && it.caseId == accepted.claimId && it.portionId == accepted.portionId &&
                    it.acceptedQuantity == accepted.quantity && it.issueId == cut.sale.sale.value.stockIssueId &&
                    it.acceptanceOperationId == cut.acceptance.operationId && it.acceptanceDigest == cut.acceptance.semanticDigest)
            }
            revision = state?.value?.revision ?: Revision(0)
            val read = stock.observeRelational(loan)
            val issue = read.issue(cut.sale.sale.value.stockIssueId) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            val first = read.issueOriginal(issue.id) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
            requireReturnStockIssue(cut, issue, first)
            val page = mutableListOf<LocalReturnStockAllocationViewV1>()
            var available = 0L
            var cursorPresent = query.afterAllocationId == null
            var portionQuantity = Quantity(accepted.quantity.unitId, 0, accepted.quantity.scale)
            var serial = false
            read.issueAllocations(issue.id) { slice ->
                val allocation = slice.allocation
                if (allocation.lineId != accepted.portionId) return@issueAllocations
                requireLocalSalesSource(allocation.quantity.unitId == accepted.quantity.unitId &&
                    allocation.quantity.scale == accepted.quantity.scale)
                portionQuantity += allocation.quantity
                if (allocation.position.serialId != null) serial = true
                if (allocation.allocationId == query.afterAllocationId) cursorPresent = true
                if (query.afterAllocationId == null || allocation.allocationId.value > query.afterAllocationId.value) {
                    check(available < Long.MAX_VALUE); available++
                    val position = read.position(allocation.position) ?: throw LocalSalesSourceUnavailable(LocalSalesSourceFailure.MISSING)
                    page += LocalReturnStockAllocationViewV1(allocation.allocationId, allocation.position.pool.skuId,
                        allocation.position.pool.warehouseId, allocation.position.lotId, allocation.quantity, slice.restoredQuantity,
                        allocation.quantity - slice.restoredQuantity, position.safety.name,
                        position.expiresAtEpochMillis?.let { UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(it)) })
                    page.sortBy { it.allocationId.value }
                    if (page.size > query.limit) page.removeAt(page.lastIndex)
                }
            }
            requireLocalSalesSource(cursorPresent && portionQuantity >= accepted.quantity)
            val posted = state?.value?.postedQuantity ?: Quantity(accepted.quantity.unitId, 0, accepted.quantity.scale)
            if (!serial) result = LocalReturnStockReadResultV1.Original(accepted.saleId, accepted.claimId, accepted.id,
                cut.caseRow.value.revision, accepted.portionId, accepted.quantity,
                LocalReturnDispositionV1.valueOf(accepted.requestedDisposition.name), revision, posted, accepted.quantity - posted,
                returnStockStatus(posted, accepted.quantity), issue.id, issue.revision, page,
                if (available > page.size) page.last().allocationId else null, state?.value?.lastOperationId)
        }
        val requirement = AuthorizationRequirement(Permission(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM), context.scope,
            ActionBinding(query.returnId, revision, context.semanticDigest, null, "return.stock.read"), owner = OwnerConstraint.OwnerPrivate(owner))
        val projection = result
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorizations.single().isBoundTo(requirement, context.actorId))
                return localSalesEncode(projection, LocalReturnStockReadResultV1.serializer(), LocalReturnStockProfileV1.MAX_READ_BYTES)
            }
        }
    }
}
