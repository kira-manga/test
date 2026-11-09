package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** One actual protected order/binding cut. Unavailable history never becomes unassigned. */
public class LocalFoodTableAssignmentReadHandler(private val stock: LocalStockRepository,
    private val directory: TransactionFoodTableAssignmentDirectorySourceV1) : MasterReadHandler {
    override val key: ReadKey = LocalFoodTableAssignmentReadCodecV1.QUERY
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodTableAssignmentReadCodecV1.encode(
        localSalesDecode(input, LocalFoodTableAssignmentQueryV1.serializer(), 2_048))

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS &&
            LocalFoodTableAssignmentReadCodecV1.encode(localSalesDecode(normalized,
                LocalFoodTableAssignmentQueryV1.serializer(), 2_048)) == normalized

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.key == key && context.scope == view.scope &&
            queryDigest(context.scope, context.actorId, key, normalized) == context.semanticDigest)
        val query = localSalesDecode(normalized, LocalFoodTableAssignmentQueryV1.serializer(), 2_048)
        val bounded = FoodReadView(commerceLoan(view, stock))
        val header = foodObserveRow(bounded, LocalFoodProfileV1.ORDER, query.orderId)
        val body = header?.envelope?.body as? FoodBodyV1.Order
        val requirement = AuthorizationRequirement(Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM), context.scope,
            ActionBinding(query.orderId, header?.envelope?.revision ?: Revision(0), context.semanticDigest, null, "food.table.assignment.read"),
            owner = OwnerConstraint.OwnerPrivate(body?.ownerId ?: context.actorId))
        val otherOwner = body != null && body.ownerId != context.actorId
        val observation = if (body == null || otherOwner) null else {
            val order = foodOrder(bounded, query.orderId, includeClosed = true)
            check(order.value.ownerId == context.actorId)
            val binding = readFoodTableAssignmentBinding(bounded, query.orderId, directory)
            LocalFoodTableAssignmentObservationV1(query.orderId, order.order.envelope.revision, order.value.ownerId,
                order.value.activity, order.value.service.channel, order.value.closedByOperationId != null,
                order.value.handoff != null, binding?.value?.binding)
        }
        val actorId = context.actorId
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(requirement)
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1)
                requireFoodTableAssignmentAuthorization(authorizations.single(), requirement, actorId)
                // ANY_OWNER permission cannot turn this dedicated owner's read into cross-owner disclosure.
                if (otherOwner) throw MasterReadAuthorizationDenied()
                val result = observation ?: throw MasterReadInvalidSelection()
                return localSalesEncode(result, LocalFoodTableAssignmentObservationV1.serializer(), 4_096)
            }
        }
    }
}
