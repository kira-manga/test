package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Current protected receiving eligibility and actor-private durable offer discovery, never a directory grant. */
public class LocalFoodHandoffDirectoryReadHandler : MasterReadHandler {
    override val key: ReadKey = LocalFoodHandoffProfileV2.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = localSalesEncode(
        localSalesDecode(input, LocalFoodHandoffQueryV2.serializer(), 2_048), LocalFoodHandoffQueryV2.serializer(), 2_048)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key)
        val query = localSalesDecode(normalized, LocalFoodHandoffQueryV2.serializer(), 2_048)
        val bounded = FoodReadView(view)
        return when (query) {
            is LocalFoodHandoffQueryV2.Recipients -> recipients(bounded, context, query)
            is LocalFoodHandoffQueryV2.Incoming -> incoming(bounded, context, query)
        }
    }

    private suspend fun recipients(view: FoodReadView, context: ReadContext, query: LocalFoodHandoffQueryV2.Recipients): PreparedRead {
        val header = foodObserveRow(view, LocalFoodProfileV1.ORDER, query.orderId)
        val body = header?.envelope?.body as? FoodBodyV1.Order
        val requirement = handoffDirectoryRequirement(context, query.orderId, header?.envelope?.revision ?: Revision(0), body?.ownerId ?: context.actorId)
        val result = if (body == null || body.ownerId != context.actorId || body.handoff != null ||
            header?.envelope?.revision != query.expectedOrderRevision) unavailableDirectory(LocalFoodHandoffDirectoryUnavailableV2.TARGET_UNAVAILABLE)
        else try {
            val current = foodOrder(view, query.orderId, includeClosed = true)
            check(current.order.envelope.revision == query.expectedOrderRevision && current.value.ownerId == context.actorId && current.value.handoff == null)
            val financial = if (current.value.financialCollectionId == null) null else foodPaidHandoffCut(view, current)
            if (current.value.closedByOperationId != null) unavailableDirectory(LocalFoodHandoffDirectoryUnavailableV2.TARGET_UNAVAILABLE)
            else if (financial?.unresolved == true) unavailableDirectory(LocalFoodHandoffDirectoryUnavailableV2.PAYMENT_UNRESOLVED)
            else if (financial == null && !LocalFoodHandoffPrecollection.verify(view, query.orderId, current.order.envelope.revision))
                unavailableDirectory(LocalFoodHandoffDirectoryUnavailableV2.NOT_PRECOLLECTION)
            else {
                val page = TransactionLocalHandoffRecipients.page(view.handoffBorrowedView(), query.orderId, query.after)
                LocalFoodHandoffDirectoryResultV2(recipients = page.recipients.map {
                    LocalFoodRecipientV2(it.actorId, it.label, it.accountRevision, it.policyRevision)
                }, nextRecipient = page.nextAfter, financial = financial?.projection())
            }
        } catch (_: FoodCapacityExceeded) { unavailableDirectory(LocalFoodHandoffDirectoryUnavailableV2.CAPACITY_REQUIRED) }
        return preparedDirectory(context, listOf(requirement), result)
    }

    private suspend fun incoming(view: FoodReadView, context: ReadContext, query: LocalFoodHandoffQueryV2.Incoming): PreparedRead {
        // A supplied handle is checked against actual server issuance BEFORE any claim/source
        // scan. The empty-result authorization below selects an existing exact grant only.
        val result = try { scanIncoming(view, context, query.cursor) }
        catch (_: FoodCapacityExceeded) { unavailableDirectory(LocalFoodHandoffDirectoryUnavailableV2.CAPACITY_REQUIRED) }
        catch (_: HandoffCursorExpired) { unavailableDirectory(LocalFoodHandoffDirectoryUnavailableV2.CURSOR_EXPIRED) }
        val requirements = result.incoming.map { handoffDirectoryRequirement(context, it.orderId, it.orderStorageRevision, context.actorId) }
            .ifEmpty {
                val anchor = TransactionLocalHandoffRecipients.ownInboxAuthorizationTarget(view.handoffBorrowedView()) ?: context.actorId
                listOf(handoffDirectoryRequirement(context, anchor, Revision(0), context.actorId))
            }
        return preparedDirectory(context, requirements, result)
    }
}

private fun handoffDirectoryRequirement(context: ReadContext, target: DomainId, revision: Revision, owner: DomainId): AuthorizationRequirement =
    AuthorizationRequirement(Permission(SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM), context.scope,
        ActionBinding(target, revision, context.semanticDigest, null, "food.handoff-directory"), owner = OwnerConstraint.OwnerPrivate(owner))

private fun preparedDirectory(context: ReadContext, requirements: List<AuthorizationRequirement>,
    result: LocalFoodHandoffDirectoryResultV2): PreparedRead = object : PreparedRead() {
    override val requirements: List<AuthorizationRequirement> = requirements.toList()
    override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
        check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (authorization, requirement) ->
            authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.isBoundTo(requirement, context.actorId)
        })
        return try { localSalesEncode(result, LocalFoodHandoffDirectoryResultV2.serializer(), 65_536) }
        catch (_: LocalSalesWireCapacityExceeded) { localSalesEncode(unavailableDirectory(
            LocalFoodHandoffDirectoryUnavailableV2.CAPACITY_REQUIRED), LocalFoodHandoffDirectoryResultV2.serializer(), 65_536) }
    }
}
private fun unavailableDirectory(reason: LocalFoodHandoffDirectoryUnavailableV2): LocalFoodHandoffDirectoryResultV2 =
    LocalFoodHandoffDirectoryResultV2(unavailable = reason)
private class HandoffCursorExpired : RuntimeException("food.handoff.cursor_expired")

private fun incomingNamespace(phase: String): String = if (phase == "INDEXED") LocalFoodHandoffProfileV2.INCOMING else LocalFoodProfileV1.CLAIM
private fun incomingPrefix(actorId: DomainId, phase: String): String = if (phase == "INDEXED") "${actorId.value}/" else "handoff/"
private suspend fun incomingCursor(view: FoodReadView, phase: String, after: String?): LocalFoodHandoffCursorV2 =
    LocalFoodHandoffCursorV2(phase, TransactionLocalHandoffRecipients.issueInboxCursor(view.handoffBorrowedView(), phase, after))

/**
 * Actual service-issued, current-actor/session routing metadata, not a public hash or a source
 * membership lookup. Resume the stored lower bound even if that locator has since disappeared.
 * Unknown/expired/evicted handles share one refresh-required response before source scans.
 * Durable offers and every current full proof still come from the same bounded database view.
 */
private suspend fun resolveIncomingCursor(view: FoodReadView, cursor: LocalFoodHandoffCursorV2): String? {
    val position = TransactionLocalHandoffRecipients.resumeInboxCursor(view.handoffBorrowedView(), cursor.phase, cursor.after)
        ?: throw HandoffCursorExpired()
    check(position.phase == cursor.phase)
    return position.after
}

private suspend fun scanIncoming(view: FoodReadView, context: ReadContext, cursor: LocalFoodHandoffCursorV2?): LocalFoodHandoffDirectoryResultV2 {
    var phase = cursor?.phase ?: "INDEXED"
    var after = cursor?.let { resolveIncomingCursor(view, it) }
    var scanned = 0
    val found = mutableListOf<LocalFoodIncomingOfferV2>()
    while (true) {
        if (scanned >= 8 || scanned > 0 && !view.handoffCandidateHeadroom())
            return LocalFoodHandoffDirectoryResultV2(incoming = found, nextIncoming = incomingCursor(view, phase, after))
        val prefix = incomingPrefix(context.actorId, phase)
        val page = view.claimKeys(incomingNamespace(phase), prefix, after, 1)
        val locator = page.entries.singleOrNull()
        if (locator == null) {
            if (phase == "LEGACY") return LocalFoodHandoffDirectoryResultV2(incoming = found)
            phase = "LEGACY"; after = null
            continue
        }
        val handoffId = DomainId(locator.key.key.removePrefix(prefix))
        check(locator.key.key == prefix + handoffId.value)
        val row = foodHandoffRowV2(view, handoffId)
        if (phase == "INDEXED") {
            check(row != null && row.envelope.offer.orderId == locator.ownerId && row.envelope.offer.recipientId == context.actorId)
            val order = foodOrder(view, locator.ownerId, includeClosed = true)
            requirePendingHandoffV2(order, row)
            appendEligibleIncoming(view, context, order, found)
        } else if (row != null) {
            check(row.envelope.offer.orderId == locator.ownerId)
            // Every v2 offer is already represented by its actual current indexed/resolved
            // record. Do not duplicate it through the permanent legacy identity namespace.
        } else {
            val order = foodOrder(view, locator.ownerId, includeClosed = true)
            val pending = order.value.handoff
            if (pending?.id == handoffId) {
                val currentOffer = checkNotNull(pending)
                check(view.claim(LocalFoodHandoffProfileV2.incoming(currentOffer.to, handoffId)) == null &&
                    view.claim(LocalFoodProfileV1.claim("accepted-handoff", handoffId)) == null)
                foodRequireLegacyPendingV2(view, order)
                if (currentOffer.to == context.actorId) appendEligibleIncoming(view, context, order, found)
            }
        }
        scanned++
        after = locator.key.key
        if (page.nextAfter == null) {
            if (phase == "LEGACY") return LocalFoodHandoffDirectoryResultV2(incoming = found)
            phase = "LEGACY"; after = null
        }
    }
}

private suspend fun appendEligibleIncoming(view: FoodReadView, context: ReadContext, order: FoodOrderCut,
    found: MutableList<LocalFoodIncomingOfferV2>) {
    val pending = checkNotNull(order.value.handoff)
    check(pending.to == context.actorId && pending.from == order.value.ownerId)
    if (order.value.closedByOperationId != null) return
    val financial = if (order.value.financialCollectionId == null) null else foodPaidHandoffCut(view, order)
    if (financial == null) check(LocalFoodHandoffPrecollection.verify(view, order.order.envelope.id, order.order.envelope.revision))
    if (TransactionLocalHandoffRecipients.current(view.handoffBorrowedView(), order.order.envelope.id, context.actorId) == null) return
    check(found.none { it.handoffId == pending.id })
    found += LocalFoodIncomingOfferV2(order.order.envelope.id, pending.id, pending.from, pending.to, order.order.envelope.revision,
        order.value.service, order.value.activity, order.value.portionIds.size, pending.offeredAt, financial?.projection())
}
